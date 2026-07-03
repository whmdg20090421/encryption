package com.whmdg.mczj.tools.ui.filemanager

sealed class FileManagerRoute {
    object Home : FileManagerRoute()
    data class TextEditor(val filePath: String) : FileManagerRoute()
    data class ImageViewer(val filePath: String) : FileManagerRoute()
}