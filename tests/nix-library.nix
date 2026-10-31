# SPDX-License-Identifier: MIT
#
# Unit tests of the Nix library (nix/lib) on hand-written lockfiles. The
# fetcher is replaced by one that writes each URL into a file, so nothing
# downloads. The check fails with the list of failed tests.
{ pkgs }:
let
  inherit (pkgs) lib;
  buildSbtProject = import ../nix/lib;

  lockJson =
    overrides:
    builtins.toFile "lock.json" (
      builtins.toJSON (
        {
          version = 1;
          sbtVersion = "1.11.7";
          javaVersion = "17.0.9";
          launcher = "https/repo.example/sbt-launch-1.11.7.jar";
          javaOptions = [ "-Xmx1g" ];
          injectedPlugin = null;
          projects = [
            {
              id = "core";
              organization = "example";
              name = "core_3";
              version = "1.0.0";
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

  build =
    args:
    buildSbtProject (
      {
        inherit pkgs src;
        fetchurl = fakeFetchurl;
      }
      // args
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
      expr = failsToEvaluate (lockOf { version = 2; }).artifacts;
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
    testInjectedPluginWithRepositoryEvaluates = {
      expr =
        lib.hasPrefix "/nix/store/"
          (build {
            lockFile = lockJson {
              injectedPlugin = {
                organization = "io.github.clhodapp";
                name = "sbt-nix-backend";
                version = "0.1.0";
              };
            };
            pluginRepository = src;
          }).packages.core.drvPath;
      expected = true;
    };
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
