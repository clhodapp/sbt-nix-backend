// A multi-project build that runs with sbt-nix-backend injected as a
// global plugin (it is not in project/plugins.sbt): a Scala 3 library, a
// Java library, and an application that depends on both, uses a plugin
// from project/plugins.sbt, reads a resource a third-party plugin
// downloaded outside coursier, and has a test.
ThisBuild / organization := "example"
ThisBuild / version := "1.0.0"
ThisBuild / scalaVersion := "3.3.6"

lazy val root = (project in file("."))
  .aggregate(core, javalib, app)
  .settings(publish / skip := true)

lazy val core = project
  .settings(libraryDependencies += "com.lihaoyi" %% "upickle" % "4.1.0")

lazy val javalib = project
  .settings(
    crossPaths := false,
    autoScalaLibrary := false,
    libraryDependencies += "org.apache.commons" % "commons-text" % "1.12.0"
  )

lazy val app = project
  .dependsOn(core, javalib)
  // BuildInfoPlugin comes from project/plugins.sbt, so the meta-build's
  // resolution of it is locked too.
  .enablePlugins(LicensePlugin, BuildInfoPlugin)
  .settings(
    buildInfoPackage := "example",
    Compile / mainClass := Some("example.Main"),
    licenseUrl := "https://www.apache.org/licenses/LICENSE-2.0.txt",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )
