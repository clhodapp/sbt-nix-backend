package sbtnixbackend
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import lmcoursier.{CoursierConfiguration, CoursierDependencyResolution}
import lmcoursier.definitions.CacheLogger
import sbt.librarymanagement._
import sbt.util.Logger
import RuntimeRepository._

/** Records every URL a coursier resolution reads, whether it came from the cache or the network. Coursier calls
  * `foundLocally` for each cache hit and `downloadedArtifact` for each download (coursier.cache.internal.Downloader); a
  * resolution with a new logger instance never takes lm-coursier's in-memory resolution cache, whose key holds the
  * logger.
  */
final class RecordingCacheLogger extends CacheLogger {
  private val seen = ConcurrentHashMap.newKeySet[String]()
  override def foundLocally(url: String): Unit = { seen.add(url); () }
  override def downloadedArtifact(url: String, success: Boolean): Unit =
    if (success) { seen.add(url); () }
  def urls: Vector[String] = seen.asScala.toVector.sorted
}

object RuntimeResolution {

  /** Resolves a consumer of `coordinates` and copies what it read into `repository`. `conf` is the project's coursier
    * configuration; `cacheDir` is the coursier cache that configuration reads. Returns the repository-relative paths it
    * copied.
    */
  def complete(
      repository: File,
      coordinates: Coordinates,
      conf: CoursierConfiguration,
      cacheDir: File,
      log: Logger
  ): Vector[String] = {
    val own = IvyLayout.resolvers("sbt-nix-backend-repository", repository)
    val ownNames = own.map(_.name).toSet
    val recorder = new RecordingCacheLogger
    val others = conf.resolvers.filterNot(r => ownNames(r.name))
    val resolution = CoursierDependencyResolution(
      conf
        .withResolvers(own ++ others)
        .withLogger(Some(recorder))
        // The project's own descriptor would otherwise come from the build
        // rather than from the POM or ivy.xml just published.
        .withInterProjectDependencies(Vector.empty)
        .withExtraProjects(Vector.empty)
    )
    val dependency =
      ModuleID(coordinates.organization, coordinates.name, coordinates.version).withExtraAttributes(
        coordinates.attributes
      )
    val consumer = ModuleDescriptorConfiguration(
      ModuleID("sbt-nix-backend", "consumer-of-" + coordinates.name, "0"),
      ModuleInfo("consumer-of-" + coordinates.name)
    ).withDependencies(Vector(dependency))
      .withConfigurations(Vector(Configurations.Compile, Configurations.Runtime))
    val descriptor = resolution.moduleDescriptor(consumer)
    val report = resolution.update(descriptor, UpdateConfiguration(), UnresolvedWarningConfiguration(), log) match {
      case Left(warning) => throw warning.resolveException
      case Right(r)      => r
    }
    RuntimeRepository.copyUrls(repository, coordinates, others, cacheDir, recorder.urls, report, log)
  }
}
