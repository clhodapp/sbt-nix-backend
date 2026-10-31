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
