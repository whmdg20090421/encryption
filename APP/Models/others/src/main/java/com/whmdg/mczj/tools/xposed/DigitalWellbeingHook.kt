package com.whmdg.mczj.tools.xposed

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker

/**
 * Hook 数字健康应用（com.coloros.digitalwellbeing）
 *
 * 数据流分析：
 *   主界面 → PhoneUseTimeViewModel → LoadRealDataRepository.loadDateData() → 路径A
 *   统计/详情 → UsageStatisticsViewModel / TimeUsageDetailViewModel → UsageRepository → 路径B
 *
 * 两条路径独立，但最终都调用 UsageStatsManager.queryUsageStats() 获取原始数据。
 * 在这个必经之路上拦截，一个 Hook 点覆盖所有 UI。
 */
object DigitalWellbeingHook : YukiBaseHooker() {

    private const val TAG = "MCZJ_DWHook"

    /** 需要隐藏的应用包名列表 */
    private val HIDDEN_PACKAGES = setOf(
        "com.termux"
    )

    override fun onHook() {
        // UsageStatsManager.queryUsageStats() 是所有路径的必经之路
        // 路径A（主界面）和路径B（统计/详情）最终都调这个方法拿数据
        // 在这里过滤，一个点覆盖所有 UI
        UsageStatsManager::class.java.hook {
            injectMember {
                method {
                    name = "queryUsageStats"
                    param(IntType, LongType, LongType)
                }
                afterHook {
                    filterUsageStatsList(result)
                }
            }
        }

        Log.i(TAG, "Hook 已注入，隐藏列表: $HIDDEN_PACKAGES")
    }

    /**
     * 过滤 List<UsageStats>，移除隐藏包名的条目。
     * queryUsageStats 返回的是 ArrayList，可以直接 remove。
     */
    @Suppress("UNCHECKED_CAST")
    private fun filterUsageStatsList(result: Any?) {
        if (result !is MutableList<*>) return
        val iterator = result.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            if (item is UsageStats && item.packageName in HIDDEN_PACKAGES) {
                iterator.remove()
                Log.d(TAG, "已移除: ${item.packageName}")
            }
        }
    }
}
