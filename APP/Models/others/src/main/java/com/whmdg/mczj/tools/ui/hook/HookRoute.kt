package com.whmdg.mczj.tools.ui.hook

sealed class HookRoute {
    object Home : HookRoute()
    data class Detail(val packageName: String) : HookRoute()
}
