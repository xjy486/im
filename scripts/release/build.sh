#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

usage() {
    cat <<'EOF'
Usage:
  build.sh [options]

Build a release bundle containing the server jar, Android APK, macOS DMG,
container deployment files, configuration templates, and a SHA-256 manifest.

Options:
  --version VERSION       Release version (default: JITONG_RELEASE_VERSION or
                          1.0.0)
  --output-dir PATH       Bundle directory (default: release-dist/VERSION)
  --android-base-url URL  Android compile-time server URL
                          (default: https://im.example.com/)
  --android-keystore PATH Stable Android signing keystore. Defaults to
                          JITONG_ANDROID_KEYSTORE when set.
  --skip-docker-image     Do not build/save the server image
  --skip-android          Do not build the Android APK
  --skip-macos            Do not build the macOS DMG
  --help                  Show this help

Without a stable Android signing keystore, a random temporary keystore is generated
for install-smoke purposes. Use the release signing properties documented in
android-app/README.md for a production APK.
EOF
}

VERSION=${JITONG_RELEASE_VERSION:-1.0.0}
OUTPUT_DIR=
OUTPUT_DIR_EXPLICIT=false
ANDROID_BASE_URL=${JITONG_ANDROID_BASE_URL:-https://im.example.com/}
ANDROID_KEYSTORE=${JITONG_ANDROID_KEYSTORE:-}
ANDROID_STORE_PASSWORD=${JITONG_ANDROID_STORE_PASSWORD:-}
ANDROID_KEY_ALIAS=${JITONG_ANDROID_KEY_ALIAS:-}
ANDROID_KEY_PASSWORD=${JITONG_ANDROID_KEY_PASSWORD:-}
SKIP_DOCKER_IMAGE=false
SKIP_ANDROID=false
SKIP_MACOS=false

JAVA_HOME=$("$PROJECT_ROOT/scripts/dev-env.sh" --print-java-home)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
BUILD_TMP=$(mktemp -d "${TMPDIR:-/tmp}/jitong-release-build.XXXXXX")
trap 'rm -rf "$BUILD_TMP"' EXIT HUP INT TERM

if [ -z "${ANDROID_HOME:-}" ] && [ "$(uname -s)" = Darwin ] \
    && [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

while [ "$#" -gt 0 ]; do
    case "$1" in
        --version)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            VERSION=$2
            shift 2
            ;;
        --output-dir)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            OUTPUT_DIR=$2
            OUTPUT_DIR_EXPLICIT=true
            shift 2
            ;;
        --android-base-url)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            ANDROID_BASE_URL=$2
            shift 2
            ;;
        --android-keystore)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            ANDROID_KEYSTORE=$2
            shift 2
            ;;
        --skip-docker-image)
            SKIP_DOCKER_IMAGE=true
            shift
            ;;
        --skip-android)
            SKIP_ANDROID=true
            shift
            ;;
        --skip-macos)
            SKIP_MACOS=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf '%s\n' "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

case "$VERSION" in
    ''|*[!A-Za-z0-9._-]*)
        printf '%s\n' 'Version may only contain letters, digits, dots, underscores and hyphens.' >&2
        exit 2
        ;;
esac

version_without_suffix=${VERSION%%[-+]*}
old_ifs=$IFS
IFS=.
set -- $version_without_suffix
IFS=$old_ifs
if [ "$#" -ne 3 ]; then
    printf '%s\n' 'Release version must have three numeric components, for example 1.0.0.' >&2
    exit 2
fi
for component in "$@"; do
    case "$component" in
        0|[1-9]|[1-9][0-9]*) ;;
        *)
            printf '%s\n' 'Release version components must be numeric without leading zeroes.' >&2
            exit 2
            ;;
    esac
done
android_version_code=$(( $1 * 1000000 + $2 * 1000 + $3 ))

if [ "$OUTPUT_DIR_EXPLICIT" != true ]; then
    OUTPUT_DIR="$PROJECT_ROOT/release-dist/$VERSION"
fi

