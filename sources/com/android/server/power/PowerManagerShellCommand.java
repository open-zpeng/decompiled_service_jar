package com.android.server.power;

import android.content.Intent;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ShellCommand;
import java.io.PrintWriter;

/* loaded from: classes.dex */
class PowerManagerShellCommand extends ShellCommand {
    private static final int LOW_POWER_MODE_ON = 1;
    final IPowerManager mInterface;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PowerManagerShellCommand(IPowerManager service) {
        this.mInterface = service;
    }

    /* JADX WARN: Removed duplicated region for block: B:19:0x0035  */
    /* JADX WARN: Removed duplicated region for block: B:24:0x0041 A[Catch: RemoteException -> 0x0046, TRY_LEAVE, TryCatch #0 {RemoteException -> 0x0046, blocks: (B:6:0x000c, B:20:0x0037, B:22:0x003c, B:24:0x0041, B:11:0x001c, B:14:0x0027), top: B:29:0x000c }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int onCommand(java.lang.String r6) {
        /*
            r5 = this;
            if (r6 != 0) goto L7
            int r0 = r5.handleDefaultCommands(r6)
            return r0
        L7:
            java.io.PrintWriter r0 = r5.getOutPrintWriter()
            r1 = -1
            int r2 = r6.hashCode()     // Catch: android.os.RemoteException -> L46
            r3 = -531688203(0xffffffffe04f14f5, float:-5.9687283E19)
            r4 = 1
            if (r2 == r3) goto L27
            r3 = 1369181230(0x519c0c2e, float:8.3777405E10)
            if (r2 == r3) goto L1c
        L1b:
            goto L32
        L1c:
            java.lang.String r2 = "set-mode"
            boolean r2 = r6.equals(r2)     // Catch: android.os.RemoteException -> L46
            if (r2 == 0) goto L1b
            r2 = r4
            goto L33
        L27:
            java.lang.String r2 = "set-adaptive-power-saver-enabled"
            boolean r2 = r6.equals(r2)     // Catch: android.os.RemoteException -> L46
            if (r2 == 0) goto L1b
            r2 = 0
            goto L33
        L32:
            r2 = r1
        L33:
            if (r2 == 0) goto L41
            if (r2 == r4) goto L3c
            int r1 = r5.handleDefaultCommands(r6)     // Catch: android.os.RemoteException -> L46
            return r1
        L3c:
            int r1 = r5.runSetMode()     // Catch: android.os.RemoteException -> L46
            return r1
        L41:
            int r1 = r5.runSetAdaptiveEnabled()     // Catch: android.os.RemoteException -> L46
            return r1
        L46:
            r2 = move-exception
            java.lang.StringBuilder r3 = new java.lang.StringBuilder
            r3.<init>()
            java.lang.String r4 = "Remote exception: "
            r3.append(r4)
            r3.append(r2)
            java.lang.String r3 = r3.toString()
            r0.println(r3)
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.power.PowerManagerShellCommand.onCommand(java.lang.String):int");
    }

    private int runSetAdaptiveEnabled() throws RemoteException {
        this.mInterface.setAdaptivePowerSaveEnabled(Boolean.parseBoolean(getNextArgRequired()));
        return 0;
    }

    private int runSetMode() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        try {
            int mode = Integer.parseInt(getNextArgRequired());
            this.mInterface.setPowerSaveModeEnabled(mode == 1);
            return 0;
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Power manager (power) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  set-adaptive-power-saver-enabled [true|false]");
        pw.println("    enables or disables adaptive power saver.");
        pw.println("  set-mode MODE");
        pw.println("    sets the power mode of the device to MODE.");
        pw.println("    1 turns low power mode on and 0 turns low power mode off.");
        pw.println();
        Intent.printIntentArgsHelp(pw, "");
    }
}
