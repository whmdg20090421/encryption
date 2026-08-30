#include <signal.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>
#include <stdatomic.h>
#include <time.h>
#include <ucontext.h>

#include "crash_handler.h"

/* 全局：pipe fd */
static volatile int g_pipe_crash_write = -1;  /* 写端：写崩溃信息给 Java */
static volatile int g_pipe_exit_read  = -1;   /* 读端：阻塞等 Java 通知退出 */

/* 防止信号处理器重入 */
static volatile atomic_int g_in_handler = 0;

/* 备用信号栈（栈溢出时信号处理器仍能运行） */
static uint8_t g_alt_stack_mem[SIGSTKSZ * 2];

/* 旧 action，用于 shutdown 时恢复 */
static struct sigaction g_old_sa_segv;
static struct sigaction g_old_sa_abrt;
static struct sigaction g_old_sa_bus;
static struct sigaction g_old_sa_fpe;
static struct sigaction g_old_sa_ill;
static struct sigaction g_old_sa_trap;

/* ── async-safe 辅助函数 ──────────────────────────── */

/* async-safe 的 strlen（POSIX strlen 是 async-safe 的，但为清晰起见显式实现） */
static int safe_strlen(const char *s) {
    int n = 0;
    while (s && s[n]) n++;
    return n;
}

/*
 * async-safe 的整数写入。
 * 将整数 v 转换为十进制字符串写入 fd。
 * separator 附加在末尾（'|' 或 '\n'）。
 */
static void write_int(int fd, long v, char separator) {
    char buf[32];
    int i = 31;
    buf[i] = separator;
    i--;

    if (v == 0) {
        buf[i] = '0';
        i--;
    } else {
        /* 负数处理 */
        int negative = 0;
        if (v < 0) {
            negative = 1;
            v = -v;
        }
        while (v > 0 && i >= 0) {
            buf[i] = '0' + (char)(v % 10);
            v /= 10;
            i--;
        }
        if (negative && i >= 0) {
            buf[i] = '-';
            i--;
        }
    }

    /* 写入：从 i+1 到 32 字节 */
    write(fd, buf + i + 1, (size_t)(31 - i));
}

/* async-safe 的字符串写入 */
static void write_str(int fd, const char *s, char separator) {
    if (s) {
        write(fd, s, (size_t)safe_strlen(s));
    }
    write(fd, &separator, 1);
}

/* 信号名称表（静态，async-safe） */
static const char* get_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

/* async-safe 的时间戳获取（秒级） */
static long get_timestamp(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long)ts.tv_sec;
}

/* async-safe 的十六进制写入（用于地址输出） */
static void write_hex(int fd, unsigned long v, char separator) {
    char buf[20]; /* 0x + 16 hex digits + separator */
    int i = 19;
    buf[i] = separator;
    i--;
    if (v == 0) {
        buf[i] = '0'; i--;
        buf[i] = 'x'; i--;
    } else {
        const char *hex = "0123456789abcdef";
        while (v > 0 && i >= 2) {
            buf[i] = hex[v & 0xf];
            v >>= 4;
            i--;
        }
        buf[i] = 'x'; i--;
        buf[i] = '0'; i--;
    }
    write(fd, buf + i + 1, (size_t)(19 - i));
}

/*
 * 信号处理器函数。
 * 仅使用 async-safe 函数：write, read, sigaction, clock_gettime, _exit
 *
 * 输出格式（扩展版，分段写入避免 snprintf）：
 *   SIGNO|SIGNAME|TIMESTAMP|ERRNO|FAULT_ADDR|PC|LR\n
 *
 * - FAULT_ADDR: SIGSEGV/SIGBUS 时的访问地址，其他信号为 0
 * - PC: 崩溃时的程序计数器（ARM64: uc_mcontext.pc）
 * - LR: 链接寄存器（ARM64: uc_mcontext.regs[30]）
 *
 * 写完后阻塞在 read(pipe_exit) 等待 Java 通知退出。
 */
