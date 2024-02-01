package com.android.server.notification;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.INotificationStatusCallBack;
import android.app.ITransientNotification;
import android.app.IUriGrantsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.app.UriGrantsManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.backup.BackupManager;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.ICompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
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
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.INotificationListener;
import android.service.notification.IStatusBarNotificationHolder;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.NotificationStats;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;
import android.util.proto.ProtoOutputStream;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.TriPredicate;
import com.android.server.DeviceIdleController;
import com.android.server.EventLogTags;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
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
import com.android.server.slice.SliceClientPermissions;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import com.xiaopeng.util.FeatureOption;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
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
    private static final String LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG = "allow-secure-notifications-on-lockscreen";
    private static final String LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE = "value";
    static final int LONG_DELAY = 4000;
    static final int MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS = 3000;
    static final float MATCHES_CALL_FILTER_TIMEOUT_AFFINITY = 1.0f;
    static final int MAX_PACKAGE_NOTIFICATIONS = 50;
    static final int MESSAGE_DURATION_REACHED = 2;
    static final int MESSAGE_FINISH_TOKEN_TIMEOUT = 7;
    static final int MESSAGE_LISTENER_HINTS_CHANGED = 5;
    static final int MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED = 6;
    static final int MESSAGE_ON_PACKAGE_CHANGED = 8;
    private static final int MESSAGE_RANKING_SORT = 1001;
    private static final int MESSAGE_RECONSIDER_RANKING = 1000;
    static final int MESSAGE_SEND_RANKING_UPDATE = 4;
    private static final long MIN_PACKAGE_OVERRATE_LOG_INTERVAL = 5000;
    public static final int REPORT_REMOTE_VIEWS = 1;
    private static final int REQUEST_CODE_TIMEOUT = 1;
    private static final String SCHEME_TIMEOUT = "timeout";
    static final int SHORT_DELAY = 2500;
    static final long SNOOZE_UNTIL_UNSPECIFIED = -1;
    private static final String TAG_NOTIFICATION_POLICY = "notification-policy";
    static final int VIBRATE_PATTERN_MAXLEN = 17;
    private AccessibilityManager mAccessibilityManager;
    private ActivityManager mActivityManager;
    private AlarmManager mAlarmManager;
    private TriPredicate<String, Integer, String> mAllowedManagedServicePackages;
    private IActivityManager mAm;
    private ActivityManagerInternal mAmi;
    private AppOpsManager mAppOps;
    private UsageStatsManagerInternal mAppUsageStats;
    private Archive mArchive;
    private NotificationAssistants mAssistants;
    Light mAttentionLight;
    AudioManager mAudioManager;
    AudioManagerInternal mAudioManagerInternal;
    private int mAutoGroupAtCount;
    @GuardedBy({"mNotificationLock"})
    final ArrayMap<Integer, ArrayMap<String, String>> mAutobundledSummaries;
    private Binder mCallNotificationToken;
    private int mCallState;
    private ICompanionDeviceManager mCompanionManager;
    private ConditionProviders mConditionProviders;
    private IDeviceIdleController mDeviceIdleController;
    private boolean mDisableNotificationEffects;
    private DevicePolicyManagerInternal mDpm;
    private List<ComponentName> mEffectsSuppressors;
    @GuardedBy({"mNotificationLock"})
    final ArrayList<NotificationRecord> mEnqueuedNotifications;
    private long[] mFallbackVibrationPattern;
    final IBinder mForegroundToken;
    private GroupHelper mGroupHelper;
    private WorkerHandler mHandler;
    boolean mHasLight;
    @GuardedBy({"mNotificationLock"})
    final ArrayMap<String, INotificationStatusCallBack> mINotificationStatusCallBackList;
    private AudioAttributes mInCallNotificationAudioAttributes;
    private Uri mInCallNotificationUri;
    private float mInCallNotificationVolume;
    protected boolean mInCallStateOffHook;
    private final BroadcastReceiver mIntentReceiver;
    private final NotificationManagerInternal mInternalService;
    private int mInterruptionFilter;
    private boolean mIsAutomotive;
    private boolean mIsTelevision;
    private long mLastOverRateLogTime;
    boolean mLightEnabled;
    ArrayList<String> mLights;
    private int mListenerHints;
    private NotificationListeners mListeners;
    private final SparseArray<ArraySet<ComponentName>> mListenersDisablingEffects;
    protected final BroadcastReceiver mLocaleChangeReceiver;
    private boolean mLockScreenAllowSecureNotifications;
    private float mMaxPackageEnqueueRate;
    private MetricsLogger mMetricsLogger;
    @VisibleForTesting
    final NotificationDelegate mNotificationDelegate;
    private boolean mNotificationEffectsEnabledForAutomotive;
    private Light mNotificationLight;
    @GuardedBy({"mNotificationLock"})
    final ArrayList<NotificationRecord> mNotificationList;
    final Object mNotificationLock;
    private NotificationPolicy mNotificationPolicy;
    boolean mNotificationPulseEnabled;
    private final BroadcastReceiver mNotificationTimeoutReceiver;
    @GuardedBy({"mNotificationLock"})
    final ArrayMap<String, NotificationRecord> mNotificationsByKey;
    private boolean mOsdEnabled;
    private final BroadcastReceiver mPackageIntentReceiver;
    private IPackageManager mPackageManager;
    private PackageManager mPackageManagerClient;
    private AtomicFile mPolicyFile;
    private PreferencesHelper mPreferencesHelper;
    private RankingHandler mRankingHandler;
    private RankingHelper mRankingHelper;
    private final HandlerThread mRankingThread;
    private final BroadcastReceiver mRestoreReceiver;
    private RoleObserver mRoleObserver;
    private final SavePolicyFileRunnable mSavePolicyFile;
    boolean mScreenOn;
    @VisibleForTesting
    final IBinder mService;
    private SettingsObserver mSettingsObserver;
    private SnoozeHelper mSnoozeHelper;
    private String mSoundNotificationKey;
    StatusBarManagerInternal mStatusBar;
    final ArrayMap<String, NotificationRecord> mSummaryByGroupKey;
    boolean mSystemReady;
    private boolean mToastEnabled;
    final ArrayList<ToastRecord> mToastQueue;
    private List<ArrayList<ToastRecord>> mToastQueueList;
    private IUriGrantsManager mUgm;
    private UriGrantsManagerInternal mUgmInternal;
    private UserManager mUm;
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
    static final String[] DEFAULT_ALLOWED_ADJUSTMENTS = {"key_contextual_actions", "key_text_replies"};
    static final String[] NON_BLOCKABLE_DEFAULT_ROLES = {"android.app.role.DIALER", "android.app.role.EMERGENCY"};
    private static final String ACTION_NOTIFICATION_TIMEOUT = NotificationManagerService.class.getSimpleName() + ".TIMEOUT";
    private static int screenNum = FeatureOption.FO_SCREEN_NUM;
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
        String defaultListenerAccess = getContext().getResources().getString(17039706);
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
        String defaultDndAccess = getContext().getResources().getString(17039705);
        if (defaultDndAccess != null) {
            for (String whitelisted2 : defaultDndAccess.split(":")) {
                try {
                    getBinderService().setNotificationPolicyAccessGranted(whitelisted2, true);
                } catch (RemoteException e2) {
                    e2.printStackTrace();
                }
            }
        }
        setDefaultAssistantForUser(userId);
    }

    protected void setDefaultAssistantForUser(int userId) {
        List<ComponentName> validAssistants = new ArrayList<>(this.mAssistants.queryPackageForServices(null, 786432, userId));
        List<String> candidateStrs = new ArrayList<>();
        candidateStrs.add(DeviceConfig.getProperty("systemui", "nas_default_service"));
        candidateStrs.add(getContext().getResources().getString(17039699));
        for (String candidateStr : candidateStrs) {
            if (!TextUtils.isEmpty(candidateStr)) {
                ComponentName candidate = ComponentName.unflattenFromString(candidateStr);
                if (candidate != null && validAssistants.contains(candidate)) {
                    setNotificationAssistantAccessGrantedForUserInternal(candidate, userId, true);
                    Slog.d(TAG, String.format("Set default NAS to be %s in %d", candidateStr, Integer.valueOf(userId)));
                    return;
                }
                Slog.w(TAG, "Invalid default NAS config is found: " + candidateStr);
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:45:0x00b6 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:56:0x0026 A[ADDED_TO_REGION, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    void readPolicyXml(java.io.InputStream r8, boolean r9, int r10) throws org.xmlpull.v1.XmlPullParserException, java.lang.NumberFormatException, java.io.IOException {
        /*
            r7 = this;
            org.xmlpull.v1.XmlPullParser r0 = android.util.Xml.newPullParser()
            java.nio.charset.Charset r1 = java.nio.charset.StandardCharsets.UTF_8
            java.lang.String r1 = r1.name()
            r0.setInput(r8, r1)
            java.lang.String r1 = "notification-policy"
            com.android.internal.util.XmlUtils.beginDocument(r0, r1)
            r1 = 0
            r2 = 1
            if (r9 == 0) goto L21
            android.os.UserManager r3 = r7.mUm
            boolean r3 = r3.isManagedProfile(r10)
            if (r3 == 0) goto L21
            r3 = r2
            goto L22
        L21:
            r3 = 0
        L22:
            int r4 = r0.getDepth()
        L26:
            boolean r5 = com.android.internal.util.XmlUtils.nextElementWithin(r0, r4)
            if (r5 == 0) goto Lcc
            java.lang.String r5 = r0.getName()
            java.lang.String r6 = "zen"
            boolean r5 = r6.equals(r5)
            if (r5 == 0) goto L3f
            com.android.server.notification.ZenModeHelper r5 = r7.mZenModeHelper
            r5.readXml(r0, r9, r10)
            goto L51
        L3f:
            java.lang.String r5 = r0.getName()
            java.lang.String r6 = "ranking"
            boolean r5 = r6.equals(r5)
            if (r5 == 0) goto L51
            com.android.server.notification.PreferencesHelper r5 = r7.mPreferencesHelper
            r5.readXml(r0, r9, r10)
        L51:
            com.android.server.notification.NotificationManagerService$NotificationListeners r5 = r7.mListeners
            com.android.server.notification.ManagedServices$Config r5 = r5.getConfig()
            java.lang.String r5 = r5.xmlTag
            java.lang.String r6 = r0.getName()
            boolean r5 = r5.equals(r6)
            if (r5 == 0) goto L6f
            if (r3 == 0) goto L66
            goto L26
        L66:
            com.android.server.notification.NotificationManagerService$NotificationListeners r5 = r7.mListeners
            com.android.internal.util.function.TriPredicate<java.lang.String, java.lang.Integer, java.lang.String> r6 = r7.mAllowedManagedServicePackages
            r5.readXml(r0, r6, r9, r10)
            r1 = 1
            goto Laa
        L6f:
            com.android.server.notification.NotificationManagerService$NotificationAssistants r5 = r7.mAssistants
            com.android.server.notification.ManagedServices$Config r5 = r5.getConfig()
            java.lang.String r5 = r5.xmlTag
            java.lang.String r6 = r0.getName()
            boolean r5 = r5.equals(r6)
            if (r5 == 0) goto L8d
            if (r3 == 0) goto L84
            goto L26
        L84:
            com.android.server.notification.NotificationManagerService$NotificationAssistants r5 = r7.mAssistants
            com.android.internal.util.function.TriPredicate<java.lang.String, java.lang.Integer, java.lang.String> r6 = r7.mAllowedManagedServicePackages
            r5.readXml(r0, r6, r9, r10)
            r1 = 1
            goto Laa
        L8d:
            com.android.server.notification.ConditionProviders r5 = r7.mConditionProviders
            com.android.server.notification.ManagedServices$Config r5 = r5.getConfig()
            java.lang.String r5 = r5.xmlTag
            java.lang.String r6 = r0.getName()
            boolean r5 = r5.equals(r6)
            if (r5 == 0) goto Laa
            if (r3 == 0) goto La2
            goto L26
        La2:
            com.android.server.notification.ConditionProviders r5 = r7.mConditionProviders
            com.android.internal.util.function.TriPredicate<java.lang.String, java.lang.Integer, java.lang.String> r6 = r7.mAllowedManagedServicePackages
            r5.readXml(r0, r6, r9, r10)
            r1 = 1
        Laa:
            java.lang.String r5 = r0.getName()
            java.lang.String r6 = "allow-secure-notifications-on-lockscreen"
            boolean r5 = r6.equals(r5)
            if (r5 == 0) goto L26
            if (r9 == 0) goto Lbc
            if (r10 == 0) goto Lbc
            goto L26
        Lbc:
            r5 = 0
            java.lang.String r6 = "value"
            java.lang.String r5 = r0.getAttributeValue(r5, r6)
            boolean r5 = safeBoolean(r5, r2)
            r7.mLockScreenAllowSecureNotifications = r5
            goto L26
        Lcc:
            if (r1 != 0) goto Le0
            com.android.server.notification.NotificationManagerService$NotificationListeners r2 = r7.mListeners
            r2.migrateToXml()
            com.android.server.notification.NotificationManagerService$NotificationAssistants r2 = r7.mAssistants
            r2.migrateToXml()
            com.android.server.notification.ConditionProviders r2 = r7.mConditionProviders
            r2.migrateToXml()
            r7.handleSavePolicyFile()
        Le0:
            com.android.server.notification.NotificationManagerService$NotificationAssistants r2 = r7.mAssistants
            r2.resetDefaultAssistantsIfNecessary()
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.readPolicyXml(java.io.InputStream, boolean, int):void");
    }

    @VisibleForTesting
    protected void loadPolicyFile() {
        if (DBG) {
            Slog.d(TAG, "loadPolicyFile");
        }
        synchronized (this.mPolicyFile) {
            InputStream infile = null;
            try {
                try {
                    try {
                        try {
                            infile = this.mPolicyFile.openRead();
                            readPolicyXml(infile, false, -1);
                            IoUtils.closeQuietly(infile);
                        } catch (IOException e) {
                            Log.wtf(TAG, "Unable to read notification policy", e);
                            IoUtils.closeQuietly(infile);
                        }
                    } catch (XmlPullParserException e2) {
                        Log.wtf(TAG, "Unable to parse notification policy", e2);
                        IoUtils.closeQuietly(infile);
                    }
                } catch (NumberFormatException e3) {
                    Log.wtf(TAG, "Unable to parse notification policy", e3);
                    IoUtils.closeQuietly(infile);
                }
            } catch (FileNotFoundException e4) {
                readDefaultApprovedServices(0);
                IoUtils.closeQuietly(infile);
            }
        }
    }

    @VisibleForTesting
    protected void handleSavePolicyFile() {
        if (!IoThread.getHandler().hasCallbacks(this.mSavePolicyFile)) {
            IoThread.getHandler().post(this.mSavePolicyFile);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SavePolicyFileRunnable implements Runnable {
        private SavePolicyFileRunnable() {
        }

        /* synthetic */ SavePolicyFileRunnable(NotificationManagerService x0, AnonymousClass1 x1) {
            this();
        }

        @Override // java.lang.Runnable
        public void run() {
            if (NotificationManagerService.DBG) {
                Slog.d(NotificationManagerService.TAG, "handleSavePolicyFile");
            }
            synchronized (NotificationManagerService.this.mPolicyFile) {
                try {
                    FileOutputStream stream = NotificationManagerService.this.mPolicyFile.startWrite();
                    try {
                        NotificationManagerService.this.writePolicyXml(stream, false, -1);
                        NotificationManagerService.this.mPolicyFile.finishWrite(stream);
                    } catch (IOException e) {
                        Slog.w(NotificationManagerService.TAG, "Failed to save policy file, restoring backup", e);
                        NotificationManagerService.this.mPolicyFile.failWrite(stream);
                    }
                } catch (IOException e2) {
                    Slog.w(NotificationManagerService.TAG, "Failed to save policy file", e2);
                    return;
                }
            }
            BackupManager.dataChanged(NotificationManagerService.this.getContext().getPackageName());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writePolicyXml(OutputStream stream, boolean forBackup, int userId) throws IOException {
        XmlSerializer out = new FastXmlSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);
        out.startTag(null, TAG_NOTIFICATION_POLICY);
        out.attribute(null, "version", Integer.toString(1));
        this.mZenModeHelper.writeXml(out, forBackup, null, userId);
        this.mPreferencesHelper.writeXml(out, forBackup, userId);
        this.mListeners.writeXml(out, forBackup, userId);
        this.mAssistants.writeXml(out, forBackup, userId);
        this.mConditionProviders.writeXml(out, forBackup, userId);
        if (!forBackup || userId == 0) {
            writeSecureNotificationsPolicy(out);
        }
        out.endTag(null, TAG_NOTIFICATION_POLICY);
        out.endDocument();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ToastRecord {
        ITransientNotification callback;
        int displayId;
        int duration;
        final int pid;
        final String pkg;
        int sharedId;
        Binder token;

        ToastRecord(int pid, String pkg, ITransientNotification callback, int duration, Binder token, int displayId, int sharedId) {
            this.pid = pid;
            this.pkg = pkg;
            this.callback = callback;
            this.duration = duration;
            this.token = token;
            this.displayId = displayId;
            this.sharedId = sharedId;
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
            return "ToastRecord{" + Integer.toHexString(System.identityHashCode(this)) + " pkg=" + this.pkg + " callback=" + this.callback + " duration=" + this.duration + " displayId=" + this.displayId + " sharedId=" + this.sharedId;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.notification.NotificationManagerService$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 implements NotificationDelegate {
        AnonymousClass1() {
        }

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
                    Slog.w(NotificationManagerService.TAG, "No notification with key: " + key);
                    return;
                }
                long now = System.currentTimeMillis();
                MetricsLogger.action(r.getItemLogMaker().setType(4).addTaggedData(798, Integer.valueOf(nv.rank)).addTaggedData(1395, Integer.valueOf(nv.count)));
                EventLogTags.writeNotificationClicked(key, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now), nv.rank, nv.count);
                StatusBarNotification sbn = r.sbn;
                NotificationManagerService.this.cancelNotification(callingUid, callingPid, sbn.getPackageName(), sbn.getTag(), sbn.getId(), 16, 64, false, r.getUserId(), 1, nv.rank, nv.count, null);
                nv.recycle();
                NotificationManagerService.this.reportUserInteraction(r);
            }
        }

        @Override // com.android.server.notification.NotificationDelegate
        public void onNotificationActionClick(int callingUid, int callingPid, String key, int actionIndex, Notification.Action action, NotificationVisibility nv, boolean generatedByAssistant) {
            NotificationManagerService.this.exitIdle();
            synchronized (NotificationManagerService.this.mNotificationLock) {
                try {
                    try {
                        NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                        if (r == null) {
                            Slog.w(NotificationManagerService.TAG, "No notification with key: " + key);
                            return;
                        }
                        long now = System.currentTimeMillis();
                        int i = 1;
                        LogMaker addTaggedData = r.getLogMaker(now).setCategory(129).setType(4).setSubtype(actionIndex).addTaggedData(798, Integer.valueOf(nv.rank)).addTaggedData(1395, Integer.valueOf(nv.count)).addTaggedData(1601, Integer.valueOf(action.isContextual() ? 1 : 0));
                        if (!generatedByAssistant) {
                            i = 0;
                        }
                        MetricsLogger.action(addTaggedData.addTaggedData(1600, Integer.valueOf(i)).addTaggedData(1629, Integer.valueOf(nv.location.toMetricsEventEnum())));
                        EventLogTags.writeNotificationActionClicked(key, actionIndex, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now), nv.rank, nv.count);
                        nv.recycle();
                        NotificationManagerService.this.reportUserInteraction(r);
                        NotificationManagerService.this.mAssistants.notifyAssistantActionClicked(r.sbn, actionIndex, action, generatedByAssistant);
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

        @Override // com.android.server.notification.NotificationDelegate
        public void onNotificationClear(int callingUid, int callingPid, String pkg, String tag, int id, int userId, String key, int dismissalSurface, int dismissalSentiment, NotificationVisibility nv) {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                try {
                    try {
                        try {
                            NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                            if (r != null) {
                                try {
                                    r.recordDismissalSurface(dismissalSurface);
                                    r.recordDismissalSentiment(dismissalSentiment);
                                } catch (Throwable th) {
                                    th = th;
                                    throw th;
                                }
                            }
                            NotificationManagerService.this.cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 66, true, userId, 2, nv.rank, nv.count, null);
                            nv.recycle();
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } catch (Throwable th4) {
                    th = th4;
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
        public void onNotificationError(int callingUid, int callingPid, final String pkg, final String tag, final int id, final int uid, final int initialPid, final String message, int userId) {
            boolean fgService;
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationRecord r = NotificationManagerService.this.findNotificationLocked(pkg, tag, id, userId);
                fgService = (r == null || (r.getNotification().flags & 64) == 0) ? false : true;
            }
            NotificationManagerService.this.cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId, 4, null);
            if (fgService) {
                Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$1$xhbVsydQBNNW5m21WjLTPrHQojA
                    public final void runOrThrow() {
                        NotificationManagerService.AnonymousClass1.this.lambda$onNotificationError$0$NotificationManagerService$1(uid, initialPid, pkg, tag, id, message);
                    }
                });
            }
        }

        public /* synthetic */ void lambda$onNotificationError$0$NotificationManagerService$1(int uid, int initialPid, String pkg, String tag, int id, String message) throws Exception {
            IActivityManager iActivityManager = NotificationManagerService.this.mAm;
            iActivityManager.crashApplication(uid, initialPid, pkg, -1, "Bad notification(tag=" + tag + ", id=" + id + ") posted from package " + pkg + ", crashing app(uid=" + uid + ", pid=" + initialPid + "): " + message, true);
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
                        }
                        boolean z = true;
                        r.setVisibility(true, nv.rank, nv.count);
                        if (nv.location != NotificationVisibility.NotificationLocation.LOCATION_FIRST_HEADS_UP) {
                            z = false;
                        }
                        boolean isHun = z;
                        if (isHun || r.hasBeenVisiblyExpanded()) {
                            NotificationManagerService.this.logSmartSuggestionsVisible(r, nv.location.toMetricsEventEnum());
                        }
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
        public void onNotificationExpansionChanged(String key, boolean userAction, boolean expanded, int notificationLocation) {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                if (r != null) {
                    r.stats.onExpansionChanged(userAction, expanded);
                    if (r.hasBeenVisiblyExpanded()) {
                        NotificationManagerService.this.logSmartSuggestionsVisible(r, notificationLocation);
                    }
                    if (userAction) {
                        MetricsLogger.action(r.getItemLogMaker().setType(expanded ? 3 : 14));
                    }
                    if (expanded && userAction) {
                        r.recordExpanded();
                        NotificationManagerService.this.reportUserInteraction(r);
                    }
                    NotificationManagerService.this.mAssistants.notifyAssistantExpansionChangedLocked(r.sbn, userAction, expanded);
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
                    NotificationManagerService.this.mMetricsLogger.write(r.getLogMaker().setCategory(1590).setType(4));
                    NotificationManagerService.this.reportUserInteraction(r);
                    NotificationManagerService.this.mAssistants.notifyAssistantNotificationDirectReplyLocked(r.sbn);
                }
            }
        }

        @Override // com.android.server.notification.NotificationDelegate
        public void onNotificationSmartSuggestionsAdded(String key, int smartReplyCount, int smartActionCount, boolean generatedByAssistant, boolean editBeforeSending) {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                if (r != null) {
                    r.setNumSmartRepliesAdded(smartReplyCount);
                    r.setNumSmartActionsAdded(smartActionCount);
                    r.setSuggestionsGeneratedByAssistant(generatedByAssistant);
                    r.setEditChoicesBeforeSending(editBeforeSending);
                }
            }
        }

        @Override // com.android.server.notification.NotificationDelegate
        public void onNotificationSmartReplySent(String key, int replyIndex, CharSequence reply, int notificationLocation, boolean modifiedBeforeSending) {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                if (r != null) {
                    int i = 1;
                    LogMaker addTaggedData = r.getLogMaker().setCategory(1383).setSubtype(replyIndex).addTaggedData(1600, Integer.valueOf(r.getSuggestionsGeneratedByAssistant() ? 1 : 0)).addTaggedData(1629, Integer.valueOf(notificationLocation)).addTaggedData(1647, Integer.valueOf(r.getEditChoicesBeforeSending() ? 1 : 0));
                    if (!modifiedBeforeSending) {
                        i = 0;
                    }
                    LogMaker logMaker = addTaggedData.addTaggedData(1648, Integer.valueOf(i));
                    NotificationManagerService.this.mMetricsLogger.write(logMaker);
                    NotificationManagerService.this.reportUserInteraction(r);
                    NotificationManagerService.this.mAssistants.notifyAssistantSuggestedReplySent(r.sbn, reply, r.getSuggestionsGeneratedByAssistant());
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

        @Override // com.android.server.notification.NotificationDelegate
        public void onNotificationBubbleChanged(String key, boolean isBubble) {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(key);
                if (r != null) {
                    StatusBarNotification n = r.sbn;
                    int callingUid = n.getUid();
                    String pkg = n.getPackageName();
                    if (isBubble && NotificationManagerService.this.isNotificationAppropriateToBubble(r, pkg, callingUid, null)) {
                        r.getNotification().flags |= 4096;
                    } else {
                        r.getNotification().flags &= -4097;
                    }
                }
            }
        }
    }

    @VisibleForTesting
    void logSmartSuggestionsVisible(NotificationRecord r, int notificationLocation) {
        if ((r.getNumSmartRepliesAdded() > 0 || r.getNumSmartActionsAdded() > 0) && !r.hasSeenSmartReplies()) {
            r.setSeenSmartReplies(true);
            LogMaker logMaker = r.getLogMaker().setCategory(1382).addTaggedData(1384, Integer.valueOf(r.getNumSmartRepliesAdded())).addTaggedData(1599, Integer.valueOf(r.getNumSmartActionsAdded())).addTaggedData(1600, Integer.valueOf(r.getSuggestionsGeneratedByAssistant() ? 1 : 0)).addTaggedData(1629, Integer.valueOf(notificationLocation)).addTaggedData(1647, Integer.valueOf(r.getEditChoicesBeforeSending() ? 1 : 0));
            this.mMetricsLogger.write(logMaker);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
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
    @GuardedBy({"mNotificationLock"})
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
    @GuardedBy({"mNotificationLock"})
    public void clearLightsLocked() {
        this.mLights.clear();
        updateLightsLocked();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_BADGING_URI;
        private final Uri NOTIFICATION_BUBBLES_URI_GLOBAL;
        private final Uri NOTIFICATION_BUBBLES_URI_SECURE;
        private final Uri NOTIFICATION_LIGHT_PULSE_URI;
        private final Uri NOTIFICATION_RATE_LIMIT_URI;

        SettingsObserver(Handler handler) {
            super(handler);
            this.NOTIFICATION_BADGING_URI = Settings.Secure.getUriFor("notification_badging");
            this.NOTIFICATION_BUBBLES_URI_GLOBAL = Settings.Global.getUriFor("notification_bubbles");
            this.NOTIFICATION_BUBBLES_URI_SECURE = Settings.Secure.getUriFor("notification_bubbles");
            this.NOTIFICATION_LIGHT_PULSE_URI = Settings.System.getUriFor("notification_light_pulse");
            this.NOTIFICATION_RATE_LIMIT_URI = Settings.Global.getUriFor("max_notification_enqueue_rate");
        }

        void observe() {
            ContentResolver resolver = NotificationManagerService.this.getContext().getContentResolver();
            resolver.registerContentObserver(this.NOTIFICATION_BADGING_URI, false, this, -1);
            resolver.registerContentObserver(this.NOTIFICATION_LIGHT_PULSE_URI, false, this, -1);
            resolver.registerContentObserver(this.NOTIFICATION_RATE_LIMIT_URI, false, this, -1);
            resolver.registerContentObserver(this.NOTIFICATION_BUBBLES_URI_GLOBAL, false, this, -1);
            resolver.registerContentObserver(this.NOTIFICATION_BUBBLES_URI_SECURE, false, this, -1);
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
                    NotificationManagerService notificationManagerService = NotificationManagerService.this;
                    notificationManagerService.mNotificationPulseEnabled = pulseEnabled;
                    notificationManagerService.updateNotificationPulse();
                }
            }
            if (uri == null || this.NOTIFICATION_RATE_LIMIT_URI.equals(uri)) {
                NotificationManagerService notificationManagerService2 = NotificationManagerService.this;
                notificationManagerService2.mMaxPackageEnqueueRate = Settings.Global.getFloat(resolver, "max_notification_enqueue_rate", notificationManagerService2.mMaxPackageEnqueueRate);
            }
            if (uri == null || this.NOTIFICATION_BADGING_URI.equals(uri)) {
                NotificationManagerService.this.mPreferencesHelper.updateBadgingEnabled();
            }
            if (uri == null || this.NOTIFICATION_BUBBLES_URI_GLOBAL.equals(uri)) {
                syncBubbleSettings(resolver, this.NOTIFICATION_BUBBLES_URI_GLOBAL);
                NotificationManagerService.this.mPreferencesHelper.updateBubblesEnabled();
            }
            if (this.NOTIFICATION_BUBBLES_URI_SECURE.equals(uri)) {
                syncBubbleSettings(resolver, this.NOTIFICATION_BUBBLES_URI_SECURE);
            }
        }

        private void syncBubbleSettings(ContentResolver resolver, Uri settingToFollow) {
            boolean followSecureSetting = settingToFollow.equals(this.NOTIFICATION_BUBBLES_URI_SECURE);
            int secureSettingValue = Settings.Secure.getInt(resolver, "notification_bubbles", 1);
            int globalSettingValue = Settings.Global.getInt(resolver, "notification_bubbles", 1);
            if (globalSettingValue == secureSettingValue) {
                return;
            }
            if (followSecureSetting) {
                Settings.Global.putInt(resolver, "notification_bubbles", secureSettingValue);
            } else {
                Settings.Secure.putInt(resolver, "notification_bubbles", globalSettingValue);
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
        this.mInCallStateOffHook = false;
        this.mCallNotificationToken = null;
        this.mNotificationLock = new Object();
        this.mNotificationList = new ArrayList<>();
        this.mNotificationsByKey = new ArrayMap<>();
        this.mINotificationStatusCallBackList = new ArrayMap<>();
        this.mEnqueuedNotifications = new ArrayList<>();
        this.mAutobundledSummaries = new ArrayMap<>();
        this.mToastQueue = new ArrayList<>();
        this.mSummaryByGroupKey = new ArrayMap<>();
        this.mLights = new ArrayList<>();
        this.mUserProfiles = new ManagedServices.UserProfiles();
        this.mLockScreenAllowSecureNotifications = true;
        this.mMaxPackageEnqueueRate = DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE;
        this.mSavePolicyFile = new SavePolicyFileRunnable(this, null);
        this.mNotificationDelegate = new AnonymousClass1();
        this.mLocaleChangeReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.LOCALE_CHANGED".equals(intent.getAction())) {
                    SystemNotificationChannels.createAll(context2);
                    NotificationManagerService.this.mZenModeHelper.updateDefaultZenRules();
                    NotificationManagerService.this.mPreferencesHelper.onLocaleChanged(context2, ActivityManager.getCurrentUser());
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
                        } catch (Throwable th) {
                            th = th;
                        }
                        try {
                            NotificationRecord record = NotificationManagerService.this.findNotificationByKeyLocked(intent.getStringExtra(NotificationManagerService.EXTRA_KEY));
                            if (record != null) {
                                NotificationManagerService.this.cancelNotification(record.sbn.getUid(), record.sbn.getInitialPid(), record.sbn.getPackageName(), record.sbn.getTag(), record.sbn.getId(), 0, 64, true, record.getUserId(), 19, null);
                            }
                        } catch (Throwable th2) {
                            th = th2;
                            throw th;
                        }
                    }
                }
            }
        };
        this.mPackageIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.5
            /* JADX WARN: Multi-variable type inference failed */
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                boolean packageChanged;
                boolean packageChanged2;
                boolean removingPackage;
                String pkgName;
                String[] pkgList;
                int i;
                int[] uidList;
                boolean removingPackage2;
                int changeUserId;
                boolean removingPackage3;
                int i2;
                int i3;
                int i4;
                int changeUserId2;
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                boolean queryRestart = false;
                boolean queryRemove = false;
                boolean packageChanged3 = false;
                boolean cancelNotifications = true;
                boolean hideNotifications = false;
                boolean unhideNotifications = false;
                if (action.equals("android.intent.action.PACKAGE_ADDED")) {
                    packageChanged = false;
                    packageChanged2 = false;
                } else {
                    boolean equals = action.equals("android.intent.action.PACKAGE_REMOVED");
                    queryRemove = equals;
                    if (!equals && !action.equals("android.intent.action.PACKAGE_RESTARTED")) {
                        boolean equals2 = action.equals("android.intent.action.PACKAGE_CHANGED");
                        packageChanged3 = equals2;
                        if (equals2) {
                            packageChanged = packageChanged3;
                            packageChanged2 = false;
                        } else {
                            boolean equals3 = action.equals("android.intent.action.QUERY_PACKAGE_RESTART");
                            queryRestart = equals3;
                            if (!equals3 && !action.equals("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE") && !action.equals("android.intent.action.PACKAGES_SUSPENDED") && !action.equals("android.intent.action.PACKAGES_UNSUSPENDED") && !action.equals("android.intent.action.DISTRACTING_PACKAGES_CHANGED")) {
                                return;
                            }
                        }
                    }
                    packageChanged = packageChanged3;
                    packageChanged2 = queryRestart;
                }
                int changeUserId3 = intent.getIntExtra("android.intent.extra.user_handle", -1);
                boolean removingPackage4 = queryRemove && !intent.getBooleanExtra("android.intent.extra.REPLACING", false);
                if (NotificationManagerService.DBG) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("action=");
                    sb.append(action);
                    sb.append(" removing=");
                    removingPackage = removingPackage4;
                    sb.append(removingPackage);
                    Slog.i(NotificationManagerService.TAG, sb.toString());
                } else {
                    removingPackage = removingPackage4;
                }
                if (action.equals("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE")) {
                    pkgList = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                    uidList = intent.getIntArrayExtra("android.intent.extra.changed_uid_list");
                    i = 0;
                } else if (action.equals("android.intent.action.PACKAGES_SUSPENDED")) {
                    pkgList = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                    uidList = intent.getIntArrayExtra("android.intent.extra.changed_uid_list");
                    cancelNotifications = false;
                    hideNotifications = true;
                    i = 0;
                } else if (action.equals("android.intent.action.PACKAGES_UNSUSPENDED")) {
                    pkgList = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                    uidList = intent.getIntArrayExtra("android.intent.extra.changed_uid_list");
                    cancelNotifications = false;
                    unhideNotifications = true;
                    i = 0;
                } else if (action.equals("android.intent.action.DISTRACTING_PACKAGES_CHANGED")) {
                    int distractionRestrictions = intent.getIntExtra("android.intent.extra.distraction_restrictions", 0);
                    if ((distractionRestrictions & 2) != 0) {
                        String[] pkgList2 = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                        int[] uidList2 = intent.getIntArrayExtra("android.intent.extra.changed_uid_list");
                        cancelNotifications = false;
                        hideNotifications = true;
                        pkgList = pkgList2;
                        uidList = uidList2;
                    } else {
                        String[] pkgList3 = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
                        int[] uidList3 = intent.getIntArrayExtra("android.intent.extra.changed_uid_list");
                        cancelNotifications = false;
                        unhideNotifications = true;
                        pkgList = pkgList3;
                        uidList = uidList3;
                    }
                    i = 0;
                } else if (packageChanged2) {
                    pkgList = intent.getStringArrayExtra("android.intent.extra.PACKAGES");
                    uidList = new int[]{intent.getIntExtra("android.intent.extra.UID", -1)};
                    i = 0;
                } else {
                    Uri uri = intent.getData();
                    if (uri == null || (pkgName = uri.getSchemeSpecificPart()) == null) {
                        return;
                    }
                    if (packageChanged) {
                        try {
                            int enabled = NotificationManagerService.this.mPackageManager.getApplicationEnabledSetting(pkgName, changeUserId3 != -1 ? changeUserId3 : 0);
                            if (enabled == 1 || enabled == 0) {
                                cancelNotifications = false;
                            }
                        } catch (RemoteException e) {
                        } catch (IllegalArgumentException e2) {
                            if (NotificationManagerService.DBG) {
                                Slog.i(NotificationManagerService.TAG, "Exception trying to look up app enabled setting", e2);
                            }
                        }
                    }
                    i = 0;
                    pkgList = new String[]{pkgName};
                    uidList = new int[]{intent.getIntExtra("android.intent.extra.UID", -1)};
                }
                if (pkgList == null || pkgList.length <= 0) {
                    removingPackage2 = removingPackage;
                    changeUserId = changeUserId3;
                } else {
                    int length = pkgList.length;
                    int i5 = i;
                    while (i5 < length) {
                        String pkgName2 = pkgList[i5];
                        if (cancelNotifications) {
                            i2 = i;
                            removingPackage3 = removingPackage;
                            i3 = i5;
                            i4 = length;
                            changeUserId2 = changeUserId3;
                            NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, pkgName2, null, 0, 0, !packageChanged2 ? 1 : i, changeUserId2, 5, null);
                        } else {
                            removingPackage3 = removingPackage;
                            i2 = i;
                            i3 = i5;
                            i4 = length;
                            changeUserId2 = changeUserId3;
                            if (hideNotifications) {
                                NotificationManagerService.this.hideNotificationsForPackages(pkgList);
                            } else if (unhideNotifications) {
                                NotificationManagerService.this.unhideNotificationsForPackages(pkgList);
                            }
                        }
                        i5 = i3 + 1;
                        i = i2;
                        removingPackage = removingPackage3;
                        length = i4;
                        changeUserId3 = changeUserId2;
                    }
                    removingPackage2 = removingPackage;
                    changeUserId = changeUserId3;
                }
                NotificationManagerService.this.mHandler.scheduleOnPackageChanged(removingPackage2, changeUserId, pkgList, uidList);
            }
        };
        this.mIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.notification.NotificationManagerService.6
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                String action = intent.getAction();
                if (action.equals("android.intent.action.SCREEN_ON")) {
                    NotificationManagerService notificationManagerService = NotificationManagerService.this;
                    notificationManagerService.mScreenOn = true;
                    notificationManagerService.updateNotificationPulse();
                } else if (action.equals("android.intent.action.SCREEN_OFF")) {
                    NotificationManagerService notificationManagerService2 = NotificationManagerService.this;
                    notificationManagerService2.mScreenOn = false;
                    notificationManagerService2.updateNotificationPulse();
                } else if (action.equals("android.intent.action.PHONE_STATE")) {
                    NotificationManagerService.this.mInCallStateOffHook = TelephonyManager.EXTRA_STATE_OFFHOOK.equals(intent.getStringExtra("state"));
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
                    int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    NotificationManagerService.this.mUserProfiles.updateCache(context2);
                    if (!NotificationManagerService.this.mUserProfiles.isManagedProfile(userId)) {
                        NotificationManagerService.this.mSettingsObserver.update(null);
                        NotificationManagerService.this.mConditionProviders.onUserSwitched(userId);
                        NotificationManagerService.this.mListeners.onUserSwitched(userId);
                        NotificationManagerService.this.mZenModeHelper.onUserSwitched(userId);
                        NotificationManagerService.this.mPreferencesHelper.onUserSwitched(userId);
                    }
                    NotificationManagerService.this.mAssistants.onUserSwitched(userId);
                } else if (action.equals("android.intent.action.USER_ADDED")) {
                    int userId2 = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    if (userId2 != -10000) {
                        NotificationManagerService.this.mUserProfiles.updateCache(context2);
                        if (!NotificationManagerService.this.mUserProfiles.isManagedProfile(userId2)) {
                            NotificationManagerService.this.readDefaultApprovedServices(userId2);
                        }
                    }
                } else if (action.equals("android.intent.action.USER_REMOVED")) {
                    int userId3 = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    NotificationManagerService.this.mUserProfiles.updateCache(context2);
                    NotificationManagerService.this.mZenModeHelper.onUserRemoved(userId3);
                    NotificationManagerService.this.mPreferencesHelper.onUserRemoved(userId3);
                    NotificationManagerService.this.mListeners.onUserRemoved(userId3);
                    NotificationManagerService.this.mConditionProviders.onUserRemoved(userId3);
                    NotificationManagerService.this.mAssistants.onUserRemoved(userId3);
                    NotificationManagerService.this.handleSavePolicyFile();
                } else if (action.equals("android.intent.action.USER_UNLOCKED")) {
                    int userId4 = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                    NotificationManagerService.this.mUserProfiles.updateCache(context2);
                    NotificationManagerService.this.mAssistants.onUserUnlocked(userId4);
                    if (!NotificationManagerService.this.mUserProfiles.isManagedProfile(userId4)) {
                        NotificationManagerService.this.mConditionProviders.onUserUnlocked(userId4);
                        NotificationManagerService.this.mListeners.onUserUnlocked(userId4);
                        NotificationManagerService.this.mZenModeHelper.onUserUnlocked(userId4);
                        NotificationManagerService.this.mPreferencesHelper.onUserUnlocked(userId4);
                    }
                }
            }
        };
        this.mToastEnabled = true;
        this.mOsdEnabled = true;
        this.mService = new INotificationManager.Stub() { // from class: com.android.server.notification.NotificationManagerService.10
            public void enqueueToast(String pkg, ITransientNotification callback, int duration, int displayId, int initSharedId) {
                int sharedId;
                int sharedId2;
                ArrayList<ToastRecord> arrayList;
                ArrayList<ToastRecord> arrayList2;
                int j;
                if (initSharedId != -1 || pkg == null) {
                    sharedId = initSharedId;
                } else {
                    try {
                        sharedId = xpWindowManager.getWindowManager().getSharedId(pkg);
                    } catch (Exception e) {
                        sharedId2 = -100;
                    }
                }
                sharedId2 = sharedId;
                if (NotificationManagerService.DBG) {
                    Slog.i(NotificationManagerService.TAG, "enqueueToast pkg=" + pkg + " callback=" + callback + " duration=" + duration + " displayId=" + displayId + " sharedId=" + sharedId2 + " initSharedId=" + initSharedId);
                }
                if (pkg == null || callback == null) {
                    Slog.e(NotificationManagerService.TAG, "Not enqueuing toast. pkg=" + pkg + " callback=" + callback);
                } else if (!NotificationManagerService.this.mToastEnabled) {
                    Slog.e(NotificationManagerService.TAG, "Not enqueuing toast because of toast disabled.");
                } else {
                    int callingUid = Binder.getCallingUid();
                    boolean isPackageSuspended = isPackagePaused(pkg);
                    boolean notificationsDisabledForPackage = !areNotificationsEnabledForPackage(pkg, callingUid);
                    long callingIdentity = Binder.clearCallingIdentity();
                    try {
                        int i = 0;
                        boolean appIsForeground = NotificationManagerService.this.mActivityManager.getUidImportance(callingUid) == 100;
                        if ((notificationsDisabledForPackage && !appIsForeground) || isPackageSuspended) {
                            try {
                                StringBuilder sb = new StringBuilder();
                                sb.append("Suppressing toast from package ");
                                sb.append(pkg);
                                sb.append(isPackageSuspended ? " due to package suspended." : " by user request.");
                                Slog.e(NotificationManagerService.TAG, sb.toString());
                                Binder.restoreCallingIdentity(callingIdentity);
                                return;
                            } catch (Throwable th) {
                                th = th;
                                Binder.restoreCallingIdentity(callingIdentity);
                                throw th;
                            }
                        }
                        Binder.restoreCallingIdentity(callingIdentity);
                        ArrayList<ToastRecord> arrayList3 = NotificationManagerService.this.mToastQueue;
                        synchronized (arrayList3) {
                            try {
                                try {
                                    NotificationManagerService.this.mToastQueueList = new ArrayList();
                                    for (int i2 = 0; i2 < NotificationManagerService.screenNum - 1; i2++) {
                                        try {
                                            ArrayList<ToastRecord> initToastQueue = new ArrayList<>();
                                            NotificationManagerService.this.mToastQueueList.add(initToastQueue);
                                        } catch (Throwable th2) {
                                            th = th2;
                                            arrayList = arrayList3;
                                            throw th;
                                        }
                                    }
                                    for (int i3 = 0; i3 < NotificationManagerService.this.mToastQueue.size(); i3++) {
                                        int i4 = NotificationManagerService.this.mToastQueue.get(i3).sharedId;
                                        if (i4 == -1 || i4 == 0) {
                                            ((ArrayList) NotificationManagerService.this.mToastQueueList.get(0)).add(NotificationManagerService.this.mToastQueue.get(i3));
                                        } else if (i4 != 1) {
                                            Slog.d(NotificationManagerService.TAG, "enqueueToast undefine sharedId=" + NotificationManagerService.this.mToastQueue.get(i3).sharedId);
                                        } else {
                                            ((ArrayList) NotificationManagerService.this.mToastQueueList.get(1)).add(NotificationManagerService.this.mToastQueue.get(i3));
                                        }
                                    }
                                    int callingPid = Binder.getCallingPid();
                                    long callingId = Binder.clearCallingIdentity();
                                    try {
                                        int[] searchIndex = new int[NotificationManagerService.screenNum - 1];
                                        if (sharedId2 > 0) {
                                            i = 1;
                                        }
                                        int j2 = i;
                                        searchIndex[j2] = NotificationManagerService.this.indexOfToastPackageLocked(pkg, (ArrayList) NotificationManagerService.this.mToastQueueList.get(j2));
                                        try {
                                            if (searchIndex[j2] >= 0) {
                                                ToastRecord record = (ToastRecord) ((ArrayList) NotificationManagerService.this.mToastQueueList.get(j2)).get(searchIndex[j2]);
                                                record.update(duration);
                                                try {
                                                    record.callback.hide();
                                                } catch (RemoteException e2) {
                                                }
                                                record.update(callback);
                                                j = j2;
                                                arrayList2 = arrayList3;
                                            } else {
                                                int count = 0;
                                                int i5 = 0;
                                                for (int N = ((ArrayList) NotificationManagerService.this.mToastQueueList.get(j2)).size(); i5 < N; N = N) {
                                                    ToastRecord r = (ToastRecord) ((ArrayList) NotificationManagerService.this.mToastQueueList.get(j2)).get(i5);
                                                    if (!r.pkg.equals(pkg) || (count = count + 1) < 50) {
                                                        i5++;
                                                    } else {
                                                        Slog.e(NotificationManagerService.TAG, "Package has already posted " + count + " toasts. Not showing more. Package=" + pkg);
                                                        Binder.restoreCallingIdentity(callingId);
                                                    }
                                                }
                                                Binder token = new Binder();
                                                NotificationManagerService.this.mWindowManagerInternal.addWindowToken(token, 2005, displayId);
                                                arrayList2 = arrayList3;
                                                j = j2;
                                                try {
                                                    ToastRecord record2 = new ToastRecord(callingPid, pkg, callback, duration, token, displayId, sharedId2);
                                                    ((ArrayList) NotificationManagerService.this.mToastQueueList.get(j)).add(record2);
                                                    NotificationManagerService.this.mToastQueue.add(record2);
                                                    searchIndex[j] = ((ArrayList) NotificationManagerService.this.mToastQueueList.get(j)).size() - 1;
                                                    NotificationManagerService.this.keepProcessAliveIfNeededLocked(callingPid, (ArrayList) NotificationManagerService.this.mToastQueueList.get(j));
                                                } catch (Throwable th3) {
                                                    th = th3;
                                                    Binder.restoreCallingIdentity(callingId);
                                                    throw th;
                                                }
                                            }
                                            if (searchIndex[j] == 0) {
                                                NotificationManagerService.this.showNextToastLocked((ArrayList) NotificationManagerService.this.mToastQueueList.get(j));
                                            }
                                            Binder.restoreCallingIdentity(callingId);
                                        } catch (Throwable th4) {
                                            th = th4;
                                        }
                                    } catch (Throwable th5) {
                                        th = th5;
                                    }
                                } catch (Throwable th6) {
                                    th = th6;
                                    arrayList = arrayList3;
                                }
                            } catch (Throwable th7) {
                                th = th7;
                            }
                        }
                    } catch (Throwable th8) {
                        th = th8;
                    }
                }
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
                        NotificationManagerService.this.finishTokenLocked(record.token, record.displayId);
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

            public void silenceNotificationSound() {
                NotificationManagerService.this.checkCallerIsSystem();
                NotificationManagerService.this.mNotificationDelegate.clearEffects();
            }

            public void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled) {
                enforceSystemOrSystemUI("setNotificationsEnabledForPackage");
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    boolean wasEnabled = NotificationManagerService.this.mPreferencesHelper.getImportance(pkg, uid) != 0;
                    if (wasEnabled == enabled) {
                        return;
                    }
                    NotificationManagerService.this.mPreferencesHelper.setEnabled(pkg, uid, enabled);
                    NotificationManagerService.this.mMetricsLogger.write(new LogMaker(147).setType(4).setPackageName(pkg).setSubtype(enabled ? 1 : 0));
                    if (!enabled) {
                        NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, pkg, null, 0, 0, true, UserHandle.getUserId(uid), 7, null);
                    }
                    try {
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.APP_BLOCK_STATE_CHANGED").putExtra("android.app.extra.BLOCKED_STATE", !enabled).addFlags(268435456).setPackage(pkg), UserHandle.of(UserHandle.getUserId(uid)), null);
                    } catch (SecurityException e) {
                        Slog.w(NotificationManagerService.TAG, "Can't notify app about app block change", e);
                    }
                    NotificationManagerService.this.handleSavePolicyFile();
                }
            }

            public void setNotificationsEnabledWithImportanceLockForPackage(String pkg, int uid, boolean enabled) {
                setNotificationsEnabledForPackage(pkg, uid, enabled);
                NotificationManagerService.this.mPreferencesHelper.setAppImportanceLocked(pkg, uid);
            }

            public boolean areNotificationsEnabled(String pkg) {
                return areNotificationsEnabledForPackage(pkg, Binder.getCallingUid());
            }

            public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
                enforceSystemOrSystemUIOrSamePackage(pkg, "Caller not system or systemui or same package");
                if (UserHandle.getCallingUserId() != UserHandle.getUserId(uid)) {
                    Context context2 = NotificationManagerService.this.getContext();
                    context2.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS", "canNotifyAsPackage for uid " + uid);
                }
                return NotificationManagerService.this.mPreferencesHelper.getImportance(pkg, uid) != 0;
            }

            public boolean areBubblesAllowed(String pkg) {
                return areBubblesAllowedForPackage(pkg, Binder.getCallingUid());
            }

            public boolean areBubblesAllowedForPackage(String pkg, int uid) {
                enforceSystemOrSystemUIOrSamePackage(pkg, "Caller not system or systemui or same package");
                if (UserHandle.getCallingUserId() != UserHandle.getUserId(uid)) {
                    Context context2 = NotificationManagerService.this.getContext();
                    context2.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS", "canNotifyAsPackage for uid " + uid);
                }
                return NotificationManagerService.this.mPreferencesHelper.areBubblesAllowed(pkg, uid);
            }

            public void setBubblesAllowed(String pkg, int uid, boolean allowed) {
                enforceSystemOrSystemUI("Caller not system or systemui");
                NotificationManagerService.this.mPreferencesHelper.setBubblesAllowed(pkg, uid, allowed);
                NotificationManagerService.this.handleSavePolicyFile();
            }

            public boolean hasUserApprovedBubblesForPackage(String pkg, int uid) {
                enforceSystemOrSystemUI("Caller not system or systemui");
                int lockedFields = NotificationManagerService.this.mPreferencesHelper.getAppLockedFields(pkg, uid);
                return (lockedFields & 2) != 0;
            }

            public boolean shouldHideSilentStatusIcons(String callingPkg) {
                NotificationManagerService.this.checkCallerIsSameApp(callingPkg);
                if (NotificationManagerService.this.isCallerSystemOrPhone() || NotificationManagerService.this.mListeners.isListenerPackage(callingPkg)) {
                    return NotificationManagerService.this.mPreferencesHelper.shouldHideSilentStatusIcons();
                }
                throw new SecurityException("Only available for notification listeners");
            }

            public void setHideSilentStatusIcons(boolean hide) {
                NotificationManagerService.this.checkCallerIsSystem();
                NotificationManagerService.this.mPreferencesHelper.setHideSilentStatusIcons(hide);
                NotificationManagerService.this.handleSavePolicyFile();
                NotificationManagerService.this.mListeners.onStatusBarIconsBehaviorChanged(hide);
            }

            public int getPackageImportance(String pkg) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mPreferencesHelper.getImportance(pkg, Binder.getCallingUid());
            }

            public boolean canShowBadge(String pkg, int uid) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mPreferencesHelper.canShowBadge(pkg, uid);
            }

            public void setShowBadge(String pkg, int uid, boolean showBadge) {
                NotificationManagerService.this.checkCallerIsSystem();
                NotificationManagerService.this.mPreferencesHelper.setShowBadge(pkg, uid, showBadge);
                NotificationManagerService.this.handleSavePolicyFile();
            }

            public void setNotificationDelegate(String callingPkg, String delegate) {
                NotificationManagerService.this.checkCallerIsSameApp(callingPkg);
                int callingUid = Binder.getCallingUid();
                UserHandle user = UserHandle.getUserHandleForUid(callingUid);
                if (delegate == null) {
                    NotificationManagerService.this.mPreferencesHelper.revokeNotificationDelegate(callingPkg, Binder.getCallingUid());
                    NotificationManagerService.this.handleSavePolicyFile();
                    return;
                }
                try {
                    ApplicationInfo info = NotificationManagerService.this.mPackageManager.getApplicationInfo(delegate, 786432, user.getIdentifier());
                    if (info != null) {
                        NotificationManagerService.this.mPreferencesHelper.setNotificationDelegate(callingPkg, callingUid, delegate, info.uid);
                        NotificationManagerService.this.handleSavePolicyFile();
                    }
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }

            public String getNotificationDelegate(String callingPkg) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(callingPkg);
                return NotificationManagerService.this.mPreferencesHelper.getNotificationDelegate(callingPkg, Binder.getCallingUid());
            }

            public boolean canNotifyAsPackage(String callingPkg, String targetPkg, int userId) {
                NotificationManagerService.this.checkCallerIsSameApp(callingPkg);
                int callingUid = Binder.getCallingUid();
                UserHandle user = UserHandle.getUserHandleForUid(callingUid);
                if (user.getIdentifier() != userId) {
                    Context context2 = NotificationManagerService.this.getContext();
                    context2.enforceCallingPermission("android.permission.INTERACT_ACROSS_USERS", "canNotifyAsPackage for user " + userId);
                }
                if (!callingPkg.equals(targetPkg)) {
                    try {
                        ApplicationInfo info = NotificationManagerService.this.mPackageManager.getApplicationInfo(targetPkg, 786432, userId);
                        if (info != null) {
                            return NotificationManagerService.this.mPreferencesHelper.isDelegateAllowed(targetPkg, info.uid, callingPkg, callingUid);
                        }
                        return false;
                    } catch (RemoteException e) {
                        return false;
                    }
                }
                return true;
            }

            public void updateNotificationChannelGroupForPackage(String pkg, int uid, NotificationChannelGroup group) throws RemoteException {
                enforceSystemOrSystemUI("Caller not system or systemui");
                NotificationManagerService.this.createNotificationChannelGroup(pkg, uid, group, false, false);
                NotificationManagerService.this.handleSavePolicyFile();
            }

            public void createNotificationChannelGroups(String pkg, ParceledListSlice channelGroupList) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                List<NotificationChannelGroup> groups = channelGroupList.getList();
                int groupSize = groups.size();
                for (int i = 0; i < groupSize; i++) {
                    NotificationChannelGroup group = groups.get(i);
                    NotificationManagerService.this.createNotificationChannelGroup(pkg, Binder.getCallingUid(), group, true, false);
                }
                NotificationManagerService.this.handleSavePolicyFile();
            }

            private void createNotificationChannelsImpl(String pkg, int uid, ParceledListSlice channelsList) {
                List<NotificationChannel> channels = channelsList.getList();
                int channelsSize = channels.size();
                boolean needsPolicyFileChange = false;
                for (int i = 0; i < channelsSize; i++) {
                    NotificationChannel channel = channels.get(i);
                    Preconditions.checkNotNull(channel, "channel in list is null");
                    needsPolicyFileChange = NotificationManagerService.this.mPreferencesHelper.createNotificationChannel(pkg, uid, channel, true, NotificationManagerService.this.mConditionProviders.isPackageOrComponentAllowed(pkg, UserHandle.getUserId(uid)));
                    if (needsPolicyFileChange) {
                        NotificationManagerService.this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(uid), NotificationManagerService.this.mPreferencesHelper.getNotificationChannel(pkg, uid, channel.getId(), false), 1);
                    }
                }
                if (needsPolicyFileChange) {
                    NotificationManagerService.this.handleSavePolicyFile();
                }
            }

            public void createNotificationChannels(String pkg, ParceledListSlice channelsList) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                createNotificationChannelsImpl(pkg, Binder.getCallingUid(), channelsList);
            }

            public void createNotificationChannelsForPackage(String pkg, int uid, ParceledListSlice channelsList) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystem();
                createNotificationChannelsImpl(pkg, uid, channelsList);
            }

            public NotificationChannel getNotificationChannel(String callingPkg, int userId, String targetPkg, String channelId) {
                if (canNotifyAsPackage(callingPkg, targetPkg, userId) || NotificationManagerService.this.isCallingUidSystem()) {
                    int targetUid = -1;
                    try {
                        targetUid = NotificationManagerService.this.mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                    return NotificationManagerService.this.mPreferencesHelper.getNotificationChannel(targetPkg, targetUid, channelId, false);
                }
                throw new SecurityException("Pkg " + callingPkg + " cannot read channels for " + targetPkg + " in " + userId);
            }

            public NotificationChannel getNotificationChannelForPackage(String pkg, int uid, String channelId, boolean includeDeleted) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannel(pkg, uid, channelId, includeDeleted);
            }

            private void enforceDeletingChannelHasNoFgService(String pkg, int userId, String channelId) {
                if (NotificationManagerService.this.mAmi.hasForegroundServiceNotification(pkg, userId, channelId)) {
                    Slog.w(NotificationManagerService.TAG, "Package u" + userId + SliceClientPermissions.SliceAuthority.DELIMITER + pkg + " may not delete notification channel '" + channelId + "' with fg service");
                    throw new SecurityException("Not allowed to delete channel " + channelId + " with a foreground service");
                }
            }

            public void deleteNotificationChannel(String pkg, String channelId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                int callingUid = Binder.getCallingUid();
                int callingUser = UserHandle.getUserId(callingUid);
                if ("miscellaneous".equals(channelId)) {
                    throw new IllegalArgumentException("Cannot delete default channel");
                }
                enforceDeletingChannelHasNoFgService(pkg, callingUser, channelId);
                NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, pkg, channelId, 0, 0, true, callingUser, 17, null);
                NotificationManagerService.this.mPreferencesHelper.deleteNotificationChannel(pkg, callingUid, channelId);
                NotificationManagerService.this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(callingUid), NotificationManagerService.this.mPreferencesHelper.getNotificationChannel(pkg, callingUid, channelId, true), 3);
                NotificationManagerService.this.handleSavePolicyFile();
            }

            public NotificationChannelGroup getNotificationChannelGroup(String pkg, String groupId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannelGroupWithChannels(pkg, Binder.getCallingUid(), groupId, false);
            }

            public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(String pkg) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannelGroups(pkg, Binder.getCallingUid(), false, false, true);
            }

            public void deleteNotificationChannelGroup(String pkg, String groupId) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                int callingUid = Binder.getCallingUid();
                NotificationChannelGroup groupToDelete = NotificationManagerService.this.mPreferencesHelper.getNotificationChannelGroup(groupId, pkg, callingUid);
                if (groupToDelete != null) {
                    int userId = UserHandle.getUserId(callingUid);
                    List<NotificationChannel> groupChannels = groupToDelete.getChannels();
                    for (int i = 0; i < groupChannels.size(); i++) {
                        enforceDeletingChannelHasNoFgService(pkg, userId, groupChannels.get(i).getId());
                    }
                    List<NotificationChannel> deletedChannels = NotificationManagerService.this.mPreferencesHelper.deleteNotificationChannelGroup(pkg, callingUid, groupId);
                    int i2 = 0;
                    while (i2 < deletedChannels.size()) {
                        NotificationChannel deletedChannel = deletedChannels.get(i2);
                        NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, pkg, deletedChannel.getId(), 0, 0, true, userId, 17, null);
                        NotificationManagerService.this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(callingUid), deletedChannel, 3);
                        i2++;
                        deletedChannels = deletedChannels;
                        groupChannels = groupChannels;
                        userId = userId;
                    }
                    NotificationManagerService.this.mListeners.notifyNotificationChannelGroupChanged(pkg, UserHandle.getUserHandleForUid(callingUid), groupToDelete, 3);
                    NotificationManagerService.this.handleSavePolicyFile();
                }
            }

            public void updateNotificationChannelForPackage(String pkg, int uid, NotificationChannel channel) {
                enforceSystemOrSystemUI("Caller not system or systemui");
                Preconditions.checkNotNull(channel);
                NotificationManagerService.this.updateNotificationChannelInt(pkg, uid, channel, false);
            }

            public ParceledListSlice<NotificationChannel> getNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted) {
                enforceSystemOrSystemUI("getNotificationChannelsForPackage");
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannels(pkg, uid, includeDeleted);
            }

            public int getNumNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted) {
                enforceSystemOrSystemUI("getNumNotificationChannelsForPackage");
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannels(pkg, uid, includeDeleted).getList().size();
            }

            public boolean onlyHasDefaultChannel(String pkg, int uid) {
                enforceSystemOrSystemUI("onlyHasDefaultChannel");
                return NotificationManagerService.this.mPreferencesHelper.onlyHasDefaultChannel(pkg, uid);
            }

            public int getDeletedChannelCount(String pkg, int uid) {
                enforceSystemOrSystemUI("getDeletedChannelCount");
                return NotificationManagerService.this.mPreferencesHelper.getDeletedChannelCount(pkg, uid);
            }

            public int getBlockedChannelCount(String pkg, int uid) {
                enforceSystemOrSystemUI("getBlockedChannelCount");
                return NotificationManagerService.this.mPreferencesHelper.getBlockedChannelCount(pkg, uid);
            }

            public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroupsForPackage(String pkg, int uid, boolean includeDeleted) {
                enforceSystemOrSystemUI("getNotificationChannelGroupsForPackage");
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannelGroups(pkg, uid, includeDeleted, true, false);
            }

            public NotificationChannelGroup getPopulatedNotificationChannelGroupForPackage(String pkg, int uid, String groupId, boolean includeDeleted) {
                enforceSystemOrSystemUI("getPopulatedNotificationChannelGroupForPackage");
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannelGroupWithChannels(pkg, uid, groupId, includeDeleted);
            }

            public NotificationChannelGroup getNotificationChannelGroupForPackage(String groupId, String pkg, int uid) {
                enforceSystemOrSystemUI("getNotificationChannelGroupForPackage");
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannelGroup(groupId, pkg, uid);
            }

            public ParceledListSlice<NotificationChannel> getNotificationChannels(String callingPkg, String targetPkg, int userId) {
                if (canNotifyAsPackage(callingPkg, targetPkg, userId) || NotificationManagerService.this.isCallingUidSystem()) {
                    int targetUid = -1;
                    try {
                        targetUid = NotificationManagerService.this.mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                    return NotificationManagerService.this.mPreferencesHelper.getNotificationChannels(targetPkg, targetUid, false);
                }
                throw new SecurityException("Pkg " + callingPkg + " cannot read channels for " + targetPkg + " in " + userId);
            }

            public int getBlockedAppCount(int userId) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mPreferencesHelper.getBlockedAppCount(userId);
            }

            public int getAppsBypassingDndCount(int userId) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mPreferencesHelper.getAppsBypassingDndCount(userId);
            }

            public ParceledListSlice<NotificationChannel> getNotificationChannelsBypassingDnd(String pkg, int userId) {
                NotificationManagerService.this.checkCallerIsSystem();
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannelsBypassingDnd(pkg, userId);
            }

            public boolean areChannelsBypassingDnd() {
                return NotificationManagerService.this.mPreferencesHelper.areChannelsBypassingDnd();
            }

            public void clearData(String packageName, int uid, boolean fromApp) throws RemoteException {
                NotificationManagerService.this.checkCallerIsSystem();
                NotificationManagerService.this.cancelAllNotificationsInt(NotificationManagerService.MY_UID, NotificationManagerService.MY_PID, packageName, null, 0, 0, true, UserHandle.getUserId(Binder.getCallingUid()), 17, null);
                String[] packages = {packageName};
                int[] uids = {uid};
                NotificationManagerService.this.mListeners.onPackagesChanged(true, packages, uids);
                NotificationManagerService.this.mAssistants.onPackagesChanged(true, packages, uids);
                NotificationManagerService.this.mConditionProviders.onPackagesChanged(true, packages, uids);
                NotificationManagerService.this.mSnoozeHelper.clearData(UserHandle.getUserId(uid), packageName);
                if (!fromApp) {
                    NotificationManagerService.this.mPreferencesHelper.clearData(packageName, uid);
                }
                NotificationManagerService.this.handleSavePolicyFile();
            }

            public List<String> getAllowedAssistantAdjustments(String pkg) {
                NotificationManagerService.this.checkCallerIsSystemOrSameApp(pkg);
                if (NotificationManagerService.this.isCallerSystemOrPhone() || NotificationManagerService.this.mAssistants.isPackageAllowed(pkg, UserHandle.getCallingUserId())) {
                    return NotificationManagerService.this.mAssistants.getAllowedAssistantAdjustments();
                }
                throw new SecurityException("Not currently an assistant");
            }

            public void allowAssistantAdjustment(String adjustmentType) {
                NotificationManagerService.this.checkCallerIsSystemOrSystemUiOrShell();
                NotificationManagerService.this.mAssistants.allowAdjustmentType(adjustmentType);
                NotificationManagerService.this.handleSavePolicyFile();
            }

            public void disallowAssistantAdjustment(String adjustmentType) {
                NotificationManagerService.this.checkCallerIsSystemOrSystemUiOrShell();
                NotificationManagerService.this.mAssistants.disallowAdjustmentType(adjustmentType);
                NotificationManagerService.this.handleSavePolicyFile();
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
                if (sbn.getUserId() == userId) {
                    if (sbn.getPackageName().equals(pkg) || sbn.getOpPkg().equals(pkg)) {
                        return new StatusBarNotification(sbn.getPackageName(), sbn.getOpPkg(), sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(), sbn.getNotification().clone(), sbn.getUser(), sbn.getOverrideGroupKey(), sbn.getPostTime());
                    }
                    return null;
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
                            while (i2 < N2) {
                                NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(keys[i2]);
                                if (r == null) {
                                    i = i2;
                                    N = N2;
                                } else {
                                    int userId = r.sbn.getUserId();
                                    if (userId != info.userid && userId != -1 && !NotificationManagerService.this.mUserProfiles.isCurrentProfile(userId)) {
                                        throw new SecurityException("Disallowed call from listener: " + info.service);
                                    }
                                    i = i2;
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
                        if (keys == null) {
                            return;
                        }
                        ArrayList<NotificationRecord> seen = new ArrayList<>();
                        int n = keys.length;
                        for (int i = 0; i < n; i++) {
                            NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(keys[i]);
                            if (r != null) {
                                int userId = r.sbn.getUserId();
                                if (userId != info.userid && userId != -1 && !NotificationManagerService.this.mUserProfiles.isCurrentProfile(userId)) {
                                    throw new SecurityException("Disallowed call from listener: " + info.service);
                                }
                                seen.add(r);
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
                        if (!seen.isEmpty()) {
                            NotificationManagerService.this.mAssistants.onNotificationsSeenLocked(seen);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            @GuardedBy({"mNotificationLock"})
            private void cancelNotificationFromListenerLocked(ManagedServices.ManagedServiceInfo info, int callingUid, int callingPid, String pkg, String tag, int id, int userId) {
                NotificationManagerService.this.cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 4162, true, userId, 10, info);
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
                                    Slog.e(NotificationManagerService.TAG, "Ignoring deprecated cancelNotification(pkg, tag, id) from " + info.component + " use cancelNotification(key) instead.");
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

            public void clearRequestedListenerHints(INotificationListener token) {
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        ManagedServices.ManagedServiceInfo info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                        NotificationManagerService.this.removeDisabledHints(info);
                        NotificationManagerService.this.updateListenerHintsLocked();
                        NotificationManagerService.this.updateEffectsSuppressorLocked();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
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

            public String addAutomaticZenRule(AutomaticZenRule automaticZenRule) {
                Preconditions.checkNotNull(automaticZenRule, "automaticZenRule is null");
                Preconditions.checkNotNull(automaticZenRule.getName(), "Name is null");
                if (automaticZenRule.getOwner() == null && automaticZenRule.getConfigurationActivity() == null) {
                    throw new NullPointerException("Rule must have a conditionproviderservice and/or configuration activity");
                }
                Preconditions.checkNotNull(automaticZenRule.getConditionId(), "ConditionId is null");
                if (automaticZenRule.getZenPolicy() != null && automaticZenRule.getInterruptionFilter() != 2) {
                    throw new IllegalArgumentException("ZenPolicy is only applicable to INTERRUPTION_FILTER_PRIORITY filters");
                }
                enforcePolicyAccess(Binder.getCallingUid(), "addAutomaticZenRule");
                return NotificationManagerService.this.mZenModeHelper.addAutomaticZenRule(automaticZenRule, "addAutomaticZenRule");
            }

            public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule) throws RemoteException {
                Preconditions.checkNotNull(automaticZenRule, "automaticZenRule is null");
                Preconditions.checkNotNull(automaticZenRule.getName(), "Name is null");
                if (automaticZenRule.getOwner() == null && automaticZenRule.getConfigurationActivity() == null) {
                    throw new NullPointerException("Rule must have a conditionproviderservice and/or configuration activity");
                }
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

            public void setAutomaticZenRuleState(String id, Condition condition) {
                Preconditions.checkNotNull(id, "id is null");
                Preconditions.checkNotNull(condition, "Condition is null");
                enforcePolicyAccess(Binder.getCallingUid(), "setAutomaticZenRuleState");
                NotificationManagerService.this.mZenModeHelper.setAutomaticZenRuleState(id, condition);
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
                String[] packages = NotificationManagerService.this.mPackageManagerClient.getPackagesForUid(uid);
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
                    long token = Binder.clearCallingIdentity();
                    try {
                        if (filter.stats) {
                            NotificationManagerService.this.dumpJson(pw, filter);
                        } else if (filter.rvStats) {
                            NotificationManagerService.this.dumpRemoteViewStats(pw, filter);
                        } else if (filter.proto) {
                            NotificationManagerService.this.dumpProto(fd, filter);
                        } else if (filter.criticalPriority) {
                            NotificationManagerService.this.dumpNotificationRecords(pw, filter);
                        } else {
                            NotificationManagerService.this.dumpImpl(pw, filter);
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token);
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
                NotificationManagerService.this.checkCallerIsSystem();
                if (NotificationManagerService.DBG) {
                    Slog.d(NotificationManagerService.TAG, "getBackupPayload u=" + user);
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    NotificationManagerService.this.writePolicyXml(baos, true, user);
                    return baos.toByteArray();
                } catch (IOException e) {
                    Slog.w(NotificationManagerService.TAG, "getBackupPayload: error writing payload for user " + user, e);
                    return null;
                }
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
                    return;
                }
                ByteArrayInputStream bais = new ByteArrayInputStream(payload);
                try {
                    NotificationManagerService.this.readPolicyXml(bais, true, user);
                    NotificationManagerService.this.handleSavePolicyFile();
                } catch (IOException | NumberFormatException | XmlPullParserException e) {
                    Slog.w(NotificationManagerService.TAG, "applyRestore: error reading payload", e);
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
                    if (NotificationManagerService.this.mAllowedManagedServicePackages.test(pkg, Integer.valueOf(userId), NotificationManagerService.this.mConditionProviders.getRequiredPermission())) {
                        NotificationManagerService.this.mConditionProviders.setPackageOrComponentEnabled(pkg, userId, true, granted);
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(pkg).addFlags(67108864), UserHandle.of(userId), null);
                        NotificationManagerService.this.handleSavePolicyFile();
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

            public NotificationManager.Policy getConsolidatedNotificationPolicy() {
                long identity = Binder.clearCallingIdentity();
                try {
                    return NotificationManagerService.this.mZenModeHelper.getConsolidatedNotificationPolicy();
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

            public ComponentName getAllowedNotificationAssistantForUser(int userId) {
                NotificationManagerService.this.checkCallerIsSystemOrSystemUiOrShell();
                List<ComponentName> allowedComponents = NotificationManagerService.this.mAssistants.getAllowedComponents(userId);
                if (allowedComponents.size() > 1) {
                    throw new IllegalStateException("At most one NotificationAssistant: " + allowedComponents.size());
                }
                return (ComponentName) CollectionUtils.firstOrNull(allowedComponents);
            }

            public ComponentName getAllowedNotificationAssistant() {
                return getAllowedNotificationAssistantForUser(getCallingUserHandle().getIdentifier());
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

            public void setNotificationAssistantAccessGranted(ComponentName assistant, boolean granted) {
                setNotificationAssistantAccessGrantedForUser(assistant, getCallingUserHandle().getIdentifier(), granted);
            }

            public void setNotificationListenerAccessGrantedForUser(ComponentName listener, int userId, boolean granted) {
                Preconditions.checkNotNull(listener);
                NotificationManagerService.this.checkCallerIsSystemOrShell();
                long identity = Binder.clearCallingIdentity();
                try {
                    if (NotificationManagerService.this.mAllowedManagedServicePackages.test(listener.getPackageName(), Integer.valueOf(userId), NotificationManagerService.this.mListeners.getRequiredPermission())) {
                        NotificationManagerService.this.mConditionProviders.setPackageOrComponentEnabled(listener.flattenToString(), userId, false, granted);
                        NotificationManagerService.this.mListeners.setPackageOrComponentEnabled(listener.flattenToString(), userId, true, granted);
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(listener.getPackageName()).addFlags(1073741824), UserHandle.of(userId), null);
                        NotificationManagerService.this.handleSavePolicyFile();
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
                    if (NotificationManagerService.this.mAllowedManagedServicePackages.test(listener.getPackageName(), Integer.valueOf(userId), NotificationManagerService.this.mListeners.getRequiredPermission())) {
                        NotificationManagerService.this.mConditionProviders.setPackageOrComponentEnabled(listener.flattenToString(), userId, false, granted);
                        NotificationManagerService.this.mListeners.setPackageOrComponentEnabled(listener.flattenToString(), userId, true, granted);
                        NotificationManagerService.this.getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(listener.getPackageName()).addFlags(1073741824), UserHandle.of(userId), null);
                        NotificationManagerService.this.handleSavePolicyFile();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void setNotificationAssistantAccessGrantedForUser(ComponentName assistant, int userId, boolean granted) {
                NotificationManagerService.this.checkCallerIsSystemOrSystemUiOrShell();
                for (UserInfo ui : NotificationManagerService.this.mUm.getEnabledProfiles(userId)) {
                    NotificationManagerService.this.mAssistants.setUserSet(ui.id, true);
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    NotificationManagerService.this.setNotificationAssistantAccessGrantedForUserInternal(assistant, userId, granted);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void applyEnqueuedAdjustmentFromAssistant(INotificationListener token, Adjustment adjustment) {
                boolean foundEnqueued = false;
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
                            NotificationRecord r = NotificationManagerService.this.mEnqueuedNotifications.get(i);
                            if (Objects.equals(adjustment.getKey(), r.getKey()) && Objects.equals(Integer.valueOf(adjustment.getUser()), Integer.valueOf(r.getUserId())) && NotificationManagerService.this.mAssistants.isSameUser(token, r.getUserId())) {
                                NotificationManagerService.this.applyAdjustment(r, adjustment);
                                r.applyAdjustments();
                                r.calculateImportance();
                                foundEnqueued = true;
                                break;
                            }
                            i++;
                        }
                        if (!foundEnqueued) {
                            applyAdjustmentFromAssistant(token, adjustment);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void applyAdjustmentFromAssistant(INotificationListener token, Adjustment adjustment) {
                List<Adjustment> adjustments = new ArrayList<>();
                adjustments.add(adjustment);
                applyAdjustmentsFromAssistant(token, adjustments);
            }

            public void applyAdjustmentsFromAssistant(INotificationListener token, List<Adjustment> adjustments) {
                boolean needsSort = false;
                long identity = Binder.clearCallingIdentity();
                try {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        NotificationManagerService.this.mAssistants.checkServiceTokenLocked(token);
                        for (Adjustment adjustment : adjustments) {
                            NotificationRecord r = NotificationManagerService.this.mNotificationsByKey.get(adjustment.getKey());
                            if (r != null && NotificationManagerService.this.mAssistants.isSameUser(token, r.getUserId())) {
                                NotificationManagerService.this.applyAdjustment(r, adjustment);
                                if (adjustment.getSignals().containsKey("key_importance") && adjustment.getSignals().getInt("key_importance") == 0) {
                                    cancelNotificationsFromListener(token, new String[]{r.getKey()});
                                } else {
                                    needsSort = true;
                                }
                            }
                        }
                    }
                    if (needsSort) {
                        NotificationManagerService.this.mRankingHandler.requestSort();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            public void updateNotificationChannelGroupFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user, NotificationChannelGroup group) throws RemoteException {
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user, false);
                NotificationManagerService.this.createNotificationChannelGroup(pkg, getUidForPackageAndUser(pkg, user), group, false, true);
                NotificationManagerService.this.handleSavePolicyFile();
            }

            public void updateNotificationChannelFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user, NotificationChannel channel) throws RemoteException {
                Preconditions.checkNotNull(channel);
                Preconditions.checkNotNull(pkg);
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user, false);
                NotificationManagerService.this.updateNotificationChannelInt(pkg, getUidForPackageAndUser(pkg, user), channel, true);
            }

            public ParceledListSlice<NotificationChannel> getNotificationChannelsFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user) throws RemoteException {
                Preconditions.checkNotNull(pkg);
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user, true);
                return NotificationManagerService.this.mPreferencesHelper.getNotificationChannels(pkg, getUidForPackageAndUser(pkg, user), false);
            }

            public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroupsFromPrivilegedListener(INotificationListener token, String pkg, UserHandle user) throws RemoteException {
                Preconditions.checkNotNull(pkg);
                Preconditions.checkNotNull(user);
                verifyPrivilegedListener(token, user, true);
                List<NotificationChannelGroup> groups = new ArrayList<>();
                groups.addAll(NotificationManagerService.this.mPreferencesHelper.getNotificationChannelGroups(pkg, getUidForPackageAndUser(pkg, user)));
                return new ParceledListSlice<>(groups);
            }

            public void setPrivateNotificationsAllowed(boolean allow) {
                if (NotificationManagerService.this.getContext().checkCallingPermission("android.permission.CONTROL_KEYGUARD_SECURE_NOTIFICATIONS") == 0) {
                    if (allow != NotificationManagerService.this.mLockScreenAllowSecureNotifications) {
                        NotificationManagerService.this.mLockScreenAllowSecureNotifications = allow;
                        NotificationManagerService.this.handleSavePolicyFile();
                        return;
                    }
                    return;
                }
                throw new SecurityException("Requires CONTROL_KEYGUARD_SECURE_NOTIFICATIONS permission");
            }

            public boolean getPrivateNotificationsAllowed() {
                if (NotificationManagerService.this.getContext().checkCallingPermission("android.permission.CONTROL_KEYGUARD_SECURE_NOTIFICATIONS") == 0) {
                    return NotificationManagerService.this.mLockScreenAllowSecureNotifications;
                }
                throw new SecurityException("Requires CONTROL_KEYGUARD_SECURE_NOTIFICATIONS permission");
            }

            public boolean isPackagePaused(String pkg) {
                Preconditions.checkNotNull(pkg);
                NotificationManagerService.this.checkCallerIsSameApp(pkg);
                return NotificationManagerService.this.isPackagePausedOrSuspended(pkg, Binder.getCallingUid());
            }

            private void verifyPrivilegedListener(INotificationListener token, UserHandle user, boolean assistantAllowed) {
                ManagedServices.ManagedServiceInfo info;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    info = NotificationManagerService.this.mListeners.checkServiceTokenLocked(token);
                }
                if (!NotificationManagerService.this.hasCompanionDevice(info)) {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        if (assistantAllowed) {
                            if (NotificationManagerService.this.mAssistants.isServiceTokenValidLocked(info.service)) {
                            }
                        }
                        throw new SecurityException(info + " does not have access");
                    }
                }
                if (!info.enabledAndUserMatches(user.getIdentifier())) {
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
                new NotificationShellCmd(NotificationManagerService.this).exec(this, in, out, err, args, callback, resultReceiver);
            }

            public void registerNotificationListener(String pkgName, INotificationStatusCallBack statusCallBack) {
                if (pkgName != null && statusCallBack != null) {
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        NotificationManagerService.this.mINotificationStatusCallBackList.put(pkgName, statusCallBack);
                        Log.d(NotificationManagerService.TAG, "registerNotificationListener");
                    }
                }
            }

            public void unRegisterNotificationListener(String pkgName) {
                if (pkgName == null) {
                    return;
                }
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    if (NotificationManagerService.this.mINotificationStatusCallBackList.containsKey(pkgName)) {
                        NotificationManagerService.this.mINotificationStatusCallBackList.remove(pkgName);
                        Log.d(NotificationManagerService.TAG, "unregisterNotificationListener");
                    }
                }
            }

            public void sendMessageToListener(ComponentName sender, String pkgName, long notificationID, int status) {
                INotificationStatusCallBack callBack;
                if (sender == null || pkgName == null) {
                    return;
                }
                try {
                    String senderPkgName = sender.getPackageName();
                    synchronized (NotificationManagerService.this.mNotificationLock) {
                        if (NotificationManagerService.this.mINotificationStatusCallBackList.containsKey(pkgName) && (callBack = NotificationManagerService.this.mINotificationStatusCallBackList.get(pkgName)) != null) {
                            callBack.onReceiveNotificationStatus(senderPkgName, notificationID, status);
                            Log.d(NotificationManagerService.TAG, "sendMessageToListener ok");
                        }
                    }
                } catch (RemoteException e) {
                    Log.d(NotificationManagerService.TAG, "sendMessageToListener fail");
                }
            }

            public int getUnreadCount(String packageName) {
                int count;
                synchronized (NotificationManagerService.this.mNotificationLock) {
                    count = NotificationManagerService.this.mNotificationPolicy.getUnreadCount(packageName, NotificationManagerService.this.mNotificationList);
                }
                return count;
            }

            public List<StatusBarNotification> getNotificationList(int flag) {
                return NotificationManagerService.this.mNotificationPolicy.getNotificationList(flag, NotificationManagerService.this.mNotificationList);
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
                                    record.setNotificationNumber(number);
                                    NotificationManagerService.this.mNotificationList.add(record);
                                    NotificationManagerService.this.mNotificationsByKey.put(key, record);
                                    if (record.sbn != null) {
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

            public boolean isOsdEnable() {
                Log.i(NotificationManagerService.TAG, "isOsdEnable mOsdEnabled=" + NotificationManagerService.this.mOsdEnabled);
                return NotificationManagerService.this.mOsdEnabled;
            }

            public long pullStats(long startNs, int report, boolean doAgg, List<ParcelFileDescriptor> out) {
                PulledStats stats;
                NotificationManagerService.this.checkCallerIsSystemOrShell();
                long startMs = TimeUnit.MILLISECONDS.convert(startNs, TimeUnit.NANOSECONDS);
                long identity = Binder.clearCallingIdentity();
                try {
                    if (report == 1) {
                        try {
                            Slog.e(NotificationManagerService.TAG, "pullStats REPORT_REMOTE_VIEWS from: " + startMs + "  wtih " + doAgg);
                            stats = NotificationManagerService.this.mUsageStats.remoteViewStats(startMs, doAgg);
                        } catch (IOException e) {
                            e = e;
                        } catch (Throwable th) {
                            e = th;
                            Binder.restoreCallingIdentity(identity);
                            throw e;
                        }
                        try {
                            if (stats != null) {
                                out.add(stats.toParcelFileDescriptor(report));
                                Slog.e(NotificationManagerService.TAG, "exiting pullStats with: " + out.size());
                                long endNs = TimeUnit.NANOSECONDS.convert(stats.endTimeMs(), TimeUnit.MILLISECONDS);
                                Binder.restoreCallingIdentity(identity);
                                return endNs;
                            }
                            Slog.e(NotificationManagerService.TAG, "null stats for: " + report);
                        } catch (IOException e2) {
                            e = e2;
                            Slog.e(NotificationManagerService.TAG, "exiting pullStats: on error", e);
                            Binder.restoreCallingIdentity(identity);
                            return 0L;
                        }
                    }
                    Binder.restoreCallingIdentity(identity);
                    Slog.e(NotificationManagerService.TAG, "exiting pullStats: bad request");
                    return 0L;
                } catch (Throwable th2) {
                    e = th2;
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
    void setPreferencesHelper(PreferencesHelper prefHelper) {
        this.mPreferencesHelper = prefHelper;
    }

    @VisibleForTesting
    void setRankingHandler(RankingHandler rankingHandler) {
        this.mRankingHandler = rankingHandler;
    }

    @VisibleForTesting
    void setZenHelper(ZenModeHelper zenHelper) {
        this.mZenModeHelper = zenHelper;
    }

    @VisibleForTesting
    void setIsAutomotive(boolean isAutomotive) {
        this.mIsAutomotive = isAutomotive;
    }

    @VisibleForTesting
    void setNotificationEffectsEnabledForAutomotive(boolean isEnabled) {
        this.mNotificationEffectsEnabledForAutomotive = isEnabled;
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
    void init(Looper looper, IPackageManager packageManager, PackageManager packageManagerClient, LightsManager lightsManager, NotificationListeners notificationListeners, NotificationAssistants notificationAssistants, ConditionProviders conditionProviders, ICompanionDeviceManager companionManager, SnoozeHelper snoozeHelper, NotificationUsageStats usageStats, AtomicFile policyFile, ActivityManager activityManager, GroupHelper groupHelper, IActivityManager am, UsageStatsManagerInternal appUsageStats, DevicePolicyManagerInternal dpm, IUriGrantsManager ugm, UriGrantsManagerInternal ugmInternal, AppOpsManager appOps, UserManager userManager, ActivityManagerInternal ami) {
        String[] extractorNames;
        Resources resources = getContext().getResources();
        this.mMaxPackageEnqueueRate = Settings.Global.getFloat(getContext().getContentResolver(), "max_notification_enqueue_rate", DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE);
        this.mAccessibilityManager = (AccessibilityManager) getContext().getSystemService("accessibility");
        this.mAm = am;
        this.mUgm = ugm;
        this.mUgmInternal = ugmInternal;
        this.mPackageManager = packageManager;
        this.mPackageManagerClient = packageManagerClient;
        this.mAppOps = appOps;
        this.mVibrator = (Vibrator) getContext().getSystemService("vibrator");
        this.mAppUsageStats = appUsageStats;
        this.mAlarmManager = (AlarmManager) getContext().getSystemService("alarm");
        this.mCompanionManager = companionManager;
        this.mActivityManager = activityManager;
        this.mAmi = ami;
        this.mDeviceIdleController = IDeviceIdleController.Stub.asInterface(ServiceManager.getService("deviceidle"));
        this.mDpm = dpm;
        this.mUm = userManager;
        this.mHandler = new WorkerHandler(looper);
        this.mRankingThread.start();
        try {
            extractorNames = resources.getStringArray(17236056);
        } catch (Resources.NotFoundException e) {
            extractorNames = new String[0];
        }
        this.mUsageStats = usageStats;
        this.mMetricsLogger = new MetricsLogger();
        this.mRankingHandler = new RankingHandlerWorker(this.mRankingThread.getLooper());
        this.mConditionProviders = conditionProviders;
        this.mZenModeHelper = new ZenModeHelper(getContext(), this.mHandler.getLooper(), this.mConditionProviders);
        this.mZenModeHelper.addCallback(new ZenModeHelper.Callback() { // from class: com.android.server.notification.NotificationManagerService.7
            @Override // com.android.server.notification.ZenModeHelper.Callback
            public void onConfigChanged() {
                NotificationManagerService.this.handleSavePolicyFile();
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
        this.mPreferencesHelper = new PreferencesHelper(getContext(), this.mPackageManagerClient, this.mRankingHandler, this.mZenModeHelper);
        this.mRankingHelper = new RankingHelper(getContext(), this.mRankingHandler, this.mPreferencesHelper, this.mZenModeHelper, this.mUsageStats, extractorNames);
        this.mSnoozeHelper = snoozeHelper;
        this.mGroupHelper = groupHelper;
        this.mListeners = notificationListeners;
        this.mAssistants = notificationAssistants;
        this.mAllowedManagedServicePackages = new TriPredicate() { // from class: com.android.server.notification.-$$Lambda$V4J7df5A6vhSIuw7Ym9xgkfahto
            public final boolean test(Object obj, Object obj2, Object obj3) {
                return NotificationManagerService.this.canUseManagedServices((String) obj, (Integer) obj2, (String) obj3);
            }
        };
        this.mPolicyFile = policyFile;
        loadPolicyFile();
        this.mStatusBar = (StatusBarManagerInternal) getLocalService(StatusBarManagerInternal.class);
        StatusBarManagerInternal statusBarManagerInternal = this.mStatusBar;
        if (statusBarManagerInternal != null) {
            statusBarManagerInternal.setNotificationDelegate(this.mNotificationDelegate);
        }
        this.mNotificationLight = lightsManager.getLight(4);
        this.mAttentionLight = lightsManager.getLight(5);
        this.mFallbackVibrationPattern = getLongArray(resources, 17236055, 17, DEFAULT_VIBRATE_PATTERN);
        this.mInCallNotificationUri = Uri.parse("file://" + resources.getString(17039749));
        this.mInCallNotificationAudioAttributes = new AudioAttributes.Builder().setContentType(4).setUsage(2).build();
        this.mInCallNotificationVolume = resources.getFloat(17105062);
        this.mUseAttentionLight = resources.getBoolean(17891560);
        this.mHasLight = resources.getBoolean(17891472);
        boolean z = true;
        if (Settings.Global.getInt(getContext().getContentResolver(), "device_provisioned", 0) == 0) {
            this.mDisableNotificationEffects = true;
        }
        this.mZenModeHelper.initZenMode();
        this.mInterruptionFilter = this.mZenModeHelper.getZenModeListenerInterruptionFilter();
        this.mUserProfiles.updateCache(getContext());
        listenForCallState();
        this.mSettingsObserver = new SettingsObserver(this.mHandler);
        this.mArchive = new Archive(resources.getInteger(17694861));
        if (!this.mPackageManagerClient.hasSystemFeature("android.software.leanback") && !this.mPackageManagerClient.hasSystemFeature("android.hardware.type.television")) {
            z = false;
        }
        this.mIsTelevision = z;
        this.mIsAutomotive = this.mPackageManagerClient.hasSystemFeature("android.hardware.type.automotive", 0);
        this.mNotificationEffectsEnabledForAutomotive = resources.getBoolean(17891451);
        this.mPreferencesHelper.lockChannelsForOEM(getContext().getResources().getStringArray(17236054));
        this.mZenModeHelper.setPriorityOnlyDndExemptPackages(getContext().getResources().getStringArray(17236058));
        this.mNotificationPolicy = new NotificationPolicy(looper, getContext(), this.mRankingHelper, this.mListeners);
        this.mNotificationPolicy.setListener(new NotificationPolicyListener(this, null));
        this.mNotificationPolicy.init();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class NotificationPolicyListener implements NotificationPolicy.OnListener {
        private NotificationPolicyListener() {
        }

        /* synthetic */ NotificationPolicyListener(NotificationManagerService x0, AnonymousClass1 x1) {
            this();
        }

        @Override // com.android.server.notification.NotificationPolicy.OnListener
        public void onProvidersLoaded(ArrayList<NotificationRecord> list, ArrayMap<String, NotificationRecord> map) {
            NotificationManagerService.this.mergeNotificationList(list, map);
        }
    }

    void mergeNotificationList(ArrayList<NotificationRecord> list, ArrayMap<String, NotificationRecord> map) {
        synchronized (this.mNotificationLock) {
            if (list != null) {
                if (!list.isEmpty()) {
                    this.mNotificationList.addAll(list);
                    this.mNotificationsByKey.putAll((ArrayMap<? extends String, ? extends NotificationRecord>) map);
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
        init(Looper.myLooper(), AppGlobals.getPackageManager(), getContext().getPackageManager(), (LightsManager) getLocalService(LightsManager.class), new NotificationListeners(AppGlobals.getPackageManager()), new NotificationAssistants(getContext(), this.mNotificationLock, this.mUserProfiles, AppGlobals.getPackageManager()), new ConditionProviders(getContext(), this.mUserProfiles, AppGlobals.getPackageManager()), null, snoozeHelper, new NotificationUsageStats(getContext()), new AtomicFile(new File(systemDir, "notification_policy.xml"), TAG_NOTIFICATION_POLICY), (ActivityManager) getContext().getSystemService("activity"), getGroupHelper(), ActivityManager.getService(), (UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class), (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class), UriGrantsManager.getService(), (UriGrantsManagerInternal) LocalServices.getService(UriGrantsManagerInternal.class), (AppOpsManager) getContext().getSystemService("appops"), (UserManager) getContext().getSystemService(UserManager.class), (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class));
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
        getContext().registerReceiverAsUser(this.mIntentReceiver, UserHandle.ALL, filter, null, null);
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
        suspendedPkgFilter.addAction("android.intent.action.DISTRACTING_PACKAGES_CHANGED");
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

    private void registerDeviceConfigChange() {
        DeviceConfig.addOnPropertiesChangedListener("systemui", getContext().getMainExecutor(), new DeviceConfig.OnPropertiesChangedListener() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$12gEiRp5yhg_vLn2NsMtnAkm3GI
            public final void onPropertiesChanged(DeviceConfig.Properties properties) {
                NotificationManagerService.this.lambda$registerDeviceConfigChange$0$NotificationManagerService(properties);
            }
        });
    }

    public /* synthetic */ void lambda$registerDeviceConfigChange$0$NotificationManagerService(DeviceConfig.Properties properties) {
        if ("systemui".equals(properties.getNamespace()) && properties.getKeyset().contains("nas_default_service")) {
            this.mAssistants.allowAdjustmentType("key_importance");
            this.mAssistants.resetDefaultAssistantsIfNecessary();
        }
    }

    private GroupHelper getGroupHelper() {
        this.mAutoGroupAtCount = getContext().getResources().getInteger(17694741);
        return new GroupHelper(this.mAutoGroupAtCount, new GroupHelper.Callback() { // from class: com.android.server.notification.NotificationManagerService.9
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
        Intent intent = new Intent(action);
        getContext().sendBroadcastAsUser(intent.addFlags(1073741824), UserHandle.ALL, null);
        intent.setFlags(0);
        Set<String> dndApprovedPackages = this.mConditionProviders.getAllowedPackages();
        for (String pkg : dndApprovedPackages) {
            intent.setPackage(pkg);
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            this.mSystemReady = true;
            this.mAudioManager = (AudioManager) getContext().getSystemService("audio");
            this.mAudioManagerInternal = (AudioManagerInternal) getLocalService(AudioManagerInternal.class);
            this.mWindowManagerInternal = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
            this.mZenModeHelper.onSystemReady();
            this.mRoleObserver = new RoleObserver((RoleManager) getContext().getSystemService(RoleManager.class), this.mPackageManager, getContext().getMainExecutor());
            this.mRoleObserver.init();
            this.mNotificationPolicy.onSystemReady();
        } else if (phase == 600) {
            this.mSettingsObserver.observe();
            this.mListeners.onBootPhaseAppsCanStart();
            this.mAssistants.onBootPhaseAppsCanStart();
            this.mConditionProviders.onBootPhaseAppsCanStart();
            registerDeviceConfigChange();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
    public void updateListenerHintsLocked() {
        int hints = calculateHints();
        int i = this.mListenerHints;
        if (hints == i) {
            return;
        }
        ZenLog.traceListenerHintsChanged(i, hints, this.mEffectsSuppressors.size());
        this.mListenerHints = hints;
        scheduleListenerHintsChanged(hints);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
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
                IntArray profileIds = this.mUserProfiles.getCurrentProfileIds();
                int i = 0;
                for (int N = profileIds.size(); i < N; N = N) {
                    int profileId = profileIds.get(i);
                    cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0, true, profileId, 17, null);
                    i++;
                }
            }
        }
        NotificationChannel preUpdate = this.mPreferencesHelper.getNotificationChannel(pkg, uid, channel.getId(), true);
        this.mPreferencesHelper.updateNotificationChannel(pkg, uid, channel, true);
        maybeNotifyChannelOwner(pkg, uid, preUpdate, channel);
        if (!fromListener) {
            NotificationChannel modifiedChannel = this.mPreferencesHelper.getNotificationChannel(pkg, uid, channel.getId(), false);
            this.mListeners.notifyNotificationChannelChanged(pkg, UserHandle.getUserHandleForUid(uid), modifiedChannel, 2);
        }
        handleSavePolicyFile();
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
        NotificationChannelGroup preUpdate = this.mPreferencesHelper.getNotificationChannelGroup(group.getId(), pkg, uid);
        this.mPreferencesHelper.createNotificationChannelGroup(pkg, uid, group, fromApp);
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
            ArraySet<ComponentName> serviceInfoList = this.mListenersDisablingEffects.valueAt(i);
            Iterator<ComponentName> it = serviceInfoList.iterator();
            while (it.hasNext()) {
                ComponentName info = it.next();
                names.add(info);
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
            ArraySet<ComponentName> listeners = this.mListenersDisablingEffects.valueAt(i);
            if (hints == 0 || (hint & hints) == hint) {
                removed |= listeners.remove(info.component);
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
        ArraySet<ComponentName> hintListeners = this.mListenersDisablingEffects.get(hint);
        hintListeners.add(info.component);
    }

    private int calculateHints() {
        int hints = 0;
        for (int i = this.mListenersDisablingEffects.size() - 1; i >= 0; i--) {
            int hint = this.mListenersDisablingEffects.keyAt(i);
            ArraySet<ComponentName> serviceInfoList = this.mListenersDisablingEffects.valueAt(i);
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
    @GuardedBy({"mNotificationLock"})
    public void updateInterruptionFilterLocked() {
        int interruptionFilter = this.mZenModeHelper.getZenModeListenerInterruptionFilter();
        if (interruptionFilter == this.mInterruptionFilter) {
            return;
        }
        this.mInterruptionFilter = interruptionFilter;
        scheduleInterruptionFilterChanged(interruptionFilter);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public INotificationManager getBinderService() {
        return INotificationManager.Stub.asInterface(this.mService);
    }

    @GuardedBy({"mNotificationLock"})
    protected void reportSeen(NotificationRecord r) {
        if (!r.isProxied()) {
            this.mAppUsageStats.reportEvent(r.sbn.getPackageName(), getRealUserId(r.sbn.getUserId()), 10);
        }
    }

    protected int calculateSuppressedVisualEffects(NotificationManager.Policy incomingPolicy, NotificationManager.Policy currPolicy, int targetSdkVersion) {
        if (incomingPolicy.suppressedVisualEffects == -1) {
            return incomingPolicy.suppressedVisualEffects;
        }
        int[] effectsIntroducedInP = {4, 8, 16, 32, 64, 128, 256};
        int newSuppressedVisualEffects = incomingPolicy.suppressedVisualEffects;
        if (targetSdkVersion < 28) {
            for (int i = 0; i < effectsIntroducedInP.length; i++) {
                newSuppressedVisualEffects = (newSuppressedVisualEffects & (~effectsIntroducedInP[i])) | (currPolicy.suppressedVisualEffects & effectsIntroducedInP[i]);
            }
            int i2 = newSuppressedVisualEffects & 1;
            if (i2 != 0) {
                newSuppressedVisualEffects = newSuppressedVisualEffects | 8 | 4;
            }
            if ((newSuppressedVisualEffects & 2) != 0) {
                return newSuppressedVisualEffects | 16;
            }
            return newSuppressedVisualEffects;
        }
        boolean hasNewEffects = (newSuppressedVisualEffects + (-2)) - 1 > 0;
        if (hasNewEffects) {
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

    @GuardedBy({"mNotificationLock"})
    protected void maybeRecordInterruptionLocked(NotificationRecord r) {
        if (r.isInterruptive() && !r.hasRecordedInterruption()) {
            this.mAppUsageStats.reportInterruptiveNotification(r.sbn.getPackageName(), r.getChannel().getId(), getRealUserId(r.sbn.getUserId()));
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

    @VisibleForTesting
    protected void setNotificationAssistantAccessGrantedForUserInternal(ComponentName assistant, int baseUserId, boolean granted) {
        List<UserInfo> users = this.mUm.getEnabledProfiles(baseUserId);
        if (users != null) {
            for (UserInfo user : users) {
                int userId = user.id;
                if (assistant == null) {
                    ComponentName allowedAssistant = (ComponentName) CollectionUtils.firstOrNull(this.mAssistants.getAllowedComponents(userId));
                    if (allowedAssistant != null) {
                        setNotificationAssistantAccessGrantedForUserInternal(allowedAssistant, userId, false);
                    }
                } else if (!granted || this.mAllowedManagedServicePackages.test(assistant.getPackageName(), Integer.valueOf(userId), this.mAssistants.getRequiredPermission())) {
                    this.mConditionProviders.setPackageOrComponentEnabled(assistant.flattenToString(), userId, false, granted);
                    this.mAssistants.setPackageOrComponentEnabled(assistant.flattenToString(), userId, true, granted);
                    getContext().sendBroadcastAsUser(new Intent("android.app.action.NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED").setPackage(assistant.getPackageName()).addFlags(1073741824), UserHandle.of(userId), null);
                    handleSavePolicyFile();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applyAdjustment(NotificationRecord r, Adjustment adjustment) {
        if (r != null && adjustment.getSignals() != null) {
            Bundle adjustments = adjustment.getSignals();
            Bundle.setDefusable(adjustments, true);
            List<String> toRemove = new ArrayList<>();
            for (String potentialKey : adjustments.keySet()) {
                if (!this.mAssistants.isAdjustmentAllowed(potentialKey)) {
                    toRemove.add(potentialKey);
                }
            }
            for (String removeKey : toRemove) {
                adjustments.remove(removeKey);
            }
            r.addAdjustment(adjustment);
        }
    }

    @GuardedBy({"mNotificationLock"})
    void addAutogroupKeyLocked(String key) {
        NotificationRecord r = this.mNotificationsByKey.get(key);
        if (r != null && r.sbn.getOverrideGroupKey() == null) {
            addAutoGroupAdjustment(r, "ranker_group");
            EventLogTags.writeNotificationAutogrouped(key);
            this.mRankingHandler.requestSort();
        }
    }

    @GuardedBy({"mNotificationLock"})
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
        Adjustment adjustment = new Adjustment(r.sbn.getPackageName(), r.getKey(), signals, "", r.sbn.getUserId());
        r.addAdjustment(adjustment);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
    public void clearAutogroupSummaryLocked(int userId, String pkg) {
        NotificationRecord removed;
        ArrayMap<String, String> summaries = this.mAutobundledSummaries.get(Integer.valueOf(userId));
        if (summaries != null && summaries.containsKey(pkg) && (removed = findNotificationByKeyLocked(summaries.remove(pkg))) != null) {
            boolean wasPosted = removeFromNotificationListsLocked(removed);
            cancelNotificationLocked(removed, false, 16, wasPosted, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
    public boolean hasAutoGroupSummaryLocked(StatusBarNotification sbn) {
        ArrayMap<String, String> summaries = this.mAutobundledSummaries.get(Integer.valueOf(sbn.getUserId()));
        return summaries != null && summaries.containsKey(sbn.getPackageName());
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:49:0x016b -> B:47:0x0169). Please submit an issue!!! */
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
            dump.put("bans", this.mPreferencesHelper.dumpBansJson(filter));
            dump.put("ranking", this.mPreferencesHelper.dumpJson(filter));
            dump.put("stats", this.mUsageStats.dumpJson(filter));
            dump.put("channels", this.mPreferencesHelper.dumpChannelsJson(filter));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pw.println(dump);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpRemoteViewStats(PrintWriter pw, DumpFilter filter) {
        PulledStats stats = this.mUsageStats.remoteViewStats(filter.since, true);
        if (stats == null) {
            pw.println("no remote view stats reported.");
        } else {
            stats.dump(1, pw, filter);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpProto(FileDescriptor fd, DumpFilter filter) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (this.mNotificationLock) {
            int N = this.mNotificationList.size();
            for (int i = 0; i < N; i++) {
                NotificationRecord nr = this.mNotificationList.get(i);
                if (!filter.filtered || filter.matches(nr.sbn)) {
                    nr.dump(proto, 2246267895809L, filter.redact, 1);
                }
            }
            int N2 = this.mEnqueuedNotifications.size();
            for (int i2 = 0; i2 < N2; i2++) {
                NotificationRecord nr2 = this.mEnqueuedNotifications.get(i2);
                if (!filter.filtered || filter.matches(nr2.sbn)) {
                    nr2.dump(proto, 2246267895809L, filter.redact, 0);
                }
            }
            List<NotificationRecord> snoozed = this.mSnoozeHelper.getSnoozed();
            int N3 = snoozed.size();
            for (int i3 = 0; i3 < N3; i3++) {
                NotificationRecord nr3 = snoozed.get(i3);
                if (!filter.filtered || filter.matches(nr3.sbn)) {
                    nr3.dump(proto, 2246267895809L, filter.redact, 2);
                }
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
            int i4 = 0;
            while (i4 < this.mListenersDisablingEffects.size()) {
                long effectsToken = proto.start(2246267895813L);
                int i5 = i4;
                long zenLog2 = zenLog;
                proto.write(1120986464257L, this.mListenersDisablingEffects.keyAt(i5));
                ArraySet<ComponentName> listeners = this.mListenersDisablingEffects.valueAt(i5);
                for (int j = 0; j < listeners.size(); j++) {
                    ComponentName componentName = listeners.valueAt(j);
                    componentName.writeToProto(proto, 2246267895811L);
                }
                proto.end(effectsToken);
                i4 = i5 + 1;
                zenLog = zenLog2;
            }
            long assistantsToken = proto.start(1146756268038L);
            this.mAssistants.dump(proto, filter);
            proto.end(assistantsToken);
            long conditionsToken = proto.start(1146756268039L);
            this.mConditionProviders.dump(proto, filter);
            proto.end(conditionsToken);
            long rankingToken = proto.start(1146756268040L);
            this.mRankingHelper.dump(proto, filter);
            this.mPreferencesHelper.dump(proto, filter);
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
        int j = 0;
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
                    while (true) {
                        if (!iter.hasNext()) {
                            break;
                        }
                        StatusBarNotification sbn = iter.next();
                        if (filter.matches(sbn)) {
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
                pw.println("\n Notification Preferences:");
                this.mPreferencesHelper.dump(pw, "    ", filter);
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
                    ArraySet<ComponentName> listeners = this.mListenersDisablingEffects.valueAt(i4);
                    int listenerSize = listeners.size();
                    for (int j2 = 0; j2 < listenerSize; j2++) {
                        if (j2 > 0) {
                            pw.print(',');
                        }
                        ComponentName listener = listeners.valueAt(j2);
                        if (listener != null) {
                            pw.print(listener);
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
            return NotificationManagerService.this.mPreferencesHelper.getNotificationChannel(pkg, uid, channelId, false);
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
                    NotificationManagerService.AnonymousClass11.this.lambda$removeForegroundServiceFlagFromNotification$0$NotificationManagerService$11(pkg, notificationId, userId);
                }
            });
        }

        public /* synthetic */ void lambda$removeForegroundServiceFlagFromNotification$0$NotificationManagerService$11(String pkg, int notificationId, int userId) {
            synchronized (NotificationManagerService.this.mNotificationLock) {
                List<NotificationRecord> enqueued = NotificationManagerService.this.findNotificationsByListLocked(NotificationManagerService.this.mEnqueuedNotifications, pkg, null, notificationId, userId);
                for (int i = 0; i < enqueued.size(); i++) {
                    removeForegroundServiceFlagLocked(enqueued.get(i));
                }
                NotificationRecord r = NotificationManagerService.this.findNotificationByListLocked(NotificationManagerService.this.mNotificationList, pkg, null, notificationId, userId);
                if (r != null) {
                    removeForegroundServiceFlagLocked(r);
                    NotificationManagerService.this.mRankingHelper.sort(NotificationManagerService.this.mNotificationList);
                    NotificationManagerService.this.mListeners.notifyPostedLocked(r, r);
                }
            }
        }

        @GuardedBy({"mNotificationLock"})
        private void removeForegroundServiceFlagLocked(NotificationRecord r) {
            if (r == null) {
                return;
            }
            StatusBarNotification sbn = r.sbn;
            sbn.getNotification().flags = r.mOriginalFlags & (-65);
        }
    }

    void enqueueNotificationInternal(String pkg, String opPkg, int callingUid, int callingPid, String tag, int id, Notification notification, int incomingUserId) {
        boolean z;
        boolean z2;
        int intentCount;
        String channelId;
        if (DBG) {
            Slog.v(TAG, "enqueueNotificationInternal: pkg=" + pkg + " id=" + id + " notification=" + notification);
        }
        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg + " id=" + id + " notification=" + notification);
        }
        int userId = ActivityManager.handleIncomingUser(callingPid, callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        UserHandle user = UserHandle.of(userId);
        int notificationUid = resolveNotificationUid(opPkg, pkg, callingUid, userId);
        checkRestrictedCategories(notification);
        try {
            fixNotification(notification, pkg, userId);
            this.mUsageStats.registerEnqueuedByApp(pkg);
            String channelId2 = notification.getChannelId();
            if (this.mIsTelevision && new Notification.TvExtender(notification).getChannelId() != null) {
                channelId2 = new Notification.TvExtender(notification).getChannelId();
            }
            NotificationChannel channel = this.mPreferencesHelper.getNotificationChannel(pkg, notificationUid, channelId2, false);
            if (channel != null) {
                this.mNotificationPolicy.applyNotificationPolicy(notification, pkg);
                StatusBarNotification n = new StatusBarNotification(pkg, opPkg, id, tag, notificationUid, callingPid, notification, user, (String) null, System.currentTimeMillis());
                NotificationRecord r = new NotificationRecord(getContext(), n, channel);
                r.setIsAppImportanceLocked(this.mPreferencesHelper.getIsAppImportanceLocked(pkg, callingUid));
                if ((notification.flags & 64) == 0) {
                    z = true;
                    z2 = false;
                } else {
                    boolean fgServiceShown = channel.isFgServiceShown();
                    if (((channel.getUserLockedFields() & 4) == 0 || !fgServiceShown) && (r.getImportance() == 1 || r.getImportance() == 0)) {
                        if (TextUtils.isEmpty(channelId2)) {
                            z = true;
                            z2 = false;
                        } else if ("miscellaneous".equals(channelId2)) {
                            z = true;
                            z2 = false;
                        } else {
                            channel.setImportance(2);
                            r.setSystemImportance(2);
                            if (fgServiceShown) {
                                z = true;
                            } else {
                                channel.unlockFields(4);
                                z = true;
                                channel.setFgServiceShown(true);
                            }
                            z2 = false;
                            this.mPreferencesHelper.updateNotificationChannel(pkg, notificationUid, channel, false);
                            r.updateNotificationChannel(channel);
                        }
                        r.setSystemImportance(2);
                    } else if (fgServiceShown || TextUtils.isEmpty(channelId2)) {
                        z = true;
                        z2 = false;
                    } else if ("miscellaneous".equals(channelId2)) {
                        z = true;
                        z2 = false;
                    } else {
                        channel.setFgServiceShown(true);
                        r.updateNotificationChannel(channel);
                        z = true;
                        z2 = false;
                    }
                }
                if (r.sbn.getOverrideGroupKey() == null) {
                    z = z2;
                }
                if (!checkDisqualifyingFeatures(userId, notificationUid, id, tag, r, z)) {
                    return;
                }
                if (notification.allPendingIntents != null && (intentCount = notification.allPendingIntents.size()) > 0) {
                    ActivityManagerInternal am = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
                    long duration = ((DeviceIdleController.LocalService) LocalServices.getService(DeviceIdleController.LocalService.class)).getNotificationWhitelistDuration();
                    int i = 0;
                    while (i < intentCount) {
                        PendingIntent pendingIntent = (PendingIntent) notification.allPendingIntents.valueAt(i);
                        if (pendingIntent == null) {
                            channelId = channelId2;
                        } else {
                            am.setPendingIntentWhitelistDuration(pendingIntent.getTarget(), WHITELIST_TOKEN, duration);
                            channelId = channelId2;
                            am.setPendingIntentAllowBgActivityStarts(pendingIntent.getTarget(), WHITELIST_TOKEN, 7);
                        }
                        i++;
                        channelId2 = channelId;
                    }
                }
                this.mHandler.post(new EnqueueNotificationRunnable(userId, r));
                return;
            }
            String noChannelStr = "No Channel found for pkg=" + pkg + ", channelId=" + channelId2 + ", id=" + id + ", tag=" + tag + ", opPkg=" + opPkg + ", callingUid=" + callingUid + ", userId=" + userId + ", incomingUserId=" + incomingUserId + ", notificationUid=" + notificationUid + ", notification=" + notification;
            Slog.e(TAG, noChannelStr);
            boolean appNotificationsOff = this.mPreferencesHelper.getImportance(pkg, notificationUid) == 0;
            if (!appNotificationsOff) {
                doChannelWarningToast("Developer warning for package \"" + pkg + "\"\nFailed to post notification on channel \"" + channelId2 + "\"\nSee log for more details");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Cannot create a context for sending app", e);
        }
    }

    @VisibleForTesting
    protected void fixNotification(Notification notification, String pkg, int userId) throws PackageManager.NameNotFoundException {
        ApplicationInfo ai = this.mPackageManagerClient.getApplicationInfoAsUser(pkg, 268435456, userId == -1 ? 0 : userId);
        Notification.addFieldsFromContext(ai, notification);
        int canColorize = this.mPackageManagerClient.checkPermission("android.permission.USE_COLORIZED_NOTIFICATIONS", pkg);
        if (canColorize == 0) {
            notification.flags |= 2048;
        } else {
            notification.flags &= -2049;
        }
        if (notification.fullScreenIntent != null && ai.targetSdkVersion >= 29) {
            int fullscreenIntentPermission = this.mPackageManagerClient.checkPermission("android.permission.USE_FULL_SCREEN_INTENT", pkg);
            if (fullscreenIntentPermission != 0) {
                notification.fullScreenIntent = null;
                Slog.w(TAG, "Package " + pkg + ": Use of fullScreenIntent requires the USE_FULL_SCREEN_INTENT permission");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void flagNotificationForBubbles(NotificationRecord r, String pkg, int userId, NotificationRecord oldRecord) {
        Notification notification = r.getNotification();
        if (isNotificationAppropriateToBubble(r, pkg, userId, oldRecord)) {
            notification.flags |= 4096;
        } else {
            notification.flags &= -4097;
        }
        boolean appIsForeground = this.mActivityManager.getPackageImportance(pkg) == 100;
        Notification.BubbleMetadata metadata = notification.getBubbleMetadata();
        if (!appIsForeground && metadata != null) {
            int flags = metadata.getFlags();
            metadata.setFlags(flags & (-2) & (-3));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isNotificationAppropriateToBubble(NotificationRecord r, String pkg, int userId, NotificationRecord oldRecord) {
        ArrayList<Person> peopleList;
        Notification notification = r.getNotification();
        if (canBubble(r, pkg, userId)) {
            if (this.mActivityManager.isLowRamDevice()) {
                logBubbleError(r.getKey(), "low ram device");
                return false;
            } else if (this.mActivityManager.getPackageImportance(pkg) == 100) {
                return true;
            } else {
                if (oldRecord == null || (oldRecord.getNotification().flags & 4096) == 0) {
                    if (notification.extras != null) {
                        peopleList = notification.extras.getParcelableArrayList("android.people.list");
                    } else {
                        peopleList = null;
                    }
                    boolean isMessageStyle = Notification.MessagingStyle.class.equals(notification.getNotificationStyle());
                    if (!isMessageStyle && (peopleList == null || peopleList.isEmpty())) {
                        logBubbleError(r.getKey(), "if not foreground, must have a person and be Notification.MessageStyle or Notification.CATEGORY_CALL");
                        return false;
                    }
                    boolean isCall = "call".equals(notification.category);
                    boolean hasForegroundService = (notification.flags & 64) != 0;
                    if (isMessageStyle) {
                        if (hasValidRemoteInput(notification)) {
                            return true;
                        }
                        logBubbleError(r.getKey(), "messages require valid remote input");
                        return false;
                    } else if (isCall) {
                        if (hasForegroundService) {
                            return true;
                        }
                        logBubbleError(r.getKey(), "calls require foreground service");
                        return false;
                    } else {
                        logBubbleError(r.getKey(), "if not foreground, must be Notification.MessageStyle or Notification.CATEGORY_CALL");
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private boolean canBubble(NotificationRecord r, String pkg, int userId) {
        Notification notification = r.getNotification();
        Notification.BubbleMetadata metadata = notification.getBubbleMetadata();
        if (metadata == null || !canLaunchInActivityView(getContext(), metadata.getIntent(), pkg)) {
            return false;
        }
        if (!this.mPreferencesHelper.bubblesEnabled()) {
            String key = r.getKey();
            logBubbleError(key, "bubbles disabled for user: " + userId);
            return false;
        } else if (!this.mPreferencesHelper.areBubblesAllowed(pkg, userId)) {
            String key2 = r.getKey();
            logBubbleError(key2, "bubbles for package: " + pkg + " disabled for user: " + userId);
            return false;
        } else if (!r.getChannel().canBubble()) {
            String key3 = r.getKey();
            logBubbleError(key3, "bubbles for channel " + r.getChannel().getId() + " disabled");
            return false;
        } else {
            return true;
        }
    }

    private boolean hasValidRemoteInput(Notification n) {
        Notification.Action[] actions = n.actions;
        if (actions != null) {
            for (Notification.Action action : actions) {
                RemoteInput[] inputs = action.getRemoteInputs();
                if (inputs != null && inputs.length > 0) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private void logBubbleError(String key, String failureMessage) {
        if (DBG) {
            Log.w(TAG, "Bubble notification: " + key + " failed: " + failureMessage);
        }
    }

    @VisibleForTesting
    protected boolean canLaunchInActivityView(Context context, PendingIntent pendingIntent, String packageName) {
        ActivityInfo info;
        if (pendingIntent == null) {
            Log.w(TAG, "Unable to create bubble -- no intent");
            return false;
        }
        long token = Binder.clearCallingIdentity();
        try {
            Intent intent = pendingIntent.getIntent();
            if (intent != null) {
                info = intent.resolveActivityInfo(context.getPackageManager(), 0);
            } else {
                info = null;
            }
            if (info == null) {
                StatsLog.write(173, packageName, 1);
                Log.w(TAG, "Unable to send as bubble -- couldn't find activity info for intent: " + intent);
                return false;
            } else if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
                StatsLog.write(173, packageName, 2);
                Log.w(TAG, "Unable to send as bubble -- activity is not resizable for intent: " + intent);
                return false;
            } else if (info.documentLaunchMode != 2) {
                StatsLog.write(173, packageName, 3);
                Log.w(TAG, "Unable to send as bubble -- activity is not documentLaunchMode=always for intent: " + intent);
                return false;
            } else if ((info.flags & Integer.MIN_VALUE) != 0) {
                return true;
            } else {
                Log.w(TAG, "Unable to send as bubble -- activity is not embeddable for intent: " + intent);
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void doChannelWarningToast(final CharSequence toastText) {
        Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$9w6AHlBdw5iqUuefzw5prTMG5fg
            public final void runOrThrow() {
                NotificationManagerService.this.lambda$doChannelWarningToast$1$NotificationManagerService(toastText);
            }
        });
    }

    public /* synthetic */ void lambda$doChannelWarningToast$1$NotificationManagerService(CharSequence toastText) throws Exception {
        boolean z = Build.IS_DEBUGGABLE;
        ContentResolver contentResolver = getContext().getContentResolver();
        int defaultWarningEnabled = z ? 1 : 0;
        boolean warningEnabled = Settings.Global.getInt(contentResolver, "show_notification_channel_warnings", defaultWarningEnabled) != 0;
        if (warningEnabled) {
            Toast toast = Toast.makeText(getContext(), this.mHandler.getLooper(), toastText, 0);
            toast.show();
        }
    }

    @VisibleForTesting
    int resolveNotificationUid(String callingPkg, String targetPkg, int callingUid, int userId) {
        if (userId == -1) {
            userId = 0;
        }
        if (isCallerSameApp(targetPkg, callingUid, userId) && (TextUtils.equals(callingPkg, targetPkg) || isCallerSameApp(callingPkg, callingUid, userId))) {
            return callingUid;
        }
        int targetUid = -1;
        try {
            targetUid = this.mPackageManagerClient.getPackageUidAsUser(targetPkg, userId);
        } catch (PackageManager.NameNotFoundException e) {
        }
        if (targetUid != -1 && (isCallerAndroid(callingPkg, callingUid) || this.mPreferencesHelper.isDelegateAllowed(targetPkg, targetUid, callingPkg, callingUid))) {
            return targetUid;
        }
        throw new SecurityException("Caller " + callingPkg + ":" + callingUid + " cannot post for pkg " + targetPkg + " in user " + userId);
    }

    private boolean checkDisqualifyingFeatures(int userId, int uid, int id, String tag, NotificationRecord r, boolean isAutogroup) {
        String pkg = r.sbn.getPackageName();
        boolean isSystemNotification = isUidSystemOrPhone(uid) || PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkg);
        boolean isNotificationFromListener = this.mListeners.isListenerPackage(pkg);
        if (!isSystemNotification && !isNotificationFromListener) {
            synchronized (this.mNotificationLock) {
                try {
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    int callingUid = Binder.getCallingUid();
                    if (this.mNotificationsByKey.get(r.sbn.getKey()) == null && isCallerInstantApp(callingUid, userId)) {
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
        handleSavePolicyFile();
        return false;
    }

    @GuardedBy({"mNotificationLock"})
    protected int getNotificationCountLocked(String pkg, int userId, int excludedId, String excludedTag) {
        int count = 0;
        int N = this.mNotificationList.size();
        for (int i = 0; i < N; i++) {
            NotificationRecord existing = this.mNotificationList.get(i);
            if (existing.sbn.getPackageName().equals(pkg) && existing.sbn.getUserId() == userId && (existing.sbn.getId() != excludedId || !TextUtils.equals(existing.sbn.getTag(), excludedTag))) {
                count++;
            }
        }
        int M = this.mEnqueuedNotifications.size();
        for (int i2 = 0; i2 < M; i2++) {
            NotificationRecord existing2 = this.mEnqueuedNotifications.get(i2);
            if (existing2.sbn.getPackageName().equals(pkg) && existing2.sbn.getUserId() == userId) {
                count++;
            }
        }
        return count;
    }

    protected boolean isBlocked(NotificationRecord r, NotificationUsageStats usageStats) {
        if (isBlocked(r)) {
            Slog.e(TAG, "Suppressing notification from package by user request.");
            usageStats.registerBlocked(r);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isBlocked(NotificationRecord r) {
        String pkg = r.sbn.getPackageName();
        int callingUid = r.sbn.getUid();
        return this.mPreferencesHelper.isGroupBlocked(pkg, callingUid, r.getChannel().getGroup()) || this.mPreferencesHelper.getImportance(pkg, callingUid) == 0 || r.getImportance() == 0;
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

        @GuardedBy({"mNotificationLock"})
        void snoozeLocked(NotificationRecord r) {
            if (r.sbn.isGroup()) {
                List<NotificationRecord> groupNotifications = NotificationManagerService.this.findGroupNotificationsLocked(r.sbn.getPackageName(), r.sbn.getGroupKey(), r.sbn.getUserId());
                if (r.getNotification().isGroupSummary()) {
                    for (int i = 0; i < groupNotifications.size(); i++) {
                        snoozeNotificationLocked(groupNotifications.get(i));
                    }
                    return;
                } else if (NotificationManagerService.this.mSummaryByGroupKey.containsKey(r.sbn.getGroupKey())) {
                    if (groupNotifications.size() != 2) {
                        snoozeNotificationLocked(r);
                        return;
                    }
                    for (int i2 = 0; i2 < groupNotifications.size(); i2++) {
                        snoozeNotificationLocked(groupNotifications.get(i2));
                    }
                    return;
                } else {
                    snoozeNotificationLocked(r);
                    return;
                }
            }
            snoozeNotificationLocked(r);
        }

        @GuardedBy({"mNotificationLock"})
        void snoozeNotificationLocked(NotificationRecord r) {
            MetricsLogger.action(r.getLogMaker().setCategory(831).setType(2).addTaggedData(1139, Long.valueOf(this.mDuration)).addTaggedData(832, Integer.valueOf(this.mSnoozeCriterionId == null ? 0 : 1)));
            NotificationManagerService.this.reportUserInteraction(r);
            boolean wasPosted = NotificationManagerService.this.removeFromNotificationListsLocked(r);
            NotificationManagerService.this.cancelNotificationLocked(r, false, 18, wasPosted, null);
            NotificationManagerService.this.updateLightsLocked();
            if (this.mSnoozeCriterionId == null) {
                NotificationManagerService.this.mSnoozeHelper.snooze(r, this.mDuration);
            } else {
                NotificationManagerService.this.mAssistants.notifyAssistantSnoozedLocked(r.sbn, this.mSnoozeCriterionId);
                NotificationManagerService.this.mSnoozeHelper.snooze(r);
            }
            r.recordSnoozed();
            NotificationManagerService.this.handleSavePolicyFile();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public class CancelNotificationRunnable implements Runnable {
        private final int mCallingPid;
        private final int mCallingUid;
        private final int mCount;
        private final int mId;
        private final ManagedServices.ManagedServiceInfo mListener;
        private final int mMustHaveFlags;
        private final int mMustNotHaveFlags;
        private final String mPkg;
        private final int mRank;
        private final int mReason;
        private final boolean mSendDelete;
        private final String mTag;
        private final int mUserId;

        CancelNotificationRunnable(int callingUid, int callingPid, String pkg, String tag, int id, int mustHaveFlags, int mustNotHaveFlags, boolean sendDelete, int userId, int reason, int rank, int count, ManagedServices.ManagedServiceInfo listener) {
            this.mCallingUid = callingUid;
            this.mCallingPid = callingPid;
            this.mPkg = pkg;
            this.mTag = tag;
            this.mId = id;
            this.mMustHaveFlags = mustHaveFlags;
            this.mMustNotHaveFlags = mustNotHaveFlags;
            this.mSendDelete = sendDelete;
            this.mUserId = userId;
            this.mReason = reason;
            this.mRank = rank;
            this.mCount = count;
            this.mListener = listener;
        }

        @Override // java.lang.Runnable
        public void run() {
            FlagChecker childrenFlagChecker;
            ManagedServices.ManagedServiceInfo managedServiceInfo = this.mListener;
            String listenerName = managedServiceInfo == null ? null : managedServiceInfo.component.toShortString();
            if (NotificationManagerService.DBG) {
                EventLogTags.writeNotificationCancel(this.mCallingUid, this.mCallingPid, this.mPkg, this.mId, this.mTag, this.mUserId, this.mMustHaveFlags, this.mMustNotHaveFlags, this.mReason, listenerName);
            }
            synchronized (NotificationManagerService.this.mNotificationLock) {
                if (NotificationManagerService.this.findNotificationByListLocked(NotificationManagerService.this.mEnqueuedNotifications, this.mPkg, this.mTag, this.mId, this.mUserId) == null) {
                    NotificationRecord r = NotificationManagerService.this.findNotificationByListLocked(NotificationManagerService.this.mNotificationList, this.mPkg, this.mTag, this.mId, this.mUserId);
                    if (r != null) {
                        if (this.mReason == 1) {
                            NotificationManagerService.this.mUsageStats.registerClickedByUser(r);
                        }
                        if ((r.getNotification().flags & this.mMustHaveFlags) != this.mMustHaveFlags) {
                            return;
                        }
                        if ((r.getNotification().flags & this.mMustNotHaveFlags) != 0) {
                            return;
                        }
                        if (this.mReason != 2 && this.mReason != 1 && this.mReason != 3) {
                            childrenFlagChecker = null;
                            boolean wasPosted = NotificationManagerService.this.removeFromNotificationListsLocked(r);
                            NotificationManagerService.this.cancelNotificationLocked(r, this.mSendDelete, this.mReason, this.mRank, this.mCount, wasPosted, listenerName);
                            NotificationManagerService.this.cancelGroupChildrenLocked(r, this.mCallingUid, this.mCallingPid, listenerName, this.mSendDelete, childrenFlagChecker);
                            NotificationManagerService.this.updateLightsLocked();
                        }
                        FlagChecker childrenFlagChecker2 = new FlagChecker() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$CancelNotificationRunnable$1i8BOFS2Ap_BvazcwqssFxW6U1U
                            @Override // com.android.server.notification.NotificationManagerService.FlagChecker
                            public final boolean apply(int i) {
                                return NotificationManagerService.CancelNotificationRunnable.lambda$run$0(i);
                            }
                        };
                        childrenFlagChecker = childrenFlagChecker2;
                        boolean wasPosted2 = NotificationManagerService.this.removeFromNotificationListsLocked(r);
                        NotificationManagerService.this.cancelNotificationLocked(r, this.mSendDelete, this.mReason, this.mRank, this.mCount, wasPosted2, listenerName);
                        NotificationManagerService.this.cancelGroupChildrenLocked(r, this.mCallingUid, this.mCallingPid, listenerName, this.mSendDelete, childrenFlagChecker);
                        NotificationManagerService.this.updateLightsLocked();
                    } else if (this.mReason != 18) {
                        boolean wasSnoozed = NotificationManagerService.this.mSnoozeHelper.cancel(this.mUserId, this.mPkg, this.mTag, this.mId);
                        if (wasSnoozed) {
                            NotificationManagerService.this.handleSavePolicyFile();
                        }
                    }
                    return;
                }
                NotificationManagerService.this.mHandler.post(this);
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$run$0(int flags) {
            if ((flags & 4096) != 0) {
                return false;
            }
            return true;
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
            int enqueueStatus;
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
                NotificationManagerService.this.flagNotificationForBubbles(this.r, pkg, callingUid, old);
                NotificationManagerService.this.handleGroupedNotificationLocked(this.r, old, callingUid, callingPid);
                if (n.isGroup() && notification.isGroupChild()) {
                    NotificationManagerService.this.mSnoozeHelper.repostGroupSummary(pkg, this.r.getUserId(), n.getGroupKey());
                }
                if (!pkg.equals("com.android.providers.downloads") || Log.isLoggable("DownloadManager", 2)) {
                    if (old == null) {
                        enqueueStatus = 0;
                    } else {
                        enqueueStatus = 1;
                    }
                    EventLogTags.writeNotificationEnqueue(callingUid, callingPid, pkg, id, tag, this.userId, notification.toString(), enqueueStatus);
                }
                if (!NotificationManagerService.this.mAssistants.isEnabled()) {
                    NotificationManagerService.this.mHandler.post(new PostNotificationRunnable(this.r.getKey()));
                } else {
                    NotificationManagerService.this.mAssistants.onNotificationEnqueuedLocked(this.r);
                    NotificationManagerService.this.mHandler.postDelayed(new PostNotificationRunnable(this.r.getKey()), 100L);
                }
            }
        }
    }

    @GuardedBy({"mNotificationLock"})
    boolean isPackagePausedOrSuspended(String pkg, int uid) {
        PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        int flags = pmi.getDistractingPackageRestrictions(pkg, Binder.getCallingUserHandle().getIdentifier());
        boolean isPaused = (flags & 2) != 0;
        return isPaused | isPackageSuspendedForUser(pkg, uid);
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
                int N = NotificationManagerService.this.mEnqueuedNotifications.size();
                int i = 0;
                while (true) {
                    if (i >= N) {
                        break;
                    }
                    NotificationRecord enqueued = NotificationManagerService.this.mEnqueuedNotifications.get(i);
                    if (Objects.equals(this.key, enqueued.getKey())) {
                        r = enqueued;
                        break;
                    }
                    i++;
                }
                if (r == null) {
                    Slog.i(NotificationManagerService.TAG, "Cannot find enqueued record for key: " + this.key);
                    int N2 = NotificationManagerService.this.mEnqueuedNotifications.size();
                    int i2 = 0;
                    while (true) {
                        if (i2 >= N2) {
                            break;
                        }
                        NotificationRecord enqueued2 = NotificationManagerService.this.mEnqueuedNotifications.get(i2);
                        if (Objects.equals(this.key, enqueued2.getKey())) {
                            NotificationManagerService.this.mEnqueuedNotifications.remove(i2);
                            break;
                        }
                        i2++;
                    }
                } else if (NotificationManagerService.this.isBlocked(r)) {
                    Slog.i(NotificationManagerService.TAG, "notification blocked by assistant request");
                    int N3 = NotificationManagerService.this.mEnqueuedNotifications.size();
                    int i3 = 0;
                    while (true) {
                        if (i3 >= N3) {
                            break;
                        }
                        NotificationRecord enqueued3 = NotificationManagerService.this.mEnqueuedNotifications.get(i3);
                        if (Objects.equals(this.key, enqueued3.getKey())) {
                            NotificationManagerService.this.mEnqueuedNotifications.remove(i3);
                            break;
                        }
                        i3++;
                    }
                } else {
                    boolean isPackageSuspended = NotificationManagerService.this.isPackagePausedOrSuspended(r.sbn.getPackageName(), r.getUid());
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
                        boolean isInterruptive = NotificationManagerService.this.isVisuallyInterruptive(old, r);
                        r.setTextChanged(isInterruptive);
                        r.setInterruptive(isInterruptive);
                    }
                    if (!NotificationManagerService.this.mOsdEnabled && notification.hasDisplayFlag(2)) {
                        Slog.i(NotificationManagerService.TAG, "Osd toast cannt run because of Osd toast disabled");
                        int N4 = NotificationManagerService.this.mEnqueuedNotifications.size();
                        int i4 = 0;
                        while (true) {
                            if (i4 >= N4) {
                                break;
                            }
                            NotificationRecord enqueued4 = NotificationManagerService.this.mEnqueuedNotifications.get(i4);
                            if (Objects.equals(this.key, enqueued4.getKey())) {
                                NotificationManagerService.this.mEnqueuedNotifications.remove(i4);
                                break;
                            }
                            i4++;
                        }
                        return;
                    }
                    NotificationManagerService.this.mNotificationsByKey.put(n.getKey(), r);
                    if ((notification.flags & 64) != 0) {
                        notification.flags |= 34;
                    }
                    NotificationManagerService.this.mRankingHelper.extractSignals(r);
                    NotificationManagerService.this.mRankingHelper.sort(NotificationManagerService.this.mNotificationList);
                    if (!r.isHidden()) {
                        NotificationManagerService.this.buzzBeepBlinkLocked(r);
                    }
                    if (notification.getSmallIcon() != null) {
                        StatusBarNotification oldSbn = old != null ? old.sbn : null;
                        NotificationManagerService.this.mListeners.notifyPostedLocked(r, old);
                        if ((oldSbn == null || !Objects.equals(oldSbn.getGroup(), n.getGroup())) && !NotificationManagerService.this.isCritical(r)) {
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
                            NotificationManagerService.this.mListeners.notifyRemovedLocked(r, 4, r.getStats());
                            NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.NotificationManagerService.PostNotificationRunnable.2
                                @Override // java.lang.Runnable
                                public void run() {
                                    NotificationManagerService.this.mGroupHelper.onNotificationRemoved(n);
                                }
                            });
                        }
                        Slog.e(NotificationManagerService.TAG, "WARNING: In a future release this will crash the app: " + n.getPackageName());
                    }
                    NotificationManagerService.this.maybeRecordInterruptionLocked(r);
                    int N5 = NotificationManagerService.this.mEnqueuedNotifications.size();
                    int i5 = 0;
                    while (true) {
                        if (i5 >= N5) {
                            break;
                        }
                        NotificationRecord enqueued5 = NotificationManagerService.this.mEnqueuedNotifications.get(i5);
                        if (Objects.equals(this.key, enqueued5.getKey())) {
                            NotificationManagerService.this.mEnqueuedNotifications.remove(i5);
                            break;
                        }
                        i5++;
                    }
                }
            }
        }
    }

    @GuardedBy({"mNotificationLock"})
    @VisibleForTesting
    protected boolean isVisuallyInterruptive(NotificationRecord old, NotificationRecord r) {
        Notification.Builder oldB;
        Notification.Builder newB;
        if (!r.sbn.isGroup() || !r.sbn.getNotification().isGroupSummary()) {
            if (old == null) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: new notification");
                }
                return true;
            }
            Notification oldN = old.sbn.getNotification();
            Notification newN = r.sbn.getNotification();
            if (oldN.extras == null || newN.extras == null) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: no extras");
                }
                return false;
            } else if ((r.sbn.getNotification().flags & 64) != 0) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: foreground service");
                }
                return false;
            } else {
                String oldTitle = String.valueOf(oldN.extras.get("android.title"));
                String newTitle = String.valueOf(newN.extras.get("android.title"));
                if (!Objects.equals(oldTitle, newTitle)) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: changed title");
                        StringBuilder sb = new StringBuilder();
                        sb.append("INTERRUPTIVENESS: ");
                        sb.append(String.format("   old title: %s (%s@0x%08x)", oldTitle, oldTitle.getClass(), Integer.valueOf(oldTitle.hashCode())));
                        Slog.v(TAG, sb.toString());
                        Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   new title: %s (%s@0x%08x)", newTitle, newTitle.getClass(), Integer.valueOf(newTitle.hashCode())));
                    }
                    return true;
                }
                String oldText = String.valueOf(oldN.extras.get("android.text"));
                String newText = String.valueOf(newN.extras.get("android.text"));
                if (!Objects.equals(oldText, newText)) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: changed text");
                        StringBuilder sb2 = new StringBuilder();
                        sb2.append("INTERRUPTIVENESS: ");
                        sb2.append(String.format("   old text: %s (%s@0x%08x)", oldText, oldText.getClass(), Integer.valueOf(oldText.hashCode())));
                        Slog.v(TAG, sb2.toString());
                        Slog.v(TAG, "INTERRUPTIVENESS: " + String.format("   new text: %s (%s@0x%08x)", newText, newText.getClass(), Integer.valueOf(newText.hashCode())));
                    }
                    return true;
                } else if (oldN.hasCompletedProgress() != newN.hasCompletedProgress()) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: completed progress");
                    }
                    return true;
                } else if (r.canBubble()) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: bubble");
                    }
                    return false;
                } else if (Notification.areActionsVisiblyDifferent(oldN, newN)) {
                    if (DEBUG_INTERRUPTIVENESS) {
                        Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: changed actions");
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
                            Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: styles differ");
                        }
                        return true;
                    }
                    if (Notification.areRemoteViewsChanged(oldB, newB)) {
                        if (DEBUG_INTERRUPTIVENESS) {
                            Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is interruptive: remoteviews differ");
                        }
                        return true;
                    }
                    return false;
                }
            }
        }
        if (DEBUG_INTERRUPTIVENESS) {
            Slog.v(TAG, "INTERRUPTIVENESS: " + r.getKey() + " is not interruptive: summary");
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isCritical(NotificationRecord record) {
        return record.getCriticality() < 2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
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

    @GuardedBy({"mNotificationLock"})
    @VisibleForTesting
    void scheduleTimeoutLocked(NotificationRecord record) {
        if (record.getNotification().getTimeoutAfter() > 0) {
            PendingIntent pi = PendingIntent.getBroadcast(getContext(), 1, new Intent(ACTION_NOTIFICATION_TIMEOUT).setPackage(PackageManagerService.PLATFORM_PACKAGE_NAME).setData(new Uri.Builder().scheme(SCHEME_TIMEOUT).appendPath(record.getKey()).build()).addFlags(268435456).putExtra(EXTRA_KEY, record.getKey()), 134217728);
            this.mAlarmManager.setExactAndAllowWhileIdle(2, SystemClock.elapsedRealtime() + record.getNotification().getTimeoutAfter(), pi);
        }
    }

    @GuardedBy({"mNotificationLock"})
    @VisibleForTesting
    void buzzBeepBlinkLocked(NotificationRecord record) {
        boolean aboveThreshold;
        boolean buzz;
        boolean beep;
        int i;
        boolean z;
        boolean beep2;
        if (this.mIsAutomotive && !this.mNotificationEffectsEnabledForAutomotive) {
            return;
        }
        boolean blink = false;
        Notification notification = record.sbn.getNotification();
        String key = record.getKey();
        if (this.mIsAutomotive) {
            aboveThreshold = record.getImportance() > 3;
        } else {
            aboveThreshold = record.getImportance() >= 3;
        }
        boolean wasBeep = key != null && key.equals(this.mSoundNotificationKey);
        boolean wasBuzz = key != null && key.equals(this.mVibrateNotificationKey);
        boolean hasValidVibrate = false;
        boolean hasValidSound = false;
        boolean sentAccessibilityEvent = false;
        if (!record.isUpdate && record.getImportance() > 1) {
            sendAccessibilityEvent(notification, record.sbn.getPackageName());
            sentAccessibilityEvent = true;
        }
        if (!aboveThreshold || !isNotificationForCurrentUser(record)) {
            buzz = false;
            beep = false;
            i = 4;
        } else if (!this.mSystemReady || this.mAudioManager == null) {
            buzz = false;
            beep = false;
            i = 4;
        } else {
            Uri soundUri = record.getSound();
            hasValidSound = (soundUri == null || Uri.EMPTY.equals(soundUri)) ? false : true;
            long[] vibration = record.getVibration();
            if (vibration == null && hasValidSound) {
                buzz = false;
                beep = false;
                if (this.mAudioManager.getRingerModeInternal() == 1 && this.mAudioManager.getStreamVolume(AudioAttributes.toLegacyStreamType(record.getAudioAttributes())) == 0) {
                    vibration = this.mFallbackVibrationPattern;
                }
            } else {
                buzz = false;
                beep = false;
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
                if (!hasValidSound) {
                    beep2 = beep;
                } else {
                    if (isInCall()) {
                        playInCallNotification();
                        beep2 = true;
                    } else {
                        beep2 = playSound(record, soundUri);
                    }
                    if (beep2) {
                        this.mSoundNotificationKey = key;
                    }
                }
                boolean ringerModeSilent = this.mAudioManager.getRingerModeInternal() == 0;
                if (!isInCall() && hasValidVibrate && !ringerModeSilent && (buzz = playVibration(record, vibration, hasValidSound))) {
                    this.mVibrateNotificationKey = key;
                }
                boolean ringerModeSilent2 = buzz;
                buzz = ringerModeSilent2;
                beep = beep2;
                i = 4;
            } else {
                i = 4;
                if ((record.getFlags() & 4) != 0) {
                    hasValidSound = false;
                }
            }
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
        if (buzz || beep || blink) {
            if (record.sbn.isGroup() && record.sbn.getNotification().isGroupSummary()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: " + record.getKey() + " is not interruptive: summary");
                }
            } else if (record.canBubble()) {
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: " + record.getKey() + " is not interruptive: bubble");
                }
            } else {
                record.setInterruptive(true);
                if (DEBUG_INTERRUPTIVENESS) {
                    Slog.v(TAG, "INTERRUPTIVENESS: " + record.getKey() + " is interruptive: alerted");
                }
            }
            z = true;
            LogMaker type = record.getLogMaker().setCategory(199).setType(1);
            int i2 = (buzz ? 1 : 0) | (beep ? 2 : 0);
            if (!blink) {
                i = 0;
            }
            MetricsLogger.action(type.setSubtype(i | i2));
            EventLogTags.writeNotificationAlert(key, buzz ? 1 : 0, beep ? 1 : 0, blink ? 1 : 0);
        } else {
            z = true;
        }
        if (!buzz && !beep) {
            z = false;
        }
        record.setAudiblyAlerted(z);
    }

    @GuardedBy({"mNotificationLock"})
    boolean canShowLightsLocked(NotificationRecord record, boolean aboveThreshold) {
        if (this.mHasLight && this.mNotificationPulseEnabled && record.getLight() != null && aboveThreshold && (record.getSuppressedVisualEffects() & 8) == 0) {
            Notification notification = record.getNotification();
            if (!record.isUpdate || (notification.flags & 8) == 0) {
                return ((record.sbn.isGroup() && record.getNotification().suppressAlertingDueToGrouping()) || isInCall() || this.mScreenOn) ? false : true;
            }
            return false;
        }
        return false;
    }

    @GuardedBy({"mNotificationLock"})
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
                new Thread(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$j4BPSChjnlmaf86eJ3K-gjNHWGc
                    @Override // java.lang.Runnable
                    public final void run() {
                        NotificationManagerService.this.lambda$playVibration$2$NotificationManagerService(record, effect);
                    }
                }).start();
            } else {
                this.mVibrator.vibrate(record.sbn.getUid(), record.sbn.getPackageName(), effect, "Notification", record.getAudioAttributes());
            }
            return true;
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Error creating vibration waveform with pattern: " + Arrays.toString(vibration));
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public /* synthetic */ void lambda$playVibration$2$NotificationManagerService(NotificationRecord record, VibrationEffect effect) {
        int waitMs = this.mAudioManager.getFocusRampTimeMs(3, record.getAudioAttributes());
        if (DBG) {
            Slog.v(TAG, "Delaying vibration by " + waitMs + "ms");
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
        }
        synchronized (this.mNotificationLock) {
            if (this.mNotificationsByKey.get(record.getKey()) != null) {
                this.mVibrator.vibrate(record.sbn.getUid(), record.sbn.getOpPkg(), effect, "Notification (delayed)", record.getAudioAttributes());
            } else {
                Slog.e(TAG, "No vibration for canceled notification : " + record.getKey());
            }
        }
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

    /* JADX WARN: Type inference failed for: r0v5, types: [com.android.server.notification.NotificationManagerService$12] */
    protected void playInCallNotification() {
        if (this.mAudioManager.getRingerModeInternal() == 2 && Settings.Secure.getInt(getContext().getContentResolver(), "in_call_notification_enabled", 1) != 0) {
            new Thread() { // from class: com.android.server.notification.NotificationManagerService.12
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    long identity = Binder.clearCallingIdentity();
                    try {
                        IRingtonePlayer player = NotificationManagerService.this.mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            if (NotificationManagerService.this.mCallNotificationToken != null) {
                                player.stop(NotificationManagerService.this.mCallNotificationToken);
                            }
                            NotificationManagerService.this.mCallNotificationToken = new Binder();
                            player.play(NotificationManagerService.this.mCallNotificationToken, NotificationManagerService.this.mInCallNotificationUri, NotificationManagerService.this.mInCallNotificationAudioAttributes, NotificationManagerService.this.mInCallNotificationVolume, false);
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
    }

    @GuardedBy({"mToastQueue"})
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

    void showNextToastLocked(ArrayList<ToastRecord> mScreenToastQueue) {
        ToastRecord record = mScreenToastQueue.get(0);
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
                int index = mScreenToastQueue.indexOf(record);
                if (index >= 0) {
                    mScreenToastQueue.remove(index);
                }
                keepProcessAliveIfNeededLocked(record.pid);
                if (mScreenToastQueue.size() > 0) {
                    ToastRecord record2 = mScreenToastQueue.get(0);
                    record = record2;
                } else {
                    record = null;
                }
            }
        }
    }

    @GuardedBy({"mToastQueue"})
    void cancelToastLocked(int index) {
        ToastRecord record = this.mToastQueue.get(index);
        try {
            record.callback.hide();
        } catch (RemoteException e) {
            Slog.w(TAG, "Object died trying to hide notification " + record.callback + " in package " + record.pkg);
        }
        ToastRecord lastToast = this.mToastQueue.remove(index);
        this.mWindowManagerInternal.removeWindowToken(lastToast.token, false, lastToast.displayId);
        scheduleKillTokenTimeout(lastToast);
        keepProcessAliveIfNeededLocked(record.pid);
        if (this.mToastQueue.size() > 0) {
            showNextToastLocked();
        }
    }

    void finishTokenLocked(IBinder t, int displayId) {
        this.mHandler.removeCallbacksAndMessages(t);
        this.mWindowManagerInternal.removeWindowToken(t, true, displayId);
    }

    @GuardedBy({"mToastQueue"})
    private void scheduleDurationReachedLocked(ToastRecord r) {
        this.mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(this.mHandler, 2, r);
        int delay = (int) xpWindowManager.getToastDurationMillis(r.duration);
        this.mHandler.sendMessageDelayed(m, this.mAccessibilityManager.getRecommendedTimeoutMillis(delay, 2));
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

    @GuardedBy({"mToastQueue"})
    private void scheduleKillTokenTimeout(ToastRecord r) {
        this.mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(this.mHandler, 7, r);
        this.mHandler.sendMessageDelayed(m, 11000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleKillTokenTimeout(ToastRecord record) {
        if (DBG) {
            Slog.d(TAG, "Kill Token Timeout token=" + record.token);
        }
        synchronized (this.mToastQueue) {
            finishTokenLocked(record.token, record.displayId);
        }
    }

    @GuardedBy({"mToastQueue"})
    int indexOfToastLocked(String pkg, ITransientNotification callback) {
        IBinder cbak = callback.asBinder();
        ArrayList<ToastRecord> list = this.mToastQueue;
        int len = list.size();
        for (int i = 0; i < len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.callback.asBinder() == cbak) {
                return i;
            }
        }
        return -1;
    }

    int indexOfToastLocked(String pkg, ITransientNotification callback, ArrayList<ToastRecord> mScreenToastQueue) {
        IBinder cbak = callback.asBinder();
        int len = mScreenToastQueue.size();
        for (int i = 0; i < len; i++) {
            ToastRecord r = mScreenToastQueue.get(i);
            if (r.pkg.equals(pkg) && r.callback.asBinder() == cbak) {
                return i;
            }
        }
        return -1;
    }

    @GuardedBy({"mToastQueue"})
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

    int indexOfToastPackageLocked(String pkg, ArrayList<ToastRecord> mScreenToastQueue) {
        int len = mScreenToastQueue.size();
        for (int i = 0; i < len; i++) {
            ToastRecord r = mScreenToastQueue.get(i);
            if (r.pkg.equals(pkg)) {
                return i;
            }
        }
        return -1;
    }

    @GuardedBy({"mToastQueue"})
    void keepProcessAliveIfNeededLocked(int pid) {
        int toastCount = 0;
        ArrayList<ToastRecord> list = this.mToastQueue;
        int N = list.size();
        for (int i = 0; i < N; i++) {
            ToastRecord r = list.get(i);
            if (r.pid == pid) {
                toastCount++;
            }
        }
        try {
            this.mAm.setProcessImportant(this.mForegroundToken, pid, toastCount > 0, "toast");
        } catch (RemoteException e) {
        }
    }

    void keepProcessAliveIfNeededLocked(int pid, ArrayList<ToastRecord> mScreenToastQueue) {
        int toastCount = 0;
        int N = mScreenToastQueue.size();
        for (int i = 0; i < N; i++) {
            ToastRecord r = mScreenToastQueue.get(i);
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
    /* JADX WARN: Removed duplicated region for block: B:47:0x0098  */
    /* JADX WARN: Removed duplicated region for block: B:58:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void handleRankingReconsideration(android.os.Message r16) {
        /*
            r15 = this;
            r1 = r15
            r2 = r16
            java.lang.Object r0 = r2.obj
            boolean r0 = r0 instanceof com.android.server.notification.RankingReconsideration
            if (r0 != 0) goto La
            return
        La:
            java.lang.Object r0 = r2.obj
            r3 = r0
            com.android.server.notification.RankingReconsideration r3 = (com.android.server.notification.RankingReconsideration) r3
            r3.run()
            java.lang.Object r4 = r1.mNotificationLock
            monitor-enter(r4)
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r0 = r1.mNotificationsByKey     // Catch: java.lang.Throwable -> L9e
            java.lang.String r5 = r3.getKey()     // Catch: java.lang.Throwable -> L9e
            java.lang.Object r0 = r0.get(r5)     // Catch: java.lang.Throwable -> L9e
            com.android.server.notification.NotificationRecord r0 = (com.android.server.notification.NotificationRecord) r0     // Catch: java.lang.Throwable -> L9e
            if (r0 != 0) goto L29
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L25
            return
        L25:
            r0 = move-exception
            r11 = r3
            goto La0
        L29:
            int r5 = r15.findNotificationRecordIndexLocked(r0)     // Catch: java.lang.Throwable -> L9e
            boolean r6 = r0.isIntercepted()     // Catch: java.lang.Throwable -> L9e
            int r7 = r0.getPackageVisibilityOverride()     // Catch: java.lang.Throwable -> L9e
            boolean r8 = r0.isInterruptive()     // Catch: java.lang.Throwable -> L9e
            r3.applyChangesLocked(r0)     // Catch: java.lang.Throwable -> L9e
            r15.applyZenModeLocked(r0)     // Catch: java.lang.Throwable -> L9e
            com.android.server.notification.RankingHelper r9 = r1.mRankingHelper     // Catch: java.lang.Throwable -> L9e
            java.util.ArrayList<com.android.server.notification.NotificationRecord> r10 = r1.mNotificationList     // Catch: java.lang.Throwable -> L9e
            r9.sort(r10)     // Catch: java.lang.Throwable -> L9e
            int r9 = r15.findNotificationRecordIndexLocked(r0)     // Catch: java.lang.Throwable -> L9e
            r10 = 1
            r11 = 0
            if (r5 == r9) goto L50
            r9 = r10
            goto L51
        L50:
            r9 = r11
        L51:
            boolean r12 = r0.isIntercepted()     // Catch: java.lang.Throwable -> L9e
            if (r6 == r12) goto L59
            r12 = r10
            goto L5a
        L59:
            r12 = r11
        L5a:
            int r13 = r0.getPackageVisibilityOverride()     // Catch: java.lang.Throwable -> L9e
            if (r7 == r13) goto L62
            r13 = r10
            goto L63
        L62:
            r13 = r11
        L63:
            boolean r14 = r0.canBubble()     // Catch: java.lang.Throwable -> L9e
            if (r14 == 0) goto L72
            boolean r14 = r0.isInterruptive()     // Catch: java.lang.Throwable -> L25
            if (r8 == r14) goto L72
            r14 = r10
            goto L73
        L72:
            r14 = r11
        L73:
            if (r9 != 0) goto L7d
            if (r12 != 0) goto L7d
            if (r13 != 0) goto L7d
            if (r14 == 0) goto L7c
            goto L7d
        L7c:
            r10 = r11
        L7d:
            if (r6 == 0) goto L94
            boolean r11 = r0.isIntercepted()     // Catch: java.lang.Throwable -> L9e
            if (r11 != 0) goto L94
            r11 = r3
            long r2 = java.lang.System.currentTimeMillis()     // Catch: java.lang.Throwable -> La2
            boolean r2 = r0.isNewEnoughForAlerting(r2)     // Catch: java.lang.Throwable -> La2
            if (r2 == 0) goto L95
            r15.buzzBeepBlinkLocked(r0)     // Catch: java.lang.Throwable -> La2
            goto L95
        L94:
            r11 = r3
        L95:
            monitor-exit(r4)     // Catch: java.lang.Throwable -> La2
            if (r10 == 0) goto L9d
            com.android.server.notification.NotificationManagerService$WorkerHandler r0 = r1.mHandler
            r0.scheduleSendRankingUpdate()
        L9d:
            return
        L9e:
            r0 = move-exception
            r11 = r3
        La0:
            monitor-exit(r4)     // Catch: java.lang.Throwable -> La2
            throw r0
        La2:
            r0 = move-exception
            goto La0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.handleRankingReconsideration(android.os.Message):void");
    }

    void handleRankingSort() {
        NotificationManagerService notificationManagerService = this;
        if (notificationManagerService.mRankingHelper == null) {
            return;
        }
        synchronized (notificationManagerService.mNotificationLock) {
            try {
                int N = notificationManagerService.mNotificationList.size();
                ArrayList<String> orderBefore = new ArrayList<>(N);
                int[] visibilities = new int[N];
                boolean[] showBadges = new boolean[N];
                boolean[] allowBubbles = new boolean[N];
                ArrayList<NotificationChannel> channelBefore = new ArrayList<>(N);
                ArrayList<String> groupKeyBefore = new ArrayList<>(N);
                ArrayList<ArrayList<String>> overridePeopleBefore = new ArrayList<>(N);
                ArrayList<ArrayList<SnoozeCriterion>> snoozeCriteriaBefore = new ArrayList<>(N);
                ArrayList<Integer> userSentimentBefore = new ArrayList<>(N);
                ArrayList<Integer> suppressVisuallyBefore = new ArrayList<>(N);
                ArrayList<ArrayList<Notification.Action>> systemSmartActionsBefore = new ArrayList<>(N);
                ArrayList<ArrayList<CharSequence>> smartRepliesBefore = new ArrayList<>(N);
                int[] importancesBefore = new int[N];
                int i = 0;
                while (i < N) {
                    int N2 = N;
                    NotificationRecord r = notificationManagerService.mNotificationList.get(i);
                    try {
                        orderBefore.add(r.getKey());
                        visibilities[i] = r.getPackageVisibilityOverride();
                        showBadges[i] = r.canShowBadge();
                        allowBubbles[i] = r.canBubble();
                        channelBefore.add(r.getChannel());
                        groupKeyBefore.add(r.getGroupKey());
                        overridePeopleBefore.add(r.getPeopleOverride());
                        snoozeCriteriaBefore.add(r.getSnoozeCriteria());
                        userSentimentBefore.add(Integer.valueOf(r.getUserSentiment()));
                        suppressVisuallyBefore.add(Integer.valueOf(r.getSuppressedVisualEffects()));
                        systemSmartActionsBefore.add(r.getSystemGeneratedSmartActions());
                        smartRepliesBefore.add(r.getSmartReplies());
                        importancesBefore[i] = r.getImportance();
                        notificationManagerService = this;
                        ArrayList<ArrayList<CharSequence>> smartRepliesBefore2 = smartRepliesBefore;
                        notificationManagerService.mRankingHelper.extractSignals(r);
                        i++;
                        N = N2;
                        smartRepliesBefore = smartRepliesBefore2;
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                }
                int N3 = N;
                ArrayList<ArrayList<CharSequence>> smartRepliesBefore3 = smartRepliesBefore;
                notificationManagerService.mRankingHelper.sort(notificationManagerService.mNotificationList);
                int i2 = 0;
                while (true) {
                    int N4 = N3;
                    if (i2 < N4) {
                        NotificationRecord r2 = notificationManagerService.mNotificationList.get(i2);
                        ArrayList<String> orderBefore2 = orderBefore;
                        N3 = N4;
                        if (!orderBefore.get(i2).equals(r2.getKey()) || visibilities[i2] != r2.getPackageVisibilityOverride() || showBadges[i2] != r2.canShowBadge() || allowBubbles[i2] != r2.canBubble() || !Objects.equals(channelBefore.get(i2), r2.getChannel()) || !Objects.equals(groupKeyBefore.get(i2), r2.getGroupKey()) || !Objects.equals(overridePeopleBefore.get(i2), r2.getPeopleOverride()) || !Objects.equals(snoozeCriteriaBefore.get(i2), r2.getSnoozeCriteria()) || !Objects.equals(userSentimentBefore.get(i2), Integer.valueOf(r2.getUserSentiment())) || !Objects.equals(suppressVisuallyBefore.get(i2), Integer.valueOf(r2.getSuppressedVisualEffects())) || !Objects.equals(systemSmartActionsBefore.get(i2), r2.getSystemGeneratedSmartActions())) {
                            break;
                        }
                        ArrayList<ArrayList<CharSequence>> smartRepliesBefore4 = smartRepliesBefore3;
                        smartRepliesBefore3 = smartRepliesBefore4;
                        if (!Objects.equals(smartRepliesBefore4.get(i2), r2.getSmartReplies()) || importancesBefore[i2] != r2.getImportance()) {
                            break;
                        }
                        i2++;
                        orderBefore = orderBefore2;
                    } else {
                        return;
                    }
                }
                notificationManagerService.mHandler.scheduleSendRankingUpdate();
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    @GuardedBy({"mNotificationLock"})
    private void recordCallerLocked(NotificationRecord record) {
        if (this.mZenModeHelper.isCall(record)) {
            this.mZenModeHelper.recordCaller(record);
        }
    }

    @GuardedBy({"mNotificationLock"})
    private void applyZenModeLocked(NotificationRecord record) {
        record.setIntercepted(this.mZenModeHelper.shouldIntercept(record));
        if (record.isIntercepted()) {
            record.setSuppressedVisualEffects(this.mZenModeHelper.getConsolidatedNotificationPolicy().suppressedVisualEffects);
        } else {
            record.setSuppressedVisualEffects(0);
        }
    }

    @GuardedBy({"mNotificationLock"})
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

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnPackageChanged(boolean removingPackage, int changeUserId, String[] pkgList, int[] uidList) {
        this.mListeners.onPackagesChanged(removingPackage, pkgList, uidList);
        this.mAssistants.onPackagesChanged(removingPackage, pkgList, uidList);
        this.mConditionProviders.onPackagesChanged(removingPackage, pkgList, uidList);
        boolean preferencesChanged = removingPackage | this.mPreferencesHelper.onPackagesChanged(removingPackage, changeUserId, pkgList, uidList);
        if (preferencesChanged) {
            handleSavePolicyFile();
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
                default:
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
                    NotificationManagerService.this.handleKillTokenTimeout((ToastRecord) msg.obj);
                    return;
                case 8:
                    SomeArgs args = (SomeArgs) msg.obj;
                    NotificationManagerService.this.handleOnPackageChanged(((Boolean) args.arg1).booleanValue(), args.argi1, (String[]) args.arg2, (int[]) args.arg3);
                    args.recycle();
                    return;
            }
        }

        protected void scheduleSendRankingUpdate() {
            if (!hasMessages(4)) {
                Message m = Message.obtain(this, 4);
                sendMessage(m);
            }
        }

        protected void scheduleCancelNotification(CancelNotificationRunnable cancelRunnable) {
            if (!hasCallbacks(cancelRunnable)) {
                sendMessage(Message.obtain(this, cancelRunnable));
            }
        }

        protected void scheduleOnPackageChanged(boolean removingPackage, int changeUserId, String[] pkgList, int[] uidList) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = Boolean.valueOf(removingPackage);
            args.argi1 = changeUserId;
            args.arg2 = pkgList;
            args.arg3 = uidList;
            sendMessage(Message.obtain(this, 8, args));
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
            int i = msg.what;
            if (i == 1000) {
                NotificationManagerService.this.handleRankingReconsideration(msg);
            } else if (i == 1001) {
                NotificationManagerService.this.handleRankingSort();
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
    @GuardedBy({"mNotificationLock"})
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
    @GuardedBy({"mNotificationLock"})
    public void cancelNotificationLocked(NotificationRecord r, boolean sendDelete, int reason, boolean wasPosted, String listenerName) {
        cancelNotificationLocked(r, sendDelete, reason, -1, -1, wasPosted, listenerName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
    public void cancelNotificationLocked(final NotificationRecord r, boolean sendDelete, int reason, int rank, int count, boolean wasPosted, String listenerName) {
        String groupKey;
        NotificationRecord groupSummary;
        ArrayMap<String, String> summaries;
        LogMaker logMaker;
        long identity;
        PendingIntent deleteIntent;
        String canceledKey = r.getKey();
        recordCallerLocked(r);
        if (r.getStats().getDismissalSurface() == -1) {
            r.recordDismissalSurface(0);
        }
        if (sendDelete && (deleteIntent = r.getNotification().deleteIntent) != null) {
            try {
                ((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).clearPendingIntentAllowBgActivityStarts(deleteIntent.getTarget(), WHITELIST_TOKEN);
                deleteIntent.send();
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
        if (reason != 2 && reason != 3) {
            switch (reason) {
                case 8:
                case 9:
                    this.mUsageStats.registerRemovedByApp(r);
                    break;
            }
            groupKey = r.getGroupKey();
            groupSummary = this.mSummaryByGroupKey.get(groupKey);
            if (groupSummary != null && groupSummary.getKey().equals(canceledKey)) {
                this.mSummaryByGroupKey.remove(groupKey);
            }
            summaries = this.mAutobundledSummaries.get(Integer.valueOf(r.sbn.getUserId()));
            if (summaries != null && r.sbn.getKey().equals(summaries.get(r.sbn.getPackageName()))) {
                summaries.remove(r.sbn.getPackageName());
            }
            this.mArchive.record(r.sbn);
            long now = System.currentTimeMillis();
            logMaker = r.getItemLogMaker().setType(5).setSubtype(reason);
            if (rank != -1 && count != -1) {
                logMaker.addTaggedData(798, Integer.valueOf(rank)).addTaggedData(1395, Integer.valueOf(count));
            }
            MetricsLogger.action(logMaker);
            EventLogTags.writeNotificationCanceled(canceledKey, reason, r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now), rank, count, listenerName);
        }
        this.mUsageStats.registerDismissedByUser(r);
        groupKey = r.getGroupKey();
        groupSummary = this.mSummaryByGroupKey.get(groupKey);
        if (groupSummary != null) {
            this.mSummaryByGroupKey.remove(groupKey);
        }
        summaries = this.mAutobundledSummaries.get(Integer.valueOf(r.sbn.getUserId()));
        if (summaries != null) {
            summaries.remove(r.sbn.getPackageName());
        }
        this.mArchive.record(r.sbn);
        long now2 = System.currentTimeMillis();
        logMaker = r.getItemLogMaker().setType(5).setSubtype(reason);
        if (rank != -1) {
            logMaker.addTaggedData(798, Integer.valueOf(rank)).addTaggedData(1395, Integer.valueOf(count));
        }
        MetricsLogger.action(logMaker);
        EventLogTags.writeNotificationCanceled(canceledKey, reason, r.getLifespanMs(now2), r.getFreshnessMs(now2), r.getExposureMs(now2), rank, count, listenerName);
    }

    @VisibleForTesting
    void updateUriPermissions(NotificationRecord newRecord, NotificationRecord oldRecord, String targetPkg, int targetUserId) {
        IBinder permissionOwner;
        String key = newRecord != null ? newRecord.getKey() : oldRecord.getKey();
        if (DBG) {
            Slog.d(TAG, key + ": updating permissions");
        }
        ArraySet<Uri> newUris = newRecord != null ? newRecord.getGrantableUris() : null;
        ArraySet<Uri> oldUris = oldRecord != null ? oldRecord.getGrantableUris() : null;
        if (newUris == null && oldUris == null) {
            return;
        }
        IBinder permissionOwner2 = null;
        if (newRecord != null && 0 == 0) {
            permissionOwner2 = newRecord.permissionOwner;
        }
        if (oldRecord != null && permissionOwner2 == null) {
            permissionOwner2 = oldRecord.permissionOwner;
        }
        if (newUris != null && permissionOwner2 == null) {
            if (DBG) {
                Slog.d(TAG, key + ": creating owner");
            }
            permissionOwner2 = this.mUgmInternal.newUriPermissionOwner("NOTIF:" + key);
        }
        if (newUris == null && permissionOwner2 != null) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (DBG) {
                    Slog.d(TAG, key + ": destroying owner");
                }
                this.mUgmInternal.revokeUriPermissionFromOwner(permissionOwner2, null, -1, UserHandle.getUserId(oldRecord.getUid()));
                permissionOwner = null;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        } else {
            permissionOwner = permissionOwner2;
        }
        if (newUris != null && permissionOwner != null) {
            for (int i = 0; i < newUris.size(); i++) {
                Uri uri = newUris.valueAt(i);
                if (oldUris == null || !oldUris.contains(uri)) {
                    if (DBG) {
                        Slog.d(TAG, key + ": granting " + uri);
                    }
                    grantUriPermission(permissionOwner, uri, newRecord.getUid(), targetPkg, targetUserId);
                }
            }
        }
        if (oldUris != null && permissionOwner != null) {
            for (int i2 = 0; i2 < oldUris.size(); i2++) {
                Uri uri2 = oldUris.valueAt(i2);
                if (newUris == null || !newUris.contains(uri2)) {
                    if (DBG) {
                        Slog.d(TAG, key + ": revoking " + uri2);
                    }
                    revokeUriPermission(permissionOwner, uri2, oldRecord.getUid());
                }
            }
        }
        if (newRecord != null) {
            newRecord.permissionOwner = permissionOwner;
        }
    }

    private void grantUriPermission(IBinder owner, Uri uri, int sourceUid, String targetPkg, int targetUserId) {
        if (uri == null || !ActivityTaskManagerInternal.ASSIST_KEY_CONTENT.equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mUgm.grantUriPermissionFromOwner(owner, sourceUid, targetPkg, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)), targetUserId);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    private void revokeUriPermission(IBinder owner, Uri uri, int sourceUid) {
        if (uri == null || !ActivityTaskManagerInternal.ASSIST_KEY_CONTENT.equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mUgmInternal.revokeUriPermissionFromOwner(owner, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void cancelNotification(int callingUid, int callingPid, String pkg, String tag, int id, int mustHaveFlags, int mustNotHaveFlags, boolean sendDelete, int userId, int reason, ManagedServices.ManagedServiceInfo listener) {
        cancelNotification(callingUid, callingPid, pkg, tag, id, mustHaveFlags, mustNotHaveFlags, sendDelete, userId, reason, -1, -1, listener);
    }

    void cancelNotification(int callingUid, int callingPid, String pkg, String tag, int id, int mustHaveFlags, int mustNotHaveFlags, boolean sendDelete, int userId, int reason, int rank, int count, ManagedServices.ManagedServiceInfo listener) {
        this.mHandler.scheduleCancelNotification(new CancelNotificationRunnable(callingUid, callingPid, pkg, tag, id, mustHaveFlags, mustNotHaveFlags, sendDelete, userId, reason, rank, count, listener));
    }

    private boolean notificationMatchesUserId(NotificationRecord r, int userId) {
        return userId == -1 || r.getUserId() == -1 || r.getUserId() == userId;
    }

    private boolean notificationMatchesCurrentProfiles(NotificationRecord r, int userId) {
        return notificationMatchesUserId(r, userId) || this.mUserProfiles.isCurrentProfile(r.getUserId());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.notification.NotificationManagerService$14  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass14 implements Runnable {
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

        AnonymousClass14(ManagedServices.ManagedServiceInfo managedServiceInfo, int i, int i2, String str, int i3, int i4, int i5, int i6, boolean z, String str2) {
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
            ManagedServices.ManagedServiceInfo managedServiceInfo = this.val$listener;
            String listenerName = managedServiceInfo == null ? null : managedServiceInfo.component.toShortString();
            EventLogTags.writeNotificationCancelAll(this.val$callingUid, this.val$callingPid, this.val$pkg, this.val$userId, this.val$mustHaveFlags, this.val$mustNotHaveFlags, this.val$reason, listenerName);
            if (!this.val$doit) {
                return;
            }
            synchronized (NotificationManagerService.this.mNotificationLock) {
                final int i = this.val$mustHaveFlags;
                final int i2 = this.val$mustNotHaveFlags;
                FlagChecker flagChecker = new FlagChecker() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$14$hWnH6mjUAxwVmpU3QRoPHh5_FyI
                    @Override // com.android.server.notification.NotificationManagerService.FlagChecker
                    public final boolean apply(int i3) {
                        return NotificationManagerService.AnonymousClass14.lambda$run$0(i, i2, i3);
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
        this.mHandler.post(new AnonymousClass14(listener, callingUid, callingPid, pkg, userId, mustHaveFlags, mustNotHaveFlags, reason, doit, channelId));
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:29:0x006f  */
    @com.android.internal.annotations.GuardedBy({"mNotificationLock"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void cancelAllNotificationsByListLocked(java.util.ArrayList<com.android.server.notification.NotificationRecord> r17, int r18, int r19, java.lang.String r20, boolean r21, java.lang.String r22, com.android.server.notification.NotificationManagerService.FlagChecker r23, boolean r24, int r25, boolean r26, int r27, java.lang.String r28, boolean r29) {
        /*
            r16 = this;
            r7 = r16
            r8 = r17
            r9 = r20
            r10 = r22
            r11 = r25
            r0 = 0
            int r1 = r17.size()
            r6 = 1
            int r1 = r1 - r6
            r13 = r0
            r12 = r1
        L13:
            if (r12 < 0) goto L99
            java.lang.Object r0 = r8.get(r12)
            r14 = r0
            com.android.server.notification.NotificationRecord r14 = (com.android.server.notification.NotificationRecord) r14
            if (r24 == 0) goto L28
            boolean r0 = r7.notificationMatchesCurrentProfiles(r14, r11)
            if (r0 != 0) goto L32
            r15 = r23
            goto L95
        L28:
            boolean r0 = r7.notificationMatchesUserId(r14, r11)
            if (r0 != 0) goto L32
            r15 = r23
            goto L95
        L32:
            if (r21 == 0) goto L40
            if (r9 != 0) goto L40
            int r0 = r14.getUserId()
            r1 = -1
            if (r0 != r1) goto L40
            r15 = r23
            goto L95
        L40:
            int r0 = r14.getFlags()
            r15 = r23
            boolean r0 = r15.apply(r0)
            if (r0 != 0) goto L4d
            goto L95
        L4d:
            if (r9 == 0) goto L5c
            android.service.notification.StatusBarNotification r0 = r14.sbn
            java.lang.String r0 = r0.getPackageName()
            boolean r0 = r0.equals(r9)
            if (r0 != 0) goto L5c
            goto L95
        L5c:
            if (r10 == 0) goto L6d
            android.app.NotificationChannel r0 = r14.getChannel()
            java.lang.String r0 = r0.getId()
            boolean r0 = r10.equals(r0)
            if (r0 != 0) goto L6d
            goto L95
        L6d:
            if (r13 != 0) goto L75
            java.util.ArrayList r0 = new java.util.ArrayList
            r0.<init>()
            r13 = r0
        L75:
            r8.remove(r12)
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r0 = r7.mNotificationsByKey
            java.lang.String r1 = r14.getKey()
            r0.remove(r1)
            r14.recordDismissalSentiment(r6)
            r13.add(r14)
            r0 = r16
            r1 = r14
            r2 = r26
            r3 = r27
            r4 = r29
            r5 = r28
            r0.cancelNotificationLocked(r1, r2, r3, r4, r5)
        L95:
            int r12 = r12 + (-1)
            goto L13
        L99:
            r15 = r23
            if (r13 == 0) goto Lc0
            int r12 = r13.size()
            r0 = 0
            r14 = r0
        La3:
            if (r14 >= r12) goto Lbd
            java.lang.Object r0 = r13.get(r14)
            r1 = r0
            com.android.server.notification.NotificationRecord r1 = (com.android.server.notification.NotificationRecord) r1
            r5 = 0
            r0 = r16
            r2 = r18
            r3 = r19
            r4 = r28
            r6 = r23
            r0.cancelGroupChildrenLocked(r1, r2, r3, r4, r5, r6)
            int r14 = r14 + 1
            goto La3
        Lbd:
            r16.updateLightsLocked()
        Lc0:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.cancelAllNotificationsByListLocked(java.util.ArrayList, int, int, java.lang.String, boolean, java.lang.String, com.android.server.notification.NotificationManagerService$FlagChecker, boolean, int, boolean, int, java.lang.String, boolean):void");
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
        handleSavePolicyFile();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.notification.NotificationManagerService$15  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass15 implements Runnable {
        final /* synthetic */ int val$callingPid;
        final /* synthetic */ int val$callingUid;
        final /* synthetic */ boolean val$includeCurrentProfiles;
        final /* synthetic */ ManagedServices.ManagedServiceInfo val$listener;
        final /* synthetic */ int val$reason;
        final /* synthetic */ int val$userId;

        AnonymousClass15(ManagedServices.ManagedServiceInfo managedServiceInfo, int i, int i2, int i3, int i4, boolean z) {
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
                final int i = this.val$reason;
                FlagChecker flagChecker = new FlagChecker() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$15$U436K_bi4RF3tuE3ATVdL4kLpsQ
                    @Override // com.android.server.notification.NotificationManagerService.FlagChecker
                    public final boolean apply(int i2) {
                        return NotificationManagerService.AnonymousClass15.lambda$run$0(i, i2);
                    }
                };
                NotificationManagerService.this.cancelAllNotificationsByListLocked(NotificationManagerService.this.mNotificationList, this.val$callingUid, this.val$callingPid, null, false, null, flagChecker, this.val$includeCurrentProfiles, this.val$userId, true, this.val$reason, listenerName, true);
                NotificationManagerService.this.cancelAllNotificationsByListLocked(NotificationManagerService.this.mEnqueuedNotifications, this.val$callingUid, this.val$callingPid, null, false, null, flagChecker, this.val$includeCurrentProfiles, this.val$userId, true, this.val$reason, listenerName, false);
                NotificationManagerService.this.mSnoozeHelper.cancel(this.val$userId, this.val$includeCurrentProfiles);
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$run$0(int reason, int flags) {
            int flagsToCheck = 34;
            if (11 == reason) {
                flagsToCheck = 34 | 4096;
            }
            if ((flags & flagsToCheck) != 0) {
                return false;
            }
            return true;
        }
    }

    @GuardedBy({"mNotificationLock"})
    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason, ManagedServices.ManagedServiceInfo listener, boolean includeCurrentProfiles) {
        this.mHandler.post(new AnonymousClass15(listener, callingUid, callingPid, userId, reason, includeCurrentProfiles));
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mNotificationLock"})
    public void cancelGroupChildrenLocked(NotificationRecord r, int callingUid, int callingPid, String listenerName, boolean sendDelete, FlagChecker flagChecker) {
        Notification n = r.getNotification();
        if (!n.isGroupSummary()) {
            return;
        }
        String pkg = r.sbn.getPackageName();
        if (pkg == null) {
            if (DBG) {
                Slog.e(TAG, "No package for group summary: " + r.getKey());
                return;
            }
            return;
        }
        cancelGroupChildrenByListLocked(this.mNotificationList, r, callingUid, callingPid, listenerName, sendDelete, true, flagChecker);
        cancelGroupChildrenByListLocked(this.mEnqueuedNotifications, r, callingUid, callingPid, listenerName, sendDelete, false, flagChecker);
    }

    @GuardedBy({"mNotificationLock"})
    private void cancelGroupChildrenByListLocked(ArrayList<NotificationRecord> notificationList, NotificationRecord parentNotification, int callingUid, int callingPid, String listenerName, boolean sendDelete, boolean wasPosted, FlagChecker flagChecker) {
        int i;
        FlagChecker flagChecker2 = flagChecker;
        String pkg = parentNotification.sbn.getPackageName();
        int userId = parentNotification.getUserId();
        int i2 = notificationList.size() - 1;
        while (i2 >= 0) {
            NotificationRecord childR = notificationList.get(i2);
            StatusBarNotification childSbn = childR.sbn;
            if (!childSbn.isGroup() || childSbn.getNotification().isGroupSummary()) {
                i = i2;
            } else if (!childR.getGroupKey().equals(parentNotification.getGroupKey())) {
                i = i2;
            } else if ((childR.getFlags() & 64) != 0) {
                i = i2;
            } else if (flagChecker2 == null || flagChecker2.apply(childR.getFlags())) {
                i = i2;
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, childSbn.getId(), childSbn.getTag(), userId, 0, 0, 12, listenerName);
                notificationList.remove(i);
                this.mNotificationsByKey.remove(childR.getKey());
                cancelNotificationLocked(childR, sendDelete, 12, wasPosted, listenerName);
            } else {
                i = i2;
            }
            i2 = i - 1;
            flagChecker2 = flagChecker;
        }
    }

    @GuardedBy({"mNotificationLock"})
    void updateLightsLocked() {
        NotificationRecord ledNotification = null;
        while (ledNotification == null && !this.mLights.isEmpty()) {
            ArrayList<String> arrayList = this.mLights;
            String owner = arrayList.get(arrayList.size() - 1);
            NotificationRecord ledNotification2 = this.mNotificationsByKey.get(owner);
            ledNotification = ledNotification2;
            if (ledNotification == null) {
                Slog.wtfStack(TAG, "LED Notification does not exist: " + owner);
                this.mLights.remove(owner);
            }
        }
        if (ledNotification == null || isInCall() || this.mScreenOn) {
            this.mNotificationLight.turnOff();
            return;
        }
        NotificationRecord.Light light = ledNotification.getLight();
        if (light != null && this.mNotificationPulseEnabled) {
            this.mNotificationLight.setFlashing(light.color, 1, light.onMs, light.offMs);
        }
    }

    @GuardedBy({"mNotificationLock"})
    List<NotificationRecord> findGroupNotificationsLocked(String pkg, String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        records.addAll(findGroupNotificationByListLocked(this.mNotificationList, pkg, groupKey, userId));
        records.addAll(findGroupNotificationByListLocked(this.mEnqueuedNotifications, pkg, groupKey, userId));
        return records;
    }

    @GuardedBy({"mNotificationLock"})
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
    @GuardedBy({"mNotificationLock"})
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mNotificationLock"})
    public NotificationRecord findNotificationLocked(String pkg, String tag, int id, int userId) {
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
    @GuardedBy({"mNotificationLock"})
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
    @GuardedBy({"mNotificationLock"})
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

    @GuardedBy({"mNotificationLock"})
    private NotificationRecord findNotificationByListLocked(ArrayList<NotificationRecord> list, String key) {
        int N = list.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(list.get(i).getKey())) {
                return list.get(i);
            }
        }
        return null;
    }

    @GuardedBy({"mNotificationLock"})
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
    public void checkCallerIsSystemOrSystemUiOrShell() {
        if (Binder.getCallingUid() == 2000 || isCallerSystemOrPhone()) {
            return;
        }
        getContext().enforceCallingPermission("android.permission.STATUS_BAR_SERVICE", null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkCallerIsSystemOrSameApp(String pkg) {
        if (isCallerSystemOrPhone()) {
            return;
        }
        checkCallerIsSameApp(pkg);
    }

    private boolean isCallerAndroid(String callingPkg, int uid) {
        return isUidSystemOrPhone(uid) && callingPkg != null && PackageManagerService.PLATFORM_PACKAGE_NAME.equals(callingPkg);
    }

    private void checkRestrictedCategories(Notification notification) {
        try {
            if (!this.mPackageManager.hasSystemFeature("android.hardware.type.automotive", 0)) {
                return;
            }
        } catch (RemoteException e) {
            if (DBG) {
                Slog.e(TAG, "Unable to confirm if it's safe to skip category restrictions check thus the check will be done anyway");
            }
        }
        if ("car_emergency".equals(notification.category) || "car_warning".equals(notification.category) || "car_information".equals(notification.category)) {
            checkCallerIsSystem();
        }
    }

    @VisibleForTesting
    boolean isCallerInstantApp(int callingUid, int userId) {
        if (isUidSystemOrPhone(callingUid)) {
            return false;
        }
        if (userId == -1) {
            userId = 0;
        }
        try {
            String[] pkgs = this.mPackageManager.getPackagesForUid(callingUid);
            if (pkgs == null) {
                throw new SecurityException("Unknown uid " + callingUid);
            }
            String pkg = pkgs[0];
            this.mAppOps.checkPackage(callingUid, pkg);
            ApplicationInfo ai = this.mPackageManager.getApplicationInfo(pkg, 0, userId);
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            }
            return ai.isInstantApp();
        } catch (RemoteException re) {
            throw new SecurityException("Unknown uid " + callingUid, re);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkCallerIsSameApp(String pkg) {
        checkCallerIsSameApp(pkg, Binder.getCallingUid(), UserHandle.getCallingUserId());
    }

    private void checkCallerIsSameApp(String pkg, int uid, int userId) {
        try {
            ApplicationInfo ai = this.mPackageManager.getApplicationInfo(pkg, 0, userId);
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            } else if (!UserHandle.isSameApp(ai.uid, uid)) {
                throw new SecurityException("Calling uid " + uid + " gave package " + pkg + " which is owned by uid " + ai.uid);
            }
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg + "\n" + re);
        }
    }

    private boolean isCallerSameApp(String pkg) {
        try {
            checkCallerIsSameApp(pkg);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean isCallerSameApp(String pkg, int uid, int userId) {
        try {
            checkCallerIsSameApp(pkg, uid, userId);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String callStateToString(int state) {
        if (state != 0) {
            if (state != 1) {
                if (state == 2) {
                    return "CALL_STATE_OFFHOOK";
                }
                return "CALL_STATE_UNKNOWN_" + state;
            }
            return "CALL_STATE_RINGING";
        }
        return "CALL_STATE_IDLE";
    }

    private void listenForCallState() {
        TelephonyManager.from(getContext()).listen(new PhoneStateListener() { // from class: com.android.server.notification.NotificationManagerService.16
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
    @GuardedBy({"mNotificationLock"})
    public NotificationRankingUpdate makeRankingUpdateLocked(ManagedServices.ManagedServiceInfo info) {
        NotificationManagerService notificationManagerService = this;
        int N = notificationManagerService.mNotificationList.size();
        ArrayList<NotificationListenerService.Ranking> rankings = new ArrayList<>();
        int i = 0;
        while (true) {
            boolean z = false;
            if (i < N) {
                NotificationRecord record = notificationManagerService.mNotificationList.get(i);
                if (notificationManagerService.isVisibleToListener(record.sbn, info)) {
                    String key = record.sbn.getKey();
                    NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
                    int size = rankings.size();
                    boolean z2 = !record.isIntercepted();
                    int packageVisibilityOverride = record.getPackageVisibilityOverride();
                    int suppressedVisualEffects = record.getSuppressedVisualEffects();
                    int importance = record.getImportance();
                    CharSequence importanceExplanation = record.getImportanceExplanation();
                    String overrideGroupKey = record.sbn.getOverrideGroupKey();
                    NotificationChannel channel = record.getChannel();
                    ArrayList<String> peopleOverride = record.getPeopleOverride();
                    ArrayList<SnoozeCriterion> snoozeCriteria = record.getSnoozeCriteria();
                    boolean canShowBadge = record.canShowBadge();
                    int userSentiment = record.getUserSentiment();
                    boolean isHidden = record.isHidden();
                    long lastAudiblyAlertedMs = record.getLastAudiblyAlertedMs();
                    if (record.getSound() != null || record.getVibration() != null) {
                        z = true;
                    }
                    ranking.populate(key, size, z2, packageVisibilityOverride, suppressedVisualEffects, importance, importanceExplanation, overrideGroupKey, channel, peopleOverride, snoozeCriteria, canShowBadge, userSentiment, isHidden, lastAudiblyAlertedMs, z, record.getSystemGeneratedSmartActions(), record.getSmartReplies(), record.canBubble(), record.isInterruptive());
                    rankings.add(ranking);
                }
                i++;
                notificationManagerService = this;
            } else {
                return new NotificationRankingUpdate((NotificationListenerService.Ranking[]) rankings.toArray(new NotificationListenerService.Ranking[0]));
            }
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
                } catch (SecurityException e) {
                }
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

    private boolean isPackageSuspendedForUser(String pkg, int uid) {
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
    public boolean canUseManagedServices(String pkg, Integer userId, String requiredPermission) {
        String[] stringArray;
        boolean canUseManagedServices = !this.mActivityManager.isLowRamDevice() || this.mPackageManagerClient.hasSystemFeature("android.hardware.type.watch");
        for (String whitelisted : getContext().getResources().getStringArray(17235977)) {
            if (whitelisted.equals(pkg)) {
                canUseManagedServices = true;
            }
        }
        if (requiredPermission != null) {
            try {
                if (this.mPackageManager.checkPermission(requiredPermission, pkg, userId.intValue()) != 0) {
                    return false;
                }
                return canUseManagedServices;
            } catch (RemoteException e) {
                Slog.e(TAG, "can't talk to pm", e);
                return canUseManagedServices;
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

    private boolean isInCall() {
        int audioMode;
        return this.mInCallStateOffHook || (audioMode = this.mAudioManager.getMode()) == 2 || audioMode == 3;
    }

    /* loaded from: classes.dex */
    public class NotificationAssistants extends ManagedServices {
        private static final String ATT_TYPES = "types";
        private static final String ATT_USER_SET = "user_set";
        private static final String TAG_ALLOWED_ADJUSTMENT_TYPES = "q_allowed_adjustments";
        static final String TAG_ENABLED_NOTIFICATION_ASSISTANTS = "enabled_assistants";
        private Set<String> mAllowedAdjustments;
        private final Object mLock;
        @GuardedBy({"mLock"})
        private ArrayMap<Integer, Boolean> mUserSetMap;

        public NotificationAssistants(Context context, Object lock, ManagedServices.UserProfiles up, IPackageManager pm) {
            super(context, lock, up, pm);
            this.mLock = new Object();
            this.mUserSetMap = new ArrayMap<>();
            this.mAllowedAdjustments = new ArraySet();
            for (int i = 0; i < NotificationManagerService.DEFAULT_ALLOWED_ADJUSTMENTS.length; i++) {
                this.mAllowedAdjustments.add(NotificationManagerService.DEFAULT_ALLOWED_ADJUSTMENTS[i]);
            }
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
            c.clientLabel = 17040528;
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
        @GuardedBy({"mNotificationLock"})
        protected void onServiceRemovedLocked(ManagedServices.ManagedServiceInfo removed) {
            NotificationManagerService.this.mListeners.unregisterService(removed.service, removed.userid);
        }

        @Override // com.android.server.notification.ManagedServices
        public void onUserUnlocked(int user) {
            if (this.DEBUG) {
                String str = this.TAG;
                Slog.d(str, "onUserUnlocked u=" + user);
            }
            rebindServices(true, user);
        }

        @Override // com.android.server.notification.ManagedServices
        protected String getRequiredPermission() {
            return "android.permission.REQUEST_NOTIFICATION_ASSISTANT_SERVICE";
        }

        @Override // com.android.server.notification.ManagedServices
        protected void writeExtraXmlTags(XmlSerializer out) throws IOException {
            synchronized (this.mLock) {
                out.startTag(null, TAG_ALLOWED_ADJUSTMENT_TYPES);
                out.attribute(null, ATT_TYPES, TextUtils.join(",", this.mAllowedAdjustments));
                out.endTag(null, TAG_ALLOWED_ADJUSTMENT_TYPES);
            }
        }

        @Override // com.android.server.notification.ManagedServices
        protected void readExtraTag(String tag, XmlPullParser parser) throws IOException {
            if (TAG_ALLOWED_ADJUSTMENT_TYPES.equals(tag)) {
                String types = XmlUtils.readStringAttribute(parser, ATT_TYPES);
                synchronized (this.mLock) {
                    this.mAllowedAdjustments.clear();
                    if (!TextUtils.isEmpty(types)) {
                        this.mAllowedAdjustments.addAll(Arrays.asList(types.split(",")));
                    }
                }
            }
        }

        protected void allowAdjustmentType(String type) {
            synchronized (this.mLock) {
                this.mAllowedAdjustments.add(type);
            }
            for (final ManagedServices.ManagedServiceInfo info : getServices()) {
                NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$FsWpf1cmSi9GG7O4rBv1eLAEE9M
                    @Override // java.lang.Runnable
                    public final void run() {
                        NotificationManagerService.NotificationAssistants.this.lambda$allowAdjustmentType$0$NotificationManagerService$NotificationAssistants(info);
                    }
                });
            }
        }

        protected void disallowAdjustmentType(String type) {
            synchronized (this.mLock) {
                this.mAllowedAdjustments.remove(type);
            }
            for (final ManagedServices.ManagedServiceInfo info : getServices()) {
                NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$6E04T6AkRfKEIjCw7jopFAFGv30
                    @Override // java.lang.Runnable
                    public final void run() {
                        NotificationManagerService.NotificationAssistants.this.lambda$disallowAdjustmentType$1$NotificationManagerService$NotificationAssistants(info);
                    }
                });
            }
        }

        protected List<String> getAllowedAssistantAdjustments() {
            List<String> types;
            synchronized (this.mLock) {
                types = new ArrayList<>();
                types.addAll(this.mAllowedAdjustments);
            }
            return types;
        }

        protected boolean isAdjustmentAllowed(String type) {
            boolean contains;
            synchronized (this.mLock) {
                contains = this.mAllowedAdjustments.contains(type);
            }
            return contains;
        }

        protected void onNotificationsSeenLocked(ArrayList<NotificationRecord> records) {
            for (final ManagedServices.ManagedServiceInfo info : getServices()) {
                final ArrayList<String> keys = new ArrayList<>(records.size());
                Iterator<NotificationRecord> it = records.iterator();
                while (it.hasNext()) {
                    NotificationRecord r = it.next();
                    boolean sbnVisible = NotificationManagerService.this.isVisibleToListener(r.sbn, info) && info.isSameUser(r.getUserId());
                    if (sbnVisible) {
                        keys.add(r.getKey());
                    }
                }
                if (!keys.isEmpty()) {
                    NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$hdUZ_hmwLutGkIKdq7dHKjQLP4E
                        @Override // java.lang.Runnable
                        public final void run() {
                            NotificationManagerService.NotificationAssistants.this.lambda$onNotificationsSeenLocked$2$NotificationManagerService$NotificationAssistants(info, keys);
                        }
                    });
                }
            }
        }

        boolean hasUserSet(int userId) {
            boolean booleanValue;
            synchronized (this.mLock) {
                booleanValue = this.mUserSetMap.getOrDefault(Integer.valueOf(userId), false).booleanValue();
            }
            return booleanValue;
        }

        void setUserSet(int userId, boolean set) {
            synchronized (this.mLock) {
                this.mUserSetMap.put(Integer.valueOf(userId), Boolean.valueOf(set));
            }
        }

        @Override // com.android.server.notification.ManagedServices
        protected void writeExtraAttributes(XmlSerializer out, int userId) throws IOException {
            out.attribute(null, ATT_USER_SET, Boolean.toString(hasUserSet(userId)));
        }

        @Override // com.android.server.notification.ManagedServices
        protected void readExtraAttributes(String tag, XmlPullParser parser, int userId) throws IOException {
            boolean userSet = XmlUtils.readBooleanAttribute(parser, ATT_USER_SET, false);
            setUserSet(userId, userSet);
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* renamed from: notifyCapabilitiesChanged */
        public void lambda$disallowAdjustmentType$1$NotificationManagerService$NotificationAssistants(ManagedServices.ManagedServiceInfo info) {
            INotificationListener assistant = info.service;
            try {
                assistant.onAllowedAdjustmentsChanged();
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify assistant (capabilities): " + assistant, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* renamed from: notifySeen */
        public void lambda$onNotificationsSeenLocked$2$NotificationManagerService$NotificationAssistants(ManagedServices.ManagedServiceInfo info, ArrayList<String> keys) {
            INotificationListener assistant = info.service;
            try {
                assistant.onNotificationsSeen(keys);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify assistant (seen): " + assistant, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy({"mNotificationLock"})
        public void onNotificationEnqueuedLocked(final NotificationRecord r) {
            final boolean debug = isVerboseLogEnabled();
            if (debug) {
                String str = this.TAG;
                Slog.v(str, "onNotificationEnqueuedLocked() called with: r = [" + r + "]");
            }
            StatusBarNotification sbn = r.sbn;
            notifyAssistantLocked(sbn, true, new BiConsumer() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$xFD5w0lXKCfWgU2f03eJAOPQABs
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    NotificationManagerService.NotificationAssistants.this.lambda$onNotificationEnqueuedLocked$3$NotificationManagerService$NotificationAssistants(debug, r, (INotificationListener) obj, (NotificationManagerService.StatusBarNotificationHolder) obj2);
                }
            });
        }

        public /* synthetic */ void lambda$onNotificationEnqueuedLocked$3$NotificationManagerService$NotificationAssistants(boolean debug, NotificationRecord r, INotificationListener assistant, StatusBarNotificationHolder sbnHolder) {
            if (debug) {
                try {
                    String str = this.TAG;
                    Slog.v(str, "calling onNotificationEnqueuedWithChannel " + sbnHolder);
                } catch (RemoteException ex) {
                    String str2 = this.TAG;
                    Slog.e(str2, "unable to notify assistant (enqueued): " + assistant, ex);
                    return;
                }
            }
            assistant.onNotificationEnqueuedWithChannel(sbnHolder, r.getChannel());
        }

        @GuardedBy({"mNotificationLock"})
        void notifyAssistantExpansionChangedLocked(StatusBarNotification sbn, final boolean isUserAction, final boolean isExpanded) {
            final String key = sbn.getKey();
            notifyAssistantLocked(sbn, false, new BiConsumer() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$h7WPxGy6WExnaTHJZiTUqSURFAU
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    NotificationManagerService.NotificationAssistants.this.lambda$notifyAssistantExpansionChangedLocked$4$NotificationManagerService$NotificationAssistants(key, isUserAction, isExpanded, (INotificationListener) obj, (NotificationManagerService.StatusBarNotificationHolder) obj2);
                }
            });
        }

        public /* synthetic */ void lambda$notifyAssistantExpansionChangedLocked$4$NotificationManagerService$NotificationAssistants(String key, boolean isUserAction, boolean isExpanded, INotificationListener assistant, StatusBarNotificationHolder sbnHolder) {
            try {
                assistant.onNotificationExpansionChanged(key, isUserAction, isExpanded);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify assistant (expanded): " + assistant, ex);
            }
        }

        @GuardedBy({"mNotificationLock"})
        void notifyAssistantNotificationDirectReplyLocked(StatusBarNotification sbn) {
            final String key = sbn.getKey();
            notifyAssistantLocked(sbn, false, new BiConsumer() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$JF5pLiK7GJ1M0xNPiK9WMEs3Axo
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    NotificationManagerService.NotificationAssistants.this.lambda$notifyAssistantNotificationDirectReplyLocked$5$NotificationManagerService$NotificationAssistants(key, (INotificationListener) obj, (NotificationManagerService.StatusBarNotificationHolder) obj2);
                }
            });
        }

        public /* synthetic */ void lambda$notifyAssistantNotificationDirectReplyLocked$5$NotificationManagerService$NotificationAssistants(String key, INotificationListener assistant, StatusBarNotificationHolder sbnHolder) {
            try {
                assistant.onNotificationDirectReply(key);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify assistant (expanded): " + assistant, ex);
            }
        }

        @GuardedBy({"mNotificationLock"})
        void notifyAssistantSuggestedReplySent(StatusBarNotification sbn, final CharSequence reply, final boolean generatedByAssistant) {
            final String key = sbn.getKey();
            notifyAssistantLocked(sbn, false, new BiConsumer() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$-pTtydmbKR53sVGAi5B-_cGeLDo
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    NotificationManagerService.NotificationAssistants.this.lambda$notifyAssistantSuggestedReplySent$6$NotificationManagerService$NotificationAssistants(key, reply, generatedByAssistant, (INotificationListener) obj, (NotificationManagerService.StatusBarNotificationHolder) obj2);
                }
            });
        }

        public /* synthetic */ void lambda$notifyAssistantSuggestedReplySent$6$NotificationManagerService$NotificationAssistants(String key, CharSequence reply, boolean generatedByAssistant, INotificationListener assistant, StatusBarNotificationHolder sbnHolder) {
            int i;
            if (generatedByAssistant) {
                i = 1;
            } else {
                i = 0;
            }
            try {
                assistant.onSuggestedReplySent(key, reply, i);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify assistant (snoozed): " + assistant, ex);
            }
        }

        @GuardedBy({"mNotificationLock"})
        void notifyAssistantActionClicked(StatusBarNotification sbn, int actionIndex, final Notification.Action action, final boolean generatedByAssistant) {
            final String key = sbn.getKey();
            notifyAssistantLocked(sbn, false, new BiConsumer() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$Rqv2CeOOOVMkVDRSXa6GcHvi5Vc
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    NotificationManagerService.NotificationAssistants.this.lambda$notifyAssistantActionClicked$7$NotificationManagerService$NotificationAssistants(key, action, generatedByAssistant, (INotificationListener) obj, (NotificationManagerService.StatusBarNotificationHolder) obj2);
                }
            });
        }

        public /* synthetic */ void lambda$notifyAssistantActionClicked$7$NotificationManagerService$NotificationAssistants(String key, Notification.Action action, boolean generatedByAssistant, INotificationListener assistant, StatusBarNotificationHolder sbnHolder) {
            int i;
            if (generatedByAssistant) {
                i = 1;
            } else {
                i = 0;
            }
            try {
                assistant.onActionClicked(key, action, i);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify assistant (snoozed): " + assistant, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy({"mNotificationLock"})
        public void notifyAssistantSnoozedLocked(StatusBarNotification sbn, final String snoozeCriterionId) {
            notifyAssistantLocked(sbn, false, new BiConsumer() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$hZf_EguDPjMH_PBC0gm7sadRDTE
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    NotificationManagerService.NotificationAssistants.this.lambda$notifyAssistantSnoozedLocked$8$NotificationManagerService$NotificationAssistants(snoozeCriterionId, (INotificationListener) obj, (NotificationManagerService.StatusBarNotificationHolder) obj2);
                }
            });
        }

        public /* synthetic */ void lambda$notifyAssistantSnoozedLocked$8$NotificationManagerService$NotificationAssistants(String snoozeCriterionId, INotificationListener assistant, StatusBarNotificationHolder sbnHolder) {
            try {
                assistant.onNotificationSnoozedUntilContext(sbnHolder, snoozeCriterionId);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify assistant (snoozed): " + assistant, ex);
            }
        }

        @GuardedBy({"mNotificationLock"})
        private void notifyAssistantLocked(StatusBarNotification sbn, boolean sameUserOnly, final BiConsumer<INotificationListener, StatusBarNotificationHolder> callback) {
            TrimCache trimCache = new TrimCache(sbn);
            boolean debug = isVerboseLogEnabled();
            if (debug) {
                String str = this.TAG;
                Slog.v(str, "notifyAssistantLocked() called with: sbn = [" + sbn + "], sameUserOnly = [" + sameUserOnly + "], callback = [" + callback + "]");
            }
            for (ManagedServices.ManagedServiceInfo info : getServices()) {
                boolean sbnVisible = NotificationManagerService.this.isVisibleToListener(sbn, info) && (!sameUserOnly || info.isSameUser(sbn.getUserId()));
                if (debug) {
                    String str2 = this.TAG;
                    Slog.v(str2, "notifyAssistantLocked info=" + info + " snbVisible=" + sbnVisible);
                }
                if (sbnVisible) {
                    final INotificationListener assistant = info.service;
                    StatusBarNotification sbnToPost = trimCache.ForListener(info);
                    final StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbnToPost);
                    NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationAssistants$FrOqX0VMAS0gs6vhrmVEabwpi2k
                        @Override // java.lang.Runnable
                        public final void run() {
                            callback.accept(assistant, sbnHolder);
                        }
                    });
                }
            }
        }

        public boolean isEnabled() {
            return !getServices().isEmpty();
        }

        protected void resetDefaultAssistantsIfNecessary() {
            List<UserInfo> activeUsers = this.mUm.getUsers(true);
            for (UserInfo userInfo : activeUsers) {
                int userId = userInfo.getUserHandle().getIdentifier();
                if (!hasUserSet(userId)) {
                    String str = this.TAG;
                    Slog.d(str, "Approving default notification assistant for user " + userId);
                    NotificationManagerService.this.setDefaultAssistantForUser(userId);
                }
            }
        }

        @Override // com.android.server.notification.ManagedServices
        protected void setPackageOrComponentEnabled(String pkgOrComponent, int userId, boolean isPrimary, boolean enabled) {
            if (enabled) {
                List<ComponentName> allowedComponents = getAllowedComponents(userId);
                if (!allowedComponents.isEmpty()) {
                    ComponentName currentComponent = (ComponentName) CollectionUtils.firstOrNull(allowedComponents);
                    if (currentComponent.flattenToString().equals(pkgOrComponent)) {
                        return;
                    }
                    NotificationManagerService.this.setNotificationAssistantAccessGrantedForUserInternal(currentComponent, userId, false);
                }
            }
            super.setPackageOrComponentEnabled(pkgOrComponent, userId, isPrimary, enabled);
        }

        @Override // com.android.server.notification.ManagedServices
        public void dump(PrintWriter pw, DumpFilter filter) {
            super.dump(pw, filter);
            pw.println("    Has user set:");
            synchronized (this.mLock) {
                Set<Integer> userIds = this.mUserSetMap.keySet();
                for (Integer num : userIds) {
                    int userId = num.intValue();
                    pw.println("      userId=" + userId + " value=" + this.mUserSetMap.get(Integer.valueOf(userId)));
                }
            }
        }

        private boolean isVerboseLogEnabled() {
            return Log.isLoggable("notification_assistant", 2);
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
            c.clientLabel = 17040526;
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
        @GuardedBy({"mNotificationLock"})
        protected void onServiceRemovedLocked(ManagedServices.ManagedServiceInfo removed) {
            if (NotificationManagerService.this.removeDisabledHints(removed)) {
                NotificationManagerService.this.updateListenerHintsLocked();
                NotificationManagerService.this.updateEffectsSuppressorLocked();
            }
            this.mLightTrimListeners.remove(removed);
        }

        @Override // com.android.server.notification.ManagedServices
        protected String getRequiredPermission() {
            return null;
        }

        @GuardedBy({"mNotificationLock"})
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

        public void onStatusBarIconsBehaviorChanged(final boolean hideSilentStatusIcons) {
            for (final ManagedServices.ManagedServiceInfo info : getServices()) {
                NotificationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$Uven29tL9-XX5tMiwAHBwNumQKc
                    @Override // java.lang.Runnable
                    public final void run() {
                        NotificationManagerService.NotificationListeners.this.lambda$onStatusBarIconsBehaviorChanged$0$NotificationManagerService$NotificationListeners(info, hideSilentStatusIcons);
                    }
                });
            }
        }

        public /* synthetic */ void lambda$onStatusBarIconsBehaviorChanged$0$NotificationManagerService$NotificationListeners(ManagedServices.ManagedServiceInfo info, boolean hideSilentStatusIcons) {
            INotificationListener listener = info.service;
            try {
                listener.onStatusBarIconsBehaviorChanged(hideSilentStatusIcons);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify listener (hideSilentStatusIcons): " + listener, ex);
            }
        }

        @GuardedBy({"mNotificationLock"})
        public void notifyPostedLocked(NotificationRecord r, NotificationRecord old) {
            notifyPostedLocked(r, old, true);
        }

        @GuardedBy({"mNotificationLock"})
        private void notifyPostedLocked(NotificationRecord r, NotificationRecord old, boolean notifyAllListeners) {
            boolean oldSbnVisible;
            StatusBarNotification sbn = r.sbn;
            StatusBarNotification oldSbn = old != null ? old.sbn : null;
            TrimCache trimCache = new TrimCache(sbn);
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

        /* JADX WARN: Code restructure failed: missing block: B:16:0x003f, code lost:
            if (r13.targetSdkVersion < 28) goto L16;
         */
        @com.android.internal.annotations.GuardedBy({"mNotificationLock"})
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void notifyRemovedLocked(final com.android.server.notification.NotificationRecord r17, final int r18, android.service.notification.NotificationStats r19) {
            /*
                r16 = this;
                r7 = r16
                r8 = r17
                r9 = r18
                android.service.notification.StatusBarNotification r10 = r8.sbn
                android.service.notification.StatusBarNotification r11 = r10.cloneLight()
                java.util.List r0 = r16.getServices()
                java.util.Iterator r12 = r0.iterator()
            L14:
                boolean r0 = r12.hasNext()
                if (r0 == 0) goto L75
                java.lang.Object r0 = r12.next()
                r13 = r0
                com.android.server.notification.ManagedServices$ManagedServiceInfo r13 = (com.android.server.notification.ManagedServices.ManagedServiceInfo) r13
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                boolean r0 = com.android.server.notification.NotificationManagerService.access$4900(r0, r10, r13)
                if (r0 != 0) goto L2a
                goto L14
            L2a:
                boolean r0 = r17.isHidden()
                r1 = 28
                r2 = 14
                if (r0 == 0) goto L3b
                if (r9 == r2) goto L3b
                int r0 = r13.targetSdkVersion
                if (r0 >= r1) goto L3b
                goto L14
            L3b:
                if (r9 != r2) goto L42
                int r0 = r13.targetSdkVersion
                if (r0 < r1) goto L42
                goto L14
            L42:
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                com.android.server.notification.NotificationManagerService$NotificationAssistants r0 = com.android.server.notification.NotificationManagerService.access$600(r0)
                android.os.IInterface r1 = r13.service
                boolean r0 = r0.isServiceTokenValidLocked(r1)
                if (r0 == 0) goto L53
                r5 = r19
                goto L55
            L53:
                r0 = 0
                r5 = r0
            L55:
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                android.service.notification.NotificationRankingUpdate r14 = com.android.server.notification.NotificationManagerService.access$10000(r0, r13)
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                com.android.server.notification.NotificationManagerService$WorkerHandler r15 = com.android.server.notification.NotificationManagerService.access$2100(r0)
                com.android.server.notification.NotificationManagerService$NotificationListeners$3 r6 = new com.android.server.notification.NotificationManagerService$NotificationListeners$3
                r0 = r6
                r1 = r16
                r2 = r13
                r3 = r11
                r4 = r14
                r9 = r6
                r6 = r18
                r0.<init>()
                r15.post(r9)
                r9 = r18
                goto L14
            L75:
                com.android.server.notification.NotificationManagerService r0 = com.android.server.notification.NotificationManagerService.this
                com.android.server.notification.NotificationManagerService$WorkerHandler r0 = com.android.server.notification.NotificationManagerService.access$2100(r0)
                com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$3bretMyG2YyNFKU5plLQgmxuGr0 r1 = new com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$3bretMyG2YyNFKU5plLQgmxuGr0
                r1.<init>()
                r0.post(r1)
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.NotificationListeners.notifyRemovedLocked(com.android.server.notification.NotificationRecord, int, android.service.notification.NotificationStats):void");
        }

        public /* synthetic */ void lambda$notifyRemovedLocked$1$NotificationManagerService$NotificationListeners(NotificationRecord r) {
            NotificationManagerService.this.updateUriPermissions(null, r, null, 0);
        }

        @GuardedBy({"mNotificationLock"})
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

        @GuardedBy({"mNotificationLock"})
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

        @GuardedBy({"mNotificationLock"})
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

        @GuardedBy({"mNotificationLock"})
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
                    BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$T5BM1IF40aMGtqZZRr6BWGjzNxA
                        @Override // java.lang.Runnable
                        public final void run() {
                            NotificationManagerService.NotificationListeners.this.lambda$notifyNotificationChannelChanged$2$NotificationManagerService$NotificationListeners(serviceInfo, pkg, user, channel, modificationType);
                        }
                    });
                }
            }
        }

        public /* synthetic */ void lambda$notifyNotificationChannelChanged$2$NotificationManagerService$NotificationListeners(ManagedServices.ManagedServiceInfo serviceInfo, String pkg, UserHandle user, NotificationChannel channel, int modificationType) {
            if (NotificationManagerService.this.hasCompanionDevice(serviceInfo)) {
                notifyNotificationChannelChanged(serviceInfo, pkg, user, channel, modificationType);
            }
        }

        protected void notifyNotificationChannelGroupChanged(final String pkg, final UserHandle user, final NotificationChannelGroup group, final int modificationType) {
            if (group == null) {
                return;
            }
            for (final ManagedServices.ManagedServiceInfo serviceInfo : getServices()) {
                if (serviceInfo.enabledAndUserMatches(UserHandle.getCallingUserId())) {
                    BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.notification.-$$Lambda$NotificationManagerService$NotificationListeners$Srt8NNqA1xJUAp_7nDU6CBZJm_0
                        @Override // java.lang.Runnable
                        public final void run() {
                            NotificationManagerService.NotificationListeners.this.lambda$notifyNotificationChannelGroupChanged$3$NotificationManagerService$NotificationListeners(serviceInfo, pkg, user, group, modificationType);
                        }
                    });
                }
            }
        }

        public /* synthetic */ void lambda$notifyNotificationChannelGroupChanged$3$NotificationManagerService$NotificationListeners(ManagedServices.ManagedServiceInfo serviceInfo, String pkg, UserHandle user, NotificationChannelGroup group, int modificationType) {
            if (NotificationManagerService.this.hasCompanionDevice(serviceInfo)) {
                notifyNotificationChannelGroupChanged(serviceInfo, pkg, user, group, modificationType);
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
                Slog.e(str, "unable to notify listener (posted): " + listener, ex);
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
                Slog.e(str, "unable to notify listener (removed): " + listener, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyRankingUpdate(ManagedServices.ManagedServiceInfo info, NotificationRankingUpdate rankingUpdate) {
            INotificationListener listener = info.service;
            try {
                listener.onNotificationRankingUpdate(rankingUpdate);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify listener (ranking update): " + listener, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyListenerHintsChanged(ManagedServices.ManagedServiceInfo info, int hints) {
            INotificationListener listener = info.service;
            try {
                listener.onListenerHintsChanged(hints);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify listener (listener hints): " + listener, ex);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void notifyInterruptionFilterChanged(ManagedServices.ManagedServiceInfo info, int interruptionFilter) {
            INotificationListener listener = info.service;
            try {
                listener.onInterruptionFilterChanged(interruptionFilter);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify listener (interruption filter): " + listener, ex);
            }
        }

        void notifyNotificationChannelChanged(ManagedServices.ManagedServiceInfo info, String pkg, UserHandle user, NotificationChannel channel, int modificationType) {
            INotificationListener listener = info.service;
            try {
                listener.onNotificationChannelModification(pkg, user, channel, modificationType);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify listener (channel changed): " + listener, ex);
            }
        }

        private void notifyNotificationChannelGroupChanged(ManagedServices.ManagedServiceInfo info, String pkg, UserHandle user, NotificationChannelGroup group, int modificationType) {
            INotificationListener listener = info.service;
            try {
                listener.onNotificationChannelGroupModification(pkg, user, group, modificationType);
            } catch (RemoteException ex) {
                String str = this.TAG;
                Slog.e(str, "unable to notify listener (channel group changed): " + listener, ex);
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
    class RoleObserver implements OnRoleHoldersChangedListener {
        private final Executor mExecutor;
        private ArrayMap<String, ArrayMap<Integer, ArraySet<String>>> mNonBlockableDefaultApps;
        private final IPackageManager mPm;
        private final RoleManager mRm;

        RoleObserver(RoleManager roleManager, IPackageManager pkgMgr, Executor executor) {
            this.mRm = roleManager;
            this.mPm = pkgMgr;
            this.mExecutor = executor;
        }

        public void init() {
            List<UserInfo> users = NotificationManagerService.this.mUm.getUsers();
            this.mNonBlockableDefaultApps = new ArrayMap<>();
            for (int i = 0; i < NotificationManagerService.NON_BLOCKABLE_DEFAULT_ROLES.length; i++) {
                ArrayMap<Integer, ArraySet<String>> userToApprovedList = new ArrayMap<>();
                this.mNonBlockableDefaultApps.put(NotificationManagerService.NON_BLOCKABLE_DEFAULT_ROLES[i], userToApprovedList);
                for (int j = 0; j < users.size(); j++) {
                    Integer userId = Integer.valueOf(users.get(j).getUserHandle().getIdentifier());
                    ArraySet<String> approvedForUserId = new ArraySet<>(this.mRm.getRoleHoldersAsUser(NotificationManagerService.NON_BLOCKABLE_DEFAULT_ROLES[i], UserHandle.of(userId.intValue())));
                    ArraySet<Pair<String, Integer>> approvedAppUids = new ArraySet<>();
                    Iterator<String> it = approvedForUserId.iterator();
                    while (it.hasNext()) {
                        String pkg = it.next();
                        approvedAppUids.add(new Pair<>(pkg, Integer.valueOf(getUidForPackage(pkg, userId.intValue()))));
                    }
                    userToApprovedList.put(userId, approvedForUserId);
                    NotificationManagerService.this.mPreferencesHelper.updateDefaultApps(userId.intValue(), null, approvedAppUids);
                }
            }
            this.mRm.addOnRoleHoldersChangedListenerAsUser(this.mExecutor, this, UserHandle.ALL);
        }

        @VisibleForTesting
        public boolean isApprovedPackageForRoleForUser(String role, String pkg, int userId) {
            return this.mNonBlockableDefaultApps.get(role).get(Integer.valueOf(userId)).contains(pkg);
        }

        public void onRoleHoldersChanged(String roleName, UserHandle user) {
            boolean relevantChange = false;
            int i = 0;
            while (true) {
                if (i >= NotificationManagerService.NON_BLOCKABLE_DEFAULT_ROLES.length) {
                    break;
                } else if (!NotificationManagerService.NON_BLOCKABLE_DEFAULT_ROLES[i].equals(roleName)) {
                    i++;
                } else {
                    relevantChange = true;
                    break;
                }
            }
            if (!relevantChange) {
                return;
            }
            ArraySet<String> roleHolders = new ArraySet<>(this.mRm.getRoleHoldersAsUser(roleName, user));
            ArrayMap<Integer, ArraySet<String>> prevApprovedForRole = this.mNonBlockableDefaultApps.getOrDefault(roleName, new ArrayMap<>());
            ArraySet<String> previouslyApproved = prevApprovedForRole.getOrDefault(Integer.valueOf(user.getIdentifier()), new ArraySet<>());
            ArraySet<String> toRemove = new ArraySet<>();
            ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
            Iterator<String> it = previouslyApproved.iterator();
            while (it.hasNext()) {
                String previous = it.next();
                if (!roleHolders.contains(previous)) {
                    toRemove.add(previous);
                }
            }
            Iterator<String> it2 = roleHolders.iterator();
            while (it2.hasNext()) {
                String nowApproved = it2.next();
                if (!previouslyApproved.contains(nowApproved)) {
                    toAdd.add(new Pair<>(nowApproved, Integer.valueOf(getUidForPackage(nowApproved, user.getIdentifier()))));
                }
            }
            prevApprovedForRole.put(Integer.valueOf(user.getIdentifier()), roleHolders);
            this.mNonBlockableDefaultApps.put(roleName, prevApprovedForRole);
            NotificationManagerService.this.mPreferencesHelper.updateDefaultApps(user.getIdentifier(), toRemove, toAdd);
        }

        private int getUidForPackage(String pkg, int userId) {
            try {
                return this.mPm.getPackageUid(pkg, 131072, userId);
            } catch (RemoteException e) {
                Slog.e(NotificationManagerService.TAG, "role manager has bad default " + pkg + " " + userId);
                return -1;
            }
        }
    }

    /* loaded from: classes.dex */
    public static final class DumpFilter {
        public String pkgFilter;
        public boolean rvStats;
        public long since;
        public boolean stats;
        public boolean zen;
        public boolean filtered = false;
        public boolean redact = true;
        public boolean proto = false;
        public boolean criticalPriority = false;
        public boolean normalPriority = false;

        /* JADX WARN: Code restructure failed: missing block: B:47:0x00be, code lost:
            if (r3.equals(com.android.server.utils.PriorityDump.PRIORITY_ARG_CRITICAL) == false) goto L56;
         */
        /* JADX WARN: Removed duplicated region for block: B:54:0x00ce  */
        /* JADX WARN: Removed duplicated region for block: B:57:0x00d4  */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public static com.android.server.notification.NotificationManagerService.DumpFilter parseFromArguments(java.lang.String[] r9) {
            /*
                Method dump skipped, instructions count: 259
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationManagerService.DumpFilter.parseFromArguments(java.lang.String[]):com.android.server.notification.NotificationManagerService$DumpFilter");
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public void resetAssistantUserSet(int userId) {
        checkCallerIsSystemOrShell();
        this.mAssistants.setUserSet(userId, false);
        handleSavePolicyFile();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public ComponentName getApprovedAssistant(int userId) {
        checkCallerIsSystemOrShell();
        List<ComponentName> allowedComponents = this.mAssistants.getAllowedComponents(userId);
        return (ComponentName) CollectionUtils.firstOrNull(allowedComponents);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @VisibleForTesting
    public void simulatePackageSuspendBroadcast(boolean suspend, String pkg) {
        checkCallerIsSystemOrShell();
        Bundle extras = new Bundle();
        extras.putStringArray("android.intent.extra.changed_package_list", new String[]{pkg});
        String action = suspend ? "android.intent.action.PACKAGES_SUSPENDED" : "android.intent.action.PACKAGES_UNSUSPENDED";
        Intent intent = new Intent(action);
        intent.putExtras(extras);
        this.mPackageIntentReceiver.onReceive(getContext(), intent);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @VisibleForTesting
    public void simulatePackageDistractionBroadcast(int flag, String[] pkgs) {
        checkCallerIsSystemOrShell();
        Bundle extras = new Bundle();
        extras.putStringArray("android.intent.extra.changed_package_list", pkgs);
        extras.putInt("android.intent.extra.distraction_restrictions", flag);
        Intent intent = new Intent("android.intent.action.DISTRACTING_PACKAGES_CHANGED");
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

    private void writeSecureNotificationsPolicy(XmlSerializer out) throws IOException {
        out.startTag(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG);
        out.attribute(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_VALUE, Boolean.toString(this.mLockScreenAllowSecureNotifications));
        out.endTag(null, LOCKSCREEN_ALLOW_SECURE_NOTIFICATIONS_TAG);
    }

    private static boolean safeBoolean(String val, boolean defValue) {
        return TextUtils.isEmpty(val) ? defValue : Boolean.parseBoolean(val);
    }
}
