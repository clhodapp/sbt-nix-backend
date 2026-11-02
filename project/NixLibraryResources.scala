// SPDX-License-Identifier: MIT
import sbt._
import sbt.Keys._

/** Puts the Nix library (nix/lib in this repository)
  * into the plugin jar at sbtnixbackend/nix/, where NixLibrary reads them,
  * so the one copy of the library is nix/lib. project/build.sbt does the
  * same for the meta-build, which compiles the plugin from source.
  */
object NixLibraryResources {
  def settings: Seq[Setting[_]] = Seq(
    Compile / resourceGenerators += Def.task {
      val out = (Compile / resourceManaged).value / "sbtnixbackend" / "nix"
      val root = (ThisBuild / baseDirectory).value
      // The flake.lock goes beside the library, for purifier-source.nix.
      ((root / "nix" / "lib" * ("*.nix" | "*.sbt")).get :+ (root / "flake.lock")).map { source =>
        val target = out / source.getName
        IO.copyFile(source, target)
        target
      }
    }.taskValue
  )
}
