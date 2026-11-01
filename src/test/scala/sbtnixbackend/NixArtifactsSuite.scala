package sbtnixbackend

import java.io.File
import java.nio.file.Files

import sbt.librarymanagement.Artifact

class NixArtifactsSuite extends munit.FunSuite {

  private def write(dir: File, path: String, text: String): File = {
    val f = new File(dir, path)
    f.getParentFile.mkdirs()
    Files.write(f.toPath, text.getBytes("UTF-8"))
    f
  }

  test("of two files with the bytes of an artifact, a variant takes the one in its own module") {
    val out = Files.createTempDirectory("nix-artifacts-out").toFile
    val published = Files.createTempDirectory("nix-artifacts-published").toFile
    // Ivy-style outputs of crosslib for Scala 2.13 and 3: their sources jars
    // have the same bytes.
    for (v <- Seq("2.13", "3")) {
      write(out, s"example/crosslib_$v/1.0/jars/crosslib_$v.jar", s"classes for $v")
      write(out, s"example/crosslib_$v/1.0/srcs/crosslib_$v-sources.jar", "the same sources")
      write(out, s"example/crosslib_$v/1.0/ivys/ivy.xml", s"descriptor for $v")
    }
    val jar = write(published, "crosslib_3.jar", "classes for 3")
    val sources = write(published, "crosslib_3-sources.jar", "the same sources")
    NixArtifacts.record(
      out,
      "3.3.6",
      Seq(Artifact("crosslib", "jar", "jar") -> jar, Artifact("crosslib", "src", "jar", "sources") -> sources)
    )
    val Vector(variant) = NixArtifacts.readManifest(out)
    val links = new File(out, "nix-support/artifacts/3.3.6").toPath
    def target(link: String) = out.toPath.relativize(links.resolve(link).toRealPath()).toString
    assertEquals(variant.artifacts.map(NixArtifacts.key), Vector("crosslib.jar", "crosslib-sources.jar"))
    assertEquals(target("crosslib.jar"), "example/crosslib_3/1.0/jars/crosslib_3.jar")
    assertEquals(target("crosslib-sources.jar"), "example/crosslib_3/1.0/srcs/crosslib_3-sources.jar")
    assertEquals(target("descriptor"), "example/crosslib_3/1.0/ivys/ivy.xml")
  }
}
