#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

usage() {
    cat >&2 <<'EOF'
usage: verify.sh --backup-dir PATH --key-file PATH [options]

Verify encrypted backup checksums, decryptability, archive structure, and the
absence of credential fields from the manifest.
EOF
}

BACKUP_DIR=
BACKUP_KEY_FILE=${BACKUP_KEY_FILE:-}
EVIDENCE_ROOT=${EVIDENCE_ROOT:-}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --backup-dir)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            BACKUP_DIR=$2
            shift 2
            ;;
        --key-file)
            [ "$#" -ge 2 ] || { usage; exit 2; }
            BACKUP_KEY_FILE=$2
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

EVIDENCE_ROOT=${EVIDENCE_ROOT:-"$BACKUP_PROJECT_ROOT/backup-evidence"}
mkdir -p "$EVIDENCE_ROOT"
EVIDENCE_FILE="$EVIDENCE_ROOT/verify-$(date -u '+%Y%m%dT%H%M%SZ')-$$.env"
FAILURE_REASON=not_started
STARTED_AT=$(utc_now)
STAGE_DIR=

finish() {
    status=$?
    if [ "$status" -eq 0 ]; then
        outcome=PASS
        reason=completed
    else
        outcome=FAIL
        reason=${FAILURE_REASON:-verification_failed}
    fi
    write_evidence "$EVIDENCE_FILE" \
        "operation=verify" \
        "status=$outcome" \
        "reason=$reason" \
        "started_at=$STARTED_AT" \
        "completed_at=$(utc_now)" \
        "backup_dir=$BACKUP_DIR" \
        "credentials_included=false" \
        "decryption_checked=true" \
        "archive_structure_checked=true"
    if [ -n "$STAGE_DIR" ]; then
        rm -rf "$STAGE_DIR"
    fi
    exit "$status"
}
trap finish EXIT HUP INT TERM

[ -n "$BACKUP_DIR" ] || { usage; exit 2; }
[ -d "$BACKUP_DIR" ] || die backup_directory_missing
BACKUP_DIR=$(CDPATH= cd -- "$BACKUP_DIR" && pwd)
MANIFEST="$BACKUP_DIR/manifest.env"
CHECKSUMS="$BACKUP_DIR/checksums.sha256"
[ -f "$MANIFEST" ] || die manifest_missing
[ -f "$CHECKSUMS" ] || die checksums_missing
[ -f "$BACKUP_DIR/manifest.hmac" ] || die manifest_hmac_missing
[ -f "$BACKUP_DIR/checksums.hmac" ] || die checksums_hmac_missing
assert_manifest_has_no_secrets "$MANIFEST"
require_key_file
STAGE_DIR="$BACKUP_DIR/.verify-$$_$(date +%s)"

mkdir -m 700 "$STAGE_DIR"
checksums_hmac=$(hmac_sha256_file "$CHECKSUMS")
[ "$checksums_hmac" = "$(tr -d '[:space:]' < "$BACKUP_DIR/checksums.hmac")" ] || {
    FAILURE_REASON=checksums_authentication_failed
    die "$FAILURE_REASON"
}
if ! (cd "$BACKUP_DIR" && sha256sum -c checksums.sha256 >/dev/null 2>"$STAGE_DIR/checksum.log"); then
    if ! (cd "$BACKUP_DIR" && shasum -a 256 -c checksums.sha256 >/dev/null 2>"$STAGE_DIR/checksum.log"); then
        FAILURE_REASON=checksum_mismatch
        die "$FAILURE_REASON"
    fi
fi

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

mkdir -m 700 "$STAGE_DIR/minio-objects"
tar -tf "$STAGE_DIR/minio-objects.tar" >/dev/null 2>"$STAGE_DIR/tar.log" \
    || {
        FAILURE_REASON=minio_archive_invalid
        die "$FAILURE_REASON"
    }

manifest_crypto=$(manifest_value "$MANIFEST" crypto)
[ "$manifest_crypto" = openssl-aes-256-cbc-pbkdf2 ] || {
    FAILURE_REASON=unsupported_encryption
    die "$FAILURE_REASON"
}

printf '%s\n' "Backup verification passed: $BACKUP_DIR"
printf '%s\n' "Evidence: $EVIDENCE_FILE"
