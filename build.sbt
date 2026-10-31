// SPDX-License-Identifier: MIT

ThisBuild / organization := "io.github.clhodapp"
ThisBuild / organizationName := "io.github.clhodapp"
ThisBuild / version := "0.1.0"
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/clhodapp/sbt-nix-backend"))

lazy val root = (project in file("."))
  .settings(
    name := "sbt-nix-backend",
    sbtPlugin := true,
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlint:-unused,_"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test,
    NixLibraryResources.settings,
    // NixBackendPlugin reads its coordinates from the jar manifest to
    // record which plugin version a lock was made with.
    Compile / packageBin / packageOptions += Package.ManifestAttributes(
      "Implementation-Title" -> name.value,
      "Implementation-Version" -> version.value,
      "Implementation-Vendor" -> organization.value
    )
  )
