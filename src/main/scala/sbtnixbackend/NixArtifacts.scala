package sbtnixbackend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

import sbt.librarymanagement.{Artifact, ConfigRef, CrossVersion}

/** The artifacts of a package output, by variant.
  *
  * The Nix build publishes a project into its output through the publisher
  * of sbt, which lays the files out by its own patterns, the version of the
  * project in every path. After that publish, [[record]] finds, for each
  * artifact the publish was given, the output file with the same bytes, and
  * links it at a name that does not depend on the version:
  * `nix-support/artifacts/<variant>/<key>`, beside the link
  * `nix-support/artifacts/<variant>/descriptor` to the POM or ivy.xml.
  * The variant is named for the cross version of the publish (see
  * [[variantName]]). A manifest in the output keeps where each file is.
  *
  * The lock run runs the same build, and the lock takes the [[Variant]]s
  * from those manifests (see LockGeneration): each variant of a project
  * with the identity of its artifacts and no path. The Nix library
  * knows every artifact file of a package from the lock alone, before it
  * builds it, and `nixPackagedArtifacts` and `nixPublish` find the files by
  * their links.
  */
object NixArtifacts {

  /** The artifacts of one Scala version of a project, under the variant
    * name [[variantName]] gives it.
    */
  final case class Variant(name: String, scalaVersion: String, artifacts: Vector[Artifact])

  /** The name of the variant of a project for Scala `scalaFull`: a Nix
    * identifier made from the cross version sbt publishes the module with.
    *
    * For a project other than an sbt plugin, it is the suffix that
    * `crossVersion` appends to the module name, split at `_`. A token that
    * starts with a digit is a Scala version and gets the prefix `scala`,
    * and every character other than a letter or a digit becomes `_`:
    * `_3` gives `scala3`, `_2.13.16` gives `scala2_13_16`, and `_sjs1_3`
    * gives `sjs1_scala3`. A module without a suffix gives `default`.
    *
    * An sbt plugin is published for a Scala and an sbt binary version,
    * in its name or in extra attributes, and gives `scala<Scala>_sbt<sbt>`:
    * `scala2_12_sbt1_0` for sbt 1 and `scala3_sbt2` for sbt 2.
    */
  def variantName(crossVersion: CrossVersion, plugin: Boolean, sbtBinary: String, scalaFull: String): String = {
    val scalaBinary = CrossVersion.binaryScalaVersion(scalaFull)
    def token(t: String): String = {
      val clean = t.replaceAll("[^A-Za-z0-9]", "_")
      if (clean.headOption.exists(_.isDigit)) "scala" + clean else clean
    }
    if (plugin) Seq(scalaBinary, "sbt" + sbtBinary).map(token).mkString("_")
    else {
      val suffix = CrossVersion(crossVersion, scalaFull, scalaBinary).fold("")(f => f(""))
      val tokens = suffix.split('_').toVector.filter(_.nonEmpty).map(token)
      if (tokens.isEmpty) "default" else tokens.mkString("_")
    }
  }

  /** The name an artifact is linked at in its variant:
    * `<name>(-<classifier>).<extension>`, such as `core-sources.jar`.
    */
  def key(a: Artifact): String = a.name + a.classifier.fold("")("-" + _) + "." + a.extension

  /** The name the module descriptor (POM or ivy.xml) is linked at. */
  val Descriptor = "descriptor"

  /** The directory of the links of the variants. */
  val LinkDirectory = "nix-support/artifacts"

  /** The manifest file at the root of a package output. */
  val FileName = "sbt-nix-artifacts.json"

  private val checksumSuffixes = Vector(".sha1", ".md5", ".sha256", ".sha512")

