package com.android.server.backup.restore;

import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.RestoreSet;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Slog;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.TransportManager;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.params.RestoreGetSetsParams;
import com.android.server.backup.params.RestoreParams;
import com.android.server.backup.transport.TransportClient;
import java.util.function.BiFunction;
/* loaded from: classes.dex */
public class ActiveRestoreSession extends IRestoreSession.Stub {
    private static final String TAG = "RestoreSession";
    private final BackupManagerService mBackupManagerService;
    private final String mPackageName;
    private final TransportManager mTransportManager;
    private final String mTransportName;
    public RestoreSet[] mRestoreSets = null;
    boolean mEnded = false;
    boolean mTimedOut = false;

    public ActiveRestoreSession(BackupManagerService backupManagerService, String packageName, String transportName) {
        this.mBackupManagerService = backupManagerService;
        this.mPackageName = packageName;
        this.mTransportManager = backupManagerService.getTransportManager();
        this.mTransportName = transportName;
    }

    public void markTimedOut() {
        this.mTimedOut = true;
    }

    public synchronized int getAvailableRestoreSets(IRestoreObserver observer, IBackupManagerMonitor monitor) {
        this.mBackupManagerService.getContext().enforceCallingOrSelfPermission("android.permission.BACKUP", "getAvailableRestoreSets");
        if (observer != null) {
            if (this.mEnded) {
                throw new IllegalStateException("Restore session already ended");
            }
            if (this.mTimedOut) {
                Slog.i(TAG, "Session already timed out");
                return -1;
            }
            long oldId = Binder.clearCallingIdentity();
            try {
                final TransportClient transportClient = this.mTransportManager.getTransportClient(this.mTransportName, "RestoreSession.getAvailableRestoreSets()");
                if (transportClient == null) {
                    Slog.w(TAG, "Null transport client getting restore sets");
                    Binder.restoreCallingIdentity(oldId);
                    return -1;
                }
                this.mBackupManagerService.getBackupHandler().removeMessages(8);
                final PowerManager.WakeLock wakelock = this.mBackupManagerService.getWakelock();
                wakelock.acquire();
                final TransportManager transportManager = this.mTransportManager;
                OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.restore.-$$Lambda$ActiveRestoreSession$0wzV_GqtA0thM1WxLthNBKD3Ygw
                    @Override // com.android.server.backup.internal.OnTaskFinishedListener
                    public final void onFinished(String str) {
                        ActiveRestoreSession.lambda$getAvailableRestoreSets$0(TransportManager.this, transportClient, wakelock, str);
                    }
                };
                Message msg = this.mBackupManagerService.getBackupHandler().obtainMessage(6, new RestoreGetSetsParams(transportClient, this, observer, monitor, listener));
                this.mBackupManagerService.getBackupHandler().sendMessage(msg);
                Binder.restoreCallingIdentity(oldId);
                return 0;
            } catch (Exception e) {
                Slog.e(TAG, "Error in getAvailableRestoreSets", e);
                Binder.restoreCallingIdentity(oldId);
                return -1;
            }
        }
        throw new IllegalArgumentException("Observer must not be null");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getAvailableRestoreSets$0(TransportManager transportManager, TransportClient transportClient, PowerManager.WakeLock wakelock, String caller) {
        transportManager.disposeOfTransportClient(transportClient, caller);
        wakelock.release();
    }

    public synchronized int restoreAll(final long token, final IRestoreObserver observer, final IBackupManagerMonitor monitor) {
        this.mBackupManagerService.getContext().enforceCallingOrSelfPermission("android.permission.BACKUP", "performRestore");
        Slog.d(TAG, "restoreAll token=" + Long.toHexString(token) + " observer=" + observer);
        if (this.mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }
        if (this.mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return -1;
        } else if (this.mRestoreSets == null) {
            Slog.e(TAG, "Ignoring restoreAll() with no restore set");
            return -1;
        } else if (this.mPackageName != null) {
            Slog.e(TAG, "Ignoring restoreAll() on single-package session");
            return -1;
        } else if (!this.mTransportManager.isTransportRegistered(this.mTransportName)) {
            Slog.e(TAG, "Transport " + this.mTransportName + " not registered");
            return -1;
        } else {
            synchronized (this.mBackupManagerService.getQueueLock()) {
                for (int i = 0; i < this.mRestoreSets.length; i++) {
                    if (token == this.mRestoreSets[i].token) {
                        long oldId = Binder.clearCallingIdentity();
                        try {
                            return sendRestoreToHandlerLocked(new BiFunction() { // from class: com.android.server.backup.restore.-$$Lambda$ActiveRestoreSession$iPMdVI7x_J8xmayWzH6Euhd5674
                                @Override // java.util.function.BiFunction
                                public final Object apply(Object obj, Object obj2) {
                                    RestoreParams createForRestoreAll;
                                    createForRestoreAll = RestoreParams.createForRestoreAll((TransportClient) obj, observer, monitor, token, (OnTaskFinishedListener) obj2);
                                    return createForRestoreAll;
                                }
                            }, "RestoreSession.restoreAll()");
                        } finally {
                            Binder.restoreCallingIdentity(oldId);
                        }
                    }
                }
                Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
                return -1;
            }
        }
    }

