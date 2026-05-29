#!/usr/bin/env bash
# 等待 GitHub Actions 构建完成并自动下载 APK
# 用法: ./tools/wait_and_download.sh <run_id>
#   或: ./tools/wait_and_download.sh              (自动取最新一次 run)
#
# 依赖: gh CLI (已认证)
# 下载目录: /root/Android_tools/build_output/

set -euo pipefail

REPO="whmdg20090421/encryption"
ARTIFACT_NAME="工具箱-arm64-v8a-release"
OUTPUT_DIR="/root/Android_tools/apk_output"
POLL_INTERVAL=30  # 秒

# ── 获取 run ID ──
if [[ $# -ge 1 ]]; then
    RUN_ID="$1"
else
    echo "⏳ 未指定 run_id，正在获取最新一次 workflow run..."
    RUN_ID=$(gh api "repos/$REPO/actions/runs?per_page=1" --jq '.workflow_runs[0].id')
    if [[ -z "$RUN_ID" ]]; then
        echo "❌ 无法获取最新 run，请手动指定: $0 <run_id>"
        exit 1
    fi
fi

echo "📦 监控 Run ID: $RUN_ID"
echo "🔄 轮询间隔: ${POLL_INTERVAL}s"
echo "📂 下载目录: $OUTPUT_DIR"
echo ""

# ── 轮询状态 ──
while true; do
    RESULT=$(gh api "repos/$REPO/actions/runs/$RUN_ID" --jq '{
        status:   .status,
        conclusion: .conclusion,
        name:     .name,
        html_url: .html_url
    }' 2>/dev/null)

    STATUS=$(echo "$RESULT" | jq -r '.status')
    CONCLUSION=$(echo "$RESULT" | jq -r '.conclusion')
    RUN_NAME=$(echo "$RESULT" | jq -r '.name')
    RUN_URL=$(echo "$RESULT" | jq -r '.html_url')

    TIMESTAMP=$(date '+%H:%M:%S')

    if [[ "$STATUS" == "completed" ]]; then
        echo ""
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        if [[ "$CONCLUSION" == "success" ]]; then
            echo "✅ [$TIMESTAMP] 构建成功！"
        else
            echo "❌ [$TIMESTAMP] 构建失败: $CONCLUSION"
            echo "🔗 $RUN_URL"
            echo ""
            echo "查看日志: gh run view $RUN_ID --log-failed"
            exit 1
        fi
        break
    fi

    echo "⏳ [$TIMESTAMP] 状态: $STATUS — 等待 ${POLL_INTERVAL}s..."
    sleep "$POLL_INTERVAL"
done

# ── 下载 artifact ──
echo ""
echo "📥 正在下载 artifact..."
mkdir -p "$OUTPUT_DIR"

# 清理旧文件
rm -f "$OUTPUT_DIR"/*.apk 2>/dev/null || true

gh run download "$RUN_ID" \
    --name "$ARTIFACT_NAME" \
    --dir "$OUTPUT_DIR" \
    --repo "$REPO"

# ── 找到 APK 并报告 ──
APK_FILE=$(find "$OUTPUT_DIR" -name "*.apk" -type f | head -1)

if [[ -n "$APK_FILE" ]]; then
    APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "🎉 APK 已下载！"
    echo "📂 路径: $APK_FILE"
    echo "📏 大小: $APK_SIZE"
    echo "🔗 构建: $RUN_URL"
else
    echo "⚠️ 下载完成但未找到 APK 文件，请检查: $OUTPUT_DIR"
    ls -la "$OUTPUT_DIR"
fi
