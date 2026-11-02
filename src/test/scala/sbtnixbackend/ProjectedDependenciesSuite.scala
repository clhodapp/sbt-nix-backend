package sbtnixbackend

import sbt._

class ProjectedDependenciesSuite extends munit.FunSuite {
  private val supplied = Vector(ModuleID("example", "library_2.13", "1.0-nix-build"))

  test("Nix selection overrides a native constraint after Scala cross-versioning") {
    val requested = ModuleID("example", "library", "[99,100)").cross(CrossVersion.binary)
    val selected = ProjectedDependencies.select(requested, "2.13.16", "2.13", supplied)
    assertEquals(selected.revision, "1.0-nix-build")
    assertEquals(selected.crossVersion, requested.crossVersion)
    assertEquals(ProjectedDependencies.select(requested, "3.3.6", "3", supplied), requested)
  }

  test("published POMs state the selected version for ordinary downstreams") {
    val pom = scala.xml.XML.loadString("""<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>
      <dependency><groupId>example</groupId><artifactId>library_2.13</artifactId>
      <version>[99,100)</version><scope>runtime</scope></dependency></dependencies></project>""")
    val rewritten = ProjectedDependencies.pom(pom, supplied)
    assertEquals((rewritten \\ "dependency" \ "version").text, "1.0-nix-build")
    assertEquals((rewritten \\ "dependency" \ "scope").text, "runtime")
    assertEquals((rewritten \\ "dependency" \ "version").length, 1)
  }
  test("sbt plugin attributes identify the cross-published artifact") {
    val requested = ModuleID("example", "widget", "99.0")
      .extra("scalaVersion" -> "2.10", "sbtVersion" -> "0.13")
    val provided = Vector(ModuleID("example", "widget_2.10_0.13", "1-nix-build"))
    assertEquals(ProjectedDependencies.select(requested, "2.10.7", "2.10", provided).revision, "1-nix-build")
  }

  test("different Nix providers cannot silently choose an ordering") {
    intercept[RuntimeException] {
      ProjectedDependencies.merge(supplied ++ supplied.map(module => Compat.moduleRevision(module, "other-nix-build")))
    }
    assertEquals(ProjectedDependencies.merge(supplied ++ supplied), supplied)
  }

  test("a projected parent does not retain a source-tree relative path") {
    val pom = scala.xml.XML.loadString("""<project><parent><groupId>example</groupId>
      <artifactId>library_2.13</artifactId><version>99</version><relativePath>../pom.xml</relativePath>
      </parent></project>""")
    val rewritten = ProjectedDependencies.pom(pom, supplied)
    assertEquals((rewritten \ "parent" \ "version").text, "1.0-nix-build")
    assertEquals((rewritten \ "parent" \ "relativePath").text, "")
  }

}
