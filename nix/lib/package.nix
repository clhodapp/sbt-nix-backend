# What the plugin's nixPackagedArtifacts and nixRuntimeRepository build:
# one project of a build, by buildSbtProject (build-sbt-project.nix, called
# with the nixpkgs package set), from arguments that sbt passes on the
# command line as strings.
#
#   nixpkgs           a nixpkgs source directory, or a flake reference
#   src               the build root
#   project           the sbt project id
#   lockFile          the lockfile, or "" for the one in the build root
#   pluginRepository  the repository to inject the plugin from, or ""
#
# `out` and `repository` are the two outputs of the project's package.
{
  nixpkgs,
  src,
  project,
  # The lockfile, or "" for sbt-purifier.lock.json in src.
  lockFile ? "",
  pluginRepository ? "",
}:
let
  pkgs =
    if builtins.substring 0 1 nixpkgs == "/" then
      import nixpkgs { }
    else
      (builtins.getFlake nixpkgs).legacyPackages.${builtins.currentSystem};
  lib = import ./library.nix { inherit (pkgs) lib; };
  build = lib.sbt-purifier.buildSbtProject (
    {
      inherit pkgs;
      src = /. + src;
    }
    // lib.optionalAttrs (lockFile != "") { lockFile = /. + lockFile; }
    // lib.optionalAttrs (pluginRepository != "") {
      # A store path passed as a string has no context; storePath gives
      # it one, so the package build may read the repository.
      pluginRepository = builtins.storePath pluginRepository;
    }
  );
  package =
    build.packages.${project}
      or (throw "sbt-purifier: the lockfile builds no project ${project} (it builds ${builtins.concatStringsSep ", " (builtins.attrNames build.packages)})");
in
{
  out = package;
  inherit (package) repository;
}