static void crash_signal_handler(int sig, siginfo_t *info, void *context) {
    /* 防重入：如果已经在处理中，直接终止 */
    int expected = 0;
    if (!atomic_compare_exchange_strong(&g_in_handler, &expected, 1)) {
        _exit(128 + sig);
    }

    /* 保存 errno（信号处理器中可能被修改） */
    int saved_errno = errno;

    int wfd = g_pipe_crash_write;
    if (wfd < 0) {
        _exit(128 + sig);
    }

    /* 提取 fault address */
    unsigned long fault_addr = 0;
    if (info != NULL) {
        fault_addr = (unsigned long)info->si_addr;
    }

    /* 提取 PC 和 LR（ARM64） */
    unsigned long pc = 0;
    unsigned long lr = 0;
    if (context != NULL) {
        ucontext_t *uc = (ucontext_t *)context;
#if defined(__aarch64__)
        pc = (unsigned long)uc->uc_mcontext.pc;
        lr = (unsigned long)uc->uc_mcontext.regs[30];
#elif defined(__arm__)
        pc = (unsigned long)uc->uc_mcontext.arm_pc;
        lr = (unsigned long)uc->uc_mcontext.arm_lr;
#elif defined(__x86_64__)
        pc = (unsigned long)uc->uc_mcontext.gregs[REG_RIP];
        lr = 0; /* x86_64 无 LR */
#elif defined(__i386__)
        pc = (unsigned long)uc->uc_mcontext.gregs[REG_EIP];
        lr = 0;
#endif
    }

    /* 分段写入崩溃信息：SIGNO|SIGNAME|TIMESTAMP|ERRNO|FAULT_ADDR|PC|LR\n */
    write_int(wfd, (long)sig, '|');
    write_str(wfd, get_signal_name(sig), '|');
    write_int(wfd, get_timestamp(), '|');
    write_int(wfd, (long)saved_errno, '|');
    write_hex(wfd, fault_addr, '|');
    write_hex(wfd, pc, '|');
    write_hex(wfd, lr, '\n');

    /*
     * 阻塞等待 Java 层发送退出信号。
     * read() 是 async-safe 的。
     * Java 用户点"退出应用"后向 pipe_exit_write 写入任意字节，这里收到后 _exit。
     */
    char dummy;
    int rfd = g_pipe_exit_read;
    if (rfd >= 0) {
        read(rfd, &dummy, 1);
    }

    _exit(128 + sig);
}

/* ── 公开 API ─────────────────────────────────────── */

int crash_handler_init(int pipe_crash_write, int pipe_exit_read) {
    g_pipe_crash_write = pipe_crash_write;
    g_pipe_exit_read = pipe_exit_read;

    /* 设置备用信号栈（栈溢出时信号处理器仍能运行） */
    stack_t ss;
    ss.ss_sp = g_alt_stack_mem;
    ss.ss_size = sizeof(g_alt_stack_mem);
    ss.ss_flags = 0;
    if (sigaltstack(&ss, NULL) != 0) {
        return -1;
    }

    /* 信号处理 action：SA_ONSTACK + SA_RESETHAND + SA_SIGINFO（获取 siginfo_t 和 ucontext_t） */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_ONSTACK | SA_RESETHAND | SA_SIGINFO;
    sa.sa_sigaction = crash_signal_handler;

    /* 注册所有关心的信号 */
    if (sigaction(SIGSEGV, &sa, &g_old_sa_segv) != 0) return -1;
    if (sigaction(SIGABRT, &sa, &g_old_sa_abrt) != 0) return -1;
    if (sigaction(SIGBUS,  &sa, &g_old_sa_bus)  != 0) return -1;
    if (sigaction(SIGFPE,  &sa, &g_old_sa_fpe)  != 0) return -1;
    if (sigaction(SIGILL,  &sa, &g_old_sa_ill)  != 0) return -1;
    if (sigaction(SIGTRAP, &sa, &g_old_sa_trap) != 0) return -1;

    return 0;
}

void crash_handler_shutdown(void) {
    /* 恢复默认信号处理器 */
    sigaction(SIGSEGV, &g_old_sa_segv, NULL);
    sigaction(SIGABRT, &g_old_sa_abrt, NULL);
    sigaction(SIGBUS,  &g_old_sa_bus,  NULL);
    sigaction(SIGFPE,  &g_old_sa_fpe,  NULL);
    sigaction(SIGILL,  &g_old_sa_ill,  NULL);
    sigaction(SIGTRAP, &g_old_sa_trap, NULL);

    /* 关闭 pipe fd */
    if (g_pipe_crash_write >= 0) {
        close(g_pipe_crash_write);
        g_pipe_crash_write = -1;
    }
    if (g_pipe_exit_read >= 0) {
        close(g_pipe_exit_read);
        g_pipe_exit_read = -1;
    }

    /* 重置重入标志 */
    atomic_store(&g_in_handler, 0);
}
