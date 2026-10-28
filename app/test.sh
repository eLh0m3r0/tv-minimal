#!/usr/bin/env bash
# Host JVM unit tests for pure core logic. Usage: ./test.sh [logfile]
set -uo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk17}
CP="libs/test/junit__junit-4.13.2.jar:libs/test/org.hamcrest__hamcrest-core-1.3.jar:libs/test/org.json__json-20240303.jar"
rm -rf build/test && mkdir -p build/test
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 11 -d build/test -cp "$CP" $(find src/core test/java -name '*.java') || exit 2
CLASSES=$(cd test/java && find . -name '*Test.java' | sed 's#^\./##; s#\.java$##; s#/#.#g' | sort)
"$JAVA_HOME/bin/java" -cp "build/test:$CP" org.junit.runner.JUnitCore $CLASSES
