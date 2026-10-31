// sbt-nix-backend is installed here, in the meta-most build, and only here.
// It passes itself on to the meta-build (project/) and to the build, so the
// .sbt files of both can use its keys, and it governs the resolution of
// both, project/plugins.sbt included.
//
// SBT_NIX_BACKEND_REPOSITORY names the repository it comes from: the
// sbt-nix-backend flake's dev shell sets it, and so does a Nix build of a
// lockfile made with it.
resolvers += MavenRepository(
  "sbt-nix-backend",
  new File(sys.env.getOrElse("SBT_NIX_BACKEND_REPOSITORY", sys.error("SBT_NIX_BACKEND_REPOSITORY is not set"))).toURI.toString
)
addSbtPlugin("io.github.clhodapp" % "sbt-nix-backend" % "0.1.0")
