package com.android.server.wm;

import android.content.res.Configuration;

/* loaded from: classes2.dex */
public interface WindowContainerListener {
    void registerConfigurationChangeListener(ConfigurationContainerListener configurationContainerListener);

    void unregisterConfigurationChangeListener(ConfigurationContainerListener configurationContainerListener);

    default void onInitializeOverrideConfiguration(Configuration config) {
    }
}
