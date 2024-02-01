package com.android.server.wm;

import android.app.IActivityTaskManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import com.android.internal.annotations.GuardedBy;
import com.android.server.input.InputManagerService;
import com.xiaopeng.view.SharedDisplayManager;
import java.util.ArrayList;
import java.util.Iterator;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class TaskPositioningController {
    private final IActivityTaskManager mActivityManager;
    private final Handler mHandler;
    private final InputManagerService mInputManager;
    private SurfaceControl mInputSurface;
    private DisplayContent mPositioningDisplay;
    private final WindowManagerService mService;
    @GuardedBy({"WindowManagerSerivce.mWindowMap"})
    private TaskPositioner mTaskPositioner;
    private TaskPositionerPolicy mTaskPositionerPolicy;
    private boolean mSharedDisplayEnabled = SharedDisplayManager.enable();
    private final Rect mTmpClipRect = new Rect();

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPositioningLocked() {
        return this.mTaskPositioner != null;
    }

    InputWindowHandle getDragWindowHandleLocked() {
        TaskPositioner taskPositioner = this.mTaskPositioner;
        if (taskPositioner != null) {
            return taskPositioner.mDragWindowHandle;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskPositioningController(WindowManagerService service, InputManagerService inputManager, IActivityTaskManager activityManager, Looper looper) {
        this.mService = service;
        this.mInputManager = inputManager;
        this.mActivityManager = activityManager;
        this.mHandler = new Handler(looper);
        this.mTaskPositionerPolicy = new TaskPositionerPolicy(service, activityManager);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hideInputSurface(SurfaceControl.Transaction t, int displayId) {
        SurfaceControl surfaceControl;
        DisplayContent displayContent = this.mPositioningDisplay;
        if (displayContent != null && displayContent.getDisplayId() == displayId && (surfaceControl = this.mInputSurface) != null) {
            t.hide(surfaceControl);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void showInputSurface(SurfaceControl.Transaction t, int displayId) {
        DisplayContent displayContent = this.mPositioningDisplay;
        if (displayContent == null || displayContent.getDisplayId() != displayId) {
            return;
        }
        DisplayContent dc = this.mService.mRoot.getDisplayContent(displayId);
        if (this.mInputSurface == null) {
            this.mInputSurface = this.mService.makeSurfaceBuilder(dc.getSession()).setContainerLayer().setName("Drag and Drop Input Consumer").build();
        }
        InputWindowHandle h = getDragWindowHandleLocked();
        if (h == null) {
            Slog.w("WindowManager", "Drag is in progress but there is no drag window handle.");
            return;
        }
        t.show(this.mInputSurface);
        t.setInputWindowInfo(this.mInputSurface, h);
        t.setLayer(this.mInputSurface, Integer.MAX_VALUE);
        Display display = dc.getDisplay();
        Point p = new Point();
        display.getRealSize(p);
        this.mTmpClipRect.set(0, 0, p.x, p.y);
        t.setWindowCrop(this.mInputSurface, this.mTmpClipRect);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean startMovingTask(IWindow window, float startX, float startY) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                int i = 0;
                WindowState win = this.mService.windowForClientLocked((Session) null, window, false);
                boolean sharedTask = TaskPositionerPolicy.sharedTask(win);
                if (this.mSharedDisplayEnabled && sharedTask) {
                    if (this.mTaskPositionerPolicy == null) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return false;
                    }
                    if (startX == -1.0f && startY == -1.0f) {
                        i = 1;
                    }
                    int type = i;
                    this.mTaskPositionerPolicy.execute(type, win);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return true;
                } else if (!startPositioningLocked(win, false, false, startX, startY)) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } else {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    try {
                        this.mActivityManager.setFocusedTask(win.getTask().mTaskId);
                    } catch (RemoteException e) {
                    }
                    return true;
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleTapOutsideTask(final DisplayContent displayContent, final int x, final int y) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskPositioningController$u0oAwi82C-bAGo2JAsAc_9ZLi70
            @Override // java.lang.Runnable
            public final void run() {
                TaskPositioningController.this.lambda$handleTapOutsideTask$0$TaskPositioningController(displayContent, x, y);
            }
        });
    }

    public /* synthetic */ void lambda$handleTapOutsideTask$0$TaskPositioningController(DisplayContent displayContent, int x, int y) {
        int taskId = -1;
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                Task task = displayContent.findTaskForResizePoint(x, y);
                if (task != null) {
                    if (!startPositioningLocked(task.getTopVisibleAppMainWindow(), true, task.preserveOrientationOnResize(), x, y)) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    taskId = task.mTaskId;
                }
                WindowState window = displayContent.findFocusedWindow();
                ArrayList<Task> tasks = displayContent.getVisibleTasks();
                if (window == null) {
                    taskId = -1;
                }
                if (taskId >= 0) {
                    Iterator<Task> it = tasks.iterator();
                    while (true) {
                        if (!it.hasNext()) {
                            break;
                        }
                        Task t = it.next();
                        if (t != null && taskId == t.mTaskId) {
                            WindowState win = t.getTopVisibleAppMainWindow();
                            if (window != null && win != null && window.getAppToken() == win.getAppToken()) {
                                taskId = -1;
                                break;
                            }
                        }
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                if (taskId >= 0) {
                    try {
                        this.mActivityManager.setFocusedTask(taskId);
                    } catch (RemoteException e) {
                    }
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private boolean startPositioningLocked(WindowState win, boolean resize, boolean preserveOrientation, float startX, float startY) {
        WindowState transferFocusFromWin;
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d("WindowManager", "startPositioningLocked: win=" + win + ", resize=" + resize + ", preserveOrientation=" + preserveOrientation + ", {" + startX + ", " + startY + "}");
        }
        if (this.mSharedDisplayEnabled) {
            return false;
        }
        if (win == null || win.getAppToken() == null) {
            Slog.w("WindowManager", "startPositioningLocked: Bad window " + win);
            return false;
        } else if (win.mInputChannel == null) {
            Slog.wtf("WindowManager", "startPositioningLocked: " + win + " has no input channel,  probably being removed");
            return false;
        } else {
            DisplayContent displayContent = win.getDisplayContent();
            if (displayContent == null) {
                Slog.w("WindowManager", "startPositioningLocked: Invalid display content " + win);
                return false;
            }
            this.mPositioningDisplay = displayContent;
            this.mTaskPositioner = TaskPositioner.create(this.mService);
            this.mTaskPositioner.register(displayContent);
            if (displayContent.mCurrentFocus != null && displayContent.mCurrentFocus != win && displayContent.mCurrentFocus.mAppToken == win.mAppToken) {
                WindowState transferFocusFromWin2 = displayContent.mCurrentFocus;
                transferFocusFromWin = transferFocusFromWin2;
            } else {
                transferFocusFromWin = win;
            }
            if (!this.mInputManager.transferTouchFocus(transferFocusFromWin.mInputChannel, this.mTaskPositioner.mServerChannel)) {
                Slog.e("WindowManager", "startPositioningLocked: Unable to transfer touch focus");
                cleanUpTaskPositioner();
                return false;
            }
            this.mTaskPositioner.startDrag(win, resize, preserveOrientation, startX, startY);
            return true;
        }
    }

    public void finishTaskPositioning(IWindow window) {
        TaskPositioner taskPositioner = this.mTaskPositioner;
        if (taskPositioner != null && taskPositioner.mClientCallback == window.asBinder()) {
            finishTaskPositioning();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishTaskPositioning() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskPositioningController$z3n1stJjOdhDbXXrvPlvlqmON6k
            @Override // java.lang.Runnable
            public final void run() {
                TaskPositioningController.this.lambda$finishTaskPositioning$1$TaskPositioningController();
            }
        });
    }

    public /* synthetic */ void lambda$finishTaskPositioning$1$TaskPositioningController() {
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d("WindowManager", "finishPositioning");
        }
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                cleanUpTaskPositioner();
                this.mPositioningDisplay = null;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    private void cleanUpTaskPositioner() {
        TaskPositioner positioner = this.mTaskPositioner;
        if (positioner == null) {
            return;
        }
        this.mTaskPositioner = null;
        positioner.unregister();
    }
}
