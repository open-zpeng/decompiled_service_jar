package com.android.server.wm;
/* loaded from: classes.dex */
public interface AppWindowContainerListener extends WindowContainerListener {
    boolean keyDispatchingTimedOut(String str, int i);

    void onStartingWindowDrawn(long j);

    void onWindowsDrawn(long j);

    void onWindowsGone();

    void onWindowsVisible();

    default void onWindowsNotDrawn(long timestamp) {
    }
}
