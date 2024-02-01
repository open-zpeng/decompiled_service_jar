package com.android.server.wm;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import com.android.internal.util.FastPrintWriter;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.wm.SurfaceAnimator;
import com.android.server.wm.utils.InsetUtils;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RemoteAnimationController implements IBinder.DeathRecipient {
    private static final String TAG;
    private static final long TIMEOUT_MS = 2000;
    private boolean mCanceled;
    private FinishedCallback mFinishedCallback;
    private final Handler mHandler;
    private boolean mLinkedToDeathOfRunner;
    private final RemoteAnimationAdapter mRemoteAnimationAdapter;
    private final WindowManagerService mService;
    private final ArrayList<RemoteAnimationAdapterWrapper> mPendingAnimations = new ArrayList<>();
    private final Rect mTmpRect = new Rect();
    private final Runnable mTimeoutRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$RemoteAnimationController$uQS8vaPKQ-E3x_9G8NCxPQmw1fw
        @Override // java.lang.Runnable
        public final void run() {
            RemoteAnimationController.this.cancelAnimation("timeoutRunnable");
        }
    };

    static {
        TAG = (!WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS || WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) ? "WindowManager" : "RemoteAnimationController";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteAnimationController(WindowManagerService service, RemoteAnimationAdapter remoteAnimationAdapter, Handler handler) {
        this.mService = service;
        this.mRemoteAnimationAdapter = remoteAnimationAdapter;
        this.mHandler = handler;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AnimationAdapter createAnimationAdapter(AppWindowToken appWindowToken, Point position, Rect stackBounds) {
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            String str = TAG;
            Slog.d(str, "createAnimationAdapter(): token=" + appWindowToken);
        }
        RemoteAnimationAdapterWrapper adapter = new RemoteAnimationAdapterWrapper(appWindowToken, position, stackBounds);
        this.mPendingAnimations.add(adapter);
        return adapter;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void goodToGo() {
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            Slog.d(TAG, "goodToGo()");
        }
        if (this.mPendingAnimations.isEmpty() || this.mCanceled) {
            if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                String str = TAG;
                Slog.d(str, "goodToGo(): Animation finished already, canceled=" + this.mCanceled + " mPendingAnimations=" + this.mPendingAnimations.size());
            }
            onAnimationFinished();
            return;
        }
        this.mHandler.postDelayed(this.mTimeoutRunnable, 2000.0f * this.mService.getCurrentAnimatorScale());
        this.mFinishedCallback = new FinishedCallback(this);
        final RemoteAnimationTarget[] animations = createAnimations();
        if (animations.length == 0) {
            if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                Slog.d(TAG, "goodToGo(): No apps to animate");
            }
            onAnimationFinished();
            return;
        }
        this.mService.mAnimator.addAfterPrepareSurfacesRunnable(new Runnable() { // from class: com.android.server.wm.-$$Lambda$RemoteAnimationController$f_Hsu4PN7pGOiq9Nl8vxzEA3wa0
            @Override // java.lang.Runnable
            public final void run() {
                RemoteAnimationController.lambda$goodToGo$1(RemoteAnimationController.this, animations);
            }
        });
        sendRunningRemoteAnimation(true);
    }

    public static /* synthetic */ void lambda$goodToGo$1(RemoteAnimationController remoteAnimationController, RemoteAnimationTarget[] animations) {
        try {
            remoteAnimationController.linkToDeathOfRunner();
            remoteAnimationController.mRemoteAnimationAdapter.getRunner().onAnimationStart(animations, remoteAnimationController.mFinishedCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to start remote animation", e);
            remoteAnimationController.onAnimationFinished();
        }
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            Slog.d(TAG, "startAnimation(): Notify animation start:");
            remoteAnimationController.writeStartDebugStatement();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelAnimation(String reason) {
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            String str = TAG;
            Slog.d(str, "cancelAnimation(): reason=" + reason);
        }
        synchronized (this.mService.getWindowManagerLock()) {
            if (this.mCanceled) {
                return;
            }
            this.mCanceled = true;
            onAnimationFinished();
            invokeAnimationCancelled();
        }
    }

    private void writeStartDebugStatement() {
        Slog.i(TAG, "Starting remote animation");
        StringWriter sw = new StringWriter();
        PrintWriter fastPrintWriter = new FastPrintWriter(sw);
        for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
            this.mPendingAnimations.get(i).dump(fastPrintWriter, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        }
        fastPrintWriter.close();
        Slog.i(TAG, sw.toString());
    }

    private RemoteAnimationTarget[] createAnimations() {
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            Slog.d(TAG, "createAnimations()");
        }
        ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
            RemoteAnimationAdapterWrapper wrapper = this.mPendingAnimations.get(i);
            RemoteAnimationTarget target = wrapper.createRemoteAppAnimation();
            if (target != null) {
                if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                    Slog.d(TAG, "\tAdd token=" + wrapper.mAppWindowToken);
                }
                targets.add(target);
            } else {
                if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                    Slog.d(TAG, "\tRemove token=" + wrapper.mAppWindowToken);
                }
                if (wrapper.mCapturedFinishCallback != null) {
                    wrapper.mCapturedFinishCallback.onAnimationFinished(wrapper);
                }
                this.mPendingAnimations.remove(i);
            }
        }
        int i2 = targets.size();
        return (RemoteAnimationTarget[]) targets.toArray(new RemoteAnimationTarget[i2]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onAnimationFinished() {
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            Slog.d(TAG, "onAnimationFinished(): mPendingAnimations=" + this.mPendingAnimations.size());
        }
        this.mHandler.removeCallbacks(this.mTimeoutRunnable);
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                unlinkToDeathOfRunner();
                releaseFinishedCallback();
                this.mService.openSurfaceTransaction();
                try {
                    if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                        Slog.d(TAG, "onAnimationFinished(): Notify animation finished:");
                    }
                    for (int i = this.mPendingAnimations.size() - 1; i >= 0; i--) {
                        RemoteAnimationAdapterWrapper adapter = this.mPendingAnimations.get(i);
                        adapter.mCapturedFinishCallback.onAnimationFinished(adapter);
                        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                            Slog.d(TAG, "\t" + adapter.mAppWindowToken);
                        }
                    }
                    this.mService.closeSurfaceTransaction("RemoteAnimationController#finished");
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to finish remote animation", e);
                    throw e;
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        sendRunningRemoteAnimation(false);
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            Slog.i(TAG, "Finishing remote animation");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void invokeAnimationCancelled() {
        try {
            this.mRemoteAnimationAdapter.getRunner().onAnimationCancelled();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to notify cancel", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void releaseFinishedCallback() {
        if (this.mFinishedCallback != null) {
            this.mFinishedCallback.release();
            this.mFinishedCallback = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendRunningRemoteAnimation(boolean running) {
        int pid = this.mRemoteAnimationAdapter.getCallingPid();
        if (pid == 0) {
            throw new RuntimeException("Calling pid of remote animation was null");
        }
        this.mService.sendSetRunningRemoteAnimation(pid, running);
    }

    private void linkToDeathOfRunner() throws RemoteException {
        if (!this.mLinkedToDeathOfRunner) {
            this.mRemoteAnimationAdapter.getRunner().asBinder().linkToDeath(this, 0);
            this.mLinkedToDeathOfRunner = true;
        }
    }

    private void unlinkToDeathOfRunner() {
        if (this.mLinkedToDeathOfRunner) {
            this.mRemoteAnimationAdapter.getRunner().asBinder().unlinkToDeath(this, 0);
            this.mLinkedToDeathOfRunner = false;
        }
    }

    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        cancelAnimation("binderDied");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class FinishedCallback extends IRemoteAnimationFinishedCallback.Stub {
        RemoteAnimationController mOuter;

        FinishedCallback(RemoteAnimationController outer) {
            this.mOuter = outer;
        }

        public void onAnimationFinished() throws RemoteException {
            if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                String str = RemoteAnimationController.TAG;
                Slog.d(str, "app-onAnimationFinished(): mOuter=" + this.mOuter);
            }
            long token = Binder.clearCallingIdentity();
            try {
                if (this.mOuter != null) {
                    this.mOuter.onAnimationFinished();
                    this.mOuter = null;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        void release() {
            if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                String str = RemoteAnimationController.TAG;
                Slog.d(str, "app-release(): mOuter=" + this.mOuter);
            }
            this.mOuter = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class RemoteAnimationAdapterWrapper implements AnimationAdapter {
        private final AppWindowToken mAppWindowToken;
        private SurfaceAnimator.OnAnimationFinishedCallback mCapturedFinishCallback;
        private SurfaceControl mCapturedLeash;
        private final Point mPosition = new Point();
        private final Rect mStackBounds = new Rect();
        private RemoteAnimationTarget mTarget;

        RemoteAnimationAdapterWrapper(AppWindowToken appWindowToken, Point position, Rect stackBounds) {
            this.mAppWindowToken = appWindowToken;
            this.mPosition.set(position.x, position.y);
            this.mStackBounds.set(stackBounds);
        }

        RemoteAnimationTarget createRemoteAppAnimation() {
            Task task = this.mAppWindowToken.getTask();
            WindowState mainWindow = this.mAppWindowToken.findMainWindow();
            if (task == null || mainWindow == null || this.mCapturedFinishCallback == null || this.mCapturedLeash == null) {
                return null;
            }
            Rect insets = new Rect(mainWindow.mContentInsets);
            InsetUtils.addInsets(insets, this.mAppWindowToken.getLetterboxInsets());
            this.mTarget = new RemoteAnimationTarget(task.mTaskId, getMode(), this.mCapturedLeash, !this.mAppWindowToken.fillsParent(), mainWindow.mWinAnimator.mLastClipRect, insets, this.mAppWindowToken.getPrefixOrderIndex(), this.mPosition, this.mStackBounds, task.getWindowConfiguration(), false);
            return this.mTarget;
        }

        private int getMode() {
            if (RemoteAnimationController.this.mService.mOpeningApps.contains(this.mAppWindowToken)) {
                return 0;
            }
            return 1;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public boolean getDetachWallpaper() {
            return false;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public boolean getShowWallpaper() {
            return false;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public int getBackgroundColor() {
            return 0;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
            if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                Slog.d(RemoteAnimationController.TAG, "startAnimation");
            }
            t.setLayer(animationLeash, this.mAppWindowToken.getPrefixOrderIndex());
            t.setPosition(animationLeash, this.mPosition.x, this.mPosition.y);
            RemoteAnimationController.this.mTmpRect.set(this.mStackBounds);
            RemoteAnimationController.this.mTmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, RemoteAnimationController.this.mTmpRect);
            this.mCapturedLeash = animationLeash;
            this.mCapturedFinishCallback = finishCallback;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            RemoteAnimationController.this.mPendingAnimations.remove(this);
            if (RemoteAnimationController.this.mPendingAnimations.isEmpty()) {
                RemoteAnimationController.this.mHandler.removeCallbacks(RemoteAnimationController.this.mTimeoutRunnable);
                RemoteAnimationController.this.releaseFinishedCallback();
                RemoteAnimationController.this.invokeAnimationCancelled();
                RemoteAnimationController.this.sendRunningRemoteAnimation(false);
            }
        }

        @Override // com.android.server.wm.AnimationAdapter
        public long getDurationHint() {
            return RemoteAnimationController.this.mRemoteAnimationAdapter.getDuration();
        }

        @Override // com.android.server.wm.AnimationAdapter
        public long getStatusBarTransitionsStartTime() {
            return SystemClock.uptimeMillis() + RemoteAnimationController.this.mRemoteAnimationAdapter.getStatusBarTransitionDelay();
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.print("token=");
            pw.println(this.mAppWindowToken);
            if (this.mTarget != null) {
                pw.print(prefix);
                pw.println("Target:");
                RemoteAnimationTarget remoteAnimationTarget = this.mTarget;
                remoteAnimationTarget.dump(pw, prefix + "  ");
                return;
            }
            pw.print(prefix);
            pw.println("Target: null");
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void writeToProto(ProtoOutputStream proto) {
            long token = proto.start(1146756268034L);
            if (this.mTarget != null) {
                this.mTarget.writeToProto(proto, 1146756268033L);
            }
            proto.end(token);
        }
    }
}
