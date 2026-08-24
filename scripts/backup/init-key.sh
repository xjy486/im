#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
KEY_FILE=${BACKUP_KEY_FILE:-"$HOME/.config/jitong/backup.key"}

if [ "$#" -gt 0 ]; then
    case "$1" in
        --key-file)
            [ "$#" -eq 2 ] || {
                printf '%s\n' 'usage: init-key.sh [--key-file PATH]' >&2
                exit 2
            }
            KEY_FILE=$2
            ;;
        *)
            printf '%s\n' 'usage: init-key.sh [--key-file PATH]' >&2
            exit 2
            ;;
    esac
fi

if [ -e "$KEY_FILE" ]; then
    printf '%s\n' "backup key already exists: $KEY_FILE" >&2
    exit 1
fi

mkdir -p "$(dirname -- "$KEY_FILE")"
umask 077
openssl rand -hex 32 > "$KEY_FILE"
chmod 600 "$KEY_FILE"
printf '%s\n' "Created owner-readable backup key: $KEY_FILE"
printf '%s\n' 'Store this key outside the repository. It is required for restore.'
