# SPDX-License-Identifier: MIT
#
# The flake's checks. Every one builds in the sandbox; the only network
# access is the fixed-output fetches of locked artifacts.
{
  pkgs,
  # The flake's default package overlay, which adds buildSbtProject.
  overlay,
  plugin,
  plugin-repository,
}:
let
  inherit (pkgs) lib;

  # Every fixture builds the way a consumer's package does: through
  # buildSbtProject of a package set the overlay applies to.
  withOverlay = pkgs.extend overlay;
  inherit (withOverlay) buildSbtProject;

  # The fixtures were locked with the plugin injected (see
  # tests/relock-fixtures.sh), so their builds inject a plugin. multi takes
  # the builder's default, the plugin repository of the overlay's scope.
  multi = buildSbtProject {
    src = ./fixtures/multi;
    # app's munit suite runs in the build, and it reads the license the
    # third-party plugin fetched outside coursier.
    doCheck = true;
  };

  # An sbt 2 build, which injects the plugin's sbt 2 variant.
  sbt2 = buildSbtProject {
    src = ./fixtures/sbt2;
    pluginRepository = plugin-repository;
    doCheck = true;
  };

  # A build that installs the plugin in project/project/plugins.sbt and was
  # locked with plain sbt; its Nix build names the plugin repository in
  # SBT_NIX_BACKEND_REPOSITORY.
  metameta = buildSbtProject {
    src = ./fixtures/metameta;
    pluginRepository = plugin-repository;
    doCheck = true;
  };

  # A build that resolves with Ivy (useCoursier := false), in its
  # meta-build too; in the sandbox Ivy reads the locked downloads through
  # a file: mirror of their URLs.
  ivy = buildSbtProject {
    src = ./fixtures/ivy;
    pluginRepository = plugin-repository;
    doCheck = true;
  };

  # The consumer's lock pins a flake reference to the producer's
  # repository; the check passes the producer's output instead, as a flake
  # with the producer as an input would.
  consumer = buildSbtProject {
    src = ./fixtures/consumer;
    pluginRepository = plugin-repository;
    flakeRepositories.multi = multi.repositories.app;
  };

  # Resolves `module` from `repository` alone and runs `mainClass`; the
  # check passes when the output holds every line of `expected`.
  #
  # The resolver is sbt (coursier inside it), booted offline from the multi
  # fixture's locked cache. The throwaway build it runs has `repository` as
  # its only resolver and no managed Scala, so every file of the module's
  # runtime classpath must come from the repository: a file repository is
  # read in place, never through the cache, and the build is offline.
  runFromRepository =
    name: repository: module: mainClass: expected:
    let
      coordinates = lib.splitString ":" module;
      consumerBuild = pkgs.writeTextDir "build.sbt" ''
        autoScalaLibrary := false
        managedScalaInstance := false
        crossPaths := false
        externalResolvers := Seq(MavenRepository("repository", "file://${repository}"))
        libraryDependencies += "${builtins.elemAt coordinates 0}" % "${builtins.elemAt coordinates 1}" % "${builtins.elemAt coordinates 2}"
      '';
    in
    pkgs.runCommand name { nativeBuildInputs = [ multi.jdk ]; } ''
      export HOME="$TMPDIR/home" COURSIER_CACHE="$TMPDIR/cache" COURSIER_MODE=offline
      mkdir -p "$HOME" consumer/project
      cp -rs --no-preserve=mode ${multi.dependencies}/. "$COURSIER_CACHE"
      cp ${consumerBuild}/build.sbt consumer/
      echo sbt.version=${multi.lock.sbtVersion} > consumer/project/build.properties
      cd consumer
      java -Duser.home="$HOME" -Djava.io.tmpdir="$TMPDIR" -Dsbt.global.base="$HOME/.sbt" \
        -Dsbt.boot.directory="$HOME/.sbt/boot" -Dsbt.ivy.home="$HOME/.ivy2" \
        -Dsbt.server.autostart=false -Dsbt.server.forcestart=true -Dsbt.log.noformat=true \
        -jar "$COURSIER_CACHE/${multi.lock.launcher}" "export Runtime/managedClasspath" </dev/null > sbt-output
      cp=$(tail -n 1 sbt-output)
      case "$cp" in ${repository}/*) ;; *) cat sbt-output >&2; echo "no classpath from the repository" >&2; exit 1 ;; esac
      tr ':' '\n' <<<"$cp" | grep -v '^${repository}/' && { echo "classpath entries outside the repository" >&2; exit 1; }
      java -cp "$cp" ${mainClass} > output
      cat output
      ${lib.concatMapStrings (line: ''
        grep -qxF ${lib.escapeShellArg line} output || { echo "missing output line: ${line}" >&2; exit 1; }
      '') expected}
      touch $out
    '';

  # The `out` output of a package holds the project's own artifacts and
  # nothing else.
  onlyOwnArtifacts =
    name: package: dir:
    pkgs.runCommand name { } ''
      cd ${package}
      others=$(find . -mindepth 2 -maxdepth 3 -type d -path './*/*' | grep -v '^./${dir}' || true)
      test -d ${dir} || { echo "${package} does not hold ${dir}" >&2; exit 1; }
      test -z "$others" || { echo "${package} holds more than ${dir}: $others" >&2; exit 1; }
      ls ${dir}/*/*.pom ${dir}/*/*.jar
      touch $out
    '';

