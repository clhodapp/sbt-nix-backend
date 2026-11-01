# SPDX-License-Identifier: MIT
#
# The default package overlay. It adds the packages of pkgs/ under
# `pkgs.sbt-purifier` (`.plugin`, `.plugin-repository`, `.sbt`,
# `.sbt-purifier`), and the builder `pkgs.buildSbtProject`
# (nix/lib/build-sbt-project.nix) beside them, where nixpkgs keeps its own
# builders, so a package written for callPackage takes it by name. A
# consumer that lists this flake in `projects` holds it as
# `sbt-purifier/default`, which its package sets apply by default.
{ closure-lib, ... }:
let
  packages = closure-lib.caisson.nixpkgs.mkPackagesOverlay ../../pkgs "sbt-purifier";
in
{
  overlay =
    final: prev:
    packages final prev
    // {
      buildSbtProject = final.callPackage ../../nix/lib/build-sbt-project.nix {
        inherit (closure-lib) purifier;
        inherit (closure-lib.sbt-purifier) readLock;
      };
    };
}
