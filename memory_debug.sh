#!/system/bin/sh
# 内存诊断脚本 — root 权限运行
# 用法: su -c sh memory_debug.sh

echo "=========================================="
echo "  内存诊断脚本"
echo "=========================================="
echo ""

# ========== 1. /proc/meminfo 原始数据 ==========
echo "========== [1] /proc/meminfo 关键字段 =========="
echo ""
for field in MemTotal MemFree MemAvailable Buffers Cached SwapCached SReclaimable SUnreclaim PageTables KernelStack VmallocUsed CmaTotal CmaFree AnonPages Shmem Slab; do
    val=$(grep "^${field}:" /proc/meminfo | awk '{print $2}')
    printf "%-20s %10s KB\n" "$field" "$val"
done
echo ""

# 计算各项
MEM_TOTAL=$(grep "^MemTotal:" /proc/meminfo | awk '{print $2}')
MEM_AVAIL=$(grep "^MemAvailable:" /proc/meminfo | awk '{print $2}')
REAL_USED=$((MEM_TOTAL - MEM_AVAIL))

S_UNRECLAIM=$(grep "^SUnreclaim:" /proc/meminfo | awk '{print $2}')
KERNEL_STACK=$(grep "^KernelStack:" /proc/meminfo | awk '{print $2}')
VMALLOC_USED=$(grep "^VmallocUsed:" /proc/meminfo | awk '{print $2}')
CMA_TOTAL=$(grep "^CmaTotal:" /proc/meminfo | awk '{print $2}')
CMA_FREE=$(grep "^CmaFree:" /proc/meminfo | awk '{print $2}')
CMA_USED=$((CMA_TOTAL - CMA_FREE))
PAGE_TABLES=$(grep "^PageTables:" /proc/meminfo | awk '{print $2}')
ANON_PAGES=$(grep "^AnonPages:" /proc/meminfo | awk '{print $2}')
SHMEM=$(grep "^Shmem:" /proc/meminfo | awk '{print $2}')
SLAB=$(grep "^Slab:" /proc/meminfo | awk '{print $2}')
S_RECLAIM=$(grep "^SReclaimable:" /proc/meminfo | awk '{print $2}')
BUFFERS=$(grep "^Buffers:" /proc/meminfo | awk '{print $2}')
CACHED=$(grep "^Cached:" /proc/meminfo | awk '{print $2}')
SWAP_CACHED=$(grep "^SwapCached:" /proc/meminfo | awk '{print $2}')

echo "---------- 计算结果 ----------"
echo "真实已用 X = MemTotal - MemAvailable = $MEM_TOTAL - $MEM_AVAIL = $REAL_USED KB"
echo ""
echo "内核字段累加（我的旧 Y 公式中的内核部分）:"
echo "  SUnreclaim   = $S_UNRECLAIM KB"
echo "  KernelStack  = $KERNEL_STACK KB"
echo "  VmallocUsed  = $VMALLOC_USED KB"
echo "  CMA 已用     = $CMA_USED KB"
echo "  PageTables   = $PAGE_TABLES KB"
KERNEL_SUM=$((S_UNRECLAIM + KERNEL_STACK + VMALLOC_USED + CMA_USED + PAGE_TABLES))
echo "  合计 = $KERNEL_SUM KB"
echo ""
echo "文件缓存:"
echo "  Buffers      = $BUFFERS KB"
echo "  Cached       = $CACHED KB"
echo "  SwapCached   = $SWAP_CACHED KB"
FILE_CACHE=$((BUFFERS + CACHED + SWAP_CACHED))
echo "  合计 = $FILE_CACHE KB"
echo ""

# ========== 2. dumpsys meminfo 关键行 ==========
echo "========== [2] dumpsys meminfo 关键行 =========="
echo ""
DUMPSYS_OUTPUT=$(dumpsys meminfo)

echo "--- Used RAM 行 ---"
echo "$DUMPSYS_OUTPUT" | grep "Used RAM:"
echo ""

echo "--- Lost RAM 行 ---"
echo "$DUMPSYS_OUTPUT" | grep "Lost RAM:"
echo ""

echo "--- DMA-BUF 行 ---"
echo "$DUMPSYS_OUTPUT" | grep "^DMA-BUF:" | head -1
echo ""

echo "--- GPU 行 ---"
echo "$DUMPSYS_OUTPUT" | grep "GPU:" | head -1
echo ""

echo "--- ZRAM 行 ---"
echo "$DUMPSYS_OUTPUT" | grep "ZRAM:"
echo ""

# 解析 dumpsys 中的 kernel 值
DUMPSYS_KERNEL=$(echo "$DUMPSYS_OUTPUT" | grep "Used RAM:" | grep -o '[0-9,]*K kernel' | grep -o '[0-9,]*' | tr -d ',')
DUMPSYS_PSS=$(echo "$DUMPSYS_OUTPUT" | grep "Used RAM:" | grep -o '[0-9,]*K used pss' | grep -o '[0-9,]*' | tr -d ',')

echo "--- 解析结果 ---"
echo "dumpsys PSS 总计    = $DUMPSYS_PSS KB"
echo "dumpsys kernel 总计 = $DUMPSYS_KERNEL KB"
echo ""

