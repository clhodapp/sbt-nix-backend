package sbtnixbackend

import java.io.File
import java.nio.file.Files

import sbt.librarymanagement.{MavenRepository, Resolver}

class CentralMirrorSuite extends munit.FunSuite {

  private val google = "https://maven-central.storage-download.googleapis.com/maven2/"
  private val jar = "org/typelevel/cats-core_3/2.12.0/cats-core_3-2.12.0.jar"

  test("the mirror comes from the environment, with a trailing slash") {
    assertEquals(CentralMirror.fromEnv(Map(CentralMirror.Variable -> google.stripSuffix("/"))), Some(google))
    assertEquals(CentralMirror.fromEnv(Map(CentralMirror.Variable -> " ")), None)
    assertEquals(CentralMirror.fromEnv(Map.empty), None)
  }

  test("a file of Central downloads from the mirror, and any other file from its own URL") {
    assertEquals(CentralMirror.downloadUrl(CentralMirror.Central + jar, Some(google)), google + jar)
    assertEquals(CentralMirror.downloadUrl("https://repo.scala-sbt.org/x.jar", Some(google)), "https://repo.scala-sbt.org/x.jar")
    assertEquals(CentralMirror.downloadUrl(CentralMirror.Central + jar, None), CentralMirror.Central + jar)
  }

  test("a Maven repository at the root of Central resolves from the mirror") {
    val central = CentralMirror.resolver(Resolver.DefaultMavenRepository, google)
    assertEquals(central.asInstanceOf[MavenRepository].root, google)
    val other = MavenRepository("other", "https://repo.example/maven2/")
    assertEquals(CentralMirror.resolver(other, google), other)
  }

  test("canonicalize moves the mirror's files to the coursier paths of their Central URLs") {
    val cache = Files.createTempDirectory("central-mirror").toFile
    def file(url: String) = new File(cache, CachePaths.relativePath(url))
    def write(url: String, text: String) = {
      val f = file(url)
      f.getParentFile.mkdirs()
      Files.write(f.toPath, text.getBytes("UTF-8"))
    }
    write(google + jar, "classes")
    write(google + "a/b.pom", "pom")
    write(CentralMirror.Central + "a/b.pom", "pom")
    CentralMirror.canonicalize(cache, google)
    assertEquals(new String(Files.readAllBytes(file(CentralMirror.Central + jar).toPath), "UTF-8"), "classes")
    assert(file(CentralMirror.Central + "a/b.pom").isFile)
    assert(!new File(cache, CachePaths.relativePath(google.stripSuffix("/"))).exists)
  }

  test("canonicalize refuses a file whose bytes differ from the one at its Central path") {
    val cache = Files.createTempDirectory("central-mirror").toFile
    for ((url, text) <- Seq(google + "a/b.pom" -> "mirror", CentralMirror.Central + "a/b.pom" -> "central")) {
      val f = new File(cache, CachePaths.relativePath(url))
      f.getParentFile.mkdirs()
      Files.write(f.toPath, text.getBytes("UTF-8"))
    }
    intercept[LockGeneration.LockFailed](CentralMirror.canonicalize(cache, google))
  }
}
