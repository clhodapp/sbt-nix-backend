// An sbt 2 build that runs with sbt-nix-backend injected as a global
// plugin: a Scala 3 library and an application that depends on it, uses a
// plugin from project/plugins.sbt, and has a test.
ThisBuild / organization := "example"
ThisBuild / version := "2.0.0"
ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .aggregate(lib, app)
  .settings(publish / skip := true)

lazy val lib = project
  .settings(libraryDependencies += "com.lihaoyi" %% "upickle" % "4.1.0")

lazy val app = project
  .dependsOn(lib)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoPackage := "example2",
    Compile / mainClass := Some("example2.Main"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )
