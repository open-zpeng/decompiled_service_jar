package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.WindowManager;
import com.android.internal.util.ArrayUtils;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupManagerConstants;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RootWindowContainer extends WindowContainer<DisplayContent> {
    private static final int SET_SCREEN_BRIGHTNESS_OVERRIDE = 1;
    private static final int SET_USER_ACTIVITY_TIMEOUT = 2;
    private static final String TAG = "WindowManager";
    private static final Consumer<WindowState> sRemoveReplacedWindowsConsumer = new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$Vvv8jzH2oSE9-eakZwTuKd5NpsU
        @Override // java.util.function.Consumer
        public final void accept(Object obj) {
            RootWindowContainer.lambda$static$1((WindowState) obj);
        }
    };
    private final Consumer<WindowState> mCloseSystemDialogsConsumer;
    private String mCloseSystemDialogsReason;
    private final SurfaceControl.Transaction mDisplayTransaction;
    private final Handler mHandler;
    private Session mHoldScreen;
    WindowState mHoldScreenWindow;
    private Object mLastWindowFreezeSource;
    private boolean mObscureApplicationContentOnSecondaryDisplays;
    WindowState mObscuringWindow;
    boolean mOrientationChangeComplete;
    private float mScreenBrightness;
    private boolean mSustainedPerformanceModeCurrent;
    private boolean mSustainedPerformanceModeEnabled;
    private final ArrayList<Integer> mTmpStackIds;
    private final ArrayList<TaskStack> mTmpStackList;
    private boolean mUpdateRotation;
    private long mUserActivityTimeout;
    boolean mWallpaperActionPending;
    final WallpaperController mWallpaperController;
    private boolean mWallpaperForceHidingChanged;
    boolean mWallpaperMayChange;

    public static /* synthetic */ void lambda$new$0(RootWindowContainer rootWindowContainer, WindowState w) {
        if (w.mHasSurface) {
            try {
                w.mClient.closeSystemDialogs(rootWindowContainer.mCloseSystemDialogsReason);
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
        this.mWallpaperForceHidingChanged = false;
        this.mLastWindowFreezeSource = null;
        this.mHoldScreen = null;
        this.mScreenBrightness = -1.0f;
        this.mUserActivityTimeout = -1L;
        this.mUpdateRotation = false;
        this.mHoldScreenWindow = null;
        this.mObscuringWindow = null;
        this.mObscureApplicationContentOnSecondaryDisplays = false;
        this.mSustainedPerformanceModeEnabled = false;
        this.mSustainedPerformanceModeCurrent = false;
        this.mWallpaperMayChange = false;
        this.mOrientationChangeComplete = true;
        this.mWallpaperActionPending = false;
        this.mTmpStackList = new ArrayList<>();
        this.mTmpStackIds = new ArrayList<>();
        this.mDisplayTransaction = new SurfaceControl.Transaction();
        this.mCloseSystemDialogsConsumer = new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$qT2ficAmvrvFcBdiJIGNKxJ8Z9Q
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$new$0(RootWindowContainer.this, (WindowState) obj);
            }
        };
        this.mHandler = new MyHandler(service.mH.getLooper());
        this.mWallpaperController = new WallpaperController(this.mService);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState computeFocusedWindow() {
        boolean forceDefaultDisplay = this.mService.isKeyguardShowingAndNotOccluded();
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            WindowState win = dc.findFocusedWindow();
            if (win != null) {
                if (forceDefaultDisplay && !dc.isDefaultDisplay) {
                    EventLog.writeEvent(1397638484, "71786287", Integer.valueOf(win.mOwnerUid), BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                } else {
                    return win;
                }
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getDisplaysInFocusOrder(SparseIntArray displaysInFocusOrder) {
        displaysInFocusOrder.clear();
        int size = this.mChildren.size();
        for (int i = 0; i < size; i++) {
            DisplayContent displayContent = (DisplayContent) this.mChildren.get(i);
            if (!displayContent.isRemovalDeferred()) {
                displaysInFocusOrder.put(i, displayContent.getDisplayId());
            }
        }
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
    public DisplayContent createDisplayContent(Display display, DisplayWindowController controller) {
        int displayId = display.getDisplayId();
        DisplayContent existing = getDisplayContent(displayId);
        if (existing != null) {
            existing.setController(controller);
            return existing;
        }
        DisplayContent dc = new DisplayContent(display, this.mService, this.mWallpaperController, controller);
        if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
            Slog.v(TAG, "Adding display=" + display);
        }
        DisplayInfo displayInfo = dc.getDisplayInfo();
        Rect rect = new Rect();
        this.mService.mDisplaySettings.getOverscanLocked(displayInfo.name, displayInfo.uniqueId, rect);
        displayInfo.overscanLeft = rect.left;
        displayInfo.overscanTop = rect.top;
        displayInfo.overscanRight = rect.right;
        displayInfo.overscanBottom = rect.bottom;
        if (this.mService.mDisplayManagerInternal != null) {
            this.mService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(displayId, displayInfo);
            dc.configureDisplayPolicy();
            if (this.mService.canDispatchPointerEvents()) {
                if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                    Slog.d(TAG, "Registering PointerEventListener for DisplayId: " + displayId);
                }
                dc.mTapDetector = new TaskTapPointerEventListener(this.mService, dc);
                this.mService.registerPointerEventListener(dc.mTapDetector);
                if (displayId == 0) {
                    this.mService.registerPointerEventListener(this.mService.mMousePositionTracker);
                }
            }
        }
        return dc;
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
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$O6gArs92KbWUhitra1og4WTg69c
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$getWindowsByName$2(name, output, objectId, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getWindowsByName$2(String name, ArrayList output, int objectId, WindowState w) {
        if (name != null) {
            if (w.mAttrs.getTitle().toString().contains(name)) {
                output.add(w);
            }
        } else if (System.identityHashCode(w) == objectId) {
            output.add(w);
        }
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
    public int[] setDisplayOverrideConfigurationIfNeeded(Configuration newConfiguration, int displayId) {
        DisplayContent displayContent = getDisplayContent(displayId);
        if (displayContent == null) {
            throw new IllegalArgumentException("Display not found for id: " + displayId);
        }
        Configuration currentConfig = displayContent.getOverrideConfiguration();
        boolean configChanged = currentConfig.diff(newConfiguration) != 0;
        if (!configChanged) {
            return null;
        }
        displayContent.onOverrideConfigurationChanged(newConfiguration);
        this.mTmpStackList.clear();
        if (displayId == 0) {
            setGlobalConfigurationIfNeeded(newConfiguration, this.mTmpStackList);
        } else {
            updateStackBoundsAfterConfigChange(displayId, this.mTmpStackList);
        }
        this.mTmpStackIds.clear();
        int stackCount = this.mTmpStackList.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStack stack = this.mTmpStackList.get(i);
            if (!stack.mDeferRemoval) {
                this.mTmpStackIds.add(Integer.valueOf(stack.mStackId));
            }
        }
        if (this.mTmpStackIds.isEmpty()) {
            return null;
        }
        return ArrayUtils.convertToIntArray(this.mTmpStackIds);
    }

    private void setGlobalConfigurationIfNeeded(Configuration newConfiguration, List<TaskStack> changedStacks) {
        boolean configChanged = getConfiguration().diff(newConfiguration) != 0;
        if (!configChanged) {
            return;
        }
        onConfigurationChanged(newConfiguration);
        updateStackBoundsAfterConfigChange(changedStacks);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        prepareFreezingTaskBounds();
        super.onConfigurationChanged(newParentConfig);
        this.mService.mPolicy.onConfigurationChanged();
    }

    private void updateStackBoundsAfterConfigChange(List<TaskStack> changedStacks) {
        int numDisplays = this.mChildren.size();
        for (int i = 0; i < numDisplays; i++) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(i);
            dc.updateStackBoundsAfterConfigChange(changedStacks);
        }
    }

    private void updateStackBoundsAfterConfigChange(int displayId, List<TaskStack> changedStacks) {
        DisplayContent dc = getDisplayContent(displayId);
        dc.updateStackBoundsAfterConfigChange(changedStacks);
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
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$3VVFoec4x74e1MMAq03gYI9kKjo
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$setSecureSurfaceState$3(userId, disabled, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setSecureSurfaceState$3(int userId, boolean disabled, WindowState w) {
        if (w.mHasSurface && userId == UserHandle.getUserId(w.mOwnerUid)) {
            w.mWinAnimator.setSecureLocked(disabled);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateHiddenWhileSuspendedState(final ArraySet<String> packages, final boolean suspended) {
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$9Gi6QLDM5W-SF-EH_zfgZZvIlo0
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$updateHiddenWhileSuspendedState$4(packages, suspended, (WindowState) obj);
            }
        }, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$updateHiddenWhileSuspendedState$4(ArraySet packages, boolean suspended, WindowState w) {
        if (packages.contains(w.getOwningPackage())) {
            w.setHiddenWhileSuspended(suspended);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateAppOpsState() {
        forAllWindows((Consumer<WindowState>) new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$0aCEx04eIvMHmZVtI4ucsiK5s9I
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WindowState) obj).updateAppOpsState();
            }
        }, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$canShowStrictModeViolation$6(int pid, WindowState w) {
        return w.mSession.mPid == pid && w.isVisibleLw();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowStrictModeViolation(final int pid) {
        WindowState win = getWindow(new Predicate() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$ZTXupc1zKRWZgWpo-r3so3blHoI
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return RootWindowContainer.lambda$canShowStrictModeViolation$6(pid, (WindowState) obj);
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
        this.mService.openSurfaceTransaction();
        try {
            forAllWindows(sRemoveReplacedWindowsConsumer, true);
        } finally {
            this.mService.closeSurfaceTransaction("removeReplacedWindows");
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
                animator.mBulkUpdateParams |= 16;
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
                int displayNdx2 = 0;
                while (true) {
                    int displayNdx3 = displayNdx2;
                    if (displayNdx3 >= numDisplays) {
                        break;
                    }
                    ((DisplayContent) this.mChildren.get(displayNdx3)).forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$utugHDPHgMp2b3JwigOH_-Y0P1Q
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            RootWindowContainer.lambda$reclaimSomeSurfaceMemory$7(RootWindowContainer.this, pidCandidates, (WindowState) obj);
                        }
                    }, false);
                    if (pidCandidates.size() > 0) {
                        int[] pids = new int[pidCandidates.size()];
                        for (int i = 0; i < pids.length; i++) {
                            pids[i] = pidCandidates.keyAt(i);
                        }
                        try {
                            try {
                                try {
                                    if (this.mService.mActivityManager.killPids(pids, "Free memory", secure)) {
                                        killedApps = true;
                                    }
                                } catch (Throwable th) {
                                    th = th;
                                    Binder.restoreCallingIdentity(callingIdentity);
                                    throw th;
                                }
                            } catch (RemoteException e) {
                            }
                        } catch (RemoteException e2) {
                        }
                    }
                    displayNdx2 = displayNdx3 + 1;
                }
            }
            if (leakedSurface || killedApps) {
                Slog.w(TAG, "Looks like we have reclaimed some memory, clearing surface for retry.");
                if (surfaceController != null) {
                    if (WindowManagerDebugConfig.SHOW_TRANSACTIONS || WindowManagerDebugConfig.SHOW_SURFACE_ALLOC) {
                        WindowManagerService.logSurface(winAnimator.mWin, "RECOVER DESTROY", false);
                    }
                    winAnimator.destroySurface();
                    if (winAnimator.mWin.mAppToken != null && winAnimator.mWin.mAppToken.getController() != null) {
                        winAnimator.mWin.mAppToken.getController().removeStartingWindow();
                    }
                }
                try {
                    winAnimator.mWin.mClient.dispatchGetNewSurface();
                } catch (RemoteException e3) {
                }
            }
            Binder.restoreCallingIdentity(callingIdentity);
            return leakedSurface || killedApps;
        } catch (Throwable th2) {
            th = th2;
        }
    }

    public static /* synthetic */ void lambda$reclaimSomeSurfaceMemory$7(RootWindowContainer rootWindowContainer, SparseIntArray pidCandidates, WindowState w) {
        if (rootWindowContainer.mService.mForceRemoves.contains(w)) {
            return;
        }
        WindowStateAnimator wsa = w.mWinAnimator;
        if (wsa.mSurfaceController != null) {
            pidCandidates.append(wsa.mSession.mPid, wsa.mSession.mPid);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:100:0x023e, code lost:
        if (r15.pendingLayoutChanges == 0) goto L99;
     */
    /* JADX WARN: Code restructure failed: missing block: B:101:0x0240, code lost:
        r15.setLayoutNeeded();
     */
    /* JADX WARN: Code restructure failed: missing block: B:102:0x0243, code lost:
        r4 = r4 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:103:0x0247, code lost:
        r20.mService.mInputMonitor.updateInputWindowsLw(true);
        r20.mService.setHoldScreenLocked(r20.mHoldScreen);
     */
    /* JADX WARN: Code restructure failed: missing block: B:104:0x025a, code lost:
        if (r20.mService.mDisplayFrozen != false) goto L197;
     */
    /* JADX WARN: Code restructure failed: missing block: B:106:0x0261, code lost:
        if (r20.mScreenBrightness < 0.0f) goto L196;
     */
    /* JADX WARN: Code restructure failed: missing block: B:108:0x0269, code lost:
        if (r20.mScreenBrightness <= 1.0f) goto L107;
     */
    /* JADX WARN: Code restructure failed: missing block: B:110:0x026c, code lost:
        r15 = toBrightnessOverride(r20.mScreenBrightness);
     */
    /* JADX WARN: Code restructure failed: missing block: B:111:0x0273, code lost:
        r15 = -1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:112:0x0274, code lost:
        r4 = r15;
     */
    /* JADX WARN: Code restructure failed: missing block: B:113:0x0275, code lost:
        if (r4 != 0) goto L195;
     */
    /* JADX WARN: Code restructure failed: missing block: B:114:0x0277, code lost:
        r5 = 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:115:0x0279, code lost:
        r5 = r4;
     */
    /* JADX WARN: Code restructure failed: missing block: B:116:0x027a, code lost:
        r20.mHandler.obtainMessage(1, r5, 0).sendToTarget();
        r20.mHandler.obtainMessage(2, java.lang.Long.valueOf(r20.mUserActivityTimeout)).sendToTarget();
     */
    /* JADX WARN: Code restructure failed: missing block: B:119:0x02a2, code lost:
        if (r20.mSustainedPerformanceModeCurrent == r20.mSustainedPerformanceModeEnabled) goto L115;
     */
    /* JADX WARN: Code restructure failed: missing block: B:120:0x02a4, code lost:
        r20.mSustainedPerformanceModeEnabled = r20.mSustainedPerformanceModeCurrent;
        r20.mService.mPowerManagerInternal.powerHint(6, r20.mSustainedPerformanceModeEnabled ? 1 : 0);
     */
    /* JADX WARN: Code restructure failed: missing block: B:122:0x02b4, code lost:
        if (r20.mUpdateRotation == false) goto L194;
     */
    /* JADX WARN: Code restructure failed: missing block: B:124:0x02b8, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION == false) goto L120;
     */
    /* JADX WARN: Code restructure failed: missing block: B:125:0x02ba, code lost:
        android.util.Slog.d(com.android.server.wm.RootWindowContainer.TAG, "Performing post-rotate rotation");
     */
    /* JADX WARN: Code restructure failed: missing block: B:126:0x02c1, code lost:
        r3 = r6.getDisplayId();
     */
    /* JADX WARN: Code restructure failed: missing block: B:127:0x02cb, code lost:
        if (r6.updateRotationUnchecked() == false) goto L193;
     */
    /* JADX WARN: Code restructure failed: missing block: B:128:0x02cd, code lost:
        r20.mService.mH.obtainMessage(18, java.lang.Integer.valueOf(r3)).sendToTarget();
     */
    /* JADX WARN: Code restructure failed: missing block: B:129:0x02dd, code lost:
        r20.mUpdateRotation = false;
     */
    /* JADX WARN: Code restructure failed: missing block: B:131:0x02e5, code lost:
        if (r20.mService.mVr2dDisplayId == (-1)) goto L192;
     */
    /* JADX WARN: Code restructure failed: missing block: B:132:0x02e7, code lost:
        r4 = getDisplayContent(r20.mService.mVr2dDisplayId);
     */
    /* JADX WARN: Code restructure failed: missing block: B:133:0x02f0, code lost:
        r4 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:134:0x02f1, code lost:
        if (r4 == null) goto L194;
     */
    /* JADX WARN: Code restructure failed: missing block: B:136:0x02f7, code lost:
        if (r4.updateRotationUnchecked() == false) goto L194;
     */
    /* JADX WARN: Code restructure failed: missing block: B:137:0x02f9, code lost:
        r20.mService.mH.obtainMessage(18, java.lang.Integer.valueOf(r20.mService.mVr2dDisplayId)).sendToTarget();
     */
    /* JADX WARN: Code restructure failed: missing block: B:140:0x0317, code lost:
        if (r20.mService.mWaitingForDrawnCallback != null) goto L191;
     */
    /* JADX WARN: Code restructure failed: missing block: B:142:0x031b, code lost:
        if (r20.mOrientationChangeComplete == false) goto L138;
     */
    /* JADX WARN: Code restructure failed: missing block: B:144:0x0321, code lost:
        if (r6.isLayoutNeeded() != false) goto L138;
     */
    /* JADX WARN: Code restructure failed: missing block: B:146:0x0325, code lost:
        if (r20.mUpdateRotation != false) goto L138;
     */
    /* JADX WARN: Code restructure failed: missing block: B:147:0x0327, code lost:
        r20.mService.checkDrawnWindowsLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:148:0x032c, code lost:
        r0 = r20.mService.mPendingRemove.size();
     */
    /* JADX WARN: Code restructure failed: missing block: B:149:0x0334, code lost:
        if (r0 <= 0) goto L190;
     */
    /* JADX WARN: Code restructure failed: missing block: B:151:0x033b, code lost:
        if (r20.mService.mPendingRemoveTmp.length >= r0) goto L143;
     */
    /* JADX WARN: Code restructure failed: missing block: B:152:0x033d, code lost:
        r20.mService.mPendingRemoveTmp = new com.android.server.wm.WindowState[r0 + 10];
     */
    /* JADX WARN: Code restructure failed: missing block: B:153:0x0345, code lost:
        r20.mService.mPendingRemove.toArray(r20.mService.mPendingRemoveTmp);
        r20.mService.mPendingRemove.clear();
        r3 = new java.util.ArrayList<>();
        r14 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:154:0x035e, code lost:
        if (r14 >= r0) goto L154;
     */
    /* JADX WARN: Code restructure failed: missing block: B:155:0x0360, code lost:
        r4 = r20.mService.mPendingRemoveTmp[r14];
        r4.removeImmediately();
        r5 = r4.getDisplayContent();
     */
    /* JADX WARN: Code restructure failed: missing block: B:156:0x036d, code lost:
        if (r5 == null) goto L153;
     */
    /* JADX WARN: Code restructure failed: missing block: B:158:0x0373, code lost:
        if (r3.contains(r5) != false) goto L152;
     */
    /* JADX WARN: Code restructure failed: missing block: B:159:0x0375, code lost:
        r3.add(r5);
     */
    /* JADX WARN: Code restructure failed: missing block: B:160:0x0378, code lost:
        r14 = r14 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:161:0x037b, code lost:
        r5 = 1;
        r4 = r3.size() - 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:162:0x0381, code lost:
        if (r4 < 0) goto L158;
     */
    /* JADX WARN: Code restructure failed: missing block: B:163:0x0383, code lost:
        r3.get(r4).assignWindowLayers(true);
        r4 = r4 - 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:164:0x038f, code lost:
        r5 = 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:165:0x0390, code lost:
        r3 = r20.mChildren.size() - r5;
     */
    /* JADX WARN: Code restructure failed: missing block: B:166:0x0397, code lost:
        if (r3 < 0) goto L162;
     */
    /* JADX WARN: Code restructure failed: missing block: B:167:0x0399, code lost:
        ((com.android.server.wm.DisplayContent) r20.mChildren.get(r3)).checkCompleteDeferredRemoval();
        r3 = r3 - 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:168:0x03a7, code lost:
        if (r2 == false) goto L165;
     */
    /* JADX WARN: Code restructure failed: missing block: B:169:0x03a9, code lost:
        r20.mService.mInputMonitor.updateInputWindowsLw(false);
     */
    /* JADX WARN: Code restructure failed: missing block: B:170:0x03b1, code lost:
        r20.mService.setFocusTaskRegionLocked(null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:171:0x03b7, code lost:
        if (r13 == null) goto L184;
     */
    /* JADX WARN: Code restructure failed: missing block: B:173:0x03bd, code lost:
        if (r20.mService.mFocusedApp == null) goto L183;
     */
    /* JADX WARN: Code restructure failed: missing block: B:174:0x03bf, code lost:
        r5 = r20.mService.mFocusedApp.getDisplayContent();
     */
    /* JADX WARN: Code restructure failed: missing block: B:175:0x03c8, code lost:
        r5 = null;
     */
    /* JADX WARN: Code restructure failed: missing block: B:176:0x03c9, code lost:
        r3 = r5;
        r4 = r13.iterator();
     */
    /* JADX WARN: Code restructure failed: missing block: B:178:0x03d2, code lost:
        if (r4.hasNext() == false) goto L182;
     */
    /* JADX WARN: Code restructure failed: missing block: B:179:0x03d4, code lost:
        r5 = r4.next();
     */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x0090, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS != false) goto L19;
     */
    /* JADX WARN: Code restructure failed: missing block: B:180:0x03da, code lost:
        if (r3 == r5) goto L175;
     */
    /* JADX WARN: Code restructure failed: missing block: B:181:0x03dc, code lost:
        r5.setTouchExcludeRegion(null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:184:0x03e3, code lost:
        r20.mService.enableScreenIfNeededLocked();
        r20.mService.scheduleAnimationLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:185:0x03ef, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE == false) goto L189;
     */
    /* JADX WARN: Code restructure failed: missing block: B:186:0x03f1, code lost:
        android.util.Slog.e(com.android.server.wm.RootWindowContainer.TAG, "performSurfacePlacementInner exit: animating=" + r20.mService.mAnimator.isAnimating());
     */
    /* JADX WARN: Code restructure failed: missing block: B:187:0x0410, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:18:0x0092, code lost:
        android.util.Slog.i(com.android.server.wm.RootWindowContainer.TAG, "<<< CLOSE TRANSACTION performLayoutAndPlaceSurfaces");
     */
    /* JADX WARN: Code restructure failed: missing block: B:216:?, code lost:
        return;
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x00b2, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS == false) goto L20;
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x00b5, code lost:
        r20.mService.mAnimator.executeAfterPrepareSurfacesRunnables();
        r0 = r20.mService.mWindowPlacerLocked;
     */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00c8, code lost:
        if (r20.mService.mAppTransition.isReady() == false) goto L25;
     */
    /* JADX WARN: Code restructure failed: missing block: B:29:0x00ca, code lost:
        r12 = r0.handleAppTransitionReadyLocked();
        r6.pendingLayoutChanges |= r12;
     */
    /* JADX WARN: Code restructure failed: missing block: B:30:0x00d5, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS == false) goto L25;
     */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x00d7, code lost:
        r0.debugLayoutRepeats("after handleAppTransitionReadyLocked", r6.pendingLayoutChanges);
     */
    /* JADX WARN: Code restructure failed: missing block: B:33:0x00e2, code lost:
        if (isAppAnimating() != false) goto L32;
     */
    /* JADX WARN: Code restructure failed: missing block: B:35:0x00ec, code lost:
        if (r20.mService.mAppTransition.isRunning() == false) goto L32;
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00ee, code lost:
        r6.pendingLayoutChanges |= r20.mService.handleAnimatingStoppedAndTransitionLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:37:0x00fb, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS == false) goto L32;
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x00fd, code lost:
        r0.debugLayoutRepeats("after handleAnimStopAndXitionLock", r6.pendingLayoutChanges);
     */
    /* JADX WARN: Code restructure failed: missing block: B:39:0x0104, code lost:
        r12 = r20.mService.getRecentsAnimationController();
     */
    /* JADX WARN: Code restructure failed: missing block: B:40:0x010a, code lost:
        if (r12 == null) goto L35;
     */
    /* JADX WARN: Code restructure failed: missing block: B:41:0x010c, code lost:
        r12.checkAnimationReady(r20.mWallpaperController);
     */
    /* JADX WARN: Code restructure failed: missing block: B:43:0x0113, code lost:
        if (r20.mWallpaperForceHidingChanged == false) goto L44;
     */
    /* JADX WARN: Code restructure failed: missing block: B:45:0x0117, code lost:
        if (r6.pendingLayoutChanges != 0) goto L44;
     */
    /* JADX WARN: Code restructure failed: missing block: B:47:0x0121, code lost:
        if (r20.mService.mAppTransition.isReady() != false) goto L44;
     */
    /* JADX WARN: Code restructure failed: missing block: B:48:0x0123, code lost:
        r6.pendingLayoutChanges |= 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:49:0x012a, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS == false) goto L44;
     */
    /* JADX WARN: Code restructure failed: missing block: B:50:0x012c, code lost:
        r0.debugLayoutRepeats("after animateAwayWallpaperLocked", r6.pendingLayoutChanges);
     */
    /* JADX WARN: Code restructure failed: missing block: B:51:0x0133, code lost:
        r20.mWallpaperForceHidingChanged = false;
     */
    /* JADX WARN: Code restructure failed: missing block: B:52:0x0137, code lost:
        if (r20.mWallpaperMayChange == false) goto L52;
     */
    /* JADX WARN: Code restructure failed: missing block: B:54:0x013b, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT == false) goto L49;
     */
    /* JADX WARN: Code restructure failed: missing block: B:55:0x013d, code lost:
        android.util.Slog.v(com.android.server.wm.RootWindowContainer.TAG, "Wallpaper may change!  Adjusting");
     */
    /* JADX WARN: Code restructure failed: missing block: B:56:0x0144, code lost:
        r6.pendingLayoutChanges |= 4;
     */
    /* JADX WARN: Code restructure failed: missing block: B:57:0x014c, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS == false) goto L52;
     */
    /* JADX WARN: Code restructure failed: missing block: B:58:0x014e, code lost:
        r0.debugLayoutRepeats("WallpaperMayChange", r6.pendingLayoutChanges);
     */
    /* JADX WARN: Code restructure failed: missing block: B:60:0x015a, code lost:
        if (r20.mService.mFocusMayChange == false) goto L57;
     */
    /* JADX WARN: Code restructure failed: missing block: B:61:0x015c, code lost:
        r20.mService.mFocusMayChange = false;
     */
    /* JADX WARN: Code restructure failed: missing block: B:62:0x0166, code lost:
        if (r20.mService.updateFocusedWindowLocked(2, false) == false) goto L57;
     */
    /* JADX WARN: Code restructure failed: missing block: B:63:0x0168, code lost:
        r2 = true;
        r6.pendingLayoutChanges |= 8;
     */
    /* JADX WARN: Code restructure failed: missing block: B:65:0x0173, code lost:
        if (isLayoutNeeded() == false) goto L62;
     */
    /* JADX WARN: Code restructure failed: missing block: B:66:0x0175, code lost:
        r6.pendingLayoutChanges |= 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:67:0x017c, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS == false) goto L62;
     */
    /* JADX WARN: Code restructure failed: missing block: B:68:0x017e, code lost:
        r0.debugLayoutRepeats("mLayoutNeeded", r6.pendingLayoutChanges);
     */
    /* JADX WARN: Code restructure failed: missing block: B:69:0x0186, code lost:
        r13 = handleResizingWindows();
     */
    /* JADX WARN: Code restructure failed: missing block: B:70:0x018c, code lost:
        if (com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION == false) goto L67;
     */
    /* JADX WARN: Code restructure failed: missing block: B:72:0x0192, code lost:
        if (r20.mService.mDisplayFrozen == false) goto L67;
     */
    /* JADX WARN: Code restructure failed: missing block: B:73:0x0194, code lost:
        android.util.Slog.v(com.android.server.wm.RootWindowContainer.TAG, "With display frozen, orientationChangeComplete=" + r20.mOrientationChangeComplete);
     */
    /* JADX WARN: Code restructure failed: missing block: B:75:0x01ae, code lost:
        if (r20.mOrientationChangeComplete == false) goto L73;
     */
    /* JADX WARN: Code restructure failed: missing block: B:77:0x01b4, code lost:
        if (r20.mService.mWindowsFreezingScreen == 0) goto L72;
     */
    /* JADX WARN: Code restructure failed: missing block: B:78:0x01b6, code lost:
        r20.mService.mWindowsFreezingScreen = 0;
        r20.mService.mLastFinishedFreezeSource = r20.mLastWindowFreezeSource;
        r20.mService.mH.removeMessages(11);
     */
    /* JADX WARN: Code restructure failed: missing block: B:79:0x01c9, code lost:
        r20.mService.stopFreezingDisplayLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:80:0x01ce, code lost:
        r7 = false;
        r14 = r20.mService.mDestroySurface.size();
        r15 = -1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:81:0x01d8, code lost:
        if (r14 <= 0) goto L86;
     */
    /* JADX WARN: Code restructure failed: missing block: B:82:0x01da, code lost:
        r14 = r14 + r15;
        r15 = r20.mService.mDestroySurface.get(r14);
        r15.mDestroying = r4;
     */
    /* JADX WARN: Code restructure failed: missing block: B:83:0x01eb, code lost:
        if (r20.mService.mInputMethodWindow != r15) goto L78;
     */
    /* JADX WARN: Code restructure failed: missing block: B:84:0x01ed, code lost:
        r20.mService.setInputMethodWindowLocked(null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:86:0x01fc, code lost:
        if (r15.getDisplayContent().mWallpaperController.isWallpaperTarget(r15) == false) goto L81;
     */
    /* JADX WARN: Code restructure failed: missing block: B:87:0x01fe, code lost:
        r7 = true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:88:0x0200, code lost:
        r15.destroySurfaceUnchecked();
        r15.mWinAnimator.destroyPreservedSurfaceLocked();
     */
    /* JADX WARN: Code restructure failed: missing block: B:89:0x0208, code lost:
        if (r14 > 0) goto L83;
     */
    /* JADX WARN: Code restructure failed: missing block: B:90:0x020a, code lost:
        r20.mService.mDestroySurface.clear();
     */
    /* JADX WARN: Code restructure failed: missing block: B:91:0x0212, code lost:
        r4 = false;
        r15 = -1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:92:0x0215, code lost:
        r4 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:93:0x0216, code lost:
        if (r4 >= r3) goto L89;
     */
    /* JADX WARN: Code restructure failed: missing block: B:94:0x0218, code lost:
        ((com.android.server.wm.DisplayContent) r20.mChildren.get(r4)).removeExistingTokensIfPossible();
        r4 = r4 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:95:0x0226, code lost:
        if (r7 == false) goto L92;
     */
    /* JADX WARN: Code restructure failed: missing block: B:96:0x0228, code lost:
        r6.pendingLayoutChanges |= 4;
        r6.setLayoutNeeded();
     */
    /* JADX WARN: Code restructure failed: missing block: B:97:0x0231, code lost:
        r4 = 0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:98:0x0232, code lost:
        if (r4 >= r3) goto L100;
     */
    /* JADX WARN: Code restructure failed: missing block: B:99:0x0234, code lost:
        r15 = (com.android.server.wm.DisplayContent) r20.mChildren.get(r4);
     */
    /* JADX WARN: Removed duplicated region for block: B:192:0x0420  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void performSurfacePlacement(boolean r21) {
        /*
            Method dump skipped, instructions count: 1064
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.RootWindowContainer.performSurfacePlacement(boolean):void");
    }

    private void applySurfaceChangesTransaction(boolean recoveringMemory, int defaultDw, int defaultDh) {
        this.mHoldScreenWindow = null;
        this.mObscuringWindow = null;
        if (this.mService.mWatermark != null) {
            this.mService.mWatermark.positionSurface(defaultDw, defaultDh);
        }
        if (this.mService.mStrictModeFlash != null) {
            this.mService.mStrictModeFlash.positionSurface(defaultDw, defaultDh);
        }
        if (this.mService.mCircularDisplayMask != null) {
            this.mService.mCircularDisplayMask.positionSurface(defaultDw, defaultDh, this.mService.getDefaultDisplayRotation());
        }
        if (this.mService.mEmulatorDisplayOverlay != null) {
            this.mService.mEmulatorDisplayOverlay.positionSurface(defaultDw, defaultDh, this.mService.getDefaultDisplayRotation());
        }
        boolean focusDisplayed = false;
        int count = this.mChildren.size();
        for (int j = 0; j < count; j++) {
            DisplayContent dc = (DisplayContent) this.mChildren.get(j);
            focusDisplayed |= dc.applySurfaceChangesTransaction(recoveringMemory);
        }
        if (focusDisplayed) {
            this.mService.mH.sendEmptyMessage(3);
        }
        this.mService.mDisplayManagerInternal.performTraversal(this.mDisplayTransaction);
        SurfaceControl.mergeToGlobalTransaction(this.mDisplayTransaction);
    }

    private ArraySet<DisplayContent> handleResizingWindows() {
        ArraySet<DisplayContent> touchExcludeRegionUpdateSet = null;
        for (int i = this.mService.mResizingWindows.size() - 1; i >= 0; i--) {
            WindowState win = this.mService.mResizingWindows.get(i);
            if (!win.mAppFreezing) {
                win.reportResized();
                this.mService.mResizingWindows.remove(i);
                if (WindowManagerService.excludeWindowTypeFromTapOutTask(win.mAttrs.type)) {
                    DisplayContent dc = win.getDisplayContent();
                    if (touchExcludeRegionUpdateSet == null) {
                        touchExcludeRegionUpdateSet = new ArraySet<>();
                    }
                    touchExcludeRegionUpdateSet.add(dc);
                }
            }
        }
        return touchExcludeRegionUpdateSet;
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
            }
            int type = attrs.type;
            DisplayContent displayContent = w.getDisplayContent();
            if (displayContent != null && displayContent.isDefaultDisplay) {
                if (type == 2023 || (attrs.privateFlags & 1024) != 0) {
                    this.mObscureApplicationContentOnSecondaryDisplays = true;
                }
                displayHasContent = true;
            } else if (displayContent != null && (!this.mObscureApplicationContentOnSecondaryDisplays || (obscured && type == 2009))) {
                displayHasContent = true;
            }
            if ((262144 & privateflags) != 0) {
                this.mSustainedPerformanceModeCurrent = true;
            }
        }
        return displayHasContent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean copyAnimToLayoutParams() {
        boolean doRequest = false;
        int bulkUpdateParams = this.mService.mAnimator.mBulkUpdateParams;
        if ((bulkUpdateParams & 1) != 0) {
            this.mUpdateRotation = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & 2) != 0) {
            this.mWallpaperMayChange = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & 4) != 0) {
            this.mWallpaperForceHidingChanged = true;
            doRequest = true;
        }
        if ((bulkUpdateParams & 8) == 0) {
            this.mOrientationChangeComplete = false;
        } else {
            this.mOrientationChangeComplete = true;
            this.mLastWindowFreezeSource = this.mService.mAnimator.mLastWindowFreezeSource;
            if (this.mService.mWindowsFreezingScreen != 0) {
                doRequest = true;
            }
        }
        if ((bulkUpdateParams & 16) != 0) {
            this.mWallpaperActionPending = true;
        }
        return doRequest;
    }

    private static int toBrightnessOverride(float value) {
        return (int) (255.0f * value);
    }

    /* loaded from: classes.dex */
    private final class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    RootWindowContainer.this.mService.mPowerManagerInternal.setScreenBrightnessOverrideFromWindowManager(msg.arg1);
                    return;
                case 2:
                    RootWindowContainer.this.mService.mPowerManagerInternal.setUserActivityTimeoutOverrideFromWindowManager(((Long) msg.obj).longValue());
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpDisplayContents(PrintWriter pw) {
        pw.println("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)");
        if (this.mService.mDisplayReady) {
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
        forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$lQbVdBqi1IIiuRy86WremqX682s
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                RootWindowContainer.lambda$dumpWindowsNoHeader$8(windows, pw, index, dumpAll, (WindowState) obj);
            }
        }, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dumpWindowsNoHeader$8(ArrayList windows, PrintWriter pw, int[] index, boolean dumpAll, WindowState w) {
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
    public void writeToProto(final ProtoOutputStream proto, long fieldId, boolean trim) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, trim);
        if (this.mService.mDisplayReady) {
            int count = this.mChildren.size();
            for (int i = 0; i < count; i++) {
                DisplayContent displayContent = (DisplayContent) this.mChildren.get(i);
                displayContent.writeToProto(proto, 2246267895810L, trim);
            }
        }
        if (!trim) {
            forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$RootWindowContainer$WK0a_BR42j4A-e0Xx1wj4BL8rUk
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
    public void scheduleAnimation() {
        this.mService.scheduleAnimationLocked();
    }
}
