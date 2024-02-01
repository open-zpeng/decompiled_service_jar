package com.android.server.location;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.util.NtpTrustedTime;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import java.util.Date;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class NtpTimeHelper {
    private static final boolean DEBUG = Log.isLoggable("NtpTimeHelper", 3);
    private static final long MAX_RETRY_INTERVAL = 14400000;
    private static final int MSG_INJECT_TIME = 101;
    private static final int MSG_RETRIEVE_TIME = 102;
    @VisibleForTesting
    static final long NTP_INTERVAL = 86400000;
    @VisibleForTesting
    static final long RETRY_INTERVAL = 300000;
    private static final int STATE_IDLE = 2;
    private static final int STATE_PENDING_NETWORK = 0;
    private static final int STATE_RETRIEVING_AND_INJECTING = 1;
    private static final String TAG = "NtpTimeHelper";
    private static final String WAKELOCK_KEY = "NtpTimeHelper";
    private static final long WAKELOCK_TIMEOUT_MILLIS = 60000;
    @GuardedBy("this")
    private final InjectNtpTimeCallback mCallback;
    private final ConnectivityManager mConnMgr;
    private final Handler mHandler;
    @GuardedBy("this")
    private int mInjectNtpTimeState;
    private final ExponentialBackOff mNtpBackOff;
    private final NtpTrustedTime mNtpTime;
    @GuardedBy("this")
    private boolean mOnDemandTimeInjection;
    private final PowerManager.WakeLock mWakeLock;

    /* loaded from: classes.dex */
    interface InjectNtpTimeCallback {
        void injectTime(long j, long j2, int i);
    }

    @VisibleForTesting
    NtpTimeHelper(Context context, Looper looper, InjectNtpTimeCallback callback, NtpTrustedTime ntpTime) {
        this.mNtpBackOff = new ExponentialBackOff(300000L, 14400000L);
        this.mInjectNtpTimeState = 0;
        this.mConnMgr = (ConnectivityManager) context.getSystemService("connectivity");
        this.mCallback = callback;
        this.mNtpTime = ntpTime;
        this.mHandler = new Handler(looper) { // from class: com.android.server.location.NtpTimeHelper.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case 101:
                        Bundle bundle = msg.getData();
                        if (bundle != null) {
                            int certainty = msg.arg1;
                            long time = bundle.getLong("time", 0L);
                            long timeReference = bundle.getLong("timeReference", 0L);
                            if (NtpTimeHelper.this.mCallback != null) {
                                NtpTimeHelper.this.mCallback.injectTime(time, timeReference, certainty);
                                return;
                            }
                            return;
                        }
                        return;
                    case 102:
                        NtpTimeHelper.this.retrieveAndInjectNtpTime();
                        return;
                    default:
                        return;
                }
            }
        };
        PowerManager powerManager = (PowerManager) context.getSystemService("power");
        this.mWakeLock = powerManager.newWakeLock(1, "NtpTimeHelper");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public NtpTimeHelper(Context context, Looper looper, InjectNtpTimeCallback callback) {
        this(context, looper, callback, NtpTrustedTime.getInstance(context));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void enablePeriodicTimeInjection() {
        this.mOnDemandTimeInjection = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onNetworkAvailable() {
        if (this.mInjectNtpTimeState == 0) {
            retrieveAndInjectNtpTime();
        }
    }

    private boolean isNetworkConnected() {
        NetworkInfo activeNetworkInfo = this.mConnMgr.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void retrieveAndInjectNtpTime() {
        if (this.mInjectNtpTimeState == 1) {
            return;
        }
        if (!isNetworkConnected()) {
            this.mInjectNtpTimeState = 0;
            return;
        }
        this.mInjectNtpTimeState = 1;
        this.mWakeLock.acquire(60000L);
        new Thread(new Runnable() { // from class: com.android.server.location.-$$Lambda$NtpTimeHelper$xWqlqJuq4jBJ5-xhFLCwEKGVB0k
            @Override // java.lang.Runnable
            public final void run() {
                NtpTimeHelper.this.blockingGetNtpTimeAndInject();
            }
        }).start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void blockingGetNtpTimeAndInject() {
        long delay;
        boolean refreshSuccess = true;
        if (this.mNtpTime.getCacheAge() >= 86400000) {
            refreshSuccess = this.mNtpTime.forceRefresh();
        }
        synchronized (this) {
            this.mInjectNtpTimeState = 2;
            if (this.mNtpTime.getCacheAge() < 86400000) {
                long time = this.mNtpTime.getCachedNtpTime();
                long timeReference = this.mNtpTime.getCachedNtpTimeReference();
                long certainty = this.mNtpTime.getCacheCertainty();
                if (DEBUG) {
                    long now = System.currentTimeMillis();
                    Log.d("NtpTimeHelper", "NTP server returned: " + time + " (" + new Date(time) + ") reference: " + timeReference + " certainty: " + certainty + " system time offset: " + (time - now));
                }
                postInjectTimeTask(time, timeReference, (int) certainty);
                delay = 86400000;
                this.mNtpBackOff.reset();
            } else {
                Log.e("NtpTimeHelper", "requestTime failed");
                delay = this.mNtpBackOff.nextBackoffMillis();
            }
            long delay2 = delay;
            if (DEBUG) {
                Log.d("NtpTimeHelper", String.format("onDemandTimeInjection=%s, refreshSuccess=%s, delay=%s", Boolean.valueOf(this.mOnDemandTimeInjection), Boolean.valueOf(refreshSuccess), Long.valueOf(delay2)));
            }
            if (this.mOnDemandTimeInjection || !refreshSuccess) {
                postRetrieveTime(delay2);
            }
        }
        try {
            this.mWakeLock.release();
        } catch (Exception e) {
        }
    }

    private void postInjectTimeTask(long time, long timeReference, int uncertainty) {
        Message msg = Message.obtain();
        msg.what = 101;
        msg.arg1 = uncertainty;
        Bundle bundle = new Bundle();
        bundle.putLong("time", time);
        bundle.putLong("timeReference", timeReference);
        msg.setData(bundle);
        this.mHandler.removeMessages(101);
        this.mHandler.sendMessage(msg);
    }

    private void postRetrieveTime(long delay) {
        this.mHandler.removeMessages(102);
        this.mHandler.sendEmptyMessageDelayed(102, delay);
    }
}
