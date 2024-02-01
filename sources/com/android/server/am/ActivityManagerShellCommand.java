package com.android.server.am;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.IApplicationThread;
import android.app.IStopUserCallback;
import android.app.IUidObserver;
import android.app.KeyguardManager;
import android.app.ProfilerInfo;
import android.app.usage.AppStandbyInfo;
import android.app.usage.ConfigurationStats;
import android.app.usage.IUsageStatsManager;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.UserInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.INetd;
import android.opengl.GLES10;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IProgressListener;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.DisplayMetrics;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import com.android.internal.util.HexDump;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.Preconditions;
import com.android.server.am.ActivityManagerService;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.job.controllers.JobStatus;
import com.android.server.net.NetworkPolicyManagerService;
import com.android.server.pm.DumpState;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerService;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class ActivityManagerShellCommand extends ShellCommand {
    public static final String NO_CLASS_ERROR_CODE = "Error type 3";
    private static final String SHELL_PACKAGE_NAME = "com.android.shell";
    private static final int USER_OPERATION_TIMEOUT_MS = 120000;
    private int mActivityType;
    private String mAgent;
    private boolean mAttachAgentDuringBind;
    private boolean mAutoStop;
    private int mDisplayId;
    final boolean mDumping;
    final IActivityManager mInterface;
    final ActivityManagerService mInternal;
    private boolean mIsLockTask;
    private boolean mIsTaskOverlay;
    private String mProfileFile;
    private String mReceiverPermission;
    private int mSamplingInterval;
    private boolean mStreaming;
    private int mTaskId;
    final IActivityTaskManager mTaskInterface;
    private int mUserId;
    private int mWindowingMode;
    private int mStartFlags = 0;
    private boolean mWaitOption = false;
    private boolean mStopOption = false;
    private int mRepeat = 0;
    final IPackageManager mPm = AppGlobals.getPackageManager();

    static /* synthetic */ int access$076(ActivityManagerShellCommand x0, int x1) {
        int i = x0.mStartFlags | x1;
        x0.mStartFlags = i;
        return i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManagerShellCommand(ActivityManagerService service, boolean dumping) {
        this.mInterface = service;
        this.mTaskInterface = service.mActivityTaskManager;
        this.mInternal = service;
        this.mDumping = dumping;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public int onCommand(String cmd) {
        char c;
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd.hashCode()) {
                case -2121667104:
                    if (cmd.equals("dumpheap")) {
                        c = 14;
                        break;
                    }
                    c = 65535;
                    break;
                case -1969672196:
                    if (cmd.equals("set-debug-app")) {
                        c = 15;
                        break;
                    }
                    c = 65535;
                    break;
                case -1719979774:
                    if (cmd.equals("get-inactive")) {
                        c = '1';
                        break;
                    }
                    c = 65535;
                    break;
                case -1710503333:
                    if (cmd.equals("package-importance")) {
                        c = ' ';
                        break;
                    }
                    c = 65535;
                    break;
                case -1667670943:
                    if (cmd.equals("get-standby-bucket")) {
                        c = '3';
                        break;
                    }
                    c = 65535;
                    break;
                case -1619282346:
                    if (cmd.equals("start-user")) {
                        c = '&';
                        break;
                    }
                    c = 65535;
                    break;
                case -1618876223:
                    if (cmd.equals(INetd.IF_FLAG_BROADCAST)) {
                        c = '\n';
                        break;
                    }
                    c = 65535;
                    break;
                case -1324660647:
                    if (cmd.equals("suppress-resize-config-changes")) {
                        c = '/';
                        break;
                    }
                    c = 65535;
                    break;
                case -1303445945:
                    if (cmd.equals("send-trim-memory")) {
                        c = '4';
                        break;
                    }
                    c = 65535;
                    break;
                case -1131287478:
                    if (cmd.equals("start-service")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case -1002578147:
                    if (cmd.equals("get-uid-state")) {
                        c = '-';
                        break;
                    }
                    c = 65535;
                    break;
                case -965273485:
                    if (cmd.equals("stopservice")) {
                        c = '\b';
                        break;
                    }
                    c = 65535;
                    break;
                case -930080590:
                    if (cmd.equals("startfgservice")) {
                        c = 5;
                        break;
                    }
                    c = 65535;
                    break;
                case -907667276:
                    if (cmd.equals("unlock-user")) {
                        c = '\'';
                        break;
                    }
                    c = 65535;
                    break;
                case -892396682:
                    if (cmd.equals("start-foreground-service")) {
                        c = 6;
                        break;
                    }
                    c = 65535;
                    break;
                case -870018278:
                    if (cmd.equals("to-uri")) {
                        c = '!';
                        break;
                    }
                    c = 65535;
                    break;
                case -812219210:
                    if (cmd.equals("get-current-user")) {
                        c = '%';
                        break;
                    }
                    c = 65535;
                    break;
                case -747637291:
                    if (cmd.equals("set-standby-bucket")) {
                        c = '2';
                        break;
                    }
                    c = 65535;
                    break;
                case -699625063:
                    if (cmd.equals("get-config")) {
                        c = '.';
                        break;
                    }
                    c = 65535;
                    break;
                case -606123342:
                    if (cmd.equals("kill-all")) {
                        c = 24;
                        break;
                    }
                    c = 65535;
                    break;
                case -548621938:
                    if (cmd.equals("is-user-stopped")) {
                        c = ')';
                        break;
                    }
                    c = 65535;
                    break;
                case -387147436:
                    if (cmd.equals("track-associations")) {
                        c = '+';
                        break;
                    }
                    c = 65535;
                    break;
                case -354890749:
                    if (cmd.equals("screen-compat")) {
                        c = 31;
                        break;
                    }
                    c = 65535;
                    break;
                case -309425751:
                    if (cmd.equals("profile")) {
                        c = '\r';
                        break;
                    }
                    c = 65535;
                    break;
                case -170987146:
                    if (cmd.equals("set-inactive")) {
                        c = '0';
                        break;
                    }
                    c = 65535;
                    break;
                case -146027423:
                    if (cmd.equals("watch-uids")) {
                        c = 27;
                        break;
                    }
                    c = 65535;
                    break;
                case -100644880:
                    if (cmd.equals("startforegroundservice")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case -27715536:
                    if (cmd.equals("make-uid-idle")) {
                        c = 25;
                        break;
                    }
                    c = 65535;
                    break;
                case 3194994:
                    if (cmd.equals("hang")) {
                        c = 28;
                        break;
                    }
                    c = 65535;
                    break;
                case 3291998:
                    if (cmd.equals("kill")) {
                        c = 23;
                        break;
                    }
                    c = 65535;
                    break;
                case 3552645:
                    if (cmd.equals("task")) {
                        c = '7';
                        break;
                    }
                    c = 65535;
                    break;
                case 88586660:
                    if (cmd.equals("force-stop")) {
                        c = 21;
                        break;
                    }
                    c = 65535;
                    break;
                case 94921639:
                    if (cmd.equals("crash")) {
                        c = 22;
                        break;
                    }
                    c = 65535;
                    break;
                case 109757064:
                    if (cmd.equals("stack")) {
                        c = '6';
                        break;
                    }
                    c = 65535;
                    break;
                case 109757538:
                    if (cmd.equals("start")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 113399775:
                    if (cmd.equals("write")) {
                        c = '8';
                        break;
                    }
                    c = 65535;
                    break;
                case 185053203:
                    if (cmd.equals("startservice")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case 237240942:
                    if (cmd.equals("to-app-uri")) {
                        c = '#';
                        break;
                    }
                    c = 65535;
                    break;
                case 549617690:
                    if (cmd.equals("start-activity")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case 622433197:
                    if (cmd.equals("untrack-associations")) {
                        c = ',';
                        break;
                    }
                    c = 65535;
                    break;
                case 667014829:
                    if (cmd.equals("bug-report")) {
                        c = 20;
                        break;
                    }
                    c = 65535;
                    break;
                case 680834441:
                    if (cmd.equals("supports-split-screen-multi-window")) {
                        c = ';';
                        break;
                    }
                    c = 65535;
                    break;
                case 723112852:
                    if (cmd.equals("trace-ipc")) {
                        c = '\f';
                        break;
                    }
                    c = 65535;
                    break;
                case 764545184:
                    if (cmd.equals("supports-multiwindow")) {
                        c = ':';
                        break;
                    }
                    c = 65535;
                    break;
                case 808179021:
                    if (cmd.equals("to-intent-uri")) {
                        c = '\"';
                        break;
                    }
                    c = 65535;
                    break;
                case 810242677:
                    if (cmd.equals("set-watch-heap")) {
                        c = 18;
                        break;
                    }
                    c = 65535;
                    break;
                case 817137578:
                    if (cmd.equals("clear-watch-heap")) {
                        c = 19;
                        break;
                    }
                    c = 65535;
                    break;
                case 822490030:
                    if (cmd.equals("set-agent-app")) {
                        c = 16;
                        break;
                    }
                    c = 65535;
                    break;
                case 900455412:
                    if (cmd.equals("start-fg-service")) {
                        c = 7;
                        break;
                    }
                    c = 65535;
                    break;
                case 1024703869:
                    if (cmd.equals("attach-agent")) {
                        c = '9';
                        break;
                    }
                    c = 65535;
                    break;
                case 1078591527:
                    if (cmd.equals("clear-debug-app")) {
                        c = 17;
                        break;
                    }
                    c = 65535;
                    break;
                case 1097506319:
                    if (cmd.equals("restart")) {
                        c = 29;
                        break;
                    }
                    c = 65535;
                    break;
                case 1129261387:
                    if (cmd.equals("update-appinfo")) {
                        c = '<';
                        break;
                    }
                    c = 65535;
                    break;
                case 1219773618:
                    if (cmd.equals("get-started-user-state")) {
                        c = '*';
                        break;
                    }
                    c = 65535;
                    break;
                case 1236319578:
                    if (cmd.equals("monitor")) {
                        c = 26;
                        break;
                    }
                    c = 65535;
                    break;
                case 1395483623:
                    if (cmd.equals("instrument")) {
                        c = 11;
                        break;
                    }
                    c = 65535;
                    break;
                case 1583986358:
                    if (cmd.equals("stop-user")) {
                        c = '(';
                        break;
                    }
                    c = 65535;
                    break;
                case 1618908732:
                    if (cmd.equals("wait-for-broadcast-idle")) {
                        c = '>';
                        break;
                    }
                    c = 65535;
                    break;
                case 1671764162:
                    if (cmd.equals("display")) {
                        c = '5';
                        break;
                    }
                    c = 65535;
                    break;
                case 1852789518:
                    if (cmd.equals("no-home-screen")) {
                        c = '=';
                        break;
                    }
                    c = 65535;
                    break;
                case 1861559962:
                    if (cmd.equals("idle-maintenance")) {
                        c = 30;
                        break;
                    }
                    c = 65535;
                    break;
                case 1863290858:
                    if (cmd.equals("stop-service")) {
                        c = '\t';
                        break;
                    }
                    c = 65535;
                    break;
                case 2083239620:
                    if (cmd.equals("switch-user")) {
                        c = '$';
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
                case 1:
                    return runStartActivity(pw);
                case 2:
                case 3:
                    return runStartService(pw, false);
                case 4:
                case 5:
                case 6:
                case 7:
                    return runStartService(pw, true);
                case '\b':
                case '\t':
                    return runStopService(pw);
                case '\n':
                    return runSendBroadcast(pw);
                case 11:
                    getOutPrintWriter().println("Error: must be invoked through 'am instrument'.");
                    return -1;
                case '\f':
                    return runTraceIpc(pw);
                case '\r':
                    return runProfile(pw);
                case 14:
                    return runDumpHeap(pw);
                case 15:
                    return runSetDebugApp(pw);
                case 16:
                    return runSetAgentApp(pw);
                case 17:
                    return runClearDebugApp(pw);
                case 18:
                    return runSetWatchHeap(pw);
                case 19:
                    return runClearWatchHeap(pw);
                case 20:
                    return runBugReport(pw);
                case 21:
                    return runForceStop(pw);
                case 22:
                    return runCrash(pw);
                case 23:
                    return runKill(pw);
                case WindowManagerService.H.WAITING_FOR_DRAWN_TIMEOUT /* 24 */:
                    return runKillAll(pw);
                case WindowManagerService.H.SHOW_STRICT_MODE_VIOLATION /* 25 */:
                    return runMakeIdle(pw);
                case 26:
                    return runMonitor(pw);
                case 27:
                    return runWatchUids(pw);
                case 28:
                    return runHang(pw);
                case HdmiCecKeycode.CEC_KEYCODE_NUMBER_ENTRY_MODE /* 29 */:
                    return runRestart(pw);
                case 30:
                    return runIdleMaintenance(pw);
                case HdmiCecKeycode.CEC_KEYCODE_NUMBER_12 /* 31 */:
                    return runScreenCompat(pw);
                case ' ':
                    return runPackageImportance(pw);
                case '!':
                    return runToUri(pw, 0);
                case '\"':
                    return runToUri(pw, 1);
                case '#':
                    return runToUri(pw, 2);
                case '$':
                    return runSwitchUser(pw);
                case '%':
                    return runGetCurrentUser(pw);
                case '&':
                    return runStartUser(pw);
                case '\'':
                    return runUnlockUser(pw);
                case '(':
                    return runStopUser(pw);
                case ')':
                    return runIsUserStopped(pw);
                case '*':
                    return runGetStartedUserState(pw);
                case '+':
                    return runTrackAssociations(pw);
                case ',':
                    return runUntrackAssociations(pw);
                case NetworkPolicyManagerService.TYPE_RAPID /* 45 */:
                    return getUidState(pw);
                case WindowManagerService.H.WINDOW_REPLACEMENT_TIMEOUT /* 46 */:
                    return runGetConfig(pw);
                case HdmiCecKeycode.CEC_KEYCODE_NEXT_FAVORITE /* 47 */:
                    return runSuppressResizeConfigChanges(pw);
                case '0':
                    return runSetInactive(pw);
                case HdmiCecKeycode.CEC_KEYCODE_CHANNEL_DOWN /* 49 */:
                    return runGetInactive(pw);
                case HdmiCecKeycode.CEC_KEYCODE_PREVIOUS_CHANNEL /* 50 */:
                    return runSetStandbyBucket(pw);
                case '3':
                    return runGetStandbyBucket(pw);
                case '4':
                    return runSendTrimMemory(pw);
                case '5':
                    return runDisplay(pw);
                case '6':
                    return runStack(pw);
                case '7':
                    return runTask(pw);
                case '8':
                    return runWrite(pw);
                case '9':
                    return runAttachAgent(pw);
                case WindowManagerService.H.SET_HAS_OVERLAY_UI /* 58 */:
                    return runSupportsMultiwindow(pw);
                case ';':
                    return runSupportsSplitScreenMultiwindow(pw);
                case WindowManagerService.H.ANIMATION_FAILSAFE /* 60 */:
                    return runUpdateApplicationInfo(pw);
                case WindowManagerService.H.RECOMPUTE_FOCUS /* 61 */:
                    return runNoHomeScreen(pw);
                case WindowManagerService.H.ON_POINTER_DOWN_OUTSIDE_FOCUS /* 62 */:
                    return runWaitForBroadcastIdle(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
            return -1;
        }
    }

    private Intent makeIntent(int defUser) throws URISyntaxException {
        this.mStartFlags = 0;
        this.mWaitOption = false;
        this.mStopOption = false;
        this.mRepeat = 0;
        this.mProfileFile = null;
        this.mSamplingInterval = 0;
        this.mAutoStop = false;
        this.mStreaming = false;
        this.mUserId = defUser;
        this.mDisplayId = -1;
        this.mWindowingMode = 0;
        this.mActivityType = 0;
        this.mTaskId = -1;
        this.mIsTaskOverlay = false;
        this.mIsLockTask = false;
        return Intent.parseCommandArgs(this, new Intent.CommandOptionHandler() { // from class: com.android.server.am.ActivityManagerShellCommand.1
            public boolean handleOption(String opt, ShellCommand cmd) {
                if (opt.equals("-D")) {
                    ActivityManagerShellCommand.access$076(ActivityManagerShellCommand.this, 2);
                } else if (opt.equals("-N")) {
                    ActivityManagerShellCommand.access$076(ActivityManagerShellCommand.this, 8);
                } else if (opt.equals("-W")) {
                    ActivityManagerShellCommand.this.mWaitOption = true;
                } else if (opt.equals("-P")) {
                    ActivityManagerShellCommand activityManagerShellCommand = ActivityManagerShellCommand.this;
                    activityManagerShellCommand.mProfileFile = activityManagerShellCommand.getNextArgRequired();
                    ActivityManagerShellCommand.this.mAutoStop = true;
                } else if (opt.equals("--start-profiler")) {
                    ActivityManagerShellCommand activityManagerShellCommand2 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand2.mProfileFile = activityManagerShellCommand2.getNextArgRequired();
                    ActivityManagerShellCommand.this.mAutoStop = false;
                } else if (opt.equals("--sampling")) {
                    ActivityManagerShellCommand activityManagerShellCommand3 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand3.mSamplingInterval = Integer.parseInt(activityManagerShellCommand3.getNextArgRequired());
                } else if (opt.equals("--streaming")) {
                    ActivityManagerShellCommand.this.mStreaming = true;
                } else if (opt.equals("--attach-agent")) {
                    if (ActivityManagerShellCommand.this.mAgent != null) {
                        cmd.getErrPrintWriter().println("Multiple --attach-agent(-bind) not supported");
                        return false;
                    }
                    ActivityManagerShellCommand activityManagerShellCommand4 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand4.mAgent = activityManagerShellCommand4.getNextArgRequired();
                    ActivityManagerShellCommand.this.mAttachAgentDuringBind = false;
                } else if (opt.equals("--attach-agent-bind")) {
                    if (ActivityManagerShellCommand.this.mAgent != null) {
                        cmd.getErrPrintWriter().println("Multiple --attach-agent(-bind) not supported");
                        return false;
                    }
                    ActivityManagerShellCommand activityManagerShellCommand5 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand5.mAgent = activityManagerShellCommand5.getNextArgRequired();
                    ActivityManagerShellCommand.this.mAttachAgentDuringBind = true;
                } else if (opt.equals("-R")) {
                    ActivityManagerShellCommand activityManagerShellCommand6 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand6.mRepeat = Integer.parseInt(activityManagerShellCommand6.getNextArgRequired());
                } else if (opt.equals("-S")) {
                    ActivityManagerShellCommand.this.mStopOption = true;
                } else if (opt.equals("--track-allocation")) {
                    ActivityManagerShellCommand.access$076(ActivityManagerShellCommand.this, 4);
                } else if (opt.equals("--user")) {
                    ActivityManagerShellCommand activityManagerShellCommand7 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand7.mUserId = UserHandle.parseUserArg(activityManagerShellCommand7.getNextArgRequired());
                } else if (opt.equals("--receiver-permission")) {
                    ActivityManagerShellCommand activityManagerShellCommand8 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand8.mReceiverPermission = activityManagerShellCommand8.getNextArgRequired();
                } else if (opt.equals("--display")) {
                    ActivityManagerShellCommand activityManagerShellCommand9 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand9.mDisplayId = Integer.parseInt(activityManagerShellCommand9.getNextArgRequired());
                } else if (opt.equals("--windowingMode")) {
                    ActivityManagerShellCommand activityManagerShellCommand10 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand10.mWindowingMode = Integer.parseInt(activityManagerShellCommand10.getNextArgRequired());
                } else if (opt.equals("--activityType")) {
                    ActivityManagerShellCommand activityManagerShellCommand11 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand11.mActivityType = Integer.parseInt(activityManagerShellCommand11.getNextArgRequired());
                } else if (opt.equals("--task")) {
                    ActivityManagerShellCommand activityManagerShellCommand12 = ActivityManagerShellCommand.this;
                    activityManagerShellCommand12.mTaskId = Integer.parseInt(activityManagerShellCommand12.getNextArgRequired());
                } else if (opt.equals("--task-overlay")) {
                    ActivityManagerShellCommand.this.mIsTaskOverlay = true;
                } else if (!opt.equals("--lock-task")) {
                    return false;
                } else {
                    ActivityManagerShellCommand.this.mIsLockTask = true;
                }
                return true;
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ProgressWaiter extends IProgressListener.Stub {
        private final CountDownLatch mFinishedLatch;

        private ProgressWaiter() {
            this.mFinishedLatch = new CountDownLatch(1);
        }

        public void onStarted(int id, Bundle extras) {
        }

        public void onProgress(int id, int progress, Bundle extras) {
        }

        public void onFinished(int id, Bundle extras) {
            this.mFinishedLatch.countDown();
        }

        public boolean waitForFinish(long timeoutMillis) {
            try {
                return this.mFinishedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                System.err.println("Thread interrupted unexpectedly.");
                return false;
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:36:0x00d5, code lost:
        getErrPrintWriter().println("Error: Intent does not match any activities: " + r0);
     */
    /* JADX WARN: Code restructure failed: missing block: B:37:0x00ed, code lost:
        return r13;
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r13v0 */
    /* JADX WARN: Type inference failed for: r13v1, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r13v3 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    int runStartActivity(java.io.PrintWriter r25) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 930
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerShellCommand.runStartActivity(java.io.PrintWriter):int");
    }

    int runStartService(PrintWriter pw, boolean asForeground) throws RemoteException {
        PrintWriter err = getErrPrintWriter();
        try {
            Intent intent = makeIntent(-2);
            if (this.mUserId == -1) {
                err.println("Error: Can't start activity with user 'all'");
                return -1;
            }
            pw.println("Starting service: " + intent);
            pw.flush();
            ComponentName cn = this.mInterface.startService((IApplicationThread) null, intent, intent.getType(), asForeground, SHELL_PACKAGE_NAME, this.mUserId);
            if (cn == null) {
                err.println("Error: Not found; no service started.");
                return -1;
            } else if (cn.getPackageName().equals("!")) {
                err.println("Error: Requires permission " + cn.getClassName());
                return -1;
            } else if (cn.getPackageName().equals("!!")) {
                err.println("Error: " + cn.getClassName());
                return -1;
            } else if (cn.getPackageName().equals("?")) {
                err.println("Error: " + cn.getClassName());
                return -1;
            } else {
                return 0;
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    int runStopService(PrintWriter pw) throws RemoteException {
        PrintWriter err = getErrPrintWriter();
        try {
            Intent intent = makeIntent(-2);
            if (this.mUserId == -1) {
                err.println("Error: Can't stop activity with user 'all'");
                return -1;
            }
            pw.println("Stopping service: " + intent);
            pw.flush();
            int result = this.mInterface.stopService((IApplicationThread) null, intent, intent.getType(), this.mUserId);
            if (result == 0) {
                err.println("Service not stopped: was not running.");
                return -1;
            } else if (result == 1) {
                err.println("Service stopped");
                return -1;
            } else if (result == -1) {
                err.println("Error stopping service");
                return -1;
            } else {
                return 0;
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class IntentReceiver extends IIntentReceiver.Stub {
        private boolean mFinished = false;
        private final PrintWriter mPw;

        IntentReceiver(PrintWriter pw) {
            this.mPw = pw;
        }

        public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
            String line = "Broadcast completed: result=" + resultCode;
            if (data != null) {
                line = line + ", data=\"" + data + "\"";
            }
            if (extras != null) {
                line = line + ", extras: " + extras;
            }
            this.mPw.println(line);
            this.mPw.flush();
            synchronized (this) {
                this.mFinished = true;
                notifyAll();
            }
        }

        public synchronized void waitForFinish() {
            while (!this.mFinished) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    int runSendBroadcast(PrintWriter pw) throws RemoteException {
        try {
            Intent intent = makeIntent(-2);
            intent.addFlags(DumpState.DUMP_CHANGES);
            IntentReceiver receiver = new IntentReceiver(pw);
            String str = this.mReceiverPermission;
            String[] requiredPermissions = str == null ? null : new String[]{str};
            pw.println("Broadcasting: " + intent);
            pw.flush();
            this.mInterface.broadcastIntent((IApplicationThread) null, intent, (String) null, receiver, 0, (String) null, (Bundle) null, requiredPermissions, -1, (Bundle) null, true, false, this.mUserId);
            receiver.waitForFinish();
            return 0;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    int runTraceIpc(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        if (op.equals("start")) {
            return runTraceIpcStart(pw);
        }
        if (op.equals("stop")) {
            return runTraceIpcStop(pw);
        }
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Error: unknown trace ipc command '" + op + "'");
        return -1;
    }

    int runTraceIpcStart(PrintWriter pw) throws RemoteException {
        pw.println("Starting IPC tracing.");
        pw.flush();
        this.mInterface.startBinderTracking();
        return 0;
    }

    int runTraceIpcStop(PrintWriter pw) throws RemoteException {
        PrintWriter err = getErrPrintWriter();
        String filename = null;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--dump-file")) {
                    filename = getNextArgRequired();
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else if (filename == null) {
                err.println("Error: Specify filename to dump logs to.");
                return -1;
            } else {
                File file = new File(filename);
                file.delete();
                ParcelFileDescriptor fd = openFileForSystem(filename, "w");
                if (fd == null) {
                    return -1;
                }
                if (!this.mInterface.stopBinderTrackingAndDump(fd)) {
                    err.println("STOP TRACE FAILED.");
                    return -1;
                }
                pw.println("Stopped IPC tracing. Dumping logs to: " + filename);
                return 0;
            }
        }
    }

    static void removeWallOption() {
        String props = SystemProperties.get("dalvik.vm.extra-opts");
        if (props != null && props.contains("-Xprofile:wallclock")) {
            SystemProperties.set("dalvik.vm.extra-opts", props.replace("-Xprofile:wallclock", "").trim());
        }
    }

    private int runProfile(PrintWriter pw) throws RemoteException {
        boolean wall;
        String process;
        ProfilerInfo profilerInfo;
        PrintWriter err = getErrPrintWriter();
        boolean start = false;
        boolean wall2 = false;
        int userId = -2;
        this.mSamplingInterval = 0;
        this.mStreaming = false;
        String cmd = getNextArgRequired();
        if ("start".equals(cmd)) {
            start = true;
            while (true) {
                String opt = getNextOption();
                if (opt != null) {
                    if (opt.equals("--user")) {
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                    } else if (opt.equals("--wall")) {
                        wall2 = true;
                    } else if (opt.equals("--streaming")) {
                        this.mStreaming = true;
                    } else if (opt.equals("--sampling")) {
                        this.mSamplingInterval = Integer.parseInt(getNextArgRequired());
                    } else {
                        err.println("Error: Unknown option: " + opt);
                        return -1;
                    }
                } else {
                    String process2 = getNextArgRequired();
                    wall = wall2;
                    process = process2;
                    break;
                }
            }
        } else if ("stop".equals(cmd)) {
            while (true) {
                String opt2 = getNextOption();
                if (opt2 != null) {
                    if (opt2.equals("--user")) {
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                    } else {
                        err.println("Error: Unknown option: " + opt2);
                        return -1;
                    }
                } else {
                    String process3 = getNextArgRequired();
                    wall = false;
                    process = process3;
                    break;
                }
            }
        } else {
            String cmd2 = getNextArgRequired();
            if ("start".equals(cmd2)) {
                start = true;
                wall = false;
                process = cmd;
            } else if ("stop".equals(cmd2)) {
                wall = false;
                process = cmd;
            } else {
                throw new IllegalArgumentException("Profile command " + cmd + " not valid");
            }
        }
        if (userId == -1) {
            err.println("Error: Can't profile with user 'all'");
            return -1;
        }
        if (!start) {
            profilerInfo = null;
        } else {
            String profileFile = getNextArgRequired();
            ParcelFileDescriptor fd = openFileForSystem(profileFile, "w");
            if (fd == null) {
                return -1;
            }
            ProfilerInfo profilerInfo2 = new ProfilerInfo(profileFile, fd, this.mSamplingInterval, false, this.mStreaming, (String) null, false);
            profilerInfo = profilerInfo2;
        }
        if (wall) {
            try {
                String props = SystemProperties.get("dalvik.vm.extra-opts");
                if (props == null || !props.contains("-Xprofile:wallclock")) {
                    String str = props + " -Xprofile:wallclock";
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        if (this.mInterface.profileControl(process, userId, start, profilerInfo, 0)) {
            return 0;
        }
        err.println("PROFILE FAILED on process " + process);
        return -1;
    }

    int runDumpHeap(PrintWriter pw) throws RemoteException {
        String heapFile;
        PrintWriter err = getErrPrintWriter();
        int userId = -2;
        boolean runGc = false;
        boolean mallocInfo = false;
        boolean managed = true;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    if (userId == -1) {
                        err.println("Error: Can't dump heap with user 'all'");
                        return -1;
                    }
                } else if (opt.equals("-n")) {
                    managed = false;
                } else if (opt.equals("-g")) {
                    runGc = true;
                } else if (opt.equals("-m")) {
                    managed = false;
                    mallocInfo = true;
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String process = getNextArgRequired();
                String heapFile2 = getNextArg();
                if (heapFile2 != null) {
                    heapFile = heapFile2;
                } else {
                    Time t = new Time();
                    t.set(System.currentTimeMillis());
                    heapFile = "/data/local/tmp/heapdump-" + t.format("%Y%m%d-%H%M%S") + ".prof";
                }
                pw.println("File: " + heapFile);
                pw.flush();
                File file = new File(heapFile);
                file.delete();
                ParcelFileDescriptor fd = openFileForSystem(heapFile, "w");
                if (fd == null) {
                    return -1;
                }
                final CountDownLatch latch = new CountDownLatch(1);
                RemoteCallback finishCallback = new RemoteCallback(new RemoteCallback.OnResultListener() { // from class: com.android.server.am.ActivityManagerShellCommand.2
                    public void onResult(Bundle result) {
                        latch.countDown();
                    }
                }, (Handler) null);
                if (!this.mInterface.dumpHeap(process, userId, managed, mallocInfo, runGc, heapFile, fd, finishCallback)) {
                    err.println("HEAP DUMP FAILED on process " + process);
                    return -1;
                }
                pw.println("Waiting for dump to finish...");
                pw.flush();
                try {
                    latch.await();
                    return 0;
                } catch (InterruptedException e) {
                    err.println("Caught InterruptedException");
                    return 0;
                }
            }
        }
    }

    int runSetDebugApp(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        boolean persistent = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("-w")) {
                    wait = true;
                } else if (opt.equals("--persistent")) {
                    persistent = true;
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String pkg = getNextArgRequired();
                this.mInterface.setDebugApp(pkg, wait, persistent);
                return 0;
            }
        }
    }

    int runSetAgentApp(PrintWriter pw) throws RemoteException {
        String pkg = getNextArgRequired();
        String agent = getNextArg();
        this.mInterface.setAgentApp(pkg, agent);
        return 0;
    }

    int runClearDebugApp(PrintWriter pw) throws RemoteException {
        this.mInterface.setDebugApp((String) null, false, true);
        return 0;
    }

    int runSetWatchHeap(PrintWriter pw) throws RemoteException {
        String proc = getNextArgRequired();
        String limit = getNextArgRequired();
        this.mInterface.setDumpHeapDebugLimit(proc, 0, Long.parseLong(limit), (String) null);
        return 0;
    }

    int runClearWatchHeap(PrintWriter pw) throws RemoteException {
        String proc = getNextArgRequired();
        this.mInterface.setDumpHeapDebugLimit(proc, 0, -1L, (String) null);
        return 0;
    }

    int runBugReport(PrintWriter pw) throws RemoteException {
        int bugreportType = 0;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--progress")) {
                    bugreportType = 1;
                } else if (opt.equals("--telephony")) {
                    bugreportType = 4;
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                this.mInterface.requestBugReport(bugreportType);
                pw.println("Your lovely bug report is being created; please be patient.");
                return 0;
            }
        }
    }

    int runForceStop(PrintWriter pw) throws RemoteException {
        int userId = -1;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                this.mInterface.forceStopPackage(getNextArgRequired(), userId);
                return 0;
            }
        }
    }

    int runCrash(PrintWriter pw) throws RemoteException {
        int pid;
        String packageName;
        int userId = -1;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    getErrPrintWriter().println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String arg = getNextArgRequired();
                try {
                    int pid2 = Integer.parseInt(arg);
                    pid = pid2;
                    packageName = null;
                } catch (NumberFormatException e) {
                    pid = -1;
                    packageName = arg;
                }
                this.mInterface.crashApplication(-1, pid, packageName, userId, "shell-induced crash", false);
                return 0;
            }
        }
    }

    int runKill(PrintWriter pw) throws RemoteException {
        int userId = -1;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                this.mInterface.killBackgroundProcesses(getNextArgRequired(), userId);
                return 0;
            }
        }
    }

    int runKillAll(PrintWriter pw) throws RemoteException {
        this.mInterface.killAllBackgroundProcesses();
        return 0;
    }

    int runMakeIdle(PrintWriter pw) throws RemoteException {
        int userId = -1;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                this.mInterface.makePackageIdle(getNextArgRequired(), userId);
                return 0;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class MyActivityController extends IActivityController.Stub {
        static final int RESULT_ANR_DIALOG = 0;
        static final int RESULT_ANR_KILL = 1;
        static final int RESULT_ANR_WAIT = 1;
        static final int RESULT_CRASH_DIALOG = 0;
        static final int RESULT_CRASH_KILL = 1;
        static final int RESULT_DEFAULT = 0;
        static final int RESULT_EARLY_ANR_CONTINUE = 0;
        static final int RESULT_EARLY_ANR_KILL = 1;
        static final int STATE_ANR = 3;
        static final int STATE_CRASHED = 1;
        static final int STATE_EARLY_ANR = 2;
        static final int STATE_NORMAL = 0;
        final String mGdbPort;
        Process mGdbProcess;
        Thread mGdbThread;
        boolean mGotGdbPrint;
        final InputStream mInput;
        final IActivityManager mInterface;
        final boolean mMonkey;
        final PrintWriter mPw;
        int mResult;
        int mState;

        MyActivityController(IActivityManager iam, PrintWriter pw, InputStream input, String gdbPort, boolean monkey) {
            this.mInterface = iam;
            this.mPw = pw;
            this.mInput = input;
            this.mGdbPort = gdbPort;
            this.mMonkey = monkey;
        }

        public boolean activityResuming(String pkg) {
            synchronized (this) {
                PrintWriter printWriter = this.mPw;
                printWriter.println("** Activity resuming: " + pkg);
                this.mPw.flush();
            }
            return true;
        }

        public boolean activityStarting(Intent intent, String pkg) {
            synchronized (this) {
                PrintWriter printWriter = this.mPw;
                printWriter.println("** Activity starting: " + pkg);
                this.mPw.flush();
            }
            return true;
        }

        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg, long timeMillis, String stackTrace) {
            boolean z;
            synchronized (this) {
                this.mPw.println("** ERROR: PROCESS CRASHED");
                PrintWriter printWriter = this.mPw;
                printWriter.println("processName: " + processName);
                PrintWriter printWriter2 = this.mPw;
                printWriter2.println("processPid: " + pid);
                PrintWriter printWriter3 = this.mPw;
                printWriter3.println("shortMsg: " + shortMsg);
                PrintWriter printWriter4 = this.mPw;
                printWriter4.println("longMsg: " + longMsg);
                PrintWriter printWriter5 = this.mPw;
                printWriter5.println("timeMillis: " + timeMillis);
                this.mPw.println("stack:");
                this.mPw.print(stackTrace);
                this.mPw.println("#");
                this.mPw.flush();
                int result = waitControllerLocked(pid, 1);
                z = result != 1;
            }
            return z;
        }

        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            synchronized (this) {
                this.mPw.println("** ERROR: EARLY PROCESS NOT RESPONDING");
                PrintWriter printWriter = this.mPw;
                printWriter.println("processName: " + processName);
                PrintWriter printWriter2 = this.mPw;
                printWriter2.println("processPid: " + pid);
                PrintWriter printWriter3 = this.mPw;
                printWriter3.println("annotation: " + annotation);
                this.mPw.flush();
                int result = waitControllerLocked(pid, 2);
                return result == 1 ? -1 : 0;
            }
        }

        public int appNotResponding(String processName, int pid, String processStats) {
            synchronized (this) {
                this.mPw.println("** ERROR: PROCESS NOT RESPONDING");
                PrintWriter printWriter = this.mPw;
                printWriter.println("processName: " + processName);
                PrintWriter printWriter2 = this.mPw;
                printWriter2.println("processPid: " + pid);
                this.mPw.println("processStats:");
                this.mPw.print(processStats);
                this.mPw.println("#");
                this.mPw.flush();
                int result = waitControllerLocked(pid, 3);
                if (result == 1) {
                    return -1;
                }
                if (result == 1) {
                    return 1;
                }
                return 0;
            }
        }

        public int systemNotResponding(String message) {
            synchronized (this) {
                this.mPw.println("** ERROR: PROCESS NOT RESPONDING");
                PrintWriter printWriter = this.mPw;
                printWriter.println("message: " + message);
                this.mPw.println("#");
                this.mPw.println("Allowing system to die.");
                this.mPw.flush();
            }
            return -1;
        }

        void killGdbLocked() {
            this.mGotGdbPrint = false;
            if (this.mGdbProcess != null) {
                this.mPw.println("Stopping gdbserver");
                this.mPw.flush();
                this.mGdbProcess.destroy();
                this.mGdbProcess = null;
            }
            Thread thread = this.mGdbThread;
            if (thread != null) {
                thread.interrupt();
                this.mGdbThread = null;
            }
        }

        int waitControllerLocked(int pid, int state) {
            if (this.mGdbPort != null) {
                killGdbLocked();
                try {
                    PrintWriter printWriter = this.mPw;
                    printWriter.println("Starting gdbserver on port " + this.mGdbPort);
                    this.mPw.println("Do the following:");
                    PrintWriter printWriter2 = this.mPw;
                    printWriter2.println("  adb forward tcp:" + this.mGdbPort + " tcp:" + this.mGdbPort);
                    PrintWriter printWriter3 = this.mPw;
                    StringBuilder sb = new StringBuilder();
                    sb.append("  gdbclient app_process :");
                    sb.append(this.mGdbPort);
                    printWriter3.println(sb.toString());
                    this.mPw.flush();
                    Runtime runtime = Runtime.getRuntime();
                    this.mGdbProcess = runtime.exec(new String[]{"gdbserver", ":" + this.mGdbPort, "--attach", Integer.toString(pid)});
                    final InputStreamReader converter = new InputStreamReader(this.mGdbProcess.getInputStream());
                    this.mGdbThread = new Thread() { // from class: com.android.server.am.ActivityManagerShellCommand.MyActivityController.1
                        @Override // java.lang.Thread, java.lang.Runnable
                        public void run() {
                            BufferedReader in = new BufferedReader(converter);
                            int count = 0;
                            while (true) {
                                synchronized (MyActivityController.this) {
                                    if (MyActivityController.this.mGdbThread == null) {
                                        return;
                                    }
                                    if (count == 2) {
                                        MyActivityController.this.mGotGdbPrint = true;
                                        MyActivityController.this.notifyAll();
                                    }
                                    try {
                                        String line = in.readLine();
                                        if (line == null) {
                                            return;
                                        }
                                        PrintWriter printWriter4 = MyActivityController.this.mPw;
                                        printWriter4.println("GDB: " + line);
                                        MyActivityController.this.mPw.flush();
                                        count++;
                                    } catch (IOException e) {
                                        return;
                                    }
                                }
                            }
                        }
                    };
                    this.mGdbThread.start();
                    try {
                        wait(500L);
                    } catch (InterruptedException e) {
                    }
                } catch (IOException e2) {
                    PrintWriter printWriter4 = this.mPw;
                    printWriter4.println("Failure starting gdbserver: " + e2);
                    this.mPw.flush();
                    killGdbLocked();
                }
            }
            this.mState = state;
            this.mPw.println("");
            printMessageForState();
            this.mPw.flush();
            while (this.mState != 0) {
                try {
                    wait();
                } catch (InterruptedException e3) {
                }
            }
            killGdbLocked();
            return this.mResult;
        }

        void resumeController(int result) {
            synchronized (this) {
                this.mState = 0;
                this.mResult = result;
                notifyAll();
            }
        }

        void printMessageForState() {
            int i = this.mState;
            if (i == 0) {
                this.mPw.println("Monitoring activity manager...  available commands:");
            } else if (i == 1) {
                this.mPw.println("Waiting after crash...  available commands:");
                this.mPw.println("(c)ontinue: show crash dialog");
                this.mPw.println("(k)ill: immediately kill app");
            } else if (i == 2) {
                this.mPw.println("Waiting after early ANR...  available commands:");
                this.mPw.println("(c)ontinue: standard ANR processing");
                this.mPw.println("(k)ill: immediately kill app");
            } else if (i == 3) {
                this.mPw.println("Waiting after ANR...  available commands:");
                this.mPw.println("(c)ontinue: show ANR dialog");
                this.mPw.println("(k)ill: immediately kill app");
                this.mPw.println("(w)ait: wait some more");
            }
            this.mPw.println("(q)uit: finish monitoring");
        }

        void run() throws RemoteException {
            try {
                try {
                    printMessageForState();
                    this.mPw.flush();
                    this.mInterface.setActivityController(this, this.mMonkey);
                    this.mState = 0;
                    InputStreamReader converter = new InputStreamReader(this.mInput);
                    BufferedReader in = new BufferedReader(converter);
                    while (true) {
                        String line = in.readLine();
                        if (line == null) {
                            break;
                        }
                        boolean addNewline = true;
                        if (line.length() <= 0) {
                            addNewline = false;
                        } else if ("q".equals(line) || "quit".equals(line)) {
                            break;
                        } else if (this.mState == 1) {
                            if (!"c".equals(line) && !"continue".equals(line)) {
                                if (!"k".equals(line) && !"kill".equals(line)) {
                                    PrintWriter printWriter = this.mPw;
                                    printWriter.println("Invalid command: " + line);
                                }
                                resumeController(1);
                            }
                            resumeController(0);
                        } else if (this.mState == 3) {
                            if (!"c".equals(line) && !"continue".equals(line)) {
                                if (!"k".equals(line) && !"kill".equals(line)) {
                                    if (!"w".equals(line) && !"wait".equals(line)) {
                                        PrintWriter printWriter2 = this.mPw;
                                        printWriter2.println("Invalid command: " + line);
                                    }
                                    resumeController(1);
                                }
                                resumeController(1);
                            }
                            resumeController(0);
                        } else if (this.mState == 2) {
                            if (!"c".equals(line) && !"continue".equals(line)) {
                                if (!"k".equals(line) && !"kill".equals(line)) {
                                    PrintWriter printWriter3 = this.mPw;
                                    printWriter3.println("Invalid command: " + line);
                                }
                                resumeController(1);
                            }
                            resumeController(0);
                        } else {
                            PrintWriter printWriter4 = this.mPw;
                            printWriter4.println("Invalid command: " + line);
                        }
                        synchronized (this) {
                            if (addNewline) {
                                this.mPw.println("");
                            }
                            printMessageForState();
                            this.mPw.flush();
                        }
                    }
                    resumeController(0);
                } catch (IOException e) {
                    e.printStackTrace(this.mPw);
                    this.mPw.flush();
                }
            } finally {
                this.mInterface.setActivityController((IActivityController) null, this.mMonkey);
            }
        }
    }

    int runMonitor(PrintWriter pw) throws RemoteException {
        String gdbPort = null;
        boolean monkey = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--gdb")) {
                    gdbPort = getNextArgRequired();
                } else if (opt.equals("-m")) {
                    monkey = true;
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                MyActivityController controller = new MyActivityController(this.mInterface, pw, getRawInputStream(), gdbPort, monkey);
                controller.run();
                return 0;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class MyUidObserver extends IUidObserver.Stub implements ActivityManagerService.OomAdjObserver {
        static final int STATE_NORMAL = 0;
        final InputStream mInput;
        final IActivityManager mInterface;
        final ActivityManagerService mInternal;
        final PrintWriter mPw;
        int mState;
        final int mUid;

        MyUidObserver(ActivityManagerService service, PrintWriter pw, InputStream input, int uid) {
            this.mInterface = service;
            this.mInternal = service;
            this.mPw = pw;
            this.mInput = input;
            this.mUid = uid;
        }

        public void onUidStateChanged(int uid, int procState, long procStateSeq) throws RemoteException {
            synchronized (this) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                this.mPw.print(uid);
                this.mPw.print(" procstate ");
                this.mPw.print(ProcessList.makeProcStateString(procState));
                this.mPw.print(" seq ");
                this.mPw.println(procStateSeq);
                this.mPw.flush();
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }

        public void onUidGone(int uid, boolean disabled) throws RemoteException {
            synchronized (this) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                this.mPw.print(uid);
                this.mPw.print(" gone");
                if (disabled) {
                    this.mPw.print(" disabled");
                }
                this.mPw.println();
                this.mPw.flush();
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }

        public void onUidActive(int uid) throws RemoteException {
            synchronized (this) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                this.mPw.print(uid);
                this.mPw.println(" active");
                this.mPw.flush();
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }

        public void onUidIdle(int uid, boolean disabled) throws RemoteException {
            synchronized (this) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                this.mPw.print(uid);
                this.mPw.print(" idle");
                if (disabled) {
                    this.mPw.print(" disabled");
                }
                this.mPw.println();
                this.mPw.flush();
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }

        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            synchronized (this) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                this.mPw.print(uid);
                this.mPw.println(cached ? " cached" : " uncached");
                this.mPw.flush();
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }

        @Override // com.android.server.am.ActivityManagerService.OomAdjObserver
        public void onOomAdjMessage(String msg) {
            synchronized (this) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
                this.mPw.print("# ");
                this.mPw.println(msg);
                this.mPw.flush();
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }

        void printMessageForState() {
            if (this.mState == 0) {
                this.mPw.println("Watching uid states...  available commands:");
            }
            this.mPw.println("(q)uit: finish watching");
        }

        /* JADX WARN: Code restructure failed: missing block: B:27:0x007d, code lost:
            if (r7.mUid >= 0) goto L35;
         */
        /* JADX WARN: Code restructure failed: missing block: B:34:0x008f, code lost:
            if (r7.mUid < 0) goto L33;
         */
        /* JADX WARN: Code restructure failed: missing block: B:35:0x0091, code lost:
            r7.mInternal.clearOomAdjObserver();
         */
        /* JADX WARN: Code restructure failed: missing block: B:36:0x0096, code lost:
            r7.mInterface.unregisterUidObserver(r7);
         */
        /* JADX WARN: Code restructure failed: missing block: B:37:0x009c, code lost:
            return;
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        void run() throws android.os.RemoteException {
            /*
                r7 = this;
                r7.printMessageForState()     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                java.io.PrintWriter r0 = r7.mPw     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r0.flush()     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                android.app.IActivityManager r0 = r7.mInterface     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r1 = 31
                r2 = -1
                r3 = 0
                r0.registerUidObserver(r7, r1, r2, r3)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                int r0 = r7.mUid     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                if (r0 < 0) goto L1c
                com.android.server.am.ActivityManagerService r0 = r7.mInternal     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                int r1 = r7.mUid     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r0.setOomAdjObserver(r1, r7)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
            L1c:
                r0 = 0
                r7.mState = r0     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                java.io.InputStreamReader r0 = new java.io.InputStreamReader     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                java.io.InputStream r1 = r7.mInput     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r0.<init>(r1)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                java.io.BufferedReader r1 = new java.io.BufferedReader     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r1.<init>(r0)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
            L2b:
                java.lang.String r2 = r1.readLine()     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r3 = r2
                if (r2 == 0) goto L7b
                r2 = 1
                int r4 = r3.length()     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                if (r4 > 0) goto L3b
                r2 = 0
                goto L64
            L3b:
                java.lang.String r4 = "q"
                boolean r4 = r4.equals(r3)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                if (r4 != 0) goto L7b
                java.lang.String r4 = "quit"
                boolean r4 = r4.equals(r3)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                if (r4 == 0) goto L4e
                goto L7b
            L4e:
                java.io.PrintWriter r4 = r7.mPw     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r5.<init>()     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                java.lang.String r6 = "Invalid command: "
                r5.append(r6)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r5.append(r3)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                r4.println(r5)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
            L64:
                monitor-enter(r7)     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
                if (r2 == 0) goto L6e
                java.io.PrintWriter r4 = r7.mPw     // Catch: java.lang.Throwable -> L78
                java.lang.String r5 = ""
                r4.println(r5)     // Catch: java.lang.Throwable -> L78
            L6e:
                r7.printMessageForState()     // Catch: java.lang.Throwable -> L78
                java.io.PrintWriter r4 = r7.mPw     // Catch: java.lang.Throwable -> L78
                r4.flush()     // Catch: java.lang.Throwable -> L78
                monitor-exit(r7)     // Catch: java.lang.Throwable -> L78
                goto L2b
            L78:
                r4 = move-exception
                monitor-exit(r7)     // Catch: java.lang.Throwable -> L78
                throw r4     // Catch: java.lang.Throwable -> L80 java.io.IOException -> L82
            L7b:
                int r0 = r7.mUid
                if (r0 < 0) goto L96
                goto L91
            L80:
                r0 = move-exception
                goto L9d
            L82:
                r0 = move-exception
                java.io.PrintWriter r1 = r7.mPw     // Catch: java.lang.Throwable -> L80
                r0.printStackTrace(r1)     // Catch: java.lang.Throwable -> L80
                java.io.PrintWriter r1 = r7.mPw     // Catch: java.lang.Throwable -> L80
                r1.flush()     // Catch: java.lang.Throwable -> L80
                int r0 = r7.mUid
                if (r0 < 0) goto L96
            L91:
                com.android.server.am.ActivityManagerService r0 = r7.mInternal
                r0.clearOomAdjObserver()
            L96:
                android.app.IActivityManager r0 = r7.mInterface
                r0.unregisterUidObserver(r7)
                return
            L9d:
                int r1 = r7.mUid
                if (r1 < 0) goto La6
                com.android.server.am.ActivityManagerService r1 = r7.mInternal
                r1.clearOomAdjObserver()
            La6:
                android.app.IActivityManager r1 = r7.mInterface
                r1.unregisterUidObserver(r7)
                throw r0
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActivityManagerShellCommand.MyUidObserver.run():void");
        }
    }

    int runWatchUids(PrintWriter pw) throws RemoteException {
        int uid = -1;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--oom")) {
                    uid = Integer.parseInt(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                MyUidObserver controller = new MyUidObserver(this.mInternal, pw, getRawInputStream(), uid);
                controller.run();
                return 0;
            }
        }
    }

    int runHang(PrintWriter pw) throws RemoteException {
        boolean allowRestart = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--allow-restart")) {
                    allowRestart = true;
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                pw.println("Hanging the system...");
                pw.flush();
                this.mInterface.hang(new Binder(), allowRestart);
                return 0;
            }
        }
    }

    int runRestart(PrintWriter pw) throws RemoteException {
        String opt = getNextOption();
        if (opt != null) {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: Unknown option: " + opt);
            return -1;
        }
        pw.println("Restart the system...");
        pw.flush();
        this.mInterface.restart();
        return 0;
    }

    int runIdleMaintenance(PrintWriter pw) throws RemoteException {
        String opt = getNextOption();
        if (opt != null) {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: Unknown option: " + opt);
            return -1;
        }
        pw.println("Performing idle maintenance...");
        this.mInterface.sendIdleJobTrigger();
        return 0;
    }

    int runScreenCompat(PrintWriter pw) throws RemoteException {
        boolean enabled;
        int i;
        String mode = getNextArgRequired();
        if ("on".equals(mode)) {
            enabled = true;
        } else if ("off".equals(mode)) {
            enabled = false;
        } else {
            getErrPrintWriter().println("Error: enabled mode must be 'on' or 'off' at " + mode);
            return -1;
        }
        String packageName = getNextArgRequired();
        do {
            try {
                IActivityManager iActivityManager = this.mInterface;
                if (enabled) {
                    i = 1;
                } else {
                    i = 0;
                }
                iActivityManager.setPackageScreenCompatMode(packageName, i);
            } catch (RemoteException e) {
            }
            packageName = getNextArg();
        } while (packageName != null);
        return 0;
    }

    int runPackageImportance(PrintWriter pw) throws RemoteException {
        String packageName = getNextArgRequired();
        int procState = this.mInterface.getPackageProcessState(packageName, SHELL_PACKAGE_NAME);
        pw.println(ActivityManager.RunningAppProcessInfo.procStateToImportance(procState));
        return 0;
    }

    int runToUri(PrintWriter pw, int flags) throws RemoteException {
        try {
            Intent intent = makeIntent(-2);
            pw.println(intent.toUri(flags));
            return 0;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    int runSwitchUser(PrintWriter pw) throws RemoteException {
        UserManager userManager = (UserManager) this.mInternal.mContext.getSystemService(UserManager.class);
        int userSwitchable = userManager.getUserSwitchability();
        if (userSwitchable != 0) {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: " + userSwitchable);
            return -1;
        }
        String user = getNextArgRequired();
        this.mInterface.switchUser(Integer.parseInt(user));
        return 0;
    }

    int runGetCurrentUser(PrintWriter pw) throws RemoteException {
        UserInfo currentUser = (UserInfo) Preconditions.checkNotNull(this.mInterface.getCurrentUser(), "Current user not set");
        pw.println(currentUser.id);
        return 0;
    }

    int runStartUser(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if ("-w".equals(opt)) {
                    wait = true;
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: unknown option: " + opt);
                    return -1;
                }
            } else {
                int userId = Integer.parseInt(getNextArgRequired());
                IProgressListener progressWaiter = wait ? new ProgressWaiter() : null;
                boolean success = this.mInterface.startUserInBackgroundWithListener(userId, progressWaiter);
                if (wait && success) {
                    success = progressWaiter.waitForFinish(JobStatus.DEFAULT_TRIGGER_MAX_DELAY);
                }
                if (success) {
                    pw.println("Success: user started");
                    return 0;
                }
                getErrPrintWriter().println("Error: could not start user");
                return 0;
            }
        }
    }

    private static byte[] argToBytes(String arg) {
        if (arg.equals("!")) {
            return null;
        }
        return HexDump.hexStringToByteArray(arg);
    }

    int runUnlockUser(PrintWriter pw) throws RemoteException {
        int userId = Integer.parseInt(getNextArgRequired());
        byte[] token = argToBytes(getNextArgRequired());
        byte[] secret = argToBytes(getNextArgRequired());
        boolean success = this.mInterface.unlockUser(userId, token, secret, (IProgressListener) null);
        if (success) {
            pw.println("Success: user unlocked");
            return 0;
        }
        getErrPrintWriter().println("Error: could not unlock user");
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class StopUserCallback extends IStopUserCallback.Stub {
        private boolean mFinished = false;

        StopUserCallback() {
        }

        public synchronized void waitForFinish() {
            while (!this.mFinished) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        public synchronized void userStopped(int userId) {
            this.mFinished = true;
            notifyAll();
        }

        public synchronized void userStopAborted(int userId) {
            this.mFinished = true;
            notifyAll();
        }
    }

    int runStopUser(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        boolean force = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if ("-w".equals(opt)) {
                    wait = true;
                } else if ("-f".equals(opt)) {
                    force = true;
                } else {
                    getErrPrintWriter().println("Error: unknown option: " + opt);
                    return -1;
                }
            } else {
                int user = Integer.parseInt(getNextArgRequired());
                StopUserCallback callback = wait ? new StopUserCallback() : null;
                int res = this.mInterface.stopUser(user, force, callback);
                if (res != 0) {
                    String txt = "";
                    if (res == -4) {
                        txt = " (Can't stop user " + user + " - one of its related users can't be stopped)";
                    } else if (res == -3) {
                        txt = " (System user cannot be stopped)";
                    } else if (res == -2) {
                        txt = " (Can't stop current user)";
                    } else if (res == -1) {
                        txt = " (Unknown user " + user + ")";
                    }
                    getErrPrintWriter().println("Switch failed: " + res + txt);
                    return -1;
                } else if (callback != null) {
                    callback.waitForFinish();
                    return 0;
                } else {
                    return 0;
                }
            }
        }
    }

    int runIsUserStopped(PrintWriter pw) {
        int userId = UserHandle.parseUserArg(getNextArgRequired());
        boolean stopped = this.mInternal.isUserStopped(userId);
        pw.println(stopped);
        return 0;
    }

    int runGetStartedUserState(PrintWriter pw) throws RemoteException {
        this.mInternal.enforceCallingPermission("android.permission.DUMP", "runGetStartedUserState()");
        int userId = Integer.parseInt(getNextArgRequired());
        try {
            pw.println(this.mInternal.getStartedUserState(userId));
            return 0;
        } catch (NullPointerException e) {
            pw.println("User is not started: " + userId);
            return 0;
        }
    }

    int runTrackAssociations(PrintWriter pw) {
        this.mInternal.enforceCallingPermission("android.permission.SET_ACTIVITY_WATCHER", "runTrackAssociations()");
        synchronized (this.mInternal) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (!this.mInternal.mTrackingAssociations) {
                    this.mInternal.mTrackingAssociations = true;
                    pw.println("Association tracking started.");
                } else {
                    pw.println("Association tracking already enabled.");
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        return 0;
    }

    int runUntrackAssociations(PrintWriter pw) {
        this.mInternal.enforceCallingPermission("android.permission.SET_ACTIVITY_WATCHER", "runUntrackAssociations()");
        synchronized (this.mInternal) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (this.mInternal.mTrackingAssociations) {
                    this.mInternal.mTrackingAssociations = false;
                    this.mInternal.mAssociations.clear();
                    pw.println("Association tracking stopped.");
                } else {
                    pw.println("Association tracking not running.");
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        return 0;
    }

    int getUidState(PrintWriter pw) throws RemoteException {
        this.mInternal.enforceCallingPermission("android.permission.DUMP", "getUidState()");
        int state = this.mInternal.getUidState(Integer.parseInt(getNextArgRequired()));
        pw.print(state);
        pw.print(" (");
        pw.printf(DebugUtils.valueToString(ActivityManager.class, "PROCESS_STATE_", state), new Object[0]);
        pw.println(")");
        return 0;
    }

    private List<Configuration> getRecentConfigurations(int days) {
        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService("usagestats"));
        long now = System.currentTimeMillis();
        long nDaysAgo = now - ((((days * 24) * 60) * 60) * 1000);
        try {
            ParceledListSlice<ConfigurationStats> configStatsSlice = usm.queryConfigurationStats(4, nDaysAgo, now, SHELL_PACKAGE_NAME);
            if (configStatsSlice == null) {
                return Collections.emptyList();
            }
            final ArrayMap<Configuration, Integer> recentConfigs = new ArrayMap<>();
            List<ConfigurationStats> configStatsList = configStatsSlice.getList();
            int configStatsListSize = configStatsList.size();
            for (int i = 0; i < configStatsListSize; i++) {
                ConfigurationStats stats = configStatsList.get(i);
                int indexOfKey = recentConfigs.indexOfKey(stats.getConfiguration());
                if (indexOfKey < 0) {
                    recentConfigs.put(stats.getConfiguration(), Integer.valueOf(stats.getActivationCount()));
                } else {
                    recentConfigs.setValueAt(indexOfKey, Integer.valueOf(recentConfigs.valueAt(indexOfKey).intValue() + stats.getActivationCount()));
                }
            }
            Comparator<Configuration> comparator = new Comparator<Configuration>() { // from class: com.android.server.am.ActivityManagerShellCommand.3
                @Override // java.util.Comparator
                public int compare(Configuration a, Configuration b) {
                    return ((Integer) recentConfigs.get(b)).compareTo((Integer) recentConfigs.get(a));
                }
            };
            ArrayList<Configuration> configs = new ArrayList<>(recentConfigs.size());
            configs.addAll(recentConfigs.keySet());
            Collections.sort(configs, comparator);
            return configs;
        } catch (RemoteException e) {
            return Collections.emptyList();
        }
    }

    private static void addExtensionsForConfig(EGL10 egl, EGLDisplay display, EGLConfig config, int[] surfaceSize, int[] contextAttribs, Set<String> glExtensions) {
        String[] split;
        EGLContext context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs);
        if (context == EGL10.EGL_NO_CONTEXT) {
            return;
        }
        EGLSurface surface = egl.eglCreatePbufferSurface(display, config, surfaceSize);
        if (surface == EGL10.EGL_NO_SURFACE) {
            egl.eglDestroyContext(display, context);
            return;
        }
        egl.eglMakeCurrent(display, surface, surface, context);
        String extensionList = GLES10.glGetString(7939);
        if (!TextUtils.isEmpty(extensionList)) {
            for (String extension : extensionList.split(" ")) {
                glExtensions.add(extension);
            }
        }
        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        egl.eglDestroySurface(display, surface);
        egl.eglDestroyContext(display, context);
    }

    Set<String> getGlExtensionsFromDriver() {
        int i;
        int[] attrib;
        EGLConfig[] configs;
        char c;
        int[] numConfigs;
        int i2;
        Set<String> glExtensions = new HashSet<>();
        EGL10 egl = (EGL10) EGLContext.getEGL();
        if (egl == null) {
            getErrPrintWriter().println("Warning: couldn't get EGL");
            return glExtensions;
        }
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        egl.eglInitialize(display, version);
        int i3 = 1;
        int[] numConfigs2 = new int[1];
        char c2 = 0;
        if (!egl.eglGetConfigs(display, null, 0, numConfigs2)) {
            getErrPrintWriter().println("Warning: couldn't get EGL config count");
            return glExtensions;
        }
        EGLConfig[] configs2 = new EGLConfig[numConfigs2[0]];
        if (!egl.eglGetConfigs(display, configs2, numConfigs2[0], numConfigs2)) {
            getErrPrintWriter().println("Warning: couldn't get EGL configs");
            return glExtensions;
        }
        int[] surfaceSize = {12375, 1, 12374, 1, 12344};
        int[] gles2 = {12440, 2, 12344};
        int[] attrib2 = new int[1];
        int i4 = 0;
        while (i4 < numConfigs2[c2]) {
            egl.eglGetConfigAttrib(display, configs2[i4], 12327, attrib2);
            if (attrib2[c2] == 12368) {
                i = i4;
                attrib = attrib2;
                configs = configs2;
                c = c2;
                numConfigs = numConfigs2;
                i2 = i3;
            } else {
                egl.eglGetConfigAttrib(display, configs2[i4], 12339, attrib2);
                if ((attrib2[c2] & i3) == 0) {
                    i = i4;
                    attrib = attrib2;
                    configs = configs2;
                    c = c2;
                    numConfigs = numConfigs2;
                    i2 = i3;
                } else {
                    egl.eglGetConfigAttrib(display, configs2[i4], 12352, attrib2);
                    if ((attrib2[c2] & i3) == 0) {
                        i = i4;
                    } else {
                        i = i4;
                        addExtensionsForConfig(egl, display, configs2[i4], surfaceSize, null, glExtensions);
                    }
                    if ((attrib2[c2] & 4) == 0) {
                        attrib = attrib2;
                        configs = configs2;
                        c = c2;
                        numConfigs = numConfigs2;
                        i2 = i3;
                    } else {
                        attrib = attrib2;
                        configs = configs2;
                        c = c2;
                        numConfigs = numConfigs2;
                        i2 = i3;
                        addExtensionsForConfig(egl, display, configs2[i], surfaceSize, gles2, glExtensions);
                    }
                }
            }
            numConfigs2 = numConfigs;
            configs2 = configs;
            c2 = c;
            i3 = i2;
            i4 = i + 1;
            attrib2 = attrib;
        }
        egl.eglTerminate(display);
        return glExtensions;
    }

    private void writeDeviceConfig(ProtoOutputStream protoOutputStream, long fieldId, PrintWriter pw, Configuration config, DisplayMetrics displayMetrics) {
        ConfigurationInfo configInfo;
        long token = -1;
        if (protoOutputStream != null) {
            token = protoOutputStream.start(fieldId);
            protoOutputStream.write(1155346202625L, displayMetrics.widthPixels);
            protoOutputStream.write(1155346202626L, displayMetrics.heightPixels);
            protoOutputStream.write(1155346202627L, DisplayMetrics.DENSITY_DEVICE_STABLE);
        }
        if (pw != null) {
            pw.print("stable-width-px: ");
            pw.println(displayMetrics.widthPixels);
            pw.print("stable-height-px: ");
            pw.println(displayMetrics.heightPixels);
            pw.print("stable-density-dpi: ");
            pw.println(DisplayMetrics.DENSITY_DEVICE_STABLE);
        }
        MemInfoReader memreader = new MemInfoReader();
        memreader.readMemInfo();
        KeyguardManager kgm = (KeyguardManager) this.mInternal.mContext.getSystemService(KeyguardManager.class);
        if (protoOutputStream != null) {
            protoOutputStream.write(1116691496964L, memreader.getTotalSize());
            protoOutputStream.write(1133871366149L, ActivityManager.isLowRamDeviceStatic());
            protoOutputStream.write(1155346202630L, Runtime.getRuntime().availableProcessors());
            protoOutputStream.write(1133871366151L, kgm.isDeviceSecure());
        }
        if (pw != null) {
            pw.print("total-ram: ");
            pw.println(memreader.getTotalSize());
            pw.print("low-ram: ");
            pw.println(ActivityManager.isLowRamDeviceStatic());
            pw.print("max-cores: ");
            pw.println(Runtime.getRuntime().availableProcessors());
            pw.print("has-secure-screen-lock: ");
            pw.println(kgm.isDeviceSecure());
        }
        try {
            ConfigurationInfo configInfo2 = this.mTaskInterface.getDeviceConfigurationInfo();
            if (configInfo2.reqGlEsVersion != 0) {
                if (protoOutputStream != null) {
                    protoOutputStream.write(1155346202632L, configInfo2.reqGlEsVersion);
                }
                if (pw != null) {
                    pw.print("opengl-version: 0x");
                    pw.println(Integer.toHexString(configInfo2.reqGlEsVersion));
                }
            }
            Set<String> glExtensionsSet = getGlExtensionsFromDriver();
            String[] glExtensions = (String[]) glExtensionsSet.toArray(new String[glExtensionsSet.size()]);
            Arrays.sort(glExtensions);
            for (int i = 0; i < glExtensions.length; i++) {
                if (protoOutputStream != null) {
                    protoOutputStream.write(2237677961225L, glExtensions[i]);
                }
                if (pw != null) {
                    pw.print("opengl-extensions: ");
                    pw.println(glExtensions[i]);
                }
            }
            PackageManager pm = this.mInternal.mContext.getPackageManager();
            List<SharedLibraryInfo> slibs = pm.getSharedLibraries(0);
            Collections.sort(slibs, Comparator.comparing(new Function() { // from class: com.android.server.am.-$$Lambda$jVSWDZTj55yxOQmZSLdNsbUkMb4
                @Override // java.util.function.Function
                public final Object apply(Object obj) {
                    return ((SharedLibraryInfo) obj).getName();
                }
            }));
            int i2 = 0;
            while (i2 < slibs.size()) {
                if (protoOutputStream == null) {
                    configInfo = configInfo2;
                } else {
                    configInfo = configInfo2;
                    protoOutputStream.write(2237677961226L, slibs.get(i2).getName());
                }
                if (pw != null) {
                    pw.print("shared-libraries: ");
                    pw.println(slibs.get(i2).getName());
                }
                i2++;
                configInfo2 = configInfo;
            }
            FeatureInfo[] features = pm.getSystemAvailableFeatures();
            Arrays.sort(features, new Comparator() { // from class: com.android.server.am.-$$Lambda$ActivityManagerShellCommand$yu115wjRB5hvRTjVM9oePAy5cM0
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    return ActivityManagerShellCommand.lambda$writeDeviceConfig$0((FeatureInfo) obj, (FeatureInfo) obj2);
                }
            });
            for (int i3 = 0; i3 < features.length; i3++) {
                if (features[i3].name != null) {
                    if (protoOutputStream != null) {
                        protoOutputStream.write(2237677961227L, features[i3].name);
                    }
                    if (pw != null) {
                        pw.print("features: ");
                        pw.println(features[i3].name);
                    }
                }
            }
            if (protoOutputStream != null) {
                protoOutputStream.end(token);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$writeDeviceConfig$0(FeatureInfo o1, FeatureInfo o2) {
        if (o1.name == o2.name) {
            return 0;
        }
        if (o1.name == null) {
            return -1;
        }
        if (o2.name == null) {
            return 1;
        }
        return o1.name.compareTo(o2.name);
    }

    int runGetConfig(PrintWriter pw) throws RemoteException {
        List<Configuration> recentConfigs;
        int recentConfigSize;
        ProtoOutputStream proto;
        int days = -1;
        int displayId = 0;
        boolean asProto = false;
        boolean inclDevice = false;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--days")) {
                    days = Integer.parseInt(getNextArgRequired());
                    if (days <= 0) {
                        throw new IllegalArgumentException("--days must be a positive integer");
                    }
                } else if (opt.equals(PriorityDump.PROTO_ARG)) {
                    asProto = true;
                } else if (opt.equals("--device")) {
                    inclDevice = true;
                } else if (opt.equals("--display")) {
                    displayId = Integer.parseInt(getNextArgRequired());
                    if (displayId < 0) {
                        throw new IllegalArgumentException("--display must be a non-negative integer");
                    }
                } else {
                    getErrPrintWriter().println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                Configuration config = this.mInterface.getConfiguration();
                if (config == null) {
                    getErrPrintWriter().println("Activity manager has no configuration");
                    return -1;
                }
                DisplayManager dm = (DisplayManager) this.mInternal.mContext.getSystemService(DisplayManager.class);
                Display display = dm.getDisplay(displayId);
                if (display == null) {
                    getErrPrintWriter().println("Error: Display does not exist: " + displayId);
                    return -1;
                }
                DisplayMetrics metrics = new DisplayMetrics();
                display.getMetrics(metrics);
                if (asProto) {
                    ProtoOutputStream proto2 = new ProtoOutputStream(getOutFileDescriptor());
                    config.writeResConfigToProto(proto2, 1146756268033L, metrics);
                    if (!inclDevice) {
                        proto = proto2;
                    } else {
                        proto = proto2;
                        writeDeviceConfig(proto2, 1146756268034L, null, config, metrics);
                    }
                    proto.flush();
                    return 0;
                }
                pw.println("config: " + Configuration.resourceQualifierString(config, metrics));
                pw.println("abi: " + TextUtils.join(",", Build.SUPPORTED_ABIS));
                if (inclDevice) {
                    writeDeviceConfig(null, -1L, pw, config, metrics);
                }
                if (days >= 0 && (recentConfigSize = (recentConfigs = getRecentConfigurations(days)).size()) > 0) {
                    pw.println("recentConfigs:");
                    for (int i = 0; i < recentConfigSize; i++) {
                        pw.println("  config: " + Configuration.resourceQualifierString(recentConfigs.get(i)));
                    }
                    return 0;
                }
                return 0;
            }
        }
    }

    int runSuppressResizeConfigChanges(PrintWriter pw) throws RemoteException {
        boolean suppress = Boolean.valueOf(getNextArgRequired()).booleanValue();
        this.mTaskInterface.suppressResizeConfigChanges(suppress);
        return 0;
    }

    int runSetInactive(PrintWriter pw) throws RemoteException {
        int userId = -2;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String packageName = getNextArgRequired();
                String value = getNextArgRequired();
                IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService("usagestats"));
                usm.setAppInactive(packageName, Boolean.parseBoolean(value), userId);
                return 0;
            }
        }
    }

    private int bucketNameToBucketValue(String name) {
        String lower = name.toLowerCase();
        if (lower.startsWith("ac")) {
            return 10;
        }
        if (lower.startsWith("wo")) {
            return 20;
        }
        if (lower.startsWith("fr")) {
            return 30;
        }
        if (lower.startsWith("ra")) {
            return 40;
        }
        if (lower.startsWith("ne")) {
            return 50;
        }
        try {
            int bucket = Integer.parseInt(lower);
            return bucket;
        } catch (NumberFormatException e) {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: Unknown bucket: " + name);
            return -1;
        }
    }

    int runSetStandbyBucket(PrintWriter pw) throws RemoteException {
        int userId = -2;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String packageName = getNextArgRequired();
                String value = getNextArgRequired();
                int bucket = bucketNameToBucketValue(value);
                if (bucket < 0) {
                    return -1;
                }
                boolean multiple = peekNextArg() != null;
                IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService("usagestats"));
                if (!multiple) {
                    usm.setAppStandbyBucket(packageName, bucketNameToBucketValue(value), userId);
                } else {
                    ArrayList<AppStandbyInfo> bucketInfoList = new ArrayList<>();
                    bucketInfoList.add(new AppStandbyInfo(packageName, bucket));
                    while (true) {
                        String packageName2 = getNextArg();
                        if (packageName2 == null) {
                            break;
                        }
                        int bucket2 = bucketNameToBucketValue(getNextArgRequired());
                        if (bucket2 >= 0) {
                            bucketInfoList.add(new AppStandbyInfo(packageName2, bucket2));
                        }
                    }
                    ParceledListSlice<AppStandbyInfo> slice = new ParceledListSlice<>(bucketInfoList);
                    usm.setAppStandbyBuckets(slice, userId);
                }
                return 0;
            }
        }
    }

    int runGetStandbyBucket(PrintWriter pw) throws RemoteException {
        int userId = -2;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String packageName = getNextArg();
                IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService("usagestats"));
                if (packageName != null) {
                    int bucket = usm.getAppStandbyBucket(packageName, (String) null, userId);
                    pw.println(bucket);
                    return 0;
                }
                ParceledListSlice<AppStandbyInfo> buckets = usm.getAppStandbyBuckets(SHELL_PACKAGE_NAME, userId);
                for (AppStandbyInfo bucketInfo : buckets.getList()) {
                    pw.print(bucketInfo.mPackageName);
                    pw.print(": ");
                    pw.println(bucketInfo.mStandbyBucket);
                }
                return 0;
            }
        }
    }

    int runGetInactive(PrintWriter pw) throws RemoteException {
        int userId = -2;
        while (true) {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    PrintWriter errPrintWriter = getErrPrintWriter();
                    errPrintWriter.println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String packageName = getNextArgRequired();
                IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService("usagestats"));
                boolean isIdle = usm.isAppInactive(packageName, userId);
                pw.println("Idle=" + isIdle);
                return 0;
            }
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    int runSendTrimMemory(PrintWriter pw) throws RemoteException {
        char c;
        int level;
        int userId = -2;
        do {
            String opt = getNextOption();
            if (opt != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    getErrPrintWriter().println("Error: Unknown option: " + opt);
                    return -1;
                }
            } else {
                String proc = getNextArgRequired();
                String levelArg = getNextArgRequired();
                switch (levelArg.hashCode()) {
                    case -1943119297:
                        if (levelArg.equals("RUNNING_CRITICAL")) {
                            c = 5;
                            break;
                        }
                        c = 65535;
                        break;
                    case -847101650:
                        if (levelArg.equals("BACKGROUND")) {
                            c = 2;
                            break;
                        }
                        c = 65535;
                        break;
                    case -219160669:
                        if (levelArg.equals("RUNNING_MODERATE")) {
                            c = 1;
                            break;
                        }
                        c = 65535;
                        break;
                    case 163769603:
                        if (levelArg.equals("MODERATE")) {
                            c = 4;
                            break;
                        }
                        c = 65535;
                        break;
                    case 183181625:
                        if (levelArg.equals("COMPLETE")) {
                            c = 6;
                            break;
                        }
                        c = 65535;
                        break;
                    case 1072631956:
                        if (levelArg.equals("RUNNING_LOW")) {
                            c = 3;
                            break;
                        }
                        c = 65535;
                        break;
                    case 2130809258:
                        if (levelArg.equals("HIDDEN")) {
                            c = 0;
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
                        level = 20;
                        break;
                    case 1:
                        level = 5;
                        break;
                    case 2:
                        level = 40;
                        break;
                    case 3:
                        level = 10;
                        break;
                    case 4:
                        level = 60;
                        break;
                    case 5:
                        level = 15;
                        break;
                    case 6:
                        level = 80;
                        break;
                    default:
                        try {
                            level = Integer.parseInt(levelArg);
                            break;
                        } catch (NumberFormatException e) {
                            getErrPrintWriter().println("Error: Unknown level option: " + levelArg);
                            return -1;
                        }
                }
                if (this.mInterface.setProcessMemoryTrimLevel(proc, userId, level)) {
                    return 0;
                }
                getErrPrintWriter().println("Unknown error: failed to set trim level");
                return -1;
            }
        } while (userId != -1);
        getErrPrintWriter().println("Error: Can't use user 'all'");
        return -1;
    }

    int runDisplay(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        if (!((op.hashCode() == 1625698700 && op.equals("move-stack")) ? false : true)) {
            return runDisplayMoveStack(pw);
        }
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Error: unknown command '" + op + "'");
        return -1;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    int runStack(PrintWriter pw) throws RemoteException {
        char c;
        String op = getNextArgRequired();
        switch (op.hashCode()) {
            case -1551426916:
                if (op.equals("resize-animated")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case -1152140410:
                if (op.equals("resize-docked-stack")) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case -1082080869:
                if (op.equals("move-top-activity-to-pinned-stack")) {
                    c = 7;
                    break;
                }
                c = 65535;
                break;
            case -934610812:
                if (op.equals("remove")) {
                    c = '\b';
                    break;
                }
                c = 65535;
                break;
            case -934437708:
                if (op.equals("resize")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            case 3237038:
                if (op.equals("info")) {
                    c = 6;
                    break;
                }
                c = 65535;
                break;
            case 3322014:
                if (op.equals("list")) {
                    c = 5;
                    break;
                }
                c = 65535;
                break;
            case 35000878:
                if (op.equals("positiontask")) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case 1022285313:
                if (op.equals("move-task")) {
                    c = 0;
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
                return runStackMoveTask(pw);
            case 1:
                return runStackResize(pw);
            case 2:
                return runStackResizeAnimated(pw);
            case 3:
                return runStackResizeDocked(pw);
            case 4:
                return runStackPositionTask(pw);
            case 5:
                return runStackList(pw);
            case 6:
                return runStackInfo(pw);
            case 7:
                return runMoveTopActivityToPinnedStack(pw);
            case '\b':
                return runStackRemove(pw);
            default:
                getErrPrintWriter().println("Error: unknown command '" + op + "'");
                return -1;
        }
    }

    private Rect getBounds() {
        String leftStr = getNextArgRequired();
        int left = Integer.parseInt(leftStr);
        String topStr = getNextArgRequired();
        int top = Integer.parseInt(topStr);
        String rightStr = getNextArgRequired();
        int right = Integer.parseInt(rightStr);
        String bottomStr = getNextArgRequired();
        int bottom = Integer.parseInt(bottomStr);
        if (left < 0) {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: bad left arg: " + leftStr);
            return null;
        } else if (top < 0) {
            PrintWriter errPrintWriter2 = getErrPrintWriter();
            errPrintWriter2.println("Error: bad top arg: " + topStr);
            return null;
        } else if (right <= 0) {
            PrintWriter errPrintWriter3 = getErrPrintWriter();
            errPrintWriter3.println("Error: bad right arg: " + rightStr);
            return null;
        } else if (bottom <= 0) {
            PrintWriter errPrintWriter4 = getErrPrintWriter();
            errPrintWriter4.println("Error: bad bottom arg: " + bottomStr);
            return null;
        } else {
            return new Rect(left, top, right, bottom);
        }
    }

    int runDisplayMoveStack(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String displayIdStr = getNextArgRequired();
        int displayId = Integer.parseInt(displayIdStr);
        this.mTaskInterface.moveStackToDisplay(stackId, displayId);
        return 0;
    }

    int runStackMoveTask(PrintWriter pw) throws RemoteException {
        boolean toTop;
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String toTopStr = getNextArgRequired();
        if ("true".equals(toTopStr)) {
            toTop = true;
        } else if ("false".equals(toTopStr)) {
            toTop = false;
        } else {
            PrintWriter errPrintWriter = getErrPrintWriter();
            errPrintWriter.println("Error: bad toTop arg: " + toTopStr);
            return -1;
        }
        this.mTaskInterface.moveTaskToStack(taskId, stackId, toTop);
        return 0;
    }

    int runStackResize(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        Rect bounds = getBounds();
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        return resizeStack(stackId, bounds, 0);
    }

    int runStackResizeAnimated(PrintWriter pw) throws RemoteException {
        Rect bounds;
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        if ("null".equals(peekNextArg())) {
            bounds = null;
        } else {
            bounds = getBounds();
            if (bounds == null) {
                getErrPrintWriter().println("Error: invalid input bounds");
                return -1;
            }
        }
        return resizeStackUnchecked(stackId, bounds, 0, true);
    }

    int resizeStackUnchecked(int stackId, Rect bounds, int delayMs, boolean animate) throws RemoteException {
        try {
            this.mTaskInterface.resizeStack(stackId, bounds, false, false, animate, -1);
            Thread.sleep(delayMs);
            return 0;
        } catch (InterruptedException e) {
            return 0;
        }
    }

    int runStackResizeDocked(PrintWriter pw) throws RemoteException {
        Rect bounds = getBounds();
        Rect taskBounds = getBounds();
        if (bounds == null || taskBounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        this.mTaskInterface.resizeDockedStack(bounds, taskBounds, (Rect) null, (Rect) null, (Rect) null);
        return 0;
    }

    int resizeStack(int stackId, Rect bounds, int delayMs) throws RemoteException {
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        return resizeStackUnchecked(stackId, bounds, delayMs, false);
    }

    int runStackPositionTask(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String positionStr = getNextArgRequired();
        int position = Integer.parseInt(positionStr);
        this.mTaskInterface.positionTaskInStack(taskId, stackId, position);
        return 0;
    }

    int runStackList(PrintWriter pw) throws RemoteException {
        List<ActivityManager.StackInfo> stacks = this.mTaskInterface.getAllStackInfos();
        for (ActivityManager.StackInfo info : stacks) {
            pw.println(info);
        }
        return 0;
    }

    int runStackInfo(PrintWriter pw) throws RemoteException {
        int windowingMode = Integer.parseInt(getNextArgRequired());
        int activityType = Integer.parseInt(getNextArgRequired());
        ActivityManager.StackInfo info = this.mTaskInterface.getStackInfo(windowingMode, activityType);
        pw.println(info);
        return 0;
    }

    int runStackRemove(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        this.mTaskInterface.removeStack(stackId);
        return 0;
    }

    int runMoveTopActivityToPinnedStack(PrintWriter pw) throws RemoteException {
        int stackId = Integer.parseInt(getNextArgRequired());
        Rect bounds = getBounds();
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        } else if (!this.mTaskInterface.moveTopActivityToPinnedStack(stackId, bounds)) {
            getErrPrintWriter().println("Didn't move top activity to pinned stack.");
            return -1;
        } else {
            return 0;
        }
    }

    void setBoundsSide(Rect bounds, String side, int value) {
        char c;
        int hashCode = side.hashCode();
        if (hashCode == 98) {
            if (side.equals("b")) {
                c = 3;
            }
            c = 65535;
        } else if (hashCode == 108) {
            if (side.equals("l")) {
                c = 0;
            }
            c = 65535;
        } else if (hashCode != 114) {
            if (hashCode == 116 && side.equals("t")) {
                c = 2;
            }
            c = 65535;
        } else {
            if (side.equals(ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD)) {
                c = 1;
            }
            c = 65535;
        }
        if (c == 0) {
            bounds.left = value;
        } else if (c == 1) {
            bounds.right = value;
        } else if (c == 2) {
            bounds.top = value;
        } else if (c == 3) {
            bounds.bottom = value;
        } else {
            getErrPrintWriter().println("Unknown set side: " + side);
        }
    }

    int runTask(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        if (op.equals("lock")) {
            return runTaskLock(pw);
        }
        if (op.equals("resizeable")) {
            return runTaskResizeable(pw);
        }
        if (op.equals("resize")) {
            return runTaskResize(pw);
        }
        if (op.equals("focus")) {
            return runTaskFocus(pw);
        }
        PrintWriter errPrintWriter = getErrPrintWriter();
        errPrintWriter.println("Error: unknown command '" + op + "'");
        return -1;
    }

    int runTaskLock(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        if (taskIdStr.equals("stop")) {
            this.mTaskInterface.stopSystemLockTaskMode();
        } else {
            int taskId = Integer.parseInt(taskIdStr);
            this.mTaskInterface.startSystemLockTaskMode(taskId);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Activity manager is ");
        sb.append(this.mTaskInterface.isInLockTaskMode() ? "" : "not ");
        sb.append("in lockTaskMode");
        pw.println(sb.toString());
        return 0;
    }

    int runTaskResizeable(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String resizeableStr = getNextArgRequired();
        int resizeableMode = Integer.parseInt(resizeableStr);
        this.mTaskInterface.setTaskResizeable(taskId, resizeableMode);
        return 0;
    }

    int runTaskResize(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        Rect bounds = getBounds();
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        taskResize(taskId, bounds, 0, false);
        return 0;
    }

    void taskResize(int taskId, Rect bounds, int delay_ms, boolean pretendUserResize) throws RemoteException {
        this.mTaskInterface.resizeTask(taskId, bounds, pretendUserResize ? 1 : 0);
        try {
            Thread.sleep(delay_ms);
        } catch (InterruptedException e) {
        }
    }

    int moveTask(int taskId, Rect taskRect, Rect stackRect, int stepSize, int maxToTravel, boolean movingForward, boolean horizontal, int delay_ms) throws RemoteException {
        if (movingForward) {
            while (maxToTravel > 0 && ((horizontal && taskRect.right < stackRect.right) || (!horizontal && taskRect.bottom < stackRect.bottom))) {
                if (horizontal) {
                    int maxMove = Math.min(stepSize, stackRect.right - taskRect.right);
                    maxToTravel -= maxMove;
                    taskRect.right += maxMove;
                    taskRect.left += maxMove;
                } else {
                    int maxMove2 = Math.min(stepSize, stackRect.bottom - taskRect.bottom);
                    maxToTravel -= maxMove2;
                    taskRect.top += maxMove2;
                    taskRect.bottom += maxMove2;
                }
                taskResize(taskId, taskRect, delay_ms, false);
            }
        } else {
            while (maxToTravel < 0 && ((horizontal && taskRect.left > stackRect.left) || (!horizontal && taskRect.top > stackRect.top))) {
                if (horizontal) {
                    int maxMove3 = Math.min(stepSize, taskRect.left - stackRect.left);
                    maxToTravel -= maxMove3;
                    taskRect.right -= maxMove3;
                    taskRect.left -= maxMove3;
                } else {
                    int maxMove4 = Math.min(stepSize, taskRect.top - stackRect.top);
                    maxToTravel -= maxMove4;
                    taskRect.top -= maxMove4;
                    taskRect.bottom -= maxMove4;
                }
                taskResize(taskId, taskRect, delay_ms, false);
            }
        }
        return maxToTravel;
    }

    int getStepSize(int current, int target, int inStepSize, boolean greaterThanTarget) {
        int stepSize = 0;
        if (greaterThanTarget && target < current) {
            current -= inStepSize;
            stepSize = inStepSize;
            if (target > current) {
                stepSize -= target - current;
            }
        }
        if (!greaterThanTarget && target > current) {
            int current2 = current + inStepSize;
            return target < current2 ? inStepSize + (current2 - target) : inStepSize;
        }
        return stepSize;
    }

    int runTaskFocus(PrintWriter pw) throws RemoteException {
        int taskId = Integer.parseInt(getNextArgRequired());
        pw.println("Setting focus to task " + taskId);
        this.mTaskInterface.setFocusedTask(taskId);
        return 0;
    }

    int runWrite(PrintWriter pw) {
        this.mInternal.enforceCallingPermission("android.permission.SET_ACTIVITY_WATCHER", "registerUidObserver()");
        this.mInternal.mAtmInternal.flushRecentTasks();
        pw.println("All tasks persisted.");
        return 0;
    }

    int runAttachAgent(PrintWriter pw) {
        this.mInternal.enforceCallingPermission("android.permission.SET_ACTIVITY_WATCHER", "attach-agent");
        String process = getNextArgRequired();
        String agent = getNextArgRequired();
        String opt = getNextArg();
        if (opt != null) {
            pw.println("Error: Unknown option: " + opt);
            return -1;
        }
        this.mInternal.attachAgent(process, agent);
        return 0;
    }

    int runSupportsMultiwindow(PrintWriter pw) throws RemoteException {
        Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(ActivityTaskManager.supportsMultiWindow(this.mInternal.mContext));
        return 0;
    }

    int runSupportsSplitScreenMultiwindow(PrintWriter pw) throws RemoteException {
        Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(ActivityTaskManager.supportsSplitScreenMultiWindow(this.mInternal.mContext));
        return 0;
    }

    int runUpdateApplicationInfo(PrintWriter pw) throws RemoteException {
        int userid = UserHandle.parseUserArg(getNextArgRequired());
        ArrayList<String> packages = new ArrayList<>();
        packages.add(getNextArgRequired());
        while (true) {
            String packageName = getNextArg();
            if (packageName != null) {
                packages.add(packageName);
            } else {
                this.mInternal.scheduleApplicationInfoChanged(packages, userid);
                pw.println("Packages updated with most recent ApplicationInfos.");
                return 0;
            }
        }
    }

    int runNoHomeScreen(PrintWriter pw) throws RemoteException {
        Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(res.getBoolean(17891491));
        return 0;
    }

    int runWaitForBroadcastIdle(PrintWriter pw) throws RemoteException {
        this.mInternal.waitForBroadcastIdle(pw);
        return 0;
    }

    private Resources getResources(PrintWriter pw) throws RemoteException {
        Configuration config = this.mInterface.getConfiguration();
        if (config == null) {
            pw.println("Error: Activity manager has no configuration");
            return null;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        return new Resources(AssetManager.getSystem(), metrics, config);
    }

    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        dumpHelp(pw, this.mDumping);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void dumpHelp(PrintWriter pw, boolean dumping) {
        if (dumping) {
            pw.println("Activity manager dump options:");
            pw.println("  [-a] [-c] [-p PACKAGE] [-h] [WHAT] ...");
            pw.println("  WHAT may be one of:");
            pw.println("    a[ctivities]: activity stack state");
            pw.println("    r[recents]: recent activities state");
            pw.println("    b[roadcasts] [PACKAGE_NAME] [history [-s]]: broadcast state");
            pw.println("    broadcast-stats [PACKAGE_NAME]: aggregated broadcast statistics");
            pw.println("    i[ntents] [PACKAGE_NAME]: pending intent state");
            pw.println("    p[rocesses] [PACKAGE_NAME]: process state");
            pw.println("    o[om]: out of memory management");
            pw.println("    perm[issions]: URI permission grant state");
            pw.println("    prov[iders] [COMP_SPEC ...]: content provider state");
            pw.println("    provider [COMP_SPEC]: provider client-side state");
            pw.println("    s[ervices] [COMP_SPEC ...]: service state");
            pw.println("    allowed-associations: current package association restrictions");
            pw.println("    as[sociations]: tracked app associations");
            pw.println("    lmk: stats on low memory killer");
            pw.println("    lru: raw LRU process list");
            pw.println("    binder-proxies: stats on binder objects and IPCs");
            pw.println("    settings: currently applied config settings");
            pw.println("    service [COMP_SPEC]: service client-side state");
            pw.println("    package [PACKAGE_NAME]: all state related to given package");
            pw.println("    all: dump all activities");
            pw.println("    top: dump the top activity");
            pw.println("  WHAT may also be a COMP_SPEC to dump activities.");
            pw.println("  COMP_SPEC may be a component name (com.foo/.myApp),");
            pw.println("    a partial substring in a component name, a");
            pw.println("    hex object identifier.");
            pw.println("  -a: include all available server state.");
            pw.println("  -c: include client state.");
            pw.println("  -p: limit output to given package.");
            pw.println("  --checkin: output checkin format, resetting data.");
            pw.println("  --C: output checkin format, not resetting data.");
            pw.println("  --proto: output dump in protocol buffer format.");
            pw.println("  --autofill: dump just the autofill-related state of an activity");
            return;
        }
        pw.println("Activity manager (activity) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  start-activity [-D] [-N] [-W] [-P <FILE>] [--start-profiler <FILE>]");
        pw.println("          [--sampling INTERVAL] [--streaming] [-R COUNT] [-S]");
        pw.println("          [--track-allocation] [--user <USER_ID> | current] <INTENT>");
        pw.println("      Start an Activity.  Options are:");
        pw.println("      -D: enable debugging");
        pw.println("      -N: enable native debugging");
        pw.println("      -W: wait for launch to complete");
        pw.println("      --start-profiler <FILE>: start profiler and send results to <FILE>");
        pw.println("      --sampling INTERVAL: use sample profiling with INTERVAL microseconds");
        pw.println("          between samples (use with --start-profiler)");
        pw.println("      --streaming: stream the profiling output to the specified file");
        pw.println("          (use with --start-profiler)");
        pw.println("      -P <FILE>: like above, but profiling stops when app goes idle");
        pw.println("      --attach-agent <agent>: attach the given agent before binding");
        pw.println("      --attach-agent-bind <agent>: attach the given agent during binding");
        pw.println("      -R: repeat the activity launch <COUNT> times.  Prior to each repeat,");
        pw.println("          the top activity will be finished.");
        pw.println("      -S: force stop the target app before starting the activity");
        pw.println("      --track-allocation: enable tracking of object allocations");
        pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
        pw.println("          specified then run as the current user.");
        pw.println("      --windowingMode <WINDOWING_MODE>: The windowing mode to launch the activity into.");
        pw.println("      --activityType <ACTIVITY_TYPE>: The activity type to launch the activity as.");
        pw.println("      --display <DISPLAY_ID>: The display to launch the activity into.");
        pw.println("  start-service [--user <USER_ID> | current] <INTENT>");
        pw.println("      Start a Service.  Options are:");
        pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
        pw.println("          specified then run as the current user.");
        pw.println("  start-foreground-service [--user <USER_ID> | current] <INTENT>");
        pw.println("      Start a foreground Service.  Options are:");
        pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
        pw.println("          specified then run as the current user.");
        pw.println("  stop-service [--user <USER_ID> | current] <INTENT>");
        pw.println("      Stop a Service.  Options are:");
        pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
        pw.println("          specified then run as the current user.");
        pw.println("  broadcast [--user <USER_ID> | all | current] <INTENT>");
        pw.println("      Send a broadcast Intent.  Options are:");
        pw.println("      --user <USER_ID> | all | current: Specify which user to send to; if not");
        pw.println("          specified then send to all users.");
        pw.println("      --receiver-permission <PERMISSION>: Require receiver to hold permission.");
        pw.println("  instrument [-r] [-e <NAME> <VALUE>] [-p <FILE>] [-w]");
        pw.println("          [--user <USER_ID> | current] [--no-hidden-api-checks]");
        pw.println("          [--no-isolated-storage]");
        pw.println("          [--no-window-animation] [--abi <ABI>] <COMPONENT>");
        pw.println("      Start an Instrumentation.  Typically this target <COMPONENT> is in the");
        pw.println("      form <TEST_PACKAGE>/<RUNNER_CLASS> or only <TEST_PACKAGE> if there");
        pw.println("      is only one instrumentation.  Options are:");
        pw.println("      -r: print raw results (otherwise decode REPORT_KEY_STREAMRESULT).  Use with");
        pw.println("          [-e perf true] to generate raw output for performance measurements.");
        pw.println("      -e <NAME> <VALUE>: set argument <NAME> to <VALUE>.  For test runners a");
        pw.println("          common form is [-e <testrunner_flag> <value>[,<value>...]].");
        pw.println("      -p <FILE>: write profiling data to <FILE>");
        pw.println("      -m: Write output as protobuf to stdout (machine readable)");
        pw.println("      -f <Optional PATH/TO/FILE>: Write output as protobuf to a file (machine");
        pw.println("          readable). If path is not specified, default directory and file name will");
        pw.println("          be used: /sdcard/instrument-logs/log-yyyyMMdd-hhmmss-SSS.instrumentation_data_proto");
        pw.println("      -w: wait for instrumentation to finish before returning.  Required for");
        pw.println("          test runners.");
        pw.println("      --user <USER_ID> | current: Specify user instrumentation runs in;");
        pw.println("          current user if not specified.");
        pw.println("      --no-hidden-api-checks: disable restrictions on use of hidden API.");
        pw.println("      --no-isolated-storage: don't use isolated storage sandbox and ");
        pw.println("          mount full external storage");
        pw.println("      --no-window-animation: turn off window animations while running.");
        pw.println("      --abi <ABI>: Launch the instrumented process with the selected ABI.");
        pw.println("          This assumes that the process supports the selected ABI.");
        pw.println("  trace-ipc [start|stop] [--dump-file <FILE>]");
        pw.println("      Trace IPC transactions.");
        pw.println("      start: start tracing IPC transactions.");
        pw.println("      stop: stop tracing IPC transactions and dump the results to file.");
        pw.println("      --dump-file <FILE>: Specify the file the trace should be dumped to.");
        pw.println("  profile start [--user <USER_ID> current]");
        pw.println("          [--sampling INTERVAL | --streaming] <PROCESS> <FILE>");
        pw.println("      Start profiler on a process.  The given <PROCESS> argument");
        pw.println("        may be either a process name or pid.  Options are:");
        pw.println("      --user <USER_ID> | current: When supplying a process name,");
        pw.println("          specify user of process to profile; uses current user if not");
        pw.println("          specified.");
        pw.println("      --sampling INTERVAL: use sample profiling with INTERVAL microseconds");
        pw.println("          between samples.");
        pw.println("      --streaming: stream the profiling output to the specified file.");
        pw.println("  profile stop [--user <USER_ID> current] <PROCESS>");
        pw.println("      Stop profiler on a process.  The given <PROCESS> argument");
        pw.println("        may be either a process name or pid.  Options are:");
        pw.println("      --user <USER_ID> | current: When supplying a process name,");
        pw.println("          specify user of process to profile; uses current user if not");
        pw.println("          specified.");
        pw.println("  dumpheap [--user <USER_ID> current] [-n] [-g] <PROCESS> <FILE>");
        pw.println("      Dump the heap of a process.  The given <PROCESS> argument may");
        pw.println("        be either a process name or pid.  Options are:");
        pw.println("      -n: dump native heap instead of managed heap");
        pw.println("      -g: force GC before dumping the heap");
        pw.println("      --user <USER_ID> | current: When supplying a process name,");
        pw.println("          specify user of process to dump; uses current user if not specified.");
        pw.println("  set-debug-app [-w] [--persistent] <PACKAGE>");
        pw.println("      Set application <PACKAGE> to debug.  Options are:");
        pw.println("      -w: wait for debugger when application starts");
        pw.println("      --persistent: retain this value");
        pw.println("  clear-debug-app");
        pw.println("      Clear the previously set-debug-app.");
        pw.println("  set-watch-heap <PROCESS> <MEM-LIMIT>");
        pw.println("      Start monitoring pss size of <PROCESS>, if it is at or");
        pw.println("      above <HEAP-LIMIT> then a heap dump is collected for the user to report.");
        pw.println("  clear-watch-heap");
        pw.println("      Clear the previously set-watch-heap.");
        pw.println("  bug-report [--progress | --telephony]");
        pw.println("      Request bug report generation; will launch a notification");
        pw.println("        when done to select where it should be delivered. Options are:");
        pw.println("     --progress: will launch a notification right away to show its progress.");
        pw.println("     --telephony: will dump only telephony sections.");
        pw.println("  force-stop [--user <USER_ID> | all | current] <PACKAGE>");
        pw.println("      Completely stop the given application package.");
        pw.println("  crash [--user <USER_ID>] <PACKAGE|PID>");
        pw.println("      Induce a VM crash in the specified package or process");
        pw.println("  kill [--user <USER_ID> | all | current] <PACKAGE>");
        pw.println("      Kill all background processes associated with the given application.");
        pw.println("  kill-all");
        pw.println("      Kill all processes that are safe to kill (cached, etc).");
        pw.println("  make-uid-idle [--user <USER_ID> | all | current] <PACKAGE>");
        pw.println("      If the given application's uid is in the background and waiting to");
        pw.println("      become idle (not allowing background services), do that now.");
        pw.println("  monitor [--gdb <port>]");
        pw.println("      Start monitoring for crashes or ANRs.");
        pw.println("      --gdb: start gdbserv on the given port at crash/ANR");
        pw.println("  watch-uids [--oom <uid>]");
        pw.println("      Start watching for and reporting uid state changes.");
        pw.println("      --oom: specify a uid for which to report detailed change messages.");
        pw.println("  hang [--allow-restart]");
        pw.println("      Hang the system.");
        pw.println("      --allow-restart: allow watchdog to perform normal system restart");
        pw.println("  restart");
        pw.println("      Restart the user-space system.");
        pw.println("  idle-maintenance");
        pw.println("      Perform idle maintenance now.");
        pw.println("  screen-compat [on|off] <PACKAGE>");
        pw.println("      Control screen compatibility mode of <PACKAGE>.");
        pw.println("  package-importance <PACKAGE>");
        pw.println("      Print current importance of <PACKAGE>.");
        pw.println("  to-uri [INTENT]");
        pw.println("      Print the given Intent specification as a URI.");
        pw.println("  to-intent-uri [INTENT]");
        pw.println("      Print the given Intent specification as an intent: URI.");
        pw.println("  to-app-uri [INTENT]");
        pw.println("      Print the given Intent specification as an android-app: URI.");
        pw.println("  switch-user <USER_ID>");
        pw.println("      Switch to put USER_ID in the foreground, starting");
        pw.println("      execution of that user if it is currently stopped.");
        pw.println("  get-current-user");
        pw.println("      Returns id of the current foreground user.");
        pw.println("  start-user [-w] <USER_ID>");
        pw.println("      Start USER_ID in background if it is currently stopped;");
        pw.println("      use switch-user if you want to start the user in foreground.");
        pw.println("      -w: wait for start-user to complete and the user to be unlocked.");
        pw.println("  unlock-user <USER_ID> [TOKEN_HEX]");
        pw.println("      Attempt to unlock the given user using the given authorization token.");
        pw.println("  stop-user [-w] [-f] <USER_ID>");
        pw.println("      Stop execution of USER_ID, not allowing it to run any");
        pw.println("      code until a later explicit start or switch to it.");
        pw.println("      -w: wait for stop-user to complete.");
        pw.println("      -f: force stop even if there are related users that cannot be stopped.");
        pw.println("  is-user-stopped <USER_ID>");
        pw.println("      Returns whether <USER_ID> has been stopped or not.");
        pw.println("  get-started-user-state <USER_ID>");
        pw.println("      Gets the current state of the given started user.");
        pw.println("  track-associations");
        pw.println("      Enable association tracking.");
        pw.println("  untrack-associations");
        pw.println("      Disable and clear association tracking.");
        pw.println("  get-uid-state <UID>");
        pw.println("      Gets the process state of an app given its <UID>.");
        pw.println("  attach-agent <PROCESS> <FILE>");
        pw.println("    Attach an agent to the specified <PROCESS>, which may be either a process name or a PID.");
        pw.println("  get-config [--days N] [--device] [--proto] [--display <DISPLAY_ID>]");
        pw.println("      Retrieve the configuration and any recent configurations of the device.");
        pw.println("      --days: also return last N days of configurations that have been seen.");
        pw.println("      --device: also output global device configuration info.");
        pw.println("      --proto: return result as a proto; does not include --days info.");
        pw.println("      --display: Specify for which display to run the command; if not ");
        pw.println("          specified then run for the default display.");
        pw.println("  supports-multiwindow");
        pw.println("      Returns true if the device supports multiwindow.");
        pw.println("  supports-split-screen-multi-window");
        pw.println("      Returns true if the device supports split screen multiwindow.");
        pw.println("  suppress-resize-config-changes <true|false>");
        pw.println("      Suppresses configuration changes due to user resizing an activity/task.");
        pw.println("  set-inactive [--user <USER_ID>] <PACKAGE> true|false");
        pw.println("      Sets the inactive state of an app.");
        pw.println("  get-inactive [--user <USER_ID>] <PACKAGE>");
        pw.println("      Returns the inactive state of an app.");
        pw.println("  set-standby-bucket [--user <USER_ID>] <PACKAGE> active|working_set|frequent|rare");
        pw.println("      Puts an app in the standby bucket.");
        pw.println("  get-standby-bucket [--user <USER_ID>] <PACKAGE>");
        pw.println("      Returns the standby bucket of an app.");
        pw.println("  send-trim-memory [--user <USER_ID>] <PROCESS>");
        pw.println("          [HIDDEN|RUNNING_MODERATE|BACKGROUND|RUNNING_LOW|MODERATE|RUNNING_CRITICAL|COMPLETE]");
        pw.println("      Send a memory trim event to a <PROCESS>.  May also supply a raw trim int level.");
        pw.println("  display [COMMAND] [...]: sub-commands for operating on displays.");
        pw.println("       move-stack <STACK_ID> <DISPLAY_ID>");
        pw.println("           Move <STACK_ID> from its current display to <DISPLAY_ID>.");
        pw.println("  stack [COMMAND] [...]: sub-commands for operating on activity stacks.");
        pw.println("       move-task <TASK_ID> <STACK_ID> [true|false]");
        pw.println("           Move <TASK_ID> from its current stack to the top (true) or");
        pw.println("           bottom (false) of <STACK_ID>.");
        pw.println("       resize <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
        pw.println("           Change <STACK_ID> size and position to <LEFT,TOP,RIGHT,BOTTOM>.");
        pw.println("       resize-animated <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
        pw.println("           Same as resize, but allow animation.");
        pw.println("       resize-docked-stack <LEFT,TOP,RIGHT,BOTTOM> [<TASK_LEFT,TASK_TOP,TASK_RIGHT,TASK_BOTTOM>]");
        pw.println("           Change docked stack to <LEFT,TOP,RIGHT,BOTTOM>");
        pw.println("           and supplying temporary different task bounds indicated by");
        pw.println("           <TASK_LEFT,TOP,RIGHT,BOTTOM>");
        pw.println("       move-top-activity-to-pinned-stack: <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
        pw.println("           Moves the top activity from");
        pw.println("           <STACK_ID> to the pinned stack using <LEFT,TOP,RIGHT,BOTTOM> for the");
        pw.println("           bounds of the pinned stack.");
        pw.println("       positiontask <TASK_ID> <STACK_ID> <POSITION>");
        pw.println("           Place <TASK_ID> in <STACK_ID> at <POSITION>");
        pw.println("       list");
        pw.println("           List all of the activity stacks and their sizes.");
        pw.println("       info <WINDOWING_MODE> <ACTIVITY_TYPE>");
        pw.println("           Display the information about activity stack in <WINDOWING_MODE> and <ACTIVITY_TYPE>.");
        pw.println("       remove <STACK_ID>");
        pw.println("           Remove stack <STACK_ID>.");
        pw.println("  task [COMMAND] [...]: sub-commands for operating on activity tasks.");
        pw.println("       lock <TASK_ID>");
        pw.println("           Bring <TASK_ID> to the front and don't allow other tasks to run.");
        pw.println("       lock stop");
        pw.println("           End the current task lock.");
        pw.println("       resizeable <TASK_ID> [0|1|2|3]");
        pw.println("           Change resizeable mode of <TASK_ID> to one of the following:");
        pw.println("           0: unresizeable");
        pw.println("           1: crop_windows");
        pw.println("           2: resizeable");
        pw.println("           3: resizeable_and_pipable");
        pw.println("       resize <TASK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
        pw.println("           Makes sure <TASK_ID> is in a stack with the specified bounds.");
        pw.println("           Forces the task to be resizeable and creates a stack if no existing stack");
        pw.println("           has the specified bounds.");
        pw.println("  update-appinfo <USER_ID> <PACKAGE_NAME> [<PACKAGE_NAME>...]");
        pw.println("      Update the ApplicationInfo objects of the listed packages for <USER_ID>");
        pw.println("      without restarting any processes.");
        pw.println("  write");
        pw.println("      Write all pending state to storage.");
        pw.println();
        Intent.printIntentArgsHelp(pw, "");
    }
}
