package com.android.server.pm;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageCleanItem;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Message;
import android.os.PatternMatcher;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;
import com.android.server.UiModeManagerService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.permission.BasePermission;
import com.android.server.pm.permission.PermissionSettings;
import com.android.server.pm.permission.PermissionsState;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.voiceinteraction.DatabaseHelper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
public final class Settings {
    private static final String ATTR_APP_LINK_GENERATION = "app-link-generation";
    private static final String ATTR_BLOCKED = "blocked";
    @Deprecated
    private static final String ATTR_BLOCK_UNINSTALL = "blockUninstall";
    private static final String ATTR_CE_DATA_INODE = "ceDataInode";
    private static final String ATTR_CODE = "code";
    private static final String ATTR_DATABASE_VERSION = "databaseVersion";
    private static final String ATTR_DOMAIN_VERIFICATON_STATE = "domainVerificationStatus";
    private static final String ATTR_ENABLED = "enabled";
    private static final String ATTR_ENABLED_CALLER = "enabledCaller";
    private static final String ATTR_ENFORCEMENT = "enforcement";
    private static final String ATTR_FINGERPRINT = "fingerprint";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_GRANTED = "granted";
    private static final String ATTR_HARMFUL_APP_WARNING = "harmful-app-warning";
    private static final String ATTR_HIDDEN = "hidden";
    private static final String ATTR_INSTALLED = "inst";
    private static final String ATTR_INSTALL_REASON = "install-reason";
    private static final String ATTR_INSTANT_APP = "instant-app";
    public static final String ATTR_NAME = "name";
    private static final String ATTR_NOT_LAUNCHED = "nl";
    public static final String ATTR_PACKAGE = "package";
    private static final String ATTR_PACKAGE_NAME = "packageName";
    private static final String ATTR_REVOKE_ON_UPGRADE = "rou";
    private static final String ATTR_SDK_VERSION = "sdkVersion";
    private static final String ATTR_STOPPED = "stopped";
    private static final String ATTR_SUSPENDED = "suspended";
    private static final String ATTR_SUSPENDING_PACKAGE = "suspending-package";
    private static final String ATTR_SUSPEND_DIALOG_MESSAGE = "suspend_dialog_message";
    private static final String ATTR_USER = "user";
    private static final String ATTR_USER_FIXED = "fixed";
    private static final String ATTR_USER_SET = "set";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_VIRTUAL_PRELOAD = "virtual-preload";
    private static final String ATTR_VOLUME_UUID = "volumeUuid";
    public static final int CURRENT_DATABASE_VERSION = 3;
    private static final boolean DEBUG_KERNEL = false;
    private static final boolean DEBUG_MU = false;
    private static final boolean DEBUG_PARSER = false;
    private static final boolean DEBUG_STOPPED = false;
    private static final int FIXED_VMAP_UID = 10000;
    private static final String RUNTIME_PERMISSIONS_FILE_NAME = "runtime-permissions.xml";
    private static final String TAG = "PackageSettings";
    private static final String TAG_ALL_INTENT_FILTER_VERIFICATION = "all-intent-filter-verifications";
    private static final String TAG_BLOCK_UNINSTALL = "block-uninstall";
    private static final String TAG_BLOCK_UNINSTALL_PACKAGES = "block-uninstall-packages";
    private static final String TAG_CHILD_PACKAGE = "child-package";
    static final String TAG_CROSS_PROFILE_INTENT_FILTERS = "crossProfile-intent-filters";
    private static final String TAG_DEFAULT_APPS = "default-apps";
    private static final String TAG_DEFAULT_BROWSER = "default-browser";
    private static final String TAG_DEFAULT_DIALER = "default-dialer";
    private static final String TAG_DISABLED_COMPONENTS = "disabled-components";
    private static final String TAG_DOMAIN_VERIFICATION = "domain-verification";
    private static final String TAG_ENABLED_COMPONENTS = "enabled-components";
    public static final String TAG_ITEM = "item";
    private static final String TAG_PACKAGE = "pkg";
    private static final String TAG_PACKAGE_RESTRICTIONS = "package-restrictions";
    private static final String TAG_PERMISSIONS = "perms";
    private static final String TAG_PERMISSION_ENTRY = "perm";
    private static final String TAG_PERSISTENT_PREFERRED_ACTIVITIES = "persistent-preferred-activities";
    private static final String TAG_READ_EXTERNAL_STORAGE = "read-external-storage";
    private static final String TAG_RESTORED_RUNTIME_PERMISSIONS = "restored-perms";
    private static final String TAG_RUNTIME_PERMISSIONS = "runtime-permissions";
    private static final String TAG_SHARED_USER = "shared-user";
    private static final String TAG_SUSPENDED_APP_EXTRAS = "suspended-app-extras";
    private static final String TAG_SUSPENDED_LAUNCHER_EXTRAS = "suspended-launcher-extras";
    private static final String TAG_USES_STATIC_LIB = "uses-static-lib";
    private static final String TAG_VERSION = "version";
    private static final int USER_RUNTIME_GRANT_MASK = 11;
    private final File mBackupSettingsFilename;
    private final File mBackupStoppedPackagesFilename;
    private final SparseArray<ArraySet<String>> mBlockUninstallPackages;
    final SparseArray<CrossProfileIntentResolver> mCrossProfileIntentResolvers;
    final SparseArray<String> mDefaultBrowserApp;
    final SparseArray<String> mDefaultDialerApp;
    private final ArrayMap<String, PackageSetting> mDisabledSysPackages;
    final ArraySet<String> mInstallerPackages;
    private final ArrayMap<String, KernelPackageState> mKernelMapping;
    private final File mKernelMappingFilename;
    public final KeySetManagerService mKeySetManagerService;
    private final ArrayMap<Long, Integer> mKeySetRefs;
    private final Object mLock;
    final SparseIntArray mNextAppLinkGeneration;
    private final SparseArray<Object> mOtherUserIds;
    private final File mPackageListFilename;
    final ArrayMap<String, PackageSetting> mPackages;
    final ArrayList<PackageCleanItem> mPackagesToBeCleaned;
    private final ArrayList<Signature> mPastSignatures;
    private final ArrayList<PackageSetting> mPendingPackages;
    final PermissionSettings mPermissions;
    final SparseArray<PersistentPreferredIntentResolver> mPersistentPreferredActivities;
    final SparseArray<PreferredIntentResolver> mPreferredActivities;
    Boolean mReadExternalStorageEnforced;
    final StringBuilder mReadMessages;
    private final ArrayMap<String, String> mRenamedPackages;
    private final ArrayMap<String, IntentFilterVerificationInfo> mRestoredIntentFilterVerifications;
    private final SparseArray<ArrayMap<String, ArraySet<RestoredPermissionGrant>>> mRestoredUserGrants;
    private final RuntimePermissionPersistence mRuntimePermissionsPersistence;
    private final File mSettingsFilename;
    final ArrayMap<String, SharedUserSetting> mSharedUsers;
    private final File mStoppedPackagesFilename;
    private final File mSystemDir;
    private final ArrayList<Object> mUserIds;
    private VerifierDeviceIdentity mVerifierDeviceIdentity;
    private ArrayMap<String, VersionInfo> mVersion;
    private static int mFirstAvailableUid = 10001;
    private static int PRE_M_APP_INFO_FLAG_HIDDEN = 134217728;
    private static int PRE_M_APP_INFO_FLAG_CANT_SAVE_STATE = 268435456;
    private static int PRE_M_APP_INFO_FLAG_FORWARD_LOCK = 536870912;
    private static int PRE_M_APP_INFO_FLAG_PRIVILEGED = 1073741824;
    static final Object[] FLAG_DUMP_SPEC = {1, "SYSTEM", 2, "DEBUGGABLE", 4, "HAS_CODE", 8, "PERSISTENT", 16, "FACTORY_TEST", 32, "ALLOW_TASK_REPARENTING", 64, "ALLOW_CLEAR_USER_DATA", 128, "UPDATED_SYSTEM_APP", 256, "TEST_ONLY", 16384, "VM_SAFE_MODE", 32768, "ALLOW_BACKUP", 65536, "KILL_AFTER_RESTORE", Integer.valueOf((int) DumpState.DUMP_INTENT_FILTER_VERIFIERS), "RESTORE_ANY_VERSION", Integer.valueOf((int) DumpState.DUMP_DOMAIN_PREFERRED), "EXTERNAL_STORAGE", 1048576, "LARGE_HEAP"};
    private static final Object[] PRIVATE_FLAG_DUMP_SPEC = {1024, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE", 4096, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION", 2048, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE", 8192, "BACKUP_IN_FOREGROUND", 2, "CANT_SAVE_STATE", 32, "DEFAULT_TO_DEVICE_PROTECTED_STORAGE", 64, "DIRECT_BOOT_AWARE", 4, "FORWARD_LOCK", 16, "HAS_DOMAIN_URLS", 1, "HIDDEN", 128, "EPHEMERAL", 32768, "ISOLATED_SPLIT_LOADING", Integer.valueOf((int) DumpState.DUMP_INTENT_FILTER_VERIFIERS), "OEM", 256, "PARTIALLY_DIRECT_BOOT_AWARE", 8, "PRIVILEGED", 512, "REQUIRED_FOR_SYSTEM_USER", 16384, "STATIC_SHARED_LIBRARY", Integer.valueOf((int) DumpState.DUMP_DOMAIN_PREFERRED), "VENDOR", Integer.valueOf((int) DumpState.DUMP_FROZEN), "PRODUCT", 65536, "VIRTUAL_PRELOAD"};

    /* loaded from: classes.dex */
    public static class DatabaseVersion {
        public static final int FIRST_VERSION = 1;
        public static final int SIGNATURE_END_ENTITY = 2;
        public static final int SIGNATURE_MALFORMED_RECOVER = 3;
    }

    static /* synthetic */ File access$200(Settings x0, int x1) {
        return x0.getUserRuntimePermissionsFile(x1);
    }

    static /* synthetic */ SparseArray access$300(Settings x0) {
        return x0.mRestoredUserGrants;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class KernelPackageState {
        int appId;
        int[] excludedUserIds;

        private KernelPackageState() {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class RestoredPermissionGrant {
        int grantBits;
        boolean granted;
        String permissionName;

        RestoredPermissionGrant(String name, boolean isGranted, int theGrantBits) {
            this.permissionName = name;
            this.granted = isGranted;
            this.grantBits = theGrantBits;
        }
    }

    /* loaded from: classes.dex */
    public static class VersionInfo {
        int databaseVersion;
        String fingerprint;
        int sdkVersion;

        public void forceCurrent() {
            this.sdkVersion = Build.VERSION.SDK_INT;
            this.databaseVersion = 3;
            this.fingerprint = Build.FINGERPRINT;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Settings(PermissionSettings permissions, Object lock) {
        this(Environment.getDataDirectory(), permissions, lock);
    }

    Settings(File dataDir, PermissionSettings permission, Object lock) {
        this.mPackages = new ArrayMap<>();
        this.mInstallerPackages = new ArraySet<>();
        this.mKernelMapping = new ArrayMap<>();
        this.mDisabledSysPackages = new ArrayMap<>();
        this.mBlockUninstallPackages = new SparseArray<>();
        this.mRestoredIntentFilterVerifications = new ArrayMap<>();
        this.mRestoredUserGrants = new SparseArray<>();
        this.mVersion = new ArrayMap<>();
        this.mPreferredActivities = new SparseArray<>();
        this.mPersistentPreferredActivities = new SparseArray<>();
        this.mCrossProfileIntentResolvers = new SparseArray<>();
        this.mSharedUsers = new ArrayMap<>();
        this.mUserIds = new ArrayList<>();
        this.mOtherUserIds = new SparseArray<>();
        this.mPastSignatures = new ArrayList<>();
        this.mKeySetRefs = new ArrayMap<>();
        this.mPackagesToBeCleaned = new ArrayList<>();
        this.mRenamedPackages = new ArrayMap<>();
        this.mDefaultBrowserApp = new SparseArray<>();
        this.mDefaultDialerApp = new SparseArray<>();
        this.mNextAppLinkGeneration = new SparseIntArray();
        this.mReadMessages = new StringBuilder();
        this.mPendingPackages = new ArrayList<>();
        this.mKeySetManagerService = new KeySetManagerService(this.mPackages);
        this.mLock = lock;
        this.mPermissions = permission;
        this.mRuntimePermissionsPersistence = new RuntimePermissionPersistence(this.mLock);
        this.mSystemDir = new File(dataDir, "system");
        this.mSystemDir.mkdirs();
        FileUtils.setPermissions(this.mSystemDir.toString(), 509, -1, -1);
        this.mSettingsFilename = new File(this.mSystemDir, "packages.xml");
        this.mBackupSettingsFilename = new File(this.mSystemDir, "packages-backup.xml");
        this.mPackageListFilename = new File(this.mSystemDir, "packages.list");
        FileUtils.setPermissions(this.mPackageListFilename, 416, 1000, 1032);
        File kernelDir = new File("/config/sdcardfs");
        this.mKernelMappingFilename = kernelDir.exists() ? kernelDir : null;
        this.mStoppedPackagesFilename = new File(this.mSystemDir, "packages-stopped.xml");
        this.mBackupStoppedPackagesFilename = new File(this.mSystemDir, "packages-stopped-backup.xml");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageSetting getPackageLPr(String pkgName) {
        return this.mPackages.get(pkgName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getRenamedPackageLPr(String pkgName) {
        return this.mRenamedPackages.get(pkgName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String addRenamedPackageLPw(String pkgName, String origPkgName) {
        return this.mRenamedPackages.put(pkgName, origPkgName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyPendingPermissionGrantsLPw(String packageName, int userId) {
        ArraySet<RestoredPermissionGrant> grants;
        ArrayMap<String, ArraySet<RestoredPermissionGrant>> grantsByPackage = this.mRestoredUserGrants.get(userId);
        if (grantsByPackage == null || grantsByPackage.size() == 0 || (grants = grantsByPackage.get(packageName)) == null || grants.size() == 0) {
            return;
        }
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            Slog.e(TAG, "Can't find supposedly installed package " + packageName);
            return;
        }
        PermissionsState perms = ps.getPermissionsState();
        Iterator<RestoredPermissionGrant> it = grants.iterator();
        while (it.hasNext()) {
            RestoredPermissionGrant grant = it.next();
            BasePermission bp = this.mPermissions.getPermission(grant.permissionName);
            if (bp != null) {
                if (grant.granted) {
                    perms.grantRuntimePermission(bp, userId);
                }
                perms.updatePermissionFlags(bp, userId, 11, grant.grantBits);
            }
        }
        grantsByPackage.remove(packageName);
        if (grantsByPackage.size() < 1) {
            this.mRestoredUserGrants.remove(userId);
        }
        writeRuntimePermissionsForUserLPr(userId, false);
    }

    public boolean canPropagatePermissionToInstantApp(String permName) {
        return this.mPermissions.canPropagatePermissionToInstantApp(permName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInstallerPackageName(String pkgName, String installerPkgName) {
        PackageSetting p = this.mPackages.get(pkgName);
        if (p != null) {
            p.setInstallerPackageName(installerPkgName);
            if (installerPkgName != null) {
                this.mInstallerPackages.add(installerPkgName);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SharedUserSetting getSharedUserLPw(String name, int pkgFlags, int pkgPrivateFlags, boolean create) throws PackageManagerException {
        SharedUserSetting s = this.mSharedUsers.get(name);
        if (s == null && create) {
            s = new SharedUserSetting(name, pkgFlags, pkgPrivateFlags);
            s.userId = newUserIdLPw(s);
            if (s.userId < 0) {
                throw new PackageManagerException(-4, "Creating shared user " + name + " failed");
            }
            Log.i("PackageManager", "New shared user " + name + ": id=" + s.userId);
            this.mSharedUsers.put(name, s);
        }
        return s;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Collection<SharedUserSetting> getAllSharedUsersLPw() {
        return this.mSharedUsers.values();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean disableSystemPackageLPw(String name, boolean replaced) {
        PackageSetting p = this.mPackages.get(name);
        if (p == null) {
            Log.w("PackageManager", "Package " + name + " is not an installed package");
            return false;
        }
        PackageSetting dp = this.mDisabledSysPackages.get(name);
        if (dp != null || p.pkg == null || !p.pkg.isSystem() || p.pkg.isUpdatedSystemApp()) {
            return false;
        }
        if (p.pkg != null && p.pkg.applicationInfo != null) {
            p.pkg.applicationInfo.flags |= 128;
        }
        this.mDisabledSysPackages.put(name, p);
        if (replaced) {
            PackageSetting newp = new PackageSetting(p);
            replacePackageLPw(name, newp);
            return true;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageSetting enableSystemPackageLPw(String name) {
        PackageSetting p = this.mDisabledSysPackages.get(name);
        if (p == null) {
            Log.w("PackageManager", "Package " + name + " is not disabled");
            return null;
        }
        if (p.pkg != null && p.pkg.applicationInfo != null) {
            p.pkg.applicationInfo.flags &= -129;
        }
        PackageSetting ret = addPackageLPw(name, p.realName, p.codePath, p.resourcePath, p.legacyNativeLibraryPathString, p.primaryCpuAbiString, p.secondaryCpuAbiString, p.cpuAbiOverrideString, p.appId, p.versionCode, p.pkgFlags, p.pkgPrivateFlags, p.parentPackageName, p.childPackageNames, p.usesStaticLibraries, p.usesStaticLibrariesVersions);
        this.mDisabledSysPackages.remove(name);
        return ret;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDisabledSystemPackageLPr(String name) {
        return this.mDisabledSysPackages.containsKey(name);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeDisabledSystemPackageLPw(String name) {
        this.mDisabledSysPackages.remove(name);
    }

    PackageSetting addPackageLPw(String name, String realName, File codePath, File resourcePath, String legacyNativeLibraryPathString, String primaryCpuAbiString, String secondaryCpuAbiString, String cpuAbiOverrideString, int uid, long vc, int pkgFlags, int pkgPrivateFlags, String parentPackageName, List<String> childPackageNames, String[] usesStaticLibraries, long[] usesStaticLibraryNames) {
        PackageSetting p = this.mPackages.get(name);
        if (p != null) {
            if (p.appId == uid) {
                return p;
            }
            PackageManagerService.reportSettingsProblem(6, "Adding duplicate package, keeping first: " + name);
            return null;
        }
        PackageSetting p2 = new PackageSetting(name, realName, codePath, resourcePath, legacyNativeLibraryPathString, primaryCpuAbiString, secondaryCpuAbiString, cpuAbiOverrideString, vc, pkgFlags, pkgPrivateFlags, parentPackageName, childPackageNames, 0, usesStaticLibraries, usesStaticLibraryNames);
        p2.appId = uid;
        if (!addUserIdLPw(uid, p2, name)) {
            return null;
        }
        this.mPackages.put(name, p2);
        return p2;
    }

    void addAppOpPackage(String permName, String packageName) {
        this.mPermissions.addAppOpPackage(permName, packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SharedUserSetting addSharedUserLPw(String name, int uid, int pkgFlags, int pkgPrivateFlags) {
        SharedUserSetting s = this.mSharedUsers.get(name);
        if (s != null) {
            if (s.userId == uid) {
                return s;
            }
            PackageManagerService.reportSettingsProblem(6, "Adding duplicate shared user, keeping first: " + name);
            return null;
        }
        SharedUserSetting s2 = new SharedUserSetting(name, pkgFlags, pkgPrivateFlags);
        s2.userId = uid;
        if (!addUserIdLPw(uid, s2, name)) {
            return null;
        }
        this.mSharedUsers.put(name, s2);
        return s2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void pruneSharedUsersLPw() {
        ArrayList<String> removeStage = new ArrayList<>();
        for (Map.Entry<String, SharedUserSetting> entry : this.mSharedUsers.entrySet()) {
            SharedUserSetting sus = entry.getValue();
            if (sus == null) {
                removeStage.add(entry.getKey());
            } else {
                Iterator<PackageSetting> iter = sus.packages.iterator();
                while (iter.hasNext()) {
                    PackageSetting ps = iter.next();
                    if (this.mPackages.get(ps.name) == null) {
                        iter.remove();
                    }
                }
                if (sus.packages.size() == 0) {
                    removeStage.add(entry.getKey());
                }
            }
        }
        for (int i = 0; i < removeStage.size(); i++) {
            this.mSharedUsers.remove(removeStage.get(i));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00f6, code lost:
        if (isAdbInstallDisallowed(r63, r7.id) != false) goto L32;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static com.android.server.pm.PackageSetting createNewSetting(java.lang.String r43, com.android.server.pm.PackageSetting r44, com.android.server.pm.PackageSetting r45, java.lang.String r46, com.android.server.pm.SharedUserSetting r47, java.io.File r48, java.io.File r49, java.lang.String r50, java.lang.String r51, java.lang.String r52, long r53, int r55, int r56, android.os.UserHandle r57, boolean r58, boolean r59, boolean r60, java.lang.String r61, java.util.List<java.lang.String> r62, com.android.server.pm.UserManagerService r63, java.lang.String[] r64, long[] r65) {
        /*
            Method dump skipped, instructions count: 393
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.createNewSetting(java.lang.String, com.android.server.pm.PackageSetting, com.android.server.pm.PackageSetting, java.lang.String, com.android.server.pm.SharedUserSetting, java.io.File, java.io.File, java.lang.String, java.lang.String, java.lang.String, long, int, int, android.os.UserHandle, boolean, boolean, boolean, java.lang.String, java.util.List, com.android.server.pm.UserManagerService, java.lang.String[], long[]):com.android.server.pm.PackageSetting");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void updatePackageSetting(PackageSetting pkgSetting, PackageSetting disabledPkg, SharedUserSetting sharedUser, File codePath, File resourcePath, String legacyNativeLibraryPath, String primaryCpuAbi, String secondaryCpuAbi, int pkgFlags, int pkgPrivateFlags, List<String> childPkgNames, UserManagerService userManager, String[] usesStaticLibraries, long[] usesStaticLibrariesVersions) throws PackageManagerException {
        List<UserInfo> allUserInfos;
        String pkgName = pkgSetting.name;
        if (pkgSetting.sharedUser != sharedUser) {
            StringBuilder sb = new StringBuilder();
            sb.append("Package ");
            sb.append(pkgName);
            sb.append(" shared user changed from ");
            sb.append(pkgSetting.sharedUser != null ? pkgSetting.sharedUser.name : "<nothing>");
            sb.append(" to ");
            sb.append(sharedUser != null ? sharedUser.name : "<nothing>");
            PackageManagerService.reportSettingsProblem(5, sb.toString());
            throw new PackageManagerException(-8, "Updating application package " + pkgName + " failed");
        }
        if (!pkgSetting.codePath.equals(codePath)) {
            boolean isSystem = pkgSetting.isSystem();
            StringBuilder sb2 = new StringBuilder();
            sb2.append("Update");
            sb2.append(isSystem ? " system" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb2.append(" package ");
            sb2.append(pkgName);
            sb2.append(" code path from ");
            sb2.append(pkgSetting.codePathString);
            sb2.append(" to ");
            sb2.append(codePath.toString());
            sb2.append("; Retain data and using new");
            Slog.i("PackageManager", sb2.toString());
            if (!isSystem) {
                if ((pkgFlags & 1) != 0 && disabledPkg == null && (allUserInfos = getAllUsers(userManager)) != null) {
                    for (UserInfo userInfo : allUserInfos) {
                        pkgSetting.setInstalled(true, userInfo.id);
                    }
                }
                pkgSetting.legacyNativeLibraryPathString = legacyNativeLibraryPath;
            }
            pkgSetting.codePath = codePath;
            pkgSetting.codePathString = codePath.toString();
        }
        if (!pkgSetting.resourcePath.equals(resourcePath)) {
            boolean isSystem2 = pkgSetting.isSystem();
            StringBuilder sb3 = new StringBuilder();
            sb3.append("Update");
            sb3.append(isSystem2 ? " system" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb3.append(" package ");
            sb3.append(pkgName);
            sb3.append(" resource path from ");
            sb3.append(pkgSetting.resourcePathString);
            sb3.append(" to ");
            sb3.append(resourcePath.toString());
            sb3.append("; Retain data and using new");
            Slog.i("PackageManager", sb3.toString());
            pkgSetting.resourcePath = resourcePath;
            pkgSetting.resourcePathString = resourcePath.toString();
        }
        pkgSetting.pkgFlags &= -2;
        pkgSetting.pkgPrivateFlags &= -917513;
        pkgSetting.pkgFlags |= pkgFlags & 1;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & 8;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & DumpState.DUMP_INTENT_FILTER_VERIFIERS;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & DumpState.DUMP_DOMAIN_PREFERRED;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & DumpState.DUMP_FROZEN;
        pkgSetting.primaryCpuAbiString = primaryCpuAbi;
        pkgSetting.secondaryCpuAbiString = secondaryCpuAbi;
        if (childPkgNames != null) {
            pkgSetting.childPackageNames = new ArrayList(childPkgNames);
        }
        if (usesStaticLibraries != null && usesStaticLibrariesVersions != null && usesStaticLibraries.length == usesStaticLibrariesVersions.length) {
            pkgSetting.usesStaticLibraries = usesStaticLibraries;
            pkgSetting.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
            return;
        }
        pkgSetting.usesStaticLibraries = null;
        pkgSetting.usesStaticLibrariesVersions = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addUserToSettingLPw(PackageSetting p) throws PackageManagerException {
        if (p.appId == 0) {
            p.appId = newUserIdLPw(p);
        } else {
            addUserIdLPw(p.appId, p, p.name);
        }
        if (p.appId < 0) {
            PackageManagerService.reportSettingsProblem(5, "Package " + p.name + " could not be assigned a valid UID");
            throw new PackageManagerException(-4, "Package " + p.name + " could not be assigned a valid UID");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeUserRestrictionsLPw(PackageSetting newPackage, PackageSetting oldPackage) {
        List<UserInfo> allUsers;
        PackageUserState oldUserState;
        if (getPackageLPr(newPackage.name) == null || (allUsers = getAllUsers(UserManagerService.getInstance())) == null) {
            return;
        }
        for (UserInfo user : allUsers) {
            if (oldPackage == null) {
                oldUserState = PackageSettingBase.DEFAULT_USER_STATE;
            } else {
                oldUserState = oldPackage.readUserState(user.id);
            }
            if (!oldUserState.equals(newPackage.readUserState(user.id))) {
                writePackageRestrictionsLPr(user.id);
            }
        }
    }

    static boolean isAdbInstallDisallowed(UserManagerService userManager, int userId) {
        return userManager.hasUserRestriction("no_debugging_features", userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void insertPackageSettingLPw(PackageSetting p, PackageParser.Package pkg) {
        if (p.signatures.mSigningDetails.signatures == null) {
            p.signatures.mSigningDetails = pkg.mSigningDetails;
        }
        if (p.sharedUser != null && p.sharedUser.signatures.mSigningDetails.signatures == null) {
            p.sharedUser.signatures.mSigningDetails = pkg.mSigningDetails;
        }
        addPackageSettingLPw(p, p.sharedUser);
    }

    private void addPackageSettingLPw(PackageSetting p, SharedUserSetting sharedUser) {
        this.mPackages.put(p.name, p);
        if (sharedUser != null) {
            if (p.sharedUser != null && p.sharedUser != sharedUser) {
                PackageManagerService.reportSettingsProblem(6, "Package " + p.name + " was user " + p.sharedUser + " but is now " + sharedUser + "; I am not changing its files so it will probably fail!");
                p.sharedUser.removePackage(p);
            } else if (p.appId != sharedUser.userId) {
                PackageManagerService.reportSettingsProblem(6, "Package " + p.name + " was user id " + p.appId + " but is now user " + sharedUser + " with id " + sharedUser.userId + "; I am not changing its files so it will probably fail!");
            }
            sharedUser.addPackage(p);
            p.sharedUser = sharedUser;
            p.appId = sharedUser.userId;
        }
        Object userIdPs = getUserIdLPr(p.appId);
        if (sharedUser == null) {
            if (userIdPs != null && userIdPs != p) {
                replaceUserIdLPw(p.appId, p);
            }
        } else if (userIdPs != null && userIdPs != sharedUser) {
            replaceUserIdLPw(p.appId, sharedUser);
        }
        IntentFilterVerificationInfo ivi = this.mRestoredIntentFilterVerifications.get(p.name);
        if (ivi != null) {
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.i(TAG, "Applying restored IVI for " + p.name + " : " + ivi.getStatusString());
            }
            this.mRestoredIntentFilterVerifications.remove(p.name);
            p.setIntentFilterVerificationInfo(ivi);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int updateSharedUserPermsLPw(PackageSetting deletedPs, int userId) {
        if (deletedPs == null || deletedPs.pkg == null) {
            Slog.i("PackageManager", "Trying to update info for null package. Just ignoring");
            return -10000;
        } else if (deletedPs.sharedUser == null) {
            return -10000;
        } else {
            SharedUserSetting sus = deletedPs.sharedUser;
            Iterator it = deletedPs.pkg.requestedPermissions.iterator();
            while (it.hasNext()) {
                String eachPerm = (String) it.next();
                BasePermission bp = this.mPermissions.getPermission(eachPerm);
                if (bp != null) {
                    boolean used = false;
                    Iterator<PackageSetting> it2 = sus.packages.iterator();
                    while (true) {
                        if (!it2.hasNext()) {
                            break;
                        }
                        PackageSetting pkg = it2.next();
                        if (pkg.pkg != null && !pkg.pkg.packageName.equals(deletedPs.pkg.packageName) && pkg.pkg.requestedPermissions.contains(eachPerm)) {
                            used = true;
                            break;
                        }
                    }
                    if (used) {
                        continue;
                    } else {
                        PermissionsState permissionsState = sus.getPermissionsState();
                        PackageSetting disabledPs = getDisabledSystemPkgLPr(deletedPs.pkg.packageName);
                        if (disabledPs != null) {
                            boolean reqByDisabledSysPkg = false;
                            Iterator it3 = disabledPs.pkg.requestedPermissions.iterator();
                            while (true) {
                                if (!it3.hasNext()) {
                                    break;
                                }
                                String permission = (String) it3.next();
                                if (permission.equals(eachPerm)) {
                                    reqByDisabledSysPkg = true;
                                    break;
                                }
                            }
                            if (reqByDisabledSysPkg) {
                                continue;
                            }
                        }
                        permissionsState.updatePermissionFlags(bp, userId, 255, 0);
                        if (permissionsState.revokeInstallPermission(bp) == 1) {
                            return -1;
                        }
                        if (permissionsState.revokeRuntimePermission(bp, userId) == 1) {
                            return userId;
                        }
                    }
                }
            }
            return -10000;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int removePackageLPw(String name) {
        PackageSetting p = this.mPackages.get(name);
        if (p != null) {
            this.mPackages.remove(name);
            removeInstallerPackageStatus(name);
            if (p.sharedUser != null) {
                p.sharedUser.removePackage(p);
                if (p.sharedUser.packages.size() == 0) {
                    this.mSharedUsers.remove(p.sharedUser.name);
                    removeUserIdLPw(p.sharedUser.userId);
                    return p.sharedUser.userId;
                }
                return -1;
            }
            removeUserIdLPw(p.appId);
            return p.appId;
        }
        return -1;
    }

    private void removeInstallerPackageStatus(String packageName) {
        if (!this.mInstallerPackages.contains(packageName)) {
            return;
        }
        for (int i = 0; i < this.mPackages.size(); i++) {
            PackageSetting ps = this.mPackages.valueAt(i);
            String installerPackageName = ps.getInstallerPackageName();
            if (installerPackageName != null && installerPackageName.equals(packageName)) {
                ps.setInstallerPackageName(null);
                ps.isOrphaned = true;
            }
        }
        this.mInstallerPackages.remove(packageName);
    }

    private void replacePackageLPw(String name, PackageSetting newp) {
        PackageSetting p = this.mPackages.get(name);
        if (p != null) {
            if (p.sharedUser != null) {
                p.sharedUser.removePackage(p);
                p.sharedUser.addPackage(newp);
            } else {
                replaceUserIdLPw(p.appId, newp);
            }
        }
        this.mPackages.put(name, newp);
    }

    private boolean addUserIdLPw(int uid, Object obj, Object name) {
        if (uid > 19999) {
            return false;
        }
        if (uid >= 10000) {
            int index = uid - 10000;
            for (int N = this.mUserIds.size(); index >= N; N++) {
                this.mUserIds.add(null);
            }
            if (this.mUserIds.get(index) != null) {
                PackageManagerService.reportSettingsProblem(6, "Adding duplicate user id: " + uid + " name=" + name);
                return false;
            }
            this.mUserIds.set(index, obj);
            return true;
        } else if (this.mOtherUserIds.get(uid) != null) {
            PackageManagerService.reportSettingsProblem(6, "Adding duplicate shared id: " + uid + " name=" + name);
            return false;
        } else {
            this.mOtherUserIds.put(uid, obj);
            return true;
        }
    }

    public Object getUserIdLPr(int uid) {
        if (uid >= 10000) {
            int N = this.mUserIds.size();
            int index = uid - 10000;
            if (index < N) {
                return this.mUserIds.get(index);
            }
            return null;
        }
        return this.mOtherUserIds.get(uid);
    }

    private void removeUserIdLPw(int uid) {
        if (uid >= 10000) {
            int N = this.mUserIds.size();
            int index = uid - 10000;
            if (index < N) {
                this.mUserIds.set(index, null);
            }
        } else {
            this.mOtherUserIds.remove(uid);
        }
        setFirstAvailableUid(uid + 1);
    }

    private void replaceUserIdLPw(int uid, Object obj) {
        if (uid >= 10000) {
            int N = this.mUserIds.size();
            int index = uid - 10000;
            if (index < N) {
                this.mUserIds.set(index, obj);
                return;
            }
            return;
        }
        this.mOtherUserIds.put(uid, obj);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PreferredIntentResolver editPreferredActivitiesLPw(int userId) {
        PreferredIntentResolver pir = this.mPreferredActivities.get(userId);
        if (pir == null) {
            PreferredIntentResolver pir2 = new PreferredIntentResolver();
            this.mPreferredActivities.put(userId, pir2);
            return pir2;
        }
        return pir;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PersistentPreferredIntentResolver editPersistentPreferredActivitiesLPw(int userId) {
        PersistentPreferredIntentResolver ppir = this.mPersistentPreferredActivities.get(userId);
        if (ppir == null) {
            PersistentPreferredIntentResolver ppir2 = new PersistentPreferredIntentResolver();
            this.mPersistentPreferredActivities.put(userId, ppir2);
            return ppir2;
        }
        return ppir;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public CrossProfileIntentResolver editCrossProfileIntentResolverLPw(int userId) {
        CrossProfileIntentResolver cpir = this.mCrossProfileIntentResolvers.get(userId);
        if (cpir == null) {
            CrossProfileIntentResolver cpir2 = new CrossProfileIntentResolver();
            this.mCrossProfileIntentResolvers.put(userId, cpir2);
            return cpir2;
        }
        return cpir;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IntentFilterVerificationInfo getIntentFilterVerificationLPr(String packageName) {
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.w("PackageManager", "No package known: " + packageName);
                return null;
            }
            return null;
        }
        return ps.getIntentFilterVerificationInfo();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IntentFilterVerificationInfo createIntentFilterVerificationIfNeededLPw(String packageName, ArraySet<String> domains) {
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.w("PackageManager", "No package known: " + packageName);
                return null;
            }
            return null;
        }
        IntentFilterVerificationInfo ivi = ps.getIntentFilterVerificationInfo();
        if (ivi == null) {
            ivi = new IntentFilterVerificationInfo(packageName, domains);
            ps.setIntentFilterVerificationInfo(ivi);
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.d("PackageManager", "Creating new IntentFilterVerificationInfo for pkg: " + packageName);
            }
        } else {
            ivi.setDomains(domains);
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.d("PackageManager", "Setting domains to existing IntentFilterVerificationInfo for pkg: " + packageName + " and with domains: " + ivi.getDomainsString());
            }
        }
        return ivi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getIntentFilterVerificationStatusLPr(String packageName, int userId) {
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.w("PackageManager", "No package known: " + packageName);
                return 0;
            }
            return 0;
        }
        return (int) (ps.getDomainVerificationStatusForUser(userId) >> 32);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateIntentFilterVerificationStatusLPw(String packageName, int status, int userId) {
        PackageSetting current = this.mPackages.get(packageName);
        int alwaysGeneration = 0;
        if (current == null) {
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.w("PackageManager", "No package known: " + packageName);
            }
            return false;
        }
        if (status == 2) {
            alwaysGeneration = this.mNextAppLinkGeneration.get(userId) + 1;
            this.mNextAppLinkGeneration.put(userId, alwaysGeneration);
        }
        current.setDomainVerificationStatusForUser(status, alwaysGeneration, userId);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<IntentFilterVerificationInfo> getIntentFilterVerificationsLPr(String packageName) {
        if (packageName == null) {
            return Collections.emptyList();
        }
        ArrayList<IntentFilterVerificationInfo> result = new ArrayList<>();
        for (PackageSetting ps : this.mPackages.values()) {
            IntentFilterVerificationInfo ivi = ps.getIntentFilterVerificationInfo();
            if (ivi != null && !TextUtils.isEmpty(ivi.getPackageName()) && ivi.getPackageName().equalsIgnoreCase(packageName)) {
                result.add(ivi);
            }
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeIntentFilterVerificationLPw(String packageName, int userId) {
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.w("PackageManager", "No package known: " + packageName);
                return false;
            }
            return false;
        }
        ps.clearDomainVerificationStatusForUser(userId);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeIntentFilterVerificationLPw(String packageName, int[] userIds) {
        boolean result = false;
        for (int userId : userIds) {
            result |= removeIntentFilterVerificationLPw(packageName, userId);
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setDefaultBrowserPackageNameLPw(String packageName, int userId) {
        if (userId == -1) {
            return false;
        }
        if (packageName != null) {
            this.mDefaultBrowserApp.put(userId, packageName);
        } else {
            this.mDefaultBrowserApp.remove(userId);
        }
        writePackageRestrictionsLPr(userId);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getDefaultBrowserPackageNameLPw(int userId) {
        if (userId == -1) {
            return null;
        }
        return this.mDefaultBrowserApp.get(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setDefaultDialerPackageNameLPw(String packageName, int userId) {
        if (userId == -1) {
            return false;
        }
        this.mDefaultDialerApp.put(userId, packageName);
        writePackageRestrictionsLPr(userId);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getDefaultDialerPackageNameLPw(int userId) {
        if (userId == -1) {
            return null;
        }
        return this.mDefaultDialerApp.get(userId);
    }

    private File getUserPackagesStateFile(int userId) {
        File userDir = new File(new File(this.mSystemDir, DatabaseHelper.SoundModelContract.KEY_USERS), Integer.toString(userId));
        return new File(userDir, "package-restrictions.xml");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public File getUserRuntimePermissionsFile(int userId) {
        File userDir = new File(new File(this.mSystemDir, DatabaseHelper.SoundModelContract.KEY_USERS), Integer.toString(userId));
        return new File(userDir, RUNTIME_PERMISSIONS_FILE_NAME);
    }

    private File getUserPackagesStateBackupFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), "package-restrictions-backup.xml");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeAllUsersPackageRestrictionsLPr() {
        List<UserInfo> users = getAllUsers(UserManagerService.getInstance());
        if (users == null) {
            return;
        }
        for (UserInfo user : users) {
            writePackageRestrictionsLPr(user.id);
        }
    }

    void writeAllRuntimePermissionsLPr() {
        int[] userIds;
        for (int userId : UserManagerService.getInstance().getUserIds()) {
            this.mRuntimePermissionsPersistence.writePermissionsForUserAsyncLPr(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean areDefaultRuntimePermissionsGrantedLPr(int userId) {
        return this.mRuntimePermissionsPersistence.areDefaultRuntimPermissionsGrantedLPr(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onDefaultRuntimePermissionsGrantedLPr(int userId) {
        this.mRuntimePermissionsPersistence.onDefaultRuntimePermissionsGrantedLPr(userId);
    }

    public VersionInfo findOrCreateVersion(String volumeUuid) {
        VersionInfo ver = this.mVersion.get(volumeUuid);
        if (ver == null) {
            VersionInfo ver2 = new VersionInfo();
            this.mVersion.put(volumeUuid, ver2);
            return ver2;
        }
        return ver;
    }

    public VersionInfo getInternalVersion() {
        return this.mVersion.get(StorageManager.UUID_PRIVATE_INTERNAL);
    }

    public VersionInfo getExternalVersion() {
        return this.mVersion.get("primary_physical");
    }

    public void onVolumeForgotten(String fsUuid) {
        this.mVersion.remove(fsUuid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void readPreferredActivitiesLPw(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_ITEM)) {
                            PreferredActivity pa = new PreferredActivity(parser);
                            if (pa.mPref.getParseError() == null) {
                                editPreferredActivitiesLPw(userId).addFilter(pa);
                            } else {
                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <preferred-activity> " + pa.mPref.getParseError() + " at " + parser.getPositionDescription());
                            }
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "Unknown element under <preferred-activities>: " + parser.getName());
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

    private void readPersistentPreferredActivitiesLPw(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_ITEM)) {
                            PersistentPreferredActivity ppa = new PersistentPreferredActivity(parser);
                            editPersistentPreferredActivitiesLPw(userId).addFilter(ppa);
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "Unknown element under <persistent-preferred-activities>: " + parser.getName());
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

    private void readCrossProfileIntentFiltersLPw(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_ITEM)) {
                            CrossProfileIntentFilter cpif = new CrossProfileIntentFilter(parser);
                            editCrossProfileIntentResolverLPw(userId).addFilter(cpif);
                        } else {
                            String msg = "Unknown element under crossProfile-intent-filters: " + tagName;
                            PackageManagerService.reportSettingsProblem(5, msg);
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

    private void readDomainVerificationLPw(XmlPullParser parser, PackageSettingBase packageSetting) throws XmlPullParserException, IOException {
        IntentFilterVerificationInfo ivi = new IntentFilterVerificationInfo(parser);
        packageSetting.setIntentFilterVerificationInfo(ivi);
    }

    private void readRestoredIntentFilterVerifications(XmlPullParser parser) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_DOMAIN_VERIFICATION)) {
                            IntentFilterVerificationInfo ivi = new IntentFilterVerificationInfo(parser);
                            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                                Slog.i(TAG, "Restored IVI for " + ivi.getPackageName() + " status=" + ivi.getStatusString());
                            }
                            this.mRestoredIntentFilterVerifications.put(ivi.getPackageName(), ivi);
                        } else {
                            Slog.w(TAG, "Unknown element: " + tagName);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void readDefaultAppsLPw(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_DEFAULT_BROWSER)) {
                            String packageName = parser.getAttributeValue(null, "packageName");
                            this.mDefaultBrowserApp.put(userId, packageName);
                        } else if (tagName.equals(TAG_DEFAULT_DIALER)) {
                            String packageName2 = parser.getAttributeValue(null, "packageName");
                            this.mDefaultDialerApp.put(userId, packageName2);
                        } else {
                            String msg = "Unknown element under default-apps: " + parser.getName();
                            PackageManagerService.reportSettingsProblem(5, msg);
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

    void readBlockUninstallPackagesLPw(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        ArraySet<String> packages = new ArraySet<>();
        while (true) {
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth)) {
                break;
            } else if (type != 3 && type != 4) {
                String tagName = parser.getName();
                if (tagName.equals(TAG_BLOCK_UNINSTALL)) {
                    String packageName = parser.getAttributeValue(null, "packageName");
                    packages.add(packageName);
                } else {
                    String msg = "Unknown element under block-uninstall-packages: " + parser.getName();
                    PackageManagerService.reportSettingsProblem(5, msg);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        if (packages.isEmpty()) {
            this.mBlockUninstallPackages.remove(userId);
        } else {
            this.mBlockUninstallPackages.put(userId, packages);
        }
    }

    void readPackageRestrictionsLPr(int userId) {
        int i;
        File userPackagesStateFile;
        FileInputStream str;
        XmlPullParser parser;
        int type;
        char c;
        boolean z;
        int maxAppLinkGeneration;
        FileInputStream str2;
        int type2;
        int type3;
        boolean z2;
        String str3;
        char c2;
        int i2;
        int outerDepth;
        FileInputStream str4;
        File userPackagesStateFile2;
        int maxAppLinkGeneration2;
        int packageDepth;
        String hiddenStr;
        int type4;
        int i3;
        boolean z3;
        int packageDepth2;
        String hiddenStr2;
        Settings settings = this;
        int i4 = userId;
        FileInputStream str5 = null;
        File userPackagesStateFile3 = getUserPackagesStateFile(userId);
        File backupFile = getUserPackagesStateBackupFile(userId);
        int i5 = 4;
        if (backupFile.exists()) {
            try {
                str5 = new FileInputStream(backupFile);
                settings.mReadMessages.append("Reading from backup stopped packages file\n");
                PackageManagerService.reportSettingsProblem(4, "Need to read from backup stopped packages file");
                if (userPackagesStateFile3.exists()) {
                    Slog.w("PackageManager", "Cleaning up stopped packages file " + userPackagesStateFile3);
                    userPackagesStateFile3.delete();
                }
            } catch (IOException e) {
            }
        }
        FileInputStream str6 = str5;
        int i6 = 6;
        if (str6 == null) {
            try {
                if (!userPackagesStateFile3.exists()) {
                    try {
                        settings.mReadMessages.append("No stopped packages file found\n");
                        PackageManagerService.reportSettingsProblem(4, "No stopped packages file; assuming all started");
                        for (PackageSetting pkg : settings.mPackages.values()) {
                            File backupFile2 = backupFile;
                            File userPackagesStateFile4 = userPackagesStateFile3;
                            try {
                                pkg.setUserState(i4, 0L, 0, true, false, false, false, false, null, null, null, null, false, false, null, null, null, 0, 0, 0, null);
                                backupFile = backupFile2;
                                userPackagesStateFile3 = userPackagesStateFile4;
                                i6 = 6;
                                i4 = userId;
                            } catch (IOException e2) {
                                e = e2;
                                settings.mReadMessages.append("Error reading: " + e.toString());
                                PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                            } catch (XmlPullParserException e3) {
                                e = e3;
                                i = 6;
                                settings.mReadMessages.append("Error reading: " + e.toString());
                                PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                            }
                        }
                        return;
                    } catch (IOException e4) {
                        e = e4;
                        settings.mReadMessages.append("Error reading: " + e.toString());
                        PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                        Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                    } catch (XmlPullParserException e5) {
                        e = e5;
                        i = i6;
                        settings.mReadMessages.append("Error reading: " + e.toString());
                        PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                        Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                    }
                }
                try {
                    userPackagesStateFile = userPackagesStateFile3;
                    try {
                        str = new FileInputStream(userPackagesStateFile);
                    } catch (IOException e6) {
                        e = e6;
                        settings.mReadMessages.append("Error reading: " + e.toString());
                        PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                        Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                    } catch (XmlPullParserException e7) {
                        e = e7;
                        i = 6;
                        settings.mReadMessages.append("Error reading: " + e.toString());
                        PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                        Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                    }
                } catch (IOException e8) {
                    e = e8;
                    settings.mReadMessages.append("Error reading: " + e.toString());
                    PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                    Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                } catch (XmlPullParserException e9) {
                    e = e9;
                    i = 6;
                    settings.mReadMessages.append("Error reading: " + e.toString());
                    PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                    Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                }
            } catch (IOException e10) {
                e = e10;
            } catch (XmlPullParserException e11) {
                e = e11;
                i = 6;
            }
        } else {
            userPackagesStateFile = userPackagesStateFile3;
            str = str6;
        }
        try {
            parser = Xml.newPullParser();
            parser.setInput(str, StandardCharsets.UTF_8.name());
        } catch (IOException e12) {
            e = e12;
        } catch (XmlPullParserException e13) {
            e = e13;
            i = 6;
        }
        try {
            do {
                type = parser.next();
                c = 2;
                z = true;
                if (type == 2) {
                    break;
                }
            } while (type != 1);
            break;
            if (type != 2) {
                settings.mReadMessages.append("No start tag found in package restrictions file\n");
                PackageManagerService.reportSettingsProblem(5, "No start tag found in package manager stopped packages");
                return;
            }
            int outerDepth2 = parser.getDepth();
            String str7 = null;
            int maxAppLinkGeneration3 = 0;
            while (true) {
                int type5 = parser.next();
                if (type5 == z) {
                    maxAppLinkGeneration = maxAppLinkGeneration3;
                    str2 = str;
                    type2 = userId;
                } else if (type5 != 3 || parser.getDepth() > outerDepth2) {
                    if (type5 == 3) {
                        type3 = type5;
                        z2 = z;
                        str3 = str7;
                        c2 = c;
                        i2 = i5;
                        outerDepth = outerDepth2;
                        str4 = str;
                        userPackagesStateFile2 = userPackagesStateFile;
                        maxAppLinkGeneration2 = maxAppLinkGeneration3;
                    } else if (type5 == i5) {
                        type3 = type5;
                        z2 = z;
                        str3 = str7;
                        c2 = c;
                        i2 = i5;
                        outerDepth = outerDepth2;
                        str4 = str;
                        userPackagesStateFile2 = userPackagesStateFile;
                        maxAppLinkGeneration2 = maxAppLinkGeneration3;
                    } else {
                        try {
                            String tagName = parser.getName();
                            if (tagName.equals(TAG_PACKAGE)) {
                                try {
                                    String name = parser.getAttributeValue(str7, ATTR_NAME);
                                    PackageSetting ps = settings.mPackages.get(name);
                                    if (ps == null) {
                                        Slog.w("PackageManager", "No package known for stopped package " + name);
                                        XmlUtils.skipCurrentTag(parser);
                                    } else {
                                        int maxAppLinkGeneration4 = maxAppLinkGeneration3;
                                        long ceDataInode = XmlUtils.readLongAttribute(parser, ATTR_CE_DATA_INODE, 0L);
                                        boolean installed = XmlUtils.readBooleanAttribute(parser, ATTR_INSTALLED, z);
                                        boolean stopped = XmlUtils.readBooleanAttribute(parser, ATTR_STOPPED, false);
                                        boolean notLaunched = XmlUtils.readBooleanAttribute(parser, ATTR_NOT_LAUNCHED, false);
                                        String blockedStr = parser.getAttributeValue(str7, ATTR_BLOCKED);
                                        boolean hidden = blockedStr == null ? false : Boolean.parseBoolean(blockedStr);
                                        String hiddenStr3 = parser.getAttributeValue(str7, ATTR_HIDDEN);
                                        boolean hidden2 = hiddenStr3 == null ? hidden : Boolean.parseBoolean(hiddenStr3);
                                        boolean suspended = XmlUtils.readBooleanAttribute(parser, ATTR_SUSPENDED, false);
                                        String suspendingPackage = parser.getAttributeValue(null, ATTR_SUSPENDING_PACKAGE);
                                        String dialogMessage = parser.getAttributeValue(null, ATTR_SUSPEND_DIALOG_MESSAGE);
                                        outerDepth = outerDepth2;
                                        String suspendingPackage2 = (suspended && suspendingPackage == null) ? PackageManagerService.PLATFORM_PACKAGE_NAME : suspendingPackage;
                                        boolean blockUninstall = XmlUtils.readBooleanAttribute(parser, ATTR_BLOCK_UNINSTALL, false);
                                        boolean instantApp = XmlUtils.readBooleanAttribute(parser, ATTR_INSTANT_APP, false);
                                        boolean virtualPreload = XmlUtils.readBooleanAttribute(parser, ATTR_VIRTUAL_PRELOAD, false);
                                        int enabled = XmlUtils.readIntAttribute(parser, ATTR_ENABLED, 0);
                                        int i7 = 1;
                                        String enabledCaller = parser.getAttributeValue(null, ATTR_ENABLED_CALLER);
                                        String harmfulAppWarning = parser.getAttributeValue(null, ATTR_HARMFUL_APP_WARNING);
                                        int verifState = XmlUtils.readIntAttribute(parser, ATTR_DOMAIN_VERIFICATON_STATE, 0);
                                        int linkGeneration = XmlUtils.readIntAttribute(parser, ATTR_APP_LINK_GENERATION, 0);
                                        int maxAppLinkGeneration5 = linkGeneration > maxAppLinkGeneration4 ? linkGeneration : maxAppLinkGeneration4;
                                        int installReason = XmlUtils.readIntAttribute(parser, ATTR_INSTALL_REASON, 0);
                                        int packageDepth3 = parser.getDepth();
                                        ArraySet<String> enabledComponents = null;
                                        ArraySet<String> disabledComponents = null;
                                        PersistableBundle suspendedAppExtras = null;
                                        PersistableBundle suspendedLauncherExtras = null;
                                        while (true) {
                                            int packageDepth4 = packageDepth3;
                                            int type6 = parser.next();
                                            if (type6 != i7) {
                                                type4 = type6;
                                                if (type4 == 3 && parser.getDepth() <= packageDepth4) {
                                                    packageDepth = packageDepth4;
                                                    hiddenStr = hiddenStr3;
                                                }
                                                if (type4 == 3) {
                                                    packageDepth2 = packageDepth4;
                                                    hiddenStr2 = hiddenStr3;
                                                } else if (type4 != 4) {
                                                    String name2 = parser.getName();
                                                    char c3 = 65535;
                                                    packageDepth2 = packageDepth4;
                                                    int packageDepth5 = name2.hashCode();
                                                    hiddenStr2 = hiddenStr3;
                                                    if (packageDepth5 != -2027581689) {
                                                        if (packageDepth5 != -1963032286) {
                                                            if (packageDepth5 != -1592287551) {
                                                                if (packageDepth5 == -1422791362 && name2.equals(TAG_SUSPENDED_LAUNCHER_EXTRAS)) {
                                                                    c3 = 3;
                                                                }
                                                            } else if (name2.equals(TAG_SUSPENDED_APP_EXTRAS)) {
                                                                c3 = 2;
                                                            }
                                                        } else if (name2.equals(TAG_ENABLED_COMPONENTS)) {
                                                            c3 = 0;
                                                        }
                                                    } else if (name2.equals(TAG_DISABLED_COMPONENTS)) {
                                                        c3 = 1;
                                                    }
                                                    switch (c3) {
                                                        case 0:
                                                            enabledComponents = settings.readComponentsLPr(parser);
                                                            break;
                                                        case 1:
                                                            disabledComponents = settings.readComponentsLPr(parser);
                                                            break;
                                                        case 2:
                                                            suspendedAppExtras = PersistableBundle.restoreFromXml(parser);
                                                            break;
                                                        case 3:
                                                            suspendedLauncherExtras = PersistableBundle.restoreFromXml(parser);
                                                            break;
                                                        default:
                                                            Slog.wtf(TAG, "Unknown tag " + parser.getName() + " under tag " + TAG_PACKAGE);
                                                            break;
                                                    }
                                                } else {
                                                    packageDepth2 = packageDepth4;
                                                    hiddenStr2 = hiddenStr3;
                                                }
                                                packageDepth3 = packageDepth2;
                                                hiddenStr3 = hiddenStr2;
                                                i7 = 1;
                                            } else {
                                                packageDepth = packageDepth4;
                                                hiddenStr = hiddenStr3;
                                                type4 = type6;
                                            }
                                        }
                                        if (blockUninstall) {
                                            i3 = userId;
                                            z3 = true;
                                            try {
                                                settings.setBlockUninstallLPw(i3, name, true);
                                            } catch (IOException e14) {
                                                e = e14;
                                                settings.mReadMessages.append("Error reading: " + e.toString());
                                                PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                                                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                            } catch (XmlPullParserException e15) {
                                                e = e15;
                                                i = 6;
                                                settings.mReadMessages.append("Error reading: " + e.toString());
                                                PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                                                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                            }
                                        } else {
                                            i3 = userId;
                                            z3 = true;
                                        }
                                        c2 = 2;
                                        type3 = type4;
                                        i2 = 4;
                                        str4 = str;
                                        userPackagesStateFile2 = userPackagesStateFile;
                                        str3 = null;
                                        z2 = z3;
                                        try {
                                            ps.setUserState(i3, ceDataInode, enabled, installed, stopped, notLaunched, hidden2, suspended, suspendingPackage2, dialogMessage, suspendedAppExtras, suspendedLauncherExtras, instantApp, virtualPreload, enabledCaller, enabledComponents, disabledComponents, verifState, linkGeneration, installReason, harmfulAppWarning);
                                            maxAppLinkGeneration3 = maxAppLinkGeneration5;
                                            settings = this;
                                        } catch (IOException e16) {
                                            e = e16;
                                            settings = this;
                                            settings.mReadMessages.append("Error reading: " + e.toString());
                                            PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                                            Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                        } catch (XmlPullParserException e17) {
                                            e = e17;
                                            settings = this;
                                            i = 6;
                                            settings.mReadMessages.append("Error reading: " + e.toString());
                                            PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                                            Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                        }
                                    }
                                } catch (IOException e18) {
                                    e = e18;
                                    settings = this;
                                    settings.mReadMessages.append("Error reading: " + e.toString());
                                    PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                                    Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                } catch (XmlPullParserException e19) {
                                    e = e19;
                                    settings = this;
                                    i = 6;
                                    settings.mReadMessages.append("Error reading: " + e.toString());
                                    PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                                    Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                }
                            } else {
                                type3 = type5;
                                z2 = z;
                                str3 = str7;
                                c2 = c;
                                i2 = i5;
                                outerDepth = outerDepth2;
                                str4 = str;
                                userPackagesStateFile2 = userPackagesStateFile;
                                int maxAppLinkGeneration6 = maxAppLinkGeneration3;
                                try {
                                    if (tagName.equals("preferred-activities")) {
                                        settings = this;
                                        try {
                                            settings.readPreferredActivitiesLPw(parser, userId);
                                        } catch (IOException e20) {
                                            e = e20;
                                            settings.mReadMessages.append("Error reading: " + e.toString());
                                            PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                                            Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                        } catch (XmlPullParserException e21) {
                                            e = e21;
                                            i = 6;
                                            settings.mReadMessages.append("Error reading: " + e.toString());
                                            PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                                            Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
                                        }
                                    } else {
                                        settings = this;
                                        if (tagName.equals(TAG_PERSISTENT_PREFERRED_ACTIVITIES)) {
                                            settings.readPersistentPreferredActivitiesLPw(parser, userId);
                                        } else if (tagName.equals(TAG_CROSS_PROFILE_INTENT_FILTERS)) {
                                            settings.readCrossProfileIntentFiltersLPw(parser, userId);
                                        } else if (tagName.equals(TAG_DEFAULT_APPS)) {
                                            settings.readDefaultAppsLPw(parser, userId);
                                        } else if (tagName.equals(TAG_BLOCK_UNINSTALL_PACKAGES)) {
                                            settings.readBlockUninstallPackagesLPw(parser, userId);
                                        } else {
                                            Slog.w("PackageManager", "Unknown element under <stopped-packages>: " + parser.getName());
                                            XmlUtils.skipCurrentTag(parser);
                                        }
                                    }
                                    maxAppLinkGeneration3 = maxAppLinkGeneration6;
                                } catch (IOException e22) {
                                    e = e22;
                                    settings = this;
                                } catch (XmlPullParserException e23) {
                                    e = e23;
                                    settings = this;
                                }
                            }
                            outerDepth2 = outerDepth;
                            c = c2;
                            str7 = str3;
                            z = z2;
                            i5 = i2;
                            userPackagesStateFile = userPackagesStateFile2;
                            str = str4;
                        } catch (IOException e24) {
                            e = e24;
                        } catch (XmlPullParserException e25) {
                            e = e25;
                        }
                    }
                    maxAppLinkGeneration3 = maxAppLinkGeneration2;
                    outerDepth2 = outerDepth;
                    c = c2;
                    str7 = str3;
                    z = z2;
                    i5 = i2;
                    userPackagesStateFile = userPackagesStateFile2;
                    str = str4;
                } else {
                    maxAppLinkGeneration = maxAppLinkGeneration3;
                    str2 = str;
                    type2 = userId;
                }
            }
            try {
                str2.close();
                settings.mNextAppLinkGeneration.put(type2, maxAppLinkGeneration + 1);
            } catch (IOException e26) {
                e = e26;
                settings.mReadMessages.append("Error reading: " + e.toString());
                PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
            } catch (XmlPullParserException e27) {
                e = e27;
                i = 6;
                settings.mReadMessages.append("Error reading: " + e.toString());
                PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
            }
        } catch (IOException e28) {
            e = e28;
            settings.mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e);
            Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
        } catch (XmlPullParserException e29) {
            e = e29;
            i = 6;
            settings.mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(i, "Error reading stopped packages: " + e);
            Slog.wtf("PackageManager", "Error reading package manager stopped packages", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBlockUninstallLPw(int userId, String packageName, boolean blockUninstall) {
        ArraySet<String> packages = this.mBlockUninstallPackages.get(userId);
        if (blockUninstall) {
            if (packages == null) {
                packages = new ArraySet<>();
                this.mBlockUninstallPackages.put(userId, packages);
            }
            packages.add(packageName);
        } else if (packages != null) {
            packages.remove(packageName);
            if (packages.isEmpty()) {
                this.mBlockUninstallPackages.remove(userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getBlockUninstallLPr(int userId, String packageName) {
        ArraySet<String> packages = this.mBlockUninstallPackages.get(userId);
        if (packages == null) {
            return false;
        }
        return packages.contains(packageName);
    }

    private ArraySet<String> readComponentsLPr(XmlPullParser parser) throws IOException, XmlPullParserException {
        String componentName;
        ArraySet<String> components = null;
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth)) {
                break;
            } else if (type != 3 && type != 4) {
                String tagName = parser.getName();
                if (tagName.equals(TAG_ITEM) && (componentName = parser.getAttributeValue(null, ATTR_NAME)) != null) {
                    if (components == null) {
                        components = new ArraySet<>();
                    }
                    components.add(componentName);
                }
            }
        }
        return components;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writePreferredActivitiesLPr(XmlSerializer serializer, int userId, boolean full) throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, "preferred-activities");
        PreferredIntentResolver pir = this.mPreferredActivities.get(userId);
        if (pir != null) {
            for (PreferredActivity pa : pir.filterSet()) {
                serializer.startTag(null, TAG_ITEM);
                pa.writeToXml(serializer, full);
                serializer.endTag(null, TAG_ITEM);
            }
        }
        serializer.endTag(null, "preferred-activities");
    }

    void writePersistentPreferredActivitiesLPr(XmlSerializer serializer, int userId) throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_PERSISTENT_PREFERRED_ACTIVITIES);
        PersistentPreferredIntentResolver ppir = this.mPersistentPreferredActivities.get(userId);
        if (ppir != null) {
            for (PersistentPreferredActivity ppa : ppir.filterSet()) {
                serializer.startTag(null, TAG_ITEM);
                ppa.writeToXml(serializer);
                serializer.endTag(null, TAG_ITEM);
            }
        }
        serializer.endTag(null, TAG_PERSISTENT_PREFERRED_ACTIVITIES);
    }

    void writeCrossProfileIntentFiltersLPr(XmlSerializer serializer, int userId) throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_CROSS_PROFILE_INTENT_FILTERS);
        CrossProfileIntentResolver cpir = this.mCrossProfileIntentResolvers.get(userId);
        if (cpir != null) {
            for (CrossProfileIntentFilter cpif : cpir.filterSet()) {
                serializer.startTag(null, TAG_ITEM);
                cpif.writeToXml(serializer);
                serializer.endTag(null, TAG_ITEM);
            }
        }
        serializer.endTag(null, TAG_CROSS_PROFILE_INTENT_FILTERS);
    }

    void writeDomainVerificationsLPr(XmlSerializer serializer, IntentFilterVerificationInfo verificationInfo) throws IllegalArgumentException, IllegalStateException, IOException {
        if (verificationInfo != null && verificationInfo.getPackageName() != null) {
            serializer.startTag(null, TAG_DOMAIN_VERIFICATION);
            verificationInfo.writeToXml(serializer);
            if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(TAG, "Wrote domain verification for package: " + verificationInfo.getPackageName());
            }
            serializer.endTag(null, TAG_DOMAIN_VERIFICATION);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeAllDomainVerificationsLPr(XmlSerializer serializer, int userId) throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_ALL_INTENT_FILTER_VERIFICATION);
        int N = this.mPackages.size();
        for (int i = 0; i < N; i++) {
            PackageSetting ps = this.mPackages.valueAt(i);
            IntentFilterVerificationInfo ivi = ps.getIntentFilterVerificationInfo();
            if (ivi != null) {
                writeDomainVerificationsLPr(serializer, ivi);
            }
        }
        serializer.endTag(null, TAG_ALL_INTENT_FILTER_VERIFICATION);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void readAllDomainVerificationsLPr(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        this.mRestoredIntentFilterVerifications.clear();
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_DOMAIN_VERIFICATION)) {
                            IntentFilterVerificationInfo ivi = new IntentFilterVerificationInfo(parser);
                            String pkgName = ivi.getPackageName();
                            PackageSetting ps = this.mPackages.get(pkgName);
                            if (ps != null) {
                                ps.setIntentFilterVerificationInfo(ivi);
                                if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                                    Slog.d(TAG, "Restored IVI for existing app " + pkgName + " status=" + ivi.getStatusString());
                                }
                            } else {
                                this.mRestoredIntentFilterVerifications.put(pkgName, ivi);
                                if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                                    Slog.d(TAG, "Restored IVI for pending app " + pkgName + " status=" + ivi.getStatusString());
                                }
                            }
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "Unknown element under <all-intent-filter-verification>: " + parser.getName());
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

    public void processRestoredPermissionGrantLPr(String pkgName, String permission, boolean isGranted, int restoredFlagSet, int userId) {
        this.mRuntimePermissionsPersistence.rememberRestoredUserGrantLPr(pkgName, permission, isGranted, restoredFlagSet, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeDefaultAppsLPr(XmlSerializer serializer, int userId) throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_DEFAULT_APPS);
        String defaultBrowser = this.mDefaultBrowserApp.get(userId);
        if (!TextUtils.isEmpty(defaultBrowser)) {
            serializer.startTag(null, TAG_DEFAULT_BROWSER);
            serializer.attribute(null, "packageName", defaultBrowser);
            serializer.endTag(null, TAG_DEFAULT_BROWSER);
        }
        String defaultDialer = this.mDefaultDialerApp.get(userId);
        if (!TextUtils.isEmpty(defaultDialer)) {
            serializer.startTag(null, TAG_DEFAULT_DIALER);
            serializer.attribute(null, "packageName", defaultDialer);
            serializer.endTag(null, TAG_DEFAULT_DIALER);
        }
        serializer.endTag(null, TAG_DEFAULT_APPS);
    }

    void writeBlockUninstallPackagesLPr(XmlSerializer serializer, int userId) throws IOException {
        ArraySet<String> packages = this.mBlockUninstallPackages.get(userId);
        if (packages != null) {
            serializer.startTag(null, TAG_BLOCK_UNINSTALL_PACKAGES);
            for (int i = 0; i < packages.size(); i++) {
                serializer.startTag(null, TAG_BLOCK_UNINSTALL);
                serializer.attribute(null, "packageName", packages.valueAt(i));
                serializer.endTag(null, TAG_BLOCK_UNINSTALL);
            }
            serializer.endTag(null, TAG_BLOCK_UNINSTALL_PACKAGES);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writePackageRestrictionsLPr(int userId) {
        SystemClock.uptimeMillis();
        File userPackagesStateFile = getUserPackagesStateFile(userId);
        File backupFile = getUserPackagesStateBackupFile(userId);
        new File(userPackagesStateFile.getParent()).mkdirs();
        if (userPackagesStateFile.exists()) {
            if (!backupFile.exists()) {
                if (!userPackagesStateFile.renameTo(backupFile)) {
                    Slog.wtf("PackageManager", "Unable to backup user packages state file, current changes will be lost at reboot");
                    return;
                }
            } else {
                userPackagesStateFile.delete();
                Slog.w("PackageManager", "Preserving older stopped packages backup");
            }
        }
        try {
            FileOutputStream fstr = new FileOutputStream(userPackagesStateFile);
            BufferedOutputStream str = new BufferedOutputStream(fstr);
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(str, StandardCharsets.UTF_8.name());
            String str2 = null;
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startTag(null, TAG_PACKAGE_RESTRICTIONS);
            for (PackageSetting pkg : this.mPackages.values()) {
                PackageUserState ustate = pkg.readUserState(userId);
                serializer.startTag(str2, TAG_PACKAGE);
                serializer.attribute(str2, ATTR_NAME, pkg.name);
                if (ustate.ceDataInode != 0) {
                    XmlUtils.writeLongAttribute(serializer, ATTR_CE_DATA_INODE, ustate.ceDataInode);
                }
                if (!ustate.installed) {
                    serializer.attribute(null, ATTR_INSTALLED, "false");
                }
                if (ustate.stopped) {
                    serializer.attribute(null, ATTR_STOPPED, "true");
                }
                if (ustate.notLaunched) {
                    serializer.attribute(null, ATTR_NOT_LAUNCHED, "true");
                }
                if (ustate.hidden) {
                    serializer.attribute(null, ATTR_HIDDEN, "true");
                }
                if (ustate.suspended) {
                    serializer.attribute(null, ATTR_SUSPENDED, "true");
                    if (ustate.suspendingPackage != null) {
                        serializer.attribute(null, ATTR_SUSPENDING_PACKAGE, ustate.suspendingPackage);
                    }
                    if (ustate.dialogMessage != null) {
                        serializer.attribute(null, ATTR_SUSPEND_DIALOG_MESSAGE, ustate.dialogMessage);
                    }
                    if (ustate.suspendedAppExtras != null) {
                        serializer.startTag(null, TAG_SUSPENDED_APP_EXTRAS);
                        try {
                            ustate.suspendedAppExtras.saveToXml(serializer);
                        } catch (XmlPullParserException xmle) {
                            Slog.wtf(TAG, "Exception while trying to write suspendedAppExtras for " + pkg + ". Will be lost on reboot", xmle);
                        }
                        serializer.endTag(null, TAG_SUSPENDED_APP_EXTRAS);
                    }
                    if (ustate.suspendedLauncherExtras != null) {
                        serializer.startTag(null, TAG_SUSPENDED_LAUNCHER_EXTRAS);
                        try {
                            ustate.suspendedLauncherExtras.saveToXml(serializer);
                        } catch (XmlPullParserException xmle2) {
                            Slog.wtf(TAG, "Exception while trying to write suspendedLauncherExtras for " + pkg + ". Will be lost on reboot", xmle2);
                        }
                        serializer.endTag(null, TAG_SUSPENDED_LAUNCHER_EXTRAS);
                    }
                }
                if (ustate.instantApp) {
                    serializer.attribute(null, ATTR_INSTANT_APP, "true");
                }
                if (ustate.virtualPreload) {
                    serializer.attribute(null, ATTR_VIRTUAL_PRELOAD, "true");
                }
                if (ustate.enabled != 0) {
                    serializer.attribute(null, ATTR_ENABLED, Integer.toString(ustate.enabled));
                    if (ustate.lastDisableAppCaller != null) {
                        serializer.attribute(null, ATTR_ENABLED_CALLER, ustate.lastDisableAppCaller);
                    }
                }
                if (ustate.domainVerificationStatus != 0) {
                    XmlUtils.writeIntAttribute(serializer, ATTR_DOMAIN_VERIFICATON_STATE, ustate.domainVerificationStatus);
                }
                if (ustate.appLinkGeneration != 0) {
                    XmlUtils.writeIntAttribute(serializer, ATTR_APP_LINK_GENERATION, ustate.appLinkGeneration);
                }
                if (ustate.installReason != 0) {
                    serializer.attribute(null, ATTR_INSTALL_REASON, Integer.toString(ustate.installReason));
                }
                if (ustate.harmfulAppWarning != null) {
                    serializer.attribute(null, ATTR_HARMFUL_APP_WARNING, ustate.harmfulAppWarning);
                }
                if (!ArrayUtils.isEmpty(ustate.enabledComponents)) {
                    serializer.startTag(null, TAG_ENABLED_COMPONENTS);
                    Iterator it = ustate.enabledComponents.iterator();
                    while (it.hasNext()) {
                        String name = (String) it.next();
                        serializer.startTag(null, TAG_ITEM);
                        serializer.attribute(null, ATTR_NAME, name);
                        serializer.endTag(null, TAG_ITEM);
                    }
                    serializer.endTag(null, TAG_ENABLED_COMPONENTS);
                }
                if (!ArrayUtils.isEmpty(ustate.disabledComponents)) {
                    serializer.startTag(null, TAG_DISABLED_COMPONENTS);
                    Iterator it2 = ustate.disabledComponents.iterator();
                    while (it2.hasNext()) {
                        String name2 = (String) it2.next();
                        serializer.startTag(null, TAG_ITEM);
                        serializer.attribute(null, ATTR_NAME, name2);
                        serializer.endTag(null, TAG_ITEM);
                    }
                    serializer.endTag(null, TAG_DISABLED_COMPONENTS);
                }
                serializer.endTag(null, TAG_PACKAGE);
                str2 = null;
            }
            writePreferredActivitiesLPr(serializer, userId, true);
            writePersistentPreferredActivitiesLPr(serializer, userId);
            writeCrossProfileIntentFiltersLPr(serializer, userId);
            writeDefaultAppsLPr(serializer, userId);
            writeBlockUninstallPackagesLPr(serializer, userId);
            serializer.endTag(null, TAG_PACKAGE_RESTRICTIONS);
            serializer.endDocument();
            str.flush();
            FileUtils.sync(fstr);
            str.close();
            backupFile.delete();
            FileUtils.setPermissions(userPackagesStateFile.toString(), 432, -1, -1);
        } catch (IOException e) {
            Slog.wtf("PackageManager", "Unable to write package manager user packages state,  current changes will be lost at reboot", e);
            if (userPackagesStateFile.exists() && !userPackagesStateFile.delete()) {
                Log.i("PackageManager", "Failed to clean up mangled file: " + this.mStoppedPackagesFilename);
            }
        }
    }

    void readInstallPermissionsLPr(XmlPullParser parser, PermissionsState permissionsState) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            boolean granted = true;
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_ITEM)) {
                            String name = parser.getAttributeValue(null, ATTR_NAME);
                            BasePermission bp = this.mPermissions.getPermission(name);
                            if (bp == null) {
                                Slog.w("PackageManager", "Unknown permission: " + name);
                                XmlUtils.skipCurrentTag(parser);
                            } else {
                                String grantedStr = parser.getAttributeValue(null, ATTR_GRANTED);
                                int flags = 0;
                                if (grantedStr != null && !Boolean.parseBoolean(grantedStr)) {
                                    granted = false;
                                }
                                String flagsStr = parser.getAttributeValue(null, "flags");
                                if (flagsStr != null) {
                                    flags = Integer.parseInt(flagsStr, 16);
                                }
                                if (granted) {
                                    if (permissionsState.grantInstallPermission(bp) == -1) {
                                        Slog.w("PackageManager", "Permission already added: " + name);
                                        XmlUtils.skipCurrentTag(parser);
                                    } else {
                                        permissionsState.updatePermissionFlags(bp, -1, 255, flags);
                                    }
                                } else if (permissionsState.revokeInstallPermission(bp) == -1) {
                                    Slog.w("PackageManager", "Permission already added: " + name);
                                    XmlUtils.skipCurrentTag(parser);
                                } else {
                                    permissionsState.updatePermissionFlags(bp, -1, 255, flags);
                                }
                            }
                        } else {
                            Slog.w("PackageManager", "Unknown element under <permissions>: " + parser.getName());
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

    void writePermissionsLPr(XmlSerializer serializer, List<PermissionsState.PermissionState> permissionStates) throws IOException {
        if (permissionStates.isEmpty()) {
            return;
        }
        serializer.startTag(null, TAG_PERMISSIONS);
        for (PermissionsState.PermissionState permissionState : permissionStates) {
            serializer.startTag(null, TAG_ITEM);
            serializer.attribute(null, ATTR_NAME, permissionState.getName());
            serializer.attribute(null, ATTR_GRANTED, String.valueOf(permissionState.isGranted()));
            serializer.attribute(null, "flags", Integer.toHexString(permissionState.getFlags()));
            serializer.endTag(null, TAG_ITEM);
        }
        serializer.endTag(null, TAG_PERMISSIONS);
    }

    void writeChildPackagesLPw(XmlSerializer serializer, List<String> childPackageNames) throws IOException {
        if (childPackageNames == null) {
            return;
        }
        int childCount = childPackageNames.size();
        for (int i = 0; i < childCount; i++) {
            String childPackageName = childPackageNames.get(i);
            serializer.startTag(null, TAG_CHILD_PACKAGE);
            serializer.attribute(null, ATTR_NAME, childPackageName);
            serializer.endTag(null, TAG_CHILD_PACKAGE);
        }
    }

    void readUsesStaticLibLPw(XmlPullParser parser, PackageSetting outPs) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String libName = parser.getAttributeValue(null, ATTR_NAME);
                        String libVersionStr = parser.getAttributeValue(null, "version");
                        long libVersion = -1;
                        try {
                            libVersion = Long.parseLong(libVersionStr);
                        } catch (NumberFormatException e) {
                        }
                        if (libName != null && libVersion >= 0) {
                            outPs.usesStaticLibraries = (String[]) ArrayUtils.appendElement(String.class, outPs.usesStaticLibraries, libName);
                            outPs.usesStaticLibrariesVersions = ArrayUtils.appendLong(outPs.usesStaticLibrariesVersions, libVersion);
                        }
                        XmlUtils.skipCurrentTag(parser);
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    void writeUsesStaticLibLPw(XmlSerializer serializer, String[] usesStaticLibraries, long[] usesStaticLibraryVersions) throws IOException {
        if (ArrayUtils.isEmpty(usesStaticLibraries) || ArrayUtils.isEmpty(usesStaticLibraryVersions) || usesStaticLibraries.length != usesStaticLibraryVersions.length) {
            return;
        }
        int libCount = usesStaticLibraries.length;
        for (int i = 0; i < libCount; i++) {
            String libName = usesStaticLibraries[i];
            long libVersion = usesStaticLibraryVersions[i];
            serializer.startTag(null, TAG_USES_STATIC_LIB);
            serializer.attribute(null, ATTR_NAME, libName);
            serializer.attribute(null, "version", Long.toString(libVersion));
            serializer.endTag(null, TAG_USES_STATIC_LIB);
        }
    }

    void readStoppedLPw() {
        int type;
        FileInputStream str = null;
        if (this.mBackupStoppedPackagesFilename.exists()) {
            try {
                str = new FileInputStream(this.mBackupStoppedPackagesFilename);
                this.mReadMessages.append("Reading from backup stopped packages file\n");
                PackageManagerService.reportSettingsProblem(4, "Need to read from backup stopped packages file");
                if (this.mSettingsFilename.exists()) {
                    Slog.w("PackageManager", "Cleaning up stopped packages file " + this.mStoppedPackagesFilename);
                    this.mStoppedPackagesFilename.delete();
                }
            } catch (IOException e) {
            }
        }
        if (str == null) {
            try {
                if (!this.mStoppedPackagesFilename.exists()) {
                    this.mReadMessages.append("No stopped packages file found\n");
                    PackageManagerService.reportSettingsProblem(4, "No stopped packages file file; assuming all started");
                    for (PackageSetting pkg : this.mPackages.values()) {
                        pkg.setStopped(false, 0);
                        pkg.setNotLaunched(false, 0);
                    }
                    return;
                }
                str = new FileInputStream(this.mStoppedPackagesFilename);
            } catch (IOException e2) {
                StringBuilder sb = this.mReadMessages;
                sb.append("Error reading: " + e2.toString());
                PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e2);
                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e2);
                return;
            } catch (XmlPullParserException e3) {
                StringBuilder sb2 = this.mReadMessages;
                sb2.append("Error reading: " + e3.toString());
                PackageManagerService.reportSettingsProblem(6, "Error reading stopped packages: " + e3);
                Slog.wtf("PackageManager", "Error reading package manager stopped packages", e3);
                return;
            }
        }
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(str, null);
        while (true) {
            type = parser.next();
            if (type == 2 || type == 1) {
                break;
            }
        }
        if (type != 2) {
            this.mReadMessages.append("No start tag found in stopped packages file\n");
            PackageManagerService.reportSettingsProblem(5, "No start tag found in package manager stopped packages");
            return;
        }
        int outerDepth = parser.getDepth();
        while (true) {
            int type2 = parser.next();
            if (type2 == 1 || (type2 == 3 && parser.getDepth() <= outerDepth)) {
                break;
            } else if (type2 != 3 && type2 != 4) {
                String tagName = parser.getName();
                if (tagName.equals(TAG_PACKAGE)) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    PackageSetting ps = this.mPackages.get(name);
                    if (ps != null) {
                        ps.setStopped(true, 0);
                        if ("1".equals(parser.getAttributeValue(null, ATTR_NOT_LAUNCHED))) {
                            ps.setNotLaunched(true, 0);
                        }
                    } else {
                        Slog.w("PackageManager", "No package known for stopped package " + name);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else {
                    Slog.w("PackageManager", "Unknown element under <stopped-packages>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        str.close();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeLPr() {
        SystemClock.uptimeMillis();
        if (this.mSettingsFilename.exists()) {
            if (!this.mBackupSettingsFilename.exists()) {
                if (!this.mSettingsFilename.renameTo(this.mBackupSettingsFilename)) {
                    Slog.wtf("PackageManager", "Unable to backup package manager settings,  current changes will be lost at reboot");
                    return;
                }
            } else {
                this.mSettingsFilename.delete();
                Slog.w("PackageManager", "Preserving older settings backup");
            }
        }
        this.mPastSignatures.clear();
        try {
            FileOutputStream fstr = new FileOutputStream(this.mSettingsFilename);
            BufferedOutputStream str = new BufferedOutputStream(fstr);
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(str, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startTag(null, "packages");
            for (int i = 0; i < this.mVersion.size(); i++) {
                String volumeUuid = this.mVersion.keyAt(i);
                VersionInfo ver = this.mVersion.valueAt(i);
                serializer.startTag(null, "version");
                XmlUtils.writeStringAttribute(serializer, ATTR_VOLUME_UUID, volumeUuid);
                XmlUtils.writeIntAttribute(serializer, ATTR_SDK_VERSION, ver.sdkVersion);
                XmlUtils.writeIntAttribute(serializer, ATTR_DATABASE_VERSION, ver.databaseVersion);
                XmlUtils.writeStringAttribute(serializer, ATTR_FINGERPRINT, ver.fingerprint);
                serializer.endTag(null, "version");
            }
            if (this.mVerifierDeviceIdentity != null) {
                serializer.startTag(null, "verifier");
                serializer.attribute(null, "device", this.mVerifierDeviceIdentity.toString());
                serializer.endTag(null, "verifier");
            }
            if (this.mReadExternalStorageEnforced != null) {
                serializer.startTag(null, TAG_READ_EXTERNAL_STORAGE);
                serializer.attribute(null, ATTR_ENFORCEMENT, this.mReadExternalStorageEnforced.booleanValue() ? "1" : "0");
                serializer.endTag(null, TAG_READ_EXTERNAL_STORAGE);
            }
            serializer.startTag(null, "permission-trees");
            this.mPermissions.writePermissionTrees(serializer);
            serializer.endTag(null, "permission-trees");
            serializer.startTag(null, "permissions");
            this.mPermissions.writePermissions(serializer);
            serializer.endTag(null, "permissions");
            for (PackageSetting pkg : this.mPackages.values()) {
                writePackageLPr(serializer, pkg);
            }
            for (PackageSetting pkg2 : this.mDisabledSysPackages.values()) {
                writeDisabledSysPackageLPr(serializer, pkg2);
            }
            for (SharedUserSetting usr : this.mSharedUsers.values()) {
                serializer.startTag(null, TAG_SHARED_USER);
                serializer.attribute(null, ATTR_NAME, usr.name);
                serializer.attribute(null, "userId", Integer.toString(usr.userId));
                usr.signatures.writeXml(serializer, "sigs", this.mPastSignatures);
                writePermissionsLPr(serializer, usr.getPermissionsState().getInstallPermissionStates());
                serializer.endTag(null, TAG_SHARED_USER);
            }
            if (this.mPackagesToBeCleaned.size() > 0) {
                Iterator<PackageCleanItem> it = this.mPackagesToBeCleaned.iterator();
                while (it.hasNext()) {
                    PackageCleanItem item = it.next();
                    String userStr = Integer.toString(item.userId);
                    serializer.startTag(null, "cleaning-package");
                    serializer.attribute(null, ATTR_NAME, item.packageName);
                    serializer.attribute(null, ATTR_CODE, item.andCode ? "true" : "false");
                    serializer.attribute(null, ATTR_USER, userStr);
                    serializer.endTag(null, "cleaning-package");
                }
            }
            if (this.mRenamedPackages.size() > 0) {
                for (Map.Entry<String, String> e : this.mRenamedPackages.entrySet()) {
                    serializer.startTag(null, "renamed-package");
                    serializer.attribute(null, "new", e.getKey());
                    serializer.attribute(null, "old", e.getValue());
                    serializer.endTag(null, "renamed-package");
                }
            }
            int numIVIs = this.mRestoredIntentFilterVerifications.size();
            if (numIVIs > 0) {
                if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                    Slog.i(TAG, "Writing restored-ivi entries to packages.xml");
                }
                serializer.startTag(null, "restored-ivi");
                for (int i2 = 0; i2 < numIVIs; i2++) {
                    IntentFilterVerificationInfo ivi = this.mRestoredIntentFilterVerifications.valueAt(i2);
                    writeDomainVerificationsLPr(serializer, ivi);
                }
                serializer.endTag(null, "restored-ivi");
            } else if (PackageManagerService.DEBUG_DOMAIN_VERIFICATION) {
                Slog.i(TAG, "  no restored IVI entries to write");
            }
            this.mKeySetManagerService.writeKeySetManagerServiceLPr(serializer);
            serializer.endTag(null, "packages");
            serializer.endDocument();
            str.flush();
            FileUtils.sync(fstr);
            str.close();
            this.mBackupSettingsFilename.delete();
            FileUtils.setPermissions(this.mSettingsFilename.toString(), 432, -1, -1);
            writeKernelMappingLPr();
            writePackageListLPr();
            writeAllUsersPackageRestrictionsLPr();
            writeAllRuntimePermissionsLPr();
        } catch (IOException e2) {
            Slog.wtf("PackageManager", "Unable to write package manager settings, current changes will be lost at reboot", e2);
            if (this.mSettingsFilename.exists() && !this.mSettingsFilename.delete()) {
                Slog.wtf("PackageManager", "Failed to clean up mangled file: " + this.mSettingsFilename);
            }
        }
    }

    private void writeKernelRemoveUserLPr(int userId) {
        if (this.mKernelMappingFilename == null) {
            return;
        }
        File removeUserIdFile = new File(this.mKernelMappingFilename, "remove_userid");
        writeIntToFile(removeUserIdFile, userId);
    }

    void writeKernelMappingLPr() {
        if (this.mKernelMappingFilename == null) {
            return;
        }
        String[] known = this.mKernelMappingFilename.list();
        ArraySet<String> knownSet = new ArraySet<>(known.length);
        int i = 0;
        for (String name : known) {
            knownSet.add(name);
        }
        for (PackageSetting ps : this.mPackages.values()) {
            knownSet.remove(ps.name);
            writeKernelMappingLPr(ps);
        }
        while (true) {
            int i2 = i;
            int i3 = knownSet.size();
            if (i2 < i3) {
                String name2 = knownSet.valueAt(i2);
                this.mKernelMapping.remove(name2);
                new File(this.mKernelMappingFilename, name2).delete();
                i = i2 + 1;
            } else {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeKernelMappingLPr(PackageSetting ps) {
        if (this.mKernelMappingFilename == null || ps == null || ps.name == null) {
            return;
        }
        KernelPackageState cur = this.mKernelMapping.get(ps.name);
        boolean userIdsChanged = true;
        boolean firstTime = cur == null;
        int[] excludedUserIds = ps.getNotInstalledUserIds();
        if (!firstTime && Arrays.equals(excludedUserIds, cur.excludedUserIds)) {
            userIdsChanged = false;
        }
        File dir = new File(this.mKernelMappingFilename, ps.name);
        if (firstTime) {
            dir.mkdir();
            cur = new KernelPackageState();
            this.mKernelMapping.put(ps.name, cur);
        }
        if (cur.appId != ps.appId) {
            File appIdFile = new File(dir, "appid");
            writeIntToFile(appIdFile, ps.appId);
        }
        if (userIdsChanged) {
            for (int i = 0; i < excludedUserIds.length; i++) {
                if (cur.excludedUserIds == null || !ArrayUtils.contains(cur.excludedUserIds, excludedUserIds[i])) {
                    writeIntToFile(new File(dir, "excluded_userids"), excludedUserIds[i]);
                }
            }
            if (cur.excludedUserIds != null) {
                for (int i2 = 0; i2 < cur.excludedUserIds.length; i2++) {
                    if (!ArrayUtils.contains(excludedUserIds, cur.excludedUserIds[i2])) {
                        writeIntToFile(new File(dir, "clear_userid"), cur.excludedUserIds[i2]);
                    }
                }
            }
            cur.excludedUserIds = excludedUserIds;
        }
    }

    private void writeIntToFile(File file, int value) {
        try {
            FileUtils.bytesToFile(file.getAbsolutePath(), Integer.toString(value).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Slog.w(TAG, "Couldn't write " + value + " to " + file.getAbsolutePath());
        }
    }

    void writePackageListLPr() {
        writePackageListLPr(-1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writePackageListLPr(int creatingUserId) {
        List<UserInfo> users = UserManagerService.getInstance().getUsers(true);
        int[] userIds = new int[users.size()];
        for (int i = 0; i < userIds.length; i++) {
            userIds[i] = users.get(i).id;
        }
        if (creatingUserId != -1) {
            userIds = ArrayUtils.appendInt(userIds, creatingUserId);
        }
        int[] userIds2 = userIds;
        File tempFile = new File(this.mPackageListFilename.getAbsolutePath() + ".tmp");
        JournaledFile journal = new JournaledFile(this.mPackageListFilename, tempFile);
        File writeTarget = journal.chooseForWrite();
        BufferedWriter writer = null;
        try {
            FileOutputStream fstr = new FileOutputStream(writeTarget);
            writer = new BufferedWriter(new OutputStreamWriter(fstr, Charset.defaultCharset()));
            FileUtils.setPermissions(fstr.getFD(), 416, 1000, 1032);
            StringBuilder sb = new StringBuilder();
            for (PackageSetting pkg : this.mPackages.values()) {
                if (pkg.pkg != null && pkg.pkg.applicationInfo != null && pkg.pkg.applicationInfo.dataDir != null) {
                    ApplicationInfo ai = pkg.pkg.applicationInfo;
                    String dataPath = ai.dataDir;
                    boolean isDebug = (ai.flags & 2) != 0;
                    int[] gids = pkg.getPermissionsState().computeGids(userIds2);
                    if (dataPath.indexOf(32) < 0) {
                        sb.setLength(0);
                        sb.append(ai.packageName);
                        sb.append(" ");
                        sb.append(ai.uid);
                        sb.append(isDebug ? " 1 " : " 0 ");
                        sb.append(dataPath);
                        sb.append(" ");
                        sb.append(ai.seInfo);
                        sb.append(" ");
                        if (gids != null && gids.length > 0) {
                            sb.append(gids[0]);
                            for (int i2 = 1; i2 < gids.length; i2++) {
                                sb.append(",");
                                sb.append(gids[i2]);
                            }
                        } else {
                            sb.append("none");
                        }
                        sb.append("\n");
                        writer.append((CharSequence) sb);
                    }
                }
                if (!PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkg.name)) {
                    Slog.w(TAG, "Skipping " + pkg + " due to missing metadata");
                }
            }
            writer.flush();
            FileUtils.sync(fstr);
            writer.close();
            journal.commit();
        } catch (Exception e) {
            Slog.wtf(TAG, "Failed to write packages.list", e);
            IoUtils.closeQuietly(writer);
            journal.rollback();
        }
    }

    void writeDisabledSysPackageLPr(XmlSerializer serializer, PackageSetting pkg) throws IOException {
        serializer.startTag(null, "updated-package");
        serializer.attribute(null, ATTR_NAME, pkg.name);
        if (pkg.realName != null) {
            serializer.attribute(null, "realName", pkg.realName);
        }
        serializer.attribute(null, "codePath", pkg.codePathString);
        serializer.attribute(null, "ft", Long.toHexString(pkg.timeStamp));
        serializer.attribute(null, "it", Long.toHexString(pkg.firstInstallTime));
        serializer.attribute(null, "ut", Long.toHexString(pkg.lastUpdateTime));
        serializer.attribute(null, "version", String.valueOf(pkg.versionCode));
        if (!pkg.resourcePathString.equals(pkg.codePathString)) {
            serializer.attribute(null, "resourcePath", pkg.resourcePathString);
        }
        if (pkg.legacyNativeLibraryPathString != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.legacyNativeLibraryPathString);
        }
        if (pkg.primaryCpuAbiString != null) {
            serializer.attribute(null, "primaryCpuAbi", pkg.primaryCpuAbiString);
        }
        if (pkg.secondaryCpuAbiString != null) {
            serializer.attribute(null, "secondaryCpuAbi", pkg.secondaryCpuAbiString);
        }
        if (pkg.cpuAbiOverrideString != null) {
            serializer.attribute(null, "cpuAbiOverride", pkg.cpuAbiOverrideString);
        }
        if (pkg.sharedUser == null) {
            serializer.attribute(null, "userId", Integer.toString(pkg.appId));
        } else {
            serializer.attribute(null, "sharedUserId", Integer.toString(pkg.appId));
        }
        if (pkg.parentPackageName != null) {
            serializer.attribute(null, "parentPackageName", pkg.parentPackageName);
        }
        writeChildPackagesLPw(serializer, pkg.childPackageNames);
        writeUsesStaticLibLPw(serializer, pkg.usesStaticLibraries, pkg.usesStaticLibrariesVersions);
        if (pkg.sharedUser == null) {
            writePermissionsLPr(serializer, pkg.getPermissionsState().getInstallPermissionStates());
        }
        serializer.endTag(null, "updated-package");
    }

    void writePackageLPr(XmlSerializer serializer, PackageSetting pkg) throws IOException {
        serializer.startTag(null, "package");
        serializer.attribute(null, ATTR_NAME, pkg.name);
        if (pkg.realName != null) {
            serializer.attribute(null, "realName", pkg.realName);
        }
        serializer.attribute(null, "codePath", pkg.codePathString);
        if (!pkg.resourcePathString.equals(pkg.codePathString)) {
            serializer.attribute(null, "resourcePath", pkg.resourcePathString);
        }
        if (pkg.legacyNativeLibraryPathString != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.legacyNativeLibraryPathString);
        }
        if (pkg.primaryCpuAbiString != null) {
            serializer.attribute(null, "primaryCpuAbi", pkg.primaryCpuAbiString);
        }
        if (pkg.secondaryCpuAbiString != null) {
            serializer.attribute(null, "secondaryCpuAbi", pkg.secondaryCpuAbiString);
        }
        if (pkg.cpuAbiOverrideString != null) {
            serializer.attribute(null, "cpuAbiOverride", pkg.cpuAbiOverrideString);
        }
        serializer.attribute(null, "publicFlags", Integer.toString(pkg.pkgFlags));
        serializer.attribute(null, "privateFlags", Integer.toString(pkg.pkgPrivateFlags));
        serializer.attribute(null, "ft", Long.toHexString(pkg.timeStamp));
        serializer.attribute(null, "it", Long.toHexString(pkg.firstInstallTime));
        serializer.attribute(null, "ut", Long.toHexString(pkg.lastUpdateTime));
        serializer.attribute(null, "version", String.valueOf(pkg.versionCode));
        if (pkg.sharedUser == null) {
            serializer.attribute(null, "userId", Integer.toString(pkg.appId));
        } else {
            serializer.attribute(null, "sharedUserId", Integer.toString(pkg.appId));
        }
        if (pkg.uidError) {
            serializer.attribute(null, "uidError", "true");
        }
        if (pkg.installerPackageName != null) {
            serializer.attribute(null, "installer", pkg.installerPackageName);
        }
        if (pkg.isOrphaned) {
            serializer.attribute(null, "isOrphaned", "true");
        }
        if (pkg.volumeUuid != null) {
            serializer.attribute(null, ATTR_VOLUME_UUID, pkg.volumeUuid);
        }
        if (pkg.categoryHint != -1) {
            serializer.attribute(null, "categoryHint", Integer.toString(pkg.categoryHint));
        }
        if (pkg.parentPackageName != null) {
            serializer.attribute(null, "parentPackageName", pkg.parentPackageName);
        }
        if (pkg.updateAvailable) {
            serializer.attribute(null, "updateAvailable", "true");
        }
        writeChildPackagesLPw(serializer, pkg.childPackageNames);
        writeUsesStaticLibLPw(serializer, pkg.usesStaticLibraries, pkg.usesStaticLibrariesVersions);
        pkg.signatures.writeXml(serializer, "sigs", this.mPastSignatures);
        writePermissionsLPr(serializer, pkg.getPermissionsState().getInstallPermissionStates());
        writeSigningKeySetLPr(serializer, pkg.keySetData);
        writeUpgradeKeySetsLPr(serializer, pkg.keySetData);
        writeKeySetAliasesLPr(serializer, pkg.keySetData);
        writeDomainVerificationsLPr(serializer, pkg.verificationInfo);
        serializer.endTag(null, "package");
    }

    void writeSigningKeySetLPr(XmlSerializer serializer, PackageKeySetData data) throws IOException {
        serializer.startTag(null, "proper-signing-keyset");
        serializer.attribute(null, "identifier", Long.toString(data.getProperSigningKeySet()));
        serializer.endTag(null, "proper-signing-keyset");
    }

    void writeUpgradeKeySetsLPr(XmlSerializer serializer, PackageKeySetData data) throws IOException {
        long[] upgradeKeySets;
        if (data.isUsingUpgradeKeySets()) {
            for (long id : data.getUpgradeKeySets()) {
                serializer.startTag(null, "upgrade-keyset");
                serializer.attribute(null, "identifier", Long.toString(id));
                serializer.endTag(null, "upgrade-keyset");
            }
        }
    }

    void writeKeySetAliasesLPr(XmlSerializer serializer, PackageKeySetData data) throws IOException {
        for (Map.Entry<String, Long> e : data.getAliases().entrySet()) {
            serializer.startTag(null, "defined-keyset");
            serializer.attribute(null, "alias", e.getKey());
            serializer.attribute(null, "identifier", Long.toString(e.getValue().longValue()));
            serializer.endTag(null, "defined-keyset");
        }
    }

    void writePermissionLPr(XmlSerializer serializer, BasePermission bp) throws IOException {
        bp.writeLPr(serializer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addPackageToCleanLPw(PackageCleanItem pkg) {
        if (!this.mPackagesToBeCleaned.contains(pkg)) {
            this.mPackagesToBeCleaned.add(pkg);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean readLPw(List<UserInfo> users) {
        int type;
        FileInputStream str = null;
        if (this.mBackupSettingsFilename.exists()) {
            try {
                str = new FileInputStream(this.mBackupSettingsFilename);
                this.mReadMessages.append("Reading from backup settings file\n");
                PackageManagerService.reportSettingsProblem(4, "Need to read from backup settings file");
                if (this.mSettingsFilename.exists()) {
                    Slog.w("PackageManager", "Cleaning up settings file " + this.mSettingsFilename);
                    this.mSettingsFilename.delete();
                }
            } catch (IOException e) {
            }
        }
        this.mPendingPackages.clear();
        this.mPastSignatures.clear();
        this.mKeySetRefs.clear();
        this.mInstallerPackages.clear();
        if (str == null) {
            try {
                if (!this.mSettingsFilename.exists()) {
                    this.mReadMessages.append("No settings file found\n");
                    PackageManagerService.reportSettingsProblem(4, "No settings file; creating initial state");
                    findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL).forceCurrent();
                    findOrCreateVersion("primary_physical").forceCurrent();
                    return false;
                }
                str = new FileInputStream(this.mSettingsFilename);
            } catch (IOException e2) {
                this.mReadMessages.append("Error reading: " + e2.toString());
                PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e2);
                Slog.wtf("PackageManager", "Error reading package manager settings", e2);
            } catch (XmlPullParserException e3) {
                this.mReadMessages.append("Error reading: " + e3.toString());
                PackageManagerService.reportSettingsProblem(6, "Error reading settings: " + e3);
                Slog.wtf("PackageManager", "Error reading package manager settings", e3);
            }
        }
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(str, StandardCharsets.UTF_8.name());
        while (true) {
            type = parser.next();
            if (type == 2 || type == 1) {
                break;
            }
        }
        if (type != 2) {
            this.mReadMessages.append("No start tag found in settings file\n");
            PackageManagerService.reportSettingsProblem(5, "No start tag found in package manager settings");
            Slog.wtf("PackageManager", "No start tag found in package manager settings");
            return false;
        }
        int outerDepth = parser.getDepth();
        while (true) {
            int outerDepth2 = outerDepth;
            int type2 = parser.next();
            if (type2 == 1 || (type2 == 3 && parser.getDepth() <= outerDepth2)) {
                break;
            }
            if (type2 != 3 && type2 != 4) {
                String tagName = parser.getName();
                if (tagName.equals("package")) {
                    readPackageLPw(parser);
                } else if (tagName.equals("permissions")) {
                    this.mPermissions.readPermissions(parser);
                } else if (tagName.equals("permission-trees")) {
                    this.mPermissions.readPermissionTrees(parser);
                } else if (tagName.equals(TAG_SHARED_USER)) {
                    readSharedUserLPw(parser);
                } else if (!tagName.equals("preferred-packages")) {
                    if (tagName.equals("preferred-activities")) {
                        readPreferredActivitiesLPw(parser, 0);
                    } else if (tagName.equals(TAG_PERSISTENT_PREFERRED_ACTIVITIES)) {
                        readPersistentPreferredActivitiesLPw(parser, 0);
                    } else if (tagName.equals(TAG_CROSS_PROFILE_INTENT_FILTERS)) {
                        readCrossProfileIntentFiltersLPw(parser, 0);
                    } else if (tagName.equals(TAG_DEFAULT_BROWSER)) {
                        readDefaultAppsLPw(parser, 0);
                    } else if (tagName.equals("updated-package")) {
                        readDisabledSysPackageLPw(parser);
                    } else if (tagName.equals("cleaning-package")) {
                        String name = parser.getAttributeValue(null, ATTR_NAME);
                        String userStr = parser.getAttributeValue(null, ATTR_USER);
                        String codeStr = parser.getAttributeValue(null, ATTR_CODE);
                        if (name != null) {
                            int userId = 0;
                            boolean andCode = true;
                            if (userStr != null) {
                                try {
                                    userId = Integer.parseInt(userStr);
                                } catch (NumberFormatException e4) {
                                }
                            }
                            if (codeStr != null) {
                                andCode = Boolean.parseBoolean(codeStr);
                            }
                            addPackageToCleanLPw(new PackageCleanItem(userId, name, andCode));
                        }
                    } else if (tagName.equals("renamed-package")) {
                        String nname = parser.getAttributeValue(null, "new");
                        String oname = parser.getAttributeValue(null, "old");
                        if (nname != null && oname != null) {
                            this.mRenamedPackages.put(nname, oname);
                        }
                    } else if (tagName.equals("restored-ivi")) {
                        readRestoredIntentFilterVerifications(parser);
                    } else if (tagName.equals("last-platform-version")) {
                        VersionInfo internal = findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL);
                        VersionInfo external = findOrCreateVersion("primary_physical");
                        internal.sdkVersion = XmlUtils.readIntAttribute(parser, "internal", 0);
                        external.sdkVersion = XmlUtils.readIntAttribute(parser, "external", 0);
                        String readStringAttribute = XmlUtils.readStringAttribute(parser, ATTR_FINGERPRINT);
                        external.fingerprint = readStringAttribute;
                        internal.fingerprint = readStringAttribute;
                    } else if (tagName.equals("database-version")) {
                        VersionInfo internal2 = findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL);
                        VersionInfo external2 = findOrCreateVersion("primary_physical");
                        internal2.databaseVersion = XmlUtils.readIntAttribute(parser, "internal", 0);
                        external2.databaseVersion = XmlUtils.readIntAttribute(parser, "external", 0);
                    } else if (tagName.equals("verifier")) {
                        String deviceIdentity = parser.getAttributeValue(null, "device");
                        try {
                            this.mVerifierDeviceIdentity = VerifierDeviceIdentity.parse(deviceIdentity);
                        } catch (IllegalArgumentException e5) {
                            Slog.w("PackageManager", "Discard invalid verifier device id: " + e5.getMessage());
                        }
                    } else if (TAG_READ_EXTERNAL_STORAGE.equals(tagName)) {
                        String enforcement = parser.getAttributeValue(null, ATTR_ENFORCEMENT);
                        this.mReadExternalStorageEnforced = "1".equals(enforcement) ? Boolean.TRUE : Boolean.FALSE;
                    } else if (tagName.equals("keyset-settings")) {
                        this.mKeySetManagerService.readKeySetsLPw(parser, this.mKeySetRefs);
                    } else if ("version".equals(tagName)) {
                        String volumeUuid = XmlUtils.readStringAttribute(parser, ATTR_VOLUME_UUID);
                        VersionInfo ver = findOrCreateVersion(volumeUuid);
                        ver.sdkVersion = XmlUtils.readIntAttribute(parser, ATTR_SDK_VERSION);
                        ver.databaseVersion = XmlUtils.readIntAttribute(parser, ATTR_DATABASE_VERSION);
                        ver.fingerprint = XmlUtils.readStringAttribute(parser, ATTR_FINGERPRINT);
                    } else {
                        Slog.w("PackageManager", "Unknown element under <packages>: " + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }
            }
            outerDepth = outerDepth2;
        }
        str.close();
        int N = this.mPendingPackages.size();
        for (int i = 0; i < N; i++) {
            PackageSetting p = this.mPendingPackages.get(i);
            int sharedUserId = p.getSharedUserId();
            Object idObj = getUserIdLPr(sharedUserId);
            if (idObj instanceof SharedUserSetting) {
                SharedUserSetting sharedUser = (SharedUserSetting) idObj;
                p.sharedUser = sharedUser;
                p.appId = sharedUser.userId;
                addPackageSettingLPw(p, sharedUser);
            } else if (idObj != null) {
                String msg = "Bad package setting: package " + p.name + " has shared uid " + sharedUserId + " that is not a shared uid\n";
                this.mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(6, msg);
            } else {
                String msg2 = "Bad package setting: package " + p.name + " has shared uid " + sharedUserId + " that is not defined\n";
                this.mReadMessages.append(msg2);
                PackageManagerService.reportSettingsProblem(6, msg2);
            }
        }
        this.mPendingPackages.clear();
        if (this.mBackupStoppedPackagesFilename.exists() || this.mStoppedPackagesFilename.exists()) {
            readStoppedLPw();
            this.mBackupStoppedPackagesFilename.delete();
            this.mStoppedPackagesFilename.delete();
            writePackageRestrictionsLPr(0);
        } else {
            for (UserInfo user : users) {
                readPackageRestrictionsLPr(user.id);
            }
        }
        for (UserInfo user2 : users) {
            this.mRuntimePermissionsPersistence.readStateForUserSyncLPr(user2.id);
        }
        for (PackageSetting disabledPs : this.mDisabledSysPackages.values()) {
            Object id = getUserIdLPr(disabledPs.appId);
            if (id != null && (id instanceof SharedUserSetting)) {
                disabledPs.sharedUser = (SharedUserSetting) id;
            }
        }
        this.mReadMessages.append("Read completed successfully: " + this.mPackages.size() + " packages, " + this.mSharedUsers.size() + " shared uids\n");
        writeKernelMappingLPr();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyDefaultPreferredAppsLPw(PackageManagerService service, int userId) {
        int i;
        int type;
        Iterator<PackageSetting> it = this.mPackages.values().iterator();
        while (true) {
            i = 0;
            if (!it.hasNext()) {
                break;
            }
            PackageSetting ps = it.next();
            if ((1 & ps.pkgFlags) != 0 && ps.pkg != null && ps.pkg.preferredActivityFilters != null) {
                ArrayList<PackageParser.ActivityIntentInfo> intents = ps.pkg.preferredActivityFilters;
                while (i < intents.size()) {
                    PackageParser.ActivityIntentInfo aii = intents.get(i);
                    applyDefaultPreferredActivityLPw(service, aii, new ComponentName(ps.name, aii.activity.className), userId);
                    i++;
                }
            }
        }
        File preferredDir = new File(Environment.getRootDirectory(), "etc/preferred-apps");
        if (preferredDir.exists() && preferredDir.isDirectory()) {
            if (!preferredDir.canRead()) {
                Slog.w(TAG, "Directory " + preferredDir + " cannot be read");
                return;
            }
            File[] listFiles = preferredDir.listFiles();
            int length = listFiles.length;
            while (i < length) {
                File f = listFiles[i];
                if (!f.getPath().endsWith(".xml")) {
                    Slog.i(TAG, "Non-xml file " + f + " in " + preferredDir + " directory, ignoring");
                } else if (f.canRead()) {
                    if (PackageManagerService.DEBUG_PREFERRED) {
                        Log.d(TAG, "Reading default preferred " + f);
                    }
                    InputStream str = null;
                    try {
                        try {
                            try {
                                str = new BufferedInputStream(new FileInputStream(f));
                                XmlPullParser parser = Xml.newPullParser();
                                parser.setInput(str, null);
                                while (true) {
                                    type = parser.next();
                                    if (type == 2 || type == 1) {
                                        break;
                                    }
                                }
                                if (type != 2) {
                                    Slog.w(TAG, "Preferred apps file " + f + " does not have start tag");
                                    try {
                                        str.close();
                                    } catch (IOException e) {
                                    }
                                } else if ("preferred-activities".equals(parser.getName())) {
                                    readDefaultPreferredActivitiesLPw(service, parser, userId);
                                    str.close();
                                } else {
                                    Slog.w(TAG, "Preferred apps file " + f + " does not start with 'preferred-activities'");
                                    str.close();
                                }
                            } catch (IOException e2) {
                                Slog.w(TAG, "Error reading apps file " + f, e2);
                                if (str != null) {
                                    str.close();
                                }
                            }
                        } catch (XmlPullParserException e3) {
                            Slog.w(TAG, "Error reading apps file " + f, e3);
                            if (str != null) {
                                str.close();
                            }
                        }
                    } catch (Throwable th) {
                        if (str != null) {
                            try {
                                str.close();
                            } catch (IOException e4) {
                            }
                        }
                        throw th;
                    }
                } else {
                    Slog.w(TAG, "Preferred apps file " + f + " cannot be read");
                }
                i++;
            }
        }
    }

    private void applyDefaultPreferredActivityLPw(PackageManagerService service, IntentFilter tmpPa, ComponentName cn, int userId) {
        int ischeme;
        Uri.Builder builder;
        if (PackageManagerService.DEBUG_PREFERRED) {
            Log.d(TAG, "Processing preferred:");
            tmpPa.dump(new LogPrinter(3, TAG), "  ");
        }
        Intent intent = new Intent();
        int i = 0;
        intent.setAction(tmpPa.getAction(0));
        int flags = 786432;
        for (int flags2 = 0; flags2 < tmpPa.countCategories(); flags2++) {
            String cat = tmpPa.getCategory(flags2);
            if (cat.equals("android.intent.category.DEFAULT")) {
                flags |= 65536;
            } else {
                intent.addCategory(cat);
            }
        }
        boolean doNonData = true;
        int ischeme2 = 0;
        boolean hasSchemes = false;
        while (ischeme2 < tmpPa.countDataSchemes()) {
            String scheme = tmpPa.getDataScheme(ischeme2);
            if (scheme != null && !scheme.isEmpty()) {
                hasSchemes = true;
            }
            boolean doScheme = true;
            int issp = i;
            while (true) {
                int issp2 = issp;
                int issp3 = tmpPa.countDataSchemeSpecificParts();
                if (issp2 >= issp3) {
                    break;
                }
                Uri.Builder builder2 = new Uri.Builder();
                builder2.scheme(scheme);
                PatternMatcher ssp = tmpPa.getDataSchemeSpecificPart(issp2);
                builder2.opaquePart(ssp.getPath());
                Intent finalIntent = new Intent(intent);
                finalIntent.setData(builder2.build());
                applyDefaultPreferredActivityLPw(service, finalIntent, flags, cn, scheme, ssp, null, null, userId);
                doScheme = false;
                issp = issp2 + 1;
                scheme = scheme;
            }
            String scheme2 = scheme;
            int iauth = 0;
            while (true) {
                int iauth2 = iauth;
                int iauth3 = tmpPa.countDataAuthorities();
                if (iauth2 >= iauth3) {
                    break;
                }
                IntentFilter.AuthorityEntry auth = tmpPa.getDataAuthority(iauth2);
                boolean doScheme2 = doScheme;
                boolean doAuth = true;
                int ipath = 0;
                while (true) {
                    int ipath2 = ipath;
                    int ipath3 = tmpPa.countDataPaths();
                    if (ipath2 >= ipath3) {
                        break;
                    }
                    Uri.Builder builder3 = new Uri.Builder();
                    builder3.scheme(scheme2);
                    if (auth.getHost() != null) {
                        builder3.authority(auth.getHost());
                    }
                    PatternMatcher path = tmpPa.getDataPath(ipath2);
                    builder3.path(path.getPath());
                    Intent finalIntent2 = new Intent(intent);
                    finalIntent2.setData(builder3.build());
                    applyDefaultPreferredActivityLPw(service, finalIntent2, flags, cn, scheme2, null, auth, path, userId);
                    doScheme2 = false;
                    doAuth = false;
                    ipath = ipath2 + 1;
                    auth = auth;
                    iauth2 = iauth2;
                }
                IntentFilter.AuthorityEntry auth2 = auth;
                int iauth4 = iauth2;
                if (doAuth) {
                    Uri.Builder builder4 = new Uri.Builder();
                    builder4.scheme(scheme2);
                    if (auth2.getHost() != null) {
                        builder4.authority(auth2.getHost());
                    }
                    Intent finalIntent3 = new Intent(intent);
                    finalIntent3.setData(builder4.build());
                    applyDefaultPreferredActivityLPw(service, finalIntent3, flags, cn, scheme2, null, auth2, null, userId);
                    doScheme = false;
                } else {
                    doScheme = doScheme2;
                }
                iauth = iauth4 + 1;
            }
            if (doScheme) {
                Uri.Builder builder5 = new Uri.Builder();
                builder5.scheme(scheme2);
                Intent finalIntent4 = new Intent(intent);
                finalIntent4.setData(builder5.build());
                applyDefaultPreferredActivityLPw(service, finalIntent4, flags, cn, scheme2, null, null, null, userId);
            }
            doNonData = false;
            ischeme2++;
            i = 0;
        }
        int i2 = i;
        for (int idata = i2; idata < tmpPa.countDataTypes(); idata++) {
            String mimeType = tmpPa.getDataType(idata);
            if (hasSchemes) {
                Uri.Builder builder6 = new Uri.Builder();
                int ischeme3 = i2;
                while (true) {
                    int ischeme4 = ischeme3;
                    int ischeme5 = tmpPa.countDataSchemes();
                    if (ischeme4 < ischeme5) {
                        String scheme3 = tmpPa.getDataScheme(ischeme4);
                        if (scheme3 == null || scheme3.isEmpty()) {
                            ischeme = ischeme4;
                            builder = builder6;
                        } else {
                            Intent finalIntent5 = new Intent(intent);
                            builder6.scheme(scheme3);
                            finalIntent5.setDataAndType(builder6.build(), mimeType);
                            ischeme = ischeme4;
                            builder = builder6;
                            applyDefaultPreferredActivityLPw(service, finalIntent5, flags, cn, scheme3, null, null, null, userId);
                        }
                        ischeme3 = ischeme + 1;
                        builder6 = builder;
                    }
                }
            } else {
                Intent finalIntent6 = new Intent(intent);
                finalIntent6.setType(mimeType);
                applyDefaultPreferredActivityLPw(service, finalIntent6, flags, cn, null, null, null, null, userId);
            }
            doNonData = false;
        }
        if (doNonData) {
            applyDefaultPreferredActivityLPw(service, intent, flags, cn, null, null, null, null, userId);
        }
    }

    private void applyDefaultPreferredActivityLPw(PackageManagerService service, Intent intent, int flags, ComponentName cn, String scheme, PatternMatcher ssp, IntentFilter.AuthorityEntry auth, PatternMatcher path, int userId) {
        ComponentName componentName;
        int flags2 = service.updateFlagsForResolve(flags, userId, intent, Binder.getCallingUid(), false);
        List<ResolveInfo> ri = service.mActivities.queryIntent(intent, intent.getType(), flags2, 0);
        if (PackageManagerService.DEBUG_PREFERRED) {
            Log.d(TAG, "Queried " + intent + " results: " + ri);
        }
        if (ri != null && ri.size() > 1) {
            boolean haveAct = false;
            ComponentName haveNonSys = null;
            ComponentName[] set = new ComponentName[ri.size()];
            int systemMatch = 0;
            int systemMatch2 = 0;
            while (true) {
                if (systemMatch2 >= ri.size()) {
                    break;
                }
                ActivityInfo ai = ri.get(systemMatch2).activityInfo;
                set[systemMatch2] = new ComponentName(ai.packageName, ai.name);
                if ((ai.applicationInfo.flags & 1) == 0) {
                    if (ri.get(systemMatch2).match >= 0) {
                        if (PackageManagerService.DEBUG_PREFERRED) {
                            Log.d(TAG, "Result " + ai.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + ai.name + ": non-system!");
                        }
                        haveNonSys = set[systemMatch2];
                    }
                } else if (cn.getPackageName().equals(ai.packageName) && cn.getClassName().equals(ai.name)) {
                    if (PackageManagerService.DEBUG_PREFERRED) {
                        Log.d(TAG, "Result " + ai.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + ai.name + ": default!");
                    }
                    haveAct = true;
                    systemMatch = ri.get(systemMatch2).match;
                } else {
                    boolean haveAct2 = PackageManagerService.DEBUG_PREFERRED;
                    if (haveAct2) {
                        Log.d(TAG, "Result " + ai.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + ai.name + ": skipped");
                    }
                }
                systemMatch2++;
            }
            if (haveNonSys != null && 0 < systemMatch) {
                haveNonSys = null;
            }
            if (haveAct && haveNonSys == null) {
                IntentFilter filter = new IntentFilter();
                if (intent.getAction() != null) {
                    filter.addAction(intent.getAction());
                }
                if (intent.getCategories() != null) {
                    for (String cat : intent.getCategories()) {
                        filter.addCategory(cat);
                    }
                }
                if ((65536 & flags2) != 0) {
                    filter.addCategory("android.intent.category.DEFAULT");
                }
                if (scheme != null) {
                    filter.addDataScheme(scheme);
                }
                if (ssp != null) {
                    filter.addDataSchemeSpecificPart(ssp.getPath(), ssp.getType());
                }
                if (auth != null) {
                    filter.addDataAuthority(auth);
                }
                if (path != null) {
                    filter.addDataPath(path);
                }
                if (intent.getType() != null) {
                    try {
                        filter.addDataType(intent.getType());
                        componentName = cn;
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Malformed mimetype ");
                        sb.append(intent.getType());
                        sb.append(" for ");
                        componentName = cn;
                        sb.append(componentName);
                        Slog.w(TAG, sb.toString());
                    }
                } else {
                    componentName = cn;
                }
                PreferredActivity pa = new PreferredActivity(filter, systemMatch, set, componentName, true);
                editPreferredActivitiesLPw(userId).addFilter(pa);
            } else if (haveNonSys == null) {
                StringBuilder sb2 = new StringBuilder();
                sb2.append("No component ");
                sb2.append(cn.flattenToShortString());
                sb2.append(" found setting preferred ");
                sb2.append(intent);
                sb2.append("; possible matches are ");
                int i = 0;
                while (true) {
                    int i2 = i;
                    List<ResolveInfo> ri2 = ri;
                    if (i2 < set.length) {
                        if (i2 > 0) {
                            sb2.append(", ");
                        }
                        sb2.append(set[i2].flattenToShortString());
                        i = i2 + 1;
                        ri = ri2;
                    } else {
                        Slog.w(TAG, sb2.toString());
                        return;
                    }
                }
            } else {
                Slog.i(TAG, "Not setting preferred " + intent + "; found third party match " + haveNonSys.flattenToShortString());
            }
        } else {
            Slog.w(TAG, "No potential matches found for " + intent + " while setting preferred " + cn.flattenToShortString());
        }
    }

    private void readDefaultPreferredActivitiesLPw(PackageManagerService service, XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_ITEM)) {
                            PreferredActivity tmpPa = new PreferredActivity(parser);
                            if (tmpPa.mPref.getParseError() == null) {
                                applyDefaultPreferredActivityLPw(service, tmpPa, tmpPa.mPref.mComponent, userId);
                            } else {
                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <preferred-activity> " + tmpPa.mPref.getParseError() + " at " + parser.getPositionDescription());
                            }
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "Unknown element under <preferred-activities>: " + parser.getName());
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

    private void readDisabledSysPackageLPw(XmlPullParser parser) throws XmlPullParserException, IOException {
        int pkgPrivateFlags;
        String name = parser.getAttributeValue(null, ATTR_NAME);
        String realName = parser.getAttributeValue(null, "realName");
        String codePathStr = parser.getAttributeValue(null, "codePath");
        String resourcePathStr = parser.getAttributeValue(null, "resourcePath");
        String legacyCpuAbiStr = parser.getAttributeValue(null, "requiredCpuAbi");
        String legacyNativeLibraryPathStr = parser.getAttributeValue(null, "nativeLibraryPath");
        String parentPackageName = parser.getAttributeValue(null, "parentPackageName");
        String primaryCpuAbiStr = parser.getAttributeValue(null, "primaryCpuAbi");
        String secondaryCpuAbiStr = parser.getAttributeValue(null, "secondaryCpuAbi");
        String cpuAbiOverrideStr = parser.getAttributeValue(null, "cpuAbiOverride");
        if (primaryCpuAbiStr == null && legacyCpuAbiStr != null) {
            primaryCpuAbiStr = legacyCpuAbiStr;
        }
        String primaryCpuAbiStr2 = primaryCpuAbiStr;
        if (resourcePathStr == null) {
            resourcePathStr = codePathStr;
        }
        String resourcePathStr2 = resourcePathStr;
        String version = parser.getAttributeValue(null, "version");
        long versionCode = 0;
        if (version != null) {
            try {
                versionCode = Long.parseLong(version);
            } catch (NumberFormatException e) {
            }
        }
        long versionCode2 = versionCode;
        int pkgFlags = 0 | 1;
        if (PackageManagerService.locationIsPrivileged(codePathStr)) {
            int pkgPrivateFlags2 = 0 | 8;
            pkgPrivateFlags = pkgPrivateFlags2;
        } else {
            pkgPrivateFlags = 0;
        }
        PackageSetting ps = new PackageSetting(name, realName, new File(codePathStr), new File(resourcePathStr2), legacyNativeLibraryPathStr, primaryCpuAbiStr2, secondaryCpuAbiStr, cpuAbiOverrideStr, versionCode2, pkgFlags, pkgPrivateFlags, parentPackageName, null, 0, null, null);
        String timeStampStr = parser.getAttributeValue(null, "ft");
        if (timeStampStr != null) {
            try {
                long timeStamp = Long.parseLong(timeStampStr, 16);
                ps.setTimeStamp(timeStamp);
            } catch (NumberFormatException e2) {
            }
        } else {
            String timeStampStr2 = parser.getAttributeValue(null, "ts");
            if (timeStampStr2 != null) {
                try {
                    long timeStamp2 = Long.parseLong(timeStampStr2);
                    ps.setTimeStamp(timeStamp2);
                } catch (NumberFormatException e3) {
                }
            }
        }
        String timeStampStr3 = parser.getAttributeValue(null, "it");
        if (timeStampStr3 != null) {
            try {
                ps.firstInstallTime = Long.parseLong(timeStampStr3, 16);
            } catch (NumberFormatException e4) {
            }
        }
        String timeStampStr4 = parser.getAttributeValue(null, "ut");
        if (timeStampStr4 != null) {
            try {
                ps.lastUpdateTime = Long.parseLong(timeStampStr4, 16);
            } catch (NumberFormatException e5) {
            }
        }
        String idStr = parser.getAttributeValue(null, "userId");
        ps.appId = idStr != null ? Integer.parseInt(idStr) : 0;
        if (ps.appId <= 0) {
            String sharedIdStr = parser.getAttributeValue(null, "sharedUserId");
            ps.appId = sharedIdStr != null ? Integer.parseInt(sharedIdStr) : 0;
        }
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth)) {
                break;
            } else if (type != 3 && type != 4) {
                if (parser.getName().equals(TAG_PERMISSIONS)) {
                    readInstallPermissionsLPr(parser, ps.getPermissionsState());
                } else if (parser.getName().equals(TAG_CHILD_PACKAGE)) {
                    String childPackageName = parser.getAttributeValue(null, ATTR_NAME);
                    if (ps.childPackageNames == null) {
                        ps.childPackageNames = new ArrayList();
                    }
                    ps.childPackageNames.add(childPackageName);
                } else if (parser.getName().equals(TAG_USES_STATIC_LIB)) {
                    readUsesStaticLibLPw(parser, ps);
                } else {
                    PackageManagerService.reportSettingsProblem(5, "Unknown element under <updated-package>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        this.mDisabledSysPackages.put(name, ps);
    }

    /* JADX WARN: Can't wrap try/catch for region: R(53:1|(4:2|3|4|(13:5|6|7|8|9|10|11|12|13|14|15|16|(13:17|18|19|20|21|22|23|24|25|26|27|28|29)))|(9:31|32|33|34|35|(1:497)(1:38)|39|40|41)|(51:488|489|490|44|45|46|47|48|49|50|51|52|53|54|55|(3:57|58|59)|62|63|64|65|(6:456|457|458|459|460|(3:462|463|464))(4:67|68|(13:437|438|439|440|(1:442)|443|(1:445)|446|(1:448)|449|(1:451)|452|453)(3:70|71|(3:73|(1:75)(1:434)|76)(1:435))|77)|78|79|80|81|(3:426|427|428)(3:83|84|(3:420|421|422))|86|87|88|89|(4:91|92|93|94)|97|98|99|100|(4:102|103|104|105)|108|109|110|(5:403|404|405|406|407)(1:112)|(1:114)(1:402)|(1:116)(1:401)|117|(1:119)(1:397)|(3:390|391|392)|121|122|(7:376|377|378|379|380|381|382)(2:124|(4:126|127|128|129)(4:232|(16:310|311|312|313|314|315|316|317|318|319|320|322|323|(5:353|354|355|356|357)(1:325)|(2:327|328)(9:335|336|337|338|339|340|341|342|343)|329)(2:234|(2:236|(18:253|254|255|256|257|258|259|260|262|263|264|265|266|267|268|269|(4:271|272|273|274)(1:280)|275)(7:238|239|240|241|242|243|244))(6:298|299|300|301|302|303))|131|(5:133|(6:208|209|210|211|212|213)(1:135)|(1:137)|138|(2:139|(2:141|(2:(2:150|(2:152|153)(8:155|(1:157)(2:164|(1:166)(2:167|(6:169|159|160|161|162|163)(5:170|(1:172)(3:173|(3:175|(1:177)(1:179)|178)(2:180|(2:182|(1:184)(2:185|(3:187|(1:189)(1:191)|190)(2:192|(1:194)(2:195|(5:197|(1:199)|200|162|163)(2:201|202))))))|160)|161|162|163)))|158|159|160|161|162|163))(2:203|204)|154)(3:145|146|147))(3:205|206|207)))(2:229|230)))|130|131|(0)(0))|43|44|45|46|47|48|49|50|51|52|53|54|55|(0)|62|63|64|65|(0)(0)|78|79|80|81|(0)(0)|86|87|88|89|(0)|97|98|99|100|(0)|108|109|110|(0)(0)|(0)(0)|(0)(0)|117|(0)(0)|(0)|121|122|(0)(0)|130|131|(0)(0)) */
    /* JADX WARN: Can't wrap try/catch for region: R(56:1|2|3|4|(13:5|6|7|8|9|10|11|12|13|14|15|16|(13:17|18|19|20|21|22|23|24|25|26|27|28|29))|(9:31|32|33|34|35|(1:497)(1:38)|39|40|41)|(51:488|489|490|44|45|46|47|48|49|50|51|52|53|54|55|(3:57|58|59)|62|63|64|65|(6:456|457|458|459|460|(3:462|463|464))(4:67|68|(13:437|438|439|440|(1:442)|443|(1:445)|446|(1:448)|449|(1:451)|452|453)(3:70|71|(3:73|(1:75)(1:434)|76)(1:435))|77)|78|79|80|81|(3:426|427|428)(3:83|84|(3:420|421|422))|86|87|88|89|(4:91|92|93|94)|97|98|99|100|(4:102|103|104|105)|108|109|110|(5:403|404|405|406|407)(1:112)|(1:114)(1:402)|(1:116)(1:401)|117|(1:119)(1:397)|(3:390|391|392)|121|122|(7:376|377|378|379|380|381|382)(2:124|(4:126|127|128|129)(4:232|(16:310|311|312|313|314|315|316|317|318|319|320|322|323|(5:353|354|355|356|357)(1:325)|(2:327|328)(9:335|336|337|338|339|340|341|342|343)|329)(2:234|(2:236|(18:253|254|255|256|257|258|259|260|262|263|264|265|266|267|268|269|(4:271|272|273|274)(1:280)|275)(7:238|239|240|241|242|243|244))(6:298|299|300|301|302|303))|131|(5:133|(6:208|209|210|211|212|213)(1:135)|(1:137)|138|(2:139|(2:141|(2:(2:150|(2:152|153)(8:155|(1:157)(2:164|(1:166)(2:167|(6:169|159|160|161|162|163)(5:170|(1:172)(3:173|(3:175|(1:177)(1:179)|178)(2:180|(2:182|(1:184)(2:185|(3:187|(1:189)(1:191)|190)(2:192|(1:194)(2:195|(5:197|(1:199)|200|162|163)(2:201|202))))))|160)|161|162|163)))|158|159|160|161|162|163))(2:203|204)|154)(3:145|146|147))(3:205|206|207)))(2:229|230)))|130|131|(0)(0))|43|44|45|46|47|48|49|50|51|52|53|54|55|(0)|62|63|64|65|(0)(0)|78|79|80|81|(0)(0)|86|87|88|89|(0)|97|98|99|100|(0)|108|109|110|(0)(0)|(0)(0)|(0)(0)|117|(0)(0)|(0)|121|122|(0)(0)|130|131|(0)(0)) */
    /* JADX WARN: Can't wrap try/catch for region: R(68:1|2|3|4|5|6|7|8|9|10|11|12|13|14|15|16|(13:17|18|19|20|21|22|23|24|25|26|27|28|29)|(9:31|32|33|34|35|(1:497)(1:38)|39|40|41)|(51:488|489|490|44|45|46|47|48|49|50|51|52|53|54|55|(3:57|58|59)|62|63|64|65|(6:456|457|458|459|460|(3:462|463|464))(4:67|68|(13:437|438|439|440|(1:442)|443|(1:445)|446|(1:448)|449|(1:451)|452|453)(3:70|71|(3:73|(1:75)(1:434)|76)(1:435))|77)|78|79|80|81|(3:426|427|428)(3:83|84|(3:420|421|422))|86|87|88|89|(4:91|92|93|94)|97|98|99|100|(4:102|103|104|105)|108|109|110|(5:403|404|405|406|407)(1:112)|(1:114)(1:402)|(1:116)(1:401)|117|(1:119)(1:397)|(3:390|391|392)|121|122|(7:376|377|378|379|380|381|382)(2:124|(4:126|127|128|129)(4:232|(16:310|311|312|313|314|315|316|317|318|319|320|322|323|(5:353|354|355|356|357)(1:325)|(2:327|328)(9:335|336|337|338|339|340|341|342|343)|329)(2:234|(2:236|(18:253|254|255|256|257|258|259|260|262|263|264|265|266|267|268|269|(4:271|272|273|274)(1:280)|275)(7:238|239|240|241|242|243|244))(6:298|299|300|301|302|303))|131|(5:133|(6:208|209|210|211|212|213)(1:135)|(1:137)|138|(2:139|(2:141|(2:(2:150|(2:152|153)(8:155|(1:157)(2:164|(1:166)(2:167|(6:169|159|160|161|162|163)(5:170|(1:172)(3:173|(3:175|(1:177)(1:179)|178)(2:180|(2:182|(1:184)(2:185|(3:187|(1:189)(1:191)|190)(2:192|(1:194)(2:195|(5:197|(1:199)|200|162|163)(2:201|202))))))|160)|161|162|163)))|158|159|160|161|162|163))(2:203|204)|154)(3:145|146|147))(3:205|206|207)))(2:229|230)))|130|131|(0)(0))|43|44|45|46|47|48|49|50|51|52|53|54|55|(0)|62|63|64|65|(0)(0)|78|79|80|81|(0)(0)|86|87|88|89|(0)|97|98|99|100|(0)|108|109|110|(0)(0)|(0)(0)|(0)(0)|117|(0)(0)|(0)|121|122|(0)(0)|130|131|(0)(0)) */
    /* JADX WARN: Can't wrap try/catch for region: R(88:1|2|3|4|5|6|7|8|9|10|11|12|13|14|15|16|17|18|19|20|21|22|23|24|25|26|27|28|29|31|32|33|34|35|(1:497)(1:38)|39|40|41|(51:488|489|490|44|45|46|47|48|49|50|51|52|53|54|55|(3:57|58|59)|62|63|64|65|(6:456|457|458|459|460|(3:462|463|464))(4:67|68|(13:437|438|439|440|(1:442)|443|(1:445)|446|(1:448)|449|(1:451)|452|453)(3:70|71|(3:73|(1:75)(1:434)|76)(1:435))|77)|78|79|80|81|(3:426|427|428)(3:83|84|(3:420|421|422))|86|87|88|89|(4:91|92|93|94)|97|98|99|100|(4:102|103|104|105)|108|109|110|(5:403|404|405|406|407)(1:112)|(1:114)(1:402)|(1:116)(1:401)|117|(1:119)(1:397)|(3:390|391|392)|121|122|(7:376|377|378|379|380|381|382)(2:124|(4:126|127|128|129)(4:232|(16:310|311|312|313|314|315|316|317|318|319|320|322|323|(5:353|354|355|356|357)(1:325)|(2:327|328)(9:335|336|337|338|339|340|341|342|343)|329)(2:234|(2:236|(18:253|254|255|256|257|258|259|260|262|263|264|265|266|267|268|269|(4:271|272|273|274)(1:280)|275)(7:238|239|240|241|242|243|244))(6:298|299|300|301|302|303))|131|(5:133|(6:208|209|210|211|212|213)(1:135)|(1:137)|138|(2:139|(2:141|(2:(2:150|(2:152|153)(8:155|(1:157)(2:164|(1:166)(2:167|(6:169|159|160|161|162|163)(5:170|(1:172)(3:173|(3:175|(1:177)(1:179)|178)(2:180|(2:182|(1:184)(2:185|(3:187|(1:189)(1:191)|190)(2:192|(1:194)(2:195|(5:197|(1:199)|200|162|163)(2:201|202))))))|160)|161|162|163)))|158|159|160|161|162|163))(2:203|204)|154)(3:145|146|147))(3:205|206|207)))(2:229|230)))|130|131|(0)(0))|43|44|45|46|47|48|49|50|51|52|53|54|55|(0)|62|63|64|65|(0)(0)|78|79|80|81|(0)(0)|86|87|88|89|(0)|97|98|99|100|(0)|108|109|110|(0)(0)|(0)(0)|(0)(0)|117|(0)(0)|(0)|121|122|(0)(0)|130|131|(0)(0)) */
    /* JADX WARN: Code restructure failed: missing block: B:162:0x0285, code lost:
        r13 = r14;
        r14 = r3;
        r3 = r67;
        r10 = r10;
     */
    /* JADX WARN: Code restructure failed: missing block: B:172:0x02c6, code lost:
        r13 = r14;
        r3 = r67;
        r10 = r10;
     */
    /* JADX WARN: Code restructure failed: missing block: B:277:0x068c, code lost:
        r13 = r14;
        r7 = r0;
        r14 = 5;
        r3 = r3;
        r4 = r4;
        r1 = r1;
        r10 = r10;
     */
    /* JADX WARN: Code restructure failed: missing block: B:279:0x06a8, code lost:
        r13 = r14;
        r7 = r0;
        r14 = 5;
        r3 = r3;
        r4 = r4;
        r1 = r1;
        r10 = r10;
     */
    /* JADX WARN: Code restructure failed: missing block: B:281:0x06c4, code lost:
        r13 = r14;
        r7 = r0;
        r14 = 5;
        r3 = r3;
        r4 = r4;
        r1 = r1;
        r10 = r10;
     */
    /* JADX WARN: Code restructure failed: missing block: B:283:0x06e0, code lost:
        r11 = r1;
        r1 = r4;
        r4 = r3;
        r3 = 5;
     */
    /* JADX WARN: Code restructure failed: missing block: B:285:0x06ec, code lost:
        r11 = r1;
        r1 = r4;
        r4 = r3;
        r3 = 5;
     */
    /* JADX WARN: Code restructure failed: missing block: B:286:0x06f8, code lost:
        r13 = r14;
        r7 = r0;
        r14 = r3;
        r3 = r4;
        r4 = r1;
        r1 = r11;
        r10 = r10;
     */
    /* JADX WARN: Code restructure failed: missing block: B:288:0x070a, code lost:
        r11 = r1;
        r1 = r4;
        r13 = r14;
     */
    /* JADX WARN: Code restructure failed: missing block: B:290:0x0714, code lost:
        r11 = r1;
        r1 = r4;
        r13 = r14;
     */
    /* JADX WARN: Code restructure failed: missing block: B:292:0x071e, code lost:
        r11 = r1;
        r1 = r4;
        r13 = r14;
     */
    /* JADX WARN: Code restructure failed: missing block: B:294:0x0728, code lost:
        r11 = r1;
        r1 = r4;
        r13 = r14;
        r33 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:295:0x0732, code lost:
        r57 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:296:0x0734, code lost:
        r58 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:297:0x0736, code lost:
        r7 = r0;
        r14 = 5;
        r4 = r1;
        r1 = r11;
        r10 = r10;
     */
    /* JADX WARN: Code restructure failed: missing block: B:61:0x0113, code lost:
        r13 = r14;
        r7 = r0;
        r14 = 5;
        r10 = r10;
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:101:0x01a3 A[Catch: NumberFormatException -> 0x06df, TRY_ENTER, TRY_LEAVE, TryCatch #53 {NumberFormatException -> 0x06df, blocks: (B:92:0x018c, B:101:0x01a3), top: B:527:0x018c }] */
    /* JADX WARN: Removed duplicated region for block: B:111:0x01c1  */
    /* JADX WARN: Removed duplicated region for block: B:119:0x01d8  */
    /* JADX WARN: Removed duplicated region for block: B:131:0x021e  */
    /* JADX WARN: Removed duplicated region for block: B:133:0x0222 A[Catch: NumberFormatException -> 0x0227, TryCatch #16 {NumberFormatException -> 0x0227, blocks: (B:128:0x01f1, B:133:0x0222, B:140:0x0235), top: B:456:0x01f1 }] */
    /* JADX WARN: Removed duplicated region for block: B:138:0x0232  */
    /* JADX WARN: Removed duplicated region for block: B:140:0x0235 A[Catch: NumberFormatException -> 0x0227, TRY_LEAVE, TryCatch #16 {NumberFormatException -> 0x0227, blocks: (B:128:0x01f1, B:133:0x0222, B:140:0x0235), top: B:456:0x01f1 }] */
    /* JADX WARN: Removed duplicated region for block: B:142:0x023a  */
    /* JADX WARN: Removed duplicated region for block: B:145:0x023e  */
    /* JADX WARN: Removed duplicated region for block: B:146:0x0240  */
    /* JADX WARN: Removed duplicated region for block: B:165:0x0296  */
    /* JADX WARN: Removed duplicated region for block: B:333:0x08fc  */
    /* JADX WARN: Removed duplicated region for block: B:356:0x09a9  */
    /* JADX WARN: Removed duplicated region for block: B:360:0x09ba  */
    /* JADX WARN: Removed duplicated region for block: B:422:0x0b9a  */
    /* JADX WARN: Removed duplicated region for block: B:454:0x0244 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:464:0x0197 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:490:0x00f2 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:494:0x01e8 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:499:0x0254 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:515:0x00df A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:543:0x0b93 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:62:0x0125 A[Catch: NumberFormatException -> 0x06eb, TRY_ENTER, TRY_LEAVE, TryCatch #59 {NumberFormatException -> 0x06eb, blocks: (B:47:0x00e8, B:62:0x0125, B:82:0x016e), top: B:539:0x00e8 }] */
    /* JADX WARN: Type inference failed for: r10v20 */
    /* JADX WARN: Type inference failed for: r10v44 */
    /* JADX WARN: Type inference failed for: r3v32 */
    /* JADX WARN: Type inference failed for: r3v38 */
    /* JADX WARN: Type inference failed for: r3v41 */
    /* JADX WARN: Type inference failed for: r3v90 */
    /* JADX WARN: Type inference failed for: r7v41 */
    /* JADX WARN: Type inference failed for: r7v42 */
    /* JADX WARN: Type inference failed for: r7v43 */
    /* JADX WARN: Type inference failed for: r7v44 */
    /* JADX WARN: Type inference failed for: r7v45 */
    /* JADX WARN: Type inference failed for: r7v46 */
    /* JADX WARN: Type inference failed for: r7v47 */
    /* JADX WARN: Type inference failed for: r7v48 */
    /* JADX WARN: Type inference failed for: r7v49 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void readPackageLPw(org.xmlpull.v1.XmlPullParser r98) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        /*
            Method dump skipped, instructions count: 2990
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.readPackageLPw(org.xmlpull.v1.XmlPullParser):void");
    }

    private void readDisabledComponentsLPw(PackageSettingBase packageSetting, XmlPullParser parser, int userId) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_ITEM)) {
                            String name = parser.getAttributeValue(null, ATTR_NAME);
                            if (name != null) {
                                packageSetting.addDisabledComponent(name.intern(), userId);
                            } else {
                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <disabled-components> has no name at " + parser.getPositionDescription());
                            }
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "Unknown element under <disabled-components>: " + parser.getName());
                        }
                        XmlUtils.skipCurrentTag(parser);
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void readEnabledComponentsLPw(PackageSettingBase packageSetting, XmlPullParser parser, int userId) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_ITEM)) {
                            String name = parser.getAttributeValue(null, ATTR_NAME);
                            if (name != null) {
                                packageSetting.addEnabledComponent(name.intern(), userId);
                            } else {
                                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <enabled-components> has no name at " + parser.getPositionDescription());
                            }
                        } else {
                            PackageManagerService.reportSettingsProblem(5, "Unknown element under <enabled-components>: " + parser.getName());
                        }
                        XmlUtils.skipCurrentTag(parser);
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void readSharedUserLPw(XmlPullParser parser) throws XmlPullParserException, IOException {
        int pkgFlags = 0;
        SharedUserSetting su = null;
        try {
            String name = parser.getAttributeValue(null, ATTR_NAME);
            String idStr = parser.getAttributeValue(null, "userId");
            int userId = idStr != null ? Integer.parseInt(idStr) : 0;
            if ("true".equals(parser.getAttributeValue(null, "system"))) {
                pkgFlags = 0 | 1;
            }
            if (name == null) {
                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: <shared-user> has no name at " + parser.getPositionDescription());
            } else if (userId != 0) {
                SharedUserSetting addSharedUserLPw = addSharedUserLPw(name.intern(), userId, pkgFlags, 0);
                su = addSharedUserLPw;
                if (addSharedUserLPw == null) {
                    PackageManagerService.reportSettingsProblem(6, "Occurred while parsing settings at " + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: shared-user " + name + " has bad userId " + idStr + " at " + parser.getPositionDescription());
            }
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: package " + ((String) null) + " has bad userId " + ((String) null) + " at " + parser.getPositionDescription());
        }
        if (su != null) {
            int outerDepth = parser.getDepth();
            while (true) {
                int type = parser.next();
                if (type != 1) {
                    if (type != 3 || parser.getDepth() > outerDepth) {
                        if (type != 3 && type != 4) {
                            String tagName = parser.getName();
                            if (tagName.equals("sigs")) {
                                su.signatures.readXml(parser, this.mPastSignatures);
                            } else if (tagName.equals(TAG_PERMISSIONS)) {
                                readInstallPermissionsLPr(parser, su.getPermissionsState());
                            } else {
                                PackageManagerService.reportSettingsProblem(5, "Unknown element under <shared-user>: " + parser.getName());
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
        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:25:0x0055 A[Catch: all -> 0x00d9, TryCatch #4 {all -> 0x00d9, blocks: (B:16:0x003d, B:18:0x0043, B:23:0x0050, B:25:0x0055, B:26:0x0058, B:28:0x0077, B:33:0x0083), top: B:76:0x003d }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void createNewUserLI(com.android.server.pm.PackageManagerService r22, com.android.server.pm.Installer r23, int r24, java.lang.String[] r25) {
        /*
            Method dump skipped, instructions count: 228
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.createNewUserLI(com.android.server.pm.PackageManagerService, com.android.server.pm.Installer, int, java.lang.String[]):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUserLPw(int userId) {
        Set<Map.Entry<String, PackageSetting>> entries = this.mPackages.entrySet();
        for (Map.Entry<String, PackageSetting> entry : entries) {
            entry.getValue().removeUser(userId);
        }
        this.mPreferredActivities.remove(userId);
        File file = getUserPackagesStateFile(userId);
        file.delete();
        File file2 = getUserPackagesStateBackupFile(userId);
        file2.delete();
        removeCrossProfileIntentFiltersLPw(userId);
        this.mRuntimePermissionsPersistence.onUserRemovedLPw(userId);
        writePackageListLPr();
        writeKernelRemoveUserLPr(userId);
    }

    void removeCrossProfileIntentFiltersLPw(int userId) {
        synchronized (this.mCrossProfileIntentResolvers) {
            if (this.mCrossProfileIntentResolvers.get(userId) != null) {
                this.mCrossProfileIntentResolvers.remove(userId);
                writePackageRestrictionsLPr(userId);
            }
            int count = this.mCrossProfileIntentResolvers.size();
            for (int i = 0; i < count; i++) {
                int sourceUserId = this.mCrossProfileIntentResolvers.keyAt(i);
                CrossProfileIntentResolver cpir = this.mCrossProfileIntentResolvers.get(sourceUserId);
                boolean needsWriting = false;
                ArraySet<CrossProfileIntentFilter> cpifs = new ArraySet<>(cpir.filterSet());
                Iterator<CrossProfileIntentFilter> it = cpifs.iterator();
                while (it.hasNext()) {
                    CrossProfileIntentFilter cpif = it.next();
                    if (cpif.getTargetUserId() == userId) {
                        needsWriting = true;
                        cpir.removeFilter(cpif);
                    }
                }
                if (needsWriting) {
                    writePackageRestrictionsLPr(sourceUserId);
                }
            }
        }
    }

    private void setFirstAvailableUid(int uid) {
        if (uid > mFirstAvailableUid) {
            mFirstAvailableUid = uid;
        }
    }

    private int newUserIdLPw(Object obj) {
        int N = this.mUserIds.size();
        if (N == 0) {
            this.mUserIds.add(null);
            N = 1;
        }
        if ((obj instanceof SharedUserSetting) && ((SharedUserSetting) obj).name.equals("android.uid.vmap")) {
            this.mUserIds.set(0, obj);
            return 10000;
        }
        for (int i = mFirstAvailableUid - 10000; i < N; i++) {
            if (this.mUserIds.get(i) == null) {
                this.mUserIds.set(i, obj);
                return 10000 + i;
            }
        }
        if (N > 9999) {
            return -1;
        }
        this.mUserIds.add(obj);
        return 10000 + N;
    }

    public VerifierDeviceIdentity getVerifierDeviceIdentityLPw() {
        if (this.mVerifierDeviceIdentity == null) {
            this.mVerifierDeviceIdentity = VerifierDeviceIdentity.generate();
            writeLPr();
        }
        return this.mVerifierDeviceIdentity;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasOtherDisabledSystemPkgWithChildLPr(String parentPackageName, String childPackageName) {
        int packageCount = this.mDisabledSysPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageSetting disabledPs = this.mDisabledSysPackages.valueAt(i);
            if (disabledPs.childPackageNames != null && !disabledPs.childPackageNames.isEmpty() && !disabledPs.name.equals(parentPackageName)) {
                int childCount = disabledPs.childPackageNames.size();
                for (int j = 0; j < childCount; j++) {
                    String currChildPackageName = disabledPs.childPackageNames.get(j);
                    if (currChildPackageName.equals(childPackageName)) {
                        return true;
                    }
                }
                continue;
            }
        }
        return false;
    }

    public PackageSetting getDisabledSystemPkgLPr(String name) {
        PackageSetting ps = this.mDisabledSysPackages.get(name);
        return ps;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isEnabledAndMatchLPr(ComponentInfo componentInfo, int flags, int userId) {
        PackageSetting ps = this.mPackages.get(componentInfo.packageName);
        if (ps == null) {
            return false;
        }
        PackageUserState userState = ps.readUserState(userId);
        return userState.isMatch(componentInfo, flags);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getInstallerPackageNameLPr(String packageName) {
        PackageSetting pkg = this.mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkg.installerPackageName;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isOrphaned(String packageName) {
        PackageSetting pkg = this.mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkg.isOrphaned;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getApplicationEnabledSettingLPr(String packageName, int userId) {
        PackageSetting pkg = this.mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkg.getEnabled(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getComponentEnabledSettingLPr(ComponentName componentName, int userId) {
        String packageName = componentName.getPackageName();
        PackageSetting pkg = this.mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown component: " + componentName);
        }
        String classNameStr = componentName.getClassName();
        return pkg.getCurrentEnabledStateLPr(classNameStr, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean wasPackageEverLaunchedLPr(String packageName, int userId) {
        PackageSetting pkgSetting = this.mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return !pkgSetting.getNotLaunched(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setPackageStoppedStateLPw(final PackageManagerService pm, final String packageName, boolean stopped, boolean allowedByPermission, int uid, int userId) {
        int appId = UserHandle.getAppId(uid);
        PackageSetting pkgSetting = this.mPackages.get(packageName);
        try {
            if (pkgSetting.getStopped(userId) != stopped && pkgSetting.getNotLaunched(userId)) {
                boolean bootCompleted = "1".equals(SystemProperties.get("sys.boot_completed", "0"));
                if (bootCompleted) {
                    pm.mHandler.post(new Runnable() { // from class: com.android.server.pm.Settings.1
                        @Override // java.lang.Runnable
                        public void run() {
                            Intent intent = new Intent();
                            intent.setAction("android.intent.action.XP_FIRST_LAUNCH");
                            intent.addFlags(32);
                            intent.addFlags(16777216);
                            Uri data = Uri.parse("package:" + packageName);
                            intent.setData(data);
                            pm.mContext.sendBroadcast(intent, "android.permission.RECV_XP_FIRST_LAUNCH");
                            Slog.d(Settings.TAG, "check setPackageStoppedStateLPw pm.mContext.sendBroadcast done packageName=" + packageName);
                        }
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        } else if (!allowedByPermission && appId != pkgSetting.appId) {
            throw new SecurityException("Permission Denial: attempt to change stopped state from pid=" + Binder.getCallingPid() + ", uid=" + uid + ", package uid=" + pkgSetting.appId);
        } else if (pkgSetting.getStopped(userId) != stopped) {
            pkgSetting.setStopped(stopped, userId);
            if (pkgSetting.getNotLaunched(userId)) {
                if (pkgSetting.installerPackageName != null) {
                    pm.notifyFirstLaunch(pkgSetting.name, pkgSetting.installerPackageName, userId);
                }
                pkgSetting.setNotLaunched(false, userId);
                return true;
            }
            return true;
        } else {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHarmfulAppWarningLPw(String packageName, CharSequence warning, int userId) {
        PackageSetting pkgSetting = this.mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        pkgSetting.setHarmfulAppWarning(userId, warning == null ? null : warning.toString());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getHarmfulAppWarningLPr(String packageName, int userId) {
        PackageSetting pkgSetting = this.mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkgSetting.getHarmfulAppWarning(userId);
    }

    private static List<UserInfo> getAllUsers(UserManagerService userManager) {
        long id = Binder.clearCallingIdentity();
        try {
            List<UserInfo> users = userManager.getUsers(false);
            Binder.restoreCallingIdentity(id);
            return users;
        } catch (NullPointerException e) {
            Binder.restoreCallingIdentity(id);
            return null;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(id);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<PackageSetting> getVolumePackagesLPr(String volumeUuid) {
        ArrayList<PackageSetting> res = new ArrayList<>();
        for (int i = 0; i < this.mPackages.size(); i++) {
            PackageSetting setting = this.mPackages.valueAt(i);
            if (Objects.equals(volumeUuid, setting.volumeUuid)) {
                res.add(setting);
            }
        }
        return res;
    }

    static void printFlags(PrintWriter pw, int val, Object[] spec) {
        pw.print("[ ");
        for (int i = 0; i < spec.length; i += 2) {
            int mask = ((Integer) spec[i]).intValue();
            if ((val & mask) != 0) {
                pw.print(spec[i + 1]);
                pw.print(" ");
            }
        }
        pw.print("]");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpVersionLPr(IndentingPrintWriter pw) {
        pw.increaseIndent();
        for (int i = 0; i < this.mVersion.size(); i++) {
            String volumeUuid = this.mVersion.keyAt(i);
            VersionInfo ver = this.mVersion.valueAt(i);
            if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, volumeUuid)) {
                pw.println("Internal:");
            } else if (Objects.equals("primary_physical", volumeUuid)) {
                pw.println("External:");
            } else {
                pw.println("UUID " + volumeUuid + ":");
            }
            pw.increaseIndent();
            pw.printPair(ATTR_SDK_VERSION, Integer.valueOf(ver.sdkVersion));
            pw.printPair(ATTR_DATABASE_VERSION, Integer.valueOf(ver.databaseVersion));
            pw.println();
            pw.printPair(ATTR_FINGERPRINT, ver.fingerprint);
            pw.println();
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    void dumpPackageLPr(PrintWriter pw, String prefix, String checkinTag, ArraySet<String> permissionNames, PackageSetting ps, SimpleDateFormat sdf, Date date, List<UserInfo> users, boolean dumpAll) {
        UserInfo user;
        if (checkinTag != null) {
            pw.print(checkinTag);
            pw.print(",");
            pw.print(ps.realName != null ? ps.realName : ps.name);
            pw.print(",");
            pw.print(ps.appId);
            pw.print(",");
            pw.print(ps.versionCode);
            pw.print(",");
            pw.print(ps.firstInstallTime);
            pw.print(",");
            pw.print(ps.lastUpdateTime);
            pw.print(",");
            pw.print(ps.installerPackageName != null ? ps.installerPackageName : "?");
            pw.println();
            if (ps.pkg != null) {
                pw.print(checkinTag);
                pw.print("-");
                pw.print("splt,");
                pw.print("base,");
                pw.println(ps.pkg.baseRevisionCode);
                if (ps.pkg.splitNames != null) {
                    int i = 0;
                    while (true) {
                        int i2 = i;
                        if (i2 >= ps.pkg.splitNames.length) {
                            break;
                        }
                        pw.print(checkinTag);
                        pw.print("-");
                        pw.print("splt,");
                        pw.print(ps.pkg.splitNames[i2]);
                        pw.print(",");
                        pw.println(ps.pkg.splitRevisionCodes[i2]);
                        i = i2 + 1;
                    }
                }
            }
            for (UserInfo user2 : users) {
                pw.print(checkinTag);
                pw.print("-");
                pw.print("usr");
                pw.print(",");
                pw.print(user2.id);
                pw.print(",");
                pw.print(ps.getInstalled(user2.id) ? "I" : "i");
                pw.print(ps.getHidden(user2.id) ? "B" : "b");
                pw.print(ps.getSuspended(user2.id) ? "SU" : "su");
                pw.print(ps.getStopped(user2.id) ? "S" : "s");
                pw.print(ps.getNotLaunched(user2.id) ? "l" : "L");
                pw.print(ps.getInstantApp(user2.id) ? "IA" : "ia");
                pw.print(ps.getVirtulalPreload(user2.id) ? "VPI" : "vpi");
                pw.print(ps.getHarmfulAppWarning(user2.id) != null ? "HA" : "ha");
                pw.print(",");
                pw.print(ps.getEnabled(user2.id));
                String lastDisabledAppCaller = ps.getLastDisabledAppCaller(user2.id);
                pw.print(",");
                pw.print(lastDisabledAppCaller != null ? lastDisabledAppCaller : "?");
                pw.print(",");
                pw.println();
            }
            return;
        }
        pw.print(prefix);
        pw.print("Package [");
        pw.print(ps.realName != null ? ps.realName : ps.name);
        pw.print("] (");
        pw.print(Integer.toHexString(System.identityHashCode(ps)));
        pw.println("):");
        if (ps.realName != null) {
            pw.print(prefix);
            pw.print("  compat name=");
            pw.println(ps.name);
        }
        pw.print(prefix);
        pw.print("  userId=");
        pw.println(ps.appId);
        if (ps.sharedUser != null) {
            pw.print(prefix);
            pw.print("  sharedUser=");
            pw.println(ps.sharedUser);
        }
        pw.print(prefix);
        pw.print("  pkg=");
        pw.println(ps.pkg);
        pw.print(prefix);
        pw.print("  codePath=");
        pw.println(ps.codePathString);
        if (permissionNames == null) {
            pw.print(prefix);
            pw.print("  resourcePath=");
            pw.println(ps.resourcePathString);
            pw.print(prefix);
            pw.print("  legacyNativeLibraryDir=");
            pw.println(ps.legacyNativeLibraryPathString);
            pw.print(prefix);
            pw.print("  primaryCpuAbi=");
            pw.println(ps.primaryCpuAbiString);
            pw.print(prefix);
            pw.print("  secondaryCpuAbi=");
            pw.println(ps.secondaryCpuAbiString);
        }
        pw.print(prefix);
        pw.print("  versionCode=");
        pw.print(ps.versionCode);
        if (ps.pkg != null) {
            pw.print(" minSdk=");
            pw.print(ps.pkg.applicationInfo.minSdkVersion);
            pw.print(" targetSdk=");
            pw.print(ps.pkg.applicationInfo.targetSdkVersion);
        }
        pw.println();
        if (ps.pkg != null) {
            if (ps.pkg.parentPackage != null) {
                PackageParser.Package parentPkg = ps.pkg.parentPackage;
                PackageSetting pps = this.mPackages.get(parentPkg.packageName);
                if (pps == null || !pps.codePathString.equals(parentPkg.codePath)) {
                    pps = this.mDisabledSysPackages.get(parentPkg.packageName);
                }
                if (pps != null) {
                    pw.print(prefix);
                    pw.print("  parentPackage=");
                    pw.println(pps.realName != null ? pps.realName : pps.name);
                }
            } else if (ps.pkg.childPackages != null) {
                pw.print(prefix);
                pw.print("  childPackages=[");
                int childCount = ps.pkg.childPackages.size();
                for (int i3 = 0; i3 < childCount; i3++) {
                    PackageParser.Package childPkg = (PackageParser.Package) ps.pkg.childPackages.get(i3);
                    PackageSetting cps = this.mPackages.get(childPkg.packageName);
                    if (cps == null || !cps.codePathString.equals(childPkg.codePath)) {
                        cps = this.mDisabledSysPackages.get(childPkg.packageName);
                    }
                    if (cps != null) {
                        if (i3 > 0) {
                            pw.print(", ");
                        }
                        pw.print(cps.realName != null ? cps.realName : cps.name);
                    }
                }
                pw.println("]");
            }
            pw.print(prefix);
            pw.print("  versionName=");
            pw.println(ps.pkg.mVersionName);
            pw.print(prefix);
            pw.print("  splits=");
            dumpSplitNames(pw, ps.pkg);
            pw.println();
            int apkSigningVersion = ps.pkg.mSigningDetails.signatureSchemeVersion;
            pw.print(prefix);
            pw.print("  apkSigningVersion=");
            pw.println(apkSigningVersion);
            pw.print(prefix);
            pw.print("  applicationInfo=");
            pw.println(ps.pkg.applicationInfo.toString());
            pw.print(prefix);
            pw.print("  flags=");
            printFlags(pw, ps.pkg.applicationInfo.flags, FLAG_DUMP_SPEC);
            pw.println();
            if (ps.pkg.applicationInfo.privateFlags != 0) {
                pw.print(prefix);
                pw.print("  privateFlags=");
                printFlags(pw, ps.pkg.applicationInfo.privateFlags, PRIVATE_FLAG_DUMP_SPEC);
                pw.println();
            }
            pw.print(prefix);
            pw.print("  dataDir=");
            pw.println(ps.pkg.applicationInfo.dataDir);
            pw.print(prefix);
            pw.print("  supportsScreens=[");
            boolean first = true;
            if ((ps.pkg.applicationInfo.flags & 512) != 0) {
                if (1 == 0) {
                    pw.print(", ");
                }
                first = false;
                pw.print("small");
            }
            if ((ps.pkg.applicationInfo.flags & 1024) != 0) {
                if (!first) {
                    pw.print(", ");
                }
                first = false;
                pw.print("medium");
            }
            if ((ps.pkg.applicationInfo.flags & 2048) != 0) {
                if (!first) {
                    pw.print(", ");
                }
                first = false;
                pw.print("large");
            }
            if ((ps.pkg.applicationInfo.flags & DumpState.DUMP_FROZEN) != 0) {
                if (!first) {
                    pw.print(", ");
                }
                first = false;
                pw.print("xlarge");
            }
            if ((ps.pkg.applicationInfo.flags & 4096) != 0) {
                if (!first) {
                    pw.print(", ");
                }
                first = false;
                pw.print("resizeable");
            }
            if ((ps.pkg.applicationInfo.flags & 8192) != 0) {
                if (!first) {
                    pw.print(", ");
                }
                pw.print("anyDensity");
            }
            pw.println("]");
            if (ps.pkg.libraryNames != null && ps.pkg.libraryNames.size() > 0) {
                pw.print(prefix);
                pw.println("  dynamic libraries:");
                for (int i4 = 0; i4 < ps.pkg.libraryNames.size(); i4++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println((String) ps.pkg.libraryNames.get(i4));
                }
            }
            if (ps.pkg.staticSharedLibName != null) {
                pw.print(prefix);
                pw.println("  static library:");
                pw.print(prefix);
                pw.print("    ");
                pw.print("name:");
                pw.print(ps.pkg.staticSharedLibName);
                pw.print(" version:");
                pw.println(ps.pkg.staticSharedLibVersion);
            }
            if (ps.pkg.usesLibraries != null && ps.pkg.usesLibraries.size() > 0) {
                pw.print(prefix);
                pw.println("  usesLibraries:");
                for (int i5 = 0; i5 < ps.pkg.usesLibraries.size(); i5++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println((String) ps.pkg.usesLibraries.get(i5));
                }
            }
            if (ps.pkg.usesStaticLibraries != null && ps.pkg.usesStaticLibraries.size() > 0) {
                pw.print(prefix);
                pw.println("  usesStaticLibraries:");
                for (int i6 = 0; i6 < ps.pkg.usesStaticLibraries.size(); i6++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.print((String) ps.pkg.usesStaticLibraries.get(i6));
                    pw.print(" version:");
                    pw.println(ps.pkg.usesStaticLibrariesVersions[i6]);
                }
            }
            if (ps.pkg.usesOptionalLibraries != null && ps.pkg.usesOptionalLibraries.size() > 0) {
                pw.print(prefix);
                pw.println("  usesOptionalLibraries:");
                for (int i7 = 0; i7 < ps.pkg.usesOptionalLibraries.size(); i7++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println((String) ps.pkg.usesOptionalLibraries.get(i7));
                }
            }
            if (ps.pkg.usesLibraryFiles != null && ps.pkg.usesLibraryFiles.length > 0) {
                pw.print(prefix);
                pw.println("  usesLibraryFiles:");
                for (int i8 = 0; i8 < ps.pkg.usesLibraryFiles.length; i8++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println(ps.pkg.usesLibraryFiles[i8]);
                }
            }
        }
        pw.print(prefix);
        pw.print("  timeStamp=");
        date.setTime(ps.timeStamp);
        pw.println(sdf.format(date));
        pw.print(prefix);
        pw.print("  firstInstallTime=");
        date.setTime(ps.firstInstallTime);
        pw.println(sdf.format(date));
        pw.print(prefix);
        pw.print("  lastUpdateTime=");
        date.setTime(ps.lastUpdateTime);
        pw.println(sdf.format(date));
        if (ps.installerPackageName != null) {
            pw.print(prefix);
            pw.print("  installerPackageName=");
            pw.println(ps.installerPackageName);
        }
        if (ps.volumeUuid != null) {
            pw.print(prefix);
            pw.print("  volumeUuid=");
            pw.println(ps.volumeUuid);
        }
        pw.print(prefix);
        pw.print("  signatures=");
        pw.println(ps.signatures);
        pw.print(prefix);
        pw.print("  installPermissionsFixed=");
        pw.print(ps.installPermissionsFixed);
        pw.println();
        pw.print(prefix);
        pw.print("  pkgFlags=");
        printFlags(pw, ps.pkgFlags, FLAG_DUMP_SPEC);
        pw.println();
        if (ps.pkg != null && ps.pkg.mOverlayTarget != null) {
            pw.print(prefix);
            pw.print("  overlayTarget=");
            pw.println(ps.pkg.mOverlayTarget);
            pw.print(prefix);
            pw.print("  overlayCategory=");
            pw.println(ps.pkg.mOverlayCategory);
        }
        if (ps.pkg != null && ps.pkg.permissions != null && ps.pkg.permissions.size() > 0) {
            ArrayList<PackageParser.Permission> perms = ps.pkg.permissions;
            pw.print(prefix);
            pw.println("  declared permissions:");
            for (int i9 = 0; i9 < perms.size(); i9++) {
                PackageParser.Permission perm = perms.get(i9);
                if (permissionNames == null || permissionNames.contains(perm.info.name)) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.print(perm.info.name);
                    pw.print(": prot=");
                    pw.print(PermissionInfo.protectionToString(perm.info.protectionLevel));
                    if ((perm.info.flags & 1) != 0) {
                        pw.print(", COSTS_MONEY");
                    }
                    if ((perm.info.flags & 2) != 0) {
                        pw.print(", HIDDEN");
                    }
                    if ((perm.info.flags & 1073741824) != 0) {
                        pw.print(", INSTALLED");
                    }
                    pw.println();
                }
            }
        }
        if ((permissionNames != null || dumpAll) && ps.pkg != null && ps.pkg.requestedPermissions != null && ps.pkg.requestedPermissions.size() > 0) {
            ArrayList<String> perms2 = ps.pkg.requestedPermissions;
            pw.print(prefix);
            pw.println("  requested permissions:");
            for (int i10 = 0; i10 < perms2.size(); i10++) {
                String perm2 = perms2.get(i10);
                if (permissionNames == null || permissionNames.contains(perm2)) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println(perm2);
                }
            }
        }
        if (ps.sharedUser == null || permissionNames != null || dumpAll) {
            dumpInstallPermissionsLPr(pw, prefix + "  ", permissionNames, ps.getPermissionsState());
        }
        for (UserInfo user3 : users) {
            pw.print(prefix);
            pw.print("  User ");
            pw.print(user3.id);
            pw.print(": ");
            pw.print("ceDataInode=");
            pw.print(ps.getCeDataInode(user3.id));
            pw.print(" installed=");
            pw.print(ps.getInstalled(user3.id));
            pw.print(" hidden=");
            pw.print(ps.getHidden(user3.id));
            pw.print(" suspended=");
            pw.print(ps.getSuspended(user3.id));
            if (ps.getSuspended(user3.id)) {
                PackageUserState pus = ps.readUserState(user3.id);
                pw.print(" suspendingPackage=");
                pw.print(pus.suspendingPackage);
                pw.print(" dialogMessage=");
                pw.print(pus.dialogMessage);
            }
            pw.print(" stopped=");
            pw.print(ps.getStopped(user3.id));
            pw.print(" notLaunched=");
            pw.print(ps.getNotLaunched(user3.id));
            pw.print(" enabled=");
            pw.print(ps.getEnabled(user3.id));
            pw.print(" instant=");
            pw.print(ps.getInstantApp(user3.id));
            pw.print(" virtual=");
            pw.println(ps.getVirtulalPreload(user3.id));
            String[] overlayPaths = ps.getOverlayPaths(user3.id);
            if (overlayPaths != null && overlayPaths.length > 0) {
                pw.print(prefix);
                pw.println("  overlay paths:");
                for (String path : overlayPaths) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println(path);
                }
            }
            String lastDisabledAppCaller2 = ps.getLastDisabledAppCaller(user3.id);
            if (lastDisabledAppCaller2 != null) {
                pw.print(prefix);
                pw.print("    lastDisabledCaller: ");
                pw.println(lastDisabledAppCaller2);
            }
            if (ps.sharedUser == null) {
                PermissionsState permissionsState = ps.getPermissionsState();
                dumpGidsLPr(pw, prefix + "    ", permissionsState.computeGids(user3.id));
                user = user3;
                dumpRuntimePermissionsLPr(pw, prefix + "    ", permissionNames, permissionsState.getRuntimePermissionStates(user3.id), dumpAll);
            } else {
                user = user3;
            }
            String harmfulAppWarning = ps.getHarmfulAppWarning(user.id);
            if (harmfulAppWarning != null) {
                pw.print(prefix);
                pw.print("      harmfulAppWarning: ");
                pw.println(harmfulAppWarning);
            }
            if (permissionNames == null) {
                ArraySet<String> cmp = ps.getDisabledComponents(user.id);
                if (cmp != null && cmp.size() > 0) {
                    pw.print(prefix);
                    pw.println("    disabledComponents:");
                    Iterator<String> it = cmp.iterator();
                    while (it.hasNext()) {
                        String s = it.next();
                        pw.print(prefix);
                        pw.print("      ");
                        pw.println(s);
                    }
                }
                ArraySet<String> cmp2 = ps.getEnabledComponents(user.id);
                if (cmp2 != null && cmp2.size() > 0) {
                    pw.print(prefix);
                    pw.println("    enabledComponents:");
                    Iterator<String> it2 = cmp2.iterator();
                    while (it2.hasNext()) {
                        String s2 = it2.next();
                        pw.print(prefix);
                        pw.print("      ");
                        pw.println(s2);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:14:0x0055, code lost:
        if (r11.getPermissionsState().hasRequestedPermission(r22) != false) goto L14;
     */
    /* JADX WARN: Code restructure failed: missing block: B:47:0x00d6, code lost:
        if (r0 != false) goto L59;
     */
    /* JADX WARN: Code restructure failed: missing block: B:49:0x00dc, code lost:
        if (r23.onTitlePrinted() == false) goto L58;
     */
    /* JADX WARN: Code restructure failed: missing block: B:50:0x00de, code lost:
        r20.println();
     */
    /* JADX WARN: Code restructure failed: missing block: B:51:0x00e1, code lost:
        r14.println("Renamed packages:");
        r0 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:52:0x00e7, code lost:
        r14.print("  ");
     */
    /* JADX WARN: Code restructure failed: missing block: B:73:0x0146, code lost:
        if (r0 != false) goto L91;
     */
    /* JADX WARN: Code restructure failed: missing block: B:75:0x014c, code lost:
        if (r23.onTitlePrinted() == false) goto L90;
     */
    /* JADX WARN: Code restructure failed: missing block: B:76:0x014e, code lost:
        r20.println();
     */
    /* JADX WARN: Code restructure failed: missing block: B:77:0x0151, code lost:
        r14.println("Hidden system packages:");
        r0 = true;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void dumpPackagesLPr(java.io.PrintWriter r20, java.lang.String r21, android.util.ArraySet<java.lang.String> r22, com.android.server.pm.DumpState r23, boolean r24) {
        /*
            Method dump skipped, instructions count: 380
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.dumpPackagesLPr(java.io.PrintWriter, java.lang.String, android.util.ArraySet, com.android.server.pm.DumpState, boolean):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpPackagesProto(ProtoOutputStream proto) {
        List<UserInfo> users = getAllUsers(UserManagerService.getInstance());
        int count = this.mPackages.size();
        for (int i = 0; i < count; i++) {
            PackageSetting ps = this.mPackages.valueAt(i);
            ps.writeToProto(proto, 2246267895813L, users);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpPermissionsLPr(PrintWriter pw, String packageName, ArraySet<String> permissionNames, DumpState dumpState) {
        this.mPermissions.dumpPermissions(pw, packageName, permissionNames, this.mReadExternalStorageEnforced == Boolean.TRUE, dumpState);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpSharedUsersLPr(PrintWriter pw, String packageName, ArraySet<String> permissionNames, DumpState dumpState, boolean checkin) {
        int i;
        int i2;
        int[] iArr;
        boolean printedSomething = false;
        for (SharedUserSetting su : this.mSharedUsers.values()) {
            if (packageName == null || su == dumpState.getSharedUser()) {
                if (permissionNames == null || su.getPermissionsState().hasRequestedPermission(permissionNames)) {
                    if (!checkin) {
                        if (!printedSomething) {
                            if (dumpState.onTitlePrinted()) {
                                pw.println();
                            }
                            pw.println("Shared users:");
                            printedSomething = true;
                        }
                        boolean printedSomething2 = printedSomething;
                        pw.print("  SharedUser [");
                        pw.print(su.name);
                        pw.print("] (");
                        pw.print(Integer.toHexString(System.identityHashCode(su)));
                        pw.println("):");
                        pw.print("    ");
                        pw.print("userId=");
                        pw.println(su.userId);
                        PermissionsState permissionsState = su.getPermissionsState();
                        dumpInstallPermissionsLPr(pw, "    ", permissionNames, permissionsState);
                        int[] userIds = UserManagerService.getInstance().getUserIds();
                        int length = userIds.length;
                        int i3 = 0;
                        while (i3 < length) {
                            int userId = userIds[i3];
                            int[] gids = permissionsState.computeGids(userId);
                            List<PermissionsState.PermissionState> permissions = permissionsState.getRuntimePermissionStates(userId);
                            if (ArrayUtils.isEmpty(gids) && permissions.isEmpty()) {
                                i = i3;
                                i2 = length;
                                iArr = userIds;
                            } else {
                                pw.print("    ");
                                pw.print("User ");
                                pw.print(userId);
                                pw.println(": ");
                                dumpGidsLPr(pw, "      ", gids);
                                i = i3;
                                i2 = length;
                                iArr = userIds;
                                dumpRuntimePermissionsLPr(pw, "      ", permissionNames, permissions, packageName != null);
                            }
                            i3 = i + 1;
                            length = i2;
                            userIds = iArr;
                        }
                        printedSomething = printedSomething2;
                    } else {
                        pw.print("suid,");
                        pw.print(su.userId);
                        pw.print(",");
                        pw.println(su.name);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpSharedUsersProto(ProtoOutputStream proto) {
        int count = this.mSharedUsers.size();
        for (int i = 0; i < count; i++) {
            this.mSharedUsers.valueAt(i).writeToProto(proto, 2246267895814L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpReadMessagesLPr(PrintWriter pw, DumpState dumpState) {
        pw.println("Settings parse messages:");
        pw.print(this.mReadMessages.toString());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpRestoredPermissionGrantsLPr(PrintWriter pw, DumpState dumpState) {
        if (this.mRestoredUserGrants.size() > 0) {
            pw.println();
            pw.println("Restored (pending) permission grants:");
            for (int userIndex = 0; userIndex < this.mRestoredUserGrants.size(); userIndex++) {
                ArrayMap<String, ArraySet<RestoredPermissionGrant>> grantsByPackage = this.mRestoredUserGrants.valueAt(userIndex);
                if (grantsByPackage != null && grantsByPackage.size() > 0) {
                    int userId = this.mRestoredUserGrants.keyAt(userIndex);
                    pw.print("  User ");
                    pw.println(userId);
                    for (int pkgIndex = 0; pkgIndex < grantsByPackage.size(); pkgIndex++) {
                        ArraySet<RestoredPermissionGrant> grants = grantsByPackage.valueAt(pkgIndex);
                        if (grants != null && grants.size() > 0) {
                            String pkgName = grantsByPackage.keyAt(pkgIndex);
                            pw.print("    ");
                            pw.print(pkgName);
                            pw.println(" :");
                            Iterator<RestoredPermissionGrant> it = grants.iterator();
                            while (it.hasNext()) {
                                RestoredPermissionGrant g = it.next();
                                pw.print("      ");
                                pw.print(g.permissionName);
                                if (g.granted) {
                                    pw.print(" GRANTED");
                                }
                                if ((g.grantBits & 1) != 0) {
                                    pw.print(" user_set");
                                }
                                if ((g.grantBits & 2) != 0) {
                                    pw.print(" user_fixed");
                                }
                                if ((g.grantBits & 8) != 0) {
                                    pw.print(" revoke_on_upgrade");
                                }
                                pw.println();
                            }
                        }
                    }
                }
            }
            pw.println();
        }
    }

    private static void dumpSplitNames(PrintWriter pw, PackageParser.Package pkg) {
        if (pkg == null) {
            pw.print(UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN);
            return;
        }
        pw.print("[");
        pw.print("base");
        if (pkg.baseRevisionCode != 0) {
            pw.print(":");
            pw.print(pkg.baseRevisionCode);
        }
        if (pkg.splitNames != null) {
            for (int i = 0; i < pkg.splitNames.length; i++) {
                pw.print(", ");
                pw.print(pkg.splitNames[i]);
                if (pkg.splitRevisionCodes[i] != 0) {
                    pw.print(":");
                    pw.print(pkg.splitRevisionCodes[i]);
                }
            }
        }
        pw.print("]");
    }

    void dumpGidsLPr(PrintWriter pw, String prefix, int[] gids) {
        if (!ArrayUtils.isEmpty(gids)) {
            pw.print(prefix);
            pw.print("gids=");
            pw.println(PackageManagerService.arrayToString(gids));
        }
    }

    void dumpRuntimePermissionsLPr(PrintWriter pw, String prefix, ArraySet<String> permissionNames, List<PermissionsState.PermissionState> permissionStates, boolean dumpAll) {
        if (!permissionStates.isEmpty() || dumpAll) {
            pw.print(prefix);
            pw.println("runtime permissions:");
            for (PermissionsState.PermissionState permissionState : permissionStates) {
                if (permissionNames == null || permissionNames.contains(permissionState.getName())) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print(permissionState.getName());
                    pw.print(": granted=");
                    pw.print(permissionState.isGranted());
                    pw.println(permissionFlagsToString(", flags=", permissionState.getFlags()));
                }
            }
        }
    }

    private static String permissionFlagsToString(String prefix, int flags) {
        StringBuilder flagsString = null;
        while (flags != 0) {
            if (flagsString == null) {
                flagsString = new StringBuilder();
                flagsString.append(prefix);
                flagsString.append("[ ");
            }
            int flag = 1 << Integer.numberOfTrailingZeros(flags);
            flags &= ~flag;
            flagsString.append(PackageManager.permissionFlagToString(flag));
            flagsString.append(' ');
        }
        if (flagsString != null) {
            flagsString.append(']');
            return flagsString.toString();
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    void dumpInstallPermissionsLPr(PrintWriter pw, String prefix, ArraySet<String> permissionNames, PermissionsState permissionsState) {
        List<PermissionsState.PermissionState> permissionStates = permissionsState.getInstallPermissionStates();
        if (!permissionStates.isEmpty()) {
            pw.print(prefix);
            pw.println("install permissions:");
            for (PermissionsState.PermissionState permissionState : permissionStates) {
                if (permissionNames == null || permissionNames.contains(permissionState.getName())) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print(permissionState.getName());
                    pw.print(": granted=");
                    pw.print(permissionState.isGranted());
                    pw.println(permissionFlagsToString(", flags=", permissionState.getFlags()));
                }
            }
        }
    }

    public void writeRuntimePermissionsForUserLPr(int userId, boolean sync) {
        if (sync) {
            this.mRuntimePermissionsPersistence.writePermissionsForUserSyncLPr(userId);
        } else {
            this.mRuntimePermissionsPersistence.writePermissionsForUserAsyncLPr(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class RuntimePermissionPersistence {
        private static final long MAX_WRITE_PERMISSIONS_DELAY_MILLIS = 2000;
        private static final long WRITE_PERMISSIONS_DELAY_MILLIS = 200;
        private final Object mPersistenceLock;
        private final Handler mHandler = new MyHandler();
        @GuardedBy("mLock")
        private final SparseBooleanArray mWriteScheduled = new SparseBooleanArray();
        @GuardedBy("mLock")
        private final SparseLongArray mLastNotWrittenMutationTimesMillis = new SparseLongArray();
        @GuardedBy("mLock")
        private final SparseArray<String> mFingerprints = new SparseArray<>();
        @GuardedBy("mLock")
        private final SparseBooleanArray mDefaultPermissionsGranted = new SparseBooleanArray();

        public RuntimePermissionPersistence(Object persistenceLock) {
            this.mPersistenceLock = persistenceLock;
        }

        public boolean areDefaultRuntimPermissionsGrantedLPr(int userId) {
            return this.mDefaultPermissionsGranted.get(userId);
        }

        public void onDefaultRuntimePermissionsGrantedLPr(int userId) {
            this.mFingerprints.put(userId, Build.FINGERPRINT);
            writePermissionsForUserAsyncLPr(userId);
        }

        public void writePermissionsForUserSyncLPr(int userId) {
            this.mHandler.removeMessages(userId);
            writePermissionsSync(userId);
        }

        public void writePermissionsForUserAsyncLPr(int userId) {
            long currentTimeMillis = SystemClock.uptimeMillis();
            if (this.mWriteScheduled.get(userId)) {
                this.mHandler.removeMessages(userId);
                long lastNotWrittenMutationTimeMillis = this.mLastNotWrittenMutationTimesMillis.get(userId);
                long timeSinceLastNotWrittenMutationMillis = currentTimeMillis - lastNotWrittenMutationTimeMillis;
                if (timeSinceLastNotWrittenMutationMillis >= MAX_WRITE_PERMISSIONS_DELAY_MILLIS) {
                    this.mHandler.obtainMessage(userId).sendToTarget();
                    return;
                }
                long maxDelayMillis = Math.max((MAX_WRITE_PERMISSIONS_DELAY_MILLIS + lastNotWrittenMutationTimeMillis) - currentTimeMillis, 0L);
                long writeDelayMillis = Math.min((long) WRITE_PERMISSIONS_DELAY_MILLIS, maxDelayMillis);
                Message message = this.mHandler.obtainMessage(userId);
                this.mHandler.sendMessageDelayed(message, writeDelayMillis);
                return;
            }
            this.mLastNotWrittenMutationTimesMillis.put(userId, currentTimeMillis);
            Message message2 = this.mHandler.obtainMessage(userId);
            this.mHandler.sendMessageDelayed(message2, WRITE_PERMISSIONS_DELAY_MILLIS);
            this.mWriteScheduled.put(userId, true);
        }

        /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
            jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
            	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
            	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
            	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
            	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
            	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
            */
        /* JADX INFO: Access modifiers changed from: private */
        public void writePermissionsSync(int r26) {
            /*
                Method dump skipped, instructions count: 638
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.RuntimePermissionPersistence.writePermissionsSync(int):void");
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void onUserRemovedLPw(int userId) {
            this.mHandler.removeMessages(userId);
            for (SettingBase sb : Settings.this.mPackages.values()) {
                revokeRuntimePermissionsAndClearFlags(sb, userId);
            }
            for (SettingBase sb2 : Settings.this.mSharedUsers.values()) {
                revokeRuntimePermissionsAndClearFlags(sb2, userId);
            }
            this.mDefaultPermissionsGranted.delete(userId);
            this.mFingerprints.remove(userId);
        }

        private void revokeRuntimePermissionsAndClearFlags(SettingBase sb, int userId) {
            PermissionsState permissionsState = sb.getPermissionsState();
            for (PermissionsState.PermissionState permissionState : permissionsState.getRuntimePermissionStates(userId)) {
                BasePermission bp = Settings.this.mPermissions.getPermission(permissionState.getName());
                if (bp != null) {
                    permissionsState.revokeRuntimePermission(bp, userId);
                    permissionsState.updatePermissionFlags(bp, userId, 255, 0);
                }
            }
        }

        public void deleteUserRuntimePermissionsFile(int userId) {
            Settings.this.getUserRuntimePermissionsFile(userId).delete();
        }

        public void readStateForUserSyncLPr(int userId) {
            File permissionsFile = Settings.this.getUserRuntimePermissionsFile(userId);
            if (!permissionsFile.exists()) {
                return;
            }
            try {
                FileInputStream in = new AtomicFile(permissionsFile).openRead();
                try {
                    try {
                        XmlPullParser parser = Xml.newPullParser();
                        parser.setInput(in, null);
                        parseRuntimePermissionsLPr(parser, userId);
                    } catch (IOException | XmlPullParserException e) {
                        throw new IllegalStateException("Failed parsing permissions file: " + permissionsFile, e);
                    }
                } finally {
                    IoUtils.closeQuietly(in);
                }
            } catch (FileNotFoundException e2) {
                Slog.i("PackageManager", "No permissions state");
            }
        }

        public void rememberRestoredUserGrantLPr(String pkgName, String permission, boolean isGranted, int restoredFlagSet, int userId) {
            ArrayMap<String, ArraySet<RestoredPermissionGrant>> grantsByPackage = (ArrayMap) Settings.this.mRestoredUserGrants.get(userId);
            if (grantsByPackage == null) {
                grantsByPackage = new ArrayMap<>();
                Settings.this.mRestoredUserGrants.put(userId, grantsByPackage);
            }
            ArraySet<RestoredPermissionGrant> grants = grantsByPackage.get(pkgName);
            if (grants == null) {
                grants = new ArraySet<>();
                grantsByPackage.put(pkgName, grants);
            }
            RestoredPermissionGrant grant = new RestoredPermissionGrant(permission, isGranted, restoredFlagSet);
            grants.add(grant);
        }

        /* JADX WARN: Code restructure failed: missing block: B:29:0x0056, code lost:
            if (r4.equals(com.android.server.pm.Settings.TAG_PACKAGE) != false) goto L27;
         */
        /* JADX WARN: Removed duplicated region for block: B:54:0x006b A[SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:55:0x0077 A[SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:56:0x00ad A[SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:57:0x00e3 A[SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:65:0x0004 A[SYNTHETIC] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        private void parseRuntimePermissionsLPr(org.xmlpull.v1.XmlPullParser r9, int r10) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
            /*
                Method dump skipped, instructions count: 266
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.RuntimePermissionPersistence.parseRuntimePermissionsLPr(org.xmlpull.v1.XmlPullParser, int):void");
        }

        private void parseRestoredRuntimePermissionsLPr(XmlPullParser parser, String pkgName, int userId) throws IOException, XmlPullParserException {
            int permBits;
            int outerDepth = parser.getDepth();
            while (true) {
                int type = parser.next();
                if (type != 1) {
                    if (type != 3 || parser.getDepth() > outerDepth) {
                        if (type != 3 && type != 4) {
                            String name = parser.getName();
                            char c = 65535;
                            if (name.hashCode() == 3437296 && name.equals(Settings.TAG_PERMISSION_ENTRY)) {
                                c = 0;
                            }
                            if (c == 0) {
                                String permName = parser.getAttributeValue(null, Settings.ATTR_NAME);
                                boolean isGranted = "true".equals(parser.getAttributeValue(null, Settings.ATTR_GRANTED));
                                int permBits2 = 0;
                                if ("true".equals(parser.getAttributeValue(null, Settings.ATTR_USER_SET))) {
                                    permBits2 = 0 | 1;
                                }
                                if ("true".equals(parser.getAttributeValue(null, Settings.ATTR_USER_FIXED))) {
                                    permBits2 |= 2;
                                }
                                if ("true".equals(parser.getAttributeValue(null, Settings.ATTR_REVOKE_ON_UPGRADE))) {
                                    permBits = permBits2 | 8;
                                } else {
                                    permBits = permBits2;
                                }
                                if (isGranted || permBits != 0) {
                                    rememberRestoredUserGrantLPr(pkgName, permName, isGranted, permBits, userId);
                                }
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

        private void parsePermissionsLPr(XmlPullParser parser, PermissionsState permissionsState, int userId) throws IOException, XmlPullParserException {
            int outerDepth = parser.getDepth();
            while (true) {
                int type = parser.next();
                boolean granted = true;
                if (type != 1) {
                    if (type != 3 || parser.getDepth() > outerDepth) {
                        if (type != 3 && type != 4) {
                            String name = parser.getName();
                            char c = 65535;
                            if (name.hashCode() == 3242771 && name.equals(Settings.TAG_ITEM)) {
                                c = 0;
                            }
                            if (c == 0) {
                                String name2 = parser.getAttributeValue(null, Settings.ATTR_NAME);
                                BasePermission bp = Settings.this.mPermissions.getPermission(name2);
                                if (bp == null) {
                                    Slog.w("PackageManager", "Unknown permission:" + name2);
                                    XmlUtils.skipCurrentTag(parser);
                                } else {
                                    String grantedStr = parser.getAttributeValue(null, Settings.ATTR_GRANTED);
                                    if (grantedStr != null && !Boolean.parseBoolean(grantedStr)) {
                                        granted = false;
                                    }
                                    String flagsStr = parser.getAttributeValue(null, "flags");
                                    int flags = flagsStr != null ? Integer.parseInt(flagsStr, 16) : 0;
                                    if (granted) {
                                        permissionsState.grantRuntimePermission(bp, userId);
                                        permissionsState.updatePermissionFlags(bp, userId, 255, flags);
                                    } else {
                                        permissionsState.updatePermissionFlags(bp, userId, 255, flags);
                                    }
                                }
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

        private void writePermissions(XmlSerializer serializer, List<PermissionsState.PermissionState> permissionStates) throws IOException {
            for (PermissionsState.PermissionState permissionState : permissionStates) {
                serializer.startTag(null, Settings.TAG_ITEM);
                serializer.attribute(null, Settings.ATTR_NAME, permissionState.getName());
                serializer.attribute(null, Settings.ATTR_GRANTED, String.valueOf(permissionState.isGranted()));
                serializer.attribute(null, "flags", Integer.toHexString(permissionState.getFlags()));
                serializer.endTag(null, Settings.TAG_ITEM);
            }
        }

        /* loaded from: classes.dex */
        private final class MyHandler extends Handler {
            public MyHandler() {
                super(BackgroundThread.getHandler().getLooper());
            }

            @Override // android.os.Handler
            public void handleMessage(Message message) {
                int userId = message.what;
                Runnable callback = (Runnable) message.obj;
                RuntimePermissionPersistence.this.writePermissionsSync(userId);
                if (callback != null) {
                    callback.run();
                }
            }
        }
    }
}
