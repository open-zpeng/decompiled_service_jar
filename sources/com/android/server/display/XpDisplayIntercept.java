package com.android.server.display;

import android.os.Build;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.SurfaceControl;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/* loaded from: classes.dex */
public class XpDisplayIntercept {
    private static final String DISPLAY_STATUS = "/sys/bus/i2c/drivers/max96789/10-0062/max96789_status";
    private static final String DISPLAY_VALUE = "dp_off";
    private static final String STATUS_OK = "ok";
    private static final String TAG = "XpDisplayIntercept";
    private boolean mIsSyncComplete;
    private final SparseArray<IBinder> mDisplayToken = new SparseArray<>(4);
    private final SparseIntArray mDisplayState = new SparseIntArray(4);
    private final Object mSyncIntercept = new Object();

    public boolean isSetDisplayPowerModeEnable(int displayType, int mode) {
        boolean isSyncComplete;
        synchronized (this.mSyncIntercept) {
            isSyncComplete = this.mIsSyncComplete;
        }
        if (isSyncComplete || displayType != 1 || mode != 0) {
            return true;
        }
        Slog.i(TAG, "isSetDisplayPowerModeEnable is not complete");
        return !Build.IS_USER && SystemProperties.getBoolean("xp.debug.display", false);
    }

    public void addDisplayToken(int displayType, IBinder token) {
        synchronized (this.mSyncIntercept) {
            this.mDisplayToken.put(displayType, token);
        }
    }

    public void handleDisplayPowerMode(int displayType, int mode) {
        synchronized (this.mSyncIntercept) {
            if (this.mIsSyncComplete) {
                return;
            }
            this.mDisplayState.put(displayType, mode);
            if (mode != 0) {
                return;
            }
            boolean isDisplayAllOff = false;
            if (displayType == 6) {
                if (this.mDisplayState.get(1) == 0) {
                    isDisplayAllOff = true;
                }
            } else if (displayType == 1 && this.mDisplayState.get(6) == 0) {
                isDisplayAllOff = true;
            }
            if (isDisplayAllOff) {
                Slog.i(TAG, "setDisplayPowerMode off built in");
                SurfaceControl.setDisplayPowerMode(this.mDisplayToken.get(1), 0);
                syncDisplayState();
                synchronized (this.mSyncIntercept) {
                    try {
                        if (!this.mIsSyncComplete) {
                            this.mSyncIntercept.wait();
                        }
                    } catch (InterruptedException e) {
                        Slog.w(TAG, "handleDisplayPowerMode: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void syncDisplayState() {
        new Thread(new Runnable() { // from class: com.android.server.display.-$$Lambda$XpDisplayIntercept$JMr-hXl-jEeHJLcxUnQAcV9Kaf4
            @Override // java.lang.Runnable
            public final void run() {
                XpDisplayIntercept.this.lambda$syncDisplayState$0$XpDisplayIntercept();
            }
        }).start();
    }

    public /* synthetic */ void lambda$syncDisplayState$0$XpDisplayIntercept() {
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(DISPLAY_STATUS, "rw");
            randomAccessFile.write(DISPLAY_VALUE.getBytes(StandardCharsets.UTF_8));
            long length = randomAccessFile.length();
            byte[] bytes = new byte[(int) length];
            int i = 0;
            while (true) {
                if (i >= 50) {
                    break;
                }
                randomAccessFile.seek(0L);
                int read = randomAccessFile.read(bytes);
                String status = new String(bytes, 0, read).trim();
                if (STATUS_OK.equals(status)) {
                    Slog.i(TAG, "dp_off result: " + status);
                    synchronized (this.mSyncIntercept) {
                        this.mIsSyncComplete = true;
                    }
                    break;
                }
                if (i % 6 == 0) {
                    Slog.i(TAG, "dp_off result: " + status);
                }
                Thread.sleep(100L);
                i++;
            }
            randomAccessFile.close();
        } catch (Exception e) {
            Slog.w(TAG, "syncDisplayState--> " + e.getMessage());
        }
        synchronized (this.mSyncIntercept) {
            this.mSyncIntercept.notifyAll();
        }
    }
}
