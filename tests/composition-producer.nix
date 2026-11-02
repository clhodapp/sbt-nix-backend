# SPDX-License-Identifier: MIT
{ pkgs, purifier }:
let
  producer =
    pkgs.runCommand "gradle-nix-input"
      {
        nativeBuildInputs = [ pkgs.jdk_headless ];
        passthru.projections = purifier.jvmProjections {
          repository = producer;
          inherit (pkgs) runCommand python3;
        };
      }
      ''
        mkdir -p classes
        cat > StringUtils.java <<'JAVA'
        package org.apache.commons.lang3;
        public final class StringUtils {
          public static boolean isEmpty(CharSequence value) {
            return value == null || value.length() == 0;
          }
          public static String capitalize(String value) {
            if (value == null || value.isEmpty()) return value;
            return Character.toUpperCase(value.charAt(0)) + value.substring(1);
          }
        }
        JAVA
        javac --release 8 -d classes StringUtils.java
        dir=$out/org/apache/commons/commons-lang3/0.0.1
        mkdir -p "$dir"
        jar --create --file "$dir/commons-lang3-0.0.1.jar" \
          --date=1980-01-01T00:00:02Z -C classes .
        cat > "$dir/commons-lang3-0.0.1.pom" <<'POM'
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion><groupId>org.apache.commons</groupId>
          <artifactId>commons-lang3</artifactId><version>0.0.1</version>
        </project>
        POM
      '';
in
producer
