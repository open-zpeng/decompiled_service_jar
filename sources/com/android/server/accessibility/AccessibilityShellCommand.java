package com.android.server.accessibility;

import android.os.ShellCommand;
import android.os.UserHandle;
import java.io.PrintWriter;
/* loaded from: classes.dex */
final class AccessibilityShellCommand extends ShellCommand {
    final AccessibilityManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public AccessibilityShellCommand(AccessibilityManagerService service) {
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
        switch (z) {
            case false:
                return runGetBindInstantServiceAllowed();
            case true:
                return runSetBindInstantServiceAllowed();
            default:
                return -1;
        }
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
        return 0;
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Accessibility service (accessibility) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-bind-instant-service-allowed [--user <USER_ID>] true|false ");
        pw.println("    Set whether binding to services provided by instant apps is allowed.");
        pw.println("  get-bind-instant-service-allowed [--user <USER_ID>]");
        pw.println("    Get whether binding to services provided by instant apps is allowed.");
    }
}
