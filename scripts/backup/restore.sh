#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

usage() {
    cat >&2 <<'EOF'
usage: restore.sh --backup-dir PATH --env-file PATH --key-file PATH [options]

Restore an encrypted backup into a new, isolated Compose project. The target
project is kept for inspection unless --cleanup is supplied.

options:
  --backup-dir PATH     Backup directory created by create.sh (required)
  --env-file PATH       Target environment file with fresh/local credentials
  --key-file PATH       Owner-readable backup key (required)
  --target-project NAME Isolated Compose project name
  --http-port PORT      Loopback port for the restored Caddy service
  --replace             Remove an existing target project and its volumes
  --cleanup             Remove target containers and volumes after verification
  --evidence-dir PATH   Evidence directory
EOF
}

BACKUP_DIR=
ENV_FILE=${ENV_FILE:-}
BACKUP_KEY_FILE=${BACKUP_KEY_FILE:-}
COMPOSE_FILE=${COMPOSE_FILE:-}
TARGET_PROJECT=${TARGET_PROJECT:-}
RESTORE_HTTP_PORT=${RESTORE_HTTP_PORT:-}
REPLACE_TARGET=0
CLEANUP_TARGET=0
EVIDENCE_ROOT=${EVIDENCE_ROOT:-}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --backup-dir)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            BACKUP_DIR=$2
            shift 2
            ;;
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
        --compose-file)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            COMPOSE_FILE=$2
            shift 2
            ;;
        --target-project)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            TARGET_PROJECT=$2
            shift 2
            ;;
        --http-port)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            RESTORE_HTTP_PORT=$2
            shift 2
            ;;
        --replace)
            REPLACE_TARGET=1
            shift
            ;;
        --cleanup)
            CLEANUP_TARGET=1
            shift
            ;;
        --evidence-dir)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            EVIDENCE_ROOT=$2
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

EVIDENCE_ROOT=${EVIDENCE_ROOT:-"$BACKUP_PROJECT_ROOT/backup-evidence"}
mkdir -p "$EVIDENCE_ROOT"
EVIDENCE_FILE="$EVIDENCE_ROOT/restore-$(date -u '+%Y%m%dT%H%M%SZ')-$$.env"
FAILURE_REASON=not_started
STARTED_AT=$(utc_now)
TARGET_PROJECT=${TARGET_PROJECT:-unavailable}
RESTORE_HTTP_PORT=${RESTORE_HTTP_PORT:-unavailable}
STAGE_DIR="$BACKUP_PROJECT_ROOT/.restore-work-$TARGET_PROJECT"
TARGET_ENV_FILE="$STAGE_DIR/target.env"
TARGET_STARTED=0

compose_target() {
    docker_cmd compose \
        --project-name "$TARGET_PROJECT" \
        --project-directory "$BACKUP_PROJECT_ROOT" \
        --env-file "$TARGET_ENV_FILE" \
        --file "$COMPOSE_FILE" \
        "$@"
}

finish() {
    status=$?
    if [ "$status" -eq 0 ]; then
        outcome=PASS
        reason=completed
    else
        outcome=FAIL
        reason=${FAILURE_REASON:-restore_failed}
    fi

    if [ "$CLEANUP_TARGET" -eq 1 ] && [ "$TARGET_STARTED" -eq 1 ]; then
        compose_target down --volumes --remove-orphans >/dev/null 2>&1 || true
        cleanup=PASS
    else
        cleanup=KEPT
    fi

    write_evidence "$EVIDENCE_FILE" \
        "operation=restore" \
        "status=$outcome" \
        "reason=$reason" \
        "started_at=$STARTED_AT" \
        "completed_at=$(utc_now)" \
        "backup_dir=$BACKUP_DIR" \
        "target_project=$TARGET_PROJECT" \
        "restore_http_port=$RESTORE_HTTP_PORT" \
        "health_url=http://127.0.0.1:$RESTORE_HTTP_PORT/api/v1/system/health" \
        "target_cleanup=$cleanup" \
        "credentials_included=false"
    rm -rf "$STAGE_DIR"
    exit "$status"
}
trap finish EXIT HUP INT TERM

