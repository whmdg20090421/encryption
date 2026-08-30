package com.whmdg.mczj.tools.util

object ShellEscape {
    fun escape(path: String): String {
        return "'" + path.replace("'", "'\\''") + "'"
    }
}
