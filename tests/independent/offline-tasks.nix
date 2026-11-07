# SPDX-License-Identifier: MIT
# Ordinary task replay in the supported sandbox mode, without a Nix daemon.
# The interactive wrapper/daemon integration is tested by runtime-sample.py.
{
  pkgs,
  build,
  src,
  pluginRepository,
}:
let
  inherit (pkgs) lib;
  inherit (build) lock;
  missing = builtins.head (
    builtins.filter (
      a:
      lib.hasInfix "/scalatest-refspec_" a.path
      && lib.hasSuffix ".jar" a.path
      && !(lib.hasSuffix "-sources.jar" a.path || lib.hasSuffix "-javadoc.jar" a.path)
    ) lock.artifacts
  );
  inject = pkgs.writeText "sample-offline-plugin.sbt" ''
    resolvers += MavenRepository("sbt-purifier", "file://${pluginRepository}")
    addSbtPlugin("${lock.plugin.organization}" % "${lock.plugin.name}" % "${lock.plugin.version}")
  '';
in
pkgs.runCommand "test-interface-sbt-${lock.sbtVersion}-offline-tasks"
  {
    nativeBuildInputs = [ build.jdk ];
    passthru = {
      inherit src;
      sbtVersion = lock.sbtVersion;
      javaMajor = lock.javaVersion;
      dependencies = build.dependencies;
      inherit pluginRepository;
    };
  }
  ''
    set -euo pipefail
    export SBT_NIX_MODE=sandbox COURSIER_MODE=offline
    export SBT_NIX_BACKEND_REPOSITORY=${pluginRepository}
    run_tasks() (
      label=$1
      state="$TMPDIR/$label"
      mkdir -p "$state/project" "$state/home" "$state/run" "$state/tmp" "$state/plugins"
      cp -r ${src}/. "$state/project"
      chmod -R u+w "$state/project"
      cp ${./test-interface-sbt1.lock.json} "$state/project/sbt-purifier.lock.json"
      cp ${inject} "$state/plugins/plugin.sbt"
      export HOME="$state/home" XDG_CACHE_HOME="$state/home/.cache" XDG_RUNTIME_DIR="$state/run"
      export COURSIER_CACHE="$state/coursier"
      mkdir -p "$COURSIER_CACHE"
      cp -rs --no-preserve=mode ${build.dependencies}/. "$COURSIER_CACHE"
      if [ "$label" = missing ]; then
        test -f "$COURSIER_CACHE/${missing.path}"
        rm "$COURSIER_CACHE/${missing.path}"
      else
        cmp "$COURSIER_CACHE/${missing.path}" "${build.dependencies}/${missing.path}"
      fi
      cd "$state/project"
      java ${lib.escapeShellArgs lock.javaOptions} -XX:ActiveProcessorCount=2 \
        -Duser.home="$HOME" -Djava.io.tmpdir="$state/tmp" \
        -Dsbt.global.base="$HOME/.sbt" -Dsbt.boot.directory="$HOME/.sbt/boot" \
        -Dsbt.ivy.home="$HOME/.ivy2" -Dsbt.global.plugins="$state/plugins" \
        -Dsbt.nix.backend.inject="$state/plugins/plugin.sbt" \
        -Dsbt.server.autostart=false -Dsbt.server.forcestart=true \
        -Dsbt.log.noformat=true -Dsbt.supershell=false \
        -jar "$COURSIER_CACHE/${lock.launcher}" \
        ';clean;compile;test;updateClassifiers' </dev/null
    )
    if run_tasks missing > missing.log 2>&1; then
      cat missing.log >&2
      echo 'the missing locked JAR unexpectedly resolved' >&2
      exit 1
    fi
    cat missing.log
    grep -Eiq 'not found|Error downloading|FileNotFound|No such file|not available' missing.log
    grep -q 'scalatest-refspec' missing.log
    run_tasks restored 2>&1 | tee restored.log
    grep -q 'Total number of tests run: 38' restored.log
    grep -q 'All tests passed.' restored.log
    test ! -e "$TMPDIR/restored/home/.ivy2/cache"
    mkdir -p "$out"
    cp missing.log restored.log "$out/"
    cat > "$out/receipt.json" <<EOF
    ${builtins.toJSON {
      source = toString src;
      sampleRevision = "22b24334e8ea0274fc9a8fa7df8dd5ecd4f45cd5";
      sbtVersion = lock.sbtVersion;
      javaMajor = lock.javaVersion;
      plugin = toString pluginRepository;
      dependencies = toString build.dependencies;
      mode = "SBT_NIX_MODE=sandbox";
      commands = [
        "clean"
        "compile"
        "test"
        "updateClassifiers"
      ];
      tests = 38;
      missingJar = missing.path;
    }}
    EOF
  ''
