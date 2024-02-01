package com.android.server.wm;

import android.content.res.Configuration;
import com.android.server.wm.WindowContainer;
import com.android.server.wm.WindowContainerListener;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class WindowContainerController<E extends WindowContainer, I extends WindowContainerListener> implements ConfigurationContainerListener {
    E mContainer;
    final WindowManagerGlobalLock mGlobalLock;
    final I mListener;
    final RootWindowContainer mRoot;
    final WindowManagerService mService;

    WindowContainerController(I listener, WindowManagerService service) {
        this.mListener = listener;
        this.mService = service;
        WindowManagerService windowManagerService = this.mService;
        this.mRoot = windowManagerService != null ? windowManagerService.mRoot : null;
        WindowManagerService windowManagerService2 = this.mService;
        this.mGlobalLock = windowManagerService2 != null ? windowManagerService2.mGlobalLock : null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setContainer(E container) {
        I i;
        if (this.mContainer != null && container != null) {
            throw new IllegalArgumentException("Can't set container=" + container + " for controller=" + this + " Already set to=" + this.mContainer);
        }
        this.mContainer = container;
        if (this.mContainer != null && (i = this.mListener) != null) {
            i.registerConfigurationChangeListener(this);
        }
    }

    void removeContainer() {
        E e = this.mContainer;
        if (e == null) {
            return;
        }
        e.setController(null);
        this.mContainer = null;
        I i = this.mListener;
        if (i != null) {
            i.unregisterConfigurationChangeListener(this);
        }
    }

    @Override // com.android.server.wm.ConfigurationContainerListener
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        synchronized (this.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                this.mContainer.onRequestedOverrideConfigurationChanged(overrideConfiguration);
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }
}
