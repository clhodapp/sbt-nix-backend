package sbtnixbackend

import java.io.File
import java.net.{HttpURLConnection, URI}
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}

/** Writes a lock by running the sandboxed build once with the network.
  *
  * The run copies the build to a scratch directory and forks sbt there
  * with an empty coursier cache, home, boot directory and global base: the
  * same command and directory layout a Nix build uses. Every file the run
  * downloads into the cache is then locked by URL and hash. Whatever a
  * sandboxed build reads through coursier, this run read too, so the lock
  * is complete by construction rather than by listing what sbt needs.
  *
  * Two kinds of input do not go through the fork's cache on their own and
  * are placed in it first: the sbt launcher jar (the Nix build runs it
  * from the cache) and the declared network dependencies (fetched by
  * plugins in ways coursier does not see).
  */
object LockGeneration {

  final case class Inputs(
      buildRoot: File,
      workDir: File,
      sbtVersion: String,
      launcherUrl: String,
      javaOptions: Vector[String],
      projects: Vector[LockedProject],
      networkUrls: Vector[String],
      flakeRepositories: Vector[LockedFlakeRepository],
      flakeRepositoryPaths: Map[String, File],
      injectedPluginFile: Option[File],
      injectedPlugin: Option[InjectedPlugin],
      inputsHash: String
  )

  final class LockFailed(message: String) extends RuntimeException(message)

  /** Directories in the fork's home that sbt itself fills and a sandboxed
    * build fills the same way. Anything else written under the home was
    * fetched behind coursier's back and is reported.
    */
  private val expectedHomeEntries = Set(".sbt", ".ivy2", ".cache/sbt")

