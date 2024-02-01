package com.android.server.wm;

import android.app.ActivityManager;
import android.graphics.Rect;
/* loaded from: classes.dex */
public interface TaskWindowContainerListener extends WindowContainerListener {
    void onSnapshotChanged(ActivityManager.TaskSnapshot taskSnapshot);

    void requestResize(Rect rect, int i);
}
