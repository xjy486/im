#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

is_java_21() {
    candidate=$1
    [ -x "$candidate/bin/java" ] \
        && "$candidate/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "21([."])'
}

find_java_home() {
    if [ -n "${JAVA_HOME:-}" ] && is_java_21 "$JAVA_HOME"; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi

    if [ -x /usr/libexec/java_home ]; then
        detected=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
        if [ -n "$detected" ] && is_java_21 "$detected"; then
            printf '%s\n' "$detected"
            return 0
        fi
    fi

    for candidate in \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    do
        if is_java_21 "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

print_java_home() {
    JAVA_21_HOME=$(find_java_home || true)
    if [ -z "$JAVA_21_HOME" ]; then
        printf '%s\n' 'JDK 21 was not found.' >&2
        exit 1
    fi
    printf '%s\n' "$JAVA_21_HOME"
}

case "${1:-}" in
    --print-java-home)
        print_java_home
        exit 0
        ;;
esac

find_docker() {
    if command -v docker >/dev/null 2>&1; then
        command -v docker
        return 0
    fi

    if [ -x /Applications/Docker.app/Contents/Resources/bin/docker ]; then
        printf '%s\n' /Applications/Docker.app/Contents/Resources/bin/docker
        return 0
    fi

    return 1
}

JAVA_21_HOME=$(find_java_home || true)
if [ -z "$JAVA_21_HOME" ]; then
    printf '%s\n' 'JDK 21 was not found.' >&2
    printf '%s\n' 'Install it once with: brew install --cask temurin@21' >&2
    printf '%s\n' 'Then rerun: ./scripts/dev-env.sh --check' >&2
    exit 1
fi

DOCKER_BIN=$(find_docker || true)
if [ -z "$DOCKER_BIN" ]; then
    printf '%s\n' 'Docker CLI was not found. Install and start Docker Desktop.' >&2
    exit 1
fi

if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
    printf '%s\n' 'Docker Desktop is installed, but its daemon is not ready.' >&2
    printf '%s\n' 'Start it with: open -a Docker' >&2
    printf '%s\n' 'Wait until `docker info` succeeds, then retry.' >&2
    exit 1
fi

export JAVA_HOME="$JAVA_21_HOME"
export PATH="$JAVA_HOME/bin:$(dirname -- "$DOCKER_BIN"):$PATH"
cd "$PROJECT_DIR"

print_environment() {
    printf 'Project: %s\n' "$PROJECT_DIR"
    printf 'JAVA_HOME: %s\n' "$JAVA_HOME"
    "$JAVA_HOME/bin/java" -version
    "$DOCKER_BIN" --version
    "$DOCKER_BIN" compose version
    "$PROJECT_DIR/mvnw" --version
}

case "${1:-}" in
    --check)
        print_environment
        exit 0
        ;;
    --shell)
        shift
        print_environment
        exec "${SHELL:-/bin/zsh}" -i
        ;;
    --)
        shift
        ;;
esac

if [ "$#" -eq 0 ]; then
    print_environment
    exec "${SHELL:-/bin/zsh}" -i
fi

exec "$@"
