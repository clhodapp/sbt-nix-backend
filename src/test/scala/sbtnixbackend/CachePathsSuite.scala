package sbtnixbackend

import java.io.File
import java.nio.file.Files

class CachePathsSuite extends munit.FunSuite {

  test("a plain URL maps to protocol/host/path") {
    assertEquals(
      CachePaths.relativePath("https://repo1.maven.org/maven2/org/x/y/1.0/y-1.0.jar"),
      "https/repo1.maven.org/maven2/org/x/y/1.0/y-1.0.jar"
    )
  }

  test("characters coursier escapes are percent-escaped") {
    // coursier.paths.CachePath escapes " %$&+,:;=?@<>#" and non-ASCII.
    assertEquals(
      CachePaths.relativePath("https://host:8080/a+b/c d?x=1"),
      "https/host%3A8080/a%2Bb/c%20d%3Fx%3D1"
    )
  }

  test("a directory URL is stored as .directory") {
    assertEquals(CachePaths.relativePath("https://host/a/"), "https/host/a/.directory")
  }

  test("the mapping is invertible") {
    Seq(
      "https://repo1.maven.org/maven2/org/x/y/1.0/y-1.0.pom",
      "http://host:8080/a+b/c%d",
      "https://host/dir/",
      "https://host/q?x=1&y=2"
    ).foreach(u => assertEquals(CachePaths.url(CachePaths.relativePath(u)), u))
  }

  test("file URLs and relative URLs are rejected") {
    intercept[IllegalArgumentException](CachePaths.relativePath("file:/tmp/x.jar"))
    intercept[IllegalArgumentException](CachePaths.relativePath("https:relative"))
    intercept[IllegalArgumentException](CachePaths.relativePath("no-protocol"))
  }

  test("a path downloaded with credentials cannot become a URL") {
    val e = intercept[IllegalArgumentException](CachePaths.url("https/user%40host/a.jar"))
    assert(e.getMessage.contains("credentials"), e.getMessage)
  }

  test("bookkeeping files are dot files other than .directory") {
    assert(CachePaths.isBookkeeping(".y-1.0.jar__sha1"))
    assert(CachePaths.isBookkeeping(".y-1.0.jar.checked"))
    assert(!CachePaths.isBookkeeping(".directory"))
    assert(!CachePaths.isBookkeeping("y-1.0.jar"))
  }

  test("downloadedFiles lists downloads only, sorted") {
    val dir = Files.createTempDirectory("cache").toFile
    def touch(rel: String): Unit = {
      val f = new File(dir, rel)
      f.getParentFile.mkdirs()
      Files.write(f.toPath, Array[Byte](1))
    }
    touch("https/h/b/b.jar")
    touch("https/h/a/a.pom")
    touch("https/h/a/.a.pom__sha1")
    touch("https/h/c/.directory")
    assertEquals(CachePaths.downloadedFiles(dir), Vector("https/h/a/a.pom", "https/h/b/b.jar", "https/h/c/.directory"))
  }
}
