# buildSbtProject: the sandboxed builds of an sbt build, from the lockfile
# the plugin's nixLock writes at the build root. The sbt-nix-backend flake
# exports this function as `lib.sbt-nix-backend.buildSbtProject`:
#
#   sbt-nix-backend.lib.sbt-nix-backend.buildSbtProject { inherit pkgs; src = ./.; }
#
# returns
#
#   dependencies          the coursier cache of every locked artifact (feature 1)
#   packages.<id>         the published artifacts of sbt project <id>, in
#                         Maven layout (feature 2); its `repository` output
#                         holds them together with their runtime
#                         dependencies (feature 4)
#   repositories.<id>     packages.<id>.repository
#   sbtRepositories       the same set, under the name a flake exports it as
#                         (`sbtRepositories.<system>.<id>`) for other builds
#                         to reference (feature 5)
{
  # The root of the sbt build.
  src,
  # The lockfile; the default is the one nixLock writes at the build root.
  lockFile ? src + "/sbt-nix-backend.lock.json",
  pkgs,
  # The JDK that runs sbt. The default is the headless JDK of the major
  # version the lock was made with.
  jdk ? null,
  # Replaces the built-in fetcher; see fetch.nix.
  fetchurl ? null,
  # The repository the sbt-nix-backend plugin is injected from, when the
  # lock was made with the plugin injected (`injectedPlugin` in the lock).
  # The default builds it from the source this library came with, which
  # is the plugin version of the same sbt-nix-backend revision.
  pluginRepository ? null,
  # Repository paths that replace flake repositories of the lock, by
  # resolver name. A flake that has the producing flake as an input can
  # pass its output here rather than letting the lock's reference be
  # fetched with builtins.getFlake.
  flakeRepositories ? { },
  # Runs the build's tests (`test`) in each package build.
  doCheck ? false,
  # Filters the build root before it is copied into the store, as the
  # `filter` of builtins.path. The default leaves out build outputs and
  # editor state.
  srcFilter ? null,
}:
let
  inherit (pkgs) lib;

  lock = import ./lock.nix { inherit lockFile; };
  fetch = import ./fetch.nix { inherit lock fetchurl; };
  system = pkgs.stdenv.hostPlatform.system;

  # Build outputs and editor state never take part in a build.
  defaultSrcFilter =
    path: type:
    let
      name = baseNameOf path;
    in
    !(
      (
        type == "directory" && (name == "target" || name == ".bsp" || name == ".metals" || name == ".bloop")
      )
      || name == ".git"
      || name == ".idea"
      || lib.hasPrefix "result" name
    );

  filteredSrc = builtins.path {
    path = src;
    name = "sbt-source";
    filter = if srcFilter != null then srcFilter else defaultSrcFilter;
  };

  javaMajor = builtins.head (lib.splitString "." lock.javaVersion);
  defaultJdk =
    pkgs."jdk${javaMajor}_headless" or pkgs."jdk${javaMajor}"
      or (throw "sbt-nix-backend: nixpkgs has no jdk${javaMajor}_headless or jdk${javaMajor} for the Java ${lock.javaVersion} the lock was made with; pass `jdk`.");
  jdk' = if jdk != null then jdk else defaultJdk;

  # Feature 1: every locked file at its coursier-cache path. The manifest
  # goes to the builder as a file, since a large build locks thousands of
  # artifacts and the list would not fit in an environment variable.
  dependencies =
    pkgs.runCommand "sbt-coursier-cache"
      {
        manifest = lib.concatMapStrings (a: "${a.path}\t${fetch a}\n") lock.artifacts;
        passAsFile = [ "manifest" ];
        preferLocalBuild = true;
        allowSubstitutes = false;
        passthru = { inherit lock; };
      }
      ''
        mkdir -p $out
        while IFS=$'\t' read -r rel target; do
          mkdir -p "$out/$(dirname "$rel")"
          ln -s "$target" "$out/$rel"
        done < "$manifestPath"
      '';

  # Feature 5: the store path of each flake repository of the lock.
  flakeRepositoryPaths = builtins.listToAttrs (
    map (r: {
      inherit (r) name;
      value =
        flakeRepositories.${r.name} or (
          let
            flake = builtins.getFlake r.locked;
          in
          flake.sbtRepositories.${system}.${r.attribute}
            or (throw "sbt-nix-backend: ${r.locked} has no output sbtRepositories.${system}.${r.attribute} (flake repository ${r.name})")
        );
    }) lock.flakeRepositories
  );

  injectedPlugin =
    if lock.injectedPlugin == null then
      null
    else
      let
        p = lock.injectedPlugin;
        # The plugin's own lock has no injected plugin, so this does not
        # recurse.
        repo =
          if pluginRepository != null then
            pluginRepository
          else
            (import ./plugin.nix { inherit pkgs; }).repository;
        orgPath = lib.replaceStrings [ "." ] [ "/" ] p.organization;
      in
      {
        file = pkgs.writeText "sbt-nix-backend-plugin.sbt" ''
          resolvers += MavenRepository("sbt-nix-backend-plugin", "file://${repo}")
          addSbtPlugin("${p.organization}" % "${p.name}" % "${p.version}")
        '';
        # An sbt plugin publishes as <name>_<scala>_<sbt> in Maven layout.
        # The check runs in the build rather than at evaluation, which
        # would have to build the repository first.
        check = ''
          shopt -s nullglob
          found=(${lib.escapeShellArg "${repo}/${orgPath}/${p.name}_"}*/${lib.escapeShellArg p.version})
          shopt -u nullglob
          if [ ''${#found[@]} -eq 0 ]; then
            echo "sbt-nix-backend: ${repo} does not hold ${p.organization}:${p.name}:${p.version}, the plugin version the lock was made with." >&2
            echo "Use the sbt-nix-backend flake revision that matches the lock, or re-lock." >&2
            exit 1
          fi
        '';
      };

  # The sbt invocation shared by every package build; nixLock runs sbt the
  # same way, so a lock made from it holds what this needs.
  runSbt = command: ''
    export HOME="$TMPDIR/home"
    export XDG_RUNTIME_DIR="$TMPDIR/run"
    export SBT_NIX_MODE=sandbox
    export SBT_NIX_OUTPUT="$TMPDIR/sbt-nix-output"
    export SBT_NIX_FLAKE_REPOSITORIES=${lib.escapeShellArg (builtins.toJSON flakeRepositoryPaths)}
    export COURSIER_CACHE="$TMPDIR/coursier-cache"
    export COURSIER_MODE=offline
    ${lib.optionalString (injectedPlugin != null) ''
      ${injectedPlugin.check}
      # The plugin is the build's only global plugin, which sbt loads below
      # every level of the build (project/project, project/, the build), so
      # the plugin governs the resolution of each. sbt writes target/
      # directories into a global plugins directory, so it is a copy.
      mkdir -p "$TMPDIR/global-plugins"
      cp ${injectedPlugin.file} "$TMPDIR/global-plugins/sbt-nix-backend.sbt"
    ''}
    mkdir -p "$HOME" "$XDG_RUNTIME_DIR" "$TMPDIR/java-tmp" "$COURSIER_CACHE"
    # Coursier and the launcher write lock and marker files next to what
    # they read, so the cache is a writable tree of links into the store.
    cp -rs --no-preserve=mode ${dependencies}/. "$COURSIER_CACHE"
    java ${lib.escapeShellArgs lock.javaOptions} \
      -Duser.home="$HOME" \
      -Djava.io.tmpdir="$TMPDIR/java-tmp" \
      -Dsbt.global.base="$HOME/.sbt" \
      -Dsbt.boot.directory="$HOME/.sbt/boot" \
      -Dsbt.ivy.home="$HOME/.ivy2" \
      -Dsbt.server.autostart=false \
      -Dsbt.server.forcestart=true \
      -Dsbt.log.noformat=true \
      -Dsbt.supershell=false \
      -jar "$COURSIER_CACHE/${lock.launcher}" \
      ${lib.optionalString (injectedPlugin != null) ''-Dsbt.global.plugins="$TMPDIR/global-plugins"''} \
      ${lib.escapeShellArg command} </dev/null
  '';

  mkPackage =
    project:
    pkgs.stdenvNoCC.mkDerivation {
      pname = project.name;
      inherit (project) version;
      src = filteredSrc;
      outputs = [
        "out"
        "repository"
      ];
      nativeBuildInputs = [ jdk' ];
      dontConfigure = true;
      # The outputs are jars and POMs; fixup has nothing to patch, and it
      # must not rewrite them.
      dontFixup = true;
      buildPhase = ''
        runHook preBuild
        ${runSbt ("nixSandboxBuild " + lib.optionalString doCheck "--test " + project.id)}
        runHook postBuild
      '';
      installPhase = ''
        runHook preInstall
        cp -r "$SBT_NIX_OUTPUT/${project.id}/artifacts" "$out"
        cp -r "$SBT_NIX_OUTPUT/${project.id}/repository" "$repository"
        runHook postInstall
      '';
      passthru = {
        inherit dependencies lock;
        sbtProject = project;
      };
      meta.description = "${project.organization}:${project.name}:${project.version}, built by sbt-nix-backend";
    };

  packages = builtins.mapAttrs (_: mkPackage) lock.projectsById;
  repositories = builtins.mapAttrs (_: p: p.repository) packages;
in
{
  inherit
    lock
    dependencies
    packages
    repositories
    ;
  sbtRepositories = repositories;
  jdk = jdk';
}
