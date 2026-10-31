// A build that installs sbt-nix-backend in its meta-most build
// (project/project/plugins.sbt) and runs under a standard sbt, without the
// sbt-nix-backend wrapper. The build and its meta-build both use the
// plugin's keys, which they have because the plugin passes itself on.
ThisBuild / organization := "example"
ThisBuild / version := "3.0.0"
ThisBuild / scalaVersion := "3.3.6"
ThisBuild / nixSbtJavaOptions := Seq("-Xss4m", "-Xmx1536m")

lazy val root = (project in file("."))
  .aggregate(app)
  .settings(publish / skip := true)

lazy val app = project
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoPackage := "metameta",
    Compile / mainClass := Some("metameta.Main"),
    libraryDependencies += "com.lihaoyi" %% "upickle" % "4.1.0",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )
