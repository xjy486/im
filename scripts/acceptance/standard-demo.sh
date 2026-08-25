#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)

usage() {
    cat <<'EOF'
Usage:
  standard-demo.sh [options]

Run the repeatable T39 standard demo against a running Jitong server. The
demo uses four accounts in this order: Alice, Bob, Carol and Dave.

Options:
  --base-url URL          Server base URL (default: JITONG_SERVER_URL or
                          http://127.0.0.1:8080)
  --admin-api-key KEY     Create demo accounts with this administrator key
                          (default: ADMIN_API_KEY)
  --accounts PATH         Existing credentials from init-demo.sh; when absent,
                          four accounts are created in a temporary file
  --password PASSWORD     Password used when accounts are created
  --output PATH            Markdown evidence report (default:
                          acceptance-evidence/standard-demo-<timestamp>.md)
  --force                  Replace an existing report
  -h, --help               Show this help

The generated report never contains passwords, access tokens, push tokens, or
message contents. Keep any credentials file passed to --accounts private.
AI provider behavior, FCM delivery, encrypted local storage, and client UI
flows are recorded separately by the contract and client test evidence
described in docs/acceptance/t39-standard-demo.md.
EOF
}

BASE_URL=${JITONG_SERVER_URL:-http://127.0.0.1:8080}
ADMIN_API_KEY=${ADMIN_API_KEY:-}
ACCOUNTS_PATH=
DEMO_PASSWORD=${DEMO_PASSWORD:-}
OUTPUT_PATH=
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
        --accounts)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            ACCOUNTS_PATH=$2
            shift 2
            ;;
        --password)
            [ "$#" -ge 2 ] || { usage >&2; exit 2; }
            DEMO_PASSWORD=$2
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
command -v python3 >/dev/null 2>&1 || {
    printf '%s\n' 'Python 3 is required to create the image fixture.' >&2
    exit 1
}

BASE_URL=${BASE_URL%/}
if [ -z "$OUTPUT_PATH" ]; then
    mkdir -p "$PROJECT_ROOT/acceptance-evidence"
    OUTPUT_PATH="$PROJECT_ROOT/acceptance-evidence/standard-demo-$(date -u '+%Y%m%dT%H%M%SZ').md"
fi
if [ -e "$OUTPUT_PATH" ] && [ "$FORCE" != true ]; then
    printf '%s\n' "Output already exists: $OUTPUT_PATH (use --force to replace it)." >&2
    exit 2
fi

OUTPUT_DIR=$(CDPATH= cd -- "$(dirname -- "$OUTPUT_PATH")" && pwd)
OUTPUT_FILE="$OUTPUT_DIR/$(basename -- "$OUTPUT_PATH")"
mkdir -p "$OUTPUT_DIR"

umask 077
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/jitong-standard-demo.XXXXXX")
ACTION_LOG="$WORK_DIR/actions.log"
FAILURE_REASON=
RUN_STATUS=FAILED
STARTED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
ACCOUNTS_WAS_CREATED=false

finish() {
    FINISHED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    {
        printf '%s\n' '# T39 standard demo evidence'
        printf '\n%s\n' "- Status: **$RUN_STATUS**"
        printf '%s\n' "- Started: \`$STARTED_AT\`"
        printf '%s\n' "- Finished: \`$FINISHED_AT\`"
        printf '%s\n' "- Server: \`$BASE_URL\`"
        printf '%s\n' "- Accounts: four demo roles (credentials intentionally omitted)"
        if [ "$ACCOUNTS_WAS_CREATED" = true ]; then
            printf '%s\n' '- Account setup: created by `scripts/release/init-demo.sh`'
        else
            printf '%s\n' '- Account setup: supplied through an existing credentials file'
        fi
        if [ -n "$FAILURE_REASON" ]; then
            printf '%s\n' "- Failure: $FAILURE_REASON"
        fi
        printf '\n%s\n' '## Executed functional checks'
        if [ -s "$ACTION_LOG" ]; then
            cat "$ACTION_LOG"
        else
            printf '%s\n' '- No checks completed.'
        fi
        printf '\n%s\n' '## Scope notes'
        printf '%s\n' '- Binary image/avatar responses were checked for successful HTTP status and non-empty content; no media bytes are included in this report.'
        printf '%s\n' '- AI provider output, FCM delivery, encrypted local storage/search, and native client UI evidence are covered by the automated tests and manual runbook in `docs/acceptance/t39-standard-demo.md`.'
    } >"$OUTPUT_FILE"
    rm -rf "$WORK_DIR"
}
trap finish EXIT HUP INT TERM

