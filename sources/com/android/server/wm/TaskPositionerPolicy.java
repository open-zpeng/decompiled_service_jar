package com.android.server.wm;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import com.android.server.wm.TaskPositionerPolicy;
import com.xiaopeng.server.wm.WindowFrameController;
import com.xiaopeng.util.xpLogger;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes2.dex */
public class TaskPositionerPolicy {
    private static final boolean DEBUG = true;
    public static final int EVENT_STARTED = 1;
    public static final int EVENT_STOPPED = 2;
    private static final String TAG = "TaskPositionerPolicy";
    public static final int TYPE_POSITION_ANIM = 1;
    public static final int TYPE_POSITION_MOVE = 0;
    private static volatile boolean sTaskPositioning = false;
    private final IActivityTaskManager mActivityManager;
    private PositionExecutor mAnimExecutor;
    private PositionExecutor mMoveExecutor;
    private String mPackageName;
    private volatile int mPositionType = -1;
    private int mSharedId;
    private final WindowManagerService mWindowManager;
    private WindowState mWindowState;

    public TaskPositionerPolicy(WindowManagerService wm, IActivityTaskManager am) {
        this.mWindowManager = wm;
        this.mActivityManager = am;
        this.mMoveExecutor = new MoveExecutor(wm, am);
        this.mAnimExecutor = new AnimExecutor(wm, am);
    }

    public void execute(int type, WindowState win) {
        if (sTaskPositioning) {
            return;
        }
        this.mWindowState = win;
        this.mSharedId = (win == null || win.mAttrs == null) ? -1 : win.mAttrs.sharedId;
        this.mPackageName = (win == null || win.mAttrs == null) ? "" : win.mAttrs.packageName;
        this.mPositionType = type;
        onStart();
    }

