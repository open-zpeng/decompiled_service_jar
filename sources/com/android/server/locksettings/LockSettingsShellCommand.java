package com.android.server.locksettings;

import android.os.ShellCommand;
import com.android.internal.widget.LockPatternUtils;
import java.io.PrintWriter;

/* loaded from: classes.dex */
class LockSettingsShellCommand extends ShellCommand {
    private static final String COMMAND_CLEAR = "clear";
    private static final String COMMAND_GET_DISABLED = "get-disabled";
    private static final String COMMAND_HELP = "help";
    private static final String COMMAND_SET_DISABLED = "set-disabled";
    private static final String COMMAND_SET_PASSWORD = "set-password";
    private static final String COMMAND_SET_PATTERN = "set-pattern";
    private static final String COMMAND_SET_PIN = "set-pin";
    private static final String COMMAND_SP = "sp";
    private static final String COMMAND_VERIFY = "verify";
    private int mCurrentUserId;
    private final LockPatternUtils mLockPatternUtils;
    private String mOld = "";
    private String mNew = "";

    /* JADX INFO: Access modifiers changed from: package-private */
    public LockSettingsShellCommand(LockPatternUtils lockPatternUtils) {
        this.mLockPatternUtils = lockPatternUtils;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Removed duplicated region for block: B:27:0x0058 A[ADDED_TO_REGION] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int onCommand(java.lang.String r10) {
        /*
            Method dump skipped, instructions count: 366
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.locksettings.LockSettingsShellCommand.onCommand(java.lang.String):int");
    }

    private void runVerify() {
        getOutPrintWriter().println("Lock credential verified successfully");
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        try {
            pw.println("lockSettings service commands:");
            pw.println("");
            pw.println("NOTE: when lock screen is set, all commands require the --old <CREDENTIAL> argument.");
            pw.println("");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  get-disabled [--old <CREDENTIAL>] [--user USER_ID]");
            pw.println("    Checks whether lock screen is disabled.");
            pw.println("");
            pw.println("  set-disabled [--old <CREDENTIAL>] [--user USER_ID] <true|false>");
            pw.println("    When true, disables lock screen.");
            pw.println("");
            pw.println("  set-pattern [--old <CREDENTIAL>] [--user USER_ID] <PATTERN>");
            pw.println("    Sets the lock screen as pattern, using the given PATTERN to unlock.");
            pw.println("");
            pw.println("  set-pin [--old <CREDENTIAL>] [--user USER_ID] <PIN>");
            pw.println("    Sets the lock screen as PIN, using the given PIN to unlock.");
            pw.println("");
            pw.println("  set-pin [--old <CREDENTIAL>] [--user USER_ID] <PASSWORD>");
            pw.println("    Sets the lock screen as password, using the given PASSOWRD to unlock.");
            pw.println("");
            pw.println("  sp [--old <CREDENTIAL>] [--user USER_ID]");
            pw.println("    Gets whether synthetic password is enabled.");
            pw.println("");
            pw.println("  sp [--old <CREDENTIAL>] [--user USER_ID] <1|0>");
            pw.println("    Enables / disables synthetic password.");
            pw.println("");
            pw.println("  clear [--old <CREDENTIAL>] [--user USER_ID]");
            pw.println("    Clears the lock credentials.");
            pw.println("");
            pw.println("  verify [--old <CREDENTIAL>] [--user USER_ID]");
            pw.println("    Verifies the lock credentials.");
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

    private void parseArgs() {
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if ("--old".equals(opt)) {
                    this.mOld = getNextArgRequired();
                } else if ("--user".equals(opt)) {
                    this.mCurrentUserId = Integer.parseInt(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Unknown option: " + opt);
                    throw new IllegalArgumentException();
                }
            } else {
                this.mNew = getNextArg();
                return;
            }
        }
    }

    private void runChangeSp() {
        String str = this.mNew;
        if (str != null) {
            if ("1".equals(str)) {
                this.mLockPatternUtils.enableSyntheticPassword();
                getOutPrintWriter().println("Synthetic password enabled");
            } else if ("0".equals(this.mNew)) {
                this.mLockPatternUtils.disableSyntheticPassword();
                getOutPrintWriter().println("Synthetic password disabled");
            }
        }
        getOutPrintWriter().println(String.format("SP Enabled = %b", Boolean.valueOf(this.mLockPatternUtils.isSyntheticPasswordEnabled())));
    }

    private void runSetPattern() {
        String str = this.mOld;
        byte[] oldBytes = str != null ? str.getBytes() : null;
        this.mLockPatternUtils.saveLockPattern(LockPatternUtils.stringToPattern(this.mNew), oldBytes, this.mCurrentUserId);
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Pattern set to '" + this.mNew + "'");
    }

    private void runSetPassword() {
        String str = this.mNew;
        byte[] newBytes = str != null ? str.getBytes() : null;
        String str2 = this.mOld;
        byte[] oldBytes = str2 != null ? str2.getBytes() : null;
        this.mLockPatternUtils.saveLockPassword(newBytes, oldBytes, 262144, this.mCurrentUserId);
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Password set to '" + this.mNew + "'");
    }

    private void runSetPin() {
        String str = this.mNew;
        byte[] newBytes = str != null ? str.getBytes() : null;
        String str2 = this.mOld;
        byte[] oldBytes = str2 != null ? str2.getBytes() : null;
        this.mLockPatternUtils.saveLockPassword(newBytes, oldBytes, 131072, this.mCurrentUserId);
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Pin set to '" + this.mNew + "'");
    }

    private void runClear() {
        String str = this.mOld;
        byte[] oldBytes = str != null ? str.getBytes() : null;
        this.mLockPatternUtils.clearLock(oldBytes, this.mCurrentUserId);
        getOutPrintWriter().println("Lock credential cleared");
    }

    private void runSetDisabled() {
        boolean disabled = Boolean.parseBoolean(this.mNew);
        this.mLockPatternUtils.setLockScreenDisabled(disabled, this.mCurrentUserId);
        PrintWriter outPrintWriter = getOutPrintWriter();
        outPrintWriter.println("Lock screen disabled set to " + disabled);
    }

    private void runGetDisabled() {
        boolean isLockScreenDisabled = this.mLockPatternUtils.isLockScreenDisabled(this.mCurrentUserId);
        getOutPrintWriter().println(isLockScreenDisabled);
    }

    private boolean checkCredential() {
        boolean result;
        boolean havePassword = this.mLockPatternUtils.isLockPasswordEnabled(this.mCurrentUserId);
        boolean havePattern = this.mLockPatternUtils.isLockPatternEnabled(this.mCurrentUserId);
        if (havePassword || havePattern) {
            if (this.mLockPatternUtils.isManagedProfileWithUnifiedChallenge(this.mCurrentUserId)) {
                getOutPrintWriter().println("Profile uses unified challenge");
                return false;
            }
            try {
                if (havePassword) {
                    byte[] passwordBytes = this.mOld != null ? this.mOld.getBytes() : null;
                    result = this.mLockPatternUtils.checkPassword(passwordBytes, this.mCurrentUserId);
                } else {
                    result = this.mLockPatternUtils.checkPattern(LockPatternUtils.stringToPattern(this.mOld), this.mCurrentUserId);
                }
                if (!result) {
                    if (!this.mLockPatternUtils.isManagedProfileWithUnifiedChallenge(this.mCurrentUserId)) {
                        this.mLockPatternUtils.reportFailedPasswordAttempt(this.mCurrentUserId);
                    }
                    PrintWriter outPrintWriter = getOutPrintWriter();
                    outPrintWriter.println("Old password '" + this.mOld + "' didn't match");
                } else {
                    this.mLockPatternUtils.reportSuccessfulPasswordAttempt(this.mCurrentUserId);
                }
                return result;
            } catch (LockPatternUtils.RequestThrottledException e) {
                getOutPrintWriter().println("Request throttled");
                return false;
            }
        } else if (!this.mOld.isEmpty()) {
            getOutPrintWriter().println("Old password provided but user has no password");
            return false;
        } else {
            return true;
        }
    }
}
