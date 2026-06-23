package com.whmdg.mczj.tools.util

/**
 * 7zzs 命令行构建器。
 * 生成可直接在 sh/su 中执行的完整命令字符串。
 */
object SevenZipCommand {

    /** 路径单引号转义：' → '\'' */
    fun escape(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /**
     * 密码转义：单引号包裹，内部单引号用 '\'' 转义。
     * 所有字符保留，不省略、不过滤。
     */
    fun escapePassword(pwd: String): String = "'" + pwd.replace("'", "'\\''") + "'"

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
