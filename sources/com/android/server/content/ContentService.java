package com.android.server.content;

import android.accounts.Account;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentService;
import android.content.ISyncStatusObserver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PeriodicSync;
import android.content.SyncAdapterType;
import android.content.SyncInfo;
import android.content.SyncRequest;
import android.content.SyncStatusInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ProviderInfo;
import android.database.IContentObserver;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.FactoryTest;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.WindowManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BinderDeathDispatcher;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.content.SyncStorageEngine;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.xiaopeng.view.SharedDisplayListener;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/* loaded from: classes.dex */
public final class ContentService extends IContentService.Stub {
    static final boolean DEBUG = false;
    static final String TAG = "ContentService";
    private static final int TOO_MANY_OBSERVERS_THRESHOLD = 1000;
    private static final BinderDeathDispatcher<IContentObserver> sObserverDeathDispatcher = new BinderDeathDispatcher<>();
    @GuardedBy({"sObserverLeakDetectedUid"})
    private static final ArraySet<Integer> sObserverLeakDetectedUid = new ArraySet<>(0);
    private Context mContext;
    private boolean mFactoryTest;
    private final ObserverNode mRootNode = new ObserverNode("");
    private SyncManager mSyncManager = null;
    private final Object mSyncManagerLock = new Object();
    @GuardedBy({"mCache"})
    private final SparseArray<ArrayMap<String, ArrayMap<Pair<String, Uri>, Bundle>>> mCache = new SparseArray<>();
    private BroadcastReceiver mCacheReceiver = new BroadcastReceiver() { // from class: com.android.server.content.ContentService.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            synchronized (ContentService.this.mCache) {
                if ("android.intent.action.LOCALE_CHANGED".equals(intent.getAction())) {
                    ContentService.this.mCache.clear();
                } else if ("android.intent.action.BOOT_COMPLETED".equals(intent.getAction())) {
                    ContentService.this.listenerScreenFlow(context);
                } else {
                    Uri data = intent.getData();
                    if (data != null) {
                        int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                        String packageName = data.getSchemeSpecificPart();
                        ContentService.this.invalidateCacheLocked(userId, packageName, null);
                    }
                }
            }
        }
    };

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        private ContentService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            boolean factoryTest = FactoryTest.getMode() == 1;
            this.mService = new ContentService(getContext(), factoryTest);
            publishBinderService(ActivityTaskManagerInternal.ASSIST_KEY_CONTENT, this.mService);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            this.mService.onBootPhase(phase);
        }

        @Override // com.android.server.SystemService
        public void onStartUser(int userHandle) {
            this.mService.onStartUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            this.mService.onUnlockUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userHandle) {
            this.mService.onStopUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onCleanupUser(int userHandle) {
            synchronized (this.mService.mCache) {
                this.mService.mCache.remove(userHandle);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SharedDisplayListenerImpl extends SharedDisplayListener {
        private SharedDisplayListenerImpl() {
        }

        public void onChanged(String s, int i) throws RemoteException {
            Slog.d(ContentService.TAG, "onChanged packageName=" + s + " sharedId=" + i);
            ContentService.this.mRootNode.notifyScreenBrightnessObservers();
        }
    }

    private static String getPackageName(int pid) {
        try {
            IBinder b = ServiceManager.getService("activity");
            IActivityManager am = IActivityManager.Stub.asInterface(b);
            List<ActivityManager.RunningAppProcessInfo> l = am.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo info : l) {
                try {
                    if (info.pid == pid && info.pkgList != null && info.pkgList.length > 0) {
                        String packageName = info.pkgList[0];
                        return packageName;
                    }
                } catch (Exception e) {
                }
            }
            return "";
        } catch (RemoteException e2) {
            return "";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void listenerScreenFlow(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService("window");
        wm.getSharedPackages();
        int pid = Binder.getCallingPid();
        String callingApp = getPackageName(pid);
        int sharedId = wm.getSharedId(callingApp);
        WindowManager.isPrimaryId(sharedId);
        wm.registerSharedListener(new SharedDisplayListenerImpl());
    }

    private SyncManager getSyncManager() {
        SyncManager syncManager;
        synchronized (this.mSyncManagerLock) {
            try {
                if (this.mSyncManager == null) {
                    this.mSyncManager = new SyncManager(this.mContext, this.mFactoryTest);
                }
            } catch (SQLiteException e) {
                Log.e(TAG, "Can't create SyncManager", e);
            }
            syncManager = this.mSyncManager;
        }
        return syncManager;
    }

    void onStartUser(int userHandle) {
        SyncManager syncManager = this.mSyncManager;
        if (syncManager != null) {
            syncManager.onStartUser(userHandle);
        }
    }

    void onUnlockUser(int userHandle) {
        SyncManager syncManager = this.mSyncManager;
        if (syncManager != null) {
            syncManager.onUnlockUser(userHandle);
        }
    }

    void onStopUser(int userHandle) {
        SyncManager syncManager = this.mSyncManager;
        if (syncManager != null) {
            syncManager.onStopUser(userHandle);
        }
    }

    protected synchronized void dump(FileDescriptor fd, PrintWriter pw_, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(this.mContext, TAG, pw_)) {
            return;
        }
        PrintWriter indentingPrintWriter = new IndentingPrintWriter(pw_, "  ");
        boolean dumpAll = ArrayUtils.contains(args, "-a");
        long identityToken = clearCallingIdentity();
        if (this.mSyncManager == null) {
            indentingPrintWriter.println("SyncManager not available yet");
        } else {
            this.mSyncManager.dump(fd, indentingPrintWriter, dumpAll);
        }
        indentingPrintWriter.println();
        indentingPrintWriter.println("Observer tree:");
        synchronized (this.mRootNode) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                int[] counts = new int[2];
                SparseIntArray pidCounts = new SparseIntArray();
                this.mRootNode.dumpLocked(fd, indentingPrintWriter, args, "", "  ", counts, pidCounts);
                indentingPrintWriter.println();
                ArrayList<Integer> sorted = new ArrayList<>();
                int i = 0;
                while (i < pidCounts.size()) {
                    SparseIntArray pidCounts2 = pidCounts;
                    sorted.add(Integer.valueOf(pidCounts2.keyAt(i)));
                    i++;
                    pidCounts = pidCounts2;
                }
                final SparseIntArray pidCounts3 = pidCounts;
                Collections.sort(sorted, new Comparator<Integer>() { // from class: com.android.server.content.ContentService.2
                    @Override // java.util.Comparator
                    public int compare(Integer lhs, Integer rhs) {
                        int lc = pidCounts3.get(lhs.intValue());
                        int rc = pidCounts3.get(rhs.intValue());
                        if (lc < rc) {
                            return 1;
                        }
                        if (lc > rc) {
                            return -1;
                        }
                        return 0;
                    }
                });
                for (int i2 = 0; i2 < sorted.size(); i2++) {
                    int pid = sorted.get(i2).intValue();
                    indentingPrintWriter.print("  pid ");
                    indentingPrintWriter.print(pid);
                    indentingPrintWriter.print(": ");
                    indentingPrintWriter.print(pidCounts3.get(pid));
                    indentingPrintWriter.println(" observers");
                }
                indentingPrintWriter.println();
                indentingPrintWriter.print(" Total number of nodes: ");
                indentingPrintWriter.println(counts[0]);
                indentingPrintWriter.print(" Total number of observers: ");
                indentingPrintWriter.println(counts[1]);
                sObserverDeathDispatcher.dump(indentingPrintWriter, " ");
                synchronized (sObserverLeakDetectedUid) {
                    indentingPrintWriter.println();
                    indentingPrintWriter.print("Observer leaking UIDs: ");
                    indentingPrintWriter.println(sObserverLeakDetectedUid.toString());
                }
                try {
                    synchronized (this.mCache) {
                        try {
                            indentingPrintWriter.println();
                            indentingPrintWriter.println("Cached content:");
                            indentingPrintWriter.increaseIndent();
                            for (int i3 = 0; i3 < this.mCache.size(); i3++) {
                                indentingPrintWriter.println("User " + this.mCache.keyAt(i3) + ":");
                                indentingPrintWriter.increaseIndent();
                                indentingPrintWriter.println(this.mCache.valueAt(i3));
                                indentingPrintWriter.decreaseIndent();
                            }
                            indentingPrintWriter.decreaseIndent();
                            restoreCallingIdentity(identityToken);
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            } catch (Throwable th4) {
                th = th4;
                throw th;
            }
        }
    }

    ContentService(Context context, boolean factoryTest) {
        this.mContext = context;
        this.mFactoryTest = factoryTest;
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.setSyncAdapterPackagesprovider(new PackageManagerInternal.SyncAdapterPackagesProvider() { // from class: com.android.server.content.ContentService.3
            public String[] getPackages(String authority, int userId) {
                return ContentService.this.getSyncAdapterPackagesForAuthorityAsUser(authority, userId);
            }
        });
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
        packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
        packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        packageFilter.addAction("android.intent.action.PACKAGE_DATA_CLEARED");
        packageFilter.addDataScheme("package");
        this.mContext.registerReceiverAsUser(this.mCacheReceiver, UserHandle.ALL, packageFilter, null, null);
        IntentFilter localeFilter = new IntentFilter();
        localeFilter.addAction("android.intent.action.LOCALE_CHANGED");
        this.mContext.registerReceiverAsUser(this.mCacheReceiver, UserHandle.ALL, localeFilter, null, null);
        IntentFilter bootFilter = new IntentFilter();
        bootFilter.addAction("android.intent.action.BOOT_COMPLETED");
        this.mContext.registerReceiverAsUser(this.mCacheReceiver, UserHandle.ALL, bootFilter, null, null);
    }

    void onBootPhase(int phase) {
        if (phase == 550) {
            getSyncManager();
        }
        SyncManager syncManager = this.mSyncManager;
        if (syncManager != null) {
            syncManager.onBootPhase(phase);
        }
    }

    public void registerContentObserver(Uri uri, boolean notifyForDescendants, IContentObserver observer, int userHandle, int targetSdkVersion) {
        if (observer == null || uri == null) {
            throw new IllegalArgumentException("You must pass a valid uri and observer");
        }
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        int userHandle2 = handleIncomingUser(uri, pid, uid, 1, true, userHandle);
        String msg = ((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).checkContentProviderAccess(uri.getAuthority(), userHandle2);
        if (msg != null) {
            if (targetSdkVersion >= 26) {
                throw new SecurityException(msg);
            }
            if (!msg.startsWith("Failed to find provider")) {
                Log.w(TAG, "Ignoring content changes for " + uri + " from " + uid + ": " + msg);
                return;
            }
        }
        synchronized (this.mRootNode) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                this.mRootNode.addObserverLocked(uri, observer, notifyForDescendants, this.mRootNode, uid, pid, userHandle2);
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    public void registerContentObserver(Uri uri, boolean notifyForDescendants, IContentObserver observer) {
        registerContentObserver(uri, notifyForDescendants, observer, UserHandle.getCallingUserId(), 10000);
    }

    public void unregisterContentObserver(IContentObserver observer) {
        if (observer == null) {
            throw new IllegalArgumentException("You must pass a valid observer");
        }
        synchronized (this.mRootNode) {
            this.mRootNode.removeObserverLocked(observer);
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:125:? -> B:63:0x012b). Please submit an issue!!! */
    public void notifyChange(Uri uri, IContentObserver observer, boolean observerWantsSelfNotifications, int flags, int userHandle, int targetSdkVersion, String callingPackage) {
        Uri uri2;
        Uri uri3;
        String msg;
        ArrayList<ObserverCall> calls;
        ObserverNode.ObserverEntry oe;
        ArrayList<ObserverCall> calls2;
        if (uri == null) {
            throw new NullPointerException("Uri must not be null");
        }
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        int callingUserHandle = UserHandle.getCallingUserId();
        int userHandle2 = handleIncomingUser(uri, callingPid, callingUid, 2, true, userHandle);
        String msg2 = ((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).checkContentProviderAccess(uri.getAuthority(), userHandle2);
        if (msg2 != null) {
            if (targetSdkVersion >= 26) {
                throw new SecurityException(msg2);
            }
            if (!msg2.startsWith("Failed to find provider")) {
                Log.w(TAG, "Ignoring notify for " + uri + " from " + callingUid + ": " + msg2);
                return;
            }
        }
        long identityToken = clearCallingIdentity();
        try {
            ArrayList<ObserverCall> calls3 = new ArrayList<>();
            try {
                synchronized (this.mRootNode) {
                    try {
                    } catch (Throwable th) {
                        th = th;
                    }
                    try {
                        this.mRootNode.collectObserversLocked(uri, 0, observer, observerWantsSelfNotifications, flags, userHandle2, calls3);
                        try {
                            int numCalls = calls3.size();
                            int i = 0;
                            while (i < numCalls) {
                                try {
                                    ObserverCall oc = calls3.get(i);
                                    try {
                                        uri3 = uri;
                                        try {
                                            oc.mObserver.onChange(oc.mSelfChange, uri3, userHandle2);
                                            msg = msg2;
                                            calls = calls3;
                                        } catch (RemoteException e) {
                                            e = e;
                                            try {
                                                synchronized (this.mRootNode) {
                                                    try {
                                                        Log.w(TAG, "Found dead observer, removing");
                                                        IBinder binder = oc.mObserver.asBinder();
                                                        ArrayList<ObserverNode.ObserverEntry> list = oc.mNode.mObservers;
                                                        int numList = list.size();
                                                        msg = msg2;
                                                        int numList2 = numList;
                                                        int j = 0;
                                                        while (j < numList2) {
                                                            try {
                                                                oe = list.get(j);
                                                                calls2 = calls3;
                                                            } catch (Throwable th2) {
                                                                th = th2;
                                                                try {
                                                                    throw th;
                                                                } catch (Throwable th3) {
                                                                    th = th3;
                                                                    restoreCallingIdentity(identityToken);
                                                                    throw th;
                                                                }
                                                            }
                                                            try {
                                                                if (oe.observer.asBinder() == binder) {
                                                                    list.remove(j);
                                                                    j--;
                                                                    numList2--;
                                                                }
                                                                j++;
                                                                calls3 = calls2;
                                                            } catch (Throwable th4) {
                                                                th = th4;
                                                                throw th;
                                                            }
                                                        }
                                                        calls = calls3;
                                                    } catch (Throwable th5) {
                                                        th = th5;
                                                    }
                                                }
                                                i++;
                                                msg2 = msg;
                                                calls3 = calls;
                                            } catch (Throwable th6) {
                                                th = th6;
                                                restoreCallingIdentity(identityToken);
                                                throw th;
                                            }
                                        } catch (Throwable th7) {
                                            th = th7;
                                            restoreCallingIdentity(identityToken);
                                            throw th;
                                        }
                                    } catch (RemoteException e2) {
                                        e = e2;
                                        uri3 = uri;
                                    } catch (Throwable th8) {
                                        th = th8;
                                    }
                                    i++;
                                    msg2 = msg;
                                    calls3 = calls;
                                } catch (Throwable th9) {
                                    th = th9;
                                }
                            }
                            if ((flags & 1) != 0) {
                                try {
                                    SyncManager syncManager = getSyncManager();
                                    if (syncManager != null) {
                                        try {
                                            uri2 = uri;
                                            syncManager.scheduleLocalSync(null, callingUserHandle, callingUid, uri.getAuthority(), getSyncExemptionForCaller(callingUid), callingUid, callingPid, callingPackage);
                                        } catch (Throwable th10) {
                                            th = th10;
                                            restoreCallingIdentity(identityToken);
                                            throw th;
                                        }
                                    } else {
                                        uri2 = uri;
                                    }
                                } catch (Throwable th11) {
                                    th = th11;
                                }
                            } else {
                                uri2 = uri;
                            }
                            synchronized (this.mCache) {
                                String providerPackageName = getProviderPackageName(uri);
                                invalidateCacheLocked(userHandle2, providerPackageName, uri2);
                            }
                            restoreCallingIdentity(identityToken);
                        } catch (Throwable th12) {
                            th = th12;
                        }
                    } catch (Throwable th13) {
                        th = th13;
                        while (true) {
                            try {
                                break;
                            } catch (Throwable th14) {
                                th = th14;
                            }
                        }
                        throw th;
                    }
                }
            } catch (Throwable th15) {
                th = th15;
            }
        } catch (Throwable th16) {
            th = th16;
        }
    }

    private int checkUriPermission(Uri uri, int pid, int uid, int modeFlags, int userHandle) {
        try {
            return ActivityManager.getService().checkUriPermission(uri, pid, uid, modeFlags, userHandle, (IBinder) null);
        } catch (RemoteException e) {
            return -1;
        }
    }

    public void notifyChange(Uri uri, IContentObserver observer, boolean observerWantsSelfNotifications, boolean syncToNetwork, String callingPackage) {
        notifyChange(uri, observer, observerWantsSelfNotifications, syncToNetwork ? 1 : 0, UserHandle.getCallingUserId(), 10000, callingPackage);
    }

    /* loaded from: classes.dex */
    public static final class ObserverCall {
        final ObserverNode mNode;
        final IContentObserver mObserver;
        final int mObserverUserId;
        final boolean mSelfChange;

        ObserverCall(ObserverNode node, IContentObserver observer, boolean selfChange, int observerUserId) {
            this.mNode = node;
            this.mObserver = observer;
            this.mSelfChange = selfChange;
            this.mObserverUserId = observerUserId;
        }
    }

    public void requestSync(Account account, String authority, Bundle extras, String callingPackage) {
        Bundle.setDefusable(extras, true);
        ContentResolver.validateSyncExtrasBundle(extras);
        int userId = UserHandle.getCallingUserId();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        validateExtras(callingUid, extras);
        int syncExemption = getSyncExemptionAndCleanUpExtrasForCaller(callingUid, extras);
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.scheduleSync(account, userId, callingUid, authority, extras, -2, syncExemption, callingUid, callingPid, callingPackage);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void sync(SyncRequest request, String callingPackage) {
        syncAsUser(request, UserHandle.getCallingUserId(), callingPackage);
    }

    private long clampPeriod(long period) {
        long minPeriod = JobInfo.getMinPeriodMillis() / 1000;
        if (period < minPeriod) {
            Slog.w(TAG, "Requested poll frequency of " + period + " seconds being rounded up to " + minPeriod + "s.");
            return minPeriod;
        }
        return period;
    }

    public void syncAsUser(SyncRequest request, int userId, String callingPackage) {
        enforceCrossUserPermission(userId, "no permission to request sync as user: " + userId);
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        Bundle extras = request.getBundle();
        validateExtras(callingUid, extras);
        int syncExemption = getSyncExemptionAndCleanUpExtrasForCaller(callingUid, extras);
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager == null) {
                restoreCallingIdentity(identityToken);
                return;
            }
            long flextime = request.getSyncFlexTime();
            long runAtTime = request.getSyncRunTime();
            if (request.isPeriodic()) {
                try {
                    this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SYNC_SETTINGS", "no permission to write the sync settings");
                    SyncStorageEngine.EndPoint info = new SyncStorageEngine.EndPoint(request.getAccount(), request.getProvider(), userId);
                    getSyncManager().updateOrAddPeriodicSync(info, clampPeriod(runAtTime), flextime, extras);
                } catch (Throwable th) {
                    th = th;
                    restoreCallingIdentity(identityToken);
                    throw th;
                }
            } else {
                try {
                    syncManager.scheduleSync(request.getAccount(), userId, callingUid, request.getProvider(), extras, -2, syncExemption, callingUid, callingPid, callingPackage);
                } catch (Throwable th2) {
                    th = th2;
                    restoreCallingIdentity(identityToken);
                    throw th;
                }
            }
            restoreCallingIdentity(identityToken);
        } catch (Throwable th3) {
            th = th3;
        }
    }

    public void cancelSync(Account account, String authority, ComponentName cname) {
        cancelSyncAsUser(account, authority, cname, UserHandle.getCallingUserId());
    }

    public void cancelSyncAsUser(Account account, String authority, ComponentName cname, int userId) {
        if (authority != null && authority.length() == 0) {
            throw new IllegalArgumentException("Authority must be non-empty");
        }
        enforceCrossUserPermission(userId, "no permission to modify the sync settings for user " + userId);
        long identityToken = clearCallingIdentity();
        if (cname != null) {
            Slog.e(TAG, "cname not null.");
            return;
        }
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                SyncStorageEngine.EndPoint info = new SyncStorageEngine.EndPoint(account, authority, userId);
                syncManager.clearScheduledSyncOperations(info);
                syncManager.cancelActiveSync(info, null, "API");
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void cancelRequest(SyncRequest request) {
        SyncManager syncManager = getSyncManager();
        if (syncManager == null) {
            return;
        }
        int userId = UserHandle.getCallingUserId();
        int callingUid = Binder.getCallingUid();
        if (request.isPeriodic()) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SYNC_SETTINGS", "no permission to write the sync settings");
        }
        Bundle extras = new Bundle(request.getBundle());
        validateExtras(callingUid, extras);
        long identityToken = clearCallingIdentity();
        try {
            Account account = request.getAccount();
            String provider = request.getProvider();
            SyncStorageEngine.EndPoint info = new SyncStorageEngine.EndPoint(account, provider, userId);
            if (request.isPeriodic()) {
                SyncManager syncManager2 = getSyncManager();
                syncManager2.removePeriodicSync(info, extras, "cancelRequest() by uid=" + callingUid);
            }
            syncManager.cancelScheduledSyncOperation(info, extras);
            syncManager.cancelActiveSync(info, extras, "API");
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public SyncAdapterType[] getSyncAdapterTypes() {
        return getSyncAdapterTypesAsUser(UserHandle.getCallingUserId());
    }

    public SyncAdapterType[] getSyncAdapterTypesAsUser(int userId) {
        enforceCrossUserPermission(userId, "no permission to read sync settings for user " + userId);
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            return syncManager.getSyncAdapterTypes(userId);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public String[] getSyncAdapterPackagesForAuthorityAsUser(String authority, int userId) {
        enforceCrossUserPermission(userId, "no permission to read sync settings for user " + userId);
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            return syncManager.getSyncAdapterPackagesForAuthorityAsUser(authority, userId);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public boolean getSyncAutomatically(Account account, String providerName) {
        return getSyncAutomaticallyAsUser(account, providerName, UserHandle.getCallingUserId());
    }

    public boolean getSyncAutomaticallyAsUser(Account account, String providerName, int userId) {
        enforceCrossUserPermission(userId, "no permission to read the sync settings for user " + userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_SETTINGS", "no permission to read the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getSyncAutomatically(account, userId, providerName);
            }
            restoreCallingIdentity(identityToken);
            return false;
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setSyncAutomatically(Account account, String providerName, boolean sync) {
        setSyncAutomaticallyAsUser(account, providerName, sync, UserHandle.getCallingUserId());
    }

    public void setSyncAutomaticallyAsUser(Account account, String providerName, boolean sync, int userId) {
        if (TextUtils.isEmpty(providerName)) {
            throw new IllegalArgumentException("Authority must be non-empty");
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SYNC_SETTINGS", "no permission to write the sync settings");
        enforceCrossUserPermission(userId, "no permission to modify the sync settings for user " + userId);
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        int syncExemptionFlag = getSyncExemptionForCaller(callingUid);
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setSyncAutomatically(account, userId, providerName, sync, syncExemptionFlag, callingUid, callingPid);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void addPeriodicSync(Account account, String authority, Bundle extras, long pollFrequency) {
        Bundle.setDefusable(extras, true);
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (TextUtils.isEmpty(authority)) {
            throw new IllegalArgumentException("Authority must not be empty.");
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SYNC_SETTINGS", "no permission to write the sync settings");
        validateExtras(Binder.getCallingUid(), extras);
        int userId = UserHandle.getCallingUserId();
        long pollFrequency2 = clampPeriod(pollFrequency);
        long defaultFlex = SyncStorageEngine.calculateDefaultFlexTime(pollFrequency2);
        long identityToken = clearCallingIdentity();
        try {
            SyncStorageEngine.EndPoint info = new SyncStorageEngine.EndPoint(account, authority, userId);
            getSyncManager().updateOrAddPeriodicSync(info, pollFrequency2, defaultFlex, extras);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void removePeriodicSync(Account account, String authority, Bundle extras) {
        Bundle.setDefusable(extras, true);
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (TextUtils.isEmpty(authority)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SYNC_SETTINGS", "no permission to write the sync settings");
        validateExtras(Binder.getCallingUid(), extras);
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            SyncStorageEngine.EndPoint endPoint = new SyncStorageEngine.EndPoint(account, authority, userId);
            syncManager.removePeriodicSync(endPoint, extras, "removePeriodicSync() by uid=" + callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public List<PeriodicSync> getPeriodicSyncs(Account account, String providerName, ComponentName cname) {
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (TextUtils.isEmpty(providerName)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_SETTINGS", "no permission to read the sync settings");
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            return getSyncManager().getPeriodicSyncs(new SyncStorageEngine.EndPoint(account, providerName, userId));
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public int getIsSyncable(Account account, String providerName) {
        return getIsSyncableAsUser(account, providerName, UserHandle.getCallingUserId());
    }

    public int getIsSyncableAsUser(Account account, String providerName, int userId) {
        enforceCrossUserPermission(userId, "no permission to read the sync settings for user " + userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_SETTINGS", "no permission to read the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.computeSyncable(account, userId, providerName, false);
            }
            restoreCallingIdentity(identityToken);
            return -1;
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setIsSyncable(Account account, String providerName, int syncable) {
        setIsSyncableAsUser(account, providerName, syncable, UserHandle.getCallingUserId());
    }

    public void setIsSyncableAsUser(Account account, String providerName, int syncable, int userId) {
        if (TextUtils.isEmpty(providerName)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }
        enforceCrossUserPermission(userId, "no permission to set the sync settings for user " + userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SYNC_SETTINGS", "no permission to write the sync settings");
        int syncable2 = normalizeSyncable(syncable);
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setIsSyncable(account, userId, providerName, syncable2, callingUid, callingPid);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public boolean getMasterSyncAutomatically() {
        return getMasterSyncAutomaticallyAsUser(UserHandle.getCallingUserId());
    }

    public boolean getMasterSyncAutomaticallyAsUser(int userId) {
        enforceCrossUserPermission(userId, "no permission to read the sync settings for user " + userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_SETTINGS", "no permission to read the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getMasterSyncAutomatically(userId);
            }
            restoreCallingIdentity(identityToken);
            return false;
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setMasterSyncAutomatically(boolean flag) {
        setMasterSyncAutomaticallyAsUser(flag, UserHandle.getCallingUserId());
    }

    public void setMasterSyncAutomaticallyAsUser(boolean flag, int userId) {
        enforceCrossUserPermission(userId, "no permission to set the sync status for user " + userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SYNC_SETTINGS", "no permission to write the sync settings");
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setMasterSyncAutomatically(flag, userId, getSyncExemptionForCaller(callingUid), callingUid, callingPid);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public boolean isSyncActive(Account account, String authority, ComponentName cname) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_STATS", "no permission to read the sync stats");
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().isSyncActive(new SyncStorageEngine.EndPoint(account, authority, userId));
            }
            return false;
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public List<SyncInfo> getCurrentSyncs() {
        return getCurrentSyncsAsUser(UserHandle.getCallingUserId());
    }

    public List<SyncInfo> getCurrentSyncsAsUser(int userId) {
        enforceCrossUserPermission(userId, "no permission to read the sync settings for user " + userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_STATS", "no permission to read the sync stats");
        boolean canAccessAccounts = this.mContext.checkCallingOrSelfPermission("android.permission.GET_ACCOUNTS") == 0;
        long identityToken = clearCallingIdentity();
        try {
            return getSyncManager().getSyncStorageEngine().getCurrentSyncsCopy(userId, canAccessAccounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public SyncStatusInfo getSyncStatus(Account account, String authority, ComponentName cname) {
        return getSyncStatusAsUser(account, authority, cname, UserHandle.getCallingUserId());
    }

    public SyncStatusInfo getSyncStatusAsUser(Account account, String authority, ComponentName cname, int userId) {
        if (TextUtils.isEmpty(authority)) {
            throw new IllegalArgumentException("Authority must not be empty");
        }
        enforceCrossUserPermission(userId, "no permission to read the sync stats for user " + userId);
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_STATS", "no permission to read the sync stats");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                if (account != null && authority != null) {
                    SyncStorageEngine.EndPoint info = new SyncStorageEngine.EndPoint(account, authority, userId);
                    return syncManager.getSyncStorageEngine().getStatusByAuthority(info);
                }
                throw new IllegalArgumentException("Must call sync status with valid authority");
            }
            return null;
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public boolean isSyncPending(Account account, String authority, ComponentName cname) {
        return isSyncPendingAsUser(account, authority, cname, UserHandle.getCallingUserId());
    }

    public boolean isSyncPendingAsUser(Account account, String authority, ComponentName cname, int userId) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_SYNC_STATS", "no permission to read the sync stats");
        enforceCrossUserPermission(userId, "no permission to retrieve the sync settings for user " + userId);
        long identityToken = clearCallingIdentity();
        SyncManager syncManager = getSyncManager();
        if (syncManager == null) {
            return false;
        }
        try {
            if (account != null && authority != null) {
                SyncStorageEngine.EndPoint info = new SyncStorageEngine.EndPoint(account, authority, userId);
                return syncManager.getSyncStorageEngine().isSyncPending(info);
            }
            throw new IllegalArgumentException("Invalid authority specified");
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void addStatusChangeListener(int mask, ISyncStatusObserver callback) {
        int callingUid = Binder.getCallingUid();
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null && callback != null) {
                syncManager.getSyncStorageEngine().addStatusChangeListener(mask, UserHandle.getUserId(callingUid), callback);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void removeStatusChangeListener(ISyncStatusObserver callback) {
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null && callback != null) {
                syncManager.getSyncStorageEngine().removeStatusChangeListener(callback);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String getProviderPackageName(Uri uri) {
        ProviderInfo pi = this.mContext.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        if (pi != null) {
            return pi.packageName;
        }
        return null;
    }

    @GuardedBy({"mCache"})
    private ArrayMap<Pair<String, Uri>, Bundle> findOrCreateCacheLocked(int userId, String providerPackageName) {
        ArrayMap<String, ArrayMap<Pair<String, Uri>, Bundle>> userCache = this.mCache.get(userId);
        if (userCache == null) {
            userCache = new ArrayMap<>();
            this.mCache.put(userId, userCache);
        }
        ArrayMap<Pair<String, Uri>, Bundle> packageCache = userCache.get(providerPackageName);
        if (packageCache == null) {
            ArrayMap<Pair<String, Uri>, Bundle> packageCache2 = new ArrayMap<>();
            userCache.put(providerPackageName, packageCache2);
            return packageCache2;
        }
        return packageCache;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mCache"})
    public void invalidateCacheLocked(int userId, String providerPackageName, Uri uri) {
        ArrayMap<Pair<String, Uri>, Bundle> packageCache;
        ArrayMap<String, ArrayMap<Pair<String, Uri>, Bundle>> userCache = this.mCache.get(userId);
        if (userCache == null || (packageCache = userCache.get(providerPackageName)) == null) {
            return;
        }
        if (uri != null) {
            int i = 0;
            while (i < packageCache.size()) {
                Pair<String, Uri> key = packageCache.keyAt(i);
                if (key.second != null && ((Uri) key.second).toString().startsWith(uri.toString())) {
                    packageCache.removeAt(i);
                } else {
                    i++;
                }
            }
            return;
        }
        packageCache.clear();
    }

    public void putCache(String packageName, Uri key, Bundle value, int userId) {
        Bundle.setDefusable(value, true);
        enforceCrossUserPermission(userId, TAG);
        this.mContext.enforceCallingOrSelfPermission("android.permission.CACHE_CONTENT", TAG);
        ((AppOpsManager) this.mContext.getSystemService(AppOpsManager.class)).checkPackage(Binder.getCallingUid(), packageName);
        String providerPackageName = getProviderPackageName(key);
        Pair<String, Uri> fullKey = Pair.create(packageName, key);
        synchronized (this.mCache) {
            ArrayMap<Pair<String, Uri>, Bundle> cache = findOrCreateCacheLocked(userId, providerPackageName);
            if (value != null) {
                cache.put(fullKey, value);
            } else {
                cache.remove(fullKey);
            }
        }
    }

    public Bundle getCache(String packageName, Uri key, int userId) {
        Bundle bundle;
        enforceCrossUserPermission(userId, TAG);
        this.mContext.enforceCallingOrSelfPermission("android.permission.CACHE_CONTENT", TAG);
        ((AppOpsManager) this.mContext.getSystemService(AppOpsManager.class)).checkPackage(Binder.getCallingUid(), packageName);
        String providerPackageName = getProviderPackageName(key);
        Pair<String, Uri> fullKey = Pair.create(packageName, key);
        synchronized (this.mCache) {
            ArrayMap<Pair<String, Uri>, Bundle> cache = findOrCreateCacheLocked(userId, providerPackageName);
            bundle = cache.get(fullKey);
        }
        return bundle;
    }

    private int handleIncomingUser(Uri uri, int pid, int uid, int modeFlags, boolean allowNonFull, int userId) {
        if (userId == -2) {
            userId = ActivityManager.getCurrentUser();
        }
        if (userId == -1) {
            Context context = this.mContext;
            context.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "No access to " + uri);
        } else if (userId < 0) {
            throw new IllegalArgumentException("Invalid user: " + userId);
        } else if (userId != UserHandle.getCallingUserId() && checkUriPermission(uri, pid, uid, modeFlags, userId) != 0) {
            boolean allow = false;
            if (this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") == 0) {
                allow = true;
            } else if (allowNonFull && this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS") == 0) {
                allow = true;
            }
            if (!allow) {
                String permissions = allowNonFull ? "android.permission.INTERACT_ACROSS_USERS_FULL or android.permission.INTERACT_ACROSS_USERS" : "android.permission.INTERACT_ACROSS_USERS_FULL";
                throw new SecurityException("No access to " + uri + ": neither user " + uid + " nor current process has " + permissions);
            }
        }
        return userId;
    }

    private void enforceCrossUserPermission(int userHandle, String message) {
        int callingUser = UserHandle.getCallingUserId();
        if (callingUser != userHandle) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", message);
        }
    }

    private static int normalizeSyncable(int syncable) {
        if (syncable > 0) {
            return 1;
        }
        if (syncable == 0) {
            return 0;
        }
        return -2;
    }

    private void validateExtras(int callingUid, Bundle extras) {
        if (extras.containsKey("v_exemption") && callingUid != 0 && callingUid != 1000 && callingUid != 2000) {
            Log.w(TAG, "Invalid extras specified. requestsync -f/-F needs to run on 'adb shell'");
            throw new SecurityException("Invalid extras specified.");
        }
    }

    private int getSyncExemptionForCaller(int callingUid) {
        return getSyncExemptionAndCleanUpExtrasForCaller(callingUid, null);
    }

    private int getSyncExemptionAndCleanUpExtrasForCaller(int callingUid, Bundle extras) {
        if (extras != null) {
            int exemption = extras.getInt("v_exemption", -1);
            extras.remove("v_exemption");
            if (exemption != -1) {
                return exemption;
            }
        }
        ActivityManagerInternal ami = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        if (ami == null) {
            return 0;
        }
        int procState = ami.getUidProcessState(callingUid);
        boolean isUidActive = ami.isUidActive(callingUid);
        if (procState <= 2 || procState == 4) {
            return 2;
        }
        if (procState > 7 && !isUidActive) {
            return 0;
        }
        return 1;
    }

    /* loaded from: classes.dex */
    public static final class ObserverNode {
        public static final int DELETE_TYPE = 2;
        public static final int INSERT_TYPE = 0;
        public static final int UPDATE_TYPE = 1;
        private String mName;
        private ArrayList<ObserverNode> mChildren = new ArrayList<>();
        private ArrayList<ObserverEntry> mObservers = new ArrayList<>();

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public class ObserverEntry implements IBinder.DeathRecipient {
            public final boolean notifyForDescendants;
            public final IContentObserver observer;
            private final Object observersLock;
            public final int pid;
            public final int uid;
            private final int userHandle;

            public ObserverEntry(IContentObserver o, boolean n, Object observersLock, int _uid, int _pid, int _userHandle, Uri uri) {
                boolean alreadyDetected;
                this.observersLock = observersLock;
                this.observer = o;
                this.uid = _uid;
                this.pid = _pid;
                this.userHandle = _userHandle;
                this.notifyForDescendants = n;
                int entries = ContentService.sObserverDeathDispatcher.linkToDeath(this.observer, this);
                if (entries == -1) {
                    binderDied();
                } else if (entries == 1000) {
                    synchronized (ContentService.sObserverLeakDetectedUid) {
                        alreadyDetected = ContentService.sObserverLeakDetectedUid.contains(Integer.valueOf(this.uid));
                        if (!alreadyDetected) {
                            ContentService.sObserverLeakDetectedUid.add(Integer.valueOf(this.uid));
                        }
                    }
                    if (!alreadyDetected) {
                        String caller = null;
                        try {
                            caller = (String) ArrayUtils.firstOrNull(AppGlobals.getPackageManager().getPackagesForUid(this.uid));
                        } catch (RemoteException e) {
                        }
                        Slog.wtf(ContentService.TAG, "Observer registered too many times. Leak? cpid=" + this.pid + " cuid=" + this.uid + " cpkg=" + caller + " url=" + uri);
                    }
                }
            }

            @Override // android.os.IBinder.DeathRecipient
            public void binderDied() {
                synchronized (this.observersLock) {
                    ObserverNode.this.removeObserverLocked(this.observer);
                }
            }

            public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args, String name, String prefix, SparseIntArray pidCounts) {
                int i = this.pid;
                pidCounts.put(i, pidCounts.get(i) + 1);
                pw.print(prefix);
                pw.print(name);
                pw.print(": pid=");
                pw.print(this.pid);
                pw.print(" uid=");
                pw.print(this.uid);
                pw.print(" user=");
                pw.print(this.userHandle);
                pw.print(" target=");
                IContentObserver iContentObserver = this.observer;
                pw.println(Integer.toHexString(System.identityHashCode(iContentObserver != null ? iContentObserver.asBinder() : null)));
            }
        }

        public ObserverNode(String name) {
            this.mName = name;
        }

        public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args, String name, String prefix, int[] counts, SparseIntArray pidCounts) {
            String innerName;
            String innerName2 = null;
            if (this.mObservers.size() > 0) {
                innerName2 = "".equals(name) ? this.mName : name + SliceClientPermissions.SliceAuthority.DELIMITER + this.mName;
                for (int i = 0; i < this.mObservers.size(); i++) {
                    counts[1] = counts[1] + 1;
                    this.mObservers.get(i).dumpLocked(fd, pw, args, innerName2, prefix, pidCounts);
                }
            }
            if (this.mChildren.size() > 0) {
                if (innerName2 != null) {
                    innerName = innerName2;
                } else if ("".equals(name)) {
                    String innerName3 = this.mName;
                    innerName = innerName3;
                } else {
                    String innerName4 = name + SliceClientPermissions.SliceAuthority.DELIMITER + this.mName;
                    innerName = innerName4;
                }
                for (int i2 = 0; i2 < this.mChildren.size(); i2++) {
                    counts[0] = counts[0] + 1;
                    this.mChildren.get(i2).dumpLocked(fd, pw, args, innerName, prefix, counts, pidCounts);
                }
            }
        }

        private String getUriSegment(Uri uri, int index) {
            if (uri != null) {
                if (index == 0) {
                    return uri.getAuthority();
                }
                return uri.getPathSegments().get(index - 1);
            }
            return null;
        }

        private int countUriSegments(Uri uri) {
            if (uri == null) {
                return 0;
            }
            return uri.getPathSegments().size() + 1;
        }

        public void addObserverLocked(Uri uri, IContentObserver observer, boolean notifyForDescendants, Object observersLock, int uid, int pid, int userHandle) {
            addObserverLocked(uri, 0, observer, notifyForDescendants, observersLock, uid, pid, userHandle);
        }

        private void addObserverLocked(Uri uri, int index, IContentObserver observer, boolean notifyForDescendants, Object observersLock, int uid, int pid, int userHandle) {
            if (index == countUriSegments(uri)) {
                this.mObservers.add(new ObserverEntry(observer, notifyForDescendants, observersLock, uid, pid, userHandle, uri));
                return;
            }
            String segment = getUriSegment(uri, index);
            if (segment == null) {
                throw new IllegalArgumentException("Invalid Uri (" + uri + ") used for observer");
            }
            int N = this.mChildren.size();
            for (int i = 0; i < N; i++) {
                ObserverNode node = this.mChildren.get(i);
                if (node.mName.equals(segment)) {
                    node.addObserverLocked(uri, index + 1, observer, notifyForDescendants, observersLock, uid, pid, userHandle);
                    return;
                }
            }
            ObserverNode node2 = new ObserverNode(segment);
            this.mChildren.add(node2);
            node2.addObserverLocked(uri, index + 1, observer, notifyForDescendants, observersLock, uid, pid, userHandle);
        }

        public boolean removeObserverLocked(IContentObserver observer) {
            int size = this.mChildren.size();
            int i = 0;
            while (i < size) {
                boolean empty = this.mChildren.get(i).removeObserverLocked(observer);
                if (empty) {
                    this.mChildren.remove(i);
                    i--;
                    size--;
                }
                i++;
            }
            IBinder observerBinder = observer.asBinder();
            int size2 = this.mObservers.size();
            int i2 = 0;
            while (true) {
                if (i2 >= size2) {
                    break;
                }
                ObserverEntry entry = this.mObservers.get(i2);
                if (entry.observer.asBinder() != observerBinder) {
                    i2++;
                } else {
                    this.mObservers.remove(i2);
                    ContentService.sObserverDeathDispatcher.unlinkToDeath(observer, entry);
                    break;
                }
            }
            return this.mChildren.size() == 0 && this.mObservers.size() == 0;
        }

        private void collectMyObserversLocked(boolean leaf, IContentObserver observer, boolean observerWantsSelfNotifications, int flags, int targetUserHandle, ArrayList<ObserverCall> calls) {
            int N = this.mObservers.size();
            IBinder observerBinder = observer == null ? null : observer.asBinder();
            for (int i = 0; i < N; i++) {
                ObserverEntry entry = this.mObservers.get(i);
                boolean selfChange = entry.observer.asBinder() == observerBinder;
                if ((!selfChange || observerWantsSelfNotifications) && (targetUserHandle == -1 || entry.userHandle == -1 || targetUserHandle == entry.userHandle)) {
                    if (leaf) {
                        if ((flags & 2) != 0 && entry.notifyForDescendants) {
                        }
                        calls.add(new ObserverCall(this, entry.observer, selfChange, UserHandle.getUserId(entry.uid)));
                    } else {
                        if (!entry.notifyForDescendants) {
                        }
                        calls.add(new ObserverCall(this, entry.observer, selfChange, UserHandle.getUserId(entry.uid)));
                    }
                }
            }
        }

        public void collectObserversLocked(Uri uri, int index, IContentObserver observer, boolean observerWantsSelfNotifications, int flags, int targetUserHandle, ArrayList<ObserverCall> calls) {
            String segment = null;
            int segmentCount = countUriSegments(uri);
            if (index >= segmentCount) {
                collectMyObserversLocked(true, observer, observerWantsSelfNotifications, flags, targetUserHandle, calls);
            } else if (index < segmentCount) {
                segment = getUriSegment(uri, index);
                collectMyObserversLocked(false, observer, observerWantsSelfNotifications, flags, targetUserHandle, calls);
            }
            int N = this.mChildren.size();
            for (int i = 0; i < N; i++) {
                ObserverNode node = this.mChildren.get(i);
                if (segment == null || node.mName.equals(segment)) {
                    node.collectObserversLocked(uri, index + 1, observer, observerWantsSelfNotifications, flags, targetUserHandle, calls);
                    if (segment != null) {
                        return;
                    }
                }
            }
        }

        public void notifyScreenBrightnessObservers() {
            Uri uri = Uri.parse("content://settings/system/screen_brightness");
            ArrayList<ObserverCall> calls = new ArrayList<>();
            collectObserversLocked(uri, 0, null, false, 1, 0, calls);
            int numCalls = calls.size();
            for (int i = 0; i < numCalls; i++) {
                ObserverCall oc = calls.get(i);
                try {
                    oc.mObserver.onChange(oc.mSelfChange, uri, 0);
                    Slog.d(ContentService.TAG, "NotifiedScreen " + oc.mObserver + " of update at " + uri);
                } catch (RemoteException e) {
                }
            }
        }
    }

    private void enforceShell(String method) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 2000 && callingUid != 0) {
            throw new SecurityException("Non-shell user attempted to call " + method);
        }
    }

    public void resetTodayStats() {
        enforceShell("resetTodayStats");
        if (this.mSyncManager != null) {
            long token = Binder.clearCallingIdentity();
            try {
                this.mSyncManager.resetTodayStats();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    public void onDbCorruption(String tag, String message, String stacktrace) {
        Slog.e(tag, message);
        Slog.e(tag, "at " + stacktrace);
        Slog.wtf(tag, message);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new ContentShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }
}
