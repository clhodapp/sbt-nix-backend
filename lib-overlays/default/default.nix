# SPDX-License-Identifier: MIT
#
# The `sbt-purifier` library namespace, the interface of the purifier
# contract (CONTRACT.md of the purifier library):
#
#   readLock { src, lockFile ? ... }        the lock in memory
#   buildSbtProject { pkgs, src, ... }      the builder (nix/lib/build-sbt-project.nix)
#   plugin pkgs                             the plugin, built for `pkgs`
#
# It takes `lib.purifier` from the purifier flake, which this flake lists
# in its projects. nix/lib/library.nix composes the same namespace for an
# evaluation outside the flake.
{ ... }:
{

  overlay = final: prev: {
    sbt-purifier = {
      readLock = import ../../nix/lib/read-lock.nix;
      buildSbtProject = import ../../nix/lib/build-sbt-project.nix { lib = final; };
      plugin = import ../../nix/lib/plugin.nix { lib = final; };
    };
  };

}