[ -n "$BACKUP_DIR" ] || { usage; exit 2; }
[ -n "$ENV_FILE" ] || { usage; exit 2; }
[ -n "$BACKUP_KEY_FILE" ] || { usage; exit 2; }
[ -d "$BACKUP_DIR" ] || die backup_directory_missing
BACKUP_DIR=$(CDPATH= cd -- "$BACKUP_DIR" && pwd)

load_environment
require_key_file
validate_safe_value COMPOSE_PROJECT_NAME

TARGET_PROJECT=${TARGET_PROJECT:-"$COMPOSE_PROJECT_NAME-restore-$(date -u '+%Y%m%d%H%M%S')-$$"}
RESTORE_HTTP_PORT=${RESTORE_HTTP_PORT:-18080}
case "$RESTORE_HTTP_PORT" in
    *[!0-9]*|'') fail 'restore HTTP port must be numeric.' ;;
esac
validate_safe_value TARGET_PROJECT
JITONG_HTTP_PORT=$RESTORE_HTTP_PORT
export JITONG_HTTP_PORT

MANIFEST="$BACKUP_DIR/manifest.env"
CHECKSUMS="$BACKUP_DIR/checksums.sha256"
[ -f "$MANIFEST" ] || die manifest_missing
[ -f "$CHECKSUMS" ] || die checksums_missing
[ -f "$BACKUP_DIR/manifest.hmac" ] || die manifest_hmac_missing
[ -f "$BACKUP_DIR/checksums.hmac" ] || die checksums_hmac_missing
assert_manifest_has_no_secrets "$MANIFEST"

STAGE_DIR="$BACKUP_PROJECT_ROOT/.restore-work-$TARGET_PROJECT"
TARGET_ENV_FILE="$STAGE_DIR/target.env"

if [ "$REPLACE_TARGET" -eq 1 ]; then
    # The target environment file is generated below, so this command only
    # removes containers belonging to the explicitly named restore project.
    COMPOSE_PROJECT_NAME=$TARGET_PROJECT
    if compose_cmd ps -aq | grep -q .; then
        compose_cmd down --volumes --remove-orphans >/dev/null 2>&1 \
            || die target_cleanup_failed
    fi
fi

if [ "$REPLACE_TARGET" -eq 0 ]; then
    COMPOSE_PROJECT_NAME=$TARGET_PROJECT
    if compose_cmd ps -aq | grep -q .; then
        FAILURE_REASON=target_project_exists
        die "$FAILURE_REASON"
    fi
fi

mkdir -m 700 "$STAGE_DIR"
mkdir -m 700 "$STAGE_DIR/minio-objects"
cp "$ENV_FILE" "$TARGET_ENV_FILE"
chmod 600 "$TARGET_ENV_FILE"
{
    printf '\n# Generated for restore target; do not commit.\n'
    printf 'JITONG_HTTP_PORT=%s\n' "$RESTORE_HTTP_PORT"
    printf 'COMPOSE_PROJECT_NAME=%s\n' "$TARGET_PROJECT"
} >> "$TARGET_ENV_FILE"

COMPOSE_PROJECT_NAME=$TARGET_PROJECT

if ! (cd "$BACKUP_DIR" && sha256sum -c checksums.sha256 >/dev/null 2>"$STAGE_DIR/checksum.log"); then
    if ! (cd "$BACKUP_DIR" && shasum -a 256 -c checksums.sha256 >/dev/null 2>>"$STAGE_DIR/checksum.log"); then
        FAILURE_REASON=checksum_mismatch
        die "$FAILURE_REASON"
    fi
fi

