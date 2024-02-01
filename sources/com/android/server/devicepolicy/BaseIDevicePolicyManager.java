package com.android.server.devicepolicy;

import android.app.admin.IDevicePolicyManager;

/* loaded from: classes.dex */
abstract class BaseIDevicePolicyManager extends IDevicePolicyManager.Stub {
    /* JADX INFO: Access modifiers changed from: package-private */
    public abstract void handleStartUser(int i);

    /* JADX INFO: Access modifiers changed from: package-private */
    public abstract void handleStopUser(int i);

    /* JADX INFO: Access modifiers changed from: package-private */
    public abstract void handleUnlockUser(int i);

    /* JADX INFO: Access modifiers changed from: package-private */
    public abstract void systemReady(int i);

    public void clearSystemUpdatePolicyFreezePeriodRecord() {
    }
}
