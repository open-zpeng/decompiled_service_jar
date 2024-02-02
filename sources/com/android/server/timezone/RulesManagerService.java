package com.android.server.timezone;

import android.app.timezone.DistroFormatVersion;
import android.app.timezone.DistroRulesVersion;
import android.app.timezone.ICallback;
import android.app.timezone.IRulesManager;
import android.app.timezone.RulesState;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.EventLogTags;
import com.android.server.NetworkManagementService;
import com.android.server.SystemService;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.timezone.distro.DistroException;
import com.android.timezone.distro.DistroVersion;
import com.android.timezone.distro.StagedDistroOperation;
import com.android.timezone.distro.TimeZoneDistro;
import com.android.timezone.distro.installer.TimeZoneDistroInstaller;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import libcore.icu.ICU;
import libcore.util.TimeZoneFinder;
import libcore.util.ZoneInfoDB;
/* loaded from: classes.dex */
public final class RulesManagerService extends IRulesManager.Stub {
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final String REQUIRED_QUERY_PERMISSION = "android.permission.QUERY_TIME_ZONE_RULES";
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final String REQUIRED_UPDATER_PERMISSION = "android.permission.UPDATE_TIME_ZONE_RULES";
    private static final String TAG = "timezone.RulesManagerService";
    private final Executor mExecutor;
    private final TimeZoneDistroInstaller mInstaller;
    private final RulesManagerIntentHelper mIntentHelper;
    private final AtomicBoolean mOperationInProgress = new AtomicBoolean(false);
    private final PackageTracker mPackageTracker;
    private final PermissionHelper mPermissionHelper;
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final DistroFormatVersion DISTRO_FORMAT_VERSION_SUPPORTED = new DistroFormatVersion(2, 1);
    private static final File SYSTEM_TZ_DATA_FILE = new File("/system/usr/share/zoneinfo/tzdata");
    private static final File TZ_DATA_DIR = new File("/data/misc/zoneinfo");

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
        }

        /* JADX WARN: Multi-variable type inference failed */
        /* JADX WARN: Type inference failed for: r0v1, types: [com.android.server.timezone.RulesManagerService, java.lang.Object, android.os.IBinder] */
        @Override // com.android.server.SystemService
        public void onStart() {
            ?? create = RulesManagerService.create(getContext());
            create.start();
            publishBinderService("timezone", create);
            publishLocalService(RulesManagerService.class, create);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static RulesManagerService create(Context context) {
        RulesManagerServiceHelperImpl helper = new RulesManagerServiceHelperImpl(context);
        return new RulesManagerService(helper, helper, helper, PackageTracker.create(context), new TimeZoneDistroInstaller(TAG, SYSTEM_TZ_DATA_FILE, TZ_DATA_DIR));
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    RulesManagerService(PermissionHelper permissionHelper, Executor executor, RulesManagerIntentHelper intentHelper, PackageTracker packageTracker, TimeZoneDistroInstaller timeZoneDistroInstaller) {
        this.mPermissionHelper = permissionHelper;
        this.mExecutor = executor;
        this.mIntentHelper = intentHelper;
        this.mPackageTracker = packageTracker;
        this.mInstaller = timeZoneDistroInstaller;
    }

    public void start() {
        this.mPackageTracker.start();
    }

    public RulesState getRulesState() {
        this.mPermissionHelper.enforceCallerHasPermission(REQUIRED_QUERY_PERMISSION);
        return getRulesStateInternal();
    }

    private RulesState getRulesStateInternal() {
        RulesState rulesState;
        synchronized (this) {
            DistroRulesVersion installedDistroRulesVersion = null;
            try {
                try {
                    String systemRulesVersion = this.mInstaller.getSystemRulesVersion();
                    int distroStatus = 0;
                    try {
                        DistroVersion installedDistroVersion = this.mInstaller.getInstalledDistroVersion();
                        if (installedDistroVersion == null) {
                            distroStatus = 1;
                            installedDistroRulesVersion = null;
                        } else {
                            distroStatus = 2;
                            installedDistroRulesVersion = new DistroRulesVersion(installedDistroVersion.rulesVersion, installedDistroVersion.revision);
                        }
                    } catch (DistroException | IOException e) {
                        Slog.w(TAG, "Failed to read installed distro.", e);
                    }
                    boolean operationInProgress = this.mOperationInProgress.get();
                    DistroRulesVersion stagedDistroRulesVersion = null;
                    int stagedOperationStatus = 0;
                    if (!operationInProgress) {
                        try {
                            StagedDistroOperation stagedDistroOperation = this.mInstaller.getStagedDistroOperation();
                            if (stagedDistroOperation == null) {
                                stagedOperationStatus = 1;
                            } else if (stagedDistroOperation.isUninstall) {
                                stagedOperationStatus = 2;
                            } else {
                                stagedOperationStatus = 3;
                                DistroVersion stagedDistroVersion = stagedDistroOperation.distroVersion;
                                stagedDistroRulesVersion = new DistroRulesVersion(stagedDistroVersion.rulesVersion, stagedDistroVersion.revision);
                            }
                        } catch (DistroException | IOException e2) {
                            Slog.w(TAG, "Failed to read staged distro.", e2);
                        }
                    }
                    int stagedOperationStatus2 = stagedOperationStatus;
                    rulesState = new RulesState(systemRulesVersion, DISTRO_FORMAT_VERSION_SUPPORTED, operationInProgress, stagedOperationStatus2, stagedDistroRulesVersion, distroStatus, installedDistroRulesVersion);
                } catch (IOException e3) {
                    Slog.w(TAG, "Failed to read system rules", e3);
                    return null;
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        return rulesState;
    }

    public int requestInstall(ParcelFileDescriptor distroParcelFileDescriptor, byte[] checkTokenBytes, ICallback callback) {
        boolean closeParcelFileDescriptorOnExit = true;
        try {
            this.mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
            CheckToken checkToken = checkTokenBytes != null ? createCheckTokenOrThrow(checkTokenBytes) : null;
            EventLogTags.writeTimezoneRequestInstall(toStringOrNull(checkToken));
            synchronized (this) {
                try {
                    if (distroParcelFileDescriptor != null) {
                        if (callback != null) {
                            if (this.mOperationInProgress.get()) {
                                return 1;
                            }
                            this.mOperationInProgress.set(true);
                            this.mExecutor.execute(new InstallRunnable(distroParcelFileDescriptor, checkToken, callback));
                            try {
                                if (distroParcelFileDescriptor != null && 0 != 0) {
                                    try {
                                        distroParcelFileDescriptor.close();
                                    } catch (IOException e) {
                                        Slog.w(TAG, "Failed to close distroParcelFileDescriptor", e);
                                    }
                                }
                                return 0;
                            } catch (Throwable th) {
                                closeParcelFileDescriptorOnExit = false;
                                th = th;
                                throw th;
                            }
                        }
                        throw new NullPointerException("observer == null");
                    }
                    throw new NullPointerException("distroParcelFileDescriptor == null");
                } catch (Throwable th2) {
                    th = th2;
                }
            }
        } finally {
            if (distroParcelFileDescriptor != null && closeParcelFileDescriptorOnExit) {
                try {
                    distroParcelFileDescriptor.close();
                } catch (IOException e2) {
                    Slog.w(TAG, "Failed to close distroParcelFileDescriptor", e2);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private class InstallRunnable implements Runnable {
        private final ICallback mCallback;
        private final CheckToken mCheckToken;
        private final ParcelFileDescriptor mDistroParcelFileDescriptor;

        InstallRunnable(ParcelFileDescriptor distroParcelFileDescriptor, CheckToken checkToken, ICallback callback) {
            this.mDistroParcelFileDescriptor = distroParcelFileDescriptor;
            this.mCheckToken = checkToken;
            this.mCallback = callback;
        }

        @Override // java.lang.Runnable
        public void run() {
            ParcelFileDescriptor pfd;
            EventLogTags.writeTimezoneInstallStarted(RulesManagerService.toStringOrNull(this.mCheckToken));
            boolean success = false;
            try {
                try {
                    pfd = this.mDistroParcelFileDescriptor;
                } catch (Exception e) {
                    Slog.w(RulesManagerService.TAG, "Failed to install distro.", e);
                    EventLogTags.writeTimezoneInstallComplete(RulesManagerService.toStringOrNull(this.mCheckToken), 1);
                    RulesManagerService.this.sendFinishedStatus(this.mCallback, 1);
                }
                try {
                    InputStream is = new FileInputStream(pfd.getFileDescriptor(), false);
                    TimeZoneDistro distro = new TimeZoneDistro(is);
                    int installerResult = RulesManagerService.this.mInstaller.stageInstallWithErrorCode(distro);
                    sendInstallNotificationIntentIfRequired(installerResult);
                    int resultCode = mapInstallerResultToApiCode(installerResult);
                    EventLogTags.writeTimezoneInstallComplete(RulesManagerService.toStringOrNull(this.mCheckToken), resultCode);
                    RulesManagerService.this.sendFinishedStatus(this.mCallback, resultCode);
                    success = true;
                    if (pfd != null) {
                        pfd.close();
                    }
                } catch (Throwable th) {
                    try {
                        throw th;
                    } catch (Throwable th2) {
                        if (pfd != null) {
                            if (th != null) {
                                try {
                                    pfd.close();
                                } catch (Throwable th3) {
                                    th.addSuppressed(th3);
                                }
                            } else {
                                pfd.close();
                            }
                        }
                        throw th2;
                    }
                }
            } finally {
                RulesManagerService.this.mPackageTracker.recordCheckResult(this.mCheckToken, false);
                RulesManagerService.this.mOperationInProgress.set(false);
            }
        }

        private void sendInstallNotificationIntentIfRequired(int installerResult) {
            if (installerResult == 0) {
                RulesManagerService.this.mIntentHelper.sendTimeZoneOperationStaged();
            }
        }

        private int mapInstallerResultToApiCode(int installerResult) {
            switch (installerResult) {
                case 0:
                    return 0;
                case 1:
                    return 2;
                case 2:
                    return 3;
                case 3:
                    return 4;
                case 4:
                    return 5;
                default:
                    return 1;
            }
        }
    }

    public int requestUninstall(byte[] checkTokenBytes, ICallback callback) {
        this.mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
        CheckToken checkToken = null;
        if (checkTokenBytes != null) {
            checkToken = createCheckTokenOrThrow(checkTokenBytes);
        }
        EventLogTags.writeTimezoneRequestUninstall(toStringOrNull(checkToken));
        synchronized (this) {
            if (callback == null) {
                throw new NullPointerException("callback == null");
            }
            if (this.mOperationInProgress.get()) {
                return 1;
            }
            this.mOperationInProgress.set(true);
            this.mExecutor.execute(new UninstallRunnable(checkToken, callback));
            return 0;
        }
    }

    /* loaded from: classes.dex */
    private class UninstallRunnable implements Runnable {
        private final ICallback mCallback;
        private final CheckToken mCheckToken;

        UninstallRunnable(CheckToken checkToken, ICallback callback) {
            this.mCheckToken = checkToken;
            this.mCallback = callback;
        }

        /* JADX WARN: Removed duplicated region for block: B:11:0x0024  */
        /* JADX WARN: Removed duplicated region for block: B:12:0x0026  */
        @Override // java.lang.Runnable
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void run() {
            /*
                r7 = this;
                com.android.server.timezone.CheckToken r0 = r7.mCheckToken
                java.lang.String r0 = com.android.server.timezone.RulesManagerService.access$100(r0)
                com.android.server.EventLogTags.writeTimezoneUninstallStarted(r0)
                r0 = 0
                r1 = r0
                r2 = 1
                com.android.server.timezone.RulesManagerService r3 = com.android.server.timezone.RulesManagerService.this     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.timezone.distro.installer.TimeZoneDistroInstaller r3 = com.android.server.timezone.RulesManagerService.access$200(r3)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                int r3 = r3.stageUninstall()     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                r7.sendUninstallNotificationIntentIfRequired(r3)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                if (r3 == 0) goto L20
                if (r3 != r2) goto L1e
                goto L20
            L1e:
                r4 = r0
                goto L21
            L20:
                r4 = r2
            L21:
                r1 = r4
                if (r1 == 0) goto L26
                r4 = r0
                goto L27
            L26:
                r4 = r2
            L27:
                com.android.server.timezone.CheckToken r5 = r7.mCheckToken     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                java.lang.String r5 = com.android.server.timezone.RulesManagerService.access$100(r5)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.server.EventLogTags.writeTimezoneUninstallComplete(r5, r4)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.server.timezone.RulesManagerService r5 = com.android.server.timezone.RulesManagerService.this     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                android.app.timezone.ICallback r6 = r7.mCallback     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.server.timezone.RulesManagerService.access$300(r5, r6, r4)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                goto L53
            L38:
                r2 = move-exception
                goto L69
            L3a:
                r3 = move-exception
                com.android.server.timezone.CheckToken r4 = r7.mCheckToken     // Catch: java.lang.Throwable -> L38
                java.lang.String r4 = com.android.server.timezone.RulesManagerService.access$100(r4)     // Catch: java.lang.Throwable -> L38
                com.android.server.EventLogTags.writeTimezoneUninstallComplete(r4, r2)     // Catch: java.lang.Throwable -> L38
                java.lang.String r4 = "timezone.RulesManagerService"
                java.lang.String r5 = "Failed to uninstall distro."
                android.util.Slog.w(r4, r5, r3)     // Catch: java.lang.Throwable -> L38
                com.android.server.timezone.RulesManagerService r4 = com.android.server.timezone.RulesManagerService.this     // Catch: java.lang.Throwable -> L38
                android.app.timezone.ICallback r5 = r7.mCallback     // Catch: java.lang.Throwable -> L38
                com.android.server.timezone.RulesManagerService.access$300(r4, r5, r2)     // Catch: java.lang.Throwable -> L38
            L53:
                com.android.server.timezone.RulesManagerService r2 = com.android.server.timezone.RulesManagerService.this
                com.android.server.timezone.PackageTracker r2 = com.android.server.timezone.RulesManagerService.access$400(r2)
                com.android.server.timezone.CheckToken r3 = r7.mCheckToken
                r2.recordCheckResult(r3, r1)
                com.android.server.timezone.RulesManagerService r2 = com.android.server.timezone.RulesManagerService.this
                java.util.concurrent.atomic.AtomicBoolean r2 = com.android.server.timezone.RulesManagerService.access$500(r2)
                r2.set(r0)
                return
            L69:
                com.android.server.timezone.RulesManagerService r3 = com.android.server.timezone.RulesManagerService.this
                com.android.server.timezone.PackageTracker r3 = com.android.server.timezone.RulesManagerService.access$400(r3)
                com.android.server.timezone.CheckToken r4 = r7.mCheckToken
                r3.recordCheckResult(r4, r1)
                com.android.server.timezone.RulesManagerService r3 = com.android.server.timezone.RulesManagerService.this
                java.util.concurrent.atomic.AtomicBoolean r3 = com.android.server.timezone.RulesManagerService.access$500(r3)
                r3.set(r0)
                throw r2
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.timezone.RulesManagerService.UninstallRunnable.run():void");
        }

        private void sendUninstallNotificationIntentIfRequired(int uninstallResult) {
            switch (uninstallResult) {
                case 0:
                    RulesManagerService.this.mIntentHelper.sendTimeZoneOperationStaged();
                    return;
                case 1:
                    RulesManagerService.this.mIntentHelper.sendTimeZoneOperationUnstaged();
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendFinishedStatus(ICallback callback, int resultCode) {
        try {
            callback.onFinished(resultCode);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to notify observer of result", e);
        }
    }

    public void requestNothing(byte[] checkTokenBytes, boolean success) {
        this.mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
        CheckToken checkToken = null;
        if (checkTokenBytes != null) {
            checkToken = createCheckTokenOrThrow(checkTokenBytes);
        }
        EventLogTags.writeTimezoneRequestNothing(toStringOrNull(checkToken));
        this.mPackageTracker.recordCheckResult(checkToken, success);
        EventLogTags.writeTimezoneNothingComplete(toStringOrNull(checkToken));
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        char[] charArray;
        if (!this.mPermissionHelper.checkDumpPermission(TAG, pw)) {
            return;
        }
        RulesState rulesState = getRulesStateInternal();
        if (args != null && args.length == 2) {
            if ("-format_state".equals(args[0]) && args[1] != null) {
                for (char c : args[1].toCharArray()) {
                    switch (c) {
                        case HdmiCecKeycode.CEC_KEYCODE_PAUSE_PLAY_FUNCTION /* 97 */:
                            pw.println("Active rules version (ICU, ZoneInfoDB, TimeZoneFinder): " + ICU.getTZDataVersion() + "," + ZoneInfoDB.getInstance().getVersion() + "," + TimeZoneFinder.getInstance().getIanaVersion());
                            break;
                        case 'c':
                            String value = "Unknown";
                            if (rulesState != null) {
                                value = distroStatusToString(rulesState.getDistroStatus());
                            }
                            pw.println("Current install state: " + value);
                            break;
                        case HdmiCecKeycode.CEC_KEYCODE_SELECT_AV_INPUT_FUNCTION /* 105 */:
                            String value2 = "Unknown";
                            if (rulesState != null) {
                                DistroRulesVersion installedRulesVersion = rulesState.getInstalledDistroRulesVersion();
                                if (installedRulesVersion == null) {
                                    value2 = "<None>";
                                } else {
                                    value2 = installedRulesVersion.toDumpString();
                                }
                            }
                            pw.println("Installed rules version: " + value2);
                            break;
                        case NetworkManagementService.NetdResponseCode.TetherInterfaceListResult /* 111 */:
                            String value3 = "Unknown";
                            if (rulesState != null) {
                                int stagedOperationType = rulesState.getStagedOperationType();
                                value3 = stagedOperationToString(stagedOperationType);
                            }
                            pw.println("Staged operation: " + value3);
                            break;
                        case 'p':
                            String value4 = "Unknown";
                            if (rulesState != null) {
                                value4 = Boolean.toString(rulesState.isOperationInProgress());
                            }
                            pw.println("Operation in progress: " + value4);
                            break;
                        case HdmiCecKeycode.CEC_KEYCODE_F3_GREEN /* 115 */:
                            String value5 = "Unknown";
                            if (rulesState != null) {
                                value5 = rulesState.getSystemRulesVersion();
                            }
                            pw.println("System rules version: " + value5);
                            break;
                        case HdmiCecKeycode.CEC_KEYCODE_F4_YELLOW /* 116 */:
                            String value6 = "Unknown";
                            if (rulesState != null) {
                                DistroRulesVersion stagedDistroRulesVersion = rulesState.getStagedDistroRulesVersion();
                                if (stagedDistroRulesVersion == null) {
                                    value6 = "<None>";
                                } else {
                                    value6 = stagedDistroRulesVersion.toDumpString();
                                }
                            }
                            pw.println("Staged rules version: " + value6);
                            break;
                        default:
                            pw.println("Unknown option: " + c);
                            break;
                    }
                }
                return;
            }
        }
        pw.println("RulesManagerService state: " + toString());
        pw.println("Active rules version (ICU, ZoneInfoDB, TimeZoneFinder): " + ICU.getTZDataVersion() + "," + ZoneInfoDB.getInstance().getVersion() + "," + TimeZoneFinder.getInstance().getIanaVersion());
        StringBuilder sb = new StringBuilder();
        sb.append("Distro state: ");
        sb.append(rulesState.toString());
        pw.println(sb.toString());
        this.mPackageTracker.dump(pw);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyIdle() {
        this.mPackageTracker.triggerUpdateIfNeeded(false);
    }

    public String toString() {
        return "RulesManagerService{mOperationInProgress=" + this.mOperationInProgress + '}';
    }

    private static CheckToken createCheckTokenOrThrow(byte[] checkTokenBytes) {
        try {
            CheckToken checkToken = CheckToken.fromByteArray(checkTokenBytes);
            return checkToken;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read token bytes " + Arrays.toString(checkTokenBytes), e);
        }
    }

    private static String distroStatusToString(int distroStatus) {
        switch (distroStatus) {
            case 1:
                return "None";
            case 2:
                return "Installed";
            default:
                return "Unknown";
        }
    }

    private static String stagedOperationToString(int stagedOperationType) {
        switch (stagedOperationType) {
            case 1:
                return "None";
            case 2:
                return "Uninstall";
            case 3:
                return "Install";
            default:
                return "Unknown";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String toStringOrNull(Object obj) {
        if (obj == null) {
            return null;
        }
        return obj.toString();
    }
}
