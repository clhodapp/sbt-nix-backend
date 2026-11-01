# SPDX-License-Identifier: MIT
#
# Unit tests of the Nix library (nix/lib) on hand-written lockfiles. The
# fetcher is replaced by one that writes each URL into a file, so nothing
# downloads. The check fails with the list of failed tests.
{
  pkgs,
  # The flake's default package overlay, which adds buildSbtProject.
  overlay,
}:
let
  inherit (pkgs) lib;
  withOverlay = pkgs.extend overlay;
  inherit (withOverlay) buildSbtProject;

  lockJson =
    overrides:
    builtins.toFile "lock.json" (
      builtins.toJSON (
        {
          version = 2;
          sbtVersion = "1.11.7";
          javaVersion = "17.0.9";
          launcher = "https/repo.example/sbt-launch-1.11.7.jar";
          javaOptions = [ "-Xmx1g" ];
          plugin = null;
          projects = [
            {
              id = "core";
              organization = "example";
              name = "core_3";
              variants = [
                {
                  name = "scala3";
                  scalaVersion = "3.3.6";
                  artifacts = [
                    {
                      key = "core.jar";
                      name = "core";
                      type = "jar";
                      extension = "jar";
                      classifier = null;
                      configurations = [ "compile" ];
                      extraAttributes = { };
                    }
                    {
                      key = "core-sources.jar";
                      name = "core";
                      type = "src";
                      extension = "jar";
                      classifier = "sources";
                      configurations = [ "compile" ];
                      extraAttributes = { };
                    }
                  ];
                }
              ];
            }
          ];
          flakeRepositories = [ ];
          inputsHash = "sha256-inputs";
          definitionHash = "sha256-definition";
          artifacts = [
            {
              path = "https/repo.example/a/b/b-1.0.jar";
              url = "https://repo.example/a/b/b-1.0.jar";
              hash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
            }
            {
              path = "https/host%3A8080/c/.directory";
              url = "https://host:8080/c/";
              hash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
            }
          ];
        }
        // overrides
      )
    );

  fakeFetchurl = { name, url, ... }: pkgs.writeText name url;

  src = pkgs.runCommand "empty-build" { } "mkdir $out";

  build = buildWith buildSbtProject;

  buildWith =
    builder: args:
    builder (
      {
        inherit src;
        fetchurl = fakeFetchurl;
      }
      // args
    );

  pluginLock =
    via:
    lockJson {
      plugin = {
        organization = "io.github.clhodapp";
        name = "sbt-nix-backend";
        version = "0.1.0";
        inherit via;
      };
    };

  markerJdk = pkgs.runCommand "marker-jdk" { } "mkdir $out";
  markerRepository = pkgs.runCommand "marker-plugin-repository" { } "mkdir $out";

  # A package set with a consumer's package, written for callPackage, that
  # takes the builder by name, as a package in an overlay does.
  withConsumer = withOverlay.extend (
    final: prev: {
      example = final.callPackage (
        { buildSbtProject }:
        (buildWith buildSbtProject { lockFile = lockJson { }; }).packages.core
      ) { };
    }
  );

  # A package set whose sbt-nix-backend scope has another plugin
  # repository, as an overlay that overrides the scope makes it.
  withOtherPluginRepository = withOverlay.extend (
    final: prev: {
      sbt-nix-backend = prev.sbt-nix-backend.overrideScope (
        _: _: { plugin-repository = markerRepository; }
      );
    }
  );

  lockOf = overrides: import ../nix/lib/lock.nix { lockFile = lockJson overrides; };

  failsToEvaluate = value: !(builtins.tryEval (builtins.deepSeq value value)).success;

  tests = {
    testStoreNameKeepsValidNames = {
      expr = (lockOf { }).storeName "scala-library-2.13.16.jar";
      expected = "scala-library-2.13.16.jar";
    };
    testStoreNameReplacesInvalidCharacters = {
      expr = (lockOf { }).storeName "a b%c~d.jar";
      expected = "a_b_c_d.jar";
    };
    testStoreNameDropsLeadingDots = {
      expr = (lockOf { }).storeName ".directory";
      expected = "directory";
    };
    testStoreNameIsNeverEmpty = {
      expr = (lockOf { }).storeName "...";
      expected = "file";
    };
    testStoreNameIsAtMost200Characters = {
      expr = builtins.stringLength ((lockOf { }).storeName (lib.concatStrings (lib.replicate 300 "x")));
      expected = 200;
    };
    testUnsupportedLockVersionFails = {
      expr = failsToEvaluate (lockOf { version = 1; }).artifacts;
      expected = true;
    };
    testPackagesAreNamedByProjectId = {
      expr = builtins.attrNames (build { lockFile = lockJson { }; }).packages;
      expected = [ "core" ];
    };
    testPackageHasRepositoryOutput = {
      expr = (build { lockFile = lockJson { }; }).packages.core.outputs;
      expected = [
        "out"
        "repository"
      ];
    };
    testRepositoriesAreTheRepositoryOutputs = {
      expr =
        let
          b = build { lockFile = lockJson { }; };
        in
        b.repositories.core.outPath == b.packages.core.repository.outPath
        && b.sbtRepositories.core.outPath == b.repositories.core.outPath;
      expected = true;
    };
    testDefaultJdkFollowsTheLockedJavaMajorVersion = {
      expr = (build { lockFile = lockJson { }; }).jdk.outPath;
      expected = pkgs.jdk17_headless.outPath;
    };
    testJdkArgumentWins = {
      expr =
        (build {
          lockFile = lockJson { };
          jdk = pkgs.jdk21_headless;
        }).jdk.outPath;
      expected = pkgs.jdk21_headless.outPath;
    };
    testFlakeRepositoryOverrideAvoidsGetFlake = {
      # The locked reference does not exist; evaluating the package must
      # not fetch it when an override is given.
      expr =
        lib.hasPrefix "/nix/store/"
          (build {
            lockFile = lockJson {
              flakeRepositories = [
                {
                  name = "other";
                  ref = "github:nobody/nothing#x";
                  locked = "github:nobody/nothing/0000000000000000000000000000000000000000";
                  attribute = "x";
                }
              ];
            };
            flakeRepositories.other = src;
          }).packages.core.drvPath;
      expected = true;
    };
    # A lock made with the wrapper gets the global plugin and the variable,
    # which a build that installs the plugin in its own
    # project/project/plugins.sbt resolves it through.
    testGlobalPluginGoesInAsAGlobalPlugin = {
      expr =
        let
          phase = (buildWithPlugin "global-plugin").packages.core.buildPhase;
        in
        lib.hasInfix "-Dsbt.global.plugins=" phase
        && lib.hasInfix "export SBT_NIX_BACKEND_REPOSITORY=/nix/store/" phase;
      expected = true;
    };
    testEnvironmentPluginIsNamedByTheVariable = {
      expr =
        let
          phase = (buildWithPlugin "environment").packages.core.buildPhase;
        in
        lib.hasInfix "export SBT_NIX_BACKEND_REPOSITORY=/nix/store/" phase
        && !(lib.hasInfix "-Dsbt.global.plugins=" phase);
      expected = true;
    };
    testPluginRepositoryVersionIsChecked = {
      expr = lib.hasInfix "does not hold io.github.clhodapp:sbt-nix-backend:0.1.0" (buildWithPlugin "environment")
      .packages.core.buildPhase;
      expected = true;
    };
    testAPackageInAnOverlayTakesTheBuilderByCallPackage = {
      expr = withConsumer.example.drvPath == (build { lockFile = lockJson { }; }).packages.core.drvPath;
      expected = true;
    };
    testBuilderOverrideReplacesWhatItTakesFromThePackageSet = {
      expr =
        (buildWith (buildSbtProject.override { javaPackages.compiler.openjdk17.headless = markerJdk; }) {
          lockFile = lockJson { };
        }).jdk.outPath;
      expected = markerJdk.outPath;
    };
    # The lock names the artifacts of a package, so evaluating them builds
    # nothing: the empty source tree here builds no sbt project at all.
    testTheArtifactsOfAPackageAreKnownBeforeItIsBuilt = {
      expr =
        lib.mapAttrs (_: builtins.attrNames)
          (build { lockFile = lockJson { }; }).packages.core.artifacts;
      expected = {
        scala3 = [
          "core-sources.jar"
          "core.jar"
        ];
      };
    };
    testAnArtifactIsLinkedByVariantAndKey = {
      expr =
        let
          core = (build { lockFile = lockJson { }; }).packages.core;
        in
        core.artifacts.scala3."core-sources.jar" == "${core}/nix-support/artifacts/scala3/core-sources.jar";
      expected = true;
    };
    testTheVariantsKeepWhatTheLockRecords = {
      expr = map (v: {
        inherit (v) name scalaVersion;
        classifiers = map (a: a.classifier) v.artifacts;
        descriptor = lib.removePrefix "${(build { lockFile = lockJson { }; }).packages.core}/" v.descriptorFile;
      }) (build { lockFile = lockJson { }; }).packages.core.variants;
      expected = [
        {
          name = "scala3";
          scalaVersion = "3.3.6";
          classifiers = [
            null
            "sources"
          ];
          descriptor = "nix-support/artifacts/scala3/descriptor";
        }
      ];
    };
    # The derivation of a package is named after the project, without its
    # version, so a version bump renames nothing.
    testAPackageIsNamedAfterItsProject = {
      expr = (build { lockFile = lockJson { }; }).packages.core.name;
      expected = "core_3";
    };
    testTheScopePluginRepositoryIsTheDefault = {
      expr =
        lib.hasInfix "marker-plugin-repository"
          (buildWith withOtherPluginRepository.buildSbtProject { lockFile = pluginLock "environment"; })
          .packages.core.buildPhase;
      expected = true;
    };
    testWithoutTheScopeAPluginLockNeedsARepository = {
      expr =
        failsToEvaluate
          (buildWith (pkgs.callPackage ../nix/lib/build-sbt-project.nix { sbt-nix-backend = null; }) {
            lockFile = pluginLock "environment";
          }).packages.core.drvPath;
      expected = true;
    };
    testUnknownPluginRouteFails = {
      expr = failsToEvaluate (buildWithPlugin "telepathy").packages.core.drvPath;
      expected = true;
    };
  };

  buildWithPlugin =
    via:
    build {
      lockFile = pluginLock via;
      pluginRepository = src;
    };

  failures = lib.runTests tests;

  # The coursier cache of the hand-written lock: each artifact at its
  # cache path, holding (through the fake fetcher) its URL.
  dependencies = (build { lockFile = lockJson { }; }).dependencies;
in
pkgs.runCommand "nix-library-tests"
  {
    passAsFile = [ "failures" ];
    failures = builtins.toJSON failures;
  }
  ''
    if [ "$(cat "$failuresPath")" != "[]" ]; then
      echo "failed tests:" >&2
      cat "$failuresPath" >&2
      exit 1
    fi
    test -L ${dependencies}/https/repo.example/a/b/b-1.0.jar
    test "$(cat ${dependencies}/https/repo.example/a/b/b-1.0.jar)" = https://repo.example/a/b/b-1.0.jar
    test "$(cat '${dependencies}/https/host%3A8080/c/.directory')" = https://host:8080/c/
    touch $out
  ''
