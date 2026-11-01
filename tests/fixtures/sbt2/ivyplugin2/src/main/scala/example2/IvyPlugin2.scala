package example2

import sbt.*

object IvyPlugin2 extends AutoPlugin {
  object autoImport {
    val ivyPlugin2Message = settingKey[String]("A message from an sbt 2 plugin published Ivy-style.")
  }
  import autoImport.*

  override def trigger = allRequirements
  override def projectSettings = Seq(ivyPlugin2Message := "published Ivy-style on sbt 2")
}
