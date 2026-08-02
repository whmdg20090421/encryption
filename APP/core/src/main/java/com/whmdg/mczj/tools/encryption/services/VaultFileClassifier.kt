package com.whmdg.mczj.tools.encryption.services

import android.content.Context
import com.whmdg.mczj.tools.encryption.data.VaultPaths
import com.whmdg.mczj.tools.encryption.data.VaultRecord
import java.io.File

/**
 * 文件路径分类结果。
 */
sealed class VaultClassification {
    /** 不在任何保险箱内 */
    data object External : VaultClassification()

    /** 在某个保险箱内 */
    data class InVault(
        val record: VaultRecord,
        val vaultDir: File,
        val subPath: String // 相对于 vaultDir 的子路径（空字符串表示根目录）
    ) : VaultClassification()
}

/**
 * 批量分类结果。
 */
data class BatchClassificationResult(
    /** 是否合法（无混合类型） */
    val isValid: Boolean,
    /** 每个路径的分类 */
    val classifications: List<Pair<String, VaultClassification>>,
    /** 错误消息（isValid=false 时有值） */
    val errorMessage: String? = null
)

/**
 * 按路径前缀判断文件属于哪个保险箱。
 */
class VaultFileClassifier(
    private val context: Context,
    vaults: List<VaultRecord>
) {
    /** 已解析的保险箱目录列表，按路径长度降序（优先匹配最深的） */
    private val vaultDirs: List<Triple<String, VaultRecord, File>> = vaults
        .map { rec ->
            val dir = VaultPaths.resolveVault(context, rec.location, rec.relativePath)
            Triple(dir.absolutePath, rec, dir)
        }
        .sortedByDescending { it.first.length }

    /**
     * 判断单个路径属于哪个保险箱。
     */
    fun classify(path: String): VaultClassification {
        val absPath = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            File(path).absolutePath
        }

        for ((vaultAbsPath, record, vaultDir) in vaultDirs) {
            if (absPath == vaultAbsPath || absPath.startsWith("$vaultAbsPath/")) {
                val subPath = if (absPath == vaultAbsPath) {
                    ""
                } else {
                    absPath.substring(vaultAbsPath.length + 1)
                }
                return VaultClassification.InVault(record, vaultDir, subPath)
            }
        }

        return VaultClassification.External
    }

    /**
     * 批量分类源路径列表，判断是否合法（无混合类型）。
     *
     * 合法情况：
     * - 全部是外部文件
     * - 全部在同一保险箱内
     *
     * 不合法情况：
     * - 部分外部 + 部分保险箱
     * - 跨多个不同保险箱
     */
    fun classifyBatch(paths: List<String>): BatchClassificationResult {
        val classifications = paths.map { it to classify(it) }

        var externalCount = 0
        var firstVaultId: Int? = null
        var hasMixedVaults = false

        for ((_, cls) in classifications) {
            when (cls) {
                is VaultClassification.External -> externalCount++
                is VaultClassification.InVault -> {
                    if (firstVaultId == null) {
                        firstVaultId = cls.record.id
                    } else if (firstVaultId != cls.record.id) {
                        hasMixedVaults = true
                    }
                }
            }
        }

        val vaultCount = classifications.size - externalCount

        return when {
            // 全部外部
            vaultCount == 0 -> BatchClassificationResult(
                isValid = true,
                classifications = classifications
            )
            // 全部在同一保险箱
            externalCount == 0 && !hasMixedVaults -> BatchClassificationResult(
                isValid = true,
                classifications = classifications
            )
            // 混合类型
            hasMixedVaults -> BatchClassificationResult(
                isValid = false,
                classifications = classifications,
                errorMessage = "选中的文件来自不同的加密保险箱，无法同时操作。请分批处理。"
            )
            // 外部 + 保险箱混合
            else -> BatchClassificationResult(
                isValid = false,
                classifications = classifications,
                errorMessage = "选中的文件中包含加密保险箱内部和外部文件，无法同时操作。请分批处理。"
            )
        }
    }
}
