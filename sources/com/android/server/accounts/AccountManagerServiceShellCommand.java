package com.android.server.accounts;

import android.app.ActivityManager;
import android.os.ShellCommand;
import android.os.UserHandle;
import java.io.PrintWriter;

/* loaded from: classes.dex */
final class AccountManagerServiceShellCommand extends ShellCommand {
    final AccountManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public AccountManagerServiceShellCommand(AccountManagerService service) {
        this.mService = service;
    }

    public int onCommand(String cmd) {
        boolean z;
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        int hashCode = cmd.hashCode();
        if (hashCode != -859068373) {
            if (hashCode == 789489311 && cmd.equals("set-bind-instant-service-allowed")) {
                z = true;
            }
            z = true;
        } else {
            if (cmd.equals("get-bind-instant-service-allowed")) {
                z = false;
            }
            z = true;
        }
        if (z) {
            if (!z) {
                return -1;
            }
            return runSetBindInstantServiceAllowed();
        }
        return runGetBindInstantServiceAllowed();
    }

    private int runGetBindInstantServiceAllowed() {
        Integer userId = parseUserId();
        if (userId == null) {
            return -1;
        }
        getOutPrintWriter().println(Boolean.toString(this.mService.getBindInstantServiceAllowed(userId.intValue())));
        return 0;
    }

    private int runSetBindInstantServiceAllowed() {
        Integer userId = parseUserId();
        if (userId == null) {
            return -1;
        }
        String allowed = getNextArgRequired();
        if (allowed == null) {
            getErrPrintWriter().println("Error: no true/false specified");
            return -1;
        }
        this.mService.setBindInstantServiceAllowed(userId.intValue(), Boolean.parseBoolean(allowed));
        return 0;
    }

    private Integer parseUserId() {
        String option = getNextOption();
        if (option != null) {
            if (option.equals("--user")) {
                return Integer.valueOf(UserHandle.parseUserArg(getNextArgRequired()));
            }
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Unknown option: " + option);
            return null;
        }
        return Integer.valueOf(ActivityManager.getCurrentUser());
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Account manager service commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-bind-instant-service-allowed [--user <USER_ID> (current user if not specified)] true|false ");
        pw.println("    Set whether binding to services provided by instant apps is allowed.");
        pw.println("  get-bind-instant-service-allowed [--user <USER_ID> (current user if not specified)]");
        pw.println("    Get whether binding to services provided by instant apps is allowed.");
    }
}
