package com.android.server.storage;
/* loaded from: classes.dex */
public interface DeviceStorageMonitorInternal {
    void checkMemory();

    long getMemoryLowThreshold();

    boolean isMemoryLow();
}
