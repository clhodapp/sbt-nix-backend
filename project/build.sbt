// SPDX-License-Identifier: MIT
//
// The meta-build compiles the plugin from this repository's own sources,
// so the plugin's build runs with the plugin: `nixLock` locks it and the
// flake builds it in the sandbox without a published copy. The resource
// generator is the one in NixLibraryResources.scala, which the meta-build
// compiles and so cannot use itself.
Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala"
Compile / resourceGenerators += Def.task {
  val out = (Compile / resourceManaged).value / "sbtnixbackend" / "nix"
  (baseDirectory.value.getParentFile / "nix" / "lib" * "*.nix").get.map(_.getName).map { name =>
    val target = out / name
    IO.copyFile(baseDirectory.value.getParentFile / "nix" / "lib" / name, target)
    target
  }
}.taskValue
