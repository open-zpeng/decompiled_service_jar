package com.android.server;

import android.content.Context;
/* loaded from: classes.dex */
class BluetoothService extends SystemService {
    private BluetoothManagerService mBluetoothManagerService;

    public BluetoothService(Context context) {
        super(context);
        this.mBluetoothManagerService = new BluetoothManagerService(context);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            publishBinderService("bluetooth_manager", this.mBluetoothManagerService);
        } else if (phase == 550) {
            this.mBluetoothManagerService.handleOnBootPhase();
        }
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userHandle) {
        this.mBluetoothManagerService.handleOnSwitchUser(userHandle);
    }

    @Override // com.android.server.SystemService
    public void onUnlockUser(int userHandle) {
        this.mBluetoothManagerService.handleOnUnlockUser(userHandle);
    }
}
