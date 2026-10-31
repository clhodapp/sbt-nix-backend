package sbtnixbackend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import lmcoursier.definitions.CachePolicy
import sbt._
import sbt.Keys._
import sbt.librarymanagement.MavenCache

/** sbt-nix-backend: Nix as the dependency and build backend of sbt.
  *
  * The plugin runs in one of three modes, chosen by the environment:
  *
  *  - sandbox (`SBT_NIX_MODE=sandbox`): inside a Nix build. Resolution reads
  *    the coursier cache the build was given (`COURSIER_CACHE`), offline.
  *  - lock (`SBT_NIX_MODE=lock`): inside the sbt that `nixLock` forks. The
  *    same build as in the sandbox, with an empty cache and the network.
  *  - interactive (neither): the sbt a developer runs. With a lock present,
  *    resolution reads a cache built from the lock's store paths, offline,
  *    so compile, run and package are incremental builds on the locked
  *    downloads (feature 3).
  */
object NixBackendPlugin extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  object autoImport {

    /** A flake reference to an sbt-nix-backend repository output: `ref` is
      * a flake reference whose fragment names the attribute under
      * `sbtRepositories.<system>` (`github:owner/repo/<rev>#core`); the
      * fragment defaults to `name`.
      */
    final case class NixFlakeRepository(name: String, ref: String) {
      def flakeRef: String = ref.takeWhile(_ != '#')
      def attribute: String = if (ref.contains('#')) ref.dropWhile(_ != '#').drop(1) else name
    }

    val nixBuildRoot = settingKey[File](
      "The root of the concrete build. In a meta-build (project/, project/project/, ...) it is the build " +
        "the meta-build defines, so every level reads the one lockfile and coursier cache."
    )
    val nixLockFile = settingKey[File](
      "The lockfile, which the flake's lib.sbt-nix-backend.buildSbtProject builds the build from " +
        "(default: sbt-nix-backend.lock.json in the build root)."
    )
    val nixBackend = settingKey[Boolean](
      "Whether interactive resolution reads the locked downloads through Nix, offline. " +
        "Defaults to true when the lock file exists; SBT_NIX_BACKEND=0 turns it off."
    )
    val nixPackaged = taskKey[Boolean](
      "Whether the lockfile names this project as a package to build (default: !(publish / skip))."
    )
    val nixNetworkDependencies = taskKey[Seq[String]](
      "URLs this project's build downloads outside coursier. nixLock fetches and locks them, and sandboxed " +
        "builds find them in the coursier cache at the path coursier would use (see nixNetworkFile). " +
        "A plugin can declare them without depending on sbt-nix-backend by defining a task key of " +
        "the same name and type, and read them from the cache the system property " +
        "sbt.nix.backend.coursierCache names."
    )
    val nixFlakeRepositories = settingKey[Seq[NixFlakeRepository]](
      "Flake references to sbt-nix-backend repositories that resolution reads before the other resolvers (feature 5)."
    )
    val nixFlakeRepositoryPaths = settingKey[Map[String, File]](
      "The store path of each flake repository, by name."
    )
    val nixSbtJavaOptions = settingKey[Seq[String]](
      "JVM options of the sbt that nixLock and the Nix builds run."
    )
    val nixLauncherUrl = settingKey[String]("Where the sbt launcher jar of this build's sbt version is downloaded from.")
    val nixCoursierCache = taskKey[File](
      "Interactive mode: realizes the lock through Nix and returns the coursier cache built from its store paths."
    )
    val nixLock = taskKey[File](
      "Writes the lockfile when the build definition changed since the last one: runs the sandboxed build " +
        "once, with an empty cache and the network, and locks every file it downloaded."
    )
    val nixRelock = taskKey[File]("Writes the lockfile whether or not the build definition changed.")
    val nixNixpkgs = settingKey[String](
      "The nixpkgs nixPackagedArtifacts builds with: a nixpkgs source directory or a flake reference. " +
        "The sbt-nix-backend sbt wrapper sets the nixpkgs its flake pins; elsewhere the default is flake:nixpkgs."
    )
    val nixPackagedArtifacts = taskKey[Map[Artifact, File]](
      "Like packagedArtifacts, but built by Nix in its sandbox from the lockfile (relocking first if the " +
        "build definition changed): each artifact this project declares, as a file in the Nix build's output."
    )
    val nixRuntimeRepository = taskKey[File](
      "Builds this project's repository output in the Nix sandbox: a Maven repository of its artifacts and " +
        "their runtime dependencies."
    )
    val nixLockUpdate = taskKey[File]("Like nixRelock, and also locks every flake repository afresh.")
    val nixPublishArtifacts =
      taskKey[File]("Sandbox: publishes packagedArtifacts as a Maven repository under SBT_NIX_OUTPUT.")
    val nixPublishRepository = taskKey[File](
      "Sandbox: writes this project, its internal dependencies and their runtime dependencies as a Maven " +
        "repository under SBT_NIX_OUTPUT."
    )
    val nixLockedProject = taskKey[Option[LockedProject]]("How the lock names this project, if it is packaged.")

