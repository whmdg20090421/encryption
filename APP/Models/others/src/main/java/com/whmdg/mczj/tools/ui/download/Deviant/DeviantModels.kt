package com.whmdg.mczj.tools.ui.download.Deviant

import android.net.Uri
import com.whmdg.mczj.tools.ui.download.DownloadLog
import com.whmdg.mczj.tools.ui.download.NetworkStatus

/** Deviation 元数据 */
data class DeviationMeta(
    val deviationId: String = "",
    val title: String = "",
    val author: String = "",
    val imageUrl: String = "",
    val fileName: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val description: String = ""
)

/** 预览列表中的单个下载任务 */
data class DeviantPreviewItem(
    val seq: Int,
    val fileName: String,
    val imageUrl: String,
    val title: String = "",
    val deviationId: String = "",
    val author: String = ""
)

/** UI 状态 */
data class DeviantUiState(
    val username: String = "",
    val galleryType: String = "gallery", // gallery / favourites
    val saveDir: Uri? = null,
    val saveDirPath: String = "",
    val isLoggedIn: Boolean = false,
    val networkStatus: NetworkStatus = NetworkStatus.CHECKING,
    val isDownloading: Boolean = false,
    val isCollecting: Boolean = false,
    val showPreview: Boolean = false,
    val pendingTasks: List<DeviantPreviewItem> = emptyList(),
    val logs: List<DownloadLog> = emptyList(),
    val downloadedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val currentProgress: Float = 0f,
    val statusMessage: String = "准备就绪",
    val errorMessage: String? = null,
    val isPaused: Boolean = false,
    val collectionComplete: Boolean = false,
    val collectionLoaded: Int = 0,
    val collectionTotal: Int = 0,
    val downloadThreads: Int = 2,
    val skipExisting: Boolean = true
)
