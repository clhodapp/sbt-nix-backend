package sbtnixbackend

import java.io.File
import sbt._
import sbt.Keys._
import lmcoursier.definitions.CachePolicy
import NixBackendPlugin.Mode
import NixBackendPlugin.autoImport._

/** Coursier integration shared by sbt 1 and sbt 2; sbt 0.13 uses Ivy. */
object ResolutionSupport {
  val buildSettings: Seq[Setting[_]] = Seq(
    // The sbt wrapper boots sbt with COURSIER_CACHE set to the read-only
    // tree of locked downloads, and names the user's own cache in
    // SBT_NIX_BACKEND_USER_COURSIER_CACHE. Every resolution the lock does
    // not cover (source jars for an IDE, a build whose lock is stale)
    // goes to the user's cache.
    csrCacheDirectory := (sys.env.get("SBT_NIX_BACKEND_USER_COURSIER_CACHE") match {
      case Some(dir) if dir.nonEmpty => new File(dir)
      case Some(_)                   => defaultUserCoursierCache
      case None                      => csrCacheDirectory.value
    })
  )

  val projectSettings: Seq[Setting[_]] = Seq(
    // Feature 3: interactive resolution reads the locked downloads, offline.
    csrConfiguration := Compat.uncached(withLockedCache(csrConfiguration).value),
    scalaCompilerBridgeScope / csrConfiguration :=
      Compat.uncached(withLockedCache(scalaCompilerBridgeScope / csrConfiguration).value),
    // The sources and javadoc jars an editor asks for resolve through
    // configurations of their own, which sbt does not derive from the one
    // above.
    updateClassifiers / csrConfiguration :=
      Compat.uncached(withLockedCache(updateClassifiers / csrConfiguration).value),
    updateSbtClassifiers / csrConfiguration :=
      Compat.uncached(withLockedCache(updateSbtClassifiers / csrConfiguration).value)
  )

  private def withLockedCache(
      key: TaskKey[lmcoursier.CoursierConfiguration]
  ): Def.Initialize[Task[lmcoursier.CoursierConfiguration]] =
    Def.taskDyn {
      val conf = key.value
      Mode.current match {
        case Mode.Sandbox =>
          Def.task(conf.withCache(Some(Mode.coursierCacheFromEnv)).withCachePolicies(Vector(CachePolicy.LocalOnly)))
        case Mode.Lock =>
          // The lock run downloads the files of Maven Central from a mirror
          // when one is named; LockGeneration records their Central URLs.
          val resolvers =
            CentralMirror.fromEnv(sys.env).fold(conf.resolvers)(m => conf.resolvers.map(CentralMirror.resolver(_, m)))
          Def.task(conf.withCache(Some(Mode.coursierCacheFromEnv)).withResolvers(resolvers))
        case Mode.Interactive =>
          if ((ThisBuild / nixBackend).value)
            Def.task(
              conf
                .withCache(Some((ThisBuild / nixCoursierCache).value))
                .withCachePolicies(Vector(CachePolicy.LocalOnly))
            )
          else Def.task(conf)
      }
    }

  /** Coursier's default cache location when COURSIER_CACHE is unset (coursier.paths.CoursierPaths): the XDG cache on
    * Linux, Library/Caches on macOS.
    */
  private def defaultUserCoursierCache: File = {
    val home = new File(sys.props("user.home"))
    if (sys.props("os.name").toLowerCase.contains("mac")) new File(home, "Library/Caches/Coursier/v1")
    else
      new File(
        sys.env.get("XDG_CACHE_HOME").filter(_.nonEmpty).map(new File(_)).getOrElse(new File(home, ".cache")),
        "coursier/v1"
      )
  }

  def runtimeCompletion(
      mirror: Def.Initialize[Task[Option[IvyMirror.Setup]]]
  ): Def.Initialize[Task[RuntimeCompletion]] = Def.task {
    val base = csrConfiguration.value
    val settings = Compat.ivySettingsResolvers(mirror).value
    val conf = base.withResolvers(base.resolvers ++ settings.filterNot(base.resolvers.contains))
    new RuntimeCompletion {
      def apply(repository: File, coordinates: RuntimeRepository.Coordinates, log: sbt.util.Logger): Vector[String] =
        RuntimeResolution.complete(repository, coordinates, conf, conf.cache.getOrElse(Mode.coursierCacheFromEnv), log)
    }
  }
}
