# SPDX-License-Identifier: MIT
#
# The default package overlay. It adds the packages of pkgs/ under
# `pkgs.sbt-purifier` (`.plugin`, `.plugin-repository`, `.sbt`). A consumer
# that lists this flake in `projects` holds it as `sbt-purifier/default`,
# which its package sets apply by default. The builder is in the library,
# as `lib.sbt-purifier.buildSbtProject`.
{ closure-lib, ... }:
{
  overlay = closure-lib.caisson.nixpkgs.mkPackagesOverlay ../../pkgs "sbt-purifier";
}
