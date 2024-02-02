package com.android.server.wm;

import android.graphics.Matrix;
import android.view.DisplayInfo;
import com.android.server.wm.utils.CoordinateTransforms;
import java.io.PrintWriter;
import java.io.StringWriter;
/* loaded from: classes.dex */
public class ForcedSeamlessRotator {
    private final int mNewRotation;
    private final int mOldRotation;
    private final Matrix mTransform = new Matrix();
    private final float[] mFloat9 = new float[9];

    public ForcedSeamlessRotator(int oldRotation, int newRotation, DisplayInfo info) {
        this.mOldRotation = oldRotation;
        this.mNewRotation = newRotation;
        boolean z = true;
        if (info.rotation != 1 && info.rotation != 3) {
            z = false;
        }
        boolean flipped = z;
        int h = flipped ? info.logicalWidth : info.logicalHeight;
        int w = flipped ? info.logicalHeight : info.logicalWidth;
        Matrix tmp = new Matrix();
        CoordinateTransforms.transformLogicalToPhysicalCoordinates(oldRotation, w, h, this.mTransform);
        CoordinateTransforms.transformPhysicalToLogicalCoordinates(newRotation, w, h, tmp);
        this.mTransform.postConcat(tmp);
    }

    public void unrotate(WindowToken token) {
        token.getPendingTransaction().setMatrix(token.getSurfaceControl(), this.mTransform, this.mFloat9);
    }

    public int getOldRotation() {
        return this.mOldRotation;
    }

    public void finish(WindowToken token, WindowState win) {
        this.mTransform.reset();
        token.getPendingTransaction().setMatrix(token.mSurfaceControl, this.mTransform, this.mFloat9);
        if (win.mWinAnimator.mSurfaceController != null) {
            token.getPendingTransaction().deferTransactionUntil(token.mSurfaceControl, win.mWinAnimator.mSurfaceController.mSurfaceControl.getHandle(), win.getFrameNumber());
            win.getPendingTransaction().deferTransactionUntil(win.mSurfaceControl, win.mWinAnimator.mSurfaceController.mSurfaceControl.getHandle(), win.getFrameNumber());
        }
    }

    public void dump(PrintWriter pw) {
        pw.print("{old=");
        pw.print(this.mOldRotation);
        pw.print(", new=");
        pw.print(this.mNewRotation);
        pw.print("}");
    }

    public String toString() {
        StringWriter sw = new StringWriter();
        dump(new PrintWriter(sw));
        return "ForcedSeamlessRotator" + sw.toString();
    }
}
