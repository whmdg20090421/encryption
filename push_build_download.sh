#!/bin/sh
# Run the release workflow and collect its APK artifact.

set -eu

export PATH="/data/data/com.termux/files/usr/bin:$PATH"
export GH_CONFIG_DIR="/data/user/0/com.termux/files/home/.config/gh"
GH_BIN="/data/data/com.termux/files/usr/bin/gh"
# Use the directory containing this script. Android's /data/user/0 and
# /data/data aliases can otherwise make a fixed Termux home path unreliable.
PROJECT_ROOT=$(CDPATH= cd "$(dirname "$0")" && pwd)
REPO="whmdg20090421/encryption"
BRANCH="master"
WORKFLOW="build.yml"
ARTIFACT_NAME="工具箱-arm64-v8a-release"
POLL_INTERVAL=30
APK_OUTPUT_DIR="$PROJECT_ROOT/应用安装包"
TEMP_DIR=""

RED='\033[31m'
GREEN='\033[32m'
CYAN='\033[36m'
RESET='\033[0m'

die() {
    printf '%bERROR: %s%b\n' "$RED" "$1" "$RESET" >&2
    exit 1
}

cleanup() {
    if [ -n "$TEMP_DIR" ] && [ -d "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
    fi
}
trap cleanup 0 1 2 15

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

directory_megabytes() {
    bytes=$(du -sk "$1" 2>/dev/null | awk '{print $1 * 1024}')
    awk -v bytes="${bytes:-0}" 'BEGIN { printf "%.1f", bytes / 1048576 }'
}

cd "$PROJECT_ROOT" || die "Cannot enter project directory: $PROJECT_ROOT"

require_command find
require_command du
require_command awk

[ -x "$GH_BIN" ] || die "GitHub CLI was not found: $GH_BIN"
"$GH_BIN" auth status --hostname github.com >/dev/null 2>&1 \
    || die "GitHub CLI is not authenticated with $GH_CONFIG_DIR."

PREVIOUS_RUN_ID=$("$GH_BIN" run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" \
    --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)

printf '%bTriggering GitHub Actions workflow...%b\n' "$CYAN" "$RESET"
"$GH_BIN" workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH" --field compact=true
printf '%bWorkflow triggered.%b\n' "$GREEN" "$RESET"

RUN_ID=""
attempt=1
while [ "$attempt" -le 12 ]; do
    RUN_ID=$("$GH_BIN" run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" \
        --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
    if [ -n "$RUN_ID" ] && [ "$RUN_ID" != "null" ] && [ "$RUN_ID" != "$PREVIOUS_RUN_ID" ]; then
        break
    fi
    RUN_ID=""
    attempt=$((attempt + 1))
    sleep 2
done
[ -n "$RUN_ID" ] || die "Could not find the workflow run that was just triggered."

printf 'Monitoring workflow run: %s\n' "$RUN_ID"
RUN_URL=""
while true; do
    RUN_INFO=$("$GH_BIN" run view "$RUN_ID" --repo "$REPO" --json status,conclusion,url \
        --jq '[.status, .conclusion, .url] | @tsv') || die "Unable to query workflow run $RUN_ID."
    STATUS=$(printf '%s\n' "$RUN_INFO" | awk -F '\t' '{print $1}')
    CONCLUSION=$(printf '%s\n' "$RUN_INFO" | awk -F '\t' '{print $2}')
    RUN_URL=$(printf '%s\n' "$RUN_INFO" | awk -F '\t' '{print $3}')

    if [ "$STATUS" = "completed" ]; then
        if [ "$CONCLUSION" = "success" ]; then
            printf '%bBuild succeeded.%b\n' "$GREEN" "$RESET"
            break
        fi
        printf '%bBuild failed: %s%b\n' "$RED" "${CONCLUSION:-unknown}" "$RESET" >&2
        printf 'Run: %s\n' "$RUN_URL" >&2
        "$GH_BIN" run view "$RUN_ID" --repo "$REPO" --log-failed || true
        exit 1
    fi

    printf '[%s] Build status: %s. Checking again in %ss.\n' \
        "$(date '+%H:%M:%S')" "${STATUS:-unknown}" "$POLL_INTERVAL"
    sleep "$POLL_INTERVAL"
done

mkdir -p "$APK_OUTPUT_DIR"
TEMP_DIR=$(mktemp -d "$PROJECT_ROOT/.artifact-download.XXXXXX")
printf '%bDownloading artifact...%b\n' "$CYAN" "$RESET"

"$GH_BIN" run download "$RUN_ID" --repo "$REPO" --name "$ARTIFACT_NAME" --dir "$TEMP_DIR" &
DOWNLOAD_PID=$!
PREVIOUS_MB=0
while kill -0 "$DOWNLOAD_PID" 2>/dev/null; do
    sleep 1
    CURRENT_MB=$(directory_megabytes "$TEMP_DIR")
    SPEED=$(awk -v now="$CURRENT_MB" -v previous="$PREVIOUS_MB" 'BEGIN { printf "%.1f", now - previous }')
    printf '\rDownloaded: %s MB | Speed: %s MB/s' "$CURRENT_MB" "$SPEED"
    PREVIOUS_MB=$CURRENT_MB
done

if ! wait "$DOWNLOAD_PID"; then
    printf '\n'
    die "Artifact download failed."
fi
printf '\rDownloaded: %s MB | Speed: 0.0 MB/s\n' "$(directory_megabytes "$TEMP_DIR")"

APK_FILE=$(find "$TEMP_DIR" -type f -iname '*.apk' -print -quit)
[ -n "$APK_FILE" ] || die "Artifact download completed, but no APK was found."

APK_NAME=$(basename "$APK_FILE")
DESTINATION="$APK_OUTPUT_DIR/$APK_NAME"
if [ -e "$DESTINATION" ]; then
    DESTINATION="$APK_OUTPUT_DIR/${APK_NAME%.apk}-$(date '+%Y%m%d-%H%M%S').apk"
fi
mv "$APK_FILE" "$DESTINATION"

printf '%bAPK ready: %s%b\n' "$GREEN" "$DESTINATION" "$RESET"
