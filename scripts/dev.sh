#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Prefer the isolated JDK downloaded for this workspace; otherwise use caller's JDK 21.
if [ -d '../.tools/jdk-21.0.12.1+1/Contents/Home' ]; then
  JAVA_HOME="$(cd '../.tools/jdk-21.0.12.1+1/Contents/Home' && pwd)"
  export JAVA_HOME
fi
if [ -d '../.tools/gradle' ]; then
  GRADLE_USER_HOME="$(cd '../.tools/gradle' && pwd)"
  export GRADLE_USER_HOME
fi
exec ./gradlew "${@:-runClient}" --console=plain
