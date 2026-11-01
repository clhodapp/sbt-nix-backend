# sbt-nix-backend

An sbt plugin and Nix library that make Nix the dependency and build
backend of an sbt build. It takes its model from
[cargo (libnix)](https://github.com/nixcloud/cargo): sbt still plans the
build, and Nix fetches what the build needs and builds it in the sandbox.

- **A lockfile.** `nixLock` writes `sbt-nix-backend.lock.json` at the build
  root, with every file the build downloads (by URL and SHA-256), the sbt
  launcher, the JVM options, and the projects to build.
- **Sandboxed builds.** `buildSbtProject`, which the flake's overlay adds
  to a package set, turns a build and its lockfile into derivations. `dependencies` is the
  coursier cache of every locked file. `packages.<project>` holds the
  project's published artifacts, in the layout it publishes in. Its
  `repository` output holds them with their runtime dependencies, as a
  repository that resolves offline (see [Repository layouts](#repository-layouts)).
- **Interactive sbt on the locked downloads.** With a lockfile present,
  `compile`, `run`, `test` and `packagedArtifacts` stay incremental, but
  resolution reads only the locked files, through Nix, offline. That holds
  at every level of the build, including the plugins in
  `project/plugins.sbt` and sbt itself.
- **Builds that depend on builds.** `nixFlakeRepositories` names another
  flake's repository output; resolution reads it first, and the lockfile
  pins its revision.
- **`nixPackagedArtifacts`**: `packagedArtifacts`, built by Nix in its
  sandbox.

## How the lock is made

`nixLock` copies the build to `target/sbt-nix-backend/lock-run`, and forks
sbt there with an empty coursier cache, home and boot directory. The fork
runs the same command a Nix build runs (`nixSandboxBuild`), with the
network. Every file it downloads goes into the lockfile. A sandboxed build
reads only through coursier, from that cache, so the lockfile holds what
it needs without a list of what sbt fetches: the launcher's download of
sbt, the meta-builds' plugins, compiler bridges, Scaladoc tooling and the
project's dependencies alike.

`nixLock` relocks only when the build definition changed: the `*.sbt`
files, everything under `project/`, the declared network URLs, the flake
repositories or the projects. `nixRelock` relocks regardless, and
`nixLockUpdate` also locks the flake repositories afresh.

Files a plugin downloads outside coursier are invisible to that run. A
plugin declares them in `nixNetworkDependencies`, a task of URLs. `nixLock`
fetches and locks them, and every mode puts them in a coursier cache at the
path coursier would use, named by the system property
`sbt.nix.backend.coursierCache`. A plugin can do this without depending on
sbt-nix-backend: it defines a task key of the same name and type
(`taskKey[Seq[String]]("...")`), which sbt treats as the same key, and reads
the property (`tests/fixtures/multi/project/LicensePlugin.scala` is an
example). A plugin that downloads without declaring its URLs fails in the
sandbox; the lock run lists what it wrote under its home directory, to
help find such files.

## Use

Start sbt through the flake's wrapper, which loads the plugin as a global
plugin. sbt loads global plugins below every level of a build, so the
build needs no entry in `project/plugins.sbt`:

```zsh
nix run github:clhodapp/sbt-nix-backend -- nixLock
```

Commit `sbt-nix-backend.lock.json`. The flake's `overlays.default` adds
`buildSbtProject` to a package set, and a package written for
`callPackage` takes it by name, like any other builder:

```nix
# package.nix
{ buildSbtProject }:
(buildSbtProject { src = ./.; }).packages.core
```

```nix
{
  inputs.sbt-nix-backend.url = "github:clhodapp/sbt-nix-backend";
  inputs.nixpkgs.follows = "sbt-nix-backend/nixpkgs";

  outputs = { nixpkgs, sbt-nix-backend, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        overlays = [
          sbt-nix-backend.overlays.default
          (final: prev: { core = final.callPackage ./package.nix { }; })
        ];
      };
    in
    {
      packages.${system}.core = pkgs.core;
      # Other builds reference these (see below).
      sbtRepositories.${system} = (pkgs.buildSbtProject { src = ./.; }).sbtRepositories;
    };
}
```

`nix build '.#core'` builds project `core`; `nix build '.#core.repository'`
builds its repository. The builder takes what it needs from the package
set (`stdenvNoCC`, `javaPackages`, the `sbt-nix-backend` scope for its
plugin repository), so `buildSbtProject.override` or an overlay on the
scope replaces any of it. `nix/lib/build-sbt-project.nix` documents the
build's arguments: `jdk` (the default is the headless JDK of the major
version the lock was made with), `fetchurl` (for credentials or mirrors),
`doCheck` (runs `test`), `srcFilter`, `pluginRepository` and
`flakeRepositories`.

### Without the wrapper

A build whose `.sbt` files use the plugin's keys needs the plugin in its
own definition, or plain sbt cannot load it. Install it once, in the
meta-most build, `project/project/plugins.sbt`:

```scala
resolvers += MavenRepository(
  "sbt-nix-backend",
  new File(sys.env("SBT_NIX_BACKEND_REPOSITORY")).toURI.toString
)
addSbtPlugin("io.github.clhodapp" % "sbt-nix-backend" % "0.1.0")
```

In a meta-build, the plugin adds itself to the build the meta-build
defines, so `project/` and the build both have it: both can use its keys,
and it governs the resolution of both, `project/plugins.sbt` included.
`nix develop github:clhodapp/sbt-nix-backend` gives plain nixpkgs sbt with
`SBT_NIX_BACKEND_REPOSITORY` naming the plugin repository. The lockfile
records that the build takes the plugin from that variable, and
`buildSbtProject` sets it in the sandbox. The wrapper sets it too, to the
repository it injects the plugin from, so such a build also runs through
the wrapper, with one plugin build at every level. Without the wrapper, sbt itself
and the meta-most build (which resolves the plugin) download the usual
way; every level above resolves from the lockfile.
`tests/fixtures/metameta` is such a build.

### Depending on another build

```scala
ThisBuild / nixFlakeRepositories += NixFlakeRepository("lib", "github:owner/lib/<rev>#core")
libraryDependencies += nixFlakeModule("lib").value
```

The fragment names the attribute under the producer's
`sbtRepositories.<system>`. `nixFlakeModule` reads the coordinates the
producer published, so the build does not repeat them. The lockfile pins
the reference with its `narHash`, and the Nix build fetches it with
`builtins.getFlake`; a flake that has the producer as an input can pass
its output instead (`flakeRepositories.lib = inputs.lib.sbtRepositories.${system}.core`).

### Interactive mode

With a lockfile, the wrapper boots sbt from the locked downloads, and the
plugin realizes the lockfile through Nix (one GC root, under
`target/sbt-nix-backend`) into a coursier cache of links into the store.
Every level of the build resolves from it, offline. A dependency that is
not in the lockfile fails resolution with a hint to run `nixLock`. When the
build definition changed since the lockfile was made, sbt warns and
resolves the usual way until `nixLock` renews it. `SBT_NIX_BACKEND=0` turns
interactive mode off. Source jars for an IDE are not build inputs, so
`updateClassifiers` resolves the usual way, into the user's coursier
cache.

### Repository layouts

A project publishes into its `out` output in the layout it publishes in
anywhere. A Maven-style project uses the Maven layout. A project with
`publishMavenStyle := false` uses sbt's standard Ivy layout
(`Resolver.ivyStylePatterns`, the layout of `publishLocal`). An sbt plugin
published Ivy-style keeps its plain name and carries its Scala and sbt
versions in its path.

A `repository` output keeps every module in the layout it was published
in. Modules from Maven repositories keep their Maven paths. Modules from
Ivy repositories go to their paths in the standard Ivy layout, whatever
patterns their repository used. The two layouts share the directory
without colliding. A Maven path starts with the organization split at its
dots, and an Ivy path with the organization whole. A consumer reads the
directory with both resolvers:

```scala
externalResolvers := Seq(
  MavenRepository("repository", "file:///nix/store/...-repository"),
  Resolver.file("repository-ivy", file("/nix/store/...-repository"))(Resolver.ivyStylePatterns)
)
```

`nixFlakeRepositories` adds both resolvers for each flake repository, and
`nixFlakeModule` returns a module with the extra attributes it was
published with.

### Resolution with Ivy

On sbt 1, a project or meta-build may resolve with Apache Ivy
(`useCoursier := false`). The plugin rewrites every remote resolver of
such a project onto a mirror of the coursier cache, where the file of
`https://host/path` sits at `https/host/path`. The rewrite covers the
project's Ivy configuration and the one that resolves the compiler bridge
of Scala 2.12 and older. In a Nix build and in interactive mode, the
mirror is the coursier cache built from the lockfile, read as a `file:`
URL, so Ivy reads only the locked files. In the lock run, the mirror is an
HTTP server on localhost that fetches each file Ivy asks for from its real
URL into the fork's coursier cache, so the lockfile holds it under that URL
with everything else.

Such a project gets an Ivy home of its own under
`target/sbt-nix-backend/ivy-homes`, named by a digest of the lockfile and
of the local repositories it reads. Ivy keeps each module it resolved
from a local repository in its cache by version, and reads its files where
it first found them. A rebuilt plugin or flake repository keeps its
version at a new store path, and gets a fresh cache under the new digest.
sbt's `local` repository stays in the user's Ivy home, so `publishLocal`
and its readers are unaffected.

A project that resolves through an Ivy settings file
(`externalIvySettings`) gives Ivy a copy of the file under its Ivy home.
In the copy, every remote URL is on the mirror. That covers attribute
values, the values of included properties files, and every included
settings file, which is copied the same way. A remote file the settings
include is read through the mirror too, so the lock run locks it. Ivy
interprets the copy itself, so chains, module rules and conflict managers
keep their meaning. Coursier, which completes the repository outputs,
reads the resolvers of the copy, converted to sbt resolvers at their real
URLs.

`tests/fixtures/ivy` resolves with Ivy at both levels. It publishes
Ivy-style, including an sbt plugin. It depends on a module that only an
Ivy-layout repository has, and one of its projects resolves through an Ivy
settings file. `tests/fixtures/sbt2` publishes Ivy-style on
sbt 2, which resolves with coursier only.

## Keys

| Key | What it does |
|---|---|
| `nixLock` | Writes the lockfile when the build definition changed. |
| `nixRelock` | Writes the lockfile. |
| `nixLockUpdate` | Writes the lockfile and locks every flake repository afresh. |
| `nixPackagedArtifacts` | `packagedArtifacts`, built by Nix in its sandbox. |
| `nixRuntimeRepository` | The project's repository output, built by Nix. |
| `nixNetworkDependencies` | URLs a plugin downloads outside coursier. |
| `nixFlakeRepositories` | Flake references to other builds' repositories. |
| `nixFlakeModule(name)` | The module a flake repository publishes. |
| `nixNetworkFile(url)` | Where a declared URL is in the coursier cache. |
| `nixPackaged` | Whether the lockfile builds the project (default: `!(publish / skip)`). |
| `nixSbtJavaOptions` | JVM options of the sbt that `nixLock` and Nix builds run. |
| `nixNixpkgs` | The nixpkgs `nixPackagedArtifacts` builds with. |
| `nixBackend` | Whether interactive resolution reads the lockfile. |

## Scope and limits

- sbt 1.11 and later in the 1.x line, and sbt 2.0. The plugin is
  cross-built (`sbt-nix-backend_2.12_1.0` and `sbt-nix-backend_sbt2_3`),
  and the wrapper injects the variant the build's sbt loads. For an sbt 2
  build the runner starts a client by default; the wrapper passes its
  settings in `SBT_OPTS`, which the server it starts receives too.
- A cross-built project (`crossScalaVersions`) is built for each of its
  Scala versions, as `+` does, and its outputs hold every variant.
- A lockfile records URLs, not credentials; a dependency from an
  authenticated repository needs a `fetchurl` that supplies them.
- A `-SNAPSHOT` dependency changes under its URL, so its locked hash stops
  matching when it does; `nixLock` warns about them.
- Building in Nix needs the build's sources to be visible to Nix: in a
  flake, tracked by git.

## Development

`docs/development/` describes the project, its tests and its commands.
