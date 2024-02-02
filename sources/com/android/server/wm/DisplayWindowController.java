package com.android.server.wm;

import android.content.res.Configuration;
import android.os.Binder;
import android.util.Slog;
import android.view.Display;
/* loaded from: classes.dex */
public class DisplayWindowController extends WindowContainerController<DisplayContent, WindowContainerListener> {
    private final int mDisplayId;

    public DisplayWindowController(Display display, WindowContainerListener listener) {
        super(listener, WindowManagerService.getInstance());
        this.mDisplayId = display.getDisplayId();
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                long callingIdentity = Binder.clearCallingIdentity();
                this.mRoot.createDisplayContent(display, this);
                Binder.restoreCallingIdentity(callingIdentity);
                if (this.mContainer == 0) {
                    throw new IllegalArgumentException("Trying to add display=" + display + " dc=" + this.mRoot.getDisplayContent(this.mDisplayId));
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    @Override // com.android.server.wm.WindowContainerController
    public void removeContainer() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_DISPLAY) {
                        Slog.i("WindowManager", "removeDisplay: could not find displayId=" + this.mDisplayId);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                ((DisplayContent) this.mContainer).removeIfPossible();
                super.removeContainer();
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    @Override // com.android.server.wm.WindowContainerController, com.android.server.wm.ConfigurationContainerListener
    public void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
    }

    public void positionChildAt(StackWindowController child, int position) {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (WindowManagerDebugConfig.DEBUG_STACK) {
                    Slog.i("WindowManager", "positionTaskStackAt: positioning stack=" + child + " at " + position);
                }
                if (this.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_STACK) {
                        Slog.i("WindowManager", "positionTaskStackAt: could not find display=" + this.mContainer);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else if (child.mContainer == 0) {
                    if (WindowManagerDebugConfig.DEBUG_STACK) {
                        Slog.i("WindowManager", "positionTaskStackAt: could not find stack=" + this);
                    }
                    WindowManagerService.resetPriorityAfterLockedSection();
                } else {
                    ((DisplayContent) this.mContainer).positionStackAt(position, (TaskStack) child.mContainer);
                    WindowManagerService.resetPriorityAfterLockedSection();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void deferUpdateImeTarget() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(this.mDisplayId);
                if (dc != null) {
                    dc.deferUpdateImeTarget();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void continueUpdateImeTarget() {
        synchronized (this.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                DisplayContent dc = this.mRoot.getDisplayContent(this.mDisplayId);
                if (dc != null) {
                    dc.continueUpdateImeTarget();
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public String toString() {
        return "{DisplayWindowController displayId=" + this.mDisplayId + "}";
    }
}
