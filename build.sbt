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
    // The plugin builds for sbt 1 (Scala 2.12) and sbt 2 (Scala 3).
    scalaVersion := "2.12.20",
    crossScalaVersions := Seq("2.12.20", "3.8.4"),
    pluginCrossBuild / sbtVersion := (scalaBinaryVersion.value match {
      case "2.12" => "1.11.7"
      case _      => "2.0.12"
    }),
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked") ++
      (if (scalaBinaryVersion.value == "2.12") Seq("-Xlint:-unused,_") else Nil),
    // Scaladoc 3.8.4 cannot read lm-coursier's TASTy (its @data classes), so
    // the sbt 2 variant publishes an empty documentation jar.
    Compile / packageDoc / mappings := (if (scalaBinaryVersion.value == "2.12") (Compile / packageDoc / mappings).value else Nil),
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
