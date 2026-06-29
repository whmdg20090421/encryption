package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 彩色图标显示组件。
 * 从 assets/color_icons/ 加载 PNG，支持可选的文字叠加层。
 *
 * @param buildInId build_in_XXXX 格式的图标 ID
 * @param overlay   可选叠加字符（如红包分类的"爸"、"妈"），渲染在图标正中间
 */
@Composable
fun ColorIconImage(
    buildInId: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    overlay: String? = null
) {
    val context = LocalContext.current
    val bitmap = remember(buildInId) {
        ColorIconBitmapCache.get(context, buildInId)
    }
    if (bitmap != null) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size)
            )
            if (!overlay.isNullOrEmpty()) {
                Text(
                    text = overlay,
                    color = Color.White,
                    fontSize = (size.value * 0.45f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else {
        Box(modifier = modifier.size(size))
    }
}

/**
 * 全局 LRU 缓存，避免重复解码 PNG。
 * 279 张图标 × ~10KB ≈ 2.8MB，分配 4MB 缓存足够。
 */
private object ColorIconBitmapCache {
    private val cache = LruCache<String, android.graphics.Bitmap>(4 * 1024 * 1024)

    fun get(context: Context, buildInId: String): android.graphics.Bitmap? {
        cache.get(buildInId)?.let { return it }
        val filename = ColorIconRegistry.getFilename(buildInId) ?: return null
        return try {
            context.assets.open("color_icons/$filename.png").use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    cache.put(buildInId, bitmap)
                }
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }
}
