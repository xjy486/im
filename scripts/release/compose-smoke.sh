#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$SCRIPT_DIR/../compose.yaml" ]; then
    PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
    DOCKER_RUNTIME="$SCRIPT_DIR/docker-runtime.sh"
else
    PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
    DOCKER_RUNTIME="$SCRIPT_DIR/../docker-runtime.sh"
fi

usage() {
    cat <<'EOF'
Usage:
  compose-smoke.sh

Start an isolated Compose project through the repository Docker runtime
wrapper, validate the 2 CPU / 2 GiB service budget, create four demo users,
and verify health after a server restart.
EOF
}

case "${1:-}" in
    '' ) ;;
    -h|--help)
        usage
        exit 0
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac

command -v python3 >/dev/null 2>&1 || {
    printf '%s\n' 'Python 3 is required.' >&2
    exit 1
}
command -v curl >/dev/null 2>&1 || {
    printf '%s\n' 'curl is required.' >&2
    exit 1
}
command -v jq >/dev/null 2>&1 || {
    printf '%s\n' 'jq is required.' >&2
    exit 1
}

pick_free_port() {
    python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/jitong-release-compose.XXXXXX")
ENV_FILE="$TEST_ROOT/.env"
PROJECT_NAME="jitong-release-smoke-$$"
HTTP_PORT=$(pick_free_port)
POSTGRES_PASSWORD=$(openssl rand -hex 24)
MINIO_ROOT_PASSWORD=$(openssl rand -hex 24)
ADMIN_API_KEY=$(openssl rand -hex 32)
JITONG_DOMAIN=im.example.com
CADDY_EMAIL=ops@example.com
cat >"$ENV_FILE" <<EOF
JITONG_HTTP_PORT=$HTTP_PORT
COMPOSE_PROJECT_NAME=$PROJECT_NAME
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
MINIO_ROOT_USER=jitong
MINIO_ROOT_PASSWORD=$MINIO_ROOT_PASSWORD
MINIO_BUCKET=jitong-media
ADMIN_API_KEY=$ADMIN_API_KEY
JITONG_SERVER_IMAGE=jitong-im-server:release-smoke
JITONG_DOMAIN=$JITONG_DOMAIN
CADDY_EMAIL=$CADDY_EMAIL
EOF
chmod 600 "$ENV_FILE"

compose() {
    "$DOCKER_RUNTIME" docker compose \
        --project-name "$PROJECT_NAME" \
        --project-directory "$PROJECT_ROOT" \
        --env-file "$ENV_FILE" \
        "$@"
}

cleanup() {
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

compose_up_args=
image_tar=
RELEASE_ROOT=
if [ -f "$PROJECT_ROOT/../server/image-name.txt" ]; then
    RELEASE_ROOT=$(CDPATH= cd -- "$PROJECT_ROOT/.." && pwd)
elif [ -f "$PROJECT_ROOT/server/image-name.txt" ]; then
    RELEASE_ROOT="$PROJECT_ROOT"
fi
if [ -n "$RELEASE_ROOT" ]; then
    image_tar=$(find "$RELEASE_ROOT/server" -maxdepth 1 -type f -name '*.tar' -print -quit)
fi
if [ -n "$RELEASE_ROOT" ] && [ -n "$image_tar" ]; then
    image_name=$(cat "$RELEASE_ROOT/server/image-name.txt")
    printf '%s\n' "Loading release image $image_name..."
    "$DOCKER_RUNTIME" docker load --input "$image_tar"
    sed -i.bak "s#^JITONG_SERVER_IMAGE=.*#JITONG_SERVER_IMAGE=$image_name#" "$ENV_FILE"
    rm -f "$ENV_FILE.bak"
    compose_up_args='--no-build'
else
    compose_up_args='--build'
fi

printf '%s\n' 'Validating Compose resource budget...'
compose config --format json >"$TEST_ROOT/compose.json"
cpu_total=$(jq -r '
    [.services[].cpus | tonumber] | add
' "$TEST_ROOT/compose.json")
memory_total=$(jq -r '
    [.services[].mem_limit | tonumber] | add
' "$TEST_ROOT/compose.json")
python3 - "$cpu_total" "$memory_total" <<'PY'
import sys

cpu_total = float(sys.argv[1])
memory_total = int(sys.argv[2])
if cpu_total > 2.0001:
    raise SystemExit(f"CPU budget exceeded: {cpu_total}")
if memory_total > 2 * 1024 * 1024 * 1024:
    raise SystemExit(f"memory budget exceeded: {memory_total}")
print(f"Resource budget OK: {cpu_total:g} CPUs, {memory_total} bytes")
PY

printf '%s\n' 'Validating production Compose and Caddy configuration...'
compose -f compose.yaml -f compose.production.yaml config --quiet
"$DOCKER_RUNTIME" docker run --rm \
    -e "JITONG_DOMAIN=$JITONG_DOMAIN" \
    -e "CADDY_EMAIL=$CADDY_EMAIL" \
    -v "$PROJECT_ROOT/infra/caddy:/etc/caddy:ro" \
    caddy:2.10.2-alpine \
    caddy validate --config /etc/caddy/Caddyfile.production

printf '%s\n' 'Starting isolated Compose stack...'
compose up $compose_up_args --detach --wait

health_url="http://127.0.0.1:$HTTP_PORT/api/v1/system/health"
attempt=0
until curl --fail --silent --show-error "$health_url" >/dev/null; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 45 ] || {
        printf '%s\n' 'Compose stack did not become healthy.' >&2
        compose logs --no-color server caddy >&2 || true
        exit 1
    }
    sleep 2
done

demo_output="$TEST_ROOT/demo-accounts.json"
"$SCRIPT_DIR/init-demo.sh" \
    --base-url "http://127.0.0.1:$HTTP_PORT" \
    --admin-api-key "$ADMIN_API_KEY" \
    --password 'demo-password-for-smoke' \
    --output "$demo_output"
jq -e '.version == 1 and (.users | length == 4)' "$demo_output" >/dev/null

compose restart server
attempt=0
until curl --fail --silent --show-error "$health_url" >/dev/null; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 45 ] || {
        printf '%s\n' 'Compose stack did not recover after server restart.' >&2
        compose logs --no-color server caddy >&2 || true
        exit 1
    }
    sleep 2
done

printf '%s\n' 'Compose 2 CPU / 2 GiB startup and restart smoke passed.'
