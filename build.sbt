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
    // sbt 2's lm-coursier classes carry the @data annotation of
    // data-class, a library sbt compiles them with but does not depend on.
    // Scaladoc reads the annotation from their TASTy and fails without its
    // class, so the documentation classpath, and only that, gets the jar;
    // a library dependency would put it in the published POM. The version
    // is the one whose annotation constructor matches what the TASTy calls
    // (0.2.9 and later take nine arguments where sbt 2.0.12 passes none).
    Compile / doc / dependencyClasspath ++= {
      if (scalaBinaryVersion.value == "2.12") Nil
      else
        dependencyResolution.value
          .retrieve(
            ("io.github.alexarchambault" % "data-class_3" % "0.2.8").intransitive(),
            scalaModuleInfo.value,
            target.value / "doc-classpath",
            streams.value.log
          )
          .fold(w => throw w.resolveException, _.map(Attributed.blank))
    },
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
