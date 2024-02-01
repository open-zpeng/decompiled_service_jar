package com.android.server.pm;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.IShortcutService;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TypedValue;
import android.util.Xml;
import android.view.IWindowManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.StatLogger;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.ShortcutService;
import com.android.server.pm.ShortcutUser;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import libcore.io.IoUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
public class ShortcutService extends IShortcutService.Stub {
    private static final String ATTR_VALUE = "value";
    static final boolean DEBUG = false;
    static final boolean DEBUG_LOAD = false;
    static final boolean DEBUG_PROCSTATE = false;
    @VisibleForTesting
    static final int DEFAULT_ICON_PERSIST_QUALITY = 100;
    @VisibleForTesting
    static final int DEFAULT_MAX_ICON_DIMENSION_DP = 96;
    @VisibleForTesting
    static final int DEFAULT_MAX_ICON_DIMENSION_LOWRAM_DP = 48;
    @VisibleForTesting
    static final int DEFAULT_MAX_SHORTCUTS_PER_APP = 5;
    @VisibleForTesting
    static final int DEFAULT_MAX_UPDATES_PER_INTERVAL = 10;
    @VisibleForTesting
    static final long DEFAULT_RESET_INTERVAL_SEC = 86400;
    @VisibleForTesting
    static final int DEFAULT_SAVE_DELAY_MS = 3000;
    static final String DIRECTORY_BITMAPS = "bitmaps";
    @VisibleForTesting
    static final String DIRECTORY_DUMP = "shortcut_dump";
    @VisibleForTesting
    static final String DIRECTORY_PER_USER = "shortcut_service";
    private static final String DUMMY_MAIN_ACTIVITY = "android.__dummy__";
    @VisibleForTesting
    static final String FILENAME_BASE_STATE = "shortcut_service.xml";
    @VisibleForTesting
    static final String FILENAME_USER_PACKAGES = "shortcuts.xml";
    private static final String KEY_ICON_SIZE = "iconSize";
    private static final String KEY_LOW_RAM = "lowRam";
    private static final String KEY_SHORTCUT = "shortcut";
    private static final String LAUNCHER_INTENT_CATEGORY = "android.intent.category.LAUNCHER";
    static final int OPERATION_ADD = 1;
    static final int OPERATION_SET = 0;
    static final int OPERATION_UPDATE = 2;
    private static final int PACKAGE_MATCH_FLAGS = 794624;
    private static final int PROCESS_STATE_FOREGROUND_THRESHOLD = 4;
    static final String TAG = "ShortcutService";
    private static final String TAG_LAST_RESET_TIME = "last_reset_time";
    private static final String TAG_ROOT = "root";
    private final ActivityManagerInternal mActivityManagerInternal;
    private final AtomicBoolean mBootCompleted;
    final Context mContext;
    @GuardedBy("mLock")
    private List<Integer> mDirtyUserIds;
    private final Handler mHandler;
    private final IPackageManager mIPackageManager;
    private Bitmap.CompressFormat mIconPersistFormat;
    private int mIconPersistQuality;
    @GuardedBy("mLock")
    private Exception mLastWtfStacktrace;
    @GuardedBy("mLock")
    private final ArrayList<ShortcutServiceInternal.ShortcutChangeListener> mListeners;
    private final Object mLock;
    private int mMaxIconDimension;
    private int mMaxShortcuts;
    int mMaxUpdatesPerInterval;
    private final PackageManagerInternal mPackageManagerInternal;
    @VisibleForTesting
    final BroadcastReceiver mPackageMonitor;
    @GuardedBy("mLock")
    private long mRawLastResetTime;
    final BroadcastReceiver mReceiver;
    private long mResetInterval;
    private int mSaveDelayMillis;
    private final Runnable mSaveDirtyInfoRunner;
    private final ShortcutBitmapSaver mShortcutBitmapSaver;
    private final ShortcutDumpFiles mShortcutDumpFiles;
    @GuardedBy("mLock")
    private final SparseArray<ShortcutNonPersistentUser> mShortcutNonPersistentUsers;
    private final ShortcutRequestPinProcessor mShortcutRequestPinProcessor;
    private final StatLogger mStatLogger;
    @GuardedBy("mLock")
    final SparseLongArray mUidLastForegroundElapsedTime;
    private final IUidObserver mUidObserver;
    @GuardedBy("mLock")
    final SparseIntArray mUidState;
    @GuardedBy("mUnlockedUsers")
    final SparseBooleanArray mUnlockedUsers;
    private final UsageStatsManagerInternal mUsageStatsManagerInternal;
    private final UserManagerInternal mUserManagerInternal;
    @GuardedBy("mLock")
    private final SparseArray<ShortcutUser> mUsers;
    @GuardedBy("mLock")
    private int mWtfCount;
    @VisibleForTesting
    static final String DEFAULT_ICON_PERSIST_FORMAT = Bitmap.CompressFormat.PNG.name();
    private static List<ResolveInfo> EMPTY_RESOLVE_INFO = new ArrayList(0);
    private static Predicate<ResolveInfo> ACTIVITY_NOT_EXPORTED = new Predicate<ResolveInfo>() { // from class: com.android.server.pm.ShortcutService.1
        @Override // java.util.function.Predicate
        public boolean test(ResolveInfo ri) {
            return !ri.activityInfo.exported;
        }
    };
    private static Predicate<PackageInfo> PACKAGE_NOT_INSTALLED = new Predicate<PackageInfo>() { // from class: com.android.server.pm.ShortcutService.2
        @Override // java.util.function.Predicate
        public boolean test(PackageInfo pi) {
            return !ShortcutService.isInstalled(pi);
        }
    };

