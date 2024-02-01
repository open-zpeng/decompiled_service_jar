package com.android.server.wm;

import android.os.SystemClock;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import com.android.server.wm.SurfaceAnimator;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class LocalAnimationAdapter implements AnimationAdapter {
    private final SurfaceAnimationRunner mAnimator;
    private final AnimationSpec mSpec;

    /* JADX INFO: Access modifiers changed from: package-private */
    public LocalAnimationAdapter(AnimationSpec spec, SurfaceAnimationRunner animator) {
        this.mSpec = spec;
        this.mAnimator = animator;
    }

    @Override // com.android.server.wm.AnimationAdapter
    public boolean getDetachWallpaper() {
        return this.mSpec.getDetachWallpaper();
    }

    @Override // com.android.server.wm.AnimationAdapter
    public boolean getShowWallpaper() {
        return this.mSpec.getShowWallpaper();
    }

    @Override // com.android.server.wm.AnimationAdapter
    public int getBackgroundColor() {
        return this.mSpec.getBackgroundColor();
    }

    @Override // com.android.server.wm.AnimationAdapter
    public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t, final SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
        this.mAnimator.startAnimation(this.mSpec, animationLeash, t, new Runnable() { // from class: com.android.server.wm.-$$Lambda$LocalAnimationAdapter$X--EomqUvw4qy89IeeTFTH7aCMo
            @Override // java.lang.Runnable
            public final void run() {
                finishCallback.onAnimationFinished(LocalAnimationAdapter.this);
            }
        });
    }

    @Override // com.android.server.wm.AnimationAdapter
    public void onAnimationCancelled(SurfaceControl animationLeash) {
        this.mAnimator.onAnimationCancelled(animationLeash);
    }

    @Override // com.android.server.wm.AnimationAdapter
    public long getDurationHint() {
        return this.mSpec.getDuration();
    }

    @Override // com.android.server.wm.AnimationAdapter
    public long getStatusBarTransitionsStartTime() {
        return this.mSpec.calculateStatusBarTransitionStartTime();
    }

    @Override // com.android.server.wm.AnimationAdapter
    public void dump(PrintWriter pw, String prefix) {
        this.mSpec.dump(pw, prefix);
    }

    @Override // com.android.server.wm.AnimationAdapter
    public void writeToProto(ProtoOutputStream proto) {
        long token = proto.start(1146756268033L);
        this.mSpec.writeToProto(proto, 1146756268033L);
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface AnimationSpec {
        void apply(SurfaceControl.Transaction transaction, SurfaceControl surfaceControl, long j);

        void dump(PrintWriter printWriter, String str);

        long getDuration();

        void writeToProtoInner(ProtoOutputStream protoOutputStream);

        default boolean getDetachWallpaper() {
            return false;
        }

        default boolean getShowWallpaper() {
            return false;
        }

        default int getBackgroundColor() {
            return 0;
        }

        default long calculateStatusBarTransitionStartTime() {
            return SystemClock.uptimeMillis();
        }

        default boolean canSkipFirstFrame() {
            return false;
        }

        default boolean needsEarlyWakeup() {
            return false;
        }

        default void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            writeToProtoInner(proto);
            proto.end(token);
        }
    }
}
