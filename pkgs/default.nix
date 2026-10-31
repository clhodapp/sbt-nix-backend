# SPDX-License-Identifier: MIT
{ callPackage, ... }:
let
  plugin = callPackage ./plugin { };
in
{
  # The plugin's published artifacts, in Maven layout.
  inherit plugin;
  # The plugin with its runtime dependencies, as a Maven repository: what
  # the `sbt` wrapper and a sandboxed build of an injected lock resolve the
  # plugin from.
  plugin-repository = plugin.repository;
  # sbt with the plugin injected.
  sbt = callPackage ./sbt {
    pluginRepository = plugin.repository;
    pluginVersion = plugin.sbtProject.version;
  };
}
