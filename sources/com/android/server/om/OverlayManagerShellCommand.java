package com.android.server.om;

import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
/* loaded from: classes.dex */
final class OverlayManagerShellCommand extends ShellCommand {
    private final IOverlayManager mInterface;

    /* JADX INFO: Access modifiers changed from: package-private */
    public OverlayManagerShellCommand(IOverlayManager iom) {
        this.mInterface = iom;
    }

    public int onCommand(String cmd) {
        char c;
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter err = getErrPrintWriter();
        try {
            switch (cmd.hashCode()) {
                case -1361113425:
                    if (cmd.equals("set-priority")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case -1298848381:
                    if (cmd.equals(xpInputManagerService.InputPolicyKey.KEY_ENABLE)) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case -794624300:
                    if (cmd.equals("enable-exclusive")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case 3322014:
                    if (cmd.equals("list")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 1671308008:
                    if (cmd.equals("disable")) {
                        c = 2;
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
                    return runList();
                case 1:
                    return runEnableDisable(true);
                case 2:
                    return runEnableDisable(false);
                case 3:
                    return runEnableExclusive();
                case 4:
                    return runSetPriority();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            err.println("Remote exception: " + e);
            return -1;
        } catch (IllegalArgumentException e2) {
            err.println("Error: " + e2.getMessage());
            return -1;
        }
    }

    public void onHelp() {
        PrintWriter out = getOutPrintWriter();
        out.println("Overlay manager (overlay) commands:");
        out.println("  help");
        out.println("    Print this help text.");
        out.println("  dump [--verbose] [--user USER_ID] [PACKAGE [PACKAGE [...]]]");
        out.println("    Print debugging information about the overlay manager.");
        out.println("  list [--user USER_ID] [PACKAGE [PACKAGE [...]]]");
        out.println("    Print information about target and overlay packages.");
        out.println("    Overlay packages are printed in priority order. With optional");
        out.println("    parameters PACKAGEs, limit output to the specified packages");
        out.println("    but include more information about each package.");
        out.println("  enable [--user USER_ID] PACKAGE");
        out.println("    Enable overlay package PACKAGE.");
        out.println("  disable [--user USER_ID] PACKAGE");
        out.println("    Disable overlay package PACKAGE.");
        out.println("  enable-exclusive [--user USER_ID] [--category] PACKAGE");
        out.println("    Enable overlay package PACKAGE and disable all other overlays for");
        out.println("    its target package. If the --category option is given, only disables");
        out.println("    other overlays in the same category.");
        out.println("  set-priority [--user USER_ID] PACKAGE PARENT|lowest|highest");
        out.println("    Change the priority of the overlay PACKAGE to be just higher than");
        out.println("    the priority of PACKAGE_PARENT If PARENT is the special keyword");
        out.println("    'lowest', change priority of PACKAGE to the lowest priority.");
        out.println("    If PARENT is the special keyword 'highest', change priority of");
        out.println("    PACKAGE to the highest priority.");
    }

    private int runList() throws RemoteException {
        String status;
        PrintWriter out = getOutPrintWriter();
        PrintWriter err = getErrPrintWriter();
        int i = 0;
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            boolean z = true;
            if (opt != null) {
                char c = 65535;
                if (opt.hashCode() == 1333469547 && opt.equals("--user")) {
                    c = 0;
                }
                if (c != 0) {
                    err.println("Error: Unknown option: " + opt);
                    return 1;
                }
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                Map<String, List<OverlayInfo>> allOverlays = this.mInterface.getAllOverlays(userId);
                for (String targetPackageName : allOverlays.keySet()) {
                    out.println(targetPackageName);
                    List<OverlayInfo> overlaysForTarget = allOverlays.get(targetPackageName);
                    int N = overlaysForTarget.size();
                    int i2 = i;
                    while (i2 < N) {
                        OverlayInfo oi = overlaysForTarget.get(i2);
                        int i3 = oi.state;
                        if (i3 != 6) {
                            switch (i3) {
                                case 2:
                                    status = "[ ]";
                                    break;
                                case 3:
                                    break;
                                default:
                                    status = "---";
                                    break;
                            }
                            Object[] objArr = new Object[2];
                            objArr[i] = status;
                            objArr[1] = oi.packageName;
                            out.println(String.format("%s %s", objArr));
                            i2++;
                            z = true;
                            i = 0;
                        }
                        status = "[x]";
                        Object[] objArr2 = new Object[2];
                        objArr2[i] = status;
                        objArr2[1] = oi.packageName;
                        out.println(String.format("%s %s", objArr2));
                        i2++;
                        z = true;
                        i = 0;
                    }
                    out.println();
                    i = 0;
                }
                return 0;
            }
        }
    }

    private int runEnableDisable(boolean enable) throws RemoteException {
        PrintWriter err = getErrPrintWriter();
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                char c = 65535;
                if (opt.hashCode() == 1333469547 && opt.equals("--user")) {
                    c = 0;
                }
                if (c == 0) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return 1;
                }
            } else {
                String packageName = getNextArgRequired();
                return !this.mInterface.setEnabled(packageName, enable, userId) ? 1 : 0;
            }
        }
    }

    private int runEnableExclusive() throws RemoteException {
        PrintWriter err = getErrPrintWriter();
        int userId = 0;
        boolean inCategory = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                char c = 65535;
                int hashCode = opt.hashCode();
                if (hashCode != 66265758) {
                    if (hashCode == 1333469547 && opt.equals("--user")) {
                        c = 0;
                    }
                } else if (opt.equals("--category")) {
                    c = 1;
                }
                switch (c) {
                    case 0:
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    case 1:
                        inCategory = true;
                        break;
                    default:
                        err.println("Error: Unknown option: " + opt);
                        return 1;
                }
            } else {
                String overlay = getNextArgRequired();
                return inCategory ? !this.mInterface.setEnabledExclusiveInCategory(overlay, userId) ? 1 : 0 : !this.mInterface.setEnabledExclusive(overlay, true, userId) ? 1 : 0;
            }
        }
    }

    private int runSetPriority() throws RemoteException {
        PrintWriter err = getErrPrintWriter();
        int userId = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                char c = 65535;
                if (opt.hashCode() == 1333469547 && opt.equals("--user")) {
                    c = 0;
                }
                if (c == 0) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return 1;
                }
            } else {
                String packageName = getNextArgRequired();
                String newParentPackageName = getNextArgRequired();
                if ("highest".equals(newParentPackageName)) {
                    return 1 ^ (this.mInterface.setHighestPriority(packageName, userId) ? 1 : 0);
                }
                return "lowest".equals(newParentPackageName) ? 1 ^ (this.mInterface.setLowestPriority(packageName, userId) ? 1 : 0) : 1 ^ (this.mInterface.setPriority(packageName, newParentPackageName, userId) ? 1 : 0);
            }
        }
    }
}
