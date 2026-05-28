package com.whmdg.mczj.tools.auth

import android.util.Base64

object KeyProfile {

    private const val K1 = "azE="
    private const val K2 = "azI="
    private const val K3 = "azM="

    private fun decode(b64: String): String =
        String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)

    val PROFILES: Map<String, Set<Feature>> = mapOf(
        decode(K1) to Feature.values().toSet(),
        decode(K2) to setOf(Feature.ENCRYPTION_VAULT, Feature.FA_DOWNLOADER),
        decode(K3) to setOf(Feature.FA_DOWNLOADER)
    )

    fun featuresFor(keyId: String): Set<Feature> =
        PROFILES[keyId] ?: emptySet()

    fun allKeyIds(): Set<String> = PROFILES.keys
}
