package com.whmdg.mczj.tools.encryption.services

import android.content.Context
import com.whmdg.mczj.tools.encryption.core.FileCodec
import com.whmdg.mczj.tools.encryption.core.FileConstants
import com.whmdg.mczj.tools.encryption.core.FilenameCodec
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把外部文件加密"进"保险箱，或把箱内文件解密"出"到指定目录。
 */
object CryptoService {

    /** 已打开的保险箱流式写入事务。成功时原子发布密文，失败时删除未完成文件。 */
    class VaultStreamWrite internal constructor(
        private val context: Context,
        private val session: VaultSession,
        private val output: File,
        private val pending: File,
        private val mappingKey: String?,
        private val mappingValue: String?,
        val sink: FileCodec.EncryptingSink
    ) {
        fun finish(): File {
            try {
                sink.finish()
                if (!pending.renameTo(output)) {
                    pending.delete()
                    throw IllegalStateException("无法提交加密文件: ${output.path}")
                }
                if (mappingKey != null && mappingValue != null) {
                    session.nameMapping.set(mappingKey, mappingValue)
                    session.saveNameMapping(context)
                }
                return output
            } catch (e: Exception) {
                pending.delete()
                output.delete()
                throw e
            }
        }

        fun abort() = sink.abort()
    }

    /** 创建一个接收明文分块的保险箱写入器，明文不会落盘。 */
    fun openStreamIntoVault(
        context: Context,
        session: VaultSession,
        sourceName: String,
        subDir: String = "",
        onProgress: (Long) -> Unit = {},
        cancelFlag: AtomicBoolean? = null
    ): VaultStreamWrite {
        val encodedName = if (session.record.encryptFilename) {
            FilenameCodec.encrypt(
                filename = sourceName,
                dek = session.dek,
                aad = if (session.record.customEncryption) FileConstants.aadCustomObf else null
            )
        } else null
        val outName = encodedName?.encoded ?: "$sourceName.whm"
        val targetDir = if (subDir.isEmpty()) session.vaultDir else File(session.vaultDir, subDir)
        val output = File(targetDir, outName)
        val pending = File(targetDir, ".${outName}.part")
        targetDir.mkdirs()
        if (output.exists() || pending.exists()) throw IllegalStateException("目标加密文件已存在: ${output.path}")
        val sink = FileCodec.EncryptingSink(
            dst = pending,
            dek = session.dek,
            encryptMetadata = session.record.encryptMetadata,
            customEncryption = session.record.customEncryption,
            onProgress = onProgress,
            cancelFlag = cancelFlag
        )
        return VaultStreamWrite(context, session, output, pending, encodedName?.mappingKey, encodedName?.mappingValue, sink)
    }

    /**
     * 把 [srcFile] 加密进 [session]，返回生成的 `.whm` 文件路径。
     */
    fun encryptIntoVault(
        context: Context,
        session: VaultSession,
        srcFile: File,
        subDir: String = "",
        overwrite: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        cancelFlag: AtomicBoolean? = null
    ): File {
        val origName = srcFile.name
        val outName: String
        
        if (session.record.encryptFilename) {
            val enc = FilenameCodec.encrypt(
                filename = origName,
                dek = session.dek,
                aad = if (session.record.customEncryption) FileConstants.aadCustomObf else null
            )
            outName = enc.encoded
            if (enc.mappingKey != null && enc.mappingValue != null) {
                session.nameMapping.set(enc.mappingKey, enc.mappingValue)
                session.saveNameMapping(context)
            }
        } else {
            outName = "$origName.whm"
        }

        val targetDir = if (subDir.isEmpty()) session.vaultDir else File(session.vaultDir, subDir)
        val outFile = File(targetDir, outName)
        outFile.parentFile?.mkdirs()
        
        if (outFile.exists() && !overwrite) {
            throw Exception("目标加密文件已存在: ${outFile.path}")
        }

        FileCodec.encrypt(
            src = srcFile,
            dst = outFile,
            dek = session.dek,
            encryptMetadata = session.record.encryptMetadata,
            customEncryption = session.record.customEncryption,
            onProgress = onProgress,
            cancelFlag = cancelFlag
        )
        return outFile
    }

    /**
     * 把 [encryptedFile] 解密到 [outputDir]，返回输出的明文文件路径。
     */
    fun decryptOutOfVault(
        session: VaultSession,
        encryptedFile: File,
        outputDir: File,
        overwrite: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val encName = encryptedFile.name
        var realName = "unnamed_recovered"
        
        if (session.record.encryptFilename) {
            realName = FilenameCodec.decrypt(
                encryptedName = encName,
                dek = session.dek,
                aad = if (session.record.customEncryption) FileConstants.aadCustomObf else null,
                lookupMapping = { session.nameMapping.get(it) }
            )
        } else {
            realName = if (encName.endsWith(".whm")) {
                encName.substring(0, encName.length - 4)
            } else {
                encName
            }
        }

        val outFile = File(outputDir, realName)
        if (outFile.exists() && !overwrite) {
            throw Exception("解密目标已存在: ${outFile.path}")
        }

        FileCodec.decrypt(
            src = encryptedFile,
            dst = outFile,
            dek = session.dek,
            customEncryption = session.record.customEncryption,
            onProgress = onProgress
        )
        return outFile
    }

    /**
     * 列出 vault 内所有 `.whm` 文件（不递归子目录）。
     */
    fun listEncryptedFiles(session: VaultSession): List<File> {
        val files = session.vaultDir.listFiles { _, name -> name.endsWith(".whm") }
        return files?.sortedBy { it.name } ?: emptyList()
    }
}
