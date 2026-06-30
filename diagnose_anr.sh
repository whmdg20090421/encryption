#!/bin/bash
# ANR 诊断脚本 - 捕获记账本白屏/无响应的原因
# 用法: adb shell "su -c sh /sdcard/diagnose_anr.sh"

PKG="com.whmdg.mczj.tools"
# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$SCRIPT_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUT_FILE="$OUT_DIR/anr_diag_$TIMESTAMP.txt"

echo "===== ANR 诊断报告 =====" > "$OUT_FILE"
echo "时间: $(date)" >> "$OUT_FILE"
echo "包名: $PKG" >> "$OUT_FILE"
echo "" >> "$OUT_FILE"

# 1. 检查 /data/anr/ 目录下的 traces
echo "===== [1] /data/anr/ traces =====" >> "$OUT_FILE"
if [ -d /data/anr ]; then
    ls -lt /data/anr/ >> "$OUT_FILE" 2>&1
    echo "" >> "$OUT_FILE"
    # 读取最新的 traces 文件
    LATEST=$(ls -t /data/anr/traces*.txt 2>/dev/null | head -1)
    if [ -n "$LATEST" ]; then
        echo "--- 最新 traces: $LATEST ---" >> "$OUT_FILE"
        # 只提取本应用相关的线程堆栈
        sed -n "/----- pid .* at .*/{
            :loop
            N
            /----- end .* -----/!b loop
            /$PKG/p
            d
        }" "$LATEST" >> "$OUT_FILE" 2>&1
        # 如果 sed 没匹配到，直接看最后的完整 traces
        if [ $? -ne 0 ] || [ ! -s "$OUT_FILE" ]; then
            echo "(sed 过滤失败，输出完整 traces 的最后 200 行)" >> "$OUT_FILE"
            tail -200 "$LATEST" >> "$OUT_FILE" 2>&1
        fi
    else
        echo "没有找到 traces 文件" >> "$OUT_FILE"
    fi
else
    echo "/data/anr 目录不存在" >> "$OUT_FILE"
fi
echo "" >> "$OUT_FILE"

# 2. dumpsys activity 获取 ANR 信息
echo "===== [2] dumpsys activity anr =====" >> "$OUT_FILE"
dumpsys activity anr >> "$OUT_FILE" 2>&1
echo "" >> "$OUT_FILE"

# 3. 当前进程状态
echo "===== [3] 进程状态 =====" >> "$OUT_FILE"
PID=$(pidof "$PKG" 2>/dev/null)
if [ -n "$PID" ]; then
    echo "PID: $PID" >> "$OUT_FILE"
    # 线程状态
    echo "--- 线程列表 ---" >> "$OUT_FILE"
    ls /proc/$PID/task/ >> "$OUT_FILE" 2>&1
    echo "" >> "$OUT_FILE"
    # 主线程 wchan（是否在等待内核）
    echo "--- 主线程 wchan ---" >> "$OUT_FILE"
    cat /proc/$PID/task/$PID/wchan >> "$OUT_FILE" 2>&1
    echo "" >> "$OUT_FILE"
    echo "--- 主线程 status ---" >> "$OUT_FILE"
    cat /proc/$PID/task/$PID/status >> "$OUT_FILE" 2>&1
    echo "" >> "$OUT_FILE"
    # 主线程堆栈（内核态）
    echo "--- 主线程内核堆栈 ---" >> "$OUT_FILE"
    cat /proc/$PID/task/$PID/stack >> "$OUT_FILE" 2>&1
    echo "" >> "$OUT_FILE"
    # 所有线程状态摘要
    echo "--- 所有线程状态摘要 ---" >> "$OUT_FILE"
    for TID in /proc/$PID/task/*; do
        T=$(basename "$TID")
        TNAME=$(cat "$TID/comm" 2>/dev/null)
        TSTATE=$(grep "State:" "$TID/status" 2>/dev/null | awk '{print $2}')
        echo "  TID=$T name=$TNAME state=$TSTATE" >> "$OUT_FILE"
    done
else
    echo "进程未运行（可能已崩溃或被杀）" >> "$OUT_FILE"
fi
echo "" >> "$OUT_FILE"

# 4. logcat 最近的 ANR / 崩溃 / GC 日志
echo "===== [4] logcat 关键日志 =====" >> "$OUT_FILE"
logcat -d -t 500 | grep -iE "(ANR|not responding|$PKG|FATAL|Exception|DeadObject|GC_|Slow|jank|Choreographer|skipped.*frames)" >> "$OUT_FILE" 2>&1
echo "" >> "$OUT_FILE"

# 5. 完整 logcat 保存到单独文件
echo "===== [5] 完整 logcat 已保存到 $OUT_DIR/logcat_$TIMESTAMP.txt =====" >> "$OUT_FILE"
logcat -d > "$OUT_DIR/logcat_$TIMESTAMP.txt" 2>&1

# 6. meminfo
echo "===== [6] 内存信息 =====" >> "$OUT_FILE"
dumpsys meminfo "$PKG" >> "$OUT_FILE" 2>&1
echo "" >> "$OUT_FILE"

echo "===== 诊断完成 =====" >> "$OUT_FILE"
echo "输出文件: $OUT_FILE" >> "$OUT_FILE"
echo "完整 logcat: $OUT_DIR/logcat_$TIMESTAMP.txt" >> "$OUT_FILE"

echo ""
echo "===== 诊断完成 ====="
echo "报告: $OUT_FILE"
echo "logcat: $OUT_DIR/logcat_$TIMESTAMP.txt"
echo ""
echo "请将这两个文件发给我分析"
