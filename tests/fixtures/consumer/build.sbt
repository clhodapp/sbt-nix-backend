// A build that depends on another build's sbt-nix-backend repository
// through a flake reference (feature 5). The reference names the producer
// flake and the attribute under its `sbtRepositories.<system>`; the lock
// pins its revision. nixFlakeModule reads the coordinates the producer
// published, so this build names the module rather than its version.
ThisBuild / scalaVersion := "3.3.6"

ThisBuild / nixFlakeRepositories += NixFlakeRepository(
  "multi",
  sys.env.getOrElse("SBT_NIX_BACKEND_TEST_PRODUCER", "path:../multi") + "#app"
)

lazy val consumer = (project in file("."))
  .settings(
    organization := "example",
    version := "1.0.0",
    libraryDependencies += nixFlakeModule("multi").value,
    Compile / mainClass := Some("consumer.Main")
  )
