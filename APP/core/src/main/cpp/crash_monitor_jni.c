#include <jni.h>
#include <unistd.h>
#include <errno.h>
#include <android/log.h>

#include "crash_handler.h"

#define LOG_TAG "CrashMonitorNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

/* 全局 pipe fd */
static int g_pipe_crash[2] = {-1, -1};  /* [0]=读(Java), [1]=写(信号处理器) */
static int g_pipe_exit[2]  = {-1, -1};  /* [0]=读(信号处理器阻塞), [1]=写(Java发退出) */

/*
 * 初始化崩溃监控。
 * 创建两个 pipe，注册信号处理器。
 * 返回 int[2]：{crash_read_fd, exit_write_fd}
 *   - crash_read_fd: Java 读取崩溃信息的 fd
 *   - exit_write_fd: Java 写入退出信号的 fd
 */
JNIEXPORT jintArray JNICALL
Java_com_whmdg_mczj_tools_security_CrashMonitor_nativeInit(JNIEnv *env, jclass clazz) {
    /* 创建 pipe1: crash 信息传递 (Native → Java) */
    if (pipe(g_pipe_crash) != 0) {
        LOGE("Failed to create crash pipe: %d", errno);
        return NULL;
    }

    /* 创建 pipe2: 退出信号传递 (Java → Native) */
    if (pipe(g_pipe_exit) != 0) {
        LOGE("Failed to create exit pipe: %d", errno);
        close(g_pipe_crash[0]);
        close(g_pipe_crash[1]);
        return NULL;
    }

    /*
     * 注册信号处理器：
     * - pipe_crash_write = g_pipe_crash[1]（信号处理器写崩溃信息）
     * - pipe_exit_read   = g_pipe_exit[0]（信号处理器阻塞等退出通知）
     */
    if (crash_handler_init(g_pipe_crash[1], g_pipe_exit[0]) != 0) {
        LOGE("Failed to init crash handler");
        close(g_pipe_crash[0]);
        close(g_pipe_crash[1]);
        close(g_pipe_exit[0]);
        close(g_pipe_exit[1]);
        return NULL;
    }

    LOGI("Crash monitor initialized: crash_fd=%d/%d, exit_fd=%d/%d",
         g_pipe_crash[0], g_pipe_crash[1], g_pipe_exit[0], g_pipe_exit[1]);

    /* 返回给 Java 层：{crash_read_fd, exit_write_fd} */
    jint fds[2] = {g_pipe_crash[0], g_pipe_exit[1]};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result) {
        (*env)->SetIntArrayRegion(env, result, 0, 2, fds);
    }
    return result;
}

/*
 * 关闭崩溃监控，恢复信号处理器，关闭所有 pipe。
 */
JNIEXPORT void JNICALL
Java_com_whmdg_mczj_tools_security_CrashMonitor_nativeShutdown(JNIEnv *env, jclass clazz) {
    crash_handler_shutdown();

    if (g_pipe_crash[0] >= 0) { close(g_pipe_crash[0]); g_pipe_crash[0] = -1; }
    if (g_pipe_crash[1] >= 0) { close(g_pipe_crash[1]); g_pipe_crash[1] = -1; }
    if (g_pipe_exit[0] >= 0)  { close(g_pipe_exit[0]);  g_pipe_exit[0]  = -1; }
    if (g_pipe_exit[1] >= 0)  { close(g_pipe_exit[1]);  g_pipe_exit[1]  = -1; }

    LOGI("Crash monitor shut down");
}
