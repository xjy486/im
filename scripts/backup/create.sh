#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

usage() {
    cat >&2 <<'EOF'
usage: create.sh --key-file PATH [options]

Create an encrypted PostgreSQL dump and an encrypted MinIO object archive from
the running Compose project. Credentials are read from --env-file and never
written to the backup or evidence.

options:
  --env-file PATH       Local Compose environment file (default: .env)
  --key-file PATH       Owner-readable OpenSSL passphrase file (required)
  --project NAME        Compose project name (default: jitong-im)
  --output-dir PATH     Backup output directory (default: ./backups)
  --evidence-dir PATH   Evidence directory (default: ./backup-evidence)
EOF
}

ENV_FILE=${ENV_FILE:-}
BACKUP_KEY_FILE=${BACKUP_KEY_FILE:-}
COMPOSE_FILE=${COMPOSE_FILE:-}
COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-}
BACKUP_ROOT=${BACKUP_ROOT:-}
EVIDENCE_ROOT=${EVIDENCE_ROOT:-}

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
        --compose-file)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            COMPOSE_FILE=$2
            shift 2
            ;;
        --project)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            COMPOSE_PROJECT_NAME=$2
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

FAILURE_REASON=not_started
BACKUP_DIR=
STAGE_DIR=
STARTED_AT=$(utc_now)
EVIDENCE_ROOT=${EVIDENCE_ROOT:-"$BACKUP_PROJECT_ROOT/backup-evidence"}
mkdir -p "$EVIDENCE_ROOT"
EVIDENCE_FILE="$EVIDENCE_ROOT/create-$(date -u '+%Y%m%dT%H%M%SZ')-$$.env"

finish() {
    status=$?
    if [ "$status" -eq 0 ]; then
        outcome=PASS
        reason=completed
    else
        outcome=FAIL
        reason=${FAILURE_REASON:-command_failed}
    fi

    if [ -n "$EVIDENCE_FILE" ]; then
        write_evidence "$EVIDENCE_FILE" \
            "operation=create" \
            "status=$outcome" \
            "reason=$reason" \
            "started_at=$STARTED_AT" \
            "completed_at=$(utc_now)" \
            "backup_dir=${BACKUP_DIR:-unavailable}" \
            "credentials_included=false" \
            "encryption=openssl-aes-256-cbc-pbkdf2" \
            "key_in_backup=false"
    fi

    if [ -n "$STAGE_DIR" ] && [ -d "$STAGE_DIR" ]; then
        rm -rf "$STAGE_DIR"
    fi
    exit "$status"
}
trap finish EXIT HUP INT TERM

load_environment
require_key_file
mkdir -p "$BACKUP_ROOT" "$EVIDENCE_ROOT"
chmod 700 "$BACKUP_ROOT" "$EVIDENCE_ROOT"

backup_id=$(date -u '+%Y%m%dT%H%M%SZ')-$(openssl rand -hex 4)
STAGE_DIR="$BACKUP_PROJECT_ROOT/.backup-work-$backup_id"
BACKUP_DIR="$BACKUP_ROOT/$backup_id"
mkdir -m 700 "$STAGE_DIR"

if ! compose_cmd exec -T postgres true >/dev/null 2>"$STAGE_DIR/runtime.log"; then
    FAILURE_REASON=postgres_not_running
    die "$FAILURE_REASON"
fi
if ! compose_cmd exec -T minio true >/dev/null 2>>"$STAGE_DIR/runtime.log"; then
    FAILURE_REASON=minio_not_running
    die "$FAILURE_REASON"
fi

if ! compose_cmd exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" pg_dump \
        --format=custom \
        --no-owner \
        --no-privileges \
        --host=127.0.0.1 \
        --username="$1" \
        --dbname="$2"
' sh "$DB_USERNAME" "$DB_NAME" \
    > "$STAGE_DIR/postgres.dump" \
    2> "$STAGE_DIR/postgres.dump.log"; then
    FAILURE_REASON=postgres_dump_failed
    die "$FAILURE_REASON"
fi

minio_container=$(compose_cmd ps -q minio)
[ -n "$minio_container" ] || die minio_container_not_found
MINIO_NETWORK=$(minio_network "$minio_container")
[ -n "$MINIO_NETWORK" ] || die minio_network_not_found
mkdir -m 700 "$STAGE_DIR/mc-config" "$STAGE_DIR/minio-objects"

write_mc_config "$STAGE_DIR/mc-config" source \
    "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
if ! mc_cmd "$MINIO_NETWORK" "$STAGE_DIR" \
    ls --recursive --json "source/$MINIO_BUCKET" \
    > "$STAGE_DIR/minio-listing.jsonl" 2> "$STAGE_DIR/mc.log"; then
    FAILURE_REASON=minio_listing_failed
    die "$FAILURE_REASON"
fi
if [ -s "$STAGE_DIR/minio-listing.jsonl" ] \
    && ! mc_cmd "$MINIO_NETWORK" "$STAGE_DIR" \
        mirror --quiet --overwrite "source/$MINIO_BUCKET" /backup/minio-objects \
        >> "$STAGE_DIR/mc.log" 2>&1; then
    FAILURE_REASON=minio_mirror_failed
    die "$FAILURE_REASON"
fi

tar -cf "$STAGE_DIR/minio-objects.tar" -C "$STAGE_DIR/minio-objects" . \
    > /dev/null 2> "$STAGE_DIR/tar.log" \
    || die minio_archive_failed

