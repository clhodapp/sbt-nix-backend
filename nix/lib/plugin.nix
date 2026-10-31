# The sbt-nix-backend plugin, built by itself from the lockfile at the root
# of this repository: its meta-build compiles the plugin from source, so no
# published copy is needed. Its unit tests run in the build. The package's
# `repository` output holds the plugin with its runtime dependencies as a
# Maven repository, which builds of injected locks resolve the plugin from.
{ pkgs }:
let
  root = ../..;
  # Only what the plugin's own build reads: the test fixtures and the flake
  # wiring do not change it.
  parts = [
    "build.sbt"
    "project"
    "src"
    "nix"
    "sbt-nix-backend.lock.json"
  ];
in
(import ./. {
  inherit pkgs;
  src = root;
  doCheck = true;
  srcFilter =
    path: type:
    let
      rel = pkgs.lib.removePrefix (toString root + "/") (toString path);
      top = builtins.head (pkgs.lib.splitString "/" rel);
      name = baseNameOf path;
    in
    builtins.elem top parts && !(type == "directory" && name == "target");
}).packages.root
