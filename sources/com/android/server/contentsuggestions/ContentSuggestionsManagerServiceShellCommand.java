package com.android.server.contentsuggestions;

import android.os.ShellCommand;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public class ContentSuggestionsManagerServiceShellCommand extends ShellCommand {
    private static final String TAG = ContentSuggestionsManagerServiceShellCommand.class.getSimpleName();
    private final ContentSuggestionsManagerService mService;

    public ContentSuggestionsManagerServiceShellCommand(ContentSuggestionsManagerService service) {
        this.mService = service;
    }

    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter pw = getOutPrintWriter();
        char c = 65535;
        int hashCode = cmd.hashCode();
        if (hashCode != 102230) {
            if (hashCode == 113762 && cmd.equals("set")) {
                c = 0;
            }
        } else if (cmd.equals("get")) {
            c = 1;
        }
        if (c != 0) {
            if (c == 1) {
                return requestGet(pw);
            }
            return handleDefaultCommands(cmd);
        }
        return requestSet(pw);
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        try {
            pw.println("ContentSuggestionsManagerService commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  set temporary-service USER_ID [COMPONENT_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the service implementation.");
            pw.println("    To reset, call with just the USER_ID argument.");
            pw.println("");
            pw.println("  set default-service-enabled USER_ID [true|false]");
            pw.println("    Enable / disable the default service for the user.");
            pw.println("");
            pw.println("  get default-service-enabled USER_ID");
            pw.println("    Checks whether the default service is enabled for the user.");
            pw.println("");
            pw.close();
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (pw != null) {
                    try {
                        pw.close();
                    } catch (Throwable th3) {
                        th.addSuppressed(th3);
                    }
                }
                throw th2;
            }
        }
    }

    private int requestSet(PrintWriter pw) {
        boolean z;
        String what = getNextArgRequired();
        int hashCode = what.hashCode();
        if (hashCode != 529654941) {
            if (hashCode == 2003978041 && what.equals("temporary-service")) {
                z = false;
            }
            z = true;
        } else {
            if (what.equals("default-service-enabled")) {
                z = true;
            }
            z = true;
        }
        if (z) {
            if (z) {
                return setDefaultServiceEnabled();
            }
            pw.println("Invalid set: " + what);
            return -1;
        }
        return setTemporaryService(pw);
    }

    private int requestGet(PrintWriter pw) {
        String what = getNextArgRequired();
        if (!((what.hashCode() == 529654941 && what.equals("default-service-enabled")) ? false : true)) {
            return getDefaultServiceEnabled(pw);
        }
        pw.println("Invalid get: " + what);
        return -1;
    }

    private int setTemporaryService(PrintWriter pw) {
        int userId = Integer.parseInt(getNextArgRequired());
        String serviceName = getNextArg();
        if (serviceName == null) {
            this.mService.resetTemporaryService(userId);
            return 0;
        }
        int duration = Integer.parseInt(getNextArgRequired());
        this.mService.setTemporaryService(userId, serviceName, duration);
        pw.println("ContentSuggestionsService temporarily set to " + serviceName + " for " + duration + "ms");
        return 0;
    }

    private int setDefaultServiceEnabled() {
        int userId = getNextIntArgRequired();
        boolean enabled = Boolean.parseBoolean(getNextArg());
        this.mService.setDefaultServiceEnabled(userId, enabled);
        return 0;
    }

    private int getDefaultServiceEnabled(PrintWriter pw) {
        int userId = getNextIntArgRequired();
        boolean enabled = this.mService.isDefaultServiceEnabled(userId);
        pw.println(enabled);
        return 0;
    }

    private int getNextIntArgRequired() {
        return Integer.parseInt(getNextArgRequired());
    }
}
