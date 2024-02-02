package com.android.server.backup.transport;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.EventLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupManagerConstants;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import dalvik.system.CloseGuard;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
/* loaded from: classes.dex */
public class TransportClient {
    private static final int LOG_BUFFER_SIZE = 5;
    @VisibleForTesting
    static final String TAG = "TransportClient";
    private final Intent mBindIntent;
    private final CloseGuard mCloseGuard;
    private final ServiceConnection mConnection;
    private final Context mContext;
    private final String mCreatorLogString;
    private final String mIdentifier;
    private final Handler mListenerHandler;
    @GuardedBy("mStateLock")
    private final Map<TransportConnectionListener, String> mListeners;
    @GuardedBy("mLogBufferLock")
    private final List<String> mLogBuffer;
    private final Object mLogBufferLock;
    private final String mPrefixForLog;
    @GuardedBy("mStateLock")
    private int mState;
    private final Object mStateLock;
    @GuardedBy("mStateLock")
    private volatile IBackupTransport mTransport;
    private final ComponentName mTransportComponent;
    private final TransportStats mTransportStats;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    private @interface State {
        public static final int BOUND_AND_CONNECTING = 2;
        public static final int CONNECTED = 3;
        public static final int IDLE = 1;
        public static final int UNUSABLE = 0;
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    private @interface Transition {
        public static final int DOWN = -1;
        public static final int NO_TRANSITION = 0;
        public static final int UP = 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TransportClient(Context context, TransportStats transportStats, Intent bindIntent, ComponentName transportComponent, String identifier, String caller) {
        this(context, transportStats, bindIntent, transportComponent, identifier, caller, new Handler(Looper.getMainLooper()));
    }

    @VisibleForTesting
    TransportClient(Context context, TransportStats transportStats, Intent bindIntent, ComponentName transportComponent, String identifier, String caller, Handler listenerHandler) {
        this.mStateLock = new Object();
        this.mLogBufferLock = new Object();
        this.mCloseGuard = CloseGuard.get();
        this.mLogBuffer = new LinkedList();
        this.mListeners = new ArrayMap();
        this.mState = 1;
        this.mContext = context;
        this.mTransportStats = transportStats;
        this.mTransportComponent = transportComponent;
        this.mBindIntent = bindIntent;
        this.mIdentifier = identifier;
        this.mCreatorLogString = caller;
        this.mListenerHandler = listenerHandler;
        this.mConnection = new TransportConnection(context, this);
        String classNameForLog = this.mTransportComponent.getShortClassName().replaceFirst(".*\\.", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        this.mPrefixForLog = classNameForLog + AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR + this.mIdentifier + ":";
        this.mCloseGuard.open("markAsDisposed");
    }

    public ComponentName getTransportComponent() {
        return this.mTransportComponent;
    }

    public void connectAsync(TransportConnectionListener listener, String caller) {
        synchronized (this.mStateLock) {
            checkStateIntegrityLocked();
            switch (this.mState) {
                case 0:
                    log(5, caller, "Async connect: UNUSABLE client");
                    notifyListener(listener, null, caller);
                    break;
                case 1:
                    boolean hasBound = this.mContext.bindServiceAsUser(this.mBindIntent, this.mConnection, 1, UserHandle.SYSTEM);
                    if (hasBound) {
                        log(3, caller, "Async connect: service bound, connecting");
                        setStateLocked(2, null);
                        this.mListeners.put(listener, caller);
                        break;
                    } else {
                        log(6, "Async connect: bindService returned false");
                        this.mContext.unbindService(this.mConnection);
                        notifyListener(listener, null, caller);
                        break;
                    }
                case 2:
                    log(3, caller, "Async connect: already connecting, adding listener");
                    this.mListeners.put(listener, caller);
                    break;
                case 3:
                    log(3, caller, "Async connect: reusing transport");
                    notifyListener(listener, this.mTransport, caller);
                    break;
            }
        }
    }

    public void unbind(String caller) {
        synchronized (this.mStateLock) {
            checkStateIntegrityLocked();
            log(3, caller, "Unbind requested (was " + stateToString(this.mState) + ")");
            switch (this.mState) {
                case 2:
                    setStateLocked(1, null);
                    this.mContext.unbindService(this.mConnection);
                    notifyListenersAndClearLocked(null);
                    break;
                case 3:
                    setStateLocked(1, null);
                    this.mContext.unbindService(this.mConnection);
                    break;
            }
        }
    }

    public void markAsDisposed() {
        synchronized (this.mStateLock) {
            Preconditions.checkState(this.mState < 2, "Can't mark as disposed if still bound");
            this.mCloseGuard.close();
        }
    }

    public IBackupTransport connect(String caller) {
        Preconditions.checkState(!Looper.getMainLooper().isCurrentThread(), "Can't call connect() on main thread");
        IBackupTransport transport = this.mTransport;
        if (transport != null) {
            log(3, caller, "Sync connect: reusing transport");
            return transport;
        }
        synchronized (this.mStateLock) {
            if (this.mState == 0) {
                log(5, caller, "Sync connect: UNUSABLE client");
                return null;
            }
            final CompletableFuture<IBackupTransport> transportFuture = new CompletableFuture<>();
            TransportConnectionListener requestListener = new TransportConnectionListener() { // from class: com.android.server.backup.transport.-$$Lambda$TransportClient$uc3fygwQjQIS_JT7mlt-yMBfJcE
                @Override // com.android.server.backup.transport.TransportConnectionListener
                public final void onTransportConnectionResult(IBackupTransport iBackupTransport, TransportClient transportClient) {
                    transportFuture.complete(iBackupTransport);
                }
            };
            long requestTime = SystemClock.elapsedRealtime();
            log(3, caller, "Sync connect: calling async");
            connectAsync(requestListener, caller);
            try {
                IBackupTransport transport2 = transportFuture.get();
                long time = SystemClock.elapsedRealtime() - requestTime;
                this.mTransportStats.registerConnectionTime(this.mTransportComponent, time);
                log(3, caller, String.format(Locale.US, "Connect took %d ms", Long.valueOf(time)));
                return transport2;
            } catch (InterruptedException | ExecutionException e) {
                String error = e.getClass().getSimpleName();
                log(6, caller, error + " while waiting for transport: " + e.getMessage());
                return null;
            }
        }
    }

    public IBackupTransport connectOrThrow(String caller) throws TransportNotAvailableException {
        IBackupTransport transport = connect(caller);
        if (transport == null) {
            log(6, caller, "Transport connection failed");
            throw new TransportNotAvailableException();
        }
        return transport;
    }

    public IBackupTransport getConnectedTransport(String caller) throws TransportNotAvailableException {
        IBackupTransport transport = this.mTransport;
        if (transport == null) {
            log(6, caller, "Transport not connected");
            throw new TransportNotAvailableException();
        }
        return transport;
    }

    public String toString() {
        return "TransportClient{" + this.mTransportComponent.flattenToShortString() + AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR + this.mIdentifier + "}";
    }

    protected void finalize() throws Throwable {
        synchronized (this.mStateLock) {
            this.mCloseGuard.warnIfOpen();
            if (this.mState >= 2) {
                log(6, "TransportClient.finalize()", "Dangling TransportClient created in [" + this.mCreatorLogString + "] being GC'ed. Left bound, unbinding...");
                try {
                    unbind("TransportClient.finalize()");
                } catch (IllegalStateException e) {
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onServiceConnected(IBinder binder) {
        IBackupTransport transport = IBackupTransport.Stub.asInterface(binder);
        synchronized (this.mStateLock) {
            checkStateIntegrityLocked();
            if (this.mState != 0) {
                log(3, "Transport connected");
                setStateLocked(3, transport);
                notifyListenersAndClearLocked(transport);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onServiceDisconnected() {
        synchronized (this.mStateLock) {
            log(6, "Service disconnected: client UNUSABLE");
            setStateLocked(0, null);
            try {
                this.mContext.unbindService(this.mConnection);
            } catch (IllegalArgumentException e) {
                log(5, "Exception trying to unbind onServiceDisconnected(): " + e.getMessage());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBindingDied() {
        synchronized (this.mStateLock) {
            checkStateIntegrityLocked();
            log(6, "Binding died: client UNUSABLE");
            switch (this.mState) {
                case 1:
                    log(6, "Unexpected state transition IDLE => UNUSABLE");
                    setStateLocked(0, null);
                    break;
                case 2:
                    setStateLocked(0, null);
                    this.mContext.unbindService(this.mConnection);
                    notifyListenersAndClearLocked(null);
                    break;
                case 3:
                    setStateLocked(0, null);
                    this.mContext.unbindService(this.mConnection);
                    break;
            }
        }
    }

    private void notifyListener(final TransportConnectionListener listener, final IBackupTransport transport, String caller) {
        String transportString = transport != null ? "IBackupTransport" : "null";
        log(4, "Notifying [" + caller + "] transport = " + transportString);
        this.mListenerHandler.post(new Runnable() { // from class: com.android.server.backup.transport.-$$Lambda$TransportClient$ciIUj0x0CRg93UETUpy2FB5aqCQ
            @Override // java.lang.Runnable
            public final void run() {
                listener.onTransportConnectionResult(transport, TransportClient.this);
            }
        });
    }

    @GuardedBy("mStateLock")
    private void notifyListenersAndClearLocked(IBackupTransport transport) {
        for (Map.Entry<TransportConnectionListener, String> entry : this.mListeners.entrySet()) {
            TransportConnectionListener listener = entry.getKey();
            String caller = entry.getValue();
            notifyListener(listener, transport, caller);
        }
        this.mListeners.clear();
    }

    @GuardedBy("mStateLock")
    private void setStateLocked(int state, IBackupTransport transport) {
        log(2, "State: " + stateToString(this.mState) + " => " + stateToString(state));
        onStateTransition(this.mState, state);
        this.mState = state;
        this.mTransport = transport;
    }

    private void onStateTransition(int oldState, int newState) {
        String transport = this.mTransportComponent.flattenToShortString();
        int bound = transitionThroughState(oldState, newState, 2);
        int connected = transitionThroughState(oldState, newState, 3);
        if (bound != 0) {
            int value = bound == 1 ? 1 : 0;
            EventLog.writeEvent((int) EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, transport, Integer.valueOf(value));
        }
        if (connected != 0) {
            int value2 = connected == 1 ? 1 : 0;
            EventLog.writeEvent((int) EventLogTags.BACKUP_TRANSPORT_CONNECTION, transport, Integer.valueOf(value2));
        }
    }

    private int transitionThroughState(int oldState, int newState, int stateReference) {
        if (oldState < stateReference && stateReference <= newState) {
            return 1;
        }
        if (oldState >= stateReference && stateReference > newState) {
            return -1;
        }
        return 0;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    @GuardedBy("mStateLock")
    private void checkStateIntegrityLocked() {
        switch (this.mState) {
            case 0:
                checkState(this.mListeners.isEmpty(), "Unexpected listeners when state = UNUSABLE");
                checkState(this.mTransport == null, "Transport expected to be null when state = UNUSABLE");
                break;
            case 1:
                break;
            case 2:
                checkState(this.mTransport == null, "Transport expected to be null when state = BOUND_AND_CONNECTING");
                return;
            case 3:
                checkState(this.mListeners.isEmpty(), "Unexpected listeners when state = CONNECTED");
                checkState(this.mTransport != null, "Transport expected to be non-null when state = CONNECTED");
                return;
            default:
                checkState(false, "Unexpected state = " + stateToString(this.mState));
                return;
        }
        checkState(this.mListeners.isEmpty(), "Unexpected listeners when state = IDLE");
        checkState(this.mTransport == null, "Transport expected to be null when state = IDLE");
    }

    private void checkState(boolean assertion, String message) {
        if (!assertion) {
            log(6, message);
        }
    }

    private String stateToString(int state) {
        switch (state) {
            case 0:
                return "UNUSABLE";
            case 1:
                return "IDLE";
            case 2:
                return "BOUND_AND_CONNECTING";
            case 3:
                return "CONNECTED";
            default:
                return "<UNKNOWN = " + state + ">";
        }
    }

    private void log(int priority, String message) {
        TransportUtils.log(priority, TAG, TransportUtils.formatMessage(this.mPrefixForLog, null, message));
        saveLogEntry(TransportUtils.formatMessage(null, null, message));
    }

    private void log(int priority, String caller, String message) {
        TransportUtils.log(priority, TAG, TransportUtils.formatMessage(this.mPrefixForLog, caller, message));
        saveLogEntry(TransportUtils.formatMessage(null, caller, message));
    }

    private void saveLogEntry(String message) {
        CharSequence time = DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis());
        String message2 = ((Object) time) + " " + message;
        synchronized (this.mLogBufferLock) {
            if (this.mLogBuffer.size() == 5) {
                this.mLogBuffer.remove(this.mLogBuffer.size() - 1);
            }
            this.mLogBuffer.add(0, message2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<String> getLogBuffer() {
        List<String> unmodifiableList;
        synchronized (this.mLogBufferLock) {
            unmodifiableList = Collections.unmodifiableList(this.mLogBuffer);
        }
        return unmodifiableList;
    }

    /* loaded from: classes.dex */
    private static class TransportConnection implements ServiceConnection {
        private final Context mContext;
        private final WeakReference<TransportClient> mTransportClientRef;

        private TransportConnection(Context context, TransportClient transportClient) {
            this.mContext = context;
            this.mTransportClientRef = new WeakReference<>(transportClient);
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName transportComponent, IBinder binder) {
            TransportClient transportClient = this.mTransportClientRef.get();
            if (transportClient != null) {
                transportClient.onServiceConnected(binder);
            } else {
                referenceLost("TransportConnection.onServiceConnected()");
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName transportComponent) {
            TransportClient transportClient = this.mTransportClientRef.get();
            if (transportClient != null) {
                transportClient.onServiceDisconnected();
            } else {
                referenceLost("TransportConnection.onServiceDisconnected()");
            }
        }

        @Override // android.content.ServiceConnection
        public void onBindingDied(ComponentName transportComponent) {
            TransportClient transportClient = this.mTransportClientRef.get();
            if (transportClient != null) {
                transportClient.onBindingDied();
            } else {
                referenceLost("TransportConnection.onBindingDied()");
            }
        }

        private void referenceLost(String caller) {
            this.mContext.unbindService(this);
            TransportUtils.log(4, TransportClient.TAG, caller + " called but TransportClient reference has been GC'ed");
        }
    }
}
