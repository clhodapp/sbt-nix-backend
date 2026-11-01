package sbtnixbackend

import java.io.{ByteArrayInputStream, File}
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

import scala.collection.JavaConverters._
import scala.util.matching.Regex

import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.resolver.{
  AbstractPatternsBasedResolver,
  ChainResolver,
  DependencyResolver,
  FileSystemResolver,
  IBiblioResolver
}
import org.w3c.dom.{Document, Element}
import sbt.librarymanagement.{FileRepository, MavenRepository, Patterns, Resolver, URLRepository}

/** An Ivy settings file (`externalIvySettings`) read through the mirror of
  * the locked downloads (see IvyMirror).
  *
  * Ivy interprets the settings itself, so the plugin hands it a copy in
  * which every remote URL is on the mirror: attribute values, the values of
  * included properties files, and every included settings file, copied the
  * same way. A remote file the settings include is read through the mirror
  * too, so the lock run locks it. The copy ends with a `caches` element
  * that puts Ivy's cache in the project's Ivy home.
  *
  * Coursier, which builds the repository outputs, cannot read Ivy settings,
  * so [[resolvers]] turns the resolvers of a copy into sbt resolvers at
  * their real URLs.
  */
object IvySettingsFile {

  /** The files of a copy: the top settings file first. */
  final case class Copy(top: File, files: Vector[File])

  private val remoteUrl: Regex = """https?://[^\s\[\]()$"'<>]*""".r

  private def isRemote(s: String): Boolean = s.startsWith("http://") || s.startsWith("https://")

  /** Copies the settings at `source`, and what they include, into `dir`. */
  def copy(source: URI, dir: File, mirror: Option[String]): Copy = {
    dir.mkdirs()
    val files = Vector.newBuilder[File]
    var count = 0
    def next(suffix: String): File = {
      count += 1
      new File(dir, s"$count-$suffix")
    }

    // Every file of the copy is in one directory, so a reference to another
    // is relative to the directory of the file that holds it, and the copy
    // can move.
    def sibling(f: File): String = "${ivy.settings.dir}/" + f.getName

    def read(uri: URI): Array[Byte] = {
      val s = uri.toString
      val actual = if (isRemote(s)) mirror.flatMap(IvyMirror.rewritePrefix(s, _)).getOrElse(s) else s
      val in = URI.create(actual).toURL.openStream()
      try {
        val out = new java.io.ByteArrayOutputStream
        val buffer = new Array[Byte](8192)
        Iterator.continually(in.read(buffer)).takeWhile(_ >= 0).foreach(n => out.write(buffer, 0, n))
        out.toByteArray
      } finally in.close()
    }

    def settingsDir(uri: URI): String =
      if (uri.getScheme == "file") new File(uri).getParentFile.getAbsolutePath
      else uri.resolve(".").toString.stripSuffix("/")

    def value(v: String, dirOf: String): String = {
      val substituted = v.replace("${ivy.settings.dir}", dirOf)
      mirror.fold(substituted) { m =>
        remoteUrl.replaceAllIn(substituted, u => Regex.quoteReplacement(IvyMirror.rewritePrefix(u.matched, m).getOrElse(u.matched)))
      }
    }

    // The location an include or properties element names, with
    // ${ivy.settings.dir} and system properties substituted, relative to the
    // file that names it.
    def location(target: String, from: URI): URI = {
      val substituted = "\\$\\{([^}]+)\\}".r.replaceAllIn(
        target.replace("${ivy.settings.dir}", settingsDir(from)),
        m => Regex.quoteReplacement(sys.props.getOrElse(m.group(1), m.matched))
      )
      if (isRemote(substituted) || substituted.startsWith("file:")) URI.create(substituted)
      else if (from.getScheme == "file") {
        val f = new File(substituted)
        (if (f.isAbsolute) f else new File(settingsDir(from), substituted)).toURI
      } else from.resolve(substituted)
    }

    def copySettings(uri: URI): File = {
      val doc = parse(read(uri))
      val dirOf = settingsDir(uri)
      def walk(e: Element): Unit = {
        val target = Option(e.getAttribute("file")).filter(_.nonEmpty).orElse(Option(e.getAttribute("url")).filter(_.nonEmpty))
        (e.getTagName, target) match {
          case ("include", Some(t)) =>
            val copied = copySettings(location(t, uri))
            e.removeAttribute("url")
            e.setAttribute("file", sibling(copied))
          case ("properties", Some(t)) =>
            val properties = new java.util.Properties
            properties.load(new ByteArrayInputStream(read(location(t, uri))))
            val rewritten = new java.util.Properties
            properties.stringPropertyNames.asScala.foreach(k => rewritten.setProperty(k, value(properties.getProperty(k), dirOf)))
            val out = next("ivy.properties")
            val stream = Files.newOutputStream(out.toPath)
            try rewritten.store(stream, null)
            finally stream.close()
            files += out
            e.removeAttribute("url")
            e.setAttribute("file", sibling(out))
          case _ =>
            val attributes = e.getAttributes
            (0 until attributes.getLength).map(attributes.item).foreach(a => a.setNodeValue(value(a.getNodeValue, dirOf)))
        }
        val children = e.getChildNodes
        (0 until children.getLength).map(children.item).foreach {
          case c: Element => walk(c)
          case _          => ()
        }
      }
      walk(doc.getDocumentElement)
      val out = next("ivysettings.xml")
      write(doc, out)
      files += out
      out
    }

    val top = copySettings(source)
    val all = files.result()
    Copy(top, top +: all.filterNot(_ == top))
  }

