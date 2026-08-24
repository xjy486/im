#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

usage() {
    cat <<'EOF'
Usage:
  init-demo.sh [options]

Create four demo users through the protected administrator API and write the
credentials to a mode-600 JSON file.

Options:
  --base-url URL          Server base URL (default: JITONG_SERVER_URL or
                          http://127.0.0.1:8080)
  --admin-api-key KEY     Administrator API key (default: ADMIN_API_KEY)
  --password PASSWORD     Shared demo password (default: generated locally)
  --prefix TEXT           Display-name prefix (default: Jitong Demo)
  --output PATH           Credential file (default: ./demo-accounts.json)
  --force                 Replace an existing output file
  -h, --help              Show this help

The generated credential file contains the demo password and must be handled
as a secret. It is intentionally ignored by Git.
EOF
}

BASE_URL=${JITONG_SERVER_URL:-http://127.0.0.1:8080}
ADMIN_API_KEY=${ADMIN_API_KEY:-}
DEMO_PASSWORD=${DEMO_PASSWORD:-}
PREFIX='Jitong Demo'
OUTPUT_PATH="$PROJECT_ROOT/demo-accounts.json"
FORCE=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --base-url)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            BASE_URL=$2
            shift 2
            ;;
        --admin-api-key)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            ADMIN_API_KEY=$2
            shift 2
            ;;
        --password)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            DEMO_PASSWORD=$2
            shift 2
            ;;
        --prefix)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            PREFIX=$2
            shift 2
            ;;
        --output)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            OUTPUT_PATH=$2
            shift 2
            ;;
        --force)
            FORCE=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf '%s\n' "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

command -v curl >/dev/null 2>&1 || {
    printf '%s\n' 'curl is required.' >&2
    exit 1
}
command -v jq >/dev/null 2>&1 || {
    printf '%s\n' 'jq is required.' >&2
    exit 1
}
command -v openssl >/dev/null 2>&1 || {
    printf '%s\n' 'openssl is required when --password is omitted.' >&2
    exit 1
}

[ -n "$ADMIN_API_KEY" ] || {
    printf '%s\n' 'An administrator API key is required via --admin-api-key or ADMIN_API_KEY.' >&2
    exit 2
}

if [ -z "$DEMO_PASSWORD" ]; then
    DEMO_PASSWORD=$(openssl rand -base64 36 | tr -dc 'A-Za-z0-9' | cut -c1-24)
fi
[ "${#DEMO_PASSWORD}" -ge 8 ] || {
    printf '%s\n' 'The demo password must contain at least 8 characters.' >&2
    exit 2
}

if [ -e "$OUTPUT_PATH" ] && [ "$FORCE" != true ]; then
    printf '%s\n' "Output already exists: $OUTPUT_PATH (use --force to replace it)." >&2
    exit 2
fi

OUTPUT_DIR=$(CDPATH= cd -- "$(dirname -- "$OUTPUT_PATH")" && pwd)
OUTPUT_FILE="$OUTPUT_DIR/$(basename -- "$OUTPUT_PATH")"
umask 077
mkdir -p "$OUTPUT_DIR"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/jitong-demo-users.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

created_users="$TEMP_DIR/users.jsonl"
: > "$created_users"

for name in Alice Bob Carol Dave; do
    display_name="$PREFIX $name"
    response_file="$TEMP_DIR/response-$name.json"
    error_file="$TEMP_DIR/error-$name.log"
    payload=$(jq -n --arg displayName "$display_name" --arg password "$DEMO_PASSWORD" \
        '{displayName: $displayName, password: $password}')

    if ! curl --fail --silent --show-error \
        -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
        -H 'Content-Type: application/json' \
        --data "$payload" \
        "$BASE_URL/api/v1/admin/users" \
        >"$response_file" 2>"$error_file"; then
        printf '%s\n' "Failed to create $display_name." >&2
        cat "$error_file" >&2
        exit 1
    fi

    jq -e '
        (.version == 1)
        and (.userId | type == "string")
        and (.accountNo | type == "string" and test("^[1-9][0-9]{10}$"))
        and (.displayName | type == "string")
    ' "$response_file" >/dev/null || {
        printf '%s\n' "The server returned an invalid user response for $display_name." >&2
        exit 1
    }
    jq --arg password "$DEMO_PASSWORD" \
        '. + {password: $password}' "$response_file" >>"$created_users"
done

generated_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
jq -n \
    --arg generatedAt "$generated_at" \
    --arg baseUrl "$BASE_URL" \
    --arg password "$DEMO_PASSWORD" \
    --slurpfile users "$created_users" \
    '{
        version: 1,
        generatedAt: $generatedAt,
        baseUrl: $baseUrl,
        password: $password,
        users: $users
    }' >"$OUTPUT_FILE"
chmod 600 "$OUTPUT_FILE"

printf '%s\n' "Created four demo users."
printf '%s\n' "Credentials: $OUTPUT_FILE"
printf '%s\n' 'Keep this file private; it contains the shared demo password.'
