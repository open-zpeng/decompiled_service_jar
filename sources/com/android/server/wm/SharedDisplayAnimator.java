package com.android.server.wm;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import com.android.server.wm.SharedDisplayAnimator;

/* loaded from: classes2.dex */
public class SharedDisplayAnimator {
    private static final float ATTR_ALPHA = Float.valueOf(SystemProperties.get("persist.sys.xp.task.position.alpha", "0.99")).floatValue();
    private static final float ATTR_SCALE_MIN = Float.valueOf(SystemProperties.get("persist.sys.xp.task.position.scale.min", "0.95")).floatValue();
    private static final float ATTR_SCALE_MAX = Float.valueOf(SystemProperties.get("persist.sys.xp.task.position.scale.max", "1.0526316")).floatValue();
    private static final long ATTR_DURATION_SCALE_IN = SystemProperties.getLong("persist.sys.xp.task.position.duration.scale.in", 90);
    private static final long ATTR_OFFSET_SCALE_IN = SystemProperties.getLong("persist.sys.xp.task.position.offset.scale.in", 0);
    private static final long ATTR_DURATION_ALPHA_IN = SystemProperties.getLong("persist.sys.xp.task.position.duration.alpha.in", 10);
    private static final long ATTR_OFFSET_ALPHA_IN = SystemProperties.getLong("persist.sys.xp.task.position.offset.alpha.in", 90);
    private static final long ATTR_DURATION_TRANSLATE = SystemProperties.getLong("persist.sys.xp.task.position.duration.translate", 250);
    private static final long ATTR_OFFSET_TRANSLATE = SystemProperties.getLong("persist.sys.xp.task.position.offset.translate", 50);
    private static final long ATTR_DURATION_SCALE_OUT = SystemProperties.getLong("persist.sys.xp.task.position.duration.scale.out", 90);
    private static final long ATTR_OFFSET_SCALE_OUT = SystemProperties.getLong("persist.sys.xp.task.position.offset.scale.out", 250);
    private static final long ATTR_DURATION_ALPHA_OUT = SystemProperties.getLong("persist.sys.xp.task.position.duration.alpha.out", 10);
    private static final long ATTR_OFFSET_ALPHA_OUT = SystemProperties.getLong("persist.sys.xp.task.position.offset.alpha.out", 340);

    public static long getAnimatorDuration() {
        long duration = 0 + ATTR_DURATION_ALPHA_OUT;
        return duration + ATTR_OFFSET_ALPHA_OUT;
    }

    private static void setDefaultAnimation(Animation animation) {
        if (animation != null) {
            animation.setRepeatCount(0);
            animation.setFillEnabled(true);
            animation.setFillBefore(false);
            animation.setFillAfter(true);
        }
    }

    public static void startAnimation(WindowState win, Rect from, Rect to) {
        if (win == null || from == null || to == null) {
            return;
        }
        try {
            AnimationSet animations = new AnimationSet(false);
            setDefaultAnimation(animations);
            ScaleAnimation scaleIn = new ScaleAnimation(1.0f, ATTR_SCALE_MIN, 1.0f, ATTR_SCALE_MIN, 1, 0.5f, 1, 0.5f);
            scaleIn.setInterpolator(new DecelerateInterpolator());
            scaleIn.setAnimationListener(new Animation.AnimationListener() { // from class: com.android.server.wm.SharedDisplayAnimator.1
                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationStart(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationEnd(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationRepeat(Animation animation) {
                }
            });
            scaleIn.setDuration(ATTR_DURATION_SCALE_IN);
            scaleIn.setStartOffset(ATTR_OFFSET_SCALE_IN);
            setDefaultAnimation(scaleIn);
            ScaleAnimation scaleOut = new ScaleAnimation(1.0f, ATTR_SCALE_MAX, 1.0f, ATTR_SCALE_MAX, 1, 0.5f, 1, 0.5f);
            scaleOut.setInterpolator(new DecelerateInterpolator());
            scaleOut.setAnimationListener(new Animation.AnimationListener() { // from class: com.android.server.wm.SharedDisplayAnimator.2
                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationStart(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationEnd(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationRepeat(Animation animation) {
                }
            });
            scaleOut.setDuration(ATTR_DURATION_SCALE_OUT);
            scaleOut.setStartOffset(ATTR_OFFSET_SCALE_OUT);
            setDefaultAnimation(scaleOut);
            float toXValue = to.left - from.left;
            TranslateAnimation translate = new TranslateAnimation(0, 0.0f, 0, toXValue, 0, 0.0f, 0, 0.0f);
            translate.setInterpolator(new DecelerateInterpolator());
            translate.setAnimationListener(new Animation.AnimationListener() { // from class: com.android.server.wm.SharedDisplayAnimator.3
                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationRepeat(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationStart(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationEnd(Animation animation) {
                }
            });
            translate.setDuration(ATTR_DURATION_TRANSLATE);
            translate.setStartOffset(ATTR_OFFSET_TRANSLATE);
            setDefaultAnimation(translate);
            AlphaAnimation alphaIn = new AlphaAnimation(1.0f, ATTR_ALPHA);
            alphaIn.setInterpolator(new AccelerateDecelerateInterpolator());
            alphaIn.setAnimationListener(new Animation.AnimationListener() { // from class: com.android.server.wm.SharedDisplayAnimator.4
                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationStart(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationEnd(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationRepeat(Animation animation) {
                }
            });
            alphaIn.setDuration(ATTR_DURATION_ALPHA_IN);
            alphaIn.setStartOffset(ATTR_OFFSET_ALPHA_IN);
            setDefaultAnimation(alphaIn);
            AlphaAnimation alphaOut = new AlphaAnimation(ATTR_ALPHA, 1.0f);
            alphaOut.setInterpolator(new AccelerateDecelerateInterpolator());
            alphaOut.setAnimationListener(new AnonymousClass5(win));
            alphaOut.setDuration(ATTR_DURATION_ALPHA_OUT);
            alphaOut.setStartOffset(ATTR_OFFSET_ALPHA_OUT);
            setDefaultAnimation(alphaOut);
            animations.addAnimation(scaleIn);
            animations.addAnimation(scaleOut);
            animations.addAnimation(alphaIn);
            animations.addAnimation(alphaOut);
            animations.addAnimation(translate);
            Point position = win.getSurfacePosition();
            boolean readyToAnimate = (position == null || (position.x == 0 && position.y == 0)) ? false : true;
            if (readyToAnimate) {
                win.startAnimation(0, animations, (Object) null);
            } else {
                win.mResizedAnimating = false;
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.wm.SharedDisplayAnimator$5  reason: invalid class name */
    /* loaded from: classes2.dex */
    public class AnonymousClass5 implements Animation.AnimationListener {
        final /* synthetic */ WindowState val$win;

        AnonymousClass5(WindowState windowState) {
            this.val$win = windowState;
        }

        @Override // android.view.animation.Animation.AnimationListener
        public void onAnimationStart(Animation animation) {
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$onAnimationEnd$0(WindowState win) {
            if (win != null) {
                win.mResizedAnimating = false;
            }
        }

        @Override // android.view.animation.Animation.AnimationListener
        public void onAnimationEnd(Animation animation) {
            Handler handler = new Handler(Looper.getMainLooper());
            final WindowState windowState = this.val$win;
            handler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayAnimator$5$g8Dg3NG0Dj-WR34GkkJybbkDo0U
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayAnimator.AnonymousClass5.lambda$onAnimationEnd$0(WindowState.this);
                }
            });
        }

        @Override // android.view.animation.Animation.AnimationListener
        public void onAnimationRepeat(Animation animation) {
        }
    }
}
