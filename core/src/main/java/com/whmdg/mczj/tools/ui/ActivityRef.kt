package com.whmdg.mczj.tools.ui

import android.app.Activity

object ActivityRef {
    @Volatile var currentActivity: Activity? = null
    var element4TopPx: Float = 0f
}
