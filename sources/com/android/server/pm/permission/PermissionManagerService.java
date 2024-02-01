package com.android.server.pm.permission;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManager;
import android.permission.PermissionManagerInternal;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.permission.PermissionsState;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.policy.SoftRestrictedPermissionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import libcore.util.EmptyArray;

/* loaded from: classes.dex */
public class PermissionManagerService {
    private static final int BLOCKING_PERMISSION_FLAGS = 52;
    private static final int GRANT_DENIED = 1;
    private static final int GRANT_INSTALL = 2;
    private static final int GRANT_RUNTIME = 3;
    private static final int GRANT_UPGRADE = 4;
    private static final int MAX_PERMISSION_TREE_FOOTPRINT = 32768;
    private static final int PERMISSION_DEFAULT = 0;
    private static final int PERMISSION_DENIED_AND_STOP_CHECK = 2;
    private static final int PERMISSION_GRANTED = 1;
    private static final String TAG = "PackageManager";
    private static final int UPDATE_PERMISSIONS_ALL = 1;
    private static final int UPDATE_PERMISSIONS_REPLACE_ALL = 4;
    private static final int UPDATE_PERMISSIONS_REPLACE_PKG = 2;
    private static final int USER_PERMISSION_FLAGS = 3;
    @GuardedBy({"mLock"})
    private ArrayMap<String, List<String>> mBackgroundPermissions;
    private final Context mContext;
    private final DefaultPermissionGrantPolicy mDefaultPermissionGrantPolicy;
    private final int[] mGlobalGids;
    private final Handler mHandler;
    private final Object mLock;
    private PermissionControllerManager mPermissionControllerManager;
    private PermissionManagerPolicy mPermissionPolicy;
    @GuardedBy({"mLock"})
    private PermissionPolicyInternal mPermissionPolicyInternal;
    @GuardedBy({"mLock"})
    private ArraySet<String> mPrivappPermissionsViolations;
    @GuardedBy({"mLock"})
    private final PermissionSettings mSettings;
    private final SparseArray<ArraySet<String>> mSystemPermissions;
    @GuardedBy({"mLock"})
    private boolean mSystemReady;
    private static final long BACKUP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final List<String> STORAGE_PERMISSIONS = new ArrayList();
    private static final Map<String, String> FULLER_PERMISSION_MAP = new HashMap();
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    @GuardedBy({"mLock"})
    private final SparseBooleanArray mHasNoDelayedPermBackup = new SparseBooleanArray();
    @GuardedBy({"mLock"})
    private final ArrayList<PermissionManagerInternal.OnRuntimePermissionStateChangedListener> mRuntimePermissionStateChangedListeners = new ArrayList<>();
    private final PackageManagerInternal mPackageManagerInt = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
    private final UserManagerInternal mUserManagerInt = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
    private final HandlerThread mHandlerThread = new ServiceThread(TAG, 10, true);

    static {
        FULLER_PERMISSION_MAP.put("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION");
        FULLER_PERMISSION_MAP.put("android.permission.INTERACT_ACROSS_USERS", "android.permission.INTERACT_ACROSS_USERS_FULL");
        STORAGE_PERMISSIONS.add("android.permission.READ_EXTERNAL_STORAGE");
        STORAGE_PERMISSIONS.add("android.permission.WRITE_EXTERNAL_STORAGE");
        STORAGE_PERMISSIONS.add("android.permission.ACCESS_MEDIA_LOCATION");
    }

    PermissionManagerService(Context context, Object externalLock) {
        this.mContext = context;
        this.mLock = externalLock;
        this.mSettings = new PermissionSettings(this.mLock);
        this.mHandlerThread.start();
        this.mHandler = new Handler(this.mHandlerThread.getLooper());
        Watchdog.getInstance().addThread(this.mHandler);
        this.mDefaultPermissionGrantPolicy = new DefaultPermissionGrantPolicy(context, this.mHandlerThread.getLooper(), this);
        SystemConfig systemConfig = SystemConfig.getInstance();
        this.mSystemPermissions = systemConfig.getSystemPermissions();
        this.mGlobalGids = systemConfig.getGlobalGids();
        ArrayMap<String, SystemConfig.PermissionEntry> permConfig = SystemConfig.getInstance().getPermissions();
        synchronized (this.mLock) {
            for (int i = 0; i < permConfig.size(); i++) {
                SystemConfig.PermissionEntry perm = permConfig.valueAt(i);
                BasePermission bp = this.mSettings.getPermissionLocked(perm.name);
                if (bp == null) {
                    bp = new BasePermission(perm.name, PackageManagerService.PLATFORM_PACKAGE_NAME, 1);
                    this.mSettings.putPermissionLocked(perm.name, bp);
                }
                if (perm.gids != null) {
                    bp.setGids(perm.gids, perm.perUser);
                }
            }
        }
        PermissionManagerServiceInternalImpl localService = new PermissionManagerServiceInternalImpl();
        LocalServices.addService(PermissionManagerServiceInternal.class, localService);
        LocalServices.addService(PermissionManagerInternal.class, localService);
        this.mPermissionPolicy = new PermissionManagerPolicy(this.mContext, this.mHandler);
    }

