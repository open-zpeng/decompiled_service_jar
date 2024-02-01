package com.android.server.wm;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayTransformManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WindowTracing {
    private static final long MAGIC_NUMBER_VALUE = 4990904633914181975L;
    private static final String TAG = "WindowTracing";
    private boolean mEnabled;
    private volatile boolean mEnabledLockFree;
    private final File mTraceFile;
    private final Object mLock = new Object();
    private final BlockingQueue<ProtoOutputStream> mWriteQueue = new ArrayBlockingQueue(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);

    WindowTracing(File file) {
        this.mTraceFile = file;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startTrace(PrintWriter pw) throws IOException {
        if (Build.IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (this.mLock) {
            logAndPrintln(pw, "Start tracing to " + this.mTraceFile + ".");
            this.mWriteQueue.clear();
            this.mTraceFile.delete();
            OutputStream os = new FileOutputStream(this.mTraceFile);
            this.mTraceFile.setReadable(true, false);
            ProtoOutputStream proto = new ProtoOutputStream(os);
            proto.write(1125281431553L, MAGIC_NUMBER_VALUE);
            proto.flush();
            $closeResource(null, os);
            this.mEnabledLockFree = true;
            this.mEnabled = true;
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    private void logAndPrintln(PrintWriter pw, String msg) {
        Log.i(TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopTrace(PrintWriter pw) {
        if (Build.IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (this.mLock) {
            logAndPrintln(pw, "Stop tracing to " + this.mTraceFile + ". Waiting for traces to flush.");
            this.mEnabledLockFree = false;
            this.mEnabled = false;
            while (!this.mWriteQueue.isEmpty()) {
                if (this.mEnabled) {
                    logAndPrintln(pw, "ERROR: tracing was re-enabled while waiting for flush.");
                    throw new IllegalStateException("tracing enabled while waiting for flush.");
                }
                try {
                    this.mLock.wait();
                    this.mLock.notify();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            logAndPrintln(pw, "Trace written to " + this.mTraceFile + ".");
        }
    }

    void appendTraceEntry(ProtoOutputStream proto) {
        if (this.mEnabledLockFree && !this.mWriteQueue.offer(proto)) {
            Log.e(TAG, "Dropping window trace entry, queue full");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void loop() {
        while (true) {
            loopOnce();
        }
    }

    @VisibleForTesting
    void loopOnce() {
        try {
            ProtoOutputStream proto = this.mWriteQueue.take();
            synchronized (this.mLock) {
                try {
                    Trace.traceBegin(32L, "writeToFile");
                    OutputStream os = new FileOutputStream(this.mTraceFile, true);
                    try {
                        os.write(proto.getBytes());
                        $closeResource(null, os);
                        Trace.traceEnd(32L);
                    } finally {
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write file " + this.mTraceFile, e);
                    Trace.traceEnd(32L);
                }
                this.mLock.notify();
            }
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isEnabled() {
        return this.mEnabledLockFree;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static WindowTracing createDefaultAndStartLooper(Context context) {
        File file = new File("/data/misc/wmtrace/wm_trace.pb");
        final WindowTracing windowTracing = new WindowTracing(file);
        if (!Build.IS_USER) {
            Objects.requireNonNull(windowTracing);
            new Thread(new Runnable() { // from class: com.android.server.wm.-$$Lambda$8kACnZAYfDhQTXwuOd2shUPmkTE
                @Override // java.lang.Runnable
                public final void run() {
                    WindowTracing.this.loop();
                }
            }, "window_tracing").start();
        }
        return windowTracing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:16:0x002f A[Catch: IOException -> 0x004d, TryCatch #0 {IOException -> 0x004d, blocks: (B:3:0x0004, B:15:0x002c, B:16:0x002f, B:21:0x003a, B:17:0x0032, B:19:0x0036, B:8:0x0015, B:11:0x0020), top: B:26:0x0004 }] */
    /* JADX WARN: Removed duplicated region for block: B:17:0x0032 A[Catch: IOException -> 0x004d, TryCatch #0 {IOException -> 0x004d, blocks: (B:3:0x0004, B:15:0x002c, B:16:0x002f, B:21:0x003a, B:17:0x0032, B:19:0x0036, B:8:0x0015, B:11:0x0020), top: B:26:0x0004 }] */
    /* JADX WARN: Removed duplicated region for block: B:19:0x0036 A[Catch: IOException -> 0x004d, TryCatch #0 {IOException -> 0x004d, blocks: (B:3:0x0004, B:15:0x002c, B:16:0x002f, B:21:0x003a, B:17:0x0032, B:19:0x0036, B:8:0x0015, B:11:0x0020), top: B:26:0x0004 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int onShellCommand(android.os.ShellCommand r6, java.lang.String r7) {
        /*
            r5 = this;
            java.io.PrintWriter r0 = r6.getOutPrintWriter()
            int r1 = r7.hashCode()     // Catch: java.io.IOException -> L4d
            r2 = 3540994(0x360802, float:4.96199E-39)
            r3 = -1
            r4 = 0
            if (r1 == r2) goto L20
            r2 = 109757538(0x68ac462, float:5.219839E-35)
            if (r1 == r2) goto L15
            goto L2b
        L15:
            java.lang.String r1 = "start"
            boolean r1 = r7.equals(r1)     // Catch: java.io.IOException -> L4d
            if (r1 == 0) goto L2b
            r1 = r4
            goto L2c
        L20:
            java.lang.String r1 = "stop"
            boolean r1 = r7.equals(r1)     // Catch: java.io.IOException -> L4d
            if (r1 == 0) goto L2b
            r1 = 1
            goto L2c
        L2b:
            r1 = r3
        L2c:
            switch(r1) {
                case 0: goto L36;
                case 1: goto L32;
                default: goto L2f;
            }     // Catch: java.io.IOException -> L4d
        L2f:
            java.lang.StringBuilder r1 = new java.lang.StringBuilder     // Catch: java.io.IOException -> L4d
            goto L3a
        L32:
            r5.stopTrace(r0)     // Catch: java.io.IOException -> L4d
            return r4
        L36:
            r5.startTrace(r0)     // Catch: java.io.IOException -> L4d
            return r4
        L3a:
            r1.<init>()     // Catch: java.io.IOException -> L4d
            java.lang.String r2 = "Unknown command: "
            r1.append(r2)     // Catch: java.io.IOException -> L4d
            r1.append(r7)     // Catch: java.io.IOException -> L4d
            java.lang.String r1 = r1.toString()     // Catch: java.io.IOException -> L4d
            r0.println(r1)     // Catch: java.io.IOException -> L4d
            return r3
        L4d:
            r1 = move-exception
            java.lang.String r2 = r1.toString()
            r5.logAndPrintln(r0, r2)
            java.lang.RuntimeException r2 = new java.lang.RuntimeException
            r2.<init>(r1)
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowTracing.onShellCommand(android.os.ShellCommand, java.lang.String):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void traceStateLocked(String where, WindowManagerService service) {
        if (!isEnabled()) {
            return;
        }
        ProtoOutputStream os = new ProtoOutputStream();
        long tokenOuter = os.start(2246267895810L);
        os.write(1125281431553L, SystemClock.elapsedRealtimeNanos());
        os.write(1138166333442L, where);
        Trace.traceBegin(32L, "writeToProtoLocked");
        try {
            long tokenInner = os.start(1146756268035L);
            service.writeToProtoLocked(os, true);
            os.end(tokenInner);
            Trace.traceEnd(32L);
            os.end(tokenOuter);
            appendTraceEntry(os);
        } finally {
            Trace.traceEnd(32L);
        }
    }
}
