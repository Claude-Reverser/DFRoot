#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_binary=$(mktemp "${TMPDIR:-/tmp}/dfroot-bootstrap-test.XXXXXX")
trap 'rm -f "$test_binary"' EXIT HUP INT TERM
${CC:-cc} -std=gnu11 -Wall -Wextra -Itests/stubs tests/test_disable_modules.c -o "$test_binary"
"$test_binary"
