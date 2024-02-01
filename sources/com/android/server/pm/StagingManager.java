package com.android.server.pm;

import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageParser;
import android.content.pm.ParceledListSlice;
import android.content.pm.Signature;
import android.content.rollback.IRollbackManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.apk.ApkSignatureVerifier;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/* loaded from: classes.dex */
public class StagingManager {
    private static final String TAG = "StagingManager";
    private final ApexManager mApexManager;
    private final PackageInstallerService mPi;
    private final PowerManager mPowerManager;
    @GuardedBy({"mStagedSessions"})
    private final SparseArray<PackageInstallerSession> mStagedSessions = new SparseArray<>();
    private final Handler mBgHandler = BackgroundThread.getHandler();

    /* JADX INFO: Access modifiers changed from: package-private */
    public StagingManager(PackageInstallerService pi, ApexManager am, Context context) {
        this.mPi = pi;
        this.mApexManager = am;
        this.mPowerManager = (PowerManager) context.getSystemService("power");
    }

    private void updateStoredSession(PackageInstallerSession sessionInfo) {
        synchronized (this.mStagedSessions) {
            PackageInstallerSession storedSession = this.mStagedSessions.get(sessionInfo.sessionId);
            if (storedSession != null) {
                this.mStagedSessions.put(sessionInfo.sessionId, sessionInfo);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ParceledListSlice<PackageInstaller.SessionInfo> getSessions(int callingUid) {
        List<PackageInstaller.SessionInfo> result = new ArrayList<>();
        synchronized (this.mStagedSessions) {
            for (int i = 0; i < this.mStagedSessions.size(); i++) {
                PackageInstallerSession stagedSession = this.mStagedSessions.valueAt(i);
                result.add(stagedSession.generateInfoForCaller(false, callingUid));
            }
        }
        return new ParceledListSlice<>(result);
    }

    private boolean validateApexSignature(String apexPath, String packageName) {
        try {
            PackageParser.SigningDetails signingDetails = ApkSignatureVerifier.verify(apexPath, 1);
            PackageInfo packageInfo = this.mApexManager.getPackageInfoForApexName(packageName);
            if (packageInfo == null) {
                Slog.e(TAG, "Attempted to install a new apex " + packageName + ". Rejecting");
                return false;
            }
            try {
                PackageParser.SigningDetails existingSigningDetails = ApkSignatureVerifier.verify(packageInfo.applicationInfo.sourceDir, 1);
                return Signature.areExactMatch(existingSigningDetails.signatures, signingDetails.signatures);
            } catch (PackageParser.PackageParserException e) {
                Slog.e(TAG, "Unable to parse APEX package: " + packageInfo.applicationInfo.sourceDir, e);
                return false;
            }
        } catch (PackageParser.PackageParserException e2) {
            Slog.e(TAG, "Unable to parse APEX package: " + apexPath, e2);
            return false;
        }
    }

    private boolean submitSessionToApexService(PackageInstallerSession session, List<PackageInstallerSession> childSessions, ApexInfoList apexInfoList) {
        int[] iArr;
        ApexInfo[] apexInfoArr;
        boolean z;
        ApexManager apexManager = this.mApexManager;
        int i = session.sessionId;
        boolean z2 = false;
        if (childSessions != null) {
            iArr = childSessions.stream().mapToInt(new ToIntFunction() { // from class: com.android.server.pm.-$$Lambda$StagingManager$ox-u05b9FQec8uLfg6h5LkmV4gk
                @Override // java.util.function.ToIntFunction
                public final int applyAsInt(Object obj) {
                    int i2;
                    i2 = ((PackageInstallerSession) obj).sessionId;
                    return i2;
                }
            }).toArray();
        } else {
            iArr = new int[0];
        }
        boolean submittedToApexd = apexManager.submitStagedSession(i, iArr, apexInfoList);
        int i2 = 1;
        if (!submittedToApexd) {
            session.setStagedSessionFailed(1, "APEX staging failed, check logcat messages from apexd for more details.");
            return false;
        }
        ApexInfo[] apexInfoArr2 = apexInfoList.apexInfos;
        int length = apexInfoArr2.length;
        int i3 = 0;
        while (i3 < length) {
            ApexInfo newPackage = apexInfoArr2[i3];
            PackageInfo activePackage = this.mApexManager.getPackageInfoForApexName(newPackage.packageName);
            if (activePackage == null) {
                z = z2;
                apexInfoArr = apexInfoArr2;
            } else {
                long activeVersion = activePackage.applicationInfo.longVersionCode;
                if (session.params.requiredInstalledVersionCode == -1) {
                    apexInfoArr = apexInfoArr2;
                } else {
                    apexInfoArr = apexInfoArr2;
                    if (activeVersion != session.params.requiredInstalledVersionCode) {
                        session.setStagedSessionFailed(i2, "Installed version of APEX package " + newPackage.packageName + " does not match required. Active version: " + activeVersion + " required: " + session.params.requiredInstalledVersionCode);
                        if (!this.mApexManager.abortActiveSession()) {
                            Slog.e(TAG, "Failed to abort apex session " + session.sessionId);
                            return false;
                        }
                        return false;
                    }
                }
                boolean allowsDowngrade = PackageManagerServiceUtils.isDowngradePermitted(session.params.installFlags, activePackage.applicationInfo.flags);
                if (activeVersion <= newPackage.versionCode || allowsDowngrade) {
                    z = false;
                } else {
                    session.setStagedSessionFailed(1, "Downgrade of APEX package " + newPackage.packageName + " is not allowed. Active version: " + activeVersion + " attempted: " + newPackage.versionCode);
                    if (!this.mApexManager.abortActiveSession()) {
                        Slog.e(TAG, "Failed to abort apex session " + session.sessionId);
                        return false;
                    }
                    return false;
                }
            }
            i3++;
            z2 = z;
            apexInfoArr2 = apexInfoArr;
            i2 = 1;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isApexSession(PackageInstallerSession session) {
        return (session.params.installFlags & 131072) != 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: preRebootVerification */
    public void lambda$resumeSession$7$StagingManager(PackageInstallerSession session) {
        ApexInfo[] apexInfoArr;
        boolean success = true;
        ApexInfoList apexInfoList = new ApexInfoList();
        if (!session.isMultiPackage() && isApexSession(session)) {
            success = submitSessionToApexService(session, null, apexInfoList);
        } else if (session.isMultiPackage()) {
            List<PackageInstallerSession> childSessions = (List) Arrays.stream(session.getChildSessionIds()).mapToObj(new IntFunction() { // from class: com.android.server.pm.-$$Lambda$StagingManager$BE6qQjRTVtd2eql5RkbIXPjyDYY
                @Override // java.util.function.IntFunction
                public final Object apply(int i) {
                    return StagingManager.this.lambda$preRebootVerification$1$StagingManager(i);
                }
            }).filter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$StagingManager$AgaT69AQKjTcEHdOPat7Y2rDy90
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean isApexSession;
                    isApexSession = StagingManager.isApexSession((PackageInstallerSession) obj);
                    return isApexSession;
                }
            }).collect(Collectors.toList());
            if (!childSessions.isEmpty()) {
                success = submitSessionToApexService(session, childSessions, apexInfoList);
            }
        }
        if (!success) {
            return;
        }
        if (sessionContainsApk(session) && !installApksInSession(session, true)) {
            session.setStagedSessionFailed(1, "APK verification failed. Check logcat messages for more information.");
            return;
        }
        if (apexInfoList.apexInfos != null && apexInfoList.apexInfos.length > 0) {
            for (ApexInfo apexPackage : apexInfoList.apexInfos) {
                if (!validateApexSignature(apexPackage.packagePath, apexPackage.packageName)) {
                    session.setStagedSessionFailed(1, "APK-container signature verification failed for package " + apexPackage.packageName + ". Signature of file " + apexPackage.packagePath + " does not match the signature of  the package already installed.");
                    return;
                }
            }
        }
        if ((session.params.installFlags & 262144) != 0) {
            IRollbackManager rm = IRollbackManager.Stub.asInterface(ServiceManager.getService("rollback"));
            try {
                if (!rm.notifyStagedSession(session.sessionId)) {
                    Slog.e(TAG, "Unable to enable rollback for session: " + session.sessionId);
                }
            } catch (RemoteException e) {
            }
        }
        session.setStagedSessionReady();
        if (sessionContainsApex(session) && !this.mApexManager.markStagedSessionReady(session.sessionId)) {
            session.setStagedSessionFailed(1, "APEX staging failed, check logcat messages from apexd for more details.");
        }
    }

    public /* synthetic */ PackageInstallerSession lambda$preRebootVerification$1$StagingManager(int i) {
        return this.mStagedSessions.get(i);
    }

    private boolean sessionContains(PackageInstallerSession session, final Predicate<PackageInstallerSession> filter) {
        boolean z;
        if (!session.isMultiPackage()) {
            return filter.test(session);
        }
        synchronized (this.mStagedSessions) {
            z = !((List) Arrays.stream(session.getChildSessionIds()).mapToObj(new IntFunction() { // from class: com.android.server.pm.-$$Lambda$StagingManager$zPvhMKF7o6jzlVNzE42Fq_qJt9I
                @Override // java.util.function.IntFunction
                public final Object apply(int i) {
                    return StagingManager.this.lambda$sessionContains$3$StagingManager(i);
                }
            }).filter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$StagingManager$lOH9gVOKGitWaFqixZa09s5PphU
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean test;
                    test = filter.test((PackageInstallerSession) obj);
                    return test;
                }
            }).collect(Collectors.toList())).isEmpty();
        }
        return z;
    }

