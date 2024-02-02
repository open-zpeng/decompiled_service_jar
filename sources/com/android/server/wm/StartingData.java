package com.android.server.wm;

import com.android.server.policy.WindowManagerPolicy;
/* loaded from: classes.dex */
public abstract class StartingData {
    protected final WindowManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public abstract WindowManagerPolicy.StartingSurface createStartingSurface(AppWindowToken appWindowToken);

    /* JADX INFO: Access modifiers changed from: protected */
    public StartingData(WindowManagerService service) {
        this.mService = service;
    }
}
