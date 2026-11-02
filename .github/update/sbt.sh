#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# sbt.sh: moves this repository to the newest sbt 1 and sbt 2 releases on
# Maven Central. Run it from the root of a checkout; it edits files and
# prints what it changed, and prints nothing when everything is current.
#
# For each line of sbt, it moves:
#  - the sbt version the plugin compiles against
#    (pluginCrossBuild / sbtVersion in build.sbt), and the Scala version
#    that sbt release runs on, which the plugin compiles with
#    (crossScalaVersions and scalaVersion in build.sbt), read from the
#    scala-library or scala3-library dependency of its POM; a plugin
#    built with a newer Scala than the sbt that loads it does not load;
#  - project/build.properties, for sbt 1 (the build of the plugin itself);
#  - the project/build.properties of the test fixtures on that line,
#    except the consumer fixture, whose lockfile pins a commit of this
#    repository on GitHub (tests/relock-fixtures.sh).
#
# Only stable releases count: versions of the form X.Y.Z.
set -euo pipefail

central=https://repo1.maven.org/maven2/org/scala-sbt/sbt

latest() {
  curl -sf "$central/maven-metadata.xml" | grep -oP '<version>\K[0-9]+\.[0-9]+\.[0-9]+(?=</version>)' |
    grep "^$1\." | sort -V | tail -n 1
}

# scala_of <sbt version> <artifactId>: the version of that Scala library in
# the POM of the sbt release.
scala_of() {
  curl -sf "$central/$1/sbt-$1.pom" | tr -d ' \n' |
    grep -oP "<artifactId>$2</artifactId><version>\K[^<]+" | head -n 1
}

current_sbt() { grep -oP "case $1 +=> \"\K[^\"]+" build.sbt; }

changes=()

# move <old> <new> <file>...: replaces the version in each file.
move() {
  local old=$1 new=$2
  shift 2
  if [[ $old == "$new" ]]; then return; fi
  for f in "$@"; do sed -i "s/\"$old\"/\"$new\"/g; s/=$old\$/=$new/" "$f"; done
  changes+=("$old -> $new")
}

sbt1_old=$(current_sbt '"2\.12"')
sbt1_new=$(latest 1)
sbt2_old=$(current_sbt _)
sbt2_new=$(latest 2)
scala2_old=$(grep -oP 'scalaVersion := "\K2\.12\.[0-9]+' build.sbt)
scala2_new=$(scala_of "$sbt1_new" scala-library)
scala3_old=$(grep -oP 'crossScalaVersions := Seq\("2\.12\.[0-9]+", "2\.10\.[0-9]+", "\K3\.[0-9.]+' build.sbt)
scala3_new=$(scala_of "$sbt2_new" scala3-library_3)

for v in "$sbt1_new" "$sbt2_new" "$scala2_new" "$scala3_new"; do
  if [[ -z $v ]]; then
    echo "sbt.sh: could not read a version from Maven Central" >&2
    exit 1
  fi
done

move "$sbt1_old" "$sbt1_new" build.sbt project/build.properties \
  tests/fixtures/{multi,ivy,matrix,metameta}/project/build.properties
move "$sbt2_old" "$sbt2_new" build.sbt tests/fixtures/{sbt2,matrix2}/project/build.properties
move "$scala2_old" "$scala2_new" build.sbt
move "$scala3_old" "$scala3_new" build.sbt

if ((${#changes[@]} > 0)); then
  printf 'sbt %s, %s; Scala %s, %s\n' "$sbt1_new" "$sbt2_new" "$scala2_new" "$scala3_new"
  printf '%s\n' "${changes[@]}"
fi