  def run(in: Inputs, info: String => Unit, warn: String => Unit): Lock = {
    val work = in.workDir
    deleteRecursively(work)
    val src = new File(work, "src")
    val home = new File(work, "home")
    val cache = new File(work, "coursier-cache")
    val output = new File(work, "output")
    // sbt puts its boot server socket under XDG_RUNTIME_DIR, and a Unix
    // socket path is limited to about 100 bytes, so this one is short.
    val runtime = Files.createTempDirectory("sbt-nix-run").toFile
    val javaTmp = new File(work, "java-tmp")
    Seq(src, home, cache, output, javaTmp).foreach(_.mkdirs())

    info(s"[sbt-nix-backend] copying the build to $src")
    copyBuild(in.buildRoot, src)

    val launcherPath = CachePaths.relativePath(in.launcherUrl)
    download(in.launcherUrl, new File(cache, launcherPath))
    in.networkUrls.foreach { url =>
      info(s"[sbt-nix-backend] fetching declared network dependency $url")
      download(url, new File(cache, CachePaths.relativePath(url)))
    }

    // The injected plugin goes in as the fork's only global plugin, as it
    // does in a Nix build: sbt loads it below every level of the build.
    val globalPlugins = in.injectedPluginFile.map { f =>
      val dir = new File(work, "global-plugins")
      dir.mkdirs()
      Files.copy(f.toPath, new File(dir, "sbt-nix-backend.sbt").toPath)
      dir
    }

    val javaHome = new File(System.getProperty("java.home"))
    val java = new File(javaHome, "bin/java").getAbsolutePath
    val ids = in.projects.map(_.id)
    if (ids.isEmpty) throw new LockFailed("no project of the build is packaged (every one sets publish / skip)")
    val command = (Seq("nixSandboxBuild", "--test-compile") ++ ids).mkString(" ")
    val cmd =
      Seq(java) ++ in.javaOptions ++ Seq(
        s"-Duser.home=$home",
        s"-Djava.io.tmpdir=$javaTmp",
        s"-Dsbt.global.base=$home/.sbt",
        s"-Dsbt.boot.directory=$home/.sbt/boot",
        s"-Dsbt.ivy.home=$home/.ivy2",
        "-Dsbt.server.autostart=false",
        // Without a boot server socket (its path may exceed the Unix
        // socket length limit), sbt otherwise exits.
        "-Dsbt.server.forcestart=true",
        "-Dsbt.log.noformat=true",
        "-Dsbt.supershell=false"
      ) ++ globalPlugins.map(d => s"-Dsbt.global.plugins=${d.getAbsolutePath}").toSeq ++
        Seq("-jar", new File(cache, launcherPath).getAbsolutePath, command)

    // The proxy settings may hold a password, and a command line is
    // readable by every local user, so they go through the environment,
    // which only the owner can read. The java launcher reads
    // JDK_JAVA_OPTIONS, with double quotes around each option.
    val javaOptionsEnv = (sys.env.get("JDK_JAVA_OPTIONS").filter(_.trim.nonEmpty).toSeq ++
      proxyProperties.map(p => "\"" + p + "\"")).mkString(" ")

    // The fork sees what a Nix build sees: no coursier settings beyond the
    // cache, and the sbt-nix-backend mode variables.
    val env: Map[String, Option[String]] = Map(
      "HOME" -> Some(home.getAbsolutePath),
      "XDG_RUNTIME_DIR" -> Some(runtime.getAbsolutePath),
      "COURSIER_CACHE" -> Some(cache.getAbsolutePath),
      "COURSIER_MODE" -> None,
      "COURSIER_REPOSITORIES" -> None,
      "COURSIER_CONFIG_DIR" -> None,
      "COURSIER_CREDENTIALS" -> None,
      "SBT_OPTS" -> None,
      "JDK_JAVA_OPTIONS" -> Some(javaOptionsEnv).filter(_.nonEmpty),
      "SBT_NIX_BACKEND" -> None,
      "SBT_NIX_MODE" -> Some("lock"),
      "SBT_NIX_OUTPUT" -> Some(output.getAbsolutePath),
      "SBT_NIX_FLAKE_REPOSITORIES" -> Some(
        Json.Obj(in.flakeRepositoryPaths.toVector.sortBy(_._1).map { case (k, v) => k -> Json.str(v.getAbsolutePath) }).render
      )
    )
    info(s"[sbt-nix-backend] running `$command` in a fresh sbt with an empty cache")
    val code =
      try Proc.stream(cmd, src, env, line => info("[sbt-nix-backend lock] " + line))
      finally deleteRecursively(runtime)
    if (code != 0)
      throw new LockFailed(
        s"the lock run failed (exit code $code); its output is above, and its directories are kept in $work"
      )

    val files = CachePaths.downloadedFiles(cache)
    val artifacts = files.map { rel =>
      val url =
        try CachePaths.url(rel)
        catch { case e: IllegalArgumentException => throw new LockFailed(e.getMessage) }
      LockedArtifact(rel, url, Lock.sriHash(new File(cache, rel)))
    }
    reportStrayFiles(home, warn)
    val snapshots = artifacts.filter(_.path.contains("-SNAPSHOT/"))
    if (snapshots.nonEmpty)
      warn(
        s"[sbt-nix-backend] the lock holds ${snapshots.size} SNAPSHOT files; a SNAPSHOT changes under its URL, " +
          "and the Nix fetch of it fails when it does: " + snapshots.take(5).map(_.url).mkString(", ")
      )
    Lock(
      sbtVersion = in.sbtVersion,
      javaVersion = System.getProperty("java.version"),
      launcher = launcherPath,
      javaOptions = in.javaOptions,
      injectedPlugin = in.injectedPlugin,
      projects = in.projects,
      flakeRepositories = in.flakeRepositories,
      artifacts = artifacts,
      inputsHash = in.inputsHash,
      definitionHash = Lock.buildDefinitionHash(in.buildRoot, Nil)
    )
  }

  /** The proxy settings of this JVM, so the fork downloads the way sbt does. */
  private def proxyProperties: Seq[String] =
    Seq(
      "http.proxyHost",
      "http.proxyPort",
      "http.nonProxyHosts",
      "https.proxyHost",
      "https.proxyPort",
      "http.proxyUser",
      "http.proxyPassword",
      "https.proxyUser",
      "https.proxyPassword",
      "socksProxyHost",
      "socksProxyPort"
    ).flatMap(k => Option(System.getProperty(k)).map(v => s"-D$k=$v"))

