package com.android.server.wm;

import android.app.ActivityManager;
import android.app.IAppTask;
import android.app.IApplicationThread;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;
import com.android.server.pm.DumpState;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class AppTaskImpl extends IAppTask.Stub {
    private static final String TAG = "AppTaskImpl";
    private int mCallingUid;
    private ActivityTaskManagerService mService;
    private int mTaskId;

    public AppTaskImpl(ActivityTaskManagerService service, int taskId, int callingUid) {
        this.mService = service;
        this.mTaskId = taskId;
        this.mCallingUid = callingUid;
    }

    private void checkCaller() {
        if (this.mCallingUid != Binder.getCallingUid()) {
            throw new SecurityException("Caller " + this.mCallingUid + " does not match caller of getAppTasks(): " + Binder.getCallingUid());
        }
    }

    public void finishAndRemoveTask() {
        checkCaller();
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                if (!this.mService.mStackSupervisor.removeTaskByIdLocked(this.mTaskId, false, true, "finish-and-remove-task")) {
                    throw new IllegalArgumentException("Unable to find task ID " + this.mTaskId);
                }
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public ActivityManager.RecentTaskInfo getTaskInfo() {
        ActivityManager.RecentTaskInfo createRecentTaskInfo;
        checkCaller();
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                TaskRecord tr = this.mService.mRootActivityContainer.anyTaskForId(this.mTaskId, 1);
                if (tr == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + this.mTaskId);
                }
                createRecentTaskInfo = this.mService.getRecentTasks().createRecentTaskInfo(tr);
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return createRecentTaskInfo;
    }

    public void moveToFront(IApplicationThread appThread, String callingPackage) {
        checkCaller();
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        if (!this.mService.isSameApp(callingUid, callingPackage)) {
            String msg = "Permission Denial: moveToFront() from pid=" + Binder.getCallingPid() + " as package " + callingPackage;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        long origId = Binder.clearCallingIdentity();
        try {
            try {
                synchronized (this.mService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        if (!this.mService.checkAppSwitchAllowedLocked(callingPid, callingUid, -1, -1, "Move to front")) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                        WindowProcessController callerApp = appThread != null ? this.mService.getProcessController(appThread) : null;
                        ActivityStarter starter = this.mService.getActivityStartController().obtainStarter(null, "moveToFront");
                        if (starter.shouldAbortBackgroundActivityStart(callingUid, callingPid, callingPackage, -1, -1, callerApp, null, false, null) && !this.mService.isBackgroundActivityStartsEnabled()) {
                            WindowManagerService.resetPriorityAfterLockedSection();
                            return;
                        }
                        this.mService.mStackSupervisor.startActivityFromRecents(callingPid, callingUid, this.mTaskId, null);
                        WindowManagerService.resetPriorityAfterLockedSection();
                    } catch (Throwable th) {
                        th = th;
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
            } catch (Throwable th2) {
                th = th2;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public int startActivity(IBinder whoThread, String callingPackage, Intent intent, String resolvedType, Bundle bOptions) {
        TaskRecord tr;
        IApplicationThread appThread;
        checkCaller();
        int callingUser = UserHandle.getCallingUserId();
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                tr = this.mService.mRootActivityContainer.anyTaskForId(this.mTaskId, 1);
                if (tr == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + this.mTaskId);
                }
                appThread = IApplicationThread.Stub.asInterface(whoThread);
                if (appThread == null) {
                    throw new IllegalArgumentException("Bad app thread " + appThread);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return this.mService.getActivityStartController().obtainStarter(intent, TAG).setCaller(appThread).setCallingPackage(callingPackage).setResolvedType(resolvedType).setActivityOptions(bOptions).setMayWait(callingUser).setInTask(tr).execute();
    }

    public void setExcludeFromRecents(boolean exclude) {
        checkCaller();
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long origId = Binder.clearCallingIdentity();
                TaskRecord tr = this.mService.mRootActivityContainer.anyTaskForId(this.mTaskId, 1);
                if (tr == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + this.mTaskId);
                }
                Intent intent = tr.getBaseIntent();
                if (exclude) {
                    intent.addFlags(DumpState.DUMP_VOLUMES);
                } else {
                    intent.setFlags(intent.getFlags() & (-8388609));
                }
                Binder.restoreCallingIdentity(origId);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }
}
