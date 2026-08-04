package com.whmdg.mczj.tools.security

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.IBinder
import com.whmdg.mczj.tools.auth.NativeAuth
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UsageStatsReporter {

    data class AttemptResult(val label: String, val success: Boolean, val detail: String)

    data class ReportResult(
        val success: Boolean,
        val attempts: List<AttemptResult>,
        val eventDetail: String = "",
        val queryVerified: Boolean = false,
        val queryDetail: String = ""
    )

    private const val HOOK_STATUS_FILE = "hook_report_event_result"
    private const val HOOK_BYPASS_FILE = "hook_hidden_api_status"

    fun reportTestEvent(context: Context): ReportResult {
        val packageName = context.packageName
        val timeStamp = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val attempts = mutableListOf<AttemptResult>()

        // 第1次：Hook 已解除限制，JNI 直接访问
        val r1 = tryReportEvent(context)
        attempts.add(AttemptResult("第1次（JNI 直接访问）", r1.success, r1.detail))
        if (r1.success) {
            val eventDetail = "type=USER_INTERACTION(7) pkg=$packageName ts=$timeStamp time=${sdf.format(Date(timeStamp))}"
            return buildResult(true, attempts, eventDetail, context, packageName, timeStamp)
        }

        // 第2次：JNI 先设置解除限制，再访问
        val setOk = try { NativeAuth.bypassHiddenApi() } catch (_: Throwable) { false }
        if (setOk) {
            val r2 = tryReportEvent(context)
            attempts.add(AttemptResult("第2次（JNI 设置后访问）", r2.success, r2.detail))
            if (r2.success) {
                val eventDetail = "type=USER_INTERACTION(7) pkg=$packageName ts=$timeStamp time=${sdf.format(Date(timeStamp))}"
                return buildResult(true, attempts, eventDetail, context, packageName, timeStamp)
            }
        } else {
            attempts.add(AttemptResult("第2次（JNI 设置后访问）", false, "JNI setHiddenApiExemptions 失败"))
        }

        // 第3次：读取 Hook 已执行的结果
        val hookResult = readHookResult(context)
        attempts.add(AttemptResult("第3次（Hook 代为执行）", hookResult.first, hookResult.second))

        if (hookResult.first) {
            val eventDetail = "type=USER_INTERACTION(7) pkg=$packageName ts=$timeStamp time=${sdf.format(Date(timeStamp))}"
            return buildResult(true, attempts, eventDetail, context, packageName, timeStamp)
        }

        return ReportResult(false, attempts)
    }

    private data class TryResult(val success: Boolean, val detail: String)

    private fun tryReportEvent(context: Context): TryResult {
        try {
            val binder = getUsageStatsBinder()
                ?: return TryResult(false, "无法获取 usagestats 服务 binder")
            val proxy = asInterface(binder)

            val event = UsageEvents.Event()
            val cls = UsageEvents.Event::class.java
            cls.getDeclaredField("mEventType").apply { isAccessible = true }.setInt(event, UsageEvents.Event.USER_INTERACTION)
            cls.getDeclaredField("mTimeStamp").apply { isAccessible = true }.setLong(event, System.currentTimeMillis())
            cls.getDeclaredField("mPackageName").apply { isAccessible = true }.set(event, context.packageName)

            val method = proxy.javaClass.getMethod("reportEvent", UsageEvents.Event::class.java, Int::class.java)
            method.invoke(proxy, event, 0)
            return TryResult(true, "reportEvent 调用成功")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            return TryResult(false, "${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun readHookResult(context: Context): Pair<Boolean, String> {
        return try {
            val file = File(context.filesDir, HOOK_STATUS_FILE)
            if (!file.exists()) return Pair(false, "Hook 未执行（文件不存在）")
            val content = file.readText()
            when {
                content.startsWith("ok\n") -> Pair(true, content.removePrefix("ok\n"))
                content.startsWith("fail\n") -> Pair(false, content.removePrefix("fail\n"))
                else -> Pair(false, "未知状态: $content")
            }
        } catch (e: Throwable) {
            Pair(false, "读取失败: ${e.message}")
        }
    }

    fun readHookBypassStatus(context: Context): String {
        return try {
            val file = File(context.filesDir, HOOK_BYPASS_FILE)
            if (!file.exists()) return "未执行"
            val content = file.readText()
            when (content) {
                "ok" -> "成功"
                else -> content
            }
        } catch (e: Throwable) {
            "读取失败: ${e.message}"
        }
    }

    private fun buildResult(
        success: Boolean,
        attempts: List<AttemptResult>,
        eventDetail: String,
        context: Context,
        packageName: String,
        timeStamp: Long
    ): ReportResult {
        val verified = try { verifyEvent(context, packageName, timeStamp) } catch (_: Throwable) { false }
        val queryDetail = if (verified) "查询验证通过：事件已写入" else "查询未找到该事件（可能需要等待 flush）"
        return ReportResult(success, attempts, eventDetail, verified, queryDetail)
    }

    private fun getUsageStatsBinder(): IBinder? {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        return getService.invoke(null, "usagestats") as? IBinder
    }

    private fun asInterface(binder: IBinder): Any {
        val stubClass = Class.forName("android.app.usage.IUsageStatsManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
            ?: throw IllegalStateException("IUsageStatsManager.Stub.asInterface 返回 null")
    }

    private fun verifyEvent(context: Context, packageName: String, afterTime: Long): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val events = usm.queryEvents(afterTime - 1000, System.currentTimeMillis() + 1000)
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            if (event.packageName == packageName && event.eventType == UsageEvents.Event.USER_INTERACTION
                && event.timeStamp >= afterTime - 1000
            ) {
                return true
            }
        }
        return false
    }
}
