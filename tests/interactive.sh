#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Tests of what runs outside the Nix sandbox, on a copy of the multi
# fixture with its committed lockfile:
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
cp -r "$root/tests/fixtures/multi" "$work/multi"
chmod -R u+w "$work/multi"
rm -rf "$work/multi/target" "$work/multi"/*/target "$work/multi/project/target" "$work/multi/project/project"

export HOME="$work/home" XDG_CACHE_HOME="$work/cache" XDG_RUNTIME_DIR="$work/run"
export COURSIER_CACHE="$work/user-coursier-cache"
mkdir -p "$HOME" "$XDG_RUNTIME_DIR"
unset JAVA_TOOL_OPTIONS COURSIER_MODE COURSIER_REPOSITORIES SBT_NIX_BACKEND
no_network="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9"
export SBT_OPTS="-Xss4m -Xmx2g -Duser.home=$HOME -Djava.io.tmpdir=$work -Dsbt.global.base=$HOME/.sbt \
  -Dsbt.boot.directory=$HOME/.sbt/boot -Dsbt.ivy.home=$HOME/.ivy2 -Dsbt.server.autostart=false \
  -Dsbt.server.forcestart=true -Dsbt.log.noformat=true -Dsbt.supershell=false $no_network"

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
sbt_in_multi() { (cd "$work/multi" && "$sbt_wrapper/bin/sbt" -batch "$@") 2>&1 | tee -a "$work/sbt.log"; }

echo "== interactive build with the network cut off"
output=$(sbt_in_multi compile app/test 'app/runMain example.Main') || fail "the offline build failed"
expect "the app runs" has '\{"hello":"Nix World"\}'
expect "the meta-build's plugin (sbt-buildinfo) works" has 'version 1\.0\.0'
expect "the tests run" has 'Passed: Total 2'

downloads=$(find "$COURSIER_CACHE" -type f -not -name '.*' 2>/dev/null | head -5 || true)
expect "no level downloaded into the user's coursier cache (found: ${downloads:-nothing})" test -z "$downloads"

buildinfo="$work/multi/target/sbt-nix-backend/coursier-cache/https/repo1.maven.org/maven2/com/eed3si9n/sbt-buildinfo_2.12_1.0/0.13.1/sbt-buildinfo_2.12_1.0-0.13.1.jar"
expect "the meta-build's plugin comes from the store" from_store "$buildinfo"
expect "the locked downloads have a GC root" test -L "$work/multi/target/sbt-nix-backend/gcroots/coursier-cache"

echo "== nixPackagedArtifacts"
output=$(sbt_in_multi 'show app/nixPackagedArtifacts' 'show app/nixRuntimeRepository') || fail "nixPackagedArtifacts failed"
expect "nixPackagedArtifacts returns the jar the Nix build made" \
  has '/nix/store/[a-z0-9]+-app_3-1\.0\.0/example/app_3/1\.0\.0/app_3-1\.0\.0\.jar'
expect "nixRuntimeRepository returns the repository output" has 'app_3-1\.0\.0-repository'

echo "== a lockfile made from another build definition"
echo '// changed' >>"$work/multi/build.sbt"
output=$(sbt_in_multi 'show app/version') || fail "sbt did not load with a stale lockfile"
expect "sbt warns that the lockfile is stale" has 'was made from another build definition'

if ((failures > 0)); then
  echo "$failures failure(s); the sbt output is in $work/sbt.log (kept)" >&2
  trap - EXIT
  exit 1
fi
echo "all interactive tests passed"