fail() {
    FAILURE_REASON=$1
    printf '%s\n' "standard-demo: $FAILURE_REASON" >&2
    exit 1
}

record_pass() {
    printf '%s\n' "- PASS — $1" >>"$ACTION_LOG"
}

record_expected() {
    printf '%s\n' "- PASS — $1 (HTTP $2)" >>"$ACTION_LOG"
}

assert_json() {
    label=$1
    file=$2
    filter=$3
    jq -e "$filter" "$file" >/dev/null 2>&1 || fail "$label returned an unexpected response."
    record_pass "$label"
}

request_json() {
    label=$1
    method=$2
    path=$3
    token=$4
    body_file=$5
    expected_status=$6
    output_file=$7

    if [ -n "$token" ] && [ -n "$body_file" ]; then
        status=$(curl --silent --show-error \
            --output "$output_file" \
            --write-out '%{http_code}' \
            -X "$method" \
            -H "Authorization: Bearer $token" \
            -H 'Content-Type: application/json' \
            --data-binary "@$body_file" \
            "$BASE_URL$path") || fail "$label could not reach the server."
    elif [ -n "$token" ]; then
        status=$(curl --silent --show-error \
            --output "$output_file" \
            --write-out '%{http_code}' \
            -X "$method" \
            -H "Authorization: Bearer $token" \
            "$BASE_URL$path") || fail "$label could not reach the server."
    elif [ -n "$body_file" ]; then
        status=$(curl --silent --show-error \
            --output "$output_file" \
            --write-out '%{http_code}' \
            -X "$method" \
            -H 'Content-Type: application/json' \
            --data-binary "@$body_file" \
            "$BASE_URL$path") || fail "$label could not reach the server."
    else
        status=$(curl --silent --show-error \
            --output "$output_file" \
            --write-out '%{http_code}' \
            -X "$method" \
            "$BASE_URL$path") || fail "$label could not reach the server."
    fi

    [ "$status" = "$expected_status" ] || fail "$label returned HTTP $status; expected $expected_status."
    record_expected "$label" "$status"
}

request_binary() {
    label=$1
    method=$2
    path=$3
    token=$4
    expected_status=$5
    output_file=$6

    status=$(curl --silent --show-error \
        --output "$output_file" \
        --write-out '%{http_code}' \
        -X "$method" \
        -H "Authorization: Bearer $token" \
        "$BASE_URL$path") || fail "$label could not reach the server."
    [ "$status" = "$expected_status" ] || fail "$label returned HTTP $status; expected $expected_status."
    [ -s "$output_file" ] || fail "$label returned an empty body."
    record_expected "$label" "$status"
}

request_status() {
    label=$1
    method=$2
    path=$3
    token=$4
    expected_status=$5
    output_file=$6

    status=$(curl --silent --show-error \
        --output "$output_file" \
        --write-out '%{http_code}' \
        -X "$method" \
        -H "Authorization: Bearer $token" \
        "$BASE_URL$path") || fail "$label could not reach the server."
    [ "$status" = "$expected_status" ] || fail "$label returned HTTP $status; expected $expected_status."
    record_expected "$label" "$status"
}

request_multipart() {
    label=$1
    method=$2
    path=$3
    token=$4
    image_file=$5
    expected_status=$6
    output_file=$7

    status=$(curl --silent --show-error \
        --output "$output_file" \
        --write-out '%{http_code}' \
        -X "$method" \
        -H "Authorization: Bearer $token" \
        -F "file=@$image_file;type=image/png" \
        "$BASE_URL$path") || fail "$label could not reach the server."
    [ "$status" = "$expected_status" ] || fail "$label returned HTTP $status; expected $expected_status."
    record_expected "$label" "$status"
}

