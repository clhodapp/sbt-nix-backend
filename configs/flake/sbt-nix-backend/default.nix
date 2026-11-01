# SPDX-License-Identifier: MIT
{ ... }:
{
  config,
  inputs,
  lib,
  ...
}:
{

  imports = [ inputs.flake-parts.flakeModules.partitions ];

  debug = false;
  caisson = {
    libOverlays.exported = libOverlays: {
      inherit (libOverlays) default;
    };

    # The packages are the `default` entry of this flake's package overlay
    # registry (pkg-overlays/): the package set applies it by default, and
    # the flake exports it as `pkgOverlays` and as the plain
    # `overlays.default`.
    nixpkgs = {
      packages.export.enabled = true;
    };
  };

  # `nix run github:nix-purifiers/sbt-purifier` starts sbt with the plugin
  # injected, which is how a build without the plugin in its
  # project/plugins.sbt locks and exports itself.
  #
  # `nix develop github:nix-purifiers/sbt-purifier` is the other way in: plain
  # nixpkgs sbt, with SBT_NIX_BACKEND_REPOSITORY naming the plugin
  # repository, for a build that installs the plugin in its
  # project/project/plugins.sbt (see the README).
  perSystem =
    { config, pkgs, ... }:
    {
      packages.default = config.packages.sbt;
      devShells.default = pkgs.mkShell {
        packages = [ pkgs.sbt ];
        SBT_NIX_BACKEND_REPOSITORY = config.packages.plugin-repository;
      };
    };

  partitionedAttrs.checks = "checks";
  partitionedAttrs.formatter = "formatter";

  partitions.formatter = {
    extraInputs = (lib.caisson.pins.flake-compat ../../../tests/dependencies).sources;
    module =
      { inputs, ... }:
      {
        imports = [ inputs.treefmt-nix.flakeModule ];
        perSystem.treefmt.programs.nixfmt.enable = true;
      };
  };

  partitions.checks = {
    extraInputs = (lib.caisson.pins.flake-compat ../../../tests/dependencies).sources;
    module =
      { inputs, self, ... }:
      {
        imports = [ inputs.treefmt-nix.flakeModule ];
        perSystem =
          { pkgs, system, ... }:
          {
            checks = import ../../../tests {
              inherit pkgs;
              overlay = self.overlays.default;
              inherit (self.packages.${system}) plugin plugin-repository;
            };
            treefmt.programs.nixfmt.enable = true;
          };
      };
  };

}
