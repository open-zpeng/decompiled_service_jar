package com.android.server.autofill;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.service.autofill.FillEventHistory;
import android.service.autofill.UserData;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManagerInternal;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManager;
import android.view.autofill.IAutoFillManagerClient;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.autofill.ui.AutoFillUI;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/* loaded from: classes.dex */
public final class AutofillManagerService extends SystemService {
    private static final char COMPAT_PACKAGE_DELIMITER = ':';
    private static final char COMPAT_PACKAGE_URL_IDS_BLOCK_BEGIN = '[';
    private static final char COMPAT_PACKAGE_URL_IDS_BLOCK_END = ']';
    private static final char COMPAT_PACKAGE_URL_IDS_DELIMITER = ',';
    static final String RECEIVER_BUNDLE_EXTRA_SESSIONS = "sessions";
    private static final String TAG = "AutofillManagerService";
    @GuardedBy("mLock")
    private boolean mAllowInstantService;
    private final AutofillCompatState mAutofillCompatState;
    private final BroadcastReceiver mBroadcastReceiver;
    private final Context mContext;
    @GuardedBy("mLock")
    private final SparseBooleanArray mDisabledUsers;
    private final LocalService mLocalService;
    private final Object mLock;
    private final LocalLog mRequestsHistory;
    @GuardedBy("mLock")
    private SparseArray<AutofillManagerServiceImpl> mServicesCache;
    private final AutoFillUI mUi;
    private final LocalLog mUiLatencyHistory;
    private final LocalLog mWtfHistory;