    private void onStart() {
        if (sTaskPositioning) {
            return;
        }
        boolean checkNotNull = checkNotNull(this.mWindowState, this.mWindowManager, this.mActivityManager);
        if (checkNotNull) {
            onEventChanged(this.mWindowState, this.mPackageName, 1, this.mSharedId);
            int type = this.mPositionType;
            if (type == 0) {
                this.mMoveExecutor.set(this.mWindowState);
                this.mMoveExecutor.onStart();
            } else if (type == 1) {
                this.mAnimExecutor.set(this.mWindowState);
                this.mAnimExecutor.onStart();
            }
            sTaskPositioning = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onStop() {
        xpLogger.i(TAG, "onStop");
        if (sTaskPositioning) {
            onEventChanged(this.mWindowState, this.mPackageName, 2, this.mSharedId);
            int type = this.mPositionType;
            if (type == 0) {
                this.mMoveExecutor.onStop();
            } else if (type == 1) {
                this.mAnimExecutor.onStop();
            }
            this.mPositionType = -1;
            sTaskPositioning = false;
        }
    }

    private void onEventChanged(WindowState win, String packageName, int event, int sharedId) {
        int to = SharedDisplayContainer.generateNextSharedId(sharedId);
        if (event == 1) {
            WindowFrameController.setResizePackage(packageName);
            this.mWindowManager.onPositionEventChanged(packageName, event, sharedId, to, win);
        } else if (event == 2) {
            WindowFrameController.setResizePackage("");
            this.mWindowManager.onPositionEventChanged(packageName, event, sharedId, to, win);
        }
    }

    public static List<ActivityManager.RunningTaskInfo> getTasks(IActivityTaskManager am, String packageName) {
        if (am != null) {
            try {
                if (!TextUtils.isEmpty(packageName)) {
                    List<ActivityManager.RunningTaskInfo> lists = new ArrayList<>();
                    List<ActivityManager.RunningTaskInfo> tasks = am.getTasks(200);
                    if (tasks != null && !tasks.isEmpty()) {
                        for (ActivityManager.RunningTaskInfo info : tasks) {
                            if (info != null && info.realActivity != null && packageName.equals(info.realActivity.getPackageName())) {
                                lists.add(info);
                            }
                        }
                    }
                    return lists;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static boolean sharedTask(WindowState win) {
        if (win == null || win.getAppToken() == null || win.mInputChannel == null || win.mAttrs == null) {
            return false;
        }
        DisplayContent displayContent = win.getDisplayContent();
        return displayContent != null && win.mAttrs.type == 10;
    }

    public static boolean checkNotNull(WindowState win, WindowManagerService wm, IActivityTaskManager am) {
        if (win == null || wm == null || am == null || win.mAttrs == null) {
            return false;
        }
        DisplayContent dc = win.getDisplayContent();
        if (dc == null) {
            return false;
        }
        Task task = win.getTask();
        if (task == null) {
            return false;
        }
        return true;
    }

    public static Rect getTargetBounds(WindowManager.LayoutParams lp, int sharedId) {
        if (lp == null) {
            return null;
        }
        WindowManager.LayoutParams _lp = new WindowManager.LayoutParams();
        _lp.copyFrom(lp);
        _lp.sharedId = sharedId;
        return SharedDisplayContainer.getBounds(_lp);
    }

    public static void resizeTask(IActivityTaskManager am, int taskId, List<ActivityManager.RunningTaskInfo> tasks, Rect bounds) {
        if (am == null || tasks == null || bounds == null) {
            return;
        }
        try {
            StringBuffer buffer = new StringBuffer("resizeTask");
            buffer.append(" taskId=" + taskId);
            am.resizeTask(taskId, bounds, 3);
            if (!tasks.isEmpty()) {
                for (ActivityManager.RunningTaskInfo info : tasks) {
                    if (info != null) {
                        buffer.append(" item");
                        buffer.append(" tid=" + info.taskId);
                        buffer.append(" topActivity=" + info.topActivity);
                        buffer.append(" isRunning=" + info.isRunning);
                        if (taskId != info.taskId && info.isRunning) {
                            am.resizeTask(info.taskId, bounds, 3);
                        }
                    }
                }
            }
            xpLogger.i(TAG, buffer.toString());
        } catch (Exception e) {
            xpLogger.i(TAG, "resizeTask e=" + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchPointerEvent(MotionEvent event) {
        MoveExecutor executor = (MoveExecutor) this.mMoveExecutor;
        executor.onTouchEvent(event);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class MoveExecutor extends PositionExecutor {
        private static final long TOUCH_INTERVAL_MAX = 1000;
        private TaskPointerEventListener listener;
        private volatile boolean registered;
        private volatile long touchDown;
        private volatile long touchUp;
        private volatile float touchX;
        private volatile float touchY;
        private volatile boolean touching;

        public MoveExecutor(WindowManagerService wm, IActivityTaskManager am) {
            super(wm, am);
            this.touching = true;
            this.registered = false;
            this.listener = new TaskPointerEventListener();
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public void onStart() {
            super.onStart();
            boolean checkNotNull = TaskPositionerPolicy.checkNotNull(this.window, this.wm, this.am);
            if (checkNotNull && !this.registered && this.display != null) {
                this.display.registerPointerEventListener(this.listener);
                this.registered = true;
            }
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public void onStop() {
            super.onStop();
            boolean checkNotNull = TaskPositionerPolicy.checkNotNull(this.window, this.wm, this.am);
            if (checkNotNull) {
                if (this.registered && this.display != null) {
                    this.display.unregisterPointerEventListener(this.listener);
                    this.registered = false;
                }
                this.touchX = 0.0f;
                this.touchY = 0.0f;
            }
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public void execute() {
            super.execute();
            boolean checkNotNull = TaskPositionerPolicy.checkNotNull(this.window, this.wm, this.am);
            if (checkNotNull) {
                performResize(5L);
            }
        }

        public void onTouchEvent(MotionEvent event) {
            if (event == null) {
                return;
            }
            int action = event.getAction();
            boolean z = true;
            if (action != 0) {
                if (action != 1) {
                    if (action != 2) {
                        if (action != 3) {
                            return;
                        }
                    }
                }
                this.touchUp = System.currentTimeMillis();
                this.touchDown = 0L;
                if (this.touching) {
                    performFinish(5L);
                    return;
                }
                return;
            }
            if (this.touchDown <= 0) {
                this.touchDown = System.currentTimeMillis();
            }
            if (this.touchDown <= 0 || this.touchDown - this.touchUp <= 1000) {
                z = false;
            }
            this.touching = z;
            if (this.touching) {
                float x = event.getRawX();
                float y = event.getRawY();
                this.deltaX = this.touchX != 0.0f ? Math.round(x - this.touchX) : 0;
                this.deltaY = this.touchY != 0.0f ? Math.round(y - this.touchY) : 0;
                this.touchX = x;
                this.touchY = y;
                execute();
            }
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public Runnable createResizeRunnable() {
            return new Runnable() { // from class: com.android.server.wm.TaskPositionerPolicy.MoveExecutor.1
                @Override // java.lang.Runnable
                public void run() {
                    boolean checkNotNull = TaskPositionerPolicy.checkNotNull(MoveExecutor.this.window, MoveExecutor.this.wm, MoveExecutor.this.am);
                    if (checkNotNull) {
                        Task task = MoveExecutor.this.window.getTask();
                        task.setSharedResizing(true);
                        task.getBounds(MoveExecutor.this.bounds);
                        MoveExecutor.this.bounds.offset(MoveExecutor.this.deltaX, MoveExecutor.this.deltaY);
                        int taskId = task.mTaskId;
                        TaskPositionerPolicy.resizeTask(MoveExecutor.this.am, taskId, MoveExecutor.this.tasks, MoveExecutor.this.bounds);
                    }
                }
            };
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public Runnable createFinishRunnable() {
            return new Runnable() { // from class: com.android.server.wm.TaskPositionerPolicy.MoveExecutor.2
                @Override // java.lang.Runnable
                public void run() {
                    boolean checkNotNull = TaskPositionerPolicy.checkNotNull(MoveExecutor.this.window, MoveExecutor.this.wm, MoveExecutor.this.am);
                    if (checkNotNull) {
                        MoveExecutor.this.handler.removeCallbacks(MoveExecutor.this.resizeRunnable);
                        Task task = MoveExecutor.this.window.getTask();
                        int sharedId = SharedDisplayContainer.generateNextSharedId(MoveExecutor.this.window.mAttrs.sharedId);
                        Rect rect = TaskPositionerPolicy.getTargetBounds(MoveExecutor.this.window.mAttrs, sharedId);
                        int taskId = task.mTaskId;
                        MoveExecutor.this.window.mAttrs.sharedId = sharedId;
                        TaskPositionerPolicy.resizeTask(MoveExecutor.this.am, taskId, MoveExecutor.this.tasks, rect);
                        task.setSharedResizing(false);
                        TaskPositionerPolicy.this.onStop();
                    }
                }
            };
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class AnimExecutor extends PositionExecutor {
        public AnimExecutor(WindowManagerService wm, IActivityTaskManager am) {
            super(wm, am);
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public void onStart() {
            super.onStart();
            xpLogger.i(TaskPositionerPolicy.TAG, "onStart");
            boolean checkNotNull = TaskPositionerPolicy.checkNotNull(this.window, this.wm, this.am);
            if (checkNotNull) {
                try {
                    this.am.setFocusedTask(this.window.getTask().mTaskId);
                } catch (RemoteException e) {
                }
                execute();
            }
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public void execute() {
            super.execute();
            boolean checkNotNull = TaskPositionerPolicy.checkNotNull(this.window, this.wm, this.am);
            if (checkNotNull) {
                performResize(5L);
                performFinish(SharedDisplayAnimator.getAnimatorDuration() + 50);
            }
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public void performFinish(long delay) {
            if (this.finishRunnable == null) {
                return;
            }
            boolean checkNotNull = TaskPositionerPolicy.checkNotNull(this.window, this.wm, this.am);
            if (!checkNotNull) {
                this.handler.removeCallbacks(this.finishRunnable);
                this.handler.post(this.finishRunnable);
                return;
            }
            boolean finish = true;
            boolean timeout = System.currentTimeMillis() - this.startMillis > 2000;
            if (!this.window.getTask().isSharedResizing() || this.window.mResizedAnimating) {
                finish = false;
            }
            if (timeout || finish) {
                this.handler.removeCallbacks(this.finishRunnable);
                this.handler.post(this.finishRunnable);
                this.window.mResizedAnimating = false;
                return;
            }
            this.handler.postDelayed(new Runnable() { // from class: com.android.server.wm.-$$Lambda$TaskPositionerPolicy$AnimExecutor$XLXk8hBFLwNh9nYiWfJ9VmBUWs8
                @Override // java.lang.Runnable
                public final void run() {
                    TaskPositionerPolicy.AnimExecutor.this.lambda$performFinish$0$TaskPositionerPolicy$AnimExecutor();
                }
            }, delay);
        }

        public /* synthetic */ void lambda$performFinish$0$TaskPositionerPolicy$AnimExecutor() {
            performFinish(50L);
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public Runnable createResizeRunnable() {
            return new Runnable() { // from class: com.android.server.wm.TaskPositionerPolicy.AnimExecutor.1
                @Override // java.lang.Runnable
                public void run() {
                    boolean checkNotNull = TaskPositionerPolicy.checkNotNull(AnimExecutor.this.window, AnimExecutor.this.wm, AnimExecutor.this.am);
                    if (checkNotNull) {
                        Task task = AnimExecutor.this.window.getTask();
                        task.setSharedResizing(true);
                        Rect fromBounds = TaskPositionerPolicy.getTargetBounds(AnimExecutor.this.window.mAttrs, AnimExecutor.this.window.mAttrs.sharedId);
                        int sharedId = SharedDisplayContainer.generateNextSharedId(AnimExecutor.this.window.mAttrs.sharedId);
                        Rect toBounds = TaskPositionerPolicy.getTargetBounds(AnimExecutor.this.window.mAttrs, sharedId);
                        int taskId = task.mTaskId;
                        AnimExecutor.this.window.mAttrs.sharedId = sharedId;
                        AnimExecutor.this.window.mToBounds = toBounds;
                        AnimExecutor.this.window.mFromBounds = fromBounds;
                        AnimExecutor.this.window.mUseResizedAnimator = true;
                        TaskPositionerPolicy.resizeTask(AnimExecutor.this.am, taskId, AnimExecutor.this.tasks, toBounds);
                    }
                }
            };
        }

        @Override // com.android.server.wm.TaskPositionerPolicy.PositionExecutor
        public Runnable createFinishRunnable() {
            return new Runnable() { // from class: com.android.server.wm.TaskPositionerPolicy.AnimExecutor.2
                @Override // java.lang.Runnable
                public void run() {
                    boolean checkNotNull = TaskPositionerPolicy.checkNotNull(AnimExecutor.this.window, AnimExecutor.this.wm, AnimExecutor.this.am);
                    if (checkNotNull) {
                        Task task = AnimExecutor.this.window.getTask();
                        task.setSharedResizing(false);
                    }
                    TaskPositionerPolicy.this.onStop();
                }
            };
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class PositionExecutor {
        protected final IActivityTaskManager am;
        protected volatile int deltaX;
        protected volatile int deltaY;
        protected DisplayContent display;
        protected String packageName;
        protected List<ActivityManager.RunningTaskInfo> tasks;
        protected WindowState window;
        protected final WindowManagerService wm;
        protected volatile long startMillis = 0;
        protected volatile long stopMillis = 0;
        protected volatile Rect bounds = new Rect();
        protected final Handler handler = new Handler(Looper.getMainLooper());
        protected final Runnable resizeRunnable = createResizeRunnable();
        protected final Runnable finishRunnable = createFinishRunnable();

        public PositionExecutor(WindowManagerService wm, IActivityTaskManager am) {
            this.wm = wm;
            this.am = am;
        }

        public void set(WindowState window) {
            this.window = window;
            this.display = window != null ? window.getDisplayContent() : null;
        }

        public void onStart() {
            boolean checkNotNull = TaskPositionerPolicy.checkNotNull(this.window, this.wm, this.am);
            if (checkNotNull) {
                this.startMillis = System.currentTimeMillis();
                this.packageName = this.window.mAttrs.packageName;
                this.tasks = TaskPositionerPolicy.getTasks(this.am, this.packageName);
            }
        }

        public void onStop() {
            this.deltaX = 0;
            this.deltaY = 0;
            this.stopMillis = System.currentTimeMillis();
        }

        public void execute() {
        }

        public void performResize(long delay) {
            Runnable runnable = this.resizeRunnable;
            if (runnable == null) {
                return;
            }
            this.handler.removeCallbacks(runnable);
            this.handler.postDelayed(this.resizeRunnable, delay);
        }

        public void performFinish(long delay) {
            Runnable runnable = this.finishRunnable;
            if (runnable == null) {
                return;
            }
            this.handler.removeCallbacks(runnable);
            this.handler.postDelayed(this.finishRunnable, delay);
        }

        public Runnable createFinishRunnable() {
            return null;
        }

        public Runnable createResizeRunnable() {
            return null;
        }
    }

    /* loaded from: classes2.dex */
    private class TaskPointerEventListener implements WindowManagerPolicyConstants.PointerEventListener {
        public TaskPointerEventListener() {
        }

        public void onPointerEvent(MotionEvent motionEvent) {
            TaskPositionerPolicy.this.dispatchPointerEvent(motionEvent);
        }
    }
}
