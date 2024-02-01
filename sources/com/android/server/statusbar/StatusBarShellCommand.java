package com.android.server.statusbar;

import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.service.quicksettings.TileService;
import java.io.PrintWriter;

/* loaded from: classes2.dex */
public class StatusBarShellCommand extends ShellCommand {
    private static final IBinder sToken = new StatusBarShellCommandToken();
    private final Context mContext;
    private final StatusBarManagerService mInterface;

    public StatusBarShellCommand(StatusBarManagerService service, Context context) {
        this.mInterface = service;
        this.mContext = context;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public int onCommand(String cmd) {
        char c;
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        try {
            switch (cmd.hashCode()) {
                case -1282000806:
                    if (cmd.equals("add-tile")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case -1239176554:
                    if (cmd.equals("get-status-icons")) {
                        c = 7;
                        break;
                    }
                    c = 65535;
                    break;
                case -1052548778:
                    if (cmd.equals("send-disable-flag")) {
                        c = '\t';
                        break;
                    }
                    c = 65535;
                    break;
                case -823073837:
                    if (cmd.equals("click-tile")) {
                        c = 5;
                        break;
                    }
                    c = 65535;
                    break;
                case -632085587:
                    if (cmd.equals("collapse")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case -339726761:
                    if (cmd.equals("remove-tile")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case 901899220:
                    if (cmd.equals("disable-for-setup")) {
                        c = '\b';
                        break;
                    }
                    c = 65535;
                    break;
                case 1612300298:
                    if (cmd.equals("check-support")) {
                        c = 6;
                        break;
                    }
                    c = 65535;
                    break;
                case 1629310709:
                    if (cmd.equals("expand-notifications")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 1672031734:
                    if (cmd.equals("expand-settings")) {
                        c = 1;
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
                    return runExpandNotifications();
                case 1:
                    return runExpandSettings();
                case 2:
                    return runCollapse();
                case 3:
                    return runAddTile();
                case 4:
                    return runRemoveTile();
                case 5:
                    return runClickTile();
                case 6:
                    PrintWriter pw = getOutPrintWriter();
                    pw.println(String.valueOf(TileService.isQuickSettingsSupported()));
                    return 0;
                case 7:
                    return runGetStatusIcons();
                case '\b':
                    return runDisableForSetup();
                case '\t':
                    return runSendDisableFlag();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            PrintWriter pw2 = getOutPrintWriter();
            pw2.println("Remote exception: " + e);
            return -1;
        }
    }

    private int runAddTile() throws RemoteException {
        this.mInterface.addTile(ComponentName.unflattenFromString(getNextArgRequired()));
        return 0;
    }

    private int runRemoveTile() throws RemoteException {
        this.mInterface.remTile(ComponentName.unflattenFromString(getNextArgRequired()));
        return 0;
    }

    private int runClickTile() throws RemoteException {
        this.mInterface.clickTile(ComponentName.unflattenFromString(getNextArgRequired()));
        return 0;
    }

    private int runCollapse() throws RemoteException {
        this.mInterface.collapsePanels();
        return 0;
    }

    private int runExpandSettings() throws RemoteException {
        this.mInterface.expandSettingsPanel(null);
        return 0;
    }

    private int runExpandNotifications() throws RemoteException {
        this.mInterface.expandNotificationsPanel();
        return 0;
    }

    private int runGetStatusIcons() {
        String[] statusBarIcons;
        PrintWriter pw = getOutPrintWriter();
        for (String icon : this.mInterface.getStatusBarIcons()) {
            pw.println(icon);
        }
        return 0;
    }

    private int runDisableForSetup() {
        String arg = getNextArgRequired();
        String pkg = this.mContext.getPackageName();
        boolean disable = Boolean.parseBoolean(arg);
        if (!disable) {
            this.mInterface.disable(0, sToken, pkg);
            this.mInterface.disable2(0, sToken, pkg);
        } else {
            this.mInterface.disable(61145088, sToken, pkg);
            this.mInterface.disable2(16, sToken, pkg);
        }
        return 0;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x0060, code lost:
        if (r4.equals("search") != false) goto L9;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int runSendDisableFlag() {
        /*
            r10 = this;
            android.content.Context r0 = r10.mContext
            java.lang.String r0 = r0.getPackageName()
            r1 = 0
            r2 = 0
            android.app.StatusBarManager$DisableInfo r3 = new android.app.StatusBarManager$DisableInfo
            r3.<init>()
            java.lang.String r4 = r10.getNextArg()
        L11:
            r5 = 0
            if (r4 == 0) goto L98
            r6 = -1
            int r7 = r4.hashCode()
            r8 = 1
            switch(r7) {
                case -1786496516: goto L63;
                case -906336856: goto L5a;
                case -755976775: goto L50;
                case 3208415: goto L46;
                case 94755854: goto L3c;
                case 1011652819: goto L32;
                case 1082295672: goto L28;
                case 1368216504: goto L1e;
                default: goto L1d;
            }
        L1d:
            goto L6d
        L1e:
            java.lang.String r5 = "notification-icons"
            boolean r5 = r4.equals(r5)
            if (r5 == 0) goto L1d
            r5 = 7
            goto L6e
        L28:
            java.lang.String r5 = "recents"
            boolean r5 = r4.equals(r5)
            if (r5 == 0) goto L1d
            r5 = 2
            goto L6e
        L32:
            java.lang.String r5 = "statusbar-expansion"
            boolean r5 = r4.equals(r5)
            if (r5 == 0) goto L1d
            r5 = 4
            goto L6e
        L3c:
            java.lang.String r5 = "clock"
            boolean r5 = r4.equals(r5)
            if (r5 == 0) goto L1d
            r5 = 6
            goto L6e
        L46:
            java.lang.String r5 = "home"
            boolean r5 = r4.equals(r5)
            if (r5 == 0) goto L1d
            r5 = r8
            goto L6e
        L50:
            java.lang.String r5 = "notification-alerts"
            boolean r5 = r4.equals(r5)
            if (r5 == 0) goto L1d
            r5 = 3
            goto L6e
        L5a:
            java.lang.String r7 = "search"
            boolean r7 = r4.equals(r7)
            if (r7 == 0) goto L1d
            goto L6e
        L63:
            java.lang.String r5 = "system-icons"
            boolean r5 = r4.equals(r5)
            if (r5 == 0) goto L1d
            r5 = 5
            goto L6e
        L6d:
            r5 = r6
        L6e:
            switch(r5) {
                case 0: goto L8e;
                case 1: goto L8a;
                case 2: goto L86;
                case 3: goto L82;
                case 4: goto L7e;
                case 5: goto L7a;
                case 6: goto L76;
                case 7: goto L72;
                default: goto L71;
            }
        L71:
            goto L92
        L72:
            r3.setNotificationIconsDisabled(r8)
            goto L92
        L76:
            r3.setClockDisabled(r8)
            goto L92
        L7a:
            r3.setSystemIconsDisabled(r8)
            goto L92
        L7e:
            r3.setStatusBarExpansionDisabled(r8)
            goto L92
        L82:
            r3.setNotificationPeekingDisabled(r8)
            goto L92
        L86:
            r3.setRecentsDisabled(r8)
            goto L92
        L8a:
            r3.setNagivationHomeDisabled(r8)
            goto L92
        L8e:
            r3.setSearchDisabled(r8)
        L92:
            java.lang.String r4 = r10.getNextArg()
            goto L11
        L98:
            android.util.Pair r6 = r3.toFlags()
            com.android.server.statusbar.StatusBarManagerService r7 = r10.mInterface
            java.lang.Object r8 = r6.first
            java.lang.Integer r8 = (java.lang.Integer) r8
            int r8 = r8.intValue()
            android.os.IBinder r9 = com.android.server.statusbar.StatusBarShellCommand.sToken
            r7.disable(r8, r9, r0)
            com.android.server.statusbar.StatusBarManagerService r7 = r10.mInterface
            java.lang.Object r8 = r6.second
            java.lang.Integer r8 = (java.lang.Integer) r8
            int r8 = r8.intValue()
            android.os.IBinder r9 = com.android.server.statusbar.StatusBarShellCommand.sToken
            r7.disable2(r8, r9, r0)
            return r5
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.statusbar.StatusBarShellCommand.runSendDisableFlag():int");
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Status bar commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  expand-notifications");
        pw.println("    Open the notifications panel.");
        pw.println("");
        pw.println("  expand-settings");
        pw.println("    Open the notifications panel and expand quick settings if present.");
        pw.println("");
        pw.println("  collapse");
        pw.println("    Collapse the notifications and settings panel.");
        pw.println("");
        pw.println("  add-tile COMPONENT");
        pw.println("    Add a TileService of the specified component");
        pw.println("");
        pw.println("  remove-tile COMPONENT");
        pw.println("    Remove a TileService of the specified component");
        pw.println("");
        pw.println("  click-tile COMPONENT");
        pw.println("    Click on a TileService of the specified component");
        pw.println("");
        pw.println("  check-support");
        pw.println("    Check if this device supports QS + APIs");
        pw.println("");
        pw.println("  get-status-icons");
        pw.println("    Print the list of status bar icons and the order they appear in");
        pw.println("");
        pw.println("  disable-for-setup DISABLE");
        pw.println("    If true, disable status bar components unsuitable for device setup");
        pw.println("");
        pw.println("  send-disable-flag FLAG...");
        pw.println("    Send zero or more disable flags (parsed individually) to StatusBarManager");
        pw.println("    Valid options:");
        pw.println("        <blank>             - equivalent to \"none\"");
        pw.println("        none                - re-enables all components");
        pw.println("        search              - disable search");
        pw.println("        home                - disable naviagation home");
        pw.println("        recents             - disable recents/overview");
        pw.println("        notification-peek   - disable notification peeking");
        pw.println("        statusbar-expansion - disable status bar expansion");
        pw.println("        system-icons        - disable system icons appearing in status bar");
        pw.println("        clock               - disable clock appearing in status bar");
        pw.println("        notification-icons  - disable notification icons from status bar");
        pw.println("");
    }

    /* loaded from: classes2.dex */
    private static final class StatusBarShellCommandToken extends Binder {
        private StatusBarShellCommandToken() {
        }
    }
}
