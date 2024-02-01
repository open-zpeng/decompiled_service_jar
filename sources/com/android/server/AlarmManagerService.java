package com.android.server;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IAlarmCompleteListener;
import android.app.IAlarmListener;
import android.app.IAlarmManager;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelableException;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.NtpTrustedTime;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.LocalLog;
import com.android.internal.util.StatLogger;
import com.android.server.AlarmManagerService;
import com.android.server.AppStateTracker;
import com.android.server.DeviceIdleController;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.PackageManagerService;
import com.android.server.usage.AppStandbyController;
import com.android.server.utils.PriorityDump;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.Predicate;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AlarmManagerService extends SystemService {
    static final int ACTIVE_INDEX = 0;
    static final int ALARM_EVENT = 1;
    static final boolean DEBUG_ALARM_CLOCK = false;
    static final boolean DEBUG_BATCH = false;
    static final boolean DEBUG_BG_LIMIT = false;
    static final boolean DEBUG_LISTENER_CALLBACK = false;
    static final boolean DEBUG_STANDBY = false;
    static final boolean DEBUG_VALIDATE = false;
    static final boolean DEBUG_WAKELOCK = false;
    private static final int ELAPSED_REALTIME_MASK = 8;
    private static final int ELAPSED_REALTIME_WAKEUP_MASK = 4;
    static final int FREQUENT_INDEX = 2;
    static final int IS_WAKEUP_MASK = 5;
    static final long MIN_FUZZABLE_INTERVAL = 10000;
    static final int NEVER_INDEX = 4;
    static final int PRIO_NORMAL = 2;
    static final int PRIO_TICK = 0;
    static final int PRIO_WAKEUP = 1;
    static final int RARE_INDEX = 3;
    static final boolean RECORD_ALARMS_IN_HISTORY = true;
    static final boolean RECORD_DEVICE_IDLE_ALARMS = false;
    private static final int RTC_MASK = 2;
    private static final int RTC_WAKEUP_MASK = 1;
    private static final String SYSTEM_UI_SELF_PERMISSION = "android.permission.systemui.IDENTITY";
    static final String TAG = "AlarmManager";
    static final String TIMEZONE_PROPERTY = "persist.sys.timezone";
    static final int TIME_CHANGED_MASK = 65536;
    static final int TYPE_NONWAKEUP_MASK = 1;
    static final boolean WAKEUP_STATS = false;
    static final int WORKING_INDEX = 1;
    static final boolean localLOGV = false;
    final long RECENT_WAKEUP_PERIOD;
    final ArrayList<Batch> mAlarmBatches;
    final Comparator<Alarm> mAlarmDispatchComparator;
    final ArrayList<IdleDispatchEntry> mAllowWhileIdleDispatches;
    AppOpsManager mAppOps;
    private boolean mAppStandbyParole;
    private AppStateTracker mAppStateTracker;
    private final Intent mBackgroundIntent;
    int mBroadcastRefCount;
    final SparseArray<ArrayMap<String, BroadcastStats>> mBroadcastStats;
    ClockReceiver mClockReceiver;
    final Constants mConstants;
    int mCurrentSeq;
    PendingIntent mDateChangeSender;
    final DeliveryTracker mDeliveryTracker;
    private final AppStateTracker.Listener mForceAppStandbyListener;
    final AlarmHandler mHandler;
    private final SparseArray<AlarmManager.AlarmClockInfo> mHandlerSparseAlarmClockArray;
    Bundle mIdleOptions;
    ArrayList<InFlight> mInFlight;
    boolean mInteractive;
    InteractiveStateReceiver mInteractiveStateReceiver;
    private ArrayMap<Pair<String, Integer>, Long> mLastAlarmDeliveredForPackage;
    long mLastAlarmDeliveryTime;
    final SparseLongArray mLastAllowWhileIdleDispatch;
    private long mLastTickAdded;
    private long mLastTickIssued;
    private long mLastTickReceived;
    private long mLastTickRemoved;
    private long mLastTickSet;
    long mLastTimeChangeClockTime;
    long mLastTimeChangeRealtime;
    private long mLastTrigger;
    boolean mLastWakeLockUnimportantForLogging;
    private long mLastWakeup;
    private long mLastWakeupSet;
    @GuardedBy("mLock")
    private int mListenerCount;
    @GuardedBy("mLock")
    private int mListenerFinishCount;
    DeviceIdleController.LocalService mLocalDeviceIdleController;
    final Object mLock;
    final LocalLog mLog;
    long mMaxDelayTime;
    long mNativeData;
    private final SparseArray<AlarmManager.AlarmClockInfo> mNextAlarmClockForUser;
    private boolean mNextAlarmClockMayChange;
    private long mNextNonWakeup;
    long mNextNonWakeupDeliveryTime;
    Alarm mNextWakeFromIdle;
    private long mNextWakeup;
    long mNonInteractiveStartTime;
    long mNonInteractiveTime;
    int mNumDelayedAlarms;
    int mNumTimeChanged;
    SparseArray<ArrayList<Alarm>> mPendingBackgroundAlarms;
    Alarm mPendingIdleUntil;
    ArrayList<Alarm> mPendingNonWakeupAlarms;
    private final SparseBooleanArray mPendingSendNextAlarmClockChangedForUser;
    ArrayList<Alarm> mPendingWhileIdleAlarms;
    final HashMap<String, PriorityClass> mPriorities;
    Random mRandom;
    final LinkedList<WakeupEvent> mRecentWakeups;
    @GuardedBy("mLock")
    private int mSendCount;
    @GuardedBy("mLock")
    private int mSendFinishCount;
    private final IBinder mService;
    long mStartCurrentDelayTime;
    private final StatLogger mStatLogger;
    int mSystemUiUid;
    PendingIntent mTimeTickSender;
    private final SparseArray<AlarmManager.AlarmClockInfo> mTmpSparseAlarmClockArray;
    long mTotalDelayTime;
    private UninstallReceiver mUninstallReceiver;
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    final SparseBooleanArray mUseAllowWhileIdleShortTime;
    PowerManager.WakeLock mWakeLock;
    static final IncreasingTimeOrder sIncreasingTimeOrder = new IncreasingTimeOrder();
    private static final Intent NEXT_ALARM_CLOCK_CHANGED_INTENT = new Intent("android.app.action.NEXT_ALARM_CLOCK_CHANGED").addFlags(553648128);
    static final BatchTimeOrder sBatchOrder = new BatchTimeOrder();

    /* loaded from: classes.dex */
    interface Stats {
        public static final int REBATCH_ALL_ALARMS = 0;
        public static final int REORDER_ALARMS_FOR_STANDBY = 1;
    }

    private native void close(long j);

    private native long init();

    private native int set(long j, int i, long j2, long j3);

    private native int setKernelTime(long j, long j2);

    /* JADX INFO: Access modifiers changed from: private */
    public native int setKernelTimezone(long j, int i);

    /* JADX INFO: Access modifiers changed from: private */
    public native int waitForAlarm(long j);

    static /* synthetic */ int access$2008(AlarmManagerService x0) {
        int i = x0.mListenerFinishCount;
        x0.mListenerFinishCount = i + 1;
        return i;
    }

    static /* synthetic */ int access$2108(AlarmManagerService x0) {
        int i = x0.mSendFinishCount;
        x0.mSendFinishCount = i + 1;
        return i;
    }

    static /* synthetic */ int access$2208(AlarmManagerService x0) {
        int i = x0.mSendCount;
        x0.mSendCount = i + 1;
        return i;
    }

    static /* synthetic */ int access$2508(AlarmManagerService x0) {
        int i = x0.mListenerCount;
        x0.mListenerCount = i + 1;
        return i;
    }

    /* loaded from: classes.dex */
    static final class IdleDispatchEntry {
        long argRealtime;
        long elapsedRealtime;
        String op;
        String pkg;
        String tag;
        int uid;

        IdleDispatchEntry() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Constants extends ContentObserver {
        private static final long DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME = 540000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_SHORT_TIME = 5000;
        private static final long DEFAULT_ALLOW_WHILE_IDLE_WHITELIST_DURATION = 10000;
        private static final long DEFAULT_LISTENER_TIMEOUT = 5000;
        private static final long DEFAULT_MAX_INTERVAL = 31536000000L;
        private static final long DEFAULT_MIN_FUTURITY = 5000;
        private static final long DEFAULT_MIN_INTERVAL = 60000;
        private static final String KEY_ALLOW_WHILE_IDLE_LONG_TIME = "allow_while_idle_long_time";
        private static final String KEY_ALLOW_WHILE_IDLE_SHORT_TIME = "allow_while_idle_short_time";
        private static final String KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION = "allow_while_idle_whitelist_duration";
        private static final String KEY_LISTENER_TIMEOUT = "listener_timeout";
        private static final String KEY_MAX_INTERVAL = "max_interval";
        private static final String KEY_MIN_FUTURITY = "min_futurity";
        private static final String KEY_MIN_INTERVAL = "min_interval";
        public long ALLOW_WHILE_IDLE_LONG_TIME;
        public long ALLOW_WHILE_IDLE_SHORT_TIME;
        public long ALLOW_WHILE_IDLE_WHITELIST_DURATION;
        public long[] APP_STANDBY_MIN_DELAYS;
        private final long[] DEFAULT_APP_STANDBY_DELAYS;
        private final String[] KEYS_APP_STANDBY_DELAY;
        public long LISTENER_TIMEOUT;
        public long MAX_INTERVAL;
        public long MIN_FUTURITY;
        public long MIN_INTERVAL;
        private long mLastAllowWhileIdleWhitelistDuration;
        private final KeyValueListParser mParser;
        private ContentResolver mResolver;

        public Constants(Handler handler) {
            super(handler);
            this.KEYS_APP_STANDBY_DELAY = new String[]{"standby_active_delay", "standby_working_delay", "standby_frequent_delay", "standby_rare_delay", "standby_never_delay"};
            this.DEFAULT_APP_STANDBY_DELAYS = new long[]{0, 360000, BackupAgentTimeoutParameters.DEFAULT_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS, AppStandbyController.SettingsObserver.DEFAULT_SYSTEM_UPDATE_TIMEOUT, 864000000};
            this.MIN_FUTURITY = 5000L;
            this.MIN_INTERVAL = 60000L;
            this.MAX_INTERVAL = 31536000000L;
            this.ALLOW_WHILE_IDLE_SHORT_TIME = 5000L;
            this.ALLOW_WHILE_IDLE_LONG_TIME = DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME;
            this.ALLOW_WHILE_IDLE_WHITELIST_DURATION = 10000L;
            this.LISTENER_TIMEOUT = 5000L;
            this.APP_STANDBY_MIN_DELAYS = new long[this.DEFAULT_APP_STANDBY_DELAYS.length];
            this.mParser = new KeyValueListParser(',');
            this.mLastAllowWhileIdleWhitelistDuration = -1L;
            updateAllowWhileIdleWhitelistDurationLocked();
        }

        public void start(ContentResolver resolver) {
            this.mResolver = resolver;
            this.mResolver.registerContentObserver(Settings.Global.getUriFor("alarm_manager_constants"), false, this);
            updateConstants();
        }

        public void updateAllowWhileIdleWhitelistDurationLocked() {
            if (this.mLastAllowWhileIdleWhitelistDuration != this.ALLOW_WHILE_IDLE_WHITELIST_DURATION) {
                this.mLastAllowWhileIdleWhitelistDuration = this.ALLOW_WHILE_IDLE_WHITELIST_DURATION;
                BroadcastOptions opts = BroadcastOptions.makeBasic();
                opts.setTemporaryAppWhitelistDuration(this.ALLOW_WHILE_IDLE_WHITELIST_DURATION);
                AlarmManagerService.this.mIdleOptions = opts.toBundle();
            }
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (AlarmManagerService.this.mLock) {
                try {
                    this.mParser.setString(Settings.Global.getString(this.mResolver, "alarm_manager_constants"));
                } catch (IllegalArgumentException e) {
                    Slog.e(AlarmManagerService.TAG, "Bad alarm manager settings", e);
                }
                this.MIN_FUTURITY = this.mParser.getLong(KEY_MIN_FUTURITY, 5000L);
                this.MIN_INTERVAL = this.mParser.getLong(KEY_MIN_INTERVAL, 60000L);
                this.MAX_INTERVAL = this.mParser.getLong(KEY_MAX_INTERVAL, 31536000000L);
                this.ALLOW_WHILE_IDLE_SHORT_TIME = this.mParser.getLong(KEY_ALLOW_WHILE_IDLE_SHORT_TIME, 5000L);
                this.ALLOW_WHILE_IDLE_LONG_TIME = this.mParser.getLong(KEY_ALLOW_WHILE_IDLE_LONG_TIME, (long) DEFAULT_ALLOW_WHILE_IDLE_LONG_TIME);
                this.ALLOW_WHILE_IDLE_WHITELIST_DURATION = this.mParser.getLong(KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION, 10000L);
                this.LISTENER_TIMEOUT = this.mParser.getLong(KEY_LISTENER_TIMEOUT, 5000L);
                this.APP_STANDBY_MIN_DELAYS[0] = this.mParser.getDurationMillis(this.KEYS_APP_STANDBY_DELAY[0], this.DEFAULT_APP_STANDBY_DELAYS[0]);
                for (int i = 1; i < this.KEYS_APP_STANDBY_DELAY.length; i++) {
                    this.APP_STANDBY_MIN_DELAYS[i] = this.mParser.getDurationMillis(this.KEYS_APP_STANDBY_DELAY[i], Math.max(this.APP_STANDBY_MIN_DELAYS[i - 1], this.DEFAULT_APP_STANDBY_DELAYS[i]));
                }
                updateAllowWhileIdleWhitelistDurationLocked();
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");
            pw.print("    ");
            pw.print(KEY_MIN_FUTURITY);
            pw.print("=");
            TimeUtils.formatDuration(this.MIN_FUTURITY, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_MIN_INTERVAL);
            pw.print("=");
            TimeUtils.formatDuration(this.MIN_INTERVAL, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_MAX_INTERVAL);
            pw.print("=");
            TimeUtils.formatDuration(this.MAX_INTERVAL, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_LISTENER_TIMEOUT);
            pw.print("=");
            TimeUtils.formatDuration(this.LISTENER_TIMEOUT, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_ALLOW_WHILE_IDLE_SHORT_TIME);
            pw.print("=");
            TimeUtils.formatDuration(this.ALLOW_WHILE_IDLE_SHORT_TIME, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_ALLOW_WHILE_IDLE_LONG_TIME);
            pw.print("=");
            TimeUtils.formatDuration(this.ALLOW_WHILE_IDLE_LONG_TIME, pw);
            pw.println();
            pw.print("    ");
            pw.print(KEY_ALLOW_WHILE_IDLE_WHITELIST_DURATION);
            pw.print("=");
            TimeUtils.formatDuration(this.ALLOW_WHILE_IDLE_WHITELIST_DURATION, pw);
            pw.println();
            for (int i = 0; i < this.KEYS_APP_STANDBY_DELAY.length; i++) {
                pw.print("    ");
                pw.print(this.KEYS_APP_STANDBY_DELAY[i]);
                pw.print("=");
                TimeUtils.formatDuration(this.APP_STANDBY_MIN_DELAYS[i], pw);
                pw.println();
            }
        }

        void dumpProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1112396529665L, this.MIN_FUTURITY);
            proto.write(1112396529666L, this.MIN_INTERVAL);
            proto.write(1112396529671L, this.MAX_INTERVAL);
            proto.write(1112396529667L, this.LISTENER_TIMEOUT);
            proto.write(1112396529668L, this.ALLOW_WHILE_IDLE_SHORT_TIME);
            proto.write(1112396529669L, this.ALLOW_WHILE_IDLE_LONG_TIME);
            proto.write(1112396529670L, this.ALLOW_WHILE_IDLE_WHITELIST_DURATION);
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class PriorityClass {
        int priority = 2;
        int seq;

        PriorityClass() {
            this.seq = AlarmManagerService.this.mCurrentSeq - 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class WakeupEvent {
        public String action;
        public int uid;
        public long when;

        public WakeupEvent(long theTime, int theUid, String theAction) {
            this.when = theTime;
            this.uid = theUid;
            this.action = theAction;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class Batch {
        final ArrayList<Alarm> alarms;
        long end;
        int flags;
        long start;

        Batch() {
            this.alarms = new ArrayList<>();
            this.start = 0L;
            this.end = JobStatus.NO_LATEST_RUNTIME;
            this.flags = 0;
        }

        Batch(Alarm seed) {
            this.alarms = new ArrayList<>();
            this.start = seed.whenElapsed;
            this.end = AlarmManagerService.clampPositive(seed.maxWhenElapsed);
            this.flags = seed.flags;
            this.alarms.add(seed);
            if (seed.operation == AlarmManagerService.this.mTimeTickSender) {
                AlarmManagerService.this.mLastTickAdded = System.currentTimeMillis();
            }
        }

        int size() {
            return this.alarms.size();
        }

        Alarm get(int index) {
            return this.alarms.get(index);
        }

        boolean canHold(long whenElapsed, long maxWhen) {
            return this.end >= whenElapsed && this.start <= maxWhen;
        }

        boolean add(Alarm alarm) {
            boolean newStart = false;
            int index = Collections.binarySearch(this.alarms, alarm, AlarmManagerService.sIncreasingTimeOrder);
            if (index < 0) {
                index = (0 - index) - 1;
            }
            this.alarms.add(index, alarm);
            if (alarm.operation == AlarmManagerService.this.mTimeTickSender) {
                AlarmManagerService.this.mLastTickAdded = System.currentTimeMillis();
            }
            if (alarm.whenElapsed > this.start) {
                this.start = alarm.whenElapsed;
                newStart = true;
            }
            if (alarm.maxWhenElapsed < this.end) {
                this.end = alarm.maxWhenElapsed;
            }
            this.flags |= alarm.flags;
            return newStart;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$remove$0(Alarm alarm, Alarm a) {
            return a == alarm;
        }

        boolean remove(final Alarm alarm) {
            return remove(new Predicate() { // from class: com.android.server.-$$Lambda$AlarmManagerService$Batch$Xltkj5RTKUMuFVeuavpuY7-Ogzc
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return AlarmManagerService.Batch.lambda$remove$0(AlarmManagerService.Alarm.this, (AlarmManagerService.Alarm) obj);
                }
            });
        }

        boolean remove(Predicate<Alarm> predicate) {
            boolean didRemove = false;
            long newStart = 0;
            long newEnd = JobStatus.NO_LATEST_RUNTIME;
            int newFlags = 0;
            int i = 0;
            while (i < this.alarms.size()) {
                Alarm alarm = this.alarms.get(i);
                if (predicate.test(alarm)) {
                    this.alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        AlarmManagerService.this.mNextAlarmClockMayChange = true;
                    }
                    if (alarm.operation == AlarmManagerService.this.mTimeTickSender) {
                        AlarmManagerService.this.mLastTickRemoved = System.currentTimeMillis();
                    }
                } else {
                    if (alarm.whenElapsed > newStart) {
                        newStart = alarm.whenElapsed;
                    }
                    if (alarm.maxWhenElapsed < newEnd) {
                        newEnd = alarm.maxWhenElapsed;
                    }
                    newFlags |= alarm.flags;
                    i++;
                }
            }
            if (didRemove) {
                this.start = newStart;
                this.end = newEnd;
                this.flags = newFlags;
            }
            return didRemove;
        }

        boolean hasPackage(String packageName) {
            int N = this.alarms.size();
            for (int i = 0; i < N; i++) {
                Alarm a = this.alarms.get(i);
                if (a.matches(packageName)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasWakeups() {
            int N = this.alarms.size();
            for (int i = 0; i < N; i++) {
                Alarm a = this.alarms.get(i);
                if ((a.type & 1) == 0) {
                    return true;
                }
            }
            return false;
        }

        public String toString() {
            StringBuilder b = new StringBuilder(40);
            b.append("Batch{");
            b.append(Integer.toHexString(hashCode()));
            b.append(" num=");
            b.append(size());
            b.append(" start=");
            b.append(this.start);
            b.append(" end=");
            b.append(this.end);
            if (this.flags != 0) {
                b.append(" flgs=0x");
                b.append(Integer.toHexString(this.flags));
            }
            b.append('}');
            return b.toString();
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId, long nowElapsed, long nowRTC) {
            long token = proto.start(fieldId);
            proto.write(1112396529665L, this.start);
            proto.write(1112396529666L, this.end);
            proto.write(1120986464259L, this.flags);
            Iterator<Alarm> it = this.alarms.iterator();
            while (it.hasNext()) {
                Alarm a = it.next();
                a.writeToProto(proto, 2246267895812L, nowElapsed, nowRTC);
            }
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class BatchTimeOrder implements Comparator<Batch> {
        BatchTimeOrder() {
        }

        @Override // java.util.Comparator
        public int compare(Batch b1, Batch b2) {
            long when1 = b1.start;
            long when2 = b2.start;
            if (when1 > when2) {
                return 1;
            }
            if (when1 < when2) {
                return -1;
            }
            return 0;
        }
    }

    void calculateDeliveryPriorities(ArrayList<Alarm> alarms) {
        int alarmPrio;
        int N = alarms.size();
        for (int i = 0; i < N; i++) {
            Alarm a = alarms.get(i);
            if (a.operation != null && "android.intent.action.TIME_TICK".equals(a.operation.getIntent().getAction())) {
                alarmPrio = 0;
            } else if (a.wakeup) {
                alarmPrio = 1;
            } else {
                alarmPrio = 2;
            }
            PriorityClass packagePrio = a.priorityClass;
            String alarmPackage = a.sourcePackage;
            if (packagePrio == null) {
                packagePrio = this.mPriorities.get(alarmPackage);
            }
            if (packagePrio == null) {
                PriorityClass priorityClass = new PriorityClass();
                a.priorityClass = priorityClass;
                packagePrio = priorityClass;
                this.mPriorities.put(alarmPackage, packagePrio);
            }
            a.priorityClass = packagePrio;
            if (packagePrio.seq != this.mCurrentSeq) {
                packagePrio.priority = alarmPrio;
                packagePrio.seq = this.mCurrentSeq;
            } else if (alarmPrio < packagePrio.priority) {
                packagePrio.priority = alarmPrio;
            }
        }
    }

    public AlarmManagerService(Context context) {
        super(context);
        this.mBackgroundIntent = new Intent().addFlags(4);
        this.mLog = new LocalLog(TAG);
        this.mLock = new Object();
        this.mPendingBackgroundAlarms = new SparseArray<>();
        this.mBroadcastRefCount = 0;
        this.mPendingNonWakeupAlarms = new ArrayList<>();
        this.mInFlight = new ArrayList<>();
        this.mHandler = new AlarmHandler();
        this.mDeliveryTracker = new DeliveryTracker();
        this.mInteractive = true;
        this.mLastAllowWhileIdleDispatch = new SparseLongArray();
        this.mUseAllowWhileIdleShortTime = new SparseBooleanArray();
        this.mAllowWhileIdleDispatches = new ArrayList<>();
        this.mStatLogger = new StatLogger(new String[]{"REBATCH_ALL_ALARMS", "REORDER_ALARMS_FOR_STANDBY"});
        this.mNextAlarmClockForUser = new SparseArray<>();
        this.mTmpSparseAlarmClockArray = new SparseArray<>();
        this.mPendingSendNextAlarmClockChangedForUser = new SparseBooleanArray();
        this.mHandlerSparseAlarmClockArray = new SparseArray<>();
        this.mLastAlarmDeliveredForPackage = new ArrayMap<>();
        this.mPriorities = new HashMap<>();
        this.mCurrentSeq = 0;
        this.mRecentWakeups = new LinkedList<>();
        this.RECENT_WAKEUP_PERIOD = 86400000L;
        this.mAlarmDispatchComparator = new Comparator<Alarm>() { // from class: com.android.server.AlarmManagerService.1
            @Override // java.util.Comparator
            public int compare(Alarm lhs, Alarm rhs) {
                if (lhs.priorityClass.priority < rhs.priorityClass.priority) {
                    return -1;
                }
                if (lhs.priorityClass.priority > rhs.priorityClass.priority) {
                    return 1;
                }
                if (lhs.whenElapsed < rhs.whenElapsed) {
                    return -1;
                }
                return lhs.whenElapsed > rhs.whenElapsed ? 1 : 0;
            }
        };
        this.mAlarmBatches = new ArrayList<>();
        this.mPendingIdleUntil = null;
        this.mNextWakeFromIdle = null;
        this.mPendingWhileIdleAlarms = new ArrayList<>();
        this.mBroadcastStats = new SparseArray<>();
        this.mNumDelayedAlarms = 0;
        this.mTotalDelayTime = 0L;
        this.mMaxDelayTime = 0L;
        this.mService = new IAlarmManager.Stub() { // from class: com.android.server.AlarmManagerService.2
            public void set(String callingPackage, int type, long triggerAtTime, long windowLength, long interval, int flags, PendingIntent operation, IAlarmListener directReceiver, String listenerTag, WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock) {
                int flags2;
                int flags3;
                int callingUid = Binder.getCallingUid();
                AlarmManagerService.this.mAppOps.checkPackage(callingUid, callingPackage);
                if (interval != 0 && directReceiver != null) {
                    throw new IllegalArgumentException("Repeating alarms cannot use AlarmReceivers");
                }
                if (workSource != null) {
                    AlarmManagerService.this.getContext().enforcePermission("android.permission.UPDATE_DEVICE_STATS", Binder.getCallingPid(), callingUid, "AlarmManager.set");
                }
                int flags4 = flags & (-11);
                if (callingUid != 1000) {
                    flags4 &= -17;
                }
                if (windowLength == 0) {
                    flags4 |= 1;
                }
                if (alarmClock != null) {
                    flags3 = flags4 | 3;
                } else if (workSource == null && (callingUid < 10000 || UserHandle.isSameApp(callingUid, AlarmManagerService.this.mSystemUiUid) || (AlarmManagerService.this.mAppStateTracker != null && AlarmManagerService.this.mAppStateTracker.isUidPowerSaveUserWhitelisted(callingUid)))) {
                    flags3 = (flags4 | 8) & (-5);
                } else {
                    flags2 = flags4;
                    AlarmManagerService.this.setImpl(type, triggerAtTime, windowLength, interval, operation, directReceiver, listenerTag, flags2, workSource, alarmClock, callingUid, callingPackage);
                }
                flags2 = flags3;
                AlarmManagerService.this.setImpl(type, triggerAtTime, windowLength, interval, operation, directReceiver, listenerTag, flags2, workSource, alarmClock, callingUid, callingPackage);
            }

            public boolean setTime(long millis) {
                AlarmManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.SET_TIME", "setTime");
                return AlarmManagerService.this.setTimeImpl(millis);
            }

            public void setTimeZone(String tz) {
                AlarmManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.SET_TIME_ZONE", "setTimeZone");
                long oldId = Binder.clearCallingIdentity();
                try {
                    AlarmManagerService.this.setTimeZoneImpl(tz);
                } finally {
                    Binder.restoreCallingIdentity(oldId);
                }
            }

            public void remove(PendingIntent operation, IAlarmListener listener) {
                if (operation == null && listener == null) {
                    Slog.w(AlarmManagerService.TAG, "remove() with no intent or listener");
                    return;
                }
                synchronized (AlarmManagerService.this.mLock) {
                    AlarmManagerService.this.removeLocked(operation, listener);
                }
            }

            public long getNextWakeFromIdleTime() {
                return AlarmManagerService.this.getNextWakeFromIdleTimeImpl();
            }

            public AlarmManager.AlarmClockInfo getNextAlarmClock(int userId) {
                return AlarmManagerService.this.getNextAlarmClockImpl(ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, "getNextAlarmClock", null));
            }

            public long currentNetworkTimeMillis() {
                NtpTrustedTime time = NtpTrustedTime.getInstance(AlarmManagerService.this.getContext());
                if (time.hasCache()) {
                    return time.currentTimeMillis();
                }
                throw new ParcelableException(new DateTimeException("Missing NTP fix"));
            }

            protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
                if (DumpUtils.checkDumpAndUsageStatsPermission(AlarmManagerService.this.getContext(), AlarmManagerService.TAG, pw)) {
                    if (args.length > 0 && PriorityDump.PROTO_ARG.equals(args[0])) {
                        AlarmManagerService.this.dumpProto(fd);
                    } else {
                        AlarmManagerService.this.dumpImpl(pw);
                    }
                }
            }

            /* JADX WARN: Multi-variable type inference failed */
            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                new ShellCmd().exec(this, in, out, err, args, callback, resultReceiver);
            }
        };
        this.mForceAppStandbyListener = new AppStateTracker.Listener() { // from class: com.android.server.AlarmManagerService.5
            @Override // com.android.server.AppStateTracker.Listener
            public void unblockAllUnrestrictedAlarms() {
                synchronized (AlarmManagerService.this.mLock) {
                    AlarmManagerService.this.sendAllUnrestrictedPendingBackgroundAlarmsLocked();
                }
            }

            @Override // com.android.server.AppStateTracker.Listener
            public void unblockAlarmsForUid(int uid) {
                synchronized (AlarmManagerService.this.mLock) {
                    AlarmManagerService.this.sendPendingBackgroundAlarmsLocked(uid, null);
                }
            }

            @Override // com.android.server.AppStateTracker.Listener
            public void unblockAlarmsForUidPackage(int uid, String packageName) {
                synchronized (AlarmManagerService.this.mLock) {
                    AlarmManagerService.this.sendPendingBackgroundAlarmsLocked(uid, packageName);
                }
            }

            @Override // com.android.server.AppStateTracker.Listener
            public void onUidForeground(int uid, boolean foreground) {
                synchronized (AlarmManagerService.this.mLock) {
                    if (foreground) {
                        try {
                            AlarmManagerService.this.mUseAllowWhileIdleShortTime.put(uid, true);
                        } catch (Throwable th) {
                            throw th;
                        }
                    }
                }
            }
        };
        this.mSendCount = 0;
        this.mSendFinishCount = 0;
        this.mListenerCount = 0;
        this.mListenerFinishCount = 0;
        this.mConstants = new Constants(this.mHandler);
        publishLocalService(AlarmManagerInternal.class, new LocalService());
    }

    static long convertToElapsed(long when, int type) {
        boolean isRtc = true;
        if (type != 1 && type != 0) {
            isRtc = false;
        }
        if (isRtc) {
            return when - (System.currentTimeMillis() - SystemClock.elapsedRealtime());
        }
        return when;
    }

    static long maxTriggerTime(long now, long triggerAtTime, long interval) {
        long futurity;
        if (interval == 0) {
            futurity = triggerAtTime - now;
        } else {
            futurity = interval;
        }
        if (futurity < 10000) {
            futurity = 0;
        }
        return clampPositive(((long) (0.75d * futurity)) + triggerAtTime);
    }

    static boolean addBatchLocked(ArrayList<Batch> list, Batch newBatch) {
        int index = Collections.binarySearch(list, newBatch, sBatchOrder);
        if (index < 0) {
            index = (0 - index) - 1;
        }
        list.add(index, newBatch);
        return index == 0;
    }

    private void insertAndBatchAlarmLocked(Alarm alarm) {
        int whichBatch = (alarm.flags & 1) != 0 ? -1 : attemptCoalesceLocked(alarm.whenElapsed, alarm.maxWhenElapsed);
        if (whichBatch < 0) {
            addBatchLocked(this.mAlarmBatches, new Batch(alarm));
            return;
        }
        Batch batch = this.mAlarmBatches.get(whichBatch);
        if (batch.add(alarm)) {
            this.mAlarmBatches.remove(whichBatch);
            addBatchLocked(this.mAlarmBatches, batch);
        }
    }

    int attemptCoalesceLocked(long whenElapsed, long maxWhen) {
        int N = this.mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            Batch b = this.mAlarmBatches.get(i);
            if ((b.flags & 1) == 0 && b.canHold(whenElapsed, maxWhen)) {
                return i;
            }
        }
        return -1;
    }

    static int getAlarmCount(ArrayList<Batch> batches) {
        int ret = 0;
        int size = batches.size();
        for (int i = 0; i < size; i++) {
            ret += batches.get(i).size();
        }
        return ret;
    }

    boolean haveAlarmsTimeTickAlarm(ArrayList<Alarm> alarms) {
        if (alarms.size() == 0) {
            return false;
        }
        int batchSize = alarms.size();
        for (int j = 0; j < batchSize; j++) {
            if (alarms.get(j).operation == this.mTimeTickSender) {
                return true;
            }
        }
        return false;
    }

    boolean haveBatchesTimeTickAlarm(ArrayList<Batch> batches) {
        int numBatches = batches.size();
        for (int i = 0; i < numBatches; i++) {
            if (haveAlarmsTimeTickAlarm(batches.get(i).alarms)) {
                return true;
            }
        }
        return false;
    }

    void rebatchAllAlarms() {
        synchronized (this.mLock) {
            rebatchAllAlarmsLocked(true);
        }
    }

    void rebatchAllAlarmsLocked(boolean doValidate) {
        long start = this.mStatLogger.getTime();
        int oldCount = getAlarmCount(this.mAlarmBatches) + ArrayUtils.size(this.mPendingWhileIdleAlarms);
        boolean oldHasTick = haveBatchesTimeTickAlarm(this.mAlarmBatches) || haveAlarmsTimeTickAlarm(this.mPendingWhileIdleAlarms);
        ArrayList<Batch> oldSet = (ArrayList) this.mAlarmBatches.clone();
        this.mAlarmBatches.clear();
        Alarm oldPendingIdleUntil = this.mPendingIdleUntil;
        long nowElapsed = SystemClock.elapsedRealtime();
        int oldBatches = oldSet.size();
        for (int batchNum = 0; batchNum < oldBatches; batchNum++) {
            Batch batch = oldSet.get(batchNum);
            int N = batch.size();
            for (int i = 0; i < N; i++) {
                reAddAlarmLocked(batch.get(i), nowElapsed, doValidate);
            }
        }
        if (oldPendingIdleUntil != null && oldPendingIdleUntil != this.mPendingIdleUntil) {
            Slog.wtf(TAG, "Rebatching: idle until changed from " + oldPendingIdleUntil + " to " + this.mPendingIdleUntil);
            if (this.mPendingIdleUntil == null) {
                restorePendingWhileIdleAlarmsLocked();
            }
        }
        int newCount = getAlarmCount(this.mAlarmBatches) + ArrayUtils.size(this.mPendingWhileIdleAlarms);
        boolean newHasTick = haveBatchesTimeTickAlarm(this.mAlarmBatches) || haveAlarmsTimeTickAlarm(this.mPendingWhileIdleAlarms);
        if (oldCount != newCount) {
            Slog.wtf(TAG, "Rebatching: total count changed from " + oldCount + " to " + newCount);
        }
        if (oldHasTick != newHasTick) {
            Slog.wtf(TAG, "Rebatching: hasTick changed from " + oldHasTick + " to " + newHasTick);
        }
        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();
        this.mStatLogger.logDurationStat(0, start);
    }

    boolean reorderAlarmsBasedOnStandbyBuckets(ArraySet<Pair<String, Integer>> targetPackages) {
        long start = this.mStatLogger.getTime();
        ArrayList<Alarm> rescheduledAlarms = new ArrayList<>();
        for (int batchIndex = this.mAlarmBatches.size() - 1; batchIndex >= 0; batchIndex--) {
            Batch batch = this.mAlarmBatches.get(batchIndex);
            for (int alarmIndex = batch.size() - 1; alarmIndex >= 0; alarmIndex--) {
                Alarm alarm = batch.get(alarmIndex);
                Pair<String, Integer> packageUser = Pair.create(alarm.sourcePackage, Integer.valueOf(UserHandle.getUserId(alarm.creatorUid)));
                if ((targetPackages == null || targetPackages.contains(packageUser)) && adjustDeliveryTimeBasedOnStandbyBucketLocked(alarm)) {
                    batch.remove(alarm);
                    rescheduledAlarms.add(alarm);
                }
            }
            int alarmIndex2 = batch.size();
            if (alarmIndex2 == 0) {
                this.mAlarmBatches.remove(batchIndex);
            }
        }
        for (int i = 0; i < rescheduledAlarms.size(); i++) {
            Alarm a = rescheduledAlarms.get(i);
            insertAndBatchAlarmLocked(a);
        }
        this.mStatLogger.logDurationStat(1, start);
        return rescheduledAlarms.size() > 0;
    }

    void reAddAlarmLocked(Alarm a, long nowElapsed, boolean doValidate) {
        long maxElapsed;
        a.when = a.origWhen;
        long whenElapsed = convertToElapsed(a.when, a.type);
        if (a.windowLength == 0) {
            maxElapsed = whenElapsed;
        } else {
            long maxElapsed2 = a.windowLength;
            if (maxElapsed2 > 0) {
                maxElapsed = clampPositive(a.windowLength + whenElapsed);
            } else {
                maxElapsed = maxTriggerTime(nowElapsed, whenElapsed, a.repeatInterval);
            }
        }
        a.whenElapsed = whenElapsed;
        a.maxWhenElapsed = maxElapsed;
        setImplLocked(a, true, doValidate);
    }

    static long clampPositive(long val) {
        return val >= 0 ? val : JobStatus.NO_LATEST_RUNTIME;
    }

    void sendPendingBackgroundAlarmsLocked(int uid, String packageName) {
        ArrayList<Alarm> alarmsToDeliver;
        ArrayList<Alarm> alarmsForUid = this.mPendingBackgroundAlarms.get(uid);
        if (alarmsForUid == null || alarmsForUid.size() == 0) {
            return;
        }
        if (packageName != null) {
            alarmsToDeliver = new ArrayList<>();
            for (int i = alarmsForUid.size() - 1; i >= 0; i--) {
                Alarm a = alarmsForUid.get(i);
                if (a.matches(packageName)) {
                    alarmsToDeliver.add(alarmsForUid.remove(i));
                }
            }
            int i2 = alarmsForUid.size();
            if (i2 == 0) {
                this.mPendingBackgroundAlarms.remove(uid);
            }
        } else {
            alarmsToDeliver = alarmsForUid;
            this.mPendingBackgroundAlarms.remove(uid);
        }
        deliverPendingBackgroundAlarmsLocked(alarmsToDeliver, SystemClock.elapsedRealtime());
    }

    void sendAllUnrestrictedPendingBackgroundAlarmsLocked() {
        ArrayList<Alarm> alarmsToDeliver = new ArrayList<>();
        findAllUnrestrictedPendingBackgroundAlarmsLockedInner(this.mPendingBackgroundAlarms, alarmsToDeliver, new Predicate() { // from class: com.android.server.-$$Lambda$AlarmManagerService$nSJw2tKfoL3YIrKDtszoL44jcSM
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isBackgroundRestricted;
                isBackgroundRestricted = AlarmManagerService.this.isBackgroundRestricted((AlarmManagerService.Alarm) obj);
                return isBackgroundRestricted;
            }
        });
        if (alarmsToDeliver.size() > 0) {
            deliverPendingBackgroundAlarmsLocked(alarmsToDeliver, SystemClock.elapsedRealtime());
        }
    }

    @VisibleForTesting
    static void findAllUnrestrictedPendingBackgroundAlarmsLockedInner(SparseArray<ArrayList<Alarm>> pendingAlarms, ArrayList<Alarm> unrestrictedAlarms, Predicate<Alarm> isBackgroundRestricted) {
        for (int uidIndex = pendingAlarms.size() - 1; uidIndex >= 0; uidIndex--) {
            pendingAlarms.keyAt(uidIndex);
            ArrayList<Alarm> alarmsForUid = pendingAlarms.valueAt(uidIndex);
            for (int alarmIndex = alarmsForUid.size() - 1; alarmIndex >= 0; alarmIndex--) {
                Alarm alarm = alarmsForUid.get(alarmIndex);
                if (!isBackgroundRestricted.test(alarm)) {
                    unrestrictedAlarms.add(alarm);
                    alarmsForUid.remove(alarmIndex);
                }
            }
            int alarmIndex2 = alarmsForUid.size();
            if (alarmIndex2 == 0) {
                pendingAlarms.removeAt(uidIndex);
            }
        }
    }

    private void deliverPendingBackgroundAlarmsLocked(ArrayList<Alarm> alarms, long nowELAPSED) {
        AlarmManagerService alarmManagerService;
        long j;
        int N;
        int i;
        AlarmManagerService alarmManagerService2 = this;
        ArrayList<Alarm> arrayList = alarms;
        long j2 = nowELAPSED;
        int N2 = alarms.size();
        boolean hasWakeup = false;
        int i2 = 0;
        while (true) {
            int i3 = i2;
            if (i3 >= N2) {
                break;
            }
            Alarm alarm = arrayList.get(i3);
            if (alarm.wakeup) {
                hasWakeup = true;
            }
            boolean hasWakeup2 = hasWakeup;
            alarm.count = 1;
            if (alarm.repeatInterval <= 0) {
                N = N2;
                i = i3;
            } else {
                alarm.count = (int) (alarm.count + ((j2 - alarm.expectedWhenElapsed) / alarm.repeatInterval));
                long delta = alarm.count * alarm.repeatInterval;
                long nextElapsed = alarm.whenElapsed + delta;
                N = N2;
                i = i3;
                alarmManagerService2.setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength, maxTriggerTime(j2, nextElapsed, alarm.repeatInterval), alarm.repeatInterval, alarm.operation, null, null, alarm.flags, true, alarm.workSource, alarm.alarmClock, alarm.uid, alarm.packageName);
            }
            i2 = i + 1;
            hasWakeup = hasWakeup2;
            N2 = N;
            j2 = nowELAPSED;
            arrayList = alarms;
            alarmManagerService2 = this;
        }
        if (hasWakeup) {
            alarmManagerService = this;
            j = nowELAPSED;
        } else {
            alarmManagerService = this;
            j = nowELAPSED;
            if (alarmManagerService.checkAllowNonWakeupDelayLocked(j)) {
                if (alarmManagerService.mPendingNonWakeupAlarms.size() == 0) {
                    alarmManagerService.mStartCurrentDelayTime = j;
                    alarmManagerService.mNextNonWakeupDeliveryTime = ((alarmManagerService.currentNonWakeupFuzzLocked(j) * 3) / 2) + j;
                }
                alarmManagerService.mPendingNonWakeupAlarms.addAll(alarms);
                alarmManagerService.mNumDelayedAlarms += alarms.size();
                return;
            }
        }
        if (alarmManagerService.mPendingNonWakeupAlarms.size() > 0) {
            alarms.addAll(alarmManagerService.mPendingNonWakeupAlarms);
            long thisDelayTime = j - alarmManagerService.mStartCurrentDelayTime;
            alarmManagerService.mTotalDelayTime += thisDelayTime;
            if (alarmManagerService.mMaxDelayTime < thisDelayTime) {
                alarmManagerService.mMaxDelayTime = thisDelayTime;
            }
            alarmManagerService.mPendingNonWakeupAlarms.clear();
        }
        calculateDeliveryPriorities(alarms);
        Collections.sort(alarms, alarmManagerService.mAlarmDispatchComparator);
        deliverAlarmsLocked(alarms, nowELAPSED);
    }

    void restorePendingWhileIdleAlarmsLocked() {
        if (this.mPendingWhileIdleAlarms.size() > 0) {
            ArrayList<Alarm> alarms = this.mPendingWhileIdleAlarms;
            this.mPendingWhileIdleAlarms = new ArrayList<>();
            long nowElapsed = SystemClock.elapsedRealtime();
            for (int i = alarms.size() - 1; i >= 0; i--) {
                Alarm a = alarms.get(i);
                reAddAlarmLocked(a, nowElapsed, false);
            }
        }
        rescheduleKernelAlarmsLocked();
        updateNextAlarmClockLocked();
        try {
            this.mTimeTickSender.send();
        } catch (PendingIntent.CanceledException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class InFlight {
        final int mAlarmType;
        final BroadcastStats mBroadcastStats;
        final FilterStats mFilterStats;
        final IBinder mListener;
        final PendingIntent mPendingIntent;
        final String mTag;
        final int mUid;
        final long mWhenElapsed;
        final WorkSource mWorkSource;

        InFlight(AlarmManagerService service, PendingIntent pendingIntent, IAlarmListener listener, WorkSource workSource, int uid, String alarmPkg, int alarmType, String tag, long nowELAPSED) {
            this.mPendingIntent = pendingIntent;
            this.mWhenElapsed = nowELAPSED;
            this.mListener = listener != null ? listener.asBinder() : null;
            this.mWorkSource = workSource;
            this.mUid = uid;
            this.mTag = tag;
            this.mBroadcastStats = pendingIntent != null ? service.getStatsLocked(pendingIntent) : service.getStatsLocked(uid, alarmPkg);
            FilterStats fs = this.mBroadcastStats.filterStats.get(this.mTag);
            if (fs == null) {
                fs = new FilterStats(this.mBroadcastStats, this.mTag);
                this.mBroadcastStats.filterStats.put(this.mTag, fs);
            }
            fs.lastTime = nowELAPSED;
            this.mFilterStats = fs;
            this.mAlarmType = alarmType;
        }

        public String toString() {
            return "InFlight{pendingIntent=" + this.mPendingIntent + ", when=" + this.mWhenElapsed + ", workSource=" + this.mWorkSource + ", uid=" + this.mUid + ", tag=" + this.mTag + ", broadcastStats=" + this.mBroadcastStats + ", filterStats=" + this.mFilterStats + ", alarmType=" + this.mAlarmType + "}";
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1120986464257L, this.mUid);
            proto.write(1138166333442L, this.mTag);
            proto.write(1112396529667L, this.mWhenElapsed);
            proto.write(1159641169924L, this.mAlarmType);
            if (this.mPendingIntent != null) {
                this.mPendingIntent.writeToProto(proto, 1146756268037L);
            }
            if (this.mBroadcastStats != null) {
                this.mBroadcastStats.writeToProto(proto, 1146756268038L);
            }
            if (this.mFilterStats != null) {
                this.mFilterStats.writeToProto(proto, 1146756268039L);
            }
            if (this.mWorkSource != null) {
                this.mWorkSource.writeToProto(proto, 1146756268040L);
            }
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class FilterStats {
        long aggregateTime;
        int count;
        long lastTime;
        final BroadcastStats mBroadcastStats;
        final String mTag;
        int nesting;
        int numWakeup;
        long startTime;

        FilterStats(BroadcastStats broadcastStats, String tag) {
            this.mBroadcastStats = broadcastStats;
            this.mTag = tag;
        }

        public String toString() {
            return "FilterStats{tag=" + this.mTag + ", lastTime=" + this.lastTime + ", aggregateTime=" + this.aggregateTime + ", count=" + this.count + ", numWakeup=" + this.numWakeup + ", startTime=" + this.startTime + ", nesting=" + this.nesting + "}";
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1138166333441L, this.mTag);
            proto.write(1112396529666L, this.lastTime);
            proto.write(1112396529667L, this.aggregateTime);
            proto.write(1120986464260L, this.count);
            proto.write(1120986464261L, this.numWakeup);
            proto.write(1112396529670L, this.startTime);
            proto.write(1120986464263L, this.nesting);
            proto.end(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class BroadcastStats {
        long aggregateTime;
        int count;
        final ArrayMap<String, FilterStats> filterStats = new ArrayMap<>();
        final String mPackageName;
        final int mUid;
        int nesting;
        int numWakeup;
        long startTime;

        BroadcastStats(int uid, String packageName) {
            this.mUid = uid;
            this.mPackageName = packageName;
        }

        public String toString() {
            return "BroadcastStats{uid=" + this.mUid + ", packageName=" + this.mPackageName + ", aggregateTime=" + this.aggregateTime + ", count=" + this.count + ", numWakeup=" + this.numWakeup + ", startTime=" + this.startTime + ", nesting=" + this.nesting + "}";
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(1120986464257L, this.mUid);
            proto.write(1138166333442L, this.mPackageName);
            proto.write(1112396529667L, this.aggregateTime);
            proto.write(1120986464260L, this.count);
            proto.write(1120986464261L, this.numWakeup);
            proto.write(1112396529670L, this.startTime);
            proto.write(1120986464263L, this.nesting);
            proto.end(token);
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        this.mNativeData = init();
        this.mNextNonWakeup = 0L;
        this.mNextWakeup = 0L;
        setTimeZoneImpl(SystemProperties.get(TIMEZONE_PROPERTY));
        if (this.mNativeData != 0) {
            long systemBuildTime = Long.max(1000 * SystemProperties.getLong("ro.build.date.utc", -1L), Long.max(Environment.getRootDirectory().lastModified(), Build.TIME));
            if (System.currentTimeMillis() < systemBuildTime) {
                Slog.i(TAG, "Current time only " + System.currentTimeMillis() + ", advancing to build time " + systemBuildTime);
                setKernelTime(this.mNativeData, systemBuildTime);
            }
        }
        PackageManager packMan = getContext().getPackageManager();
        try {
            PermissionInfo sysUiPerm = packMan.getPermissionInfo(SYSTEM_UI_SELF_PERMISSION, 0);
            ApplicationInfo sysUi = packMan.getApplicationInfo(sysUiPerm.packageName, 0);
            if ((sysUi.privateFlags & 8) != 0) {
                this.mSystemUiUid = sysUi.uid;
            } else {
                Slog.e(TAG, "SysUI permission android.permission.systemui.IDENTITY defined by non-privileged app " + sysUi.packageName + " - ignoring");
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        if (this.mSystemUiUid <= 0) {
            Slog.wtf(TAG, "SysUI package not found!");
        }
        PowerManager pm = (PowerManager) getContext().getSystemService("power");
        this.mWakeLock = pm.newWakeLock(1, "*alarm*");
        this.mTimeTickSender = PendingIntent.getBroadcastAsUser(getContext(), 0, new Intent("android.intent.action.TIME_TICK").addFlags(1344274432), 0, UserHandle.ALL);
        Intent intent = new Intent("android.intent.action.DATE_CHANGED");
        intent.addFlags(538968064);
        this.mDateChangeSender = PendingIntent.getBroadcastAsUser(getContext(), 0, intent, 67108864, UserHandle.ALL);
        this.mClockReceiver = new ClockReceiver();
        this.mClockReceiver.scheduleTimeTickEvent();
        this.mClockReceiver.scheduleDateChangedEvent();
        this.mInteractiveStateReceiver = new InteractiveStateReceiver();
        this.mUninstallReceiver = new UninstallReceiver();
        if (this.mNativeData != 0) {
            AlarmThread waitThread = new AlarmThread();
            waitThread.start();
        } else {
            Slog.w(TAG, "Failed to open alarm driver. Falling back to a handler.");
        }
        try {
            ActivityManager.getService().registerUidObserver(new UidObserver(), 14, -1, (String) null);
        } catch (RemoteException e2) {
        }
        publishBinderService("alarm", this.mService);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            this.mConstants.start(getContext().getContentResolver());
            this.mAppOps = (AppOpsManager) getContext().getSystemService("appops");
            this.mLocalDeviceIdleController = (DeviceIdleController.LocalService) LocalServices.getService(DeviceIdleController.LocalService.class);
            this.mUsageStatsManagerInternal = (UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class);
            this.mUsageStatsManagerInternal.addAppIdleStateChangeListener(new AppStandbyTracker());
            this.mAppStateTracker = (AppStateTracker) LocalServices.getService(AppStateTracker.class);
            this.mAppStateTracker.addListener(this.mForceAppStandbyListener);
        }
    }

    protected void finalize() throws Throwable {
        try {
            close(this.mNativeData);
        } finally {
            super.finalize();
        }
    }

    boolean setTimeImpl(long millis) {
        boolean z;
        if (this.mNativeData == 0) {
            Slog.w(TAG, "Not setting time since no alarm driver is available.");
            return false;
        }
        synchronized (this.mLock) {
            z = setKernelTime(this.mNativeData, millis) == 0;
        }
        return z;
    }

    void setTimeZoneImpl(String tz) {
        if (TextUtils.isEmpty(tz)) {
            return;
        }
        TimeZone zone = TimeZone.getTimeZone(tz);
        boolean timeZoneWasChanged = false;
        synchronized (this) {
            String current = SystemProperties.get(TIMEZONE_PROPERTY);
            if (current == null || !current.equals(zone.getID())) {
                timeZoneWasChanged = true;
                SystemProperties.set(TIMEZONE_PROPERTY, zone.getID());
            }
            int gmtOffset = zone.getOffset(System.currentTimeMillis());
            setKernelTimezone(this.mNativeData, -(gmtOffset / 60000));
        }
        TimeZone.setDefault(null);
        if (timeZoneWasChanged) {
            Intent intent = new Intent("android.intent.action.TIMEZONE_CHANGED");
            intent.addFlags(555745280);
            intent.putExtra("time-zone", zone.getID());
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    void removeImpl(PendingIntent operation) {
        if (operation == null) {
            return;
        }
        synchronized (this.mLock) {
            removeLocked(operation, null);
        }
    }

    void setImpl(int type, long triggerAtTime, long windowLength, long interval, PendingIntent operation, IAlarmListener directReceiver, String listenerTag, int flags, WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock, int callingUid, String callingPackage) {
        long maxElapsed;
        long j = triggerAtTime;
        long windowLength2 = windowLength;
        long j2 = interval;
        if ((operation == null && directReceiver == null) || (operation != null && directReceiver != null)) {
            Slog.w(TAG, "Alarms must either supply a PendingIntent or an AlarmReceiver");
            return;
        }
        if (windowLength2 > AppStandbyController.SettingsObserver.DEFAULT_NOTIFICATION_TIMEOUT) {
            Slog.w(TAG, "Window length " + windowLength2 + "ms suspiciously long; limiting to 1 hour");
            windowLength2 = 3600000;
        }
        long minInterval = this.mConstants.MIN_INTERVAL;
        if (j2 > 0 && j2 < minInterval) {
            Slog.w(TAG, "Suspiciously short interval " + j2 + " millis; expanding to " + (minInterval / 1000) + " seconds");
            j2 = minInterval;
        } else if (j2 > this.mConstants.MAX_INTERVAL) {
            Slog.w(TAG, "Suspiciously long interval " + j2 + " millis; clamping");
            j2 = this.mConstants.MAX_INTERVAL;
        }
        long interval2 = j2;
        if (type < 0 || type > 3) {
            throw new IllegalArgumentException("Invalid alarm type " + type);
        }
        if (j < 0) {
            long what = Binder.getCallingPid();
            Slog.w(TAG, "Invalid alarm trigger time! " + j + " from uid=" + callingUid + " pid=" + what);
            j = 0;
        }
        long triggerAtTime2 = j;
        long nowElapsed = SystemClock.elapsedRealtime();
        long nominalTrigger = convertToElapsed(triggerAtTime2, type);
        long minTrigger = nowElapsed + this.mConstants.MIN_FUTURITY;
        long triggerElapsed = nominalTrigger > minTrigger ? nominalTrigger : minTrigger;
        if (windowLength2 == 0) {
            maxElapsed = triggerElapsed;
        } else if (windowLength2 < 0) {
            maxElapsed = maxTriggerTime(nowElapsed, triggerElapsed, interval2);
            windowLength2 = maxElapsed - triggerElapsed;
        } else {
            maxElapsed = triggerElapsed + windowLength2;
        }
        long maxElapsed2 = maxElapsed;
        long windowLength3 = windowLength2;
        synchronized (this.mLock) {
            setImplLocked(type, triggerAtTime2, triggerElapsed, windowLength3, maxElapsed2, interval2, operation, directReceiver, listenerTag, flags, true, workSource, alarmClock, callingUid, callingPackage);
        }
    }

    private void setImplLocked(int type, long when, long whenElapsed, long windowLength, long maxWhen, long interval, PendingIntent operation, IAlarmListener directReceiver, String listenerTag, int flags, boolean doValidate, WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock, int callingUid, String callingPackage) {
        Alarm a = new Alarm(type, when, whenElapsed, windowLength, maxWhen, interval, operation, directReceiver, listenerTag, workSource, flags, alarmClock, callingUid, callingPackage);
        try {
            try {
                if (ActivityManager.getService().isAppStartModeDisabled(callingUid, callingPackage)) {
                    Slog.w(TAG, "Not setting alarm from " + callingUid + ":" + a + " -- package not allowed to start");
                    return;
                }
            } catch (RemoteException e) {
            }
        } catch (RemoteException e2) {
        }
        removeLocked(operation, directReceiver);
        setImplLocked(a, false, doValidate);
    }

    private long getMinDelayForBucketLocked(int bucket) {
        int index;
        if (bucket == 50) {
            index = 4;
        } else if (bucket > 30) {
            index = 3;
        } else if (bucket > 20) {
            index = 2;
        } else {
            index = bucket > 10 ? 1 : 0;
        }
        return this.mConstants.APP_STANDBY_MIN_DELAYS[index];
    }

    private boolean adjustDeliveryTimeBasedOnStandbyBucketLocked(Alarm alarm) {
        if (isExemptFromAppStandby(alarm)) {
            return false;
        }
        if (this.mAppStandbyParole) {
            if (alarm.whenElapsed > alarm.expectedWhenElapsed) {
                alarm.whenElapsed = alarm.expectedWhenElapsed;
                alarm.maxWhenElapsed = alarm.expectedMaxWhenElapsed;
                return true;
            }
            return false;
        }
        long oldWhenElapsed = alarm.whenElapsed;
        long oldMaxWhenElapsed = alarm.maxWhenElapsed;
        String sourcePackage = alarm.sourcePackage;
        int sourceUserId = UserHandle.getUserId(alarm.creatorUid);
        int standbyBucket = this.mUsageStatsManagerInternal.getAppStandbyBucket(sourcePackage, sourceUserId, SystemClock.elapsedRealtime());
        Pair<String, Integer> packageUser = Pair.create(sourcePackage, Integer.valueOf(sourceUserId));
        long lastElapsed = this.mLastAlarmDeliveredForPackage.getOrDefault(packageUser, 0L).longValue();
        if (lastElapsed > 0) {
            long minElapsed = getMinDelayForBucketLocked(standbyBucket) + lastElapsed;
            if (alarm.expectedWhenElapsed >= minElapsed) {
                alarm.whenElapsed = alarm.expectedWhenElapsed;
                alarm.maxWhenElapsed = alarm.expectedMaxWhenElapsed;
            } else {
                alarm.maxWhenElapsed = minElapsed;
                alarm.whenElapsed = minElapsed;
            }
        }
        return (oldWhenElapsed == alarm.whenElapsed && oldMaxWhenElapsed == alarm.maxWhenElapsed) ? false : true;
    }

    private void setImplLocked(Alarm a, boolean rebatching, boolean doValidate) {
        if ((a.flags & 16) != 0) {
            if (this.mNextWakeFromIdle != null && a.whenElapsed > this.mNextWakeFromIdle.whenElapsed) {
                long j = this.mNextWakeFromIdle.whenElapsed;
                a.maxWhenElapsed = j;
                a.whenElapsed = j;
                a.when = j;
            }
            long nowElapsed = SystemClock.elapsedRealtime();
            int fuzz = fuzzForDuration(a.whenElapsed - nowElapsed);
            if (fuzz > 0) {
                if (this.mRandom == null) {
                    this.mRandom = new Random();
                }
                int delta = this.mRandom.nextInt(fuzz);
                a.whenElapsed -= delta;
                long j2 = a.whenElapsed;
                a.maxWhenElapsed = j2;
                a.when = j2;
            }
        } else if (this.mPendingIdleUntil != null && (a.flags & 14) == 0) {
            this.mPendingWhileIdleAlarms.add(a);
            return;
        }
        adjustDeliveryTimeBasedOnStandbyBucketLocked(a);
        insertAndBatchAlarmLocked(a);
        if (a.alarmClock != null) {
            this.mNextAlarmClockMayChange = true;
        }
        boolean needRebatch = false;
        if ((a.flags & 16) != 0) {
            if (this.mPendingIdleUntil != a && this.mPendingIdleUntil != null) {
                Slog.wtfStack(TAG, "setImplLocked: idle until changed from " + this.mPendingIdleUntil + " to " + a);
            }
            this.mPendingIdleUntil = a;
            needRebatch = true;
        } else if ((a.flags & 2) != 0 && (this.mNextWakeFromIdle == null || this.mNextWakeFromIdle.whenElapsed > a.whenElapsed)) {
            this.mNextWakeFromIdle = a;
            if (this.mPendingIdleUntil != null) {
                needRebatch = true;
            }
        }
        if (!rebatching) {
            if (needRebatch) {
                rebatchAllAlarmsLocked(false);
            }
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    /* loaded from: classes.dex */
    private final class LocalService implements AlarmManagerInternal {
        private LocalService() {
        }

        @Override // com.android.server.AlarmManagerInternal
        public void removeAlarmsForUid(int uid) {
            synchronized (AlarmManagerService.this.mLock) {
                AlarmManagerService.this.removeLocked(uid);
            }
        }
    }

    void dumpImpl(PrintWriter pw) {
        SimpleDateFormat sdf;
        int pos;
        ArrayMap<String, BroadcastStats> uidStats;
        BroadcastStats bs;
        int i;
        Iterator<Integer> it;
        long nextWakeupRTC;
        long nextWakeupRTC2;
        synchronized (this.mLock) {
            pw.println("Current Alarm Manager state:");
            this.mConstants.dump(pw);
            pw.println();
            if (this.mAppStateTracker != null) {
                this.mAppStateTracker.dump(pw, "  ");
                pw.println();
            }
            pw.println("  App Standby Parole: " + this.mAppStandbyParole);
            pw.println();
            long nowRTC = System.currentTimeMillis();
            long nowELAPSED = SystemClock.elapsedRealtime();
            long nowUPTIME = SystemClock.uptimeMillis();
            SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            pw.print("  nowRTC=");
            pw.print(nowRTC);
            pw.print("=");
            pw.print(sdf2.format(new Date(nowRTC)));
            pw.print(" nowELAPSED=");
            pw.print(nowELAPSED);
            pw.println();
            pw.print("  mLastTimeChangeClockTime=");
            pw.print(this.mLastTimeChangeClockTime);
            pw.print("=");
            pw.println(sdf2.format(new Date(this.mLastTimeChangeClockTime)));
            pw.print("  mLastTimeChangeRealtime=");
            pw.println(this.mLastTimeChangeRealtime);
            pw.print("  mLastTickIssued=");
            pw.println(sdf2.format(new Date(nowRTC - (nowELAPSED - this.mLastTickIssued))));
            pw.print("  mLastTickReceived=");
            pw.println(sdf2.format(new Date(this.mLastTickReceived)));
            pw.print("  mLastTickSet=");
            pw.println(sdf2.format(new Date(this.mLastTickSet)));
            pw.print("  mLastTickAdded=");
            pw.println(sdf2.format(new Date(this.mLastTickAdded)));
            pw.print("  mLastTickRemoved=");
            pw.println(sdf2.format(new Date(this.mLastTickRemoved)));
            SystemServiceManager ssm = (SystemServiceManager) LocalServices.getService(SystemServiceManager.class);
            if (ssm != null) {
                pw.println();
                pw.print("  RuntimeStarted=");
                pw.print(sdf2.format(new Date((nowRTC - nowELAPSED) + ssm.getRuntimeStartElapsedTime())));
                if (ssm.isRuntimeRestarted()) {
                    pw.print("  (Runtime restarted)");
                }
                pw.println();
                pw.print("  Runtime uptime (elapsed): ");
                TimeUtils.formatDuration(nowELAPSED, ssm.getRuntimeStartElapsedTime(), pw);
                pw.println();
                pw.print("  Runtime uptime (uptime): ");
                TimeUtils.formatDuration(nowUPTIME, ssm.getRuntimeStartUptime(), pw);
                pw.println();
            }
            pw.println();
            if (!this.mInteractive) {
                pw.print("  Time since non-interactive: ");
                TimeUtils.formatDuration(nowELAPSED - this.mNonInteractiveStartTime, pw);
                pw.println();
            }
            pw.print("  Max wakeup delay: ");
            TimeUtils.formatDuration(currentNonWakeupFuzzLocked(nowELAPSED), pw);
            pw.println();
            pw.print("  Time since last dispatch: ");
            TimeUtils.formatDuration(nowELAPSED - this.mLastAlarmDeliveryTime, pw);
            pw.println();
            pw.print("  Next non-wakeup delivery time: ");
            TimeUtils.formatDuration(nowELAPSED - this.mNextNonWakeupDeliveryTime, pw);
            pw.println();
            long nowRTC2 = nowUPTIME;
            long nowRTC3 = this.mNextWakeup + (nowRTC - nowELAPSED);
            long nowRTC4 = nowRTC;
            long nextNonWakeupRTC = this.mNextNonWakeup + (nowRTC - nowELAPSED);
            pw.print("  Next non-wakeup alarm: ");
            TimeUtils.formatDuration(this.mNextNonWakeup, nowELAPSED, pw);
            pw.print(" = ");
            pw.print(this.mNextNonWakeup);
            pw.print(" = ");
            pw.println(sdf2.format(new Date(nextNonWakeupRTC)));
            pw.print("  Next wakeup alarm: ");
            TimeUtils.formatDuration(this.mNextWakeup, nowELAPSED, pw);
            pw.print(" = ");
            pw.print(this.mNextWakeup);
            pw.print(" = ");
            pw.println(sdf2.format(new Date(nowRTC3)));
            pw.print("    set at ");
            TimeUtils.formatDuration(this.mLastWakeupSet, nowELAPSED, pw);
            pw.println();
            pw.print("  Last wakeup: ");
            TimeUtils.formatDuration(this.mLastWakeup, nowELAPSED, pw);
            pw.print(" = ");
            pw.println(this.mLastWakeup);
            pw.print("  Last trigger: ");
            TimeUtils.formatDuration(this.mLastTrigger, nowELAPSED, pw);
            pw.print(" = ");
            pw.println(this.mLastTrigger);
            pw.print("  Num time change events: ");
            pw.println(this.mNumTimeChanged);
            pw.println();
            pw.println("  Next alarm clock information: ");
            TreeSet<Integer> users = new TreeSet<>();
            for (int i2 = 0; i2 < this.mNextAlarmClockForUser.size(); i2++) {
                users.add(Integer.valueOf(this.mNextAlarmClockForUser.keyAt(i2)));
            }
            for (int i3 = 0; i3 < this.mPendingSendNextAlarmClockChangedForUser.size(); i3++) {
                users.add(Integer.valueOf(this.mPendingSendNextAlarmClockChangedForUser.keyAt(i3)));
            }
            Iterator<Integer> it2 = users.iterator();
            while (true) {
                long nextNonWakeupRTC2 = nextNonWakeupRTC;
                if (!it2.hasNext()) {
                    break;
                }
                int user = it2.next().intValue();
                AlarmManager.AlarmClockInfo next = this.mNextAlarmClockForUser.get(user);
                long time = next != null ? next.getTriggerTime() : 0L;
                boolean pendingSend = this.mPendingSendNextAlarmClockChangedForUser.get(user);
                pw.print("    user:");
                pw.print(user);
                pw.print(" pendingSend:");
                pw.print(pendingSend);
                pw.print(" time:");
                pw.print(time);
                if (time > 0) {
                    it = it2;
                    pw.print(" = ");
                    pw.print(sdf2.format(new Date(time)));
                    pw.print(" = ");
                    nextWakeupRTC = nowRTC3;
                    nextWakeupRTC2 = nowRTC4;
                    TimeUtils.formatDuration(time, nextWakeupRTC2, pw);
                } else {
                    it = it2;
                    nextWakeupRTC = nowRTC3;
                    nextWakeupRTC2 = nowRTC4;
                }
                pw.println();
                nowRTC4 = nextWakeupRTC2;
                nextNonWakeupRTC = nextNonWakeupRTC2;
                it2 = it;
                nowRTC3 = nextWakeupRTC;
            }
            long nextWakeupRTC3 = nowRTC3;
            long nowRTC5 = nowRTC4;
            if (this.mAlarmBatches.size() > 0) {
                pw.println();
                pw.print("  Pending alarm batches: ");
                pw.println(this.mAlarmBatches.size());
                for (Iterator<Batch> it3 = this.mAlarmBatches.iterator(); it3.hasNext(); it3 = it3) {
                    Batch b = it3.next();
                    pw.print(b);
                    pw.println(':');
                    dumpAlarmList(pw, b.alarms, "    ", nowELAPSED, nowRTC5, sdf2);
                    nowRTC5 = nowRTC5;
                    users = users;
                    ssm = ssm;
                    nowRTC2 = nowRTC2;
                    nextWakeupRTC3 = nextWakeupRTC3;
                }
            }
            int i4 = 0;
            long nowUPTIME2 = nowRTC5;
            pw.println();
            pw.println("  Pending user blocked background alarms: ");
            boolean blocked = false;
            int i5 = 0;
            while (true) {
                int i6 = i5;
                if (i6 >= this.mPendingBackgroundAlarms.size()) {
                    break;
                }
                ArrayList<Alarm> blockedAlarms = this.mPendingBackgroundAlarms.valueAt(i6);
                if (blockedAlarms == null || blockedAlarms.size() <= 0) {
                    i = i6;
                } else {
                    blocked = true;
                    i = i6;
                    dumpAlarmList(pw, blockedAlarms, "    ", nowELAPSED, nowUPTIME2, sdf2);
                }
                i5 = i + 1;
            }
            if (!blocked) {
                pw.println("    none");
            }
            pw.println("  mLastAlarmDeliveredForPackage:");
            for (int i7 = 0; i7 < this.mLastAlarmDeliveredForPackage.size(); i7++) {
                Pair<String, Integer> packageUser = this.mLastAlarmDeliveredForPackage.keyAt(i7);
                pw.print("    Package " + ((String) packageUser.first) + ", User " + packageUser.second + ":");
                TimeUtils.formatDuration(this.mLastAlarmDeliveredForPackage.valueAt(i7).longValue(), nowELAPSED, pw);
                pw.println();
            }
            pw.println();
            if (this.mPendingIdleUntil != null || this.mPendingWhileIdleAlarms.size() > 0) {
                pw.println();
                pw.println("    Idle mode state:");
                pw.print("      Idling until: ");
                if (this.mPendingIdleUntil != null) {
                    pw.println(this.mPendingIdleUntil);
                    this.mPendingIdleUntil.dump(pw, "        ", nowELAPSED, nowUPTIME2, sdf2);
                } else {
                    pw.println("null");
                }
                pw.println("      Pending alarms:");
                dumpAlarmList(pw, this.mPendingWhileIdleAlarms, "      ", nowELAPSED, nowUPTIME2, sdf2);
            }
            if (this.mNextWakeFromIdle != null) {
                pw.println();
                pw.print("  Next wake from idle: ");
                pw.println(this.mNextWakeFromIdle);
                this.mNextWakeFromIdle.dump(pw, "    ", nowELAPSED, nowUPTIME2, sdf2);
            }
            pw.println();
            pw.print("  Past-due non-wakeup alarms: ");
            if (this.mPendingNonWakeupAlarms.size() > 0) {
                pw.println(this.mPendingNonWakeupAlarms.size());
                dumpAlarmList(pw, this.mPendingNonWakeupAlarms, "    ", nowELAPSED, nowUPTIME2, sdf2);
            } else {
                pw.println("(none)");
            }
            pw.print("    Number of delayed alarms: ");
            pw.print(this.mNumDelayedAlarms);
            pw.print(", total delay time: ");
            TimeUtils.formatDuration(this.mTotalDelayTime, pw);
            pw.println();
            pw.print("    Max delay time: ");
            TimeUtils.formatDuration(this.mMaxDelayTime, pw);
            pw.print(", max non-interactive time: ");
            TimeUtils.formatDuration(this.mNonInteractiveTime, pw);
            pw.println();
            pw.println();
            pw.print("  Broadcast ref count: ");
            pw.println(this.mBroadcastRefCount);
            pw.print("  PendingIntent send count: ");
            pw.println(this.mSendCount);
            pw.print("  PendingIntent finish count: ");
            pw.println(this.mSendFinishCount);
            pw.print("  Listener send count: ");
            pw.println(this.mListenerCount);
            pw.print("  Listener finish count: ");
            pw.println(this.mListenerFinishCount);
            pw.println();
            if (this.mInFlight.size() > 0) {
                pw.println("Outstanding deliveries:");
                for (int i8 = 0; i8 < this.mInFlight.size(); i8++) {
                    pw.print("   #");
                    pw.print(i8);
                    pw.print(": ");
                    pw.println(this.mInFlight.get(i8));
                }
                pw.println();
            }
            if (this.mLastAllowWhileIdleDispatch.size() > 0) {
                pw.println("  Last allow while idle dispatch times:");
                for (int i9 = 0; i9 < this.mLastAllowWhileIdleDispatch.size(); i9++) {
                    pw.print("    UID ");
                    int uid = this.mLastAllowWhileIdleDispatch.keyAt(i9);
                    UserHandle.formatUid(pw, uid);
                    pw.print(": ");
                    long lastTime = this.mLastAllowWhileIdleDispatch.valueAt(i9);
                    TimeUtils.formatDuration(lastTime, nowELAPSED, pw);
                    long minInterval = getWhileIdleMinIntervalLocked(uid);
                    pw.print("  Next allowed:");
                    TimeUtils.formatDuration(lastTime + minInterval, nowELAPSED, pw);
                    pw.print(" (");
                    TimeUtils.formatDuration(minInterval, 0L, pw);
                    pw.print(")");
                    pw.println();
                }
            }
            pw.print("  mUseAllowWhileIdleShortTime: [");
            for (int i10 = 0; i10 < this.mUseAllowWhileIdleShortTime.size(); i10++) {
                if (this.mUseAllowWhileIdleShortTime.valueAt(i10)) {
                    UserHandle.formatUid(pw, this.mUseAllowWhileIdleShortTime.keyAt(i10));
                    pw.print(" ");
                }
            }
            pw.println("]");
            pw.println();
            if (this.mLog.dump(pw, "  Recent problems", "    ")) {
                pw.println();
            }
            FilterStats[] topFilters = new FilterStats[10];
            Comparator<FilterStats> comparator = new Comparator<FilterStats>() { // from class: com.android.server.AlarmManagerService.3
                @Override // java.util.Comparator
                public int compare(FilterStats lhs, FilterStats rhs) {
                    if (lhs.aggregateTime < rhs.aggregateTime) {
                        return 1;
                    }
                    if (lhs.aggregateTime > rhs.aggregateTime) {
                        return -1;
                    }
                    return 0;
                }
            };
            int len = 0;
            int len2 = 0;
            while (len2 < this.mBroadcastStats.size()) {
                ArrayMap<String, BroadcastStats> uidStats2 = this.mBroadcastStats.valueAt(len2);
                int len3 = len;
                int len4 = i4;
                while (len4 < uidStats2.size()) {
                    BroadcastStats bs2 = uidStats2.valueAt(len4);
                    int len5 = len3;
                    int len6 = i4;
                    while (len6 < bs2.filterStats.size()) {
                        FilterStats fs = bs2.filterStats.valueAt(len6);
                        if (len5 > 0) {
                            sdf = sdf2;
                            pos = Arrays.binarySearch(topFilters, 0, len5, fs, comparator);
                        } else {
                            sdf = sdf2;
                            pos = 0;
                        }
                        int pos2 = pos;
                        if (pos2 < 0) {
                            uidStats = uidStats2;
                            pos2 = (-pos2) - 1;
                        } else {
                            uidStats = uidStats2;
                        }
                        if (pos2 < topFilters.length) {
                            int copylen = (topFilters.length - pos2) - 1;
                            if (copylen > 0) {
                                bs = bs2;
                                System.arraycopy(topFilters, pos2, topFilters, pos2 + 1, copylen);
                            } else {
                                bs = bs2;
                            }
                            topFilters[pos2] = fs;
                            if (len5 < topFilters.length) {
                                len5++;
                            }
                        } else {
                            bs = bs2;
                        }
                        len6++;
                        sdf2 = sdf;
                        uidStats2 = uidStats;
                        bs2 = bs;
                    }
                    len4++;
                    len3 = len5;
                    i4 = 0;
                }
                len2++;
                len = len3;
                i4 = 0;
            }
            if (len > 0) {
                pw.println("  Top Alarms:");
                for (int i11 = 0; i11 < len; i11++) {
                    FilterStats fs2 = topFilters[i11];
                    pw.print("    ");
                    if (fs2.nesting > 0) {
                        pw.print("*ACTIVE* ");
                    }
                    TimeUtils.formatDuration(fs2.aggregateTime, pw);
                    pw.print(" running, ");
                    pw.print(fs2.numWakeup);
                    pw.print(" wakeups, ");
                    pw.print(fs2.count);
                    pw.print(" alarms: ");
                    UserHandle.formatUid(pw, fs2.mBroadcastStats.mUid);
                    pw.print(":");
                    pw.print(fs2.mBroadcastStats.mPackageName);
                    pw.println();
                    pw.print("      ");
                    pw.print(fs2.mTag);
                    pw.println();
                }
            }
            pw.println(" ");
            pw.println("  Alarm Stats:");
            ArrayList<FilterStats> tmpFilters = new ArrayList<>();
            for (int iu = 0; iu < this.mBroadcastStats.size(); iu++) {
                ArrayMap<String, BroadcastStats> uidStats3 = this.mBroadcastStats.valueAt(iu);
                int ip = 0;
                while (ip < uidStats3.size()) {
                    BroadcastStats bs3 = uidStats3.valueAt(ip);
                    pw.print("  ");
                    if (bs3.nesting > 0) {
                        pw.print("*ACTIVE* ");
                    }
                    UserHandle.formatUid(pw, bs3.mUid);
                    pw.print(":");
                    pw.print(bs3.mPackageName);
                    pw.print(" ");
                    int len7 = len;
                    ArrayMap<String, BroadcastStats> uidStats4 = uidStats3;
                    TimeUtils.formatDuration(bs3.aggregateTime, pw);
                    pw.print(" running, ");
                    pw.print(bs3.numWakeup);
                    pw.println(" wakeups:");
                    tmpFilters.clear();
                    for (int is = 0; is < bs3.filterStats.size(); is++) {
                        tmpFilters.add(bs3.filterStats.valueAt(is));
                    }
                    Collections.sort(tmpFilters, comparator);
                    int i12 = 0;
                    while (i12 < tmpFilters.size()) {
                        FilterStats fs3 = tmpFilters.get(i12);
                        pw.print("    ");
                        if (fs3.nesting > 0) {
                            pw.print("*ACTIVE* ");
                        }
                        TimeUtils.formatDuration(fs3.aggregateTime, pw);
                        pw.print(" ");
                        pw.print(fs3.numWakeup);
                        pw.print(" wakes ");
                        pw.print(fs3.count);
                        pw.print(" alarms, last ");
                        TimeUtils.formatDuration(fs3.lastTime, nowELAPSED, pw);
                        pw.println(":");
                        pw.print("      ");
                        pw.print(fs3.mTag);
                        pw.println();
                        i12++;
                        topFilters = topFilters;
                        comparator = comparator;
                    }
                    ip++;
                    len = len7;
                    uidStats3 = uidStats4;
                }
            }
            pw.println();
            this.mStatLogger.dump(pw, "  ");
        }
    }

    void dumpProto(FileDescriptor fd) {
        TreeSet<Integer> users;
        AlarmManagerService alarmManagerService = this;
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (alarmManagerService.mLock) {
            long nowRTC = System.currentTimeMillis();
            long nowElapsed = SystemClock.elapsedRealtime();
            proto.write(1112396529665L, nowRTC);
            proto.write(1112396529666L, nowElapsed);
            proto.write(1112396529667L, alarmManagerService.mLastTimeChangeClockTime);
            proto.write(1112396529668L, alarmManagerService.mLastTimeChangeRealtime);
            alarmManagerService.mConstants.dumpProto(proto, 1146756268037L);
            if (alarmManagerService.mAppStateTracker != null) {
                alarmManagerService.mAppStateTracker.dumpProto(proto, 1146756268038L);
            }
            proto.write(1133871366151L, alarmManagerService.mInteractive);
            if (!alarmManagerService.mInteractive) {
                proto.write(1112396529672L, nowElapsed - alarmManagerService.mNonInteractiveStartTime);
                proto.write(1112396529673L, alarmManagerService.currentNonWakeupFuzzLocked(nowElapsed));
                proto.write(1112396529674L, nowElapsed - alarmManagerService.mLastAlarmDeliveryTime);
                proto.write(1112396529675L, nowElapsed - alarmManagerService.mNextNonWakeupDeliveryTime);
            }
            proto.write(1112396529676L, alarmManagerService.mNextNonWakeup - nowElapsed);
            proto.write(1112396529677L, alarmManagerService.mNextWakeup - nowElapsed);
            proto.write(1112396529678L, nowElapsed - alarmManagerService.mLastWakeup);
            proto.write(1112396529679L, nowElapsed - alarmManagerService.mLastWakeupSet);
            proto.write(1112396529680L, alarmManagerService.mNumTimeChanged);
            TreeSet<Integer> users2 = new TreeSet<>();
            int nextAlarmClockForUserSize = alarmManagerService.mNextAlarmClockForUser.size();
            for (int i = 0; i < nextAlarmClockForUserSize; i++) {
                users2.add(Integer.valueOf(alarmManagerService.mNextAlarmClockForUser.keyAt(i)));
            }
            int pendingSendNextAlarmClockChangedForUserSize = alarmManagerService.mPendingSendNextAlarmClockChangedForUser.size();
            for (int i2 = 0; i2 < pendingSendNextAlarmClockChangedForUserSize; i2++) {
                users2.add(Integer.valueOf(alarmManagerService.mPendingSendNextAlarmClockChangedForUser.keyAt(i2)));
            }
            Iterator<Integer> it = users2.iterator();
            while (it.hasNext()) {
                int user = it.next().intValue();
                AlarmManager.AlarmClockInfo next = alarmManagerService.mNextAlarmClockForUser.get(user);
                long time = next != null ? next.getTriggerTime() : 0L;
                boolean pendingSend = alarmManagerService.mPendingSendNextAlarmClockChangedForUser.get(user);
                long aToken = proto.start(2246267895826L);
                proto.write(1120986464257L, user);
                proto.write(1133871366146L, pendingSend);
                proto.write(1112396529667L, time);
                proto.end(aToken);
                it = it;
                nextAlarmClockForUserSize = nextAlarmClockForUserSize;
                nowRTC = nowRTC;
            }
            int nextAlarmClockForUserSize2 = nextAlarmClockForUserSize;
            long nowRTC2 = nowRTC;
            long j = 1120986464257L;
            Iterator<Batch> it2 = alarmManagerService.mAlarmBatches.iterator();
            while (it2.hasNext()) {
                Batch b = it2.next();
                b.writeToProto(proto, 2246267895827L, nowElapsed, nowRTC2);
                j = j;
                nextAlarmClockForUserSize2 = nextAlarmClockForUserSize2;
                nowElapsed = nowElapsed;
                pendingSendNextAlarmClockChangedForUserSize = pendingSendNextAlarmClockChangedForUserSize;
            }
            long j2 = j;
            long nowElapsed2 = nowElapsed;
            for (int i3 = 0; i3 < alarmManagerService.mPendingBackgroundAlarms.size(); i3++) {
                ArrayList<Alarm> blockedAlarms = alarmManagerService.mPendingBackgroundAlarms.valueAt(i3);
                if (blockedAlarms != null) {
                    for (Iterator<Alarm> it3 = blockedAlarms.iterator(); it3.hasNext(); it3 = it3) {
                        Alarm a = it3.next();
                        a.writeToProto(proto, 2246267895828L, nowElapsed2, nowRTC2);
                        blockedAlarms = blockedAlarms;
                    }
                }
            }
            if (alarmManagerService.mPendingIdleUntil != null) {
                alarmManagerService.mPendingIdleUntil.writeToProto(proto, 1146756268053L, nowElapsed2, nowRTC2);
            }
            Iterator<Alarm> it4 = alarmManagerService.mPendingWhileIdleAlarms.iterator();
            while (it4.hasNext()) {
                Alarm a2 = it4.next();
                a2.writeToProto(proto, 2246267895830L, nowElapsed2, nowRTC2);
            }
            if (alarmManagerService.mNextWakeFromIdle != null) {
                alarmManagerService.mNextWakeFromIdle.writeToProto(proto, 1146756268055L, nowElapsed2, nowRTC2);
            }
            Iterator<Alarm> it5 = alarmManagerService.mPendingNonWakeupAlarms.iterator();
            while (it5.hasNext()) {
                Alarm a3 = it5.next();
                a3.writeToProto(proto, 2246267895832L, nowElapsed2, nowRTC2);
            }
            proto.write(1120986464281L, alarmManagerService.mNumDelayedAlarms);
            proto.write(1112396529690L, alarmManagerService.mTotalDelayTime);
            proto.write(1112396529691L, alarmManagerService.mMaxDelayTime);
            proto.write(1112396529692L, alarmManagerService.mNonInteractiveTime);
            proto.write(1120986464285L, alarmManagerService.mBroadcastRefCount);
            proto.write(1120986464286L, alarmManagerService.mSendCount);
            proto.write(1120986464287L, alarmManagerService.mSendFinishCount);
            proto.write(1120986464288L, alarmManagerService.mListenerCount);
            proto.write(1120986464289L, alarmManagerService.mListenerFinishCount);
            Iterator<InFlight> it6 = alarmManagerService.mInFlight.iterator();
            while (it6.hasNext()) {
                InFlight f = it6.next();
                f.writeToProto(proto, 2246267895842L);
            }
            int i4 = 0;
            while (i4 < alarmManagerService.mLastAllowWhileIdleDispatch.size()) {
                long token = proto.start(2246267895844L);
                int uid = alarmManagerService.mLastAllowWhileIdleDispatch.keyAt(i4);
                long lastTime = alarmManagerService.mLastAllowWhileIdleDispatch.valueAt(i4);
                proto.write(j2, uid);
                proto.write(1112396529666L, lastTime);
                proto.write(1112396529667L, lastTime + alarmManagerService.getWhileIdleMinIntervalLocked(uid));
                proto.end(token);
                i4++;
                j2 = 1120986464257L;
            }
            for (int i5 = 0; i5 < alarmManagerService.mUseAllowWhileIdleShortTime.size(); i5++) {
                if (alarmManagerService.mUseAllowWhileIdleShortTime.valueAt(i5)) {
                    proto.write(2220498092067L, alarmManagerService.mUseAllowWhileIdleShortTime.keyAt(i5));
                }
            }
            alarmManagerService.mLog.writeToProto(proto, 1146756268069L);
            FilterStats[] topFilters = new FilterStats[10];
            Comparator<FilterStats> comparator = new Comparator<FilterStats>() { // from class: com.android.server.AlarmManagerService.4
                @Override // java.util.Comparator
                public int compare(FilterStats lhs, FilterStats rhs) {
                    if (lhs.aggregateTime < rhs.aggregateTime) {
                        return 1;
                    }
                    if (lhs.aggregateTime > rhs.aggregateTime) {
                        return -1;
                    }
                    return 0;
                }
            };
            int len = 0;
            int len2 = 0;
            while (len2 < alarmManagerService.mBroadcastStats.size()) {
                ArrayMap<String, BroadcastStats> uidStats = alarmManagerService.mBroadcastStats.valueAt(len2);
                int len3 = len;
                int len4 = 0;
                while (len4 < uidStats.size()) {
                    BroadcastStats bs = uidStats.valueAt(len4);
                    int len5 = len3;
                    int len6 = 0;
                    while (len6 < bs.filterStats.size()) {
                        FilterStats fs = bs.filterStats.valueAt(len6);
                        int pos = len5 > 0 ? Arrays.binarySearch(topFilters, 0, len5, fs, comparator) : 0;
                        if (pos < 0) {
                            pos = (-pos) - 1;
                        }
                        if (pos >= topFilters.length) {
                            users = users2;
                        } else {
                            int copylen = (topFilters.length - pos) - 1;
                            if (copylen > 0) {
                                users = users2;
                                System.arraycopy(topFilters, pos, topFilters, pos + 1, copylen);
                            } else {
                                users = users2;
                            }
                            topFilters[pos] = fs;
                            if (len5 < topFilters.length) {
                                len5++;
                            }
                        }
                        len6++;
                        users2 = users;
                    }
                    len4++;
                    len3 = len5;
                }
                len2++;
                len = len3;
            }
            for (int i6 = 0; i6 < len; i6++) {
                long token2 = proto.start(2246267895846L);
                FilterStats fs2 = topFilters[i6];
                proto.write(1120986464257L, fs2.mBroadcastStats.mUid);
                proto.write(1138166333442L, fs2.mBroadcastStats.mPackageName);
                fs2.writeToProto(proto, 1146756268035L);
                proto.end(token2);
            }
            ArrayList<FilterStats> tmpFilters = new ArrayList<>();
            int iu = 0;
            while (iu < alarmManagerService.mBroadcastStats.size()) {
                ArrayMap<String, BroadcastStats> uidStats2 = alarmManagerService.mBroadcastStats.valueAt(iu);
                int ip = 0;
                while (ip < uidStats2.size()) {
                    long token3 = proto.start(2246267895847L);
                    BroadcastStats bs2 = uidStats2.valueAt(ip);
                    bs2.writeToProto(proto, 1146756268033L);
                    tmpFilters.clear();
                    for (int is = 0; is < bs2.filterStats.size(); is++) {
                        tmpFilters.add(bs2.filterStats.valueAt(is));
                    }
                    Collections.sort(tmpFilters, comparator);
                    Iterator<FilterStats> it7 = tmpFilters.iterator();
                    while (it7.hasNext()) {
                        it7.next().writeToProto(proto, 2246267895810L);
                        tmpFilters = tmpFilters;
                    }
                    proto.end(token3);
                    ip++;
                    tmpFilters = tmpFilters;
                }
                iu++;
                alarmManagerService = this;
            }
        }
        proto.flush();
    }

    private void logBatchesLocked(SimpleDateFormat sdf) {
        ByteArrayOutputStream bs = new ByteArrayOutputStream(2048);
        PrintWriter pw = new PrintWriter(bs);
        long nowRTC = System.currentTimeMillis();
        long nowELAPSED = SystemClock.elapsedRealtime();
        int NZ = this.mAlarmBatches.size();
        int iz = 0;
        while (true) {
            int iz2 = iz;
            if (iz2 < NZ) {
                Batch bz = this.mAlarmBatches.get(iz2);
                pw.append((CharSequence) "Batch ");
                pw.print(iz2);
                pw.append((CharSequence) ": ");
                pw.println(bz);
                dumpAlarmList(pw, bz.alarms, "  ", nowELAPSED, nowRTC, sdf);
                pw.flush();
                Slog.v(TAG, bs.toString());
                bs.reset();
                iz = iz2 + 1;
            } else {
                return;
            }
        }
    }

    private boolean validateConsistencyLocked() {
        return true;
    }

    private Batch findFirstWakeupBatchLocked() {
        int N = this.mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            Batch b = this.mAlarmBatches.get(i);
            if (b.hasWakeups()) {
                return b;
            }
        }
        return null;
    }

    long getNextWakeFromIdleTimeImpl() {
        long j;
        synchronized (this.mLock) {
            j = this.mNextWakeFromIdle != null ? this.mNextWakeFromIdle.whenElapsed : JobStatus.NO_LATEST_RUNTIME;
        }
        return j;
    }

    AlarmManager.AlarmClockInfo getNextAlarmClockImpl(int userId) {
        AlarmManager.AlarmClockInfo alarmClockInfo;
        synchronized (this.mLock) {
            alarmClockInfo = this.mNextAlarmClockForUser.get(userId);
        }
        return alarmClockInfo;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateNextAlarmClockLocked() {
        if (!this.mNextAlarmClockMayChange) {
            return;
        }
        this.mNextAlarmClockMayChange = false;
        SparseArray<AlarmManager.AlarmClockInfo> nextForUser = this.mTmpSparseAlarmClockArray;
        nextForUser.clear();
        int N = this.mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            ArrayList<Alarm> alarms = this.mAlarmBatches.get(i).alarms;
            int M = alarms.size();
            for (int j = 0; j < M; j++) {
                Alarm a = alarms.get(j);
                if (a.alarmClock != null) {
                    int userId = UserHandle.getUserId(a.uid);
                    AlarmManager.AlarmClockInfo current = this.mNextAlarmClockForUser.get(userId);
                    if (nextForUser.get(userId) == null) {
                        nextForUser.put(userId, a.alarmClock);
                    } else if (a.alarmClock.equals(current) && current.getTriggerTime() <= nextForUser.get(userId).getTriggerTime()) {
                        nextForUser.put(userId, current);
                    }
                }
            }
        }
        int NN = nextForUser.size();
        for (int i2 = 0; i2 < NN; i2++) {
            AlarmManager.AlarmClockInfo newAlarm = nextForUser.valueAt(i2);
            int userId2 = nextForUser.keyAt(i2);
            AlarmManager.AlarmClockInfo currentAlarm = this.mNextAlarmClockForUser.get(userId2);
            if (!newAlarm.equals(currentAlarm)) {
                updateNextAlarmInfoForUserLocked(userId2, newAlarm);
            }
        }
        int NNN = this.mNextAlarmClockForUser.size();
        for (int i3 = NNN - 1; i3 >= 0; i3--) {
            int userId3 = this.mNextAlarmClockForUser.keyAt(i3);
            if (nextForUser.get(userId3) == null) {
                updateNextAlarmInfoForUserLocked(userId3, null);
            }
        }
    }

    private void updateNextAlarmInfoForUserLocked(int userId, AlarmManager.AlarmClockInfo alarmClock) {
        if (alarmClock != null) {
            this.mNextAlarmClockForUser.put(userId, alarmClock);
        } else {
            this.mNextAlarmClockForUser.remove(userId);
        }
        this.mPendingSendNextAlarmClockChangedForUser.put(userId, true);
        this.mHandler.removeMessages(2);
        this.mHandler.sendEmptyMessage(2);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendNextAlarmClockChanged() {
        int userId;
        SparseArray<AlarmManager.AlarmClockInfo> pendingUsers = this.mHandlerSparseAlarmClockArray;
        pendingUsers.clear();
        synchronized (this.mLock) {
            int N = this.mPendingSendNextAlarmClockChangedForUser.size();
            userId = 0;
            for (int i = 0; i < N; i++) {
                int userId2 = this.mPendingSendNextAlarmClockChangedForUser.keyAt(i);
                pendingUsers.append(userId2, this.mNextAlarmClockForUser.get(userId2));
            }
            this.mPendingSendNextAlarmClockChangedForUser.clear();
        }
        int N2 = pendingUsers.size();
        while (true) {
            int i2 = userId;
            if (i2 >= N2) {
                return;
            }
            int userId3 = pendingUsers.keyAt(i2);
            AlarmManager.AlarmClockInfo alarmClock = pendingUsers.valueAt(i2);
            Settings.System.putStringForUser(getContext().getContentResolver(), "next_alarm_formatted", formatNextAlarm(getContext(), alarmClock, userId3), userId3);
            getContext().sendBroadcastAsUser(NEXT_ALARM_CLOCK_CHANGED_INTENT, new UserHandle(userId3));
            userId = i2 + 1;
        }
    }

    private static String formatNextAlarm(Context context, AlarmManager.AlarmClockInfo info, int userId) {
        String skeleton = DateFormat.is24HourFormat(context, userId) ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return info == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    void rescheduleKernelAlarmsLocked() {
        long nextNonWakeup = 0;
        if (this.mAlarmBatches.size() > 0) {
            Batch firstWakeup = findFirstWakeupBatchLocked();
            Batch firstBatch = this.mAlarmBatches.get(0);
            if (firstWakeup != null) {
                this.mNextWakeup = firstWakeup.start;
                this.mLastWakeupSet = SystemClock.elapsedRealtime();
                setLocked(2, firstWakeup.start);
            }
            if (firstBatch != firstWakeup) {
                nextNonWakeup = firstBatch.start;
            }
        }
        if (this.mPendingNonWakeupAlarms.size() > 0 && (nextNonWakeup == 0 || this.mNextNonWakeupDeliveryTime < nextNonWakeup)) {
            nextNonWakeup = this.mNextNonWakeupDeliveryTime;
        }
        if (nextNonWakeup != 0) {
            this.mNextNonWakeup = nextNonWakeup;
            setLocked(3, nextNonWakeup);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeLocked(final PendingIntent operation, final IAlarmListener directReceiver) {
        if (operation == null && directReceiver == null) {
            return;
        }
        boolean didRemove = false;
        Predicate<Alarm> whichAlarms = new Predicate() { // from class: com.android.server.-$$Lambda$AlarmManagerService$ZVedZIeWdB3G6AGM0_-9P_GEO24
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean matches;
                matches = ((AlarmManagerService.Alarm) obj).matches(operation, directReceiver);
                return matches;
            }
        };
        for (int i = this.mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = this.mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                this.mAlarmBatches.remove(i);
            }
        }
        for (int i2 = this.mPendingWhileIdleAlarms.size() - 1; i2 >= 0; i2--) {
            if (this.mPendingWhileIdleAlarms.get(i2).matches(operation, directReceiver)) {
                this.mPendingWhileIdleAlarms.remove(i2);
            }
        }
        for (int i3 = this.mPendingBackgroundAlarms.size() - 1; i3 >= 0; i3--) {
            ArrayList<Alarm> alarmsForUid = this.mPendingBackgroundAlarms.valueAt(i3);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                if (alarmsForUid.get(j).matches(operation, directReceiver)) {
                    alarmsForUid.remove(j);
                }
            }
            int j2 = alarmsForUid.size();
            if (j2 == 0) {
                this.mPendingBackgroundAlarms.removeAt(i3);
            }
        }
        if (didRemove) {
            boolean restorePending = false;
            if (this.mPendingIdleUntil != null && this.mPendingIdleUntil.matches(operation, directReceiver)) {
                this.mPendingIdleUntil = null;
                restorePending = true;
            }
            if (this.mNextWakeFromIdle != null && this.mNextWakeFromIdle.matches(operation, directReceiver)) {
                this.mNextWakeFromIdle = null;
            }
            rebatchAllAlarmsLocked(true);
            if (restorePending) {
                restorePendingWhileIdleAlarmsLocked();
            }
            updateNextAlarmClockLocked();
        }
    }

    void removeLocked(final int uid) {
        if (uid == 1000) {
            Slog.wtf(TAG, "removeLocked: Shouldn't for UID=" + uid);
            return;
        }
        boolean didRemove = false;
        Predicate<Alarm> whichAlarms = new Predicate() { // from class: com.android.server.-$$Lambda$AlarmManagerService$qehVSjTLWvtJYPGgKh2mkJ6ePnk
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return AlarmManagerService.lambda$removeLocked$1(uid, (AlarmManagerService.Alarm) obj);
            }
        };
        for (int i = this.mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = this.mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                this.mAlarmBatches.remove(i);
            }
        }
        for (int i2 = this.mPendingWhileIdleAlarms.size() - 1; i2 >= 0; i2--) {
            Alarm a = this.mPendingWhileIdleAlarms.get(i2);
            if (a.uid == uid) {
                this.mPendingWhileIdleAlarms.remove(i2);
            }
        }
        for (int i3 = this.mPendingBackgroundAlarms.size() - 1; i3 >= 0; i3--) {
            ArrayList<Alarm> alarmsForUid = this.mPendingBackgroundAlarms.valueAt(i3);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                if (alarmsForUid.get(j).uid == uid) {
                    alarmsForUid.remove(j);
                }
            }
            int j2 = alarmsForUid.size();
            if (j2 == 0) {
                this.mPendingBackgroundAlarms.removeAt(i3);
            }
        }
        if (didRemove) {
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$removeLocked$1(int uid, Alarm a) {
        return a.uid == uid;
    }

    void removeLocked(final String packageName) {
        if (packageName == null) {
            return;
        }
        boolean didRemove = false;
        Predicate<Alarm> whichAlarms = new Predicate() { // from class: com.android.server.-$$Lambda$AlarmManagerService$oMxEf0J1UujgwLNvXJjew5Pq3f0
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean matches;
                matches = ((AlarmManagerService.Alarm) obj).matches(packageName);
                return matches;
            }
        };
        boolean oldHasTick = haveBatchesTimeTickAlarm(this.mAlarmBatches);
        for (int i = this.mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = this.mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                this.mAlarmBatches.remove(i);
            }
        }
        boolean newHasTick = haveBatchesTimeTickAlarm(this.mAlarmBatches);
        if (oldHasTick != newHasTick) {
            Slog.wtf(TAG, "removeLocked: hasTick changed from " + oldHasTick + " to " + newHasTick);
        }
        for (int i2 = this.mPendingWhileIdleAlarms.size() - 1; i2 >= 0; i2--) {
            Alarm a = this.mPendingWhileIdleAlarms.get(i2);
            if (a.matches(packageName)) {
                this.mPendingWhileIdleAlarms.remove(i2);
            }
        }
        for (int i3 = this.mPendingBackgroundAlarms.size() - 1; i3 >= 0; i3--) {
            ArrayList<Alarm> alarmsForUid = this.mPendingBackgroundAlarms.valueAt(i3);
            for (int j = alarmsForUid.size() - 1; j >= 0; j--) {
                if (alarmsForUid.get(j).matches(packageName)) {
                    alarmsForUid.remove(j);
                }
            }
            int j2 = alarmsForUid.size();
            if (j2 == 0) {
                this.mPendingBackgroundAlarms.removeAt(i3);
            }
        }
        if (didRemove) {
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeForStoppedLocked(final int uid) {
        if (uid == 1000) {
            Slog.wtf(TAG, "removeForStoppedLocked: Shouldn't for UID=" + uid);
            return;
        }
        boolean didRemove = false;
        Predicate<Alarm> whichAlarms = new Predicate() { // from class: com.android.server.-$$Lambda$AlarmManagerService$lzZOWJB2te9UTLsLWoZ6M8xouQQ
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return AlarmManagerService.lambda$removeForStoppedLocked$3(uid, (AlarmManagerService.Alarm) obj);
            }
        };
        for (int i = this.mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = this.mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                this.mAlarmBatches.remove(i);
            }
        }
        for (int i2 = this.mPendingWhileIdleAlarms.size() - 1; i2 >= 0; i2--) {
            Alarm a = this.mPendingWhileIdleAlarms.get(i2);
            if (a.uid == uid) {
                this.mPendingWhileIdleAlarms.remove(i2);
            }
        }
        for (int i3 = this.mPendingBackgroundAlarms.size() - 1; i3 >= 0; i3--) {
            if (this.mPendingBackgroundAlarms.keyAt(i3) == uid) {
                this.mPendingBackgroundAlarms.removeAt(i3);
            }
        }
        if (didRemove) {
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$removeForStoppedLocked$3(int uid, Alarm a) {
        try {
            if (a.uid == uid) {
                if (ActivityManager.getService().isAppStartModeDisabled(uid, a.packageName)) {
                    return true;
                }
                return false;
            }
            return false;
        } catch (RemoteException e) {
            return false;
        }
    }

    void removeUserLocked(final int userHandle) {
        if (userHandle == 0) {
            Slog.wtf(TAG, "removeForStoppedLocked: Shouldn't for user=" + userHandle);
            return;
        }
        boolean didRemove = false;
        Predicate<Alarm> whichAlarms = new Predicate() { // from class: com.android.server.-$$Lambda$AlarmManagerService$nhEd_CDoc7mzdNLRwGUhwl9TaGk
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return AlarmManagerService.lambda$removeUserLocked$4(userHandle, (AlarmManagerService.Alarm) obj);
            }
        };
        for (int i = this.mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = this.mAlarmBatches.get(i);
            didRemove |= b.remove(whichAlarms);
            if (b.size() == 0) {
                this.mAlarmBatches.remove(i);
            }
        }
        for (int i2 = this.mPendingWhileIdleAlarms.size() - 1; i2 >= 0; i2--) {
            if (UserHandle.getUserId(this.mPendingWhileIdleAlarms.get(i2).creatorUid) == userHandle) {
                this.mPendingWhileIdleAlarms.remove(i2);
            }
        }
        for (int i3 = this.mPendingBackgroundAlarms.size() - 1; i3 >= 0; i3--) {
            if (UserHandle.getUserId(this.mPendingBackgroundAlarms.keyAt(i3)) == userHandle) {
                this.mPendingBackgroundAlarms.removeAt(i3);
            }
        }
        for (int i4 = this.mLastAllowWhileIdleDispatch.size() - 1; i4 >= 0; i4--) {
            if (UserHandle.getUserId(this.mLastAllowWhileIdleDispatch.keyAt(i4)) == userHandle) {
                this.mLastAllowWhileIdleDispatch.removeAt(i4);
            }
        }
        if (didRemove) {
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$removeUserLocked$4(int userHandle, Alarm a) {
        return UserHandle.getUserId(a.creatorUid) == userHandle;
    }

    void interactiveStateChangedLocked(boolean interactive) {
        if (this.mInteractive != interactive) {
            this.mInteractive = interactive;
            long nowELAPSED = SystemClock.elapsedRealtime();
            if (interactive) {
                if (this.mPendingNonWakeupAlarms.size() > 0) {
                    long thisDelayTime = nowELAPSED - this.mStartCurrentDelayTime;
                    this.mTotalDelayTime += thisDelayTime;
                    if (this.mMaxDelayTime < thisDelayTime) {
                        this.mMaxDelayTime = thisDelayTime;
                    }
                    deliverAlarmsLocked(this.mPendingNonWakeupAlarms, nowELAPSED);
                    this.mPendingNonWakeupAlarms.clear();
                }
                if (this.mNonInteractiveStartTime > 0) {
                    long dur = nowELAPSED - this.mNonInteractiveStartTime;
                    if (dur > this.mNonInteractiveTime) {
                        this.mNonInteractiveTime = dur;
                        return;
                    }
                    return;
                }
                return;
            }
            this.mNonInteractiveStartTime = nowELAPSED;
        }
    }

    boolean lookForPackageLocked(String packageName) {
        for (int i = 0; i < this.mAlarmBatches.size(); i++) {
            Batch b = this.mAlarmBatches.get(i);
            if (b.hasPackage(packageName)) {
                return true;
            }
        }
        for (int i2 = 0; i2 < this.mPendingWhileIdleAlarms.size(); i2++) {
            Alarm a = this.mPendingWhileIdleAlarms.get(i2);
            if (a.matches(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void setLocked(int type, long when) {
        long alarmSeconds;
        long alarmSeconds2;
        if (this.mNativeData != 0) {
            if (when >= 0) {
                long alarmSeconds3 = when / 1000;
                alarmSeconds = 1000 * (when % 1000) * 1000;
                alarmSeconds2 = alarmSeconds3;
            } else {
                alarmSeconds2 = 0;
                alarmSeconds = 0;
            }
            int result = set(this.mNativeData, type, alarmSeconds2, alarmSeconds);
            if (result != 0) {
                long nowElapsed = SystemClock.elapsedRealtime();
                Slog.wtf(TAG, "Unable to set kernel alarm, now=" + nowElapsed + " type=" + type + " when=" + when + " @ (" + alarmSeconds2 + "," + alarmSeconds + "), ret = " + result + " = " + Os.strerror(result));
                return;
            }
            return;
        }
        Message msg = Message.obtain();
        msg.what = 1;
        this.mHandler.removeMessages(1);
        this.mHandler.sendMessageAtTime(msg, when);
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list, String prefix, String label, long nowELAPSED, long nowRTC, SimpleDateFormat sdf) {
        int i = list.size() - 1;
        while (true) {
            int i2 = i;
            if (i2 < 0) {
                return;
            }
            Alarm a = list.get(i2);
            pw.print(prefix);
            pw.print(label);
            pw.print(" #");
            pw.print(i2);
            pw.print(": ");
            pw.println(a);
            a.dump(pw, prefix + "  ", nowELAPSED, nowRTC, sdf);
            i = i2 + (-1);
        }
    }

    private static final String labelForType(int type) {
        switch (type) {
            case 0:
                return "RTC_WAKEUP";
            case 1:
                return "RTC";
            case 2:
                return "ELAPSED_WAKEUP";
            case 3:
                return "ELAPSED";
            default:
                return "--unknown--";
        }
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list, String prefix, long nowELAPSED, long nowRTC, SimpleDateFormat sdf) {
        int i = list.size() - 1;
        while (true) {
            int i2 = i;
            if (i2 < 0) {
                return;
            }
            Alarm a = list.get(i2);
            String label = labelForType(a.type);
            pw.print(prefix);
            pw.print(label);
            pw.print(" #");
            pw.print(i2);
            pw.print(": ");
            pw.println(a);
            a.dump(pw, prefix + "  ", nowELAPSED, nowRTC, sdf);
            i = i2 + (-1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isBackgroundRestricted(Alarm alarm) {
        boolean exemptOnBatterySaver = (alarm.flags & 4) != 0;
        if (alarm.alarmClock != null) {
            return false;
        }
        if (alarm.operation != null) {
            if (alarm.operation.isActivity()) {
                return false;
            }
            if (alarm.operation.isForegroundService()) {
                exemptOnBatterySaver = true;
            }
        }
        String sourcePackage = alarm.sourcePackage;
        int sourceUid = alarm.creatorUid;
        return this.mAppStateTracker != null && this.mAppStateTracker.areAlarmsRestricted(sourceUid, sourcePackage, exemptOnBatterySaver);
    }

    private long getWhileIdleMinIntervalLocked(int uid) {
        boolean ebs = false;
        boolean dozing = this.mPendingIdleUntil != null;
        if (this.mAppStateTracker != null && this.mAppStateTracker.isForceAllAppsStandbyEnabled()) {
            ebs = true;
        }
        if (!dozing && !ebs) {
            return this.mConstants.ALLOW_WHILE_IDLE_SHORT_TIME;
        }
        if (dozing) {
            return this.mConstants.ALLOW_WHILE_IDLE_LONG_TIME;
        }
        if (this.mUseAllowWhileIdleShortTime.get(uid)) {
            return this.mConstants.ALLOW_WHILE_IDLE_SHORT_TIME;
        }
        return this.mConstants.ALLOW_WHILE_IDLE_LONG_TIME;
    }

    /* JADX WARN: Type inference failed for: r12v0 */
    /* JADX WARN: Type inference failed for: r12v1, types: [int, boolean] */
    /* JADX WARN: Type inference failed for: r12v3 */
    boolean triggerAlarmsLocked(ArrayList<Alarm> triggerList, long nowELAPSED, long nowRTC) {
        int i;
        Alarm alarm;
        Batch batch;
        int N;
        boolean z;
        AlarmManagerService alarmManagerService;
        boolean z2;
        AlarmManagerService alarmManagerService2 = this;
        ArrayList<Alarm> arrayList = triggerList;
        boolean z3 = false;
        boolean hasWakeup = false;
        while (true) {
            ?? r12 = 1;
            if (alarmManagerService2.mAlarmBatches.size() <= 0) {
                break;
            }
            Batch batch2 = alarmManagerService2.mAlarmBatches.get(z3 ? 1 : 0);
            if (batch2.start > nowELAPSED) {
                break;
            }
            alarmManagerService2.mAlarmBatches.remove(z3 ? 1 : 0);
            int N2 = batch2.size();
            boolean hasWakeup2 = hasWakeup;
            int i2 = z3 ? 1 : 0;
            while (true) {
                int i3 = i2;
                if (i3 < N2) {
                    Alarm alarm2 = batch2.get(i3);
                    if ((alarm2.flags & 4) != 0) {
                        long lastTime = alarmManagerService2.mLastAllowWhileIdleDispatch.get(alarm2.creatorUid, -1L);
                        long minTime = alarmManagerService2.getWhileIdleMinIntervalLocked(alarm2.creatorUid) + lastTime;
                        if (lastTime >= 0 && nowELAPSED < minTime) {
                            alarm2.whenElapsed = minTime;
                            alarm2.expectedWhenElapsed = minTime;
                            if (alarm2.maxWhenElapsed < minTime) {
                                alarm2.maxWhenElapsed = minTime;
                            }
                            alarm2.expectedMaxWhenElapsed = alarm2.maxWhenElapsed;
                            alarmManagerService2.setImplLocked(alarm2, r12, z3);
                            i = i3;
                            batch = batch2;
                            N = N2;
                            z2 = r12;
                            z = z3 ? 1 : 0;
                            alarmManagerService = alarmManagerService2;
                            i2 = i + 1;
                            arrayList = triggerList;
                            alarmManagerService2 = alarmManagerService;
                            r12 = z2;
                            batch2 = batch;
                            z3 = z;
                            N2 = N;
                        }
                    }
                    if (alarmManagerService2.isBackgroundRestricted(alarm2)) {
                        ArrayList<Alarm> alarmsForUid = alarmManagerService2.mPendingBackgroundAlarms.get(alarm2.creatorUid);
                        if (alarmsForUid == null) {
                            alarmsForUid = new ArrayList<>();
                            alarmManagerService2.mPendingBackgroundAlarms.put(alarm2.creatorUid, alarmsForUid);
                        }
                        alarmsForUid.add(alarm2);
                        i = i3;
                        batch = batch2;
                        N = N2;
                        z2 = r12;
                        z = z3 ? 1 : 0;
                        alarmManagerService = alarmManagerService2;
                        i2 = i + 1;
                        arrayList = triggerList;
                        alarmManagerService2 = alarmManagerService;
                        r12 = z2;
                        batch2 = batch;
                        z3 = z;
                        N2 = N;
                    } else {
                        alarm2.count = r12;
                        arrayList.add(alarm2);
                        if ((alarm2.flags & 2) != 0) {
                            EventLogTags.writeDeviceIdleWakeFromIdle(alarmManagerService2.mPendingIdleUntil != null ? r12 : z3 ? 1 : 0, alarm2.statsTag);
                        }
                        if (alarmManagerService2.mPendingIdleUntil == alarm2) {
                            alarmManagerService2.mPendingIdleUntil = null;
                            alarmManagerService2.rebatchAllAlarmsLocked(z3);
                            restorePendingWhileIdleAlarmsLocked();
                        }
                        if (alarmManagerService2.mNextWakeFromIdle == alarm2) {
                            alarmManagerService2.mNextWakeFromIdle = null;
                            alarmManagerService2.rebatchAllAlarmsLocked(z3);
                        }
                        if (alarm2.repeatInterval > 0) {
                            alarm2.count = (int) (alarm2.count + ((nowELAPSED - alarm2.expectedWhenElapsed) / alarm2.repeatInterval));
                            long delta = alarm2.count * alarm2.repeatInterval;
                            long nextElapsed = alarm2.whenElapsed + delta;
                            int i4 = alarm2.type;
                            long j = alarm2.when + delta;
                            long j2 = alarm2.windowLength;
                            long maxTriggerTime = maxTriggerTime(nowELAPSED, nextElapsed, alarm2.repeatInterval);
                            long j3 = alarm2.repeatInterval;
                            PendingIntent pendingIntent = alarm2.operation;
                            int i5 = alarm2.flags;
                            WorkSource workSource = alarm2.workSource;
                            AlarmManager.AlarmClockInfo alarmClockInfo = alarm2.alarmClock;
                            int i6 = alarm2.uid;
                            String str = alarm2.packageName;
                            i = i3;
                            alarm = alarm2;
                            batch = batch2;
                            N = N2;
                            z = z3 ? 1 : 0;
                            alarmManagerService2.setImplLocked(i4, j, nextElapsed, j2, maxTriggerTime, j3, pendingIntent, null, null, i5, true, workSource, alarmClockInfo, i6, str);
                        } else {
                            i = i3;
                            alarm = alarm2;
                            batch = batch2;
                            N = N2;
                            z = z3 ? 1 : 0;
                        }
                        Alarm alarm3 = alarm;
                        if (alarm3.wakeup) {
                            hasWakeup2 = true;
                        }
                        if (alarm3.alarmClock == null) {
                            alarmManagerService = this;
                            z2 = true;
                        } else {
                            alarmManagerService = this;
                            z2 = true;
                            alarmManagerService.mNextAlarmClockMayChange = true;
                        }
                        i2 = i + 1;
                        arrayList = triggerList;
                        alarmManagerService2 = alarmManagerService;
                        r12 = z2;
                        batch2 = batch;
                        z3 = z;
                        N2 = N;
                    }
                }
            }
            Object[] objArr = z3 ? 1 : 0;
            arrayList = triggerList;
            hasWakeup = hasWakeup2;
        }
        AlarmManagerService alarmManagerService3 = alarmManagerService2;
        alarmManagerService3.mCurrentSeq++;
        calculateDeliveryPriorities(triggerList);
        Collections.sort(triggerList, alarmManagerService3.mAlarmDispatchComparator);
        return hasWakeup;
    }

    /* loaded from: classes.dex */
    public static class IncreasingTimeOrder implements Comparator<Alarm> {
        @Override // java.util.Comparator
        public int compare(Alarm a1, Alarm a2) {
            long when1 = a1.whenElapsed;
            long when2 = a2.whenElapsed;
            if (when1 > when2) {
                return 1;
            }
            if (when1 < when2) {
                return -1;
            }
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class Alarm {
        public final AlarmManager.AlarmClockInfo alarmClock;
        public int count;
        public final int creatorUid;
        public long expectedMaxWhenElapsed;
        public long expectedWhenElapsed;
        public final int flags;
        public final IAlarmListener listener;
        public final String listenerTag;
        public long maxWhenElapsed;
        public final PendingIntent operation;
        public final long origWhen;
        public final String packageName;
        public PriorityClass priorityClass;
        public long repeatInterval;
        public final String sourcePackage;
        public final String statsTag;
        public final int type;
        public final int uid;
        public final boolean wakeup;
        public long when;
        public long whenElapsed;
        public long windowLength;
        public final WorkSource workSource;

        public Alarm(int _type, long _when, long _whenElapsed, long _windowLength, long _maxWhen, long _interval, PendingIntent _op, IAlarmListener _rec, String _listenerTag, WorkSource _ws, int _flags, AlarmManager.AlarmClockInfo _info, int _uid, String _pkgName) {
            this.type = _type;
            this.origWhen = _when;
            this.wakeup = _type == 2 || _type == 0;
            this.when = _when;
            this.whenElapsed = _whenElapsed;
            this.expectedWhenElapsed = _whenElapsed;
            this.windowLength = _windowLength;
            long clampPositive = AlarmManagerService.clampPositive(_maxWhen);
            this.expectedMaxWhenElapsed = clampPositive;
            this.maxWhenElapsed = clampPositive;
            this.repeatInterval = _interval;
            this.operation = _op;
            this.listener = _rec;
            this.listenerTag = _listenerTag;
            this.statsTag = makeTag(_op, _listenerTag, _type);
            this.workSource = _ws;
            this.flags = _flags;
            this.alarmClock = _info;
            this.uid = _uid;
            this.packageName = _pkgName;
            this.sourcePackage = this.operation != null ? this.operation.getCreatorPackage() : this.packageName;
            this.creatorUid = this.operation != null ? this.operation.getCreatorUid() : this.uid;
        }

        public static String makeTag(PendingIntent pi, String tag, int type) {
            String alarmString = (type == 2 || type == 0) ? "*walarm*:" : "*alarm*:";
            if (pi != null) {
                return pi.getTag(alarmString);
            }
            return alarmString + tag;
        }

        public WakeupEvent makeWakeupEvent(long nowRTC) {
            String str;
            int i = this.creatorUid;
            if (this.operation != null) {
                str = this.operation.getIntent().getAction();
            } else {
                str = "<listener>:" + this.listenerTag;
            }
            return new WakeupEvent(nowRTC, i, str);
        }

        public boolean matches(PendingIntent pi, IAlarmListener rec) {
            if (this.operation != null) {
                return this.operation.equals(pi);
            }
            return rec != null && this.listener.asBinder().equals(rec.asBinder());
        }

        public boolean matches(String packageName) {
            return packageName.equals(this.sourcePackage);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Alarm{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" type ");
            sb.append(this.type);
            sb.append(" when ");
            sb.append(this.when);
            sb.append(" ");
            sb.append(this.sourcePackage);
            sb.append('}');
            return sb.toString();
        }

        public void dump(PrintWriter pw, String prefix, long nowELAPSED, long nowRTC, SimpleDateFormat sdf) {
            boolean z = true;
            if (this.type != 1 && this.type != 0) {
                z = false;
            }
            boolean isRtc = z;
            pw.print(prefix);
            pw.print("tag=");
            pw.println(this.statsTag);
            pw.print(prefix);
            pw.print("type=");
            pw.print(this.type);
            pw.print(" expectedWhenElapsed=");
            TimeUtils.formatDuration(this.expectedWhenElapsed, nowELAPSED, pw);
            pw.print(" expectedMaxWhenElapsed=");
            TimeUtils.formatDuration(this.expectedMaxWhenElapsed, nowELAPSED, pw);
            pw.print(" whenElapsed=");
            TimeUtils.formatDuration(this.whenElapsed, nowELAPSED, pw);
            pw.print(" maxWhenElapsed=");
            TimeUtils.formatDuration(this.maxWhenElapsed, nowELAPSED, pw);
            pw.print(" when=");
            if (isRtc) {
                pw.print(sdf.format(new Date(this.when)));
            } else {
                TimeUtils.formatDuration(this.when, nowELAPSED, pw);
            }
            pw.println();
            pw.print(prefix);
            pw.print("window=");
            TimeUtils.formatDuration(this.windowLength, pw);
            pw.print(" repeatInterval=");
            pw.print(this.repeatInterval);
            pw.print(" count=");
            pw.print(this.count);
            pw.print(" flags=0x");
            pw.println(Integer.toHexString(this.flags));
            if (this.alarmClock != null) {
                pw.print(prefix);
                pw.println("Alarm clock:");
                pw.print(prefix);
                pw.print("  triggerTime=");
                pw.println(sdf.format(new Date(this.alarmClock.getTriggerTime())));
                pw.print(prefix);
                pw.print("  showIntent=");
                pw.println(this.alarmClock.getShowIntent());
            }
            pw.print(prefix);
            pw.print("operation=");
            pw.println(this.operation);
            if (this.listener != null) {
                pw.print(prefix);
                pw.print("listener=");
                pw.println(this.listener.asBinder());
            }
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId, long nowElapsed, long nowRTC) {
            long token = proto.start(fieldId);
            proto.write(1138166333441L, this.statsTag);
            proto.write(1159641169922L, this.type);
            proto.write(1112396529667L, this.whenElapsed - nowElapsed);
            proto.write(1112396529668L, this.windowLength);
            proto.write(1112396529669L, this.repeatInterval);
            proto.write(1120986464262L, this.count);
            proto.write(1120986464263L, this.flags);
            if (this.alarmClock != null) {
                this.alarmClock.writeToProto(proto, 1146756268040L);
            }
            if (this.operation != null) {
                this.operation.writeToProto(proto, 1146756268041L);
            }
            if (this.listener != null) {
                proto.write(1138166333450L, this.listener.asBinder().toString());
            }
            proto.end(token);
        }
    }

    void recordWakeupAlarms(ArrayList<Batch> batches, long nowELAPSED, long nowRTC) {
        int numBatches = batches.size();
        for (int nextBatch = 0; nextBatch < numBatches; nextBatch++) {
            Batch b = batches.get(nextBatch);
            if (b.start <= nowELAPSED) {
                int numAlarms = b.alarms.size();
                for (int nextAlarm = 0; nextAlarm < numAlarms; nextAlarm++) {
                    Alarm a = b.alarms.get(nextAlarm);
                    this.mRecentWakeups.add(a.makeWakeupEvent(nowRTC));
                }
            } else {
                return;
            }
        }
    }

    long currentNonWakeupFuzzLocked(long nowELAPSED) {
        long timeSinceOn = nowELAPSED - this.mNonInteractiveStartTime;
        if (timeSinceOn < BackupAgentTimeoutParameters.DEFAULT_FULL_BACKUP_AGENT_TIMEOUT_MILLIS) {
            return JobStatus.DEFAULT_TRIGGER_MAX_DELAY;
        }
        if (timeSinceOn < BackupAgentTimeoutParameters.DEFAULT_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS) {
            return 900000L;
        }
        return 3600000L;
    }

    static int fuzzForDuration(long duration) {
        if (duration < 900000) {
            return (int) duration;
        }
        if (duration < 5400000) {
            return 900000;
        }
        return 1800000;
    }

    boolean checkAllowNonWakeupDelayLocked(long nowELAPSED) {
        if (!this.mInteractive && this.mLastAlarmDeliveryTime > 0) {
            if (this.mPendingNonWakeupAlarms.size() <= 0 || this.mNextNonWakeupDeliveryTime >= nowELAPSED) {
                long timeSinceLast = nowELAPSED - this.mLastAlarmDeliveryTime;
                return timeSinceLast <= currentNonWakeupFuzzLocked(nowELAPSED);
            }
            return false;
        }
        return false;
    }

    void deliverAlarmsLocked(ArrayList<Alarm> triggerList, long nowELAPSED) {
        this.mLastAlarmDeliveryTime = nowELAPSED;
        for (int i = 0; i < triggerList.size(); i++) {
            Alarm alarm = triggerList.get(i);
            boolean allowWhileIdle = (alarm.flags & 4) != 0;
            if (alarm.wakeup) {
                Trace.traceBegin(131072L, "Dispatch wakeup alarm to " + alarm.packageName);
            } else {
                Trace.traceBegin(131072L, "Dispatch non-wakeup alarm to " + alarm.packageName);
            }
            try {
                ActivityManager.noteAlarmStart(alarm.operation, alarm.workSource, alarm.uid, alarm.statsTag);
                this.mDeliveryTracker.deliverLocked(alarm, nowELAPSED, allowWhileIdle);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure sending alarm.", e);
            }
            Trace.traceEnd(131072L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isExemptFromAppStandby(Alarm a) {
        return (a.alarmClock == null && !UserHandle.isCore(a.creatorUid) && (a.flags & 8) == 0) ? false : true;
    }

    /* loaded from: classes.dex */
    private class AlarmThread extends Thread {
        public AlarmThread() {
            super(AlarmManagerService.TAG);
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            long lastTimeChangeClockTime;
            long expectedClockTime;
            ArrayList<Alarm> triggerList = new ArrayList<>();
            while (true) {
                ArrayList<Alarm> triggerList2 = triggerList;
                int result = AlarmManagerService.this.waitForAlarm(AlarmManagerService.this.mNativeData);
                long nowRTC = System.currentTimeMillis();
                long nowELAPSED = SystemClock.elapsedRealtime();
                synchronized (AlarmManagerService.this.mLock) {
                    AlarmManagerService.this.mLastWakeup = nowELAPSED;
                }
                triggerList2.clear();
                if ((result & 65536) != 0) {
                    synchronized (AlarmManagerService.this.mLock) {
                        lastTimeChangeClockTime = AlarmManagerService.this.mLastTimeChangeClockTime;
                        expectedClockTime = (nowELAPSED - AlarmManagerService.this.mLastTimeChangeRealtime) + lastTimeChangeClockTime;
                    }
                    if (lastTimeChangeClockTime == 0 || nowRTC < expectedClockTime - 1000 || nowRTC > 1000 + expectedClockTime) {
                        AlarmManagerService.this.removeImpl(AlarmManagerService.this.mTimeTickSender);
                        AlarmManagerService.this.removeImpl(AlarmManagerService.this.mDateChangeSender);
                        AlarmManagerService.this.rebatchAllAlarms();
                        AlarmManagerService.this.mClockReceiver.scheduleTimeTickEvent();
                        AlarmManagerService.this.mClockReceiver.scheduleDateChangedEvent();
                        synchronized (AlarmManagerService.this.mLock) {
                            AlarmManagerService.this.mNumTimeChanged++;
                            AlarmManagerService.this.mLastTimeChangeClockTime = nowRTC;
                            AlarmManagerService.this.mLastTimeChangeRealtime = nowELAPSED;
                        }
                        Intent intent = new Intent("android.intent.action.TIME_SET");
                        intent.addFlags(622854144);
                        AlarmManagerService.this.getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
                        result |= 5;
                    }
                }
                if (result != 65536) {
                    synchronized (AlarmManagerService.this.mLock) {
                        AlarmManagerService.this.mLastTrigger = nowELAPSED;
                        boolean hasWakeup = AlarmManagerService.this.triggerAlarmsLocked(triggerList2, nowELAPSED, nowRTC);
                        if (!hasWakeup && AlarmManagerService.this.checkAllowNonWakeupDelayLocked(nowELAPSED)) {
                            if (AlarmManagerService.this.mPendingNonWakeupAlarms.size() == 0) {
                                AlarmManagerService.this.mStartCurrentDelayTime = nowELAPSED;
                                AlarmManagerService.this.mNextNonWakeupDeliveryTime = ((AlarmManagerService.this.currentNonWakeupFuzzLocked(nowELAPSED) * 3) / 2) + nowELAPSED;
                            }
                            AlarmManagerService.this.mPendingNonWakeupAlarms.addAll(triggerList2);
                            AlarmManagerService.this.mNumDelayedAlarms += triggerList2.size();
                            AlarmManagerService.this.rescheduleKernelAlarmsLocked();
                            AlarmManagerService.this.updateNextAlarmClockLocked();
                        } else {
                            if (AlarmManagerService.this.mPendingNonWakeupAlarms.size() > 0) {
                                AlarmManagerService.this.calculateDeliveryPriorities(AlarmManagerService.this.mPendingNonWakeupAlarms);
                                triggerList2.addAll(AlarmManagerService.this.mPendingNonWakeupAlarms);
                                Collections.sort(triggerList2, AlarmManagerService.this.mAlarmDispatchComparator);
                                long thisDelayTime = nowELAPSED - AlarmManagerService.this.mStartCurrentDelayTime;
                                AlarmManagerService.this.mTotalDelayTime += thisDelayTime;
                                if (AlarmManagerService.this.mMaxDelayTime < thisDelayTime) {
                                    AlarmManagerService.this.mMaxDelayTime = thisDelayTime;
                                }
                                AlarmManagerService.this.mPendingNonWakeupAlarms.clear();
                            }
                            ArraySet<Pair<String, Integer>> triggerPackages = new ArraySet<>();
                            for (int i = 0; i < triggerList2.size(); i++) {
                                Alarm a = triggerList2.get(i);
                                if (!AlarmManagerService.this.isExemptFromAppStandby(a)) {
                                    triggerPackages.add(Pair.create(a.sourcePackage, Integer.valueOf(UserHandle.getUserId(a.creatorUid))));
                                }
                            }
                            AlarmManagerService.this.deliverAlarmsLocked(triggerList2, nowELAPSED);
                            AlarmManagerService.this.reorderAlarmsBasedOnStandbyBuckets(triggerPackages);
                            AlarmManagerService.this.rescheduleKernelAlarmsLocked();
                            AlarmManagerService.this.updateNextAlarmClockLocked();
                        }
                    }
                } else {
                    synchronized (AlarmManagerService.this.mLock) {
                        AlarmManagerService.this.rescheduleKernelAlarmsLocked();
                    }
                }
                triggerList = triggerList2;
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:16:0x0023 A[Catch: Exception -> 0x0048, TryCatch #0 {Exception -> 0x0048, blocks: (B:3:0x0001, B:7:0x0008, B:9:0x000f, B:12:0x0014, B:14:0x001f, B:16:0x0023, B:22:0x003c, B:20:0x002e, B:13:0x001a), top: B:28:0x0001 }] */
    /* JADX WARN: Removed duplicated region for block: B:18:0x0029  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    void setWakelockWorkSource(android.app.PendingIntent r6, android.os.WorkSource r7, int r8, java.lang.String r9, int r10, boolean r11) {
        /*
            r5 = this;
            r0 = 0
            android.app.PendingIntent r1 = r5.mTimeTickSender     // Catch: java.lang.Exception -> L48
            if (r6 != r1) goto L7
            r1 = 1
            goto L8
        L7:
            r1 = 0
        L8:
            android.os.PowerManager$WakeLock r2 = r5.mWakeLock     // Catch: java.lang.Exception -> L48
            r2.setUnimportantForLogging(r1)     // Catch: java.lang.Exception -> L48
            if (r11 != 0) goto L1a
            boolean r2 = r5.mLastWakeLockUnimportantForLogging     // Catch: java.lang.Exception -> L48
            if (r2 == 0) goto L14
            goto L1a
        L14:
            android.os.PowerManager$WakeLock r2 = r5.mWakeLock     // Catch: java.lang.Exception -> L48
            r2.setHistoryTag(r0)     // Catch: java.lang.Exception -> L48
            goto L1f
        L1a:
            android.os.PowerManager$WakeLock r2 = r5.mWakeLock     // Catch: java.lang.Exception -> L48
            r2.setHistoryTag(r9)     // Catch: java.lang.Exception -> L48
        L1f:
            r5.mLastWakeLockUnimportantForLogging = r1     // Catch: java.lang.Exception -> L48
            if (r7 == 0) goto L29
            android.os.PowerManager$WakeLock r2 = r5.mWakeLock     // Catch: java.lang.Exception -> L48
            r2.setWorkSource(r7)     // Catch: java.lang.Exception -> L48
            return
        L29:
            if (r10 < 0) goto L2e
        L2c:
            r2 = r10
            goto L3a
        L2e:
            android.app.IActivityManager r2 = android.app.ActivityManager.getService()     // Catch: java.lang.Exception -> L48
            android.content.IIntentSender r3 = r6.getTarget()     // Catch: java.lang.Exception -> L48
            int r2 = r2.getUidForIntentSender(r3)     // Catch: java.lang.Exception -> L48
        L3a:
            if (r2 < 0) goto L47
            android.os.PowerManager$WakeLock r3 = r5.mWakeLock     // Catch: java.lang.Exception -> L48
            android.os.WorkSource r4 = new android.os.WorkSource     // Catch: java.lang.Exception -> L48
            r4.<init>(r2)     // Catch: java.lang.Exception -> L48
            r3.setWorkSource(r4)     // Catch: java.lang.Exception -> L48
            return
        L47:
            goto L49
        L48:
            r1 = move-exception
        L49:
            android.os.PowerManager$WakeLock r1 = r5.mWakeLock
            r1.setWorkSource(r0)
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.AlarmManagerService.setWakelockWorkSource(android.app.PendingIntent, android.os.WorkSource, int, java.lang.String, int, boolean):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class AlarmHandler extends Handler {
        public static final int ALARM_EVENT = 1;
        public static final int APP_STANDBY_BUCKET_CHANGED = 5;
        public static final int APP_STANDBY_PAROLE_CHANGED = 6;
        public static final int LISTENER_TIMEOUT = 3;
        public static final int REMOVE_FOR_STOPPED = 7;
        public static final int REPORT_ALARMS_ACTIVE = 4;
        public static final int SEND_NEXT_ALARM_CLOCK_CHANGED = 2;

        public AlarmHandler() {
        }

        public void postRemoveForStopped(int uid) {
            obtainMessage(7, uid, 0).sendToTarget();
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    ArrayList<Alarm> triggerList = new ArrayList<>();
                    synchronized (AlarmManagerService.this.mLock) {
                        long nowRTC = System.currentTimeMillis();
                        long nowELAPSED = SystemClock.elapsedRealtime();
                        AlarmManagerService.this.triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
                        AlarmManagerService.this.updateNextAlarmClockLocked();
                    }
                    for (int i = 0; i < triggerList.size(); i++) {
                        Alarm alarm = triggerList.get(i);
                        try {
                            alarm.operation.send();
                        } catch (PendingIntent.CanceledException e) {
                            if (alarm.repeatInterval > 0) {
                                AlarmManagerService.this.removeImpl(alarm.operation);
                            }
                        }
                    }
                    return;
                case 2:
                    AlarmManagerService.this.sendNextAlarmClockChanged();
                    return;
                case 3:
                    AlarmManagerService.this.mDeliveryTracker.alarmTimedOut((IBinder) msg.obj);
                    return;
                case 4:
                    if (AlarmManagerService.this.mLocalDeviceIdleController != null) {
                        AlarmManagerService.this.mLocalDeviceIdleController.setAlarmsActive(msg.arg1 != 0);
                        return;
                    }
                    return;
                case 5:
                    synchronized (AlarmManagerService.this.mLock) {
                        ArraySet<Pair<String, Integer>> filterPackages = new ArraySet<>();
                        filterPackages.add(Pair.create((String) msg.obj, Integer.valueOf(msg.arg1)));
                        if (AlarmManagerService.this.reorderAlarmsBasedOnStandbyBuckets(filterPackages)) {
                            AlarmManagerService.this.rescheduleKernelAlarmsLocked();
                            AlarmManagerService.this.updateNextAlarmClockLocked();
                        }
                    }
                    return;
                case 6:
                    synchronized (AlarmManagerService.this.mLock) {
                        AlarmManagerService.this.mAppStandbyParole = ((Boolean) msg.obj).booleanValue();
                        if (AlarmManagerService.this.reorderAlarmsBasedOnStandbyBuckets(null)) {
                            AlarmManagerService.this.rescheduleKernelAlarmsLocked();
                            AlarmManagerService.this.updateNextAlarmClockLocked();
                        }
                    }
                    return;
                case 7:
                    synchronized (AlarmManagerService.this.mLock) {
                        AlarmManagerService.this.removeForStoppedLocked(msg.arg1);
                    }
                    return;
                default:
                    return;
            }
        }
    }

    /* loaded from: classes.dex */
    class ClockReceiver extends BroadcastReceiver {
        public ClockReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.TIME_TICK");
            filter.addAction("android.intent.action.DATE_CHANGED");
            AlarmManagerService.this.getContext().registerReceiver(this, filter);
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.intent.action.TIME_TICK")) {
                synchronized (AlarmManagerService.this.mLock) {
                    AlarmManagerService.this.mLastTickReceived = System.currentTimeMillis();
                }
                scheduleTimeTickEvent();
            } else if (intent.getAction().equals("android.intent.action.DATE_CHANGED")) {
                TimeZone zone = TimeZone.getTimeZone(SystemProperties.get(AlarmManagerService.TIMEZONE_PROPERTY));
                int gmtOffset = zone.getOffset(System.currentTimeMillis());
                AlarmManagerService.this.setKernelTimezone(AlarmManagerService.this.mNativeData, -(gmtOffset / 60000));
                scheduleDateChangedEvent();
            }
        }

        public void scheduleTimeTickEvent() {
            long currentTime = System.currentTimeMillis();
            long nextTime = 60000 * ((currentTime / 60000) + 1);
            long tickEventDelay = nextTime - currentTime;
            AlarmManagerService.this.setImpl(3, SystemClock.elapsedRealtime() + tickEventDelay, 0L, 0L, AlarmManagerService.this.mTimeTickSender, null, null, 1, null, null, Process.myUid(), PackageManagerService.PLATFORM_PACKAGE_NAME);
            synchronized (AlarmManagerService.this.mLock) {
                AlarmManagerService.this.mLastTickSet = currentTime;
            }
        }

        public void scheduleDateChangedEvent() {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(11, 0);
            calendar.set(12, 0);
            calendar.set(13, 0);
            calendar.set(14, 0);
            calendar.add(5, 1);
            AlarmManagerService.this.setImpl(1, calendar.getTimeInMillis(), 0L, 0L, AlarmManagerService.this.mDateChangeSender, null, null, 1, null, null, Process.myUid(), PackageManagerService.PLATFORM_PACKAGE_NAME);
        }
    }

    /* loaded from: classes.dex */
    class InteractiveStateReceiver extends BroadcastReceiver {
        public InteractiveStateReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.SCREEN_OFF");
            filter.addAction("android.intent.action.SCREEN_ON");
            filter.setPriority(1000);
            AlarmManagerService.this.getContext().registerReceiver(this, filter);
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            synchronized (AlarmManagerService.this.mLock) {
                AlarmManagerService.this.interactiveStateChangedLocked("android.intent.action.SCREEN_ON".equals(intent.getAction()));
            }
        }
    }

    /* loaded from: classes.dex */
    class UninstallReceiver extends BroadcastReceiver {
        public UninstallReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.PACKAGE_REMOVED");
            filter.addAction("android.intent.action.PACKAGE_RESTARTED");
            filter.addAction("android.intent.action.QUERY_PACKAGE_RESTART");
            filter.addDataScheme("package");
            AlarmManagerService.this.getContext().registerReceiver(this, filter);
            IntentFilter sdFilter = new IntentFilter();
            sdFilter.addAction("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE");
            sdFilter.addAction("android.intent.action.USER_STOPPED");
            sdFilter.addAction("android.intent.action.UID_REMOVED");
            AlarmManagerService.this.getContext().registerReceiver(this, sdFilter);
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String pkg;
            int uid = intent.getIntExtra("android.intent.extra.UID", -1);
            synchronized (AlarmManagerService.this.mLock) {
                String action = intent.getAction();
                String[] pkgList = null;
                int i = 0;
                if ("android.intent.action.QUERY_PACKAGE_RESTART".equals(action)) {
                    String[] pkgList2 = intent.getStringArrayExtra("android.intent.extra.PACKAGES");
                    int length = pkgList2.length;
                    while (i < length) {
                        String packageName = pkgList2[i];
                        if (!AlarmManagerService.this.lookForPackageLocked(packageName)) {
                            i++;
                        } else {
                            setResultCode(-1);
                            return;
                        }
                    }
                    return;
                }
                if ("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE".equals(action)) {
                    pkgList = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                } else if ("android.intent.action.USER_STOPPED".equals(action)) {
                    int userHandle = intent.getIntExtra("android.intent.extra.user_handle", -1);
                    if (userHandle >= 0) {
                        AlarmManagerService.this.removeUserLocked(userHandle);
                        for (int i2 = AlarmManagerService.this.mLastAlarmDeliveredForPackage.size() - 1; i2 >= 0; i2--) {
                            if (((Integer) ((Pair) AlarmManagerService.this.mLastAlarmDeliveredForPackage.keyAt(i2)).second).intValue() == userHandle) {
                                AlarmManagerService.this.mLastAlarmDeliveredForPackage.removeAt(i2);
                            }
                        }
                    }
                } else if ("android.intent.action.UID_REMOVED".equals(action)) {
                    if (uid >= 0) {
                        AlarmManagerService.this.mLastAllowWhileIdleDispatch.delete(uid);
                        AlarmManagerService.this.mUseAllowWhileIdleShortTime.delete(uid);
                    }
                } else if ("android.intent.action.PACKAGE_REMOVED".equals(action) && intent.getBooleanExtra("android.intent.extra.REPLACING", false)) {
                    return;
                } else {
                    Uri data = intent.getData();
                    if (data != null && (pkg = data.getSchemeSpecificPart()) != null) {
                        pkgList = new String[]{pkg};
                    }
                }
                if (pkgList != null && pkgList.length > 0) {
                    for (int i3 = AlarmManagerService.this.mLastAlarmDeliveredForPackage.size() - 1; i3 >= 0; i3--) {
                        Pair<String, Integer> packageUser = (Pair) AlarmManagerService.this.mLastAlarmDeliveredForPackage.keyAt(i3);
                        if (ArrayUtils.contains(pkgList, (String) packageUser.first) && ((Integer) packageUser.second).intValue() == UserHandle.getUserId(uid)) {
                            AlarmManagerService.this.mLastAlarmDeliveredForPackage.removeAt(i3);
                        }
                    }
                    int i4 = pkgList.length;
                    while (i < i4) {
                        String pkg2 = pkgList[i];
                        if (uid >= 0) {
                            AlarmManagerService.this.removeLocked(uid);
                        } else {
                            AlarmManagerService.this.removeLocked(pkg2);
                        }
                        AlarmManagerService.this.mPriorities.remove(pkg2);
                        for (int i5 = AlarmManagerService.this.mBroadcastStats.size() - 1; i5 >= 0; i5--) {
                            ArrayMap<String, BroadcastStats> uidStats = AlarmManagerService.this.mBroadcastStats.valueAt(i5);
                            if (uidStats.remove(pkg2) != null && uidStats.size() <= 0) {
                                AlarmManagerService.this.mBroadcastStats.removeAt(i5);
                            }
                        }
                        i++;
                    }
                }
            }
        }
    }

    /* loaded from: classes.dex */
    final class UidObserver extends IUidObserver.Stub {
        UidObserver() {
        }

        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
        }

        public void onUidGone(int uid, boolean disabled) {
            if (disabled) {
                AlarmManagerService.this.mHandler.postRemoveForStopped(uid);
            }
        }

        public void onUidActive(int uid) {
        }

        public void onUidIdle(int uid, boolean disabled) {
            if (disabled) {
                AlarmManagerService.this.mHandler.postRemoveForStopped(uid);
            }
        }

        public void onUidCachedChanged(int uid, boolean cached) {
        }
    }

    /* loaded from: classes.dex */
    final class AppStandbyTracker extends UsageStatsManagerInternal.AppIdleStateChangeListener {
        AppStandbyTracker() {
        }

        public void onAppIdleStateChanged(String packageName, int userId, boolean idle, int bucket, int reason) {
            AlarmManagerService.this.mHandler.removeMessages(5);
            AlarmManagerService.this.mHandler.obtainMessage(5, userId, -1, packageName).sendToTarget();
        }

        public void onParoleStateChanged(boolean isParoleOn) {
            AlarmManagerService.this.mHandler.removeMessages(5);
            AlarmManagerService.this.mHandler.removeMessages(6);
            AlarmManagerService.this.mHandler.obtainMessage(6, Boolean.valueOf(isParoleOn)).sendToTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final BroadcastStats getStatsLocked(PendingIntent pi) {
        String pkg = pi.getCreatorPackage();
        int uid = pi.getCreatorUid();
        return getStatsLocked(uid, pkg);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final BroadcastStats getStatsLocked(int uid, String pkgName) {
        ArrayMap<String, BroadcastStats> uidStats = this.mBroadcastStats.get(uid);
        if (uidStats == null) {
            uidStats = new ArrayMap<>();
            this.mBroadcastStats.put(uid, uidStats);
        }
        BroadcastStats bs = uidStats.get(pkgName);
        if (bs == null) {
            BroadcastStats bs2 = new BroadcastStats(uid, pkgName);
            uidStats.put(pkgName, bs2);
            return bs2;
        }
        return bs;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class DeliveryTracker extends IAlarmCompleteListener.Stub implements PendingIntent.OnFinished {
        DeliveryTracker() {
        }

        private InFlight removeLocked(PendingIntent pi, Intent intent) {
            for (int i = 0; i < AlarmManagerService.this.mInFlight.size(); i++) {
                if (AlarmManagerService.this.mInFlight.get(i).mPendingIntent == pi) {
                    return AlarmManagerService.this.mInFlight.remove(i);
                }
            }
            LocalLog localLog = AlarmManagerService.this.mLog;
            localLog.w("No in-flight alarm for " + pi + " " + intent);
            return null;
        }

        private InFlight removeLocked(IBinder listener) {
            for (int i = 0; i < AlarmManagerService.this.mInFlight.size(); i++) {
                if (AlarmManagerService.this.mInFlight.get(i).mListener == listener) {
                    return AlarmManagerService.this.mInFlight.remove(i);
                }
            }
            LocalLog localLog = AlarmManagerService.this.mLog;
            localLog.w("No in-flight alarm for listener " + listener);
            return null;
        }

        private void updateStatsLocked(InFlight inflight) {
            long nowELAPSED = SystemClock.elapsedRealtime();
            BroadcastStats bs = inflight.mBroadcastStats;
            bs.nesting--;
            if (bs.nesting <= 0) {
                bs.nesting = 0;
                bs.aggregateTime += nowELAPSED - bs.startTime;
            }
            FilterStats fs = inflight.mFilterStats;
            fs.nesting--;
            if (fs.nesting <= 0) {
                fs.nesting = 0;
                fs.aggregateTime += nowELAPSED - fs.startTime;
            }
            ActivityManager.noteAlarmFinish(inflight.mPendingIntent, inflight.mWorkSource, inflight.mUid, inflight.mTag);
        }

        private void updateTrackingLocked(InFlight inflight) {
            if (inflight != null) {
                updateStatsLocked(inflight);
            }
            AlarmManagerService alarmManagerService = AlarmManagerService.this;
            alarmManagerService.mBroadcastRefCount--;
            int i = 0;
            if (AlarmManagerService.this.mBroadcastRefCount == 0) {
                AlarmManagerService.this.mHandler.obtainMessage(4, 0).sendToTarget();
                AlarmManagerService.this.mWakeLock.release();
                if (AlarmManagerService.this.mInFlight.size() > 0) {
                    AlarmManagerService.this.mLog.w("Finished all dispatches with " + AlarmManagerService.this.mInFlight.size() + " remaining inflights");
                    while (true) {
                        int i2 = i;
                        if (i2 < AlarmManagerService.this.mInFlight.size()) {
                            AlarmManagerService.this.mLog.w("  Remaining #" + i2 + ": " + AlarmManagerService.this.mInFlight.get(i2));
                            i = i2 + 1;
                        } else {
                            AlarmManagerService.this.mInFlight.clear();
                            return;
                        }
                    }
                }
            } else if (AlarmManagerService.this.mInFlight.size() > 0) {
                InFlight inFlight = AlarmManagerService.this.mInFlight.get(0);
                AlarmManagerService.this.setWakelockWorkSource(inFlight.mPendingIntent, inFlight.mWorkSource, inFlight.mAlarmType, inFlight.mTag, -1, false);
            } else {
                AlarmManagerService.this.mLog.w("Alarm wakelock still held but sent queue empty");
                AlarmManagerService.this.mWakeLock.setWorkSource(null);
            }
        }

        public void alarmComplete(IBinder who) {
            if (who == null) {
                LocalLog localLog = AlarmManagerService.this.mLog;
                localLog.w("Invalid alarmComplete: uid=" + Binder.getCallingUid() + " pid=" + Binder.getCallingPid());
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                synchronized (AlarmManagerService.this.mLock) {
                    AlarmManagerService.this.mHandler.removeMessages(3, who);
                    InFlight inflight = removeLocked(who);
                    if (inflight != null) {
                        updateTrackingLocked(inflight);
                        AlarmManagerService.access$2008(AlarmManagerService.this);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // android.app.PendingIntent.OnFinished
        public void onSendFinished(PendingIntent pi, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
            synchronized (AlarmManagerService.this.mLock) {
                AlarmManagerService.access$2108(AlarmManagerService.this);
                updateTrackingLocked(removeLocked(pi, intent));
            }
        }

        public void alarmTimedOut(IBinder who) {
            synchronized (AlarmManagerService.this.mLock) {
                InFlight inflight = removeLocked(who);
                if (inflight != null) {
                    updateTrackingLocked(inflight);
                    AlarmManagerService.access$2008(AlarmManagerService.this);
                } else {
                    LocalLog localLog = AlarmManagerService.this.mLog;
                    localLog.w("Spurious timeout of listener " + who);
                }
            }
        }

        @GuardedBy("mLock")
        public void deliverLocked(Alarm alarm, long nowELAPSED, boolean allowWhileIdle) {
            int i;
            Alarm alarm2;
            if (alarm.operation != null) {
                AlarmManagerService.access$2208(AlarmManagerService.this);
                if (alarm.priorityClass.priority == 0) {
                    AlarmManagerService.this.mLastTickIssued = nowELAPSED;
                }
                try {
                    alarm.operation.send(AlarmManagerService.this.getContext(), 0, AlarmManagerService.this.mBackgroundIntent.putExtra("android.intent.extra.ALARM_COUNT", alarm.count), AlarmManagerService.this.mDeliveryTracker, AlarmManagerService.this.mHandler, null, allowWhileIdle ? AlarmManagerService.this.mIdleOptions : null);
                } catch (PendingIntent.CanceledException e) {
                    if (alarm.operation == AlarmManagerService.this.mTimeTickSender) {
                        Slog.wtf(AlarmManagerService.TAG, "mTimeTickSender canceled");
                    }
                    if (alarm.repeatInterval > 0) {
                        AlarmManagerService.this.removeImpl(alarm.operation);
                    }
                    AlarmManagerService.access$2108(AlarmManagerService.this);
                    return;
                }
            } else {
                AlarmManagerService.access$2508(AlarmManagerService.this);
                try {
                    alarm.listener.doAlarm(this);
                    AlarmManagerService.this.mHandler.sendMessageDelayed(AlarmManagerService.this.mHandler.obtainMessage(3, alarm.listener.asBinder()), AlarmManagerService.this.mConstants.LISTENER_TIMEOUT);
                } catch (Exception e2) {
                    AlarmManagerService.access$2008(AlarmManagerService.this);
                    return;
                }
            }
            if (AlarmManagerService.this.mBroadcastRefCount == 0) {
                AlarmManagerService.this.setWakelockWorkSource(alarm.operation, alarm.workSource, alarm.type, alarm.statsTag, alarm.operation == null ? alarm.uid : -1, true);
                AlarmManagerService.this.mWakeLock.acquire();
                AlarmManagerService.this.mHandler.obtainMessage(4, 1).sendToTarget();
            }
            InFlight inflight = new InFlight(AlarmManagerService.this, alarm.operation, alarm.listener, alarm.workSource, alarm.uid, alarm.packageName, alarm.type, alarm.statsTag, nowELAPSED);
            AlarmManagerService.this.mInFlight.add(inflight);
            AlarmManagerService.this.mBroadcastRefCount++;
            if (allowWhileIdle) {
                i = 1;
                alarm2 = alarm;
                AlarmManagerService.this.mLastAllowWhileIdleDispatch.put(alarm2.creatorUid, nowELAPSED);
                if (AlarmManagerService.this.mAppStateTracker == null || AlarmManagerService.this.mAppStateTracker.isUidInForeground(alarm2.creatorUid)) {
                    AlarmManagerService.this.mUseAllowWhileIdleShortTime.put(alarm2.creatorUid, true);
                } else {
                    AlarmManagerService.this.mUseAllowWhileIdleShortTime.put(alarm2.creatorUid, false);
                }
            } else {
                i = 1;
                alarm2 = alarm;
            }
            if (!AlarmManagerService.this.isExemptFromAppStandby(alarm2)) {
                Pair<String, Integer> packageUser = Pair.create(alarm2.sourcePackage, Integer.valueOf(UserHandle.getUserId(alarm2.creatorUid)));
                AlarmManagerService.this.mLastAlarmDeliveredForPackage.put(packageUser, Long.valueOf(nowELAPSED));
            }
            BroadcastStats bs = inflight.mBroadcastStats;
            bs.count += i;
            if (bs.nesting == 0) {
                bs.nesting = i;
                bs.startTime = nowELAPSED;
            } else {
                bs.nesting += i;
            }
            FilterStats fs = inflight.mFilterStats;
            fs.count += i;
            if (fs.nesting == 0) {
                fs.nesting = i;
                fs.startTime = nowELAPSED;
            } else {
                fs.nesting += i;
            }
            if (alarm2.type == 2 || alarm2.type == 0) {
                bs.numWakeup += i;
                fs.numWakeup += i;
                ActivityManager.noteWakeupAlarm(alarm2.operation, alarm2.workSource, alarm2.uid, alarm2.packageName, alarm2.statsTag);
            }
        }
    }

    /* loaded from: classes.dex */
    private class ShellCmd extends ShellCommand {
        private ShellCmd() {
        }

        IAlarmManager getBinderService() {
            return IAlarmManager.Stub.asInterface(AlarmManagerService.this.mService);
        }

        /* JADX WARN: Removed duplicated region for block: B:19:0x0036 A[Catch: Exception -> 0x005d, TryCatch #0 {Exception -> 0x005d, blocks: (B:6:0x000c, B:18:0x0033, B:19:0x0036, B:20:0x003b, B:22:0x0047, B:11:0x001c, B:14:0x0027), top: B:30:0x000c }] */
        /* JADX WARN: Removed duplicated region for block: B:20:0x003b A[Catch: Exception -> 0x005d, TryCatch #0 {Exception -> 0x005d, blocks: (B:6:0x000c, B:18:0x0033, B:19:0x0036, B:20:0x003b, B:22:0x0047, B:11:0x001c, B:14:0x0027), top: B:30:0x000c }] */
        /* JADX WARN: Removed duplicated region for block: B:22:0x0047 A[Catch: Exception -> 0x005d, TRY_LEAVE, TryCatch #0 {Exception -> 0x005d, blocks: (B:6:0x000c, B:18:0x0033, B:19:0x0036, B:20:0x003b, B:22:0x0047, B:11:0x001c, B:14:0x0027), top: B:30:0x000c }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public int onCommand(java.lang.String r7) {
            /*
                r6 = this;
                if (r7 != 0) goto L7
                int r0 = r6.handleDefaultCommands(r7)
                return r0
            L7:
                java.io.PrintWriter r0 = r6.getOutPrintWriter()
                r1 = -1
                int r2 = r7.hashCode()     // Catch: java.lang.Exception -> L5d
                r3 = 1369384280(0x519f2558, float:8.544079E10)
                r4 = 0
                if (r2 == r3) goto L27
                r3 = 2023087364(0x7895dd04, float:2.4316718E34)
                if (r2 == r3) goto L1c
                goto L32
            L1c:
                java.lang.String r2 = "set-timezone"
                boolean r2 = r7.equals(r2)     // Catch: java.lang.Exception -> L5d
                if (r2 == 0) goto L32
                r2 = 1
                goto L33
            L27:
                java.lang.String r2 = "set-time"
                boolean r2 = r7.equals(r2)     // Catch: java.lang.Exception -> L5d
                if (r2 == 0) goto L32
                r2 = r4
                goto L33
            L32:
                r2 = r1
            L33:
                switch(r2) {
                    case 0: goto L47;
                    case 1: goto L3b;
                    default: goto L36;
                }     // Catch: java.lang.Exception -> L5d
            L36:
                int r2 = r6.handleDefaultCommands(r7)     // Catch: java.lang.Exception -> L5d
                goto L5c
            L3b:
                java.lang.String r2 = r6.getNextArgRequired()     // Catch: java.lang.Exception -> L5d
                android.app.IAlarmManager r3 = r6.getBinderService()     // Catch: java.lang.Exception -> L5d
                r3.setTimeZone(r2)     // Catch: java.lang.Exception -> L5d
                return r4
            L47:
                java.lang.String r2 = r6.getNextArgRequired()     // Catch: java.lang.Exception -> L5d
                long r2 = java.lang.Long.parseLong(r2)     // Catch: java.lang.Exception -> L5d
                android.app.IAlarmManager r5 = r6.getBinderService()     // Catch: java.lang.Exception -> L5d
                boolean r5 = r5.setTime(r2)     // Catch: java.lang.Exception -> L5d
                if (r5 == 0) goto L5b
                r1 = r4
            L5b:
                return r1
            L5c:
                return r2
            L5d:
                r2 = move-exception
                r0.println(r2)
                return r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.AlarmManagerService.ShellCmd.onCommand(java.lang.String):int");
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Alarm manager service (alarm) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  set-time TIME");
            pw.println("    Set the system clock time to TIME where TIME is milliseconds");
            pw.println("    since the Epoch.");
            pw.println("  set-timezone TZ");
            pw.println("    Set the system timezone to TZ where TZ is an Olson id.");
        }
    }
}
