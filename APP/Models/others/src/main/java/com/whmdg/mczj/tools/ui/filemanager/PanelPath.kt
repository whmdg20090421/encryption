package com.whmdg.mczj.tools.ui.filemanager

import com.whmdg.mczj.tools.util.ArchiveBrowser

/**
 * 面板当前浏览位置的类型安全表示。
 *
 * 每种浏览模式有对应的子类型，携带该模式所需的上下文。
 * [displayPath] 用于 UI 显示，[fileSystemPath] 用于需要真实文件系统路径的场景（shell 命令等）。
 */
sealed class PanelPath {

    /** UI 工具栏/路径栏显示用 */
    abstract val displayPath: String

    /** 真实文件系统绝对路径（压缩包模式下返回进入前的目录路径） */
    abstract val fileSystemPath: String

    /** 计算父路径；返回 null 表示已在根目录 */
    abstract fun goUp(): PanelPath?

    /** 是否在根目录（用于工具栏"返回上一级"按钮的 enabled 判断） */
    abstract val isAtRoot: Boolean

    /** 滚动位置缓存 key（不含面板前缀） */
    abstract val scrollKey: String

    // ── 普通文件系统目录 ──

    data class FileSystem(
        val path: String,
        val effectiveRoot: String = "/storage/emulated/0"
    ) : PanelPath() {
        override val displayPath: String get() = path
        override val fileSystemPath: String get() = path
        override val scrollKey: String get() = "fs:$path"
        override val isAtRoot: Boolean get() = path == effectiveRoot

        override fun goUp(): PanelPath? {
            if (path == effectiveRoot || !path.contains('/')) return null
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            return if (parent != path) copy(path = parent) else null
        }
    }

    // ── 压缩包内虚拟浏览 ──

    data class Archive(
        val virtualPath: String,
        val archivePath: String,
        val originalPath: String,
        val isAtArchiveRoot: Boolean
    ) : PanelPath() {
        override val displayPath: String get() = virtualPath
        override val fileSystemPath: String get() = originalPath
        override val scrollKey: String get() = "archive:$virtualPath"
        override val isAtRoot: Boolean get() = isAtArchiveRoot

        override fun goUp(): PanelPath? {
            if (isAtArchiveRoot) return null
            val parent = virtualPath.substringBeforeLast('/')
            return copy(virtualPath = parent, isAtArchiveRoot = parent == archivePath)
        }
    }

    // ── 保险箱目录 ──

    data class Vault(
        val path: String,
        val vaultDir: String
    ) : PanelPath() {
        override val displayPath: String get() = path
        override val fileSystemPath: String get() = path
        override val scrollKey: String get() = "vault:$path"
        override val isAtRoot: Boolean get() = path == vaultDir

        override fun goUp(): PanelPath? {
            if (path == vaultDir || !path.contains('/')) return null
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            if (parent == path) return null

            // 如果父目录在保险箱外，切换回 FileSystem 类型
            if (parent != vaultDir && !parent.startsWith("$vaultDir/")) {
                return FileSystem(parent)
            }

            // 父目录仍在保险箱内，保持 Vault 类型
            return copy(path = parent)
        }

        fun isInsideVault(realPath: String): Boolean =
            realPath == vaultDir || realPath.startsWith("$vaultDir/")
    }
}
