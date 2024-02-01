package com.android.server.pm.permission;

import android.content.Context;
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
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.SharedUserSetting;
import com.android.server.pm.permission.DefaultPermissionGrantPolicy;
import com.android.server.pm.permission.PermissionManagerInternal;
import com.android.server.pm.permission.PermissionsState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import libcore.util.EmptyArray;
/* loaded from: classes.dex */
public class PermissionManagerService {
    private static final int[] EMPTY_INT_ARRAY = new int[0];
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
    private final Context mContext;
    private final DefaultPermissionGrantPolicy mDefaultPermissionGrantPolicy;
    private final int[] mGlobalGids;
    private final Handler mHandler;
    private final Object mLock;
    private PermissionManagerPolicy mPermissionPolicy;
    @GuardedBy("mLock")
    private ArraySet<String> mPrivappPermissionsViolations;
    @GuardedBy("mLock")
    private final PermissionSettings mSettings;
    private final SparseArray<ArraySet<String>> mSystemPermissions;
    @GuardedBy("mLock")
    private boolean mSystemReady;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private final PackageManagerInternal mPackageManagerInt = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
    private final UserManagerInternal mUserManagerInt = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
    private final HandlerThread mHandlerThread = new ServiceThread(TAG, 10, true);

    PermissionManagerService(Context context, DefaultPermissionGrantPolicy.DefaultPermissionGrantedCallback defaultGrantCallback, Object externalLock) {
        this.mContext = context;
        this.mLock = externalLock;
        this.mSettings = new PermissionSettings(context, this.mLock);
        this.mHandlerThread.start();
        this.mHandler = new Handler(this.mHandlerThread.getLooper());
        Watchdog.getInstance().addThread(this.mHandler);
        this.mDefaultPermissionGrantPolicy = new DefaultPermissionGrantPolicy(context, this.mHandlerThread.getLooper(), defaultGrantCallback, this);
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
        LocalServices.addService(PermissionManagerInternal.class, new PermissionManagerInternalImpl());
        this.mPermissionPolicy = new PermissionManagerPolicy(this.mContext, this.mHandler);
    }

