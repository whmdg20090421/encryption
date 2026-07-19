#!/bin/bash
# 艨艟战舰工具箱 - 白屏诊断脚本
# 用法: su -c sh diagnose.sh

PKG="com.whmdg.mczj.tools"
echo "=========================================="
echo "  艨艟战舰工具箱 - 白屏诊断报告"
echo "=========================================="
echo ""

# 1. 检查应用是否安装
echo "[1/6] 检查应用安装状态..."
pm list packages | grep "$PKG" && echo "  ✓ 应用已安装" || echo "  ✗ 应用未安装"
echo ""

# 2. 检查应用是否在运行
echo "[2/6] 检查进程状态..."
PID=$(pidof "$PKG" 2>/dev/null)
if [ -n "$PID" ]; then
    echo "  ✓ 进程运行中 (PID: $PID)"
else
    echo "  ✗ 进程未运行（可能已崩溃）"
fi
echo ""

# 3. 崩溃日志（最近 5 分钟）
echo "[3/6] 最近崩溃日志 (logcat -d -b crash)..."
echo "----------------------------------------"
logcat -d -b crash 2>/dev/null | grep -i "$PKG\|whmdg\|mczj" | tail -30
echo "----------------------------------------"
echo ""

# 4. FATAL EXCEPTION
echo "[4/6] FATAL EXCEPTION..."
echo "----------------------------------------"
logcat -d -s AndroidRuntime:E 2>/dev/null | tail -60
echo "----------------------------------------"
echo ""

# 5. 应用自身日志（DiagnosticLog + 通用错误）
echo "[5/6] 应用日志 (最近 200 条含 $PKG 的日志)..."
echo "----------------------------------------"
logcat -d 2>/dev/null | grep -i "$PKG\|ToolsApp\|CrashMonitor\|DiagnosticLog\|NativeCrash\|MainActivity" | tail -200
echo "----------------------------------------"
echo ""

# 6. JNI / Native 崩溃
echo "[6/6] JNI / Native 相关日志..."
echo "----------------------------------------"
logcat -d 2>/dev/null | grep -iE "JNI|native.*crash|authcore|signal.*SEGV|signal.*ABRT|Fatal signal" | tail -30
echo "----------------------------------------"
echo ""

echo "=========================================="
echo "  诊断完成"
echo "=========================================="
