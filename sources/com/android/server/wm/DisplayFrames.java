package com.android.server.wm;

import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import com.android.server.wm.utils.WmDisplayCutout;
import java.io.PrintWriter;
/* loaded from: classes.dex */
public class DisplayFrames {
    public int mDisplayHeight;
    public final int mDisplayId;
    public int mDisplayWidth;
    public int mRotation;
    public final Rect mOverscan = new Rect();
    public final Rect mUnrestricted = new Rect();
    public final Rect mRestrictedOverscan = new Rect();
    public final Rect mRestricted = new Rect();
    public final Rect mSystem = new Rect();
    public final Rect mStable = new Rect();
    public final Rect mStableFullscreen = new Rect();
    public final Rect mCurrent = new Rect();
    public final Rect mContent = new Rect();
    public final Rect mVoiceContent = new Rect();
    public final Rect mDock = new Rect();
    public WmDisplayCutout mDisplayCutout = WmDisplayCutout.NO_CUTOUT;
    public WmDisplayCutout mDisplayInfoCutout = WmDisplayCutout.NO_CUTOUT;
    public final Rect mDisplayCutoutSafe = new Rect();
    private final Rect mDisplayInfoOverscan = new Rect();
    private final Rect mRotatedDisplayInfoOverscan = new Rect();

    public DisplayFrames(int displayId, DisplayInfo info, WmDisplayCutout displayCutout) {
        this.mDisplayId = displayId;
        onDisplayInfoUpdated(info, displayCutout);
    }

    public void onDisplayInfoUpdated(DisplayInfo info, WmDisplayCutout displayCutout) {
        this.mDisplayWidth = info.logicalWidth;
        this.mDisplayHeight = info.logicalHeight;
        this.mRotation = info.rotation;
        this.mDisplayInfoOverscan.set(info.overscanLeft, info.overscanTop, info.overscanRight, info.overscanBottom);
        this.mDisplayInfoCutout = displayCutout != null ? displayCutout : WmDisplayCutout.NO_CUTOUT;
    }

    public void onBeginLayout() {
        switch (this.mRotation) {
            case 1:
                this.mRotatedDisplayInfoOverscan.left = this.mDisplayInfoOverscan.top;
                this.mRotatedDisplayInfoOverscan.top = this.mDisplayInfoOverscan.right;
                this.mRotatedDisplayInfoOverscan.right = this.mDisplayInfoOverscan.bottom;
                this.mRotatedDisplayInfoOverscan.bottom = this.mDisplayInfoOverscan.left;
                break;
            case 2:
                this.mRotatedDisplayInfoOverscan.left = this.mDisplayInfoOverscan.right;
                this.mRotatedDisplayInfoOverscan.top = this.mDisplayInfoOverscan.bottom;
                this.mRotatedDisplayInfoOverscan.right = this.mDisplayInfoOverscan.left;
                this.mRotatedDisplayInfoOverscan.bottom = this.mDisplayInfoOverscan.top;
                break;
            case 3:
                this.mRotatedDisplayInfoOverscan.left = this.mDisplayInfoOverscan.bottom;
                this.mRotatedDisplayInfoOverscan.top = this.mDisplayInfoOverscan.left;
                this.mRotatedDisplayInfoOverscan.right = this.mDisplayInfoOverscan.top;
                this.mRotatedDisplayInfoOverscan.bottom = this.mDisplayInfoOverscan.right;
                break;
            default:
                this.mRotatedDisplayInfoOverscan.set(this.mDisplayInfoOverscan);
                break;
        }
        this.mRestrictedOverscan.set(0, 0, this.mDisplayWidth, this.mDisplayHeight);
        this.mOverscan.set(this.mRestrictedOverscan);
        this.mSystem.set(this.mRestrictedOverscan);
        this.mUnrestricted.set(this.mRotatedDisplayInfoOverscan);
        this.mUnrestricted.right = this.mDisplayWidth - this.mUnrestricted.right;
        this.mUnrestricted.bottom = this.mDisplayHeight - this.mUnrestricted.bottom;
        this.mRestricted.set(this.mUnrestricted);
        this.mDock.set(this.mUnrestricted);
        this.mContent.set(this.mUnrestricted);
        this.mVoiceContent.set(this.mUnrestricted);
        this.mStable.set(this.mUnrestricted);
        this.mStableFullscreen.set(this.mUnrestricted);
        this.mCurrent.set(this.mUnrestricted);
        this.mDisplayCutout = this.mDisplayInfoCutout;
        this.mDisplayCutoutSafe.set(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (!this.mDisplayCutout.getDisplayCutout().isEmpty()) {
            DisplayCutout c = this.mDisplayCutout.getDisplayCutout();
            if (c.getSafeInsetLeft() > 0) {
                this.mDisplayCutoutSafe.left = this.mRestrictedOverscan.left + c.getSafeInsetLeft();
            }
            if (c.getSafeInsetTop() > 0) {
                this.mDisplayCutoutSafe.top = this.mRestrictedOverscan.top + c.getSafeInsetTop();
            }
            if (c.getSafeInsetRight() > 0) {
                this.mDisplayCutoutSafe.right = this.mRestrictedOverscan.right - c.getSafeInsetRight();
            }
            if (c.getSafeInsetBottom() > 0) {
                this.mDisplayCutoutSafe.bottom = this.mRestrictedOverscan.bottom - c.getSafeInsetBottom();
            }
        }
    }

    public int getInputMethodWindowVisibleHeight() {
        return this.mDock.bottom - this.mCurrent.bottom;
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        this.mStable.writeToProto(proto, 1146756268033L);
        proto.end(token);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayFrames w=" + this.mDisplayWidth + " h=" + this.mDisplayHeight + " r=" + this.mRotation);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        sb.append("  ");
        String myPrefix = sb.toString();
        dumpFrame(this.mStable, "mStable", myPrefix, pw);
        dumpFrame(this.mStableFullscreen, "mStableFullscreen", myPrefix, pw);
        dumpFrame(this.mDock, "mDock", myPrefix, pw);
        dumpFrame(this.mCurrent, "mCurrent", myPrefix, pw);
        dumpFrame(this.mSystem, "mSystem", myPrefix, pw);
        dumpFrame(this.mContent, "mContent", myPrefix, pw);
        dumpFrame(this.mVoiceContent, "mVoiceContent", myPrefix, pw);
        dumpFrame(this.mOverscan, "mOverscan", myPrefix, pw);
        dumpFrame(this.mRestrictedOverscan, "mRestrictedOverscan", myPrefix, pw);
        dumpFrame(this.mRestricted, "mRestricted", myPrefix, pw);
        dumpFrame(this.mUnrestricted, "mUnrestricted", myPrefix, pw);
        dumpFrame(this.mDisplayInfoOverscan, "mDisplayInfoOverscan", myPrefix, pw);
        dumpFrame(this.mRotatedDisplayInfoOverscan, "mRotatedDisplayInfoOverscan", myPrefix, pw);
        pw.println(myPrefix + "mDisplayCutout=" + this.mDisplayCutout);
    }

    private void dumpFrame(Rect frame, String name, String prefix, PrintWriter pw) {
        pw.print(prefix + name + "=");
        frame.printShortString(pw);
        pw.println();
    }
}
