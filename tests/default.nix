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
    flakeRepositories = {
      multi = multi.repositories.app;
      ivy = ivy.repositories.ivyjava;
    };
  };

  # Resolves `dependency` (an sbt ModuleID expression) from `repository`
  # alone and runs `check` with the runtime classpath in $cp.
  #
  # The resolver is sbt (coursier inside it), booted offline from the multi
  # fixture's locked cache. The throwaway build it runs reads `repository`
  # as a consumer does, with a Maven resolver and an Ivy resolver in the
  # standard Ivy layout, and has no managed Scala, so every file of the
  # module's runtime classpath must come from the repository: a file
  # repository is read in place, never through the cache, and the build is
  # offline.
  resolveFromRepository =
    name: repository: dependency: check:
    let
      consumerBuild = pkgs.writeTextDir "build.sbt" ''
        autoScalaLibrary := false
        managedScalaInstance := false
        crossPaths := false
        externalResolvers := Seq(
          MavenRepository("repository", "file://${repository}"),
          Resolver.file("repository-ivy", file("${repository}"))(Resolver.ivyStylePatterns)
        )
        libraryDependencies += ${dependency}
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
      ${check}
      touch $out
    '';

  # Resolves `module` (organization:name:version) from `repository` alone
  # and runs `mainClass`; the check passes when the output holds every
  # line of `expected`.
  runFromRepository =
    name: repository: module: mainClass: expected:
    let
      coordinates = lib.splitString ":" module;
    in
    resolveFromRepository name repository
      ''"${builtins.elemAt coordinates 0}" % "${builtins.elemAt coordinates 1}" % "${builtins.elemAt coordinates 2}"''
      ''
        java -cp "$cp" ${mainClass} > output
        cat output
        ${lib.concatMapStrings (line: ''
          grep -qxF ${lib.escapeShellArg line} output || { echo "missing output line: ${line}" >&2; exit 1; }
        '') expected}
      '';

  # A repository holds every one of `files`.
  repositoryLayouts =
    name: repository: files:
    pkgs.runCommand name { } ''
      cd ${repository}
      ls ${lib.escapeShellArgs files}
      touch $out
    '';

  # The `out` output of a package holds the project's own artifacts, under
  # `dir`, and nothing else; `files` are paths below `dir` it must hold.
  # The default is a POM and a jar of the Maven layout.
  onlyOwnArtifacts =
    name: package: dir:
    {
      files ? [
        "*/*.pom"
        "*/*.jar"
      ],
    }:
    pkgs.runCommand name { } ''
      cd ${package}
      others=$(find . -mindepth 2 -maxdepth 3 -type d -path './*/*' | grep -v '^./${dir}' || true)
      test -d ${dir} || { echo "${package} does not hold ${dir}" >&2; exit 1; }
      test -z "$others" || { echo "${package} holds more than ${dir}: $others" >&2; exit 1; }
      ls ${lib.concatMapStringsSep " " (f: "${dir}/${f}") files}
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
  multi-app-artifacts = onlyOwnArtifacts "multi-app-artifacts" multi.packages.app "example/app_3" { };
  multi-core-artifacts =
    onlyOwnArtifacts "multi-core-artifacts" multi.packages.core "example/core_3"
      { };
  multi-javalib-artifacts =
    onlyOwnArtifacts "multi-javalib-artifacts" multi.packages.javalib "example/javalib"
      { };

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
      [
        ''consumer: {"hello":"Nix World"}''
        "consumer: published Ivy-style"
      ];
  # The consumer's repository holds the module it took from the ivy
  # fixture's flake repository in the standard Ivy layout.
  consumer-repository-layouts =
    repositoryLayouts "consumer-repository-layouts" consumer.repositories.consumer
      [
        "example/ivyjava/4.0.0/ivys/ivy.xml"
        "example/ivyjava/4.0.0/jars/ivyjava.jar"
      ];

  # The same on sbt 2: the build locks and builds with the plugin's sbt 2
  # variant injected, its test suite runs, and the app's repository alone
  # resolves and runs the app.
  sbt2-app-artifacts = onlyOwnArtifacts "sbt2-app-artifacts" sbt2.packages.app "example/app_3" { };
  sbt2-app-repository =
    runFromRepository "sbt2-app-repository" sbt2.repositories.app "example:app_3:2.0.0" "example2.Main"
      [
        ''{"sbt":2}''
        "version 2.0.0"
        "interface xsbti.Logger"
      ];
  # sbt 2 publishes the lib Ivy-style, and the app's repository holds it
  # and sbt's interface module in the standard Ivy layout.
  sbt2-lib-artifacts = onlyOwnArtifacts "sbt2-lib-artifacts" sbt2.packages.lib "example/lib_3" {
    files = [
      "2.0.0/ivys/ivy.xml"
      "2.0.0/jars/lib_3.jar"
    ];
  };
  sbt2-app-repository-layouts =
    repositoryLayouts "sbt2-app-repository-layouts" sbt2.repositories.app
      [
        "example/app_3/2.0.0/app_3-2.0.0.pom"
        "example/lib_3/2.0.0/ivys/ivy.xml"
        "example/lib_3/2.0.0/jars/lib_3.jar"
        "org.scala-sbt/interface/0.13.18/ivys/ivy.xml"
        "org.scala-sbt/interface/0.13.18/jars/interface.jar"
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

  # A build that resolves with Ivy builds in the sandbox, and its tests run.
  # The app's repository alone resolves and runs the app: it holds the app
  # in Maven layout, and in the standard Ivy layout the Ivy-style lib and
  # sbt's interface module, which came from an Ivy-layout repository.
  ivy-app-repository =
    runFromRepository "ivy-app-repository" ivy.repositories.app "example:app_2.13:4.0.0"
      "ivyexample.Main"
      [
        ''{"resolvedWith":"ivy"}''
        "version 4.0.0"
        "interface xsbti.Logger"
      ];
  ivy-app-repository-layouts = repositoryLayouts "ivy-app-repository-layouts" ivy.repositories.app [
    "example/app_2.13/4.0.0/app_2.13-4.0.0.pom"
    "example/lib_2.13/4.0.0/ivys/ivy.xml"
    "example/lib_2.13/4.0.0/jars/lib_2.13.jar"
    "org.scala-sbt/interface/0.13.18/ivys/ivy.xml"
    "org.scala-sbt/interface/0.13.18/jars/interface.jar"
  ];

  # A Scala 2.12 project that resolves with Ivy builds in the sandbox: Ivy
  # resolves the compiler bridge sources from the locked downloads too.
  ivy-legacy-artifacts =
    onlyOwnArtifacts "ivy-legacy-artifacts" ivy.packages.legacy "example/legacy_2.12"
      { };

  # A project published Ivy-style has its output in the standard Ivy
  # layout.
  ivy-lib-artifacts = onlyOwnArtifacts "ivy-lib-artifacts" ivy.packages.lib "example/lib_2.13" {
    files = [
      "4.0.0/ivys/ivy.xml"
      "4.0.0/jars/lib_2.13.jar"
    ];
  };

  # An sbt plugin published Ivy-style keeps its name and carries its Scala
  # and sbt versions as extra attributes, which are part of its path; its
  # repository resolves it by them.
  ivy-plugin-artifacts =
    onlyOwnArtifacts "ivy-plugin-artifacts" ivy.packages.ivyplugin "example/ivyplugin"
      {
        files = [
          "scala_2.12/sbt_1.0/4.0.0/ivys/ivy.xml"
          "scala_2.12/sbt_1.0/4.0.0/jars/ivyplugin.jar"
        ];
      };
  ivy-plugin-repository =
    resolveFromRepository "ivy-plugin-repository" ivy.repositories.ivyplugin
      ''Defaults.sbtPluginExtra("example" % "ivyplugin" % "4.0.0", "1.0", "2.12")''
      ''
        tr ':' '\n' <<<"$cp" | grep -qx '${ivy.repositories.ivyplugin}/example/ivyplugin/scala_2.12/sbt_1.0/4.0.0/jars/ivyplugin.jar' ||
          { echo "the plugin jar is not on the classpath: $cp" >&2; exit 1; }
      '';

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
