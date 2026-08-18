#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE="$PROJECT_DIR/.env"

"$PROJECT_DIR/scripts/dev-up.sh" >/dev/null

DOCKER_BIN=${DOCKER_BIN:-docker}
if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
    DOCKER_BIN=/Applications/Docker.app/Contents/Resources/bin/docker
fi

JITONG_HTTP_PORT=$(sed -n 's/^JITONG_HTTP_PORT=//p' "$ENV_FILE")
HEALTH_URL="http://127.0.0.1:${JITONG_HTTP_PORT:-8080}/api/v1/system/health"

"$DOCKER_BIN" compose \
    --project-directory "$PROJECT_DIR" \
    --env-file "$ENV_FILE" \
    restart server >/dev/null

attempt=0
until curl --fail --silent "$HEALTH_URL" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 45 ]; then
        printf '%s\n' 'Service did not become healthy after restart.' >&2
        exit 1
    fi
    sleep 2
done

curl --fail --silent --show-error "$HEALTH_URL"
printf '\nMigration restart smoke test passed.\n'
