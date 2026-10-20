#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/dfroot-process-test.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM

if [ -n "${JAVA_HOME:-}" ]; then
    test_javac=${JAVAC:-"$JAVA_HOME/bin/javac"}
    test_java=${JAVA:-"$JAVA_HOME/bin/java"}
else
    test_javac=${JAVAC:-javac}
    test_java=${JAVA:-java}
fi

"$test_javac" --release 11 -Xlint:all -d "$test_dir" \
    app/src/main/java/df/root/IReporter.java \
    app/src/main/java/df/root/ProcessOutput.java \
    tests/ProcessOutputTest.java
"$test_java" -ea -cp "$test_dir" df.root.ProcessOutputTest
