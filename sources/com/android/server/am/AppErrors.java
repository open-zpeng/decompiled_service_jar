package com.android.server.am;

import android.app.ActivityManager;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.app.ProcessMap;
import com.android.server.Watchdog;
import com.android.server.am.AppErrorDialog;
import com.xiaopeng.server.aftersales.AfterSalesService;
import java.util.Collections;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AppErrors {
    private static final String TAG = "ActivityManager";
    private ArraySet<String> mAppsNotReportingCrashes;
    private final Context mContext;
    private final ActivityManagerService mService;
    private final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<>();
    private final ProcessMap<Long> mProcessCrashTimesPersistent = new ProcessMap<>();
    private final ProcessMap<BadProcessInfo> mBadProcesses = new ProcessMap<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppErrors(Context context, ActivityManagerService service) {
        context.assertRuntimeOverlayThemable();
        this.mService = service;
        this.mContext = context;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId, String dumpPackage) {
        ArrayMap<String, SparseArray<BadProcessInfo>> pmap;
        String pname;
        SparseArray<BadProcessInfo> uids;
        int uidCount;
        ArrayMap<String, SparseArray<Long>> pmap2;
        int procCount;
        String pname2;
        long token;
        String str = dumpPackage;
        if (this.mProcessCrashTimes.getMap().isEmpty() && this.mBadProcesses.getMap().isEmpty()) {
            return;
        }
        long token2 = proto.start(fieldId);
        long now = SystemClock.uptimeMillis();
        proto.write(1112396529665L, now);
        long j = 1138166333441L;
        long j2 = 2246267895810L;
        if (!this.mProcessCrashTimes.getMap().isEmpty()) {
            ArrayMap<String, SparseArray<Long>> pmap3 = this.mProcessCrashTimes.getMap();
            int procCount2 = pmap3.size();
            int ip = 0;
            while (ip < procCount2) {
                long ctoken = proto.start(j2);
                String pname3 = pmap3.keyAt(ip);
                SparseArray<Long> uids2 = pmap3.valueAt(ip);
                long now2 = now;
                int uidCount2 = uids2.size();
                proto.write(j, pname3);
                int i = 0;
                while (i < uidCount2) {
                    int puid = uids2.keyAt(i);
                    ProcessRecord r = (ProcessRecord) this.mService.mProcessNames.get(pname3, puid);
                    if (str != null) {
                        if (r != null) {
                            uidCount = uidCount2;
                            if (!r.pkgList.containsKey(str)) {
                                token = token2;
                                pmap2 = pmap3;
                                procCount = procCount2;
                                pname2 = pname3;
                            }
                        } else {
                            uidCount = uidCount2;
                            token = token2;
                            pmap2 = pmap3;
                            procCount = procCount2;
                            pname2 = pname3;
                        }
                        i++;
                        uidCount2 = uidCount;
                        pmap3 = pmap2;
                        procCount2 = procCount;
                        pname3 = pname2;
                        token2 = token;
                    } else {
                        uidCount = uidCount2;
                    }
                    pmap2 = pmap3;
                    procCount = procCount2;
                    pname2 = pname3;
                    long etoken = proto.start(2246267895810L);
                    proto.write(1120986464257L, puid);
                    token = token2;
                    proto.write(1112396529666L, uids2.valueAt(i).longValue());
                    proto.end(etoken);
                    i++;
                    uidCount2 = uidCount;
                    pmap3 = pmap2;
                    procCount2 = procCount;
                    pname3 = pname2;
                    token2 = token;
                }
                proto.end(ctoken);
                ip++;
                now = now2;
                j = 1138166333441L;
                j2 = 2246267895810L;
            }
        }
        long token3 = token2;
        if (!this.mBadProcesses.getMap().isEmpty()) {
            ArrayMap<String, SparseArray<BadProcessInfo>> pmap4 = this.mBadProcesses.getMap();
            int processCount = pmap4.size();
            int ip2 = 0;
            while (ip2 < processCount) {
                long btoken = proto.start(2246267895811L);
                String pname4 = pmap4.keyAt(ip2);
                SparseArray<BadProcessInfo> uids3 = pmap4.valueAt(ip2);
                int uidCount3 = uids3.size();
                proto.write(1138166333441L, pname4);
                int i2 = 0;
                while (i2 < uidCount3) {
                    int puid2 = uids3.keyAt(i2);
                    ProcessRecord r2 = (ProcessRecord) this.mService.mProcessNames.get(pname4, puid2);
                    if (str != null && (r2 == null || !r2.pkgList.containsKey(str))) {
                        pmap = pmap4;
                        pname = pname4;
                        uids = uids3;
                    } else {
                        BadProcessInfo info = uids3.valueAt(i2);
                        pmap = pmap4;
                        pname = pname4;
                        uids = uids3;
                        long etoken2 = proto.start(2246267895810L);
                        proto.write(1120986464257L, puid2);
                        proto.write(1112396529666L, info.time);
                        proto.write(1138166333443L, info.shortMsg);
                        proto.write(1138166333444L, info.longMsg);
                        proto.write(1138166333445L, info.stack);
                        proto.end(etoken2);
                    }
                    i2++;
                    pmap4 = pmap;
                    pname4 = pname;
                    uids3 = uids;
                    str = dumpPackage;
                }
                proto.end(btoken);
                ip2++;
                str = dumpPackage;
            }
        }
        proto.end(token3);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:12:0x0055, code lost:
        if (r4.pkgList.containsKey(r27) == false) goto L19;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean dumpLocked(java.io.FileDescriptor r24, java.io.PrintWriter r25, boolean r26, java.lang.String r27) {
        /*
            Method dump skipped, instructions count: 432
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppErrors.dumpLocked(java.io.FileDescriptor, java.io.PrintWriter, boolean, java.lang.String):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBadProcessLocked(ApplicationInfo info) {
        return this.mBadProcesses.get(info.processName, info.uid) != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearBadProcessLocked(ApplicationInfo info) {
        this.mBadProcesses.remove(info.processName, info.uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetProcessCrashTimeLocked(ApplicationInfo info) {
        this.mProcessCrashTimes.remove(info.processName, info.uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetProcessCrashTimeLocked(boolean resetEntireUser, int appId, int userId) {
        ArrayMap<String, SparseArray<Long>> pmap = this.mProcessCrashTimes.getMap();
        for (int ip = pmap.size() - 1; ip >= 0; ip--) {
            SparseArray<Long> ba = pmap.valueAt(ip);
            for (int i = ba.size() - 1; i >= 0; i--) {
                boolean remove = false;
                int entUid = ba.keyAt(i);
                if (!resetEntireUser) {
                    if (userId == -1) {
                        if (UserHandle.getAppId(entUid) == appId) {
                            remove = true;
                        }
                    } else if (entUid == UserHandle.getUid(userId, appId)) {
                        remove = true;
                    }
                } else if (UserHandle.getUserId(entUid) == userId) {
                    remove = true;
                }
                if (remove) {
                    ba.removeAt(i);
                }
            }
            int i2 = ba.size();
            if (i2 == 0) {
                pmap.removeAt(ip);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void loadAppsNotReportingCrashesFromConfigLocked(String appsNotReportingCrashesConfig) {
        if (appsNotReportingCrashesConfig != null) {
            String[] split = appsNotReportingCrashesConfig.split(",");
            if (split.length > 0) {
                this.mAppsNotReportingCrashes = new ArraySet<>();
                Collections.addAll(this.mAppsNotReportingCrashes, split);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void killAppAtUserRequestLocked(ProcessRecord app, Dialog fromDialog) {
        app.crashing = false;
        app.crashingReport = null;
        app.notResponding = false;
        app.notRespondingReport = null;
        if (app.anrDialog == fromDialog) {
            app.anrDialog = null;
        }
        if (app.waitDialog == fromDialog) {
            app.waitDialog = null;
        }
        if (app.pid > 0 && app.pid != ActivityManagerService.MY_PID) {
            handleAppCrashLocked(app, "user-terminated", null, null, null, null);
            app.kill("user request after error", true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleAppCrashLocked(int uid, int initialPid, String packageName, int userId, String message) {
        ProcessRecord proc = null;
        synchronized (this.mService.mPidsSelfLocked) {
            int i = 0;
            while (true) {
                if (i >= this.mService.mPidsSelfLocked.size()) {
                    break;
                }
                ProcessRecord p = this.mService.mPidsSelfLocked.valueAt(i);
                if (uid < 0 || p.uid == uid) {
                    if (p.pid == initialPid) {
                        proc = p;
                        break;
                    } else if (p.pkgList.containsKey(packageName) && (userId < 0 || p.userId == userId)) {
                        proc = p;
                    }
                }
                i++;
            }
        }
        if (proc == null) {
            Slog.w(TAG, "crashApplication: nothing for uid=" + uid + " initialPid=" + initialPid + " packageName=" + packageName + " userId=" + userId);
            return;
        }
        proc.scheduleCrash(message);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void crashApplication(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo, long time) {
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        long origId = Binder.clearCallingIdentity();
        try {
            crashApplicationInner(r, crashInfo, callingPid, callingUid, time);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:105:0x01b9 A[Catch: all -> 0x01b0, TRY_LEAVE, TryCatch #6 {all -> 0x01b0, blocks: (B:92:0x0188, B:97:0x01a7, B:105:0x01b9, B:86:0x0159, B:94:0x018d, B:96:0x0198), top: B:153:0x0159 }] */
    /* JADX WARN: Removed duplicated region for block: B:108:0x01e4  */
    /* JADX WARN: Removed duplicated region for block: B:111:0x01e9 A[Catch: all -> 0x0230, TryCatch #5 {all -> 0x0230, blocks: (B:107:0x01c1, B:111:0x01e9, B:113:0x01f0, B:117:0x01f7, B:118:0x020a, B:128:0x022b), top: B:152:0x00f3 }] */
    /* JADX WARN: Removed duplicated region for block: B:148:0x0210 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:165:? A[RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:92:0x0188 A[Catch: all -> 0x01b0, TRY_LEAVE, TryCatch #6 {all -> 0x01b0, blocks: (B:92:0x0188, B:97:0x01a7, B:105:0x01b9, B:86:0x0159, B:94:0x018d, B:96:0x0198), top: B:153:0x0159 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    void crashApplicationInner(com.android.server.am.ProcessRecord r42, android.app.ApplicationErrorReport.CrashInfo r43, int r44, int r45, long r46) {
        /*
            Method dump skipped, instructions count: 588
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppErrors.crashApplicationInner(com.android.server.am.ProcessRecord, android.app.ApplicationErrorReport$CrashInfo, int, int, long):void");
    }

    private boolean handleAppCrashInActivityController(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo, String shortMsg, String longMsg, String stackTrace, long timeMillis, int callingPid, int callingUid) {
        String name;
        if (this.mService.mController == null) {
            return false;
        }
        if (r != null) {
            try {
                name = r.processName;
            } catch (RemoteException e) {
                this.mService.mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        } else {
            name = null;
        }
        int pid = r != null ? r.pid : callingPid;
        int uid = r != null ? r.info.uid : callingUid;
        if (!this.mService.mController.appCrashed(name, pid, shortMsg, longMsg, timeMillis, crashInfo.stackTrace)) {
            if ("1".equals(SystemProperties.get("ro.debuggable", "0")) && "Native crash".equals(crashInfo.exceptionClassName)) {
                Slog.w(TAG, "Skip killing native crashed app " + name + "(" + pid + ") during testing");
            } else {
                Slog.w(TAG, "Force-killing crashed app " + name + " at watcher's request");
                if (r != null) {
                    if (!makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace, null)) {
                        r.kill("crash", true);
                    }
                } else {
                    Process.killProcess(pid);
                    ActivityManagerService.killProcessGroup(uid, pid);
                }
            }
            return true;
        }
        return false;
    }

    private boolean makeAppCrashingLocked(ProcessRecord app, String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        app.crashing = true;
        app.crashingReport = generateProcessError(app, 1, null, shortMsg, longMsg, stackTrace);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
        return handleAppCrashLocked(app, "force-crash", shortMsg, longMsg, stackTrace, data);
    }

    void startAppProblemLocked(ProcessRecord app) {
        int[] currentProfileIds;
        app.errorReportReceiver = null;
        for (int userId : this.mService.mUserController.getCurrentProfileIds()) {
            if (app.userId == userId) {
                app.errorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(this.mContext, app.info.packageName, app.info.flags);
            }
        }
        this.mService.skipCurrentReceiverLocked(app);
    }

    private ActivityManager.ProcessErrorStateInfo generateProcessError(ProcessRecord app, int condition, String activity, String shortMsg, String longMsg, String stackTrace) {
        ActivityManager.ProcessErrorStateInfo report = new ActivityManager.ProcessErrorStateInfo();
        report.condition = condition;
        report.processName = app.processName;
        report.pid = app.pid;
        report.uid = app.info.uid;
        report.tag = activity;
        report.shortMsg = shortMsg;
        report.longMsg = longMsg;
        report.stackTrace = stackTrace;
        return report;
    }

    Intent createAppErrorServiceLocked(ProcessRecord r, long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = createErrorServiceLocked(r, timeMillis, crashInfo);
        if (report == null) {
            return null;
        }
        Intent result = new Intent("android.intent.action.APP_ERROR");
        result.setPackage("com.xiaopeng.bughunter");
        result.putExtra("android.intent.extra.BUG_REPORT", report);
        return result;
    }

    private ApplicationErrorReport createErrorServiceLocked(ProcessRecord r, long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        if (!r.crashing && !r.notResponding && !r.forceCrashReport) {
            return null;
        }
        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & 1) != 0;
        if (r.crashing || r.forceCrashReport) {
            report.type = 1;
            report.crashInfo = crashInfo;
        } else if (r.notResponding) {
            report.type = 2;
            report.anrInfo = new ApplicationErrorReport.AnrInfo();
            report.anrInfo.activity = r.notRespondingReport.tag;
            report.anrInfo.cause = r.notRespondingReport.shortMsg;
            report.anrInfo.info = r.notRespondingReport.longMsg;
        }
        return report;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Intent createAppErrorIntentLocked(ProcessRecord r, long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = createAppErrorReportLocked(r, timeMillis, crashInfo);
        if (report == null) {
            return null;
        }
        Intent result = new Intent("android.intent.action.APP_ERROR");
        result.setComponent(r.errorReportReceiver);
        result.putExtra("android.intent.extra.BUG_REPORT", report);
        result.addFlags(268435456);
        return result;
    }

    private ApplicationErrorReport createAppErrorReportLocked(ProcessRecord r, long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        if (r.errorReportReceiver == null) {
            return null;
        }
        if (r.crashing || r.notResponding || r.forceCrashReport) {
            ApplicationErrorReport report = new ApplicationErrorReport();
            report.packageName = r.info.packageName;
            report.installerPackageName = r.errorReportReceiver.getPackageName();
            report.processName = r.processName;
            report.time = timeMillis;
            report.systemApp = (r.info.flags & 1) != 0;
            if (r.crashing || r.forceCrashReport) {
                report.type = 1;
                report.crashInfo = crashInfo;
            } else if (r.notResponding) {
                report.type = 2;
                report.anrInfo = new ApplicationErrorReport.AnrInfo();
                report.anrInfo.activity = r.notRespondingReport.tag;
                report.anrInfo.cause = r.notRespondingReport.shortMsg;
                report.anrInfo.info = r.notRespondingReport.longMsg;
            }
            return report;
        }
        return null;
    }

    /* JADX WARN: Removed duplicated region for block: B:64:0x01b7  */
    /* JADX WARN: Removed duplicated region for block: B:73:0x01ee  */
    /* JADX WARN: Removed duplicated region for block: B:74:0x020f  */
    /* JADX WARN: Removed duplicated region for block: B:77:0x0215  */
    /* JADX WARN: Removed duplicated region for block: B:86:0x01ea A[EDGE_INSN: B:86:0x01ea->B:71:0x01ea ?: BREAK  , SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:90:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    boolean handleAppCrashLocked(com.android.server.am.ProcessRecord r21, java.lang.String r22, java.lang.String r23, java.lang.String r24, java.lang.String r25, com.android.server.am.AppErrorDialog.Data r26) {
        /*
            Method dump skipped, instructions count: 544
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppErrors.handleAppCrashLocked(com.android.server.am.ProcessRecord, java.lang.String, java.lang.String, java.lang.String, java.lang.String, com.android.server.am.AppErrorDialog$Data):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleShowAppErrorUi(Message msg) {
        AppErrorDialog.Data data = (AppErrorDialog.Data) msg.obj;
        boolean showBackground = Settings.Secure.getInt(this.mContext.getContentResolver(), "anr_show_background", 0) != 0;
        AppErrorDialog dialogToShow = null;
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                ProcessRecord proc = data.proc;
                AppErrorResult res = data.result;
                if (proc == null) {
                    Slog.e(TAG, "handleShowAppErrorUi: proc is null");
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                String packageName = proc.info.packageName;
                int userId = proc.userId;
                if (proc.crashDialog != null) {
                    Slog.e(TAG, "App already has crash dialog: " + proc);
                    if (res != null) {
                        res.set(AppErrorDialog.ALREADY_SHOWING);
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                boolean isBackground = UserHandle.getAppId(proc.uid) >= 10000 && proc.pid != ActivityManagerService.MY_PID;
                int[] currentProfileIds = this.mService.mUserController.getCurrentProfileIds();
                int length = currentProfileIds.length;
                boolean isBackground2 = isBackground;
                for (int i = 0; i < length; i++) {
                    int profileId = currentProfileIds[i];
                    isBackground2 &= userId != profileId;
                }
                if (isBackground2 && !showBackground) {
                    Slog.w(TAG, "Skipping crash dialog of " + proc + ": background");
                    if (res != null) {
                        res.set(AppErrorDialog.BACKGROUND_USER);
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                boolean showFirstCrash = Settings.Global.getInt(this.mContext.getContentResolver(), "show_first_crash_dialog", 0) != 0;
                boolean showFirstCrashDevOption = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "show_first_crash_dialog_dev_option", 0, this.mService.mUserController.getCurrentUserId()) != 0;
                boolean crashSilenced = this.mAppsNotReportingCrashes != null && this.mAppsNotReportingCrashes.contains(proc.info.packageName);
                if ((this.mService.canShowErrorDialogs() || showBackground) && !crashSilenced && (showFirstCrash || showFirstCrashDevOption || data.repeating)) {
                    AppErrorDialog appErrorDialog = new AppErrorDialog(this.mContext, this.mService, data);
                    dialogToShow = appErrorDialog;
                    proc.crashDialog = appErrorDialog;
                } else if (res != null) {
                    res.set(AppErrorDialog.CANT_SHOW);
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
                if (dialogToShow != null) {
                    Slog.i(TAG, "Showing crash dialog for package " + packageName + " u" + userId);
                    dialogToShow.show();
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    void stopReportingCrashesLocked(ProcessRecord proc) {
        if (this.mAppsNotReportingCrashes == null) {
            this.mAppsNotReportingCrashes = new ArraySet<>();
        }
        this.mAppsNotReportingCrashes.add(proc.info.packageName);
    }

    static boolean isInterestingForBackgroundTraces(ProcessRecord app) {
        if (app.pid == ActivityManagerService.MY_PID || app.isInterestingToUserLocked()) {
            return true;
        }
        return (app.info != null && AfterSalesService.PackgeName.SYSTEMUI.equals(app.info.packageName)) || app.hasTopUi || app.hasOverlayUi;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:269:0x0418 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final void appNotResponding(com.android.server.am.ProcessRecord r39, com.android.server.am.ActivityRecord r40, com.android.server.am.ActivityRecord r41, boolean r42, java.lang.String r43) {
        /*
            Method dump skipped, instructions count: 1245
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppErrors.appNotResponding(com.android.server.am.ProcessRecord, com.android.server.am.ActivityRecord, com.android.server.am.ActivityRecord, boolean, java.lang.String):void");
    }

    private void makeAppNotRespondingLocked(ProcessRecord app, String activity, String shortMsg, String longMsg) {
        app.notResponding = true;
        app.notRespondingReport = generateProcessError(app, 2, activity, shortMsg, longMsg, null);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:33:0x00b3  */
    /* JADX WARN: Removed duplicated region for block: B:44:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void handleShowAnrUi(android.os.Message r27) {
        /*
            r26 = this;
            r1 = r26
            r2 = 0
            com.android.server.am.ActivityManagerService r3 = r1.mService
            monitor-enter(r3)
            com.android.server.am.ActivityManagerService.boostPriorityForLockedSection()     // Catch: java.lang.Throwable -> Lb9
            r4 = r27
            java.lang.Object r0 = r4.obj     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.AppNotRespondingDialog$Data r0 = (com.android.server.am.AppNotRespondingDialog.Data) r0     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ProcessRecord r5 = r0.proc     // Catch: java.lang.Throwable -> Lb7
            if (r5 != 0) goto L1f
            java.lang.String r6 = "ActivityManager"
            java.lang.String r7 = "handleShowAnrUi: proc is null"
            android.util.Slog.e(r6, r7)     // Catch: java.lang.Throwable -> Lb7
            monitor-exit(r3)     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            return
        L1f:
            android.app.Dialog r6 = r5.anrDialog     // Catch: java.lang.Throwable -> Lb7
            r7 = 317(0x13d, float:4.44E-43)
            if (r6 == 0) goto L46
            java.lang.String r6 = "ActivityManager"
            java.lang.StringBuilder r8 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> Lb7
            r8.<init>()     // Catch: java.lang.Throwable -> Lb7
            java.lang.String r9 = "App already has anr dialog: "
            r8.append(r9)     // Catch: java.lang.Throwable -> Lb7
            r8.append(r5)     // Catch: java.lang.Throwable -> Lb7
            java.lang.String r8 = r8.toString()     // Catch: java.lang.Throwable -> Lb7
            android.util.Slog.e(r6, r8)     // Catch: java.lang.Throwable -> Lb7
            android.content.Context r6 = r1.mContext     // Catch: java.lang.Throwable -> Lb7
            r8 = -2
            com.android.internal.logging.MetricsLogger.action(r6, r7, r8)     // Catch: java.lang.Throwable -> Lb7
            monitor-exit(r3)     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            return
        L46:
            android.content.Intent r6 = new android.content.Intent     // Catch: java.lang.Throwable -> Lb7
            java.lang.String r8 = "android.intent.action.ANR"
            r6.<init>(r8)     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ActivityManagerService r8 = r1.mService     // Catch: java.lang.Throwable -> Lb7
            boolean r8 = r8.mProcessesReady     // Catch: java.lang.Throwable -> Lb7
            if (r8 != 0) goto L58
            r8 = 1342177280(0x50000000, float:8.589935E9)
            r6.addFlags(r8)     // Catch: java.lang.Throwable -> Lb7
        L58:
            com.android.server.am.ActivityManagerService r9 = r1.mService     // Catch: java.lang.Throwable -> Lb7
            r10 = 0
            r11 = 0
            r13 = 0
            r14 = 0
            r15 = 0
            r16 = 0
            r17 = 0
            r18 = 0
            r19 = -1
            r20 = 0
            r21 = 0
            r22 = 0
            int r23 = com.android.server.am.ActivityManagerService.MY_PID     // Catch: java.lang.Throwable -> Lb7
            r24 = 1000(0x3e8, float:1.401E-42)
            r25 = 0
            r12 = r6
            r9.broadcastIntentLocked(r10, r11, r12, r13, r14, r15, r16, r17, r18, r19, r20, r21, r22, r23, r24, r25)     // Catch: java.lang.Throwable -> Lb7
            android.content.Context r8 = r1.mContext     // Catch: java.lang.Throwable -> Lb7
            android.content.ContentResolver r8 = r8.getContentResolver()     // Catch: java.lang.Throwable -> Lb7
            java.lang.String r9 = "anr_show_background"
            r10 = 0
            int r8 = android.provider.Settings.Secure.getInt(r8, r9, r10)     // Catch: java.lang.Throwable -> Lb7
            if (r8 == 0) goto L88
            r10 = 1
        L88:
            r8 = r10
            com.android.server.am.ActivityManagerService r9 = r1.mService     // Catch: java.lang.Throwable -> Lb7
            boolean r9 = r9.canShowErrorDialogs()     // Catch: java.lang.Throwable -> Lb7
            if (r9 != 0) goto La1
            if (r8 == 0) goto L94
            goto La1
        L94:
            android.content.Context r9 = r1.mContext     // Catch: java.lang.Throwable -> Lb7
            r10 = -1
            com.android.internal.logging.MetricsLogger.action(r9, r7, r10)     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ActivityManagerService r7 = r1.mService     // Catch: java.lang.Throwable -> Lb7
            r9 = 0
            r7.killAppAtUsersRequest(r5, r9)     // Catch: java.lang.Throwable -> Lb7
            goto Lad
        La1:
            com.android.server.am.AppNotRespondingDialog r7 = new com.android.server.am.AppNotRespondingDialog     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ActivityManagerService r9 = r1.mService     // Catch: java.lang.Throwable -> Lb7
            android.content.Context r10 = r1.mContext     // Catch: java.lang.Throwable -> Lb7
            r7.<init>(r9, r10, r0)     // Catch: java.lang.Throwable -> Lb7
            r2 = r7
            r5.anrDialog = r2     // Catch: java.lang.Throwable -> Lb7
        Lad:
            monitor-exit(r3)     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            if (r2 == 0) goto Lb6
            r2.show()
        Lb6:
            return
        Lb7:
            r0 = move-exception
            goto Lbc
        Lb9:
            r0 = move-exception
            r4 = r27
        Lbc:
            monitor-exit(r3)     // Catch: java.lang.Throwable -> Lb7
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppErrors.handleShowAnrUi(android.os.Message):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class BadProcessInfo {
        final String longMsg;
        final String shortMsg;
        final String stack;
        final long time;

        BadProcessInfo(long time, String shortMsg, String longMsg, String stack) {
            this.time = time;
            this.shortMsg = shortMsg;
            this.longMsg = longMsg;
            this.stack = stack;
        }
    }
}
