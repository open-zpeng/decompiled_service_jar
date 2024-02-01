package com.android.server.net;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.DataUsageRequest;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkIdentity;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.BestClock;
import android.os.Binder;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.net.VpnInfo;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FileRotator;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.NetworkManagementService;
import com.android.server.NetworkManagementSocketTagger;
import com.android.server.job.controllers.JobStatus;
import com.android.server.usage.AppStandbyController;
import com.android.server.utils.PriorityDump;
import com.xiaopeng.server.input.xpInputActionHandler;
import com.xiaopeng.server.net.netstats.NetworkStatsHandler;
import com.xiaopeng.server.net.netstats.TrafficStatsEntry;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
/* loaded from: classes.dex */
public class NetworkStatsService extends INetworkStatsService.Stub {
    @VisibleForTesting
    public static final String ACTION_NETWORK_STATS_POLL = "com.android.server.action.NETWORK_STATS_POLL";
    public static final String ACTION_NETWORK_STATS_UPDATED = "com.android.server.action.NETWORK_STATS_UPDATED";
    private static final int DUMP_STATS_SESSION_COUNT = 20;
    private static final int FLAG_PERSIST_ALL = 3;
    private static final int FLAG_PERSIST_FORCE = 256;
    private static final int FLAG_PERSIST_NETWORK = 1;
    private static final int FLAG_PERSIST_UID = 2;
    private static final int MSG_PERFORM_POLL = 1;
    private static final int MSG_PERFORM_POLL_REGISTER_ALERT = 2;
    private static final int PERFORM_POLL_DELAY_MS = 1000;
    private static final long POLL_RATE_LIMIT_MS = 15000;
    private static final String PREFIX_DEV = "dev";
    private static final String PREFIX_UID = "uid";
    private static final String PREFIX_UID_TAG = "uid_tag";
    private static final String PREFIX_XT = "xt";
    private static final String TAG_NETSTATS_ERROR = "netstats_error";
    private static int TYPE_RX_BYTES = 0;
    private static int TYPE_RX_PACKETS = 0;
    private static int TYPE_TCP_RX_PACKETS = 0;
    private static int TYPE_TCP_TX_PACKETS = 0;
    private static int TYPE_TX_BYTES = 0;
    private static int TYPE_TX_PACKETS = 0;
    public static final String VT_INTERFACE = "vt_data0";
    @GuardedBy("mStatsLock")
    private String mActiveIface;
    private final AlarmManager mAlarmManager;
    private final File mBaseDir;
    private final Clock mClock;
    private final Context mContext;
    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mDevRecorder;
    private long mGlobalAlertBytes;
    private Handler mHandler;
    private Handler.Callback mHandlerCallback;
    private long mLastStatsSessionPoll;
    private final INetworkManagementService mNetworkManager;
    private PendingIntent mPollIntent;
    private final NetworkStatsSettings mSettings;
    private final NetworkStatsObservers mStatsObservers;
    private final File mSystemDir;
    private volatile boolean mSystemReady;
    private final TelephonyManager mTeleManager;
    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mUidRecorder;
    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mUidTagRecorder;
    private final PowerManager.WakeLock mWakeLock;
    @GuardedBy("mStatsLock")
    private NetworkStatsRecorder mXtRecorder;
    @GuardedBy("mStatsLock")
    private NetworkStatsCollection mXtStatsCached;
    static final String TAG = "NetworkStats";
    static final boolean LOGD = Log.isLoggable(TAG, 3);
    static final boolean LOGV = Log.isLoggable(TAG, 2);
    private final Object mStatsLock = new Object();
    @GuardedBy("mStatsLock")
    private final ArrayMap<String, NetworkIdentitySet> mActiveIfaces = new ArrayMap<>();
    @GuardedBy("mStatsLock")
    private final ArrayMap<String, NetworkIdentitySet> mActiveUidIfaces = new ArrayMap<>();
    @GuardedBy("mStatsLock")
    private String[] mMobileIfaces = new String[0];
    @GuardedBy("mStatsLock")
    private Network[] mDefaultNetworks = new Network[0];
    @GuardedBy("mStatsLock")
    private VpnInfo[] mVpnInfos = new VpnInfo[0];
    private final DropBoxNonMonotonicObserver mNonMonotonicObserver = new DropBoxNonMonotonicObserver();
    private SparseIntArray mActiveUidCounterSet = new SparseIntArray();
    private NetworkStats mUidOperations = new NetworkStats(0, 10);
    private long mPersistThreshold = 2097152;
    @GuardedBy("mOpenSessionCallsPerUid")
    private final SparseIntArray mOpenSessionCallsPerUid = new SparseIntArray();
    private BroadcastReceiver mTetherReceiver = new BroadcastReceiver() { // from class: com.android.server.net.NetworkStatsService.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            NetworkStatsService.this.performPoll(1);
        }
    };
    private BroadcastReceiver mPollReceiver = new BroadcastReceiver() { // from class: com.android.server.net.NetworkStatsService.3
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            NetworkStatsService.this.performPoll(3);
            NetworkStatsService.this.registerGlobalAlert();
        }
    };
    private BroadcastReceiver mRemovedReceiver = new BroadcastReceiver() { // from class: com.android.server.net.NetworkStatsService.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            int uid = intent.getIntExtra("android.intent.extra.UID", -1);
            if (uid == -1) {
                return;
            }
            synchronized (NetworkStatsService.this.mStatsLock) {
                NetworkStatsService.this.mWakeLock.acquire();
                NetworkStatsService.this.removeUidsLocked(uid);
                NetworkStatsService.this.mWakeLock.release();
            }
        }
    };
    private BroadcastReceiver mUserReceiver = new BroadcastReceiver() { // from class: com.android.server.net.NetworkStatsService.5
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            int userId = intent.getIntExtra("android.intent.extra.user_handle", -1);
            if (userId == -1) {
                return;
            }
            synchronized (NetworkStatsService.this.mStatsLock) {
                NetworkStatsService.this.mWakeLock.acquire();
                NetworkStatsService.this.removeUserLocked(userId);
                NetworkStatsService.this.mWakeLock.release();
            }
        }
    };
    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() { // from class: com.android.server.net.NetworkStatsService.6
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            synchronized (NetworkStatsService.this.mStatsLock) {
                NetworkStatsService.this.shutdownLocked();
            }
        }
    };
    private INetworkManagementEventObserver mAlertObserver = new BaseNetworkObserver() { // from class: com.android.server.net.NetworkStatsService.7
        public void limitReached(String limitName, String iface) {
            NetworkStatsService.this.mContext.enforceCallingOrSelfPermission("android.permission.CONNECTIVITY_INTERNAL", NetworkStatsService.TAG);
            if (NetworkManagementService.LIMIT_GLOBAL_ALERT.equals(limitName) && !NetworkStatsService.this.mHandler.hasMessages(2)) {
                NetworkStatsService.this.mHandler.sendEmptyMessageDelayed(2, 1000L);
            }
        }
    };
    private final boolean mUseBpfTrafficStats = new File("/sys/fs/bpf/traffic_uid_stats_map").exists();

    private static native long nativeGetIfaceStat(String str, int i, boolean z);

    private static native long nativeGetTotalStat(int i, boolean z);

    private static native long nativeGetUidStat(int i, int i2, boolean z);

    /* loaded from: classes.dex */
    public interface NetworkStatsSettings {
        boolean getAugmentEnabled();

        Config getDevConfig();

        long getDevPersistBytes(long j);

        long getGlobalAlertBytes(long j);

        long getPollInterval();

        boolean getSampleEnabled();

        Config getUidConfig();

        long getUidPersistBytes(long j);

        Config getUidTagConfig();

        long getUidTagPersistBytes(long j);

        Config getXtConfig();

        long getXtPersistBytes(long j);

        /* loaded from: classes.dex */
        public static class Config {
            public final long bucketDuration;
            public final long deleteAgeMillis;
            public final long rotateAgeMillis;

            public Config(long bucketDuration, long rotateAgeMillis, long deleteAgeMillis) {
                this.bucketDuration = bucketDuration;
                this.rotateAgeMillis = rotateAgeMillis;
                this.deleteAgeMillis = deleteAgeMillis;
            }
        }
    }

    private static File getDefaultSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    private static File getDefaultBaseDir() {
        File baseDir = new File(getDefaultSystemDir(), "netstats");
        baseDir.mkdirs();
        return baseDir;
    }

    private static Clock getDefaultClock() {
        return new BestClock(ZoneOffset.UTC, new Clock[]{SystemClock.currentNetworkTimeClock(), Clock.systemUTC()});
    }

    public static NetworkStatsService create(Context context, INetworkManagementService networkManager) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService("alarm");
        PowerManager powerManager = (PowerManager) context.getSystemService("power");
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(1, TAG);
        NetworkStatsService service = new NetworkStatsService(context, networkManager, alarmManager, wakeLock, getDefaultClock(), TelephonyManager.getDefault(), new DefaultNetworkStatsSettings(context), new NetworkStatsObservers(), getDefaultSystemDir(), getDefaultBaseDir());
        HandlerThread handlerThread = new HandlerThread(TAG);
        Handler.Callback callback = new HandlerCallback(service);
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper(), callback);
        service.setHandler(handler, callback);
        return service;
    }

    @VisibleForTesting
    NetworkStatsService(Context context, INetworkManagementService networkManager, AlarmManager alarmManager, PowerManager.WakeLock wakeLock, Clock clock, TelephonyManager teleManager, NetworkStatsSettings settings, NetworkStatsObservers statsObservers, File systemDir, File baseDir) {
        this.mContext = (Context) Preconditions.checkNotNull(context, "missing Context");
        this.mNetworkManager = (INetworkManagementService) Preconditions.checkNotNull(networkManager, "missing INetworkManagementService");
        this.mAlarmManager = (AlarmManager) Preconditions.checkNotNull(alarmManager, "missing AlarmManager");
        this.mClock = (Clock) Preconditions.checkNotNull(clock, "missing Clock");
        this.mSettings = (NetworkStatsSettings) Preconditions.checkNotNull(settings, "missing NetworkStatsSettings");
        this.mTeleManager = (TelephonyManager) Preconditions.checkNotNull(teleManager, "missing TelephonyManager");
        this.mWakeLock = (PowerManager.WakeLock) Preconditions.checkNotNull(wakeLock, "missing WakeLock");
        this.mStatsObservers = (NetworkStatsObservers) Preconditions.checkNotNull(statsObservers, "missing NetworkStatsObservers");
        this.mSystemDir = (File) Preconditions.checkNotNull(systemDir, "missing systemDir");
        this.mBaseDir = (File) Preconditions.checkNotNull(baseDir, "missing baseDir");
        LocalServices.addService(NetworkStatsManagerInternal.class, new NetworkStatsManagerInternalImpl());
    }

    @VisibleForTesting
    void setHandler(Handler handler, Handler.Callback callback) {
        this.mHandler = handler;
        this.mHandlerCallback = callback;
    }

    public void systemReady() {
        this.mSystemReady = true;
        if (!isBandwidthControlEnabled()) {
            Slog.w(TAG, "bandwidth controls disabled, unable to track stats");
            return;
        }
        synchronized (this.mStatsLock) {
            this.mDevRecorder = buildRecorder(PREFIX_DEV, this.mSettings.getDevConfig(), false);
            this.mXtRecorder = buildRecorder(PREFIX_XT, this.mSettings.getXtConfig(), false);
            this.mUidRecorder = buildRecorder("uid", this.mSettings.getUidConfig(), false);
            this.mUidTagRecorder = buildRecorder(PREFIX_UID_TAG, this.mSettings.getUidTagConfig(), true);
            updatePersistThresholdsLocked();
            maybeUpgradeLegacyStatsLocked();
            this.mXtStatsCached = this.mXtRecorder.getOrLoadCompleteLocked();
            bootstrapStatsLocked();
        }
        IntentFilter tetherFilter = new IntentFilter("android.net.conn.TETHER_STATE_CHANGED");
        this.mContext.registerReceiver(this.mTetherReceiver, tetherFilter, null, this.mHandler);
        IntentFilter pollFilter = new IntentFilter(ACTION_NETWORK_STATS_POLL);
        this.mContext.registerReceiver(this.mPollReceiver, pollFilter, "android.permission.READ_NETWORK_USAGE_HISTORY", this.mHandler);
        IntentFilter removedFilter = new IntentFilter("android.intent.action.UID_REMOVED");
        this.mContext.registerReceiver(this.mRemovedReceiver, removedFilter, null, this.mHandler);
        IntentFilter userFilter = new IntentFilter("android.intent.action.USER_REMOVED");
        this.mContext.registerReceiver(this.mUserReceiver, userFilter, null, this.mHandler);
        IntentFilter shutdownFilter = new IntentFilter("android.intent.action.ACTION_SHUTDOWN");
        this.mContext.registerReceiver(this.mShutdownReceiver, shutdownFilter);
        try {
            this.mNetworkManager.registerObserver(this.mAlertObserver);
        } catch (RemoteException e) {
        }
        registerPollAlarmLocked();
        registerGlobalAlert();
        NetworkStatsHandler.getInstance(this.mContext).onBootPhase(0);
    }

    private NetworkStatsRecorder buildRecorder(String prefix, NetworkStatsSettings.Config config, boolean includeTags) {
        DropBoxManager dropBox = (DropBoxManager) this.mContext.getSystemService("dropbox");
        return new NetworkStatsRecorder(new FileRotator(this.mBaseDir, prefix, config.rotateAgeMillis, config.deleteAgeMillis), this.mNonMonotonicObserver, dropBox, prefix, config.bucketDuration, includeTags);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mStatsLock")
    public void shutdownLocked() {
        this.mContext.unregisterReceiver(this.mTetherReceiver);
        this.mContext.unregisterReceiver(this.mPollReceiver);
        this.mContext.unregisterReceiver(this.mRemovedReceiver);
        this.mContext.unregisterReceiver(this.mUserReceiver);
        this.mContext.unregisterReceiver(this.mShutdownReceiver);
        long currentTime = this.mClock.millis();
        this.mDevRecorder.forcePersistLocked(currentTime);
        this.mXtRecorder.forcePersistLocked(currentTime);
        this.mUidRecorder.forcePersistLocked(currentTime);
        this.mUidTagRecorder.forcePersistLocked(currentTime);
        this.mSystemReady = false;
    }

    @GuardedBy("mStatsLock")
    private void maybeUpgradeLegacyStatsLocked() {
        try {
            File file = new File(this.mSystemDir, "netstats.bin");
            if (file.exists()) {
                this.mDevRecorder.importLegacyNetworkLocked(file);
                file.delete();
            }
            File file2 = new File(this.mSystemDir, "netstats_xt.bin");
            if (file2.exists()) {
                file2.delete();
            }
            File file3 = new File(this.mSystemDir, "netstats_uid.bin");
            if (file3.exists()) {
                this.mUidRecorder.importLegacyUidLocked(file3);
                this.mUidTagRecorder.importLegacyUidLocked(file3);
                file3.delete();
            }
        } catch (IOException e) {
            Log.wtf(TAG, "problem during legacy upgrade", e);
        } catch (OutOfMemoryError e2) {
            Log.wtf(TAG, "problem during legacy upgrade", e2);
        }
    }

    private void registerPollAlarmLocked() {
        if (this.mPollIntent != null) {
            this.mAlarmManager.cancel(this.mPollIntent);
        }
        this.mPollIntent = PendingIntent.getBroadcast(this.mContext, 0, new Intent(ACTION_NETWORK_STATS_POLL), 0);
        long currentRealtime = SystemClock.elapsedRealtime();
        this.mAlarmManager.setInexactRepeating(3, currentRealtime, this.mSettings.getPollInterval(), this.mPollIntent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void registerGlobalAlert() {
        try {
            this.mNetworkManager.setGlobalAlert(this.mGlobalAlertBytes);
        } catch (RemoteException e) {
        } catch (IllegalStateException e2) {
            Slog.w(TAG, "problem registering for global alert: " + e2);
        }
    }

    public INetworkStatsSession openSession() {
        return openSessionInternal(4, null);
    }

    public INetworkStatsSession openSessionForUsageStats(int flags, String callingPackage) {
        return openSessionInternal(flags, callingPackage);
    }

    private boolean isRateLimitedForPoll(int callingUid) {
        long lastCallTime;
        if (callingUid == 1000) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (this.mOpenSessionCallsPerUid) {
            int calls = this.mOpenSessionCallsPerUid.get(callingUid, 0);
            this.mOpenSessionCallsPerUid.put(callingUid, calls + 1);
            lastCallTime = this.mLastStatsSessionPoll;
            this.mLastStatsSessionPoll = now;
        }
        return now - lastCallTime < POLL_RATE_LIMIT_MS;
    }

    private INetworkStatsSession openSessionInternal(int flags, final String callingPackage) {
        final int usedFlags;
        assertBandwidthControlEnabled();
        final int callingUid = Binder.getCallingUid();
        if (isRateLimitedForPoll(callingUid)) {
            usedFlags = flags & (-2);
        } else {
            usedFlags = flags;
        }
        if ((usedFlags & 3) != 0) {
            long ident = Binder.clearCallingIdentity();
            try {
                performPoll(3);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return new INetworkStatsSession.Stub() { // from class: com.android.server.net.NetworkStatsService.1
            private final int mAccessLevel;
            private final String mCallingPackage;
            private final int mCallingUid;
            private NetworkStatsCollection mUidComplete;
            private NetworkStatsCollection mUidTagComplete;

            {
                this.mCallingUid = callingUid;
                this.mCallingPackage = callingPackage;
                this.mAccessLevel = NetworkStatsService.this.checkAccessLevel(callingPackage);
            }

            private NetworkStatsCollection getUidComplete() {
                NetworkStatsCollection networkStatsCollection;
                synchronized (NetworkStatsService.this.mStatsLock) {
                    if (this.mUidComplete == null) {
                        this.mUidComplete = NetworkStatsService.this.mUidRecorder.getOrLoadCompleteLocked();
                    }
                    networkStatsCollection = this.mUidComplete;
                }
                return networkStatsCollection;
            }

            private NetworkStatsCollection getUidTagComplete() {
                NetworkStatsCollection networkStatsCollection;
                synchronized (NetworkStatsService.this.mStatsLock) {
                    if (this.mUidTagComplete == null) {
                        this.mUidTagComplete = NetworkStatsService.this.mUidTagRecorder.getOrLoadCompleteLocked();
                    }
                    networkStatsCollection = this.mUidTagComplete;
                }
                return networkStatsCollection;
            }

            public int[] getRelevantUids() {
                return getUidComplete().getRelevantUids(this.mAccessLevel);
            }

            public NetworkStats getDeviceSummaryForNetwork(NetworkTemplate template, long start, long end) {
                return NetworkStatsService.this.internalGetSummaryForNetwork(template, usedFlags, start, end, this.mAccessLevel, this.mCallingUid);
            }

            public NetworkStats getSummaryForNetwork(NetworkTemplate template, long start, long end) {
                return NetworkStatsService.this.internalGetSummaryForNetwork(template, usedFlags, start, end, this.mAccessLevel, this.mCallingUid);
            }

            public NetworkStatsHistory getHistoryForNetwork(NetworkTemplate template, int fields) {
                return NetworkStatsService.this.internalGetHistoryForNetwork(template, usedFlags, fields, this.mAccessLevel, this.mCallingUid);
            }

            public NetworkStats getSummaryForAllUid(NetworkTemplate template, long start, long end, boolean includeTags) {
                try {
                    NetworkStats stats = getUidComplete().getSummary(template, start, end, this.mAccessLevel, this.mCallingUid);
                    if (includeTags) {
                        NetworkStats tagStats = getUidTagComplete().getSummary(template, start, end, this.mAccessLevel, this.mCallingUid);
                        stats.combineAllValues(tagStats);
                    }
                    return stats;
                } catch (NullPointerException e) {
                    Slog.wtf(NetworkStatsService.TAG, "NullPointerException in getSummaryForAllUid", e);
                    throw e;
                }
            }

            public NetworkStatsHistory getHistoryForUid(NetworkTemplate template, int uid, int set, int tag, int fields) {
                if (tag == 0) {
                    return getUidComplete().getHistory(template, null, uid, set, tag, fields, Long.MIN_VALUE, JobStatus.NO_LATEST_RUNTIME, this.mAccessLevel, this.mCallingUid);
                }
                return getUidTagComplete().getHistory(template, null, uid, set, tag, fields, Long.MIN_VALUE, JobStatus.NO_LATEST_RUNTIME, this.mAccessLevel, this.mCallingUid);
            }

            public NetworkStatsHistory getHistoryIntervalForUid(NetworkTemplate template, int uid, int set, int tag, int fields, long start, long end) {
                if (tag == 0) {
                    return getUidComplete().getHistory(template, null, uid, set, tag, fields, start, end, this.mAccessLevel, this.mCallingUid);
                }
                if (uid == Binder.getCallingUid()) {
                    return getUidTagComplete().getHistory(template, null, uid, set, tag, fields, start, end, this.mAccessLevel, this.mCallingUid);
                }
                throw new SecurityException("Calling package " + this.mCallingPackage + " cannot access tag information from a different uid");
            }

            public void close() {
                this.mUidComplete = null;
                this.mUidTagComplete = null;
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkAccessLevel(String callingPackage) {
        return NetworkStatsAccess.checkAccessLevel(this.mContext, Binder.getCallingUid(), callingPackage);
    }

    /* JADX WARN: Finally extract failed */
    private SubscriptionPlan resolveSubscriptionPlan(NetworkTemplate template, int flags) {
        SubscriptionPlan plan = null;
        if ((flags & 4) != 0 && this.mSettings.getAugmentEnabled()) {
            if (LOGD) {
                Slog.d(TAG, "Resolving plan for " + template);
            }
            long token = Binder.clearCallingIdentity();
            try {
                plan = ((NetworkPolicyManagerInternal) LocalServices.getService(NetworkPolicyManagerInternal.class)).getSubscriptionPlan(template);
                Binder.restoreCallingIdentity(token);
                if (LOGD) {
                    Slog.d(TAG, "Resolved to plan " + plan);
                }
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(token);
                throw th;
            }
        }
        return plan;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NetworkStats internalGetSummaryForNetwork(NetworkTemplate template, int flags, long start, long end, int accessLevel, int callingUid) {
        NetworkStatsHistory history = internalGetHistoryForNetwork(template, flags, -1, accessLevel, callingUid);
        long now = System.currentTimeMillis();
        NetworkStatsHistory.Entry entry = history.getValues(start, end, now, (NetworkStatsHistory.Entry) null);
        NetworkStats stats = new NetworkStats(end - start, 1);
        stats.addValues(new NetworkStats.Entry(NetworkStats.IFACE_ALL, -1, -1, 0, -1, -1, -1, entry.rxBytes, entry.rxPackets, entry.txBytes, entry.txPackets, entry.operations));
        return stats;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NetworkStatsHistory internalGetHistoryForNetwork(NetworkTemplate template, int flags, int fields, int accessLevel, int callingUid) {
        SubscriptionPlan augmentPlan = resolveSubscriptionPlan(template, flags);
        synchronized (this.mStatsLock) {
            try {
                try {
                    return this.mXtStatsCached.getHistory(template, augmentPlan, -1, -1, 0, fields, Long.MIN_VALUE, JobStatus.NO_LATEST_RUNTIME, accessLevel, callingUid);
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
        assertSystemReady();
        assertBandwidthControlEnabled();
        return internalGetSummaryForNetwork(template, 4, start, end, 3, Binder.getCallingUid()).getTotalBytes();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NetworkStats getNetworkUidBytes(NetworkTemplate template, long start, long end) {
        NetworkStatsCollection uidComplete;
        assertSystemReady();
        assertBandwidthControlEnabled();
        synchronized (this.mStatsLock) {
            uidComplete = this.mUidRecorder.getOrLoadCompleteLocked();
        }
        return uidComplete.getSummary(template, start, end, 3, 1000);
    }

    public NetworkStats getDataLayerSnapshotForUid(int uid) throws RemoteException {
        if (Binder.getCallingUid() != uid) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_NETWORK_STATE", TAG);
        }
        assertBandwidthControlEnabled();
        long token = Binder.clearCallingIdentity();
        try {
            NetworkStats networkLayer = this.mNetworkManager.getNetworkStatsUidDetail(uid, NetworkStats.INTERFACES_ALL);
            Binder.restoreCallingIdentity(token);
            networkLayer.spliceOperationsFrom(this.mUidOperations);
            NetworkStats dataLayer = new NetworkStats(networkLayer.getElapsedRealtime(), networkLayer.size());
            NetworkStats.Entry entry = null;
            for (int i = 0; i < networkLayer.size(); i++) {
                entry = networkLayer.getValues(i, entry);
                entry.iface = NetworkStats.IFACE_ALL;
                dataLayer.combineValues(entry);
            }
            return dataLayer;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(token);
            throw th;
        }
    }

    public NetworkStats getDetailedUidStats(String[] requiredIfaces) {
        try {
            String[] ifacesToQuery = NetworkStatsFactory.augmentWithStackedInterfaces(requiredIfaces);
            return getNetworkStatsUidDetail(ifacesToQuery);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error compiling UID stats", e);
            return new NetworkStats(0L, 0);
        }
    }

    public String[] getMobileIfaces() {
        return this.mMobileIfaces;
    }

    public void incrementOperationCount(int uid, int tag, int operationCount) {
        Object obj;
        int set;
        if (Binder.getCallingUid() != uid) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_DEVICE_STATS", TAG);
        }
        if (operationCount < 0) {
            throw new IllegalArgumentException("operation count can only be incremented");
        }
        if (tag == 0) {
            throw new IllegalArgumentException("operation count must have specific tag");
        }
        Object obj2 = this.mStatsLock;
        synchronized (obj2) {
            try {
                try {
                    set = this.mActiveUidCounterSet.get(uid, 0);
                    obj = obj2;
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    this.mUidOperations.combineValues(this.mActiveIface, uid, set, tag, 0L, 0L, 0L, 0L, operationCount);
                    this.mUidOperations.combineValues(this.mActiveIface, uid, set, 0, 0L, 0L, 0L, 0L, operationCount);
                } catch (Throwable th2) {
                    th = th2;
                    throw th;
                }
            } catch (Throwable th3) {
                th = th3;
                obj = obj2;
            }
        }
    }

    @VisibleForTesting
    void setUidForeground(int uid, boolean uidForeground) {
        synchronized (this.mStatsLock) {
            int oldSet = this.mActiveUidCounterSet.get(uid, 0);
            if (oldSet != uidForeground) {
                this.mActiveUidCounterSet.put(uid, uidForeground ? 1 : 0);
                NetworkManagementSocketTagger.setKernelCounterSet(uid, uidForeground ? 1 : 0);
            }
        }
    }

    public void forceUpdateIfaces(Network[] defaultNetworks, VpnInfo[] vpnArray, NetworkState[] networkStates, String activeIface) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_NETWORK_USAGE_HISTORY", TAG);
        assertBandwidthControlEnabled();
        long token = Binder.clearCallingIdentity();
        try {
            updateIfaces(defaultNetworks, vpnArray, networkStates, activeIface);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void forceUpdate() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.READ_NETWORK_USAGE_HISTORY", TAG);
        assertBandwidthControlEnabled();
        long token = Binder.clearCallingIdentity();
        try {
            performPoll(3);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void advisePersistThreshold(long thresholdBytes) {
        assertBandwidthControlEnabled();
        this.mPersistThreshold = MathUtils.constrain(thresholdBytes, 131072L, 2097152L);
        if (LOGV) {
            Slog.v(TAG, "advisePersistThreshold() given " + thresholdBytes + ", clamped to " + this.mPersistThreshold);
        }
        long currentTime = this.mClock.millis();
        synchronized (this.mStatsLock) {
            if (this.mSystemReady) {
                updatePersistThresholdsLocked();
                this.mDevRecorder.maybePersistLocked(currentTime);
                this.mXtRecorder.maybePersistLocked(currentTime);
                this.mUidRecorder.maybePersistLocked(currentTime);
                this.mUidTagRecorder.maybePersistLocked(currentTime);
                registerGlobalAlert();
            }
        }
    }

    public DataUsageRequest registerUsageCallback(String callingPackage, DataUsageRequest request, Messenger messenger, IBinder binder) {
        Preconditions.checkNotNull(callingPackage, "calling package is null");
        Preconditions.checkNotNull(request, "DataUsageRequest is null");
        Preconditions.checkNotNull(request.template, "NetworkTemplate is null");
        Preconditions.checkNotNull(messenger, "messenger is null");
        Preconditions.checkNotNull(binder, "binder is null");
        int callingUid = Binder.getCallingUid();
        int accessLevel = checkAccessLevel(callingPackage);
        long token = Binder.clearCallingIdentity();
        try {
            DataUsageRequest normalizedRequest = this.mStatsObservers.register(request, messenger, binder, callingUid, accessLevel);
            Binder.restoreCallingIdentity(token);
            this.mHandler.sendMessage(this.mHandler.obtainMessage(1));
            return normalizedRequest;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(token);
            throw th;
        }
    }

    public void unregisterUsageRequest(DataUsageRequest request) {
        Preconditions.checkNotNull(request, "DataUsageRequest is null");
        int callingUid = Binder.getCallingUid();
        long token = Binder.clearCallingIdentity();
        try {
            this.mStatsObservers.unregister(request, callingUid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public long getUidStats(int uid, int type) {
        return nativeGetUidStat(uid, type, checkBpfStatsEnable());
    }

    public long getIfaceStats(String iface, int type) {
        if (type == TYPE_TCP_TX_PACKETS || type == TYPE_TCP_RX_PACKETS) {
            return nativeGetIfaceStat(iface, type, false);
        }
        return nativeGetIfaceStat(iface, type, checkBpfStatsEnable());
    }

    public long getTotalStats(int type) {
        return nativeGetTotalStat(type, checkBpfStatsEnable());
    }

    public int[] getTrafficStatsInfo(String packageName) {
        if (!TextUtils.isEmpty(packageName)) {
            int uid = TrafficStatsEntry.getUid(packageName);
            int tag = TrafficStatsEntry.getTag(packageName);
            int[] info = {uid, tag};
            return info;
        }
        return null;
    }

    private boolean checkBpfStatsEnable() {
        return this.mUseBpfTrafficStats;
    }

    @GuardedBy("mStatsLock")
    private void updatePersistThresholdsLocked() {
        this.mDevRecorder.setPersistThreshold(this.mSettings.getDevPersistBytes(this.mPersistThreshold));
        this.mXtRecorder.setPersistThreshold(this.mSettings.getXtPersistBytes(this.mPersistThreshold));
        this.mUidRecorder.setPersistThreshold(this.mSettings.getUidPersistBytes(this.mPersistThreshold));
        this.mUidTagRecorder.setPersistThreshold(this.mSettings.getUidTagPersistBytes(this.mPersistThreshold));
        this.mGlobalAlertBytes = this.mSettings.getGlobalAlertBytes(this.mPersistThreshold);
    }

    private void updateIfaces(Network[] defaultNetworks, VpnInfo[] vpnArray, NetworkState[] networkStates, String activeIface) {
        synchronized (this.mStatsLock) {
            this.mWakeLock.acquire();
            this.mVpnInfos = vpnArray;
            this.mActiveIface = activeIface;
            updateIfacesLocked(defaultNetworks, networkStates);
            this.mWakeLock.release();
        }
    }

    @GuardedBy("mStatsLock")
    private void updateIfacesLocked(Network[] defaultNetworks, NetworkState[] states) {
        if (this.mSystemReady) {
            if (LOGV) {
                Slog.v(TAG, "updateIfacesLocked()");
            }
            boolean z = true;
            performPollLocked(1);
            this.mActiveIfaces.clear();
            this.mActiveUidIfaces.clear();
            if (defaultNetworks != null) {
                this.mDefaultNetworks = defaultNetworks;
            }
            ArraySet<String> mobileIfaces = new ArraySet<>();
            int length = states.length;
            int i = 0;
            while (i < length) {
                NetworkState state = states[i];
                boolean hasInternetCapability = state.networkCapabilities != null ? state.networkCapabilities.hasCapability(12) : z;
                if (state.networkInfo.isConnected() && hasInternetCapability) {
                    boolean isMobile = ConnectivityManager.isNetworkTypeMobile(state.networkInfo.getType());
                    boolean isDefault = ArrayUtils.contains(this.mDefaultNetworks, state.network);
                    NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(this.mContext, state, isDefault);
                    String baseIface = state.linkProperties.getInterfaceName();
                    if (baseIface != null) {
                        findOrCreateNetworkIdentitySet(this.mActiveIfaces, baseIface).add(ident);
                        findOrCreateNetworkIdentitySet(this.mActiveUidIfaces, baseIface).add(ident);
                        if (state.networkCapabilities.hasCapability(4) && !ident.getMetered()) {
                            NetworkIdentity vtIdent = new NetworkIdentity(ident.getType(), ident.getSubType(), ident.getSubscriberId(), ident.getNetworkId(), ident.getRoaming(), true, true);
                            findOrCreateNetworkIdentitySet(this.mActiveIfaces, VT_INTERFACE).add(vtIdent);
                            findOrCreateNetworkIdentitySet(this.mActiveUidIfaces, VT_INTERFACE).add(vtIdent);
                        }
                        if (isMobile) {
                            mobileIfaces.add(baseIface);
                        }
                    }
                    List<LinkProperties> stackedLinks = state.linkProperties.getStackedLinks();
                    for (LinkProperties stackedLink : stackedLinks) {
                        String stackedIface = stackedLink.getInterfaceName();
                        if (stackedIface != null) {
                            findOrCreateNetworkIdentitySet(this.mActiveUidIfaces, stackedIface).add(ident);
                            if (isMobile) {
                                mobileIfaces.add(stackedIface);
                            }
                            NetworkStatsFactory.noteStackedIface(stackedIface, baseIface);
                        }
                    }
                }
                i++;
                z = true;
            }
            this.mMobileIfaces = (String[]) mobileIfaces.toArray(new String[mobileIfaces.size()]);
        }
    }

    private static <K> NetworkIdentitySet findOrCreateNetworkIdentitySet(ArrayMap<K, NetworkIdentitySet> map, K key) {
        NetworkIdentitySet ident = map.get(key);
        if (ident == null) {
            NetworkIdentitySet ident2 = new NetworkIdentitySet();
            map.put(key, ident2);
            return ident2;
        }
        return ident;
    }

    @GuardedBy("mStatsLock")
    private void recordSnapshotLocked(long currentTime) throws RemoteException {
        Trace.traceBegin(2097152L, "snapshotUid");
        NetworkStats uidSnapshot = getNetworkStatsUidDetail(NetworkStats.INTERFACES_ALL);
        Trace.traceEnd(2097152L);
        Trace.traceBegin(2097152L, "snapshotXt");
        NetworkStats xtSnapshot = getNetworkStatsXt();
        Trace.traceEnd(2097152L);
        Trace.traceBegin(2097152L, "snapshotDev");
        NetworkStats devSnapshot = this.mNetworkManager.getNetworkStatsSummaryDev();
        Trace.traceEnd(2097152L);
        Trace.traceBegin(2097152L, "snapshotTether");
        NetworkStats tetherSnapshot = getNetworkStatsTethering(0);
        Trace.traceEnd(2097152L);
        xtSnapshot.combineAllValues(tetherSnapshot);
        devSnapshot.combineAllValues(tetherSnapshot);
        Trace.traceBegin(2097152L, "recordDev");
        this.mDevRecorder.recordSnapshotLocked(devSnapshot, this.mActiveIfaces, null, currentTime);
        Trace.traceEnd(2097152L);
        Trace.traceBegin(2097152L, "recordXt");
        this.mXtRecorder.recordSnapshotLocked(xtSnapshot, this.mActiveIfaces, null, currentTime);
        Trace.traceEnd(2097152L);
        VpnInfo[] vpnArray = this.mVpnInfos;
        Trace.traceBegin(2097152L, "recordUid");
        this.mUidRecorder.recordSnapshotLocked(uidSnapshot, this.mActiveUidIfaces, vpnArray, currentTime);
        Trace.traceEnd(2097152L);
        Trace.traceBegin(2097152L, "recordUidTag");
        this.mUidTagRecorder.recordSnapshotLocked(uidSnapshot, this.mActiveUidIfaces, vpnArray, currentTime);
        Trace.traceEnd(2097152L);
        this.mStatsObservers.updateStats(xtSnapshot, uidSnapshot, new ArrayMap<>(this.mActiveIfaces), new ArrayMap<>(this.mActiveUidIfaces), vpnArray, currentTime);
    }

    @GuardedBy("mStatsLock")
    private void bootstrapStatsLocked() {
        long currentTime = this.mClock.millis();
        try {
            recordSnapshotLocked(currentTime);
        } catch (RemoteException e) {
        } catch (IllegalStateException e2) {
            Slog.w(TAG, "problem reading network stats: " + e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void performPoll(int flags) {
        synchronized (this.mStatsLock) {
            this.mWakeLock.acquire();
            performPollLocked(flags);
            this.mWakeLock.release();
        }
    }

    @GuardedBy("mStatsLock")
    private void performPollLocked(int flags) {
        if (this.mSystemReady) {
            if (LOGV) {
                Slog.v(TAG, "performPollLocked(flags=0x" + Integer.toHexString(flags) + ")");
            }
            Trace.traceBegin(2097152L, "performPollLocked");
            boolean persistNetwork = (flags & 1) != 0;
            boolean persistUid = (flags & 2) != 0;
            boolean persistForce = (flags & 256) != 0;
            long currentTime = this.mClock.millis();
            try {
                recordSnapshotLocked(currentTime);
                Trace.traceBegin(2097152L, "[persisting]");
                if (persistForce) {
                    this.mDevRecorder.forcePersistLocked(currentTime);
                    this.mXtRecorder.forcePersistLocked(currentTime);
                    this.mUidRecorder.forcePersistLocked(currentTime);
                    this.mUidTagRecorder.forcePersistLocked(currentTime);
                } else {
                    if (persistNetwork) {
                        this.mDevRecorder.maybePersistLocked(currentTime);
                        this.mXtRecorder.maybePersistLocked(currentTime);
                    }
                    if (persistUid) {
                        this.mUidRecorder.maybePersistLocked(currentTime);
                        this.mUidTagRecorder.maybePersistLocked(currentTime);
                    }
                }
                Trace.traceEnd(2097152L);
                if (this.mSettings.getSampleEnabled()) {
                    performSampleLocked();
                }
                Intent updatedIntent = new Intent(ACTION_NETWORK_STATS_UPDATED);
                updatedIntent.setFlags(1073741824);
                this.mContext.sendBroadcastAsUser(updatedIntent, UserHandle.ALL, "android.permission.READ_NETWORK_USAGE_HISTORY");
                Trace.traceEnd(2097152L);
            } catch (RemoteException e) {
            } catch (IllegalStateException e2) {
                Log.wtf(TAG, "problem reading network stats", e2);
            }
        }
    }

    @GuardedBy("mStatsLock")
    private void performSampleLocked() {
        this.mClock.millis();
        NetworkTemplate template = NetworkTemplate.buildTemplateMobileWildcard();
        this.mDevRecorder.getTotalSinceBootLocked(template);
        this.mXtRecorder.getTotalSinceBootLocked(template);
        this.mUidRecorder.getTotalSinceBootLocked(template);
        NetworkTemplate template2 = NetworkTemplate.buildTemplateWifiWildcard();
        this.mDevRecorder.getTotalSinceBootLocked(template2);
        this.mXtRecorder.getTotalSinceBootLocked(template2);
        this.mUidRecorder.getTotalSinceBootLocked(template2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mStatsLock")
    public void removeUidsLocked(int... uids) {
        if (LOGV) {
            Slog.v(TAG, "removeUidsLocked() for UIDs " + Arrays.toString(uids));
        }
        performPollLocked(3);
        this.mUidRecorder.removeUidsLocked(uids);
        this.mUidTagRecorder.removeUidsLocked(uids);
        for (int uid : uids) {
            NetworkManagementSocketTagger.resetKernelUidStats(uid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mStatsLock")
    public void removeUserLocked(int userId) {
        if (LOGV) {
            Slog.v(TAG, "removeUserLocked() for userId=" + userId);
        }
        int[] uids = new int[0];
        List<ApplicationInfo> apps = this.mContext.getPackageManager().getInstalledApplications(4194816);
        for (ApplicationInfo app : apps) {
            int uid = UserHandle.getUid(userId, app.uid);
            uids = ArrayUtils.appendInt(uids, uid);
        }
        removeUidsLocked(uids);
    }

    /* loaded from: classes.dex */
    private class NetworkStatsManagerInternalImpl extends NetworkStatsManagerInternal {
        private NetworkStatsManagerInternalImpl() {
        }

        @Override // com.android.server.net.NetworkStatsManagerInternal
        public long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
            Trace.traceBegin(2097152L, "getNetworkTotalBytes");
            try {
                return NetworkStatsService.this.getNetworkTotalBytes(template, start, end);
            } finally {
                Trace.traceEnd(2097152L);
            }
        }

        @Override // com.android.server.net.NetworkStatsManagerInternal
        public NetworkStats getNetworkUidBytes(NetworkTemplate template, long start, long end) {
            Trace.traceBegin(2097152L, "getNetworkUidBytes");
            try {
                return NetworkStatsService.this.getNetworkUidBytes(template, start, end);
            } finally {
                Trace.traceEnd(2097152L);
            }
        }

        @Override // com.android.server.net.NetworkStatsManagerInternal
        public void setUidForeground(int uid, boolean uidForeground) {
            NetworkStatsService.this.setUidForeground(uid, uidForeground);
        }

        @Override // com.android.server.net.NetworkStatsManagerInternal
        public void advisePersistThreshold(long thresholdBytes) {
            NetworkStatsService.this.advisePersistThreshold(thresholdBytes);
        }

        @Override // com.android.server.net.NetworkStatsManagerInternal
        public void forceUpdate() {
            NetworkStatsService.this.forceUpdate();
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter rawWriter, String[] args) {
        Object obj;
        SparseIntArray calls;
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, rawWriter)) {
            HashSet<String> argSet = new HashSet<>();
            long duration = 86400000;
            for (String arg : args) {
                argSet.add(arg);
                if (arg.startsWith("--duration=")) {
                    try {
                        long duration2 = Long.parseLong(arg.substring(11));
                        duration = duration2;
                    } catch (NumberFormatException e) {
                    }
                }
            }
            boolean z = true;
            boolean poll = argSet.contains("--poll") || argSet.contains("poll");
            boolean checkin = argSet.contains("--checkin");
            boolean fullHistory = argSet.contains("--full") || argSet.contains("full");
            boolean includeUid = argSet.contains("--uid") || argSet.contains("detail");
            if (!argSet.contains("--tag") && !argSet.contains("detail")) {
                z = false;
            }
            boolean includeTag = z;
            IndentingPrintWriter pw = new IndentingPrintWriter(rawWriter, "  ");
            Object obj2 = this.mStatsLock;
            synchronized (obj2) {
                try {
                    if (args.length > 0) {
                        try {
                            if (PriorityDump.PROTO_ARG.equals(args[0])) {
                                dumpProtoLocked(fd);
                                return;
                            }
                        } catch (Throwable th) {
                            th = th;
                            obj = obj2;
                            throw th;
                        }
                    }
                } catch (Throwable th2) {
                    th = th2;
                    obj = obj2;
                }
                if (poll) {
                    performPollLocked(259);
                    pw.println("Forced poll");
                    return;
                }
                try {
                    if (checkin) {
                        try {
                            long end = System.currentTimeMillis();
                            long start = end - duration;
                            pw.print("v1,");
                            pw.print(start / 1000);
                            pw.print(',');
                            pw.print(end / 1000);
                            pw.println();
                            pw.println(PREFIX_XT);
                            this.mXtRecorder.dumpCheckin(rawWriter, start, end);
                            if (includeUid) {
                                pw.println("uid");
                                this.mUidRecorder.dumpCheckin(rawWriter, start, end);
                            }
                            if (includeTag) {
                                pw.println("tag");
                                this.mUidTagRecorder.dumpCheckin(rawWriter, start, end);
                            }
                            return;
                        } catch (Throwable th3) {
                            th = th3;
                            obj = obj2;
                        }
                    } else {
                        obj = obj2;
                        try {
                            pw.println("Active interfaces:");
                            pw.increaseIndent();
                            for (int i = 0; i < this.mActiveIfaces.size(); i++) {
                                pw.printPair("iface", this.mActiveIfaces.keyAt(i));
                                pw.printPair("ident", this.mActiveIfaces.valueAt(i));
                                pw.println();
                            }
                            pw.decreaseIndent();
                            pw.println("Active UID interfaces:");
                            pw.increaseIndent();
                            for (int i2 = 0; i2 < this.mActiveUidIfaces.size(); i2++) {
                                pw.printPair("iface", this.mActiveUidIfaces.keyAt(i2));
                                pw.printPair("ident", this.mActiveUidIfaces.valueAt(i2));
                                pw.println();
                            }
                            pw.decreaseIndent();
                            synchronized (this.mOpenSessionCallsPerUid) {
                                try {
                                    calls = this.mOpenSessionCallsPerUid.clone();
                                } catch (Throwable th4) {
                                    th = th4;
                                }
                            }
                            int N = calls.size();
                            long[] values = new long[N];
                            int j = 0;
                            while (j < N) {
                                HashSet<String> argSet2 = argSet;
                                long duration3 = duration;
                                values[j] = (calls.valueAt(j) << 32) | calls.keyAt(j);
                                j++;
                                argSet = argSet2;
                                duration = duration3;
                            }
                            Arrays.sort(values);
                            pw.println("Top openSession callers (uid=count):");
                            pw.increaseIndent();
                            int end2 = Math.max(0, N - 20);
                            for (int j2 = N - 1; j2 >= end2; j2--) {
                                int uid = (int) (values[j2] & (-1));
                                int count = (int) (values[j2] >> 32);
                                pw.print(uid);
                                pw.print("=");
                                pw.println(count);
                            }
                            pw.decreaseIndent();
                            pw.println();
                            pw.println("Dev stats:");
                            pw.increaseIndent();
                            this.mDevRecorder.dumpLocked(pw, fullHistory);
                            pw.decreaseIndent();
                            pw.println("Xt stats:");
                            pw.increaseIndent();
                            this.mXtRecorder.dumpLocked(pw, fullHistory);
                            pw.decreaseIndent();
                            if (includeUid) {
                                pw.println("UID stats:");
                                pw.increaseIndent();
                                this.mUidRecorder.dumpLocked(pw, fullHistory);
                                pw.decreaseIndent();
                            }
                            if (includeTag) {
                                pw.println("UID tag stats:");
                                pw.increaseIndent();
                                this.mUidTagRecorder.dumpLocked(pw, fullHistory);
                                pw.decreaseIndent();
                            }
                            return;
                        } catch (Throwable th5) {
                            th = th5;
                        }
                    }
                } catch (Throwable th6) {
                    th = th6;
                    throw th;
                }
                throw th;
            }
        }
    }

    @GuardedBy("mStatsLock")
    private void dumpProtoLocked(FileDescriptor fd) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        dumpInterfaces(proto, 2246267895809L, this.mActiveIfaces);
        dumpInterfaces(proto, 2246267895810L, this.mActiveUidIfaces);
        this.mDevRecorder.writeToProtoLocked(proto, 1146756268035L);
        this.mXtRecorder.writeToProtoLocked(proto, 1146756268036L);
        this.mUidRecorder.writeToProtoLocked(proto, 1146756268037L);
        this.mUidTagRecorder.writeToProtoLocked(proto, 1146756268038L);
        proto.flush();
    }

    private static void dumpInterfaces(ProtoOutputStream proto, long tag, ArrayMap<String, NetworkIdentitySet> ifaces) {
        for (int i = 0; i < ifaces.size(); i++) {
            long start = proto.start(tag);
            proto.write(1138166333441L, ifaces.keyAt(i));
            ifaces.valueAt(i).writeToProto(proto, 1146756268034L);
            proto.end(start);
        }
    }

    private NetworkStats getNetworkStatsUidDetail(String[] ifaces) throws RemoteException {
        NetworkStats uidSnapshot = this.mNetworkManager.getNetworkStatsUidDetail(-1, ifaces);
        NetworkStats tetherSnapshot = getNetworkStatsTethering(1);
        tetherSnapshot.filter(-1, ifaces, -1);
        NetworkStatsFactory.apply464xlatAdjustments(uidSnapshot, tetherSnapshot, this.mUseBpfTrafficStats);
        uidSnapshot.combineAllValues(tetherSnapshot);
        TelephonyManager telephonyManager = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
        NetworkStats vtStats = telephonyManager.getVtDataUsage(1);
        if (vtStats != null) {
            vtStats.filter(-1, ifaces, -1);
            NetworkStatsFactory.apply464xlatAdjustments(uidSnapshot, vtStats, this.mUseBpfTrafficStats);
            uidSnapshot.combineAllValues(vtStats);
        }
        uidSnapshot.combineAllValues(this.mUidOperations);
        return uidSnapshot;
    }

    private NetworkStats getNetworkStatsXt() throws RemoteException {
        NetworkStats xtSnapshot = this.mNetworkManager.getNetworkStatsSummaryXt();
        TelephonyManager telephonyManager = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
        NetworkStats vtSnapshot = telephonyManager.getVtDataUsage(0);
        if (vtSnapshot != null) {
            xtSnapshot.combineAllValues(vtSnapshot);
        }
        return xtSnapshot;
    }

    private NetworkStats getNetworkStatsTethering(int how) throws RemoteException {
        try {
            return this.mNetworkManager.getNetworkStatsTethering(how);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem reading network stats", e);
            return new NetworkStats(0L, 10);
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    static class HandlerCallback implements Handler.Callback {
        private final NetworkStatsService mService;

        HandlerCallback(NetworkStatsService service) {
            this.mService = service;
        }

        @Override // android.os.Handler.Callback
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    this.mService.performPoll(3);
                    return true;
                case 2:
                    this.mService.performPoll(1);
                    this.mService.registerGlobalAlert();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void assertSystemReady() {
        if (!this.mSystemReady) {
            throw new IllegalStateException("System not ready");
        }
    }

    private void assertBandwidthControlEnabled() {
        if (!isBandwidthControlEnabled()) {
            throw new IllegalStateException("Bandwidth module disabled");
        }
    }

    private boolean isBandwidthControlEnabled() {
        long token = Binder.clearCallingIdentity();
        try {
            return this.mNetworkManager.isBandwidthControlEnabled();
        } catch (RemoteException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class DropBoxNonMonotonicObserver implements NetworkStats.NonMonotonicObserver<String> {
        private DropBoxNonMonotonicObserver() {
        }

        public void foundNonMonotonic(NetworkStats left, int leftIndex, NetworkStats right, int rightIndex, String cookie) {
            Log.w(NetworkStatsService.TAG, "Found non-monotonic values; saving to dropbox");
            StringBuilder builder = new StringBuilder();
            builder.append("found non-monotonic " + cookie + " values at left[" + leftIndex + "] - right[" + rightIndex + "]\n");
            builder.append("left=");
            builder.append(left);
            builder.append('\n');
            builder.append("right=");
            builder.append(right);
            builder.append('\n');
            ((DropBoxManager) NetworkStatsService.this.mContext.getSystemService(DropBoxManager.class)).addText(NetworkStatsService.TAG_NETSTATS_ERROR, builder.toString());
        }

        public void foundNonMonotonic(NetworkStats stats, int statsIndex, String cookie) {
            Log.w(NetworkStatsService.TAG, "Found non-monotonic values; saving to dropbox");
            StringBuilder builder = new StringBuilder();
            builder.append("Found non-monotonic " + cookie + " values at [" + statsIndex + "]\n");
            builder.append("stats=");
            builder.append(stats);
            builder.append('\n');
            ((DropBoxManager) NetworkStatsService.this.mContext.getSystemService(DropBoxManager.class)).addText(NetworkStatsService.TAG_NETSTATS_ERROR, builder.toString());
        }
    }

    /* loaded from: classes.dex */
    private static class DefaultNetworkStatsSettings implements NetworkStatsSettings {
        private final ContentResolver mResolver;

        public DefaultNetworkStatsSettings(Context context) {
            this.mResolver = (ContentResolver) Preconditions.checkNotNull(context.getContentResolver());
        }

        private long getGlobalLong(String name, long def) {
            return Settings.Global.getLong(this.mResolver, name, def);
        }

        private boolean getGlobalBoolean(String name, boolean def) {
            return Settings.Global.getInt(this.mResolver, name, def ? 1 : 0) != 0;
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public long getPollInterval() {
            return getGlobalLong("netstats_poll_interval", 600000L);
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public long getGlobalAlertBytes(long def) {
            return getGlobalLong("netstats_global_alert_bytes", def);
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public boolean getSampleEnabled() {
            return getGlobalBoolean("netstats_sample_enabled", true);
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public boolean getAugmentEnabled() {
            return getGlobalBoolean("netstats_augment_enabled", true);
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public NetworkStatsSettings.Config getDevConfig() {
            return new NetworkStatsSettings.Config(getGlobalLong("netstats_dev_bucket_duration", 3600000L), getGlobalLong("netstats_dev_rotate_age", 1296000000L), getGlobalLong("netstats_dev_delete_age", 7776000000L));
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public NetworkStatsSettings.Config getXtConfig() {
            return getDevConfig();
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public NetworkStatsSettings.Config getUidConfig() {
            return new NetworkStatsSettings.Config(getGlobalLong("netstats_uid_bucket_duration", AppStandbyController.SettingsObserver.DEFAULT_SYSTEM_UPDATE_TIMEOUT), getGlobalLong("netstats_uid_rotate_age", 1296000000L), getGlobalLong("netstats_uid_delete_age", 7776000000L));
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public NetworkStatsSettings.Config getUidTagConfig() {
            return new NetworkStatsSettings.Config(getGlobalLong("netstats_uid_tag_bucket_duration", AppStandbyController.SettingsObserver.DEFAULT_SYSTEM_UPDATE_TIMEOUT), getGlobalLong("netstats_uid_tag_rotate_age", 432000000L), getGlobalLong("netstats_uid_tag_delete_age", 1296000000L));
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public long getDevPersistBytes(long def) {
            return getGlobalLong("netstats_dev_persist_bytes", def);
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public long getXtPersistBytes(long def) {
            return getDevPersistBytes(def);
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public long getUidPersistBytes(long def) {
            return getGlobalLong("netstats_uid_persist_bytes", def);
        }

        @Override // com.android.server.net.NetworkStatsService.NetworkStatsSettings
        public long getUidTagPersistBytes(long def) {
            return getGlobalLong("netstats_uid_tag_persist_bytes", def);
        }
    }
}
