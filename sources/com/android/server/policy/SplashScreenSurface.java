package com.android.server.policy;

import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import com.android.server.policy.WindowManagerPolicy;

/* loaded from: classes.dex */
class SplashScreenSurface implements WindowManagerPolicy.StartingSurface {
    private static final String TAG = "WindowManager";
    private final IBinder mAppToken;
    private final View mView;

    /* JADX INFO: Access modifiers changed from: package-private */
    public SplashScreenSurface(View view, IBinder appToken) {
        this.mView = view;
        this.mAppToken = appToken;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.StartingSurface
    public void remove() {
        WindowManager wm = (WindowManager) this.mView.getContext().getSystemService(WindowManager.class);
        wm.removeView(this.mView);
    }
}
