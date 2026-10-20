#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_binary=$(mktemp "${TMPDIR:-/tmp}/dfroot-native-safety.XXXXXX")
trap 'rm -f "$test_binary"' EXIT HUP INT TERM
${CC:-cc} -std=gnu11 -Wall -Wextra -Werror -pedantic \
    ${CFLAGS:-} tests/test_native_safety.c -o "$test_binary"
"$test_binary"
