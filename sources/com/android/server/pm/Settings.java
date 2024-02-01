package com.android.server.pm;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
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
import android.os.SELinux;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.EventLogTags;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.UiModeManagerService;
import com.android.server.pm.permission.BasePermission;
import com.android.server.pm.permission.PermissionSettings;
import com.android.server.pm.permission.PermissionsState;
import com.android.server.voiceinteraction.DatabaseHelper;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath;
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
    private static final String ATTR_DATABASE_VERSION = "databaseVersion";
    private static final String ATTR_DISTRACTION_FLAGS = "distraction_flags";
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
    private static final String ATTR_SDK_VERSION = "sdkVersion";
    private static final String ATTR_STOPPED = "stopped";
    private static final String ATTR_SUSPENDED = "suspended";
    private static final String ATTR_SUSPENDING_PACKAGE = "suspending-package";
    @Deprecated
    private static final String ATTR_SUSPEND_DIALOG_MESSAGE = "suspend_dialog_message";
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
    private static final String TAG_PERSISTENT_PREFERRED_ACTIVITIES = "persistent-preferred-activities";
    private static final String TAG_READ_EXTERNAL_STORAGE = "read-external-storage";
    private static final String TAG_RUNTIME_PERMISSIONS = "runtime-permissions";
    private static final String TAG_SHARED_USER = "shared-user";
    private static final String TAG_SUSPENDED_APP_EXTRAS = "suspended-app-extras";
    private static final String TAG_SUSPENDED_DIALOG_INFO = "suspended-dialog-info";
    private static final String TAG_SUSPENDED_LAUNCHER_EXTRAS = "suspended-launcher-extras";
    private static final String TAG_USES_STATIC_LIB = "uses-static-lib";
    private static final String TAG_VERSION = "version";
    private final File mBackupSettingsFilename;
    private final File mBackupStoppedPackagesFilename;
    private final File mKernelMappingFilename;
    private final Object mLock;
    private final File mPackageListFilename;
    final PermissionSettings mPermissions;
    Boolean mReadExternalStorageEnforced;
    private final RuntimePermissionPersistence mRuntimePermissionsPersistence;
    private final File mSettingsFilename;
    private final File mStoppedPackagesFilename;
    private final File mSystemDir;
    private VerifierDeviceIdentity mVerifierDeviceIdentity;
    private static int mFirstAvailableUid = 10001;
    private static int PRE_M_APP_INFO_FLAG_HIDDEN = 134217728;
    private static int PRE_M_APP_INFO_FLAG_CANT_SAVE_STATE = 268435456;
    private static int PRE_M_APP_INFO_FLAG_PRIVILEGED = 1073741824;
    static final Object[] FLAG_DUMP_SPEC = {1, xuiExternalAudioPath.SYS_BUS, 2, "DEBUGGABLE", 4, "HAS_CODE", 8, "PERSISTENT", 16, "FACTORY_TEST", 32, "ALLOW_TASK_REPARENTING", 64, "ALLOW_CLEAR_USER_DATA", 128, "UPDATED_SYSTEM_APP", 256, "TEST_ONLY", 16384, "VM_SAFE_MODE", 32768, "ALLOW_BACKUP", 65536, "KILL_AFTER_RESTORE", 131072, "RESTORE_ANY_VERSION", 262144, "EXTERNAL_STORAGE", Integer.valueOf((int) DumpState.DUMP_DEXOPT), "LARGE_HEAP"};
    private static final Object[] PRIVATE_FLAG_DUMP_SPEC = {1024, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE", 4096, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION", 2048, "PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE", 134217728, "ALLOW_AUDIO_PLAYBACK_CAPTURE", 536870912, "PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE", 8192, "BACKUP_IN_FOREGROUND", 2, "CANT_SAVE_STATE", 32, "DEFAULT_TO_DEVICE_PROTECTED_STORAGE", 64, "DIRECT_BOOT_AWARE", 16, "HAS_DOMAIN_URLS", 1, "HIDDEN", 128, "EPHEMERAL", 32768, "ISOLATED_SPLIT_LOADING", 131072, "OEM", 256, "PARTIALLY_DIRECT_BOOT_AWARE", 8, "PRIVILEGED", 512, "REQUIRED_FOR_SYSTEM_USER", 16384, "STATIC_SHARED_LIBRARY", 262144, "VENDOR", Integer.valueOf((int) DumpState.DUMP_FROZEN), "PRODUCT", Integer.valueOf((int) DumpState.DUMP_COMPILER_STATS), "PRODUCT_SERVICES", 65536, "VIRTUAL_PRELOAD", 1073741824, "ODM"};
    final ArrayMap<String, PackageSetting> mPackages = new ArrayMap<>();
    final ArraySet<String> mInstallerPackages = new ArraySet<>();
    private final ArrayMap<String, KernelPackageState> mKernelMapping = new ArrayMap<>();
    private final ArrayMap<String, PackageSetting> mDisabledSysPackages = new ArrayMap<>();
    private final SparseArray<ArraySet<String>> mBlockUninstallPackages = new SparseArray<>();
    private final ArrayMap<String, IntentFilterVerificationInfo> mRestoredIntentFilterVerifications = new ArrayMap<>();
    private ArrayMap<String, VersionInfo> mVersion = new ArrayMap<>();
    final SparseArray<PreferredIntentResolver> mPreferredActivities = new SparseArray<>();
    final SparseArray<PersistentPreferredIntentResolver> mPersistentPreferredActivities = new SparseArray<>();
    final SparseArray<CrossProfileIntentResolver> mCrossProfileIntentResolvers = new SparseArray<>();
    final ArrayMap<String, SharedUserSetting> mSharedUsers = new ArrayMap<>();
    private final ArrayList<SettingBase> mAppIds = new ArrayList<>();
    private final SparseArray<SettingBase> mOtherAppIds = new SparseArray<>();
    private final ArrayList<Signature> mPastSignatures = new ArrayList<>();
    private final ArrayMap<Long, Integer> mKeySetRefs = new ArrayMap<>();
    private final ArrayMap<String, String> mRenamedPackages = new ArrayMap<>();
    final SparseArray<String> mDefaultBrowserApp = new SparseArray<>();
    final SparseIntArray mNextAppLinkGeneration = new SparseIntArray();
    final StringBuilder mReadMessages = new StringBuilder();
    private final ArrayList<PackageSetting> mPendingPackages = new ArrayList<>();
    public final KeySetManagerService mKeySetManagerService = new KeySetManagerService(this.mPackages);

    /* loaded from: classes.dex */
    public static class DatabaseVersion {
        public static final int FIRST_VERSION = 1;
        public static final int SIGNATURE_END_ENTITY = 2;
        public static final int SIGNATURE_MALFORMED_RECOVER = 3;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class KernelPackageState {
        int appId;
        int[] excludedUserIds;

        private KernelPackageState() {
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
    public Settings(File dataDir, PermissionSettings permission, Object lock) {
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
            s.userId = acquireAndRegisterNewAppIdLPw(s);
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
        PackageSetting disabled;
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
        if (replaced) {
            disabled = new PackageSetting(p);
        } else {
            disabled = p;
        }
        this.mDisabledSysPackages.put(name, disabled);
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
        if (!registerExistingAppIdLPw(uid, p2, name)) {
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
        if (!registerExistingAppIdLPw(uid, s2, name)) {
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
    /* JADX WARN: Code restructure failed: missing block: B:25:0x00cb, code lost:
        if (isAdbInstallDisallowed(r66, r8.id) != false) goto L26;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static com.android.server.pm.PackageSetting createNewSetting(java.lang.String r46, com.android.server.pm.PackageSetting r47, com.android.server.pm.PackageSetting r48, java.lang.String r49, com.android.server.pm.SharedUserSetting r50, java.io.File r51, java.io.File r52, java.lang.String r53, java.lang.String r54, java.lang.String r55, long r56, int r58, int r59, android.os.UserHandle r60, boolean r61, boolean r62, boolean r63, java.lang.String r64, java.util.List<java.lang.String> r65, com.android.server.pm.UserManagerService r66, java.lang.String[] r67, long[] r68) {
        /*
            Method dump skipped, instructions count: 354
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.createNewSetting(java.lang.String, com.android.server.pm.PackageSetting, com.android.server.pm.PackageSetting, java.lang.String, com.android.server.pm.SharedUserSetting, java.io.File, java.io.File, java.lang.String, java.lang.String, java.lang.String, long, int, int, android.os.UserHandle, boolean, boolean, boolean, java.lang.String, java.util.List, com.android.server.pm.UserManagerService, java.lang.String[], long[]):com.android.server.pm.PackageSetting");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void updatePackageSetting(PackageSetting pkgSetting, PackageSetting disabledPkg, SharedUserSetting sharedUser, File codePath, File resourcePath, String legacyNativeLibraryPath, String primaryCpuAbi, String secondaryCpuAbi, int pkgFlags, int pkgPrivateFlags, List<String> childPkgNames, UserManagerService userManager, String[] usesStaticLibraries, long[] usesStaticLibrariesVersions) throws PackageManagerException {
        String str;
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
        if (pkgSetting.codePath.equals(codePath)) {
            str = " system";
        } else {
            boolean isSystem = pkgSetting.isSystem();
            StringBuilder sb2 = new StringBuilder();
            sb2.append("Update");
            str = " system";
            sb2.append(isSystem ? " system" : "");
            sb2.append(" package ");
            sb2.append(pkgName);
            sb2.append(" code path from ");
            sb2.append(pkgSetting.codePathString);
            sb2.append(" to ");
            sb2.append(codePath.toString());
            sb2.append("; Retain data and using new");
            Slog.i("PackageManager", sb2.toString());
            if (!isSystem) {
                if ((pkgFlags & 1) != 0 && disabledPkg == null) {
                    List<UserInfo> allUserInfos = getAllUsers(userManager);
                    if (allUserInfos != null) {
                        for (UserInfo userInfo : allUserInfos) {
                            List<UserInfo> allUserInfos2 = allUserInfos;
                            pkgSetting.setInstalled(true, userInfo.id);
                            isSystem = isSystem;
                            allUserInfos = allUserInfos2;
                        }
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
            sb3.append(isSystem2 ? str : "");
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
        pkgSetting.pkgPrivateFlags &= -1076756489;
        pkgSetting.pkgFlags |= pkgFlags & 1;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & 8;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & 131072;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & 262144;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & DumpState.DUMP_FROZEN;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & DumpState.DUMP_COMPILER_STATS;
        pkgSetting.pkgPrivateFlags |= pkgPrivateFlags & 1073741824;
        pkgSetting.primaryCpuAbiString = primaryCpuAbi;
        pkgSetting.secondaryCpuAbiString = secondaryCpuAbi;
        if (childPkgNames != null) {
            pkgSetting.childPackageNames = new ArrayList(childPkgNames);
        }
        if (usesStaticLibraries == null || usesStaticLibrariesVersions == null || usesStaticLibraries.length != usesStaticLibrariesVersions.length) {
            pkgSetting.usesStaticLibraries = null;
            pkgSetting.usesStaticLibrariesVersions = null;
            return;
        }
        pkgSetting.usesStaticLibraries = usesStaticLibraries;
        pkgSetting.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean registerAppIdLPw(PackageSetting p) throws PackageManagerException {
        boolean createdNew;
        if (p.appId == 0) {
            p.appId = acquireAndRegisterNewAppIdLPw(p);
            createdNew = true;
        } else {
            createdNew = registerExistingAppIdLPw(p.appId, p, p.name);
        }
        if (p.appId < 0) {
            PackageManagerService.reportSettingsProblem(5, "Package " + p.name + " could not be assigned a valid UID");
            throw new PackageManagerException(-4, "Package " + p.name + " could not be assigned a valid UID");
        }
        return createdNew;
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
        Object userIdPs = getSettingLPr(p.appId);
        if (sharedUser == null) {
            if (userIdPs != null && userIdPs != p) {
                replaceAppIdLPw(p.appId, p);
            }
        } else if (userIdPs != null && userIdPs != sharedUser) {
            replaceAppIdLPw(p.appId, sharedUser);
        }
        IntentFilterVerificationInfo ivi = this.mRestoredIntentFilterVerifications.get(p.name);
        if (ivi != null) {
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
            int affectedUserId = -10000;
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
                    if (!used) {
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
                            }
                        }
                        permissionsState.updatePermissionFlags(bp, userId, 64511, 0);
                        if (permissionsState.revokeInstallPermission(bp) == 1) {
                            affectedUserId = -1;
                        }
                        if (permissionsState.revokeRuntimePermission(bp, userId) == 1) {
                            if (affectedUserId == -10000) {
                                affectedUserId = userId;
                            } else if (affectedUserId != userId) {
                                affectedUserId = -1;
                            }
                        }
                    }
                }
            }
            return affectedUserId;
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
                    removeAppIdLPw(p.sharedUser.userId);
                    return p.sharedUser.userId;
                }
                return -1;
            }
            removeAppIdLPw(p.appId);
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

    private boolean registerExistingAppIdLPw(int appId, SettingBase obj, Object name) {
        if (appId > 19999) {
            return false;
        }
        if (appId >= 10000) {
            int index = appId - 10000;
            for (int size = this.mAppIds.size(); index >= size; size++) {
                this.mAppIds.add(null);
            }
            if (this.mAppIds.get(index) != null) {
                PackageManagerService.reportSettingsProblem(6, "Adding duplicate app id: " + appId + " name=" + name);
                return false;
            }
            this.mAppIds.set(index, obj);
            return true;
        } else if (this.mOtherAppIds.get(appId) != null) {
            PackageManagerService.reportSettingsProblem(6, "Adding duplicate shared id: " + appId + " name=" + name);
            return false;
        } else {
            this.mOtherAppIds.put(appId, obj);
            return true;
        }
    }

    public SettingBase getSettingLPr(int appId) {
        if (appId >= 10000) {
            int size = this.mAppIds.size();
            int index = appId - 10000;
            if (index < size) {
                return this.mAppIds.get(index);
            }
            return null;
        }
        return this.mOtherAppIds.get(appId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAppIdLPw(int appId) {
        if (appId >= 10000) {
            int size = this.mAppIds.size();
            int index = appId - 10000;
            if (index < size) {
                this.mAppIds.set(index, null);
            }
        } else {
            this.mOtherAppIds.remove(appId);
        }
        setFirstAvailableUid(appId + 1);
    }

    private void replaceAppIdLPw(int appId, SettingBase obj) {
        if (appId >= 10000) {
            int size = this.mAppIds.size();
            int index = appId - 10000;
            if (index < size) {
                this.mAppIds.set(index, obj);
                return;
            }
            return;
        }
        this.mOtherAppIds.put(appId, obj);
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
            return null;
        }
        return ps.getIntentFilterVerificationInfo();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IntentFilterVerificationInfo createIntentFilterVerificationIfNeededLPw(String packageName, ArraySet<String> domains) {
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            return null;
        }
        IntentFilterVerificationInfo ivi = ps.getIntentFilterVerificationInfo();
        if (ivi == null) {
            IntentFilterVerificationInfo ivi2 = new IntentFilterVerificationInfo(packageName, domains);
            ps.setIntentFilterVerificationInfo(ivi2);
            return ivi2;
        }
        ivi.setDomains(domains);
        return ivi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getIntentFilterVerificationStatusLPr(String packageName, int userId) {
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            return 0;
        }
        return (int) (ps.getDomainVerificationStatusForUser(userId) >> 32);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateIntentFilterVerificationStatusLPw(String packageName, int status, int userId) {
        int alwaysGeneration;
        PackageSetting current = this.mPackages.get(packageName);
        if (current == null) {
            return false;
        }
        if (status == 2) {
            alwaysGeneration = this.mNextAppLinkGeneration.get(userId) + 1;
            this.mNextAppLinkGeneration.put(userId, alwaysGeneration);
        } else {
            alwaysGeneration = 0;
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
    public boolean removeIntentFilterVerificationLPw(String packageName, int userId, boolean alsoResetStatus) {
        PackageSetting ps = this.mPackages.get(packageName);
        if (ps == null) {
            return false;
        }
        if (alsoResetStatus) {
            ps.clearDomainVerificationStatusForUser(userId);
        }
        ps.setIntentFilterVerificationInfo(null);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeIntentFilterVerificationLPw(String packageName, int[] userIds) {
        boolean result = false;
        for (int userId : userIds) {
            result |= removeIntentFilterVerificationLPw(packageName, userId, true);
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String removeDefaultBrowserPackageNameLPw(int userId) {
        if (userId == -1) {
            return null;
        }
        return (String) this.mDefaultBrowserApp.removeReturnOld(userId);
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
        return this.mRuntimePermissionsPersistence.areDefaultRuntimePermissionsGrantedLPr(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRuntimePermissionsFingerPrintLPr(String fingerPrint, int userId) {
        this.mRuntimePermissionsPersistence.setRuntimePermissionsFingerPrintLPr(fingerPrint, userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getDefaultRuntimePermissionsVersionLPr(int userId) {
        return this.mRuntimePermissionsPersistence.getVersionLPr(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDefaultRuntimePermissionsVersionLPr(int version, int userId) {
        this.mRuntimePermissionsPersistence.setVersionLPr(version, userId);
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
                        } else if (!tagName.equals(TAG_DEFAULT_DIALER)) {
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:106:0x02bc
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    void readPackageRestrictionsLPr(int r58) {
        /*
            Method dump skipped, instructions count: 1780
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.readPackageRestrictionsLPr(int):void");
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
                            } else {
                                this.mRestoredIntentFilterVerifications.put(pkgName, ivi);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeDefaultAppsLPr(XmlSerializer serializer, int userId) throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, TAG_DEFAULT_APPS);
        String defaultBrowser = this.mDefaultBrowserApp.get(userId);
        if (!TextUtils.isEmpty(defaultBrowser)) {
            serializer.startTag(null, TAG_DEFAULT_BROWSER);
            serializer.attribute(null, "packageName", defaultBrowser);
            serializer.endTag(null, TAG_DEFAULT_BROWSER);
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
        File userPackagesStateFile;
        String str;
        FileOutputStream fstr;
        String str2;
        Settings settings = this;
        int i = userId;
        String str3 = TAG_SUSPENDED_DIALOG_INFO;
        long startTime = SystemClock.uptimeMillis();
        File userPackagesStateFile2 = getUserPackagesStateFile(userId);
        File backupFile = getUserPackagesStateBackupFile(userId);
        new File(userPackagesStateFile2.getParent()).mkdirs();
        if (userPackagesStateFile2.exists()) {
            if (!backupFile.exists()) {
                if (!userPackagesStateFile2.renameTo(backupFile)) {
                    Slog.wtf("PackageManager", "Unable to backup user packages state file, current changes will be lost at reboot");
                    return;
                }
            } else {
                userPackagesStateFile2.delete();
                Slog.w("PackageManager", "Preserving older stopped packages backup");
            }
        }
        try {
            str = "PackageManager";
            FileOutputStream fstr2 = new FileOutputStream(userPackagesStateFile2);
            try {
                BufferedOutputStream str4 = new BufferedOutputStream(fstr2);
                XmlSerializer serializer = new FastXmlSerializer();
                try {
                    serializer.setOutput(str4, StandardCharsets.UTF_8.name());
                    userPackagesStateFile = userPackagesStateFile2;
                    try {
                        serializer.startDocument(null, true);
                        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                        serializer.startTag(null, TAG_PACKAGE_RESTRICTIONS);
                        Iterator<PackageSetting> it = settings.mPackages.values().iterator();
                        while (it.hasNext()) {
                            try {
                                PackageSetting pkg = it.next();
                                Iterator<PackageSetting> it2 = it;
                                PackageUserState ustate = pkg.readUserState(i);
                                File backupFile2 = backupFile;
                                try {
                                    serializer.startTag(null, TAG_PACKAGE);
                                    serializer.attribute(null, ATTR_NAME, pkg.name);
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
                                    if (ustate.distractionFlags == 0) {
                                        fstr = fstr2;
                                    } else {
                                        fstr = fstr2;
                                        serializer.attribute(null, ATTR_DISTRACTION_FLAGS, Integer.toString(ustate.distractionFlags));
                                    }
                                    if (!ustate.suspended) {
                                        str2 = str3;
                                    } else {
                                        serializer.attribute(null, ATTR_SUSPENDED, "true");
                                        if (ustate.suspendingPackage != null) {
                                            serializer.attribute(null, ATTR_SUSPENDING_PACKAGE, ustate.suspendingPackage);
                                        }
                                        if (ustate.dialogInfo != null) {
                                            serializer.startTag(null, str3);
                                            ustate.dialogInfo.saveToXml(serializer);
                                            serializer.endTag(null, str3);
                                        }
                                        if (ustate.suspendedAppExtras == null) {
                                            str2 = str3;
                                        } else {
                                            serializer.startTag(null, TAG_SUSPENDED_APP_EXTRAS);
                                            try {
                                                ustate.suspendedAppExtras.saveToXml(serializer);
                                                str2 = str3;
                                            } catch (XmlPullParserException xmle) {
                                                StringBuilder sb = new StringBuilder();
                                                str2 = str3;
                                                sb.append("Exception while trying to write suspendedAppExtras for ");
                                                sb.append(pkg);
                                                sb.append(". Will be lost on reboot");
                                                Slog.wtf(TAG, sb.toString(), xmle);
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
                                        Iterator it3 = ustate.enabledComponents.iterator();
                                        while (it3.hasNext()) {
                                            String name = (String) it3.next();
                                            serializer.startTag(null, TAG_ITEM);
                                            serializer.attribute(null, ATTR_NAME, name);
                                            serializer.endTag(null, TAG_ITEM);
                                        }
                                        serializer.endTag(null, TAG_ENABLED_COMPONENTS);
                                    }
                                    if (!ArrayUtils.isEmpty(ustate.disabledComponents)) {
                                        serializer.startTag(null, TAG_DISABLED_COMPONENTS);
                                        Iterator it4 = ustate.disabledComponents.iterator();
                                        while (it4.hasNext()) {
                                            String name2 = (String) it4.next();
                                            serializer.startTag(null, TAG_ITEM);
                                            serializer.attribute(null, ATTR_NAME, name2);
                                            serializer.endTag(null, TAG_ITEM);
                                        }
                                        serializer.endTag(null, TAG_DISABLED_COMPONENTS);
                                    }
                                    serializer.endTag(null, TAG_PACKAGE);
                                    settings = this;
                                    i = userId;
                                    fstr2 = fstr;
                                    it = it2;
                                    backupFile = backupFile2;
                                    str3 = str2;
                                } catch (IOException e) {
                                    e = e;
                                    settings = this;
                                    String str5 = str;
                                    Slog.wtf(str5, "Unable to write package manager user packages state,  current changes will be lost at reboot", e);
                                    if (!userPackagesStateFile.exists()) {
                                    }
                                    return;
                                }
                            } catch (IOException e2) {
                                e = e2;
                                settings = this;
                            }
                        }
                        File backupFile3 = backupFile;
                        FileOutputStream fstr3 = fstr2;
                        settings = this;
                        try {
                            settings.writePreferredActivitiesLPr(serializer, userId, true);
                            settings.writePersistentPreferredActivitiesLPr(serializer, userId);
                            settings.writeCrossProfileIntentFiltersLPr(serializer, userId);
                            settings.writeDefaultAppsLPr(serializer, userId);
                            settings.writeBlockUninstallPackagesLPr(serializer, userId);
                            serializer.endTag(null, TAG_PACKAGE_RESTRICTIONS);
                            serializer.endDocument();
                            str4.flush();
                            FileUtils.sync(fstr3);
                            str4.close();
                            backupFile3.delete();
                            FileUtils.setPermissions(userPackagesStateFile.toString(), 432, -1, -1);
                            EventLogTags.writeCommitSysConfigFile("package-user-" + userId, SystemClock.uptimeMillis() - startTime);
                        } catch (IOException e3) {
                            e = e3;
                            String str52 = str;
                            Slog.wtf(str52, "Unable to write package manager user packages state,  current changes will be lost at reboot", e);
                            if (!userPackagesStateFile.exists() && !userPackagesStateFile.delete()) {
                                Log.i(str52, "Failed to clean up mangled file: " + settings.mStoppedPackagesFilename);
                            }
                        }
                    } catch (IOException e4) {
                        e = e4;
                    }
                } catch (IOException e5) {
                    e = e5;
                    userPackagesStateFile = userPackagesStateFile2;
                }
            } catch (IOException e6) {
                e = e6;
                userPackagesStateFile = userPackagesStateFile2;
            }
        } catch (IOException e7) {
            e = e7;
            userPackagesStateFile = userPackagesStateFile2;
            str = "PackageManager";
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
                            if (bp != null) {
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
                                        permissionsState.updatePermissionFlags(bp, -1, 64511, flags);
                                    }
                                } else if (permissionsState.revokeInstallPermission(bp) == -1) {
                                    Slog.w("PackageManager", "Permission already added: " + name);
                                    XmlUtils.skipCurrentTag(parser);
                                } else {
                                    permissionsState.updatePermissionFlags(bp, -1, 64511, flags);
                                }
                            } else {
                                Slog.w("PackageManager", "Unknown permission: " + name);
                                XmlUtils.skipCurrentTag(parser);
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
        int i = 4;
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
            }
            if (type2 != 3 && type2 != i) {
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
            i = 4;
        }
        str.close();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeLPr() {
        String str;
        long startTime = SystemClock.uptimeMillis();
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
            BufferedOutputStream str2 = new BufferedOutputStream(fstr);
            XmlSerializer serializer = new FastXmlSerializer();
            str = "PackageManager";
            try {
                serializer.setOutput(str2, StandardCharsets.UTF_8.name());
                try {
                    serializer.startDocument(null, true);
                    serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    serializer.startTag(null, "packages");
                    int i = 0;
                    while (i < this.mVersion.size()) {
                        String volumeUuid = this.mVersion.keyAt(i);
                        VersionInfo ver = this.mVersion.valueAt(i);
                        serializer.startTag(null, "version");
                        XmlUtils.writeStringAttribute(serializer, ATTR_VOLUME_UUID, volumeUuid);
                        XmlUtils.writeIntAttribute(serializer, ATTR_SDK_VERSION, ver.sdkVersion);
                        XmlUtils.writeIntAttribute(serializer, ATTR_DATABASE_VERSION, ver.databaseVersion);
                        XmlUtils.writeStringAttribute(serializer, ATTR_FINGERPRINT, ver.fingerprint);
                        serializer.endTag(null, "version");
                        i++;
                        fstr = fstr;
                    }
                    FileOutputStream fstr2 = fstr;
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
                        serializer.startTag(null, "restored-ivi");
                        for (int i2 = 0; i2 < numIVIs; i2++) {
                            IntentFilterVerificationInfo ivi = this.mRestoredIntentFilterVerifications.valueAt(i2);
                            writeDomainVerificationsLPr(serializer, ivi);
                        }
                        serializer.endTag(null, "restored-ivi");
                    }
                    this.mKeySetManagerService.writeKeySetManagerServiceLPr(serializer);
                    serializer.endTag(null, "packages");
                    serializer.endDocument();
                    str2.flush();
                    FileUtils.sync(fstr2);
                    str2.close();
                    this.mBackupSettingsFilename.delete();
                    FileUtils.setPermissions(this.mSettingsFilename.toString(), 432, -1, -1);
                    writeKernelMappingLPr();
                    writePackageListLPr();
                    writeAllUsersPackageRestrictionsLPr();
                    writeAllRuntimePermissionsLPr();
                    EventLogTags.writeCommitSysConfigFile("package", SystemClock.uptimeMillis() - startTime);
                } catch (IOException e2) {
                    e = e2;
                    String str3 = str;
                    Slog.wtf(str3, "Unable to write package manager settings, current changes will be lost at reboot", e);
                    if (this.mSettingsFilename.exists() && !this.mSettingsFilename.delete()) {
                        Slog.wtf(str3, "Failed to clean up mangled file: " + this.mSettingsFilename);
                    }
                }
            } catch (IOException e3) {
                e = e3;
            }
        } catch (IOException e4) {
            e = e4;
            str = "PackageManager";
        }
    }

    private void writeKernelRemoveUserLPr(int userId) {
        File file = this.mKernelMappingFilename;
        if (file == null) {
            return;
        }
        File removeUserIdFile = new File(file, "remove_userid");
        writeIntToFile(removeUserIdFile, userId);
    }

    void writeKernelMappingLPr() {
        File file = this.mKernelMappingFilename;
        if (file == null) {
            return;
        }
        String[] known = file.list();
        ArraySet<String> knownSet = new ArraySet<>(known.length);
        for (String name : known) {
            knownSet.add(name);
        }
        for (PackageSetting ps : this.mPackages.values()) {
            knownSet.remove(ps.name);
            writeKernelMappingLPr(ps);
        }
        for (int i = 0; i < knownSet.size(); i++) {
            String name2 = knownSet.valueAt(i);
            this.mKernelMapping.remove(name2);
            new File(this.mKernelMappingFilename, name2).delete();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeKernelMappingLPr(PackageSetting ps) {
        if (this.mKernelMappingFilename == null || ps == null || ps.name == null) {
            return;
        }
        writeKernelMappingLPr(ps.name, ps.appId, ps.getNotInstalledUserIds());
    }

    void writeKernelMappingLPr(String name, int appId, int[] excludedUserIds) {
        KernelPackageState cur = this.mKernelMapping.get(name);
        boolean userIdsChanged = false;
        boolean firstTime = cur == null;
        userIdsChanged = (firstTime || !Arrays.equals(excludedUserIds, cur.excludedUserIds)) ? true : true;
        File dir = new File(this.mKernelMappingFilename, name);
        if (firstTime) {
            dir.mkdir();
            cur = new KernelPackageState();
            this.mKernelMapping.put(name, cur);
        }
        if (cur.appId != appId) {
            File appIdFile = new File(dir, "appid");
            writeIntToFile(appIdFile, appId);
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
        String filename = this.mPackageListFilename.getAbsolutePath();
        String ctx = SELinux.fileSelabelLookup(filename);
        if (ctx == null) {
            Slog.wtf(TAG, "Failed to get SELinux context for " + this.mPackageListFilename.getAbsolutePath());
        }
        if (!SELinux.setFSCreateContext(ctx)) {
            Slog.wtf(TAG, "Failed to set packages.list SELinux context");
        }
        try {
            writePackageListLPrInternal(creatingUserId);
        } finally {
            SELinux.setFSCreateContext((String) null);
        }
    }

    private void writePackageListLPrInternal(int creatingUserId) {
        List<UserInfo> users;
        String str;
        String str2 = " ";
        List<UserInfo> users2 = getUsers(UserManagerService.getInstance(), true);
        int[] userIds = new int[users2.size()];
        for (int i = 0; i < userIds.length; i++) {
            userIds[i] = users2.get(i).id;
        }
        if (creatingUserId != -1) {
            userIds = ArrayUtils.appendInt(userIds, creatingUserId);
        }
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
                try {
                    if (pkg.pkg == null || pkg.pkg.applicationInfo == null) {
                        users = users2;
                        str = str2;
                    } else if (pkg.pkg.applicationInfo.dataDir == null) {
                        users = users2;
                        str = str2;
                    } else {
                        ApplicationInfo ai = pkg.pkg.applicationInfo;
                        String dataPath = ai.dataDir;
                        boolean isDebug = (ai.flags & 2) != 0;
                        int[] gids = pkg.getPermissionsState().computeGids(userIds);
                        List<UserInfo> users3 = users2;
                        if (dataPath.indexOf(32) >= 0) {
                            users2 = users3;
                        } else {
                            sb.setLength(0);
                            sb.append(ai.packageName);
                            sb.append(str2);
                            sb.append(ai.uid);
                            sb.append(isDebug ? " 1 " : " 0 ");
                            sb.append(dataPath);
                            sb.append(str2);
                            sb.append(ai.seInfo);
                            sb.append(str2);
                            if (gids != null && gids.length > 0) {
                                sb.append(gids[0]);
                                int i2 = 1;
                                while (true) {
                                    boolean isDebug2 = isDebug;
                                    if (i2 >= gids.length) {
                                        break;
                                    }
                                    sb.append(",");
                                    sb.append(gids[i2]);
                                    i2++;
                                    isDebug = isDebug2;
                                }
                            } else {
                                sb.append("none");
                            }
                            sb.append(str2);
                            sb.append(ai.isProfileableByShell() ? "1" : "0");
                            sb.append(str2);
                            sb.append(String.valueOf(ai.longVersionCode));
                            sb.append("\n");
                            writer.append((CharSequence) sb);
                            str2 = str2;
                            users2 = users3;
                        }
                    }
                    if (PackageManagerService.PLATFORM_PACKAGE_NAME.equals(pkg.name)) {
                        str2 = str;
                        users2 = users;
                    } else {
                        Slog.w(TAG, "Skipping " + pkg + " due to missing metadata");
                        str2 = str;
                        users2 = users;
                    }
                } catch (Exception e) {
                    e = e;
                    Slog.wtf(TAG, "Failed to write packages.list", e);
                    IoUtils.closeQuietly(writer);
                    journal.rollback();
                    return;
                }
            }
            writer.flush();
            FileUtils.sync(fstr);
            writer.close();
            journal.commit();
        } catch (Exception e2) {
            e = e2;
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
    public boolean readLPw(List<UserInfo> users) {
        int type;
        FileInputStream str = null;
        int i = 4;
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
        int i2 = 1;
        int i3 = 0;
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
            int type2 = parser.next();
            if (type2 == i2 || (type2 == 3 && parser.getDepth() <= outerDepth)) {
                break;
            }
            if (type2 != 3 && type2 != i) {
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
                        readPreferredActivitiesLPw(parser, i3);
                    } else if (tagName.equals(TAG_PERSISTENT_PREFERRED_ACTIVITIES)) {
                        readPersistentPreferredActivitiesLPw(parser, i3);
                    } else if (tagName.equals(TAG_CROSS_PROFILE_INTENT_FILTERS)) {
                        readCrossProfileIntentFiltersLPw(parser, i3);
                    } else if (tagName.equals(TAG_DEFAULT_BROWSER)) {
                        readDefaultAppsLPw(parser, i3);
                    } else if (tagName.equals("updated-package")) {
                        readDisabledSysPackageLPw(parser);
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
                        internal.sdkVersion = XmlUtils.readIntAttribute(parser, "internal", i3);
                        external.sdkVersion = XmlUtils.readIntAttribute(parser, "external", i3);
                        String readStringAttribute = XmlUtils.readStringAttribute(parser, ATTR_FINGERPRINT);
                        external.fingerprint = readStringAttribute;
                        internal.fingerprint = readStringAttribute;
                    } else if (tagName.equals("database-version")) {
                        VersionInfo internal2 = findOrCreateVersion(StorageManager.UUID_PRIVATE_INTERNAL);
                        VersionInfo external2 = findOrCreateVersion("primary_physical");
                        internal2.databaseVersion = XmlUtils.readIntAttribute(parser, "internal", i3);
                        external2.databaseVersion = XmlUtils.readIntAttribute(parser, "external", i3);
                    } else if (tagName.equals("verifier")) {
                        String deviceIdentity = parser.getAttributeValue(null, "device");
                        try {
                            this.mVerifierDeviceIdentity = VerifierDeviceIdentity.parse(deviceIdentity);
                        } catch (IllegalArgumentException e4) {
                            Slog.w("PackageManager", "Discard invalid verifier device id: " + e4.getMessage());
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
            i = 4;
            i2 = 1;
            i3 = 0;
        }
        str.close();
        int N = this.mPendingPackages.size();
        for (int i4 = 0; i4 < N; i4++) {
            PackageSetting p = this.mPendingPackages.get(i4);
            int sharedUserId = p.getSharedUserId();
            Object idObj = getSettingLPr(sharedUserId);
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
            Object id = getSettingLPr(disabledPs.appId);
            if (id != null && (id instanceof SharedUserSetting)) {
                disabledPs.sharedUser = (SharedUserSetting) id;
            }
        }
        this.mReadMessages.append("Read completed successfully: " + this.mPackages.size() + " packages, " + this.mSharedUsers.size() + " shared uids\n");
        writeKernelMappingLPr();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void readPermissionStateForUserSyncLPr(int userId) {
        this.mRuntimePermissionsPersistence.readStateForUserSyncLPr(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyDefaultPreferredAppsLPw(int userId) {
        Throwable th;
        PackageManagerInternal pmInternal;
        int type;
        PackageManagerInternal pmInternal2 = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        for (PackageSetting ps : this.mPackages.values()) {
            if ((1 & ps.pkgFlags) != 0 && ps.pkg != null && ps.pkg.preferredActivityFilters != null) {
                ArrayList<PackageParser.ActivityIntentInfo> intents = ps.pkg.preferredActivityFilters;
                for (int i = 0; i < intents.size(); i++) {
                    PackageParser.ActivityIntentInfo aii = intents.get(i);
                    applyDefaultPreferredActivityLPw(pmInternal2, aii, new ComponentName(ps.name, aii.activity.className), userId);
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
            int i2 = 0;
            while (i2 < length) {
                File f = listFiles[i2];
                if (!f.getPath().endsWith(".xml")) {
                    Slog.i(TAG, "Non-xml file " + f + " in " + preferredDir + " directory, ignoring");
                    pmInternal = pmInternal2;
                } else if (f.canRead()) {
                    InputStream str = null;
                    try {
                        str = new BufferedInputStream(new FileInputStream(f));
                        XmlPullParser parser = Xml.newPullParser();
                        parser.setInput(str, null);
                        while (true) {
                            int type2 = parser.next();
                            if (type2 == 2) {
                                type = type2;
                                pmInternal = pmInternal2;
                                break;
                            }
                            type = type2;
                            pmInternal = pmInternal2;
                            if (type == 1) {
                                break;
                            }
                            pmInternal2 = pmInternal;
                        }
                        if (type != 2) {
                            try {
                                try {
                                    Slog.w(TAG, "Preferred apps file " + f + " does not have start tag");
                                    try {
                                        str.close();
                                    } catch (IOException e) {
                                    }
                                } catch (IOException e2) {
                                    e = e2;
                                    Slog.w(TAG, "Error reading apps file " + f, e);
                                    if (str != null) {
                                        str.close();
                                    }
                                    i2++;
                                    pmInternal2 = pmInternal;
                                } catch (XmlPullParserException e3) {
                                    e = e3;
                                    Slog.w(TAG, "Error reading apps file " + f, e);
                                    if (str != null) {
                                        str.close();
                                    }
                                    i2++;
                                    pmInternal2 = pmInternal;
                                }
                            } catch (Throwable th2) {
                                th = th2;
                                if (str != null) {
                                    try {
                                        str.close();
                                    } catch (IOException e4) {
                                    }
                                }
                                throw th;
                            }
                        } else if ("preferred-activities".equals(parser.getName())) {
                            readDefaultPreferredActivitiesLPw(parser, userId);
                            try {
                                str.close();
                            } catch (IOException e5) {
                            }
                        } else {
                            Slog.w(TAG, "Preferred apps file " + f + " does not start with 'preferred-activities'");
                            str.close();
                        }
                    } catch (IOException e6) {
                        e = e6;
                        pmInternal = pmInternal2;
                    } catch (XmlPullParserException e7) {
                        e = e7;
                        pmInternal = pmInternal2;
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } else {
                    Slog.w(TAG, "Preferred apps file " + f + " cannot be read");
                    pmInternal = pmInternal2;
                }
                i2++;
                pmInternal2 = pmInternal;
            }
        }
    }

    /* JADX WARN: Incorrect condition in loop: B:4:0x0017 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void applyDefaultPreferredActivityLPw(android.content.pm.PackageManagerInternal r31, android.content.IntentFilter r32, android.content.ComponentName r33, int r34) {
        /*
            Method dump skipped, instructions count: 587
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.applyDefaultPreferredActivityLPw(android.content.pm.PackageManagerInternal, android.content.IntentFilter, android.content.ComponentName, int):void");
    }

    private void applyDefaultPreferredActivityLPw(PackageManagerInternal pmInternal, Intent intent, int flags, ComponentName cn, String scheme, PatternMatcher ssp, IntentFilter.AuthorityEntry auth, PatternMatcher path, int userId) {
        int systemMatch;
        List<ResolveInfo> ri = pmInternal.queryIntentActivities(intent, flags, Binder.getCallingUid(), 0);
        int numMatches = ri != null ? ri.size() : 0;
        if (numMatches <= 1) {
            Slog.w(TAG, "No potential matches found for " + intent + " while setting preferred " + cn.flattenToShortString());
            return;
        }
        boolean haveAct = false;
        ComponentName haveNonSys = null;
        ComponentName[] set = new ComponentName[ri.size()];
        int i = 0;
        int systemMatch2 = 0;
        while (true) {
            if (i >= numMatches) {
                break;
            }
            ActivityInfo ai = ri.get(i).activityInfo;
            int numMatches2 = numMatches;
            set[i] = new ComponentName(ai.packageName, ai.name);
            if ((ai.applicationInfo.flags & 1) == 0) {
                if (ri.get(i).match >= 0) {
                    haveNonSys = set[i];
                    break;
                }
            } else if (cn.getPackageName().equals(ai.packageName) && cn.getClassName().equals(ai.name)) {
                haveAct = true;
                systemMatch2 = ri.get(i).match;
            }
            i++;
            numMatches = numMatches2;
        }
        if (haveNonSys != null) {
            systemMatch = systemMatch2;
            if (0 < systemMatch) {
                haveNonSys = null;
            }
        } else {
            systemMatch = systemMatch2;
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
            if ((65536 & flags) != 0) {
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
                } catch (IntentFilter.MalformedMimeTypeException e) {
                    Slog.w(TAG, "Malformed mimetype " + intent.getType() + " for " + cn);
                }
            }
            PreferredActivity pa = new PreferredActivity(filter, systemMatch, set, cn, true);
            editPreferredActivitiesLPw(userId).addFilter(pa);
        } else if (haveNonSys == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("No component ");
            sb.append(cn.flattenToShortString());
            sb.append(" found setting preferred ");
            sb.append(intent);
            sb.append("; possible matches are ");
            for (int i2 = 0; i2 < set.length; i2++) {
                if (i2 > 0) {
                    sb.append(", ");
                }
                sb.append(set[i2].flattenToShortString());
            }
            Slog.w(TAG, sb.toString());
        } else {
            Slog.i(TAG, "Not setting preferred " + intent + "; found third party match " + haveNonSys.flattenToShortString());
        }
    }

    private void readDefaultPreferredActivitiesLPw(XmlPullParser parser, int userId) throws XmlPullParserException, IOException {
        PackageManagerInternal pmInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
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
                                applyDefaultPreferredActivityLPw(pmInternal, tmpPa, tmpPa.mPref.mComponent, userId);
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

    /* JADX WARN: Removed duplicated region for block: B:18:0x007b  */
    /* JADX WARN: Removed duplicated region for block: B:19:0x0080  */
    /* JADX WARN: Removed duplicated region for block: B:26:0x00c9  */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0106  */
    /* JADX WARN: Removed duplicated region for block: B:44:0x010b  */
    /* JADX WARN: Removed duplicated region for block: B:47:0x0112  */
    /* JADX WARN: Removed duplicated region for block: B:77:0x00bf A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:83:0x00e3 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:85:0x00f4 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void readDisabledSysPackageLPw(org.xmlpull.v1.XmlPullParser r39) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        /*
            Method dump skipped, instructions count: 424
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.readDisabledSysPackageLPw(org.xmlpull.v1.XmlPullParser):void");
    }

    /* JADX WARN: Can't wrap try/catch for region: R(19:1|(13:2|3|4|5|6|7|8|9|10|11|12|13|(10:14|15|16|17|18|19|20|21|22|(32:23|24|25|26|27|28|29|30|31|32|33|34|35|36|(1:463)(1:39)|40|41|42|(3:456|457|458)|44|45|(3:451|452|453)|47|48|49|(7:435|436|437|438|439|(3:442|443|444)|441)(3:51|52|(11:419|420|421|422|(1:424)|425|(1:427)|428|(1:430)|431|432)(3:54|55|(4:410|411|(1:413)(1:415)|414)(1:57)))|58|59|(4:61|62|63|64)(5:396|397|398|(3:401|402|403)|400)|65|66|67)))|(19:69|70|71|72|73|74|75|(14:77|78|79|80|(2:380|381)(1:82)|83|(1:85)(1:379)|86|(1:88)(1:375)|(3:369|370|371)(1:90)|(9:351|352|353|354|355|356|357|358|359)(2:92|(9:333|334|335|336|337|338|339|340|341)(4:94|(7:295|296|297|298|299|(4:301|302|303|304)(9:309|310|311|312|313|314|315|316|317)|305)(2:96|(3:(14:234|235|236|237|238|239|240|241|242|243|244|245|246|247)(16:99|100|101|102|103|104|105|106|107|108|109|110|111|112|113|114)|220|(5:118|(5:194|195|196|197|198)(1:120)|(1:122)(1:193)|123|(2:124|(2:126|(2:(3:139|140|(3:187|188|189)(5:142|143|(1:145)(2:148|(1:150)(2:151|(1:153)(2:154|(1:156)(3:157|(3:159|(1:161)(1:164)|162)(2:165|(2:167|(1:169)(2:170|(3:172|(1:174)(1:176)|175)(2:177|(1:179)(2:180|(3:182|(1:184)|185)(1:186))))))|163))))|146|147))(3:135|136|137)|138)(3:130|131|132))(3:190|191|192)))(2:214|215))(15:263|264|265|266|267|268|269|270|271|272|273|274|275|276|277))|116|(0)(0)))|115|116|(0)(0))|384|(0)(0)|83|(0)(0)|86|(0)(0)|(0)(0)|(0)(0)|115|116|(0)(0))|390|73|74|75|(0)|384|(0)(0)|83|(0)(0)|86|(0)(0)|(0)(0)|(0)(0)|115|116|(0)(0)) */
    /* JADX WARN: Code restructure failed: missing block: B:261:0x0726, code lost:
        r14 = "Error in package manager settings: package ";
        r74 = com.android.server.pm.Settings.ATTR_NAME;
        r71 = "true";
        r7 = 5;
        r2 = " has bad userId ";
        r1 = r1;
        r3 = r3;
        r10 = " at ";
        r16 = r0;
        r6 = r6;
        r4 = r4;
     */
    /* JADX WARN: Removed duplicated region for block: B:111:0x020c  */
    /* JADX WARN: Removed duplicated region for block: B:120:0x0233  */
    /* JADX WARN: Removed duplicated region for block: B:123:0x0237 A[Catch: NumberFormatException -> 0x0221, TRY_LEAVE, TryCatch #18 {NumberFormatException -> 0x0221, blocks: (B:117:0x021c, B:123:0x0237), top: B:414:0x021c }] */
    /* JADX WARN: Removed duplicated region for block: B:125:0x023c  */
    /* JADX WARN: Removed duplicated region for block: B:128:0x0241  */
    /* JADX WARN: Removed duplicated region for block: B:129:0x0244  */
    /* JADX WARN: Removed duplicated region for block: B:135:0x0265  */
    /* JADX WARN: Removed duplicated region for block: B:149:0x02da  */
    /* JADX WARN: Removed duplicated region for block: B:294:0x09b3  */
    /* JADX WARN: Removed duplicated region for block: B:316:0x0a48  */
    /* JADX WARN: Removed duplicated region for block: B:317:0x0a50  */
    /* JADX WARN: Removed duplicated region for block: B:321:0x0a5e  */
    /* JADX WARN: Removed duplicated region for block: B:377:0x0c67  */
    /* JADX WARN: Removed duplicated region for block: B:381:0x0269 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:397:0x0248 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:414:0x021c A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:496:0x0c5e A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void readPackageLPw(org.xmlpull.v1.XmlPullParser r79) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        /*
            Method dump skipped, instructions count: 3193
            To view this dump change 'Code comments level' option to 'DEBUG'
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
    /* JADX WARN: Removed duplicated region for block: B:25:0x005e A[Catch: all -> 0x00cd, TryCatch #3 {all -> 0x00cd, blocks: (B:4:0x0007, B:28:0x0080, B:16:0x0046, B:18:0x004c, B:23:0x0059, B:25:0x005e, B:26:0x0061, B:32:0x0089, B:58:0x00ce), top: B:66:0x0007 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void createNewUserLI(com.android.server.pm.PackageManagerService r21, com.android.server.pm.Installer r22, int r23, java.lang.String[] r24) {
        /*
            Method dump skipped, instructions count: 208
            To view this dump change 'Code comments level' option to 'DEBUG'
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

    private int acquireAndRegisterNewAppIdLPw(SettingBase obj) {
        int size = this.mAppIds.size();
        if (size == 0) {
            this.mAppIds.add(null);
            size = 1;
        }
        if ((obj instanceof SharedUserSetting) && ((SharedUserSetting) obj).name.equals("android.uid.vmap")) {
            this.mAppIds.set(0, obj);
            return 10000;
        }
        for (int i = mFirstAvailableUid - 10000; i < size; i++) {
            if (this.mAppIds.get(i) == null) {
                this.mAppIds.set(i, obj);
                return i + 10000;
            }
        }
        if (size > 9999) {
            return -1;
        }
        this.mAppIds.add(obj);
        return size + 10000;
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

    public PackageSetting getDisabledSystemPkgLPr(PackageSetting enabledPackageSetting) {
        if (enabledPackageSetting == null) {
            return null;
        }
        return getDisabledSystemPkgLPr(enabledPackageSetting.name);
    }

    public PackageSetting[] getChildSettingsLPr(PackageSetting parentPackageSetting) {
        if (parentPackageSetting == null || !parentPackageSetting.hasChildPackages()) {
            return null;
        }
        int childCount = parentPackageSetting.childPackageNames.size();
        PackageSetting[] children = new PackageSetting[childCount];
        for (int i = 0; i < childCount; i++) {
            children[i] = this.mPackages.get(parentPackageSetting.childPackageNames.get(i));
        }
        return children;
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
        return getUsers(userManager, false);
    }

    private static List<UserInfo> getUsers(UserManagerService userManager, boolean excludeDying) {
        long id = Binder.clearCallingIdentity();
        try {
            List<UserInfo> users = userManager.getUsers(excludeDying);
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

    void dumpPackageLPr(PrintWriter pw, String prefix, String checkinTag, ArraySet<String> permissionNames, PackageSetting ps, SimpleDateFormat sdf, Date date, List<UserInfo> users, boolean dumpAll, boolean dumpAllComponents) {
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
                    for (int i = 0; i < ps.pkg.splitNames.length; i++) {
                        pw.print(checkinTag);
                        pw.print("-");
                        pw.print("splt,");
                        pw.print(ps.pkg.splitNames[i]);
                        pw.print(",");
                        pw.println(ps.pkg.splitRevisionCodes[i]);
                    }
                }
            }
            for (UserInfo user : users) {
                pw.print(checkinTag);
                pw.print("-");
                pw.print("usr");
                pw.print(",");
                pw.print(user.id);
                pw.print(",");
                pw.print(ps.getInstalled(user.id) ? "I" : "i");
                pw.print(ps.getHidden(user.id) ? "B" : "b");
                pw.print(ps.getSuspended(user.id) ? "SU" : "su");
                pw.print(ps.getStopped(user.id) ? "S" : "s");
                pw.print(ps.getNotLaunched(user.id) ? "l" : "L");
                pw.print(ps.getInstantApp(user.id) ? "IA" : "ia");
                pw.print(ps.getVirtulalPreload(user.id) ? "VPI" : "vpi");
                pw.print(ps.getHarmfulAppWarning(user.id) != null ? "HA" : "ha");
                pw.print(",");
                pw.print(ps.getEnabled(user.id));
                String lastDisabledAppCaller = ps.getLastDisabledAppCaller(user.id);
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
                for (int i2 = 0; i2 < childCount; i2++) {
                    PackageParser.Package childPkg = (PackageParser.Package) ps.pkg.childPackages.get(i2);
                    PackageSetting cps = this.mPackages.get(childPkg.packageName);
                    if (cps == null || !cps.codePathString.equals(childPkg.codePath)) {
                        cps = this.mDisabledSysPackages.get(childPkg.packageName);
                    }
                    if (cps != null) {
                        if (i2 > 0) {
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
                for (int i3 = 0; i3 < ps.pkg.libraryNames.size(); i3++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println((String) ps.pkg.libraryNames.get(i3));
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
                for (int i4 = 0; i4 < ps.pkg.usesLibraries.size(); i4++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println((String) ps.pkg.usesLibraries.get(i4));
                }
            }
            if (ps.pkg.usesStaticLibraries != null && ps.pkg.usesStaticLibraries.size() > 0) {
                pw.print(prefix);
                pw.println("  usesStaticLibraries:");
                for (int i5 = 0; i5 < ps.pkg.usesStaticLibraries.size(); i5++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.print((String) ps.pkg.usesStaticLibraries.get(i5));
                    pw.print(" version:");
                    pw.println(ps.pkg.usesStaticLibrariesVersions[i5]);
                }
            }
            if (ps.pkg.usesOptionalLibraries != null && ps.pkg.usesOptionalLibraries.size() > 0) {
                pw.print(prefix);
                pw.println("  usesOptionalLibraries:");
                for (int i6 = 0; i6 < ps.pkg.usesOptionalLibraries.size(); i6++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println((String) ps.pkg.usesOptionalLibraries.get(i6));
                }
            }
            if (ps.pkg.usesLibraryFiles != null && ps.pkg.usesLibraryFiles.length > 0) {
                pw.print(prefix);
                pw.println("  usesLibraryFiles:");
                for (int i7 = 0; i7 < ps.pkg.usesLibraryFiles.length; i7++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println(ps.pkg.usesLibraryFiles[i7]);
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
            for (int i8 = 0; i8 < perms.size(); i8++) {
                PackageParser.Permission perm = perms.get(i8);
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
            for (int i9 = 0; i9 < perms2.size(); i9++) {
                String perm2 = perms2.get(i9);
                if (permissionNames == null || permissionNames.contains(perm2)) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.print(perm2);
                    BasePermission bp = this.mPermissions.getPermission(perm2);
                    if (bp != null && bp.isHardOrSoftRestricted()) {
                        pw.println(": restricted=true");
                    } else {
                        pw.println();
                    }
                }
            }
        }
        if (ps.sharedUser == null || permissionNames != null || dumpAll) {
            dumpInstallPermissionsLPr(pw, prefix + "  ", permissionNames, ps.getPermissionsState());
        }
        if (dumpAllComponents) {
            dumpComponents(pw, prefix + "  ", ps);
        }
        for (UserInfo user2 : users) {
            pw.print(prefix);
            pw.print("  User ");
            pw.print(user2.id);
            pw.print(": ");
            pw.print("ceDataInode=");
            pw.print(ps.getCeDataInode(user2.id));
            pw.print(" installed=");
            pw.print(ps.getInstalled(user2.id));
            pw.print(" hidden=");
            pw.print(ps.getHidden(user2.id));
            pw.print(" suspended=");
            pw.print(ps.getSuspended(user2.id));
            if (ps.getSuspended(user2.id)) {
                PackageUserState pus = ps.readUserState(user2.id);
                pw.print(" suspendingPackage=");
                pw.print(pus.suspendingPackage);
                pw.print(" dialogInfo=");
                pw.print(pus.dialogInfo);
            }
            pw.print(" stopped=");
            pw.print(ps.getStopped(user2.id));
            pw.print(" notLaunched=");
            pw.print(ps.getNotLaunched(user2.id));
            pw.print(" enabled=");
            pw.print(ps.getEnabled(user2.id));
            pw.print(" instant=");
            pw.print(ps.getInstantApp(user2.id));
            pw.print(" virtual=");
            pw.println(ps.getVirtulalPreload(user2.id));
            String[] overlayPaths = ps.getOverlayPaths(user2.id);
            if (overlayPaths != null && overlayPaths.length > 0) {
                pw.print(prefix);
                pw.println("  overlay paths:");
                for (String path : overlayPaths) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.println(path);
                }
            }
            String lastDisabledAppCaller2 = ps.getLastDisabledAppCaller(user2.id);
            if (lastDisabledAppCaller2 != null) {
                pw.print(prefix);
                pw.print("    lastDisabledCaller: ");
                pw.println(lastDisabledAppCaller2);
            }
            if (ps.sharedUser == null) {
                PermissionsState permissionsState = ps.getPermissionsState();
                dumpGidsLPr(pw, prefix + "    ", permissionsState.computeGids(user2.id));
                dumpRuntimePermissionsLPr(pw, prefix + "    ", permissionNames, permissionsState.getRuntimePermissionStates(user2.id), dumpAll);
            }
            String harmfulAppWarning = ps.getHarmfulAppWarning(user2.id);
            if (harmfulAppWarning != null) {
                pw.print(prefix);
                pw.print("      harmfulAppWarning: ");
                pw.println(harmfulAppWarning);
            }
            if (permissionNames == null) {
                ArraySet<String> cmp = ps.getDisabledComponents(user2.id);
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
                ArraySet<String> cmp2 = ps.getEnabledComponents(user2.id);
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
    /* JADX WARN: Code restructure failed: missing block: B:14:0x005d, code lost:
        if (r10.getPermissionsState().hasRequestedPermission(r24) != false) goto L14;
     */
    /* JADX WARN: Code restructure failed: missing block: B:46:0x00dc, code lost:
        if (r0 != false) goto L58;
     */
    /* JADX WARN: Code restructure failed: missing block: B:48:0x00e2, code lost:
        if (r25.onTitlePrinted() == false) goto L57;
     */
    /* JADX WARN: Code restructure failed: missing block: B:49:0x00e4, code lost:
        r22.println();
     */
    /* JADX WARN: Code restructure failed: missing block: B:50:0x00e7, code lost:
        r14.println("Renamed packages:");
        r0 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:51:0x00ed, code lost:
        r14.print("  ");
     */
    /* JADX WARN: Code restructure failed: missing block: B:72:0x014c, code lost:
        if (r0 != false) goto L90;
     */
    /* JADX WARN: Code restructure failed: missing block: B:74:0x0152, code lost:
        if (r25.onTitlePrinted() == false) goto L89;
     */
    /* JADX WARN: Code restructure failed: missing block: B:75:0x0154, code lost:
        r22.println();
     */
    /* JADX WARN: Code restructure failed: missing block: B:76:0x0157, code lost:
        r14.println("Hidden system packages:");
        r0 = true;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void dumpPackagesLPr(java.io.PrintWriter r22, java.lang.String r23, android.util.ArraySet<java.lang.String> r24, com.android.server.pm.DumpState r25, boolean r26) {
        /*
            Method dump skipped, instructions count: 395
            To view this dump change 'Code comments level' option to 'DEBUG'
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
        boolean printedSomething;
        int i;
        int i2;
        int[] iArr;
        PermissionsState permissionsState;
        boolean printedSomething2 = false;
        for (SharedUserSetting su : this.mSharedUsers.values()) {
            if (packageName == null || su == dumpState.getSharedUser()) {
                if (permissionNames == null || su.getPermissionsState().hasRequestedPermission(permissionNames)) {
                    if (checkin) {
                        pw.print("suid,");
                        pw.print(su.userId);
                        pw.print(",");
                        pw.println(su.name);
                    } else {
                        if (printedSomething2) {
                            printedSomething = printedSomething2;
                        } else {
                            if (dumpState.onTitlePrinted()) {
                                pw.println();
                            }
                            pw.println("Shared users:");
                            printedSomething = true;
                        }
                        pw.print("  SharedUser [");
                        pw.print(su.name);
                        pw.print("] (");
                        pw.print(Integer.toHexString(System.identityHashCode(su)));
                        pw.println("):");
                        pw.print("    ");
                        pw.print("userId=");
                        pw.println(su.userId);
                        pw.print("    ");
                        pw.println("Packages");
                        int numPackages = su.packages.size();
                        for (int i3 = 0; i3 < numPackages; i3++) {
                            PackageSetting ps = su.packages.valueAt(i3);
                            if (ps != null) {
                                pw.print("      ");
                                pw.println(ps.toString());
                            } else {
                                pw.print("      ");
                                pw.println("NULL?!");
                            }
                        }
                        if (dumpState.isOptionEnabled(4)) {
                            printedSomething2 = printedSomething;
                        } else {
                            PermissionsState permissionsState2 = su.getPermissionsState();
                            dumpInstallPermissionsLPr(pw, "    ", permissionNames, permissionsState2);
                            int[] userIds = UserManagerService.getInstance().getUserIds();
                            int length = userIds.length;
                            int i4 = 0;
                            while (i4 < length) {
                                int userId = userIds[i4];
                                int[] gids = permissionsState2.computeGids(userId);
                                List<PermissionsState.PermissionState> permissions = permissionsState2.getRuntimePermissionStates(userId);
                                if (ArrayUtils.isEmpty(gids) && permissions.isEmpty()) {
                                    i = i4;
                                    i2 = length;
                                    iArr = userIds;
                                    permissionsState = permissionsState2;
                                } else {
                                    pw.print("    ");
                                    i = i4;
                                    pw.print("User ");
                                    pw.print(userId);
                                    pw.println(": ");
                                    dumpGidsLPr(pw, "      ", gids);
                                    i2 = length;
                                    iArr = userIds;
                                    permissionsState = permissionsState2;
                                    dumpRuntimePermissionsLPr(pw, "      ", permissionNames, permissions, packageName != null);
                                }
                                i4 = i + 1;
                                length = i2;
                                userIds = iArr;
                                permissionsState2 = permissionsState;
                            }
                            printedSomething2 = printedSomething;
                        }
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
            if (flags != 0) {
                flagsString.append('|');
            }
        }
        if (flagsString != null) {
            flagsString.append(']');
            return flagsString.toString();
        }
        return "";
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

    void dumpComponents(PrintWriter pw, String prefix, PackageSetting ps) {
        dumpComponents(pw, prefix, ps, "activities:", ps.pkg.activities);
        dumpComponents(pw, prefix, ps, "services:", ps.pkg.services);
        dumpComponents(pw, prefix, ps, "receivers:", ps.pkg.receivers);
        dumpComponents(pw, prefix, ps, "providers:", ps.pkg.providers);
        dumpComponents(pw, prefix, ps, "instrumentations:", ps.pkg.instrumentation);
    }

    void dumpComponents(PrintWriter pw, String prefix, PackageSetting ps, String label, List<? extends PackageParser.Component<?>> list) {
        int size = CollectionUtils.size(list);
        if (size == 0) {
            return;
        }
        pw.print(prefix);
        pw.println(label);
        for (int i = 0; i < size; i++) {
            PackageParser.Component<?> component = list.get(i);
            pw.print(prefix);
            pw.print("  ");
            pw.println(component.getComponentName().flattenToShortString());
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
        private static final int INITIAL_VERSION = 0;
        private static final long MAX_WRITE_PERMISSIONS_DELAY_MILLIS = 2000;
        private static final int UPGRADE_VERSION = -1;
        private static final long WRITE_PERMISSIONS_DELAY_MILLIS = 200;
        private final Object mPersistenceLock;
        private final Handler mHandler = new MyHandler();
        @GuardedBy({"mLock"})
        private final SparseBooleanArray mWriteScheduled = new SparseBooleanArray();
        @GuardedBy({"mLock"})
        private final SparseLongArray mLastNotWrittenMutationTimesMillis = new SparseLongArray();
        @GuardedBy({"mLock"})
        private final SparseIntArray mVersions = new SparseIntArray();
        @GuardedBy({"mLock"})
        private final SparseArray<String> mFingerprints = new SparseArray<>();
        @GuardedBy({"mLock"})
        private final SparseBooleanArray mDefaultPermissionsGranted = new SparseBooleanArray();

        public RuntimePermissionPersistence(Object persistenceLock) {
            this.mPersistenceLock = persistenceLock;
        }

        @GuardedBy({"Settings.this.mLock"})
        int getVersionLPr(int userId) {
            return this.mVersions.get(userId, 0);
        }

        @GuardedBy({"Settings.this.mLock"})
        void setVersionLPr(int version, int userId) {
            this.mVersions.put(userId, version);
            writePermissionsForUserAsyncLPr(userId);
        }

        @GuardedBy({"Settings.this.mLock"})
        public boolean areDefaultRuntimePermissionsGrantedLPr(int userId) {
            return this.mDefaultPermissionsGranted.get(userId);
        }

        @GuardedBy({"Settings.this.mLock"})
        public void setRuntimePermissionsFingerPrintLPr(String fingerPrint, int userId) {
            this.mFingerprints.put(userId, fingerPrint);
            writePermissionsForUserAsyncLPr(userId);
        }

        public void writePermissionsForUserSyncLPr(int userId) {
            this.mHandler.removeMessages(userId);
            writePermissionsSync(userId);
        }

        @GuardedBy({"Settings.this.mLock"})
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

        /* JADX INFO: Access modifiers changed from: private */
        public void writePermissionsSync(int userId) {
            File userRuntimePermissionsFile = Settings.this.getUserRuntimePermissionsFile(userId);
            AtomicFile destination = new AtomicFile(userRuntimePermissionsFile, "package-perms-" + userId);
            ArrayMap<String, List<PermissionsState.PermissionState>> permissionsForPackage = new ArrayMap<>();
            ArrayMap<String, List<PermissionsState.PermissionState>> permissionsForSharedUser = new ArrayMap<>();
            synchronized (this.mPersistenceLock) {
                this.mWriteScheduled.delete(userId);
                int packageCount = Settings.this.mPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    String packageName = Settings.this.mPackages.keyAt(i);
                    PackageSetting packageSetting = Settings.this.mPackages.valueAt(i);
                    if (packageSetting.sharedUser == null) {
                        PermissionsState permissionsState = packageSetting.getPermissionsState();
                        List<PermissionsState.PermissionState> permissionsStates = permissionsState.getRuntimePermissionStates(userId);
                        if (!permissionsStates.isEmpty()) {
                            permissionsForPackage.put(packageName, permissionsStates);
                        }
                    }
                }
                int sharedUserCount = Settings.this.mSharedUsers.size();
                for (int i2 = 0; i2 < sharedUserCount; i2++) {
                    String sharedUserName = Settings.this.mSharedUsers.keyAt(i2);
                    SharedUserSetting sharedUser = Settings.this.mSharedUsers.valueAt(i2);
                    PermissionsState permissionsState2 = sharedUser.getPermissionsState();
                    List<PermissionsState.PermissionState> permissionsStates2 = permissionsState2.getRuntimePermissionStates(userId);
                    if (!permissionsStates2.isEmpty()) {
                        permissionsForSharedUser.put(sharedUserName, permissionsStates2);
                    }
                }
            }
            FileOutputStream out = null;
            try {
                out = destination.startWrite();
                XmlSerializer serializer = Xml.newSerializer();
                serializer.setOutput(out, StandardCharsets.UTF_8.name());
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                serializer.startDocument(null, true);
                serializer.startTag(null, Settings.TAG_RUNTIME_PERMISSIONS);
                int version = this.mVersions.get(userId, 0);
                serializer.attribute(null, "version", Integer.toString(version));
                String fingerprint = this.mFingerprints.get(userId);
                if (fingerprint != null) {
                    serializer.attribute(null, Settings.ATTR_FINGERPRINT, fingerprint);
                }
                int packageCount2 = permissionsForPackage.size();
                for (int i3 = 0; i3 < packageCount2; i3++) {
                    String packageName2 = permissionsForPackage.keyAt(i3);
                    List<PermissionsState.PermissionState> permissionStates = permissionsForPackage.valueAt(i3);
                    serializer.startTag(null, Settings.TAG_PACKAGE);
                    serializer.attribute(null, Settings.ATTR_NAME, packageName2);
                    writePermissions(serializer, permissionStates);
                    serializer.endTag(null, Settings.TAG_PACKAGE);
                }
                int sharedUserCount2 = permissionsForSharedUser.size();
                for (int i4 = 0; i4 < sharedUserCount2; i4++) {
                    String packageName3 = permissionsForSharedUser.keyAt(i4);
                    List<PermissionsState.PermissionState> permissionStates2 = permissionsForSharedUser.valueAt(i4);
                    serializer.startTag(null, Settings.TAG_SHARED_USER);
                    serializer.attribute(null, Settings.ATTR_NAME, packageName3);
                    writePermissions(serializer, permissionStates2);
                    serializer.endTag(null, Settings.TAG_SHARED_USER);
                }
                serializer.endTag(null, Settings.TAG_RUNTIME_PERMISSIONS);
                serializer.endDocument();
                destination.finishWrite(out);
                if (Build.FINGERPRINT.equals(fingerprint)) {
                    this.mDefaultPermissionsGranted.put(userId, true);
                }
            } finally {
                try {
                } finally {
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy({"Settings.this.mLock"})
        public void onUserRemovedLPw(int userId) {
            this.mHandler.removeMessages(userId);
            for (SettingBase sb : Settings.this.mPackages.values()) {
                revokeRuntimePermissionsAndClearFlags(sb, userId);
            }
            for (SettingBase sb2 : Settings.this.mSharedUsers.values()) {
                revokeRuntimePermissionsAndClearFlags(sb2, userId);
            }
            this.mDefaultPermissionsGranted.delete(userId);
            this.mVersions.delete(userId);
            this.mFingerprints.remove(userId);
        }

        private void revokeRuntimePermissionsAndClearFlags(SettingBase sb, int userId) {
            PermissionsState permissionsState = sb.getPermissionsState();
            for (PermissionsState.PermissionState permissionState : permissionsState.getRuntimePermissionStates(userId)) {
                BasePermission bp = Settings.this.mPermissions.getPermission(permissionState.getName());
                if (bp != null) {
                    permissionsState.revokeRuntimePermission(bp, userId);
                    permissionsState.updatePermissionFlags(bp, userId, 64511, 0);
                }
            }
        }

        public void deleteUserRuntimePermissionsFile(int userId) {
            Settings.this.getUserRuntimePermissionsFile(userId).delete();
        }

        @GuardedBy({"Settings.this.mLock"})
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

        /* JADX WARN: Removed duplicated region for block: B:51:0x00c7 A[SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:59:0x005a A[SYNTHETIC] */
        @com.android.internal.annotations.GuardedBy({"Settings.this.mLock"})
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        private void parseRuntimePermissionsLPr(org.xmlpull.v1.XmlPullParser r9, int r10) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
            /*
                r8 = this;
                int r0 = r9.getDepth()
            L4:
                int r1 = r9.next()
                r2 = r1
                r3 = 1
                if (r1 == r3) goto Lec
                r1 = 3
                if (r2 != r1) goto L15
                int r4 = r9.getDepth()
                if (r4 <= r0) goto Lec
            L15:
                if (r2 == r1) goto L4
                r1 = 4
                if (r2 != r1) goto L1b
                goto L4
            L1b:
                java.lang.String r1 = r9.getName()
                int r4 = r1.hashCode()
                r5 = 111052(0x1b1cc, float:1.55617E-40)
                r6 = 2
                r7 = -1
                if (r4 == r5) goto L4b
                r5 = 160289295(0x98dd20f, float:3.4142053E-33)
                if (r4 == r5) goto L40
                r5 = 485578803(0x1cf15833, float:1.5970841E-21)
                if (r4 == r5) goto L35
            L34:
                goto L56
            L35:
                java.lang.String r4 = "shared-user"
                boolean r1 = r1.equals(r4)
                if (r1 == 0) goto L34
                r1 = r6
                goto L57
            L40:
                java.lang.String r4 = "runtime-permissions"
                boolean r1 = r1.equals(r4)
                if (r1 == 0) goto L34
                r1 = 0
                goto L57
            L4b:
                java.lang.String r4 = "pkg"
                boolean r1 = r1.equals(r4)
                if (r1 == 0) goto L34
                r1 = r3
                goto L57
            L56:
                r1 = r7
            L57:
                r4 = 0
                if (r1 == 0) goto Lc7
                java.lang.String r5 = "PackageManager"
                java.lang.String r7 = "name"
                if (r1 == r3) goto L96
                if (r1 == r6) goto L65
                goto Lea
            L65:
                java.lang.String r1 = r9.getAttributeValue(r4, r7)
                com.android.server.pm.Settings r3 = com.android.server.pm.Settings.this
                android.util.ArrayMap<java.lang.String, com.android.server.pm.SharedUserSetting> r3 = r3.mSharedUsers
                java.lang.Object r3 = r3.get(r1)
                com.android.server.pm.SharedUserSetting r3 = (com.android.server.pm.SharedUserSetting) r3
                if (r3 != 0) goto L8e
                java.lang.StringBuilder r4 = new java.lang.StringBuilder
                r4.<init>()
                java.lang.String r6 = "Unknown shared user:"
                r4.append(r6)
                r4.append(r1)
                java.lang.String r4 = r4.toString()
                android.util.Slog.w(r5, r4)
                com.android.internal.util.XmlUtils.skipCurrentTag(r9)
                goto L4
            L8e:
                com.android.server.pm.permission.PermissionsState r4 = r3.getPermissionsState()
                r8.parsePermissionsLPr(r9, r4, r10)
                goto Lea
            L96:
                java.lang.String r1 = r9.getAttributeValue(r4, r7)
                com.android.server.pm.Settings r3 = com.android.server.pm.Settings.this
                android.util.ArrayMap<java.lang.String, com.android.server.pm.PackageSetting> r3 = r3.mPackages
                java.lang.Object r3 = r3.get(r1)
                com.android.server.pm.PackageSetting r3 = (com.android.server.pm.PackageSetting) r3
                if (r3 != 0) goto Lbf
                java.lang.StringBuilder r4 = new java.lang.StringBuilder
                r4.<init>()
                java.lang.String r6 = "Unknown package:"
                r4.append(r6)
                r4.append(r1)
                java.lang.String r4 = r4.toString()
                android.util.Slog.w(r5, r4)
                com.android.internal.util.XmlUtils.skipCurrentTag(r9)
                goto L4
            Lbf:
                com.android.server.pm.permission.PermissionsState r4 = r3.getPermissionsState()
                r8.parsePermissionsLPr(r9, r4, r10)
                goto Lea
            Lc7:
                java.lang.String r1 = "version"
                int r1 = com.android.internal.util.XmlUtils.readIntAttribute(r9, r1, r7)
                android.util.SparseIntArray r3 = r8.mVersions
                r3.put(r10, r1)
                java.lang.String r3 = "fingerprint"
                java.lang.String r3 = r9.getAttributeValue(r4, r3)
                android.util.SparseArray<java.lang.String> r4 = r8.mFingerprints
                r4.put(r10, r3)
                java.lang.String r4 = android.os.Build.FINGERPRINT
                boolean r4 = r4.equals(r3)
                android.util.SparseBooleanArray r5 = r8.mDefaultPermissionsGranted
                r5.put(r10, r4)
            Lea:
                goto L4
            Lec:
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.Settings.RuntimePermissionPersistence.parseRuntimePermissionsLPr(org.xmlpull.v1.XmlPullParser, int):void");
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
                                if (bp != null) {
                                    String grantedStr = parser.getAttributeValue(null, Settings.ATTR_GRANTED);
                                    if (grantedStr != null && !Boolean.parseBoolean(grantedStr)) {
                                        granted = false;
                                    }
                                    String flagsStr = parser.getAttributeValue(null, "flags");
                                    int flags = flagsStr != null ? Integer.parseInt(flagsStr, 16) : 0;
                                    if (granted) {
                                        permissionsState.grantRuntimePermission(bp, userId);
                                        permissionsState.updatePermissionFlags(bp, userId, 64511, flags);
                                    } else {
                                        permissionsState.updatePermissionFlags(bp, userId, 64511, flags);
                                    }
                                } else {
                                    Slog.w("PackageManager", "Unknown permission:" + name2);
                                    XmlUtils.skipCurrentTag(parser);
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
