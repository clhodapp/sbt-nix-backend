#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Native JVM input selection, sandbox build, and projected publication."""
from pathlib import Path
import argparse, json, os, shutil, subprocess, tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--sbt-version', default='1.11.7')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
system = subprocess.check_output(['nix', 'eval', '--impure', '--raw', '--expr',
                                  'builtins.currentSystem'], text=True).strip()
revision = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
assert not subprocess.check_output(['git', '-C', str(root), 'diff', '--binary', 'HEAD'], text=True).strip(), \
    'composition evidence requires committed producer source'
reference = 'git+file://' + str(root) + '?rev=' + revision
lock_bytes = (root / 'flake.lock').read_bytes()
archived = json.loads(subprocess.check_output(
    ['nix', 'flake', 'archive', '--json', '--no-update-lock-file', reference], text=True))
snapshot = archived['path']
def check_archived_inputs(node):
    path = Path(node['path'])
    assert path.is_dir() and str(path.resolve()) == str(path), path
    for value in node.get('inputs', {}).values():
        check_archived_inputs(value)
check_archived_inputs(archived)
assert (root / 'flake.lock').read_bytes() == lock_bytes, 'archive changed the producer lockfile'
source_nar_hash = subprocess.check_output(['nix', 'hash', 'path', snapshot], text=True).strip()
print(json.dumps({'producerSource': {'revision': revision, 'reference': reference,
    'path': snapshot, 'narHash': source_nar_hash}, 'system': system, 'sbtVersion': args.sbt_version}), flush=True)
with tempfile.TemporaryDirectory(prefix='sbt-native-composition-') as temporary:
    temporary = Path(temporary)
    package = subprocess.check_output(['nix', 'build', 'path:' + snapshot + '#sbt',
        '--print-out-paths', '--out-link', str(temporary / 'wrapper')], text=True).strip().splitlines()[-1]
    sbt = Path(package) / 'bin/sbt'
    project = temporary / 'native-project'
    (project / 'project').mkdir(parents=True)
    (project / 'project/build.properties').write_text('sbt.version=' + args.sbt_version + '\n')
    reference = 'path:' + snapshot + '#checks.' + system + '.composition-producer'
    (project/'build.sbt').write_text('''ThisBuild / scalaVersion := "2.12.20"
ThisBuild / nixFlakeRepositories += NixFlakeRepository("lang3", '''+json.dumps(reference)+''')
organization := "example"
name := "native-input-consumer"
version := "1.0.0"
crossPaths := false
autoScalaLibrary := false
libraryDependencies ++= Seq(
  "org.apache.commons" % "commons-text" % "1.13.0",
  "org.apache.commons" % "commons-lang3" % "[99,100)"
)
Compile / mainClass := Some("example.NativeMain")
''')
    source=project/'src/main/java/example/NativeMain.java'
    source.parent.mkdir(parents=True)
    source.write_text('''package example;
public class NativeMain {
  public static void main(String[] args) {
    String result = org.apache.commons.text.WordUtils.capitalizeFully("hello nix");
    if (!result.equals("Hello Nix")) throw new AssertionError(result);
    System.out.println(result);
  }
}
''')
    definition = project / 'build.sbt'
    if args.sbt_version.startswith('0.13.'):
        definition.write_text(definition.read_text()
            .replace('ThisBuild / scalaVersion', 'scalaVersion in ThisBuild')
            .replace('ThisBuild / nixFlakeRepositories', 'nixFlakeRepositories in ThisBuild')
            .replace('Compile / mainClass', 'mainClass in Compile')
            .replace('2.12.20', '2.10.7'))
    elif args.sbt_version.startswith('2.'):
        definition.write_text(definition.read_text().replace('2.12.20', '3.8.4'))
    env = dict(os.environ, SBT_NIX_BACKEND_MAVEN_CENTRAL_MIRROR='https://repo.maven.apache.org/maven2/')
    def run(*commands):
        subprocess.run([str(sbt), '-batch', ';' + ';'.join(commands)], cwd=project, env=env, check=True)
    run('nixLock')
    locked = json.loads((project / 'sbt-purifier.lock.json').read_text())
    assert len(locked['flakeRepositories']) == 1
    assert locked['buildDirectory'] == project.name
    assert not any('/commons-lang3/0.0.1-nix-' in entry['path'] for entry in locked['artifacts'])
    run('run', 'makePom', 'nixRuntimeRepository')
    published = temporary / 'published'
    scope = 'publishTo'
    setting = 'set ' + scope + ' := Some("native-test" at ' + json.dumps(published.as_uri()) + ')'
    shutil.rmtree(project / 'target/classes', ignore_errors=True)
    run(setting, 'nixPublish')
    assert not (project / 'target/classes').exists(), 'publication compiled the working tree'
    pom = next((published / 'example/native-input-consumer').glob('*/*.pom'))
    assert '-nix-' in pom.parent.name
    dependencies = ET.parse(pom).findall('.//{*}dependency')
    for name in ['commons-text', 'commons-lang3']:
        dependency = next(node for node in dependencies if node.findtext('{*}artifactId') == name)
        version = dependency.findtext('{*}version')
        assert version.startswith('[') and version.endswith(']') and '-nix-' in version
        revision = version[1:-1]
        path = published / 'org/apache/commons' / name / revision
        assert (path / (name + '-' + revision + '.jar')).is_file()
    assert (published / 'org/apache/commons/commons-text').is_dir()
    # Resolve the deployed descriptors with an ordinary Maven client. This
    # also requires uploaded parent/BOM POMs, not just the project JARs.
    expression = """let
      f = builtins.getFlake ("path:" + builtins.getEnv "COMPOSITION_FLAKE");
      p = f.inputs.nixpkgs.legacyPackages.${builtins.currentSystem};
    in p.runCommand "sbt-composition-downstream-tools" {} ''
      mkdir -p "$out/bin"
      ln -s ${p.maven}/bin/mvn "$out/bin/mvn"
      ln -s ${p.jdk21_headless.home} "$out/jdk"
    '' """
    tools = Path(subprocess.check_output(['nix', 'build', '--impure', '--print-out-paths',
        '--out-link', str(temporary / 'maven-tools'), '--expr', expression], text=True,
        env=dict(os.environ, COMPOSITION_FLAKE=snapshot)).strip())
    downstream = temporary / 'ordinary-downstream'
    downstream.mkdir()
    (downstream / 'pom.xml').write_text(f"""<project><modelVersion>4.0.0</modelVersion>
      <groupId>example</groupId><artifactId>downstream</artifactId><version>1</version>
      <repositories><repository><id>projected</id><url>{published.as_uri()}</url></repository></repositories>
      <dependencies><dependency><groupId>example</groupId><artifactId>native-input-consumer</artifactId>
      <version>{pom.parent.name}</version></dependency></dependencies></project>""")
    plain = dict(os.environ, JAVA_HOME=str(tools / 'jdk'))
    subprocess.run([str(tools / 'bin/mvn'), '-B', '-Dmaven.repo.local='+str(temporary / 'ordinary-m2'),
        'dependency:build-classpath', '-Dmdep.outputFile=classpath'], cwd=downstream, env=plain, check=True)
    output = subprocess.check_output([str(tools / 'jdk/bin/java'), '-cp',
        (downstream / 'classpath').read_text().strip(), 'example.NativeMain'],
        cwd=downstream, env=plain, text=True)
    assert 'Hello Nix' in output, output
    print('SBT NATIVE COMPOSITION PASSED: ' + args.sbt_version)
