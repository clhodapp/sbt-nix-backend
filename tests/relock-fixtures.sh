#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Brings the lockfiles of this repository's own build and of the test
# fixtures up to date.
#
#   tests/relock-fixtures.sh          relock what is stale (nixLock)
#   tests/relock-fixtures.sh --relock relock everything (nixRelock)
#
# Run it from anywhere, with the network: a relock downloads every artifact
# of a build afresh. The fixtures run with the plugin injected by this
# flake's `sbt` wrapper, the way a build without the plugin in
# project/plugins.sbt uses it.
#
# The consumer fixture refers to the multi fixture as a flake. Its lockfile
# pins a `path:` reference to a copy of multi under $TMPDIR, which exists
# only while this script runs; the flake checks pass multi's repository
# output in its place.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
task=nixLock
if [[ ${1:-} == --relock ]]; then task=nixRelock; fi

# The root build compiles the plugin from source in its meta-build.
(cd "$root" && sbt -batch "$task")

sbt_wrapper=$(nix build --no-link --print-out-paths "$root#sbt")

# A meta-build keeps its resolution of the plugin in project/target, and
# sbt 2 keeps one in its disk cache under XDG_CACHE_HOME, while the plugin
# version stays the same, even when the plugin changed. Fresh meta-builds
# and a cache directory of this run's own resolve the current plugin.
for fixture in multi sbt2 ivy matrix matrix2 metameta consumer; do
  rm -rf "$root/tests/fixtures/$fixture/project/target" "$root/tests/fixtures/$fixture/project/project/target"
done
XDG_CACHE_HOME=$(mktemp -d)
export XDG_CACHE_HOME

for fixture in multi sbt2 ivy matrix matrix2; do
  (cd "$root/tests/fixtures/$fixture" && "$sbt_wrapper/bin/sbt" --server -batch "$task")
done

# metameta installs the plugin in its own project/project/plugins.sbt and
# runs under plain sbt, from the flake's dev shell.
(cd "$root/tests/fixtures/metameta" && nix develop "$root" -c sbt --server -batch "$task")

# The producer flakes for the consumer fixture: copies of multi and ivy,
# each with a flake.nix that exports its repositories as sbtRepositories.
producer=$(mktemp -d)
ivy_producer=$(mktemp -d)
trap 'rm -rf "$producer" "$ivy_producer" "$XDG_CACHE_HOME"' EXIT
system=$(nix eval --impure --raw --expr builtins.currentSystem)
make_producer() {
  local fixture=$1 dir=$2
  cp -r "$root/tests/fixtures/$fixture/." "$dir/"
  rm -rf "$dir/target" "$dir"/*/target "$dir/project/target" "$dir/project/project/target"
  cat >"$dir/flake.nix" <<EOF
{
  inputs.sbt-nix-backend.url = "git+file://$root";
  outputs = { sbt-nix-backend, ... }:
    let
      pkgs = sbt-nix-backend.inputs.nixpkgs.legacyPackages.$system.extend sbt-nix-backend.overlays.default;
      build = pkgs.buildSbtProject { src = ./.; };
    in { sbtRepositories.$system = build.sbtRepositories; };
}
EOF
}
make_producer multi "$producer"
make_producer ivy "$ivy_producer"

(cd "$root/tests/fixtures/consumer" &&
  SBT_NIX_BACKEND_TEST_PRODUCER="path:$producer" SBT_NIX_BACKEND_TEST_IVY_PRODUCER="path:$ivy_producer" \
    "$sbt_wrapper/bin/sbt" --server -batch "$task")
