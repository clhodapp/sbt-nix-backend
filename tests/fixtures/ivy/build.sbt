// A build that resolves its dependencies with Apache Ivy rather than
// coursier, as its meta-build (project/build.sbt) does too.
ThisBuild / useCoursier := false
ThisBuild / organization := "example"
ThisBuild / version := "4.0.0"
ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file("."))
  .aggregate(lib, app)
  .settings(publish / skip := true)

lazy val lib = project
  .settings(libraryDependencies += "com.lihaoyi" %% "upickle" % "4.1.0")

lazy val app = project
  .dependsOn(lib)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoPackage := "ivyexample",
    Compile / mainClass := Some("ivyexample.Main"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )
