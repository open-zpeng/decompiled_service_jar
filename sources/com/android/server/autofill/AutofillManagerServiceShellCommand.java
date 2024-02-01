package com.android.server.autofill;

import android.os.Bundle;
import android.os.RemoteCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.service.autofill.AutofillFieldClassificationService;
import com.android.internal.os.IResultReceiver;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/* loaded from: classes.dex */
public final class AutofillManagerServiceShellCommand extends ShellCommand {
    private final AutofillManagerService mService;

    public AutofillManagerServiceShellCommand(AutofillManagerService service) {
        this.mService = service;
    }

    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter pw = getOutPrintWriter();
        char c = 65535;
        switch (cmd.hashCode()) {
            case 102230:
                if (cmd.equals("get")) {
                    c = 3;
                    break;
                }
                break;
            case 113762:
                if (cmd.equals("set")) {
                    c = 4;
                    break;
                }
                break;
            case 3322014:
                if (cmd.equals("list")) {
                    c = 0;
                    break;
                }
                break;
            case 108404047:
                if (cmd.equals("reset")) {
                    c = 2;
                    break;
                }
                break;
            case 1557372922:
                if (cmd.equals("destroy")) {
                    c = 1;
                    break;
                }
                break;
        }
        if (c != 0) {
            if (c != 1) {
                if (c != 2) {
                    if (c != 3) {
                        if (c == 4) {
                            return requestSet(pw);
                        }
                        return handleDefaultCommands(cmd);
                    }
                    return requestGet(pw);
                }
                return requestReset();
            }
            return requestDestroy(pw);
        }
        return requestList(pw);
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        try {
            pw.println("AutoFill Service (autofill) commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  get log_level ");
            pw.println("    Gets the Autofill log level (off | debug | verbose).");
            pw.println("");
            pw.println("  get max_partitions");
            pw.println("    Gets the maximum number of partitions per session.");
            pw.println("");
            pw.println("  get max_visible_datasets");
            pw.println("    Gets the maximum number of visible datasets in the UI.");
            pw.println("");
            pw.println("  get full_screen_mode");
            pw.println("    Gets the Fill UI full screen mode");
            pw.println("");
            pw.println("  get fc_score [--algorithm ALGORITHM] value1 value2");
            pw.println("    Gets the field classification score for 2 fields.");
            pw.println("");
            pw.println("  get bind-instant-service-allowed");
            pw.println("    Gets whether binding to services provided by instant apps is allowed");
            pw.println("");
            pw.println("  set log_level [off | debug | verbose]");
            pw.println("    Sets the Autofill log level.");
            pw.println("");
            pw.println("  set max_partitions number");
            pw.println("    Sets the maximum number of partitions per session.");
            pw.println("");
            pw.println("  set max_visible_datasets number");
            pw.println("    Sets the maximum number of visible datasets in the UI.");
            pw.println("");
            pw.println("  set full_screen_mode [true | false | default]");
            pw.println("    Sets the Fill UI full screen mode");
            pw.println("");
            pw.println("  set bind-instant-service-allowed [true | false]");
            pw.println("    Sets whether binding to services provided by instant apps is allowed");
            pw.println("");
            pw.println("  set temporary-augmented-service USER_ID [COMPONENT_NAME DURATION]");
            pw.println("    Temporarily (for DURATION ms) changes the augmented autofill service implementation.");
            pw.println("    To reset, call with just the USER_ID argument.");
            pw.println("");
            pw.println("  set default-augmented-service-enabled USER_ID [true|false]");
            pw.println("    Enable / disable the default augmented autofill service for the user.");
            pw.println("");
            pw.println("  get default-augmented-service-enabled USER_ID");
            pw.println("    Checks whether the default augmented autofill service is enabled for the user.");
            pw.println("");
            pw.println("  list sessions [--user USER_ID]");
            pw.println("    Lists all pending sessions.");
            pw.println("");
            pw.println("  destroy sessions [--user USER_ID]");
            pw.println("    Destroys all pending sessions.");
            pw.println("");
            pw.println("  reset");
            pw.println("    Resets all pending sessions and cached service connections.");
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

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private int requestGet(PrintWriter pw) {
        char c;
        String what = getNextArgRequired();
        switch (what.hashCode()) {
            case -2124387184:
                if (what.equals("fc_score")) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case -2006901047:
                if (what.equals("log_level")) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case -1298810906:
                if (what.equals("full_screen_mode")) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case 809633044:
                if (what.equals("bind-instant-service-allowed")) {
                    c = 5;
                    break;
                }
                c = 65535;
                break;
            case 852405952:
                if (what.equals("default-augmented-service-enabled")) {
                    c = 6;
                    break;
                }
                c = 65535;
                break;
            case 1393110435:
                if (what.equals("max_visible_datasets")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 1772188804:
                if (what.equals("max_partitions")) {
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
                return getLogLevel(pw);
            case 1:
                return getMaxPartitions(pw);
            case 2:
                return getMaxVisibileDatasets(pw);
            case 3:
                return getFieldClassificationScore(pw);
            case 4:
                return getFullScreenMode(pw);
            case 5:
                return getBindInstantService(pw);
            case 6:
                return getDefaultAugmentedServiceEnabled(pw);
            default:
                pw.println("Invalid set: " + what);
                return -1;
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private int requestSet(PrintWriter pw) {
        char c;
        String what = getNextArgRequired();
        switch (what.hashCode()) {
            case -2006901047:
                if (what.equals("log_level")) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case -1298810906:
                if (what.equals("full_screen_mode")) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case -571600804:
                if (what.equals("temporary-augmented-service")) {
                    c = 5;
                    break;
                }
                c = 65535;
                break;
            case 809633044:
                if (what.equals("bind-instant-service-allowed")) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case 852405952:
                if (what.equals("default-augmented-service-enabled")) {
                    c = 6;
                    break;
                }
                c = 65535;
                break;
            case 1393110435:
                if (what.equals("max_visible_datasets")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 1772188804:
                if (what.equals("max_partitions")) {
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
                return setLogLevel(pw);
            case 1:
                return setMaxPartitions();
            case 2:
                return setMaxVisibileDatasets();
            case 3:
                return setFullScreenMode(pw);
            case 4:
                return setBindInstantService(pw);
            case 5:
                return setTemporaryAugmentedService(pw);
            case 6:
                return setDefaultAugmentedServiceEnabled(pw);
            default:
                pw.println("Invalid set: " + what);
                return -1;
        }
    }

    private int getLogLevel(PrintWriter pw) {
        int logLevel = this.mService.getLogLevel();
        if (logLevel == 0) {
            pw.println("off");
            return 0;
        } else if (logLevel == 2) {
            pw.println("debug");
            return 0;
        } else if (logLevel == 4) {
            pw.println("verbose");
            return 0;
        } else {
            pw.println("unknow (" + logLevel + ")");
            return 0;
        }
    }

    private int setLogLevel(PrintWriter pw) {
        boolean z;
        String logLevel = getNextArgRequired();
        String lowerCase = logLevel.toLowerCase();
        int hashCode = lowerCase.hashCode();
        if (hashCode == 109935) {
            if (lowerCase.equals("off")) {
                z = true;
            }
            z = true;
        } else if (hashCode != 95458899) {
            if (hashCode == 351107458 && lowerCase.equals("verbose")) {
                z = false;
            }
            z = true;
        } else {
            if (lowerCase.equals("debug")) {
                z = true;
            }
            z = true;
        }
        if (!z) {
            this.mService.setLogLevel(4);
            return 0;
        } else if (z) {
            this.mService.setLogLevel(2);
            return 0;
        } else if (z) {
            this.mService.setLogLevel(0);
            return 0;
        } else {
            pw.println("Invalid level: " + logLevel);
            return -1;
        }
    }

    private int getMaxPartitions(PrintWriter pw) {
        pw.println(this.mService.getMaxPartitions());
        return 0;
    }

    private int setMaxPartitions() {
        this.mService.setMaxPartitions(Integer.parseInt(getNextArgRequired()));
        return 0;
    }

    private int getMaxVisibileDatasets(PrintWriter pw) {
        pw.println(this.mService.getMaxVisibleDatasets());
        return 0;
    }

    private int setMaxVisibileDatasets() {
        this.mService.setMaxVisibleDatasets(Integer.parseInt(getNextArgRequired()));
        return 0;
    }

    private int getFieldClassificationScore(final PrintWriter pw) {
        String algorithm;
        String value1;
        String nextArg = getNextArgRequired();
        if ("--algorithm".equals(nextArg)) {
            algorithm = getNextArgRequired();
            value1 = getNextArgRequired();
        } else {
            algorithm = null;
            value1 = nextArg;
        }
        String value2 = getNextArgRequired();
        final CountDownLatch latch = new CountDownLatch(1);
        this.mService.calculateScore(algorithm, value1, value2, new RemoteCallback(new RemoteCallback.OnResultListener() { // from class: com.android.server.autofill.-$$Lambda$AutofillManagerServiceShellCommand$3WCRplTGFh_xsmb8tmAG8x-Pn5A
            public final void onResult(Bundle bundle) {
                AutofillManagerServiceShellCommand.lambda$getFieldClassificationScore$0(pw, latch, bundle);
            }
        }));
        return waitForLatch(pw, latch);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getFieldClassificationScore$0(PrintWriter pw, CountDownLatch latch, Bundle result) {
        AutofillFieldClassificationService.Scores scores = result.getParcelable("scores");
        if (scores == null) {
            pw.println("no score");
        } else {
            pw.println(scores.scores[0][0]);
        }
        latch.countDown();
    }

    private int getFullScreenMode(PrintWriter pw) {
        Boolean mode = this.mService.getFullScreenMode();
        if (mode == null) {
            pw.println("default");
            return 0;
        } else if (mode.booleanValue()) {
            pw.println("true");
            return 0;
        } else {
            pw.println("false");
            return 0;
        }
    }

    private int setFullScreenMode(PrintWriter pw) {
        boolean z;
        String mode = getNextArgRequired();
        String lowerCase = mode.toLowerCase();
        int hashCode = lowerCase.hashCode();
        if (hashCode == 3569038) {
            if (lowerCase.equals("true")) {
                z = false;
            }
            z = true;
        } else if (hashCode != 97196323) {
            if (hashCode == 1544803905 && lowerCase.equals("default")) {
                z = true;
            }
            z = true;
        } else {
            if (lowerCase.equals("false")) {
                z = true;
            }
            z = true;
        }
        if (!z) {
            this.mService.setFullScreenMode(Boolean.TRUE);
            return 0;
        } else if (z) {
            this.mService.setFullScreenMode(Boolean.FALSE);
            return 0;
        } else if (z) {
            this.mService.setFullScreenMode(null);
            return 0;
        } else {
            pw.println("Invalid mode: " + mode);
            return -1;
        }
    }

    private int getBindInstantService(PrintWriter pw) {
        if (this.mService.getAllowInstantService()) {
            pw.println("true");
            return 0;
        }
        pw.println("false");
        return 0;
    }

    private int setBindInstantService(PrintWriter pw) {
        boolean z;
        String mode = getNextArgRequired();
        String lowerCase = mode.toLowerCase();
        int hashCode = lowerCase.hashCode();
        if (hashCode != 3569038) {
            if (hashCode == 97196323 && lowerCase.equals("false")) {
                z = true;
            }
            z = true;
        } else {
            if (lowerCase.equals("true")) {
                z = false;
            }
            z = true;
        }
        if (!z) {
            this.mService.setAllowInstantService(true);
            return 0;
        } else if (z) {
            this.mService.setAllowInstantService(false);
            return 0;
        } else {
            pw.println("Invalid mode: " + mode);
            return -1;
        }
    }

    private int setTemporaryAugmentedService(PrintWriter pw) {
        int userId = getNextIntArgRequired();
        String serviceName = getNextArg();
        if (serviceName == null) {
            this.mService.resetTemporaryAugmentedAutofillService(userId);
            return 0;
        }
        int duration = getNextIntArgRequired();
        this.mService.setTemporaryAugmentedAutofillService(userId, serviceName, duration);
        pw.println("AugmentedAutofillService temporarily set to " + serviceName + " for " + duration + "ms");
        return 0;
    }

    private int getDefaultAugmentedServiceEnabled(PrintWriter pw) {
        int userId = getNextIntArgRequired();
        boolean enabled = this.mService.isDefaultAugmentedServiceEnabled(userId);
        pw.println(enabled);
        return 0;
    }

    private int setDefaultAugmentedServiceEnabled(PrintWriter pw) {
        int userId = getNextIntArgRequired();
        boolean enabled = Boolean.parseBoolean(getNextArgRequired());
        boolean changed = this.mService.setDefaultAugmentedServiceEnabled(userId, enabled);
        if (!changed) {
            pw.println("already " + enabled);
            return 0;
        }
        return 0;
    }

    private int requestDestroy(PrintWriter pw) {
        if (!isNextArgSessions(pw)) {
            return -1;
        }
        final int userId = getUserIdFromArgsOrAllUsers();
        final CountDownLatch latch = new CountDownLatch(1);
        final IResultReceiver.Stub stub = new IResultReceiver.Stub() { // from class: com.android.server.autofill.AutofillManagerServiceShellCommand.1
            public void send(int resultCode, Bundle resultData) {
                latch.countDown();
            }
        };
        return requestSessionCommon(pw, latch, new Runnable() { // from class: com.android.server.autofill.-$$Lambda$AutofillManagerServiceShellCommand$ww56nbkJspkRdVJ0yMdT4sroSiY
            @Override // java.lang.Runnable
            public final void run() {
                AutofillManagerServiceShellCommand.this.lambda$requestDestroy$1$AutofillManagerServiceShellCommand(userId, stub);
            }
        });
    }

    public /* synthetic */ void lambda$requestDestroy$1$AutofillManagerServiceShellCommand(int userId, IResultReceiver receiver) {
        this.mService.destroySessions(userId, receiver);
    }

    private int requestList(final PrintWriter pw) {
        if (!isNextArgSessions(pw)) {
            return -1;
        }
        final int userId = getUserIdFromArgsOrAllUsers();
        final CountDownLatch latch = new CountDownLatch(1);
        final IResultReceiver.Stub stub = new IResultReceiver.Stub() { // from class: com.android.server.autofill.AutofillManagerServiceShellCommand.2
            public void send(int resultCode, Bundle resultData) {
                ArrayList<String> sessions = resultData.getStringArrayList("sessions");
                Iterator<String> it = sessions.iterator();
                while (it.hasNext()) {
                    String session = it.next();
                    pw.println(session);
                }
                latch.countDown();
            }
        };
        return requestSessionCommon(pw, latch, new Runnable() { // from class: com.android.server.autofill.-$$Lambda$AutofillManagerServiceShellCommand$WrWpLlZPawytZji8-6Dx9_p70Dw
            @Override // java.lang.Runnable
            public final void run() {
                AutofillManagerServiceShellCommand.this.lambda$requestList$2$AutofillManagerServiceShellCommand(userId, stub);
            }
        });
    }

    public /* synthetic */ void lambda$requestList$2$AutofillManagerServiceShellCommand(int userId, IResultReceiver receiver) {
        this.mService.listSessions(userId, receiver);
    }

    private boolean isNextArgSessions(PrintWriter pw) {
        String type = getNextArgRequired();
        if (!type.equals("sessions")) {
            pw.println("Error: invalid list type");
            return false;
        }
        return true;
    }

    private int requestSessionCommon(PrintWriter pw, CountDownLatch latch, Runnable command) {
        command.run();
        return waitForLatch(pw, latch);
    }

    private int waitForLatch(PrintWriter pw, CountDownLatch latch) {
        try {
            boolean received = latch.await(5L, TimeUnit.SECONDS);
            if (!received) {
                pw.println("Timed out after 5 seconds");
                return -1;
            }
            return 0;
        } catch (InterruptedException e) {
            pw.println("System call interrupted");
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private int requestReset() {
        this.mService.reset();
        return 0;
    }

    private int getUserIdFromArgsOrAllUsers() {
        if ("--user".equals(getNextArg())) {
            return UserHandle.parseUserArg(getNextArgRequired());
        }
        return -1;
    }

    private int getNextIntArgRequired() {
        return Integer.parseInt(getNextArgRequired());
    }
}
