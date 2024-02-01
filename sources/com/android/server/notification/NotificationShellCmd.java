package com.android.server.notification;

import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Slog;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.PrintWriter;
import java.util.Collections;

/* loaded from: classes.dex */
public class NotificationShellCmd extends ShellCommand {
    public static final String CHANNEL_ID = "shell_cmd";
    public static final int CHANNEL_IMP = 3;
    public static final String CHANNEL_NAME = "Shell command";
    public static final int NOTIFICATION_ID = 2020;
    private static final String NOTIFY_USAGE = "usage: cmd notification post [flags] <tag> <text>\n\nflags:\n  -h|--help\n  -v|--verbose\n  -t|--title <text>\n  -i|--icon <iconspec>\n  -I|--large-icon <iconspec>\n  -S|--style <style> [styleargs]\n  -c|--content-intent <intentspec>\n\nstyles: (default none)\n  bigtext\n  bigpicture --picture <iconspec>\n  inbox --line <text> --line <text> ...\n  messaging --conversation <title> --message <who>:<text> ...\n  media\n\nan <iconspec> is one of\n  file:///data/local/tmp/<img.png>\n  content://<provider>/<path>\n  @[<package>:]drawable/<img>\n  data:base64,<B64DATA==>\n\nan <intentspec> is (broadcast|service|activity) <args>\n  <args> are as described in `am start`";
    private static final String TAG = "NotifShellCmd";
    private static final String USAGE = "usage: cmd notification SUBCMD [args]\n\nSUBCMDs:\n  allow_listener COMPONENT [user_id (current user if not specified)]\n  disallow_listener COMPONENT [user_id (current user if not specified)]\n  allow_assistant COMPONENT [user_id (current user if not specified)]\n  remove_assistant COMPONENT [user_id (current user if not specified)]\n  allow_dnd PACKAGE [user_id (current user if not specified)]\n  disallow_dnd PACKAGE [user_id (current user if not specified)]\n  suspend_package PACKAGE\n  unsuspend_package PACKAGE\n  reset_assistant_user_set [user_id (current user if not specified)]\n  get_approved_assistant [user_id (current user if not specified)]\n  post [--help | flags] TAG TEXT";
    private final INotificationManager mBinderService;
    private final NotificationManagerService mDirectService;
    private final PackageManager mPm;

