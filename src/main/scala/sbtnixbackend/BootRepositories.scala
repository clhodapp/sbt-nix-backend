package sbtnixbackend

/** The archived sbt 0.13 artifacts use Ivy, including the launcher itself. */
object BootRepositories {
  val archive = "https://scala.jfrog.io/artifactory/api/download/ivy-releases/"
  val central = CentralMirror.Central
  val pattern = "[organization]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext]"
  def legacy(version: String): Boolean = version.startsWith("0.13.")
  def launcher(version: String): String =
    if (legacy(version)) archive + "org.scala-sbt/sbt-launch/" + version + "/jars/sbt-launch.jar"
    else "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/" + version + "/sbt-launch-" + version + ".jar"
  def contents(mirror: Option[String]): String = {
    def url(u: String): String = mirror.flatMap(IvyMirror.rewritePrefix(u, _)).getOrElse(u)
    "[repositories]\nlocal\nmaven-central: " + url(central) + "\nsbt-ivy: " + url(archive) + ", " + pattern + "\n"
  }
}