mkdir -m 700 "$STAGE_DIR/encrypted"
encrypt_file "$STAGE_DIR/postgres.dump" \
    "$STAGE_DIR/encrypted/postgres.dump.enc" \
    "$STAGE_DIR/openssl-postgres.log" \
    || die postgres_encryption_failed
encrypt_file "$STAGE_DIR/minio-objects.tar" \
    "$STAGE_DIR/encrypted/minio-objects.tar.enc" \
    "$STAGE_DIR/openssl-minio.log" \
    || die minio_encryption_failed

users_count=$(compose_cmd exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" psql \
        --host=127.0.0.1 --username="$1" --dbname="$2" \
        --tuples-only --no-align --command="SELECT count(*) FROM users"
' sh "$DB_USERNAME" "$DB_NAME" \
    2> "$STAGE_DIR/counts.log" | tr -d '[:space:]') || die database_count_failed
messages_count=$(compose_cmd exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" psql \
        --host=127.0.0.1 --username="$1" --dbname="$2" \
        --tuples-only --no-align --command="SELECT count(*) FROM messages"
' sh "$DB_USERNAME" "$DB_NAME" \
    2>> "$STAGE_DIR/counts.log" | tr -d '[:space:]') || die database_count_failed
bound_media_count=$(compose_cmd exec -T postgres sh -ceu '
    PGPASSWORD="$POSTGRES_PASSWORD" psql \
        --host=127.0.0.1 --username="$1" --dbname="$2" \
        --tuples-only --no-align --command="SELECT count(*) FROM media WHERE state = '\''BOUND'\''"
' sh "$DB_USERNAME" "$DB_NAME" \
    2>> "$STAGE_DIR/counts.log" | tr -d '[:space:]') || die database_count_failed
object_count=$(find "$STAGE_DIR/minio-objects" -type f -print | wc -l | tr -d ' ')

mv "$STAGE_DIR/encrypted/postgres.dump.enc" "$STAGE_DIR/postgres.dump.enc"
mv "$STAGE_DIR/encrypted/minio-objects.tar.enc" "$STAGE_DIR/minio-objects.tar.enc"

cat > "$STAGE_DIR/manifest.env" <<EOF
manifest_version=1
created_at=$(utc_now)
source_project=$COMPOSE_PROJECT_NAME
database_name=$DB_NAME
database_username=$DB_USERNAME
minio_bucket=$MINIO_BUCKET
crypto=openssl-aes-256-cbc-pbkdf2
postgres_file=postgres.dump.enc
minio_file=minio-objects.tar.enc
postgres_bytes=$(file_size "$STAGE_DIR/postgres.dump.enc")
minio_bytes=$(file_size "$STAGE_DIR/minio-objects.tar.enc")
users_count=$users_count
messages_count=$messages_count
bound_media_count=$bound_media_count
object_count=$object_count
credentials_included=false
key_in_backup=false
EOF

postgres_hmac=$(hmac_sha256_file "$STAGE_DIR/postgres.dump.enc")
minio_hmac=$(hmac_sha256_file "$STAGE_DIR/minio-objects.tar.enc")
printf '%s\n' "postgres_hmac=$postgres_hmac" "minio_hmac=$minio_hmac" \
    >> "$STAGE_DIR/manifest.env"
manifest_hmac=$(hmac_sha256_file "$STAGE_DIR/manifest.env")
printf '%s\n' "$manifest_hmac" > "$STAGE_DIR/manifest.hmac"
chmod 600 "$STAGE_DIR/manifest.hmac"

assert_manifest_has_no_secrets "$STAGE_DIR/manifest.env"
{
    printf '%s  %s\n' "$(sha256_file "$STAGE_DIR/postgres.dump.enc")" postgres.dump.enc
    printf '%s  %s\n' "$(sha256_file "$STAGE_DIR/minio-objects.tar.enc")" minio-objects.tar.enc
    printf '%s  %s\n' "$(sha256_file "$STAGE_DIR/manifest.env")" manifest.env
    printf '%s  %s\n' "$(sha256_file "$STAGE_DIR/manifest.hmac")" manifest.hmac
} > "$STAGE_DIR/checksums.sha256"
printf '%s\n' "$(hmac_sha256_file "$STAGE_DIR/checksums.sha256")" \
    > "$STAGE_DIR/checksums.hmac"
chmod 600 "$STAGE_DIR/checksums.hmac"

rm -rf "$STAGE_DIR/mc-config" "$STAGE_DIR/minio-objects" "$STAGE_DIR/encrypted"
rm -f "$STAGE_DIR/postgres.dump" "$STAGE_DIR/minio-objects.tar" \
    "$STAGE_DIR/runtime.log" "$STAGE_DIR/postgres.dump.log" "$STAGE_DIR/mc.log" \
    "$STAGE_DIR/minio-listing.jsonl" \
    "$STAGE_DIR/tar.log" "$STAGE_DIR/openssl-postgres.log" \
    "$STAGE_DIR/openssl-minio.log" "$STAGE_DIR/counts.log"

mv "$STAGE_DIR" "$BACKUP_DIR"
STAGE_DIR=
chmod 700 "$BACKUP_DIR"
chmod 600 "$BACKUP_DIR"/*

printf '%s\n' "Encrypted backup created: $BACKUP_DIR"
printf '%s\n' "Evidence: $EVIDENCE_FILE"
