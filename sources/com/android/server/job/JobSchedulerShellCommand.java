package com.android.server.job;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.ShellCommand;
import android.os.UserHandle;
import com.android.server.backup.BackupManagerConstants;
import java.io.PrintWriter;
/* loaded from: classes.dex */
public final class JobSchedulerShellCommand extends ShellCommand {
    public static final int CMD_ERR_CONSTRAINTS = -1002;
    public static final int CMD_ERR_NO_JOB = -1001;
    public static final int CMD_ERR_NO_PACKAGE = -1000;
    JobSchedulerService mInternal;
    IPackageManager mPM = AppGlobals.getPackageManager();

    /* JADX INFO: Access modifiers changed from: package-private */
    public JobSchedulerShellCommand(JobSchedulerService service) {
        this.mInternal = service;
    }

    public int onCommand(String cmd) {
        char c;
        PrintWriter pw = getOutPrintWriter();
        String str = cmd != null ? cmd : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        try {
            switch (str.hashCode()) {
                case -1894245460:
                    if (str.equals("trigger-dock-state")) {
                        c = 11;
                        break;
                    }
                    c = 65535;
                    break;
                case -1845752298:
                    if (str.equals("get-storage-seq")) {
                        c = 7;
                        break;
                    }
                    c = 65535;
                    break;
                case -1687551032:
                    if (str.equals("get-battery-charging")) {
                        c = 5;
                        break;
                    }
                    c = 65535;
                    break;
                case -1367724422:
                    if (str.equals("cancel")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case -1313911455:
                    if (str.equals("timeout")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case 113291:
                    if (str.equals("run")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 55361425:
                    if (str.equals("get-storage-not-low")) {
                        c = '\b';
                        break;
                    }
                    c = 65535;
                    break;
                case 200896764:
                    if (str.equals("heartbeat")) {
                        c = '\n';
                        break;
                    }
                    c = 65535;
                    break;
                case 703160488:
                    if (str.equals("get-battery-seq")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case 1749711139:
                    if (str.equals("get-battery-not-low")) {
                        c = 6;
                        break;
                    }
                    c = 65535;
                    break;
                case 1791471818:
                    if (str.equals("get-job-state")) {
                        c = '\t';
                        break;
                    }
                    c = 65535;
                    break;
                case 1854493850:
                    if (str.equals("monitor-battery")) {
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
                    return runJob(pw);
                case 1:
                    return timeout(pw);
                case 2:
                    return cancelJob(pw);
                case 3:
                    return monitorBattery(pw);
                case 4:
                    return getBatterySeq(pw);
                case 5:
                    return getBatteryCharging(pw);
                case 6:
                    return getBatteryNotLow(pw);
                case 7:
                    return getStorageSeq(pw);
                case '\b':
                    return getStorageNotLow(pw);
                case '\t':
                    return getJobState(pw);
                case '\n':
                    return doHeartbeat(pw);
                case 11:
                    return triggerDockState(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception: " + e);
            return -1;
        }
    }

    private void checkPermission(String operation) throws Exception {
        int uid = Binder.getCallingUid();
        if (uid == 0) {
            return;
        }
        int perm = this.mPM.checkUidPermission("android.permission.CHANGE_APP_IDLE_STATE", uid);
        if (perm != 0) {
            throw new SecurityException("Uid " + uid + " not permitted to " + operation);
        }
    }

    private boolean printError(int errCode, String pkgName, int userId, int jobId) {
        switch (errCode) {
            case CMD_ERR_CONSTRAINTS /* -1002 */:
                PrintWriter pw = getErrPrintWriter();
                pw.print("Job ");
                pw.print(jobId);
                pw.print(" in package ");
                pw.print(pkgName);
                pw.print(" / user ");
                pw.print(userId);
                pw.println(" has functional constraints but --force not specified");
                return true;
            case CMD_ERR_NO_JOB /* -1001 */:
                PrintWriter pw2 = getErrPrintWriter();
                pw2.print("Could not find job ");
                pw2.print(jobId);
                pw2.print(" in package ");
                pw2.print(pkgName);
                pw2.print(" / user ");
                pw2.println(userId);
                return true;
            case CMD_ERR_NO_PACKAGE /* -1000 */:
                PrintWriter pw3 = getErrPrintWriter();
                pw3.print("Package not found: ");
                pw3.print(pkgName);
                pw3.print(" / user ");
                pw3.println(userId);
                return true;
            default:
                return false;
        }
    }

    private int runJob(PrintWriter pw) throws Exception {
        char c;
        checkPermission("force scheduled jobs");
        boolean force = false;
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                int hashCode = opt.hashCode();
                if (hashCode == -1626076853) {
                    if (opt.equals("--force")) {
                        c = 1;
                    }
                    c = 65535;
                } else if (hashCode == 1497) {
                    if (opt.equals("-f")) {
                        c = 0;
                    }
                    c = 65535;
                } else if (hashCode != 1512) {
                    if (hashCode == 1333469547 && opt.equals("--user")) {
                        c = 3;
                    }
                    c = 65535;
                } else {
                    if (opt.equals("-u")) {
                        c = 2;
                    }
                    c = 65535;
                }
                switch (c) {
                    case 0:
                    case 1:
                        force = true;
                        break;
                    case 2:
                    case 3:
                        userId = Integer.parseInt(getNextArgRequired());
                        break;
                    default:
                        pw.println("Error: unknown option '" + opt + "'");
                        return -1;
                }
            } else {
                String pkgName = getNextArgRequired();
                int jobId = Integer.parseInt(getNextArgRequired());
                long ident = Binder.clearCallingIdentity();
                try {
                    int ret = this.mInternal.executeRunCommand(pkgName, userId, jobId, force);
                    if (printError(ret, pkgName, userId, jobId)) {
                        return ret;
                    }
                    pw.print("Running job");
                    if (force) {
                        pw.print(" [FORCED]");
                    }
                    pw.println();
                    return ret;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:14:0x0030, code lost:
        if (r3.equals("-u") != false) goto L11;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int timeout(java.io.PrintWriter r18) throws java.lang.Exception {
        /*
            r17 = this;
            r1 = r17
            java.lang.String r0 = "force timeout jobs"
            r1.checkPermission(r0)
            r0 = -1
            r2 = r0
        L9:
            java.lang.String r3 = r17.getNextOption()
            r4 = r3
            r5 = 0
            r6 = 1
            if (r3 == 0) goto L5c
            int r3 = r4.hashCode()
            r7 = 1512(0x5e8, float:2.119E-42)
            if (r3 == r7) goto L2a
            r5 = 1333469547(0x4f7b216b, float:4.2132713E9)
            if (r3 == r5) goto L20
            goto L33
        L20:
            java.lang.String r3 = "--user"
            boolean r3 = r4.equals(r3)
            if (r3 == 0) goto L33
            r5 = r6
            goto L34
        L2a:
            java.lang.String r3 = "-u"
            boolean r3 = r4.equals(r3)
            if (r3 == 0) goto L33
            goto L34
        L33:
            r5 = r0
        L34:
            switch(r5) {
                case 0: goto L53;
                case 1: goto L53;
                default: goto L37;
            }
        L37:
            java.lang.StringBuilder r3 = new java.lang.StringBuilder
            r3.<init>()
            java.lang.String r5 = "Error: unknown option '"
            r3.append(r5)
            r3.append(r4)
            java.lang.String r5 = "'"
            r3.append(r5)
            java.lang.String r3 = r3.toString()
            r13 = r18
            r13.println(r3)
            return r0
        L53:
            java.lang.String r3 = r17.getNextArgRequired()
            int r2 = android.os.UserHandle.parseUserArg(r3)
            goto L9
        L5c:
            r13 = r18
            r3 = -2
            if (r2 != r3) goto L65
            int r2 = android.app.ActivityManager.getCurrentUser()
        L65:
            java.lang.String r3 = r17.getNextArg()
            java.lang.String r14 = r17.getNextArg()
            if (r14 == 0) goto L75
            int r0 = java.lang.Integer.parseInt(r14)
        L73:
            r12 = r0
            goto L76
        L75:
            goto L73
        L76:
            long r7 = android.os.Binder.clearCallingIdentity()
            r10 = r7
            com.android.server.job.JobSchedulerService r7 = r1.mInternal     // Catch: java.lang.Throwable -> L93
            if (r14 == 0) goto L81
            r5 = r6
        L81:
            r8 = r13
            r9 = r3
            r16 = r3
            r15 = r4
            r3 = r10
            r10 = r2
            r11 = r5
            int r0 = r7.executeTimeoutCommand(r8, r9, r10, r11, r12)     // Catch: java.lang.Throwable -> L91
            android.os.Binder.restoreCallingIdentity(r3)
            return r0
        L91:
            r0 = move-exception
            goto L98
        L93:
            r0 = move-exception
            r16 = r3
            r15 = r4
            r3 = r10
        L98:
            android.os.Binder.restoreCallingIdentity(r3)
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.job.JobSchedulerShellCommand.timeout(java.io.PrintWriter):int");
    }

    /* JADX WARN: Code restructure failed: missing block: B:11:0x0024, code lost:
        if (r2.equals("--user") == false) goto L16;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int cancelJob(java.io.PrintWriter r14) throws java.lang.Exception {
        /*
            r13 = this;
            java.lang.String r0 = "cancel jobs"
            r13.checkPermission(r0)
            r0 = 0
            r1 = r0
        L7:
            java.lang.String r2 = r13.getNextOption()
            r8 = r2
            r3 = 1
            r4 = -1
            if (r2 == 0) goto L58
            int r2 = r8.hashCode()
            r5 = 1512(0x5e8, float:2.119E-42)
            if (r2 == r5) goto L27
            r5 = 1333469547(0x4f7b216b, float:4.2132713E9)
            if (r2 == r5) goto L1e
            goto L31
        L1e:
            java.lang.String r2 = "--user"
            boolean r2 = r8.equals(r2)
            if (r2 == 0) goto L31
            goto L32
        L27:
            java.lang.String r2 = "-u"
            boolean r2 = r8.equals(r2)
            if (r2 == 0) goto L31
            r3 = r0
            goto L32
        L31:
            r3 = r4
        L32:
            switch(r3) {
                case 0: goto L4f;
                case 1: goto L4f;
                default: goto L35;
            }
        L35:
            java.lang.StringBuilder r0 = new java.lang.StringBuilder
            r0.<init>()
            java.lang.String r2 = "Error: unknown option '"
            r0.append(r2)
            r0.append(r8)
            java.lang.String r2 = "'"
            r0.append(r2)
            java.lang.String r0 = r0.toString()
            r14.println(r0)
            return r4
        L4f:
            java.lang.String r2 = r13.getNextArgRequired()
            int r1 = android.os.UserHandle.parseUserArg(r2)
            goto L7
        L58:
            if (r1 >= 0) goto L60
            java.lang.String r0 = "Error: must specify a concrete user ID"
            r14.println(r0)
            return r4
        L60:
            java.lang.String r9 = r13.getNextArg()
            java.lang.String r10 = r13.getNextArg()
            if (r10 == 0) goto L70
            int r2 = java.lang.Integer.parseInt(r10)
            r7 = r2
            goto L71
        L70:
            r7 = r4
        L71:
            long r4 = android.os.Binder.clearCallingIdentity()
            r11 = r4
            com.android.server.job.JobSchedulerService r2 = r13.mInternal     // Catch: java.lang.Throwable -> L88
            if (r10 == 0) goto L7c
            r6 = r3
            goto L7d
        L7c:
            r6 = r0
        L7d:
            r3 = r14
            r4 = r9
            r5 = r1
            int r0 = r2.executeCancelCommand(r3, r4, r5, r6, r7)     // Catch: java.lang.Throwable -> L88
            android.os.Binder.restoreCallingIdentity(r11)
            return r0
        L88:
            r0 = move-exception
            android.os.Binder.restoreCallingIdentity(r11)
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.job.JobSchedulerShellCommand.cancelJob(java.io.PrintWriter):int");
    }

    private int monitorBattery(PrintWriter pw) throws Exception {
        boolean enabled;
        checkPermission("change battery monitoring");
        String opt = getNextArgRequired();
        if ("on".equals(opt)) {
            enabled = true;
        } else if ("off".equals(opt)) {
            enabled = false;
        } else {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: unknown option " + opt);
            return 1;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mInternal.setMonitorBattery(enabled);
            if (enabled) {
                pw.println("Battery monitoring enabled");
            } else {
                pw.println("Battery monitoring disabled");
            }
            Binder.restoreCallingIdentity(ident);
            return 0;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
    }

    private int getBatterySeq(PrintWriter pw) {
        int seq = this.mInternal.getBatterySeq();
        pw.println(seq);
        return 0;
    }

    private int getBatteryCharging(PrintWriter pw) {
        boolean val = this.mInternal.getBatteryCharging();
        pw.println(val);
        return 0;
    }

    private int getBatteryNotLow(PrintWriter pw) {
        boolean val = this.mInternal.getBatteryNotLow();
        pw.println(val);
        return 0;
    }

    private int getStorageSeq(PrintWriter pw) {
        int seq = this.mInternal.getStorageSeq();
        pw.println(seq);
        return 0;
    }

    private int getStorageNotLow(PrintWriter pw) {
        boolean val = this.mInternal.getStorageNotLow();
        pw.println(val);
        return 0;
    }

    private int getJobState(PrintWriter pw) throws Exception {
        boolean z;
        checkPermission("force timeout jobs");
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                int hashCode = opt.hashCode();
                if (hashCode != 1512) {
                    if (hashCode == 1333469547 && opt.equals("--user")) {
                        z = true;
                    }
                    z = true;
                } else {
                    if (opt.equals("-u")) {
                        z = false;
                    }
                    z = true;
                }
                switch (z) {
                    case false:
                    case true:
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                    default:
                        pw.println("Error: unknown option '" + opt + "'");
                        return -1;
                }
            } else {
                if (userId == -2) {
                    userId = ActivityManager.getCurrentUser();
                }
                String pkgName = getNextArgRequired();
                String jobIdStr = getNextArgRequired();
                int jobId = Integer.parseInt(jobIdStr);
                long ident = Binder.clearCallingIdentity();
                try {
                    int ret = this.mInternal.getJobState(pw, pkgName, userId, jobId);
                    printError(ret, pkgName, userId, jobId);
                    return ret;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    private int doHeartbeat(PrintWriter pw) throws Exception {
        checkPermission("manipulate scheduler heartbeat");
        String arg = getNextArg();
        int numBeats = arg != null ? Integer.parseInt(arg) : 0;
        long ident = Binder.clearCallingIdentity();
        try {
            return this.mInternal.executeHeartbeatCommand(pw, numBeats);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int triggerDockState(PrintWriter pw) throws Exception {
        boolean idleState;
        checkPermission("trigger wireless charging dock state");
        String opt = getNextArgRequired();
        if ("idle".equals(opt)) {
            idleState = true;
        } else if ("active".equals(opt)) {
            idleState = false;
        } else {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: unknown option " + opt);
            return 1;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mInternal.triggerDockState(idleState);
            Binder.restoreCallingIdentity(ident);
            return 0;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Job scheduler (jobscheduler) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  run [-f | --force] [-u | --user USER_ID] PACKAGE JOB_ID");
        pw.println("    Trigger immediate execution of a specific scheduled job.");
        pw.println("    Options:");
        pw.println("      -f or --force: run the job even if technical constraints such as");
        pw.println("         connectivity are not currently met");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  timeout [-u | --user USER_ID] [PACKAGE] [JOB_ID]");
        pw.println("    Trigger immediate timeout of currently executing jobs, as if their.");
        pw.println("    execution timeout had expired.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         all users");
        pw.println("  cancel [-u | --user USER_ID] PACKAGE [JOB_ID]");
        pw.println("    Cancel a scheduled job.  If a job ID is not supplied, all jobs scheduled");
        pw.println("    by that package will be canceled.  USE WITH CAUTION.");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  heartbeat [num]");
        pw.println("    With no argument, prints the current standby heartbeat.  With a positive");
        pw.println("    argument, advances the standby heartbeat by that number.");
        pw.println("  monitor-battery [on|off]");
        pw.println("    Control monitoring of all battery changes.  Off by default.  Turning");
        pw.println("    on makes get-battery-seq useful.");
        pw.println("  get-battery-seq");
        pw.println("    Return the last battery update sequence number that was received.");
        pw.println("  get-battery-charging");
        pw.println("    Return whether the battery is currently considered to be charging.");
        pw.println("  get-battery-not-low");
        pw.println("    Return whether the battery is currently considered to not be low.");
        pw.println("  get-storage-seq");
        pw.println("    Return the last storage update sequence number that was received.");
        pw.println("  get-storage-not-low");
        pw.println("    Return whether storage is currently considered to not be low.");
        pw.println("  get-job-state [-u | --user USER_ID] PACKAGE JOB_ID");
        pw.println("    Return the current state of a job, may be any combination of:");
        pw.println("      pending: currently on the pending list, waiting to be active");
        pw.println("      active: job is actively running");
        pw.println("      user-stopped: job can't run because its user is stopped");
        pw.println("      backing-up: job can't run because app is currently backing up its data");
        pw.println("      no-component: job can't run because its component is not available");
        pw.println("      ready: job is ready to run (all constraints satisfied or bypassed)");
        pw.println("      waiting: if nothing else above is printed, job not ready to run");
        pw.println("    Options:");
        pw.println("      -u or --user: specify which user's job is to be run; the default is");
        pw.println("         the primary or system user");
        pw.println("  trigger-dock-state [idle|active]");
        pw.println("    Trigger wireless charging dock state.  Active by default.");
        pw.println();
    }
}