    public synchronized int restoreSome(final long token, final IRestoreObserver observer, final IBackupManagerMonitor monitor, final String[] packages) {
        this.mBackupManagerService.getContext().enforceCallingOrSelfPermission("android.permission.BACKUP", "performRestore");
        StringBuilder b = new StringBuilder(128);
        b.append("restoreSome token=");
        b.append(Long.toHexString(token));
        b.append(" observer=");
        b.append(observer.toString());
        b.append(" monitor=");
        if (monitor == null) {
            b.append("null");
        } else {
            b.append(monitor.toString());
        }
        b.append(" packages=");
        int i = 0;
        if (packages == null) {
            b.append("null");
        } else {
            b.append('{');
            boolean first = true;
            for (String s : packages) {
                if (first) {
                    first = false;
                } else {
                    b.append(", ");
                }
                b.append(s);
            }
            b.append('}');
        }
        Slog.d(TAG, b.toString());
        if (this.mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }
        if (this.mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return -1;
        } else if (this.mRestoreSets == null) {
            Slog.e(TAG, "Ignoring restoreAll() with no restore set");
            return -1;
        } else if (this.mPackageName != null) {
            Slog.e(TAG, "Ignoring restoreAll() on single-package session");
            return -1;
        } else if (!this.mTransportManager.isTransportRegistered(this.mTransportName)) {
            Slog.e(TAG, "Transport " + this.mTransportName + " not registered");
            return -1;
        } else {
            synchronized (this.mBackupManagerService.getQueueLock()) {
                while (true) {
                    int i2 = i;
                    if (i2 >= this.mRestoreSets.length) {
                        Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
                        return -1;
                    } else if (token == this.mRestoreSets[i2].token) {
                        long oldId = Binder.clearCallingIdentity();
                        try {
                            return sendRestoreToHandlerLocked(new BiFunction() { // from class: com.android.server.backup.restore.-$$Lambda$ActiveRestoreSession$amDGbcwA180LGcZKUosvhspMk2E
                                @Override // java.util.function.BiFunction
                                public final Object apply(Object obj, Object obj2) {
                                    RestoreParams createForRestoreSome;
                                    createForRestoreSome = RestoreParams.createForRestoreSome((TransportClient) obj, observer, monitor, token, packages, packages.length > 1, (OnTaskFinishedListener) obj2);
                                    return createForRestoreSome;
                                }
                            }, "RestoreSession.restoreSome(" + packages.length + " packages)");
                        } finally {
                            Binder.restoreCallingIdentity(oldId);
                        }
                    } else {
                        i = i2 + 1;
                    }
                }
            }
        }
    }

