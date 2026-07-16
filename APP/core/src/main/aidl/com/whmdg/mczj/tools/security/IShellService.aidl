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
     * 执行命令，stdout 通过 PFD 管道实时流式返回。
     * 返回格式同 execute()（stdoutB64\nstderrB64\nexitCode），其中 stdoutB64 为空。
     * @param command 要执行的 shell 命令
     * @param stdoutWriteFd 客户端创建的管道写端，服务端将 stdout 写入此 fd
     */
    String executeStreamingStdout(String command, in ParcelFileDescriptor stdoutWriteFd) = 4;

    /**
     * 以提升权限打开文件用于读取，返回 PFD 传回调用方进程。
     * ShellService 运行在 Shizuku 进程（uid 2000 或 0），可打开应用自身无权访问的文件。
     * 返回的 PFD 跨 Binder 传递后，调用方可用 FileInputStream(fd) 直接读取。
     */
    ParcelFileDescriptor openForRead(String path) = 5;

    /**
     * 以提升权限打开/创建文件用于写入，返回 PFD 传回调用方进程。
     * 若文件已存在则截断。ShellService 运行在 Shizuku 进程（uid 2000 或 0）。
     * 返回的 PFD 跨 Binder 传递后，调用方可用 FileOutputStream(fd) 直接写入。
     */
    ParcelFileDescriptor openForWrite(String path) = 6;

    /**
     * 销毁服务（transaction code = 16777114，Shizuku 保留）
     */
    void destroy() = 16777114;
}
