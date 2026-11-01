# SPDX-License-Identifier: MIT
#
# The packages of the `sbt-purifier` scope. `lib` is the composed library,
# with `lib.sbt-purifier`.
{ callPackage, lib, ... }:
let
  # The plugin's published artifacts, in Maven layout (nix/lib/plugin.nix).
  plugin = callPackage ({ pkgs }: lib.sbt-purifier.plugin pkgs) { };
in
{
  inherit plugin;
  # The plugin with its runtime dependencies, as a Maven repository: what
  # the `sbt` wrapper and a sandboxed build of an injected lock resolve the
  # plugin from.
  plugin-repository = plugin.repository;
  # sbt with the plugin injected.
  sbt = callPackage ./sbt {
    pluginRepository = plugin.repository;
    # The version of builds from source, which build.sbt reads too.
    pluginVersion = builtins.replaceStrings [ "\n" ] [ "" ] (builtins.readFile ../version.txt);
  };
}
// {
  # The commands of the purifier contract: lock, update, prefetch, publish.
  sbt-purifier = callPackage ./sbt-purifier { };
}
