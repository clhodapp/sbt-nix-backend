# Feature 1: the coursier cache of a lockfile, every locked file at its
# coursier-cache path as a link into the store. It is a package for
# callPackage, with the lockfile among its arguments. buildSbtProject's
# `dependencies` is this; the sbt-nix-backend sbt wrapper builds it on its
# own (it needs no build source) to boot sbt from the locked downloads.
{
  lib,
  runCommand,
  lockFile,
  # Replaces the built-in fetcher; see fetch.nix.
  fetchurl ? null,
}:
let
  lock = import ./lock.nix { inherit lockFile; };
  fetch = import ./fetch.nix { inherit lock fetchurl; };
in
# The manifest goes to the builder as a file, since a large build locks
# thousands of artifacts and the list would not fit in an environment
# variable.
runCommand "sbt-coursier-cache"
  {
    manifest = lib.concatMapStrings (a: "${a.path}\t${fetch a}\n") lock.artifacts;
    passAsFile = [ "manifest" ];
    preferLocalBuild = true;
    allowSubstitutes = false;
    passthru = { inherit lock; };
  }
  ''
    mkdir -p $out
    while IFS=$'\t' read -r rel target; do
      mkdir -p "$out/$(dirname "$rel")"
      ln -s "$target" "$out/$rel"
    done < "$manifestPath"
  ''
