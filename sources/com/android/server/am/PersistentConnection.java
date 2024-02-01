package com.android.server.am;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.TimeUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import java.io.PrintWriter;
/* loaded from: classes.dex */
public abstract class PersistentConnection<T> {
    private static final boolean DEBUG = false;
    @GuardedBy("mLock")
    private boolean mBound;
    private final ComponentName mComponentName;
    private final Context mContext;
    private final Handler mHandler;
    @GuardedBy("mLock")
    private boolean mIsConnected;
    private long mNextBackoffMs;
    private final double mRebindBackoffIncrease;
    private final long mRebindBackoffMs;
    private final long mRebindMaxBackoffMs;
    @GuardedBy("mLock")
    private boolean mRebindScheduled;
    private long mReconnectTime;
    @GuardedBy("mLock")
    private T mService;
    @GuardedBy("mLock")
    private boolean mShouldBeBound;
    private final String mTag;
    private final int mUserId;
    private final Object mLock = new Object();
    private final ServiceConnection mServiceConnection = new ServiceConnection() { // from class: com.android.server.am.PersistentConnection.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (PersistentConnection.this.mLock) {
                if (!PersistentConnection.this.mBound) {
                    String str = PersistentConnection.this.mTag;
                    Slog.w(str, "Connected: " + PersistentConnection.this.mComponentName.flattenToShortString() + " u" + PersistentConnection.this.mUserId + " but not bound, ignore.");
                    return;
                }
                String str2 = PersistentConnection.this.mTag;
                Slog.i(str2, "Connected: " + PersistentConnection.this.mComponentName.flattenToShortString() + " u" + PersistentConnection.this.mUserId);
                PersistentConnection.this.mIsConnected = true;
                PersistentConnection.this.mService = PersistentConnection.this.asInterface(service);
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            synchronized (PersistentConnection.this.mLock) {
                String str = PersistentConnection.this.mTag;
                Slog.i(str, "Disconnected: " + PersistentConnection.this.mComponentName.flattenToShortString() + " u" + PersistentConnection.this.mUserId);
                PersistentConnection.this.cleanUpConnectionLocked();
            }
        }

