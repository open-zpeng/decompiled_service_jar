package com.android.server.appprediction;

import android.os.ShellCommand;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public class AppPredictionManagerServiceShellCommand extends ShellCommand {
    private static final String TAG = AppPredictionManagerServiceShellCommand.class.getSimpleName();
    private final AppPredictionManagerService mService;

    public AppPredictionManagerServiceShellCommand(AppPredictionManagerService service) {
        this.mService = service;
    }

    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter pw = getOutPrintWriter();
        char c = 65535;
        if (!((cmd.hashCode() == 113762 && cmd.equals("set")) ? false : true)) {
            String what = getNextArgRequired();
            if (what.hashCode() == 2003978041 && what.equals("temporary-service")) {
                c = 0;
            }
            if (c == 0) {
                int userId = Integer.parseInt(getNextArgRequired());
                String serviceName = getNextArg();
                if (serviceName == null) {
                    this.mService.resetTemporaryService(userId);
                    return 0;
                }
                int duration = Integer.parseInt(getNextArgRequired());
                this.mService.setTemporaryService(userId, serviceName, duration);
                pw.println("AppPredictionService temporarily set to " + serviceName + " for " + duration + "ms");
            }
            return 0;
        }
        return handleDefaultCommands(cmd);
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        try {
            pw.println("AppPredictionManagerService commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  set temporary-service USER_ID [COMPONENT_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the service implemtation.");
            pw.println("    To reset, call with just the USER_ID argument.");
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
}
