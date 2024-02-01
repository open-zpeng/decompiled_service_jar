package com.android.server.location;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.util.Preconditions;
import com.android.server.location.RemoteListenerHelper;
import java.util.HashMap;
import java.util.Map;

/* loaded from: classes.dex */
public abstract class RemoteListenerHelper<TListener extends IInterface> {
    protected static final int RESULT_GPS_LOCATION_DISABLED = 3;
    protected static final int RESULT_INTERNAL_ERROR = 4;
    protected static final int RESULT_NOT_ALLOWED = 6;
    protected static final int RESULT_NOT_AVAILABLE = 1;
    protected static final int RESULT_NOT_SUPPORTED = 2;
    protected static final int RESULT_SUCCESS = 0;
    protected static final int RESULT_UNKNOWN = 5;
    protected final AppOpsManager mAppOps;
    protected final Context mContext;
    protected final Handler mHandler;
    private boolean mHasIsSupported;
    private volatile boolean mIsRegistered;
    private boolean mIsSupported;
    private final String mTag;
    private final Map<IBinder, RemoteListenerHelper<TListener>.IdentifiedListener> mListenerMap = new HashMap();
    private int mLastReportedResult = 5;

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public interface ListenerOperation<TListener extends IInterface> {
        void execute(TListener tlistener, CallerIdentity callerIdentity) throws RemoteException;
    }

    protected abstract ListenerOperation<TListener> getHandlerOperation(int i);

    protected abstract boolean isAvailableInPlatform();

    protected abstract boolean isGpsEnabled();

    protected abstract int registerWithService();

    protected abstract void unregisterFromService();

    /* JADX INFO: Access modifiers changed from: protected */
    public RemoteListenerHelper(Context context, Handler handler, String name) {
        Preconditions.checkNotNull(name);
        this.mHandler = handler;
        this.mTag = name;
        this.mContext = context;
        this.mAppOps = (AppOpsManager) context.getSystemService("appops");
    }

    public boolean isRegistered() {
        return this.mIsRegistered;
    }

    public void addListener(TListener listener, CallerIdentity callerIdentity) {
        int result;
        Preconditions.checkNotNull(listener, "Attempted to register a 'null' listener.");
        IBinder binder = listener.asBinder();
        synchronized (this.mListenerMap) {
            if (this.mListenerMap.containsKey(binder)) {
                return;
            }
            RemoteListenerHelper<TListener>.IdentifiedListener identifiedListener = new IdentifiedListener(this, listener, callerIdentity, null);
            this.mListenerMap.put(binder, identifiedListener);
            if (!isAvailableInPlatform()) {
                result = 1;
            } else if (this.mHasIsSupported && !this.mIsSupported) {
                result = 2;
            } else if (!isGpsEnabled()) {
                result = 3;
            } else if (!this.mHasIsSupported || !this.mIsSupported) {
                return;
            } else {
                tryRegister();
                result = 0;
            }
            post(identifiedListener, getHandlerOperation(result));
        }
    }

