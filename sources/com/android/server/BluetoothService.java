package com.android.server;

import android.content.Context;

/* loaded from: classes.dex */
class BluetoothService extends SystemService {
    private BluetoothManagerService mBluetoothManagerService;
    private boolean mInitialized;

    public BluetoothService(Context context) {
        super(context);
        this.mInitialized = false;
        this.mBluetoothManagerService = new BluetoothManagerService(context);
    }

    private void initialize() {
        if (!this.mInitialized) {
            this.mBluetoothManagerService.handleOnBootPhase();
            this.mInitialized = true;
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            publishBinderService("bluetooth_manager", this.mBluetoothManagerService);
        } else if (phase == 550) {
            initialize();
        }
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userHandle) {
        if (!this.mInitialized) {
            initialize();
        } else {
            this.mBluetoothManagerService.handleOnSwitchUser(userHandle);
        }
    }

    @Override // com.android.server.SystemService
    public void onUnlockUser(int userHandle) {
        this.mBluetoothManagerService.handleOnUnlockUser(userHandle);
    }
}
