package sbtnixbackend

import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import java.util.Arrays

import sbt.librarymanagement.{MavenRepository, Resolver}

/** A mirror of Maven Central that the lock run downloads through.
  *
  * Maven Central can refuse a large lock run with HTTP 429. When
  * [[Variable]] names a mirror that serves the same files, such as
  * https://maven-central.storage-download.googleapis.com/maven2/, the lock
  * run downloads every file of Maven Central from the mirror: coursier
  * resolves the Maven repositories at the root of Central from the mirror,
  * the sbt launcher boots from it, and the Ivy mirror and the lock run's
  * own downloads fetch from it. After the run, [[canonicalize]] moves each
  * file from the coursier path of its mirror URL to the coursier path of
  * its Central URL, so the lockfile records the Central URLs and the hashes
  * of the same bytes, and a Nix build fetches the files from Central.
  */
object CentralMirror {

  /** The environment variable that names the mirror. */
  val Variable = "SBT_NIX_BACKEND_MAVEN_CENTRAL_MIRROR"

  /** The root of Maven Central. */
  val Central = "https://repo1.maven.org/maven2/"

  /** The root of Google's mirror of Maven Central, which every lockfile
    * records as a mirror of each file of Central, so that a Nix fetch that
    * Central refuses (HTTP 429) falls back to it.
    */
  val Google = "https://maven-central.storage-download.googleapis.com/maven2/"

  /** The mirrors the lockfile records for `url`. */
  def recordedMirrors(url: String): Vector[String] =
    if (url.startsWith(Central)) Vector(Google + url.stripPrefix(Central)) else Vector.empty

  private def withSlash(url: String) = if (url.endsWith("/")) url else url + "/"

  /** The mirror [[Variable]] names in `env`, with a trailing slash. */
  def fromEnv(env: Map[String, String]): Option[String] =
    env.get(Variable).map(_.trim).filter(_.nonEmpty).map(withSlash)

  /** The URL to download `url` from: on the mirror for a file of Central. */
  def downloadUrl(url: String, mirror: Option[String]): String =
    mirror match {
      case Some(m) if url.startsWith(Central) => m + url.stripPrefix(Central)
      case _                                  => url
    }

  /** `r` with the mirror as its root when it is a Maven repository at the
    * root of Central.
    */
  def resolver(r: Resolver, mirror: String): Resolver =
    r match {
      case m: MavenRepository if withSlash(m.root) == Central => MavenRepository(m.name, mirror)
      case other                                              => other
    }

  /** A repositories file for the sbt launcher, which boots sbt from the
    * mirror and from the Maven repository of sbt.
    */
  def launcherRepositories(mirror: String): String =
    s"""[repositories]
       |  local
       |  maven-central: $mirror
       |  sbt-maven-releases: https://repo.scala-sbt.org/scalasbt/maven-releases/, bootOnly
       |""".stripMargin

  /** Moves every file in the coursier cache `cache` from the coursier path
    * of its mirror URL to the coursier path of its Central URL. A file
    * already at the Central path stays when it has the same bytes.
    */
  def canonicalize(cache: File, mirror: String): Unit = {
    // The directory of a repository root in the cache: relativePath of a
    // URL that ends in a slash names the directory listing in it.
    def directory(root: String) = new File(cache, CachePaths.relativePath(root.stripSuffix("/")))
    val from = directory(mirror)
    val to = directory(Central)
    def move(src: File, dst: File): Unit =
      if (src.isDirectory) {
        Option(src.listFiles()).getOrElse(Array.empty[File]).foreach(f => move(f, new File(dst, f.getName)))
        src.delete()
      } else if (dst.isFile) {
        if (!Arrays.equals(Files.readAllBytes(src.toPath), Files.readAllBytes(dst.toPath)) && !src.getName.startsWith("."))
          throw new LockGeneration.LockFailed(s"the mirror and Maven Central serve different bytes for $dst")
        src.delete()
      } else {
        dst.getParentFile.mkdirs()
        Files.move(src.toPath, dst.toPath, StandardCopyOption.REPLACE_EXISTING)
      }
    if (from.exists) move(from, to)
  }
}
