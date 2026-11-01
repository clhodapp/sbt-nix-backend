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
    # sbt itself boots before any level of the build exists, and the
    # launcher downloads it through COURSIER_CACHE. With a lockfile for
    # this sbt version, that cache is the locked downloads, so sbt boots
    # from them. The plugin points the build's own resolution elsewhere
    # (the locked cache, or the user's cache named here), so nothing
    # writes into this read-only tree.
    lockfile=''${SBT_PURIFIER_LOCKFILE:-sbt-purifier.lock.json}
    case "''${SBT_NIX_BACKEND:-1}" in 0 | false) lockfile= ;; esac
    # sbt runs on nixpkgs' JDK of the Java major the lockfile records, so
    # a relock records the same major whatever JAVA_HOME the caller has;
    # without a lockfile, on nixpkgs' default JDK.
    export JAVA_HOME=${pkgs.jdk.home}
    if [ -n "$lockfile" ] && [ -f "$lockfile" ]; then
      major=$(sed -n 's/^  "javaVersion": "\([0-9]*\)",$/\1/p' "$lockfile")
      if [ -n "$major" ]; then
        mkdir -p target/sbt-purifier/gcroots
        if jdk=$(nix-build ${pkgs.path} -A "javaPackages.compiler.openjdk$major.headless" \
          --out-link target/sbt-purifier/gcroots/jdk 2>/dev/null); then
          export JAVA_HOME="$jdk/lib/openjdk"
        else
          echo "sbt-purifier: nixpkgs has no headless OpenJDK $major, the Java major $lockfile records; sbt runs on $JAVA_HOME" >&2
        fi
      fi
    fi
    if [ -n "$lockfile" ] && [ -f "$lockfile" ] && [ -f project/build.properties ]; then
      locked=$(sed -n 's/^  "sbtVersion": "\(.*\)",$/\1/p' "$lockfile")
      wanted=$(sed -n 's/^[[:space:]]*sbt\.version[[:space:]]*=[[:space:]]*\([^[:space:]]*\).*/\1/p' project/build.properties)
      # A lockfile of another format (made by another plugin version) is
      # one nixLock replaces; until then, sbt boots the usual way.
      format=$(sed -n 's/^  "version": \([0-9]*\),$/\1/p' "$lockfile")
      if [ -n "$locked" ] && [ "$locked" = "$wanted" ] && [ "$format" = "${toString (import ../../nix/lib/lock-version.nix)}" ]; then
        mkdir -p target/sbt-nix-backend/gcroots
        if tree=$(nix-build --expr \
          '{ lockFile }: (import ${pkgs.path} { }).callPackage ${../../nix/lib}/dependencies.nix { inherit lockFile; }' \
          --arg lockFile "$(realpath "$lockfile")" --out-link target/sbt-nix-backend/gcroots/boot-cache); then
          export SBT_NIX_BACKEND_USER_COURSIER_CACHE="''${COURSIER_CACHE:-}"
          export COURSIER_CACHE="$tree"
        else
          echo "sbt-nix-backend: the locked downloads did not build; sbt boots the usual way" >&2
        fi
      fi
    fi
    # A build that also installs the plugin itself, in its
    # project/project/plugins.sbt, resolves it from the repository this
    # variable names; naming the wrapper's own repository there gives that
    # build the jar the wrapper injects, so one plugin build runs.
    export SBT_NIX_BACKEND_REPOSITORY=${pluginRepository}
    # sbt 2 keeps the target of the global plugins project in the build's
    # own target/out/jvm/<Scala version>/global-plugins, and its update
    # cache there names the plugin repository it resolved the plugin from.
    # A stamp records the plugin file this wrapper injects; when it names
    # another, the wrapper removes those targets, so sbt 2 resolves the
    # plugin of this wrapper.
    if [ -f build.sbt ] || [ -d project ]; then
      stamp=target/sbt-nix-backend/global-plugins-inject
      if [ "$(cat "$stamp" 2>/dev/null)" != ${inject} ]; then
        for target in target/out/jvm/*/global-plugins; do
          if [ -d "$target" ]; then rm -rf "$target"; fi
        done
        mkdir -p target/sbt-nix-backend
        echo ${inject} > "$stamp"
      fi
    fi
    # The properties go in SBT_OPTS rather than on the command line: for an
    # sbt 2 build the runner starts a client, and the server it starts in
    # the background gets SBT_OPTS too.
    export SBT_OPTS="-Dsbt.global.plugins=$dir -Dsbt.nix.backend.inject=${inject} -Dsbt.nix.backend.repository=${pluginRepository} -Dsbt.nix.backend.nixpkgs=${pkgs.path} ''${SBT_OPTS:-}"
    exec ${lib.getExe pkgs.sbt} "$@"
  '';
}
