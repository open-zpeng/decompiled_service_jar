package com.android.server.am;

import android.app.ActivityManager;
import android.app.ITaskStackListener;
import android.content.ComponentName;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import java.util.ArrayList;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TaskChangeNotificationController {
    private static final int LOG_STACK_STATE_MSG = 1;
    private static final int NOTIFY_ACTIVITY_DISMISSING_DOCKED_STACK_MSG = 7;
    private static final int NOTIFY_ACTIVITY_LAUNCH_ON_SECONDARY_DISPLAY_FAILED_MSG = 18;
    private static final int NOTIFY_ACTIVITY_PINNED_LISTENERS_MSG = 3;
    private static final int NOTIFY_ACTIVITY_REQUESTED_ORIENTATION_CHANGED_LISTENERS = 12;
    private static final int NOTIFY_ACTIVITY_UNPINNED_LISTENERS_MSG = 17;
    private static final int NOTIFY_FORCED_RESIZABLE_MSG = 6;
    private static final int NOTIFY_PINNED_ACTIVITY_RESTART_ATTEMPT_LISTENERS_MSG = 4;
    private static final int NOTIFY_PINNED_STACK_ANIMATION_ENDED_LISTENERS_MSG = 5;
    private static final int NOTIFY_PINNED_STACK_ANIMATION_STARTED_LISTENERS_MSG = 16;
    private static final int NOTIFY_TASK_ADDED_LISTENERS_MSG = 8;
    private static final int NOTIFY_TASK_DESCRIPTION_CHANGED_LISTENERS_MSG = 11;
    private static final int NOTIFY_TASK_MOVED_TO_FRONT_LISTENERS_MSG = 10;
    private static final int NOTIFY_TASK_PROFILE_LOCKED_LISTENERS_MSG = 14;
    private static final int NOTIFY_TASK_REMOVAL_STARTED_LISTENERS = 13;
    private static final int NOTIFY_TASK_REMOVED_LISTENERS_MSG = 9;
    private static final int NOTIFY_TASK_SNAPSHOT_CHANGED_LISTENERS_MSG = 15;
    private static final int NOTIFY_TASK_STACK_CHANGE_LISTENERS_DELAY = 100;
    private static final int NOTIFY_TASK_STACK_CHANGE_LISTENERS_MSG = 2;
    private final Handler mHandler;
    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private final RemoteCallbackList<ITaskStackListener> mRemoteTaskStackListeners = new RemoteCallbackList<>();
    private final ArrayList<ITaskStackListener> mLocalTaskStackListeners = new ArrayList<>();
    private final TaskStackConsumer mNotifyTaskStackChanged = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$kftD881t3KfWCASQEbeTkieVI2M
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskStackChanged();
        }
    };
    private final TaskStackConsumer mNotifyTaskCreated = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$YDk9fnP8p2R_OweiU9rSGaheQeE
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskCreated(message.arg1, (ComponentName) message.obj);
        }
    };
    private final TaskStackConsumer mNotifyTaskRemoved = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$K9kaSj6_p5pzfyRh9i93xiC9T3s
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskRemoved(message.arg1);
        }
    };
    private final TaskStackConsumer mNotifyTaskMovedToFront = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$IPqcWaWHIL4UnZEYJhAve5H7KmE
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskMovedToFront(message.arg1);
        }
    };
    private final TaskStackConsumer mNotifyTaskDescriptionChanged = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$bteC39aBoUFmJeWf3dk2BX1xZ6k
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskDescriptionChanged(message.arg1, (ActivityManager.TaskDescription) message.obj);
        }
    };
    private final TaskStackConsumer mNotifyActivityRequestedOrientationChanged = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$grn5FwM5ofT98exjpSvrJhz-e7s
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onActivityRequestedOrientationChanged(message.arg1, message.arg2);
        }
    };
    private final TaskStackConsumer mNotifyTaskRemovalStarted = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$O2UuB84QeMcZfsRHiuiFSTwwWHY
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskRemovalStarted(message.arg1);
        }
    };
    private final TaskStackConsumer mNotifyActivityPinned = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$a1rNhcYLIsgLeCng0_osaimgbqE
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onActivityPinned((String) message.obj, message.sendingUid, message.arg1, message.arg2);
        }
    };
    private final TaskStackConsumer mNotifyActivityUnpinned = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$3Qs2duXCIzQ1W3uon7k5iYUmOy8
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onActivityUnpinned();
        }
    };
    private final TaskStackConsumer mNotifyPinnedActivityRestartAttempt = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$YVmGNqlD5lzQCN49aly8kWWz1po
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onPinnedActivityRestartAttempt(m.arg1 != 0);
        }
    };
    private final TaskStackConsumer mNotifyPinnedStackAnimationStarted = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$iVGVcx2Ee37igl6ebl_htq_WO9o
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onPinnedStackAnimationStarted();
        }
    };
    private final TaskStackConsumer mNotifyPinnedStackAnimationEnded = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$5xMsPmGMl_n12-F1m2p9OBuXGrA
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onPinnedStackAnimationEnded();
        }
    };
    private final TaskStackConsumer mNotifyActivityForcedResizable = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$sw023kIrIGSeLwYwKC0ioKX3zEA
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onActivityForcedResizable((String) message.obj, message.arg1, message.arg2);
        }
    };
    private final TaskStackConsumer mNotifyActivityDismissingDockedStack = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$d9Depygk2x7Vm_pl1RSk9_SSjvA
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onActivityDismissingDockedStack();
        }
    };
    private final TaskStackConsumer mNotifyActivityLaunchOnSecondaryDisplayFailed = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$Ln9-GPCsfrWRlWBInk_Po_Uv-_U
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onActivityLaunchOnSecondaryDisplayFailed();
        }
    };
    private final TaskStackConsumer mNotifyTaskProfileLocked = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$FNdlAMBaRkRCa4U_pc-uamD9VHw
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskProfileLocked(message.arg1, message.arg2);
        }
    };
    private final TaskStackConsumer mNotifyTaskSnapshotChanged = new TaskStackConsumer() { // from class: com.android.server.am.-$$Lambda$TaskChangeNotificationController$1RAH1a7gRlnrDczBty2_cTiNlBI
        @Override // com.android.server.am.TaskChangeNotificationController.TaskStackConsumer
        public final void accept(ITaskStackListener iTaskStackListener, Message message) {
            iTaskStackListener.onTaskSnapshotChanged(message.arg1, (ActivityManager.TaskSnapshot) message.obj);
        }
    };

    @FunctionalInterface
    /* loaded from: classes.dex */
    public interface TaskStackConsumer {
        void accept(ITaskStackListener iTaskStackListener, Message message) throws RemoteException;
    }

    /* loaded from: classes.dex */
    private class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    synchronized (TaskChangeNotificationController.this.mService) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            TaskChangeNotificationController.this.mStackSupervisor.logStackState();
                        } catch (Throwable th) {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                case 2:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskStackChanged, msg);
                    return;
                case 3:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyActivityPinned, msg);
                    return;
                case 4:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyPinnedActivityRestartAttempt, msg);
                    return;
                case 5:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyPinnedStackAnimationEnded, msg);
                    return;
                case 6:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyActivityForcedResizable, msg);
                    return;
                case 7:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyActivityDismissingDockedStack, msg);
                    return;
                case 8:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskCreated, msg);
                    return;
                case 9:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskRemoved, msg);
                    return;
                case 10:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskMovedToFront, msg);
                    return;
                case 11:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskDescriptionChanged, msg);
                    return;
                case 12:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyActivityRequestedOrientationChanged, msg);
                    return;
                case 13:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskRemovalStarted, msg);
                    return;
                case 14:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskProfileLocked, msg);
                    return;
                case 15:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyTaskSnapshotChanged, msg);
                    return;
                case 16:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyPinnedStackAnimationStarted, msg);
                    return;
                case 17:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyActivityUnpinned, msg);
                    return;
                case 18:
                    TaskChangeNotificationController.this.forAllRemoteListeners(TaskChangeNotificationController.this.mNotifyActivityLaunchOnSecondaryDisplayFailed, msg);
                    return;
                default:
                    return;
            }
        }
    }

    public TaskChangeNotificationController(ActivityManagerService service, ActivityStackSupervisor stackSupervisor, Handler handler) {
        this.mService = service;
        this.mStackSupervisor = stackSupervisor;
        this.mHandler = new MainHandler(handler.getLooper());
    }

    public void registerTaskStackListener(ITaskStackListener listener) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (listener != null) {
                    if (Binder.getCallingPid() == Process.myPid()) {
                        if (!this.mLocalTaskStackListeners.contains(listener)) {
                            this.mLocalTaskStackListeners.add(listener);
                        }
                    } else {
                        this.mRemoteTaskStackListeners.register(listener);
                    }
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    public void unregisterTaskStackListener(ITaskStackListener listener) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (listener != null) {
                    if (Binder.getCallingPid() == Process.myPid()) {
                        this.mLocalTaskStackListeners.remove(listener);
                    } else {
                        this.mRemoteTaskStackListeners.unregister(listener);
                    }
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void forAllRemoteListeners(TaskStackConsumer callback, Message message) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                for (int i = this.mRemoteTaskStackListeners.beginBroadcast() - 1; i >= 0; i--) {
                    try {
                        callback.accept(this.mRemoteTaskStackListeners.getBroadcastItem(i), message);
                    } catch (RemoteException e) {
                    }
                }
                this.mRemoteTaskStackListeners.finishBroadcast();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    private void forAllLocalListeners(TaskStackConsumer callback, Message message) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                for (int i = this.mLocalTaskStackListeners.size() - 1; i >= 0; i--) {
                    try {
                        callback.accept(this.mLocalTaskStackListeners.get(i), message);
                    } catch (RemoteException e) {
                    }
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskStackChanged() {
        this.mHandler.sendEmptyMessage(1);
        this.mHandler.removeMessages(2);
        Message msg = this.mHandler.obtainMessage(2);
        forAllLocalListeners(this.mNotifyTaskStackChanged, msg);
        this.mHandler.sendMessageDelayed(msg, 100L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityPinned(ActivityRecord r) {
        this.mHandler.removeMessages(3);
        Message msg = this.mHandler.obtainMessage(3, r.getTask().taskId, r.getStackId(), r.packageName);
        msg.sendingUid = r.userId;
        forAllLocalListeners(this.mNotifyActivityPinned, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityUnpinned() {
        this.mHandler.removeMessages(17);
        Message msg = this.mHandler.obtainMessage(17);
        forAllLocalListeners(this.mNotifyActivityUnpinned, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyPinnedActivityRestartAttempt(boolean clearedTask) {
        this.mHandler.removeMessages(4);
        Message msg = this.mHandler.obtainMessage(4, clearedTask ? 1 : 0, 0);
        forAllLocalListeners(this.mNotifyPinnedActivityRestartAttempt, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyPinnedStackAnimationStarted() {
        this.mHandler.removeMessages(16);
        Message msg = this.mHandler.obtainMessage(16);
        forAllLocalListeners(this.mNotifyPinnedStackAnimationStarted, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyPinnedStackAnimationEnded() {
        this.mHandler.removeMessages(5);
        Message msg = this.mHandler.obtainMessage(5);
        forAllLocalListeners(this.mNotifyPinnedStackAnimationEnded, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityDismissingDockedStack() {
        this.mHandler.removeMessages(7);
        Message msg = this.mHandler.obtainMessage(7);
        forAllLocalListeners(this.mNotifyActivityDismissingDockedStack, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityForcedResizable(int taskId, int reason, String packageName) {
        this.mHandler.removeMessages(6);
        Message msg = this.mHandler.obtainMessage(6, taskId, reason, packageName);
        forAllLocalListeners(this.mNotifyActivityForcedResizable, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityLaunchOnSecondaryDisplayFailed() {
        this.mHandler.removeMessages(18);
        Message msg = this.mHandler.obtainMessage(18);
        forAllLocalListeners(this.mNotifyActivityLaunchOnSecondaryDisplayFailed, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskCreated(int taskId, ComponentName componentName) {
        Message msg = this.mHandler.obtainMessage(8, taskId, 0, componentName);
        forAllLocalListeners(this.mNotifyTaskCreated, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskRemoved(int taskId) {
        Message msg = this.mHandler.obtainMessage(9, taskId, 0);
        forAllLocalListeners(this.mNotifyTaskRemoved, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskMovedToFront(int taskId) {
        Message msg = this.mHandler.obtainMessage(10, taskId, 0);
        forAllLocalListeners(this.mNotifyTaskMovedToFront, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskDescriptionChanged(int taskId, ActivityManager.TaskDescription taskDescription) {
        Message msg = this.mHandler.obtainMessage(11, taskId, 0, taskDescription);
        forAllLocalListeners(this.mNotifyTaskDescriptionChanged, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyActivityRequestedOrientationChanged(int taskId, int orientation) {
        Message msg = this.mHandler.obtainMessage(12, taskId, orientation);
        forAllLocalListeners(this.mNotifyActivityRequestedOrientationChanged, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskRemovalStarted(int taskId) {
        Message msg = this.mHandler.obtainMessage(13, taskId, 0);
        forAllLocalListeners(this.mNotifyTaskRemovalStarted, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskProfileLocked(int taskId, int userId) {
        Message msg = this.mHandler.obtainMessage(14, taskId, userId);
        forAllLocalListeners(this.mNotifyTaskProfileLocked, msg);
        msg.sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyTaskSnapshotChanged(int taskId, ActivityManager.TaskSnapshot snapshot) {
        Message msg = this.mHandler.obtainMessage(15, taskId, 0, snapshot);
        forAllLocalListeners(this.mNotifyTaskSnapshotChanged, msg);
        msg.sendToTarget();
    }
}
