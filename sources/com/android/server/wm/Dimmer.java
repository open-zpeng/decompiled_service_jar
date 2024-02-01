package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.Dimmer;
import com.android.server.wm.LocalAnimationAdapter;
import com.android.server.wm.SurfaceAnimator;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class Dimmer {
    private static final int DEFAULT_DIM_ANIM_DURATION = 200;
    private static final float[] DIM_COLOR_DAY = {0.31f, 0.35f, 0.41f};
    private static final float[] DIM_COLOR_NIGHT = {0.0f, 0.0f, 0.0f};
    private static final String TAG = "WindowManager";
    @VisibleForTesting
    DimState mDimState;
    private WindowContainer mHost;
    private WindowContainer mLastRequestedDimContainer;
    boolean mNightMode;
    private final SurfaceAnimatorStarter mSurfaceAnimatorStarter;

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes2.dex */
    public interface SurfaceAnimatorStarter {
        void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction transaction, AnimationAdapter animationAdapter, boolean z);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class DimAnimatable implements SurfaceAnimator.Animatable {
        private SurfaceControl mDimLayer;

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
        public void onAnimationLeashLost(SurfaceControl.Transaction t) {
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

        void removeSurface() {
            SurfaceControl surfaceControl = this.mDimLayer;
            if (surfaceControl != null && surfaceControl.isValid()) {
                getPendingTransaction().remove(this.mDimLayer);
            }
            this.mDimLayer = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes2.dex */
    public class DimState {
        boolean isVisible;
        SurfaceControl mDimLayer;
        DimmerInfo mDimmerInfo;
        boolean mDontReset;
        SurfaceAnimator mSurfaceAnimator;
        boolean mAnimateExit = true;
        boolean mDimming = true;

        DimState(SurfaceControl dimLayer) {
            this.mDimLayer = dimLayer;
            final DimAnimatable dimAnimatable = new DimAnimatable(dimLayer);
            this.mSurfaceAnimator = new SurfaceAnimator(dimAnimatable, new Runnable() { // from class: com.android.server.wm.-$$Lambda$Dimmer$DimState$QYvwJex5H10MFMe0LEzEUs1b2G0
                @Override // java.lang.Runnable
                public final void run() {
                    Dimmer.DimState.this.lambda$new$0$Dimmer$DimState(dimAnimatable);
                }
            }, Dimmer.this.mHost.mWmService);
            xpWindowManager.setRoundCorner(this.mDimLayer);
        }

        public /* synthetic */ void lambda$new$0$Dimmer$DimState(DimAnimatable dimAnimatable) {
            if (!this.mDimming) {
                dimAnimatable.removeSurface();
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
        SurfaceControl.Builder colorLayer = this.mHost.makeChildSurface(null).setParent(this.mHost.getSurfaceControl()).setColorLayer();
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
            d.mDimmerInfo = DimmerInfo.create(container, this.mHost);
            float[] colors = isNight() ? DIM_COLOR_NIGHT : DIM_COLOR_DAY;
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
        DimState dimState = this.mDimState;
        if (dimState != null) {
            t.hide(dimState.mDimLayer);
            DimState dimState2 = this.mDimState;
            dimState2.isVisible = false;
            dimState2.mDontReset = false;
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
        DimState dimState = this.mDimState;
        if (dimState != null && !dimState.mDontReset) {
            this.mDimState.mDimming = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dontAnimateExit() {
        DimState dimState = this.mDimState;
        if (dimState != null) {
            dimState.mAnimateExit = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean updateDims(SurfaceControl.Transaction t, Rect bounds) {
        DimState dimState = this.mDimState;
        if (dimState == null) {
            return false;
        }
        if (dimState.mDimmerInfo != null) {
            bounds = this.mDimState.mDimmerInfo.getDimmerBounds(bounds);
        }
        if (!this.mDimState.mDimming) {
            if (!this.mDimState.mAnimateExit) {
                if (this.mDimState.mDimLayer.isValid()) {
                    t.remove(this.mDimState.mDimLayer);
                }
            } else {
                startDimExit(this.mLastRequestedDimContainer, this.mDimState.mSurfaceAnimator, t);
            }
            this.mDimState = null;
            return false;
        } else if (isDimmerValid(this.mDimState.mDimmerInfo)) {
            t.setPosition(this.mDimState.mDimLayer, bounds.left, bounds.top);
            t.setWindowCrop(this.mDimState.mDimLayer, bounds.width(), bounds.height());
            boolean isNight = isNight();
            boolean isNightChanged = isNight != this.mNightMode;
            if (isNightChanged) {
                float[] colors = isNight() ? DIM_COLOR_NIGHT : DIM_COLOR_DAY;
                t.setColor(this.mDimState.mDimLayer, colors);
            }
            this.mNightMode = isNight;
            if (!this.mDimState.isVisible) {
                DimState dimState2 = this.mDimState;
                dimState2.isVisible = true;
                t.show(dimState2.mDimLayer);
                startDimEnter(this.mLastRequestedDimContainer, this.mDimState.mSurfaceAnimator, t);
            }
            return true;
        } else {
            return false;
        }
    }

    private void startDimEnter(WindowContainer container, SurfaceAnimator animator, SurfaceControl.Transaction t) {
        startAnim(container, animator, t, 0.0f, 1.0f);
    }

    private void startDimExit(WindowContainer container, SurfaceAnimator animator, SurfaceControl.Transaction t) {
        startAnim(container, animator, t, 1.0f, 0.0f);
    }

    private void startAnim(WindowContainer container, SurfaceAnimator animator, SurfaceControl.Transaction t, float startAlpha, float endAlpha) {
        this.mSurfaceAnimatorStarter.startAnimation(animator, t, new LocalAnimationAdapter(new AlphaAnimationSpec(startAlpha, endAlpha, getDimDuration(container)), this.mHost.mWmService.mSurfaceAnimationRunner), false);
    }

    private long getDimDuration(WindowContainer container) {
        if (container == null) {
            return 0L;
        }
        AnimationAdapter animationAdapter = container.mSurfaceAnimator.getAnimation();
        if (animationAdapter == null) {
            return 200L;
        }
        return animationAdapter.getDurationHint();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
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
            float duration = ((float) currentPlayTime) / ((float) getDuration());
            float f = this.mToAlpha;
            float f2 = this.mFromAlpha;
            float alpha = (duration * (f - f2)) + f2;
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

    private boolean isNight() {
        WindowContainer windowContainer = this.mHost;
        Configuration configuration = windowContainer != null ? windowContainer.getConfiguration() : null;
        if (configuration == null) {
            return false;
        }
        boolean isNight = (configuration.uiMode & 48) == 32;
        return isNight;
    }

    private static boolean isDimmerValid(DimmerInfo info) {
        if (info != null && SharedDisplayManager.enable() && SharedDisplayManager.isDefaultDisplay(info.displayId) && info.frameBounds != null && info.logicalWidth > 0 && info.logicalHeight > 0) {
            int screenId = SharedDisplayManager.findScreenId(info.frameBounds, info.logicalWidth, info.logicalHeight);
            if (screenId != info.screenId) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class DimmerInfo {
        public int screenId = 0;
        public int displayId = 0;
        public int logicalWidth = -1;
        public int logicalHeight = -1;
        public Rect taskBounds = null;
        public Rect frameBounds = null;
        public boolean fullscreen = false;
        public xpWindowManager.WindowInfo windowInfo = null;

        private DimmerInfo() {
        }

        public static DimmerInfo create(WindowContainer container, WindowContainer host) {
            if (container == null || host == null) {
                return null;
            }
            try {
                DimmerInfo info = new DimmerInfo();
                if (container instanceof WindowState) {
                    WindowState win = (WindowState) container;
                    AppWindowToken token = win.mAppToken;
                    WindowState parent = token != null ? token.findMainWindow() : null;
                    int systemUiVisibility = win.mAttrs.systemUiVisibility | win.mAttrs.subtreeSystemUiVisibility;
                    info.windowInfo = xpWindowManager.getWindowInfo(win.mAttrs, parent != null ? parent.mAttrs : null);
                    info.fullscreen = xpWindowManager.isFullscreen(systemUiVisibility, win.mAttrs.flags, win.mAttrs.xpFlags);
                    info.screenId = SharedDisplayManager.findScreenId(win.mAttrs.sharedId);
                    info.displayId = win.getDisplayId();
                    info.frameBounds = win.getFrameLw();
                    DisplayInfo displayInfo = win.getDisplayInfo();
                    if (displayInfo != null) {
                        info.logicalWidth = displayInfo.logicalWidth;
                        info.logicalHeight = displayInfo.logicalHeight;
                    }
                }
                info.taskBounds = host.getBounds();
                return info;
            } catch (Exception e) {
                return null;
            }
        }

        public Rect getDimmerBounds(Rect bounds) {
            Rect rect = new Rect(bounds);
            xpWindowManager.WindowInfo windowInfo = this.windowInfo;
            if (windowInfo != null && windowInfo.dimmerBounds != null) {
                rect.set(this.windowInfo.dimmerBounds);
                int windowType = this.windowInfo.windowType;
                if ((windowType == 10 || windowType == 12) && xpWindowManager.rectValid(this.taskBounds)) {
                    int dx = -this.taskBounds.left;
                    int dy = -this.taskBounds.top;
                    rect.offset(dx, dy);
                }
            }
            return rect;
        }
    }
}
