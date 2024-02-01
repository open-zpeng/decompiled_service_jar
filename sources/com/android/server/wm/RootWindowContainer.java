package com.android.server.wm;

import android.content.res.Configuration;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.EventLogTags;
import com.xiaopeng.view.SharedDisplayManager;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class RootWindowContainer extends WindowContainer<DisplayContent> implements ConfigurationContainerListener {
    private static final String KEY_BRIGHTNESS_CHANGE = "screen_brightness_change";
    private static final String KEY_PACKAGE_NAME = "screen_package_name";
    private static final String KEY_WINDOW_BRIGHTNESS = "screen_window_brightness";
    private static final int SET_SCREEN_BRIGHTNESS_OVERRIDE = 1;
    private static final int SET_USER_ACTIVITY_TIMEOUT = 2;
    private static final String TAG = "WindowManager";
    private static int mWindowBrightness = -1;
    private static final Consumer<WindowState> sRemoveReplacedWindowsConsumer = new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$Vvv8jzH2oSE9-eakZwTuKd5NpsU
        @Override // java.util.function.Consumer
        public final void accept(Object obj) {
            RootWindowContainer.lambda$static$1((WindowState) obj);
        }
    };
    private final Consumer<WindowState> mCloseSystemDialogsConsumer;
    private String mCloseSystemDialogsReason;
    private final SurfaceControl.Transaction mDisplayTransaction;
    private float mFirstWindwBrightness;
    private final Handler mHandler;
    private Session mHoldScreen;
    WindowState mHoldScreenWindow;
    private Object mLastWindowFreezeSource;
    private float mLastWindwBrightness;
    private boolean mObscureApplicationContentOnSecondaryDisplays;
    WindowState mObscuringWindow;
    boolean mOrientationChangeComplete;
    private final PowerManager mPowerManager;
    private RootActivityContainer mRootActivityContainer;
    private float mScreenBrightness;
    private boolean mSustainedPerformanceModeCurrent;
    private boolean mSustainedPerformanceModeEnabled;
    final HashMap<Integer, AppWindowToken> mTopFocusedAppByProcess;
    private int mTopFocusedDisplayId;
    private boolean mUpdateRotation;
    private long mUserActivityTimeout;
    boolean mWallpaperActionPending;

    public /* synthetic */ void lambda$new$0$RootWindowContainer(WindowState w) {
        if (w.mHasSurface) {
            try {
                w.mClient.closeSystemDialogs(this.mCloseSystemDialogsReason);
            } catch (RemoteException e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$static$1(WindowState w) {
        AppWindowToken aToken = w.mAppToken;
        if (aToken != null) {
            aToken.removeReplacedWindowIfNeeded(w);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RootWindowContainer(WindowManagerService service) {
        super(service);
        this.mLastWindowFreezeSource = null;
        this.mHoldScreen = null;
        this.mScreenBrightness = -1.0f;
        this.mFirstWindwBrightness = -1.0f;
        this.mLastWindwBrightness = -1.0f;
        this.mUserActivityTimeout = -1L;
        this.mUpdateRotation = false;
        this.mHoldScreenWindow = null;
        this.mObscuringWindow = null;
        this.mObscureApplicationContentOnSecondaryDisplays = false;
        this.mSustainedPerformanceModeEnabled = false;
        this.mSustainedPerformanceModeCurrent = false;
        this.mOrientationChangeComplete = true;
        this.mWallpaperActionPending = false;
        this.mTopFocusedDisplayId = -1;
        this.mTopFocusedAppByProcess = new HashMap<>();
        this.mDisplayTransaction = new SurfaceControl.Transaction();
        this.mCloseSystemDialogsConsumer = new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$qT2ficAmvrvFcBdiJIGNKxJ8Z9Q
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.this.lambda$new$0$RootWindowContainer((WindowState) obj);
            }
        };
        this.mHandler = new MyHandler(service.mH.getLooper());
        this.mPowerManager = (PowerManager) this.mWmService.mContext.getSystemService(PowerManager.class);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRootActivityContainer(RootActivityContainer container) {
        this.mRootActivityContainer = container;
        if (container != null) {
            container.registerConfigurationChangeListener(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateFocusedWindowLocked(int mode, boolean updateInputWindows) {
        this.mTopFocusedAppByProcess.clear();
        boolean changed = false;
        int topFocusedDisplayId = -1;
        int i = this.mChildren.size();
        while (true) {
            i--;
            if (i < 0) {
                break;
            }
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            changed |= dc.updateFocusedWindowLocked(mode, updateInputWindows, topFocusedDisplayId);
            WindowState newFocus = dc.mCurrentFocus;
            if (newFocus != null) {
                int pidOfNewFocus = newFocus.mSession.mPid;
                if (this.mTopFocusedAppByProcess.get(Integer.valueOf(pidOfNewFocus)) == null) {
                    this.mTopFocusedAppByProcess.put(Integer.valueOf(pidOfNewFocus), newFocus.mAppToken);
                }
                if (topFocusedDisplayId == -1) {
                    topFocusedDisplayId = dc.getDisplayId();
                }
            } else if (topFocusedDisplayId == -1 && dc.mFocusedApp != null) {
                topFocusedDisplayId = dc.getDisplayId();
            }
        }
        if (topFocusedDisplayId == -1) {
            topFocusedDisplayId = 0;
        }
        if (this.mTopFocusedDisplayId != topFocusedDisplayId) {
            this.mTopFocusedDisplayId = topFocusedDisplayId;
            this.mWmService.mInputManager.setFocusedDisplay(topFocusedDisplayId);
            this.mWmService.mPolicy.setTopFocusedDisplay(topFocusedDisplayId);
            if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                Slog.v(TAG, "New topFocusedDisplayId=" + topFocusedDisplayId);
            }
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent getTopFocusedDisplayContent() {
        DisplayContent dc = getDisplayContent(this.mTopFocusedDisplayId);
        return dc != null ? dc : getDisplayContent(0);
    }

    @Override // com.android.server.wm.WindowContainer
    void onChildPositionChanged() {
        this.mWmService.updateFocusedWindowLocked(0, !this.mWmService.mPerDisplayFocusEnabled);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent getDisplayContent(int displayId) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent current = (DisplayContent) this.mChildren.get(i);
            if (current.getDisplayId() == displayId) {
                return current;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent createDisplayContent(Display display, ActivityDisplay activityDisplay) {
        int displayId = display.getDisplayId();
        DisplayContent existing = getDisplayContent(displayId);
        if (existing != null) {
            existing.mAcitvityDisplay = activityDisplay;
            existing.initializeDisplayOverrideConfiguration();
            return existing;
        }
        DisplayContent dc = new DisplayContent(display, this.mWmService, activityDisplay);
        if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
            Slog.v(TAG, "Adding display=" + display);
        }
        this.mWmService.mDisplayWindowSettings.applySettingsToDisplayLocked(dc);
        dc.initializeDisplayOverrideConfiguration();
        if (this.mWmService.mDisplayManagerInternal != null) {
            this.mWmService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(displayId, dc.getDisplayInfo());
            dc.configureDisplayPolicy();
        }
        this.mWmService.reconfigureDisplayLocked(dc);
        return dc;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSettingsRetrieved() {
        int numDisplays = this.mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            DisplayContent displayContent = (DisplayContent) this.mChildren.get(displayNdx);
            boolean changed = this.mWmService.mDisplayWindowSettings.updateSettingsForDisplay(displayContent);
            if (changed) {
                displayContent.initializeDisplayOverrideConfiguration();
                this.mWmService.reconfigureDisplayLocked(displayContent);
                if (displayContent.isDefaultDisplay) {
                    Configuration newConfig = this.mWmService.computeNewConfiguration(displayContent.getDisplayId());
                    this.mWmService.mAtmService.updateConfigurationLocked(newConfig, null, false);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLayoutNeeded() {
        int numDisplays = this.mChildren.size();
        for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
            DisplayContent displayContent = (DisplayContent) this.mChildren.get(displayNdx);
            if (displayContent.isLayoutNeeded()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getVisibleWindows(final int type, final int value, final ArrayList<WindowState> output) {
        try {
            forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$c0JyWPXopJ8lfw-_jNaEte6XsDM
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    RootWindowContainer.lambda$getVisibleWindows$2(type, value, output, (WindowState) obj);
                }
            }, true);
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getVisibleWindows$2(int type, int value, ArrayList output, WindowState w) {
        boolean isMatched = true;
        boolean isDefault = true;
        boolean isVisible = w.isVisibleNow() || w.isVisible();
        if (type == -1) {
            isDefault = true;
            if (value == 0) {
                isMatched = SharedDisplayManager.isDefaultDisplay(w.mAttrs.displayId);
            } else {
                isMatched = w.mAttrs.displayId == value;
            }
        } else if (type == 0) {
            isDefault = SharedDisplayManager.isDefaultDisplay(w.mAttrs.displayId);
            isMatched = SharedDisplayManager.findScreenId(w.mAttrs.sharedId) == value;
        } else if (type == 1) {
            isDefault = SharedDisplayManager.isDefaultDisplay(w.mAttrs.displayId);
            isMatched = w.mAttrs.sharedId == value;
        }
        if (isDefault && isVisible && isMatched) {
            output.add(w);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getWindowsByType(final ArrayList<WindowState> output, final int type) {
        try {
            forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$qRonWrPi0Su0E2nh-R20QyjchTw
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    RootWindowContainer.lambda$getWindowsByType$3(type, output, (WindowState) obj);
                }
            }, true);
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getWindowsByType$3(int type, ArrayList output, WindowState w) {
        if (w.mAttrs.type == type) {
            output.add(w);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getWindowsByName(ArrayList<WindowState> output, String name) {
        int objectId = 0;
        try {
            objectId = Integer.parseInt(name, 16);
            name = null;
        } catch (RuntimeException e) {
        }
        getWindowsByName(output, name, objectId);
    }

    private void getWindowsByName(final ArrayList<WindowState> output, final String name, final int objectId) {
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$SziJIxK0Li-Rb8TfkVYVqNogZTk
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$getWindowsByName$4(name, output, objectId, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getWindowsByName$4(String name, ArrayList output, int objectId, WindowState w) {
        if (name != null) {
            if (w.mAttrs.getTitle().toString().contains(name)) {
                output.add(w);
            }
        } else if (System.identityHashCode(w) == objectId) {
            output.add(w);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnyNonToastWindowVisibleForUid(final int callingUid) {
        return forAllWindows(new ToBooleanFunction() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$reWFCw3cu-fjpkfbQC2TVoskHug
            public final boolean apply(Object obj) {
                return RootWindowContainer.lambda$isAnyNonToastWindowVisibleForUid$5(callingUid, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$isAnyNonToastWindowVisibleForUid$5(int callingUid, WindowState w) {
        return w.getOwningUid() == callingUid && w.mAttrs.type != 2005 && w.mAttrs.type != 3 && w.isVisibleNow();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowToken getAppWindowToken(IBinder binder) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            AppWindowToken atoken = dc.getAppWindowToken(binder);
            if (atoken != null) {
                return atoken;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowToken getWindowToken(IBinder binder) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            WindowToken wtoken = dc.getWindowToken(binder);
            if (wtoken != null) {
                return wtoken;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent getWindowTokenDisplay(WindowToken token) {
        if (token == null) {
            return null;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            WindowToken current = dc.getWindowToken(token.token);
            if (current == token) {
                return dc;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisplayOverrideConfigurationIfNeeded(Configuration newConfiguration, DisplayContent displayContent) {
        Configuration currentConfig = displayContent.getRequestedOverrideConfiguration();
        boolean configChanged = currentConfig.diff(newConfiguration) != 0;
        if (!configChanged) {
            return;
        }
        displayContent.onRequestedOverrideConfigurationChanged(newConfiguration);
        if (displayContent.getDisplayId() == 0) {
            setGlobalConfigurationIfNeeded(newConfiguration);
        }
    }

    private void setGlobalConfigurationIfNeeded(Configuration newConfiguration) {
        boolean configChanged = getConfiguration().diff(newConfiguration) != 0;
        if (!configChanged) {
            return;
        }
        onConfigurationChanged(newConfiguration);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        prepareFreezingTaskBounds();
        super.onConfigurationChanged(newParentConfig);
    }

    private void prepareFreezingTaskBounds() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            ((DisplayContent) this.mChildren.get(i)).prepareFreezingTaskBounds();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getStack(int windowingMode, int activityType) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            TaskStack stack = dc.getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSecureSurfaceState(final int userId, final boolean disabled) {
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$2sm3WOK4P7YPDjdTQGcu15SHG-Y
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$setSecureSurfaceState$6(userId, disabled, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setSecureSurfaceState$6(int userId, boolean disabled, WindowState w) {
        if (w.mHasSurface && userId == UserHandle.getUserId(w.mOwnerUid)) {
            w.mWinAnimator.setSecureLocked(disabled);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateHiddenWhileSuspendedState(final ArraySet<String> packages, final boolean suspended) {
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$35i52E8KuPAP5uUc9yYAwyk_fLA
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$updateHiddenWhileSuspendedState$7(packages, suspended, (WindowState) obj);
            }
        }, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$updateHiddenWhileSuspendedState$7(ArraySet packages, boolean suspended, WindowState w) {
        if (packages.contains(w.getOwningPackage())) {
            w.setHiddenWhileSuspended(suspended);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateAppOpsState() {
        forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$RpIXYFrRuUkkQ_wNc5vwa_J40Ww
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).updateAppOpsState();
            }
        }, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$canShowStrictModeViolation$9(int pid, WindowState w) {
        return w.mSession.mPid == pid && w.isVisibleLw();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowStrictModeViolation(final int pid) {
        WindowState win = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$OLRYNC72o5mvMTENnAV_p7aFKD4
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return RootWindowContainer.lambda$canShowStrictModeViolation$9(pid, (WindowState) obj);
            }
        });
        return win != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void closeSystemDialogs(String reason) {
        this.mCloseSystemDialogsReason = reason;
        forAllWindows(this.mCloseSystemDialogsConsumer, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeReplacedWindows() {
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            Slog.i(TAG, ">>> OPEN TRANSACTION removeReplacedWindows");
        }
        this.mWmService.openSurfaceTransaction();
        try {
            forAllWindows(sRemoveReplacedWindowsConsumer, true);
        } finally {
            this.mWmService.closeSurfaceTransaction("removeReplacedWindows");
            if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
                Slog.i(TAG, "<<< CLOSE TRANSACTION removeReplacedWindows");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasPendingLayoutChanges(WindowAnimator animator) {
        boolean hasChanges = false;
        int count = this.mChildren.size();
        for (int i = 0; i < count; i++) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            int pendingChanges = animator.getPendingLayoutChanges(dc.getDisplayId());
            if ((pendingChanges & 4) != 0) {
                animator.mBulkUpdateParams |= 8;
            }
            if (pendingChanges != 0) {
                hasChanges = true;
            }
        }
        return hasChanges;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean reclaimSomeSurfaceMemory(WindowStateAnimator winAnimator, String operation, boolean secure) {
        WindowSurfaceController surfaceController = winAnimator.mSurfaceController;
        boolean leakedSurface = false;
        boolean killedApps = false;
        EventLog.writeEvent((int) EventLogTags.WM_NO_SURFACE_MEMORY, winAnimator.mWin.toString(), Integer.valueOf(winAnimator.mSession.mPid), operation);
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            Slog.i(TAG, "Out of memory for surface!  Looking for leaks...");
            int numDisplays = this.mChildren.size();
            for (int displayNdx = 0; displayNdx < numDisplays; displayNdx++) {
                leakedSurface |= ((DisplayContent) this.mChildren.get(displayNdx)).destroyLeakedSurfaces();
            }
            if (!leakedSurface) {
                Slog.w(TAG, "No leaked surfaces; killing applications!");
                final SparseIntArray pidCandidates = new SparseIntArray();
                boolean killedApps2 = false;
                for (int displayNdx2 = 0; displayNdx2 < numDisplays; displayNdx2++) {
                    try {
                        ((DisplayContent) this.mChildren.get(displayNdx2)).forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$9uC7uq9ogBKxxQb7vGWNiHRPUV4
                            @Override // java.util.function.Consumer
                            public final void accept(Object obj) {
                                RootWindowContainer.this.lambda$reclaimSomeSurfaceMemory$10$RootWindowContainer(pidCandidates, (WindowState) obj);
                            }
                        }, false);
                        if (pidCandidates.size() > 0) {
                            int[] pids = new int[pidCandidates.size()];
                            for (int i = 0; i < pids.length; i++) {
                                pids[i] = pidCandidates.keyAt(i);
                            }
                            try {
                                try {
                                    if (this.mWmService.mActivityManager.killPids(pids, "Free memory", secure)) {
                                        killedApps2 = true;
                                    }
                                } catch (RemoteException e) {
                                } catch (Throwable th) {
                                    th = th;
                                    Binder.restoreCallingIdentity(callingIdentity);
                                    throw th;
                                }
                            } catch (RemoteException e2) {
                            }
                        }
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
                killedApps = killedApps2;
            }
            if (leakedSurface || killedApps) {
                try {
                    Slog.w(TAG, "Looks like we have reclaimed some memory, clearing surface for retry.");
                    if (surfaceController != null) {
                        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                            WindowManagerService.logSurface(winAnimator.mWin, "RECOVER DESTROY", false);
                        }
                        winAnimator.destroySurface();
                        if (winAnimator.mWin.mAppToken != null) {
                            winAnimator.mWin.mAppToken.removeStartingWindow();
                        }
                    }
                    try {
                        winAnimator.mWin.mClient.dispatchGetNewSurface();
                    } catch (RemoteException e3) {
                    }
                } catch (Throwable th3) {
                    th = th3;
                    Binder.restoreCallingIdentity(callingIdentity);
                    throw th;
                }
            }
            Binder.restoreCallingIdentity(callingIdentity);
            return leakedSurface || killedApps;
        } catch (Throwable th4) {
            th = th4;
        }
    }

    public /* synthetic */ void lambda$reclaimSomeSurfaceMemory$10$RootWindowContainer(SparseIntArray pidCandidates, WindowState w) {
        if (this.mWmService.mForceRemoves.contains(w)) {
            return;
        }
        WindowStateAnimator wsa = w.mWinAnimator;
        if (wsa.mSurfaceController != null) {
            pidCandidates.append(wsa.mSession.mPid, wsa.mSession.mPid);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void performSurfacePlacement(boolean recoveringMemory) {
        Trace.traceBegin(32L, "performSurfacePlacement");
        try {
            performSurfacePlacementNoTrace(recoveringMemory);
        } finally {
            Trace.traceEnd(32L);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:101:0x0229, code lost:
        if (r14.mOrientationChangeComplete == false) goto L107;
     */
    /* JADX WARN: Code restructure failed: missing block: B:103:0x022f, code lost:
        if (isLayoutNeeded() != false) goto L107;
     */
    /* JADX WARN: Code restructure failed: missing block: B:105:0x0233, code lost:
        if (r14.mUpdateRotation != false) goto L107;
     */
    /* JADX WARN: Code restructure failed: missing block: B:106:0x0235, code lost:
        r14.mWmService.checkDrawnWindowsLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:107:0x023a, code lost:
        r3 = r14.mWmService.mPendingRemove.size();
     */
    /* JADX WARN: Code restructure failed: missing block: B:108:0x0242, code lost:
        if (r3 <= 0) goto L128;
     */
    /* JADX WARN: Code restructure failed: missing block: B:110:0x0249, code lost:
        if (r14.mWmService.mPendingRemoveTmp.length >= r3) goto L112;
     */
    /* JADX WARN: Code restructure failed: missing block: B:111:0x024b, code lost:
        r14.mWmService.mPendingRemoveTmp = new com.android.server.wm.WindowState[r3 + 10];
     */
    /* JADX WARN: Code restructure failed: missing block: B:112:0x0253, code lost:
        r14.mWmService.mPendingRemove.toArray(r14.mWmService.mPendingRemoveTmp);
        r14.mWmService.mPendingRemove.clear();
        r5 = new java.util.ArrayList<>();
        r1 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:113:0x026b, code lost:
        if (r1 >= r3) goto L123;
     */
    /* JADX WARN: Code restructure failed: missing block: B:114:0x026d, code lost:
        r9 = r14.mWmService.mPendingRemoveTmp[r1];
        r9.removeImmediately();
        r10 = r9.getDisplayContent();
     */
    /* JADX WARN: Code restructure failed: missing block: B:115:0x027a, code lost:
        if (r10 == null) goto L122;
     */
    /* JADX WARN: Code restructure failed: missing block: B:117:0x0280, code lost:
        if (r5.contains(r10) != false) goto L121;
     */
    /* JADX WARN: Code restructure failed: missing block: B:118:0x0282, code lost:
        r5.add(r10);
     */
    /* JADX WARN: Code restructure failed: missing block: B:119:0x0285, code lost:
        r1 = r1 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:120:0x0288, code lost:
        r9 = r5.size() - 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:121:0x028d, code lost:
        if (r9 < 0) goto L127;
     */
    /* JADX WARN: Code restructure failed: missing block: B:122:0x028f, code lost:
        r10 = r5.get(r9);
        r10.assignWindowLayers(true);
        r9 = r9 - 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:123:0x029b, code lost:
        r5 = r14.mChildren.size() - 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:124:0x02a2, code lost:
        if (r5 < 0) goto L131;
     */
    /* JADX WARN: Code restructure failed: missing block: B:125:0x02a4, code lost:
        ((com.android.server.wm.DisplayContent) r14.mChildren.get(r5)).checkCompleteDeferredRemoval();
        r5 = r5 - 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:126:0x02b2, code lost:
        forAllDisplays(com.android.server.wm.$$Lambda$RootWindowContainer$TP5gXLWU07bdIi6cEo4IoOXPTnI.INSTANCE);
        r14.mWmService.enableScreenIfNeededLocked();
        r14.mWmService.scheduleAnimationLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:127:0x02c3, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE == false) goto L137;
     */
    /* JADX WARN: Code restructure failed: missing block: B:128:0x02c5, code lost:
        android.util.Slog.e(com.android.server.wm.RootWindowContainer.TAG, "performSurfacePlacementInner exit: animating=" + r14.mWmService.mAnimator.isAnimating());
     */
    /* JADX WARN: Code restructure failed: missing block: B:129:0x02e1, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:153:?, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:24:0x00a9, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS == false) goto L20;
     */
    /* JADX WARN: Code restructure failed: missing block: B:26:0x00ac, code lost:
        r14.mWmService.mAnimator.executeAfterPrepareSurfacesRunnables();
        checkAppTransitionReady(r7);
        r0 = r14.mWmService.getRecentsAnimationController();
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x00bc, code lost:
        if (r0 == null) goto L23;
     */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00be, code lost:
        r0.checkAnimationReady(r6.mWallpaperController);
     */
    /* JADX WARN: Code restructure failed: missing block: B:29:0x00c3, code lost:
        r1 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:30:0x00c4, code lost:
        if (r1 >= r2) goto L37;
     */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x00c6, code lost:
        r9 = (com.android.server.wm.DisplayContent) r14.mChildren.get(r1);
     */
    /* JADX WARN: Code restructure failed: missing block: B:32:0x00d0, code lost:
        if (r9.mWallpaperMayChange == false) goto L36;
     */
    /* JADX WARN: Code restructure failed: missing block: B:34:0x00d4, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT == false) goto L30;
     */
    /* JADX WARN: Code restructure failed: missing block: B:35:0x00d6, code lost:
        android.util.Slog.v(com.android.server.wm.RootWindowContainer.TAG, "Wallpaper may change!  Adjusting");
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00db, code lost:
        r9.pendingLayoutChanges |= 4;
     */
    /* JADX WARN: Code restructure failed: missing block: B:37:0x00e3, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS == false) goto L35;
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x00e5, code lost:
        r7.debugLayoutRepeats("WallpaperMayChange", r9.pendingLayoutChanges);
     */
    /* JADX WARN: Code restructure failed: missing block: B:39:0x00ec, code lost:
        r1 = r1 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:41:0x00f4, code lost:
        if (r14.mWmService.mFocusMayChange == false) goto L41;
     */
    /* JADX WARN: Code restructure failed: missing block: B:42:0x00f6, code lost:
        r14.mWmService.mFocusMayChange = false;
        r14.mWmService.updateFocusedWindowLocked(2, false);
     */
    /* JADX WARN: Code restructure failed: missing block: B:44:0x0103, code lost:
        if (isLayoutNeeded() == false) goto L46;
     */
    /* JADX WARN: Code restructure failed: missing block: B:45:0x0105, code lost:
        r6.pendingLayoutChanges |= 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:46:0x010c, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS == false) goto L46;
     */
    /* JADX WARN: Code restructure failed: missing block: B:47:0x010e, code lost:
        r7.debugLayoutRepeats("mLayoutNeeded", r6.pendingLayoutChanges);
     */
    /* JADX WARN: Code restructure failed: missing block: B:48:0x0115, code lost:
        handleResizingWindows();
     */
    /* JADX WARN: Code restructure failed: missing block: B:49:0x011a, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION == false) goto L51;
     */
    /* JADX WARN: Code restructure failed: missing block: B:51:0x0120, code lost:
        if (r14.mWmService.mDisplayFrozen == false) goto L51;
     */
    /* JADX WARN: Code restructure failed: missing block: B:52:0x0122, code lost:
        android.util.Slog.v(com.android.server.wm.RootWindowContainer.TAG, "With display frozen, orientationChangeComplete=" + r14.mOrientationChangeComplete);
     */
    /* JADX WARN: Code restructure failed: missing block: B:54:0x013a, code lost:
        if (r14.mOrientationChangeComplete == false) goto L57;
     */
    /* JADX WARN: Code restructure failed: missing block: B:56:0x0140, code lost:
        if (r14.mWmService.mWindowsFreezingScreen == 0) goto L56;
     */
    /* JADX WARN: Code restructure failed: missing block: B:57:0x0142, code lost:
        r14.mWmService.mWindowsFreezingScreen = 0;
        r14.mWmService.mLastFinishedFreezeSource = r14.mLastWindowFreezeSource;
        r14.mWmService.mH.removeMessages(11);
     */
    /* JADX WARN: Code restructure failed: missing block: B:58:0x0155, code lost:
        r14.mWmService.stopFreezingDisplayLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:59:0x015a, code lost:
        r1 = r14.mWmService.mDestroySurface.size();
        r10 = -1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:60:0x0163, code lost:
        if (r1 <= 0) goto L69;
     */
    /* JADX WARN: Code restructure failed: missing block: B:61:0x0165, code lost:
        r1 = r1 - 1;
        r11 = r14.mWmService.mDestroySurface.get(r1);
        r11.mDestroying = false;
        r12 = r11.getDisplayContent();
     */
    /* JADX WARN: Code restructure failed: missing block: B:62:0x0178, code lost:
        if (r12.mInputMethodWindow != r11) goto L62;
     */
    /* JADX WARN: Code restructure failed: missing block: B:63:0x017a, code lost:
        r12.setInputMethodWindowLocked(null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:65:0x0183, code lost:
        if (r12.mWallpaperController.isWallpaperTarget(r11) == false) goto L65;
     */
    /* JADX WARN: Code restructure failed: missing block: B:66:0x0185, code lost:
        r12.pendingLayoutChanges |= 4;
     */
    /* JADX WARN: Code restructure failed: missing block: B:67:0x018b, code lost:
        r11.destroySurfaceUnchecked();
        r11.mWinAnimator.destroyPreservedSurfaceLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:68:0x0193, code lost:
        if (r1 > 0) goto L59;
     */
    /* JADX WARN: Code restructure failed: missing block: B:69:0x0195, code lost:
        r14.mWmService.mDestroySurface.clear();
     */
    /* JADX WARN: Code restructure failed: missing block: B:70:0x019c, code lost:
        r3 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:71:0x019d, code lost:
        if (r3 >= r2) goto L72;
     */
    /* JADX WARN: Code restructure failed: missing block: B:72:0x019f, code lost:
        ((com.android.server.wm.DisplayContent) r14.mChildren.get(r3)).removeExistingTokensIfPossible();
        r3 = r3 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:73:0x01ad, code lost:
        r3 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:74:0x01ae, code lost:
        if (r3 >= r2) goto L81;
     */
    /* JADX WARN: Code restructure failed: missing block: B:75:0x01b0, code lost:
        r11 = (com.android.server.wm.DisplayContent) r14.mChildren.get(r3);
     */
    /* JADX WARN: Code restructure failed: missing block: B:76:0x01ba, code lost:
        if (r11.pendingLayoutChanges == 0) goto L80;
     */
    /* JADX WARN: Code restructure failed: missing block: B:77:0x01bc, code lost:
        r11.setLayoutNeeded();
     */
    /* JADX WARN: Code restructure failed: missing block: B:78:0x01bf, code lost:
        r3 = r3 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:79:0x01c2, code lost:
        r14.mWmService.setHoldScreenLocked(r14.mHoldScreen);
     */
    /* JADX WARN: Code restructure failed: missing block: B:80:0x01cd, code lost:
        if (r14.mWmService.mDisplayFrozen != false) goto L90;
     */
    /* JADX WARN: Code restructure failed: missing block: B:81:0x01cf, code lost:
        r3 = r14.mScreenBrightness;
     */
    /* JADX WARN: Code restructure failed: missing block: B:82:0x01d4, code lost:
        if (r3 < 0.0f) goto L89;
     */
    /* JADX WARN: Code restructure failed: missing block: B:84:0x01da, code lost:
        if (r3 <= 1.0f) goto L88;
     */
    /* JADX WARN: Code restructure failed: missing block: B:86:0x01dd, code lost:
        r10 = toBrightnessOverride(r3);
     */
    /* JADX WARN: Code restructure failed: missing block: B:88:0x01e3, code lost:
        r3 = r10;
        r14.mHandler.obtainMessage(1, r3, 0).sendToTarget();
        r14.mHandler.obtainMessage(2, java.lang.Long.valueOf(r14.mUserActivityTimeout)).sendToTarget();
     */
    /* JADX WARN: Code restructure failed: missing block: B:89:0x01fc, code lost:
        r3 = r14.mSustainedPerformanceModeCurrent;
     */
    /* JADX WARN: Code restructure failed: missing block: B:90:0x0200, code lost:
        if (r3 == r14.mSustainedPerformanceModeEnabled) goto L93;
     */
    /* JADX WARN: Code restructure failed: missing block: B:91:0x0202, code lost:
        r14.mSustainedPerformanceModeEnabled = r3;
        r14.mWmService.mPowerManagerInternal.powerHint(6, r14.mSustainedPerformanceModeEnabled ? 1 : 0);
     */
    /* JADX WARN: Code restructure failed: missing block: B:93:0x0210, code lost:
        if (r14.mUpdateRotation == false) goto L99;
     */
    /* JADX WARN: Code restructure failed: missing block: B:95:0x0214, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION == false) goto L98;
     */
    /* JADX WARN: Code restructure failed: missing block: B:96:0x0216, code lost:
        android.util.Slog.d(com.android.server.wm.RootWindowContainer.TAG, "Performing post-rotate rotation");
     */
    /* JADX WARN: Code restructure failed: missing block: B:97:0x021b, code lost:
        r14.mUpdateRotation = updateRotationUnchecked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:99:0x0225, code lost:
        if (r14.mWmService.mWaitingForDrawnCallback != null) goto L138;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    void performSurfacePlacementNoTrace(boolean r15) {
        /*
            Method dump skipped, instructions count: 754
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.RootWindowContainer.performSurfacePlacementNoTrace(boolean):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$performSurfacePlacementNoTrace$11(DisplayContent dc) {
        dc.getInputMonitor().updateInputWindowsLw(true);
        dc.updateSystemGestureExclusion();
        dc.updateTouchExcludeRegion();
    }

    private void checkAppTransitionReady(WindowSurfacePlacer surfacePlacer) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent curDisplay = (DisplayContent) this.mChildren.get(i);
            if (curDisplay.mAppTransition.isReady()) {
                curDisplay.mAppTransitionController.handleAppTransitionReady();
                if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after handleAppTransitionReady", curDisplay.pendingLayoutChanges);
                }
            }
            if (curDisplay.mAppTransition.isRunning() && !curDisplay.isAppAnimating()) {
                curDisplay.handleAnimatingStoppedAndTransition();
                if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                    surfacePlacer.debugLayoutRepeats("after handleAnimStopAndXitionLock", curDisplay.pendingLayoutChanges);
                }
            }
        }
    }

    private void applySurfaceChangesTransaction(boolean recoveringMemory) {
        this.mHoldScreenWindow = null;
        this.mObscuringWindow = null;
        DisplayContent defaultDc = this.mWmService.getDefaultDisplayContentLocked();
        DisplayInfo defaultInfo = defaultDc.getDisplayInfo();
        int defaultDw = defaultInfo.logicalWidth;
        int defaultDh = defaultInfo.logicalHeight;
        if (this.mWmService.mWatermark != null) {
            this.mWmService.mWatermark.positionSurface(defaultDw, defaultDh);
        }
        if (this.mWmService.mStrictModeFlash != null) {
            this.mWmService.mStrictModeFlash.positionSurface(defaultDw, defaultDh);
        }
        if (this.mWmService.mCircularDisplayMask != null) {
            this.mWmService.mCircularDisplayMask.positionSurface(defaultDw, defaultDh, this.mWmService.getDefaultDisplayRotation());
        }
        if (this.mWmService.mEmulatorDisplayOverlay != null) {
            this.mWmService.mEmulatorDisplayOverlay.positionSurface(defaultDw, defaultDh, this.mWmService.getDefaultDisplayRotation());
        }
        int count = this.mChildren.size();
        for (int j = 0; j < count; j++) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(j);
            dc.applySurfaceChangesTransaction(recoveringMemory);
        }
        this.mWmService.mDisplayManagerInternal.performTraversal(this.mDisplayTransaction);
        SurfaceControl.mergeToGlobalTransaction(this.mDisplayTransaction);
    }

    private void handleResizingWindows() {
        for (int i = this.mWmService.mResizingWindows.size() - 1; i >= 0; i--) {
            WindowState win = this.mWmService.mResizingWindows.get(i);
            if (!win.mAppFreezing && !win.getDisplayContent().mWaitingForConfig) {
                win.reportResized();
                this.mWmService.mResizingWindows.remove(i);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handleNotObscuredLocked(WindowState w, boolean obscured, boolean syswin) {
        WindowManager.LayoutParams attrs = w.mAttrs;
        int attrFlags = attrs.flags;
        boolean onScreen = w.isOnScreen();
        boolean canBeSeen = w.isDisplayedLw();
        int privateflags = attrs.privateFlags;
        boolean displayHasContent = false;
        if (w.mHasSurface && onScreen && !syswin && w.mAttrs.userActivityTimeout >= 0 && this.mUserActivityTimeout < 0) {
            this.mUserActivityTimeout = w.mAttrs.userActivityTimeout;
        }
        if (w.mHasSurface && canBeSeen) {
            if ((attrFlags & 128) != 0) {
                this.mHoldScreen = w.mSession;
                this.mHoldScreenWindow = w;
            }
            if (!syswin && w.mAttrs.screenBrightness >= 0.0f && this.mScreenBrightness < 0.0f) {
                this.mScreenBrightness = w.mAttrs.screenBrightness;
                this.mFirstWindwBrightness = this.mScreenBrightness;
                String packageName = w.mAttrs.packageName;
                int sharedId = -1;
                try {
                    WindowManager wm = (WindowManager) this.mWmService.mContext.getSystemService("window");
                    sharedId = wm.getSharedId(packageName);
                    boolean isPrimaryId = WindowManager.isPrimaryId(sharedId);
                    if (isPrimaryId) {
                        sharedId = 0;
                    }
                    Slog.d(TAG, "isPrimaryId=" + isPrimaryId + " sharedId=" + sharedId);
                    if (this.mLastWindwBrightness != this.mFirstWindwBrightness) {
                        Settings.System.putInt(this.mWmService.mContext.getContentResolver(), "screen_brightness_change_" + sharedId, 0);
                    }
                    this.mLastWindwBrightness = this.mFirstWindwBrightness;
                } catch (Exception e) {
                    e.printStackTrace();
                }
                int windowBrightness = (int) (this.mScreenBrightness * 255.0f);
                this.mScreenBrightness = -1.0f;
                int mScreenChange = Settings.System.getInt(this.mWmService.mContext.getContentResolver(), "screen_brightness_change_" + sharedId, 0);
                if (mScreenChange == 1) {
                    Settings.System.putInt(this.mWmService.mContext.getContentResolver(), "screen_window_brightness_" + sharedId, -1);
                    this.mPowerManager.exitXpWindowBrightness(sharedId);
                    Slog.d(TAG, "set type: " + sharedId + " windowBrightness=-1");
                } else {
                    Settings.System.putString(this.mWmService.mContext.getContentResolver(), "screen_package_name_" + sharedId, packageName);
                    Settings.System.putInt(this.mWmService.mContext.getContentResolver(), "screen_window_brightness_" + sharedId, windowBrightness);
                    this.mPowerManager.setXpWindowBrightness(sharedId);
                    Slog.d(TAG, "shareId =" + sharedId + " screenBrightness= " + this.mScreenBrightness + " windowBrightness= " + windowBrightness);
                }
            }
            int windowBrightness2 = attrs.type;
            DisplayContent displayContent = w.getDisplayContent();
            if (displayContent != null && displayContent.isDefaultDisplay) {
                if (windowBrightness2 == 2023 || (attrs.privateFlags & 1024) != 0) {
                    this.mObscureApplicationContentOnSecondaryDisplays = true;
                }
                displayHasContent = true;
            } else if (displayContent != null && (!this.mObscureApplicationContentOnSecondaryDisplays || (obscured && windowBrightness2 == 2009))) {
                displayHasContent = true;
            }
            if ((262144 & privateflags) != 0) {
                this.mSustainedPerformanceModeCurrent = true;
            }
        }
        return displayHasContent;
    }

    boolean updateRotationUnchecked() {
        boolean changed = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent displayContent = (DisplayContent) this.mChildren.get(i);
            if (displayContent.updateRotationAndSendNewConfigIfNeeded()) {
                changed = true;
            }
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean copyAnimToLayoutParams() {
        boolean doRequest = false;
        int bulkUpdateParams = this.mWmService.mAnimator.mBulkUpdateParams;
        if ((bulkUpdateParams & 1) != 0) {
            this.mUpdateRotation = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & 4) == 0) {
            this.mOrientationChangeComplete = false;
        } else {
            this.mOrientationChangeComplete = true;
            this.mLastWindowFreezeSource = this.mWmService.mAnimator.mLastWindowFreezeSource;
            if (this.mWmService.mWindowsFreezingScreen != 0) {
                doRequest = true;
            }
        }
        if ((bulkUpdateParams & 8) != 0) {
            this.mWallpaperActionPending = true;
        }
        return doRequest;
    }

    private static int toBrightnessOverride(float value) {
        return (int) (255.0f * value);
    }

    /* loaded from: classes2.dex */
    private final class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                RootWindowContainer.this.mWmService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(msg.arg1);
            } else if (i == 2) {
                RootWindowContainer.this.mWmService.mPowerManagerInternal.setUserActivityTimeoutOverrideFromWindowManager(((Long) msg.obj).longValue());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpDisplayContents(PrintWriter pw) {
        pw.println("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)");
        if (this.mWmService.mDisplayReady) {
            int count = this.mChildren.size();
            for (int i = 0; i < count; i++) {
                DisplayContent displayContent = (DisplayContent) this.mChildren.get(i);
                displayContent.dump(pw, "  ", true);
            }
            return;
        }
        pw.println("  NO DISPLAY");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpTopFocusedDisplayId(PrintWriter pw) {
        pw.print("  mTopFocusedDisplayId=");
        pw.println(this.mTopFocusedDisplayId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpLayoutNeededDisplayIds(PrintWriter pw) {
        if (!isLayoutNeeded()) {
            return;
        }
        pw.print("  mLayoutNeeded on displays=");
        int count = this.mChildren.size();
        for (int displayNdx = 0; displayNdx < count; displayNdx++) {
            DisplayContent displayContent = (DisplayContent) this.mChildren.get(displayNdx);
            if (displayContent.isLayoutNeeded()) {
                pw.print(displayContent.getDisplayId());
            }
        }
        pw.println();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpWindowsNoHeader(final PrintWriter pw, final boolean dumpAll, final ArrayList<WindowState> windows) {
        final int[] index = new int[1];
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$QacoBaz61ENUVlxMcAhKNleOJZ4
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$dumpWindowsNoHeader$12(windows, pw, index, dumpAll, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dumpWindowsNoHeader$12(ArrayList windows, PrintWriter pw, int[] index, boolean dumpAll, WindowState w) {
        if (windows == null || windows.contains(w)) {
            pw.println("  Window #" + index[0] + " " + w + ":");
            w.dump(pw, "    ", dumpAll || windows != null);
            index[0] = index[0] + 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpTokens(PrintWriter pw, boolean dumpAll) {
        pw.println("  All tokens:");
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            ((DisplayContent) this.mChildren.get(i)).dumpTokens(pw, dumpAll);
        }
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(final ProtoOutputStream proto, long fieldId, int logLevel) {
        if (logLevel == 2 && !isVisible()) {
            return;
        }
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        if (this.mWmService.mDisplayReady) {
            int count = this.mChildren.size();
            for (int i = 0; i < count; i++) {
                DisplayContent displayContent = (DisplayContent) this.mChildren.get(i);
                displayContent.writeToProto(proto, 2246267895810L, logLevel);
            }
        }
        if (logLevel == 0) {
            forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$MbASqBvA7H-Yntg2mJ6WEMV39O8
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((WindowState) obj).writeIdentifierToProto(proto, 2246267895811L);
                }
            }, true);
        }
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public String getName() {
        return "ROOT";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void positionChildAt(int position, DisplayContent child, boolean includingParents) {
        super.positionChildAt(position, (int) child, includingParents);
        RootActivityContainer rootActivityContainer = this.mRootActivityContainer;
        if (rootActivityContainer != null) {
            rootActivityContainer.onChildPositionChanged(child.mAcitvityDisplay, position);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAt(int position, DisplayContent child) {
        super.positionChildAt(position, (int) child, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void scheduleAnimation() {
        this.mWmService.scheduleAnimationLocked();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.WindowContainer
    public void removeChild(DisplayContent dc) {
        super.removeChild((RootWindowContainer) dc);
        if (this.mTopFocusedDisplayId == dc.getDisplayId()) {
            this.mWmService.updateFocusedWindowLocked(0, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forAllDisplays(Consumer<DisplayContent> callback) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            callback.accept((DisplayContent) this.mChildren.get(i));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forAllDisplayPolicies(Consumer<DisplayPolicy> callback) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            callback.accept(((DisplayContent) this.mChildren.get(i)).getDisplayPolicy());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getCurrentInputMethodWindow() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent displayContent = (DisplayContent) this.mChildren.get(i);
            if (displayContent.mInputMethodWindow != null) {
                return displayContent.mInputMethodWindow;
            }
        }
        return null;
    }
}
