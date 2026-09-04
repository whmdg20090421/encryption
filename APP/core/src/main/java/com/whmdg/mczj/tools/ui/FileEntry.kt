package com.whmdg.mczj.tools.ui

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val permission: String = "",
    val size: Long = 0,
    val lastModified: Long = 0,
    val createdAt: Long = 0,
    val compressedSize: Long = 0,
    val isCloudOnly: Boolean = false
)