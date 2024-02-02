package com.android.server.wm;

import android.app.IActivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IWindow;
import com.android.internal.annotations.GuardedBy;
import com.android.server.input.InputManagerService;
import com.android.server.input.InputWindowHandle;
import java.util.ArrayList;
import java.util.Iterator;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TaskPositioningController {
    private final IActivityManager mActivityManager;
    private final Handler mHandler;
    private final InputManagerService mInputManager;
    private final InputMonitor mInputMonitor;
    private final WindowManagerService mService;
    @GuardedBy("WindowManagerSerivce.mWindowMap")
    private TaskPositioner mTaskPositioner;

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPositioningLocked() {
        return this.mTaskPositioner != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public InputWindowHandle getDragWindowHandleLocked() {
        if (this.mTaskPositioner != null) {
            return this.mTaskPositioner.mDragWindowHandle;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskPositioningController(WindowManagerService service, InputManagerService inputManager, InputMonitor inputMonitor, IActivityManager activityManager, Looper looper) {
        this.mService = service;
        this.mInputMonitor = inputMonitor;
        this.mInputManager = inputManager;
        this.mActivityManager = activityManager;
        this.mHandler = new Handler(looper);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean startMovingTask(IWindow window, float startX, float startY) {
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                WindowState win = this.mService.windowForClientLocked((Session) null, window, false);
                if (!startPositioningLocked(win, false, false, startX, startY)) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                try {
                    this.mActivityManager.setFocusedTask(win.getTask().mTaskId);
                    return true;
                } catch (RemoteException e) {
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
                TaskPositioningController.lambda$handleTapOutsideTask$0(TaskPositioningController.this, displayContent, x, y);
            }
        });
    }

    public static /* synthetic */ void lambda$handleTapOutsideTask$0(TaskPositioningController taskPositioningController, DisplayContent displayContent, int x, int y) {
        int taskId;
        synchronized (taskPositioningController.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                Task task = displayContent.findTaskForResizePoint(x, y);
                if (task != null) {
                    if (!taskPositioningController.startPositioningLocked(task.getTopVisibleAppMainWindow(), true, task.preserveOrientationOnResize(), x, y)) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    taskId = task.mTaskId;
                } else {
                    taskId = displayContent.taskIdFromPoint(x, y);
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
                        taskPositioningController.mActivityManager.setFocusedTask(taskId);
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
        boolean z;
        boolean z2;
        float f;
        float f2;
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            StringBuilder sb = new StringBuilder();
            sb.append("startPositioningLocked: win=");
            sb.append(win);
            sb.append(", resize=");
            z = resize;
            sb.append(z);
            sb.append(", preserveOrientation=");
            z2 = preserveOrientation;
            sb.append(z2);
            sb.append(", {");
            f = startX;
            sb.append(f);
            sb.append(", ");
            f2 = startY;
            sb.append(f2);
            sb.append("}");
            Slog.d("WindowManager", sb.toString());
        } else {
            z = resize;
            z2 = preserveOrientation;
            f = startX;
            f2 = startY;
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
            displayContent.getDisplay();
            this.mTaskPositioner = TaskPositioner.create(this.mService);
            this.mTaskPositioner.register(displayContent);
            this.mInputMonitor.updateInputWindowsLw(true);
            WindowState transferFocusFromWin = win;
            if (this.mService.mCurrentFocus != null && this.mService.mCurrentFocus != win && this.mService.mCurrentFocus.mAppToken == win.mAppToken) {
                transferFocusFromWin = this.mService.mCurrentFocus;
            }
            if (!this.mInputManager.transferTouchFocus(transferFocusFromWin.mInputChannel, this.mTaskPositioner.mServerChannel)) {
                Slog.e("WindowManager", "startPositioningLocked: Unable to transfer touch focus");
                this.mTaskPositioner.unregister();
                this.mTaskPositioner = null;
                this.mInputMonitor.updateInputWindowsLw(true);
                return false;
            }
            this.mTaskPositioner.startDrag(win, z, z2, f, f2);
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishTaskPositioning() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskPositioningController$z3n1stJjOdhDbXXrvPlvlqmON6k
            @Override // java.lang.Runnable
            public final void run() {
                TaskPositioningController.lambda$finishTaskPositioning$1(TaskPositioningController.this);
            }
        });
    }

    public static /* synthetic */ void lambda$finishTaskPositioning$1(TaskPositioningController taskPositioningController) {
        if (WindowManagerDebugConfig.DEBUG_TASK_POSITIONING) {
            Slog.d("WindowManager", "finishPositioning");
        }
        synchronized (taskPositioningController.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (taskPositioningController.mTaskPositioner != null) {
                    taskPositioningController.mTaskPositioner.unregister();
                    taskPositioningController.mTaskPositioner = null;
                    taskPositioningController.mInputMonitor.updateInputWindowsLw(true);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }
}
