# SPDX-License-Identifier: MIT
#
# The default package overlay. It adds the packages of pkgs/ under
# `pkgs.sbt-nix-backend` (`.plugin`, `.plugin-repository`, `.sbt`), and the
# builder `pkgs.buildSbtProject` (nix/lib/build-sbt-project.nix) beside
# them, where nixpkgs keeps its own builders, so a package written for
# callPackage takes it by name. A consumer that lists this flake in
# `projects` holds it as `sbt-nix-backend/default`, which its package sets
# apply by default.
{ closure-lib, ... }:
let
  packages = closure-lib.caisson.nixpkgs.mkPackagesOverlay ../../pkgs "sbt-nix-backend";
in
{
  overlay =
    final: prev:
    packages final prev
    // {
      buildSbtProject = final.callPackage ../../nix/lib/build-sbt-project.nix { };
    };
}
