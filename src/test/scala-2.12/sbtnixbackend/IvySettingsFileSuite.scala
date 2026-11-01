package sbtnixbackend

import java.io.File
import java.nio.file.Files

import sbt.librarymanagement.{MavenRepository, URLRepository}

class IvySettingsFileSuite extends munit.FunSuite {

  private def write(f: File, text: String): Unit = Files.write(f.toPath, text.getBytes("UTF-8"))

  private def fixture(): File = {
    val dir = Files.createTempDirectory("ivy-settings-suite").toFile
    write(
      new File(dir, "ivysettings.xml"),
      """<ivysettings>
        |  <properties file="${ivy.settings.dir}/repositories.properties"/>
        |  <settings defaultResolver="main"/>
        |  <include file="resolvers.xml"/>
        |</ivysettings>
        |""".stripMargin
    )
    write(new File(dir, "repositories.properties"), "central.root=https://repo1.maven.org/maven2/\n")
    write(
      new File(dir, "resolvers.xml"),
      """<ivysettings>
        |  <resolvers>
        |    <chain name="main">
        |      <ibiblio name="central" m2compatible="true" root="${central.root}"/>
        |      <url name="flat">
        |        <ivy pattern="https://ivy.example.com/repo/[organisation]/[module]/[revision]/ivy.xml"/>
        |        <artifact pattern="https://ivy.example.com/repo/[organisation]/[module]/[revision]/[artifact].[ext]"/>
        |      </url>
        |    </chain>
        |  </resolvers>
        |</ivysettings>
        |""".stripMargin
    )
    dir
  }

  private val mirror = "file:/cache"

  test("the copy reads every remote URL through the mirror, in included files and properties too") {
    val source = fixture()
    val copy = IvySettingsFile.copy(new File(source, "ivysettings.xml").toURI, new File(source, "copy"), Some(mirror))
    val text = copy.files.map(f => new String(Files.readAllBytes(f.toPath), "UTF-8")).mkString("\n")
    assert(!text.contains("https://"), text)
    assert(text.contains("file:/cache/https/ivy.example.com/repo/[organisation]"), text)
    val properties = new java.util.Properties
    val in = Files.newInputStream(copy.files.find(_.getName.endsWith(".properties")).get.toPath)
    try properties.load(in)
    finally in.close()
    assertEquals(properties.getProperty("central.root"), "file:/cache/https/repo1.maven.org/maven2/")
  }

  test("the resolvers of a copy are at the URLs the mirror mirrors") {
    val source = fixture()
    val copy = IvySettingsFile.copy(new File(source, "ivysettings.xml").toURI, new File(source, "copy"), Some(mirror))
    IvySettingsFile.withCaches(copy.top, new File(source, "home"))
    val resolvers = IvySettingsFile.resolvers(copy.top.toURI, Some(mirror))
    assert(resolvers.collect { case m: MavenRepository => m.root }.contains("https://repo1.maven.org/maven2/"), resolvers.toString)
    val flat = resolvers.collect { case u: URLRepository => u }
    assertEquals(flat.flatMap(_.patterns.ivyPatterns), Vector("https://ivy.example.com/repo/[organisation]/[module]/[revision]/ivy.xml"))
  }

  test("without a mirror the copy keeps the URLs") {
    val source = fixture()
    val copy = IvySettingsFile.copy(new File(source, "ivysettings.xml").toURI, new File(source, "copy"), None)
    val text = copy.files.map(f => new String(Files.readAllBytes(f.toPath), "UTF-8")).mkString("\n")
    assert(text.contains("https://ivy.example.com/repo/"), text)
  }
}