    /** The module a flake repository publishes as its primary module, read
      * from the repository's `sbt-nix-repository.json`.
      */
    def nixFlakeModule(name: String): Def.Initialize[ModuleID] = Def.setting {
      val paths = (ThisBuild / nixFlakeRepositoryPaths).value
      val dir = paths.getOrElse(
        name,
        sys.error(s"sbt-nix-backend: no flake repository named $name in ThisBuild / nixFlakeRepositories")
      )
      val meta = Json.parse(new String(Files.readAllBytes(new File(dir, RepositoryMetadata).toPath), StandardCharsets.UTF_8)).asObject
      ModuleID(meta("organization").asString, meta("name").asString, meta("version").asString)
    }

    /** Where a declared network dependency is in the coursier cache, as a
      * task for a plugin to read the file from.
      */
    def nixNetworkFile(url: String): Def.Initialize[Task[File]] = Def.task {
      val dir = Mode.current match {
        case Mode.Interactive if (ThisBuild / nixBackend).value => (ThisBuild / nixCoursierCache).value
        case Mode.Interactive => ((ThisBuild / nixBuildRoot).value / "target") / "sbt-nix-backend" / "network"
        case _                => Mode.coursierCacheFromEnv
      }
      val f = dir / CachePaths.relativePath(url)
      if (!f.exists() && Mode.current == Mode.Interactive && !(ThisBuild / nixBackend).value)
        LockGeneration.download(url, f)
      if (!f.exists())
        sys.error(s"sbt-nix-backend: $url is not in the coursier cache; declare it in nixNetworkDependencies and relock")
      f
    }
  }

  import autoImport._

  /** The file at the root of every repository the plugin writes, naming
    * the module it was built for.
    */
  val RepositoryMetadata = "sbt-nix-repository.json"

  sealed trait Mode
  object Mode {
    case object Sandbox extends Mode
    case object Lock extends Mode
    case object Interactive extends Mode

    lazy val current: Mode = sys.env.get("SBT_NIX_MODE") match {
      case Some("sandbox") => Sandbox
      case Some("lock")    => Lock
      case None | Some("") => Interactive
      case Some(other)     => sys.error(s"sbt-nix-backend: unknown SBT_NIX_MODE '$other'")
    }

    def output: File = new File(
      sys.env.getOrElse("SBT_NIX_OUTPUT", sys.error("sbt-nix-backend: SBT_NIX_OUTPUT is not set"))
    )

    def coursierCacheFromEnv: File = new File(
      sys.env.getOrElse("COURSIER_CACHE", sys.error("sbt-nix-backend: COURSIER_CACHE is not set"))
    )

    def flakeRepositoryPathsFromEnv: Map[String, File] =
      sys.env.get("SBT_NIX_FLAKE_REPOSITORIES").filter(_.trim.nonEmpty) match {
        case None => Map.empty
        case Some(json) =>
          Json.parse(json).asObject.fields.map { case (k, v) => k -> new File(v.asString) }.toMap
      }
  }

  /** The coordinates this plugin was published under, from its jar
    * manifest. A build that compiles the plugin from source (this
    * repository's own) has no manifest, and then no injected plugin.
    */
  lazy val pluginCoordinates: Option[InjectedPlugin] = {
    val pkg = getClass.getPackage
    for {
      org <- Option(pkg.getImplementationVendor)
      name <- Option(pkg.getImplementationTitle)
      version <- Option(pkg.getImplementationVersion)
    } yield InjectedPlugin(org, name, version)
  }

  def pluginVersion: String = pluginCoordinates.map(_.version).getOrElse("(from source)")

  /** The plugin file the sbt-nix-backend `sbt` wrapper put into the global
    * plugins it starts sbt with; the wrapper names the file in this system
    * property too.
    */
  def injectedPluginFile: Option[File] =
    sys.props.get("sbt.nix.backend.inject").filter(_.nonEmpty).map(new File(_))

  private val NixOutputResolver = "sbt-nix-output"

  override def globalSettings: Seq[Setting[_]] = Seq(
    commands += sandboxBuildCommand,
    // The default lives in Global only, so a project-level append from any
    // plugin survives whatever order the plugins apply in.
    nixNetworkDependencies := Nil,
    onLoad := onLoad.value.andThen(publishCoursierCache)
  )

  /** The system property that names the coursier cache holding the locked
    * downloads, including every declared network dependency at the path
    * coursier would use. A plugin that bridges itself without depending on
    * sbt-nix-backend reads its files from there whenever the property is
    * set, in a Nix build, in the lock run and interactively.
    */
  val CoursierCacheProperty = "sbt.nix.backend.coursierCache"

  /** Sets [[CoursierCacheProperty]] once the build is loaded. Interactively
    * that means realizing the lock now, so its files exist before any task
    * reads them.
    */
  private def publishCoursierCache(state: State): State = {
    Mode.current match {
      case Mode.Interactive =>
        val extracted = Project.extract(state)
        if (extracted.getOpt(ThisBuild / nixBackend).getOrElse(false)) {
          val (next, dir) = extracted.runTask(ThisBuild / nixCoursierCache, state)
          System.setProperty(CoursierCacheProperty, dir.getAbsolutePath)
          next
        } else {
          System.clearProperty(CoursierCacheProperty)
          state
        }
      case _ =>
        System.setProperty(CoursierCacheProperty, Mode.coursierCacheFromEnv.getAbsolutePath)
        state
    }
  }

  override def buildSettings: Seq[Setting[_]] = Seq(
    nixBuildRoot := buildRootSetting.value,
    nixLockFile := nixBuildRoot.value / "sbt-nix-backend.lock.json",
    nixBackend := {
      val log = sLog.value
      val lockFile = nixLockFile.value
      sys.env.get("SBT_NIX_BACKEND") match {
        case Some("0") | Some("false")                      => false
        case _ if Mode.current != Mode.Interactive          => false
        case _ if !lockFile.isFile                          => false
        case _ =>
          // A lock made from another build definition would leave this
          // level unable to load, and nixLock with it; resolution goes
          // its usual way until the lock is renewed.
          val current = Lock.buildDefinitionHash(nixBuildRoot.value, Nil)
          Lock.readOption(lockFile) match {
            case Some(l) if l.definitionHash == current => true
            case _ =>
              log.warn(
                s"[sbt-nix-backend] ${lockFile.getName} was made from another build definition; " +
                  "resolution does not read the locked downloads until nixLock renews it"
              )
              false
          }
      }
    },
    nixFlakeRepositories := Nil,
    nixFlakeRepositoryPaths := flakeRepositoryPathsSetting.value,
    nixSbtJavaOptions := Seq("-Xss4m", "-Xmx2g"),
    nixLauncherUrl := {
      val v = sbtVersion.value
      s"https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/$v/sbt-launch-$v.jar"
    },
    nixCoursierCache := coursierCacheTask.value,
    nixNixpkgs := sys.props.get("sbt.nix.backend.nixpkgs").filter(_.nonEmpty).getOrElse("flake:nixpkgs"),
    // The sbt wrapper boots sbt with COURSIER_CACHE set to the read-only
    // tree of locked downloads, and names the user's own cache in
    // SBT_NIX_BACKEND_USER_COURSIER_CACHE. Every resolution the lock does
    // not cover (source jars for an IDE, a build whose lock is stale)
    // goes to the user's cache.
    csrCacheDirectory := (sys.env.get("SBT_NIX_BACKEND_USER_COURSIER_CACHE") match {
      case Some(dir) if dir.nonEmpty => new File(dir)
      case Some(_)                   => defaultUserCoursierCache
      case None                      => csrCacheDirectory.value
    }),
    nixLock := lockTask(force = false, updateFlakes = false).value,
    nixRelock := lockTask(force = true, updateFlakes = false).value,
    nixLockUpdate := lockTask(force = true, updateFlakes = true).value
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    nixPackaged := !(publish / skip).value,
    nixLockedProject := {
      if (!nixPackaged.value) None
      else {
        val id = projectID.value
        val cross = CrossVersion(id.crossVersion, scalaVersion.value, scalaBinaryVersion.value)
        val crossed = cross.fold(id.name)(f => f(id.name))
        // An sbt plugin publishes Maven-style as <name>_<scala>_<sbt>.
        val name = if (sbtPlugin.value) s"${crossed}_${scalaBinaryVersion.value}_${sbtBinaryVersion.value}" else crossed
        Some(LockedProject(thisProject.value.id, id.organization, name, id.revision))
      }
    },
    resolvers := {
      val flake = (ThisBuild / nixFlakeRepositoryPaths).value.toVector.sortBy(_._1).map { case (n, dir) =>
        MavenRepository("nix-flake-" + n, dir.toURI.toString)
      }
      flake ++ resolvers.value.filterNot(r => flake.exists(_.name == r.name))
    },
    otherResolvers ++= {
      Mode.current match {
        case Mode.Interactive => Nil
        case _ => Seq(MavenCache(NixOutputResolver, Mode.output / thisProject.value.id / "artifacts"))
      }
    },
    nixPublishArtifacts / publishConfiguration := {
      if (!publishMavenStyle.value)
        sys.error(
          s"sbt-nix-backend: ${thisProject.value.id} sets publishMavenStyle := false; " +
            "its Nix outputs are Maven repositories and need the POM a Maven-style publish writes"
        )
      // The stock publishConfiguration fails when publishTo is unset, so
      // this one is built the same way with the output resolver named.
      Classpaths.publishConfig(
        publishMavenStyle.value,
        Classpaths.deliverPattern(crossTarget.value),
        if (isSnapshot.value) "integration" else "release",
        ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
        (publish / packagedArtifacts).value.toVector,
        (publish / checksums).value.toVector,
        NixOutputResolver,
        ivyLoggingLevel.value,
        overwrite = true
      )
    },
    // The sandbox publishes every packaged project and its internal
    // dependencies, whatever `publish / skip` says.
    nixPublishArtifacts / skip := false,
    nixPublishArtifacts := {
      Classpaths.publishOrSkip(nixPublishArtifacts / publishConfiguration, nixPublishArtifacts / skip).value
      Mode.output / thisProject.value.id / "artifacts"
    },
    nixPublishRepository := publishRepositoryTask.value,
    nixPackagedArtifacts := {
      val out = nixBuildPackage("out").value
      val log = streams.value.log
      val version = projectID.value.revision
      val files = allFiles(out).filterNot(f => f.getName.endsWith(".sha1") || f.getName.endsWith(".md5"))
      artifacts.value.map { a =>
        val suffix = "-" + version + a.classifier.fold("")("-" + _) + "." + a.extension
        val matching = files.filter(_.getName.endsWith(suffix))
        val file = matching match {
          case Seq(f) => f
          case Seq() =>
            sys.error(s"sbt-nix-backend: the Nix build of ${thisProject.value.id} in $out has no file for artifact $a")
          // The output holds one module, and a classified file ends in
          // -<version>-<classifier>.<ext>, so a suffix names one file.
          case many =>
            sys.error(s"sbt-nix-backend: artifact $a matches more than one file in $out: ${many.mkString(", ")}")
        }
        log.debug(s"[sbt-nix-backend] $a -> $file")
        a -> file
      }.toMap
    },
    nixRuntimeRepository := nixBuildPackage("repository").value,
    // Feature 3: interactive resolution reads the locked downloads, offline.
    csrConfiguration := withLockedCache(csrConfiguration).value,
    scalaCompilerBridgeScope / csrConfiguration := withLockedCache(scalaCompilerBridgeScope / csrConfiguration).value,
    update := {
      val result = update.result.value
      val log = streams.value.log
      result match {
        case Value(report) => report
        case Inc(cause) =>
          if ((ThisBuild / nixBackend).value)
            log.error(
              s"[sbt-nix-backend] resolution reads only the downloads ${(ThisBuild / nixLockFile).value.getName} locks. " +
                "If the build's dependencies changed, run nixLock; SBT_NIX_BACKEND=0 resolves without the lock."
            )
          throw cause
      }
    }
  )

  private def withLockedCache(
      key: TaskKey[lmcoursier.CoursierConfiguration]
  ): Def.Initialize[Task[lmcoursier.CoursierConfiguration]] =
    Def.taskDyn {
      val conf = key.value
      Mode.current match {
        case Mode.Sandbox =>
          Def.task(conf.withCache(Some(Mode.coursierCacheFromEnv)).withCachePolicies(Vector(CachePolicy.LocalOnly)))
        case Mode.Lock => Def.task(conf.withCache(Some(Mode.coursierCacheFromEnv)))
        case Mode.Interactive =>
          if ((ThisBuild / nixBackend).value)
            Def.task(conf.withCache(Some((ThisBuild / nixCoursierCache).value)).withCachePolicies(Vector(CachePolicy.LocalOnly)))
          else Def.task(conf)
      }
    }

  /** The concrete build's root. sbt runs every level of a build in one JVM
    * started in the concrete build's root, and level k lives at
    * `<root>/project` repeated k times; a meta-build checks that its own
    * base has that shape before it takes the working directory as the root.
    */
  private def buildRootSetting: Def.Initialize[File] = Def.setting {
    val base = (ThisBuild / baseDirectory).value.getCanonicalFile
    if (!(LocalRootProject / isMetaBuild).value) base
    else {
      val root = new File(sys.props("user.dir")).getCanonicalFile
      val rel = root.toPath.relativize(base.toPath).toString.replace(File.separatorChar, '/')
      if (rel.isEmpty || rel.split('/').exists(_ != "project"))
        sys.error(s"sbt-nix-backend: the meta-build at $base is not a project/ directory under the build root $root")
      root
    }
  }

  /** Coursier's default cache location when COURSIER_CACHE is unset
    * (coursier.paths.CoursierPaths): the XDG cache on Linux, Library/Caches
    * on macOS.
    */
  private def defaultUserCoursierCache: File = {
    val home = new File(sys.props("user.home"))
    if (sys.props("os.name").toLowerCase.contains("mac")) new File(home, "Library/Caches/Coursier/v1")
    else new File(sys.env.get("XDG_CACHE_HOME").filter(_.nonEmpty).map(new File(_)).getOrElse(new File(home, ".cache")), "coursier/v1")
  }

  private def allFiles(dir: File): Vector[File] =
    Option(dir.listFiles()).getOrElse(Array.empty[File]).toVector.flatMap { f =>
      if (f.isDirectory) allFiles(f) else Vector(f)
    }

  /** Builds `output` of this project's package in the Nix sandbox: the
    * lockfile is brought up to date first, and the build runs
    * buildSbtProject from the plugin's copy of the Nix library.
    */
  private def nixBuildPackage(output: String): Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    if (Mode.current != Mode.Interactive)
      sys.error("sbt-nix-backend: nixPackagedArtifacts runs only outside the sandbox")
    val lockFile = (ThisBuild / nixLock).value
    val id = thisProject.value.id
    if (!Lock.read(lockFile).projects.exists(_.id == id))
      sys.error(s"sbt-nix-backend: $lockFile builds no project $id (nixPackaged is false for it)")
    val base = (ThisBuild / nixBuildRoot).value / "target" / "sbt-nix-backend"
    val lib = NixLibrary.write(base / "nix-lib")
    val repository = sys.props.get("sbt.nix.backend.repository").filter(_.nonEmpty).getOrElse("")
    log.info(s"[sbt-nix-backend] building $id ($output) in the Nix sandbox")
    new Nix(line => log.info("[nix] " + line)).buildFile(
      new File(lib, "package.nix"),
      output,
      Seq(
        "nixpkgs" -> (ThisBuild / nixNixpkgs).value,
        "src" -> (ThisBuild / nixBuildRoot).value.getAbsolutePath,
        "project" -> id,
        "pluginRepository" -> repository
      ),
      base / "gcroots" / s"package-$id-$output"
    )
  }

  private val coursierCacheLock = new Object

  private def coursierCacheTask: Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val lockFile = nixLockFile.value
    val base = ((ThisBuild / nixBuildRoot).value / "target") / "sbt-nix-backend"
    val dir = base / "coursier-cache"
    if (!lockFile.isFile) sys.error(s"sbt-nix-backend: no lock at $lockFile; run nixLock")
    coursierCacheLock.synchronized {
      val marker = dir / ".sbt-nix-backend-lock"
      val lockHash = Lock.sriHash(lockFile)
      val upToDate = marker.isFile && new String(Files.readAllBytes(marker.toPath), StandardCharsets.UTF_8) == lockHash
      if (!upToDate) {
        log.info(s"[sbt-nix-backend] realizing ${lockFile.getName} through Nix")
        val lib = NixLibrary.write(base / "nix-lib")
        val manifest = new Nix(line => log.info("[nix] " + line)).realizeLock(lockFile, lib, base / "gcroots" / "coursier-cache")
        Nix.linkCache(dir, manifest)
        Files.write(marker.toPath, lockHash.getBytes(StandardCharsets.UTF_8))
      }
      dir
    }
  }

  /** The flake repositories' store paths. In the sandbox and the lock run
    * they come from the environment. Interactively, each one is locked
    * (reusing the lock file's pin while the declared reference is the
    * same) and built once, with a GC root and a record of the pin, so
    * later loads read the record rather than calling Nix.
    */
  private def flakeRepositoryPathsSetting: Def.Initialize[Map[String, File]] = Def.setting {
    val declared = nixFlakeRepositories.value
    Mode.current match {
      case Mode.Interactive if declared.nonEmpty =>
        val lock = Lock.readOption(nixLockFile.value)
        val base = ((ThisBuild / nixBuildRoot).value / "target") / "sbt-nix-backend" / "flake-repositories"
        lazy val nix = new Nix(_ => ())
        declared.map { r =>
          val locked = lock
            .flatMap(_.flakeRepositories.find(l => l.name == r.name && l.ref == r.ref))
            .map(_.locked)
            .getOrElse(nix.lockFlakeRef(r.flakeRef))
          r.name -> realizeFlakeRepository(nix, base, r.name, locked, r.attribute)
        }.toMap
      case Mode.Interactive => Map.empty
      case _                => Mode.flakeRepositoryPathsFromEnv
    }
  }

  private def realizeFlakeRepository(nix: => Nix, base: File, name: String, locked: String, attribute: String): File = {
    val record = base / (name + ".json")
    val root = base / (name + "-gcroot")
    val key = Json.obj("locked" -> Json.str(locked), "attribute" -> Json.str(attribute))
    val recorded =
      if (record.isFile) {
        val o = Json.parse(new String(Files.readAllBytes(record.toPath), StandardCharsets.UTF_8)).asObject
        val path = new File(o("path").asString)
        if (o("locked") == key("locked") && o("attribute") == key("attribute") && path.isDirectory) Some(path) else None
      } else None
    recorded.getOrElse {
      val installable = s"$locked#sbtRepositories.${nix.currentSystem}.$attribute"
      val path = nix.build(installable, root)
      base.mkdirs()
      Files.write(
        record.toPath,
        Json.Obj(key.fields :+ ("path" -> Json.str(path.getAbsolutePath))).render.getBytes(StandardCharsets.UTF_8)
      )
      path
    }
  }

  private val allProjects = ScopeFilter(inAnyProject)

  private def lockInputs(updateFlakes: Boolean): Def.Initialize[Task[LockGeneration.Inputs]] = Def.task {
    val root = (ThisBuild / nixBuildRoot).value
    val projects = nixLockedProject.all(allProjects).value.flatten.toVector.sortBy(_.id)
    val urls = (nixNetworkDependencies.all(allProjects).value.flatten ++ (ThisBuild / nixNetworkDependencies).value).distinct.sorted.toVector
    val declared = (ThisBuild / nixFlakeRepositories).value.toVector
    val existing = Lock.readOption((ThisBuild / nixLockFile).value)
    val nix = new Nix(_ => ())
    val lockedRepos = declared.map { r =>
      val kept =
        if (updateFlakes) None
        else existing.flatMap(_.flakeRepositories.find(l => l.name == r.name && l.ref == r.ref))
      kept.getOrElse(LockedFlakeRepository(r.name, r.ref, nix.lockFlakeRef(r.flakeRef), r.attribute))
    }
    val repoBase = ((ThisBuild / nixBuildRoot).value / "target") / "sbt-nix-backend" / "flake-repositories"
    val repoPaths = lockedRepos.map(r => r.name -> realizeFlakeRepository(nix, repoBase, r.name, r.locked, r.attribute)).toMap
    val injectFile = injectedPluginFile
    val injected = injectFile.flatMap(_ => pluginCoordinates)
    if (injectFile.isDefined && injected.isEmpty)
      sys.error("sbt-nix-backend: the plugin was injected but its jar names no coordinates")
    val javaOptions = (ThisBuild / nixSbtJavaOptions).value.toVector
    val launcherUrl = (ThisBuild / nixLauncherUrl).value
    val hash = Lock.buildDefinitionHash(
      root,
      Seq("plugin=" + injected.fold("none")(p => s"${p.organization}:${p.name}:${p.version}"), "launcher=" + launcherUrl) ++
        javaOptions.map("javaOption=" + _) ++ urls.map("url=" + _) ++
        lockedRepos.map(r => s"flake=${r.name}=${r.locked}#${r.attribute}") ++
        projects.map(p => s"project=${p.id}=${p.organization}:${p.name}:${p.version}")
    )
    LockGeneration.Inputs(
      buildRoot = root,
      workDir = ((ThisBuild / nixBuildRoot).value / "target") / "sbt-nix-backend" / "lock-run",
      sbtVersion = sbtVersion.value,
      launcherUrl = launcherUrl,
      javaOptions = javaOptions,
      projects = projects,
      networkUrls = urls,
      flakeRepositories = lockedRepos,
      flakeRepositoryPaths = repoPaths,
      injectedPluginFile = injectFile,
      injectedPlugin = injected,
      inputsHash = hash
    )
  }

  private def lockTask(force: Boolean, updateFlakes: Boolean): Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val in = lockInputs(updateFlakes).value
    val lockFile = (ThisBuild / nixLockFile).value
    if (Mode.current != Mode.Interactive) sys.error("sbt-nix-backend: nixLock runs only outside the sandbox")
    val existing = Lock.readOption(lockFile)
    val javaMajor = System.getProperty("java.version").takeWhile(_ != '.')
    val fresh = existing.exists { l =>
      l.inputsHash == in.inputsHash && l.javaVersion.takeWhile(_ != '.') == javaMajor && l.sbtVersion == in.sbtVersion
    }
    if (!force && fresh) {
      log.info(s"[sbt-nix-backend] $lockFile is up to date")
    } else {
      val lock = LockGeneration.run(in, log.info(_), log.warn(_))
      lock.write(lockFile)
      log.info(s"[sbt-nix-backend] wrote $lockFile (${lock.artifacts.size} artifacts)")
    }
    lockFile
  }

  private def publishRepositoryTask: Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val id = thisProject.value.id
    val repository = Mode.output / id / "repository"
    // The coordinates come from the POM sbt published, which already
    // carries every naming convention (cross versions, the sbt plugin
    // suffix) applied.
    val coordinates = RuntimeRepository.publishedCoordinates(Mode.output / id / "artifacts")
    val conf = csrConfiguration.value
    val copied = RuntimeRepository.complete(
      repository,
      coordinates,
      conf,
      conf.cache.getOrElse(Mode.coursierCacheFromEnv),
      log
    )
    val mainClassName = (Compile / mainClass).?.value.flatten
    val meta = Json.obj(
      "organization" -> Json.str(coordinates.organization),
      "name" -> Json.str(coordinates.name),
      "version" -> Json.str(coordinates.version),
      "mainClass" -> mainClassName.fold[Json](Json.Null)(Json.str)
    )
    Files.write((repository / RepositoryMetadata).toPath, (meta.render + "\n").getBytes(StandardCharsets.UTF_8))
    log.info(s"[sbt-nix-backend] $id: repository holds ${copied.size} files besides the build's own artifacts")
    repository
  }

  /** The projects `ref` depends on for compile or runtime, transitively. */
  private def runtimeProjectClosure(structure: sbt.internal.BuildStructure, ref: ProjectRef): Vector[ProjectRef] = {
    def runtimeEdge(config: Option[String]): Boolean = config match {
      case None => true
      case Some(c) =>
        c.split(';').exists { mapping =>
          val from = mapping.split("->").head.split(',').map(_.trim)
          from.exists(f => f == "compile" || f == "runtime" || f == "default" || f == "*")
        }
    }
    val seen = scala.collection.mutable.LinkedHashSet.empty[ProjectRef]
    def visit(r: ProjectRef): Unit =
      if (seen.add(r)) {
        val project = Project.getProject(r, structure).getOrElse(sys.error(s"sbt-nix-backend: no project $r"))
        project.dependencies.filter(d => runtimeEdge(d.configuration)).foreach(d => visit(d.project))
      }
    visit(ref)
    seen.toVector
  }

  /** `nixSandboxBuild [--test] [--test-compile] <project>...`: what a Nix
    * build runs (and the lock run, with --test-compile). For each project
    * it publishes the packaged artifacts to `$$SBT_NIX_OUTPUT/<id>/artifacts`
    * and writes `$$SBT_NIX_OUTPUT/<id>/repository`.
    */
  lazy val sandboxBuildCommand: Command =
    Command.args("nixSandboxBuild", "[--test] [--test-compile] <project>...") { (state0, args) =>
      if (Mode.current == Mode.Interactive)
        sys.error("sbt-nix-backend: nixSandboxBuild runs inside Nix builds and nixLock; set SBT_NIX_MODE")
      val (flags, ids) = args.partition(_.startsWith("--"))
      flags.filterNot(Set("--test", "--test-compile")).foreach(f => sys.error(s"nixSandboxBuild: unknown flag $f"))
      val extracted = Project.extract(state0)
      val structure = extracted.structure
      val known = structure.allProjectRefs.map(_.project).toSet
      ids.filterNot(known).foreach(id => sys.error(s"nixSandboxBuild: no project $id"))
      var state = state0
      def run[T](key: TaskKey[T]): T = {
        val (s, v) = extracted.runTask(key, state)
        state = s
        v
      }
      ids.foreach { id =>
        val ref = ProjectRef(structure.root, id)
        if (flags.contains("--test")) run(ref / Test / test)
        if (flags.contains("--test-compile")) run(ref / Test / compile)
        val closure = runtimeProjectClosure(structure, ref)
        closure.foreach(r => run(r / nixPublishArtifacts))
        val repository = Mode.output / id / "repository"
        closure.foreach(r => IO.copyDirectory(Mode.output / r.project / "artifacts", repository, overwrite = true))
        run(ref / nixPublishRepository)
      }
      state
    }
}
