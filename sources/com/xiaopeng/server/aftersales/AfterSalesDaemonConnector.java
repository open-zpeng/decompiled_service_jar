package com.xiaopeng.server.aftersales;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import com.android.server.FgThread;
import com.android.server.Watchdog;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.power.ShutdownThread;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class AfterSalesDaemonConnector implements Runnable, Handler.Callback, Watchdog.Monitor {
    private static final int FACTORY_BUILD_SPECIAL = 6;
    private static final int MSG_WHAT_SOCKET_READ = 1;
    private static final String TAG = "AfterSalesDaemonConnector";
    private static final String XP_AFTERSALES_LOCAL_SOCKET = "xpaftersalesd";
    private final int BUFFER_SIZE;
    private Handler mCallbackHandler;
    private IAfterSalesDaemonCallbacks mCallbacks;
    private final Object mDaemonLock;
    private final Looper mLooper;
    private OutputStream mOutputStream;
    private static final String BUILD_SPECIAL_PROPERTIES = "ro.xiaopeng.special";
    private static final int BUILD_SPECIAL = SystemProperties.getInt(BUILD_SPECIAL_PROPERTIES, 0);

    /* JADX INFO: Access modifiers changed from: package-private */
    public AfterSalesDaemonConnector(IAfterSalesDaemonCallbacks callbacks) {
        this(callbacks, FgThread.get().getLooper());
    }

    AfterSalesDaemonConnector(IAfterSalesDaemonCallbacks callbacks, Looper looper) {
        this.mDaemonLock = new Object();
        this.BUFFER_SIZE = 4096;
        this.mCallbacks = callbacks;
        this.mLooper = looper;
    }

    @Override // java.lang.Runnable
    public void run() {
        this.mCallbackHandler = new Handler(this.mLooper, this);
        while (!isShuttingDown()) {
            if (BUILD_SPECIAL == 6) {
                Slog.i(TAG, "This is factory bin");
                return;
            }
            try {
                listenToSocket();
            } catch (Exception e) {
                Slog.e(TAG, "Error in NativeDaemonConnector: " + e);
                if (!isShuttingDown()) {
                    SystemClock.sleep(5000L);
                } else {
                    return;
                }
            }
        }
    }

    private static boolean isShuttingDown() {
        String shutdownAct = SystemProperties.get(ShutdownThread.SHUTDOWN_ACTION_PROPERTY, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        return shutdownAct != null && shutdownAct.length() > 0;
    }

    @Override // android.os.Handler.Callback
    public boolean handleMessage(Message msg) {
        AfterSalesDaemonEvent event = (AfterSalesDaemonEvent) msg.obj;
        try {
            this.mCallbacks.onEvent(event);
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "Error handling '" + event + "': " + e);
            return true;
        }
    }

    private void listenToSocket() throws IOException {
        LocalSocket socket = null;
        try {
            try {
                LocalSocket socket2 = new LocalSocket();
                LocalSocketAddress address = new LocalSocketAddress(XP_AFTERSALES_LOCAL_SOCKET, LocalSocketAddress.Namespace.RESERVED);
                socket2.connect(address);
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket2.getInputStream()));
                synchronized (this.mDaemonLock) {
                    this.mOutputStream = socket2.getOutputStream();
                }
                this.mCallbacks.onDaemonConnected();
                while (true) {
                    String rawEvent = bufferedReader.readLine();
                    Slog.d(TAG, "readLine rawEvent : " + rawEvent);
                    AfterSalesDaemonEvent event = AfterSalesDaemonEvent.parseRawEvent(rawEvent);
                    if (event != null && (AfterSalesDaemonEvent.XP_AFTERSALES_RESPONSE.equalsIgnoreCase(event.getFlag()) || AfterSalesDaemonEvent.XP_AFTERSALES_REQ.equalsIgnoreCase(event.getFlag()))) {
                        Message msg = this.mCallbackHandler.obtainMessage(1, event);
                        if (!this.mCallbackHandler.sendMessage(msg)) {
                            Slog.e(TAG, "mCallbackHandler.sendMessage fail");
                        }
                    }
                }
            } catch (IOException ex) {
                Slog.e(TAG, "Communications error: " + ex);
                throw ex;
            }
        } catch (Throwable th) {
            synchronized (this.mDaemonLock) {
                if (this.mOutputStream != null) {
                    try {
                        Slog.e(TAG, "closing stream ");
                        this.mOutputStream.close();
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed closing output stream: " + e);
                    }
                    this.mOutputStream = null;
                }
                if (0 != 0) {
                    try {
                        socket.close();
                    } catch (IOException ex2) {
                        Slog.e(TAG, "Failed closing socket: " + ex2);
                    }
                }
                throw th;
            }
        }
    }

    public void waitForCallbacks() {
        if (Thread.currentThread() == this.mLooper.getThread()) {
            throw new IllegalStateException("Must not call this method on callback thread");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        this.mCallbackHandler.post(new Runnable() { // from class: com.xiaopeng.server.aftersales.AfterSalesDaemonConnector.1
            @Override // java.lang.Runnable
            public void run() {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Slog.wtf(TAG, "Interrupted while waiting for unsolicited response handling", e);
        }
    }

    public boolean execute(int cmd, int action, Object... args) {
        boolean res = false;
        AfterSalesDaemonEvent event = new AfterSalesDaemonEvent(AfterSalesDaemonEvent.XP_AFTERSALES_REQ, cmd, action, args);
        synchronized (this.mDaemonLock) {
            if (this.mOutputStream == null) {
                Slog.e(TAG, "missing output stream");
            } else {
                try {
                    this.mOutputStream.write(event.toRawEvent().getBytes(StandardCharsets.UTF_8));
                    res = true;
                    Slog.d(TAG, "write succeed: " + event.toRawEvent());
                } catch (IOException e) {
                    Slog.e(TAG, "problem sending command:" + e);
                }
            }
        }
        return res;
    }

    @Override // com.android.server.Watchdog.Monitor
    public void monitor() {
        synchronized (this.mDaemonLock) {
        }
    }
}
