import Common.{stableVersion, betaVersion, snapshotVersion}

linuxPackageMappings in Debian += packageMapping(file("LICENSE") -> "/usr/share/doc/cortex/copyright").withPerms("644")
version in Debian := {
  version.value match {
    case stableVersion(_, _) => version.value
    case betaVersion(v1, v2) => v1 + "-0.1RC" + v2
    case snapshotVersion(_, _) => version.value + "-SNAPSHOT"
    case _ => sys.error("Invalid version: " + version.value)
  }
}
debianPackageRecommends := Seq("elasticsearch")
debianPackageDependencies += "java8-runtime | java8-runtime-headless"
maintainerScripts in Debian := maintainerScriptsFromDirectory(
  baseDirectory.value / "package" / "debian",
  Seq(DebianConstants.Postinst, DebianConstants.Prerm, DebianConstants.Postrm)
)
linuxEtcDefaultTemplate in Debian := (baseDirectory.value / "package" / "etc_default_cortex").asURL
linuxMakeStartScript in Debian := None
