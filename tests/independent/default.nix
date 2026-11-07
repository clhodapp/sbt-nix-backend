# SPDX-License-Identifier: MIT
# The real test-interface project, including its forked ScalaTest suite.
# Only the sbt version is adapted; its sources, tests and plugins are intact.
{
  pkgs,
  buildSbtProject,
  pluginRepository,
}:
let
  upstream = pkgs.fetchzip {
    url = "https://github.com/sbt/test-interface/archive/22b24334e8ea0274fc9a8fa7df8dd5ecd4f45cd5.tar.gz";
    hash = "sha256-cSk+xOeYYzTsTDkSYcYJbjTWyUue1oJsrBqwxyHHCag=";
  };
  src = pkgs.runCommand "test-interface-sbt-1.13.1-source" { } ''
    cp -r ${upstream} $out
    chmod -R u+w $out
    echo 'sbt.version=1.13.1' > $out/project/build.properties
  '';
  build = buildSbtProject {
    inherit src;
    lockFile = ./test-interface-sbt1.lock.json;
    doCheck = true;
  };
in
{
  inherit (build) tests;
  offline = import ./offline-tasks.nix {
    inherit
      pkgs
      build
      src
      pluginRepository
      ;
  };
}
