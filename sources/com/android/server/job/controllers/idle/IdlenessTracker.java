package com.android.server.job.controllers.idle;

import android.content.Context;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public interface IdlenessTracker {
    void dump(PrintWriter printWriter);

    boolean isIdle();

    void startTracking(Context context, IdlenessListener idlenessListener);
}
