#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE="${JITONG_FORWARD_ENV_FILE:-$PROJECT_DIR/.env.forward}"
JAR_FILE="$PROJECT_DIR/target/im-server-0.1.0-SNAPSHOT.jar"
PID_FILE="${JITONG_FORWARD_PID_FILE:-${TMPDIR:-/tmp}/jitong-im-server-forward.pid}"
LOG_FILE="${JITONG_FORWARD_LOG_FILE:-${TMPDIR:-/tmp}/jitong-im-server-forward.log}"

if [ "${1:-}" = "stop" ]; then
    if [ -f "$PID_FILE" ]; then
        old_pid=$(cat "$PID_FILE")
        if kill -0 "$old_pid" 2>/dev/null; then
            kill "$old_pid"
            printf '%s\n' "Stopped Spring Boot PID $old_pid"
        else
            printf '%s\n' "Spring Boot PID $old_pid is not running"
        fi
        rm -f "$PID_FILE"
    else
        printf '%s\n' 'No forward Spring Boot PID file found'
    fi
    exit 0
fi

if [ ! -f "$ENV_FILE" ]; then
    printf '%s\n' "Missing $ENV_FILE. Copy .env.forward.example to .env.forward and fill local values." >&2
    exit 1
fi

requested_port="${SERVER_PORT:-}"
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

if [ -n "$requested_port" ]; then
    HTTP_PORT="$requested_port"
else
    HTTP_PORT="${SERVER_PORT:-${JITONG_HTTP_PORT:-8080}}"
fi
export SERVER_PORT="$HTTP_PORT"

JAVA_HOME=$("$PROJECT_DIR/scripts/dev-env.sh" --print-java-home)
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

printf '%s\n' 'Building the Spring Boot jar with the project JDK.' >&2
"$PROJECT_DIR/mvnw" -DskipTests package

if curl --silent --fail --max-time 1 "http://127.0.0.1:${HTTP_PORT}/api/v1/system/health" >/dev/null 2>&1; then
    printf '%s\n' "Spring Boot is already healthy at http://127.0.0.1:${HTTP_PORT}"
    exit 0
fi

if [ -f "$PID_FILE" ]; then
    old_pid=$(cat "$PID_FILE")
    if kill -0 "$old_pid" 2>/dev/null; then
        printf '%s\n' "Spring Boot is already running with PID $old_pid"
        exit 0
    fi
    rm -f "$PID_FILE"
fi

umask 077
nohup java -jar "$JAR_FILE" >"$LOG_FILE" 2>&1 &
server_pid=$!
printf '%s\n' "$server_pid" >"$PID_FILE"

attempt=0
until curl --silent --fail --max-time 2 "http://127.0.0.1:${HTTP_PORT}/api/v1/system/health" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if ! kill -0 "$server_pid" 2>/dev/null; then
        printf '%s\n' "Spring Boot stopped unexpectedly; see $LOG_FILE" >&2
        tail -80 "$LOG_FILE" >&2 || true
        exit 1
    fi
    if [ "$attempt" -ge 45 ]; then
        printf '%s\n' "Spring Boot did not become healthy; see $LOG_FILE" >&2
        exit 1
    fi
    sleep 2
done

printf '%s\n' "Spring Boot is healthy at http://127.0.0.1:${HTTP_PORT}"
printf '%s\n' "PID: $server_pid"
printf '%s\n' "Log: $LOG_FILE"
