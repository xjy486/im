#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

usage() {
    cat >&2 <<'EOF'
usage: restore-smoke.sh --key-file PATH [options]

Seed three users and an authorized C2C image in the running Compose stack,
create an encrypted backup, restore it into an isolated stack, and prove that
an authorized user can download the media while an outsider receives 403.
EOF
}

ENV_FILE=${ENV_FILE:-"$PROJECT_ROOT/.env"}
BACKUP_KEY_FILE=${BACKUP_KEY_FILE:-}
BACKUP_ROOT=${BACKUP_ROOT:-"$PROJECT_ROOT/backups"}
EVIDENCE_ROOT=${EVIDENCE_ROOT:-"$PROJECT_ROOT/backup-evidence"}
RESTORE_HTTP_PORT=${RESTORE_HTTP_PORT:-18080}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --env-file)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            ENV_FILE=$2
            shift 2
            ;;
        --key-file)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            BACKUP_KEY_FILE=$2
            shift 2
            ;;
        --output-dir)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            BACKUP_ROOT=$2
            shift 2
            ;;
        --evidence-dir)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            EVIDENCE_ROOT=$2
            shift 2
            ;;
        --http-port)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            RESTORE_HTTP_PORT=$2
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

load_environment
require_key_file
mkdir -p "$EVIDENCE_ROOT"
umask 077
SMOKE_EVIDENCE="$EVIDENCE_ROOT/restore-smoke-$(date -u '+%Y%m%dT%H%M%SZ')-$$.env"
SMOKE_STAGE="$PROJECT_ROOT/.restore-smoke-$$_$(date +%s)"
mkdir -m 700 "$SMOKE_STAGE"
SOURCE_PROJECT="$COMPOSE_PROJECT_NAME-restore-source-$(date -u '+%Y%m%d%H%M%S')-$$"
TARGET_PROJECT="$COMPOSE_PROJECT_NAME-restore-target-$(date -u '+%Y%m%d%H%M%S')-$$"
pick_free_port() {
    python3 - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}
SOURCE_PORT=$(pick_free_port)
if [ "$RESTORE_HTTP_PORT" = 0 ]; then
    RESTORE_HTTP_PORT=$(pick_free_port)
fi
SOURCE_ENV_FILE="$SMOKE_STAGE/source.env"
TARGET_ENV_FILE="$SMOKE_STAGE/target.env"
cp "$ENV_FILE" "$SOURCE_ENV_FILE"
{
    printf '\nJITONG_HTTP_PORT=%s\n' "$SOURCE_PORT"
    printf 'COMPOSE_PROJECT_NAME=%s\n' "$SOURCE_PROJECT"
} >> "$SOURCE_ENV_FILE"
chmod 600 "$SOURCE_ENV_FILE"
cp "$SOURCE_ENV_FILE" "$TARGET_ENV_FILE"
{
    printf '\nJITONG_HTTP_PORT=%s\n' "$RESTORE_HTTP_PORT"
    printf 'COMPOSE_PROJECT_NAME=%s\n' "$TARGET_PROJECT"
} >> "$TARGET_ENV_FILE"
chmod 600 "$TARGET_ENV_FILE"
ENV_FILE="$SOURCE_ENV_FILE"
COMPOSE_PROJECT_NAME="$SOURCE_PROJECT"
JITONG_HTTP_PORT="$SOURCE_PORT"
BASE_URL="http://127.0.0.1:$SOURCE_PORT"
TARGET_URL="http://127.0.0.1:$RESTORE_HTTP_PORT"

cleanup() {
    docker_cmd compose \
        --project-name "$SOURCE_PROJECT" \
        --project-directory "$PROJECT_ROOT" \
        --env-file "$SOURCE_ENV_FILE" \
        --file "$PROJECT_ROOT/compose.yaml" \
        down --volumes --remove-orphans >/dev/null 2>&1 || true
    docker_cmd compose \
        --project-name "$TARGET_PROJECT" \
        --project-directory "$PROJECT_ROOT" \
        --env-file "$TARGET_ENV_FILE" \
        --file "$PROJECT_ROOT/compose.yaml" \
        down --volumes --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$SMOKE_STAGE"
}
trap cleanup EXIT HUP INT TERM

compose_cmd up -d postgres minio >/dev/null 2>&1 \
    || { printf '%s\n' 'restore-smoke: source dependencies failed to start.' >&2; exit 1; }
wait_for_service_health compose_cmd postgres 180 \
    || { printf '%s\n' 'restore-smoke: source PostgreSQL failed health check.' >&2; exit 1; }
wait_for_service_health compose_cmd minio 180 \
    || { printf '%s\n' 'restore-smoke: source MinIO failed health check.' >&2; exit 1; }
compose_cmd up -d server >/dev/null 2>&1 \
    || { printf '%s\n' 'restore-smoke: source server failed to start.' >&2; exit 1; }
compose_cmd up -d --no-deps caddy >/dev/null 2>&1 \
    || { printf '%s\n' 'restore-smoke: source Caddy failed to start.' >&2; exit 1; }
wait_for_http "$BASE_URL/api/v1/system/health" 360 \
    || { printf '%s\n' 'restore-smoke: source HTTP endpoint failed.' >&2; exit 1; }

suffix=$(date -u '+%s')-$$
admin_headers="-H X-Admin-Api-Key:$ADMIN_API_KEY -H Content-Type:application/json"
password='correct horse battery staple'

create_user() {
    name=$1
    curl --fail --silent --show-error \
        $admin_headers \
        --data "{\"displayName\":\"$name-$suffix\",\"password\":\"$password\"}" \
        "$BASE_URL/api/v1/admin/users"
}

