package com.android.server.job;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.ShellCommand;
import android.os.UserHandle;
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

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public int onCommand(String cmd) {
        char c;
        PrintWriter pw = getOutPrintWriter();
        String str = cmd != null ? cmd : "";
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
        boolean z;
        checkPermission("force scheduled jobs");
        boolean force = false;
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                int hashCode = opt.hashCode();
                if (hashCode == -1626076853) {
                    if (opt.equals("--force")) {
                        z = true;
                    }
                    z = true;
                } else if (hashCode == 1497) {
                    if (opt.equals("-f")) {
                        z = false;
                    }
                    z = true;
                } else if (hashCode != 1512) {
                    if (hashCode == 1333469547 && opt.equals("--user")) {
                        z = true;
                    }
                    z = true;
                } else {
                    if (opt.equals("-u")) {
                        z = true;
                    }
                    z = true;
                }
                if (!z || z) {
                    force = true;
                } else if (z || z) {
                    userId = Integer.parseInt(getNextArgRequired());
                } else {
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
        if (r2.equals("-u") != false) goto L11;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int timeout(java.io.PrintWriter r19) throws java.lang.Exception {
        /*
            r18 = this;
            r1 = r18
            java.lang.String r0 = "force timeout jobs"
            r1.checkPermission(r0)
            r0 = -1
        L8:
            java.lang.String r2 = r18.getNextOption()
            r3 = r2
            r4 = 0
            r5 = 1
            r6 = -1
            if (r2 == 0) goto L60
            int r2 = r3.hashCode()
            r7 = 1512(0x5e8, float:2.119E-42)
            if (r2 == r7) goto L2a
            r4 = 1333469547(0x4f7b216b, float:4.2132713E9)
            if (r2 == r4) goto L20
        L1f:
            goto L33
        L20:
            java.lang.String r2 = "--user"
            boolean r2 = r3.equals(r2)
            if (r2 == 0) goto L1f
            r4 = r5
            goto L34
        L2a:
            java.lang.String r2 = "-u"
            boolean r2 = r3.equals(r2)
            if (r2 == 0) goto L1f
            goto L34
        L33:
            r4 = r6
        L34:
            if (r4 == 0) goto L54
            if (r4 == r5) goto L54
            java.lang.StringBuilder r2 = new java.lang.StringBuilder
            r2.<init>()
            java.lang.String r4 = "Error: unknown option '"
            r2.append(r4)
            r2.append(r3)
            java.lang.String r4 = "'"
            r2.append(r4)
            java.lang.String r2 = r2.toString()
            r13 = r19
            r13.println(r2)
            return r6
        L54:
            r13 = r19
            java.lang.String r2 = r18.getNextArgRequired()
            int r0 = android.os.UserHandle.parseUserArg(r2)
            goto L8
        L60:
            r13 = r19
            r2 = -2
            if (r0 != r2) goto L6b
            int r0 = android.app.ActivityManager.getCurrentUser()
            r2 = r0
            goto L6c
        L6b:
            r2 = r0
        L6c:
            java.lang.String r14 = r18.getNextArg()
            java.lang.String r15 = r18.getNextArg()
            if (r15 == 0) goto L7a
            int r6 = java.lang.Integer.parseInt(r15)
        L7a:
            r12 = r6
            long r16 = android.os.Binder.clearCallingIdentity()
            com.android.server.job.JobSchedulerService r7 = r1.mInternal     // Catch: java.lang.Throwable -> L92
            if (r15 == 0) goto L85
            r11 = r5
            goto L86
        L85:
            r11 = r4
        L86:
            r8 = r19
            r9 = r14
            r10 = r2
            int r0 = r7.executeTimeoutCommand(r8, r9, r10, r11, r12)     // Catch: java.lang.Throwable -> L92
            android.os.Binder.restoreCallingIdentity(r16)
            return r0
        L92:
            r0 = move-exception
            android.os.Binder.restoreCallingIdentity(r16)
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.job.JobSchedulerShellCommand.timeout(java.io.PrintWriter):int");
    }

    /* JADX WARN: Code restructure failed: missing block: B:14:0x002e, code lost:
        if (r1.equals("-u") != false) goto L11;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int cancelJob(java.io.PrintWriter r13) throws java.lang.Exception {
        /*
            r12 = this;
            java.lang.String r0 = "cancel jobs"
            r12.checkPermission(r0)
            r0 = 0
        L6:
            java.lang.String r1 = r12.getNextOption()
            r7 = r1
            r2 = 0
            r3 = 1
            r4 = -1
            if (r1 == 0) goto L5a
            int r1 = r7.hashCode()
            r5 = 1512(0x5e8, float:2.119E-42)
            if (r1 == r5) goto L28
            r2 = 1333469547(0x4f7b216b, float:4.2132713E9)
            if (r1 == r2) goto L1e
        L1d:
            goto L31
        L1e:
            java.lang.String r1 = "--user"
            boolean r1 = r7.equals(r1)
            if (r1 == 0) goto L1d
            r2 = r3
            goto L32
        L28:
            java.lang.String r1 = "-u"
            boolean r1 = r7.equals(r1)
            if (r1 == 0) goto L1d
            goto L32
        L31:
            r2 = r4
        L32:
            if (r2 == 0) goto L50
            if (r2 == r3) goto L50
            java.lang.StringBuilder r1 = new java.lang.StringBuilder
            r1.<init>()
            java.lang.String r2 = "Error: unknown option '"
            r1.append(r2)
            r1.append(r7)
            java.lang.String r2 = "'"
            r1.append(r2)
            java.lang.String r1 = r1.toString()
            r13.println(r1)
            return r4
        L50:
            java.lang.String r1 = r12.getNextArgRequired()
            int r0 = android.os.UserHandle.parseUserArg(r1)
            goto L6
        L5a:
            if (r0 >= 0) goto L62
            java.lang.String r1 = "Error: must specify a concrete user ID"
            r13.println(r1)
            return r4
        L62:
            java.lang.String r8 = r12.getNextArg()
            java.lang.String r9 = r12.getNextArg()
            if (r9 == 0) goto L72
            int r1 = java.lang.Integer.parseInt(r9)
            r6 = r1
            goto L73
        L72:
            r6 = r4
        L73:
            long r10 = android.os.Binder.clearCallingIdentity()
            com.android.server.job.JobSchedulerService r1 = r12.mInternal     // Catch: java.lang.Throwable -> L89
            if (r9 == 0) goto L7d
            r5 = r3
            goto L7e
        L7d:
            r5 = r2
        L7e:
            r2 = r13
            r3 = r8
            r4 = r0
            int r1 = r1.executeCancelCommand(r2, r3, r4, r5, r6)     // Catch: java.lang.Throwable -> L89
            android.os.Binder.restoreCallingIdentity(r10)
            return r1
        L89:
            r1 = move-exception
            android.os.Binder.restoreCallingIdentity(r10)
            throw r1
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
                if (!z || z) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
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