    public static PermissionManagerServiceInternal create(Context context, Object externalLock) {
        PermissionManagerServiceInternal permMgrInt = (PermissionManagerServiceInternal) LocalServices.getService(PermissionManagerServiceInternal.class);
        if (permMgrInt != null) {
            return permMgrInt;
        }
        new PermissionManagerService(context, externalLock);
        return (PermissionManagerServiceInternal) LocalServices.getService(PermissionManagerServiceInternal.class);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BasePermission getPermission(String permName) {
        BasePermission permissionLocked;
        synchronized (this.mLock) {
            permissionLocked = this.mSettings.getPermissionLocked(permName);
        }
        return permissionLocked;
    }

    private int checkPermissionPolicy(String permName, PackageParser.Package pkg) {
        PermissionManagerPolicy permissionManagerPolicy;
        if (pkg != null && (permissionManagerPolicy = this.mPermissionPolicy) != null) {
            return permissionManagerPolicy.enforceGrantPermission(pkg.packageName, permName);
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkPermission(String permName, String pkgName, int callingUid, int userId) {
        return checkPermission(permName, pkgName, callingUid, userId, true);
    }

    private int checkPermission(String permName, String pkgName, int callingUid, int userId, boolean checkPolicy) {
        if (this.mUserManagerInt.exists(userId)) {
            PackageParser.Package pkg = this.mPackageManagerInt.getPackage(pkgName);
            if (checkPolicy) {
                int result = checkPermissionPolicy(permName, pkg);
                Log.i(TAG, "checkPermissionPolicy result is " + result + "permName:" + permName + "pkgName:" + pkgName);
                if (result != 0) {
                    if (result == 1) {
                        return 0;
                    }
                    if (result == 2) {
                        return 2;
                    }
                }
            }
            if (pkg == null || pkg.mExtras == null || this.mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
                return -1;
            }
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            boolean instantApp = ps.getInstantApp(userId);
            PermissionsState permissionsState = ps.getPermissionsState();
            if (permissionsState.hasPermission(permName, userId)) {
                if (!instantApp) {
                    return 0;
                }
                synchronized (this.mLock) {
                    BasePermission bp = this.mSettings.getPermissionLocked(permName);
                    if (bp != null && bp.isInstant()) {
                        return 0;
                    }
                }
            }
            return isImpliedPermissionGranted(permissionsState, permName, userId) ? 0 : -1;
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkUidPermission(String permName, PackageParser.Package pkg, int uid, int callingUid) {
        int callingUserId = UserHandle.getUserId(callingUid);
        boolean isCallerInstantApp = this.mPackageManagerInt.getInstantAppPackageName(callingUid) != null;
        boolean isUidInstantApp = this.mPackageManagerInt.getInstantAppPackageName(uid) != null;
        int userId = UserHandle.getUserId(uid);
        if (this.mUserManagerInt.exists(userId)) {
            if (checkPermissionPolicy(permName, pkg) == 1) {
                return 0;
            }
            if (pkg != null) {
                if (pkg.mSharedUserId != null) {
                    if (isCallerInstantApp) {
                        return -1;
                    }
                } else if (this.mPackageManagerInt.filterAppAccess(pkg, callingUid, callingUserId)) {
                    return -1;
                }
                PermissionsState permissionsState = ((PackageSetting) pkg.mExtras).getPermissionsState();
                if ((permissionsState.hasPermission(permName, userId) && (!isUidInstantApp || this.mSettings.isPermissionInstant(permName))) || isImpliedPermissionGranted(permissionsState, permName, userId)) {
                    return 0;
                }
            } else {
                ArraySet<String> perms = this.mSystemPermissions.get(uid);
                if (perms != null) {
                    if (perms.contains(permName)) {
                        return 0;
                    }
                    if (FULLER_PERMISSION_MAP.containsKey(permName) && perms.contains(FULLER_PERMISSION_MAP.get(permName))) {
                        return 0;
                    }
                }
            }
            return -1;
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public byte[] backupRuntimePermissions(UserHandle user) {
        final CompletableFuture<byte[]> backup = new CompletableFuture<>();
        PermissionControllerManager permissionControllerManager = this.mPermissionControllerManager;
        Executor mainExecutor = this.mContext.getMainExecutor();
        Objects.requireNonNull(backup);
        permissionControllerManager.getRuntimePermissionBackup(user, mainExecutor, new PermissionControllerManager.OnGetRuntimePermissionBackupCallback() { // from class: com.android.server.pm.permission.-$$Lambda$js2BSmz1ucAEj8fgl3jw5trxIjw
            public final void onGetRuntimePermissionsBackup(byte[] bArr) {
                backup.complete(bArr);
            }
        });
        try {
            return backup.get(BACKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Slog.e(TAG, "Cannot create permission backup for " + user, e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void restoreRuntimePermissions(byte[] backup, UserHandle user) {
        synchronized (this.mLock) {
            this.mHasNoDelayedPermBackup.delete(user.getIdentifier());
            this.mPermissionControllerManager.restoreRuntimePermissionBackup(backup, user);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void restoreDelayedRuntimePermissions(String packageName, final UserHandle user) {
        synchronized (this.mLock) {
            if (this.mHasNoDelayedPermBackup.get(user.getIdentifier(), false)) {
                return;
            }
            this.mPermissionControllerManager.restoreDelayedRuntimePermissionBackup(packageName, user, this.mContext.getMainExecutor(), new Consumer() { // from class: com.android.server.pm.permission.-$$Lambda$PermissionManagerService$KZ0-FIR02GsOfMAAOdWzIbkVHHM
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    PermissionManagerService.this.lambda$restoreDelayedRuntimePermissions$0$PermissionManagerService(user, (Boolean) obj);
                }
            });
        }
    }

    public /* synthetic */ void lambda$restoreDelayedRuntimePermissions$0$PermissionManagerService(UserHandle user, Boolean hasMoreBackup) {
        if (hasMoreBackup.booleanValue()) {
            return;
        }
        synchronized (this.mLock) {
            this.mHasNoDelayedPermBackup.put(user.getIdentifier(), true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addOnRuntimePermissionStateChangedListener(PermissionManagerInternal.OnRuntimePermissionStateChangedListener listener) {
        synchronized (this.mLock) {
            this.mRuntimePermissionStateChangedListeners.add(listener);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeOnRuntimePermissionStateChangedListener(PermissionManagerInternal.OnRuntimePermissionStateChangedListener listener) {
        synchronized (this.mLock) {
            this.mRuntimePermissionStateChangedListeners.remove(listener);
        }
    }

    private void notifyRuntimePermissionStateChanged(String packageName, int userId) {
        FgThread.getHandler().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.pm.permission.-$$Lambda$PermissionManagerService$NPd9St1HBvGAtg1uhMV2Upfww4g
            public final void accept(Object obj, Object obj2, Object obj3) {
                ((PermissionManagerService) obj).doNotifyRuntimePermissionStateChanged((String) obj2, ((Integer) obj3).intValue());
            }
        }, this, packageName, Integer.valueOf(userId)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doNotifyRuntimePermissionStateChanged(String packageName, int userId) {
        synchronized (this.mLock) {
            if (this.mRuntimePermissionStateChangedListeners.isEmpty()) {
                return;
            }
            ArrayList<PermissionManagerInternal.OnRuntimePermissionStateChangedListener> listeners = new ArrayList<>(this.mRuntimePermissionStateChangedListeners);
            int listenerCount = listeners.size();
            for (int i = 0; i < listenerCount; i++) {
                listeners.get(i).onRuntimePermissionStateChanged(packageName, userId);
            }
        }
    }

    private static boolean isImpliedPermissionGranted(PermissionsState permissionsState, String permName, int userId) {
        return FULLER_PERMISSION_MAP.containsKey(permName) && permissionsState.hasPermission(FULLER_PERMISSION_MAP.get(permName), userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags, int callingUid) {
        PermissionGroupInfo generatePermissionGroupInfo;
        if (this.mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (this.mLock) {
            generatePermissionGroupInfo = PackageParser.generatePermissionGroupInfo(this.mSettings.mPermissionGroups.get(groupName), flags);
        }
        return generatePermissionGroupInfo;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags, int callingUid) {
        ArrayList<PermissionGroupInfo> out;
        if (this.mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (this.mLock) {
            int N = this.mSettings.mPermissionGroups.size();
            out = new ArrayList<>(N);
            for (PackageParser.PermissionGroup pg : this.mSettings.mPermissionGroups.values()) {
                out.add(PackageParser.generatePermissionGroupInfo(pg, flags));
            }
        }
        return out;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PermissionInfo getPermissionInfo(String permName, String packageName, int flags, int callingUid) {
        if (this.mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (this.mLock) {
            BasePermission bp = this.mSettings.getPermissionLocked(permName);
            if (bp == null) {
                return null;
            }
            int adjustedProtectionLevel = adjustPermissionProtectionFlagsLocked(bp.getProtectionLevel(), packageName, callingUid);
            return bp.generatePermissionInfo(adjustedProtectionLevel, flags);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<PermissionInfo> getPermissionInfoByGroup(String groupName, int flags, int callingUid) {
        if (this.mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        synchronized (this.mLock) {
            if (groupName != null) {
                if (!this.mSettings.mPermissionGroups.containsKey(groupName)) {
                    return null;
                }
            }
            ArrayList<PermissionInfo> out = new ArrayList<>(10);
            for (BasePermission bp : this.mSettings.mPermissions.values()) {
                PermissionInfo pi = bp.generatePermissionInfo(groupName, flags);
                if (pi != null) {
                    out.add(pi);
                }
            }
            return out;
        }
    }

    private int adjustPermissionProtectionFlagsLocked(int protectionLevel, String packageName, int uid) {
        int protectionLevelMasked = protectionLevel & 3;
        if (protectionLevelMasked == 2) {
            return protectionLevel;
        }
        int appId = UserHandle.getAppId(uid);
        if (appId == 1000 || appId == 0 || appId == 2000) {
            return protectionLevel;
        }
        PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
        if (pkg == null) {
            return protectionLevel;
        }
        if (pkg.applicationInfo.targetSdkVersion < 26) {
            return protectionLevelMasked;
        }
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return protectionLevel;
        }
        if (ps.getAppId() != appId) {
            return protectionLevel;
        }
        return protectionLevel;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void revokeStoragePermissionsIfScopeExpanded(PackageParser.Package newPackage, PackageParser.Package oldPackage, PermissionManagerServiceInternal.PermissionCallback permissionCallback) {
        int i;
        int numRequestedPermissions;
        int numRequestedPermissions2;
        int i2;
        int[] iArr;
        int i3;
        int i4;
        int userId;
        int i5 = 0;
        boolean downgradedSdk = oldPackage.applicationInfo.targetSdkVersion >= 29 && newPackage.applicationInfo.targetSdkVersion < 29;
        boolean upgradedSdk = oldPackage.applicationInfo.targetSdkVersion < 29 && newPackage.applicationInfo.targetSdkVersion >= 29;
        boolean newlyRequestsLegacy = (upgradedSdk || oldPackage.applicationInfo.hasRequestedLegacyExternalStorage() || !newPackage.applicationInfo.hasRequestedLegacyExternalStorage()) ? false : true;
        if (!newlyRequestsLegacy && !downgradedSdk) {
            return;
        }
        int callingUid = Binder.getCallingUid();
        int[] userIds = this.mUserManagerInt.getUserIds();
        int length = userIds.length;
        int i6 = 0;
        while (i6 < length) {
            int userId2 = userIds[i6];
            int userId3 = newPackage.requestedPermissions.size();
            int i7 = 0;
            while (i7 < userId3) {
                PermissionInfo permInfo = getPermissionInfo((String) newPackage.requestedPermissions.get(i7), newPackage.packageName, i5, callingUid);
                if (permInfo == null) {
                    i = i7;
                    numRequestedPermissions = userId3;
                    numRequestedPermissions2 = userId2;
                    i2 = length;
                    iArr = userIds;
                    i3 = i5;
                    i4 = i6;
                } else if (!STORAGE_PERMISSIONS.contains(permInfo.name)) {
                    i = i7;
                    numRequestedPermissions = userId3;
                    numRequestedPermissions2 = userId2;
                    i4 = i6;
                    i2 = length;
                    iArr = userIds;
                    i3 = 0;
                } else {
                    i3 = 0;
                    StringBuilder sb = new StringBuilder();
                    int i8 = i7;
                    sb.append("Revoking permission ");
                    sb.append(permInfo.name);
                    sb.append(" from package ");
                    sb.append(newPackage.packageName);
                    sb.append(" as either the sdk downgraded ");
                    sb.append(downgradedSdk);
                    sb.append(" or newly requested legacy full storage ");
                    sb.append(newlyRequestsLegacy);
                    EventLog.writeEvent(1397638484, "171430330", Integer.valueOf(newPackage.applicationInfo.uid), sb.toString());
                    try {
                        i = i8;
                        numRequestedPermissions = userId3;
                        userId = userId2;
                        i4 = i6;
                        i2 = length;
                        iArr = userIds;
                    } catch (IllegalStateException | SecurityException e) {
                        e = e;
                        userId = userId2;
                        i4 = i6;
                        i2 = length;
                        iArr = userIds;
                        i = i8;
                        numRequestedPermissions = userId3;
                    }
                    try {
                        revokeRuntimePermission(permInfo.name, newPackage.packageName, false, userId, permissionCallback);
                        numRequestedPermissions2 = userId;
                    } catch (IllegalStateException | SecurityException e2) {
                        e = e2;
                        StringBuilder sb2 = new StringBuilder();
                        sb2.append("unable to revoke ");
                        sb2.append(permInfo.name);
                        sb2.append(" for ");
                        sb2.append(newPackage.packageName);
                        sb2.append(" user ");
                        numRequestedPermissions2 = userId;
                        sb2.append(numRequestedPermissions2);
                        Log.e(TAG, sb2.toString(), e);
                        i7 = i + 1;
                        userId2 = numRequestedPermissions2;
                        i6 = i4;
                        i5 = i3;
                        length = i2;
                        userId3 = numRequestedPermissions;
                        userIds = iArr;
                    }
                }
                i7 = i + 1;
                userId2 = numRequestedPermissions2;
                i6 = i4;
                i5 = i3;
                length = i2;
                userId3 = numRequestedPermissions;
                userIds = iArr;
            }
            i6++;
            i5 = i5;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void revokeRuntimePermissionsIfGroupChanged(PackageParser.Package newPackage, PackageParser.Package oldPackage, ArrayList<String> allPackageNames, PermissionManagerServiceInternal.PermissionCallback permissionCallback) {
        int userIdNum;
        int numUserIds;
        int[] userIds;
        String oldPermissionGroupName;
        String newPermissionGroupName;
        String permissionName;
        PermissionManagerService permissionManagerService = this;
        int numOldPackagePermissions = oldPackage.permissions.size();
        ArrayMap<String, String> oldPermissionNameToGroupName = new ArrayMap<>(numOldPackagePermissions);
        for (int i = 0; i < numOldPackagePermissions; i++) {
            PackageParser.Permission permission = (PackageParser.Permission) oldPackage.permissions.get(i);
            if (permission.group != null) {
                oldPermissionNameToGroupName.put(permission.info.name, permission.group.info.name);
            }
        }
        int numNewPackagePermissions = newPackage.permissions.size();
        int newPermissionNum = 0;
        while (newPermissionNum < numNewPackagePermissions) {
            PackageParser.Permission newPermission = (PackageParser.Permission) newPackage.permissions.get(newPermissionNum);
            int newProtection = newPermission.info.getProtection();
            if ((newProtection & 1) != 0) {
                String permissionName2 = newPermission.info.name;
                String newPermissionGroupName2 = newPermission.group == null ? null : newPermission.group.info.name;
                String oldPermissionGroupName2 = oldPermissionNameToGroupName.get(permissionName2);
                if (newPermissionGroupName2 != null && !newPermissionGroupName2.equals(oldPermissionGroupName2)) {
                    int[] userIds2 = permissionManagerService.mUserManagerInt.getUserIds();
                    int numUserIds2 = userIds2.length;
                    int userIdNum2 = 0;
                    while (userIdNum2 < numUserIds2) {
                        int userId = userIds2[userIdNum2];
                        int numOldPackagePermissions2 = numOldPackagePermissions;
                        int numPackages = allPackageNames.size();
                        ArrayMap<String, String> oldPermissionNameToGroupName2 = oldPermissionNameToGroupName;
                        int packageNum = 0;
                        while (packageNum < numPackages) {
                            int numPackages2 = numPackages;
                            String packageName = allPackageNames.get(packageNum);
                            if (permissionManagerService.checkPermission(permissionName2, packageName, 0, userId) != 0) {
                                userIdNum = userIdNum2;
                                numUserIds = numUserIds2;
                                userIds = userIds2;
                                oldPermissionGroupName = oldPermissionGroupName2;
                                newPermissionGroupName = newPermissionGroupName2;
                                permissionName = permissionName2;
                            } else {
                                userIdNum = userIdNum2;
                                EventLog.writeEvent(1397638484, "72710897", Integer.valueOf(newPackage.applicationInfo.uid), "Revoking permission " + permissionName2 + " from package " + packageName + " as the group changed from " + oldPermissionGroupName2 + " to " + newPermissionGroupName2);
                                numUserIds = numUserIds2;
                                userIds = userIds2;
                                oldPermissionGroupName = oldPermissionGroupName2;
                                newPermissionGroupName = newPermissionGroupName2;
                                permissionName = permissionName2;
                                try {
                                    revokeRuntimePermission(permissionName2, packageName, false, userId, permissionCallback);
                                } catch (IllegalArgumentException e) {
                                    Slog.e(TAG, "Could not revoke " + permissionName + " from " + packageName, e);
                                }
                            }
                            packageNum++;
                            permissionName2 = permissionName;
                            numPackages = numPackages2;
                            userIdNum2 = userIdNum;
                            numUserIds2 = numUserIds;
                            userIds2 = userIds;
                            oldPermissionGroupName2 = oldPermissionGroupName;
                            newPermissionGroupName2 = newPermissionGroupName;
                            permissionManagerService = this;
                        }
                        userIdNum2++;
                        numOldPackagePermissions = numOldPackagePermissions2;
                        oldPermissionNameToGroupName = oldPermissionNameToGroupName2;
                        permissionManagerService = this;
                    }
                }
            }
            newPermissionNum++;
            permissionManagerService = this;
            numOldPackagePermissions = numOldPackagePermissions;
            oldPermissionNameToGroupName = oldPermissionNameToGroupName;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void revokeRuntimePermissionsIfPermissionDefinitionChanged(List<String> permissionsToRevoke, ArrayList<String> allPackageNames, PermissionManagerServiceInternal.PermissionCallback permissionCallback) {
        int[] userIds;
        int numPermissions;
        int packageNum;
        int userId;
        int userIdNum;
        int numPermissions2;
        BasePermission bp;
        PermissionManagerService permissionManagerService = this;
        int[] userIds2 = permissionManagerService.mUserManagerInt.getUserIds();
        int numPermissions3 = permissionsToRevoke.size();
        int numUserIds = userIds2.length;
        int numPackages = allPackageNames.size();
        Binder.getCallingUid();
        int permNum = 0;
        while (permNum < numPermissions3) {
            String permName = permissionsToRevoke.get(permNum);
            BasePermission bp2 = permissionManagerService.mSettings.getPermission(permName);
            if (bp2 == null) {
                userIds = userIds2;
                numPermissions = numPermissions3;
            } else if (!bp2.isRuntime()) {
                userIds = userIds2;
                numPermissions = numPermissions3;
            } else {
                int userIdNum2 = 0;
                while (userIdNum2 < numUserIds) {
                    int userId2 = userIds2[userIdNum2];
                    int packageNum2 = 0;
                    while (packageNum2 < numPackages) {
                        String packageName = allPackageNames.get(packageNum2);
                        int[] userIds3 = userIds2;
                        int uid = permissionManagerService.mPackageManagerInt.getPackageUid(packageName, 0, userId2);
                        if (uid < 10000) {
                            packageNum = packageNum2;
                            userId = userId2;
                            userIdNum = userIdNum2;
                            numPermissions2 = numPermissions3;
                            bp = bp2;
                        } else {
                            int permissionState = permissionManagerService.checkPermission(permName, packageName, Binder.getCallingUid(), userId2);
                            int flags = permissionManagerService.getPermissionFlags(permName, packageName, Binder.getCallingUid(), userId2);
                            if (permissionState != 0 || (flags & 52) != 0) {
                                packageNum = packageNum2;
                                userId = userId2;
                                userIdNum = userIdNum2;
                                numPermissions2 = numPermissions3;
                                bp = bp2;
                            } else {
                                EventLog.writeEvent(1397638484, "154505240", Integer.valueOf(uid), "Revoking permission " + permName + " from package " + packageName + " due to definition change");
                                EventLog.writeEvent(1397638484, "168319670", Integer.valueOf(uid), "Revoking permission " + permName + " from package " + packageName + " due to definition change");
                                Slog.e(TAG, "Revoking permission " + permName + " from package " + packageName + " due to definition change");
                                packageNum = packageNum2;
                                userId = userId2;
                                userIdNum = userIdNum2;
                                numPermissions2 = numPermissions3;
                                bp = bp2;
                                try {
                                    revokeRuntimePermission(permName, packageName, false, userId, permissionCallback);
                                } catch (Exception e) {
                                    Slog.e(TAG, "Could not revoke " + permName + " from " + packageName, e);
                                }
                            }
                        }
                        packageNum2 = packageNum + 1;
                        permissionManagerService = this;
                        bp2 = bp;
                        userIds2 = userIds3;
                        userId2 = userId;
                        userIdNum2 = userIdNum;
                        numPermissions3 = numPermissions2;
                    }
                    userIdNum2++;
                    permissionManagerService = this;
                    numPermissions3 = numPermissions3;
                }
                userIds = userIds2;
                numPermissions = numPermissions3;
                bp2.setPermissionDefinitionChanged(false);
            }
            permNum++;
            permissionManagerService = this;
            userIds2 = userIds;
            numPermissions3 = numPermissions;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<String> addAllPermissions(PackageParser.Package pkg, boolean chatty) {
        BasePermission bp;
        int N = ArrayUtils.size(pkg.permissions);
        ArrayList<String> definitionChangedPermissions = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            PackageParser.Permission p = (PackageParser.Permission) pkg.permissions.get(i);
            p.info.flags &= -1073741825;
            synchronized (this.mLock) {
                if (pkg.applicationInfo.targetSdkVersion > 22) {
                    p.group = this.mSettings.mPermissionGroups.get(p.info.group);
                }
                if (p.tree) {
                    bp = BasePermission.createOrUpdate(this.mSettings.getPermissionTreeLocked(p.info.name), p, pkg, this.mSettings.getAllPermissionTreesLocked(), chatty);
                    this.mSettings.putPermissionTreeLocked(p.info.name, bp);
                } else {
                    bp = BasePermission.createOrUpdate(this.mSettings.getPermissionLocked(p.info.name), p, pkg, this.mSettings.getAllPermissionTreesLocked(), chatty);
                    this.mSettings.putPermissionLocked(p.info.name, bp);
                }
                if (bp.isPermissionDefinitionChanged()) {
                    definitionChangedPermissions.add(p.info.name);
                }
            }
        }
        return definitionChangedPermissions;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addAllPermissionGroups(PackageParser.Package pkg, boolean chatty) {
        int N = pkg.permissionGroups.size();
        for (int i = 0; i < N; i++) {
            PackageParser.PermissionGroup pg = (PackageParser.PermissionGroup) pkg.permissionGroups.get(i);
            PackageParser.PermissionGroup cur = this.mSettings.mPermissionGroups.get(pg.info.name);
            String curPackageName = cur == null ? null : cur.info.packageName;
            boolean isPackageUpdate = pg.info.packageName.equals(curPackageName);
            if (cur == null || isPackageUpdate) {
                this.mSettings.mPermissionGroups.put(pg.info.name, pg);
            } else {
                Slog.w(TAG, "Permission group " + pg.info.name + " from package " + pg.info.packageName + " ignored: original from " + cur.info.packageName);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeAllPermissions(PackageParser.Package pkg, boolean chatty) {
        ArraySet<String> appOpPkgs;
        ArraySet<String> appOpPkgs2;
        synchronized (this.mLock) {
            int N = pkg.permissions.size();
            for (int i = 0; i < N; i++) {
                PackageParser.Permission p = (PackageParser.Permission) pkg.permissions.get(i);
                BasePermission bp = this.mSettings.mPermissions.get(p.info.name);
                if (bp == null) {
                    bp = this.mSettings.mPermissionTrees.get(p.info.name);
                }
                if (bp != null && bp.isPermission(p)) {
                    bp.setPermission(null);
                }
                if (p.isAppOp() && (appOpPkgs2 = this.mSettings.mAppOpPermissionPackages.get(p.info.name)) != null) {
                    appOpPkgs2.remove(pkg.packageName);
                }
            }
            int N2 = pkg.requestedPermissions.size();
            for (int i2 = 0; i2 < N2; i2++) {
                String perm = (String) pkg.requestedPermissions.get(i2);
                if (this.mSettings.isPermissionAppOp(perm) && (appOpPkgs = this.mSettings.mAppOpPermissionPackages.get(perm)) != null) {
                    appOpPkgs.remove(pkg.packageName);
                    if (appOpPkgs.isEmpty()) {
                        this.mSettings.mAppOpPermissionPackages.remove(perm);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean addDynamicPermission(PermissionInfo info, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
        boolean added;
        boolean changed;
        if (this.mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant apps can't add permissions");
        }
        if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
            throw new SecurityException("Label must be specified in permission");
        }
        BasePermission tree = this.mSettings.enforcePermissionTree(info.name, callingUid);
        synchronized (this.mLock) {
            BasePermission bp = this.mSettings.getPermissionLocked(info.name);
            added = bp == null;
            int fixedLevel = PermissionInfo.fixProtectionLevel(info.protectionLevel);
            if (added) {
                enforcePermissionCapLocked(info, tree);
                bp = new BasePermission(info.name, tree.getSourcePackageName(), 2);
            } else if (!bp.isDynamic()) {
                throw new SecurityException("Not allowed to modify non-dynamic permission " + info.name);
            }
            changed = bp.addToTree(fixedLevel, info, tree);
            if (added) {
                this.mSettings.putPermissionLocked(info.name, bp);
            }
        }
        if (changed && callback != null) {
            callback.onPermissionChanged();
        }
        return added;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeDynamicPermission(String permName, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
        if (this.mPackageManagerInt.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        this.mSettings.enforcePermissionTree(permName, callingUid);
        synchronized (this.mLock) {
            BasePermission bp = this.mSettings.getPermissionLocked(permName);
            if (bp == null) {
                return;
            }
            if (bp.isDynamic()) {
                Slog.wtf(TAG, "Not allowed to modify non-dynamic permission " + permName);
            }
            this.mSettings.removePermissionLocked(permName);
            if (callback != null) {
                callback.onPermissionRemoved();
            }
        }
    }

    /* JADX WARN: Can't wrap try/catch for region: R(14:48|(6:50|51|52|(3:453|454|(1:456))|54|(2:56|57)(11:436|437|(2:438|(3:440|(3:444|445|446)|448)(2:451|452))|59|60|(3:426|427|(4:429|29|30|31))|62|(6:66|(2:68|69)(1:425)|70|(1:72)(2:410|(2:412|(1:416)(1:415))(2:417|(3:419|420|(1:422)(1:423))(1:424)))|73|(6:104|105|(1:116)|117|(2:119|(2:121|(1:(2:124|125)(1:126))(14:127|128|(1:130)(1:241)|(1:132)(2:239|240)|133|134|(4:136|137|138|139)(1:238)|140|141|142|(14:144|145|(3:219|220|(11:222|149|150|(1:152)(1:218)|153|(1:155)(1:217)|(5:(2:200|(1:205))(1:(2:(1:198)(3:163|164|(1:166))|(1:168))(1:199))|169|(1:171)|172|(1:174)(1:(1:197)))(3:206|(1:210)|(1:216))|(4:179|180|(1:182)|183)|(1:189)|190|191))(1:147)|148|149|150|(0)(0)|153|(0)(0)|(0)(0)|(4:179|180|(0)|183)|(0)|190|191)|229|230|231))(8:245|246|247|248|(21:250|251|(3:358|359|(18:361|254|255|256|(2:350|351)(1:258)|259|260|261|(1:263)(1:349)|264|265|266|(1:268)(1:345)|(5:(2:320|(1:325))(1:(2:(1:318)(3:276|277|(1:279))|(1:281)(1:317))(1:319))|282|(1:284)|285|(1:287)(2:(1:(1:316)(2:313|(1:315)))(1:308)|309))(5:326|(1:332)|333|(1:337)|(1:343))|(4:292|293|(1:295)|296)|(1:302)|303|304))|253|254|255|256|(0)(0)|259|260|261|(0)(0)|264|265|266|(0)(0)|(0)(0)|(4:292|293|(0)|296)|(0)|303|304)|368|369|370))(9:374|375|376|377|(7:379|380|381|382|383|(2:385|386)(1:388)|387)|395|396|397|(2:399|400)(2:401|402))|31)(7:75|76|77|(10:79|80|81|82|83|84|85|86|87|31)(4:94|95|96|97)|37|(3:38|39|41)|42))(1:65)|29|30|31))(1:463)|58|59|60|(0)|62|(0)|66|(0)(0)|70|(0)(0)|73|(0)(0)) */
    /* JADX WARN: Code restructure failed: missing block: B:395:0x0795, code lost:
        r0 = th;
     */
    /* JADX WARN: Code restructure failed: missing block: B:417:0x0845, code lost:
        if (r11 != false) goto L519;
     */
    /* JADX WARN: Code restructure failed: missing block: B:423:0x0854, code lost:
        if (r29.isSystem() != false) goto L480;
     */
    /* JADX WARN: Removed duplicated region for block: B:165:0x02e7  */
    /* JADX WARN: Removed duplicated region for block: B:166:0x02e9  */
    /* JADX WARN: Removed duplicated region for block: B:169:0x02f4  */
    /* JADX WARN: Removed duplicated region for block: B:170:0x02f6  */
    /* JADX WARN: Removed duplicated region for block: B:172:0x02f9  */
    /* JADX WARN: Removed duplicated region for block: B:204:0x0357 A[Catch: all -> 0x03ad, TryCatch #6 {all -> 0x03ad, blocks: (B:220:0x0386, B:222:0x038a, B:225:0x0391, B:226:0x0396, B:201:0x034d, B:204:0x0357, B:206:0x0363, B:208:0x036a, B:214:0x0378), top: B:475:0x0386 }] */
    /* JADX WARN: Removed duplicated region for block: B:222:0x038a A[Catch: all -> 0x03ad, TryCatch #6 {all -> 0x03ad, blocks: (B:220:0x0386, B:222:0x038a, B:225:0x0391, B:226:0x0396, B:201:0x034d, B:204:0x0357, B:206:0x0363, B:208:0x036a, B:214:0x0378), top: B:475:0x0386 }] */
    /* JADX WARN: Removed duplicated region for block: B:225:0x0391 A[Catch: all -> 0x03ad, TryCatch #6 {all -> 0x03ad, blocks: (B:220:0x0386, B:222:0x038a, B:225:0x0391, B:226:0x0396, B:201:0x034d, B:204:0x0357, B:206:0x0363, B:208:0x036a, B:214:0x0378), top: B:475:0x0386 }] */
    /* JADX WARN: Removed duplicated region for block: B:256:0x0494  */
    /* JADX WARN: Removed duplicated region for block: B:261:0x04a6  */
    /* JADX WARN: Removed duplicated region for block: B:262:0x04a8  */
    /* JADX WARN: Removed duplicated region for block: B:266:0x04b5  */
    /* JADX WARN: Removed duplicated region for block: B:267:0x04b7  */
    /* JADX WARN: Removed duplicated region for block: B:269:0x04ba  */
    /* JADX WARN: Removed duplicated region for block: B:309:0x0535  */
    /* JADX WARN: Removed duplicated region for block: B:335:0x0584 A[Catch: all -> 0x05a0, TryCatch #0 {all -> 0x05a0, blocks: (B:333:0x0580, B:335:0x0584, B:338:0x058a, B:339:0x058f, B:302:0x0518, B:304:0x051e, B:311:0x053b, B:313:0x0549, B:315:0x054f, B:317:0x0556, B:319:0x055e, B:327:0x0572), top: B:463:0x0580 }] */
    /* JADX WARN: Removed duplicated region for block: B:338:0x058a A[Catch: all -> 0x05a0, TryCatch #0 {all -> 0x05a0, blocks: (B:333:0x0580, B:335:0x0584, B:338:0x058a, B:339:0x058f, B:302:0x0518, B:304:0x051e, B:311:0x053b, B:313:0x0549, B:315:0x054f, B:317:0x0556, B:319:0x055e, B:327:0x0572), top: B:463:0x0580 }] */
    /* JADX WARN: Removed duplicated region for block: B:375:0x06d1  */
    /* JADX WARN: Removed duplicated region for block: B:444:0x08ac  */
    /* JADX WARN: Removed duplicated region for block: B:447:0x08b3 A[LOOP:6: B:446:0x08b1->B:447:0x08b3, LOOP_END] */
    /* JADX WARN: Removed duplicated region for block: B:483:0x0161 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:493:0x0477 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:509:0x01fc A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:84:0x01aa  */
    /* JADX WARN: Removed duplicated region for block: B:87:0x01b6  */
    /* JADX WARN: Removed duplicated region for block: B:90:0x01c0  */
    /* JADX WARN: Removed duplicated region for block: B:91:0x01c5 A[Catch: all -> 0x0795, TRY_LEAVE, TryCatch #32 {all -> 0x0795, blocks: (B:71:0x0159, B:78:0x0188, B:82:0x019d, B:88:0x01ba, B:91:0x01c5, B:99:0x01e2), top: B:527:0x0159 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void restorePermissionState(android.content.pm.PackageParser.Package r37, boolean r38, java.lang.String r39, com.android.server.pm.permission.PermissionManagerServiceInternal.PermissionCallback r40) {
        /*
            Method dump skipped, instructions count: 2292
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.permission.PermissionManagerService.restorePermissionState(android.content.pm.PackageParser$Package, boolean, java.lang.String, com.android.server.pm.permission.PermissionManagerServiceInternal$PermissionCallback):void");
    }

    private int[] revokePermissionsNoLongerImplicitLocked(PermissionsState ps, PackageParser.Package pkg, int[] updatedUserIds) {
        String str = pkg.packageName;
        boolean supportsRuntimePermissions = pkg.applicationInfo.targetSdkVersion >= 23;
        int[] users = UserManagerService.getInstance().getUserIds();
        int[] updatedUserIds2 = updatedUserIds;
        for (int userId : users) {
            for (String permission : ps.getPermissions(userId)) {
                if (!pkg.implicitPermissions.contains(permission) && !ps.hasInstallPermission(permission)) {
                    int flags = ps.getRuntimePermissionState(permission, userId).getFlags();
                    if ((flags & 128) != 0) {
                        BasePermission bp = this.mSettings.getPermissionLocked(permission);
                        int flagsToRemove = 128;
                        if ((flags & 52) == 0 && supportsRuntimePermissions) {
                            ps.revokeRuntimePermission(bp, userId);
                            flagsToRemove = 128 | 3;
                        }
                        ps.updatePermissionFlags(bp, userId, flagsToRemove, 0);
                        updatedUserIds2 = ArrayUtils.appendInt(updatedUserIds2, userId);
                    }
                }
            }
        }
        return updatedUserIds2;
    }

    private void inheritPermissionStateToNewImplicitPermissionLocked(ArraySet<String> sourcePerms, String newPerm, PermissionsState ps, PackageParser.Package pkg, int userId) {
        String str = pkg.packageName;
        boolean isGranted = false;
        int flags = 0;
        int numSourcePerm = sourcePerms.size();
        for (int i = 0; i < numSourcePerm; i++) {
            String sourcePerm = sourcePerms.valueAt(i);
            if (ps.hasRuntimePermission(sourcePerm, userId) || ps.hasInstallPermission(sourcePerm)) {
                if (!isGranted) {
                    flags = 0;
                }
                isGranted = true;
                flags |= ps.getPermissionFlags(sourcePerm, userId);
            } else if (!isGranted) {
                flags |= ps.getPermissionFlags(sourcePerm, userId);
            }
        }
        if (isGranted) {
            ps.grantRuntimePermission(this.mSettings.getPermissionLocked(newPerm), userId);
        }
        ps.updatePermissionFlags(this.mSettings.getPermission(newPerm), userId, flags, flags);
    }

    private int[] checkIfLegacyStorageOpsNeedToBeUpdated(PackageParser.Package pkg, boolean replace, int[] updatedUserIds) {
        if (replace && pkg.applicationInfo.hasRequestedLegacyExternalStorage() && (pkg.requestedPermissions.contains("android.permission.READ_EXTERNAL_STORAGE") || pkg.requestedPermissions.contains("android.permission.WRITE_EXTERNAL_STORAGE"))) {
            return UserManagerService.getInstance().getUserIds();
        }
        return updatedUserIds;
    }

    private int[] setInitialGrantForNewImplicitPermissionsLocked(PermissionsState origPs, PermissionsState ps, PackageParser.Package pkg, ArraySet<String> newImplicitPermissions, int[] updatedUserIds) {
        boolean inheritsFromInstallPerm;
        int numUsers;
        int[] users;
        BasePermission bp;
        ArraySet<String> sourcePerms;
        String str = pkg.packageName;
        ArrayMap<String, ArraySet<String>> newToSplitPerms = new ArrayMap<>();
        List<PermissionManager.SplitPermissionInfo> permissionList = getSplitPermissions();
        int numSplitPerms = permissionList.size();
        for (int splitPermNum = 0; splitPermNum < numSplitPerms; splitPermNum++) {
            PermissionManager.SplitPermissionInfo spi = permissionList.get(splitPermNum);
            List<String> newPerms = spi.getNewPermissions();
            int numNewPerms = newPerms.size();
            for (int newPermNum = 0; newPermNum < numNewPerms; newPermNum++) {
                String newPerm = newPerms.get(newPermNum);
                ArraySet<String> splitPerms = newToSplitPerms.get(newPerm);
                if (splitPerms == null) {
                    splitPerms = new ArraySet<>();
                    newToSplitPerms.put(newPerm, splitPerms);
                }
                splitPerms.add(spi.getSplitPermission());
            }
        }
        int numNewImplicitPerms = newImplicitPermissions.size();
        int[] updatedUserIds2 = updatedUserIds;
        for (int newImplicitPermNum = 0; newImplicitPermNum < numNewImplicitPerms; newImplicitPermNum++) {
            String newPerm2 = newImplicitPermissions.valueAt(newImplicitPermNum);
            ArraySet<String> sourcePerms2 = newToSplitPerms.get(newPerm2);
            if (sourcePerms2 != null && !ps.hasInstallPermission(newPerm2)) {
                BasePermission bp2 = this.mSettings.getPermissionLocked(newPerm2);
                int[] users2 = UserManagerService.getInstance().getUserIds();
                int numUsers2 = users2.length;
                int[] updatedUserIds3 = updatedUserIds2;
                int userNum = 0;
                while (userNum < numUsers2) {
                    int userId = users2[userNum];
                    int userNum2 = userNum;
                    if (!newPerm2.equals("android.permission.ACTIVITY_RECOGNITION")) {
                        ps.updatePermissionFlags(bp2, userId, 128, 128);
                    }
                    int[] updatedUserIds4 = ArrayUtils.appendInt(updatedUserIds3, userId);
                    boolean inheritsFromInstallPerm2 = false;
                    int sourcePermNum = 0;
                    while (true) {
                        boolean inheritsFromInstallPerm3 = inheritsFromInstallPerm2;
                        if (sourcePermNum < sourcePerms2.size()) {
                            if (!ps.hasInstallPermission(sourcePerms2.valueAt(sourcePermNum))) {
                                sourcePermNum++;
                                inheritsFromInstallPerm2 = inheritsFromInstallPerm3;
                            } else {
                                inheritsFromInstallPerm = true;
                                break;
                            }
                        } else {
                            inheritsFromInstallPerm = inheritsFromInstallPerm3;
                            break;
                        }
                    }
                    if (origPs.hasRequestedPermission(sourcePerms2) || inheritsFromInstallPerm) {
                        numUsers = numUsers2;
                        users = users2;
                        bp = bp2;
                        sourcePerms = sourcePerms2;
                        inheritPermissionStateToNewImplicitPermissionLocked(sourcePerms2, newPerm2, ps, pkg, userId);
                    } else {
                        numUsers = numUsers2;
                        users = users2;
                        bp = bp2;
                        sourcePerms = sourcePerms2;
                    }
                    userNum = userNum2 + 1;
                    updatedUserIds3 = updatedUserIds4;
                    numUsers2 = numUsers;
                    users2 = users;
                    bp2 = bp;
                    sourcePerms2 = sourcePerms;
                }
                updatedUserIds2 = updatedUserIds3;
            }
        }
        return updatedUserIds2;
    }

    private List<PermissionManager.SplitPermissionInfo> getSplitPermissions() {
        return SystemConfig.getInstance().getSplitPermissions();
    }

    private boolean isNewPlatformPermissionForPackage(String perm, PackageParser.Package pkg) {
        int NP = PackageParser.NEW_PERMISSIONS.length;
        for (int ip = 0; ip < NP; ip++) {
            PackageParser.NewPermissionInfo npi = PackageParser.NEW_PERMISSIONS[ip];
            if (npi.name.equals(perm) && pkg.applicationInfo.targetSdkVersion < npi.sdkVersion) {
                Log.i(TAG, "Auto-granting " + perm + " to old pkg " + pkg.packageName);
                return true;
            }
        }
        return false;
    }

    private boolean hasPrivappWhitelistEntry(String perm, PackageParser.Package pkg) {
        ArraySet<String> wlPermissions;
        if (pkg.isVendor()) {
            wlPermissions = SystemConfig.getInstance().getVendorPrivAppPermissions(pkg.packageName);
        } else if (pkg.isProduct()) {
            wlPermissions = SystemConfig.getInstance().getProductPrivAppPermissions(pkg.packageName);
        } else if (pkg.isProductServices()) {
            wlPermissions = SystemConfig.getInstance().getProductServicesPrivAppPermissions(pkg.packageName);
        } else {
            wlPermissions = SystemConfig.getInstance().getPrivAppPermissions(pkg.packageName);
        }
        boolean whitelisted = wlPermissions != null && wlPermissions.contains(perm);
        if (whitelisted) {
            return true;
        }
        return pkg.parentPackage != null && hasPrivappWhitelistEntry(perm, pkg.parentPackage);
    }

    /* JADX WARN: Code restructure failed: missing block: B:152:0x0257, code lost:
        if (r5 == false) goto L59;
     */
    /* JADX WARN: Code restructure failed: missing block: B:153:0x0259, code lost:
        if (r4 != false) goto L59;
     */
    /* JADX WARN: Code restructure failed: missing block: B:155:0x025f, code lost:
        if (r23.isVendor() == false) goto L59;
     */
    /* JADX WARN: Code restructure failed: missing block: B:156:0x0261, code lost:
        android.util.Slog.w(com.android.server.pm.permission.PermissionManagerService.TAG, "Permission " + r22 + " cannot be granted to privileged vendor apk " + r23.packageName + " because it isn't a 'vendorPrivileged' permission.");
        r13 = false;
     */
    /* JADX WARN: Code restructure failed: missing block: B:160:0x028d, code lost:
        if (r13 != false) goto L66;
     */
    /* JADX WARN: Code restructure failed: missing block: B:162:0x0293, code lost:
        if (r24.isPre23() == false) goto L66;
     */
    /* JADX WARN: Code restructure failed: missing block: B:164:0x029b, code lost:
        if (r23.applicationInfo.targetSdkVersion >= 23) goto L66;
     */
    /* JADX WARN: Code restructure failed: missing block: B:165:0x029d, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:166:0x029e, code lost:
        if (r13 != false) goto L74;
     */
    /* JADX WARN: Code restructure failed: missing block: B:168:0x02a4, code lost:
        if (r24.isInstaller() == false) goto L74;
     */
    /* JADX WARN: Code restructure failed: missing block: B:170:0x02b4, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(2, 0)) != false) goto L73;
     */
    /* JADX WARN: Code restructure failed: missing block: B:172:0x02c3, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(6, 0)) == false) goto L74;
     */
    /* JADX WARN: Code restructure failed: missing block: B:173:0x02c5, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:174:0x02c6, code lost:
        if (r13 != false) goto L80;
     */
    /* JADX WARN: Code restructure failed: missing block: B:176:0x02cc, code lost:
        if (r24.isVerifier() == false) goto L80;
     */
    /* JADX WARN: Code restructure failed: missing block: B:178:0x02dc, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(3, 0)) == false) goto L80;
     */
    /* JADX WARN: Code restructure failed: missing block: B:179:0x02de, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:180:0x02df, code lost:
        if (r13 != false) goto L86;
     */
    /* JADX WARN: Code restructure failed: missing block: B:182:0x02e5, code lost:
        if (r24.isPreInstalled() == false) goto L86;
     */
    /* JADX WARN: Code restructure failed: missing block: B:184:0x02eb, code lost:
        if (r23.isSystem() == false) goto L86;
     */
    /* JADX WARN: Code restructure failed: missing block: B:185:0x02ed, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:186:0x02ee, code lost:
        if (r13 != false) goto L135;
     */
    /* JADX WARN: Code restructure failed: missing block: B:188:0x02f4, code lost:
        if (r24.isDevelopment() == false) goto L135;
     */
    /* JADX WARN: Code restructure failed: missing block: B:189:0x02f6, code lost:
        r13 = r25.hasInstallPermission(r22);
     */
    /* JADX WARN: Code restructure failed: missing block: B:191:0x02ff, code lost:
        if (r13 != false) goto L96;
     */
    /* JADX WARN: Code restructure failed: missing block: B:193:0x0305, code lost:
        if (r24.isSetup() == false) goto L96;
     */
    /* JADX WARN: Code restructure failed: missing block: B:195:0x0315, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(1, 0)) == false) goto L96;
     */
    /* JADX WARN: Code restructure failed: missing block: B:196:0x0317, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:197:0x0318, code lost:
        if (r13 != false) goto L102;
     */
    /* JADX WARN: Code restructure failed: missing block: B:199:0x031e, code lost:
        if (r24.isSystemTextClassifier() == false) goto L102;
     */
    /* JADX WARN: Code restructure failed: missing block: B:201:0x032e, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(5, 0)) == false) goto L102;
     */
    /* JADX WARN: Code restructure failed: missing block: B:202:0x0330, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:203:0x0331, code lost:
        if (r13 != false) goto L108;
     */
    /* JADX WARN: Code restructure failed: missing block: B:205:0x0337, code lost:
        if (r24.isConfigurator() == false) goto L108;
     */
    /* JADX WARN: Code restructure failed: missing block: B:207:0x0348, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(9, 0)) == false) goto L108;
     */
    /* JADX WARN: Code restructure failed: missing block: B:208:0x034a, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:209:0x034b, code lost:
        if (r13 != false) goto L114;
     */
    /* JADX WARN: Code restructure failed: missing block: B:211:0x0351, code lost:
        if (r24.isWellbeing() == false) goto L114;
     */
    /* JADX WARN: Code restructure failed: missing block: B:213:0x0361, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(7, 0)) == false) goto L114;
     */
    /* JADX WARN: Code restructure failed: missing block: B:214:0x0363, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:215:0x0364, code lost:
        if (r13 != false) goto L120;
     */
    /* JADX WARN: Code restructure failed: missing block: B:217:0x036a, code lost:
        if (r24.isDocumenter() == false) goto L120;
     */
    /* JADX WARN: Code restructure failed: missing block: B:219:0x037b, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(8, 0)) == false) goto L120;
     */
    /* JADX WARN: Code restructure failed: missing block: B:220:0x037d, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:221:0x037e, code lost:
        if (r13 != false) goto L126;
     */
    /* JADX WARN: Code restructure failed: missing block: B:223:0x0384, code lost:
        if (r24.isIncidentReportApprover() == false) goto L126;
     */
    /* JADX WARN: Code restructure failed: missing block: B:225:0x0395, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(10, 0)) == false) goto L126;
     */
    /* JADX WARN: Code restructure failed: missing block: B:226:0x0397, code lost:
        r13 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:227:0x0399, code lost:
        if (r13 != false) goto L134;
     */
    /* JADX WARN: Code restructure failed: missing block: B:229:0x039f, code lost:
        if (r24.isAppPredictor() == false) goto L133;
     */
    /* JADX WARN: Code restructure failed: missing block: B:231:0x03b0, code lost:
        if (r23.packageName.equals(r21.mPackageManagerInt.getKnownPackageName(11, 0)) == false) goto L132;
     */
    /* JADX WARN: Code restructure failed: missing block: B:232:0x03b2, code lost:
        return true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:239:?, code lost:
        return r13;
     */
    /* JADX WARN: Code restructure failed: missing block: B:240:?, code lost:
        return r13;
     */
    /* JADX WARN: Code restructure failed: missing block: B:241:?, code lost:
        return r13;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean grantSignaturePermission(java.lang.String r22, android.content.pm.PackageParser.Package r23, com.android.server.pm.permission.BasePermission r24, com.android.server.pm.permission.PermissionsState r25) {
        /*
            Method dump skipped, instructions count: 951
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.permission.PermissionManagerService.grantSignaturePermission(java.lang.String, android.content.pm.PackageParser$Package, com.android.server.pm.permission.BasePermission, com.android.server.pm.permission.PermissionsState):boolean");
    }

    private static boolean canGrantOemPermission(PackageSetting ps, String permission) {
        if (ps.isOem()) {
            Boolean granted = (Boolean) SystemConfig.getInstance().getOemPermissions(ps.name).get(permission);
            if (granted != null) {
                return Boolean.TRUE == granted;
            }
            throw new IllegalStateException("OEM permission" + permission + " requested by package " + ps.name + " must be explicitly declared granted or not");
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isPermissionsReviewRequired(PackageParser.Package pkg, int userId) {
        if (pkg.applicationInfo.targetSdkVersion < 23 && pkg.mExtras != null) {
            PermissionManagerPolicy permissionManagerPolicy = this.mPermissionPolicy;
            if (permissionManagerPolicy == null || permissionManagerPolicy.enforceGrantPermission(pkg.applicationInfo.packageName, null) != 1) {
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                PermissionsState permissionsState = ps.getPermissionsState();
                return permissionsState.isPermissionReviewRequired(userId);
            }
            return false;
        }
        return false;
    }

    private boolean isPackageRequestingPermission(PackageParser.Package pkg, String permission) {
        int permCount = pkg.requestedPermissions.size();
        for (int j = 0; j < permCount; j++) {
            String requestedPermission = (String) pkg.requestedPermissions.get(j);
            if (permission.equals(requestedPermission)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void grantRuntimePermissionsGrantedToDisabledPackageLocked(PackageParser.Package pkg, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
        PackageParser.Package disabledPkg;
        int i;
        int i2;
        if (pkg.parentPackage == null || pkg.requestedPermissions == null || (disabledPkg = this.mPackageManagerInt.getDisabledSystemPackage(pkg.parentPackage.packageName)) == null || disabledPkg.mExtras == null) {
            return;
        }
        PackageSetting disabledPs = (PackageSetting) disabledPkg.mExtras;
        if (!disabledPs.isPrivileged() || disabledPs.hasChildPackages()) {
            return;
        }
        int permCount = pkg.requestedPermissions.size();
        for (int i3 = 0; i3 < permCount; i3++) {
            String permission = (String) pkg.requestedPermissions.get(i3);
            BasePermission bp = this.mSettings.getPermissionLocked(permission);
            if (bp != null && (bp.isRuntime() || bp.isDevelopment())) {
                int[] userIds = this.mUserManagerInt.getUserIds();
                int length = userIds.length;
                int i4 = 0;
                while (i4 < length) {
                    int userId = userIds[i4];
                    if (disabledPs.getPermissionsState().hasRuntimePermission(permission, userId)) {
                        i = i4;
                        i2 = length;
                        grantRuntimePermission(permission, pkg.packageName, false, callingUid, userId, callback);
                    } else {
                        i = i4;
                        i2 = length;
                    }
                    i4 = i + 1;
                    length = i2;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void grantRequestedRuntimePermissions(PackageParser.Package pkg, int[] userIds, String[] grantedPermissions, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
        for (int userId : userIds) {
            grantRequestedRuntimePermissionsForUser(pkg, userId, grantedPermissions, callingUid, callback);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public List<String> getWhitelistedRestrictedPermissions(PackageParser.Package pkg, int whitelistFlags, int userId) {
        PackageSetting packageSetting = (PackageSetting) pkg.mExtras;
        if (packageSetting == null) {
            return null;
        }
        PermissionsState permissionsState = packageSetting.getPermissionsState();
        int queryFlags = 0;
        if ((whitelistFlags & 1) != 0) {
            queryFlags = 0 | 4096;
        }
        if ((whitelistFlags & 4) != 0) {
            queryFlags |= 8192;
        }
        if ((whitelistFlags & 2) != 0) {
            queryFlags |= 2048;
        }
        ArrayList<String> whitelistedPermissions = null;
        int permissionCount = pkg.requestedPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            String permissionName = (String) pkg.requestedPermissions.get(i);
            int currentFlags = permissionsState.getPermissionFlags(permissionName, userId);
            if ((currentFlags & queryFlags) != 0) {
                if (whitelistedPermissions == null) {
                    whitelistedPermissions = new ArrayList<>();
                }
                whitelistedPermissions.add(permissionName);
            }
        }
        return whitelistedPermissions;
    }

    private void grantRequestedRuntimePermissionsForUser(PackageParser.Package pkg, int userId, String[] grantedPermissions, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
        BasePermission bp;
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }
        PermissionsState permissionsState = ps.getPermissionsState();
        boolean supportsRuntimePermissions = pkg.applicationInfo.targetSdkVersion >= 23;
        boolean instantApp = this.mPackageManagerInt.isInstantApp(pkg.packageName, userId);
        Iterator it = pkg.requestedPermissions.iterator();
        while (it.hasNext()) {
            String permission = (String) it.next();
            synchronized (this.mLock) {
                try {
                    bp = this.mSettings.getPermissionLocked(permission);
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
            if (bp != null && (bp.isRuntime() || bp.isDevelopment())) {
                if (!instantApp || bp.isInstant()) {
                    if (supportsRuntimePermissions || !bp.isRuntimeOnly()) {
                        if (grantedPermissions == null || ArrayUtils.contains(grantedPermissions, permission)) {
                            int flags = permissionsState.getPermissionFlags(permission, userId);
                            if (supportsRuntimePermissions) {
                                if ((flags & 20) == 0) {
                                    grantRuntimePermission(permission, pkg.packageName, false, callingUid, userId, callback);
                                }
                            } else if ((flags & 64) != 0) {
                                updatePermissionFlags(permission, pkg.packageName, 64, 0, callingUid, userId, false, callback);
                            }
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void grantRuntimePermission(String permName, String packageName, boolean overridePolicy, int callingUid, int userId, PermissionManagerServiceInternal.PermissionCallback callback) {
        BasePermission bp;
        if (!this.mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.GRANT_RUNTIME_PERMISSIONS", "grantRuntimePermission");
        enforceCrossUserPermission(callingUid, userId, true, true, false, "grantRuntimePermission");
        PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
        if (pkg != null && pkg.mExtras != null) {
            synchronized (this.mLock) {
                try {
                    bp = this.mSettings.getPermissionLocked(permName);
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
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            } else if (this.mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            } else {
                bp.enforceDeclaredUsedAndRuntimeOrDevelopment(pkg);
                if (pkg.applicationInfo.targetSdkVersion < 23 && bp.isRuntime()) {
                    return;
                }
                int uid = UserHandle.getUid(userId, pkg.applicationInfo.uid);
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                PermissionsState permissionsState = ps.getPermissionsState();
                int flags = permissionsState.getPermissionFlags(permName, userId);
                if ((flags & 16) != 0) {
                    Log.e(TAG, "Cannot grant system fixed permission " + permName + " for package " + packageName);
                    return;
                } else if (!overridePolicy && (flags & 4) != 0) {
                    Log.e(TAG, "Cannot grant policy fixed permission " + permName + " for package " + packageName);
                    return;
                } else if (bp.isHardRestricted() && (flags & 14336) == 0) {
                    Log.e(TAG, "Cannot grant hard restricted non-exempt permission " + permName + " for package " + packageName);
                    return;
                } else if (bp.isSoftRestricted() && !SoftRestrictedPermissionPolicy.forPermission(this.mContext, pkg.applicationInfo, UserHandle.of(userId), permName).canBeGranted()) {
                    Log.e(TAG, "Cannot grant soft restricted permission " + permName + " for package " + packageName);
                    return;
                } else if (bp.isDevelopment()) {
                    if (permissionsState.grantInstallPermission(bp) != -1 && callback != null) {
                        callback.onInstallPermissionGranted();
                        return;
                    }
                    return;
                } else if (ps.getInstantApp(userId) && !bp.isInstant()) {
                    throw new SecurityException("Cannot grant non-ephemeral permission" + permName + " for package " + packageName);
                } else if (pkg.applicationInfo.targetSdkVersion < 23) {
                    Slog.w(TAG, "Cannot grant runtime permission to a legacy app");
                    return;
                } else {
                    int result = permissionsState.grantRuntimePermission(bp, userId);
                    if (result == -1) {
                        return;
                    }
                    if (result == 1 && callback != null) {
                        callback.onGidsChanged(UserHandle.getAppId(pkg.applicationInfo.uid), userId);
                    }
                    if (bp.isRuntime()) {
                        logPermission(1243, permName, packageName);
                    }
                    if (callback != null) {
                        callback.onPermissionGranted(uid, userId);
                    }
                    if (bp.isRuntime()) {
                        notifyRuntimePermissionStateChanged(packageName, userId);
                    }
                    if ("android.permission.READ_EXTERNAL_STORAGE".equals(permName) || "android.permission.WRITE_EXTERNAL_STORAGE".equals(permName)) {
                        long token = Binder.clearCallingIdentity();
                        try {
                            if (this.mUserManagerInt.isUserInitialized(userId)) {
                                StorageManagerInternal storageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
                                storageManagerInternal.onExternalStoragePolicyChanged(uid, packageName);
                            }
                            return;
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                    return;
                }
            }
        }
        Log.e(TAG, "Unknown package: " + packageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void revokeRuntimePermission(String permName, String packageName, boolean overridePolicy, int userId, PermissionManagerServiceInternal.PermissionCallback callback) {
        if (!this.mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.REVOKE_RUNTIME_PERMISSIONS", "revokeRuntimePermission");
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, true, false, "revokeRuntimePermission");
        PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            Log.e(TAG, "Unknown package: " + packageName);
        } else if (this.mPackageManagerInt.filterAppAccess(pkg, Binder.getCallingUid(), userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        } else {
            BasePermission bp = this.mSettings.getPermissionLocked(permName);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            }
            bp.enforceDeclaredUsedAndRuntimeOrDevelopment(pkg);
            if (pkg.applicationInfo.targetSdkVersion < 23 && bp.isRuntime()) {
                return;
            }
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            PermissionsState permissionsState = ps.getPermissionsState();
            int flags = permissionsState.getPermissionFlags(permName, userId);
            if ((flags & 16) != 0 && UserHandle.getCallingAppId() != 1000) {
                throw new SecurityException("Non-System UID cannot revoke system fixed permission " + permName + " for package " + packageName);
            } else if (!overridePolicy && (flags & 4) != 0) {
                throw new SecurityException("Cannot revoke policy fixed permission " + permName + " for package " + packageName);
            } else if (bp.isDevelopment()) {
                if (permissionsState.revokeInstallPermission(bp) != -1 && callback != null) {
                    callback.onInstallPermissionRevoked();
                }
            } else if (!permissionsState.hasRuntimePermission(permName, userId) || permissionsState.revokeRuntimePermission(bp, userId) == -1) {
            } else {
                if (bp.isRuntime()) {
                    logPermission(1245, permName, packageName);
                }
                if (callback != null) {
                    callback.onPermissionRevoked(pkg.applicationInfo.uid, userId);
                }
                if (bp.isRuntime()) {
                    notifyRuntimePermissionStateChanged(packageName, userId);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setWhitelistedRestrictedPermissions(PackageParser.Package pkg, int[] userIds, List<String> permissions, int callingUid, int whitelistFlags, PermissionManagerServiceInternal.PermissionCallback callback) {
        boolean updatePermissions;
        boolean updatePermissions2;
        int i;
        int i2;
        int i3;
        int newFlags;
        int mask;
        int userId;
        int permissionCount;
        SparseArray<ArraySet<String>> oldGrantedRestrictedPermissionsByUser;
        PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }
        PermissionsState permissionsState = ps.getPermissionsState();
        SparseArray<ArraySet<String>> oldGrantedRestrictedPermissionsByUser2 = new SparseArray<>();
        boolean updatePermissions3 = false;
        int permissionCount2 = pkg.requestedPermissions.size();
        int length = userIds.length;
        int i4 = 0;
        while (i4 < length) {
            int userId2 = userIds[i4];
            int i5 = 0;
            while (i5 < permissionCount2) {
                String permissionName = (String) pkg.requestedPermissions.get(i5);
                BasePermission bp = this.mSettings.getPermissionLocked(permissionName);
                if (bp == null) {
                    StringBuilder sb = new StringBuilder();
                    updatePermissions2 = updatePermissions3;
                    sb.append("Cannot whitelist unknown permission: ");
                    sb.append(permissionName);
                    Slog.w(TAG, sb.toString());
                    i = i5;
                    i2 = i4;
                    i3 = length;
                } else {
                    updatePermissions2 = updatePermissions3;
                    boolean updatePermissions4 = bp.isHardOrSoftRestricted();
                    if (!updatePermissions4) {
                        i = i5;
                        i2 = i4;
                        i3 = length;
                    } else {
                        if (permissionsState.hasPermission(permissionName, userId2)) {
                            if (oldGrantedRestrictedPermissionsByUser2.get(userId2) == null) {
                                oldGrantedRestrictedPermissionsByUser2.put(userId2, new ArraySet<>());
                            }
                            oldGrantedRestrictedPermissionsByUser2.get(userId2).add(permissionName);
                        }
                        int oldFlags = permissionsState.getPermissionFlags(permissionName, userId2);
                        int newFlags2 = oldFlags;
                        int whitelistFlagsCopy = whitelistFlags;
                        i = i5;
                        int mask2 = 0;
                        while (true) {
                            i2 = i4;
                            if (whitelistFlagsCopy == 0) {
                                break;
                            }
                            int i6 = length;
                            int flag = 1 << Integer.numberOfTrailingZeros(whitelistFlagsCopy);
                            whitelistFlagsCopy &= ~flag;
                            if (flag == 1) {
                                mask2 |= 4096;
                                if (permissions != null && permissions.contains(permissionName)) {
                                    newFlags2 |= 4096;
                                } else {
                                    newFlags2 &= -4097;
                                }
                            } else if (flag == 2) {
                                mask2 |= 2048;
                                if (permissions != null && permissions.contains(permissionName)) {
                                    newFlags2 |= 2048;
                                } else {
                                    newFlags2 &= -2049;
                                }
                            } else if (flag == 4) {
                                mask2 |= 8192;
                                if (permissions != null && permissions.contains(permissionName)) {
                                    newFlags2 |= 8192;
                                } else {
                                    newFlags2 &= -8193;
                                }
                            }
                            i4 = i2;
                            length = i6;
                        }
                        i3 = length;
                        if (oldFlags != newFlags2) {
                            updatePermissions2 = true;
                            boolean wasWhitelisted = (oldFlags & 14336) != 0;
                            boolean isWhitelisted = (newFlags2 & 14336) != 0;
                            if ((oldFlags & 4) != 0) {
                                boolean isGranted = permissionsState.hasPermission(permissionName, userId2);
                                if (!isWhitelisted && isGranted) {
                                    mask2 |= 4;
                                    newFlags2 &= -5;
                                }
                            }
                            if (pkg.applicationInfo.targetSdkVersion < 23 && !wasWhitelisted && isWhitelisted) {
                                newFlags = newFlags2 | 64;
                                mask = mask2 | 64;
                            } else {
                                newFlags = newFlags2;
                                mask = mask2;
                            }
                            userId = userId2;
                            permissionCount = permissionCount2;
                            oldGrantedRestrictedPermissionsByUser = oldGrantedRestrictedPermissionsByUser2;
                            updatePermissionFlags(permissionName, pkg.packageName, mask, newFlags, callingUid, userId, false, null);
                            updatePermissions3 = updatePermissions2;
                            i5 = i + 1;
                            i4 = i2;
                            length = i3;
                            oldGrantedRestrictedPermissionsByUser2 = oldGrantedRestrictedPermissionsByUser;
                            userId2 = userId;
                            permissionCount2 = permissionCount;
                        }
                    }
                }
                userId = userId2;
                permissionCount = permissionCount2;
                oldGrantedRestrictedPermissionsByUser = oldGrantedRestrictedPermissionsByUser2;
                updatePermissions3 = updatePermissions2;
                i5 = i + 1;
                i4 = i2;
                length = i3;
                oldGrantedRestrictedPermissionsByUser2 = oldGrantedRestrictedPermissionsByUser;
                userId2 = userId;
                permissionCount2 = permissionCount;
            }
            i4++;
        }
        SparseArray<ArraySet<String>> oldGrantedRestrictedPermissionsByUser3 = oldGrantedRestrictedPermissionsByUser2;
        if (updatePermissions3) {
            restorePermissionState(pkg, false, pkg.packageName, callback);
            int oldGrantedRestrictedPermissionsByUserCount = oldGrantedRestrictedPermissionsByUser3.size();
            int j = 0;
            while (j < oldGrantedRestrictedPermissionsByUserCount) {
                SparseArray<ArraySet<String>> oldGrantedRestrictedPermissionsByUser4 = oldGrantedRestrictedPermissionsByUser3;
                int userId3 = oldGrantedRestrictedPermissionsByUser4.keyAt(j);
                ArraySet<String> oldGrantedRestrictedPermissions = oldGrantedRestrictedPermissionsByUser4.valueAt(j);
                int oldGrantedCount = oldGrantedRestrictedPermissions.size();
                int i7 = 0;
                while (true) {
                    if (i7 >= oldGrantedCount) {
                        updatePermissions = updatePermissions3;
                        break;
                    }
                    String permission = oldGrantedRestrictedPermissions.valueAt(i7);
                    updatePermissions = updatePermissions3;
                    if (ps.getPermissionsState().hasPermission(permission, userId3)) {
                        i7++;
                        updatePermissions3 = updatePermissions;
                    } else {
                        callback.onPermissionRevoked(pkg.applicationInfo.uid, userId3);
                        break;
                    }
                }
                j++;
                oldGrantedRestrictedPermissionsByUser3 = oldGrantedRestrictedPermissionsByUser4;
                updatePermissions3 = updatePermissions;
            }
        }
    }

    @GuardedBy({"mLock"})
    private int[] revokeUnusedSharedUserPermissionsLocked(SharedUserSetting suSetting, int[] allUserIds) {
        char c;
        boolean z;
        boolean z2;
        char c2;
        BasePermission bp;
        PermissionManagerService permissionManagerService = this;
        ArraySet<String> usedPermissions = new ArraySet<>();
        List<PackageParser.Package> pkgList = suSetting.getPackages();
        if (pkgList == null || pkgList.size() == 0) {
            return EmptyArray.INT;
        }
        for (PackageParser.Package pkg : pkgList) {
            if (pkg.requestedPermissions != null) {
                int requestedPermCount = pkg.requestedPermissions.size();
                for (int j = 0; j < requestedPermCount; j++) {
                    String permission = (String) pkg.requestedPermissions.get(j);
                    if (permissionManagerService.mSettings.getPermissionLocked(permission) != null) {
                        usedPermissions.add(permission);
                    }
                }
            }
        }
        PermissionsState permissionsState = suSetting.getPermissionsState();
        List<PermissionsState.PermissionState> installPermStates = permissionsState.getInstallPermissionStates();
        int installPermCount = installPermStates.size();
        int i = installPermCount - 1;
        while (true) {
            c = 64511;
            z = false;
            if (i < 0) {
                break;
            }
            PermissionsState.PermissionState permissionState = installPermStates.get(i);
            if (!usedPermissions.contains(permissionState.getName()) && (bp = permissionManagerService.mSettings.getPermissionLocked(permissionState.getName())) != null) {
                permissionsState.revokeInstallPermission(bp);
                permissionsState.updatePermissionFlags(bp, -1, 64511, 0);
            }
            i--;
        }
        int[] runtimePermissionChangedUserIds = EmptyArray.INT;
        int length = allUserIds.length;
        int[] runtimePermissionChangedUserIds2 = runtimePermissionChangedUserIds;
        int i2 = 0;
        while (i2 < length) {
            int userId = allUserIds[i2];
            List<PermissionsState.PermissionState> runtimePermStates = permissionsState.getRuntimePermissionStates(userId);
            int runtimePermCount = runtimePermStates.size();
            int i3 = runtimePermCount - 1;
            while (i3 >= 0) {
                PermissionsState.PermissionState permissionState2 = runtimePermStates.get(i3);
                if (usedPermissions.contains(permissionState2.getName())) {
                    z2 = z;
                    c2 = 64511;
                } else {
                    BasePermission bp2 = permissionManagerService.mSettings.getPermissionLocked(permissionState2.getName());
                    if (bp2 == null) {
                        z2 = false;
                        c2 = 64511;
                    } else {
                        permissionsState.revokeRuntimePermission(bp2, userId);
                        z2 = false;
                        c2 = 64511;
                        permissionsState.updatePermissionFlags(bp2, userId, 64511, 0);
                        runtimePermissionChangedUserIds2 = ArrayUtils.appendInt(runtimePermissionChangedUserIds2, userId);
                    }
                }
                i3--;
                c = c2;
                z = z2;
                permissionManagerService = this;
            }
            i2++;
            z = z;
            permissionManagerService = this;
        }
        return runtimePermissionChangedUserIds2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String[] getAppOpPermissionPackages(String permName) {
        if (this.mPackageManagerInt.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (this.mLock) {
            ArraySet<String> pkgs = this.mSettings.mAppOpPermissionPackages.get(permName);
            if (pkgs == null) {
                return null;
            }
            return (String[]) pkgs.toArray(new String[pkgs.size()]);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getPermissionFlags(String permName, String packageName, int callingUid, int userId) {
        if (this.mUserManagerInt.exists(userId)) {
            enforceGrantRevokeGetRuntimePermissionPermissions("getPermissionFlags");
            enforceCrossUserPermission(callingUid, userId, true, false, false, "getPermissionFlags");
            PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
            if (pkg == null || pkg.mExtras == null) {
                return 0;
            }
            synchronized (this.mLock) {
                if (this.mSettings.getPermissionLocked(permName) == null) {
                    return 0;
                }
                if (this.mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
                    return 0;
                }
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                PermissionsState permissionsState = ps.getPermissionsState();
                return permissionsState.getPermissionFlags(permName, userId);
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePermissions(String packageName, PackageParser.Package pkg, boolean replaceGrant, Collection<PackageParser.Package> allPackages, PermissionManagerServiceInternal.PermissionCallback callback) {
        int flags = (replaceGrant ? 2 : 0) | (pkg != null ? 1 : 0);
        updatePermissions(packageName, pkg, getVolumeUuidForPackage(pkg), flags, allPackages, callback);
        if (pkg != null && pkg.childPackages != null) {
            Iterator it = pkg.childPackages.iterator();
            while (it.hasNext()) {
                PackageParser.Package childPkg = (PackageParser.Package) it.next();
                updatePermissions(childPkg.packageName, childPkg, getVolumeUuidForPackage(childPkg), flags, allPackages, callback);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateAllPermissions(String volumeUuid, boolean sdkUpdated, Collection<PackageParser.Package> allPackages, PermissionManagerServiceInternal.PermissionCallback callback) {
        int i;
        if (sdkUpdated) {
            i = 6;
        } else {
            i = 0;
        }
        int flags = i | 1;
        updatePermissions(null, null, volumeUuid, flags, allPackages, callback);
    }

    private void updatePermissions(String changingPkgName, PackageParser.Package changingPkg, String replaceVolumeUuid, int flags, Collection<PackageParser.Package> allPackages, PermissionManagerServiceInternal.PermissionCallback callback) {
        int flags2 = updatePermissions(changingPkgName, changingPkg, updatePermissionTrees(changingPkgName, changingPkg, flags), callback);
        synchronized (this.mLock) {
            if (this.mBackgroundPermissions == null) {
                this.mBackgroundPermissions = new ArrayMap<>();
                for (BasePermission bp : this.mSettings.getAllPermissionsLocked()) {
                    if (bp.perm != null && bp.perm.info != null && bp.perm.info.backgroundPermission != null) {
                        String fgPerm = bp.name;
                        String bgPerm = bp.perm.info.backgroundPermission;
                        List<String> fgPerms = this.mBackgroundPermissions.get(bgPerm);
                        if (fgPerms == null) {
                            fgPerms = new ArrayList();
                            this.mBackgroundPermissions.put(bgPerm, fgPerms);
                        }
                        fgPerms.add(fgPerm);
                    }
                }
            }
        }
        Trace.traceBegin(262144L, "restorePermissionState");
        boolean replace = false;
        if ((flags2 & 1) != 0) {
            for (PackageParser.Package pkg : allPackages) {
                if (pkg != changingPkg) {
                    String volumeUuid = getVolumeUuidForPackage(pkg);
                    restorePermissionState(pkg, (flags2 & 4) != 0 && Objects.equals(replaceVolumeUuid, volumeUuid), changingPkgName, callback);
                }
            }
        }
        if (changingPkg != null) {
            String volumeUuid2 = getVolumeUuidForPackage(changingPkg);
            if ((flags2 & 2) != 0 && Objects.equals(replaceVolumeUuid, volumeUuid2)) {
                replace = true;
            }
            restorePermissionState(changingPkg, replace, changingPkgName, callback);
        }
        Trace.traceEnd(262144L);
    }

    private int updatePermissions(String packageName, PackageParser.Package pkg, int flags, final PermissionManagerServiceInternal.PermissionCallback callback) {
        Set<BasePermission> needsUpdate = null;
        synchronized (this.mLock) {
            Iterator<BasePermission> it = this.mSettings.mPermissions.values().iterator();
            while (it.hasNext()) {
                final BasePermission bp = it.next();
                if (bp.isDynamic()) {
                    bp.updateDynamicPermission(this.mSettings.mPermissionTrees.values());
                }
                if (bp.getSourcePackageSetting() != null) {
                    if (packageName != null && packageName.equals(bp.getSourcePackageName()) && (pkg == null || !hasPermission(pkg, bp.getName()))) {
                        Slog.i(TAG, "Removing old permission tree: " + bp.getName() + " from package " + bp.getSourcePackageName());
                        if (bp.isRuntime()) {
                            int[] userIds = this.mUserManagerInt.getUserIds();
                            for (final int userId : userIds) {
                                this.mPackageManagerInt.forEachPackage(new Consumer() { // from class: com.android.server.pm.permission.-$$Lambda$PermissionManagerService$w2aPgVKY5ZkiKKZQUVsj6t4Bn4c
                                    @Override // java.util.function.Consumer
                                    public final void accept(Object obj) {
                                        PermissionManagerService.this.lambda$updatePermissions$1$PermissionManagerService(bp, userId, callback, (PackageParser.Package) obj);
                                    }
                                });
                            }
                        } else {
                            this.mPackageManagerInt.forEachPackage(new Consumer() { // from class: com.android.server.pm.permission.-$$Lambda$PermissionManagerService$C766Q8ipIOp2T5Ab8p3YEsiC8m0
                                @Override // java.util.function.Consumer
                                public final void accept(Object obj) {
                                    PermissionManagerService.lambda$updatePermissions$2(BasePermission.this, (PackageParser.Package) obj);
                                }
                            });
                        }
                        flags |= 1;
                        it.remove();
                    }
                } else {
                    if (needsUpdate == null) {
                        needsUpdate = new ArraySet<>(this.mSettings.mPermissions.size());
                    }
                    needsUpdate.add(bp);
                }
            }
        }
        if (needsUpdate != null) {
            for (BasePermission bp2 : needsUpdate) {
                PackageParser.Package sourcePkg = this.mPackageManagerInt.getPackage(bp2.getSourcePackageName());
                synchronized (this.mLock) {
                    if (sourcePkg != null) {
                        if (sourcePkg.mExtras != null) {
                            PackageSetting sourcePs = (PackageSetting) sourcePkg.mExtras;
                            if (bp2.getSourcePackageSetting() == null) {
                                bp2.setSourcePackageSetting(sourcePs);
                            }
                        }
                    }
                    Slog.w(TAG, "Removing dangling permission: " + bp2.getName() + " from package " + bp2.getSourcePackageName());
                    this.mSettings.removePermissionLocked(bp2.getName());
                }
            }
        }
        return flags;
    }

    public /* synthetic */ void lambda$updatePermissions$1$PermissionManagerService(BasePermission bp, int userId, PermissionManagerServiceInternal.PermissionCallback callback, PackageParser.Package p) {
        String pName = p.packageName;
        ApplicationInfo appInfo = this.mPackageManagerInt.getApplicationInfo(pName, 0, 1000, 0);
        if (appInfo != null && appInfo.targetSdkVersion < 23) {
            return;
        }
        String permissionName = bp.getName();
        int checkPermission = checkPermission(permissionName, pName, 1000, userId, false);
        if (checkPermission == 0) {
            try {
                revokeRuntimePermission(permissionName, pName, false, userId, callback);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Failed to revoke " + permissionName + " from " + pName, e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$updatePermissions$2(BasePermission bp, PackageParser.Package p) {
        PackageSetting ps = (PackageSetting) p.mExtras;
        if (ps == null) {
            return;
        }
        PermissionsState permissionsState = ps.getPermissionsState();
        if (permissionsState.getInstallPermissionState(bp.getName()) != null) {
            permissionsState.revokeInstallPermission(bp);
            permissionsState.updatePermissionFlags(bp, -1, 64511, 0);
        }
    }

    private int updatePermissionTrees(String packageName, PackageParser.Package pkg, int flags) {
        Set<BasePermission> needsUpdate = null;
        synchronized (this.mLock) {
            Iterator<BasePermission> it = this.mSettings.mPermissionTrees.values().iterator();
            while (it.hasNext()) {
                BasePermission bp = it.next();
                if (bp.getSourcePackageSetting() != null) {
                    if (packageName != null && packageName.equals(bp.getSourcePackageName()) && (pkg == null || !hasPermission(pkg, bp.getName()))) {
                        Slog.i(TAG, "Removing old permission tree: " + bp.getName() + " from package " + bp.getSourcePackageName());
                        flags |= 1;
                        it.remove();
                    }
                } else {
                    if (needsUpdate == null) {
                        needsUpdate = new ArraySet<>(this.mSettings.mPermissionTrees.size());
                    }
                    needsUpdate.add(bp);
                }
            }
        }
        if (needsUpdate != null) {
            for (BasePermission bp2 : needsUpdate) {
                PackageParser.Package sourcePkg = this.mPackageManagerInt.getPackage(bp2.getSourcePackageName());
                synchronized (this.mLock) {
                    if (sourcePkg != null) {
                        if (sourcePkg.mExtras != null) {
                            PackageSetting sourcePs = (PackageSetting) sourcePkg.mExtras;
                            if (bp2.getSourcePackageSetting() == null) {
                                bp2.setSourcePackageSetting(sourcePs);
                            }
                        }
                    }
                    Slog.w(TAG, "Removing dangling permission tree: " + bp2.getName() + " from package " + bp2.getSourcePackageName());
                    this.mSettings.removePermissionLocked(bp2.getName());
                }
            }
        }
        return flags;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePermissionFlags(String permName, String packageName, int flagMask, int flagValues, int callingUid, int userId, boolean overridePolicy, PermissionManagerServiceInternal.PermissionCallback callback) {
        int flagValues2;
        int flagValues3;
        BasePermission bp;
        if (this.mUserManagerInt.exists(userId)) {
            enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlags");
            enforceCrossUserPermission(callingUid, userId, true, true, false, "updatePermissionFlags");
            if ((flagMask & 4) != 0 && !overridePolicy) {
                throw new SecurityException("updatePermissionFlags requires android.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY");
            }
            if (callingUid == 1000) {
                flagValues2 = flagMask;
                flagValues3 = flagValues;
            } else {
                flagValues3 = flagValues & (-17) & (-33) & (-65) & (-4097) & (-2049) & (-8193) & (-16385);
                flagValues2 = flagMask & (-17) & (-33);
            }
            PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
            if (pkg == null || pkg.mExtras == null) {
                Log.e(TAG, "Unknown package: " + packageName);
            } else if (this.mPackageManagerInt.filterAppAccess(pkg, callingUid, userId)) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            } else {
                synchronized (this.mLock) {
                    bp = this.mSettings.getPermissionLocked(permName);
                }
                if (bp == null) {
                    throw new IllegalArgumentException("Unknown permission: " + permName);
                }
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                PermissionsState permissionsState = ps.getPermissionsState();
                boolean hadState = permissionsState.getRuntimePermissionState(permName, userId) != null;
                boolean permissionUpdated = permissionsState.updatePermissionFlags(bp, userId, flagValues2, flagValues3);
                if (permissionUpdated && bp.isRuntime()) {
                    notifyRuntimePermissionStateChanged(packageName, userId);
                }
                if (permissionUpdated && callback != null) {
                    if (permissionsState.getInstallPermissionState(permName) != null) {
                        callback.onInstallPermissionUpdated();
                    } else if (permissionsState.getRuntimePermissionState(permName, userId) != null || hadState) {
                        callback.onPermissionUpdated(new int[]{userId}, false);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues, int callingUid, int userId, Collection<PackageParser.Package> packages, PermissionManagerServiceInternal.PermissionCallback callback) {
        if (!this.mUserManagerInt.exists(userId)) {
            return false;
        }
        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlagsForAllApps");
        enforceCrossUserPermission(callingUid, userId, true, true, false, "updatePermissionFlagsForAllApps");
        if (callingUid != 1000) {
            flagMask &= -17;
            flagValues &= -17;
        }
        boolean changed = false;
        for (PackageParser.Package pkg : packages) {
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            if (ps != null) {
                PermissionsState permissionsState = ps.getPermissionsState();
                changed |= permissionsState.updatePermissionFlagsForAllPermissions(userId, flagMask, flagValues);
            }
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceGrantRevokeRuntimePermissionPermissions(String message) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.GRANT_RUNTIME_PERMISSIONS") != 0 && this.mContext.checkCallingOrSelfPermission("android.permission.REVOKE_RUNTIME_PERMISSIONS") != 0) {
            throw new SecurityException(message + " requires android.permission.GRANT_RUNTIME_PERMISSIONS or android.permission.REVOKE_RUNTIME_PERMISSIONS");
        }
    }

    private void enforceGrantRevokeGetRuntimePermissionPermissions(String message) {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.GET_RUNTIME_PERMISSIONS") != 0 && this.mContext.checkCallingOrSelfPermission("android.permission.GRANT_RUNTIME_PERMISSIONS") != 0 && this.mContext.checkCallingOrSelfPermission("android.permission.REVOKE_RUNTIME_PERMISSIONS") != 0) {
            throw new SecurityException(message + " requires android.permission.GRANT_RUNTIME_PERMISSIONS or android.permission.REVOKE_RUNTIME_PERMISSIONS or android.permission.GET_RUNTIME_PERMISSIONS");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceCrossUserPermission(int callingUid, int userId, boolean requireFullPermission, boolean checkShell, boolean requirePermissionWhenSameUser, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction("no_debugging_features", callingUid, userId);
        }
        if ((!requirePermissionWhenSameUser && userId == UserHandle.getUserId(callingUid)) || callingUid == 1000 || callingUid == 0) {
            return;
        }
        if (requireFullPermission) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", message);
            return;
        }
        try {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", message);
        } catch (SecurityException e) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS", message);
        }
    }

    @GuardedBy({"mSettings.mLock", "mLock"})
    private int calculateCurrentPermissionFootprintLocked(BasePermission tree) {
        int size = 0;
        for (BasePermission perm : this.mSettings.mPermissions.values()) {
            size += tree.calculateFootprint(perm);
        }
        return size;
    }

    @GuardedBy({"mSettings.mLock", "mLock"})
    private void enforcePermissionCapLocked(PermissionInfo info, BasePermission tree) {
        if (tree.getUid() != 1000) {
            int curTreeSize = calculateCurrentPermissionFootprintLocked(tree);
            if (info.calculateFootprint() + curTreeSize > 32768) {
                throw new SecurityException("Permission tree size cap exceeded");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void systemReady() {
        this.mSystemReady = true;
        if (this.mPrivappPermissionsViolations != null) {
            throw new IllegalStateException("Signature|privileged permissions not in privapp-permissions whitelist: " + this.mPrivappPermissionsViolations);
        }
        this.mPermissionControllerManager = (PermissionControllerManager) this.mContext.getSystemService(PermissionControllerManager.class);
        this.mPermissionPolicyInternal = (PermissionPolicyInternal) LocalServices.getService(PermissionPolicyInternal.class);
    }

    private static String getVolumeUuidForPackage(PackageParser.Package pkg) {
        if (pkg == null) {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
        if (pkg.isExternal()) {
            if (TextUtils.isEmpty(pkg.volumeUuid)) {
                return "primary_physical";
            }
            return pkg.volumeUuid;
        }
        return StorageManager.UUID_PRIVATE_INTERNAL;
    }

    private static boolean hasPermission(PackageParser.Package pkgInfo, String permName) {
        for (int i = pkgInfo.permissions.size() - 1; i >= 0; i--) {
            if (((PackageParser.Permission) pkgInfo.permissions.get(i)).info.name.equals(permName)) {
                return true;
            }
        }
        return false;
    }

    private void logPermission(int action, String name, String packageName) {
        LogMaker log = new LogMaker(action);
        log.setPackageName(packageName);
        log.addTaggedData(1241, name);
        this.mMetricsLogger.write(log);
    }

    public ArrayMap<String, List<String>> getBackgroundPermissions() {
        return this.mBackgroundPermissions;
    }

    /* loaded from: classes.dex */
    private class PermissionManagerServiceInternalImpl extends PermissionManagerServiceInternal {
        private PermissionManagerServiceInternalImpl() {
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void systemReady() {
            PermissionManagerService.this.systemReady();
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public boolean isPermissionsReviewRequired(PackageParser.Package pkg, int userId) {
            return PermissionManagerService.this.isPermissionsReviewRequired(pkg, userId);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void revokeStoragePermissionsIfScopeExpanded(PackageParser.Package newPackage, PackageParser.Package oldPackage, PermissionManagerServiceInternal.PermissionCallback permissionCallback) {
            PermissionManagerService.this.revokeStoragePermissionsIfScopeExpanded(newPackage, oldPackage, permissionCallback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void revokeRuntimePermissionsIfGroupChanged(PackageParser.Package newPackage, PackageParser.Package oldPackage, ArrayList<String> allPackageNames, PermissionManagerServiceInternal.PermissionCallback permissionCallback) {
            PermissionManagerService.this.revokeRuntimePermissionsIfGroupChanged(newPackage, oldPackage, allPackageNames, permissionCallback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void revokeRuntimePermissionsIfPermissionDefinitionChanged(List<String> permissionsToRevoke, ArrayList<String> allPackageNames, PermissionManagerServiceInternal.PermissionCallback permissionCallback) {
            PermissionManagerService.this.revokeRuntimePermissionsIfPermissionDefinitionChanged(permissionsToRevoke, allPackageNames, permissionCallback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public List<String> addAllPermissions(PackageParser.Package pkg, boolean chatty) {
            return PermissionManagerService.this.addAllPermissions(pkg, chatty);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void addAllPermissionGroups(PackageParser.Package pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissionGroups(pkg, chatty);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void removeAllPermissions(PackageParser.Package pkg, boolean chatty) {
            PermissionManagerService.this.removeAllPermissions(pkg, chatty);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public boolean addDynamicPermission(PermissionInfo info, boolean async, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
            return PermissionManagerService.this.addDynamicPermission(info, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void removeDynamicPermission(String permName, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.removeDynamicPermission(permName, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void grantRuntimePermission(String permName, String packageName, boolean overridePolicy, int callingUid, int userId, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.grantRuntimePermission(permName, packageName, overridePolicy, callingUid, userId, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void grantRequestedRuntimePermissions(PackageParser.Package pkg, int[] userIds, String[] grantedPermissions, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.grantRequestedRuntimePermissions(pkg, userIds, grantedPermissions, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public List<String> getWhitelistedRestrictedPermissions(PackageParser.Package pkg, int whitelistFlags, int userId) {
            return PermissionManagerService.this.getWhitelistedRestrictedPermissions(pkg, whitelistFlags, userId);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void setWhitelistedRestrictedPermissions(PackageParser.Package pkg, int[] userIds, List<String> permissions, int callingUid, int whitelistFlags, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.setWhitelistedRestrictedPermissions(pkg, userIds, permissions, callingUid, whitelistFlags, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void grantRuntimePermissionsGrantedToDisabledPackage(PackageParser.Package pkg, int callingUid, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.grantRuntimePermissionsGrantedToDisabledPackageLocked(pkg, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void revokeRuntimePermission(String permName, String packageName, boolean overridePolicy, int userId, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.revokeRuntimePermission(permName, packageName, overridePolicy, userId, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void updatePermissions(String packageName, PackageParser.Package pkg, boolean replaceGrant, Collection<PackageParser.Package> allPackages, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.updatePermissions(packageName, pkg, replaceGrant, allPackages, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void updateAllPermissions(String volumeUuid, boolean sdkUpdated, Collection<PackageParser.Package> allPackages, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.updateAllPermissions(volumeUuid, sdkUpdated, allPackages, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public String[] getAppOpPermissionPackages(String permName) {
            return PermissionManagerService.this.getAppOpPermissionPackages(permName);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public int getPermissionFlags(String permName, String packageName, int callingUid, int userId) {
            return PermissionManagerService.this.getPermissionFlags(permName, packageName, callingUid, userId);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void updatePermissionFlags(String permName, String packageName, int flagMask, int flagValues, int callingUid, int userId, boolean overridePolicy, PermissionManagerServiceInternal.PermissionCallback callback) {
            PermissionManagerService.this.updatePermissionFlags(permName, packageName, flagMask, flagValues, callingUid, userId, overridePolicy, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues, int callingUid, int userId, Collection<PackageParser.Package> packages, PermissionManagerServiceInternal.PermissionCallback callback) {
            return PermissionManagerService.this.updatePermissionFlagsForAllApps(flagMask, flagValues, callingUid, userId, packages, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void enforceCrossUserPermission(int callingUid, int userId, boolean requireFullPermission, boolean checkShell, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId, requireFullPermission, checkShell, false, message);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void enforceCrossUserPermission(int callingUid, int userId, boolean requireFullPermission, boolean checkShell, boolean requirePermissionWhenSameUser, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId, requireFullPermission, checkShell, requirePermissionWhenSameUser, message);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public void enforceGrantRevokeRuntimePermissionPermissions(String message) {
            PermissionManagerService.this.enforceGrantRevokeRuntimePermissionPermissions(message);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public int checkPermission(String permName, String packageName, int callingUid, int userId) {
            return PermissionManagerService.this.checkPermission(permName, packageName, callingUid, userId);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public int checkUidPermission(String permName, PackageParser.Package pkg, int uid, int callingUid) {
            return PermissionManagerService.this.checkUidPermission(permName, pkg, uid, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags, int callingUid) {
            return PermissionManagerService.this.getPermissionGroupInfo(groupName, flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public List<PermissionGroupInfo> getAllPermissionGroups(int flags, int callingUid) {
            return PermissionManagerService.this.getAllPermissionGroups(flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public PermissionInfo getPermissionInfo(String permName, String packageName, int flags, int callingUid) {
            return PermissionManagerService.this.getPermissionInfo(permName, packageName, flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public List<PermissionInfo> getPermissionInfoByGroup(String group, int flags, int callingUid) {
            return PermissionManagerService.this.getPermissionInfoByGroup(group, flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public PermissionSettings getPermissionSettings() {
            return PermissionManagerService.this.mSettings;
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy() {
            return PermissionManagerService.this.mDefaultPermissionGrantPolicy;
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public BasePermission getPermissionTEMP(String permName) {
            BasePermission permissionLocked;
            synchronized (PermissionManagerService.this.mLock) {
                permissionLocked = PermissionManagerService.this.mSettings.getPermissionLocked(permName);
            }
            return permissionLocked;
        }

        @Override // com.android.server.pm.permission.PermissionManagerServiceInternal
        public ArrayList<PermissionInfo> getAllPermissionWithProtectionLevel(int protectionLevel) {
            ArrayList<PermissionInfo> matchingPermissions = new ArrayList<>();
            synchronized (PermissionManagerService.this.mLock) {
                int numTotalPermissions = PermissionManagerService.this.mSettings.mPermissions.size();
                for (int i = 0; i < numTotalPermissions; i++) {
                    BasePermission bp = PermissionManagerService.this.mSettings.mPermissions.valueAt(i);
                    if (bp.perm != null && bp.perm.info != null && bp.protectionLevel == protectionLevel) {
                        matchingPermissions.add(bp.perm.info);
                    }
                }
            }
            return matchingPermissions;
        }

        public byte[] backupRuntimePermissions(UserHandle user) {
            return PermissionManagerService.this.backupRuntimePermissions(user);
        }

        public void restoreRuntimePermissions(byte[] backup, UserHandle user) {
            PermissionManagerService.this.restoreRuntimePermissions(backup, user);
        }

        public void restoreDelayedRuntimePermissions(String packageName, UserHandle user) {
            PermissionManagerService.this.restoreDelayedRuntimePermissions(packageName, user);
        }

        public void addOnRuntimePermissionStateChangedListener(PermissionManagerInternal.OnRuntimePermissionStateChangedListener listener) {
            PermissionManagerService.this.addOnRuntimePermissionStateChangedListener(listener);
        }

        public void removeOnRuntimePermissionStateChangedListener(PermissionManagerInternal.OnRuntimePermissionStateChangedListener listener) {
            PermissionManagerService.this.removeOnRuntimePermissionStateChangedListener(listener);
        }
    }
}
