#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# The conformance-update check of the purifier contract. The update fixture
# depends on example:lib from a producer flake that this script makes in a
# git repository of its own, with lib 1.0.0 in its first commit. The script
# locks the fixture at that commit and then commits lib 1.1.0.
# `sbt-purifier lock` keeps the lockfile's pin of the first commit,
# `sbt-purifier update` moves it to the second, and the lockfile then
# passes the stale check. It runs as a CI job, because the lock needs the
# network, and it runs from any directory.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cli=$(nix build --no-link --print-out-paths "$root#sbt-purifier")/bin/sbt-purifier
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The producer builds its repository with the nixpkgs this repository pins,
# which the flake checks have already fetched.
nixpkgs=$(jq -r '.nodes.nixpkgs.locked | "github:\(.owner)/\(.repo)/\(.rev)"' "$root/flake.lock")
producer=$work/producer
mkdir -p "$producer"
cat > "$producer/flake.nix" <<EOF
{
  inputs.nixpkgs.url = "$nixpkgs";
  outputs = { nixpkgs, ... }: {
    sbtRepositories = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-linux" ] (system: {
      lib = nixpkgs.legacyPackages.\${system}.runCommandLocal "lib-repository" { } "cp -r \${./repository} \$out";
    });
  };
}
EOF

# Writes the repository of example:lib <version>: its POM and an empty jar
# in Maven layout, and the metadata that nixFlakeModule reads.
publish() {
  local version=$1 dir=$producer/repository/example/lib/$1
  rm -rf "$producer/repository"
  mkdir -p "$dir"
  cat > "$dir/lib-$version.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>lib</artifactId>
  <version>$version</version>
  <packaging>jar</packaging>
</project>
EOF
  python3 -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "w").writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")' "$dir/lib-$version.jar"
  for f in "$dir"/*; do
    sha1sum "$f" | cut -d' ' -f1 | tr -d '\n' > "$f.sha1"
  done
  printf '{"modules":[{"organization":"example","name":"lib","version":"%s","layout":"maven","attributes":{},"mainClass":null}]}\n' "$version" \
    > "$producer/repository/sbt-nix-repository.json"
  git -C "$producer" add -A
  git -C "$producer" -c user.name=update.sh -c user.email=update.sh@example.invalid commit -q -m "lib $version"
  git -C "$producer" rev-parse HEAD
}

git -C "$producer" init -q
first=$(publish 1.0.0)
export SBT_PURIFIER_TEST_UPDATE_PRODUCER="git+file://$producer"

cp -r "$root/tests/fixtures/update" "$work/update"
cd "$work/update"
XDG_CACHE_HOME=$work/cache
export XDG_CACHE_HOME

pin() {
  jq -r '.flakeRepositories[] | select(.name == "lib") | .locked' sbt-purifier.lock.json
}

"$cli" lock
case $(pin) in
  *"rev=$first"*) ;;
  *)
    echo "update.sh: the lock pins $(pin), not the first commit $first" >&2
    exit 1
    ;;
esac

second=$(publish 1.1.0)

"$cli" lock
case $(pin) in
  *"rev=$first"*) ;;
  *)
    echo "update.sh: lock moved the pin to $(pin); it keeps the commit the lockfile recorded, $first" >&2
    exit 1
    ;;
esac

"$cli" update
case $(pin) in
  *"rev=$second"*) ;;
  *)
    echo "update.sh: update left the pin at $(pin), not the newest commit $second" >&2
    exit 1
    ;;
esac

# The stale check: the command finds the lockfile current, and the
# prefetch reads it through readLock and checkLock.
"$cli" lock 2>&1 | tee "$work/lock.log"
grep -q 'is up to date' "$work/lock.log"
"$cli" prefetch
echo "update.sh: passed"
