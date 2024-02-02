package com.android.server.wm;

import android.graphics.Rect;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public interface BoundsAnimationTarget {
    void onAnimationEnd(boolean z, Rect rect, boolean z2);

    void onAnimationStart(boolean z, boolean z2);

    boolean setPinnedStackSize(Rect rect, Rect rect2);

    boolean shouldDeferStartOnMoveToFullscreen();
}
