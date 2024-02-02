package com.android.server.wm;

import android.graphics.Rect;
/* loaded from: classes.dex */
public interface PinnedStackWindowListener extends StackWindowListener {
    default void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds, boolean forceUpdate) {
    }
}
