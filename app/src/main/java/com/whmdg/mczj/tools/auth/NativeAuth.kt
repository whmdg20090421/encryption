package com.whmdg.mczj.tools.auth

object NativeAuth {

    init {
        System.loadLibrary("authcore")
    }

    external fun verifyPassword(pw: String): ByteArray?

    external fun keyIdOf(pw: String): Int
}