login() {
    role=$1
    account_no=$2
    device_class=$3
    installation_id=$4
    request_file="$WORK_DIR/login-$role.request.json"
    response_file="$WORK_DIR/login-$role.response.json"
    jq -n \
        --arg accountNo "$account_no" \
        --arg password "$DEMO_PASSWORD" \
        --arg deviceClass "$device_class" \
        --arg installationId "$installation_id" \
        '{accountNo: $accountNo, password: $password, deviceClass: $deviceClass, installationId: $installationId}' \
        >"$request_file"
    status=$(curl --silent --show-error \
        --output "$response_file" \
        --write-out '%{http_code}' \
        -H 'Content-Type: application/json' \
        --data-binary "@$request_file" \
        "$BASE_URL/api/v1/auth/login") || fail "$role login could not reach the server."
    [ "$status" = 200 ] || fail "$role login returned HTTP $status."
    jq -e '.accessToken | type == "string" and length > 0' "$response_file" >/dev/null 2>&1 \
        || fail "$role login did not return an access token."
    record_expected "$role $device_class login" "$status"
    token_variable="${role}_token"
    device_variable="${role}_device_id"
    eval "$token_variable=\$(jq -r '.accessToken' '$response_file')"
    eval "$device_variable=\$(jq -r '.deviceId' '$response_file')"
}

make_image() {
    python3 - "$1" <<'PY'
import struct
import sys
import zlib

path = sys.argv[1]
width, height = 96, 64
rows = []
for y in range(height):
    row = bytearray([0])
    for x in range(width):
        row.extend(((x * 3) % 256, (y * 5) % 256, ((x + y) * 7) % 256))
    rows.append(bytes(row))

def chunk(kind, data):
    return (
        struct.pack(">I", len(data))
        + kind
        + data
        + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    )

png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
png += chunk(b"IEND", b"")
with open(path, "wb") as output:
    output.write(png)
PY
}

[ -n "$ACCOUNTS_PATH" ] || {
    [ -n "$ADMIN_API_KEY" ] || fail 'an administrator API key is required when --accounts is omitted.'
    ACCOUNTS_PATH="$WORK_DIR/demo-accounts.json"
    init_args="--base-url $BASE_URL --admin-api-key $ADMIN_API_KEY --output $ACCOUNTS_PATH --force"
    if [ -n "$DEMO_PASSWORD" ]; then
        "$SCRIPT_DIR/../release/init-demo.sh" $init_args --password "$DEMO_PASSWORD" >/dev/null
    else
        "$SCRIPT_DIR/../release/init-demo.sh" $init_args >/dev/null
    fi
    ACCOUNTS_WAS_CREATED=true
}

[ -f "$ACCOUNTS_PATH" ] || fail "credentials file does not exist: $ACCOUNTS_PATH"
jq -e '
    .version == 1
    and (.users | length == 4)
    and ([.users[].accountNo] | all(test("^[1-9][0-9]{10}$")))
    and (.password | type == "string" and length >= 8)
' "$ACCOUNTS_PATH" >/dev/null 2>&1 || fail 'credentials file is not a valid four-account init-demo file.'
DEMO_PASSWORD=${DEMO_PASSWORD:-$(jq -r '.password' "$ACCOUNTS_PATH")}

ALICE_ACCOUNT=$(jq -r '.users[0].accountNo' "$ACCOUNTS_PATH")
BOB_ACCOUNT=$(jq -r '.users[1].accountNo' "$ACCOUNTS_PATH")
CAROL_ACCOUNT=$(jq -r '.users[2].accountNo' "$ACCOUNTS_PATH")
DAVE_ACCOUNT=$(jq -r '.users[3].accountNo' "$ACCOUNTS_PATH")
ALICE_USER_ID=$(jq -r '.users[0].userId' "$ACCOUNTS_PATH")
BOB_USER_ID=$(jq -r '.users[1].userId' "$ACCOUNTS_PATH")
CAROL_USER_ID=$(jq -r '.users[2].userId' "$ACCOUNTS_PATH")
DAVE_USER_ID=$(jq -r '.users[3].userId' "$ACCOUNTS_PATH")