        @Override // android.content.ServiceConnection
        public void onBindingDied(ComponentName name) {
            synchronized (PersistentConnection.this.mLock) {
                if (!PersistentConnection.this.mBound) {
                    String str = PersistentConnection.this.mTag;
                    Slog.w(str, "Binding died: " + PersistentConnection.this.mComponentName.flattenToShortString() + " u" + PersistentConnection.this.mUserId + " but not bound, ignore.");
                    return;
                }
                String str2 = PersistentConnection.this.mTag;
                Slog.w(str2, "Binding died: " + PersistentConnection.this.mComponentName.flattenToShortString() + " u" + PersistentConnection.this.mUserId);
                PersistentConnection.this.scheduleRebindLocked();
            }
        }
    };
    private final Runnable mBindForBackoffRunnable = new Runnable() { // from class: com.android.server.am.-$$Lambda$PersistentConnection$xTW-hnA2hSnEFuF87mUe85RYnfE
        @Override // java.lang.Runnable
        public final void run() {
            PersistentConnection.this.bindForBackoff();
        }
    };

    protected abstract T asInterface(IBinder iBinder);

    public PersistentConnection(String tag, Context context, Handler handler, int userId, ComponentName componentName, long rebindBackoffSeconds, double rebindBackoffIncrease, long rebindMaxBackoffSeconds) {
        this.mTag = tag;
        this.mContext = context;
        this.mHandler = handler;
        this.mUserId = userId;
        this.mComponentName = componentName;
        this.mRebindBackoffMs = rebindBackoffSeconds * 1000;
        this.mRebindBackoffIncrease = rebindBackoffIncrease;
        this.mRebindMaxBackoffMs = 1000 * rebindMaxBackoffSeconds;
        this.mNextBackoffMs = this.mRebindBackoffMs;
    }

    public final ComponentName getComponentName() {
        return this.mComponentName;
    }

    public final boolean isBound() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mBound;
        }
        return z;
    }

    public final boolean isRebindScheduled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mRebindScheduled;
        }
        return z;
    }

    public final boolean isConnected() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mIsConnected;
        }
        return z;
    }

    public final T getServiceBinder() {
        T t;
        synchronized (this.mLock) {
            t = this.mService;
        }
        return t;
    }

    public final void bind() {
        synchronized (this.mLock) {
            this.mShouldBeBound = true;
            bindInnerLocked(true);
        }
    }

    @GuardedBy("mLock")
    public final void bindInnerLocked(boolean resetBackoff) {
        unscheduleRebindLocked();
        if (this.mBound) {
            return;
        }
        this.mBound = true;
        if (resetBackoff) {
            this.mNextBackoffMs = this.mRebindBackoffMs;
        }
        Intent service = new Intent().setComponent(this.mComponentName);
        boolean success = this.mContext.bindServiceAsUser(service, this.mServiceConnection, 67108865, this.mHandler, UserHandle.of(this.mUserId));
        if (!success) {
            String str = this.mTag;
            Slog.e(str, "Binding: " + service.getComponent() + " u" + this.mUserId + " failed.");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void bindForBackoff() {
        synchronized (this.mLock) {
            if (this.mShouldBeBound) {
                bindInnerLocked(false);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mLock")
    public void cleanUpConnectionLocked() {
        this.mIsConnected = false;
        this.mService = null;
    }

    public final void unbind() {
        synchronized (this.mLock) {
            this.mShouldBeBound = false;
            unbindLocked();
        }
    }

    @GuardedBy("mLock")
    private final void unbindLocked() {
        unscheduleRebindLocked();
        if (!this.mBound) {
            return;
        }
        String str = this.mTag;
        Slog.i(str, "Stopping: " + this.mComponentName.flattenToShortString() + " u" + this.mUserId);
        this.mBound = false;
        this.mContext.unbindService(this.mServiceConnection);
        cleanUpConnectionLocked();
    }

    @GuardedBy("mLock")
    void unscheduleRebindLocked() {
        injectRemoveCallbacks(this.mBindForBackoffRunnable);
        this.mRebindScheduled = false;
    }

    @GuardedBy("mLock")
    void scheduleRebindLocked() {
        unbindLocked();
        if (!this.mRebindScheduled) {
            String str = this.mTag;
            Slog.i(str, "Scheduling to reconnect in " + this.mNextBackoffMs + " ms (uptime)");
            this.mReconnectTime = injectUptimeMillis() + this.mNextBackoffMs;
            injectPostAtTime(this.mBindForBackoffRunnable, this.mReconnectTime);
            this.mNextBackoffMs = Math.min(this.mRebindMaxBackoffMs, (long) (((double) this.mNextBackoffMs) * this.mRebindBackoffIncrease));
            this.mRebindScheduled = true;
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        synchronized (this.mLock) {
            pw.print(prefix);
            pw.print(this.mComponentName.flattenToShortString());
            pw.print(this.mBound ? "  [bound]" : "  [not bound]");
            pw.print(this.mIsConnected ? "  [connected]" : "  [not connected]");
            if (this.mRebindScheduled) {
                pw.print("  reconnect in ");
                TimeUtils.formatDuration(this.mReconnectTime - injectUptimeMillis(), pw);
            }
            pw.println();
            pw.print(prefix);
            pw.print("  Next backoff(sec): ");
            pw.print(this.mNextBackoffMs / 1000);
        }
    }

    @VisibleForTesting
    void injectRemoveCallbacks(Runnable r) {
        this.mHandler.removeCallbacks(r);
    }

    @VisibleForTesting
    void injectPostAtTime(Runnable r, long uptimeMillis) {
        this.mHandler.postAtTime(r, uptimeMillis);
    }

    @VisibleForTesting
    long injectUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    @VisibleForTesting
    long getNextBackoffMsForTest() {
        return this.mNextBackoffMs;
    }

    @VisibleForTesting
    long getReconnectTimeForTest() {
        return this.mReconnectTime;
    }

    @VisibleForTesting
    ServiceConnection getServiceConnectionForTest() {
        return this.mServiceConnection;
    }

    @VisibleForTesting
    Runnable getBindForBackoffRunnableForTest() {
        return this.mBindForBackoffRunnable;
    }

    @VisibleForTesting
    boolean shouldBeBoundForTest() {
        return this.mShouldBeBound;
    }
}
