# sbt-nix-backend

An sbt plugin and Nix library that make Nix the dependency and build
backend of an sbt build. It takes its model from
[cargo (libnix)](https://github.com/nixcloud/cargo): sbt still plans the
build, and Nix fetches what the build needs and builds it in the sandbox.

- **A lockfile.** `nixLock` writes `sbt-nix-backend.lock.json` at the build
  root, with every file the build downloads (by URL and SHA-256), the sbt
  launcher, the JVM options, and the projects to build.
- **Sandboxed builds.** The flake's `lib.sbt-nix-backend.buildSbtProject`
  turns a build and its lockfile into derivations. `dependencies` is the
  coursier cache of every locked file. `packages.<project>` holds the
  project's published artifacts in Maven layout. Its `repository` output
  holds them with their runtime dependencies, as a Maven repository that
  resolves offline.
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

Commit `sbt-nix-backend.lock.json`, and build from a flake:

```nix
{
  inputs.sbt-nix-backend.url = "github:clhodapp/sbt-nix-backend";
  inputs.nixpkgs.follows = "sbt-nix-backend/nixpkgs";

  outputs = { nixpkgs, sbt-nix-backend, ... }:
    let
      system = "x86_64-linux";
      build = sbt-nix-backend.lib.sbt-nix-backend.buildSbtProject {
        pkgs = nixpkgs.legacyPackages.${system};
        src = ./.;
      };
    in
    {
      packages.${system} = build.packages;
      # Other builds reference these (see below).
      sbtRepositories.${system} = build.sbtRepositories;
    };
}
```

`nix build '.#core'` builds project `core`; `nix build '.#core.repository'`
builds its repository. `nix/lib/default.nix` documents every argument:
`jdk` (the default is the headless JDK of the major version the lock was
made with), `fetchurl` (for credentials or mirrors), `doCheck` (runs
`test`), `srcFilter`, `pluginRepository` and `flakeRepositories`.

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
- Nix outputs are Maven repositories, so a project with
  `publishMavenStyle := false` is refused, and a runtime dependency that
  comes from an Ivy-layout repository cannot go into a repository output.
- A lockfile records URLs, not credentials; a dependency from an
  authenticated repository needs a `fetchurl` that supplies them.
- A `-SNAPSHOT` dependency changes under its URL, so its locked hash stops
  matching when it does; `nixLock` warns about them.
- Building in Nix needs the build's sources to be visible to Nix: in a
  flake, tracked by git.

## Development

`docs/development/` describes the project, its tests and its commands.
