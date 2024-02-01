package com.android.server.systemcaptions;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public final class RemoteSystemCaptionsManagerService {
    private static final String SERVICE_INTERFACE = "android.service.systemcaptions.SystemCaptionsManagerService";
    private static final String TAG = RemoteSystemCaptionsManagerService.class.getSimpleName();
    private final ComponentName mComponentName;
    private final Context mContext;
    private final Intent mIntent;
    @GuardedBy({"mLock"})
    private IBinder mService;
    private final int mUserId;
    private final boolean mVerbose;
    private final Object mLock = new Object();
    private final RemoteServiceConnection mServiceConnection = new RemoteServiceConnection();
    @GuardedBy({"mLock"})
    private boolean mBinding = false;
    @GuardedBy({"mLock"})
    private boolean mDestroyed = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteSystemCaptionsManagerService(Context context, ComponentName componentName, int userId, boolean verbose) {
        this.mContext = context;
        this.mComponentName = componentName;
        this.mUserId = userId;
        this.mVerbose = verbose;
        this.mIntent = new Intent(SERVICE_INTERFACE).setComponent(componentName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initialize() {
        if (this.mVerbose) {
            Slog.v(TAG, "initialize()");
        }
        ensureBound();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroy() {
        if (this.mVerbose) {
            Slog.v(TAG, "destroy()");
        }
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                if (this.mVerbose) {
                    Slog.v(TAG, "destroy(): Already destroyed");
                }
                return;
            }
            this.mDestroyed = true;
            ensureUnboundLocked();
        }
    }

    boolean isDestroyed() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mDestroyed;
        }
        return z;
    }

    private void ensureBound() {
        synchronized (this.mLock) {
            if (this.mService == null && !this.mBinding) {
                if (this.mVerbose) {
                    Slog.v(TAG, "ensureBound(): binding");
                }
                this.mBinding = true;
                boolean willBind = this.mContext.bindServiceAsUser(this.mIntent, this.mServiceConnection, 67108865, this.mHandler, new UserHandle(this.mUserId));
                if (!willBind) {
                    String str = TAG;
                    Slog.w(str, "Could not bind to " + this.mIntent + " with flags 67108865");
                    this.mBinding = false;
                    this.mService = null;
                }
            }
        }
    }

    @GuardedBy({"mLock"})
    private void ensureUnboundLocked() {
        if (this.mService == null && !this.mBinding) {
            return;
        }
        this.mBinding = false;
        this.mService = null;
        if (this.mVerbose) {
            Slog.v(TAG, "ensureUnbound(): unbinding");
        }
        this.mContext.unbindService(this.mServiceConnection);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class RemoteServiceConnection implements ServiceConnection {
        private RemoteServiceConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (RemoteSystemCaptionsManagerService.this.mLock) {
                if (RemoteSystemCaptionsManagerService.this.mVerbose) {
                    Slog.v(RemoteSystemCaptionsManagerService.TAG, "onServiceConnected()");
                }
                if (!RemoteSystemCaptionsManagerService.this.mDestroyed && RemoteSystemCaptionsManagerService.this.mBinding) {
                    RemoteSystemCaptionsManagerService.this.mBinding = false;
                    RemoteSystemCaptionsManagerService.this.mService = service;
                    return;
                }
                Slog.wtf(RemoteSystemCaptionsManagerService.TAG, "onServiceConnected() dispatched after unbindService");
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            synchronized (RemoteSystemCaptionsManagerService.this.mLock) {
                if (RemoteSystemCaptionsManagerService.this.mVerbose) {
                    Slog.v(RemoteSystemCaptionsManagerService.TAG, "onServiceDisconnected()");
                }
                RemoteSystemCaptionsManagerService.this.mBinding = true;
                RemoteSystemCaptionsManagerService.this.mService = null;
            }
        }
    }
}
