package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.TimeUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.job.controllers.JobStatus;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public class OldNetworkTimeUpdateService extends Binder implements NetworkTimeUpdateService {
    private static final String ACTION_POLL = "com.android.server.NetworkTimeUpdateService.action.POLL";
    private static final boolean DBG = false;
    private static final int EVENT_AUTO_TIME_CHANGED = 1;
    private static final int EVENT_NETWORK_CHANGED = 3;
    private static final int EVENT_POLL_NETWORK_TIME = 2;
    private static final long NOT_SET = -1;
    private static final int POLL_REQUEST = 0;
    private static final String TAG = "NetworkTimeUpdateService";
    private final AlarmManager mAlarmManager;
    private final ConnectivityManager mCM;
    private final Context mContext;
    private Handler mHandler;
    private NetworkTimeUpdateCallback mNetworkTimeUpdateCallback;
    private final PendingIntent mPendingPollIntent;
    private final long mPollingIntervalMs;
    private final long mPollingIntervalShorterMs;
    private SettingsObserver mSettingsObserver;
    private final NtpTrustedTime mTime;
    private final int mTimeErrorThresholdMs;
    private int mTryAgainCounter;
    private final int mTryAgainTimesMax;
    private final PowerManager.WakeLock mWakeLock;
    private long mNitzTimeSetTime = -1;
    private Network mDefaultNetwork = null;
    private BroadcastReceiver mNitzReceiver = new BroadcastReceiver() { // from class: com.android.server.OldNetworkTimeUpdateService.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.intent.action.NETWORK_SET_TIME".equals(action)) {
                OldNetworkTimeUpdateService.this.mNitzTimeSetTime = SystemClock.elapsedRealtime();
            }
        }
    };

    public OldNetworkTimeUpdateService(Context context) {
        this.mContext = context;
        this.mTime = NtpTrustedTime.getInstance(context);
        this.mAlarmManager = (AlarmManager) this.mContext.getSystemService(AlarmManager.class);
        this.mCM = (ConnectivityManager) this.mContext.getSystemService(ConnectivityManager.class);
        Intent pollIntent = new Intent(ACTION_POLL, (Uri) null);
        this.mPendingPollIntent = PendingIntent.getBroadcast(this.mContext, 0, pollIntent, 0);
        this.mPollingIntervalMs = this.mContext.getResources().getInteger(17694867);
        this.mPollingIntervalShorterMs = this.mContext.getResources().getInteger(17694868);
        this.mTryAgainTimesMax = this.mContext.getResources().getInteger(17694869);
        this.mTimeErrorThresholdMs = this.mContext.getResources().getInteger(17694870);
        this.mWakeLock = ((PowerManager) context.getSystemService(PowerManager.class)).newWakeLock(1, TAG);
    }

    @Override // com.android.server.NetworkTimeUpdateService
    public void systemRunning() {
        registerForTelephonyIntents();
        registerForAlarms();
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        this.mHandler = new MyHandler(thread.getLooper());
        this.mNetworkTimeUpdateCallback = new NetworkTimeUpdateCallback();
        this.mCM.registerDefaultNetworkCallback(this.mNetworkTimeUpdateCallback, this.mHandler);
        this.mSettingsObserver = new SettingsObserver(this.mHandler, 1);
        this.mSettingsObserver.observe(this.mContext);
    }

    private void registerForTelephonyIntents() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.NETWORK_SET_TIME");
        this.mContext.registerReceiver(this.mNitzReceiver, intentFilter);
    }

    private void registerForAlarms() {
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.OldNetworkTimeUpdateService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                OldNetworkTimeUpdateService.this.mHandler.obtainMessage(2).sendToTarget();
            }
        }, new IntentFilter(ACTION_POLL));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPollNetworkTime(int event) {
        if (this.mDefaultNetwork == null) {
            return;
        }
        this.mWakeLock.acquire();
        try {
            onPollNetworkTimeUnderWakeLock(event);
        } finally {
            this.mWakeLock.release();
        }
    }

    private void onPollNetworkTimeUnderWakeLock(int event) {
        if (this.mTime.getCacheAge() >= this.mPollingIntervalMs) {
            this.mTime.forceRefresh();
        }
        long cacheAge = this.mTime.getCacheAge();
        long j = this.mPollingIntervalMs;
        if (cacheAge < j) {
            resetAlarm(j);
            if (isAutomaticTimeRequested()) {
                updateSystemClock(event);
                return;
            }
            return;
        }
        this.mTryAgainCounter++;
        int i = this.mTryAgainTimesMax;
        if (i < 0 || this.mTryAgainCounter <= i) {
            resetAlarm(this.mPollingIntervalShorterMs);
            return;
        }
        this.mTryAgainCounter = 0;
        resetAlarm(j);
    }

    private long getNitzAge() {
        if (this.mNitzTimeSetTime == -1) {
            return JobStatus.NO_LATEST_RUNTIME;
        }
        return SystemClock.elapsedRealtime() - this.mNitzTimeSetTime;
    }

    private void updateSystemClock(int event) {
        boolean forceUpdate = event == 1;
        if (!forceUpdate) {
            if (getNitzAge() < this.mPollingIntervalMs) {
                return;
            }
            long skew = Math.abs(this.mTime.currentTimeMillis() - System.currentTimeMillis());
            if (skew < this.mTimeErrorThresholdMs) {
                return;
            }
        }
        SystemClock.setCurrentTimeMillis(this.mTime.currentTimeMillis());
    }

    private void resetAlarm(long interval) {
        this.mAlarmManager.cancel(this.mPendingPollIntent);
        long now = SystemClock.elapsedRealtime();
        long next = now + interval;
        this.mAlarmManager.set(3, next, this.mPendingPollIntent);
    }

    private boolean isAutomaticTimeRequested() {
        return Settings.Global.getInt(this.mContext.getContentResolver(), "auto_time", 0) != 0;
    }

    /* loaded from: classes.dex */
    private class MyHandler extends Handler {
        public MyHandler(Looper l) {
            super(l);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1 || i == 2 || i == 3) {
                OldNetworkTimeUpdateService.this.onPollNetworkTime(msg.what);
            }
        }
    }

    /* loaded from: classes.dex */
    private class NetworkTimeUpdateCallback extends ConnectivityManager.NetworkCallback {
        private NetworkTimeUpdateCallback() {
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onAvailable(Network network) {
            Log.d(OldNetworkTimeUpdateService.TAG, String.format("New default network %s; checking time.", network));
            OldNetworkTimeUpdateService.this.mDefaultNetwork = network;
            OldNetworkTimeUpdateService.this.onPollNetworkTime(3);
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onLost(Network network) {
            if (network.equals(OldNetworkTimeUpdateService.this.mDefaultNetwork)) {
                OldNetworkTimeUpdateService.this.mDefaultNetwork = null;
            }
        }
    }

    /* loaded from: classes.dex */
    private static class SettingsObserver extends ContentObserver {
        private Handler mHandler;
        private int mMsg;

        SettingsObserver(Handler handler, int msg) {
            super(handler);
            this.mHandler = handler;
            this.mMsg = msg;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor("auto_time"), false, this);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            this.mHandler.obtainMessage(this.mMsg).sendToTarget();
        }
    }

    @Override // android.os.Binder
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            pw.print("PollingIntervalMs: ");
            TimeUtils.formatDuration(this.mPollingIntervalMs, pw);
            pw.print("\nPollingIntervalShorterMs: ");
            TimeUtils.formatDuration(this.mPollingIntervalShorterMs, pw);
            pw.println("\nTryAgainTimesMax: " + this.mTryAgainTimesMax);
            pw.print("TimeErrorThresholdMs: ");
            TimeUtils.formatDuration((long) this.mTimeErrorThresholdMs, pw);
            pw.println("\nTryAgainCounter: " + this.mTryAgainCounter);
            pw.println("NTP cache age: " + this.mTime.getCacheAge());
            pw.println("NTP cache certainty: " + this.mTime.getCacheCertainty());
            pw.println();
        }
    }
}
