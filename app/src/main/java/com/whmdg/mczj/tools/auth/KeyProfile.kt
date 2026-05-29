package com.whmdg.mczj.tools.auth

import android.util.Base64
import android.util.Log

object KeyProfile {

    private const val TAG = "KeyProfile"

    private const val K1 = "azE="
    private const val K2 = "azI="
    private const val K3 = "azM="

    private fun decode(b64: String): String =
        String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)

    val PROFILES: Map<String, Set<Feature>> = run {
        val m = mapOf(
            decode(K1) to Feature.values().toSet(),
            decode(K2) to setOf(Feature.ENCRYPTION_VAULT, Feature.BATCH_DOWNLOADER, Feature.SECURITY_SETTINGS),
            decode(K3) to setOf(Feature.BATCH_DOWNLOADER, Feature.SECURITY_SETTINGS)
        )
        Log.d(TAG, "PROFILES initialized: ${m.keys}")
        m.forEach { (k, v) -> Log.d(TAG, "  key=$k features=$v") }
        m
    }

    fun featuresFor(keyId: String): Set<Feature> {
        val f = PROFILES[keyId] ?: emptySet()
        Log.d(TAG, "featuresFor($keyId) = $f (map keys: ${PROFILES.keys})")
        return f
    }

    fun allKeyIds(): Set<String> = PROFILES.keys
}