in
{
  # The plugin built by itself from its lock, with its unit tests run.
  inherit plugin;

  nix-library = import ./nix-library.nix { inherit pkgs overlay; };

  # Feature 1: the coursier cache of the multi fixture.
  multi-dependencies = multi.dependencies;

  # Feature 2: each package holds its own artifacts.
  multi-app-artifacts = onlyOwnArtifacts "multi-app-artifacts" multi.packages.app "example/app_3";
  multi-core-artifacts = onlyOwnArtifacts "multi-core-artifacts" multi.packages.core "example/core_3";
  multi-javalib-artifacts =
    onlyOwnArtifacts "multi-javalib-artifacts" multi.packages.javalib
      "example/javalib";

  # Feature 4: a repository is enough to resolve and run its module.
  multi-app-repository =
    runFromRepository "multi-app-repository" multi.repositories.app "example:app_3:1.0.0" "example.Main"
      [
        ''{"hello":"Nix World"}''
        "Apache License"
        "version 1.0.0"
      ];

  # Feature 5: a build that resolves another build's repository through a
  # flake repository, and whose own repository holds what it took from it.
  consumer-repository =
    runFromRepository "consumer-repository" consumer.repositories.consumer "example:consumer_3:1.0.0"
      "consumer.Main"
      [ ''consumer: {"hello":"Nix World"}'' ];

  # The same on sbt 2: the build locks and builds with the plugin's sbt 2
  # variant injected, its test suite runs, and the app's repository alone
  # resolves and runs the app.
  sbt2-app-artifacts = onlyOwnArtifacts "sbt2-app-artifacts" sbt2.packages.app "example/app_3";
  sbt2-app-repository =
    runFromRepository "sbt2-app-repository" sbt2.repositories.app "example:app_3:2.0.0" "example2.Main"
      [
        ''{"sbt":2}''
        "version 2.0.0"
      ];

  # A build that installs the plugin once, in its meta-most build, under
  # plain sbt: the plugin reaches the meta-build and the build (both use its
  # keys), the Nix build supplies its repository, and the app's repository
  # alone resolves and runs the app.
  metameta-app-repository =
    runFromRepository "metameta-app-repository" metameta.repositories.app "example:app_3:3.0.0"
      "metameta.Main"
      [
        ''{"installed":"project/project"}''
        "version 3.0.0"
      ];

  # A build that resolves with Ivy builds in the sandbox, its tests run,
  # and the app's repository alone resolves and runs the app.
  ivy-app-repository =
    runFromRepository "ivy-app-repository" ivy.repositories.app "example:app_2.13:4.0.0"
      "ivyexample.Main"
      [
        ''{"resolvedWith":"ivy"}''
        "version 4.0.0"
      ];

  # The plugin repository holds the plugin for sbt 1 and for sbt 2, each
  # with its API documentation.
  plugin-variants = pkgs.runCommand "plugin-variants" { nativeBuildInputs = [ multi.jdk ]; } ''
    for variant in sbt-nix-backend_2.12_1.0 sbt-nix-backend_sbt2_3; do
      ls ${plugin-repository}/io/github/clhodapp/$variant/*/*.jar
      pages=$(jar tf ${plugin-repository}/io/github/clhodapp/$variant/*/*-javadoc.jar | grep -c 'NixBackendPlugin.*\.html$' || true)
      if [ "$pages" -eq 0 ]; then
        echo "the $variant documentation jar has no page for NixBackendPlugin" >&2
        exit 1
      fi
    done
    touch $out
  '';
}
