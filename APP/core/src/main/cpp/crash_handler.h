#ifndef CRASH_HANDLER_H
#define CRASH_HANDLER_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 初始化崩溃信号处理器。
 * - 设置 sigaltstack（备用信号栈，防止栈溢出时无法处理信号）
 * - 注册 SIGSEGV/SIGABRT/SIGBUS/SIGFPE/SIGILL/SIGTRAP 信号处理器
 *
 * @param pipe_crash_write pipe1 写端 fd，崩溃信息通过此 fd 传递给 Java 层
 * @param pipe_exit_read   pipe2 读端 fd，信号处理器阻塞在此等待 Java 通知退出
 * @return 0 成功，-1 失败
 */
int crash_handler_init(int pipe_crash_write, int pipe_exit_read);

/**
 * 关闭崩溃处理器，恢复默认信号处理器，关闭 pipe fd。
 */
void crash_handler_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif /* CRASH_HANDLER_H */
