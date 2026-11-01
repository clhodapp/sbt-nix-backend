package sbtnixbackend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

import sbt.librarymanagement.{Artifact, ConfigRef}

/** The manifest of a package output: for each Scala version the project was
  * built for, the artifacts `publish` publishes and the file of the output
  * that holds each.
  *
  * The Nix build publishes a project into its output through sbt's
  * publisher, which lays the files out by its own patterns. After that
  * publish, [[record]] finds, for each artifact the publish was given, the
  * output file with the same bytes. [[read]] turns the manifest back into
  * the artifacts, so `nixPackagedArtifacts` and `nixPublish` hand sbt the
  * files the Nix build made.
  */
object NixArtifacts {

  /** The manifest file at the root of a package output. */
  val FileName = "sbt-nix-artifacts.json"

  private val checksumSuffixes = Vector(".sha1", ".md5", ".sha256", ".sha512")

  /** Records the artifacts `published` for `scalaVersion` in the manifest of
    * the output `dir`, beside the variants already there.
    */
  def record(dir: File, scalaVersion: String, published: Seq[(Artifact, File)]): Unit = {
    val byDigest = files(dir).groupBy(digest)
    val entries = published.map { case (a, f) =>
      val held = byDigest
        .getOrElse(digest(f), Vector.empty)
        .headOption
        .getOrElse(sys.error(s"sbt-nix-backend: the output $dir holds no file with the bytes of $f ($a)"))
      artifactJson(a, dir.toPath.relativize(held.toPath).toString.replace(File.separatorChar, '/'))
    }
    val variant = Json.obj("scalaVersion" -> Json.str(scalaVersion), "artifacts" -> Json.Arr(entries.toVector))
    val manifest = new File(dir, FileName)
    val earlier =
      if (manifest.isFile) parse(manifest)("variants").asArray.filterNot(_.asObject("scalaVersion").asString == scalaVersion)
      else Vector.empty
    val variants = (earlier :+ variant).sortBy(_.asObject("scalaVersion").asString)
    Files.write(manifest.toPath, (Json.obj("variants" -> Json.Arr(variants)).render + "\n").getBytes(StandardCharsets.UTF_8))
  }

  /** The artifacts of the output `dir` for `scalaVersion`. */
  def read(dir: File, scalaVersion: String): Map[Artifact, File] = {
    val manifest = new File(dir, FileName)
    if (!manifest.isFile)
      sys.error(s"sbt-nix-backend: $dir has no $FileName; it was built by an older sbt-nix-backend")
    val variants = parse(manifest)("variants").asArray.map(_.asObject)
    val variant = variants
      .find(_("scalaVersion").asString == scalaVersion)
      .getOrElse(
        sys.error(
          s"sbt-nix-backend: $dir holds no build for Scala $scalaVersion " +
            s"(it holds ${variants.map(_("scalaVersion").asString).mkString(", ")})"
        )
      )
    variant("artifacts").asArray.map { j =>
      val o = j.asObject
      val artifact = Artifact(
        o("name").asString,
        o("type").asString,
        o("extension").asString,
        o.get("classifier").collect { case s: Json.Str => s.value },
        o("configurations").asArray.map(c => ConfigRef(c.asString)),
        None,
        o("extraAttributes").asObject.fields.map { case (k, v) => k -> v.asString }.toMap,
        None,
        false
      )
      artifact -> new File(dir, o("path").asString)
    }.toMap
  }

  /** Whether `name` is the manifest file. */
  def isManifest(f: File): Boolean = f.getName == FileName

  private def artifactJson(a: Artifact, path: String): Json =
    Json.obj(
      "name" -> Json.str(a.name),
      "type" -> Json.str(a.`type`),
      "extension" -> Json.str(a.extension),
      "classifier" -> a.classifier.fold[Json](Json.Null)(Json.str),
      "configurations" -> Json.Arr(a.configurations.map(c => Json.str(c.name))),
      "extraAttributes" -> Json.Obj(a.extraAttributes.toVector.sorted.map { case (k, v) => k -> Json.str(v) }),
      "path" -> Json.str(path)
    )

  private def files(dir: File): Vector[File] =
    Option(dir.listFiles()).getOrElse(Array.empty[File]).toVector.flatMap { f =>
      if (f.isDirectory) files(f)
      else if (isManifest(f) || checksumSuffixes.exists(f.getName.endsWith)) Vector.empty
      else Vector(f)
    }

  private def digest(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath)).map(b => f"${b & 0xff}%02x").mkString

  private def parse(f: File): Json.Obj =
    Json.parse(new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)).asObject
}
