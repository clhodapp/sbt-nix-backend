// The fixture of the conformance-update check (tests/update.sh). It
// depends on the module of a producer flake that tests/update.sh makes in
// a git repository of its own and names in SBT_PURIFIER_TEST_UPDATE_PRODUCER.
// The lockfile pins the producer's revision: `sbt-purifier lock` keeps it,
// and `sbt-purifier update` moves it to the newest commit.
ThisBuild / nixFlakeRepositories +=
  NixFlakeRepository("lib", sys.env("SBT_PURIFIER_TEST_UPDATE_PRODUCER") + "#lib")

lazy val app = (project in file("."))
  .settings(
    organization := "example",
    version := "1.0.0",
    crossPaths := false,
    autoScalaLibrary := false,
    libraryDependencies += nixFlakeModule("lib").value
  )
