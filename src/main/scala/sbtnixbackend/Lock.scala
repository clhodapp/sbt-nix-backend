package sbtnixbackend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.security.MessageDigest
import java.util.Base64

/** One file in the locked coursier cache.
  *
  * @param path the coursier-cache-relative path (see [[CachePaths]])
  * @param url  where the Nix side downloads it from
  * @param hash an SRI SHA-256 hash (`sha256-<base64>`)
  */
final case class LockedArtifact(path: String, url: String, hash: String)

/** A project of the build, as the Nix side names and builds it.
  *
  * @param id           the sbt project id (`core` in `core/compile`)
  * @param organization the published organization
  * @param name         the published artifact name for the first Scala
  *                     version, cross-version suffix included
  * @param version      the published version
  * @param variants     for each Scala version the project is built for,
  *                     the artifacts its package output holds and their
  *                     paths, which the lock run's build of the project
  *                     published
  */
final case class LockedProject(
    id: String,
    organization: String,
    name: String,
    version: String,
    variants: Vector[NixArtifacts.Variant] = Vector.empty
)

/** A flake reference to an sbt-nix-backend repository output (feature 5).
  *
  * @param name      the resolver name the build declared it under
  * @param ref       the flake reference as the build declared it
  * @param locked    the locked flake reference (`rev` and `narHash` pinned)
  * @param attribute the attribute under `sbtRepositories.<system>`
  */
final case class LockedFlakeRepository(name: String, ref: String, locked: String, attribute: String)

/** The sbt-nix-backend plugin, when the lock run took it from a repository
  * the Nix build must supply, and how the build reached it (`via`):
  *
  *  - [[LockedPlugin.GlobalPlugin]]: the sbt wrapper loaded it as a global
  *    plugin (`sbt.global.plugins`), and the Nix build does the same.
  *  - [[LockedPlugin.Environment]]: the build's own
  *    `project/project/plugins.sbt` resolved it from the repository named by
  *    `SBT_NIX_BACKEND_REPOSITORY`, and the Nix build sets that variable.
  *
  * Either way the Nix build checks that its repository holds this version.
  * A plugin resolved from a remote repository is an ordinary locked
  * artifact and has no record here.
  */
final case class LockedPlugin(organization: String, name: String, version: String, via: String)

object LockedPlugin {
  val GlobalPlugin = "global-plugin"
  val Environment = "environment"

  /** The variable a build's `project/project/plugins.sbt` names the plugin
    * repository with.
    */
  val RepositoryVariable = "SBT_NIX_BACKEND_REPOSITORY"
}

/** The lockfile: every input a sandboxed build of this sbt build needs.
  *
  * `definitionHash` is a digest of the build definition files alone (see
  * [[Lock.buildDefinitionHash]]); every level of the build can compute it,
  * and interactive resolution reads the lock only while it matches.
  * `inputsHash` adds the inputs only the concrete build knows (the
  * plugin, declared network URLs, flake references, the projects); nixLock
  * relocks when it changes.
  */
