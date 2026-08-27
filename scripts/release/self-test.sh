#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

for script in \
    "$PROJECT_ROOT/scripts/release/build.sh" \
    "$PROJECT_ROOT/scripts/release/init-demo.sh" \
    "$PROJECT_ROOT/scripts/release/compose-smoke.sh" \
    "$PROJECT_ROOT/scripts/release/android-smoke.sh" \
    "$PROJECT_ROOT/scripts/release/macos-smoke.sh" \
    "$PROJECT_ROOT/scripts/acceptance/standard-demo.sh" \
    "$PROJECT_ROOT/scripts/acceptance/self-test.sh"
do
    sh -n "$script"
done

"$PROJECT_ROOT/scripts/release/build.sh" --help >/dev/null
"$PROJECT_ROOT/scripts/release/init-demo.sh" --help >/dev/null
"$PROJECT_ROOT/scripts/release/compose-smoke.sh" --help >/dev/null
"$PROJECT_ROOT/scripts/release/android-smoke.sh" --help >/dev/null
"$PROJECT_ROOT/scripts/release/macos-smoke.sh" --help >/dev/null
"$PROJECT_ROOT/scripts/acceptance/standard-demo.sh" --help >/dev/null

for required in \
    "$PROJECT_ROOT/.env.example" \
    "$PROJECT_ROOT/config/firebase.properties.example" \
    "$PROJECT_ROOT/compose.yaml" \
    "$PROJECT_ROOT/compose.production.yaml" \
    "$PROJECT_ROOT/docs/deployment.md" \
    "$PROJECT_ROOT/infra/caddy/Caddyfile" \
    "$PROJECT_ROOT/infra/caddy/Caddyfile.production"
do
    [ -f "$required" ] || {
        printf '%s\n' "Missing release input: $required" >&2
        exit 1
    }
done

# Catch the common accidental-credential patterns without rejecting the
# intentionally named placeholder variables in the templates.
if git -C "$PROJECT_ROOT" grep -nE \
    'AIza[0-9A-Za-z_-]{20,}|-----BEGIN (RSA |EC )?PRIVATE KEY-----|(^|[[:space:]])sk-[A-Za-z0-9]{20,}' \
    -- ':!secrets/**' ':!.env*' >/dev/null 2>&1; then
    printf '%s\n' 'A likely real credential is present in tracked files.' >&2
    exit 1
fi

printf '%s\n' 'Release tooling self-test passed.'
