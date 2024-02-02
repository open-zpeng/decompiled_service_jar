package com.android.server.wm;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.RemoteAnimationDefinition;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.policy.WindowManagerPolicy;
/* loaded from: classes.dex */
public class AppWindowContainerController extends WindowContainerController<AppWindowToken, AppWindowContainerListener> {
    private static final int STARTING_WINDOW_TYPE_NONE = 0;
    private static final int STARTING_WINDOW_TYPE_SNAPSHOT = 1;
    private static final int STARTING_WINDOW_TYPE_SPLASH_SCREEN = 2;
    private final Runnable mAddStartingWindow;
    private final Handler mHandler;
    private final Runnable mOnWindowsGone;
    private final Runnable mOnWindowsVisible;
    private final IApplicationToken mToken;

    @Override // com.android.server.wm.WindowContainerController, com.android.server.wm.ConfigurationContainerListener
    public /* bridge */ /* synthetic */ void onOverrideConfigurationChanged(Configuration configuration) {
        super.onOverrideConfigurationChanged(configuration);
    }

    /* loaded from: classes.dex */
    private final class H extends Handler {
        public static final int NOTIFY_STARTING_WINDOW_DRAWN = 2;
        public static final int NOTIFY_WINDOWS_DRAWN = 1;
        public static final int NOTIFY_WINDOWS_NOTDRAWN = 3;

