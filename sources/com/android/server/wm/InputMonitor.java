package com.android.server.wm;

import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.IWindow;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import com.android.server.AnimationThread;
import com.android.server.policy.WindowManagerPolicy;
import java.io.PrintWriter;
import java.util.Set;
import java.util.function.Consumer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public final class InputMonitor {
    private boolean mApplyImmediately;
    private boolean mDisableWallpaperTouchEvents;
    private final DisplayContent mDisplayContent;
    private final int mDisplayId;
    private boolean mDisplayRemoved;
    private InputWindowHandle mFocusedInputWindowHandle;
    private WindowState mInputFocus;
    private final SurfaceControl.Transaction mInputTransaction;
    private final WindowManagerService mService;
    private boolean mUpdateInputWindowsPending;
    private boolean mUpdateInputWindowsNeeded = true;
    private final Rect mTmpRect = new Rect();
    private final ArrayMap<String, InputConsumerImpl> mInputConsumers = new ArrayMap<>();
    private final Runnable mUpdateInputWindows = new Runnable() { // from class: com.android.server.wm.InputMonitor.1
        @Override // java.lang.Runnable
        public void run() {
            synchronized (InputMonitor.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    InputMonitor.this.mUpdateInputWindowsPending = false;
                    InputMonitor.this.mUpdateInputWindowsNeeded = false;
                    if (InputMonitor.this.mDisplayRemoved) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    boolean inDrag = InputMonitor.this.mService.mDragDropController.dragDropActiveLocked();
                    boolean inPositioning = InputMonitor.this.mService.mTaskPositioningController.isPositioningLocked();
                    if (inPositioning) {
                        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
                            Log.d("WindowManager", "Inserting window handle for repositioning");
                        }
                        InputMonitor.this.mService.mTaskPositioningController.showInputSurface(InputMonitor.this.mInputTransaction, InputMonitor.this.mDisplayId);
                    } else {
                        InputMonitor.this.mService.mTaskPositioningController.hideInputSurface(InputMonitor.this.mInputTransaction, InputMonitor.this.mDisplayId);
                    }
                    InputMonitor.this.mUpdateInputForAllWindowsConsumer.updateInputWindows(inDrag);
                    WindowManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
        }
    };
    private final Handler mHandler = AnimationThread.getHandler();
    private final UpdateInputForAllWindowsConsumer mUpdateInputForAllWindowsConsumer = new UpdateInputForAllWindowsConsumer();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class EventReceiverInputConsumer extends InputConsumerImpl implements WindowManagerPolicy.InputConsumer {
        private final InputEventReceiver mInputEventReceiver;
        private InputMonitor mInputMonitor;

        EventReceiverInputConsumer(WindowManagerService service, InputMonitor monitor, Looper looper, String name, InputEventReceiver.Factory inputEventReceiverFactory, int clientPid, UserHandle clientUser, int displayId) {
            super(service, null, name, null, clientPid, clientUser, displayId);
            this.mInputMonitor = monitor;
            this.mInputEventReceiver = inputEventReceiverFactory.createInputEventReceiver(this.mClientChannel, looper);
        }

        @Override // com.android.server.policy.WindowManagerPolicy.InputConsumer
        public void dismiss() {
            synchronized (this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (this.mInputMonitor.destroyInputConsumer(this.mWindowHandle.name)) {
                        this.mInputEventReceiver.dispose();
                    }
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        }
    }

    public InputMonitor(WindowManagerService service, int displayId) {
        this.mService = service;
        this.mDisplayContent = this.mService.mRoot.getDisplayContent(displayId);
        this.mDisplayId = displayId;
        this.mInputTransaction = this.mService.mTransactionFactory.make();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onDisplayRemoved() {
        this.mHandler.removeCallbacks(this.mUpdateInputWindows);
        this.mService.mInputManager.onDisplayRemoved(this.mDisplayId);
        this.mDisplayRemoved = true;
    }

    private void addInputConsumer(String name, InputConsumerImpl consumer) {
        this.mInputConsumers.put(name, consumer);
        consumer.linkToDeathRecipient();
        updateInputWindowsLw(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean destroyInputConsumer(String name) {
        if (disposeInputConsumer(this.mInputConsumers.remove(name))) {
            updateInputWindowsLw(true);
            return true;
        }
        return false;
    }

    private boolean disposeInputConsumer(InputConsumerImpl consumer) {
        if (consumer != null) {
            consumer.disposeChannelsLw();
            consumer.hide(this.mInputTransaction);
            return true;
        }
        return false;
    }

    InputConsumerImpl getInputConsumer(String name) {
        return this.mInputConsumers.get(name);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void layoutInputConsumers(int dw, int dh) {
        try {
            Trace.traceBegin(32L, "layoutInputConsumer");
            for (int i = this.mInputConsumers.size() - 1; i >= 0; i--) {
                this.mInputConsumers.valueAt(i).layout(this.mInputTransaction, dw, dh);
            }
        } finally {
            Trace.traceEnd(32L);
        }
    }

    void resetInputConsumers(SurfaceControl.Transaction t) {
        for (int i = this.mInputConsumers.size() - 1; i >= 0; i--) {
            this.mInputConsumers.valueAt(i).hide(t);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowManagerPolicy.InputConsumer createInputConsumer(Looper looper, String name, InputEventReceiver.Factory inputEventReceiverFactory) {
        if (this.mInputConsumers.containsKey(name)) {
            throw new IllegalStateException("Existing input consumer found with name: " + name + ", display: " + this.mDisplayId);
        }
        EventReceiverInputConsumer consumer = new EventReceiverInputConsumer(this.mService, this, looper, name, inputEventReceiverFactory, Process.myPid(), UserHandle.SYSTEM, this.mDisplayId);
        addInputConsumer(name, consumer);
        return consumer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createInputConsumer(IBinder token, String name, InputChannel inputChannel, int clientPid, UserHandle clientUser) {
        if (this.mInputConsumers.containsKey(name)) {
            throw new IllegalStateException("Existing input consumer found with name: " + name + ", display: " + this.mDisplayId);
        }
        InputConsumerImpl consumer = new InputConsumerImpl(this.mService, token, name, inputChannel, clientPid, clientUser, this.mDisplayId);
        char c = 65535;
        int hashCode = name.hashCode();
        if (hashCode != 1024719987) {
            if (hashCode == 1415830696 && name.equals("wallpaper_input_consumer")) {
                c = 0;
            }
        } else if (name.equals("pip_input_consumer")) {
            c = 1;
        }
        if (c == 0) {
            consumer.mWindowHandle.hasWallpaper = true;
        } else if (c == 1) {
            consumer.mWindowHandle.layoutParamsFlags |= 32;
        }
        addInputConsumer(name, consumer);
    }

    void populateInputWindowHandle(InputWindowHandle inputWindowHandle, WindowState child, int flags, int type, boolean isVisible, boolean hasFocus, boolean hasWallpaper) {
        inputWindowHandle.name = child.toString();
        inputWindowHandle.layoutParamsFlags = child.getSurfaceTouchableRegion(inputWindowHandle, flags);
        inputWindowHandle.layoutParamsType = type;
        inputWindowHandle.dispatchingTimeoutNanos = child.getInputDispatchingTimeoutNanos();
        inputWindowHandle.visible = isVisible;
        inputWindowHandle.canReceiveKeys = child.canReceiveKeys();
        inputWindowHandle.hasFocus = hasFocus;
        inputWindowHandle.hasWallpaper = hasWallpaper;
        inputWindowHandle.paused = child.mAppToken != null ? child.mAppToken.paused : false;
        inputWindowHandle.layer = child.mLayer;
        inputWindowHandle.ownerPid = child.mSession.mPid;
        inputWindowHandle.ownerUid = child.mSession.mUid;
        inputWindowHandle.inputFeatures = child.mAttrs.inputFeatures;
        inputWindowHandle.displayId = child.getDisplayId();
        Rect frame = child.getFrameLw();
        inputWindowHandle.frameLeft = frame.left;
        inputWindowHandle.frameTop = frame.top;
        inputWindowHandle.frameRight = frame.right;
        inputWindowHandle.frameBottom = frame.bottom;
        inputWindowHandle.surfaceInset = child.getAttrs().surfaceInsets.left;
        if (child.mGlobalScale != 1.0f) {
            inputWindowHandle.scaleFactor = 1.0f / child.mGlobalScale;
        } else {
            inputWindowHandle.scaleFactor = 1.0f;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT) {
            Slog.d("WindowManager", "addInputWindowHandle: " + child + ", " + inputWindowHandle);
        }
        if (hasFocus) {
            this.mFocusedInputWindowHandle = inputWindowHandle;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setUpdateInputWindowsNeededLw() {
        this.mUpdateInputWindowsNeeded = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateInputWindowsLw(boolean force) {
        if (!force && !this.mUpdateInputWindowsNeeded) {
            return;
        }
        scheduleUpdateInputWindows();
    }

    private void scheduleUpdateInputWindows() {
        if (!this.mDisplayRemoved && !this.mUpdateInputWindowsPending) {
            this.mUpdateInputWindowsPending = true;
            this.mHandler.post(this.mUpdateInputWindows);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateInputWindowsImmediately() {
        if (this.mUpdateInputWindowsPending) {
            this.mApplyImmediately = true;
            this.mUpdateInputWindows.run();
            this.mApplyImmediately = false;
        }
    }

    public void setInputFocusLw(WindowState newWindow, boolean updateInputWindows) {
        if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT || WindowManagerDebugConfig.DEBUG_INPUT) {
            Slog.d("WindowManager", "Input focus has changed to " + newWindow);
        }
        if (newWindow != this.mInputFocus) {
            if (newWindow != null && newWindow.canReceiveKeys()) {
                newWindow.mToken.paused = false;
            }
            this.mInputFocus = newWindow;
            setUpdateInputWindowsNeededLw();
            if (updateInputWindows) {
                updateInputWindowsLw(false);
            }
        }
    }

    public void setFocusedAppLw(AppWindowToken newApp) {
        if (newApp == null) {
            this.mService.mInputManager.setFocusedApplication(this.mDisplayId, null);
            return;
        }
        InputApplicationHandle handle = newApp.mInputApplicationHandle;
        handle.name = newApp.toString();
        handle.dispatchingTimeoutNanos = newApp.mInputDispatchingTimeoutNanos;
        this.mService.mInputManager.setFocusedApplication(this.mDisplayId, handle);
    }

    public void pauseDispatchingLw(WindowToken window) {
        if (!window.paused) {
            if (WindowManagerDebugConfig.DEBUG_INPUT) {
                Slog.v("WindowManager", "Pausing WindowToken " + window);
            }
            window.paused = true;
            updateInputWindowsLw(true);
        }
    }

    public void resumeDispatchingLw(WindowToken window) {
        if (window.paused) {
            if (WindowManagerDebugConfig.DEBUG_INPUT) {
                Slog.v("WindowManager", "Resuming WindowToken " + window);
            }
            window.paused = false;
            updateInputWindowsLw(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        Set<String> inputConsumerKeys = this.mInputConsumers.keySet();
        if (!inputConsumerKeys.isEmpty()) {
            pw.println(prefix + "InputConsumers:");
            for (String key : inputConsumerKeys) {
                this.mInputConsumers.get(key).dump(pw, key, prefix);
            }
        }
    }

    /* loaded from: classes2.dex */
    private final class UpdateInputForAllWindowsConsumer implements Consumer<WindowState> {
        boolean inDrag;
        private boolean mAddInputConsumerHandle;
        private boolean mAddPipInputConsumerHandle;
        private boolean mAddRecentsAnimationInputConsumerHandle;
        private boolean mAddWallpaperInputConsumerHandle;
        final InputWindowHandle mInvalidInputWindow;
        InputConsumerImpl navInputConsumer;
        InputConsumerImpl pipInputConsumer;
        InputConsumerImpl recentsAnimationInputConsumer;
        WallpaperController wallpaperController;
        InputConsumerImpl wallpaperInputConsumer;

        private UpdateInputForAllWindowsConsumer() {
            this.mInvalidInputWindow = new InputWindowHandle((InputApplicationHandle) null, (IWindow) null, InputMonitor.this.mDisplayId);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void updateInputWindows(boolean inDrag) {
            Trace.traceBegin(32L, "updateInputWindows");
            this.navInputConsumer = InputMonitor.this.getInputConsumer("nav_input_consumer");
            this.pipInputConsumer = InputMonitor.this.getInputConsumer("pip_input_consumer");
            this.wallpaperInputConsumer = InputMonitor.this.getInputConsumer("wallpaper_input_consumer");
            this.recentsAnimationInputConsumer = InputMonitor.this.getInputConsumer("recents_animation_input_consumer");
            this.mAddInputConsumerHandle = this.navInputConsumer != null;
            this.mAddPipInputConsumerHandle = this.pipInputConsumer != null;
            this.mAddWallpaperInputConsumerHandle = this.wallpaperInputConsumer != null;
            this.mAddRecentsAnimationInputConsumerHandle = this.recentsAnimationInputConsumer != null;
            InputMonitor.this.mTmpRect.setEmpty();
            InputMonitor.this.mDisableWallpaperTouchEvents = false;
            this.inDrag = inDrag;
            this.wallpaperController = InputMonitor.this.mDisplayContent.mWallpaperController;
            InputMonitor inputMonitor = InputMonitor.this;
            inputMonitor.resetInputConsumers(inputMonitor.mInputTransaction);
            InputMonitor.this.mDisplayContent.forAllWindows((Consumer<WindowState>) this, true);
            if (this.mAddWallpaperInputConsumerHandle) {
                this.wallpaperInputConsumer.show(InputMonitor.this.mInputTransaction, 0);
            }
            if (InputMonitor.this.mApplyImmediately) {
                InputMonitor.this.mInputTransaction.apply();
            } else {
                InputMonitor.this.mDisplayContent.getPendingTransaction().merge(InputMonitor.this.mInputTransaction);
                InputMonitor.this.mDisplayContent.scheduleAnimation();
            }
            Trace.traceEnd(32L);
        }

        @Override // java.util.function.Consumer
        public void accept(WindowState w) {
            RecentsAnimationController recentsAnimationController;
            InputChannel inputChannel = w.mInputChannel;
            InputWindowHandle inputWindowHandle = w.mInputWindowHandle;
            if (inputChannel == null || inputWindowHandle == null || w.mRemoved || w.cantReceiveTouchInput()) {
                if (w.mWinAnimator.hasSurface()) {
                    InputMonitor.this.mInputTransaction.setInputWindowInfo(w.mWinAnimator.mSurfaceController.mSurfaceControl, this.mInvalidInputWindow);
                    return;
                }
                return;
            }
            int flags = w.mAttrs.flags;
            int privateFlags = w.mAttrs.privateFlags;
            int type = w.mAttrs.type;
            boolean hasFocus = w.isFocused();
            boolean isVisible = w.isVisibleLw();
            if (this.mAddRecentsAnimationInputConsumerHandle && (recentsAnimationController = InputMonitor.this.mService.getRecentsAnimationController()) != null && recentsAnimationController.shouldApplyInputConsumer(w.mAppToken) && recentsAnimationController.updateInputConsumerForApp(this.recentsAnimationInputConsumer.mWindowHandle, hasFocus)) {
                this.recentsAnimationInputConsumer.show(InputMonitor.this.mInputTransaction, w);
                this.mAddRecentsAnimationInputConsumerHandle = false;
            }
            if (w.inPinnedWindowingMode() && this.mAddPipInputConsumerHandle) {
                w.getBounds(InputMonitor.this.mTmpRect);
                this.pipInputConsumer.layout(InputMonitor.this.mInputTransaction, InputMonitor.this.mTmpRect);
                InputMonitor.this.mTmpRect.offsetTo(0, 0);
                this.pipInputConsumer.mWindowHandle.touchableRegion.set(InputMonitor.this.mTmpRect);
                this.pipInputConsumer.show(InputMonitor.this.mInputTransaction, w);
                this.mAddPipInputConsumerHandle = false;
            }
            if (this.mAddInputConsumerHandle && inputWindowHandle.layer <= this.navInputConsumer.mWindowHandle.layer) {
                this.navInputConsumer.show(InputMonitor.this.mInputTransaction, w);
                this.mAddInputConsumerHandle = false;
            }
            if (this.mAddWallpaperInputConsumerHandle && w.mAttrs.type == 2013 && w.isVisibleLw()) {
                this.wallpaperInputConsumer.show(InputMonitor.this.mInputTransaction, w);
                this.mAddWallpaperInputConsumerHandle = false;
            }
            if ((privateFlags & 2048) != 0) {
                InputMonitor.this.mDisableWallpaperTouchEvents = true;
            }
            boolean hasWallpaper = this.wallpaperController.isWallpaperTarget(w) && (privateFlags & 1024) == 0 && !InputMonitor.this.mDisableWallpaperTouchEvents;
            if (this.inDrag && isVisible && w.getDisplayContent().isDefaultDisplay) {
                InputMonitor.this.mService.mDragDropController.sendDragStartedIfNeededLocked(w);
            }
            InputMonitor.this.populateInputWindowHandle(inputWindowHandle, w, flags, type, isVisible, hasFocus, hasWallpaper);
            if (w.mWinAnimator.hasSurface()) {
                InputMonitor.this.mInputTransaction.setInputWindowInfo(w.mWinAnimator.mSurfaceController.mSurfaceControl, inputWindowHandle);
            }
        }
    }
}
