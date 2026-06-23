package com.whmdg.mczj.tools.util

/**
 * 7zzs 命令行构建器。
 * 生成可直接在 sh/su 中执行的完整命令字符串。
 */
object SevenZipCommand {

    /**
     * 路径转义：反斜杠转义所有 shell 特殊字符。
     *
     * 注意：反斜杠转义仅在命令通过 heredoc（ArchiveBrowser.wrapInHeredoc）或
     * 直接传给 libsu（Shell.cmd）时有效。如果命令通过 sh -c "..." 双引号传递，
     * 反斜杠会被 shell 消费（\[ → [），导致 glob 展开。ArchiveBrowser 已用
     * heredoc 绕过此问题；其他调用方（FileManagerViewModel 等）走 libsu，由
     * libsu 内部处理引号，不受影响。
     */
    fun escape(path: String): String {
        if (path.isEmpty()) return "''"
        val sb = StringBuilder(path.length + 16)
        for (c in path) {
            if (c in SHELL_SPECIAL) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 密码转义：与路径转义逻辑相同，反斜杠转义所有 shell 特殊字符。
     */
    fun escapePassword(pwd: String): String {
        if (pwd.isEmpty()) return "''"
        val sb = StringBuilder(pwd.length + 16)
        for (c in pwd) {
            if (c in SHELL_SPECIAL) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** 需要反斜杠转义的 shell 特殊字符 */
    private val SHELL_SPECIAL = setOf(
        ' ', '\t', '\'', '"', '\\',
        '(', ')', '[', ']', '{', '}',
        '*', '?', '!', '#', '$', '&', ';',
        '|', '<', '>', '`', '~'
    )

    /**
     * 构建 7zzs 压缩命令。
     * 输出格式: `<binary> a -t<type> -mx=<level> [-p'pwd' [-mem=AES256]] -bsp1 2>&1 <output> <sources...>`
     */
    fun build(binaryPath: String, options: CompressService.CompressOptions): String {
        val cmd = StringBuilder(escape(binaryPath))
        cmd.append(" a")

        // 格式类型
        when (options.format) {
            "zip" -> cmd.append(" -tzip")
            "7z" -> cmd.append(" -t7z")
            "tar" -> cmd.append(" -ttar")
            "tar.gz" -> cmd.append(" -ttar -m0=gzip")
            "tar.bz2" -> cmd.append(" -ttar -m0=bzip2")
            "tar.xz" -> cmd.append(" -ttar -m0=xz")
        }

        // 压缩级别（tar 不支持）
        if (options.format != "tar") {
            cmd.append(" -mx=${options.compressionLevel}")
        }

        // 加密参数（tar 系列不支持加密）
        if (options.password.isNotEmpty() && options.format !in listOf("tar", "tar.gz", "tar.bz2", "tar.xz")) {
            cmd.append(" -p${escapePassword(options.password)}")
            // ZIP AES-256 开关
            if (options.format == "zip" && options.useAes) {
                cmd.append(" -mem=AES256")
            }
        }

        // 进度输出 + stderr 合并到 stdout
        cmd.append(" -bsp1 2>&1")

        // 输出路径
        cmd.append(" ${escape(options.outputPath)}")

        // 源文件路径
        for (src in options.sourcePaths) {
            cmd.append(" ${escape(src)}")
        }

        return cmd.toString()
    }

    /**
     * 构建 7zzs 列表命令（用于压缩包浏览）。
     * 输出格式: `<binary> l -ba [-p'pwd'] <archive>`
     * @param password 空=不带密码
     */
    fun buildListCommand(binaryPath: String, archivePath: String, password: String = ""): String {
        val cmd = StringBuilder(escape(binaryPath))
        cmd.append(" l -ba")
        if (password.isNotEmpty()) {
            cmd.append(" -p${escapePassword(password)}")
        }
        cmd.append(" ${escape(archivePath)}")
        return cmd.toString()
    }

    /**
     * 构建 7zzs 技术详情列表命令（用于检测加密状态）。
     * 输出格式: `<binary> l -slt [-p'pwd'] <archive>`
     * 输出包含 `Encrypted = +` 或 `Encrypted = -` 字段。
     * @param password 空=不带密码
     */
    fun buildListDetailCommand(binaryPath: String, archivePath: String, password: String = ""): String {
        val cmd = StringBuilder(escape(binaryPath))
        cmd.append(" l -slt")
        if (password.isNotEmpty()) {
            cmd.append(" -p${escapePassword(password)}")
        }
        cmd.append(" ${escape(archivePath)}")
        return cmd.toString()
    }

    /**
     * 构建 7zzs 解压命令。
     * 输出格式: `<binary> x [-p'pwd'] -bsp1 2>&1 <archive> -o<outputDir> -aoa`
     * @param password 空=不带密码
     */
    fun buildExtractCommand(
        binaryPath: String,
        archivePath: String,
        outputDir: String,
        password: String = ""
    ): String {
        val cmd = StringBuilder(escape(binaryPath))
        cmd.append(" x")
        if (password.isNotEmpty()) {
            cmd.append(" -p${escapePassword(password)}")
        }
        cmd.append(" -bsp1 2>&1")
        cmd.append(" ${escape(archivePath)}")
        cmd.append(" -o${escape(outputDir)}")
        cmd.append(" -aoa")
        return cmd.toString()
    }
}
