package sbtnixbackend

import java.io.File
import java.net.URI
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters._

import lmcoursier.{CoursierConfiguration, CoursierDependencyResolution}
import lmcoursier.definitions.CacheLogger
import sbt.librarymanagement._
import sbt.util.Logger

/** Records every URL a coursier resolution reads, whether it came from the
  * cache or the network. Coursier calls `foundLocally` for each cache hit
  * and `downloadedArtifact` for each download (coursier.cache.internal.Downloader);
  * a resolution with a new logger instance never takes lm-coursier's
  * in-memory resolution cache, whose key holds the logger.
  */
final class RecordingCacheLogger extends CacheLogger {
  private val seen = ConcurrentHashMap.newKeySet[String]()
  override def foundLocally(url: String): Unit = { seen.add(url); () }
  override def downloadedArtifact(url: String, success: Boolean): Unit =
    if (success) { seen.add(url); () }
  def urls: Vector[String] = seen.asScala.toVector.sorted
}

/** Feature 4: a Maven repository holding a project's published artifacts
  * and everything a consumer needs to resolve and run them.
  *
  * The repository is complete by construction: after the project and its
  * internal dependencies are published into it, a consumer of the project's
  * coordinates is resolved against it and the build's own resolvers with a
  * [[RecordingCacheLogger]], and every file that resolution read from
  * elsewhere (POMs, parent POMs, imported BOMs, jars) is copied in at its
  * repository-relative path.
  */
object RuntimeRepository {

  final case class Coordinates(organization: String, name: String, version: String)

  final class NotRepresentable(message: String) extends RuntimeException(message)

  /** A repository root: a remote URL prefix, or a local directory. */
  private sealed trait Root
  private final case class RemoteRoot(prefix: String) extends Root
  private final case class LocalRoot(dir: java.nio.file.Path) extends Root

  /** The groupId, artifactId and version a POM declares for itself. */
  def pomCoordinates(pom: File): Coordinates = {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val doc = factory.newDocumentBuilder().parse(pom)
    val root = doc.getDocumentElement
    def child(name: String): Option[String] = {
      val nodes = root.getChildNodes
      (0 until nodes.getLength).map(nodes.item).collectFirst {
        case e: org.w3c.dom.Element if e.getTagName == name => e.getTextContent.trim
      }
    }
    def parent(name: String): Option[String] = {
      val nodes = root.getElementsByTagName("parent")
      if (nodes.getLength == 0) None
      else {
        val p = nodes.item(0).getChildNodes
        (0 until p.getLength).map(p.item).collectFirst {
          case e: org.w3c.dom.Element if e.getTagName == name => e.getTextContent.trim
        }
      }
    }
    def need(name: String): String =
      child(name).orElse(parent(name)).getOrElse(throw new NotRepresentable(s"$pom declares no $name"))
    Coordinates(need("groupId"), child("artifactId").getOrElse(throw new NotRepresentable(s"$pom declares no artifactId")), need("version"))
  }

  /** Resolves a consumer of `coordinates` and copies what it read into
    * `repository`. `conf` is the project's coursier configuration;
    * `cacheDir` is the coursier cache that configuration reads. Returns the
    * repository-relative paths it copied.
    */
  def complete(
      repository: File,
      coordinates: Coordinates,
      conf: CoursierConfiguration,
      cacheDir: File,
      log: Logger
  ): Vector[String] = {
    val own = MavenCache("sbt-nix-backend-repository", repository)
    val recorder = new RecordingCacheLogger
    val resolvers = own +: conf.resolvers.filterNot(_.name == own.name)
    val resolution = CoursierDependencyResolution(
      conf
        .withResolvers(resolvers)
        .withLogger(Some(recorder))
        // The project's own descriptor would otherwise come from the build
        // rather than from the POM just published.
        .withInterProjectDependencies(Vector.empty)
        .withExtraProjects(Vector.empty)
    )
    val dependency = ModuleID(coordinates.organization, coordinates.name, coordinates.version)
    val consumer = ModuleDescriptorConfiguration(
      ModuleID("sbt-nix-backend", "consumer-of-" + coordinates.name, "0"),
      ModuleInfo("consumer-of-" + coordinates.name)
    ).withDependencies(Vector(dependency))
      .withConfigurations(Vector(Configurations.Compile, Configurations.Runtime))
    val descriptor = resolution.moduleDescriptor(consumer)
    resolution.update(descriptor, UpdateConfiguration(), UnresolvedWarningConfiguration(), log) match {
      case Left(warning) => throw warning.resolveException
      case Right(_)      => ()
    }
    val roots = resolverRoots(resolvers.filterNot(_.name == own.name))
    val ownDir = normalize(repository)
    val copied = Vector.newBuilder[String]
    recorder.urls.foreach { url =>
      val local = if (url.startsWith("file:")) Some(normalize(new File(new URI(url)))) else None
      if (!local.exists(_.startsWith(ownDir))) {
        val relative = roots
          .collectFirst {
            case RemoteRoot(prefix) if local.isEmpty && url.startsWith(prefix) => url.substring(prefix.length)
            case LocalRoot(dir) if local.exists(_.startsWith(dir)) =>
              dir.relativize(local.get).toString.replace(File.separatorChar, '/')
          }
          .getOrElse(
            throw new NotRepresentable(
              s"$url was read while resolving ${coordinates.name}, but it does not come from a Maven repository; " +
                "a Maven-layout repository cannot hold it"
            )
          )
        val source = local.map(_.toFile).getOrElse(new File(cacheDir, CachePaths.relativePath(url)))
        // Coursier reports checksum URLs it satisfied from header checksums
        // without a file at the URL's own cache path; the repository does
        // not need them.
        if (source.isFile) {
          val target = new File(repository, relative)
          if (!target.exists()) {
            target.getParentFile.mkdirs()
            Files.copy(source.toPath, target.toPath)
          }
          copied += relative
        } else log.debug(s"[sbt-nix-backend] $url has no file in the cache; skipped")
      }
    }
    copied.result()
  }

  private def normalize(f: File): java.nio.file.Path = f.getAbsoluteFile.toPath.normalize

  /** The root of each Maven repository, longest first so a nested root wins. */
  private def resolverRoots(resolvers: Seq[Resolver]): Vector[Root] =
    resolvers.toVector
      .collect {
        case m: MavenCache => LocalRoot(normalize(m.rootFile))
        case m: MavenRepository if m.root.startsWith("file:") =>
          LocalRoot(normalize(new File(new URI(m.root))))
        case m: MavenRepository => RemoteRoot(if (m.root.endsWith("/")) m.root else m.root + "/")
      }
      .distinct
      .sortBy {
        case RemoteRoot(p) => -p.length
        case LocalRoot(d)  => -d.toString.length
      }
}
