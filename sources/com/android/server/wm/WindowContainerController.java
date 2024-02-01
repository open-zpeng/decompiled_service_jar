package com.android.server.wm;

import android.content.res.Configuration;
import com.android.server.wm.WindowContainer;
import com.android.server.wm.WindowContainerListener;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WindowContainerController<E extends WindowContainer, I extends WindowContainerListener> implements ConfigurationContainerListener {
    E mContainer;
    final I mListener;
    final RootWindowContainer mRoot;
    final WindowManagerService mService;
    final WindowHashMap mWindowMap;

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowContainerController(I listener, WindowManagerService service) {
        this.mListener = listener;
        this.mService = service;
        this.mRoot = this.mService != null ? this.mService.mRoot : null;
        this.mWindowMap = this.mService != null ? this.mService.mWindowMap : null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setContainer(E container) {
        if (this.mContainer != null && container != null) {
            throw new IllegalArgumentException("Can't set container=" + container + " for controller=" + this + " Already set to=" + this.mContainer);
        }
        this.mContainer = container;
        if (this.mContainer != null && this.mListener != null) {
            this.mListener.registerConfigurationChangeListener(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeContainer() {
        if (this.mContainer == null) {
            return;
        }
        this.mContainer.setController(null);
        this.mContainer = null;
        if (this.mListener != null) {
            this.mListener.unregisterConfigurationChangeListener(this);
        }
    }

    @Override // com.android.server.wm.ConfigurationContainerListener
    public void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                this.mContainer.onOverrideConfigurationChanged(overrideConfiguration);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }
}