    public synchronized int restorePackage(String packageName, final IRestoreObserver observer, final IBackupManagerMonitor monitor) {
        Slog.v(TAG, "restorePackage pkg=" + packageName + " obs=" + observer + "monitor=" + monitor);
        if (this.mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }
        if (this.mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return -1;
        } else if (this.mPackageName != null && !this.mPackageName.equals(packageName)) {
            Slog.e(TAG, "Ignoring attempt to restore pkg=" + packageName + " on session for package " + this.mPackageName);
            return -1;
        } else {
            try {
                final PackageInfo app = this.mBackupManagerService.getPackageManager().getPackageInfo(packageName, 0);
                int perm = this.mBackupManagerService.getContext().checkPermission("android.permission.BACKUP", Binder.getCallingPid(), Binder.getCallingUid());
                if (perm == -1 && app.applicationInfo.uid != Binder.getCallingUid()) {
                    Slog.w(TAG, "restorePackage: bad packageName=" + packageName + " or calling uid=" + Binder.getCallingUid());
                    throw new SecurityException("No permission to restore other packages");
                }
                if (!this.mTransportManager.isTransportRegistered(this.mTransportName)) {
                    Slog.e(TAG, "Transport " + this.mTransportName + " not registered");
                    return -1;
                }
                long oldId = Binder.clearCallingIdentity();
                final long token = this.mBackupManagerService.getAvailableRestoreToken(packageName);
                Slog.v(TAG, "restorePackage pkg=" + packageName + " token=" + Long.toHexString(token));
                if (token == 0) {
                    Slog.w(TAG, "No data available for this package; not restoring");
                    Binder.restoreCallingIdentity(oldId);
                    return -1;
                }
                BiFunction<TransportClient, OnTaskFinishedListener, RestoreParams> biFunction = new BiFunction() { // from class: com.android.server.backup.restore.-$$Lambda$ActiveRestoreSession$tb1mCMujBEuhHsxQ6tX_mYJVCII
                    @Override // java.util.function.BiFunction
                    public final Object apply(Object obj, Object obj2) {
                        RestoreParams createForSinglePackage;
                        createForSinglePackage = RestoreParams.createForSinglePackage((TransportClient) obj, observer, monitor, token, app, (OnTaskFinishedListener) obj2);
                        return createForSinglePackage;
                    }
                };
                int sendRestoreToHandlerLocked = sendRestoreToHandlerLocked(biFunction, "RestoreSession.restorePackage(" + packageName + ")");
                Binder.restoreCallingIdentity(oldId);
                return sendRestoreToHandlerLocked;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Asked to restore nonexistent pkg " + packageName);
                return -1;
            }
        }
    }

    public void setRestoreSets(RestoreSet[] restoreSets) {
        this.mRestoreSets = restoreSets;
    }

    private int sendRestoreToHandlerLocked(BiFunction<TransportClient, OnTaskFinishedListener, RestoreParams> restoreParamsBuilder, String callerLogString) {
        final TransportClient transportClient = this.mTransportManager.getTransportClient(this.mTransportName, callerLogString);
        if (transportClient == null) {
            Slog.e(TAG, "Transport " + this.mTransportName + " got unregistered");
            return -1;
        }
        Handler backupHandler = this.mBackupManagerService.getBackupHandler();
        backupHandler.removeMessages(8);
        final PowerManager.WakeLock wakelock = this.mBackupManagerService.getWakelock();
        wakelock.acquire();
        final TransportManager transportManager = this.mTransportManager;
        OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.restore.-$$Lambda$ActiveRestoreSession$0QlkHke0fYNRb0nGuyNs6WmyPDM
            @Override // com.android.server.backup.internal.OnTaskFinishedListener
            public final void onFinished(String str) {
                ActiveRestoreSession.lambda$sendRestoreToHandlerLocked$4(TransportManager.this, transportClient, wakelock, str);
            }
        };
        Message msg = backupHandler.obtainMessage(3);
        msg.obj = restoreParamsBuilder.apply(transportClient, listener);
        backupHandler.sendMessage(msg);
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$sendRestoreToHandlerLocked$4(TransportManager transportManager, TransportClient transportClient, PowerManager.WakeLock wakelock, String caller) {
        transportManager.disposeOfTransportClient(transportClient, caller);
        wakelock.release();
    }

    /* loaded from: classes.dex */
    public class EndRestoreRunnable implements Runnable {
        BackupManagerService mBackupManager;
        ActiveRestoreSession mSession;

        public EndRestoreRunnable(BackupManagerService manager, ActiveRestoreSession session) {
            this.mBackupManager = manager;
            this.mSession = session;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (this.mSession) {
                this.mSession.mEnded = true;
            }
            this.mBackupManager.clearRestoreSession(this.mSession);
        }
    }

    public synchronized void endRestoreSession() {
        Slog.d(TAG, "endRestoreSession");
        if (this.mTimedOut) {
            Slog.i(TAG, "Session already timed out");
        } else if (this.mEnded) {
            throw new IllegalStateException("Restore session already ended");
        } else {
            this.mBackupManagerService.getBackupHandler().post(new EndRestoreRunnable(this.mBackupManagerService, this));
        }
    }
}
