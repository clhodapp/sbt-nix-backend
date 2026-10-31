# SPDX-License-Identifier: MIT
#
# nixpkgs' sbt with sbt-nix-backend as a global plugin. sbt loads global
# plugins below every level of a build, so the plugin applies to
# project/project, project/ and the build itself, and governs the
# resolution of every plugin the build declares. No entry in the build's
# project/plugins.sbt is needed.
#
# sbt writes target/ directories into the global plugins directory, so the
# wrapper keeps a writable one per plugin version under
# $XDG_CACHE_HOME/sbt-nix-backend, holding the plugin's .sbt file and links
# to the user's own global plugins (~/.sbt/1.0/plugins, or the directory in
# SBT_NIX_BACKEND_USER_GLOBAL_PLUGINS).
#
# System properties tell the plugin what the wrapper knows:
# `sbt.nix.backend.inject` names the plugin's .sbt file, so a lock made
# with this sbt records the injection; `sbt.nix.backend.repository` is the
# repository a Nix build of such a lock injects the plugin from; and
# `sbt.nix.backend.nixpkgs` is the nixpkgs this flake pins, the default
# package set of the Nix builds nixPackagedArtifacts runs.
{
  lib,
  # nixpkgs' sbt, taken from `pkgs`: in this package scope `sbt` names
  # this wrapper.
  pkgs,
  runtimeShell,
  writeText,
  writeTextFile,
  pluginRepository,
  pluginVersion,
}:
let
  inject = writeText "sbt-nix-backend-plugin.sbt" ''
    resolvers += MavenRepository("sbt-nix-backend-plugin", "file://${pluginRepository}")
    addSbtPlugin("io.github.clhodapp" % "sbt-nix-backend" % "${pluginVersion}")
  '';
  # The directory name changes with the plugin, so a new plugin version
  # never meets the old one's resolution in target/.
  dirName = "global-plugins-" + builtins.substring 11 32 (toString inject);
in
writeTextFile {
  name = "sbt-with-nix-backend-${pluginVersion}";
  executable = true;
  destination = "/bin/sbt";
  derivationArgs.passthru = { inherit inject pluginRepository; };
  meta = {
    description = "sbt with the sbt-nix-backend plugin as a global plugin";
    mainProgram = "sbt";
  };
  text = ''
    #!${runtimeShell}
    set -eu
    dir="''${XDG_CACHE_HOME:-$HOME/.cache}/sbt-nix-backend/${dirName}"
    user="''${SBT_NIX_BACKEND_USER_GLOBAL_PLUGINS:-$HOME/.sbt/1.0/plugins}"
    mkdir -p "$dir"
    ln -sfn ${inject} "$dir/sbt-nix-backend.sbt"
    # Links to the user's global plugins: added for new entries, removed
    # for entries that are gone. target/ stays this directory's own.
    for link in "$dir"/* "$dir"/.[!.]*; do
      if [ -L "$link" ] && [ "$(basename "$link")" != sbt-nix-backend.sbt ] && [ ! -e "$link" ]; then
        rm "$link"
      fi
    done
    if [ -d "$user" ]; then
      for entry in "$user"/* "$user"/.[!.]*; do
        [ -e "$entry" ] || continue
        name=$(basename "$entry")
        [ "$name" = target ] && continue
        [ -e "$dir/$name" ] || ln -s "$entry" "$dir/$name"
      done
    fi
    exec ${lib.getExe pkgs.sbt} \
      -Dsbt.global.plugins="$dir" \
      -Dsbt.nix.backend.inject=${inject} \
      -Dsbt.nix.backend.repository=${pluginRepository} \
      -Dsbt.nix.backend.nixpkgs=${pkgs.path} \
      "$@"
  '';
}
