package com.whmdg.mczj.tools.security

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.IBinder
import com.whmdg.mczj.tools.auth.NativeAuth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UsageStatsReporter {

    data class ReportResult(
        val success: Boolean,
        val message: String,
        val eventDetail: String = "",
        val queryVerified: Boolean = false,
        val queryDetail: String = ""
    )

    fun reportTestEvent(context: Context): ReportResult {
        // 绕过 hidden API 限制
        try {
            if (!NativeAuth.bypassHiddenApi()) {
                return ReportResult(false, "JNI setHiddenApiExemptions 调用失败")
            }
        } catch (e: Throwable) {
            return ReportResult(false, "绕过 hidden API 失败: ${e.message}")
        }

        // 获取 IUsageStatsManager binder
        val proxy: Any
        try {
            val binder = getUsageStatsBinder()
                ?: return ReportResult(false, "无法获取 usagestats 服务 binder")
            proxy = asInterface(binder)
        } catch (e: Throwable) {
            return ReportResult(false, "获取 IUsageStatsManager 失败: ${e.message}")
        }

        // 构造 Event（动态查找字段，兼容不同设备）
        val event: UsageEvents.Event
        val packageName = context.packageName
        val timeStamp = System.currentTimeMillis()
        try {
            event = UsageEvents.Event()
            setEventFields(event, packageName)
        } catch (e: Throwable) {
            return ReportResult(false, "构造 Event 失败: ${e.message}")
        }

        // 调用 reportEvent
        try {
            val method = proxy.javaClass.getMethod(
                "reportEvent",
                UsageEvents.Event::class.java,
                Int::class.java
            )
            method.invoke(proxy, event, 0)
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            return ReportResult(false, "reportEvent 调用失败: ${cause.javaClass.simpleName}: ${cause.message}")
        }

        // 等待后查询验证
        Thread.sleep(500)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val eventDetail = "type=USER_INTERACTION(7) pkg=$packageName ts=$timeStamp time=${sdf.format(Date(timeStamp))}"

        return try {
            val verified = verifyEvent(context, packageName, timeStamp)
            ReportResult(
                success = true,
                message = "reportEvent 调用成功",
                eventDetail = eventDetail,
                queryVerified = verified,
                queryDetail = if (verified) "查询验证通过：事件已写入" else "查询未找到该事件（可能需要等待 flush）"
            )
        } catch (e: Throwable) {
            ReportResult(
                success = true,
                message = "reportEvent 调用成功，但查询验证失败: ${e.message}",
                eventDetail = eventDetail
            )
        }
    }

    /**
     * 动态查找 Event 字段并设置值（字段名因设备/Android 版本而异）。
     */
    private fun setEventFields(event: UsageEvents.Event, packageName: String) {
        val cls = UsageEvents.Event::class.java
        for (f in cls.declaredFields) {
            f.isAccessible = true
            when {
                f.type == Int::class.javaPrimitiveType && f.getInt(event) == 0 ->
                    f.setInt(event, UsageEvents.Event.USER_INTERACTION)
                f.type == Long::class.javaPrimitiveType && f.getLong(event) == 0L ->
                    f.setLong(event, System.currentTimeMillis())
                f.type == String::class.java && f.get(event) == null ->
                    f.set(event, packageName)
            }
        }
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
