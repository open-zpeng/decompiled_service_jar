package com.android.server.pm;

import android.accounts.IAccountManager;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.dex.ISnapshotRuntimeProfileCallback;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;
import android.net.util.NetworkConstants;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.PrintWriterPrinter;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.UiModeManagerService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.voiceinteraction.DatabaseHelper;
import com.android.server.wm.WindowManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import dalvik.system.DexFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import libcore.io.IoUtils;
import libcore.io.Streams;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class PackageManagerShellCommand extends ShellCommand {
    private static final String ART_PROFILE_SNAPSHOT_DEBUG_LOCATION = "/data/misc/profman/";
    private static final String STDIN_PATH = "-";
    boolean mBrief;
    boolean mComponents;
    final IPackageManager mInterface;
    private final WeakHashMap<String, Resources> mResourceCache = new WeakHashMap<>();
    int mTargetUser;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageManagerShellCommand(PackageManagerService service) {
        this.mInterface = service;
    }

    public int onCommand(String cmd) {
        char c;
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd.hashCode()) {
                case -2102802879:
                    if (cmd.equals("set-harmful-app-warning")) {
                        c = '7';
                        break;
                    }
                    c = 65535;
                    break;
                case -1967190973:
                    if (cmd.equals("install-abandon")) {
                        c = '\b';
                        break;
                    }
                    c = 65535;
                    break;
                case -1937348290:
                    if (cmd.equals("get-install-location")) {
                        c = 16;
                        break;
                    }
                    c = 65535;
                    break;
                case -1852006340:
                    if (cmd.equals("suspend")) {
                        c = '\"';
                        break;
                    }
                    c = 65535;
                    break;
                case -1846646502:
                    if (cmd.equals("get-max-running-users")) {
                        c = '2';
                        break;
                    }
                    c = 65535;
                    break;
                case -1741208611:
                    if (cmd.equals("set-installer")) {
                        c = '4';
                        break;
                    }
                    c = 65535;
                    break;
                case -1347307837:
                    if (cmd.equals("has-feature")) {
                        c = '6';
                        break;
                    }
                    c = 65535;
                    break;
                case -1298848381:
                    if (cmd.equals(xpInputManagerService.InputPolicyKey.KEY_ENABLE)) {
                        c = 27;
                        break;
                    }
                    c = 65535;
                    break;
                case -1267782244:
                    if (cmd.equals("get-instantapp-resolver")) {
                        c = '5';
                        break;
                    }
                    c = 65535;
                    break;
                case -1231004208:
                    if (cmd.equals("resolve-activity")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case -1102348235:
                    if (cmd.equals("get-privapp-deny-permissions")) {
                        c = ')';
                        break;
                    }
                    c = 65535;
                    break;
                case -1091400553:
                    if (cmd.equals("get-oem-permissions")) {
                        c = '*';
                        break;
                    }
                    c = 65535;
                    break;
                case -1070704814:
                    if (cmd.equals("get-privapp-permissions")) {
                        c = '(';
                        break;
                    }
                    c = 65535;
                    break;
                case -1032029296:
                    if (cmd.equals("disable-user")) {
                        c = 29;
                        break;
                    }
                    c = 65535;
                    break;
                case -934343034:
                    if (cmd.equals("revoke")) {
                        c = '%';
                        break;
                    }
                    c = 65535;
                    break;
                case -919935069:
                    if (cmd.equals("dump-profiles")) {
                        c = 23;
                        break;
                    }
                    c = 65535;
                    break;
                case -840566949:
                    if (cmd.equals("unhide")) {
                        c = '!';
                        break;
                    }
                    c = 65535;
                    break;
                case -625596190:
                    if (cmd.equals("uninstall")) {
                        c = 25;
                        break;
                    }
                    c = 65535;
                    break;
                case -623224643:
                    if (cmd.equals("get-app-link")) {
                        c = ',';
                        break;
                    }
                    c = 65535;
                    break;
                case -539710980:
                    if (cmd.equals("create-user")) {
                        c = '.';
                        break;
                    }
                    c = 65535;
                    break;
                case -458695741:
                    if (cmd.equals("query-services")) {
                        c = 5;
                        break;
                    }
                    c = 65535;
                    break;
                case -444750796:
                    if (cmd.equals("bg-dexopt-job")) {
                        c = 22;
                        break;
                    }
                    c = 65535;
                    break;
                case -440994401:
                    if (cmd.equals("query-receivers")) {
                        c = 6;
                        break;
                    }
                    c = 65535;
                    break;
                case -339687564:
                    if (cmd.equals("remove-user")) {
                        c = '/';
                        break;
                    }
                    c = 65535;
                    break;
                case -220055275:
                    if (cmd.equals("set-permission-enforced")) {
                        c = '\'';
                        break;
                    }
                    c = 65535;
                    break;
                case -140205181:
                    if (cmd.equals("unsuspend")) {
                        c = '#';
                        break;
                    }
                    c = 65535;
                    break;
                case -132384343:
                    if (cmd.equals("install-commit")) {
                        c = '\n';
                        break;
                    }
                    c = 65535;
                    break;
                case -129863314:
                    if (cmd.equals("install-create")) {
                        c = 11;
                        break;
                    }
                    c = 65535;
                    break;
                case -115000827:
                    if (cmd.equals("default-state")) {
                        c = 31;
                        break;
                    }
                    c = 65535;
                    break;
                case -87258188:
                    if (cmd.equals("move-primary-storage")) {
                        c = 18;
                        break;
                    }
                    c = 65535;
                    break;
                case 3095028:
                    if (cmd.equals("dump")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case 3202370:
                    if (cmd.equals("hide")) {
                        c = ' ';
                        break;
                    }
                    c = 65535;
                    break;
                case 3322014:
                    if (cmd.equals("list")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case 3433509:
                    if (cmd.equals("path")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 18936394:
                    if (cmd.equals("move-package")) {
                        c = 17;
                        break;
                    }
                    c = 65535;
                    break;
                case 86600360:
                    if (cmd.equals("get-max-users")) {
                        c = '1';
                        break;
                    }
                    c = 65535;
                    break;
                case 94746189:
                    if (cmd.equals("clear")) {
                        c = 26;
                        break;
                    }
                    c = 65535;
                    break;
                case 98615580:
                    if (cmd.equals("grant")) {
                        c = '$';
                        break;
                    }
                    c = 65535;
                    break;
                case 107262333:
                    if (cmd.equals("install-existing")) {
                        c = 14;
                        break;
                    }
                    c = 65535;
                    break;
                case 139892533:
                    if (cmd.equals("get-harmful-app-warning")) {
                        c = '8';
                        break;
                    }
                    c = 65535;
                    break;
                case 287820022:
                    if (cmd.equals("install-remove")) {
                        c = '\f';
                        break;
                    }
                    c = 65535;
                    break;
                case 359572742:
                    if (cmd.equals("reset-permissions")) {
                        c = '&';
                        break;
                    }
                    c = 65535;
                    break;
                case 467549856:
                    if (cmd.equals("snapshot-profile")) {
                        c = 24;
                        break;
                    }
                    c = 65535;
                    break;
                case 798023112:
                    if (cmd.equals("install-destroy")) {
                        c = '\t';
                        break;
                    }
                    c = 65535;
                    break;
                case 826473335:
                    if (cmd.equals("uninstall-system-updates")) {
                        c = '9';
                        break;
                    }
                    c = 65535;
                    break;
                case 925176533:
                    if (cmd.equals("set-user-restriction")) {
                        c = '0';
                        break;
                    }
                    c = 65535;
                    break;
                case 925767985:
                    if (cmd.equals("set-app-link")) {
                        c = '+';
                        break;
                    }
                    c = 65535;
                    break;
                case 950491699:
                    if (cmd.equals("compile")) {
                        c = 19;
                        break;
                    }
                    c = 65535;
                    break;
                case 1053409810:
                    if (cmd.equals("query-activities")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case 1124603675:
                    if (cmd.equals("force-dex-opt")) {
                        c = 21;
                        break;
                    }
                    c = 65535;
                    break;
                case 1177857340:
                    if (cmd.equals("trim-caches")) {
                        c = '-';
                        break;
                    }
                    c = 65535;
                    break;
                case 1429366290:
                    if (cmd.equals("set-home-activity")) {
                        c = '3';
                        break;
                    }
                    c = 65535;
                    break;
                case 1538306349:
                    if (cmd.equals("install-write")) {
                        c = '\r';
                        break;
                    }
                    c = 65535;
                    break;
                case 1671308008:
                    if (cmd.equals("disable")) {
                        c = 28;
                        break;
                    }
                    c = 65535;
                    break;
                case 1697997009:
                    if (cmd.equals("disable-until-used")) {
                        c = 30;
                        break;
                    }
                    c = 65535;
                    break;
                case 1746695602:
                    if (cmd.equals("set-install-location")) {
                        c = 15;
                        break;
                    }
                    c = 65535;
                    break;
                case 1783979817:
                    if (cmd.equals("reconcile-secondary-dex-files")) {
                        c = 20;
                        break;
                    }
                    c = 65535;
                    break;
                case 1957569947:
                    if (cmd.equals("install")) {
                        c = 7;
                        break;
                    }
                    c = 65535;
                    break;
                default:
                    c = 65535;
                    break;
            }
            switch (c) {
                case 0:
                    return runPath();
                case 1:
                    return runDump();
                case 2:
                    return runList();
                case 3:
                    return runResolveActivity();
                case 4:
                    return runQueryIntentActivities();
                case 5:
                    return runQueryIntentServices();
                case 6:
                    return runQueryIntentReceivers();
                case 7:
                    return runInstall();
                case '\b':
                case '\t':
                    return runInstallAbandon();
                case '\n':
                    return runInstallCommit();
                case 11:
                    return runInstallCreate();
                case '\f':
                    return runInstallRemove();
                case '\r':
                    return runInstallWrite();
                case 14:
                    return runInstallExisting();
                case 15:
                    return runSetInstallLocation();
                case 16:
                    return runGetInstallLocation();
                case 17:
                    return runMovePackage();
                case 18:
                    return runMovePrimaryStorage();
                case WindowManagerService.H.REPORT_WINDOWS_CHANGE /* 19 */:
                    return runCompile();
                case 20:
                    return runreconcileSecondaryDexFiles();
                case BackupHandler.MSG_OP_COMPLETE /* 21 */:
                    return runForceDexOpt();
                case WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE /* 22 */:
                    return runDexoptJob();
                case WindowManagerService.H.BOOT_TIMEOUT /* 23 */:
                    return runDumpProfiles();
                case 24:
                    return runSnapshotProfile();
                case WindowManagerService.H.SHOW_STRICT_MODE_VIOLATION /* 25 */:
                    return runUninstall();
                case WindowManagerService.H.DO_ANIMATION_CALLBACK /* 26 */:
                    return runClear();
                case 27:
                    return runSetEnabledSetting(1);
                case NetworkConstants.ARP_PAYLOAD_LEN /* 28 */:
                    return runSetEnabledSetting(2);
                case HdmiCecKeycode.CEC_KEYCODE_NUMBER_ENTRY_MODE /* 29 */:
                    return runSetEnabledSetting(3);
                case 30:
                    return runSetEnabledSetting(4);
                case HdmiCecKeycode.CEC_KEYCODE_NUMBER_12 /* 31 */:
                    return runSetEnabledSetting(0);
                case ' ':
                    return runSetHiddenSetting(true);
                case '!':
                    return runSetHiddenSetting(false);
                case '\"':
                    return runSuspend(true);
                case '#':
                    return runSuspend(false);
                case '$':
                    return runGrantRevokePermission(true);
                case '%':
                    return runGrantRevokePermission(false);
                case '&':
                    return runResetPermissions();
                case '\'':
                    return runSetPermissionEnforced();
                case '(':
                    return runGetPrivappPermissions();
                case ')':
                    return runGetPrivappDenyPermissions();
                case HdmiCecKeycode.CEC_KEYCODE_DOT /* 42 */:
                    return runGetOemPermissions();
                case HdmiCecKeycode.CEC_KEYCODE_ENTER /* 43 */:
                    return runSetAppLink();
                case HdmiCecKeycode.CEC_KEYCODE_CLEAR /* 44 */:
                    return runGetAppLink();
                case NetworkPolicyManagerService.TYPE_RAPID /* 45 */:
                    return runTrimCaches();
                case WindowManagerService.H.WINDOW_REPLACEMENT_TIMEOUT /* 46 */:
                    return runCreateUser();
                case '/':
                    return runRemoveUser();
                case '0':
                    return runSetUserRestriction();
                case '1':
                    return runGetMaxUsers();
                case HdmiCecKeycode.CEC_KEYCODE_PREVIOUS_CHANNEL /* 50 */:
                    return runGetMaxRunningUsers();
                case '3':
                    return runSetHomeActivity();
                case '4':
                    return runSetInstaller();
                case '5':
                    return runGetInstantAppResolver();
                case '6':
                    return runHasFeature();
                case '7':
                    return runSetHarmfulAppWarning();
                case '8':
                    return runGetHarmfulAppWarning();
                case WindowManagerService.H.NOTIFY_KEYGUARD_TRUSTED_CHANGED /* 57 */:
                    return uninstallSystemUpdates();
                default:
                    String nextArg = getNextArg();
                    if (nextArg == null) {
                        if (cmd.equalsIgnoreCase("-l")) {
                            return runListPackages(false);
                        }
                        if (cmd.equalsIgnoreCase("-lf")) {
                            return runListPackages(true);
                        }
                    } else if (getNextArg() == null && cmd.equalsIgnoreCase("-p")) {
                        return displayPackageFilePath(nextArg, 0);
                    }
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
            return -1;
        }
    }

    private int uninstallSystemUpdates() {
        PrintWriter pw = getOutPrintWriter();
        List<String> failedUninstalls = new LinkedList<>();
        try {
            ParceledListSlice<ApplicationInfo> packages = this.mInterface.getInstalledApplications(1048576, 0);
            IPackageInstaller installer = this.mInterface.getPackageInstaller();
            List<ApplicationInfo> list = packages.getList();
            for (ApplicationInfo info : list) {
                if (info.isUpdatedSystemApp()) {
                    pw.println("Uninstalling updates to " + info.packageName + "...");
                    LocalIntentReceiver receiver = new LocalIntentReceiver();
                    installer.uninstall(new VersionedPackage(info.packageName, info.versionCode), (String) null, 0, receiver.getIntentSender(), 0);
                    Intent result = receiver.getResult();
                    int status = result.getIntExtra("android.content.pm.extra.STATUS", 1);
                    if (status != 0) {
                        failedUninstalls.add(info.packageName);
                    }
                }
            }
            if (!failedUninstalls.isEmpty()) {
                pw.println("Failure [Couldn't uninstall packages: " + TextUtils.join(", ", failedUninstalls) + "]");
                return 0;
            }
            pw.println("Success");
            return 1;
        } catch (RemoteException e) {
            pw.println("Failure [" + e.getClass().getName() + " - " + e.getMessage() + "]");
            return 0;
        }
    }

    private void setParamsSize(InstallParams params, String inPath) {
        if (params.sessionParams.sizeBytes == -1 && !STDIN_PATH.equals(inPath)) {
            ParcelFileDescriptor fd = openFileForSystem(inPath, "r");
            if (fd == null) {
                PrintWriter errPrintWriter = getErrPrintWriter();
                errPrintWriter.println("Error: Can't open file: " + inPath);
                throw new IllegalArgumentException("Error: Can't open file: " + inPath);
            }
            try {
                try {
                    PackageParser.ApkLite baseApk = PackageParser.parseApkLite(fd.getFileDescriptor(), inPath, 0);
                    PackageParser.PackageLite pkgLite = new PackageParser.PackageLite((String) null, baseApk, (String[]) null, (boolean[]) null, (String[]) null, (String[]) null, (String[]) null, (int[]) null);
                    params.sessionParams.setSize(PackageHelper.calculateInstalledSize(pkgLite, params.sessionParams.abiOverride, fd.getFileDescriptor()));
                    try {
                        fd.close();
                    } catch (IOException e) {
                    }
                } catch (PackageParser.PackageParserException | IOException e2) {
                    PrintWriter errPrintWriter2 = getErrPrintWriter();
                    errPrintWriter2.println("Error: Failed to parse APK file: " + inPath);
                    throw new IllegalArgumentException("Error: Failed to parse APK file: " + inPath, e2);
                }
            } catch (Throwable th) {
                try {
                    fd.close();
                } catch (IOException e3) {
                }
                throw th;
            }
        }
    }

    private int displayPackageFilePath(String pckg, int userId) throws RemoteException {
        String[] strArr;
        PackageInfo info = this.mInterface.getPackageInfo(pckg, 0, userId);
        if (info != null && info.applicationInfo != null) {
            PrintWriter pw = getOutPrintWriter();
            pw.print("package:");
            pw.println(info.applicationInfo.sourceDir);
            if (!ArrayUtils.isEmpty(info.applicationInfo.splitSourceDirs)) {
                for (String splitSourceDir : info.applicationInfo.splitSourceDirs) {
                    pw.print("package:");
                    pw.println(splitSourceDir);
                }
            }
            return 0;
        }
        return 1;
    }

    private int runPath() throws RemoteException {
        int userId = 0;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }
        String pkg = getNextArgRequired();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        return displayPackageFilePath(pkg, userId);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private int runList() throws RemoteException {
        char c;
        PrintWriter pw = getOutPrintWriter();
        String type = getNextArg();
        if (type != null) {
            switch (type.hashCode()) {
                case -997447790:
                    if (type.equals("permission-groups")) {
                        c = 5;
                        break;
                    }
                    c = 65535;
                    break;
                case -807062458:
                    if (type.equals("package")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case -290659267:
                    if (type.equals("features")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 111578632:
                    if (type.equals(DatabaseHelper.SoundModelContract.KEY_USERS)) {
                        c = 7;
                        break;
                    }
                    c = 65535;
                    break;
                case 544550766:
                    if (type.equals("instrumentation")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case 750867693:
                    if (type.equals("packages")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case 812757657:
                    if (type.equals("libraries")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case 1133704324:
                    if (type.equals("permissions")) {
                        c = 6;
                        break;
                    }
                    c = 65535;
                    break;
                default:
                    c = 65535;
                    break;
            }
            switch (c) {
                case 0:
                    return runListFeatures();
                case 1:
                    return runListInstrumentation();
                case 2:
                    return runListLibraries();
                case 3:
                case 4:
                    return runListPackages(false);
                case 5:
                    return runListPermissionGroups();
                case 6:
                    return runListPermissions();
                case 7:
                    ServiceManager.getService("user").shellCommand(getInFileDescriptor(), getOutFileDescriptor(), getErrFileDescriptor(), new String[]{"list"}, getShellCallback(), adoptResultReceiver());
                    return 0;
                default:
                    pw.println("Error: unknown list type '" + type + "'");
                    return -1;
            }
        }
        pw.println("Error: didn't specify type of data to list");
        return -1;
    }

    private int runListFeatures() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        List<FeatureInfo> list = this.mInterface.getSystemAvailableFeatures().getList();
        Collections.sort(list, new Comparator<FeatureInfo>() { // from class: com.android.server.pm.PackageManagerShellCommand.1
            @Override // java.util.Comparator
            public int compare(FeatureInfo o1, FeatureInfo o2) {
                if (o1.name == o2.name) {
                    return 0;
                }
                if (o1.name == null) {
                    return -1;
                }
                if (o2.name == null) {
                    return 1;
                }
                return o1.name.compareTo(o2.name);
            }
        });
        int count = list != null ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            FeatureInfo fi = list.get(p);
            pw.print("feature:");
            if (fi.name != null) {
                pw.print(fi.name);
                if (fi.version > 0) {
                    pw.print("=");
                    pw.print(fi.version);
                }
                pw.println();
            } else {
                pw.println("reqGlEsVersion=0x" + Integer.toHexString(fi.reqGlEsVersion));
            }
        }
        return 0;
    }

    /* JADX WARN: Removed duplicated region for block: B:37:0x0044 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:40:0x0025 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int runListInstrumentation() throws android.os.RemoteException {
        /*
            r11 = this;
            java.io.PrintWriter r0 = r11.getOutPrintWriter()
            r1 = 0
            r2 = 0
        L6:
            r3 = -1
            java.lang.String r4 = r11.getNextArg()     // Catch: java.lang.RuntimeException -> L9f
            r5 = r4
            r6 = 0
            if (r4 == 0) goto L47
            int r4 = r5.hashCode()     // Catch: java.lang.RuntimeException -> L9f
            r7 = 1497(0x5d9, float:2.098E-42)
            if (r4 == r7) goto L18
            goto L22
        L18:
            java.lang.String r4 = "-f"
            boolean r4 = r5.equals(r4)     // Catch: java.lang.RuntimeException -> L9f
            if (r4 == 0) goto L22
            r4 = r6
            goto L23
        L22:
            r4 = r3
        L23:
            if (r4 == 0) goto L44
            char r4 = r5.charAt(r6)     // Catch: java.lang.RuntimeException -> L9f
            r6 = 45
            if (r4 == r6) goto L2f
            r2 = r5
            goto L46
        L2f:
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.RuntimeException -> L9f
            r4.<init>()     // Catch: java.lang.RuntimeException -> L9f
            java.lang.String r6 = "Error: Unknown option: "
            r4.append(r6)     // Catch: java.lang.RuntimeException -> L9f
            r4.append(r5)     // Catch: java.lang.RuntimeException -> L9f
            java.lang.String r4 = r4.toString()     // Catch: java.lang.RuntimeException -> L9f
            r0.println(r4)     // Catch: java.lang.RuntimeException -> L9f
            return r3
        L44:
            r1 = 1
        L46:
            goto L6
        L47:
            android.content.pm.IPackageManager r3 = r11.mInterface
            android.content.pm.ParceledListSlice r3 = r3.queryInstrumentation(r2, r6)
            java.util.List r3 = r3.getList()
            com.android.server.pm.PackageManagerShellCommand$2 r4 = new com.android.server.pm.PackageManagerShellCommand$2
            r4.<init>()
            java.util.Collections.sort(r3, r4)
            if (r3 == 0) goto L61
            int r4 = r3.size()
            goto L62
        L61:
            r4 = r6
        L62:
            r5 = r6
        L63:
            if (r5 >= r4) goto L9e
            java.lang.Object r7 = r3.get(r5)
            android.content.pm.InstrumentationInfo r7 = (android.content.pm.InstrumentationInfo) r7
            java.lang.String r8 = "instrumentation:"
            r0.print(r8)
            if (r1 == 0) goto L7c
            java.lang.String r8 = r7.sourceDir
            r0.print(r8)
            java.lang.String r8 = "="
            r0.print(r8)
        L7c:
            android.content.ComponentName r8 = new android.content.ComponentName
            java.lang.String r9 = r7.packageName
            java.lang.String r10 = r7.name
            r8.<init>(r9, r10)
            java.lang.String r9 = r8.flattenToShortString()
            r0.print(r9)
            java.lang.String r9 = " (target="
            r0.print(r9)
            java.lang.String r9 = r7.targetPackage
            r0.print(r9)
            java.lang.String r9 = ")"
            r0.println(r9)
            int r5 = r5 + 1
            goto L63
        L9e:
            return r6
        L9f:
            r4 = move-exception
            java.lang.StringBuilder r5 = new java.lang.StringBuilder
            r5.<init>()
            java.lang.String r6 = "Error: "
            r5.append(r6)
            java.lang.String r6 = r4.toString()
            r5.append(r6)
            java.lang.String r5 = r5.toString()
            r0.println(r5)
            return r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerShellCommand.runListInstrumentation():int");
    }

    private int runListLibraries() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        List<String> list = new ArrayList<>();
        String[] rawList = this.mInterface.getSystemSharedLibraryNames();
        for (String str : rawList) {
            list.add(str);
        }
        Collections.sort(list, new Comparator<String>() { // from class: com.android.server.pm.PackageManagerShellCommand.3
            @Override // java.util.Comparator
            public int compare(String o1, String o2) {
                if (o1 == o2) {
                    return 0;
                }
                if (o1 == null) {
                    return -1;
                }
                if (o2 == null) {
                    return 1;
                }
                return o1.compareTo(o2);
            }
        });
        int count = list.size();
        for (int p = 0; p < count; p++) {
            String lib = list.get(p);
            pw.print("library:");
            pw.println(lib);
        }
        return 0;
    }

    /* JADX WARN: Code restructure failed: missing block: B:91:0x015c, code lost:
        if (r3.packageName.contains(r0) == false) goto L110;
     */
    /* JADX WARN: Removed duplicated region for block: B:138:0x00db A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:67:0x00de A[Catch: RuntimeException -> 0x0122, TryCatch #1 {RuntimeException -> 0x0122, blocks: (B:8:0x0028, B:26:0x0053, B:65:0x00d8, B:66:0x00db, B:80:0x010e, B:67:0x00de, B:68:0x00ea, B:71:0x00fb, B:28:0x0058, B:31:0x0063, B:34:0x006f, B:37:0x0079, B:40:0x0084, B:43:0x008f, B:46:0x0099, B:49:0x00a3, B:52:0x00ad, B:55:0x00b7, B:58:0x00c1, B:61:0x00cc), top: B:135:0x0028 }] */
    /* JADX WARN: Removed duplicated region for block: B:68:0x00ea A[Catch: RuntimeException -> 0x0122, TryCatch #1 {RuntimeException -> 0x0122, blocks: (B:8:0x0028, B:26:0x0053, B:65:0x00d8, B:66:0x00db, B:80:0x010e, B:67:0x00de, B:68:0x00ea, B:71:0x00fb, B:28:0x0058, B:31:0x0063, B:34:0x006f, B:37:0x0079, B:40:0x0084, B:43:0x008f, B:46:0x0099, B:49:0x00a3, B:52:0x00ad, B:55:0x00b7, B:58:0x00c1, B:61:0x00cc), top: B:135:0x0028 }] */
    /* JADX WARN: Removed duplicated region for block: B:69:0x00f5  */
    /* JADX WARN: Removed duplicated region for block: B:70:0x00f9  */
    /* JADX WARN: Removed duplicated region for block: B:71:0x00fb A[Catch: RuntimeException -> 0x0122, TryCatch #1 {RuntimeException -> 0x0122, blocks: (B:8:0x0028, B:26:0x0053, B:65:0x00d8, B:66:0x00db, B:80:0x010e, B:67:0x00de, B:68:0x00ea, B:71:0x00fb, B:28:0x0058, B:31:0x0063, B:34:0x006f, B:37:0x0079, B:40:0x0084, B:43:0x008f, B:46:0x0099, B:49:0x00a3, B:52:0x00ad, B:55:0x00b7, B:58:0x00c1, B:61:0x00cc), top: B:135:0x0028 }] */
    /* JADX WARN: Removed duplicated region for block: B:72:0x00fe  */
    /* JADX WARN: Removed duplicated region for block: B:73:0x0100  */
    /* JADX WARN: Removed duplicated region for block: B:74:0x0102  */
    /* JADX WARN: Removed duplicated region for block: B:75:0x0103  */
    /* JADX WARN: Removed duplicated region for block: B:76:0x0105  */
    /* JADX WARN: Removed duplicated region for block: B:77:0x0107  */
    /* JADX WARN: Removed duplicated region for block: B:78:0x0109  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int runListPackages(boolean r26) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 574
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerShellCommand.runListPackages(boolean):int");
    }

    private int runListPermissionGroups() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        List<PermissionGroupInfo> pgs = this.mInterface.getAllPermissionGroups(0).getList();
        int count = pgs.size();
        for (int p = 0; p < count; p++) {
            PermissionGroupInfo pgi = pgs.get(p);
            pw.print("permission group:");
            pw.println(pgi.name);
        }
        return 0;
    }

    private int runListPermissions() throws RemoteException {
        PackageManagerShellCommand packageManagerShellCommand;
        boolean labels;
        PrintWriter pw = getOutPrintWriter();
        boolean groups = false;
        boolean userOnly = false;
        boolean summary = false;
        boolean labels2 = false;
        boolean labels3 = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                char c = 65535;
                int hashCode = opt.hashCode();
                if (hashCode != 1495) {
                    if (hashCode != 1510) {
                        if (hashCode != 1512) {
                            switch (hashCode) {
                                case 1497:
                                    if (opt.equals("-f")) {
                                        c = 1;
                                        break;
                                    }
                                    break;
                                case 1498:
                                    if (opt.equals("-g")) {
                                        c = 2;
                                        break;
                                    }
                                    break;
                            }
                        } else if (opt.equals("-u")) {
                            c = 4;
                        }
                    } else if (opt.equals("-s")) {
                        c = 3;
                    }
                } else if (opt.equals("-d")) {
                    c = 0;
                }
                switch (c) {
                    case 0:
                        labels3 = true;
                        continue;
                    case 1:
                        labels = true;
                        break;
                    case 2:
                        groups = true;
                        continue;
                    case 3:
                        groups = true;
                        labels = true;
                        summary = true;
                        break;
                    case 4:
                        userOnly = true;
                        continue;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return 1;
                }
                labels2 = labels;
            } else {
                ArrayList<String> groupList = new ArrayList<>();
                if (groups) {
                    packageManagerShellCommand = this;
                    List<PermissionGroupInfo> infos = packageManagerShellCommand.mInterface.getAllPermissionGroups(0).getList();
                    int count = infos.size();
                    for (int i = 0; i < count; i++) {
                        groupList.add(infos.get(i).name);
                    }
                    groupList.add(null);
                } else {
                    packageManagerShellCommand = this;
                    String grp = getNextArg();
                    groupList.add(grp);
                }
                if (labels3) {
                    pw.println("Dangerous Permissions:");
                    pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    packageManagerShellCommand.doListPermissions(groupList, groups, labels2, summary, 1, 1);
                    if (userOnly) {
                        pw.println("Normal Permissions:");
                        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                        doListPermissions(groupList, groups, labels2, summary, 0, 0);
                    }
                } else if (userOnly) {
                    pw.println("Dangerous and Normal Permissions:");
                    pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    doListPermissions(groupList, groups, labels2, summary, 0, 1);
                } else {
                    pw.println("All Permissions:");
                    pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    doListPermissions(groupList, groups, labels2, summary, -10000, 10000);
                }
                return 0;
            }
        }
    }

    private Intent parseIntentAndUser() throws URISyntaxException {
        this.mTargetUser = -2;
        this.mBrief = false;
        this.mComponents = false;
        Intent intent = Intent.parseCommandArgs(this, new Intent.CommandOptionHandler() { // from class: com.android.server.pm.PackageManagerShellCommand.4
            public boolean handleOption(String opt, ShellCommand cmd) {
                if ("--user".equals(opt)) {
                    PackageManagerShellCommand.this.mTargetUser = UserHandle.parseUserArg(cmd.getNextArgRequired());
                    return true;
                } else if ("--brief".equals(opt)) {
                    PackageManagerShellCommand.this.mBrief = true;
                    return true;
                } else if ("--components".equals(opt)) {
                    PackageManagerShellCommand.this.mComponents = true;
                    return true;
                } else {
                    return false;
                }
            }
        });
        this.mTargetUser = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), this.mTargetUser, false, false, null, null);
        return intent;
    }

    private void printResolveInfo(PrintWriterPrinter pr, String prefix, ResolveInfo ri, boolean brief, boolean components) {
        ComponentName comp;
        if (brief || components) {
            if (ri.activityInfo != null) {
                comp = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            } else if (ri.serviceInfo != null) {
                comp = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            } else if (ri.providerInfo != null) {
                comp = new ComponentName(ri.providerInfo.packageName, ri.providerInfo.name);
            } else {
                comp = null;
            }
            if (comp != null) {
                if (!components) {
                    pr.println(prefix + "priority=" + ri.priority + " preferredOrder=" + ri.preferredOrder + " match=0x" + Integer.toHexString(ri.match) + " specificIndex=" + ri.specificIndex + " isDefault=" + ri.isDefault);
                }
                pr.println(prefix + comp.flattenToShortString());
                return;
            }
        }
        ri.dump(pr, prefix);
    }

    private int runResolveActivity() {
        try {
            Intent intent = parseIntentAndUser();
            try {
                ResolveInfo ri = this.mInterface.resolveIntent(intent, intent.getType(), 0, this.mTargetUser);
                PrintWriter pw = getOutPrintWriter();
                if (ri == null) {
                    pw.println("No activity found");
                } else {
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    printResolveInfo(pr, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, ri, this.mBrief, this.mComponents);
                }
                return 0;
            } catch (RemoteException e) {
                throw new RuntimeException("Failed calling service", e);
            }
        } catch (URISyntaxException e2) {
            throw new RuntimeException(e2.getMessage(), e2);
        }
    }

    private int runQueryIntentActivities() {
        try {
            Intent intent = parseIntentAndUser();
            try {
                List<ResolveInfo> result = this.mInterface.queryIntentActivities(intent, intent.getType(), 0, this.mTargetUser).getList();
                PrintWriter pw = getOutPrintWriter();
                if (result != null && result.size() > 0) {
                    if (!this.mComponents) {
                        pw.print(result.size());
                        pw.println(" activities found:");
                        PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                        for (int i = 0; i < result.size(); i++) {
                            pw.print("  Activity #");
                            pw.print(i);
                            pw.println(":");
                            printResolveInfo(pr, "    ", result.get(i), this.mBrief, this.mComponents);
                        }
                    } else {
                        PrintWriterPrinter pr2 = new PrintWriterPrinter(pw);
                        for (int i2 = 0; i2 < result.size(); i2++) {
                            printResolveInfo(pr2, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, result.get(i2), this.mBrief, this.mComponents);
                        }
                    }
                    return 0;
                }
                pw.println("No activities found");
                return 0;
            } catch (RemoteException e) {
                throw new RuntimeException("Failed calling service", e);
            }
        } catch (URISyntaxException e2) {
            throw new RuntimeException(e2.getMessage(), e2);
        }
    }

    private int runQueryIntentServices() {
        try {
            Intent intent = parseIntentAndUser();
            try {
                List<ResolveInfo> result = this.mInterface.queryIntentServices(intent, intent.getType(), 0, this.mTargetUser).getList();
                PrintWriter pw = getOutPrintWriter();
                if (result != null && result.size() > 0) {
                    if (!this.mComponents) {
                        pw.print(result.size());
                        pw.println(" services found:");
                        PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                        for (int i = 0; i < result.size(); i++) {
                            pw.print("  Service #");
                            pw.print(i);
                            pw.println(":");
                            printResolveInfo(pr, "    ", result.get(i), this.mBrief, this.mComponents);
                        }
                    } else {
                        PrintWriterPrinter pr2 = new PrintWriterPrinter(pw);
                        for (int i2 = 0; i2 < result.size(); i2++) {
                            printResolveInfo(pr2, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, result.get(i2), this.mBrief, this.mComponents);
                        }
                    }
                    return 0;
                }
                pw.println("No services found");
                return 0;
            } catch (RemoteException e) {
                throw new RuntimeException("Failed calling service", e);
            }
        } catch (URISyntaxException e2) {
            throw new RuntimeException(e2.getMessage(), e2);
        }
    }

    private int runQueryIntentReceivers() {
        try {
            Intent intent = parseIntentAndUser();
            try {
                List<ResolveInfo> result = this.mInterface.queryIntentReceivers(intent, intent.getType(), 0, this.mTargetUser).getList();
                PrintWriter pw = getOutPrintWriter();
                if (result != null && result.size() > 0) {
                    if (!this.mComponents) {
                        pw.print(result.size());
                        pw.println(" receivers found:");
                        PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                        for (int i = 0; i < result.size(); i++) {
                            pw.print("  Receiver #");
                            pw.print(i);
                            pw.println(":");
                            printResolveInfo(pr, "    ", result.get(i), this.mBrief, this.mComponents);
                        }
                    } else {
                        PrintWriterPrinter pr2 = new PrintWriterPrinter(pw);
                        for (int i2 = 0; i2 < result.size(); i2++) {
                            printResolveInfo(pr2, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, result.get(i2), this.mBrief, this.mComponents);
                        }
                    }
                    return 0;
                }
                pw.println("No receivers found");
                return 0;
            } catch (RemoteException e) {
                throw new RuntimeException("Failed calling service", e);
            }
        } catch (URISyntaxException e2) {
            throw new RuntimeException(e2.getMessage(), e2);
        }
    }

    private int runInstall() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        InstallParams params = makeInstallParams();
        String inPath = getNextArg();
        setParamsSize(params, inPath);
        int sessionId = doCreateSession(params.sessionParams, params.installerPackageName, params.userId);
        if (inPath == null) {
            try {
                if (params.sessionParams.sizeBytes == -1) {
                    pw.println("Error: must either specify a package size or an APK file");
                    return 1;
                }
            } finally {
                if (1 != 0) {
                    try {
                        doAbandonSession(sessionId, false);
                    } catch (Exception e) {
                    }
                }
            }
        }
        if (doWriteSplit(sessionId, inPath, params.sessionParams.sizeBytes, "base.apk", false) != 0) {
            if (1 != 0) {
                try {
                    doAbandonSession(sessionId, false);
                } catch (Exception e2) {
                }
            }
            return 1;
        } else if (doCommitSession(sessionId, false) != 0) {
            if (1 != 0) {
                try {
                    doAbandonSession(sessionId, false);
                } catch (Exception e3) {
                }
            }
            return 1;
        } else {
            pw.println("Success");
            if (0 != 0) {
                try {
                    doAbandonSession(sessionId, false);
                } catch (Exception e4) {
                }
            }
            return 0;
        }
    }

    private int runInstallAbandon() throws RemoteException {
        int sessionId = Integer.parseInt(getNextArg());
        return doAbandonSession(sessionId, true);
    }

    private int runInstallCommit() throws RemoteException {
        int sessionId = Integer.parseInt(getNextArg());
        return doCommitSession(sessionId, true);
    }

    private int runInstallCreate() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        InstallParams installParams = makeInstallParams();
        int sessionId = doCreateSession(installParams.sessionParams, installParams.installerPackageName, installParams.userId);
        pw.println("Success: created install session [" + sessionId + "]");
        return 0;
    }

    private int runInstallWrite() throws RemoteException {
        long sizeBytes = -1;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("-S")) {
                    sizeBytes = Long.parseLong(getNextArg());
                } else {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            } else {
                int sessionId = Integer.parseInt(getNextArg());
                String splitName = getNextArg();
                String path = getNextArg();
                return doWriteSplit(sessionId, path, sizeBytes, splitName, true);
            }
        }
    }

    private int runInstallRemove() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        int sessionId = Integer.parseInt(getNextArg());
        String splitName = getNextArg();
        if (splitName == null) {
            pw.println("Error: split name not specified");
            return 1;
        }
        return doRemoveSplit(sessionId, splitName, true);
    }

    private int runInstallExisting() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        int userId = 0;
        int userId2 = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                char c = 65535;
                int hashCode = opt.hashCode();
                if (hashCode != -951415743) {
                    if (hashCode != 1051781117) {
                        if (hashCode != 1333024815) {
                            if (hashCode == 1333469547 && opt.equals("--user")) {
                                c = 0;
                            }
                        } else if (opt.equals("--full")) {
                            c = 3;
                        }
                    } else if (opt.equals("--ephemeral")) {
                        c = 1;
                    }
                } else if (opt.equals("--instant")) {
                    c = 2;
                }
                switch (c) {
                    case 0:
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    case 1:
                    case 2:
                        int installFlags = userId2 | 2048;
                        userId2 = installFlags & (-16385);
                        break;
                    case 3:
                        int installFlags2 = userId2 & (-2049);
                        userId2 = installFlags2 | 16384;
                        break;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return 1;
                }
            } else {
                String packageName = getNextArg();
                if (packageName != null) {
                    try {
                        int res = this.mInterface.installExistingPackageAsUser(packageName, userId, userId2, 0);
                        if (res == -3) {
                            throw new PackageManager.NameNotFoundException("Package " + packageName + " doesn't exist");
                        }
                        pw.println("Package " + packageName + " installed for user: " + userId);
                        return 0;
                    } catch (PackageManager.NameNotFoundException | RemoteException e) {
                        pw.println(e.toString());
                        return 1;
                    }
                }
                pw.println("Error: package name not specified");
                return 1;
            }
        }
    }

    private int runSetInstallLocation() throws RemoteException {
        String arg = getNextArg();
        if (arg == null) {
            getErrPrintWriter().println("Error: no install location specified.");
            return 1;
        }
        try {
            int loc = Integer.parseInt(arg);
            if (!this.mInterface.setInstallLocation(loc)) {
                getErrPrintWriter().println("Error: install location has to be a number.");
                return 1;
            }
            return 0;
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: install location has to be a number.");
            return 1;
        }
    }

    private int runGetInstallLocation() throws RemoteException {
        int loc = this.mInterface.getInstallLocation();
        String locStr = "invalid";
        if (loc == 0) {
            locStr = UiModeManagerService.Shell.NIGHT_MODE_STR_AUTO;
        } else if (loc == 1) {
            locStr = "internal";
        } else if (loc == 2) {
            locStr = "external";
        }
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println(loc + "[" + locStr + "]");
        return 0;
    }

    public int runMovePackage() throws RemoteException {
        String packageName = getNextArg();
        if (packageName == null) {
            getErrPrintWriter().println("Error: package name not specified");
            return 1;
        }
        String volumeUuid = getNextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }
        int moveId = this.mInterface.movePackage(packageName, volumeUuid);
        int status = this.mInterface.getMoveStatus(moveId);
        while (!PackageManager.isMoveStatusFinished(status)) {
            SystemClock.sleep(1000L);
            status = this.mInterface.getMoveStatus(moveId);
        }
        if (status == -100) {
            getOutPrintWriter().println("Success");
            return 0;
        }
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Failure [" + status + "]");
        return 1;
    }

    public int runMovePrimaryStorage() throws RemoteException {
        String volumeUuid = getNextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }
        int moveId = this.mInterface.movePrimaryStorage(volumeUuid);
        int status = this.mInterface.getMoveStatus(moveId);
        while (!PackageManager.isMoveStatusFinished(status)) {
            SystemClock.sleep(1000L);
            status = this.mInterface.getMoveStatus(moveId);
        }
        if (status == -100) {
            getOutPrintWriter().println("Success");
            return 0;
        }
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Failure [" + status + "]");
        return 1;
    }

    private int runCompile() throws RemoteException {
        String targetCompilerFilter;
        int i;
        List<String> packageNames;
        boolean allPackages;
        int index;
        List<String> packageNames2;
        List<String> failedPackages;
        int i2;
        String opt;
        boolean result;
        char c;
        boolean secondaryDex;
        PackageManagerShellCommand packageManagerShellCommand = this;
        PrintWriter pw = getOutPrintWriter();
        boolean checkProfiles = SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false);
        boolean forceCompilation = false;
        boolean allPackages2 = false;
        boolean clearProfileData = false;
        String compilerFilter = null;
        String compilationReason = null;
        String checkProfilesRaw = null;
        boolean secondaryDex2 = false;
        String split = null;
        while (true) {
            String nextOption = getNextOption();
            String opt2 = nextOption;
            if (nextOption == null) {
                if (checkProfilesRaw != null) {
                    if ("true".equals(checkProfilesRaw)) {
                        checkProfiles = true;
                    } else if (!"false".equals(checkProfilesRaw)) {
                        pw.println("Invalid value for \"--check-prof\". Expected \"true\" or \"false\".");
                        return 1;
                    } else {
                        checkProfiles = false;
                    }
                }
                if (compilerFilter != null && compilationReason != null) {
                    pw.println("Cannot use compilation filter (\"-m\") and compilation reason (\"-r\") at the same time");
                    return 1;
                } else if (compilerFilter == null && compilationReason == null) {
                    pw.println("Cannot run without any of compilation filter (\"-m\") and compilation reason (\"-r\") at the same time");
                    return 1;
                } else if (allPackages2 && split != null) {
                    pw.println("-a cannot be specified together with --split");
                    return 1;
                } else if (secondaryDex2 && split != null) {
                    pw.println("--secondary-dex cannot be specified together with --split");
                    return 1;
                } else {
                    if (compilerFilter == null) {
                        int reason = -1;
                        int i3 = 0;
                        while (true) {
                            if (i3 < PackageManagerServiceCompilerMapping.REASON_STRINGS.length) {
                                if (PackageManagerServiceCompilerMapping.REASON_STRINGS[i3].equals(compilationReason)) {
                                    reason = i3;
                                } else {
                                    i3++;
                                }
                            }
                        }
                        if (reason == -1) {
                            pw.println("Error: Unknown compilation reason: " + compilationReason);
                            return 1;
                        }
                        targetCompilerFilter = PackageManagerServiceCompilerMapping.getCompilerFilterForReason(reason);
                    } else if (!DexFile.isValidCompilerFilter(compilerFilter)) {
                        pw.println("Error: \"" + compilerFilter + "\" is not a valid compilation filter.");
                        return 1;
                    } else {
                        targetCompilerFilter = compilerFilter;
                    }
                    if (allPackages2) {
                        List<String> packageNames3 = packageManagerShellCommand.mInterface.getAllPackages();
                        packageNames = packageNames3;
                        i = 1;
                    } else {
                        String packageName = getNextArg();
                        if (packageName == null) {
                            pw.println("Error: package name not specified");
                            return 1;
                        }
                        i = 1;
                        List<String> packageNames4 = Collections.singletonList(packageName);
                        packageNames = packageNames4;
                    }
                    List<String> failedPackages2 = new ArrayList<>();
                    int index2 = 0;
                    Iterator<String> it = packageNames.iterator();
                    while (it.hasNext()) {
                        Iterator<String> it2 = it;
                        String packageName2 = it.next();
                        if (clearProfileData) {
                            packageManagerShellCommand.mInterface.clearApplicationProfileData(packageName2);
                        }
                        if (allPackages2) {
                            StringBuilder sb = new StringBuilder();
                            allPackages = allPackages2;
                            int index3 = index2 + 1;
                            sb.append(index3);
                            index = index3;
                            sb.append(SliceClientPermissions.SliceAuthority.DELIMITER);
                            sb.append(packageNames.size());
                            sb.append(": ");
                            sb.append(packageName2);
                            pw.println(sb.toString());
                            pw.flush();
                        } else {
                            allPackages = allPackages2;
                            index = index2;
                        }
                        if (secondaryDex2) {
                            failedPackages = failedPackages2;
                            packageNames2 = packageNames;
                            opt = opt2;
                            result = packageManagerShellCommand.mInterface.performDexOptSecondary(packageName2, targetCompilerFilter, forceCompilation);
                            i2 = 1;
                        } else {
                            IPackageManager iPackageManager = packageManagerShellCommand.mInterface;
                            packageNames2 = packageNames;
                            failedPackages = failedPackages2;
                            i2 = 1;
                            opt = opt2;
                            result = iPackageManager.performDexOptMode(packageName2, checkProfiles, targetCompilerFilter, forceCompilation, true, split);
                        }
                        if (!result) {
                            failedPackages.add(packageName2);
                        }
                        failedPackages2 = failedPackages;
                        i = i2;
                        opt2 = opt;
                        it = it2;
                        allPackages2 = allPackages;
                        index2 = index;
                        packageNames = packageNames2;
                        packageManagerShellCommand = this;
                    }
                    List<String> failedPackages3 = failedPackages2;
                    int i4 = i;
                    if (failedPackages3.isEmpty()) {
                        pw.println("Success");
                        return 0;
                    } else if (failedPackages3.size() == i4) {
                        pw.println("Failure: package " + failedPackages3.get(0) + " could not be compiled");
                        return i4;
                    } else {
                        pw.print("Failure: the following packages could not be compiled: ");
                        boolean is_first = true;
                        for (String packageName3 : failedPackages3) {
                            if (is_first) {
                                is_first = false;
                            } else {
                                pw.print(", ");
                            }
                            pw.print(packageName3);
                        }
                        pw.println();
                        return i4;
                    }
                }
            }
            int hashCode = opt2.hashCode();
            if (hashCode == -1615291473) {
                if (opt2.equals("--reset")) {
                    c = 6;
                }
                c = 65535;
            } else if (hashCode == -1614046854) {
                if (opt2.equals("--split")) {
                    c = '\b';
                }
                c = 65535;
            } else if (hashCode == 1492) {
                if (opt2.equals("-a")) {
                    c = 0;
                }
                c = 65535;
            } else if (hashCode == 1494) {
                if (opt2.equals("-c")) {
                    c = 1;
                }
                c = 65535;
            } else if (hashCode == 1497) {
                if (opt2.equals("-f")) {
                    c = 2;
                }
                c = 65535;
            } else if (hashCode == 1504) {
                if (opt2.equals("-m")) {
                    c = 3;
                }
                c = 65535;
            } else if (hashCode == 1509) {
                if (opt2.equals("-r")) {
                    c = 4;
                }
                c = 65535;
            } else if (hashCode != 1269477022) {
                if (hashCode == 1690714782 && opt2.equals("--check-prof")) {
                    c = 5;
                }
                c = 65535;
            } else {
                if (opt2.equals("--secondary-dex")) {
                    c = 7;
                }
                c = 65535;
            }
            switch (c) {
                case 0:
                    allPackages2 = true;
                    continue;
                case 1:
                    clearProfileData = true;
                    continue;
                case 2:
                    secondaryDex = true;
                    break;
                case 3:
                    String compilerFilter2 = getNextArgRequired();
                    compilerFilter = compilerFilter2;
                    continue;
                case 4:
                    String compilationReason2 = getNextArgRequired();
                    compilationReason = compilationReason2;
                    continue;
                case 5:
                    String checkProfilesRaw2 = getNextArgRequired();
                    checkProfilesRaw = checkProfilesRaw2;
                    continue;
                case 6:
                    secondaryDex = true;
                    compilationReason = "install";
                    clearProfileData = true;
                    break;
                case 7:
                    secondaryDex2 = true;
                    continue;
                case '\b':
                    String split2 = getNextArgRequired();
                    split = split2;
                    continue;
                default:
                    pw.println("Error: Unknown option: " + opt2);
                    return 1;
            }
            forceCompilation = secondaryDex;
        }
    }

    private int runreconcileSecondaryDexFiles() throws RemoteException {
        String packageName = getNextArg();
        this.mInterface.reconcileSecondaryDexFiles(packageName);
        return 0;
    }

    public int runForceDexOpt() throws RemoteException {
        this.mInterface.forceDexOpt(getNextArgRequired());
        return 0;
    }

    private int runDexoptJob() throws RemoteException {
        List<String> packageNames = new ArrayList<>();
        while (true) {
            String arg = getNextArg();
            if (arg == null) {
                break;
            }
            packageNames.add(arg);
        }
        boolean result = this.mInterface.runBackgroundDexoptJob(packageNames.isEmpty() ? null : packageNames);
        return result ? 0 : -1;
    }

    private int runDumpProfiles() throws RemoteException {
        String packageName = getNextArg();
        this.mInterface.dumpProfiles(packageName);
        return 0;
    }

    private int runSnapshotProfile() throws RemoteException {
        String str;
        PrintWriter pw = getOutPrintWriter();
        String packageName = getNextArg();
        boolean isBootImage = PackageManagerService.PLATFORM_PACKAGE_NAME.equals(packageName);
        String codePath = null;
        while (true) {
            String opt = getNextArg();
            boolean z = false;
            if (opt != null) {
                if (opt.hashCode() != -684928411 || !opt.equals("--code-path")) {
                    z = true;
                }
                if (z) {
                    pw.write("Unknown arg: " + opt);
                    return -1;
                } else if (isBootImage) {
                    pw.write("--code-path cannot be used for the boot image.");
                    return -1;
                } else {
                    codePath = getNextArg();
                }
            } else {
                String baseCodePath = null;
                if (!isBootImage) {
                    PackageInfo packageInfo = this.mInterface.getPackageInfo(packageName, 0, 0);
                    if (packageInfo == null) {
                        pw.write("Package not found " + packageName);
                        return -1;
                    }
                    baseCodePath = packageInfo.applicationInfo.getBaseCodePath();
                    if (codePath == null) {
                        codePath = baseCodePath;
                    }
                }
                String codePath2 = codePath;
                String baseCodePath2 = baseCodePath;
                SnapshotRuntimeProfileCallback callback = new SnapshotRuntimeProfileCallback();
                String callingPackage = Binder.getCallingUid() == 0 ? "root" : "com.android.shell";
                int profileType = isBootImage ? 1 : 0;
                if (!this.mInterface.getArtManager().isRuntimeProfilingEnabled(profileType, callingPackage)) {
                    pw.println("Error: Runtime profiling is not enabled");
                    return -1;
                }
                this.mInterface.getArtManager().snapshotRuntimeProfile(profileType, packageName, codePath2, callback, callingPackage);
                if (!callback.waitTillDone()) {
                    pw.println("Error: callback not called");
                    return callback.mErrCode;
                }
                try {
                    InputStream inStream = new ParcelFileDescriptor.AutoCloseInputStream(callback.mProfileReadFd);
                    try {
                        if (!isBootImage && !Objects.equals(baseCodePath2, codePath2)) {
                            str = STDIN_PATH + new File(codePath2).getName();
                            String outputFileSuffix = str;
                            String outputProfilePath = ART_PROFILE_SNAPSHOT_DEBUG_LOCATION + packageName + outputFileSuffix + ".prof";
                            OutputStream outStream = new FileOutputStream(outputProfilePath);
                            Streams.copy(inStream, outStream);
                            $closeResource(null, outStream);
                            Os.chmod(outputProfilePath, 420);
                            $closeResource(null, inStream);
                            return 0;
                        }
                        Streams.copy(inStream, outStream);
                        $closeResource(null, outStream);
                        Os.chmod(outputProfilePath, 420);
                        $closeResource(null, inStream);
                        return 0;
                    } finally {
                    }
                    str = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    String outputFileSuffix2 = str;
                    String outputProfilePath2 = ART_PROFILE_SNAPSHOT_DEBUG_LOCATION + packageName + outputFileSuffix2 + ".prof";
                    OutputStream outStream2 = new FileOutputStream(outputProfilePath2);
                } catch (ErrnoException | IOException e) {
                    pw.println("Error when reading the profile fd: " + e.getMessage());
                    e.printStackTrace(pw);
                    return -1;
                }
            }
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
    /* loaded from: classes.dex */
    public static class SnapshotRuntimeProfileCallback extends ISnapshotRuntimeProfileCallback.Stub {
        private CountDownLatch mDoneSignal;
        private int mErrCode;
        private ParcelFileDescriptor mProfileReadFd;
        private boolean mSuccess;

        private SnapshotRuntimeProfileCallback() {
            this.mSuccess = false;
            this.mErrCode = -1;
            this.mProfileReadFd = null;
            this.mDoneSignal = new CountDownLatch(1);
        }

        public void onSuccess(ParcelFileDescriptor profileReadFd) {
            this.mSuccess = true;
            try {
                this.mProfileReadFd = profileReadFd.dup();
            } catch (IOException e) {
                e.printStackTrace();
            }
            this.mDoneSignal.countDown();
        }

        public void onError(int errCode) {
            this.mSuccess = false;
            this.mErrCode = errCode;
            this.mDoneSignal.countDown();
        }

        boolean waitTillDone() {
            boolean done = false;
            try {
                done = this.mDoneSignal.await(10000000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
            }
            return done && this.mSuccess;
        }
    }

    private int runUninstall() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        int flags = 0;
        int userId = -1;
        long versionCode = -1;
        while (true) {
            String opt = getNextOption();
            char c = 65535;
            if (opt != null) {
                int hashCode = opt.hashCode();
                if (hashCode != 1502) {
                    if (hashCode != 1333469547) {
                        if (hashCode == 1884113221 && opt.equals("--versionCode")) {
                            c = 2;
                        }
                    } else if (opt.equals("--user")) {
                        c = 1;
                    }
                } else if (opt.equals("-k")) {
                    c = 0;
                }
                switch (c) {
                    case 0:
                        flags |= 1;
                        break;
                    case 1:
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    case 2:
                        versionCode = Long.parseLong(getNextArgRequired());
                        break;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return 1;
                }
            } else {
                String packageName = getNextArg();
                if (packageName == null) {
                    pw.println("Error: package name not specified");
                    return 1;
                }
                String splitName = getNextArg();
                if (splitName == null) {
                    int userId2 = translateUserId(userId, true, "runUninstall");
                    if (userId2 == -1) {
                        userId2 = 0;
                        flags |= 2;
                    } else {
                        PackageInfo info = this.mInterface.getPackageInfo(packageName, 67108864, userId2);
                        if (info == null) {
                            pw.println("Failure [not installed for " + userId2 + "]");
                            return 1;
                        }
                        boolean isSystem = (info.applicationInfo.flags & 1) != 0;
                        if (isSystem) {
                            flags |= 4;
                        }
                    }
                    LocalIntentReceiver receiver = new LocalIntentReceiver();
                    this.mInterface.getPackageInstaller().uninstall(new VersionedPackage(packageName, versionCode), (String) null, flags, receiver.getIntentSender(), userId2);
                    Intent result = receiver.getResult();
                    int status = result.getIntExtra("android.content.pm.extra.STATUS", 1);
                    if (status == 0) {
                        pw.println("Success");
                        return 0;
                    }
                    pw.println("Failure [" + result.getStringExtra("android.content.pm.extra.STATUS_MESSAGE") + "]");
                    return 1;
                }
                return runRemoveSplit(packageName, splitName);
            }
        }
    }

    private int runRemoveSplit(String packageName, String splitName) throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(2);
        sessionParams.installFlags = 2 | sessionParams.installFlags;
        sessionParams.appPackageName = packageName;
        int sessionId = doCreateSession(sessionParams, null, -1);
        boolean abandonSession = true;
        try {
            if (doRemoveSplit(sessionId, splitName, false) != 0) {
                return 1;
            }
            if (doCommitSession(sessionId, false) != 0) {
                if (1 != 0) {
                    try {
                        doAbandonSession(sessionId, false);
                    } catch (Exception e) {
                    }
                }
                return 1;
            }
            abandonSession = false;
            pw.println("Success");
            if (0 != 0) {
                try {
                    doAbandonSession(sessionId, false);
                } catch (Exception e2) {
                }
            }
            return 0;
        } finally {
            if (abandonSession) {
                try {
                    doAbandonSession(sessionId, false);
                } catch (Exception e3) {
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class ClearDataObserver extends IPackageDataObserver.Stub {
        boolean finished;
        boolean result;

        ClearDataObserver() {
        }

        public void onRemoveCompleted(String packageName, boolean succeeded) throws RemoteException {
            synchronized (this) {
                this.finished = true;
                this.result = succeeded;
                notifyAll();
            }
        }
    }

    private int runClear() throws RemoteException {
        int userId = 0;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        ClearDataObserver obs = new ClearDataObserver();
        ActivityManager.getService().clearApplicationUserData(pkg, false, obs, userId);
        synchronized (obs) {
            while (!obs.finished) {
                try {
                    obs.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        if (obs.result) {
            getOutPrintWriter().println("Success");
            return 0;
        }
        getErrPrintWriter().println("Failed");
        return 1;
    }

    private static String enabledSettingToString(int state) {
        switch (state) {
            case 0:
                return "default";
            case 1:
                return "enabled";
            case 2:
                return "disabled";
            case 3:
                return "disabled-user";
            case 4:
                return "disabled-until-used";
            default:
                return UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
        }
    }

    private int runSetEnabledSetting(int state) throws RemoteException {
        int userId = 0;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package or component specified");
            return 1;
        }
        ComponentName cn = ComponentName.unflattenFromString(pkg);
        if (cn != null) {
            this.mInterface.setComponentEnabledSetting(cn, state, 0, userId);
            PrintWriter outPrintWriter = getOutPrintWriter();
            outPrintWriter.println("Component " + cn.toShortString() + " new state: " + enabledSettingToString(this.mInterface.getComponentEnabledSetting(cn, userId)));
            return 0;
        }
        IPackageManager iPackageManager = this.mInterface;
        iPackageManager.setApplicationEnabledSetting(pkg, state, 0, userId, "shell:" + Process.myUid());
        PrintWriter outPrintWriter2 = getOutPrintWriter();
        outPrintWriter2.println("Package " + pkg + " new state: " + enabledSettingToString(this.mInterface.getApplicationEnabledSetting(pkg, userId)));
        return 0;
    }

    private int runSetHiddenSetting(boolean state) throws RemoteException {
        int userId = 0;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package or component specified");
            return 1;
        }
        this.mInterface.setApplicationHiddenSettingAsUser(pkg, state, userId);
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Package " + pkg + " new hidden state: " + this.mInterface.getApplicationHiddenSettingAsUser(pkg, userId));
        return 0;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:9:0x002d, code lost:
        if (r0.equals("--user") != false) goto L8;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int runSuspend(boolean r18) {
        /*
            Method dump skipped, instructions count: 376
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerShellCommand.runSuspend(boolean):int");
    }

    private int runGrantRevokePermission(boolean grant) throws RemoteException {
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt == null) {
                break;
            } else if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            }
        }
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        String perm = getNextArg();
        if (perm == null) {
            getErrPrintWriter().println("Error: no permission specified");
            return 1;
        } else if (grant) {
            this.mInterface.grantRuntimePermission(pkg, perm, userId);
            return 0;
        } else {
            this.mInterface.revokeRuntimePermission(pkg, perm, userId);
            return 0;
        }
    }

    private int runResetPermissions() throws RemoteException {
        this.mInterface.resetRuntimePermissions();
        return 0;
    }

    private int runSetPermissionEnforced() throws RemoteException {
        String permission = getNextArg();
        if (permission == null) {
            getErrPrintWriter().println("Error: no permission specified");
            return 1;
        }
        String enforcedRaw = getNextArg();
        if (enforcedRaw == null) {
            getErrPrintWriter().println("Error: no enforcement specified");
            return 1;
        }
        this.mInterface.setPermissionEnforced(permission, Boolean.parseBoolean(enforcedRaw));
        return 0;
    }

    private boolean isVendorApp(String pkg) {
        try {
            PackageInfo info = this.mInterface.getPackageInfo(pkg, 0, 0);
            if (info != null) {
                return info.applicationInfo.isVendor();
            }
            return false;
        } catch (RemoteException e) {
            return false;
        }
    }

    private boolean isProductApp(String pkg) {
        try {
            PackageInfo info = this.mInterface.getPackageInfo(pkg, 0, 0);
            if (info != null) {
                return info.applicationInfo.isProduct();
            }
            return false;
        } catch (RemoteException e) {
            return false;
        }
    }

    private int runGetPrivappPermissions() {
        ArraySet<String> privAppPermissions;
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified.");
            return 1;
        }
        if (isVendorApp(pkg)) {
            privAppPermissions = SystemConfig.getInstance().getVendorPrivAppPermissions(pkg);
        } else if (isProductApp(pkg)) {
            privAppPermissions = SystemConfig.getInstance().getProductPrivAppPermissions(pkg);
        } else {
            privAppPermissions = SystemConfig.getInstance().getPrivAppPermissions(pkg);
        }
        getOutPrintWriter().println(privAppPermissions == null ? "{}" : privAppPermissions.toString());
        return 0;
    }

    private int runGetPrivappDenyPermissions() {
        ArraySet<String> privAppPermissions;
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified.");
            return 1;
        }
        if (isVendorApp(pkg)) {
            privAppPermissions = SystemConfig.getInstance().getVendorPrivAppDenyPermissions(pkg);
        } else if (isProductApp(pkg)) {
            privAppPermissions = SystemConfig.getInstance().getProductPrivAppDenyPermissions(pkg);
        } else {
            privAppPermissions = SystemConfig.getInstance().getPrivAppDenyPermissions(pkg);
        }
        getOutPrintWriter().println(privAppPermissions == null ? "{}" : privAppPermissions.toString());
        return 0;
    }

    private int runGetOemPermissions() {
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified.");
            return 1;
        }
        Map<String, Boolean> oemPermissions = SystemConfig.getInstance().getOemPermissions(pkg);
        if (oemPermissions == null || oemPermissions.isEmpty()) {
            getOutPrintWriter().println("{}");
            return 0;
        }
        oemPermissions.forEach(new BiConsumer() { // from class: com.android.server.pm.-$$Lambda$PackageManagerShellCommand$-OZpz58K2HXVuHDuVYKnCu6oo4c
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                PackageManagerShellCommand.lambda$runGetOemPermissions$0(PackageManagerShellCommand.this, (String) obj, (Boolean) obj2);
            }
        });
        return 0;
    }

    public static /* synthetic */ void lambda$runGetOemPermissions$0(PackageManagerShellCommand packageManagerShellCommand, String permission, Boolean granted) {
        PrintWriter outPrintWriter = packageManagerShellCommand.getOutPrintWriter();
        outPrintWriter.println(permission + " granted:" + granted);
    }

    private String linkStateToString(int state) {
        switch (state) {
            case 0:
                return "undefined";
            case 1:
                return "ask";
            case 2:
                return "always";
            case 3:
                return "never";
            case 4:
                return "always ask";
            default:
                return "Unknown link state: " + state;
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private int runSetAppLink() throws RemoteException {
        char c;
        int newMode;
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    getErrPrintWriter().println("Error: unknown option: " + opt);
                    return 1;
                }
            } else {
                String pkg = getNextArg();
                if (pkg == null) {
                    getErrPrintWriter().println("Error: no package specified.");
                    return 1;
                }
                String modeString = getNextArg();
                if (modeString == null) {
                    getErrPrintWriter().println("Error: no app link state specified.");
                    return 1;
                }
                String lowerCase = modeString.toLowerCase();
                switch (lowerCase.hashCode()) {
                    case -1414557169:
                        if (lowerCase.equals("always")) {
                            c = 1;
                            break;
                        }
                        c = 65535;
                        break;
                    case -1038130864:
                        if (lowerCase.equals("undefined")) {
                            c = 0;
                            break;
                        }
                        c = 65535;
                        break;
                    case 96889:
                        if (lowerCase.equals("ask")) {
                            c = 2;
                            break;
                        }
                        c = 65535;
                        break;
                    case 104712844:
                        if (lowerCase.equals("never")) {
                            c = 4;
                            break;
                        }
                        c = 65535;
                        break;
                    case 1182785979:
                        if (lowerCase.equals("always-ask")) {
                            c = 3;
                            break;
                        }
                        c = 65535;
                        break;
                    default:
                        c = 65535;
                        break;
                }
                switch (c) {
                    case 0:
                        newMode = 0;
                        break;
                    case 1:
                        newMode = 2;
                        break;
                    case 2:
                        newMode = 1;
                        break;
                    case 3:
                        newMode = 4;
                        break;
                    case 4:
                        newMode = 3;
                        break;
                    default:
                        getErrPrintWriter().println("Error: unknown app link state '" + modeString + "'");
                        return 1;
                }
                PackageInfo info = this.mInterface.getPackageInfo(pkg, 0, userId);
                if (info == null) {
                    getErrPrintWriter().println("Error: package " + pkg + " not found.");
                    return 1;
                } else if ((info.applicationInfo.privateFlags & 16) == 0) {
                    getErrPrintWriter().println("Error: package " + pkg + " does not handle web links.");
                    return 1;
                } else if (this.mInterface.updateIntentVerificationStatus(pkg, newMode, userId)) {
                    return 0;
                } else {
                    getErrPrintWriter().println("Error: unable to update app link status for " + pkg);
                    return 1;
                }
            }
        }
    }

    private int runGetAppLink() throws RemoteException {
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    getErrPrintWriter().println("Error: unknown option: " + opt);
                    return 1;
                }
            } else {
                String pkg = getNextArg();
                if (pkg != null) {
                    PackageInfo info = this.mInterface.getPackageInfo(pkg, 0, userId);
                    if (info == null) {
                        getErrPrintWriter().println("Error: package " + pkg + " not found.");
                        return 1;
                    } else if ((info.applicationInfo.privateFlags & 16) == 0) {
                        getErrPrintWriter().println("Error: package " + pkg + " does not handle web links.");
                        return 1;
                    } else {
                        getOutPrintWriter().println(linkStateToString(this.mInterface.getIntentVerificationStatus(pkg, userId)));
                        return 0;
                    }
                }
                getErrPrintWriter().println("Error: no package specified.");
                return 1;
            }
        }
    }

    private int runTrimCaches() throws RemoteException {
        String size = getNextArg();
        if (size == null) {
            getErrPrintWriter().println("Error: no size specified");
            return 1;
        }
        long multiplier = 1;
        int len = size.length();
        char c = size.charAt(len - 1);
        if (c < '0' || c > '9') {
            if (c == 'K' || c == 'k') {
                multiplier = 1024;
            } else if (c == 'M' || c == 'm') {
                multiplier = 1048576;
            } else if (c == 'G' || c == 'g') {
                multiplier = 1073741824;
            } else {
                PrintWriter errPrintWriter = getErrPrintWriter();
                errPrintWriter.println("Invalid suffix: " + c);
                return 1;
            }
            size = size.substring(0, len - 1);
        }
        long multiplier2 = multiplier;
        String size2 = size;
        try {
            long sizeVal = Long.parseLong(size2) * multiplier2;
            String volumeUuid = getNextArg();
            if ("internal".equals(volumeUuid)) {
                volumeUuid = null;
            }
            String volumeUuid2 = volumeUuid;
            ClearDataObserver obs = new ClearDataObserver();
            this.mInterface.freeStorageAndNotify(volumeUuid2, sizeVal, 2, obs);
            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
            return 0;
        } catch (NumberFormatException e2) {
            PrintWriter errPrintWriter2 = getErrPrintWriter();
            errPrintWriter2.println("Error: expected number at: " + size2);
            return 1;
        }
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public int runCreateUser() throws RemoteException {
        UserInfo info;
        UserInfo info2;
        int userId = -1;
        int userId2 = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if ("--profileOf".equals(opt)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else if ("--managed".equals(opt)) {
                    userId2 |= 32;
                } else if ("--restricted".equals(opt)) {
                    userId2 |= 8;
                } else if ("--ephemeral".equals(opt)) {
                    userId2 |= 256;
                } else if ("--guest".equals(opt)) {
                    userId2 |= 4;
                } else if ("--demo".equals(opt)) {
                    userId2 |= 512;
                } else {
                    getErrPrintWriter().println("Error: unknown option " + opt);
                    return 1;
                }
            } else {
                String arg = getNextArg();
                if (arg == null) {
                    getErrPrintWriter().println("Error: no user name specified.");
                    return 1;
                }
                IUserManager um = IUserManager.Stub.asInterface(ServiceManager.getService("user"));
                IAccountManager accm = IAccountManager.Stub.asInterface(ServiceManager.getService("account"));
                if ((userId2 & 8) != 0) {
                    int parentUserId = userId >= 0 ? userId : 0;
                    info = um.createRestrictedProfile(arg, parentUserId);
                    accm.addSharedAccountsFromParentUser(parentUserId, userId, Process.myUid() == 0 ? "root" : "com.android.shell");
                } else if (userId >= 0) {
                    info = um.createProfileForUser(arg, userId2, userId, (String[]) null);
                } else {
                    info = um.createUser(arg, userId2);
                }
                if (info != null) {
                    getOutPrintWriter().println("Success: created user id " + info2.id);
                    return 0;
                }
                getErrPrintWriter().println("Error: couldn't create User.");
                return 1;
            }
        }
    }

    public int runRemoveUser() throws RemoteException {
        String arg = getNextArg();
        if (arg == null) {
            getErrPrintWriter().println("Error: no user id specified.");
            return 1;
        }
        int userId = UserHandle.parseUserArg(arg);
        IUserManager um = IUserManager.Stub.asInterface(ServiceManager.getService("user"));
        if (um.removeUser(userId)) {
            getOutPrintWriter().println("Success: removed user");
            return 0;
        }
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Error: couldn't remove user id " + userId);
        return 1;
    }

    public int runSetUserRestriction() throws RemoteException {
        boolean value;
        int userId = 0;
        String opt = getNextOption();
        if (opt != null && "--user".equals(opt)) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }
        String restriction = getNextArg();
        String arg = getNextArg();
        if ("1".equals(arg)) {
            value = true;
        } else if ("0".equals(arg)) {
            value = false;
        } else {
            getErrPrintWriter().println("Error: valid value not specified");
            return 1;
        }
        IUserManager um = IUserManager.Stub.asInterface(ServiceManager.getService("user"));
        um.setUserRestriction(restriction, value, userId);
        return 0;
    }

    public int runGetMaxUsers() {
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Maximum supported users: " + UserManager.getMaxSupportedUsers());
        return 0;
    }

    public int runGetMaxRunningUsers() {
        ActivityManagerInternal activityManagerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Maximum supported running users: " + activityManagerInternal.getMaxRunningUsers());
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class InstallParams {
        String installerPackageName;
        PackageInstaller.SessionParams sessionParams;
        int userId;

        private InstallParams() {
            this.userId = -1;
        }
    }

    private InstallParams makeInstallParams() {
        PackageInstaller.SessionParams sessionParams = new PackageInstaller.SessionParams(1);
        InstallParams params = new InstallParams();
        params.sessionParams = sessionParams;
        boolean replaceExisting = true;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                char c = 65535;
                switch (opt.hashCode()) {
                    case -1950997763:
                        if (opt.equals("--force-uuid")) {
                            c = 23;
                            break;
                        }
                        break;
                    case -1777984902:
                        if (opt.equals("--dont-kill")) {
                            c = '\t';
                            break;
                        }
                        break;
                    case -1313152697:
                        if (opt.equals("--install-location")) {
                            c = 22;
                            break;
                        }
                        break;
                    case -1137116608:
                        if (opt.equals("--instantapp")) {
                            c = 18;
                            break;
                        }
                        break;
                    case -951415743:
                        if (opt.equals("--instant")) {
                            c = 17;
                            break;
                        }
                        break;
                    case -706813505:
                        if (opt.equals("--referrer")) {
                            c = 11;
                            break;
                        }
                        break;
                    case 1477:
                        if (opt.equals("-R")) {
                            c = 2;
                            break;
                        }
                        break;
                    case 1478:
                        if (opt.equals("-S")) {
                            c = 14;
                            break;
                        }
                        break;
                    case 1495:
                        if (opt.equals("-d")) {
                            c = 7;
                            break;
                        }
                        break;
                    case 1497:
                        if (opt.equals("-f")) {
                            c = 6;
                            break;
                        }
                        break;
                    case 1498:
                        if (opt.equals("-g")) {
                            c = '\b';
                            break;
                        }
                        break;
                    case NetworkConstants.ETHER_MTU /* 1500 */:
                        if (opt.equals("-i")) {
                            c = 3;
                            break;
                        }
                        break;
                    case 1503:
                        if (opt.equals("-l")) {
                            c = 0;
                            break;
                        }
                        break;
                    case 1507:
                        if (opt.equals("-p")) {
                            c = '\f';
                            break;
                        }
                        break;
                    case 1509:
                        if (opt.equals("-r")) {
                            c = 1;
                            break;
                        }
                        break;
                    case 1510:
                        if (opt.equals("-s")) {
                            c = 5;
                            break;
                        }
                        break;
                    case 1511:
                        if (opt.equals("-t")) {
                            c = 4;
                            break;
                        }
                        break;
                    case 42995400:
                        if (opt.equals("--abi")) {
                            c = 15;
                            break;
                        }
                        break;
                    case 43010092:
                        if (opt.equals("--pkg")) {
                            c = '\r';
                            break;
                        }
                        break;
                    case 148207464:
                        if (opt.equals("--originating-uri")) {
                            c = '\n';
                            break;
                        }
                        break;
                    case 1051781117:
                        if (opt.equals("--ephemeral")) {
                            c = 16;
                            break;
                        }
                        break;
                    case 1067504745:
                        if (opt.equals("--preload")) {
                            c = 20;
                            break;
                        }
                        break;
                    case 1333024815:
                        if (opt.equals("--full")) {
                            c = 19;
                            break;
                        }
                        break;
                    case 1333469547:
                        if (opt.equals("--user")) {
                            c = 21;
                            break;
                        }
                        break;
                    case 2015272120:
                        if (opt.equals("--force-sdk")) {
                            c = 24;
                            break;
                        }
                        break;
                }
                switch (c) {
                    case 0:
                        sessionParams.installFlags |= 1;
                        break;
                    case 1:
                        break;
                    case 2:
                        replaceExisting = false;
                        break;
                    case 3:
                        params.installerPackageName = getNextArg();
                        if (params.installerPackageName == null) {
                            throw new IllegalArgumentException("Missing installer package");
                        }
                        break;
                    case 4:
                        sessionParams.installFlags |= 4;
                        break;
                    case 5:
                        sessionParams.installFlags |= 8;
                        break;
                    case 6:
                        sessionParams.installFlags |= 16;
                        break;
                    case 7:
                        sessionParams.installFlags |= 128;
                        break;
                    case '\b':
                        sessionParams.installFlags |= 256;
                        break;
                    case '\t':
                        sessionParams.installFlags |= 4096;
                        break;
                    case '\n':
                        sessionParams.originatingUri = Uri.parse(getNextArg());
                        break;
                    case 11:
                        sessionParams.referrerUri = Uri.parse(getNextArg());
                        break;
                    case '\f':
                        sessionParams.mode = 2;
                        sessionParams.appPackageName = getNextArg();
                        if (sessionParams.appPackageName == null) {
                            throw new IllegalArgumentException("Missing inherit package name");
                        }
                        break;
                    case '\r':
                        sessionParams.appPackageName = getNextArg();
                        if (sessionParams.appPackageName == null) {
                            throw new IllegalArgumentException("Missing package name");
                        }
                        break;
                    case 14:
                        long sizeBytes = Long.parseLong(getNextArg());
                        if (sizeBytes <= 0) {
                            throw new IllegalArgumentException("Size must be positive");
                        }
                        sessionParams.setSize(sizeBytes);
                        break;
                    case 15:
                        sessionParams.abiOverride = checkAbiArgument(getNextArg());
                        break;
                    case 16:
                    case 17:
                    case 18:
                        sessionParams.setInstallAsInstantApp(true);
                        break;
                    case WindowManagerService.H.REPORT_WINDOWS_CHANGE /* 19 */:
                        sessionParams.setInstallAsInstantApp(false);
                        break;
                    case 20:
                        sessionParams.setInstallAsVirtualPreload();
                        break;
                    case BackupHandler.MSG_OP_COMPLETE /* 21 */:
                        params.userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    case WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE /* 22 */:
                        sessionParams.installLocation = Integer.parseInt(getNextArg());
                        break;
                    case WindowManagerService.H.BOOT_TIMEOUT /* 23 */:
                        sessionParams.installFlags |= 512;
                        sessionParams.volumeUuid = getNextArg();
                        if ("internal".equals(sessionParams.volumeUuid)) {
                            sessionParams.volumeUuid = null;
                            break;
                        }
                        break;
                    case 24:
                        sessionParams.installFlags |= 8192;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option " + opt);
                }
                if (replaceExisting) {
                    sessionParams.installFlags |= 2;
                }
            } else {
                return params;
            }
        }
    }

    private int runSetHomeActivity() {
        PrintWriter pw = getOutPrintWriter();
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                char c = 65535;
                if (opt.hashCode() == 1333469547 && opt.equals("--user")) {
                    c = 0;
                }
                if (c == 0) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
                }
            } else {
                String component = getNextArg();
                ComponentName componentName = component != null ? ComponentName.unflattenFromString(component) : null;
                if (componentName == null) {
                    pw.println("Error: component name not specified or invalid");
                    return 1;
                }
                try {
                    this.mInterface.setHomeActivity(componentName, userId);
                    pw.println("Success");
                    return 0;
                } catch (Exception e) {
                    pw.println(e.toString());
                    return 1;
                }
            }
        }
    }

    private int runSetInstaller() throws RemoteException {
        String targetPackage = getNextArg();
        String installerPackageName = getNextArg();
        if (targetPackage == null || installerPackageName == null) {
            getErrPrintWriter().println("Must provide both target and installer package names");
            return 1;
        }
        this.mInterface.setInstallerPackageName(targetPackage, installerPackageName);
        getOutPrintWriter().println("Success");
        return 0;
    }

    private int runGetInstantAppResolver() {
        PrintWriter pw = getOutPrintWriter();
        try {
            ComponentName instantAppsResolver = this.mInterface.getInstantAppResolverComponent();
            if (instantAppsResolver == null) {
                return 1;
            }
            pw.println(instantAppsResolver.flattenToString());
            return 0;
        } catch (Exception e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runHasFeature() {
        int version;
        PrintWriter err = getErrPrintWriter();
        String featureName = getNextArg();
        if (featureName == null) {
            err.println("Error: expected FEATURE name");
            return 1;
        }
        String versionString = getNextArg();
        if (versionString == null) {
            version = 0;
        } else {
            try {
                version = Integer.parseInt(versionString);
            } catch (RemoteException e) {
                err.println(e.toString());
                return 1;
            } catch (NumberFormatException e2) {
                err.println("Error: illegal version number " + versionString);
                return 1;
            }
        }
        boolean hasFeature = this.mInterface.hasSystemFeature(featureName, version);
        getOutPrintWriter().println(hasFeature);
        if (!hasFeature) {
            return 1;
        }
        return 0;
    }

    private int runDump() {
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        ActivityManager.dumpPackageStateStatic(getOutFileDescriptor(), pkg);
        return 0;
    }

    private int runSetHarmfulAppWarning() throws RemoteException {
        int userId = -2;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                int userId2 = translateUserId(userId, false, "runSetHarmfulAppWarning");
                String packageName = getNextArgRequired();
                String warning = getNextArg();
                this.mInterface.setHarmfulAppWarning(packageName, warning, userId2);
                return 0;
            }
        }
    }

    private int runGetHarmfulAppWarning() throws RemoteException {
        int userId = -2;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                int userId2 = translateUserId(userId, false, "runGetHarmfulAppWarning");
                String packageName = getNextArgRequired();
                CharSequence warning = this.mInterface.getHarmfulAppWarning(packageName, userId2);
                if (!TextUtils.isEmpty(warning)) {
                    getOutPrintWriter().println(warning);
                    return 0;
                }
                return 1;
            }
        }
    }

    private static String checkAbiArgument(String abi) {
        if (TextUtils.isEmpty(abi)) {
            throw new IllegalArgumentException("Missing ABI argument");
        }
        if (STDIN_PATH.equals(abi)) {
            return abi;
        }
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        for (String supportedAbi : supportedAbis) {
            if (supportedAbi.equals(abi)) {
                return abi;
            }
        }
        throw new IllegalArgumentException("ABI " + abi + " not supported on this device");
    }

    private int translateUserId(int userId, boolean allowAll, String logContext) {
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, allowAll, true, logContext, "pm command");
    }

    private int doCreateSession(PackageInstaller.SessionParams params, String installerPackageName, int userId) throws RemoteException {
        int userId2 = translateUserId(userId, true, "runInstallCreate");
        if (userId2 == -1) {
            userId2 = 0;
            params.installFlags |= 64;
        }
        int sessionId = this.mInterface.getPackageInstaller().createSession(params, installerPackageName, userId2);
        return sessionId;
    }

    /* JADX WARN: Removed duplicated region for block: B:18:0x005f  */
    /* JADX WARN: Removed duplicated region for block: B:20:0x0069  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int doWriteSplit(int r17, java.lang.String r18, long r19, java.lang.String r21, boolean r22) throws android.os.RemoteException {
        /*
            r16 = this;
            r1 = r16
            r2 = r18
            java.io.PrintWriter r3 = r16.getOutPrintWriter()
            java.lang.String r0 = "-"
            boolean r0 = r0.equals(r2)
            r4 = 0
            if (r0 == 0) goto L1f
            android.os.ParcelFileDescriptor r0 = new android.os.ParcelFileDescriptor
            java.io.FileDescriptor r6 = r16.getInFileDescriptor()
            r0.<init>(r6)
        L1b:
            r14 = r19
            r13 = r0
            goto L5a
        L1f:
            if (r2 == 0) goto L50
            java.lang.String r0 = "r"
            android.os.ParcelFileDescriptor r0 = r1.openFileForSystem(r2, r0)
            r6 = -1
            if (r0 != 0) goto L2c
            return r6
        L2c:
            long r7 = r0.getStatSize()
            int r9 = (r7 > r4 ? 1 : (r7 == r4 ? 0 : -1))
            if (r9 >= 0) goto L4d
            java.io.PrintWriter r4 = r16.getErrPrintWriter()
            java.lang.StringBuilder r5 = new java.lang.StringBuilder
            r5.<init>()
            java.lang.String r9 = "Unable to get size of: "
            r5.append(r9)
            r5.append(r2)
            java.lang.String r5 = r5.toString()
            r4.println(r5)
            return r6
        L4d:
            r13 = r0
            r14 = r7
            goto L5a
        L50:
            android.os.ParcelFileDescriptor r0 = new android.os.ParcelFileDescriptor
            java.io.FileDescriptor r6 = r16.getInFileDescriptor()
            r0.<init>(r6)
            goto L1b
        L5a:
            int r0 = (r14 > r4 ? 1 : (r14 == r4 ? 0 : -1))
            r4 = 1
            if (r0 > 0) goto L69
            java.io.PrintWriter r0 = r16.getErrPrintWriter()
            java.lang.String r5 = "Error: must specify a APK size"
            r0.println(r5)
            return r4
        L69:
            r0 = 0
            r5 = r0
            android.content.pm.PackageInstaller$Session r0 = new android.content.pm.PackageInstaller$Session     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            android.content.pm.IPackageManager r6 = r1.mInterface     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            android.content.pm.IPackageInstaller r6 = r6.getPackageInstaller()     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            r11 = r17
            android.content.pm.IPackageInstallerSession r6 = r6.openSession(r11)     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            r0.<init>(r6)     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            r5 = r0
            r9 = 0
            r7 = r5
            r8 = r21
            r11 = r14
            r7.write(r8, r9, r11, r13)     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            if (r22 == 0) goto La1
            java.lang.StringBuilder r0 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            r0.<init>()     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            java.lang.String r7 = "Success: streamed "
            r0.append(r7)     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            r0.append(r14)     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            java.lang.String r7 = " bytes"
            r0.append(r7)     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            java.lang.String r0 = r0.toString()     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
            r3.println(r0)     // Catch: java.lang.Throwable -> La6 java.io.IOException -> La8
        La1:
            r0 = 0
            libcore.io.IoUtils.closeQuietly(r5)
            return r0
        La6:
            r0 = move-exception
            goto Lca
        La8:
            r0 = move-exception
            java.io.PrintWriter r7 = r16.getErrPrintWriter()     // Catch: java.lang.Throwable -> La6
            java.lang.StringBuilder r8 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> La6
            r8.<init>()     // Catch: java.lang.Throwable -> La6
            java.lang.String r9 = "Error: failed to write; "
            r8.append(r9)     // Catch: java.lang.Throwable -> La6
            java.lang.String r9 = r0.getMessage()     // Catch: java.lang.Throwable -> La6
            r8.append(r9)     // Catch: java.lang.Throwable -> La6
            java.lang.String r8 = r8.toString()     // Catch: java.lang.Throwable -> La6
            r7.println(r8)     // Catch: java.lang.Throwable -> La6
            libcore.io.IoUtils.closeQuietly(r5)
            return r4
        Lca:
            libcore.io.IoUtils.closeQuietly(r5)
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.PackageManagerShellCommand.doWriteSplit(int, java.lang.String, long, java.lang.String, boolean):int");
    }

    private int doRemoveSplit(int sessionId, String splitName, boolean logSuccess) throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(this.mInterface.getPackageInstaller().openSession(sessionId));
            session.removeSplit(splitName);
            if (logSuccess) {
                pw.println("Success");
            }
            return 0;
        } catch (IOException e) {
            pw.println("Error: failed to remove split; " + e.getMessage());
            return 1;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doCommitSession(int sessionId, boolean logSuccess) throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(this.mInterface.getPackageInstaller().openSession(sessionId));
            try {
                DexMetadataHelper.validateDexPaths(session.getNames());
            } catch (IOException | IllegalStateException e) {
                pw.println("Warning [Could not validate the dex paths: " + e.getMessage() + "]");
            }
            LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());
            Intent result = receiver.getResult();
            int status = result.getIntExtra("android.content.pm.extra.STATUS", 1);
            if (status == 0) {
                if (logSuccess) {
                    pw.println("Success");
                }
            } else {
                pw.println("Failure [" + result.getStringExtra("android.content.pm.extra.STATUS_MESSAGE") + "]");
            }
            return status;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doAbandonSession(int sessionId, boolean logSuccess) throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(this.mInterface.getPackageInstaller().openSession(sessionId));
            session.abandon();
            if (logSuccess) {
                pw.println("Success");
            }
            return 0;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private void doListPermissions(ArrayList<String> groupList, boolean groups, boolean labels, boolean summary, int startProtectionLevel, int endProtectionLevel) throws RemoteException {
        int base;
        ArrayList<String> arrayList = groupList;
        PrintWriter pw = getOutPrintWriter();
        int groupCount = groupList.size();
        int i = 0;
        int i2 = 0;
        while (i2 < groupCount) {
            String groupName = arrayList.get(i2);
            String prefix = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            if (groups) {
                if (i2 > 0) {
                    pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                }
                if (groupName != null) {
                    PermissionGroupInfo pgi = this.mInterface.getPermissionGroupInfo(groupName, i);
                    if (summary) {
                        Resources res = getResources(pgi);
                        if (res != null) {
                            pw.print(loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel) + ": ");
                        } else {
                            pw.print(pgi.name + ": ");
                        }
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append(labels ? "+ " : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                        sb.append("group:");
                        sb.append(pgi.name);
                        pw.println(sb.toString());
                        if (labels) {
                            pw.println("  package:" + pgi.packageName);
                            Resources res2 = getResources(pgi);
                            if (res2 != null) {
                                pw.println("  label:" + loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel));
                                pw.println("  description:" + loadText(pgi, pgi.descriptionRes, pgi.nonLocalizedDescription));
                            }
                        }
                    }
                } else {
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append((!labels || summary) ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "+ ");
                    sb2.append("ungrouped:");
                    pw.println(sb2.toString());
                }
                prefix = "  ";
            }
            List<PermissionInfo> ps = this.mInterface.queryPermissionsByGroup(arrayList.get(i2), i).getList();
            int count = ps.size();
            boolean first = true;
            for (int p = i; p < count; p++) {
                PermissionInfo pi = ps.get(p);
                if ((!groups || groupName != null || pi.group == null) && (base = pi.protectionLevel & 15) >= startProtectionLevel && base <= endProtectionLevel) {
                    if (summary) {
                        if (first) {
                            first = false;
                        } else {
                            pw.print(", ");
                        }
                        Resources res3 = getResources(pi);
                        if (res3 != null) {
                            pw.print(loadText(pi, pi.labelRes, pi.nonLocalizedLabel));
                        } else {
                            pw.print(pi.name);
                        }
                    } else {
                        StringBuilder sb3 = new StringBuilder();
                        sb3.append(prefix);
                        sb3.append(labels ? "+ " : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                        sb3.append("permission:");
                        sb3.append(pi.name);
                        pw.println(sb3.toString());
                        if (labels) {
                            pw.println(prefix + "  package:" + pi.packageName);
                            Resources res4 = getResources(pi);
                            if (res4 != null) {
                                pw.println(prefix + "  label:" + loadText(pi, pi.labelRes, pi.nonLocalizedLabel));
                                pw.println(prefix + "  description:" + loadText(pi, pi.descriptionRes, pi.nonLocalizedDescription));
                            }
                            pw.println(prefix + "  protectionLevel:" + PermissionInfo.protectionToString(pi.protectionLevel));
                        }
                    }
                }
            }
            if (summary) {
                pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            }
            i2++;
            arrayList = groupList;
            i = 0;
        }
    }

    private String loadText(PackageItemInfo pii, int res, CharSequence nonLocalized) throws RemoteException {
        Resources r;
        if (nonLocalized != null) {
            return nonLocalized.toString();
        }
        if (res != 0 && (r = getResources(pii)) != null) {
            try {
                return r.getString(res);
            } catch (Resources.NotFoundException e) {
                return null;
            }
        }
        return null;
    }

    private Resources getResources(PackageItemInfo pii) throws RemoteException {
        Resources res = this.mResourceCache.get(pii.packageName);
        if (res != null) {
            return res;
        }
        ApplicationInfo ai = this.mInterface.getApplicationInfo(pii.packageName, 0, 0);
        AssetManager am = new AssetManager();
        am.addAssetPath(ai.publicSourceDir);
        Resources res2 = new Resources(am, null, null);
        this.mResourceCache.put(pii.packageName, res2);
        return res2;
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Package manager (package) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  path [--user USER_ID] PACKAGE");
        pw.println("    Print the path to the .apk of the given PACKAGE.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  dump PACKAGE");
        pw.println("    Print various system state associated with the given PACKAGE.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  list features");
        pw.println("    Prints all features of the system.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  has-feature FEATURE_NAME [version]");
        pw.println("    Prints true and returns exit status 0 when system has a FEATURE_NAME,");
        pw.println("    otherwise prints false and returns exit status 1");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  list instrumentation [-f] [TARGET-PACKAGE]");
        pw.println("    Prints all test packages; optionally only those targeting TARGET-PACKAGE");
        pw.println("    Options:");
        pw.println("      -f: dump the name of the .apk file containing the test package");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  list libraries");
        pw.println("    Prints all system libraries.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  list packages [-f] [-d] [-e] [-s] [-3] [-i] [-l] [-u] [-U] ");
        pw.println("      [--uid UID] [--user USER_ID] [FILTER]");
        pw.println("    Prints all packages; optionally only those whose name contains");
        pw.println("    the text in FILTER.  Options are:");
        pw.println("      -f: see their associated file");
        pw.println("      -d: filter to only show disabled packages");
        pw.println("      -e: filter to only show enabled packages");
        pw.println("      -s: filter to only show system packages");
        pw.println("      -3: filter to only show third party packages");
        pw.println("      -i: see the installer for the packages");
        pw.println("      -l: ignored (used for compatibility with older releases)");
        pw.println("      -U: also show the package UID");
        pw.println("      -u: also include uninstalled packages");
        pw.println("      --uid UID: filter to only show packages with the given UID");
        pw.println("      --user USER_ID: only list packages belonging to the given user");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  list permission-groups");
        pw.println("    Prints all known permission groups.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  list permissions [-g] [-f] [-d] [-u] [GROUP]");
        pw.println("    Prints all known permissions; optionally only those in GROUP.  Options are:");
        pw.println("      -g: organize by group");
        pw.println("      -f: print all information");
        pw.println("      -s: short summary");
        pw.println("      -d: only list dangerous permissions");
        pw.println("      -u: list only the permissions users will see");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  resolve-activity [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints the activity that resolves to the given INTENT.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  query-activities [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints all activities that can handle the given INTENT.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  query-services [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints all services that can handle the given INTENT.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  query-receivers [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints all broadcast receivers that can handle the given INTENT.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  install [-lrtsfdg] [-i PACKAGE] [--user USER_ID|all|current]");
        pw.println("       [-p INHERIT_PACKAGE] [--install-location 0/1/2]");
        pw.println("       [--originating-uri URI] [---referrer URI]");
        pw.println("       [--abi ABI_NAME] [--force-sdk]");
        pw.println("       [--preload] [--instantapp] [--full] [--dont-kill]");
        pw.println("       [--force-uuid internal|UUID] [--pkg PACKAGE] [-S BYTES] [PATH|-]");
        pw.println("    Install an application.  Must provide the apk data to install, either as a");
        pw.println("    file path or '-' to read from stdin.  Options are:");
        pw.println("      -l: forward lock application");
        pw.println("      -R: disallow replacement of existing application");
        pw.println("      -t: allow test packages");
        pw.println("      -i: specify package name of installer owning the app");
        pw.println("      -s: install application on sdcard");
        pw.println("      -f: install application on internal flash");
        pw.println("      -d: allow version code downgrade (debuggable packages only)");
        pw.println("      -p: partial application install (new split on top of existing pkg)");
        pw.println("      -g: grant all runtime permissions");
        pw.println("      -S: size in bytes of package, required for stdin");
        pw.println("      --user: install under the given user.");
        pw.println("      --dont-kill: installing a new feature split, don't kill running app");
        pw.println("      --originating-uri: set URI where app was downloaded from");
        pw.println("      --referrer: set URI that instigated the install of the app");
        pw.println("      --pkg: specify expected package name of app being installed");
        pw.println("      --abi: override the default ABI of the platform");
        pw.println("      --instantapp: cause the app to be installed as an ephemeral install app");
        pw.println("      --full: cause the app to be installed as a non-ephemeral full app");
        pw.println("      --install-location: force the install location:");
        pw.println("          0=auto, 1=internal only, 2=prefer external");
        pw.println("      --force-uuid: force install on to disk volume with given UUID");
        pw.println("      --force-sdk: allow install even when existing app targets platform");
        pw.println("          codename but new one targets a final API level");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  install-create [-lrtsfdg] [-i PACKAGE] [--user USER_ID|all|current]");
        pw.println("       [-p INHERIT_PACKAGE] [--install-location 0/1/2]");
        pw.println("       [--originating-uri URI] [---referrer URI]");
        pw.println("       [--abi ABI_NAME] [--force-sdk]");
        pw.println("       [--preload] [--instantapp] [--full] [--dont-kill]");
        pw.println("       [--force-uuid internal|UUID] [--pkg PACKAGE] [-S BYTES]");
        pw.println("    Like \"install\", but starts an install session.  Use \"install-write\"");
        pw.println("    to push data into the session, and \"install-commit\" to finish.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH|-]");
        pw.println("    Write an apk into the given install session.  If the path is '-', data");
        pw.println("    will be read from stdin.  Options are:");
        pw.println("      -S: size in bytes of package, required for stdin");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  install-commit SESSION_ID");
        pw.println("    Commit the given active install session, installing the app.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  install-abandon SESSION_ID");
        pw.println("    Delete the given active install session.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  set-install-location LOCATION");
        pw.println("    Changes the default install location.  NOTE this is only intended for debugging;");
        pw.println("    using this can cause applications to break and other undersireable behavior.");
        pw.println("    LOCATION is one of:");
        pw.println("    0 [auto]: Let system decide the best location");
        pw.println("    1 [internal]: Install on internal device storage");
        pw.println("    2 [external]: Install on external media");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-install-location");
        pw.println("    Returns the current install location: 0, 1 or 2 as per set-install-location.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  move-package PACKAGE [internal|UUID]");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  move-primary-storage [internal|UUID]");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  pm uninstall [-k] [--user USER_ID] [--versionCode VERSION_CODE] PACKAGE [SPLIT]");
        pw.println("    Remove the given package name from the system.  May remove an entire app");
        pw.println("    if no SPLIT name is specified, otherwise will remove only the split of the");
        pw.println("    given app.  Options are:");
        pw.println("      -k: keep the data and cache directories around after package removal.");
        pw.println("      --user: remove the app from the given user.");
        pw.println("      --versionCode: only uninstall if the app has the given version code.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  clear [--user USER_ID] PACKAGE");
        pw.println("    Deletes all data associated with a package.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  enable [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  disable [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  disable-user [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  disable-until-used [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  default-state [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("    These commands change the enabled state of a given package or");
        pw.println("    component (written as \"package/class\").");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  hide [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  unhide [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  suspend [--user USER_ID] TARGET-PACKAGE");
        pw.println("    Suspends the specified package (as user).");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  unsuspend [--user USER_ID] TARGET-PACKAGE");
        pw.println("    Unsuspends the specified package (as user).");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  grant [--user USER_ID] PACKAGE PERMISSION");
        pw.println("  revoke [--user USER_ID] PACKAGE PERMISSION");
        pw.println("    These commands either grant or revoke permissions to apps.  The permissions");
        pw.println("    must be declared as used in the app's manifest, be runtime permissions");
        pw.println("    (protection level dangerous), and the app targeting SDK greater than Lollipop MR1.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  reset-permissions");
        pw.println("    Revert all runtime permissions to their default state.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  set-permission-enforced PERMISSION [true|false]");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-privapp-permissions TARGET-PACKAGE");
        pw.println("    Prints all privileged permissions for a package.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-privapp-deny-permissions TARGET-PACKAGE");
        pw.println("    Prints all privileged permissions that are denied for a package.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-oem-permissions TARGET-PACKAGE");
        pw.println("    Prints all OEM permissions for a package.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  set-app-link [--user USER_ID] PACKAGE {always|ask|never|undefined}");
        pw.println("  get-app-link [--user USER_ID] PACKAGE");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  trim-caches DESIRED_FREE_SPACE [internal|UUID]");
        pw.println("    Trim cache files to reach the given free space.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  create-user [--profileOf USER_ID] [--managed] [--restricted] [--ephemeral]");
        pw.println("      [--guest] USER_NAME");
        pw.println("    Create a new user with the given USER_NAME, printing the new user identifier");
        pw.println("    of the user.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  remove-user USER_ID");
        pw.println("    Remove the user with the given USER_IDENTIFIER, deleting all data");
        pw.println("    associated with that user");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  set-user-restriction [--user USER_ID] RESTRICTION VALUE");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-max-users");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-max-running-users");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  compile [-m MODE | -r REASON] [-f] [-c] [--split SPLIT_NAME]");
        pw.println("          [--reset] [--check-prof (true | false)] (-a | TARGET-PACKAGE)");
        pw.println("    Trigger compilation of TARGET-PACKAGE or all packages if \"-a\".  Options are:");
        pw.println("      -a: compile all packages");
        pw.println("      -c: clear profile data before compiling");
        pw.println("      -f: force compilation even if not needed");
        pw.println("      -m: select compilation mode");
        pw.println("          MODE is one of the dex2oat compiler filters:");
        pw.println("            assume-verified");
        pw.println("            extract");
        pw.println("            verify");
        pw.println("            quicken");
        pw.println("            space-profile");
        pw.println("            space");
        pw.println("            speed-profile");
        pw.println("            speed");
        pw.println("            everything");
        pw.println("      -r: select compilation reason");
        pw.println("          REASON is one of:");
        for (int i = 0; i < PackageManagerServiceCompilerMapping.REASON_STRINGS.length; i++) {
            pw.println("            " + PackageManagerServiceCompilerMapping.REASON_STRINGS[i]);
        }
        pw.println("      --reset: restore package to its post-install state");
        pw.println("      --check-prof (true | false): look at profiles when doing dexopt?");
        pw.println("      --secondary-dex: compile app secondary dex files");
        pw.println("      --split SPLIT: compile only the given split name");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  force-dex-opt PACKAGE");
        pw.println("    Force immediate execution of dex opt for the given PACKAGE.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  bg-dexopt-job");
        pw.println("    Execute the background optimizations immediately.");
        pw.println("    Note that the command only runs the background optimizer logic. It may");
        pw.println("    overlap with the actual job but the job scheduler will not be able to");
        pw.println("    cancel it. It will also run even if the device is not in the idle");
        pw.println("    maintenance mode.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  reconcile-secondary-dex-files TARGET-PACKAGE");
        pw.println("    Reconciles the package secondary dex files with the generated oat files.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  dump-profiles TARGET-PACKAGE");
        pw.println("    Dumps method/class profile files to");
        pw.println("    /data/misc/profman/TARGET-PACKAGE.txt");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  snapshot-profile TARGET-PACKAGE [--code-path path]");
        pw.println("    Take a snapshot of the package profiles to");
        pw.println("    /data/misc/profman/TARGET-PACKAGE[-code-path].prof");
        pw.println("    If TARGET-PACKAGE=android it will take a snapshot of the boot image");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  set-home-activity [--user USER_ID] TARGET-COMPONENT");
        pw.println("    Set the default home activity (aka launcher).");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  set-installer PACKAGE INSTALLER");
        pw.println("    Set installer package name");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-instantapp-resolver");
        pw.println("    Return the name of the component that is the current instant app installer.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  set-harmful-app-warning [--user <USER_ID>] <PACKAGE> [<WARNING>]");
        pw.println("    Mark the app as harmful with the given warning message.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  get-harmful-app-warning [--user <USER_ID>] <PACKAGE>");
        pw.println("    Return the harmful app warning message for the given app, if present");
        pw.println();
        pw.println("  uninstall-system-updates");
        pw.println("    Remove updates to all system applications and fall back to their /system version.");
        pw.println();
        Intent.printIntentArgsHelp(pw, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class LocalIntentReceiver {
        private IIntentSender.Stub mLocalSender;
        private final SynchronousQueue<Intent> mResult;

        private LocalIntentReceiver() {
            this.mResult = new SynchronousQueue<>();
            this.mLocalSender = new IIntentSender.Stub() { // from class: com.android.server.pm.PackageManagerShellCommand.LocalIntentReceiver.1
                public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken, IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                    try {
                        LocalIntentReceiver.this.mResult.offer(intent, 5L, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }

        public IntentSender getIntentSender() {
            return new IntentSender(this.mLocalSender);
        }

        public Intent getResult() {
            try {
                return this.mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
