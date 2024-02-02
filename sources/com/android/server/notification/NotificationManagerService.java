package com.android.server.notification;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.backup.BackupManager;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.ICompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.IRingtonePlayer;
import android.metrics.LogMaker;
import android.net.Uri;
import android.net.util.NetworkConstants;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IDeviceIdleController;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.INotificationListener;
import android.service.notification.IStatusBarNotificationHolder;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.NotificationStats;
import android.service.notification.NotifyingApp;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.audio.AudioService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.notification.GroupHelper;
import com.android.server.notification.ManagedServices;
import com.android.server.notification.NotificationManagerService;
import com.android.server.notification.NotificationPolicy;
import com.android.server.notification.NotificationRecord;
import com.android.server.notification.SnoozeHelper;
import com.android.server.notification.ZenModeHelper;
import com.android.server.pm.PackageManagerService;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.WindowManagerInternal;
import com.xiaopeng.view.xpWindowManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import libcore.io.IoUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
public class NotificationManagerService extends SystemService {
    private static final String ATTR_VERSION = "version";
    private static final int DB_VERSION = 1;
    static final float DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE = 5.0f;
    static final int DEFAULT_STREAM_TYPE = 5;
    private static final long DELAY_FOR_ASSISTANT_TIME = 100;
    static final boolean ENABLE_BLOCKED_TOASTS = true;
    private static final int EVENTLOG_ENQUEUE_STATUS_IGNORED = 2;
    private static final int EVENTLOG_ENQUEUE_STATUS_NEW = 0;
    private static final int EVENTLOG_ENQUEUE_STATUS_UPDATE = 1;
    private static final String EXTRA_KEY = "key";
    static final int FINISH_TOKEN_TIMEOUT = 11000;
    static final int LONG_DELAY = 4000;
    static final int MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS = 3000;
    static final float MATCHES_CALL_FILTER_TIMEOUT_AFFINITY = 1.0f;
    static final int MAX_PACKAGE_NOTIFICATIONS = 50;
    static final int MESSAGE_DURATION_REACHED = 2;
    static final int MESSAGE_FINISH_TOKEN_TIMEOUT = 7;
    static final int MESSAGE_LISTENER_HINTS_CHANGED = 5;
    static final int MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED = 6;
    private static final int MESSAGE_RANKING_SORT = 1001;
    private static final int MESSAGE_RECONSIDER_RANKING = 1000;
    static final int MESSAGE_SAVE_POLICY_FILE = 3;
    static final int MESSAGE_SEND_RANKING_UPDATE = 4;
    private static final long MIN_PACKAGE_OVERRATE_LOG_INTERVAL = 5000;
    private static final int REQUEST_CODE_TIMEOUT = 1;
    private static final String SCHEME_TIMEOUT = "timeout";
    static final int SHORT_DELAY = 2500;
    static final long SNOOZE_UNTIL_UNSPECIFIED = -1;
    private static final String TAG_NOTIFICATION_POLICY = "notification-policy";
    static final int VIBRATE_PATTERN_MAXLEN = 17;
    private AccessibilityManager mAccessibilityManager;
    private ActivityManager mActivityManager;
    private AlarmManager mAlarmManager;
    private Predicate<String> mAllowedManagedServicePackages;
    private IActivityManager mAm;
    private AppOpsManager mAppOps;
    private UsageStatsManagerInternal mAppUsageStats;
    private Archive mArchive;
    private NotificationAssistants mAssistants;
    Light mAttentionLight;
    AudioManager mAudioManager;
    AudioManagerInternal mAudioManagerInternal;
    @GuardedBy("mNotificationLock")
    final ArrayMap<Integer, ArrayMap<String, String>> mAutobundledSummaries;
    private int mCallState;
    private ICompanionDeviceManager mCompanionManager;
    private ConditionProviders mConditionProviders;
    private IDeviceIdleController mDeviceIdleController;
    private boolean mDisableNotificationEffects;
    private DevicePolicyManagerInternal mDpm;
    private List<ComponentName> mEffectsSuppressors;
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mEnqueuedNotifications;
    private long[] mFallbackVibrationPattern;
    final IBinder mForegroundToken;
    private GroupHelper mGroupHelper;
    private WorkerHandler mHandler;
    boolean mHasLight;
    protected boolean mInCall;
    private AudioAttributes mInCallNotificationAudioAttributes;
    private Uri mInCallNotificationUri;
    private float mInCallNotificationVolume;
    private final BroadcastReceiver mIntentReceiver;
    private final NotificationManagerInternal mInternalService;
    private int mInterruptionFilter;
    private boolean mIsTelevision;
    private long mLastOverRateLogTime;
    boolean mLightEnabled;
    ArrayList<String> mLights;
    private int mListenerHints;
    private NotificationListeners mListeners;
    private final SparseArray<ArraySet<ManagedServices.ManagedServiceInfo>> mListenersDisablingEffects;
    protected final BroadcastReceiver mLocaleChangeReceiver;
    private float mMaxPackageEnqueueRate;
    private MetricsLogger mMetricsLogger;
    @VisibleForTesting
    final NotificationDelegate mNotificationDelegate;
    private Light mNotificationLight;
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mNotificationList;
    final Object mNotificationLock;
    private NotificationPolicy mNotificationPolicy;
    boolean mNotificationPulseEnabled;
    private final BroadcastReceiver mNotificationTimeoutReceiver;
    @GuardedBy("mNotificationLock")
    final ArrayMap<String, NotificationRecord> mNotificationsByKey;
    private boolean mOsdEnabled;
    private final BroadcastReceiver mPackageIntentReceiver;
    private IPackageManager mPackageManager;
    private PackageManager mPackageManagerClient;
    private AtomicFile mPolicyFile;
    private RankingHandler mRankingHandler;
    private RankingHelper mRankingHelper;
    private final HandlerThread mRankingThread;
    final ArrayMap<Integer, ArrayList<NotifyingApp>> mRecentApps;
    private final BroadcastReceiver mRestoreReceiver;
    boolean mScreenOn;
    private final IBinder mService;
    private SettingsObserver mSettingsObserver;
    private SnoozeHelper mSnoozeHelper;
    private String mSoundNotificationKey;
    StatusBarManagerInternal mStatusBar;
    final ArrayMap<String, NotificationRecord> mSummaryByGroupKey;
    boolean mSystemReady;
    private boolean mToastEnabled;
    final ArrayList<ToastRecord> mToastQueue;
    private NotificationUsageStats mUsageStats;
    private boolean mUseAttentionLight;
    private final ManagedServices.UserProfiles mUserProfiles;
    private String mVibrateNotificationKey;
    Vibrator mVibrator;
    private WindowManagerInternal mWindowManagerInternal;
    protected ZenModeHelper mZenModeHelper;
    static final String TAG = "NotificationService";
    static final boolean DBG = Log.isLoggable(TAG, 3);
    public static final boolean ENABLE_CHILD_NOTIFICATIONS = SystemProperties.getBoolean("debug.child_notifs", true);
    static final boolean DEBUG_INTERRUPTIVENESS = SystemProperties.getBoolean("debug.notification.interruptiveness", false);
    static final long[] DEFAULT_VIBRATE_PATTERN = {0, 250, 250, 250};
    private static final String ACTION_NOTIFICATION_TIMEOUT = NotificationManagerService.class.getSimpleName() + ".TIMEOUT";
    private static final int MY_UID = Process.myUid();
    private static final int MY_PID = Process.myPid();
    private static final IBinder WHITELIST_TOKEN = new Binder();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public interface FlagChecker {
        boolean apply(int i);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Archive {
        final ArrayDeque<StatusBarNotification> mBuffer;
        final int mBufferSize;

        public Archive(int size) {
            this.mBufferSize = size;
            this.mBuffer = new ArrayDeque<>(this.mBufferSize);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            int N = this.mBuffer.size();
            sb.append("Archive (");
            sb.append(N);
            sb.append(" notification");
            sb.append(N == 1 ? ")" : "s)");
            return sb.toString();
        }

        public void record(StatusBarNotification nr) {
            if (this.mBuffer.size() == this.mBufferSize) {
                this.mBuffer.removeFirst();
            }
            this.mBuffer.addLast(nr.cloneLight());
        }

        public Iterator<StatusBarNotification> descendingIterator() {
            return this.mBuffer.descendingIterator();
        }

        public StatusBarNotification[] getArray(int count) {
            if (count == 0) {
                count = this.mBufferSize;
            }
            StatusBarNotification[] a = new StatusBarNotification[Math.min(count, this.mBuffer.size())];
            Iterator<StatusBarNotification> iter = descendingIterator();
            for (int i = 0; iter.hasNext() && i < count; i++) {
                a[i] = iter.next();
            }
            return a;
        }
    }

    protected void readDefaultApprovedServices(int userId) {
        String[] split;
        String[] split2;
        String defaultListenerAccess = getContext().getResources().getString(17039657);
        if (defaultListenerAccess != null) {
            for (String whitelisted : defaultListenerAccess.split(":")) {
                Set<ComponentName> approvedListeners = this.mListeners.queryPackageForServices(whitelisted, 786432, userId);
                for (ComponentName cn : approvedListeners) {
                    try {
                        getBinderService().setNotificationListenerAccessGrantedForUser(cn, userId, true);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        String defaultDndAccess = getContext().getResources().getString(17039656);
        if (defaultListenerAccess != null) {
            for (String whitelisted2 : defaultDndAccess.split(":")) {
                try {
                    getBinderService().setNotificationPolicyAccessGranted(whitelisted2, true);
                } catch (RemoteException e2) {
                    e2.printStackTrace();
                }
            }
        }
        readDefaultAssistant(userId);
    }

    protected void readDefaultAssistant(int userId) {
        String defaultAssistantAccess = getContext().getResources().getString(17039652);
        if (defaultAssistantAccess != null) {
            Set<ComponentName> approvedAssistants = this.mAssistants.queryPackageForServices(defaultAssistantAccess, 786432, userId);
            for (ComponentName cn : approvedAssistants) {
                try {
                    getBinderService().setNotificationAssistantAccessGrantedForUser(cn, userId, true);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    void readPolicyXml(InputStream stream, boolean forRestore) throws XmlPullParserException, NumberFormatException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());
        XmlUtils.beginDocument(parser, TAG_NOTIFICATION_POLICY);
        boolean migratedManagedServices = false;
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if ("zen".equals(parser.getName())) {
                this.mZenModeHelper.readXml(parser, forRestore);
            } else if ("ranking".equals(parser.getName())) {
                this.mRankingHelper.readXml(parser, forRestore);
            }
            if (this.mListeners.getConfig().xmlTag.equals(parser.getName())) {
                this.mListeners.readXml(parser, this.mAllowedManagedServicePackages);
                migratedManagedServices = true;
            } else if (this.mAssistants.getConfig().xmlTag.equals(parser.getName())) {
                this.mAssistants.readXml(parser, this.mAllowedManagedServicePackages);
                migratedManagedServices = true;
            } else if (this.mConditionProviders.getConfig().xmlTag.equals(parser.getName())) {
                this.mConditionProviders.readXml(parser, this.mAllowedManagedServicePackages);
                migratedManagedServices = true;
            }
        }
        if (!migratedManagedServices) {
            this.mListeners.migrateToXml();
            this.mAssistants.migrateToXml();
            this.mConditionProviders.migrateToXml();
            savePolicyFile();
        }
        this.mAssistants.ensureAssistant();
    }

    private void loadPolicyFile() {
        if (DBG) {
            Slog.d(TAG, "loadPolicyFile");
        }
        synchronized (this.mPolicyFile) {
            InputStream infile = null;
            try {
                try {
                    try {
                        infile = this.mPolicyFile.openRead();
                        readPolicyXml(infile, false);
                    } catch (IOException e) {
                        Log.wtf(TAG, "Unable to read notification policy", e);
                        IoUtils.closeQuietly(infile);
                    }
                } catch (NumberFormatException e2) {
                    Log.wtf(TAG, "Unable to parse notification policy", e2);
                    IoUtils.closeQuietly(infile);
                }
            } catch (FileNotFoundException e3) {
                readDefaultApprovedServices(0);
            } catch (XmlPullParserException e4) {
                Log.wtf(TAG, "Unable to parse notification policy", e4);
                IoUtils.closeQuietly(infile);
            }
            IoUtils.closeQuietly(infile);
        }
    }

    public void savePolicyFile() {
        this.mHandler.removeMessages(3);
        this.mHandler.sendEmptyMessage(3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSavePolicyFile() {
        if (DBG) {
            Slog.d(TAG, "handleSavePolicyFile");
        }
        synchronized (this.mPolicyFile) {
            try {
                FileOutputStream stream = this.mPolicyFile.startWrite();
                try {
                    writePolicyXml(stream, false);
                    this.mPolicyFile.finishWrite(stream);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to save policy file, restoring backup", e);
                    this.mPolicyFile.failWrite(stream);
                }
            } catch (IOException e2) {
                Slog.w(TAG, "Failed to save policy file", e2);
                return;
            }
        }
        BackupManager.dataChanged(getContext().getPackageName());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writePolicyXml(OutputStream stream, boolean forBackup) throws IOException {
        XmlSerializer out = new FastXmlSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);
        out.startTag(null, TAG_NOTIFICATION_POLICY);
        out.attribute(null, "version", Integer.toString(1));
        this.mZenModeHelper.writeXml(out, forBackup, null);
        this.mRankingHelper.writeXml(out, forBackup);
        this.mListeners.writeXml(out, forBackup);
        this.mAssistants.writeXml(out, forBackup);
        this.mConditionProviders.writeXml(out, forBackup);
        out.endTag(null, TAG_NOTIFICATION_POLICY);
        out.endDocument();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ToastRecord {
        ITransientNotification callback;
        int duration;
        final int pid;
        final String pkg;
        Binder token;

        ToastRecord(int pid, String pkg, ITransientNotification callback, int duration, Binder token) {
            this.pid = pid;
            this.pkg = pkg;
            this.callback = callback;
            this.duration = duration;
            this.token = token;
        }

        void update(int duration) {
            this.duration = duration;
        }

        void update(ITransientNotification callback) {
            this.callback = callback;
        }

        void dump(PrintWriter pw, String prefix, DumpFilter filter) {
            if (filter == null || filter.matches(this.pkg)) {
                pw.println(prefix + this);
            }
        }

        public final String toString() {
            return "ToastRecord{" + Integer.toHexString(System.identityHashCode(this)) + " pkg=" + this.pkg + " callback=" + this.callback + " duration=" + this.duration;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void clearSoundLocked() {
        this.mSoundNotificationKey = null;
        long identity = Binder.clearCallingIdentity();
        try {
            IRingtonePlayer player = this.mAudioManager.getRingtonePlayer();
            if (player != null) {
                player.stopAsync();
            }
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
        Binder.restoreCallingIdentity(identity);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void clearVibrateLocked() {
        this.mVibrateNotificationKey = null;
        long identity = Binder.clearCallingIdentity();
        try {
            this.mVibrator.cancel();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void clearLightsLocked() {
        this.mLights.clear();
        updateLightsLocked();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_BADGING_URI;
        private final Uri NOTIFICATION_LIGHT_PULSE_URI;
        private final Uri NOTIFICATION_RATE_LIMIT_URI;

        SettingsObserver(Handler handler) {
            super(handler);
            this.NOTIFICATION_BADGING_URI = Settings.Secure.getUriFor("notification_badging");
            this.NOTIFICATION_LIGHT_PULSE_URI = Settings.System.getUriFor("notification_light_pulse");
            this.NOTIFICATION_RATE_LIMIT_URI = Settings.Global.getUriFor("max_notification_enqueue_rate");
        }

        void observe() {
            ContentResolver resolver = NotificationManagerService.this.getContext().getContentResolver();
            resolver.registerContentObserver(this.NOTIFICATION_BADGING_URI, false, this, -1);
            resolver.registerContentObserver(this.NOTIFICATION_LIGHT_PULSE_URI, false, this, -1);
            resolver.registerContentObserver(this.NOTIFICATION_RATE_LIMIT_URI, false, this, -1);
            update(null);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            ContentResolver resolver = NotificationManagerService.this.getContext().getContentResolver();
            if (uri == null || this.NOTIFICATION_LIGHT_PULSE_URI.equals(uri)) {
                boolean pulseEnabled = Settings.System.getIntForUser(resolver, "notification_light_pulse", 0, -2) != 0;
                if (NotificationManagerService.this.mNotificationPulseEnabled != pulseEnabled) {
                    NotificationManagerService.this.mNotificationPulseEnabled = pulseEnabled;
                    NotificationManagerService.this.updateNotificationPulse();
                }
            }
            if (uri == null || this.NOTIFICATION_RATE_LIMIT_URI.equals(uri)) {
                NotificationManagerService.this.mMaxPackageEnqueueRate = Settings.Global.getFloat(resolver, "max_notification_enqueue_rate", NotificationManagerService.this.mMaxPackageEnqueueRate);
            }
            if (uri == null || this.NOTIFICATION_BADGING_URI.equals(uri)) {
                NotificationManagerService.this.mRankingHelper.updateBadgingEnabled();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static long[] getLongArray(Resources r, int resid, int maxlen, long[] def) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return def;
        }
        int len = ar.length > maxlen ? maxlen : ar.length;
        long[] out = new long[len];
        for (int i = 0; i < len; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    public NotificationManagerService(Context context) {
        super(context);
        this.mForegroundToken = new Binder();
        this.mRankingThread = new HandlerThread("ranker", 10);
        this.mHasLight = true;
        this.mListenersDisablingEffects = new SparseArray<>();
        this.mEffectsSuppressors = new ArrayList();
        this.mInterruptionFilter = 0;
        this.mScreenOn = true;
        this.mInCall = false;
        this.mNotificationLock = new Object();
        this.mNotificationList = new ArrayList<>();
        this.mNotificationsByKey = new ArrayMap<>();
        this.mEnqueuedNotifications = new ArrayList<>();
        this.mAutobundledSummaries = new ArrayMap<>();
        this.mToastQueue = new ArrayList<>();
        this.mSummaryByGroupKey = new ArrayMap<>();
        this.mRecentApps = new ArrayMap<>();
        this.mLights = new ArrayList<>();
        this.mUserProfiles = new ManagedServices.UserProfiles();
        this.mMaxPackageEnqueueRate = 5.0f;
        this.mNotificationDelegate = new NotificationDelegate() { // from class: com.android.server.notification.NotificationManagerService.1
            @Override // com.android.server.notification.NotificationDelegate
            public void onSetDisabled(int status) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationManagerService.this.mDisableNotificationEffects = (262144 & status) != 0;
                    if (NotificationManagerService.this.disableNotificationEffects(null) != null) {
                        long identity = Binder.clearCallingIdentity();
                        try {
                            IRingtonePlayer player = NotificationManagerService.this.mAudioManager.getRingtonePlayer();
                            if (player != null) {
                                player.stopAsync();
                            }
                            Binder.restoreCallingIdentity(identity);
                        } catch (RemoteException e) {
                            Binder.restoreCallingIdentity(identity);
                        } catch (Throwable th) {
                            Binder.restoreCallingIdentity(identity);
                            throw th;
                        }
                        long identity2 = Binder.clearCallingIdentity();
                        NotificationManagerService.this.mVibrator.cancel();
                        Binder.restoreCallingIdentity(identity2);
                    }
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onClearAll(int callingUid, int callingPid, int userId) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationManagerService.this.cancelAllLocked(callingUid, callingPid, userId, 3, null, true);
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationClick(int callingUid, int callingPid, String key, NotificationVisibility nv) {
                NotificationManagerService.this.exitIdle();
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                    if (r == null) {
                        Log.w(NotificationManagerService.TAG, "No notification with key: " + key);
                        return;
                    }
                    long now = System.currentTimeMillis();
                    MetricsLogger.action(r.getLogMaker(now).setCategory(128).setType(4).addTaggedData(798, Integer.valueOf(nv.rank)).addTaggedData(1395, Integer.valueOf(nv.count)));
                    EventLogTags.writeNotificationClicked(key, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now), nv.rank, nv.count);
                    StatusBarNotification sbn = r.sbn;
                    NotificationManagerService.this.cancelNotification(callingUid, callingPid, sbn.getPackageName(), sbn.getTag(), sbn.getId(), 16, 64, false, r.getUserId(), 1, nv.rank, nv.count, null);
                    nv.recycle();
                    NotificationManagerService.this.reportUserInteraction(r);
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationActionClick(int callingUid, int callingPid, String key, int actionIndex, NotificationVisibility nv) {
                NotificationManagerService.this.exitIdle();
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    try {
                        try {
                            NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                            if (r == null) {
                                Log.w(NotificationManagerService.TAG, "No notification with key: " + key);
                                return;
                            }
                            long now = System.currentTimeMillis();
                            MetricsLogger.action(r.getLogMaker(now).setCategory((int) NetworkConstants.ICMPV6_ECHO_REPLY_TYPE).setType(4).setSubtype(actionIndex).addTaggedData(798, Integer.valueOf(nv.rank)).addTaggedData(1395, Integer.valueOf(nv.count)));
                            EventLogTags.writeNotificationActionClicked(key, actionIndex, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now), nv.rank, nv.count);
                            nv.recycle();
                            NotificationManagerService.this.reportUserInteraction(r);
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationClear(int callingUid, int callingPid, String pkg, String tag, int id, int userId, String key, int dismissalSurface, NotificationVisibility nv) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    try {
                        try {
                            try {
                                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                                if (r != null) {
                                    r.recordDismissalSurface(dismissalSurface);
                                }
                                NotificationManagerService.this.cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 66, true, userId, 2, nv.rank, nv.count, null);
                                nv.recycle();
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
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onPanelRevealed(boolean clearEffects, int items) {
                MetricsLogger.visible(NotificationManagerService.this.getContext(), 127);
                MetricsLogger.histogram(NotificationManagerService.this.getContext(), "note_load", items);
                EventLogTags.writeNotificationPanelRevealed(items);
                if (clearEffects) {
                    clearEffects();
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onPanelHidden() {
                MetricsLogger.hidden(NotificationManagerService.this.getContext(), 127);
                EventLogTags.writeNotificationPanelHidden();
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void clearEffects() {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    if (NotificationManagerService.DBG) {
                        Slog.d(NotificationManagerService.TAG, "clearEffects");
                    }
                    NotificationManagerService.this.clearSoundLocked();
                    NotificationManagerService.this.clearVibrateLocked();
                    NotificationManagerService.this.clearLightsLocked();
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationError(int callingUid, int callingPid, String pkg, String tag, int id, int uid, int initialPid, String message, int userId) {
                NotificationManagerService.this.cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId, 4, null);
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationVisibilityChanged(NotificationVisibility[] newlyVisibleKeys, NotificationVisibility[] noLongerVisibleKeys) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    for (NotificationVisibility nv : newlyVisibleKeys) {
                        NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(nv.key);
                        if (r != null) {
                            if (!r.isSeen()) {
                                if (NotificationManagerService.DBG) {
                                    Slog.d(NotificationManagerService.TAG, "Marking notification as visible " + nv.key);
                                }
                                NotificationManagerService.this.reportSeen(r);
                                if (r.getNumSmartRepliesAdded() > 0 && !r.hasSeenSmartReplies()) {
                                    r.setSeenSmartReplies(true);
                                    LogMaker logMaker = r.getLogMaker().setCategory(1382).addTaggedData(1384, Integer.valueOf(r.getNumSmartRepliesAdded()));
                                    NotificationManagerService.this.mMetricsLogger.write(logMaker);
                                }
                            }
                            r.setVisibility(true, nv.rank, nv.count);
                            NotificationManagerService.this.maybeRecordInterruptionLocked(r);
                            nv.recycle();
                        }
                    }
                    for (NotificationVisibility nv2 : noLongerVisibleKeys) {
                        NotificationRecord r2 = NotificationManagerService.this.mNotificationsByKey.get(nv2.key);
                        if (r2 != null) {
                            r2.setVisibility(false, nv2.rank, nv2.count);
                            nv2.recycle();
                        }
                    }
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationExpansionChanged(String key, boolean userAction, boolean expanded) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                    if (r != null) {
                        r.stats.onExpansionChanged(userAction, expanded);
                        long now = System.currentTimeMillis();
                        if (userAction) {
                            MetricsLogger.action(r.getLogMaker(now).setCategory(128).setType(expanded ? 3 : 14));
                        }
                        if (expanded && userAction) {
                            r.recordExpanded();
                        }
                        EventLogTags.writeNotificationExpansion(key, userAction ? 1 : 0, expanded ? 1 : 0, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));
                    }
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationDirectReplied(String key) {
                NotificationManagerService.this.exitIdle();
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                    if (r != null) {
                        r.recordDirectReplied();
                        NotificationManagerService.this.reportUserInteraction(r);
                    }
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationSmartRepliesAdded(String key, int replyCount) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                    if (r != null) {
                        r.setNumSmartRepliesAdded(replyCount);
                    }
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationSmartReplySent(String key, int replyIndex) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                    if (r != null) {
                        LogMaker logMaker = r.getLogMaker().setCategory(1383).setSubtype(replyIndex);
                        NotificationManagerService.this.mMetricsLogger.write(logMaker);
                        NotificationManagerService.this.reportUserInteraction(r);
                    }
                }
            }

            @Override // com.android.server.notification.NotificationDelegate
            public void onNotificationSettingsViewed(String key) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                    if (r != null) {
                        r.recordViewedSettings();
                    }
                }
            }
        };
        this.mLocaleChangeReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.LOCALE_CHANGED".equals(intent.getAction())) {
                    SystemNotificationChannels.createAll(context2);
                    NotificationManagerService.this.mZenModeHelper.updateDefaultZenRules();
                    NotificationManagerService.this.mRankingHelper.onLocaleChanged(context2, ActivityManager.getCurrentUser());
                }
            }
        };
        this.mRestoreReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.os.action.SETTING_RESTORED".equals(intent.getAction())) {
                    try {
                        String element = intent.getStringExtra("setting_name");
                        String newValue = intent.getStringExtra("new_value");
                        int restoredFromSdkInt = intent.getIntExtra("restored_from_sdk_int", 0);
                        NotificationManagerService.this.mListeners.onSettingRestored(element, newValue, restoredFromSdkInt, getSendingUserId());
                        NotificationManagerService.this.mConditionProviders.onSettingRestored(element, newValue, restoredFromSdkInt, getSendingUserId());
                    } catch (Exception e) {
                        Slog.wtf(NotificationManagerService.TAG, "Cannot restore managed services from settings", e);
                    }
                }
            }
        };
        this.mNotificationTimeoutReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.4
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                String action = intent.getAction();
                if (action != null && NotificationManagerService.ACTION_NOTIFICATION_TIMEOUT.equals(action)) {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        try {
                            try {
                                NotificationRecord record = NotificationManagerService.this.findNotificationByKeyLocked(intent.getStringExtra(NotificationManagerService.EXTRA_KEY));
                                if (record != null) {
                                    NotificationManagerService.this.cancelNotification(record.sbn.getUid(), record.sbn.getInitialPid(), record.sbn.getPackageName(), record.sbn.getTag(), record.sbn.getId(), 0, 64, true, record.getUserId(), 19, null);
                                }
                            } catch (Throwable th) {
                                th = th;
                                throw th;
                            }
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    }
                }
            }
        };
        this.mPackageIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.5
            /* JADX WARN: Multi-variable type inference failed */
            /* JADX WARN: Removed duplicated region for block: B:76:0x014e  */
            @Override // android.content.BroadcastReceiver
            /*
                Code decompiled incorrectly, please refer to instructions dump.
                To view partially-correct add '--show-bad-code' argument
            */
            public void onReceive(android.content.Context r32, android.content.Intent r33) {
                /*
                    Method dump skipped, instructions count: 480
                    To view this dump add '--comments-level debug' option
                */
                throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.AnonymousClass5.onReceive(android.content.Context, android.content.Intent):void");
            }
        };
        this.mIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.6
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                String action = intent.getAction();
                if (action.equals("android.intent.action.SCREEN_ON")) {
                    NotificationManagerService.this.mScreenOn = true;
                    NotificationManagerService.this.updateNotificationPulse();
                } else if (action.equals("android.intent.action.SCREEN_OFF")) {
                    NotificationManagerService.this.mScreenOn = false;
                    NotificationManagerService.this.updateNotificationPulse();
                } else if (action.equals("android.intent.action.PHONE_STATE")) {
                    NotificationManagerService.this.mInCall = TelephonyManager.EXTRA_STATE_OFFHOOK.equals(intent.getStringExtra(AudioService.CONNECT_INTENT_KEY_STATE));
                    NotificationManagerService.this.updateNotificationPulse();
                } else if (action.equals("android.intent.action.USER_STOPPED")) {
                    int userHandle = intent.getIntExtra("android.intent.extra.user_handle", -1);
                    if (userHandle >= 0) {
                        NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, null, null, 0, 0, true, userHandle, 6, null);
                    }
                } else if (action.equals("android.intent.action.MANAGED_PROFILE_UNAVAILABLE")) {
                    int userHandle2 = intent.getIntExtra("android.intent.extra.user_handle", -1);
                    if (userHandle2 >= 0) {
                        NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, null, null, 0, 0, true, userHandle2, 15, null);
                    }
                } else if (action.equals("android.intent.action.USER_PRESENT")) {
                    NotificationManagerService.this.mNotificationLight.turnOff();
                } else if (action.equals("android.intent.action.USER_SWITCHED")) {
                    int user = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    NotificationManagerService.this.mSettingsObserver.update(null);
                    NotificationManagerService.this.mUserProfiles.updateCache(context2);
                    NotificationManagerService.this.mConditionProviders.onUserSwitched(user);
                    NotificationManagerService.this.mListeners.onUserSwitched(user);
                    NotificationManagerService.this.mAssistants.onUserSwitched(user);
                    NotificationManagerService.this.mZenModeHelper.onUserSwitched(user);
                } else if (action.equals("android.intent.action.USER_ADDED")) {
                    int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    if (userId != -10000) {
                        NotificationManagerService.this.mUserProfiles.updateCache(context2);
                        if (!NotificationManagerService.this.mUserProfiles.isManagedProfile(userId)) {
                            NotificationManagerService.this.readDefaultApprovedServices(userId);
                        }
                    }
                } else if (!action.equals("android.intent.action.USER_REMOVED")) {
                    if (action.equals("android.intent.action.USER_UNLOCKED")) {
                        int user2 = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                        NotificationManagerService.this.mConditionProviders.onUserUnlocked(user2);
                        NotificationManagerService.this.mListeners.onUserUnlocked(user2);
                        NotificationManagerService.this.mAssistants.onUserUnlocked(user2);
                        NotificationManagerService.this.mZenModeHelper.onUserUnlocked(user2);
                    }
                } else {
                    int user3 = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    NotificationManagerService.this.mUserProfiles.updateCache(context2);
                    NotificationManagerService.this.mZenModeHelper.onUserRemoved(user3);
                    NotificationManagerService.this.mRankingHelper.onUserRemoved(user3);
                    NotificationManagerService.this.mListeners.onUserRemoved(user3);
                    NotificationManagerService.this.mConditionProviders.onUserRemoved(user3);
                    NotificationManagerService.this.mAssistants.onUserRemoved(user3);
                    NotificationManagerService.this.savePolicyFile();
                }
            }
        };
        this.mToastEnabled = true;
        this.mOsdEnabled = true;
        this.mService = new INotificationManager.Stub() { // from class: com.android.server.notification.NotificationManagerService.10
            public void enqueueToast(String pkg, ITransientNotification callback, int duration) {
                boolean isPackageSuspended;
                String str;
                long callingId;
                if (NotificationManagerService.DBG) {
                    Slog.i(NotificationManagerService.TAG, "enqueueToast pkg=" + pkg + " callback=" + callback + " duration=" + duration);
                }
                if (pkg != null && callback != null) {
                    if (NotificationManagerService.this.mToastEnabled) {
                        boolean isPackageSuspended2 = NotificationManagerService.this.isPackageSuspendedForUser(pkg, Binder.getCallingUid());
                        if (!areNotificationsEnabledForPackage(pkg, Binder.getCallingUid())) {
                            isPackageSuspended = isPackageSuspended2;
                        } else if (isPackageSuspended2) {
                            isPackageSuspended = isPackageSuspended2;
                        } else {
                            synchronized (NotificationManagerService.this.mToastQueue) {
                                try {
                                    try {
                                        int callingPid = Binder.getCallingPid();
                                        long callingId2 = Binder.clearCallingIdentity();
                                        try {
                                            int index = NotificationManagerService.this.indexOfToastPackageLocked(pkg);
                                            if (index >= 0) {
                                                try {
                                                    ToastRecord record = NotificationManagerService.this.mToastQueue.get(index);
                                                    record.update(duration);
                                                    try {
                                                        record.callback.hide();
                                                    } catch (RemoteException e) {
                                                    }
                                                    record.update(callback);
                                                    callingId = callingId2;
                                                } catch (Throwable th) {
                                                    th = th;
                                                    callingId = callingId2;
                                                    Binder.restoreCallingIdentity(callingId);
                                                    throw th;
                                                }
                                            } else {
                                                Binder token = new Binder();
                                                NotificationManagerService.this.mWindowManagerInternal.addWindowToken(token, 2005, 0);
                                                callingId = callingId2;
                                                try {
                                                    NotificationManagerService.this.mToastQueue.add(new ToastRecord(callingPid, pkg, callback, duration, token));
                                                    index = NotificationManagerService.this.mToastQueue.size() - 1;
                                                } catch (Throwable th2) {
                                                    th = th2;
                                                    Binder.restoreCallingIdentity(callingId);
                                                    throw th;
                                                }
                                            }
                                            NotificationManagerService.this.keepProcessAliveIfNeededLocked(callingPid);
                                            if (index == 0) {
                                                NotificationManagerService.this.showNextToastLocked();
                                            }
                                            Binder.restoreCallingIdentity(callingId);
                                            return;
                                        } catch (Throwable th3) {
                                            th = th3;
                                            callingId = callingId2;
                                        }
                                    } catch (Throwable th4) {
                                        th = th4;
                                        throw th;
                                    }
                                } catch (Throwable th5) {
                                    th = th5;
                                }
                            }
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("Suppressing toast from package ");
                        sb.append(pkg);
                        if (isPackageSuspended) {
                            str = " due to package suspended by administrator.";
                        } else {
                            str = " by user request.";
                        }
                        sb.append(str);
                        Slog.e(NotificationManagerService.TAG, sb.toString());
                        return;
                    }
                    Slog.e(NotificationManagerService.TAG, "Not enqueuing toast because of toast disabled.");
                    return;
                }
                Slog.e(NotificationManagerService.TAG, "Not doing toast. pkg=" + pkg + " callback=" + callback);
            }

            public void cancelToast(String pkg, ITransientNotification callback) {
                Slog.i(NotificationManagerService.TAG, "cancelToast pkg=" + pkg + " callback=" + callback);
                if (pkg == null || callback == null) {
                    Slog.e(NotificationManagerService.TAG, "Not cancelling notification. pkg=" + pkg + " callback=" + callback);
                    return;
                }
                synchronized (NotificationManagerService.this.mToastQueue) {
                    long callingId = Binder.clearCallingIdentity();
                    int index = NotificationManagerService.this.indexOfToastLocked(pkg, callback);
                    if (index >= 0) {
                        NotificationManagerService.this.cancelToastLocked(index);
                    } else {
                        Slog.w(NotificationManagerService.TAG, "Toast already cancelled. pkg=" + pkg + " callback=" + callback);
                    }
                    Binder.restoreCallingIdentity(callingId);
                }
            }

            public void finishToken(String pkg, ITransientNotification callback) {
                synchronized (NotificationManagerService.this.mToastQueue) {
                    long callingId = Binder.clearCallingIdentity();
                    int index = NotificationManagerService.this.indexOfToastLocked(pkg, callback);
                    if (index >= 0) {
                        ToastRecord record = NotificationManagerService.this.mToastQueue.get(index);
                        NotificationManagerService.this.finishTokenLocked(record.token);
                    } else {
                        Slog.w(NotificationManagerService.TAG, "Toast already killed. pkg=" + pkg + " callback=" + callback);
                    }
                    Binder.restoreCallingIdentity(callingId);
                }
            }

            public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id, Notification notification, int userId) throws RemoteException {
                NotificationManagerService.this.enqueueNotificationInternal(pkg, opPkg, Binder.getCallingUid(), Binder.getCallingPid(), tag, id, notification, userId);
            }

            public void cancelNotificationWithTag(String pkg, String tag, int id, int userId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, false, "cancelNotificationWithTag", pkg);
                int mustNotHaveFlags = NotificationManagerService.this.isCallingUidSystem() ? 0 : 1088;
                NotificationManagerService.this.cancelNotification(Binder.getCallingUid(), Binder.getCallingPid(), pkg, tag, id, 0, mustNotHaveFlags, false, userId2, 8, null);
            }

            public void cancelAllNotifications(String pkg, int userId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                NotificationManagerService.this.cancelAllNotificationsInt(Binder.getCallingUid(), Binder.getCallingPid(), pkg, null, 0, 64, true, ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, false, "cancelAllNotifications", pkg), 9, null);
            }

            public void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled) {
                enforceSystemOrSystemUI("setNotificationsEnabledForPackage");
                NotificationManagerService.this.mRankingHelper.setEnabled(pkg, uid, enabled);
                NotificationManagerService.this.mMetricsLogger.write(new LogMaker(147).setType(4).setPackageName(pkg).setSubtype(enabled ? 1 : 0));
                if (!enabled) {
                    NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, pkg, null, 0, 0, true, UserHandle.getUserId(uid), 7, null);
                }
                try {
                    NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.APP_BLOCK_STATE_CHANGED").putExtra("android.app.extra.BLOCKED_STATE", !enabled ? 1 : 0).addFlags(268435456).setPackage(pkg), UserHandle.of(UserHandle.getUserId(uid)), null);
                } catch (SecurityException e) {
                    Slog.w(NotificationManagerService.TAG, "Can't notify app about app block change", e);
                }
                NotificationManagerService.this.savePolicyFile();
            }

            public void setNotificationsEnabledWithImportanceLockForPackage(String pkg, int uid, boolean enabled) {
                setNotificationsEnabledForPackage(pkg, uid, enabled);
                NotificationManagerService.this.mRankingHelper.setAppImportanceLocked(pkg, uid);
            }

            public boolean areNotificationsEnabled(String pkg) {
                return areNotificationsEnabledForPackage(pkg, Binder.getCallingUid());
            }

            public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                if (UserHandle.getCallingUserId() != UserHandle.getUserId(uid)) {
                    Context context2 = NotificationManagerService.this.getContext();
                    context2.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS", "canNotifyAsPackage for uid " + uid);
                }
                return NotificationManagerService.this.mRankingHelper.getImportance(pkg, uid) != 0;
            }

            public int getPackageImportance(String pkg) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mRankingHelper.getImportance(pkg, Binder.getCallingUid());
            }

            public boolean canShowBadge(String pkg, int uid) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mRankingHelper.canShowBadge(pkg, uid);
            }

            public void setShowBadge(String pkg, int uid, boolean showBadge) {
                NotificationManagerService.this.checkCallerIsSystem();
                NotificationManagerService.this.mRankingHelper.setShowBadge(pkg, uid, showBadge);
                NotificationManagerService.this.savePolicyFile();
            }

            public void updateNotificationChannelGroupForPackage(String pkg, int uid, NotificationChannelGroup group) throws RemoteException {
                enforceSystemOrSystemUI("Caller not system or systemui");
                NotificationManagerService.this.createNotificationChannelGroup(pkg, uid, group, false, false);
                NotificationManagerService.this.savePolicyFile();
            }

            public void createNotificationChannelGroups(String pkg, ParceledListSlice channelGroupList) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                List<NotificationChannelGroup> groups = channelGroupList.getList();
                int groupSize = groups.size();
                for (int i = 0; i < groupSize; i++) {
                    NotificationChannelGroup group = groups.get(i);
                    NotificationManagerService.this.createNotificationChannelGroup(pkg, Binder.getCallingUid(), group, true, false);
                }
                NotificationManagerService.this.savePolicyFile();
            }

            private void createNotificationChannelsImpl(String pkg, int uid, ParceledListSlice channelsList) {
                List<NotificationChannel> channels = channelsList.getList();
                int channelsSize = channels.size();
                for (int i = 0; i < channelsSize; i++) {
                    NotificationChannel channel = channels.get(i);
                    Preconditions.checkNotNull(channel, "channel in list is null");
                    NotificationManagerService.this.mRankingHelper.createNotificationChannel(pkg, uid, channel, true, NotificationManagerService.this.mConditionProviders.isPackageOrComponentAllowed(pkg, UserHandle.getUserId(uid)));
                    NotificationManagerService.this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(uid), NotificationManagerService.this.mRankingHelper.getNotificationChannel(pkg, uid, channel.getId(), false), 1);
                }
                NotificationManagerService.this.savePolicyFile();
            }

            public void createNotificationChannels(String pkg, ParceledListSlice channelsList) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                createNotificationChannelsImpl(pkg, Binder.getCallingUid(), channelsList);
            }

            public void createNotificationChannelsForPackage(String pkg, int uid, ParceledListSlice channelsList) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystem();
                createNotificationChannelsImpl(pkg, uid, channelsList);
            }

