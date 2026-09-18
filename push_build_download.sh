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
PROXY_HOST="127.0.0.1"
PROXY_PORT="7890"
PROXY_URL="http://$PROXY_HOST:$PROXY_PORT"
# 统一让本脚本后续的 gh / curl 等命令全部走 VPN 代理。
export HTTP_PROXY="$PROXY_URL"
export HTTPS_PROXY="$PROXY_URL"
export http_proxy="$PROXY_URL"
export https_proxy="$PROXY_URL"

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
require_command curl
require_command unzip
require_command aria2c

# 仅检查本地 VPN 代理端口是否在监听，未监听则提示后退出。
# 不请求任何外网地址（避免 GitHub 限流 / 无网等误判）。
require_vpn_proxy() {
    if curl -sS --max-time 3 -o /dev/null "$PROXY_URL"; then
        return 0
    fi
    printf '%b请先开启 VPN（本地代理端口 %s 未监听），再重新运行本脚本。%b\n' \
        "$RED" "$PROXY_URL" "$RESET" >&2
    exit 1
}

[ -x "$GH_BIN" ] || die "GitHub CLI was not found: $GH_BIN"
"$GH_BIN" auth status --hostname github.com >/dev/null 2>&1 \
    || die "GitHub CLI is not authenticated with $GH_CONFIG_DIR."

require_vpn_proxy

REQUESTED_SHA=$("$GH_BIN" api "repos/$REPO/commits/$BRANCH" --jq '.sha') \
    || die "Unable to resolve the current commit on $BRANCH."
REQUESTED_SHORT_SHA=$(printf '%s' "$REQUESTED_SHA" | cut -c1-7)

