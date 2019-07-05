package org.thp.cortex.services

import scala.concurrent.{ExecutionContext, Future}

import play.api.http.Status
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.WSClient
import play.api.mvc.RequestHeader
import play.api.{Configuration, Logger}

import akka.stream.Materializer
import javax.inject.{Inject, Singleton}
import org.thp.cortex.services.mappers.UserMapper

import org.elastic4play.services.{AuthContext, AuthSrv}
import org.elastic4play.{AuthenticationError, AuthorizationError, OAuth2Redirect}

case class OAuth2Config(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    responseType: String,
    grantType: String,
    authorizationUrl: String,
    tokenUrl: String,
    userUrl: String,
    scope: String,
    autocreate: Boolean
)

object OAuth2Config {

  def apply(configuration: Configuration): Option[OAuth2Config] =
    for {
      clientId         ← configuration.getOptional[String]("auth.oauth2.clientId")
      clientSecret     ← configuration.getOptional[String]("auth.oauth2.clientSecret")
      redirectUri      ← configuration.getOptional[String]("auth.oauth2.redirectUri")
      responseType     ← configuration.getOptional[String]("auth.oauth2.responseType")
      grantType        ← configuration.getOptional[String]("auth.oauth2.grantType")
      authorizationUrl ← configuration.getOptional[String]("auth.oauth2.authorizationUrl")
      userUrl          ← configuration.getOptional[String]("auth.oauth2.userUrl")
      tokenUrl         ← configuration.getOptional[String]("auth.oauth2.tokenUrl")
      scope            ← configuration.getOptional[String]("auth.oauth2.scope")
      autocreate = configuration.getOptional[Boolean]("auth.sso.autocreate").getOrElse(false)
    } yield OAuth2Config(clientId, clientSecret, redirectUri, responseType, grantType, authorizationUrl, tokenUrl, userUrl, scope, autocreate)
}

@Singleton
class OAuth2Srv(
    ws: WSClient,
    userSrv: UserSrv,
    ssoMapper: UserMapper,
    oauth2Config: Option[OAuth2Config],
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer
) extends AuthSrv {

  @Inject() def this(ws: WSClient, ssoMapper: UserMapper, userSrv: UserSrv, configuration: Configuration, ec: ExecutionContext, mat: Materializer) =
    this(ws, userSrv, ssoMapper, OAuth2Config(configuration), ec, mat)

  override val name: String = "oauth2"
  private val logger        = Logger(getClass)

  val Oauth2TokenQueryString = "code"

  private def withOAuth2Config[A](body: OAuth2Config ⇒ Future[A]): Future[A] =
    oauth2Config.fold[Future[A]](Future.failed(AuthenticationError("OAuth2 not configured properly")))(body)

  override def authenticate()(implicit request: RequestHeader): Future[AuthContext] =
    withOAuth2Config { cfg ⇒
      request
        .queryString
        .get(Oauth2TokenQueryString)
        .flatMap(_.headOption)
        .fold(createOauth2Redirect(cfg.clientId)) { code ⇒
          getAuthTokenAndAuthenticate(cfg.clientId, code)
        }
    }

  private def getAuthTokenAndAuthenticate(clientId: String, code: String)(implicit request: RequestHeader): Future[AuthContext] = {
    logger.debug("Getting user token with the code from the response!")
    withOAuth2Config { cfg ⇒
      val acceptHeader = "Accept" → cfg.responseType
      ws.url(cfg.tokenUrl)
        .addHttpHeaders(acceptHeader)
        .post(
          Map(
            "code"          → code,
            "grant_type"    → cfg.grantType,
            "client_secret" → cfg.clientSecret,
            "redirect_uri"  → cfg.redirectUri,
            "client_id"     → clientId
          )
        )
        .recoverWith {
          case error ⇒
            logger.error(s"Token verification failure", error)
            Future.failed(AuthenticationError("Token verification failure"))
        }
        .flatMap { r ⇒
          r.status match {
            case Status.OK ⇒
              val accessToken = (r.json \ "access_token").asOpt[String].getOrElse("")
              val authHeader  = "Authorization" → s"bearer $accessToken"
              ws.url(cfg.userUrl)
                .addHttpHeaders(authHeader)
                .get()
                .flatMap { userResponse ⇒
                  if (userResponse.status != Status.OK) {
                    Future.failed(AuthenticationError(s"unexpected response from server: ${userResponse.status} ${userResponse.body}"))
                  } else {
                    val response = userResponse.json.asInstanceOf[JsObject]
                    getOrCreateUser(response, authHeader)
                  }
                }
            case _ ⇒
              logger.error(s"unexpected response from server: ${r.status} ${r.body}")
              Future.failed(AuthenticationError("unexpected response from server"))
          }
        }
    }
  }

  private def getOrCreateUser(response: JsValue, authHeader: (String, String))(implicit request: RequestHeader): Future[AuthContext] =
    withOAuth2Config { cfg ⇒
      ssoMapper.getUserFields(response, Some(authHeader)).flatMap { userFields ⇒
        val userId = userFields.getString("login").getOrElse("")
        userSrv
          .get(userId)
          .flatMap(user ⇒ {
            userSrv.getFromUser(request, user, name)
          })
          .recoverWith {
            case authErr: AuthorizationError ⇒ Future.failed(authErr)
            case _ if cfg.autocreate ⇒
              userSrv.inInitAuthContext { implicit authContext ⇒
                userSrv
                  .create(userFields)
                  .flatMap(user ⇒ {
                    userSrv.getFromUser(request, user, name)
                  })
              }
          }
      }
    }

  private def createOauth2Redirect(clientId: String): Future[AuthContext] =
    withOAuth2Config { cfg ⇒
      val queryStringParams = Map[String, Seq[String]](
        "scope"         → Seq(cfg.scope),
        "response_type" → Seq(cfg.responseType),
        "redirect_uri"  → Seq(cfg.redirectUri),
        "client_id"     → Seq(clientId)
      )
      Future.failed(OAuth2Redirect(cfg.authorizationUrl, queryStringParams))
    }
}
