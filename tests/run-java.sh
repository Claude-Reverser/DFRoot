#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d "${TMPDIR:-/tmp}/dfroot-java-test.XXXXXX")
trap 'rm -rf "$test_classes"' EXIT HUP INT TERM
test_javac="${JAVAC:-javac}"
test_java="${JAVA:-java}"
"$test_javac" --release 11 -d "$test_classes" \
    app/src/main/java/df/root/IReporter.java \
    app/src/main/java/df/root/RunLogStore.java \
    app/src/main/java/df/root/RunCoordinator.java \
    tests/RunCoordinatorTest.java
"$test_java" -ea -cp "$test_classes" df.root.RunCoordinatorTest
