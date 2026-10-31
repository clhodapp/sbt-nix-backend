# SPDX-License-Identifier: MIT
#
# The `sbt-nix-backend` library namespace. It holds nothing: the builder
# needs a package set, so it is the package `buildSbtProject` that the
# default package overlay adds.
{ ... }:
{

  overlay = final: prev: {
    sbt-nix-backend = prev.sbt-nix-backend or { };
  };

}
