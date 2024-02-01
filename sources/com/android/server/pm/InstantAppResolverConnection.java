package com.android.server.pm;

import android.app.IInstantAppResolver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.InstantAppResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.TimedRemoteCaller;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.server.pm.InstantAppResolverConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class InstantAppResolverConnection implements IBinder.DeathRecipient {
    private static final long BIND_SERVICE_TIMEOUT_MS;
    private static final long CALL_SERVICE_TIMEOUT_MS;
    private static final boolean DEBUG_INSTANT;
    private static final int STATE_BINDING = 1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PENDING = 2;
    private static final String TAG = "PackageManager";
    private final Context mContext;
    private final Intent mIntent;
    @GuardedBy({"mLock"})
    private IInstantAppResolver mRemoteInstance;
    private final Object mLock = new Object();
    private final GetInstantAppResolveInfoCaller mGetInstantAppResolveInfoCaller = new GetInstantAppResolveInfoCaller();
    private final ServiceConnection mServiceConnection = new MyServiceConnection(this, null);
    @GuardedBy({"mLock"})
    private int mBindState = 0;
    private final Handler mBgHandler = BackgroundThread.getHandler();

    /* loaded from: classes.dex */
    public static abstract class PhaseTwoCallback {
        /* JADX INFO: Access modifiers changed from: package-private */
        public abstract void onPhaseTwoResolved(List<InstantAppResolveInfo> list, long j);
    }

    static {
        BIND_SERVICE_TIMEOUT_MS = Build.IS_ENG ? 500L : 300L;
        CALL_SERVICE_TIMEOUT_MS = Build.IS_ENG ? 200L : 100L;
        DEBUG_INSTANT = Build.IS_DEBUGGABLE;
    }

    public InstantAppResolverConnection(Context context, ComponentName componentName, String action) {
        this.mContext = context;
        this.mIntent = new Intent(action).setComponent(componentName);
    }

    public List<InstantAppResolveInfo> getInstantAppResolveInfoList(Intent sanitizedIntent, int[] hashPrefix, int userId, String token) throws ConnectionException {
        IInstantAppResolver target;
        throwIfCalledOnMainThread();
        try {
            try {
                target = getRemoteInstanceLazy(token);
            } catch (InterruptedException e) {
                throw new ConnectionException(3);
            } catch (TimeoutException e2) {
                throw new ConnectionException(1);
            }
        } catch (Throwable th) {
            e = th;
        }
        try {
            try {
                List<InstantAppResolveInfo> instantAppResolveInfoList = this.mGetInstantAppResolveInfoCaller.getInstantAppResolveInfoList(target, sanitizedIntent, hashPrefix, userId, token);
                synchronized (this.mLock) {
                    this.mLock.notifyAll();
                }
                return instantAppResolveInfoList;
            } catch (RemoteException e3) {
                synchronized (this.mLock) {
                    this.mLock.notifyAll();
                    return null;
                }
            } catch (TimeoutException e4) {
                throw new ConnectionException(2);
            }
        } catch (Throwable th2) {
            e = th2;
            synchronized (this.mLock) {
                this.mLock.notifyAll();
            }
            throw e;
        }
    }

    /* renamed from: com.android.server.pm.InstantAppResolverConnection$1  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass1 extends IRemoteCallback.Stub {
        final /* synthetic */ PhaseTwoCallback val$callback;
        final /* synthetic */ Handler val$callbackHandler;
        final /* synthetic */ long val$startTime;

        AnonymousClass1(Handler handler, PhaseTwoCallback phaseTwoCallback, long j) {
            this.val$callbackHandler = handler;
            this.val$callback = phaseTwoCallback;
            this.val$startTime = j;
        }

        public void sendResult(Bundle data) throws RemoteException {
            final ArrayList<InstantAppResolveInfo> resolveList = data.getParcelableArrayList("android.app.extra.RESOLVE_INFO");
            Handler handler = this.val$callbackHandler;
            final PhaseTwoCallback phaseTwoCallback = this.val$callback;
            final long j = this.val$startTime;
            handler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$InstantAppResolverConnection$1$eWvILRylTGnW4MEpM1wMNc5IMnY
                @Override // java.lang.Runnable
                public final void run() {
                    InstantAppResolverConnection.PhaseTwoCallback.this.onPhaseTwoResolved(resolveList, j);
                }
            });
        }
    }

    public void getInstantAppIntentFilterList(Intent sanitizedIntent, int[] hashPrefix, int userId, String token, PhaseTwoCallback callback, Handler callbackHandler, long startTime) throws ConnectionException {
        try {
            getRemoteInstanceLazy(token).getInstantAppIntentFilterList(sanitizedIntent, hashPrefix, userId, token, new AnonymousClass1(callbackHandler, callback, startTime));
        } catch (RemoteException e) {
        } catch (InterruptedException e2) {
            throw new ConnectionException(3);
        } catch (TimeoutException e3) {
            throw new ConnectionException(1);
        }
    }

    private IInstantAppResolver getRemoteInstanceLazy(String token) throws ConnectionException, TimeoutException, InterruptedException {
        long binderToken = Binder.clearCallingIdentity();
        try {
            return bind(token);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @GuardedBy({"mLock"})
    private void waitForBindLocked(String token) throws TimeoutException, InterruptedException {
        long startMillis = SystemClock.uptimeMillis();
        while (this.mBindState != 0 && this.mRemoteInstance == null) {
            long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
            long remainingMillis = BIND_SERVICE_TIMEOUT_MS - elapsedMillis;
            if (remainingMillis <= 0) {
                throw new TimeoutException("[" + token + "] Didn't bind to resolver in time!");
            }
            this.mLock.wait(remainingMillis);
        }
    }

    private IInstantAppResolver bind(String token) throws ConnectionException, TimeoutException, InterruptedException {
        IInstantAppResolver instance;
        boolean doUnbind = false;
        synchronized (this.mLock) {
            if (this.mRemoteInstance != null) {
                return this.mRemoteInstance;
            }
            if (this.mBindState == 2) {
                if (DEBUG_INSTANT) {
                    Slog.i(TAG, "[" + token + "] Previous bind timed out; waiting for connection");
                }
                try {
                    waitForBindLocked(token);
                    if (this.mRemoteInstance != null) {
                        return this.mRemoteInstance;
                    }
                } catch (TimeoutException e) {
                    doUnbind = true;
                }
            }
            if (this.mBindState == 1) {
                if (DEBUG_INSTANT) {
                    Slog.i(TAG, "[" + token + "] Another thread is binding; waiting for connection");
                }
                waitForBindLocked(token);
                if (this.mRemoteInstance != null) {
                    return this.mRemoteInstance;
                }
                throw new ConnectionException(1);
            }
            this.mBindState = 1;
            if (doUnbind) {
                try {
                    if (DEBUG_INSTANT) {
                        Slog.i(TAG, "[" + token + "] Previous connection never established; rebinding");
                    }
                    this.mContext.unbindService(this.mServiceConnection);
                } catch (Throwable th) {
                    synchronized (this.mLock) {
                        if (0 != 0 && 0 == 0) {
                            this.mBindState = 2;
                        } else {
                            this.mBindState = 0;
                        }
                        this.mLock.notifyAll();
                        throw th;
                    }
                }
            }
            if (DEBUG_INSTANT) {
                Slog.v(TAG, "[" + token + "] Binding to instant app resolver");
            }
            boolean wasBound = this.mContext.bindServiceAsUser(this.mIntent, this.mServiceConnection, 67108865, UserHandle.SYSTEM);
            if (wasBound) {
                synchronized (this.mLock) {
                    waitForBindLocked(token);
                    instance = this.mRemoteInstance;
                }
                synchronized (this.mLock) {
                    if (wasBound && instance == null) {
                        this.mBindState = 2;
                    } else {
                        this.mBindState = 0;
                    }
                    this.mLock.notifyAll();
                }
                return instance;
            }
            Slog.w(TAG, "[" + token + "] Failed to bind to: " + this.mIntent);
            throw new ConnectionException(1);
        }
    }

    private void throwIfCalledOnMainThread() {
        if (Thread.currentThread() == this.mContext.getMainLooper().getThread()) {
            throw new RuntimeException("Cannot invoke on the main thread");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void optimisticBind() {
        this.mBgHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$InstantAppResolverConnection$D-JKXi4qrYjnPQMOwj8UtfZenps
            @Override // java.lang.Runnable
            public final void run() {
                InstantAppResolverConnection.this.lambda$optimisticBind$0$InstantAppResolverConnection();
            }
        });
    }

    public /* synthetic */ void lambda$optimisticBind$0$InstantAppResolverConnection() {
        try {
            if (bind("Optimistic Bind") != null && DEBUG_INSTANT) {
                Slog.i(TAG, "Optimistic bind succeeded.");
            }
        } catch (ConnectionException | InterruptedException | TimeoutException e) {
            Slog.e(TAG, "Optimistic bind failed.", e);
        }
    }

    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        if (DEBUG_INSTANT) {
            Slog.d(TAG, "Binder to instant app resolver died");
        }
        synchronized (this.mLock) {
            handleBinderDiedLocked();
        }
        optimisticBind();
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void handleBinderDiedLocked() {
        IInstantAppResolver iInstantAppResolver = this.mRemoteInstance;
        if (iInstantAppResolver != null) {
            try {
                iInstantAppResolver.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
            }
        }
        this.mRemoteInstance = null;
    }

    /* loaded from: classes.dex */
    public static class ConnectionException extends Exception {
        public static final int FAILURE_BIND = 1;
        public static final int FAILURE_CALL = 2;
        public static final int FAILURE_INTERRUPTED = 3;
        public final int failure;

        public ConnectionException(int _failure) {
            this.failure = _failure;
        }
    }

    /* loaded from: classes.dex */
    private final class MyServiceConnection implements ServiceConnection {
        private MyServiceConnection() {
        }

        /* synthetic */ MyServiceConnection(InstantAppResolverConnection x0, AnonymousClass1 x1) {
            this();
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (InstantAppResolverConnection.DEBUG_INSTANT) {
                Slog.d(InstantAppResolverConnection.TAG, "Connected to instant app resolver");
            }
            synchronized (InstantAppResolverConnection.this.mLock) {
                InstantAppResolverConnection.this.mRemoteInstance = IInstantAppResolver.Stub.asInterface(service);
                if (InstantAppResolverConnection.this.mBindState == 2) {
                    InstantAppResolverConnection.this.mBindState = 0;
                }
                try {
                    service.linkToDeath(InstantAppResolverConnection.this, 0);
                } catch (RemoteException e) {
                    InstantAppResolverConnection.this.handleBinderDiedLocked();
                }
                InstantAppResolverConnection.this.mLock.notifyAll();
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            if (InstantAppResolverConnection.DEBUG_INSTANT) {
                Slog.d(InstantAppResolverConnection.TAG, "Disconnected from instant app resolver");
            }
            synchronized (InstantAppResolverConnection.this.mLock) {
                InstantAppResolverConnection.this.handleBinderDiedLocked();
            }
        }
    }

    /* loaded from: classes.dex */
    private static final class GetInstantAppResolveInfoCaller extends TimedRemoteCaller<List<InstantAppResolveInfo>> {
        private final IRemoteCallback mCallback;

        public GetInstantAppResolveInfoCaller() {
            super(InstantAppResolverConnection.CALL_SERVICE_TIMEOUT_MS);
            this.mCallback = new IRemoteCallback.Stub() { // from class: com.android.server.pm.InstantAppResolverConnection.GetInstantAppResolveInfoCaller.1
                public void sendResult(Bundle data) throws RemoteException {
                    ArrayList<InstantAppResolveInfo> resolveList = data.getParcelableArrayList("android.app.extra.RESOLVE_INFO");
                    int sequence = data.getInt("android.app.extra.SEQUENCE", -1);
                    GetInstantAppResolveInfoCaller.this.onRemoteMethodResult(resolveList, sequence);
                }
            };
        }

        public List<InstantAppResolveInfo> getInstantAppResolveInfoList(IInstantAppResolver target, Intent sanitizedIntent, int[] hashPrefix, int userId, String token) throws RemoteException, TimeoutException {
            int sequence = onBeforeRemoteCall();
            target.getInstantAppResolveInfoList(sanitizedIntent, hashPrefix, userId, token, sequence, this.mCallback);
            return (List) getResultTimed(sequence);
        }
    }
}
