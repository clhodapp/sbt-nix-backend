// A build that resolves its dependencies with Apache Ivy rather than
// coursier, as its meta-build (project/build.sbt) does too, and that
// publishes Ivy-style.
ThisBuild / useCoursier := false
ThisBuild / organization := "example"
ThisBuild / version := "4.0.0"
ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file("."))
  .aggregate(lib, app, ivyplugin, legacy, ivyjava)
  .settings(publish / skip := true)

// A Java library published Ivy-style; the consumer fixture depends on its
// repository through a flake repository.
lazy val ivyjava = project
  .settings(
    crossPaths := false,
    autoScalaLibrary := false,
    publishMavenStyle := false
  )

// Scala 2.12, whose compiler bridge sbt compiles from sources that Ivy
// resolves.
lazy val legacy = project
  .settings(scalaVersion := "2.12.20")

// Published Ivy-style.
lazy val lib = project
  .settings(
    publishMavenStyle := false,
    libraryDependencies += "com.lihaoyi" %% "upickle" % "4.1.0"
  )

// Published Maven-style, with a dependency on the Ivy-style lib and one on
// a module only an Ivy-layout repository has (sbt's interface 0.13.18 is
// not on Maven Central).
lazy val app = project
  .dependsOn(lib)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoPackage := "ivyexample",
    Compile / mainClass := Some("ivyexample.Main"),
    resolvers += Resolver.typesafeIvyRepo("releases"),
    libraryDependencies += "org.scala-sbt" % "interface" % "0.13.18",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )

// An sbt plugin published Ivy-style, at
// example/ivyplugin/scala_2.12/sbt_1.0/4.0.0. It resolves with coursier,
// as most sbt 1 builds do, unlike the rest of this build.
lazy val ivyplugin = project
  .enablePlugins(SbtPlugin)
  .settings(
    scalaVersion := "2.12.20",
    useCoursier := true,
    publishMavenStyle := false
  )
