package com.android.server.location;

import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.util.Preconditions;
import java.util.HashMap;
import java.util.Map;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public abstract class RemoteListenerHelper<TListener extends IInterface> {
    protected static final int RESULT_GPS_LOCATION_DISABLED = 3;
    protected static final int RESULT_INTERNAL_ERROR = 4;
    protected static final int RESULT_NOT_ALLOWED = 6;
    protected static final int RESULT_NOT_AVAILABLE = 1;
    protected static final int RESULT_NOT_SUPPORTED = 2;
    protected static final int RESULT_SUCCESS = 0;
    protected static final int RESULT_UNKNOWN = 5;
    private final Handler mHandler;
    private boolean mHasIsSupported;
    private volatile boolean mIsRegistered;
    private boolean mIsSupported;
    private final String mTag;
    private final Map<IBinder, RemoteListenerHelper<TListener>.LinkedListener> mListenerMap = new HashMap();
    private int mLastReportedResult = 5;

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public interface ListenerOperation<TListener extends IInterface> {
        void execute(TListener tlistener) throws RemoteException;
    }

    protected abstract ListenerOperation<TListener> getHandlerOperation(int i);

    protected abstract boolean isAvailableInPlatform();

    protected abstract boolean isGpsEnabled();

    protected abstract int registerWithService();

    protected abstract void unregisterFromService();

    /* JADX INFO: Access modifiers changed from: protected */
    public RemoteListenerHelper(Handler handler, String name) {
        Preconditions.checkNotNull(name);
        this.mHandler = handler;
        this.mTag = name;
    }

    public boolean isRegistered() {
        return this.mIsRegistered;
    }

    public boolean addListener(TListener listener) {
        int result;
        Preconditions.checkNotNull(listener, "Attempted to register a 'null' listener.");
        IBinder binder = listener.asBinder();
        RemoteListenerHelper<TListener>.LinkedListener deathListener = new LinkedListener(listener);
        synchronized (this.mListenerMap) {
            if (this.mListenerMap.containsKey(binder)) {
                return true;
            }
            try {
                binder.linkToDeath(deathListener, 0);
                this.mListenerMap.put(binder, deathListener);
                if (!isAvailableInPlatform()) {
                    result = 1;
                } else if (this.mHasIsSupported && !this.mIsSupported) {
                    result = 2;
                } else if (!isGpsEnabled()) {
                    result = 3;
                } else if (!this.mHasIsSupported || !this.mIsSupported) {
                    return true;
                } else {
                    tryRegister();
                    result = 0;
                }
                post(listener, getHandlerOperation(result));
                return true;
            } catch (RemoteException e) {
                Log.v(this.mTag, "Remote listener already died.", e);
                return false;
            }
        }
    }

    public void removeListener(TListener listener) {
        RemoteListenerHelper<TListener>.LinkedListener linkedListener;
        Preconditions.checkNotNull(listener, "Attempted to remove a 'null' listener.");
        IBinder binder = listener.asBinder();
        synchronized (this.mListenerMap) {
            linkedListener = this.mListenerMap.remove(binder);
            if (this.mListenerMap.isEmpty()) {
                tryUnregister();
            }
        }
        if (linkedListener != null) {
            binder.unlinkToDeath(linkedListener, 0);
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

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    public void foreachUnsafe(ListenerOperation<TListener> operation) {
        for (RemoteListenerHelper<TListener>.LinkedListener linkedListener : this.mListenerMap.values()) {
            post(linkedListener.getUnderlyingListener(), operation);
        }
    }

    private void post(TListener listener, ListenerOperation<TListener> operation) {
        if (operation != null) {
            this.mHandler.post(new HandlerRunnable(listener, operation));
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
                RemoteListenerHelper.this.mHandler.post(new Runnable() { // from class: com.android.server.location.RemoteListenerHelper.1.1
                    @Override // java.lang.Runnable
                    public void run() {
                        synchronized (RemoteListenerHelper.this.mListenerMap) {
                            ListenerOperation<TListener> operation = RemoteListenerHelper.this.getHandlerOperation(AnonymousClass1.this.registrationState);
                            RemoteListenerHelper.this.foreachUnsafe(operation);
                        }
                    }
                });
            }
        }
    }

    private void tryRegister() {
        this.mHandler.post(new AnonymousClass1());
    }

    private void tryUnregister() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.RemoteListenerHelper.2
            @Override // java.lang.Runnable
            public void run() {
                if (!RemoteListenerHelper.this.mIsRegistered) {
                    return;
                }
                RemoteListenerHelper.this.unregisterFromService();
                RemoteListenerHelper.this.mIsRegistered = false;
            }
        });
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
    public class LinkedListener implements IBinder.DeathRecipient {
        private final TListener mListener;

        public LinkedListener(TListener listener) {
            this.mListener = listener;
        }

        public TListener getUnderlyingListener() {
            return this.mListener;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            String str = RemoteListenerHelper.this.mTag;
            Log.d(str, "Remote Listener died: " + this.mListener);
            RemoteListenerHelper.this.removeListener(this.mListener);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class HandlerRunnable implements Runnable {
        private final TListener mListener;
        private final ListenerOperation<TListener> mOperation;

        public HandlerRunnable(TListener listener, ListenerOperation<TListener> operation) {
            this.mListener = listener;
            this.mOperation = operation;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                this.mOperation.execute(this.mListener);
            } catch (RemoteException e) {
                Log.v(RemoteListenerHelper.this.mTag, "Error in monitored listener.", e);
            }
        }
    }
}
