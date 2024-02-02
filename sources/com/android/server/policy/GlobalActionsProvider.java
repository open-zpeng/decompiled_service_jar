package com.android.server.policy;
/* loaded from: classes.dex */
public interface GlobalActionsProvider {

    /* loaded from: classes.dex */
    public interface GlobalActionsListener {
        void onGlobalActionsAvailableChanged(boolean z);

        void onGlobalActionsDismissed();

        void onGlobalActionsShown();
    }

    boolean isGlobalActionsDisabled();

    void setGlobalActionsListener(GlobalActionsListener globalActionsListener);

    void showGlobalActions();
}
