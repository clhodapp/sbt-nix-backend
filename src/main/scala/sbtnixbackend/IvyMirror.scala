package sbtnixbackend

import java.io.File
import java.net.{HttpURLConnection, InetSocketAddress, URI}
import java.nio.file.{Files, StandardCopyOption}
import java.util.concurrent.Executors

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import sbt.librarymanagement.{MavenRepository, Patterns, Resolver, URLRepository}

/** Dependency resolution through Apache Ivy (`useCoursier := false`, sbt 1
  * only) on the locked downloads.
  *
  * The coursier cache of a lock mirrors the URLs it holds: the file of
  * `https://host/path` is at `https/host/path`. A build that resolves with
  * Ivy has every remote resolver rewritten onto a mirror of that layout:
  *
  *  - in a Nix build and interactively, the cache itself, as a `file:` URL,
  *    so Ivy reads exactly the locked files;
  *  - in the lock run, a [[IvyMirror.Server]] in front of the network, which
  *    fetches each file Ivy asks for into the fork's cache at its coursier
  *    path, so the lock collects it with the rest.
  */
object IvyMirror {

  /** The environment variable that names the mirror in the lock run. */
  val Variable = "SBT_NIX_IVY_MIRROR"

  /** The mirror path of a URL prefix: `https://host:8080/a/` becomes
    * `<base>/https/host%3A8080/a/`. A prefix that is not an absolute
    * http(s) URL is not rewritten.
    */
  def rewritePrefix(prefix: String, base: String): Option[String] =
    if (!(prefix.startsWith("http://") || prefix.startsWith("https://"))) None
    else {
      val slash = prefix.endsWith("/")
      val rel = CachePaths.relativePath(if (slash) prefix.stripSuffix("/") else prefix)
      Some(base.stripSuffix("/") + "/" + rel + (if (slash) "/" else ""))
    }

  /** `resolver` reading from the mirror at `base` instead of the network;
    * a resolver that is not a remote Maven or URL repository is left alone.
    */
  def rewrite(resolver: Resolver, base: String): Resolver = resolver match {
    case m: MavenRepository if !m.root.startsWith("file:") =>
      rewritePrefix(m.root, base).fold[Resolver](m) { root =>
        MavenRepository(m.name, root).withAllowInsecureProtocol(true)
      }
    case u: URLRepository =>
      def patterns(ps: Vector[String]) = ps.map(p => rewritePrefix(p, base).getOrElse(p))
      val p = u.patterns
      URLRepository(
        u.name,
        Patterns(patterns(p.ivyPatterns), patterns(p.artifactPatterns), p.isMavenCompatible, p.descriptorOptional, p.skipConsistencyCheck),
        true
      )
    case other => other
  }

  /** A local HTTP server in front of the network for the lock run: a GET or
    * HEAD of `/https/host/path` answers with the file of
    * `https://host/path`, downloaded once into `cache` at its coursier
    * path. An upstream 404 is a 404, and nothing is stored.
    */
  final class Server(cache: File) {
    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64)
    server.setExecutor(Executors.newFixedThreadPool(8))
    server.createContext(
      "/",
      new HttpHandler {
        def handle(exchange: HttpExchange): Unit =
          try serve(exchange)
          catch { case e: Exception => respond(exchange, 502, None) }
          finally exchange.close()
      }
    )
    server.start()

    val base: String = s"http://127.0.0.1:${server.getAddress.getPort}"

    def stop(): Unit = server.stop(0)

    private val lock = new Object

    private def serve(exchange: HttpExchange): Unit = {
      val rawPath = exchange.getRequestURI.getRawPath.stripPrefix("/")
      // The request path is the coursier path; coursier made its percent
      // escapes, so they stay as they are.
      val rel = if (rawPath.endsWith("/")) rawPath + ".directory" else rawPath
      val file = new File(cache, rel)
      val found = lock.synchronized {
        file.isFile || fetch(CachePaths.url(rel), file)
      }
      if (!found) respond(exchange, 404, None)
      else respond(exchange, 200, if (exchange.getRequestMethod == "HEAD") None else Some(file))
    }

    private def fetch(url: String, target: File): Boolean = {
      val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
      conn.setInstanceFollowRedirects(true)
      conn.setRequestProperty("User-Agent", "sbt-nix-backend")
      conn.getResponseCode match {
        case 200 =>
          target.getParentFile.mkdirs()
          val tmp = new File(target.getPath + ".part")
          val in = conn.getInputStream
          try Files.copy(in, tmp.toPath, StandardCopyOption.REPLACE_EXISTING)
          finally in.close()
          Files.move(tmp.toPath, target.toPath, StandardCopyOption.REPLACE_EXISTING)
          true
        case 404 | 410 => false
        case code      => throw new IllegalStateException(s"$url: HTTP $code")
      }
    }

    private def respond(exchange: HttpExchange, code: Int, body: Option[File]): Unit = {
      val length = body.map(_.length).getOrElse(-1L)
      if (code == 200 && body.isEmpty) exchange.sendResponseHeaders(200, -1)
      else exchange.sendResponseHeaders(code, if (code == 200) length else -1)
      body.foreach(f => Files.copy(f.toPath, exchange.getResponseBody))
    }
  }
}
