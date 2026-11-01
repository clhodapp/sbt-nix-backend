# SPDX-License-Identifier: MIT
#
# The producer flake of tests/fixtures/consumer, which depends on these
# repositories through nixFlakeRepositories. It builds the multi and ivy
# fixtures with the sbt-nix-backend of the same revision, which it reaches
# as the root of the repository it is in, and exports their repositories
# as sbtRepositories, the attribute nixFlakeRepositories reads.
{
  inputs.sbt-nix-backend.url = "path:../..";

  outputs =
    { sbt-nix-backend, ... }:
    let
      inherit (sbt-nix-backend.inputs.nixpkgs) lib;
    in
    {
      sbtRepositories = lib.genAttrs lib.systems.flakeExposed (
        system:
        let
          pkgs =
            sbt-nix-backend.inputs.nixpkgs.legacyPackages.${system}.extend
              sbt-nix-backend.overlays.default;
          multi = pkgs.buildSbtProject { src = ../fixtures/multi; };
          ivy = pkgs.buildSbtProject { src = ../fixtures/ivy; };
        in
        {
          multi-app = multi.repositories.app;
          ivy-ivyjava = ivy.repositories.ivyjava;
        }
      );
    };
}
