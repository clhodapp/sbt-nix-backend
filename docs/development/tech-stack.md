# Tech stack

Extends the workspace `docs/development/tech-defaults.md`.

- **Scala 2.12, sbt 1.11.7** for the plugin. The sources keep to the subset
  of Scala that also compiles on Scala 3, for a later sbt 2 build, and
  avoid sbt APIs that differ between the two (JSON and process handling
  are the plugin's own: `Json.scala`, `Proc.scala`).
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