command -v shasum >/dev/null 2>&1 || {
    printf '%s\n' 'shasum is required.' >&2
    exit 1
}
if [ "$SKIP_ANDROID" != true ]; then
    if [ -n "$ANDROID_KEYSTORE" ]; then
        [ -f "$ANDROID_KEYSTORE" ] || {
            printf '%s\n' "Android keystore does not exist: $ANDROID_KEYSTORE" >&2
            exit 1
        }
        [ -n "$ANDROID_STORE_PASSWORD" ] \
            && [ -n "$ANDROID_KEY_ALIAS" ] \
            && [ -n "$ANDROID_KEY_PASSWORD" ] || {
            printf '%s\n' \
                'Stable Android signing requires JITONG_ANDROID_STORE_PASSWORD, JITONG_ANDROID_KEY_ALIAS and JITONG_ANDROID_KEY_PASSWORD.' \
                >&2
            exit 1
        }
    else
        command -v keytool >/dev/null 2>&1 || {
            printf '%s\n' 'keytool is required to create the Android install-smoke signing key.' >&2
            exit 1
        }
        ANDROID_KEYSTORE="$BUILD_TMP/android-install-smoke.keystore"
        ANDROID_STORE_PASSWORD=$(openssl rand -hex 24)
        ANDROID_KEY_ALIAS=jitong-install-smoke
        ANDROID_KEY_PASSWORD=$ANDROID_STORE_PASSWORD
        keytool -genkeypair -noprompt \
            -alias "$ANDROID_KEY_ALIAS" \
            -keyalg RSA \
            -keysize 2048 \
            -validity 3650 \
            -keystore "$ANDROID_KEYSTORE" \
            -storepass "$ANDROID_STORE_PASSWORD" \
            -keypass "$ANDROID_KEY_PASSWORD" \
            -dname 'CN=Jitong Install Smoke,O=Jitong,C=US' \
            >/dev/null 2>&1
    fi
fi

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/server" "$OUTPUT_DIR/android" "$OUTPUT_DIR/macos" \
    "$OUTPUT_DIR/infra/caddy" "$OUTPUT_DIR/config" "$OUTPUT_DIR/scripts"

printf '%s\n' "Building server jar for $VERSION..."
(
    cd "$PROJECT_ROOT"
    "$PROJECT_ROOT/scripts/dev-env.sh" ./mvnw --batch-mode --no-transfer-progress \
    -DskipTests package
)
server_jar=$(find "$PROJECT_ROOT/target" -maxdepth 1 -type f \
    -name 'im-server-*.jar' ! -name '*.original' -print -quit)
[ -n "$server_jar" ] || {
    printf '%s\n' 'The Maven build did not produce a server jar.' >&2
    exit 1
}
cp "$server_jar" \
    "$OUTPUT_DIR/server/jitong-server-$VERSION.jar"
if [ "$SKIP_DOCKER_IMAGE" != true ]; then
    image="jitong-im-server:$VERSION"
    printf '%s\n' "Building container image $image..."
    "$PROJECT_ROOT/scripts/docker-runtime.sh" docker build \
        --tag "$image" "$PROJECT_ROOT"
    "$PROJECT_ROOT/scripts/docker-runtime.sh" docker save \
        --output "$OUTPUT_DIR/server/jitong-im-server-$VERSION.tar" "$image"
    printf '%s\n' "$image" >"$OUTPUT_DIR/server/image-name.txt"
fi

if [ "$SKIP_ANDROID" != true ]; then
    (
        cd "$PROJECT_ROOT/android-app"
        ./gradlew --no-daemon --stacktrace assembleRelease \
            -PjitongVersion="$VERSION" \
            -PjitongVersionCode="$android_version_code" \
            -PjitongBaseUrl="$ANDROID_BASE_URL" \
            -PreleaseKeystore="$ANDROID_KEYSTORE" \
            -PreleaseStorePassword="$ANDROID_STORE_PASSWORD" \
            -PreleaseKeyAlias="$ANDROID_KEY_ALIAS" \
            -PreleaseKeyPassword="$ANDROID_KEY_PASSWORD"
    )
    cp "$PROJECT_ROOT/android-app/app/build/outputs/apk/release/app-release.apk" \
        "$OUTPUT_DIR/android/jitong-$VERSION.apk"
fi

