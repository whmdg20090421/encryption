package com.whmdg.mczj.tools.security;

interface IShellService {
    /**
     * 执行 shell 命令。
     * 返回格式: "stdout---STDERR---\nstderr---EXIT---\nexitCode"
     */
    String execute(String command);

    /**
     * 销毁服务（transaction code = 16777114，Shizuku 保留）
     */
    void destroy() = 16777114;
}