# 查询当前 commit 的所有工作流运行记录（最新在前）。
RUN_LIST=$("$GH_BIN" run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" \
    --limit 30 --json databaseId,headSha,status,conclusion,url \
    --jq ".[] | select(.headSha == \"$REQUESTED_SHA\") | [.databaseId, .status, .conclusion, .url] | @tsv" \
    2>/dev/null || true)

# 优先级：正在运行 > 已成功 > 已失败 > 从未运行
ACTIVE_RUN_ID=$(printf '%s\n' "$RUN_LIST" | awk -F '\t' '$2 != "completed" {print $1; exit}')
SUCCESS_RUN_ID=$(printf '%s\n' "$RUN_LIST" | awk -F '\t' '$2 == "completed" && $3 == "success" {print $1; exit}')
FAILED_RUN_ID=$(printf '%s\n' "$RUN_LIST" | awk -F '\t' '$2 == "completed" && $3 != "success" {print $1; exit}')

if [ -n "$ACTIVE_RUN_ID" ]; then
    RUN_ID="$ACTIVE_RUN_ID"
    printf '%bFound an active workflow run for %s; monitoring it.%b\n' \
        "$GREEN" "$REQUESTED_SHORT_SHA" "$RESET"
elif [ -n "$SUCCESS_RUN_ID" ]; then
    RUN_ID="$SUCCESS_RUN_ID"
    printf '%bFound a successful workflow run for %s; reusing its artifact.%b\n' \
        "$GREEN" "$REQUESTED_SHORT_SHA" "$RESET"
elif [ -n "$FAILED_RUN_ID" ]; then
    FAILED_URL=$(printf '%s\n' "$RUN_LIST" | awk -F '\t' -v id="$FAILED_RUN_ID" '$1 == id {print $4; exit}')
    printf '%b当前版本 %s 已编译失败，未重新触发编译。%b\n' \
        "$RED" "$REQUESTED_SHORT_SHA" "$RESET" >&2
    printf '%b请修复后重新提交（push）生成新的提交，再运行本脚本。%b\n' \
        "$RED" "$RESET" >&2
    [ -n "$FAILED_URL" ] && printf 'Failed run: %s\n' "$FAILED_URL" >&2
    exit 1
else
    printf '%bTriggering GitHub Actions workflow for %s @ %s...%b\n' \
        "$CYAN" "$BRANCH" "$REQUESTED_SHORT_SHA" "$RESET"
    "$GH_BIN" workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH" --field compact=true
    printf '%bWorkflow triggered.%b\n' "$GREEN" "$RESET"

    RUN_ID=""
    attempt=1
    while [ "$attempt" -le 12 ]; do
        RUN_ID=$("$GH_BIN" run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" \
            --limit 30 --json databaseId,headSha,status --jq \
            ".[] | select(.headSha == \"$REQUESTED_SHA\" and .status != \"completed\") | .databaseId" \
            2>/dev/null | head -n 1 || true)
        if [ -n "$RUN_ID" ] && [ "$RUN_ID" != "null" ]; then
            break
        fi
        RUN_ID=""
        attempt=$((attempt + 1))
        sleep 2
    done
    [ -n "$RUN_ID" ] || die "Could not find the workflow run that was just triggered."
fi

RUN_SHORT_SHA=$("$GH_BIN" run view "$RUN_ID" --repo "$REPO" --json headSha --jq '.headSha[0:7]') \
    || die "Unable to resolve the commit for workflow run $RUN_ID."
printf 'Monitoring workflow run: %s (commit: %s)\n' "$RUN_ID" "$RUN_SHORT_SHA"
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

ARTIFACT_ID=$("$GH_BIN" api "repos/$REPO/actions/runs/$RUN_ID/artifacts" \
    --jq ".artifacts[] | select(.name == \"$ARTIFACT_NAME\") | .id" | head -n 1)
[ -n "$ARTIFACT_ID" ] || die "Artifact not found for workflow run $RUN_ID."

ARCHIVE_FILE="$TEMP_DIR/artifact.zip"
TOKEN=$("$GH_BIN" auth token) || die "Unable to read GitHub authentication token."
API_URL="https://api.github.com/repos/$REPO/actions/artifacts/$ARTIFACT_ID/zip"

# 先解析 302 拿到 Azure Blob 的签名下载地址。
# 不能把 GitHub 的 Authorization 头带过重定向——Azure 会以 errorCode=24
# "Authorization failed" 拒绝；而签名 URL 本身已含鉴权，无需再带头。
DOWNLOAD_URL=$(curl -sSL -o /dev/null --max-time 30 -x "$PROXY_URL" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -w '%{url_effective}' "$API_URL" 2>/dev/null || true)
[ -n "$DOWNLOAD_URL" ] || die "Unable to resolve artifact download URL."

# 多线程分片下载（10 连接），显著改善单连接被限速的问题。
DOWNLOAD_ATTEMPT=1
while [ "$DOWNLOAD_ATTEMPT" -le 3 ]; do
    START_TIME=$(date +%s)
    # 仅保留单行实时进度（console readout，\r 原地刷新），关闭周期性汇总表格。
    aria2c -x 10 -s 10 -k 1M \
        --allow-overwrite=true --auto-file-renaming=false \
        --file-allocation=none --console-log-level=warn --summary-interval=0 \
        --show-console-readout=true --enable-color=true \
        --all-proxy="$PROXY_URL" \
        --header="Accept: application/vnd.github+json" \
        --dir="$TEMP_DIR" --out="artifact.zip" \
        "$DOWNLOAD_URL"
    ARIA_STATUS=$?
    if [ "$ARIA_STATUS" -eq 0 ] && [ -s "$ARCHIVE_FILE" ]; then
        printf '\n'
        break
    fi
    printf '\nDownload interrupted; retry %s/3...\n' "$DOWNLOAD_ATTEMPT"
    rm -f "$ARCHIVE_FILE" "$ARCHIVE_FILE.aria2"
    DOWNLOAD_ATTEMPT=$((DOWNLOAD_ATTEMPT + 1))
    [ "$DOWNLOAD_ATTEMPT" -le 3 ] && sleep 3
done
[ "$DOWNLOAD_ATTEMPT" -le 3 ] || die "Artifact download failed after 3 attempts."

unzip -oq "$ARCHIVE_FILE" -d "$TEMP_DIR" || die "Artifact archive extraction failed."

APK_FILE=$(find "$TEMP_DIR" -type f -iname '*.apk' -print -quit)
[ -n "$APK_FILE" ] || die "Artifact download completed, but no APK was found."

APK_NAME=$(basename "$APK_FILE")
DESTINATION="$APK_OUTPUT_DIR/$APK_NAME"
if [ -e "$DESTINATION" ]; then
    DESTINATION="$APK_OUTPUT_DIR/${APK_NAME%.apk}-$(date '+%Y%m%d-%H%M%S').apk"
fi
mv "$APK_FILE" "$DESTINATION"

printf '%bAPK ready: %s%b\n' "$GREEN" "$DESTINATION" "$RESET"
