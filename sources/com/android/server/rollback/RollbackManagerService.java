package com.android.server.rollback;

import android.content.Context;
import com.android.server.SystemService;

/* loaded from: classes.dex */
public final class RollbackManagerService extends SystemService {
    private RollbackManagerServiceImpl mService;

    public RollbackManagerService(Context context) {
        super(context);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        this.mService = new RollbackManagerServiceImpl(getContext());
        publishBinderService("rollback", this.mService);
    }

    @Override // com.android.server.SystemService
    public void onUnlockUser(int user) {
        this.mService.onUnlockUser(user);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 1000) {
            this.mService.onBootCompleted();
        }
    }
}
