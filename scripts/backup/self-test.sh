#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/jitong-backup-self-test.XXXXXX")
KEY_FILE="$TEST_ROOT/backup.key"
BACKUP_DIR="$TEST_ROOT/backup"
EVIDENCE_DIR="$TEST_ROOT/evidence"
mkdir -m 700 "$BACKUP_DIR" "$EVIDENCE_DIR"

cleanup() {
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

"$SCRIPT_DIR/init-key.sh" --key-file "$KEY_FILE" >/dev/null
printf '%s\n' 'self-test payload' > "$TEST_ROOT/postgres.dump"
tar -cf "$TEST_ROOT/minio-objects.tar" -C "$TEST_ROOT" postgres.dump

BACKUP_KEY_FILE=$KEY_FILE
encrypt_file "$TEST_ROOT/postgres.dump" "$BACKUP_DIR/postgres.dump.enc" "$TEST_ROOT/openssl.log"
encrypt_file "$TEST_ROOT/minio-objects.tar" "$BACKUP_DIR/minio-objects.tar.enc" "$TEST_ROOT/openssl.log"

postgres_hmac=$(hmac_sha256_file "$BACKUP_DIR/postgres.dump.enc")
minio_hmac=$(hmac_sha256_file "$BACKUP_DIR/minio-objects.tar.enc")
cat > "$BACKUP_DIR/manifest.env" <<EOF
manifest_version=1
created_at=2026-08-24T00:00:00Z
source_project=self-test
database_name=jitong
database_username=jitong
minio_bucket=jitong-media
crypto=openssl-aes-256-cbc-pbkdf2
postgres_file=postgres.dump.enc
minio_file=minio-objects.tar.enc
postgres_bytes=$(file_size "$BACKUP_DIR/postgres.dump.enc")
minio_bytes=$(file_size "$BACKUP_DIR/minio-objects.tar.enc")
users_count=1
messages_count=1
bound_media_count=1
object_count=1
credentials_included=false
key_in_backup=false
postgres_hmac=$postgres_hmac
minio_hmac=$minio_hmac
EOF
printf '%s\n' "$(hmac_sha256_file "$BACKUP_DIR/manifest.env")" > "$BACKUP_DIR/manifest.hmac"
(
    cd "$BACKUP_DIR"
    sha256sum postgres.dump.enc minio-objects.tar.enc manifest.env manifest.hmac \
        > checksums.sha256
)
printf '%s\n' "$(hmac_sha256_file "$BACKUP_DIR/checksums.sha256")" \
    > "$BACKUP_DIR/checksums.hmac"

"$SCRIPT_DIR/verify.sh" \
    --backup-dir "$BACKUP_DIR" \
    --key-file "$KEY_FILE" \
    --evidence-dir "$EVIDENCE_DIR" >/dev/null

chmod 644 "$KEY_FILE"
if "$SCRIPT_DIR/verify.sh" \
    --backup-dir "$BACKUP_DIR" \
    --key-file "$KEY_FILE" \
    --evidence-dir "$EVIDENCE_DIR" >/dev/null 2>&1; then
    printf '%s\n' 'self-test: insecure key permissions unexpectedly passed.' >&2
    exit 1
fi
chmod 600 "$KEY_FILE"
failure_evidence=$(ls -t "$EVIDENCE_DIR"/verify-*.env | head -1)
grep -q '^status=FAIL$' "$failure_evidence"

printf '%s\n' 'object_count=2' >> "$BACKUP_DIR/manifest.env"
(
    cd "$BACKUP_DIR"
    sha256sum manifest.env | awk '{print $1"  "$2}' > "$TEST_ROOT/manifest-checksum"
    awk '
        $2 == "manifest.env" {
            getline replacement < "'"$TEST_ROOT/manifest-checksum"'"
            print replacement
            next
        }
        { print }
    ' checksums.sha256 > checksums.sha256.new
    mv checksums.sha256.new checksums.sha256
)
if "$SCRIPT_DIR/verify.sh" \
    --backup-dir "$BACKUP_DIR" \
    --key-file "$KEY_FILE" \
    --evidence-dir "$EVIDENCE_DIR" >/dev/null 2>&1; then
    printf '%s\n' 'self-test: manifest tampering unexpectedly passed.' >&2
    exit 1
fi

printf '%s\n' 'Backup self-test passed.'
