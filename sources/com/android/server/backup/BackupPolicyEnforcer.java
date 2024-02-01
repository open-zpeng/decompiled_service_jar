package com.android.server.backup;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import com.android.internal.annotations.VisibleForTesting;
@VisibleForTesting
/* loaded from: classes.dex */
public class BackupPolicyEnforcer {
    private DevicePolicyManager mDevicePolicyManager;

    public BackupPolicyEnforcer(Context context) {
        this.mDevicePolicyManager = (DevicePolicyManager) context.getSystemService("device_policy");
    }

    public ComponentName getMandatoryBackupTransport() {
        return this.mDevicePolicyManager.getMandatoryBackupTransport();
    }
}
