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
# The consumer fixture refers to the repositories of the multi and ivy
# fixtures through tests/producers, a flake in this repository, on GitHub.
# Its lockfile pins the commit this script runs from, which must be pushed;
# the flake checks pass the repository outputs of this checkout instead.
# While the GitHub repository is private, Nix fetches it with the token of
# the GitHub CLI, which this script passes in NIX_CONFIG.
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
trap 'rm -rf "$XDG_CACHE_HOME"' EXIT

for fixture in multi sbt2 ivy matrix matrix2; do
  (cd "$root/tests/fixtures/$fixture" && "$sbt_wrapper/bin/sbt" --server -batch "$task")
done

# metameta installs the plugin in its own project/project/plugins.sbt and
# runs under plain sbt, from the flake's dev shell.
(cd "$root/tests/fixtures/metameta" && nix develop "$root" -c sbt --server -batch "$task")

# The consumer fixture locks tests/producers at the commit this checkout is
# at, on GitHub.
rev=$(git -C "$root" rev-parse HEAD)
if [[ -z $(git -C "$root" branch -r --contains "$rev" 2>/dev/null) ]]; then
  echo "relock-fixtures: push $rev first; the consumer fixture locks it from GitHub" >&2
  exit 1
fi
if command -v gh >/dev/null && token=$(gh auth token 2>/dev/null); then
  NIX_CONFIG="access-tokens = github.com=$token"${NIX_CONFIG:+$'\n'"$NIX_CONFIG"}
  export NIX_CONFIG
fi

(cd "$root/tests/fixtures/consumer" &&
  SBT_NIX_BACKEND_TEST_PRODUCER="github:clhodapp/sbt-nix-backend/$rev?dir=tests/producers" \
    "$sbt_wrapper/bin/sbt" --server -batch "$task")