    public NotificationShellCmd(NotificationManagerService service) {
        this.mDirectService = service;
        this.mBinderService = service.getBinderService();
        this.mPm = this.mDirectService.getContext().getPackageManager();
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public int onCommand(String cmd) {
        char c;
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        String callingPackage = null;
        int callingUid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                String[] packages = this.mPm.getPackagesForUid(callingUid);
                if (packages != null && packages.length > 0) {
                    callingPackage = packages[0];
                }
            } catch (Exception e) {
                Slog.e(TAG, "failed to get caller pkg", e);
            }
            PrintWriter pw = getOutPrintWriter();
            try {
                String replace = cmd.replace('-', '_');
                char c2 = 65535;
                switch (replace.hashCode()) {
                    case -1325770982:
                        if (replace.equals("disallow_assistant")) {
                            c = 6;
                            break;
                        }
                        c = 65535;
                        break;
                    case -1039689911:
                        if (replace.equals("notify")) {
                            c = '\r';
                            break;
                        }
                        c = 65535;
                        break;
                    case -506770550:
                        if (replace.equals("unsuspend_package")) {
                            c = '\b';
                            break;
                        }
                        c = 65535;
                        break;
                    case -432999190:
                        if (replace.equals("allow_listener")) {
                            c = 3;
                            break;
                        }
                        c = 65535;
                        break;
                    case -429832618:
                        if (replace.equals("disallow_dnd")) {
                            c = 2;
                            break;
                        }
                        c = 65535;
                        break;
                    case -414550305:
                        if (replace.equals("get_approved_assistant")) {
                            c = 11;
                            break;
                        }
                        c = 65535;
                        break;
                    case 3446944:
                        if (replace.equals("post")) {
                            c = '\f';
                            break;
                        }
                        c = 65535;
                        break;
                    case 372345636:
                        if (replace.equals("allow_dnd")) {
                            c = 1;
                            break;
                        }
                        c = 65535;
                        break;
                    case 393969475:
                        if (replace.equals("suspend_package")) {
                            c = 7;
                            break;
                        }
                        c = 65535;
                        break;
                    case 683492127:
                        if (replace.equals("reset_assistant_user_set")) {
                            c = '\n';
                            break;
                        }
                        c = 65535;
                        break;
                    case 1257269496:
                        if (replace.equals("disallow_listener")) {
                            c = 4;
                            break;
                        }
                        c = 65535;
                        break;
                    case 1570441869:
                        if (replace.equals("distract_package")) {
                            c = '\t';
                            break;
                        }
                        c = 65535;
                        break;
                    case 1985310653:
                        if (replace.equals("set_dnd")) {
                            c = 0;
                            break;
                        }
                        c = 65535;
                        break;
                    case 2110474600:
                        if (replace.equals("allow_assistant")) {
                            c = 5;
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
                        String mode = getNextArgRequired();
                        int interruptionFilter = 0;
                        switch (mode.hashCode()) {
                            case -1415196606:
                                if (mode.equals("alarms")) {
                                    c2 = 3;
                                    break;
                                }
                                break;
                            case -1165461084:
                                if (mode.equals(xpInputManagerService.InputPolicyKey.KEY_PRIORITY)) {
                                    c2 = 2;
                                    break;
                                }
                                break;
                            case 3551:
                                if (mode.equals("on")) {
                                    c2 = 1;
                                    break;
                                }
                                break;
                            case 96673:
                                if (mode.equals("all")) {
                                    c2 = 4;
                                    break;
                                }
                                break;
                            case 109935:
                                if (mode.equals("off")) {
                                    c2 = 5;
                                    break;
                                }
                                break;
                            case 3387192:
                                if (mode.equals("none")) {
                                    c2 = 0;
                                    break;
                                }
                                break;
                        }
                        if (c2 == 0 || c2 == 1) {
                            interruptionFilter = 3;
                        } else if (c2 == 2) {
                            interruptionFilter = 2;
                        } else if (c2 == 3) {
                            interruptionFilter = 4;
                        } else if (c2 == 4 || c2 == 5) {
                            interruptionFilter = 1;
                        }
                        int filter = interruptionFilter;
                        this.mBinderService.setInterruptionFilter(callingPackage, filter);
                        break;
                    case 1:
                        String packageName = getNextArgRequired();
                        int userId = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId = Integer.parseInt(getNextArgRequired());
                        }
                        this.mBinderService.setNotificationPolicyAccessGrantedForUser(packageName, userId, true);
                        break;
                    case 2:
                        String packageName2 = getNextArgRequired();
                        int userId2 = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId2 = Integer.parseInt(getNextArgRequired());
                        }
                        this.mBinderService.setNotificationPolicyAccessGrantedForUser(packageName2, userId2, false);
                        break;
                    case 3:
                        ComponentName cn = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn == null) {
                            pw.println("Invalid listener - must be a ComponentName");
                            return -1;
                        }
                        int userId3 = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId3 = Integer.parseInt(getNextArgRequired());
                        }
                        this.mBinderService.setNotificationListenerAccessGrantedForUser(cn, userId3, true);
                        break;
                    case 4:
                        ComponentName cn2 = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn2 == null) {
                            pw.println("Invalid listener - must be a ComponentName");
                            return -1;
                        }
                        int userId4 = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId4 = Integer.parseInt(getNextArgRequired());
                        }
                        this.mBinderService.setNotificationListenerAccessGrantedForUser(cn2, userId4, false);
                        break;
                    case 5:
                        ComponentName cn3 = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn3 == null) {
                            pw.println("Invalid assistant - must be a ComponentName");
                            return -1;
                        }
                        int userId5 = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId5 = Integer.parseInt(getNextArgRequired());
                        }
                        this.mBinderService.setNotificationAssistantAccessGrantedForUser(cn3, userId5, true);
                        break;
                    case 6:
                        ComponentName cn4 = ComponentName.unflattenFromString(getNextArgRequired());
                        if (cn4 == null) {
                            pw.println("Invalid assistant - must be a ComponentName");
                            return -1;
                        }
                        int userId6 = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId6 = Integer.parseInt(getNextArgRequired());
                        }
                        this.mBinderService.setNotificationAssistantAccessGrantedForUser(cn4, userId6, false);
                        break;
                    case 7:
                        this.mDirectService.simulatePackageSuspendBroadcast(true, getNextArgRequired());
                        break;
                    case '\b':
                        this.mDirectService.simulatePackageSuspendBroadcast(false, getNextArgRequired());
                        break;
                    case '\t':
                        this.mDirectService.simulatePackageDistractionBroadcast(Integer.parseInt(getNextArgRequired()), getNextArgRequired().split(","));
                        break;
                    case '\n':
                        int userId7 = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId7 = Integer.parseInt(getNextArgRequired());
                        }
                        this.mDirectService.resetAssistantUserSet(userId7);
                        break;
                    case 11:
                        int userId8 = ActivityManager.getCurrentUser();
                        if (peekNextArg() != null) {
                            userId8 = Integer.parseInt(getNextArgRequired());
                        }
                        ComponentName approvedAssistant = this.mDirectService.getApprovedAssistant(userId8);
                        if (approvedAssistant == null) {
                            pw.println("null");
                            break;
                        } else {
                            pw.println(approvedAssistant.flattenToString());
                            break;
                        }
                    case '\f':
                    case '\r':
                        doNotify(pw, callingPackage, callingUid);
                        break;
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (Exception e2) {
                pw.println("Error occurred. Check logcat for details. " + e2.getMessage());
                Slog.e("NotificationService", "Error running shell command", e2);
            }
            return 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void ensureChannel(String callingPackage, int callingUid) throws RemoteException {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, 3);
        this.mBinderService.createNotificationChannels(callingPackage, new ParceledListSlice(Collections.singletonList(channel)));
        Slog.v("NotificationService", "created channel: " + this.mBinderService.getNotificationChannel(callingPackage, UserHandle.getUserId(callingUid), callingPackage, CHANNEL_ID));
    }

    Icon parseIcon(Resources res, String encoded) throws IllegalArgumentException {
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }
        if (encoded.startsWith(SliceClientPermissions.SliceAuthority.DELIMITER)) {
            encoded = "file://" + encoded;
        }
        if (encoded.startsWith("http:") || encoded.startsWith("https:") || encoded.startsWith("content:") || encoded.startsWith("file:") || encoded.startsWith("android.resource:")) {
            Uri asUri = Uri.parse(encoded);
            return Icon.createWithContentUri(asUri);
        }
        if (encoded.startsWith("@")) {
            int resid = res.getIdentifier(encoded.substring(1), "drawable", PackageManagerService.PLATFORM_PACKAGE_NAME);
            if (resid != 0) {
                return Icon.createWithResource(res, resid);
            }
        } else if (encoded.startsWith("data:")) {
            byte[] bits = Base64.decode(encoded.substring(encoded.indexOf(44) + 1), 0);
            return Icon.createWithData(bits, 0, bits.length);
        }
        return null;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Removed duplicated region for block: B:193:0x03e5  */
    /* JADX WARN: Removed duplicated region for block: B:201:0x03fe  */
    /* JADX WARN: Removed duplicated region for block: B:202:0x0420  */
    /* JADX WARN: Removed duplicated region for block: B:205:0x042c  */
    /* JADX WARN: Removed duplicated region for block: B:206:0x0439  */
    /* JADX WARN: Removed duplicated region for block: B:217:0x04a1  */
    /* JADX WARN: Removed duplicated region for block: B:258:0x048b A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private int doNotify(java.io.PrintWriter r27, java.lang.String r28, int r29) throws android.os.RemoteException, java.net.URISyntaxException {
        /*
            Method dump skipped, instructions count: 1670
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.NotificationShellCmd.doNotify(java.io.PrintWriter, java.lang.String, int):int");
    }

    public void onHelp() {
        getOutPrintWriter().println(USAGE);
    }
}