health_file="$WORK_DIR/health.json"
status=$(curl --silent --show-error \
    --output "$health_file" \
    --write-out '%{http_code}' \
    "$BASE_URL/api/v1/system/health") || fail 'health check could not reach the server.'
[ "$status" = 200 ] || fail "health check returned HTTP $status."
assert_json 'server health' "$health_file" '.version == 1 and .status == "UP"'

login alice_pc "$ALICE_ACCOUNT" PC "t39-alice-pc"
login alice_mobile "$ALICE_ACCOUNT" MOBILE "t39-alice-mobile"
login bob_mobile "$BOB_ACCOUNT" MOBILE "t39-bob-mobile"
login carol_mobile "$CAROL_ACCOUNT" MOBILE "t39-carol-mobile"
login dave_mobile "$DAVE_ACCOUNT" MOBILE "t39-dave-mobile"

validate_file="$WORK_DIR/validate.json"
request_json 'Alice PC validation' POST /api/v1/auth/validate "$alice_pc_token" '' 204 "$validate_file"
request_json 'Alice MOBILE validation' POST /api/v1/auth/validate "$alice_mobile_token" '' 204 "$validate_file"
request_json 'Bob MOBILE validation' POST /api/v1/auth/validate "$bob_mobile_token" '' 204 "$validate_file"

search_file="$WORK_DIR/user-search.json"
request_json 'exact account search' GET "/api/v1/users/search?accountNo=$BOB_ACCOUNT" "$alice_pc_token" '' 200 "$search_file"
assert_json 'exact account search result' "$search_file" \
    ".accountNo == \"$BOB_ACCOUNT\" and .relationship == \"NONE\""

contact_body="$WORK_DIR/contact.request.json"
jq -n --arg accountNo "$BOB_ACCOUNT" \
    '{accountNo: $accountNo, verification: "T39 demo"}' >"$contact_body"
contact_file="$WORK_DIR/contact.response.json"
request_json 'contact request creation' POST /api/v1/contact-requests "$alice_pc_token" \
    "$contact_body" 200 "$contact_file"
CONTACT_REQUEST_ID=$(jq -r '.requestId' "$contact_file")
assert_json 'contact request pending' "$contact_file" '.status == "PENDING"'

accept_file="$WORK_DIR/contact.accept.json"
request_json 'contact request acceptance' POST \
    "/api/v1/contact-requests/$CONTACT_REQUEST_ID/accept" "$bob_mobile_token" '' 200 "$accept_file"
CONVERSATION_ID=$(jq -r '.conversationId' "$accept_file")
assert_json 'C2C conversation created' "$accept_file" \
    ".status == \"ACCEPTED\" and .conversationId == \"$CONVERSATION_ID\""

message_body="$WORK_DIR/message.request.json"
jq -n --arg clientMsgId "$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')" \
    '{clientMsgId: $clientMsgId, text: "T39 functional demo message"}' >"$message_body"
message_file="$WORK_DIR/message.response.json"
request_json 'C2C text message' POST \
    "/api/v1/conversations/$CONVERSATION_ID/messages" "$alice_pc_token" \
    "$message_body" 200 "$message_file"
MESSAGE_ID=$(jq -r '.messageId' "$message_file")
MESSAGE_SEQ=$(jq -r '.conversationSeq' "$message_file")
assert_json 'C2C text message accepted' "$message_file" \
    ".type == \"TEXT\" and .conversationSeq == $MESSAGE_SEQ"

sync_file="$WORK_DIR/sync.json"
request_json 'durable sync pull on second device' GET \
    "/api/v1/sync?after=0&limit=200" "$alice_mobile_token" '' 200 "$sync_file"
assert_json 'durable sync contains the C2C message' "$sync_file" \
    ".events | any(.[]; .eventType == \"MESSAGE_CREATED\" and .entityId == \"$MESSAGE_ID\")"

