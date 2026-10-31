package sbtnixbackend

import java.io.File
import java.nio.file.Files

/** The sbt-nix-backend Nix library (nix/lib in its repository), which the
  * plugin carries in its jar: the same files the flake exports as
  * `lib.sbt-nix-backend.buildSbtProject`, from the same revision.
  * Interactive mode evaluates realize.nix from it, and nixPackagedArtifacts
  * builds package.nix.
  */
object NixLibrary {

  val files: Vector[String] =
    Vector("default.nix", "dependencies.nix", "fetch.nix", "lock.nix", "package.nix", "plugin.nix", "realize.nix")

  private def resource(name: String): Array[Byte] = {
    val in = getClass.getResourceAsStream("/sbtnixbackend/nix/" + name)
    if (in == null) throw new IllegalStateException(s"the plugin jar has no Nix file $name")
    try {
      val out = new java.io.ByteArrayOutputStream
      val buf = new Array[Byte](8192)
      var n = in.read(buf)
      while (n >= 0) { out.write(buf, 0, n); n = in.read(buf) }
      out.toByteArray
    } finally in.close()
  }

  /** Writes the files to `dir`, leaving unchanged ones alone, and returns
    * `dir`.
    */
  def write(dir: File): File = {
    dir.mkdirs()
    files.foreach { n =>
      val f = new File(dir, n)
      val content = resource(n)
      if (!(f.isFile && java.util.Arrays.equals(Files.readAllBytes(f.toPath), content)))
        Files.write(f.toPath, content)
    }
    dir
  }
}
