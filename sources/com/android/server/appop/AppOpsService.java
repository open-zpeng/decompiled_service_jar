package com.android.server.appop;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.TriFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.SystemService;
import com.android.server.appop.AppOpsService;
import com.android.server.display.color.DisplayTransformManager;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.wm.ActivityTaskManagerService;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import libcore.util.EmptyArray;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public class AppOpsService extends IAppOpsService.Stub {
    private static final int CURRENT_VERSION = 1;
    static final boolean DEBUG = false;
    private static final int NO_VERSION = -1;
    static final String TAG = "AppOps";
    private static final int UID_ANY = -2;
    static final long WRITE_DELAY = 1800000;
    @GuardedBy({"this"})
    private AppOpsManagerInternal.CheckOpsDelegate mCheckOpsDelegate;
    @VisibleForTesting
    final Constants mConstants;
    Context mContext;
    boolean mFastWriteScheduled;
    final AtomicFile mFile;
    final Handler mHandler;
    long mLastRealtime;
    SparseIntArray mProfileOwners;
    boolean mWriteScheduled;
    private static final int[] PROCESS_STATE_TO_UID_STATE = {100, 100, 200, DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR, SystemService.PHASE_SYSTEM_SERVICES_READY, 400, SystemService.PHASE_SYSTEM_SERVICES_READY, SystemService.PHASE_SYSTEM_SERVICES_READY, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, SystemService.PHASE_THIRD_PARTY_APPS_CAN_START, 700, 700, 700, 700, 700, 700, 700, 700, 700};
    private static final int[] OPS_RESTRICTED_ON_SUSPEND = {28, 27, 26};
    private final AppOpsManagerInternalImpl mAppOpsManagerInternal = new AppOpsManagerInternalImpl();
    final Runnable mWriteRunner = new Runnable() { // from class: com.android.server.appop.AppOpsService.1
        @Override // java.lang.Runnable
        public void run() {
            synchronized (AppOpsService.this) {
                AppOpsService.this.mWriteScheduled = false;
                AppOpsService.this.mFastWriteScheduled = false;
                AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() { // from class: com.android.server.appop.AppOpsService.1.1
                    /* JADX INFO: Access modifiers changed from: protected */
                    @Override // android.os.AsyncTask
                    public Void doInBackground(Void... params) {
                        AppOpsService.this.writeState();
                        return null;
                    }
                };
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, null);
            }
        }
    };
    @VisibleForTesting
    final SparseArray<UidState> mUidStates = new SparseArray<>();
    final HistoricalRegistry mHistoricalRegistry = new HistoricalRegistry(this);
    private final ArrayMap<IBinder, ClientRestrictionState> mOpUserRestrictions = new ArrayMap<>();
    final SparseArray<ArraySet<ModeCallback>> mOpModeWatchers = new SparseArray<>();
    final ArrayMap<String, ArraySet<ModeCallback>> mPackageModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, ModeCallback> mModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<ActiveCallback>> mActiveWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<NotedCallback>> mNotedWatchers = new ArrayMap<>();
    final SparseArray<SparseArray<Restriction>> mAudioRestrictions = new SparseArray<>();
    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public final class Constants extends ContentObserver {
        private static final String KEY_BG_STATE_SETTLE_TIME = "bg_state_settle_time";
        private static final String KEY_FG_SERVICE_STATE_SETTLE_TIME = "fg_service_state_settle_time";
        private static final String KEY_TOP_STATE_SETTLE_TIME = "top_state_settle_time";
        public long BG_STATE_SETTLE_TIME;
        public long FG_SERVICE_STATE_SETTLE_TIME;
        public long TOP_STATE_SETTLE_TIME;
        private final KeyValueListParser mParser;
        private ContentResolver mResolver;

        public Constants(Handler handler) {
            super(handler);
            this.mParser = new KeyValueListParser(',');
            updateConstants();
        }

        public void startMonitoring(ContentResolver resolver) {
            this.mResolver = resolver;
            this.mResolver.registerContentObserver(Settings.Global.getUriFor("app_ops_constants"), false, this);
            updateConstants();
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            ContentResolver contentResolver = this.mResolver;
            String value = contentResolver != null ? Settings.Global.getString(contentResolver, "app_ops_constants") : "";
            synchronized (AppOpsService.this) {
                try {
                    this.mParser.setString(value);
                } catch (IllegalArgumentException e) {
                    Slog.e(AppOpsService.TAG, "Bad app ops settings", e);
                }
                this.TOP_STATE_SETTLE_TIME = this.mParser.getDurationMillis(KEY_TOP_STATE_SETTLE_TIME, 30000L);
                this.FG_SERVICE_STATE_SETTLE_TIME = this.mParser.getDurationMillis(KEY_FG_SERVICE_STATE_SETTLE_TIME, (long) JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
                this.BG_STATE_SETTLE_TIME = this.mParser.getDurationMillis(KEY_BG_STATE_SETTLE_TIME, 1000L);
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");
            pw.print("    ");
            pw.print(KEY_TOP_STATE_SETTLE_TIME);
            pw.print("=");
            TimeUtils.formatDuration(this.TOP_STATE_SETTLE_TIME, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_FG_SERVICE_STATE_SETTLE_TIME);
            pw.print("=");
            TimeUtils.formatDuration(this.FG_SERVICE_STATE_SETTLE_TIME, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_BG_STATE_SETTLE_TIME);
            pw.print("=");
            TimeUtils.formatDuration(this.BG_STATE_SETTLE_TIME, pw);
            pw.println();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static final class UidState {
        public SparseBooleanArray foregroundOps;
        public boolean hasForegroundWatchers;
        public SparseIntArray opModes;
        public long pendingStateCommitTime;
        public ArrayMap<String, Ops> pkgOps;
        public int startNesting;
        public final int uid;
        public int state = 700;
        public int pendingState = 700;

        public UidState(int uid) {
            this.uid = uid;
        }

        public void clear() {
            this.pkgOps = null;
            this.opModes = null;
        }

        public boolean isDefault() {
            SparseIntArray sparseIntArray;
            ArrayMap<String, Ops> arrayMap = this.pkgOps;
            return (arrayMap == null || arrayMap.isEmpty()) && ((sparseIntArray = this.opModes) == null || sparseIntArray.size() <= 0) && this.state == 700 && this.pendingState == 700;
        }

        int evalMode(int op, int mode) {
            if (mode == 4) {
                return this.state <= AppOpsManager.resolveFirstUnrestrictedUidState(op) ? 0 : 1;
            }
            return mode;
        }

        private void evalForegroundWatchers(int op, SparseArray<ArraySet<ModeCallback>> watchers, SparseBooleanArray which) {
            boolean curValue = which.get(op, false);
            ArraySet<ModeCallback> callbacks = watchers.get(op);
            if (callbacks != null) {
                for (int cbi = callbacks.size() - 1; !curValue && cbi >= 0; cbi--) {
                    if ((callbacks.valueAt(cbi).mFlags & 1) != 0) {
                        this.hasForegroundWatchers = true;
                        curValue = true;
                    }
                }
            }
            which.put(op, curValue);
        }

        public void evalForegroundOps(SparseArray<ArraySet<ModeCallback>> watchers) {
            SparseBooleanArray which = null;
            this.hasForegroundWatchers = false;
            SparseIntArray sparseIntArray = this.opModes;
            if (sparseIntArray != null) {
                for (int i = sparseIntArray.size() - 1; i >= 0; i--) {
                    if (this.opModes.valueAt(i) == 4) {
                        if (which == null) {
                            which = new SparseBooleanArray();
                        }
                        evalForegroundWatchers(this.opModes.keyAt(i), watchers, which);
                    }
                }
            }
            ArrayMap<String, Ops> arrayMap = this.pkgOps;
            if (arrayMap != null) {
                for (int i2 = arrayMap.size() - 1; i2 >= 0; i2--) {
                    Ops ops = this.pkgOps.valueAt(i2);
                    for (int j = ops.size() - 1; j >= 0; j--) {
                        if (ops.valueAt(j).mode == 4) {
                            if (which == null) {
                                which = new SparseBooleanArray();
                            }
                            evalForegroundWatchers(ops.keyAt(j), watchers, which);
                        }
                    }
                }
            }
            this.foregroundOps = which;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class Ops extends SparseArray<Op> {
        final boolean isPrivileged;
        final String packageName;
        final UidState uidState;

        Ops(String _packageName, UidState _uidState, boolean _isPrivileged) {
            this.packageName = _packageName;
            this.uidState = _uidState;
            this.isPrivileged = _isPrivileged;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class Op {
        private LongSparseLongArray mAccessTimes;
        private LongSparseLongArray mDurations;
        private LongSparseArray<String> mProxyPackageNames;
        private LongSparseLongArray mProxyUids;
        private LongSparseLongArray mRejectTimes;
        private int mode;
        int op;
        final String packageName;
        boolean running;
        int startNesting;
        long startRealtime;
        final UidState uidState;

        Op(UidState uidState, String packageName, int op) {
            this.op = op;
            this.uidState = uidState;
            this.packageName = packageName;
            this.mode = AppOpsManager.opToDefaultMode(op);
        }

        int getMode() {
            return this.mode;
        }

        int evalMode() {
            return this.uidState.evalMode(this.op, this.mode);
        }

        public void accessed(long time, int proxyUid, String proxyPackageName, int uidState, int flags) {
            long key = AppOpsManager.makeKey(uidState, flags);
            if (this.mAccessTimes == null) {
                this.mAccessTimes = new LongSparseLongArray();
            }
            this.mAccessTimes.put(key, time);
            updateProxyState(key, proxyUid, proxyPackageName);
            LongSparseLongArray longSparseLongArray = this.mDurations;
            if (longSparseLongArray != null) {
                longSparseLongArray.delete(key);
            }
        }

        public void rejected(long time, int proxyUid, String proxyPackageName, int uidState, int flags) {
            long key = AppOpsManager.makeKey(uidState, flags);
            if (this.mRejectTimes == null) {
                this.mRejectTimes = new LongSparseLongArray();
            }
            this.mRejectTimes.put(key, time);
            updateProxyState(key, proxyUid, proxyPackageName);
            LongSparseLongArray longSparseLongArray = this.mDurations;
            if (longSparseLongArray != null) {
                longSparseLongArray.delete(key);
            }
        }

        public void started(long time, int uidState, int flags) {
            updateAccessTimeAndDuration(time, -1L, uidState, flags);
            this.running = true;
        }

        public void finished(long time, long duration, int uidState, int flags) {
            updateAccessTimeAndDuration(time, duration, uidState, flags);
            this.running = false;
        }

        public void running(long time, long duration, int uidState, int flags) {
            updateAccessTimeAndDuration(time, duration, uidState, flags);
        }

        public void continuing(long duration, int uidState, int flags) {
            long key = AppOpsManager.makeKey(uidState, flags);
            if (this.mDurations == null) {
                this.mDurations = new LongSparseLongArray();
            }
            this.mDurations.put(key, duration);
        }

        private void updateAccessTimeAndDuration(long time, long duration, int uidState, int flags) {
            long key = AppOpsManager.makeKey(uidState, flags);
            if (this.mAccessTimes == null) {
                this.mAccessTimes = new LongSparseLongArray();
            }
            this.mAccessTimes.put(key, time);
            if (this.mDurations == null) {
                this.mDurations = new LongSparseLongArray();
            }
            this.mDurations.put(key, duration);
        }

        private void updateProxyState(long key, int proxyUid, String proxyPackageName) {
            if (proxyUid == -1) {
                return;
            }
            if (this.mProxyUids == null) {
                this.mProxyUids = new LongSparseLongArray();
            }
            this.mProxyUids.put(key, proxyUid);
            if (this.mProxyPackageNames == null) {
                this.mProxyPackageNames = new LongSparseArray<>();
            }
            this.mProxyPackageNames.put(key, proxyPackageName);
        }

        boolean hasAnyTime() {
            LongSparseLongArray longSparseLongArray;
            LongSparseLongArray longSparseLongArray2 = this.mAccessTimes;
            return (longSparseLongArray2 != null && longSparseLongArray2.size() > 0) || ((longSparseLongArray = this.mRejectTimes) != null && longSparseLongArray.size() > 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ModeCallback implements IBinder.DeathRecipient {
        final IAppOpsCallback mCallback;
        final int mCallingPid;
        final int mCallingUid;
        final int mFlags;
        final int mWatchingUid;

        ModeCallback(IAppOpsCallback callback, int watchingUid, int flags, int callingUid, int callingPid) {
            this.mCallback = callback;
            this.mWatchingUid = watchingUid;
            this.mFlags = flags;
            this.mCallingUid = callingUid;
            this.mCallingPid = callingPid;
            try {
                this.mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        public boolean isWatchingUid(int uid) {
            int i;
            return uid == -2 || (i = this.mWatchingUid) < 0 || i == uid;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ModeCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, this.mWatchingUid);
            sb.append(" flags=0x");
            sb.append(Integer.toHexString(this.mFlags));
            sb.append(" from uid=");
            UserHandle.formatUid(sb, this.mCallingUid);
            sb.append(" pid=");
            sb.append(this.mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void unlinkToDeath() {
            this.mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            AppOpsService.this.stopWatchingMode(this.mCallback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ActiveCallback implements IBinder.DeathRecipient {
        final IAppOpsActiveCallback mCallback;
        final int mCallingPid;
        final int mCallingUid;
        final int mWatchingUid;

        ActiveCallback(IAppOpsActiveCallback callback, int watchingUid, int callingUid, int callingPid) {
            this.mCallback = callback;
            this.mWatchingUid = watchingUid;
            this.mCallingUid = callingUid;
            this.mCallingPid = callingPid;
            try {
                this.mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ActiveCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, this.mWatchingUid);
            sb.append(" from uid=");
            UserHandle.formatUid(sb, this.mCallingUid);
            sb.append(" pid=");
            sb.append(this.mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void destroy() {
            this.mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            AppOpsService.this.stopWatchingActive(this.mCallback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class NotedCallback implements IBinder.DeathRecipient {
        final IAppOpsNotedCallback mCallback;
        final int mCallingPid;
        final int mCallingUid;
        final int mWatchingUid;

        NotedCallback(IAppOpsNotedCallback callback, int watchingUid, int callingUid, int callingPid) {
            this.mCallback = callback;
            this.mWatchingUid = watchingUid;
            this.mCallingUid = callingUid;
            this.mCallingPid = callingPid;
            try {
                this.mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("NotedCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, this.mWatchingUid);
            sb.append(" from uid=");
            UserHandle.formatUid(sb, this.mCallingUid);
            sb.append(" pid=");
            sb.append(this.mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void destroy() {
            this.mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            AppOpsService.this.stopWatchingNoted(this.mCallback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ClientState extends Binder implements IBinder.DeathRecipient {
        final IBinder mAppToken;
        final ArrayList<Op> mStartedOps = new ArrayList<>();
        final int mPid = Binder.getCallingPid();

        ClientState(IBinder appToken) {
            this.mAppToken = appToken;
            if (!(appToken instanceof Binder)) {
                try {
                    this.mAppToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                }
            }
        }

        public String toString() {
            return "ClientState{mAppToken=" + this.mAppToken + ", pid=" + this.mPid + '}';
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (AppOpsService.this) {
                for (int i = this.mStartedOps.size() - 1; i >= 0; i--) {
                    AppOpsService.this.finishOperationLocked(this.mStartedOps.get(i), true);
                }
                AppOpsService.this.mClients.remove(this.mAppToken);
            }
        }
    }

    public AppOpsService(File storagePath, Handler handler) {
        LockGuard.installLock(this, 0);
        this.mFile = new AtomicFile(storagePath, "appops");
        this.mHandler = handler;
        this.mConstants = new Constants(this.mHandler);
        readState();
    }

    public void publish(Context context) {
        this.mContext = context;
        ServiceManager.addService("appops", asBinder());
        LocalServices.addService(AppOpsManagerInternal.class, this.mAppOpsManagerInternal);
    }

    public void systemReady() {
        this.mConstants.startMonitoring(this.mContext.getContentResolver());
        this.mHistoricalRegistry.systemReady(this.mContext.getContentResolver());
        synchronized (this) {
            boolean changed = false;
            for (int i = this.mUidStates.size() - 1; i >= 0; i--) {
                UidState uidState = this.mUidStates.valueAt(i);
                String[] packageNames = getPackagesForUid(uidState.uid);
                if (ArrayUtils.isEmpty(packageNames)) {
                    uidState.clear();
                    this.mUidStates.removeAt(i);
                    changed = true;
                } else {
                    ArrayMap<String, Ops> pkgs = uidState.pkgOps;
                    if (pkgs != null) {
                        Iterator<Ops> it = pkgs.values().iterator();
                        while (it.hasNext()) {
                            Ops ops = it.next();
                            int curUid = -1;
                            try {
                                curUid = AppGlobals.getPackageManager().getPackageUid(ops.packageName, 8192, UserHandle.getUserId(ops.uidState.uid));
                            } catch (RemoteException e) {
                            }
                            if (curUid != ops.uidState.uid) {
                                Slog.i(TAG, "Pruning old package " + ops.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + ops.uidState + ": new uid=" + curUid);
                                it.remove();
                                changed = true;
                            }
                        }
                        if (uidState.isDefault()) {
                            this.mUidStates.removeAt(i);
                        }
                    }
                }
            }
            if (changed) {
                scheduleFastWriteLocked();
            }
        }
        IntentFilter packageSuspendFilter = new IntentFilter();
        packageSuspendFilter.addAction("android.intent.action.PACKAGES_UNSUSPENDED");
        packageSuspendFilter.addAction("android.intent.action.PACKAGES_SUSPENDED");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.appop.AppOpsService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                int[] iArr;
                int[] changedUids = intent.getIntArrayExtra("android.intent.extra.changed_uid_list");
                String[] changedPkgs = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                for (int code : AppOpsService.OPS_RESTRICTED_ON_SUSPEND) {
                    synchronized (AppOpsService.this) {
                        ArraySet<ModeCallback> callbacks = AppOpsService.this.mOpModeWatchers.get(code);
                        if (callbacks != null) {
                            ArraySet<ModeCallback> callbacks2 = new ArraySet<>(callbacks);
                            for (int i2 = 0; i2 < changedUids.length; i2++) {
                                int changedUid = changedUids[i2];
                                String changedPkg = changedPkgs[i2];
                                AppOpsService.this.notifyOpChanged(callbacks2, code, changedUid, changedPkg);
                            }
                        }
                    }
                }
            }
        }, packageSuspendFilter);
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.setExternalSourcesPolicy(new PackageManagerInternal.ExternalSourcesPolicy() { // from class: com.android.server.appop.AppOpsService.3
            public int getPackageTrustedToInstallApps(String packageName, int uid) {
                int appOpMode = AppOpsService.this.checkOperation(66, uid, packageName);
                if (appOpMode != 0) {
                    return appOpMode != 2 ? 2 : 1;
                }
                return 0;
            }
        });
        if (!StorageManager.hasIsolatedStorage()) {
            StorageManagerInternal storageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
            storageManagerInternal.addExternalStoragePolicy(new StorageManagerInternal.ExternalStorageMountPolicy() { // from class: com.android.server.appop.AppOpsService.4
                public int getMountMode(int uid, String packageName) {
                    if (!Process.isIsolated(uid) && AppOpsService.this.noteOperation(59, uid, packageName) == 0) {
                        if (AppOpsService.this.noteOperation(60, uid, packageName) != 0) {
                            return 2;
                        }
                        return 3;
                    }
                    return 0;
                }

                public boolean hasExternalStorage(int uid, String packageName) {
                    int mountMode = getMountMode(uid, packageName);
                    return mountMode == 2 || mountMode == 3;
                }
            });
        }
    }

    public void packageRemoved(int uid, String packageName) {
        synchronized (this) {
            UidState uidState = this.mUidStates.get(uid);
            if (uidState == null) {
                return;
            }
            Ops ops = null;
            if (uidState.pkgOps != null) {
                ops = uidState.pkgOps.remove(packageName);
            }
            if (ops != null && uidState.pkgOps.isEmpty() && getPackagesForUid(uid).length <= 0) {
                this.mUidStates.remove(uid);
            }
            int clientCount = this.mClients.size();
            for (int i = 0; i < clientCount; i++) {
                ClientState client = this.mClients.valueAt(i);
                if (client.mStartedOps != null) {
                    int opCount = client.mStartedOps.size();
                    for (int j = opCount - 1; j >= 0; j--) {
                        Op op = client.mStartedOps.get(j);
                        if (uid == op.uidState.uid && packageName.equals(op.packageName)) {
                            finishOperationLocked(op, true);
                            client.mStartedOps.remove(j);
                            if (op.startNesting <= 0) {
                                scheduleOpActiveChangedIfNeededLocked(op.op, uid, packageName, false);
                            }
                        }
                    }
                }
            }
            if (ops != null) {
                scheduleFastWriteLocked();
                int opCount2 = ops.size();
                for (int i2 = 0; i2 < opCount2; i2++) {
                    Op op2 = ops.valueAt(i2);
                    if (op2.running) {
                        scheduleOpActiveChangedIfNeededLocked(op2.op, op2.uidState.uid, op2.packageName, false);
                    }
                }
            }
            this.mHistoricalRegistry.clearHistory(uid, packageName);
        }
    }

    public void uidRemoved(int uid) {
        synchronized (this) {
            if (this.mUidStates.indexOfKey(uid) >= 0) {
                this.mUidStates.remove(uid);
                scheduleFastWriteLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePendingState(long currentTime, int uid) {
        synchronized (this) {
            this.mLastRealtime = Long.max(currentTime, this.mLastRealtime);
            updatePendingStateIfNeededLocked(this.mUidStates.get(uid));
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:29:0x006c A[Catch: all -> 0x00e3, TryCatch #0 {, blocks: (B:5:0x0006, B:7:0x0010, B:9:0x0014, B:13:0x0020, B:16:0x0025, B:18:0x002d, B:20:0x0033, B:25:0x0045, B:21:0x0038, B:23:0x003c, B:24:0x0041, B:27:0x0068, B:29:0x006c, B:31:0x007a, B:33:0x008b, B:35:0x0096, B:37:0x00d2, B:38:0x00d8, B:26:0x0065, B:40:0x00e1), top: B:45:0x0006 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void updateUidProcState(int r24, int r25) {
        /*
            Method dump skipped, instructions count: 230
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.AppOpsService.updateUidProcState(int, int):void");
    }

    public void shutdown() {
        Slog.w(TAG, "Writing app ops before shutdown...");
        boolean doWrite = false;
        synchronized (this) {
            if (this.mWriteScheduled) {
                this.mWriteScheduled = false;
                doWrite = true;
            }
        }
        if (doWrite) {
            writeState();
        }
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(Ops pkgOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        long elapsedNow = SystemClock.elapsedRealtime();
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int j = 0; j < pkgOps.size(); j++) {
                resOps.add(getOpEntryForResult(pkgOps.valueAt(j), elapsedNow));
            }
        } else {
            for (int i : ops) {
                Op curOp = pkgOps.get(i);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(getOpEntryForResult(curOp, elapsedNow));
                }
            }
        }
        return resOps;
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(SparseIntArray uidOps, int[] ops) {
        if (uidOps == null) {
            return null;
        }
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int j = 0; j < uidOps.size(); j++) {
                resOps.add(new AppOpsManager.OpEntry(uidOps.keyAt(j), uidOps.valueAt(j)));
            }
        } else {
            for (int j2 = 0; j2 < ops.length; j2++) {
                int index = uidOps.indexOfKey(ops[j2]);
                if (index >= 0) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(new AppOpsManager.OpEntry(uidOps.keyAt(j2), uidOps.valueAt(j2)));
                }
            }
        }
        return resOps;
    }

    private static AppOpsManager.OpEntry getOpEntryForResult(Op op, long elapsedNow) {
        if (op.running) {
            op.continuing(elapsedNow - op.startRealtime, op.uidState.state, 1);
        }
        AppOpsManager.OpEntry entry = new AppOpsManager.OpEntry(op.op, op.running, op.mode, op.mAccessTimes != null ? op.mAccessTimes.clone() : null, op.mRejectTimes != null ? op.mRejectTimes.clone() : null, op.mDurations != null ? op.mDurations.clone() : null, op.mProxyUids != null ? op.mProxyUids.clone() : null, op.mProxyPackageNames != null ? op.mProxyPackageNames.clone() : null);
        return entry;
    }

    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        this.mContext.enforcePermission("android.permission.GET_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), null);
        ArrayList<AppOpsManager.PackageOps> res = null;
        synchronized (this) {
            int uidStateCount = this.mUidStates.size();
            for (int i = 0; i < uidStateCount; i++) {
                UidState uidState = this.mUidStates.valueAt(i);
                if (uidState.pkgOps != null && !uidState.pkgOps.isEmpty()) {
                    ArrayMap<String, Ops> packages = uidState.pkgOps;
                    int packageCount = packages.size();
                    for (int j = 0; j < packageCount; j++) {
                        Ops pkgOps = packages.valueAt(j);
                        ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
                        if (resOps != null) {
                            if (res == null) {
                                res = new ArrayList<>();
                            }
                            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(pkgOps.packageName, pkgOps.uidState.uid, resOps);
                            res.add(resPackage);
                        }
                    }
                }
            }
        }
        return res;
    }

    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName, int[] ops) {
        this.mContext.enforcePermission("android.permission.GET_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), null);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return Collections.emptyList();
        }
        synchronized (this) {
            Ops pkgOps = getOpsRawLocked(uid, resolvedPackageName, false, false);
            if (pkgOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(pkgOps.packageName, pkgOps.uidState.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    public void getHistoricalOps(int uid, String packageName, List<String> opNames, long beginTimeMillis, long endTimeMillis, int flags, RemoteCallback callback) {
        new AppOpsManager.HistoricalOpsRequest.Builder(beginTimeMillis, endTimeMillis).setUid(uid).setPackageName(packageName).setOpNames(opNames).setFlags(flags).build();
        Preconditions.checkNotNull(callback, "callback cannot be null");
        this.mContext.enforcePermission("android.permission.GET_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), "getHistoricalOps");
        String[] opNamesArray = opNames != null ? (String[]) opNames.toArray(new String[opNames.size()]) : null;
        this.mHistoricalRegistry.getHistoricalOps(uid, packageName, opNamesArray, beginTimeMillis, endTimeMillis, flags, callback);
    }

    public void getHistoricalOpsFromDiskRaw(int uid, String packageName, List<String> opNames, long beginTimeMillis, long endTimeMillis, int flags, RemoteCallback callback) {
        new AppOpsManager.HistoricalOpsRequest.Builder(beginTimeMillis, endTimeMillis).setUid(uid).setPackageName(packageName).setOpNames(opNames).setFlags(flags).build();
        Preconditions.checkNotNull(callback, "callback cannot be null");
        this.mContext.enforcePermission("android.permission.MANAGE_APPOPS", Binder.getCallingPid(), Binder.getCallingUid(), "getHistoricalOps");
        String[] opNamesArray = opNames != null ? (String[]) opNames.toArray(new String[opNames.size()]) : null;
        this.mHistoricalRegistry.getHistoricalOpsFromDiskRaw(uid, packageName, opNamesArray, beginTimeMillis, endTimeMillis, flags, callback);
    }

    public void reloadNonHistoricalState() {
        this.mContext.enforcePermission("android.permission.MANAGE_APPOPS", Binder.getCallingPid(), Binder.getCallingUid(), "reloadNonHistoricalState");
        writeState();
        readState();
    }

    public List<AppOpsManager.PackageOps> getUidOps(int uid, int[] ops) {
        this.mContext.enforcePermission("android.permission.GET_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(uidState.opModes, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps((String) null, uidState.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    private void pruneOp(Op op, int uid, String packageName) {
        Ops ops;
        UidState uidState;
        ArrayMap<String, Ops> pkgOps;
        if (!op.hasAnyTime() && (ops = getOpsRawLocked(uid, packageName, false, false)) != null) {
            ops.remove(op.op);
            if (ops.size() <= 0 && (pkgOps = (uidState = ops.uidState).pkgOps) != null) {
                pkgOps.remove(ops.packageName);
                if (pkgOps.isEmpty()) {
                    uidState.pkgOps = null;
                }
                if (uidState.isDefault()) {
                    this.mUidStates.remove(uid);
                }
            }
        }
    }

    private void enforceManageAppOpsModes(int callingPid, int callingUid, int targetUid) {
        if (callingPid == Process.myPid()) {
            return;
        }
        int callingUser = UserHandle.getUserId(callingUid);
        synchronized (this) {
            if (this.mProfileOwners == null || this.mProfileOwners.get(callingUser, -1) != callingUid || targetUid < 0 || callingUser != UserHandle.getUserId(targetUid)) {
                this.mContext.enforcePermission("android.permission.MANAGE_APP_OPS_MODES", Binder.getCallingPid(), Binder.getCallingUid(), null);
            }
        }
    }

    public void setUidMode(int code, int uid, int mode) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingOp(code);
        int code2 = AppOpsManager.opToSwitch(code);
        synchronized (this) {
            int defaultMode = AppOpsManager.opToDefaultMode(code2);
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                if (mode == defaultMode) {
                    return;
                }
                uidState = new UidState(uid);
                uidState.opModes = new SparseIntArray();
                uidState.opModes.put(code2, mode);
                this.mUidStates.put(uid, uidState);
                scheduleWriteLocked();
            } else if (uidState.opModes == null) {
                if (mode != defaultMode) {
                    uidState.opModes = new SparseIntArray();
                    uidState.opModes.put(code2, mode);
                    scheduleWriteLocked();
                }
            } else if (uidState.opModes.indexOfKey(code2) >= 0 && uidState.opModes.get(code2) == mode) {
                return;
            } else {
                if (mode == defaultMode) {
                    uidState.opModes.delete(code2);
                    if (uidState.opModes.size() <= 0) {
                        uidState.opModes = null;
                    }
                } else {
                    uidState.opModes.put(code2, mode);
                }
                scheduleWriteLocked();
            }
            uidState.evalForegroundOps(this.mOpModeWatchers);
            notifyOpChangedForAllPkgsInUid(code2, uid, false);
            notifyOpChangedSync(code2, uid, null, mode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyOpChangedForAllPkgsInUid(int code, int uid, boolean onlyForeground) {
        String[] uidPackageNames = getPackagesForUid(uid);
        ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs = null;
        synchronized (this) {
            try {
                try {
                    ArraySet<ModeCallback> callbacks = this.mOpModeWatchers.get(code);
                    if (callbacks != null) {
                        try {
                            int callbackCount = callbacks.size();
                            for (int i = 0; i < callbackCount; i++) {
                                ModeCallback callback = callbacks.valueAt(i);
                                if (!onlyForeground || (callback.mFlags & 1) != 0) {
                                    ArraySet<String> changedPackages = new ArraySet<>();
                                    Collections.addAll(changedPackages, uidPackageNames);
                                    if (callbackSpecs == null) {
                                        callbackSpecs = new ArrayMap<>();
                                    }
                                    callbackSpecs.put(callback, changedPackages);
                                }
                            }
                        } catch (Throwable th) {
                            th = th;
                            while (true) {
                                try {
                                    break;
                                } catch (Throwable th2) {
                                    th = th2;
                                }
                            }
                            throw th;
                        }
                    }
                    ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs2 = callbackSpecs;
                    for (String uidPackageName : uidPackageNames) {
                        try {
                            ArraySet<ModeCallback> callbacks2 = this.mPackageModeWatchers.get(uidPackageName);
                            if (callbacks2 != null) {
                                ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs3 = callbackSpecs2 == null ? new ArrayMap<>() : callbackSpecs2;
                                try {
                                    int callbackCount2 = callbacks2.size();
                                    for (int i2 = 0; i2 < callbackCount2; i2++) {
                                        ModeCallback callback2 = callbacks2.valueAt(i2);
                                        if (!onlyForeground || (callback2.mFlags & 1) != 0) {
                                            ArraySet<String> changedPackages2 = callbackSpecs3.get(callback2);
                                            if (changedPackages2 == null) {
                                                changedPackages2 = new ArraySet<>();
                                                callbackSpecs3.put(callback2, changedPackages2);
                                            }
                                            changedPackages2.add(uidPackageName);
                                        }
                                    }
                                    callbackSpecs2 = callbackSpecs3;
                                } catch (Throwable th3) {
                                    th = th3;
                                    while (true) {
                                        break;
                                        break;
                                    }
                                    throw th;
                                }
                            }
                        } catch (Throwable th4) {
                            th = th4;
                        }
                    }
                    try {
                        if (callbackSpecs2 == null) {
                            return;
                        }
                        int i3 = 0;
                        while (i3 < callbackSpecs2.size()) {
                            ModeCallback callback3 = callbackSpecs2.keyAt(i3);
                            ArraySet<String> reportedPackageNames = callbackSpecs2.valueAt(i3);
                            if (reportedPackageNames == null) {
                                this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$FYLTtxqrHmv8Y5UdZ9ybXKsSJhs.INSTANCE, this, callback3, Integer.valueOf(code), Integer.valueOf(uid), (Object) null));
                            } else {
                                int reportedPackageCount = reportedPackageNames.size();
                                int j = 0;
                                while (j < reportedPackageCount) {
                                    String reportedPackageName = reportedPackageNames.valueAt(j);
                                    this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$FYLTtxqrHmv8Y5UdZ9ybXKsSJhs.INSTANCE, this, callback3, Integer.valueOf(code), Integer.valueOf(uid), reportedPackageName));
                                    j++;
                                    uidPackageNames = uidPackageNames;
                                }
                            }
                            i3++;
                            uidPackageNames = uidPackageNames;
                        }
                    } catch (Throwable th5) {
                        th = th5;
                        while (true) {
                            break;
                            break;
                        }
                        throw th;
                    }
                } catch (Throwable th6) {
                    th = th6;
                }
            } catch (Throwable th7) {
                th = th7;
            }
        }
    }

    private void notifyOpChangedSync(int code, int uid, String packageName, int mode) {
        StorageManagerInternal storageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
        if (storageManagerInternal != null) {
            storageManagerInternal.onAppOpsChanged(code, uid, packageName, mode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setAllPkgModesToDefault(int code, int uid) {
        int defaultMode;
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                return;
            }
            ArrayMap<String, Ops> pkgOps = uidState.pkgOps;
            if (pkgOps == null) {
                return;
            }
            boolean scheduleWrite = false;
            int numPkgs = pkgOps.size();
            for (int pkgNum = 0; pkgNum < numPkgs; pkgNum++) {
                Ops ops = pkgOps.valueAt(pkgNum);
                Op op = ops.get(code);
                if (op != null && op.mode != (defaultMode = AppOpsManager.opToDefaultMode(code))) {
                    op.mode = defaultMode;
                    scheduleWrite = true;
                }
            }
            if (scheduleWrite) {
                scheduleWriteLocked();
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:33:0x0086  */
    /* JADX WARN: Removed duplicated region for block: B:34:0x00a0  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void setMode(int r17, int r18, java.lang.String r19, int r20) {
        /*
            r16 = this;
            r12 = r16
            r13 = r18
            r14 = r19
            r15 = r20
            int r0 = android.os.Binder.getCallingPid()
            int r1 = android.os.Binder.getCallingUid()
            r12.enforceManageAppOpsModes(r0, r1, r13)
            r16.verifyIncomingOp(r17)
            r7 = 0
            int r11 = android.app.AppOpsManager.opToSwitch(r17)
            boolean r5 = r12.verifyAndGetIsPrivileged(r13, r14)     // Catch: java.lang.SecurityException -> Laf
            monitor-enter(r16)
            r0 = 0
            com.android.server.appop.AppOpsService$UidState r0 = r12.getUidStateLocked(r13, r0)     // Catch: java.lang.Throwable -> La9
            r6 = 1
            r1 = r16
            r2 = r11
            r3 = r18
            r4 = r19
            com.android.server.appop.AppOpsService$Op r1 = r1.getOpLocked(r2, r3, r4, r5, r6)     // Catch: java.lang.Throwable -> La9
            if (r1 == 0) goto L82
            int r2 = com.android.server.appop.AppOpsService.Op.access$100(r1)     // Catch: java.lang.Throwable -> L7f
            if (r2 == r15) goto L82
            com.android.server.appop.AppOpsService.Op.access$102(r1, r15)     // Catch: java.lang.Throwable -> L7f
            if (r0 == 0) goto L44
            android.util.SparseArray<android.util.ArraySet<com.android.server.appop.AppOpsService$ModeCallback>> r2 = r12.mOpModeWatchers     // Catch: java.lang.Throwable -> L7f
            r0.evalForegroundOps(r2)     // Catch: java.lang.Throwable -> L7f
        L44:
            android.util.SparseArray<android.util.ArraySet<com.android.server.appop.AppOpsService$ModeCallback>> r2 = r12.mOpModeWatchers     // Catch: java.lang.Throwable -> L7f
            java.lang.Object r2 = r2.get(r11)     // Catch: java.lang.Throwable -> L7f
            android.util.ArraySet r2 = (android.util.ArraySet) r2     // Catch: java.lang.Throwable -> L7f
            if (r2 == 0) goto L59
            if (r7 != 0) goto L56
            android.util.ArraySet r3 = new android.util.ArraySet     // Catch: java.lang.Throwable -> L7f
            r3.<init>()     // Catch: java.lang.Throwable -> L7f
            r7 = r3
        L56:
            r7.addAll(r2)     // Catch: java.lang.Throwable -> L7f
        L59:
            android.util.ArrayMap<java.lang.String, android.util.ArraySet<com.android.server.appop.AppOpsService$ModeCallback>> r3 = r12.mPackageModeWatchers     // Catch: java.lang.Throwable -> L7f
            java.lang.Object r3 = r3.get(r14)     // Catch: java.lang.Throwable -> L7f
            android.util.ArraySet r3 = (android.util.ArraySet) r3     // Catch: java.lang.Throwable -> L7f
            r2 = r3
            if (r2 == 0) goto L6f
            if (r7 != 0) goto L6c
            android.util.ArraySet r3 = new android.util.ArraySet     // Catch: java.lang.Throwable -> L7f
            r3.<init>()     // Catch: java.lang.Throwable -> L7f
            r7 = r3
        L6c:
            r7.addAll(r2)     // Catch: java.lang.Throwable -> L7f
        L6f:
            int r3 = r1.op     // Catch: java.lang.Throwable -> L7f
            int r3 = android.app.AppOpsManager.opToDefaultMode(r3)     // Catch: java.lang.Throwable -> L7f
            if (r15 != r3) goto L7a
            r12.pruneOp(r1, r13, r14)     // Catch: java.lang.Throwable -> L7f
        L7a:
            r16.scheduleFastWriteLocked()     // Catch: java.lang.Throwable -> L7f
            r1 = r7
            goto L83
        L7f:
            r0 = move-exception
            r2 = r11
            goto Lab
        L82:
            r1 = r7
        L83:
            monitor-exit(r16)     // Catch: java.lang.Throwable -> La5
            if (r1 == 0) goto La0
            android.os.Handler r0 = r12.mHandler
            com.android.server.appop.-$$Lambda$AppOpsService$NDUi03ZZuuR42-RDEIQ0UELKycc r6 = new com.android.internal.util.function.QuintConsumer() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$NDUi03ZZuuR42-RDEIQ0UELKycc
                static {
                    /*
                        com.android.server.appop.-$$Lambda$AppOpsService$NDUi03ZZuuR42-RDEIQ0UELKycc r0 = new com.android.server.appop.-$$Lambda$AppOpsService$NDUi03ZZuuR42-RDEIQ0UELKycc
                        r0.<init>()
                        
                        // error: 0x0005: SPUT  (r0 I:com.android.server.appop.-$$Lambda$AppOpsService$NDUi03ZZuuR42-RDEIQ0UELKycc) com.android.server.appop.-$$Lambda$AppOpsService$NDUi03ZZuuR42-RDEIQ0UELKycc.INSTANCE com.android.server.appop.-$$Lambda$AppOpsService$NDUi03ZZuuR42-RDEIQ0UELKycc
                        return
                    */
                    throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.$$Lambda$AppOpsService$NDUi03ZZuuR42RDEIQ0UELKycc.<clinit>():void");
                }

                {
                    /*
                        r0 = this;
                        r0.<init>()
                        return
                    */
                    throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.$$Lambda$AppOpsService$NDUi03ZZuuR42RDEIQ0UELKycc.<init>():void");
                }

                public final void accept(java.lang.Object r1, java.lang.Object r2, java.lang.Object r3, java.lang.Object r4, java.lang.Object r5) {
                    /*
                        r0 = this;
                        com.android.server.appop.AppOpsService r1 = (com.android.server.appop.AppOpsService) r1
                        android.util.ArraySet r2 = (android.util.ArraySet) r2
                        java.lang.Integer r3 = (java.lang.Integer) r3
                        int r3 = r3.intValue()
                        java.lang.Integer r4 = (java.lang.Integer) r4
                        int r4 = r4.intValue()
                        java.lang.String r5 = (java.lang.String) r5
                        com.android.server.appop.AppOpsService.m15lambda$NDUi03ZZuuR42RDEIQ0UELKycc(r1, r2, r3, r4, r5)
                        return
                    */
                    throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.$$Lambda$AppOpsService$NDUi03ZZuuR42RDEIQ0UELKycc.accept(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object):void");
                }
            }
            java.lang.Integer r9 = java.lang.Integer.valueOf(r11)
            java.lang.Integer r10 = java.lang.Integer.valueOf(r18)
            r7 = r16
            r8 = r1
            r2 = r11
            r11 = r19
            android.os.Message r3 = com.android.internal.util.function.pooled.PooledLambda.obtainMessage(r6, r7, r8, r9, r10, r11)
            r0.sendMessage(r3)
            goto La1
        La0:
            r2 = r11
        La1:
            r12.notifyOpChangedSync(r2, r13, r14, r15)
            return
        La5:
            r0 = move-exception
            r2 = r11
            r7 = r1
            goto Lab
        La9:
            r0 = move-exception
            r2 = r11
        Lab:
            monitor-exit(r16)     // Catch: java.lang.Throwable -> Lad
            throw r0
        Lad:
            r0 = move-exception
            goto Lab
        Laf:
            r0 = move-exception
            r2 = r11
            r1 = r0
            r0 = r1
            java.lang.String r1 = "AppOps"
            java.lang.String r3 = "Cannot setMode"
            android.util.Slog.e(r1, r3, r0)
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.AppOpsService.setMode(int, int, java.lang.String, int):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyOpChanged(ArraySet<ModeCallback> callbacks, int code, int uid, String packageName) {
        for (int i = 0; i < callbacks.size(); i++) {
            ModeCallback callback = callbacks.valueAt(i);
            notifyOpChanged(callback, code, uid, packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyOpChanged(ModeCallback callback, int code, int uid, String packageName) {
        if (uid != -2 && callback.mWatchingUid >= 0 && callback.mWatchingUid != uid) {
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            callback.mCallback.opChanged(code, uid, packageName);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
        Binder.restoreCallingIdentity(identity);
    }

    private static HashMap<ModeCallback, ArrayList<ChangeRec>> addCallbacks(HashMap<ModeCallback, ArrayList<ChangeRec>> callbacks, int op, int uid, String packageName, ArraySet<ModeCallback> cbs) {
        if (cbs == null) {
            return callbacks;
        }
        if (callbacks == null) {
            callbacks = new HashMap<>();
        }
        boolean duplicate = false;
        int N = cbs.size();
        for (int i = 0; i < N; i++) {
            ModeCallback cb = cbs.valueAt(i);
            ArrayList<ChangeRec> reports = callbacks.get(cb);
            if (reports == null) {
                reports = new ArrayList<>();
                callbacks.put(cb, reports);
            } else {
                int reportCount = reports.size();
                int j = 0;
                while (true) {
                    if (j >= reportCount) {
                        break;
                    }
                    ChangeRec report = reports.get(j);
                    if (report.op != op || !report.pkg.equals(packageName)) {
                        j++;
                    } else {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (!duplicate) {
                reports.add(new ChangeRec(op, uid, packageName));
            }
        }
        return callbacks;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class ChangeRec {
        final int op;
        final String pkg;
        final int uid;

        ChangeRec(int _op, int _uid, String _pkg) {
            this.op = _op;
            this.uid = _uid;
            this.pkg = _pkg;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:10:0x0031  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void resetAllModes(int r23, java.lang.String r24) {
        /*
            Method dump skipped, instructions count: 656
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.AppOpsService.resetAllModes(int, java.lang.String):void");
    }

    private void evalAllForegroundOpsLocked() {
        for (int uidi = this.mUidStates.size() - 1; uidi >= 0; uidi--) {
            UidState uidState = this.mUidStates.valueAt(uidi);
            if (uidState.foregroundOps != null) {
                uidState.evalForegroundOps(this.mOpModeWatchers);
            }
        }
    }

    public void startWatchingMode(int op, String packageName, IAppOpsCallback callback) {
        startWatchingModeWithFlags(op, packageName, 0, callback);
    }

    public void startWatchingModeWithFlags(int op, String packageName, int flags, IAppOpsCallback callback) {
        int opToSwitch;
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        Preconditions.checkArgumentInRange(op, -1, 90, "Invalid op code: " + op);
        if (callback == null) {
            return;
        }
        synchronized (this) {
            try {
                if (op == -1) {
                    opToSwitch = op;
                } else {
                    try {
                        opToSwitch = AppOpsManager.opToSwitch(op);
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
                int op2 = opToSwitch;
                ModeCallback cb = this.mModeWatchers.get(callback.asBinder());
                if (cb == null) {
                    cb = new ModeCallback(callback, -1, flags, callingUid, callingPid);
                    this.mModeWatchers.put(callback.asBinder(), cb);
                }
                if (op2 != -1) {
                    ArraySet<ModeCallback> cbs = this.mOpModeWatchers.get(op2);
                    if (cbs == null) {
                        cbs = new ArraySet<>();
                        this.mOpModeWatchers.put(op2, cbs);
                    }
                    cbs.add(cb);
                }
                if (packageName != null) {
                    ArraySet<ModeCallback> cbs2 = this.mPackageModeWatchers.get(packageName);
                    if (cbs2 == null) {
                        cbs2 = new ArraySet<>();
                        this.mPackageModeWatchers.put(packageName, cbs2);
                    }
                    cbs2.add(cb);
                }
                evalAllForegroundOpsLocked();
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    public void stopWatchingMode(IAppOpsCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (this) {
            ModeCallback cb = this.mModeWatchers.remove(callback.asBinder());
            if (cb != null) {
                cb.unlinkToDeath();
                for (int i = this.mOpModeWatchers.size() - 1; i >= 0; i--) {
                    ArraySet<ModeCallback> cbs = this.mOpModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        this.mOpModeWatchers.removeAt(i);
                    }
                }
                for (int i2 = this.mPackageModeWatchers.size() - 1; i2 >= 0; i2--) {
                    ArraySet<ModeCallback> cbs2 = this.mPackageModeWatchers.valueAt(i2);
                    cbs2.remove(cb);
                    if (cbs2.size() <= 0) {
                        this.mPackageModeWatchers.removeAt(i2);
                    }
                }
            }
            evalAllForegroundOpsLocked();
        }
    }

    public IBinder getToken(IBinder clientToken) {
        ClientState cs;
        synchronized (this) {
            cs = this.mClients.get(clientToken);
            if (cs == null) {
                cs = new ClientState(clientToken);
                this.mClients.put(clientToken, cs);
            }
        }
        return cs;
    }

    public AppOpsManagerInternal.CheckOpsDelegate getAppOpsServiceDelegate() {
        AppOpsManagerInternal.CheckOpsDelegate checkOpsDelegate;
        synchronized (this) {
            checkOpsDelegate = this.mCheckOpsDelegate;
        }
        return checkOpsDelegate;
    }

    public void setAppOpsServiceDelegate(AppOpsManagerInternal.CheckOpsDelegate delegate) {
        synchronized (this) {
            this.mCheckOpsDelegate = delegate;
        }
    }

    public int checkOperationRaw(int code, int uid, String packageName) {
        return checkOperationInternal(code, uid, packageName, true);
    }

    public int checkOperation(int code, int uid, String packageName) {
        return checkOperationInternal(code, uid, packageName, false);
    }

    private int checkOperationInternal(int code, int uid, String packageName, boolean raw) {
        AppOpsManagerInternal.CheckOpsDelegate delegate;
        synchronized (this) {
            delegate = this.mCheckOpsDelegate;
        }
        if (delegate == null) {
            return checkOperationImpl(code, uid, packageName, raw);
        }
        return delegate.checkOperation(code, uid, packageName, raw, new QuadFunction() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$gQy7GOuCV6GbjQtdNhNG6xld8I4
            public final Object apply(Object obj, Object obj2, Object obj3, Object obj4) {
                int checkOperationImpl;
                checkOperationImpl = AppOpsService.this.checkOperationImpl(((Integer) obj).intValue(), ((Integer) obj2).intValue(), (String) obj3, ((Boolean) obj4).booleanValue());
                return Integer.valueOf(checkOperationImpl);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkOperationImpl(int code, int uid, String packageName, boolean raw) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return 1;
        }
        return checkOperationUnchecked(code, uid, resolvedPackageName, raw);
    }

    private int checkOperationUnchecked(int code, int uid, String packageName, boolean raw) {
        if (isOpRestrictedDueToSuspend(code, packageName, uid)) {
            return 1;
        }
        try {
            boolean isPrivileged = verifyAndGetIsPrivileged(uid, packageName);
            synchronized (this) {
                if (isOpRestrictedLocked(uid, code, packageName, isPrivileged)) {
                    return 1;
                }
                int code2 = AppOpsManager.opToSwitch(code);
                UidState uidState = getUidStateLocked(uid, false);
                if (uidState != null && uidState.opModes != null && uidState.opModes.indexOfKey(code2) >= 0) {
                    int rawMode = uidState.opModes.get(code2);
                    return raw ? rawMode : uidState.evalMode(code2, rawMode);
                }
                Op op = getOpLocked(code2, uid, packageName, false, false);
                if (op == null) {
                    return AppOpsManager.opToDefaultMode(code2);
                }
                return raw ? op.mode : op.evalMode();
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "checkOperation failed for uid " + uid + " packageName " + packageName);
            return AppOpsManager.opToDefaultMode(code);
        }
    }

    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        AppOpsManagerInternal.CheckOpsDelegate delegate;
        synchronized (this) {
            delegate = this.mCheckOpsDelegate;
        }
        if (delegate == null) {
            return checkAudioOperationImpl(code, usage, uid, packageName);
        }
        return delegate.checkAudioOperation(code, usage, uid, packageName, new QuadFunction() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$mfUWTdGevxEoIUv1cEPEFG0qAaI
            public final Object apply(Object obj, Object obj2, Object obj3, Object obj4) {
                int checkAudioOperationImpl;
                checkAudioOperationImpl = AppOpsService.this.checkAudioOperationImpl(((Integer) obj).intValue(), ((Integer) obj2).intValue(), ((Integer) obj3).intValue(), (String) obj4);
                return Integer.valueOf(checkAudioOperationImpl);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkAudioOperationImpl(int code, int usage, int uid, String packageName) {
        boolean suspended;
        try {
            suspended = isPackageSuspendedForUser(packageName, uid);
        } catch (IllegalArgumentException e) {
            suspended = false;
        }
        if (suspended) {
            Slog.i(TAG, "Audio disabled for suspended package=" + packageName + " for uid=" + uid);
            return 1;
        }
        synchronized (this) {
            int mode = checkRestrictionLocked(code, usage, uid, packageName);
            return mode != 0 ? mode : checkOperation(code, uid, packageName);
        }
    }

    private boolean isPackageSuspendedForUser(String pkg, int uid) {
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                return AppGlobals.getPackageManager().isPackageSuspendedForUser(pkg, UserHandle.getUserId(uid));
            } catch (RemoteException e) {
                throw new SecurityException("Could not talk to package manager service");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private int checkRestrictionLocked(int code, int usage, int uid, String packageName) {
        Restriction r;
        SparseArray<Restriction> usageRestrictions = this.mAudioRestrictions.get(code);
        if (usageRestrictions != null && (r = usageRestrictions.get(usage)) != null && !r.exceptionPackages.contains(packageName)) {
            return r.mode;
        }
        return 0;
    }

    public void setAudioRestriction(int code, int usage, int uid, int mode, String[] exceptionPackages) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        synchronized (this) {
            SparseArray<Restriction> usageRestrictions = this.mAudioRestrictions.get(code);
            if (usageRestrictions == null) {
                usageRestrictions = new SparseArray<>();
                this.mAudioRestrictions.put(code, usageRestrictions);
            }
            usageRestrictions.remove(usage);
            if (mode != 0) {
                Restriction r = new Restriction();
                r.mode = mode;
                if (exceptionPackages != null) {
                    int N = exceptionPackages.length;
                    r.exceptionPackages = new ArraySet<>(N);
                    for (String pkg : exceptionPackages) {
                        if (pkg != null) {
                            r.exceptionPackages.add(pkg.trim());
                        }
                    }
                }
                usageRestrictions.put(usage, r);
            }
        }
        this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$GUeKjlbzT65s86vaxy5gvOajuhw.INSTANCE, this, Integer.valueOf(code), -2));
    }

    public int checkPackage(int uid, String packageName) {
        Preconditions.checkNotNull(packageName);
        try {
            verifyAndGetIsPrivileged(uid, packageName);
            return 0;
        } catch (SecurityException e) {
            return 2;
        }
    }

    public int noteProxyOperation(int code, int proxyUid, String proxyPackageName, int proxiedUid, String proxiedPackageName) {
        verifyIncomingUid(proxyUid);
        verifyIncomingOp(code);
        String resolveProxyPackageName = resolvePackageName(proxyUid, proxyPackageName);
        if (resolveProxyPackageName == null) {
            return 1;
        }
        boolean isProxyTrusted = this.mContext.checkPermission("android.permission.UPDATE_APP_OPS_STATS", -1, proxyUid) == 0;
        int proxyFlags = isProxyTrusted ? 2 : 4;
        int proxyMode = noteOperationUnchecked(code, proxyUid, resolveProxyPackageName, -1, null, proxyFlags);
        if (proxyMode == 0 && Binder.getCallingUid() != proxiedUid) {
            String resolveProxiedPackageName = resolvePackageName(proxiedUid, proxiedPackageName);
            if (resolveProxiedPackageName == null) {
                return 1;
            }
            int proxiedFlags = isProxyTrusted ? 8 : 16;
            return noteOperationUnchecked(code, proxiedUid, resolveProxiedPackageName, proxyUid, resolveProxyPackageName, proxiedFlags);
        }
        return proxyMode;
    }

    public int noteOperation(int code, int uid, String packageName) {
        AppOpsManagerInternal.CheckOpsDelegate delegate;
        synchronized (this) {
            delegate = this.mCheckOpsDelegate;
        }
        if (delegate == null) {
            return noteOperationImpl(code, uid, packageName);
        }
        return delegate.noteOperation(code, uid, packageName, new TriFunction() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$hqd76gFlOJ1gAuDYDPVUaSkXjTc
            public final Object apply(Object obj, Object obj2, Object obj3) {
                int noteOperationImpl;
                noteOperationImpl = AppOpsService.this.noteOperationImpl(((Integer) obj).intValue(), ((Integer) obj2).intValue(), (String) obj3);
                return Integer.valueOf(noteOperationImpl);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int noteOperationImpl(int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return 1;
        }
        return noteOperationUnchecked(code, uid, resolvedPackageName, -1, null, 1);
    }

    private int noteOperationUnchecked(int code, int uid, String packageName, int proxyUid, String proxyPackageName, int flags) {
        UidState uidState;
        Op op;
        try {
            boolean isPrivileged = verifyAndGetIsPrivileged(uid, packageName);
            synchronized (this) {
                try {
                    try {
                        Ops ops = getOpsRawLocked(uid, packageName, isPrivileged, true);
                        try {
                            if (ops == null) {
                                scheduleOpNotedIfNeededLocked(code, uid, packageName, 1);
                                return 2;
                            }
                            Op op2 = getOpLocked(ops, code, true);
                            if (isOpRestrictedLocked(uid, code, packageName, isPrivileged)) {
                                scheduleOpNotedIfNeededLocked(code, uid, packageName, 1);
                                return 1;
                            }
                            UidState uidState2 = ops.uidState;
                            if (op2.running) {
                                AppOpsManager.OpEntry entry = new AppOpsManager.OpEntry(op2.op, op2.running, op2.mode, op2.mAccessTimes, op2.mRejectTimes, op2.mDurations, op2.mProxyUids, op2.mProxyPackageNames);
                                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName + " code " + code + " time=" + entry.getLastAccessTime(uidState2.state, uidState2.state, flags) + " duration=" + entry.getLastDuration(uidState2.state, uidState2.state, flags));
                            }
                            int switchCode = AppOpsManager.opToSwitch(code);
                            if (uidState2.opModes != null && uidState2.opModes.indexOfKey(switchCode) >= 0) {
                                int uidMode = uidState2.evalMode(code, uidState2.opModes.get(switchCode));
                                if (uidMode != 0) {
                                    op2.rejected(System.currentTimeMillis(), proxyUid, proxyPackageName, uidState2.state, flags);
                                    this.mHistoricalRegistry.incrementOpRejected(code, uid, packageName, uidState2.state, flags);
                                    scheduleOpNotedIfNeededLocked(code, uid, packageName, uidMode);
                                    return uidMode;
                                }
                                uidState = uidState2;
                                op = op2;
                            } else {
                                Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op2;
                                int mode = switchOp.evalMode();
                                if (switchOp.mode != 0) {
                                    op2.rejected(System.currentTimeMillis(), proxyUid, proxyPackageName, uidState2.state, flags);
                                    this.mHistoricalRegistry.incrementOpRejected(code, uid, packageName, uidState2.state, flags);
                                    scheduleOpNotedIfNeededLocked(code, uid, packageName, mode);
                                    return mode;
                                }
                                uidState = uidState2;
                                op = op2;
                            }
                            op.accessed(System.currentTimeMillis(), proxyUid, proxyPackageName, uidState.state, flags);
                            this.mHistoricalRegistry.incrementOpAccessedCount(op.op, uid, packageName, uidState.state, flags);
                            scheduleOpNotedIfNeededLocked(code, uid, packageName, 0);
                            return 0;
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "noteOperation", e);
            return 2;
        }
    }

    public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
        SparseArray<ActiveCallback> callbacks;
        int watchedUid = -1;
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WATCH_APPOPS") != 0) {
            watchedUid = callingUid;
        }
        if (ops != null) {
            Preconditions.checkArrayElementsInRange(ops, 0, 90, "Invalid op code in: " + Arrays.toString(ops));
        }
        if (callback == null) {
            return;
        }
        synchronized (this) {
            SparseArray<ActiveCallback> callbacks2 = this.mActiveWatchers.get(callback.asBinder());
            if (callbacks2 != null) {
                callbacks = callbacks2;
            } else {
                SparseArray<ActiveCallback> callbacks3 = new SparseArray<>();
                this.mActiveWatchers.put(callback.asBinder(), callbacks3);
                callbacks = callbacks3;
            }
            ActiveCallback activeCallback = new ActiveCallback(callback, watchedUid, callingUid, callingPid);
            for (int op : ops) {
                callbacks.put(op, activeCallback);
            }
        }
    }

    public void stopWatchingActive(IAppOpsActiveCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (this) {
            SparseArray<ActiveCallback> activeCallbacks = this.mActiveWatchers.remove(callback.asBinder());
            if (activeCallbacks == null) {
                return;
            }
            int callbackCount = activeCallbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                activeCallbacks.valueAt(i).destroy();
            }
        }
    }

    public void startWatchingNoted(int[] ops, IAppOpsNotedCallback callback) {
        SparseArray<NotedCallback> callbacks;
        int watchedUid = -1;
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WATCH_APPOPS") != 0) {
            watchedUid = callingUid;
        }
        Preconditions.checkArgument(!ArrayUtils.isEmpty(ops), "Ops cannot be null or empty");
        Preconditions.checkArrayElementsInRange(ops, 0, 90, "Invalid op code in: " + Arrays.toString(ops));
        Preconditions.checkNotNull(callback, "Callback cannot be null");
        synchronized (this) {
            SparseArray<NotedCallback> callbacks2 = this.mNotedWatchers.get(callback.asBinder());
            if (callbacks2 != null) {
                callbacks = callbacks2;
            } else {
                SparseArray<NotedCallback> callbacks3 = new SparseArray<>();
                this.mNotedWatchers.put(callback.asBinder(), callbacks3);
                callbacks = callbacks3;
            }
            NotedCallback notedCallback = new NotedCallback(callback, watchedUid, callingUid, callingPid);
            for (int op : ops) {
                callbacks.put(op, notedCallback);
            }
        }
    }

    public void stopWatchingNoted(IAppOpsNotedCallback callback) {
        Preconditions.checkNotNull(callback, "Callback cannot be null");
        synchronized (this) {
            SparseArray<NotedCallback> notedCallbacks = this.mNotedWatchers.remove(callback.asBinder());
            if (notedCallbacks == null) {
                return;
            }
            int callbackCount = notedCallbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                notedCallbacks.valueAt(i).destroy();
            }
        }
    }

    public int startOperation(IBinder token, int code, int uid, String packageName, boolean startIfModeDefault) {
        UidState uidState;
        Op op;
        int switchCode;
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return 1;
        }
        ClientState client = (ClientState) token;
        try {
            boolean isPrivileged = verifyAndGetIsPrivileged(uid, packageName);
            synchronized (this) {
                try {
                    try {
                        Ops ops = getOpsRawLocked(uid, resolvedPackageName, isPrivileged, true);
                        try {
                            if (ops == null) {
                                return 2;
                            }
                            Op op2 = getOpLocked(ops, code, true);
                            if (isOpRestrictedLocked(uid, code, resolvedPackageName, isPrivileged)) {
                                return 1;
                            }
                            int switchCode2 = AppOpsManager.opToSwitch(code);
                            UidState uidState2 = ops.uidState;
                            int opCode = op2.op;
                            if (uidState2.opModes != null && uidState2.opModes.indexOfKey(switchCode2) >= 0) {
                                int uidMode = uidState2.evalMode(code, uidState2.opModes.get(switchCode2));
                                if (uidMode != 0) {
                                    if (startIfModeDefault && uidMode == 3) {
                                        switchCode = switchCode2;
                                        uidState = uidState2;
                                    }
                                    op2.rejected(System.currentTimeMillis(), -1, null, uidState2.state, 1);
                                    this.mHistoricalRegistry.incrementOpRejected(opCode, uid, packageName, uidState2.state, 1);
                                    return uidMode;
                                }
                                switchCode = switchCode2;
                                uidState = uidState2;
                                op = op2;
                            } else {
                                uidState = uidState2;
                                Op switchOp = switchCode2 != code ? getOpLocked(ops, switchCode2, true) : op2;
                                int mode = switchOp.evalMode();
                                if (mode != 0) {
                                    if (startIfModeDefault && mode == 3) {
                                        op = op2;
                                    }
                                    op2.rejected(System.currentTimeMillis(), -1, null, uidState.state, 1);
                                    this.mHistoricalRegistry.incrementOpRejected(opCode, uid, packageName, uidState.state, 1);
                                    return mode;
                                }
                                op = op2;
                            }
                            if (op.startNesting == 0) {
                                op.startRealtime = SystemClock.elapsedRealtime();
                                op.started(System.currentTimeMillis(), uidState.state, 1);
                                this.mHistoricalRegistry.incrementOpAccessedCount(opCode, uid, packageName, uidState.state, 1);
                                scheduleOpActiveChangedIfNeededLocked(code, uid, packageName, true);
                            }
                            op.startNesting++;
                            uidState.startNesting++;
                            if (client.mStartedOps != null) {
                                client.mStartedOps.add(op);
                            }
                            return 0;
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "startOperation", e);
            return 2;
        }
    }

    public void finishOperation(IBinder token, int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null || !(token instanceof ClientState)) {
            return;
        }
        ClientState client = (ClientState) token;
        try {
            boolean isPrivileged = verifyAndGetIsPrivileged(uid, packageName);
            synchronized (this) {
                Op op = getOpLocked(code, uid, resolvedPackageName, isPrivileged, true);
                if (op == null) {
                    return;
                }
                if (!client.mStartedOps.remove(op)) {
                    long identity = Binder.clearCallingIdentity();
                    if (((PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class)).getPackageUid(resolvedPackageName, 0, UserHandle.getUserId(uid)) < 0) {
                        Slog.i(TAG, "Finishing op=" + AppOpsManager.opToName(code) + " for non-existing package=" + resolvedPackageName + " in uid=" + uid);
                        Binder.restoreCallingIdentity(identity);
                        return;
                    }
                    Binder.restoreCallingIdentity(identity);
                    Slog.wtf(TAG, "Operation not started: uid=" + op.uidState.uid + " pkg=" + op.packageName + " op=" + AppOpsManager.opToName(op.op));
                    return;
                }
                finishOperationLocked(op, false);
                if (op.startNesting <= 0) {
                    scheduleOpActiveChangedIfNeededLocked(code, uid, packageName, false);
                }
            }
        } catch (SecurityException e) {
            Slog.e(TAG, "Cannot finishOperation", e);
        }
    }

    private void scheduleOpActiveChangedIfNeededLocked(int code, int uid, String packageName, boolean active) {
        ArraySet<ActiveCallback> dispatchedCallbacks = null;
        int callbackListCount = this.mActiveWatchers.size();
        for (int i = 0; i < callbackListCount; i++) {
            SparseArray<ActiveCallback> callbacks = this.mActiveWatchers.valueAt(i);
            ActiveCallback callback = callbacks.get(code);
            if (callback != null && (callback.mWatchingUid < 0 || callback.mWatchingUid == uid)) {
                if (dispatchedCallbacks == null) {
                    dispatchedCallbacks = new ArraySet<>();
                }
                dispatchedCallbacks.add(callback);
            }
        }
        if (dispatchedCallbacks == null) {
            return;
        }
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new HexConsumer() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$ac4Ra3Yhj0OQzvkaL2dLbsuLAmQ
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5, Object obj6) {
                ((AppOpsService) obj).notifyOpActiveChanged((ArraySet) obj2, ((Integer) obj3).intValue(), ((Integer) obj4).intValue(), (String) obj5, ((Boolean) obj6).booleanValue());
            }
        }, this, dispatchedCallbacks, Integer.valueOf(code), Integer.valueOf(uid), packageName, Boolean.valueOf(active)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyOpActiveChanged(ArraySet<ActiveCallback> callbacks, int code, int uid, String packageName, boolean active) {
        long identity = Binder.clearCallingIdentity();
        try {
            int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                ActiveCallback callback = callbacks.valueAt(i);
                try {
                    callback.mCallback.opActiveChanged(code, uid, packageName, active);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void scheduleOpNotedIfNeededLocked(int code, int uid, String packageName, int result) {
        ArraySet<NotedCallback> dispatchedCallbacks = null;
        int callbackListCount = this.mNotedWatchers.size();
        for (int i = 0; i < callbackListCount; i++) {
            SparseArray<NotedCallback> callbacks = this.mNotedWatchers.valueAt(i);
            NotedCallback callback = callbacks.get(code);
            if (callback != null && (callback.mWatchingUid < 0 || callback.mWatchingUid == uid)) {
                if (dispatchedCallbacks == null) {
                    dispatchedCallbacks = new ArraySet<>();
                }
                dispatchedCallbacks.add(callback);
            }
        }
        if (dispatchedCallbacks == null) {
            return;
        }
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new HexConsumer() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$AfBLuTvVESlqN91IyRX84hMV5nE
            public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5, Object obj6) {
                ((AppOpsService) obj).notifyOpChecked((ArraySet) obj2, ((Integer) obj3).intValue(), ((Integer) obj4).intValue(), (String) obj5, ((Integer) obj6).intValue());
            }
        }, this, dispatchedCallbacks, Integer.valueOf(code), Integer.valueOf(uid), packageName, Integer.valueOf(result)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyOpChecked(ArraySet<NotedCallback> callbacks, int code, int uid, String packageName, int result) {
        long identity = Binder.clearCallingIdentity();
        try {
            int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                NotedCallback callback = callbacks.valueAt(i);
                try {
                    callback.mCallback.opNoted(code, uid, packageName, result);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public int permissionToOpCode(String permission) {
        if (permission == null) {
            return -1;
        }
        return AppOpsManager.permissionToOpCode(permission);
    }

    void finishOperationLocked(Op op, boolean finishNested) {
        int i;
        int opCode = op.op;
        int uid = op.uidState.uid;
        if (op.startNesting > 1 && !finishNested) {
            op.startNesting--;
            op.uidState.startNesting--;
            return;
        }
        if (op.startNesting == 1 || finishNested) {
            long duration = SystemClock.elapsedRealtime() - op.startRealtime;
            op.finished(System.currentTimeMillis(), duration, op.uidState.state, 1);
            i = 1;
            this.mHistoricalRegistry.increaseOpAccessDuration(opCode, uid, op.packageName, op.uidState.state, 1, duration);
        } else {
            AppOpsManager.OpEntry entry = new AppOpsManager.OpEntry(op.op, op.running, op.mode, op.mAccessTimes, op.mRejectTimes, op.mDurations, op.mProxyUids, op.mProxyPackageNames);
            Slog.w(TAG, "Finishing op nesting under-run: uid " + uid + " pkg " + op.packageName + " code " + opCode + " time=" + entry.getLastAccessTime(31) + " duration=" + entry.getLastDuration(100, 700, 31) + " nesting=" + op.startNesting);
            i = 1;
        }
        if (op.startNesting >= i) {
            op.uidState.startNesting -= op.startNesting;
        }
        op.startNesting = 0;
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid() || Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        this.mContext.enforcePermission("android.permission.UPDATE_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void verifyIncomingOp(int op) {
        if (op >= 0 && op < 91) {
            return;
        }
        throw new IllegalArgumentException("Bad operation #" + op);
    }

    private UidState getUidStateLocked(int uid, boolean edit) {
        UidState uidState = this.mUidStates.get(uid);
        if (uidState == null) {
            if (!edit) {
                return null;
            }
            UidState uidState2 = new UidState(uid);
            this.mUidStates.put(uid, uidState2);
            return uidState2;
        }
        updatePendingStateIfNeededLocked(uidState);
        return uidState;
    }

    private void updatePendingStateIfNeededLocked(UidState uidState) {
        if (uidState != null && uidState.pendingStateCommitTime != 0) {
            if (uidState.pendingStateCommitTime < this.mLastRealtime) {
                commitUidPendingStateLocked(uidState);
                return;
            }
            this.mLastRealtime = SystemClock.elapsedRealtime();
            if (uidState.pendingStateCommitTime < this.mLastRealtime) {
                commitUidPendingStateLocked(uidState);
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r8v18 */
    /* JADX WARN: Type inference failed for: r8v4, types: [int] */
    /* JADX WARN: Type inference failed for: r8v7 */
    private void commitUidPendingStateLocked(UidState uidState) {
        int pkgi;
        ModeCallback callback;
        int cbi;
        ArraySet<ModeCallback> callbacks;
        AppOpsService appOpsService = this;
        if (uidState.hasForegroundWatchers) {
            boolean z = true;
            int fgi = uidState.foregroundOps.size() - 1;
            while (fgi >= 0) {
                if (uidState.foregroundOps.valueAt(fgi)) {
                    int code = uidState.foregroundOps.keyAt(fgi);
                    long firstUnrestrictedUidState = AppOpsManager.resolveFirstUnrestrictedUidState(code);
                    boolean resolvedLastFg = ((long) uidState.state) <= firstUnrestrictedUidState ? z ? 1 : 0 : false;
                    boolean resolvedNowFg = ((long) uidState.pendingState) <= firstUnrestrictedUidState ? z ? 1 : 0 : false;
                    if (resolvedLastFg != resolvedNowFg) {
                        int i = 4;
                        if (uidState.opModes != null && uidState.opModes.indexOfKey(code) >= 0 && uidState.opModes.get(code) == 4) {
                            appOpsService.mHandler.sendMessage(PooledLambda.obtainMessage(new QuadConsumer() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$u9c0eEYUUm25QC1meV06FHffZE0
                                public final void accept(Object obj, Object obj2, Object obj3, Object obj4) {
                                    ((AppOpsService) obj).notifyOpChangedForAllPkgsInUid(((Integer) obj2).intValue(), ((Integer) obj3).intValue(), ((Boolean) obj4).booleanValue());
                                }
                            }, appOpsService, Integer.valueOf(code), Integer.valueOf(uidState.uid), Boolean.valueOf(z)));
                        } else {
                            ArraySet<ModeCallback> callbacks2 = appOpsService.mOpModeWatchers.get(code);
                            if (callbacks2 != null) {
                                int cbi2 = callbacks2.size() - (z ? 1 : 0);
                                ?? r8 = z;
                                while (cbi2 >= 0) {
                                    ModeCallback callback2 = callbacks2.valueAt(cbi2);
                                    if ((callback2.mFlags & r8) != 0 && callback2.isWatchingUid(uidState.uid)) {
                                        int pkgi2 = uidState.pkgOps.size() - r8;
                                        while (pkgi2 >= 0) {
                                            Op op = uidState.pkgOps.valueAt(pkgi2).get(code);
                                            if (op != null) {
                                                if (op.mode != i) {
                                                    pkgi = pkgi2;
                                                    callback = callback2;
                                                    cbi = cbi2;
                                                    callbacks = callbacks2;
                                                } else {
                                                    pkgi = pkgi2;
                                                    callback = callback2;
                                                    cbi = cbi2;
                                                    callbacks = callbacks2;
                                                    appOpsService.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$FYLTtxqrHmv8Y5UdZ9ybXKsSJhs.INSTANCE, this, callback2, Integer.valueOf(code), Integer.valueOf(uidState.uid), uidState.pkgOps.keyAt(pkgi2)));
                                                }
                                            } else {
                                                pkgi = pkgi2;
                                                callback = callback2;
                                                cbi = cbi2;
                                                callbacks = callbacks2;
                                            }
                                            pkgi2 = pkgi - 1;
                                            i = 4;
                                            appOpsService = this;
                                            callbacks2 = callbacks;
                                            cbi2 = cbi;
                                            callback2 = callback;
                                        }
                                    }
                                    cbi2--;
                                    i = 4;
                                    appOpsService = this;
                                    callbacks2 = callbacks2;
                                    r8 = 1;
                                }
                            }
                        }
                    }
                }
                fgi--;
                z = true;
                appOpsService = this;
            }
        }
        uidState.state = uidState.pendingState;
        uidState.pendingStateCommitTime = 0L;
    }

    private boolean verifyAndGetIsPrivileged(int uid, String packageName) {
        int pkgUid;
        Ops ops;
        if (uid == 0) {
            return false;
        }
        synchronized (this) {
            UidState uidState = this.mUidStates.get(uid);
            if (uidState != null && uidState.pkgOps != null && (ops = uidState.pkgOps.get(packageName)) != null) {
                return ops.isPrivileged;
            }
            boolean isPrivileged = false;
            long ident = Binder.clearCallingIdentity();
            try {
                ApplicationInfo appInfo = ((PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class)).getApplicationInfo(packageName, 546054144, 1000, UserHandle.getUserId(uid));
                if (appInfo != null) {
                    pkgUid = appInfo.uid;
                    isPrivileged = (appInfo.privateFlags & 8) != 0;
                } else {
                    pkgUid = resolveUid(packageName);
                    if (pkgUid >= 0) {
                        isPrivileged = false;
                    }
                }
                if (pkgUid != uid) {
                    throw new SecurityException("Specified package " + packageName + " under uid " + uid + " but it is really " + pkgUid);
                }
                return isPrivileged;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private Ops getOpsRawLocked(int uid, String packageName, boolean isPrivileged, boolean edit) {
        UidState uidState = getUidStateLocked(uid, edit);
        if (uidState == null) {
            return null;
        }
        if (uidState.pkgOps == null) {
            if (!edit) {
                return null;
            }
            uidState.pkgOps = new ArrayMap<>();
        }
        Ops ops = uidState.pkgOps.get(packageName);
        if (ops == null) {
            if (!edit) {
                return null;
            }
            Ops ops2 = new Ops(packageName, uidState, isPrivileged);
            uidState.pkgOps.put(packageName, ops2);
            return ops2;
        }
        return ops;
    }

    private Ops getOpsRawNoVerifyLocked(int uid, String packageName, boolean edit, boolean isPrivileged) {
        UidState uidState = getUidStateLocked(uid, edit);
        if (uidState == null) {
            return null;
        }
        if (uidState.pkgOps == null) {
            if (!edit) {
                return null;
            }
            uidState.pkgOps = new ArrayMap<>();
        }
        Ops ops = uidState.pkgOps.get(packageName);
        if (ops == null) {
            if (!edit) {
                return null;
            }
            Ops ops2 = new Ops(packageName, uidState, isPrivileged);
            uidState.pkgOps.put(packageName, ops2);
            return ops2;
        }
        return ops;
    }

    private void scheduleWriteLocked() {
        if (!this.mWriteScheduled) {
            this.mWriteScheduled = true;
            this.mHandler.postDelayed(this.mWriteRunner, 1800000L);
        }
    }

    private void scheduleFastWriteLocked() {
        if (!this.mFastWriteScheduled) {
            this.mWriteScheduled = true;
            this.mFastWriteScheduled = true;
            this.mHandler.removeCallbacks(this.mWriteRunner);
            this.mHandler.postDelayed(this.mWriteRunner, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    private Op getOpLocked(int code, int uid, String packageName, boolean isPrivileged, boolean edit) {
        Ops ops = getOpsRawNoVerifyLocked(uid, packageName, edit, isPrivileged);
        if (ops == null) {
            return null;
        }
        return getOpLocked(ops, code, edit);
    }

    private Op getOpLocked(Ops ops, int code, boolean edit) {
        Op op = ops.get(code);
        if (op == null) {
            if (!edit) {
                return null;
            }
            op = new Op(ops.uidState, ops.packageName, code);
            ops.put(code, op);
        }
        if (edit) {
            scheduleWriteLocked();
        }
        return op;
    }

    private boolean isOpRestrictedDueToSuspend(int code, String packageName, int uid) {
        if (!ArrayUtils.contains(OPS_RESTRICTED_ON_SUSPEND, code)) {
            return false;
        }
        PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        return pmi.isPackageSuspended(packageName, UserHandle.getUserId(uid));
    }

    private boolean isOpRestrictedLocked(int uid, int code, String packageName, boolean isPrivileged) {
        int userHandle = UserHandle.getUserId(uid);
        int restrictionSetCount = this.mOpUserRestrictions.size();
        for (int i = 0; i < restrictionSetCount; i++) {
            ClientRestrictionState restrictionState = this.mOpUserRestrictions.valueAt(i);
            if (restrictionState.hasRestriction(code, packageName, userHandle)) {
                if (AppOpsManager.opAllowSystemBypassRestriction(code)) {
                    synchronized (this) {
                        Ops ops = getOpsRawLocked(uid, packageName, isPrivileged, true);
                        if (ops != null && ops.isPrivileged) {
                            return false;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:41:0x009f -> B:83:0x0183). Please submit an issue!!! */
    void readState() {
        XmlPullParser parser;
        int type;
        synchronized (this.mFile) {
            synchronized (this) {
                try {
                    FileInputStream stream = this.mFile.openRead();
                    try {
                        this.mUidStates.clear();
                    } catch (IOException e) {
                    }
                    try {
                        try {
                            try {
                                try {
                                    try {
                                        parser = Xml.newPullParser();
                                        parser.setInput(stream, StandardCharsets.UTF_8.name());
                                        while (true) {
                                            type = parser.next();
                                            if (type == 2 || type == 1) {
                                                break;
                                            }
                                        }
                                    } catch (IllegalStateException e2) {
                                        Slog.w(TAG, "Failed parsing " + e2);
                                        if (0 == 0) {
                                            this.mUidStates.clear();
                                        }
                                        stream.close();
                                    } catch (XmlPullParserException e3) {
                                        Slog.w(TAG, "Failed parsing " + e3);
                                        if (0 == 0) {
                                            this.mUidStates.clear();
                                        }
                                        stream.close();
                                    }
                                } catch (IOException e4) {
                                    Slog.w(TAG, "Failed parsing " + e4);
                                    if (0 == 0) {
                                        this.mUidStates.clear();
                                    }
                                    stream.close();
                                } catch (NullPointerException e5) {
                                    Slog.w(TAG, "Failed parsing " + e5);
                                    if (0 == 0) {
                                        this.mUidStates.clear();
                                    }
                                    stream.close();
                                }
                            } catch (IndexOutOfBoundsException e6) {
                                Slog.w(TAG, "Failed parsing " + e6);
                                if (0 == 0) {
                                    this.mUidStates.clear();
                                }
                                stream.close();
                            }
                        } catch (NumberFormatException e7) {
                            Slog.w(TAG, "Failed parsing " + e7);
                            if (0 == 0) {
                                this.mUidStates.clear();
                            }
                            stream.close();
                        }
                        if (type != 2) {
                            throw new IllegalStateException("no start tag found");
                        }
                        String versionString = parser.getAttributeValue(null, "v");
                        oldVersion = versionString != null ? Integer.parseInt(versionString) : -1;
                        int outerDepth = parser.getDepth();
                        while (true) {
                            int type2 = parser.next();
                            if (type2 == 1 || (type2 == 3 && parser.getDepth() <= outerDepth)) {
                                break;
                            } else if (type2 != 3 && type2 != 4) {
                                String tagName = parser.getName();
                                if (tagName.equals("pkg")) {
                                    readPackage(parser);
                                } else if (tagName.equals(WatchlistLoggingHandler.WatchlistEventKeys.UID)) {
                                    readUidOps(parser);
                                } else {
                                    Slog.w(TAG, "Unknown element under <app-ops>: " + parser.getName());
                                    XmlUtils.skipCurrentTag(parser);
                                }
                            }
                        }
                        if (1 == 0) {
                            this.mUidStates.clear();
                        }
                        stream.close();
                    } catch (Throwable th) {
                        if (0 == 0) {
                            this.mUidStates.clear();
                        }
                        try {
                            stream.close();
                        } catch (IOException e8) {
                        }
                        throw th;
                    }
                } catch (FileNotFoundException e9) {
                    Slog.i(TAG, "No existing app ops " + this.mFile.getBaseFile() + "; starting empty");
                    return;
                }
            }
        }
        synchronized (this) {
            upgradeLocked(oldVersion);
        }
    }

    private void upgradeRunAnyInBackgroundLocked() {
        Op op;
        int idx;
        for (int i = 0; i < this.mUidStates.size(); i++) {
            UidState uidState = this.mUidStates.valueAt(i);
            if (uidState != null) {
                if (uidState.opModes != null && (idx = uidState.opModes.indexOfKey(63)) >= 0) {
                    uidState.opModes.put(70, uidState.opModes.valueAt(idx));
                }
                if (uidState.pkgOps != null) {
                    boolean changed = false;
                    for (int j = 0; j < uidState.pkgOps.size(); j++) {
                        Ops ops = uidState.pkgOps.valueAt(j);
                        if (ops != null && (op = ops.get(63)) != null && op.mode != AppOpsManager.opToDefaultMode(op.op)) {
                            Op copy = new Op(op.uidState, op.packageName, 70);
                            copy.mode = op.mode;
                            ops.put(70, copy);
                            changed = true;
                        }
                    }
                    if (changed) {
                        uidState.evalForegroundOps(this.mOpModeWatchers);
                    }
                }
            }
        }
    }

    private void upgradeLocked(int oldVersion) {
        if (oldVersion >= 1) {
            return;
        }
        Slog.d(TAG, "Upgrading app-ops xml from version " + oldVersion + " to 1");
        if (oldVersion == -1) {
            upgradeRunAnyInBackgroundLocked();
        }
        scheduleFastWriteLocked();
    }

    private void readUidOps(XmlPullParser parser) throws NumberFormatException, XmlPullParserException, IOException {
        int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals("op")) {
                            int code = Integer.parseInt(parser.getAttributeValue(null, "n"));
                            int mode = Integer.parseInt(parser.getAttributeValue(null, "m"));
                            UidState uidState = getUidStateLocked(uid, true);
                            if (uidState.opModes == null) {
                                uidState.opModes = new SparseIntArray();
                            }
                            uidState.opModes.put(code, mode);
                        } else {
                            Slog.w(TAG, "Unknown element under <uid-ops>: " + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void readPackage(XmlPullParser parser) throws NumberFormatException, XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(WatchlistLoggingHandler.WatchlistEventKeys.UID)) {
                            readUid(parser, pkgName);
                        } else {
                            Slog.w(TAG, "Unknown element under <pkg>: " + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void readUid(XmlPullParser parser, String pkgName) throws NumberFormatException, XmlPullParserException, IOException {
        int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        UidState uidState = getUidStateLocked(uid, true);
        String isPrivilegedString = parser.getAttributeValue(null, "p");
        boolean isPrivileged = false;
        if (isPrivilegedString == null) {
            try {
                IPackageManager packageManager = ActivityThread.getPackageManager();
                if (packageManager != null) {
                    ApplicationInfo appInfo = ActivityThread.getPackageManager().getApplicationInfo(pkgName, 0, UserHandle.getUserId(uid));
                    if (appInfo != null) {
                        isPrivileged = (appInfo.privateFlags & 8) != 0;
                    }
                } else {
                    return;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not contact PackageManager", e);
            }
        } else {
            isPrivileged = Boolean.parseBoolean(isPrivilegedString);
        }
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth)) {
                break;
            } else if (type != 3 && type != 4) {
                String tagName = parser.getName();
                if (tagName.equals("op")) {
                    readOp(parser, uidState, pkgName, isPrivileged);
                } else {
                    Slog.w(TAG, "Unknown element under <pkg>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        uidState.evalForegroundOps(this.mOpModeWatchers);
    }

    private void readOp(XmlPullParser parser, UidState uidState, String pkgName, boolean isPrivileged) throws NumberFormatException, XmlPullParserException, IOException {
        long j;
        Op op = new Op(uidState, pkgName, Integer.parseInt(parser.getAttributeValue(null, "n")));
        int mode = XmlUtils.readIntAttribute(parser, "m", AppOpsManager.opToDefaultMode(op.op));
        op.mode = mode;
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth)) {
                break;
            } else if (type != 3 && type != 4) {
                String tagName = parser.getName();
                if (tagName.equals("st")) {
                    long key = XmlUtils.readLongAttribute(parser, "n");
                    int flags = AppOpsManager.extractFlagsFromKey(key);
                    int state = AppOpsManager.extractUidStateFromKey(key);
                    long accessTime = XmlUtils.readLongAttribute(parser, "t", 0L);
                    long rejectTime = XmlUtils.readLongAttribute(parser, ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD, 0L);
                    long accessDuration = XmlUtils.readLongAttribute(parser, "d", 0L);
                    String proxyPkg = XmlUtils.readStringAttribute(parser, "pp");
                    int proxyUid = XmlUtils.readIntAttribute(parser, "pu", 0);
                    if (accessTime > 0) {
                        j = 0;
                        op.accessed(accessTime, proxyUid, proxyPkg, state, flags);
                    } else {
                        j = 0;
                    }
                    if (rejectTime > j) {
                        op.rejected(rejectTime, proxyUid, proxyPkg, state, flags);
                    }
                    if (accessDuration > j) {
                        op.running(accessTime, accessDuration, state, flags);
                    }
                } else {
                    Slog.w(TAG, "Unknown element under <op>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        if (uidState.pkgOps == null) {
            uidState.pkgOps = new ArrayMap<>();
        }
        Ops ops = uidState.pkgOps.get(pkgName);
        if (ops == null) {
            ops = new Ops(pkgName, uidState, isPrivileged);
            uidState.pkgOps.put(pkgName, ops);
        }
        ops.put(op.op, op);
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r12v5 */
    /* JADX WARN: Type inference failed for: r12v6, types: [int] */
    void writeState() {
        FastXmlSerializer fastXmlSerializer;
        String lastPkg;
        List<AppOpsManager.OpEntry> ops;
        List<AppOpsManager.PackageOps> allOps;
        SparseArray<SparseIntArray> uidStatesClone;
        int uidStateCount;
        String proxyPkg;
        SparseArray<SparseIntArray> uidStatesClone2;
        int uidStateCount2;
        synchronized (this.mFile) {
            try {
                try {
                    FileOutputStream stream = this.mFile.startWrite();
                    List<AppOpsManager.PackageOps> allOps2 = getPackagesForOps(null);
                    try {
                        fastXmlSerializer = new FastXmlSerializer();
                        fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
                        fastXmlSerializer.startDocument(null, true);
                        fastXmlSerializer.startTag(null, "app-ops");
                        fastXmlSerializer.attribute(null, "v", String.valueOf(1));
                        try {
                        } catch (IOException e) {
                            e = e;
                        }
                    } catch (IOException e2) {
                        e = e2;
                    }
                    synchronized (this) {
                        try {
                            SparseArray<SparseIntArray> uidStatesClone3 = new SparseArray<>(this.mUidStates.size());
                            int uidStateCount3 = this.mUidStates.size();
                            for (int uidStateNum = 0; uidStateNum < uidStateCount3; uidStateNum++) {
                                try {
                                    int uid = this.mUidStates.keyAt(uidStateNum);
                                    SparseIntArray opModes = this.mUidStates.valueAt(uidStateNum).opModes;
                                    if (opModes != null && opModes.size() > 0) {
                                        uidStatesClone3.put(uid, new SparseIntArray(opModes.size()));
                                        int opCount = opModes.size();
                                        for (int opCountNum = 0; opCountNum < opCount; opCountNum++) {
                                            uidStatesClone3.get(uid).put(opModes.keyAt(opCountNum), opModes.valueAt(opCountNum));
                                        }
                                    }
                                } catch (Throwable th) {
                                    th = th;
                                    while (true) {
                                        try {
                                            break;
                                        } catch (Throwable th2) {
                                            th = th2;
                                        }
                                    }
                                    throw th;
                                }
                            }
                            int uidStateCount4 = uidStatesClone3.size();
                            for (int uidStateNum2 = 0; uidStateNum2 < uidStateCount4; uidStateNum2++) {
                                try {
                                    SparseIntArray opModes2 = uidStatesClone3.valueAt(uidStateNum2);
                                    if (opModes2 != null && opModes2.size() > 0) {
                                        fastXmlSerializer.startTag(null, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                        fastXmlSerializer.attribute(null, "n", Integer.toString(uidStatesClone3.keyAt(uidStateNum2)));
                                        int opCount2 = opModes2.size();
                                        for (int opCountNum2 = 0; opCountNum2 < opCount2; opCountNum2++) {
                                            int op = opModes2.keyAt(opCountNum2);
                                            int mode = opModes2.valueAt(opCountNum2);
                                            fastXmlSerializer.startTag(null, "op");
                                            fastXmlSerializer.attribute(null, "n", Integer.toString(op));
                                            fastXmlSerializer.attribute(null, "m", Integer.toString(mode));
                                            fastXmlSerializer.endTag(null, "op");
                                        }
                                        fastXmlSerializer.endTag(null, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                    }
                                } catch (IOException e3) {
                                    e = e3;
                                    Slog.w(TAG, "Failed to write state, restoring backup.", e);
                                    this.mFile.failWrite(stream);
                                }
                            }
                            if (allOps2 != null) {
                                String lastPkg2 = null;
                                boolean z = false;
                                int i = 0;
                                while (i < allOps2.size()) {
                                    AppOpsManager.PackageOps pkg = allOps2.get(i);
                                    if (pkg.getPackageName().equals(lastPkg2)) {
                                        lastPkg = lastPkg2;
                                    } else {
                                        if (lastPkg2 != null) {
                                            fastXmlSerializer.endTag(null, "pkg");
                                        }
                                        String lastPkg3 = pkg.getPackageName();
                                        fastXmlSerializer.startTag(null, "pkg");
                                        fastXmlSerializer.attribute(null, "n", lastPkg3);
                                        lastPkg = lastPkg3;
                                    }
                                    fastXmlSerializer.startTag(null, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                    fastXmlSerializer.attribute(null, "n", Integer.toString(pkg.getUid()));
                                    synchronized (this) {
                                        try {
                                            Ops ops2 = getOpsRawLocked(pkg.getUid(), pkg.getPackageName(), z, z);
                                            if (ops2 != null) {
                                                try {
                                                    fastXmlSerializer.attribute(null, "p", Boolean.toString(ops2.isPrivileged));
                                                } catch (Throwable th3) {
                                                    th = th3;
                                                    while (true) {
                                                        try {
                                                            break;
                                                        } catch (Throwable th4) {
                                                            th = th4;
                                                        }
                                                    }
                                                    throw th;
                                                }
                                            } else {
                                                fastXmlSerializer.attribute(null, "p", Boolean.toString(z));
                                            }
                                        } catch (Throwable th5) {
                                            th = th5;
                                        }
                                    }
                                    List<AppOpsManager.OpEntry> ops3 = pkg.getOps();
                                    for (int j = z; j < ops3.size(); j++) {
                                        AppOpsManager.OpEntry op2 = ops3.get(j);
                                        fastXmlSerializer.startTag(null, "op");
                                        fastXmlSerializer.attribute(null, "n", Integer.toString(op2.getOp()));
                                        if (op2.getMode() != AppOpsManager.opToDefaultMode(op2.getOp())) {
                                            fastXmlSerializer.attribute(null, "m", Integer.toString(op2.getMode()));
                                        }
                                        LongSparseArray keys = op2.collectKeys();
                                        if (keys == null) {
                                            ops = ops3;
                                            allOps = allOps2;
                                            uidStatesClone = uidStatesClone3;
                                            uidStateCount = uidStateCount4;
                                        } else if (keys.size() <= 0) {
                                            ops = ops3;
                                            allOps = allOps2;
                                            uidStatesClone = uidStatesClone3;
                                            uidStateCount = uidStateCount4;
                                        } else {
                                            int keyCount = keys.size();
                                            int k = 0;
                                            List<AppOpsManager.OpEntry> ops4 = ops3;
                                            while (k < keyCount) {
                                                long key = keys.keyAt(k);
                                                int uidState = AppOpsManager.extractUidStateFromKey(key);
                                                int flags = AppOpsManager.extractFlagsFromKey(key);
                                                List<AppOpsManager.OpEntry> ops5 = ops4;
                                                List<AppOpsManager.PackageOps> allOps3 = allOps2;
                                                long accessTime = op2.getLastAccessTime(uidState, uidState, flags);
                                                long rejectTime = op2.getLastRejectTime(uidState, uidState, flags);
                                                long accessDuration = op2.getLastDuration(uidState, uidState, flags);
                                                String proxyPkg2 = op2.getProxyPackageName(uidState, flags);
                                                int proxyUid = op2.getProxyUid(uidState, flags);
                                                if (accessTime > 0 || rejectTime > 0 || accessDuration > 0) {
                                                    proxyPkg = proxyPkg2;
                                                } else {
                                                    proxyPkg = proxyPkg2;
                                                    if (proxyPkg == null && proxyUid < 0) {
                                                        uidStatesClone2 = uidStatesClone3;
                                                        uidStateCount2 = uidStateCount4;
                                                        k++;
                                                        ops4 = ops5;
                                                        allOps2 = allOps3;
                                                        uidStatesClone3 = uidStatesClone2;
                                                        uidStateCount4 = uidStateCount2;
                                                    }
                                                }
                                                uidStatesClone2 = uidStatesClone3;
                                                fastXmlSerializer.startTag(null, "st");
                                                uidStateCount2 = uidStateCount4;
                                                fastXmlSerializer.attribute(null, "n", Long.toString(key));
                                                if (accessTime > 0) {
                                                    fastXmlSerializer.attribute(null, "t", Long.toString(accessTime));
                                                }
                                                if (rejectTime > 0) {
                                                    fastXmlSerializer.attribute(null, ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD, Long.toString(rejectTime));
                                                }
                                                if (accessDuration > 0) {
                                                    fastXmlSerializer.attribute(null, "d", Long.toString(accessDuration));
                                                }
                                                if (proxyPkg != null) {
                                                    fastXmlSerializer.attribute(null, "pp", proxyPkg);
                                                }
                                                if (proxyUid >= 0) {
                                                    fastXmlSerializer.attribute(null, "pu", Integer.toString(proxyUid));
                                                }
                                                fastXmlSerializer.endTag(null, "st");
                                                k++;
                                                ops4 = ops5;
                                                allOps2 = allOps3;
                                                uidStatesClone3 = uidStatesClone2;
                                                uidStateCount4 = uidStateCount2;
                                            }
                                            ops = ops4;
                                            allOps = allOps2;
                                            uidStatesClone = uidStatesClone3;
                                            uidStateCount = uidStateCount4;
                                            fastXmlSerializer.endTag(null, "op");
                                            ops3 = ops;
                                            allOps2 = allOps;
                                            uidStatesClone3 = uidStatesClone;
                                            uidStateCount4 = uidStateCount;
                                        }
                                        fastXmlSerializer.endTag(null, "op");
                                        ops3 = ops;
                                        allOps2 = allOps;
                                        uidStatesClone3 = uidStatesClone;
                                        uidStateCount4 = uidStateCount;
                                    }
                                    List<AppOpsManager.PackageOps> allOps4 = allOps2;
                                    SparseArray<SparseIntArray> uidStatesClone4 = uidStatesClone3;
                                    int uidStateCount5 = uidStateCount4;
                                    fastXmlSerializer.endTag(null, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                    i++;
                                    lastPkg2 = lastPkg;
                                    allOps2 = allOps4;
                                    uidStatesClone3 = uidStatesClone4;
                                    uidStateCount4 = uidStateCount5;
                                    z = false;
                                }
                                if (lastPkg2 != null) {
                                    fastXmlSerializer.endTag(null, "pkg");
                                }
                            }
                            fastXmlSerializer.endTag(null, "app-ops");
                            fastXmlSerializer.endDocument();
                            this.mFile.finishWrite(stream);
                        } catch (Throwable th6) {
                            th = th6;
                        }
                    }
                } catch (IOException e4) {
                    Slog.w(TAG, "Failed to write state: " + e4);
                }
            } finally {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class Shell extends ShellCommand {
        static final Binder sBinder = new Binder();
        final IAppOpsService mInterface;
        final AppOpsService mInternal;
        IBinder mToken;
        int mode;
        String modeStr;
        int nonpackageUid;
        int op;
        String opStr;
        String packageName;
        int packageUid;
        boolean targetsUid;
        int userId = 0;

        Shell(IAppOpsService iface, AppOpsService internal) {
            this.mInterface = iface;
            this.mInternal = internal;
            try {
                this.mToken = this.mInterface.getToken(sBinder);
            } catch (RemoteException e) {
            }
        }

        public int onCommand(String cmd) {
            return AppOpsService.onShellCommand(this, cmd);
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            AppOpsService.dumpCommandHelp(pw);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static int strOpToOp(String op, PrintWriter err) {
            try {
                return AppOpsManager.strOpToOp(op);
            } catch (IllegalArgumentException e) {
                try {
                    return Integer.parseInt(op);
                } catch (NumberFormatException e2) {
                    try {
                        return AppOpsManager.strDebugOpToOp(op);
                    } catch (IllegalArgumentException e3) {
                        err.println("Error: " + e3.getMessage());
                        return -1;
                    }
                }
            }
        }

        static int strModeToMode(String modeStr, PrintWriter err) {
            for (int i = AppOpsManager.MODE_NAMES.length - 1; i >= 0; i--) {
                if (AppOpsManager.MODE_NAMES[i].equals(modeStr)) {
                    return i;
                }
            }
            try {
                int i2 = Integer.parseInt(modeStr);
                return i2;
            } catch (NumberFormatException e) {
                err.println("Error: Mode " + modeStr + " is not valid");
                return -1;
            }
        }

        int parseUserOpMode(int defMode, PrintWriter err) throws RemoteException {
            this.userId = -2;
            this.opStr = null;
            this.modeStr = null;
            while (true) {
                String argument = getNextArg();
                if (argument == null) {
                    break;
                } else if ("--user".equals(argument)) {
                    this.userId = UserHandle.parseUserArg(getNextArgRequired());
                } else if (this.opStr == null) {
                    this.opStr = argument;
                } else if (this.modeStr == null) {
                    this.modeStr = argument;
                    break;
                }
            }
            String str = this.opStr;
            if (str == null) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            this.op = strOpToOp(str, err);
            if (this.op < 0) {
                return -1;
            }
            String str2 = this.modeStr;
            if (str2 != null) {
                int strModeToMode = strModeToMode(str2, err);
                this.mode = strModeToMode;
                return strModeToMode < 0 ? -1 : 0;
            }
            this.mode = defMode;
            return 0;
        }

        /* JADX WARN: Code restructure failed: missing block: B:57:0x00c2, code lost:
            if (r0 >= r11.packageName.length()) goto L87;
         */
        /* JADX WARN: Code restructure failed: missing block: B:58:0x00c4, code lost:
            r4 = r11.packageName.substring(1, r0);
         */
        /* JADX WARN: Code restructure failed: missing block: B:59:0x00ca, code lost:
            r5 = java.lang.Integer.parseInt(r4);
            r8 = r11.packageName.charAt(r0);
            r0 = r0 + 1;
         */
        /* JADX WARN: Code restructure failed: missing block: B:61:0x00dd, code lost:
            if (r0 >= r11.packageName.length()) goto L83;
         */
        /* JADX WARN: Code restructure failed: missing block: B:63:0x00e5, code lost:
            if (r11.packageName.charAt(r0) < '0') goto L82;
         */
        /* JADX WARN: Code restructure failed: missing block: B:65:0x00ed, code lost:
            if (r11.packageName.charAt(r0) > '9') goto L68;
         */
        /* JADX WARN: Code restructure failed: missing block: B:66:0x00ef, code lost:
            r0 = r0 + 1;
         */
        /* JADX WARN: Code restructure failed: missing block: B:67:0x00f2, code lost:
            if (r0 <= r0) goto L87;
         */
        /* JADX WARN: Code restructure failed: missing block: B:68:0x00f4, code lost:
            r6 = r11.packageName.substring(r0, r0);
         */
        /* JADX WARN: Code restructure failed: missing block: B:69:0x00fa, code lost:
            r7 = java.lang.Integer.parseInt(r6);
         */
        /* JADX WARN: Code restructure failed: missing block: B:70:0x0100, code lost:
            if (r8 != 'a') goto L77;
         */
        /* JADX WARN: Code restructure failed: missing block: B:71:0x0102, code lost:
            r11.nonpackageUid = android.os.UserHandle.getUid(r5, r7 + 10000);
         */
        /* JADX WARN: Code restructure failed: missing block: B:73:0x010d, code lost:
            if (r8 != 's') goto L75;
         */
        /* JADX WARN: Code restructure failed: missing block: B:74:0x010f, code lost:
            r11.nonpackageUid = android.os.UserHandle.getUid(r5, r7);
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        int parseUserPackageOp(boolean r12, java.io.PrintWriter r13) throws android.os.RemoteException {
            /*
                Method dump skipped, instructions count: 354
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.AppOpsService.Shell.parseUserPackageOp(boolean, java.io.PrintWriter):int");
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new Shell(this, this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    static void dumpCommandHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  start [--user <USER_ID>] <PACKAGE | UID> <OP> ");
        pw.println("    Starts a given operation for a particular application.");
        pw.println("  stop [--user <USER_ID>] <PACKAGE | UID> <OP> ");
        pw.println("    Stops a given operation for a particular application.");
        pw.println("  set [--user <USER_ID>] <[--uid] PACKAGE | UID> <OP> <MODE>");
        pw.println("    Set the mode for a particular application and operation.");
        pw.println("  get [--user <USER_ID>] <PACKAGE | UID> [<OP>]");
        pw.println("    Return the mode for a particular application and optional operation.");
        pw.println("  query-op [--user <USER_ID>] <OP> [<MODE>]");
        pw.println("    Print all packages that currently have the given op in the given mode.");
        pw.println("  reset [--user <USER_ID>] [<PACKAGE>]");
        pw.println("    Reset the given application or all applications to default modes.");
        pw.println("  write-settings");
        pw.println("    Immediately write pending changes to storage.");
        pw.println("  read-settings");
        pw.println("    Read the last written settings, replacing current state in RAM.");
        pw.println("  options:");
        pw.println("    <PACKAGE> an Android package name or its UID if prefixed by --uid");
        pw.println("    <OP>      an AppOps operation.");
        pw.println("    <MODE>    one of allow, ignore, deny, or default");
        pw.println("    <USER_ID> the user id under which the package is installed. If --user is not");
        pw.println("              specified, the current user is assumed.");
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Multi-variable type inference failed */
    static int onShellCommand(Shell shell, String cmd) {
        char c;
        List<AppOpsManager.PackageOps> ops;
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        PrintWriter err = shell.getErrPrintWriter();
        try {
            int i = 0;
            switch (cmd.hashCode()) {
                case -1703718319:
                    if (cmd.equals("write-settings")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case -1166702330:
                    if (cmd.equals("query-op")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case 102230:
                    if (cmd.equals("get")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case 113762:
                    if (cmd.equals("set")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 3540994:
                    if (cmd.equals("stop")) {
                        c = 7;
                        break;
                    }
                    c = 65535;
                    break;
                case 108404047:
                    if (cmd.equals("reset")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case 109757538:
                    if (cmd.equals("start")) {
                        c = 6;
                        break;
                    }
                    c = 65535;
                    break;
                case 2085703290:
                    if (cmd.equals("read-settings")) {
                        c = 5;
                        break;
                    }
                    c = 65535;
                    break;
                default:
                    c = 65535;
                    break;
            }
            switch (c) {
                case 0:
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }
                    String modeStr = shell.getNextArg();
                    if (modeStr == null) {
                        err.println("Error: Mode not specified.");
                        return -1;
                    }
                    int mode = Shell.strModeToMode(modeStr, err);
                    if (mode < 0) {
                        return -1;
                    }
                    if (!shell.targetsUid && shell.packageName != null) {
                        shell.mInterface.setMode(shell.op, shell.packageUid, shell.packageName, mode);
                        return 0;
                    } else if (!shell.targetsUid || shell.packageName == null) {
                        shell.mInterface.setUidMode(shell.op, shell.nonpackageUid, mode);
                        return 0;
                    } else {
                        try {
                            int uid = shell.mInternal.mContext.getPackageManager().getPackageUid(shell.packageName, shell.userId);
                            shell.mInterface.setUidMode(shell.op, uid, mode);
                            return 0;
                        } catch (PackageManager.NameNotFoundException e) {
                            return -1;
                        }
                    }
                case 1:
                    int res2 = shell.parseUserPackageOp(false, err);
                    if (res2 < 0) {
                        return res2;
                    }
                    List<AppOpsManager.PackageOps> ops2 = new ArrayList<>();
                    if (shell.packageName != null) {
                        List<AppOpsManager.PackageOps> r = shell.mInterface.getUidOps(shell.packageUid, shell.op != -1 ? new int[]{shell.op} : null);
                        if (r != null) {
                            ops2.addAll(r);
                        }
                        List<AppOpsManager.PackageOps> r2 = shell.mInterface.getOpsForPackage(shell.packageUid, shell.packageName, shell.op != -1 ? new int[]{shell.op} : null);
                        if (r2 != null) {
                            ops2.addAll(r2);
                        }
                    } else {
                        ops2 = shell.mInterface.getUidOps(shell.nonpackageUid, shell.op != -1 ? new int[]{shell.op} : null);
                    }
                    if (ops2 != null && ops2.size() > 0) {
                        long now = System.currentTimeMillis();
                        int i2 = 0;
                        while (i2 < ops2.size()) {
                            AppOpsManager.PackageOps packageOps = ops2.get(i2);
                            if (packageOps.getPackageName() == null) {
                                pw.print("Uid mode: ");
                            }
                            List<AppOpsManager.OpEntry> entries = packageOps.getOps();
                            int j = i;
                            while (j < entries.size()) {
                                AppOpsManager.OpEntry ent = entries.get(j);
                                pw.print(AppOpsManager.opToName(ent.getOp()));
                                pw.print(": ");
                                pw.print(AppOpsManager.modeToName(ent.getMode()));
                                if (ent.getTime() == 0) {
                                    ops = ops2;
                                } else {
                                    pw.print("; time=");
                                    ops = ops2;
                                    TimeUtils.formatDuration(now - ent.getTime(), pw);
                                    pw.print(" ago");
                                }
                                if (ent.getRejectTime() != 0) {
                                    pw.print("; rejectTime=");
                                    TimeUtils.formatDuration(now - ent.getRejectTime(), pw);
                                    pw.print(" ago");
                                }
                                if (ent.getDuration() == -1) {
                                    pw.print(" (running)");
                                } else if (ent.getDuration() != 0) {
                                    pw.print("; duration=");
                                    TimeUtils.formatDuration(ent.getDuration(), pw);
                                }
                                pw.println();
                                j++;
                                ops2 = ops;
                            }
                            i2++;
                            i = 0;
                        }
                        return 0;
                    }
                    pw.println("No operations.");
                    if (shell.op > -1 && shell.op < 91) {
                        pw.println("Default mode: " + AppOpsManager.modeToName(AppOpsManager.opToDefaultMode(shell.op)));
                        return 0;
                    }
                    return 0;
                case 2:
                    int res3 = shell.parseUserOpMode(1, err);
                    if (res3 < 0) {
                        return res3;
                    }
                    List<AppOpsManager.PackageOps> ops3 = shell.mInterface.getPackagesForOps(new int[]{shell.op});
                    if (ops3 != null && ops3.size() > 0) {
                        for (int i3 = 0; i3 < ops3.size(); i3++) {
                            AppOpsManager.PackageOps pkg = ops3.get(i3);
                            boolean hasMatch = false;
                            List<AppOpsManager.OpEntry> entries2 = ops3.get(i3).getOps();
                            int j2 = 0;
                            while (true) {
                                if (j2 < entries2.size()) {
                                    AppOpsManager.OpEntry ent2 = entries2.get(j2);
                                    if (ent2.getOp() != shell.op || ent2.getMode() != shell.mode) {
                                        j2++;
                                    } else {
                                        hasMatch = true;
                                    }
                                }
                            }
                            if (hasMatch) {
                                pw.println(pkg.getPackageName());
                            }
                        }
                        return 0;
                    }
                    pw.println("No operations.");
                    return 0;
                case 3:
                    String packageName = null;
                    int userId = -2;
                    while (true) {
                        String argument = shell.getNextArg();
                        if (argument != null) {
                            if ("--user".equals(argument)) {
                                String userStr = shell.getNextArgRequired();
                                userId = UserHandle.parseUserArg(userStr);
                            } else if (packageName != null) {
                                err.println("Error: Unsupported argument: " + argument);
                                return -1;
                            } else {
                                packageName = argument;
                            }
                        } else {
                            if (userId == -2) {
                                userId = ActivityManager.getCurrentUser();
                            }
                            shell.mInterface.resetAllModes(userId, packageName);
                            pw.print("Reset all modes for: ");
                            if (userId == -1) {
                                pw.print("all users");
                            } else {
                                pw.print("user ");
                                pw.print(userId);
                            }
                            pw.print(", ");
                            if (packageName == null) {
                                pw.println("all packages");
                            } else {
                                pw.print("package ");
                                pw.println(packageName);
                            }
                            return 0;
                        }
                    }
                case 4:
                    shell.mInternal.enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), -1);
                    long token = Binder.clearCallingIdentity();
                    synchronized (shell.mInternal) {
                        shell.mInternal.mHandler.removeCallbacks(shell.mInternal.mWriteRunner);
                    }
                    shell.mInternal.writeState();
                    pw.println("Current settings written.");
                    Binder.restoreCallingIdentity(token);
                    return 0;
                case 5:
                    shell.mInternal.enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), -1);
                    long token2 = Binder.clearCallingIdentity();
                    shell.mInternal.readState();
                    pw.println("Last settings read.");
                    Binder.restoreCallingIdentity(token2);
                    return 0;
                case 6:
                    int res4 = shell.parseUserPackageOp(true, err);
                    if (res4 < 0) {
                        return res4;
                    }
                    if (shell.packageName != null) {
                        shell.mInterface.startOperation(shell.mToken, shell.op, shell.packageUid, shell.packageName, true);
                        return 0;
                    }
                    return -1;
                case 7:
                    int res5 = shell.parseUserPackageOp(true, err);
                    if (res5 < 0) {
                        return res5;
                    }
                    if (shell.packageName != null) {
                        shell.mInterface.finishOperation(shell.mToken, shell.op, shell.packageUid, shell.packageName);
                        return 0;
                    }
                    return -1;
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (RemoteException e2) {
            pw.println("Remote exception: " + e2);
            return -1;
        }
    }

    private void dumpHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) dump options:");
        pw.println("  -h");
        pw.println("    Print this help text.");
        pw.println("  --op [OP]");
        pw.println("    Limit output to data associated with the given app op code.");
        pw.println("  --mode [MODE]");
        pw.println("    Limit output to data associated with the given app op mode.");
        pw.println("  --package [PACKAGE]");
        pw.println("    Limit output to data associated with the given package name.");
        pw.println("  --watchers");
        pw.println("    Only output the watcher sections.");
    }

    private void dumpStatesLocked(PrintWriter pw, Op op, long now, SimpleDateFormat sdf, Date date, String prefix) {
        String str;
        String str2;
        String str3 = prefix;
        AppOpsManager.OpEntry entry = new AppOpsManager.OpEntry(op.op, op.running, op.mode, op.mAccessTimes, op.mRejectTimes, op.mDurations, op.mProxyUids, op.mProxyPackageNames);
        LongSparseArray keys = entry.collectKeys();
        if (keys != null && keys.size() > 0) {
            int keyCount = keys.size();
            int proxyUid = 0;
            while (proxyUid < keyCount) {
                long key = keys.keyAt(proxyUid);
                int uidState = AppOpsManager.extractUidStateFromKey(key);
                int flags = AppOpsManager.extractFlagsFromKey(key);
                long accessTime = entry.getLastAccessTime(uidState, uidState, flags);
                long rejectTime = entry.getLastRejectTime(uidState, uidState, flags);
                LongSparseArray keys2 = keys;
                int keyCount2 = keyCount;
                long accessDuration = entry.getLastDuration(uidState, uidState, flags);
                String proxyPkg = entry.getProxyPackageName(uidState, flags);
                int k = proxyUid;
                int proxyUid2 = entry.getProxyUid(uidState, flags);
                AppOpsManager.OpEntry entry2 = entry;
                if (accessTime <= 0) {
                    str = " (";
                    str2 = "]";
                } else {
                    pw.print(str3);
                    pw.print("Access: ");
                    pw.print(AppOpsManager.keyToString(key));
                    pw.print(" ");
                    date.setTime(accessTime);
                    pw.print(sdf.format(date));
                    pw.print(" (");
                    str = " (";
                    TimeUtils.formatDuration(accessTime - now, pw);
                    pw.print(")");
                    if (accessDuration > 0) {
                        pw.print(" duration=");
                        TimeUtils.formatDuration(accessDuration, pw);
                    }
                    if (proxyUid2 < 0) {
                        str2 = "]";
                    } else {
                        pw.print(" proxy[");
                        pw.print("uid=");
                        pw.print(proxyUid2);
                        pw.print(", pkg=");
                        pw.print(proxyPkg);
                        str2 = "]";
                        pw.print(str2);
                    }
                    pw.println();
                }
                if (rejectTime > 0) {
                    pw.print(prefix);
                    pw.print("Reject: ");
                    pw.print(AppOpsManager.keyToString(key));
                    date.setTime(rejectTime);
                    pw.print(sdf.format(date));
                    pw.print(str);
                    TimeUtils.formatDuration(rejectTime - now, pw);
                    pw.print(")");
                    if (proxyUid2 >= 0) {
                        pw.print(" proxy[");
                        pw.print("uid=");
                        pw.print(proxyUid2);
                        pw.print(", pkg=");
                        pw.print(proxyPkg);
                        pw.print(str2);
                    }
                    pw.println();
                }
                proxyUid = k + 1;
                str3 = prefix;
                keys = keys2;
                keyCount = keyCount2;
                entry = entry2;
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:176:0x035e, code lost:
        if (r1 != android.os.UserHandle.getAppId(r24.mWatchingUid)) goto L199;
     */
    /* JADX WARN: Code restructure failed: missing block: B:452:0x08c6, code lost:
        if (r3 != r6) goto L440;
     */
    /* JADX WARN: Incorrect condition in loop: B:9:0x0021 */
    /* JADX WARN: Removed duplicated region for block: B:316:0x0682  */
    /* JADX WARN: Removed duplicated region for block: B:317:0x0684  */
    /* JADX WARN: Removed duplicated region for block: B:319:0x0687  */
    /* JADX WARN: Removed duplicated region for block: B:320:0x068a  */
    /* JADX WARN: Removed duplicated region for block: B:334:0x06b8  */
    /* JADX WARN: Removed duplicated region for block: B:369:0x0733  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    protected void dump(java.io.FileDescriptor r37, java.io.PrintWriter r38, java.lang.String[] r39) {
        /*
            Method dump skipped, instructions count: 3126
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.appop.AppOpsService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Restriction {
        private static final ArraySet<String> NO_EXCEPTIONS = new ArraySet<>();
        ArraySet<String> exceptionPackages;
        int mode;

        private Restriction() {
            this.exceptionPackages = NO_EXCEPTIONS;
        }
    }

    public void setUserRestrictions(Bundle restrictions, IBinder token, int userHandle) {
        checkSystemUid("setUserRestrictions");
        Preconditions.checkNotNull(restrictions);
        Preconditions.checkNotNull(token);
        for (int i = 0; i < 91; i++) {
            String restriction = AppOpsManager.opToRestriction(i);
            if (restriction != null) {
                setUserRestrictionNoCheck(i, restrictions.getBoolean(restriction, false), token, userHandle, null);
            }
        }
    }

    public void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle, String[] exceptionPackages) {
        if (Binder.getCallingPid() != Process.myPid()) {
            this.mContext.enforcePermission("android.permission.MANAGE_APP_OPS_RESTRICTIONS", Binder.getCallingPid(), Binder.getCallingUid(), null);
        }
        if (userHandle != UserHandle.getCallingUserId() && this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0 && this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS") != 0) {
            throw new SecurityException("Need INTERACT_ACROSS_USERS_FULL or INTERACT_ACROSS_USERS to interact cross user ");
        }
        verifyIncomingOp(code);
        Preconditions.checkNotNull(token);
        setUserRestrictionNoCheck(code, restricted, token, userHandle, exceptionPackages);
    }

    private void setUserRestrictionNoCheck(int code, boolean restricted, IBinder token, int userHandle, String[] exceptionPackages) {
        synchronized (this) {
            ClientRestrictionState restrictionState = this.mOpUserRestrictions.get(token);
            if (restrictionState == null) {
                try {
                    restrictionState = new ClientRestrictionState(token);
                    this.mOpUserRestrictions.put(token, restrictionState);
                } catch (RemoteException e) {
                    return;
                }
            }
            if (restrictionState.setRestriction(code, restricted, exceptionPackages, userHandle)) {
                this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$GUeKjlbzT65s86vaxy5gvOajuhw.INSTANCE, this, Integer.valueOf(code), -2));
            }
            if (restrictionState.isDefault()) {
                this.mOpUserRestrictions.remove(token);
                restrictionState.destroy();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyWatchersOfChange(int code, int uid) {
        synchronized (this) {
            ArraySet<ModeCallback> callbacks = this.mOpModeWatchers.get(code);
            if (callbacks == null) {
                return;
            }
            ArraySet<ModeCallback> clonedCallbacks = new ArraySet<>(callbacks);
            notifyOpChanged(clonedCallbacks, code, uid, (String) null);
        }
    }

    public void removeUser(int userHandle) throws RemoteException {
        checkSystemUid("removeUser");
        synchronized (this) {
            int tokenCount = this.mOpUserRestrictions.size();
            for (int i = tokenCount - 1; i >= 0; i--) {
                ClientRestrictionState opRestrictions = this.mOpUserRestrictions.valueAt(i);
                opRestrictions.removeUser(userHandle);
            }
            removeUidsForUserLocked(userHandle);
        }
    }

    public boolean isOperationActive(int code, int uid, String packageName) {
        if (Binder.getCallingUid() == uid || this.mContext.checkCallingOrSelfPermission("android.permission.WATCH_APPOPS") == 0) {
            verifyIncomingOp(code);
            String resolvedPackageName = resolvePackageName(uid, packageName);
            if (resolvedPackageName == null) {
                return false;
            }
            synchronized (this) {
                for (int i = this.mClients.size() - 1; i >= 0; i--) {
                    ClientState client = this.mClients.valueAt(i);
                    for (int j = client.mStartedOps.size() - 1; j >= 0; j--) {
                        Op op = client.mStartedOps.get(j);
                        if (op.op == code && op.uidState.uid == uid) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        return false;
    }

    public void setHistoryParameters(int mode, long baseSnapshotInterval, int compressionStep) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_APPOPS", "setHistoryParameters");
        this.mHistoricalRegistry.setHistoryParameters(mode, baseSnapshotInterval, compressionStep);
    }

    public void offsetHistory(long offsetMillis) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_APPOPS", "offsetHistory");
        this.mHistoricalRegistry.offsetHistory(offsetMillis);
    }

    public void addHistoricalOps(AppOpsManager.HistoricalOps ops) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_APPOPS", "addHistoricalOps");
        this.mHistoricalRegistry.addHistoricalOps(ops);
    }

    public void resetHistoryParameters() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_APPOPS", "resetHistoryParameters");
        this.mHistoricalRegistry.resetHistoryParameters();
    }

    public void clearHistory() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_APPOPS", "clearHistory");
        this.mHistoricalRegistry.clearHistory();
    }

    private void removeUidsForUserLocked(int userHandle) {
        for (int i = this.mUidStates.size() - 1; i >= 0; i--) {
            int uid = this.mUidStates.keyAt(i);
            if (UserHandle.getUserId(uid) == userHandle) {
                this.mUidStates.removeAt(i);
            }
        }
    }

    private void checkSystemUid(String function) {
        int uid = Binder.getCallingUid();
        if (uid != 1000) {
            throw new SecurityException(function + " must by called by the system");
        }
    }

    private static String resolvePackageName(int uid, String packageName) {
        if (uid == 0) {
            return "root";
        }
        if (uid == 2000) {
            return "com.android.shell";
        }
        if (uid == 1013) {
            return "media";
        }
        if (uid == 1041) {
            return "audioserver";
        }
        if (uid == 1047) {
            return "cameraserver";
        }
        if (uid == 1000 && packageName == null) {
            return PackageManagerService.PLATFORM_PACKAGE_NAME;
        }
        return packageName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public static int resolveUid(String packageName) {
        boolean z;
        if (packageName == null) {
            return -1;
        }
        switch (packageName.hashCode()) {
            case -31178072:
                if (packageName.equals("cameraserver")) {
                    z = true;
                    break;
                }
                z = true;
                break;
            case 3506402:
                if (packageName.equals("root")) {
                    z = false;
                    break;
                }
                z = true;
                break;
            case 103772132:
                if (packageName.equals("media")) {
                    z = true;
                    break;
                }
                z = true;
                break;
            case 109403696:
                if (packageName.equals("shell")) {
                    z = true;
                    break;
                }
                z = true;
                break;
            case 1344606873:
                if (packageName.equals("audioserver")) {
                    z = true;
                    break;
                }
                z = true;
                break;
            default:
                z = true;
                break;
        }
        if (z) {
            if (!z) {
                if (!z) {
                    if (!z) {
                        if (!z) {
                            return -1;
                        }
                        return 1047;
                    }
                    return 1041;
                }
                return 1013;
            }
            return 2000;
        }
        return 0;
    }

    private static String[] getPackagesForUid(int uid) {
        String[] packageNames = null;
        if (AppGlobals.getPackageManager() != null) {
            try {
                packageNames = AppGlobals.getPackageManager().getPackagesForUid(uid);
            } catch (RemoteException e) {
            }
        }
        if (packageNames == null) {
            return EmptyArray.STRING;
        }
        return packageNames;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ClientRestrictionState implements IBinder.DeathRecipient {
        SparseArray<String[]> perUserExcludedPackages;
        SparseArray<boolean[]> perUserRestrictions;
        private final IBinder token;

        public ClientRestrictionState(IBinder token) throws RemoteException {
            token.linkToDeath(this, 0);
            this.token = token;
        }

        public boolean setRestriction(int code, boolean restricted, String[] excludedPackages, int userId) {
            int[] users;
            boolean changed = false;
            if (this.perUserRestrictions == null && restricted) {
                this.perUserRestrictions = new SparseArray<>();
            }
            if (userId == -1) {
                List<UserInfo> liveUsers = UserManager.get(AppOpsService.this.mContext).getUsers(false);
                users = new int[liveUsers.size()];
                for (int i = 0; i < liveUsers.size(); i++) {
                    users[i] = liveUsers.get(i).id;
                }
            } else {
                users = new int[]{userId};
            }
            if (this.perUserRestrictions != null) {
                for (int thisUserId : users) {
                    boolean[] userRestrictions = this.perUserRestrictions.get(thisUserId);
                    if (userRestrictions == null && restricted) {
                        userRestrictions = new boolean[91];
                        this.perUserRestrictions.put(thisUserId, userRestrictions);
                    }
                    if (userRestrictions != null && userRestrictions[code] != restricted) {
                        userRestrictions[code] = restricted;
                        if (!restricted && isDefault(userRestrictions)) {
                            this.perUserRestrictions.remove(thisUserId);
                            userRestrictions = null;
                        }
                        changed = true;
                    }
                    if (userRestrictions != null) {
                        boolean noExcludedPackages = ArrayUtils.isEmpty(excludedPackages);
                        if (this.perUserExcludedPackages == null && !noExcludedPackages) {
                            this.perUserExcludedPackages = new SparseArray<>();
                        }
                        SparseArray<String[]> sparseArray = this.perUserExcludedPackages;
                        if (sparseArray != null && !Arrays.equals(excludedPackages, sparseArray.get(thisUserId))) {
                            if (noExcludedPackages) {
                                this.perUserExcludedPackages.remove(thisUserId);
                                if (this.perUserExcludedPackages.size() <= 0) {
                                    this.perUserExcludedPackages = null;
                                }
                            } else {
                                this.perUserExcludedPackages.put(thisUserId, excludedPackages);
                            }
                            changed = true;
                        }
                    }
                }
            }
            return changed;
        }

        public boolean hasRestriction(int restriction, String packageName, int userId) {
            boolean[] restrictions;
            String[] perUserExclusions;
            SparseArray<boolean[]> sparseArray = this.perUserRestrictions;
            if (sparseArray == null || (restrictions = sparseArray.get(userId)) == null || !restrictions[restriction]) {
                return false;
            }
            SparseArray<String[]> sparseArray2 = this.perUserExcludedPackages;
            if (sparseArray2 == null || (perUserExclusions = sparseArray2.get(userId)) == null) {
                return true;
            }
            return true ^ ArrayUtils.contains(perUserExclusions, packageName);
        }

        public void removeUser(int userId) {
            SparseArray<String[]> sparseArray = this.perUserExcludedPackages;
            if (sparseArray != null) {
                sparseArray.remove(userId);
                if (this.perUserExcludedPackages.size() <= 0) {
                    this.perUserExcludedPackages = null;
                }
            }
            SparseArray<boolean[]> sparseArray2 = this.perUserRestrictions;
            if (sparseArray2 != null) {
                sparseArray2.remove(userId);
                if (this.perUserRestrictions.size() <= 0) {
                    this.perUserRestrictions = null;
                }
            }
        }

        public boolean isDefault() {
            SparseArray<boolean[]> sparseArray = this.perUserRestrictions;
            return sparseArray == null || sparseArray.size() <= 0;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (AppOpsService.this) {
                AppOpsService.this.mOpUserRestrictions.remove(this.token);
                if (this.perUserRestrictions == null) {
                    return;
                }
                int userCount = this.perUserRestrictions.size();
                for (int i = 0; i < userCount; i++) {
                    boolean[] restrictions = this.perUserRestrictions.valueAt(i);
                    int restrictionCount = restrictions.length;
                    for (int j = 0; j < restrictionCount; j++) {
                        if (restrictions[j]) {
                            final int changedCode = j;
                            AppOpsService.this.mHandler.post(new Runnable() { // from class: com.android.server.appop.-$$Lambda$AppOpsService$ClientRestrictionState$uMVYManZlOG3nljcsmHU5SaC48k
                                @Override // java.lang.Runnable
                                public final void run() {
                                    AppOpsService.ClientRestrictionState.this.lambda$binderDied$0$AppOpsService$ClientRestrictionState(changedCode);
                                }
                            });
                        }
                    }
                }
                destroy();
            }
        }

        public /* synthetic */ void lambda$binderDied$0$AppOpsService$ClientRestrictionState(int changedCode) {
            AppOpsService.this.notifyWatchersOfChange(changedCode, -2);
        }

        public void destroy() {
            this.token.unlinkToDeath(this, 0);
        }

        private boolean isDefault(boolean[] array) {
            if (ArrayUtils.isEmpty(array)) {
                return true;
            }
            for (boolean value : array) {
                if (value) {
                    return false;
                }
            }
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AppOpsManagerInternalImpl extends AppOpsManagerInternal {
        private AppOpsManagerInternalImpl() {
        }

        public void setDeviceAndProfileOwners(SparseIntArray owners) {
            synchronized (AppOpsService.this) {
                AppOpsService.this.mProfileOwners = owners;
            }
        }

        public void setAllPkgModesToDefault(int code, int uid) {
            AppOpsService.this.setAllPkgModesToDefault(code, uid);
        }
    }
}
