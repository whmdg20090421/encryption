package com.whmdg.mczj.tools.security;

import android.os.ParcelFileDescriptor;

interface IShellService {
    /**
     * 执行 shell 命令。
     * 返回格式: "stdout---STDERR---\nstderr---EXIT---\nexitCode"
     */
    String execute(String command) = 1;

    /**
     * 执行 shell 命令并流式输出进度到文件（同步阻塞）。
     * 进度文件格式: 每行一个百分比数字（如 "75\n"），最后一行 "DONE:<exitCode>\n"
     * @param command 要执行的 shell 命令
     * @param progressPath 进度文件的绝对路径（app 和 ShellService 都能访问）
     */
    void executeStreaming(String command, String progressPath) = 2;

    /**
     * 执行命令，stderr 通过 PFD 管道实时流式返回。
     * 返回格式同 execute()（stdoutB64\nstderrB64\nexitCode），其中 stderrB64 为空。
     * @param command 要执行的 shell 命令
     * @param stderrWriteFd 客户端创建的管道写端，服务端将 stderr 写入此 fd
     */
    String executeStreamingStderr(String command, in ParcelFileDescriptor stderrWriteFd) = 3;

    /**
     * 销毁服务（transaction code = 16777114，Shizuku 保留）
     */
    void destroy() = 16777114;
}
