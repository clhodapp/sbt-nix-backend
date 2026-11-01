# SPDX-License-Identifier: MIT
#
# The producer flake of tests/fixtures/consumer, which depends on these
# repositories through nixFlakeRepositories. It builds the multi and ivy
# fixtures with the sbt-purifier of the same revision, which it reaches as
# the root of the repository it is in, and exports their repositories as
# sbtRepositories, the attribute nixFlakeRepositories reads.
{
  inputs.sbt-purifier.url = "path:../..";

  outputs =
    { sbt-purifier, ... }:
    let
      inherit (sbt-purifier.inputs.nixpkgs) lib;
    in
    {
      sbtRepositories = lib.genAttrs lib.systems.flakeExposed (
        system:
        let
          pkgs = sbt-purifier.inputs.nixpkgs.legacyPackages.${system}.extend sbt-purifier.overlays.default;
          build = src: pkgs.sbt-purifier.buildSbtProject { inherit src; };
          multi = build ../fixtures/multi;
          ivy = build ../fixtures/ivy;
        in
        {
          multi-app = multi.sbtRepositories.app;
          ivy-ivyjava = ivy.sbtRepositories.ivyjava;
        }
      );
    };
}
