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

(cd "$root/tests/fixtures/multi" && "$sbt_wrapper/bin/sbt" -batch "$task")

# The producer flake for the consumer fixture: a copy of multi with a
# flake.nix that exports its repositories as sbtRepositories.
producer=$(mktemp -d)
trap 'rm -rf "$producer"' EXIT
cp -r "$root/tests/fixtures/multi/." "$producer/"
rm -rf "$producer/target" "$producer/project/target" "$producer/project/project"
system=$(nix eval --impure --raw --expr builtins.currentSystem)
cat > "$producer/flake.nix" <<EOF
{
  inputs.sbt-nix-backend.url = "git+file://$root";
  outputs = { sbt-nix-backend, ... }:
    let
      pkgs = sbt-nix-backend.inputs.nixpkgs.legacyPackages.$system;
      build = sbt-nix-backend.lib.sbt-nix-backend.buildSbtProject {
        inherit pkgs;
        src = ./.;
      };
    in { sbtRepositories.$system = build.sbtRepositories; };
}
EOF

(cd "$root/tests/fixtures/consumer" &&
  SBT_NIX_BACKEND_TEST_PRODUCER="path:$producer" "$sbt_wrapper/bin/sbt" -batch "$task")
