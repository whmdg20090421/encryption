package com.whmdg.mczj.tools.ui.filemanager

sealed class FileManagerRoute {
    object Home : FileManagerRoute()
    data class TextEditor(val filePath: String) : FileManagerRoute()
    data class ImageViewer(val filePath: String, val imagePaths: List<String> = emptyList(), val startIndex: Int = 0) : FileManagerRoute()
}