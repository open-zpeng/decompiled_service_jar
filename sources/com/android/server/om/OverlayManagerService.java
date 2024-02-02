package com.android.server.om;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.FgThread;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.om.OverlayManagerService;
import com.android.server.om.OverlayManagerServiceImpl;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import libcore.util.EmptyArray;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public final class OverlayManagerService extends SystemService {
    static final boolean DEBUG = false;
    private static final String DEFAULT_OVERLAYS_PROP = "ro.boot.vendor.overlay.theme";
    static final String TAG = "OverlayManager";
    private final OverlayManagerServiceImpl mImpl;
    private Future<?> mInitCompleteSignal;
    private final Object mLock;
    private final PackageManagerHelper mPackageManager;
    private final AtomicBoolean mPersistSettingsScheduled;
    private final IBinder mService;
    private final OverlayManagerSettings mSettings;
    private final AtomicFile mSettingsFile;
    private final UserManagerService mUserManager;

    public OverlayManagerService(Context context, Installer installer) {
        super(context);
        this.mLock = new Object();
        this.mPersistSettingsScheduled = new AtomicBoolean(false);
        this.mService = new IOverlayManager.Stub() { // from class: com.android.server.om.OverlayManagerService.1
            public Map<String, List<OverlayInfo>> getAllOverlays(int userId) throws RemoteException {
                Map<String, List<OverlayInfo>> overlaysForUser;
                int userId2 = handleIncomingUser(userId, "getAllOverlays");
                synchronized (OverlayManagerService.this.mLock) {
                    overlaysForUser = OverlayManagerService.this.mImpl.getOverlaysForUser(userId2);
                }
                return overlaysForUser;
            }

            public List<OverlayInfo> getOverlayInfosForTarget(String targetPackageName, int userId) throws RemoteException {
                List<OverlayInfo> overlayInfosForTarget;
                int userId2 = handleIncomingUser(userId, "getOverlayInfosForTarget");
                if (targetPackageName != null) {
                    synchronized (OverlayManagerService.this.mLock) {
                        overlayInfosForTarget = OverlayManagerService.this.mImpl.getOverlayInfosForTarget(targetPackageName, userId2);
                    }
                    return overlayInfosForTarget;
                }
                return Collections.emptyList();
            }

            public OverlayInfo getOverlayInfo(String packageName, int userId) throws RemoteException {
                OverlayInfo overlayInfo;
                int userId2 = handleIncomingUser(userId, "getOverlayInfo");
                if (packageName != null) {
                    synchronized (OverlayManagerService.this.mLock) {
                        overlayInfo = OverlayManagerService.this.mImpl.getOverlayInfo(packageName, userId2);
                    }
                    return overlayInfo;
                }
                return null;
            }

            public boolean setEnabled(String packageName, boolean enable, int userId) throws RemoteException {
                boolean enabled;
                enforceChangeOverlayPackagesPermission("setEnabled");
                int userId2 = handleIncomingUser(userId, "setEnabled");
                if (packageName == null) {
                    return false;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (OverlayManagerService.this.mLock) {
                        enabled = OverlayManagerService.this.mImpl.setEnabled(packageName, enable, userId2);
                    }
                    return enabled;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public boolean setEnabledExclusive(String packageName, boolean enable, int userId) throws RemoteException {
                boolean enabledExclusive;
                enforceChangeOverlayPackagesPermission("setEnabled");
                int userId2 = handleIncomingUser(userId, "setEnabled");
                if (packageName == null || !enable) {
                    return false;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (OverlayManagerService.this.mLock) {
                        enabledExclusive = OverlayManagerService.this.mImpl.setEnabledExclusive(packageName, false, userId2);
                    }
                    return enabledExclusive;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public boolean setEnabledExclusiveInCategory(String packageName, int userId) throws RemoteException {
                boolean enabledExclusive;
                enforceChangeOverlayPackagesPermission("setEnabled");
                int userId2 = handleIncomingUser(userId, "setEnabled");
                if (packageName == null) {
                    return false;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (OverlayManagerService.this.mLock) {
                        enabledExclusive = OverlayManagerService.this.mImpl.setEnabledExclusive(packageName, true, userId2);
                    }
                    return enabledExclusive;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public boolean setPriority(String packageName, String parentPackageName, int userId) throws RemoteException {
                boolean priority;
                enforceChangeOverlayPackagesPermission("setPriority");
                int userId2 = handleIncomingUser(userId, "setPriority");
                if (packageName == null || parentPackageName == null) {
                    return false;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (OverlayManagerService.this.mLock) {
                        priority = OverlayManagerService.this.mImpl.setPriority(packageName, parentPackageName, userId2);
                    }
                    return priority;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public boolean setHighestPriority(String packageName, int userId) throws RemoteException {
                boolean highestPriority;
                enforceChangeOverlayPackagesPermission("setHighestPriority");
                int userId2 = handleIncomingUser(userId, "setHighestPriority");
                if (packageName == null) {
                    return false;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (OverlayManagerService.this.mLock) {
                        highestPriority = OverlayManagerService.this.mImpl.setHighestPriority(packageName, userId2);
                    }
                    return highestPriority;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public boolean setLowestPriority(String packageName, int userId) throws RemoteException {
                boolean lowestPriority;
                enforceChangeOverlayPackagesPermission("setLowestPriority");
                int userId2 = handleIncomingUser(userId, "setLowestPriority");
                if (packageName == null) {
                    return false;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (OverlayManagerService.this.mLock) {
                        lowestPriority = OverlayManagerService.this.mImpl.setLowestPriority(packageName, userId2);
                    }
                    return lowestPriority;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            /* JADX WARN: Multi-variable type inference failed */
            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                new OverlayManagerShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
            }

            protected void dump(FileDescriptor fd, PrintWriter pw, String[] argv) {
                enforceDumpPermission("dump");
                boolean z = false;
                if (argv.length > 0 && "--verbose".equals(argv[0])) {
                    z = true;
                }
                boolean verbose = z;
                synchronized (OverlayManagerService.this.mLock) {
                    OverlayManagerService.this.mImpl.onDump(pw);
                    OverlayManagerService.this.mPackageManager.dump(pw, verbose);
                }
            }

            private int handleIncomingUser(int userId, String message) {
                return ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, message, null);
            }

            private void enforceChangeOverlayPackagesPermission(String message) {
                OverlayManagerService.this.getContext().enforceCallingPermission("android.permission.CHANGE_OVERLAY_PACKAGES", message);
            }

            private void enforceDumpPermission(String message) {
                OverlayManagerService.this.getContext().enforceCallingPermission("android.permission.DUMP", message);
            }
        };
        this.mSettingsFile = new AtomicFile(new File(Environment.getDataSystemDirectory(), "overlays.xml"), "overlays");
        this.mPackageManager = new PackageManagerHelper();
        this.mUserManager = UserManagerService.getInstance();
        IdmapManager im = new IdmapManager(installer);
        this.mSettings = new OverlayManagerSettings();
        this.mImpl = new OverlayManagerServiceImpl(this.mPackageManager, im, this.mSettings, getDefaultOverlayPackages(), new OverlayChangeListener());
        this.mInitCompleteSignal = SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.om.-$$Lambda$OverlayManagerService$mX9VnR-_2XOwgKo9C81uZcpqETM
            @Override // java.lang.Runnable
            public final void run() {
                OverlayManagerService.lambda$new$0(OverlayManagerService.this);
            }
        }, "Init OverlayManagerService");
    }

    public static /* synthetic */ void lambda$new$0(OverlayManagerService overlayManagerService) {
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
        packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
        packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        packageFilter.addDataScheme("package");
        overlayManagerService.getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL, packageFilter, null, null);
        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction("android.intent.action.USER_ADDED");
        userFilter.addAction("android.intent.action.USER_REMOVED");
        overlayManagerService.getContext().registerReceiverAsUser(new UserReceiver(), UserHandle.ALL, userFilter, null, null);
        overlayManagerService.restoreSettings();
        overlayManagerService.initIfNeeded();
        overlayManagerService.onSwitchUser(0);
        overlayManagerService.publishBinderService("overlay", overlayManagerService.mService);
        overlayManagerService.publishLocalService(OverlayManagerService.class, overlayManagerService);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500 && this.mInitCompleteSignal != null) {
            ConcurrentUtils.waitForFutureNoInterrupt(this.mInitCompleteSignal, "Wait for OverlayManagerService init");
            this.mInitCompleteSignal = null;
        }
    }

    public void updateSystemUiContext() {
        if (this.mInitCompleteSignal != null) {
            ConcurrentUtils.waitForFutureNoInterrupt(this.mInitCompleteSignal, "Wait for OverlayManagerService init");
            this.mInitCompleteSignal = null;
        }
        try {
            ApplicationInfo ai = this.mPackageManager.mPackageManager.getApplicationInfo(PackageManagerService.PLATFORM_PACKAGE_NAME, 1024, 0);
            ActivityThread.currentActivityThread().handleSystemApplicationInfoChanged(ai);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    private void initIfNeeded() {
        UserManager um = (UserManager) getContext().getSystemService(UserManager.class);
        List<UserInfo> users = um.getUsers(true);
        synchronized (this.mLock) {
            int userCount = users.size();
            for (int i = 0; i < userCount; i++) {
                UserInfo userInfo = users.get(i);
                if (!userInfo.supportsSwitchTo() && userInfo.id != 0) {
                    List<String> targets = this.mImpl.updateOverlaysForUser(users.get(i).id);
                    updateOverlayPaths(users.get(i).id, targets);
                }
            }
        }
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int newUserId) {
        synchronized (this.mLock) {
            List<String> targets = this.mImpl.updateOverlaysForUser(newUserId);
            updateAssets(newUserId, targets);
        }
        schedulePersistSettings();
    }

    private static String[] getDefaultOverlayPackages() {
        String[] split;
        String str = SystemProperties.get(DEFAULT_OVERLAYS_PROP);
        if (TextUtils.isEmpty(str)) {
            return EmptyArray.STRING;
        }
        ArraySet<String> defaultPackages = new ArraySet<>();
        for (String packageName : str.split(";")) {
            if (!TextUtils.isEmpty(packageName)) {
                defaultPackages.add(packageName);
            }
        }
        return (String[]) defaultPackages.toArray(new String[defaultPackages.size()]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class PackageReceiver extends BroadcastReceiver {
        private PackageReceiver() {
        }

        /* JADX WARN: Code restructure failed: missing block: B:18:0x0056, code lost:
            if (r7.equals("android.intent.action.PACKAGE_ADDED") == false) goto L31;
         */
        @Override // android.content.BroadcastReceiver
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void onReceive(android.content.Context r12, android.content.Intent r13) {
            /*
                r11 = this;
                android.net.Uri r0 = r13.getData()
                if (r0 != 0) goto Le
                java.lang.String r1 = "OverlayManager"
                java.lang.String r2 = "Cannot handle package broadcast with null data"
                android.util.Slog.e(r1, r2)
                return
            Le:
                java.lang.String r1 = r0.getSchemeSpecificPart()
                java.lang.String r2 = "android.intent.extra.REPLACING"
                r3 = 0
                boolean r2 = r13.getBooleanExtra(r2, r3)
                java.lang.String r4 = "android.intent.extra.UID"
                r5 = -10000(0xffffffffffffd8f0, float:NaN)
                int r4 = r13.getIntExtra(r4, r5)
                r6 = 1
                if (r4 != r5) goto L2f
                com.android.server.om.OverlayManagerService r5 = com.android.server.om.OverlayManagerService.this
                com.android.server.pm.UserManagerService r5 = com.android.server.om.OverlayManagerService.access$200(r5)
                int[] r5 = r5.getUserIds()
                goto L37
            L2f:
                int[] r5 = new int[r6]
                int r7 = android.os.UserHandle.getUserId(r4)
                r5[r3] = r7
            L37:
                java.lang.String r7 = r13.getAction()
                r8 = -1
                int r9 = r7.hashCode()
                r10 = 172491798(0xa480416, float:9.630418E-33)
                if (r9 == r10) goto L63
                r6 = 525384130(0x1f50b9c2, float:4.419937E-20)
                if (r9 == r6) goto L59
                r6 = 1544582882(0x5c1076e2, float:1.6265244E17)
                if (r9 == r6) goto L50
                goto L6d
            L50:
                java.lang.String r6 = "android.intent.action.PACKAGE_ADDED"
                boolean r6 = r7.equals(r6)
                if (r6 == 0) goto L6d
                goto L6e
            L59:
                java.lang.String r3 = "android.intent.action.PACKAGE_REMOVED"
                boolean r3 = r7.equals(r3)
                if (r3 == 0) goto L6d
                r3 = 2
                goto L6e
            L63:
                java.lang.String r3 = "android.intent.action.PACKAGE_CHANGED"
                boolean r3 = r7.equals(r3)
                if (r3 == 0) goto L6d
                r3 = r6
                goto L6e
            L6d:
                r3 = r8
            L6e:
                switch(r3) {
                    case 0: goto L80;
                    case 1: goto L7c;
                    case 2: goto L72;
                    default: goto L71;
                }
            L71:
                goto L8a
            L72:
                if (r2 == 0) goto L78
                r11.onPackageUpgrading(r1, r5)
                goto L8a
            L78:
                r11.onPackageRemoved(r1, r5)
                goto L8a
            L7c:
                r11.onPackageChanged(r1, r5)
                goto L8a
            L80:
                if (r2 == 0) goto L86
                r11.onPackageUpgraded(r1, r5)
                goto L8a
            L86:
                r11.onPackageAdded(r1, r5)
            L8a:
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.om.OverlayManagerService.PackageReceiver.onReceive(android.content.Context, android.content.Intent):void");
        }

        private void onPackageAdded(String packageName, int[] userIds) {
            for (int userId : userIds) {
                synchronized (OverlayManagerService.this.mLock) {
                    PackageInfo pi = OverlayManagerService.this.mPackageManager.getPackageInfo(packageName, userId, false);
                    if (pi != null) {
                        OverlayManagerService.this.mPackageManager.cachePackageInfo(packageName, userId, pi);
                        if (pi.isOverlayPackage()) {
                            OverlayManagerService.this.mImpl.onOverlayPackageAdded(packageName, userId);
                        } else {
                            OverlayManagerService.this.mImpl.onTargetPackageAdded(packageName, userId);
                        }
                    }
                }
            }
        }

        private void onPackageChanged(String packageName, int[] userIds) {
            for (int userId : userIds) {
                synchronized (OverlayManagerService.this.mLock) {
                    PackageInfo pi = OverlayManagerService.this.mPackageManager.getPackageInfo(packageName, userId, false);
                    if (pi != null) {
                        OverlayManagerService.this.mPackageManager.cachePackageInfo(packageName, userId, pi);
                        if (pi.isOverlayPackage()) {
                            OverlayManagerService.this.mImpl.onOverlayPackageChanged(packageName, userId);
                        } else {
                            OverlayManagerService.this.mImpl.onTargetPackageChanged(packageName, userId);
                        }
                    }
                }
            }
        }

        private void onPackageUpgrading(String packageName, int[] userIds) {
            for (int userId : userIds) {
                synchronized (OverlayManagerService.this.mLock) {
                    OverlayManagerService.this.mPackageManager.forgetPackageInfo(packageName, userId);
                    OverlayInfo oi = OverlayManagerService.this.mImpl.getOverlayInfo(packageName, userId);
                    if (oi != null) {
                        OverlayManagerService.this.mImpl.onOverlayPackageUpgrading(packageName, userId);
                    } else {
                        OverlayManagerService.this.mImpl.onTargetPackageUpgrading(packageName, userId);
                    }
                }
            }
        }

        private void onPackageUpgraded(String packageName, int[] userIds) {
            for (int userId : userIds) {
                synchronized (OverlayManagerService.this.mLock) {
                    PackageInfo pi = OverlayManagerService.this.mPackageManager.getPackageInfo(packageName, userId, false);
                    if (pi != null) {
                        OverlayManagerService.this.mPackageManager.cachePackageInfo(packageName, userId, pi);
                        if (pi.isOverlayPackage()) {
                            OverlayManagerService.this.mImpl.onOverlayPackageUpgraded(packageName, userId);
                        } else {
                            OverlayManagerService.this.mImpl.onTargetPackageUpgraded(packageName, userId);
                        }
                    }
                }
            }
        }

        private void onPackageRemoved(String packageName, int[] userIds) {
            for (int userId : userIds) {
                synchronized (OverlayManagerService.this.mLock) {
                    OverlayManagerService.this.mPackageManager.forgetPackageInfo(packageName, userId);
                    OverlayInfo oi = OverlayManagerService.this.mImpl.getOverlayInfo(packageName, userId);
                    if (oi != null) {
                        OverlayManagerService.this.mImpl.onOverlayPackageRemoved(packageName, userId);
                    } else {
                        OverlayManagerService.this.mImpl.onTargetPackageRemoved(packageName, userId);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class UserReceiver extends BroadcastReceiver {
        private UserReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            char c;
            ArrayList<String> targets;
            int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
            String action = intent.getAction();
            int hashCode = action.hashCode();
            if (hashCode != -2061058799) {
                if (hashCode == 1121780209 && action.equals("android.intent.action.USER_ADDED")) {
                    c = 0;
                }
                c = 65535;
            } else {
                if (action.equals("android.intent.action.USER_REMOVED")) {
                    c = 1;
                }
                c = 65535;
            }
            switch (c) {
                case 0:
                    if (userId != -10000) {
                        synchronized (OverlayManagerService.this.mLock) {
                            targets = OverlayManagerService.this.mImpl.updateOverlaysForUser(userId);
                        }
                        OverlayManagerService.this.updateOverlayPaths(userId, targets);
                        return;
                    }
                    return;
                case 1:
                    if (userId != -10000) {
                        synchronized (OverlayManagerService.this.mLock) {
                            OverlayManagerService.this.mImpl.onUserRemoved(userId);
                            OverlayManagerService.this.mPackageManager.forgetAllPackageInfos(userId);
                        }
                        return;
                    }
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class OverlayChangeListener implements OverlayManagerServiceImpl.OverlayChangeListener {
        private OverlayChangeListener() {
        }

        @Override // com.android.server.om.OverlayManagerServiceImpl.OverlayChangeListener
        public void onOverlaysChanged(final String targetPackageName, final int userId) {
            OverlayManagerService.this.schedulePersistSettings();
            FgThread.getHandler().post(new Runnable() { // from class: com.android.server.om.-$$Lambda$OverlayManagerService$OverlayChangeListener$u9oeN2C0PDMo0pYiLqfMBkwuMNA
                @Override // java.lang.Runnable
                public final void run() {
                    OverlayManagerService.OverlayChangeListener.lambda$onOverlaysChanged$0(OverlayManagerService.OverlayChangeListener.this, userId, targetPackageName);
                }
            });
        }

        public static /* synthetic */ void lambda$onOverlaysChanged$0(OverlayChangeListener overlayChangeListener, int userId, String targetPackageName) {
            OverlayManagerService.this.updateAssets(userId, targetPackageName);
            Intent intent = new Intent("android.intent.action.OVERLAY_CHANGED", Uri.fromParts("package", targetPackageName, null));
            intent.setFlags(67108864);
            try {
                try {
                    ActivityManager.getService().broadcastIntent((IApplicationThread) null, intent, (String) null, (IIntentReceiver) null, 0, (String) null, (Bundle) null, (String[]) null, -1, (Bundle) null, false, false, userId);
                } catch (RemoteException e) {
                }
            } catch (RemoteException e2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateOverlayPaths(int userId, List<String> targetPackageNames) {
        PackageManagerInternal pm = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        boolean updateFrameworkRes = targetPackageNames.contains(PackageManagerService.PLATFORM_PACKAGE_NAME);
        if (updateFrameworkRes) {
            targetPackageNames = pm.getTargetPackageNames(userId);
        }
        Map<String, List<String>> pendingChanges = new ArrayMap<>(targetPackageNames.size());
        synchronized (this.mLock) {
            List<String> frameworkOverlays = this.mImpl.getEnabledOverlayPackageNames(PackageManagerService.PLATFORM_PACKAGE_NAME, userId);
            int N = targetPackageNames.size();
            for (int i = 0; i < N; i++) {
                String targetPackageName = targetPackageNames.get(i);
                List<String> list = new ArrayList<>();
                if (!PackageManagerService.PLATFORM_PACKAGE_NAME.equals(targetPackageName)) {
                    list.addAll(frameworkOverlays);
                }
                list.addAll(this.mImpl.getEnabledOverlayPackageNames(targetPackageName, userId));
                pendingChanges.put(targetPackageName, list);
            }
        }
        int N2 = targetPackageNames.size();
        for (int i2 = 0; i2 < N2; i2++) {
            String targetPackageName2 = targetPackageNames.get(i2);
            if (!pm.setEnabledOverlayPackages(userId, targetPackageName2, pendingChanges.get(targetPackageName2))) {
                Slog.e(TAG, String.format("Failed to change enabled overlays for %s user %d", targetPackageName2, Integer.valueOf(userId)));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateAssets(int userId, String targetPackageName) {
        updateAssets(userId, Collections.singletonList(targetPackageName));
    }

    private void updateAssets(int userId, List<String> targetPackageNames) {
        updateOverlayPaths(userId, targetPackageNames);
        IActivityManager am = ActivityManager.getService();
        try {
            am.scheduleApplicationInfoChanged(targetPackageNames, userId);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void schedulePersistSettings() {
        if (this.mPersistSettingsScheduled.getAndSet(true)) {
            return;
        }
        IoThread.getHandler().post(new Runnable() { // from class: com.android.server.om.-$$Lambda$OverlayManagerService$YGMOwF5u3kvuRAEYnGl_xpXcVC4
            @Override // java.lang.Runnable
            public final void run() {
                OverlayManagerService.lambda$schedulePersistSettings$1(OverlayManagerService.this);
            }
        });
    }

    public static /* synthetic */ void lambda$schedulePersistSettings$1(OverlayManagerService overlayManagerService) {
        overlayManagerService.mPersistSettingsScheduled.set(false);
        synchronized (overlayManagerService.mLock) {
            FileOutputStream stream = null;
            try {
                stream = overlayManagerService.mSettingsFile.startWrite();
                overlayManagerService.mSettings.persist(stream);
                overlayManagerService.mSettingsFile.finishWrite(stream);
            } catch (IOException | XmlPullParserException e) {
                overlayManagerService.mSettingsFile.failWrite(stream);
                Slog.e(TAG, "failed to persist overlay state", e);
            }
        }
    }

    private void restoreSettings() {
        FileInputStream stream;
        int[] users;
        synchronized (this.mLock) {
            if (!this.mSettingsFile.getBaseFile().exists()) {
                return;
            }
            try {
                stream = this.mSettingsFile.openRead();
            } catch (IOException | XmlPullParserException e) {
                Slog.e(TAG, "failed to restore overlay state", e);
            }
            try {
                this.mSettings.restore(stream);
                List<UserInfo> liveUsers = this.mUserManager.getUsers(true);
                int[] liveUserIds = new int[liveUsers.size()];
                for (int i = 0; i < liveUsers.size(); i++) {
                    liveUserIds[i] = liveUsers.get(i).getUserHandle().getIdentifier();
                }
                Arrays.sort(liveUserIds);
                for (int userId : this.mSettings.getUsers()) {
                    if (Arrays.binarySearch(liveUserIds, userId) < 0) {
                        this.mSettings.removeUser(userId);
                    }
                }
                if (stream != null) {
                    stream.close();
                }
            } finally {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class PackageManagerHelper implements OverlayManagerServiceImpl.PackageManagerHelper {
        private static final String TAB1 = "    ";
        private static final String TAB2 = "        ";
        private final SparseArray<HashMap<String, PackageInfo>> mCache = new SparseArray<>();
        private final IPackageManager mPackageManager = AppGlobals.getPackageManager();
        private final PackageManagerInternal mPackageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);

        PackageManagerHelper() {
        }

        public PackageInfo getPackageInfo(String packageName, int userId, boolean useCache) {
            PackageInfo cachedPi;
            if (useCache && (cachedPi = getCachedPackageInfo(packageName, userId)) != null) {
                return cachedPi;
            }
            try {
                PackageInfo pi = this.mPackageManager.getPackageInfo(packageName, 0, userId);
                if (useCache && pi != null) {
                    cachePackageInfo(packageName, userId, pi);
                }
                return pi;
            } catch (RemoteException e) {
                return null;
            }
        }

        @Override // com.android.server.om.OverlayManagerServiceImpl.PackageManagerHelper
        public PackageInfo getPackageInfo(String packageName, int userId) {
            return getPackageInfo(packageName, userId, true);
        }

        @Override // com.android.server.om.OverlayManagerServiceImpl.PackageManagerHelper
        public boolean signaturesMatching(String packageName1, String packageName2, int userId) {
            try {
                return this.mPackageManager.checkSignatures(packageName1, packageName2) == 0;
            } catch (RemoteException e) {
                return false;
            }
        }

        @Override // com.android.server.om.OverlayManagerServiceImpl.PackageManagerHelper
        public List<PackageInfo> getOverlayPackages(int userId) {
            return this.mPackageManagerInternal.getOverlayPackages(userId);
        }

        public PackageInfo getCachedPackageInfo(String packageName, int userId) {
            HashMap<String, PackageInfo> map = this.mCache.get(userId);
            if (map == null) {
                return null;
            }
            return map.get(packageName);
        }

        public void cachePackageInfo(String packageName, int userId, PackageInfo pi) {
            HashMap<String, PackageInfo> map = this.mCache.get(userId);
            if (map == null) {
                map = new HashMap<>();
                this.mCache.put(userId, map);
            }
            map.put(packageName, pi);
        }

        public void forgetPackageInfo(String packageName, int userId) {
            HashMap<String, PackageInfo> map = this.mCache.get(userId);
            if (map == null) {
                return;
            }
            map.remove(packageName);
            if (map.isEmpty()) {
                this.mCache.delete(userId);
            }
        }

        public void forgetAllPackageInfos(int userId) {
            this.mCache.delete(userId);
        }

        public void dump(PrintWriter pw, boolean verbose) {
            pw.println("PackageInfo cache");
            int i = 0;
            if (!verbose) {
                int count = 0;
                int N = this.mCache.size();
                while (i < N) {
                    count += this.mCache.get(this.mCache.keyAt(i)).size();
                    i++;
                }
                pw.println(TAB1 + count + " package(s)");
            } else if (this.mCache.size() == 0) {
                pw.println("    <empty>");
            } else {
                int N2 = this.mCache.size();
                while (i < N2) {
                    int userId = this.mCache.keyAt(i);
                    pw.println("    User " + userId);
                    HashMap<String, PackageInfo> map = this.mCache.get(userId);
                    for (Map.Entry<String, PackageInfo> entry : map.entrySet()) {
                        pw.println(TAB2 + entry.getKey() + ": " + entry.getValue());
                    }
                    i++;
                }
            }
        }
    }
}
