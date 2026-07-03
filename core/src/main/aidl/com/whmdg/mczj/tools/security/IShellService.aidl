package com.whmdg.mczj.tools.security;

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
     * 销毁服务（transaction code = 16777114，Shizuku 保留）
     */
    void destroy() = 16777114;
}
