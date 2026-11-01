package sbtnixbackend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

import sbt.librarymanagement.{Artifact, ConfigRef}

/** What a package output holds: for each Scala version the project was
  * built for, the artifacts `publish` publishes and the path in the output
  * of the file that holds each.
  *
  * The Nix build publishes a project into its output through the publisher
  * of sbt, which lays the files out by its own patterns. After that
  * publish, [[record]] finds, for each artifact the publish was given, the
  * output file with the same bytes, and writes the [[Variant]] to a
  * manifest in the output. The lock run runs the same build, and the lock
  * takes the variants from those manifests (see LockGeneration), so the
  * Nix library knows every artifact file of a package before it builds it,
  * and `nixPackagedArtifacts` and `nixPublish` find the files in the output
  * by the paths the lock records.
  */
object NixArtifacts {

  /** An artifact `publish` publishes and its path in the package output. */
  final case class Entry(artifact: Artifact, path: String)

  /** The artifacts of one Scala version of a project, and the path of its
    * module descriptor: the POM of a Maven-style module, which is one of
    * its artifacts, or the ivy.xml that the publisher of sbt delivers for
    * an Ivy-style module beside them.
    */
  final case class Variant(scalaVersion: String, entries: Vector[Entry], descriptor: String)

  /** The manifest file at the root of a package output. */
  val FileName = "sbt-nix-artifacts.json"

  private val checksumSuffixes = Vector(".sha1", ".md5", ".sha256", ".sha512")

  /** Records the artifacts `published` for `scalaVersion` in the manifest of
    * the output `dir`, beside the variants already there.
    */
  def record(dir: File, scalaVersion: String, published: Seq[(Artifact, File)]): Unit = {
    val byDigest = files(dir).groupBy(digest)
    val entries = published.toVector.map { case (a, f) =>
      val held = byDigest
        .getOrElse(digest(f), Vector.empty)
        .headOption
        .getOrElse(sys.error(s"sbt-nix-backend: the output $dir holds no file with the bytes of $f ($a)"))
      Entry(a, dir.toPath.relativize(held.toPath).toString.replace(File.separatorChar, '/'))
    }
    val descriptor = entries
      .collectFirst { case e if e.artifact.`type` == "pom" => e.path }
      .orElse {
        // Ivy-style, an artifact is at <module>/<type>s/<file>, and the
        // descriptor at <module>/ivys/ivy.xml.
        entries.headOption.map(e => e.path.split('/').dropRight(2).mkString("/") + "/ivys/ivy.xml")
      }
      .filter(p => new File(dir, p).isFile)
      .getOrElse(sys.error(s"sbt-nix-backend: the output $dir holds no POM or ivy.xml for Scala $scalaVersion"))
    val variants =
      (readManifest(dir).filterNot(_.scalaVersion == scalaVersion) :+ Variant(scalaVersion, entries, descriptor))
        .sortBy(_.scalaVersion)
    Files.write(
      new File(dir, FileName).toPath,
      (Json.obj("variants" -> Json.Arr(variants.map(variantJson))).render + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }

  /** The variants the manifest of the output `dir` records; none without a
    * manifest.
    */
  def readManifest(dir: File): Vector[Variant] = {
    val manifest = new File(dir, FileName)
    if (!manifest.isFile) Vector.empty
    else
      Json
        .parse(new String(Files.readAllBytes(manifest.toPath), StandardCharsets.UTF_8))
        .asObject("variants")
        .asArray
        .map(parseVariant)
  }

  /** The files of `variant` in the package output `out`. */
  def files(out: File, variant: Variant): Map[Artifact, File] =
    variant.entries.map { e =>
      val f = new File(out, e.path)
      if (!f.isFile) sys.error(s"sbt-nix-backend: the Nix build in $out has no file ${e.path} for ${e.artifact}")
      e.artifact -> f
    }.toMap

  def variantJson(v: Variant): Json =
    Json.obj(
      "scalaVersion" -> Json.str(v.scalaVersion),
      "descriptor" -> Json.str(v.descriptor),
      "artifacts" -> Json.Arr(v.entries.map { e =>
        val a = e.artifact
        Json.obj(
          "name" -> Json.str(a.name),
          "type" -> Json.str(a.`type`),
          "extension" -> Json.str(a.extension),
          "classifier" -> a.classifier.fold[Json](Json.Null)(Json.str),
          "configurations" -> Json.Arr(a.configurations.map(c => Json.str(c.name))),
          "extraAttributes" -> Json.Obj(a.extraAttributes.toVector.sorted.map { case (k, v) => k -> Json.str(v) }),
          "path" -> Json.str(e.path)
        )
      })
    )

  def parseVariant(j: Json): Variant = {
    val v = j.asObject
    Variant(
      v("scalaVersion").asString,
      v("artifacts").asArray.map { a =>
        val o = a.asObject
        Entry(
          Artifact(
            o("name").asString,
            o("type").asString,
            o("extension").asString,
            o.get("classifier").collect { case s: Json.Str => s.value },
            o("configurations").asArray.map(c => ConfigRef(c.asString)),
            None,
            o("extraAttributes").asObject.fields.map { case (k, v) => k -> v.asString }.toMap,
            None,
            false
          ),
          o("path").asString
        )
      },
      v("descriptor").asString
    )
  }

  /** Whether `f` is the manifest file. */
  def isManifest(f: File): Boolean = f.getName == FileName

  private def files(dir: File): Vector[File] =
    Option(dir.listFiles()).getOrElse(Array.empty[File]).toVector.flatMap { f =>
      if (f.isDirectory) files(f)
      else if (isManifest(f) || checksumSuffixes.exists(f.getName.endsWith)) Vector.empty
      else Vector(f)
    }

  private def digest(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath)).map(b => f"${b & 0xff}%02x").mkString
}
