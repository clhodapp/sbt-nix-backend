# SPDX-License-Identifier: MIT
#
# The default package overlay: the packages of pkgs/ under
# `pkgs.sbt-nix-backend` (`.plugin`, `.plugin-repository`, `.sbt`). A
# consumer that lists this flake in `projects` holds it as
# `sbt-nix-backend/default`, which its package sets apply by default.
{ closure-lib, ... }:
{
  overlay = closure-lib.caisson.nixpkgs.mkPackagesOverlay ../../pkgs "sbt-nix-backend";
}
