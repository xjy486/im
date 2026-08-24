#!/bin/sh
set -eu

usage() {
    cat <<'EOF'
Usage:
  macos-smoke.sh DMG

Mount a macOS DMG, copy the application to an isolated temporary Applications
directory, launch it, and verify that its bundled JVM process stays alive.
EOF
}

if [ "${1:-}" = '-h' ] || [ "${1:-}" = '--help' ] || [ "$#" -lt 1 ]; then
    usage
    [ "$#" -lt 1 ] && exit 2 || exit 0
fi

DMG=$1
[ -f "$DMG" ] || {
    printf '%s\n' "DMG does not exist: $DMG" >&2
    exit 1
}
[ "$(uname -s)" = Darwin ] || {
    printf '%s\n' 'The macOS installer smoke must run on macOS.' >&2
    exit 1
}
command -v hdiutil >/dev/null 2>&1 || {
    printf '%s\n' 'hdiutil is required.' >&2
    exit 1
}
command -v ditto >/dev/null 2>&1 || {
    printf '%s\n' 'ditto is required.' >&2
    exit 1
}

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/jitong-macos-install.XXXXXX")
MOUNT_POINT=
APP_PATH=
APP_PID=
cleanup() {
    if [ -n "$APP_PID" ]; then
        kill "$APP_PID" >/dev/null 2>&1 || true
    fi
    if [ -n "$MOUNT_POINT" ]; then
        hdiutil detach "$MOUNT_POINT" >/dev/null 2>&1 || true
    fi
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

mount_output=$(hdiutil attach "$DMG" -nobrowse -readonly)
MOUNT_POINT=$(printf '%s\n' "$mount_output" | awk 'NF {mount=$NF} END {print mount}')
[ -d "$MOUNT_POINT" ] || {
    printf '%s\n' 'Could not determine the mounted DMG path.' >&2
    exit 1
}

source_app=$(find "$MOUNT_POINT" -maxdepth 1 -type d -name '*.app' -print -quit)
[ -n "$source_app" ] || {
    printf '%s\n' 'The DMG does not contain an application bundle.' >&2
    exit 1
}
mkdir -p "$TEST_ROOT/Applications"
APP_PATH="$TEST_ROOT/Applications/$(basename "$source_app")"
ditto "$source_app" "$APP_PATH"
bundle_executable=$(plutil -extract CFBundleExecutable raw -o - \
    "$APP_PATH/Contents/Info.plist")
executable="$APP_PATH/Contents/MacOS/$bundle_executable"
[ -x "$executable" ] || {
    printf '%s\n' "The macOS application executable is missing: $executable" >&2
    exit 1
}
"$executable" >"$TEST_ROOT/stdout.log" 2>"$TEST_ROOT/stderr.log" &
APP_PID=$!
sleep 6

kill -0 "$APP_PID" >/dev/null 2>&1 || {
    printf '%s\n' 'The macOS application did not remain running after launch.' >&2
    cat "$TEST_ROOT/stderr.log" >&2 || true
    exit 1
}
printf '%s\n' 'macOS DMG install and launch smoke passed.'
