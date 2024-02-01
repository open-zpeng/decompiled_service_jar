package com.android.server.wm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.ResourceId;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.AppTransitionAnimationSpec;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.RemoteAnimationAdapter;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.ClipRectAnimation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import com.android.internal.R;
import com.android.internal.util.DumpUtils;
import com.android.server.AttributeCache;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.pm.PackageManagerService;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.animation.ClipRectLRAnimation;
import com.android.server.wm.animation.ClipRectTBAnimation;
import com.android.server.wm.animation.CurvedTranslateAnimation;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/* loaded from: classes.dex */
public class AppTransition implements DumpUtils.Dump {
    private static final int APP_STATE_IDLE = 0;
    private static final int APP_STATE_READY = 1;
    private static final int APP_STATE_RUNNING = 2;
    private static final int APP_STATE_TIMEOUT = 3;
    private static final long APP_TRANSITION_TIMEOUT_MS = 5000;
    private static final int CLIP_REVEAL_TRANSLATION_Y_DP = 8;
    static final int DEFAULT_APP_TRANSITION_DURATION = 336;
    private static final int MAX_CLIP_REVEAL_TRANSITION_DURATION = 420;
    private static final int NEXT_TRANSIT_TYPE_CLIP_REVEAL = 8;
    private static final int NEXT_TRANSIT_TYPE_CUSTOM = 1;
    private static final int NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE = 7;
    private static final int NEXT_TRANSIT_TYPE_NONE = 0;
    private static final int NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS = 9;
    private static final int NEXT_TRANSIT_TYPE_REMOTE = 10;
    private static final int NEXT_TRANSIT_TYPE_SCALE_UP = 2;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN = 6;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP = 5;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN = 4;
    private static final int NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP = 3;
    private static final float RECENTS_THUMBNAIL_FADEIN_FRACTION = 0.5f;
    private static final float RECENTS_THUMBNAIL_FADEOUT_FRACTION = 0.5f;
    private static final String TAG = "WindowManager";
    private static final int THUMBNAIL_APP_TRANSITION_DURATION = 336;
    private static final int THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN = 2;
    private static final int THUMBNAIL_TRANSITION_ENTER_SCALE_UP = 0;
    private static final int THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN = 3;
    private static final int THUMBNAIL_TRANSITION_EXIT_SCALE_UP = 1;
    private IRemoteCallback mAnimationFinishedCallback;
    private final int mClipRevealTranslationY;
    private final int mConfigShortAnimTime;
    private final Context mContext;
    private final Interpolator mDecelerateInterpolator;
    private AppTransitionAnimationSpec mDefaultNextAppTransitionAnimationSpec;
    private final Interpolator mFastOutLinearInInterpolator;
    private final Interpolator mFastOutSlowInInterpolator;
    private int mLastClipRevealMaxTranslation;
    private String mLastClosingApp;
    private boolean mLastHadClipReveal;
    private String mLastOpeningApp;
    private final Interpolator mLinearOutSlowInInterpolator;
    private IAppTransitionAnimationSpecsFuture mNextAppTransitionAnimationsSpecsFuture;
    private boolean mNextAppTransitionAnimationsSpecsPending;
    private IRemoteCallback mNextAppTransitionCallback;
    private int mNextAppTransitionEnter;
    private int mNextAppTransitionExit;
    private IRemoteCallback mNextAppTransitionFutureCallback;
    private int mNextAppTransitionInPlace;
    private String mNextAppTransitionPackage;
    private boolean mNextAppTransitionScaleUp;
    private RemoteAnimationController mRemoteAnimationController;
    private final WindowManagerService mService;
    static final Interpolator TOUCH_RESPONSE_INTERPOLATOR = new PathInterpolator(0.3f, 0.0f, 0.1f, 1.0f);
    private static final Interpolator THUMBNAIL_DOCK_INTERPOLATOR = new PathInterpolator(0.85f, 0.0f, 1.0f, 1.0f);
    private int mNextAppTransition = -1;
    private int mNextAppTransitionFlags = 0;
    private int mLastUsedAppTransition = -1;
    private int mNextAppTransitionType = 0;
    private final SparseArray<AppTransitionAnimationSpec> mNextAppTransitionAnimationsSpecs = new SparseArray<>();
    private Rect mNextAppTransitionInsets = new Rect();
    private Rect mTmpFromClipRect = new Rect();
    private Rect mTmpToClipRect = new Rect();
    private final Rect mTmpRect = new Rect();
    private int mAppTransitionState = 0;
    private final Interpolator mClipHorizontalInterpolator = new PathInterpolator(0.0f, 0.0f, 0.4f, 1.0f);
    private int mCurrentUserId = 0;
    private long mLastClipRevealTransitionDuration = 336;
    private final ArrayList<WindowManagerInternal.AppTransitionListener> mListeners = new ArrayList<>();
    private final ExecutorService mDefaultExecutor = Executors.newSingleThreadExecutor();
    private final Interpolator mThumbnailFadeInInterpolator = new Interpolator() { // from class: com.android.server.wm.AppTransition.1
        @Override // android.animation.TimeInterpolator
        public float getInterpolation(float input) {
            if (input >= 0.5f) {
                float t = (input - 0.5f) / 0.5f;
                return AppTransition.this.mFastOutLinearInInterpolator.getInterpolation(t);
            }
            return 0.0f;
        }
    };
    private final Interpolator mThumbnailFadeOutInterpolator = new Interpolator() { // from class: com.android.server.wm.AppTransition.2
        @Override // android.animation.TimeInterpolator
        public float getInterpolation(float input) {
            if (input < 0.5f) {
                float t = input / 0.5f;
                return AppTransition.this.mLinearOutSlowInInterpolator.getInterpolation(t);
            }
            return 1.0f;
        }
    };
    private final boolean mGridLayoutRecentsEnabled = SystemProperties.getBoolean("ro.recents.grid", false);
    private final boolean mLowRamRecentsEnabled = ActivityManager.isLowRamDeviceStatic();

