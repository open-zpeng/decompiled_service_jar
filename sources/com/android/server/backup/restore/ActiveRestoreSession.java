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
import android.util.Slog;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.params.RestoreGetSetsParams;
import com.android.server.backup.params.RestoreParams;
import com.android.server.backup.transport.TransportClient;
import java.util.function.BiFunction;

/* loaded from: classes.dex */
public class ActiveRestoreSession extends IRestoreSession.Stub {
    private static final String TAG = "RestoreSession";
    private final UserBackupManagerService mBackupManagerService;
    private final String mPackageName;
    private final TransportManager mTransportManager;
    private final String mTransportName;
    private final int mUserId;
    public RestoreSet[] mRestoreSets = null;
    boolean mEnded = false;
    boolean mTimedOut = false;

    public ActiveRestoreSession(UserBackupManagerService backupManagerService, String packageName, String transportName) {
        this.mBackupManagerService = backupManagerService;
        this.mPackageName = packageName;
        this.mTransportManager = backupManagerService.getTransportManager();
        this.mTransportName = transportName;
        this.mUserId = backupManagerService.getUserId();
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
                final UserBackupManagerService.BackupWakeLock wakelock = this.mBackupManagerService.getWakelock();
                wakelock.acquire();
                final TransportManager transportManager = this.mTransportManager;
                OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.restore.-$$Lambda$ActiveRestoreSession$sCvtVwpXah9lCpJqxZ9YbNMLXas
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
    public static /* synthetic */ void lambda$getAvailableRestoreSets$0(TransportManager transportManager, TransportClient transportClient, UserBackupManagerService.BackupWakeLock wakelock, String caller) {
        transportManager.disposeOfTransportClient(transportClient, caller);
        wakelock.release();
    }

    /* JADX WARN: Can't wrap try/catch for region: R(3:(3:32|33|(7:36|37|38|39|40|41|42)(1:35))|29|30) */
    /* JADX WARN: Code restructure failed: missing block: B:48:0x00df, code lost:
        r1 = th;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public synchronized int restoreAll(final long r7, final android.app.backup.IRestoreObserver r9, final android.app.backup.IBackupManagerMonitor r10) {
        /*
            Method dump skipped, instructions count: 237
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.restore.ActiveRestoreSession.restoreAll(long, android.app.backup.IRestoreObserver, android.app.backup.IBackupManagerMonitor):int");
    }

    /* JADX WARN: Can't wrap try/catch for region: R(3:(3:42|43|(8:46|47|48|49|50|51|52|53)(1:45))|39|40) */
    /* JADX WARN: Code restructure failed: missing block: B:69:0x0157, code lost:
        r0 = th;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public synchronized int restorePackages(final long r14, final android.app.backup.IRestoreObserver r16, final java.lang.String[] r17, final android.app.backup.IBackupManagerMonitor r18) {
        /*
            Method dump skipped, instructions count: 357
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.restore.ActiveRestoreSession.restorePackages(long, android.app.backup.IRestoreObserver, java.lang.String[], android.app.backup.IBackupManagerMonitor):int");
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
                final PackageInfo app = this.mBackupManagerService.getPackageManager().getPackageInfoAsUser(packageName, 0, this.mUserId);
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
        final UserBackupManagerService.BackupWakeLock wakelock = this.mBackupManagerService.getWakelock();
        wakelock.acquire();
        final TransportManager transportManager = this.mTransportManager;
        OnTaskFinishedListener listener = new OnTaskFinishedListener() { // from class: com.android.server.backup.restore.-$$Lambda$ActiveRestoreSession$71PrH3wEYYMIUjX_IpwtAdchLA8
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
    public static /* synthetic */ void lambda$sendRestoreToHandlerLocked$4(TransportManager transportManager, TransportClient transportClient, UserBackupManagerService.BackupWakeLock wakelock, String caller) {
        transportManager.disposeOfTransportClient(transportClient, caller);
        wakelock.release();
    }

    /* loaded from: classes.dex */
    public class EndRestoreRunnable implements Runnable {
        UserBackupManagerService mBackupManager;
        ActiveRestoreSession mSession;

        public EndRestoreRunnable(UserBackupManagerService manager, ActiveRestoreSession session) {
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
