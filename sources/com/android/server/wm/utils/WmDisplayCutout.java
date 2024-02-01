package com.android.server.wm.utils;

import android.graphics.Rect;
import android.util.Size;
import android.view.DisplayCutout;
import java.util.List;
import java.util.Objects;

/* loaded from: classes2.dex */
public class WmDisplayCutout {
    public static final WmDisplayCutout NO_CUTOUT = new WmDisplayCutout(DisplayCutout.NO_CUTOUT, null);
    private final Size mFrameSize;
    private final DisplayCutout mInner;

    public WmDisplayCutout(DisplayCutout inner, Size frameSize) {
        this.mInner = inner;
        this.mFrameSize = frameSize;
    }

    public static WmDisplayCutout computeSafeInsets(DisplayCutout inner, int displayWidth, int displayHeight) {
        if (inner == DisplayCutout.NO_CUTOUT || inner.isBoundsEmpty()) {
            return NO_CUTOUT;
        }
        Size displaySize = new Size(displayWidth, displayHeight);
        Rect safeInsets = computeSafeInsets(displaySize, inner);
        return new WmDisplayCutout(inner.replaceSafeInsets(safeInsets), displaySize);
    }

    public WmDisplayCutout inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        DisplayCutout newInner = this.mInner.inset(insetLeft, insetTop, insetRight, insetBottom);
        if (this.mInner == newInner) {
            return this;
        }
        Size size = this.mFrameSize;
        Size frame = size == null ? null : new Size((size.getWidth() - insetLeft) - insetRight, (this.mFrameSize.getHeight() - insetTop) - insetBottom);
        return new WmDisplayCutout(newInner, frame);
    }

    public WmDisplayCutout calculateRelativeTo(Rect frame) {
        Size size = this.mFrameSize;
        if (size == null) {
            return this;
        }
        int insetRight = size.getWidth() - frame.right;
        int insetBottom = this.mFrameSize.getHeight() - frame.bottom;
        if (frame.left == 0 && frame.top == 0 && insetRight == 0 && insetBottom == 0) {
            return this;
        }
        if (frame.left >= this.mInner.getSafeInsetLeft() && frame.top >= this.mInner.getSafeInsetTop() && insetRight >= this.mInner.getSafeInsetRight() && insetBottom >= this.mInner.getSafeInsetBottom()) {
            return NO_CUTOUT;
        }
        if (this.mInner.isEmpty()) {
            return this;
        }
        return inset(frame.left, frame.top, insetRight, insetBottom);
    }

    public WmDisplayCutout computeSafeInsets(int width, int height) {
        return computeSafeInsets(this.mInner, width, height);
    }

    private static Rect computeSafeInsets(Size displaySize, DisplayCutout cutout) {
        if (displaySize.getWidth() < displaySize.getHeight()) {
            List<Rect> boundingRects = cutout.replaceSafeInsets(new Rect(0, displaySize.getHeight() / 2, 0, displaySize.getHeight() / 2)).getBoundingRects();
            int topInset = findInsetForSide(displaySize, boundingRects, 48);
            int bottomInset = findInsetForSide(displaySize, boundingRects, 80);
            return new Rect(0, topInset, 0, bottomInset);
        } else if (displaySize.getWidth() > displaySize.getHeight()) {
            List<Rect> boundingRects2 = cutout.replaceSafeInsets(new Rect(displaySize.getWidth() / 2, 0, displaySize.getWidth() / 2, 0)).getBoundingRects();
            int leftInset = findInsetForSide(displaySize, boundingRects2, 3);
            int right = findInsetForSide(displaySize, boundingRects2, 5);
            return new Rect(leftInset, 0, right, 0);
        } else {
            throw new UnsupportedOperationException("not implemented: display=" + displaySize + " cutout=" + cutout);
        }
    }

    private static int findInsetForSide(Size display, List<Rect> boundingRects, int gravity) {
        int inset = 0;
        int size = boundingRects.size();
        for (int i = 0; i < size; i++) {
            Rect boundingRect = boundingRects.get(i);
            if (gravity != 3) {
                if (gravity != 5) {
                    if (gravity != 48) {
                        if (gravity == 80) {
                            if (boundingRect.bottom == display.getHeight()) {
                                inset = Math.max(inset, display.getHeight() - boundingRect.top);
                            }
                        } else {
                            throw new IllegalArgumentException("unknown gravity: " + gravity);
                        }
                    } else if (boundingRect.top == 0) {
                        inset = Math.max(inset, boundingRect.bottom);
                    }
                } else if (boundingRect.right == display.getWidth()) {
                    inset = Math.max(inset, display.getWidth() - boundingRect.left);
                }
            } else if (boundingRect.left == 0) {
                inset = Math.max(inset, boundingRect.right);
            }
        }
        return inset;
    }

    public DisplayCutout getDisplayCutout() {
        return this.mInner;
    }

    public boolean equals(Object o) {
        if (o instanceof WmDisplayCutout) {
            WmDisplayCutout that = (WmDisplayCutout) o;
            return Objects.equals(this.mInner, that.mInner) && Objects.equals(this.mFrameSize, that.mFrameSize);
        }
        return false;
    }

    public int hashCode() {
        return Objects.hash(this.mInner, this.mFrameSize);
    }

    public String toString() {
        return "WmDisplayCutout{" + this.mInner + ", mFrameSize=" + this.mFrameSize + '}';
    }
}
