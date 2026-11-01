package ivyexample

import sbt._

object IvyPlugin extends AutoPlugin {
  object autoImport {
    val ivyPluginMessage = settingKey[String]("A message from a plugin published Ivy-style.")
  }
  import autoImport._

  override def trigger = allRequirements
  override def projectSettings = Seq(ivyPluginMessage := "published Ivy-style")
}
