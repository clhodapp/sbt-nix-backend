package sbtnixbackend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import sbtnixbackend.NixBackendPlugin.autoImport.NixFlakeRepository

/** The parts of the plugin that work on files: the copy the lock run
  * builds, the coursier cache linked to the store, and POM coordinates.
  */
class FilesSuite extends munit.FunSuite {

  private def tempDir(): File = Files.createTempDirectory("files").toFile

  private def write(f: File, s: String): Unit = {
    f.getParentFile.mkdirs()
    Files.write(f.toPath, s.getBytes(StandardCharsets.UTF_8))
  }

  private def listing(root: File): Vector[String] = {
    def walk(d: File): Vector[File] =
      Option(d.listFiles()).getOrElse(Array.empty[File]).toVector.flatMap(f => if (f.isDirectory) walk(f) else Vector(f))
    walk(root).map(f => root.toPath.relativize(f.toPath).toString).sorted
  }

  test("the build copy leaves out build outputs and editor state") {
    val root = tempDir()
    Seq(
      "build.sbt",
      "src/main/scala/A.scala",
      "project/plugins.sbt",
      "target/x",
      "project/target/y",
      "sub/target/z",
      ".bsp/sbt.json",
      ".idea/w",
      "result-link"
    ).foreach(p => write(new File(root, p), p))
    val dest = tempDir()
    LockGeneration.copyBuild(root, dest)
    assertEquals(listing(dest), Vector("build.sbt", "project/plugins.sbt", "src/main/scala/A.scala"))
  }

  test("in a git work tree the copy takes tracked and unignored files") {
    // The plugin's Nix build runs these tests without git.
    assume(scala.util.Try(Proc.output(Seq("git", "--version"))).isSuccess, "git is not on the PATH")
    val root = tempDir()
    write(new File(root, ".gitignore"), "ignored.txt\n")
    write(new File(root, "build.sbt"), "a")
    write(new File(root, "ignored.txt"), "b")
    write(new File(root, "new.sbt"), "c")
    write(new File(root, "target/out"), "d")
    Proc.output(Seq("git", "-C", root.getPath, "init", "-q"))
    Proc.output(Seq("git", "-C", root.getPath, "add", "build.sbt", ".gitignore"))
    val dest = tempDir()
    LockGeneration.copyBuild(root, dest)
    assertEquals(listing(dest), Vector(".gitignore", "build.sbt", "new.sbt"))
  }

  test("linkCache links the manifest and removes what it does not list") {
    val store = tempDir()
    write(new File(store, "one"), "1")
    write(new File(store, "two"), "2")
    val cache = tempDir()
    write(new File(cache, "https/h/stale.jar"), "old")
    write(new File(cache, "https/h/.a.jar.checked"), "")
    Nix.linkCache(cache, Map("https/h/a.jar" -> new File(store, "one").getPath))
    Nix.linkCache(cache, Map("https/h/a.jar" -> new File(store, "two").getPath, "https/h/b/b.pom" -> new File(store, "one").getPath))
    assertEquals(
      Files.readSymbolicLink(new File(cache, "https/h/a.jar").toPath).toString,
      new File(store, "two").getPath
    )
    assert(new File(cache, "https/h/b/b.pom").exists())
    assert(!new File(cache, "https/h/stale.jar").exists())
    assert(new File(cache, "https/h/.a.jar.checked").exists(), "coursier's bookkeeping files stay")
  }

  test("POM coordinates come from the POM, with groupId and version inherited from the parent") {
    val dir = tempDir()
    val own = new File(dir, "own.pom")
    write(
      own,
      """<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a_3</artifactId><version>1</version>
        |<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>9</version></dependency></dependencies></project>""".stripMargin
    )
    assertEquals(RuntimeRepository.pomCoordinates(own), RuntimeRepository.Coordinates("g", "a_3", "1"))
    val inherited = new File(dir, "inherited.pom")
    write(
      inherited,
      "<project><parent><groupId>pg</groupId><artifactId>p</artifactId><version>2</version></parent><artifactId>c</artifactId></project>"
    )
    assertEquals(RuntimeRepository.pomCoordinates(inherited), RuntimeRepository.Coordinates("pg", "c", "2"))
  }

  test("a POM without coordinates is refused") {
    val pom = new File(tempDir(), "bad.pom")
    write(pom, "<project><artifactId>a</artifactId></project>")
    intercept[RuntimeRepository.NotRepresentable](RuntimeRepository.pomCoordinates(pom))
  }

  test("the plugin carries every file of the Nix library") {
    val dir = new File("nix/lib")
    assume(dir.isDirectory, "the tests run from the repository root")
    assertEquals(NixLibrary.files, dir.list().toVector.filter(_.endsWith(".nix")).sorted)
    val out = tempDir()
    NixLibrary.write(out)
    NixLibrary.files.foreach { n =>
      assertEquals(
        Files.readAllBytes(new File(out, n).toPath).toSeq,
        Files.readAllBytes(new File(dir, n).toPath).toSeq,
        s"$n in the jar differs from nix/lib/$n"
      )
    }
  }

  test("a flake repository's fragment names the attribute, defaulting to the name") {
    assertEquals(NixFlakeRepository("lib", "github:o/r/abc#core").flakeRef, "github:o/r/abc")
    assertEquals(NixFlakeRepository("lib", "github:o/r/abc#core").attribute, "core")
    assertEquals(NixFlakeRepository("lib", "github:o/r").attribute, "lib")
  }
}
