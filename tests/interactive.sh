#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Tests of what runs outside the Nix sandbox, on copies of the multi (sbt 1),
# sbt2 (sbt 2) and ivy (sbt 1, resolving with Ivy) fixtures with their
# committed lockfiles:
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

# A suite with the network cut off points the JVM's proxies at a closed
# port; one with the network keeps the proxy settings it was started with.
network_java_options=${JAVA_TOOL_OPTIONS:-}
unset JAVA_TOOL_OPTIONS COURSIER_MODE COURSIER_REPOSITORIES SBT_NIX_BACKEND
no_network="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9"
network=off

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
  if [[ -d $build ]]; then chmod -R u+w "$build"; rm -rf "$build"; fi
  cp -r "$root/tests/fixtures/$fixture" "$build"
  chmod -R u+w "$build"
  rm -rf "$build/target" "$build"/*/target "$build/project/target" "$build/project/project/target"
  export HOME="$work/$fixture-home" XDG_CACHE_HOME="$work/$fixture-cache" XDG_RUNTIME_DIR="$work/$fixture-run"
  export COURSIER_CACHE="$work/$fixture-user-coursier-cache"
  mkdir -p "$HOME" "$XDG_RUNTIME_DIR"
  local proxies=$no_network
  if [[ $network == on ]]; then
    proxies=""
    [[ -n $network_java_options ]] && export JAVA_TOOL_OPTIONS=$network_java_options
  else
    unset JAVA_TOOL_OPTIONS
  fi
  export SBT_OPTS="-Xss4m -Xmx2g -Duser.home=$HOME -Djava.io.tmpdir=$work -Dsbt.global.base=$HOME/.sbt \
    -Dsbt.boot.directory=$HOME/.sbt/boot -Dsbt.ivy.home=$HOME/.ivy2 -Dsbt.server.autostart=false \
    -Dsbt.server.forcestart=true -Dsbt.log.noformat=true -Dsbt.supershell=false $proxies"
}

# The modules of the build and its meta-build: under plain sbt, where sbt
# itself boots through the user's coursier cache, none of these may come
# from there.
locked_only_modules=(upickle munit sbt-buildinfo scala3-library)
# The sbt a suite runs: the sbt-nix-backend wrapper, or plain nixpkgs sbt
# from the flake's dev shell (`plain`), for a build that installs the plugin
# itself.
runner=wrapper
network=off
run_sbt() {
  if [[ $runner == plain ]]; then
    (cd "$build" && nix develop "$root" -c sbt --server -batch "$@") 2>&1 | tee -a "$work/sbt.log"
  else
    (cd "$build" && "$sbt_wrapper/bin/sbt" --server -batch "$@") 2>&1 | tee -a "$work/sbt.log"
  fi
}

# interactive_suite <fixture> <buildinfo jar path in the cache> <jar regex> <run command> <expected line regex>...
interactive_suite() {
  local fixture=$1 buildinfo=$2 jar=$3 run=$4
  shift 4
  use_fixture "$fixture"

  echo "== $fixture: interactive build with the network cut off"
  output=$(run_sbt compile app/test "$run") || fail "$fixture: the offline build failed"
  for line in "$@"; do expect "$fixture: the output has $line" has "$line"; done
  expect "$fixture: the tests run" has 'Passed: Total [1-9]'

  if [[ $network == off ]]; then
    downloads=$(find "$COURSIER_CACHE" -type f -not -name '.*' 2>/dev/null | head -5 || true)
    expect "$fixture: no level downloaded into the user's coursier cache (found: ${downloads:-nothing})" \
      test -z "$downloads"
  else
    for module in "${locked_only_modules[@]}"; do
      found=$(find "$COURSIER_CACHE" -type d -name "$module*" 2>/dev/null | head -1 || true)
      expect "$fixture: $module comes from the locked downloads, not the user's cache (found: ${found:-nothing})" \
        test -z "$found"
    done
  fi
  expect "$fixture: the meta-build's plugin comes from the store" \
    from_store "$build/target/sbt-nix-backend/coursier-cache/$buildinfo"
  expect "$fixture: the locked downloads have a GC root" test -L "$build/target/sbt-nix-backend/gcroots/coursier-cache"

  echo "== $fixture: nixPackagedArtifacts"
  output=$(run_sbt 'show app/nixPackagedArtifacts' 'show app/nixRuntimeRepository') ||
    fail "$fixture: nixPackagedArtifacts failed"
  expect "$fixture: nixPackagedArtifacts returns the jar the Nix build made" has "$jar"
  expect "$fixture: nixRuntimeRepository returns the repository output" has 'app_[0-9.]+-[0-9.]+-repository'

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

# ivy resolves with Ivy (useCoursier := false) in the build and its
# meta-build; Ivy reads the locked downloads through a file: mirror.
interactive_suite ivy \
  "$maven/com/eed3si9n/sbt-buildinfo_2.12_1.0/0.13.1/sbt-buildinfo_2.12_1.0-0.13.1.jar" \
  '/nix/store/[a-z0-9]+-app_2\.13-4\.0\.0/example/app_2\.13/4\.0\.0/app_2\.13-4\.0\.0\.jar' \
  'app/runMain ivyexample.Main' \
  '\{"resolvedWith":"ivy"\}' 'version 4\.0\.0'

# metameta installs the plugin only in project/project/plugins.sbt and runs
# under plain sbt, without the wrapper. Without the wrapper, sbt boots
# itself and the meta-most build (project/project) resolves the plugin
# before the plugin exists, so those two take the network and the user's
# cache; the levels the plugin reaches (project/ and the build) resolve
# from the locked downloads.
runner=plain
network=on
interactive_suite metameta \
  "$maven/com/eed3si9n/sbt-buildinfo_2.12_1.0/0.13.1/sbt-buildinfo_2.12_1.0-0.13.1.jar" \
  '/nix/store/[a-z0-9]+-app_3-3\.0\.0/example/app_3/3\.0\.0/app_3-3\.0\.0\.jar' \
  'app/runMain metameta.Main' \
  '\{"installed":"project/project"\}' 'version 3\.0\.0'

echo "== metameta: the plugin is active in the meta-build"
use_fixture metameta
output=$(run_sbt 'reload plugins' 'show nixBackend' 'show nixBuildRoot' 'reload return') ||
  fail "metameta: sbt did not load the meta-build"
expect "metameta: the meta-build reads the lockfile" has 'info\] true'
expect "metameta: the meta-build takes the build root for its own" has "info\] $build\$"

# The same build, which installs the plugin itself, run through the
# wrapper, which injects it too. Relocking needs the network.
echo "== metameta through the wrapper"
runner=wrapper
use_fixture metameta
unset SBT_NIX_BACKEND_REPOSITORY
wrapper_repository=$(sed -n 's/^export SBT_NIX_BACKEND_REPOSITORY=//p' "$sbt_wrapper/bin/sbt")
output=$(run_sbt nixRelock 'app/runMain metameta.Main' 'show app/nixPackagedArtifacts') ||
  fail "metameta through the wrapper: the build failed"
expect "metameta through the wrapper: the app runs" has '\{"installed":"project/project"\}'
expect "metameta through the wrapper: the lockfile records the injected plugin" \
  grep -q '"via": "global-plugin"' "$build/sbt-nix-backend.lock.json"
expect "metameta through the wrapper: the Nix build of that lockfile succeeds" \
  has '/nix/store/[a-z0-9]+-app_3-3\.0\.0/example/app_3/3\.0\.0/app_3-3\.0\.0\.jar'
# project/project resolves the plugin from the repository the wrapper
# injects it from, so one plugin build runs at every level.
output=$(run_sbt 'reload plugins' 'reload plugins' 'export Runtime/fullClasspath' 'reload return' 'reload return') ||
  fail "metameta through the wrapper: sbt did not load the meta-meta-build"
plugin_jars=$(tr ':' '\n' <<<"$output" | grep 'sbt-nix-backend_2\.12_1\.0-.*\.jar' | sort -u || true)
expect "metameta through the wrapper: the meta-meta-build has one plugin jar, from the wrapper's repository (found: $plugin_jars)" \
  test "$(wc -l <<<"$plugin_jars")" -eq 1 -a "${plugin_jars#"$wrapper_repository"/}" != "$plugin_jars"
runner=wrapper
network=off

if ((failures > 0)); then
  echo "$failures failure(s); the sbt output is in $work/sbt.log (kept)" >&2
  trap - EXIT
  exit 1
fi
echo "all interactive tests passed"
