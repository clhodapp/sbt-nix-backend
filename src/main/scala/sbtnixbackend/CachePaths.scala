package sbtnixbackend

import java.io.File

/** The mapping between a URL and its place in a coursier cache.
  *
  * Coursier stores `https://host/a/b.jar` at `<cache>/https/host/a/b.jar`
  * (coursier.paths.CachePath.localFile): the URL minus its `://`, with a few
  * characters percent-escaped. The lock records every artifact by this
  * relative path, the Nix side rebuilds the cache from it, and declared
  * network dependencies are placed at the same path coursier would use, so
  * a plugin that fetches them through coursier finds them in the cache.
  */
object CachePaths {

  /** Characters coursier escapes, in addition to anything above 128. */
  private val unsafe = " %$&+,:;=?@<>#"

  private def isUnsafe(c: Char): Boolean = c > 128 || unsafe.indexOf(c.toInt) >= 0

  private def escape(s: String): String = {
    val sb = new StringBuilder
    s.foreach { c =>
      if (isUnsafe(c)) sb.append('%').append(hex(c / 16)).append(hex(c % 16))
      else sb.append(c)
    }
    sb.toString
  }

  private def hex(n: Int): Char = if (n < 10) ('0' + n).toChar else ('A' + n - 10).toChar

  /** The cache-relative path coursier uses for `url`.
    *
    * Throws for a `file:` URL (coursier reads those in place rather than
    * caching them) and for a URL with no absolute path.
    */
  def relativePath(url: String): String = {
    val idx = url.indexOf(':')
    if (idx <= 0) throw new IllegalArgumentException(s"no protocol in URL $url")
    val protocol = url.substring(0, idx)
    if (protocol == "file")
      throw new IllegalArgumentException(s"file: URLs are not cached by coursier: $url")
    var rest = url.substring(idx + 1)
    if (rest.startsWith("///")) rest = rest.substring(3)
    else if (rest.startsWith("/")) rest = rest.substring(1)
    else throw new IllegalArgumentException(s"URL $url does not contain an absolute path")
    if (rest.endsWith("/")) rest = rest + ".directory"
    while (rest.startsWith("/")) rest = rest.substring(1)
    escape(protocol + "/" + rest)
  }

  /** The URL a cache-relative path was downloaded from: the inverse of
    * [[relativePath]] for every URL without credentials.
    *
    * A path whose host part carries `user@` came from an authenticated
    * download; the lock cannot reproduce credentials, so this throws.
    */
  def url(relativePath: String): String = {
    val decoded = unescape(relativePath)
    val slash = decoded.indexOf('/')
    if (slash <= 0) throw new IllegalArgumentException(s"not a coursier cache path: $relativePath")
    val protocol = decoded.substring(0, slash)
    var rest = decoded.substring(slash + 1)
    val host = rest.takeWhile(_ != '/')
    if (host.contains('@'))
      throw new IllegalArgumentException(
        s"$relativePath was downloaded with credentials ($host); a lock cannot record those"
      )
    if (rest.endsWith("/.directory")) rest = rest.stripSuffix(".directory")
    protocol + "://" + rest
  }

  private def unescape(s: String): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (c == '%' && i + 2 < s.length) {
        val hi = Character.digit(s.charAt(i + 1), 16)
        val lo = Character.digit(s.charAt(i + 2), 16)
        if (hi < 0 || lo < 0) throw new IllegalArgumentException(s"bad escape in $s")
        sb.append((hi * 16 + lo).toChar)
        i += 3
      } else if (c == '%') {
        throw new IllegalArgumentException(s"truncated escape in $s")
      } else {
        sb.append(c)
        i += 1
      }
    }
    sb.toString
  }

  /** Whether a file in a coursier cache is coursier's own bookkeeping
    * (checksums, `.checked` markers, `.error` markers, locks) rather than a
    * downloaded file. All of them are dot-prefixed. The one dot-prefixed
    * download is `.directory`, which holds the listing of a URL that ends in
    * `/` (coursier reads these for version ranges in Ivy repositories).
    */
  def isBookkeeping(name: String): Boolean = name.startsWith(".") && name != ".directory"

  /** Every downloaded file under `cache`, as sorted cache-relative paths
    * with `/` separators.
    */
  def downloadedFiles(cache: File): Vector[String] = {
    val base = cache.toPath
    val out = Vector.newBuilder[String]
    def walk(dir: File): Unit = {
      val children = Option(dir.listFiles()).getOrElse(Array.empty[File]).sortBy(_.getName)
      children.foreach { f =>
        if (f.isDirectory) walk(f)
        else if (!isBookkeeping(f.getName))
          out += base.relativize(f.toPath).toString.replace(File.separatorChar, '/')
      }
    }
    if (cache.isDirectory) walk(cache)
    out.result().sorted
  }
}
