#!/bin/sh
set -eu

BACKUP_PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
DOCKER_RUNTIME="$BACKUP_PROJECT_ROOT/scripts/docker-runtime.sh"

fail() {
    printf '%s\n' "backup: $*" >&2
    return 1
}

die() {
    FAILURE_REASON=$1
    exit 1
}

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

require_docker() {
    if [ -z "${DOCKER_BIN:-}" ]; then
        DOCKER_BIN=$(find_docker || true)
    fi
    [ -n "${DOCKER_BIN:-}" ] || fail 'Docker CLI was not found.'
}

docker_cmd() {
    require_docker
    "$DOCKER_RUNTIME" "$DOCKER_BIN" "$@"
}

load_environment() {
    ENV_FILE=${ENV_FILE:-"$BACKUP_PROJECT_ROOT/.env"}
    [ -f "$ENV_FILE" ] || fail "environment file does not exist: $ENV_FILE"
    ENV_FILE=$(CDPATH= cd -- "$(dirname -- "$ENV_FILE")" && pwd)/$(basename -- "$ENV_FILE")

    # The environment file is an operator-controlled local secret file. It is
    # never copied into a backup or written to an evidence file.
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a

    COMPOSE_FILE=${COMPOSE_FILE:-"$BACKUP_PROJECT_ROOT/compose.yaml"}
    COMPOSE_FILE=$(CDPATH= cd -- "$(dirname -- "$COMPOSE_FILE")" && pwd)/$(basename -- "$COMPOSE_FILE")
    COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-${COMPOSE_PROJECT:-jitong-im}}
    DB_NAME=${DB_NAME:-jitong}
    DB_USERNAME=${DB_USERNAME:-jitong}
    MINIO_BUCKET=${MINIO_BUCKET:-jitong-media}
    MINIO_ENDPOINT=${MINIO_ENDPOINT:-http://minio:9000}
    BACKUP_ROOT=${BACKUP_ROOT:-"$BACKUP_PROJECT_ROOT/backups"}
    EVIDENCE_ROOT=${EVIDENCE_ROOT:-"$BACKUP_PROJECT_ROOT/backup-evidence"}

    DB_PASSWORD=${DB_PASSWORD:-${POSTGRES_PASSWORD:-}}
    [ -n "$DB_PASSWORD" ] || fail 'DB_PASSWORD or POSTGRES_PASSWORD is required in the environment file.'
    [ -n "${MINIO_ROOT_USER:-}" ] || fail 'MINIO_ROOT_USER is required in the environment file.'
    [ -n "${MINIO_ROOT_PASSWORD:-}" ] \
        || fail 'MINIO_ROOT_PASSWORD is required in the environment file.'
    [ -n "${MINIO_BUCKET:-}" ] || fail 'MINIO_BUCKET is required in the environment file.'
    [ -n "${ADMIN_API_KEY:-}" ] || fail 'ADMIN_API_KEY is required in the environment file.'

    validate_safe_value COMPOSE_PROJECT_NAME
    validate_safe_value DB_NAME
    validate_safe_value DB_USERNAME
    validate_safe_value MINIO_BUCKET
}

validate_safe_value() {
    name=$1
    value=$(eval "printf '%s' \"\${$name}\"")
    case "$value" in
        ''|*[!A-Za-z0-9._-]*)
            fail "$name contains unsupported characters."
            ;;
    esac
}

compose_cmd() {
    docker_cmd compose \
        --project-name "$COMPOSE_PROJECT_NAME" \
        --project-directory "$BACKUP_PROJECT_ROOT" \
        --env-file "$ENV_FILE" \
        --file "$COMPOSE_FILE" \
        "$@"
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

utc_now() {
    date -u '+%Y-%m-%dT%H:%M:%SZ'
}

file_size() {
    wc -c < "$1" | tr -d ' '
}

hmac_sha256_file() {
    input=$1
    if ! command -v python3 >/dev/null 2>&1; then
        fail 'Python 3 is required to calculate backup HMACs.'
    fi
    python3 - "$BACKUP_KEY_FILE" "$input" <<'PY'
import hashlib
import hmac
import pathlib
import sys

key = pathlib.Path(sys.argv[1]).read_bytes()
content = pathlib.Path(sys.argv[2]).read_bytes()
print(hmac.new(key, content, hashlib.sha256).hexdigest())
PY
}

require_key_file() {
    BACKUP_KEY_FILE=${BACKUP_KEY_FILE:-}
    [ -n "$BACKUP_KEY_FILE" ] || fail \
        'BACKUP_KEY_FILE is required; create one with scripts/backup/init-key.sh.'
    [ -f "$BACKUP_KEY_FILE" ] || fail "backup key file does not exist: $BACKUP_KEY_FILE"

    if [ "$(uname -s)" = Darwin ]; then
        key_mode=$(stat -f '%Lp' "$BACKUP_KEY_FILE")
    else
        key_mode=$(stat -c '%a' "$BACKUP_KEY_FILE")
    fi
    case "$key_mode" in
        600|400|640|440) ;;
        *) fail "backup key file must be owner-readable only: $BACKUP_KEY_FILE" ;;
    esac
}

