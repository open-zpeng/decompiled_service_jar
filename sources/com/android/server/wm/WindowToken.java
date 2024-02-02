package com.android.server.wm;

import android.os.Debug;
import android.os.IBinder;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import java.io.PrintWriter;
import java.util.Comparator;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WindowToken extends WindowContainer<WindowState> {
    private static final String TAG = "WindowManager";
    boolean hasVisible;
    protected DisplayContent mDisplayContent;
    private boolean mHidden;
    final boolean mOwnerCanManageAppTokens;
    boolean mPersistOnEmpty;
    final boolean mRoundedCornerOverlay;
    private final Comparator<WindowState> mWindowComparator;
    boolean paused;
    boolean sendingToBottom;
    String stringName;
    final IBinder token;
    boolean waitingToShow;
    final int windowType;

    public static /* synthetic */ int lambda$new$0(WindowToken token, WindowState newWindow, WindowState existingWindow) {
        if (newWindow.mToken == token) {
            if (existingWindow.mToken == token) {
                return token.isFirstChildWindowGreaterThanSecond(newWindow, existingWindow) ? 1 : -1;
            }
            throw new IllegalArgumentException("existingWindow=" + existingWindow + " is not a child of token=" + token);
        }
        throw new IllegalArgumentException("newWindow=" + newWindow + " is not a child of token=" + token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowToken(WindowManagerService service, IBinder _token, int type, boolean persistOnEmpty, DisplayContent dc, boolean ownerCanManageAppTokens) {
        this(service, _token, type, persistOnEmpty, dc, ownerCanManageAppTokens, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowToken(WindowManagerService service, IBinder _token, int type, boolean persistOnEmpty, DisplayContent dc, boolean ownerCanManageAppTokens, boolean roundedCornerOverlay) {
        super(service);
        this.paused = false;
        this.mWindowComparator = new Comparator() { // from class: com.android.server.wm.-$$Lambda$WindowToken$tFLHn4S6WuSXW1gp1kvT_sp7WC0
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return WindowToken.lambda$new$0(WindowToken.this, (WindowState) obj, (WindowState) obj2);
            }
        };
        this.token = _token;
        this.windowType = type;
        this.mPersistOnEmpty = persistOnEmpty;
        this.mOwnerCanManageAppTokens = ownerCanManageAppTokens;
        this.mRoundedCornerOverlay = roundedCornerOverlay;
        onDisplayChanged(dc);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHidden(boolean hidden) {
        if (hidden != this.mHidden) {
            this.mHidden = hidden;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isHidden() {
        return this.mHidden;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAllWindowsIfPossible() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState win = (WindowState) this.mChildren.get(i);
            if (WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT) {
                Slog.w(TAG, "removeAllWindowsIfPossible: removing win=" + win);
            }
            win.removeIfPossible();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setExiting() {
        if (this.mChildren.size() == 0) {
            super.removeImmediately();
            return;
        }
        this.mPersistOnEmpty = false;
        if (this.mHidden) {
            return;
        }
        int count = this.mChildren.size();
        boolean delayed = false;
        boolean delayed2 = false;
        for (int i = 0; i < count; i++) {
            WindowState win = (WindowState) this.mChildren.get(i);
            if (win.mWinAnimator.isAnimationSet()) {
                delayed = true;
            }
            delayed2 |= win.onSetAppExiting();
        }
        setHidden(true);
        if (delayed2) {
            this.mService.mWindowPlacerLocked.performSurfacePlacement();
            this.mService.updateFocusedWindowLocked(0, false);
        }
        if (delayed) {
            this.mDisplayContent.mExitingTokens.add(this);
        }
    }

    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow, WindowState existingWindow) {
        return newWindow.mBaseLayer >= existingWindow.mBaseLayer;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addWindow(WindowState win) {
        if (WindowManagerDebugConfig.DEBUG_FOCUS) {
            Slog.d(TAG, "addWindow: win=" + win + " Callers=" + Debug.getCallers(5));
        }
        if (!win.isChildWindow() && !this.mChildren.contains(win)) {
            if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                Slog.v(TAG, "Adding " + win + " to " + this);
            }
            addChild((WindowToken) win, (Comparator<WindowToken>) this.mWindowComparator);
            this.mService.mWindowsChanged = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isEmpty() {
        return this.mChildren.isEmpty();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getReplacingWindow() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState win = (WindowState) this.mChildren.get(i);
            WindowState replacing = win.getReplacingWindow();
            if (replacing != null) {
                return replacing;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean windowsCanBeWallpaperTarget() {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            WindowState w = (WindowState) this.mChildren.get(j);
            if ((w.mAttrs.flags & 1048576) != 0) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getHighestAnimLayer() {
        int highest = -1;
        for (int j = 0; j < this.mChildren.size(); j++) {
            WindowState w = (WindowState) this.mChildren.get(j);
            int wLayer = w.getHighestAnimLayer();
            if (wLayer > highest) {
                highest = wLayer;
            }
        }
        return highest;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppWindowToken asAppWindowToken() {
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent getDisplayContent() {
        return this.mDisplayContent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeImmediately() {
        if (this.mDisplayContent != null) {
            this.mDisplayContent.removeWindowToken(this.token);
        }
        super.removeImmediately();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onDisplayChanged(DisplayContent dc) {
        dc.reParentWindowToken(this);
        this.mDisplayContent = dc;
        super.onDisplayChanged(dc);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, trim);
        proto.write(1120986464258L, System.identityHashCode(this));
        for (int i = 0; i < this.mChildren.size(); i++) {
            WindowState w = (WindowState) this.mChildren.get(i);
            w.writeToProto(proto, 2246267895811L, trim);
        }
        proto.write(1133871366148L, this.mHidden);
        proto.write(1133871366149L, this.waitingToShow);
        proto.write(1133871366150L, this.paused);
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix);
        pw.print("windows=");
        pw.println(this.mChildren);
        pw.print(prefix);
        pw.print("windowType=");
        pw.print(this.windowType);
        pw.print(" hidden=");
        pw.print(this.mHidden);
        pw.print(" hasVisible=");
        pw.println(this.hasVisible);
        if (this.waitingToShow || this.sendingToBottom) {
            pw.print(prefix);
            pw.print("waitingToShow=");
            pw.print(this.waitingToShow);
            pw.print(" sendingToBottom=");
            pw.print(this.sendingToBottom);
        }
    }

    public String toString() {
        if (this.stringName == null) {
            this.stringName = "WindowToken{" + Integer.toHexString(System.identityHashCode(this)) + " " + this.token + '}';
        }
        return this.stringName;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public String getName() {
        return toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean okToDisplay() {
        return this.mDisplayContent != null && this.mDisplayContent.okToDisplay();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean okToAnimate() {
        return this.mDisplayContent != null && this.mDisplayContent.okToAnimate();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canLayerAboveSystemBars() {
        int layer = this.mService.mPolicy.getWindowLayerFromTypeLw(this.windowType, this.mOwnerCanManageAppTokens);
        int navLayer = this.mService.mPolicy.getWindowLayerFromTypeLw(2019, this.mOwnerCanManageAppTokens);
        return this.mOwnerCanManageAppTokens && layer > navLayer;
    }
}
