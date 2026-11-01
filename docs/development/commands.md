# Commands

All from the repository root.

```zsh
# Unit tests, with the plugin compiled from source in the meta-build
sbt test

# Every check in the sandbox (each sbt build takes up to 2 GB)
nix flake check --max-jobs 2

# Interactive-mode tests (needs Nix; gives sbt no network)
tests/interactive.sh

# Renew the lockfiles of this build and the fixtures (needs the network)
tests/relock-fixtures.sh            # what is stale
tests/relock-fixtures.sh --relock   # all of them

# The plugin repository and the sbt wrapper
nix build '.#plugin-repository'
nix run '.#sbt' -- nixLock

nix fmt
```

After a change to the plugin's own build definition (`build.sbt`,
`project/`), run `sbt nixLock`, or the `plugin` check fails for want of a
locked file.

## CI and releases

The workflows under `.github/workflows` run on GitHub:

- `check.yml`, `check-pr.yml` and `cache-cleanup.yml` run
  `nix flake check` on every push to main and every pull request, with a
  build cache in the repository's Actions cache. A check of main pushes
  its builds to the clhodapp cachix cache. They are the pipeline of the
  workspace (its `docs/development/ci.md`).
- `release.yml` runs after each successful check of a push to main. It
  builds the artifacts with the last release's version and compares them
  with that release; when they differ, it releases the next revision of
  the series in `.github/release/series` (v0.1.1, v0.1.2, …): into the
  Maven repository that GitHub Pages serves from the gh-pages branch, and
  as a GitHub release. Builds from source keep the version in `build.sbt`.
- `update.yml` moves the repository to the newest sbt 1 and sbt 2
  releases every day (`.github/update/sbt.sh`), relocks, runs the
  checks, and pushes to main when they pass.

```zsh
# Build the release artifacts of a version, as release.yml does
.github/release/build.sh 0.1.9 /tmp/release

# Start a release run by hand (it releases only what changed)
gh workflow run release.yml

# See what the daily sbt update would move
.github/update/sbt.sh && git diff
```

A new series is an edit of `.github/release/series`; its first release
is revision 1.
