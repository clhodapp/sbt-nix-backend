package sbtnixbackend

import java.io.File
import java.nio.file.Files

import sbt.librarymanagement.{Artifact, CrossVersion}

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
      "scala3",
      "3.3.6",
      Seq(Artifact("crosslib", "jar", "jar") -> jar, Artifact("crosslib", "src", "jar", "sources") -> sources)
    )
    val Vector(variant) = NixArtifacts.readManifest(out)
    val links = new File(out, "nix-support/artifacts/scala3").toPath
    def target(link: String) = out.toPath.relativize(links.resolve(link).toRealPath()).toString
    assertEquals(variant.name, "scala3")
    assertEquals(variant.artifacts.map(NixArtifacts.key), Vector("crosslib.jar", "crosslib-sources.jar"))
    assertEquals(target("crosslib.jar"), "example/crosslib_3/1.0/jars/crosslib_3.jar")
    assertEquals(target("crosslib-sources.jar"), "example/crosslib_3/1.0/srcs/crosslib_3-sources.jar")
    assertEquals(target("descriptor"), "example/crosslib_3/1.0/ivys/ivy.xml")
  }

  test("two Scala versions with one variant name fail to record") {
    val out = Files.createTempDirectory("nix-artifacts-out").toFile
    val published = Files.createTempDirectory("nix-artifacts-published").toFile
    for (v <- Seq("2.13.15", "2.13.16")) {
      write(out, s"example/lib_2.13/1.0/lib_2.13-1.0-$v.jar", s"classes for $v")
      write(out, "example/lib_2.13/1.0/lib_2.13-1.0.pom", "the POM")
    }
    def recordFor(v: String) =
      NixArtifacts.record(
        out,
        "scala2_13",
        v,
        Seq(
          Artifact("lib", "jar", "jar") -> write(published, s"lib-$v.jar", s"classes for $v"),
          Artifact("lib", "pom", "pom") -> write(published, "lib.pom", "the POM")
        )
      )
    recordFor("2.13.15")
    val error = intercept[RuntimeException](recordFor("2.13.16"))
    assert(error.getMessage.contains("Scala 2.13.15 and 2.13.16 would publish the same variant, scala2_13"), error.getMessage)
  }

  private def name(cross: CrossVersion, scala: String) = NixArtifacts.variantName(cross, false, "1.0", scala)

  test("a variant is named for the cross version of its module") {
    assertEquals(name(CrossVersion.binary, "3.3.6"), "scala3")
    assertEquals(name(CrossVersion.binary, "2.13.16"), "scala2_13")
    assertEquals(name(CrossVersion.full, "2.13.16"), "scala2_13_16")
    assertEquals(name(CrossVersion.patch, "2.13.16-bin-abc"), "scala2_13_16")
    assertEquals(name(CrossVersion.for3Use2_13, "3.3.6"), "scala2_13")
    assertEquals(name(CrossVersion.for2_13Use3, "2.13.16"), "scala3")
    assertEquals(name(CrossVersion.binaryWith("sjs1_", ""), "3.3.6"), "sjs1_scala3")
    assertEquals(name(CrossVersion.binaryWith("native0.5_", ""), "2.13.16"), "native0_5_scala2_13")
    assertEquals(name(CrossVersion.constant("2.12"), "3.3.6"), "scala2_12")
    assertEquals(name(CrossVersion.disabled, "3.3.6"), "default")
  }

  test("an sbt plugin variant names its Scala and sbt binary versions") {
    assertEquals(NixArtifacts.variantName(CrossVersion.disabled, true, "1.0", "2.12.20"), "scala2_12_sbt1_0")
    assertEquals(NixArtifacts.variantName(CrossVersion.binary, true, "2", "3.8.4"), "scala3_sbt2")
  }
}
