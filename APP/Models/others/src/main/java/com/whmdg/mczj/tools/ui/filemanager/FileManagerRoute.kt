package com.whmdg.mczj.tools.ui.filemanager

sealed class FileManagerRoute {
    object Home : FileManagerRoute()
    // ImageViewer 和 TextEditor 已迁移到独立的 ViewerActivity
}
