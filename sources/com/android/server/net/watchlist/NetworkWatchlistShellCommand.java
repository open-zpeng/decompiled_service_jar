package com.android.server.net.watchlist;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.provider.Settings;
import com.android.server.wm.ActivityTaskManagerService;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;

/* loaded from: classes.dex */
class NetworkWatchlistShellCommand extends ShellCommand {
    final Context mContext;
    final NetworkWatchlistService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public NetworkWatchlistShellCommand(NetworkWatchlistService service, Context context) {
        this.mContext = context;
        this.mService = service;
    }

    /* JADX WARN: Removed duplicated region for block: B:19:0x0034  */
    /* JADX WARN: Removed duplicated region for block: B:24:0x0040 A[Catch: Exception -> 0x0045, TRY_LEAVE, TryCatch #0 {Exception -> 0x0045, blocks: (B:6:0x000c, B:20:0x0036, B:22:0x003b, B:24:0x0040, B:11:0x001c, B:14:0x0026), top: B:29:0x000c }] */
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
            int r2 = r6.hashCode()     // Catch: java.lang.Exception -> L45
            r3 = 1757613042(0x68c30bf2, float:7.3686545E24)
            r4 = 1
            if (r2 == r3) goto L26
            r3 = 1854202282(0x6e84e1aa, float:2.0562416E28)
            if (r2 == r3) goto L1c
        L1b:
            goto L31
        L1c:
            java.lang.String r2 = "force-generate-report"
            boolean r2 = r6.equals(r2)     // Catch: java.lang.Exception -> L45
            if (r2 == 0) goto L1b
            r2 = r4
            goto L32
        L26:
            java.lang.String r2 = "set-test-config"
            boolean r2 = r6.equals(r2)     // Catch: java.lang.Exception -> L45
            if (r2 == 0) goto L1b
            r2 = 0
            goto L32
        L31:
            r2 = r1
        L32:
            if (r2 == 0) goto L40
            if (r2 == r4) goto L3b
            int r1 = r5.handleDefaultCommands(r6)     // Catch: java.lang.Exception -> L45
            return r1
        L3b:
            int r1 = r5.runForceGenerateReport()     // Catch: java.lang.Exception -> L45
            return r1
        L40:
            int r1 = r5.runSetTestConfig()     // Catch: java.lang.Exception -> L45
            return r1
        L45:
            r2 = move-exception
            java.lang.StringBuilder r3 = new java.lang.StringBuilder
            r3.<init>()
            java.lang.String r4 = "Exception: "
            r3.append(r4)
            r3.append(r2)
            java.lang.String r3 = r3.toString()
            r0.println(r3)
            return r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.net.watchlist.NetworkWatchlistShellCommand.onCommand(java.lang.String):int");
    }

    private int runSetTestConfig() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        try {
            String configXmlPath = getNextArgRequired();
            ParcelFileDescriptor pfd = openFileForSystem(configXmlPath, ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD);
            if (pfd != null) {
                InputStream fileStream = new FileInputStream(pfd.getFileDescriptor());
                WatchlistConfig.getInstance().setTestMode(fileStream);
            }
            pw.println("Success!");
            return 0;
        } catch (Exception ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }
    }

    private int runForceGenerateReport() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        long ident = Binder.clearCallingIdentity();
        try {
            if (WatchlistConfig.getInstance().isConfigSecure()) {
                pw.println("Error: Cannot force generate report under production config");
                return -1;
            }
            Settings.Global.putLong(this.mContext.getContentResolver(), "network_watchlist_last_report_time", 0L);
            this.mService.forceReportWatchlistForTest(System.currentTimeMillis());
            pw.println("Success!");
            Binder.restoreCallingIdentity(ident);
            return 0;
        } catch (Exception ex) {
            pw.println("Error: " + ex);
            return -1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Network watchlist manager commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-test-config your_watchlist_config.xml");
        pw.println("    Set network watchlist test config file.");
        pw.println("  force-generate-report");
        pw.println("    Force generate watchlist test report.");
    }
}
