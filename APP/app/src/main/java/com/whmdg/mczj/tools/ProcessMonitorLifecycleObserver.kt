package com.whmdg.mczj.tools

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 应用前后台切换监听：驱动 [ProcessMonitorService] 的启停。
 *
 * - onStart（进入前台）：命令 monitor 进程开始抓取日志
 * - onStop（切到后台）：命令 monitor 进程停止抓取，释放资源
 *
 * 这样日志只在用户实际使用应用期间产生，既节省资源，也让日志更聚焦。
 */
class ProcessMonitorLifecycleObserver(private val context: Context) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        ProcessMonitorService.start(context)
    }

    override fun onStop(owner: LifecycleOwner) {
        ProcessMonitorService.stop(context)
    }
}