    public void removeListener(TListener listener) {
        Preconditions.checkNotNull(listener, "Attempted to remove a 'null' listener.");
        synchronized (this.mListenerMap) {
            this.mListenerMap.remove(listener.asBinder());
            if (this.mListenerMap.isEmpty()) {
                tryUnregister();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void foreach(ListenerOperation<TListener> operation) {
        synchronized (this.mListenerMap) {
            foreachUnsafe(operation);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setSupported(boolean value) {
        synchronized (this.mListenerMap) {
            this.mHasIsSupported = true;
            this.mIsSupported = value;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void tryUpdateRegistrationWithService() {
        synchronized (this.mListenerMap) {
            if (!isGpsEnabled()) {
                tryUnregister();
            } else if (this.mListenerMap.isEmpty()) {
            } else {
                tryRegister();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void updateResult() {
        synchronized (this.mListenerMap) {
            int newResult = calculateCurrentResultUnsafe();
            if (this.mLastReportedResult == newResult) {
                return;
            }
            foreachUnsafe(getHandlerOperation(newResult));
            this.mLastReportedResult = newResult;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean hasPermission(Context context, CallerIdentity callerIdentity) {
        return LocationPermissionUtil.doesCallerReportToAppOps(context, callerIdentity) ? this.mAppOps.checkOpNoThrow(1, callerIdentity.mUid, callerIdentity.mPackageName) == 0 : this.mAppOps.noteOpNoThrow(1, callerIdentity.mUid, callerIdentity.mPackageName) == 0;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void logPermissionDisabledEventNotReported(String tag, String packageName, String event) {
        if (Log.isLoggable(tag, 3)) {
            Log.d(tag, "Location permission disabled. Skipping " + event + " reporting for app: " + packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void foreachUnsafe(ListenerOperation<TListener> operation) {
        for (RemoteListenerHelper<TListener>.IdentifiedListener identifiedListener : this.mListenerMap.values()) {
            post(identifiedListener, operation);
        }
    }

    private void post(RemoteListenerHelper<TListener>.IdentifiedListener identifiedListener, ListenerOperation<TListener> operation) {
        if (operation != null) {
            this.mHandler.post(new HandlerRunnable(this, identifiedListener, operation, null));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.location.RemoteListenerHelper$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 implements Runnable {
        int registrationState = 4;

        AnonymousClass1() {
        }

        @Override // java.lang.Runnable
        public void run() {
            if (!RemoteListenerHelper.this.mIsRegistered) {
                this.registrationState = RemoteListenerHelper.this.registerWithService();
                RemoteListenerHelper.this.mIsRegistered = this.registrationState == 0;
            }
            if (!RemoteListenerHelper.this.mIsRegistered) {
                RemoteListenerHelper.this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$RemoteListenerHelper$1$zm4ubOjPyd7USwEJsdJwj1QLhgw
                    @Override // java.lang.Runnable
                    public final void run() {
                        RemoteListenerHelper.AnonymousClass1.this.lambda$run$0$RemoteListenerHelper$1();
                    }
                });
            }
        }

        public /* synthetic */ void lambda$run$0$RemoteListenerHelper$1() {
            synchronized (RemoteListenerHelper.this.mListenerMap) {
                RemoteListenerHelper.this.foreachUnsafe(RemoteListenerHelper.this.getHandlerOperation(this.registrationState));
            }
        }
    }

    private void tryRegister() {
        this.mHandler.post(new AnonymousClass1());
    }

    private void tryUnregister() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$RemoteListenerHelper$0Rlnad83RE1JdiVK0ULOLm530JM
            @Override // java.lang.Runnable
            public final void run() {
                RemoteListenerHelper.this.lambda$tryUnregister$0$RemoteListenerHelper();
            }
        });
    }

    public /* synthetic */ void lambda$tryUnregister$0$RemoteListenerHelper() {
        if (!this.mIsRegistered) {
            return;
        }
        unregisterFromService();
        this.mIsRegistered = false;
    }

    private int calculateCurrentResultUnsafe() {
        if (!isAvailableInPlatform()) {
            return 1;
        }
        if (!this.mHasIsSupported || this.mListenerMap.isEmpty()) {
            return 5;
        }
        if (!this.mIsSupported) {
            return 2;
        }
        if (!isGpsEnabled()) {
            return 3;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class IdentifiedListener {
        private final CallerIdentity mCallerIdentity;
        private final TListener mListener;

        /* synthetic */ IdentifiedListener(RemoteListenerHelper x0, IInterface x1, CallerIdentity x2, AnonymousClass1 x3) {
            this(x1, x2);
        }

        private IdentifiedListener(TListener listener, CallerIdentity callerIdentity) {
            this.mListener = listener;
            this.mCallerIdentity = callerIdentity;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class HandlerRunnable implements Runnable {
        private final RemoteListenerHelper<TListener>.IdentifiedListener mIdentifiedListener;
        private final ListenerOperation<TListener> mOperation;

        /* synthetic */ HandlerRunnable(RemoteListenerHelper x0, IdentifiedListener x1, ListenerOperation x2, AnonymousClass1 x3) {
            this(x1, x2);
        }

        private HandlerRunnable(RemoteListenerHelper<TListener>.IdentifiedListener identifiedListener, ListenerOperation<TListener> operation) {
            this.mIdentifiedListener = identifiedListener;
            this.mOperation = operation;
        }

        /* JADX WARN: Multi-variable type inference failed */
        @Override // java.lang.Runnable
        public void run() {
            try {
                this.mOperation.execute(((IdentifiedListener) this.mIdentifiedListener).mListener, ((IdentifiedListener) this.mIdentifiedListener).mCallerIdentity);
            } catch (RemoteException e) {
                Log.v(RemoteListenerHelper.this.mTag, "Error in monitored listener.", e);
            }
        }
    }
}
