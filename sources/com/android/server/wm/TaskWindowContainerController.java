package com.android.server.wm;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.EventLogTags;
import java.lang.ref.WeakReference;
/* loaded from: classes.dex */
public class TaskWindowContainerController extends WindowContainerController<Task, TaskWindowContainerListener> {
    private final H mHandler;
    private final int mTaskId;

    @Override // com.android.server.wm.WindowContainerController, com.android.server.wm.ConfigurationContainerListener
    public /* bridge */ /* synthetic */ void onOverrideConfigurationChanged(Configuration configuration) {
        super.onOverrideConfigurationChanged(configuration);
    }

    public TaskWindowContainerController(int taskId, TaskWindowContainerListener listener, StackWindowController stackController, int userId, Rect bounds, int resizeMode, boolean supportsPictureInPicture, boolean toTop, boolean showForAllUsers, ActivityManager.TaskDescription taskDescription) {
        this(taskId, listener, stackController, userId, bounds, resizeMode, supportsPictureInPicture, toTop, showForAllUsers, taskDescription, WindowManagerService.getInstance());
    }

    public TaskWindowContainerController(int taskId, TaskWindowContainerListener listener, StackWindowController stackController, int userId, Rect bounds, int resizeMode, boolean supportsPictureInPicture, boolean toTop, boolean showForAllUsers, ActivityManager.TaskDescription taskDescription, WindowManagerService service) {
        super(listener, service);
        this.mTaskId = taskId;
        this.mHandler = new H(new WeakReference(this), service.mH.getLooper());
        synchronized (this.mWindowMap) {
            try {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    if (WindowManagerDebugConfig.DEBUG_STACK) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("TaskWindowContainerController: taskId=");
                        sb.append(taskId);
                        sb.append(" stack=");
                        sb.append(stackController);
                        sb.append(" bounds=");
                        try {
                            sb.append(bounds);
                            Slog.i("WindowManager", sb.toString());
                        } catch (Throwable th) {
                            th = th;
                            WindowManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    TaskStack stack = (TaskStack) stackController.mContainer;
                    if (stack == null) {
                        throw new IllegalArgumentException("TaskWindowContainerController: invalid stack=" + stackController);
                    }
                    EventLog.writeEvent((int) EventLogTags.WM_TASK_CREATED, Integer.valueOf(taskId), Integer.valueOf(stack.mStackId));
                    Task task = createTask(taskId, stack, userId, resizeMode, supportsPictureInPicture, taskDescription);
                    int position = toTop ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                    stack.addTask(task, position, showForAllUsers, toTop);
                    WindowManagerService.resetPriorityAfterLockedSection();
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    @VisibleForTesting
    Task createTask(int taskId, TaskStack stack, int userId, int resizeMode, boolean supportsPictureInPicture, ActivityManager.TaskDescription taskDescription) {
        return new Task(taskId, stack, userId, this.mService, resizeMode, supportsPictureInPicture, taskDescription, this);
    }

    @Override // com.android.server.wm.WindowContainerController
    public void removeContainer() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_STACK) {
                        Slog.i("WindowManager", "removeTask: could not find taskId=" + this.mTaskId);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((Task) this.mContainer).removeIfPossible();
                super.removeContainer();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void positionChildAtTop(AppWindowContainerController childController) {
        positionChildAt(childController, Integer.MAX_VALUE);
    }

    public void positionChildAt(AppWindowContainerController childController, int position) {
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                AppWindowToken aToken = (AppWindowToken) childController.mContainer;
                if (aToken == null) {
                    Slog.w("WindowManager", "Attempted to position of non-existing app : " + childController);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                Task task = (Task) this.mContainer;
                if (task == null) {
                    throw new IllegalArgumentException("positionChildAt: invalid task=" + this);
                }
                task.positionChildAt(position, aToken, false);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void reparent(StackWindowController stackController, int position, boolean moveParents) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_STACK) {
                    Slog.i("WindowManager", "reparent: moving taskId=" + this.mTaskId + " to stack=" + stackController + " at " + position);
                }
                if (this.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_STACK) {
                        Slog.i("WindowManager", "reparent: could not find taskId=" + this.mTaskId);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                TaskStack stack = (TaskStack) stackController.mContainer;
                if (stack == null) {
                    throw new IllegalArgumentException("reparent: could not find stack=" + stackController);
                }
                ((Task) this.mContainer).reparent(stack, position, moveParents);
                ((Task) this.mContainer).getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setResizeable(int resizeMode) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    ((Task) this.mContainer).setResizeable(resizeMode);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void resize(boolean relayout, boolean forced) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    throw new IllegalArgumentException("resizeTask: taskId " + this.mTaskId + " not found.");
                } else if (((Task) this.mContainer).setBounds(((Task) this.mContainer).getOverrideBounds(), forced) != 0 && relayout) {
                    ((Task) this.mContainer).getDisplayContent().layoutAndAssignWindowLayersIfNeeded();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void getBounds(Rect bounds) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer != 0) {
                    ((Task) this.mContainer).getBounds(bounds);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                bounds.setEmpty();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setTaskDockedResizing(boolean resizing) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "setTaskDockedResizing: taskId " + this.mTaskId + " not found.");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((Task) this.mContainer).setDragResizing(resizing, 1);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void cancelWindowTransition() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "cancelWindowTransition: taskId " + this.mTaskId + " not found.");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((Task) this.mContainer).cancelTaskWindowTransition();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    Slog.w("WindowManager", "setTaskDescription: taskId " + this.mTaskId + " not found.");
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((Task) this.mContainer).setTaskDescription(taskDescription);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportSnapshotChanged(ActivityManager.TaskSnapshot snapshot) {
        this.mHandler.obtainMessage(0, snapshot).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestResize(Rect bounds, int resizeMode) {
        this.mHandler.obtainMessage(1, resizeMode, 0, bounds).sendToTarget();
    }

    public String toString() {
        return "{TaskWindowContainerController taskId=" + this.mTaskId + "}";
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class H extends Handler {
        static final int REPORT_SNAPSHOT_CHANGED = 0;
        static final int REQUEST_RESIZE = 1;
        private final WeakReference<TaskWindowContainerController> mController;

        H(WeakReference<TaskWindowContainerController> controller, Looper looper) {
            super(looper);
            this.mController = controller;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            TaskWindowContainerController controller = this.mController.get();
            TaskWindowContainerListener listener = controller != null ? (TaskWindowContainerListener) controller.mListener : null;
            if (listener == null) {
                return;
            }
            switch (msg.what) {
                case 0:
                    listener.onSnapshotChanged((ActivityManager.TaskSnapshot) msg.obj);
                    return;
                case 1:
                    listener.requestResize((Rect) msg.obj, msg.arg1);
                    return;
                default:
                    return;
            }
        }
    }
}
