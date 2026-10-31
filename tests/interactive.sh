#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Tests of what runs outside the Nix sandbox, on copies of the multi (sbt 1)
# and sbt2 (sbt 2) fixtures with their committed lockfiles:
#
#  - interactive mode (feature 3): compile, test and run read every
#    dependency from the locked downloads, at every level of the build
#    (the meta-build's sbt-buildinfo included), with sbt's network cut off;
#  - nixPackagedArtifacts: sbt runs the Nix build and returns its files;
#  - a lockfile made from another build definition: sbt warns and still
#    loads, so nixLock can run.
#
# It needs Nix (the daemon downloads the locked artifacts) but gives sbt no
# network: the JVM's proxies point at a closed port. Run it from anywhere.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
sbt_wrapper=$(nix build --no-link --print-out-paths "$root#sbt")

work=$(mktemp -d)
trap 'chmod -R u+w "$work"; rm -rf "$work"' EXIT

unset JAVA_TOOL_OPTIONS COURSIER_MODE COURSIER_REPOSITORIES SBT_NIX_BACKEND
no_network="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9"

failures=0
fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}
pass() { echo "ok: $*"; }
# expect <description> <command>...: passes when the command succeeds.
expect() {
  local description=$1
  shift
  if "$@"; then pass "$description"; else fail "$description"; fi
}
has() { grep -qE "$1" <<<"$output"; }
from_store() { [[ -L $1 && $(readlink "$1") == /nix/store/* ]]; }

# Each fixture runs with a home, caches and runtime directory of its own.
use_fixture() {
  fixture=$1
  build="$work/$fixture"
  cp -r "$root/tests/fixtures/$fixture" "$build"
  chmod -R u+w "$build"
  rm -rf "$build/target" "$build"/*/target "$build/project/target" "$build/project/project"
  export HOME="$work/$fixture-home" XDG_CACHE_HOME="$work/$fixture-cache" XDG_RUNTIME_DIR="$work/$fixture-run"
  export COURSIER_CACHE="$work/$fixture-user-coursier-cache"
  mkdir -p "$HOME" "$XDG_RUNTIME_DIR"
  export SBT_OPTS="-Xss4m -Xmx2g -Duser.home=$HOME -Djava.io.tmpdir=$work -Dsbt.global.base=$HOME/.sbt \
    -Dsbt.boot.directory=$HOME/.sbt/boot -Dsbt.ivy.home=$HOME/.ivy2 -Dsbt.server.autostart=false \
    -Dsbt.server.forcestart=true -Dsbt.log.noformat=true -Dsbt.supershell=false $no_network"
}
run_sbt() { (cd "$build" && "$sbt_wrapper/bin/sbt" --server -batch "$@") 2>&1 | tee -a "$work/sbt.log"; }

# interactive_suite <fixture> <buildinfo jar path in the cache> <jar regex> <run command> <expected line regex>...
interactive_suite() {
  local fixture=$1 buildinfo=$2 jar=$3 run=$4
  shift 4
  use_fixture "$fixture"

  echo "== $fixture: interactive build with the network cut off"
  output=$(run_sbt compile app/test "$run") || fail "$fixture: the offline build failed"
  for line in "$@"; do expect "$fixture: the output has $line" has "$line"; done
  expect "$fixture: the tests run" has 'Passed: Total [1-9]'

  downloads=$(find "$COURSIER_CACHE" -type f -not -name '.*' 2>/dev/null | head -5 || true)
  expect "$fixture: no level downloaded into the user's coursier cache (found: ${downloads:-nothing})" \
    test -z "$downloads"
  expect "$fixture: the meta-build's plugin comes from the store" \
    from_store "$build/target/sbt-nix-backend/coursier-cache/$buildinfo"
  expect "$fixture: the locked downloads have a GC root" test -L "$build/target/sbt-nix-backend/gcroots/coursier-cache"

  echo "== $fixture: nixPackagedArtifacts"
  output=$(run_sbt 'show app/nixPackagedArtifacts' 'show app/nixRuntimeRepository') ||
    fail "$fixture: nixPackagedArtifacts failed"
  expect "$fixture: nixPackagedArtifacts returns the jar the Nix build made" has "$jar"
  expect "$fixture: nixRuntimeRepository returns the repository output" has 'app_3-[0-9.]+-repository'

  echo "== $fixture: a lockfile made from another build definition"
  echo '// changed' >>"$build/build.sbt"
  output=$(run_sbt 'show app/version') || fail "$fixture: sbt did not load with a stale lockfile"
  expect "$fixture: sbt warns that the lockfile is stale" has 'was made from another build definition'
}

maven=https/repo1.maven.org/maven2

interactive_suite multi \
  "$maven/com/eed3si9n/sbt-buildinfo_2.12_1.0/0.13.1/sbt-buildinfo_2.12_1.0-0.13.1.jar" \
  '/nix/store/[a-z0-9]+-app_3-1\.0\.0/example/app_3/1\.0\.0/app_3-1\.0\.0\.jar' \
  'app/runMain example.Main' \
  '\{"hello":"Nix World"\}' 'version 1\.0\.0'

interactive_suite sbt2 \
  "$maven/com/eed3si9n/sbt-buildinfo_sbt2_3/0.13.2/sbt-buildinfo_sbt2_3-0.13.2.jar" \
  '/nix/store/[a-z0-9]+-app_3-2\.0\.0/example/app_3/2\.0\.0/app_3-2\.0\.0\.jar' \
  'app/runMain example2.Main' \
  '\{"sbt":2\}' 'version 2\.0\.0'

if ((failures > 0)); then
  echo "$failures failure(s); the sbt output is in $work/sbt.log (kept)" >&2
  trap - EXIT
  exit 1
fi
echo "all interactive tests passed"