            public NotificationChannel getNotificationChannel(String pkg, String channelId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mRankingHelper.getNotificationChannel(pkg, Binder.getCallingUid(), channelId, false);
            }

            public NotificationChannel getNotificationChannelForPackage(String pkg, int uid, String channelId, boolean includeDeleted) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mRankingHelper.getNotificationChannel(pkg, uid, channelId, includeDeleted);
            }

            public void deleteNotificationChannel(String pkg, String channelId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                int callingUid = Binder.getCallingUid();
                if ("miscellaneous".equals(channelId)) {
                    throw new IllegalArgumentException("Cannot delete default channel");
                }
                NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, pkg, channelId, 0, 0, true, UserHandle.getUserId(callingUid), 17, null);
                NotificationManagerService.this.mRankingHelper.deleteNotificationChannel(pkg, callingUid, channelId);
                NotificationManagerService.this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(callingUid), NotificationManagerService.this.mRankingHelper.getNotificationChannel(pkg, callingUid, channelId, true), 3);
                NotificationManagerService.this.savePolicyFile();
            }

            public NotificationChannelGroup getNotificationChannelGroup(String pkg, String groupId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mRankingHelper.getNotificationChannelGroupWithChannels(pkg, Binder.getCallingUid(), groupId, false);
            }

            public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(String pkg) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mRankingHelper.getNotificationChannelGroups(pkg, Binder.getCallingUid(), false, false, true);
            }

            public void deleteNotificationChannelGroup(String pkg, String groupId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                int callingUid = Binder.getCallingUid();
                NotificationChannelGroup groupToDelete = NotificationManagerService.this.mRankingHelper.getNotificationChannelGroup(groupId, pkg, callingUid);
                if (groupToDelete != null) {
                    List<NotificationChannel> deletedChannels = NotificationManagerService.this.mRankingHelper.deleteNotificationChannelGroup(pkg, callingUid, groupId);
                    int i = 0;
                    while (true) {
                        int i2 = i;
                        int i3 = deletedChannels.size();
                        if (i2 < i3) {
                            NotificationChannel deletedChannel = deletedChannels.get(i2);
                            NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, pkg, deletedChannel.getId(), 0, 0, true, UserHandle.getUserId(Binder.getCallingUid()), 17, null);
                            NotificationManagerService.this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(callingUid), deletedChannel, 3);
                            i = i2 + 1;
                            deletedChannels = deletedChannels;
                        } else {
                            NotificationManagerService.this.mListeners.notifyNotificationChannelGroupChanged(pkg, UserHandle.getUserHandleForUid(callingUid), groupToDelete, 3);
                            NotificationManagerService.this.savePolicyFile();
                            return;
                        }
                    }
                }
            }

            public void updateNotificationChannelForPackage(String pkg, int uid, NotificationChannel channel) {
                enforceSystemOrSystemUI("Caller not system or systemui");
                Preconditions.checkNotNull(channel);
                NotificationManagerService.this.updateNotificationChannelInt(pkg, uid, channel, false);
            }

            public ParceledListSlice<NotificationChannel> getNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted) {
                enforceSystemOrSystemUI("getNotificationChannelsForPackage");
                return NotificationManagerService.this.mRankingHelper.getNotificationChannels(pkg, uid, includeDeleted);
            }

            public int getNumNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted) {
                enforceSystemOrSystemUI("getNumNotificationChannelsForPackage");
                return NotificationManagerService.this.mRankingHelper.getNotificationChannels(pkg, uid, includeDeleted).getList().size();
            }

            public boolean onlyHasDefaultChannel(String pkg, int uid) {
                enforceSystemOrSystemUI("onlyHasDefaultChannel");
                return NotificationManagerService.this.mRankingHelper.onlyHasDefaultChannel(pkg, uid);
            }

            public int getDeletedChannelCount(String pkg, int uid) {
                enforceSystemOrSystemUI("getDeletedChannelCount");
                return NotificationManagerService.this.mRankingHelper.getDeletedChannelCount(pkg, uid);
            }

            public int getBlockedChannelCount(String pkg, int uid) {
                enforceSystemOrSystemUI("getBlockedChannelCount");
                return NotificationManagerService.this.mRankingHelper.getBlockedChannelCount(pkg, uid);
            }

            public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroupsForPackage(String pkg, int uid, boolean includeDeleted) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mRankingHelper.getNotificationChannelGroups(pkg, uid, includeDeleted, true, false);
            }

            public NotificationChannelGroup getPopulatedNotificationChannelGroupForPackage(String pkg, int uid, String groupId, boolean includeDeleted) {
                enforceSystemOrSystemUI("getPopulatedNotificationChannelGroupForPackage");
                return NotificationManagerService.this.mRankingHelper.getNotificationChannelGroupWithChannels(pkg, uid, groupId, includeDeleted);
            }

            public NotificationChannelGroup getNotificationChannelGroupForPackage(String groupId, String pkg, int uid) {
                enforceSystemOrSystemUI("getNotificationChannelGroupForPackage");
                return NotificationManagerService.this.mRankingHelper.getNotificationChannelGroup(groupId, pkg, uid);
            }

            public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mRankingHelper.getNotificationChannels(pkg, Binder.getCallingUid(), false);
            }

            public ParceledListSlice<NotifyingApp> getRecentNotifyingAppsForUser(int userId) {
                ParceledListSlice<NotifyingApp> parceledListSlice;
                NotificationManagerService.this.checkCallerIsSystem();
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    List<NotifyingApp> apps = new ArrayList<>(NotificationManagerService.this.mRecentApps.getOrDefault(Integer.valueOf(userId), new ArrayList<>()));
                    parceledListSlice = new ParceledListSlice<>(apps);
                }
                return parceledListSlice;
            }

            public int getBlockedAppCount(int userId) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mRankingHelper.getBlockedAppCount(userId);
            }

            public boolean areChannelsBypassingDnd() {
                return NotificationManagerService.this.mRankingHelper.areChannelsBypassingDnd();
            }

            public void clearData(String packageName, int uid, boolean fromApp) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystem();
                NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, packageName, null, 0, 0, true, UserHandle.getUserId(Binder.getCallingUid()), 17, null);
                String[] packages = {packageName};
                int[] uids = {uid};
                NotificationManagerService.this.mListeners.onPackagesChanged(true, packages, uids);
                NotificationManagerService.this.mAssistants.onPackagesChanged(true, packages, uids);
                NotificationManagerService.this.mConditionProviders.onPackagesChanged(true, packages, uids);
                if (!fromApp) {
                    NotificationManagerService.this.mRankingHelper.onPackagesChanged(true, UserHandle.getCallingUserId(), packages, uids);
                }
                NotificationManagerService.this.savePolicyFile();
            }

            public StatusBarNotification[] getActiveNotifications(String callingPkg) {
                NotificationManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.ACCESS_NOTIFICATIONS", "NotificationManagerService.getActiveNotifications");
                StatusBarNotification[] tmp = null;
                int uid = Binder.getCallingUid();
                if (NotificationManagerService.this.mAppOps.noteOpNoThrow(25, uid, callingPkg) == 0) {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        tmp = new StatusBarNotification[NotificationManagerService.this.mNotificationList.size()];
                        int N = NotificationManagerService.this.mNotificationList.size();
                        for (int i = 0; i < N; i++) {
                            tmp[i] = NotificationManagerService.this.mNotificationList.get(i).sbn;
                        }
                    }
                }
                return tmp;
            }

            public ParceledListSlice<StatusBarNotification> getAppActiveNotifications(String pkg, int incomingUserId) {
                ParceledListSlice<StatusBarNotification> parceledListSlice;
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                int userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), incomingUserId, true, false, "getAppActiveNotifications", pkg);
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    ArrayMap<String, StatusBarNotification> map = new ArrayMap<>(NotificationManagerService.this.mNotificationList.size() + NotificationManagerService.this.mEnqueuedNotifications.size());
                    int N = NotificationManagerService.this.mNotificationList.size();
                    for (int i = 0; i < N; i++) {
                        StatusBarNotification sbn = sanitizeSbn(pkg, userId, NotificationManagerService.this.mNotificationList.get(i).sbn);
                        if (sbn != null) {
                            map.put(sbn.getKey(), sbn);
                        }
                    }
                    for (NotificationRecord snoozed : NotificationManagerService.this.mSnoozeHelper.getSnoozed(userId, pkg)) {
                        StatusBarNotification sbn2 = sanitizeSbn(pkg, userId, snoozed.sbn);
                        if (sbn2 != null) {
                            map.put(sbn2.getKey(), sbn2);
                        }
                    }
                    int M = NotificationManagerService.this.mEnqueuedNotifications.size();
                    for (int i2 = 0; i2 < M; i2++) {
                        StatusBarNotification sbn3 = sanitizeSbn(pkg, userId, NotificationManagerService.this.mEnqueuedNotifications.get(i2).sbn);
                        if (sbn3 != null) {
                            map.put(sbn3.getKey(), sbn3);
                        }
                    }
                    ArrayList<StatusBarNotification> list = new ArrayList<>(map.size());
                    list.addAll(map.values());
                    parceledListSlice = new ParceledListSlice<>(list);
                }
                return parceledListSlice;
            }

            private StatusBarNotification sanitizeSbn(String pkg, int userId, StatusBarNotification sbn) {
                if (sbn.getPackageName().equals(pkg) && sbn.getUserId() == userId) {
                    return new StatusBarNotification(sbn.getPackageName(), sbn.getOpPkg(), sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(), sbn.getNotification().clone(), sbn.getUser(), sbn.getOverrideGroupKey(), sbn.getPostTime());
                }
                return null;
            }

            public StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count) {
                NotificationManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.ACCESS_NOTIFICATIONS", "NotificationManagerService.getHistoricalNotifications");
                StatusBarNotification[] tmp = null;
                int uid = Binder.getCallingUid();
                if (NotificationManagerService.this.mAppOps.noteOpNoThrow(25, uid, callingPkg) == 0) {
                    synchronized (NotificationManagerService.this.mArchive) {
                        tmp = NotificationManagerService.this.mArchive.getArray(count);
                    }
                }
                return tmp;
            }

            public void registerListener(INotificationListener listener, ComponentName component, int userid) {
                enforceSystemOrSystemUI("INotificationManager.registerListener");
                NotificationManagerService.this.mListeners.registerService(listener, component, userid);
            }

            public void unregisterListener(INotificationListener token, int userid) {
                NotificationManagerService.this.mListeners.unregisterService((IInterface) token, userid);
            }

            public void cancelNotificationsFromListener(INotificationListener token, String[] keys) {
                int i;
                int N;
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        if (keys != null) {
                            int N2 = keys.length;
                            int i2 = 0;
                            while (true) {
                                int i3 = i2;
                                if (i3 >= N2) {
                                    break;
                                }
                                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(keys[i3]);
                                if (r == null) {
                                    i = i3;
                                    N = N2;
                                } else {
                                    int userId = r.sbn.getUserId();
                                    if (userId != info.userid && userId != -1 && !NotificationManagerService.this.mUserProfiles.isCurrentProfile(userId)) {
                                        throw new SecurityException("Disallowed call from listener: " + info.service);
                                    }
                                    i = i3;
                                    N = N2;
                                    cancelNotificationFromListenerLocked(info, callingUid, callingPid, r.sbn.getPackageName(), r.sbn.getTag(), r.sbn.getId(), userId);
                                }
                                i2 = i + 1;
                                N2 = N;
                            }
                        } else {
                            NotificationManagerService.this.cancelAllLocked(callingUid, callingPid, info.userid, 11, info, info.supportsProfiles());
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void requestBindListener(ComponentName component) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(component.getPackageName());
                long identity = Binder.clearCallingIdentity();
                try {
                    ManagedServices manager = NotificationManagerService.this.mAssistants.isComponentEnabledForCurrentProfiles(component) ? NotificationManagerService.this.mAssistants : NotificationManagerService.this.mListeners;
                    manager.setComponentState(component, true);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void requestUnbindListener(INotificationListener token) {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        info.getOwner().setComponentState(info.component, false);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void setNotificationsShownFromListener(INotificationListener token, String[] keys) {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        if (keys != null) {
                            int N = keys.length;
                            for (int i = 0; i < N; i++) {
                                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(keys[i]);
                                if (r != null) {
                                    int userId = r.sbn.getUserId();
                                    if (userId != info.userid && userId != -1 && !NotificationManagerService.this.mUserProfiles.isCurrentProfile(userId)) {
                                        throw new SecurityException("Disallowed call from listener: " + info.service);
                                    }
                                    if (!r.isSeen()) {
                                        if (NotificationManagerService.DBG) {
                                            Slog.d(NotificationManagerService.TAG, "Marking notification as seen " + keys[i]);
                                        }
                                        NotificationManagerService.this.reportSeen(r);
                                        r.setSeen();
                                        NotificationManagerService.this.maybeRecordInterruptionLocked(r);
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            @GuardedBy("mNotificationLock")
            private void cancelNotificationFromListenerLocked(ManagedServices.ManagedServiceInfo info, int callingUid, int callingPid, String pkg, String tag, int id, int userId) {
                NotificationManagerService.this.cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 66, true, userId, 10, info);
            }

            public void snoozeNotificationUntilContextFromListener(INotificationListener token, String key, String snoozeCriterionId) {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        NotificationManagerService.this.snoozeNotificationInt(key, -1L, snoozeCriterionId, info);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void snoozeNotificationUntilFromListener(INotificationListener token, String key, long duration) {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        NotificationManagerService.this.snoozeNotificationInt(key, duration, null, info);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void unsnoozeNotificationFromAssistant(INotificationListener token, String key) {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mAssistants.checkServiceTokenLocked(token);
                        NotificationManagerService.this.unsnoozeNotificationInt(key, info);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void cancelNotificationFromListener(INotificationListener token, String pkg, String tag, int id) {
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();
                long identity = Binder.clearCallingIdentity();
                try {
                    try {
                        synchronized (NotificationManagerService.this.mNotificationLock) {
                            try {
                                ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                                if (info.supportsProfiles()) {
                                    Log.e(NotificationManagerService.TAG, "Ignoring deprecated cancelNotification(pkg, tag, id) from " + info.component + " use cancelNotification(key) instead.");
                                } else {
                                    cancelNotificationFromListenerLocked(info, callingUid, callingPid, pkg, tag, id, info.userid);
                                }
                                Binder.restoreCallingIdentity(identity);
                            } catch (Throwable th) {
                                th = th;
                                try {
                                    throw th;
                                } catch (Throwable th2) {
                                    th = th2;
                                    Binder.restoreCallingIdentity(identity);
                                    throw th;
                                }
                            }
                        }
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } catch (Throwable th4) {
                    th = th4;
                }
            }

            public ParceledListSlice<StatusBarNotification> getActiveNotificationsFromListener(INotificationListener token, String[] keys, int trim) {
                ParceledListSlice<StatusBarNotification> parceledListSlice;
                NotificationRecord r;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                    boolean getKeys = keys != null;
                    int N = getKeys ? keys.length : NotificationManagerService.this.mNotificationList.size();
                    ArrayList<StatusBarNotification> list = new ArrayList<>(N);
                    for (int i = 0; i < N; i++) {
                        if (getKeys) {
                            r = NotificationManagerService.this.mNotificationsByKey.get(keys[i]);
                        } else {
                            r = NotificationManagerService.this.mNotificationList.get(i);
                        }
                        if (r != null) {
                            StatusBarNotification sbn = r.sbn;
                            if (NotificationManagerService.this.isVisibleToListener(sbn, info)) {
                                StatusBarNotification sbnToSend = trim == 0 ? sbn : sbn.cloneLight();
                                list.add(sbnToSend);
                            }
                        }
                    }
                    parceledListSlice = new ParceledListSlice<>(list);
                }
                return parceledListSlice;
            }

            public ParceledListSlice<StatusBarNotification> getSnoozedNotificationsFromListener(INotificationListener token, int trim) {
                ParceledListSlice<StatusBarNotification> parceledListSlice;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                    List<NotificationRecord> snoozedRecords = NotificationManagerService.this.mSnoozeHelper.getSnoozed();
                    int N = snoozedRecords.size();
                    ArrayList<StatusBarNotification> list = new ArrayList<>(N);
                    for (int i = 0; i < N; i++) {
                        NotificationRecord r = snoozedRecords.get(i);
                        if (r != null) {
                            StatusBarNotification sbn = r.sbn;
                            if (NotificationManagerService.this.isVisibleToListener(sbn, info)) {
                                StatusBarNotification sbnToSend = trim == 0 ? sbn : sbn.cloneLight();
                                list.add(sbnToSend);
                            }
                        }
                    }
                    parceledListSlice = new ParceledListSlice<>(list);
                }
                return parceledListSlice;
            }

            public void requestHintsFromListener(INotificationListener token, int hints) {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        boolean disableEffects = (hints & 7) != 0;
                        if (disableEffects) {
                            NotificationManagerService.this.addDisabledHints(info, hints);
                        } else {
                            NotificationManagerService.this.removeDisabledHints(info, hints);
                        }
                        NotificationManagerService.this.updateListenerHintsLocked();
                        NotificationManagerService.this.updateEffectsSuppressorLocked();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public int getHintsFromListener(INotificationListener token) {
                int i;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    i = NotificationManagerService.this.mListenerHints;
                }
                return i;
            }

            public void requestInterruptionFilterFromListener(INotificationListener token, int interruptionFilter) throws RemoteException {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        NotificationManagerService.this.mZenModeHelper.requestFromListener(info.component, interruptionFilter);
                        NotificationManagerService.this.updateInterruptionFilterLocked();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public int getInterruptionFilterFromListener(INotificationListener token) throws RemoteException {
                int i;
                synchronized (NotificationManagerService.this.mNotificationLight) {
                    i = NotificationManagerService.this.mInterruptionFilter;
                }
                return i;
            }

            public void setOnNotificationPostedTrimFromListener(INotificationListener token, int trim) throws RemoteException {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                    if (info == null) {
                        return;
                    }
                    NotificationManagerService.this.mListeners.setOnNotificationPostedTrimLocked(info, trim);
                }
            }

            public int getZenMode() {
                return NotificationManagerService.this.mZenModeHelper.getZenMode();
            }

            public ZenModeConfig getZenModeConfig() {
                enforceSystemOrSystemUI("INotificationManager.getZenModeConfig");
                return NotificationManagerService.this.mZenModeHelper.getConfig();
            }

            public void setZenMode(int mode, Uri conditionId, String reason) throws RemoteException {
                enforceSystemOrSystemUI("INotificationManager.setZenMode");
                long identity = Binder.clearCallingIdentity();
                try {
                    NotificationManagerService.this.mZenModeHelper.setManualZenMode(mode, conditionId, null, reason);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public List<ZenModeConfig.ZenRule> getZenRules() throws RemoteException {
                enforcePolicyAccess(Binder.getCallingUid(), "getAutomaticZenRules");
                return NotificationManagerService.this.mZenModeHelper.getZenRules();
            }

            public AutomaticZenRule getAutomaticZenRule(String id) throws RemoteException {
                Preconditions.checkNotNull(id, "Id is null");
                enforcePolicyAccess(Binder.getCallingUid(), "getAutomaticZenRule");
                return NotificationManagerService.this.mZenModeHelper.getAutomaticZenRule(id);
            }

            public String addAutomaticZenRule(AutomaticZenRule automaticZenRule) throws RemoteException {
                Preconditions.checkNotNull(automaticZenRule, "automaticZenRule is null");
                Preconditions.checkNotNull(automaticZenRule.getName(), "Name is null");
                Preconditions.checkNotNull(automaticZenRule.getOwner(), "Owner is null");
                Preconditions.checkNotNull(automaticZenRule.getConditionId(), "ConditionId is null");
                enforcePolicyAccess(Binder.getCallingUid(), "addAutomaticZenRule");
                return NotificationManagerService.this.mZenModeHelper.addAutomaticZenRule(automaticZenRule, "addAutomaticZenRule");
            }

            public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule) throws RemoteException {
                Preconditions.checkNotNull(automaticZenRule, "automaticZenRule is null");
                Preconditions.checkNotNull(automaticZenRule.getName(), "Name is null");
                Preconditions.checkNotNull(automaticZenRule.getOwner(), "Owner is null");
                Preconditions.checkNotNull(automaticZenRule.getConditionId(), "ConditionId is null");
                enforcePolicyAccess(Binder.getCallingUid(), "updateAutomaticZenRule");
                return NotificationManagerService.this.mZenModeHelper.updateAutomaticZenRule(id, automaticZenRule, "updateAutomaticZenRule");
            }

            public boolean removeAutomaticZenRule(String id) throws RemoteException {
                Preconditions.checkNotNull(id, "Id is null");
                enforcePolicyAccess(Binder.getCallingUid(), "removeAutomaticZenRule");
                return NotificationManagerService.this.mZenModeHelper.removeAutomaticZenRule(id, "removeAutomaticZenRule");
            }

            public boolean removeAutomaticZenRules(String packageName) throws RemoteException {
                Preconditions.checkNotNull(packageName, "Package name is null");
                enforceSystemOrSystemUI("removeAutomaticZenRules");
                return NotificationManagerService.this.mZenModeHelper.removeAutomaticZenRules(packageName, "removeAutomaticZenRules");
            }

            public int getRuleInstanceCount(ComponentName owner) throws RemoteException {
                Preconditions.checkNotNull(owner, "Owner is null");
                enforceSystemOrSystemUI("getRuleInstanceCount");
                return NotificationManagerService.this.mZenModeHelper.getCurrentInstanceCount(owner);
            }

            public void setInterruptionFilter(String pkg, int filter) throws RemoteException {
                enforcePolicyAccess(pkg, "setInterruptionFilter");
                int zen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
                if (zen == -1) {
                    throw new IllegalArgumentException("Invalid filter: " + filter);
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    NotificationManagerService.this.mZenModeHelper.setManualZenMode(zen, null, pkg, "setInterruptionFilter");
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void notifyConditions(final String pkg, IConditionProvider provider, final Condition[] conditions) {
                final ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mConditionProviders.checkServiceToken(provider);
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.10.1
                    @Override // java.lang.Runnable
                    public void run() {
                        NotificationManagerService.this.mConditionProviders.notifyConditions(pkg, info, conditions);
                    }
                });
            }

            public void requestUnbindProvider(IConditionProvider provider) {
                long identity = Binder.clearCallingIdentity();
                try {
                    ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mConditionProviders.checkServiceToken(provider);
                    info.getOwner().setComponentState(info.component, false);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void requestBindProvider(ComponentName component) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(component.getPackageName());
                long identity = Binder.clearCallingIdentity();
                try {
                    NotificationManagerService.this.mConditionProviders.setComponentState(component, true);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            private void enforceSystemOrSystemUI(String message) {
                if (NotificationManagerService.this.isCallerSystemOrPhone()) {
                    return;
                }
                NotificationManagerService.this.getContext().enforceCallingPermission("android.permission.STATUS_BAR_SERVICE", message);
            }

            private void enforceSystemOrSystemUIOrSamePackage(String pkg, String message) {
                try {
                    NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                } catch (SecurityException e) {
                    NotificationManagerService.this.getContext().enforceCallingPermission("android.permission.STATUS_BAR_SERVICE", message);
                }
            }

            private void enforcePolicyAccess(int uid, String method) {
                if (NotificationManagerService.this.getContext().checkCallingPermission("android.permission.MANAGE_NOTIFICATIONS") == 0) {
                    return;
                }
                boolean accessAllowed = false;
                String[] packages = NotificationManagerService.this.getContext().getPackageManager().getPackagesForUid(uid);
                for (String str : packages) {
                    if (NotificationManagerService.this.mConditionProviders.isPackageOrComponentAllowed(str, UserHandle.getUserId(uid))) {
                        accessAllowed = true;
                    }
                }
                if (!accessAllowed) {
                    Slog.w(NotificationManagerService.TAG, "Notification policy access denied calling " + method);
                    throw new SecurityException("Notification policy access denied");
                }
            }

            private void enforcePolicyAccess(String pkg, String method) {
                if (NotificationManagerService.this.getContext().checkCallingPermission("android.permission.MANAGE_NOTIFICATIONS") != 0) {
                    NotificationManagerService.this.checkCallerIsSameApp(pkg);
                    if (!checkPolicyAccess(pkg)) {
                        Slog.w(NotificationManagerService.TAG, "Notification policy access denied calling " + method);
                        throw new SecurityException("Notification policy access denied");
                    }
                }
            }

            private boolean checkPackagePolicyAccess(String pkg) {
                return NotificationManagerService.this.mConditionProviders.isPackageOrComponentAllowed(pkg, getCallingUserHandle().getIdentifier());
            }

            private boolean checkPolicyAccess(String pkg) {
                try {
                    int uid = NotificationManagerService.this.getContext().getPackageManager().getPackageUidAsUser(pkg, UserHandle.getCallingUserId());
                    if (ActivityManager.checkComponentPermission("android.permission.MANAGE_NOTIFICATIONS", uid, -1, true) == 0) {
                        return true;
                    }
                    return checkPackagePolicyAccess(pkg) || NotificationManagerService.this.mListeners.isComponentEnabledForPackage(pkg) || (NotificationManagerService.this.mDpm != null && NotificationManagerService.this.mDpm.isActiveAdminWithPolicy(Binder.getCallingUid(), -1));
                } catch (PackageManager.NameNotFoundException e) {
                    return false;
                }
            }

            protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
                if (DumpUtils.checkDumpAndUsageStatsPermission(NotificationManagerService.this.getContext(), NotificationManagerService.TAG, pw)) {
                    DumpFilter filter = DumpFilter.parseFromArguments(args);
                    if (filter.stats) {
                        NotificationManagerService.this.dumpJson(pw, filter);
                    } else if (filter.proto) {
                        NotificationManagerService.this.dumpProto(fd, filter);
                    } else if (filter.criticalPriority) {
                        NotificationManagerService.this.dumpNotificationRecords(pw, filter);
                    } else {
                        NotificationManagerService.this.dumpImpl(pw, filter);
                    }
                }
            }

            public ComponentName getEffectsSuppressor() {
                if (NotificationManagerService.this.mEffectsSuppressors.isEmpty()) {
                    return null;
                }
                return (ComponentName) NotificationManagerService.this.mEffectsSuppressors.get(0);
            }

            public boolean matchesCallFilter(Bundle extras) {
                enforceSystemOrSystemUI("INotificationManager.matchesCallFilter");
                return NotificationManagerService.this.mZenModeHelper.matchesCallFilter(Binder.getCallingUserHandle(), extras, (ValidateNotificationPeople) NotificationManagerService.this.mRankingHelper.findExtractor(ValidateNotificationPeople.class), NotificationManagerService.MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS, 1.0f);
            }

            public boolean isSystemConditionProviderEnabled(String path) {
                enforceSystemOrSystemUI("INotificationManager.isSystemConditionProviderEnabled");
                return NotificationManagerService.this.mConditionProviders.isSystemProviderEnabled(path);
            }

            public byte[] getBackupPayload(int user) {
                byte[] byteArray;
                NotificationManagerService.this.checkCallerIsSystem();
                if (NotificationManagerService.DBG) {
                    Slog.d(NotificationManagerService.TAG, "getBackupPayload u=" + user);
                }
                if (user == 0) {
                    synchronized (NotificationManagerService.this.mPolicyFile) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try {
                            NotificationManagerService.this.writePolicyXml(baos, true);
                            byteArray = baos.toByteArray();
                        } catch (IOException e) {
                            Slog.w(NotificationManagerService.TAG, "getBackupPayload: error writing payload for user " + user, e);
                            return null;
                        }
                    }
                    return byteArray;
                }
                Slog.w(NotificationManagerService.TAG, "getBackupPayload: cannot backup policy for user " + user);
                return null;
            }

            public void applyRestore(byte[] payload, int user) {
                NotificationManagerService.this.checkCallerIsSystem();
                if (NotificationManagerService.DBG) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("applyRestore u=");
                    sb.append(user);
                    sb.append(" payload=");
                    sb.append(payload != null ? new String(payload, StandardCharsets.UTF_8) : null);
                    Slog.d(NotificationManagerService.TAG, sb.toString());
                }
                if (payload == null) {
                    Slog.w(NotificationManagerService.TAG, "applyRestore: no payload to restore for user " + user);
                } else if (user == 0) {
                    synchronized (NotificationManagerService.this.mPolicyFile) {
                        ByteArrayInputStream bais = new ByteArrayInputStream(payload);
                        try {
                            NotificationManagerService.this.readPolicyXml(bais, true);
                            NotificationManagerService.this.savePolicyFile();
                        } catch (IOException | NumberFormatException | XmlPullParserException e) {
                            Slog.w(NotificationManagerService.TAG, "applyRestore: error reading payload", e);
                        }
                    }
                } else {
                    Slog.w(NotificationManagerService.TAG, "applyRestore: cannot restore policy for user " + user);
                }
            }

            public boolean isNotificationPolicyAccessGranted(String pkg) {
                return checkPolicyAccess(pkg);
            }

            public boolean isNotificationPolicyAccessGrantedForPackage(String pkg) {
                enforceSystemOrSystemUIOrSamePackage(pkg, "request policy access status for another package");
                return checkPolicyAccess(pkg);
            }

            public void setNotificationPolicyAccessGranted(String pkg, boolean granted) throws RemoteException {
                setNotificationPolicyAccessGrantedForUser(pkg, getCallingUserHandle().getIdentifier(), granted);
            }

            public void setNotificationPolicyAccessGrantedForUser(String pkg, int userId, boolean granted) {
                NotificationManagerService.this.checkCallerIsSystemOrShell();
                long identity = Binder.clearCallingIdentity();
                try {
                    if (NotificationManagerService.this.mAllowedManagedServicePackages.test(pkg)) {
                        NotificationManagerService.this.mConditionProviders.setPackageOrComponentEnabled(pkg, userId, true, granted);
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(pkg).addFlags(1073741824), UserHandle.of(userId), null);
                        NotificationManagerService.this.savePolicyFile();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public NotificationManager.Policy getNotificationPolicy(String pkg) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return NotificationManagerService.this.mZenModeHelper.getNotificationPolicy();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void setNotificationPolicy(String pkg, NotificationManager.Policy policy) {
                enforcePolicyAccess(pkg, "setNotificationPolicy");
                long identity = Binder.clearCallingIdentity();
                try {
                    ApplicationInfo applicationInfo = NotificationManagerService.this.mPackageManager.getApplicationInfo(pkg, 0, UserHandle.getUserId(NotificationManagerService.MY_UID));
                    NotificationManager.Policy currPolicy = NotificationManagerService.this.mZenModeHelper.getNotificationPolicy();
                    if (applicationInfo.targetSdkVersion < 28) {
                        int priorityCategories = policy.priorityCategories;
                        policy = new NotificationManager.Policy((priorityCategories & (-33) & (-65) & (-129)) | (currPolicy.priorityCategories & 32) | (currPolicy.priorityCategories & 64) | (currPolicy.priorityCategories & 128), policy.priorityCallSenders, policy.priorityMessageSenders, policy.suppressedVisualEffects);
                    }
                    int newVisualEffects = NotificationManagerService.this.calculateSuppressedVisualEffects(policy, currPolicy, applicationInfo.targetSdkVersion);
                    NotificationManager.Policy policy2 = new NotificationManager.Policy(policy.priorityCategories, policy.priorityCallSenders, policy.priorityMessageSenders, newVisualEffects);
                    ZenLog.traceSetNotificationPolicy(pkg, applicationInfo.targetSdkVersion, policy2);
                    NotificationManagerService.this.mZenModeHelper.setNotificationPolicy(policy2);
                } catch (RemoteException e) {
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(identity);
                    throw th;
                }
                Binder.restoreCallingIdentity(identity);
            }

            public List<String> getEnabledNotificationListenerPackages() {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mListeners.getAllowedPackages(getCallingUserHandle().getIdentifier());
            }

            public List<ComponentName> getEnabledNotificationListeners(int userId) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mListeners.getAllowedComponents(userId);
            }

            public boolean isNotificationListenerAccessGranted(ComponentName listener) {
                Preconditions.checkNotNull(listener);
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(listener.getPackageName());
                return NotificationManagerService.this.mListeners.isPackageOrComponentAllowed(listener.flattenToString(), getCallingUserHandle().getIdentifier());
            }

            public boolean isNotificationListenerAccessGrantedForUser(ComponentName listener, int userId) {
                Preconditions.checkNotNull(listener);
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mListeners.isPackageOrComponentAllowed(listener.flattenToString(), userId);
            }

            public boolean isNotificationAssistantAccessGranted(ComponentName assistant) {
                Preconditions.checkNotNull(assistant);
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(assistant.getPackageName());
                return NotificationManagerService.this.mAssistants.isPackageOrComponentAllowed(assistant.flattenToString(), getCallingUserHandle().getIdentifier());
            }

            public void setNotificationListenerAccessGranted(ComponentName listener, boolean granted) throws RemoteException {
                setNotificationListenerAccessGrantedForUser(listener, getCallingUserHandle().getIdentifier(), granted);
            }

            public void setNotificationAssistantAccessGranted(ComponentName assistant, boolean granted) throws RemoteException {
                setNotificationAssistantAccessGrantedForUser(assistant, getCallingUserHandle().getIdentifier(), granted);
            }

            public void setNotificationListenerAccessGrantedForUser(ComponentName listener, int userId, boolean granted) throws RemoteException {
                Preconditions.checkNotNull(listener);
                NotificationManagerService.this.checkCallerIsSystemOrShell();
                long identity = Binder.clearCallingIdentity();
                try {
                    if (NotificationManagerService.this.mAllowedManagedServicePackages.test(listener.getPackageName())) {
                        NotificationManagerService.this.mConditionProviders.setPackageOrComponentEnabled(listener.flattenToString(), userId, false, granted);
                        NotificationManagerService.this.mListeners.setPackageOrComponentEnabled(listener.flattenToString(), userId, true, granted);
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(listener.getPackageName()).addFlags(1073741824), UserHandle.of(userId), null);
                        NotificationManagerService.this.savePolicyFile();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void setNotificationListenerAccessGrantedForAll(ComponentName listener, boolean granted) throws RemoteException {
                Preconditions.checkNotNull(listener);
                int userId = getCallingUserHandle().getIdentifier();
                int permissionGranted = NotificationManagerService.this.mPackageManagerClient.checkPermission("com.xiaopeng.permission.NOTIFICATION_LISTENER_ACCESS", listener.getPackageName());
                if (permissionGranted != 0) {
                    Log.d(NotificationManagerService.TAG, "setNotificationListenerAccessGrantedForAll permission fail");
                    return;
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    if (NotificationManagerService.this.mAllowedManagedServicePackages.test(listener.getPackageName())) {
                        NotificationManagerService.this.mConditionProviders.setPackageOrComponentEnabled(listener.flattenToString(), userId, false, granted);
                        NotificationManagerService.this.mListeners.setPackageOrComponentEnabled(listener.flattenToString(), userId, true, granted);
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(listener.getPackageName()).addFlags(1073741824), UserHandle.of(userId), null);
                        NotificationManagerService.this.savePolicyFile();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void setNotificationAssistantAccessGrantedForUser(ComponentName assistant, int userId, boolean granted) throws RemoteException {
                Preconditions.checkNotNull(assistant);
                NotificationManagerService.this.checkCallerIsSystemOrShell();
                long identity = Binder.clearCallingIdentity();
                try {
                    if (NotificationManagerService.this.mAllowedManagedServicePackages.test(assistant.getPackageName())) {
                        NotificationManagerService.this.mConditionProviders.setPackageOrComponentEnabled(assistant.flattenToString(), userId, false, granted);
                        NotificationManagerService.this.mAssistants.setPackageOrComponentEnabled(assistant.flattenToString(), userId, true, granted);
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(assistant.getPackageName()).addFlags(1073741824), UserHandle.of(userId), null);
                        NotificationManagerService.this.savePolicyFile();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void applyEnqueuedAdjustmentFromAssistant(INotificationListener token, Adjustment adjustment) throws RemoteException {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        NotificationManagerService.this.mAssistants.checkServiceTokenLocked(token);
                        int N = NotificationManagerService.this.mEnqueuedNotifications.size();
                        int i = 0;
                        while (true) {
                            if (i >= N) {
                                break;
                            }
                            NotificationRecord n = NotificationManagerService.this.mEnqueuedNotifications.get(i);
                            if (Objects.equals(adjustment.getKey(), n.getKey()) && Objects.equals(Integer.valueOf(adjustment.getUser()), Integer.valueOf(n.getUserId()))) {
                                NotificationManagerService.this.applyAdjustment(n, adjustment);
                                break;
                            }
                            i++;
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void applyAdjustmentFromAssistant(INotificationListener token, Adjustment adjustment) throws RemoteException {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        NotificationManagerService.this.mAssistants.checkServiceTokenLocked(token);
                        NotificationRecord n = NotificationManagerService.this.mNotificationsByKey.get(adjustment.getKey());
                        NotificationManagerService.this.applyAdjustment(n, adjustment);
                    }
                    NotificationManagerService.this.mRankingHandler.requestSort();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void applyAdjustmentsFromAssistant(INotificationListener token, List<Adjustment> adjustments) throws RemoteException {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        NotificationManagerService.this.mAssistants.checkServiceTokenLocked(token);
                        for (Adjustment adjustment : adjustments) {
                            NotificationRecord n = NotificationManagerService.this.mNotificationsByKey.get(adjustment.getKey());
                            NotificationManagerService.this.applyAdjustment(n, adjustment);
                        }
                    }
                    NotificationManagerService.this.mRankingHandler.requestSort();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void updateNotificationChannelGroupFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user, NotificationChannelGroup group) throws RemoteException {
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user);
                NotificationManagerService.this.createNotificationChannelGroup(pkg, getUidForPackageAndUser(pkg, user), group, false, true);
                NotificationManagerService.this.savePolicyFile();
            }

            public void updateNotificationChannelFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user, NotificationChannel channel) throws RemoteException {
                Preconditions.checkNotNull(channel);
                Preconditions.checkNotNull(pkg);
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user);
                NotificationManagerService.this.updateNotificationChannelInt(pkg, getUidForPackageAndUser(pkg, user), channel, true);
            }

            public ParceledListSlice<NotificationChannel> getNotificationChannelsFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user) throws RemoteException {
                Preconditions.checkNotNull(pkg);
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user);
                return NotificationManagerService.this.mRankingHelper.getNotificationChannels(pkg, getUidForPackageAndUser(pkg, user), false);
            }

            public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroupsFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user) throws RemoteException {
                Preconditions.checkNotNull(pkg);
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user);
                List<NotificationChannelGroup> groups = new ArrayList<>();
                groups.addAll(NotificationManagerService.this.mRankingHelper.getNotificationChannelGroups(pkg, getUidForPackageAndUser(pkg, user)));
                return new ParceledListSlice<>(groups);
            }

            private void verifyPrivilegedListener(INotificationListener token, UserHandle user) {
                ManagedServices.ManagedServiceInfo info;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                }
                if (!NotificationManagerService.this.hasCompanionDevice(info)) {
                    throw new SecurityException(info + " does not have access");
                } else if (!info.enabledAndUserMatches(user.getIdentifier())) {
                    throw new SecurityException(info + " does not have access");
                }
            }

            private int getUidForPackageAndUser(String pkg, UserHandle user) throws RemoteException {
                long identity = Binder.clearCallingIdentity();
                try {
                    int uid = NotificationManagerService.this.mPackageManager.getPackageUid(pkg, 0, user.getIdentifier());
                    return uid;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            /* JADX WARN: Multi-variable type inference failed */
            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) throws RemoteException {
                new ShellCmd().exec(this, in, out, err, args, callback, resultReceiver);
            }

            public int getUnreadCount(String packageName) {
                int count;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    count = NotificationManagerService.this.mNotificationPolicy.getUnreadCount(packageName, NotificationManagerService.this.mNotificationList);
                }
                return count;
            }

            public List<StatusBarNotification> getNotificationList(int flag) {
                List<StatusBarNotification> notificationList;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    notificationList = NotificationManagerService.this.mNotificationPolicy.getNotificationList(flag, NotificationManagerService.this.mNotificationList);
                }
                return notificationList;
            }

            public void setNotificationNumber(String key, int number) {
                NotificationRecord record;
                if (!TextUtils.isEmpty(key) && number >= 0) {
                    try {
                        synchronized (NotificationManagerService.this.mNotificationLock) {
                            if (NotificationManagerService.this.mNotificationsByKey.containsKey(key) && (record = NotificationManagerService.this.mNotificationsByKey.get(key)) != null && record.sbn != null && record.sbn.getNotification() != null) {
                                int N = record.sbn.getNotification().number;
                                if (number != N) {
                                    NotificationManagerService.this.mNotificationList.remove(record);
                                    NotificationManagerService.this.mNotificationsByKey.remove(key);
                                    if (record != null) {
                                        record.setNotificationNumber(number);
                                    }
                                    NotificationManagerService.this.mNotificationList.add(record);
                                    NotificationManagerService.this.mNotificationsByKey.put(key, record);
                                    if (record != null && record.sbn != null) {
                                        NotificationManagerService.this.mNotificationPolicy.onNotificationUpdated(record.sbn);
                                        NotificationManagerService.this.mNotificationPolicy.sendNotificationNumberAction(record.sbn);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            }

            public void setToastEnabled(boolean enabled) {
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();
                boolean isSystem = callingUid == 1000;
                Log.i(NotificationManagerService.TAG, "setToastEnabled callingUid=" + callingUid + " callingPid=" + callingPid + " system=" + isSystem + " enabled=" + enabled);
                if (isSystem) {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        NotificationManagerService.this.mToastEnabled = enabled;
                    }
                }
            }

            public void setOsdEnabled(boolean enabled) {
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();
                boolean isSystem = callingUid == 1000;
                Log.i(NotificationManagerService.TAG, "setOsdEnabled callingUid=" + callingUid + " callingPid=" + callingPid + " system=" + isSystem + " enabled=" + enabled);
                if (isSystem) {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        NotificationManagerService.this.mOsdEnabled = enabled;
                    }
                }
            }
        };
        this.mInternalService = new AnonymousClass11();
        Notification.processWhitelistToken = WHITELIST_TOKEN;
    }

    @VisibleForTesting
    void setAudioManager(AudioManager audioMananger) {
        this.mAudioManager = audioMananger;
    }

    @VisibleForTesting
    void setHints(int hints) {
        this.mListenerHints = hints;
    }

    @VisibleForTesting
    void setVibrator(Vibrator vibrator) {
        this.mVibrator = vibrator;
    }

    @VisibleForTesting
    void setLights(Light light) {
        this.mNotificationLight = light;
        this.mAttentionLight = light;
        this.mNotificationPulseEnabled = true;
    }

    @VisibleForTesting
    void setScreenOn(boolean on) {
        this.mScreenOn = on;
    }

    @VisibleForTesting
    int getNotificationRecordCount() {
        int count;
        synchronized (this.mNotificationLock) {
            count = this.mNotificationList.size() + this.mNotificationsByKey.size() + this.mSummaryByGroupKey.size() + this.mEnqueuedNotifications.size();
            Iterator<NotificationRecord> it = this.mNotificationList.iterator();
            while (it.hasNext()) {
                NotificationRecord posted = it.next();
                if (this.mNotificationsByKey.containsKey(posted.getKey())) {
                    count--;
                }
                if (posted.sbn.isGroup() && posted.getNotification().isGroupSummary()) {
                    count--;
                }
            }
        }
        return count;
    }

    @VisibleForTesting
    void clearNotifications() {
        this.mEnqueuedNotifications.clear();
        this.mNotificationList.clear();
        this.mNotificationsByKey.clear();
        this.mSummaryByGroupKey.clear();
    }

    @VisibleForTesting
    void addNotification(NotificationRecord r) {
        this.mNotificationList.add(r);
        this.mNotificationsByKey.put(r.sbn.getKey(), r);
        if (r.sbn.isGroup()) {
            this.mSummaryByGroupKey.put(r.getGroupKey(), r);
        }
    }

    @VisibleForTesting
    void addEnqueuedNotification(NotificationRecord r) {
        this.mEnqueuedNotifications.add(r);
    }

    @VisibleForTesting
    NotificationRecord getNotificationRecord(String key) {
        return this.mNotificationsByKey.get(key);
    }

    @VisibleForTesting
    void setSystemReady(boolean systemReady) {
        this.mSystemReady = systemReady;
    }

    @VisibleForTesting
    void setHandler(WorkerHandler handler) {
        this.mHandler = handler;
    }

    @VisibleForTesting
    void setFallbackVibrationPattern(long[] vibrationPattern) {
        this.mFallbackVibrationPattern = vibrationPattern;
    }

    @VisibleForTesting
    void setPackageManager(IPackageManager packageManager) {
        this.mPackageManager = packageManager;
    }

    @VisibleForTesting
    void setRankingHelper(RankingHelper rankingHelper) {
        this.mRankingHelper = rankingHelper;
    }

    @VisibleForTesting
    void setRankingHandler(RankingHandler rankingHandler) {
        this.mRankingHandler = rankingHandler;
    }

    @VisibleForTesting
    void setIsTelevision(boolean isTelevision) {
        this.mIsTelevision = isTelevision;
    }

    @VisibleForTesting
    void setUsageStats(NotificationUsageStats us) {
        this.mUsageStats = us;
    }

    @VisibleForTesting
    void setAccessibilityManager(AccessibilityManager am) {
        this.mAccessibilityManager = am;
    }

    @VisibleForTesting
    void init(Looper looper, IPackageManager packageManager, PackageManager packageManagerClient, LightsManager lightsManager, NotificationListeners notificationListeners, NotificationAssistants notificationAssistants, ConditionProviders conditionProviders, ICompanionDeviceManager companionManager, SnoozeHelper snoozeHelper, NotificationUsageStats usageStats, AtomicFile policyFile, ActivityManager activityManager, GroupHelper groupHelper, IActivityManager am, UsageStatsManagerInternal appUsageStats, DevicePolicyManagerInternal dpm) {
        String[] extractorNames;
        Resources resources = getContext().getResources();
        this.mMaxPackageEnqueueRate = Settings.Global.getFloat(getContext().getContentResolver(), "max_notification_enqueue_rate", 5.0f);
        this.mAccessibilityManager = (AccessibilityManager) getContext().getSystemService("accessibility");
        this.mAm = am;
        this.mPackageManager = packageManager;
        this.mPackageManagerClient = packageManagerClient;
        this.mAppOps = (AppOpsManager) getContext().getSystemService("appops");
        this.mVibrator = (Vibrator) getContext().getSystemService("vibrator");
        this.mAppUsageStats = appUsageStats;
        this.mAlarmManager = (AlarmManager) getContext().getSystemService("alarm");
        this.mCompanionManager = companionManager;
        this.mActivityManager = activityManager;
        this.mDeviceIdleController = IDeviceIdleController.Stub.asInterface(ServiceManager.getService("deviceidle"));
        this.mDpm = dpm;
        this.mHandler = new WorkerHandler(looper);
        this.mRankingThread.start();
        try {
            extractorNames = resources.getStringArray(17236028);
        } catch (Resources.NotFoundException e) {
            extractorNames = new String[0];
        }
        String[] extractorNames2 = extractorNames;
        this.mUsageStats = usageStats;
        this.mMetricsLogger = new MetricsLogger();
        this.mRankingHandler = new RankingHandlerWorker(this.mRankingThread.getLooper());
        this.mConditionProviders = conditionProviders;
        this.mZenModeHelper = new ZenModeHelper(getContext(), this.mHandler.getLooper(), this.mConditionProviders);
        this.mZenModeHelper.addCallback(new ZenModeHelper.Callback() { // from class: com.android.server.notification.NotificationManagerService.7
            @Override // com.android.server.notification.ZenModeHelper.Callback
            public void onConfigChanged() {
                NotificationManagerService.this.savePolicyFile();
            }

            @Override // com.android.server.notification.ZenModeHelper.Callback
            void onZenModeChanged() {
                NotificationManagerService.this.sendRegisteredOnlyBroadcast("android.app.action.INTERRUPTION_FILTER_CHANGED");
                NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.INTERRUPTION_FILTER_CHANGED_INTERNAL").addFlags(67108864), UserHandle.ALL, "android.permission.MANAGE_NOTIFICATIONS");
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationManagerService.this.updateInterruptionFilterLocked();
                }
                NotificationManagerService.this.mRankingHandler.requestSort();
            }

            @Override // com.android.server.notification.ZenModeHelper.Callback
            void onPolicyChanged() {
                NotificationManagerService.this.sendRegisteredOnlyBroadcast("android.app.action.NOTIFICATION_POLICY_CHANGED");
                NotificationManagerService.this.mRankingHandler.requestSort();
            }
        });
        this.mRankingHelper = new RankingHelper(getContext(), this.mPackageManagerClient, this.mRankingHandler, this.mZenModeHelper, this.mUsageStats, extractorNames2);
        this.mSnoozeHelper = snoozeHelper;
        this.mGroupHelper = groupHelper;
        this.mListeners = notificationListeners;
        this.mAssistants = notificationAssistants;
        this.mAllowedManagedServicePackages = new Predicate() { // from class: com.android.server.notification.-$$Lambda$ouaYRM5YVYoMkUW8dm6TnIjLfgg
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return NotificationManagerService.this.canUseManagedServices((String) obj);
            }
        };
        this.mPolicyFile = policyFile;
        loadPolicyFile();
        this.mStatusBar = (StatusBarManagerInternal) getLocalService(StatusBarManagerInternal.class);
        if (this.mStatusBar != null) {
            this.mStatusBar.setNotificationDelegate(this.mNotificationDelegate);
        }
        this.mNotificationLight = lightsManager.getLight(4);
        this.mAttentionLight = lightsManager.getLight(5);
        this.mFallbackVibrationPattern = getLongArray(resources, 17236027, 17, DEFAULT_VIBRATE_PATTERN);
        this.mInCallNotificationUri = Uri.parse("file://" + resources.getString(17039695));
        this.mInCallNotificationAudioAttributes = new AudioAttributes.Builder().setContentType(4).setUsage(2).build();
        this.mInCallNotificationVolume = resources.getFloat(17104982);
        this.mUseAttentionLight = resources.getBoolean(17957064);
        this.mHasLight = resources.getBoolean(17956988);
        boolean z = false;
        if (Settings.Global.getInt(getContext().getContentResolver(), "device_provisioned", 0) == 0) {
            this.mDisableNotificationEffects = true;
        }
        this.mZenModeHelper.initZenMode();
        this.mInterruptionFilter = this.mZenModeHelper.getZenModeListenerInterruptionFilter();
        this.mUserProfiles.updateCache(getContext());
        listenForCallState();
        this.mSettingsObserver = new SettingsObserver(this.mHandler);
        this.mArchive = new Archive(resources.getInteger(17694836));
        this.mIsTelevision = (this.mPackageManagerClient.hasSystemFeature("android.software.leanback") || this.mPackageManagerClient.hasSystemFeature("android.hardware.type.television")) ? true : true;
        this.mNotificationPolicy = new NotificationPolicy(looper, getContext(), this.mRankingHelper, this.mListeners);
        this.mNotificationPolicy.setListener(new NotificationPolicyListener());
        this.mNotificationPolicy.init();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class NotificationPolicyListener implements NotificationPolicy.OnListener {
        private NotificationPolicyListener() {
        }

        @Override // com.android.server.notification.NotificationPolicy.OnListener
        public void onProvidersLoaded(ArrayList<NotificationRecord> list, ArrayMap<String, NotificationRecord> map) {
            NotificationManagerService.this.mergeNotificationList(list, map);
        }
    }

    void mergeNotificationList(ArrayList<NotificationRecord> list, ArrayMap<String, NotificationRecord> map) {
        synchronized (this.mNotificationLock) {
            if (list != null) {
                try {
                    if (!list.isEmpty()) {
                        this.mNotificationList.addAll(list);
                        this.mNotificationsByKey.putAll((ArrayMap<? extends String, ? extends NotificationRecord>) map);
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        SnoozeHelper snoozeHelper = new SnoozeHelper(getContext(), new SnoozeHelper.Callback() { // from class: com.android.server.notification.NotificationManagerService.8
            @Override // com.android.server.notification.SnoozeHelper.Callback
            public void repost(int userId, NotificationRecord r) {
                try {
                    if (NotificationManagerService.DBG) {
                        Slog.d(NotificationManagerService.TAG, "Reposting " + r.getKey());
                    }
                    NotificationManagerService.this.enqueueNotificationInternal(r.sbn.getPackageName(), r.sbn.getOpPkg(), r.sbn.getUid(), r.sbn.getInitialPid(), r.sbn.getTag(), r.sbn.getId(), r.sbn.getNotification(), userId);
                } catch (Exception e) {
                    Slog.e(NotificationManagerService.TAG, "Cannot un-snooze notification", e);
                }
            }
        }, this.mUserProfiles);
        File systemDir = new File(Environment.getDataDirectory(), "system");
        init(Looper.myLooper(), AppGlobals.getPackageManager(), getContext().getPackageManager(), (LightsManager) getLocalService(LightsManager.class), new NotificationListeners(AppGlobals.getPackageManager()), new NotificationAssistants(getContext(), this.mNotificationLock, this.mUserProfiles, AppGlobals.getPackageManager()), new ConditionProviders(getContext(), this.mUserProfiles, AppGlobals.getPackageManager()), null, snoozeHelper, new NotificationUsageStats(getContext()), new AtomicFile(new File(systemDir, "notification_policy.xml"), TAG_NOTIFICATION_POLICY), (ActivityManager) getContext().getSystemService("activity"), getGroupHelper(), ActivityManager.getService(), (UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class), (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class));
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.SCREEN_ON");
        filter.addAction("android.intent.action.SCREEN_OFF");
        filter.addAction("android.intent.action.PHONE_STATE");
        filter.addAction("android.intent.action.USER_PRESENT");
        filter.addAction("android.intent.action.USER_STOPPED");
        filter.addAction("android.intent.action.USER_SWITCHED");
        filter.addAction("android.intent.action.USER_ADDED");
        filter.addAction("android.intent.action.USER_REMOVED");
        filter.addAction("android.intent.action.USER_UNLOCKED");
        filter.addAction("android.intent.action.MANAGED_PROFILE_UNAVAILABLE");
        getContext().registerReceiver(this.mIntentReceiver, filter);
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction("android.intent.action.PACKAGE_ADDED");
        pkgFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        pkgFilter.addAction("android.intent.action.PACKAGE_CHANGED");
        pkgFilter.addAction("android.intent.action.PACKAGE_RESTARTED");
        pkgFilter.addAction("android.intent.action.QUERY_PACKAGE_RESTART");
        pkgFilter.addDataScheme("package");
        getContext().registerReceiverAsUser(this.mPackageIntentReceiver, UserHandle.ALL, pkgFilter, null, null);
        IntentFilter suspendedPkgFilter = new IntentFilter();
        suspendedPkgFilter.addAction("android.intent.action.PACKAGES_SUSPENDED");
        suspendedPkgFilter.addAction("android.intent.action.PACKAGES_UNSUSPENDED");
        getContext().registerReceiverAsUser(this.mPackageIntentReceiver, UserHandle.ALL, suspendedPkgFilter, null, null);
        IntentFilter sdFilter = new IntentFilter("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE");
        getContext().registerReceiverAsUser(this.mPackageIntentReceiver, UserHandle.ALL, sdFilter, null, null);
        IntentFilter timeoutFilter = new IntentFilter(ACTION_NOTIFICATION_TIMEOUT);
        timeoutFilter.addDataScheme(SCHEME_TIMEOUT);
        getContext().registerReceiver(this.mNotificationTimeoutReceiver, timeoutFilter);
        IntentFilter settingsRestoredFilter = new IntentFilter("android.os.action.SETTING_RESTORED");
        getContext().registerReceiver(this.mRestoreReceiver, settingsRestoredFilter);
        IntentFilter localeChangedFilter = new IntentFilter("android.intent.action.LOCALE_CHANGED");
        getContext().registerReceiver(this.mLocaleChangeReceiver, localeChangedFilter);
        publishBinderService("notification", this.mService, false, 5);
        publishLocalService(NotificationManagerInternal.class, this.mInternalService);
    }

    private GroupHelper getGroupHelper() {
        return new GroupHelper(new GroupHelper.Callback() { // from class: com.android.server.notification.NotificationManagerService.9
            @Override // com.android.server.notification.GroupHelper.Callback
            public void addAutoGroup(String key) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationManagerService.this.addAutogroupKeyLocked(key);
                }
            }

            @Override // com.android.server.notification.GroupHelper.Callback
            public void removeAutoGroup(String key) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationManagerService.this.removeAutogroupKeyLocked(key);
                }
            }

            @Override // com.android.server.notification.GroupHelper.Callback
            public void addAutoGroupSummary(int userId, String pkg, String triggeringKey) {
                NotificationManagerService.this.createAutoGroupSummary(userId, pkg, triggeringKey);
            }

            @Override // com.android.server.notification.GroupHelper.Callback
            public void removeAutoGroupSummary(int userId, String pkg) {
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationManagerService.this.clearAutogroupSummaryLocked(userId, pkg);
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendRegisteredOnlyBroadcast(String action) {
        getContext().sendBroadcastAsUser(new Intent(action).addFlags(1073741824), UserHandle.ALL, null);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            this.mSystemReady = true;
            this.mAudioManager = (AudioManager) getContext().getSystemService("audio");
            this.mAudioManagerInternal = (AudioManagerInternal) getLocalService(AudioManagerInternal.class);
            this.mWindowManagerInternal = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
            this.mZenModeHelper.onSystemReady();
            this.mNotificationPolicy.onSystemReady();
        } else if (phase == 600) {
            this.mSettingsObserver.observe();
            this.mListeners.onBootPhaseAppsCanStart();
            this.mAssistants.onBootPhaseAppsCanStart();
            this.mConditionProviders.onBootPhaseAppsCanStart();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void updateListenerHintsLocked() {
        int hints = calculateHints();
        if (hints == this.mListenerHints) {
            return;
        }
        ZenLog.traceListenerHintsChanged(this.mListenerHints, hints, this.mEffectsSuppressors.size());
        this.mListenerHints = hints;
        scheduleListenerHintsChanged(hints);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void updateEffectsSuppressorLocked() {
        long updatedSuppressedEffects = calculateSuppressedEffects();
        if (updatedSuppressedEffects == this.mZenModeHelper.getSuppressedEffects()) {
            return;
        }
        List<ComponentName> suppressors = getSuppressors();
        ZenLog.traceEffectsSuppressorChanged(this.mEffectsSuppressors, suppressors, updatedSuppressedEffects);
        this.mEffectsSuppressors = suppressors;
        this.mZenModeHelper.setSuppressedEffects(updatedSuppressedEffects);
        sendRegisteredOnlyBroadcast("android.os.action.ACTION_EFFECTS_SUPPRESSOR_CHANGED");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void exitIdle() {
        try {
            if (this.mDeviceIdleController != null) {
                this.mDeviceIdleController.exitIdle("notification interaction");
            }
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateNotificationChannelInt(String pkg, int uid, NotificationChannel channel, boolean fromListener) {
        if (channel.getImportance() == 0) {
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0, true, UserHandle.getUserId(uid), 17, null);
            if (isUidSystemOrPhone(uid)) {
                int[] profileIds = this.mUserProfiles.getCurrentProfileIds();
                int N = profileIds.length;
                int i = 0;
                while (true) {
                    int i2 = i;
                    if (i2 >= N) {
                        break;
                    }
                    int profileId = profileIds[i2];
                    cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0, true, profileId, 17, null);
                    i = i2 + 1;
                    profileIds = profileIds;
                    N = N;
                }
            }
        }
        NotificationChannel preUpdate = this.mRankingHelper.getNotificationChannel(pkg, uid, channel.getId(), true);
        this.mRankingHelper.updateNotificationChannel(pkg, uid, channel, true);
        maybeNotifyChannelOwner(pkg, uid, preUpdate, channel);
        if (!fromListener) {
            NotificationChannel modifiedChannel = this.mRankingHelper.getNotificationChannel(pkg, uid, channel.getId(), false);
            this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(uid), modifiedChannel, 2);
        }
        savePolicyFile();
    }

    private void maybeNotifyChannelOwner(String pkg, int uid, NotificationChannel preUpdate, NotificationChannel update) {
        try {
            if ((preUpdate.getImportance() == 0 && update.getImportance() != 0) || (preUpdate.getImportance() != 0 && update.getImportance() == 0)) {
                getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED").putExtra("android.app.extra.NOTIFICATION_CHANNEL_ID", update.getId()).putExtra("android.app.extra.BLOCKED_STATE", update.getImportance() == 0).addFlags(268435456).setPackage(pkg), UserHandle.of(UserHandle.getUserId(uid)), null);
            }
        } catch (SecurityException e) {
            Slog.w(TAG, "Can't notify app about channel change", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group, boolean fromApp, boolean fromListener) {
        Preconditions.checkNotNull(group);
        Preconditions.checkNotNull(pkg);
        NotificationChannelGroup preUpdate = this.mRankingHelper.getNotificationChannelGroup(group.getId(), pkg, uid);
        this.mRankingHelper.createNotificationChannelGroup(pkg, uid, group, fromApp);
        if (!fromApp) {
            maybeNotifyChannelGroupOwner(pkg, uid, preUpdate, group);
        }
        if (!fromListener) {
            this.mListeners.notifyNotificationChannelGroupChanged(pkg, UserHandle.of(UserHandle.getCallingUserId()), group, 1);
        }
    }

    private void maybeNotifyChannelGroupOwner(String pkg, int uid, NotificationChannelGroup preUpdate, NotificationChannelGroup update) {
        try {
            if (preUpdate.isBlocked() != update.isBlocked()) {
                getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED").putExtra("android.app.extra.NOTIFICATION_CHANNEL_GROUP_ID", update.getId()).putExtra("android.app.extra.BLOCKED_STATE", update.isBlocked()).addFlags(268435456).setPackage(pkg), UserHandle.of(UserHandle.getUserId(uid)), null);
            }
        } catch (SecurityException e) {
            Slog.w(TAG, "Can't notify app about group change", e);
        }
    }

    private ArrayList<ComponentName> getSuppressors() {
        ArrayList<ComponentName> names = new ArrayList<>();
        for (int i = this.mListenersDisablingEffects.size() - 1; i >= 0; i--) {
            ArraySet<ManagedServices.ManagedServiceInfo> serviceInfoList = this.mListenersDisablingEffects.valueAt(i);
            Iterator<ManagedServices.ManagedServiceInfo> it = serviceInfoList.iterator();
            while (it.hasNext()) {
                ManagedServices.ManagedServiceInfo info = it.next();
                names.add(info.component);
            }
        }
        return names;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean removeDisabledHints(ManagedServices.ManagedServiceInfo info) {
        return removeDisabledHints(info, 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean removeDisabledHints(ManagedServices.ManagedServiceInfo info, int hints) {
        boolean removed = false;
        for (int i = this.mListenersDisablingEffects.size() - 1; i >= 0; i--) {
            int hint = this.mListenersDisablingEffects.keyAt(i);
            ArraySet<ManagedServices.ManagedServiceInfo> listeners = this.mListenersDisablingEffects.valueAt(i);
            if (hints == 0 || (hint & hints) == hint) {
                removed = removed || listeners.remove(info);
            }
        }
        return removed;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addDisabledHints(ManagedServices.ManagedServiceInfo info, int hints) {
        if ((hints & 1) != 0) {
            addDisabledHint(info, 1);
        }
        if ((hints & 2) != 0) {
            addDisabledHint(info, 2);
        }
        if ((hints & 4) != 0) {
            addDisabledHint(info, 4);
        }
    }

    private void addDisabledHint(ManagedServices.ManagedServiceInfo info, int hint) {
        if (this.mListenersDisablingEffects.indexOfKey(hint) < 0) {
            this.mListenersDisablingEffects.put(hint, new ArraySet<>());
        }
        ArraySet<ManagedServices.ManagedServiceInfo> hintListeners = this.mListenersDisablingEffects.get(hint);
        hintListeners.add(info);
    }

    private int calculateHints() {
        int hints = 0;
        for (int i = this.mListenersDisablingEffects.size() - 1; i >= 0; i--) {
            int hint = this.mListenersDisablingEffects.keyAt(i);
            ArraySet<ManagedServices.ManagedServiceInfo> serviceInfoList = this.mListenersDisablingEffects.valueAt(i);
            if (!serviceInfoList.isEmpty()) {
                hints |= hint;
            }
        }
        return hints;
    }

    private long calculateSuppressedEffects() {
        int hints = calculateHints();
        long suppressedEffects = (hints & 1) != 0 ? 0 | 3 : 0L;
        if ((hints & 2) != 0) {
            suppressedEffects |= 1;
        }
        if ((hints & 4) != 0) {
            return suppressedEffects | 2;
        }
        return suppressedEffects;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void updateInterruptionFilterLocked() {
        int interruptionFilter = this.mZenModeHelper.getZenModeListenerInterruptionFilter();
        if (interruptionFilter == this.mInterruptionFilter) {
            return;
        }
        this.mInterruptionFilter = interruptionFilter;
        scheduleInterruptionFilterChanged(interruptionFilter);
    }

    @VisibleForTesting
    INotificationManager getBinderService() {
        return INotificationManager.Stub.asInterface(this.mService);
    }

    @GuardedBy("mNotificationLock")
    protected void reportSeen(NotificationRecord r) {
        this.mAppUsageStats.reportEvent(r.sbn.getPackageName(), getRealUserId(r.sbn.getUserId()), 10);
    }

    protected int calculateSuppressedVisualEffects(NotificationManager.Policy incomingPolicy, NotificationManager.Policy currPolicy, int targetSdkVersion) {
        if (incomingPolicy.suppressedVisualEffects == -1) {
            return incomingPolicy.suppressedVisualEffects;
        }
        int[] effectsIntroducedInP = {4, 8, 16, 32, 64, 128, 256};
        int newSuppressedVisualEffects = incomingPolicy.suppressedVisualEffects;
        if (targetSdkVersion < 28) {
            while (true) {
                int i = i;
                if (i >= effectsIntroducedInP.length) {
                    break;
                }
                newSuppressedVisualEffects = (newSuppressedVisualEffects & (~effectsIntroducedInP[i])) | (currPolicy.suppressedVisualEffects & effectsIntroducedInP[i]);
                i = i + 1;
            }
            if ((newSuppressedVisualEffects & 1) != 0) {
                newSuppressedVisualEffects = newSuppressedVisualEffects | 8 | 4;
            }
            if ((newSuppressedVisualEffects & 2) != 0) {
                return newSuppressedVisualEffects | 16;
            }
            return newSuppressedVisualEffects;
        }
        if (((newSuppressedVisualEffects + (-2)) - 1 > 0 ? 1 : 0) != 0) {
            int newSuppressedVisualEffects2 = newSuppressedVisualEffects & (-4);
            if ((newSuppressedVisualEffects2 & 16) != 0) {
                newSuppressedVisualEffects2 |= 2;
            }
            if ((newSuppressedVisualEffects2 & 8) != 0 && (newSuppressedVisualEffects2 & 4) != 0 && (newSuppressedVisualEffects2 & 128) != 0) {
                return newSuppressedVisualEffects2 | 1;
            }
            return newSuppressedVisualEffects2;
        }
        if ((newSuppressedVisualEffects & 1) != 0) {
            newSuppressedVisualEffects = newSuppressedVisualEffects | 8 | 4 | 128;
        }
        if ((newSuppressedVisualEffects & 2) != 0) {
            return newSuppressedVisualEffects | 16;
        }
        return newSuppressedVisualEffects;
    }

    @GuardedBy("mNotificationLock")
    protected void maybeRecordInterruptionLocked(NotificationRecord r) {
        if (r.isInterruptive() && !r.hasRecordedInterruption()) {
            this.mAppUsageStats.reportInterruptiveNotification(r.sbn.getPackageName(), r.getChannel().getId(), getRealUserId(r.sbn.getUserId()));
            logRecentLocked(r);
            r.setRecordedInterruption(true);
        }
    }

    protected void reportUserInteraction(NotificationRecord r) {
        this.mAppUsageStats.reportEvent(r.sbn.getPackageName(), getRealUserId(r.sbn.getUserId()), 7);
    }

    private int getRealUserId(int userId) {
        if (userId == -1) {
            return 0;
        }
        return userId;
    }

    @VisibleForTesting
    NotificationManagerInternal getInternalService() {
        return this.mInternalService;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applyAdjustment(NotificationRecord r, Adjustment adjustment) {
        if (r != null && adjustment.getSignals() != null) {
            Bundle.setDefusable(adjustment.getSignals(), true);
            r.addAdjustment(adjustment);
        }
    }

    @GuardedBy("mNotificationLock")
    void addAutogroupKeyLocked(String key) {
        NotificationRecord r = this.mNotificationsByKey.get(key);
        if (r != null && r.sbn.getOverrideGroupKey() == null) {
            addAutoGroupAdjustment(r, "ranker_group");
            EventLogTags.writeNotificationAutogrouped(key);
            this.mRankingHandler.requestSort();
        }
    }

    @GuardedBy("mNotificationLock")
    void removeAutogroupKeyLocked(String key) {
        NotificationRecord r = this.mNotificationsByKey.get(key);
        if (r != null && r.sbn.getOverrideGroupKey() != null) {
            addAutoGroupAdjustment(r, null);
            EventLogTags.writeNotificationUnautogrouped(key);
            this.mRankingHandler.requestSort();
        }
    }

    private void addAutoGroupAdjustment(NotificationRecord r, String overrideGroupKey) {
        Bundle signals = new Bundle();
        signals.putString("key_group_key", overrideGroupKey);
        Adjustment adjustment = new Adjustment(r.sbn.getPackageName(), r.getKey(), signals, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, r.sbn.getUserId());
        r.addAdjustment(adjustment);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void clearAutogroupSummaryLocked(int userId, String pkg) {
        NotificationRecord removed;
        ArrayMap<String, String> summaries = this.mAutobundledSummaries.get(Integer.valueOf(userId));
        if (summaries != null && summaries.containsKey(pkg) && (removed = findNotificationByKeyLocked(summaries.remove(pkg))) != null) {
            boolean wasPosted = removeFromNotificationListsLocked(removed);
            cancelNotificationLocked(removed, false, 16, wasPosted, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public boolean hasAutoGroupSummaryLocked(StatusBarNotification sbn) {
        ArrayMap<String, String> summaries = this.mAutobundledSummaries.get(Integer.valueOf(sbn.getUserId()));
        return summaries != null && summaries.containsKey(sbn.getPackageName());
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:50:0x0166 -> B:48:0x0164). Please submit an issue!!! */
    public void createAutoGroupSummary(int userId, String pkg, String triggeringKey) {
        NotificationRecord summaryRecord;
        synchronized (this.mNotificationLock) {
            try {
                try {
                    NotificationRecord notificationRecord = this.mNotificationsByKey.get(triggeringKey);
                    if (notificationRecord == null) {
                        try {
                            return;
                        } catch (Throwable th) {
                            th = th;
                        }
                    } else {
                        StatusBarNotification adjustedSbn = notificationRecord.sbn;
                        int userId2 = adjustedSbn.getUser().getIdentifier();
                        try {
                            ArrayMap<String, String> summaries = this.mAutobundledSummaries.get(Integer.valueOf(userId2));
                            if (summaries == null) {
                                try {
                                    summaries = new ArrayMap<>();
                                } catch (Throwable th2) {
                                    th = th2;
                                }
                            }
                            this.mAutobundledSummaries.put(Integer.valueOf(userId2), summaries);
                            if (summaries.containsKey(pkg)) {
                                summaryRecord = null;
                            } else {
                                ApplicationInfo appInfo = (ApplicationInfo) adjustedSbn.getNotification().extras.getParcelable("android.appInfo");
                                Bundle extras = new Bundle();
                                extras.putParcelable("android.appInfo", appInfo);
                                String channelId = notificationRecord.getChannel().getId();
                                Notification summaryNotification = new Notification.Builder(getContext(), channelId).setSmallIcon(adjustedSbn.getNotification().getSmallIcon()).setGroupSummary(true).setGroupAlertBehavior(2).setGroup("ranker_group").setFlag(1024, true).setFlag(512, true).setColor(adjustedSbn.getNotification().color).setLocalOnly(true).build();
                                summaryNotification.extras.putAll(extras);
                                Intent appIntent = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
                                if (appIntent != null) {
                                    summaryNotification.contentIntent = PendingIntent.getActivityAsUser(getContext(), 0, appIntent, 0, null, UserHandle.of(userId2));
                                }
                                StatusBarNotification summarySbn = new StatusBarNotification(adjustedSbn.getPackageName(), adjustedSbn.getOpPkg(), Integer.MAX_VALUE, "ranker_group", adjustedSbn.getUid(), adjustedSbn.getInitialPid(), summaryNotification, adjustedSbn.getUser(), "ranker_group", System.currentTimeMillis());
                                try {
                                    NotificationRecord summaryRecord2 = new NotificationRecord(getContext(), summarySbn, notificationRecord.getChannel());
                                    summaryRecord2.setIsAppImportanceLocked(notificationRecord.getIsAppImportanceLocked());
                                    summaries.put(pkg, summarySbn.getKey());
                                    summaryRecord = summaryRecord2;
                                } catch (Throwable th3) {
                                    th = th3;
                                }
                            }
                            try {
                                if (summaryRecord == null || !checkDisqualifyingFeatures(userId2, MY_UID, summaryRecord.sbn.getId(), summaryRecord.sbn.getTag(), summaryRecord, true)) {
                                    return;
                                }
                                this.mHandler.post(new EnqueueNotificationRunnable(userId2, summaryRecord));
                                return;
                            } catch (Throwable th4) {
                                th = th4;
                            }
                        } catch (Throwable th5) {
                            th = th5;
                        }
                    }
                } catch (Throwable th6) {
                    th = th6;
                    throw th;
                }
            } catch (Throwable th7) {
                th = th7;
            }
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String disableNotificationEffects(NotificationRecord record) {
        if (this.mDisableNotificationEffects) {
            return "booleanState";
        }
        if ((this.mListenerHints & 1) != 0) {
            return "listenerHints";
        }
        if (record != null && record.getAudioAttributes() != null) {
            if ((this.mListenerHints & 2) != 0 && record.getAudioAttributes().getUsage() != 2) {
                return "listenerNoti";
            }
            if ((this.mListenerHints & 4) != 0 && record.getAudioAttributes().getUsage() == 2) {
                return "listenerCall";
            }
        }
        if (this.mCallState != 0 && !this.mZenModeHelper.isCall(record)) {
            return "callState";
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpJson(PrintWriter pw, DumpFilter filter) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Notification Manager");
            dump.put("bans", this.mRankingHelper.dumpBansJson(filter));
            dump.put("ranking", this.mRankingHelper.dumpJson(filter));
            dump.put("stats", this.mUsageStats.dumpJson(filter));
            dump.put("channels", this.mRankingHelper.dumpChannelsJson(filter));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pw.println(dump);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpProto(FileDescriptor fd, DumpFilter filter) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (this.mNotificationLock) {
            int N = this.mNotificationList.size();
            int i = 0;
            while (true) {
                int i2 = i;
                if (i2 >= N) {
                    break;
                }
                NotificationRecord nr = this.mNotificationList.get(i2);
                if (!filter.filtered || filter.matches(nr.sbn)) {
                    nr.dump(proto, 2246267895809L, filter.redact, 1);
                }
                i = i2 + 1;
            }
            int N2 = this.mEnqueuedNotifications.size();
            int i3 = 0;
            while (true) {
                int i4 = i3;
                if (i4 >= N2) {
                    break;
                }
                NotificationRecord nr2 = this.mEnqueuedNotifications.get(i4);
                if (!filter.filtered || filter.matches(nr2.sbn)) {
                    nr2.dump(proto, 2246267895809L, filter.redact, 0);
                }
                i3 = i4 + 1;
            }
            List<NotificationRecord> snoozed = this.mSnoozeHelper.getSnoozed();
            int N3 = snoozed.size();
            int i5 = 0;
            while (true) {
                int i6 = i5;
                if (i6 >= N3) {
                    break;
                }
                NotificationRecord nr3 = snoozed.get(i6);
                if (!filter.filtered || filter.matches(nr3.sbn)) {
                    nr3.dump(proto, 2246267895809L, filter.redact, 2);
                }
                i5 = i6 + 1;
            }
            long zenLog = proto.start(1146756268034L);
            this.mZenModeHelper.dump(proto);
            for (ComponentName suppressor : this.mEffectsSuppressors) {
                suppressor.writeToProto(proto, 2246267895812L);
            }
            proto.end(zenLog);
            long listenersToken = proto.start(1146756268035L);
            this.mListeners.dump(proto, filter);
            proto.end(listenersToken);
            proto.write(1120986464260L, this.mListenerHints);
            int i7 = 0;
            while (i7 < this.mListenersDisablingEffects.size()) {
                long effectsToken = proto.start(2246267895813L);
                List<NotificationRecord> snoozed2 = snoozed;
                proto.write(1120986464257L, this.mListenersDisablingEffects.keyAt(i7));
                ArraySet<ManagedServices.ManagedServiceInfo> listeners = this.mListenersDisablingEffects.valueAt(i7);
                int j = 0;
                while (j < listeners.size()) {
                    ManagedServices.ManagedServiceInfo listener = listeners.valueAt(i7);
                    listener.writeToProto(proto, 2246267895810L, null);
                    j++;
                    zenLog = zenLog;
                }
                proto.end(effectsToken);
                i7++;
                snoozed = snoozed2;
                zenLog = zenLog;
            }
            long assistantsToken = proto.start(1146756268038L);
            this.mAssistants.dump(proto, filter);
            proto.end(assistantsToken);
            long conditionsToken = proto.start(1146756268039L);
            this.mConditionProviders.dump(proto, filter);
            proto.end(conditionsToken);
            long rankingToken = proto.start(1146756268040L);
            this.mRankingHelper.dump(proto, filter);
            proto.end(rankingToken);
        }
        proto.flush();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpNotificationRecords(PrintWriter pw, DumpFilter filter) {
        synchronized (this.mNotificationLock) {
            int N = this.mNotificationList.size();
            if (N > 0) {
                pw.println("  Notification List:");
                for (int i = 0; i < N; i++) {
                    NotificationRecord nr = this.mNotificationList.get(i);
                    if (!filter.filtered || filter.matches(nr.sbn)) {
                        nr.dump(pw, "    ", getContext(), filter.redact);
                    }
                }
                pw.println("  ");
            }
        }
    }

    void dumpImpl(PrintWriter pw, DumpFilter filter) {
        pw.print("Current Notification Manager state");
        if (filter.filtered) {
            pw.print(" (filtered to ");
            pw.print(filter);
            pw.print(")");
        }
        pw.println(':');
        boolean zenOnly = filter.filtered && filter.zen;
        if (!zenOnly) {
            synchronized (this.mToastQueue) {
                int N = this.mToastQueue.size();
                if (N > 0) {
                    pw.println("  Toast Queue:");
                    for (int i = 0; i < N; i++) {
                        this.mToastQueue.get(i).dump(pw, "    ", filter);
                    }
                    pw.println("  ");
                }
            }
        }
        synchronized (this.mNotificationLock) {
            if (!zenOnly) {
                try {
                    if (!filter.normalPriority) {
                        dumpNotificationRecords(pw, filter);
                    }
                    if (!filter.filtered) {
                        int N2 = this.mLights.size();
                        if (N2 > 0) {
                            pw.println("  Lights List:");
                            for (int i2 = 0; i2 < N2; i2++) {
                                if (i2 == N2 - 1) {
                                    pw.print("  > ");
                                } else {
                                    pw.print("    ");
                                }
                                pw.println(this.mLights.get(i2));
                            }
                            pw.println("  ");
                        }
                        pw.println("  mUseAttentionLight=" + this.mUseAttentionLight);
                        pw.println("  mHasLight=" + this.mHasLight);
                        pw.println("  mNotificationPulseEnabled=" + this.mNotificationPulseEnabled);
                        pw.println("  mSoundNotificationKey=" + this.mSoundNotificationKey);
                        pw.println("  mVibrateNotificationKey=" + this.mVibrateNotificationKey);
                        pw.println("  mDisableNotificationEffects=" + this.mDisableNotificationEffects);
                        pw.println("  mCallState=" + callStateToString(this.mCallState));
                        pw.println("  mSystemReady=" + this.mSystemReady);
                        pw.println("  mMaxPackageEnqueueRate=" + this.mMaxPackageEnqueueRate);
                    }
                    pw.println("  mArchive=" + this.mArchive.toString());
                    Iterator<StatusBarNotification> iter = this.mArchive.descendingIterator();
                    int j = 0;
                    while (true) {
                        if (!iter.hasNext()) {
                            break;
                        }
                        StatusBarNotification sbn = iter.next();
                        if (filter == null || filter.matches(sbn)) {
                            pw.println("    " + sbn);
                            j++;
                            if (j >= 5) {
                                if (iter.hasNext()) {
                                    pw.println("    ...");
                                }
                            }
                        }
                    }
                    if (!zenOnly) {
                        int N3 = this.mEnqueuedNotifications.size();
                        if (N3 > 0) {
                            pw.println("  Enqueued Notification List:");
                            for (int i3 = 0; i3 < N3; i3++) {
                                NotificationRecord nr = this.mEnqueuedNotifications.get(i3);
                                if (!filter.filtered || filter.matches(nr.sbn)) {
                                    nr.dump(pw, "    ", getContext(), filter.redact);
                                }
                            }
                            pw.println("  ");
                        }
                        this.mSnoozeHelper.dump(pw, filter);
                    }
                } finally {
                }
            }
            if (!zenOnly) {
                pw.println("\n  Ranking Config:");
                this.mRankingHelper.dump(pw, "    ", filter);
                pw.println("\n  Notification listeners:");
                this.mListeners.dump(pw, filter);
                pw.print("    mListenerHints: ");
                pw.println(this.mListenerHints);
                pw.print("    mListenersDisablingEffects: (");
                int N4 = this.mListenersDisablingEffects.size();
                for (int i4 = 0; i4 < N4; i4++) {
                    int hint = this.mListenersDisablingEffects.keyAt(i4);
                    if (i4 > 0) {
                        pw.print(';');
                    }
                    pw.print("hint[" + hint + "]:");
                    ArraySet<ManagedServices.ManagedServiceInfo> listeners = this.mListenersDisablingEffects.valueAt(i4);
                    int listenerSize = listeners.size();
                    for (int j2 = 0; j2 < listenerSize; j2++) {
                        if (i4 > 0) {
                            pw.print(',');
                        }
                        ManagedServices.ManagedServiceInfo listener = listeners.valueAt(i4);
                        if (listener != null) {
                            pw.print(listener.component);
                        }
                    }
                }
                pw.println(')');
                pw.println("\n  Notification assistant services:");
                this.mAssistants.dump(pw, filter);
            }
            if (!filter.filtered || zenOnly) {
                pw.println("\n  Zen Mode:");
                pw.print("    mInterruptionFilter=");
                pw.println(this.mInterruptionFilter);
                this.mZenModeHelper.dump(pw, "    ");
                pw.println("\n  Zen Log:");
                ZenLog.dump(pw, "    ");
            }
            pw.println("\n  Condition providers:");
            this.mConditionProviders.dump(pw, filter);
            pw.println("\n  Group summaries:");
            for (Map.Entry<String, NotificationRecord> entry : this.mSummaryByGroupKey.entrySet()) {
                NotificationRecord r = entry.getValue();
                pw.println("    " + entry.getKey() + " -> " + r.getKey());
                if (this.mNotificationsByKey.get(r.getKey()) != r) {
                    pw.println("!!!!!!LEAK: Record not found in mNotificationsByKey.");
                    r.dump(pw, "      ", getContext(), filter.redact);
                }
            }
            if (!zenOnly) {
                pw.println("\n  Usage Stats:");
                this.mUsageStats.dump(pw, "    ", filter);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.notification.NotificationManagerService$11  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass11 implements NotificationManagerInternal {
        AnonymousClass11() {
        }

        @Override // com.android.server.notification.NotificationManagerInternal
        public NotificationChannel getNotificationChannel(String pkg, int uid, String channelId) {
            return NotificationManagerService.this.mRankingHelper.getNotificationChannel(pkg, uid, channelId, false);
        }

        @Override // com.android.server.notification.NotificationManagerInternal
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid, String tag, int id, Notification notification, int userId) {
            NotificationManagerService.this.enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification, userId);
        }

        @Override // com.android.server.notification.NotificationManagerInternal
        public void removeForegroundServiceFlagFromNotification(final String pkg, final int notificationId, final int userId) {
            NotificationManagerService.this.checkCallerIsSystem();
            NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$11$zVdn9N0ybkMxz8xM8Qa1AXowlic
                @Override // java.lang.Runnable
                public final void run() {
                    NotificationManagerService.AnonymousClass11.lambda$removeForegroundServiceFlagFromNotification$0(NotificationManagerService.AnonymousClass11.this, pkg, notificationId, userId);
                }
            });
        }

        public static /* synthetic */ void lambda$removeForegroundServiceFlagFromNotification$0(AnonymousClass11 anonymousClass11, String pkg, int notificationId, int userId) {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                List<NotificationRecord> enqueued = NotificationManagerService.this.findNotificationsByListLocked(NotificationManagerService.this.mEnqueuedNotifications, pkg, null, notificationId, userId);
                for (int i = 0; i < enqueued.size(); i++) {
                    anonymousClass11.removeForegroundServiceFlagLocked(enqueued.get(i));
                }
                NotificationRecord r = NotificationManagerService.this.findNotificationByListLocked(NotificationManagerService.this.mNotificationList, pkg, null, notificationId, userId);
                if (r != null) {
                    anonymousClass11.removeForegroundServiceFlagLocked(r);
                    NotificationManagerService.this.mRankingHelper.sort(NotificationManagerService.this.mNotificationList);
                    NotificationManagerService.this.mListeners.notifyPostedLocked(r, r);
                }
            }
        }

        @GuardedBy("mNotificationLock")
        private void removeForegroundServiceFlagLocked(NotificationRecord r) {
            if (r == null) {
                return;
            }
            StatusBarNotification sbn = r.sbn;
            sbn.getNotification().flags = r.mOriginalFlags & (-65);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:65:0x01ef  */
    /* JADX WARN: Removed duplicated region for block: B:68:0x0204 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:69:0x0205  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    void enqueueNotificationInternal(java.lang.String r23, java.lang.String r24, int r25, int r26, java.lang.String r27, int r28, android.app.Notification r29, int r30) {
        /*
            Method dump skipped, instructions count: 645
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.enqueueNotificationInternal(java.lang.String, java.lang.String, int, int, java.lang.String, int, android.app.Notification, int):void");
    }

    private void doChannelWarningToast(CharSequence toastText) {
        boolean z = Build.IS_DEBUGGABLE;
        ContentResolver contentResolver = getContext().getContentResolver();
        int defaultWarningEnabled = z ? 1 : 0;
        boolean warningEnabled = Settings.Global.getInt(contentResolver, "show_notification_channel_warnings", defaultWarningEnabled) != 0;
        if (warningEnabled) {
            Toast toast = Toast.makeText(getContext(), this.mHandler.getLooper(), toastText, 0);
            toast.show();
        }
    }

    private int resolveNotificationUid(String opPackageName, int callingUid, int userId) {
        if (isCallerSystemOrPhone() && opPackageName != null && !PackageManagerService.PLATFORM_PACKAGE_NAME.equals(opPackageName)) {
            try {
                return getContext().getPackageManager().getPackageUidAsUser(opPackageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        return callingUid;
    }

    private boolean checkDisqualifyingFeatures(int userId, int callingUid, int id, String tag, NotificationRecord r, boolean isAutogroup) {
        String pkg = r.sbn.getPackageName();
        boolean isSystemNotification = isUidSystemOrPhone(callingUid) || PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkg);
        boolean isNotificationFromListener = this.mListeners.isListenerPackage(pkg);
        if (!isSystemNotification && !isNotificationFromListener) {
            synchronized (this.mNotificationLock) {
                try {
                    try {
                        if (this.mNotificationsByKey.get(r.sbn.getKey()) == null && isCallerInstantApp(pkg)) {
                            throw new SecurityException("Instant app " + pkg + " cannot create notifications");
                        }
                        if (this.mNotificationsByKey.get(r.sbn.getKey()) != null && !r.getNotification().hasCompletedProgress() && !isAutogroup) {
                            float appEnqueueRate = this.mUsageStats.getAppEnqueueRate(pkg);
                            if (appEnqueueRate > this.mMaxPackageEnqueueRate) {
                                this.mUsageStats.registerOverRateQuota(pkg);
                                long now = SystemClock.elapsedRealtime();
                                if (now - this.mLastOverRateLogTime > MIN_PACKAGE_OVERRATE_LOG_INTERVAL) {
                                    Slog.e(TAG, "Package enqueue rate is " + appEnqueueRate + ". Shedding " + r.sbn.getKey() + ". package=" + pkg);
                                    this.mLastOverRateLogTime = now;
                                }
                                return false;
                            }
                        }
                        int count = getNotificationCountLocked(pkg, userId, id, tag);
                        if (count >= 50) {
                            this.mUsageStats.registerOverCountQuota(pkg);
                            Slog.e(TAG, "Package has already posted or enqueued " + count + " notifications.  Not showing more.  package=" + pkg);
                            return false;
                        }
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
        if (!this.mSnoozeHelper.isSnoozed(userId, pkg, r.getKey())) {
            return !isBlocked(r, this.mUsageStats);
        }
        MetricsLogger.action(r.getLogMaker().setType(6).setCategory(831));
        if (DBG) {
            Slog.d(TAG, "Ignored enqueue for snoozed notification " + r.getKey());
        }
        this.mSnoozeHelper.update(userId, r);
        savePolicyFile();
        return false;
    }

    @GuardedBy("mNotificationLock")
    protected int getNotificationCountLocked(String pkg, int userId, int excludedId, String excludedTag) {
        int N = this.mNotificationList.size();
        int count = 0;
        for (int count2 = 0; count2 < N; count2++) {
            NotificationRecord existing = this.mNotificationList.get(count2);
            if (existing.sbn.getPackageName().equals(pkg) && existing.sbn.getUserId() == userId && (existing.sbn.getId() != excludedId || !TextUtils.equals(existing.sbn.getTag(), excludedTag))) {
                count++;
            }
        }
        int M = this.mEnqueuedNotifications.size();
        for (int i = 0; i < M; i++) {
            NotificationRecord existing2 = this.mEnqueuedNotifications.get(i);
            if (existing2.sbn.getPackageName().equals(pkg) && existing2.sbn.getUserId() == userId) {
                count++;
            }
        }
        return count;
    }

    protected boolean isBlocked(NotificationRecord r, NotificationUsageStats usageStats) {
        String pkg = r.sbn.getPackageName();
        int callingUid = r.sbn.getUid();
        boolean isBlocked = this.mRankingHelper.isGroupBlocked(pkg, callingUid, r.getChannel().getGroup()) || this.mRankingHelper.getImportance(pkg, callingUid) == 0 || r.getChannel().getImportance() == 0;
        if (isBlocked) {
            Slog.e(TAG, "Suppressing notification from package by user request.");
            usageStats.registerBlocked(r);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public class SnoozeNotificationRunnable implements Runnable {
        private final long mDuration;
        private final String mKey;
        private final String mSnoozeCriterionId;

        SnoozeNotificationRunnable(String key, long duration, String snoozeCriterionId) {
            this.mKey = key;
            this.mDuration = duration;
            this.mSnoozeCriterionId = snoozeCriterionId;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationRecord r = NotificationManagerService.this.findNotificationByKeyLocked(this.mKey);
                if (r != null) {
                    snoozeLocked(r);
                }
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeLocked(NotificationRecord r) {
            if (r.sbn.isGroup()) {
                List<NotificationRecord> groupNotifications = NotificationManagerService.this.findGroupNotificationsLocked(r.sbn.getPackageName(), r.sbn.getGroupKey(), r.sbn.getUserId());
                int i = 0;
                if (r.getNotification().isGroupSummary()) {
                    while (true) {
                        int i2 = i;
                        if (i2 < groupNotifications.size()) {
                            snoozeNotificationLocked(groupNotifications.get(i2));
                            i = i2 + 1;
                        } else {
                            return;
                        }
                    }
                } else if (NotificationManagerService.this.mSummaryByGroupKey.containsKey(r.sbn.getGroupKey())) {
                    if (groupNotifications.size() != 2) {
                        snoozeNotificationLocked(r);
                        return;
                    }
                    while (true) {
                        int i3 = i;
                        if (i3 < groupNotifications.size()) {
                            snoozeNotificationLocked(groupNotifications.get(i3));
                            i = i3 + 1;
                        } else {
                            return;
                        }
                    }
                } else {
                    snoozeNotificationLocked(r);
                }
            } else {
                snoozeNotificationLocked(r);
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeNotificationLocked(NotificationRecord r) {
            MetricsLogger.action(r.getLogMaker().setCategory(831).setType(2).addTaggedData(1139, Long.valueOf(this.mDuration)).addTaggedData(832, Integer.valueOf(this.mSnoozeCriterionId == null ? 0 : 1)));
            boolean wasPosted = NotificationManagerService.this.removeFromNotificationListsLocked(r);
            NotificationManagerService.this.cancelNotificationLocked(r, false, 18, wasPosted, null);
            NotificationManagerService.this.updateLightsLocked();
            if (this.mSnoozeCriterionId != null) {
                NotificationManagerService.this.mAssistants.notifyAssistantSnoozedLocked(r.sbn, this.mSnoozeCriterionId);
                NotificationManagerService.this.mSnoozeHelper.snooze(r);
            } else {
                NotificationManagerService.this.mSnoozeHelper.snooze(r, this.mDuration);
            }
            r.recordSnoozed();
            NotificationManagerService.this.savePolicyFile();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public class EnqueueNotificationRunnable implements Runnable {
        private final NotificationRecord r;
        private final int userId;

        EnqueueNotificationRunnable(int userId, NotificationRecord r) {
            this.userId = userId;
            this.r = r;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationManagerService.this.mEnqueuedNotifications.add(this.r);
                NotificationManagerService.this.scheduleTimeoutLocked(this.r);
                StatusBarNotification n = this.r.sbn;
                if (NotificationManagerService.DBG) {
                    Slog.d(NotificationManagerService.TAG, "EnqueueNotificationRunnable.run for: " + n.getKey());
                }
                NotificationRecord old = NotificationManagerService.this.mNotificationsByKey.get(n.getKey());
                if (old != null) {
                    this.r.copyRankingInformation(old);
                }
                int callingUid = n.getUid();
                int callingPid = n.getInitialPid();
                Notification notification = n.getNotification();
                String pkg = n.getPackageName();
                int id = n.getId();
                String tag = n.getTag();
                NotificationManagerService.this.handleGroupedNotificationLocked(this.r, old, callingUid, callingPid);
                if (n.isGroup() && notification.isGroupChild()) {
                    NotificationManagerService.this.mSnoozeHelper.repostGroupSummary(pkg, this.r.getUserId(), n.getGroupKey());
                }
                if (!pkg.equals("com.android.providers.downloads") || Log.isLoggable("DownloadManager", 2)) {
                    int enqueueStatus = 0;
                    if (old != null) {
                        enqueueStatus = 1;
                    }
                    EventLogTags.writeNotificationEnqueue(callingUid, callingPid, pkg, id, tag, this.userId, notification.toString(), enqueueStatus);
                }
                NotificationManagerService.this.mRankingHelper.extractSignals(this.r);
                if (NotificationManagerService.this.mAssistants.isEnabled()) {
                    NotificationManagerService.this.mAssistants.onNotificationEnqueued(this.r);
                    NotificationManagerService.this.mHandler.postDelayed(new PostNotificationRunnable(this.r.getKey()), 100L);
                } else {
                    NotificationManagerService.this.mHandler.post(new PostNotificationRunnable(this.r.getKey()));
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public boolean isPackageSuspendedLocked(NotificationRecord r) {
        String pkg = r.sbn.getPackageName();
        int callingUid = r.sbn.getUid();
        return isPackageSuspendedForUser(pkg, callingUid);
    }

    /* loaded from: classes.dex */
    protected class PostNotificationRunnable implements Runnable {
        private final String key;

        PostNotificationRunnable(String key) {
            this.key = key;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationRecord r = null;
                int i = 0;
                int N = NotificationManagerService.this.mEnqueuedNotifications.size();
                int i2 = 0;
                while (true) {
                    if (i2 >= N) {
                        break;
                    }
                    NotificationRecord enqueued = NotificationManagerService.this.mEnqueuedNotifications.get(i2);
                    if (Objects.equals(this.key, enqueued.getKey())) {
                        r = enqueued;
                        break;
                    }
                    i2++;
                }
                if (r == null) {
                    Slog.i(NotificationManagerService.TAG, "Cannot find enqueued record for key: " + this.key);
                    int N2 = NotificationManagerService.this.mEnqueuedNotifications.size();
                    while (true) {
                        if (i >= N2) {
                            break;
                        }
                        NotificationRecord enqueued2 = NotificationManagerService.this.mEnqueuedNotifications.get(i);
                        if (Objects.equals(this.key, enqueued2.getKey())) {
                            NotificationManagerService.this.mEnqueuedNotifications.remove(i);
                            break;
                        }
                        i++;
                    }
                    return;
                }
                boolean isPackageSuspended = NotificationManagerService.this.isPackageSuspendedLocked(r);
                r.setHidden(isPackageSuspended);
                if (isPackageSuspended) {
                    NotificationManagerService.this.mUsageStats.registerSuspendedByAdmin(r);
                }
                NotificationRecord old = NotificationManagerService.this.mNotificationsByKey.get(this.key);
                final StatusBarNotification n = r.sbn;
                Notification notification = n.getNotification();
                int index = NotificationManagerService.this.indexOfNotificationLocked(n.getKey());
                if (index < 0) {
                    NotificationManagerService.this.mNotificationList.add(r);
                    NotificationManagerService.this.mUsageStats.registerPostedByApp(r);
                    r.setInterruptive(NotificationManagerService.this.isVisuallyInterruptive(null, r));
                } else {
                    old = NotificationManagerService.this.mNotificationList.get(index);
                    NotificationManagerService.this.mNotificationList.set(index, r);
                    NotificationManagerService.this.mUsageStats.registerUpdatedByApp(r, old);
                    notification.flags |= old.getNotification().flags & 64;
                    r.isUpdate = true;
                    r.setTextChanged(NotificationManagerService.this.isVisuallyInterruptive(old, r));
                }
                if (!NotificationManagerService.this.mOsdEnabled && notification.hasDisplayFlag(2)) {
                    Slog.i(NotificationManagerService.TAG, "Osd toast cannt run because of Osd toast disabled");
                    int N3 = NotificationManagerService.this.mEnqueuedNotifications.size();
                    while (true) {
                        if (i >= N3) {
                            break;
                        }
                        NotificationRecord enqueued3 = NotificationManagerService.this.mEnqueuedNotifications.get(i);
                        if (Objects.equals(this.key, enqueued3.getKey())) {
                            NotificationManagerService.this.mEnqueuedNotifications.remove(i);
                            break;
                        }
                        i++;
                    }
                    return;
                }
                NotificationManagerService.this.mNotificationsByKey.put(n.getKey(), r);
                if ((notification.flags & 64) != 0) {
                    notification.flags |= 34;
                }
                NotificationManagerService.this.applyZenModeLocked(r);
                NotificationManagerService.this.mRankingHelper.sort(NotificationManagerService.this.mNotificationList);
                if (notification.getSmallIcon() != null) {
                    StatusBarNotification oldSbn = old != null ? old.sbn : null;
                    NotificationManagerService.this.mListeners.notifyPostedLocked(r, old);
                    if (oldSbn == null || !Objects.equals(oldSbn.getGroup(), n.getGroup())) {
                        NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.PostNotificationRunnable.1
                            @Override // java.lang.Runnable
                            public void run() {
                                NotificationManagerService.this.mGroupHelper.onNotificationPosted(n, NotificationManagerService.this.hasAutoGroupSummaryLocked(n));
                            }
                        });
                    }
                } else {
                    Slog.e(NotificationManagerService.TAG, "Not posting notification without small icon: " + notification);
                    if (old != null && !old.isCanceled) {
                        NotificationManagerService.this.mListeners.notifyRemovedLocked(r, 4, null);
                        NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.PostNotificationRunnable.2
                            @Override // java.lang.Runnable
                            public void run() {
                                NotificationManagerService.this.mGroupHelper.onNotificationRemoved(n);
                            }
                        });
                    }
                    Slog.e(NotificationManagerService.TAG, "WARNING: In a future release this will crash the app: " + n.getPackageName());
                }
                if (!r.isHidden()) {
                    NotificationManagerService.this.buzzBeepBlinkLocked(r);
                }
                NotificationManagerService.this.maybeRecordInterruptionLocked(r);
                int N4 = NotificationManagerService.this.mEnqueuedNotifications.size();
                while (true) {
                    if (i >= N4) {
                        break;
                    }
                    NotificationRecord enqueued4 = NotificationManagerService.this.mEnqueuedNotifications.get(i);
                    if (Objects.equals(this.key, enqueued4.getKey())) {
                        NotificationManagerService.this.mEnqueuedNotifications.remove(i);
                        break;
                    }
                    i++;
                }
            }
        }
    }

    @GuardedBy("mNotificationLock")
    @VisibleForTesting
    protected boolean isVisuallyInterruptive(NotificationRecord old, NotificationRecord r) {
        Notification.Builder oldB;
        Notification.Builder newB;
        if (r.sbn.isGroup() && r.sbn.getNotification().isGroupSummary()) {
            if (DEBUG_INTERRUPTIVENESS) {
                Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: summary");
            }
            return false;
        } else if (old == null) {
            if (DEBUG_INTERRUPTIVENESS) {
                Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: new notification");
            }
            return true;
        } else if (r == null) {
            if (DEBUG_INTERRUPTIVENESS) {
                Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: null");
            }
            return false;
        } else {
            Notification oldN = old.sbn.getNotification();
            Notification newN = r.sbn.getNotification();
            if (oldN.extras == null || newN.extras == null) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: no extras");
                }
                return false;
            } else if ((r.sbn.getNotification().flags & 64) != 0) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: foreground service");
                }
                return false;
            } else {
                String oldTitle = String.valueOf(oldN.extras.get("android.title"));
                String newTitle = String.valueOf(newN.extras.get("android.title"));
                if (!Objects.equals(oldTitle, newTitle)) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: changed title");
                        StringBuilder sb = new StringBuilder();
                        sb.append("INTERRUPTIVENESS: ");
                        sb.append(String.format("   old title: %s (%s@0x%08x)", oldTitle, oldTitle.getClass(), Integer.valueOf(oldTitle.hashCode())));
                        Log.v(TAG, sb.toString());
                        Log.v(TAG, "INTERRUPTIVENESS: " + String.format("   new title: %s (%s@0x%08x)", newTitle, newTitle.getClass(), Integer.valueOf(newTitle.hashCode())));
                    }
                    return true;
                }
                String oldText = String.valueOf(oldN.extras.get("android.text"));
                String newText = String.valueOf(newN.extras.get("android.text"));
                if (!Objects.equals(oldText, newText)) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: changed text");
                        StringBuilder sb2 = new StringBuilder();
                        sb2.append("INTERRUPTIVENESS: ");
                        sb2.append(String.format("   old text: %s (%s@0x%08x)", oldText, oldText.getClass(), Integer.valueOf(oldText.hashCode())));
                        Log.v(TAG, sb2.toString());
                        Log.v(TAG, "INTERRUPTIVENESS: " + String.format("   new text: %s (%s@0x%08x)", newText, newText.getClass(), Integer.valueOf(newText.hashCode())));
                        return true;
                    }
                    return true;
                } else if (oldN.hasCompletedProgress() != newN.hasCompletedProgress()) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: completed progress");
                        return true;
                    }
                    return true;
                } else if (Notification.areActionsVisiblyDifferent(oldN, newN)) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: changed actions");
                        return true;
                    }
                    return true;
                } else {
                    try {
                        oldB = Notification.Builder.recoverBuilder(getContext(), oldN);
                        newB = Notification.Builder.recoverBuilder(getContext(), newN);
                    } catch (Exception e) {
                        Slog.w(TAG, "error recovering builder", e);
                    }
                    if (Notification.areStyledNotificationsVisiblyDifferent(oldB, newB)) {
                        if (DEBUG_INTERRUPTIVENESS) {
                            Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: styles differ");
                            return true;
                        }
                        return true;
                    }
                    if (Notification.areRemoteViewsChanged(oldB, newB)) {
                        if (DEBUG_INTERRUPTIVENESS) {
                            Log.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: remoteviews differ");
                            return true;
                        }
                        return true;
                    }
                    return false;
                }
            }
        }
    }

    @GuardedBy("mNotificationLock")
    @VisibleForTesting
    protected void logRecentLocked(NotificationRecord r) {
        if (r.isUpdate) {
            return;
        }
        ArrayList<NotifyingApp> recentAppsForUser = this.mRecentApps.getOrDefault(Integer.valueOf(r.getUser().getIdentifier()), new ArrayList<>(6));
        NotifyingApp na = new NotifyingApp().setPackage(r.sbn.getPackageName()).setUid(r.sbn.getUid()).setLastNotified(r.sbn.getPostTime());
        int i = recentAppsForUser.size() - 1;
        while (true) {
            if (i < 0) {
                break;
            }
            NotifyingApp naExisting = recentAppsForUser.get(i);
            if (!na.getPackage().equals(naExisting.getPackage()) || na.getUid() != naExisting.getUid()) {
                i--;
            } else {
                recentAppsForUser.remove(i);
                break;
            }
        }
        recentAppsForUser.add(0, na);
        if (recentAppsForUser.size() > 5) {
            recentAppsForUser.remove(recentAppsForUser.size() - 1);
        }
        this.mRecentApps.put(Integer.valueOf(r.getUser().getIdentifier()), recentAppsForUser);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void handleGroupedNotificationLocked(NotificationRecord r, NotificationRecord old, int callingUid, int callingPid) {
        NotificationRecord removedSummary;
        StatusBarNotification sbn = r.sbn;
        Notification n = sbn.getNotification();
        if (n.isGroupSummary() && !sbn.isAppGroup()) {
            n.flags &= -513;
        }
        String group = sbn.getGroupKey();
        boolean isSummary = n.isGroupSummary();
        Notification oldN = old != null ? old.sbn.getNotification() : null;
        String oldGroup = old != null ? old.sbn.getGroupKey() : null;
        boolean oldIsSummary = old != null && oldN.isGroupSummary();
        if (oldIsSummary && (removedSummary = this.mSummaryByGroupKey.remove(oldGroup)) != old) {
            String removedKey = removedSummary != null ? removedSummary.getKey() : "<null>";
            Slog.w(TAG, "Removed summary didn't match old notification: old=" + old.getKey() + ", removed=" + removedKey);
        }
        if (isSummary) {
            this.mSummaryByGroupKey.put(group, r);
        }
        if (oldIsSummary) {
            if (!isSummary || !oldGroup.equals(group)) {
                cancelGroupChildrenLocked(old, callingUid, callingPid, null, false, null);
            }
        }
    }

    @GuardedBy("mNotificationLock")
    @VisibleForTesting
    void scheduleTimeoutLocked(NotificationRecord record) {
        if (record.getNotification().getTimeoutAfter() > 0) {
            PendingIntent pi = PendingIntent.getBroadcast(getContext(), 1, new Intent(ACTION_NOTIFICATION_TIMEOUT).setData(new Uri.Builder().scheme(SCHEME_TIMEOUT).appendPath(record.getKey()).build()).addFlags(268435456).putExtra(EXTRA_KEY, record.getKey()), 134217728);
            this.mAlarmManager.setExactAndAllowWhileIdle(2, SystemClock.elapsedRealtime() + record.getNotification().getTimeoutAfter(), pi);
        }
    }

    @GuardedBy("mNotificationLock")
    @VisibleForTesting
    void buzzBeepBlinkLocked(NotificationRecord record) {
        boolean buzz;
        int i;
        boolean beep;
        boolean beep2 = false;
        boolean blink = false;
        Notification notification = record.sbn.getNotification();
        String key = record.getKey();
        boolean aboveThreshold = record.getImportance() >= 3;
        boolean wasBeep = key != null && key.equals(this.mSoundNotificationKey);
        boolean wasBuzz = key != null && key.equals(this.mVibrateNotificationKey);
        boolean hasValidVibrate = false;
        boolean hasValidSound = false;
        boolean sentAccessibilityEvent = false;
        if (!record.isUpdate && record.getImportance() > 1) {
            sendAccessibilityEvent(notification, record.sbn.getPackageName());
            sentAccessibilityEvent = true;
        }
        if (aboveThreshold && isNotificationForCurrentUser(record) && this.mSystemReady && this.mAudioManager != null) {
            Uri soundUri = record.getSound();
            hasValidSound = (soundUri == null || Uri.EMPTY.equals(soundUri)) ? false : true;
            long[] vibration = record.getVibration();
            if (vibration == null && hasValidSound) {
                buzz = false;
                if (this.mAudioManager.getRingerModeInternal() == 1 && this.mAudioManager.getStreamVolume(AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) == 0) {
                    vibration = this.mFallbackVibrationPattern;
                }
            } else {
                buzz = false;
            }
            hasValidVibrate = vibration != null;
            boolean hasAudibleAlert = hasValidSound || hasValidVibrate;
            if (hasAudibleAlert && !shouldMuteNotificationLocked(record)) {
                if (!sentAccessibilityEvent) {
                    sendAccessibilityEvent(notification, record.sbn.getPackageName());
                }
                if (DBG) {
                    Slog.v(TAG, "Interrupting!");
                }
                if (hasValidSound) {
                    this.mSoundNotificationKey = key;
                    if (this.mInCall) {
                        playInCallNotification();
                        beep = true;
                    } else {
                        beep = playSound(record, soundUri);
                    }
                    beep2 = beep;
                }
                boolean ringerModeSilent = this.mAudioManager.getRingerModeInternal() == 0;
                if (!this.mInCall && hasValidVibrate && !ringerModeSilent) {
                    this.mVibrateNotificationKey = key;
                    boolean ringerModeSilent2 = playVibration(record, vibration, hasValidSound);
                    buzz = ringerModeSilent2;
                }
            }
        } else {
            buzz = false;
        }
        if (wasBeep && !hasValidSound) {
            clearSoundLocked();
        }
        if (wasBuzz && !hasValidVibrate) {
            clearVibrateLocked();
        }
        boolean wasShowLights = this.mLights.remove(key);
        if (canShowLightsLocked(record, aboveThreshold)) {
            this.mLights.add(key);
            updateLightsLocked();
            if (this.mUseAttentionLight) {
                this.mAttentionLight.pulse();
            }
            blink = true;
        } else if (wasShowLights) {
            updateLightsLocked();
        }
        if (buzz || beep2 || blink) {
            if (record.sbn.isGroup() && record.sbn.getNotification().isGroupSummary()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Log.v(TAG, "INTERRUPTIVENESS: " + record.getKey() + " is not interruptive: summary");
                }
                i = 1;
            } else {
                if (DEBUG_INTERRUPTIVENESS) {
                    Log.v(TAG, "INTERRUPTIVENESS: " + record.getKey() + " is interruptive: alerted");
                }
                i = 1;
                record.setInterruptive(true);
            }
            MetricsLogger.action(record.getLogMaker().setCategory(199).setType(i).setSubtype((buzz ? i : 0) | (beep2 ? 2 : 0) | (blink ? 4 : 0)));
            int i2 = buzz ? i : 0;
            int i3 = beep2 ? i : 0;
            if (!blink) {
                i = 0;
            }
            EventLogTags.writeNotificationAlert(key, i2, i3, i);
        }
    }

    @GuardedBy("mNotificationLock")
    boolean canShowLightsLocked(NotificationRecord record, boolean aboveThreshold) {
        if (this.mHasLight && this.mNotificationPulseEnabled && record.getLight() != null && aboveThreshold && (record.getSuppressedVisualEffects() & 8) == 0) {
            Notification notification = record.getNotification();
            if (!record.isUpdate || (notification.flags & 8) == 0) {
                return ((record.sbn.isGroup() && record.getNotification().suppressAlertingDueToGrouping()) || this.mInCall || this.mScreenOn) ? false : true;
            }
            return false;
        }
        return false;
    }

    @GuardedBy("mNotificationLock")
    boolean shouldMuteNotificationLocked(NotificationRecord record) {
        Notification notification = record.getNotification();
        if (!record.isUpdate || (notification.flags & 8) == 0) {
            String disableEffects = disableNotificationEffects(record);
            if (disableEffects != null) {
                ZenLog.traceDisableEffects(record, disableEffects);
                return true;
            } else if (record.isIntercepted()) {
                return true;
            } else {
                if (record.sbn.isGroup() && notification.suppressAlertingDueToGrouping()) {
                    return true;
                }
                String pkg = record.sbn.getPackageName();
                if (this.mUsageStats.isAlertRateLimited(pkg)) {
                    Slog.e(TAG, "Muting recently noisy " + record.getKey());
                    return true;
                }
                return false;
            }
        }
        return true;
    }

    private boolean playSound(NotificationRecord record, Uri soundUri) {
        boolean looping = (record.getNotification().flags & 4) != 0;
        if (!this.mAudioManager.isAudioFocusExclusive() && this.mAudioManager.getStreamVolume(AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) != 0) {
            long identity = Binder.clearCallingIdentity();
            try {
                IRingtonePlayer player = this.mAudioManager.getRingtonePlayer();
                if (player != null) {
                    if (DBG) {
                        Slog.v(TAG, "Playing sound " + soundUri + " with attributes " + record.getAudioAttributes());
                    }
                    player.playAsync(soundUri, record.sbn.getUser(), looping, record.getAudioAttributes());
                    Binder.restoreCallingIdentity(identity);
                    return true;
                }
            } catch (RemoteException e) {
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    private boolean playVibration(final NotificationRecord record, long[] vibration, boolean delayVibForSound) {
        long identity = Binder.clearCallingIdentity();
        try {
            boolean insistent = (record.getNotification().flags & 4) != 0;
            final VibrationEffect effect = VibrationEffect.createWaveform(vibration, insistent ? 0 : -1);
            if (delayVibForSound) {
                new Thread(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$VixLwsYU0BcPccdsEjIXUFUzzx4
                    @Override // java.lang.Runnable
                    public final void run() {
                        NotificationManagerService.lambda$playVibration$0(NotificationManagerService.this, record, effect);
                    }
                }).start();
            } else {
                this.mVibrator.vibrate(record.sbn.getUid(), record.sbn.getOpPkg(), effect, record.getAudioAttributes());
            }
            return true;
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Error creating vibration waveform with pattern: " + Arrays.toString(vibration));
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public static /* synthetic */ void lambda$playVibration$0(NotificationManagerService notificationManagerService, NotificationRecord record, VibrationEffect effect) {
        int waitMs = notificationManagerService.mAudioManager.getFocusRampTimeMs(3, record.getAudioAttributes());
        if (DBG) {
            Slog.v(TAG, "Delaying vibration by " + waitMs + "ms");
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
        }
        notificationManagerService.mVibrator.vibrate(record.sbn.getUid(), record.sbn.getOpPkg(), effect, record.getAudioAttributes());
    }

    private boolean isNotificationForCurrentUser(NotificationRecord record) {
        long token = Binder.clearCallingIdentity();
        try {
            int currentUser = ActivityManager.getCurrentUser();
            Binder.restoreCallingIdentity(token);
            return record.getUserId() == -1 || record.getUserId() == currentUser || this.mUserProfiles.isCurrentProfile(record.getUserId());
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(token);
            throw th;
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.notification.NotificationManagerService$12] */
    protected void playInCallNotification() {
        new Thread() { // from class: com.android.server.notification.NotificationManagerService.12
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                long identity = Binder.clearCallingIdentity();
                try {
                    IRingtonePlayer player = NotificationManagerService.this.mAudioManager.getRingtonePlayer();
                    if (player != null) {
                        player.play(new Binder(), NotificationManagerService.this.mInCallNotificationUri, NotificationManagerService.this.mInCallNotificationAudioAttributes, NotificationManagerService.this.mInCallNotificationVolume, false);
                    }
                } catch (RemoteException e) {
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(identity);
                    throw th;
                }
                Binder.restoreCallingIdentity(identity);
            }
        }.start();
    }

    @GuardedBy("mToastQueue")
    void showNextToastLocked() {
        ToastRecord record = this.mToastQueue.get(0);
        while (record != null) {
            if (DBG) {
                Slog.d(TAG, "Show pkg=" + record.pkg + " callback=" + record.callback);
            }
            try {
                record.callback.show(record.token);
                scheduleDurationReachedLocked(record);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Object died trying to show notification " + record.callback + " in package " + record.pkg);
                int index = this.mToastQueue.indexOf(record);
                if (index >= 0) {
                    this.mToastQueue.remove(index);
                }
                keepProcessAliveIfNeededLocked(record.pid);
                if (this.mToastQueue.size() > 0) {
                    ToastRecord record2 = this.mToastQueue.get(0);
                    record = record2;
                } else {
                    record = null;
                }
            }
        }
    }

    @GuardedBy("mToastQueue")
    void cancelToastLocked(int index) {
        ToastRecord record = this.mToastQueue.get(index);
        try {
            record.callback.hide();
        } catch (RemoteException e) {
            Slog.w(TAG, "Object died trying to hide notification " + record.callback + " in package " + record.pkg);
        }
        ToastRecord lastToast = this.mToastQueue.remove(index);
        this.mWindowManagerInternal.removeWindowToken(lastToast.token, false, 0);
        scheduleKillTokenTimeout(lastToast.token);
        keepProcessAliveIfNeededLocked(record.pid);
        if (this.mToastQueue.size() > 0) {
            showNextToastLocked();
        }
    }

    void finishTokenLocked(IBinder t) {
        this.mHandler.removeCallbacksAndMessages(t);
        this.mWindowManagerInternal.removeWindowToken(t, true, 0);
    }

    @GuardedBy("mToastQueue")
    private void scheduleDurationReachedLocked(ToastRecord r) {
        this.mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(this.mHandler, 2, r);
        long delay = xpWindowManager.getToastDurationMillis(r.duration);
        this.mHandler.sendMessageDelayed(m, delay);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDurationReached(ToastRecord record) {
        if (DBG) {
            Slog.d(TAG, "Timeout pkg=" + record.pkg + " callback=" + record.callback);
        }
        synchronized (this.mToastQueue) {
            int index = indexOfToastLocked(record.pkg, record.callback);
            if (index >= 0) {
                cancelToastLocked(index);
            }
        }
    }

    @GuardedBy("mToastQueue")
    private void scheduleKillTokenTimeout(IBinder token) {
        this.mHandler.removeCallbacksAndMessages(token);
        Message m = Message.obtain(this.mHandler, 7, token);
        this.mHandler.sendMessageDelayed(m, 11000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleKillTokenTimeout(IBinder token) {
        if (DBG) {
            Slog.d(TAG, "Kill Token Timeout token=" + token);
        }
        synchronized (this.mToastQueue) {
            finishTokenLocked(token);
        }
    }

    @GuardedBy("mToastQueue")
    int indexOfToastLocked(String pkg, ITransientNotification callback) {
        IBinder cbak = callback.asBinder();
        ArrayList<ToastRecord> list = this.mToastQueue;
        int len = list.size();
        for (int i = 0; i < len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.callback.asBinder().equals(cbak)) {
                return i;
            }
        }
        return -1;
    }

    @GuardedBy("mToastQueue")
    int indexOfToastPackageLocked(String pkg) {
        ArrayList<ToastRecord> list = this.mToastQueue;
        int len = list.size();
        for (int i = 0; i < len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg)) {
                return i;
            }
        }
        return -1;
    }

    @GuardedBy("mToastQueue")
    void keepProcessAliveIfNeededLocked(int pid) {
        ArrayList<ToastRecord> list = this.mToastQueue;
        int N = list.size();
        int toastCount = 0;
        for (int toastCount2 = 0; toastCount2 < N; toastCount2++) {
            ToastRecord r = list.get(toastCount2);
            if (r.pid == pid) {
                toastCount++;
            }
        }
        try {
            this.mAm.setProcessImportant(this.mForegroundToken, pid, toastCount > 0, "toast");
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:26:0x0069  */
    /* JADX WARN: Removed duplicated region for block: B:32:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void handleRankingReconsideration(android.os.Message r14) {
        /*
            r13 = this;
            java.lang.Object r0 = r14.obj
            boolean r0 = r0 instanceof com.android.server.notification.RankingReconsideration
            if (r0 != 0) goto L7
            return
        L7:
            java.lang.Object r0 = r14.obj
            com.android.server.notification.RankingReconsideration r0 = (com.android.server.notification.RankingReconsideration) r0
            r0.run()
            java.lang.Object r1 = r13.mNotificationLock
            monitor-enter(r1)
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r2 = r13.mNotificationsByKey     // Catch: java.lang.Throwable -> L6f
            java.lang.String r3 = r0.getKey()     // Catch: java.lang.Throwable -> L6f
            java.lang.Object r2 = r2.get(r3)     // Catch: java.lang.Throwable -> L6f
            com.android.server.notification.NotificationRecord r2 = (com.android.server.notification.NotificationRecord) r2     // Catch: java.lang.Throwable -> L6f
            if (r2 != 0) goto L21
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L6f
            return
        L21:
            int r3 = r13.findNotificationRecordIndexLocked(r2)     // Catch: java.lang.Throwable -> L6f
            boolean r4 = r2.isIntercepted()     // Catch: java.lang.Throwable -> L6f
            float r5 = r2.getContactAffinity()     // Catch: java.lang.Throwable -> L6f
            int r6 = r2.getPackageVisibilityOverride()     // Catch: java.lang.Throwable -> L6f
            r0.applyChangesLocked(r2)     // Catch: java.lang.Throwable -> L6f
            r13.applyZenModeLocked(r2)     // Catch: java.lang.Throwable -> L6f
            com.android.server.notification.RankingHelper r7 = r13.mRankingHelper     // Catch: java.lang.Throwable -> L6f
            java.util.ArrayList<com.android.server.notification.NotificationRecord> r8 = r13.mNotificationList     // Catch: java.lang.Throwable -> L6f
            r7.sort(r8)     // Catch: java.lang.Throwable -> L6f
            int r7 = r13.findNotificationRecordIndexLocked(r2)     // Catch: java.lang.Throwable -> L6f
            boolean r8 = r2.isIntercepted()     // Catch: java.lang.Throwable -> L6f
            float r9 = r2.getContactAffinity()     // Catch: java.lang.Throwable -> L6f
            int r10 = r2.getPackageVisibilityOverride()     // Catch: java.lang.Throwable -> L6f
            if (r3 != r7) goto L57
            if (r4 != r8) goto L57
            if (r6 == r10) goto L55
            goto L57
        L55:
            r11 = 0
            goto L58
        L57:
            r11 = 1
        L58:
            if (r4 == 0) goto L65
            if (r8 != 0) goto L65
            int r12 = java.lang.Float.compare(r5, r9)     // Catch: java.lang.Throwable -> L6f
            if (r12 == 0) goto L65
            r13.buzzBeepBlinkLocked(r2)     // Catch: java.lang.Throwable -> L6f
        L65:
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L6f
            r1 = r11
            if (r1 == 0) goto L6e
            com.android.server.notification.NotificationManagerService$WorkerHandler r2 = r13.mHandler
            r2.scheduleSendRankingUpdate()
        L6e:
            return
        L6f:
            r2 = move-exception
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L6f
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.handleRankingReconsideration(android.os.Message):void");
    }

    void handleRankingSort() {
        if (this.mRankingHelper == null) {
            return;
        }
        synchronized (this.mNotificationLock) {
            int N = this.mNotificationList.size();
            ArrayList<String> orderBefore = new ArrayList<>(N);
            int[] visibilities = new int[N];
            boolean[] showBadges = new boolean[N];
            ArrayList<NotificationChannel> channelBefore = new ArrayList<>(N);
            ArrayList<String> groupKeyBefore = new ArrayList<>(N);
            ArrayList<ArrayList<String>> overridePeopleBefore = new ArrayList<>(N);
            ArrayList<ArrayList<SnoozeCriterion>> snoozeCriteriaBefore = new ArrayList<>(N);
            ArrayList<Integer> userSentimentBefore = new ArrayList<>(N);
            ArrayList<Integer> suppressVisuallyBefore = new ArrayList<>(N);
            for (int i = 0; i < N; i++) {
                NotificationRecord r = this.mNotificationList.get(i);
                orderBefore.add(r.getKey());
                visibilities[i] = r.getPackageVisibilityOverride();
                showBadges[i] = r.canShowBadge();
                channelBefore.add(r.getChannel());
                groupKeyBefore.add(r.getGroupKey());
                overridePeopleBefore.add(r.getPeopleOverride());
                snoozeCriteriaBefore.add(r.getSnoozeCriteria());
                userSentimentBefore.add(Integer.valueOf(r.getUserSentiment()));
                suppressVisuallyBefore.add(Integer.valueOf(r.getSuppressedVisualEffects()));
                this.mRankingHelper.extractSignals(r);
            }
            this.mRankingHelper.sort(this.mNotificationList);
            for (int i2 = 0; i2 < N; i2++) {
                NotificationRecord r2 = this.mNotificationList.get(i2);
                if (orderBefore.get(i2).equals(r2.getKey()) && visibilities[i2] == r2.getPackageVisibilityOverride() && showBadges[i2] == r2.canShowBadge() && Objects.equals(channelBefore.get(i2), r2.getChannel()) && Objects.equals(groupKeyBefore.get(i2), r2.getGroupKey()) && Objects.equals(overridePeopleBefore.get(i2), r2.getPeopleOverride()) && Objects.equals(snoozeCriteriaBefore.get(i2), r2.getSnoozeCriteria()) && Objects.equals(userSentimentBefore.get(i2), Integer.valueOf(r2.getUserSentiment())) && Objects.equals(suppressVisuallyBefore.get(i2), Integer.valueOf(r2.getSuppressedVisualEffects()))) {
                }
                this.mHandler.scheduleSendRankingUpdate();
                return;
            }
        }
    }

    @GuardedBy("mNotificationLock")
    private void recordCallerLocked(NotificationRecord record) {
        if (this.mZenModeHelper.isCall(record)) {
            this.mZenModeHelper.recordCaller(record);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void applyZenModeLocked(NotificationRecord record) {
        record.setIntercepted(this.mZenModeHelper.shouldIntercept(record));
        if (record.isIntercepted()) {
            record.setSuppressedVisualEffects(this.mZenModeHelper.getNotificationPolicy().suppressedVisualEffects);
        } else {
            record.setSuppressedVisualEffects(0);
        }
    }

    @GuardedBy("mNotificationLock")
    private int findNotificationRecordIndexLocked(NotificationRecord target) {
        return this.mRankingHelper.indexOf(this.mNotificationList, target);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSendRankingUpdate() {
        synchronized (this.mNotificationLock) {
            this.mListeners.notifyRankingUpdateLocked(null);
        }
    }

    private void scheduleListenerHintsChanged(int state) {
        this.mHandler.removeMessages(5);
        this.mHandler.obtainMessage(5, state, 0).sendToTarget();
    }

    private void scheduleInterruptionFilterChanged(int listenerInterruptionFilter) {
        this.mHandler.removeMessages(6);
        this.mHandler.obtainMessage(6, listenerInterruptionFilter, 0).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleListenerHintsChanged(int hints) {
        synchronized (this.mNotificationLock) {
            this.mListeners.notifyListenerHintsChangedLocked(hints);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleListenerInterruptionFilterChanged(int interruptionFilter) {
        synchronized (this.mNotificationLock) {
            this.mListeners.notifyInterruptionFilterChanged(interruptionFilter);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public class WorkerHandler extends Handler {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 2:
                    NotificationManagerService.this.handleDurationReached((ToastRecord) msg.obj);
                    return;
                case 3:
                    NotificationManagerService.this.handleSavePolicyFile();
                    return;
                case 4:
                    NotificationManagerService.this.handleSendRankingUpdate();
                    return;
                case 5:
                    NotificationManagerService.this.handleListenerHintsChanged(msg.arg1);
                    return;
                case 6:
                    NotificationManagerService.this.handleListenerInterruptionFilterChanged(msg.arg1);
                    return;
                case 7:
                    NotificationManagerService.this.handleKillTokenTimeout((IBinder) msg.obj);
                    return;
                default:
                    return;
            }
        }

        protected void scheduleSendRankingUpdate() {
            if (!hasMessages(4)) {
                Message m = Message.obtain(this, 4);
                sendMessage(m);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class RankingHandlerWorker extends Handler implements RankingHandler {
        public RankingHandlerWorker(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1000:
                    NotificationManagerService.this.handleRankingReconsideration(msg);
                    return;
                case 1001:
                    NotificationManagerService.this.handleRankingSort();
                    return;
                default:
                    return;
            }
        }

        @Override // com.android.server.notification.RankingHandler
        public void requestSort() {
            removeMessages(1001);
            Message msg = Message.obtain();
            msg.what = 1001;
            sendMessage(msg);
        }

        @Override // com.android.server.notification.RankingHandler
        public void requestReconsideration(RankingReconsideration recon) {
            Message m = Message.obtain(this, 1000, recon);
            long delay = recon.getDelay(TimeUnit.MILLISECONDS);
            sendMessageDelayed(m, delay);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int clamp(int x, int low, int high) {
        return x < low ? low : x > high ? high : x;
    }

    void sendAccessibilityEvent(Notification notification, CharSequence packageName) {
        if (!this.mAccessibilityManager.isEnabled()) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain(64);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        event.setParcelableData(notification);
        CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }
        this.mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public boolean removeFromNotificationListsLocked(NotificationRecord r) {
        boolean wasPosted = false;
        NotificationRecord recordInList = findNotificationByListLocked(this.mNotificationList, r.getKey());
        if (recordInList != null) {
            this.mNotificationList.remove(recordInList);
            this.mNotificationsByKey.remove(recordInList.sbn.getKey());
            wasPosted = true;
        }
        while (true) {
            NotificationRecord recordInList2 = findNotificationByListLocked(this.mEnqueuedNotifications, r.getKey());
            if (recordInList2 != null) {
                this.mEnqueuedNotifications.remove(recordInList2);
            } else {
                return wasPosted;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void cancelNotificationLocked(NotificationRecord r, boolean sendDelete, int reason, boolean wasPosted, String listenerName) {
        cancelNotificationLocked(r, sendDelete, reason, -1, -1, wasPosted, listenerName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void cancelNotificationLocked(final NotificationRecord r, boolean sendDelete, int reason, int rank, int count, boolean wasPosted, String listenerName) {
        long identity;
        String canceledKey = r.getKey();
        recordCallerLocked(r);
        if (r.getStats().getDismissalSurface() == -1) {
            r.recordDismissalSurface(0);
        }
        if (sendDelete && r.getNotification().deleteIntent != null) {
            try {
                r.getNotification().deleteIntent.send();
            } catch (PendingIntent.CanceledException ex) {
                Slog.w(TAG, "canceled PendingIntent for " + r.sbn.getPackageName(), ex);
            }
        }
        if (wasPosted) {
            if (r.getNotification().getSmallIcon() != null) {
                if (reason != 18) {
                    r.isCanceled = true;
                }
                this.mListeners.notifyRemovedLocked(r, reason, r.getStats());
                this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.13
                    @Override // java.lang.Runnable
                    public void run() {
                        NotificationManagerService.this.mGroupHelper.onNotificationRemoved(r.sbn);
                    }
                });
            }
            if (canceledKey.equals(this.mSoundNotificationKey)) {
                this.mSoundNotificationKey = null;
                identity = Binder.clearCallingIdentity();
                try {
                    IRingtonePlayer player = this.mAudioManager.getRingtonePlayer();
                    if (player != null) {
                        player.stopAsync();
                    }
                } catch (RemoteException e) {
                } catch (Throwable th) {
                    throw th;
                }
                Binder.restoreCallingIdentity(identity);
            }
            if (canceledKey.equals(this.mVibrateNotificationKey)) {
                this.mVibrateNotificationKey = null;
                identity = Binder.clearCallingIdentity();
                try {
                    this.mVibrator.cancel();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            this.mLights.remove(canceledKey);
        }
        switch (reason) {
            default:
                switch (reason) {
                    case 8:
                    case 9:
                        this.mUsageStats.registerRemovedByApp(r);
                        break;
                }
            case 2:
            case 3:
                this.mUsageStats.registerDismissedByUser(r);
                break;
        }
        String groupKey = r.getGroupKey();
        NotificationRecord groupSummary = this.mSummaryByGroupKey.get(groupKey);
        if (groupSummary != null && groupSummary.getKey().equals(canceledKey)) {
            this.mSummaryByGroupKey.remove(groupKey);
        }
        ArrayMap<String, String> summaries = this.mAutobundledSummaries.get(Integer.valueOf(r.sbn.getUserId()));
        if (summaries != null && r.sbn.getKey().equals(summaries.get(r.sbn.getPackageName()))) {
            summaries.remove(r.sbn.getPackageName());
        }
        this.mArchive.record(r.sbn);
        long now = System.currentTimeMillis();
        LogMaker logMaker = r.getLogMaker(now).setCategory(128).setType(5).setSubtype(reason);
        if (rank != -1 && count != -1) {
            logMaker.addTaggedData(798, Integer.valueOf(rank)).addTaggedData(1395, Integer.valueOf(count));
            MetricsLogger.action(logMaker);
            EventLogTags.writeNotificationCanceled(canceledKey, reason, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now), rank, count, listenerName);
        }
        MetricsLogger.action(logMaker);
        EventLogTags.writeNotificationCanceled(canceledKey, reason, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now), rank, count, listenerName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:57:0x00d7  */
    /* JADX WARN: Removed duplicated region for block: B:70:0x0120  */
    /* JADX WARN: Removed duplicated region for block: B:80:0x0157  */
    /* JADX WARN: Removed duplicated region for block: B:86:0x0114 A[EDGE_INSN: B:86:0x0114->B:66:0x0114 ?: BREAK  , SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:89:0x0155 A[EDGE_INSN: B:89:0x0155->B:79:0x0155 ?: BREAK  , SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:92:? A[RETURN, SYNTHETIC] */
    @com.android.internal.annotations.VisibleForTesting
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void updateUriPermissions(com.android.server.notification.NotificationRecord r17, com.android.server.notification.NotificationRecord r18, java.lang.String r19, int r20) {
        /*
            Method dump skipped, instructions count: 346
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.updateUriPermissions(com.android.server.notification.NotificationRecord, com.android.server.notification.NotificationRecord, java.lang.String, int):void");
    }

    private void grantUriPermission(IBinder owner, Uri uri, int sourceUid, String targetPkg, int targetUserId) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mAm.grantUriPermissionFromOwner(owner, sourceUid, targetPkg, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)), targetUserId);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    private void revokeUriPermission(IBinder owner, Uri uri, int sourceUid) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mAm.revokeUriPermissionFromOwner(owner, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    void cancelNotification(int callingUid, int callingPid, String pkg, String tag, int id, int mustHaveFlags, int mustNotHaveFlags, boolean sendDelete, int userId, int reason, ManagedServices.ManagedServiceInfo listener) {
        cancelNotification(callingUid, callingPid, pkg, tag, id, mustHaveFlags, mustNotHaveFlags, sendDelete, userId, reason, -1, -1, listener);
    }

    void cancelNotification(final int callingUid, final int callingPid, final String pkg, final String tag, final int id, final int mustHaveFlags, final int mustNotHaveFlags, final boolean sendDelete, final int userId, final int reason, final int rank, final int count, final ManagedServices.ManagedServiceInfo listener) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.14
            @Override // java.lang.Runnable
            public void run() {
                String listenerName = listener == null ? null : listener.component.toShortString();
                if (NotificationManagerService.DBG) {
                    EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, id, tag, userId, mustHaveFlags, mustNotHaveFlags, reason, listenerName);
                }
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    NotificationRecord r = NotificationManagerService.this.findNotificationLocked(pkg, tag, id, userId);
                    if (r != null) {
                        if (reason == 1) {
                            NotificationManagerService.this.mUsageStats.registerClickedByUser(r);
                        }
                        if (!NotificationManagerService.this.mNotificationPolicy.hasClearFlag(r.getNotification())) {
                            return;
                        }
                        if ((r.getNotification().flags & mustHaveFlags) != mustHaveFlags) {
                            return;
                        }
                        if ((r.getNotification().flags & mustNotHaveFlags) != 0) {
                            return;
                        }
                        boolean wasPosted = NotificationManagerService.this.removeFromNotificationListsLocked(r);
                        NotificationManagerService.this.cancelNotificationLocked(r, sendDelete, reason, rank, count, wasPosted, listenerName);
                        NotificationManagerService.this.cancelGroupChildrenLocked(r, callingUid, callingPid, listenerName, sendDelete, null);
                        NotificationManagerService.this.updateLightsLocked();
                    } else if (reason != 18) {
                        boolean wasSnoozed = NotificationManagerService.this.mSnoozeHelper.cancel(userId, pkg, tag, id);
                        if (wasSnoozed) {
                            NotificationManagerService.this.savePolicyFile();
                        }
                    }
                }
            }
        });
    }

    private boolean notificationMatchesUserId(NotificationRecord r, int userId) {
        return userId == -1 || r.getUserId() == -1 || r.getUserId() == userId;
    }

    private boolean notificationMatchesCurrentProfiles(NotificationRecord r, int userId) {
        return notificationMatchesUserId(r, userId) || this.mUserProfiles.isCurrentProfile(r.getUserId());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.notification.NotificationManagerService$15  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass15 implements Runnable {
        final /* synthetic */ int val$callingPid;
        final /* synthetic */ int val$callingUid;
        final /* synthetic */ String val$channelId;
        final /* synthetic */ boolean val$doit;
        final /* synthetic */ ManagedServices.ManagedServiceInfo val$listener;
        final /* synthetic */ int val$mustHaveFlags;
        final /* synthetic */ int val$mustNotHaveFlags;
        final /* synthetic */ String val$pkg;
        final /* synthetic */ int val$reason;
        final /* synthetic */ int val$userId;

        AnonymousClass15(ManagedServices.ManagedServiceInfo managedServiceInfo, int i, int i2, String str, int i3, int i4, int i5, int i6, boolean z, String str2) {
            this.val$listener = managedServiceInfo;
            this.val$callingUid = i;
            this.val$callingPid = i2;
            this.val$pkg = str;
            this.val$userId = i3;
            this.val$mustHaveFlags = i4;
            this.val$mustNotHaveFlags = i5;
            this.val$reason = i6;
            this.val$doit = z;
            this.val$channelId = str2;
        }

        @Override // java.lang.Runnable
        public void run() {
            String listenerName = this.val$listener == null ? null : this.val$listener.component.toShortString();
            EventLogTags.writeNotificationCancelAll(this.val$callingUid, this.val$callingPid, this.val$pkg, this.val$userId, this.val$mustHaveFlags, this.val$mustNotHaveFlags, this.val$reason, listenerName);
            if (!this.val$doit) {
                return;
            }
            synchronized (NotificationManagerService.this.mNotificationLock) {
                final int i = this.val$mustHaveFlags;
                final int i2 = this.val$mustNotHaveFlags;
                FlagChecker flagChecker = new FlagChecker() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$15$wXaTmmz_lG6grUqU8upk0686eXA
                    @Override // com.android.server.notification.NotificationManagerService.FlagChecker
                    public final boolean apply(int i3) {
                        return NotificationManagerService.AnonymousClass15.lambda$run$0(i, i2, i3);
                    }
                };
                NotificationManagerService.this.cancelAllNotificationsByListLocked(NotificationManagerService.this.mNotificationList, this.val$callingUid, this.val$callingPid, this.val$pkg, true, this.val$channelId, flagChecker, false, this.val$userId, false, this.val$reason, listenerName, true);
                NotificationManagerService.this.cancelAllNotificationsByListLocked(NotificationManagerService.this.mEnqueuedNotifications, this.val$callingUid, this.val$callingPid, this.val$pkg, true, this.val$channelId, flagChecker, false, this.val$userId, false, this.val$reason, listenerName, false);
                NotificationManagerService.this.mSnoozeHelper.cancel(this.val$userId, this.val$pkg);
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$run$0(int mustHaveFlags, int mustNotHaveFlags, int flags) {
            return (flags & mustHaveFlags) == mustHaveFlags && (flags & mustNotHaveFlags) == 0;
        }
    }

    void cancelAllNotificationsInt(int callingUid, int callingPid, String pkg, String channelId, int mustHaveFlags, int mustNotHaveFlags, boolean doit, int userId, int reason, ManagedServices.ManagedServiceInfo listener) {
        this.mHandler.post(new AnonymousClass15(listener, callingUid, callingPid, pkg, userId, mustHaveFlags, mustNotHaveFlags, reason, doit, channelId));
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void cancelAllNotificationsByListLocked(ArrayList<NotificationRecord> notificationList, int callingUid, int callingPid, String pkg, boolean nullPkgIndicatesUserSwitch, String channelId, FlagChecker flagChecker, boolean includeCurrentProfiles, int userId, boolean sendDelete, int reason, String listenerName, boolean wasPosted) {
        int i = notificationList.size() - 1;
        ArrayList<NotificationRecord> canceledNotifications = null;
        while (true) {
            int i2 = i;
            if (i2 < 0) {
                break;
            }
            NotificationRecord r = notificationList.get(i2);
            if (!includeCurrentProfiles ? notificationMatchesUserId(r, userId) : notificationMatchesCurrentProfiles(r, userId)) {
                if (!nullPkgIndicatesUserSwitch || pkg != null || r.getUserId() != -1) {
                    if (flagChecker.apply(r.getFlags()) && ((pkg == null || r.sbn.getPackageName().equals(pkg)) && (channelId == null || channelId.equals(r.getChannel().getId())))) {
                        if (canceledNotifications == null) {
                            canceledNotifications = new ArrayList<>();
                        }
                        notificationList.remove(i2);
                        this.mNotificationsByKey.remove(r.getKey());
                        canceledNotifications.add(r);
                        cancelNotificationLocked(r, sendDelete, reason, wasPosted, listenerName);
                    }
                    i = i2 - 1;
                }
            }
            i = i2 - 1;
        }
        if (canceledNotifications != null) {
            int M = canceledNotifications.size();
            int i3 = 0;
            while (true) {
                int i4 = i3;
                if (i4 < M) {
                    cancelGroupChildrenLocked(canceledNotifications.get(i4), callingUid, callingPid, listenerName, false, flagChecker);
                    i3 = i4 + 1;
                } else {
                    updateLightsLocked();
                    return;
                }
            }
        }
    }

    void snoozeNotificationInt(String key, long duration, String snoozeCriterionId, ManagedServices.ManagedServiceInfo listener) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        if ((duration <= 0 && snoozeCriterionId == null) || key == null) {
            return;
        }
        if (DBG) {
            Slog.d(TAG, String.format("snooze event(%s, %d, %s, %s)", key, Long.valueOf(duration), snoozeCriterionId, listenerName));
        }
        this.mHandler.post(new SnoozeNotificationRunnable(key, duration, snoozeCriterionId));
    }

    void unsnoozeNotificationInt(String key, ManagedServices.ManagedServiceInfo listener) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        if (DBG) {
            Slog.d(TAG, String.format("unsnooze event(%s, %s)", key, listenerName));
        }
        this.mSnoozeHelper.repost(key);
        savePolicyFile();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.notification.NotificationManagerService$16  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass16 implements Runnable {
        final /* synthetic */ int val$callingPid;
        final /* synthetic */ int val$callingUid;
        final /* synthetic */ boolean val$includeCurrentProfiles;
        final /* synthetic */ ManagedServices.ManagedServiceInfo val$listener;
        final /* synthetic */ int val$reason;
        final /* synthetic */ int val$userId;

        AnonymousClass16(ManagedServices.ManagedServiceInfo managedServiceInfo, int i, int i2, int i3, int i4, boolean z) {
            this.val$listener = managedServiceInfo;
            this.val$callingUid = i;
            this.val$callingPid = i2;
            this.val$userId = i3;
            this.val$reason = i4;
            this.val$includeCurrentProfiles = z;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                String listenerName = this.val$listener == null ? null : this.val$listener.component.toShortString();
                EventLogTags.writeNotificationCancelAll(this.val$callingUid, this.val$callingPid, null, this.val$userId, 0, 0, this.val$reason, listenerName);
                FlagChecker flagChecker = new FlagChecker() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$16$he13RdFo2qbqR04oK0hiGU5GDUg
                    @Override // com.android.server.notification.NotificationManagerService.FlagChecker
                    public final boolean apply(int i) {
                        return NotificationManagerService.AnonymousClass16.lambda$run$0(i);
                    }
                };
                NotificationManagerService.this.cancelAllNotificationsByListLocked(NotificationManagerService.this.mNotificationList, this.val$callingUid, this.val$callingPid, null, false, null, flagChecker, this.val$includeCurrentProfiles, this.val$userId, true, this.val$reason, listenerName, true);
                NotificationManagerService.this.cancelAllNotificationsByListLocked(NotificationManagerService.this.mEnqueuedNotifications, this.val$callingUid, this.val$callingPid, null, false, null, flagChecker, this.val$includeCurrentProfiles, this.val$userId, true, this.val$reason, listenerName, false);
                NotificationManagerService.this.mSnoozeHelper.cancel(this.val$userId, this.val$includeCurrentProfiles);
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$run$0(int flags) {
            if ((flags & 34) != 0) {
                return false;
            }
            return true;
        }
    }

    @GuardedBy("mNotificationLock")
    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason, ManagedServices.ManagedServiceInfo listener, boolean includeCurrentProfiles) {
        this.mHandler.post(new AnonymousClass16(listener, callingUid, callingPid, userId, reason, includeCurrentProfiles));
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public void cancelGroupChildrenLocked(NotificationRecord r, int callingUid, int callingPid, String listenerName, boolean sendDelete, FlagChecker flagChecker) {
        Notification n = r.getNotification();
        if (!n.isGroupSummary()) {
            return;
        }
        String pkg = r.sbn.getPackageName();
        if (pkg != null) {
            cancelGroupChildrenByListLocked(this.mNotificationList, r, callingUid, callingPid, listenerName, sendDelete, true, flagChecker);
            cancelGroupChildrenByListLocked(this.mEnqueuedNotifications, r, callingUid, callingPid, listenerName, sendDelete, false, flagChecker);
        } else if (DBG) {
            Log.e(TAG, "No package for group summary: " + r.getKey());
        }
    }

    @GuardedBy("mNotificationLock")
    private void cancelGroupChildrenByListLocked(ArrayList<NotificationRecord> notificationList, NotificationRecord parentNotification, int callingUid, int callingPid, String listenerName, boolean sendDelete, boolean wasPosted, FlagChecker flagChecker) {
        int i;
        FlagChecker flagChecker2 = flagChecker;
        String pkg = parentNotification.sbn.getPackageName();
        int userId = parentNotification.getUserId();
        int i2 = notificationList.size() - 1;
        while (true) {
            int i3 = i2;
            if (i3 < 0) {
                return;
            }
            NotificationRecord childR = notificationList.get(i3);
            StatusBarNotification childSbn = childR.sbn;
            if (!childSbn.isGroup() || childSbn.getNotification().isGroupSummary() || !childR.getGroupKey().equals(parentNotification.getGroupKey()) || (childR.getFlags() & 64) != 0) {
                i = i3;
            } else if (flagChecker2 == null || flagChecker2.apply(childR.getFlags())) {
                i = i3;
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, childSbn.getId(), childSbn.getTag(), userId, 0, 0, 12, listenerName);
                notificationList.remove(i);
                this.mNotificationsByKey.remove(childR.getKey());
                cancelNotificationLocked(childR, sendDelete, 12, wasPosted, listenerName);
            } else {
                i = i3;
            }
            i2 = i - 1;
            flagChecker2 = flagChecker;
        }
    }

    @GuardedBy("mNotificationLock")
    void updateLightsLocked() {
        NotificationRecord ledNotification = null;
        while (ledNotification == null && !this.mLights.isEmpty()) {
            String owner = this.mLights.get(this.mLights.size() - 1);
            NotificationRecord ledNotification2 = this.mNotificationsByKey.get(owner);
            ledNotification = ledNotification2;
            if (ledNotification == null) {
                Slog.wtfStack(TAG, "LED Notification does not exist: " + owner);
                this.mLights.remove(owner);
            }
        }
        if (ledNotification == null || this.mInCall || this.mScreenOn) {
            this.mNotificationLight.turnOff();
            return;
        }
        NotificationRecord.Light light = ledNotification.getLight();
        if (light != null && this.mNotificationPulseEnabled) {
            this.mNotificationLight.setFlashing(light.color, 1, light.onMs, light.offMs);
        }
    }

    @GuardedBy("mNotificationLock")
    List<NotificationRecord> findGroupNotificationsLocked(String pkg, String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        records.addAll(findGroupNotificationByListLocked(this.mNotificationList, pkg, groupKey, userId));
        records.addAll(findGroupNotificationByListLocked(this.mEnqueuedNotifications, pkg, groupKey, userId));
        return records;
    }

    @GuardedBy("mNotificationLock")
    private List<NotificationRecord> findGroupNotificationByListLocked(ArrayList<NotificationRecord> list, String pkg, String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId) && r.getGroupKey().equals(groupKey) && r.sbn.getPackageName().equals(pkg)) {
                records.add(r);
            }
        }
        return records;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public NotificationRecord findNotificationByKeyLocked(String key) {
        NotificationRecord r = findNotificationByListLocked(this.mNotificationList, key);
        if (r != null) {
            return r;
        }
        NotificationRecord r2 = findNotificationByListLocked(this.mEnqueuedNotifications, key);
        if (r2 != null) {
            return r2;
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    NotificationRecord findNotificationLocked(String pkg, String tag, int id, int userId) {
        NotificationRecord r = findNotificationByListLocked(this.mNotificationList, pkg, tag, id, userId);
        if (r != null) {
            return r;
        }
        NotificationRecord r2 = findNotificationByListLocked(this.mEnqueuedNotifications, pkg, tag, id, userId);
        if (r2 != null) {
            return r2;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public NotificationRecord findNotificationByListLocked(ArrayList<NotificationRecord> list, String pkg, String tag, int id, int userId) {
        int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId) && r.sbn.getId() == id && TextUtils.equals(r.sbn.getTag(), tag) && r.sbn.getPackageName().equals(pkg)) {
                return r;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public List<NotificationRecord> findNotificationsByListLocked(ArrayList<NotificationRecord> list, String pkg, String tag, int id, int userId) {
        List<NotificationRecord> matching = new ArrayList<>();
        int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId) && r.sbn.getId() == id && TextUtils.equals(r.sbn.getTag(), tag) && r.sbn.getPackageName().equals(pkg)) {
                matching.add(r);
            }
        }
        return matching;
    }

    @GuardedBy("mNotificationLock")
    private NotificationRecord findNotificationByListLocked(ArrayList<NotificationRecord> list, String key) {
        int N = list.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(list.get(i).getKey())) {
                return list.get(i);
            }
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    int indexOfNotificationLocked(String key) {
        int N = this.mNotificationList.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(this.mNotificationList.get(i).getKey())) {
                return i;
            }
        }
        return -1;
    }

    @VisibleForTesting
    protected void hideNotificationsForPackages(String[] pkgs) {
        synchronized (this.mNotificationLock) {
            List<String> pkgList = Arrays.asList(pkgs);
            List<NotificationRecord> changedNotifications = new ArrayList<>();
            int numNotifications = this.mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = this.mNotificationList.get(i);
                if (pkgList.contains(rec.sbn.getPackageName())) {
                    rec.setHidden(true);
                    changedNotifications.add(rec);
                }
            }
            this.mListeners.notifyHiddenLocked(changedNotifications);
        }
    }

    @VisibleForTesting
    protected void unhideNotificationsForPackages(String[] pkgs) {
        synchronized (this.mNotificationLock) {
            List<String> pkgList = Arrays.asList(pkgs);
            List<NotificationRecord> changedNotifications = new ArrayList<>();
            int numNotifications = this.mNotificationList.size();
            for (int i = 0; i < numNotifications; i++) {
                NotificationRecord rec = this.mNotificationList.get(i);
                if (pkgList.contains(rec.sbn.getPackageName())) {
                    rec.setHidden(false);
                    changedNotifications.add(rec);
                }
            }
            this.mListeners.notifyUnhiddenLocked(changedNotifications);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateNotificationPulse() {
        synchronized (this.mNotificationLock) {
            updateLightsLocked();
        }
    }

    protected boolean isCallingUidSystem() {
        int uid = Binder.getCallingUid();
        return uid == 1000;
    }

    protected boolean isUidSystemOrPhone(int uid) {
        int appid = UserHandle.getAppId(uid);
        return appid == 1000 || appid == 1001 || uid == 0;
    }

    protected boolean isCallerSystemOrPhone() {
        return isUidSystemOrPhone(Binder.getCallingUid());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkCallerIsSystemOrShell() {
        if (Binder.getCallingUid() == 2000) {
            return;
        }
        checkCallerIsSystem();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkCallerIsSystem() {
        if (isCallerSystemOrPhone()) {
            return;
        }
        throw new SecurityException("Disallowed call for uid " + Binder.getCallingUid());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkCallerIsSystemOrSameApp(String pkg) {
        if (isCallerSystemOrPhone()) {
            return;
        }
        checkCallerIsSameApp(pkg);
    }

    private boolean isCallerInstantApp(String pkg) {
        if (isCallerSystemOrPhone()) {
            return false;
        }
        this.mAppOps.checkPackage(Binder.getCallingUid(), pkg);
        try {
            ApplicationInfo ai = this.mPackageManager.getApplicationInfo(pkg, 0, UserHandle.getCallingUserId());
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            }
            return ai.isInstantApp();
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg, re);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkCallerIsSameApp(String pkg) {
        int uid = Binder.getCallingUid();
        try {
            ApplicationInfo ai = this.mPackageManager.getApplicationInfo(pkg, 0, UserHandle.getCallingUserId());
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            } else if (!UserHandle.isSameApp(ai.uid, uid)) {
                throw new SecurityException("Calling uid " + uid + " gave package " + pkg + " which is owned by uid " + ai.uid);
            }
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg + "\n" + re);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String callStateToString(int state) {
        switch (state) {
            case 0:
                return "CALL_STATE_IDLE";
            case 1:
                return "CALL_STATE_RINGING";
            case 2:
                return "CALL_STATE_OFFHOOK";
            default:
                return "CALL_STATE_UNKNOWN_" + state;
        }
    }

    private void listenForCallState() {
        TelephonyManager.from(getContext()).listen(new PhoneStateListener() { // from class: com.android.server.notification.NotificationManagerService.17
            @Override // android.telephony.PhoneStateListener
            public void onCallStateChanged(int state, String incomingNumber) {
                if (NotificationManagerService.this.mCallState == state) {
                    return;
                }
                if (NotificationManagerService.DBG) {
                    Slog.d(NotificationManagerService.TAG, "Call state changed: " + NotificationManagerService.callStateToString(state));
                }
                NotificationManagerService.this.mCallState = state;
            }
        }, 32);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mNotificationLock")
    public NotificationRankingUpdate makeRankingUpdateLocked(ManagedServices.ManagedServiceInfo info) {
        Bundle hidden;
        int N;
        int i;
        Bundle hidden2;
        Bundle hidden3;
        NotificationManagerService notificationManagerService = this;
        int N2 = notificationManagerService.mNotificationList.size();
        ArrayList<String> keys = new ArrayList<>(N2);
        ArrayList<String> interceptedKeys = new ArrayList<>(N2);
        ArrayList<Integer> importance = new ArrayList<>(N2);
        Bundle overrideGroupKeys = new Bundle();
        Bundle visibilityOverrides = new Bundle();
        Bundle suppressedVisualEffects = new Bundle();
        Bundle explanation = new Bundle();
        Bundle channels = new Bundle();
        Bundle overridePeople = new Bundle();
        Bundle snoozeCriteria = new Bundle();
        Bundle showBadge = new Bundle();
        Bundle userSentiment = new Bundle();
        Bundle hidden4 = new Bundle();
        int i2 = 0;
        int i3 = 0;
        while (true) {
            int i4 = i3;
            hidden = hidden4;
            if (i4 >= N2) {
                break;
            }
            try {
                NotificationRecord record = notificationManagerService.mNotificationList.get(i4);
                N = N2;
                try {
                    i = i4;
                    try {
                        if (notificationManagerService.isVisibleToListener(record.sbn, info)) {
                            String key = record.sbn.getKey();
                            keys.add(key);
                            importance.add(Integer.valueOf(record.getImportance()));
                            if (record.getImportanceExplanation() != null) {
                                try {
                                    explanation.putCharSequence(key, record.getImportanceExplanation());
                                } catch (Exception e) {
                                    e = e;
                                    hidden2 = hidden;
                                    StringBuilder sb = new StringBuilder();
                                    hidden3 = hidden2;
                                    sb.append("makeRankingUpdateLocked e ");
                                    sb.append(e);
                                    Slog.e(TAG, sb.toString());
                                    i3 = i + 1;
                                    N2 = N;
                                    hidden4 = hidden3;
                                    notificationManagerService = this;
                                }
                            }
                            if (record.isIntercepted()) {
                                interceptedKeys.add(key);
                            }
                            suppressedVisualEffects.putInt(key, record.getSuppressedVisualEffects());
                            if (record.getPackageVisibilityOverride() != -1000) {
                                visibilityOverrides.putInt(key, record.getPackageVisibilityOverride());
                            }
                            overrideGroupKeys.putString(key, record.sbn.getOverrideGroupKey());
                            channels.putParcelable(key, record.getChannel());
                            overridePeople.putStringArrayList(key, record.getPeopleOverride());
                            snoozeCriteria.putParcelableArrayList(key, record.getSnoozeCriteria());
                            showBadge.putBoolean(key, record.canShowBadge());
                            userSentiment.putInt(key, record.getUserSentiment());
                            hidden2 = hidden;
                            try {
                                hidden2.putBoolean(key, record.isHidden());
                                hidden3 = hidden2;
                            } catch (Exception e2) {
                                e = e2;
                                StringBuilder sb2 = new StringBuilder();
                                hidden3 = hidden2;
                                sb2.append("makeRankingUpdateLocked e ");
                                sb2.append(e);
                                Slog.e(TAG, sb2.toString());
                                i3 = i + 1;
                                N2 = N;
                                hidden4 = hidden3;
                                notificationManagerService = this;
                            }
                        } else {
                            hidden3 = hidden;
                        }
                    } catch (Exception e3) {
                        e = e3;
                        hidden2 = hidden;
                    }
                } catch (Exception e4) {
                    e = e4;
                    i = i4;
                    hidden2 = hidden;
                }
            } catch (Exception e5) {
                e = e5;
                N = N2;
                i = i4;
                hidden2 = hidden;
            }
            i3 = i + 1;
            N2 = N;
            hidden4 = hidden3;
            notificationManagerService = this;
        }
        int M = keys.size();
        String[] keysAr = (String[]) keys.toArray(new String[M]);
        String[] interceptedKeysAr = (String[]) interceptedKeys.toArray(new String[interceptedKeys.size()]);
        int[] importanceAr = new int[M];
        while (true) {
            int i5 = i2;
            ArrayList<String> keys2 = keys;
            if (i5 >= M) {
                return new NotificationRankingUpdate(keysAr, interceptedKeysAr, visibilityOverrides, suppressedVisualEffects, importanceAr, explanation, overrideGroupKeys, channels, overridePeople, snoozeCriteria, showBadge, userSentiment, hidden);
            }
            importanceAr[i5] = importance.get(i5).intValue();
            i2 = i5 + 1;
            keys = keys2;
            M = M;
        }
    }

    boolean hasCompanionDevice(ManagedServices.ManagedServiceInfo info) {
        if (this.mCompanionManager == null) {
            this.mCompanionManager = getCompanionManager();
        }
        if (this.mCompanionManager == null) {
            return false;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                try {
                    List<String> associations = this.mCompanionManager.getAssociations(info.component.getPackageName(), info.userid);
                    if (!ArrayUtils.isEmpty(associations)) {
                        return true;
                    }
                } catch (RemoteException re) {
                    Slog.e(TAG, "Cannot reach companion device service", re);
                }
            } catch (SecurityException e) {
            } catch (Exception e2) {
                Slog.e(TAG, "Cannot verify listener " + info, e2);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    protected ICompanionDeviceManager getCompanionManager() {
        return ICompanionDeviceManager.Stub.asInterface(ServiceManager.getService("companiondevice"));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isVisibleToListener(StatusBarNotification sbn, ManagedServices.ManagedServiceInfo listener) {
        if (!listener.enabledAndUserMatches(sbn.getUserId())) {
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isPackageSuspendedForUser(String pkg, int uid) {
        long identity = Binder.clearCallingIdentity();
        int userId = UserHandle.getUserId(uid);
        try {
            try {
                boolean isPackageSuspendedForUser = this.mPackageManager.isPackageSuspendedForUser(pkg, userId);
                Binder.restoreCallingIdentity(identity);
                return isPackageSuspendedForUser;
            } catch (RemoteException e) {
                throw new SecurityException("Could not talk to package manager service");
            } catch (IllegalArgumentException e2) {
                Binder.restoreCallingIdentity(identity);
                return false;
            }
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public boolean canUseManagedServices(String pkg) {
        String[] stringArray;
        boolean canUseManagedServices = !this.mActivityManager.isLowRamDevice() || this.mPackageManagerClient.hasSystemFeature("android.hardware.type.watch");
        for (String whitelisted : getContext().getResources().getStringArray(17235977)) {
            if (whitelisted.equals(pkg)) {
                canUseManagedServices = true;
            }
        }
        return canUseManagedServices;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class TrimCache {
        StatusBarNotification heavy;
        StatusBarNotification sbnClone;
        StatusBarNotification sbnCloneLight;

        TrimCache(StatusBarNotification sbn) {
            this.heavy = sbn;
        }

        StatusBarNotification ForListener(ManagedServices.ManagedServiceInfo info) {
            if (NotificationManagerService.this.mListeners.getOnNotificationPostedTrim(info) == 1) {
                if (this.sbnCloneLight == null) {
                    this.sbnCloneLight = this.heavy.cloneLight();
                }
                return this.sbnCloneLight;
            }
            if (this.sbnClone == null) {
                this.sbnClone = this.heavy.clone();
            }
            return this.sbnClone;
        }
    }

    /* loaded from: classes.dex */
    public class NotificationAssistants extends ManagedServices {
        static final String TAG_ENABLED_NOTIFICATION_ASSISTANTS = "enabled_assistants";

        public NotificationAssistants(Context context, Object lock, ManagedServices.UserProfiles up, IPackageManager pm) {
            super(context, lock, up, pm);
        }

        @Override // com.android.server.notification.ManagedServices
        protected ManagedServices.Config getConfig() {
            ManagedServices.Config c = new ManagedServices.Config();
            c.caption = "notification assistant";
            c.serviceInterface = "android.service.notification.NotificationAssistantService";
            c.xmlTag = TAG_ENABLED_NOTIFICATION_ASSISTANTS;
            c.secureSettingName = "enabled_notification_assistant";
            c.bindPermission = "android.permission.BIND_NOTIFICATION_ASSISTANT_SERVICE";
            c.settingsAction = "android.settings.MANAGE_DEFAULT_APPS_SETTINGS";
            c.clientLabel = 17040402;
            return c;
        }

        @Override // com.android.server.notification.ManagedServices
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override // com.android.server.notification.ManagedServices
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        @Override // com.android.server.notification.ManagedServices
        protected void onServiceAdded(ManagedServices.ManagedServiceInfo info) {
            NotificationManagerService.this.mListeners.registerGuestService(info);
        }

        @Override // com.android.server.notification.ManagedServices
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServices.ManagedServiceInfo removed) {
            NotificationManagerService.this.mListeners.unregisterService(removed.service, removed.userid);
        }

        @Override // com.android.server.notification.ManagedServices
        public void onUserUnlocked(int user) {
            if (this.DEBUG) {
                String str = this.TAG;
                Slog.d(str, "onUserUnlocked u=" + user);
            }
            rebindServices(true);
        }

        public void onNotificationEnqueued(NotificationRecord r) {
            StatusBarNotification sbn = r.sbn;
            TrimCache trimCache = new TrimCache(sbn);
            for (final ManagedServices.ManagedServiceInfo info : getServices()) {
                boolean sbnVisible = NotificationManagerService.this.isVisibleToListener(sbn, info);
                if (sbnVisible) {
                    final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                    NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.NotificationAssistants.1
                        @Override // java.lang.Runnable
                        public void run() {
                            NotificationAssistants.this.notifyEnqueued(info, sbnToPost);
                        }
                    });
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyEnqueued(ManagedServices.ManagedServiceInfo info, StatusBarNotification sbn) {
            INotificationListener assistant = info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                assistant.onNotificationEnqueued(sbnHolder);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify assistant (enqueued): " + assistant, ex);
            }
        }

        @GuardedBy("mNotificationLock")
        public void notifyAssistantSnoozedLocked(StatusBarNotification sbn, final String snoozeCriterionId) {
            TrimCache trimCache = new TrimCache(sbn);
            for (final ManagedServices.ManagedServiceInfo info : getServices()) {
                boolean sbnVisible = NotificationManagerService.this.isVisibleToListener(sbn, info);
                if (sbnVisible) {
                    final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                    NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.NotificationAssistants.2
                        @Override // java.lang.Runnable
                        public void run() {
                            INotificationListener assistant = info.service;
                            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbnToPost);
                            try {
                                assistant.onNotificationSnoozedUntilContext(sbnHolder, snoozeCriterionId);
                            } catch (RemoteException ex) {
                                String str = NotificationAssistants.this.TAG;
                                Log.e(str, "unable to notify assistant (snoozed): " + assistant, ex);
                            }
                        }
                    });
                }
            }
        }

        public boolean isEnabled() {
            return !getServices().isEmpty();
        }

        protected void ensureAssistant() {
            List<UserInfo> activeUsers = this.mUm.getUsers(true);
            for (UserInfo userInfo : activeUsers) {
                int userId = userInfo.getUserHandle().getIdentifier();
                if (getAllowedPackages(userId).isEmpty()) {
                    String str = this.TAG;
                    Slog.d(str, "Approving default notification assistant for user " + userId);
                    NotificationManagerService.this.readDefaultAssistant(userId);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    public class NotificationListeners extends ManagedServices {
        static final String TAG_ENABLED_NOTIFICATION_LISTENERS = "enabled_listeners";
        private final ArraySet<ManagedServices.ManagedServiceInfo> mLightTrimListeners;

        public NotificationListeners(IPackageManager pm) {
            super(NotificationManagerService.this.getContext(), NotificationManagerService.this.mNotificationLock, NotificationManagerService.this.mUserProfiles, pm);
            this.mLightTrimListeners = new ArraySet<>();
        }

        @Override // com.android.server.notification.ManagedServices
        protected int getBindFlags() {
            return 83886337;
        }

        @Override // com.android.server.notification.ManagedServices
        protected ManagedServices.Config getConfig() {
            ManagedServices.Config c = new ManagedServices.Config();
            c.caption = "notification listener";
            c.serviceInterface = "android.service.notification.NotificationListenerService";
            c.xmlTag = TAG_ENABLED_NOTIFICATION_LISTENERS;
            c.secureSettingName = "enabled_notification_listeners";
            c.bindPermission = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE";
            c.settingsAction = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";
            c.clientLabel = 17040400;
            return c;
        }

        @Override // com.android.server.notification.ManagedServices
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override // com.android.server.notification.ManagedServices
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        @Override // com.android.server.notification.ManagedServices
        public void onServiceAdded(ManagedServices.ManagedServiceInfo info) {
            NotificationRankingUpdate update;
            INotificationListener listener = info.service;
            synchronized (NotificationManagerService.this.mNotificationLock) {
                update = NotificationManagerService.this.makeRankingUpdateLocked(info);
            }
            try {
                listener.onListenerConnected(update);
            } catch (RemoteException e) {
            }
        }

        @Override // com.android.server.notification.ManagedServices
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServices.ManagedServiceInfo removed) {
            if (NotificationManagerService.this.removeDisabledHints(removed)) {
                NotificationManagerService.this.updateListenerHintsLocked();
                NotificationManagerService.this.updateEffectsSuppressorLocked();
            }
            this.mLightTrimListeners.remove(removed);
        }

        @GuardedBy("mNotificationLock")
        public void setOnNotificationPostedTrimLocked(ManagedServices.ManagedServiceInfo info, int trim) {
            if (trim == 1) {
                this.mLightTrimListeners.add(info);
            } else {
                this.mLightTrimListeners.remove(info);
            }
        }

        public int getOnNotificationPostedTrim(ManagedServices.ManagedServiceInfo info) {
            return this.mLightTrimListeners.contains(info) ? 1 : 0;
        }

        @GuardedBy("mNotificationLock")
        public void notifyPostedLocked(NotificationRecord r, NotificationRecord old) {
            notifyPostedLocked(r, old, true);
        }

        @GuardedBy("mNotificationLock")
        private void notifyPostedLocked(NotificationRecord r, NotificationRecord old, boolean notifyAllListeners) {
            boolean oldSbnVisible;
            StatusBarNotification sbn = r.sbn;
            StatusBarNotification oldSbn = old != null ? old.sbn : null;
            TrimCache trimCache = new TrimCache(sbn);
            NotificationManagerService.this.mNotificationPolicy.onNotificationPosted(NotificationManagerService.this.mNotificationsByKey, sbn);
            for (final ManagedServices.ManagedServiceInfo info : getServices()) {
                boolean sbnVisible = NotificationManagerService.this.isVisibleToListener(sbn, info);
                if (oldSbn != null) {
                    oldSbnVisible = NotificationManagerService.this.isVisibleToListener(oldSbn, info);
                } else {
                    oldSbnVisible = false;
                }
                if (oldSbnVisible || sbnVisible) {
                    if (!r.isHidden() || info.targetSdkVersion >= 28) {
                        if (notifyAllListeners || info.targetSdkVersion < 28) {
                            final NotificationRankingUpdate update = NotificationManagerService.this.makeRankingUpdateLocked(info);
                            if (oldSbnVisible && !sbnVisible) {
                                final StatusBarNotification oldSbnLightClone = oldSbn.cloneLight();
                                NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.NotificationListeners.1
                                    @Override // java.lang.Runnable
                                    public void run() {
                                        NotificationListeners.this.notifyRemoved(info, oldSbnLightClone, update, null, 6);
                                    }
                                });
                            } else {
                                int targetUserId = info.userid != -1 ? info.userid : 0;
                                NotificationManagerService.this.updateUriPermissions(r, old, info.component.getPackageName(), targetUserId);
                                final StatusBarNotification sbnToPost = trimCache.ForListener(info);
                                NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.NotificationListeners.2
                                    @Override // java.lang.Runnable
                                    public void run() {
                                        NotificationListeners.this.notifyPosted(info, sbnToPost, update);
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }

        /* JADX WARN: Code restructure failed: missing block: B:16:0x0048, code lost:
            if (r13.targetSdkVersion < 28) goto L16;
         */
        @com.android.internal.annotations.GuardedBy("mNotificationLock")
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void notifyRemovedLocked(final com.android.server.notification.NotificationRecord r18, final int r19, android.service.notification.NotificationStats r20) {
            /*
                r17 = this;
                r7 = r17
                r8 = r18
                r9 = r19
                android.service.notification.StatusBarNotification r10 = r8.sbn
                android.service.notification.StatusBarNotification r11 = r10.cloneLight()
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                com.android.server.notification.NotificationPolicy r0 = com.android.server.notification.NotificationManagerService.access$5800(r0)
                r0.onNotificationRemoved(r10)
                java.util.List r0 = r17.getServices()
                java.util.Iterator r12 = r0.iterator()
            L1d:
                boolean r0 = r12.hasNext()
                if (r0 == 0) goto L7f
                java.lang.Object r0 = r12.next()
                r13 = r0
                com.android.server.notification.ManagedServices$ManagedServiceInfo r13 = (com.android.server.notification.ManagedServices.ManagedServiceInfo) r13
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                boolean r0 = com.android.server.notification.NotificationManagerService.access$3700(r0, r10, r13)
                if (r0 != 0) goto L33
                goto L1d
            L33:
                boolean r0 = r18.isHidden()
                r1 = 28
                r2 = 14
                if (r0 == 0) goto L44
                if (r9 == r2) goto L44
                int r0 = r13.targetSdkVersion
                if (r0 >= r1) goto L44
                goto L1d
            L44:
                if (r9 != r2) goto L4b
                int r0 = r13.targetSdkVersion
                if (r0 < r1) goto L4b
                goto L1d
            L4b:
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                com.android.server.notification.NotificationManagerService$NotificationAssistants r0 = com.android.server.notification.NotificationManagerService.access$1500(r0)
                android.os.IInterface r1 = r13.service
                boolean r0 = r0.isServiceTokenValidLocked(r1)
                if (r0 == 0) goto L5c
                r5 = r20
                goto L5e
            L5c:
                r0 = 0
                r5 = r0
            L5e:
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                android.service.notification.NotificationRankingUpdate r14 = com.android.server.notification.NotificationManagerService.access$8600(r0, r13)
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                com.android.server.notification.NotificationManagerService$WorkerHandler r15 = com.android.server.notification.NotificationManagerService.access$4400(r0)
                com.android.server.notification.NotificationManagerService$NotificationListeners$3 r6 = new com.android.server.notification.NotificationManagerService$NotificationListeners$3
                r0 = r6
                r1 = r7
                r2 = r13
                r3 = r11
                r4 = r14
                r16 = r10
                r10 = r6
                r6 = r9
                r0.<init>()
                r15.post(r10)
                r10 = r16
                goto L1d
            L7f:
                r16 = r10
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                com.android.server.notification.NotificationManagerService$WorkerHandler r0 = com.android.server.notification.NotificationManagerService.access$4400(r0)
                com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$Zsu32u2gOsCsEatsnnXTLoY37u0 r1 = new com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$Zsu32u2gOsCsEatsnnXTLoY37u0
                r1.<init>()
                r0.post(r1)
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.NotificationListeners.notifyRemovedLocked(com.android.server.notification.NotificationRecord, int, android.service.notification.NotificationStats):void");
        }

        @GuardedBy("mNotificationLock")
        public void notifyRankingUpdateLocked(List<NotificationRecord> changedHiddenNotifications) {
            boolean isHiddenRankingUpdate = changedHiddenNotifications != null && changedHiddenNotifications.size() > 0;
            for (final ManagedServices.ManagedServiceInfo serviceInfo : getServices()) {
                if (serviceInfo.isEnabledForCurrentProfiles()) {
                    boolean notifyThisListener = false;
                    if (isHiddenRankingUpdate && serviceInfo.targetSdkVersion >= 28) {
                        Iterator<NotificationRecord> it = changedHiddenNotifications.iterator();
                        while (true) {
                            if (!it.hasNext()) {
                                break;
                            }
                            NotificationRecord rec = it.next();
                            if (NotificationManagerService.this.isVisibleToListener(rec.sbn, serviceInfo)) {
                                notifyThisListener = true;
                                break;
                            }
                        }
                    }
                    if (notifyThisListener || !isHiddenRankingUpdate) {
                        final NotificationRankingUpdate update = NotificationManagerService.this.makeRankingUpdateLocked(serviceInfo);
                        NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.NotificationListeners.4
                            @Override // java.lang.Runnable
                            public void run() {
                                NotificationListeners.this.notifyRankingUpdate(serviceInfo, update);
                            }
                        });
                    }
                }
            }
        }

        @GuardedBy("mNotificationLock")
        public void notifyListenerHintsChangedLocked(final int hints) {
            for (final ManagedServices.ManagedServiceInfo serviceInfo : getServices()) {
                if (serviceInfo.isEnabledForCurrentProfiles()) {
                    NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.NotificationListeners.5
                        @Override // java.lang.Runnable
                        public void run() {
                            NotificationListeners.this.notifyListenerHintsChanged(serviceInfo, hints);
                        }
                    });
                }
            }
        }

        @GuardedBy("mNotificationLock")
        public void notifyHiddenLocked(List<NotificationRecord> changedNotifications) {
            if (changedNotifications == null || changedNotifications.size() == 0) {
                return;
            }
            notifyRankingUpdateLocked(changedNotifications);
            int numChangedNotifications = changedNotifications.size();
            for (int i = 0; i < numChangedNotifications; i++) {
                NotificationRecord rec = changedNotifications.get(i);
                NotificationManagerService.this.mListeners.notifyRemovedLocked(rec, 14, rec.getStats());
            }
        }

        @GuardedBy("mNotificationLock")
        public void notifyUnhiddenLocked(List<NotificationRecord> changedNotifications) {
            if (changedNotifications == null || changedNotifications.size() == 0) {
                return;
            }
            notifyRankingUpdateLocked(changedNotifications);
            int numChangedNotifications = changedNotifications.size();
            for (int i = 0; i < numChangedNotifications; i++) {
                NotificationRecord rec = changedNotifications.get(i);
                NotificationManagerService.this.mListeners.notifyPostedLocked(rec, rec, false);
            }
        }

        public void notifyInterruptionFilterChanged(final int interruptionFilter) {
            for (final ManagedServices.ManagedServiceInfo serviceInfo : getServices()) {
                if (serviceInfo.isEnabledForCurrentProfiles()) {
                    NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.NotificationListeners.6
                        @Override // java.lang.Runnable
                        public void run() {
                            NotificationListeners.this.notifyInterruptionFilterChanged(serviceInfo, interruptionFilter);
                        }
                    });
                }
            }
        }

        protected void notifyNotificationChannelChanged(final String pkg, final UserHandle user, final NotificationChannel channel, final int modificationType) {
            if (channel == null) {
                return;
            }
            for (final ManagedServices.ManagedServiceInfo serviceInfo : getServices()) {
                if (serviceInfo.enabledAndUserMatches(UserHandle.getCallingUserId())) {
                    BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$E8qsF-PrFYYUtUGked50-pRub20
                        @Override // java.lang.Runnable
                        public final void run() {
                            NotificationManagerService.NotificationListeners.lambda$notifyNotificationChannelChanged$1(NotificationManagerService.NotificationListeners.this, serviceInfo, pkg, user, channel, modificationType);
                        }
                    });
                }
            }
        }

        public static /* synthetic */ void lambda$notifyNotificationChannelChanged$1(NotificationListeners notificationListeners, ManagedServices.ManagedServiceInfo serviceInfo, String pkg, UserHandle user, NotificationChannel channel, int modificationType) {
            if (NotificationManagerService.this.hasCompanionDevice(serviceInfo)) {
                notificationListeners.notifyNotificationChannelChanged(serviceInfo, pkg, user, channel, modificationType);
            }
        }

        protected void notifyNotificationChannelGroupChanged(final String pkg, final UserHandle user, final NotificationChannelGroup group, final int modificationType) {
            if (group == null) {
                return;
            }
            for (final ManagedServices.ManagedServiceInfo serviceInfo : getServices()) {
                if (serviceInfo.enabledAndUserMatches(UserHandle.getCallingUserId())) {
                    BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$ZpwYxOiDD13VBHvGZVH3p7iGkFI
                        @Override // java.lang.Runnable
                        public final void run() {
                            NotificationManagerService.NotificationListeners.lambda$notifyNotificationChannelGroupChanged$2(NotificationManagerService.NotificationListeners.this, serviceInfo, pkg, user, group, modificationType);
                        }
                    });
                }
            }
        }

        public static /* synthetic */ void lambda$notifyNotificationChannelGroupChanged$2(NotificationListeners notificationListeners, ManagedServices.ManagedServiceInfo serviceInfo, String pkg, UserHandle user, NotificationChannelGroup group, int modificationType) {
            if (NotificationManagerService.this.hasCompanionDevice(serviceInfo)) {
                notificationListeners.notifyNotificationChannelGroupChanged(serviceInfo, pkg, user, group, modificationType);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyPosted(ManagedServices.ManagedServiceInfo info, StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate) {
            INotificationListener listener = info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationPosted(sbnHolder, rankingUpdate);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify listener (posted): " + listener, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyRemoved(ManagedServices.ManagedServiceInfo info, StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate, NotificationStats stats, int reason) {
            if (!info.enabledAndUserMatches(sbn.getUserId())) {
                return;
            }
            INotificationListener listener = info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationRemoved(sbnHolder, rankingUpdate, stats, reason);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify listener (removed): " + listener, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyRankingUpdate(ManagedServices.ManagedServiceInfo info, NotificationRankingUpdate rankingUpdate) {
            INotificationListener listener = info.service;
            try {
                listener.onNotificationRankingUpdate(rankingUpdate);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify listener (ranking update): " + listener, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyListenerHintsChanged(ManagedServices.ManagedServiceInfo info, int hints) {
            INotificationListener listener = info.service;
            try {
                listener.onListenerHintsChanged(hints);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify listener (listener hints): " + listener, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyInterruptionFilterChanged(ManagedServices.ManagedServiceInfo info, int interruptionFilter) {
            INotificationListener listener = info.service;
            try {
                listener.onInterruptionFilterChanged(interruptionFilter);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify listener (interruption filter): " + listener, ex);
            }
        }

        void notifyNotificationChannelChanged(ManagedServices.ManagedServiceInfo info, String pkg, UserHandle user, NotificationChannel channel, int modificationType) {
            INotificationListener listener = info.service;
            try {
                listener.onNotificationChannelModification(pkg, user, channel, modificationType);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify listener (channel changed): " + listener, ex);
            }
        }

        private void notifyNotificationChannelGroupChanged(ManagedServices.ManagedServiceInfo info, String pkg, UserHandle user, NotificationChannelGroup group, int modificationType) {
            INotificationListener listener = info.service;
            try {
                listener.onNotificationChannelGroupModification(pkg, user, group, modificationType);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Log.e(str, "unable to notify listener (channel group changed): " + listener, ex);
            }
        }

        public boolean isListenerPackage(String packageName) {
            if (packageName == null) {
                return false;
            }
            synchronized (NotificationManagerService.this.mNotificationLock) {
                for (ManagedServices.ManagedServiceInfo serviceInfo : getServices()) {
                    if (packageName.equals(serviceInfo.component.getPackageName())) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    /* loaded from: classes.dex */
    public static final class DumpFilter {
        public String pkgFilter;
        public long since;
        public boolean stats;
        public boolean zen;
        public boolean filtered = false;
        public boolean redact = true;
        public boolean proto = false;
        public boolean criticalPriority = false;
        public boolean normalPriority = false;

        public static DumpFilter parseFromArguments(String[] args) {
            DumpFilter filter = new DumpFilter();
            int ai = 0;
            while (ai < args.length) {
                String a = args[ai];
                if (PriorityDump.PROTO_ARG.equals(a)) {
                    filter.proto = true;
                } else if ("--noredact".equals(a) || "--reveal".equals(a)) {
                    filter.redact = false;
                } else if ("p".equals(a) || "pkg".equals(a) || "--package".equals(a)) {
                    if (ai < args.length - 1) {
                        ai++;
                        filter.pkgFilter = args[ai].trim().toLowerCase();
                        if (filter.pkgFilter.isEmpty()) {
                            filter.pkgFilter = null;
                        } else {
                            filter.filtered = true;
                        }
                    }
                } else if ("--zen".equals(a) || "zen".equals(a)) {
                    filter.filtered = true;
                    filter.zen = true;
                } else if ("--stats".equals(a)) {
                    filter.stats = true;
                    if (ai < args.length - 1) {
                        ai++;
                        filter.since = Long.parseLong(args[ai]);
                    } else {
                        filter.since = 0L;
                    }
                } else if (PriorityDump.PRIORITY_ARG.equals(a) && ai < args.length - 1) {
                    ai++;
                    String str = args[ai];
                    char c = 65535;
                    int hashCode = str.hashCode();
                    if (hashCode != -1986416409) {
                        if (hashCode == -1560189025 && str.equals(PriorityDump.PRIORITY_ARG_CRITICAL)) {
                            c = 0;
                        }
                    } else if (str.equals(PriorityDump.PRIORITY_ARG_NORMAL)) {
                        c = 1;
                    }
                    switch (c) {
                        case 0:
                            filter.criticalPriority = true;
                            continue;
                        case 1:
                            filter.normalPriority = true;
                            continue;
                    }
                }
                ai++;
            }
            return filter;
        }

        public boolean matches(StatusBarNotification sbn) {
            if (this.filtered && !this.zen) {
                return sbn != null && (matches(sbn.getPackageName()) || matches(sbn.getOpPkg()));
            }
            return true;
        }

        public boolean matches(ComponentName component) {
            if (this.filtered && !this.zen) {
                return component != null && matches(component.getPackageName());
            }
            return true;
        }

        public boolean matches(String pkg) {
            if (this.filtered && !this.zen) {
                return pkg != null && pkg.toLowerCase().contains(this.pkgFilter);
            }
            return true;
        }

        public String toString() {
            if (this.stats) {
                return "stats";
            }
            if (this.zen) {
                return "zen";
            }
            return '\'' + this.pkgFilter + '\'';
        }
    }

    @VisibleForTesting
    protected void simulatePackageSuspendBroadcast(boolean suspend, String pkg) {
        Bundle extras = new Bundle();
        extras.putStringArray("android.intent.extra.changed_package_list", new String[]{pkg});
        String action = suspend ? "android.intent.action.PACKAGES_SUSPENDED" : "android.intent.action.PACKAGES_UNSUSPENDED";
        Intent intent = new Intent(action);
        intent.putExtras(extras);
        this.mPackageIntentReceiver.onReceive(getContext(), intent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class StatusBarNotificationHolder extends IStatusBarNotificationHolder.Stub {
        private StatusBarNotification mValue;

        public StatusBarNotificationHolder(StatusBarNotification value) {
            this.mValue = value;
        }

        public StatusBarNotification get() {
            StatusBarNotification value = this.mValue;
            this.mValue = null;
            return value;
        }
    }

    /* loaded from: classes.dex */
    private class ShellCmd extends ShellCommand {
        public static final String USAGE = "help\nallow_listener COMPONENT [user_id]\ndisallow_listener COMPONENT [user_id]\nallow_assistant COMPONENT\nremove_assistant COMPONENT\nallow_dnd PACKAGE\ndisallow_dnd PACKAGE\nsuspend_package PACKAGE\nunsuspend_package PACKAGE";

        private ShellCmd() {
        }

        public int onCommand(String cmd) {
            char c;
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            PrintWriter pw = getOutPrintWriter();
            try {
                switch (cmd.hashCode()) {
                    case -1325770982:
                        if (cmd.equals("disallow_assistant")) {
                            c = 5;
                            break;
                        }
                        c = 65535;
                        break;
                    case -506770550:
                        if (cmd.equals("unsuspend_package")) {
                            c = 7;
                            break;
                        }
                        c = 65535;
                        break;
                    case -432999190:
                        if (cmd.equals("allow_listener")) {
                            c = 2;
                            break;
                        }
                        c = 65535;
                        break;
                    case -429832618:
                        if (cmd.equals("disallow_dnd")) {
                            c = 1;
                            break;
                        }
                        c = 65535;
                        break;
                    case 372345636:
                        if (cmd.equals("allow_dnd")) {
                            c = 0;
                            break;
                        }
                        c = 65535;
                        break;
                    case 393969475:
                        if (cmd.equals("suspend_package")) {
                            c = 6;
                            break;
                        }
                        c = 65535;
                        break;
                    case 1257269496:
                        if (cmd.equals("disallow_listener")) {
                            c = 3;
                            break;
                        }
                        c = 65535;
                        break;
                    case 2110474600:
                        if (cmd.equals("allow_assistant")) {
                            c = 4;
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
                        NotificationManagerService.this.getBinderService().setNotificationPolicyAccessGranted(getNextArgRequired(), true);
                        break;
                    case 1:
                        NotificationManagerService.this.getBinderService().setNotificationPolicyAccessGranted(getNextArgRequired(), false);
                        break;
                    case 2:
                        ComponentName cn = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn == null) {
                            pw.println("Invalid listener - must be a ComponentName");
                            return -1;
                        }
                        String userId = getNextArg();
                        if (userId == null) {
                            NotificationManagerService.this.getBinderService().setNotificationListenerAccessGranted(cn, true);
                        } else {
                            NotificationManagerService.this.getBinderService().setNotificationListenerAccessGrantedForUser(cn, Integer.parseInt(userId), true);
                        }
                        break;
                    case 3:
                        ComponentName cn2 = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn2 == null) {
                            pw.println("Invalid listener - must be a ComponentName");
                            return -1;
                        }
                        String userId2 = getNextArg();
                        if (userId2 == null) {
                            NotificationManagerService.this.getBinderService().setNotificationListenerAccessGranted(cn2, false);
                        } else {
                            NotificationManagerService.this.getBinderService().setNotificationListenerAccessGrantedForUser(cn2, Integer.parseInt(userId2), false);
                        }
                        break;
                    case 4:
                        ComponentName cn3 = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn3 == null) {
                            pw.println("Invalid assistant - must be a ComponentName");
                            return -1;
                        }
                        NotificationManagerService.this.getBinderService().setNotificationAssistantAccessGranted(cn3, true);
                        break;
                    case 5:
                        ComponentName cn4 = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn4 != null) {
                            NotificationManagerService.this.getBinderService().setNotificationAssistantAccessGranted(cn4, false);
                            break;
                        } else {
                            pw.println("Invalid assistant - must be a ComponentName");
                            return -1;
                        }
                    case 6:
                        NotificationManagerService.this.simulatePackageSuspendBroadcast(true, getNextArgRequired());
                        break;
                    case 7:
                        NotificationManagerService.this.simulatePackageSuspendBroadcast(false, getNextArgRequired());
                        break;
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (Exception e) {
                pw.println("Error occurred. Check logcat for details. " + e.getMessage());
                Slog.e(NotificationManagerService.TAG, "Error running shell command", e);
            }
            return 0;
        }

        public void onHelp() {
            getOutPrintWriter().println(USAGE);
        }
    }
}