    public AutofillManagerService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mServicesCache = new SparseArray<>();
        this.mDisabledUsers = new SparseBooleanArray();
        this.mRequestsHistory = new LocalLog(20);
        this.mUiLatencyHistory = new LocalLog(20);
        this.mWtfHistory = new LocalLog(50);
        this.mAutofillCompatState = new AutofillCompatState();
        this.mLocalService = new LocalService();
        this.mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.autofill.AutofillManagerService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.CLOSE_SYSTEM_DIALOGS".equals(intent.getAction())) {
                    if (Helper.sDebug) {
                        Slog.d(AutofillManagerService.TAG, "Close system dialogs");
                    }
                    synchronized (AutofillManagerService.this.mLock) {
                        for (int i = 0; i < AutofillManagerService.this.mServicesCache.size(); i++) {
                            ((AutofillManagerServiceImpl) AutofillManagerService.this.mServicesCache.valueAt(i)).destroyFinishedSessionsLocked();
                        }
                    }
                    AutofillManagerService.this.mUi.hideAll(null);
                }
            }
        };
        this.mContext = context;
        this.mUi = new AutoFillUI(ActivityThread.currentActivityThread().getSystemUiContext());
        boolean debug = Build.IS_DEBUGGABLE;
        Slog.i(TAG, "Setting debug to " + debug);
        setDebugLocked(debug);
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.CLOSE_SYSTEM_DIALOGS");
        this.mContext.registerReceiver(this.mBroadcastReceiver, filter, null, FgThread.getHandler());
        UserManager um = (UserManager) context.getSystemService(UserManager.class);
        UserManagerInternal umi = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
        List<UserInfo> users = um.getUsers();
        for (int i = 0; i < users.size(); i++) {
            int userId = users.get(i).id;
            boolean disabled = umi.getUserRestriction(userId, "no_autofill");
            if (disabled) {
                if (disabled) {
                    Slog.i(TAG, "Disabling Autofill for user " + userId);
                }
                this.mDisabledUsers.put(userId, disabled);
            }
        }
        umi.addUserRestrictionsListener(new UserManagerInternal.UserRestrictionsListener() { // from class: com.android.server.autofill.-$$Lambda$AutofillManagerService$Yt8ZUfnHlFcXzCNLhvGde5dPRDA
            public final void onUserRestrictionsChanged(int i2, Bundle bundle, Bundle bundle2) {
                AutofillManagerService.lambda$new$0(AutofillManagerService.this, i2, bundle, bundle2);
            }
        });
        startTrackingPackageChanges();
    }

    public static /* synthetic */ void lambda$new$0(AutofillManagerService autofillManagerService, int userId, Bundle newRestrictions, Bundle prevRestrictions) {
        boolean disabledNow = newRestrictions.getBoolean("no_autofill", false);
        synchronized (autofillManagerService.mLock) {
            boolean disabledBefore = autofillManagerService.mDisabledUsers.get(userId);
            if (disabledBefore == disabledNow && Helper.sDebug) {
                Slog.d(TAG, "Autofill restriction did not change for user " + userId);
                return;
            }
            Slog.i(TAG, "Updating Autofill for user " + userId + ": disabled=" + disabledNow);
            autofillManagerService.mDisabledUsers.put(userId, disabledNow);
            autofillManagerService.updateCachedServiceLocked(userId, disabledNow);
        }
    }

    private void startTrackingPackageChanges() {
        PackageMonitor monitor = new PackageMonitor() { // from class: com.android.server.autofill.AutofillManagerService.2
            public void onSomePackagesChanged() {
                synchronized (AutofillManagerService.this.mLock) {
                    AutofillManagerService.this.updateCachedServiceLocked(getChangingUserId());
                }
            }

            public void onPackageUpdateFinished(String packageName, int uid) {
                synchronized (AutofillManagerService.this.mLock) {
                    String activePackageName = getActiveAutofillServicePackageName();
                    if (packageName.equals(activePackageName)) {
                        AutofillManagerService.this.removeCachedServiceLocked(getChangingUserId());
                    } else {
                        handlePackageUpdateLocked(packageName);
                    }
                }
            }

            public void onPackageRemoved(String packageName, int uid) {
                ComponentName componentName;
                synchronized (AutofillManagerService.this.mLock) {
                    int userId = getChangingUserId();
                    AutofillManagerServiceImpl userState = AutofillManagerService.this.peekServiceForUserLocked(userId);
                    if (userState != null && (componentName = userState.getServiceComponentName()) != null && packageName.equals(componentName.getPackageName())) {
                        handleActiveAutofillServiceRemoved(userId);
                    }
                }
            }

            public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
                synchronized (AutofillManagerService.this.mLock) {
                    String activePackageName = getActiveAutofillServicePackageName();
                    for (String pkg : packages) {
                        if (pkg.equals(activePackageName)) {
                            if (!doit) {
                                return true;
                            }
                            AutofillManagerService.this.removeCachedServiceLocked(getChangingUserId());
                        } else {
                            handlePackageUpdateLocked(pkg);
                        }
                    }
                    return false;
                }
            }

            private void handleActiveAutofillServiceRemoved(int userId) {
                AutofillManagerService.this.removeCachedServiceLocked(userId);
                Settings.Secure.putStringForUser(AutofillManagerService.this.mContext.getContentResolver(), "autofill_service", null, userId);
            }

            private String getActiveAutofillServicePackageName() {
                ComponentName serviceComponent;
                int userId = getChangingUserId();
                AutofillManagerServiceImpl userState = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (userState == null || (serviceComponent = userState.getServiceComponentName()) == null) {
                    return null;
                }
                return serviceComponent.getPackageName();
            }

            @GuardedBy("mLock")
            private void handlePackageUpdateLocked(String packageName) {
                int size = AutofillManagerService.this.mServicesCache.size();
                for (int i = 0; i < size; i++) {
                    ((AutofillManagerServiceImpl) AutofillManagerService.this.mServicesCache.valueAt(i)).handlePackageUpdateLocked(packageName);
                }
            }
        };
        monitor.register(this.mContext, (Looper) null, UserHandle.ALL, true);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("autofill", new AutoFillManagerServiceStub());
        publishLocalService(AutofillManagerInternal.class, this.mLocalService);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 600) {
            new SettingsObserver(BackgroundThread.getHandler());
        }
    }

    @Override // com.android.server.SystemService
    public void onUnlockUser(int userId) {
        synchronized (this.mLock) {
            updateCachedServiceLocked(userId);
        }
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userHandle) {
        if (Helper.sDebug) {
            Slog.d(TAG, "Hiding UI when user switched");
        }
        this.mUi.hideAll(null);
    }

    @Override // com.android.server.SystemService
    public void onCleanupUser(int userId) {
        synchronized (this.mLock) {
            removeCachedServiceLocked(userId);
        }
    }

    @GuardedBy("mLock")
    AutofillManagerServiceImpl getServiceForUserLocked(int userId) {
        int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, null, null);
        AutofillManagerServiceImpl service = this.mServicesCache.get(resolvedUserId);
        if (service == null) {
            AutofillManagerServiceImpl service2 = new AutofillManagerServiceImpl(this.mContext, this.mLock, this.mRequestsHistory, this.mUiLatencyHistory, this.mWtfHistory, resolvedUserId, this.mUi, this.mAutofillCompatState, this.mDisabledUsers.get(resolvedUserId));
            this.mServicesCache.put(userId, service2);
            addCompatibilityModeRequestsLocked(service2, userId);
            return service2;
        }
        return service;
    }

    @GuardedBy("mLock")
    AutofillManagerServiceImpl peekServiceForUserLocked(int userId) {
        int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, null, null);
        return this.mServicesCache.get(resolvedUserId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroySessions(int userId, IResultReceiver receiver) {
        Slog.i(TAG, "destroySessions() for userId " + userId);
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        synchronized (this.mLock) {
            try {
                if (userId != -1) {
                    AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                    if (service != null) {
                        service.destroySessionsLocked();
                    }
                } else {
                    int size = this.mServicesCache.size();
                    for (int i = 0; i < size; i++) {
                        this.mServicesCache.valueAt(i).destroySessionsLocked();
                    }
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        try {
            receiver.send(0, new Bundle());
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void listSessions(int userId, IResultReceiver receiver) {
        Slog.i(TAG, "listSessions() for userId " + userId);
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        Bundle resultData = new Bundle();
        ArrayList<String> sessions = new ArrayList<>();
        synchronized (this.mLock) {
            try {
                if (userId != -1) {
                    AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
                    if (service != null) {
                        service.listSessionsLocked(sessions);
                    }
                } else {
                    int size = this.mServicesCache.size();
                    for (int i = 0; i < size; i++) {
                        this.mServicesCache.valueAt(i).listSessionsLocked(sessions);
                    }
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        resultData.putStringArrayList(RECEIVER_BUNDLE_EXTRA_SESSIONS, sessions);
        try {
            receiver.send(0, resultData);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reset() {
        Slog.i(TAG, "reset()");
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        synchronized (this.mLock) {
            int size = this.mServicesCache.size();
            for (int i = 0; i < size; i++) {
                this.mServicesCache.valueAt(i).destroyLocked();
            }
            this.mServicesCache.clear();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLogLevel(int level) {
        Slog.i(TAG, "setLogLevel(): " + level);
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        boolean debug = false;
        boolean verbose = false;
        if (level == 4) {
            verbose = true;
            debug = true;
        } else if (level == 2) {
            debug = true;
        }
        synchronized (this.mLock) {
            setDebugLocked(debug);
            setVerboseLocked(verbose);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLogLevel() {
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        synchronized (this.mLock) {
            if (Helper.sVerbose) {
                return 4;
            }
            return Helper.sDebug ? 2 : 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getMaxPartitions() {
        int i;
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        synchronized (this.mLock) {
            i = Helper.sPartitionMaxCount;
        }
        return i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setMaxPartitions(int max) {
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        Slog.i(TAG, "setMaxPartitions(): " + max);
        synchronized (this.mLock) {
            Helper.sPartitionMaxCount = max;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getMaxVisibleDatasets() {
        int i;
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        synchronized (this.mLock) {
            i = Helper.sVisibleDatasetsMaxCount;
        }
        return i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setMaxVisibleDatasets(int max) {
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        Slog.i(TAG, "setMaxVisibleDatasets(): " + max);
        synchronized (this.mLock) {
            Helper.sVisibleDatasetsMaxCount = max;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getScore(String algorithmName, String value1, String value2, RemoteCallback callback) {
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        FieldClassificationStrategy strategy = new FieldClassificationStrategy(this.mContext, -2);
        strategy.getScores(callback, algorithmName, null, Arrays.asList(AutofillValue.forText(value1)), new String[]{value2});
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Boolean getFullScreenMode() {
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        return Helper.sFullScreenMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFullScreenMode(Boolean mode) {
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        Helper.sFullScreenMode = mode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getAllowInstantService() {
        boolean z;
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        synchronized (this.mLock) {
            z = this.mAllowInstantService;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAllowInstantService(boolean mode) {
        this.mContext.enforceCallingPermission("android.permission.MANAGE_AUTO_FILL", TAG);
        Slog.i(TAG, "setAllowInstantService(): " + mode);
        synchronized (this.mLock) {
            this.mAllowInstantService = mode;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setDebugLocked(boolean debug) {
        Helper.sDebug = debug;
        android.view.autofill.Helper.sDebug = debug;
    }

    private void setVerboseLocked(boolean verbose) {
        Helper.sVerbose = verbose;
        android.view.autofill.Helper.sVerbose = verbose;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mLock")
    public void removeCachedServiceLocked(int userId) {
        AutofillManagerServiceImpl service = peekServiceForUserLocked(userId);
        if (service != null) {
            this.mServicesCache.delete(userId);
            service.destroyLocked();
            this.mAutofillCompatState.removeCompatibilityModeRequests(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mLock")
    public void updateCachedServiceLocked(int userId) {
        updateCachedServiceLocked(userId, this.mDisabledUsers.get(userId));
    }

    @GuardedBy("mLock")
    private void updateCachedServiceLocked(int userId, boolean disabled) {
        AutofillManagerServiceImpl service = getServiceForUserLocked(userId);
        if (service != null) {
            service.destroySessionsLocked();
            service.updateLocked(disabled);
            if (!service.isEnabledLocked()) {
                removeCachedServiceLocked(userId);
            } else {
                addCompatibilityModeRequestsLocked(service, userId);
            }
        }
    }

    private void addCompatibilityModeRequestsLocked(AutofillManagerServiceImpl service, int userId) {
        this.mAutofillCompatState.reset(userId);
        ArrayMap<String, Long> compatPackages = service.getCompatibilityPackagesLocked();
        if (compatPackages == null || compatPackages.isEmpty()) {
            return;
        }
        Map<String, String[]> whiteListedPackages = getWhitelistedCompatModePackages();
        int compatPackageCount = compatPackages.size();
        for (int i = 0; i < compatPackageCount; i++) {
            String packageName = compatPackages.keyAt(i);
            if (whiteListedPackages == null || !whiteListedPackages.containsKey(packageName)) {
                Slog.w(TAG, "Ignoring not whitelisted compat package " + packageName);
            } else {
                Long maxVersionCode = compatPackages.valueAt(i);
                if (maxVersionCode != null) {
                    this.mAutofillCompatState.addCompatibilityModeRequest(packageName, maxVersionCode.longValue(), whiteListedPackages.get(packageName), userId);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getWhitelistedCompatModePackagesFromSettings() {
        return Settings.Global.getString(this.mContext.getContentResolver(), "autofill_compat_mode_allowed_packages");
    }

    private Map<String, String[]> getWhitelistedCompatModePackages() {
        return getWhitelistedCompatModePackages(getWhitelistedCompatModePackagesFromSettings());
    }

    @VisibleForTesting
    static Map<String, String[]> getWhitelistedCompatModePackages(String setting) {
        String packageName;
        List<String> urlBarIds;
        if (TextUtils.isEmpty(setting)) {
            return null;
        }
        ArrayMap<String, String[]> compatPackages = new ArrayMap<>();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(COMPAT_PACKAGE_DELIMITER);
        splitter.setString(setting);
        while (splitter.hasNext()) {
            String packageBlock = splitter.next();
            int urlBlockIndex = packageBlock.indexOf(91);
            if (urlBlockIndex == -1) {
                packageName = packageBlock;
                urlBarIds = null;
            } else if (packageBlock.charAt(packageBlock.length() - 1) != ']') {
                Slog.w(TAG, "Ignoring entry '" + packageBlock + "' on '" + setting + "'because it does not end on '" + COMPAT_PACKAGE_URL_IDS_BLOCK_END + "'");
            } else {
                packageName = packageBlock.substring(0, urlBlockIndex);
                urlBarIds = new ArrayList<>();
                String urlBarIdsBlock = packageBlock.substring(urlBlockIndex + 1, packageBlock.length() - 1);
                if (Helper.sVerbose) {
                    Slog.v(TAG, "pkg:" + packageName + ": block:" + packageBlock + ": urls:" + urlBarIds + ": block:" + urlBarIdsBlock + ":");
                }
                TextUtils.SimpleStringSplitter splitter2 = new TextUtils.SimpleStringSplitter(COMPAT_PACKAGE_URL_IDS_DELIMITER);
                splitter2.setString(urlBarIdsBlock);
                while (splitter2.hasNext()) {
                    String urlBarId = splitter2.next();
                    urlBarIds.add(urlBarId);
                }
            }
            if (urlBarIds == null) {
                compatPackages.put(packageName, null);
            } else {
                String[] urlBarIdsArray = new String[urlBarIds.size()];
                urlBarIds.toArray(urlBarIdsArray);
                compatPackages.put(packageName, urlBarIdsArray);
            }
        }
        return compatPackages;
    }

    /* loaded from: classes.dex */
    private final class LocalService extends AutofillManagerInternal {
        private LocalService() {
        }

        public void onBackKeyPressed() {
            if (Helper.sDebug) {
                Slog.d(AutofillManagerService.TAG, "onBackKeyPressed()");
            }
            AutofillManagerService.this.mUi.hideAll(null);
        }

        public boolean isCompatibilityModeRequested(String packageName, long versionCode, int userId) {
            return AutofillManagerService.this.mAutofillCompatState.isCompatibilityModeRequested(packageName, versionCode, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class PackageCompatState {
        private final long maxVersionCode;
        private final String[] urlBarResourceIds;

        PackageCompatState(long maxVersionCode, String[] urlBarResourceIds) {
            this.maxVersionCode = maxVersionCode;
            this.urlBarResourceIds = urlBarResourceIds;
        }

        public String toString() {
            return "maxVersionCode=" + this.maxVersionCode + ", urlBarResourceIds=" + Arrays.toString(this.urlBarResourceIds);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class AutofillCompatState {
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private SparseArray<ArrayMap<String, PackageCompatState>> mUserSpecs;

        AutofillCompatState() {
        }

        boolean isCompatibilityModeRequested(String packageName, long versionCode, int userId) {
            synchronized (this.mLock) {
                if (this.mUserSpecs == null) {
                    return false;
                }
                ArrayMap<String, PackageCompatState> userSpec = this.mUserSpecs.get(userId);
                if (userSpec == null) {
                    return false;
                }
                PackageCompatState metadata = userSpec.get(packageName);
                if (metadata == null) {
                    return false;
                }
                return versionCode <= metadata.maxVersionCode;
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public String[] getUrlBarResourceIds(String packageName, int userId) {
            synchronized (this.mLock) {
                if (this.mUserSpecs == null) {
                    return null;
                }
                ArrayMap<String, PackageCompatState> userSpec = this.mUserSpecs.get(userId);
                if (userSpec == null) {
                    return null;
                }
                PackageCompatState metadata = userSpec.get(packageName);
                if (metadata == null) {
                    return null;
                }
                return metadata.urlBarResourceIds;
            }
        }

        void addCompatibilityModeRequest(String packageName, long versionCode, String[] urlBarResourceIds, int userId) {
            synchronized (this.mLock) {
                if (this.mUserSpecs == null) {
                    this.mUserSpecs = new SparseArray<>();
                }
                ArrayMap<String, PackageCompatState> userSpec = this.mUserSpecs.get(userId);
                if (userSpec == null) {
                    userSpec = new ArrayMap<>();
                    this.mUserSpecs.put(userId, userSpec);
                }
                userSpec.put(packageName, new PackageCompatState(versionCode, urlBarResourceIds));
            }
        }

        void removeCompatibilityModeRequests(int userId) {
            synchronized (this.mLock) {
                if (this.mUserSpecs != null) {
                    this.mUserSpecs.remove(userId);
                    if (this.mUserSpecs.size() <= 0) {
                        this.mUserSpecs = null;
                    }
                }
            }
        }

        void reset(int userId) {
            synchronized (this.mLock) {
                if (this.mUserSpecs != null) {
                    this.mUserSpecs.delete(userId);
                    int newSize = this.mUserSpecs.size();
                    if (newSize == 0) {
                        if (Helper.sVerbose) {
                            Slog.v(AutofillManagerService.TAG, "reseting mUserSpecs");
                        }
                        this.mUserSpecs = null;
                    } else if (Helper.sVerbose) {
                        Slog.v(AutofillManagerService.TAG, "mUserSpecs down to " + newSize);
                    }
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dump(String prefix, PrintWriter pw) {
            if (this.mUserSpecs == null) {
                pw.println("N/A");
                return;
            }
            pw.println();
            String prefix2 = prefix + "  ";
            for (int i = 0; i < this.mUserSpecs.size(); i++) {
                int user = this.mUserSpecs.keyAt(i);
                pw.print(prefix);
                pw.print("User: ");
                pw.println(user);
                ArrayMap<String, PackageCompatState> perUser = this.mUserSpecs.valueAt(i);
                for (int j = 0; j < perUser.size(); j++) {
                    String packageName = perUser.keyAt(j);
                    PackageCompatState state = perUser.valueAt(j);
                    pw.print(prefix2);
                    pw.print(packageName);
                    pw.print(": ");
                    pw.println(state);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    final class AutoFillManagerServiceStub extends IAutoFillManager.Stub {
        AutoFillManagerServiceStub() {
        }

        public int addClient(IAutoFillManagerClient client, int userId) {
            int flags;
            synchronized (AutofillManagerService.this.mLock) {
                flags = 0;
                if (AutofillManagerService.this.getServiceForUserLocked(userId).addClientLocked(client)) {
                    flags = 0 | 1;
                }
                if (Helper.sDebug) {
                    flags |= 2;
                }
                if (Helper.sVerbose) {
                    flags |= 4;
                }
            }
            return flags;
        }

        public void removeClient(IAutoFillManagerClient client, int userId) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    service.removeClientLocked(client);
                } else if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "removeClient(): no service for " + userId);
                }
            }
        }

        public void setAuthenticationResult(Bundle data, int sessionId, int authenticationId, int userId) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.getServiceForUserLocked(userId);
                service.setAuthenticationResultLocked(data, sessionId, authenticationId, getCallingUid());
            }
        }

        public void setHasCallback(int sessionId, int userId, boolean hasIt) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.getServiceForUserLocked(userId);
                service.setHasCallback(sessionId, getCallingUid(), hasIt);
            }
        }

        public int startSession(IBinder activityToken, IBinder appCallback, AutofillId autofillId, Rect bounds, AutofillValue value, int userId, boolean hasCallback, int flags, ComponentName componentName, boolean compatMode) {
            IBinder activityToken2 = (IBinder) Preconditions.checkNotNull(activityToken, "activityToken");
            IBinder appCallback2 = (IBinder) Preconditions.checkNotNull(appCallback, "appCallback");
            AutofillId autofillId2 = (AutofillId) Preconditions.checkNotNull(autofillId, "autoFillId");
            ComponentName componentName2 = (ComponentName) Preconditions.checkNotNull(componentName, "componentName");
            String packageName = (String) Preconditions.checkNotNull(componentName2.getPackageName());
            Preconditions.checkArgument(userId == UserHandle.getUserId(getCallingUid()), "userId");
            try {
                AutofillManagerService.this.mContext.getPackageManager().getPackageInfoAsUser(packageName, 0, userId);
                synchronized (AutofillManagerService.this.mLock) {
                    try {
                        try {
                            AutofillManagerServiceImpl service = AutofillManagerService.this.getServiceForUserLocked(userId);
                            return service.startSessionLocked(activityToken2, getCallingUid(), appCallback2, autofillId2, bounds, value, hasCallback, componentName2, compatMode, AutofillManagerService.this.mAllowInstantService, flags);
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException(packageName + " is not a valid package", e);
            }
        }

        public FillEventHistory getFillEventHistory() throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.getFillEventHistory(getCallingUid());
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "getFillEventHistory(): no service for " + userId);
                }
                return null;
            }
        }

        public UserData getUserData() throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.getUserData(getCallingUid());
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "getUserData(): no service for " + userId);
                }
                return null;
            }
        }

        public String getUserDataId() throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                String str = null;
                if (service != null) {
                    UserData userData = service.getUserData(getCallingUid());
                    if (userData != null) {
                        str = userData.getId();
                    }
                    return str;
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "getUserDataId(): no service for " + userId);
                }
                return null;
            }
        }

        public void setUserData(UserData userData) throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    service.setUserData(getCallingUid(), userData);
                } else if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "setUserData(): no service for " + userId);
                }
            }
        }

        public boolean isFieldClassificationEnabled() throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.isFieldClassificationEnabled(getCallingUid());
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "isFieldClassificationEnabled(): no service for " + userId);
                }
                return false;
            }
        }

        public String getDefaultFieldClassificationAlgorithm() throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.getDefaultFieldClassificationAlgorithm(getCallingUid());
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "getDefaultFcAlgorithm(): no service for " + userId);
                }
                return null;
            }
        }

        public String[] getAvailableFieldClassificationAlgorithms() throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.getAvailableFieldClassificationAlgorithms(getCallingUid());
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "getAvailableFcAlgorithms(): no service for " + userId);
                }
                return null;
            }
        }

        public ComponentName getAutofillServiceComponentName() throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    return service.getServiceComponentName();
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "getAutofillServiceComponentName(): no service for " + userId);
                }
                return null;
            }
        }

        public boolean restoreSession(int sessionId, IBinder activityToken, IBinder appCallback) throws RemoteException {
            int userId = UserHandle.getCallingUserId();
            IBinder activityToken2 = (IBinder) Preconditions.checkNotNull(activityToken, "activityToken");
            IBinder appCallback2 = (IBinder) Preconditions.checkNotNull(appCallback, "appCallback");
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = (AutofillManagerServiceImpl) AutofillManagerService.this.mServicesCache.get(userId);
                if (service != null) {
                    return service.restoreSession(sessionId, getCallingUid(), activityToken2, appCallback2);
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "restoreSession(): no service for " + userId);
                }
                return false;
            }
        }

        public void updateSession(int sessionId, AutofillId autoFillId, Rect bounds, AutofillValue value, int action, int flags, int userId) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    service.updateSessionLocked(sessionId, getCallingUid(), autoFillId, bounds, value, action, flags);
                } else if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "updateSession(): no service for " + userId);
                }
            }
        }

        /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:18:0x0066 -> B:19:0x0067). Please submit an issue!!! */
        public int updateOrRestartSession(IBinder activityToken, IBinder appCallback, AutofillId autoFillId, Rect bounds, AutofillValue value, int userId, boolean hasCallback, int flags, ComponentName componentName, int sessionId, int action, boolean compatMode) {
            boolean restart = false;
            synchronized (AutofillManagerService.this.mLock) {
                try {
                    AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                    if (service != null) {
                        restart = service.updateSessionLocked(sessionId, getCallingUid(), autoFillId, bounds, value, action, flags);
                    } else if (Helper.sVerbose) {
                        Slog.v(AutofillManagerService.TAG, "updateOrRestartSession(): no service for " + userId);
                    }
                    boolean restart2 = restart;
                    try {
                        if (restart2) {
                            return startSession(activityToken, appCallback, autoFillId, bounds, value, userId, hasCallback, flags, componentName, compatMode);
                        }
                        return sessionId;
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            }
        }

        public void setAutofillFailure(int sessionId, List<AutofillId> ids, int userId) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    service.setAutofillFailureLocked(sessionId, getCallingUid(), ids);
                } else if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "setAutofillFailure(): no service for " + userId);
                }
            }
        }

        public void finishSession(int sessionId, int userId) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    service.finishSessionLocked(sessionId, getCallingUid());
                } else if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "finishSession(): no service for " + userId);
                }
            }
        }

        public void cancelSession(int sessionId, int userId) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    service.cancelSessionLocked(sessionId, getCallingUid());
                } else if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "cancelSession(): no service for " + userId);
                }
            }
        }

        public void disableOwnedAutofillServices(int userId) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    service.disableOwnedAutofillServicesLocked(Binder.getCallingUid());
                } else if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "cancelSession(): no service for " + userId);
                }
            }
        }

        public boolean isServiceSupported(int userId) {
            boolean z;
            synchronized (AutofillManagerService.this.mLock) {
                z = !AutofillManagerService.this.mDisabledUsers.get(userId);
            }
            return z;
        }

        public boolean isServiceEnabled(int userId, String packageName) {
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(userId);
                if (service != null) {
                    return Objects.equals(packageName, service.getServicePackageName());
                }
                if (Helper.sVerbose) {
                    Slog.v(AutofillManagerService.TAG, "isServiceEnabled(): no service for " + userId);
                }
                return false;
            }
        }

        public void onPendingSaveUi(int operation, IBinder token) {
            Preconditions.checkNotNull(token, "token");
            Preconditions.checkArgument(operation == 1 || operation == 2, "invalid operation: %d", new Object[]{Integer.valueOf(operation)});
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerServiceImpl service = AutofillManagerService.this.peekServiceForUserLocked(UserHandle.getCallingUserId());
                if (service != null) {
                    service.onPendingSaveUi(operation, token);
                }
            }
        }

        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (DumpUtils.checkDumpPermission(AutofillManagerService.this.mContext, AutofillManagerService.TAG, pw)) {
                boolean showHistory = true;
                boolean uiOnly = false;
                if (args != null) {
                    boolean showHistory2 = true;
                    for (String arg : args) {
                        char c = 65535;
                        int hashCode = arg.hashCode();
                        if (hashCode != 900765093) {
                            if (hashCode != 1098711592) {
                                if (hashCode == 1333069025 && arg.equals("--help")) {
                                    c = 2;
                                }
                            } else if (arg.equals("--no-history")) {
                                c = 0;
                            }
                        } else if (arg.equals("--ui-only")) {
                            c = 1;
                        }
                        switch (c) {
                            case 0:
                                showHistory2 = false;
                                break;
                            case 1:
                                uiOnly = true;
                                break;
                            case 2:
                                pw.println("Usage: dumpsys autofill [--ui-only|--no-history]");
                                return;
                            default:
                                Slog.w(AutofillManagerService.TAG, "Ignoring invalid dump arg: " + arg);
                                break;
                        }
                    }
                    showHistory = showHistory2;
                }
                if (uiOnly) {
                    AutofillManagerService.this.mUi.dump(pw);
                    return;
                }
                boolean oldDebug = Helper.sDebug;
                try {
                    synchronized (AutofillManagerService.this.mLock) {
                        oldDebug = Helper.sDebug;
                        AutofillManagerService.this.setDebugLocked(true);
                        pw.print("Debug mode: ");
                        pw.println(oldDebug);
                        pw.print("Verbose mode: ");
                        pw.println(Helper.sVerbose);
                        pw.print("Disabled users: ");
                        pw.println(AutofillManagerService.this.mDisabledUsers);
                        pw.print("Max partitions per session: ");
                        pw.println(Helper.sPartitionMaxCount);
                        pw.print("Max visible datasets: ");
                        pw.println(Helper.sVisibleDatasetsMaxCount);
                        if (Helper.sFullScreenMode != null) {
                            pw.print("Overridden full-screen mode: ");
                            pw.println(Helper.sFullScreenMode);
                        }
                        pw.println("User data constraints: ");
                        UserData.dumpConstraints("  ", pw);
                        int size = AutofillManagerService.this.mServicesCache.size();
                        pw.print("Cached services: ");
                        if (size == 0) {
                            pw.println("none");
                        } else {
                            pw.println(size);
                            for (int i = 0; i < size; i++) {
                                pw.print("\nService at index ");
                                pw.println(i);
                                AutofillManagerServiceImpl impl = (AutofillManagerServiceImpl) AutofillManagerService.this.mServicesCache.valueAt(i);
                                impl.dumpLocked("  ", pw);
                            }
                        }
                        AutofillManagerService.this.mUi.dump(pw);
                        pw.print("Autofill Compat State: ");
                        AutofillManagerService.this.mAutofillCompatState.dump("    ", pw);
                        pw.print("    ");
                        pw.print("from settings: ");
                        pw.println(AutofillManagerService.this.getWhitelistedCompatModePackagesFromSettings());
                        pw.print("Allow instant service: ");
                        pw.println(AutofillManagerService.this.mAllowInstantService);
                    }
                    if (showHistory) {
                        pw.println();
                        pw.println("Requests history:");
                        pw.println();
                        AutofillManagerService.this.mRequestsHistory.reverseDump(fd, pw, args);
                        pw.println();
                        pw.println("UI latency history:");
                        pw.println();
                        AutofillManagerService.this.mUiLatencyHistory.reverseDump(fd, pw, args);
                        pw.println();
                        pw.println("WTF history:");
                        pw.println();
                        AutofillManagerService.this.mWtfHistory.reverseDump(fd, pw, args);
                    }
                } finally {
                    AutofillManagerService.this.setDebugLocked(oldDebug);
                }
            }
        }

        /* JADX WARN: Multi-variable type inference failed */
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new AutofillManagerServiceShellCommand(AutofillManagerService.this).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    /* loaded from: classes.dex */
    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = AutofillManagerService.this.mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor("autofill_service"), false, this, -1);
            resolver.registerContentObserver(Settings.Secure.getUriFor("user_setup_complete"), false, this, -1);
            resolver.registerContentObserver(Settings.Global.getUriFor("autofill_compat_mode_allowed_packages"), false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (Helper.sVerbose) {
                Slog.v(AutofillManagerService.TAG, "onChange(): uri=" + uri + ", userId=" + userId);
            }
            synchronized (AutofillManagerService.this.mLock) {
                AutofillManagerService.this.updateCachedServiceLocked(userId);
            }
        }
    }
}