  private def reportStrayFiles(home: File, warn: String => Unit): Unit = {
    val base = home.toPath
    val stray = Vector.newBuilder[String]
    def walk(dir: File): Unit =
      Option(dir.listFiles()).getOrElse(Array.empty[File]).foreach { f =>
        val rel = base.relativize(f.toPath).toString.replace(File.separatorChar, '/')
        val expected = expectedHomeEntries.exists(e => rel == e || rel.startsWith(e + "/"))
        val prefixOfExpected = expectedHomeEntries.exists(e => e.startsWith(rel + "/"))
        if (!expected) {
          if (f.isDirectory && prefixOfExpected) walk(f)
          else stray += rel
        }
      }
    walk(home)
    val found = stray.result()
    if (found.nonEmpty)
      warn(
        "[sbt-nix-backend] the lock run wrote these under its home directory, outside the coursier cache; " +
          "a sandboxed build starts without them, so if a plugin downloaded them, declare their URLs in " +
          "nixNetworkDependencies: " + found.mkString(", ")
      )
  }

  /** Downloads `url` to `target` through java.net, honoring this JVM's
    * proxy settings and following redirects within the same protocol.
    */
  def download(url: String, target: File): Unit = {
    target.getParentFile.mkdirs()
    val tmp = new File(target.getPath + ".part")
    val conn = URI.create(url).toURL.openConnection()
    conn match {
      case h: HttpURLConnection =>
        h.setInstanceFollowRedirects(true)
        h.setRequestProperty("User-Agent", "sbt-nix-backend")
        val code = h.getResponseCode
        if (code != 200) throw new LockFailed(s"downloading $url failed with HTTP $code")
      case _ => ()
    }
    val in = conn.getInputStream
    try Files.copy(in, tmp.toPath, StandardCopyOption.REPLACE_EXISTING)
    finally in.close()
    Files.move(tmp.toPath, target.toPath, StandardCopyOption.REPLACE_EXISTING)
  }

  /** Path components the copy leaves out: build outputs and editor state.
    * The Nix library's default source filter leaves out the same ones.
    */
  def excluded(name: String): Boolean =
    Set("target", ".git", ".bsp", ".metals", ".bloop", ".idea").contains(name) || name.startsWith("result")

  /** Copies what a Nix build of `root` sees: in a git work tree, the
    * tracked files and the untracked ones git does not ignore (a flake's
    * source is the tracked files, and a file not yet added counts as part
    * of the build being locked); elsewhere, every file. Either way, no path
    * with an [[excluded]] component is copied: a build output left in the
    * copy would let the fork skip work, and with it the downloads that
    * work needs.
    */
  def copyBuild(root: File, dest: File): Unit = {
    val gitFiles =
      if (new File(root, ".git").exists())
        try
          Some(
            Proc
              .output(Seq("git", "-C", root.getAbsolutePath, "ls-files", "-z", "--cached", "--others", "--exclude-standard"))
              .split('\u0000')
              .toVector
              .filter(_.nonEmpty)
          )
        catch { case _: Proc.ProcessFailed => None }
      else None
    val rootPath = root.toPath
    gitFiles match {
      case Some(paths) =>
        paths.filterNot(_.split('/').exists(excluded)).foreach { rel =>
          val from = rootPath.resolve(rel)
          if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) copyPath(from, dest.toPath.resolve(rel))
        }
      case None =>
        def walk(dir: Path): Unit =
          Option(dir.toFile.listFiles()).getOrElse(Array.empty[File]).foreach { f =>
            if (!excluded(f.getName)) {
              val p = f.toPath
              if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) walk(p)
              else copyPath(p, dest.toPath.resolve(rootPath.relativize(p)))
            }
          }
        walk(rootPath)
    }
  }

  private def copyPath(from: Path, to: Path): Unit = {
    Files.createDirectories(to.getParent)
    if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
      // A git submodule: ls-files lists its directory as one entry.
      Files.createDirectories(to)
      Option(from.toFile.listFiles()).getOrElse(Array.empty[File]).foreach { f =>
        if (!excluded(f.getName)) copyPath(f.toPath, to.resolve(f.getName))
      }
    } else Files.copy(from, to, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
  }

  def deleteRecursively(f: File): Unit = {
    if (Files.isDirectory(f.toPath, LinkOption.NOFOLLOW_LINKS))
      Option(f.listFiles()).getOrElse(Array.empty[File]).foreach(deleteRecursively)
    if (Files.exists(f.toPath, LinkOption.NOFOLLOW_LINKS)) {
      f.setWritable(true)
      Files.delete(f.toPath)
    }
  }
}
