package sbtnixbackend

import java.io.File
import java.nio.file.Files

import lmcoursier.CoursierConfiguration
import sbt.librarymanagement.{Patterns, Resolver}

class IvyLayoutSuite extends munit.FunSuite {

  private val standard = IvyLayout.patterns.ivyPatterns.head

  test("a token without a value drops its optional part") {
    val tokens = IvyLayout.moduleTokens("org.example", "lib", "1.0", Map.empty, mavenCompatible = false) ++
      IvyLayout.descriptorTokens
    assertEquals(IvyLayout.render(standard, tokens), Some("org.example/lib/1.0/ivys/ivy.xml"))
  }

  test("a plugin's extra attributes are part of its path") {
    val tokens = IvyLayout.moduleTokens(
      "org.example",
      "plugin",
      "1.0",
      IvyLayout.pluginAttributes("2.12", "1.0"),
      mavenCompatible = false
    ) ++ IvyLayout.artifactTokens("plugin", "jar", "jar", None)
    assertEquals(
      IvyLayout.render(IvyLayout.patterns.artifactPatterns.head, tokens),
      Some("org.example/plugin/scala_2.12/sbt_1.0/1.0/jars/plugin.jar")
    )
  }

  test("the Ivy resolver's patterns are below its directory") {
    val repository = IvyLayout.ivy("output", new File("/nix/store/x-repository")).asInstanceOf[sbt.librarymanagement.FileRepository]
    assert(repository.patterns.ivyPatterns.forall(_.startsWith("/nix/store/x-repository/[organisation]/")), repository.patterns.toString)
  }

  test("a token without a value outside an optional part renders nothing") {
    assertEquals(IvyLayout.render("[organisation]/[module]", Map("module" -> "lib")), None)
  }

  test("a Maven-compatible pattern splits the organization at dots") {
    val tokens = IvyLayout.moduleTokens("org.example", "lib", "1.0", Map.empty, mavenCompatible = true)
    assertEquals(IvyLayout.render("[organisation]/[module]/[revision]", tokens), Some("org/example/lib/1.0"))
  }

  test("a module from an Ivy repository with other patterns goes to its standard path") {
    val work = Files.createTempDirectory("ivy-layout-suite").toFile
    val source = new File(work, "source")
    // A repository laid out by a pattern of its own: flat, with the revision
    // in the file names.
    val pattern = "[organisation]/[module]/[artifact]-[revision].[ext]"
    val moduleDir = new File(source, "org.example/flat")
    moduleDir.mkdirs()
    Files.write(
      new File(moduleDir, "ivy-1.0.xml").toPath,
      """<ivy-module version="2.0">
        |  <info organisation="org.example" module="flat" revision="1.0"/>
        |  <configurations><conf name="compile"/><conf name="runtime" extends="compile"/><conf name="default" extends="runtime"/></configurations>
        |  <publications><artifact name="flat" type="jar" ext="jar" conf="compile"/></publications>
        |</ivy-module>
        |""".stripMargin.getBytes("UTF-8")
    )
    Files.write(new File(moduleDir, "flat-1.0.jar").toPath, Array[Byte](1, 2, 3))
    val resolver = Resolver.file("flat", source)(Patterns(Vector(pattern), Vector(pattern), false, false, false))
    val cache = new File(work, "cache")
    val repository = new File(work, "repository")
    val copied = RuntimeRepository.complete(
      repository,
      RuntimeRepository.Coordinates("org.example", "flat", "1.0"),
      CoursierConfiguration().withResolvers(Vector(resolver)).withCache(Some(cache)),
      cache,
      sbt.util.Logger.Null
    )
    assertEquals(copied.toSet, Set("org.example/flat/1.0/ivys/ivy.xml", "org.example/flat/1.0/jars/flat.jar"))
    assert(new File(repository, "org.example/flat/1.0/jars/flat.jar").isFile)
  }
}
