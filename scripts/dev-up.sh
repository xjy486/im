#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE="$PROJECT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
    umask 077
    POSTGRES_PASSWORD=$(openssl rand -hex 24)
    MINIO_ROOT_PASSWORD=$(openssl rand -hex 24)
    {
        printf '%s\n' "JITONG_HTTP_PORT=8080"
        printf '%s\n' "POSTGRES_PASSWORD=$POSTGRES_PASSWORD"
        printf '%s\n' "MINIO_ROOT_USER=jitong"
        printf '%s\n' "MINIO_ROOT_PASSWORD=$MINIO_ROOT_PASSWORD"
        printf '%s\n' "MINIO_BUCKET=jitong-media"
    } > "$ENV_FILE"
    printf '%s\n' "Created local credentials in $ENV_FILE"
fi

DOCKER_BIN=${DOCKER_BIN:-docker}
if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
    if [ -x /Applications/Docker.app/Contents/Resources/bin/docker ]; then
        DOCKER_BIN=/Applications/Docker.app/Contents/Resources/bin/docker
    else
        printf '%s\n' 'Docker CLI was not found.' >&2
        exit 1
    fi
fi

"$DOCKER_BIN" compose \
    --project-directory "$PROJECT_DIR" \
    --env-file "$ENV_FILE" \
    up --build --detach --wait

JITONG_HTTP_PORT=$(sed -n 's/^JITONG_HTTP_PORT=//p' "$ENV_FILE")
curl --fail --silent --show-error "http://127.0.0.1:${JITONG_HTTP_PORT:-8080}/api/v1/system/health"
printf '\n'
