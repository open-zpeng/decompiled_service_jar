package com.android.server.wm;

import android.view.SurfaceControl;
import android.view.SurfaceSession;

/* loaded from: classes2.dex */
interface SurfaceBuilderFactory {
    SurfaceControl.Builder make(SurfaceSession surfaceSession);
}
