package sbtnixbackend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class LockSuite extends munit.FunSuite {

  private val lock = Lock(
    sbtVersion = "1.11.7",
    javaVersion = "21.0.12",
    launcher = "https/repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.11.7/sbt-launch-1.11.7.jar",
    javaOptions = Vector("-Xmx2g"),
    plugin = Some(LockedPlugin("io.github.clhodapp", "sbt-nix-backend", "0.1.0", LockedPlugin.Environment)),
    projects = Vector(
      LockedProject("b", "o", "b_3", "1"),
      LockedProject(
        "a",
        "o",
        "a_3",
        "1",
        Vector(
          NixArtifacts.Variant(
            "3.3.6",
            Vector(
              NixArtifacts.Entry(sbt.librarymanagement.Artifact("a", "jar", "jar"), "o/a_3/1/a_3-1.jar"),
              NixArtifacts.Entry(
                sbt.librarymanagement.Artifact("a", "src", "jar", "sources"),
                "o/a_3/1/a_3-1-sources.jar"
              )
            ),
            "o/a_3/1/a_3-1.pom"
          )
        )
      )
    ),
    flakeRepositories = Vector(LockedFlakeRepository("r", "github:x/y#z", "github:x/y/abc?narHash=h", "z")),
    artifacts = Vector(
      LockedArtifact("https/h/z.jar", "https://h/z.jar", "sha256-z"),
      LockedArtifact("https/h/a.jar", "https://h/a.jar", "sha256-a")
    ),
    inputsHash = "sha256-i",
    definitionHash = "sha256-d"
  )

  private def tempDir(): File = Files.createTempDirectory("lock").toFile

  private def write(f: File, s: String): Unit = {
    f.getParentFile.mkdirs()
    Files.write(f.toPath, s.getBytes(StandardCharsets.UTF_8))
  }

  test("a lock survives writing and reading, with its lists sorted") {
    val f = new File(tempDir(), "lock.json")
    lock.write(f)
    val read = Lock.read(f)
    assertEquals(read.projects.map(_.id), Vector("a", "b"))
    assertEquals(read.artifacts.map(_.path), Vector("https/h/a.jar", "https/h/z.jar"))
    assertEquals(read.copy(projects = lock.projects, artifacts = lock.artifacts), lock)
  }

  test("writing is deterministic") {
    val d = tempDir()
    lock.write(new File(d, "1.json"))
    lock.copy(artifacts = lock.artifacts.reverse, projects = lock.projects.reverse).write(new File(d, "2.json"))
    assertEquals(
      Files.readAllBytes(new File(d, "1.json").toPath).toSeq,
      Files.readAllBytes(new File(d, "2.json").toPath).toSeq
    )
  }

  test("another format version is refused, and readOption treats it as no lock") {
    val f = new File(tempDir(), "lock.json")
    write(f, lock.toJson.render.replace("\"version\": " + Lock.FormatVersion, "\"version\": 99"))
    intercept[IllegalStateException](Lock.read(f))
    assertEquals(Lock.readOption(f), None)
    assertEquals(Lock.readOption(new File(tempDir(), "absent.json")), None)
  }

  test("the definition hash covers *.sbt and project/, and ignores target/ and sources") {
    val root = tempDir()
    write(new File(root, "build.sbt"), "a")
    write(new File(root, "project/plugins.sbt"), "b")
    write(new File(root, "project/Build.scala"), "c")
    write(new File(root, "src/main/scala/A.scala"), "d")
    val h0 = Lock.buildDefinitionHash(root, Nil)
    write(new File(root, "project/target/x"), "noise")
    write(new File(root, "project/project/target/y"), "noise")
    write(new File(root, "src/main/scala/A.scala"), "changed")
    assertEquals(Lock.buildDefinitionHash(root, Nil), h0)
    write(new File(root, "project/plugins.sbt"), "b2")
    val h1 = Lock.buildDefinitionHash(root, Nil)
    assertNotEquals(h1, h0)
    write(new File(root, "other.sbt"), "e")
    assertNotEquals(Lock.buildDefinitionHash(root, Nil), h1)
  }

  test("extra inputs change the hash, and moving bytes between files does too") {
    val root = tempDir()
    write(new File(root, "a.sbt"), "xy")
    write(new File(root, "b.sbt"), "z")
    val h = Lock.buildDefinitionHash(root, Nil)
    assertNotEquals(Lock.buildDefinitionHash(root, Seq("url=x")), h)
    write(new File(root, "a.sbt"), "x")
    write(new File(root, "b.sbt"), "yz")
    assertNotEquals(Lock.buildDefinitionHash(root, Nil), h)
  }

  test("sriHash is the SRI form of SHA-256") {
    val f = new File(tempDir(), "f")
    write(f, "abc")
    assertEquals(Lock.sriHash(f), "sha256-ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=")
  }
}
