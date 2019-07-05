package org.thp.cortex.services

import scala.concurrent.{ExecutionContext, Future}

import play.api.Logger
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.json.{JsObject, Json}

import org.elastic4play.services.{AuditOperation, AuxSrv, MigrationEvent}

trait StreamMessageGroup[M] {
  def :+(message: M): StreamMessageGroup[M]
  val isReady: Boolean
  def makeReady: StreamMessageGroup[M]
  def toJson(implicit ec: ExecutionContext): Future[JsObject]
}

case class AuditOperationGroup(
    auxSrv: AuxSrv,
    operation: AuditOperation,
    auditedAttributes: JsObject,
    obj: Future[JsObject],
    summary: Map[String, Map[String, Int]],
    isReady: Boolean
) extends StreamMessageGroup[AuditOperation] {

  def :+(operation: AuditOperation): AuditOperationGroup = {
    val modelSummary = summary.getOrElse(operation.entity.model.modelName, Map.empty[String, Int])
    val actionCount  = modelSummary.getOrElse(operation.action.toString, 0)
    copy(
      summary = summary + (operation.entity.model.modelName → (modelSummary +
        (operation.action.toString                          → (actionCount + 1))))
    )
  }

  def makeReady: AuditOperationGroup = copy(isReady = true)

  def toJson(implicit ec: ExecutionContext): Future[JsObject] = obj.map { o ⇒
    Json.obj(
      "base" → Json.obj(
        "objectId"   → operation.entity.id,
        "objectType" → operation.entity.model.modelName,
        "operation"  → operation.action,
        "startDate"  → operation.date,
        "rootId"     → operation.entity.routing,
        "user"       → operation.authContext.userId,
        "createdBy"  → operation.authContext.userId,
        "createdAt"  → operation.date,
        "requestId"  → operation.authContext.requestId,
        "object"     → o,
        "details"    → auditedAttributes
      ),
      "summary" → summary
    )
  }
}

object AuditOperationGroup {
  private[AuditOperationGroup] lazy val logger = Logger(classOf[AuditOperationGroup])

  def apply(auxSrv: AuxSrv, operation: AuditOperation)(implicit ec: ExecutionContext): AuditOperationGroup = {
    val auditedAttributes = JsObject {
      operation
        .details
        .fields
        .map {
          case (name, value) ⇒
            val baseName = name.split("\\.").head
            (name, value, operation.entity.model.attributes.find(_.attributeName == baseName))
        }
        .collect { case (name, value, Some(attr)) if !attr.isUnaudited ⇒ (name, value) }
    }
    val obj = auxSrv(operation.entity, 10, withStats = false, removeUnaudited = true)
      .recover {
        case error ⇒
          logger.error("auxSrv fails", error)
          JsObject.empty
      }
    new AuditOperationGroup(
      auxSrv,
      operation,
      auditedAttributes,
      obj,
      Map(operation.entity.model.modelName → Map(operation.action.toString → 1)),
      false
    )
  }
}

case class MigrationEventGroup(tableName: String, current: Long, total: Long) extends StreamMessageGroup[MigrationEvent] {

  def :+(event: MigrationEvent): MigrationEventGroup = {
    assert(event.modelName == tableName)
    if (current < event.current)
      copy(current = event.current, total = event.total)
    else this
  }

  val isReady                        = true
  def makeReady: MigrationEventGroup = this

  def toJson(implicit ec: ExecutionContext): Future[JsObject] =
    Future.successful(
      Json.obj("base" → Json.obj("rootId" → current, "objectType" → "migration", "tableName" → tableName, "current" → current, "total" → total))
    )
}

object MigrationEventGroup {
  def apply(event: MigrationEvent) = new MigrationEventGroup(event.modelName, event.current, event.total)
  def endOfMigration               = new MigrationEventGroup("end", 0, 0)
}
