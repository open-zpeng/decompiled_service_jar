package com.android.server.wm;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Slog;
import android.view.IWindow;
import com.android.internal.util.Preconditions;
import com.android.server.wm.DragState;
import com.android.server.wm.WindowManagerInternal;
import java.util.concurrent.atomic.AtomicReference;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class DragDropController {
    private static final float DRAG_SHADOW_ALPHA_TRANSPARENT = 0.7071f;
    private static final long DRAG_TIMEOUT_MS = 5000;
    static final int MSG_ANIMATION_END = 2;
    static final int MSG_DRAG_END_TIMEOUT = 0;
    static final int MSG_TEAR_DOWN_DRAG_AND_DROP_INPUT = 1;
    private AtomicReference<WindowManagerInternal.IDragDropCallback> mCallback = new AtomicReference<>(new WindowManagerInternal.IDragDropCallback() { // from class: com.android.server.wm.DragDropController.1
    });
    private DragState mDragState;
    private final Handler mHandler;
    private WindowManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dragDropActiveLocked() {
        DragState dragState = this.mDragState;
        return (dragState == null || dragState.isClosing()) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerCallback(WindowManagerInternal.IDragDropCallback callback) {
        Preconditions.checkNotNull(callback);
        this.mCallback.set(callback);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DragDropController(WindowManagerService service, Looper looper) {
        this.mService = service;
        this.mHandler = new DragHandler(service, looper);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendDragStartedIfNeededLocked(WindowState window) {
        this.mDragState.sendDragStartedIfNeededLocked(window);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:147:0x0308 A[Catch: all -> 0x031e, TRY_ENTER, TryCatch #16 {all -> 0x031e, blocks: (B:156:0x031f, B:147:0x0308, B:148:0x030b, B:150:0x030f, B:152:0x0317, B:154:0x031d, B:101:0x0242, B:102:0x0245, B:104:0x0249, B:106:0x0251, B:107:0x0256), top: B:193:0x006e }] */
    /* JADX WARN: Removed duplicated region for block: B:150:0x030f A[Catch: all -> 0x031e, TryCatch #16 {all -> 0x031e, blocks: (B:156:0x031f, B:147:0x0308, B:148:0x030b, B:150:0x030f, B:152:0x0317, B:154:0x031d, B:101:0x0242, B:102:0x0245, B:104:0x0249, B:106:0x0251, B:107:0x0256), top: B:193:0x006e }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public android.os.IBinder performDrag(android.view.SurfaceSession r20, int r21, int r22, android.view.IWindow r23, int r24, android.view.SurfaceControl r25, int r26, float r27, float r28, float r29, float r30, android.content.ClipData r31) {
        /*
            Method dump skipped, instructions count: 829
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DragDropController.performDrag(android.view.SurfaceSession, int, int, android.view.IWindow, int, android.view.SurfaceControl, int, float, float, float, float, android.content.ClipData):android.os.IBinder");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportDropResult(IWindow window, boolean consumed) {
        AtomicReference<WindowManagerInternal.IDragDropCallback> atomicReference;
        WindowManagerInternal.IDragDropCallback iDragDropCallback;
        WindowManagerInternal.IDragDropCallback iDragDropCallback2;
        IBinder token = window.asBinder();
        if (WindowManagerDebugConfig.DEBUG_DRAG) {
            Slog.d("WindowManager", "Drop result=" + consumed + " reported by " + token);
        }
        this.mCallback.get().preReportDropResult(window, consumed);
        try {
            synchronized (this.mService.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mDragState == null) {
                    Slog.w("WindowManager", "Drop result given but no drag in progress");
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (this.mDragState.mToken != token) {
                    Slog.w("WindowManager", "Invalid drop-result claim by " + window);
                    throw new IllegalStateException("reportDropResult() by non-recipient");
                } else {
                    this.mHandler.removeMessages(0, window.asBinder());
                    WindowState callingWin = this.mService.windowForClientLocked((Session) null, window, false);
                    if (callingWin != null) {
                        this.mDragState.mDragResult = consumed;
                        this.mDragState.endDragLocked();
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    Slog.w("WindowManager", "Bad result-reporting window " + window);
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            }
        } finally {
            this.mCallback.get().postReportDropResult();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelDragAndDrop(IBinder dragToken, boolean skipAnimation) {
        if (WindowManagerDebugConfig.DEBUG_DRAG) {
            Slog.d("WindowManager", "cancelDragAndDrop");
        }
        this.mCallback.get().preCancelDragAndDrop(dragToken);
        try {
            synchronized (this.mService.mGlobalLock) {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mDragState == null) {
                    Slog.w("WindowManager", "cancelDragAndDrop() without prepareDrag()");
                    throw new IllegalStateException("cancelDragAndDrop() without prepareDrag()");
                } else if (this.mDragState.mToken != dragToken) {
                    Slog.w("WindowManager", "cancelDragAndDrop() does not match prepareDrag()");
                    throw new IllegalStateException("cancelDragAndDrop() does not match prepareDrag()");
                } else {
                    this.mDragState.mDragResult = false;
                    this.mDragState.cancelDragLocked(skipAnimation);
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
        } finally {
            this.mCallback.get().postCancelDragAndDrop();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleMotionEvent(boolean keepHandling, float newX, float newY) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (!dragDropActiveLocked()) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                if (keepHandling) {
                    this.mDragState.notifyMoveLocked(newX, newY);
                } else {
                    this.mDragState.notifyDropLocked(newX, newY);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dragRecipientEntered(IWindow window) {
        if (WindowManagerDebugConfig.DEBUG_DRAG) {
            Slog.d("WindowManager", "Drag into new candidate view @ " + window.asBinder());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dragRecipientExited(IWindow window) {
        if (WindowManagerDebugConfig.DEBUG_DRAG) {
            Slog.d("WindowManager", "Drag from old candidate view @ " + window.asBinder());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendHandlerMessage(int what, Object arg) {
        this.mHandler.obtainMessage(what, arg).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendTimeoutMessage(int what, Object arg) {
        this.mHandler.removeMessages(what, arg);
        Message msg = this.mHandler.obtainMessage(what, arg);
        this.mHandler.sendMessageDelayed(msg, DRAG_TIMEOUT_MS);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onDragStateClosedLocked(DragState dragState) {
        if (this.mDragState != dragState) {
            Slog.wtf("WindowManager", "Unknown drag state is closed");
        } else {
            this.mDragState = null;
        }
    }

    /* loaded from: classes2.dex */
    private class DragHandler extends Handler {
        private final WindowManagerService mService;

        DragHandler(WindowManagerService service, Looper looper) {
            super(looper);
            this.mService = service;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 0) {
                IBinder win = (IBinder) msg.obj;
                if (WindowManagerDebugConfig.DEBUG_DRAG) {
                    Slog.w("WindowManager", "Timeout ending drag to win " + win);
                }
                synchronized (this.mService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (DragDropController.this.mDragState != null) {
                            DragDropController.this.mDragState.mDragResult = false;
                            DragDropController.this.mDragState.endDragLocked();
                        }
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } else if (i == 1) {
                if (WindowManagerDebugConfig.DEBUG_DRAG) {
                    Slog.d("WindowManager", "Drag ending; tearing down input channel");
                }
                DragState.InputInterceptor interceptor = (DragState.InputInterceptor) msg.obj;
                if (interceptor == null) {
                    return;
                }
                synchronized (this.mService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        interceptor.tearDown();
                    } finally {
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } else if (i == 2) {
                synchronized (this.mService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (DragDropController.this.mDragState != null) {
                            DragDropController.this.mDragState.closeLocked();
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                        Slog.wtf("WindowManager", "mDragState unexpectedly became null while plyaing animation");
                    } finally {
                        WindowManagerService.resetPriorityAfterLockedSection();
                    }
                }
            }
        }
    }
}
