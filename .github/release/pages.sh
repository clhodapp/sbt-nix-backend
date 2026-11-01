#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# pages.sh <site> <artifacts>: adds the release in <artifacts> (the
# directory build.sh wrote) to the Maven repository under <site>/maven,
# the checkout of the gh-pages branch that GitHub Pages serves at
# https://clhodapp.github.io/sbt-nix-backend/maven. A version already in
# the repository is left as it is.
#
# Each artifact directory gets a maven-metadata.xml listing its versions,
# with its checksums, so tools that resolve a version range or the latest
# version find them.
set -euo pipefail

site=$1
artifacts=$2
repo="$site/maven"
mkdir -p "$repo"

(cd "$artifacts" && find . -type f -not -name SHA256SUMS) | while read -r f; do
  target="$repo/${f#./}"
  if [[ -e $target ]]; then continue; fi
  mkdir -p "$(dirname "$target")"
  cp "$artifacts/$f" "$target"
done

now=$(date -u +%Y%m%d%H%M%S)
# An artifact directory holds one directory per version, each with a POM.
find "$repo" -name '*.pom' -printf '%h\n' | xargs -n1 dirname | sort -u | while read -r adir; do
  artifact=$(basename "$adir")
  group=$(dirname "${adir#"$repo"/}" | tr / .)
  versions=$(find "$adir" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V)
  latest=$(tail -n 1 <<<"$versions")
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<metadata>'
    echo "  <groupId>$group</groupId>"
    echo "  <artifactId>$artifact</artifactId>"
    echo '  <versioning>'
    echo "    <latest>$latest</latest>"
    echo "    <release>$latest</release>"
    echo '    <versions>'
    while read -r v; do echo "      <version>$v</version>"; done <<<"$versions"
    echo '    </versions>'
    echo "    <lastUpdated>$now</lastUpdated>"
    echo '  </versioning>'
    echo '</metadata>'
  } >"$adir/maven-metadata.xml"
  sha1sum "$adir/maven-metadata.xml" | cut -d' ' -f1 >"$adir/maven-metadata.xml.sha1"
  md5sum "$adir/maven-metadata.xml" | cut -d' ' -f1 >"$adir/maven-metadata.xml.md5"
done

# GitHub Pages serves the branch as it is, without Jekyll.
touch "$site/.nojekyll"
