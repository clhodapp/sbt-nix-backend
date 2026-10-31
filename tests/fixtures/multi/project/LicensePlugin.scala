import sbt._
import sbt.Keys._

/** Stands in for a third-party plugin that downloads a file outside
  * coursier, and bridges itself to sbt-nix-backend without depending on it.
  *
  * It declares the URL in a task key with the same name and type as
  * sbt-nix-backend's `nixNetworkDependencies`, which sbt treats as the same
  * key. When the system property `sbt.nix.backend.coursierCache` is set (in
  * a Nix build, in the lock run, and interactively with a lockfile), it
  * reads the file from that coursier cache, where sbt-nix-backend put it at
  * the path coursier would use; otherwise it downloads the file itself.
  */
object LicensePlugin extends AutoPlugin {
  object autoImport {
    val licenseUrl = settingKey[String]("A license text to bundle as a resource.")
  }
  import autoImport._

  val nixNetworkDependencies = taskKey[Seq[String]]("URLs this project's build downloads outside coursier.")

  private def cachePath(url: String): String = url.replaceFirst("://", "/")

  override def projectSettings: Seq[Setting[_]] = Seq(
    nixNetworkDependencies := (nixNetworkDependencies ?? Nil).value :+ licenseUrl.value,
    Compile / resourceGenerators += Def.task {
      val url = licenseUrl.value
      val out = (Compile / resourceManaged).value / "license.txt"
      sys.props.get("sbt.nix.backend.coursierCache") match {
        case Some(cache) =>
          val cached = file(cache) / cachePath(url)
          if (!cached.exists()) sys.error(s"$url is not in the coursier cache at $cached")
          IO.copyFile(cached, out)
        case None =>
          if (!out.exists()) IO.transfer(new java.net.URI(url).toURL.openStream(), out)
      }
      Seq(out)
    }.taskValue
  )
}
