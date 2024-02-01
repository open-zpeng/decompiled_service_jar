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
import libcore.timezone.TimeZoneDataFiles;
import libcore.timezone.TimeZoneFinder;
import libcore.timezone.TzDataSetVersion;
import libcore.timezone.ZoneInfoDB;

/* loaded from: classes2.dex */
public final class RulesManagerService extends IRulesManager.Stub {
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static final DistroFormatVersion DISTRO_FORMAT_VERSION_SUPPORTED = new DistroFormatVersion(TzDataSetVersion.currentFormatMajorVersion(), TzDataSetVersion.currentFormatMinorVersion());
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

    /* loaded from: classes2.dex */
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
        File baseVersionFile = new File(TimeZoneDataFiles.getRuntimeModuleTzVersionFile());
        File tzDataDir = new File(TimeZoneDataFiles.getDataTimeZoneRootDir());
        return new RulesManagerService(helper, helper, helper, PackageTracker.create(context), new TimeZoneDistroInstaller(TAG, baseVersionFile, tzDataDir));
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
            try {
                try {
                    TzDataSetVersion baseVersion = this.mInstaller.readBaseVersion();
                    int distroStatus = 0;
                    DistroRulesVersion installedDistroRulesVersion = null;
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
                    rulesState = new RulesState(baseVersion.rulesVersion, DISTRO_FORMAT_VERSION_SUPPORTED, operationInProgress, stagedOperationStatus, stagedDistroRulesVersion, distroStatus, installedDistroRulesVersion);
                } catch (IOException e3) {
                    Slog.w(TAG, "Failed to read base rules version", e3);
                    return null;
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        return rulesState;
    }

    public int requestInstall(ParcelFileDescriptor distroParcelFileDescriptor, byte[] checkTokenBytes, ICallback callback) {
        try {
            this.mPermissionHelper.enforceCallerHasPermission(REQUIRED_UPDATER_PERMISSION);
            CheckToken checkToken = checkTokenBytes != null ? createCheckTokenOrThrow(checkTokenBytes) : null;
            EventLogTags.writeTimezoneRequestInstall(toStringOrNull(checkToken));
            synchronized (this) {
                if (distroParcelFileDescriptor != null) {
                    if (callback != null) {
                        if (this.mOperationInProgress.get()) {
                            if (1 != 0) {
                                try {
                                    distroParcelFileDescriptor.close();
                                } catch (IOException e) {
                                    Slog.w(TAG, "Failed to close distroParcelFileDescriptor", e);
                                }
                            }
                            return 1;
                        }
                        this.mOperationInProgress.set(true);
                        this.mExecutor.execute(new InstallRunnable(distroParcelFileDescriptor, checkToken, callback));
                        if (0 != 0) {
                            try {
                                distroParcelFileDescriptor.close();
                            } catch (IOException e2) {
                                Slog.w(TAG, "Failed to close distroParcelFileDescriptor", e2);
                            }
                        }
                        return 0;
                    }
                    throw new NullPointerException("observer == null");
                }
                throw new NullPointerException("distroParcelFileDescriptor == null");
            }
        } catch (Throwable th) {
            if (distroParcelFileDescriptor != null && 1 != 0) {
                try {
                    distroParcelFileDescriptor.close();
                } catch (IOException e3) {
                    Slog.w(TAG, "Failed to close distroParcelFileDescriptor", e3);
                }
            }
            throw th;
        }
    }

    /* loaded from: classes2.dex */
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
                    pfd.close();
                } catch (Throwable th) {
                    try {
                        throw th;
                    } catch (Throwable th2) {
                        if (pfd != null) {
                            try {
                                pfd.close();
                            } catch (Throwable th3) {
                                th.addSuppressed(th3);
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
            if (installerResult != 0) {
                if (installerResult != 1) {
                    if (installerResult != 2) {
                        if (installerResult != 3) {
                            return installerResult != 4 ? 1 : 5;
                        }
                        return 4;
                    }
                    return 3;
                }
                return 2;
            }
            return 0;
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
            try {
                if (callback == null) {
                    throw new NullPointerException("callback == null");
                }
                if (this.mOperationInProgress.get()) {
                    return 1;
                }
                this.mOperationInProgress.set(true);
                this.mExecutor.execute(new UninstallRunnable(checkToken, callback));
                return 0;
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    /* loaded from: classes2.dex */
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
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void run() {
            /*
                r7 = this;
                com.android.server.timezone.CheckToken r0 = r7.mCheckToken
                java.lang.String r0 = com.android.server.timezone.RulesManagerService.access$100(r0)
                com.android.server.EventLogTags.writeTimezoneUninstallStarted(r0)
                r0 = 0
                r1 = 1
                r2 = 0
                com.android.server.timezone.RulesManagerService r3 = com.android.server.timezone.RulesManagerService.this     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.timezone.distro.installer.TimeZoneDistroInstaller r3 = com.android.server.timezone.RulesManagerService.access$200(r3)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                int r3 = r3.stageUninstall()     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                r7.sendUninstallNotificationIntentIfRequired(r3)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                if (r3 == 0) goto L20
                if (r3 != r1) goto L1e
                goto L20
            L1e:
                r4 = r2
                goto L21
            L20:
                r4 = r1
            L21:
                r0 = r4
                if (r0 == 0) goto L26
                r4 = r2
                goto L27
            L26:
                r4 = r1
            L27:
                com.android.server.timezone.CheckToken r5 = r7.mCheckToken     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                java.lang.String r5 = com.android.server.timezone.RulesManagerService.access$100(r5)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.server.EventLogTags.writeTimezoneUninstallComplete(r5, r4)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.server.timezone.RulesManagerService r5 = com.android.server.timezone.RulesManagerService.this     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                android.app.timezone.ICallback r6 = r7.mCallback     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                com.android.server.timezone.RulesManagerService.access$300(r5, r6, r4)     // Catch: java.lang.Throwable -> L38 java.lang.Exception -> L3a
                goto L52
            L38:
                r1 = move-exception
                goto L68
            L3a:
                r3 = move-exception
                com.android.server.timezone.CheckToken r4 = r7.mCheckToken     // Catch: java.lang.Throwable -> L38
                java.lang.String r4 = com.android.server.timezone.RulesManagerService.access$100(r4)     // Catch: java.lang.Throwable -> L38
                com.android.server.EventLogTags.writeTimezoneUninstallComplete(r4, r1)     // Catch: java.lang.Throwable -> L38
                java.lang.String r4 = "timezone.RulesManagerService"
                java.lang.String r5 = "Failed to uninstall distro."
                android.util.Slog.w(r4, r5, r3)     // Catch: java.lang.Throwable -> L38
                com.android.server.timezone.RulesManagerService r4 = com.android.server.timezone.RulesManagerService.this     // Catch: java.lang.Throwable -> L38
                android.app.timezone.ICallback r5 = r7.mCallback     // Catch: java.lang.Throwable -> L38
                com.android.server.timezone.RulesManagerService.access$300(r4, r5, r1)     // Catch: java.lang.Throwable -> L38
            L52:
                com.android.server.timezone.RulesManagerService r1 = com.android.server.timezone.RulesManagerService.this
                com.android.server.timezone.PackageTracker r1 = com.android.server.timezone.RulesManagerService.access$400(r1)
                com.android.server.timezone.CheckToken r3 = r7.mCheckToken
                r1.recordCheckResult(r3, r0)
                com.android.server.timezone.RulesManagerService r1 = com.android.server.timezone.RulesManagerService.this
                java.util.concurrent.atomic.AtomicBoolean r1 = com.android.server.timezone.RulesManagerService.access$500(r1)
                r1.set(r2)
                return
            L68:
                com.android.server.timezone.RulesManagerService r3 = com.android.server.timezone.RulesManagerService.this
                com.android.server.timezone.PackageTracker r3 = com.android.server.timezone.RulesManagerService.access$400(r3)
                com.android.server.timezone.CheckToken r4 = r7.mCheckToken
                r3.recordCheckResult(r4, r0)
                com.android.server.timezone.RulesManagerService r3 = com.android.server.timezone.RulesManagerService.this
                java.util.concurrent.atomic.AtomicBoolean r3 = com.android.server.timezone.RulesManagerService.access$500(r3)
                r3.set(r2)
                throw r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.timezone.RulesManagerService.UninstallRunnable.run():void");
        }

        private void sendUninstallNotificationIntentIfRequired(int uninstallResult) {
            if (uninstallResult == 0) {
                RulesManagerService.this.mIntentHelper.sendTimeZoneOperationStaged();
            } else if (uninstallResult == 1) {
                RulesManagerService.this.mIntentHelper.sendTimeZoneOperationUnstaged();
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
                    if (c == 'i') {
                        String value = "Unknown";
                        if (rulesState != null) {
                            DistroRulesVersion installedRulesVersion = rulesState.getInstalledDistroRulesVersion();
                            if (installedRulesVersion == null) {
                                value = "<None>";
                            } else {
                                value = installedRulesVersion.toDumpString();
                            }
                        }
                        pw.println("Installed rules version: " + value);
                    } else if (c == 't') {
                        String value2 = "Unknown";
                        if (rulesState != null) {
                            DistroRulesVersion stagedDistroRulesVersion = rulesState.getStagedDistroRulesVersion();
                            if (stagedDistroRulesVersion == null) {
                                value2 = "<None>";
                            } else {
                                value2 = stagedDistroRulesVersion.toDumpString();
                            }
                        }
                        pw.println("Staged rules version: " + value2);
                    } else if (c == 'o') {
                        String value3 = "Unknown";
                        if (rulesState != null) {
                            int stagedOperationType = rulesState.getStagedOperationType();
                            value3 = stagedOperationToString(stagedOperationType);
                        }
                        pw.println("Staged operation: " + value3);
                    } else if (c == 'p') {
                        String value4 = "Unknown";
                        if (rulesState != null) {
                            value4 = Boolean.toString(rulesState.isOperationInProgress());
                        }
                        pw.println("Operation in progress: " + value4);
                    } else {
                        switch (c) {
                            case HdmiCecKeycode.CEC_KEYCODE_PAUSE_PLAY_FUNCTION /* 97 */:
                                pw.println("Active rules version (ICU, ZoneInfoDB, TimeZoneFinder): " + ICU.getTZDataVersion() + "," + ZoneInfoDB.getInstance().getVersion() + "," + TimeZoneFinder.getInstance().getIanaVersion());
                                continue;
                            case HdmiCecKeycode.CEC_KEYCODE_RECORD_FUNCTION /* 98 */:
                                String value5 = "Unknown";
                                if (rulesState != null) {
                                    value5 = rulesState.getBaseRulesVersion();
                                }
                                pw.println("Base rules version: " + value5);
                                continue;
                            case 'c':
                                String value6 = "Unknown";
                                if (rulesState != null) {
                                    value6 = distroStatusToString(rulesState.getDistroStatus());
                                }
                                pw.println("Current install state: " + value6);
                                continue;
                            default:
                                pw.println("Unknown option: " + c);
                                continue;
                        }
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
        if (distroStatus != 1) {
            if (distroStatus == 2) {
                return "Installed";
            }
            return "Unknown";
        }
        return "None";
    }

    private static String stagedOperationToString(int stagedOperationType) {
        if (stagedOperationType != 1) {
            if (stagedOperationType != 2) {
                if (stagedOperationType == 3) {
                    return "Install";
                }
                return "Unknown";
            }
            return "Uninstall";
        }
        return "None";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String toStringOrNull(Object obj) {
        if (obj == null) {
            return null;
        }
        return obj.toString();
    }
}
