# Reads an sbt-nix-backend lock file (written by the plugin's nixLock).
#
# Needs no nixpkgs: the interactive mode of the plugin evaluates this with
# nothing but the Nix installation it runs.
{ lockFile }:
let
  lock = builtins.fromJSON (builtins.readFile lockFile);

  supportedVersion = 1;

  # A store path name from a file name. Store path names allow
  # [A-Za-z0-9+._?=-], must not start with a dot, and are at most 211
  # characters; every other character becomes "_". The plugin's interactive
  # mode and the sandboxed build both name their fetches with this
  # function, so they share store paths.
  storeName =
    fileName:
    let
      # builtins.split alternates the text between matches with a list per
      # match.
      replaced = builtins.concatStringsSep "" (
        map (part: if builtins.isList part then "_" else part) (
          builtins.split "[^A-Za-z0-9+._?=-]" fileName
        )
      );
      undotted = builtins.head (builtins.match "\\.*(.*)" replaced);
      nonEmpty = if undotted == "" then "file" else undotted;
    in
    builtins.substring 0 200 nonEmpty;

  baseName = path: builtins.baseNameOf path;
in
assert
  lock.version == supportedVersion
  || throw "sbt-nix-backend: ${toString lockFile} has lock format version ${toString lock.version}; this library reads version ${toString supportedVersion}. Re-export it with the plugin version that wrote this library.";
lock
// {
  inherit storeName;

  # The fetch name of an artifact: its file name, made a valid store name.
  artifactName = artifact: storeName (baseName artifact.path);

  projectsById = builtins.listToAttrs (
    map (p: {
      name = p.id;
      value = p;
    }) lock.projects
  );
}
