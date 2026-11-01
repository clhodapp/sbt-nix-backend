// The matrix fixture on sbt 2, whose projectMatrix is built in: each
// matrix has a project per Scala version, each an ordinary project with a
// Nix package of its own.
ThisBuild / organization := "example"
ThisBuild / version := "6.0.0"

lazy val root = (project in file("."))
  .aggregate((core.projectRefs ++ app.projectRefs)*)
  .settings(publish / skip := true)

lazy val core = (projectMatrix in file("core"))
  .jvmPlatform(scalaVersions = Seq("3.8.4", "2.13.16"))

lazy val app = (projectMatrix in file("app"))
  .dependsOn(core)
  .settings(Compile / mainClass := Some("matrixexample.Main"))
  .jvmPlatform(scalaVersions = Seq("3.8.4", "2.13.16"))