alice_json=$(create_user Alice)
bob_json=$(create_user Bob)
eve_json=$(create_user Eve)
alice_account=$(printf '%s' "$alice_json" | jq -r .accountNo)
bob_account=$(printf '%s' "$bob_json" | jq -r .accountNo)
eve_account=$(printf '%s' "$eve_json" | jq -r .accountNo)

login() {
    account=$1
    installation=$2
    curl --fail --silent --show-error \
        -H 'Content-Type: application/json' \
        --data "{\"accountNo\":\"$account\",\"password\":\"$password\",\"installationId\":\"$installation-$suffix\"}" \
        "$BASE_URL/api/v1/auth/login" | jq -r .accessToken
}

alice_token=$(login "$alice_account" alice)
bob_token=$(login "$bob_account" bob)
eve_token=$(login "$eve_account" eve)

request_json=$(curl --fail --silent --show-error \
    -H "Authorization: Bearer $alice_token" \
    -H 'Content-Type: application/json' \
    --data "{\"accountNo\":\"$bob_account\",\"verification\":\"\"}" \
    "$BASE_URL/api/v1/contact-requests")
request_id=$(printf '%s' "$request_json" | jq -r .requestId)
conversation_id=$(curl --fail --silent --show-error \
    -H "Authorization: Bearer $bob_token" \
    -X POST "$BASE_URL/api/v1/contact-requests/$request_id/accept" \
    | jq -r .conversationId)

image_file="$SMOKE_STAGE/image.png"
base64_decode() {
    if base64 --decode </dev/null >/dev/null 2>&1; then
        base64 --decode
    else
        base64 -D
    fi
}
printf '%s' \
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=' \
    | base64_decode > "$image_file"

upload_json=$(curl --fail --silent --show-error \
    -H "Authorization: Bearer $alice_token" \
    -F "uploadId=$(uuidgen)" \
    -F "file=@$image_file;type=image/png" \
    "$BASE_URL/api/v1/media/images")
media_id=$(printf '%s' "$upload_json" | jq -r .mediaId)

message_json=$(curl --fail --silent --show-error \
    -H "Authorization: Bearer $alice_token" \
    -H 'Content-Type: application/json' \
    --data "{\"clientMsgId\":\"$(uuidgen)\",\"type\":\"IMAGE\",\"mediaId\":\"$media_id\"}" \
    "$BASE_URL/api/v1/conversations/$conversation_id/messages")
printf '%s' "$message_json" | jq -e --arg media "$media_id" '.mediaId == $media' >/dev/null

create_output=$("$SCRIPT_DIR/create.sh" \
    --env-file "$SOURCE_ENV_FILE" \
    --key-file "$BACKUP_KEY_FILE" \
    --output-dir "$BACKUP_ROOT" \
    --evidence-dir "$EVIDENCE_ROOT")
backup_dir=$(printf '%s\n' "$create_output" | sed -n 's/^Encrypted backup created: //p')
[ -n "$backup_dir" ] || { printf '%s\n' 'restore-smoke: backup path missing.' >&2; exit 1; }

"$SCRIPT_DIR/verify.sh" \
    --backup-dir "$backup_dir" \
    --key-file "$BACKUP_KEY_FILE" \
    --evidence-dir "$EVIDENCE_ROOT" >/dev/null

"$SCRIPT_DIR/restore.sh" \
    --backup-dir "$backup_dir" \
    --env-file "$TARGET_ENV_FILE" \
    --key-file "$BACKUP_KEY_FILE" \
    --target-project "$TARGET_PROJECT" \
    --http-port "$RESTORE_HTTP_PORT" \
    --evidence-dir "$EVIDENCE_ROOT" >/dev/null

authorized_status=$(curl --silent --output "$SMOKE_STAGE/authorized-media" \
    --max-time 15 \
    --write-out '%{http_code}' \
    -H "Authorization: Bearer $alice_token" \
    "$TARGET_URL/api/v1/media/$media_id")
peer_status=$(curl --silent --output "$SMOKE_STAGE/peer-media" \
    --max-time 15 \
    --write-out '%{http_code}' \
    -H "Authorization: Bearer $bob_token" \
    "$TARGET_URL/api/v1/media/$media_id")
outsider_status=$(curl --silent --output "$SMOKE_STAGE/outsider.json" \
    --max-time 15 \
    --write-out '%{http_code}' \
    -H "Authorization: Bearer $eve_token" \
    "$TARGET_URL/api/v1/media/$media_id")

[ "$authorized_status" = 200 ] || { printf '%s\n' 'restore-smoke: uploader download was not authorized.' >&2; exit 1; }
[ "$peer_status" = 200 ] || { printf '%s\n' 'restore-smoke: conversation peer download failed.' >&2; exit 1; }
[ "$outsider_status" = 403 ] || { printf '%s\n' 'restore-smoke: outsider was not denied.' >&2; exit 1; }
[ -s "$SMOKE_STAGE/authorized-media" ] || { printf '%s\n' 'restore-smoke: restored media is empty.' >&2; exit 1; }

write_evidence "$SMOKE_EVIDENCE" \
    'operation=restore-smoke' \
    'status=PASS' \
    'authorized_uploader_download=PASS' \
    'authorized_peer_download=PASS' \
    'unauthorized_download=403' \
    "backup_dir=$backup_dir" \
    "target_project=$TARGET_PROJECT" \
    "source_user_count=3" \
    "media_id=$media_id" \
    "credentials_included=false" \
    "completed_at=$(utc_now)"

printf '%s\n' 'Backup/restore smoke passed.'
printf '%s\n' "Evidence: $SMOKE_EVIDENCE"
