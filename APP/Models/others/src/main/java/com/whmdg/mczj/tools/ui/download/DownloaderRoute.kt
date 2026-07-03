package com.whmdg.mczj.tools.ui.download

sealed class DownloaderRoute {
    object BatchDownloader : DownloaderRoute()
    object FADownloader : DownloaderRoute()
    object FALogin : DownloaderRoute()
    object DeviantDownloader : DownloaderRoute()
    object DeviantLogin : DownloaderRoute()
}