final case class Lock(
    sbtVersion: String,
    javaVersion: String,
    launcher: String,
    javaOptions: Vector[String],
    plugin: Option[LockedPlugin],
    projects: Vector[LockedProject],
    flakeRepositories: Vector[LockedFlakeRepository],
    artifacts: Vector[LockedArtifact],
    inputsHash: String,
    definitionHash: String
) {
  def toJson: Json = {
    import Json._
    obj(
      "version" -> num(Lock.FormatVersion),
      "sbtVersion" -> str(sbtVersion),
      "javaVersion" -> str(javaVersion),
      "launcher" -> str(launcher),
      "javaOptions" -> Arr(javaOptions.map(str)),
      "plugin" -> plugin.fold[Json](Null) { p =>
        obj(
          "organization" -> str(p.organization),
          "name" -> str(p.name),
          "version" -> str(p.version),
          "via" -> str(p.via)
        )
      },
      "projects" -> Arr(projects.sortBy(_.id).map { p =>
        obj(
          "id" -> str(p.id),
          "organization" -> str(p.organization),
          "name" -> str(p.name),
          "version" -> str(p.version),
          "variants" -> Arr(p.variants.map(NixArtifacts.variantJson))
        )
      }),
      "flakeRepositories" -> Arr(flakeRepositories.sortBy(_.name).map { r =>
        obj(
          "name" -> str(r.name),
          "ref" -> str(r.ref),
          "locked" -> str(r.locked),
          "attribute" -> str(r.attribute)
        )
      }),
      "inputsHash" -> str(inputsHash),
      "definitionHash" -> str(definitionHash),
      "artifacts" -> Arr(artifacts.sortBy(_.path).map { a =>
        obj("path" -> str(a.path), "url" -> str(a.url), "hash" -> str(a.hash))
      })
    )
  }

  def write(file: File): Unit = {
    Option(file.getParentFile).foreach(_.mkdirs())
    val tmp = new File(file.getPath + ".tmp")
    Files.write(tmp.toPath, (toJson.render + "\n").getBytes(StandardCharsets.UTF_8))
    Files.move(tmp.toPath, file.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }
}

object Lock {

  /** Bumped when the format changes incompatibly; [[parse]] rejects others. */
  val FormatVersion = 2

  def parse(text: String): Lock = {
    val o = Json.parse(text).asObject
    val version = o("version").asInt
    if (version != FormatVersion)
      throw new Json.JsonError(
        s"lock format version $version is not supported by this plugin (it reads version $FormatVersion); run nixLock"
      )
    Lock(
      sbtVersion = o("sbtVersion").asString,
      javaVersion = o("javaVersion").asString,
      launcher = o("launcher").asString,
      javaOptions = o("javaOptions").asArray.map(_.asString),
      plugin = o("plugin") match {
        case Json.Null => None
        case j =>
          val p = j.asObject
          Some(LockedPlugin(p("organization").asString, p("name").asString, p("version").asString, p("via").asString))
      },
      projects = o("projects").asArray.map { j =>
        val p = j.asObject
        LockedProject(
          p("id").asString,
          p("organization").asString,
          p("name").asString,
          p("version").asString,
          p("variants").asArray.map(NixArtifacts.parseVariant)
        )
      },
      flakeRepositories = o("flakeRepositories").asArray.map { j =>
        val r = j.asObject
        LockedFlakeRepository(r("name").asString, r("ref").asString, r("locked").asString, r("attribute").asString)
      },
      artifacts = o("artifacts").asArray.map { j =>
        val a = j.asObject
        LockedArtifact(a("path").asString, a("url").asString, a("hash").asString)
      },
      inputsHash = o("inputsHash").asString,
      definitionHash = o("definitionHash").asString
    )
  }

  def read(file: File): Lock =
    try parse(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8))
    catch {
      case e: Json.JsonError => throw new IllegalStateException(s"cannot read lock file $file: ${e.getMessage}", e)
    }

  /** The lock in `file`, if there is one this plugin can read. A lock of
    * another format counts as none, so the callers relock.
    */
  def readOption(file: File): Option[Lock] =
    if (!file.isFile) None
    else
      try Some(read(file))
      catch { case _: IllegalStateException => None }

  /** A digest of the files that define the build's dependencies: every
    * `*.sbt` file at the root, and everything under `project/` except its
    * `target/` directories, recursively through the meta-builds. The extra
    * strings cover dependency inputs that live outside those files (the
    * injected plugin, declared network URLs, flake references).
    */
  def buildDefinitionHash(root: File, extra: Seq[String]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    def add(s: String): Unit = {
      val b = s.getBytes(StandardCharsets.UTF_8)
      md.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array())
      md.update(b)
    }
    def files(dir: File, rel: String): Vector[(String, File)] =
      Option(dir.listFiles()).getOrElse(Array.empty[File]).toVector.sortBy(_.getName).flatMap { f =>
        val r = if (rel.isEmpty) f.getName else rel + "/" + f.getName
        if (f.isDirectory) { if (f.getName == "target") Vector.empty else files(f, r) }
        else Vector(r -> f)
      }
    val sbtFiles = Option(root.listFiles()).getOrElse(Array.empty[File]).toVector
      .filter(f => f.isFile && f.getName.endsWith(".sbt"))
      .sortBy(_.getName)
      .map(f => f.getName -> f)
    val all = sbtFiles ++ files(new File(root, "project"), "project")
    all.foreach { case (rel, f) =>
      add(rel)
      md.update(Files.readAllBytes(f.toPath))
    }
    extra.foreach(add)
    "sha256-" + Base64.getEncoder.encodeToString(md.digest())
  }

  /** The SRI SHA-256 hash of a file, as Nix's `hash` attribute takes it. */
  def sriHash(file: File): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(file.toPath)
    try {
      val buf = new Array[Byte](1 << 16)
      var n = in.read(buf)
      while (n >= 0) { md.update(buf, 0, n); n = in.read(buf) }
    } finally in.close()
    "sha256-" + Base64.getEncoder.encodeToString(md.digest())
  }
}
