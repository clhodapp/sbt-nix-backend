# Tech stack

Extends the workspace `docs/development/tech-defaults.md`.

- **The plugin is cross-built**: Scala 2.12 against sbt 1.11.7, and Scala
  3.8.4 against sbt 2.0.12 (`crossScalaVersions` with
  `pluginCrossBuild / sbtVersion` in `build.sbt`). `src/main/scala` keeps to
  the Scala both compile and to sbt APIs both versions share; JSON and
  process handling are the plugin's own (`Json.scala`, `Proc.scala`). The
  sbt APIs that differ go through `Compat`, one per version in
  `src/main/scala-2.12` and `src/main/scala-3`: virtual files in
  `packagedArtifacts`, the `Result` cases, `test` as an input key, and
  `Def.uncached` for every task sbt 2 must not cache.
- **lm-coursier's public API** (`lmcoursier.CoursierConfiguration`,
  `CoursierDependencyResolution`, `lmcoursier.definitions.CacheLogger`) for
  resolution and recording; no shaded coursier internals.
- **The plugin builds itself.** `project/build.sbt` compiles the plugin
  sources into the meta-build, so the repository's own build runs with the
  plugin and is locked by it (`sbt-nix-backend.lock.json`).
- **munit** for the Scala unit tests.
- **caisson** for the flake, as in the other workspace projects; nixpkgs in
  the package set builds the plugin and runs the checks. The Nix library
  itself needs only `pkgs` from its caller, and its interactive part
  (`realize.nix`, `lock.nix`, `fetch.nix`) needs no nixpkgs at all.
