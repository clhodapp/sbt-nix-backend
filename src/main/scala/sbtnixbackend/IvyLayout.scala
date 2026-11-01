package sbtnixbackend

import java.io.File

import sbt.librarymanagement.{FileRepository, MavenCache, Patterns, Resolver}

/** The Ivy layout of the plugin's outputs.
  *
  * A repository output keeps every module in the layout it was published
  * in: a Maven module at its Maven path, and an Ivy module at its path in
  * sbt's standard Ivy layout ([[Resolver.ivyStylePatterns]], the layout of
  * `publishLocal` and of the sbt plugin repositories). The two layouts
  * share one directory: a Maven path starts with the organization's
  * segments split at dots, and an Ivy path with the organization whole, and
  * neither layout reads a file the other wrote. A consumer reads the
  * directory with [[resolvers]].
  */
object IvyLayout {

  /** The Ivy and artifact patterns of the layout, relative to its root. */
  val patterns: Patterns = Resolver.ivyStylePatterns

  /** A Maven resolver and an Ivy resolver on `dir`, in that order. */
  def resolvers(name: String, dir: File): Vector[Resolver] =
    Vector(MavenCache(name, dir), ivy(name + "-ivy", dir))

  /** The Ivy resolver on `dir`, with its patterns below the directory. */
  def ivy(name: String, dir: File): Resolver = {
    val base = dir.getAbsolutePath.stripSuffix(File.separator) + "/"
    FileRepository(
      name,
      Resolver.defaultFileConfiguration,
      Patterns(
        patterns.ivyPatterns.map(base + _),
        patterns.artifactPatterns.map(base + _),
        patterns.isMavenCompatible,
        patterns.descriptorOptional,
        patterns.skipConsistencyCheck
      )
    )
  }

  /** The extra attributes an sbt plugin published Ivy-style carries. */
  def pluginAttributes(scalaBinary: String, sbtBinary: String): Map[String, String] =
    Map("e:scalaVersion" -> scalaBinary, "e:sbtVersion" -> sbtBinary)

  /** `pattern` with its tokens substituted, as Ivy substitutes them: a
    * `[token]` becomes its value, and a parenthesized part is kept only when
    * every token in it has a non-empty value. A token without a value
    * outside parentheses makes the result None.
    */
  def render(pattern: String, tokens: Map[String, String]): Option[String] = {
    val out = new StringBuilder
    var i = 0
    var failed = false
    while (i < pattern.length && !failed) {
      pattern.charAt(i) match {
        case '(' =>
          val end = pattern.indexOf(')', i)
          if (end < 0) failed = true
          else {
            render(pattern.substring(i + 1, end), tokens).foreach(out ++= _)
            i = end + 1
          }
        case '[' =>
          val end = pattern.indexOf(']', i)
          val value = if (end < 0) None else tokens.get(pattern.substring(i + 1, end)).filter(_.nonEmpty)
          value match {
            case Some(v) =>
              out ++= v
              i = end + 1
            case None => failed = true
          }
        case c =>
          out += c
          i += 1
      }
    }
    if (failed) None else Some(out.result())
  }

  /** The tokens of a module: the organization, name, revision and extra
    * attributes (with or without their `e:` prefix). The organization is
    * split at dots for a Maven-compatible pattern.
    */
  def moduleTokens(
      organization: String,
      name: String,
      revision: String,
      extra: Map[String, String],
      mavenCompatible: Boolean
  ): Map[String, String] =
    extra.map { case (k, v) => k.stripPrefix("e:") -> v } ++ Map(
      "organisation" -> (if (mavenCompatible) organization.replace('.', '/') else organization),
      "organization" -> (if (mavenCompatible) organization.replace('.', '/') else organization),
      "orgPath" -> organization.replace('.', '/'),
      "module" -> name,
      "revision" -> revision
    )

  /** The directory of an artifact type in the layout, as sbt 2 names it. */
  def typeDirectory(`type`: String): String = `type` match {
    case "src" | "source" | "sources"            => "srcs"
    case "doc" | "docs" | "javadoc" | "javadocs" => "docs"
    case other                                   => other + "s"
  }

  /** The tokens of a module descriptor (`ivy.xml`). */
  val descriptorTokens: Map[String, String] = Map("type" -> "ivy", "artifact" -> "ivy", "ext" -> "xml")

  /** The tokens of an artifact. */
  def artifactTokens(name: String, `type`: String, extension: String, classifier: Option[String]): Map[String, String] =
    Map("artifact" -> name, "type" -> `type`, "ext" -> extension) ++ classifier.map("classifier" -> _)
}