    /* JADX INFO: Access modifiers changed from: package-private */
    public AppTransition(Context context, WindowManagerService service) {
        this.mContext = context;
        this.mService = service;
        this.mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(context, 17563662);
        this.mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context, 17563663);
        this.mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context, 17563661);
        this.mConfigShortAnimTime = context.getResources().getInteger(17694720);
        this.mDecelerateInterpolator = AnimationUtils.loadInterpolator(context, 17563651);
        this.mClipRevealTranslationY = (int) (8.0f * this.mContext.getResources().getDisplayMetrics().density);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTransitionSet() {
        return this.mNextAppTransition != -1;
    }

    boolean isTransitionEqual(int transit) {
        return this.mNextAppTransition == transit;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getAppTransition() {
        return this.mNextAppTransition;
    }

    private void setAppTransition(int transit, int flags) {
        this.mNextAppTransition = transit;
        this.mNextAppTransitionFlags |= flags;
        setLastAppTransition(-1, null, null);
        updateBooster();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastAppTransition(int transit, AppWindowToken openingApp, AppWindowToken closingApp) {
        this.mLastUsedAppTransition = transit;
        this.mLastOpeningApp = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + openingApp;
        this.mLastClosingApp = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + closingApp;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isReady() {
        return this.mAppTransitionState == 1 || this.mAppTransitionState == 3;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setReady() {
        setAppTransitionState(1);
        fetchAppTransitionSpecsFromFuture();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isRunning() {
        return this.mAppTransitionState == 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setIdle() {
        setAppTransitionState(0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTimeout() {
        return this.mAppTransitionState == 3;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setTimeout() {
        setAppTransitionState(3);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public GraphicBuffer getAppTransitionThumbnailHeader(int taskId) {
        AppTransitionAnimationSpec spec = this.mNextAppTransitionAnimationsSpecs.get(taskId);
        if (spec == null) {
            spec = this.mDefaultNextAppTransitionAnimationSpec;
        }
        if (spec != null) {
            return spec.buffer;
        }
        return null;
    }

    boolean isNextThumbnailTransitionAspectScaled() {
        return this.mNextAppTransitionType == 5 || this.mNextAppTransitionType == 6;
    }

    boolean isNextThumbnailTransitionScaleUp() {
        return this.mNextAppTransitionScaleUp;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNextAppTransitionThumbnailUp() {
        return this.mNextAppTransitionType == 3 || this.mNextAppTransitionType == 5;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNextAppTransitionThumbnailDown() {
        return this.mNextAppTransitionType == 4 || this.mNextAppTransitionType == 6;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNextAppTransitionOpenCrossProfileApps() {
        return this.mNextAppTransitionType == 9;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFetchingAppTransitionsSpecs() {
        return this.mNextAppTransitionAnimationsSpecsPending;
    }

    private boolean prepare() {
        if (isRunning()) {
            return false;
        }
        setAppTransitionState(0);
        notifyAppTransitionPendingLocked();
        this.mLastHadClipReveal = false;
        this.mLastClipRevealMaxTranslation = 0;
        this.mLastClipRevealTransitionDuration = 336L;
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int goodToGo(int transit, AppWindowToken topOpeningApp, AppWindowToken topClosingApp, ArraySet<AppWindowToken> openingApps, ArraySet<AppWindowToken> closingApps) {
        AnimationAdapter animationAdapter;
        this.mNextAppTransition = -1;
        this.mNextAppTransitionFlags = 0;
        setAppTransitionState(2);
        if (topOpeningApp != null) {
            animationAdapter = topOpeningApp.getAnimation();
        } else {
            animationAdapter = null;
        }
        AnimationAdapter topOpeningAnim = animationAdapter;
        int redoLayout = notifyAppTransitionStartingLocked(transit, topOpeningApp != null ? topOpeningApp.token : null, topClosingApp != null ? topClosingApp.token : null, topOpeningAnim != null ? topOpeningAnim.getDurationHint() : 0L, topOpeningAnim != null ? topOpeningAnim.getStatusBarTransitionsStartTime() : SystemClock.uptimeMillis(), 120L);
        this.mService.getDefaultDisplayContentLocked().getDockedDividerController().notifyAppTransitionStarting(openingApps, transit);
        if (this.mRemoteAnimationController != null) {
            this.mRemoteAnimationController.goodToGo();
        }
        return redoLayout;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clear() {
        this.mNextAppTransitionType = 0;
        this.mNextAppTransitionPackage = null;
        this.mNextAppTransitionAnimationsSpecs.clear();
        this.mRemoteAnimationController = null;
        this.mNextAppTransitionAnimationsSpecsFuture = null;
        this.mDefaultNextAppTransitionAnimationSpec = null;
        this.mAnimationFinishedCallback = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void freeze() {
        int transit = this.mNextAppTransition;
        setAppTransition(-1, 0);
        clear();
        setReady();
        notifyAppTransitionCancelledLocked(transit);
    }

    private void setAppTransitionState(int state) {
        this.mAppTransitionState = state;
        updateBooster();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateBooster() {
        WindowManagerService.sThreadPriorityBooster.setAppTransitionRunning(needsBoosting());
    }

    private boolean needsBoosting() {
        boolean recentsAnimRunning = this.mService.getRecentsAnimationController() != null;
        return this.mNextAppTransition != -1 || this.mAppTransitionState == 1 || this.mAppTransitionState == 2 || recentsAnimRunning;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerListenerLocked(WindowManagerInternal.AppTransitionListener listener) {
        this.mListeners.add(listener);
    }

    public void notifyAppTransitionFinishedLocked(IBinder token) {
        for (int i = 0; i < this.mListeners.size(); i++) {
            this.mListeners.get(i).onAppTransitionFinishedLocked(token);
        }
    }

    private void notifyAppTransitionPendingLocked() {
        for (int i = 0; i < this.mListeners.size(); i++) {
            this.mListeners.get(i).onAppTransitionPendingLocked();
        }
    }

    private void notifyAppTransitionCancelledLocked(int transit) {
        for (int i = 0; i < this.mListeners.size(); i++) {
            this.mListeners.get(i).onAppTransitionCancelledLocked(transit);
        }
    }

    private int notifyAppTransitionStartingLocked(int transit, IBinder openToken, IBinder closeToken, long duration, long statusBarAnimationStartTime, long statusBarAnimationDuration) {
        int redoLayout = 0;
        for (int i = 0; i < this.mListeners.size(); i++) {
            redoLayout |= this.mListeners.get(i).onAppTransitionStartingLocked(transit, openToken, closeToken, duration, statusBarAnimationStartTime, statusBarAnimationDuration);
        }
        return redoLayout;
    }

    private AttributeCache.Entry getCachedAnimations(WindowManager.LayoutParams lp) {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            StringBuilder sb = new StringBuilder();
            sb.append("Loading animations: layout params pkg=");
            sb.append(lp != null ? lp.packageName : null);
            sb.append(" resId=0x");
            sb.append(lp != null ? Integer.toHexString(lp.windowAnimations) : null);
            Slog.v(TAG, sb.toString());
        }
        if (lp == null || lp.windowAnimations == 0) {
            return null;
        }
        String packageName = lp.packageName != null ? lp.packageName : PackageManagerService.PLATFORM_PACKAGE_NAME;
        int resId = lp.windowAnimations;
        if (((-16777216) & resId) == 16777216) {
            packageName = PackageManagerService.PLATFORM_PACKAGE_NAME;
        }
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "Loading animations: picked package=" + packageName);
        }
        return AttributeCache.instance().get(packageName, resId, R.styleable.WindowAnimation, this.mCurrentUserId);
    }

    private AttributeCache.Entry getCachedAnimations(String packageName, int resId) {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "Loading animations: package=" + packageName + " resId=0x" + Integer.toHexString(resId));
        }
        if (packageName != null) {
            if (((-16777216) & resId) == 16777216) {
                packageName = PackageManagerService.PLATFORM_PACKAGE_NAME;
            }
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.v(TAG, "Loading animations: picked package=" + packageName);
            }
            return AttributeCache.instance().get(packageName, resId, R.styleable.WindowAnimation, this.mCurrentUserId);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Animation loadAnimationAttr(WindowManager.LayoutParams lp, int animAttr, int transit) {
        AttributeCache.Entry ent;
        int resId = 0;
        Context context = this.mContext;
        if (animAttr >= 0 && (ent = getCachedAnimations(lp)) != null) {
            context = ent.context;
            resId = ent.array.getResourceId(animAttr, 0);
        }
        int resId2 = updateToTranslucentAnimIfNeeded(resId, transit);
        if (ResourceId.isValid(resId2)) {
            return AnimationUtils.loadAnimation(context, resId2);
        }
        return null;
    }

    Animation loadAnimationRes(WindowManager.LayoutParams lp, int resId) {
        Context context = this.mContext;
        if (ResourceId.isValid(resId)) {
            AttributeCache.Entry ent = getCachedAnimations(lp);
            if (ent != null) {
                context = ent.context;
            }
            return AnimationUtils.loadAnimation(context, resId);
        }
        return null;
    }

    private Animation loadAnimationRes(String packageName, int resId) {
        AttributeCache.Entry ent;
        if (ResourceId.isValid(resId) && (ent = getCachedAnimations(packageName, resId)) != null) {
            return AnimationUtils.loadAnimation(ent.context, resId);
        }
        return null;
    }

    private int updateToTranslucentAnimIfNeeded(int anim, int transit) {
        if (transit == 24 && anim == 17432591) {
            return 17432594;
        }
        if (transit == 25 && anim == 17432590) {
            return 17432593;
        }
        return anim;
    }

    private static float computePivot(int startPos, float finalScale) {
        float denom = finalScale - 1.0f;
        if (Math.abs(denom) < 1.0E-4f) {
            return startPos;
        }
        return (-startPos) / denom;
    }

    /* JADX WARN: Multi-variable type inference failed */
    private Animation createScaleUpAnimationLocked(int transit, boolean enter, Rect containingFrame) {
        Animation alpha;
        long duration;
        getDefaultNextAppTransitionStartRect(this.mTmpRect);
        int appWidth = containingFrame.width();
        int appHeight = containingFrame.height();
        if (enter) {
            float scaleW = this.mTmpRect.width() / appWidth;
            float scaleH = this.mTmpRect.height() / appHeight;
            Animation scale = new ScaleAnimation(scaleW, 1.0f, scaleH, 1.0f, computePivot(this.mTmpRect.left, scaleW), computePivot(this.mTmpRect.top, scaleH));
            scale.setInterpolator(this.mDecelerateInterpolator);
            Animation alpha2 = new AlphaAnimation(0.0f, 1.0f);
            alpha2.setInterpolator(this.mThumbnailFadeOutInterpolator);
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha2);
            set.setDetachWallpaper(true);
            alpha = set;
        } else if (transit == 14 || transit == 15) {
            alpha = new AlphaAnimation(1.0f, 0.0f);
            alpha.setDetachWallpaper(true);
        } else {
            alpha = new AlphaAnimation(1.0f, 1.0f);
        }
        switch (transit) {
            case 6:
            case 7:
                duration = this.mConfigShortAnimTime;
                break;
            default:
                duration = 336;
                break;
        }
        alpha.setDuration(duration);
        alpha.setFillAfter(true);
        alpha.setInterpolator(this.mDecelerateInterpolator);
        alpha.initialize(appWidth, appHeight, appWidth, appHeight);
        return alpha;
    }

    private void getDefaultNextAppTransitionStartRect(Rect rect) {
        if (this.mDefaultNextAppTransitionAnimationSpec == null || this.mDefaultNextAppTransitionAnimationSpec.rect == null) {
            Slog.e(TAG, "Starting rect for app requested, but none available", new Throwable());
            rect.setEmpty();
            return;
        }
        rect.set(this.mDefaultNextAppTransitionAnimationSpec.rect);
    }

    void getNextAppTransitionStartRect(int taskId, Rect rect) {
        AppTransitionAnimationSpec spec = this.mNextAppTransitionAnimationsSpecs.get(taskId);
        if (spec == null) {
            spec = this.mDefaultNextAppTransitionAnimationSpec;
        }
        if (spec == null || spec.rect == null) {
            Slog.e(TAG, "Starting rect for task: " + taskId + " requested, but not available", new Throwable());
            rect.setEmpty();
            return;
        }
        rect.set(spec.rect);
    }

    private void putDefaultNextAppTransitionCoordinates(int left, int top, int width, int height, GraphicBuffer buffer) {
        this.mDefaultNextAppTransitionAnimationSpec = new AppTransitionAnimationSpec(-1, buffer, new Rect(left, top, left + width, top + height));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getLastClipRevealTransitionDuration() {
        return this.mLastClipRevealTransitionDuration;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLastClipRevealMaxTranslation() {
        return this.mLastClipRevealMaxTranslation;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hadClipRevealAnimation() {
        return this.mLastHadClipReveal;
    }

    private long calculateClipRevealTransitionDuration(boolean cutOff, float translationX, float translationY, Rect displayFrame) {
        if (!cutOff) {
            return 336L;
        }
        float fraction = Math.max(Math.abs(translationX) / displayFrame.width(), Math.abs(translationY) / displayFrame.height());
        return 336.0f + (84.0f * fraction);
    }

    private Animation createClipRevealAnimationLocked(int transit, boolean enter, Rect appFrame, Rect displayFrame) {
        long duration;
        Animation anim;
        boolean z;
        int translationYCorrection;
        int translationYCorrection2;
        int clipStartY;
        if (enter) {
            int appWidth = appFrame.width();
            int appHeight = appFrame.height();
            getDefaultNextAppTransitionStartRect(this.mTmpRect);
            float t = 0.0f;
            if (appHeight > 0) {
                t = this.mTmpRect.top / displayFrame.height();
            }
            int translationY = this.mClipRevealTranslationY + ((int) ((displayFrame.height() / 7.0f) * t));
            int translationX = 0;
            int centerX = this.mTmpRect.centerX();
            int centerY = this.mTmpRect.centerY();
            int halfWidth = this.mTmpRect.width() / 2;
            int halfHeight = this.mTmpRect.height() / 2;
            int clipStartX = (centerX - halfWidth) - appFrame.left;
            int clipStartY2 = (centerY - halfHeight) - appFrame.top;
            boolean cutOff = false;
            if (appFrame.top <= centerY - halfHeight) {
                translationYCorrection = translationY;
                translationYCorrection2 = translationY;
                clipStartY = clipStartY2;
            } else {
                cutOff = true;
                translationYCorrection2 = 0;
                translationYCorrection = (centerY - halfHeight) - appFrame.top;
                clipStartY = 0;
            }
            if (appFrame.left > centerX - halfWidth) {
                translationX = (centerX - halfWidth) - appFrame.left;
                clipStartX = 0;
                cutOff = true;
            }
            if (appFrame.right < centerX + halfWidth) {
                translationX = (centerX + halfWidth) - appFrame.right;
                clipStartX = appWidth - this.mTmpRect.width();
                cutOff = true;
            }
            int clipStartX2 = clipStartX;
            boolean cutOff2 = cutOff;
            int translationX2 = translationX;
            long duration2 = calculateClipRevealTransitionDuration(cutOff2, translationX2, translationYCorrection, displayFrame);
            ClipRectAnimation clipRectLRAnimation = new ClipRectLRAnimation(clipStartX2, this.mTmpRect.width() + clipStartX2, 0, appWidth);
            clipRectLRAnimation.setInterpolator(this.mClipHorizontalInterpolator);
            clipRectLRAnimation.setDuration(((float) duration2) / 2.5f);
            TranslateAnimation translate = new TranslateAnimation(translationX2, 0.0f, translationYCorrection, 0.0f);
            translate.setInterpolator(cutOff2 ? TOUCH_RESPONSE_INTERPOLATOR : this.mLinearOutSlowInInterpolator);
            translate.setDuration(duration2);
            int translationX3 = translationYCorrection2;
            ClipRectAnimation clipRectTBAnimation = new ClipRectTBAnimation(clipStartY, clipStartY + this.mTmpRect.height(), 0, appHeight, translationX3, 0, this.mLinearOutSlowInInterpolator);
            clipRectTBAnimation.setInterpolator(TOUCH_RESPONSE_INTERPOLATOR);
            clipRectTBAnimation.setDuration(duration2);
            long alphaDuration = duration2 / 4;
            AlphaAnimation alpha = new AlphaAnimation(0.5f, 1.0f);
            alpha.setDuration(alphaDuration);
            alpha.setInterpolator(this.mLinearOutSlowInInterpolator);
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(clipRectLRAnimation);
            set.addAnimation(clipRectTBAnimation);
            set.addAnimation(translate);
            set.addAnimation(alpha);
            set.setZAdjustment(1);
            set.initialize(appWidth, appHeight, appWidth, appHeight);
            this.mLastHadClipReveal = true;
            this.mLastClipRevealTransitionDuration = duration2;
            this.mLastClipRevealMaxTranslation = cutOff2 ? Math.max(Math.abs(translationYCorrection), Math.abs(translationX2)) : 0;
            return set;
        }
        switch (transit) {
            case 6:
            case 7:
                duration = this.mConfigShortAnimTime;
                break;
            default:
                duration = 336;
                break;
        }
        if (transit == 14 || transit == 15) {
            anim = new AlphaAnimation(1.0f, 0.0f);
            z = true;
            anim.setDetachWallpaper(true);
        } else {
            anim = new AlphaAnimation(1.0f, 1.0f);
            z = true;
        }
        anim.setInterpolator(this.mDecelerateInterpolator);
        anim.setDuration(duration);
        anim.setFillAfter(z);
        return anim;
    }

    Animation prepareThumbnailAnimationWithDuration(Animation a, int appWidth, int appHeight, long duration, Interpolator interpolator) {
        if (duration > 0) {
            a.setDuration(duration);
        }
        a.setFillAfter(true);
        if (interpolator != null) {
            a.setInterpolator(interpolator);
        }
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }

    Animation prepareThumbnailAnimation(Animation a, int appWidth, int appHeight, int transit) {
        int duration;
        switch (transit) {
            case 6:
            case 7:
                duration = this.mConfigShortAnimTime;
                break;
            default:
                duration = 336;
                break;
        }
        return prepareThumbnailAnimationWithDuration(a, appWidth, appHeight, duration, this.mDecelerateInterpolator);
    }

    int getThumbnailTransitionState(boolean enter) {
        if (enter) {
            if (this.mNextAppTransitionScaleUp) {
                return 0;
            }
            return 2;
        } else if (this.mNextAppTransitionScaleUp) {
            return 1;
        } else {
            return 3;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public GraphicBuffer createCrossProfileAppsThumbnail(int thumbnailDrawableRes, Rect frame) {
        int width = frame.width();
        int height = frame.height();
        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(width, height);
        canvas.drawColor(Color.argb(0.6f, 0.0f, 0.0f, 0.0f));
        int thumbnailSize = this.mService.mContext.getResources().getDimensionPixelSize(17105006);
        Drawable drawable = this.mService.mContext.getDrawable(thumbnailDrawableRes);
        drawable.setBounds((width - thumbnailSize) / 2, (height - thumbnailSize) / 2, (width + thumbnailSize) / 2, (height + thumbnailSize) / 2);
        drawable.setTint(this.mContext.getColor(17170443));
        drawable.draw(canvas);
        picture.endRecording();
        return Bitmap.createBitmap(picture).createGraphicBufferHandle();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Animation createCrossProfileAppsThumbnailAnimationLocked(Rect appRect) {
        Animation animation = loadAnimationRes(PackageManagerService.PLATFORM_PACKAGE_NAME, 17432609);
        return prepareThumbnailAnimationWithDuration(animation, appRect.width(), appRect.height(), 0L, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Animation createThumbnailAspectScaleAnimationLocked(Rect appRect, Rect contentInsets, GraphicBuffer thumbnailHeader, int taskId, int uiMode, int orientation) {
        float fromX;
        float pivotX;
        float pivotY;
        float fromY;
        float fromY2;
        float toY;
        int appWidth;
        float fromY3;
        AnimationSet set;
        long j;
        float fromY4;
        int thumbWidthI = thumbnailHeader.getWidth();
        float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1.0f;
        int thumbHeightI = thumbnailHeader.getHeight();
        int appWidth2 = appRect.width();
        float scaleW = appWidth2 / thumbWidth;
        getNextAppTransitionStartRect(taskId, this.mTmpRect);
        if (shouldScaleDownThumbnailTransition(uiMode, orientation)) {
            float f = this.mTmpRect.left;
            float fromY5 = this.mTmpRect.top;
            fromY2 = ((this.mTmpRect.width() / 2) * (scaleW - 1.0f)) + appRect.left;
            toY = ((appRect.height() / 2) * (1.0f - (1.0f / scaleW))) + appRect.top;
            float width = this.mTmpRect.width() / 2;
            float pivotY2 = (appRect.height() / 2) / scaleW;
            if (this.mGridLayoutRecentsEnabled) {
                fromY5 -= thumbHeightI;
                toY -= thumbHeightI * scaleW;
            }
            fromY = fromY5;
            pivotY = pivotY2;
            fromX = f;
            pivotX = width;
        } else {
            fromX = this.mTmpRect.left;
            float fromY6 = this.mTmpRect.top;
            float toX = appRect.left;
            pivotX = 0.0f;
            pivotY = 0.0f;
            fromY = fromY6;
            fromY2 = toX;
            toY = appRect.top;
        }
        float pivotY3 = toY;
        long duration = getAspectScaleDuration();
        Interpolator interpolator = getAspectScaleInterpolator();
        if (this.mNextAppTransitionScaleUp) {
            Animation scale = new ScaleAnimation(1.0f, scaleW, 1.0f, scaleW, pivotX, pivotY);
            scale.setInterpolator(interpolator);
            scale.setDuration(duration);
            appWidth = appWidth2;
            Animation alpha = new AlphaAnimation(1.0f, 0.0f);
            alpha.setInterpolator(this.mNextAppTransition == 19 ? THUMBNAIL_DOCK_INTERPOLATOR : this.mThumbnailFadeOutInterpolator);
            if (this.mNextAppTransition == 19) {
                j = duration / 2;
            } else {
                j = duration;
            }
            alpha.setDuration(j);
            Animation translate = createCurvedMotion(fromX, fromY2, fromY, pivotY3);
            translate.setInterpolator(interpolator);
            translate.setDuration(duration);
            this.mTmpFromClipRect.set(0, 0, thumbWidthI, thumbHeightI);
            this.mTmpToClipRect.set(appRect);
            this.mTmpToClipRect.offsetTo(0, 0);
            this.mTmpToClipRect.right = (int) (this.mTmpToClipRect.right / scaleW);
            this.mTmpToClipRect.bottom = (int) (this.mTmpToClipRect.bottom / scaleW);
            if (contentInsets != null) {
                fromY4 = fromY;
                this.mTmpToClipRect.inset((int) ((-contentInsets.left) * scaleW), (int) ((-contentInsets.top) * scaleW), (int) ((-contentInsets.right) * scaleW), (int) ((-contentInsets.bottom) * scaleW));
            } else {
                fromY4 = fromY;
            }
            ClipRectAnimation clipRectAnimation = new ClipRectAnimation(this.mTmpFromClipRect, this.mTmpToClipRect);
            clipRectAnimation.setInterpolator(interpolator);
            clipRectAnimation.setDuration(duration);
            AnimationSet set2 = new AnimationSet(false);
            set2.addAnimation(scale);
            if (!this.mGridLayoutRecentsEnabled) {
                set2.addAnimation(alpha);
            }
            set2.addAnimation(translate);
            set2.addAnimation(clipRectAnimation);
            set = set2;
            fromY3 = fromY4;
        } else {
            float fromY7 = fromY;
            appWidth = appWidth2;
            Animation scale2 = new ScaleAnimation(scaleW, 1.0f, scaleW, 1.0f, pivotX, pivotY);
            scale2.setInterpolator(interpolator);
            scale2.setDuration(duration);
            Animation alpha2 = new AlphaAnimation(0.0f, 1.0f);
            alpha2.setInterpolator(this.mThumbnailFadeInInterpolator);
            alpha2.setDuration(duration);
            fromY3 = fromY7;
            Animation translate2 = createCurvedMotion(fromY2, fromX, pivotY3, fromY3);
            translate2.setInterpolator(interpolator);
            translate2.setDuration(duration);
            set = new AnimationSet(false);
            set.addAnimation(scale2);
            if (!this.mGridLayoutRecentsEnabled) {
                set.addAnimation(alpha2);
            }
            set.addAnimation(translate2);
        }
        Animation a = set;
        return prepareThumbnailAnimationWithDuration(a, appWidth, appRect.height(), 0L, null);
    }

    private Animation createCurvedMotion(float fromX, float toX, float fromY, float toY) {
        if (Math.abs(toX - fromX) < 1.0f || this.mNextAppTransition != 19) {
            return new TranslateAnimation(fromX, toX, fromY, toY);
        }
        Path path = createCurvedPath(fromX, toX, fromY, toY);
        return new CurvedTranslateAnimation(path);
    }

    private Path createCurvedPath(float fromX, float toX, float fromY, float toY) {
        Path path = new Path();
        path.moveTo(fromX, fromY);
        if (fromY > toY) {
            path.cubicTo(fromX, fromY, toX, (0.9f * fromY) + (0.1f * toY), toX, toY);
        } else {
            path.cubicTo(fromX, fromY, fromX, (0.1f * fromY) + (0.9f * toY), toX, toY);
        }
        return path;
    }

    private long getAspectScaleDuration() {
        if (this.mNextAppTransition == 19) {
            return 453L;
        }
        return 336L;
    }

    private Interpolator getAspectScaleInterpolator() {
        if (this.mNextAppTransition == 19) {
            return this.mFastOutSlowInInterpolator;
        }
        return TOUCH_RESPONSE_INTERPOLATOR;
    }

    Animation createAspectScaledThumbnailEnterExitAnimationLocked(int thumbTransitState, int uiMode, int orientation, int transit, Rect containingFrame, Rect contentInsets, Rect surfaceInsets, Rect stableInsets, boolean freeform, int taskId) {
        ClipRectAnimation clipRectAnimation;
        Animation translateAnim;
        Animation a;
        Animation translateAnim2;
        Animation a2;
        int appWidth = containingFrame.width();
        int appHeight = containingFrame.height();
        getDefaultNextAppTransitionStartRect(this.mTmpRect);
        int thumbWidthI = this.mTmpRect.width();
        float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1.0f;
        int thumbHeightI = this.mTmpRect.height();
        float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1.0f;
        int thumbStartX = (this.mTmpRect.left - containingFrame.left) - contentInsets.left;
        int thumbStartY = this.mTmpRect.top - containingFrame.top;
        switch (thumbTransitState) {
            case 0:
            case 3:
                boolean scaleUp = thumbTransitState == 0;
                if (freeform && scaleUp) {
                    a2 = createAspectScaledThumbnailEnterFreeformAnimationLocked(containingFrame, surfaceInsets, taskId);
                } else if (freeform) {
                    a2 = createAspectScaledThumbnailExitFreeformAnimationLocked(containingFrame, surfaceInsets, taskId);
                } else {
                    AnimationSet set = new AnimationSet(true);
                    this.mTmpFromClipRect.set(containingFrame);
                    this.mTmpToClipRect.set(containingFrame);
                    this.mTmpFromClipRect.offsetTo(0, 0);
                    this.mTmpToClipRect.offsetTo(0, 0);
                    this.mTmpFromClipRect.inset(contentInsets);
                    this.mNextAppTransitionInsets.set(contentInsets);
                    if (shouldScaleDownThumbnailTransition(uiMode, orientation)) {
                        float scale = thumbWidth / ((appWidth - contentInsets.left) - contentInsets.right);
                        if (!this.mGridLayoutRecentsEnabled) {
                            int unscaledThumbHeight = (int) (thumbHeight / scale);
                            this.mTmpFromClipRect.bottom = this.mTmpFromClipRect.top + unscaledThumbHeight;
                        }
                        this.mNextAppTransitionInsets.set(contentInsets);
                        Animation scaleAnim = new ScaleAnimation(scaleUp ? scale : 1.0f, scaleUp ? 1.0f : scale, scaleUp ? scale : 1.0f, scaleUp ? 1.0f : scale, containingFrame.width() / 2.0f, (containingFrame.height() / 2.0f) + contentInsets.top);
                        float targetX = this.mTmpRect.left - containingFrame.left;
                        float x = (containingFrame.width() / 2.0f) - ((containingFrame.width() / 2.0f) * scale);
                        float targetY = this.mTmpRect.top - containingFrame.top;
                        float y = (containingFrame.height() / 2.0f) - ((containingFrame.height() / 2.0f) * scale);
                        if (this.mLowRamRecentsEnabled && contentInsets.top == 0 && scaleUp) {
                            this.mTmpFromClipRect.top += stableInsets.top;
                            y += stableInsets.top;
                        }
                        float scale2 = targetX - x;
                        float startY = targetY - y;
                        Animation clipAnim = scaleUp ? new ClipRectAnimation(this.mTmpFromClipRect, this.mTmpToClipRect) : new ClipRectAnimation(this.mTmpToClipRect, this.mTmpFromClipRect);
                        if (scaleUp) {
                            translateAnim2 = createCurvedMotion(scale2, 0.0f, startY - contentInsets.top, 0.0f);
                        } else {
                            translateAnim2 = createCurvedMotion(0.0f, scale2, 0.0f, startY - contentInsets.top);
                        }
                        set.addAnimation(clipAnim);
                        set.addAnimation(scaleAnim);
                        set.addAnimation(translateAnim2);
                    } else {
                        this.mTmpFromClipRect.bottom = this.mTmpFromClipRect.top + thumbHeightI;
                        this.mTmpFromClipRect.right = this.mTmpFromClipRect.left + thumbWidthI;
                        if (scaleUp) {
                            clipRectAnimation = new ClipRectAnimation(this.mTmpFromClipRect, this.mTmpToClipRect);
                        } else {
                            clipRectAnimation = new ClipRectAnimation(this.mTmpToClipRect, this.mTmpFromClipRect);
                        }
                        if (scaleUp) {
                            translateAnim = createCurvedMotion(thumbStartX, 0.0f, thumbStartY - contentInsets.top, 0.0f);
                        } else {
                            translateAnim = createCurvedMotion(0.0f, thumbStartX, 0.0f, thumbStartY - contentInsets.top);
                        }
                        set.addAnimation(clipRectAnimation);
                        set.addAnimation(translateAnim);
                    }
                    a = set;
                    a.setZAdjustment(1);
                    break;
                }
                a = a2;
                break;
            case 1:
                if (transit == 14) {
                    a = new AlphaAnimation(1.0f, 0.0f);
                    break;
                } else {
                    a = new AlphaAnimation(1.0f, 1.0f);
                    break;
                }
            case 2:
                if (transit == 14) {
                    a = new AlphaAnimation(0.0f, 1.0f);
                    break;
                } else {
                    a = new AlphaAnimation(1.0f, 1.0f);
                    break;
                }
            default:
                throw new RuntimeException("Invalid thumbnail transition state");
        }
        return prepareThumbnailAnimationWithDuration(a, appWidth, appHeight, getAspectScaleDuration(), getAspectScaleInterpolator());
    }

    private Animation createAspectScaledThumbnailEnterFreeformAnimationLocked(Rect frame, Rect surfaceInsets, int taskId) {
        getNextAppTransitionStartRect(taskId, this.mTmpRect);
        return createAspectScaledThumbnailFreeformAnimationLocked(this.mTmpRect, frame, surfaceInsets, true);
    }

    private Animation createAspectScaledThumbnailExitFreeformAnimationLocked(Rect frame, Rect surfaceInsets, int taskId) {
        getNextAppTransitionStartRect(taskId, this.mTmpRect);
        return createAspectScaledThumbnailFreeformAnimationLocked(frame, this.mTmpRect, surfaceInsets, false);
    }

    private AnimationSet createAspectScaledThumbnailFreeformAnimationLocked(Rect sourceFrame, Rect destFrame, Rect surfaceInsets, boolean enter) {
        float sourceWidth = sourceFrame.width();
        float sourceHeight = sourceFrame.height();
        float destWidth = destFrame.width();
        float destHeight = destFrame.height();
        float scaleH = enter ? sourceWidth / destWidth : destWidth / sourceWidth;
        float scaleV = enter ? sourceHeight / destHeight : destHeight / sourceHeight;
        AnimationSet set = new AnimationSet(true);
        int surfaceInsetsH = surfaceInsets == null ? 0 : surfaceInsets.left + surfaceInsets.right;
        int surfaceInsetsV = surfaceInsets != null ? surfaceInsets.top + surfaceInsets.bottom : 0;
        float scaleHCenter = ((enter ? destWidth : sourceWidth) + surfaceInsetsH) / 2.0f;
        float scaleVCenter = ((enter ? destHeight : sourceHeight) + surfaceInsetsV) / 2.0f;
        ScaleAnimation scale = enter ? new ScaleAnimation(scaleH, 1.0f, scaleV, 1.0f, scaleHCenter, scaleVCenter) : new ScaleAnimation(1.0f, scaleH, 1.0f, scaleV, scaleHCenter, scaleVCenter);
        int sourceHCenter = sourceFrame.left + (sourceFrame.width() / 2);
        int sourceVCenter = sourceFrame.top + (sourceFrame.height() / 2);
        int destHCenter = destFrame.left + (destFrame.width() / 2);
        int destVCenter = destFrame.top + (destFrame.height() / 2);
        int fromX = enter ? sourceHCenter - destHCenter : destHCenter - sourceHCenter;
        int fromY = enter ? sourceVCenter - destVCenter : destVCenter - sourceVCenter;
        TranslateAnimation translation = enter ? new TranslateAnimation(fromX, 0.0f, fromY, 0.0f) : new TranslateAnimation(0.0f, fromX, 0.0f, fromY);
        set.addAnimation(scale);
        set.addAnimation(translation);
        final IRemoteCallback callback = this.mAnimationFinishedCallback;
        if (callback != null) {
            set.setAnimationListener(new Animation.AnimationListener() { // from class: com.android.server.wm.AppTransition.3
                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationStart(Animation animation) {
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationEnd(Animation animation) {
                    AppTransition.this.mService.mH.obtainMessage(26, callback).sendToTarget();
                }

                @Override // android.view.animation.Animation.AnimationListener
                public void onAnimationRepeat(Animation animation) {
                }
            });
        }
        return set;
    }

    /* JADX WARN: Multi-variable type inference failed */
    Animation createThumbnailScaleAnimationLocked(int appWidth, int appHeight, int transit, GraphicBuffer thumbnailHeader) {
        Animation a;
        getDefaultNextAppTransitionStartRect(this.mTmpRect);
        int thumbWidthI = thumbnailHeader.getWidth();
        float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1.0f;
        int thumbHeightI = thumbnailHeader.getHeight();
        float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1.0f;
        if (this.mNextAppTransitionScaleUp) {
            float scaleW = appWidth / thumbWidth;
            float scaleH = appHeight / thumbHeight;
            Animation scale = new ScaleAnimation(1.0f, scaleW, 1.0f, scaleH, computePivot(this.mTmpRect.left, 1.0f / scaleW), computePivot(this.mTmpRect.top, 1.0f / scaleH));
            scale.setInterpolator(this.mDecelerateInterpolator);
            Animation alpha = new AlphaAnimation(1.0f, 0.0f);
            alpha.setInterpolator(this.mThumbnailFadeOutInterpolator);
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            a = set;
        } else {
            float scaleW2 = appWidth / thumbWidth;
            float scaleH2 = appHeight / thumbHeight;
            a = new ScaleAnimation(scaleW2, 1.0f, scaleH2, 1.0f, computePivot(this.mTmpRect.left, 1.0f / scaleW2), computePivot(this.mTmpRect.top, 1.0f / scaleH2));
        }
        return prepareThumbnailAnimation(a, appWidth, appHeight, transit);
    }

    Animation createThumbnailEnterExitAnimationLocked(int thumbTransitState, Rect containingFrame, int transit, int taskId) {
        Animation a;
        int appWidth = containingFrame.width();
        int appHeight = containingFrame.height();
        GraphicBuffer thumbnailHeader = getAppTransitionThumbnailHeader(taskId);
        getDefaultNextAppTransitionStartRect(this.mTmpRect);
        int thumbWidthI = thumbnailHeader != null ? thumbnailHeader.getWidth() : appWidth;
        float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1.0f;
        int thumbHeightI = thumbnailHeader != null ? thumbnailHeader.getHeight() : appHeight;
        float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1.0f;
        switch (thumbTransitState) {
            case 0:
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                a = new ScaleAnimation(scaleW, 1.0f, scaleH, 1.0f, computePivot(this.mTmpRect.left, scaleW), computePivot(this.mTmpRect.top, scaleH));
                break;
            case 1:
                if (transit == 14) {
                    a = new AlphaAnimation(1.0f, 0.0f);
                    break;
                } else {
                    a = new AlphaAnimation(1.0f, 1.0f);
                    break;
                }
            case 2:
                a = new AlphaAnimation(1.0f, 1.0f);
                break;
            case 3:
                float scaleW2 = thumbWidth / appWidth;
                float scaleH2 = thumbHeight / appHeight;
                Animation scale = new ScaleAnimation(1.0f, scaleW2, 1.0f, scaleH2, computePivot(this.mTmpRect.left, scaleW2), computePivot(this.mTmpRect.top, scaleH2));
                Animation alpha = new AlphaAnimation(1.0f, 0.0f);
                AnimationSet set = new AnimationSet(true);
                set.addAnimation(scale);
                set.addAnimation(alpha);
                set.setZAdjustment(1);
                a = set;
                break;
            default:
                throw new RuntimeException("Invalid thumbnail transition state");
        }
        return prepareThumbnailAnimation(a, appWidth, appHeight, transit);
    }

    private Animation createRelaunchAnimation(Rect containingFrame, Rect contentInsets) {
        getDefaultNextAppTransitionStartRect(this.mTmpFromClipRect);
        int left = this.mTmpFromClipRect.left;
        int top = this.mTmpFromClipRect.top;
        this.mTmpFromClipRect.offset(-left, -top);
        this.mTmpToClipRect.set(0, 0, containingFrame.width(), containingFrame.height());
        AnimationSet set = new AnimationSet(true);
        float fromWidth = this.mTmpFromClipRect.width();
        float toWidth = this.mTmpToClipRect.width();
        float fromHeight = this.mTmpFromClipRect.height();
        float toHeight = (this.mTmpToClipRect.height() - contentInsets.top) - contentInsets.bottom;
        int translateAdjustment = 0;
        if (fromWidth <= toWidth && fromHeight <= toHeight) {
            set.addAnimation(new ClipRectAnimation(this.mTmpFromClipRect, this.mTmpToClipRect));
        } else {
            set.addAnimation(new ScaleAnimation(fromWidth / toWidth, 1.0f, fromHeight / toHeight, 1.0f));
            translateAdjustment = (int) ((contentInsets.top * fromHeight) / toHeight);
        }
        TranslateAnimation translate = new TranslateAnimation(left - containingFrame.left, 0.0f, (top - containingFrame.top) - translateAdjustment, 0.0f);
        set.addAnimation(translate);
        set.setDuration(336L);
        set.setZAdjustment(1);
        return set;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canSkipFirstFrame() {
        return (this.mNextAppTransitionType == 1 || this.mNextAppTransitionType == 7 || this.mNextAppTransitionType == 8 || this.mNextAppTransition == 20) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteAnimationController getRemoteAnimationController() {
        return this.mRemoteAnimationController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:133:0x0307  */
    /* JADX WARN: Removed duplicated region for block: B:137:0x030d  */
    /* JADX WARN: Removed duplicated region for block: B:144:0x031e  */
    /* JADX WARN: Removed duplicated region for block: B:145:0x0323  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.view.animation.Animation loadAnimation(android.view.WindowManager.LayoutParams r20, int r21, boolean r22, int r23, int r24, android.graphics.Rect r25, android.graphics.Rect r26, android.graphics.Rect r27, android.graphics.Rect r28, android.graphics.Rect r29, boolean r30, boolean r31, int r32) {
        /*
            Method dump skipped, instructions count: 1134
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.AppTransition.loadAnimation(android.view.WindowManager$LayoutParams, int, boolean, int, int, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, boolean, boolean, int):android.view.animation.Animation");
    }

    private Animation loadKeyguardExitAnimation(int transit) {
        if ((this.mNextAppTransitionFlags & 2) != 0) {
            return null;
        }
        boolean toShade = (this.mNextAppTransitionFlags & 1) != 0;
        return this.mService.mPolicy.createHiddenByKeyguardExit(transit == 21, toShade);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getAppStackClipMode() {
        if (this.mNextAppTransition == 20 || this.mNextAppTransition == 21) {
            return 1;
        }
        if (this.mNextAppTransition == 18 || this.mNextAppTransition == 19 || this.mNextAppTransitionType == 8) {
            return 2;
        }
        return 0;
    }

    public int getTransitFlags() {
        return this.mNextAppTransitionFlags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void postAnimationCallback() {
        if (this.mNextAppTransitionCallback != null) {
            this.mService.mH.sendMessage(this.mService.mH.obtainMessage(26, this.mNextAppTransitionCallback));
            this.mNextAppTransitionCallback = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim, IRemoteCallback startedCallback) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = 1;
            this.mNextAppTransitionPackage = packageName;
            this.mNextAppTransitionEnter = enterAnim;
            this.mNextAppTransitionExit = exitAnim;
            postAnimationCallback();
            this.mNextAppTransitionCallback = startedCallback;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth, int startHeight) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = 2;
            putDefaultNextAppTransitionCoordinates(startX, startY, startWidth, startHeight, null);
            postAnimationCallback();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionClipReveal(int startX, int startY, int startWidth, int startHeight) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = 8;
            putDefaultNextAppTransitionCoordinates(startX, startY, startWidth, startHeight, null);
            postAnimationCallback();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionThumb(GraphicBuffer srcThumb, int startX, int startY, IRemoteCallback startedCallback, boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = scaleUp ? 3 : 4;
            this.mNextAppTransitionScaleUp = scaleUp;
            putDefaultNextAppTransitionCoordinates(startX, startY, 0, 0, srcThumb);
            postAnimationCallback();
            this.mNextAppTransitionCallback = startedCallback;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionAspectScaledThumb(GraphicBuffer srcThumb, int startX, int startY, int targetWidth, int targetHeight, IRemoteCallback startedCallback, boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = scaleUp ? 5 : 6;
            this.mNextAppTransitionScaleUp = scaleUp;
            putDefaultNextAppTransitionCoordinates(startX, startY, targetWidth, targetHeight, srcThumb);
            postAnimationCallback();
            this.mNextAppTransitionCallback = startedCallback;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionMultiThumb(AppTransitionAnimationSpec[] specs, IRemoteCallback onAnimationStartedCallback, IRemoteCallback onAnimationFinishedCallback, boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = scaleUp ? 5 : 6;
            this.mNextAppTransitionScaleUp = scaleUp;
            if (specs != null) {
                for (int i = 0; i < specs.length; i++) {
                    AppTransitionAnimationSpec spec = specs[i];
                    if (spec != null) {
                        this.mNextAppTransitionAnimationsSpecs.put(spec.taskId, spec);
                        if (i == 0) {
                            Rect rect = spec.rect;
                            putDefaultNextAppTransitionCoordinates(rect.left, rect.top, rect.width(), rect.height(), spec.buffer);
                        }
                    }
                }
            }
            postAnimationCallback();
            this.mNextAppTransitionCallback = onAnimationStartedCallback;
            this.mAnimationFinishedCallback = onAnimationFinishedCallback;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionMultiThumbFuture(IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback callback, boolean scaleUp) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = scaleUp ? 5 : 6;
            this.mNextAppTransitionAnimationsSpecsFuture = specsFuture;
            this.mNextAppTransitionScaleUp = scaleUp;
            this.mNextAppTransitionFutureCallback = callback;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionRemote(RemoteAnimationAdapter remoteAnimationAdapter) {
        if (isTransitionSet()) {
            clear();
            this.mNextAppTransitionType = 10;
            this.mRemoteAnimationController = new RemoteAnimationController(this.mService, remoteAnimationAdapter, this.mService.mH);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overrideInPlaceAppTransition(String packageName, int anim) {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = 7;
            this.mNextAppTransitionPackage = packageName;
            this.mNextAppTransitionInPlace = anim;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void overridePendingAppTransitionStartCrossProfileApps() {
        if (canOverridePendingAppTransition()) {
            clear();
            this.mNextAppTransitionType = 9;
            postAnimationCallback();
        }
    }

    private boolean canOverridePendingAppTransition() {
        return isTransitionSet() && this.mNextAppTransitionType != 10;
    }

    private void fetchAppTransitionSpecsFromFuture() {
        if (this.mNextAppTransitionAnimationsSpecsFuture != null) {
            this.mNextAppTransitionAnimationsSpecsPending = true;
            final IAppTransitionAnimationSpecsFuture future = this.mNextAppTransitionAnimationsSpecsFuture;
            this.mNextAppTransitionAnimationsSpecsFuture = null;
            this.mDefaultExecutor.execute(new Runnable() { // from class: com.android.server.wm.-$$Lambda$AppTransition$CyT0POoZKxhd7Ybm_eVYXG4NCrI
                @Override // java.lang.Runnable
                public final void run() {
                    AppTransition.lambda$fetchAppTransitionSpecsFromFuture$0(AppTransition.this, future);
                }
            });
        }
    }

    public static /* synthetic */ void lambda$fetchAppTransitionSpecsFromFuture$0(AppTransition appTransition, IAppTransitionAnimationSpecsFuture future) {
        AppTransitionAnimationSpec[] specs = null;
        try {
            Binder.allowBlocking(future.asBinder());
            specs = future.get();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to fetch app transition specs: " + e);
        }
        synchronized (appTransition.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                appTransition.mNextAppTransitionAnimationsSpecsPending = false;
                appTransition.overridePendingAppTransitionMultiThumb(specs, appTransition.mNextAppTransitionFutureCallback, null, appTransition.mNextAppTransitionScaleUp);
                appTransition.mNextAppTransitionFutureCallback = null;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        appTransition.mService.requestTraversal();
    }

    public String toString() {
        return "mNextAppTransition=" + appTransitionToString(this.mNextAppTransition);
    }

    public static String appTransitionToString(int transition) {
        switch (transition) {
            case -1:
                return "TRANSIT_UNSET";
            case 0:
                return "TRANSIT_NONE";
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 17:
            default:
                return "<UNKNOWN: " + transition + ">";
            case 6:
                return "TRANSIT_ACTIVITY_OPEN";
            case 7:
                return "TRANSIT_ACTIVITY_CLOSE";
            case 8:
                return "TRANSIT_TASK_OPEN";
            case 9:
                return "TRANSIT_TASK_CLOSE";
            case 10:
                return "TRANSIT_TASK_TO_FRONT";
            case 11:
                return "TRANSIT_TASK_TO_BACK";
            case 12:
                return "TRANSIT_WALLPAPER_CLOSE";
            case 13:
                return "TRANSIT_WALLPAPER_OPEN";
            case 14:
                return "TRANSIT_WALLPAPER_INTRA_OPEN";
            case 15:
                return "TRANSIT_WALLPAPER_INTRA_CLOSE";
            case 16:
                return "TRANSIT_TASK_OPEN_BEHIND";
            case 18:
                return "TRANSIT_ACTIVITY_RELAUNCH";
            case WindowManagerService.H.REPORT_WINDOWS_CHANGE /* 19 */:
                return "TRANSIT_DOCK_TASK_FROM_RECENTS";
            case 20:
                return "TRANSIT_KEYGUARD_GOING_AWAY";
            case BackupHandler.MSG_OP_COMPLETE /* 21 */:
                return "TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER";
            case WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE /* 22 */:
                return "TRANSIT_KEYGUARD_OCCLUDE";
            case WindowManagerService.H.BOOT_TIMEOUT /* 23 */:
                return "TRANSIT_KEYGUARD_UNOCCLUDE";
            case 24:
                return "TRANSIT_TRANSLUCENT_ACTIVITY_OPEN";
            case WindowManagerService.H.SHOW_STRICT_MODE_VIOLATION /* 25 */:
                return "TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE";
            case WindowManagerService.H.DO_ANIMATION_CALLBACK /* 26 */:
                return "TRANSIT_CRASHING_ACTIVITY_CLOSE";
        }
    }

    private String appStateToString() {
        switch (this.mAppTransitionState) {
            case 0:
                return "APP_STATE_IDLE";
            case 1:
                return "APP_STATE_READY";
            case 2:
                return "APP_STATE_RUNNING";
            case 3:
                return "APP_STATE_TIMEOUT";
            default:
                return "unknown state=" + this.mAppTransitionState;
        }
    }

    private String transitTypeToString() {
        switch (this.mNextAppTransitionType) {
            case 0:
                return "NEXT_TRANSIT_TYPE_NONE";
            case 1:
                return "NEXT_TRANSIT_TYPE_CUSTOM";
            case 2:
                return "NEXT_TRANSIT_TYPE_SCALE_UP";
            case 3:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_UP";
            case 4:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_SCALE_DOWN";
            case 5:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_UP";
            case 6:
                return "NEXT_TRANSIT_TYPE_THUMBNAIL_ASPECT_SCALE_DOWN";
            case 7:
                return "NEXT_TRANSIT_TYPE_CUSTOM_IN_PLACE";
            case 8:
            default:
                return "unknown type=" + this.mNextAppTransitionType;
            case 9:
                return "NEXT_TRANSIT_TYPE_OPEN_CROSS_PROFILE_APPS";
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1159641169921L, this.mAppTransitionState);
        proto.write(1159641169922L, this.mLastUsedAppTransition);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println(this);
        pw.print(prefix);
        pw.print("mAppTransitionState=");
        pw.println(appStateToString());
        if (this.mNextAppTransitionType != 0) {
            pw.print(prefix);
            pw.print("mNextAppTransitionType=");
            pw.println(transitTypeToString());
        }
        switch (this.mNextAppTransitionType) {
            case 1:
                pw.print(prefix);
                pw.print("mNextAppTransitionPackage=");
                pw.println(this.mNextAppTransitionPackage);
                pw.print(prefix);
                pw.print("mNextAppTransitionEnter=0x");
                pw.print(Integer.toHexString(this.mNextAppTransitionEnter));
                pw.print(" mNextAppTransitionExit=0x");
                pw.println(Integer.toHexString(this.mNextAppTransitionExit));
                break;
            case 2:
                getDefaultNextAppTransitionStartRect(this.mTmpRect);
                pw.print(prefix);
                pw.print("mNextAppTransitionStartX=");
                pw.print(this.mTmpRect.left);
                pw.print(" mNextAppTransitionStartY=");
                pw.println(this.mTmpRect.top);
                pw.print(prefix);
                pw.print("mNextAppTransitionStartWidth=");
                pw.print(this.mTmpRect.width());
                pw.print(" mNextAppTransitionStartHeight=");
                pw.println(this.mTmpRect.height());
                break;
            case 3:
            case 4:
            case 5:
            case 6:
                pw.print(prefix);
                pw.print("mDefaultNextAppTransitionAnimationSpec=");
                pw.println(this.mDefaultNextAppTransitionAnimationSpec);
                pw.print(prefix);
                pw.print("mNextAppTransitionAnimationsSpecs=");
                pw.println(this.mNextAppTransitionAnimationsSpecs);
                pw.print(prefix);
                pw.print("mNextAppTransitionScaleUp=");
                pw.println(this.mNextAppTransitionScaleUp);
                break;
            case 7:
                pw.print(prefix);
                pw.print("mNextAppTransitionPackage=");
                pw.println(this.mNextAppTransitionPackage);
                pw.print(prefix);
                pw.print("mNextAppTransitionInPlace=0x");
                pw.print(Integer.toHexString(this.mNextAppTransitionInPlace));
                break;
        }
        if (this.mNextAppTransitionCallback != null) {
            pw.print(prefix);
            pw.print("mNextAppTransitionCallback=");
            pw.println(this.mNextAppTransitionCallback);
        }
        if (this.mLastUsedAppTransition != 0) {
            pw.print(prefix);
            pw.print("mLastUsedAppTransition=");
            pw.println(appTransitionToString(this.mLastUsedAppTransition));
            pw.print(prefix);
            pw.print("mLastOpeningApp=");
            pw.println(this.mLastOpeningApp);
            pw.print(prefix);
            pw.print("mLastClosingApp=");
            pw.println(this.mLastClosingApp);
        }
    }

    public void setCurrentUser(int newUserId) {
        this.mCurrentUserId = newUserId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean prepareAppTransitionLocked(int transit, boolean alwaysKeepCurrent, int flags, boolean forceOverride) {
        if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
            Slog.v(TAG, "Prepare app transition: transit=" + appTransitionToString(transit) + " " + this + " alwaysKeepCurrent=" + alwaysKeepCurrent + " Callers=" + Debug.getCallers(3));
        }
        boolean allowSetCrashing = !isKeyguardTransit(this.mNextAppTransition) && transit == 26;
        if (forceOverride || isKeyguardTransit(transit) || !isTransitionSet() || this.mNextAppTransition == 0 || allowSetCrashing) {
            setAppTransition(transit, flags);
        } else if (!alwaysKeepCurrent && !isKeyguardTransit(this.mNextAppTransition) && this.mNextAppTransition != 26) {
            if (transit == 8 && isTransitionEqual(9)) {
                setAppTransition(transit, flags);
            } else if (transit == 6 && isTransitionEqual(7)) {
                setAppTransition(transit, flags);
            } else if (isTaskTransit(transit) && isActivityTransit(this.mNextAppTransition)) {
                setAppTransition(transit, flags);
            }
        }
        boolean prepared = prepare();
        if (isTransitionSet()) {
            this.mService.mH.removeMessages(13);
            this.mService.mH.sendEmptyMessageDelayed(13, APP_TRANSITION_TIMEOUT_MS);
        }
        return prepared;
    }

    public static boolean isKeyguardGoingAwayTransit(int transit) {
        return transit == 20 || transit == 21;
    }

    private static boolean isKeyguardTransit(int transit) {
        return isKeyguardGoingAwayTransit(transit) || transit == 22 || transit == 23;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isTaskTransit(int transit) {
        return isTaskOpenTransit(transit) || transit == 9 || transit == 11 || transit == 17;
    }

    private static boolean isTaskOpenTransit(int transit) {
        return transit == 8 || transit == 16 || transit == 10;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isActivityTransit(int transit) {
        return transit == 6 || transit == 7 || transit == 18;
    }

    private boolean shouldScaleDownThumbnailTransition(int uiMode, int orientation) {
        return this.mGridLayoutRecentsEnabled || orientation == 1;
    }
}
