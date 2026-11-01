# SPDX-License-Identifier: MIT
#
# The commands of the purifier contract, each a thin wrapper around a task
# of the plugin, run through the sbt wrapper (pkgs/sbt) from the root of
# the sbt build:
#
#   sbt-purifier lock      nixLock: lock the build, keeping a lockfile
#                          that is up to date as it is
#   sbt-purifier update    nixLockUpdate: resolve every dependency afresh,
#                          dynamic versions included, lock every flake
#                          repository at the newest revision of its
#                          reference, and relock
#   sbt-purifier prefetch  realize every locked download and the JDK in
#                          the Nix store, with garbage-collector roots
#                          beside the lockfile, so sbt and an editor work
#                          offline
#   sbt-purifier publish   nixPublish: publish what Nix built
#
# `--lockfile <path>` names a lockfile kept outside the build; it sets
# SBT_PURIFIER_LOCKFILE, which the plugin and the sbt wrapper read, and
# which a dev shell can set instead.
{
  lib,
  pkgs,
  writeShellScriptBin,
  sbt,
}:
let
  nixLib = ../../nix/lib;
in
writeShellScriptBin "sbt-purifier" ''
  set -euo pipefail

  usage() {
    echo "usage: sbt-purifier <lock|update|prefetch|publish> [--lockfile <path>] [sbt arguments...]" >&2
    exit 2
  }

  [ $# -ge 1 ] || usage
  command=$1
  shift
  args=()
  while [ $# -gt 0 ]; do
    case $1 in
      --lockfile)
        [ $# -ge 2 ] || usage
        SBT_PURIFIER_LOCKFILE=$(realpath -m "$2")
        export SBT_PURIFIER_LOCKFILE
        shift 2
        ;;
      *)
        args+=("$1")
        shift
        ;;
    esac
  done
  lockfile=''${SBT_PURIFIER_LOCKFILE:-$PWD/sbt-purifier.lock.json}

  # The problems readLock finds in the lockfile, one per line, each
  # naming the command that fixes it.
  problems() {
    nix-instantiate --eval --strict --json --expr \
      '{ src, lockFile }: (import ${nixLib}/read-lock.nix { src = /. + src; lockFile = /. + lockFile; }).problems' \
      --argstr src "$PWD" --argstr lockFile "$lockfile" |
      ${lib.getExe pkgs.jq} -r '.[]'
  }

  check() {
    found=$(problems)
    if [ -n "$found" ]; then
      echo "sbt-purifier: $found" >&2
      exit 1
    fi
  }

  case $command in
    lock)
      exec ${lib.getExe sbt} -batch "''${args[@]}" nixLock
      ;;
    update)
      if [ ''${#args[@]} -gt 0 ]; then
        echo "sbt-purifier: sbt resolves every dependency of the build at once, so update takes no package names; run \`sbt-purifier update\`" >&2
        exit 2
      fi
      exec ${lib.getExe sbt} -batch nixLockUpdate
      ;;
    prefetch)
      check
      major=$(sed -n 's/^  "javaVersion": "\([0-9]*\)",$/\1/p' "$lockfile")
      nix-build --expr \
        '{ lockFile, major }: let pkgs = import ${pkgs.path} { }; in [
          (pkgs.callPackage ${nixLib}/dependencies.nix { lockFile = /. + lockFile; })
          pkgs.javaPackages.compiler."openjdk''${major}".headless
        ]' \
        --argstr lockFile "$lockfile" --argstr major "$major" --out-link "$lockfile.gcroot"
      echo "sbt-purifier: every download of $lockfile, and the JDK, are in the Nix store; $lockfile.gcroot* keep them" >&2
      ;;
    publish)
      check
      exec ${lib.getExe sbt} -batch "''${args[@]}" nixPublish
      ;;
    *)
      usage
      ;;
  esac
''
