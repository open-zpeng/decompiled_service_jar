package com.android.server.twilight;

import android.os.Handler;
/* loaded from: classes.dex */
public interface TwilightManager {
    TwilightState getLastTwilightState();

    void registerListener(TwilightListener twilightListener, Handler handler);

    void unregisterListener(TwilightListener twilightListener);
}
