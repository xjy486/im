#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

for script in \
    "$PROJECT_ROOT/scripts/acceptance/standard-demo.sh" \
    "$PROJECT_ROOT/scripts/release/init-demo.sh"
do
    [ -x "$script" ] || {
        printf '%s\n' "Not executable: $script" >&2
        exit 1
    }
    "$script" --help >/dev/null
done

sh -n "$PROJECT_ROOT/scripts/acceptance/standard-demo.sh"
grep -q 'T39 standard demo evidence' "$PROJECT_ROOT/scripts/acceptance/standard-demo.sh"
grep -q 'DEVICE_REPLACEMENT_REQUIRED' "$PROJECT_ROOT/scripts/acceptance/standard-demo.sh"
grep -q 'group invite QR payload' "$PROJECT_ROOT/scripts/acceptance/standard-demo.sh"

printf '%s\n' 'Acceptance tooling self-test passed.'
