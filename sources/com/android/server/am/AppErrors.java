package com.android.server.am;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.internal.app.ProcessMap;
import com.android.internal.logging.MetricsLogger;
import com.android.server.PackageWatchdog;
import com.android.server.RescueParty;
import com.android.server.am.AppErrorDialog;
import com.android.server.wm.WindowProcessController;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AppErrors {
    private static final String TAG = "ActivityManager";
    private ArraySet<String> mAppsNotReportingCrashes;
    private final Context mContext;
    private final PackageWatchdog mPackageWatchdog;
    private final ActivityManagerService mService;
    private final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<>();
    private final ProcessMap<Long> mProcessCrashTimesPersistent = new ProcessMap<>();
    private final ProcessMap<BadProcessInfo> mBadProcesses = new ProcessMap<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppErrors(Context context, ActivityManagerService service, PackageWatchdog watchdog) {
        context.assertRuntimeOverlayThemable();
        this.mService = service;
        this.mContext = context;
        this.mPackageWatchdog = watchdog;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId, String dumpPackage) {
        long token;
        ArrayMap<String, SparseArray<BadProcessInfo>> pmap;
        String pname;
        SparseArray<BadProcessInfo> uids;
        int uidCount;
        ArrayMap<String, SparseArray<Long>> pmap2;
        int procCount;
        String pname2;
        long token2;
        String str = dumpPackage;
        if (this.mProcessCrashTimes.getMap().isEmpty() && this.mBadProcesses.getMap().isEmpty()) {
            return;
        }
        long token3 = proto.start(fieldId);
        long now = SystemClock.uptimeMillis();
        proto.write(1112396529665L, now);
        long j = 1138166333441L;
        long j2 = 2246267895810L;
        if (this.mProcessCrashTimes.getMap().isEmpty()) {
            token = token3;
        } else {
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
                    ProcessRecord r = (ProcessRecord) this.mService.getProcessNames().get(pname3, puid);
                    if (str != null) {
                        if (r != null) {
                            uidCount = uidCount2;
                            if (!r.pkgList.containsKey(str)) {
                                token2 = token3;
                                pmap2 = pmap3;
                                procCount = procCount2;
                                pname2 = pname3;
                            }
                        } else {
                            uidCount = uidCount2;
                            token2 = token3;
                            pmap2 = pmap3;
                            procCount = procCount2;
                            pname2 = pname3;
                        }
                        i++;
                        pmap3 = pmap2;
                        uidCount2 = uidCount;
                        procCount2 = procCount;
                        pname3 = pname2;
                        token3 = token2;
                    } else {
                        uidCount = uidCount2;
                    }
                    pmap2 = pmap3;
                    procCount = procCount2;
                    pname2 = pname3;
                    long etoken = proto.start(2246267895810L);
                    proto.write(1120986464257L, puid);
                    token2 = token3;
                    proto.write(1112396529666L, uids2.valueAt(i).longValue());
                    proto.end(etoken);
                    i++;
                    pmap3 = pmap2;
                    uidCount2 = uidCount;
                    procCount2 = procCount;
                    pname3 = pname2;
                    token3 = token2;
                }
                proto.end(ctoken);
                ip++;
                now = now2;
                j = 1138166333441L;
                j2 = 2246267895810L;
            }
            token = token3;
        }
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
                    ProcessRecord r2 = (ProcessRecord) this.mService.getProcessNames().get(pname4, puid2);
                    if (str != null) {
                        if (r2 == null) {
                            pmap = pmap4;
                            pname = pname4;
                            uids = uids3;
                        } else if (!r2.pkgList.containsKey(str)) {
                            pmap = pmap4;
                            pname = pname4;
                            uids = uids3;
                        }
                        i2++;
                        str = dumpPackage;
                        pmap4 = pmap;
                        pname4 = pname;
                        uids3 = uids;
                    }
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
                    i2++;
                    str = dumpPackage;
                    pmap4 = pmap;
                    pname4 = pname;
                    uids3 = uids;
                }
                proto.end(btoken);
                ip2++;
                str = dumpPackage;
            }
        }
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dumpLocked(FileDescriptor fd, PrintWriter pw, boolean needSep, String dumpPackage) {
        boolean needSep2;
        String str;
        ArrayMap<String, SparseArray<BadProcessInfo>> pmap;
        int processCount;
        AppErrors appErrors = this;
        String str2 = dumpPackage;
        String str3 = " uid ";
        if (appErrors.mProcessCrashTimes.getMap().isEmpty()) {
            needSep2 = needSep;
        } else {
            boolean printed = false;
            long now = SystemClock.uptimeMillis();
            ArrayMap<String, SparseArray<Long>> pmap2 = appErrors.mProcessCrashTimes.getMap();
            int processCount2 = pmap2.size();
            needSep2 = needSep;
            for (int ip = 0; ip < processCount2; ip++) {
                String pname = pmap2.keyAt(ip);
                SparseArray<Long> uids = pmap2.valueAt(ip);
                int uidCount = uids.size();
                int i = 0;
                while (i < uidCount) {
                    int puid = uids.keyAt(i);
                    ArrayMap<String, SparseArray<Long>> pmap3 = pmap2;
                    ProcessRecord r = (ProcessRecord) appErrors.mService.getProcessNames().get(pname, puid);
                    if (str2 != null) {
                        if (r == null) {
                            processCount = processCount2;
                        } else {
                            processCount = processCount2;
                            if (!r.pkgList.containsKey(str2)) {
                            }
                        }
                        i++;
                        pmap2 = pmap3;
                        processCount2 = processCount;
                    } else {
                        processCount = processCount2;
                    }
                    if (!printed) {
                        if (needSep2) {
                            pw.println();
                        }
                        needSep2 = true;
                        pw.println("  Time since processes crashed:");
                        printed = true;
                    }
                    pw.print("    Process ");
                    pw.print(pname);
                    pw.print(" uid ");
                    pw.print(puid);
                    pw.print(": last crashed ");
                    TimeUtils.formatDuration(now - uids.valueAt(i).longValue(), pw);
                    pw.println(" ago");
                    i++;
                    pmap2 = pmap3;
                    processCount2 = processCount;
                }
            }
        }
        if (!appErrors.mBadProcesses.getMap().isEmpty()) {
            boolean printed2 = false;
            ArrayMap<String, SparseArray<BadProcessInfo>> pmap4 = appErrors.mBadProcesses.getMap();
            int processCount3 = pmap4.size();
            int ip2 = 0;
            while (ip2 < processCount3) {
                String pname2 = pmap4.keyAt(ip2);
                SparseArray<BadProcessInfo> uids2 = pmap4.valueAt(ip2);
                int uidCount2 = uids2.size();
                int i2 = 0;
                while (i2 < uidCount2) {
                    int puid2 = uids2.keyAt(i2);
                    ProcessRecord r2 = (ProcessRecord) appErrors.mService.getProcessNames().get(pname2, puid2);
                    if (str2 != null && (r2 == null || !r2.pkgList.containsKey(str2))) {
                        str = str3;
                        pmap = pmap4;
                    } else {
                        if (!printed2) {
                            if (needSep2) {
                                pw.println();
                            }
                            needSep2 = true;
                            pw.println("  Bad processes:");
                            printed2 = true;
                        }
                        BadProcessInfo info = uids2.valueAt(i2);
                        pw.print("    Bad process ");
                        pw.print(pname2);
                        pw.print(str3);
                        pw.print(puid2);
                        pw.print(": crashed at time ");
                        boolean printed3 = printed2;
                        pw.println(info.time);
                        if (info.shortMsg != null) {
                            pw.print("      Short msg: ");
                            pw.println(info.shortMsg);
                        }
                        if (info.longMsg != null) {
                            pw.print("      Long msg: ");
                            pw.println(info.longMsg);
                        }
                        if (info.stack == null) {
                            str = str3;
                            pmap = pmap4;
                        } else {
                            pw.println("      Stack:");
                            int lastPos = 0;
                            int pos = 0;
                            while (true) {
                                str = str3;
                                if (pos >= info.stack.length()) {
                                    break;
                                }
                                ArrayMap<String, SparseArray<BadProcessInfo>> pmap5 = pmap4;
                                if (info.stack.charAt(pos) == '\n') {
                                    pw.print("        ");
                                    pw.write(info.stack, lastPos, pos - lastPos);
                                    pw.println();
                                    lastPos = pos + 1;
                                }
                                pos++;
                                str3 = str;
                                pmap4 = pmap5;
                            }
                            pmap = pmap4;
                            if (lastPos < info.stack.length()) {
                                pw.print("        ");
                                pw.write(info.stack, lastPos, info.stack.length() - lastPos);
                                pw.println();
                            }
                        }
                        printed2 = printed3;
                    }
                    i2++;
                    appErrors = this;
                    str2 = dumpPackage;
                    str3 = str;
                    pmap4 = pmap;
                }
                ip2++;
                appErrors = this;
                str2 = dumpPackage;
            }
        }
        return needSep2;
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
        if (app.anrDialog == fromDialog) {
            app.anrDialog = null;
        }
        if (app.waitDialog == fromDialog) {
            app.waitDialog = null;
        }
        killAppImmediateLocked(app, "user-terminated", "user request after error");
    }

    private void killAppImmediateLocked(ProcessRecord app, String reason, String killReason) {
        app.setCrashing(false);
        app.crashingReport = null;
        app.setNotResponding(false);
        app.notRespondingReport = null;
        if (app.pid > 0 && app.pid != ActivityManagerService.MY_PID) {
            handleAppCrashLocked(app, reason, null, null, null, null);
            app.kill(killReason, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleAppCrashLocked(int uid, int initialPid, String packageName, int userId, String message, boolean force) {
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
        if (force) {
            final ProcessRecord p2 = proc;
            this.mService.mHandler.postDelayed(new Runnable() { // from class: com.android.server.am.-$$Lambda$AppErrors$1aFX_-j-MSc0clpKk9XdlBZz9lU
                @Override // java.lang.Runnable
                public final void run() {
                    AppErrors.this.lambda$scheduleAppCrashLocked$0$AppErrors(p2);
                }
            }, 5000L);
        }
    }

    public /* synthetic */ void lambda$scheduleAppCrashLocked$0$AppErrors(ProcessRecord p) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                killAppImmediateLocked(p, "forced", "killed for invalid state");
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
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

    void crashApplicationInner(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo, int callingPid, int callingUid, long time) {
        long timeMillis;
        String longMsg;
        ActivityManagerService activityManagerService;
        if (time == 0) {
            timeMillis = System.currentTimeMillis();
        } else {
            timeMillis = time;
        }
        String shortMsg = crashInfo.exceptionClassName;
        String longMsg2 = crashInfo.exceptionMessage;
        String stackTrace = crashInfo.stackTrace;
        if (shortMsg != null && longMsg2 != null) {
            longMsg = shortMsg + ": " + longMsg2;
        } else if (shortMsg == null) {
            longMsg = longMsg2;
        } else {
            longMsg = shortMsg;
        }
        if (r != null) {
            boolean isApexModule = false;
            try {
                String[] packageList = r.getPackageList();
                int length = packageList.length;
                int i = 0;
                while (true) {
                    if (i >= length) {
                        break;
                    }
                    String androidPackage = packageList[i];
                    ModuleInfo moduleInfo = this.mContext.getPackageManager().getModuleInfo(androidPackage, 0);
                    if (moduleInfo == null) {
                        i++;
                    } else {
                        isApexModule = true;
                        break;
                    }
                }
            } catch (PackageManager.NameNotFoundException | IllegalStateException e) {
            }
            if (r.isPersistent() || isApexModule) {
                RescueParty.noteAppCrash(this.mContext, r.uid);
            }
            this.mPackageWatchdog.onPackageFailure(r.getPackageListWithVersionCode(), 3);
        }
        int relaunchReason = r != null ? r.getWindowProcessController().computeRelaunchReason() : 0;
        AppErrorResult result = new AppErrorResult();
        ActivityManagerService activityManagerService2 = this.mService;
        synchronized (activityManagerService2) {
            try {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    activityManagerService = activityManagerService2;
                    try {
                        try {
                            if (handleAppCrashInActivityController(r, crashInfo, shortMsg, longMsg, stackTrace, timeMillis, callingPid, callingUid)) {
                            } else if (relaunchReason == 2) {
                                ActivityManagerService.resetPriorityAfterLockedSection();
                            } else if (r != null && r.getActiveInstrumentation() != null) {
                                ActivityManagerService.resetPriorityAfterLockedSection();
                            } else {
                                if (r != null) {
                                    this.mService.mBatteryStatsService.noteProcessCrash(r.processName, r.uid);
                                }
                                AppErrorDialog.Data data = new AppErrorDialog.Data();
                                try {
                                    data.result = result;
                                    data.proc = r;
                                    if (r != null && makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace, data)) {
                                        Message msg = Message.obtain();
                                        msg.what = 1;
                                        int taskId = data.taskId;
                                        msg.obj = data;
                                        this.mService.mUiHandler.sendMessage(msg);
                                        Intent appErrorService = this.mService.createAppErrorServiceLocked(r, timeMillis, crashInfo);
                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                        if (appErrorService != null) {
                                            try {
                                                this.mContext.startServiceAsUser(appErrorService, UserHandle.SYSTEM);
                                            } catch (Exception e2) {
                                                Slog.w(TAG, "bug report receiver dissappeared", e2);
                                            }
                                        }
                                        int res = result.get();
                                        Intent appErrorIntent = null;
                                        MetricsLogger.action(this.mContext, 316, res);
                                        int res2 = (res == 6 || res == 7) ? 1 : res;
                                        synchronized (this.mService) {
                                            try {
                                                ActivityManagerService.boostPriorityForLockedSection();
                                                if (res2 == 5) {
                                                    stopReportingCrashesLocked(r);
                                                }
                                                if (res2 == 3) {
                                                    this.mService.mProcessList.removeProcessLocked(r, false, true, "crash");
                                                    if (taskId != -1) {
                                                        try {
                                                            this.mService.startActivityFromRecents(taskId, ActivityOptions.makeBasic().toBundle());
                                                        } catch (IllegalArgumentException e3) {
                                                            Slog.e(TAG, "Could not restart taskId=" + taskId, e3);
                                                        }
                                                    }
                                                }
                                                if (res2 == 1) {
                                                    long orig = Binder.clearCallingIdentity();
                                                    this.mService.mAtmInternal.onHandleAppCrash(r.getWindowProcessController());
                                                    if (!r.isPersistent()) {
                                                        this.mService.mProcessList.removeProcessLocked(r, false, false, "crash");
                                                        this.mService.mAtmInternal.resumeTopActivities(false);
                                                    }
                                                    Binder.restoreCallingIdentity(orig);
                                                }
                                                if (res2 == 8) {
                                                    appErrorIntent = new Intent("android.settings.APPLICATION_DETAILS_SETTINGS");
                                                    appErrorIntent.setData(Uri.parse("package:" + r.info.packageName));
                                                    appErrorIntent.addFlags(268435456);
                                                }
                                                if (res2 == 2) {
                                                    appErrorIntent = createAppErrorIntentLocked(r, timeMillis, crashInfo);
                                                }
                                                if (!r.isolated && res2 != 3) {
                                                    this.mProcessCrashTimes.put(r.info.processName, r.uid, Long.valueOf(SystemClock.uptimeMillis()));
                                                }
                                            } finally {
                                                ActivityManagerService.resetPriorityAfterLockedSection();
                                            }
                                        }
                                        ActivityManagerService.resetPriorityAfterLockedSection();
                                        if (appErrorIntent != null) {
                                            try {
                                                this.mContext.startActivityAsUser(appErrorIntent, new UserHandle(r.userId));
                                                return;
                                            } catch (ActivityNotFoundException e4) {
                                                Slog.w(TAG, "bug report receiver dissappeared", e4);
                                                return;
                                            }
                                        }
                                        return;
                                    }
                                    ActivityManagerService.resetPriorityAfterLockedSection();
                                } catch (Throwable th) {
                                    th = th;
                                    ActivityManagerService.resetPriorityAfterLockedSection();
                                    throw th;
                                }
                            }
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    } catch (Throwable th3) {
                        th = th3;
                    }
                } catch (Throwable th4) {
                    th = th4;
                    activityManagerService = activityManagerService2;
                }
            } catch (Throwable th5) {
                th = th5;
            }
        }
    }

    private boolean handleAppCrashInActivityController(final ProcessRecord r, final ApplicationErrorReport.CrashInfo crashInfo, final String shortMsg, final String longMsg, final String stackTrace, long timeMillis, int callingPid, int callingUid) {
        final String name = r != null ? r.processName : null;
        final int pid = r != null ? r.pid : callingPid;
        final int uid = r != null ? r.info.uid : callingUid;
        return this.mService.mAtmInternal.handleAppCrashInActivityController(name, pid, shortMsg, longMsg, timeMillis, crashInfo.stackTrace, new Runnable() { // from class: com.android.server.am.-$$Lambda$AppErrors$Ziph9zXnTzhEV6frMYJe_IEvvfY
            @Override // java.lang.Runnable
            public final void run() {
                AppErrors.this.lambda$handleAppCrashInActivityController$1$AppErrors(crashInfo, name, pid, r, shortMsg, longMsg, stackTrace, uid);
            }
        });
    }

    public /* synthetic */ void lambda$handleAppCrashInActivityController$1$AppErrors(ApplicationErrorReport.CrashInfo crashInfo, String name, int pid, ProcessRecord r, String shortMsg, String longMsg, String stackTrace, int uid) {
        if ("1".equals(SystemProperties.get("ro.debuggable", "0")) && "Native crash".equals(crashInfo.exceptionClassName)) {
            Slog.w(TAG, "Skip killing native crashed app " + name + "(" + pid + ") during testing");
            return;
        }
        Slog.w(TAG, "Force-killing crashed app " + name + " at watcher's request");
        if (r != null) {
            if (!makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace, null)) {
                r.kill("crash", true);
                return;
            }
            return;
        }
        Process.killProcess(pid);
        ProcessList.killProcessGroup(uid, pid);
    }

    private boolean makeAppCrashingLocked(ProcessRecord app, String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        app.setCrashing(true);
        app.crashingReport = generateProcessError(app, 1, null, shortMsg, longMsg, stackTrace);
        app.startAppProblemLocked();
        app.getWindowProcessController().stopFreezingActivities();
        return handleAppCrashLocked(app, "force-crash", shortMsg, longMsg, stackTrace, data);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManager.ProcessErrorStateInfo generateProcessError(ProcessRecord app, int condition, String activity, String shortMsg, String longMsg, String stackTrace) {
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
        if (r.isCrashing() || r.isNotResponding() || r.forceCrashReport) {
            ApplicationErrorReport report = new ApplicationErrorReport();
            report.packageName = r.info.packageName;
            report.installerPackageName = r.errorReportReceiver.getPackageName();
            report.processName = r.processName;
            report.time = timeMillis;
            report.systemApp = (r.info.flags & 1) != 0;
            if (r.isCrashing() || r.forceCrashReport) {
                report.type = 1;
                report.crashInfo = crashInfo;
            } else if (r.isNotResponding()) {
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

    boolean handleAppCrashLocked(ProcessRecord app, String reason, String shortMsg, String longMsg, String stackTrace, AppErrorDialog.Data data) {
        Long crashTime;
        Long crashTimePersistent;
        long now;
        boolean tryAgain;
        boolean z;
        long now2 = SystemClock.uptimeMillis();
        boolean showBackground = Settings.Secure.getInt(this.mContext.getContentResolver(), "anr_show_background", 0) != 0;
        boolean procIsBoundForeground = app.getCurProcState() == 6;
        if (!app.isolated) {
            Long crashTime2 = (Long) this.mProcessCrashTimes.get(app.info.processName, app.uid);
            crashTime = crashTime2;
            crashTimePersistent = (Long) this.mProcessCrashTimesPersistent.get(app.info.processName, app.uid);
        } else {
            crashTime = null;
            crashTimePersistent = null;
        }
        boolean tryAgain2 = false;
        for (int i = app.services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = app.services.valueAt(i);
            if (now2 > sr.restartTime + 60000) {
                sr.crashCount = 1;
            } else {
                sr.crashCount++;
            }
            if (sr.crashCount < this.mService.mConstants.BOUND_SERVICE_MAX_CRASH_RETRY && (sr.isForeground || procIsBoundForeground)) {
                tryAgain2 = true;
            }
        }
        if (crashTime == null || now2 >= crashTime.longValue() + 60000) {
            now = now2;
            tryAgain = tryAgain2;
            int affectedTaskId = this.mService.mAtmInternal.finishTopCrashedActivities(app.getWindowProcessController(), reason);
            if (data != null) {
                data.taskId = affectedTaskId;
            }
            if (data != null && crashTimePersistent != null && now < crashTimePersistent.longValue() + 60000) {
                data.repeating = true;
            }
        } else {
            Slog.w(TAG, "Process " + app.info.processName + " has crashed too many times: killing!");
            EventLog.writeEvent((int) EventLogTags.AM_PROCESS_CRASHED_TOO_MUCH, Integer.valueOf(app.userId), app.info.processName, Integer.valueOf(app.uid));
            this.mService.mAtmInternal.onHandleAppCrash(app.getWindowProcessController());
            if (app.isPersistent()) {
                now = now2;
                z = false;
                tryAgain = tryAgain2;
            } else {
                EventLog.writeEvent((int) EventLogTags.AM_PROC_BAD, Integer.valueOf(app.userId), Integer.valueOf(app.uid), app.info.processName);
                if (app.isolated) {
                    now = now2;
                    tryAgain = tryAgain2;
                } else {
                    now = now2;
                    tryAgain = tryAgain2;
                    this.mBadProcesses.put(app.info.processName, app.uid, new BadProcessInfo(now2, shortMsg, longMsg, stackTrace));
                    this.mProcessCrashTimes.remove(app.info.processName, app.uid);
                }
                app.bad = true;
                app.removed = true;
                z = false;
                this.mService.mProcessList.removeProcessLocked(app, false, tryAgain, "crash");
                this.mService.mAtmInternal.resumeTopActivities(false);
                if (!showBackground) {
                    return false;
                }
            }
            this.mService.mAtmInternal.resumeTopActivities(z);
        }
        if (data != null && tryAgain) {
            data.isRestartableForService = true;
        }
        WindowProcessController proc = app.getWindowProcessController();
        WindowProcessController homeProc = this.mService.mAtmInternal.getHomeProcess();
        if (proc == homeProc && proc.hasActivities() && (((ProcessRecord) homeProc.mOwner).info.flags & 1) == 0) {
            proc.clearPackagePreferredForHomeActivities();
        }
        if (!app.isolated) {
            this.mProcessCrashTimes.put(app.info.processName, app.uid, Long.valueOf(now));
            this.mProcessCrashTimesPersistent.put(app.info.processName, app.uid, Long.valueOf(now));
        }
        if (app.crashHandler != null) {
            this.mService.mHandler.post(app.crashHandler);
            return true;
        }
        return true;
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
                if ((this.mService.mAtmInternal.canShowErrorDialogs() || showBackground) && !crashSilenced && (showFirstCrash || showFirstCrashDevOption || data.repeating)) {
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

    private void stopReportingCrashesLocked(ProcessRecord proc) {
        if (this.mAppsNotReportingCrashes == null) {
            this.mAppsNotReportingCrashes = new ArraySet<>();
        }
        this.mAppsNotReportingCrashes.add(proc.info.packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:31:0x008b  */
    /* JADX WARN: Removed duplicated region for block: B:33:0x0090  */
    /* JADX WARN: Removed duplicated region for block: B:40:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void handleShowAnrUi(android.os.Message r10) {
        /*
            r9 = this;
            r0 = 0
            r1 = 0
            com.android.server.am.ActivityManagerService r2 = r9.mService
            monitor-enter(r2)
            com.android.server.am.ActivityManagerService.boostPriorityForLockedSection()     // Catch: java.lang.Throwable -> L97
            java.lang.Object r3 = r10.obj     // Catch: java.lang.Throwable -> L97
            com.android.server.am.AppNotRespondingDialog$Data r3 = (com.android.server.am.AppNotRespondingDialog.Data) r3     // Catch: java.lang.Throwable -> L97
            com.android.server.am.ProcessRecord r4 = r3.proc     // Catch: java.lang.Throwable -> L97
            if (r4 != 0) goto L1c
            java.lang.String r5 = "ActivityManager"
            java.lang.String r6 = "handleShowAnrUi: proc is null"
            android.util.Slog.e(r5, r6)     // Catch: java.lang.Throwable -> L97
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L97
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            return
        L1c:
            boolean r5 = r4.isPersistent()     // Catch: java.lang.Throwable -> L97
            if (r5 != 0) goto L27
            java.util.List r5 = r4.getPackageListWithVersionCode()     // Catch: java.lang.Throwable -> L97
            r1 = r5
        L27:
            android.app.Dialog r5 = r4.anrDialog     // Catch: java.lang.Throwable -> L97
            r6 = 317(0x13d, float:4.44E-43)
            if (r5 == 0) goto L4e
            java.lang.String r5 = "ActivityManager"
            java.lang.StringBuilder r7 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L97
            r7.<init>()     // Catch: java.lang.Throwable -> L97
            java.lang.String r8 = "App already has anr dialog: "
            r7.append(r8)     // Catch: java.lang.Throwable -> L97
            r7.append(r4)     // Catch: java.lang.Throwable -> L97
            java.lang.String r7 = r7.toString()     // Catch: java.lang.Throwable -> L97
            android.util.Slog.e(r5, r7)     // Catch: java.lang.Throwable -> L97
            android.content.Context r5 = r9.mContext     // Catch: java.lang.Throwable -> L97
            r7 = -2
            com.android.internal.logging.MetricsLogger.action(r5, r6, r7)     // Catch: java.lang.Throwable -> L97
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L97
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            return
        L4e:
            android.content.Context r5 = r9.mContext     // Catch: java.lang.Throwable -> L97
            android.content.ContentResolver r5 = r5.getContentResolver()     // Catch: java.lang.Throwable -> L97
            java.lang.String r7 = "anr_show_background"
            r8 = 0
            int r5 = android.provider.Settings.Secure.getInt(r5, r7, r8)     // Catch: java.lang.Throwable -> L97
            if (r5 == 0) goto L5e
            r8 = 1
        L5e:
            r5 = r8
            com.android.server.am.ActivityManagerService r7 = r9.mService     // Catch: java.lang.Throwable -> L97
            com.android.server.wm.ActivityTaskManagerInternal r7 = r7.mAtmInternal     // Catch: java.lang.Throwable -> L97
            boolean r7 = r7.canShowErrorDialogs()     // Catch: java.lang.Throwable -> L97
            if (r7 != 0) goto L79
            if (r5 == 0) goto L6c
            goto L79
        L6c:
            android.content.Context r7 = r9.mContext     // Catch: java.lang.Throwable -> L97
            r8 = -1
            com.android.internal.logging.MetricsLogger.action(r7, r6, r8)     // Catch: java.lang.Throwable -> L97
            com.android.server.am.ActivityManagerService r6 = r9.mService     // Catch: java.lang.Throwable -> L97
            r7 = 0
            r6.killAppAtUsersRequest(r4, r7)     // Catch: java.lang.Throwable -> L97
            goto L85
        L79:
            com.android.server.am.AppNotRespondingDialog r6 = new com.android.server.am.AppNotRespondingDialog     // Catch: java.lang.Throwable -> L97
            com.android.server.am.ActivityManagerService r7 = r9.mService     // Catch: java.lang.Throwable -> L97
            android.content.Context r8 = r9.mContext     // Catch: java.lang.Throwable -> L97
            r6.<init>(r7, r8, r3)     // Catch: java.lang.Throwable -> L97
            r0 = r6
            r4.anrDialog = r0     // Catch: java.lang.Throwable -> L97
        L85:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L97
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            if (r0 == 0) goto L8e
            r0.show()
        L8e:
            if (r1 == 0) goto L96
            com.android.server.PackageWatchdog r2 = r9.mPackageWatchdog
            r3 = 4
            r2.onPackageFailure(r1, r3)
        L96:
            return
        L97:
            r3 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L97
            com.android.server.am.ActivityManagerService.resetPriorityAfterLockedSection()
            throw r3
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
