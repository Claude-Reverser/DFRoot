#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_directory=$(mktemp -d "${TMPDIR:-/tmp}/dfroot-bootstrap-tests.XXXXXX")
trap 'rm -rf "$test_directory"' EXIT HUP INT TERM
for source in tests/test_disable_modules.c tests/test_bootstrap.c tests/test_bootstrap_log.c; do
    test_binary="$test_directory/$(basename "$source" .c)"
    ${CC:-cc} -std=gnu11 -Wall -Wextra -Werror -Itests/stubs "$source" -o "$test_binary"
    "$test_binary"
done