# ========== 3. 进程 PSS 累加 ==========
echo "========== [3] 进程 PSS (Total PSS by process) =========="
echo ""

PROCESS_PSS_SUM=0
PROCESS_COUNT=0

echo "$DUMPSYS_OUTPUT" | sed -n '/Total PSS by process:/,/Total PSS by OOM adjustment:/p' | grep -E '^\s+[0-9,]+K:' | while IFS= read -r line; do
    pss=$(echo "$line" | grep -o '^\s*[0-9,]*K' | tr -d ' K,')
    name=$(echo "$line" | sed 's/^\s*[0-9,]*K:\s*//' | sed 's/\s*(pid [0-9]*).*//')
    printf "%10s KB  %s\n" "$pss" "$name"
done

# 重新计算累加
PROCESS_PSS_SUM=$(echo "$DUMPSYS_OUTPUT" | sed -n '/Total PSS by process:/,/Total PSS by OOM adjustment:/p' | grep -E '^\s+[0-9,]+K:' | grep -o '^\s*[0-9,]*K' | tr -d ' K,' | awk '{sum+=$1} END {print sum}')
PROCESS_COUNT=$(echo "$DUMPSYS_OUTPUT" | sed -n '/Total PSS by process:/,/Total PSS by OOM adjustment:/p' | grep -cE '^\s+[0-9,]+K:')

echo ""
echo "进程数量: $PROCESS_COUNT"
echo "进程 PSS 累加 = $PROCESS_PSS_SUM KB"
echo ""

# ========== 4. DMA-BUF 解析 ==========
echo "========== [4] DMA-BUF 解析 =========="
echo ""
DMA_LINE=$(echo "$DUMPSYS_OUTPUT" | grep "^DMA-BUF:" | head -1)
echo "原始行: $DMA_LINE"
DMA_TOTAL=$(echo "$DMA_LINE" | grep -o 'DMA-BUF:\s*[0-9,]*K' | grep -o '[0-9,]*' | tr -d ',')
echo "解析值: $DMA_TOTAL KB"
echo ""

# ========== 5. GPU 解析 ==========
echo "========== [5] GPU 解析 =========="
echo ""
GPU_LINE=$(echo "$DUMPSYS_OUTPUT" | grep "GPU:" | head -1)
echo "原始行: $GPU_LINE"
GPU_TOTAL=$(echo "$GPU_LINE" | grep -o 'GPU:\s*[0-9,]*K' | grep -o '[0-9,]*' | tr -d ',')
echo "解析值: $GPU_TOTAL KB"
echo ""

# ========== 6. 各种 Y 公式对比 ==========
echo "=========================================="
echo "  各种 Y 公式对比"
echo "=========================================="
echo ""

echo "X (真实已用)           = $REAL_USED KB"
echo ""

# 旧公式 Y = PSS + SUnreclaim + KernelStack + VmallocUsed + CMA + PageTables + DMA-BUF + 文件缓存
Y_OLD=$((PROCESS_PSS_SUM + KERNEL_SUM + DMA_TOTAL + FILE_CACHE))
echo "旧 Y = PSS + 内核字段 + DMA-BUF + 文件缓存"
echo "     = $PROCESS_PSS_SUM + $KERNEL_SUM + $DMA_TOTAL + $FILE_CACHE"
echo "     = $Y_OLD KB"
echo "     差额 X - Y_OLD = $((REAL_USED - Y_OLD)) KB"
echo ""

# 新公式 Y = PSS + dumpsys_kernel + 文件缓存
Y_NEW=$((PROCESS_PSS_SUM + DUMPSYS_KERNEL + FILE_CACHE))
echo "新 Y = PSS + dumpsys_kernel + 文件缓存"
echo "     = $PROCESS_PSS_SUM + $DUMPSYS_KERNEL + $FILE_CACHE"
echo "     = $Y_NEW KB"
echo "     差额 X - Y_NEW = $((REAL_USED - Y_NEW)) KB"
echo ""

# 只有 PSS + 文件缓存
Y_PSS_ONLY=$((PROCESS_PSS_SUM + FILE_CACHE))
echo "PSS + 文件缓存（不含内核）"
echo "     = $PROCESS_PSS_SUM + $FILE_CACHE"
echo "     = $Y_PSS_ONLY KB"
echo ""

# ========== 7. 重叠检测 ==========
echo "=========================================="
echo "  重叠检测"
echo "=========================================="
echo ""
echo "dumpsys 报告 kernel = $DUMPSYS_KERNEL KB"
echo "我的内核字段累加   = $KERNEL_SUM KB"
echo "差值 (我的 - dumpsys) = $((KERNEL_SUM - DUMPSYS_KERNEL)) KB"
echo ""
echo "如果差值为正且较大，说明内核字段中有些已含在 dumpsys kernel 里，存在重复"
echo ""

# PageTables 是否在 dumpsys kernel 中
echo "PageTables = $PAGE_TABLES KB"
echo "如果不含在 dumpsys kernel 中，差值应接近 PageTables"
echo "差值 = $((KERNEL_SUM - DUMPSYS_KERNEL)) KB vs PageTables = $PAGE_TABLES KB"
echo ""

echo "=========================================="
echo "  诊断完成"
echo "=========================================="
