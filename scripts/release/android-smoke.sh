#!/bin/sh
set -eu

usage() {
    cat <<'EOF'
Usage:
  android-smoke.sh APK [ADB_SERIAL]

Uninstall the release package from a connected Android device/emulator, install
the APK, launch the main activity, and verify that the application process
stays alive.
EOF
}

if [ "${1:-}" = '-h' ] || [ "${1:-}" = '--help' ] || [ "$#" -lt 1 ]; then
    usage
    [ "$#" -lt 1 ] && exit 2 || exit 0
fi

APK=$1
ADB_SERIAL=${2:-${ANDROID_SERIAL:-}}
[ -f "$APK" ] || {
    printf '%s\n' "APK does not exist: $APK" >&2
    exit 1
}
command -v adb >/dev/null 2>&1 || {
    printf '%s\n' 'adb is required.' >&2
    exit 1
}

ADB="adb"
if [ -n "$ADB_SERIAL" ]; then
    ADB="adb -s $ADB_SERIAL"
fi

# A clean install is intentional: do not use -r and do not retain app data.
$ADB get-state >/dev/null
$ADB uninstall com.jitong.im.android >/dev/null 2>&1 || true
$ADB install "$APK" >/dev/null
$ADB shell am force-stop com.jitong.im.android
$ADB shell am start -n com.jitong.im.android/.MainActivity >/dev/null
sleep 3

$ADB shell pidof com.jitong.im.android >/dev/null 2>&1 || {
    printf '%s\n' 'Android application did not remain running after launch.' >&2
    exit 1
}
printf '%s\n' 'Android clean-install and launch smoke passed.'
