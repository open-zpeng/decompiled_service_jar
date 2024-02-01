package com.android.server;

import android.app.ITraceService;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.util.concurrent.TimeUnit;

/* loaded from: classes.dex */
public class TraceService extends ITraceService.Stub {
    private static final String TAG = "TraceService";
    private final TraceHandler mHandler;

    public TraceService() {
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        this.mHandler = new TraceHandler(handlerThread.getLooper());
    }

    public void systemReady() {
        this.mHandler.systemReady();
    }

    public void catchCaton(String fileName, String packageName) {
        synchronized (this.mHandler.getSync()) {
            if (this.mHandler.isComplete()) {
                this.mHandler.setComplete(false);
                this.mHandler.dumpTrace(fileName);
            }
        }
    }

    /* loaded from: classes.dex */
    private static final class TraceHandler extends Handler {
        private static final long DUMP_TRACE_TIMEOUT = 12000;
        private static final int MESSAGE_DUMP_TRACE = 2;
        private static final int MESSAGE_SYSTEM_READY = 1;
        private boolean isComplete;
        private final Object mSync;

        public TraceHandler(Looper looper) {
            super(looper);
            this.mSync = new Object();
        }

        public final Object getSync() {
            return this.mSync;
        }

        public void systemReady() {
            synchronized (this.mSync) {
                this.isComplete = false;
            }
            sendEmptyMessage(1);
        }

        public boolean isComplete() {
            return this.isComplete;
        }

        public void setComplete(boolean complete) {
            this.isComplete = complete;
        }

        public void dumpTrace(String fileName) {
            if (TextUtils.isEmpty(fileName)) {
                fileName = "systrace_" + Binder.getCallingPid() + "_" + System.currentTimeMillis();
            }
            Message message = obtainMessage(2);
            message.obj = fileName;
            sendMessage(message);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int i = msg.what;
            if (i == 1) {
                File dir = new File("/data/caton");
                try {
                    if (!dir.exists() || !dir.isDirectory()) {
                        dir.delete();
                        dir.mkdir();
                    }
                } catch (Exception e) {
                }
                runCmd("atrace -c -b 3072 --async_start gfx input view wm am sched idle freq -a*");
            } else if (i == 2) {
                String fileName = (String) msg.obj;
                runCmd("atrace -z -c -b 3072 --async_dump gfx input view wm am sched idle freq -o /data/caton/" + fileName);
            }
        }

        private void runCmd(String command) {
            try {
                Log.i(TraceService.TAG, "runCmd = " + command);
                Process process = Runtime.getRuntime().exec(command);
                boolean exit = process.waitFor(DUMP_TRACE_TIMEOUT, TimeUnit.MILLISECONDS);
                process.destroy();
                Log.i(TraceService.TAG, "dump over: " + exit);
                synchronized (this.mSync) {
                    this.isComplete = true;
                }
            } catch (Exception e) {
                throw new RuntimeException("Error running shell command '" + command);
            }
        }
    }
}
