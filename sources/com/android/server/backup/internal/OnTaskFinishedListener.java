package com.android.server.backup.internal;

/* loaded from: classes.dex */
public interface OnTaskFinishedListener {
    public static final OnTaskFinishedListener NOP = new OnTaskFinishedListener() { // from class: com.android.server.backup.internal.-$$Lambda$OnTaskFinishedListener$Z6_F14MuVekV2sT2TC7YsDXDS1o
        @Override // com.android.server.backup.internal.OnTaskFinishedListener
        public final void onFinished(String str) {
            OnTaskFinishedListener.lambda$static$0(str);
        }
    };

    void onFinished(String str);

    static /* synthetic */ void lambda$static$0(String caller) {
    }
}
