// A build that depends on other builds' sbt-nix-backend repositories
// through flake references (feature 5). A reference names the producer
// flake and the attribute under its `sbtRepositories.<system>`; the lock
// pins its revision. nixFlakeModule reads the coordinates the producer
// published, so this build names the module rather than its version.
// The producer is tests/producers in this repository: the repository of
// multi's app is in Maven layout, and the repository of the ivy fixture's
// ivyjava holds a module published Ivy-style. tests/relock-fixtures.sh
// names the revision it locks in SBT_NIX_BACKEND_TEST_PRODUCER.
ThisBuild / scalaVersion := "3.3.6"

val producer = sys.env.getOrElse("SBT_NIX_BACKEND_TEST_PRODUCER", "github:nix-purifiers/sbt-purifier?dir=tests/producers")

ThisBuild / nixFlakeRepositories ++= Seq(
  NixFlakeRepository("multi", producer + "#multi-app"),
  NixFlakeRepository("ivy", producer + "#ivy-ivyjava")
)

lazy val consumer = (project in file("."))
  .settings(
    organization := "example",
    version := "1.0.0",
    libraryDependencies += nixFlakeModule("multi").value,
    libraryDependencies += nixFlakeModule("ivy").value,
    Compile / mainClass := Some("consumer.Main")
  )
