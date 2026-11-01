// A build that depends on other builds' sbt-nix-backend repositories
// through flake references (feature 5). A reference names the producer
// flake and the attribute under its `sbtRepositories.<system>`; the lock
// pins its revision. nixFlakeModule reads the coordinates the producer
// published, so this build names the module rather than its version.
// The repository of multi's app is in Maven layout; the repository of the
// ivy fixture's ivyjava holds a module published Ivy-style.
ThisBuild / scalaVersion := "3.3.6"

ThisBuild / nixFlakeRepositories ++= Seq(
  NixFlakeRepository("multi", sys.env.getOrElse("SBT_NIX_BACKEND_TEST_PRODUCER", "path:../multi") + "#app"),
  NixFlakeRepository("ivy", sys.env.getOrElse("SBT_NIX_BACKEND_TEST_IVY_PRODUCER", "path:../ivy") + "#ivyjava")
)

lazy val consumer = (project in file("."))
  .settings(
    organization := "example",
    version := "1.0.0",
    libraryDependencies += nixFlakeModule("multi").value,
    libraryDependencies += nixFlakeModule("ivy").value,
    Compile / mainClass := Some("consumer.Main")
  )
