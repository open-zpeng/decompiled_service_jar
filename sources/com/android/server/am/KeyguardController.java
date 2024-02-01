package com.android.server.am;

import android.app.ActivityManagerInternal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.server.wm.WindowManagerService;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class KeyguardController {
    private static final String TAG = "ActivityManager";
    private boolean mAodShowing;
    private int mBeforeUnoccludeTransit;
    private boolean mDismissalRequested;
    private ActivityRecord mDismissingKeyguardActivity;
    private boolean mKeyguardGoingAway;
    private boolean mKeyguardShowing;
    private boolean mOccluded;
    private int mSecondaryDisplayShowing = -1;
    private final ActivityManagerService mService;
    private ActivityManagerInternal.SleepToken mSleepToken;
    private final ActivityStackSupervisor mStackSupervisor;
    private int mVisibilityTransactionDepth;
    private WindowManagerService mWindowManager;

    /* JADX INFO: Access modifiers changed from: package-private */
    public KeyguardController(ActivityManagerService service, ActivityStackSupervisor stackSupervisor) {
        this.mService = service;
        this.mStackSupervisor = stackSupervisor;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowManager(WindowManagerService windowManager) {
        this.mWindowManager = windowManager;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isKeyguardOrAodShowing(int displayId) {
        return (this.mKeyguardShowing || this.mAodShowing) && !this.mKeyguardGoingAway && (displayId != 0 ? displayId == this.mSecondaryDisplayShowing : !this.mOccluded);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isKeyguardShowing(int displayId) {
        return this.mKeyguardShowing && !this.mKeyguardGoingAway && (displayId != 0 ? displayId == this.mSecondaryDisplayShowing : !this.mOccluded);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isKeyguardLocked() {
        return this.mKeyguardShowing && !this.mKeyguardGoingAway;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isKeyguardGoingAway() {
        return this.mKeyguardGoingAway && this.mKeyguardShowing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setKeyguardShown(boolean keyguardShowing, boolean aodShowing, int secondaryDisplayShowing) {
        boolean z = true;
        boolean showingChanged = (keyguardShowing == this.mKeyguardShowing && aodShowing == this.mAodShowing) ? false : true;
        if (!this.mKeyguardGoingAway || !keyguardShowing) {
            z = false;
        }
        boolean showingChanged2 = showingChanged | z;
        if (!showingChanged2 && secondaryDisplayShowing == this.mSecondaryDisplayShowing) {
            return;
        }
        this.mKeyguardShowing = keyguardShowing;
        this.mAodShowing = aodShowing;
        this.mSecondaryDisplayShowing = secondaryDisplayShowing;
        this.mWindowManager.setAodShowing(aodShowing);
        if (showingChanged2) {
            dismissDockedStackIfNeeded();
            setKeyguardGoingAway(false);
            this.mWindowManager.setKeyguardOrAodShowingOnDefaultDisplay(isKeyguardOrAodShowing(0));
            if (keyguardShowing) {
                this.mDismissalRequested = false;
            }
        }
        this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
        updateKeyguardSleepToken();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void keyguardGoingAway(int flags) {
        if (!this.mKeyguardShowing) {
            return;
        }
        Trace.traceBegin(64L, "keyguardGoingAway");
        this.mWindowManager.deferSurfaceLayout();
        try {
            setKeyguardGoingAway(true);
            this.mWindowManager.prepareAppTransition(20, false, convertTransitFlags(flags), false);
            updateKeyguardSleepToken();
            this.mStackSupervisor.resumeFocusedStackTopActivityLocked();
            this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
            this.mStackSupervisor.addStartingWindowsForVisibleActivities(true);
            this.mWindowManager.executeAppTransition();
        } finally {
            Trace.traceBegin(64L, "keyguardGoingAway: surfaceLayout");
            this.mWindowManager.continueSurfaceLayout();
            Trace.traceEnd(64L);
            Trace.traceEnd(64L);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback, CharSequence message) {
        ActivityRecord activityRecord = ActivityRecord.forTokenLocked(token);
        if (activityRecord == null || !activityRecord.visibleIgnoringKeyguard) {
            failCallback(callback);
            return;
        }
        Slog.i(TAG, "Activity requesting to dismiss Keyguard: " + activityRecord);
        if (activityRecord.getTurnScreenOnFlag() && activityRecord.isTopRunningActivity()) {
            this.mStackSupervisor.wakeUp("dismissKeyguard");
        }
        this.mWindowManager.dismissKeyguard(callback, message);
    }

    private void setKeyguardGoingAway(boolean keyguardGoingAway) {
        this.mKeyguardGoingAway = keyguardGoingAway;
        this.mWindowManager.setKeyguardGoingAway(keyguardGoingAway);
    }

    private void failCallback(IKeyguardDismissCallback callback) {
        try {
            callback.onDismissError();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call callback", e);
        }
    }

    private int convertTransitFlags(int keyguardGoingAwayFlags) {
        int result = 0;
        if ((keyguardGoingAwayFlags & 1) != 0) {
            result = 0 | 1;
        }
        if ((keyguardGoingAwayFlags & 2) != 0) {
            result |= 2;
        }
        if ((keyguardGoingAwayFlags & 4) != 0) {
            return result | 4;
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void beginActivityVisibilityUpdate() {
        this.mVisibilityTransactionDepth++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void endActivityVisibilityUpdate() {
        this.mVisibilityTransactionDepth--;
        if (this.mVisibilityTransactionDepth == 0) {
            visibilitiesUpdated();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowActivityWhileKeyguardShowing(ActivityRecord r, boolean dismissKeyguard) {
        return dismissKeyguard && canDismissKeyguard() && !this.mAodShowing && (this.mDismissalRequested || r != this.mDismissingKeyguardActivity);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canShowWhileOccluded(boolean dismissKeyguard, boolean showWhenLocked) {
        return showWhenLocked || (dismissKeyguard && !this.mWindowManager.isKeyguardSecure());
    }

    private void visibilitiesUpdated() {
        boolean lastOccluded = this.mOccluded;
        ActivityRecord lastDismissingKeyguardActivity = this.mDismissingKeyguardActivity;
        this.mOccluded = false;
        this.mDismissingKeyguardActivity = null;
        for (int displayNdx = this.mStackSupervisor.getChildCount() - 1; displayNdx >= 0; displayNdx--) {
            ActivityDisplay display = this.mStackSupervisor.getChildAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
                ActivityStack stack = display.getChildAt(stackNdx);
                if (display.mDisplayId == 0 && this.mStackSupervisor.isFocusedStack(stack)) {
                    ActivityRecord topDismissing = stack.getTopDismissingKeyguardActivity();
                    this.mOccluded = stack.topActivityOccludesKeyguard() || (topDismissing != null && stack.topRunningActivityLocked() == topDismissing && canShowWhileOccluded(true, false));
                }
                if (this.mDismissingKeyguardActivity == null && stack.getTopDismissingKeyguardActivity() != null) {
                    this.mDismissingKeyguardActivity = stack.getTopDismissingKeyguardActivity();
                }
            }
        }
        this.mOccluded |= this.mWindowManager.isShowingDream();
        if (this.mOccluded != lastOccluded) {
            handleOccludedChanged();
        }
        if (this.mDismissingKeyguardActivity != lastDismissingKeyguardActivity) {
            handleDismissKeyguard();
        }
    }

    private void handleOccludedChanged() {
        this.mWindowManager.onKeyguardOccludedChanged(this.mOccluded);
        if (isKeyguardLocked()) {
            this.mWindowManager.deferSurfaceLayout();
            try {
                this.mWindowManager.prepareAppTransition(resolveOccludeTransit(), false, 0, true);
                updateKeyguardSleepToken();
                this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
                this.mWindowManager.executeAppTransition();
            } finally {
                this.mWindowManager.continueSurfaceLayout();
            }
        }
        dismissDockedStackIfNeeded();
    }

    private void handleDismissKeyguard() {
        if (!this.mOccluded && this.mDismissingKeyguardActivity != null && this.mWindowManager.isKeyguardSecure()) {
            this.mWindowManager.dismissKeyguard(null, null);
            this.mDismissalRequested = true;
            if (this.mKeyguardShowing && canDismissKeyguard() && this.mWindowManager.getPendingAppTransition() == 23) {
                this.mWindowManager.prepareAppTransition(this.mBeforeUnoccludeTransit, false, 0, true);
                this.mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
                this.mWindowManager.executeAppTransition();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canDismissKeyguard() {
        return this.mWindowManager.isKeyguardTrusted() || !this.mWindowManager.isKeyguardSecure();
    }

    private int resolveOccludeTransit() {
        if (this.mBeforeUnoccludeTransit != -1 && this.mWindowManager.getPendingAppTransition() == 23 && this.mOccluded) {
            return this.mBeforeUnoccludeTransit;
        }
        if (!this.mOccluded) {
            this.mBeforeUnoccludeTransit = this.mWindowManager.getPendingAppTransition();
            return 23;
        }
        return 22;
    }

    private void dismissDockedStackIfNeeded() {
        ActivityStack stack;
        if (!this.mKeyguardShowing || !this.mOccluded || (stack = this.mStackSupervisor.getDefaultDisplay().getSplitScreenPrimaryStack()) == null) {
            return;
        }
        this.mStackSupervisor.moveTasksToFullscreenStackLocked(stack, this.mStackSupervisor.mFocusedStack == stack);
    }

    private void updateKeyguardSleepToken() {
        if (this.mSleepToken == null && isKeyguardOrAodShowing(0)) {
            this.mSleepToken = this.mService.acquireSleepToken("Keyguard", 0);
        } else if (this.mSleepToken != null && !isKeyguardOrAodShowing(0)) {
            this.mSleepToken.release();
            this.mSleepToken = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "KeyguardController:");
        pw.println(prefix + "  mKeyguardShowing=" + this.mKeyguardShowing);
        pw.println(prefix + "  mAodShowing=" + this.mAodShowing);
        pw.println(prefix + "  mKeyguardGoingAway=" + this.mKeyguardGoingAway);
        pw.println(prefix + "  mOccluded=" + this.mOccluded);
        pw.println(prefix + "  mDismissingKeyguardActivity=" + this.mDismissingKeyguardActivity);
        pw.println(prefix + "  mDismissalRequested=" + this.mDismissalRequested);
        pw.println(prefix + "  mVisibilityTransactionDepth=" + this.mVisibilityTransactionDepth);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1133871366145L, this.mKeyguardShowing);
        proto.write(1133871366146L, this.mOccluded);
        proto.end(token);
    }
}
