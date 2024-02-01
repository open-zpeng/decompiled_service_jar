package com.android.server.dreams;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.dreams.IDreamService;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import com.android.internal.logging.MetricsLogger;
import com.android.server.dreams.DreamController;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.policy.PhoneWindowManager;
import java.io.PrintWriter;
import java.util.NoSuchElementException;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class DreamController {
    private static final int DREAM_CONNECTION_TIMEOUT = 5000;
    private static final int DREAM_FINISH_TIMEOUT = 5000;
    private static final String TAG = "DreamController";
    private final Context mContext;
    private DreamRecord mCurrentDream;
    private long mDreamStartTime;
    private final Handler mHandler;
    private final Listener mListener;
    private final Intent mDreamingStartedIntent = new Intent("android.intent.action.DREAMING_STARTED").addFlags(1073741824);
    private final Intent mDreamingStoppedIntent = new Intent("android.intent.action.DREAMING_STOPPED").addFlags(1073741824);
    private final Runnable mStopUnconnectedDreamRunnable = new Runnable() { // from class: com.android.server.dreams.DreamController.1
        @Override // java.lang.Runnable
        public void run() {
            if (DreamController.this.mCurrentDream != null && DreamController.this.mCurrentDream.mBound && !DreamController.this.mCurrentDream.mConnected) {
                Slog.w(DreamController.TAG, "Bound dream did not connect in the time allotted");
                DreamController.this.stopDream(true);
            }
        }
    };
    private final Runnable mStopStubbornDreamRunnable = new Runnable() { // from class: com.android.server.dreams.DreamController.2
        @Override // java.lang.Runnable
        public void run() {
            Slog.w(DreamController.TAG, "Stubborn dream did not finish itself in the time allotted");
            DreamController.this.stopDream(true);
        }
    };
    private final IWindowManager mIWindowManager = WindowManagerGlobal.getWindowManagerService();
    private final Intent mCloseNotificationShadeIntent = new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS");

    /* loaded from: classes.dex */
    public interface Listener {
        void onDreamStopped(Binder binder);
    }

    public DreamController(Context context, Handler handler, Listener listener) {
        this.mContext = context;
        this.mHandler = handler;
        this.mListener = listener;
        this.mCloseNotificationShadeIntent.putExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY, "dream");
    }

    public void dump(PrintWriter pw) {
        pw.println("Dreamland:");
        if (this.mCurrentDream != null) {
            pw.println("  mCurrentDream:");
            pw.println("    mToken=" + this.mCurrentDream.mToken);
            pw.println("    mName=" + this.mCurrentDream.mName);
            pw.println("    mIsTest=" + this.mCurrentDream.mIsTest);
            pw.println("    mCanDoze=" + this.mCurrentDream.mCanDoze);
            pw.println("    mUserId=" + this.mCurrentDream.mUserId);
            pw.println("    mBound=" + this.mCurrentDream.mBound);
            pw.println("    mService=" + this.mCurrentDream.mService);
            pw.println("    mSentStartBroadcast=" + this.mCurrentDream.mSentStartBroadcast);
            pw.println("    mWakingGently=" + this.mCurrentDream.mWakingGently);
            return;
        }
        pw.println("  mCurrentDream: null");
    }

    public void startDream(Binder token, ComponentName name, boolean isTest, boolean canDoze, int userId, PowerManager.WakeLock wakeLock) {
        long j;
        StringBuilder sb;
        stopDream(true);
        Trace.traceBegin(131072L, "startDream");
        try {
            this.mContext.sendBroadcastAsUser(this.mCloseNotificationShadeIntent, UserHandle.ALL);
            sb = new StringBuilder();
            sb.append("Starting dream: name=");
            sb.append(name);
            sb.append(", isTest=");
        } catch (Throwable th) {
            ex = th;
        }
        try {
            sb.append(isTest);
            sb.append(", canDoze=");
            sb.append(canDoze);
            sb.append(", userId=");
            sb.append(userId);
            Slog.i(TAG, sb.toString());
            j = 131072;
            try {
                this.mCurrentDream = new DreamRecord(token, name, isTest, canDoze, userId, wakeLock);
                this.mDreamStartTime = SystemClock.elapsedRealtime();
            } catch (Throwable th2) {
                ex = th2;
            }
        } catch (Throwable th3) {
            ex = th3;
            j = 131072;
            Trace.traceEnd(j);
            throw ex;
        }
        try {
            MetricsLogger.visible(this.mContext, this.mCurrentDream.mCanDoze ? 223 : 222);
            try {
            } catch (RemoteException e) {
                ex = e;
            }
            try {
                this.mIWindowManager.addWindowToken(token, 2023, 0);
                Intent intent = new Intent("android.service.dreams.DreamService");
                intent.setComponent(name);
                intent.addFlags(DumpState.DUMP_VOLUMES);
                try {
                    if (this.mContext.bindServiceAsUser(intent, this.mCurrentDream, 67108865, new UserHandle(userId))) {
                        this.mCurrentDream.mBound = true;
                        this.mHandler.postDelayed(this.mStopUnconnectedDreamRunnable, 5000L);
                        Trace.traceEnd(131072L);
                        return;
                    }
                    Slog.e(TAG, "Unable to bind dream service: " + intent);
                    stopDream(true);
                    Trace.traceEnd(131072L);
                } catch (SecurityException ex) {
                    Slog.e(TAG, "Unable to bind dream service: " + intent, ex);
                    stopDream(true);
                    Trace.traceEnd(131072L);
                }
            } catch (RemoteException e2) {
                ex = e2;
                Slog.e(TAG, "Unable to add window token for dream.", ex);
                stopDream(true);
                Trace.traceEnd(131072L);
            }
        } catch (Throwable th4) {
            ex = th4;
            Trace.traceEnd(j);
            throw ex;
        }
    }

    public void stopDream(boolean immediate) {
        if (this.mCurrentDream == null) {
            return;
        }
        Trace.traceBegin(131072L, "stopDream");
        if (!immediate) {
            try {
                if (this.mCurrentDream.mWakingGently) {
                    return;
                }
                if (this.mCurrentDream.mService != null) {
                    this.mCurrentDream.mWakingGently = true;
                    try {
                        this.mCurrentDream.mService.wakeUp();
                        this.mHandler.postDelayed(this.mStopStubbornDreamRunnable, 5000L);
                        return;
                    } catch (RemoteException e) {
                    }
                }
            } finally {
                Trace.traceEnd(131072L);
            }
        }
        final DreamRecord oldDream = this.mCurrentDream;
        this.mCurrentDream = null;
        Slog.i(TAG, "Stopping dream: name=" + oldDream.mName + ", isTest=" + oldDream.mIsTest + ", canDoze=" + oldDream.mCanDoze + ", userId=" + oldDream.mUserId);
        MetricsLogger.hidden(this.mContext, oldDream.mCanDoze ? 223 : 222);
        MetricsLogger.histogram(this.mContext, oldDream.mCanDoze ? "dozing_minutes" : "dreaming_minutes", (int) ((SystemClock.elapsedRealtime() - this.mDreamStartTime) / 60000));
        this.mHandler.removeCallbacks(this.mStopUnconnectedDreamRunnable);
        this.mHandler.removeCallbacks(this.mStopStubbornDreamRunnable);
        if (oldDream.mSentStartBroadcast) {
            this.mContext.sendBroadcastAsUser(this.mDreamingStoppedIntent, UserHandle.ALL);
        }
        if (oldDream.mService != null) {
            try {
                oldDream.mService.detach();
            } catch (RemoteException e2) {
            }
            try {
                oldDream.mService.asBinder().unlinkToDeath(oldDream, 0);
            } catch (NoSuchElementException e3) {
            }
            oldDream.mService = null;
        }
        if (oldDream.mBound) {
            this.mContext.unbindService(oldDream);
        }
        oldDream.releaseWakeLockIfNeeded();
        try {
            this.mIWindowManager.removeWindowToken(oldDream.mToken, 0);
        } catch (RemoteException ex) {
            Slog.w(TAG, "Error removing window token for dream.", ex);
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.dreams.DreamController.3
            @Override // java.lang.Runnable
            public void run() {
                DreamController.this.mListener.onDreamStopped(oldDream.mToken);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void attach(IDreamService service) {
        try {
            service.asBinder().linkToDeath(this.mCurrentDream, 0);
            service.attach(this.mCurrentDream.mToken, this.mCurrentDream.mCanDoze, this.mCurrentDream.mDreamingStartedCallback);
            DreamRecord dreamRecord = this.mCurrentDream;
            dreamRecord.mService = service;
            if (!dreamRecord.mIsTest) {
                this.mContext.sendBroadcastAsUser(this.mDreamingStartedIntent, UserHandle.ALL);
                this.mCurrentDream.mSentStartBroadcast = true;
            }
        } catch (RemoteException ex) {
            Slog.e(TAG, "The dream service died unexpectedly.", ex);
            stopDream(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DreamRecord implements IBinder.DeathRecipient, ServiceConnection {
        public boolean mBound;
        public final boolean mCanDoze;
        public boolean mConnected;
        public final boolean mIsTest;
        public final ComponentName mName;
        public boolean mSentStartBroadcast;
        public IDreamService mService;
        public final Binder mToken;
        public final int mUserId;
        public PowerManager.WakeLock mWakeLock;
        public boolean mWakingGently;
        final Runnable mReleaseWakeLockIfNeeded = new Runnable() { // from class: com.android.server.dreams.-$$Lambda$gXC4nM2f5GMCBX0ED45DCQQjqv0
            @Override // java.lang.Runnable
            public final void run() {
                DreamController.DreamRecord.this.releaseWakeLockIfNeeded();
            }
        };
        final IRemoteCallback mDreamingStartedCallback = new IRemoteCallback.Stub() { // from class: com.android.server.dreams.DreamController.DreamRecord.4
            public void sendResult(Bundle data) throws RemoteException {
                DreamController.this.mHandler.post(DreamRecord.this.mReleaseWakeLockIfNeeded);
            }
        };

        public DreamRecord(Binder token, ComponentName name, boolean isTest, boolean canDoze, int userId, PowerManager.WakeLock wakeLock) {
            this.mToken = token;
            this.mName = name;
            this.mIsTest = isTest;
            this.mCanDoze = canDoze;
            this.mUserId = userId;
            this.mWakeLock = wakeLock;
            this.mWakeLock.acquire();
            DreamController.this.mHandler.postDelayed(this.mReleaseWakeLockIfNeeded, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            DreamController.this.mHandler.post(new Runnable() { // from class: com.android.server.dreams.DreamController.DreamRecord.1
                @Override // java.lang.Runnable
                public void run() {
                    DreamRecord dreamRecord = DreamRecord.this;
                    dreamRecord.mService = null;
                    DreamRecord dreamRecord2 = DreamController.this.mCurrentDream;
                    DreamRecord dreamRecord3 = DreamRecord.this;
                    if (dreamRecord2 == dreamRecord3) {
                        DreamController.this.stopDream(true);
                    }
                }
            });
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, final IBinder service) {
            DreamController.this.mHandler.post(new Runnable() { // from class: com.android.server.dreams.DreamController.DreamRecord.2
                @Override // java.lang.Runnable
                public void run() {
                    DreamRecord dreamRecord = DreamRecord.this;
                    dreamRecord.mConnected = true;
                    DreamRecord dreamRecord2 = DreamController.this.mCurrentDream;
                    DreamRecord dreamRecord3 = DreamRecord.this;
                    if (dreamRecord2 == dreamRecord3 && dreamRecord3.mService == null) {
                        DreamController.this.attach(IDreamService.Stub.asInterface(service));
                    } else {
                        DreamRecord.this.releaseWakeLockIfNeeded();
                    }
                }
            });
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            DreamController.this.mHandler.post(new Runnable() { // from class: com.android.server.dreams.DreamController.DreamRecord.3
                @Override // java.lang.Runnable
                public void run() {
                    DreamRecord dreamRecord = DreamRecord.this;
                    dreamRecord.mService = null;
                    DreamRecord dreamRecord2 = DreamController.this.mCurrentDream;
                    DreamRecord dreamRecord3 = DreamRecord.this;
                    if (dreamRecord2 == dreamRecord3) {
                        DreamController.this.stopDream(true);
                    }
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void releaseWakeLockIfNeeded() {
            PowerManager.WakeLock wakeLock = this.mWakeLock;
            if (wakeLock != null) {
                wakeLock.release();
                this.mWakeLock = null;
                DreamController.this.mHandler.removeCallbacks(this.mReleaseWakeLockIfNeeded);
            }
        }
    }
}
