# The sbt-purifier plugin for the package set `pkgs`, built by itself from
# the lockfile at the root of this repository: its meta-build compiles the
# plugin from source, so no published copy is needed. Its unit tests run in
# the build. The package's `repository` output holds the plugin with its
# runtime dependencies as a Maven repository, which builds of injected
# locks resolve the plugin from. The plugin's own lock names no plugin, so
# its build never asks for a plugin repository.
{ lib }:
pkgs:
let
  root = ../..;
  # Only what the plugin's own build reads: the test fixtures and the flake
  # wiring do not change it.
  parts = [
    "build.sbt"
    "version.txt"
    "project"
    "src"
    "nix"
    "sbt-purifier.lock.json"
  ];
in
(lib.sbt-purifier.buildSbtProject {
  inherit pkgs;
  src = root;
  doCheck = true;
  srcFilter =
    path: type:
    let
      rel = lib.removePrefix (toString root + "/") (toString path);
      top = builtins.head (lib.splitString "/" rel);
      name = baseNameOf path;
    in
    builtins.elem top parts && !(type == "directory" && name == "target");
}).packages.root
