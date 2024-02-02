package com.android.server.wm;
/* loaded from: classes.dex */
public interface WindowContainerListener {
    void registerConfigurationChangeListener(ConfigurationContainerListener configurationContainerListener);

    void unregisterConfigurationChangeListener(ConfigurationContainerListener configurationContainerListener);
}
