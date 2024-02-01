package com.android.server;

import android.os.Build;
import android.os.Process;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.Preconditions;
import com.android.server.am.ActivityManagerService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/* loaded from: classes.dex */
public class SystemServerInitThreadPool {
    private static final int SHUTDOWN_TIMEOUT_MILLIS = 20000;
    private static SystemServerInitThreadPool sInstance;
    private static final String TAG = SystemServerInitThreadPool.class.getSimpleName();
    private static final boolean IS_DEBUGGABLE = Build.IS_DEBUGGABLE;
    private ExecutorService mService = ConcurrentUtils.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), "system-server-init-thread", -2);
    private List<String> mPendingTasks = new ArrayList();

    public static synchronized SystemServerInitThreadPool get() {
        SystemServerInitThreadPool systemServerInitThreadPool;
        synchronized (SystemServerInitThreadPool.class) {
            if (sInstance == null) {
                sInstance = new SystemServerInitThreadPool();
            }
            boolean z = sInstance.mService != null;
            Preconditions.checkState(z, "Cannot get " + TAG + " - it has been shut down");
            systemServerInitThreadPool = sInstance;
        }
        return systemServerInitThreadPool;
    }

    public Future<?> submit(final Runnable runnable, final String description) {
        synchronized (this.mPendingTasks) {
            this.mPendingTasks.add(description);
        }
        return this.mService.submit(new Runnable() { // from class: com.android.server.-$$Lambda$SystemServerInitThreadPool$jLyL3DFmbjsFesU5SGktD3NoWSc
            @Override // java.lang.Runnable
            public final void run() {
                SystemServerInitThreadPool.this.lambda$submit$0$SystemServerInitThreadPool(description, runnable);
            }
        });
    }

    public /* synthetic */ void lambda$submit$0$SystemServerInitThreadPool(String description, Runnable runnable) {
        if (IS_DEBUGGABLE) {
            String str = TAG;
            Slog.d(str, "Started executing " + description);
        }
        try {
            runnable.run();
            synchronized (this.mPendingTasks) {
                this.mPendingTasks.remove(description);
            }
            if (IS_DEBUGGABLE) {
                String str2 = TAG;
                Slog.d(str2, "Finished executing " + description);
            }
        } catch (RuntimeException e) {
            String str3 = TAG;
            Slog.e(str3, "Failure in " + description + ": " + e, e);
            throw e;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static synchronized void shutdown() {
        synchronized (SystemServerInitThreadPool.class) {
            if (sInstance != null && sInstance.mService != null) {
                sInstance.mService.shutdown();
                try {
                    boolean terminated = sInstance.mService.awaitTermination(20000L, TimeUnit.MILLISECONDS);
                    if (!terminated) {
                        dumpStackTraces();
                    }
                    List<Runnable> unstartedRunnables = sInstance.mService.shutdownNow();
                    if (!terminated) {
                        List<String> copy = new ArrayList<>();
                        synchronized (sInstance.mPendingTasks) {
                            copy.addAll(sInstance.mPendingTasks);
                        }
                        throw new IllegalStateException("Cannot shutdown. Unstarted tasks " + unstartedRunnables + " Unfinished tasks " + copy);
                    }
                    sInstance.mService = null;
                    sInstance.mPendingTasks = null;
                    Slog.d(TAG, "Shutdown successful");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dumpStackTraces();
                    throw new IllegalStateException(TAG + " init interrupted");
                }
            }
        }
    }

    private static void dumpStackTraces() {
        ArrayList<Integer> pids = new ArrayList<>();
        pids.add(Integer.valueOf(Process.myPid()));
        ActivityManagerService.dumpStackTraces(pids, (ProcessCpuTracker) null, (SparseArray<Boolean>) null, Watchdog.getInterestingNativePids());
    }
}
