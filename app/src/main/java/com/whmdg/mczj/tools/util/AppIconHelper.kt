package com.whmdg.mczj.tools.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 应用图标辅助工具：在文件管理器中为已安装应用的包名目录显示应用图标。
 *
 * 使用方式：
 * 1. 应用启动时调用 [init] 缓存包名集合（图标懒加载）
 * 2. 渲染文件列表时调用 [getAppIconBitmap] 获取图标
 */
object AppIconHelper {

    /** 硬编码的应用专属目录前缀 */
    private val APP_DIR_PREFIXES = listOf(
        "/data/data/",
        "/data/user/",
        "/data/user_de/",
        "/data/app/",
        "/sdcard/Android/data/",
        "/sdcard/Android/obb/",
    )

    /** 缓存：包名集合 */
    private var packageNames: Set<String> = emptySet()

    /** 缓存：包名 → 图标 Bitmap（懒加载） */
    private val iconCache = mutableMapOf<String, ImageBitmap>()

    /** 初始化，扫描已安装应用的包名集合。建议在 Application.onCreate 或首次进入文件管理器时调用。 */
    fun init(context: Context) {
        val pm = context.packageManager
        packageNames = pm.getInstalledPackages(0).map { it.packageName }.toSet()
    }

    /** 当前路径是否属于应用专属目录（即子目录可能是包名目录） */
    fun isAppDirParent(parentPath: String): Boolean {
        val normalized = parentPath.trimEnd('/') + "/"
        val prefix = APP_DIR_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return false
        val afterPrefix = normalized.removePrefix(prefix)
        // prefix 之后应恰好有一段（包名），如 "com.example.app/"
        return afterPrefix.isNotEmpty() && afterPrefix.count { it == '/' } == 1
    }

    /** 指定目录是否是一个已安装应用的包名目录 */
    fun isAppPackageDir(parentPath: String, dirName: String): Boolean {
        return dirName in packageNames && isAppDirParent(parentPath)
    }

    /**
     * 获取应用图标，返回 Compose 可用的 ImageBitmap。
     * 首次调用时从 PackageManager 加载并缓存。
     */
    fun getAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
        iconCache[packageName]?.let { return it }

        val pm = context.packageManager
        val drawable: Drawable = try {
            pm.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val bitmap = drawableToBitmap(drawable) ?: return null
        val imageBitmap = bitmap.asImageBitmap()
        iconCache[packageName] = imageBitmap
        return imageBitmap
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }
}