  /** Records the artifacts `published` for `scalaVersion` in the output
    * `dir` as the variant `name`: links each artifact and the module
    * descriptor at their names, and adds the variant to the manifest.
    */
  def record(dir: File, name: String, scalaVersion: String, published: Seq[(Artifact, File)]): Unit = {
    val keys = published.map(p => key(p._1))
    keys.diff(keys.distinct).headOption.foreach { k =>
      sys.error(s"sbt-nix-backend: two artifacts of variant $name would both be linked as $k")
    }
    val existing = readManifest(dir)
    existing.find(v => v.name == name && v.scalaVersion != scalaVersion).foreach { v =>
      sys.error(conflict(name, Seq(v.scalaVersion, scalaVersion)))
    }
    val byDigest = files(dir).groupBy(digest)
    def rel(f: File) = dir.toPath.relativize(f.toPath).toString.replace(File.separatorChar, '/')
    val candidates = published.toVector.map { case (a, f) =>
      val held = byDigest.getOrElse(digest(f), Vector.empty).map(rel).sorted
      if (held.isEmpty) sys.error(s"sbt-nix-backend: the output $dir holds no file with the bytes of $f ($a)")
      a -> held
    }
    // Two variants can publish files with the same bytes (the sources jar
    // of a project built for two Scala versions). A file only this publish
    // matches places it in the output; of several files with the bytes of
    // an artifact, it takes the one nearest those.
    val placed = candidates.collect { case (_, Vector(only)) => only }
    def shared(a: String, b: String) = a.split('/').zip(b.split('/')).takeWhile { case (x, y) => x == y }.length
    val paths = candidates.map { case (a, held) => a -> held.maxBy(p => placed.map(shared(p, _)).foldLeft(0)(math.max)) }
    val descriptor = paths
      .collectFirst { case (a, p) if a.`type` == "pom" => p }
      .orElse {
        // Ivy-style, an artifact is at <module>/<type>s/<file>, and the
        // descriptor at <module>/ivys/ivy.xml.
        paths.headOption.map(_._2.split('/').dropRight(2).mkString("/") + "/ivys/ivy.xml")
      }
      .filter(p => new File(dir, p).isFile)
      .getOrElse(sys.error(s"sbt-nix-backend: the output $dir holds no POM or ivy.xml for variant $name"))

    val links = new File(new File(dir, LinkDirectory), name)
    links.mkdirs()
    def link(name: String, target: String): Unit = {
      val l = new File(links, name).toPath
      Files.deleteIfExists(l)
      Files.createSymbolicLink(l, links.toPath.relativize(new File(dir, target).toPath))
    }
    paths.foreach { case (a, p) => link(key(a), p) }
    link(Descriptor, descriptor)

    val variants = (existing.filterNot(_.name == name) :+ Variant(name, scalaVersion, paths.map(_._1))).sortBy(_.name)
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

  /** The message for Scala versions `scalaVersions` of a project that all
    * have the variant name `name`.
    */
  def conflict(name: String, scalaVersions: Seq[String]): String =
    s"sbt-nix-backend: Scala ${scalaVersions.mkString(" and ")} would publish the same variant, $name, of the project: " +
      "the lockfile cannot tell their artifacts apart. Build the project for one of them."

  /** The files of `variant` in the package output `out`, by their links. */
  def files(out: File, variant: Variant): Map[Artifact, File] = {
    val links = new File(new File(out, LinkDirectory), variant.name)
    variant.artifacts.map { a =>
      val f = new File(links, key(a))
      if (!f.isFile) sys.error(s"sbt-nix-backend: the Nix build in $out has no file for $a (looked for $f)")
      a -> f
    }.toMap
  }

  def variantJson(v: Variant): Json =
    Json.obj(
      "name" -> Json.str(v.name),
      "scalaVersion" -> Json.str(v.scalaVersion),
      "artifacts" -> Json.Arr(v.artifacts.map { a =>
        Json.obj(
          "key" -> Json.str(key(a)),
          "name" -> Json.str(a.name),
          "type" -> Json.str(a.`type`),
          "extension" -> Json.str(a.extension),
          "classifier" -> a.classifier.fold[Json](Json.Null)(Json.str),
          "configurations" -> Json.Arr(a.configurations.map(c => Json.str(c.name))),
          "extraAttributes" -> Json.Obj(a.extraAttributes.toVector.sorted.map { case (k, v) => k -> Json.str(v) })
        )
      })
    )

  def parseVariant(j: Json): Variant = {
    val v = j.asObject
    Variant(
      v("name").asString,
      v("scalaVersion").asString,
      v("artifacts").asArray.map { a =>
        val o = a.asObject
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
        )
      }
    )
  }

  /** Whether `f` is the manifest file. */
  def isManifest(f: File): Boolean = f.getName == FileName

  // The published files: neither the manifest, the checksums, nor the links.
  private def files(dir: File): Vector[File] =
    Option(dir.listFiles()).getOrElse(Array.empty[File]).toVector.flatMap { f =>
      if (f.getName == "nix-support") Vector.empty
      else if (f.isDirectory) files(f)
      else if (isManifest(f) || checksumSuffixes.exists(f.getName.endsWith)) Vector.empty
      else Vector(f)
    }

  private def digest(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath)).map(b => f"${b & 0xff}%02x").mkString
}
