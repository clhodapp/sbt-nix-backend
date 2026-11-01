# sbt-purifier

An sbt plugin and Nix library that make Nix the dependency and build
backend of an sbt build. sbt still plans the build, and Nix fetches what
the build needs and builds it in the sandbox. It meets the purifier
contract of the [nix-purifiers](https://github.com/nix-purifiers), whose
shared library it builds on.

- **A lockfile.** `sbt-purifier lock` writes `sbt-purifier.lock.json` at
  the build root, with every file the build downloads (by URL, mirrors and
  SHA-256), the sbt launcher, the JVM options, and the projects to build.
- **Sandboxed builds.** `lib.sbt-purifier.buildSbtProject` turns a build
  and its lockfile into derivations, one package per project.
- **Interactive sbt on the locked downloads.** With a lockfile present,
  `compile`, `run` and `test` stay incremental, but resolution reads only
  the locked files, through Nix, offline. That holds at every level of the
  build, including the plugins in `project/plugins.sbt` and sbt itself.
- **Builds that depend on builds.** `nixFlakeRepositories` names another
  flake's repository output; resolution reads it first, and the lockfile
  pins its revision.
- **Publishing what Nix built.** `sbt-purifier publish` runs sbt's
  `publish` with the artifacts the Nix sandbox built in place of locally
  packaged ones.

## Quickstart

From the root of an sbt build, lock it with the flake's commands, which
run sbt with the plugin loaded as a global plugin, so the build needs no
entry in `project/plugins.sbt`:

```zsh
nix run github:nix-purifiers/sbt-purifier -- lock
```

Commit `sbt-purifier.lock.json`. Then build a project with Nix, here
`core`, from the same directory, with no Nix file in the build:

```zsh
nix build --impure --expr '
  let
    purifier = builtins.getFlake "github:nix-purifiers/sbt-purifier";
    pkgs = purifier.inputs.nixpkgs.legacyPackages.${builtins.currentSystem};
  in
  (purifier.lib.sbt-purifier.buildSbtProject { inherit pkgs; src = ./.; }).packages.core'
```

A flake builds it the same way:

```nix
{
  inputs.sbt-purifier.url = "github:nix-purifiers/sbt-purifier";
  inputs.nixpkgs.follows = "sbt-purifier/nixpkgs";

  outputs = { nixpkgs, sbt-purifier, ... }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
      build = sbt-purifier.lib.sbt-purifier.buildSbtProject { inherit pkgs; src = ./.; };
    in
    {
      packages.${system}.core = build.packages.core;
      checks.${system}.tests = build.tests;
      # Other builds reference these (see "Sources supported and refused").
      sbtRepositories.${system} = build.sbtRepositories;
    };
}
```

### The builder

`lib.sbt-purifier.buildSbtProject { pkgs, src, lockFile ? src + "/sbt-purifier.lock.json", fetchers ? { }, ... }`
returns:

- `packages.<project>`: the project's published artifacts, in the layout
  it publishes in. Its `repository` output holds them with their runtime
  dependencies, as a repository that resolves offline.
- `tests`: every package built with its tests run.
- `release`: the published artifacts of every package, as one repository.
- `fetches`: the lock's download entries, in the shape of the purifier
  library.
- `sbtRepositories`: each package's `repository` output by project.

`fetchers` goes to the purifier library's `tree`, so a `fetchurl` with
credentials or a company mirror replaces the default one. The other
arguments are `jdk` (the default is nixpkgs' headless JDK of the Java
major the lock records), `doCheck` (runs `test` in every package build),
`srcFilter`, `pluginRepository` and `flakeRepositories`;
`nix/lib/build-sbt-project.nix` documents them.
`lib.sbt-purifier.readLock { src, lockFile ? ... }` reads the lockfile
into the purifier library's lock in memory, which other tools read.

A build kept external keeps its lockfile outside the sbt project: the
calling flake passes it as `lockFile`, and the commands take
`--lockfile <path>` or `SBT_PURIFIER_LOCKFILE`. A lockfile outside `src`
takes the place of any lockfile in it.

### What a package holds

The lockfile names every artifact of every package, so Nix knows them
before it builds them. `packages.<project>.artifacts.<variant>.<key>` is
an artifact's file; the key is `<name>[-<classifier>].<extension>`, so the
sources jar of `core` built for Scala 3 is
`artifacts.scala3."core-sources.jar"`. `packages.<project>.variants` has
each variant's name, Scala version, artifacts and `descriptorFile`, its
POM or `ivy.xml`. A project has one variant for each Scala version it is
built for, named for the cross version its module is published with:

| Published as | Variant |
| --- | --- |
| `core_3` | `scala3` |
| `core_2.13` | `scala2_13` |
| `core_2.13.16` (`CrossVersion.full`) | `scala2_13_16` |
| `core_sjs1_3` (Scala.js) | `sjs1_scala3` |
| `core` (`crossPaths := false`) | `default` |
| an sbt 1 plugin, Maven or Ivy style | `scala2_12_sbt1_0` |
| an sbt 2 plugin (`_sbt2_3`) | `scala3_sbt2` |

The lock fails when two Scala versions of a project would have the same
variant, such as 2.13.15 and 2.13.16 with a binary cross version. The
package links each artifact at `nix-support/artifacts/<variant>/<key>`,
and the module descriptor at `nix-support/artifacts/<variant>/descriptor`.
Neither these names nor the lockfile hold the version of the project.

## Everyday loop

The everyday loop goes through sbt itself, started through the flake's
wrapper, which loads the plugin and boots sbt from the locked downloads:

```zsh
nix shell github:nix-purifiers/sbt-purifier#sbt
sbt compile test run
```

The wrapper runs sbt on nixpkgs' JDK of the Java major the lockfile
records, so every developer and CI runner relocks on the same JDK.

To add or upgrade a dependency, edit the build and lock again. sbt warns
that the lockfile was made from another build definition, and resolves
the usual way until the lockfile is renewed:

```zsh
sbt-purifier lock     # relocks when the build definition changed
sbt-purifier update   # resolves every dependency afresh, dynamic versions included
```

`lock` keeps a lockfile that is up to date as it is, and a relock of an
unchanged build reproduces it byte for byte. sbt resolves the whole build
at once, so `update` takes no package names. `sbt-purifier publish` runs
`publish` with the artifacts Nix built; `publishTo`, credentials and the
publishing style work as they do for `publish`, and `+nixPublish` in sbt
publishes every Scala version.

The lock run downloads every dependency afresh. When Maven Central limits
the rate of a large run, `SBT_NIX_BACKEND_MAVEN_CENTRAL_MIRROR` names a
mirror that serves the same files, and the lock run downloads the files
of Maven Central from it; the lockfile still records their Maven Central
URLs, with Google's mirror of Maven Central among their mirrors:

```zsh
SBT_NIX_BACKEND_MAVEN_CENTRAL_MIRROR=https://maven-central.storage-download.googleapis.com/maven2/ \
  sbt-purifier lock
```

### From the Maven repository

Each release is in a Maven repository on GitHub Pages, which sbt reads
without credentials. Install it in the meta-most build, with the version
of the newest [release](https://github.com/nix-purifiers/sbt-purifier/releases):

```scala
resolvers += "sbt-nix-backend" at "https://nix-purifiers.github.io/sbt-purifier/maven"
addSbtPlugin("io.github.clhodapp" % "sbt-nix-backend" % "<version>")
```

sbt downloads the plugin like any other plugin, so the lockfile records it
with everything else, and a Nix build of the lockfile needs neither the
wrapper nor the flake's plugin repository.

### Without the wrapper

A build whose `.sbt` files use the plugin's keys needs the plugin in its
own definition. Install it once, in `project/project/plugins.sbt`:

```scala
resolvers += MavenRepository(
  "sbt-nix-backend",
  new File(sys.env("SBT_NIX_BACKEND_REPOSITORY")).toURI.toString
)
addSbtPlugin("io.github.clhodapp" % "sbt-nix-backend" % "0.1.0")
```

`nix develop github:nix-purifiers/sbt-purifier` gives plain nixpkgs sbt
with `SBT_NIX_BACKEND_REPOSITORY` naming the plugin repository. In a
meta-build, the plugin adds itself to the build the meta-build defines,
so `project/` and the build both have it. `tests/fixtures/metameta` is
such a build.

### Keys

| Key | What it does |
|---|---|
| `nixLock` | Writes the lockfile when the build definition changed (`sbt-purifier lock`). |
| `nixRelock` | Writes the lockfile (`sbt-purifier update`). |
| `nixLockUpdate` | Writes the lockfile and locks every flake repository afresh. |
| `nixPublish` | A command: `publish`, with the artifacts Nix built (`sbt-purifier publish`). |
| `nixPackagedArtifacts` | `publish / packagedArtifacts` for the current Scala version, built by Nix. |
| `nixRuntimeRepository` | The project's repository output, built by Nix. |
| `nixNetworkDependencies` | URLs a plugin downloads outside coursier. |
| `nixFlakeRepositories` | Flake references to other builds' repositories. |
| `nixFlakeModule(name)` | The module a flake repository publishes. |
| `nixNetworkFile(url)` | Where a declared URL is in the coursier cache. |
| `nixPackaged` | Whether the lockfile builds the project (default: `!(publish / skip)`). |
| `nixSbtJavaOptions` | JVM options of the sbt that the lock run and Nix builds run. |
| `nixNixpkgs` | The nixpkgs `nixPackagedArtifacts` builds with. |
| `nixBackend` | Whether interactive resolution reads the lockfile. |

## Offline work

```zsh
sbt-purifier prefetch
```

realizes every download the lockfile lists, and the JDK of its Java
major, in the Nix store, with garbage-collector roots beside the lockfile
(`sbt-purifier.lock.json.gcroot*`). The downloads include sbt itself, the
plugins of every level of the build, compiler bridges, and the sources
and javadoc jars an editor asks for. After a prefetch, sbt and the
everyday loop work with the network cut off.

In interactive mode, the wrapper boots sbt from the locked downloads, and
the plugin realizes the lockfile through Nix (one garbage-collector root,
under `target/sbt-nix-backend`) into a coursier cache of links into the
store. Every level of the build resolves from it, offline.
`SBT_NIX_BACKEND=0` turns interactive mode off.

## Editor

An editor imports the build through sbt's build server, and asks it for
the sources of every dependency (`buildTarget/dependencySources`, which
runs `updateClassifiers`). The lock run resolves what a
`dependencySources` request needs: it runs `updateClassifiers` for every
project at its default Scala version, unpublished projects included, and
`updateSbtClassifiers` for the sources of sbt and of the build's plugins.
`tests/interactive.sh` checks that `updateClassifiers` resolves from the
lock with the network cut off. An import by Metals itself is not yet part
of the tests.

## Errors and fixes

Every problem with a lockfile stops the command, the sbt load or the Nix
evaluation with a message that names the command that fixes it.

| Problem | Where it shows | Fix |
| --- | --- | --- |
| No lockfile | `prefetch`, `publish`, Nix evaluation | `sbt-purifier lock` |
| The build definition changed since the lock (stale) | sbt warns and resolves the usual way; `prefetch`, `publish` and Nix evaluation stop | `sbt-purifier lock` |
| The lockfile was edited or damaged (its content hash does not match) | every command, sbt, Nix evaluation | `sbt-purifier lock` |
| The lockfile is not one sbt-purifier wrote, or has another format version | every command, sbt, Nix evaluation | `sbt-purifier lock` |
| A dependency not in the lockfile | resolution in interactive mode and in the sandbox | `sbt-purifier lock` |
| A plugin version that the plugin repository does not hold | the Nix build | the sbt-purifier revision the lock was made with, or `sbt-purifier lock` |

The lockfile's third line holds the SHA-256 of the rest of the file, and
the library checks it with string functions before it parses the JSON, so
a malformed or edited lockfile is a problem with a message, not a failed
parse. The definition hash covers the `*.sbt` files at the root and
everything under `project/`, so a version set there makes the lockfile
stale like any other edit, while a version the build reads from another
file keeps it up to date.

## Sources supported and refused

### How the lock is made

The lock run copies the build to `target/sbt-nix-backend/lock-run`, and
forks sbt there with an empty coursier cache, home and boot directory. The
fork runs the editor downloads and then the command a Nix build runs
(`nixSandboxBuild`), with the network. Every file it downloads goes into
the lockfile. A sandboxed build reads only through coursier, from that
cache, so the lockfile holds what it needs without a list of what sbt
fetches: the launcher's download of sbt, the meta-builds' plugins,
compiler bridges, Scaladoc tooling and the projects' dependencies alike.

### Supported

- **Maven repositories**, resolved with coursier, at every level of the
  build.
- **Ivy repositories and Ivy settings files**, on sbt 1 with
  `useCoursier := false`. The plugin rewrites every remote resolver of such
  a project onto a mirror of the coursier cache, where the file of
  `https://host/path` sits at `https/host/path`, including the resolvers
  of an Ivy settings file and every file it includes. Such a project gets
  an Ivy home of its own under `target/sbt-nix-backend/ivy-homes`.
- **Flake repositories**: another build's repository output, named in
  `nixFlakeRepositories` with a flake reference whose fragment names the
  attribute under the producer's `sbtRepositories.<system>`. The lockfile
  pins the reference with its `narHash`.

  ```scala
  ThisBuild / nixFlakeRepositories += NixFlakeRepository("lib", "github:owner/lib/<rev>#core")
  libraryDependencies += nixFlakeModule("lib").value
  ```

- **Files a plugin downloads outside coursier**, when the plugin declares
  them in `nixNetworkDependencies` and reads them where the system
  property `sbt.nix.backend.coursierCache` says
  (`tests/fixtures/multi/project/LicensePlugin.scala` is an example).
- **sbt 1.11 and later in the 1.x line, and sbt 2.0.** The plugin is
  cross-built (`sbt-nix-backend_2.12_1.0` and `sbt-nix-backend_sbt2_3`),
  and the wrapper injects the variant the build's sbt loads.
- **Cross builds and projectMatrix.** A project in `crossScalaVersions` is
  built for each of its Scala versions; a `projectMatrix` makes a package
  per Scala version.

A project publishes into its package in the layout it publishes in
anywhere: Maven layout, or sbt's standard Ivy layout for
`publishMavenStyle := false`. A `repository` output keeps every module in
the layout it was published in, and a consumer reads it with both
resolvers:

```scala
externalResolvers := Seq(
  MavenRepository("repository", "file:///nix/store/...-repository"),
  Resolver.file("repository-ivy", file("/nix/store/...-repository"))(Resolver.ivyStylePatterns)
)
```

### Refused, or needing help

- A plugin that downloads outside coursier without declaring its URLs
  fails in the sandbox; the lock run lists what it wrote under its home
  directory, to help find such files.
- A lockfile records URLs, not credentials; a dependency from an
  authenticated repository needs a `fetchers.fetchurl` that supplies them.
- A `-SNAPSHOT` dependency changes under its URL, so its locked hash stops
  matching when it does; the lock run warns about them.
- A `file:` URL is read in place by coursier and never locked.
- Building in Nix needs the build's sources to be visible to Nix: in a
  flake, tracked by git.

## Examples

[pureconfig](https://github.com/pureconfig/pureconfig) at `7c2efae` locks
with `sbt-purifier lock` in about four minutes: sbt 1.13.0, 36 projects
for Scala 2.12, 2.13 and 3, with sbt-boilerplate, scalafix, scalafmt,
mdoc, microsites and scoverage, and 6,798 downloads in its lockfile. Its
Nix build and a CI job that locks and builds it are in progress.

## Credits

The model of the plugin, where the build tool plans and Nix fetches and
builds, follows [cargo (libnix)](https://github.com/nixcloud/cargo). The
download entries, their fetches and trees, and the lock in memory are the
[purifier library](https://github.com/nix-purifiers/purifier), which the
nix-purifiers share. Resolution is [coursier](https://get-coursier.io)'s.

## Development

`docs/development/` describes the project, its tests and its commands.
