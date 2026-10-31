# What the plugin's nixPackagedArtifacts and nixRuntimeRepository build:
# one project of a build, by buildSbtProject (default.nix), from arguments
# that sbt passes on the command line as strings.
#
#   nixpkgs           a nixpkgs source directory, or a flake reference
#   src               the build root
#   project           the sbt project id
#   pluginRepository  the repository to inject the plugin from, or ""
#
# `out` and `repository` are the two outputs of the project's package.
{
  nixpkgs,
  src,
  project,
  pluginRepository ? "",
}:
let
  pkgs =
    if builtins.substring 0 1 nixpkgs == "/" then
      import nixpkgs { }
    else
      (builtins.getFlake nixpkgs).legacyPackages.${builtins.currentSystem};
  build = import ./. {
    inherit pkgs;
    src = /. + src;
    # A store path passed as a string has no context; storePath gives it
    # one, so the package build may read the repository.
    pluginRepository = if pluginRepository == "" then null else builtins.storePath pluginRepository;
  };
  package =
    build.packages.${project}
      or (throw "sbt-nix-backend: the lockfile builds no project ${project} (it builds ${builtins.concatStringsSep ", " (builtins.attrNames build.packages)})");
in
{
  out = package;
  inherit (package) repository;
}