    public static PermissionManagerInternal create(Context context, DefaultPermissionGrantPolicy.DefaultPermissionGrantedCallback defaultGrantCallback, Object externalLock) {
        PermissionManagerInternal permMgrInt = (PermissionManagerInternal) LocalServices.getService(PermissionManagerInternal.class);
        if (permMgrInt != null) {
            return permMgrInt;
        }
        new PermissionManagerService(context, defaultGrantCallback, externalLock);
        return (PermissionManagerInternal) LocalServices.getService(PermissionManagerInternal.class);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BasePermission getPermission(String permName) {
        BasePermission permissionLocked;
        synchronized (this.mLock) {
            permissionLocked = this.mSettings.getPermissionLocked(permName);
        }
        return permissionLocked;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int checkPermission(String permName, String pkgName, int callingUid, int userId) {
        if (this.mUserManagerInt.exists(userId)) {
            if (this.mPermissionPolicy != null) {
                int result = this.mPermissionPolicy.enforceGrantPermission(pkgName, permName);
                Log.i(TAG, "checkPermissionPolicy  result is " + result + " permName:" + permName + " pkgName:" + pkgName);
                switch (result) {
                    case 1:
                        return 0;
                    case 2:
                        return 2;
                }
            }
            PackageParser.Package pkg = this.mPackageManagerInt.getPackage(pkgName);
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
            return ("android.permission.ACCESS_COARSE_LOCATION".equals(permName) && permissionsState.hasPermission("android.permission.ACCESS_FINE_LOCATION", userId)) ? 0 : -1;
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
            if (this.mPermissionPolicy != null) {
                if (this.mPermissionPolicy.enforceGrantPermission(pkg != null ? pkg.packageName : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, permName) == 1) {
                    return 0;
                }
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
                if (permissionsState.hasPermission(permName, userId) && (!isUidInstantApp || this.mSettings.isPermissionInstant(permName))) {
                    return 0;
                }
                if ("android.permission.ACCESS_COARSE_LOCATION".equals(permName) && permissionsState.hasPermission("android.permission.ACCESS_FINE_LOCATION", userId)) {
                    return 0;
                }
            } else {
                ArraySet<String> perms = this.mSystemPermissions.get(uid);
                if (perms != null) {
                    if (perms.contains(permName)) {
                        return 0;
                    }
                    if ("android.permission.ACCESS_COARSE_LOCATION".equals(permName) && perms.contains("android.permission.ACCESS_FINE_LOCATION")) {
                        return 0;
                    }
                }
            }
            return -1;
        }
        return -1;
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
                try {
                    if (!this.mSettings.mPermissionGroups.containsKey(groupName)) {
                        return null;
                    }
                } catch (Throwable th) {
                    throw th;
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
    public void revokeRuntimePermissionsIfGroupChanged(PackageParser.Package newPackage, PackageParser.Package oldPackage, ArrayList<String> allPackageNames, PermissionManagerInternal.PermissionCallback permissionCallback) {
        int numOldPackagePermissions;
        int userIdNum;
        int numUserIds;
        int[] userIds;
        String oldPermissionGroupName;
        String newPermissionGroupName;
        String permissionName;
        PackageParser.Permission newPermission;
        int numOldPackagePermissions2 = oldPackage.permissions.size();
        ArrayMap<String, String> oldPermissionNameToGroupName = new ArrayMap<>(numOldPackagePermissions2);
        for (int i = 0; i < numOldPackagePermissions2; i++) {
            PackageParser.Permission permission = (PackageParser.Permission) oldPackage.permissions.get(i);
            if (permission.group != null) {
                oldPermissionNameToGroupName.put(permission.info.name, permission.group.info.name);
            }
        }
        int numNewPackagePermissions = newPackage.permissions.size();
        int newPermissionNum = 0;
        while (true) {
            int newPermissionNum2 = newPermissionNum;
            if (newPermissionNum2 >= numNewPackagePermissions) {
                return;
            }
            PackageParser.Permission newPermission2 = (PackageParser.Permission) newPackage.permissions.get(newPermissionNum2);
            int newProtection = newPermission2.info.getProtection();
            if ((newProtection & 1) != 0) {
                String permissionName2 = newPermission2.info.name;
                String newPermissionGroupName2 = newPermission2.group == null ? null : newPermission2.group.info.name;
                String oldPermissionGroupName2 = oldPermissionNameToGroupName.get(permissionName2);
                if (newPermissionGroupName2 != null && !newPermissionGroupName2.equals(oldPermissionGroupName2)) {
                    int[] userIds2 = this.mUserManagerInt.getUserIds();
                    int numUserIds2 = userIds2.length;
                    int userIdNum2 = 0;
                    while (true) {
                        int userIdNum3 = userIdNum2;
                        if (userIdNum3 < numUserIds2) {
                            int userId = userIds2[userIdNum3];
                            int numPackages = allPackageNames.size();
                            int packageNum = 0;
                            while (true) {
                                int packageNum2 = packageNum;
                                numOldPackagePermissions = numOldPackagePermissions2;
                                if (packageNum2 < numPackages) {
                                    int numPackages2 = numPackages;
                                    String packageName = allPackageNames.get(packageNum2);
                                    ArrayMap<String, String> oldPermissionNameToGroupName2 = oldPermissionNameToGroupName;
                                    if (checkPermission(permissionName2, packageName, 0, userId) != 0) {
                                        userIdNum = userIdNum3;
                                        numUserIds = numUserIds2;
                                        userIds = userIds2;
                                        oldPermissionGroupName = oldPermissionGroupName2;
                                        newPermissionGroupName = newPermissionGroupName2;
                                        permissionName = permissionName2;
                                        newPermission = newPermission2;
                                    } else {
                                        EventLog.writeEvent(1397638484, "72710897", Integer.valueOf(newPackage.applicationInfo.uid), "Revoking permission " + permissionName2 + " from package " + packageName + " as the group changed from " + oldPermissionGroupName2 + " to " + newPermissionGroupName2);
                                        userIdNum = userIdNum3;
                                        numUserIds = numUserIds2;
                                        userIds = userIds2;
                                        oldPermissionGroupName = oldPermissionGroupName2;
                                        newPermissionGroupName = newPermissionGroupName2;
                                        permissionName = permissionName2;
                                        newPermission = newPermission2;
                                        try {
                                            revokeRuntimePermission(permissionName2, packageName, false, 1000, userId, permissionCallback);
                                        } catch (IllegalArgumentException e) {
                                            Slog.e(TAG, "Could not revoke " + permissionName + " from " + packageName, e);
                                        }
                                    }
                                    packageNum = packageNum2 + 1;
                                    permissionName2 = permissionName;
                                    userIdNum3 = userIdNum;
                                    numUserIds2 = numUserIds;
                                    numOldPackagePermissions2 = numOldPackagePermissions;
                                    numPackages = numPackages2;
                                    oldPermissionNameToGroupName = oldPermissionNameToGroupName2;
                                    userIds2 = userIds;
                                    oldPermissionGroupName2 = oldPermissionGroupName;
                                    newPermissionGroupName2 = newPermissionGroupName;
                                    newPermission2 = newPermission;
                                }
                            }
                            userIdNum2 = userIdNum3 + 1;
                            numOldPackagePermissions2 = numOldPackagePermissions;
                            oldPermissionNameToGroupName = oldPermissionNameToGroupName;
                        }
                    }
                }
            }
            newPermissionNum = newPermissionNum2 + 1;
            numOldPackagePermissions2 = numOldPackagePermissions2;
            oldPermissionNameToGroupName = oldPermissionNameToGroupName;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addAllPermissions(PackageParser.Package pkg, boolean chatty) {
        int N = pkg.permissions.size();
        for (int i = 0; i < N; i++) {
            PackageParser.Permission p = (PackageParser.Permission) pkg.permissions.get(i);
            p.info.flags &= -1073741825;
            synchronized (this.mLock) {
                if (pkg.applicationInfo.targetSdkVersion > 22) {
                    p.group = this.mSettings.mPermissionGroups.get(p.info.group);
                    if (PackageManagerService.DEBUG_PERMISSIONS && p.info.group != null && p.group == null) {
                        Slog.i(TAG, "Permission " + p.info.name + " from package " + p.info.packageName + " in an unknown group " + p.info.group);
                    }
                }
                if (p.tree) {
                    BasePermission bp = BasePermission.createOrUpdate(this.mSettings.getPermissionTreeLocked(p.info.name), p, pkg, this.mSettings.getAllPermissionTreesLocked(), chatty);
                    this.mSettings.putPermissionTreeLocked(p.info.name, bp);
                } else {
                    BasePermission bp2 = BasePermission.createOrUpdate(this.mSettings.getPermissionLocked(p.info.name), p, pkg, this.mSettings.getAllPermissionTreesLocked(), chatty);
                    this.mSettings.putPermissionLocked(p.info.name, bp2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addAllPermissionGroups(PackageParser.Package pkg, boolean chatty) {
        int N = pkg.permissionGroups.size();
        StringBuilder r = null;
        for (int i = 0; i < N; i++) {
            PackageParser.PermissionGroup pg = (PackageParser.PermissionGroup) pkg.permissionGroups.get(i);
            PackageParser.PermissionGroup cur = this.mSettings.mPermissionGroups.get(pg.info.name);
            String curPackageName = cur == null ? null : cur.info.packageName;
            boolean isPackageUpdate = pg.info.packageName.equals(curPackageName);
            if (cur == null || isPackageUpdate) {
                this.mSettings.mPermissionGroups.put(pg.info.name, pg);
                if (chatty && PackageManagerService.DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    if (isPackageUpdate) {
                        r.append("UPD:");
                    }
                    r.append(pg.info.name);
                }
            } else {
                Slog.w(TAG, "Permission group " + pg.info.name + " from package " + pg.info.packageName + " ignored: original from " + cur.info.packageName);
                if (chatty && PackageManagerService.DEBUG_PACKAGE_SCANNING) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append("DUP:");
                    r.append(pg.info.name);
                }
            }
        }
        if (r != null && PackageManagerService.DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "  Permission Groups: " + ((Object) r));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeAllPermissions(PackageParser.Package pkg, boolean chatty) {
        ArraySet<String> appOpPkgs;
        ArraySet<String> appOpPkgs2;
        synchronized (this.mLock) {
            int N = pkg.permissions.size();
            StringBuilder r = null;
            for (int i = 0; i < N; i++) {
                PackageParser.Permission p = (PackageParser.Permission) pkg.permissions.get(i);
                BasePermission bp = this.mSettings.mPermissions.get(p.info.name);
                if (bp == null) {
                    bp = this.mSettings.mPermissionTrees.get(p.info.name);
                }
                if (bp != null && bp.isPermission(p)) {
                    bp.setPermission(null);
                    if (PackageManagerService.DEBUG_REMOVE && chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.info.name);
                    }
                }
                if (p.isAppOp() && (appOpPkgs2 = this.mSettings.mAppOpPermissionPackages.get(p.info.name)) != null) {
                    appOpPkgs2.remove(pkg.packageName);
                }
            }
            if (r != null && PackageManagerService.DEBUG_REMOVE) {
                Log.d(TAG, "  Permissions: " + ((Object) r));
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
            if (0 != 0 && PackageManagerService.DEBUG_REMOVE) {
                Log.d(TAG, "  Permissions: " + ((Object) null));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean addDynamicPermission(PermissionInfo info, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
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
    public void removeDynamicPermission(String permName, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:102:0x01e5
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private void grantPermissions(android.content.pm.PackageParser.Package r38, boolean r39, java.lang.String r40, com.android.server.pm.permission.PermissionManagerInternal.PermissionCallback r41) {
        /*
            Method dump skipped, instructions count: 1444
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.permission.PermissionManagerService.grantPermissions(android.content.pm.PackageParser$Package, boolean, java.lang.String, com.android.server.pm.permission.PermissionManagerInternal$PermissionCallback):void");
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
        } else {
            wlPermissions = SystemConfig.getInstance().getPrivAppPermissions(pkg.packageName);
        }
        boolean whitelisted = wlPermissions != null && wlPermissions.contains(perm);
        return whitelisted || (pkg.parentPackage != null && hasPrivappWhitelistEntry(perm, pkg.parentPackage));
    }

    private boolean grantSignaturePermission(String perm, PackageParser.Package pkg, BasePermission bp, PermissionsState origPermissions) {
        Iterator it;
        PackageSetting disabledChildPs;
        ArraySet<String> deniedPermissions;
        boolean oemPermission = bp.isOEM();
        boolean vendorPrivilegedPermission = bp.isVendorPrivileged();
        boolean privilegedPermission = bp.isPrivileged() || bp.isVendorPrivileged();
        boolean privappPermissionsDisable = RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_DISABLE;
        boolean platformPermission = PackageManagerService.PLATFORM_PACKAGE_NAME.equals(bp.getSourcePackageName());
        boolean platformPackage = PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkg.packageName);
        if (!privappPermissionsDisable && privilegedPermission && pkg.isPrivileged() && !platformPackage && platformPermission && !hasPrivappWhitelistEntry(perm, pkg)) {
            if (!this.mSystemReady && !pkg.isUpdatedSystemApp()) {
                if (pkg.isVendor()) {
                    deniedPermissions = SystemConfig.getInstance().getVendorPrivAppDenyPermissions(pkg.packageName);
                } else if (pkg.isProduct()) {
                    deniedPermissions = SystemConfig.getInstance().getProductPrivAppDenyPermissions(pkg.packageName);
                } else {
                    deniedPermissions = SystemConfig.getInstance().getPrivAppDenyPermissions(pkg.packageName);
                }
                boolean permissionViolation = deniedPermissions == null || !deniedPermissions.contains(perm);
                if (!permissionViolation) {
                    return false;
                }
                Slog.w(TAG, "Privileged permission " + perm + " for package " + pkg.packageName + " - not in privapp-permissions whitelist");
                if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                    if (this.mPrivappPermissionsViolations == null) {
                        this.mPrivappPermissionsViolations = new ArraySet<>();
                    }
                    this.mPrivappPermissionsViolations.add(pkg.packageName + ": " + perm);
                }
            }
            if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                return false;
            }
        }
        String systemPackageName = this.mPackageManagerInt.getKnownPackageName(0, 0);
        PackageParser.Package systemPackage = this.mPackageManagerInt.getPackage(systemPackageName);
        boolean allowed = pkg.mSigningDetails.hasAncestorOrSelf(bp.getSourcePackageSetting().getSigningDetails()) || bp.getSourcePackageSetting().getSigningDetails().checkCapability(pkg.mSigningDetails, 4) || pkg.mSigningDetails.hasAncestorOrSelf(systemPackage.mSigningDetails) || systemPackage.mSigningDetails.checkCapability(pkg.mSigningDetails, 4);
        if (!allowed && ((privilegedPermission || oemPermission) && pkg.isSystem())) {
            if (pkg.isUpdatedSystemApp()) {
                PackageParser.Package disabledPkg = this.mPackageManagerInt.getDisabledPackage(pkg.packageName);
                PackageSetting disabledPs = disabledPkg != null ? (PackageSetting) disabledPkg.mExtras : null;
                if (disabledPs != null && disabledPs.getPermissionsState().hasInstallPermission(perm)) {
                    if ((privilegedPermission && disabledPs.isPrivileged()) || (oemPermission && disabledPs.isOem() && canGrantOemPermission(disabledPs, perm))) {
                        allowed = true;
                    }
                } else {
                    if (disabledPs != null && disabledPkg != null && isPackageRequestingPermission(disabledPkg, perm) && ((privilegedPermission && disabledPs.isPrivileged()) || (oemPermission && disabledPs.isOem() && canGrantOemPermission(disabledPs, perm)))) {
                        allowed = true;
                    }
                    if (pkg.parentPackage != null) {
                        PackageParser.Package disabledParentPkg = this.mPackageManagerInt.getDisabledPackage(pkg.parentPackage.packageName);
                        PackageSetting disabledParentPs = disabledParentPkg != null ? (PackageSetting) disabledParentPkg.mExtras : null;
                        if (disabledParentPkg != null && ((privilegedPermission && disabledParentPs.isPrivileged()) || (oemPermission && disabledParentPs.isOem()))) {
                            if (isPackageRequestingPermission(disabledParentPkg, perm) && canGrantOemPermission(disabledParentPs, perm)) {
                                allowed = true;
                            } else if (disabledParentPkg.childPackages != null) {
                                Iterator it2 = disabledParentPkg.childPackages.iterator();
                                while (true) {
                                    if (!it2.hasNext()) {
                                        break;
                                    }
                                    PackageParser.Package disabledParentPkg2 = disabledParentPkg;
                                    PackageParser.Package disabledChildPkg = (PackageParser.Package) it2.next();
                                    if (disabledChildPkg != null) {
                                        it = it2;
                                        disabledChildPs = (PackageSetting) disabledChildPkg.mExtras;
                                    } else {
                                        it = it2;
                                        disabledChildPs = null;
                                    }
                                    if (!isPackageRequestingPermission(disabledChildPkg, perm) || !canGrantOemPermission(disabledChildPs, perm)) {
                                        disabledParentPkg = disabledParentPkg2;
                                        it2 = it;
                                    } else {
                                        allowed = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                allowed = (privilegedPermission && pkg.isPrivileged()) || (oemPermission && pkg.isOem() && canGrantOemPermission(ps, perm));
            }
            if (allowed && privilegedPermission && !vendorPrivilegedPermission && pkg.isVendor()) {
                Slog.w(TAG, "Permission " + perm + " cannot be granted to privileged vendor apk " + pkg.packageName + " because it isn't a 'vendorPrivileged' permission.");
                allowed = false;
            }
        }
        if (allowed) {
            return allowed;
        }
        if (!allowed && bp.isPre23() && pkg.applicationInfo.targetSdkVersion < 23) {
            allowed = true;
        }
        if (!allowed && bp.isInstaller() && pkg.packageName.equals(this.mPackageManagerInt.getKnownPackageName(2, 0))) {
            allowed = true;
        }
        if (!allowed && bp.isVerifier() && pkg.packageName.equals(this.mPackageManagerInt.getKnownPackageName(3, 0))) {
            allowed = true;
        }
        if (!allowed && bp.isPreInstalled() && pkg.isSystem()) {
            allowed = true;
        }
        if (!allowed && bp.isDevelopment()) {
            allowed = origPermissions.hasInstallPermission(perm);
        }
        if (!allowed && bp.isSetup() && pkg.packageName.equals(this.mPackageManagerInt.getKnownPackageName(1, 0))) {
            allowed = true;
        }
        if (!allowed && bp.isSystemTextClassifier() && pkg.packageName.equals(this.mPackageManagerInt.getKnownPackageName(5, 0))) {
            return true;
        }
        return allowed;
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
        if (this.mSettings.mPermissionReviewRequired && pkg.applicationInfo.targetSdkVersion < 23 && pkg != null && pkg.mExtras != null) {
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            PermissionsState permissionsState = ps.getPermissionsState();
            return permissionsState.isPermissionReviewRequired(userId);
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
    @GuardedBy("mLock")
    public void grantRuntimePermissionsGrantedToDisabledPackageLocked(PackageParser.Package pkg, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
        PackageParser.Package disabledPkg;
        int i;
        int i2;
        int[] iArr;
        if (pkg.parentPackage == null || pkg.requestedPermissions == null || (disabledPkg = this.mPackageManagerInt.getDisabledPackage(pkg.parentPackage.packageName)) == null || disabledPkg.mExtras == null) {
            return;
        }
        PackageSetting disabledPs = (PackageSetting) disabledPkg.mExtras;
        if (!disabledPs.isPrivileged() || disabledPs.hasChildPackages()) {
            return;
        }
        int permCount = pkg.requestedPermissions.size();
        int i3 = 0;
        while (true) {
            int i4 = i3;
            if (i4 < permCount) {
                String permission = (String) pkg.requestedPermissions.get(i4);
                BasePermission bp = this.mSettings.getPermissionLocked(permission);
                if (bp != null && (bp.isRuntime() || bp.isDevelopment())) {
                    int[] userIds = this.mUserManagerInt.getUserIds();
                    int length = userIds.length;
                    int i5 = 0;
                    while (i5 < length) {
                        int userId = userIds[i5];
                        if (!disabledPs.getPermissionsState().hasRuntimePermission(permission, userId)) {
                            i = i5;
                            i2 = length;
                            iArr = userIds;
                        } else {
                            i = i5;
                            i2 = length;
                            iArr = userIds;
                            grantRuntimePermission(permission, pkg.packageName, false, callingUid, userId, callback);
                        }
                        i5 = i + 1;
                        length = i2;
                        userIds = iArr;
                    }
                }
                i3 = i4 + 1;
            } else {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void grantRequestedRuntimePermissions(PackageParser.Package pkg, int[] userIds, String[] grantedPermissions, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
        for (int userId : userIds) {
            grantRequestedRuntimePermissionsForUser(pkg, userId, grantedPermissions, callingUid, callback);
        }
    }

    private void grantRequestedRuntimePermissionsForUser(PackageParser.Package pkg, int userId, String[] grantedPermissions, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
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
                            } else if (this.mSettings.mPermissionReviewRequired && (flags & 64) != 0) {
                                updatePermissionFlags(permission, pkg.packageName, 64, 0, callingUid, userId, callback);
                            }
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void grantRuntimePermission(String permName, String packageName, boolean overridePolicy, int callingUid, int userId, PermissionManagerInternal.PermissionCallback callback) {
        BasePermission bp;
        long token;
        if (!this.mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.GRANT_RUNTIME_PERMISSIONS", "grantRuntimePermission");
        enforceCrossUserPermission(callingUid, userId, true, true, false, "grantRuntimePermission");
        PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
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
            if (this.mSettings.mPermissionReviewRequired && pkg.applicationInfo.targetSdkVersion < 23 && bp.isRuntime()) {
                return;
            }
            int uid = UserHandle.getUid(userId, pkg.applicationInfo.uid);
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            PermissionsState permissionsState = ps.getPermissionsState();
            int flags = permissionsState.getPermissionFlags(permName, userId);
            if ((flags & 16) != 0) {
                throw new SecurityException("Cannot grant system fixed permission " + permName + " for package " + packageName);
            } else if (!overridePolicy && (flags & 4) != 0) {
                throw new SecurityException("Cannot grant policy fixed permission " + permName + " for package " + packageName);
            } else if (bp.isDevelopment()) {
                if (permissionsState.grantInstallPermission(bp) != -1 && callback != null) {
                    callback.onInstallPermissionGranted();
                }
            } else if (ps.getInstantApp(userId) && !bp.isInstant()) {
                throw new SecurityException("Cannot grant non-ephemeral permission" + permName + " for package " + packageName);
            } else if (pkg.applicationInfo.targetSdkVersion < 23) {
                Slog.w(TAG, "Cannot grant runtime permission to a legacy app");
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
                if ("android.permission.READ_EXTERNAL_STORAGE".equals(permName) || "android.permission.WRITE_EXTERNAL_STORAGE".equals(permName)) {
                    long token2 = Binder.clearCallingIdentity();
                    try {
                        if (this.mUserManagerInt.isUserInitialized(userId)) {
                            try {
                                StorageManagerInternal storageManagerInternal = (StorageManagerInternal) LocalServices.getService(StorageManagerInternal.class);
                                storageManagerInternal.onExternalStoragePolicyChanged(uid, packageName);
                            } catch (Throwable th3) {
                                th = th3;
                                token = token2;
                                Binder.restoreCallingIdentity(token);
                                throw th;
                            }
                        }
                        Binder.restoreCallingIdentity(token2);
                    } catch (Throwable th4) {
                        th = th4;
                        token = token2;
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void revokeRuntimePermission(String permName, String packageName, boolean overridePolicy, int callingUid, int userId, PermissionManagerInternal.PermissionCallback callback) {
        if (!this.mUserManagerInt.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }
        this.mContext.enforceCallingOrSelfPermission("android.permission.REVOKE_RUNTIME_PERMISSIONS", "revokeRuntimePermission");
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, true, false, "revokeRuntimePermission");
        PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        } else if (this.mPackageManagerInt.filterAppAccess(pkg, Binder.getCallingUid(), userId)) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        } else {
            BasePermission bp = this.mSettings.getPermissionLocked(permName);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + permName);
            }
            bp.enforceDeclaredUsedAndRuntimeOrDevelopment(pkg);
            if (this.mSettings.mPermissionReviewRequired && pkg.applicationInfo.targetSdkVersion < 23 && bp.isRuntime()) {
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
            } else if (permissionsState.revokeRuntimePermission(bp, userId) == -1) {
            } else {
                if (bp.isRuntime()) {
                    logPermission(1245, permName, packageName);
                }
                if (callback != null) {
                    UserHandle.getUid(userId, pkg.applicationInfo.uid);
                    callback.onPermissionRevoked(pkg.applicationInfo.uid, userId);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private int[] revokeUnusedSharedUserPermissionsLocked(SharedUserSetting suSetting, int[] allUserIds) {
        int j;
        char c;
        char c2;
        int i;
        BasePermission bp;
        BasePermission bp2;
        PermissionManagerService permissionManagerService = this;
        int[] iArr = allUserIds;
        ArraySet<String> usedPermissions = new ArraySet<>();
        List<PackageParser.Package> pkgList = suSetting.getPackages();
        if (pkgList == null || pkgList.size() == 0) {
            return EmptyArray.INT;
        }
        Iterator<PackageParser.Package> it = pkgList.iterator();
        while (true) {
            j = 0;
            if (!it.hasNext()) {
                break;
            }
            PackageParser.Package pkg = it.next();
            if (pkg.requestedPermissions != null) {
                int requestedPermCount = pkg.requestedPermissions.size();
                while (j < requestedPermCount) {
                    String permission = (String) pkg.requestedPermissions.get(j);
                    if (permissionManagerService.mSettings.getPermissionLocked(permission) != null) {
                        usedPermissions.add(permission);
                    }
                    j++;
                }
            }
        }
        PermissionsState permissionsState = suSetting.getPermissionsState();
        List<PermissionsState.PermissionState> installPermStates = permissionsState.getInstallPermissionStates();
        int installPermCount = installPermStates.size();
        int i2 = installPermCount - 1;
        while (true) {
            c = 255;
            if (i2 < 0) {
                break;
            }
            PermissionsState.PermissionState permissionState = installPermStates.get(i2);
            if (!usedPermissions.contains(permissionState.getName()) && (bp2 = permissionManagerService.mSettings.getPermissionLocked(permissionState.getName())) != null) {
                permissionsState.revokeInstallPermission(bp2);
                permissionsState.updatePermissionFlags(bp2, -1, 255, 0);
            }
            i2--;
        }
        int[] runtimePermissionChangedUserIds = EmptyArray.INT;
        int length = iArr.length;
        int[] runtimePermissionChangedUserIds2 = runtimePermissionChangedUserIds;
        int i3 = 0;
        while (i3 < length) {
            int userId = iArr[i3];
            List<PermissionsState.PermissionState> runtimePermStates = permissionsState.getRuntimePermissionStates(userId);
            int runtimePermCount = runtimePermStates.size();
            int i4 = runtimePermCount - 1;
            while (i4 >= 0) {
                PermissionsState.PermissionState permissionState2 = runtimePermStates.get(i4);
                if (usedPermissions.contains(permissionState2.getName()) || (bp = permissionManagerService.mSettings.getPermissionLocked(permissionState2.getName())) == null) {
                    c2 = 255;
                    i = 0;
                } else {
                    permissionsState.revokeRuntimePermission(bp, userId);
                    c2 = 255;
                    i = 0;
                    permissionsState.updatePermissionFlags(bp, userId, 255, 0);
                    runtimePermissionChangedUserIds2 = ArrayUtils.appendInt(runtimePermissionChangedUserIds2, userId);
                }
                i4--;
                j = i;
                permissionManagerService = this;
                c = c2;
            }
            i3++;
            permissionManagerService = this;
            c = c;
            iArr = allUserIds;
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
            enforceGrantRevokeRuntimePermissionPermissions("getPermissionFlags");
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
    public void updatePermissions(String packageName, PackageParser.Package pkg, boolean replaceGrant, Collection<PackageParser.Package> allPackages, PermissionManagerInternal.PermissionCallback callback) {
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
    public void updateAllPermissions(String volumeUuid, boolean sdkUpdated, Collection<PackageParser.Package> allPackages, PermissionManagerInternal.PermissionCallback callback) {
        int i;
        if (sdkUpdated) {
            i = 6;
        } else {
            i = 0;
        }
        int flags = i | 1;
        updatePermissions(null, null, volumeUuid, flags, allPackages, callback);
    }

    private void updatePermissions(String changingPkgName, PackageParser.Package changingPkg, String replaceVolumeUuid, int flags, Collection<PackageParser.Package> allPackages, PermissionManagerInternal.PermissionCallback callback) {
        int flags2 = updatePermissions(changingPkgName, changingPkg, updatePermissionTrees(changingPkgName, changingPkg, flags));
        Trace.traceBegin(262144L, "grantPermissions");
        boolean replace = false;
        if ((flags2 & 1) != 0) {
            for (PackageParser.Package pkg : allPackages) {
                if (pkg != changingPkg) {
                    String volumeUuid = getVolumeUuidForPackage(pkg);
                    grantPermissions(pkg, (flags2 & 4) != 0 && Objects.equals(replaceVolumeUuid, volumeUuid), changingPkgName, callback);
                }
            }
        }
        if (changingPkg != null) {
            String volumeUuid2 = getVolumeUuidForPackage(changingPkg);
            if ((flags2 & 2) != 0 && Objects.equals(replaceVolumeUuid, volumeUuid2)) {
                replace = true;
            }
            grantPermissions(changingPkg, replace, changingPkgName, callback);
        }
        Trace.traceEnd(262144L);
    }

    private int updatePermissions(String packageName, PackageParser.Package pkg, int flags) {
        Set<BasePermission> needsUpdate = null;
        synchronized (this.mLock) {
            Iterator<BasePermission> it = this.mSettings.mPermissions.values().iterator();
            while (it.hasNext()) {
                BasePermission bp = it.next();
                if (bp.isDynamic()) {
                    bp.updateDynamicPermission(this.mSettings.mPermissionTrees.values());
                }
                if (bp.getSourcePackageSetting() != null) {
                    if (packageName != null && packageName.equals(bp.getSourcePackageName()) && (pkg == null || !hasPermission(pkg, bp.getName()))) {
                        Slog.i(TAG, "Removing old permission tree: " + bp.getName() + " from package " + bp.getSourcePackageName());
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
                        try {
                            if (sourcePkg.mExtras != null) {
                                PackageSetting sourcePs = (PackageSetting) sourcePkg.mExtras;
                                if (bp2.getSourcePackageSetting() == null) {
                                    bp2.setSourcePackageSetting(sourcePs);
                                }
                            }
                        } finally {
                        }
                    }
                    Slog.w(TAG, "Removing dangling permission: " + bp2.getName() + " from package " + bp2.getSourcePackageName());
                    this.mSettings.removePermissionLocked(bp2.getName());
                }
            }
        }
        return flags;
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
                        try {
                            if (sourcePkg.mExtras != null) {
                                PackageSetting sourcePs = (PackageSetting) sourcePkg.mExtras;
                                if (bp2.getSourcePackageSetting() == null) {
                                    bp2.setSourcePackageSetting(sourcePs);
                                }
                            }
                        } finally {
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
    public void updatePermissionFlags(String permName, String packageName, int flagMask, int flagValues, int callingUid, int userId, PermissionManagerInternal.PermissionCallback callback) {
        int flagValues2;
        int flagValues3;
        BasePermission bp;
        if (!this.mUserManagerInt.exists(userId)) {
            return;
        }
        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlags");
        enforceCrossUserPermission(callingUid, userId, true, true, false, "updatePermissionFlags");
        if (callingUid != 1000) {
            flagValues3 = flagValues & (-17) & (-33) & (-65);
            flagValues2 = flagMask & (-17) & (-33);
        } else {
            flagValues2 = flagMask;
            flagValues3 = flagValues;
        }
        PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
        if (pkg == null || pkg.mExtras == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
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
            if (permissionUpdated && callback != null) {
                if (permissionsState.getInstallPermissionState(permName) != null) {
                    callback.onInstallPermissionUpdated();
                } else if (permissionsState.getRuntimePermissionState(permName, userId) != null || hadState) {
                    callback.onPermissionUpdated(new int[]{userId}, false);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues, int callingUid, int userId, Collection<PackageParser.Package> packages, PermissionManagerInternal.PermissionCallback callback) {
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

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceCrossUserPermission(int callingUid, int userId, boolean requireFullPermission, boolean checkShell, boolean requirePermissionWhenSameUser, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            PackageManagerServiceUtils.enforceShellRestriction("no_debugging_features", callingUid, userId);
        }
        if ((requirePermissionWhenSameUser || userId != UserHandle.getUserId(callingUid)) && callingUid != 1000 && callingUid != 0) {
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
    }

    private int calculateCurrentPermissionFootprintLocked(BasePermission tree) {
        int size = 0;
        for (BasePermission perm : this.mSettings.mPermissions.values()) {
            size += tree.calculateFootprint(perm);
        }
        return size;
    }

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

    /* loaded from: classes.dex */
    private class PermissionManagerInternalImpl extends PermissionManagerInternal {
        private PermissionManagerInternalImpl() {
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void systemReady() {
            PermissionManagerService.this.systemReady();
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public boolean isPermissionsReviewRequired(PackageParser.Package pkg, int userId) {
            return PermissionManagerService.this.isPermissionsReviewRequired(pkg, userId);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void revokeRuntimePermissionsIfGroupChanged(PackageParser.Package newPackage, PackageParser.Package oldPackage, ArrayList<String> allPackageNames, PermissionManagerInternal.PermissionCallback permissionCallback) {
            PermissionManagerService.this.revokeRuntimePermissionsIfGroupChanged(newPackage, oldPackage, allPackageNames, permissionCallback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void addAllPermissions(PackageParser.Package pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissions(pkg, chatty);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void addAllPermissionGroups(PackageParser.Package pkg, boolean chatty) {
            PermissionManagerService.this.addAllPermissionGroups(pkg, chatty);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void removeAllPermissions(PackageParser.Package pkg, boolean chatty) {
            PermissionManagerService.this.removeAllPermissions(pkg, chatty);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public boolean addDynamicPermission(PermissionInfo info, boolean async, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
            return PermissionManagerService.this.addDynamicPermission(info, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void removeDynamicPermission(String permName, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.removeDynamicPermission(permName, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void grantRuntimePermission(String permName, String packageName, boolean overridePolicy, int callingUid, int userId, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.grantRuntimePermission(permName, packageName, overridePolicy, callingUid, userId, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void grantRequestedRuntimePermissions(PackageParser.Package pkg, int[] userIds, String[] grantedPermissions, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.grantRequestedRuntimePermissions(pkg, userIds, grantedPermissions, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void grantRuntimePermissionsGrantedToDisabledPackage(PackageParser.Package pkg, int callingUid, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.grantRuntimePermissionsGrantedToDisabledPackageLocked(pkg, callingUid, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void revokeRuntimePermission(String permName, String packageName, boolean overridePolicy, int callingUid, int userId, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.revokeRuntimePermission(permName, packageName, overridePolicy, callingUid, userId, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void updatePermissions(String packageName, PackageParser.Package pkg, boolean replaceGrant, Collection<PackageParser.Package> allPackages, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.updatePermissions(packageName, pkg, replaceGrant, allPackages, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void updateAllPermissions(String volumeUuid, boolean sdkUpdated, Collection<PackageParser.Package> allPackages, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.updateAllPermissions(volumeUuid, sdkUpdated, allPackages, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public String[] getAppOpPermissionPackages(String permName) {
            return PermissionManagerService.this.getAppOpPermissionPackages(permName);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public int getPermissionFlags(String permName, String packageName, int callingUid, int userId) {
            return PermissionManagerService.this.getPermissionFlags(permName, packageName, callingUid, userId);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void updatePermissionFlags(String permName, String packageName, int flagMask, int flagValues, int callingUid, int userId, PermissionManagerInternal.PermissionCallback callback) {
            PermissionManagerService.this.updatePermissionFlags(permName, packageName, flagMask, flagValues, callingUid, userId, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public boolean updatePermissionFlagsForAllApps(int flagMask, int flagValues, int callingUid, int userId, Collection<PackageParser.Package> packages, PermissionManagerInternal.PermissionCallback callback) {
            return PermissionManagerService.this.updatePermissionFlagsForAllApps(flagMask, flagValues, callingUid, userId, packages, callback);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void enforceCrossUserPermission(int callingUid, int userId, boolean requireFullPermission, boolean checkShell, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId, requireFullPermission, checkShell, false, message);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void enforceCrossUserPermission(int callingUid, int userId, boolean requireFullPermission, boolean checkShell, boolean requirePermissionWhenSameUser, String message) {
            PermissionManagerService.this.enforceCrossUserPermission(callingUid, userId, requireFullPermission, checkShell, requirePermissionWhenSameUser, message);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public void enforceGrantRevokeRuntimePermissionPermissions(String message) {
            PermissionManagerService.this.enforceGrantRevokeRuntimePermissionPermissions(message);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public int checkPermission(String permName, String packageName, int callingUid, int userId) {
            return PermissionManagerService.this.checkPermission(permName, packageName, callingUid, userId);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public int checkUidPermission(String permName, PackageParser.Package pkg, int uid, int callingUid) {
            return PermissionManagerService.this.checkUidPermission(permName, pkg, uid, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags, int callingUid) {
            return PermissionManagerService.this.getPermissionGroupInfo(groupName, flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public List<PermissionGroupInfo> getAllPermissionGroups(int flags, int callingUid) {
            return PermissionManagerService.this.getAllPermissionGroups(flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public PermissionInfo getPermissionInfo(String permName, String packageName, int flags, int callingUid) {
            return PermissionManagerService.this.getPermissionInfo(permName, packageName, flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public List<PermissionInfo> getPermissionInfoByGroup(String group, int flags, int callingUid) {
            return PermissionManagerService.this.getPermissionInfoByGroup(group, flags, callingUid);
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public PermissionSettings getPermissionSettings() {
            return PermissionManagerService.this.mSettings;
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy() {
            return PermissionManagerService.this.mDefaultPermissionGrantPolicy;
        }

        @Override // com.android.server.pm.permission.PermissionManagerInternal
        public BasePermission getPermissionTEMP(String permName) {
            BasePermission permissionLocked;
            synchronized (PermissionManagerService.this.mLock) {
                permissionLocked = PermissionManagerService.this.mSettings.getPermissionLocked(permName);
            }
            return permissionLocked;
        }
    }
}
