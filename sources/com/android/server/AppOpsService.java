package com.android.server;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
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
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.Xml;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
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
    private final Constants mConstants;
    Context mContext;
    boolean mFastWriteScheduled;
    final AtomicFile mFile;
    final Handler mHandler;
    long mLastUptime;
    SparseIntArray mProfileOwners;
    boolean mWriteScheduled;
    private static final int[] PROCESS_STATE_TO_UID_STATE = {0, 0, 1, 2, 3, 3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5};
    static final String[] UID_STATE_NAMES = {"pers ", "top  ", "fgsvc", "fg   ", "bg   ", "cch  "};
    static final String[] UID_STATE_TIME_ATTRS = {"tp", "tt", "tfs", "tf", "tb", "tc"};
    static final String[] UID_STATE_REJECT_ATTRS = {"rp", "rt", "rfs", "rf", "rb", "rc"};
    private final AppOpsManagerInternalImpl mAppOpsManagerInternal = new AppOpsManagerInternalImpl();
    final Runnable mWriteRunner = new Runnable() { // from class: com.android.server.AppOpsService.1
        @Override // java.lang.Runnable
        public void run() {
            synchronized (AppOpsService.this) {
                AppOpsService.this.mWriteScheduled = false;
                AppOpsService.this.mFastWriteScheduled = false;
                AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() { // from class: com.android.server.AppOpsService.1.1
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
    private final ArrayMap<IBinder, ClientRestrictionState> mOpUserRestrictions = new ArrayMap<>();
    final SparseArray<ArraySet<ModeCallback>> mOpModeWatchers = new SparseArray<>();
    final ArrayMap<String, ArraySet<ModeCallback>> mPackageModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, ModeCallback> mModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<ActiveCallback>> mActiveWatchers = new ArrayMap<>();
    final SparseArray<SparseArray<Restriction>> mAudioRestrictions = new SparseArray<>();
    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

    /* loaded from: classes.dex */
    private final class Constants extends ContentObserver {
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
            String value = this.mResolver != null ? Settings.Global.getString(this.mResolver, "app_ops_constants") : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
        public int state = 5;
        public int pendingState = 5;

        public UidState(int uid) {
            this.uid = uid;
        }

        public void clear() {
            this.pkgOps = null;
            this.opModes = null;
        }

        public boolean isDefault() {
            return (this.pkgOps == null || this.pkgOps.isEmpty()) && (this.opModes == null || this.opModes.size() <= 0);
        }

        int evalMode(int mode) {
            if (mode == 4) {
                return this.state <= 2 ? 0 : 1;
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
            if (this.opModes != null) {
                for (int i = this.opModes.size() - 1; i >= 0; i--) {
                    if (this.opModes.valueAt(i) == 4) {
                        if (which == null) {
                            which = new SparseBooleanArray();
                        }
                        evalForegroundWatchers(this.opModes.keyAt(i), watchers, which);
                    }
                }
            }
            if (this.pkgOps != null) {
                for (int i2 = this.pkgOps.size() - 1; i2 >= 0; i2--) {
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
        int duration;
        int mode;
        final int op;
        final String packageName;
        String proxyPackageName;
        int startNesting;
        long startRealtime;
        final int uid;
        final UidState uidState;
        int proxyUid = -1;
        long[] time = new long[6];
        long[] rejectTime = new long[6];

        Op(UidState _uidState, String _packageName, int _op) {
            this.uidState = _uidState;
            this.uid = _uidState.uid;
            this.packageName = _packageName;
            this.op = _op;
            this.mode = AppOpsManager.opToDefaultMode(this.op);
        }

        boolean hasAnyTime() {
            for (int i = 0; i < 6; i++) {
                if (this.time[i] != 0 || this.rejectTime[i] != 0) {
                    return true;
                }
            }
            return false;
        }

        int getMode() {
            return this.uidState.evalMode(this.mode);
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
            return uid == -2 || this.mWatchingUid < 0 || this.mWatchingUid == uid;
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

    /* loaded from: classes.dex */
    final class ClientState extends Binder implements IBinder.DeathRecipient {
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
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.setExternalSourcesPolicy(new PackageManagerInternal.ExternalSourcesPolicy() { // from class: com.android.server.AppOpsService.2
            public int getPackageTrustedToInstallApps(String packageName, int uid) {
                int appOpMode = AppOpsService.this.checkOperation(66, uid, packageName);
                if (appOpMode != 0) {
                    return appOpMode != 2 ? 2 : 1;
                }
                return 0;
            }
        });
        StorageManagerInternal storageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
        storageManagerInternal.addExternalStoragePolicy(new StorageManagerInternal.ExternalStorageMountPolicy() { // from class: com.android.server.AppOpsService.3
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
                        if (uid == op.uid && packageName.equals(op.packageName)) {
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
                    if (op2.duration == -1) {
                        scheduleOpActiveChangedIfNeededLocked(op2.op, op2.uid, op2.packageName, false);
                    }
                }
            }
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

    /* JADX WARN: Removed duplicated region for block: B:27:0x0049 A[Catch: all -> 0x007f, TryCatch #0 {, blocks: (B:4:0x0002, B:6:0x000c, B:8:0x0010, B:13:0x001c, B:15:0x0024, B:17:0x0028, B:23:0x003a, B:19:0x002d, B:21:0x0031, B:22:0x0036, B:25:0x0045, B:27:0x0049, B:29:0x0056, B:31:0x0065, B:33:0x006f, B:34:0x0077, B:35:0x007a, B:24:0x0042, B:36:0x007d), top: B:41:0x0002 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void updateUidProcState(int r12, int r13) {
        /*
            r11 = this;
            monitor-enter(r11)
            r0 = 1
            com.android.server.AppOpsService$UidState r1 = r11.getUidStateLocked(r12, r0)     // Catch: java.lang.Throwable -> L7f
            int[] r2 = com.android.server.AppOpsService.PROCESS_STATE_TO_UID_STATE     // Catch: java.lang.Throwable -> L7f
            r2 = r2[r13]     // Catch: java.lang.Throwable -> L7f
            if (r1 == 0) goto L7d
            int r3 = r1.pendingState     // Catch: java.lang.Throwable -> L7f
            if (r3 == r2) goto L7d
            int r3 = r1.pendingState     // Catch: java.lang.Throwable -> L7f
            r1.pendingState = r2     // Catch: java.lang.Throwable -> L7f
            int r4 = r1.state     // Catch: java.lang.Throwable -> L7f
            if (r2 < r4) goto L42
            r4 = 2
            if (r2 > r4) goto L1c
            goto L42
        L1c:
            long r5 = r1.pendingStateCommitTime     // Catch: java.lang.Throwable -> L7f
            r7 = 0
            int r5 = (r5 > r7 ? 1 : (r5 == r7 ? 0 : -1))
            if (r5 != 0) goto L45
            int r5 = r1.state     // Catch: java.lang.Throwable -> L7f
            if (r5 > r0) goto L2d
            com.android.server.AppOpsService$Constants r4 = r11.mConstants     // Catch: java.lang.Throwable -> L7f
            long r4 = r4.TOP_STATE_SETTLE_TIME     // Catch: java.lang.Throwable -> L7f
        L2c:
            goto L3a
        L2d:
            int r5 = r1.state     // Catch: java.lang.Throwable -> L7f
            if (r5 > r4) goto L36
            com.android.server.AppOpsService$Constants r4 = r11.mConstants     // Catch: java.lang.Throwable -> L7f
            long r4 = r4.FG_SERVICE_STATE_SETTLE_TIME     // Catch: java.lang.Throwable -> L7f
            goto L2c
        L36:
            com.android.server.AppOpsService$Constants r4 = r11.mConstants     // Catch: java.lang.Throwable -> L7f
            long r4 = r4.BG_STATE_SETTLE_TIME     // Catch: java.lang.Throwable -> L7f
        L3a:
            long r6 = android.os.SystemClock.uptimeMillis()     // Catch: java.lang.Throwable -> L7f
            long r6 = r6 + r4
            r1.pendingStateCommitTime = r6     // Catch: java.lang.Throwable -> L7f
            goto L45
        L42:
            r11.commitUidPendingStateLocked(r1)     // Catch: java.lang.Throwable -> L7f
        L45:
            int r4 = r1.startNesting     // Catch: java.lang.Throwable -> L7f
            if (r4 == 0) goto L7d
            long r4 = java.lang.System.currentTimeMillis()     // Catch: java.lang.Throwable -> L7f
            android.util.ArrayMap<java.lang.String, com.android.server.AppOpsService$Ops> r6 = r1.pkgOps     // Catch: java.lang.Throwable -> L7f
            int r6 = r6.size()     // Catch: java.lang.Throwable -> L7f
            int r6 = r6 - r0
        L54:
            if (r6 < 0) goto L7d
            android.util.ArrayMap<java.lang.String, com.android.server.AppOpsService$Ops> r7 = r1.pkgOps     // Catch: java.lang.Throwable -> L7f
            java.lang.Object r7 = r7.valueAt(r6)     // Catch: java.lang.Throwable -> L7f
            com.android.server.AppOpsService$Ops r7 = (com.android.server.AppOpsService.Ops) r7     // Catch: java.lang.Throwable -> L7f
            int r8 = r7.size()     // Catch: java.lang.Throwable -> L7f
            int r8 = r8 - r0
        L63:
            if (r8 < 0) goto L7a
            java.lang.Object r9 = r7.valueAt(r8)     // Catch: java.lang.Throwable -> L7f
            com.android.server.AppOpsService$Op r9 = (com.android.server.AppOpsService.Op) r9     // Catch: java.lang.Throwable -> L7f
            int r10 = r9.startNesting     // Catch: java.lang.Throwable -> L7f
            if (r10 <= 0) goto L77
            long[] r10 = r9.time     // Catch: java.lang.Throwable -> L7f
            r10[r3] = r4     // Catch: java.lang.Throwable -> L7f
            long[] r10 = r9.time     // Catch: java.lang.Throwable -> L7f
            r10[r2] = r4     // Catch: java.lang.Throwable -> L7f
        L77:
            int r8 = r8 + (-1)
            goto L63
        L7a:
            int r6 = r6 + (-1)
            goto L54
        L7d:
            monitor-exit(r11)     // Catch: java.lang.Throwable -> L7f
            return
        L7f:
            r0 = move-exception
            monitor-exit(r11)     // Catch: java.lang.Throwable -> L7f
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.AppOpsService.updateUidProcState(int, int):void");
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
        long j;
        long j2;
        Ops ops2 = pkgOps;
        long elapsedNow = SystemClock.elapsedRealtime();
        int i = -1;
        if (ops != null) {
            ArrayList<AppOpsManager.OpEntry> resOps = null;
            int j3 = 0;
            while (j3 < ops.length) {
                Op curOp = ops2.get(ops[j3]);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    boolean running = curOp.duration == -1;
                    if (running) {
                        j = elapsedNow - curOp.startRealtime;
                    } else {
                        j = curOp.duration;
                    }
                    long duration = j;
                    resOps.add(new AppOpsManager.OpEntry(curOp.op, curOp.mode, curOp.time, curOp.rejectTime, (int) duration, running, curOp.proxyUid, curOp.proxyPackageName));
                }
                j3++;
                ops2 = pkgOps;
            }
            return resOps;
        }
        ArrayList<AppOpsManager.OpEntry> resOps2 = new ArrayList<>();
        int j4 = 0;
        while (j4 < pkgOps.size()) {
            Op curOp2 = ops2.valueAt(j4);
            boolean running2 = curOp2.duration == i;
            if (running2) {
                j2 = elapsedNow - curOp2.startRealtime;
            } else {
                j2 = curOp2.duration;
            }
            long duration2 = j2;
            resOps2.add(new AppOpsManager.OpEntry(curOp2.op, curOp2.mode, curOp2.time, curOp2.rejectTime, (int) duration2, running2, curOp2.proxyUid, curOp2.proxyPackageName));
            j4++;
            elapsedNow = elapsedNow;
            i = -1;
        }
        return resOps2;
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(SparseIntArray uidOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        int j = 0;
        if (ops != null) {
            while (j < ops.length) {
                int index = uidOps.indexOfKey(ops[j]);
                if (index >= 0) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(new AppOpsManager.OpEntry(uidOps.keyAt(index), uidOps.valueAt(index), 0L, 0L, 0, -1, (String) null));
                }
                j++;
            }
        } else {
            resOps = new ArrayList<>();
            while (j < uidOps.size()) {
                resOps.add(new AppOpsManager.OpEntry(uidOps.keyAt(j), uidOps.valueAt(j), 0L, 0L, 0, -1, (String) null));
                j++;
            }
        }
        return resOps;
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:31:0x006f -> B:29:0x006d). Please submit an issue!!! */
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        Throwable th;
        this.mContext.enforcePermission("android.permission.GET_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            try {
                int uidStateCount = this.mUidStates.size();
                ArrayList<AppOpsManager.PackageOps> res = null;
                for (int i = 0; i < uidStateCount; i++) {
                    try {
                        UidState uidState = this.mUidStates.valueAt(i);
                        if (uidState.pkgOps != null && !uidState.pkgOps.isEmpty()) {
                            ArrayMap<String, Ops> packages = uidState.pkgOps;
                            int packageCount = packages.size();
                            ArrayList<AppOpsManager.PackageOps> res2 = res;
                            for (int j = 0; j < packageCount; j++) {
                                try {
                                    Ops pkgOps = packages.valueAt(j);
                                    ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
                                    if (resOps != null) {
                                        if (res2 == null) {
                                            res2 = new ArrayList<>();
                                        }
                                        AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(pkgOps.packageName, pkgOps.uidState.uid, resOps);
                                        res2.add(resPackage);
                                    }
                                } catch (Throwable th2) {
                                    th = th2;
                                    throw th;
                                }
                            }
                            res = res2;
                        }
                    } catch (Throwable th3) {
                        th = th3;
                        throw th;
                    }
                }
                return res;
            } catch (Throwable th4) {
                th = th4;
            }
        }
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

    void enforceManageAppOpsModes(int callingPid, int callingUid, int targetUid) {
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
            int i = 0;
            UidState uidState = getUidStateLocked(uid, false);
            String str = null;
            if (uidState == null) {
                if (mode == defaultMode) {
                    return;
                }
                UidState uidState2 = new UidState(uid);
                uidState2.opModes = new SparseIntArray();
                uidState2.opModes.put(code2, mode);
                this.mUidStates.put(uid, uidState2);
                scheduleWriteLocked();
            } else if (uidState.opModes != null) {
                if (uidState.opModes.get(code2) == mode) {
                    return;
                }
                if (mode != defaultMode) {
                    uidState.opModes.put(code2, mode);
                } else {
                    uidState.opModes.delete(code2);
                    if (uidState.opModes.size() <= 0) {
                        uidState.opModes = null;
                    }
                }
                scheduleWriteLocked();
            } else if (mode != defaultMode) {
                uidState.opModes = new SparseIntArray();
                uidState.opModes.put(code2, mode);
                scheduleWriteLocked();
            }
            String[] uidPackageNames = getPackagesForUid(uid);
            ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs = null;
            synchronized (this) {
                try {
                    ArraySet<ModeCallback> callbacks = this.mOpModeWatchers.get(code2);
                    if (callbacks != null) {
                        int callbackCount = callbacks.size();
                        ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs2 = null;
                        for (int i2 = 0; i2 < callbackCount; i2++) {
                            try {
                                ModeCallback callback = callbacks.valueAt(i2);
                                ArraySet<String> changedPackages = new ArraySet<>();
                                Collections.addAll(changedPackages, uidPackageNames);
                                if (callbackSpecs2 == null) {
                                    callbackSpecs2 = new ArrayMap<>();
                                }
                                callbackSpecs2.put(callback, changedPackages);
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        }
                        callbackSpecs = callbackSpecs2;
                    }
                    int length = uidPackageNames.length;
                    ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs3 = callbackSpecs;
                    int i3 = 0;
                    while (i3 < length) {
                        try {
                            String uidPackageName = uidPackageNames[i3];
                            ArraySet<ModeCallback> callbacks2 = this.mPackageModeWatchers.get(uidPackageName);
                            if (callbacks2 != null) {
                                if (callbackSpecs3 == null) {
                                    ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs4 = new ArrayMap<>();
                                    callbackSpecs3 = callbackSpecs4;
                                }
                                int callbackCount2 = callbacks2.size();
                                for (int i4 = i; i4 < callbackCount2; i4++) {
                                    ModeCallback callback2 = callbacks2.valueAt(i4);
                                    ArraySet<String> changedPackages2 = callbackSpecs3.get(callback2);
                                    if (changedPackages2 == null) {
                                        changedPackages2 = new ArraySet<>();
                                        callbackSpecs3.put(callback2, changedPackages2);
                                    }
                                    changedPackages2.add(uidPackageName);
                                }
                            }
                            i3++;
                            i = 0;
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                    if (callbackSpecs3 == null) {
                        return;
                    }
                    int i5 = 0;
                    while (i5 < callbackSpecs3.size()) {
                        ModeCallback callback3 = callbackSpecs3.keyAt(i5);
                        ArraySet<String> reportedPackageNames = callbackSpecs3.valueAt(i5);
                        if (reportedPackageNames == null) {
                            this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$lxgFmOnGguOiLyfUZbyOpNBfTVw.INSTANCE, this, callback3, Integer.valueOf(code2), Integer.valueOf(uid), str));
                        } else {
                            int reportedPackageCount = reportedPackageNames.size();
                            int j = 0;
                            while (true) {
                                int j2 = j;
                                if (j2 < reportedPackageCount) {
                                    String reportedPackageName = reportedPackageNames.valueAt(j2);
                                    this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$lxgFmOnGguOiLyfUZbyOpNBfTVw.INSTANCE, this, callback3, Integer.valueOf(code2), Integer.valueOf(uid), reportedPackageName));
                                    j = j2 + 1;
                                }
                            }
                        }
                        i5++;
                        str = null;
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
        }
    }

    public void setMode(int code, int uid, String packageName, int mode) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingOp(code);
        ArraySet<ModeCallback> repCbs = null;
        int code2 = AppOpsManager.opToSwitch(code);
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            Op op = getOpLocked(code2, uid, packageName, true);
            if (op != null && op.mode != mode) {
                op.mode = mode;
                if (uidState != null) {
                    uidState.evalForegroundOps(this.mOpModeWatchers);
                }
                ArraySet<? extends ModeCallback> arraySet = this.mOpModeWatchers.get(code2);
                if (arraySet != null) {
                    if (0 == 0) {
                        repCbs = new ArraySet<>();
                    }
                    repCbs.addAll(arraySet);
                }
                ArraySet<? extends ModeCallback> arraySet2 = this.mPackageModeWatchers.get(packageName);
                if (arraySet2 != null) {
                    if (repCbs == null) {
                        repCbs = new ArraySet<>();
                    }
                    repCbs.addAll(arraySet2);
                }
                if (mode == AppOpsManager.opToDefaultMode(op.op)) {
                    pruneOp(op, uid, packageName);
                }
                scheduleFastWriteLocked();
            }
        }
        if (repCbs != null) {
            this.mHandler.sendMessage(PooledLambda.obtainMessage(new QuintConsumer() { // from class: com.android.server.-$$Lambda$AppOpsService$1lQKm3WHEUQsD7KzYyJ5stQSc04
                public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5) {
                    ((AppOpsService) obj).notifyOpChanged((ArraySet) obj2, ((Integer) obj3).intValue(), ((Integer) obj4).intValue(), (String) obj5);
                }
            }, this, repCbs, Integer.valueOf(code2), Integer.valueOf(uid), packageName));
        }
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
        int N = cbs.size();
        boolean duplicate = false;
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

    /* JADX WARN: Removed duplicated region for block: B:148:0x01d5 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:62:0x0116 A[Catch: all -> 0x01ef, TryCatch #9 {all -> 0x01ef, blocks: (B:51:0x00f1, B:93:0x01d5, B:56:0x00fa, B:59:0x0104, B:60:0x0110, B:62:0x0116, B:64:0x0124, B:67:0x012b, B:69:0x0139, B:71:0x0147, B:73:0x0153, B:84:0x01af, B:86:0x01b5, B:88:0x01bf, B:90:0x01c7, B:92:0x01d0, B:99:0x01eb), top: B:142:0x00f1 }] */
    /* JADX WARN: Removed duplicated region for block: B:90:0x01c7 A[Catch: all -> 0x01ef, TryCatch #9 {all -> 0x01ef, blocks: (B:51:0x00f1, B:93:0x01d5, B:56:0x00fa, B:59:0x0104, B:60:0x0110, B:62:0x0116, B:64:0x0124, B:67:0x012b, B:69:0x0139, B:71:0x0147, B:73:0x0153, B:84:0x01af, B:86:0x01b5, B:88:0x01bf, B:90:0x01c7, B:92:0x01d0, B:99:0x01eb), top: B:142:0x00f1 }] */
    /* JADX WARN: Removed duplicated region for block: B:92:0x01d0 A[Catch: all -> 0x01ef, TryCatch #9 {all -> 0x01ef, blocks: (B:51:0x00f1, B:93:0x01d5, B:56:0x00fa, B:59:0x0104, B:60:0x0110, B:62:0x0116, B:64:0x0124, B:67:0x012b, B:69:0x0139, B:71:0x0147, B:73:0x0153, B:84:0x01af, B:86:0x01b5, B:88:0x01bf, B:90:0x01c7, B:92:0x01d0, B:99:0x01eb), top: B:142:0x00f1 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void resetAllModes(int r30, java.lang.String r31) {
        /*
            Method dump skipped, instructions count: 631
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.AppOpsService.resetAllModes(int, java.lang.String):void");
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
        Preconditions.checkArgumentInRange(op, -1, 77, "Invalid op code: " + op);
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

    public int checkOperation(int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return 1;
        }
        synchronized (this) {
            if (isOpRestrictedLocked(uid, code, resolvedPackageName)) {
                return 1;
            }
            int code2 = AppOpsManager.opToSwitch(code);
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState != null && uidState.opModes != null && uidState.opModes.indexOfKey(code2) >= 0) {
                return uidState.opModes.get(code2);
            }
            Op op = getOpLocked(code2, uid, resolvedPackageName, false);
            if (op == null) {
                return AppOpsManager.opToDefaultMode(code2);
            }
            return op.mode;
        }
    }

    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
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
        try {
            return AppGlobals.getPackageManager().isPackageSuspendedForUser(pkg, UserHandle.getUserId(uid));
        } catch (RemoteException e) {
            throw new SecurityException("Could not talk to package manager service");
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
        this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$UKMH8n9xZqCOX59uFPylskhjBgo.INSTANCE, this, Integer.valueOf(code), -2));
    }

    public int checkPackage(int uid, String packageName) {
        Preconditions.checkNotNull(packageName);
        synchronized (this) {
            Ops ops = getOpsRawLocked(uid, packageName, true, true);
            if (ops != null) {
                return 0;
            }
            return 2;
        }
    }

    public int noteProxyOperation(int code, String proxyPackageName, int proxiedUid, String proxiedPackageName) {
        verifyIncomingOp(code);
        int proxyUid = Binder.getCallingUid();
        String resolveProxyPackageName = resolvePackageName(proxyUid, proxyPackageName);
        if (resolveProxyPackageName == null) {
            return 1;
        }
        int proxyMode = noteOperationUnchecked(code, proxyUid, resolveProxyPackageName, -1, null);
        if (proxyMode != 0 || Binder.getCallingUid() == proxiedUid) {
            return proxyMode;
        }
        String resolveProxiedPackageName = resolvePackageName(proxiedUid, proxiedPackageName);
        if (resolveProxiedPackageName == null) {
            return 1;
        }
        return noteOperationUnchecked(code, proxiedUid, resolveProxiedPackageName, proxyMode, resolveProxyPackageName);
    }

    public int noteOperation(int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return 1;
        }
        return noteOperationUnchecked(code, uid, resolvedPackageName, 0, null);
    }

    private int noteOperationUnchecked(int code, int uid, String packageName, int proxyUid, String proxyPackageName) {
        synchronized (this) {
            Ops ops = getOpsRawLocked(uid, packageName, true, false);
            if (ops == null) {
                return 2;
            }
            Op op = getOpLocked(ops, code, true);
            if (isOpRestrictedLocked(uid, code, packageName)) {
                return 1;
            }
            UidState uidState = ops.uidState;
            if (op.duration == -1) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName + " code " + code + " time=" + op.time[uidState.state] + " duration=" + op.duration);
            }
            op.duration = 0;
            int switchCode = AppOpsManager.opToSwitch(code);
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                int uidMode = uidState.evalMode(uidState.opModes.get(switchCode));
                if (uidMode != 0) {
                    op.rejectTime[uidState.state] = System.currentTimeMillis();
                    return uidMode;
                }
            } else {
                Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
                int mode = switchOp.getMode();
                if (mode != 0) {
                    op.rejectTime[uidState.state] = System.currentTimeMillis();
                    return mode;
                }
            }
            op.time[uidState.state] = System.currentTimeMillis();
            op.rejectTime[uidState.state] = 0;
            op.proxyUid = proxyUid;
            op.proxyPackageName = proxyPackageName;
            return 0;
        }
    }

    public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
        int watchedUid = -1;
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        if (this.mContext.checkCallingOrSelfPermission("android.permission.WATCH_APPOPS") != 0) {
            watchedUid = callingUid;
        }
        if (ops != null) {
            Preconditions.checkArrayElementsInRange(ops, 0, 77, "Invalid op code in: " + Arrays.toString(ops));
        }
        if (callback == null) {
            return;
        }
        synchronized (this) {
            SparseArray<ActiveCallback> callbacks = this.mActiveWatchers.get(callback.asBinder());
            if (callbacks == null) {
                callbacks = new SparseArray<>();
                this.mActiveWatchers.put(callback.asBinder(), callbacks);
            }
            SparseArray<ActiveCallback> callbacks2 = callbacks;
            ActiveCallback activeCallback = new ActiveCallback(callback, watchedUid, callingUid, callingPid);
            for (int op : ops) {
                callbacks2.put(op, activeCallback);
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
                if (i == 0) {
                    activeCallbacks.valueAt(i).destroy();
                }
            }
        }
    }

    public int startOperation(IBinder token, int code, int uid, String packageName, boolean startIfModeDefault) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return 1;
        }
        ClientState client = (ClientState) token;
        synchronized (this) {
            try {
                try {
                    Ops ops = getOpsRawLocked(uid, resolvedPackageName, true, false);
                    if (ops == null) {
                        return 2;
                    }
                    Op op = getOpLocked(ops, code, true);
                    if (isOpRestrictedLocked(uid, code, resolvedPackageName)) {
                        return 1;
                    }
                    int switchCode = AppOpsManager.opToSwitch(code);
                    UidState uidState = ops.uidState;
                    if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                        int uidMode = uidState.evalMode(uidState.opModes.get(switchCode));
                        if (uidMode != 0 && (!startIfModeDefault || uidMode != 3)) {
                            op.rejectTime[uidState.state] = System.currentTimeMillis();
                            return uidMode;
                        }
                    } else {
                        Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
                        int mode = switchOp.getMode();
                        if (mode != 0 && (!startIfModeDefault || mode != 3)) {
                            op.rejectTime[uidState.state] = System.currentTimeMillis();
                            return mode;
                        }
                    }
                    if (op.startNesting == 0) {
                        op.startRealtime = SystemClock.elapsedRealtime();
                        op.time[uidState.state] = System.currentTimeMillis();
                        op.rejectTime[uidState.state] = 0;
                        op.duration = -1;
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
        synchronized (this) {
            Op op = getOpLocked(code, uid, resolvedPackageName, true);
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
                Slog.wtf(TAG, "Operation not started: uid=" + op.uid + " pkg=" + op.packageName + " op=" + AppOpsManager.opToName(op.op));
                return;
            }
            finishOperationLocked(op, false);
            if (op.startNesting <= 0) {
                scheduleOpActiveChangedIfNeededLocked(code, uid, packageName, false);
            }
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
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new HexConsumer() { // from class: com.android.server.-$$Lambda$AppOpsService$NC5g1JY4YR6y4VePru4TO7AKp8M
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

    public int permissionToOpCode(String permission) {
        if (permission == null) {
            return -1;
        }
        return AppOpsManager.permissionToOpCode(permission);
    }

    void finishOperationLocked(Op op, boolean finishNested) {
        if (op.startNesting <= 1 || finishNested) {
            if (op.startNesting == 1 || finishNested) {
                op.duration = (int) (SystemClock.elapsedRealtime() - op.startRealtime);
                op.time[op.uidState.state] = System.currentTimeMillis();
            } else {
                Slog.w(TAG, "Finishing op nesting under-run: uid " + op.uid + " pkg " + op.packageName + " code " + op.op + " time=" + op.time + " duration=" + op.duration + " nesting=" + op.startNesting);
            }
            if (op.startNesting >= 1) {
                op.uidState.startNesting -= op.startNesting;
            }
            op.startNesting = 0;
            return;
        }
        op.startNesting--;
        op.uidState.startNesting--;
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid() || Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        this.mContext.enforcePermission("android.permission.UPDATE_APP_OPS_STATS", Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void verifyIncomingOp(int op) {
        if (op >= 0 && op < 78) {
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
        } else if (uidState.pendingStateCommitTime != 0) {
            if (uidState.pendingStateCommitTime < this.mLastUptime) {
                commitUidPendingStateLocked(uidState);
                return uidState;
            }
            this.mLastUptime = SystemClock.uptimeMillis();
            if (uidState.pendingStateCommitTime < this.mLastUptime) {
                commitUidPendingStateLocked(uidState);
                return uidState;
            }
            return uidState;
        } else {
            return uidState;
        }
    }

    private void commitUidPendingStateLocked(UidState uidState) {
        int code;
        ArraySet<ModeCallback> callbacks;
        int pkgi;
        int i;
        ModeCallback callback;
        int i2 = 1;
        boolean lastForeground = uidState.state <= 2;
        boolean nowForeground = uidState.pendingState <= 2;
        uidState.state = uidState.pendingState;
        uidState.pendingStateCommitTime = 0L;
        if (uidState.hasForegroundWatchers && lastForeground != nowForeground) {
            int cbi = uidState.foregroundOps.size() - 1;
            while (true) {
                int fgi = cbi;
                if (fgi >= 0) {
                    if (uidState.foregroundOps.valueAt(fgi) && (callbacks = this.mOpModeWatchers.get((code = uidState.foregroundOps.keyAt(fgi)))) != null) {
                        int pkgi2 = callbacks.size() - i2;
                        while (true) {
                            int cbi2 = pkgi2;
                            if (cbi2 >= 0) {
                                ModeCallback callback2 = callbacks.valueAt(cbi2);
                                if ((callback2.mFlags & i2) != 0 && callback2.isWatchingUid(uidState.uid)) {
                                    int i3 = 4;
                                    int i4 = (uidState.opModes == null || uidState.opModes.get(code) != 4) ? 0 : i2;
                                    if (uidState.pkgOps != null) {
                                        int pkgi3 = uidState.pkgOps.size() - i2;
                                        while (true) {
                                            int pkgi4 = pkgi3;
                                            if (pkgi4 >= 0) {
                                                Op op = uidState.pkgOps.valueAt(pkgi4).get(code);
                                                if (i4 == 0 && (op == null || op.mode != i3)) {
                                                    pkgi = pkgi4;
                                                    i = i3;
                                                    callback = callback2;
                                                } else {
                                                    pkgi = pkgi4;
                                                    i = 4;
                                                    callback = callback2;
                                                    this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$lxgFmOnGguOiLyfUZbyOpNBfTVw.INSTANCE, this, callback2, Integer.valueOf(code), Integer.valueOf(uidState.uid), uidState.pkgOps.keyAt(pkgi4)));
                                                }
                                                pkgi3 = pkgi - 1;
                                                i3 = i;
                                                callback2 = callback;
                                            }
                                        }
                                    }
                                }
                                pkgi2 = cbi2 - 1;
                                i2 = 1;
                            }
                        }
                    }
                    cbi = fgi - 1;
                    i2 = 1;
                } else {
                    return;
                }
            }
        }
    }

    private Ops getOpsRawLocked(int uid, String packageName, boolean edit, boolean uidMismatchExpected) {
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
            if (edit) {
                boolean isPrivileged = false;
                if (uid != 0) {
                    long ident = Binder.clearCallingIdentity();
                    int pkgUid = -1;
                    try {
                        try {
                            ApplicationInfo appInfo = ActivityThread.getPackageManager().getApplicationInfo(packageName, 268435456, UserHandle.getUserId(uid));
                            if (appInfo != null) {
                                pkgUid = appInfo.uid;
                                isPrivileged = (appInfo.privateFlags & 8) != 0;
                            } else {
                                pkgUid = resolveUid(packageName);
                                if (pkgUid >= 0) {
                                    isPrivileged = false;
                                }
                            }
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Could not contact PackageManager", e);
                        }
                        if (pkgUid != uid) {
                            if (!uidMismatchExpected) {
                                RuntimeException ex = new RuntimeException("here");
                                ex.fillInStackTrace();
                                Slog.w(TAG, "Bad call: specified package " + packageName + " under uid " + uid + " but it is really " + pkgUid, ex);
                            }
                            return null;
                        }
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
                Ops ops2 = new Ops(packageName, uidState, isPrivileged);
                uidState.pkgOps.put(packageName, ops2);
                return ops2;
            }
            return null;
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

    private Op getOpLocked(int code, int uid, String packageName, boolean edit) {
        Ops ops = getOpsRawLocked(uid, packageName, edit, false);
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

    private boolean isOpRestrictedLocked(int uid, int code, String packageName) {
        int userHandle = UserHandle.getUserId(uid);
        int restrictionSetCount = this.mOpUserRestrictions.size();
        for (int i = 0; i < restrictionSetCount; i++) {
            ClientRestrictionState restrictionState = this.mOpUserRestrictions.valueAt(i);
            if (restrictionState.hasRestriction(code, packageName, userHandle)) {
                if (AppOpsManager.opAllowSystemBypassRestriction(code)) {
                    synchronized (this) {
                        Ops ops = getOpsRawLocked(uid, packageName, true, false);
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

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:41:0x00a0 -> B:83:0x017e). Please submit an issue!!! */
    void readState() {
        XmlPullParser parser;
        int type;
        synchronized (this.mFile) {
            synchronized (this) {
                try {
                    FileInputStream stream = this.mFile.openRead();
                    try {
                        this.mUidStates.clear();
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
                                        } catch (IllegalStateException e) {
                                            Slog.w(TAG, "Failed parsing " + e);
                                            if (0 == 0) {
                                                this.mUidStates.clear();
                                            }
                                            stream.close();
                                        }
                                    } catch (NumberFormatException e2) {
                                        Slog.w(TAG, "Failed parsing " + e2);
                                        if (0 == 0) {
                                            this.mUidStates.clear();
                                        }
                                        stream.close();
                                    }
                                } catch (IndexOutOfBoundsException e3) {
                                    Slog.w(TAG, "Failed parsing " + e3);
                                    if (0 == 0) {
                                        this.mUidStates.clear();
                                    }
                                    stream.close();
                                }
                            } catch (NullPointerException e4) {
                                Slog.w(TAG, "Failed parsing " + e4);
                                if (0 == 0) {
                                    this.mUidStates.clear();
                                }
                                stream.close();
                            }
                        } catch (IOException e5) {
                            Slog.w(TAG, "Failed parsing " + e5);
                            if (0 == 0) {
                                this.mUidStates.clear();
                            }
                            stream.close();
                        } catch (XmlPullParserException e6) {
                            Slog.w(TAG, "Failed parsing " + e6);
                            if (0 == 0) {
                                this.mUidStates.clear();
                            }
                            stream.close();
                        }
                    } catch (IOException e7) {
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
                } catch (FileNotFoundException e8) {
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
        if (oldVersion < 1) {
            Slog.d(TAG, "Upgrading app-ops xml from version " + oldVersion + " to 1");
            if (oldVersion == -1) {
                upgradeRunAnyInBackgroundLocked();
            }
            scheduleFastWriteLocked();
        }
    }

    void readUidOps(XmlPullParser parser) throws NumberFormatException, XmlPullParserException, IOException {
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

    void readPackage(XmlPullParser parser) throws NumberFormatException, XmlPullParserException, IOException {
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

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r10v3 */
    /* JADX WARN: Type inference failed for: r10v4 */
    /* JADX WARN: Type inference failed for: r10v7 */
    void readUid(XmlPullParser parser, String pkgName) throws NumberFormatException, XmlPullParserException, IOException {
        int outerDepth;
        char c;
        char c2;
        char c3;
        int outerDepth2;
        String str = null;
        int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        String isPrivilegedString = parser.getAttributeValue(null, "p");
        boolean isPrivileged = false;
        boolean z = true;
        if (isPrivilegedString == null) {
            try {
                IPackageManager packageManager = ActivityThread.getPackageManager();
                if (packageManager == null) {
                    return;
                }
                ApplicationInfo appInfo = ActivityThread.getPackageManager().getApplicationInfo(pkgName, 0, UserHandle.getUserId(uid));
                if (appInfo != null) {
                    isPrivileged = (appInfo.privateFlags & 8) != 0;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not contact PackageManager", e);
            }
        } else {
            isPrivileged = Boolean.parseBoolean(isPrivilegedString);
        }
        int outerDepth3 = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != z && (type != 3 || parser.getDepth() > outerDepth3)) {
                if (type == 3) {
                    outerDepth = outerDepth3;
                } else if (type == 4) {
                    outerDepth = outerDepth3;
                } else {
                    String tagName = parser.getName();
                    if (tagName.equals("op")) {
                        UidState uidState = getUidStateLocked(uid, z);
                        if (uidState.pkgOps == null) {
                            uidState.pkgOps = new ArrayMap<>();
                        }
                        Op op = new Op(uidState, pkgName, Integer.parseInt(parser.getAttributeValue(str, "n")));
                        int i = parser.getAttributeCount() - (z ? 1 : 0);
                        ?? r10 = z;
                        while (i >= 0) {
                            String name = parser.getAttributeName(i);
                            String value = parser.getAttributeValue(i);
                            switch (name.hashCode()) {
                                case 100:
                                    if (name.equals("d")) {
                                        c = r10;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION /* 109 */:
                                    if (name.equals("m")) {
                                        c = 0;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 114:
                                    if (name.equals("r")) {
                                        c = 17;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case HdmiCecKeycode.CEC_KEYCODE_F4_YELLOW /* 116 */:
                                    if (name.equals("t")) {
                                        c = 16;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3584:
                                    if (name.equals("pp")) {
                                        c = 3;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3589:
                                    if (name.equals("pu")) {
                                        c = 2;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3632:
                                    if (name.equals("rb")) {
                                        c = 14;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3633:
                                    if (name.equals("rc")) {
                                        c = 15;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3636:
                                    if (name.equals("rf")) {
                                        c = '\r';
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3646:
                                    if (name.equals("rp")) {
                                        c = '\n';
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3650:
                                    if (name.equals("rt")) {
                                        c = 11;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3694:
                                    if (name.equals("tb")) {
                                        c = '\b';
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3695:
                                    if (name.equals("tc")) {
                                        c = '\t';
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3698:
                                    if (name.equals("tf")) {
                                        c = 7;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3708:
                                    if (name.equals("tp")) {
                                        c = 4;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 3712:
                                    if (name.equals("tt")) {
                                        c = 5;
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 112831:
                                    if (name.equals("rfs")) {
                                        c = '\f';
                                        break;
                                    }
                                    c = 65535;
                                    break;
                                case 114753:
                                    if (name.equals("tfs")) {
                                        c = 6;
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
                                    c2 = 3;
                                    c3 = 4;
                                    op.mode = Integer.parseInt(value);
                                    outerDepth2 = outerDepth3;
                                    break;
                                case 1:
                                    c2 = 3;
                                    c3 = 4;
                                    op.duration = Integer.parseInt(value);
                                    outerDepth2 = outerDepth3;
                                    break;
                                case 2:
                                    c2 = 3;
                                    c3 = 4;
                                    op.proxyUid = Integer.parseInt(value);
                                    outerDepth2 = outerDepth3;
                                    break;
                                case 3:
                                    c2 = 3;
                                    c3 = 4;
                                    op.proxyPackageName = value;
                                    outerDepth2 = outerDepth3;
                                    break;
                                case 4:
                                    c2 = 3;
                                    c3 = 4;
                                    op.time[0] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    break;
                                case 5:
                                    c2 = 3;
                                    c3 = 4;
                                    op.time[r10] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    break;
                                case 6:
                                    c2 = 3;
                                    c3 = 4;
                                    op.time[2] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    break;
                                case 7:
                                    c3 = 4;
                                    c2 = 3;
                                    op.time[3] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    break;
                                case '\b':
                                    c3 = 4;
                                    op.time[4] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    break;
                                case '\t':
                                    op.time[5] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case '\n':
                                    op.rejectTime[0] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case 11:
                                    op.rejectTime[r10] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case '\f':
                                    op.rejectTime[2] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case '\r':
                                    op.rejectTime[3] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case 14:
                                    op.rejectTime[4] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case 15:
                                    op.rejectTime[5] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case 16:
                                    op.time[r10] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                case 17:
                                    op.rejectTime[r10] = Long.parseLong(value);
                                    outerDepth2 = outerDepth3;
                                    c2 = 3;
                                    c3 = 4;
                                    break;
                                default:
                                    c2 = 3;
                                    c3 = 4;
                                    StringBuilder sb = new StringBuilder();
                                    outerDepth2 = outerDepth3;
                                    sb.append("Unknown attribute in 'op' tag: ");
                                    sb.append(name);
                                    Slog.w(TAG, sb.toString());
                                    break;
                            }
                            i--;
                            outerDepth3 = outerDepth2;
                            r10 = 1;
                        }
                        outerDepth = outerDepth3;
                        Ops ops = uidState.pkgOps.get(pkgName);
                        if (ops == null) {
                            ops = new Ops(pkgName, uidState, isPrivileged);
                            uidState.pkgOps.put(pkgName, ops);
                        }
                        ops.put(op.op, op);
                    } else {
                        outerDepth = outerDepth3;
                        Slog.w(TAG, "Unknown element under <pkg>: " + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }
                outerDepth3 = outerDepth;
                str = null;
                z = true;
            }
        }
        UidState uidState2 = getUidStateLocked(uid, false);
        if (uidState2 != null) {
            uidState2.evalForegroundOps(this.mOpModeWatchers);
        }
    }

    void writeState() {
        int i;
        List<AppOpsManager.PackageOps> allOps;
        int uidStateCount;
        synchronized (this.mFile) {
            try {
                try {
                    FileOutputStream stream = this.mFile.startWrite();
                    String str = null;
                    List<AppOpsManager.PackageOps> allOps2 = getPackagesForOps(null);
                    try {
                        FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                        fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
                        fastXmlSerializer.startDocument(null, true);
                        fastXmlSerializer.startTag(null, "app-ops");
                        fastXmlSerializer.attribute(null, "v", String.valueOf(1));
                        int uidStateCount2 = this.mUidStates.size();
                        for (int i2 = 0; i2 < uidStateCount2; i2++) {
                            try {
                                UidState uidState = this.mUidStates.valueAt(i2);
                                if (uidState.opModes != null && uidState.opModes.size() > 0) {
                                    fastXmlSerializer.startTag(null, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                    fastXmlSerializer.attribute(null, "n", Integer.toString(uidState.uid));
                                    SparseIntArray uidOpModes = uidState.opModes;
                                    int opCount = uidOpModes.size();
                                    for (int j = 0; j < opCount; j++) {
                                        int op = uidOpModes.keyAt(j);
                                        int mode = uidOpModes.valueAt(j);
                                        fastXmlSerializer.startTag(null, "op");
                                        fastXmlSerializer.attribute(null, "n", Integer.toString(op));
                                        fastXmlSerializer.attribute(null, "m", Integer.toString(mode));
                                        fastXmlSerializer.endTag(null, "op");
                                    }
                                    fastXmlSerializer.endTag(null, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                }
                            } catch (IOException e) {
                                e = e;
                                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                                this.mFile.failWrite(stream);
                            }
                        }
                        if (allOps2 != null) {
                            String lastPkg = null;
                            int i3 = 0;
                            while (i3 < allOps2.size()) {
                                AppOpsManager.PackageOps pkg = allOps2.get(i3);
                                if (!pkg.getPackageName().equals(lastPkg)) {
                                    if (lastPkg != null) {
                                        fastXmlSerializer.endTag(str, "pkg");
                                    }
                                    lastPkg = pkg.getPackageName();
                                    fastXmlSerializer.startTag(str, "pkg");
                                    fastXmlSerializer.attribute(str, "n", lastPkg);
                                }
                                fastXmlSerializer.startTag(str, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                fastXmlSerializer.attribute(str, "n", Integer.toString(pkg.getUid()));
                                synchronized (this) {
                                    try {
                                        Ops ops = getOpsRawLocked(pkg.getUid(), pkg.getPackageName(), false, false);
                                        if (ops != null) {
                                            try {
                                                fastXmlSerializer.attribute(str, "p", Boolean.toString(ops.isPrivileged));
                                                i = 0;
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
                                        } else {
                                            i = 0;
                                            fastXmlSerializer.attribute(str, "p", Boolean.toString(false));
                                        }
                                    } catch (Throwable th3) {
                                        th = th3;
                                    }
                                }
                                List<AppOpsManager.OpEntry> ops2 = pkg.getOps();
                                int j2 = i;
                                while (j2 < ops2.size()) {
                                    AppOpsManager.OpEntry op2 = ops2.get(j2);
                                    fastXmlSerializer.startTag(str, "op");
                                    fastXmlSerializer.attribute(str, "n", Integer.toString(op2.getOp()));
                                    if (op2.getMode() != AppOpsManager.opToDefaultMode(op2.getOp())) {
                                        fastXmlSerializer.attribute(str, "m", Integer.toString(op2.getMode()));
                                    }
                                    int k = i;
                                    while (k < 6) {
                                        long time = op2.getLastTimeFor(k);
                                        AppOpsManager.OpEntry op3 = op2;
                                        if (time != 0) {
                                            allOps = allOps2;
                                            try {
                                                fastXmlSerializer.attribute(null, UID_STATE_TIME_ATTRS[k], Long.toString(time));
                                            } catch (IOException e2) {
                                                e = e2;
                                                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                                                this.mFile.failWrite(stream);
                                            }
                                        } else {
                                            allOps = allOps2;
                                        }
                                        long rejectTime = op3.getLastRejectTimeFor(k);
                                        String lastPkg2 = lastPkg;
                                        AppOpsManager.PackageOps pkg2 = pkg;
                                        if (rejectTime != 0) {
                                            uidStateCount = uidStateCount2;
                                            fastXmlSerializer.attribute(null, UID_STATE_REJECT_ATTRS[k], Long.toString(rejectTime));
                                        } else {
                                            uidStateCount = uidStateCount2;
                                        }
                                        k++;
                                        op2 = op3;
                                        allOps2 = allOps;
                                        pkg = pkg2;
                                        lastPkg = lastPkg2;
                                        uidStateCount2 = uidStateCount;
                                    }
                                    List<AppOpsManager.PackageOps> allOps3 = allOps2;
                                    int uidStateCount3 = uidStateCount2;
                                    String lastPkg3 = lastPkg;
                                    AppOpsManager.PackageOps pkg3 = pkg;
                                    AppOpsManager.OpEntry op4 = op2;
                                    int dur = op4.getDuration();
                                    if (dur != 0) {
                                        fastXmlSerializer.attribute(null, "d", Integer.toString(dur));
                                    }
                                    int proxyUid = op4.getProxyUid();
                                    if (proxyUid != -1) {
                                        fastXmlSerializer.attribute(null, "pu", Integer.toString(proxyUid));
                                    }
                                    String proxyPackageName = op4.getProxyPackageName();
                                    if (proxyPackageName != null) {
                                        fastXmlSerializer.attribute(null, "pp", proxyPackageName);
                                    }
                                    fastXmlSerializer.endTag(null, "op");
                                    j2++;
                                    allOps2 = allOps3;
                                    pkg = pkg3;
                                    lastPkg = lastPkg3;
                                    uidStateCount2 = uidStateCount3;
                                    str = null;
                                    i = 0;
                                }
                                List<AppOpsManager.PackageOps> allOps4 = allOps2;
                                int uidStateCount4 = uidStateCount2;
                                String lastPkg4 = lastPkg;
                                fastXmlSerializer.endTag(null, WatchlistLoggingHandler.WatchlistEventKeys.UID);
                                i3++;
                                allOps2 = allOps4;
                                lastPkg = lastPkg4;
                                uidStateCount2 = uidStateCount4;
                                str = null;
                            }
                            if (lastPkg != null) {
                                fastXmlSerializer.endTag(null, "pkg");
                            }
                        }
                        fastXmlSerializer.endTag(null, "app-ops");
                        fastXmlSerializer.endDocument();
                        this.mFile.finishWrite(stream);
                    } catch (IOException e3) {
                        e = e3;
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
            if (this.opStr == null) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            this.op = strOpToOp(this.opStr, err);
            if (this.op < 0) {
                return -1;
            }
            if (this.modeStr != null) {
                int strModeToMode = strModeToMode(this.modeStr, err);
                this.mode = strModeToMode;
                return strModeToMode < 0 ? -1 : 0;
            }
            this.mode = defMode;
            return 0;
        }

        /* JADX WARN: Code restructure failed: missing block: B:54:0x00ba, code lost:
            if (r0 >= r11.packageName.length()) goto L89;
         */
        /* JADX WARN: Code restructure failed: missing block: B:55:0x00bc, code lost:
            r4 = r11.packageName.substring(1, r0);
         */
        /* JADX WARN: Code restructure failed: missing block: B:56:0x00c2, code lost:
            r5 = java.lang.Integer.parseInt(r4);
            r8 = r11.packageName.charAt(r0);
         */
        /* JADX WARN: Code restructure failed: missing block: B:57:0x00cc, code lost:
            r0 = r0 + 1;
            r9 = r0;
         */
        /* JADX WARN: Code restructure failed: missing block: B:59:0x00d5, code lost:
            if (r9 >= r11.packageName.length()) goto L82;
         */
        /* JADX WARN: Code restructure failed: missing block: B:61:0x00dd, code lost:
            if (r11.packageName.charAt(r9) < '0') goto L81;
         */
        /* JADX WARN: Code restructure failed: missing block: B:63:0x00e5, code lost:
            if (r11.packageName.charAt(r9) > '9') goto L67;
         */
        /* JADX WARN: Code restructure failed: missing block: B:64:0x00e7, code lost:
            r9 = r9 + 1;
         */
        /* JADX WARN: Code restructure failed: missing block: B:65:0x00ea, code lost:
            if (r9 <= r0) goto L89;
         */
        /* JADX WARN: Code restructure failed: missing block: B:66:0x00ec, code lost:
            r6 = r11.packageName.substring(r0, r9);
         */
        /* JADX WARN: Code restructure failed: missing block: B:67:0x00f2, code lost:
            r7 = java.lang.Integer.parseInt(r6);
         */
        /* JADX WARN: Code restructure failed: missing block: B:68:0x00f8, code lost:
            if (r8 != 'a') goto L76;
         */
        /* JADX WARN: Code restructure failed: missing block: B:69:0x00fa, code lost:
            r11.nonpackageUid = android.os.UserHandle.getUid(r5, r7 + 10000);
         */
        /* JADX WARN: Code restructure failed: missing block: B:71:0x0105, code lost:
            if (r8 != 's') goto L74;
         */
        /* JADX WARN: Code restructure failed: missing block: B:72:0x0107, code lost:
            r11.nonpackageUid = android.os.UserHandle.getUid(r5, r7);
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        int parseUserPackageOp(boolean r12, java.io.PrintWriter r13) throws android.os.RemoteException {
            /*
                Method dump skipped, instructions count: 349
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.AppOpsService.Shell.parseUserPackageOp(boolean, java.io.PrintWriter):int");
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
        pw.println("  set [--user <USER_ID>] <PACKAGE | UID> <OP> <MODE>");
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
        pw.println("    <PACKAGE> an Android package name.");
        pw.println("    <OP>      an AppOps operation.");
        pw.println("    <MODE>    one of allow, ignore, deny, or default");
        pw.println("    <USER_ID> the user id under which the package is installed. If --user is not");
        pw.println("              specified, the current user is assumed.");
    }

    static int onShellCommand(Shell shell, String cmd) {
        char c;
        List<AppOpsManager.PackageOps> ops;
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        PrintWriter err = shell.getErrPrintWriter();
        try {
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
                    if (shell.packageName != null) {
                        shell.mInterface.setMode(shell.op, shell.packageUid, shell.packageName, mode);
                    } else {
                        shell.mInterface.setUidMode(shell.op, shell.nonpackageUid, mode);
                    }
                    return 0;
                case 1:
                    int res2 = shell.parseUserPackageOp(false, err);
                    if (res2 < 0) {
                        return res2;
                    }
                    if (shell.packageName != null) {
                        ops = shell.mInterface.getOpsForPackage(shell.packageUid, shell.packageName, shell.op != -1 ? new int[]{shell.op} : null);
                    } else {
                        ops = shell.mInterface.getUidOps(shell.nonpackageUid, shell.op != -1 ? new int[]{shell.op} : null);
                    }
                    if (ops != null && ops.size() > 0) {
                        long now = System.currentTimeMillis();
                        for (int i = 0; i < ops.size(); i++) {
                            List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();
                            for (int j = 0; j < entries.size(); j++) {
                                AppOpsManager.OpEntry ent = entries.get(j);
                                pw.print(AppOpsManager.opToName(ent.getOp()));
                                pw.print(": ");
                                pw.print(AppOpsManager.modeToName(ent.getMode()));
                                if (ent.getTime() != 0) {
                                    pw.print("; time=");
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
                            }
                        }
                        return 0;
                    }
                    pw.println("No operations.");
                    if (shell.op > -1 && shell.op < 78) {
                        pw.println("Default mode: " + AppOpsManager.modeToName(AppOpsManager.opToDefaultMode(shell.op)));
                    }
                    return 0;
                case 2:
                    int res3 = shell.parseUserOpMode(1, err);
                    if (res3 < 0) {
                        return res3;
                    }
                    List<AppOpsManager.PackageOps> ops2 = shell.mInterface.getPackagesForOps(new int[]{shell.op});
                    if (ops2 != null && ops2.size() > 0) {
                        for (int i2 = 0; i2 < ops2.size(); i2++) {
                            AppOpsManager.PackageOps pkg = ops2.get(i2);
                            boolean hasMatch = false;
                            List<AppOpsManager.OpEntry> entries2 = ops2.get(i2).getOps();
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
                    if (shell.packageName == null) {
                        return -1;
                    }
                    shell.mInterface.startOperation(shell.mToken, shell.op, shell.packageUid, shell.packageName, true);
                    return 0;
                case 7:
                    int res5 = shell.parseUserPackageOp(true, err);
                    if (res5 < 0) {
                        return res5;
                    }
                    if (shell.packageName == null) {
                        return -1;
                    }
                    shell.mInterface.finishOperation(shell.mToken, shell.op, shell.packageUid, shell.packageName);
                    return 0;
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
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
    }

    private void dumpTimesLocked(PrintWriter pw, String firstPrefix, String prefix, long[] times, long now, SimpleDateFormat sdf, Date date) {
        boolean hasTime = false;
        int i = 0;
        while (true) {
            if (i < 6) {
                if (times[i] == 0) {
                    i++;
                } else {
                    hasTime = true;
                    break;
                }
            } else {
                break;
            }
        }
        if (!hasTime) {
            return;
        }
        boolean first = true;
        for (int i2 = 0; i2 < 6; i2++) {
            if (times[i2] != 0) {
                pw.print(first ? firstPrefix : prefix);
                first = false;
                pw.print(UID_STATE_NAMES[i2]);
                pw.print(" = ");
                date.setTime(times[i2]);
                pw.print(sdf.format(date));
                pw.print(" (");
                TimeUtils.formatDuration(times[i2] - now, pw);
                pw.println(")");
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:557:0x091c, code lost:
        continue;
     */
    /* JADX WARN: Removed duplicated region for block: B:269:0x0582  */
    /* JADX WARN: Removed duplicated region for block: B:270:0x0585  */
    /* JADX WARN: Removed duplicated region for block: B:272:0x0588  */
    /* JADX WARN: Removed duplicated region for block: B:273:0x058b  */
    /* JADX WARN: Removed duplicated region for block: B:289:0x05c1  */
    /* JADX WARN: Removed duplicated region for block: B:323:0x063c  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    protected void dump(java.io.FileDescriptor r60, java.io.PrintWriter r61, java.lang.String[] r62) {
        /*
            Method dump skipped, instructions count: 2769
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.AppOpsService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
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
        for (int i = 0; i < 78; i++) {
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
                this.mHandler.sendMessage(PooledLambda.obtainMessage($$Lambda$AppOpsService$UKMH8n9xZqCOX59uFPylskhjBgo.INSTANCE, this, Integer.valueOf(code), -2));
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
                        if (op.op == code && op.uid == uid) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        return false;
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
        char c;
        if (packageName == null) {
            return -1;
        }
        switch (packageName.hashCode()) {
            case -31178072:
                if (packageName.equals("cameraserver")) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case 3506402:
                if (packageName.equals("root")) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case 103772132:
                if (packageName.equals("media")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 109403696:
                if (packageName.equals("shell")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            case 1344606873:
                if (packageName.equals("audioserver")) {
                    c = 3;
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
                return 0;
            case 1:
                return 2000;
            case 2:
                return 1013;
            case 3:
                return 1041;
            case 4:
                return 1047;
            default:
                return -1;
        }
    }

    private static String[] getPackagesForUid(int uid) {
        String[] packageNames = null;
        try {
            packageNames = AppGlobals.getPackageManager().getPackagesForUid(uid);
        } catch (RemoteException e) {
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
            int[] users2 = users;
            if (this.perUserRestrictions != null) {
                for (int thisUserId : users2) {
                    boolean[] userRestrictions = this.perUserRestrictions.get(thisUserId);
                    if (userRestrictions == null && restricted) {
                        userRestrictions = new boolean[78];
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
                        if (this.perUserExcludedPackages != null && !Arrays.equals(excludedPackages, this.perUserExcludedPackages.get(thisUserId))) {
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
            if (this.perUserRestrictions == null || (restrictions = this.perUserRestrictions.get(userId)) == null || !restrictions[restriction]) {
                return false;
            }
            if (this.perUserExcludedPackages == null || (perUserExclusions = this.perUserExcludedPackages.get(userId)) == null) {
                return true;
            }
            return true ^ ArrayUtils.contains(perUserExclusions, packageName);
        }

        public void removeUser(int userId) {
            if (this.perUserExcludedPackages != null) {
                this.perUserExcludedPackages.remove(userId);
                if (this.perUserExcludedPackages.size() <= 0) {
                    this.perUserExcludedPackages = null;
                }
            }
            if (this.perUserRestrictions != null) {
                this.perUserRestrictions.remove(userId);
                if (this.perUserRestrictions.size() <= 0) {
                    this.perUserRestrictions = null;
                }
            }
        }

        public boolean isDefault() {
            return this.perUserRestrictions == null || this.perUserRestrictions.size() <= 0;
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
                            AppOpsService.this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$AppOpsService$ClientRestrictionState$1l-YeBkF_Y04gZU4mqxsyXZNtwY
                                @Override // java.lang.Runnable
                                public final void run() {
                                    AppOpsService.this.notifyWatchersOfChange(changedCode, -2);
                                }
                            });
                        }
                    }
                }
                destroy();
            }
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

    /* loaded from: classes.dex */
    private final class AppOpsManagerInternalImpl extends AppOpsManagerInternal {
        private AppOpsManagerInternalImpl() {
        }

        public void setDeviceAndProfileOwners(SparseIntArray owners) {
            synchronized (AppOpsService.this) {
                AppOpsService.this.mProfileOwners = owners;
            }
        }
    }
}