encrypt_file() {
    input=$1
    output=$2
    error_log=$3
    if ! openssl enc -aes-256-cbc \
        -salt \
        -pbkdf2 \
        -iter 600000 \
        -md sha256 \
        -pass "file:$BACKUP_KEY_FILE" \
        -in "$input" \
        -out "$output" \
        >/dev/null 2>"$error_log"; then
        return 1
    fi
}

decrypt_file() {
    input=$1
    output=$2
    error_log=$3
    if ! openssl enc -d -aes-256-cbc \
        -pbkdf2 \
        -iter 600000 \
        -md sha256 \
        -pass "file:$BACKUP_KEY_FILE" \
        -in "$input" \
        -out "$output" \
        >/dev/null 2>"$error_log"; then
        return 1
    fi
}

minio_network() {
    minio_container=$1
    docker_cmd inspect \
        --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' \
        "$minio_container" | sed -n '1p'
}

write_mc_config() {
    config_dir=$1
    alias_name=$2
    endpoint=$3
    access_key=$4
    secret_key=$5
    mkdir -p "$config_dir"
    chmod 700 "$config_dir"
    jq -n \
        --arg alias "$alias_name" \
        --arg endpoint "$endpoint" \
        --arg accessKey "$access_key" \
        --arg secretKey "$secret_key" \
        '{
            version: "10",
            aliases: {
                ($alias): {
                    url: $endpoint,
                    accessKey: $accessKey,
                    secretKey: $secretKey,
                    api: "S3v4",
                    path: "auto",
                    lookup: "auto"
                }
            }
        }' > "$config_dir/config.json"
    chmod 600 "$config_dir/config.json"
}

mc_cmd() {
    network=$1
    stage_dir=$2
    shift 2
    docker_cmd run \
        --rm \
        --network "$network" \
        --volume "$stage_dir:/backup" \
        --env MC_CONFIG_DIR=/backup/mc-config \
        --entrypoint mc \
        minio/mc:latest \
        "$@"
}

wait_for_service_health() {
    compose_command=$1
    service=$2
    timeout_seconds=$3
    started_at=$(date +%s)
    container=$(eval "$compose_command ps -q \"$service\"")
    [ -n "$container" ] || fail "container not found for service: $service"

    while :; do
        state=$(
            docker_cmd inspect \
                --format '{{.State.Status}}' \
                "$container" 2>/dev/null || printf '%s' stopped
        )
        [ "$state" = running ] || fail "$service container is $state"
        health=$(
            docker_cmd inspect \
                --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}running{{end}}' \
                "$container" 2>/dev/null || printf '%s' stopped
        )
        case "$health" in
            healthy|running)
                return 0
                ;;
            starting|unhealthy) ;;
            exited|dead|stopped)
                fail "$service container is $health"
                return 1
                ;;
        esac
        now=$(date +%s)
        if [ $((now - started_at)) -ge "$timeout_seconds" ]; then
            fail "$service did not become healthy within ${timeout_seconds}s"
            return 1
        fi
        sleep 2
    done
}

wait_for_http() {
    url=$1
    timeout_seconds=$2
    started_at=$(date +%s)
    while :; do
        if curl --fail --silent --max-time 5 "$url" >/dev/null 2>&1; then
            return 0
        fi
        now=$(date +%s)
        if [ $((now - started_at)) -ge "$timeout_seconds" ]; then
            fail "HTTP endpoint did not become ready within ${timeout_seconds}s: $url"
            return 1
        fi
        sleep 2
    done
}

manifest_value() {
    manifest=$1
    key=$2
    awk -F= -v wanted="$key" '
        $1 == wanted {
            sub(/^[^=]*=/, "", $0)
            print
            exit
        }
    ' "$manifest"
}

assert_manifest_has_no_secrets() {
    manifest=$1
    if grep -Eiq '(^|_)(password|secret|token|credential|api_key)(=|_)' "$manifest"; then
        fail "manifest appears to contain credentials: $manifest"
    fi
}

write_evidence() {
    evidence_file=$1
    shift
    mkdir -p "$EVIDENCE_ROOT"
    umask 077
    {
        printf '%s\n' 'evidence_version=1'
        for entry in "$@"; do
            printf '%s\n' "$entry"
        done
    } > "$evidence_file"
    chmod 600 "$evidence_file"
}
