package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;
import android.view.SurfaceControl;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayTransformManager;
import com.android.server.wm.Dimmer;
import com.android.server.wm.LocalAnimationAdapter;
import com.android.server.wm.SurfaceAnimator;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class Dimmer {
    private static final boolean DEBUG = false;
    private static final int DEFAULT_DIM_ANIM_DURATION = SystemProperties.getInt("persist.dimmer.duration", (int) DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);
    private static final String TAG = "WindowManager";
    @VisibleForTesting
    DimState mDimState;
    private WindowContainer mHost;
    private WindowContainer mLastRequestedDimContainer;
    boolean mNightMode;
    private final SurfaceAnimatorStarter mSurfaceAnimatorStarter;

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface SurfaceAnimatorStarter {
        void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction transaction, AnimationAdapter animationAdapter, boolean z);
    }

    /* loaded from: classes.dex */
    private class DimAnimatable implements SurfaceAnimator.Animatable {
        private final SurfaceControl mDimLayer;

        private DimAnimatable(SurfaceControl dimLayer) {
            this.mDimLayer = dimLayer;
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public SurfaceControl.Transaction getPendingTransaction() {
            return Dimmer.this.mHost.getPendingTransaction();
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public void commitPendingTransaction() {
            Dimmer.this.mHost.commitPendingTransaction();
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public void onAnimationLeashDestroyed(SurfaceControl.Transaction t) {
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public SurfaceControl.Builder makeAnimationLeash() {
            return Dimmer.this.mHost.makeAnimationLeash();
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public SurfaceControl getAnimationLeashParent() {
            return Dimmer.this.mHost.getSurfaceControl();
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public SurfaceControl getSurfaceControl() {
            return this.mDimLayer;
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public SurfaceControl getParentSurfaceControl() {
            return Dimmer.this.mHost.getSurfaceControl();
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public int getSurfaceWidth() {
            return Dimmer.this.mHost.getSurfaceWidth();
        }

        @Override // com.android.server.wm.SurfaceAnimator.Animatable
        public int getSurfaceHeight() {
            return Dimmer.this.mHost.getSurfaceHeight();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public class DimState {
        boolean isVisible;
        SurfaceControl mDimLayer;
        boolean mDontReset;
        SurfaceAnimator mSurfaceAnimator;
        xpWindowManager.WindowInfo mWindowInfo;
        boolean mAnimateExit = true;
        boolean mDimming = true;

        DimState(SurfaceControl dimLayer) {
            this.mDimLayer = dimLayer;
            this.mSurfaceAnimator = new SurfaceAnimator(new DimAnimatable(dimLayer), new Runnable() { // from class: com.android.server.wm.-$$Lambda$Dimmer$DimState$jMIg4fVfhKsf8fm7mIcffBmkFt8
                @Override // java.lang.Runnable
                public final void run() {
                    Dimmer.DimState.lambda$new$0(Dimmer.DimState.this);
                }
            }, Dimmer.this.mHost.mService);
        }

        public static /* synthetic */ void lambda$new$0(DimState dimState) {
            if (!dimState.mDimming) {
                dimState.mDimLayer.destroy();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Dimmer(WindowContainer host) {
        this(host, new SurfaceAnimatorStarter() { // from class: com.android.server.wm.-$$Lambda$yACUZqn1Ak-GL14-Nu3kHUSaLX0
            @Override // com.android.server.wm.Dimmer.SurfaceAnimatorStarter
            public final void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction transaction, AnimationAdapter animationAdapter, boolean z) {
                surfaceAnimator.startAnimation(transaction, animationAdapter, z);
            }
        });
    }

    Dimmer(WindowContainer host, SurfaceAnimatorStarter surfaceAnimatorStarter) {
        this.mHost = host;
        this.mSurfaceAnimatorStarter = surfaceAnimatorStarter;
    }

    private SurfaceControl makeDimLayer() {
        SurfaceControl.Builder colorLayer = this.mHost.makeChildSurface(null).setParent(this.mHost.getSurfaceControl()).setColorLayer(true);
        return colorLayer.setName("Dim Layer for - " + this.mHost.getName()).build();
    }

    private DimState getDimState(WindowContainer container) {
        if (this.mDimState == null) {
            try {
                SurfaceControl ctl = makeDimLayer();
                this.mDimState = new DimState(ctl);
                if (container == null) {
                    this.mDimState.mDontReset = true;
                }
            } catch (Surface.OutOfResourcesException e) {
                Log.w(TAG, "OutOfResourcesException creating dim surface");
            }
        }
        this.mLastRequestedDimContainer = container;
        return this.mDimState;
    }

    private void dim(SurfaceControl.Transaction t, WindowContainer container, int relativeLayer, float alpha) {
        DimState d = getDimState(container);
        if (d == null) {
            return;
        }
        if (container != null) {
            if (container instanceof WindowState) {
                d.mWindowInfo = getWindowInfo((WindowState) container);
                if (d.mWindowInfo != null && d.mWindowInfo.roundCorner != null && d.mWindowInfo.roundCorner.length == 3) {
                    t.setRoundCorner(d.mDimLayer, d.mWindowInfo.roundCorner[0], d.mWindowInfo.roundCorner[1], d.mWindowInfo.roundCorner[2]);
                }
            }
            float[] colors = isNight() ? new float[]{0.0f, 0.0f, 0.0f} : new float[]{0.47f, 0.51f, 0.57f};
            t.setColor(d.mDimLayer, colors);
            t.setRelativeLayer(d.mDimLayer, container.getSurfaceControl(), relativeLayer);
        } else {
            t.setLayer(d.mDimLayer, Integer.MAX_VALUE);
        }
        t.setAlpha(d.mDimLayer, alpha);
        d.mDimming = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopDim(SurfaceControl.Transaction t) {
        if (this.mDimState != null) {
            t.hide(this.mDimState.mDimLayer);
            this.mDimState.isVisible = false;
            this.mDimState.mDontReset = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dimAbove(SurfaceControl.Transaction t, float alpha) {
        dim(t, null, 1, alpha);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dimAbove(SurfaceControl.Transaction t, WindowContainer container, float alpha) {
        dim(t, container, 1, alpha);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dimBelow(SurfaceControl.Transaction t, WindowContainer container, float alpha) {
        dim(t, container, -1, alpha);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetDimStates() {
        if (this.mDimState != null && !this.mDimState.mDontReset) {
            this.mDimState.mDimming = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dontAnimateExit() {
        if (this.mDimState != null) {
            this.mDimState.mAnimateExit = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateDims(SurfaceControl.Transaction t, Rect bounds) {
        if (this.mDimState == null) {
            return false;
        }
        if (this.mDimState.mWindowInfo != null && this.mDimState.mWindowInfo.dimmerBounds != null) {
            bounds = this.mDimState.mWindowInfo.dimmerBounds;
        }
        if (!this.mDimState.mDimming) {
            if (!this.mDimState.mAnimateExit) {
                t.destroy(this.mDimState.mDimLayer);
            } else {
                startDimExit(this.mLastRequestedDimContainer, this.mDimState.mSurfaceAnimator, t);
            }
            this.mDimState = null;
            return false;
        }
        t.setSize(this.mDimState.mDimLayer, bounds.width(), bounds.height());
        t.setPosition(this.mDimState.mDimLayer, bounds.left, bounds.top);
        boolean isNight = isNight();
        boolean isNightChanged = isNight != this.mNightMode;
        if (isNightChanged) {
            float[] colors = isNight() ? new float[]{0.0f, 0.0f, 0.0f} : new float[]{0.47f, 0.51f, 0.57f};
            t.setColor(this.mDimState.mDimLayer, colors);
        }
        this.mNightMode = isNight;
        if (!this.mDimState.isVisible) {
            this.mDimState.isVisible = true;
            t.show(this.mDimState.mDimLayer);
            startDimEnter(this.mLastRequestedDimContainer, this.mDimState.mSurfaceAnimator, t);
        }
        return true;
    }

    private void startDimEnter(WindowContainer container, SurfaceAnimator animator, SurfaceControl.Transaction t) {
        startAnim(container, animator, t, 0.0f, 1.0f);
    }

    private void startDimExit(WindowContainer container, SurfaceAnimator animator, SurfaceControl.Transaction t) {
        startAnim(container, animator, t, 1.0f, 0.0f);
    }

    private void startAnim(WindowContainer container, SurfaceAnimator animator, SurfaceControl.Transaction t, float startAlpha, float endAlpha) {
        this.mSurfaceAnimatorStarter.startAnimation(animator, t, new LocalAnimationAdapter(new AlphaAnimationSpec(startAlpha, endAlpha, getDimDuration(container)), this.mHost.mService.mSurfaceAnimationRunner), false);
    }

    private long getDimDuration(WindowContainer container) {
        if (container == null) {
            return 0L;
        }
        AnimationAdapter animationAdapter = container.mSurfaceAnimator.getAnimation();
        return animationAdapter == null ? DEFAULT_DIM_ANIM_DURATION : animationAdapter.getDurationHint();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class AlphaAnimationSpec implements LocalAnimationAdapter.AnimationSpec {
        private final long mDuration;
        private final float mFromAlpha;
        private final float mToAlpha;

        AlphaAnimationSpec(float fromAlpha, float toAlpha, long duration) {
            this.mFromAlpha = fromAlpha;
            this.mToAlpha = toAlpha;
            this.mDuration = duration;
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public long getDuration() {
            return this.mDuration;
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public void apply(SurfaceControl.Transaction t, SurfaceControl sc, long currentPlayTime) {
            float alpha = ((((float) currentPlayTime) / ((float) getDuration())) * (this.mToAlpha - this.mFromAlpha)) + this.mFromAlpha;
            t.setAlpha(sc, alpha);
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.print("from=");
            pw.print(this.mFromAlpha);
            pw.print(" to=");
            pw.print(this.mToAlpha);
            pw.print(" duration=");
            pw.println(this.mDuration);
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public void writeToProtoInner(ProtoOutputStream proto) {
            long token = proto.start(1146756268035L);
            proto.write(1108101562369L, this.mFromAlpha);
            proto.write(1108101562370L, this.mToAlpha);
            proto.write(1112396529667L, this.mDuration);
            proto.end(token);
        }
    }

    private static xpWindowManager.WindowInfo getWindowInfo(WindowState state) {
        if (state != null) {
            try {
                AppWindowToken token = state.mAppToken;
                WindowState parent = token != null ? token.findMainWindow() : null;
                return xpWindowManager.getWindowInfo(state.mAttrs, parent != null ? parent.mAttrs : null);
            } catch (Exception e) {
            }
        }
        return null;
    }

    private boolean isNight() {
        Configuration configuration = this.mHost != null ? this.mHost.getConfiguration() : null;
        if (configuration == null) {
            return false;
        }
        boolean isNight = (configuration.uiMode & 48) == 32;
        return isNight;
    }
}
