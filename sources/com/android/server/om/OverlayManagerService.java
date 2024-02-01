package com.android.server.om;

import android.app.ActivityManager;
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
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import com.android.server.FgThread;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.om.OverlayManagerService;
import com.android.server.om.OverlayManagerServiceImpl;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.UserManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import libcore.util.EmptyArray;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public final class OverlayManagerService extends SystemService {
    static final boolean DEBUG = false;
    private static final String DEFAULT_OVERLAYS_PROP = "ro.boot.vendor.overlay.theme";
    static final String TAG = "OverlayManager";
    private final OverlayManagerServiceImpl mImpl;
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
                try {
                    Trace.traceBegin(67108864L, "OMS#getAllOverlays " + userId);
                    int userId2 = handleIncomingUser(userId, "getAllOverlays");
                    synchronized (OverlayManagerService.this.mLock) {
                        overlaysForUser = OverlayManagerService.this.mImpl.getOverlaysForUser(userId2);
                    }
                    return overlaysForUser;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public List<OverlayInfo> getOverlayInfosForTarget(String targetPackageName, int userId) throws RemoteException {
                List<OverlayInfo> overlayInfosForTarget;
                try {
                    Trace.traceBegin(67108864L, "OMS#getOverlayInfosForTarget " + targetPackageName);
                    int userId2 = handleIncomingUser(userId, "getOverlayInfosForTarget");
                    if (targetPackageName != null) {
                        synchronized (OverlayManagerService.this.mLock) {
                            overlayInfosForTarget = OverlayManagerService.this.mImpl.getOverlayInfosForTarget(targetPackageName, userId2);
                        }
                        return overlayInfosForTarget;
                    }
                    return Collections.emptyList();
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public OverlayInfo getOverlayInfo(String packageName, int userId) throws RemoteException {
                OverlayInfo overlayInfo;
                try {
                    Trace.traceBegin(67108864L, "OMS#getOverlayInfo " + packageName);
                    int userId2 = handleIncomingUser(userId, "getOverlayInfo");
                    if (packageName != null) {
                        synchronized (OverlayManagerService.this.mLock) {
                            overlayInfo = OverlayManagerService.this.mImpl.getOverlayInfo(packageName, userId2);
                        }
                        return overlayInfo;
                    }
                    return null;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public boolean setEnabled(String packageName, boolean enable, int userId) throws RemoteException {
                boolean enabled;
                try {
                    Trace.traceBegin(67108864L, "OMS#setEnabled " + packageName + " " + enable);
                    enforceChangeOverlayPackagesPermission("setEnabled");
                    int userId2 = handleIncomingUser(userId, "setEnabled");
                    if (packageName != null) {
                        long ident = Binder.clearCallingIdentity();
                        synchronized (OverlayManagerService.this.mLock) {
                            enabled = OverlayManagerService.this.mImpl.setEnabled(packageName, enable, userId2);
                        }
                        Binder.restoreCallingIdentity(ident);
                        return enabled;
                    }
                    return false;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public boolean setEnabledExclusive(String packageName, boolean enable, int userId) throws RemoteException {
                boolean enabledExclusive;
                try {
                    Trace.traceBegin(67108864L, "OMS#setEnabledExclusive " + packageName + " " + enable);
                    enforceChangeOverlayPackagesPermission("setEnabledExclusive");
                    int userId2 = handleIncomingUser(userId, "setEnabledExclusive");
                    if (packageName != null && enable) {
                        long ident = Binder.clearCallingIdentity();
                        synchronized (OverlayManagerService.this.mLock) {
                            enabledExclusive = OverlayManagerService.this.mImpl.setEnabledExclusive(packageName, false, userId2);
                        }
                        Binder.restoreCallingIdentity(ident);
                        return enabledExclusive;
                    }
                    return false;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public boolean setEnabledExclusiveInCategory(String packageName, int userId) throws RemoteException {
                boolean enabledExclusive;
                try {
                    Trace.traceBegin(67108864L, "OMS#setEnabledExclusiveInCategory " + packageName);
                    enforceChangeOverlayPackagesPermission("setEnabledExclusiveInCategory");
                    int userId2 = handleIncomingUser(userId, "setEnabledExclusiveInCategory");
                    if (packageName != null) {
                        long ident = Binder.clearCallingIdentity();
                        synchronized (OverlayManagerService.this.mLock) {
                            enabledExclusive = OverlayManagerService.this.mImpl.setEnabledExclusive(packageName, true, userId2);
                        }
                        Binder.restoreCallingIdentity(ident);
                        return enabledExclusive;
                    }
                    return false;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public boolean setPriority(String packageName, String parentPackageName, int userId) throws RemoteException {
                boolean priority;
                try {
                    Trace.traceBegin(67108864L, "OMS#setPriority " + packageName + " " + parentPackageName);
                    enforceChangeOverlayPackagesPermission("setPriority");
                    int userId2 = handleIncomingUser(userId, "setPriority");
                    if (packageName != null && parentPackageName != null) {
                        long ident = Binder.clearCallingIdentity();
                        synchronized (OverlayManagerService.this.mLock) {
                            priority = OverlayManagerService.this.mImpl.setPriority(packageName, parentPackageName, userId2);
                        }
                        Binder.restoreCallingIdentity(ident);
                        return priority;
                    }
                    return false;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public boolean setHighestPriority(String packageName, int userId) throws RemoteException {
                boolean highestPriority;
                try {
                    Trace.traceBegin(67108864L, "OMS#setHighestPriority " + packageName);
                    enforceChangeOverlayPackagesPermission("setHighestPriority");
                    int userId2 = handleIncomingUser(userId, "setHighestPriority");
                    if (packageName != null) {
                        long ident = Binder.clearCallingIdentity();
                        synchronized (OverlayManagerService.this.mLock) {
                            highestPriority = OverlayManagerService.this.mImpl.setHighestPriority(packageName, userId2);
                        }
                        Binder.restoreCallingIdentity(ident);
                        return highestPriority;
                    }
                    return false;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public boolean setLowestPriority(String packageName, int userId) throws RemoteException {
                boolean lowestPriority;
                try {
                    Trace.traceBegin(67108864L, "OMS#setLowestPriority " + packageName);
                    enforceChangeOverlayPackagesPermission("setLowestPriority");
                    int userId2 = handleIncomingUser(userId, "setLowestPriority");
                    if (packageName != null) {
                        long ident = Binder.clearCallingIdentity();
                        synchronized (OverlayManagerService.this.mLock) {
                            lowestPriority = OverlayManagerService.this.mImpl.setLowestPriority(packageName, userId2);
                        }
                        Binder.restoreCallingIdentity(ident);
                        return lowestPriority;
                    }
                    return false;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            public String[] getDefaultOverlayPackages() throws RemoteException {
                String[] defaultOverlayPackages;
                try {
                    Trace.traceBegin(67108864L, "OMS#getDefaultOverlayPackages");
                    OverlayManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.MODIFY_THEME_OVERLAY", null);
                    long ident = Binder.clearCallingIdentity();
                    synchronized (OverlayManagerService.this.mLock) {
                        defaultOverlayPackages = OverlayManagerService.this.mImpl.getDefaultOverlayPackages();
                    }
                    Binder.restoreCallingIdentity(ident);
                    return defaultOverlayPackages;
                } finally {
                    Trace.traceEnd(67108864L);
                }
            }

            /* JADX WARN: Multi-variable type inference failed */
            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                new OverlayManagerShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
            }

            protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
                String opt;
                DumpState dumpState = new DumpState();
                char c = 65535;
                dumpState.setUserId(-1);
                int opti = 0;
                while (opti < args.length && (opt = args[opti]) != null && opt.length() > 0 && opt.charAt(0) == '-') {
                    opti++;
                    if ("-h".equals(opt)) {
                        pw.println("dump [-h] [--verbose] [--user USER_ID] [[FIELD] PACKAGE]");
                        pw.println("  Print debugging information about the overlay manager.");
                        pw.println("  With optional parameter PACKAGE, limit output to the specified");
                        pw.println("  package. With optional parameter FIELD, limit output to");
                        pw.println("  the value of that SettingsItem field. Field names are");
                        pw.println("  case insensitive and out.println the m prefix can be omitted,");
                        pw.println("  so the following are equivalent: mState, mstate, State, state.");
                        return;
                    } else if ("--user".equals(opt)) {
                        if (opti >= args.length) {
                            pw.println("Error: user missing argument");
                            return;
                        }
                        try {
                            dumpState.setUserId(Integer.parseInt(args[opti]));
                            opti++;
                        } catch (NumberFormatException e) {
                            pw.println("Error: user argument is not a number: " + args[opti]);
                            return;
                        }
                    } else if ("--verbose".equals(opt)) {
                        dumpState.setVerbose(true);
                    } else {
                        pw.println("Unknown argument: " + opt + "; use -h for help");
                    }
                }
                if (opti < args.length) {
                    String arg = args[opti];
                    opti++;
                    switch (arg.hashCode()) {
                        case -1750736508:
                            if (arg.equals("targetoverlayablename")) {
                                c = 3;
                                break;
                            }
                            break;
                        case -1248283232:
                            if (arg.equals("targetpackagename")) {
                                c = 2;
                                break;
                            }
                            break;
                        case -1165461084:
                            if (arg.equals(xpInputManagerService.InputPolicyKey.KEY_PRIORITY)) {
                                c = '\b';
                                break;
                            }
                            break;
                        case -836029914:
                            if (arg.equals("userid")) {
                                c = 1;
                                break;
                            }
                            break;
                        case 50511102:
                            if (arg.equals("category")) {
                                c = '\t';
                                break;
                            }
                            break;
                        case 109757585:
                            if (arg.equals("state")) {
                                c = 5;
                                break;
                            }
                            break;
                        case 440941271:
                            if (arg.equals("isenabled")) {
                                c = 6;
                                break;
                            }
                            break;
                        case 697685016:
                            if (arg.equals("isstatic")) {
                                c = 7;
                                break;
                            }
                            break;
                        case 909712337:
                            if (arg.equals("packagename")) {
                                c = 0;
                                break;
                            }
                            break;
                        case 1693907299:
                            if (arg.equals("basecodepath")) {
                                c = 4;
                                break;
                            }
                            break;
                    }
                    switch (c) {
                        case 0:
                        case 1:
                        case 2:
                        case 3:
                        case 4:
                        case 5:
                        case 6:
                        case 7:
                        case '\b':
                        case '\t':
                            dumpState.setField(arg);
                            break;
                        default:
                            dumpState.setPackageName(arg);
                            break;
                    }
                }
                if (dumpState.getPackageName() == null && opti < args.length) {
                    dumpState.setPackageName(args[opti]);
                    int i = opti + 1;
                }
                enforceDumpPermission("dump");
                synchronized (OverlayManagerService.this.mLock) {
                    OverlayManagerService.this.mImpl.dump(pw, dumpState);
                    if (dumpState.getPackageName() == null) {
                        OverlayManagerService.this.mPackageManager.dump(pw, dumpState);
                    }
                }
            }

            private int handleIncomingUser(int userId, String message) {
                return ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, message, null);
            }

            private void enforceChangeOverlayPackagesPermission(String message) {
                OverlayManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.CHANGE_OVERLAY_PACKAGES", message);
            }

            private void enforceDumpPermission(String message) {
                OverlayManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DUMP", message);
            }
        };
        try {
            Trace.traceBegin(67108864L, "OMS#OverlayManagerService");
            this.mSettingsFile = new AtomicFile(new File(Environment.getDataSystemDirectory(), "overlays.xml"), "overlays");
            this.mPackageManager = new PackageManagerHelper();
            this.mUserManager = UserManagerService.getInstance();
        } catch (Throwable th) {
            th = th;
        }
        try {
            IdmapManager im = new IdmapManager(installer, this.mPackageManager);
            this.mSettings = new OverlayManagerSettings();
            this.mImpl = new OverlayManagerServiceImpl(this.mPackageManager, im, this.mSettings, getDefaultOverlayPackages(), new OverlayChangeListener());
            IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
            packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
            packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
            packageFilter.addDataScheme("package");
            getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL, packageFilter, null, null);
            IntentFilter userFilter = new IntentFilter();
            userFilter.addAction("android.intent.action.USER_ADDED");
            userFilter.addAction("android.intent.action.USER_REMOVED");
            getContext().registerReceiverAsUser(new UserReceiver(), UserHandle.ALL, userFilter, null, null);
            restoreSettings();
            initIfNeeded();
            onSwitchUser(0);
            publishBinderService("overlay", this.mService);
            publishLocalService(OverlayManagerService.class, this);
            Trace.traceEnd(67108864L);
        } catch (Throwable th2) {
            th = th2;
            Trace.traceEnd(67108864L);
            throw th;
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
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
        try {
            Trace.traceBegin(67108864L, "OMS#onSwitchUser " + newUserId);
            synchronized (this.mLock) {
                List<String> targets = this.mImpl.updateOverlaysForUser(newUserId);
                updateAssets(newUserId, targets);
            }
            schedulePersistSettings();
        } finally {
            Trace.traceEnd(67108864L);
        }
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

    /* loaded from: classes.dex */
    private final class PackageReceiver extends BroadcastReceiver {
        private PackageReceiver() {
        }

        /* JADX WARN: Code restructure failed: missing block: B:22:0x005f, code lost:
            if (r0.equals("android.intent.action.PACKAGE_ADDED") == false) goto L37;
         */
        @Override // android.content.BroadcastReceiver
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void onReceive(android.content.Context r13, android.content.Intent r14) {
            /*
                r12 = this;
                java.lang.String r0 = r14.getAction()
                java.lang.String r1 = "OverlayManager"
                if (r0 != 0) goto Le
                java.lang.String r2 = "Cannot handle package broadcast with null action"
                android.util.Slog.e(r1, r2)
                return
            Le:
                android.net.Uri r2 = r14.getData()
                if (r2 != 0) goto L1a
                java.lang.String r3 = "Cannot handle package broadcast with null data"
                android.util.Slog.e(r1, r3)
                return
            L1a:
                java.lang.String r1 = r2.getSchemeSpecificPart()
                r3 = 0
                java.lang.String r4 = "android.intent.extra.REPLACING"
                boolean r4 = r14.getBooleanExtra(r4, r3)
                r5 = -10000(0xffffffffffffd8f0, float:NaN)
                java.lang.String r6 = "android.intent.extra.UID"
                int r6 = r14.getIntExtra(r6, r5)
                r7 = 1
                if (r6 != r5) goto L3b
                com.android.server.om.OverlayManagerService r5 = com.android.server.om.OverlayManagerService.this
                com.android.server.pm.UserManagerService r5 = com.android.server.om.OverlayManagerService.access$300(r5)
                int[] r5 = r5.getUserIds()
                goto L43
            L3b:
                int[] r5 = new int[r7]
                int r8 = android.os.UserHandle.getUserId(r6)
                r5[r3] = r8
            L43:
                r8 = -1
                int r9 = r0.hashCode()
                r10 = 172491798(0xa480416, float:9.630418E-33)
                r11 = 2
                if (r9 == r10) goto L6c
                r10 = 525384130(0x1f50b9c2, float:4.419937E-20)
                if (r9 == r10) goto L62
                r10 = 1544582882(0x5c1076e2, float:1.6265244E17)
                if (r9 == r10) goto L59
            L58:
                goto L76
            L59:
                java.lang.String r9 = "android.intent.action.PACKAGE_ADDED"
                boolean r9 = r0.equals(r9)
                if (r9 == 0) goto L58
                goto L77
            L62:
                java.lang.String r3 = "android.intent.action.PACKAGE_REMOVED"
                boolean r3 = r0.equals(r3)
                if (r3 == 0) goto L58
                r3 = r11
                goto L77
            L6c:
                java.lang.String r3 = "android.intent.action.PACKAGE_CHANGED"
                boolean r3 = r0.equals(r3)
                if (r3 == 0) goto L58
                r3 = r7
                goto L77
            L76:
                r3 = r8
            L77:
                if (r3 == 0) goto L8c
                if (r3 == r7) goto L88
                if (r3 == r11) goto L7e
                goto L96
            L7e:
                if (r4 == 0) goto L84
                r12.onPackageReplacing(r1, r5)
                goto L96
            L84:
                r12.onPackageRemoved(r1, r5)
                goto L96
            L88:
                r12.onPackageChanged(r1, r5)
                goto L96
            L8c:
                if (r4 == 0) goto L92
                r12.onPackageReplaced(r1, r5)
                goto L96
            L92:
                r12.onPackageAdded(r1, r5)
            L96:
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.om.OverlayManagerService.PackageReceiver.onReceive(android.content.Context, android.content.Intent):void");
        }

        private void onPackageAdded(String packageName, int[] userIds) {
            try {
                Trace.traceBegin(67108864L, "OMS#onPackageAdded " + packageName);
                int length = userIds.length;
                for (int i = 0; i < length; i++) {
                    int userId = userIds[i];
                    synchronized (OverlayManagerService.this.mLock) {
                        PackageInfo pi = OverlayManagerService.this.mPackageManager.getPackageInfo(packageName, userId, false);
                        if (pi != null && !pi.applicationInfo.isInstantApp()) {
                            OverlayManagerService.this.mPackageManager.cachePackageInfo(packageName, userId, pi);
                            if (pi.isOverlayPackage()) {
                                OverlayManagerService.this.mImpl.onOverlayPackageAdded(packageName, userId);
                            } else {
                                OverlayManagerService.this.mImpl.onTargetPackageAdded(packageName, userId);
                            }
                        }
                    }
                }
            } finally {
                Trace.traceEnd(67108864L);
            }
        }

        private void onPackageChanged(String packageName, int[] userIds) {
            try {
                Trace.traceBegin(67108864L, "OMS#onPackageChanged " + packageName);
                int length = userIds.length;
                for (int i = 0; i < length; i++) {
                    int userId = userIds[i];
                    synchronized (OverlayManagerService.this.mLock) {
                        PackageInfo pi = OverlayManagerService.this.mPackageManager.getPackageInfo(packageName, userId, false);
                        if (pi != null && pi.applicationInfo.isInstantApp()) {
                            OverlayManagerService.this.mPackageManager.cachePackageInfo(packageName, userId, pi);
                            if (pi.isOverlayPackage()) {
                                OverlayManagerService.this.mImpl.onOverlayPackageChanged(packageName, userId);
                            } else {
                                OverlayManagerService.this.mImpl.onTargetPackageChanged(packageName, userId);
                            }
                        }
                    }
                }
            } finally {
                Trace.traceEnd(67108864L);
            }
        }

        private void onPackageReplacing(String packageName, int[] userIds) {
            try {
                Trace.traceBegin(67108864L, "OMS#onPackageReplacing " + packageName);
                for (int userId : userIds) {
                    synchronized (OverlayManagerService.this.mLock) {
                        OverlayManagerService.this.mPackageManager.forgetPackageInfo(packageName, userId);
                        OverlayInfo oi = OverlayManagerService.this.mImpl.getOverlayInfo(packageName, userId);
                        if (oi != null) {
                            OverlayManagerService.this.mImpl.onOverlayPackageReplacing(packageName, userId);
                        }
                    }
                }
            } finally {
                Trace.traceEnd(67108864L);
            }
        }

        private void onPackageReplaced(String packageName, int[] userIds) {
            try {
                Trace.traceBegin(67108864L, "OMS#onPackageReplaced " + packageName);
                int length = userIds.length;
                for (int i = 0; i < length; i++) {
                    int userId = userIds[i];
                    synchronized (OverlayManagerService.this.mLock) {
                        PackageInfo pi = OverlayManagerService.this.mPackageManager.getPackageInfo(packageName, userId, false);
                        if (pi != null && !pi.applicationInfo.isInstantApp()) {
                            OverlayManagerService.this.mPackageManager.cachePackageInfo(packageName, userId, pi);
                            if (pi.isOverlayPackage()) {
                                OverlayManagerService.this.mImpl.onOverlayPackageReplaced(packageName, userId);
                            } else {
                                OverlayManagerService.this.mImpl.onTargetPackageReplaced(packageName, userId);
                            }
                        }
                    }
                }
            } finally {
                Trace.traceEnd(67108864L);
            }
        }

        private void onPackageRemoved(String packageName, int[] userIds) {
            try {
                Trace.traceBegin(67108864L, "OMS#onPackageRemoved " + packageName);
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
            } finally {
                Trace.traceEnd(67108864L);
            }
        }
    }

    /* loaded from: classes.dex */
    private final class UserReceiver extends BroadcastReceiver {
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
            if (c == 0) {
                if (userId != -10000) {
                    try {
                        Trace.traceBegin(67108864L, "OMS ACTION_USER_ADDED");
                        synchronized (OverlayManagerService.this.mLock) {
                            targets = OverlayManagerService.this.mImpl.updateOverlaysForUser(userId);
                        }
                        OverlayManagerService.this.updateOverlayPaths(userId, targets);
                    } finally {
                    }
                }
            } else if (c == 1 && userId != -10000) {
                try {
                    Trace.traceBegin(67108864L, "OMS ACTION_USER_REMOVED");
                    synchronized (OverlayManagerService.this.mLock) {
                        OverlayManagerService.this.mImpl.onUserRemoved(userId);
                        OverlayManagerService.this.mPackageManager.forgetAllPackageInfos(userId);
                    }
                } finally {
                }
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
                    OverlayManagerService.OverlayChangeListener.this.lambda$onOverlaysChanged$0$OverlayManagerService$OverlayChangeListener(userId, targetPackageName);
                }
            });
        }

        public /* synthetic */ void lambda$onOverlaysChanged$0$OverlayManagerService$OverlayChangeListener(int userId, String targetPackageName) {
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
        try {
            Trace.traceBegin(67108864L, "OMS#updateOverlayPaths " + targetPackageNames);
            PackageManagerInternal pm = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
            boolean updateFrameworkRes = targetPackageNames.contains(PackageManagerService.PLATFORM_PACKAGE_NAME);
            if (updateFrameworkRes) {
                targetPackageNames = pm.getTargetPackageNames(userId);
            }
            Map<String, List<String>> pendingChanges = new ArrayMap<>(targetPackageNames.size());
            synchronized (this.mLock) {
                List<String> frameworkOverlays = this.mImpl.getEnabledOverlayPackageNames(PackageManagerService.PLATFORM_PACKAGE_NAME, userId);
                int n = targetPackageNames.size();
                for (int i = 0; i < n; i++) {
                    String targetPackageName = targetPackageNames.get(i);
                    List<String> list = new ArrayList<>();
                    if (!PackageManagerService.PLATFORM_PACKAGE_NAME.equals(targetPackageName)) {
                        list.addAll(frameworkOverlays);
                    }
                    list.addAll(this.mImpl.getEnabledOverlayPackageNames(targetPackageName, userId));
                    pendingChanges.put(targetPackageName, list);
                }
            }
            int n2 = targetPackageNames.size();
            for (int i2 = 0; i2 < n2; i2++) {
                String targetPackageName2 = targetPackageNames.get(i2);
                if (!pm.setEnabledOverlayPackages(userId, targetPackageName2, pendingChanges.get(targetPackageName2))) {
                    Slog.e(TAG, String.format("Failed to change enabled overlays for %s user %d", targetPackageName2, Integer.valueOf(userId)));
                }
            }
        } finally {
            Trace.traceEnd(67108864L);
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
        IoThread.getHandler().post(new Runnable() { // from class: com.android.server.om.-$$Lambda$OverlayManagerService$_WGEV7N0qhntbqqDW3A1O-TVv5o
            @Override // java.lang.Runnable
            public final void run() {
                OverlayManagerService.this.lambda$schedulePersistSettings$0$OverlayManagerService();
            }
        });
    }

    public /* synthetic */ void lambda$schedulePersistSettings$0$OverlayManagerService() {
        this.mPersistSettingsScheduled.set(false);
        synchronized (this.mLock) {
            FileOutputStream stream = null;
            try {
                stream = this.mSettingsFile.startWrite();
                this.mSettings.persist(stream);
                this.mSettingsFile.finishWrite(stream);
            } catch (IOException | XmlPullParserException e) {
                this.mSettingsFile.failWrite(stream);
                Slog.e(TAG, "failed to persist overlay state", e);
            }
        }
    }

    private void restoreSettings() {
        FileInputStream stream;
        int[] users;
        try {
            Trace.traceBegin(67108864L, "OMS#restoreSettings");
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
        } finally {
            Trace.traceEnd(67108864L);
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

        public void dump(PrintWriter pw, DumpState dumpState) {
            pw.println("PackageInfo cache");
            if (!dumpState.isVerbose()) {
                int count = 0;
                int n = this.mCache.size();
                for (int i = 0; i < n; i++) {
                    count += this.mCache.get(this.mCache.keyAt(i)).size();
                }
                pw.println(TAB1 + count + " package(s)");
            } else if (this.mCache.size() == 0) {
                pw.println("    <empty>");
            } else {
                int n2 = this.mCache.size();
                for (int i2 = 0; i2 < n2; i2++) {
                    int userId = this.mCache.keyAt(i2);
                    pw.println("    User " + userId);
                    HashMap<String, PackageInfo> map = this.mCache.get(userId);
                    for (Map.Entry<String, PackageInfo> entry : map.entrySet()) {
                        pw.println(TAB2 + entry.getKey() + ": " + entry.getValue());
                    }
                }
            }
        }
    }
}
