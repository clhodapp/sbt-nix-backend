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

/** Feature 4: a repository holding a project's published artifacts and
  * everything a consumer needs to resolve and run them.
  *
  * The repository is complete by construction: after the project and its
  * internal dependencies are published into it, a consumer of the project's
  * coordinates is resolved against it and the build's own resolvers with a
  * [[RecordingCacheLogger]], and every file that resolution read from
  * elsewhere (POMs, parent POMs, imported BOMs, Ivy descriptors, jars) is
  * copied in. A file from a Maven repository keeps its path; a file from an
  * Ivy repository goes to its path in the standard Ivy layout (see
  * [[IvyLayout]]).
  */
object RuntimeRepository {

  /** A module's coordinates; `attributes` are its extra attributes, which
    * an sbt plugin published Ivy-style needs to be found.
    */
  final case class Coordinates(
      organization: String,
      name: String,
      version: String,
      attributes: Map[String, String] = Map.empty
  )

  final class NotRepresentable(message: String) extends RuntimeException(message)

  /** Where a repository is: a remote URL prefix, or a local directory. */
  private sealed trait Base
  private final case class RemoteBase(prefix: String) extends Base
  private final case class LocalBase(dir: java.nio.file.Path) extends Base

  /** A repository a resolution read from: a Maven repository, or the
    * patterns of an Ivy repository below one base, relative to it.
    */
  private sealed trait Root { def base: Base }
  private final case class MavenRoot(base: Base) extends Root
  private final case class IvyRoot(
      base: Base,
      ivyPatterns: Vector[String],
      artifactPatterns: Vector[String],
      mavenCompatible: Boolean
  ) extends Root {
    def standard: Boolean =
      !mavenCompatible &&
        ivyPatterns.forall(IvyLayout.patterns.ivyPatterns.contains) &&
        artifactPatterns.forall(IvyLayout.patterns.artifactPatterns.contains)
  }

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
      ModuleID(coordinates.organization, coordinates.name, coordinates.version).withExtraAttributes(coordinates.attributes)
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
    val roots = resolverRoots(others)
    val ownDir = normalize(repository)
    lazy val modules = reportedModules(report)
    val copied = Vector.newBuilder[String]
    recorder.urls.foreach { url =>
      val local = if (url.startsWith("file:")) Some(normalize(new File(new URI(url)))) else None
      if (!local.exists(_.startsWith(ownDir)) && !url.endsWith("/")) {
        val found = first(roots.iterator.flatMap(root => relativeTo(root.base, url, local).map(root -> _)))
          .getOrElse(throw notRepresentable(url, coordinates, "it comes from no Maven or Ivy repository of the build"))
        val relative = found match {
          case (_: MavenRoot, rel)                 => rel
          case (r: IvyRoot, rel) if r.standard => rel
          case (r: IvyRoot, rel) =>
            standardPath(r, rel, modules).getOrElse(
              throw notRepresentable(url, coordinates, "no module or artifact of the resolution has it at that path")
            )
        }
        val source = local.map(_.toFile).getOrElse(new File(cacheDir, CachePaths.relativePath(url)))
        // Coursier reports checksum URLs it satisfied from header checksums
        // without a file at the URL's own cache path; the repository does
        // not need them.
        if (source.isFile) {
          val target = new File(repository, relative)
          if (!target.exists()) {
            target.getParentFile.mkdirs()
            Files.copy(source.toPath, target.toPath)
          } else if (!java.util.Arrays.equals(Files.readAllBytes(target.toPath), Files.readAllBytes(source.toPath)))
            throw notRepresentable(url, coordinates, s"another file of the resolution is at its path $relative")
          copied += relative
        } else log.debug(s"[sbt-nix-backend] $url has no file in the cache; skipped")
      }
    }
    copied.result().distinct
  }

  private def notRepresentable(url: String, coordinates: Coordinates, why: String) =
    new NotRepresentable(s"$url was read while resolving ${coordinates.name}, but the repository cannot hold it: $why")

  private def normalize(f: File): java.nio.file.Path = f.getAbsoluteFile.toPath.normalize

  /** The path of `url` below `base`, if it is below it. */
  private def relativeTo(base: Base, url: String, local: Option[java.nio.file.Path]): Option[String] = base match {
    case RemoteBase(prefix) if local.isEmpty && url.startsWith(prefix) => Some(url.substring(prefix.length))
    case LocalBase(dir) if local.exists(_.startsWith(dir)) =>
      Some(dir.relativize(local.get).toString.replace(File.separatorChar, '/'))
    case _ => None
  }

  /** A module the resolution reported, with its artifacts. */
  private final case class Reported(module: ModuleID, artifacts: Vector[Artifact])

  /** Every module of `report`, evicted ones included: a consumer's
    * resolution reads the descriptors of the versions it evicts too.
    */
  private def reportedModules(report: UpdateReport): Vector[Reported] =
    report.configurations.toVector
      .flatMap(c => c.modules ++ c.details.flatMap(_.modules))
      .map(m => Reported(m.module, m.artifacts.map(_._1) ++ m.missingArtifacts))
      .distinct

  private val checksumSuffixes = Vector(".sha1", ".md5", ".sha256", ".sha512")

  /** The standard Ivy path of the file at `rel` below an Ivy root with
    * other patterns: the module and artifact whose tokens render one of the
    * root's patterns to `rel`, rendered with the standard pattern. A
    * checksum file follows the file it checks.
    */
  private def standardPath(root: IvyRoot, rel: String, modules: Vector[Reported]): Option[String] = {
    val suffix = checksumSuffixes.find(rel.endsWith).getOrElse("")
    val file = rel.stripSuffix(suffix)
    val standardIvy = IvyLayout.patterns.ivyPatterns.head
    val standardArtifact = IvyLayout.patterns.artifactPatterns.head
    def matching(patterns: Vector[String], standard: String, tokens: Boolean => Map[String, String]): Option[String] =
      if (patterns.exists(p => IvyLayout.render(p, tokens(root.mavenCompatible)).contains(file)))
        IvyLayout.render(standard, tokens(false))
      else None
    first(modules.iterator.flatMap { case Reported(m, artifacts) =>
      def moduleTokens(mavenCompatible: Boolean) =
        IvyLayout.moduleTokens(m.organization, m.name, m.revision, m.extraAttributes, mavenCompatible) ++
          m.branchName.map("branch" -> _)
      matching(root.ivyPatterns, standardIvy, mc => moduleTokens(mc) ++ IvyLayout.descriptorTokens).iterator ++
        artifacts.iterator.flatMap { a =>
          matching(
            root.artifactPatterns,
            standardArtifact,
            mc => moduleTokens(mc) ++ IvyLayout.artifactTokens(a.name, a.`type`, a.extension, a.classifier)
          )
        }
    }).map(_ + suffix)
  }

  private def first[A](it: Iterator[A]): Option[A] = if (it.hasNext) Some(it.next()) else None

  /** Splits an absolute Ivy pattern at the last `/` before its first token
    * or optional part: the base, and the pattern relative to it.
    */
  private def splitPattern(pattern: String): (String, String) = {
    val first = Seq(pattern.indexOf('['), pattern.indexOf('(')).filter(_ >= 0).reduceOption(_ min _).getOrElse(pattern.length)
    val cut = pattern.lastIndexOf('/', first) + 1
    (pattern.substring(0, cut), pattern.substring(cut))
  }

  /** The repositories of `resolvers`, the deepest base first so a nested
    * repository wins.
    */
  private def resolverRoots(resolvers: Seq[Resolver]): Vector[Root] = {
    def ivyRoots(patterns: Patterns, base: String => Base): Vector[Root] = {
      val ivy = patterns.ivyPatterns.map(splitPattern)
      val artifact = patterns.artifactPatterns.map(splitPattern)
      (ivy ++ artifact).map(_._1).distinct.map { b =>
        IvyRoot(
          base(b),
          ivy.collect { case (`b`, p) => p },
          artifact.collect { case (`b`, p) => p },
          patterns.isMavenCompatible
        )
      }
    }
    def remote(prefix: String): Base =
      if (prefix.startsWith("file:")) LocalBase(normalize(new File(new URI(prefix)))) else RemoteBase(prefix)
    resolvers.toVector
      .flatMap {
        case m: MavenCache => Vector(MavenRoot(LocalBase(normalize(m.rootFile))))
        case m: MavenRepository =>
          Vector(MavenRoot(remote(if (m.root.endsWith("/")) m.root else m.root + "/")))
        case f: FileRepository => ivyRoots(f.patterns, b => LocalBase(normalize(new File(b))))
        case u: URLRepository  => ivyRoots(u.patterns, remote)
        case _                 => Vector.empty
      }
      .distinct
      .sortBy(_.base match {
        case RemoteBase(p) => -p.length
        case LocalBase(d)  => -d.toString.length
      })
  }
}

