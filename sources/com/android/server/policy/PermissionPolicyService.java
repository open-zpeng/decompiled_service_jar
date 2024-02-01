package com.android.server.policy;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManagerInternal;
import android.util.ArraySet;
import android.util.LongSparseLongArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.IntPair;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.policy.PermissionPolicyInternal;
import com.android.server.policy.PermissionPolicyService;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/* loaded from: classes.dex */
public final class PermissionPolicyService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = PermissionPolicyService.class.getSimpleName();
    @GuardedBy({"mLock"})
    private final ArraySet<Pair<String, Integer>> mIsPackageSyncsScheduled;
    @GuardedBy({"mLock"})
    private final SparseBooleanArray mIsStarted;
    private final Object mLock;
    @GuardedBy({"mLock"})
    private PermissionPolicyInternal.OnInitializedCallback mOnInitializedCallback;

    public PermissionPolicyService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mIsStarted = new SparseBooleanArray();
        this.mIsPackageSyncsScheduled = new ArraySet<>();
        LocalServices.addService(PermissionPolicyInternal.class, new Internal());
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        PermissionManagerServiceInternal permManagerInternal = (PermissionManagerServiceInternal) LocalServices.getService(PermissionManagerServiceInternal.class);
        IAppOpsService appOpsService = IAppOpsService.Stub.asInterface(ServiceManager.getService("appops"));
        packageManagerInternal.getPackageList(new PackageManagerInternal.PackageListObserver() { // from class: com.android.server.policy.PermissionPolicyService.1
            public void onPackageAdded(String packageName, int uid) {
                onPackageChanged(packageName, uid);
            }

            public void onPackageChanged(String packageName, int uid) {
                int userId = UserHandle.getUserId(uid);
                if (PermissionPolicyService.this.isStarted(userId)) {
                    PermissionPolicyService.this.synchronizePackagePermissionsAndAppOpsForUser(packageName, userId);
                }
            }

            public void onPackageRemoved(String packageName, int uid) {
            }
        });
        permManagerInternal.addOnRuntimePermissionStateChangedListener(new PermissionManagerInternal.OnRuntimePermissionStateChangedListener() { // from class: com.android.server.policy.-$$Lambda$PermissionPolicyService$V2gOjn4rTBH_rbxagOz-eOTvNfc
            public final void onRuntimePermissionStateChanged(String str, int i) {
                PermissionPolicyService.this.synchronizePackagePermissionsAndAppOpsAsyncForUser(str, i);
            }
        });
        IAppOpsCallback.Stub stub = new IAppOpsCallback.Stub() { // from class: com.android.server.policy.PermissionPolicyService.2
            public void opChanged(int op, int uid, String packageName) {
                PermissionPolicyService.this.synchronizePackagePermissionsAndAppOpsAsyncForUser(packageName, UserHandle.getUserId(uid));
            }
        };
        ArrayList<PermissionInfo> dangerousPerms = permManagerInternal.getAllPermissionWithProtectionLevel(1);
        try {
            int numDangerousPerms = dangerousPerms.size();
            for (int i = 0; i < numDangerousPerms; i++) {
                PermissionInfo perm = dangerousPerms.get(i);
                if (!perm.isHardRestricted() && perm.backgroundPermission == null) {
                    if (perm.isSoftRestricted()) {
                        appOpsService.startWatchingMode(getSwitchOp(perm.name), (String) null, stub);
                        SoftRestrictedPermissionPolicy policy = SoftRestrictedPermissionPolicy.forPermission(null, null, null, perm.name);
                        if (policy.resolveAppOp() != -1) {
                            appOpsService.startWatchingMode(policy.resolveAppOp(), (String) null, stub);
                        }
                    }
                }
                appOpsService.startWatchingMode(getSwitchOp(perm.name), (String) null, stub);
            }
        } catch (RemoteException e) {
            Slog.wtf(LOG_TAG, "Cannot set up app-ops listener");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int getSwitchOp(String permission) {
        int op = AppOpsManager.permissionToOpCode(permission);
        if (op == -1) {
            return -1;
        }
        return AppOpsManager.opToSwitch(op);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void synchronizePackagePermissionsAndAppOpsAsyncForUser(String packageName, int changedUserId) {
        if (isStarted(changedUserId)) {
            synchronized (this.mLock) {
                if (this.mIsPackageSyncsScheduled.add(new Pair<>(packageName, Integer.valueOf(changedUserId)))) {
                    FgThread.getHandler().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.policy.-$$Lambda$PermissionPolicyService$RYery4oeHNcS8uZ6BgM2MtZIvKw
                        public final void accept(Object obj, Object obj2, Object obj3) {
                            ((PermissionPolicyService) obj).synchronizePackagePermissionsAndAppOpsForUser((String) obj2, ((Integer) obj3).intValue());
                        }
                    }, this, packageName, Integer.valueOf(changedUserId)));
                }
            }
        }
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        int[] userIds;
        if (phase == 550) {
            UserManagerInternal um = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
            for (int userId : um.getUserIds()) {
                if (um.isUserRunning(userId)) {
                    onStartUser(userId);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isStarted(int userId) {
        boolean z;
        synchronized (this.mLock) {
            z = this.mIsStarted.get(userId);
        }
        return z;
    }

    @Override // com.android.server.SystemService
    public void onStartUser(int userId) {
        PermissionPolicyInternal.OnInitializedCallback callback;
        if (isStarted(userId)) {
            return;
        }
        grantOrUpgradeDefaultRuntimePermissionsIfNeeded(userId);
        synchronized (this.mLock) {
            this.mIsStarted.put(userId, true);
            callback = this.mOnInitializedCallback;
        }
        synchronizePermissionsAndAppOpsForUser(userId);
        if (callback != null) {
            callback.onInitialized(userId);
        }
    }

    @Override // com.android.server.SystemService
    public void onStopUser(int userId) {
        synchronized (this.mLock) {
            this.mIsStarted.delete(userId);
        }
    }

    private void grantOrUpgradeDefaultRuntimePermissionsIfNeeded(int userId) {
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        if (packageManagerInternal.wereDefaultPermissionsGrantedSinceBoot(userId)) {
            final CountDownLatch latch = new CountDownLatch(1);
            PermissionControllerManager permissionControllerManager = new PermissionControllerManager(getUserContext(getContext(), UserHandle.of(userId)), FgThread.getHandler());
            permissionControllerManager.grantOrUpgradeDefaultRuntimePermissions(FgThread.getExecutor(), new Consumer() { // from class: com.android.server.policy.-$$Lambda$PermissionPolicyService$8D9Zbki65ND_Q20M-Trexl6cHcQ
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    PermissionPolicyService.lambda$grantOrUpgradeDefaultRuntimePermissionsIfNeeded$0(latch, (Boolean) obj);
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
            }
            packageManagerInternal.setRuntimePermissionsFingerPrint(Build.FINGERPRINT, userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$grantOrUpgradeDefaultRuntimePermissionsIfNeeded$0(CountDownLatch latch, Boolean success) {
        if (!success.booleanValue()) {
            Slog.wtf(LOG_TAG, "Error granting/upgrading runtime permissions");
            throw new IllegalStateException("Error granting/upgrading runtime permissions");
        } else {
            latch.countDown();
        }
    }

    private static Context getUserContext(Context context, UserHandle user) {
        if (context.getUser().equals(user)) {
            return context;
        }
        try {
            return context.createPackageContextAsUser(context.getPackageName(), 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            String str = LOG_TAG;
            Slog.e(str, "Cannot create context for user " + user, e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void synchronizePackagePermissionsAndAppOpsForUser(String packageName, int userId) {
        synchronized (this.mLock) {
            this.mIsPackageSyncsScheduled.remove(new Pair(packageName, Integer.valueOf(userId)));
        }
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        PackageInfo pkg = packageManagerInternal.getPackageInfo(packageName, 0, 1000, userId);
        if (pkg == null) {
            return;
        }
        PermissionToOpSynchroniser synchroniser = new PermissionToOpSynchroniser(getUserContext(getContext(), UserHandle.of(userId)));
        synchroniser.addPackage(pkg.packageName);
        String[] sharedPkgNames = packageManagerInternal.getPackagesForSharedUserId(pkg.sharedUserId, userId);
        if (sharedPkgNames != null) {
            for (String sharedPkgName : sharedPkgNames) {
                PackageParser.Package sharedPkg = packageManagerInternal.getPackage(sharedPkgName);
                if (sharedPkg != null) {
                    synchroniser.addPackage(sharedPkg.packageName);
                }
            }
        }
        synchroniser.syncPackages();
    }

    private void synchronizePermissionsAndAppOpsForUser(int userId) {
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        final PermissionToOpSynchroniser synchronizer = new PermissionToOpSynchroniser(getUserContext(getContext(), UserHandle.of(userId)));
        packageManagerInternal.forEachPackage(new Consumer() { // from class: com.android.server.policy.-$$Lambda$PermissionPolicyService$K1QpWYLKz7rfj4y4fthPQy64Pek
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                PermissionPolicyService.PermissionToOpSynchroniser.this.addPackage(((PackageParser.Package) obj).packageName);
            }
        });
        synchronizer.syncPackages();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class PermissionToOpSynchroniser {
        private final AppOpsManager mAppOpsManager;
        private final Context mContext;
        private final PackageManager mPackageManager;
        private final SparseIntArray mAllUids = new SparseIntArray();
        private final ArrayList<OpToChange> mOpsToDefault = new ArrayList<>();
        private final ArrayList<OpToChange> mOpsToAllowIfDefault = new ArrayList<>();
        private final ArrayList<OpToChange> mOpsToAllow = new ArrayList<>();
        private final ArrayList<OpToChange> mOpsToIgnoreIfDefault = new ArrayList<>();
        private final ArrayList<OpToChange> mOpsToIgnore = new ArrayList<>();
        private final ArrayList<OpToChange> mOpsToForeground = new ArrayList<>();
        private final ArrayList<OpToChange> mOpsToForegroundIfAllow = new ArrayList<>();

        PermissionToOpSynchroniser(Context context) {
            this.mContext = context;
            this.mPackageManager = context.getPackageManager();
            this.mAppOpsManager = (AppOpsManager) context.getSystemService(AppOpsManager.class);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void syncPackages() {
            LongSparseLongArray alreadySetAppOps = new LongSparseLongArray();
            int allowCount = this.mOpsToAllow.size();
            for (int i = 0; i < allowCount; i++) {
                OpToChange op = this.mOpsToAllow.get(i);
                setUidModeAllowed(op.code, op.uid, op.packageName);
                alreadySetAppOps.put(IntPair.of(op.uid, op.code), 1L);
            }
            int allowIfDefaultCount = this.mOpsToAllowIfDefault.size();
            for (int i2 = 0; i2 < allowIfDefaultCount; i2++) {
                OpToChange op2 = this.mOpsToAllowIfDefault.get(i2);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op2.uid, op2.code)) < 0) {
                    boolean wasSet = setUidModeAllowedIfDefault(op2.code, op2.uid, op2.packageName);
                    if (wasSet) {
                        alreadySetAppOps.put(IntPair.of(op2.uid, op2.code), 1L);
                    }
                }
            }
            int foregroundIfAllowedCount = this.mOpsToForegroundIfAllow.size();
            for (int i3 = 0; i3 < foregroundIfAllowedCount; i3++) {
                OpToChange op3 = this.mOpsToForegroundIfAllow.get(i3);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op3.uid, op3.code)) < 0) {
                    boolean wasSet2 = setUidModeForegroundIfAllow(op3.code, op3.uid, op3.packageName);
                    if (wasSet2) {
                        alreadySetAppOps.put(IntPair.of(op3.uid, op3.code), 1L);
                    }
                }
            }
            int foregroundCount = this.mOpsToForeground.size();
            for (int i4 = 0; i4 < foregroundCount; i4++) {
                OpToChange op4 = this.mOpsToForeground.get(i4);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op4.uid, op4.code)) < 0) {
                    setUidModeForeground(op4.code, op4.uid, op4.packageName);
                    alreadySetAppOps.put(IntPair.of(op4.uid, op4.code), 1L);
                }
            }
            int ignoreCount = this.mOpsToIgnore.size();
            for (int i5 = 0; i5 < ignoreCount; i5++) {
                OpToChange op5 = this.mOpsToIgnore.get(i5);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op5.uid, op5.code)) < 0) {
                    setUidModeIgnored(op5.code, op5.uid, op5.packageName);
                    alreadySetAppOps.put(IntPair.of(op5.uid, op5.code), 1L);
                }
            }
            int ignoreIfDefaultCount = this.mOpsToIgnoreIfDefault.size();
            for (int i6 = 0; i6 < ignoreIfDefaultCount; i6++) {
                OpToChange op6 = this.mOpsToIgnoreIfDefault.get(i6);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op6.uid, op6.code)) < 0) {
                    boolean wasSet3 = setUidModeIgnoredIfDefault(op6.code, op6.uid, op6.packageName);
                    if (wasSet3) {
                        alreadySetAppOps.put(IntPair.of(op6.uid, op6.code), 1L);
                    }
                }
            }
            int defaultCount = this.mOpsToDefault.size();
            for (int i7 = 0; i7 < defaultCount; i7++) {
                OpToChange op7 = this.mOpsToDefault.get(i7);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op7.uid, op7.code)) < 0) {
                    setUidModeDefault(op7.code, op7.uid, op7.packageName);
                    alreadySetAppOps.put(IntPair.of(op7.uid, op7.code), 1L);
                }
            }
        }

        private void addOpIfRestricted(PermissionInfo permissionInfo, PackageInfo pkg) {
            String permission = permissionInfo.name;
            int opCode = PermissionPolicyService.getSwitchOp(permission);
            int uid = pkg.applicationInfo.uid;
            if (!permissionInfo.isRestricted()) {
                return;
            }
            boolean applyRestriction = (this.mPackageManager.getPermissionFlags(permission, pkg.packageName, this.mContext.getUser()) & 16384) != 0;
            if (permissionInfo.isHardRestricted()) {
                if (opCode != -1) {
                    if (applyRestriction) {
                        this.mOpsToDefault.add(new OpToChange(uid, pkg.packageName, opCode));
                    } else {
                        this.mOpsToAllowIfDefault.add(new OpToChange(uid, pkg.packageName, opCode));
                    }
                }
            } else if (permissionInfo.isSoftRestricted()) {
                SoftRestrictedPermissionPolicy policy = SoftRestrictedPermissionPolicy.forPermission(this.mContext, pkg.applicationInfo, this.mContext.getUser(), permission);
                if (opCode != -1) {
                    if (policy.canBeGranted()) {
                        this.mOpsToAllowIfDefault.add(new OpToChange(uid, pkg.packageName, opCode));
                    } else {
                        this.mOpsToDefault.add(new OpToChange(uid, pkg.packageName, opCode));
                    }
                }
                int op = policy.resolveAppOp();
                if (op != -1) {
                    int desiredOpMode = policy.getDesiredOpMode();
                    if (desiredOpMode == 0) {
                        if (policy.shouldSetAppOpIfNotDefault()) {
                            this.mOpsToAllow.add(new OpToChange(uid, pkg.packageName, op));
                        } else {
                            this.mOpsToAllowIfDefault.add(new OpToChange(uid, pkg.packageName, op));
                        }
                    } else if (desiredOpMode == 1) {
                        if (policy.shouldSetAppOpIfNotDefault()) {
                            this.mOpsToIgnore.add(new OpToChange(uid, pkg.packageName, op));
                        } else {
                            this.mOpsToIgnoreIfDefault.add(new OpToChange(uid, pkg.packageName, op));
                        }
                    } else if (desiredOpMode == 2) {
                        Slog.wtf(PermissionPolicyService.LOG_TAG, "Setting appop to errored is not implemented");
                    } else if (desiredOpMode == 3) {
                        this.mOpsToDefault.add(new OpToChange(uid, pkg.packageName, op));
                    } else if (desiredOpMode == 4) {
                        Slog.wtf(PermissionPolicyService.LOG_TAG, "Setting appop to foreground is not implemented");
                    }
                }
            }
        }

        private boolean isBgPermRestricted(String pkg, String perm, int uid) {
            try {
                PermissionInfo bgPermInfo = this.mPackageManager.getPermissionInfo(perm, 0);
                if (bgPermInfo.isSoftRestricted()) {
                    Slog.wtf(PermissionPolicyService.LOG_TAG, "Support for soft restricted background permissions not implemented");
                }
                if (bgPermInfo.isHardRestricted()) {
                    return (this.mPackageManager.getPermissionFlags(perm, pkg, UserHandle.getUserHandleForUid(uid)) & 16384) != 0;
                }
                return false;
            } catch (PackageManager.NameNotFoundException e) {
                String str = PermissionPolicyService.LOG_TAG;
                Slog.w(str, "Cannot read permission state of " + perm, e);
                return false;
            }
        }

        private void addOpIfFgPermissions(PermissionInfo permissionInfo, PackageInfo pkg) {
            String bgPermissionName = permissionInfo.backgroundPermission;
            if (bgPermissionName == null) {
                return;
            }
            String permission = permissionInfo.name;
            int opCode = PermissionPolicyService.getSwitchOp(permission);
            String pkgName = pkg.packageName;
            int uid = pkg.applicationInfo.uid;
            if (pkg.applicationInfo.targetSdkVersion < 23) {
                int flags = this.mPackageManager.getPermissionFlags(bgPermissionName, pkg.packageName, UserHandle.getUserHandleForUid(uid));
                if ((flags & 64) == 0 && isBgPermRestricted(pkgName, bgPermissionName, uid)) {
                    this.mOpsToForegroundIfAllow.add(new OpToChange(uid, pkgName, opCode));
                }
            } else if (this.mPackageManager.checkPermission(permission, pkgName) == 0) {
                boolean isBgHardRestricted = isBgPermRestricted(pkgName, bgPermissionName, uid);
                boolean isBgPermGranted = this.mPackageManager.checkPermission(bgPermissionName, pkgName) == 0;
                if (!isBgHardRestricted && isBgPermGranted) {
                    this.mOpsToAllow.add(new OpToChange(uid, pkgName, opCode));
                } else {
                    this.mOpsToForeground.add(new OpToChange(uid, pkgName, opCode));
                }
            } else {
                this.mOpsToIgnore.add(new OpToChange(uid, pkgName, opCode));
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void addPackage(String pkgName) {
            String[] strArr;
            try {
                PackageInfo pkg = this.mPackageManager.getPackageInfo(pkgName, 4096);
                this.mAllUids.put(pkg.applicationInfo.uid, pkg.applicationInfo.uid);
                if (pkg.requestedPermissions == null) {
                    return;
                }
                for (String permission : pkg.requestedPermissions) {
                    int opCode = PermissionPolicyService.getSwitchOp(permission);
                    if (opCode != -1) {
                        try {
                            PermissionInfo permissionInfo = this.mPackageManager.getPermissionInfo(permission, 0);
                            addOpIfRestricted(permissionInfo, pkg);
                            addOpIfFgPermissions(permissionInfo, pkg);
                        } catch (PackageManager.NameNotFoundException e) {
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e2) {
            }
        }

        private boolean setUidModeAllowedIfDefault(int opCode, int uid, String packageName) {
            return setUidModeIfMode(opCode, uid, 3, 0, packageName);
        }

        private void setUidModeAllowed(int opCode, int uid, String packageName) {
            setUidMode(opCode, uid, 0, packageName);
        }

        private boolean setUidModeForegroundIfAllow(int opCode, int uid, String packageName) {
            return setUidModeIfMode(opCode, uid, 0, 4, packageName);
        }

        private void setUidModeForeground(int opCode, int uid, String packageName) {
            setUidMode(opCode, uid, 4, packageName);
        }

        private boolean setUidModeIgnoredIfDefault(int opCode, int uid, String packageName) {
            return setUidModeIfMode(opCode, uid, 3, 1, packageName);
        }

        private void setUidModeIgnored(int opCode, int uid, String packageName) {
            setUidMode(opCode, uid, 1, packageName);
        }

        private void setUidMode(int opCode, int uid, int mode, String packageName) {
            int currentMode = this.mAppOpsManager.unsafeCheckOpRaw(AppOpsManager.opToPublicName(opCode), uid, packageName);
            if (currentMode != mode) {
                this.mAppOpsManager.setUidMode(opCode, uid, mode);
            }
        }

        private boolean setUidModeIfMode(int opCode, int uid, int requiredModeBefore, int newMode, String packageName) {
            int currentMode = this.mAppOpsManager.unsafeCheckOpRaw(AppOpsManager.opToPublicName(opCode), uid, packageName);
            if (currentMode == requiredModeBefore) {
                this.mAppOpsManager.setUidMode(opCode, uid, newMode);
                return true;
            }
            return false;
        }

        private void setUidModeDefault(int opCode, int uid, String packageName) {
            setUidMode(opCode, uid, 3, packageName);
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public class OpToChange {
            final int code;
            final String packageName;
            final int uid;

            OpToChange(int uid, String packageName, int code) {
                this.uid = uid;
                this.packageName = packageName;
                this.code = code;
            }
        }
    }

    /* loaded from: classes.dex */
    private class Internal extends PermissionPolicyInternal {
        private Internal() {
        }

        @Override // com.android.server.policy.PermissionPolicyInternal
        public boolean checkStartActivity(Intent intent, int callingUid, String callingPackage) {
            if (callingPackage != null && isActionRemovedForCallingPackage(intent, callingUid, callingPackage)) {
                String str = PermissionPolicyService.LOG_TAG;
                Slog.w(str, "Action Removed: starting " + intent.toString() + " from " + callingPackage + " (uid=" + callingUid + ")");
                return false;
            }
            return true;
        }

        @Override // com.android.server.policy.PermissionPolicyInternal
        public boolean isInitialized(int userId) {
            return PermissionPolicyService.this.isStarted(userId);
        }

        @Override // com.android.server.policy.PermissionPolicyInternal
        public void setOnInitializedCallback(PermissionPolicyInternal.OnInitializedCallback callback) {
            synchronized (PermissionPolicyService.this.mLock) {
                PermissionPolicyService.this.mOnInitializedCallback = callback;
            }
        }

        private boolean isActionRemovedForCallingPackage(Intent intent, int callingUid, String callingPackage) {
            String action = intent.getAction();
            if (action == null) {
                return false;
            }
            char c = 65535;
            int hashCode = action.hashCode();
            if (hashCode != -1673968409) {
                if (hashCode == 579418056 && action.equals("android.telecom.action.CHANGE_DEFAULT_DIALER")) {
                    c = 0;
                }
            } else if (action.equals("android.provider.Telephony.ACTION_CHANGE_DEFAULT")) {
                c = 1;
            }
            if (c != 0 && c != 1) {
                return false;
            }
            try {
                ApplicationInfo applicationInfo = PermissionPolicyService.this.getContext().getPackageManager().getApplicationInfoAsUser(callingPackage, 0, UserHandle.getUserId(callingUid));
                if (applicationInfo.targetSdkVersion >= 29) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                Slog.i(PermissionPolicyService.LOG_TAG, "Cannot find application info for " + callingPackage);
            }
            intent.putExtra("android.intent.extra.CALLING_PACKAGE", callingPackage);
            return false;
        }
    }
}