if [ "$SKIP_MACOS" != true ]; then
    if [ "$(uname -s)" != Darwin ]; then
        printf '%s\n' 'The macOS DMG can only be built on macOS; use --skip-macos on another host.' >&2
        exit 1
    fi
    (
        cd "$PROJECT_ROOT/desktop-app"
        ./gradlew --no-daemon packageDistributionForCurrentOS -PjitongVersion="$VERSION"
    )
    dmg=$(find "$PROJECT_ROOT/desktop-app/build/compose/binaries/main/dmg" \
        -maxdepth 1 -type f -name '*.dmg' -print -quit)
    [ -n "$dmg" ] || {
        printf '%s\n' 'Compose Desktop did not produce a DMG.' >&2
        exit 1
    }
    cp "$dmg" "$OUTPUT_DIR/macos/jitong-$VERSION.dmg"
fi

cp "$PROJECT_ROOT/compose.yaml" "$OUTPUT_DIR/compose.yaml"
cp "$PROJECT_ROOT/compose.production.yaml" "$OUTPUT_DIR/compose.production.yaml"
cp "$PROJECT_ROOT/.env.example" "$OUTPUT_DIR/.env.example"
cp "$PROJECT_ROOT/docs/release.md" "$OUTPUT_DIR/RELEASE.md"
cp "$PROJECT_ROOT/config/firebase.properties.example" "$OUTPUT_DIR/config/firebase.properties.example"
cp -R "$PROJECT_ROOT/infra/caddy/." "$OUTPUT_DIR/infra/caddy/"
mkdir -p "$OUTPUT_DIR/scripts/release"
cp "$PROJECT_ROOT/scripts/release/"*.sh "$OUTPUT_DIR/scripts/release/"
cp "$PROJECT_ROOT/scripts/docker-runtime.sh" "$OUTPUT_DIR/scripts/docker-runtime.sh"
cp "$PROJECT_ROOT/scripts/dev-env.sh" "$OUTPUT_DIR/scripts/dev-env.sh"
cp "$PROJECT_ROOT/scripts/dev-up.sh" "$OUTPUT_DIR/scripts/dev-up.sh"
cp "$PROJECT_ROOT/scripts/dev-smoke.sh" "$OUTPUT_DIR/scripts/dev-smoke.sh"
cp -R "$PROJECT_ROOT/scripts/backup" "$OUTPUT_DIR/scripts/backup"
mkdir -p "$OUTPUT_DIR/.mvn/wrapper"
cp "$PROJECT_ROOT/mvnw" "$PROJECT_ROOT/mvnw.cmd" "$OUTPUT_DIR/"
cp "$PROJECT_ROOT/.mvn/wrapper/maven-wrapper.properties" "$OUTPUT_DIR/.mvn/wrapper/"
chmod 755 "$OUTPUT_DIR/scripts/"*.sh "$OUTPUT_DIR/scripts/release/"*.sh \
    "$OUTPUT_DIR/scripts/backup/"*.sh

cat >"$OUTPUT_DIR/Dockerfile" <<EOF
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl \
    && addgroup -S jitong \
    && adduser -S jitong -G jitong

WORKDIR /app
COPY server/jitong-server-$VERSION.jar /app/server.jar

USER jitong
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/server.jar"]
EOF

generated_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
image_name=${image:-not-built}
{
    printf '%s\n' 'manifest_version=1'
    printf 'release_version=%s\n' "$VERSION"
    printf 'generated_at=%s\n' "$generated_at"
    printf 'server_image=%s\n' "$image_name"
    printf '%s\n' 'credentials_included=false'
    printf '%s\n' 'provider_credentials_included=false'
} >"$OUTPUT_DIR/manifest.env"

(
    cd "$OUTPUT_DIR"
    find . -type f ! -name checksums.sha256 ! -name manifest.env \
        -print | sort | while IFS= read -r file; do
        printf '%s  %s\n' "$(shasum -a 256 "$file" | awk '{print $1}')" "$file"
    done
) >"$OUTPUT_DIR/checksums.sha256"

if grep -Eiq '(^|_)(password|secret|token|credential|api_key)(=|_)' \
    "$OUTPUT_DIR/manifest.env"; then
    printf '%s\n' 'Release manifest unexpectedly contains a credential value.' >&2
    exit 1
fi

printf '%s\n' "Release bundle created: $OUTPUT_DIR"
printf '%s\n' "Manifest: $OUTPUT_DIR/manifest.env"
