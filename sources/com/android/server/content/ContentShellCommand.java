package com.android.server.content;

import android.content.IContentService;
import android.os.RemoteException;
import android.os.ShellCommand;
import com.android.server.backup.BackupManagerConstants;
import java.io.PrintWriter;
/* loaded from: classes.dex */
public class ContentShellCommand extends ShellCommand {
    final IContentService mInterface;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContentShellCommand(IContentService service) {
        this.mInterface = service;
    }

    /* JADX WARN: Removed duplicated region for block: B:14:0x0024 A[Catch: RemoteException -> 0x002e, TryCatch #0 {RemoteException -> 0x002e, blocks: (B:6:0x000c, B:14:0x0024, B:16:0x0029, B:9:0x0016), top: B:21:0x000c }] */
    /* JADX WARN: Removed duplicated region for block: B:16:0x0029 A[Catch: RemoteException -> 0x002e, TRY_LEAVE, TryCatch #0 {RemoteException -> 0x002e, blocks: (B:6:0x000c, B:14:0x0024, B:16:0x0029, B:9:0x0016), top: B:21:0x000c }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
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
            int r2 = r6.hashCode()     // Catch: android.os.RemoteException -> L2e
            r3 = -796331115(0xffffffffd088f395, float:-1.8381318E10)
            if (r2 == r3) goto L16
            goto L21
        L16:
            java.lang.String r2 = "reset-today-stats"
            boolean r2 = r6.equals(r2)     // Catch: android.os.RemoteException -> L2e
            if (r2 == 0) goto L21
            r2 = 0
            goto L22
        L21:
            r2 = r1
        L22:
            if (r2 == 0) goto L29
            int r2 = r5.handleDefaultCommands(r6)     // Catch: android.os.RemoteException -> L2e
            return r2
        L29:
            int r2 = r5.runResetTodayStats()     // Catch: android.os.RemoteException -> L2e
            return r2
        L2e:
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
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.content.ContentShellCommand.onCommand(java.lang.String):int");
    }

    private int runResetTodayStats() throws RemoteException {
        this.mInterface.resetTodayStats();
        return 0;
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Content service commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        pw.println("  reset-today-stats");
        pw.println("    Reset 1-day sync stats.");
        pw.println();
    }
}
