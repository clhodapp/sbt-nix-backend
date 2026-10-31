package sbtnixbackend

import sbt.librarymanagement.{MavenRepository, Patterns, Resolver, URLRepository}

class IvyMirrorSuite extends munit.FunSuite {

  private val base = "file:/cache/"

  test("a Maven root moves onto its coursier path under the mirror") {
    assertEquals(
      IvyMirror.rewrite(MavenRepository("central", "https://repo1.maven.org/maven2/"), base),
      MavenRepository("central", "file:/cache/https/repo1.maven.org/maven2/").withAllowInsecureProtocol(true): Resolver
    )
  }

  test("a port is escaped the way coursier stores it") {
    assertEquals(
      IvyMirror.rewritePrefix("http://localhost:8080/repo/", "http://127.0.0.1:1"),
      Some("http://127.0.0.1:1/http/localhost%3A8080/repo/")
    )
  }

  test("an Ivy pattern keeps its tokens after the rewritten prefix") {
    val pattern = "https://repo.scala-sbt.org/scalasbt/sbt-plugin-releases/[organisation]/[module](/scala_[scalaVersion])/[revision]/ivys/ivy.xml"
    val rewritten = IvyMirror.rewrite(URLRepository("sbt-plugins", Patterns(Vector(pattern), Vector(pattern), false, false, false), false), base)
    assertEquals(
      rewritten.asInstanceOf[URLRepository].patterns.ivyPatterns,
      Vector("file:/cache/https/repo.scala-sbt.org/scalasbt/sbt-plugin-releases/[organisation]/[module](/scala_[scalaVersion])/[revision]/ivys/ivy.xml")
    )
  }

  test("a local repository is read where it is") {
    val local = MavenRepository("local", "file:/home/user/.m2/repository/")
    assertEquals(IvyMirror.rewrite(local, base), local: Resolver)
  }
}
