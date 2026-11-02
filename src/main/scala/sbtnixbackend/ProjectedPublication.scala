package sbtnixbackend

import java.io.File
import sbt._

/** Exact projected files, uploaded through the project's native publisher. */
object ProjectedPublication {
  final case class Module(id: ModuleID, artifacts: Map[Artifact, File], pom: File, ivy: File)

  def modules(publication: File, maven: Boolean): Vector[Module] =
    ProjectedDependencies.readCatalog(new File(publication, "catalog.json")).map { id =>
      val directory = new File(publication, "maven/" + id.organization.replace('.', '/') + "/" + id.name + "/" + id.revision)
      val prefix = id.name + "-" + id.revision
      val pom = new File(directory, prefix + ".pom")
      val ivy = new File(publication, "ivy/" + id.organization + "/" + id.name + "/" + id.revision + "/ivys/ivy.xml")
      val artifacts = Option(directory.listFiles()).toVector.flatten.filter { file =>
        file.isFile && file.getName.startsWith(prefix + ".") ||
          file.isFile && file.getName.startsWith(prefix + "-")
      }.filterNot(file => Vector(".md5", ".sha1", ".sha256", ".sha512", ".asc").exists(file.getName.endsWith)).flatMap { file =>
        val suffix = file.getName.substring(prefix.length)
        val dot = suffix.lastIndexOf('.')
        if (dot < 0 || (!maven && suffix == ".pom")) None
        else {
          val extension = suffix.substring(dot + 1)
          val classifier = if (suffix.startsWith("-")) Some(suffix.substring(1, dot)) else None
          Some(Compat.artifact(id.name, extension, extension, classifier, Vector.empty, None, Map.empty, None, false) -> file)
        }
      }.toMap
      Module(id, artifacts, pom, ivy)
    }
}
