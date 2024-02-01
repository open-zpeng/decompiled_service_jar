package com.android.server.wm;

import android.util.ArrayMap;
import android.util.Slog;
import java.io.PrintWriter;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class UnknownAppVisibilityController {
    private static final String TAG = "WindowManager";
    private static final int UNKNOWN_STATE_WAITING_RELAYOUT = 2;
    private static final int UNKNOWN_STATE_WAITING_RESUME = 1;
    private static final int UNKNOWN_STATE_WAITING_VISIBILITY_UPDATE = 3;
    private final DisplayContent mDisplayContent;
    private final WindowManagerService mService;
    private final ArrayMap<AppWindowToken, Integer> mUnknownApps = new ArrayMap<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public UnknownAppVisibilityController(WindowManagerService service, DisplayContent displayContent) {
        this.mService = service;
        this.mDisplayContent = displayContent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean allResolved() {
        return this.mUnknownApps.isEmpty();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clear() {
        this.mUnknownApps.clear();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getDebugMessage() {
        StringBuilder builder = new StringBuilder();
        for (int i = this.mUnknownApps.size() - 1; i >= 0; i--) {
            builder.append("app=");
            builder.append(this.mUnknownApps.keyAt(i));
            builder.append(" state=");
            builder.append(this.mUnknownApps.valueAt(i));
            if (i != 0) {
                builder.append(' ');
            }
        }
        return builder.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void appRemovedOrHidden(AppWindowToken appWindow) {
        if (WindowManagerDebugConfig.DEBUG_UNKNOWN_APP_VISIBILITY) {
            Slog.d(TAG, "App removed or hidden appWindow=" + appWindow);
        }
        this.mUnknownApps.remove(appWindow);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyLaunched(AppWindowToken appWindow) {
        if (WindowManagerDebugConfig.DEBUG_UNKNOWN_APP_VISIBILITY) {
            Slog.d(TAG, "App launched appWindow=" + appWindow);
        }
        this.mUnknownApps.put(appWindow, 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyAppResumedFinished(AppWindowToken appWindow) {
        if (this.mUnknownApps.containsKey(appWindow) && this.mUnknownApps.get(appWindow).intValue() == 1) {
            if (WindowManagerDebugConfig.DEBUG_UNKNOWN_APP_VISIBILITY) {
                Slog.d(TAG, "App resume finished appWindow=" + appWindow);
            }
            this.mUnknownApps.put(appWindow, 2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyRelayouted(AppWindowToken appWindow) {
        if (!this.mUnknownApps.containsKey(appWindow)) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_UNKNOWN_APP_VISIBILITY) {
            Slog.d(TAG, "App relayouted appWindow=" + appWindow);
        }
        int state = this.mUnknownApps.get(appWindow).intValue();
        if (state == 2) {
            this.mUnknownApps.put(appWindow, 3);
            this.mService.notifyKeyguardFlagsChanged(new Runnable() { // from class: com.android.server.wm.-$$Lambda$UnknownAppVisibilityController$FYhcjOhYWVp6HX5hr3GGaPg67Gc
                @Override // java.lang.Runnable
                public final void run() {
                    UnknownAppVisibilityController.this.notifyVisibilitiesUpdated();
                }
            }, appWindow.getDisplayContent().getDisplayId());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyVisibilitiesUpdated() {
        if (WindowManagerDebugConfig.DEBUG_UNKNOWN_APP_VISIBILITY) {
            Slog.d(TAG, "Visibility updated DONE");
        }
        boolean changed = false;
        for (int i = this.mUnknownApps.size() - 1; i >= 0; i--) {
            if (this.mUnknownApps.valueAt(i).intValue() == 3) {
                this.mUnknownApps.removeAt(i);
                changed = true;
            }
        }
        if (changed) {
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        if (this.mUnknownApps.isEmpty()) {
            return;
        }
        pw.println(prefix + "Unknown visibilities:");
        for (int i = this.mUnknownApps.size() + (-1); i >= 0; i += -1) {
            pw.println(prefix + "  app=" + this.mUnknownApps.keyAt(i) + " state=" + this.mUnknownApps.valueAt(i));
        }
    }
}
