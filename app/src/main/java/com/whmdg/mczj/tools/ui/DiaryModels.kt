package com.whmdg.mczj.tools.ui

import kotlinx.serialization.Serializable

@Serializable
data class DiaryBook(
    val name: String,
    val createdAt: Long,
    val lastEditedAt: Long
)
