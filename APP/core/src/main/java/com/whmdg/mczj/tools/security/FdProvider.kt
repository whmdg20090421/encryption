package com.whmdg.mczj.tools.security

import android.os.ParcelFileDescriptor

/**
 * FD 获取薄封装。
 * 内部委托 ShellExecutor 按 Permission.MAX 路由到 ROOT/ADB/APPLICANT。
 * 调用方只需传入路径，不关心底层权限路由。
 */
object FdProvider {

    fun openForRead(path: String): ParcelFileDescriptor =
        ShellExecutor.openForRead(Permission.MAX, path)

    fun openForWrite(path: String): ParcelFileDescriptor =
        ShellExecutor.openForWrite(Permission.MAX, path)
}