history_file="$WORK_DIR/history.json"
request_json 'C2C history on second device' GET \
    "/api/v1/conversations/$CONVERSATION_ID/messages?afterSeq=0&limit=200" \
    "$alice_mobile_token" '' 200 "$history_file"
assert_json 'cross-device C2C history' "$history_file" \
    ".messages | any(.[]; .messageId == \"$MESSAGE_ID\")"

fixture="$WORK_DIR/demo.png"
make_image "$fixture"

upload_file="$WORK_DIR/upload.response.json"
request_multipart 'message image upload' POST \
    "/api/v1/media/images?uploadId=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')" \
    "$alice_pc_token" "$fixture" 200 "$upload_file"
MEDIA_ID=$(jq -r '.mediaId' "$upload_file")
assert_json 'normalized message image' "$upload_file" \
    '.purpose == "MESSAGE_IMAGE" and .state == "TEMP" and .contentType == "image/jpeg"'

image_message_body="$WORK_DIR/image-message.request.json"
jq -n --arg clientMsgId "$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')" \
    --arg mediaId "$MEDIA_ID" \
    '{clientMsgId: $clientMsgId, type: "IMAGE", mediaId: $mediaId}' >"$image_message_body"
image_message_file="$WORK_DIR/image-message.response.json"
request_json 'C2C image message' POST \
    "/api/v1/conversations/$CONVERSATION_ID/messages" "$alice_pc_token" \
    "$image_message_body" 200 "$image_message_file"
IMAGE_MESSAGE_ID=$(jq -r '.messageId' "$image_message_file")
assert_json 'C2C image message accepted' "$image_message_file" \
    ".type == \"IMAGE\" and .mediaId == \"$MEDIA_ID\""

downloaded_media="$WORK_DIR/media.jpg"
request_binary 'peer image download' GET "/api/v1/media/$MEDIA_ID?variant=thumb" \
    "$bob_mobile_token" 200 "$downloaded_media"
image_recalled_file="$WORK_DIR/image-recalled.json"
request_json 'image message recall' POST \
    "/api/v1/messages/$IMAGE_MESSAGE_ID/recall" "$alice_pc_token" '' 200 "$image_recalled_file"
assert_json 'image recall tombstone' "$image_recalled_file" \
    ".messageId == \"$IMAGE_MESSAGE_ID\" and .state == \"RECALLED\" and (.mediaId | not)"
request_status 'recalled image is no longer downloadable' GET \
    "/api/v1/media/$MEDIA_ID?variant=thumb" "$bob_mobile_token" 410 \
    "$WORK_DIR/recalled-media.response"

avatar_file="$WORK_DIR/avatar.response.json"
request_multipart 'user avatar upload' PUT \
    "/api/v1/users/me/avatar?uploadId=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')" \
    "$alice_pc_token" "$fixture" 200 "$avatar_file"
AVATAR_VERSION=$(jq -r '.avatarVersion' "$avatar_file")
assert_json 'versioned user avatar' "$avatar_file" \
    ".purpose == \"AVATAR\" and .state == \"BOUND\" and .avatarVersion == $AVATAR_VERSION"
downloaded_avatar="$WORK_DIR/avatar.webp"
request_binary 'contact avatar download' GET \
    "/api/v1/users/$ALICE_USER_ID/avatar?variant=thumb&avatarVersion=$AVATAR_VERSION" \
    "$bob_mobile_token" 200 "$downloaded_avatar"

recall_body="$WORK_DIR/recall-message.request.json"
jq -n --arg clientMsgId "$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')" \
    '{clientMsgId: $clientMsgId, text: "T39 recall candidate"}' >"$recall_body"
recall_candidate="$WORK_DIR/recall-candidate.json"
request_json 'recall candidate message' POST \
    "/api/v1/conversations/$CONVERSATION_ID/messages" "$alice_pc_token" \
    "$recall_body" 200 "$recall_candidate"
RECALL_MESSAGE_ID=$(jq -r '.messageId' "$recall_candidate")
recalled_file="$WORK_DIR/recalled.json"
request_json 'message recall' POST \
    "/api/v1/messages/$RECALL_MESSAGE_ID/recall" "$alice_pc_token" '' 200 "$recalled_file"
