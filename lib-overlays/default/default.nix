# SPDX-License-Identifier: MIT
#
# The `sbt-nix-backend` library namespace. `buildSbtProject` turns an sbt
# build and the lockfile the plugin wrote into its sandboxed builds; see
# nix/lib/default.nix for its arguments and what it returns.
{ ... }:
{

  overlay = final: prev: {
    sbt-nix-backend = (prev.sbt-nix-backend or { }) // {
      buildSbtProject = import ../../nix/lib;
    };
  };

}