checksums_hmac=$(hmac_sha256_file "$CHECKSUMS")
[ "$checksums_hmac" = "$(tr -d '[:space:]' < "$BACKUP_DIR/checksums.hmac")" ] || {
    FAILURE_REASON=checksums_authentication_failed
    die "$FAILURE_REASON"
}
manifest_hmac=$(hmac_sha256_file "$MANIFEST")
[ "$manifest_hmac" = "$(tr -d '[:space:]' < "$BACKUP_DIR/manifest.hmac")" ] || {
    FAILURE_REASON=manifest_authentication_failed
    die "$FAILURE_REASON"
}
postgres_hmac=$(hmac_sha256_file "$BACKUP_DIR/$(manifest_value "$MANIFEST" postgres_file)")
[ "$postgres_hmac" = "$(manifest_value "$MANIFEST" postgres_hmac)" ] || {
    FAILURE_REASON=postgres_authentication_failed
    die "$FAILURE_REASON"
}
minio_hmac=$(hmac_sha256_file "$BACKUP_DIR/$(manifest_value "$MANIFEST" minio_file)")
[ "$minio_hmac" = "$(manifest_value "$MANIFEST" minio_hmac)" ] || {
    FAILURE_REASON=minio_authentication_failed
    die "$FAILURE_REASON"
}

decrypt_file "$BACKUP_DIR/$(manifest_value "$MANIFEST" postgres_file)" \
    "$STAGE_DIR/postgres.dump" "$STAGE_DIR/postgres-decrypt.log" \
    || {
        FAILURE_REASON=postgres_decryption_failed
        die "$FAILURE_REASON"
    }
decrypt_file "$BACKUP_DIR/$(manifest_value "$MANIFEST" minio_file)" \
    "$STAGE_DIR/minio-objects.tar" "$STAGE_DIR/minio-decrypt.log" \
    || {
        FAILURE_REASON=minio_decryption_failed
        die "$FAILURE_REASON"
    }
tar -xf "$STAGE_DIR/minio-objects.tar" -C "$STAGE_DIR/minio-objects" \
    > /dev/null 2> "$STAGE_DIR/tar.log" \
    || {
        FAILURE_REASON=minio_archive_invalid
        die "$FAILURE_REASON"
    }

if ! compose_target up -d postgres minio > "$STAGE_DIR/compose-start.log" 2>&1; then
    FAILURE_REASON=dependency_start_failed
    die "$FAILURE_REASON"
fi
TARGET_STARTED=1
if ! wait_for_service_health compose_target postgres 180 \
    || ! wait_for_service_health compose_target minio 180; then
    FAILURE_REASON=dependency_health_failed
    die "$FAILURE_REASON"
fi

if ! compose_target cp "$STAGE_DIR/postgres.dump" postgres:/tmp/jitong-restore.dump \
    > "$STAGE_DIR/pg-copy.log" 2>&1; then
    FAILURE_REASON=postgres_dump_copy_failed
    die "$FAILURE_REASON"
fi
if ! compose_target exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" pg_restore \
        --exit-on-error \
        --no-owner \
        --no-privileges \
        --clean \
        --if-exists \
        --host=127.0.0.1 \
        --username="$1" \
        --dbname="$2" \
        /tmp/jitong-restore.dump
    rm -f /tmp/jitong-restore.dump
' sh "$DB_USERNAME" "$DB_NAME" \
    > "$STAGE_DIR/pg-restore.log" 2>&1; then
    FAILURE_REASON=postgres_restore_failed
    die "$FAILURE_REASON"
fi

target_minio_container=$(compose_target ps -q minio)
[ -n "$target_minio_container" ] || die target_minio_not_found
TARGET_MINIO_NETWORK=$(minio_network "$target_minio_container")
[ -n "$TARGET_MINIO_NETWORK" ] || die target_minio_network_not_found
mkdir -m 700 "$STAGE_DIR/mc-config"

write_mc_config "$STAGE_DIR/mc-config" target \
    "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
if ! mc_cmd "$TARGET_MINIO_NETWORK" "$STAGE_DIR" \
    mb --ignore-existing "target/$MINIO_BUCKET" \
    >> "$STAGE_DIR/mc.log" 2>&1; then
    FAILURE_REASON=target_minio_bucket_failed
    die "$FAILURE_REASON"
fi
if find "$STAGE_DIR/minio-objects" -type f -print -quit | grep -q . \
    && ! mc_cmd "$TARGET_MINIO_NETWORK" "$STAGE_DIR" \
        mirror --quiet --overwrite /backup/minio-objects "target/$MINIO_BUCKET" \
        >> "$STAGE_DIR/mc.log" 2>&1; then
    FAILURE_REASON=target_minio_restore_failed
    die "$FAILURE_REASON"