assert_json 'recall tombstone' "$recalled_file" \
    ".messageId == \"$RECALL_MESSAGE_ID\" and .state == \"RECALLED\" and (.text | not)"

group_body="$WORK_DIR/group.request.json"
jq -n '{name: "T39 Public Demo", description: "Automated standard demo", visibility: "PUBLIC"}' \
    >"$group_body"
group_file="$WORK_DIR/group.response.json"
request_json 'public group creation' POST /api/v1/groups "$carol_mobile_token" \
    "$group_body" 200 "$group_file"
GROUP_CONVERSATION_ID=$(jq -r '.conversationId' "$group_file")
GROUP_NO=$(jq -r '.groupNo' "$group_file")

group_search_file="$WORK_DIR/group-search.json"
request_json 'public group number search' GET "/api/v1/groups/search?query=$GROUP_NO" \
    "$dave_mobile_token" '' 200 "$group_search_file"
assert_json 'public group search result' "$group_search_file" \
    '.groups | length == 1'

invite_body="$WORK_DIR/invite.request.json"
jq -n '{maxUses: 1, expiresInSeconds: 600}' >"$invite_body"
invite_file="$WORK_DIR/invite.response.json"
request_json 'group invite QR payload' POST \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/invites" "$carol_mobile_token" \
    "$invite_body" 200 "$invite_file"
DEEP_LINK=$(jq -r '.deepLink' "$invite_file")
INVITE_TOKEN=$(printf '%s' "$DEEP_LINK" | sed -n 's/.*[?&]token=\([^&]*\).*/\1/p')
[ -n "$INVITE_TOKEN" ] || fail 'group invite did not contain a token in its deep link.'
assert_json 'group invite payload' "$invite_file" \
    '.qrPayload == .deepLink and (.deepLink | startswith("https://"))'

resolve_file="$WORK_DIR/invite-resolve.json"
request_json 'group invite resolve' GET \
    "/api/v1/groups/invites/resolve?token=$INVITE_TOKEN" "$dave_mobile_token" '' 200 "$resolve_file"
assert_json 'resolved group invite' "$resolve_file" \
    ".conversationId == \"$GROUP_CONVERSATION_ID\""

join_body="$WORK_DIR/join.request.json"
jq -n --arg inviteToken "$INVITE_TOKEN" '{inviteToken: $inviteToken}' >"$join_body"
join_file="$WORK_DIR/join.response.json"
request_json 'group QR join request' POST \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/join-requests" "$dave_mobile_token" \
    "$join_body" 200 "$join_file"
JOIN_REQUEST_ID=$(jq -r '.requestId' "$join_file")
assert_json 'group join request pending' "$join_file" '.status == "PENDING"'

requests_file="$WORK_DIR/group-requests.json"
request_json 'group approval queue' GET \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/join-requests" "$carol_mobile_token" \
    '' 200 "$requests_file"
assert_json 'group approval queue contains Dave' "$requests_file" \
    "any(.[]; .requestId == \"$JOIN_REQUEST_ID\" and .userId == \"$DAVE_USER_ID\")"

approve_file="$WORK_DIR/group-approve.json"
request_json 'group join approval' POST \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/join-requests/$JOIN_REQUEST_ID/approve" \
    "$carol_mobile_token" '' 200 "$approve_file"
assert_json 'group member approval' "$approve_file" '.status == "APPROVED"'

member_body="$WORK_DIR/group-member.request.json"
jq -n --arg accountNo "$BOB_ACCOUNT" '{accountNo: $accountNo}' >"$member_body"
member_file="$WORK_DIR/group-member.response.json"
request_json 'direct group member invite' POST \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/members" "$carol_mobile_token" \
    "$member_body" 200 "$member_file"

alice_member_body="$WORK_DIR/group-alice-member.request.json"
jq -n --arg accountNo "$ALICE_ACCOUNT" '{accountNo: $accountNo}' >"$alice_member_body"
request_json 'second ordinary group member invite' POST \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/members" "$carol_mobile_token" \
    "$alice_member_body" 200 "$WORK_DIR/group-alice-member.response.json"

