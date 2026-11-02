// SPDX-License-Identifier: MIT
// No explicit Project: sbt 0.13 derives its id from the directory name.
name := "legacy-example"

organization := "example.legacy"

version := "1.0.0"

scalaVersion := "2.10.7"

libraryDependencies ++= Seq(
  "org.apache.commons" % "commons-lang3" % "3.17.0",
  "com.novocode" % "junit-interface" % "0.11" % Test,
  "junit" % "junit" % "4.13.2" % Test
)

// Exercise the legacy publisher and an external Ivy settings file too.
lazy val ivy = project.settings(
  scalaVersion := "2.10.7",
  organization := "example.legacy",
  version := "1.0.0",
  publishMavenStyle := false,
  externalIvySettings(baseDirectory(_ / "ivysettings.xml")),
  libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.17.0"
)