fi
target_object_count=$(mc_cmd "$TARGET_MINIO_NETWORK" "$STAGE_DIR" \
    ls --recursive --json "target/$MINIO_BUCKET" \
    > "$STAGE_DIR/target-minio-listing.jsonl" 2>> "$STAGE_DIR/mc.log" \
    && jq -s '[.[] | select(.type == "file")] | length' \
        "$STAGE_DIR/target-minio-listing.jsonl") \
    || die target_minio_listing_failed

if ! compose_target up -d server > "$STAGE_DIR/service-start.log" 2>&1; then
    FAILURE_REASON=service_start_failed
    die "$FAILURE_REASON"
fi
if ! compose_target up -d --no-deps caddy >> "$STAGE_DIR/service-start.log" 2>&1; then
    FAILURE_REASON=caddy_start_failed
    die "$FAILURE_REASON"
fi

health_url="http://127.0.0.1:$RESTORE_HTTP_PORT/api/v1/system/health"
if ! wait_for_http "$health_url" 360; then
    FAILURE_REASON=service_health_failed
    die "$FAILURE_REASON"
fi
curl --fail --silent --show-error "$health_url" > "$STAGE_DIR/health.json" \
    2> "$STAGE_DIR/health.log"

restored_users=$(compose_target exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" psql \
        --host=127.0.0.1 --username="$1" --dbname="$2" \
        --tuples-only --no-align --command="SELECT count(*) FROM users"
' sh "$DB_USERNAME" "$DB_NAME" \
    2> "$STAGE_DIR/counts.log" | tr -d '[:space:]') || die restored_count_failed
restored_messages=$(compose_target exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" psql \
        --host=127.0.0.1 --username="$1" --dbname="$2" \
        --tuples-only --no-align --command="SELECT count(*) FROM messages"
' sh "$DB_USERNAME" "$DB_NAME" \
    2>> "$STAGE_DIR/counts.log" | tr -d '[:space:]') || die restored_count_failed
restored_bound_media=$(compose_target exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" psql \
        --host=127.0.0.1 --username="$1" --dbname="$2" \
        --tuples-only --no-align --command="SELECT count(*) FROM media WHERE state = '\''BOUND'\''"
' sh "$DB_USERNAME" "$DB_NAME" \
    2>> "$STAGE_DIR/counts.log" | tr -d '[:space:]') || die restored_count_failed

[ "$restored_users" = "$(manifest_value "$MANIFEST" users_count)" ] \
    || die restored_user_count_mismatch
[ "$restored_messages" = "$(manifest_value "$MANIFEST" messages_count)" ] \
    || die restored_message_count_mismatch
[ "$restored_bound_media" = "$(manifest_value "$MANIFEST" bound_media_count)" ] \
    || die restored_media_count_mismatch
[ "$target_object_count" = "$(manifest_value "$MANIFEST" object_count)" ] \
    || die restored_object_count_mismatch

rm -rf "$STAGE_DIR/mc-config" "$STAGE_DIR/minio-objects"
rm -f "$STAGE_DIR/postgres.dump" "$STAGE_DIR/minio-objects.tar" \
    "$STAGE_DIR/health.json" "$STAGE_DIR/health.log" "$STAGE_DIR/compose-start.log" \
    "$STAGE_DIR/service-start.log" "$STAGE_DIR/pg-copy.log" "$STAGE_DIR/pg-restore.log" "$STAGE_DIR/mc.log" \
    "$STAGE_DIR/target-minio-listing.jsonl" \
    "$STAGE_DIR/counts.log" "$STAGE_DIR/checksum.log" "$STAGE_DIR/postgres-decrypt.log" \
    "$STAGE_DIR/minio-decrypt.log" "$STAGE_DIR/tar.log"

printf '%s\n' "Restore completed: $TARGET_PROJECT"
printf '%s\n' "Health URL: $health_url"
printf '%s\n' "Evidence: $EVIDENCE_FILE"
