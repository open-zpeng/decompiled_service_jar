package com.android.server;

/* loaded from: classes.dex */
interface INativeDaemonConnectorCallbacks {
    boolean onCheckHoldWakeLock(int i);

    void onDaemonConnected();

    boolean onEvent(int i, String str, String[] strArr);
}