    @VisibleForTesting
    /* loaded from: classes.dex */
    interface ConfigConstants {
        public static final String KEY_ICON_FORMAT = "icon_format";
        public static final String KEY_ICON_QUALITY = "icon_quality";
        public static final String KEY_MAX_ICON_DIMENSION_DP = "max_icon_dimension_dp";
        public static final String KEY_MAX_ICON_DIMENSION_DP_LOWRAM = "max_icon_dimension_dp_lowram";
        public static final String KEY_MAX_SHORTCUTS = "max_shortcuts";
        public static final String KEY_MAX_UPDATES_PER_INTERVAL = "max_updates_per_interval";
        public static final String KEY_RESET_INTERVAL_SEC = "reset_interval_sec";
        public static final String KEY_SAVE_DELAY_MILLIS = "save_delay_ms";
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    @interface ShortcutOperation {
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    interface Stats {
        public static final int ASYNC_PRELOAD_USER_DELAY = 15;
        public static final int CHECK_LAUNCHER_ACTIVITY = 12;
        public static final int CHECK_PACKAGE_CHANGES = 8;
        public static final int CLEANUP_DANGLING_BITMAPS = 5;
        public static final int COUNT = 17;
        public static final int GET_ACTIVITY_WITH_METADATA = 6;
        public static final int GET_APPLICATION_INFO = 3;
        public static final int GET_APPLICATION_RESOURCES = 9;
        public static final int GET_DEFAULT_HOME = 0;
        public static final int GET_DEFAULT_LAUNCHER = 16;
        public static final int GET_INSTALLED_PACKAGES = 7;
        public static final int GET_LAUNCHER_ACTIVITY = 11;
        public static final int GET_PACKAGE_INFO = 1;
        public static final int GET_PACKAGE_INFO_WITH_SIG = 2;
        public static final int IS_ACTIVITY_ENABLED = 13;
        public static final int LAUNCHER_PERMISSION_CHECK = 4;
        public static final int PACKAGE_UPDATE_CHECK = 14;
        public static final int RESOURCE_NAME_LOOKUP = 10;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class InvalidFileFormatException extends Exception {
        public InvalidFileFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ShortcutService(Context context) {
        this(context, BackgroundThread.get().getLooper(), false);
    }

    @VisibleForTesting
    ShortcutService(Context context, Looper looper, boolean onlyForPackageManagerApis) {
        this.mLock = new Object();
        this.mListeners = new ArrayList<>(1);
        this.mUsers = new SparseArray<>();
        this.mShortcutNonPersistentUsers = new SparseArray<>();
        this.mUidState = new SparseIntArray();
        this.mUidLastForegroundElapsedTime = new SparseLongArray();
        this.mDirtyUserIds = new ArrayList();
        this.mBootCompleted = new AtomicBoolean();
        this.mUnlockedUsers = new SparseBooleanArray();
        this.mStatLogger = new StatLogger(new String[]{"getHomeActivities()", "Launcher permission check", "getPackageInfo()", "getPackageInfo(SIG)", "getApplicationInfo", "cleanupDanglingBitmaps", "getActivity+metadata", "getInstalledPackages", "checkPackageChanges", "getApplicationResources", "resourceNameLookup", "getLauncherActivity", "checkLauncherActivity", "isActivityEnabled", "packageUpdateCheck", "asyncPreloadUserDelay", "getDefaultLauncher()"});
        this.mWtfCount = 0;
        this.mUidObserver = new AnonymousClass3();
        this.mSaveDirtyInfoRunner = new Runnable() { // from class: com.android.server.pm.-$$Lambda$jZzCUQd1whVIqs_s1XMLbFqTP_E
            @Override // java.lang.Runnable
            public final void run() {
                ShortcutService.this.saveDirtyInfo();
            }
        };
        this.mReceiver = new BroadcastReceiver() { // from class: com.android.server.pm.ShortcutService.4
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if (!ShortcutService.this.mBootCompleted.get()) {
                    return;
                }
                try {
                    if ("android.intent.action.LOCALE_CHANGED".equals(intent.getAction())) {
                        ShortcutService.this.handleLocaleChanged();
                    }
                } catch (Exception e) {
                    ShortcutService.this.wtf("Exception in mReceiver.onReceive", e);
                }
            }
        };
        this.mPackageMonitor = new BroadcastReceiver() { // from class: com.android.server.pm.ShortcutService.5
            /* JADX WARN: Removed duplicated region for block: B:51:0x00cf  */
            /* JADX WARN: Removed duplicated region for block: B:52:0x00d0 A[Catch: all -> 0x00f5, Exception -> 0x00f7, TryCatch #2 {Exception -> 0x00f7, blocks: (B:7:0x002b, B:8:0x0031, B:16:0x004b, B:20:0x0059, B:22:0x005f, B:25:0x0067, B:28:0x0083, B:50:0x00cc, B:52:0x00d0, B:53:0x00d6, B:55:0x00de, B:57:0x00e6, B:58:0x00ec, B:37:0x00a4, B:40:0x00ad, B:43:0x00b7, B:46:0x00c1, B:62:0x00f4), top: B:72:0x002b, outer: #1 }] */
            /* JADX WARN: Removed duplicated region for block: B:53:0x00d6 A[Catch: all -> 0x00f5, Exception -> 0x00f7, TryCatch #2 {Exception -> 0x00f7, blocks: (B:7:0x002b, B:8:0x0031, B:16:0x004b, B:20:0x0059, B:22:0x005f, B:25:0x0067, B:28:0x0083, B:50:0x00cc, B:52:0x00d0, B:53:0x00d6, B:55:0x00de, B:57:0x00e6, B:58:0x00ec, B:37:0x00a4, B:40:0x00ad, B:43:0x00b7, B:46:0x00c1, B:62:0x00f4), top: B:72:0x002b, outer: #1 }] */
            /* JADX WARN: Removed duplicated region for block: B:54:0x00dc  */
            /* JADX WARN: Removed duplicated region for block: B:56:0x00e4  */
            @Override // android.content.BroadcastReceiver
            /*
                Code decompiled incorrectly, please refer to instructions dump.
                To view partially-correct add '--show-bad-code' argument
            */
            public void onReceive(android.content.Context r12, android.content.Intent r13) {
                /*
                    Method dump skipped, instructions count: 280
                    To view this dump add '--comments-level debug' option
                */
                throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutService.AnonymousClass5.onReceive(android.content.Context, android.content.Intent):void");
            }
        };
        this.mContext = (Context) Preconditions.checkNotNull(context);
        LocalServices.addService(ShortcutServiceInternal.class, new LocalService());
        this.mHandler = new Handler(looper);
        this.mIPackageManager = AppGlobals.getPackageManager();
        this.mPackageManagerInternal = (PackageManagerInternal) Preconditions.checkNotNull((PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class));
        this.mUserManagerInternal = (UserManagerInternal) Preconditions.checkNotNull((UserManagerInternal) LocalServices.getService(UserManagerInternal.class));
        this.mUsageStatsManagerInternal = (UsageStatsManagerInternal) Preconditions.checkNotNull((UsageStatsManagerInternal) LocalServices.getService(UsageStatsManagerInternal.class));
        this.mActivityManagerInternal = (ActivityManagerInternal) Preconditions.checkNotNull((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class));
        this.mShortcutRequestPinProcessor = new ShortcutRequestPinProcessor(this, this.mLock);
        this.mShortcutBitmapSaver = new ShortcutBitmapSaver(this);
        this.mShortcutDumpFiles = new ShortcutDumpFiles(this);
        if (onlyForPackageManagerApis) {
            return;
        }
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
        packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
        packageFilter.addAction("android.intent.action.PACKAGE_DATA_CLEARED");
        packageFilter.addDataScheme("package");
        packageFilter.setPriority(1000);
        this.mContext.registerReceiverAsUser(this.mPackageMonitor, UserHandle.ALL, packageFilter, null, this.mHandler);
        IntentFilter preferedActivityFilter = new IntentFilter();
        preferedActivityFilter.addAction("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED");
        preferedActivityFilter.setPriority(1000);
        this.mContext.registerReceiverAsUser(this.mPackageMonitor, UserHandle.ALL, preferedActivityFilter, null, this.mHandler);
        IntentFilter localeFilter = new IntentFilter();
        localeFilter.addAction("android.intent.action.LOCALE_CHANGED");
        localeFilter.setPriority(1000);
        this.mContext.registerReceiverAsUser(this.mReceiver, UserHandle.ALL, localeFilter, null, this.mHandler);
        injectRegisterUidObserver(this.mUidObserver, 3);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getStatStartTime() {
        return this.mStatLogger.getTime();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void logDurationStat(int statId, long start) {
        this.mStatLogger.logDurationStat(statId, start);
    }

    public String injectGetLocaleTagsForUser(int userId) {
        return LocaleList.getDefault().toLanguageTags();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.pm.ShortcutService$3  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass3 extends IUidObserver.Stub {
        AnonymousClass3() {
        }

        public void onUidStateChanged(final int uid, final int procState, long procStateSeq) {
            ShortcutService.this.injectPostToHandler(new Runnable() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$3$n_VdEzyBcjs0pGZO8GnB0FoTgR0
                @Override // java.lang.Runnable
                public final void run() {
                    ShortcutService.this.handleOnUidStateChanged(uid, procState);
                }
            });
        }

        public void onUidGone(final int uid, boolean disabled) {
            ShortcutService.this.injectPostToHandler(new Runnable() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$3$WghiV-HLnzJqZabObC5uHCmb960
                @Override // java.lang.Runnable
                public final void run() {
                    ShortcutService.this.handleOnUidStateChanged(uid, 19);
                }
            });
        }

        public void onUidActive(int uid) {
        }

        public void onUidIdle(int uid, boolean disabled) {
        }

        public void onUidCachedChanged(int uid, boolean cached) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleOnUidStateChanged(int uid, int procState) {
        synchronized (this.mLock) {
            this.mUidState.put(uid, procState);
            if (isProcessStateForeground(procState)) {
                this.mUidLastForegroundElapsedTime.put(uid, injectElapsedRealtime());
            }
        }
    }

    private boolean isProcessStateForeground(int processState) {
        return processState <= 4;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public boolean isUidForegroundLocked(int uid) {
        if (uid == 1000 || isProcessStateForeground(this.mUidState.get(uid, 19))) {
            return true;
        }
        return isProcessStateForeground(this.mActivityManagerInternal.getUidProcessState(uid));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public long getUidLastForegroundElapsedTimeLocked(int uid) {
        return this.mUidLastForegroundElapsedTime.get(uid);
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        final ShortcutService mService;

        public Lifecycle(Context context) {
            super(context);
            this.mService = new ShortcutService(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService(ShortcutService.KEY_SHORTCUT, this.mService);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            this.mService.onBootPhase(phase);
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userHandle) {
            this.mService.handleStopUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userId) {
            this.mService.handleUnlockUser(userId);
        }
    }

    void onBootPhase(int phase) {
        if (phase == 480) {
            initialize();
        } else if (phase == 1000) {
            this.mBootCompleted.set(true);
        }
    }

    void handleUnlockUser(final int userId) {
        synchronized (this.mUnlockedUsers) {
            this.mUnlockedUsers.put(userId, true);
        }
        final long start = getStatStartTime();
        injectRunOnNewThread(new Runnable() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$QFWliMhWloedhnaZCwVKaqKPVb4
            @Override // java.lang.Runnable
            public final void run() {
                ShortcutService.lambda$handleUnlockUser$0(ShortcutService.this, start, userId);
            }
        });
    }

    public static /* synthetic */ void lambda$handleUnlockUser$0(ShortcutService shortcutService, long start, int userId) {
        synchronized (shortcutService.mLock) {
            shortcutService.logDurationStat(15, start);
            shortcutService.getUserShortcutsLocked(userId);
        }
    }

    void handleStopUser(int userId) {
        synchronized (this.mLock) {
            unloadUserLocked(userId);
            synchronized (this.mUnlockedUsers) {
                this.mUnlockedUsers.put(userId, false);
            }
        }
    }

    @GuardedBy("mLock")
    private void unloadUserLocked(int userId) {
        saveDirtyInfo();
        this.mUsers.delete(userId);
    }

    private AtomicFile getBaseStateFile() {
        File path = new File(injectSystemDataPath(), FILENAME_BASE_STATE);
        path.mkdirs();
        return new AtomicFile(path);
    }

    private void initialize() {
        synchronized (this.mLock) {
            loadConfigurationLocked();
            loadBaseStateLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadConfigurationLocked() {
        updateConfigurationLocked(injectShortcutManagerConstants());
    }

    @VisibleForTesting
    boolean updateConfigurationLocked(String config) {
        int i;
        boolean result = true;
        KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(config);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Bad shortcut manager settings", e);
            result = false;
        }
        this.mSaveDelayMillis = Math.max(0, (int) parser.getLong(ConfigConstants.KEY_SAVE_DELAY_MILLIS, 3000L));
        this.mResetInterval = Math.max(1L, parser.getLong(ConfigConstants.KEY_RESET_INTERVAL_SEC, (long) DEFAULT_RESET_INTERVAL_SEC) * 1000);
        this.mMaxUpdatesPerInterval = Math.max(0, (int) parser.getLong(ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL, 10L));
        this.mMaxShortcuts = Math.max(0, (int) parser.getLong(ConfigConstants.KEY_MAX_SHORTCUTS, 5L));
        if (injectIsLowRamDevice()) {
            i = (int) parser.getLong(ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM, 48L);
        } else {
            i = (int) parser.getLong(ConfigConstants.KEY_MAX_ICON_DIMENSION_DP, 96L);
        }
        int iconDimensionDp = Math.max(1, i);
        this.mMaxIconDimension = injectDipToPixel(iconDimensionDp);
        this.mIconPersistFormat = Bitmap.CompressFormat.valueOf(parser.getString(ConfigConstants.KEY_ICON_FORMAT, DEFAULT_ICON_PERSIST_FORMAT));
        this.mIconPersistQuality = (int) parser.getLong(ConfigConstants.KEY_ICON_QUALITY, 100L);
        return result;
    }

    @VisibleForTesting
    String injectShortcutManagerConstants() {
        return Settings.Global.getString(this.mContext.getContentResolver(), "shortcut_manager_constants");
    }

    @VisibleForTesting
    int injectDipToPixel(int dip) {
        return (int) TypedValue.applyDimension(1, dip, this.mContext.getResources().getDisplayMetrics());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String parseStringAttribute(XmlPullParser parser, String attribute) {
        return parser.getAttributeValue(null, attribute);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean parseBooleanAttribute(XmlPullParser parser, String attribute) {
        return parseLongAttribute(parser, attribute) == 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean parseBooleanAttribute(XmlPullParser parser, String attribute, boolean def) {
        return parseLongAttribute(parser, attribute, def ? 1L : 0L) == 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int parseIntAttribute(XmlPullParser parser, String attribute) {
        return (int) parseLongAttribute(parser, attribute);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int parseIntAttribute(XmlPullParser parser, String attribute, int def) {
        return (int) parseLongAttribute(parser, attribute, def);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static long parseLongAttribute(XmlPullParser parser, String attribute) {
        return parseLongAttribute(parser, attribute, 0L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static long parseLongAttribute(XmlPullParser parser, String attribute, long def) {
        String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return def;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Error parsing long " + value);
            return def;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ComponentName parseComponentNameAttribute(XmlPullParser parser, String attribute) {
        String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return ComponentName.unflattenFromString(value);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Intent parseIntentAttributeNoDefault(XmlPullParser parser, String attribute) {
        String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            Intent parsed = Intent.parseUri(value, 0);
            return parsed;
        } catch (URISyntaxException e) {
            Slog.e(TAG, "Error parsing intent", e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Intent parseIntentAttribute(XmlPullParser parser, String attribute) {
        Intent parsed = parseIntentAttributeNoDefault(parser, attribute);
        if (parsed == null) {
            return new Intent("android.intent.action.VIEW");
        }
        return parsed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeTagValue(XmlSerializer out, String tag, String value) throws IOException {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        out.startTag(null, tag);
        out.attribute(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    static void writeTagValue(XmlSerializer out, String tag, long value) throws IOException {
        writeTagValue(out, tag, Long.toString(value));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeTagValue(XmlSerializer out, String tag, ComponentName name) throws IOException {
        if (name == null) {
            return;
        }
        writeTagValue(out, tag, name.flattenToString());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeTagExtra(XmlSerializer out, String tag, PersistableBundle bundle) throws IOException, XmlPullParserException {
        if (bundle == null) {
            return;
        }
        out.startTag(null, tag);
        bundle.saveToXml(out);
        out.endTag(null, tag);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeAttr(XmlSerializer out, String name, CharSequence value) throws IOException {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        out.attribute(null, name, value.toString());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeAttr(XmlSerializer out, String name, long value) throws IOException {
        writeAttr(out, name, String.valueOf(value));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeAttr(XmlSerializer out, String name, boolean value) throws IOException {
        if (value) {
            writeAttr(out, name, "1");
        } else {
            writeAttr(out, name, "0");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeAttr(XmlSerializer out, String name, ComponentName comp) throws IOException {
        if (comp == null) {
            return;
        }
        writeAttr(out, name, comp.flattenToString());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeAttr(XmlSerializer out, String name, Intent intent) throws IOException {
        if (intent == null) {
            return;
        }
        writeAttr(out, name, intent.toUri(0));
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    void saveBaseStateLocked() {
        AtomicFile file = getBaseStateFile();
        FileOutputStream outs = null;
        try {
            outs = file.startWrite();
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(outs, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.startTag(null, TAG_ROOT);
            writeTagValue((XmlSerializer) fastXmlSerializer, TAG_LAST_RESET_TIME, this.mRawLastResetTime);
            fastXmlSerializer.endTag(null, TAG_ROOT);
            fastXmlSerializer.endDocument();
            file.finishWrite(outs);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:15:0x0039, code lost:
        android.util.Slog.e(com.android.server.pm.ShortcutService.TAG, "Invalid root tag: " + r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x004f, code lost:
        if (r3 == null) goto L39;
     */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x0051, code lost:
        r3.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:18:0x0054, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x006c, code lost:
        android.util.Slog.e(com.android.server.pm.ShortcutService.TAG, "Invalid tag: " + r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:71:?, code lost:
        return;
     */
    @com.android.internal.annotations.GuardedBy("mLock")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void loadBaseStateLocked() {
        /*
            r12 = this;
            r0 = 0
            r12.mRawLastResetTime = r0
            android.util.AtomicFile r2 = r12.getBaseStateFile()
            java.io.FileInputStream r3 = r2.openRead()     // Catch: java.lang.Throwable -> La9 java.io.FileNotFoundException -> Lc7
            r4 = 0
            org.xmlpull.v1.XmlPullParser r5 = android.util.Xml.newPullParser()     // Catch: java.lang.Throwable -> L96
            java.nio.charset.Charset r6 = java.nio.charset.StandardCharsets.UTF_8     // Catch: java.lang.Throwable -> L96
            java.lang.String r6 = r6.name()     // Catch: java.lang.Throwable -> L96
            r5.setInput(r3, r6)     // Catch: java.lang.Throwable -> L96
        L1a:
            int r6 = r5.next()     // Catch: java.lang.Throwable -> L96
            r7 = r6
            r8 = 1
            if (r6 == r8) goto L8e
            r6 = 2
            if (r7 == r6) goto L26
            goto L1a
        L26:
            int r6 = r5.getDepth()     // Catch: java.lang.Throwable -> L96
            java.lang.String r9 = r5.getName()     // Catch: java.lang.Throwable -> L96
            if (r6 != r8) goto L55
            java.lang.String r8 = "root"
            boolean r8 = r8.equals(r9)     // Catch: java.lang.Throwable -> L96
            if (r8 != 0) goto L1a
            java.lang.String r8 = "ShortcutService"
            java.lang.StringBuilder r10 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L96
            r10.<init>()     // Catch: java.lang.Throwable -> L96
            java.lang.String r11 = "Invalid root tag: "
            r10.append(r11)     // Catch: java.lang.Throwable -> L96
            r10.append(r9)     // Catch: java.lang.Throwable -> L96
            java.lang.String r10 = r10.toString()     // Catch: java.lang.Throwable -> L96
            android.util.Slog.e(r8, r10)     // Catch: java.lang.Throwable -> L96
            if (r3 == 0) goto L54
            r3.close()     // Catch: java.lang.Throwable -> La9 java.lang.Throwable -> La9 java.io.FileNotFoundException -> Lc7
        L54:
            return
        L55:
            r8 = -1
            int r10 = r9.hashCode()     // Catch: java.lang.Throwable -> L96
            r11 = -68726522(0xfffffffffbe75106, float:-2.4021278E36)
            if (r10 == r11) goto L60
            goto L6a
        L60:
            java.lang.String r10 = "last_reset_time"
            boolean r10 = r9.equals(r10)     // Catch: java.lang.Throwable -> L96
            if (r10 == 0) goto L6a
            r8 = 0
        L6a:
            if (r8 == 0) goto L83
            java.lang.String r8 = "ShortcutService"
            java.lang.StringBuilder r10 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L96
            r10.<init>()     // Catch: java.lang.Throwable -> L96
            java.lang.String r11 = "Invalid tag: "
            r10.append(r11)     // Catch: java.lang.Throwable -> L96
            r10.append(r9)     // Catch: java.lang.Throwable -> L96
            java.lang.String r10 = r10.toString()     // Catch: java.lang.Throwable -> L96
            android.util.Slog.e(r8, r10)     // Catch: java.lang.Throwable -> L96
            goto L8d
        L83:
            java.lang.String r8 = "value"
            long r10 = parseLongAttribute(r5, r8)     // Catch: java.lang.Throwable -> L96
            r12.mRawLastResetTime = r10     // Catch: java.lang.Throwable -> L96
        L8d:
            goto L1a
        L8e:
            if (r3 == 0) goto Lc8
            r3.close()     // Catch: java.lang.Throwable -> La9 java.lang.Throwable -> La9 java.io.FileNotFoundException -> Lc7
            goto Lc8
        L94:
            r5 = move-exception
            goto L98
        L96:
            r4 = move-exception
            throw r4     // Catch: java.lang.Throwable -> L94
        L98:
            if (r3 == 0) goto La8
            if (r4 == 0) goto La5
            r3.close()     // Catch: java.lang.Throwable -> La0
            goto La8
        La0:
            r6 = move-exception
            r4.addSuppressed(r6)     // Catch: java.lang.Throwable -> La9 java.lang.Throwable -> La9 java.io.FileNotFoundException -> Lc7
            goto La8
        La5:
            r3.close()     // Catch: java.lang.Throwable -> La9 java.lang.Throwable -> La9 java.io.FileNotFoundException -> Lc7
        La8:
            throw r5     // Catch: java.lang.Throwable -> La9 java.lang.Throwable -> La9 java.io.FileNotFoundException -> Lc7
        La9:
            r3 = move-exception
            java.lang.String r4 = "ShortcutService"
            java.lang.StringBuilder r5 = new java.lang.StringBuilder
            r5.<init>()
            java.lang.String r6 = "Failed to read file "
            r5.append(r6)
            java.io.File r6 = r2.getBaseFile()
            r5.append(r6)
            java.lang.String r5 = r5.toString()
            android.util.Slog.e(r4, r5, r3)
            r12.mRawLastResetTime = r0
            goto Lc9
        Lc7:
            r0 = move-exception
        Lc8:
        Lc9:
            r12.getLastResetTimeLocked()
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutService.loadBaseStateLocked():void");
    }

    @VisibleForTesting
    final File getUserFile(int userId) {
        return new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
    }

    @GuardedBy("mLock")
    private void saveUserLocked(int userId) {
        File path = getUserFile(userId);
        this.mShortcutBitmapSaver.waitForAllSavesLocked();
        path.getParentFile().mkdirs();
        AtomicFile file = new AtomicFile(path);
        FileOutputStream os = null;
        try {
            os = file.startWrite();
            saveUserInternalLocked(userId, os, false);
            file.finishWrite(os);
            cleanupDanglingBitmapDirectoriesLocked(userId);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(os);
        }
    }

    @GuardedBy("mLock")
    private void saveUserInternalLocked(int userId, OutputStream os, boolean forBackup) throws IOException, XmlPullParserException {
        BufferedOutputStream bos = new BufferedOutputStream(os);
        FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
        fastXmlSerializer.setOutput(bos, StandardCharsets.UTF_8.name());
        fastXmlSerializer.startDocument(null, true);
        getUserShortcutsLocked(userId).saveToXml(fastXmlSerializer, forBackup);
        fastXmlSerializer.endDocument();
        bos.flush();
        os.flush();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static IOException throwForInvalidTag(int depth, String tag) throws IOException {
        throw new IOException(String.format("Invalid tag '%s' found at depth %d", tag, Integer.valueOf(depth)));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void warnForInvalidTag(int depth, String tag) throws IOException {
        Slog.w(TAG, String.format("Invalid tag '%s' found at depth %d", tag, Integer.valueOf(depth)));
    }

    private ShortcutUser loadUserLocked(int userId) {
        File path = getUserFile(userId);
        AtomicFile file = new AtomicFile(path);
        try {
            FileInputStream in = file.openRead();
            try {
                ShortcutUser ret = loadUserInternal(userId, in, false);
                return ret;
            } catch (InvalidFileFormatException | IOException | XmlPullParserException e) {
                Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);
                return null;
            } finally {
                IoUtils.closeQuietly(in);
            }
        } catch (FileNotFoundException e2) {
            return null;
        }
    }

    private ShortcutUser loadUserInternal(int userId, InputStream is, boolean fromBackup) throws XmlPullParserException, IOException, InvalidFileFormatException {
        BufferedInputStream bis = new BufferedInputStream(is);
        ShortcutUser ret = null;
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(bis, StandardCharsets.UTF_8.name());
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type == 2) {
                    int depth = parser.getDepth();
                    String tag = parser.getName();
                    if (depth == 1 && "user".equals(tag)) {
                        ret = ShortcutUser.loadFromXml(this, parser, userId, fromBackup);
                    } else {
                        throwForInvalidTag(depth, tag);
                    }
                }
            } else {
                return ret;
            }
        }
    }

    private void scheduleSaveBaseState() {
        scheduleSaveInner(-10000);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleSaveUser(int userId) {
        scheduleSaveInner(userId);
    }

    private void scheduleSaveInner(int userId) {
        synchronized (this.mLock) {
            if (!this.mDirtyUserIds.contains(Integer.valueOf(userId))) {
                this.mDirtyUserIds.add(Integer.valueOf(userId));
            }
        }
        this.mHandler.removeCallbacks(this.mSaveDirtyInfoRunner);
        this.mHandler.postDelayed(this.mSaveDirtyInfoRunner, this.mSaveDelayMillis);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public void saveDirtyInfo() {
        try {
            synchronized (this.mLock) {
                for (int i = this.mDirtyUserIds.size() - 1; i >= 0; i--) {
                    int userId = this.mDirtyUserIds.get(i).intValue();
                    if (userId == -10000) {
                        saveBaseStateLocked();
                    } else {
                        saveUserLocked(userId);
                    }
                }
                this.mDirtyUserIds.clear();
            }
        } catch (Exception e) {
            wtf("Exception in saveDirtyInfo", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public long getLastResetTimeLocked() {
        updateTimesLocked();
        return this.mRawLastResetTime;
    }

    @GuardedBy("mLock")
    long getNextResetTimeLocked() {
        updateTimesLocked();
        return this.mRawLastResetTime + this.mResetInterval;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isClockValid(long time) {
        return time >= 1420070400;
    }

    @GuardedBy("mLock")
    private void updateTimesLocked() {
        long now = injectCurrentTimeMillis();
        long prevLastResetTime = this.mRawLastResetTime;
        if (this.mRawLastResetTime == 0) {
            this.mRawLastResetTime = now;
        } else if (now < this.mRawLastResetTime) {
            if (isClockValid(now)) {
                Slog.w(TAG, "Clock rewound");
                this.mRawLastResetTime = now;
            }
        } else if (this.mRawLastResetTime + this.mResetInterval <= now) {
            long offset = this.mRawLastResetTime % this.mResetInterval;
            this.mRawLastResetTime = ((now / this.mResetInterval) * this.mResetInterval) + offset;
        }
        long offset2 = this.mRawLastResetTime;
        if (prevLastResetTime != offset2) {
            scheduleSaveBaseState();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean isUserUnlockedL(int userId) {
        synchronized (this.mUnlockedUsers) {
            if (this.mUnlockedUsers.get(userId)) {
                return true;
            }
            return this.mUserManagerInternal.isUserUnlockingOrUnlocked(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void throwIfUserLockedL(int userId) {
        if (!isUserUnlockedL(userId)) {
            throw new IllegalStateException("User " + userId + " is locked or not running");
        }
    }

    @GuardedBy("mLock")
    private boolean isUserLoadedLocked(int userId) {
        return this.mUsers.get(userId) != null;
    }

    @GuardedBy("mLock")
    ShortcutUser getUserShortcutsLocked(int userId) {
        if (!isUserUnlockedL(userId)) {
            wtf("User still locked");
        }
        ShortcutUser userPackages = this.mUsers.get(userId);
        if (userPackages == null) {
            userPackages = loadUserLocked(userId);
            if (userPackages == null) {
                userPackages = new ShortcutUser(this, userId);
            }
            this.mUsers.put(userId, userPackages);
            checkPackageChanges(userId);
        }
        return userPackages;
    }

    @GuardedBy("mLock")
    ShortcutNonPersistentUser getNonPersistentUserLocked(int userId) {
        ShortcutNonPersistentUser ret = this.mShortcutNonPersistentUsers.get(userId);
        if (ret == null) {
            ShortcutNonPersistentUser ret2 = new ShortcutNonPersistentUser(this, userId);
            this.mShortcutNonPersistentUsers.put(userId, ret2);
            return ret2;
        }
        return ret;
    }

    @GuardedBy("mLock")
    void forEachLoadedUserLocked(Consumer<ShortcutUser> c) {
        for (int i = this.mUsers.size() - 1; i >= 0; i--) {
            c.accept(this.mUsers.valueAt(i));
        }
    }

    @GuardedBy("mLock")
    ShortcutPackage getPackageShortcutsLocked(String packageName, int userId) {
        return getUserShortcutsLocked(userId).getPackageShortcuts(packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public ShortcutPackage getPackageShortcutsForPublisherLocked(String packageName, int userId) {
        ShortcutPackage ret = getUserShortcutsLocked(userId).getPackageShortcuts(packageName);
        ret.getUser().onCalledByPublisher(packageName);
        return ret;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public ShortcutLauncher getLauncherShortcutsLocked(String packageName, int ownerUserId, int launcherUserId) {
        return getUserShortcutsLocked(ownerUserId).getLauncherShortcuts(packageName, launcherUserId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeIconLocked(ShortcutInfo shortcut) {
        this.mShortcutBitmapSaver.removeIcon(shortcut);
    }

    public void cleanupBitmapsForPackage(int userId, String packageName) {
        File packagePath = new File(getUserBitmapFilePath(userId), packageName);
        if (!packagePath.isDirectory()) {
            return;
        }
        if (!FileUtils.deleteContents(packagePath) || !packagePath.delete()) {
            Slog.w(TAG, "Unable to remove directory " + packagePath);
        }
    }

    @GuardedBy("mLock")
    private void cleanupDanglingBitmapDirectoriesLocked(int userId) {
        long start = getStatStartTime();
        ShortcutUser user = getUserShortcutsLocked(userId);
        File bitmapDir = getUserBitmapFilePath(userId);
        File[] children = bitmapDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String packageName = child.getName();
                if (!user.hasPackage(packageName)) {
                    cleanupBitmapsForPackage(userId, packageName);
                } else {
                    cleanupDanglingBitmapFilesLocked(userId, user, packageName, child);
                }
            }
        }
        logDurationStat(5, start);
    }

    private void cleanupDanglingBitmapFilesLocked(int userId, ShortcutUser user, String packageName, File path) {
        File[] listFiles;
        ArraySet<String> usedFiles = user.getPackageShortcuts(packageName).getUsedBitmapFiles();
        for (File child : path.listFiles()) {
            if (child.isFile()) {
                String name = child.getName();
                if (!usedFiles.contains(name)) {
                    child.delete();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class FileOutputStreamWithPath extends FileOutputStream {
        private final File mFile;

        public FileOutputStreamWithPath(File file) throws FileNotFoundException {
            super(file);
            this.mFile = file;
        }

        public File getFile() {
            return this.mFile;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public FileOutputStreamWithPath openIconFileForWrite(int userId, ShortcutInfo shortcut) throws IOException {
        String str;
        File packagePath = new File(getUserBitmapFilePath(userId), shortcut.getPackage());
        if (!packagePath.isDirectory()) {
            packagePath.mkdirs();
            if (!packagePath.isDirectory()) {
                throw new IOException("Unable to create directory " + packagePath);
            }
            SELinux.restorecon(packagePath);
        }
        String baseName = String.valueOf(injectCurrentTimeMillis());
        int suffix = 0;
        while (true) {
            StringBuilder sb = new StringBuilder();
            if (suffix == 0) {
                str = baseName;
            } else {
                str = baseName + "_" + suffix;
            }
            sb.append(str);
            sb.append(".png");
            String filename = sb.toString();
            File file = new File(packagePath, filename);
            if (file.exists()) {
                suffix++;
            } else {
                return new FileOutputStreamWithPath(file);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveIconAndFixUpShortcutLocked(ShortcutInfo shortcut) {
        if (shortcut.hasIconFile() || shortcut.hasIconResource()) {
            return;
        }
        long token = injectClearCallingIdentity();
        try {
            removeIconLocked(shortcut);
            Icon icon = shortcut.getIcon();
            if (icon == null) {
                return;
            }
            int maxIconDimension = this.mMaxIconDimension;
            int type = icon.getType();
            if (type != 5) {
                switch (type) {
                    case 1:
                        icon.getBitmap();
                        break;
                    case 2:
                        injectValidateIconResPackage(shortcut, icon);
                        shortcut.setIconResourceId(icon.getResId());
                        shortcut.addFlags(4);
                        shortcut.clearIcon();
                        return;
                    default:
                        throw ShortcutInfo.getInvalidIconException();
                }
            } else {
                icon.getBitmap();
                maxIconDimension = (int) (maxIconDimension * (1.0f + (2.0f * AdaptiveIconDrawable.getExtraInsetFraction())));
            }
            this.mShortcutBitmapSaver.saveBitmapLocked(shortcut, maxIconDimension, this.mIconPersistFormat, this.mIconPersistQuality);
            shortcut.clearIcon();
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    void injectValidateIconResPackage(ShortcutInfo shortcut, Icon icon) {
        if (!shortcut.getPackage().equals(icon.getResPackage())) {
            throw new IllegalArgumentException("Icon resource must reside in shortcut owner package");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Bitmap shrinkBitmap(Bitmap in, int maxSize) {
        int ow = in.getWidth();
        int oh = in.getHeight();
        if (ow <= maxSize && oh <= maxSize) {
            return in;
        }
        int longerDimension = Math.max(ow, oh);
        int nw = (ow * maxSize) / longerDimension;
        int nh = (oh * maxSize) / longerDimension;
        Bitmap scaledBitmap = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(scaledBitmap);
        RectF dst = new RectF(0.0f, 0.0f, nw, nh);
        c.drawBitmap(in, (Rect) null, dst, (Paint) null);
        return scaledBitmap;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void fixUpShortcutResourceNamesAndValues(ShortcutInfo si) {
        Resources publisherRes = injectGetResourcesForApplicationAsUser(si.getPackage(), si.getUserId());
        if (publisherRes != null) {
            long start = getStatStartTime();
            try {
                si.lookupAndFillInResourceNames(publisherRes);
                logDurationStat(10, start);
                si.resolveResourceStrings(publisherRes);
            } catch (Throwable th) {
                logDurationStat(10, start);
                throw th;
            }
        }
    }

    private boolean isCallerSystem() {
        int callingUid = injectBinderCallingUid();
        return UserHandle.isSameApp(callingUid, 1000);
    }

    private boolean isCallerShell() {
        int callingUid = injectBinderCallingUid();
        return callingUid == 2000 || callingUid == 0;
    }

    private void enforceSystemOrShell() {
        if (!isCallerSystem() && !isCallerShell()) {
            throw new SecurityException("Caller must be system or shell");
        }
    }

    private void enforceShell() {
        if (!isCallerShell()) {
            throw new SecurityException("Caller must be shell");
        }
    }

    private void enforceSystem() {
        if (!isCallerSystem()) {
            throw new SecurityException("Caller must be system");
        }
    }

    private void enforceResetThrottlingPermission() {
        if (isCallerSystem()) {
            return;
        }
        enforceCallingOrSelfPermission("android.permission.RESET_SHORTCUT_MANAGER_THROTTLING", null);
    }

    private void enforceCallingOrSelfPermission(String permission, String message) {
        if (isCallerSystem()) {
            return;
        }
        injectEnforceCallingPermission(permission, message);
    }

    @VisibleForTesting
    void injectEnforceCallingPermission(String permission, String message) {
        this.mContext.enforceCallingPermission(permission, message);
    }

    private void verifyCaller(String packageName, int userId) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");
        if (isCallerSystem()) {
            return;
        }
        int callingUid = injectBinderCallingUid();
        if (UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Invalid user-ID");
        }
        if (injectGetPackageUid(packageName, userId) != callingUid) {
            throw new SecurityException("Calling package name mismatch");
        }
        Preconditions.checkState(!isEphemeralApp(packageName, userId), "Ephemeral apps can't use ShortcutManager");
    }

    private void verifyShortcutInfoPackage(String callerPackage, ShortcutInfo si) {
        if (si != null && !Objects.equals(callerPackage, si.getPackage())) {
            EventLog.writeEvent(1397638484, "109824443", -1, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            throw new SecurityException("Shortcut package name mismatch");
        }
    }

    private void verifyShortcutInfoPackages(String callerPackage, List<ShortcutInfo> list) {
        int size = list.size();
        for (int i = 0; i < size; i++) {
            verifyShortcutInfoPackage(callerPackage, list.get(i));
        }
    }

    void injectPostToHandler(Runnable r) {
        this.mHandler.post(r);
    }

    void injectRunOnNewThread(Runnable r) {
        new Thread(r).start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void enforceMaxActivityShortcuts(int numShortcuts) {
        if (numShortcuts > this.mMaxShortcuts) {
            throw new IllegalArgumentException("Max number of dynamic shortcuts exceeded");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getMaxActivityShortcuts() {
        return this.mMaxShortcuts;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void packageShortcutsChanged(String packageName, int userId) {
        notifyListeners(packageName, userId);
        scheduleSaveUser(userId);
    }

    private void notifyListeners(final String packageName, final int userId) {
        injectPostToHandler(new Runnable() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$DzwraUeMWDwA0XDfFxd3sGOsA0E
            @Override // java.lang.Runnable
            public final void run() {
                ShortcutService.lambda$notifyListeners$1(ShortcutService.this, userId, packageName);
            }
        });
    }

    public static /* synthetic */ void lambda$notifyListeners$1(ShortcutService shortcutService, int userId, String packageName) {
        try {
            synchronized (shortcutService.mLock) {
                if (shortcutService.isUserUnlockedL(userId)) {
                    ArrayList<ShortcutServiceInternal.ShortcutChangeListener> copy = new ArrayList<>(shortcutService.mListeners);
                    for (int i = copy.size() - 1; i >= 0; i--) {
                        copy.get(i).onShortcutChanged(packageName, userId);
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private void fixUpIncomingShortcutInfo(ShortcutInfo shortcut, boolean forUpdate, boolean forPinRequest) {
        if (shortcut.isReturnedByServer()) {
            Log.w(TAG, "Re-publishing ShortcutInfo returned by server is not supported. Some information such as icon may lost from shortcut.");
        }
        Preconditions.checkNotNull(shortcut, "Null shortcut detected");
        if (shortcut.getActivity() != null) {
            boolean equals = shortcut.getPackage().equals(shortcut.getActivity().getPackageName());
            Preconditions.checkState(equals, "Cannot publish shortcut: activity " + shortcut.getActivity() + " does not belong to package " + shortcut.getPackage());
            boolean injectIsMainActivity = injectIsMainActivity(shortcut.getActivity(), shortcut.getUserId());
            Preconditions.checkState(injectIsMainActivity, "Cannot publish shortcut: activity " + shortcut.getActivity() + " is not main activity");
        }
        if (!forUpdate) {
            shortcut.enforceMandatoryFields(forPinRequest);
            if (!forPinRequest) {
                Preconditions.checkState(shortcut.getActivity() != null, "Cannot publish shortcut: target activity is not set");
            }
        }
        if (shortcut.getIcon() != null) {
            ShortcutInfo.validateIcon(shortcut.getIcon());
        }
        shortcut.replaceFlags(0);
    }

    private void fixUpIncomingShortcutInfo(ShortcutInfo shortcut, boolean forUpdate) {
        fixUpIncomingShortcutInfo(shortcut, forUpdate, false);
    }

    public void validateShortcutForPinRequest(ShortcutInfo shortcut) {
        fixUpIncomingShortcutInfo(shortcut, false, true);
    }

    private void fillInDefaultActivity(List<ShortcutInfo> shortcuts) {
        ComponentName defaultActivity = null;
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = shortcuts.get(i);
            if (si.getActivity() == null) {
                if (defaultActivity == null) {
                    defaultActivity = injectGetDefaultMainActivity(si.getPackage(), si.getUserId());
                    Preconditions.checkState(defaultActivity != null, "Launcher activity not found for package " + si.getPackage());
                }
                si.setActivity(defaultActivity);
            }
        }
    }

    private void assignImplicitRanks(List<ShortcutInfo> shortcuts) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            shortcuts.get(i).setImplicitRank(i);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<ShortcutInfo> setReturnedByServer(List<ShortcutInfo> shortcuts) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            shortcuts.get(i).setReturnedByServer();
        }
        return shortcuts;
    }

    public boolean setDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList, int userId) {
        verifyCaller(packageName, userId);
        List<ShortcutInfo> newShortcuts = shortcutInfoList.getList();
        verifyShortcutInfoPackages(packageName, newShortcuts);
        int size = newShortcuts.size();
        boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(injectBinderCallingPid(), injectBinderCallingUid());
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncluded(newShortcuts, true);
            fillInDefaultActivity(newShortcuts);
            ps.enforceShortcutCountsBeforeOperation(newShortcuts, 0);
            if (!ps.tryApiCall(unlimited)) {
                return false;
            }
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);
            for (int i = 0; i < size; i++) {
                fixUpIncomingShortcutInfo(newShortcuts.get(i), false);
            }
            ps.deleteAllDynamicShortcuts(true);
            for (int i2 = 0; i2 < size; i2++) {
                ShortcutInfo newShortcut = newShortcuts.get(i2);
                ps.addOrReplaceDynamicShortcut(newShortcut);
            }
            ps.adjustRanks();
            packageShortcutsChanged(packageName, userId);
            verifyStates();
            return true;
        }
    }

    public boolean updateShortcuts(String packageName, ParceledListSlice shortcutInfoList, int userId) {
        verifyCaller(packageName, userId);
        List<ShortcutInfo> newShortcuts = shortcutInfoList.getList();
        verifyShortcutInfoPackages(packageName, newShortcuts);
        int size = newShortcuts.size();
        boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(injectBinderCallingPid(), injectBinderCallingUid());
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncluded(newShortcuts, true);
            ps.enforceShortcutCountsBeforeOperation(newShortcuts, 2);
            if (ps.tryApiCall(unlimited)) {
                ps.clearAllImplicitRanks();
                assignImplicitRanks(newShortcuts);
                for (int i = 0; i < size; i++) {
                    ShortcutInfo source = newShortcuts.get(i);
                    fixUpIncomingShortcutInfo(source, true);
                    ShortcutInfo target = ps.findShortcutById(source.getId());
                    if (target != null && target.isVisibleToPublisher()) {
                        if (target.isEnabled() != source.isEnabled()) {
                            Slog.w(TAG, "ShortcutInfo.enabled cannot be changed with updateShortcuts()");
                        }
                        if (source.hasRank()) {
                            target.setRankChanged();
                            target.setImplicitRank(source.getImplicitRank());
                        }
                        boolean replacingIcon = source.getIcon() != null;
                        if (replacingIcon) {
                            removeIconLocked(target);
                        }
                        target.copyNonNullFieldsFrom(source);
                        target.setTimestamp(injectCurrentTimeMillis());
                        if (replacingIcon) {
                            saveIconAndFixUpShortcutLocked(target);
                        }
                        if (replacingIcon || source.hasStringResources()) {
                            fixUpShortcutResourceNamesAndValues(target);
                        }
                    }
                }
                ps.adjustRanks();
                packageShortcutsChanged(packageName, userId);
                verifyStates();
                return true;
            }
            return false;
        }
    }

    public boolean addDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList, int userId) {
        verifyCaller(packageName, userId);
        List<ShortcutInfo> newShortcuts = shortcutInfoList.getList();
        verifyShortcutInfoPackages(packageName, newShortcuts);
        int size = newShortcuts.size();
        boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(injectBinderCallingPid(), injectBinderCallingUid());
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncluded(newShortcuts, true);
            fillInDefaultActivity(newShortcuts);
            ps.enforceShortcutCountsBeforeOperation(newShortcuts, 1);
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);
            if (ps.tryApiCall(unlimited)) {
                for (int i = 0; i < size; i++) {
                    ShortcutInfo newShortcut = newShortcuts.get(i);
                    fixUpIncomingShortcutInfo(newShortcut, false);
                    newShortcut.setRankChanged();
                    ps.addOrReplaceDynamicShortcut(newShortcut);
                }
                ps.adjustRanks();
                packageShortcutsChanged(packageName, userId);
                verifyStates();
                return true;
            }
            return false;
        }
    }

    public boolean requestPinShortcut(String packageName, ShortcutInfo shortcut, IntentSender resultIntent, int userId) {
        Preconditions.checkNotNull(shortcut);
        Preconditions.checkArgument(shortcut.isEnabled(), "Shortcut must be enabled");
        return requestPinItem(packageName, userId, shortcut, null, null, resultIntent);
    }

    public Intent createShortcutResultIntent(String packageName, ShortcutInfo shortcut, int userId) throws RemoteException {
        Intent ret;
        Preconditions.checkNotNull(shortcut);
        Preconditions.checkArgument(shortcut.isEnabled(), "Shortcut must be enabled");
        verifyCaller(packageName, userId);
        verifyShortcutInfoPackage(packageName, shortcut);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ret = this.mShortcutRequestPinProcessor.createShortcutResultIntent(shortcut, userId);
        }
        verifyStates();
        return ret;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean requestPinItem(String packageName, int userId, ShortcutInfo shortcut, AppWidgetProviderInfo appWidget, Bundle extras, IntentSender resultIntent) {
        boolean ret;
        verifyCaller(packageName, userId);
        verifyShortcutInfoPackage(packageName, shortcut);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            Preconditions.checkState(isUidForegroundLocked(injectBinderCallingUid()), "Calling application must have a foreground activity or a foreground service");
            if (shortcut != null) {
                ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
                String id = shortcut.getId();
                if (ps.isShortcutExistsAndInvisibleToPublisher(id)) {
                    ps.updateInvisibleShortcutForPinRequestWith(shortcut);
                    packageShortcutsChanged(packageName, userId);
                }
            }
            ret = this.mShortcutRequestPinProcessor.requestPinItemLocked(shortcut, appWidget, extras, userId, resultIntent);
        }
        verifyStates();
        return ret;
    }

    public void disableShortcuts(String packageName, List shortcutIds, CharSequence disabledMessage, int disabledMessageResId, int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutIds, "shortcutIds must be provided");
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncludedWithIds(shortcutIds, true);
            String disabledMessageString = disabledMessage == null ? null : disabledMessage.toString();
            int i = shortcutIds.size() - 1;
            while (true) {
                int i2 = i;
                if (i2 >= 0) {
                    String id = (String) Preconditions.checkStringNotEmpty((String) shortcutIds.get(i2));
                    if (ps.isShortcutExistsAndVisibleToPublisher(id)) {
                        ps.disableWithId(id, disabledMessageString, disabledMessageResId, false, true, 1);
                    }
                    i = i2 - 1;
                } else {
                    ps.adjustRanks();
                }
            }
        }
        packageShortcutsChanged(packageName, userId);
        verifyStates();
    }

    public void enableShortcuts(String packageName, List shortcutIds, int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutIds, "shortcutIds must be provided");
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncludedWithIds(shortcutIds, true);
            int i = shortcutIds.size() - 1;
            while (true) {
                int i2 = i;
                if (i2 >= 0) {
                    String id = (String) Preconditions.checkStringNotEmpty((String) shortcutIds.get(i2));
                    if (ps.isShortcutExistsAndVisibleToPublisher(id)) {
                        ps.enableWithId(id);
                    }
                    i = i2 - 1;
                }
            }
        }
        packageShortcutsChanged(packageName, userId);
        verifyStates();
    }

    public void removeDynamicShortcuts(String packageName, List shortcutIds, int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutIds, "shortcutIds must be provided");
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncludedWithIds(shortcutIds, true);
            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                String id = (String) Preconditions.checkStringNotEmpty((String) shortcutIds.get(i));
                if (ps.isShortcutExistsAndVisibleToPublisher(id)) {
                    ps.deleteDynamicWithId(id, true);
                }
            }
            ps.adjustRanks();
        }
        packageShortcutsChanged(packageName, userId);
        verifyStates();
    }

    public void removeAllDynamicShortcuts(String packageName, int userId) {
        verifyCaller(packageName, userId);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.deleteAllDynamicShortcuts(true);
        }
        packageShortcutsChanged(packageName, userId);
        verifyStates();
    }

    public ParceledListSlice<ShortcutInfo> getDynamicShortcuts(String packageName, int userId) {
        ParceledListSlice<ShortcutInfo> shortcutsWithQueryLocked;
        verifyCaller(packageName, userId);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            shortcutsWithQueryLocked = getShortcutsWithQueryLocked(packageName, userId, 9, new Predicate() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$vv6Ko6L2p38nn3EYcL5PZxcyRyk
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isDynamicVisible;
                    isDynamicVisible = ((ShortcutInfo) obj).isDynamicVisible();
                    return isDynamicVisible;
                }
            });
        }
        return shortcutsWithQueryLocked;
    }

    public ParceledListSlice<ShortcutInfo> getManifestShortcuts(String packageName, int userId) {
        ParceledListSlice<ShortcutInfo> shortcutsWithQueryLocked;
        verifyCaller(packageName, userId);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            shortcutsWithQueryLocked = getShortcutsWithQueryLocked(packageName, userId, 9, new Predicate() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$FW40Da1L1EZJ_usDX0ew1qRMmtc
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isManifestVisible;
                    isManifestVisible = ((ShortcutInfo) obj).isManifestVisible();
                    return isManifestVisible;
                }
            });
        }
        return shortcutsWithQueryLocked;
    }

    public ParceledListSlice<ShortcutInfo> getPinnedShortcuts(String packageName, int userId) {
        ParceledListSlice<ShortcutInfo> shortcutsWithQueryLocked;
        verifyCaller(packageName, userId);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            shortcutsWithQueryLocked = getShortcutsWithQueryLocked(packageName, userId, 9, new Predicate() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$K2g8Oho05j5S7zVOkoQrHzM_Gig
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isPinnedVisible;
                    isPinnedVisible = ((ShortcutInfo) obj).isPinnedVisible();
                    return isPinnedVisible;
                }
            });
        }
        return shortcutsWithQueryLocked;
    }

    @GuardedBy("mLock")
    private ParceledListSlice<ShortcutInfo> getShortcutsWithQueryLocked(String packageName, int userId, int cloneFlags, Predicate<ShortcutInfo> query) {
        ArrayList<ShortcutInfo> ret = new ArrayList<>();
        ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
        ps.findAll(ret, query, cloneFlags);
        return new ParceledListSlice<>(setReturnedByServer(ret));
    }

    public int getMaxShortcutCountPerActivity(String packageName, int userId) throws RemoteException {
        verifyCaller(packageName, userId);
        return this.mMaxShortcuts;
    }

    public int getRemainingCallCount(String packageName, int userId) {
        int apiCallCount;
        verifyCaller(packageName, userId);
        boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(injectBinderCallingPid(), injectBinderCallingUid());
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            apiCallCount = this.mMaxUpdatesPerInterval - ps.getApiCallCount(unlimited);
        }
        return apiCallCount;
    }

    public long getRateLimitResetTime(String packageName, int userId) {
        long nextResetTimeLocked;
        verifyCaller(packageName, userId);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            nextResetTimeLocked = getNextResetTimeLocked();
        }
        return nextResetTimeLocked;
    }

    public int getIconMaxDimensions(String packageName, int userId) {
        int i;
        verifyCaller(packageName, userId);
        synchronized (this.mLock) {
            i = this.mMaxIconDimension;
        }
        return i;
    }

    public void reportShortcutUsed(String packageName, String shortcutId, int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutId);
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            if (ps.findShortcutById(shortcutId) == null) {
                Log.w(TAG, String.format("reportShortcutUsed: package %s doesn't have shortcut %s", packageName, shortcutId));
                return;
            }
            long token = injectClearCallingIdentity();
            try {
                this.mUsageStatsManagerInternal.reportShortcutUsage(packageName, shortcutId, userId);
            } finally {
                injectRestoreCallingIdentity(token);
            }
        }
    }

    public boolean isRequestPinItemSupported(int callingUserId, int requestType) {
        long token = injectClearCallingIdentity();
        try {
            return this.mShortcutRequestPinProcessor.isRequestPinItemSupported(callingUserId, requestType);
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    public void resetThrottling() {
        enforceSystemOrShell();
        resetThrottlingInner(getCallingUserId());
    }

    void resetThrottlingInner(int userId) {
        synchronized (this.mLock) {
            if (!isUserUnlockedL(userId)) {
                Log.w(TAG, "User " + userId + " is locked or not running");
                return;
            }
            getUserShortcutsLocked(userId).resetThrottling();
            scheduleSaveUser(userId);
            Slog.i(TAG, "ShortcutManager: throttling counter reset for user " + userId);
        }
    }

    void resetAllThrottlingInner() {
        synchronized (this.mLock) {
            this.mRawLastResetTime = injectCurrentTimeMillis();
        }
        scheduleSaveBaseState();
        Slog.i(TAG, "ShortcutManager: throttling counter reset for all users");
    }

    public void onApplicationActive(String packageName, int userId) {
        enforceResetThrottlingPermission();
        synchronized (this.mLock) {
            if (isUserUnlockedL(userId)) {
                getPackageShortcutsLocked(packageName, userId).resetRateLimitingForCommandLineNoSaving();
                saveUserLocked(userId);
            }
        }
    }

    boolean hasShortcutHostPermission(String callingPackage, int userId, int callingPid, int callingUid) {
        if (canSeeAnyPinnedShortcut(callingPackage, userId, callingPid, callingUid)) {
            return true;
        }
        long start = getStatStartTime();
        try {
            return hasShortcutHostPermissionInner(callingPackage, userId);
        } finally {
            logDurationStat(4, start);
        }
    }

    boolean canSeeAnyPinnedShortcut(String callingPackage, int userId, int callingPid, int callingUid) {
        boolean hasHostPackage;
        if (injectHasAccessShortcutsPermission(callingPid, callingUid)) {
            return true;
        }
        synchronized (this.mLock) {
            hasHostPackage = getNonPersistentUserLocked(userId).hasHostPackage(callingPackage);
        }
        return hasHostPackage;
    }

    @VisibleForTesting
    boolean injectHasAccessShortcutsPermission(int callingPid, int callingUid) {
        return this.mContext.checkPermission("android.permission.ACCESS_SHORTCUTS", callingPid, callingUid) == 0;
    }

    @VisibleForTesting
    boolean injectHasUnlimitedShortcutsApiCallsPermission(int callingPid, int callingUid) {
        return this.mContext.checkPermission("android.permission.UNLIMITED_SHORTCUTS_API_CALLS", callingPid, callingUid) == 0;
    }

    @VisibleForTesting
    boolean hasShortcutHostPermissionInner(String packageName, int userId) {
        synchronized (this.mLock) {
            throwIfUserLockedL(userId);
            ShortcutUser user = getUserShortcutsLocked(userId);
            ComponentName cached = user.getCachedLauncher();
            if (cached != null && cached.getPackageName().equals(packageName)) {
                return true;
            }
            ComponentName detected = getDefaultLauncher(userId);
            user.setLauncher(detected);
            if (detected != null) {
                return detected.getPackageName().equals(packageName);
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:15:0x005d, code lost:
        r7 = r9.size();
        r13 = Integer.MIN_VALUE;
        r16 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x0065, code lost:
        r15 = r16;
     */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x0067, code lost:
        if (r15 >= r7) goto L23;
     */
    /* JADX WARN: Code restructure failed: missing block: B:18:0x0069, code lost:
        r16 = r9.get(r15);
        r18 = r0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:19:0x007d, code lost:
        if (r16.activityInfo.applicationInfo.isSystemApp() != false) goto L17;
     */
    /* JADX WARN: Code restructure failed: missing block: B:22:0x0082, code lost:
        if (r16.priority >= r13) goto L21;
     */
    /* JADX WARN: Code restructure failed: missing block: B:24:0x0085, code lost:
        r14 = r16.activityInfo.getComponentName();
        r2 = r16.priority;
        r13 = r2;
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x0090, code lost:
        r16 = r15 + 1;
        r0 = r18;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.content.ComponentName getDefaultLauncher(int r21) {
        /*
            r20 = this;
            r1 = r20
            r2 = r21
            long r3 = r20.getStatStartTime()
            long r5 = r20.injectClearCallingIdentity()
            java.lang.Object r8 = r1.mLock     // Catch: java.lang.Throwable -> La6
            monitor-enter(r8)     // Catch: java.lang.Throwable -> La6
            r20.throwIfUserLockedL(r21)     // Catch: java.lang.Throwable -> La3
            com.android.server.pm.ShortcutUser r0 = r20.getUserShortcutsLocked(r21)     // Catch: java.lang.Throwable -> La3
            java.util.ArrayList r9 = new java.util.ArrayList     // Catch: java.lang.Throwable -> La3
            r9.<init>()     // Catch: java.lang.Throwable -> La3
            long r10 = r20.getStatStartTime()     // Catch: java.lang.Throwable -> La3
            android.content.pm.PackageManagerInternal r12 = r1.mPackageManagerInternal     // Catch: java.lang.Throwable -> La3
            android.content.ComponentName r12 = r12.getHomeActivitiesAsUser(r9, r2)     // Catch: java.lang.Throwable -> La3
            r13 = 0
            r1.logDurationStat(r13, r10)     // Catch: java.lang.Throwable -> La3
            r14 = 0
            if (r12 == 0) goto L2e
            r14 = r12
            goto L5b
        L2e:
            android.content.ComponentName r15 = r0.getLastKnownLauncher()     // Catch: java.lang.Throwable -> La3
            r14 = r15
            if (r14 == 0) goto L5b
            boolean r15 = r1.injectIsActivityEnabledAndExported(r14, r2)     // Catch: java.lang.Throwable -> La3
            if (r15 == 0) goto L3c
            goto L5b
        L3c:
            java.lang.String r15 = "ShortcutService"
            java.lang.StringBuilder r13 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> La3
            r13.<init>()     // Catch: java.lang.Throwable -> La3
            java.lang.String r7 = "Cached launcher "
            r13.append(r7)     // Catch: java.lang.Throwable -> La3
            r13.append(r14)     // Catch: java.lang.Throwable -> La3
            java.lang.String r7 = " no longer exists"
            r13.append(r7)     // Catch: java.lang.Throwable -> La3
            java.lang.String r7 = r13.toString()     // Catch: java.lang.Throwable -> La3
            android.util.Slog.w(r15, r7)     // Catch: java.lang.Throwable -> La3
            r14 = 0
            r0.clearLauncher()     // Catch: java.lang.Throwable -> La3
        L5b:
            if (r14 != 0) goto L97
            int r7 = r9.size()     // Catch: java.lang.Throwable -> La3
            r13 = -2147483648(0xffffffff80000000, float:-0.0)
            r16 = 0
        L65:
            r15 = r16
            if (r15 >= r7) goto L97
            java.lang.Object r16 = r9.get(r15)     // Catch: java.lang.Throwable -> La3
            android.content.pm.ResolveInfo r16 = (android.content.pm.ResolveInfo) r16     // Catch: java.lang.Throwable -> La3
            r17 = r16
            r18 = r0
            r0 = r17
            android.content.pm.ActivityInfo r2 = r0.activityInfo     // Catch: java.lang.Throwable -> La3
            android.content.pm.ApplicationInfo r2 = r2.applicationInfo     // Catch: java.lang.Throwable -> La3
            boolean r2 = r2.isSystemApp()     // Catch: java.lang.Throwable -> La3
            if (r2 != 0) goto L80
            goto L90
        L80:
            int r2 = r0.priority     // Catch: java.lang.Throwable -> La3
            if (r2 >= r13) goto L85
            goto L90
        L85:
            android.content.pm.ActivityInfo r2 = r0.activityInfo     // Catch: java.lang.Throwable -> La3
            android.content.ComponentName r2 = r2.getComponentName()     // Catch: java.lang.Throwable -> La3
            r14 = r2
            int r2 = r0.priority     // Catch: java.lang.Throwable -> La3
            r0 = r2
            r13 = r0
        L90:
            int r16 = r15 + 1
            r0 = r18
            r2 = r21
            goto L65
        L97:
            r18 = r0
            monitor-exit(r8)     // Catch: java.lang.Throwable -> La3
            r1.injectRestoreCallingIdentity(r5)
            r2 = 16
            r1.logDurationStat(r2, r3)
            return r14
        La3:
            r0 = move-exception
            monitor-exit(r8)     // Catch: java.lang.Throwable -> La3
            throw r0     // Catch: java.lang.Throwable -> La6
        La6:
            r0 = move-exception
            r1.injectRestoreCallingIdentity(r5)
            r2 = 16
            r1.logDurationStat(r2, r3)
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutService.getDefaultLauncher(int):android.content.ComponentName");
    }

    public void setShortcutHostPackage(String type, String packageName, int userId) {
        synchronized (this.mLock) {
            getNonPersistentUserLocked(userId).setShortcutHostPackage(type, packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cleanUpPackageForAllLoadedUsers(final String packageName, final int packageUserId, final boolean appStillExists) {
        synchronized (this.mLock) {
            forEachLoadedUserLocked(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$7yTF58WvWYAkuD-B4tHmI0K7nyI
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ShortcutService.this.cleanUpPackageLocked(packageName, ((ShortcutUser) obj).getUserId(), packageUserId, appStillExists);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    @VisibleForTesting
    public void cleanUpPackageLocked(final String packageName, int owningUserId, final int packageUserId, boolean appStillExists) {
        boolean wasUserLoaded = isUserLoadedLocked(owningUserId);
        ShortcutUser user = getUserShortcutsLocked(owningUserId);
        boolean doNotify = false;
        if (packageUserId == owningUserId && user.removePackage(packageName) != null) {
            doNotify = true;
        }
        user.removeLauncher(packageUserId, packageName);
        user.forAllLaunchers(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$okoC4pj1SnB9cHJpbZ-Xc5MyThk
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((ShortcutLauncher) obj).cleanUpPackage(packageName, packageUserId);
            }
        });
        user.forAllPackages(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$9V6lStUbS0hFjsnM72O75L8xvNI
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((ShortcutPackage) obj).refreshPinnedFlags();
            }
        });
        scheduleSaveUser(owningUserId);
        if (doNotify) {
            notifyListeners(packageName, owningUserId);
        }
        if (appStillExists && packageUserId == owningUserId) {
            user.rescanPackageIfNeeded(packageName, true);
        }
        if (!wasUserLoaded) {
            unloadUserLocked(owningUserId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LocalService extends ShortcutServiceInternal {
        private LocalService() {
        }

        public List<ShortcutInfo> getShortcuts(final int launcherUserId, final String callingPackage, final long changedSince, String packageName, List<String> shortcutIds, final ComponentName componentName, final int queryFlags, final int userId, final int callingPid, final int callingUid) {
            final ArrayList<ShortcutInfo> ret;
            LocalService localService;
            ArrayList<ShortcutInfo> ret2 = new ArrayList<>();
            boolean cloneKeyFieldOnly = (queryFlags & 4) != 0;
            final int cloneFlag = cloneKeyFieldOnly ? 4 : 11;
            List<String> shortcutIds2 = packageName == null ? null : shortcutIds;
            synchronized (ShortcutService.this.mLock) {
                try {
                    ShortcutService.this.throwIfUserLockedL(userId);
                    ShortcutService.this.throwIfUserLockedL(launcherUserId);
                    ShortcutService.this.getLauncherShortcutsLocked(callingPackage, userId, launcherUserId).attemptToRestoreIfNeededAndSave();
                    if (packageName != null) {
                        ret = ret2;
                        try {
                            getShortcutsInnerLocked(launcherUserId, callingPackage, packageName, shortcutIds2, changedSince, componentName, queryFlags, userId, ret2, cloneFlag, callingPid, callingUid);
                            localService = this;
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
                        ret = ret2;
                        final List<String> shortcutIdsF = shortcutIds2;
                        try {
                        } catch (Throwable th3) {
                            th = th3;
                            while (true) {
                                break;
                                break;
                            }
                            throw th;
                        }
                        try {
                            localService = this;
                            try {
                                ShortcutService.this.getUserShortcutsLocked(userId).forAllPackages(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$LocalService$Q0t7aDuDFJ8HWAf1NHW1dGQjOf8
                                    @Override // java.util.function.Consumer
                                    public final void accept(Object obj) {
                                        ShortcutService.LocalService.this.getShortcutsInnerLocked(launcherUserId, callingPackage, ((ShortcutPackage) obj).getPackageName(), shortcutIdsF, changedSince, componentName, queryFlags, userId, ret, cloneFlag, callingPid, callingUid);
                                    }
                                });
                            } catch (Throwable th4) {
                                th = th4;
                                while (true) {
                                    break;
                                    break;
                                }
                                throw th;
                            }
                        } catch (Throwable th5) {
                            th = th5;
                            while (true) {
                                break;
                                break;
                            }
                            throw th;
                        }
                    }
                    return ShortcutService.this.setReturnedByServer(ret);
                } catch (Throwable th6) {
                    th = th6;
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy("ShortcutService.this.mLock")
        public void getShortcutsInnerLocked(int launcherUserId, String callingPackage, String packageName, List<String> shortcutIds, final long changedSince, final ComponentName componentName, int queryFlags, int userId, ArrayList<ShortcutInfo> ret, int cloneFlag, int callingPid, int callingUid) {
            final ArraySet<String> ids = shortcutIds == null ? null : new ArraySet<>(shortcutIds);
            ShortcutUser user = ShortcutService.this.getUserShortcutsLocked(userId);
            ShortcutPackage p = user.getPackageShortcutsIfExists(packageName);
            if (p == null) {
                return;
            }
            boolean z = false;
            final boolean matchDynamic = (queryFlags & 1) != 0;
            final boolean matchPinned = (queryFlags & 2) != 0;
            final boolean matchManifest = (queryFlags & 8) != 0;
            boolean canAccessAllShortcuts = ShortcutService.this.canSeeAnyPinnedShortcut(callingPackage, launcherUserId, callingPid, callingUid);
            if (canAccessAllShortcuts && (queryFlags & 1024) != 0) {
                z = true;
            }
            final boolean getPinnedByAnyLauncher = z;
            p.findAll(ret, new Predicate() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$LocalService$ltDE7qm9grkumxffFI8cLCFpNqU
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return ShortcutService.LocalService.lambda$getShortcutsInnerLocked$1(changedSince, ids, componentName, matchDynamic, matchPinned, getPinnedByAnyLauncher, matchManifest, (ShortcutInfo) obj);
                }
            }, cloneFlag, callingPackage, launcherUserId, getPinnedByAnyLauncher);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$getShortcutsInnerLocked$1(long changedSince, ArraySet ids, ComponentName componentName, boolean matchDynamic, boolean matchPinned, boolean getPinnedByAnyLauncher, boolean matchManifest, ShortcutInfo si) {
            if (si.getLastChangedTimestamp() < changedSince) {
                return false;
            }
            if (ids == null || ids.contains(si.getId())) {
                if (componentName == null || si.getActivity() == null || si.getActivity().equals(componentName)) {
                    if (matchDynamic && si.isDynamic()) {
                        return true;
                    }
                    if ((matchPinned || getPinnedByAnyLauncher) && si.isPinned()) {
                        return true;
                    }
                    return matchManifest && si.isDeclaredInManifest();
                }
                return false;
            }
            return false;
        }

        public boolean isPinnedByCaller(int launcherUserId, String callingPackage, String packageName, String shortcutId, int userId) {
            boolean z;
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.throwIfUserLockedL(userId);
                ShortcutService.this.throwIfUserLockedL(launcherUserId);
                ShortcutService.this.getLauncherShortcutsLocked(callingPackage, userId, launcherUserId).attemptToRestoreIfNeededAndSave();
                ShortcutInfo si = getShortcutInfoLocked(launcherUserId, callingPackage, packageName, shortcutId, userId, false);
                z = si != null && si.isPinned();
            }
            return z;
        }

        @GuardedBy("ShortcutService.this.mLock")
        private ShortcutInfo getShortcutInfoLocked(int launcherUserId, String callingPackage, String packageName, final String shortcutId, int userId, boolean getPinnedByAnyLauncher) {
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");
            ShortcutService.this.throwIfUserLockedL(userId);
            ShortcutService.this.throwIfUserLockedL(launcherUserId);
            ShortcutPackage p = ShortcutService.this.getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
            if (p == null) {
                return null;
            }
            ArrayList<ShortcutInfo> list = new ArrayList<>(1);
            p.findAll(list, new Predicate() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$LocalService$a6cj3oQpS-Z6FB4DytB0FytYmiM
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean equals;
                    equals = shortcutId.equals(((ShortcutInfo) obj).getId());
                    return equals;
                }
            }, 0, callingPackage, launcherUserId, getPinnedByAnyLauncher);
            if (list.size() == 0) {
                return null;
            }
            return list.get(0);
        }

        public void pinShortcuts(int launcherUserId, String callingPackage, String packageName, List<String> shortcutIds, int userId) {
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkNotNull(shortcutIds, "shortcutIds");
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.throwIfUserLockedL(userId);
                ShortcutService.this.throwIfUserLockedL(launcherUserId);
                ShortcutLauncher launcher = ShortcutService.this.getLauncherShortcutsLocked(callingPackage, userId, launcherUserId);
                launcher.attemptToRestoreIfNeededAndSave();
                launcher.pinShortcuts(userId, packageName, shortcutIds, false);
            }
            ShortcutService.this.packageShortcutsChanged(packageName, userId);
            ShortcutService.this.verifyStates();
        }

        public Intent[] createShortcutIntents(int launcherUserId, String callingPackage, String packageName, String shortcutId, int userId, int callingPid, int callingUid) {
            Preconditions.checkStringNotEmpty(packageName, "packageName can't be empty");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId can't be empty");
            synchronized (ShortcutService.this.mLock) {
                try {
                    try {
                        ShortcutService.this.throwIfUserLockedL(userId);
                        ShortcutService.this.throwIfUserLockedL(launcherUserId);
                        ShortcutService.this.getLauncherShortcutsLocked(callingPackage, userId, launcherUserId).attemptToRestoreIfNeededAndSave();
                        boolean getPinnedByAnyLauncher = ShortcutService.this.canSeeAnyPinnedShortcut(callingPackage, launcherUserId, callingPid, callingUid);
                        ShortcutInfo si = getShortcutInfoLocked(launcherUserId, callingPackage, packageName, shortcutId, userId, getPinnedByAnyLauncher);
                        if (si != null && si.isEnabled() && (si.isAlive() || getPinnedByAnyLauncher)) {
                            return si.getIntents();
                        }
                        Log.e(ShortcutService.TAG, "Shortcut " + shortcutId + " does not exist or disabled");
                        return null;
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

        public void addListener(ShortcutServiceInternal.ShortcutChangeListener listener) {
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.mListeners.add((ShortcutServiceInternal.ShortcutChangeListener) Preconditions.checkNotNull(listener));
            }
        }

        public int getShortcutIconResId(int launcherUserId, String callingPackage, String packageName, String shortcutId, int userId) {
            Preconditions.checkNotNull(callingPackage, "callingPackage");
            Preconditions.checkNotNull(packageName, "packageName");
            Preconditions.checkNotNull(shortcutId, "shortcutId");
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.throwIfUserLockedL(userId);
                ShortcutService.this.throwIfUserLockedL(launcherUserId);
                ShortcutService.this.getLauncherShortcutsLocked(callingPackage, userId, launcherUserId).attemptToRestoreIfNeededAndSave();
                ShortcutPackage p = ShortcutService.this.getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
                int i = 0;
                if (p == null) {
                    return 0;
                }
                ShortcutInfo shortcutInfo = p.findShortcutById(shortcutId);
                if (shortcutInfo != null && shortcutInfo.hasIconResource()) {
                    i = shortcutInfo.getIconResourceId();
                }
                return i;
            }
        }

        public ParcelFileDescriptor getShortcutIconFd(int launcherUserId, String callingPackage, String packageName, String shortcutId, int userId) {
            Preconditions.checkNotNull(callingPackage, "callingPackage");
            Preconditions.checkNotNull(packageName, "packageName");
            Preconditions.checkNotNull(shortcutId, "shortcutId");
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.throwIfUserLockedL(userId);
                ShortcutService.this.throwIfUserLockedL(launcherUserId);
                ShortcutService.this.getLauncherShortcutsLocked(callingPackage, userId, launcherUserId).attemptToRestoreIfNeededAndSave();
                ShortcutPackage p = ShortcutService.this.getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
                if (p == null) {
                    return null;
                }
                ShortcutInfo shortcutInfo = p.findShortcutById(shortcutId);
                if (shortcutInfo != null && shortcutInfo.hasIconFile()) {
                    String path = ShortcutService.this.mShortcutBitmapSaver.getBitmapPathMayWaitLocked(shortcutInfo);
                    if (path == null) {
                        Slog.w(ShortcutService.TAG, "null bitmap detected in getShortcutIconFd()");
                        return null;
                    }
                    try {
                        return ParcelFileDescriptor.open(new File(path), 268435456);
                    } catch (FileNotFoundException e) {
                        Slog.e(ShortcutService.TAG, "Icon file not found: " + path);
                        return null;
                    }
                }
                return null;
            }
        }

        public boolean hasShortcutHostPermission(int launcherUserId, String callingPackage, int callingPid, int callingUid) {
            return ShortcutService.this.hasShortcutHostPermission(callingPackage, launcherUserId, callingPid, callingUid);
        }

        public void setShortcutHostPackage(String type, String packageName, int userId) {
            ShortcutService.this.setShortcutHostPackage(type, packageName, userId);
        }

        public boolean requestPinAppWidget(String callingPackage, AppWidgetProviderInfo appWidget, Bundle extras, IntentSender resultIntent, int userId) {
            Preconditions.checkNotNull(appWidget);
            return ShortcutService.this.requestPinItem(callingPackage, userId, null, appWidget, extras, resultIntent);
        }

        public boolean isRequestPinItemSupported(int callingUserId, int requestType) {
            return ShortcutService.this.isRequestPinItemSupported(callingUserId, requestType);
        }

        public boolean isForegroundDefaultLauncher(String callingPackage, int callingUid) {
            Preconditions.checkNotNull(callingPackage);
            int userId = UserHandle.getUserId(callingUid);
            ComponentName defaultLauncher = ShortcutService.this.getDefaultLauncher(userId);
            if (defaultLauncher != null && callingPackage.equals(defaultLauncher.getPackageName())) {
                synchronized (ShortcutService.this.mLock) {
                    if (!ShortcutService.this.isUidForegroundLocked(callingUid)) {
                        return false;
                    }
                    return true;
                }
            }
            return false;
        }
    }

    void handleLocaleChanged() {
        scheduleSaveBaseState();
        synchronized (this.mLock) {
            long token = injectClearCallingIdentity();
            forEachLoadedUserLocked(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$OcNf5Tg_F4Us34H7SfC_e_tnGzw
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((ShortcutUser) obj).detectLocaleChange();
                }
            });
            injectRestoreCallingIdentity(token);
        }
    }

    @VisibleForTesting
    void checkPackageChanges(int ownerUserId) {
        if (injectIsSafeModeEnabled()) {
            Slog.i(TAG, "Safe mode, skipping checkPackageChanges()");
            return;
        }
        long start = getStatStartTime();
        try {
            final ArrayList<ShortcutUser.PackageWithUser> gonePackages = new ArrayList<>();
            synchronized (this.mLock) {
                ShortcutUser user = getUserShortcutsLocked(ownerUserId);
                user.forAllPackageItems(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$KKtB89b9du8RtyDY2LIMGlzZzzg
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ShortcutService.lambda$checkPackageChanges$6(ShortcutService.this, gonePackages, (ShortcutPackageItem) obj);
                    }
                });
                if (gonePackages.size() > 0) {
                    for (int i = gonePackages.size() - 1; i >= 0; i--) {
                        ShortcutUser.PackageWithUser pu = gonePackages.get(i);
                        cleanUpPackageLocked(pu.packageName, ownerUserId, pu.userId, false);
                    }
                }
                rescanUpdatedPackagesLocked(ownerUserId, user.getLastAppScanTime());
            }
            logDurationStat(8, start);
            verifyStates();
        } catch (Throwable th) {
            logDurationStat(8, start);
            throw th;
        }
    }

    public static /* synthetic */ void lambda$checkPackageChanges$6(ShortcutService shortcutService, ArrayList gonePackages, ShortcutPackageItem spi) {
        if (!spi.getPackageInfo().isShadow() && !shortcutService.isPackageInstalled(spi.getPackageName(), spi.getPackageUserId())) {
            gonePackages.add(ShortcutUser.PackageWithUser.of(spi));
        }
    }

    @GuardedBy("mLock")
    private void rescanUpdatedPackagesLocked(final int userId, long lastScanTime) {
        final ShortcutUser user = getUserShortcutsLocked(userId);
        long now = injectCurrentTimeMillis();
        boolean afterOta = !injectBuildFingerprint().equals(user.getLastAppScanOsFingerprint());
        forUpdatedPackages(userId, lastScanTime, afterOta, new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$XepsJlzLd-VitYi8_ThhUsx37Ok
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ShortcutService.lambda$rescanUpdatedPackagesLocked$7(ShortcutService.this, user, userId, (ApplicationInfo) obj);
            }
        });
        user.setLastAppScanTime(now);
        user.setLastAppScanOsFingerprint(injectBuildFingerprint());
        scheduleSaveUser(userId);
    }

    public static /* synthetic */ void lambda$rescanUpdatedPackagesLocked$7(ShortcutService shortcutService, ShortcutUser user, int userId, ApplicationInfo ai) {
        user.attemptToRestoreIfNeededAndSave(shortcutService, ai.packageName, userId);
        user.rescanPackageIfNeeded(ai.packageName, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackageAdded(String packageName, int userId) {
        synchronized (this.mLock) {
            ShortcutUser user = getUserShortcutsLocked(userId);
            user.attemptToRestoreIfNeededAndSave(this, packageName, userId);
            user.rescanPackageIfNeeded(packageName, true);
        }
        verifyStates();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackageUpdateFinished(String packageName, int userId) {
        synchronized (this.mLock) {
            ShortcutUser user = getUserShortcutsLocked(userId);
            user.attemptToRestoreIfNeededAndSave(this, packageName, userId);
            if (isPackageInstalled(packageName, userId)) {
                user.rescanPackageIfNeeded(packageName, true);
            }
        }
        verifyStates();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackageRemoved(String packageName, int packageUserId) {
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId, false);
        verifyStates();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackageDataCleared(String packageName, int packageUserId) {
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId, true);
        verifyStates();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackageChanged(String packageName, int packageUserId) {
        if (!isPackageInstalled(packageName, packageUserId)) {
            handlePackageRemoved(packageName, packageUserId);
            return;
        }
        synchronized (this.mLock) {
            ShortcutUser user = getUserShortcutsLocked(packageUserId);
            user.rescanPackageIfNeeded(packageName, true);
        }
        verifyStates();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final PackageInfo getPackageInfoWithSignatures(String packageName, int userId) {
        return getPackageInfo(packageName, userId, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final PackageInfo getPackageInfo(String packageName, int userId) {
        return getPackageInfo(packageName, userId, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int injectGetPackageUid(String packageName, int userId) {
        long token = injectClearCallingIdentity();
        try {
            return this.mIPackageManager.getPackageUid(packageName, (int) PACKAGE_MATCH_FLAGS, userId);
        } catch (RemoteException e) {
            Slog.wtf(TAG, "RemoteException", e);
            return -1;
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    @VisibleForTesting
    final PackageInfo getPackageInfo(String packageName, int userId, boolean getSignatures) {
        return isInstalledOrNull(injectPackageInfoWithUninstalled(packageName, userId, getSignatures));
    }

    @VisibleForTesting
    PackageInfo injectPackageInfoWithUninstalled(String packageName, int userId, boolean getSignatures) {
        long start = getStatStartTime();
        long token = injectClearCallingIdentity();
        try {
            try {
                PackageInfo packageInfo = this.mIPackageManager.getPackageInfo(packageName, PACKAGE_MATCH_FLAGS | (getSignatures ? 134217728 : 0), userId);
                injectRestoreCallingIdentity(token);
                logDurationStat(getSignatures ? 2 : 1, start);
                return packageInfo;
            } catch (RemoteException e) {
                Slog.wtf(TAG, "RemoteException", e);
                injectRestoreCallingIdentity(token);
                logDurationStat(getSignatures ? 2 : 1, start);
                return null;
            }
        } catch (Throwable th) {
            injectRestoreCallingIdentity(token);
            logDurationStat(getSignatures ? 2 : 1, start);
            throw th;
        }
    }

    @VisibleForTesting
    final ApplicationInfo getApplicationInfo(String packageName, int userId) {
        return isInstalledOrNull(injectApplicationInfoWithUninstalled(packageName, userId));
    }

    @VisibleForTesting
    ApplicationInfo injectApplicationInfoWithUninstalled(String packageName, int userId) {
        long start = getStatStartTime();
        long token = injectClearCallingIdentity();
        try {
            return this.mIPackageManager.getApplicationInfo(packageName, (int) PACKAGE_MATCH_FLAGS, userId);
        } catch (RemoteException e) {
            Slog.wtf(TAG, "RemoteException", e);
            return null;
        } finally {
            injectRestoreCallingIdentity(token);
            logDurationStat(3, start);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final ActivityInfo getActivityInfoWithMetadata(ComponentName activity, int userId) {
        return isInstalledOrNull(injectGetActivityInfoWithMetadataWithUninstalled(activity, userId));
    }

    @VisibleForTesting
    ActivityInfo injectGetActivityInfoWithMetadataWithUninstalled(ComponentName activity, int userId) {
        long start = getStatStartTime();
        long token = injectClearCallingIdentity();
        try {
            return this.mIPackageManager.getActivityInfo(activity, 794752, userId);
        } catch (RemoteException e) {
            Slog.wtf(TAG, "RemoteException", e);
            return null;
        } finally {
            injectRestoreCallingIdentity(token);
            logDurationStat(6, start);
        }
    }

    @VisibleForTesting
    final List<PackageInfo> getInstalledPackages(int userId) {
        long start = getStatStartTime();
        long token = injectClearCallingIdentity();
        try {
            List<PackageInfo> all = injectGetPackagesWithUninstalled(userId);
            all.removeIf(PACKAGE_NOT_INSTALLED);
            return all;
        } catch (RemoteException e) {
            Slog.wtf(TAG, "RemoteException", e);
            return null;
        } finally {
            injectRestoreCallingIdentity(token);
            logDurationStat(7, start);
        }
    }

    @VisibleForTesting
    List<PackageInfo> injectGetPackagesWithUninstalled(int userId) throws RemoteException {
        ParceledListSlice<PackageInfo> parceledList = this.mIPackageManager.getInstalledPackages((int) PACKAGE_MATCH_FLAGS, userId);
        if (parceledList == null) {
            return Collections.emptyList();
        }
        return parceledList.getList();
    }

    private void forUpdatedPackages(int userId, long lastScanTime, boolean afterOta, Consumer<ApplicationInfo> callback) {
        List<PackageInfo> list = getInstalledPackages(userId);
        for (int i = list.size() - 1; i >= 0; i--) {
            PackageInfo pi = list.get(i);
            if (afterOta || pi.lastUpdateTime >= lastScanTime) {
                callback.accept(pi.applicationInfo);
            }
        }
    }

    private boolean isApplicationFlagSet(String packageName, int userId, int flags) {
        ApplicationInfo ai = injectApplicationInfoWithUninstalled(packageName, userId);
        return ai != null && (ai.flags & flags) == flags;
    }

    private static boolean isInstalled(ApplicationInfo ai) {
        return (ai == null || !ai.enabled || (ai.flags & DumpState.DUMP_VOLUMES) == 0) ? false : true;
    }

    private static boolean isEphemeralApp(ApplicationInfo ai) {
        return ai != null && ai.isInstantApp();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isInstalled(PackageInfo pi) {
        return pi != null && isInstalled(pi.applicationInfo);
    }

    private static boolean isInstalled(ActivityInfo ai) {
        return ai != null && isInstalled(ai.applicationInfo);
    }

    private static ApplicationInfo isInstalledOrNull(ApplicationInfo ai) {
        if (isInstalled(ai)) {
            return ai;
        }
        return null;
    }

    private static PackageInfo isInstalledOrNull(PackageInfo pi) {
        if (isInstalled(pi)) {
            return pi;
        }
        return null;
    }

    private static ActivityInfo isInstalledOrNull(ActivityInfo ai) {
        if (isInstalled(ai)) {
            return ai;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPackageInstalled(String packageName, int userId) {
        return getApplicationInfo(packageName, userId) != null;
    }

    boolean isEphemeralApp(String packageName, int userId) {
        return isEphemeralApp(getApplicationInfo(packageName, userId));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public XmlResourceParser injectXmlMetaData(ActivityInfo activityInfo, String key) {
        return activityInfo.loadXmlMetaData(this.mContext.getPackageManager(), key);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Resources injectGetResourcesForApplicationAsUser(String packageName, int userId) {
        long start = getStatStartTime();
        long token = injectClearCallingIdentity();
        try {
            return this.mContext.getPackageManager().getResourcesForApplicationAsUser(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Resources for package " + packageName + " not found");
            return null;
        } finally {
            injectRestoreCallingIdentity(token);
            logDurationStat(9, start);
        }
    }

    private Intent getMainActivityIntent() {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory(LAUNCHER_INTENT_CATEGORY);
        return intent;
    }

    @VisibleForTesting
    List<ResolveInfo> queryActivities(Intent baseIntent, String packageName, ComponentName activity, int userId) {
        baseIntent.setPackage((String) Preconditions.checkNotNull(packageName));
        if (activity != null) {
            baseIntent.setComponent(activity);
        }
        return queryActivities(baseIntent, userId, true);
    }

    List<ResolveInfo> queryActivities(Intent intent, int userId, boolean exportedOnly) {
        long token = injectClearCallingIdentity();
        try {
            List<ResolveInfo> resolved = this.mContext.getPackageManager().queryIntentActivitiesAsUser(intent, PACKAGE_MATCH_FLAGS, userId);
            if (resolved == null || resolved.size() == 0) {
                return EMPTY_RESOLVE_INFO;
            }
            if (!isInstalled(resolved.get(0).activityInfo)) {
                return EMPTY_RESOLVE_INFO;
            }
            if (exportedOnly) {
                resolved.removeIf(ACTIVITY_NOT_EXPORTED);
            }
            return resolved;
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ComponentName injectGetDefaultMainActivity(String packageName, int userId) {
        long start = getStatStartTime();
        try {
            ComponentName componentName = null;
            List<ResolveInfo> resolved = queryActivities(getMainActivityIntent(), packageName, null, userId);
            if (resolved.size() != 0) {
                componentName = resolved.get(0).activityInfo.getComponentName();
            }
            return componentName;
        } finally {
            logDurationStat(11, start);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean injectIsMainActivity(ComponentName activity, int userId) {
        long start = getStatStartTime();
        try {
            if (activity == null) {
                wtf("null activity detected");
                return false;
            } else if (DUMMY_MAIN_ACTIVITY.equals(activity.getClassName())) {
                return true;
            } else {
                List<ResolveInfo> resolved = queryActivities(getMainActivityIntent(), activity.getPackageName(), activity, userId);
                return resolved.size() > 0;
            }
        } finally {
            logDurationStat(12, start);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ComponentName getDummyMainActivity(String packageName) {
        return new ComponentName(packageName, DUMMY_MAIN_ACTIVITY);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDummyMainActivity(ComponentName name) {
        return name != null && DUMMY_MAIN_ACTIVITY.equals(name.getClassName());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<ResolveInfo> injectGetMainActivities(String packageName, int userId) {
        long start = getStatStartTime();
        try {
            return queryActivities(getMainActivityIntent(), packageName, null, userId);
        } finally {
            logDurationStat(12, start);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public boolean injectIsActivityEnabledAndExported(ComponentName activity, int userId) {
        long start = getStatStartTime();
        try {
            return queryActivities(new Intent(), activity.getPackageName(), activity, userId).size() > 0;
        } finally {
            logDurationStat(13, start);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ComponentName injectGetPinConfirmationActivity(String launcherPackageName, int launcherUserId, int requestType) {
        String action;
        Preconditions.checkNotNull(launcherPackageName);
        if (requestType == 1) {
            action = "android.content.pm.action.CONFIRM_PIN_SHORTCUT";
        } else {
            action = "android.content.pm.action.CONFIRM_PIN_APPWIDGET";
        }
        Intent confirmIntent = new Intent(action).setPackage(launcherPackageName);
        List<ResolveInfo> candidates = queryActivities(confirmIntent, launcherUserId, false);
        Iterator<ResolveInfo> it = candidates.iterator();
        if (it.hasNext()) {
            ResolveInfo ri = it.next();
            return ri.activityInfo.getComponentName();
        }
        return null;
    }

    boolean injectIsSafeModeEnabled() {
        long token = injectClearCallingIdentity();
        try {
            return IWindowManager.Stub.asInterface(ServiceManager.getService("window")).isSafeModeEnabled();
        } catch (RemoteException e) {
            return false;
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getParentOrSelfUserId(int userId) {
        return this.mUserManagerInternal.getProfileParentId(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void injectSendIntentSender(IntentSender intentSender, Intent extras) {
        if (intentSender == null) {
            return;
        }
        try {
            intentSender.sendIntent(this.mContext, 0, extras, null, null);
        } catch (IntentSender.SendIntentException e) {
            Slog.w(TAG, "sendIntent failed().", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean shouldBackupApp(String packageName, int userId) {
        return isApplicationFlagSet(packageName, userId, 32768);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean shouldBackupApp(PackageInfo pi) {
        return (pi.applicationInfo.flags & 32768) != 0;
    }

    public byte[] getBackupPayload(int userId) {
        enforceSystem();
        synchronized (this.mLock) {
            if (!isUserUnlockedL(userId)) {
                wtf("Can't backup: user " + userId + " is locked or not running");
                return null;
            }
            ShortcutUser user = getUserShortcutsLocked(userId);
            if (user == null) {
                wtf("Can't backup: user not found: id=" + userId);
                return null;
            }
            user.forAllPackageItems(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$qeFlXbEdNY-s36xnqPf5bs5axg0
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((ShortcutPackageItem) obj).refreshPackageSignatureAndSave();
                }
            });
            user.forAllPackages(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$bdUeaAkcePSCZBDxdJttl1FPOmI
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((ShortcutPackage) obj).rescanPackageIfNeeded(false, true);
                }
            });
            user.forAllLaunchers(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$5PQDuMeuJAK9L5YMuS3D3xeOzEc
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((ShortcutLauncher) obj).ensurePackageInfo();
                }
            });
            scheduleSaveUser(userId);
            saveDirtyInfo();
            ByteArrayOutputStream os = new ByteArrayOutputStream(32768);
            try {
                saveUserInternalLocked(userId, os, true);
                byte[] payload = os.toByteArray();
                this.mShortcutDumpFiles.save("backup-1-payload.txt", payload);
                return payload;
            } catch (IOException | XmlPullParserException e) {
                Slog.w(TAG, "Backup failed.", e);
                return null;
            }
        }
    }

    public void applyRestore(byte[] payload, int userId) {
        enforceSystem();
        synchronized (this.mLock) {
            if (!isUserUnlockedL(userId)) {
                wtf("Can't restore: user " + userId + " is locked or not running");
                return;
            }
            this.mShortcutDumpFiles.save("restore-0-start.txt", new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$38xbKnaiRwgkQIdkh2OdULJL_hQ
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ShortcutService.lambda$applyRestore$11(ShortcutService.this, (PrintWriter) obj);
                }
            });
            this.mShortcutDumpFiles.save("restore-1-payload.xml", payload);
            ByteArrayInputStream is = new ByteArrayInputStream(payload);
            try {
                ShortcutUser restored = loadUserInternal(userId, is, true);
                this.mShortcutDumpFiles.save("restore-2.txt", new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$w7_ouiisHmMMzTkQ_HUAHbawlLY
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ShortcutService.this.dumpInner((PrintWriter) obj);
                    }
                });
                getUserShortcutsLocked(userId).mergeRestoredFile(restored);
                this.mShortcutDumpFiles.save("restore-3.txt", new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$w7_ouiisHmMMzTkQ_HUAHbawlLY
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ShortcutService.this.dumpInner((PrintWriter) obj);
                    }
                });
                rescanUpdatedPackagesLocked(userId, 0L);
                this.mShortcutDumpFiles.save("restore-4.txt", new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$w7_ouiisHmMMzTkQ_HUAHbawlLY
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ShortcutService.this.dumpInner((PrintWriter) obj);
                    }
                });
                this.mShortcutDumpFiles.save("restore-5-finish.txt", new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$vKI79Gf4pKq8ASWghBXV-NKhZwk
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ShortcutService.lambda$applyRestore$12(ShortcutService.this, (PrintWriter) obj);
                    }
                });
                saveUserLocked(userId);
            } catch (InvalidFileFormatException | IOException | XmlPullParserException e) {
                Slog.w(TAG, "Restoration failed.", e);
            }
        }
    }

    public static /* synthetic */ void lambda$applyRestore$11(ShortcutService shortcutService, PrintWriter pw) {
        pw.print("Start time: ");
        shortcutService.dumpCurrentTime(pw);
        pw.println();
    }

    public static /* synthetic */ void lambda$applyRestore$12(ShortcutService shortcutService, PrintWriter pw) {
        pw.print("Finish time: ");
        shortcutService.dumpCurrentTime(pw);
        pw.println();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpAndUsageStatsPermission(this.mContext, TAG, pw)) {
            dumpNoCheck(fd, pw, args);
        }
    }

    @VisibleForTesting
    void dumpNoCheck(FileDescriptor fd, PrintWriter pw, String[] args) {
        DumpFilter filter = parseDumpArgs(args);
        if (filter.shouldDumpCheckIn()) {
            dumpCheckin(pw, filter.shouldCheckInClear());
            return;
        }
        if (filter.shouldDumpMain()) {
            dumpInner(pw, filter);
            pw.println();
        }
        if (filter.shouldDumpUid()) {
            dumpUid(pw);
            pw.println();
        }
        if (filter.shouldDumpFiles()) {
            dumpDumpFiles(pw);
            pw.println();
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:68:0x0101, code lost:
        if (r2 >= r6.length) goto L58;
     */
    /* JADX WARN: Code restructure failed: missing block: B:69:0x0103, code lost:
        r0.addPackage(r6[r2]);
        r2 = r2 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:70:0x010c, code lost:
        return r0;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static com.android.server.pm.ShortcutService.DumpFilter parseDumpArgs(java.lang.String[] r6) {
        /*
            Method dump skipped, instructions count: 269
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutService.parseDumpArgs(java.lang.String[]):com.android.server.pm.ShortcutService$DumpFilter");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class DumpFilter {
        private boolean mDumpCheckIn = false;
        private boolean mCheckInClear = false;
        private boolean mDumpMain = true;
        private boolean mDumpUid = false;
        private boolean mDumpFiles = false;
        private boolean mDumpDetails = true;
        private List<Pattern> mPackagePatterns = new ArrayList();
        private List<Integer> mUsers = new ArrayList();

        DumpFilter() {
        }

        void addPackageRegex(String regex) {
            this.mPackagePatterns.add(Pattern.compile(regex));
        }

        public void addPackage(String packageName) {
            addPackageRegex(Pattern.quote(packageName));
        }

        void addUser(int userId) {
            this.mUsers.add(Integer.valueOf(userId));
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public boolean isPackageMatch(String packageName) {
            if (this.mPackagePatterns.size() == 0) {
                return true;
            }
            for (int i = 0; i < this.mPackagePatterns.size(); i++) {
                if (this.mPackagePatterns.get(i).matcher(packageName).find()) {
                    return true;
                }
            }
            return false;
        }

        boolean isUserMatch(int userId) {
            if (this.mUsers.size() == 0) {
                return true;
            }
            for (int i = 0; i < this.mUsers.size(); i++) {
                if (this.mUsers.get(i).intValue() == userId) {
                    return true;
                }
            }
            return false;
        }

        public boolean shouldDumpCheckIn() {
            return this.mDumpCheckIn;
        }

        public void setDumpCheckIn(boolean dumpCheckIn) {
            this.mDumpCheckIn = dumpCheckIn;
        }

        public boolean shouldCheckInClear() {
            return this.mCheckInClear;
        }

        public void setCheckInClear(boolean checkInClear) {
            this.mCheckInClear = checkInClear;
        }

        public boolean shouldDumpMain() {
            return this.mDumpMain;
        }

        public void setDumpMain(boolean dumpMain) {
            this.mDumpMain = dumpMain;
        }

        public boolean shouldDumpUid() {
            return this.mDumpUid;
        }

        public void setDumpUid(boolean dumpUid) {
            this.mDumpUid = dumpUid;
        }

        public boolean shouldDumpFiles() {
            return this.mDumpFiles;
        }

        public void setDumpFiles(boolean dumpFiles) {
            this.mDumpFiles = dumpFiles;
        }

        public boolean shouldDumpDetails() {
            return this.mDumpDetails;
        }

        public void setDumpDetails(boolean dumpDetails) {
            this.mDumpDetails = dumpDetails;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dumpInner(PrintWriter pw) {
        dumpInner(pw, new DumpFilter());
    }

    private void dumpInner(PrintWriter pw, DumpFilter filter) {
        synchronized (this.mLock) {
            if (filter.shouldDumpDetails()) {
                long now = injectCurrentTimeMillis();
                pw.print("Now: [");
                pw.print(now);
                pw.print("] ");
                pw.print(formatTime(now));
                pw.print("  Raw last reset: [");
                pw.print(this.mRawLastResetTime);
                pw.print("] ");
                pw.print(formatTime(this.mRawLastResetTime));
                long last = getLastResetTimeLocked();
                pw.print("  Last reset: [");
                pw.print(last);
                pw.print("] ");
                pw.print(formatTime(last));
                long next = getNextResetTimeLocked();
                pw.print("  Next reset: [");
                pw.print(next);
                pw.print("] ");
                pw.print(formatTime(next));
                pw.println();
                pw.println();
                pw.print("  Config:");
                pw.print("    Max icon dim: ");
                pw.println(this.mMaxIconDimension);
                pw.print("    Icon format: ");
                pw.println(this.mIconPersistFormat);
                pw.print("    Icon quality: ");
                pw.println(this.mIconPersistQuality);
                pw.print("    saveDelayMillis: ");
                pw.println(this.mSaveDelayMillis);
                pw.print("    resetInterval: ");
                pw.println(this.mResetInterval);
                pw.print("    maxUpdatesPerInterval: ");
                pw.println(this.mMaxUpdatesPerInterval);
                pw.print("    maxShortcutsPerActivity: ");
                pw.println(this.mMaxShortcuts);
                pw.println();
                this.mStatLogger.dump(pw, "  ");
                pw.println();
                pw.print("  #Failures: ");
                pw.println(this.mWtfCount);
                if (this.mLastWtfStacktrace != null) {
                    pw.print("  Last failure stack trace: ");
                    pw.println(Log.getStackTraceString(this.mLastWtfStacktrace));
                }
                pw.println();
                this.mShortcutBitmapSaver.dumpLocked(pw, "  ");
                pw.println();
            }
            for (int i = 0; i < this.mUsers.size(); i++) {
                ShortcutUser user = this.mUsers.valueAt(i);
                if (filter.isUserMatch(user.getUserId())) {
                    user.dump(pw, "  ", filter);
                    pw.println();
                }
            }
            for (int i2 = 0; i2 < this.mShortcutNonPersistentUsers.size(); i2++) {
                ShortcutNonPersistentUser user2 = this.mShortcutNonPersistentUsers.valueAt(i2);
                if (filter.isUserMatch(user2.getUserId())) {
                    user2.dump(pw, "  ", filter);
                    pw.println();
                }
            }
        }
    }

    private void dumpUid(PrintWriter pw) {
        synchronized (this.mLock) {
            pw.println("** SHORTCUT MANAGER UID STATES (dumpsys shortcut -n -u)");
            for (int i = 0; i < this.mUidState.size(); i++) {
                int uid = this.mUidState.keyAt(i);
                int state = this.mUidState.valueAt(i);
                pw.print("    UID=");
                pw.print(uid);
                pw.print(" state=");
                pw.print(state);
                if (isProcessStateForeground(state)) {
                    pw.print("  [FG]");
                }
                pw.print("  last FG=");
                pw.print(this.mUidLastForegroundElapsedTime.get(uid));
                pw.println();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String formatTime(long time) {
        Time tobj = new Time();
        tobj.set(time);
        return tobj.format("%Y-%m-%d %H:%M:%S");
    }

    private void dumpCurrentTime(PrintWriter pw) {
        pw.print(formatTime(injectCurrentTimeMillis()));
    }

    private void dumpCheckin(PrintWriter pw, boolean clear) {
        synchronized (this.mLock) {
            try {
                JSONArray users = new JSONArray();
                for (int i = 0; i < this.mUsers.size(); i++) {
                    users.put(this.mUsers.valueAt(i).dumpCheckin(clear));
                }
                JSONObject result = new JSONObject();
                result.put(KEY_SHORTCUT, users);
                result.put(KEY_LOW_RAM, injectIsLowRamDevice());
                result.put(KEY_ICON_SIZE, this.mMaxIconDimension);
                pw.println(result.toString(1));
            } catch (JSONException e) {
                Slog.e(TAG, "Unable to write in json", e);
            }
        }
    }

    private void dumpDumpFiles(PrintWriter pw) {
        synchronized (this.mLock) {
            pw.println("** SHORTCUT MANAGER FILES (dumpsys shortcut -n -f)");
            this.mShortcutDumpFiles.dumpAll(pw);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        enforceShell();
        long token = injectClearCallingIdentity();
        try {
            int status = new MyShellCommand().exec(this, in, out, err, args, callback, resultReceiver);
            try {
                resultReceiver.send(status, null);
                injectRestoreCallingIdentity(token);
            } catch (Throwable th) {
                th = th;
                injectRestoreCallingIdentity(token);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class CommandException extends Exception {
        public CommandException(String message) {
            super(message);
        }
    }

    /* loaded from: classes.dex */
    private class MyShellCommand extends ShellCommand {
        private int mUserId;

        private MyShellCommand() {
            this.mUserId = 0;
        }

        private void parseOptionsLocked(boolean takeUser) throws CommandException {
            do {
                String opt = getNextOption();
                if (opt != null) {
                    char c = 65535;
                    if (opt.hashCode() == 1333469547 && opt.equals("--user")) {
                        c = 0;
                    }
                    if (c == 0 && takeUser) {
                        this.mUserId = UserHandle.parseUserArg(getNextArgRequired());
                    } else {
                        throw new CommandException("Unknown option: " + opt);
                    }
                } else {
                    return;
                }
            } while (ShortcutService.this.isUserUnlockedL(this.mUserId));
            throw new CommandException("User " + this.mUserId + " is not running or locked");
        }

        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            PrintWriter pw = getOutPrintWriter();
            char c = 65535;
            try {
                switch (cmd.hashCode()) {
                    case -1117067818:
                        if (cmd.equals("verify-states")) {
                            c = '\b';
                            break;
                        }
                        break;
                    case -749565587:
                        if (cmd.equals("clear-shortcuts")) {
                            c = 7;
                            break;
                        }
                        break;
                    case -139706031:
                        if (cmd.equals("reset-all-throttling")) {
                            c = 1;
                            break;
                        }
                        break;
                    case -76794781:
                        if (cmd.equals("override-config")) {
                            c = 2;
                            break;
                        }
                        break;
                    case 188791973:
                        if (cmd.equals("reset-throttling")) {
                            c = 0;
                            break;
                        }
                        break;
                    case 237193516:
                        if (cmd.equals("clear-default-launcher")) {
                            c = 4;
                            break;
                        }
                        break;
                    case 1190495043:
                        if (cmd.equals("get-default-launcher")) {
                            c = 5;
                            break;
                        }
                        break;
                    case 1411888601:
                        if (cmd.equals("unload-user")) {
                            c = 6;
                            break;
                        }
                        break;
                    case 1964247424:
                        if (cmd.equals("reset-config")) {
                            c = 3;
                            break;
                        }
                        break;
                }
                switch (c) {
                    case 0:
                        handleResetThrottling();
                        break;
                    case 1:
                        handleResetAllThrottling();
                        break;
                    case 2:
                        handleOverrideConfig();
                        break;
                    case 3:
                        handleResetConfig();
                        break;
                    case 4:
                        handleClearDefaultLauncher();
                        break;
                    case 5:
                        handleGetDefaultLauncher();
                        break;
                    case 6:
                        handleUnloadUser();
                        break;
                    case 7:
                        handleClearShortcuts();
                        break;
                    case '\b':
                        handleVerifyStates();
                        break;
                    default:
                        return handleDefaultCommands(cmd);
                }
                pw.println("Success");
                return 0;
            } catch (CommandException e) {
                pw.println("Error: " + e.getMessage());
                return 1;
            }
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Usage: cmd shortcut COMMAND [options ...]");
            pw.println();
            pw.println("cmd shortcut reset-throttling [--user USER_ID]");
            pw.println("    Reset throttling for all packages and users");
            pw.println();
            pw.println("cmd shortcut reset-all-throttling");
            pw.println("    Reset the throttling state for all users");
            pw.println();
            pw.println("cmd shortcut override-config CONFIG");
            pw.println("    Override the configuration for testing (will last until reboot)");
            pw.println();
            pw.println("cmd shortcut reset-config");
            pw.println("    Reset the configuration set with \"update-config\"");
            pw.println();
            pw.println("cmd shortcut clear-default-launcher [--user USER_ID]");
            pw.println("    Clear the cached default launcher");
            pw.println();
            pw.println("cmd shortcut get-default-launcher [--user USER_ID]");
            pw.println("    Show the default launcher");
            pw.println();
            pw.println("cmd shortcut unload-user [--user USER_ID]");
            pw.println("    Unload a user from the memory");
            pw.println("    (This should not affect any observable behavior)");
            pw.println();
            pw.println("cmd shortcut clear-shortcuts [--user USER_ID] PACKAGE");
            pw.println("    Remove all shortcuts from a package, including pinned shortcuts");
            pw.println();
        }

        private void handleResetThrottling() throws CommandException {
            synchronized (ShortcutService.this.mLock) {
                parseOptionsLocked(true);
                Slog.i(ShortcutService.TAG, "cmd: handleResetThrottling: user=" + this.mUserId);
                ShortcutService.this.resetThrottlingInner(this.mUserId);
            }
        }

        private void handleResetAllThrottling() {
            Slog.i(ShortcutService.TAG, "cmd: handleResetAllThrottling");
            ShortcutService.this.resetAllThrottlingInner();
        }

        private void handleOverrideConfig() throws CommandException {
            String config = getNextArgRequired();
            Slog.i(ShortcutService.TAG, "cmd: handleOverrideConfig: " + config);
            synchronized (ShortcutService.this.mLock) {
                if (!ShortcutService.this.updateConfigurationLocked(config)) {
                    throw new CommandException("override-config failed.  See logcat for details.");
                }
            }
        }

        private void handleResetConfig() {
            Slog.i(ShortcutService.TAG, "cmd: handleResetConfig");
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.loadConfigurationLocked();
            }
        }

        private void clearLauncher() {
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.getUserShortcutsLocked(this.mUserId).forceClearLauncher();
            }
        }

        private void showLauncher() {
            synchronized (ShortcutService.this.mLock) {
                ShortcutService.this.hasShortcutHostPermissionInner("-", this.mUserId);
                PrintWriter outPrintWriter = getOutPrintWriter();
                outPrintWriter.println("Launcher: " + ShortcutService.this.getUserShortcutsLocked(this.mUserId).getLastKnownLauncher());
            }
        }

        private void handleClearDefaultLauncher() throws CommandException {
            synchronized (ShortcutService.this.mLock) {
                parseOptionsLocked(true);
                clearLauncher();
            }
        }

        private void handleGetDefaultLauncher() throws CommandException {
            synchronized (ShortcutService.this.mLock) {
                parseOptionsLocked(true);
                clearLauncher();
                showLauncher();
            }
        }

        private void handleUnloadUser() throws CommandException {
            synchronized (ShortcutService.this.mLock) {
                parseOptionsLocked(true);
                Slog.i(ShortcutService.TAG, "cmd: handleUnloadUser: user=" + this.mUserId);
                ShortcutService.this.handleStopUser(this.mUserId);
            }
        }

        private void handleClearShortcuts() throws CommandException {
            synchronized (ShortcutService.this.mLock) {
                parseOptionsLocked(true);
                String packageName = getNextArgRequired();
                Slog.i(ShortcutService.TAG, "cmd: handleClearShortcuts: user" + this.mUserId + ", " + packageName);
                ShortcutService.this.cleanUpPackageForAllLoadedUsers(packageName, this.mUserId, true);
            }
        }

        private void handleVerifyStates() throws CommandException {
            try {
                ShortcutService.this.verifyStatesForce();
            } catch (Throwable th) {
                throw new CommandException(th.getMessage() + "\n" + Log.getStackTraceString(th));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public long injectCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public long injectElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    @VisibleForTesting
    long injectUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public int injectBinderCallingUid() {
        return getCallingUid();
    }

    @VisibleForTesting
    int injectBinderCallingPid() {
        return getCallingPid();
    }

    private int getCallingUserId() {
        return UserHandle.getUserId(injectBinderCallingUid());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public long injectClearCallingIdentity() {
        return Binder.clearCallingIdentity();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public void injectRestoreCallingIdentity(long token) {
        Binder.restoreCallingIdentity(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String injectBuildFingerprint() {
        return Build.FINGERPRINT;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void wtf(String message) {
        wtf(message, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void wtf(String message, Throwable e) {
        if (e == null) {
            e = new RuntimeException("Stacktrace");
        }
        synchronized (this.mLock) {
            this.mWtfCount++;
            this.mLastWtfStacktrace = new Exception("Last failure was logged here:");
        }
        Slog.wtf(TAG, message, e);
    }

    @VisibleForTesting
    File injectSystemDataPath() {
        return Environment.getDataSystemDirectory();
    }

    @VisibleForTesting
    File injectUserDataPath(int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), DIRECTORY_PER_USER);
    }

    public File getDumpPath() {
        return new File(injectUserDataPath(0), DIRECTORY_DUMP);
    }

    @VisibleForTesting
    boolean injectIsLowRamDevice() {
        return ActivityManager.isLowRamDeviceStatic();
    }

    @VisibleForTesting
    void injectRegisterUidObserver(IUidObserver observer, int which) {
        try {
            ActivityManager.getService().registerUidObserver(observer, which, -1, (String) null);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public File getUserBitmapFilePath(int userId) {
        return new File(injectUserDataPath(userId), DIRECTORY_BITMAPS);
    }

    @VisibleForTesting
    SparseArray<ShortcutUser> getShortcutsForTest() {
        return this.mUsers;
    }

    @VisibleForTesting
    int getMaxShortcutsForTest() {
        return this.mMaxShortcuts;
    }

    @VisibleForTesting
    int getMaxUpdatesPerIntervalForTest() {
        return this.mMaxUpdatesPerInterval;
    }

    @VisibleForTesting
    long getResetIntervalForTest() {
        return this.mResetInterval;
    }

    @VisibleForTesting
    int getMaxIconDimensionForTest() {
        return this.mMaxIconDimension;
    }

    @VisibleForTesting
    Bitmap.CompressFormat getIconPersistFormatForTest() {
        return this.mIconPersistFormat;
    }

    @VisibleForTesting
    int getIconPersistQualityForTest() {
        return this.mIconPersistQuality;
    }

    @VisibleForTesting
    ShortcutPackage getPackageShortcutForTest(String packageName, int userId) {
        synchronized (this.mLock) {
            ShortcutUser user = this.mUsers.get(userId);
            if (user == null) {
                return null;
            }
            return user.getAllPackagesForTest().get(packageName);
        }
    }

    @VisibleForTesting
    ShortcutInfo getPackageShortcutForTest(String packageName, String shortcutId, int userId) {
        synchronized (this.mLock) {
            ShortcutPackage pkg = getPackageShortcutForTest(packageName, userId);
            if (pkg == null) {
                return null;
            }
            return pkg.findShortcutById(shortcutId);
        }
    }

    @VisibleForTesting
    ShortcutLauncher getLauncherShortcutForTest(String packageName, int userId) {
        synchronized (this.mLock) {
            ShortcutUser user = this.mUsers.get(userId);
            if (user == null) {
                return null;
            }
            return user.getAllLaunchersForTest().get(ShortcutUser.PackageWithUser.of(userId, packageName));
        }
    }

    @VisibleForTesting
    ShortcutRequestPinProcessor getShortcutRequestPinProcessorForTest() {
        return this.mShortcutRequestPinProcessor;
    }

    @VisibleForTesting
    boolean injectShouldPerformVerification() {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public final void verifyStates() {
        if (injectShouldPerformVerification()) {
            verifyStatesInner();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void verifyStatesForce() {
        verifyStatesInner();
    }

    private void verifyStatesInner() {
        synchronized (this.mLock) {
            forEachLoadedUserLocked(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutService$iMiNoPdGwz3-PvMFlFpgdWX24mE
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((ShortcutUser) obj).forAllPackageItems(new Consumer() { // from class: com.android.server.pm.-$$Lambda$sAnQjWlQDJoJcSwHDDCKcU2fneU
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj2) {
                            ((ShortcutPackageItem) obj2).verifyStates();
                        }
                    });
                }
            });
        }
    }

    @VisibleForTesting
    void waitForBitmapSavesForTest() {
        synchronized (this.mLock) {
            this.mShortcutBitmapSaver.waitForAllSavesLocked();
        }
    }
}
