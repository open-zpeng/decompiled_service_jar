package com.android.server.am;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import com.android.server.am.LaunchParamsController;
/* loaded from: classes.dex */
public class ActivityLaunchParamsModifier implements LaunchParamsController.LaunchParamsModifier {
    private final ActivityStackSupervisor mSupervisor;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityLaunchParamsModifier(ActivityStackSupervisor activityStackSupervisor) {
        this.mSupervisor = activityStackSupervisor;
    }

    @Override // com.android.server.am.LaunchParamsController.LaunchParamsModifier
    public int onCalculate(TaskRecord task, ActivityInfo.WindowLayout layout, ActivityRecord activity, ActivityRecord source, ActivityOptions options, LaunchParamsController.LaunchParams currentParams, LaunchParamsController.LaunchParams outParams) {
        Rect bounds;
        if (activity == null || !this.mSupervisor.canUseActivityOptionsLaunchBounds(options) || ((!activity.isResizeable() && (task == null || !task.isResizeable())) || (bounds = options.getLaunchBounds()) == null || bounds.isEmpty())) {
            return 0;
        }
        outParams.mBounds.set(bounds);
        return 1;
    }
}