    public /* synthetic */ PackageInstallerSession lambda$sessionContains$3$StagingManager(int i) {
        return this.mStagedSessions.get(i);
    }

    private boolean sessionContainsApex(PackageInstallerSession session) {
        return sessionContains(session, new Predicate() { // from class: com.android.server.pm.-$$Lambda$StagingManager$HJyijsQNJwcPQ10-2tU6415xlVo
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean isApexSession;
                isApexSession = StagingManager.isApexSession((PackageInstallerSession) obj);
                return isApexSession;
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$sessionContainsApk$6(PackageInstallerSession s) {
        return !isApexSession(s);
    }

    private boolean sessionContainsApk(PackageInstallerSession session) {
        return sessionContains(session, new Predicate() { // from class: com.android.server.pm.-$$Lambda$StagingManager$j1RpPmMrsxcldNpyt2n2wcJbVA0
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return StagingManager.lambda$sessionContainsApk$6((PackageInstallerSession) obj);
            }
        });
    }

    private void resumeSession(final PackageInstallerSession session) {
        boolean hasApex = sessionContainsApex(session);
        if (hasApex) {
            ApexSessionInfo apexSessionInfo = this.mApexManager.getStagedSessionInfo(session.sessionId);
            if (apexSessionInfo == null) {
                session.setStagedSessionFailed(2, "apexd did not know anything about a staged session supposed to beactivated");
                return;
            } else if (isApexSessionFailed(apexSessionInfo)) {
                session.setStagedSessionFailed(2, "APEX activation failed. Check logcat messages from apexd for more information.");
                return;
            } else if (apexSessionInfo.isVerified) {
                Slog.d(TAG, "Found pending staged session " + session.sessionId + " still to be verified, resuming pre-reboot verification");
                this.mBgHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$StagingManager$83GfAqr7qlzXNwZi6rOoUuwZb9c
                    @Override // java.lang.Runnable
                    public final void run() {
                        StagingManager.this.lambda$resumeSession$7$StagingManager(session);
                    }
                });
                return;
            } else if (!apexSessionInfo.isActivated && !apexSessionInfo.isSuccess) {
                Slog.w(TAG, "Staged session " + session.sessionId + " scheduled to be applied at boot didn't activate nor fail. This usually means that apexd will retry at next reboot.");
                return;
            }
        }
        if (!installApksInSession(session, false)) {
            session.setStagedSessionFailed(2, "Staged installation of APKs failed. Check logcat messages formore information.");
            if (!hasApex) {
                return;
            }
            if (!this.mApexManager.abortActiveSession()) {
                Slog.e(TAG, "Failed to abort APEXd session");
                return;
            }
            Slog.e(TAG, "Successfully aborted apexd session. Rebooting device in order to revert to the previous state of APEXd.");
            this.mPowerManager.reboot(null);
            return;
        }
        session.setStagedSessionApplied();
        if (hasApex) {
            this.mApexManager.markStagedSessionSuccessful(session.sessionId);
        }
    }