role_body="$WORK_DIR/group-role.request.json"
jq -n '{role: "ADMIN"}' >"$role_body"
role_file="$WORK_DIR/group-role.response.json"
request_json 'group administrator role' PUT \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/members/$BOB_USER_ID/role" \
    "$carol_mobile_token" "$role_body" 200 "$role_file"
assert_json 'group administrator assigned' "$role_file" '.role == "ADMIN"'

request_json 'ordinary group member leaves' POST \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/leave" "$dave_mobile_token" \
    '' 204 "$WORK_DIR/group-leave.response"
request_json 'group member management removal' DELETE \
    "/api/v1/groups/$GROUP_CONVERSATION_ID/members/$ALICE_USER_ID" "$bob_mobile_token" \
    '' 204 "$WORK_DIR/group-remove.response"

request_json 'group dissolution' DELETE \
    "/api/v1/groups/$GROUP_CONVERSATION_ID" "$carol_mobile_token" \
    '' 204 "$WORK_DIR/group-dissolve.response"
hidden_group_file="$WORK_DIR/group-hidden.json"
status=$(curl --silent --show-error \
    --output "$hidden_group_file" \
    --write-out '%{http_code}' \
    -H "Authorization: Bearer $dave_mobile_token" \
    "$BASE_URL/api/v1/groups/search?query=$GROUP_NO") || fail 'dissolved group search could not reach the server.'
[ "$status" = 404 ] || fail "dissolved group remained searchable (HTTP $status)."
record_expected 'dissolved group is no longer discoverable' "$status"

replacement_file="$WORK_DIR/replacement-required.json"
replacement_request="$WORK_DIR/replacement.request.json"
jq -n \
    --arg accountNo "$ALICE_ACCOUNT" \
    --arg password "$DEMO_PASSWORD" \
    '{accountNo: $accountNo, password: $password, deviceClass: "MOBILE", installationId: "t39-alice-mobile-replacement"}' \
    >"$replacement_request"
status=$(curl --silent --show-error \
    --output "$replacement_file" \
    --write-out '%{http_code}' \
    -H 'Content-Type: application/json' \
    --data-binary "@$replacement_request" \
    "$BASE_URL/api/v1/auth/login") || fail 'same-class replacement login could not reach the server.'
[ "$status" = 409 ] || fail "same-class replacement login returned HTTP $status."
assert_json 'same-class device replacement challenge' "$replacement_file" \
    '.code == "DEVICE_REPLACEMENT_REQUIRED" and (.replacementChallenge | length > 0)'
REPLACEMENT_CHALLENGE=$(jq -r '.replacementChallenge' "$replacement_file")

confirm_body="$WORK_DIR/replacement-confirm.request.json"
jq -n --arg replacementChallenge "$REPLACEMENT_CHALLENGE" \
    '{replacementChallenge: $replacementChallenge}' >"$confirm_body"
replacement_confirmed="$WORK_DIR/replacement-confirmed.json"
request_json 'same-class device replacement confirmation' POST \
    /api/v1/auth/device-replacement/confirm '' "$confirm_body" 200 "$replacement_confirmed"
NEW_ALICE_MOBILE_TOKEN=$(jq -r '.accessToken' "$replacement_confirmed")
assert_json 'replacement device issued credentials' "$replacement_confirmed" \
    '.deviceClass == "MOBILE" and (.accessToken | length > 0)'

old_device_status=$(curl --silent --show-error \
    --output "$WORK_DIR/old-device.response" \
    --write-out '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer $alice_mobile_token" \
    "$BASE_URL/api/v1/auth/validate") || fail 'old device validation could not reach the server.'
[ "$old_device_status" = 401 ] || fail "old mobile device remained trusted (HTTP $old_device_status)."
record_expected 'old mobile device is untrusted after replacement' "$old_device_status"
request_json 'replacement mobile device validation' POST /api/v1/auth/validate \
    "$NEW_ALICE_MOBILE_TOKEN" '' 204 "$WORK_DIR/new-device.response"

RUN_STATUS=PASSED
printf '%s\n' "Standard demo passed. Evidence: $OUTPUT_FILE"
