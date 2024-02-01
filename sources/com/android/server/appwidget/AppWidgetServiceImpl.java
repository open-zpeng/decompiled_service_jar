package com.android.server.appwidget;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManagerInternal;
import android.appwidget.AppWidgetManagerInternal;
import android.appwidget.AppWidgetProviderInfo;
import android.appwidget.PendingHostUpdate;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TypedValue;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.WindowManager;
import android.widget.RemoteViews;
import com.android.internal.R;
import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.widget.IRemoteViewsFactory;
import com.android.server.LocalServices;
import com.android.server.WidgetBackupProvider;
import com.android.server.policy.IconUtilities;
import com.android.server.utils.PriorityDump;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AppWidgetServiceImpl extends IAppWidgetService.Stub implements WidgetBackupProvider, DevicePolicyManagerInternal.OnCrossProfileWidgetProvidersChangeListener {
    private static final int CURRENT_VERSION = 1;
    private static boolean DEBUG = false;
    private static final int ID_PROVIDER_CHANGED = 1;
    private static final int ID_VIEWS_UPDATE = 0;
    private static final int KEYGUARD_HOST_ID = 1262836039;
    private static final int LOADED_PROFILE_ID = -1;
    private static final int MIN_UPDATE_PERIOD;
    private static final String NEW_KEYGUARD_HOST_PACKAGE = "com.android.keyguard";
    private static final String OLD_KEYGUARD_HOST_PACKAGE = "android";
    private static final String STATE_FILENAME = "appwidgets.xml";
    private static final String TAG = "AppWidgetServiceImpl";
    private static final int TAG_UNDEFINED = -1;
    private static final int UNKNOWN_UID = -1;
    private static final int UNKNOWN_USER_ID = -10;
    private static final AtomicLong UPDATE_COUNTER;
    private AlarmManager mAlarmManager;
    private AppOpsManager mAppOpsManager;
    private BackupRestoreController mBackupRestoreController;
    private Handler mCallbackHandler;
    private final Context mContext;
    private DevicePolicyManagerInternal mDevicePolicyManagerInternal;
    private IconUtilities mIconUtilities;
    private KeyguardManager mKeyguardManager;
    private Locale mLocale;
    private int mMaxWidgetBitmapMemory;
    private IPackageManager mPackageManager;
    private PackageManagerInternal mPackageManagerInternal;
    private boolean mSafeMode;
    private Handler mSaveStateHandler;
    private SecurityPolicy mSecurityPolicy;
    private UserManager mUserManager;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.appwidget.AppWidgetServiceImpl.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
            if (AppWidgetServiceImpl.DEBUG) {
                Slog.i(AppWidgetServiceImpl.TAG, "Received broadcast: " + action + " on user " + userId);
            }
            char c = 65535;
            switch (action.hashCode()) {
                case -1238404651:
                    if (action.equals("android.intent.action.MANAGED_PROFILE_UNAVAILABLE")) {
                        c = 2;
                        break;
                    }
                    break;
                case -1001645458:
                    if (action.equals("android.intent.action.PACKAGES_SUSPENDED")) {
                        c = 3;
                        break;
                    }
                    break;
                case -864107122:
                    if (action.equals("android.intent.action.MANAGED_PROFILE_AVAILABLE")) {
                        c = 1;
                        break;
                    }
                    break;
                case 158859398:
                    if (action.equals("android.intent.action.CONFIGURATION_CHANGED")) {
                        c = 0;
                        break;
                    }
                    break;
                case 1290767157:
                    if (action.equals("android.intent.action.PACKAGES_UNSUSPENDED")) {
                        c = 4;
                        break;
                    }
                    break;
            }
            if (c == 0) {
                AppWidgetServiceImpl.this.onConfigurationChanged();
            } else if (c == 1 || c == 2) {
                synchronized (AppWidgetServiceImpl.this.mLock) {
                    AppWidgetServiceImpl.this.reloadWidgetsMaskedState(userId);
                }
            } else if (c == 3) {
                AppWidgetServiceImpl.this.onPackageBroadcastReceived(intent, getSendingUserId());
                AppWidgetServiceImpl.this.updateWidgetPackageSuspensionMaskedState(intent, true, getSendingUserId());
            } else if (c != 4) {
                AppWidgetServiceImpl.this.onPackageBroadcastReceived(intent, getSendingUserId());
            } else {
                AppWidgetServiceImpl.this.onPackageBroadcastReceived(intent, getSendingUserId());
                AppWidgetServiceImpl.this.updateWidgetPackageSuspensionMaskedState(intent, false, getSendingUserId());
            }
        }
    };
    private final HashMap<Pair<Integer, Intent.FilterComparison>, HashSet<Integer>> mRemoteViewsServicesAppWidgets = new HashMap<>();
    private final Object mLock = new Object();
    private final ArrayList<Widget> mWidgets = new ArrayList<>();
    private final ArrayList<Host> mHosts = new ArrayList<>();
    private final ArrayList<Provider> mProviders = new ArrayList<>();
    private final ArraySet<Pair<Integer, String>> mPackagesWithBindWidgetPermission = new ArraySet<>();
    private final SparseIntArray mLoadedUserIds = new SparseIntArray();
    private final SparseArray<ArraySet<String>> mWidgetPackages = new SparseArray<>();
    private final SparseIntArray mNextAppWidgetIds = new SparseIntArray();

    static {
        MIN_UPDATE_PERIOD = DEBUG ? 0 : 1800000;
        UPDATE_COUNTER = new AtomicLong();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWidgetServiceImpl(Context context) {
        this.mContext = context;
    }

    public void onStart() {
        this.mPackageManager = AppGlobals.getPackageManager();
        this.mAlarmManager = (AlarmManager) this.mContext.getSystemService("alarm");
        this.mUserManager = (UserManager) this.mContext.getSystemService("user");
        this.mAppOpsManager = (AppOpsManager) this.mContext.getSystemService("appops");
        this.mKeyguardManager = (KeyguardManager) this.mContext.getSystemService("keyguard");
        this.mDevicePolicyManagerInternal = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class);
        this.mPackageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        this.mSaveStateHandler = BackgroundThread.getHandler();
        this.mCallbackHandler = new CallbackHandler(this.mContext.getMainLooper());
        this.mBackupRestoreController = new BackupRestoreController();
        this.mSecurityPolicy = new SecurityPolicy();
        this.mIconUtilities = new IconUtilities(this.mContext);
        computeMaximumWidgetBitmapMemory();
        registerBroadcastReceiver();
        registerOnCrossProfileProvidersChangedListener();
        LocalServices.addService(AppWidgetManagerInternal.class, new AppWidgetManagerLocal());
    }

    private void computeMaximumWidgetBitmapMemory() {
        WindowManager wm = (WindowManager) this.mContext.getSystemService("window");
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        this.mMaxWidgetBitmapMemory = size.x * 6 * size.y;
    }

    private void registerBroadcastReceiver() {
        IntentFilter configFilter = new IntentFilter();
        configFilter.addAction("android.intent.action.CONFIGURATION_CHANGED");
        this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, configFilter, null, null);
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
        packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
        packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        packageFilter.addDataScheme("package");
        this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, packageFilter, null, null);
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction("android.intent.action.EXTERNAL_APPLICATIONS_AVAILABLE");
        sdFilter.addAction("android.intent.action.EXTERNAL_APPLICATIONS_UNAVAILABLE");
        this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, sdFilter, null, null);
        IntentFilter offModeFilter = new IntentFilter();
        offModeFilter.addAction("android.intent.action.MANAGED_PROFILE_AVAILABLE");
        offModeFilter.addAction("android.intent.action.MANAGED_PROFILE_UNAVAILABLE");
        this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, offModeFilter, null, null);
        IntentFilter suspendPackageFilter = new IntentFilter();
        suspendPackageFilter.addAction("android.intent.action.PACKAGES_SUSPENDED");
        suspendPackageFilter.addAction("android.intent.action.PACKAGES_UNSUSPENDED");
        this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, suspendPackageFilter, null, null);
    }

    private void registerOnCrossProfileProvidersChangedListener() {
        DevicePolicyManagerInternal devicePolicyManagerInternal = this.mDevicePolicyManagerInternal;
        if (devicePolicyManagerInternal != null) {
            devicePolicyManagerInternal.addOnCrossProfileWidgetProvidersChangeListener(this);
        }
    }

    public void setSafeMode(boolean safeMode) {
        this.mSafeMode = safeMode;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onConfigurationChanged() {
        Locale locale;
        if (DEBUG) {
            Slog.i(TAG, "onConfigurationChanged()");
        }
        Locale revised = Locale.getDefault();
        if (revised == null || (locale = this.mLocale) == null || !revised.equals(locale)) {
            this.mLocale = revised;
            synchronized (this.mLock) {
                SparseIntArray changedGroups = null;
                ArrayList<Provider> installedProviders = new ArrayList<>(this.mProviders);
                HashSet<ProviderId> removedProviders = new HashSet<>();
                int N = installedProviders.size();
                for (int i = N - 1; i >= 0; i--) {
                    Provider provider = installedProviders.get(i);
                    int userId = provider.getUserId();
                    if (this.mUserManager.isUserUnlockingOrUnlocked(userId) && !isProfileWithLockedParent(userId)) {
                        ensureGroupStateLoadedLocked(userId);
                        if (!removedProviders.contains(provider.id)) {
                            boolean changed = updateProvidersForPackageLocked(provider.id.componentName.getPackageName(), provider.getUserId(), removedProviders);
                            if (changed) {
                                if (changedGroups == null) {
                                    changedGroups = new SparseIntArray();
                                }
                                int groupId = this.mSecurityPolicy.getGroupParent(provider.getUserId());
                                changedGroups.put(groupId, groupId);
                            }
                        }
                    }
                }
                if (changedGroups != null) {
                    int groupCount = changedGroups.size();
                    for (int i2 = 0; i2 < groupCount; i2++) {
                        saveGroupStateAsync(changedGroups.get(i2));
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:54:0x00a8, code lost:
        r9 = r6.length;
     */
    /* JADX WARN: Code restructure failed: missing block: B:55:0x00a9, code lost:
        if (r8 >= r9) goto L42;
     */
    /* JADX WARN: Code restructure failed: missing block: B:56:0x00ab, code lost:
        r3 = r3 | removeHostsAndProvidersForPackageLocked(r6[r8], r14);
        r8 = r8 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:58:0x00b7, code lost:
        if (r5 == null) goto L51;
     */
    /* JADX WARN: Code restructure failed: missing block: B:60:0x00bf, code lost:
        if (r5.getBoolean("android.intent.extra.REPLACING", false) != false) goto L66;
     */
    /* JADX WARN: Removed duplicated region for block: B:74:0x00e1 A[Catch: all -> 0x00eb, TryCatch #0 {, blocks: (B:38:0x007d, B:40:0x0085, B:43:0x008c, B:48:0x009a, B:54:0x00a8, B:56:0x00ab, B:74:0x00e1, B:75:0x00e7, B:59:0x00b9, B:63:0x00c3, B:65:0x00c6, B:68:0x00d2, B:70:0x00d8, B:71:0x00db, B:77:0x00e9), top: B:83:0x007d }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void onPackageBroadcastReceived(android.content.Intent r13, int r14) {
        /*
            Method dump skipped, instructions count: 258
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.appwidget.AppWidgetServiceImpl.onPackageBroadcastReceived(android.content.Intent, int):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reloadWidgetsMaskedStateForGroup(int userId) {
        if (!this.mUserManager.isUserUnlockingOrUnlocked(userId)) {
            return;
        }
        synchronized (this.mLock) {
            reloadWidgetsMaskedState(userId);
            int[] profileIds = this.mUserManager.getEnabledProfileIds(userId);
            for (int profileId : profileIds) {
                reloadWidgetsMaskedState(profileId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadWidgetsMaskedState(int userId) {
        boolean suspended;
        long identity = Binder.clearCallingIdentity();
        try {
            UserInfo user = this.mUserManager.getUserInfo(userId);
            boolean lockedProfile = !this.mUserManager.isUserUnlockingOrUnlocked(userId);
            boolean quietProfile = user.isQuietModeEnabled();
            int N = this.mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = this.mProviders.get(i);
                int providerUserId = provider.getUserId();
                if (providerUserId == userId) {
                    boolean changed = provider.setMaskedByLockedProfileLocked(lockedProfile) | provider.setMaskedByQuietProfileLocked(quietProfile);
                    try {
                        try {
                            suspended = this.mPackageManager.isPackageSuspendedForUser(provider.info.provider.getPackageName(), provider.getUserId());
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Failed to query application info", e);
                        }
                    } catch (IllegalArgumentException e2) {
                        suspended = false;
                    }
                    changed |= provider.setMaskedBySuspendedPackageLocked(suspended);
                    if (changed) {
                        if (provider.isMaskedLocked()) {
                            maskWidgetsViewsLocked(provider, null);
                        } else {
                            unmaskWidgetsViewsLocked(provider);
                        }
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateWidgetPackageSuspensionMaskedState(Intent intent, boolean suspended, int profileId) {
        String[] packagesArray = intent.getStringArrayExtra("android.intent.extra.changed_package_list");
        if (packagesArray == null) {
            return;
        }
        Set<String> packages = new ArraySet<>(Arrays.asList(packagesArray));
        synchronized (this.mLock) {
            int N = this.mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = this.mProviders.get(i);
                int providerUserId = provider.getUserId();
                if (providerUserId == profileId && packages.contains(provider.info.provider.getPackageName()) && provider.setMaskedBySuspendedPackageLocked(suspended)) {
                    if (provider.isMaskedLocked()) {
                        maskWidgetsViewsLocked(provider, null);
                    } else {
                        unmaskWidgetsViewsLocked(provider);
                    }
                }
            }
        }
    }

    private Bitmap createMaskedWidgetBitmap(String providerPackage, int providerUserId) {
        long identity = Binder.clearCallingIdentity();
        try {
            Context userContext = this.mContext.createPackageContextAsUser(providerPackage, 0, UserHandle.of(providerUserId));
            PackageManager pm = userContext.getPackageManager();
            Drawable icon = pm.getApplicationInfo(providerPackage, 0).loadUnbadgedIcon(pm).mutate();
            icon.setColorFilter(this.mIconUtilities.getDisabledColorFilter());
            return this.mIconUtilities.createIconBitmap(icon);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Fail to get application icon", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private RemoteViews createMaskedWidgetRemoteViews(Bitmap icon, boolean showBadge, PendingIntent onClickIntent) {
        RemoteViews views = new RemoteViews(this.mContext.getPackageName(), 17367344);
        if (icon != null) {
            views.setImageViewBitmap(16909664, icon);
        }
        if (!showBadge) {
            views.setViewVisibility(16909665, 4);
        }
        if (onClickIntent != null) {
            views.setOnClickPendingIntent(16909666, onClickIntent);
        }
        return views;
    }

    private void maskWidgetsViewsLocked(Provider provider, Widget targetWidget) {
        String providerPackage;
        int providerUserId;
        Bitmap iconBitmap;
        boolean showBadge;
        Intent onClickIntent;
        Provider provider2 = provider;
        int widgetCount = provider2.widgets.size();
        if (widgetCount == 0 || (iconBitmap = createMaskedWidgetBitmap((providerPackage = provider2.info.provider.getPackageName()), (providerUserId = provider.getUserId()))) == null) {
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            if (provider2.maskedBySuspendedPackage) {
                UserInfo userInfo = this.mUserManager.getUserInfo(providerUserId);
                showBadge = userInfo.isManagedProfile();
                String suspendingPackage = this.mPackageManagerInternal.getSuspendingPackage(providerPackage, providerUserId);
                if ("android".equals(suspendingPackage)) {
                    onClickIntent = this.mDevicePolicyManagerInternal.createShowAdminSupportIntent(providerUserId, true);
                } else {
                    SuspendDialogInfo dialogInfo = this.mPackageManagerInternal.getSuspendedDialogInfo(providerPackage, providerUserId);
                    onClickIntent = SuspendedAppActivity.createSuspendedAppInterceptIntent(providerPackage, suspendingPackage, dialogInfo, providerUserId);
                }
            } else if (provider2.maskedByQuietProfile) {
                showBadge = true;
                onClickIntent = UnlaunchableAppActivity.createInQuietModeDialogIntent(providerUserId);
            } else {
                showBadge = true;
                onClickIntent = this.mKeyguardManager.createConfirmDeviceCredentialIntent(null, null, providerUserId);
                if (onClickIntent != null) {
                    onClickIntent.setFlags(276824064);
                }
            }
            int j = 0;
            while (j < widgetCount) {
                Widget widget = provider2.widgets.get(j);
                if (targetWidget == null || targetWidget == widget) {
                    PendingIntent intent = null;
                    if (onClickIntent != null) {
                        intent = PendingIntent.getActivity(this.mContext, widget.appWidgetId, onClickIntent, 134217728);
                    }
                    RemoteViews views = createMaskedWidgetRemoteViews(iconBitmap, showBadge, intent);
                    if (widget.replaceWithMaskedViewsLocked(views)) {
                        scheduleNotifyUpdateAppWidgetLocked(widget, widget.getEffectiveViewsLocked());
                    }
                }
                j++;
                provider2 = provider;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void unmaskWidgetsViewsLocked(Provider provider) {
        int widgetCount = provider.widgets.size();
        for (int j = 0; j < widgetCount; j++) {
            Widget widget = provider.widgets.get(j);
            if (widget.clearMaskedViewsLocked()) {
                scheduleNotifyUpdateAppWidgetLocked(widget, widget.getEffectiveViewsLocked());
            }
        }
    }

    private void resolveHostUidLocked(String pkg, int uid) {
        int N = this.mHosts.size();
        for (int i = 0; i < N; i++) {
            Host host = this.mHosts.get(i);
            if (host.id.uid == -1 && pkg.equals(host.id.packageName)) {
                if (DEBUG) {
                    Slog.i(TAG, "host " + host.id + " resolved to uid " + uid);
                }
                host.id = new HostId(uid, host.id.hostId, host.id.packageName);
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void ensureGroupStateLoadedLocked(int userId) {
        ensureGroupStateLoadedLocked(userId, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void ensureGroupStateLoadedLocked(int userId, boolean enforceUserUnlockingOrUnlocked) {
        if (enforceUserUnlockingOrUnlocked && !isUserRunningAndUnlocked(userId)) {
            throw new IllegalStateException("User " + userId + " must be unlocked for widgets to be available");
        } else if (enforceUserUnlockingOrUnlocked && isProfileWithLockedParent(userId)) {
            throw new IllegalStateException("Profile " + userId + " must have unlocked parent");
        } else {
            int[] profileIds = this.mSecurityPolicy.getEnabledGroupProfileIds(userId);
            int newMemberCount = 0;
            int profileIdCount = profileIds.length;
            for (int i = 0; i < profileIdCount; i++) {
                if (this.mLoadedUserIds.indexOfKey(profileIds[i]) >= 0) {
                    profileIds[i] = -1;
                } else {
                    newMemberCount++;
                }
            }
            if (newMemberCount <= 0) {
                return;
            }
            int newMemberIndex = 0;
            int[] newProfileIds = new int[newMemberCount];
            for (int profileId : profileIds) {
                if (profileId != -1) {
                    this.mLoadedUserIds.put(profileId, profileId);
                    newProfileIds[newMemberIndex] = profileId;
                    newMemberIndex++;
                }
            }
            clearProvidersAndHostsTagsLocked();
            loadGroupWidgetProvidersLocked(newProfileIds);
            loadGroupStateLocked(newProfileIds);
        }
    }

    private boolean isUserRunningAndUnlocked(int userId) {
        return this.mUserManager.isUserUnlockingOrUnlocked(userId);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            synchronized (this.mLock) {
                if (args.length > 0 && PriorityDump.PROTO_ARG.equals(args[0])) {
                    dumpProto(fd);
                } else {
                    dumpInternal(pw);
                }
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        Slog.i(TAG, "dump proto for " + this.mWidgets.size() + " widgets");
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        int N = this.mWidgets.size();
        for (int i = 0; i < N; i++) {
            dumpProtoWidget(proto, this.mWidgets.get(i));
        }
        proto.flush();
    }

    private void dumpProtoWidget(ProtoOutputStream proto, Widget widget) {
        if (widget.host == null || widget.provider == null) {
            Slog.d(TAG, "skip dumping widget because host or provider is null: widget.host=" + widget.host + " widget.provider=" + widget.provider);
            return;
        }
        long token = proto.start(2246267895809L);
        proto.write(1133871366145L, widget.host.getUserId() != widget.provider.getUserId());
        proto.write(1133871366146L, widget.host.callbacks == null);
        proto.write(1138166333443L, widget.host.id.packageName);
        proto.write(1138166333444L, widget.provider.id.componentName.getPackageName());
        proto.write(1138166333445L, widget.provider.id.componentName.getClassName());
        if (widget.options != null) {
            proto.write(1120986464262L, widget.options.getInt("appWidgetMinWidth", 0));
            proto.write(1120986464263L, widget.options.getInt("appWidgetMinHeight", 0));
            proto.write(1120986464264L, widget.options.getInt("appWidgetMaxWidth", 0));
            proto.write(1120986464265L, widget.options.getInt("appWidgetMaxHeight", 0));
        }
        proto.end(token);
    }

    private void dumpInternal(PrintWriter pw) {
        int N = this.mProviders.size();
        pw.println("Providers:");
        for (int i = 0; i < N; i++) {
            dumpProvider(this.mProviders.get(i), i, pw);
        }
        int N2 = this.mWidgets.size();
        pw.println(" ");
        pw.println("Widgets:");
        for (int i2 = 0; i2 < N2; i2++) {
            dumpWidget(this.mWidgets.get(i2), i2, pw);
        }
        int N3 = this.mHosts.size();
        pw.println(" ");
        pw.println("Hosts:");
        for (int i3 = 0; i3 < N3; i3++) {
            dumpHost(this.mHosts.get(i3), i3, pw);
        }
        int N4 = this.mPackagesWithBindWidgetPermission.size();
        pw.println(" ");
        pw.println("Grants:");
        for (int i4 = 0; i4 < N4; i4++) {
            Pair<Integer, String> grant = this.mPackagesWithBindWidgetPermission.valueAt(i4);
            dumpGrant(grant, i4, pw);
        }
    }

    public ParceledListSlice<PendingHostUpdate> startListening(IAppWidgetHost callbacks, String callingPackage, int hostId, int[] appWidgetIds) {
        HostId id;
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "startListening() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            try {
                try {
                    if (this.mSecurityPolicy.isInstantAppLocked(callingPackage, userId)) {
                        Slog.w(TAG, "Instant package " + callingPackage + " cannot host app widgets");
                        return ParceledListSlice.emptyList();
                    }
                    ensureGroupStateLoadedLocked(userId);
                    try {
                        HostId id2 = new HostId(Binder.getCallingUid(), hostId, callingPackage);
                        Host host = lookupOrAddHostLocked(id2);
                        host.callbacks = callbacks;
                        long updateSequenceNo = UPDATE_COUNTER.incrementAndGet();
                        int N = appWidgetIds.length;
                        ArrayList<PendingHostUpdate> outUpdates = new ArrayList<>(N);
                        LongSparseArray<PendingHostUpdate> updatesMap = new LongSparseArray<>();
                        int i = 0;
                        while (i < N) {
                            if (!host.getPendingUpdatesForId(appWidgetIds[i], updatesMap)) {
                                id = id2;
                            } else {
                                int M = updatesMap.size();
                                id = id2;
                                for (int j = 0; j < M; j++) {
                                    outUpdates.add(updatesMap.valueAt(j));
                                }
                            }
                            i++;
                            id2 = id;
                        }
                        host.lastWidgetUpdateSequenceNo = updateSequenceNo;
                        return new ParceledListSlice<>(outUpdates);
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

    public void stopListening(String callingPackage, int hostId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "stopListening() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId, false);
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupHostLocked(id);
            if (host != null) {
                host.callbacks = null;
                pruneHostLocked(host);
            }
        }
    }

    public int allocateAppWidgetId(String callingPackage, int hostId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "allocateAppWidgetId() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            if (this.mSecurityPolicy.isInstantAppLocked(callingPackage, userId)) {
                Slog.w(TAG, "Instant package " + callingPackage + " cannot host app widgets");
                return 0;
            }
            ensureGroupStateLoadedLocked(userId);
            if (this.mNextAppWidgetIds.indexOfKey(userId) < 0) {
                this.mNextAppWidgetIds.put(userId, 1);
            }
            int appWidgetId = incrementAndGetAppWidgetIdLocked(userId);
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupOrAddHostLocked(id);
            Widget widget = new Widget();
            widget.appWidgetId = appWidgetId;
            widget.host = host;
            host.widgets.add(widget);
            addWidgetLocked(widget);
            saveGroupStateAsync(userId);
            if (DEBUG) {
                Slog.i(TAG, "Allocated widget id " + appWidgetId + " for host " + host.id);
            }
            return appWidgetId;
        }
    }

    public void deleteAppWidgetId(String callingPackage, int appWidgetId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "deleteAppWidgetId() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
            if (widget == null) {
                return;
            }
            deleteAppWidgetLocked(widget);
            saveGroupStateAsync(userId);
            if (DEBUG) {
                Slog.i(TAG, "Deleted widget id " + appWidgetId + " for host " + widget.host.id);
            }
        }
    }

    public boolean hasBindAppWidgetPermission(String packageName, int grantId) {
        if (DEBUG) {
            Slog.i(TAG, "hasBindAppWidgetPermission() " + UserHandle.getCallingUserId());
        }
        this.mSecurityPolicy.enforceModifyAppWidgetBindPermissions(packageName);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(grantId);
            int packageUid = getUidForPackage(packageName, grantId);
            if (packageUid < 0) {
                return false;
            }
            Pair<Integer, String> packageId = Pair.create(Integer.valueOf(grantId), packageName);
            return this.mPackagesWithBindWidgetPermission.contains(packageId);
        }
    }

    public void setBindAppWidgetPermission(String packageName, int grantId, boolean grantPermission) {
        if (DEBUG) {
            Slog.i(TAG, "setBindAppWidgetPermission() " + UserHandle.getCallingUserId());
        }
        this.mSecurityPolicy.enforceModifyAppWidgetBindPermissions(packageName);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(grantId);
            int packageUid = getUidForPackage(packageName, grantId);
            if (packageUid < 0) {
                return;
            }
            Pair<Integer, String> packageId = Pair.create(Integer.valueOf(grantId), packageName);
            if (grantPermission) {
                this.mPackagesWithBindWidgetPermission.add(packageId);
            } else {
                this.mPackagesWithBindWidgetPermission.remove(packageId);
            }
            saveGroupStateAsync(grantId);
        }
    }

    public IntentSender createAppWidgetConfigIntentSender(String callingPackage, int appWidgetId, int intentFlags) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "createAppWidgetConfigIntentSender() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                ensureGroupStateLoadedLocked(userId);
                Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
                if (widget == null) {
                    throw new IllegalArgumentException("Bad widget id " + appWidgetId);
                }
                Provider provider = widget.provider;
                if (provider == null) {
                    throw new IllegalArgumentException("Widget not bound " + appWidgetId);
                }
                int secureFlags = intentFlags & (-196);
                Intent intent = new Intent("android.appwidget.action.APPWIDGET_CONFIGURE");
                intent.putExtra("appWidgetId", appWidgetId);
                intent.setComponent(provider.info.configure);
                intent.setFlags(secureFlags);
                long identity = Binder.clearCallingIdentity();
                IntentSender intentSender = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 1409286144, null, new UserHandle(provider.getUserId())).getIntentSender();
                Binder.restoreCallingIdentity(identity);
                return intentSender;
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    public boolean bindAppWidgetId(String callingPackage, int appWidgetId, int providerProfileId, ComponentName providerComponent, Bundle options) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "bindAppWidgetId() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        if (this.mSecurityPolicy.isEnabledGroupProfile(providerProfileId) && this.mSecurityPolicy.isProviderInCallerOrInProfileAndWhitelListed(providerComponent.getPackageName(), providerProfileId)) {
            synchronized (this.mLock) {
                ensureGroupStateLoadedLocked(userId);
                if (this.mSecurityPolicy.hasCallerBindPermissionOrBindWhiteListedLocked(callingPackage)) {
                    Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
                    if (widget == null) {
                        Slog.e(TAG, "Bad widget id " + appWidgetId);
                        return false;
                    } else if (widget.provider == null) {
                        int providerUid = getUidForPackage(providerComponent.getPackageName(), providerProfileId);
                        if (providerUid < 0) {
                            Slog.e(TAG, "Package " + providerComponent.getPackageName() + " not installed  for profile " + providerProfileId);
                            return false;
                        }
                        ProviderId providerId = new ProviderId(providerUid, providerComponent);
                        Provider provider = lookupProviderLocked(providerId);
                        if (provider == null) {
                            Slog.e(TAG, "No widget provider " + providerComponent + " for profile " + providerProfileId);
                            return false;
                        } else if (provider.zombie) {
                            Slog.e(TAG, "Can't bind to a 3rd party provider in safe mode " + provider);
                            return false;
                        } else {
                            widget.provider = provider;
                            widget.options = options != null ? cloneIfLocalBinder(options) : new Bundle();
                            if (!widget.options.containsKey("appWidgetCategory")) {
                                widget.options.putInt("appWidgetCategory", 1);
                            }
                            provider.widgets.add(widget);
                            onWidgetProviderAddedOrChangedLocked(widget);
                            int widgetCount = provider.widgets.size();
                            if (widgetCount == 1) {
                                sendEnableIntentLocked(provider);
                            }
                            sendUpdateIntentLocked(provider, new int[]{appWidgetId});
                            registerForBroadcastsLocked(provider, getWidgetIds(provider.widgets));
                            saveGroupStateAsync(userId);
                            if (DEBUG) {
                                Slog.i(TAG, "Bound widget " + appWidgetId + " to provider " + provider.id);
                            }
                            return true;
                        }
                    } else {
                        Slog.e(TAG, "Widget id " + appWidgetId + " already bound to: " + widget.provider.id);
                        return false;
                    }
                }
                return false;
            }
        }
        return false;
    }

    public int[] getAppWidgetIds(ComponentName componentName) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetIds() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(componentName.getPackageName());
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            ProviderId providerId = new ProviderId(Binder.getCallingUid(), componentName);
            Provider provider = lookupProviderLocked(providerId);
            if (provider != null) {
                return getWidgetIds(provider.widgets);
            }
            return new int[0];
        }
    }

    public int[] getAppWidgetIdsForHost(String callingPackage, int hostId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetIdsForHost() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupHostLocked(id);
            if (host != null) {
                return getWidgetIds(host.widgets);
            }
            return new int[0];
        }
    }

    public boolean bindRemoteViewsService(String callingPackage, int appWidgetId, Intent intent, IApplicationThread caller, IBinder activtiyToken, IServiceConnection connection, int flags) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "bindRemoteViewsService() " + userId);
        }
        synchronized (this.mLock) {
            try {
                try {
                    ensureGroupStateLoadedLocked(userId);
                } catch (Throwable th) {
                    th = th;
                }
                try {
                    Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
                    if (widget != null) {
                        if (widget.provider == null) {
                            throw new IllegalArgumentException("No provider for widget " + appWidgetId);
                        }
                        ComponentName componentName = intent.getComponent();
                        String providerPackage = widget.provider.id.componentName.getPackageName();
                        String servicePackage = componentName.getPackageName();
                        if (servicePackage.equals(providerPackage)) {
                            this.mSecurityPolicy.enforceServiceExistsAndRequiresBindRemoteViewsPermission(componentName, widget.provider.getUserId());
                            long callingIdentity = Binder.clearCallingIdentity();
                            try {
                            } catch (RemoteException e) {
                            } catch (Throwable th2) {
                                th = th2;
                            }
                            try {
                            } catch (RemoteException e2) {
                                Binder.restoreCallingIdentity(callingIdentity);
                                return false;
                            } catch (Throwable th3) {
                                th = th3;
                                Binder.restoreCallingIdentity(callingIdentity);
                                throw th;
                            }
                            if (ActivityManager.getService().bindService(caller, activtiyToken, intent, intent.resolveTypeIfNeeded(this.mContext.getContentResolver()), connection, flags, this.mContext.getOpPackageName(), widget.provider.getUserId()) == 0) {
                                Binder.restoreCallingIdentity(callingIdentity);
                                return false;
                            }
                            incrementAppWidgetServiceRefCount(appWidgetId, Pair.create(Integer.valueOf(widget.provider.id.uid), new Intent.FilterComparison(intent)));
                            Binder.restoreCallingIdentity(callingIdentity);
                            return true;
                        }
                        throw new SecurityException("The taget service not in the same package as the widget provider");
                    }
                    throw new IllegalArgumentException("Bad widget id");
                } catch (Throwable th4) {
                    th = th4;
                    throw th;
                }
            } catch (Throwable th5) {
                th = th5;
                throw th;
            }
        }
    }

    public void deleteHost(String callingPackage, int hostId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "deleteHost() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            HostId id = new HostId(Binder.getCallingUid(), hostId, callingPackage);
            Host host = lookupHostLocked(id);
            if (host == null) {
                return;
            }
            deleteHostLocked(host);
            saveGroupStateAsync(userId);
            if (DEBUG) {
                Slog.i(TAG, "Deleted host " + host.id);
            }
        }
    }

    public void deleteAllHosts() {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "deleteAllHosts() " + userId);
        }
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            boolean changed = false;
            int N = this.mHosts.size();
            for (int i = N - 1; i >= 0; i--) {
                Host host = this.mHosts.get(i);
                if (host.id.uid == Binder.getCallingUid()) {
                    deleteHostLocked(host);
                    changed = true;
                    if (DEBUG) {
                        Slog.i(TAG, "Deleted host " + host.id);
                    }
                }
            }
            if (changed) {
                saveGroupStateAsync(userId);
            }
        }
    }

    public AppWidgetProviderInfo getAppWidgetInfo(String callingPackage, int appWidgetId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetInfo() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
            if (widget != null && widget.provider != null && !widget.provider.zombie) {
                return cloneIfLocalBinder(widget.provider.info);
            }
            return null;
        }
    }

    public RemoteViews getAppWidgetViews(String callingPackage, int appWidgetId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetViews() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
            if (widget != null) {
                return cloneIfLocalBinder(widget.getEffectiveViewsLocked());
            }
            return null;
        }
    }

    public void updateAppWidgetOptions(String callingPackage, int appWidgetId, Bundle options) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetOptions() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
            if (widget == null) {
                return;
            }
            widget.options.putAll(options);
            sendOptionsChangedIntentLocked(widget);
            saveGroupStateAsync(userId);
        }
    }

    public Bundle getAppWidgetOptions(String callingPackage, int appWidgetId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "getAppWidgetOptions() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
            if (widget != null && widget.options != null) {
                return cloneIfLocalBinder(widget.options);
            }
            return Bundle.EMPTY;
        }
    }

    public void updateAppWidgetIds(String callingPackage, int[] appWidgetIds, RemoteViews views) {
        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetIds() " + UserHandle.getCallingUserId());
        }
        updateAppWidgetIds(callingPackage, appWidgetIds, views, false);
    }

    public void partiallyUpdateAppWidgetIds(String callingPackage, int[] appWidgetIds, RemoteViews views) {
        if (DEBUG) {
            Slog.i(TAG, "partiallyUpdateAppWidgetIds() " + UserHandle.getCallingUserId());
        }
        updateAppWidgetIds(callingPackage, appWidgetIds, views, true);
    }

    public void notifyAppWidgetViewDataChanged(String callingPackage, int[] appWidgetIds, int viewId) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "notifyAppWidgetViewDataChanged() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            for (int appWidgetId : appWidgetIds) {
                Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
                if (widget != null) {
                    scheduleNotifyAppWidgetViewDataChanged(widget, viewId);
                }
            }
        }
    }

    public void updateAppWidgetProvider(ComponentName componentName, RemoteViews views) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetProvider() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(componentName.getPackageName());
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            ProviderId providerId = new ProviderId(Binder.getCallingUid(), componentName);
            Provider provider = lookupProviderLocked(providerId);
            if (provider == null) {
                Slog.w(TAG, "Provider doesn't exist " + providerId);
                return;
            }
            ArrayList<Widget> instances = provider.widgets;
            int N = instances.size();
            for (int i = 0; i < N; i++) {
                Widget widget = instances.get(i);
                updateAppWidgetInstanceLocked(widget, views, false);
            }
        }
    }

    public void updateAppWidgetProviderInfo(ComponentName componentName, String metadataKey) {
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "updateAppWidgetProvider() " + userId);
        }
        this.mSecurityPolicy.enforceCallFromPackage(componentName.getPackageName());
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            ProviderId providerId = new ProviderId(Binder.getCallingUid(), componentName);
            Provider provider = lookupProviderLocked(providerId);
            if (provider == null) {
                throw new IllegalArgumentException(componentName + " is not a valid AppWidget provider");
            } else if (!Objects.equals(provider.infoTag, metadataKey)) {
                String keyToUse = metadataKey == null ? "android.appwidget.provider" : metadataKey;
                AppWidgetProviderInfo info = parseAppWidgetProviderInfo(providerId, provider.info.providerInfo, keyToUse);
                if (info == null) {
                    throw new IllegalArgumentException("Unable to parse " + keyToUse + " meta-data to a valid AppWidget provider");
                }
                provider.info = info;
                provider.infoTag = metadataKey;
                int N = provider.widgets.size();
                for (int i = 0; i < N; i++) {
                    Widget widget = provider.widgets.get(i);
                    scheduleNotifyProviderChangedLocked(widget);
                    updateAppWidgetInstanceLocked(widget, widget.views, false);
                }
                saveGroupStateAsync(userId);
                scheduleNotifyGroupHostsForProvidersChangedLocked(userId);
            }
        }
    }

    public boolean isRequestPinAppWidgetSupported() {
        synchronized (this.mLock) {
            if (this.mSecurityPolicy.isCallerInstantAppLocked()) {
                Slog.w(TAG, "Instant uid " + Binder.getCallingUid() + " query information about app widgets");
                return false;
            }
            return ((ShortcutServiceInternal) LocalServices.getService(ShortcutServiceInternal.class)).isRequestPinItemSupported(UserHandle.getCallingUserId(), 2);
        }
    }

    public boolean requestPinAppWidget(String callingPackage, ComponentName componentName, Bundle extras, IntentSender resultSender) {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(callingUid);
        if (DEBUG) {
            Slog.i(TAG, "requestPinAppWidget() " + userId);
        }
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            Provider provider = lookupProviderLocked(new ProviderId(callingUid, componentName));
            if (provider != null && !provider.zombie) {
                AppWidgetProviderInfo info = provider.info;
                if ((info.widgetCategory & 1) == 0) {
                    return false;
                }
                return ((ShortcutServiceInternal) LocalServices.getService(ShortcutServiceInternal.class)).requestPinAppWidget(callingPackage, info, extras, resultSender, userId);
            }
            return false;
        }
    }

    public ParceledListSlice<AppWidgetProviderInfo> getInstalledProvidersForProfile(int categoryFilter, int profileId, String packageName) {
        boolean inPackage;
        int providerProfileId;
        int userId = UserHandle.getCallingUserId();
        if (DEBUG) {
            Slog.i(TAG, "getInstalledProvidersForProfiles() " + userId);
        }
        if (!this.mSecurityPolicy.isEnabledGroupProfile(profileId)) {
            return null;
        }
        synchronized (this.mLock) {
            if (this.mSecurityPolicy.isCallerInstantAppLocked()) {
                Slog.w(TAG, "Instant uid " + Binder.getCallingUid() + " cannot access widget providers");
                return ParceledListSlice.emptyList();
            }
            ensureGroupStateLoadedLocked(userId);
            ArrayList<AppWidgetProviderInfo> result = new ArrayList<>();
            int providerCount = this.mProviders.size();
            for (int i = 0; i < providerCount; i++) {
                Provider provider = this.mProviders.get(i);
                AppWidgetProviderInfo info = provider.info;
                if (packageName != null && !provider.id.componentName.getPackageName().equals(packageName)) {
                    inPackage = false;
                    if (!provider.zombie && (info.widgetCategory & categoryFilter) != 0 && inPackage && (providerProfileId = info.getProfile().getIdentifier()) == profileId && this.mSecurityPolicy.isProviderInCallerOrInProfileAndWhitelListed(provider.id.componentName.getPackageName(), providerProfileId)) {
                        result.add(cloneIfLocalBinder(info));
                    }
                }
                inPackage = true;
                if (!provider.zombie) {
                    result.add(cloneIfLocalBinder(info));
                }
            }
            return new ParceledListSlice<>(result);
        }
    }

    private void updateAppWidgetIds(String callingPackage, int[] appWidgetIds, RemoteViews views, boolean partially) {
        int userId = UserHandle.getCallingUserId();
        if (appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        this.mSecurityPolicy.enforceCallFromPackage(callingPackage);
        synchronized (this.mLock) {
            ensureGroupStateLoadedLocked(userId);
            for (int appWidgetId : appWidgetIds) {
                Widget widget = lookupWidgetLocked(appWidgetId, Binder.getCallingUid(), callingPackage);
                if (widget != null) {
                    updateAppWidgetInstanceLocked(widget, views, partially);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int incrementAndGetAppWidgetIdLocked(int userId) {
        int appWidgetId = peekNextAppWidgetIdLocked(userId) + 1;
        this.mNextAppWidgetIds.put(userId, appWidgetId);
        return appWidgetId;
    }

    private void setMinAppWidgetIdLocked(int userId, int minWidgetId) {
        int nextAppWidgetId = peekNextAppWidgetIdLocked(userId);
        if (nextAppWidgetId < minWidgetId) {
            this.mNextAppWidgetIds.put(userId, minWidgetId);
        }
    }

    private int peekNextAppWidgetIdLocked(int userId) {
        if (this.mNextAppWidgetIds.indexOfKey(userId) < 0) {
            return 1;
        }
        return this.mNextAppWidgetIds.get(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Host lookupOrAddHostLocked(HostId id) {
        Host host = lookupHostLocked(id);
        if (host != null) {
            return host;
        }
        Host host2 = new Host();
        host2.id = id;
        this.mHosts.add(host2);
        return host2;
    }

    private void deleteHostLocked(Host host) {
        int N = host.widgets.size();
        for (int i = N - 1; i >= 0; i--) {
            Widget widget = host.widgets.remove(i);
            deleteAppWidgetLocked(widget);
        }
        this.mHosts.remove(host);
        host.callbacks = null;
    }

    private void deleteAppWidgetLocked(Widget widget) {
        decrementAppWidgetServiceRefCount(widget);
        Host host = widget.host;
        host.widgets.remove(widget);
        pruneHostLocked(host);
        removeWidgetLocked(widget);
        Provider provider = widget.provider;
        if (provider != null) {
            provider.widgets.remove(widget);
            if (!provider.zombie) {
                sendDeletedIntentLocked(widget);
                if (provider.widgets.isEmpty()) {
                    cancelBroadcastsLocked(provider);
                    sendDisabledIntentLocked(provider);
                }
            }
        }
    }

    private void cancelBroadcastsLocked(Provider provider) {
        if (DEBUG) {
            Slog.i(TAG, "cancelBroadcastsLocked() for " + provider);
        }
        if (provider.broadcast != null) {
            final PendingIntent broadcast = provider.broadcast;
            this.mSaveStateHandler.post(new Runnable() { // from class: com.android.server.appwidget.-$$Lambda$AppWidgetServiceImpl$TEG8Dmf_tnBoLQ8rTg9_1sFaVu8
                @Override // java.lang.Runnable
                public final void run() {
                    AppWidgetServiceImpl.this.lambda$cancelBroadcastsLocked$0$AppWidgetServiceImpl(broadcast);
                }
            });
            provider.broadcast = null;
        }
    }

    public /* synthetic */ void lambda$cancelBroadcastsLocked$0$AppWidgetServiceImpl(PendingIntent broadcast) {
        this.mAlarmManager.cancel(broadcast);
        broadcast.cancel();
    }

    private void destroyRemoteViewsService(final Intent intent, Widget widget) {
        ServiceConnection conn = new ServiceConnection() { // from class: com.android.server.appwidget.AppWidgetServiceImpl.2
            @Override // android.content.ServiceConnection
            public void onServiceConnected(ComponentName name, IBinder service) {
                IRemoteViewsFactory cb = IRemoteViewsFactory.Stub.asInterface(service);
                try {
                    cb.onDestroy(intent);
                } catch (RemoteException re) {
                    Slog.e(AppWidgetServiceImpl.TAG, "Error calling remove view factory", re);
                }
                AppWidgetServiceImpl.this.mContext.unbindService(this);
            }

            @Override // android.content.ServiceConnection
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        long token = Binder.clearCallingIdentity();
        try {
            this.mContext.bindServiceAsUser(intent, conn, 33554433, widget.provider.info.getProfile());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void incrementAppWidgetServiceRefCount(int appWidgetId, Pair<Integer, Intent.FilterComparison> serviceId) {
        HashSet<Integer> appWidgetIds;
        if (this.mRemoteViewsServicesAppWidgets.containsKey(serviceId)) {
            appWidgetIds = this.mRemoteViewsServicesAppWidgets.get(serviceId);
        } else {
            appWidgetIds = new HashSet<>();
            this.mRemoteViewsServicesAppWidgets.put(serviceId, appWidgetIds);
        }
        appWidgetIds.add(Integer.valueOf(appWidgetId));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void decrementAppWidgetServiceRefCount(Widget widget) {
        Iterator<Pair<Integer, Intent.FilterComparison>> it = this.mRemoteViewsServicesAppWidgets.keySet().iterator();
        while (it.hasNext()) {
            Pair<Integer, Intent.FilterComparison> key = it.next();
            HashSet<Integer> ids = this.mRemoteViewsServicesAppWidgets.get(key);
            if (ids.remove(Integer.valueOf(widget.appWidgetId)) && ids.isEmpty()) {
                destroyRemoteViewsService(((Intent.FilterComparison) key.second).getIntent(), widget);
                it.remove();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveGroupStateAsync(int groupId) {
        this.mSaveStateHandler.post(new SaveStateRunnable(groupId));
    }

    private void updateAppWidgetInstanceLocked(Widget widget, RemoteViews views, boolean isPartialUpdate) {
        int memoryUsage;
        if (widget != null && widget.provider != null && !widget.provider.zombie && !widget.host.zombie) {
            if (isPartialUpdate && widget.views != null) {
                widget.views.mergeRemoteViews(views);
            } else {
                widget.views = views;
            }
            if (UserHandle.getAppId(Binder.getCallingUid()) != 1000 && widget.views != null && (memoryUsage = widget.views.estimateMemoryUsage()) > this.mMaxWidgetBitmapMemory) {
                widget.views = null;
                throw new IllegalArgumentException("RemoteViews for widget update exceeds maximum bitmap memory usage (used: " + memoryUsage + ", max: " + this.mMaxWidgetBitmapMemory + ")");
            }
            scheduleNotifyUpdateAppWidgetLocked(widget, widget.getEffectiveViewsLocked());
        }
    }

    private void scheduleNotifyAppWidgetViewDataChanged(Widget widget, int viewId) {
        if (viewId == 0 || viewId == 1) {
            return;
        }
        long requestId = UPDATE_COUNTER.incrementAndGet();
        if (widget != null) {
            widget.updateSequenceNos.put(viewId, requestId);
        }
        if (widget == null || widget.host == null || widget.host.zombie || widget.host.callbacks == null || widget.provider == null || widget.provider.zombie) {
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = widget.host;
        args.arg2 = widget.host.callbacks;
        args.arg3 = Long.valueOf(requestId);
        args.argi1 = widget.appWidgetId;
        args.argi2 = viewId;
        this.mCallbackHandler.obtainMessage(4, args).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNotifyAppWidgetViewDataChanged(Host host, IAppWidgetHost callbacks, int appWidgetId, int viewId, long requestId) {
        try {
            callbacks.viewDataChanged(appWidgetId, viewId);
            host.lastWidgetUpdateSequenceNo = requestId;
        } catch (RemoteException e) {
            callbacks = null;
        }
        synchronized (this.mLock) {
            if (callbacks == null) {
                host.callbacks = null;
                Set<Pair<Integer, Intent.FilterComparison>> keys = this.mRemoteViewsServicesAppWidgets.keySet();
                for (Pair<Integer, Intent.FilterComparison> key : keys) {
                    if (this.mRemoteViewsServicesAppWidgets.get(key).contains(Integer.valueOf(appWidgetId))) {
                        ServiceConnection connection = new ServiceConnection() { // from class: com.android.server.appwidget.AppWidgetServiceImpl.3
                            @Override // android.content.ServiceConnection
                            public void onServiceConnected(ComponentName name, IBinder service) {
                                IRemoteViewsFactory cb = IRemoteViewsFactory.Stub.asInterface(service);
                                try {
                                    cb.onDataSetChangedAsync();
                                } catch (RemoteException e2) {
                                    Slog.e(AppWidgetServiceImpl.TAG, "Error calling onDataSetChangedAsync()", e2);
                                }
                                AppWidgetServiceImpl.this.mContext.unbindService(this);
                            }

                            @Override // android.content.ServiceConnection
                            public void onServiceDisconnected(ComponentName name) {
                            }
                        };
                        int userId = UserHandle.getUserId(((Integer) key.first).intValue());
                        Intent intent = ((Intent.FilterComparison) key.second).getIntent();
                        bindService(intent, connection, new UserHandle(userId));
                    }
                }
            }
        }
    }

    private void scheduleNotifyUpdateAppWidgetLocked(Widget widget, RemoteViews updateViews) {
        long requestId = UPDATE_COUNTER.incrementAndGet();
        if (widget != null) {
            widget.updateSequenceNos.put(0, requestId);
        }
        if (widget == null || widget.provider == null || widget.provider.zombie || widget.host.callbacks == null || widget.host.zombie) {
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = widget.host;
        args.arg2 = widget.host.callbacks;
        args.arg3 = updateViews != null ? updateViews.clone() : null;
        args.arg4 = Long.valueOf(requestId);
        args.argi1 = widget.appWidgetId;
        this.mCallbackHandler.obtainMessage(1, args).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNotifyUpdateAppWidget(Host host, IAppWidgetHost callbacks, int appWidgetId, RemoteViews views, long requestId) {
        try {
            callbacks.updateAppWidget(appWidgetId, views);
            host.lastWidgetUpdateSequenceNo = requestId;
        } catch (RemoteException re) {
            synchronized (this.mLock) {
                Slog.e(TAG, "Widget host dead: " + host.id, re);
                host.callbacks = null;
            }
        }
    }

    private void scheduleNotifyProviderChangedLocked(Widget widget) {
        long requestId = UPDATE_COUNTER.incrementAndGet();
        if (widget != null) {
            widget.updateSequenceNos.clear();
            widget.updateSequenceNos.append(1, requestId);
        }
        if (widget == null || widget.provider == null || widget.provider.zombie || widget.host.callbacks == null || widget.host.zombie) {
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = widget.host;
        args.arg2 = widget.host.callbacks;
        args.arg3 = widget.provider.info;
        args.arg4 = Long.valueOf(requestId);
        args.argi1 = widget.appWidgetId;
        this.mCallbackHandler.obtainMessage(2, args).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNotifyProviderChanged(Host host, IAppWidgetHost callbacks, int appWidgetId, AppWidgetProviderInfo info, long requestId) {
        try {
            callbacks.providerChanged(appWidgetId, info);
            host.lastWidgetUpdateSequenceNo = requestId;
        } catch (RemoteException re) {
            synchronized (this.mLock) {
                Slog.e(TAG, "Widget host dead: " + host.id, re);
                host.callbacks = null;
            }
        }
    }

    private void scheduleNotifyGroupHostsForProvidersChangedLocked(int userId) {
        int[] profileIds = this.mSecurityPolicy.getEnabledGroupProfileIds(userId);
        int N = this.mHosts.size();
        for (int i = N - 1; i >= 0; i--) {
            Host host = this.mHosts.get(i);
            boolean hostInGroup = false;
            int M = profileIds.length;
            int j = 0;
            while (true) {
                if (j >= M) {
                    break;
                }
                int profileId = profileIds[j];
                if (host.getUserId() != profileId) {
                    j++;
                } else {
                    hostInGroup = true;
                    break;
                }
            }
            if (hostInGroup && host != null && !host.zombie && host.callbacks != null) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = host;
                args.arg2 = host.callbacks;
                this.mCallbackHandler.obtainMessage(3, args).sendToTarget();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNotifyProvidersChanged(Host host, IAppWidgetHost callbacks) {
        try {
            callbacks.providersChanged();
        } catch (RemoteException re) {
            synchronized (this.mLock) {
                Slog.e(TAG, "Widget host dead: " + host.id, re);
                host.callbacks = null;
            }
        }
    }

    private static boolean isLocalBinder() {
        return Process.myPid() == Binder.getCallingPid();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static RemoteViews cloneIfLocalBinder(RemoteViews rv) {
        if (isLocalBinder() && rv != null) {
            return rv.clone();
        }
        return rv;
    }

    private static AppWidgetProviderInfo cloneIfLocalBinder(AppWidgetProviderInfo info) {
        if (isLocalBinder() && info != null) {
            return info.clone();
        }
        return info;
    }

    private static Bundle cloneIfLocalBinder(Bundle bundle) {
        if (isLocalBinder() && bundle != null) {
            return (Bundle) bundle.clone();
        }
        return bundle;
    }

    private Widget lookupWidgetLocked(int appWidgetId, int uid, String packageName) {
        int N = this.mWidgets.size();
        for (int i = 0; i < N; i++) {
            Widget widget = this.mWidgets.get(i);
            if (widget.appWidgetId == appWidgetId && this.mSecurityPolicy.canAccessAppWidget(widget, uid, packageName)) {
                return widget;
            }
        }
        return null;
    }

    private Provider lookupProviderLocked(ProviderId id) {
        int N = this.mProviders.size();
        for (int i = 0; i < N; i++) {
            Provider provider = this.mProviders.get(i);
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        return null;
    }

    private Host lookupHostLocked(HostId hostId) {
        int N = this.mHosts.size();
        for (int i = 0; i < N; i++) {
            Host host = this.mHosts.get(i);
            if (host.id.equals(hostId)) {
                return host;
            }
        }
        return null;
    }

    private void pruneHostLocked(Host host) {
        if (host.widgets.size() == 0 && host.callbacks == null) {
            if (DEBUG) {
                Slog.i(TAG, "Pruning host " + host.id);
            }
            this.mHosts.remove(host);
        }
    }

    private void loadGroupWidgetProvidersLocked(int[] profileIds) {
        List<ResolveInfo> allReceivers = null;
        Intent intent = new Intent("android.appwidget.action.APPWIDGET_UPDATE");
        for (int profileId : profileIds) {
            List<ResolveInfo> receivers = queryIntentReceivers(intent, profileId);
            if (receivers != null && !receivers.isEmpty()) {
                if (allReceivers == null) {
                    allReceivers = new ArrayList<>();
                }
                allReceivers.addAll(receivers);
            }
        }
        int N = allReceivers == null ? 0 : allReceivers.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo receiver = allReceivers.get(i);
            addProviderLocked(receiver);
        }
    }

    private boolean addProviderLocked(ResolveInfo ri) {
        ComponentName componentName;
        ProviderId providerId;
        Provider provider;
        if ((ri.activityInfo.applicationInfo.flags & 262144) == 0 && ri.activityInfo.isEnabled() && (provider = parseProviderInfoXml((providerId = new ProviderId(ri.activityInfo.applicationInfo.uid, (componentName = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)))), ri, null)) != null) {
            Provider existing = lookupProviderLocked(providerId);
            if (existing == null) {
                ProviderId restoredProviderId = new ProviderId(-1, componentName);
                existing = lookupProviderLocked(restoredProviderId);
            }
            if (existing != null) {
                if (existing.zombie && !this.mSafeMode) {
                    existing.id = providerId;
                    existing.zombie = false;
                    existing.info = provider.info;
                    if (DEBUG) {
                        Slog.i(TAG, "Provider placeholder now reified: " + existing);
                        return true;
                    }
                    return true;
                }
                return true;
            }
            this.mProviders.add(provider);
            return true;
        }
        return false;
    }

    private void deleteWidgetsLocked(Provider provider, int userId) {
        int N = provider.widgets.size();
        for (int i = N - 1; i >= 0; i--) {
            Widget widget = provider.widgets.get(i);
            if (userId == -1 || userId == widget.host.getUserId()) {
                provider.widgets.remove(i);
                updateAppWidgetInstanceLocked(widget, null, false);
                widget.host.widgets.remove(widget);
                removeWidgetLocked(widget);
                widget.provider = null;
                pruneHostLocked(widget.host);
                widget.host = null;
            }
        }
    }

    private void deleteProviderLocked(Provider provider) {
        deleteWidgetsLocked(provider, -1);
        this.mProviders.remove(provider);
        cancelBroadcastsLocked(provider);
    }

    private void sendEnableIntentLocked(Provider p) {
        Intent intent = new Intent("android.appwidget.action.APPWIDGET_ENABLED");
        intent.setComponent(p.info.provider);
        sendBroadcastAsUser(intent, p.info.getProfile());
    }

    private void sendUpdateIntentLocked(Provider provider, int[] appWidgetIds) {
        Intent intent = new Intent("android.appwidget.action.APPWIDGET_UPDATE");
        intent.putExtra("appWidgetIds", appWidgetIds);
        intent.setComponent(provider.info.provider);
        sendBroadcastAsUser(intent, provider.info.getProfile());
    }

    private void sendDeletedIntentLocked(Widget widget) {
        Intent intent = new Intent("android.appwidget.action.APPWIDGET_DELETED");
        intent.setComponent(widget.provider.info.provider);
        intent.putExtra("appWidgetId", widget.appWidgetId);
        sendBroadcastAsUser(intent, widget.provider.info.getProfile());
    }

    private void sendDisabledIntentLocked(Provider provider) {
        Intent intent = new Intent("android.appwidget.action.APPWIDGET_DISABLED");
        intent.setComponent(provider.info.provider);
        sendBroadcastAsUser(intent, provider.info.getProfile());
    }

    public void sendOptionsChangedIntentLocked(Widget widget) {
        Intent intent = new Intent("android.appwidget.action.APPWIDGET_UPDATE_OPTIONS");
        intent.setComponent(widget.provider.info.provider);
        intent.putExtra("appWidgetId", widget.appWidgetId);
        intent.putExtra("appWidgetOptions", widget.options);
        sendBroadcastAsUser(intent, widget.provider.info.getProfile());
    }

    private void registerForBroadcastsLocked(Provider provider, int[] appWidgetIds) {
        if (provider.info.updatePeriodMillis > 0) {
            boolean alreadyRegistered = provider.broadcast != null;
            Intent intent = new Intent("android.appwidget.action.APPWIDGET_UPDATE");
            intent.putExtra("appWidgetIds", appWidgetIds);
            intent.setComponent(provider.info.provider);
            long token = Binder.clearCallingIdentity();
            try {
                provider.broadcast = PendingIntent.getBroadcastAsUser(this.mContext, 1, intent, 134217728, provider.info.getProfile());
                if (!alreadyRegistered) {
                    final long period = Math.max(provider.info.updatePeriodMillis, MIN_UPDATE_PERIOD);
                    final PendingIntent broadcast = provider.broadcast;
                    this.mSaveStateHandler.post(new Runnable() { // from class: com.android.server.appwidget.-$$Lambda$AppWidgetServiceImpl$7z3EwuT55saMxTVomcNfb1VOVL0
                        @Override // java.lang.Runnable
                        public final void run() {
                            AppWidgetServiceImpl.this.lambda$registerForBroadcastsLocked$1$AppWidgetServiceImpl(period, broadcast);
                        }
                    });
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    public /* synthetic */ void lambda$registerForBroadcastsLocked$1$AppWidgetServiceImpl(long period, PendingIntent broadcast) {
        this.mAlarmManager.setInexactRepeating(2, SystemClock.elapsedRealtime() + period, period, broadcast);
    }

    private static int[] getWidgetIds(ArrayList<Widget> widgets) {
        int instancesSize = widgets.size();
        int[] appWidgetIds = new int[instancesSize];
        for (int i = 0; i < instancesSize; i++) {
            appWidgetIds[i] = widgets.get(i).appWidgetId;
        }
        return appWidgetIds;
    }

    private static void dumpProvider(Provider provider, int index, PrintWriter pw) {
        AppWidgetProviderInfo info = provider.info;
        pw.print("  [");
        pw.print(index);
        pw.print("] provider ");
        pw.println(provider.id);
        pw.print("    min=(");
        pw.print(info.minWidth);
        pw.print("x");
        pw.print(info.minHeight);
        pw.print(")   minResize=(");
        pw.print(info.minResizeWidth);
        pw.print("x");
        pw.print(info.minResizeHeight);
        pw.print(") updatePeriodMillis=");
        pw.print(info.updatePeriodMillis);
        pw.print(" resizeMode=");
        pw.print(info.resizeMode);
        pw.print(" widgetCategory=");
        pw.print(info.widgetCategory);
        pw.print(" autoAdvanceViewId=");
        pw.print(info.autoAdvanceViewId);
        pw.print(" initialLayout=#");
        pw.print(Integer.toHexString(info.initialLayout));
        pw.print(" initialKeyguardLayout=#");
        pw.print(Integer.toHexString(info.initialKeyguardLayout));
        pw.print(" zombie=");
        pw.println(provider.zombie);
    }

    private static void dumpHost(Host host, int index, PrintWriter pw) {
        pw.print("  [");
        pw.print(index);
        pw.print("] hostId=");
        pw.println(host.id);
        pw.print("    callbacks=");
        pw.println(host.callbacks);
        pw.print("    widgets.size=");
        pw.print(host.widgets.size());
        pw.print(" zombie=");
        pw.println(host.zombie);
    }

    private static void dumpGrant(Pair<Integer, String> grant, int index, PrintWriter pw) {
        pw.print("  [");
        pw.print(index);
        pw.print(']');
        pw.print(" user=");
        pw.print(grant.first);
        pw.print(" package=");
        pw.println((String) grant.second);
    }

    private static void dumpWidget(Widget widget, int index, PrintWriter pw) {
        pw.print("  [");
        pw.print(index);
        pw.print("] id=");
        pw.println(widget.appWidgetId);
        pw.print("    host=");
        pw.println(widget.host.id);
        if (widget.provider != null) {
            pw.print("    provider=");
            pw.println(widget.provider.id);
        }
        if (widget.host != null) {
            pw.print("    host.callbacks=");
            pw.println(widget.host.callbacks);
        }
        if (widget.views != null) {
            pw.print("    views=");
            pw.println(widget.views);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void serializeProvider(XmlSerializer out, Provider p) throws IOException {
        out.startTag(null, "p");
        out.attribute(null, "pkg", p.info.provider.getPackageName());
        out.attribute(null, "cl", p.info.provider.getClassName());
        out.attribute(null, "tag", Integer.toHexString(p.tag));
        if (!TextUtils.isEmpty(p.infoTag)) {
            out.attribute(null, "info_tag", p.infoTag);
        }
        out.endTag(null, "p");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void serializeHost(XmlSerializer out, Host host) throws IOException {
        out.startTag(null, "h");
        out.attribute(null, "pkg", host.id.packageName);
        out.attribute(null, "id", Integer.toHexString(host.id.hostId));
        out.attribute(null, "tag", Integer.toHexString(host.tag));
        out.endTag(null, "h");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void serializeAppWidget(XmlSerializer out, Widget widget) throws IOException {
        out.startTag(null, "g");
        out.attribute(null, "id", Integer.toHexString(widget.appWidgetId));
        out.attribute(null, "rid", Integer.toHexString(widget.restoredId));
        out.attribute(null, "h", Integer.toHexString(widget.host.tag));
        if (widget.provider != null) {
            out.attribute(null, "p", Integer.toHexString(widget.provider.tag));
        }
        if (widget.options != null) {
            int minWidth = widget.options.getInt("appWidgetMinWidth");
            int minHeight = widget.options.getInt("appWidgetMinHeight");
            int maxWidth = widget.options.getInt("appWidgetMaxWidth");
            int maxHeight = widget.options.getInt("appWidgetMaxHeight");
            out.attribute(null, "min_width", Integer.toHexString(minWidth > 0 ? minWidth : 0));
            out.attribute(null, "min_height", Integer.toHexString(minHeight > 0 ? minHeight : 0));
            out.attribute(null, "max_width", Integer.toHexString(maxWidth > 0 ? maxWidth : 0));
            out.attribute(null, "max_height", Integer.toHexString(maxHeight > 0 ? maxHeight : 0));
            out.attribute(null, "host_category", Integer.toHexString(widget.options.getInt("appWidgetCategory")));
        }
        out.endTag(null, "g");
    }

    public List<String> getWidgetParticipants(int userId) {
        return this.mBackupRestoreController.getWidgetParticipants(userId);
    }

    public byte[] getWidgetState(String packageName, int userId) {
        return this.mBackupRestoreController.getWidgetState(packageName, userId);
    }

    public void restoreStarting(int userId) {
        this.mBackupRestoreController.restoreStarting(userId);
    }

    public void restoreWidgetState(String packageName, byte[] restoredState, int userId) {
        this.mBackupRestoreController.restoreWidgetState(packageName, restoredState, userId);
    }

    public void restoreFinished(int userId) {
        this.mBackupRestoreController.restoreFinished(userId);
    }

    private Provider parseProviderInfoXml(ProviderId providerId, ResolveInfo ri, Provider oldProvider) {
        AppWidgetProviderInfo info = null;
        if (oldProvider != null && !TextUtils.isEmpty(oldProvider.infoTag)) {
            info = parseAppWidgetProviderInfo(providerId, ri.activityInfo, oldProvider.infoTag);
        }
        if (info == null) {
            info = parseAppWidgetProviderInfo(providerId, ri.activityInfo, "android.appwidget.provider");
        }
        if (info == null) {
            return null;
        }
        Provider provider = new Provider();
        provider.id = providerId;
        provider.info = info;
        return provider;
    }

    private AppWidgetProviderInfo parseAppWidgetProviderInfo(ProviderId providerId, ActivityInfo activityInfo, String metadataKey) {
        try {
            XmlResourceParser parser = activityInfo.loadXmlMetaData(this.mContext.getPackageManager(), metadataKey);
            if (parser == null) {
                Slog.w(TAG, "No " + metadataKey + " meta-data for AppWidget provider '" + providerId + '\'');
                if (parser != null) {
                    $closeResource(null, parser);
                }
                return null;
            }
            AttributeSet attrs = Xml.asAttributeSet(parser);
            while (true) {
                int type = parser.next();
                if (type == 1 || type == 2) {
                    break;
                }
            }
            String nodeName = parser.getName();
            if (!"appwidget-provider".equals(nodeName)) {
                Slog.w(TAG, "Meta-data does not start with appwidget-provider tag for AppWidget provider " + providerId.componentName + " for user " + providerId.uid);
                $closeResource(null, parser);
                return null;
            }
            AppWidgetProviderInfo info = new AppWidgetProviderInfo();
            info.provider = providerId.componentName;
            info.providerInfo = activityInfo;
            long identity = Binder.clearCallingIdentity();
            try {
                PackageManager pm = this.mContext.getPackageManager();
                int userId = UserHandle.getUserId(providerId.uid);
                ApplicationInfo app = pm.getApplicationInfoAsUser(activityInfo.packageName, 0, userId);
                Resources resources = pm.getResourcesForApplication(app);
                Binder.restoreCallingIdentity(identity);
                TypedArray sa = resources.obtainAttributes(attrs, R.styleable.AppWidgetProviderInfo);
                TypedValue value = sa.peekValue(0);
                info.minWidth = value != null ? value.data : 0;
                TypedValue value2 = sa.peekValue(1);
                info.minHeight = value2 != null ? value2.data : 0;
                TypedValue value3 = sa.peekValue(8);
                info.minResizeWidth = value3 != null ? value3.data : info.minWidth;
                TypedValue value4 = sa.peekValue(9);
                info.minResizeHeight = value4 != null ? value4.data : info.minHeight;
                info.updatePeriodMillis = sa.getInt(2, 0);
                info.initialLayout = sa.getResourceId(3, 0);
                info.initialKeyguardLayout = sa.getResourceId(10, 0);
                String className = sa.getString(4);
                if (className != null) {
                    info.configure = new ComponentName(providerId.componentName.getPackageName(), className);
                }
                info.label = activityInfo.loadLabel(this.mContext.getPackageManager()).toString();
                info.icon = activityInfo.getIconResource();
                info.previewImage = sa.getResourceId(5, 0);
                info.autoAdvanceViewId = sa.getResourceId(6, -1);
                info.resizeMode = sa.getInt(7, 0);
                info.widgetCategory = sa.getInt(11, 1);
                info.widgetFeatures = sa.getInt(12, 0);
                sa.recycle();
                $closeResource(null, parser);
                return info;
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        } catch (PackageManager.NameNotFoundException | IOException | XmlPullParserException e) {
            Slog.w(TAG, "XML parsing failed for AppWidget provider " + providerId.componentName + " for user " + providerId.uid, e);
            return null;
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getUidForPackage(String packageName, int userId) {
        PackageInfo pkgInfo = null;
        long identity = Binder.clearCallingIdentity();
        try {
            pkgInfo = this.mPackageManager.getPackageInfo(packageName, 0, userId);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
        Binder.restoreCallingIdentity(identity);
        if (pkgInfo == null || pkgInfo.applicationInfo == null) {
            return -1;
        }
        return pkgInfo.applicationInfo.uid;
    }

    private ActivityInfo getProviderInfo(ComponentName componentName, int userId) {
        Intent intent = new Intent("android.appwidget.action.APPWIDGET_UPDATE");
        intent.setComponent(componentName);
        List<ResolveInfo> receivers = queryIntentReceivers(intent, userId);
        if (!receivers.isEmpty()) {
            return receivers.get(0).activityInfo;
        }
        return null;
    }

    private List<ResolveInfo> queryIntentReceivers(Intent intent, int userId) {
        long identity = Binder.clearCallingIdentity();
        int flags = 128 | 268435456;
        try {
            if (isProfileWithUnlockedParent(userId)) {
                flags |= 786432;
            }
            return this.mPackageManager.queryIntentReceivers(intent, intent.resolveTypeIfNeeded(this.mContext.getContentResolver()), flags | 1024, userId).getList();
        } catch (RemoteException e) {
            return Collections.emptyList();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void handleUserUnlocked(int userId) {
        if (isProfileWithLockedParent(userId)) {
            return;
        }
        if (!this.mUserManager.isUserUnlockingOrUnlocked(userId)) {
            Slog.w(TAG, "User " + userId + " is no longer unlocked - exiting");
            return;
        }
        long time = SystemClock.elapsedRealtime();
        synchronized (this.mLock) {
            Trace.traceBegin(64L, "appwidget ensure");
            ensureGroupStateLoadedLocked(userId);
            Trace.traceEnd(64L);
            Trace.traceBegin(64L, "appwidget reload");
            reloadWidgetsMaskedStateForGroup(this.mSecurityPolicy.getGroupParent(userId));
            Trace.traceEnd(64L);
            int N = this.mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = this.mProviders.get(i);
                if (provider.getUserId() == userId && provider.widgets.size() > 0) {
                    Trace.traceBegin(64L, "appwidget init " + provider.info.provider.getPackageName());
                    sendEnableIntentLocked(provider);
                    int[] appWidgetIds = getWidgetIds(provider.widgets);
                    sendUpdateIntentLocked(provider, appWidgetIds);
                    registerForBroadcastsLocked(provider, appWidgetIds);
                    Trace.traceEnd(64L);
                }
            }
        }
        Slog.i(TAG, "Processing of handleUserUnlocked u" + userId + " took " + (SystemClock.elapsedRealtime() - time) + " ms");
    }

    private void loadGroupStateLocked(int[] profileIds) {
        List<LoadedWidgetState> loadedWidgets = new ArrayList<>();
        int version = 0;
        for (int profileId : profileIds) {
            AtomicFile file = getSavedStateFile(profileId);
            try {
                FileInputStream stream = file.openRead();
                version = readProfileStateFromFileLocked(stream, profileId, loadedWidgets);
                if (stream != null) {
                    $closeResource(null, stream);
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed to read state: " + e);
            }
        }
        if (version >= 0) {
            bindLoadedWidgetsLocked(loadedWidgets);
            performUpgradeLocked(version);
            return;
        }
        Slog.w(TAG, "Failed to read state, clearing widgets and hosts.");
        clearWidgetsLocked();
        this.mHosts.clear();
        int N = this.mProviders.size();
        for (int i = 0; i < N; i++) {
            this.mProviders.get(i).widgets.clear();
        }
    }

    private void bindLoadedWidgetsLocked(List<LoadedWidgetState> loadedWidgets) {
        int loadedWidgetCount = loadedWidgets.size();
        for (int i = loadedWidgetCount - 1; i >= 0; i--) {
            LoadedWidgetState loadedWidget = loadedWidgets.remove(i);
            Widget widget = loadedWidget.widget;
            widget.provider = findProviderByTag(loadedWidget.providerTag);
            if (widget.provider != null) {
                widget.host = findHostByTag(loadedWidget.hostTag);
                if (widget.host != null) {
                    widget.provider.widgets.add(widget);
                    widget.host.widgets.add(widget);
                    addWidgetLocked(widget);
                }
            }
        }
    }

    private Provider findProviderByTag(int tag) {
        if (tag < 0) {
            return null;
        }
        int providerCount = this.mProviders.size();
        for (int i = 0; i < providerCount; i++) {
            Provider provider = this.mProviders.get(i);
            if (provider.tag == tag) {
                return provider;
            }
        }
        return null;
    }

    private Host findHostByTag(int tag) {
        if (tag < 0) {
            return null;
        }
        int hostCount = this.mHosts.size();
        for (int i = 0; i < hostCount; i++) {
            Host host = this.mHosts.get(i);
            if (host.tag == tag) {
                return host;
            }
        }
        return null;
    }

    void addWidgetLocked(Widget widget) {
        this.mWidgets.add(widget);
        onWidgetProviderAddedOrChangedLocked(widget);
    }

    void onWidgetProviderAddedOrChangedLocked(Widget widget) {
        if (widget.provider == null) {
            return;
        }
        int userId = widget.provider.getUserId();
        ArraySet<String> packages = this.mWidgetPackages.get(userId);
        if (packages == null) {
            SparseArray<ArraySet<String>> sparseArray = this.mWidgetPackages;
            ArraySet<String> arraySet = new ArraySet<>();
            packages = arraySet;
            sparseArray.put(userId, arraySet);
        }
        packages.add(widget.provider.info.provider.getPackageName());
        if (widget.provider.isMaskedLocked()) {
            maskWidgetsViewsLocked(widget.provider, widget);
        } else {
            widget.clearMaskedViewsLocked();
        }
    }

    void removeWidgetLocked(Widget widget) {
        this.mWidgets.remove(widget);
        onWidgetRemovedLocked(widget);
    }

    private void onWidgetRemovedLocked(Widget widget) {
        if (widget.provider == null) {
            return;
        }
        int userId = widget.provider.getUserId();
        String packageName = widget.provider.info.provider.getPackageName();
        ArraySet<String> packages = this.mWidgetPackages.get(userId);
        if (packages == null) {
            return;
        }
        int N = this.mWidgets.size();
        for (int i = 0; i < N; i++) {
            Widget w = this.mWidgets.get(i);
            if (w.provider != null && w.provider.getUserId() == userId && packageName.equals(w.provider.info.provider.getPackageName())) {
                return;
            }
        }
        packages.remove(packageName);
    }

    void clearWidgetsLocked() {
        this.mWidgets.clear();
        onWidgetsClearedLocked();
    }

    private void onWidgetsClearedLocked() {
        this.mWidgetPackages.clear();
    }

    public boolean isBoundWidgetPackage(String packageName, int userId) {
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system process can call this");
        }
        synchronized (this.mLock) {
            ArraySet<String> packages = this.mWidgetPackages.get(userId);
            if (packages != null) {
                return packages.contains(packageName);
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveStateLocked(int userId) {
        tagProvidersAndHosts();
        int[] profileIds = this.mSecurityPolicy.getEnabledGroupProfileIds(userId);
        for (int profileId : profileIds) {
            AtomicFile file = getSavedStateFile(profileId);
            try {
                FileOutputStream stream = file.startWrite();
                if (writeProfileStateToFileLocked(stream, profileId)) {
                    file.finishWrite(stream);
                } else {
                    file.failWrite(stream);
                    Slog.w(TAG, "Failed to save state, restoring backup.");
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed open state file for write: " + e);
            }
        }
    }

    private void tagProvidersAndHosts() {
        int providerCount = this.mProviders.size();
        for (int i = 0; i < providerCount; i++) {
            Provider provider = this.mProviders.get(i);
            provider.tag = i;
        }
        int hostCount = this.mHosts.size();
        for (int i2 = 0; i2 < hostCount; i2++) {
            Host host = this.mHosts.get(i2);
            host.tag = i2;
        }
    }

    private void clearProvidersAndHostsTagsLocked() {
        int providerCount = this.mProviders.size();
        for (int i = 0; i < providerCount; i++) {
            Provider provider = this.mProviders.get(i);
            provider.tag = -1;
        }
        int hostCount = this.mHosts.size();
        for (int i2 = 0; i2 < hostCount; i2++) {
            Host host = this.mHosts.get(i2);
            host.tag = -1;
        }
    }

    private boolean writeProfileStateToFileLocked(FileOutputStream stream, int userId) {
        try {
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.startTag(null, "gs");
            fastXmlSerializer.attribute(null, "version", String.valueOf(1));
            int N = this.mProviders.size();
            for (int i = 0; i < N; i++) {
                Provider provider = this.mProviders.get(i);
                if (provider.getUserId() == userId && provider.shouldBePersisted()) {
                    serializeProvider(fastXmlSerializer, provider);
                }
            }
            int N2 = this.mHosts.size();
            for (int i2 = 0; i2 < N2; i2++) {
                Host host = this.mHosts.get(i2);
                if (host.getUserId() == userId) {
                    serializeHost(fastXmlSerializer, host);
                }
            }
            int N3 = this.mWidgets.size();
            for (int i3 = 0; i3 < N3; i3++) {
                Widget widget = this.mWidgets.get(i3);
                if (widget.host.getUserId() == userId) {
                    serializeAppWidget(fastXmlSerializer, widget);
                }
            }
            Iterator<Pair<Integer, String>> it = this.mPackagesWithBindWidgetPermission.iterator();
            while (it.hasNext()) {
                Pair<Integer, String> binding = it.next();
                if (((Integer) binding.first).intValue() == userId) {
                    fastXmlSerializer.startTag(null, "b");
                    fastXmlSerializer.attribute(null, "packageName", (String) binding.second);
                    fastXmlSerializer.endTag(null, "b");
                }
            }
            fastXmlSerializer.endTag(null, "gs");
            fastXmlSerializer.endDocument();
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write state: " + e);
            return false;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:120:0x02a5 A[LOOP:0: B:133:0x001c->B:120:0x02a5, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:139:0x02a4 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:40:0x00e2 A[Catch: IOException | IndexOutOfBoundsException | NullPointerException | NumberFormatException | XmlPullParserException -> 0x0293, TryCatch #1 {IOException | IndexOutOfBoundsException | NullPointerException | NumberFormatException | XmlPullParserException -> 0x0293, blocks: (B:27:0x007b, B:30:0x008e, B:32:0x009c, B:34:0x00a2, B:38:0x00d6, B:40:0x00e2, B:42:0x00eb, B:44:0x0100, B:46:0x0104, B:48:0x010c, B:53:0x0120, B:57:0x012c, B:59:0x013c, B:60:0x013f, B:62:0x0143, B:64:0x0147, B:66:0x015d, B:68:0x0165, B:70:0x017d, B:72:0x0185, B:74:0x0193, B:76:0x01a6, B:78:0x01ae, B:82:0x01da, B:84:0x01eb, B:85:0x01f6, B:87:0x0200, B:89:0x0210, B:91:0x021a, B:93:0x022a, B:95:0x0234, B:97:0x0244, B:99:0x024d, B:101:0x025d, B:103:0x0270, B:105:0x0280, B:81:0x01d4), top: B:130:0x007b }] */
    /* JADX WARN: Removed duplicated region for block: B:41:0x00e9  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int readProfileStateFromFileLocked(java.io.FileInputStream r25, int r26, java.util.List<com.android.server.appwidget.AppWidgetServiceImpl.LoadedWidgetState> r27) {
        /*
            Method dump skipped, instructions count: 725
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.appwidget.AppWidgetServiceImpl.readProfileStateFromFileLocked(java.io.FileInputStream, int, java.util.List):int");
    }

    private void performUpgradeLocked(int fromVersion) {
        int uid;
        if (fromVersion < 1) {
            Slog.v(TAG, "Upgrading widget database from " + fromVersion + " to 1");
        }
        int version = fromVersion;
        if (version == 0) {
            HostId oldHostId = new HostId(Process.myUid(), KEYGUARD_HOST_ID, "android");
            Host host = lookupHostLocked(oldHostId);
            if (host != null && (uid = getUidForPackage(NEW_KEYGUARD_HOST_PACKAGE, 0)) >= 0) {
                host.id = new HostId(uid, KEYGUARD_HOST_ID, NEW_KEYGUARD_HOST_PACKAGE);
            }
            version = 1;
        }
        if (version != 1) {
            throw new IllegalStateException("Failed to upgrade widget database");
        }
    }

    private static File getStateFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), STATE_FILENAME);
    }

    private static AtomicFile getSavedStateFile(int userId) {
        File dir = Environment.getUserSystemDirectory(userId);
        File settingsFile = getStateFile(userId);
        if (!settingsFile.exists() && userId == 0) {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File oldFile = new File("/data/system/appwidgets.xml");
            oldFile.renameTo(settingsFile);
        }
        return new AtomicFile(settingsFile);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUserStopped(int userId) {
        synchronized (this.mLock) {
            boolean crossProfileWidgetsChanged = false;
            int widgetCount = this.mWidgets.size();
            int i = widgetCount - 1;
            while (true) {
                boolean providerInUser = false;
                if (i < 0) {
                    break;
                }
                Widget widget = this.mWidgets.get(i);
                boolean hostInUser = widget.host.getUserId() == userId;
                boolean hasProvider = widget.provider != null;
                if (hasProvider && widget.provider.getUserId() == userId) {
                    providerInUser = true;
                }
                if (hostInUser && (!hasProvider || providerInUser)) {
                    removeWidgetLocked(widget);
                    widget.host.widgets.remove(widget);
                    widget.host = null;
                    if (hasProvider) {
                        widget.provider.widgets.remove(widget);
                        widget.provider = null;
                    }
                }
                i--;
            }
            int hostCount = this.mHosts.size();
            for (int i2 = hostCount - 1; i2 >= 0; i2--) {
                Host host = this.mHosts.get(i2);
                if (host.getUserId() == userId) {
                    crossProfileWidgetsChanged |= !host.widgets.isEmpty();
                    deleteHostLocked(host);
                }
            }
            int grantCount = this.mPackagesWithBindWidgetPermission.size();
            for (int i3 = grantCount - 1; i3 >= 0; i3--) {
                Pair<Integer, String> packageId = this.mPackagesWithBindWidgetPermission.valueAt(i3);
                if (((Integer) packageId.first).intValue() == userId) {
                    this.mPackagesWithBindWidgetPermission.removeAt(i3);
                }
            }
            int userIndex = this.mLoadedUserIds.indexOfKey(userId);
            if (userIndex >= 0) {
                this.mLoadedUserIds.removeAt(userIndex);
            }
            int nextIdIndex = this.mNextAppWidgetIds.indexOfKey(userId);
            if (nextIdIndex >= 0) {
                this.mNextAppWidgetIds.removeAt(nextIdIndex);
            }
            if (crossProfileWidgetsChanged) {
                saveGroupStateAsync(userId);
            }
        }
    }

    private boolean updateProvidersForPackageLocked(String packageName, int userId, Set<ProviderId> removedProviders) {
        boolean providersUpdated;
        Intent intent;
        List<ResolveInfo> broadcastReceivers;
        int N;
        boolean providersUpdated2 = false;
        HashSet<ProviderId> keep = new HashSet<>();
        Intent intent2 = new Intent("android.appwidget.action.APPWIDGET_UPDATE");
        intent2.setPackage(packageName);
        List<ResolveInfo> broadcastReceivers2 = queryIntentReceivers(intent2, userId);
        int N2 = broadcastReceivers2 == null ? 0 : broadcastReceivers2.size();
        int i = 0;
        while (i < N2) {
            ResolveInfo ri = broadcastReceivers2.get(i);
            ActivityInfo ai = ri.activityInfo;
            if ((ai.applicationInfo.flags & 262144) != 0) {
                providersUpdated = providersUpdated2;
                intent = intent2;
                broadcastReceivers = broadcastReceivers2;
                N = N2;
            } else if (!packageName.equals(ai.packageName)) {
                providersUpdated = providersUpdated2;
                intent = intent2;
                broadcastReceivers = broadcastReceivers2;
                N = N2;
            } else {
                providersUpdated = providersUpdated2;
                ProviderId providerId = new ProviderId(ai.applicationInfo.uid, new ComponentName(ai.packageName, ai.name));
                Provider provider = lookupProviderLocked(providerId);
                if (provider == null) {
                    if (!addProviderLocked(ri)) {
                        intent = intent2;
                        broadcastReceivers = broadcastReceivers2;
                        N = N2;
                    } else {
                        keep.add(providerId);
                        providersUpdated2 = true;
                        intent = intent2;
                        broadcastReceivers = broadcastReceivers2;
                        N = N2;
                    }
                } else {
                    Provider parsed = parseProviderInfoXml(providerId, ri, provider);
                    if (parsed == null) {
                        intent = intent2;
                        broadcastReceivers = broadcastReceivers2;
                        N = N2;
                    } else {
                        keep.add(providerId);
                        provider.info = parsed.info;
                        int M = provider.widgets.size();
                        if (M <= 0) {
                            intent = intent2;
                            broadcastReceivers = broadcastReceivers2;
                            N = N2;
                        } else {
                            int[] appWidgetIds = getWidgetIds(provider.widgets);
                            cancelBroadcastsLocked(provider);
                            registerForBroadcastsLocked(provider, appWidgetIds);
                            intent = intent2;
                            int j = 0;
                            while (j < M) {
                                List<ResolveInfo> broadcastReceivers3 = broadcastReceivers2;
                                Widget widget = provider.widgets.get(j);
                                widget.views = null;
                                scheduleNotifyProviderChangedLocked(widget);
                                j++;
                                broadcastReceivers2 = broadcastReceivers3;
                                N2 = N2;
                            }
                            broadcastReceivers = broadcastReceivers2;
                            N = N2;
                            sendUpdateIntentLocked(provider, appWidgetIds);
                        }
                    }
                    providersUpdated2 = true;
                }
                i++;
                broadcastReceivers2 = broadcastReceivers;
                intent2 = intent;
                N2 = N;
            }
            providersUpdated2 = providersUpdated;
            i++;
            broadcastReceivers2 = broadcastReceivers;
            intent2 = intent;
            N2 = N;
        }
        boolean providersUpdated3 = providersUpdated2;
        int N3 = this.mProviders.size();
        for (int i2 = N3 - 1; i2 >= 0; i2--) {
            Provider provider2 = this.mProviders.get(i2);
            if (packageName.equals(provider2.info.provider.getPackageName()) && provider2.getUserId() == userId && !keep.contains(provider2.id)) {
                if (removedProviders != null) {
                    removedProviders.add(provider2.id);
                }
                deleteProviderLocked(provider2);
                providersUpdated3 = true;
            }
        }
        return providersUpdated3;
    }

    private void removeWidgetsForPackageLocked(String pkgName, int userId, int parentUserId) {
        int N = this.mProviders.size();
        for (int i = 0; i < N; i++) {
            Provider provider = this.mProviders.get(i);
            if (pkgName.equals(provider.info.provider.getPackageName()) && provider.getUserId() == userId && provider.widgets.size() > 0) {
                deleteWidgetsLocked(provider, parentUserId);
            }
        }
    }

    private boolean removeProvidersForPackageLocked(String pkgName, int userId) {
        boolean removed = false;
        int N = this.mProviders.size();
        for (int i = N - 1; i >= 0; i--) {
            Provider provider = this.mProviders.get(i);
            if (pkgName.equals(provider.info.provider.getPackageName()) && provider.getUserId() == userId) {
                deleteProviderLocked(provider);
                removed = true;
            }
        }
        return removed;
    }

    private boolean removeHostsAndProvidersForPackageLocked(String pkgName, int userId) {
        boolean removed = removeProvidersForPackageLocked(pkgName, userId);
        int N = this.mHosts.size();
        for (int i = N - 1; i >= 0; i--) {
            Host host = this.mHosts.get(i);
            if (pkgName.equals(host.id.packageName) && host.getUserId() == userId) {
                deleteHostLocked(host);
                removed = true;
            }
        }
        return removed;
    }

    private String getCanonicalPackageName(String packageName, String className, int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            AppGlobals.getPackageManager().getReceiverInfo(new ComponentName(packageName, className), 0, userId);
            return packageName;
        } catch (RemoteException e) {
            String[] packageNames = this.mContext.getPackageManager().currentToCanonicalPackageNames(new String[]{packageName});
            if (packageNames == null || packageNames.length <= 0) {
                Binder.restoreCallingIdentity(identity);
                return null;
            }
            return packageNames[0];
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBroadcastAsUser(Intent intent, UserHandle userHandle) {
        long identity = Binder.clearCallingIdentity();
        try {
            this.mContext.sendBroadcastAsUser(intent, userHandle);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void bindService(Intent intent, ServiceConnection connection, UserHandle userHandle) {
        long token = Binder.clearCallingIdentity();
        try {
            this.mContext.bindServiceAsUser(intent, connection, 33554433, userHandle);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void unbindService(ServiceConnection connection) {
        long token = Binder.clearCallingIdentity();
        try {
            this.mContext.unbindService(connection);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void onCrossProfileWidgetProvidersChanged(int userId, List<String> packages) {
        int parentId = this.mSecurityPolicy.getProfileParent(userId);
        if (parentId != userId) {
            synchronized (this.mLock) {
                boolean providersChanged = false;
                ArraySet<String> previousPackages = new ArraySet<>();
                int providerCount = this.mProviders.size();
                for (int i = 0; i < providerCount; i++) {
                    Provider provider = this.mProviders.get(i);
                    if (provider.getUserId() == userId) {
                        previousPackages.add(provider.id.componentName.getPackageName());
                    }
                }
                int packageCount = packages.size();
                for (int i2 = 0; i2 < packageCount; i2++) {
                    String packageName = packages.get(i2);
                    previousPackages.remove(packageName);
                    providersChanged |= updateProvidersForPackageLocked(packageName, userId, null);
                }
                int removedCount = previousPackages.size();
                for (int i3 = 0; i3 < removedCount; i3++) {
                    removeWidgetsForPackageLocked(previousPackages.valueAt(i3), userId, parentId);
                }
                if (providersChanged || removedCount > 0) {
                    saveGroupStateAsync(userId);
                    scheduleNotifyGroupHostsForProvidersChangedLocked(userId);
                }
            }
        }
    }

    private boolean isProfileWithLockedParent(int userId) {
        UserInfo parentInfo;
        long token = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = this.mUserManager.getUserInfo(userId);
            if (userInfo != null && userInfo.isManagedProfile() && (parentInfo = this.mUserManager.getProfileParent(userId)) != null) {
                if (!isUserRunningAndUnlocked(parentInfo.getUserHandle().getIdentifier())) {
                    return true;
                }
            }
            Binder.restoreCallingIdentity(token);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isProfileWithUnlockedParent(int userId) {
        UserInfo parentInfo;
        UserInfo userInfo = this.mUserManager.getUserInfo(userId);
        if (userInfo != null && userInfo.isManagedProfile() && (parentInfo = this.mUserManager.getProfileParent(userId)) != null && this.mUserManager.isUserUnlockingOrUnlocked(parentInfo.getUserHandle())) {
            return true;
        }
        return false;
    }

    /* loaded from: classes.dex */
    private final class CallbackHandler extends Handler {
        public static final int MSG_NOTIFY_PROVIDERS_CHANGED = 3;
        public static final int MSG_NOTIFY_PROVIDER_CHANGED = 2;
        public static final int MSG_NOTIFY_UPDATE_APP_WIDGET = 1;
        public static final int MSG_NOTIFY_VIEW_DATA_CHANGED = 4;

        public CallbackHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            int i = message.what;
            if (i == 1) {
                SomeArgs args = (SomeArgs) message.obj;
                Host host = (Host) args.arg1;
                IAppWidgetHost callbacks = (IAppWidgetHost) args.arg2;
                RemoteViews views = (RemoteViews) args.arg3;
                long requestId = ((Long) args.arg4).longValue();
                int appWidgetId = args.argi1;
                args.recycle();
                AppWidgetServiceImpl.this.handleNotifyUpdateAppWidget(host, callbacks, appWidgetId, views, requestId);
            } else if (i == 2) {
                SomeArgs args2 = (SomeArgs) message.obj;
                Host host2 = (Host) args2.arg1;
                IAppWidgetHost callbacks2 = (IAppWidgetHost) args2.arg2;
                AppWidgetProviderInfo info = (AppWidgetProviderInfo) args2.arg3;
                long requestId2 = ((Long) args2.arg4).longValue();
                int appWidgetId2 = args2.argi1;
                args2.recycle();
                AppWidgetServiceImpl.this.handleNotifyProviderChanged(host2, callbacks2, appWidgetId2, info, requestId2);
            } else if (i == 3) {
                SomeArgs args3 = (SomeArgs) message.obj;
                Host host3 = (Host) args3.arg1;
                IAppWidgetHost callbacks3 = (IAppWidgetHost) args3.arg2;
                args3.recycle();
                AppWidgetServiceImpl.this.handleNotifyProvidersChanged(host3, callbacks3);
            } else if (i == 4) {
                SomeArgs args4 = (SomeArgs) message.obj;
                Host host4 = (Host) args4.arg1;
                IAppWidgetHost callbacks4 = (IAppWidgetHost) args4.arg2;
                long requestId3 = ((Long) args4.arg3).longValue();
                int appWidgetId3 = args4.argi1;
                int viewId = args4.argi2;
                args4.recycle();
                AppWidgetServiceImpl.this.handleNotifyAppWidgetViewDataChanged(host4, callbacks4, appWidgetId3, viewId, requestId3);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SecurityPolicy {
        private SecurityPolicy() {
        }

        public boolean isEnabledGroupProfile(int profileId) {
            int parentId = UserHandle.getCallingUserId();
            return isParentOrProfile(parentId, profileId) && isProfileEnabled(profileId);
        }

        public int[] getEnabledGroupProfileIds(int userId) {
            int parentId = getGroupParent(userId);
            long identity = Binder.clearCallingIdentity();
            try {
                return AppWidgetServiceImpl.this.mUserManager.getEnabledProfileIds(parentId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void enforceServiceExistsAndRequiresBindRemoteViewsPermission(ComponentName componentName, int userId) {
            ServiceInfo serviceInfo;
            long identity = Binder.clearCallingIdentity();
            try {
                serviceInfo = AppWidgetServiceImpl.this.mPackageManager.getServiceInfo(componentName, 4096, userId);
            } catch (RemoteException e) {
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
            if (serviceInfo != null) {
                if (!"android.permission.BIND_REMOTEVIEWS".equals(serviceInfo.permission)) {
                    throw new SecurityException("Service " + componentName + " in user " + userId + "does not require android.permission.BIND_REMOTEVIEWS");
                }
                Binder.restoreCallingIdentity(identity);
                return;
            }
            throw new SecurityException("Service " + componentName + " not installed for user " + userId);
        }

        public void enforceModifyAppWidgetBindPermissions(String packageName) {
            Context context = AppWidgetServiceImpl.this.mContext;
            context.enforceCallingPermission("android.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS", "hasBindAppWidgetPermission packageName=" + packageName);
        }

        public boolean isCallerInstantAppLocked() {
            int callingUid = Binder.getCallingUid();
            long identity = Binder.clearCallingIdentity();
            try {
                String[] uidPackages = AppWidgetServiceImpl.this.mPackageManager.getPackagesForUid(callingUid);
                if (!ArrayUtils.isEmpty(uidPackages)) {
                    boolean isInstantApp = AppWidgetServiceImpl.this.mPackageManager.isInstantApp(uidPackages[0], UserHandle.getCallingUserId());
                    Binder.restoreCallingIdentity(identity);
                    return isInstantApp;
                }
            } catch (RemoteException e) {
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
            Binder.restoreCallingIdentity(identity);
            return false;
        }

        public boolean isInstantAppLocked(String packageName, int userId) {
            long identity = Binder.clearCallingIdentity();
            try {
                boolean isInstantApp = AppWidgetServiceImpl.this.mPackageManager.isInstantApp(packageName, userId);
                Binder.restoreCallingIdentity(identity);
                return isInstantApp;
            } catch (RemoteException e) {
                Binder.restoreCallingIdentity(identity);
                return false;
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }

        public void enforceCallFromPackage(String packageName) {
            AppWidgetServiceImpl.this.mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
        }

        public boolean hasCallerBindPermissionOrBindWhiteListedLocked(String packageName) {
            try {
                AppWidgetServiceImpl.this.mContext.enforceCallingOrSelfPermission("android.permission.BIND_APPWIDGET", null);
                return true;
            } catch (SecurityException e) {
                if (!isCallerBindAppWidgetWhiteListedLocked(packageName)) {
                    return false;
                }
                return true;
            }
        }

        private boolean isCallerBindAppWidgetWhiteListedLocked(String packageName) {
            int userId = UserHandle.getCallingUserId();
            int packageUid = AppWidgetServiceImpl.this.getUidForPackage(packageName, userId);
            if (packageUid >= 0) {
                synchronized (AppWidgetServiceImpl.this.mLock) {
                    AppWidgetServiceImpl.this.ensureGroupStateLoadedLocked(userId);
                    Pair<Integer, String> packageId = Pair.create(Integer.valueOf(userId), packageName);
                    if (AppWidgetServiceImpl.this.mPackagesWithBindWidgetPermission.contains(packageId)) {
                        return true;
                    }
                    return false;
                }
            }
            throw new IllegalArgumentException("No package " + packageName + " for user " + userId);
        }

        public boolean canAccessAppWidget(Widget widget, int uid, String packageName) {
            if (isHostInPackageForUid(widget.host, uid, packageName) || isProviderInPackageForUid(widget.provider, uid, packageName) || isHostAccessingProvider(widget.host, widget.provider, uid, packageName)) {
                return true;
            }
            int userId = UserHandle.getUserId(uid);
            return (widget.host.getUserId() == userId || (widget.provider != null && widget.provider.getUserId() == userId)) && AppWidgetServiceImpl.this.mContext.checkCallingPermission("android.permission.BIND_APPWIDGET") == 0;
        }

        private boolean isParentOrProfile(int parentId, int profileId) {
            return parentId == profileId || getProfileParent(profileId) == parentId;
        }

        public boolean isProviderInCallerOrInProfileAndWhitelListed(String packageName, int profileId) {
            int callerId = UserHandle.getCallingUserId();
            if (profileId == callerId) {
                return true;
            }
            int parentId = getProfileParent(profileId);
            if (parentId != callerId) {
                return false;
            }
            return isProviderWhiteListed(packageName, profileId);
        }

        public boolean isProviderWhiteListed(String packageName, int profileId) {
            if (AppWidgetServiceImpl.this.mDevicePolicyManagerInternal == null) {
                return false;
            }
            List<String> crossProfilePackages = AppWidgetServiceImpl.this.mDevicePolicyManagerInternal.getCrossProfileWidgetProviders(profileId);
            return crossProfilePackages.contains(packageName);
        }

        public int getProfileParent(int profileId) {
            long identity = Binder.clearCallingIdentity();
            try {
                UserInfo parent = AppWidgetServiceImpl.this.mUserManager.getProfileParent(profileId);
                if (parent != null) {
                    return parent.getUserHandle().getIdentifier();
                }
                Binder.restoreCallingIdentity(identity);
                return -10;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public int getGroupParent(int profileId) {
            int parentId = AppWidgetServiceImpl.this.mSecurityPolicy.getProfileParent(profileId);
            return parentId != -10 ? parentId : profileId;
        }

        public boolean isHostInPackageForUid(Host host, int uid, String packageName) {
            return host.id.uid == uid && host.id.packageName.equals(packageName);
        }

        public boolean isProviderInPackageForUid(Provider provider, int uid, String packageName) {
            return provider != null && provider.id.uid == uid && provider.id.componentName.getPackageName().equals(packageName);
        }

        public boolean isHostAccessingProvider(Host host, Provider provider, int uid, String packageName) {
            return host.id.uid == uid && provider != null && provider.id.componentName.getPackageName().equals(packageName);
        }

        private boolean isProfileEnabled(int profileId) {
            long identity = Binder.clearCallingIdentity();
            try {
                UserInfo userInfo = AppWidgetServiceImpl.this.mUserManager.getUserInfo(profileId);
                if (userInfo != null) {
                    if (userInfo.isEnabled()) {
                        Binder.restoreCallingIdentity(identity);
                        return true;
                    }
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Provider {
        PendingIntent broadcast;
        ProviderId id;
        AppWidgetProviderInfo info;
        String infoTag;
        boolean maskedByLockedProfile;
        boolean maskedByQuietProfile;
        boolean maskedBySuspendedPackage;
        int tag;
        ArrayList<Widget> widgets;
        boolean zombie;

        private Provider() {
            this.widgets = new ArrayList<>();
            this.tag = -1;
        }

        public int getUserId() {
            return UserHandle.getUserId(this.id.uid);
        }

        public boolean isInPackageForUser(String packageName, int userId) {
            return getUserId() == userId && this.id.componentName.getPackageName().equals(packageName);
        }

        public boolean hostedByPackageForUser(String packageName, int userId) {
            int N = this.widgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = this.widgets.get(i);
                if (packageName.equals(widget.host.id.packageName) && widget.host.getUserId() == userId) {
                    return true;
                }
            }
            return false;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Provider{");
            sb.append(this.id);
            sb.append(this.zombie ? " Z" : "");
            sb.append('}');
            return sb.toString();
        }

        public boolean setMaskedByQuietProfileLocked(boolean masked) {
            boolean oldState = this.maskedByQuietProfile;
            this.maskedByQuietProfile = masked;
            return masked != oldState;
        }

        public boolean setMaskedByLockedProfileLocked(boolean masked) {
            boolean oldState = this.maskedByLockedProfile;
            this.maskedByLockedProfile = masked;
            return masked != oldState;
        }

        public boolean setMaskedBySuspendedPackageLocked(boolean masked) {
            boolean oldState = this.maskedBySuspendedPackage;
            this.maskedBySuspendedPackage = masked;
            return masked != oldState;
        }

        public boolean isMaskedLocked() {
            return this.maskedByQuietProfile || this.maskedByLockedProfile || this.maskedBySuspendedPackage;
        }

        public boolean shouldBePersisted() {
            return (this.widgets.isEmpty() && TextUtils.isEmpty(this.infoTag)) ? false : true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ProviderId {
        final ComponentName componentName;
        final int uid;

        private ProviderId(int uid, ComponentName componentName) {
            this.uid = uid;
            this.componentName = componentName;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            ProviderId other = (ProviderId) obj;
            if (this.uid != other.uid) {
                return false;
            }
            ComponentName componentName = this.componentName;
            if (componentName == null) {
                if (other.componentName != null) {
                    return false;
                }
            } else if (!componentName.equals(other.componentName)) {
                return false;
            }
            return true;
        }

        public int hashCode() {
            int result = this.uid;
            int i = result * 31;
            ComponentName componentName = this.componentName;
            int result2 = i + (componentName != null ? componentName.hashCode() : 0);
            return result2;
        }

        public String toString() {
            return "ProviderId{user:" + UserHandle.getUserId(this.uid) + ", app:" + UserHandle.getAppId(this.uid) + ", cmp:" + this.componentName + '}';
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Host {
        IAppWidgetHost callbacks;
        HostId id;
        long lastWidgetUpdateSequenceNo;
        int tag;
        ArrayList<Widget> widgets;
        boolean zombie;

        private Host() {
            this.widgets = new ArrayList<>();
            this.tag = -1;
        }

        public int getUserId() {
            return UserHandle.getUserId(this.id.uid);
        }

        public boolean isInPackageForUser(String packageName, int userId) {
            return getUserId() == userId && this.id.packageName.equals(packageName);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean hostsPackageForUser(String pkg, int userId) {
            int N = this.widgets.size();
            for (int i = 0; i < N; i++) {
                Provider provider = this.widgets.get(i).provider;
                if (provider != null && provider.getUserId() == userId && provider.info != null && pkg.equals(provider.info.provider.getPackageName())) {
                    return true;
                }
            }
            return false;
        }

        public boolean getPendingUpdatesForId(int appWidgetId, LongSparseArray<PendingHostUpdate> outUpdates) {
            PendingHostUpdate update;
            long updateSequenceNo = this.lastWidgetUpdateSequenceNo;
            int N = this.widgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = this.widgets.get(i);
                if (widget.appWidgetId == appWidgetId) {
                    outUpdates.clear();
                    for (int j = widget.updateSequenceNos.size() - 1; j >= 0; j--) {
                        long requestId = widget.updateSequenceNos.valueAt(j);
                        if (requestId > updateSequenceNo) {
                            int id = widget.updateSequenceNos.keyAt(j);
                            if (id == 0) {
                                update = PendingHostUpdate.updateAppWidget(appWidgetId, AppWidgetServiceImpl.cloneIfLocalBinder(widget.getEffectiveViewsLocked()));
                            } else if (id == 1) {
                                update = PendingHostUpdate.providerChanged(appWidgetId, widget.provider.info);
                            } else {
                                update = PendingHostUpdate.viewDataChanged(appWidgetId, id);
                            }
                            outUpdates.put(requestId, update);
                        }
                    }
                    return true;
                }
            }
            return false;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Host{");
            sb.append(this.id);
            sb.append(this.zombie ? " Z" : "");
            sb.append('}');
            return sb.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class HostId {
        final int hostId;
        final String packageName;
        final int uid;

        public HostId(int uid, int hostId, String packageName) {
            this.uid = uid;
            this.hostId = hostId;
            this.packageName = packageName;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            HostId other = (HostId) obj;
            if (this.uid != other.uid || this.hostId != other.hostId) {
                return false;
            }
            String str = this.packageName;
            if (str == null) {
                if (other.packageName != null) {
                    return false;
                }
            } else if (!str.equals(other.packageName)) {
                return false;
            }
            return true;
        }

        public int hashCode() {
            int result = this.uid;
            int result2 = ((result * 31) + this.hostId) * 31;
            String str = this.packageName;
            return result2 + (str != null ? str.hashCode() : 0);
        }

        public String toString() {
            return "HostId{user:" + UserHandle.getUserId(this.uid) + ", app:" + UserHandle.getAppId(this.uid) + ", hostId:" + this.hostId + ", pkg:" + this.packageName + '}';
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Widget {
        int appWidgetId;
        Host host;
        RemoteViews maskedViews;
        Bundle options;
        Provider provider;
        int restoredId;
        SparseLongArray updateSequenceNos;
        RemoteViews views;

        private Widget() {
            this.updateSequenceNos = new SparseLongArray(2);
        }

        public String toString() {
            return "AppWidgetId{" + this.appWidgetId + ':' + this.host + ':' + this.provider + '}';
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean replaceWithMaskedViewsLocked(RemoteViews views) {
            this.maskedViews = views;
            return true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean clearMaskedViewsLocked() {
            if (this.maskedViews != null) {
                this.maskedViews = null;
                return true;
            }
            return false;
        }

        public RemoteViews getEffectiveViewsLocked() {
            RemoteViews remoteViews = this.maskedViews;
            return remoteViews != null ? remoteViews : this.views;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LoadedWidgetState {
        final int hostTag;
        final int providerTag;
        final Widget widget;

        public LoadedWidgetState(Widget widget, int hostTag, int providerTag) {
            this.widget = widget;
            this.hostTag = hostTag;
            this.providerTag = providerTag;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SaveStateRunnable implements Runnable {
        final int mUserId;

        public SaveStateRunnable(int userId) {
            this.mUserId = userId;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (AppWidgetServiceImpl.this.mLock) {
                AppWidgetServiceImpl.this.ensureGroupStateLoadedLocked(this.mUserId, false);
                AppWidgetServiceImpl.this.saveStateLocked(this.mUserId);
            }
        }
    }

    /* loaded from: classes.dex */
    private final class BackupRestoreController {
        private static final boolean DEBUG = true;
        private static final String TAG = "BackupRestoreController";
        private static final int WIDGET_STATE_VERSION = 2;
        private final HashSet<String> mPrunedApps;
        private final HashMap<Host, ArrayList<RestoreUpdateRecord>> mUpdatesByHost;
        private final HashMap<Provider, ArrayList<RestoreUpdateRecord>> mUpdatesByProvider;

        private BackupRestoreController() {
            this.mPrunedApps = new HashSet<>();
            this.mUpdatesByProvider = new HashMap<>();
            this.mUpdatesByHost = new HashMap<>();
        }

        public List<String> getWidgetParticipants(int userId) {
            Slog.i(TAG, "Getting widget participants for user: " + userId);
            HashSet<String> packages = new HashSet<>();
            synchronized (AppWidgetServiceImpl.this.mLock) {
                int N = AppWidgetServiceImpl.this.mWidgets.size();
                for (int i = 0; i < N; i++) {
                    Widget widget = (Widget) AppWidgetServiceImpl.this.mWidgets.get(i);
                    if (isProviderAndHostInUser(widget, userId)) {
                        packages.add(widget.host.id.packageName);
                        Provider provider = widget.provider;
                        if (provider != null) {
                            packages.add(provider.id.componentName.getPackageName());
                        }
                    }
                }
            }
            return new ArrayList(packages);
        }

        public byte[] getWidgetState(String backedupPackage, int userId) {
            Slog.i(TAG, "Getting widget state for user: " + userId);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            synchronized (AppWidgetServiceImpl.this.mLock) {
                if (packageNeedsWidgetBackupLocked(backedupPackage, userId)) {
                    try {
                        FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                        fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
                        fastXmlSerializer.startDocument(null, true);
                        fastXmlSerializer.startTag(null, "ws");
                        fastXmlSerializer.attribute(null, "version", String.valueOf(2));
                        fastXmlSerializer.attribute(null, "pkg", backedupPackage);
                        int index = 0;
                        int N = AppWidgetServiceImpl.this.mProviders.size();
                        for (int i = 0; i < N; i++) {
                            Provider provider = (Provider) AppWidgetServiceImpl.this.mProviders.get(i);
                            if (provider.shouldBePersisted() && (provider.isInPackageForUser(backedupPackage, userId) || provider.hostedByPackageForUser(backedupPackage, userId))) {
                                provider.tag = index;
                                AppWidgetServiceImpl.serializeProvider(fastXmlSerializer, provider);
                                index++;
                            }
                        }
                        int N2 = AppWidgetServiceImpl.this.mHosts.size();
                        int index2 = 0;
                        for (int i2 = 0; i2 < N2; i2++) {
                            Host host = (Host) AppWidgetServiceImpl.this.mHosts.get(i2);
                            if (!host.widgets.isEmpty() && (host.isInPackageForUser(backedupPackage, userId) || host.hostsPackageForUser(backedupPackage, userId))) {
                                host.tag = index2;
                                AppWidgetServiceImpl.serializeHost(fastXmlSerializer, host);
                                index2++;
                            }
                        }
                        int N3 = AppWidgetServiceImpl.this.mWidgets.size();
                        for (int i3 = 0; i3 < N3; i3++) {
                            Widget widget = (Widget) AppWidgetServiceImpl.this.mWidgets.get(i3);
                            Provider provider2 = widget.provider;
                            if (widget.host.isInPackageForUser(backedupPackage, userId) || (provider2 != null && provider2.isInPackageForUser(backedupPackage, userId))) {
                                AppWidgetServiceImpl.serializeAppWidget(fastXmlSerializer, widget);
                            }
                        }
                        fastXmlSerializer.endTag(null, "ws");
                        fastXmlSerializer.endDocument();
                        return stream.toByteArray();
                    } catch (IOException e) {
                        Slog.w(TAG, "Unable to save widget state for " + backedupPackage);
                        return null;
                    }
                }
                return null;
            }
        }

        public void restoreStarting(int userId) {
            Slog.i(TAG, "Restore starting for user: " + userId);
            synchronized (AppWidgetServiceImpl.this.mLock) {
                this.mPrunedApps.clear();
                this.mUpdatesByProvider.clear();
                this.mUpdatesByHost.clear();
            }
        }

        public void restoreWidgetState(String packageName, byte[] restoredState, int userId) {
            ByteArrayInputStream stream;
            ArrayList<Provider> restoredProviders;
            ArrayList<Host> restoredHosts;
            Provider p;
            Slog.i(TAG, "Restoring widget state for user:" + userId + " package: " + packageName);
            ByteArrayInputStream stream2 = new ByteArrayInputStream(restoredState);
            try {
                ArrayList<Provider> restoredProviders2 = new ArrayList<>();
                ArrayList<Host> restoredHosts2 = new ArrayList<>();
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream2, StandardCharsets.UTF_8.name());
                synchronized (AppWidgetServiceImpl.this.mLock) {
                    while (true) {
                        try {
                            int type = parser.next();
                            if (type == 2) {
                                String tag = parser.getName();
                                if ("ws".equals(tag)) {
                                    try {
                                        String version = parser.getAttributeValue(null, "version");
                                        int versionNumber = Integer.parseInt(version);
                                        if (versionNumber > 2) {
                                            Slog.w(TAG, "Unable to process state version " + version);
                                            AppWidgetServiceImpl.this.saveGroupStateAsync(userId);
                                            return;
                                        }
                                        String pkg = parser.getAttributeValue(null, "pkg");
                                        if (!packageName.equals(pkg)) {
                                            Slog.w(TAG, "Package mismatch in ws");
                                            AppWidgetServiceImpl.this.saveGroupStateAsync(userId);
                                            return;
                                        }
                                        stream = stream2;
                                        restoredProviders = restoredProviders2;
                                        restoredHosts = restoredHosts2;
                                    } catch (Throwable th) {
                                        th = th;
                                        try {
                                            try {
                                                throw th;
                                            } catch (Throwable th2) {
                                                th = th2;
                                                AppWidgetServiceImpl.this.saveGroupStateAsync(userId);
                                                throw th;
                                            }
                                        } catch (IOException | XmlPullParserException e) {
                                            Slog.w(TAG, "Unable to restore widget state for " + packageName);
                                            AppWidgetServiceImpl.this.saveGroupStateAsync(userId);
                                            return;
                                        }
                                    }
                                } else if ("p".equals(tag)) {
                                    try {
                                        String pkg2 = parser.getAttributeValue(null, "pkg");
                                        String cl = parser.getAttributeValue(null, "cl");
                                        ComponentName componentName = new ComponentName(pkg2, cl);
                                        Provider p2 = findProviderLocked(componentName, userId);
                                        if (p2 == null) {
                                            p = new Provider();
                                            stream = stream2;
                                            try {
                                                p.id = new ProviderId(-1, componentName);
                                                p.info = new AppWidgetProviderInfo();
                                                p.info.provider = componentName;
                                                p.zombie = true;
                                                AppWidgetServiceImpl.this.mProviders.add(p);
                                            } catch (Throwable th3) {
                                                th = th3;
                                                throw th;
                                            }
                                        } else {
                                            stream = stream2;
                                            p = p2;
                                        }
                                        Slog.i(TAG, "   provider " + p.id);
                                        restoredProviders2.add(p);
                                        restoredProviders = restoredProviders2;
                                        restoredHosts = restoredHosts2;
                                    } catch (Throwable th4) {
                                        th = th4;
                                    }
                                } else {
                                    stream = stream2;
                                    try {
                                        if ("h".equals(tag)) {
                                            String pkg3 = parser.getAttributeValue(null, "pkg");
                                            int uid = AppWidgetServiceImpl.this.getUidForPackage(pkg3, userId);
                                            int hostId = Integer.parseInt(parser.getAttributeValue(null, "id"), 16);
                                            Host h = AppWidgetServiceImpl.this.lookupOrAddHostLocked(new HostId(uid, hostId, pkg3));
                                            restoredHosts2.add(h);
                                            Slog.i(TAG, "   host[" + restoredHosts2.size() + "]: {" + h.id + "}");
                                            restoredProviders = restoredProviders2;
                                            restoredHosts = restoredHosts2;
                                        } else if ("g".equals(tag)) {
                                            int restoredId = Integer.parseInt(parser.getAttributeValue(null, "id"), 16);
                                            int hostIndex = Integer.parseInt(parser.getAttributeValue(null, "h"), 16);
                                            Host host = restoredHosts2.get(hostIndex);
                                            Provider p3 = null;
                                            String prov = parser.getAttributeValue(null, "p");
                                            if (prov != null) {
                                                int which = Integer.parseInt(prov, 16);
                                                p3 = restoredProviders2.get(which);
                                            }
                                            pruneWidgetStateLocked(host.id.packageName, userId);
                                            if (p3 != null) {
                                                pruneWidgetStateLocked(p3.id.componentName.getPackageName(), userId);
                                            }
                                            Widget id = findRestoredWidgetLocked(restoredId, host, p3);
                                            if (id == null) {
                                                id = new Widget();
                                                id.appWidgetId = AppWidgetServiceImpl.this.incrementAndGetAppWidgetIdLocked(userId);
                                                id.restoredId = restoredId;
                                                id.options = parseWidgetIdOptions(parser);
                                                id.host = host;
                                                id.host.widgets.add(id);
                                                id.provider = p3;
                                                if (id.provider != null) {
                                                    id.provider.widgets.add(id);
                                                }
                                                restoredProviders = restoredProviders2;
                                                try {
                                                    StringBuilder sb = new StringBuilder();
                                                    restoredHosts = restoredHosts2;
                                                    try {
                                                        sb.append("New restored id ");
                                                        sb.append(restoredId);
                                                        sb.append(" now ");
                                                        sb.append(id);
                                                        Slog.i(TAG, sb.toString());
                                                        AppWidgetServiceImpl.this.addWidgetLocked(id);
                                                    } catch (Throwable th5) {
                                                        th = th5;
                                                        throw th;
                                                    }
                                                } catch (Throwable th6) {
                                                    th = th6;
                                                }
                                            } else {
                                                restoredProviders = restoredProviders2;
                                                restoredHosts = restoredHosts2;
                                            }
                                            if (id.provider == null || id.provider.info == null) {
                                                Slog.w(TAG, "Missing provider for restored widget " + id);
                                            } else {
                                                stashProviderRestoreUpdateLocked(id.provider, restoredId, id.appWidgetId);
                                            }
                                            stashHostRestoreUpdateLocked(id.host, restoredId, id.appWidgetId);
                                            Slog.i(TAG, "   instance: " + restoredId + " -> " + id.appWidgetId + " :: p=" + id.provider);
                                        } else {
                                            restoredProviders = restoredProviders2;
                                            restoredHosts = restoredHosts2;
                                        }
                                    } catch (Throwable th7) {
                                        th = th7;
                                    }
                                }
                            } else {
                                stream = stream2;
                                restoredProviders = restoredProviders2;
                                restoredHosts = restoredHosts2;
                            }
                            if (type == 1) {
                                break;
                            }
                            restoredProviders2 = restoredProviders;
                            stream2 = stream;
                            restoredHosts2 = restoredHosts;
                        } catch (Throwable th8) {
                            th = th8;
                        }
                    }
                }
            } catch (IOException | XmlPullParserException e2) {
            } catch (Throwable th9) {
                th = th9;
                AppWidgetServiceImpl.this.saveGroupStateAsync(userId);
                throw th;
            }
        }

        public void restoreFinished(int userId) {
            Slog.i(TAG, "restoreFinished for " + userId);
            UserHandle userHandle = new UserHandle(userId);
            synchronized (AppWidgetServiceImpl.this.mLock) {
                Set<Map.Entry<Provider, ArrayList<RestoreUpdateRecord>>> providerEntries = this.mUpdatesByProvider.entrySet();
                Iterator<Map.Entry<Provider, ArrayList<RestoreUpdateRecord>>> it = providerEntries.iterator();
                while (true) {
                    boolean z = true;
                    if (!it.hasNext()) {
                        break;
                    }
                    Map.Entry<Provider, ArrayList<RestoreUpdateRecord>> e = it.next();
                    Provider provider = e.getKey();
                    ArrayList<RestoreUpdateRecord> updates = e.getValue();
                    int pending = countPendingUpdates(updates);
                    Slog.i(TAG, "Provider " + provider + " pending: " + pending);
                    if (pending > 0) {
                        int[] oldIds = new int[pending];
                        int[] newIds = new int[pending];
                        int N = updates.size();
                        int i = 0;
                        int nextPending = 0;
                        while (i < N) {
                            RestoreUpdateRecord r = updates.get(i);
                            if (!r.notified) {
                                r.notified = z;
                                oldIds[nextPending] = r.oldId;
                                newIds[nextPending] = r.newId;
                                nextPending++;
                                Slog.i(TAG, "   " + r.oldId + " => " + r.newId);
                            }
                            i++;
                            z = true;
                        }
                        sendWidgetRestoreBroadcastLocked("android.appwidget.action.APPWIDGET_RESTORED", provider, null, oldIds, newIds, userHandle);
                    }
                }
                Set<Map.Entry<Host, ArrayList<RestoreUpdateRecord>>> hostEntries = this.mUpdatesByHost.entrySet();
                for (Map.Entry<Host, ArrayList<RestoreUpdateRecord>> e2 : hostEntries) {
                    Host host = e2.getKey();
                    if (host.id.uid != -1) {
                        ArrayList<RestoreUpdateRecord> updates2 = e2.getValue();
                        int pending2 = countPendingUpdates(updates2);
                        Slog.i(TAG, "Host " + host + " pending: " + pending2);
                        if (pending2 > 0) {
                            int[] oldIds2 = new int[pending2];
                            int[] newIds2 = new int[pending2];
                            int N2 = updates2.size();
                            int nextPending2 = 0;
                            for (int i2 = 0; i2 < N2; i2++) {
                                RestoreUpdateRecord r2 = updates2.get(i2);
                                if (!r2.notified) {
                                    r2.notified = true;
                                    oldIds2[nextPending2] = r2.oldId;
                                    newIds2[nextPending2] = r2.newId;
                                    nextPending2++;
                                    Slog.i(TAG, "   " + r2.oldId + " => " + r2.newId);
                                }
                            }
                            sendWidgetRestoreBroadcastLocked("android.appwidget.action.APPWIDGET_HOST_RESTORED", null, host, oldIds2, newIds2, userHandle);
                        }
                    }
                }
            }
        }

        private Provider findProviderLocked(ComponentName componentName, int userId) {
            int providerCount = AppWidgetServiceImpl.this.mProviders.size();
            for (int i = 0; i < providerCount; i++) {
                Provider provider = (Provider) AppWidgetServiceImpl.this.mProviders.get(i);
                if (provider.getUserId() == userId && provider.id.componentName.equals(componentName)) {
                    return provider;
                }
            }
            return null;
        }

        private Widget findRestoredWidgetLocked(int restoredId, Host host, Provider p) {
            Slog.i(TAG, "Find restored widget: id=" + restoredId + " host=" + host + " provider=" + p);
            if (p != null && host != null) {
                int N = AppWidgetServiceImpl.this.mWidgets.size();
                for (int i = 0; i < N; i++) {
                    Widget widget = (Widget) AppWidgetServiceImpl.this.mWidgets.get(i);
                    if (widget.restoredId == restoredId && widget.host.id.equals(host.id) && widget.provider.id.equals(p.id)) {
                        Slog.i(TAG, "   Found at " + i + " : " + widget);
                        return widget;
                    }
                }
                return null;
            }
            return null;
        }

        private boolean packageNeedsWidgetBackupLocked(String packageName, int userId) {
            int N = AppWidgetServiceImpl.this.mWidgets.size();
            for (int i = 0; i < N; i++) {
                Widget widget = (Widget) AppWidgetServiceImpl.this.mWidgets.get(i);
                if (isProviderAndHostInUser(widget, userId)) {
                    if (widget.host.isInPackageForUser(packageName, userId)) {
                        return true;
                    }
                    Provider provider = widget.provider;
                    if (provider != null && provider.isInPackageForUser(packageName, userId)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void stashProviderRestoreUpdateLocked(Provider provider, int oldId, int newId) {
            ArrayList<RestoreUpdateRecord> r = this.mUpdatesByProvider.get(provider);
            if (r == null) {
                r = new ArrayList<>();
                this.mUpdatesByProvider.put(provider, r);
            } else if (alreadyStashed(r, oldId, newId)) {
                Slog.i(TAG, "ID remap " + oldId + " -> " + newId + " already stashed for " + provider);
                return;
            }
            r.add(new RestoreUpdateRecord(oldId, newId));
        }

        private boolean alreadyStashed(ArrayList<RestoreUpdateRecord> stash, int oldId, int newId) {
            int N = stash.size();
            for (int i = 0; i < N; i++) {
                RestoreUpdateRecord r = stash.get(i);
                if (r.oldId == oldId && r.newId == newId) {
                    return true;
                }
            }
            return false;
        }

        private void stashHostRestoreUpdateLocked(Host host, int oldId, int newId) {
            ArrayList<RestoreUpdateRecord> r = this.mUpdatesByHost.get(host);
            if (r == null) {
                r = new ArrayList<>();
                this.mUpdatesByHost.put(host, r);
            } else if (alreadyStashed(r, oldId, newId)) {
                Slog.i(TAG, "ID remap " + oldId + " -> " + newId + " already stashed for " + host);
                return;
            }
            r.add(new RestoreUpdateRecord(oldId, newId));
        }

        private void sendWidgetRestoreBroadcastLocked(String action, Provider provider, Host host, int[] oldIds, int[] newIds, UserHandle userHandle) {
            Intent intent = new Intent(action);
            intent.putExtra("appWidgetOldIds", oldIds);
            intent.putExtra("appWidgetIds", newIds);
            if (provider != null) {
                intent.setComponent(provider.info.provider);
                AppWidgetServiceImpl.this.sendBroadcastAsUser(intent, userHandle);
            }
            if (host != null) {
                intent.setComponent(null);
                intent.setPackage(host.id.packageName);
                intent.putExtra("hostId", host.id.hostId);
                AppWidgetServiceImpl.this.sendBroadcastAsUser(intent, userHandle);
            }
        }

        private void pruneWidgetStateLocked(String pkg, int userId) {
            if (!this.mPrunedApps.contains(pkg)) {
                Slog.i(TAG, "pruning widget state for restoring package " + pkg);
                for (int i = AppWidgetServiceImpl.this.mWidgets.size() + (-1); i >= 0; i--) {
                    Widget widget = (Widget) AppWidgetServiceImpl.this.mWidgets.get(i);
                    Host host = widget.host;
                    Provider provider = widget.provider;
                    if (host.hostsPackageForUser(pkg, userId) || (provider != null && provider.isInPackageForUser(pkg, userId))) {
                        host.widgets.remove(widget);
                        provider.widgets.remove(widget);
                        AppWidgetServiceImpl.this.decrementAppWidgetServiceRefCount(widget);
                        AppWidgetServiceImpl.this.removeWidgetLocked(widget);
                    }
                }
                this.mPrunedApps.add(pkg);
                return;
            }
            Slog.i(TAG, "already pruned " + pkg + ", continuing normally");
        }

        private boolean isProviderAndHostInUser(Widget widget, int userId) {
            return widget.host.getUserId() == userId && (widget.provider == null || widget.provider.getUserId() == userId);
        }

        private Bundle parseWidgetIdOptions(XmlPullParser parser) {
            Bundle options = new Bundle();
            String minWidthString = parser.getAttributeValue(null, "min_width");
            if (minWidthString != null) {
                options.putInt("appWidgetMinWidth", Integer.parseInt(minWidthString, 16));
            }
            String minHeightString = parser.getAttributeValue(null, "min_height");
            if (minHeightString != null) {
                options.putInt("appWidgetMinHeight", Integer.parseInt(minHeightString, 16));
            }
            String maxWidthString = parser.getAttributeValue(null, "max_width");
            if (maxWidthString != null) {
                options.putInt("appWidgetMaxWidth", Integer.parseInt(maxWidthString, 16));
            }
            String maxHeightString = parser.getAttributeValue(null, "max_height");
            if (maxHeightString != null) {
                options.putInt("appWidgetMaxHeight", Integer.parseInt(maxHeightString, 16));
            }
            String categoryString = parser.getAttributeValue(null, "host_category");
            if (categoryString != null) {
                options.putInt("appWidgetCategory", Integer.parseInt(categoryString, 16));
            }
            return options;
        }

        private int countPendingUpdates(ArrayList<RestoreUpdateRecord> updates) {
            int pending = 0;
            int N = updates.size();
            for (int i = 0; i < N; i++) {
                RestoreUpdateRecord r = updates.get(i);
                if (!r.notified) {
                    pending++;
                }
            }
            return pending;
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public class RestoreUpdateRecord {
            public int newId;
            public boolean notified = false;
            public int oldId;

            public RestoreUpdateRecord(int theOldId, int theNewId) {
                this.oldId = theOldId;
                this.newId = theNewId;
            }
        }
    }

    /* loaded from: classes.dex */
    private class AppWidgetManagerLocal extends AppWidgetManagerInternal {
        private AppWidgetManagerLocal() {
        }

        public ArraySet<String> getHostedWidgetPackages(int uid) {
            ArraySet<String> widgetPackages;
            synchronized (AppWidgetServiceImpl.this.mLock) {
                widgetPackages = null;
                int widgetCount = AppWidgetServiceImpl.this.mWidgets.size();
                for (int i = 0; i < widgetCount; i++) {
                    Widget widget = (Widget) AppWidgetServiceImpl.this.mWidgets.get(i);
                    if (widget.host.id.uid == uid) {
                        if (widgetPackages == null) {
                            widgetPackages = new ArraySet<>();
                        }
                        widgetPackages.add(widget.provider.id.componentName.getPackageName());
                    }
                }
            }
            return widgetPackages;
        }

        public void unlockUser(int userId) {
            AppWidgetServiceImpl.this.handleUserUnlocked(userId);
        }
    }
}