    private List<String> findAPKsInDir(File stageDir) {
        File[] listFiles;
        List<String> ret = new ArrayList<>();
        if (stageDir != null && stageDir.exists()) {
            for (File file : stageDir.listFiles()) {
                if (file.getAbsolutePath().toLowerCase().endsWith(".apk")) {
                    ret.add(file.getAbsolutePath());
                }
            }
        }
        return ret;
    }

    private PackageInstallerSession createAndWriteApkSession(PackageInstallerSession originalSession, boolean preReboot) {
        if (originalSession.stageDir != null) {
            List<String> apkFilePaths = findAPKsInDir(originalSession.stageDir);
            if (apkFilePaths.isEmpty()) {
                Slog.w(TAG, "Can't find staged APK in " + originalSession.stageDir.getAbsolutePath());
                return null;
            }
            PackageInstaller.SessionParams params = originalSession.params.copy();
            params.isStaged = false;
            params.installFlags |= DumpState.DUMP_COMPILER_STATS;
            if (preReboot) {
                params.installFlags &= -262145;
                params.installFlags |= DumpState.DUMP_VOLUMES;
            } else {
                params.installFlags |= DumpState.DUMP_FROZEN;
            }
            try {
                int apkSessionId = this.mPi.createSession(params, originalSession.getInstallerPackageName(), 0);
                PackageInstallerSession apkSession = this.mPi.getSession(apkSessionId);
                apkSession.open();
                for (String apkFilePath : apkFilePaths) {
                    File apkFile = new File(apkFilePath);
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(apkFile, 268435456);
                    long sizeBytes = pfd == null ? -1L : pfd.getStatSize();
                    if (sizeBytes < 0) {
                        Slog.e(TAG, "Unable to get size of: " + apkFilePath);
                        return null;
                    }
                    apkSession.write(apkFile.getName(), 0L, sizeBytes, pfd);
                }
                return apkSession;
            } catch (IOException | ParcelableException e) {
                Slog.e(TAG, "Failure to install APK staged session " + originalSession.sessionId, e);
                return null;
            }
        }
        Slog.wtf(TAG, "Attempting to install a staged APK session with no staging dir");
        return null;
    }