        public H(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    if (AppWindowContainerController.this.mListener == 0) {
                        return;
                    }
                    if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                        Slog.v("WindowManager", "Reporting drawn in " + AppWindowContainerController.this.mToken);
                    }
                    ((AppWindowContainerListener) AppWindowContainerController.this.mListener).onWindowsDrawn(msg.getWhen());
                    return;
                case 2:
                    if (AppWindowContainerController.this.mListener == 0) {
                        return;
                    }
                    if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                        Slog.v("WindowManager", "Reporting starting window drawn in " + AppWindowContainerController.this.mToken);
                    }
                    ((AppWindowContainerListener) AppWindowContainerController.this.mListener).onStartingWindowDrawn(msg.getWhen());
                    return;
                case 3:
                    if (AppWindowContainerController.this.mListener == 0) {
                        return;
                    }
                    if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                        Slog.v("WindowManager", "Reporting undrawn in " + AppWindowContainerController.this.mToken);
                    }
                    ((AppWindowContainerListener) AppWindowContainerController.this.mListener).onWindowsNotDrawn(msg.getWhen());
                    return;
                default:
                    return;
            }
        }
    }

    public static /* synthetic */ void lambda$new$0(AppWindowContainerController appWindowContainerController) {
        if (appWindowContainerController.mListener == 0) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v("WindowManager", "Reporting visible in " + appWindowContainerController.mToken);
        }
        ((AppWindowContainerListener) appWindowContainerController.mListener).onWindowsVisible();
    }

    public static /* synthetic */ void lambda$new$1(AppWindowContainerController appWindowContainerController) {
        if (appWindowContainerController.mListener == 0) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v("WindowManager", "Reporting gone in " + appWindowContainerController.mToken);
        }
        ((AppWindowContainerListener) appWindowContainerController.mListener).onWindowsGone();
    }

    public AppWindowContainerController(TaskWindowContainerController taskController, IApplicationToken token, AppWindowContainerListener listener, int index, int requestedOrientation, boolean fullscreen, boolean showForAllUsers, int configChanges, boolean voiceInteraction, boolean launchTaskBehind, boolean alwaysFocusable, int targetSdkVersion, int rotationAnimationHint, long inputDispatchingTimeoutNanos) {
        this(taskController, token, listener, index, requestedOrientation, fullscreen, showForAllUsers, configChanges, voiceInteraction, launchTaskBehind, alwaysFocusable, targetSdkVersion, rotationAnimationHint, inputDispatchingTimeoutNanos, WindowManagerService.getInstance());
    }

    public AppWindowContainerController(TaskWindowContainerController taskController, IApplicationToken token, AppWindowContainerListener listener, int index, int requestedOrientation, boolean fullscreen, boolean showForAllUsers, int configChanges, boolean voiceInteraction, boolean launchTaskBehind, boolean alwaysFocusable, int targetSdkVersion, int rotationAnimationHint, long inputDispatchingTimeoutNanos, WindowManagerService service) {
        super(listener, service);
        WindowHashMap windowHashMap;
        StringBuilder sb;
        int i;
        this.mOnWindowsVisible = new Runnable() { // from class: com.android.server.wm.-$$Lambda$AppWindowContainerController$BD6wMjkwgPM5dckzkeLRiPrmx9Y
            @Override // java.lang.Runnable
            public final void run() {
                AppWindowContainerController.lambda$new$0(AppWindowContainerController.this);
            }
        };
        this.mOnWindowsGone = new Runnable() { // from class: com.android.server.wm.-$$Lambda$AppWindowContainerController$mZqlV7Ety8-HHzaQXVEl4hu-8mc
            @Override // java.lang.Runnable
            public final void run() {
                AppWindowContainerController.lambda$new$1(AppWindowContainerController.this);
            }
        };
        this.mAddStartingWindow = new Runnable() { // from class: com.android.server.wm.AppWindowContainerController.1
            @Override // java.lang.Runnable
            public void run() {
                synchronized (AppWindowContainerController.this.mWindowMap) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (AppWindowContainerController.this.mContainer == 0) {
                            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                                Slog.v("WindowManager", "mContainer was null while trying to add starting window");
                            }
                            return;
                        }
                        AppWindowContainerController.this.mService.mAnimationHandler.removeCallbacks(this);
                        StartingData startingData = ((AppWindowToken) AppWindowContainerController.this.mContainer).startingData;
                        AppWindowToken container = (AppWindowToken) AppWindowContainerController.this.mContainer;
                        WindowManagerService.resetPriorityAfterLockedSection();
                        if (startingData == null) {
                            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                                Slog.v("WindowManager", "startingData was nulled out before handling mAddStartingWindow: " + AppWindowContainerController.this.mContainer);
                                return;
                            }
                            return;
                        }
                        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                            Slog.v("WindowManager", "Add starting " + AppWindowContainerController.this + ": startingData=" + container.startingData);
                        }
                        WindowManagerPolicy.StartingSurface surface = null;
                        try {
                            surface = startingData.createStartingSurface(container);
                        } catch (Exception e) {
                            Slog.w("WindowManager", "Exception when adding starting window", e);
                        }
                        if (surface != null) {
                            boolean abort = false;
                            synchronized (AppWindowContainerController.this.mWindowMap) {
                                try {
                                    WindowManagerService.boostPriorityForLockedSection();
                                    if (!container.removed && container.startingData != null) {
                                        container.startingSurface = surface;
                                        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW && !abort) {
                                            Slog.v("WindowManager", "Added starting " + AppWindowContainerController.this.mContainer + ": startingWindow=" + container.startingWindow + " startingView=" + container.startingSurface);
                                        }
                                    }
                                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                                        Slog.v("WindowManager", "Aborted starting " + container + ": removed=" + container.removed + " startingData=" + container.startingData);
                                    }
                                    container.startingWindow = null;
                                    container.startingData = null;
                                    abort = true;
                                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                                        Slog.v("WindowManager", "Added starting " + AppWindowContainerController.this.mContainer + ": startingWindow=" + container.startingWindow + " startingView=" + container.startingSurface);
                                    }
                                } finally {
                                }
                            }
                            WindowManagerService.resetPriorityAfterLockedSection();
                            if (abort) {
                                surface.remove();
                            }
                        } else if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                            Slog.v("WindowManager", "Surface returned was null: " + AppWindowContainerController.this.mContainer);
                        }
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
            }
        };
        this.mHandler = new H(service.mH.getLooper());
        this.mToken = token;
        WindowHashMap windowHashMap2 = this.mWindowMap;
        synchronized (windowHashMap2) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (this.mRoot.getAppWindowToken(this.mToken.asBinder()) != null) {
                        Slog.w("WindowManager", "Attempted to add existing app token: " + this.mToken);
                    } else {
                        Task task = (Task) taskController.mContainer;
                        if (task == null) {
                            throw new IllegalArgumentException("AppWindowContainerController: invalid  controller=" + taskController);
                        }
                        windowHashMap = windowHashMap2;
                        try {
                            AppWindowToken atoken = createAppWindow(this.mService, token, voiceInteraction, task.getDisplayContent(), inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdkVersion, requestedOrientation, rotationAnimationHint, configChanges, launchTaskBehind, alwaysFocusable, this);
                            try {
                                if (!WindowManagerDebugConfig.DEBUG_TOKEN_MOVEMENT && !WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                                    i = index;
                                    task.addChild(atoken, i);
                                    WindowManagerService.resetPriorityAfterLockedSection();
                                    return;
                                }
                                sb.append(taskController);
                                sb.append(" at ");
                                i = index;
                                sb.append(i);
                                Slog.v("WindowManager", sb.toString());
                                task.addChild(atoken, i);
                                WindowManagerService.resetPriorityAfterLockedSection();
                                return;
                            } catch (Throwable th) {
                                th = th;
                                WindowManagerService.resetPriorityAfterLockedSection();
                                throw th;
                            }
                            sb = new StringBuilder();
                            sb.append("addAppToken: ");
                            sb.append(atoken);
                            sb.append(" controller=");
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            } catch (Throwable th4) {
                th = th4;
                windowHashMap = windowHashMap2;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    @VisibleForTesting
    AppWindowToken createAppWindow(WindowManagerService service, IApplicationToken token, boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos, boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation, int rotationAnimationHint, int configChanges, boolean launchTaskBehind, boolean alwaysFocusable, AppWindowContainerController controller) {
        return new AppWindowToken(service, token, voiceInteraction, dc, inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk, orientation, rotationAnimationHint, configChanges, launchTaskBehind, alwaysFocusable, controller);
    }

    public void removeContainer(int displayId) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(displayId);
                if (dc == null) {
                    Slog.w("WindowManager", "removeAppToken: Attempted to remove binder token: " + this.mToken + " from non-existing displayId=" + displayId);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                dc.removeAppToken(this.mToken.asBinder());
                super.removeContainer();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // com.android.server.wm.WindowContainerController
    public void removeContainer() {
        throw new UnsupportedOperationException("Use removeContainer(displayId) instead.");
    }

    public void reparent(TaskWindowContainerController taskController, int position) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                    Slog.i("WindowManager", "reparent: moving app token=" + this.mToken + " to task=" + taskController + " at " + position);
                }
                if (this.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                        Slog.i("WindowManager", "reparent: could not find app token=" + this.mToken);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                Task task = (Task) taskController.mContainer;
                if (task == null) {
                    throw new IllegalArgumentException("reparent: could not find task=" + taskController);
                }
                ((AppWindowToken) this.mContainer).reparent(task, position);
                ((AppWindowToken) this.mContainer).getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public Configuration setOrientation(int requestedOrientation, int displayId, Configuration displayConfig, boolean freezeScreenIfNeeded) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to set orientation of non-existing app token: " + this.mToken);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return null;
                }
                ((AppWindowToken) this.mContainer).setOrientation(requestedOrientation);
                IBinder binder = freezeScreenIfNeeded ? this.mToken.asBinder() : null;
                Configuration updateOrientationFromAppTokens = this.mService.updateOrientationFromAppTokens(displayConfig, binder, displayId);
                WindowManagerService.resetPriorityAfterLockedSection();
                return updateOrientationFromAppTokens;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public int getOrientation() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return -1;
                }
                int orientationIgnoreVisibility = ((AppWindowToken) this.mContainer).getOrientationIgnoreVisibility();
                WindowManagerService.resetPriorityAfterLockedSection();
                return orientationIgnoreVisibility;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setDisablePreviewScreenshots(boolean disable) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to set disable screenshots of non-existing app token: " + this.mToken);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((AppWindowToken) this.mContainer).setDisablePreviewScreenshots(disable);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setVisibility(boolean visible, boolean deferHidingClient) {
        WindowState win;
        AppWindowToken focusedToken;
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to set visibility of non-existing app token: " + this.mToken);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                AppWindowToken wtoken = (AppWindowToken) this.mContainer;
                if (!visible && wtoken.hiddenRequested) {
                    if (!deferHidingClient && wtoken.mDeferHidingClient) {
                        wtoken.mDeferHidingClient = deferHidingClient;
                        wtoken.setClientHidden(true);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v("WindowManager", "setAppVisibility(" + this.mToken + ", visible=" + visible + "): " + this.mService.mAppTransition + " hidden=" + wtoken.isHidden() + " hiddenRequested=" + wtoken.hiddenRequested + " Callers=" + Debug.getCallers(6));
                }
                this.mService.mOpeningApps.remove(wtoken);
                this.mService.mClosingApps.remove(wtoken);
                wtoken.waitingToShow = false;
                wtoken.hiddenRequested = !visible;
                wtoken.mDeferHidingClient = deferHidingClient;
                if (!visible) {
                    wtoken.removeDeadWindows();
                } else {
                    if (!this.mService.mAppTransition.isTransitionSet() && this.mService.mAppTransition.isReady()) {
                        this.mService.mOpeningApps.add(wtoken);
                    }
                    wtoken.startingMoved = false;
                    if (wtoken.isHidden() || wtoken.mAppStopped) {
                        wtoken.clearAllDrawn();
                        if (wtoken.isHidden()) {
                            wtoken.waitingToShow = true;
                        }
                    }
                    wtoken.setClientHidden(false);
                    wtoken.requestUpdateWallpaperIfNeeded();
                    if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                        Slog.v("WindowManager", "No longer Stopped: " + wtoken);
                    }
                    wtoken.mAppStopped = false;
                    ((AppWindowToken) this.mContainer).transferStartingWindowFromHiddenAboveTokenIfNeeded();
                }
                if (wtoken.okToAnimate() && this.mService.mAppTransition.isTransitionSet()) {
                    wtoken.inPendingTransaction = true;
                    if (visible) {
                        this.mService.mOpeningApps.add(wtoken);
                        wtoken.mEnteringAnimation = true;
                    } else {
                        this.mService.mClosingApps.add(wtoken);
                        wtoken.mEnteringAnimation = false;
                    }
                    if (this.mService.mAppTransition.getAppTransition() == 16 && (win = this.mService.getDefaultDisplayContentLocked().findFocusedWindow()) != null && (focusedToken = win.mAppToken) != null) {
                        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                            Slog.d("WindowManager", "TRANSIT_TASK_OPEN_BEHIND,  adding " + focusedToken + " to mOpeningApps");
                        }
                        focusedToken.setHidden(true);
                        this.mService.mOpeningApps.add(focusedToken);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                wtoken.setVisibility(null, visible, -1, true, wtoken.mVoiceInteraction);
                wtoken.updateReportedVisibilityLocked();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void notifyUnknownVisibilityLaunched() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    this.mService.mUnknownAppVisibilityController.notifyLaunched((AppWindowToken) this.mContainer);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX WARN: Removed duplicated region for block: B:102:0x01df  */
    /* JADX WARN: Removed duplicated region for block: B:99:0x01d9 A[Catch: all -> 0x0222, DONT_GENERATE, TRY_LEAVE, TryCatch #0 {all -> 0x0222, blocks: (B:97:0x01cf, B:99:0x01d9, B:104:0x01e2, B:107:0x01e8, B:109:0x01ec, B:110:0x01f3), top: B:125:0x01cf }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean addStartingWindow(java.lang.String r25, int r26, android.content.res.CompatibilityInfo r27, java.lang.CharSequence r28, int r29, int r30, int r31, int r32, android.os.IBinder r33, boolean r34, boolean r35, boolean r36, boolean r37, boolean r38, boolean r39) {
        /*
            Method dump skipped, instructions count: 560
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.AppWindowContainerController.addStartingWindow(java.lang.String, int, android.content.res.CompatibilityInfo, java.lang.CharSequence, int, int, int, int, android.os.IBinder, boolean, boolean, boolean, boolean, boolean, boolean):boolean");
    }

    private int getStartingWindowType(boolean newTask, boolean taskSwitch, boolean processRunning, boolean allowTaskSnapshot, boolean activityCreated, boolean fromRecents, ActivityManager.TaskSnapshot snapshot) {
        if (this.mService.mAppTransition.getAppTransition() == 19) {
            return 0;
        }
        if (newTask || !processRunning || (taskSwitch && !activityCreated)) {
            return 2;
        }
        if (taskSwitch && allowTaskSnapshot && snapshot != null) {
            if (!snapshotOrientationSameAsTask(snapshot) && !fromRecents) {
                return 2;
            }
            return 1;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleAddStartingWindow() {
        if (!this.mService.mAnimationHandler.hasCallbacks(this.mAddStartingWindow)) {
            if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                Slog.v("WindowManager", "Enqueueing ADD_STARTING");
            }
            this.mService.mAnimationHandler.postAtFrontOfQueue(this.mAddStartingWindow);
        }
    }

    private boolean createSnapshot(ActivityManager.TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v("WindowManager", "Creating SnapshotStartingData");
        }
        ((AppWindowToken) this.mContainer).startingData = new SnapshotStartingData(this.mService, snapshot);
        scheduleAddStartingWindow();
        return true;
    }

    private boolean snapshotOrientationSameAsTask(ActivityManager.TaskSnapshot snapshot) {
        return snapshot != null && ((AppWindowToken) this.mContainer).getTask().getConfiguration().orientation == snapshot.getOrientation();
    }

    public void removeStartingWindow() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (((AppWindowToken) this.mContainer).startingWindow == null) {
                    if (((AppWindowToken) this.mContainer).startingData != null) {
                        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                            Slog.v("WindowManager", "Clearing startingData for token=" + this.mContainer);
                        }
                        ((AppWindowToken) this.mContainer).startingData = null;
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (((AppWindowToken) this.mContainer).startingData != null) {
                    final WindowManagerPolicy.StartingSurface surface = ((AppWindowToken) this.mContainer).startingSurface;
                    ((AppWindowToken) this.mContainer).startingData = null;
                    ((AppWindowToken) this.mContainer).startingSurface = null;
                    ((AppWindowToken) this.mContainer).startingWindow = null;
                    ((AppWindowToken) this.mContainer).startingDisplayed = false;
                    if (surface == null) {
                        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                            Slog.v("WindowManager", "startingWindow was set but startingSurface==null, couldn't remove");
                        }
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                        Slog.v("WindowManager", "Schedule remove starting " + this.mContainer + " startingWindow=" + ((AppWindowToken) this.mContainer).startingWindow + " startingView=" + ((AppWindowToken) this.mContainer).startingSurface + " Callers=" + Debug.getCallers(5));
                    }
                    this.mService.mAnimationHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$AppWindowContainerController$8qyUV78Is6_I1WVMp6w8VGpeuOE
                        @Override // java.lang.Runnable
                        public final void run() {
                            AppWindowContainerController.lambda$removeStartingWindow$2(WindowManagerPolicy.StartingSurface.this);
                        }
                    });
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else {
                    if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
                        Slog.v("WindowManager", "Tried to remove starting window but startingWindow was null:" + this.mContainer);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$removeStartingWindow$2(WindowManagerPolicy.StartingSurface surface) {
        if (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.v("WindowManager", "Removing startingView=" + surface);
        }
        try {
            surface.remove();
        } catch (Exception e) {
            Slog.w("WindowManager", "Exception when removing starting window", e);
        }
    }

    public void pauseKeyDispatching() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    this.mService.mInputMonitor.pauseDispatchingLw((WindowToken) this.mContainer);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void resumeKeyDispatching() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    this.mService.mInputMonitor.resumeDispatchingLw((WindowToken) this.mContainer);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void notifyAppResumed(boolean wasStopped) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to notify resumed of non-existing app token: " + this.mToken);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((AppWindowToken) this.mContainer).notifyAppResumed(wasStopped);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void notifyAppStopping() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to notify stopping on non-existing app token: " + this.mToken);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((AppWindowToken) this.mContainer).detachChildren();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void notifyAppStopped() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to notify stopped of non-existing app token: " + this.mToken);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((AppWindowToken) this.mContainer).notifyAppStopped();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void startFreezingScreen(int configChanges) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to freeze screen with non-existing app token: " + this.mContainer);
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (configChanges == 0 && ((AppWindowToken) this.mContainer).okToDisplay()) {
                    if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                        Slog.v("WindowManager", "Skipping set freeze of " + this.mToken);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else {
                    ((AppWindowToken) this.mContainer).startFreezingScreen();
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void stopFreezingScreen(boolean force) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v("WindowManager", "Clear freezing of " + this.mToken + ": hidden=" + ((AppWindowToken) this.mContainer).isHidden() + " freezing=" + ((AppWindowToken) this.mContainer).isFreezingScreen());
                }
                ((AppWindowToken) this.mContainer).stopFreezingScreen(true, force);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "Attempted to register remote animations with non-existing app token: " + this.mToken);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((AppWindowToken) this.mContainer).registerRemoteAnimations(definition);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportStartingWindowDrawn() {
        this.mHandler.sendMessage(this.mHandler.obtainMessage(2));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportWindowsDrawn() {
        this.mHandler.sendMessage(this.mHandler.obtainMessage(1));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportWindowsNotDrawn() {
        this.mHandler.sendMessage(this.mHandler.obtainMessage(3));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportWindowsVisible() {
        this.mHandler.post(this.mOnWindowsVisible);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportWindowsGone() {
        this.mHandler.post(this.mOnWindowsGone);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean keyDispatchingTimedOut(String reason, int windowPid) {
        return this.mListener != 0 && ((AppWindowContainerListener) this.mListener).keyDispatchingTimedOut(reason, windowPid);
    }

    public void setWillCloseOrEnterPip(boolean willCloseOrEnterPip) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((AppWindowToken) this.mContainer).setWillCloseOrEnterPip(willCloseOrEnterPip);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public String toString() {
        return "AppWindowContainerController{ token=" + this.mToken + " mContainer=" + this.mContainer + " mListener=" + this.mListener + "}";
    }
}
