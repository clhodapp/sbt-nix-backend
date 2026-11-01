# buildSbtProject: the sandboxed builds of an sbt build, from the lockfile
# the plugin's nixLock writes at the build root.
#
# The file is a package for callPackage: the first argument set is what it
# takes from the package set, and the result is the builder. The
# sbt-nix-backend overlay (`overlays.default`) adds it to the package set
# as `buildSbtProject`, so a package written for callPackage takes it like
# any other dependency:
#
#   { buildSbtProject }: (buildSbtProject { src = ./.; }).packages.app
#
# and `buildSbtProject.override` replaces what it takes from the set. The
# builder returns
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
  lib,
  stdenvNoCC,
  runCommand,
  writeText,
  callPackage,
  # The JDKs a build can run on; the default JDK of a build is the headless
  # one of the major version its lock was made with.
  javaPackages,
  # The sbt-nix-backend package scope, whose `plugin-repository` is the
  # default plugin repository. It is null where the overlay is not applied;
  # a lock that needs the plugin then needs `pluginRepository`.
  sbt-nix-backend ? null,
}:
{
  # The root of the sbt build.
  src,
  # The lockfile; the default is the one nixLock writes at the build root.
  lockFile ? src + "/sbt-nix-backend.lock.json",
  # The JDK that runs sbt.
  jdk ? null,
  # Replaces the built-in fetcher; see fetch.nix.
  fetchurl ? null,
  # The repository the build takes the sbt-nix-backend plugin from, when the
  # lock says the Nix build supplies it (`plugin` in the lock): as a global
  # plugin, or through SBT_NIX_BACKEND_REPOSITORY for a build that installs
  # the plugin in its project/project/plugins.sbt. The default is the
  # scope's `plugin-repository`: the plugin built from the sbt-nix-backend
  # revision whose overlay added this builder.
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
  lock = import ./lock.nix { inherit lockFile; };
  system = stdenvNoCC.hostPlatform.system;

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
    javaPackages.compiler."openjdk${javaMajor}".headless
      or (throw "sbt-nix-backend: javaPackages has no openjdk${javaMajor} for the Java ${lock.javaVersion} the lock was made with; pass `jdk`.");
  jdk' = if jdk != null then jdk else defaultJdk;

  dependencies = callPackage ./dependencies.nix { inherit lockFile fetchurl; };

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

  plugin =
    if lock.plugin == null then
      null
    else
      let
        p = lock.plugin;
        repo =
          if pluginRepository != null then
            pluginRepository
          else if sbt-nix-backend != null then
            sbt-nix-backend.plugin-repository
          else
            throw "sbt-nix-backend: the lock takes the plugin (${p.organization}:${p.name}:${p.version}) from a repository the Nix build supplies; apply the sbt-nix-backend overlay or pass `pluginRepository`.";
        orgPath = lib.replaceStrings [ "." ] [ "/" ] p.organization;
      in
      assert
        lib.elem p.via [
          "global-plugin"
          "environment"
        ]
        || throw "sbt-nix-backend: the lock reaches the plugin via '${p.via}', which this library does not know";
      {
        inherit (p) via;
        inherit repo;
        file = writeText "sbt-nix-backend-plugin.sbt" ''
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
    export XDG_CACHE_HOME="$HOME/.cache" XDG_CONFIG_HOME="$HOME/.config"
    export XDG_DATA_HOME="$HOME/.local/share" XDG_STATE_HOME="$HOME/.local/state"
    export SBT_NIX_MODE=sandbox
    export SBT_NIX_OUTPUT="$TMPDIR/sbt-nix-output"
    export SBT_NIX_FLAKE_REPOSITORIES=${lib.escapeShellArg (builtins.toJSON flakeRepositoryPaths)}
    export COURSIER_CACHE="$TMPDIR/coursier-cache"
    export COURSIER_MODE=offline
    ${lib.optionalString (plugin != null) plugin.check}
    ${lib.optionalString (plugin != null && plugin.via == "global-plugin") ''
      # The plugin is the build's only global plugin, which sbt loads below
      # every level of the build (project/project, project/, the build), so
      # the plugin governs the resolution of each. sbt writes target/
      # directories into a global plugins directory, so it is a copy.
      mkdir -p "$TMPDIR/global-plugins"
      cp ${plugin.file} "$TMPDIR/global-plugins/sbt-nix-backend.sbt"
    ''}
    ${lib.optionalString (plugin != null) ''
      # A build that installs the plugin in its project/project/plugins.sbt
      # resolves it from the repository this variable names; the sbt
      # wrapper sets it too, so a lock made with the wrapper and such a
      # build needs it as well as the global plugin.
      export SBT_NIX_BACKEND_REPOSITORY=${plugin.repo}
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
      ${
        lib.optionalString (
          plugin != null && plugin.via == "global-plugin"
        ) ''-Dsbt.global.plugins="$TMPDIR/global-plugins"''
      } \
      -jar "$COURSIER_CACHE/${lock.launcher}" \
      ${lib.escapeShellArg command} </dev/null
  '';

  # The build links each artifact of a Scala version, and its module
  # descriptor, at names without the version of the project:
  # <linkDirectory>/<Scala version>/<key> and …/descriptor.
  linkDirectory = "nix-support/artifacts";
  artifactFile =
    package: scalaVersion: key:
    "${package}/${linkDirectory}/${scalaVersion}/${key}";
  linksOf =
    v: [ "${v.scalaVersion}/descriptor" ] ++ map (a: "${v.scalaVersion}/${a.key}") v.artifacts;

  mkPackage =
    project:
    stdenvNoCC.mkDerivation (finalAttrs: {
      inherit (project) name;
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
        # The lock names the artifacts; the manifest it took them from
        # stays out of the output.
        rm -f "$out/sbt-nix-artifacts.json"
        for link in ${lib.escapeShellArgs (lib.concatMap linksOf project.variants)}; do
          if [ ! -f "$out/${linkDirectory}/$link" ]; then
            echo "sbt-nix-backend: the lock names $link, which this build of ${project.id} did not publish; run nixLock" >&2
            exit 1
          fi
        done
        runHook postInstall
      '';
      passthru = {
        inherit dependencies lock;
        sbtProject = project;
        # For each Scala version the lock builds the project for, its
        # artifacts as the lock records them (key, name, type, extension,
        # classifier, configurations, extra attributes), each with `file`,
        # its file in `out`, and `descriptorFile`, the file of its POM or
        # ivy.xml. They are known from the lock alone, before the package
        # is built.
        variants = map (
          v:
          v
          // {
            artifacts = map (
              a: a // { file = artifactFile finalAttrs.finalPackage v.scalaVersion a.key; }
            ) v.artifacts;
            descriptorFile = artifactFile finalAttrs.finalPackage v.scalaVersion "descriptor";
          }
        ) project.variants;
        # The artifact files of the package by Scala version and key, such
        # as artifacts."3.3.6"."core-sources.jar".
        artifacts = builtins.listToAttrs (
          map (v: {
            name = v.scalaVersion;
            value = builtins.listToAttrs (
              map (a: {
                name = a.key;
                value = artifactFile finalAttrs.finalPackage v.scalaVersion a.key;
              }) v.artifacts
            );
          }) project.variants
        );
      };
      meta.description = "${project.organization}:${project.name}, built by sbt-nix-backend";
    });

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