    private boolean commitApkSession(PackageInstallerSession apkSession, int originalSessionId, boolean preReboot) {
        if (!preReboot && (apkSession.params.installFlags & 262144) != 0) {
            IRollbackManager rm = IRollbackManager.Stub.asInterface(ServiceManager.getService("rollback"));
            try {
                rm.notifyStagedApkSession(originalSessionId, apkSession.sessionId);
            } catch (RemoteException e) {
            }
        }
        LocalIntentReceiver receiver = new LocalIntentReceiver();
        apkSession.commit(receiver.getIntentSender(), false);
        Intent result = receiver.getResult();
        int status = result.getIntExtra("android.content.pm.extra.STATUS", 1);
        if (status == 0) {
            return true;
        }
        Slog.e(TAG, "Failure to install APK staged session " + originalSessionId + " [" + result.getStringExtra("android.content.pm.extra.STATUS_MESSAGE") + "]");
        return false;
    }

    private boolean installApksInSession(PackageInstallerSession session, boolean preReboot) {
        List<PackageInstallerSession> childSessions;
        if (!session.isMultiPackage() && !isApexSession(session)) {
            PackageInstallerSession apkSession = createAndWriteApkSession(session, preReboot);
            if (apkSession == null) {
                return false;
            }
            return commitApkSession(apkSession, session.sessionId, preReboot);
        } else if (session.isMultiPackage()) {
            synchronized (this.mStagedSessions) {
                childSessions = (List) Arrays.stream(session.getChildSessionIds()).mapToObj(new IntFunction() { // from class: com.android.server.pm.-$$Lambda$StagingManager$SBQcrV7wm5jyjLxIITacOTLs_k4
                    @Override // java.util.function.IntFunction
                    public final Object apply(int i) {
                        return StagingManager.this.lambda$installApksInSession$8$StagingManager(i);
                    }
                }).filter(new Predicate() { // from class: com.android.server.pm.-$$Lambda$StagingManager$W4xn2etqxcpB6KS2WmEUcUMWK4M
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return StagingManager.lambda$installApksInSession$9((PackageInstallerSession) obj);
                    }
                }).collect(Collectors.toList());
            }
            if (childSessions.isEmpty()) {
                return true;
            }
            PackageInstaller.SessionParams params = session.params.copy();
            params.isStaged = false;
            if (preReboot) {
                params.installFlags &= -262145;
            }
            int apkParentSessionId = this.mPi.createSession(params, session.getInstallerPackageName(), 0);
            PackageInstallerSession apkParentSession = this.mPi.getSession(apkParentSessionId);
            try {
                apkParentSession.open();
                for (PackageInstallerSession sessionToClone : childSessions) {
                    PackageInstallerSession apkChildSession = createAndWriteApkSession(sessionToClone, preReboot);
                    if (apkChildSession == null) {
                        return false;
                    }
                    try {
                        apkParentSession.addChildSessionId(apkChildSession.sessionId);
                    } catch (IllegalStateException e) {
                        Slog.e(TAG, "Failed to add a child session for installing the APK files", e);
                        return false;
                    }
                }
                return commitApkSession(apkParentSession, session.sessionId, preReboot);
            } catch (IOException e2) {
                Slog.e(TAG, "Unable to prepare multi-package session for staged session " + session.sessionId);
                return false;
            }
        } else {
            return true;
        }
    }

    public /* synthetic */ PackageInstallerSession lambda$installApksInSession$8$StagingManager(int i) {
        return this.mStagedSessions.get(i);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$installApksInSession$9(PackageInstallerSession childSession) {
        return !isApexSession(childSession);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void commitSession(final PackageInstallerSession session) {
        updateStoredSession(session);
        this.mBgHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$StagingManager$oTjNN2Q2v9Dr5k3q884ZdgcuSqA
            @Override // java.lang.Runnable
            public final void run() {
                StagingManager.this.lambda$commitSession$10$StagingManager(session);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageInstallerSession getActiveSession() {
        synchronized (this.mStagedSessions) {
            for (int i = 0; i < this.mStagedSessions.size(); i++) {
                PackageInstallerSession session = this.mStagedSessions.valueAt(i);
                if (session.isCommitted() && !session.hasParentSessionId() && !session.isStagedSessionApplied() && !session.isStagedSessionFailed()) {
                    return session;
                }
            }
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createSession(PackageInstallerSession sessionInfo) {
        synchronized (this.mStagedSessions) {
            this.mStagedSessions.append(sessionInfo.sessionId, sessionInfo);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void abortSession(PackageInstallerSession session) {
        synchronized (this.mStagedSessions) {
            this.mStagedSessions.remove(session.sessionId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void abortCommittedSession(PackageInstallerSession session) {
        if (session.isStagedSessionApplied()) {
            Slog.w(TAG, "Cannot abort applied session : " + session.sessionId);
            return;
        }
        abortSession(session);
        boolean hasApex = sessionContainsApex(session);
        if (hasApex) {
            ApexSessionInfo apexSession = this.mApexManager.getStagedSessionInfo(session.sessionId);
            if (apexSession == null || isApexSessionFinalized(apexSession)) {
                Slog.w(TAG, "Cannot abort session because it is not active or APEXD is not reachable");
            } else {
                this.mApexManager.abortActiveSession();
            }
        }
    }

    private boolean isApexSessionFinalized(ApexSessionInfo session) {
        return session.isUnknown || session.isActivationFailed || session.isSuccess || session.isRolledBack;
    }

    private static boolean isApexSessionFailed(ApexSessionInfo apexSessionInfo) {
        return apexSessionInfo.isActivationFailed || apexSessionInfo.isUnknown || apexSessionInfo.isRolledBack || apexSessionInfo.isRollbackInProgress || apexSessionInfo.isRollbackFailed;
    }

    @GuardedBy({"mStagedSessions"})
    private boolean isMultiPackageSessionComplete(PackageInstallerSession session) {
        int[] childSessionIds;
        if (session.isMultiPackage()) {
            for (int childSession : session.getChildSessionIds()) {
                if (this.mStagedSessions.get(childSession) == null) {
                    return false;
                }
            }
            return true;
        } else if (session.hasParentSessionId()) {
            PackageInstallerSession parent = this.mStagedSessions.get(session.getParentSessionId());
            if (parent == null) {
                return false;
            }
            return isMultiPackageSessionComplete(parent);
        } else {
            Slog.wtf(TAG, "Attempting to restore an invalid multi-package session.");
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void restoreSession(PackageInstallerSession session, boolean isDeviceUpgrading) {
        PackageInstallerSession sessionToResume = session;
        synchronized (this.mStagedSessions) {
            this.mStagedSessions.append(session.sessionId, session);
            if (session.isMultiPackage() || session.hasParentSessionId()) {
                if (!isMultiPackageSessionComplete(session)) {
                    return;
                }
                if (session.hasParentSessionId()) {
                    sessionToResume = this.mStagedSessions.get(session.getParentSessionId());
                }
            }
            if (isDeviceUpgrading && !sessionToResume.isStagedAndInTerminalState()) {
                sessionToResume.setStagedSessionFailed(2, "Build fingerprint has changed");
            } else {
                checkStateAndResume(sessionToResume);
            }
        }
    }

    private void checkStateAndResume(final PackageInstallerSession session) {
        if (!session.isCommitted() || session.isStagedSessionFailed() || session.isStagedSessionApplied()) {
            return;
        }
        if (!session.isStagedSessionReady()) {
            this.mBgHandler.post(new Runnable() { // from class: com.android.server.pm.-$$Lambda$StagingManager$P2Wce7WbRVyHPDejgMPiovUuc0M
                @Override // java.lang.Runnable
                public final void run() {
                    StagingManager.this.lambda$checkStateAndResume$11$StagingManager(session);
                }
            });
        } else {
            resumeSession(session);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class LocalIntentReceiver {
        private IIntentSender.Stub mLocalSender;
        private final LinkedBlockingQueue<Intent> mResult;

        private LocalIntentReceiver() {
            this.mResult = new LinkedBlockingQueue<>();
            this.mLocalSender = new IIntentSender.Stub() { // from class: com.android.server.pm.StagingManager.LocalIntentReceiver.1
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