  /** A digest of the files of `copy`, which name every repository the
    * settings read.
    */
  def digest(copy: Copy): String = {
    val md = MessageDigest.getInstance("SHA-256")
    copy.files.foreach(f => md.update(Files.readAllBytes(f.toPath)))
    md.digest().take(10).map(b => f"${b & 0xff}%02x").mkString
  }

  /** Puts Ivy's caches of the settings in `top` under `cacheDir`. */
  def withCaches(top: File, cacheDir: File): Unit = {
    val doc = parse(Files.readAllBytes(top.toPath))
    val caches = doc.createElement("caches")
    caches.setAttribute("defaultCacheDir", new File(cacheDir, "cache").getAbsolutePath)
    caches.setAttribute("resolutionCacheDir", new File(cacheDir, "resolution-cache").getAbsolutePath)
    doc.getDocumentElement.appendChild(caches)
    write(doc, top)
  }

  /** The resolvers of the settings at `uri` as sbt resolvers, with URLs on
    * the mirror at `mirror` turned back into the URLs they mirror.
    */
  def resolvers(uri: URI, mirror: Option[String]): Vector[Resolver] = {
    val settings = new IvySettings
    settings.load(uri.toURL)
    def real(s: String): String = mirror.fold(s)(m => IvyMirror.unrewritePrefix(s, m))
    def convert(r: DependencyResolver): Vector[Resolver] = r match {
      case c: ChainResolver =>
        c.getResolvers.asScala.toVector.collect { case d: DependencyResolver => d }.flatMap(convert)
      case i: IBiblioResolver if i.isM2compatible && i.isUsepoms =>
        Vector(MavenRepository(i.getName, real(i.getRoot)).withAllowInsecureProtocol(true))
      case p: AbstractPatternsBasedResolver =>
        val ivy = p.getIvyPatterns.asScala.map(x => real(x.toString)).toVector
        val artifact = p.getArtifactPatterns.asScala.map(x => real(x.toString)).toVector
        val patterns = Patterns(ivy, artifact, p.isM2compatible, false, false)
        p match {
          case _: FileSystemResolver => Vector(FileRepository(p.getName, Resolver.defaultFileConfiguration, patterns))
          case _                     => Vector(URLRepository(p.getName, patterns, true))
        }
      case _ => Vector.empty
    }
    settings.getResolverNames.asScala.toVector
      .map(n => settings.getResolver(n.toString))
      .flatMap(convert)
      .distinct
  }

  private def parse(bytes: Array[Byte]): Document = {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes))
  }

  private def write(doc: Document, file: File): Unit = {
    val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
    transformer.transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(file))
  }
}
