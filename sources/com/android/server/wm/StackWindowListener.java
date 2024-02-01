package com.android.server.wm;

import android.graphics.Rect;
/* loaded from: classes.dex */
public interface StackWindowListener extends WindowContainerListener {
    void requestResize(Rect rect);
}
