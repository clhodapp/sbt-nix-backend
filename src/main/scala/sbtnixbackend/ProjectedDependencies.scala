package sbtnixbackend

import java.io.File
import sbt._
import scala.xml.{Elem, Node, Text}

/** Native coordinates supplied by Nix derivations, rather than download locks. */
object ProjectedDependencies {
  def read(environment: Map[String, String]): Vector[ModuleID] =
    environment
      .get("SBT_PURIFIER_PROVIDED_CATALOG")
      .filter(_.nonEmpty)
      .fold(Vector.empty[ModuleID])(path => readCatalog(new File(path)))

  def readCatalog(file: File): Vector[ModuleID] = readCatalog(file, publications = false)

  def readCatalog(file: File, publications: Boolean): Vector[ModuleID] = {
    val catalog = Json.parse(IO.read(file)).asObject
    if (catalog("version").asInt != 1) sys.error("sbt-purifier: unsupported JVM projection catalog")
    val entries = if (publications) catalog.get("publications").getOrElse(catalog("modules")) else catalog("modules")
    entries.asArray.map { entry =>
      val module = entry.asObject
      ModuleID(module("group").asString, module("name").asString, module("version").asString)
    }
  }

  def merge(modules: Seq[ModuleID]): Vector[ModuleID] =
    modules.groupBy(module => module.organization -> module.name).toVector.sortBy(_._1).map {
      case ((group, name), values) =>
        if (values.map(_.revision).distinct.size != 1)
          sys.error(s"sbt-purifier: conflicting Nix providers for $group:$name")
        values.head
    }

  def select(module: ModuleID, scalaFull: String, scalaBinary: String, supplied: Seq[ModuleID]): ModuleID = {
    val name = CrossVersion(module.crossVersion, scalaFull, scalaBinary).fold(module.name)(f => f(module.name))
    val attributes = module.extraAttributes
    val pluginName = for {
      scala <- attributes.get("e:scalaVersion").orElse(attributes.get("scalaVersion"))
      sbt <- attributes.get("e:sbtVersion").orElse(attributes.get("sbtVersion"))
    } yield module.name + "_" + scala + "_" + sbt
    supplied
      .find(m => m.organization == module.organization && (m.name == name || pluginName.exists(_ == m.name)))
      .fold(module) { selected =>
        Compat.moduleRevision(module, selected.revision)
      }
  }

  /** POMs must describe the selected modules for downstreams without Nix. */
  def pom(node: Node, supplied: Seq[ModuleID]): Node = node match {
    case element: Elem =>
      val rewritten = element.copy(child = element.child.map(child => pom(child, supplied)))
      if (Set("dependency", "parent", "plugin", "extension")(element.label)) {
        val group = (element \ "groupId").text
        val name = (element \ "artifactId").text
        supplied.find(m => m.organization == group && m.name == name).fold(rewritten) { selected =>
          val version = element.child
            .collectFirst { case e: Elem if e.label == "version" => e }
            .getOrElse(Elem(element.prefix, "version", scala.xml.Null, element.scope, false))
            .copy(child = Seq(Text(selected.revision)))
          val children = rewritten.child.filterNot(child =>
            child.label == "version" ||
              (element.label == "parent" && child.label == "relativePath")
          )
          val selectedParent =
            if (element.label == "parent")
              children :+ Elem(element.prefix, "relativePath", scala.xml.Null, element.scope, false, Text(""))
            else children
          rewritten.copy(child = selectedParent :+ version)
        }
      } else rewritten
    case other => other
  }
}
