package com.whmdg.mczj.tools.security;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.whmdg.mczj.tools.CrashActivity;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Native 崩溃监控器。
 *
 * 架构：
 * - pipe1 (Native → Java): 崩溃信号处理器写入崩溃信息，Java 后台线程读取
 * - pipe2 (Java → Native): Java 用户点"退出"后写入退出信号，信号处理器阻塞读取后 _exit
 *
 * 信号处理器内部阻塞在 read(pipe2)，无需额外的 nativeWaitForExit 线程。
 */
public class CrashMonitor {
    private static final String TAG = "CrashMonitor";

    /* nativeInit() 返回的 fd 数组索引 */
    private static final int IDX_CRASH_READ = 0;
    private static final int IDX_EXIT_WRITE = 1;

    /* Java 层持有的 ParcelFileDescriptor（管理 fd 生命周期） */
    private static ParcelFileDescriptor sCrashReadPfd;
    private static ParcelFileDescriptor sExitWritePfd;

    private static volatile boolean sInitialized = false;
    private static Context sAppContext;

    static {
        System.loadLibrary("authcore");
    }

    /**
     * 初始化崩溃监控。
     * 在 MainActivity.onCreate() 中调用。
     *
     * @param context Application 或 Activity context
     */
    public static synchronized void init(Context context) {
        if (sInitialized) {
            Log.w(TAG, "Already initialized");
            return;
        }

        sAppContext = context.getApplicationContext();

        try {
            int[] fds = nativeInit();
            if (fds == null || fds.length < 2) {
                Log.e(TAG, "nativeInit returned invalid fds");
                return;
            }

            /* 用 ParcelFileDescriptor 包装裸 fd，确保 Java 层可正确读写 */
            sCrashReadPfd = ParcelFileDescriptor.fromFd(fds[IDX_CRASH_READ]);
            sExitWritePfd = ParcelFileDescriptor.fromFd(fds[IDX_EXIT_WRITE]);

            sInitialized = true;
            Log.i(TAG, "Crash monitor initialized, crash_fd=" + fds[IDX_CRASH_READ]
                    + ", exit_fd=" + fds[IDX_EXIT_WRITE]);

            /* 启动后台线程读取崩溃信息 */
            startCrashReaderThread();

        } catch (Exception e) {
            Log.e(TAG, "Failed to init crash monitor", e);
        }
    }

    /**
     * 后台线程阻塞读取 pipe1，收到崩溃信息后切到主线程启动 CrashActivity。
     */
    private static void startCrashReaderThread() {
        Thread readerThread = new Thread(() -> {
            try {
                FileInputStream fis = new FileInputStream(sCrashReadPfd.getFileDescriptor());
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals("===END===")) break;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }

                if (sb.length() > 0) {
                    final String crashInfo = sb.toString();
                    Log.e(TAG, "Native crash received: " + crashInfo);

                    new Handler(Looper.getMainLooper()).post(() -> {
                        launchCrashActivity(crashInfo);
                    });
                }

                reader.close();
            } catch (Exception e) {
                Log.e(TAG, "Crash reader thread error", e);
            }
        }, "CrashReaderThread");

        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * 启动 CrashActivity 显示崩溃信息。
     */
    private static void launchCrashActivity(String crashInfo) {
        try {
            Intent intent = new Intent(sAppContext, CrashActivity.class);
            intent.putExtra(CrashActivity.EXTRA_CRASH_INFO, crashInfo);
            intent.putExtra(CrashActivity.EXTRA_EXIT_WRITE_FD,
                    sExitWritePfd.getFd());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NO_HISTORY);
            sAppContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch CrashActivity", e);
        }
    }

    /**
     * 关闭崩溃监控，恢复信号处理器。
     */
    public static synchronized void shutdown() {
        if (!sInitialized) return;
        try {
            nativeShutdown();
        } catch (Exception e) {
            Log.e(TAG, "Shutdown error", e);
        }
        sInitialized = false;

        /* 关闭 ParcelFileDescriptor */
        try {
            if (sCrashReadPfd != null) sCrashReadPfd.close();
        } catch (Exception ignored) {}
        try {
            if (sExitWritePfd != null) sExitWritePfd.close();
        } catch (Exception ignored) {}
        sCrashReadPfd = null;
        sExitWritePfd = null;

        Log.i(TAG, "Crash monitor shut down");
    }

    /* Native 方法声明 */
    private static native int[] nativeInit();
    private static native void nativeShutdown();
}
