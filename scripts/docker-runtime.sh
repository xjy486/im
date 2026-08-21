#!/bin/sh
set -eu

DOCKER_BIN=${DOCKER_BIN:-docker}

setup_docker_runtime() {
    if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
        printf '%s\n' 'Docker CLI was not found.' >&2
        return 1
    fi

    if [ "$(uname -s)" = "Darwin" ]; then
        if ! command -v colima >/dev/null 2>&1; then
            printf '%s\n' \
                'Colima is required for macOS Docker development. Install it with: brew install colima' \
                >&2
            return 1
        fi

        if ! colima status >/dev/null 2>&1; then
            printf '%s\n' 'Colima is not running; starting Colima.' >&2
            colima start >&2
        fi

        colima_socket="${HOME}/.colima/default/docker.sock"
        if [ ! -S "$colima_socket" ]; then
            printf '%s\n' "Colima Docker socket is unavailable: $colima_socket" >&2
            return 1
        fi

        # Clear an inherited DOCKER_HOST while selecting the context so the
        # Docker CLI does not accidentally target another daemon.
        unset DOCKER_HOST
        "$DOCKER_BIN" context use colima >/dev/null
        export DOCKER_HOST="unix://${colima_socket}"
        export TESTCONTAINERS_RYUK_DISABLED=true
    fi

    if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
        printf '%s\n' 'Docker daemon is not reachable.' >&2
        return 1
    fi
}

setup_docker_runtime

case "${1:-}" in
    --check)
        if [ "$(uname -s)" = "Darwin" ]; then
            printf '%s\n' 'Docker runtime: Colima'
            printf 'Docker socket: %s\n' "${DOCKER_HOST:-}"
            printf '%s\n' 'Testcontainers Ryuk: disabled'
        else
            printf '%s\n' 'Docker runtime: system Docker'
        fi
        "$DOCKER_BIN" info --format 'Docker server: {{.ServerVersion}}'
        "$DOCKER_BIN" compose version
        ;;
    --print-env)
        if [ "$(uname -s)" = "Darwin" ]; then
            escaped_host=$(printf '%s' "$DOCKER_HOST" | sed "s/'/'\\''/g")
            printf "export DOCKER_HOST='%s'\n" "$escaped_host"
            printf '%s\n' 'export TESTCONTAINERS_RYUK_DISABLED=true'
        fi
        ;;
    '')
        printf '%s\n' 'Docker runtime is ready. Pass a command or use --check.' >&2
        ;;
    *)
        exec "$@"
        ;;
esac
