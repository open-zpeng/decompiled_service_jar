package com.android.server.wm;

import android.animation.Animator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.util.Slog;
import android.view.Display;
import android.view.DragEvent;
import android.view.IWindow;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import com.android.internal.view.IDragAndDropPermissions;
import com.android.server.LocalServices;
import com.android.server.usb.descriptors.UsbACInterface;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Consumer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class DragState {
    private static final String ANIMATED_PROPERTY_ALPHA = "alpha";
    private static final String ANIMATED_PROPERTY_SCALE = "scale";
    private static final String ANIMATED_PROPERTY_X = "x";
    private static final String ANIMATED_PROPERTY_Y = "y";
    private static final int DRAG_FLAGS_URI_ACCESS = 3;
    private static final int DRAG_FLAGS_URI_PERMISSIONS = 195;
    private static final long MAX_ANIMATION_DURATION_MS = 375;
    private static final long MIN_ANIMATION_DURATION_MS = 195;
    private ValueAnimator mAnimator;
    boolean mCrossProfileCopyAllowed;
    float mCurrentX;
    float mCurrentY;
    ClipData mData;
    ClipDescription mDataDescription;
    DisplayContent mDisplayContent;
    final DragDropController mDragDropController;
    boolean mDragInProgress;
    boolean mDragResult;
    int mFlags;
    InputInterceptor mInputInterceptor;
    SurfaceControl mInputSurface;
    private boolean mIsClosing;
    IBinder mLocalWin;
    float mOriginalAlpha;
    float mOriginalX;
    float mOriginalY;
    int mPid;
    final WindowManagerService mService;
    int mSourceUserId;
    SurfaceControl mSurfaceControl;
    WindowState mTargetWindow;
    float mThumbOffsetX;
    float mThumbOffsetY;
    IBinder mToken;
    int mTouchSource;
    final SurfaceControl.Transaction mTransaction;
    int mUid;
    volatile boolean mAnimationCompleted = false;
    private final Interpolator mCubicEaseOutInterpolator = new DecelerateInterpolator(1.5f);
    private Point mDisplaySize = new Point();
    private final Rect mTmpClipRect = new Rect();
    ArrayList<WindowState> mNotifiedWindows = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public DragState(WindowManagerService service, DragDropController controller, IBinder token, SurfaceControl surface, int flags, IBinder localWin) {
        this.mService = service;
        this.mDragDropController = controller;
        this.mToken = token;
        this.mSurfaceControl = surface;
        this.mFlags = flags;
        this.mLocalWin = localWin;
        this.mTransaction = service.mTransactionFactory.make();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isClosing() {
        return this.mIsClosing;
    }

    private void hideInputSurface() {
        SurfaceControl surfaceControl = this.mInputSurface;
        if (surfaceControl != null) {
            this.mTransaction.hide(surfaceControl).apply();
        }
    }

    private void showInputSurface() {
        if (this.mInputSurface == null) {
            WindowManagerService windowManagerService = this.mService;
            this.mInputSurface = windowManagerService.makeSurfaceBuilder(windowManagerService.mRoot.getDisplayContent(this.mDisplayContent.getDisplayId()).getSession()).setContainerLayer().setName("Drag and Drop Input Consumer").build();
        }
        InputWindowHandle h = getInputWindowHandle();
        if (h == null) {
            Slog.w("WindowManager", "Drag is in progress but there is no drag window handle.");
            return;
        }
        this.mTransaction.show(this.mInputSurface);
        this.mTransaction.setInputWindowInfo(this.mInputSurface, h);
        this.mTransaction.setLayer(this.mInputSurface, Integer.MAX_VALUE);
        this.mTmpClipRect.set(0, 0, this.mDisplaySize.x, this.mDisplaySize.y);
        this.mTransaction.setWindowCrop(this.mInputSurface, this.mTmpClipRect);
        this.mTransaction.syncInputWindows();
        this.mTransaction.apply();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void closeLocked() {
        float y;
        float y2;
        this.mIsClosing = true;
        if (this.mInputInterceptor != null) {
            if (WindowManagerDebugConfig.DEBUG_DRAG) {
                Slog.d("WindowManager", "unregistering drag input channel");
            }
            this.mDragDropController.sendHandlerMessage(1, this.mInputInterceptor);
            this.mInputInterceptor = null;
        }
        hideInputSurface();
        if (this.mDragInProgress) {
            int myPid = Process.myPid();
            if (WindowManagerDebugConfig.DEBUG_DRAG) {
                Slog.d("WindowManager", "broadcasting DRAG_ENDED");
            }
            Iterator<WindowState> it = this.mNotifiedWindows.iterator();
            while (it.hasNext()) {
                WindowState ws = it.next();
                if (!this.mDragResult && ws.mSession.mPid == this.mPid) {
                    float x = this.mCurrentX;
                    float y3 = this.mCurrentY;
                    y = y3;
                    y2 = x;
                } else {
                    y = 0.0f;
                    y2 = 0.0f;
                }
                DragEvent evt = DragEvent.obtain(4, y2, y, null, null, null, null, this.mDragResult);
                try {
                    ws.mClient.dispatchDragEvent(evt);
                } catch (RemoteException e) {
                    Slog.w("WindowManager", "Unable to drag-end window " + ws);
                }
                if (myPid != ws.mSession.mPid) {
                    evt.recycle();
                }
            }
            this.mNotifiedWindows.clear();
            this.mDragInProgress = false;
        }
        if (isFromSource(UsbACInterface.FORMAT_III_IEC1937_MPEG1_Layer1)) {
            this.mService.restorePointerIconLocked(this.mDisplayContent, this.mCurrentX, this.mCurrentY);
            this.mTouchSource = 0;
        }
        SurfaceControl surfaceControl = this.mSurfaceControl;
        if (surfaceControl != null) {
            this.mTransaction.reparent(surfaceControl, null).apply();
            this.mSurfaceControl = null;
        }
        if (this.mAnimator != null && !this.mAnimationCompleted) {
            Slog.wtf("WindowManager", "Unexpectedly destroying mSurfaceControl while animation is running");
        }
        this.mFlags = 0;
        this.mLocalWin = null;
        this.mToken = null;
        this.mData = null;
        this.mThumbOffsetY = 0.0f;
        this.mThumbOffsetX = 0.0f;
        this.mNotifiedWindows = null;
        this.mDragDropController.onDragStateClosedLocked(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public class InputInterceptor {
        InputChannel mClientChannel;
        InputApplicationHandle mDragApplicationHandle;
        InputWindowHandle mDragWindowHandle;
        DragInputEventReceiver mInputEventReceiver;
        InputChannel mServerChannel;

        InputInterceptor(Display display) {
            InputChannel[] channels = InputChannel.openInputChannelPair("drag");
            this.mServerChannel = channels[0];
            this.mClientChannel = channels[1];
            DragState.this.mService.mInputManager.registerInputChannel(this.mServerChannel, null);
            this.mInputEventReceiver = new DragInputEventReceiver(this.mClientChannel, DragState.this.mService.mH.getLooper(), DragState.this.mDragDropController);
            this.mDragApplicationHandle = new InputApplicationHandle(new Binder());
            InputApplicationHandle inputApplicationHandle = this.mDragApplicationHandle;
            inputApplicationHandle.name = "drag";
            inputApplicationHandle.dispatchingTimeoutNanos = 5000000000L;
            this.mDragWindowHandle = new InputWindowHandle(inputApplicationHandle, (IWindow) null, display.getDisplayId());
            InputWindowHandle inputWindowHandle = this.mDragWindowHandle;
            inputWindowHandle.name = "drag";
            inputWindowHandle.token = this.mServerChannel.getToken();
            this.mDragWindowHandle.layer = DragState.this.getDragLayerLocked();
            InputWindowHandle inputWindowHandle2 = this.mDragWindowHandle;
            inputWindowHandle2.layoutParamsFlags = 0;
            inputWindowHandle2.layoutParamsType = 2016;
            inputWindowHandle2.dispatchingTimeoutNanos = 5000000000L;
            inputWindowHandle2.visible = true;
            inputWindowHandle2.canReceiveKeys = false;
            inputWindowHandle2.hasFocus = true;
            inputWindowHandle2.hasWallpaper = false;
            inputWindowHandle2.paused = false;
            inputWindowHandle2.ownerPid = Process.myPid();
            this.mDragWindowHandle.ownerUid = Process.myUid();
            InputWindowHandle inputWindowHandle3 = this.mDragWindowHandle;
            inputWindowHandle3.inputFeatures = 0;
            inputWindowHandle3.scaleFactor = 1.0f;
            inputWindowHandle3.touchableRegion.setEmpty();
            InputWindowHandle inputWindowHandle4 = this.mDragWindowHandle;
            inputWindowHandle4.frameLeft = 0;
            inputWindowHandle4.frameTop = 0;
            inputWindowHandle4.frameRight = DragState.this.mDisplaySize.x;
            this.mDragWindowHandle.frameBottom = DragState.this.mDisplaySize.y;
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.d("WindowManager", "Pausing rotation during drag");
            }
            DragState.this.mDisplayContent.pauseRotationLocked();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void tearDown() {
            DragState.this.mService.mInputManager.unregisterInputChannel(this.mServerChannel);
            this.mInputEventReceiver.dispose();
            this.mInputEventReceiver = null;
            this.mClientChannel.dispose();
            this.mServerChannel.dispose();
            this.mClientChannel = null;
            this.mServerChannel = null;
            this.mDragWindowHandle = null;
            this.mDragApplicationHandle = null;
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.d("WindowManager", "Resuming rotation after drag");
            }
            DragState.this.mDisplayContent.resumeRotationLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public InputChannel getInputChannel() {
        InputInterceptor inputInterceptor = this.mInputInterceptor;
        if (inputInterceptor == null) {
            return null;
        }
        return inputInterceptor.mServerChannel;
    }

    InputWindowHandle getInputWindowHandle() {
        InputInterceptor inputInterceptor = this.mInputInterceptor;
        if (inputInterceptor == null) {
            return null;
        }
        return inputInterceptor.mDragWindowHandle;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void register(Display display) {
        display.getRealSize(this.mDisplaySize);
        if (WindowManagerDebugConfig.DEBUG_DRAG) {
            Slog.d("WindowManager", "registering drag input channel");
        }
        if (this.mInputInterceptor != null) {
            Slog.e("WindowManager", "Duplicate register of drag input channel");
            return;
        }
        this.mInputInterceptor = new InputInterceptor(display);
        showInputSurface();
    }

    int getDragLayerLocked() {
        return (this.mService.mPolicy.getWindowLayerFromTypeLw(2016) * 10000) + 1000;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void broadcastDragStartedLocked(final float touchX, final float touchY) {
        this.mCurrentX = touchX;
        this.mOriginalX = touchX;
        this.mCurrentY = touchY;
        this.mOriginalY = touchY;
        ClipData clipData = this.mData;
        this.mDataDescription = clipData != null ? clipData.getDescription() : null;
        this.mNotifiedWindows.clear();
        this.mDragInProgress = true;
        this.mSourceUserId = UserHandle.getUserId(this.mUid);
        UserManagerInternal userManager = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
        this.mCrossProfileCopyAllowed = true ^ userManager.getUserRestriction(this.mSourceUserId, "no_cross_profile_copy_paste");
        if (WindowManagerDebugConfig.DEBUG_DRAG) {
            Slog.d("WindowManager", "broadcasting DRAG_STARTED at (" + touchX + ", " + touchY + ")");
        }
        this.mDisplayContent.forAllWindows(new Consumer() { // from class: com.android.server.wm.-$$Lambda$DragState$-yUFIMrhYYccZ0gwd6eVcpAE93o
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                DragState.this.lambda$broadcastDragStartedLocked$0$DragState(touchX, touchY, (WindowState) obj);
            }
        }, false);
    }

    public /* synthetic */ void lambda$broadcastDragStartedLocked$0$DragState(float touchX, float touchY, WindowState w) {
        sendDragStartedLocked(w, touchX, touchY, this.mDataDescription);
    }

    private void sendDragStartedLocked(WindowState newWin, float touchX, float touchY, ClipDescription desc) {
        if (this.mDragInProgress && isValidDropTarget(newWin)) {
            DragEvent event = obtainDragEvent(newWin, 1, touchX, touchY, null, desc, null, null, false);
            try {
                try {
                    newWin.mClient.dispatchDragEvent(event);
                    this.mNotifiedWindows.add(newWin);
                    if (Process.myPid() == newWin.mSession.mPid) {
                        return;
                    }
                } catch (RemoteException e) {
                    Slog.w("WindowManager", "Unable to drag-start window " + newWin);
                    if (Process.myPid() == newWin.mSession.mPid) {
                        return;
                    }
                }
                event.recycle();
            } catch (Throwable th) {
                if (Process.myPid() != newWin.mSession.mPid) {
                    event.recycle();
                }
                throw th;
            }
        }
    }

    private boolean isValidDropTarget(WindowState targetWin) {
        if (targetWin == null || !targetWin.isPotentialDragTarget()) {
            return false;
        }
        if (((this.mFlags & 256) == 0 || !targetWindowSupportsGlobalDrag(targetWin)) && this.mLocalWin != targetWin.mClient.asBinder()) {
            return false;
        }
        return this.mCrossProfileCopyAllowed || this.mSourceUserId == UserHandle.getUserId(targetWin.getOwningUid());
    }

    private boolean targetWindowSupportsGlobalDrag(WindowState targetWin) {
        return targetWin.mAppToken == null || targetWin.mAppToken.mTargetSdk >= 24;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendDragStartedIfNeededLocked(WindowState newWin) {
        if (!this.mDragInProgress || isWindowNotified(newWin)) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_DRAG) {
            Slog.d("WindowManager", "need to send DRAG_STARTED to new window " + newWin);
        }
        sendDragStartedLocked(newWin, this.mCurrentX, this.mCurrentY, this.mDataDescription);
    }

    private boolean isWindowNotified(WindowState newWin) {
        Iterator<WindowState> it = this.mNotifiedWindows.iterator();
        while (it.hasNext()) {
            WindowState ws = it.next();
            if (ws == newWin) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void endDragLocked() {
        if (this.mAnimator != null) {
            return;
        }
        if (!this.mDragResult) {
            this.mAnimator = createReturnAnimationLocked();
        } else {
            closeLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelDragLocked(boolean skipAnimation) {
        if (this.mAnimator != null) {
            return;
        }
        if (!this.mDragInProgress || skipAnimation) {
            closeLocked();
        } else {
            this.mAnimator = createCancelAnimationLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyMoveLocked(float x, float y) {
        if (this.mAnimator != null) {
            return;
        }
        this.mCurrentX = x;
        this.mCurrentY = y;
        if (WindowManagerDebugConfig.SHOW_LIGHT_TRANSACTIONS) {
            Slog.i("WindowManager", ">>> OPEN TRANSACTION notifyMoveLocked");
        }
        this.mTransaction.setPosition(this.mSurfaceControl, x - this.mThumbOffsetX, y - this.mThumbOffsetY).apply();
        if (WindowManagerDebugConfig.SHOW_TRANSACTIONS) {
            Slog.i("WindowManager", "  DRAG " + this.mSurfaceControl + ": pos=(" + ((int) (x - this.mThumbOffsetX)) + "," + ((int) (y - this.mThumbOffsetY)) + ")");
        }
        notifyLocationLocked(x, y);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyLocationLocked(float x, float y) {
        WindowState touchedWin;
        WindowState touchedWin2 = this.mDisplayContent.getTouchableWinAtPointLocked(x, y);
        if (touchedWin2 != null && !isWindowNotified(touchedWin2)) {
            touchedWin = null;
        } else {
            touchedWin = touchedWin2;
        }
        try {
            int myPid = Process.myPid();
            if (touchedWin != this.mTargetWindow && this.mTargetWindow != null) {
                if (WindowManagerDebugConfig.DEBUG_DRAG) {
                    Slog.d("WindowManager", "sending DRAG_EXITED to " + this.mTargetWindow);
                }
                DragEvent evt = obtainDragEvent(this.mTargetWindow, 6, 0.0f, 0.0f, null, null, null, null, false);
                this.mTargetWindow.mClient.dispatchDragEvent(evt);
                if (myPid != this.mTargetWindow.mSession.mPid) {
                    evt.recycle();
                }
            }
            if (touchedWin != null) {
                DragEvent evt2 = obtainDragEvent(touchedWin, 2, x, y, null, null, null, null, false);
                touchedWin.mClient.dispatchDragEvent(evt2);
                if (myPid != touchedWin.mSession.mPid) {
                    evt2.recycle();
                }
            }
        } catch (RemoteException e) {
            Slog.w("WindowManager", "can't send drag notification to windows");
        }
        this.mTargetWindow = touchedWin;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x00ab, code lost:
        if (r9 != r13.mSession.mPid) goto L29;
     */
    /* JADX WARN: Code restructure failed: missing block: B:29:0x00ad, code lost:
        r2.recycle();
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00d3, code lost:
        if (r3 == r13.mSession.mPid) goto L30;
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x00d6, code lost:
        r20.mToken = r8;
     */
    /* JADX WARN: Code restructure failed: missing block: B:39:0x00d8, code lost:
        return;
     */
    /* JADX WARN: Removed duplicated region for block: B:43:0x00e0  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void notifyDropLocked(float r21, float r22) {
        /*
            Method dump skipped, instructions count: 228
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DragState.notifyDropLocked(float, float):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInProgress() {
        return this.mDragInProgress;
    }

    private static DragEvent obtainDragEvent(WindowState win, int action, float x, float y, Object localState, ClipDescription description, ClipData data, IDragAndDropPermissions dragAndDropPermissions, boolean result) {
        float winX = win.translateToWindowX(x);
        float winY = win.translateToWindowY(y);
        return DragEvent.obtain(action, winX, winY, localState, description, data, dragAndDropPermissions, result);
    }

    private ValueAnimator createReturnAnimationLocked() {
        float f = this.mCurrentX;
        float f2 = this.mThumbOffsetX;
        float[] fArr = {f - f2, this.mOriginalX - f2};
        float f3 = this.mCurrentY;
        float f4 = this.mThumbOffsetY;
        float[] fArr2 = {f3 - f4, this.mOriginalY - f4};
        float f5 = this.mOriginalAlpha;
        final ValueAnimator animator = ValueAnimator.ofPropertyValuesHolder(PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_X, fArr), PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_Y, fArr2), PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_SCALE, 1.0f, 1.0f), PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_ALPHA, f5, f5 / 2.0f));
        float translateX = this.mOriginalX - this.mCurrentX;
        float translateY = this.mOriginalY - this.mCurrentY;
        double travelDistance = Math.sqrt((translateX * translateX) + (translateY * translateY));
        double displayDiagonal = Math.sqrt((this.mDisplaySize.x * this.mDisplaySize.x) + (this.mDisplaySize.y * this.mDisplaySize.y));
        long duration = ((long) ((travelDistance / displayDiagonal) * 180.0d)) + MIN_ANIMATION_DURATION_MS;
        AnimationListener listener = new AnimationListener();
        animator.setDuration(duration);
        animator.setInterpolator(this.mCubicEaseOutInterpolator);
        animator.addListener(listener);
        animator.addUpdateListener(listener);
        this.mService.mAnimationHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$DragState$4E4tzlfJ9AKYEiVk7F8SFlBLwPc
            @Override // java.lang.Runnable
            public final void run() {
                animator.start();
            }
        });
        return animator;
    }

    private ValueAnimator createCancelAnimationLocked() {
        float f = this.mCurrentX;
        float[] fArr = {f - this.mThumbOffsetX, f};
        float f2 = this.mCurrentY;
        final ValueAnimator animator = ValueAnimator.ofPropertyValuesHolder(PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_X, fArr), PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_Y, f2 - this.mThumbOffsetY, f2), PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_SCALE, 1.0f, 0.0f), PropertyValuesHolder.ofFloat(ANIMATED_PROPERTY_ALPHA, this.mOriginalAlpha, 0.0f));
        AnimationListener listener = new AnimationListener();
        animator.setDuration(MIN_ANIMATION_DURATION_MS);
        animator.setInterpolator(this.mCubicEaseOutInterpolator);
        animator.addListener(listener);
        animator.addUpdateListener(listener);
        this.mService.mAnimationHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$DragState$WVn6-eGpkutjNAUr_QLMbFLA5qw
            @Override // java.lang.Runnable
            public final void run() {
                animator.start();
            }
        });
        return animator;
    }

    private boolean isFromSource(int source) {
        return (this.mTouchSource & source) == source;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePointerIconLocked(int touchSource) {
        this.mTouchSource = touchSource;
        if (isFromSource(UsbACInterface.FORMAT_III_IEC1937_MPEG1_Layer1)) {
            InputManager.getInstance().setPointerIconType(1021);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class AnimationListener implements ValueAnimator.AnimatorUpdateListener, Animator.AnimatorListener {
        private AnimationListener() {
        }

        @Override // android.animation.ValueAnimator.AnimatorUpdateListener
        public void onAnimationUpdate(ValueAnimator animation) {
            SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            try {
                transaction.setPosition(DragState.this.mSurfaceControl, ((Float) animation.getAnimatedValue(DragState.ANIMATED_PROPERTY_X)).floatValue(), ((Float) animation.getAnimatedValue(DragState.ANIMATED_PROPERTY_Y)).floatValue());
                transaction.setAlpha(DragState.this.mSurfaceControl, ((Float) animation.getAnimatedValue(DragState.ANIMATED_PROPERTY_ALPHA)).floatValue());
                transaction.setMatrix(DragState.this.mSurfaceControl, ((Float) animation.getAnimatedValue(DragState.ANIMATED_PROPERTY_SCALE)).floatValue(), 0.0f, 0.0f, ((Float) animation.getAnimatedValue(DragState.ANIMATED_PROPERTY_SCALE)).floatValue());
                transaction.apply();
                transaction.close();
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    try {
                        transaction.close();
                    } catch (Throwable th3) {
                        th.addSuppressed(th3);
                    }
                    throw th2;
                }
            }
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationStart(Animator animator) {
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationCancel(Animator animator) {
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationRepeat(Animator animator) {
        }

        @Override // android.animation.Animator.AnimatorListener
        public void onAnimationEnd(Animator animator) {
            DragState dragState = DragState.this;
            dragState.mAnimationCompleted = true;
            dragState.mDragDropController.sendHandlerMessage(2, null);
        }
    }
}
