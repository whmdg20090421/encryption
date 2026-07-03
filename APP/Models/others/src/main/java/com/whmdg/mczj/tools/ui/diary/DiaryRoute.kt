package com.whmdg.mczj.tools.ui.diary

sealed class DiaryRoute {
    object Home : DiaryRoute()
    data class BookDetail(val bookName: String) : DiaryRoute()
}