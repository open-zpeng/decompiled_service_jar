package com.android.server.wm;

import android.app.WindowConfiguration;
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
import com.android.server.wm.SurfaceAnimator;
import com.android.server.wm.utils.InsetUtils;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class RemoteAnimationController implements IBinder.DeathRecipient {
    private static final String TAG;
    private static final long TIMEOUT_MS = 2000;
    private boolean mCanceled;
    private FinishedCallback mFinishedCallback;
    private final Handler mHandler;
    private boolean mLinkedToDeathOfRunner;
    private final RemoteAnimationAdapter mRemoteAnimationAdapter;
    private final WindowManagerService mService;
    private final ArrayList<RemoteAnimationRecord> mPendingAnimations = new ArrayList<>();
    private final Rect mTmpRect = new Rect();
    private final Runnable mTimeoutRunnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$RemoteAnimationController$uQS8vaPKQ-E3x_9G8NCxPQmw1fw
        @Override // java.lang.Runnable
        public final void run() {
            RemoteAnimationController.this.lambda$new$0$RemoteAnimationController();
        }
    };

    static {
        TAG = (!WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS || WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) ? "WindowManager" : "RemoteAnimationController";
    }

    public /* synthetic */ void lambda$new$0$RemoteAnimationController() {
        cancelAnimation("timeoutRunnable");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteAnimationController(WindowManagerService service, RemoteAnimationAdapter remoteAnimationAdapter, Handler handler) {
        this.mService = service;
        this.mRemoteAnimationAdapter = remoteAnimationAdapter;
        this.mHandler = handler;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteAnimationRecord createRemoteAnimationRecord(AppWindowToken appWindowToken, Point position, Rect stackBounds, Rect startBounds) {
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            String str = TAG;
            Slog.d(str, "createAnimationAdapter(): token=" + appWindowToken);
        }
        RemoteAnimationRecord adapters = new RemoteAnimationRecord(appWindowToken, position, stackBounds, startBounds);
        this.mPendingAnimations.add(adapters);
        return adapters;
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
        this.mHandler.postDelayed(this.mTimeoutRunnable, this.mService.getCurrentAnimatorScale() * 2000.0f);
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
                RemoteAnimationController.this.lambda$goodToGo$1$RemoteAnimationController(animations);
            }
        });
        setRunningRemoteAnimation(true);
    }

    public /* synthetic */ void lambda$goodToGo$1$RemoteAnimationController(RemoteAnimationTarget[] animations) {
        try {
            linkToDeathOfRunner();
            this.mRemoteAnimationAdapter.getRunner().onAnimationStart(animations, this.mFinishedCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to start remote animation", e);
            onAnimationFinished();
        }
        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
            Slog.d(TAG, "startAnimation(): Notify animation start:");
            writeStartDebugStatement();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
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
            this.mPendingAnimations.get(i).mAdapter.dump(fastPrintWriter, "");
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
            RemoteAnimationRecord wrappers = this.mPendingAnimations.get(i);
            RemoteAnimationTarget target = wrappers.createRemoteAnimationTarget();
            if (target != null) {
                if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                    Slog.d(TAG, "\tAdd token=" + wrappers.mAppWindowToken);
                }
                targets.add(target);
            } else {
                if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                    Slog.d(TAG, "\tRemove token=" + wrappers.mAppWindowToken);
                }
                if (wrappers.mAdapter != null && wrappers.mAdapter.mCapturedFinishCallback != null) {
                    wrappers.mAdapter.mCapturedFinishCallback.onAnimationFinished(wrappers.mAdapter);
                }
                if (wrappers.mThumbnailAdapter != null && wrappers.mThumbnailAdapter.mCapturedFinishCallback != null) {
                    wrappers.mThumbnailAdapter.mCapturedFinishCallback.onAnimationFinished(wrappers.mThumbnailAdapter);
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
        synchronized (this.mService.mGlobalLock) {
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
                        RemoteAnimationRecord adapters = this.mPendingAnimations.get(i);
                        if (adapters.mAdapter != null) {
                            adapters.mAdapter.mCapturedFinishCallback.onAnimationFinished(adapters.mAdapter);
                        }
                        if (adapters.mThumbnailAdapter != null) {
                            adapters.mThumbnailAdapter.mCapturedFinishCallback.onAnimationFinished(adapters.mThumbnailAdapter);
                        }
                        if (WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS) {
                            Slog.d(TAG, "\t" + adapters.mAppWindowToken);
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
        setRunningRemoteAnimation(false);
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
        FinishedCallback finishedCallback = this.mFinishedCallback;
        if (finishedCallback != null) {
            finishedCallback.release();
            this.mFinishedCallback = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setRunningRemoteAnimation(boolean running) {
        int pid = this.mRemoteAnimationAdapter.getCallingPid();
        int uid = this.mRemoteAnimationAdapter.getCallingUid();
        if (pid == 0) {
            throw new RuntimeException("Calling pid of remote animation was null");
        }
        WindowProcessController wpc = this.mService.mAtmService.getProcessController(pid, uid);
        if (wpc == null) {
            String str = TAG;
            Slog.w(str, "Unable to find process with pid=" + pid + " uid=" + uid);
            return;
        }
        wpc.setRunningRemoteAnimation(running);
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
    /* loaded from: classes2.dex */
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

    /* loaded from: classes2.dex */
    public class RemoteAnimationRecord {
        RemoteAnimationAdapterWrapper mAdapter;
        final AppWindowToken mAppWindowToken;
        final Rect mStartBounds;
        RemoteAnimationTarget mTarget;
        RemoteAnimationAdapterWrapper mThumbnailAdapter;

        RemoteAnimationRecord(AppWindowToken appWindowToken, Point endPos, Rect endBounds, Rect startBounds) {
            this.mThumbnailAdapter = null;
            this.mAppWindowToken = appWindowToken;
            this.mAdapter = new RemoteAnimationAdapterWrapper(this, endPos, endBounds);
            if (startBounds != null) {
                this.mStartBounds = new Rect(startBounds);
                RemoteAnimationController.this.mTmpRect.set(startBounds);
                RemoteAnimationController.this.mTmpRect.offsetTo(0, 0);
                if (RemoteAnimationController.this.mRemoteAnimationAdapter.getChangeNeedsSnapshot()) {
                    this.mThumbnailAdapter = new RemoteAnimationAdapterWrapper(this, new Point(0, 0), RemoteAnimationController.this.mTmpRect);
                    return;
                }
                return;
            }
            this.mStartBounds = null;
        }

        RemoteAnimationTarget createRemoteAnimationTarget() {
            RemoteAnimationAdapterWrapper remoteAnimationAdapterWrapper;
            Task task = this.mAppWindowToken.getTask();
            WindowState mainWindow = this.mAppWindowToken.findMainWindow();
            if (task != null && mainWindow != null && (remoteAnimationAdapterWrapper = this.mAdapter) != null) {
                if (remoteAnimationAdapterWrapper.mCapturedFinishCallback != null && this.mAdapter.mCapturedLeash != null) {
                    Rect insets = new Rect();
                    mainWindow.getContentInsets(insets);
                    InsetUtils.addInsets(insets, this.mAppWindowToken.getLetterboxInsets());
                    int i = task.mTaskId;
                    int mode = getMode();
                    SurfaceControl surfaceControl = this.mAdapter.mCapturedLeash;
                    boolean z = !this.mAppWindowToken.fillsParent();
                    Rect rect = mainWindow.mWinAnimator.mLastClipRect;
                    int prefixOrderIndex = this.mAppWindowToken.getPrefixOrderIndex();
                    Point point = this.mAdapter.mPosition;
                    Rect rect2 = this.mAdapter.mStackBounds;
                    WindowConfiguration windowConfiguration = task.getWindowConfiguration();
                    RemoteAnimationAdapterWrapper remoteAnimationAdapterWrapper2 = this.mThumbnailAdapter;
                    this.mTarget = new RemoteAnimationTarget(i, mode, surfaceControl, z, rect, insets, prefixOrderIndex, point, rect2, windowConfiguration, false, remoteAnimationAdapterWrapper2 != null ? remoteAnimationAdapterWrapper2.mCapturedLeash : null, this.mStartBounds);
                    return this.mTarget;
                }
            }
            return null;
        }

        private int getMode() {
            DisplayContent dc = this.mAppWindowToken.getDisplayContent();
            if (dc.mOpeningApps.contains(this.mAppWindowToken)) {
                return 0;
            }
            if (dc.mChangingApps.contains(this.mAppWindowToken)) {
                return 2;
            }
            return 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class RemoteAnimationAdapterWrapper implements AnimationAdapter {
        private SurfaceAnimator.OnAnimationFinishedCallback mCapturedFinishCallback;
        SurfaceControl mCapturedLeash;
        private final RemoteAnimationRecord mRecord;
        private final Point mPosition = new Point();
        private final Rect mStackBounds = new Rect();

        RemoteAnimationAdapterWrapper(RemoteAnimationRecord record, Point position, Rect stackBounds) {
            this.mRecord = record;
            this.mPosition.set(position.x, position.y);
            this.mStackBounds.set(stackBounds);
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
            t.setLayer(animationLeash, this.mRecord.mAppWindowToken.getPrefixOrderIndex());
            t.setPosition(animationLeash, this.mPosition.x, this.mPosition.y);
            RemoteAnimationController.this.mTmpRect.set(this.mStackBounds);
            RemoteAnimationController.this.mTmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, RemoteAnimationController.this.mTmpRect);
            this.mCapturedLeash = animationLeash;
            this.mCapturedFinishCallback = finishCallback;
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            if (this.mRecord.mAdapter == this) {
                this.mRecord.mAdapter = null;
            } else {
                this.mRecord.mThumbnailAdapter = null;
            }
            if (this.mRecord.mAdapter == null && this.mRecord.mThumbnailAdapter == null) {
                RemoteAnimationController.this.mPendingAnimations.remove(this.mRecord);
            }
            if (RemoteAnimationController.this.mPendingAnimations.isEmpty()) {
                RemoteAnimationController.this.mHandler.removeCallbacks(RemoteAnimationController.this.mTimeoutRunnable);
                RemoteAnimationController.this.releaseFinishedCallback();
                RemoteAnimationController.this.invokeAnimationCancelled();
                RemoteAnimationController.this.setRunningRemoteAnimation(false);
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
            pw.println(this.mRecord.mAppWindowToken);
            if (this.mRecord.mTarget != null) {
                pw.print(prefix);
                pw.println("Target:");
                RemoteAnimationTarget remoteAnimationTarget = this.mRecord.mTarget;
                remoteAnimationTarget.dump(pw, prefix + "  ");
                return;
            }
            pw.print(prefix);
            pw.println("Target: null");
        }

        @Override // com.android.server.wm.AnimationAdapter
        public void writeToProto(ProtoOutputStream proto) {
            long token = proto.start(1146756268034L);
            if (this.mRecord.mTarget != null) {
                this.mRecord.mTarget.writeToProto(proto, 1146756268033L);
            }
            proto.end(token);
        }
    }
}
