#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# pages.sh <site> <zips>: builds the GitHub Pages site under <site> from
# the release zips in <zips> (each sbt-nix-backend-<version>-maven.zip,
# as release.yml attaches them to the GitHub releases): a Maven
# repository under <site>/maven, served at
# https://clhodapp.github.io/sbt-nix-backend/maven. The GitHub releases
# are the only copy of the artifacts; the site is rebuilt from all of them
# at each release, and nothing of it is in git.
#
# Each artifact directory gets a maven-metadata.xml listing its versions,
# with its checksums, so tools that resolve a version range or the latest
# version find them.
set -euo pipefail

site=$1
zips=$2
repo="$site/maven"
mkdir -p "$repo"

shopt -s nullglob
archives=("$zips"/*-maven.zip)
if ((${#archives[@]} == 0)); then
  echo "pages.sh: no release zips in $zips" >&2
  exit 1
fi
for zip in "${archives[@]}"; do
  # Versions do not overlap, so no file is in two zips.
  unzip -q -n "$zip" -d "$repo"
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

# GitHub Pages serves the site as it is, without Jekyll.
touch "$site/.nojekyll"
