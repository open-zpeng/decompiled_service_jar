package com.android.server.wm;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.Debug;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputManagerService;
import com.android.server.input.InputWindowHandle;
import com.android.server.policy.WindowManagerPolicy;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class InputMonitor implements InputManagerService.WindowManagerCallbacks {
    private boolean mAddInputConsumerHandle;
    private boolean mAddPipInputConsumerHandle;
    private boolean mAddRecentsAnimationInputConsumerHandle;
    private boolean mAddWallpaperInputConsumerHandle;
    private boolean mDisableWallpaperTouchEvents;
    private InputWindowHandle mFocusedInputWindowHandle;
    private boolean mInputDevicesReady;
    private boolean mInputDispatchEnabled;
    private boolean mInputDispatchFrozen;
    private WindowState mInputFocus;
    private int mInputWindowHandleCount;
    private InputWindowHandle[] mInputWindowHandles;
    private final WindowManagerService mService;
    private String mInputFreezeReason = null;
    private boolean mUpdateInputWindowsNeeded = true;
    private final Rect mTmpRect = new Rect();
    private final UpdateInputForAllWindowsConsumer mUpdateInputForAllWindowsConsumer = new UpdateInputForAllWindowsConsumer();
    private final Object mInputDevicesReadyMonitor = new Object();
    private final ArrayMap<String, InputConsumerImpl> mInputConsumers = new ArrayMap<>();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class EventReceiverInputConsumer extends InputConsumerImpl implements WindowManagerPolicy.InputConsumer {
        private final InputEventReceiver mInputEventReceiver;
        private InputMonitor mInputMonitor;

        EventReceiverInputConsumer(WindowManagerService service, InputMonitor monitor, Looper looper, String name, InputEventReceiver.Factory inputEventReceiverFactory, int clientPid, UserHandle clientUser) {
            super(service, null, name, null, clientPid, clientUser);
            this.mInputMonitor = monitor;
            this.mInputEventReceiver = inputEventReceiverFactory.createInputEventReceiver(this.mClientChannel, looper);
        }

        @Override // com.android.server.policy.WindowManagerPolicy.InputConsumer
        public void dismiss() {
            synchronized (this.mService.mWindowMap) {
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

    public InputMonitor(WindowManagerService service) {
        this.mService = service;
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
            return true;
        }
        return false;
    }

    InputConsumerImpl getInputConsumer(String name, int displayId) {
        if (displayId == 0) {
            return this.mInputConsumers.get(name);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void layoutInputConsumers(int dw, int dh) {
        for (int i = this.mInputConsumers.size() - 1; i >= 0; i--) {
            this.mInputConsumers.valueAt(i).layout(dw, dh);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowManagerPolicy.InputConsumer createInputConsumer(Looper looper, String name, InputEventReceiver.Factory inputEventReceiverFactory) {
        if (this.mInputConsumers.containsKey(name)) {
            throw new IllegalStateException("Existing input consumer found with name: " + name);
        }
        EventReceiverInputConsumer consumer = new EventReceiverInputConsumer(this.mService, this, looper, name, inputEventReceiverFactory, Process.myPid(), UserHandle.SYSTEM);
        addInputConsumer(name, consumer);
        return consumer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void createInputConsumer(IBinder token, String name, InputChannel inputChannel, int clientPid, UserHandle clientUser) {
        if (this.mInputConsumers.containsKey(name)) {
            throw new IllegalStateException("Existing input consumer found with name: " + name);
        }
        InputConsumerImpl consumer = new InputConsumerImpl(this.mService, token, name, inputChannel, clientPid, clientUser);
        char c = 65535;
        int hashCode = name.hashCode();
        if (hashCode != 1024719987) {
            if (hashCode == 1415830696 && name.equals("wallpaper_input_consumer")) {
                c = 0;
            }
        } else if (name.equals("pip_input_consumer")) {
            c = 1;
        }
        switch (c) {
            case 0:
                consumer.mWindowHandle.hasWallpaper = true;
                break;
            case 1:
                consumer.mWindowHandle.layoutParamsFlags |= 32;
                break;
        }
        addInputConsumer(name, consumer);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public void notifyInputChannelBroken(InputWindowHandle inputWindowHandle) {
        if (inputWindowHandle == null) {
            return;
        }
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                WindowState windowState = (WindowState) inputWindowHandle.windowState;
                if (windowState != null) {
                    Slog.i("WindowManager", "WINDOW DIED " + windowState);
                    windowState.removeIfPossible();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public long notifyANR(InputApplicationHandle inputApplicationHandle, InputWindowHandle inputWindowHandle, String reason) {
        boolean abort;
        AppWindowToken appWindowToken = null;
        WindowState windowState = null;
        boolean aboveSystem = false;
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (inputWindowHandle != null && (windowState = (WindowState) inputWindowHandle.windowState) != null) {
                    appWindowToken = windowState.mAppToken;
                }
                if (appWindowToken == null && inputApplicationHandle != null) {
                    appWindowToken = (AppWindowToken) inputApplicationHandle.appWindowToken;
                }
                abort = false;
                if (windowState != null) {
                    Slog.i("WindowManager", "Input event dispatching timed out sending to " + ((Object) windowState.mAttrs.getTitle()) + ".  Reason: " + reason);
                    int systemAlertLayer = this.mService.mPolicy.getWindowLayerFromTypeLw(2038, windowState.mOwnerCanAddInternalSystemWindow);
                    aboveSystem = windowState.mBaseLayer > systemAlertLayer;
                } else if (appWindowToken != null) {
                    Slog.i("WindowManager", "Input event dispatching timed out sending to application " + appWindowToken.stringName + ".  Reason: " + reason);
                } else {
                    Slog.i("WindowManager", "Input event dispatching timed out .  Reason: " + reason);
                }
                this.mService.saveANRStateLocked(appWindowToken, windowState, reason);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        this.mService.mAmInternal.saveANRState(reason);
        if (appWindowToken != null && appWindowToken.appToken != null) {
            AppWindowContainerController controller = appWindowToken.getController();
            if (controller != null) {
                if (controller.keyDispatchingTimedOut(reason, windowState != null ? windowState.mSession.mPid : -1)) {
                    abort = true;
                }
            }
            if (!abort) {
                return appWindowToken.mInputDispatchingTimeoutNanos;
            }
        } else if (windowState != null) {
            try {
                long timeout = ActivityManager.getService().inputDispatchingTimedOut(windowState.mSession.mPid, aboveSystem, reason);
                if (timeout >= 0) {
                    return 1000000 * timeout;
                }
            } catch (RemoteException e) {
            }
        }
        return 0L;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addInputWindowHandle(InputWindowHandle windowHandle) {
        if (this.mInputWindowHandles == null) {
            this.mInputWindowHandles = new InputWindowHandle[16];
        }
        if (this.mInputWindowHandleCount >= this.mInputWindowHandles.length) {
            this.mInputWindowHandles = (InputWindowHandle[]) Arrays.copyOf(this.mInputWindowHandles, this.mInputWindowHandleCount * 2);
        }
        InputWindowHandle[] inputWindowHandleArr = this.mInputWindowHandles;
        int i = this.mInputWindowHandleCount;
        this.mInputWindowHandleCount = i + 1;
        inputWindowHandleArr[i] = windowHandle;
    }

    void addInputWindowHandle(InputWindowHandle inputWindowHandle, WindowState child, int flags, int type, boolean isVisible, boolean hasFocus, boolean hasWallpaper) {
        inputWindowHandle.name = child.toString();
        inputWindowHandle.layoutParamsFlags = child.getTouchableRegion(inputWindowHandle.touchableRegion, flags);
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
        Rect frame = child.mFrame;
        inputWindowHandle.frameLeft = frame.left;
        inputWindowHandle.frameTop = frame.top;
        inputWindowHandle.frameRight = frame.right;
        inputWindowHandle.frameBottom = frame.bottom;
        if (child.mGlobalScale != 1.0f) {
            inputWindowHandle.scaleFactor = 1.0f / child.mGlobalScale;
        } else {
            inputWindowHandle.scaleFactor = 1.0f;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT) {
            Slog.d("WindowManager", "addInputWindowHandle: " + child + ", " + inputWindowHandle);
        }
        addInputWindowHandle(inputWindowHandle);
        if (hasFocus) {
            this.mFocusedInputWindowHandle = inputWindowHandle;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearInputWindowHandlesLw() {
        while (this.mInputWindowHandleCount != 0) {
            InputWindowHandle[] inputWindowHandleArr = this.mInputWindowHandles;
            int i = this.mInputWindowHandleCount - 1;
            this.mInputWindowHandleCount = i;
            inputWindowHandleArr[i] = null;
        }
        this.mFocusedInputWindowHandle = null;
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
        this.mUpdateInputWindowsNeeded = false;
        boolean inDrag = this.mService.mDragDropController.dragDropActiveLocked();
        if (inDrag) {
            if (WindowManagerDebugConfig.DEBUG_DRAG) {
                Log.d("WindowManager", "Inserting drag window");
            }
            InputWindowHandle dragWindowHandle = this.mService.mDragDropController.getInputWindowHandleLocked();
            if (dragWindowHandle != null) {
                addInputWindowHandle(dragWindowHandle);
            } else {
                Slog.w("WindowManager", "Drag is in progress but there is no drag window handle.");
            }
        }
        boolean inPositioning = this.mService.mTaskPositioningController.isPositioningLocked();
        if (inPositioning) {
            if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
                Log.d("WindowManager", "Inserting window handle for repositioning");
            }
            InputWindowHandle dragWindowHandle2 = this.mService.mTaskPositioningController.getDragWindowHandleLocked();
            if (dragWindowHandle2 != null) {
                addInputWindowHandle(dragWindowHandle2);
            } else {
                Slog.e("WindowManager", "Repositioning is in progress but there is no drag window handle.");
            }
        }
        this.mUpdateInputForAllWindowsConsumer.updateInputWindows(inDrag);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public void notifyConfigurationChanged() {
        this.mService.sendNewConfiguration(0);
        synchronized (this.mInputDevicesReadyMonitor) {
            if (!this.mInputDevicesReady) {
                this.mInputDevicesReady = true;
                this.mInputDevicesReadyMonitor.notifyAll();
            }
        }
    }

    public boolean waitForInputDevicesReady(long timeoutMillis) {
        boolean z;
        synchronized (this.mInputDevicesReadyMonitor) {
            if (!this.mInputDevicesReady) {
                try {
                    this.mInputDevicesReadyMonitor.wait(timeoutMillis);
                } catch (InterruptedException e) {
                }
            }
            z = this.mInputDevicesReady;
        }
        return z;
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        this.mService.mPolicy.notifyLidSwitchChanged(whenNanos, lidOpen);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered) {
        this.mService.mPolicy.notifyCameraLensCoverSwitchChanged(whenNanos, lensCovered);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        return this.mService.mPolicy.interceptKeyBeforeQueueing(event, policyFlags);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags) {
        return this.mService.mPolicy.interceptMotionBeforeQueueingNonInteractive(whenNanos, policyFlags);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public long interceptKeyBeforeDispatching(InputWindowHandle focus, KeyEvent event, int policyFlags) {
        WindowState windowState = focus != null ? (WindowState) focus.windowState : null;
        return this.mService.mPolicy.interceptKeyBeforeDispatching(windowState, event, policyFlags);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public KeyEvent dispatchUnhandledKey(InputWindowHandle focus, KeyEvent event, int policyFlags) {
        WindowState windowState = focus != null ? (WindowState) focus.windowState : null;
        return this.mService.mPolicy.dispatchUnhandledKey(windowState, event, policyFlags);
    }

    @Override // com.android.server.input.InputManagerService.WindowManagerCallbacks
    public int getPointerLayer() {
        return (this.mService.mPolicy.getWindowLayerFromTypeLw(2018) * 10000) + 1000;
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
            this.mService.mInputManager.setFocusedApplication(null);
            return;
        }
        InputApplicationHandle handle = newApp.mInputApplicationHandle;
        handle.name = newApp.toString();
        handle.dispatchingTimeoutNanos = newApp.mInputDispatchingTimeoutNanos;
        this.mService.mInputManager.setFocusedApplication(handle);
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

    public void freezeInputDispatchingLw() {
        if (!this.mInputDispatchFrozen) {
            if (WindowManagerDebugConfig.DEBUG_INPUT) {
                Slog.v("WindowManager", "Freezing input dispatching");
            }
            this.mInputDispatchFrozen = true;
            boolean z = WindowManagerDebugConfig.DEBUG_INPUT;
            this.mInputFreezeReason = Debug.getCallers(6);
            updateInputDispatchModeLw();
        }
    }

    public void thawInputDispatchingLw() {
        if (this.mInputDispatchFrozen) {
            if (WindowManagerDebugConfig.DEBUG_INPUT) {
                Slog.v("WindowManager", "Thawing input dispatching");
            }
            this.mInputDispatchFrozen = false;
            this.mInputFreezeReason = null;
            updateInputDispatchModeLw();
        }
    }

    public void setEventDispatchingLw(boolean enabled) {
        if (this.mInputDispatchEnabled != enabled) {
            if (WindowManagerDebugConfig.DEBUG_INPUT) {
                Slog.v("WindowManager", "Setting event dispatching to " + enabled);
            }
            this.mInputDispatchEnabled = enabled;
            updateInputDispatchModeLw();
        }
    }

    private void updateInputDispatchModeLw() {
        this.mService.mInputManager.setInputDispatchMode(this.mInputDispatchEnabled, this.mInputDispatchFrozen);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        if (this.mInputFreezeReason != null) {
            pw.println(prefix + "mInputFreezeReason=" + this.mInputFreezeReason);
        }
        Set<String> inputConsumerKeys = this.mInputConsumers.keySet();
        if (!inputConsumerKeys.isEmpty()) {
            pw.println(prefix + "InputConsumers:");
            for (String key : inputConsumerKeys) {
                this.mInputConsumers.get(key).dump(pw, key, prefix);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class UpdateInputForAllWindowsConsumer implements Consumer<WindowState> {
        boolean inDrag;
        InputConsumerImpl navInputConsumer;
        InputConsumerImpl pipInputConsumer;
        InputConsumerImpl recentsAnimationInputConsumer;
        WallpaperController wallpaperController;
        InputConsumerImpl wallpaperInputConsumer;

        private UpdateInputForAllWindowsConsumer() {
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void updateInputWindows(boolean inDrag) {
            Trace.traceBegin(32L, "updateInputWindows");
            this.navInputConsumer = InputMonitor.this.getInputConsumer("nav_input_consumer", 0);
            this.pipInputConsumer = InputMonitor.this.getInputConsumer("pip_input_consumer", 0);
            this.wallpaperInputConsumer = InputMonitor.this.getInputConsumer("wallpaper_input_consumer", 0);
            this.recentsAnimationInputConsumer = InputMonitor.this.getInputConsumer("recents_animation_input_consumer", 0);
            InputMonitor.this.mAddInputConsumerHandle = this.navInputConsumer != null;
            InputMonitor.this.mAddPipInputConsumerHandle = this.pipInputConsumer != null;
            InputMonitor.this.mAddWallpaperInputConsumerHandle = this.wallpaperInputConsumer != null;
            InputMonitor.this.mAddRecentsAnimationInputConsumerHandle = this.recentsAnimationInputConsumer != null;
            InputMonitor.this.mTmpRect.setEmpty();
            InputMonitor.this.mDisableWallpaperTouchEvents = false;
            this.inDrag = inDrag;
            this.wallpaperController = InputMonitor.this.mService.mRoot.mWallpaperController;
            InputMonitor.this.mService.mRoot.forAllWindows((Consumer<WindowState>) this, true);
            if (InputMonitor.this.mAddWallpaperInputConsumerHandle) {
                InputMonitor.this.addInputWindowHandle(this.wallpaperInputConsumer.mWindowHandle);
            }
            InputMonitor.this.mService.mInputManager.setInputWindows(InputMonitor.this.mInputWindowHandles, InputMonitor.this.mFocusedInputWindowHandle);
            InputMonitor.this.clearInputWindowHandlesLw();
            Trace.traceEnd(32L);
        }

        @Override // java.util.function.Consumer
        public void accept(WindowState w) {
            RecentsAnimationController recentsAnimationController;
            InputChannel inputChannel = w.mInputChannel;
            InputWindowHandle inputWindowHandle = w.mInputWindowHandle;
            if (inputChannel == null || inputWindowHandle == null || w.mRemoved || w.canReceiveTouchInput()) {
                return;
            }
            int flags = w.mAttrs.flags;
            int privateFlags = w.mAttrs.privateFlags;
            int type = w.mAttrs.type;
            boolean hasFocus = w == InputMonitor.this.mInputFocus;
            boolean isVisible = w.isVisibleLw();
            if (InputMonitor.this.mAddRecentsAnimationInputConsumerHandle && (recentsAnimationController = InputMonitor.this.mService.getRecentsAnimationController()) != null && recentsAnimationController.hasInputConsumerForApp(w.mAppToken)) {
                if (recentsAnimationController.updateInputConsumerForApp(this.recentsAnimationInputConsumer, hasFocus)) {
                    InputMonitor.this.addInputWindowHandle(this.recentsAnimationInputConsumer.mWindowHandle);
                    InputMonitor.this.mAddRecentsAnimationInputConsumerHandle = false;
                    return;
                }
                return;
            }
            if (w.inPinnedWindowingMode()) {
                if (InputMonitor.this.mAddPipInputConsumerHandle && inputWindowHandle.layer <= this.pipInputConsumer.mWindowHandle.layer) {
                    w.getBounds(InputMonitor.this.mTmpRect);
                    this.pipInputConsumer.mWindowHandle.touchableRegion.set(InputMonitor.this.mTmpRect);
                    InputMonitor.this.addInputWindowHandle(this.pipInputConsumer.mWindowHandle);
                    InputMonitor.this.mAddPipInputConsumerHandle = false;
                }
                if (!hasFocus) {
                    return;
                }
            }
            if (InputMonitor.this.mAddInputConsumerHandle && inputWindowHandle.layer <= this.navInputConsumer.mWindowHandle.layer) {
                InputMonitor.this.addInputWindowHandle(this.navInputConsumer.mWindowHandle);
                InputMonitor.this.mAddInputConsumerHandle = false;
            }
            if (InputMonitor.this.mAddWallpaperInputConsumerHandle && w.mAttrs.type == 2013 && w.isVisibleLw()) {
                InputMonitor.this.addInputWindowHandle(this.wallpaperInputConsumer.mWindowHandle);
                InputMonitor.this.mAddWallpaperInputConsumerHandle = false;
            }
            if ((privateFlags & 2048) != 0) {
                InputMonitor.this.mDisableWallpaperTouchEvents = true;
            }
            boolean hasWallpaper = this.wallpaperController.isWallpaperTarget(w) && (privateFlags & 1024) == 0 && !InputMonitor.this.mDisableWallpaperTouchEvents;
            if (this.inDrag && isVisible && w.getDisplayContent().isDefaultDisplay) {
                InputMonitor.this.mService.mDragDropController.sendDragStartedIfNeededLocked(w);
            }
            InputMonitor.this.addInputWindowHandle(inputWindowHandle, w, flags, type, isVisible, hasFocus, hasWallpaper);
        }
    }
}
