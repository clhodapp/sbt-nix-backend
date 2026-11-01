// A build whose projects come from sbt-projectmatrix: each matrix has a
// project per Scala version (core and core2_13, app and app2_13), so each
// Scala version is an ordinary project with a Nix package of its own.
ThisBuild / organization := "example"
ThisBuild / version := "5.0.0"

lazy val root = (project in file("."))
  .aggregate((core.projectRefs ++ app.projectRefs): _*)
  .settings(publish / skip := true)

lazy val core = (projectMatrix in file("core"))
  .jvmPlatform(scalaVersions = Seq("3.3.6", "2.13.16"))

lazy val app = (projectMatrix in file("app"))
  .dependsOn(core)
  .settings(Compile / mainClass := Some("matrixexample.Main"))
  .jvmPlatform(scalaVersions = Seq("3.3.6", "2.13.16"))
