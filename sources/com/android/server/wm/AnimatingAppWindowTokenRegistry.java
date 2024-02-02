package com.android.server.wm;

import android.util.ArrayMap;
import android.util.ArraySet;
import java.io.PrintWriter;
import java.util.ArrayList;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AnimatingAppWindowTokenRegistry {
    private boolean mEndingDeferredFinish;
    private ArraySet<AppWindowToken> mAnimatingTokens = new ArraySet<>();
    private ArrayMap<AppWindowToken, Runnable> mFinishedTokens = new ArrayMap<>();
    private ArrayList<Runnable> mTmpRunnableList = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyStarting(AppWindowToken token) {
        this.mAnimatingTokens.add(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyFinished(AppWindowToken token) {
        this.mAnimatingTokens.remove(token);
        this.mFinishedTokens.remove(token);
        if (this.mAnimatingTokens.isEmpty()) {
            endDeferringFinished();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean notifyAboutToFinish(AppWindowToken token, Runnable endDeferFinishCallback) {
        boolean removed = this.mAnimatingTokens.remove(token);
        if (!removed) {
            return false;
        }
        if (this.mAnimatingTokens.isEmpty()) {
            endDeferringFinished();
            return false;
        }
        this.mFinishedTokens.put(token, endDeferFinishCallback);
        return true;
    }

    private void endDeferringFinished() {
        if (this.mEndingDeferredFinish) {
            return;
        }
        try {
            this.mEndingDeferredFinish = true;
            for (int i = this.mFinishedTokens.size() - 1; i >= 0; i--) {
                this.mTmpRunnableList.add(this.mFinishedTokens.valueAt(i));
            }
            this.mFinishedTokens.clear();
            int i2 = this.mTmpRunnableList.size() - 1;
            while (true) {
                int i3 = i2;
                if (i3 >= 0) {
                    this.mTmpRunnableList.get(i3).run();
                    i2 = i3 - 1;
                } else {
                    this.mTmpRunnableList.clear();
                    return;
                }
            }
        } finally {
            this.mEndingDeferredFinish = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String header, String prefix) {
        if (!this.mAnimatingTokens.isEmpty() || !this.mFinishedTokens.isEmpty()) {
            pw.print(prefix);
            pw.println(header);
            String prefix2 = prefix + "  ";
            pw.print(prefix2);
            pw.print("mAnimatingTokens=");
            pw.println(this.mAnimatingTokens);
            pw.print(prefix2);
            pw.print("mFinishedTokens=");
            pw.println(this.mFinishedTokens);
        }
    }
}
