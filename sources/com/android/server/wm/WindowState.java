package com.android.server.wm;

import android.app.AppOpsManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IApplicationToken;
import android.view.IWindow;
import android.view.IWindowFocusObserver;
import android.view.IWindowId;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.am.ActivityManagerService;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.usb.descriptors.UsbACInterface;
import com.android.server.wm.LocalAnimationAdapter;
import com.android.server.wm.utils.WmDisplayCutout;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class WindowState extends WindowContainer<WindowState> implements WindowManagerPolicy.WindowState {
    private static final float DEFAULT_DIM_AMOUNT_DEAD_WINDOW = 0.5f;
    static final int EXCLUSION_LEFT = 0;
    static final int EXCLUSION_RIGHT = 1;
    static final int LEGACY_POLICY_VISIBILITY = 1;
    static final int MINIMUM_VISIBLE_HEIGHT_IN_DP = 32;
    static final int MINIMUM_VISIBLE_WIDTH_IN_DP = 48;
    private static final int POLICY_VISIBILITY_ALL = 3;
    static final int RESIZE_HANDLE_WIDTH_IN_DP = 30;
    static final String TAG = "WindowManager";
    private static final int VISIBLE_FOR_USER = 2;
    private static final StringBuilder sTmpSB = new StringBuilder();
    private static final Comparator<WindowState> sWindowSubLayerComparator = new Comparator<WindowState>() { // from class: com.android.server.wm.WindowState.1
        @Override // java.util.Comparator
        public int compare(WindowState w1, WindowState w2) {
            int layer1 = w1.mSubLayer;
            int layer2 = w2.mSubLayer;
            if (layer1 >= layer2) {
                if (layer1 == layer2 && layer2 < 0) {
                    return -1;
                }
                return 1;
            }
            return -1;
        }
    };
    private boolean mAnimateReplacingWindow;
    boolean mAnimatingExit;
    boolean mAppDied;
    boolean mAppFreezing;
    final int mAppOp;
    private boolean mAppOpVisibility;
    AppWindowToken mAppToken;
    final WindowManager.LayoutParams mAttrs;
    final int mBaseLayer;
    final IWindow mClient;
    private InputChannel mClientChannel;
    final Context mContext;
    private DeadWindowEventReceiver mDeadWindowEventReceiver;
    final DeathRecipient mDeathRecipient;
    boolean mDestroying;
    private boolean mDragResizing;
    private boolean mDragResizingChangeReported;
    private PowerManager.WakeLock mDrawLock;
    private boolean mDrawnStateEvaluated;
    private final ArraySet<DisplayContent> mEmbeddedDisplayContents;
    private final List<Rect> mExclusionRects;
    long mFinishSeamlessRotateFrameNumber;
    private RemoteCallbackList<IWindowFocusObserver> mFocusCallbacks;
    private boolean mForceHideNonSystemOverlayWindow;
    final boolean mForceSeamlesslyRotate;
    private long mFrameNumber;
    protected volatile Rect mFromBounds;
    final Rect mGivenContentInsets;
    boolean mGivenInsetsPending;
    final Region mGivenTouchableRegion;
    final Rect mGivenVisibleInsets;
    float mGlobalScale;
    float mHScale;
    boolean mHasSurface;
    boolean mHaveFrame;
    boolean mHidden;
    private boolean mHiddenWhileSuspended;
    boolean mInRelayout;
    InputChannel mInputChannel;
    final InputWindowHandle mInputWindowHandle;
    private final Rect mInsetFrame;
    private InsetsSourceProvider mInsetProvider;
    float mInvGlobalScale;
    private boolean mIsChildWindow;
    private boolean mIsDimming;
    private final boolean mIsFloatingLayer;
    final boolean mIsImWindow;
    final boolean mIsWallpaper;
    private boolean mLastConfigReportedToClient;
    private final long[] mLastExclusionLogUptimeMillis;
    int mLastFreezeDuration;
    private final int[] mLastGrantedExclusionHeight;
    float mLastHScale;
    final Rect mLastRelayoutContentInsets;
    private final MergedConfiguration mLastReportedConfiguration;
    private final Point mLastReportedDisplayOffset;
    private final int[] mLastRequestedExclusionHeight;
    private int mLastRequestedHeight;
    private int mLastRequestedWidth;
    private boolean mLastShownChangedReported;
    final Rect mLastSurfaceInsets;
    private CharSequence mLastTitle;
    float mLastVScale;
    int mLastVisibleLayoutRotation;
    int mLayer;
    final boolean mLayoutAttached;
    boolean mLayoutNeeded;
    int mLayoutSeq;
    boolean mLegacyPolicyVisibilityAfterAnim;
    private boolean mMovedByResize;
    boolean mObscured;
    private boolean mOrientationChangeTimedOut;
    private boolean mOrientationChanging;
    final boolean mOwnerCanAddInternalSystemWindow;
    final int mOwnerUid;
    SeamlessRotator mPendingSeamlessRotate;
    boolean mPermanentlyHidden;
    final int mPid;
    final WindowManagerPolicy mPolicy;
    private int mPolicyVisibility;
    private PowerManagerWrapper mPowerManagerWrapper;
    boolean mRelayoutCalled;
    boolean mRemoveOnExit;
    boolean mRemoved;
    private WindowState mReplacementWindow;
    private boolean mReplacingRemoveRequested;
    boolean mReportOrientationChanged;
    int mRequestedHeight;
    int mRequestedWidth;
    private int mResizeMode;
    protected volatile boolean mResizedAnimating;
    boolean mResizedWhileGone;
    boolean mSeamlesslyRotated;
    int mSeq;
    final Session mSession;
    private boolean mShowToOwnerOnly;
    boolean mSkipEnterAnimationForSeamlessReplacement;
    private String mStringNameCache;
    final int mSubLayer;
    private final Point mSurfacePosition;
    int mSystemUiVisibility;
    private TapExcludeRegionHolder mTapExcludeRegionHolder;
    private final Configuration mTempConfiguration;
    final Matrix mTmpMatrix;
    private final Point mTmpPoint;
    private final Rect mTmpRect;
    protected volatile Rect mToBounds;
    WindowToken mToken;
    int mTouchableInsets;
    protected volatile boolean mUseResizedAnimator;
    float mVScale;
    int mViewVisibility;
    int mWallpaperDisplayOffsetX;
    int mWallpaperDisplayOffsetY;
    boolean mWallpaperVisible;
    float mWallpaperX;
    float mWallpaperXStep;
    float mWallpaperY;
    float mWallpaperYStep;
    private boolean mWasExiting;
    private boolean mWasVisibleBeforeClientHidden;
    boolean mWillReplaceWindow;
    final WindowStateAnimator mWinAnimator;
    private final WindowFrames mWindowFrames;
    final WindowId mWindowId;
    boolean mWindowRemovalAllowed;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public interface PowerManagerWrapper {
        boolean isInteractive();

        void wakeUp(long j, int i, String str);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void seamlesslyRotateIfAllowed(SurfaceControl.Transaction transaction, int oldRotation, int rotation, boolean requested) {
        if (!isVisibleNow() || this.mIsWallpaper) {
            return;
        }
        SeamlessRotator seamlessRotator = this.mPendingSeamlessRotate;
        if (seamlessRotator != null) {
            oldRotation = seamlessRotator.getOldRotation();
        }
        if (this.mForceSeamlesslyRotate || requested) {
            this.mPendingSeamlessRotate = new SeamlessRotator(oldRotation, rotation, getDisplayInfo());
            this.mPendingSeamlessRotate.unrotate(transaction, this);
            this.mWmService.markForSeamlessRotation(this, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishSeamlessRotation(boolean timeout) {
        SeamlessRotator seamlessRotator = this.mPendingSeamlessRotate;
        if (seamlessRotator != null) {
            seamlessRotator.finish(this, timeout);
            this.mFinishSeamlessRotateFrameNumber = getFrameNumber();
            this.mPendingSeamlessRotate = null;
            this.mWmService.markForSeamlessRotation(this, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<Rect> getSystemGestureExclusion() {
        return this.mExclusionRects;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setSystemGestureExclusion(List<Rect> exclusionRects) {
        if (this.mExclusionRects.equals(exclusionRects)) {
            return false;
        }
        this.mExclusionRects.clear();
        this.mExclusionRects.addAll(exclusionRects);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isImplicitlyExcludingAllSystemGestures() {
        AppWindowToken appWindowToken;
        boolean immersiveSticky = (this.mSystemUiVisibility & UsbACInterface.FORMAT_II_AC3) == 4098;
        return immersiveSticky && this.mWmService.mSystemGestureExcludedByPreQStickyImmersive && (appWindowToken = this.mAppToken) != null && appWindowToken.mTargetSdk < 29;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastExclusionHeights(int side, int requested, int granted) {
        boolean changed = (this.mLastGrantedExclusionHeight[side] == granted && this.mLastRequestedExclusionHeight[side] == requested) ? false : true;
        if (changed) {
            if (this.mLastShownChangedReported) {
                logExclusionRestrictions(side);
            }
            this.mLastGrantedExclusionHeight[side] = granted;
            this.mLastRequestedExclusionHeight[side] = requested;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState(final WindowManagerService service, Session s, IWindow c, WindowToken token, WindowState parentWindow, int appOp, int seq, WindowManager.LayoutParams a, int viewVisibility, int ownerId, boolean ownerCanAddInternalSystemWindow, int pid) {
        this(service, s, c, token, parentWindow, appOp, seq, a, viewVisibility, ownerId, ownerCanAddInternalSystemWindow, new PowerManagerWrapper() { // from class: com.android.server.wm.WindowState.2
            @Override // com.android.server.wm.WindowState.PowerManagerWrapper
            public void wakeUp(long time, int reason, String details) {
                WindowManagerService.this.mPowerManager.wakeUp(time, reason, details);
            }

            @Override // com.android.server.wm.WindowState.PowerManagerWrapper
            public boolean isInteractive() {
                return WindowManagerService.this.mPowerManager.isInteractive();
            }
        }, pid);
    }

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token, WindowState parentWindow, int appOp, int seq, WindowManager.LayoutParams a, int viewVisibility, int ownerId, boolean ownerCanAddInternalSystemWindow, PowerManagerWrapper powerManagerWrapper, int pid) {
        super(service);
        this.mAttrs = new WindowManager.LayoutParams();
        this.mPolicyVisibility = 3;
        this.mLegacyPolicyVisibilityAfterAnim = true;
        this.mAppOpVisibility = true;
        this.mHidden = true;
        this.mDragResizingChangeReported = true;
        this.mLayoutSeq = -1;
        this.mEmbeddedDisplayContents = new ArraySet<>();
        this.mLastReportedConfiguration = new MergedConfiguration();
        this.mTempConfiguration = new Configuration();
        this.mLastRelayoutContentInsets = new Rect();
        this.mGivenContentInsets = new Rect();
        this.mGivenVisibleInsets = new Rect();
        this.mGivenTouchableRegion = new Region();
        this.mTouchableInsets = 0;
        this.mGlobalScale = 1.0f;
        this.mInvGlobalScale = 1.0f;
        this.mHScale = 1.0f;
        this.mVScale = 1.0f;
        this.mLastHScale = 1.0f;
        this.mLastVScale = 1.0f;
        this.mTmpMatrix = new Matrix();
        this.mWindowFrames = new WindowFrames();
        this.mInsetFrame = new Rect();
        this.mExclusionRects = new ArrayList();
        this.mLastRequestedExclusionHeight = new int[]{0, 0};
        this.mLastGrantedExclusionHeight = new int[]{0, 0};
        this.mLastExclusionLogUptimeMillis = new long[]{0, 0};
        this.mWallpaperX = -1.0f;
        this.mWallpaperY = -1.0f;
        this.mWallpaperXStep = -1.0f;
        this.mWallpaperYStep = -1.0f;
        this.mWallpaperDisplayOffsetX = Integer.MIN_VALUE;
        this.mWallpaperDisplayOffsetY = Integer.MIN_VALUE;
        this.mLastVisibleLayoutRotation = -1;
        this.mHasSurface = false;
        this.mWillReplaceWindow = false;
        this.mReplacingRemoveRequested = false;
        this.mAnimateReplacingWindow = false;
        this.mReplacementWindow = null;
        this.mSkipEnterAnimationForSeamlessReplacement = false;
        this.mTmpRect = new Rect();
        this.mTmpPoint = new Point();
        this.mLastReportedDisplayOffset = new Point();
        this.mResizedWhileGone = false;
        this.mSeamlesslyRotated = false;
        this.mLastSurfaceInsets = new Rect();
        this.mSurfacePosition = new Point();
        this.mFrameNumber = -1L;
        this.mResizedAnimating = false;
        this.mUseResizedAnimator = false;
        this.mIsDimming = false;
        this.mSession = s;
        this.mClient = c;
        this.mAppOp = appOp;
        this.mToken = token;
        this.mAppToken = this.mToken.asAppWindowToken();
        this.mOwnerUid = ownerId;
        this.mPid = pid;
        this.mOwnerCanAddInternalSystemWindow = ownerCanAddInternalSystemWindow;
        this.mWindowId = new WindowId();
        this.mAttrs.copyFrom(a);
        this.mLastSurfaceInsets.set(this.mAttrs.surfaceInsets);
        this.mViewVisibility = viewVisibility;
        this.mPolicy = this.mWmService.mPolicy;
        this.mContext = this.mWmService.mContext;
        DeathRecipient deathRecipient = new DeathRecipient();
        this.mSeq = seq;
        this.mPowerManagerWrapper = powerManagerWrapper;
        this.mForceSeamlesslyRotate = token.mRoundedCornerOverlay;
        if (WindowManagerService.localLOGV) {
            Slog.v(TAG, "Window " + this + " client=" + c.asBinder() + " token=" + token + " (" + this.mAttrs.token + ") params=" + a);
        }
        try {
            c.asBinder().linkToDeath(deathRecipient, 0);
            this.mDeathRecipient = deathRecipient;
            if (this.mAttrs.type < 1000 || this.mAttrs.type > 1999) {
                this.mBaseLayer = (this.mPolicy.getWindowLayerLw(this) * 10000) + 1000;
                this.mSubLayer = 0;
                this.mIsChildWindow = false;
                this.mLayoutAttached = false;
                this.mIsImWindow = this.mAttrs.type == 2011 || this.mAttrs.type == 2012;
                this.mIsWallpaper = this.mAttrs.type == 2013;
            } else {
                this.mBaseLayer = (this.mPolicy.getWindowLayerLw(parentWindow) * 10000) + 1000;
                this.mSubLayer = this.mPolicy.getSubWindowLayerFromTypeLw(a.type);
                this.mIsChildWindow = true;
                if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                    Slog.v(TAG, "Adding " + this + " to " + parentWindow);
                }
                parentWindow.addChild(this, sWindowSubLayerComparator);
                this.mLayoutAttached = this.mAttrs.type != 1003;
                this.mIsImWindow = parentWindow.mAttrs.type == 2011 || parentWindow.mAttrs.type == 2012;
                this.mIsWallpaper = parentWindow.mAttrs.type == 2013;
            }
            this.mIsFloatingLayer = this.mIsImWindow || this.mIsWallpaper;
            AppWindowToken appWindowToken = this.mAppToken;
            if (appWindowToken != null && appWindowToken.mShowForAllUsers) {
                this.mAttrs.flags |= DumpState.DUMP_FROZEN;
            }
            this.mWinAnimator = new WindowStateAnimator(this);
            this.mWinAnimator.mAlpha = a.alpha;
            this.mRequestedWidth = 0;
            this.mRequestedHeight = 0;
            this.mLastRequestedWidth = 0;
            this.mLastRequestedHeight = 0;
            this.mLayer = 0;
            AppWindowToken appWindowToken2 = this.mAppToken;
            this.mInputWindowHandle = new InputWindowHandle(appWindowToken2 != null ? appWindowToken2.mInputApplicationHandle : null, c, getDisplayId());
        } catch (RemoteException e) {
            this.mDeathRecipient = null;
            this.mIsChildWindow = false;
            this.mLayoutAttached = false;
            this.mIsImWindow = false;
            this.mIsWallpaper = false;
            this.mIsFloatingLayer = false;
            this.mBaseLayer = 0;
            this.mSubLayer = 0;
            this.mInputWindowHandle = null;
            this.mWinAnimator = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void attach() {
        if (WindowManagerService.localLOGV) {
            Slog.v(TAG, "Attaching " + this + " token=" + this.mToken);
        }
        this.mSession.windowAddedLocked(this.mAttrs.packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean inSizeCompatMode() {
        AppWindowToken appWindowToken;
        return ((this.mAttrs.privateFlags & 128) == 0 && ((appWindowToken = this.mAppToken) == null || !appWindowToken.inSizeCompatMode() || this.mAttrs.type == 3)) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getDrawnStateEvaluated() {
        return this.mDrawnStateEvaluated;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDrawnStateEvaluated(boolean evaluated) {
        this.mDrawnStateEvaluated = evaluated;
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    void onParentChanged() {
        super.onParentChanged();
        setDrawnStateEvaluated(false);
        getDisplayContent().reapplyMagnificationSpec();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public int getOwningUid() {
        return this.mOwnerUid;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public String getOwningPackage() {
        return this.mAttrs.packageName;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean canAddInternalSystemWindow() {
        return this.mOwnerCanAddInternalSystemWindow;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean canAcquireSleepToken() {
        return this.mSession.mCanAcquireSleepToken;
    }

    private void subtractInsets(Rect frame, Rect layoutFrame, Rect insetFrame, Rect displayFrame) {
        int left = Math.max(0, insetFrame.left - Math.max(layoutFrame.left, displayFrame.left));
        int top = Math.max(0, insetFrame.top - Math.max(layoutFrame.top, displayFrame.top));
        int right = Math.max(0, Math.min(layoutFrame.right, displayFrame.right) - insetFrame.right);
        int bottom = Math.max(0, Math.min(layoutFrame.bottom, displayFrame.bottom) - insetFrame.bottom);
        frame.inset(left, top, right, bottom);
    }

    @Override // com.android.server.wm.WindowContainer
    public Rect getDisplayedBounds() {
        Task task = getTask();
        if (task != null) {
            Rect bounds = task.getOverrideDisplayedBounds();
            if (!bounds.isEmpty()) {
                return bounds;
            }
        }
        return super.getDisplayedBounds();
    }

    /* JADX WARN: Removed duplicated region for block: B:106:0x02c5  */
    /* JADX WARN: Removed duplicated region for block: B:110:0x02f8  */
    /* JADX WARN: Removed duplicated region for block: B:116:0x03e8  */
    /* JADX WARN: Removed duplicated region for block: B:117:0x0402  */
    /* JADX WARN: Removed duplicated region for block: B:120:0x0439  */
    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void computeFrameLw() {
        /*
            Method dump skipped, instructions count: 1246
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowState.computeFrameLw():void");
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public Rect getBounds() {
        Rect bounds = getOverrideBounds();
        if (xpWindowManager.rectValid(bounds)) {
            return bounds;
        }
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null) {
            return appWindowToken.getBounds();
        }
        return super.getBounds();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getFrameLw() {
        return this.mWindowFrames.mFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getDisplayFrameLw() {
        return this.mWindowFrames.mDisplayFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getOverscanFrameLw() {
        return this.mWindowFrames.mOverscanFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getContentFrameLw() {
        return this.mWindowFrames.mContentFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getVisibleFrameLw() {
        return this.mWindowFrames.mVisibleFrame;
    }

    Rect getStableFrameLw() {
        return this.mWindowFrames.mStableFrame;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getDecorFrame() {
        return this.mWindowFrames.mDecorFrame;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getParentFrame() {
        return this.mWindowFrames.mParentFrame;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getContainingFrame() {
        return this.mWindowFrames.mContainingFrame;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WmDisplayCutout getWmDisplayCutout() {
        return this.mWindowFrames.mDisplayCutout;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getCompatFrame(Rect outFrame) {
        outFrame.set(this.mWindowFrames.mCompatFrame);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getCompatFrameSize(Rect outFrame) {
        outFrame.set(0, 0, this.mWindowFrames.mCompatFrame.width(), this.mWindowFrames.mCompatFrame.height());
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean getGivenInsetsPendingLw() {
        return this.mGivenInsetsPending;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getGivenContentInsetsLw() {
        return this.mGivenContentInsets;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getGivenVisibleInsetsLw() {
        return this.mGivenVisibleInsets;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public WindowManager.LayoutParams getAttrs() {
        return this.mAttrs;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean getNeedsMenuLw(WindowManagerPolicy.WindowState bottom) {
        return getDisplayContent().getNeedsMenu(this, bottom);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public int getSystemUiVisibility() {
        return this.mSystemUiVisibility;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public int getSurfaceLayer() {
        return this.mLayer;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public int getBaseType() {
        return getTopParentWindow().mAttrs.type;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public IApplicationToken getAppToken() {
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null) {
            return appWindowToken.appToken;
        }
        return null;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isVoiceInteraction() {
        AppWindowToken appWindowToken = this.mAppToken;
        return appWindowToken != null && appWindowToken.mVoiceInteraction;
    }

    boolean setReportResizeHints() {
        return this.mWindowFrames.setReportResizeHints();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateResizingWindowIfNeeded() {
        WindowStateAnimator winAnimator = this.mWinAnimator;
        if (!this.mHasSurface || getDisplayContent().mLayoutSeq != this.mLayoutSeq || isGoneForLayoutLw()) {
            return;
        }
        Task task = getTask();
        if (task != null && task.mStack.isAnimatingBounds()) {
            return;
        }
        boolean didFrameInsetsChange = setReportResizeHints();
        boolean configChanged = !isLastConfigReportedToClient();
        if (WindowManagerDebugConfig.DEBUG_CONFIGURATION && configChanged) {
            Slog.v(TAG, "Win " + this + " config changed: " + getConfiguration());
        }
        boolean dragResizingChanged = isDragResizeChanged() && !isDragResizingChangeReported();
        if (WindowManagerService.localLOGV) {
            Slog.v(TAG, "Resizing " + this + ": configChanged=" + configChanged + " dragResizingChanged=" + dragResizingChanged + " last=" + this.mWindowFrames.mLastFrame + " frame=" + this.mWindowFrames.mFrame);
        }
        this.mWindowFrames.mLastFrame.set(this.mWindowFrames.mFrame);
        if (didFrameInsetsChange || winAnimator.mSurfaceResized || configChanged || dragResizingChanged || this.mReportOrientationChanged) {
            if (WindowManagerDebugConfig.DEBUG_RESIZE || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Resize reasons for w=" + this + ":  " + this.mWindowFrames.getInsetsChangedInfo() + " surfaceResized=" + winAnimator.mSurfaceResized + " configChanged=" + configChanged + " dragResizingChanged=" + dragResizingChanged + " reportOrientationChanged=" + this.mReportOrientationChanged);
            }
            AppWindowToken appWindowToken = this.mAppToken;
            if (appWindowToken != null && this.mAppDied) {
                appWindowToken.removeDeadWindows();
                return;
            }
            updateLastInsetValues();
            this.mWmService.makeWindowFreezingScreenIfNeededLocked(this);
            if (getOrientationChanging() || dragResizingChanged) {
                if (WindowManagerDebugConfig.DEBUG_ANIM || WindowManagerDebugConfig.DEBUG_ORIENTATION || WindowManagerDebugConfig.DEBUG_RESIZE) {
                    Slog.v(TAG, "Orientation or resize start waiting for draw, mDrawState=DRAW_PENDING in " + this + ", surfaceController " + winAnimator.mSurfaceController);
                }
                winAnimator.mDrawState = 1;
                AppWindowToken appWindowToken2 = this.mAppToken;
                if (appWindowToken2 != null) {
                    appWindowToken2.clearAllDrawn();
                }
            }
            if (!this.mWmService.mResizingWindows.contains(this)) {
                if (WindowManagerDebugConfig.DEBUG_RESIZE || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Resizing window " + this);
                }
                this.mWmService.mResizingWindows.add(this);
            }
        } else if (getOrientationChanging() && isDrawnLw()) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Orientation not waiting for draw in " + this + ", surfaceController " + winAnimator.mSurfaceController);
            }
            setOrientationChanging(false);
            this.mLastFreezeDuration = (int) (SystemClock.elapsedRealtime() - this.mWmService.mDisplayFreezeTime);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getOrientationChanging() {
        return ((!this.mOrientationChanging && (!isVisible() || getConfiguration().orientation == getLastReportedConfiguration().orientation)) || this.mSeamlesslyRotated || this.mOrientationChangeTimedOut) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOrientationChanging(boolean changing) {
        this.mOrientationChanging = changing;
        this.mOrientationChangeTimedOut = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void orientationChangeTimedOut() {
        this.mOrientationChangeTimedOut = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public DisplayContent getDisplayContent() {
        return this.mToken.getDisplayContent();
    }

    @Override // com.android.server.wm.WindowContainer
    void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        if (dc != null && this.mInputWindowHandle.displayId != dc.getDisplayId()) {
            this.mLayoutSeq = dc.mLayoutSeq - 1;
            this.mInputWindowHandle.displayId = dc.getDisplayId();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayInfo getDisplayInfo() {
        DisplayContent displayContent = getDisplayContent();
        if (displayContent != null) {
            return displayContent.getDisplayInfo();
        }
        return null;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public int getDisplayId() {
        DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return -1;
        }
        return displayContent.getDisplayId();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Task getTask() {
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null) {
            return appWindowToken.getTask();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TaskStack getStack() {
        Task task = getTask();
        if (task != null && task.mStack != null) {
            return task.mStack;
        }
        DisplayContent dc = getDisplayContent();
        if (this.mAttrs.type < 2000 || dc == null) {
            return null;
        }
        return dc.getHomeStack();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getVisibleBounds(Rect bounds) {
        Task task = getTask();
        boolean intersectWithStackBounds = task != null && task.cropWindowsToStackBounds();
        bounds.setEmpty();
        this.mTmpRect.setEmpty();
        if (intersectWithStackBounds) {
            TaskStack stack = task.mStack;
            if (stack != null) {
                stack.getDimBounds(this.mTmpRect);
            } else {
                intersectWithStackBounds = false;
            }
        }
        bounds.set(this.mWindowFrames.mVisibleFrame);
        if (intersectWithStackBounds) {
            bounds.intersect(this.mTmpRect);
        }
        if (bounds.isEmpty()) {
            bounds.set(this.mWindowFrames.mFrame);
            if (intersectWithStackBounds) {
                bounds.intersect(this.mTmpRect);
            }
        }
    }

    public long getInputDispatchingTimeoutNanos() {
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null) {
            return appWindowToken.mInputDispatchingTimeoutNanos;
        }
        return 5000000000L;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean hasAppShownWindows() {
        AppWindowToken appWindowToken = this.mAppToken;
        return appWindowToken != null && (appWindowToken.firstWindowDrawn || this.mAppToken.startingDisplayed);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isIdentityMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
        return dsdx >= 0.99999f && dsdx <= 1.00001f && dtdy >= 0.99999f && dtdy <= 1.00001f && dtdx >= -1.0E-6f && dtdx <= 1.0E-6f && dsdy >= -1.0E-6f && dsdy <= 1.0E-6f;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prelayout() {
        if (inSizeCompatMode()) {
            this.mGlobalScale = this.mToken.getSizeCompatScale();
            this.mInvGlobalScale = 1.0f / this.mGlobalScale;
            return;
        }
        this.mInvGlobalScale = 1.0f;
        this.mGlobalScale = 1.0f;
    }

    @Override // com.android.server.wm.WindowContainer
    boolean hasContentToDisplay() {
        if (!this.mAppFreezing && isDrawnLw()) {
            if (this.mViewVisibility != 0) {
                if (isAnimating() && !getDisplayContent().mAppTransition.isTransitionSet()) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return super.hasContentToDisplay();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean isVisible() {
        InsetsSourceProvider insetsSourceProvider;
        return wouldBeVisibleIfPolicyIgnored() && isVisibleByPolicy() && ((insetsSourceProvider = this.mInsetProvider) == null || insetsSourceProvider.isClientVisible());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isVisibleByPolicy() {
        return (this.mPolicyVisibility & 3) == 3;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearPolicyVisibilityFlag(int policyVisibilityFlag) {
        this.mPolicyVisibility &= ~policyVisibilityFlag;
        this.mWmService.scheduleAnimationLocked();
    }

    void setPolicyVisibilityFlag(int policyVisibilityFlag) {
        this.mPolicyVisibility |= policyVisibilityFlag;
        this.mWmService.scheduleAnimationLocked();
    }

    private boolean isLegacyPolicyVisibility() {
        return (this.mPolicyVisibility & 1) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean wouldBeVisibleIfPolicyIgnored() {
        return (!this.mHasSurface || isParentWindowHidden() || this.mAnimatingExit || this.mDestroying || (this.mIsWallpaper && !this.mWallpaperVisible)) ? false : true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isVisibleLw() {
        return isVisible();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isWinVisibleLw() {
        AppWindowToken appWindowToken = this.mAppToken;
        return (appWindowToken == null || !appWindowToken.hiddenRequested || this.mAppToken.isSelfAnimating()) && isVisible();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isVisibleNow() {
        return (!this.mToken.isHidden() || this.mAttrs.type == 3) && isVisible();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isPotentialDragTarget() {
        return (!isVisibleNow() || this.mRemoved || this.mInputChannel == null || this.mInputWindowHandle == null) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isVisibleOrAdding() {
        AppWindowToken atoken = this.mAppToken;
        return (this.mHasSurface || (!this.mRelayoutCalled && this.mViewVisibility == 0)) && isVisibleByPolicy() && !isParentWindowHidden() && !((atoken != null && atoken.hiddenRequested) || this.mAnimatingExit || this.mDestroying);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isOnScreen() {
        if (this.mHasSurface && !this.mDestroying && isVisibleByPolicy()) {
            AppWindowToken atoken = this.mAppToken;
            return atoken != null ? !(isParentWindowHidden() || atoken.hiddenRequested) || isAnimating() : !isParentWindowHidden() || isAnimating();
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean mightAffectAllDrawn() {
        boolean isAppType = this.mWinAnimator.mAttrType == 1 || this.mWinAnimator.mAttrType == 5 || this.mWinAnimator.mAttrType == 12 || this.mWinAnimator.mAttrType == 10 || this.mWinAnimator.mAttrType == 6 || this.mWinAnimator.mAttrType == 4;
        return ((!isOnScreen() && !isAppType) || this.mAnimatingExit || this.mDestroying) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInteresting() {
        AppWindowToken appWindowToken = this.mAppToken;
        return (appWindowToken == null || this.mAppDied || (appWindowToken.isFreezingScreen() && this.mAppFreezing) || this.mViewVisibility != 0) ? false : true;
    }

    boolean isReadyForDisplay() {
        if (this.mToken.waitingToShow && getDisplayContent().mAppTransition.isTransitionSet()) {
            return false;
        }
        boolean parentAndClientVisible = (isParentWindowHidden() || this.mViewVisibility != 0 || this.mToken.isHidden()) ? false : true;
        if (this.mHasSurface && isVisibleByPolicy() && !this.mDestroying) {
            return parentAndClientVisible || isAnimating();
        }
        return false;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean canAffectSystemUiFlags() {
        boolean translucent = this.mAttrs.alpha == 0.0f;
        if (translucent) {
            return false;
        }
        if (this.mAppToken == null) {
            boolean shown = this.mWinAnimator.getShown();
            boolean exiting = this.mAnimatingExit || this.mDestroying;
            return shown && !exiting;
        }
        Task task = getTask();
        boolean canFromTask = task != null && task.canAffectSystemUiFlags();
        return canFromTask && !this.mAppToken.isHidden();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isDisplayedLw() {
        AppWindowToken atoken = this.mAppToken;
        return isDrawnLw() && isVisibleByPolicy() && ((!isParentWindowHidden() && (atoken == null || !atoken.hiddenRequested)) || isAnimating());
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isAnimatingLw() {
        return isAnimating();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isGoneForLayoutLw() {
        AppWindowToken atoken = this.mAppToken;
        return this.mViewVisibility == 8 || !this.mRelayoutCalled || (atoken == null && this.mToken.isHidden()) || ((atoken != null && atoken.hiddenRequested) || isParentWindowGoneForLayout() || ((this.mAnimatingExit && !isAnimatingLw()) || this.mDestroying));
    }

    public boolean isDrawFinishedLw() {
        return this.mHasSurface && !this.mDestroying && (this.mWinAnimator.mDrawState == 2 || this.mWinAnimator.mDrawState == 3 || this.mWinAnimator.mDrawState == 4);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isDrawnLw() {
        return this.mHasSurface && !this.mDestroying && (this.mWinAnimator.mDrawState == 3 || this.mWinAnimator.mDrawState == 4);
    }

    private boolean isOpaqueDrawn() {
        return ((!this.mIsWallpaper && this.mAttrs.format == -1) || (this.mIsWallpaper && this.mWallpaperVisible)) && isDrawnLw() && !isAnimating();
    }

    @Override // com.android.server.wm.WindowContainer
    void onMovedByResize() {
        if (WindowManagerDebugConfig.DEBUG_RESIZE) {
            Slog.d(TAG, "onMovedByResize: Moving " + this);
        }
        this.mMovedByResize = true;
        super.onMovedByResize();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean onAppVisibilityChanged(boolean visible, boolean runningAppAnimation) {
        boolean changed = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            changed |= c.onAppVisibilityChanged(visible, runningAppAnimation);
        }
        if (this.mAttrs.type == 3) {
            if (!visible && isVisibleNow() && this.mAppToken.isSelfAnimating()) {
                this.mAnimatingExit = true;
                this.mRemoveOnExit = true;
                this.mWindowRemovalAllowed = true;
            }
            return changed;
        }
        boolean isVisibleNow = isVisibleNow();
        if (visible != isVisibleNow) {
            if (!runningAppAnimation && isVisibleNow) {
                AccessibilityController accessibilityController = this.mWmService.mAccessibilityController;
                this.mWinAnimator.applyAnimationLocked(2, false);
                if (accessibilityController != null) {
                    accessibilityController.onWindowTransitionLocked(this, 2);
                }
            }
            setDisplayLayoutNeeded();
            return true;
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean onSetAppExiting() {
        DisplayContent displayContent = getDisplayContent();
        boolean changed = false;
        if (isVisibleNow()) {
            this.mWinAnimator.applyAnimationLocked(2, false);
            if (this.mWmService.mAccessibilityController != null) {
                this.mWmService.mAccessibilityController.onWindowTransitionLocked(this, 2);
            }
            changed = true;
            if (displayContent != null) {
                displayContent.setLayoutNeeded();
            }
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            changed |= c.onSetAppExiting();
        }
        return changed;
    }

    @Override // com.android.server.wm.WindowContainer
    void onResize() {
        ArrayList<WindowState> resizingWindows = this.mWmService.mResizingWindows;
        if (this.mHasSurface && !isGoneForLayoutLw() && !resizingWindows.contains(this)) {
            if (WindowManagerDebugConfig.DEBUG_RESIZE) {
                Slog.d(TAG, "onResize: Resizing " + this);
            }
            resizingWindows.add(this);
        }
        if (isGoneForLayoutLw()) {
            this.mResizedWhileGone = true;
        }
        super.onResize();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUnfreezeBounds() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.onUnfreezeBounds();
        }
        if (!this.mHasSurface) {
            return;
        }
        this.mLayoutNeeded = true;
        setDisplayLayoutNeeded();
        if (!this.mWmService.mResizingWindows.contains(this)) {
            this.mWmService.mResizingWindows.add(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleWindowMovedIfNeeded() {
        if (!hasMoved()) {
            return;
        }
        int left = this.mWindowFrames.mFrame.left;
        int top = this.mWindowFrames.mFrame.top;
        Task task = getTask();
        boolean adjustedForMinimizedDockOrIme = task != null && (task.mStack.isAdjustedForMinimizedDockedStack() || task.mStack.isAdjustedForIme());
        if (this.mUseResizedAnimator) {
            this.mResizedAnimating = true;
            this.mUseResizedAnimator = false;
            SharedDisplayAnimator.startAnimation(this, this.mFromBounds, this.mToBounds);
        } else if (this.mToken.okToAnimate() && (this.mAttrs.privateFlags & 64) == 0 && !isDragResizing() && !adjustedForMinimizedDockOrIme && getWindowConfiguration().hasMovementAnimations() && !this.mWinAnimator.mLastHidden && !this.mSeamlesslyRotated) {
            startMoveAnimation(left, top);
        }
        if (this.mWmService.mAccessibilityController != null && (getDisplayId() == 0 || getDisplayContent().getParentWindow() != null)) {
            this.mWmService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
        }
        updateLocationInParentDisplayIfNeeded();
        try {
            this.mClient.moved(left, top);
        } catch (RemoteException e) {
        }
        this.mMovedByResize = false;
    }

    private boolean hasMoved() {
        return this.mHasSurface && !((!this.mWindowFrames.hasContentChanged() && !this.mMovedByResize) || this.mAnimatingExit || ((this.mWindowFrames.mFrame.top == this.mWindowFrames.mLastFrame.top && this.mWindowFrames.mFrame.left == this.mWindowFrames.mLastFrame.left) || (this.mIsChildWindow && getParentWindow().hasMoved())));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isObscuringDisplay() {
        Task task = getTask();
        return (task == null || task.mStack == null || task.mStack.fillsParent()) && isOpaqueDrawn() && fillsDisplay();
    }

    boolean fillsDisplay() {
        DisplayInfo displayInfo = getDisplayInfo();
        return this.mWindowFrames.mFrame.left <= 0 && this.mWindowFrames.mFrame.top <= 0 && this.mWindowFrames.mFrame.right >= displayInfo.appWidth && this.mWindowFrames.mFrame.bottom >= displayInfo.appHeight;
    }

    private boolean matchesDisplayBounds() {
        return getDisplayContent().getBounds().equals(getBounds());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLastConfigReportedToClient() {
        return this.mLastConfigReportedToClient;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public void onMergedOverrideConfigurationChanged() {
        super.onMergedOverrideConfigurationChanged();
        this.mLastConfigReportedToClient = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onWindowReplacementTimeout() {
        if (this.mWillReplaceWindow) {
            removeImmediately();
            return;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.onWindowReplacementTimeout();
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void forceWindowsScaleableInTransaction(boolean force) {
        WindowStateAnimator windowStateAnimator = this.mWinAnimator;
        if (windowStateAnimator != null && windowStateAnimator.hasSurface()) {
            this.mWinAnimator.mSurfaceController.forceScaleableInTransaction(force);
        }
        super.forceWindowsScaleableInTransaction(force);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeImmediately() {
        super.removeImmediately();
        if (this.mRemoved) {
            if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                Slog.v(TAG, "WS.removeImmediately: " + this + " Already removed...");
                return;
            }
            return;
        }
        this.mRemoved = true;
        this.mWillReplaceWindow = false;
        WindowState windowState = this.mReplacementWindow;
        if (windowState != null) {
            windowState.mSkipEnterAnimationForSeamlessReplacement = false;
        }
        DisplayContent dc = getDisplayContent();
        if (isInputMethodTarget()) {
            dc.computeImeTarget(true);
        }
        int type = this.mAttrs.type;
        if (WindowManagerService.excludeWindowTypeFromTapOutTask(type)) {
            dc.mTapExcludedWindows.remove(this);
        }
        if (this.mTapExcludeRegionHolder != null) {
            dc.mTapExcludeProvidingWindows.remove(this);
        }
        dc.getDisplayPolicy().removeWindowLw(this);
        disposeInputChannel();
        this.mWinAnimator.destroyDeferredSurfaceLocked();
        this.mWinAnimator.destroySurfaceLocked();
        this.mSession.windowRemovedLocked();
        try {
            this.mClient.asBinder().unlinkToDeath(this.mDeathRecipient, 0);
        } catch (RuntimeException e) {
        }
        this.mWmService.postWindowRemoveCleanupLocked(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void removeIfPossible() {
        super.removeIfPossible();
        removeIfPossible(false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeIfPossible(boolean keepVisibleDeadWindow) {
        this.mWindowRemovalAllowed = true;
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.v(TAG, "removeIfPossible: " + this + " callers=" + Debug.getCallers(5));
        }
        boolean startingWindow = this.mAttrs.type == 3;
        if (startingWindow && WindowManagerDebugConfig.DEBUG_STARTING_WINDOW) {
            Slog.d(TAG, "Starting window removed " + this);
        }
        if (WindowManagerService.localLOGV || WindowManagerDebugConfig.DEBUG_FOCUS || (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT && isFocused())) {
            Slog.v(TAG, "Remove " + this + " client=" + Integer.toHexString(System.identityHashCode(this.mClient.asBinder())) + ", surfaceController=" + this.mWinAnimator.mSurfaceController + " Callers=" + Debug.getCallers(5));
        }
        long origId = Binder.clearCallingIdentity();
        try {
            disposeInputChannel();
            if (WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS) {
                StringBuilder sb = new StringBuilder();
                sb.append("Remove ");
                sb.append(this);
                sb.append(": mSurfaceController=");
                sb.append(this.mWinAnimator.mSurfaceController);
                sb.append(" mAnimatingExit=");
                sb.append(this.mAnimatingExit);
                sb.append(" mRemoveOnExit=");
                sb.append(this.mRemoveOnExit);
                sb.append(" mHasSurface=");
                sb.append(this.mHasSurface);
                sb.append(" surfaceShowing=");
                sb.append(this.mWinAnimator.getShown());
                sb.append(" animating=");
                sb.append(isAnimating());
                sb.append(" app-animation=");
                sb.append(this.mAppToken != null ? Boolean.valueOf(this.mAppToken.isSelfAnimating()) : "false");
                sb.append(" mWillReplaceWindow=");
                sb.append(this.mWillReplaceWindow);
                sb.append(" inPendingTransaction=");
                sb.append(this.mAppToken != null ? this.mAppToken.inPendingTransaction : false);
                sb.append(" mDisplayFrozen=");
                sb.append(this.mWmService.mDisplayFrozen);
                sb.append(" callers=");
                sb.append(Debug.getCallers(6));
                Slog.v(TAG, sb.toString());
            }
            boolean wasVisible = false;
            getDisplayId();
            if (this.mHasSurface && this.mToken.okToAnimate()) {
                if (this.mWillReplaceWindow) {
                    if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                        Slog.v(TAG, "Preserving " + this + " until the new one is added");
                    }
                    this.mAnimatingExit = true;
                    this.mReplacingRemoveRequested = true;
                    return;
                }
                wasVisible = isWinVisibleLw();
                if (keepVisibleDeadWindow) {
                    if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                        Slog.v(TAG, "Not removing " + this + " because app died while it's visible");
                    }
                    this.mAppDied = true;
                    setDisplayLayoutNeeded();
                    this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
                    openInputChannel(null);
                    getDisplayContent().getInputMonitor().updateInputWindowsLw(true);
                    return;
                }
                if (wasVisible) {
                    int transit = startingWindow ? 5 : 2;
                    if (this.mWinAnimator.applyAnimationLocked(transit, false)) {
                        this.mAnimatingExit = true;
                        setDisplayLayoutNeeded();
                        this.mWmService.requestTraversal();
                    }
                    if (this.mWmService.mAccessibilityController != null) {
                        this.mWmService.mAccessibilityController.onWindowTransitionLocked(this, transit);
                    }
                }
                boolean isAnimating = isAnimating() && (this.mAppToken == null || !this.mAppToken.isWaitingForTransitionStart());
                boolean lastWindowIsStartingWindow = startingWindow && this.mAppToken != null && this.mAppToken.isLastWindow(this);
                if (this.mWinAnimator.getShown() && this.mAnimatingExit && (!lastWindowIsStartingWindow || isAnimating)) {
                    if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                        Slog.v(TAG, "Not removing " + this + " due to exit animation ");
                    }
                    setupWindowForRemoveOnExit();
                    if (this.mAppToken != null) {
                        this.mAppToken.updateReportedVisibilityLocked();
                    }
                    return;
                }
            }
            removeImmediately();
            if (wasVisible) {
                DisplayContent displayContent = getDisplayContent();
                if (displayContent.updateOrientationFromAppTokens()) {
                    displayContent.sendNewConfiguration();
                }
            }
            this.mWmService.updateFocusedWindowLocked(isFocused() ? 4 : 0, true);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void setupWindowForRemoveOnExit() {
        this.mRemoveOnExit = true;
        setDisplayLayoutNeeded();
        boolean focusChanged = this.mWmService.updateFocusedWindowLocked(3, false);
        this.mWmService.mWindowPlacerLocked.performSurfacePlacement();
        if (focusChanged) {
            getDisplayContent().getInputMonitor().updateInputWindowsLw(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasSurface(boolean hasSurface) {
        this.mHasSurface = hasSurface;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canBeImeTarget() {
        if (this.mIsImWindow) {
            return false;
        }
        AppWindowToken appWindowToken = this.mAppToken;
        boolean windowsAreFocusable = appWindowToken == null || appWindowToken.windowsAreFocusable();
        if (windowsAreFocusable) {
            int fl = this.mAttrs.flags & 131080;
            int type = this.mAttrs.type;
            if (fl == 0 || fl == 131080 || type == 3) {
                if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                    Slog.i(TAG, "isVisibleOrAdding " + this + ": " + isVisibleOrAdding());
                    if (!isVisibleOrAdding()) {
                        Slog.i(TAG, "  mSurfaceController=" + this.mWinAnimator.mSurfaceController + " relayoutCalled=" + this.mRelayoutCalled + " viewVis=" + this.mViewVisibility + " policyVis=" + isVisibleByPolicy() + " policyVisAfterAnim=" + this.mLegacyPolicyVisibilityAfterAnim + " parentHidden=" + isParentWindowHidden() + " exiting=" + this.mAnimatingExit + " destroying=" + this.mDestroying);
                        if (this.mAppToken != null) {
                            Slog.i(TAG, "  mAppToken.hiddenRequested=" + this.mAppToken.hiddenRequested);
                        }
                    }
                }
                return isVisibleOrAdding();
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class DeadWindowEventReceiver extends InputEventReceiver {
        DeadWindowEventReceiver(InputChannel inputChannel) {
            super(inputChannel, WindowState.this.mWmService.mH.getLooper());
        }

        public void onInputEvent(InputEvent event) {
            finishInputEvent(event, true);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void openInputChannel(InputChannel outInputChannel) {
        if (this.mInputChannel != null) {
            throw new IllegalStateException("Window already has an input channel.");
        }
        String name = getName();
        InputChannel[] inputChannels = InputChannel.openInputChannelPair(name);
        this.mInputChannel = inputChannels[0];
        this.mClientChannel = inputChannels[1];
        this.mInputWindowHandle.token = this.mClient.asBinder();
        if (outInputChannel != null) {
            this.mClientChannel.transferTo(outInputChannel);
            this.mClientChannel.dispose();
            this.mClientChannel = null;
        } else {
            this.mDeadWindowEventReceiver = new DeadWindowEventReceiver(this.mClientChannel);
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT) {
            StringBuilder sb = new StringBuilder();
            sb.append("openInputChannel registerInputChannel name = ");
            sb.append(name);
            sb.append(" win=");
            sb.append(this);
            sb.append(" attr=");
            sb.append(this.mAttrs);
            sb.append(" inputChannel=");
            sb.append(this.mInputChannel);
            sb.append(" inputName=");
            InputChannel inputChannel = this.mInputChannel;
            sb.append(inputChannel != null ? inputChannel.getName() : "");
            sb.append(" clientChannel=");
            sb.append(this.mClientChannel);
            sb.append(" clientName=");
            InputChannel inputChannel2 = this.mClientChannel;
            sb.append(inputChannel2 != null ? inputChannel2.getName() : "");
            sb.append(" token=");
            IWindow iWindow = this.mClient;
            sb.append(iWindow != null ? iWindow.asBinder() : "");
            Slog.i(TAG, sb.toString());
        }
        this.mWmService.mInputManager.registerInputChannel(this.mInputChannel, this.mClient.asBinder());
    }

    void disposeInputChannel() {
        DeadWindowEventReceiver deadWindowEventReceiver = this.mDeadWindowEventReceiver;
        if (deadWindowEventReceiver != null) {
            deadWindowEventReceiver.dispose();
            this.mDeadWindowEventReceiver = null;
        }
        if (WindowManagerDebugConfig.DEBUG_INPUT) {
            StringBuilder sb = new StringBuilder();
            sb.append("disposeInputChannel unregisterInputChannel win=");
            sb.append(this);
            sb.append(" attr=");
            sb.append(this.mAttrs);
            sb.append(" inputChannel=");
            sb.append(this.mInputChannel);
            sb.append(" inputName=");
            InputChannel inputChannel = this.mInputChannel;
            sb.append(inputChannel != null ? inputChannel.getName() : "");
            sb.append(" clientChannel=");
            sb.append(this.mClientChannel);
            sb.append(" clientName=");
            InputChannel inputChannel2 = this.mClientChannel;
            sb.append(inputChannel2 != null ? inputChannel2.getName() : "");
            sb.append(" token=");
            IWindow iWindow = this.mClient;
            sb.append(iWindow != null ? iWindow.asBinder() : "");
            Slog.i(TAG, sb.toString());
        }
        if (this.mInputChannel != null) {
            this.mWmService.mInputManager.unregisterInputChannel(this.mInputChannel);
            this.mInputChannel.dispose();
            this.mInputChannel = null;
        }
        InputChannel inputChannel3 = this.mClientChannel;
        if (inputChannel3 != null) {
            inputChannel3.dispose();
            this.mClientChannel = null;
        }
        this.mInputWindowHandle.token = null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeReplacedWindowIfNeeded(WindowState replacement) {
        if (this.mWillReplaceWindow && this.mReplacementWindow == replacement && replacement.hasDrawnLw()) {
            replacement.mSkipEnterAnimationForSeamlessReplacement = false;
            removeReplacedWindow();
            return true;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            if (c.removeReplacedWindowIfNeeded(replacement)) {
                return true;
            }
        }
        return false;
    }

    private void removeReplacedWindow() {
        if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.d(TAG, "Removing replaced window: " + this);
        }
        this.mWillReplaceWindow = false;
        this.mAnimateReplacingWindow = false;
        this.mReplacingRemoveRequested = false;
        this.mReplacementWindow = null;
        if (this.mAnimatingExit || !this.mAnimateReplacingWindow) {
            removeImmediately();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setReplacementWindowIfNeeded(WindowState replacementCandidate) {
        boolean replacementSet = false;
        if (this.mWillReplaceWindow && this.mReplacementWindow == null && getWindowTag().toString().equals(replacementCandidate.getWindowTag().toString())) {
            this.mReplacementWindow = replacementCandidate;
            replacementCandidate.mSkipEnterAnimationForSeamlessReplacement = !this.mAnimateReplacingWindow;
            replacementSet = true;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            replacementSet |= c.setReplacementWindowIfNeeded(replacementCandidate);
        }
        return replacementSet;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisplayLayoutNeeded() {
        DisplayContent dc = getDisplayContent();
        if (dc != null) {
            dc.setLayoutNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyAdjustForImeIfNeeded() {
        Task task = getTask();
        if (task != null && task.mStack != null && task.mStack.isAdjustedForIme()) {
            task.mStack.applyAdjustForImeIfNeeded(task);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void switchUser() {
        super.switchUser();
        if (isHiddenFromUserLocked()) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.w(TAG, "user changing, hiding " + this + ", attrs=" + this.mAttrs.type + ", belonging to " + this.mOwnerUid);
            }
            clearPolicyVisibilityFlag(2);
            return;
        }
        setPolicyVisibilityFlag(2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getSurfaceTouchableRegion(InputWindowHandle inputWindowHandle, int flags) {
        Rect rect;
        AppWindowToken appWindowToken;
        boolean modal = (flags & 40) == 0;
        Region region = inputWindowHandle.touchableRegion;
        setTouchableRegionCropIfNeeded(inputWindowHandle);
        AppWindowToken appWindowToken2 = this.mAppToken;
        Rect appOverrideBounds = appWindowToken2 != null ? appWindowToken2.getResolvedOverrideBounds() : null;
        if (appOverrideBounds != null && !appOverrideBounds.isEmpty()) {
            if (modal) {
                flags |= 32;
                this.mTmpRect.set(0, 0, appOverrideBounds.width(), appOverrideBounds.height());
            } else {
                this.mTmpRect.set(this.mWindowFrames.mCompatFrame);
            }
            int surfaceOffsetX = this.mAppToken.inSizeCompatMode() ? this.mAppToken.getBounds().left : 0;
            this.mTmpRect.offset(surfaceOffsetX - this.mWindowFrames.mFrame.left, -this.mWindowFrames.mFrame.top);
            region.set(this.mTmpRect);
            return flags;
        }
        if (modal && (appWindowToken = this.mAppToken) != null) {
            flags |= 32;
            appWindowToken.getLetterboxInnerBounds(this.mTmpRect);
            if (this.mTmpRect.isEmpty()) {
                Task task = getTask();
                if (task != null) {
                    task.getDimBounds(this.mTmpRect);
                } else {
                    getStack().getDimBounds(this.mTmpRect);
                }
            }
            if (inFreeformWindowingMode()) {
                DisplayMetrics displayMetrics = getDisplayContent().getDisplayMetrics();
                int delta = WindowManagerService.dipToPixel(30, displayMetrics);
                this.mTmpRect.inset(-delta, -delta);
            }
            region.set(this.mTmpRect);
            Rect rect2 = getOverrideTouchableRegion();
            if (rect2 != null) {
                region.set(rect2);
            } else if (this.mAttrs.type != 6) {
                getTouchableRegion(region);
            }
            cropRegionToStackBoundsIfNeeded(region);
            subtractTouchExcludeRegionIfNeeded(region);
        } else if (modal && this.mTapExcludeRegionHolder != null) {
            Region touchExcludeRegion = Region.obtain();
            amendTapExcludeRegion(touchExcludeRegion);
            if (!touchExcludeRegion.isEmpty()) {
                flags |= 32;
                getDisplayContent().getBounds(this.mTmpRect);
                int dw = this.mTmpRect.width();
                int dh = this.mTmpRect.height();
                region.set(-dw, -dh, dw + dw, dh + dh);
                region.op(touchExcludeRegion, Region.Op.DIFFERENCE);
                inputWindowHandle.setTouchableRegionCrop((SurfaceControl) null);
            }
            touchExcludeRegion.recycle();
        } else {
            getTouchableRegion(region);
            if (xpWindowManager.isSystemAlertWindowType(this.mAttrs) && (rect = getOverrideTouchableRegion()) != null) {
                region.set(rect);
            }
        }
        region.translate(-this.mWindowFrames.mFrame.left, -this.mWindowFrames.mFrame.top);
        return flags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkPolicyVisibilityChange() {
        if (isLegacyPolicyVisibility() != this.mLegacyPolicyVisibilityAfterAnim) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility changing after anim in " + this.mWinAnimator + ": " + this.mLegacyPolicyVisibilityAfterAnim);
            }
            if (this.mLegacyPolicyVisibilityAfterAnim) {
                setPolicyVisibilityFlag(1);
            } else {
                clearPolicyVisibilityFlag(1);
            }
            if (!isVisibleByPolicy()) {
                this.mWinAnimator.hide("checkPolicyVisibilityChange");
                if (isFocused()) {
                    if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                        Slog.i(TAG, "setAnimationLocked: setting mFocusMayChange true");
                    }
                    this.mWmService.mFocusMayChange = true;
                }
                setDisplayLayoutNeeded();
                this.mWmService.enableScreenIfNeededLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setRequestedSize(int requestedWidth, int requestedHeight) {
        if (this.mRequestedWidth != requestedWidth || this.mRequestedHeight != requestedHeight) {
            this.mLayoutNeeded = true;
            this.mRequestedWidth = requestedWidth;
            this.mRequestedHeight = requestedHeight;
        }
    }

    void prepareWindowToDisplayDuringRelayout(boolean wasVisible) {
        boolean hasTurnScreenOnFlag = (this.mAttrs.flags & DumpState.DUMP_COMPILER_STATS) != 0;
        if (hasTurnScreenOnFlag) {
            boolean allowTheaterMode = this.mWmService.mAllowTheaterModeWakeFromLayout || Settings.Global.getInt(this.mWmService.mContext.getContentResolver(), "theater_mode_on", 0) == 0;
            AppWindowToken appWindowToken = this.mAppToken;
            boolean canTurnScreenOn = appWindowToken == null || appWindowToken.canTurnScreenOn();
            if (allowTheaterMode && canTurnScreenOn && !this.mPowerManagerWrapper.isInteractive()) {
                if (WindowManagerDebugConfig.DEBUG_VISIBILITY || WindowManagerDebugConfig.DEBUG_POWER) {
                    Slog.v(TAG, "Relayout window turning screen on: " + this);
                }
                this.mPowerManagerWrapper.wakeUp(SystemClock.uptimeMillis(), 2, "android.server.wm:SCREEN_ON_FLAG");
            }
            AppWindowToken appWindowToken2 = this.mAppToken;
            if (appWindowToken2 != null) {
                appWindowToken2.setCanTurnScreenOn(false);
            }
        }
        if (wasVisible) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Already visible and does not turn on screen, skip preparing: " + this);
                return;
            }
            return;
        }
        if ((this.mAttrs.softInputMode & 240) == 16) {
            this.mLayoutNeeded = true;
        }
        if (isDrawnLw() && this.mToken.okToAnimate()) {
            this.mWinAnimator.applyEnterAnimationLocked();
        }
    }

    private Configuration getProcessGlobalConfiguration() {
        WindowState parentWindow = getParentWindow();
        int pid = (parentWindow != null ? parentWindow.mSession : this.mSession).mPid;
        Configuration processConfig = this.mWmService.mAtmService.getGlobalConfigurationForPid(pid);
        return processConfig;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getMergedConfiguration(MergedConfiguration outConfiguration) {
        Configuration globalConfig = getProcessGlobalConfiguration();
        Configuration overrideConfig = getMergedOverrideConfiguration();
        outConfiguration.setConfiguration(globalConfig, overrideConfig);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastReportedMergedConfiguration(MergedConfiguration config) {
        this.mLastReportedConfiguration.setTo(config);
        this.mLastConfigReportedToClient = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getLastReportedMergedConfiguration(MergedConfiguration config) {
        config.setTo(this.mLastReportedConfiguration);
    }

    private Configuration getLastReportedConfiguration() {
        return this.mLastReportedConfiguration.getMergedConfiguration();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void adjustStartingWindowFlags() {
        AppWindowToken appWindowToken;
        if ((this.mAttrs.type == 1 || this.mAttrs.type == 5 || this.mAttrs.type == 12 || this.mAttrs.type == 10 || this.mAttrs.type == 6) && (appWindowToken = this.mAppToken) != null && appWindowToken.startingWindow != null) {
            WindowManager.LayoutParams sa = this.mAppToken.startingWindow.mAttrs;
            sa.flags = (sa.flags & (-4718594)) | (this.mAttrs.flags & 4718593);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWindowScale(int requestedWidth, int requestedHeight) {
        float f;
        boolean scaledWindow = (this.mAttrs.flags & 16384) != 0;
        float f2 = 1.0f;
        if (scaledWindow) {
            if (this.mAttrs.width == requestedWidth) {
                f = 1.0f;
            } else {
                f = this.mAttrs.width / requestedWidth;
            }
            this.mHScale = f;
            if (this.mAttrs.height != requestedHeight) {
                f2 = this.mAttrs.height / requestedHeight;
            }
            this.mVScale = f2;
            return;
        }
        this.mVScale = 1.0f;
        this.mHScale = 1.0f;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class DeathRecipient implements IBinder.DeathRecipient {
        private DeathRecipient() {
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            boolean resetSplitScreenResizing = false;
            try {
                synchronized (WindowState.this.mWmService.mGlobalLock) {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState win = WindowState.this.mWmService.windowForClientLocked(WindowState.this.mSession, WindowState.this.mClient, false);
                    Slog.i(WindowState.TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        DisplayContent dc = WindowState.this.getDisplayContent();
                        if (win.mAppToken != null && win.mAppToken.findMainWindow() == win) {
                            WindowState.this.mWmService.mTaskSnapshotController.onAppDied(win.mAppToken);
                        }
                        win.removeIfPossible(WindowState.this.shouldKeepVisibleDeadAppWindow());
                        if (win.mAttrs.type == 2034) {
                            TaskStack stack = dc.getSplitScreenPrimaryStackIgnoringVisibility();
                            if (stack != null) {
                                stack.resetDockedStackToMiddle();
                            }
                            resetSplitScreenResizing = true;
                        }
                    } else if (WindowState.this.mHasSurface) {
                        Slog.e(WindowState.TAG, "!!! LEAK !!! Window removed but surface still valid.");
                        WindowState.this.removeIfPossible();
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                if (resetSplitScreenResizing) {
                    try {
                        WindowState.this.mWmService.mActivityTaskManager.setSplitScreenResizing(false);
                    } catch (RemoteException e) {
                        throw e.rethrowAsRuntimeException();
                    }
                }
            } catch (IllegalArgumentException e2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean shouldKeepVisibleDeadAppWindow() {
        AppWindowToken appWindowToken;
        if (!isWinVisibleLw() || (appWindowToken = this.mAppToken) == null || appWindowToken.isClientHidden() || this.mAttrs.token != this.mClient.asBinder() || this.mAttrs.type == 3) {
            return false;
        }
        return getWindowConfiguration().keepVisibleDeadAppWindowOnScreen();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean canReceiveKeys() {
        AppWindowToken appWindowToken;
        return isVisibleOrAdding() && this.mViewVisibility == 0 && !this.mRemoveOnExit && (this.mAttrs.flags & 8) == 0 && ((appWindowToken = this.mAppToken) == null || appWindowToken.windowsAreFocusable()) && !cantReceiveTouchInput();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean canShowWhenLocked() {
        AppWindowToken appWindowToken = this.mAppToken;
        boolean showBecauseOfActivity = appWindowToken != null && appWindowToken.mActivityRecord.canShowWhenLocked();
        boolean showBecauseOfWindow = (getAttrs().flags & DumpState.DUMP_FROZEN) != 0;
        return showBecauseOfActivity || showBecauseOfWindow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean cantReceiveTouchInput() {
        AppWindowToken appWindowToken = this.mAppToken;
        return (appWindowToken == null || appWindowToken.getTask() == null || (!this.mAppToken.getTask().mStack.shouldIgnoreInput() && !this.mAppToken.hiddenRequested)) ? false : true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean hasDrawnLw() {
        return this.mWinAnimator.mDrawState == 4;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean showLw(boolean doAnimation) {
        return showLw(doAnimation, true);
    }

    boolean showLw(boolean doAnimation, boolean requestAnim) {
        if ((isLegacyPolicyVisibility() && this.mLegacyPolicyVisibilityAfterAnim) || isHiddenFromUserLocked() || !this.mAppOpVisibility || this.mPermanentlyHidden || this.mHiddenWhileSuspended || this.mForceHideNonSystemOverlayWindow) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Policy visibility true: " + this);
        }
        if (doAnimation) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "doAnimation: mPolicyVisibility=" + isLegacyPolicyVisibility() + " animating=" + isAnimating());
            }
            if (!this.mToken.okToAnimate()) {
                doAnimation = false;
            } else if (isLegacyPolicyVisibility() && !isAnimating()) {
                doAnimation = false;
            }
        }
        setPolicyVisibilityFlag(1);
        this.mLegacyPolicyVisibilityAfterAnim = true;
        if (doAnimation) {
            this.mWinAnimator.applyAnimationLocked(1, true);
        }
        if (requestAnim) {
            this.mWmService.scheduleAnimationLocked();
        }
        if ((this.mAttrs.flags & 8) == 0) {
            this.mWmService.updateFocusedWindowLocked(0, false);
        }
        return true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean hideLw(boolean doAnimation) {
        return hideLw(doAnimation, true);
    }

    boolean hideLw(boolean doAnimation, boolean requestAnim) {
        if (doAnimation && !this.mToken.okToAnimate()) {
            doAnimation = false;
        }
        boolean current = doAnimation ? this.mLegacyPolicyVisibilityAfterAnim : isLegacyPolicyVisibility();
        if (!current) {
            return false;
        }
        if (doAnimation) {
            this.mWinAnimator.applyAnimationLocked(2, false);
            if (!isAnimating()) {
                doAnimation = false;
            }
        }
        this.mLegacyPolicyVisibilityAfterAnim = false;
        boolean isFocused = isFocused();
        if (!doAnimation) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility false: " + this);
            }
            clearPolicyVisibilityFlag(1);
            this.mWmService.enableScreenIfNeededLocked();
            if (isFocused) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.i(TAG, "WindowState.hideLw: setting mFocusMayChange true");
                }
                this.mWmService.mFocusMayChange = true;
            }
        }
        if (requestAnim) {
            this.mWmService.scheduleAnimationLocked();
        }
        if (isFocused) {
            this.mWmService.updateFocusedWindowLocked(0, false);
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setForceHideNonSystemOverlayWindowIfNeeded(boolean forceHide) {
        if (!this.mOwnerCanAddInternalSystemWindow) {
            if ((!WindowManager.LayoutParams.isSystemAlertWindowType(this.mAttrs.type) && this.mAttrs.type != 2005) || this.mForceHideNonSystemOverlayWindow == forceHide) {
                return;
            }
            this.mForceHideNonSystemOverlayWindow = forceHide;
            if (forceHide) {
                hideLw(true, true);
            } else {
                showLw(true, true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHiddenWhileSuspended(boolean hide) {
        if (!this.mOwnerCanAddInternalSystemWindow) {
            if ((!WindowManager.LayoutParams.isSystemAlertWindowType(this.mAttrs.type) && this.mAttrs.type != 2005) || this.mHiddenWhileSuspended == hide) {
                return;
            }
            this.mHiddenWhileSuspended = hide;
            if (hide) {
                hideLw(true, true);
            } else {
                showLw(true, true);
            }
        }
    }

    private void setAppOpVisibilityLw(boolean state) {
        if (this.mAppOpVisibility != state) {
            this.mAppOpVisibility = state;
            if (state) {
                showLw(true, true);
            } else {
                hideLw(true, true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initAppOpsState() {
        int mode;
        if (this.mAppOp != -1 && this.mAppOpVisibility && (mode = this.mWmService.mAppOps.startOpNoThrow(this.mAppOp, getOwningUid(), getOwningPackage(), true)) != 0 && mode != 3) {
            setAppOpVisibilityLw(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetAppOpsState() {
        if (this.mAppOp != -1 && this.mAppOpVisibility) {
            this.mWmService.mAppOps.finishOp(this.mAppOp, getOwningUid(), getOwningPackage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateAppOpsState() {
        if (this.mAppOp == -1) {
            return;
        }
        int uid = getOwningUid();
        String packageName = getOwningPackage();
        if (this.mAppOpVisibility) {
            int mode = this.mWmService.mAppOps.checkOpNoThrow(this.mAppOp, uid, packageName);
            if (mode != 0 && mode != 3) {
                this.mWmService.mAppOps.finishOp(this.mAppOp, uid, packageName);
                setAppOpVisibilityLw(false);
                return;
            }
            return;
        }
        int mode2 = this.mWmService.mAppOps.startOpNoThrow(this.mAppOp, uid, packageName, true);
        if (mode2 == 0 || mode2 == 3) {
            setAppOpVisibilityLw(true);
        }
    }

    public void hidePermanentlyLw() {
        if (!this.mPermanentlyHidden) {
            this.mPermanentlyHidden = true;
            hideLw(true, true);
        }
    }

    public void pokeDrawLockLw(long timeout) {
        if (isVisibleOrAdding()) {
            if (this.mDrawLock == null) {
                CharSequence tag = getWindowTag();
                PowerManager powerManager = this.mWmService.mPowerManager;
                this.mDrawLock = powerManager.newWakeLock(128, "Window:" + ((Object) tag));
                this.mDrawLock.setReferenceCounted(false);
                this.mDrawLock.setWorkSource(new WorkSource(this.mOwnerUid, this.mAttrs.packageName));
            }
            if (WindowManagerDebugConfig.DEBUG_POWER) {
                Slog.d(TAG, "pokeDrawLock: poking draw lock on behalf of visible window owned by " + this.mAttrs.packageName);
            }
            this.mDrawLock.acquire(timeout);
        } else if (WindowManagerDebugConfig.DEBUG_POWER) {
            Slog.d(TAG, "pokeDrawLock: suppressed draw lock request for invisible window owned by " + this.mAttrs.packageName);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isAlive() {
        return this.mClient.asBinder().isBinderAlive();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isClosing() {
        AppWindowToken appWindowToken;
        return this.mAnimatingExit || ((appWindowToken = this.mAppToken) != null && appWindowToken.isClosingOrEnteringPip());
    }

    void addWinAnimatorToList(ArrayList<WindowStateAnimator> animators) {
        animators.add(this.mWinAnimator);
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.addWinAnimatorToList(animators);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void sendAppVisibilityToClients() {
        super.sendAppVisibilityToClients();
        boolean clientHidden = this.mAppToken.isClientHidden();
        if (this.mAttrs.type == 3 && clientHidden) {
            return;
        }
        boolean z = true;
        if (clientHidden) {
            for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                WindowState c = (WindowState) this.mChildren.get(i);
                c.mWinAnimator.detachChildren();
            }
            this.mWinAnimator.detachChildren();
        }
        try {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                StringBuilder sb = new StringBuilder();
                sb.append("Setting visibility of ");
                sb.append(this);
                sb.append(": ");
                sb.append(!clientHidden);
                Slog.v(TAG, sb.toString());
            }
            IWindow iWindow = this.mClient;
            if (clientHidden) {
                z = false;
            }
            iWindow.dispatchAppVisibility(z);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onStartFreezingScreen() {
        this.mAppFreezing = true;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.onStartFreezingScreen();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean onStopFreezingScreen() {
        boolean unfrozeWindows = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            unfrozeWindows |= c.onStopFreezingScreen();
        }
        if (!this.mAppFreezing) {
            return unfrozeWindows;
        }
        this.mAppFreezing = false;
        if (this.mHasSurface && !getOrientationChanging() && this.mWmService.mWindowsFreezingScreen != 2) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "set mOrientationChanging of " + this);
            }
            setOrientationChanging(true);
            this.mWmService.mRoot.mOrientationChangeComplete = false;
        }
        this.mLastFreezeDuration = 0;
        setDisplayLayoutNeeded();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean destroySurface(boolean cleanupOnResume, boolean appStopped) {
        boolean destroyedSomething = false;
        ArrayList<WindowState> childWindows = new ArrayList<>(this.mChildren);
        for (int i = childWindows.size() - 1; i >= 0; i--) {
            WindowState c = childWindows.get(i);
            destroyedSomething |= c.destroySurface(cleanupOnResume, appStopped);
        }
        if (!appStopped && !this.mWindowRemovalAllowed && !cleanupOnResume) {
            return destroyedSomething;
        }
        if (appStopped || this.mWindowRemovalAllowed) {
            this.mWinAnimator.destroyPreservedSurfaceLocked();
        }
        if (this.mDestroying) {
            if (WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
                Slog.e(TAG, "win=" + this + " destroySurfaces: appStopped=" + appStopped + " win.mWindowRemovalAllowed=" + this.mWindowRemovalAllowed + " win.mRemoveOnExit=" + this.mRemoveOnExit);
            }
            if (!cleanupOnResume || this.mRemoveOnExit) {
                destroySurfaceUnchecked();
            }
            if (this.mRemoveOnExit) {
                removeImmediately();
            }
            if (cleanupOnResume) {
                requestUpdateWallpaperIfNeeded();
            }
            this.mDestroying = false;
            destroyedSomething = true;
            if (getDisplayContent().mAppTransition.isTransitionSet() && getDisplayContent().mOpeningApps.contains(this.mAppToken)) {
                this.mWmService.mWindowPlacerLocked.requestTraversal();
            }
        }
        return destroyedSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroySurfaceUnchecked() {
        this.mWinAnimator.destroySurfaceLocked();
        this.mAnimatingExit = false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSurfaceShownChanged(boolean shown) {
        if (this.mLastShownChangedReported == shown) {
            return;
        }
        this.mLastShownChangedReported = shown;
        if (shown) {
            initExclusionRestrictions();
            return;
        }
        logExclusionRestrictions(0);
        logExclusionRestrictions(1);
    }

    private void logExclusionRestrictions(int side) {
        if (!DisplayContent.logsGestureExclusionRestrictions(this) || SystemClock.uptimeMillis() < this.mLastExclusionLogUptimeMillis[side] + this.mWmService.mSystemGestureExclusionLogDebounceTimeoutMillis) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long[] jArr = this.mLastExclusionLogUptimeMillis;
        long duration = now - jArr[side];
        jArr[side] = now;
        int requested = this.mLastRequestedExclusionHeight[side];
        int granted = this.mLastGrantedExclusionHeight[side];
        StatsLog.write(223, this.mAttrs.packageName, requested, requested - granted, side + 1, getConfiguration().orientation == 2, WindowConfiguration.isSplitScreenWindowingMode(getWindowingMode()), (int) duration);
    }

    private void initExclusionRestrictions() {
        long now = SystemClock.uptimeMillis();
        long[] jArr = this.mLastExclusionLogUptimeMillis;
        jArr[0] = now;
        jArr[1] = now;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isDefaultDisplay() {
        DisplayContent displayContent = getDisplayContent();
        if (displayContent == null) {
            return false;
        }
        return displayContent.isDefaultDisplay;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setShowToOwnerOnlyLocked(boolean showToOwnerOnly) {
        this.mShowToOwnerOnly = showToOwnerOnly;
    }

    private boolean isHiddenFromUserLocked() {
        AppWindowToken appWindowToken;
        WindowState win = getTopParentWindow();
        return (win.mAttrs.type >= 2000 || (appWindowToken = win.mAppToken) == null || !appWindowToken.mShowForAllUsers || win.getFrameLw().left > win.getDisplayFrameLw().left || win.getFrameLw().top > win.getDisplayFrameLw().top || win.getFrameLw().right < win.getStableFrameLw().right || win.getFrameLw().bottom < win.getStableFrameLw().bottom) && win.mShowToOwnerOnly && !this.mWmService.isCurrentProfileLocked(UserHandle.getUserId(win.mOwnerUid));
    }

    private static void applyInsets(Region outRegion, Rect frame, Rect inset) {
        outRegion.set(frame.left + inset.left, frame.top + inset.top, frame.right - inset.right, frame.bottom - inset.bottom);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getTouchableRegion(Region outRegion) {
        Rect frame = this.mWindowFrames.mFrame;
        int i = this.mTouchableInsets;
        if (i == 1) {
            applyInsets(outRegion, frame, this.mGivenContentInsets);
        } else if (i == 2) {
            applyInsets(outRegion, frame, this.mGivenVisibleInsets);
        } else if (i != 3) {
            outRegion.set(frame);
        } else {
            outRegion.set(this.mGivenTouchableRegion);
            outRegion.translate(frame.left, frame.top);
        }
        cropRegionToStackBoundsIfNeeded(outRegion);
        subtractTouchExcludeRegionIfNeeded(outRegion);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getEffectiveTouchableRegion(Region outRegion) {
        boolean modal = (this.mAttrs.flags & 40) == 0;
        DisplayContent dc = getDisplayContent();
        if (modal && dc != null) {
            outRegion.set(dc.getBounds());
            cropRegionToStackBoundsIfNeeded(outRegion);
            subtractTouchExcludeRegionIfNeeded(outRegion);
            return;
        }
        getTouchableRegion(outRegion);
    }

    private void setTouchableRegionCropIfNeeded(InputWindowHandle handle) {
        TaskStack stack;
        Task task = getTask();
        if (task == null || !task.cropWindowsToStackBounds() || (stack = task.mStack) == null) {
            return;
        }
        handle.setTouchableRegionCrop(stack.getSurfaceControl());
    }

    private void cropRegionToStackBoundsIfNeeded(Region region) {
        TaskStack stack;
        Task task = getTask();
        if (task == null || !task.cropWindowsToStackBounds() || (stack = task.mStack) == null) {
            return;
        }
        stack.getDimBounds(this.mTmpRect);
        region.op(this.mTmpRect, Region.Op.INTERSECT);
    }

    private void subtractTouchExcludeRegionIfNeeded(Region touchableRegion) {
        if (this.mTapExcludeRegionHolder == null) {
            return;
        }
        Region touchExcludeRegion = Region.obtain();
        amendTapExcludeRegion(touchExcludeRegion);
        if (!touchExcludeRegion.isEmpty()) {
            touchableRegion.op(touchExcludeRegion, Region.Op.DIFFERENCE);
        }
        touchExcludeRegion.recycle();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportFocusChangedSerialized(boolean focused, boolean inTouchMode) {
        try {
            this.mClient.windowFocusChanged(focused, inTouchMode);
        } catch (RemoteException e) {
        }
        RemoteCallbackList<IWindowFocusObserver> remoteCallbackList = this.mFocusCallbacks;
        if (remoteCallbackList != null) {
            int N = remoteCallbackList.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IWindowFocusObserver obs = this.mFocusCallbacks.getBroadcastItem(i);
                if (focused) {
                    try {
                        obs.focusGained(this.mWindowId.asBinder());
                    } catch (RemoteException e2) {
                    }
                } else {
                    obs.focusLost(this.mWindowId.asBinder());
                }
            }
            this.mFocusCallbacks.finishBroadcast();
        }
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public Configuration getConfiguration() {
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null && appWindowToken.mFrozenMergedConfig.size() > 0) {
            return this.mAppToken.mFrozenMergedConfig.peek();
        }
        if (!registeredForDisplayConfigChanges()) {
            return super.getConfiguration();
        }
        this.mTempConfiguration.setTo(getProcessGlobalConfiguration());
        this.mTempConfiguration.updateFrom(getMergedOverrideConfiguration());
        return this.mTempConfiguration;
    }

    private boolean registeredForDisplayConfigChanges() {
        WindowProcessController app;
        WindowState parentWindow = getParentWindow();
        Session session = parentWindow != null ? parentWindow.mSession : this.mSession;
        return session.mPid != ActivityManagerService.MY_PID && session.mPid >= 0 && (app = this.mWmService.mAtmService.getProcessController(session.mPid, session.mUid)) != null && app.registeredForDisplayConfigChanges();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Can't wrap try/catch for region: R(23:1|2|3|(1:64)|7|(1:11)|12|(1:14)(1:63)|15|(3:16|17|18)|(3:43|44|(13:46|47|48|49|50|23|(1:29)|30|31|32|33|34|35))|20|21|22|23|(2:25|29)|30|31|32|33|34|35|(1:(0))) */
    /* JADX WARN: Can't wrap try/catch for region: R(25:1|2|3|(1:64)|7|(1:11)|12|(1:14)(1:63)|15|16|17|18|(3:43|44|(13:46|47|48|49|50|23|(1:29)|30|31|32|33|34|35))|20|21|22|23|(2:25|29)|30|31|32|33|34|35|(1:(0))) */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void reportResized() {
        /*
            Method dump skipped, instructions count: 377
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowState.reportResized():void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateLocationInParentDisplayIfNeeded() {
        int embeddedDisplayContentsSize = this.mEmbeddedDisplayContents.size();
        if (embeddedDisplayContentsSize != 0) {
            for (int i = embeddedDisplayContentsSize - 1; i >= 0; i--) {
                DisplayContent edc = this.mEmbeddedDisplayContents.valueAt(i);
                edc.notifyLocationInParentDisplayChanged();
            }
        }
        DisplayContent dc = getDisplayContent();
        if (dc.getParentWindow() == null) {
            return;
        }
        Point offset = dc.getLocationInParentDisplay();
        if (this.mLastReportedDisplayOffset.equals(offset)) {
            return;
        }
        this.mLastReportedDisplayOffset.set(offset.x, offset.y);
        try {
            this.mClient.locationInParentDisplayChanged(this.mLastReportedDisplayOffset);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to update offset from DisplayContent", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyInsetsChanged() {
        try {
            this.mClient.insetsChanged(getDisplayContent().getInsetsStateController().getInsetsForDispatch(this));
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver inset state change", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyInsetsControlChanged() {
        InsetsStateController stateController = getDisplayContent().getInsetsStateController();
        try {
            this.mClient.insetsControlChanged(stateController.getInsetsForDispatch(this), stateController.getControlsForDispatch(this));
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to deliver inset state change", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getBackdropFrame(Rect frame) {
        boolean resizing = isDragResizing() || isDragResizeChanged();
        if (getWindowConfiguration().useWindowFrameForBackdrop() || !resizing) {
            this.mTmpRect.set(frame);
            this.mTmpRect.offsetTo(0, 0);
            return this.mTmpRect;
        }
        DisplayInfo displayInfo = getDisplayInfo();
        this.mTmpRect.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
        return this.mTmpRect;
    }

    private int getStackId() {
        TaskStack stack = getStack();
        if (stack == null) {
            return -1;
        }
        return stack.mStackId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchResized(Rect frame, Rect overscanInsets, Rect contentInsets, Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw, MergedConfiguration mergedConfiguration, boolean reportOrientation, int displayId, DisplayCutout displayCutout) throws RemoteException {
        boolean forceRelayout = isDragResizeChanged() || reportOrientation;
        this.mClient.resized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets, outsets, reportDraw, mergedConfiguration, getBackdropFrame(frame), forceRelayout, getDisplayContent().getDisplayPolicy().areSystemBarsForcedShownLw(this), displayId, new DisplayCutout.ParcelableWrapper(displayCutout));
        this.mDragResizingChangeReported = true;
    }

    public void registerFocusObserver(IWindowFocusObserver observer) {
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mFocusCallbacks == null) {
                    this.mFocusCallbacks = new RemoteCallbackList<>();
                }
                this.mFocusCallbacks.register(observer);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    public void unregisterFocusObserver(IWindowFocusObserver observer) {
        synchronized (this.mWmService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mFocusCallbacks != null) {
                    this.mFocusCallbacks.unregister(observer);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFocused() {
        return getDisplayContent().mCurrentFocus == this;
    }

    private boolean inAppWindowThatMatchesParentBounds() {
        AppWindowToken appWindowToken = this.mAppToken;
        return appWindowToken == null || (appWindowToken.matchParentBounds() && !inMultiWindowMode());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLetterboxedAppWindow() {
        return isLetterboxedForDisplayCutoutLw();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isLetterboxedForDisplayCutoutLw() {
        if (this.mAppToken != null && this.mWindowFrames.parentFrameWasClippedByDisplayCutout() && this.mAttrs.layoutInDisplayCutoutMode != 1 && this.mAttrs.isFullscreen()) {
            return !frameCoversEntireAppTokenBounds();
        }
        return false;
    }

    private boolean frameCoversEntireAppTokenBounds() {
        this.mTmpRect.set(this.mAppToken.getBounds());
        this.mTmpRect.intersectUnchecked(this.mWindowFrames.mFrame);
        return this.mAppToken.getBounds().equals(this.mTmpRect);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isLetterboxedOverlappingWith(Rect rect) {
        AppWindowToken appWindowToken = this.mAppToken;
        return appWindowToken != null && appWindowToken.isLetterboxOverlappingWith(rect);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDragResizeChanged() {
        return this.mDragResizing != computeDragResizing();
    }

    @Override // com.android.server.wm.WindowContainer
    void setWaitingForDrawnIfResizingChanged() {
        if (isDragResizeChanged()) {
            this.mWmService.mWaitingForDrawn.add(this);
        }
        super.setWaitingForDrawnIfResizingChanged();
    }

    private boolean isDragResizingChangeReported() {
        return this.mDragResizingChangeReported;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void resetDragResizingChangeReported() {
        this.mDragResizingChangeReported = false;
        super.resetDragResizingChangeReported();
    }

    int getResizeMode() {
        return this.mResizeMode;
    }

    private boolean computeDragResizing() {
        AppWindowToken appWindowToken;
        Task task = getTask();
        if (task == null) {
            return false;
        }
        if ((!inSplitScreenWindowingMode() && !inFreeformWindowingMode()) || this.mAttrs.width != -1 || this.mAttrs.height != -1) {
            return false;
        }
        if (task.isDragResizing()) {
            return true;
        }
        return ((!getDisplayContent().mDividerControllerLocked.isResizing() && ((appWindowToken = this.mAppToken) == null || appWindowToken.mFrozenBounds.isEmpty())) || task.inFreeformWindowingMode() || isGoneForLayoutLw()) ? false : true;
    }

    void setDragResizing() {
        int i;
        boolean resizing = computeDragResizing();
        if (resizing == this.mDragResizing) {
            return;
        }
        this.mDragResizing = resizing;
        Task task = getTask();
        if (task != null && task.isDragResizing()) {
            this.mResizeMode = task.getDragResizeMode();
            return;
        }
        if (this.mDragResizing && getDisplayContent().mDividerControllerLocked.isResizing()) {
            i = 1;
        } else {
            i = 0;
        }
        this.mResizeMode = i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDragResizing() {
        return this.mDragResizing;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDockedResizing() {
        if (this.mDragResizing && getResizeMode() == 1) {
            return true;
        }
        return isChildWindow() && getParentWindow().isDockedResizing();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        boolean isVisible = isVisible();
        if (logLevel == 2 && !isVisible) {
            return;
        }
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        writeIdentifierToProto(proto, 1146756268034L);
        proto.write(1120986464259L, getDisplayId());
        proto.write(1120986464260L, getStackId());
        this.mAttrs.writeToProto(proto, 1146756268037L);
        this.mGivenContentInsets.writeToProto(proto, 1146756268038L);
        this.mWindowFrames.writeToProto(proto, 1146756268073L);
        this.mAttrs.surfaceInsets.writeToProto(proto, 1146756268044L);
        this.mSurfacePosition.writeToProto(proto, 1146756268048L);
        this.mWinAnimator.writeToProto(proto, 1146756268045L);
        proto.write(1133871366158L, this.mAnimatingExit);
        for (int i = 0; i < this.mChildren.size(); i++) {
            ((WindowState) this.mChildren.get(i)).writeToProto(proto, 2246267895823L, logLevel);
        }
        proto.write(1120986464274L, this.mRequestedWidth);
        proto.write(1120986464275L, this.mRequestedHeight);
        proto.write(1120986464276L, this.mViewVisibility);
        proto.write(1120986464277L, this.mSystemUiVisibility);
        proto.write(1133871366166L, this.mHasSurface);
        proto.write(1133871366167L, isReadyForDisplay());
        proto.write(1133871366178L, this.mRemoveOnExit);
        proto.write(1133871366179L, this.mDestroying);
        proto.write(1133871366180L, this.mRemoved);
        proto.write(1133871366181L, isOnScreen());
        proto.write(1133871366182L, isVisible);
        proto.write(1133871366183L, this.mPendingSeamlessRotate != null);
        proto.write(1112396529704L, this.mFinishSeamlessRotateFrameNumber);
        proto.write(1133871366186L, this.mForceSeamlesslyRotate);
        proto.end(token);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, System.identityHashCode(this));
        proto.write(1120986464258L, UserHandle.getUserId(this.mOwnerUid));
        CharSequence title = getWindowTag();
        if (title != null) {
            proto.write(1138166333443L, title.toString());
        }
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        TaskStack stack = getStack();
        pw.print(prefix + "mDisplayId=" + getDisplayId());
        if (stack != null) {
            pw.print(" stackId=" + stack.mStackId);
        }
        pw.println(" mSession=" + this.mSession + " mClient=" + this.mClient.asBinder());
        pw.println(prefix + "mOwnerUid=" + this.mOwnerUid + " mShowToOwnerOnly=" + this.mShowToOwnerOnly + " package=" + this.mAttrs.packageName + " appop=" + AppOpsManager.opToName(this.mAppOp));
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        sb.append("mAttrs=");
        sb.append(this.mAttrs.toString(prefix));
        pw.println(sb.toString());
        pw.println(prefix + "Requested w=" + this.mRequestedWidth + " h=" + this.mRequestedHeight + " mLayoutSeq=" + this.mLayoutSeq);
        if (this.mRequestedWidth != this.mLastRequestedWidth || this.mRequestedHeight != this.mLastRequestedHeight) {
            pw.println(prefix + "LastRequested w=" + this.mLastRequestedWidth + " h=" + this.mLastRequestedHeight);
        }
        if (this.mIsChildWindow || this.mLayoutAttached) {
            pw.println(prefix + "mParentWindow=" + getParentWindow() + " mLayoutAttached=" + this.mLayoutAttached);
        }
        if (this.mIsImWindow || this.mIsWallpaper || this.mIsFloatingLayer) {
            pw.println(prefix + "mIsImWindow=" + this.mIsImWindow + " mIsWallpaper=" + this.mIsWallpaper + " mIsFloatingLayer=" + this.mIsFloatingLayer + " mWallpaperVisible=" + this.mWallpaperVisible);
        }
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mBaseLayer=");
            pw.print(this.mBaseLayer);
            pw.print(" mSubLayer=");
            pw.print(this.mSubLayer);
        }
        if (dumpAll) {
            pw.println(prefix + "mToken=" + this.mToken);
            if (this.mAppToken != null) {
                pw.println(prefix + "mAppToken=" + this.mAppToken);
                pw.print(prefix + "mAppDied=" + this.mAppDied);
                pw.print(prefix + "drawnStateEvaluated=" + getDrawnStateEvaluated());
                pw.println(prefix + "mightAffectAllDrawn=" + mightAffectAllDrawn());
            }
            pw.println(prefix + "mViewVisibility=0x" + Integer.toHexString(this.mViewVisibility) + " mHaveFrame=" + this.mHaveFrame + " mObscured=" + this.mObscured);
            StringBuilder sb2 = new StringBuilder();
            sb2.append(prefix);
            sb2.append("mSeq=");
            sb2.append(this.mSeq);
            sb2.append(" mSystemUiVisibility=0x");
            sb2.append(Integer.toHexString(this.mSystemUiVisibility));
            pw.println(sb2.toString());
        }
        if (!isVisibleByPolicy() || !this.mLegacyPolicyVisibilityAfterAnim || !this.mAppOpVisibility || isParentWindowHidden() || this.mPermanentlyHidden || this.mForceHideNonSystemOverlayWindow || this.mHiddenWhileSuspended) {
            pw.println(prefix + "mPolicyVisibility=" + isVisibleByPolicy() + " mLegacyPolicyVisibilityAfterAnim=" + this.mLegacyPolicyVisibilityAfterAnim + " mAppOpVisibility=" + this.mAppOpVisibility + " parentHidden=" + isParentWindowHidden() + " mPermanentlyHidden=" + this.mPermanentlyHidden + " mHiddenWhileSuspended=" + this.mHiddenWhileSuspended + " mForceHideNonSystemOverlayWindow=" + this.mForceHideNonSystemOverlayWindow);
        }
        if (!this.mRelayoutCalled || this.mLayoutNeeded) {
            pw.println(prefix + "mRelayoutCalled=" + this.mRelayoutCalled + " mLayoutNeeded=" + this.mLayoutNeeded);
        }
        if (dumpAll) {
            pw.println(prefix + "mGivenContentInsets=" + this.mGivenContentInsets.toShortString(sTmpSB) + " mGivenVisibleInsets=" + this.mGivenVisibleInsets.toShortString(sTmpSB));
            if (this.mTouchableInsets != 0 || this.mGivenInsetsPending) {
                pw.println(prefix + "mTouchableInsets=" + this.mTouchableInsets + " mGivenInsetsPending=" + this.mGivenInsetsPending);
                Region region = new Region();
                getTouchableRegion(region);
                pw.println(prefix + "touchable region=" + region);
            }
            pw.println(prefix + "mFullConfiguration=" + getConfiguration());
            pw.println(prefix + "mLastReportedConfiguration=" + getLastReportedConfiguration());
        }
        pw.println(prefix + "mHasSurface=" + this.mHasSurface + " isReadyForDisplay()=" + isReadyForDisplay() + " mWindowRemovalAllowed=" + this.mWindowRemovalAllowed);
        if (inSizeCompatMode()) {
            pw.println(prefix + "mCompatFrame=" + this.mWindowFrames.mCompatFrame.toShortString(sTmpSB));
        }
        if (dumpAll) {
            this.mWindowFrames.dump(pw, prefix);
            pw.println(prefix + " surface=" + this.mAttrs.surfaceInsets.toShortString(sTmpSB));
        }
        super.dump(pw, prefix, dumpAll);
        pw.println(prefix + this.mWinAnimator + ":");
        WindowStateAnimator windowStateAnimator = this.mWinAnimator;
        windowStateAnimator.dump(pw, prefix + "  ", dumpAll);
        if (this.mAnimatingExit || this.mRemoveOnExit || this.mDestroying || this.mRemoved) {
            pw.println(prefix + "mAnimatingExit=" + this.mAnimatingExit + " mRemoveOnExit=" + this.mRemoveOnExit + " mDestroying=" + this.mDestroying + " mRemoved=" + this.mRemoved);
        }
        if (getOrientationChanging() || this.mAppFreezing || this.mReportOrientationChanged) {
            StringBuilder sb3 = new StringBuilder();
            sb3.append(prefix);
            sb3.append("mOrientationChanging=");
            sb3.append(this.mOrientationChanging);
            sb3.append(" configOrientationChanging=");
            sb3.append(getLastReportedConfiguration().orientation != getConfiguration().orientation);
            sb3.append(" mAppFreezing=");
            sb3.append(this.mAppFreezing);
            sb3.append(" mReportOrientationChanged=");
            sb3.append(this.mReportOrientationChanged);
            pw.println(sb3.toString());
        }
        if (this.mLastFreezeDuration != 0) {
            pw.print(prefix + "mLastFreezeDuration=");
            TimeUtils.formatDuration((long) this.mLastFreezeDuration, pw);
            pw.println();
        }
        pw.print(prefix + "mForceSeamlesslyRotate=" + this.mForceSeamlesslyRotate + " seamlesslyRotate: pending=");
        SeamlessRotator seamlessRotator = this.mPendingSeamlessRotate;
        if (seamlessRotator != null) {
            seamlessRotator.dump(pw);
        } else {
            pw.print("null");
        }
        pw.println(" finishedFrameNumber=" + this.mFinishSeamlessRotateFrameNumber);
        if (this.mHScale != 1.0f || this.mVScale != 1.0f) {
            pw.println(prefix + "mHScale=" + this.mHScale + " mVScale=" + this.mVScale);
        }
        if (this.mWallpaperX != -1.0f || this.mWallpaperY != -1.0f) {
            pw.println(prefix + "mWallpaperX=" + this.mWallpaperX + " mWallpaperY=" + this.mWallpaperY);
        }
        if (this.mWallpaperXStep != -1.0f || this.mWallpaperYStep != -1.0f) {
            pw.println(prefix + "mWallpaperXStep=" + this.mWallpaperXStep + " mWallpaperYStep=" + this.mWallpaperYStep);
        }
        if (this.mWallpaperDisplayOffsetX != Integer.MIN_VALUE || this.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            pw.println(prefix + "mWallpaperDisplayOffsetX=" + this.mWallpaperDisplayOffsetX + " mWallpaperDisplayOffsetY=" + this.mWallpaperDisplayOffsetY);
        }
        if (this.mDrawLock != null) {
            pw.println(prefix + "mDrawLock=" + this.mDrawLock);
        }
        if (isDragResizing()) {
            pw.println(prefix + "isDragResizing=" + isDragResizing());
        }
        if (computeDragResizing()) {
            pw.println(prefix + "computeDragResizing=" + computeDragResizing());
        }
        pw.println(prefix + "isOnScreen=" + isOnScreen());
        pw.println(prefix + "isVisible=" + isVisible());
        pw.println(prefix + "mEmbeddedDisplayContents=" + this.mEmbeddedDisplayContents);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public String getName() {
        return Integer.toHexString(System.identityHashCode(this)) + " " + ((Object) getWindowTag());
    }

    CharSequence getWindowTag() {
        CharSequence tag = this.mAttrs.getTitle();
        if (tag == null || tag.length() <= 0) {
            return this.mAttrs.packageName;
        }
        return tag;
    }

    public String toString() {
        CharSequence title = getWindowTag();
        if (this.mStringNameCache == null || this.mLastTitle != title || this.mWasExiting != this.mAnimatingExit) {
            this.mLastTitle = title;
            this.mWasExiting = this.mAnimatingExit;
            StringBuilder sb = new StringBuilder();
            sb.append("Window{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" u");
            sb.append(UserHandle.getUserId(this.mOwnerUid));
            sb.append(" ");
            sb.append((Object) this.mLastTitle);
            sb.append(this.mAnimatingExit ? " EXITING}" : "}");
            this.mStringNameCache = sb.toString();
        }
        return this.mStringNameCache;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void transformClipRectFromScreenToSurfaceSpace(Rect clipRect) {
        if (this.mHScale == 1.0f && this.mVScale == 1.0f) {
            return;
        }
        if (this.mHScale >= 0.0f) {
            clipRect.left = (int) (clipRect.left / this.mHScale);
            clipRect.right = (int) Math.ceil(clipRect.right / this.mHScale);
        }
        if (this.mVScale >= 0.0f) {
            clipRect.top = (int) (clipRect.top / this.mVScale);
            clipRect.bottom = (int) Math.ceil(clipRect.bottom / this.mVScale);
        }
    }

    private void applyGravityAndUpdateFrame(Rect containingFrame, Rect displayFrame) {
        int w;
        int h;
        float x;
        float y;
        int pw = containingFrame.width();
        int ph = containingFrame.height();
        Task task = getTask();
        boolean fitToDisplay = true;
        boolean inNonFullscreenContainer = !inAppWindowThatMatchesParentBounds();
        boolean noLimits = (this.mAttrs.flags & 512) != 0;
        if (task != null && inNonFullscreenContainer && (this.mAttrs.type == 1 || this.mAttrs.type == 5 || this.mAttrs.type == 12 || this.mAttrs.type == 10 || this.mAttrs.type == 6 || noLimits)) {
            fitToDisplay = false;
        }
        boolean inSizeCompatMode = inSizeCompatMode();
        if ((this.mAttrs.flags & 16384) != 0) {
            if (this.mAttrs.width < 0) {
                w = pw;
            } else if (inSizeCompatMode) {
                w = (int) ((this.mAttrs.width * this.mGlobalScale) + 0.5f);
            } else {
                w = this.mAttrs.width;
            }
            if (this.mAttrs.height < 0) {
                h = ph;
            } else {
                h = inSizeCompatMode ? (int) ((this.mAttrs.height * this.mGlobalScale) + 0.5f) : this.mAttrs.height;
            }
        } else {
            if (this.mAttrs.width == -1) {
                w = pw;
            } else if (inSizeCompatMode) {
                w = (int) ((this.mRequestedWidth * this.mGlobalScale) + 0.5f);
            } else {
                w = this.mRequestedWidth;
            }
            if (this.mAttrs.height == -1) {
                h = ph;
            } else if (inSizeCompatMode) {
                h = (int) ((this.mRequestedHeight * this.mGlobalScale) + 0.5f);
            } else {
                h = this.mRequestedHeight;
            }
        }
        if (inSizeCompatMode) {
            x = this.mAttrs.x * this.mGlobalScale;
            y = this.mAttrs.y * this.mGlobalScale;
        } else {
            x = this.mAttrs.x;
            y = this.mAttrs.y;
        }
        if (inNonFullscreenContainer && !layoutInParentFrame()) {
            w = Math.min(w, pw);
            h = Math.min(h, ph);
        }
        if (w == 0 && h == 0 && ((this.mAttrs.width > 0 || this.mAttrs.height > 0) && xpWindowManager.isApplicationWindowType(this.mAttrs.type))) {
            w = this.mAttrs.width;
            h = this.mAttrs.height;
        }
        Gravity.apply(this.mAttrs.gravity, w, h, containingFrame, (int) ((this.mAttrs.horizontalMargin * pw) + x), (int) ((this.mAttrs.verticalMargin * ph) + y), this.mWindowFrames.mFrame);
        if (fitToDisplay) {
            Gravity.applyDisplay(this.mAttrs.gravity, displayFrame, this.mWindowFrames.mFrame);
        }
        this.mWindowFrames.mCompatFrame.set(this.mWindowFrames.mFrame);
        if (inSizeCompatMode) {
            this.mWindowFrames.mCompatFrame.scale(this.mInvGlobalScale);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isChildWindow() {
        return this.mIsChildWindow;
    }

    boolean layoutInParentFrame() {
        return this.mIsChildWindow && (this.mAttrs.privateFlags & 65536) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hideNonSystemOverlayWindowsWhenVisible() {
        return (this.mAttrs.privateFlags & DumpState.DUMP_FROZEN) != 0 && this.mSession.mCanHideNonSystemOverlayWindows;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getParentWindow() {
        if (this.mIsChildWindow) {
            return (WindowState) super.getParent();
        }
        return null;
    }

    WindowState getTopParentWindow() {
        WindowState current = this;
        WindowState topParent = current;
        while (current != null && current.mIsChildWindow) {
            current = current.getParentWindow();
            if (current != null) {
                topParent = current;
            }
        }
        return topParent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isParentWindowHidden() {
        WindowState parent = getParentWindow();
        return parent != null && parent.mHidden;
    }

    private boolean isParentWindowGoneForLayout() {
        WindowState parent = getParentWindow();
        return parent != null && parent.isGoneForLayoutLw();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWillReplaceWindow(boolean animate) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.setWillReplaceWindow(animate);
        }
        if ((this.mAttrs.privateFlags & 32768) != 0 || this.mAttrs.type == 3) {
            return;
        }
        this.mWillReplaceWindow = true;
        this.mReplacementWindow = null;
        this.mAnimateReplacingWindow = animate;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearWillReplaceWindow() {
        this.mWillReplaceWindow = false;
        this.mReplacementWindow = null;
        this.mAnimateReplacingWindow = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.clearWillReplaceWindow();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean waitingForReplacement() {
        if (this.mWillReplaceWindow) {
            return true;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            if (c.waitingForReplacement()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestUpdateWallpaperIfNeeded() {
        DisplayContent dc = getDisplayContent();
        if (dc != null && (this.mAttrs.flags & DumpState.DUMP_DEXOPT) != 0) {
            dc.pendingLayoutChanges |= 4;
            dc.setLayoutNeeded();
            this.mWmService.mWindowPlacerLocked.requestTraversal();
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.requestUpdateWallpaperIfNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float translateToWindowX(float x) {
        float winX = x - this.mWindowFrames.mFrame.left;
        if (inSizeCompatMode()) {
            return winX * this.mGlobalScale;
        }
        return winX;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float translateToWindowY(float y) {
        float winY = y - this.mWindowFrames.mFrame.top;
        if (inSizeCompatMode()) {
            return winY * this.mGlobalScale;
        }
        return winY;
    }

    boolean shouldBeReplacedWithChildren() {
        return this.mIsChildWindow || this.mAttrs.type == 2 || this.mAttrs.type == 4;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWillReplaceChildWindows() {
        if (shouldBeReplacedWithChildren()) {
            setWillReplaceWindow(false);
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.setWillReplaceChildWindows();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getReplacingWindow() {
        if (this.mAnimatingExit && this.mWillReplaceWindow && this.mAnimateReplacingWindow) {
            return this;
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            WindowState replacing = c.getReplacingWindow();
            if (replacing != null) {
                return replacing;
            }
        }
        return null;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public int getRotationAnimationHint() {
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null) {
            return appWindowToken.mRotationAnimationHint;
        }
        return -1;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isInputMethodWindow() {
        return this.mIsImWindow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean performShowLocked() {
        AppWindowToken appWindowToken;
        if (isHiddenFromUserLocked()) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.w(TAG, "hiding " + this + ", belonging to " + this.mOwnerUid);
            }
            clearPolicyVisibilityFlag(2);
            return false;
        }
        logPerformShow("performShow on ");
        int drawState = this.mWinAnimator.mDrawState;
        if ((drawState == 4 || drawState == 3) && this.mAttrs.type != 3 && (appWindowToken = this.mAppToken) != null) {
            appWindowToken.onFirstWindowDrawn(this, this.mWinAnimator);
        }
        if (this.mWinAnimator.mDrawState == 3 && isReadyForDisplay()) {
            logPerformShow("Showing ");
            this.mWmService.enableScreenIfNeededLocked();
            this.mWinAnimator.applyEnterAnimationLocked();
            this.mWinAnimator.mLastAlpha = -1.0f;
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.v(TAG, "performShowLocked: mDrawState=HAS_DRAWN in " + this);
            }
            this.mWinAnimator.mDrawState = 4;
            this.mWmService.scheduleAnimationLocked();
            if (this.mHidden) {
                this.mHidden = false;
                DisplayContent displayContent = getDisplayContent();
                for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                    WindowState c = (WindowState) this.mChildren.get(i);
                    if (c.mWinAnimator.mSurfaceController != null) {
                        c.performShowLocked();
                        if (displayContent != null) {
                            displayContent.setLayoutNeeded();
                        }
                    }
                }
            }
            if (this.mAttrs.type == 2011) {
                getDisplayContent().mDividerControllerLocked.resetImeHideRequested();
            }
            return true;
        }
        return false;
    }

    private void logPerformShow(String prefix) {
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY || (WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE && this.mAttrs.type == 3)) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix);
            sb.append(this);
            sb.append(": mDrawState=");
            sb.append(this.mWinAnimator.drawStateToString());
            sb.append(" readyForDisplay=");
            sb.append(isReadyForDisplay());
            sb.append(" starting=");
            boolean z = true;
            sb.append(this.mAttrs.type == 3);
            sb.append(" during animation: policyVis=");
            sb.append(isVisibleByPolicy());
            sb.append(" parentHidden=");
            sb.append(isParentWindowHidden());
            sb.append(" tok.hiddenRequested=");
            AppWindowToken appWindowToken = this.mAppToken;
            sb.append(appWindowToken != null && appWindowToken.hiddenRequested);
            sb.append(" tok.hidden=");
            AppWindowToken appWindowToken2 = this.mAppToken;
            sb.append(appWindowToken2 != null && appWindowToken2.isHidden());
            sb.append(" animating=");
            sb.append(isAnimating());
            sb.append(" tok animating=");
            AppWindowToken appWindowToken3 = this.mAppToken;
            if (appWindowToken3 == null || !appWindowToken3.isSelfAnimating()) {
                z = false;
            }
            sb.append(z);
            sb.append(" Callers=");
            sb.append(Debug.getCallers(4));
            Slog.v(TAG, sb.toString());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowInfo getWindowInfo() {
        WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.type = this.mAttrs.type;
        windowInfo.layer = this.mLayer;
        windowInfo.token = this.mClient.asBinder();
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null) {
            windowInfo.activityToken = appWindowToken.appToken.asBinder();
        }
        windowInfo.title = this.mAttrs.accessibilityTitle;
        boolean isPanelWindow = this.mAttrs.type >= 1000 && this.mAttrs.type <= 1999;
        boolean isAccessibilityOverlay = windowInfo.type == 2032;
        if (TextUtils.isEmpty(windowInfo.title) && (isPanelWindow || isAccessibilityOverlay)) {
            CharSequence title = this.mAttrs.getTitle();
            windowInfo.title = TextUtils.isEmpty(title) ? null : title;
        }
        windowInfo.accessibilityIdOfAnchor = this.mAttrs.accessibilityIdOfAnchor;
        windowInfo.focused = isFocused();
        Task task = getTask();
        windowInfo.inPictureInPicture = task != null && task.inPinnedWindowingMode();
        windowInfo.hasFlagWatchOutsideTouch = (this.mAttrs.flags & 262144) != 0;
        if (this.mIsChildWindow) {
            windowInfo.parentToken = getParentWindow().mClient.asBinder();
        }
        int childCount = this.mChildren.size();
        if (childCount > 0) {
            if (windowInfo.childTokens == null) {
                windowInfo.childTokens = new ArrayList(childCount);
            }
            for (int j = 0; j < childCount; j++) {
                WindowState child = (WindowState) this.mChildren.get(j);
                windowInfo.childTokens.add(child.mClient.asBinder());
            }
        }
        return windowInfo;
    }

    @Override // com.android.server.wm.WindowContainer
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (this.mChildren.isEmpty()) {
            return applyInOrderWithImeWindows(callback, traverseTopToBottom);
        }
        if (traverseTopToBottom) {
            return forAllWindowTopToBottom(callback);
        }
        return forAllWindowBottomToTop(callback);
    }

    /* JADX WARN: Code restructure failed: missing block: B:15:0x0031, code lost:
        if (applyInOrderWithImeWindows(r7, false) == false) goto L16;
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x0033, code lost:
        return true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x0034, code lost:
        if (r0 >= r1) goto L26;
     */
    /* JADX WARN: Code restructure failed: missing block: B:19:0x003a, code lost:
        if (r2.applyInOrderWithImeWindows(r7, false) == false) goto L19;
     */
    /* JADX WARN: Code restructure failed: missing block: B:20:0x003c, code lost:
        return true;
     */
    /* JADX WARN: Code restructure failed: missing block: B:21:0x003d, code lost:
        r0 = r0 + 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:22:0x003f, code lost:
        if (r0 < r1) goto L21;
     */
    /* JADX WARN: Code restructure failed: missing block: B:24:0x0042, code lost:
        r2 = (com.android.server.wm.WindowState) r6.mChildren.get(r0);
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x004c, code lost:
        return false;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean forAllWindowBottomToTop(com.android.internal.util.ToBooleanFunction<com.android.server.wm.WindowState> r7) {
        /*
            r6 = this;
            r0 = 0
            com.android.server.wm.WindowList<E extends com.android.server.wm.WindowContainer> r1 = r6.mChildren
            int r1 = r1.size()
            com.android.server.wm.WindowList<E extends com.android.server.wm.WindowContainer> r2 = r6.mChildren
            java.lang.Object r2 = r2.get(r0)
            com.android.server.wm.WindowState r2 = (com.android.server.wm.WindowState) r2
        Lf:
            r3 = 0
            r4 = 1
            if (r0 >= r1) goto L2d
            int r5 = r2.mSubLayer
            if (r5 >= 0) goto L2d
            boolean r5 = r2.applyInOrderWithImeWindows(r7, r3)
            if (r5 == 0) goto L1e
            return r4
        L1e:
            int r0 = r0 + 1
            if (r0 < r1) goto L23
            goto L2d
        L23:
            com.android.server.wm.WindowList<E extends com.android.server.wm.WindowContainer> r3 = r6.mChildren
            java.lang.Object r3 = r3.get(r0)
            r2 = r3
            com.android.server.wm.WindowState r2 = (com.android.server.wm.WindowState) r2
            goto Lf
        L2d:
            boolean r5 = r6.applyInOrderWithImeWindows(r7, r3)
            if (r5 == 0) goto L34
            return r4
        L34:
            if (r0 >= r1) goto L4c
            boolean r5 = r2.applyInOrderWithImeWindows(r7, r3)
            if (r5 == 0) goto L3d
            return r4
        L3d:
            int r0 = r0 + 1
            if (r0 < r1) goto L42
            goto L4c
        L42:
            com.android.server.wm.WindowList<E extends com.android.server.wm.WindowContainer> r5 = r6.mChildren
            java.lang.Object r5 = r5.get(r0)
            r2 = r5
            com.android.server.wm.WindowState r2 = (com.android.server.wm.WindowState) r2
            goto L34
        L4c:
            return r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowState.forAllWindowBottomToTop(com.android.internal.util.ToBooleanFunction):boolean");
    }

    private boolean forAllWindowTopToBottom(ToBooleanFunction<WindowState> callback) {
        int i = this.mChildren.size() - 1;
        WindowState child = (WindowState) this.mChildren.get(i);
        while (i >= 0 && child.mSubLayer >= 0) {
            if (child.applyInOrderWithImeWindows(callback, true)) {
                return true;
            }
            i--;
            if (i < 0) {
                break;
            }
            child = (WindowState) this.mChildren.get(i);
        }
        if (applyInOrderWithImeWindows(callback, true)) {
            return true;
        }
        while (i >= 0) {
            if (child.applyInOrderWithImeWindows(callback, true)) {
                return true;
            }
            i--;
            if (i >= 0) {
                child = (WindowState) this.mChildren.get(i);
            } else {
                return false;
            }
        }
        return false;
    }

    private boolean applyImeWindowsIfNeeded(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (isInputMethodTarget() && !inSplitScreenWindowingMode() && getDisplayContent().forAllImeWindows(callback, traverseTopToBottom)) {
            return true;
        }
        return false;
    }

    private boolean applyInOrderWithImeWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            if (applyImeWindowsIfNeeded(callback, traverseTopToBottom) || callback.apply(this)) {
                return true;
            }
            return false;
        } else if (callback.apply(this) || applyImeWindowsIfNeeded(callback, traverseTopToBottom)) {
            return true;
        } else {
            return false;
        }
    }

    @Override // com.android.server.wm.WindowContainer
    WindowState getWindow(Predicate<WindowState> callback) {
        if (this.mChildren.isEmpty()) {
            if (callback.test(this)) {
                return this;
            }
            return null;
        }
        int i = this.mChildren.size() - 1;
        WindowState child = (WindowState) this.mChildren.get(i);
        while (i >= 0 && child.mSubLayer >= 0) {
            if (callback.test(child)) {
                return child;
            }
            i--;
            if (i < 0) {
                break;
            }
            child = (WindowState) this.mChildren.get(i);
        }
        if (callback.test(this)) {
            return this;
        }
        while (i >= 0) {
            if (callback.test(child)) {
                return child;
            }
            i--;
            if (i < 0) {
                break;
            }
            child = (WindowState) this.mChildren.get(i);
        }
        return null;
    }

    @VisibleForTesting
    boolean isSelfOrAncestorWindowAnimatingExit() {
        WindowState window = this;
        while (!window.mAnimatingExit) {
            window = window.getParentWindow();
            if (window == null) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onExitAnimationDone() {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "onExitAnimationDone in " + this + ": exiting=" + this.mAnimatingExit + " remove=" + this.mRemoveOnExit + " selfAnimating=" + isSelfAnimating());
        }
        if (!this.mChildren.isEmpty()) {
            ArrayList<WindowState> childWindows = new ArrayList<>(this.mChildren);
            for (int i = childWindows.size() - 1; i >= 0; i--) {
                childWindows.get(i).onExitAnimationDone();
            }
        }
        if (this.mWinAnimator.mEnteringAnimation) {
            this.mWinAnimator.mEnteringAnimation = false;
            this.mWmService.requestTraversal();
            if (this.mAppToken == null) {
                try {
                    this.mClient.dispatchWindowShown();
                } catch (RemoteException e) {
                }
            }
        }
        if (isSelfAnimating()) {
            return;
        }
        if (this.mWmService.mAccessibilityController != null && (getDisplayId() == 0 || getDisplayContent().getParentWindow() != null)) {
            this.mWmService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
        }
        if (!isSelfOrAncestorWindowAnimatingExit()) {
            return;
        }
        if (WindowManagerService.localLOGV || WindowManagerDebugConfig.DEBUG_ADD_REMOVE) {
            Slog.v(TAG, "Exit animation finished in " + this + ": remove=" + this.mRemoveOnExit);
        }
        this.mDestroying = true;
        boolean hasSurface = this.mWinAnimator.hasSurface();
        this.mWinAnimator.hide(getPendingTransaction(), "onExitAnimationDone");
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null) {
            appWindowToken.destroySurfaces();
        } else {
            if (hasSurface) {
                this.mWmService.mDestroySurface.add(this);
            }
            if (this.mRemoveOnExit) {
                this.mWmService.mPendingRemove.add(this);
                this.mRemoveOnExit = false;
            }
        }
        this.mAnimatingExit = false;
        getDisplayContent().mWallpaperController.hideWallpapers(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean clearAnimatingFlags() {
        boolean didSomething = false;
        if (!this.mWillReplaceWindow && !this.mRemoveOnExit) {
            if (this.mAnimatingExit) {
                this.mAnimatingExit = false;
                didSomething = true;
            }
            if (this.mDestroying) {
                this.mDestroying = false;
                this.mWmService.mDestroySurface.remove(this);
                didSomething = true;
            }
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            didSomething |= ((WindowState) this.mChildren.get(i)).clearAnimatingFlags();
        }
        return didSomething;
    }

    public boolean isRtl() {
        return getConfiguration().getLayoutDirection() == 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hideWallpaperWindow(boolean wasDeferred, String reason) {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            WindowState c = (WindowState) this.mChildren.get(j);
            c.hideWallpaperWindow(wasDeferred, reason);
        }
        if (!this.mWinAnimator.mLastHidden || wasDeferred) {
            this.mWinAnimator.hide(reason);
            getDisplayContent().mWallpaperController.mDeferredHideWallpaper = null;
            dispatchWallpaperVisibility(false);
            DisplayContent displayContent = getDisplayContent();
            if (displayContent != null) {
                displayContent.pendingLayoutChanges |= 4;
                if (WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS) {
                    this.mWmService.mWindowPlacerLocked.debugLayoutRepeats("hideWallpaperWindow " + this, displayContent.pendingLayoutChanges);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dispatchWallpaperVisibility(boolean visible) {
        boolean hideAllowed = getDisplayContent().mWallpaperController.mDeferredHideWallpaper == null;
        if (this.mWallpaperVisible != visible) {
            if (hideAllowed || visible) {
                this.mWallpaperVisible = visible;
                try {
                    if (WindowManagerDebugConfig.DEBUG_VISIBILITY || WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                        Slog.v(TAG, "Updating vis of wallpaper " + this + ": " + visible + " from:\n" + Debug.getCallers(4, "  "));
                    }
                    this.mClient.dispatchAppVisibility(visible);
                } catch (RemoteException e) {
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasVisibleNotDrawnWallpaper() {
        if (!this.mWallpaperVisible || isDrawnLw()) {
            for (int j = this.mChildren.size() - 1; j >= 0; j--) {
                WindowState c = (WindowState) this.mChildren.get(j);
                if (c.hasVisibleNotDrawnWallpaper()) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateReportedVisibility(UpdateReportedVisibilityResults results) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.updateReportedVisibility(results);
        }
        if (this.mAppFreezing || this.mViewVisibility != 0 || this.mAttrs.type == 3 || this.mDestroying) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Win " + this + ": isDrawn=" + isDrawnLw() + ", animating=" + isAnimating());
            if (!isDrawnLw()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Not displayed: s=");
                sb.append(this.mWinAnimator.mSurfaceController);
                sb.append(" pv=");
                sb.append(isVisibleByPolicy());
                sb.append(" mDrawState=");
                sb.append(this.mWinAnimator.mDrawState);
                sb.append(" ph=");
                sb.append(isParentWindowHidden());
                sb.append(" th=");
                AppWindowToken appWindowToken = this.mAppToken;
                sb.append(appWindowToken != null ? appWindowToken.hiddenRequested : false);
                sb.append(" a=");
                sb.append(isAnimating());
                Slog.v(TAG, sb.toString());
            }
        }
        results.numInteresting++;
        if (isDrawnLw()) {
            results.numDrawn++;
            if (!isAnimating()) {
                results.numVisible++;
            }
            results.nowGone = false;
        } else if (isAnimating()) {
            results.nowGone = false;
        }
    }

    private boolean skipDecorCrop() {
        if (this.mWindowFrames.mDecorFrame.isEmpty()) {
            return true;
        }
        if (this.mAppToken != null) {
            return false;
        }
        return this.mToken.canLayerAboveSystemBars();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void calculatePolicyCrop(Rect policyCrop) {
        DisplayContent displayContent = getDisplayContent();
        if (!displayContent.isDefaultDisplay && !displayContent.supportsSystemDecorations()) {
            DisplayInfo displayInfo = displayContent.getDisplayInfo();
            policyCrop.set(0, 0, this.mWindowFrames.mCompatFrame.width(), this.mWindowFrames.mCompatFrame.height());
            policyCrop.intersect(-this.mWindowFrames.mCompatFrame.left, -this.mWindowFrames.mCompatFrame.top, displayInfo.logicalWidth - this.mWindowFrames.mCompatFrame.left, displayInfo.logicalHeight - this.mWindowFrames.mCompatFrame.top);
        } else if (skipDecorCrop()) {
            policyCrop.set(0, 0, this.mWindowFrames.mCompatFrame.width(), this.mWindowFrames.mCompatFrame.height());
        } else {
            calculateSystemDecorRect(policyCrop);
        }
    }

    private void calculateSystemDecorRect(Rect systemDecorRect) {
        Rect decorRect = this.mWindowFrames.mDecorFrame;
        int width = this.mWindowFrames.mFrame.width();
        int height = this.mWindowFrames.mFrame.height();
        int left = this.mWindowFrames.mFrame.left;
        int top = this.mWindowFrames.mFrame.top;
        boolean z = false;
        if (isDockedResizing()) {
            DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
            systemDecorRect.set(0, 0, Math.max(width, displayInfo.logicalWidth), Math.max(height, displayInfo.logicalHeight));
        } else {
            systemDecorRect.set(0, 0, width, height);
        }
        if ((!inFreeformWindowingMode() || !isAnimatingLw()) && !isDockedResizing()) {
            z = true;
        }
        boolean cropToDecor = z;
        if (cropToDecor) {
            systemDecorRect.intersect(decorRect.left - left, decorRect.top - top, decorRect.right - left, decorRect.bottom - top);
        }
        if (this.mInvGlobalScale != 1.0f && inSizeCompatMode()) {
            float scale = this.mInvGlobalScale;
            systemDecorRect.left = (int) ((systemDecorRect.left * scale) - 0.5f);
            systemDecorRect.top = (int) ((systemDecorRect.top * scale) - 0.5f);
            systemDecorRect.right = (int) (((systemDecorRect.right + 1) * scale) - 0.5f);
            systemDecorRect.bottom = (int) (((systemDecorRect.bottom + 1) * scale) - 0.5f);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void expandForSurfaceInsets(Rect r) {
        r.inset(-this.mAttrs.surfaceInsets.left, -this.mAttrs.surfaceInsets.top, -this.mAttrs.surfaceInsets.right, -this.mAttrs.surfaceInsets.bottom);
    }

    boolean surfaceInsetsChanging() {
        return !this.mLastSurfaceInsets.equals(this.mAttrs.surfaceInsets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int relayoutVisibleWindow(int result, int attrChanges) {
        boolean freeformResizing;
        boolean wasVisible = isVisibleLw();
        int result2 = result | ((wasVisible && isDrawnLw()) ? 0 : 2);
        if (this.mAnimatingExit) {
            Slog.d(TAG, "relayoutVisibleWindow: " + this + " mAnimatingExit=true, mRemoveOnExit=" + this.mRemoveOnExit + ", mDestroying=" + this.mDestroying);
            if (isSelfAnimating()) {
                cancelAnimation();
                destroySurfaceUnchecked();
            }
            this.mAnimatingExit = false;
        }
        if (this.mDestroying) {
            this.mDestroying = false;
            this.mWmService.mDestroySurface.remove(this);
        }
        boolean dockedResizing = true;
        if (!wasVisible) {
            this.mWinAnimator.mEnterAnimationPending = true;
        }
        this.mLastVisibleLayoutRotation = getDisplayContent().getRotation();
        this.mWinAnimator.mEnteringAnimation = true;
        Trace.traceBegin(32L, "prepareToDisplay");
        try {
            prepareWindowToDisplayDuringRelayout(wasVisible);
            Trace.traceEnd(32L);
            if ((attrChanges & 8) != 0 && !this.mWinAnimator.tryChangeFormatInPlaceLocked()) {
                this.mWinAnimator.preserveSurfaceLocked();
                result2 |= 6;
            }
            if (isDragResizeChanged()) {
                setDragResizing();
                if (this.mHasSurface && !isChildWindow()) {
                    this.mWinAnimator.preserveSurfaceLocked();
                    result2 |= 6;
                }
            }
            if (!isDragResizing() || getResizeMode() != 0) {
                freeformResizing = false;
            } else {
                freeformResizing = true;
            }
            if (!isDragResizing() || getResizeMode() != 1) {
                dockedResizing = false;
            }
            return result2 | (freeformResizing ? 16 : 0) | (dockedResizing ? 8 : 0);
        } catch (Throwable th) {
            Trace.traceEnd(32L);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLaidOut() {
        return this.mLayoutSeq != -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean addEmbeddedDisplayContent(DisplayContent dc) {
        return this.mEmbeddedDisplayContents.add(dc);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeEmbeddedDisplayContent(DisplayContent dc) {
        return this.mEmbeddedDisplayContents.remove(dc);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateLastInsetValues() {
        this.mWindowFrames.updateLastInsetValues();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startAnimation(Animation anim) {
        InsetsSourceProvider insetsSourceProvider = this.mInsetProvider;
        if (insetsSourceProvider != null && insetsSourceProvider.isControllable()) {
            return;
        }
        DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
        anim.initialize(this.mWindowFrames.mFrame.width(), this.mWindowFrames.mFrame.height(), displayInfo.appWidth, displayInfo.appHeight);
        anim.restrictDuration(JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        anim.scaleCurrentDuration(this.mWmService.getWindowAnimationScaleLocked());
        AnimationAdapter adapter = new LocalAnimationAdapter(new WindowAnimationSpec(anim, this.mSurfacePosition, false, 0.0f), this.mWmService.mSurfaceAnimationRunner);
        startAnimation(getPendingTransaction(), adapter);
        commitPendingTransaction();
    }

    private void startMoveAnimation(int left, int top) {
        InsetsSourceProvider insetsSourceProvider = this.mInsetProvider;
        if (insetsSourceProvider != null && insetsSourceProvider.isControllable()) {
            return;
        }
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "Setting move animation on " + this);
        }
        Point oldPosition = new Point();
        Point newPosition = new Point();
        transformFrameToSurfacePosition(this.mWindowFrames.mLastFrame.left, this.mWindowFrames.mLastFrame.top, oldPosition);
        transformFrameToSurfacePosition(left, top, newPosition);
        AnimationAdapter adapter = new LocalAnimationAdapter(new MoveAnimationSpec(oldPosition.x, oldPosition.y, newPosition.x, newPosition.y), this.mWmService.mSurfaceAnimationRunner);
        startAnimation(getPendingTransaction(), adapter);
    }

    private void startAnimation(SurfaceControl.Transaction t, AnimationAdapter adapter) {
        startAnimation(t, adapter, this.mWinAnimator.mLastHidden);
    }

    @Override // com.android.server.wm.WindowContainer
    protected void onAnimationFinished() {
        super.onAnimationFinished();
        this.mWinAnimator.onAnimationFinished();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getTransformationMatrix(float[] float9, Matrix outMatrix) {
        float9[0] = this.mWinAnimator.mDsDx;
        float9[3] = this.mWinAnimator.mDtDx;
        float9[1] = this.mWinAnimator.mDtDy;
        float9[4] = this.mWinAnimator.mDsDy;
        int x = this.mSurfacePosition.x;
        int y = this.mSurfacePosition.y;
        DisplayContent dc = getDisplayContent();
        while (dc != null && dc.getParentWindow() != null) {
            WindowState displayParent = dc.getParentWindow();
            x = (int) (x + (displayParent.mWindowFrames.mFrame.left - displayParent.mAttrs.surfaceInsets.left) + (dc.getLocationInParentWindow().x * displayParent.mGlobalScale) + 0.5f);
            y = (int) (y + (displayParent.mWindowFrames.mFrame.top - displayParent.mAttrs.surfaceInsets.top) + (dc.getLocationInParentWindow().y * displayParent.mGlobalScale) + 0.5f);
            dc = displayParent.getDisplayContent();
        }
        WindowContainer parent = getParent();
        if (isChildWindow()) {
            WindowState parentWindow = getParentWindow();
            x += parentWindow.mWindowFrames.mFrame.left - parentWindow.mAttrs.surfaceInsets.left;
            y += parentWindow.mWindowFrames.mFrame.top - parentWindow.mAttrs.surfaceInsets.top;
        } else if (parent != null) {
            Rect parentBounds = parent.getBounds();
            x += parentBounds.left;
            y += parentBounds.top;
        }
        float9[2] = x;
        float9[5] = y;
        float9[6] = 0.0f;
        float9[7] = 0.0f;
        float9[8] = 1.0f;
        outMatrix.setValues(float9);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static final class UpdateReportedVisibilityResults {
        boolean nowGone = true;
        int numDrawn;
        int numInteresting;
        int numVisible;

        /* JADX INFO: Access modifiers changed from: package-private */
        public void reset() {
            this.numInteresting = 0;
            this.numVisible = 0;
            this.numDrawn = 0;
            this.nowGone = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class WindowId extends IWindowId.Stub {
        private final WeakReference<WindowState> mOuter;

        private WindowId(WindowState outer) {
            this.mOuter = new WeakReference<>(outer);
        }

        public void registerFocusObserver(IWindowFocusObserver observer) {
            WindowState outer = this.mOuter.get();
            if (outer != null) {
                outer.registerFocusObserver(observer);
            }
        }

        public void unregisterFocusObserver(IWindowFocusObserver observer) {
            WindowState outer = this.mOuter.get();
            if (outer != null) {
                outer.unregisterFocusObserver(observer);
            }
        }

        public boolean isFocused() {
            boolean isFocused;
            WindowState outer = this.mOuter.get();
            if (outer != null) {
                synchronized (outer.mWmService.mGlobalLock) {
                    try {
                        WindowManagerService.boostPriorityForLockedSection();
                        isFocused = outer.isFocused();
                    } catch (Throwable th) {
                        WindowManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                WindowManagerService.resetPriorityAfterLockedSection();
                return isFocused;
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean shouldMagnify() {
        if (this.mAttrs.type == 2011 || this.mAttrs.type == 2012 || this.mAttrs.type == 2027 || this.mAttrs.type == 2019 || this.mAttrs.type == 2024) {
            return false;
        }
        return true;
    }

    @Override // com.android.server.wm.WindowContainer
    SurfaceSession getSession() {
        if (this.mSession.mSurfaceSession != null) {
            return this.mSession.mSurfaceSession;
        }
        return getParent().getSession();
    }

    @Override // com.android.server.wm.WindowContainer
    boolean needsZBoost() {
        AppWindowToken appToken;
        WindowState inputMethodTarget = getDisplayContent().mInputMethodTarget;
        if (this.mIsImWindow && inputMethodTarget != null && (appToken = inputMethodTarget.mAppToken) != null) {
            return appToken.needsZBoost();
        }
        return this.mWillReplaceWindow;
    }

    private void applyDims(Dimmer dimmer) {
        if (!this.mAnimatingExit && this.mAppDied) {
            this.mIsDimming = true;
            dimmer.dimAbove(getPendingTransaction(), this, 0.5f);
        } else if ((this.mAttrs.flags & 2) != 0 && isVisibleNow() && !this.mHidden) {
            this.mIsDimming = true;
            dimmer.dimBelow(getPendingTransaction(), this, this.mAttrs.dimAmount);
        }
    }

    @Override // com.android.server.wm.WindowContainer
    void prepareSurfaces() {
        WindowManager.LayoutParams layoutParams = this.mAttrs;
        int sharedId = layoutParams != null ? layoutParams.sharedId : -1;
        boolean systemDialog = xpWindowManager.isSystemAlertWindowType(this.mAttrs);
        Dimmer dimmer = systemDialog ? getDimmer(sharedId) : getDimmer();
        this.mIsDimming = false;
        if (dimmer != null) {
            applyDims(dimmer);
        }
        updateSurfacePosition();
        this.mWinAnimator.prepareSurfaceLocked(true);
        super.prepareSurfaces();
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        super.onAnimationLeashCreated(t, leash);
        t.setPosition(this.mSurfaceControl, 0.0f, 0.0f);
        this.mLastSurfacePosition.set(0, 0);
    }

    @Override // com.android.server.wm.WindowContainer, com.android.server.wm.SurfaceAnimator.Animatable
    public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        super.onAnimationLeashLost(t);
        updateSurfacePosition(t);
    }

    @Override // com.android.server.wm.WindowContainer
    void updateSurfacePosition() {
        updateSurfacePosition(getPendingTransaction());
    }

    @VisibleForTesting
    void updateSurfacePosition(SurfaceControl.Transaction t) {
        if (this.mSurfaceControl == null) {
            return;
        }
        transformFrameToSurfacePosition(this.mWindowFrames.mFrame.left, this.mWindowFrames.mFrame.top, this.mSurfacePosition);
        if (!this.mSurfaceAnimator.hasLeash() && this.mPendingSeamlessRotate == null && !this.mLastSurfacePosition.equals(this.mSurfacePosition)) {
            t.setPosition(this.mSurfaceControl, this.mSurfacePosition.x, this.mSurfacePosition.y);
            this.mLastSurfacePosition.set(this.mSurfacePosition.x, this.mSurfacePosition.y);
            if (surfaceInsetsChanging() && this.mWinAnimator.hasSurface()) {
                this.mLastSurfaceInsets.set(this.mAttrs.surfaceInsets);
                t.deferTransactionUntil(this.mSurfaceControl, this.mWinAnimator.mSurfaceController.mSurfaceControl.getHandle(), getFrameNumber());
            }
        }
    }

    private void transformFrameToSurfacePosition(int left, int top, Point outPoint) {
        outPoint.set(left, top);
        WindowContainer parentWindowContainer = getParent();
        if (isChildWindow()) {
            WindowState parent = getParentWindow();
            transformSurfaceInsetsPosition(this.mTmpPoint, parent.mAttrs.surfaceInsets);
            outPoint.offset((-parent.mWindowFrames.mFrame.left) + this.mTmpPoint.x, (-parent.mWindowFrames.mFrame.top) + this.mTmpPoint.y);
        } else if (parentWindowContainer != null) {
            Rect parentBounds = parentWindowContainer.getDisplayedBounds();
            outPoint.offset(-parentBounds.left, -parentBounds.top);
        }
        TaskStack stack = getStack();
        if (stack != null) {
            int outset = stack.getStackOutset();
            outPoint.offset(outset, outset);
        }
        transformSurfaceInsetsPosition(this.mTmpPoint, this.mAttrs.surfaceInsets);
        outPoint.offset(-this.mTmpPoint.x, -this.mTmpPoint.y);
    }

    private void transformSurfaceInsetsPosition(Point outPos, Rect surfaceInsets) {
        if (!inSizeCompatMode()) {
            outPos.x = surfaceInsets.left;
            outPos.y = surfaceInsets.top;
            return;
        }
        outPos.x = (int) ((surfaceInsets.left * this.mGlobalScale) + 0.5f);
        outPos.y = (int) ((surfaceInsets.top * this.mGlobalScale) + 0.5f);
    }

    boolean needsRelativeLayeringToIme() {
        WindowState imeTarget;
        if (inSplitScreenWindowingMode()) {
            if (!isChildWindow()) {
                return (this.mAppToken == null || (imeTarget = getDisplayContent().mInputMethodTarget) == null || imeTarget == this || imeTarget.mToken != this.mToken || imeTarget.compareTo((WindowContainer) this) > 0) ? false : true;
            } else if (getParentWindow().isInputMethodTarget()) {
                return true;
            }
            return false;
        }
        return false;
    }

    @Override // com.android.server.wm.WindowContainer
    void assignLayer(SurfaceControl.Transaction t, int layer) {
        if (needsRelativeLayeringToIme()) {
            getDisplayContent().assignRelativeLayerForImeTargetChild(t, this);
        } else {
            super.assignLayer(t, layer);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isDimming() {
        return this.mIsDimming;
    }

    @Override // com.android.server.wm.WindowContainer
    public void assignChildLayers(SurfaceControl.Transaction t) {
        int layer = 2;
        for (int i = 0; i < this.mChildren.size(); i++) {
            WindowState w = (WindowState) this.mChildren.get(i);
            if (w.mAttrs.type == 1001) {
                w.assignLayer(t, -2);
            } else if (w.mAttrs.type == 1004) {
                w.assignLayer(t, -1);
            } else {
                w.assignLayer(t, layer);
            }
            w.assignChildLayers(t);
            layer++;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateTapExcludeRegion(int regionId, Region region) {
        DisplayContent currentDisplay = getDisplayContent();
        if (currentDisplay == null) {
            throw new IllegalStateException("Trying to update window not attached to any display.");
        }
        if (this.mTapExcludeRegionHolder == null) {
            this.mTapExcludeRegionHolder = new TapExcludeRegionHolder();
            currentDisplay.mTapExcludeProvidingWindows.add(this);
        }
        this.mTapExcludeRegionHolder.updateRegion(regionId, region);
        currentDisplay.updateTouchExcludeRegion();
        currentDisplay.getInputMonitor().updateInputWindowsLw(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void amendTapExcludeRegion(Region region) {
        Region tempRegion = Region.obtain();
        this.mTmpRect.set(this.mWindowFrames.mFrame);
        this.mTmpRect.offsetTo(0, 0);
        this.mTapExcludeRegionHolder.amendRegion(tempRegion, this.mTmpRect);
        tempRegion.translate(this.mWindowFrames.mFrame.left, this.mWindowFrames.mFrame.top);
        region.op(tempRegion, Region.Op.UNION);
        tempRegion.recycle();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasTapExcludeRegion() {
        TapExcludeRegionHolder tapExcludeRegionHolder = this.mTapExcludeRegionHolder;
        return (tapExcludeRegionHolder == null || tapExcludeRegionHolder.isEmpty()) ? false : true;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isInputMethodTarget() {
        return getDisplayContent().mInputMethodTarget == this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getFrameNumber() {
        return this.mFrameNumber;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFrameNumber(long frameNumber) {
        this.mFrameNumber = frameNumber;
    }

    public void getMaxVisibleBounds(Rect out) {
        if (out.isEmpty()) {
            out.set(this.mWindowFrames.mVisibleFrame);
            return;
        }
        if (this.mWindowFrames.mVisibleFrame.left < out.left) {
            out.left = this.mWindowFrames.mVisibleFrame.left;
        }
        if (this.mWindowFrames.mVisibleFrame.top < out.top) {
            out.top = this.mWindowFrames.mVisibleFrame.top;
        }
        if (this.mWindowFrames.mVisibleFrame.right > out.right) {
            out.right = this.mWindowFrames.mVisibleFrame.right;
        }
        if (this.mWindowFrames.mVisibleFrame.bottom > out.bottom) {
            out.bottom = this.mWindowFrames.mVisibleFrame.bottom;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getInsetsForRelayout(Rect outOverscanInsets, Rect outContentInsets, Rect outVisibleInsets, Rect outStableInsets, Rect outOutsets) {
        outOverscanInsets.set(this.mWindowFrames.mOverscanInsets);
        outContentInsets.set(this.mWindowFrames.mContentInsets);
        outVisibleInsets.set(this.mWindowFrames.mVisibleInsets);
        outStableInsets.set(this.mWindowFrames.mStableInsets);
        outOutsets.set(this.mWindowFrames.mOutsets);
        this.mLastRelayoutContentInsets.set(this.mWindowFrames.mContentInsets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getContentInsets(Rect outContentInsets) {
        outContentInsets.set(this.mWindowFrames.mContentInsets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getContentInsets() {
        return this.mWindowFrames.mContentInsets;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getStableInsets(Rect outStableInsets) {
        outStableInsets.set(this.mWindowFrames.mStableInsets);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getStableInsets() {
        return this.mWindowFrames.mStableInsets;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetLastContentInsets() {
        this.mWindowFrames.resetLastContentInsets();
    }

    Rect getVisibleInsets() {
        return this.mWindowFrames.mVisibleInsets;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public WindowFrames getWindowFrames() {
        return this.mWindowFrames;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetContentChanged() {
        this.mWindowFrames.setContentChanged(false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setInsetProvider(InsetsSourceProvider insetProvider) {
        this.mInsetProvider = insetProvider;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public InsetsSourceProvider getInsetProvider() {
        return this.mInsetProvider;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class MoveAnimationSpec implements LocalAnimationAdapter.AnimationSpec {
        private final long mDuration;
        private Point mFrom;
        private Interpolator mInterpolator;
        private Point mTo;

        private MoveAnimationSpec(int fromX, int fromY, int toX, int toY) {
            this.mFrom = new Point();
            this.mTo = new Point();
            Animation anim = AnimationUtils.loadAnimation(WindowState.this.mContext, 17432777);
            this.mDuration = ((float) anim.computeDurationHint()) * WindowState.this.mWmService.getWindowAnimationScaleLocked();
            this.mInterpolator = anim.getInterpolator();
            this.mFrom.set(fromX, fromY);
            this.mTo.set(toX, toY);
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public long getDuration() {
            return this.mDuration;
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public void apply(SurfaceControl.Transaction t, SurfaceControl leash, long currentPlayTime) {
            float fraction = ((float) currentPlayTime) / ((float) getDuration());
            float v = this.mInterpolator.getInterpolation(fraction);
            t.setPosition(leash, this.mFrom.x + ((this.mTo.x - this.mFrom.x) * v), this.mFrom.y + ((this.mTo.y - this.mFrom.y) * v));
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "from=" + this.mFrom + " to=" + this.mTo + " duration=" + this.mDuration);
        }

        @Override // com.android.server.wm.LocalAnimationAdapter.AnimationSpec
        public void writeToProtoInner(ProtoOutputStream proto) {
            long token = proto.start(1146756268034L);
            this.mFrom.writeToProto(proto, 1146756268033L);
            this.mTo.writeToProto(proto, 1146756268034L);
            proto.write(1112396529667L, this.mDuration);
            proto.end(token);
        }
    }

    private Rect getOverrideBounds() {
        WindowManager.LayoutParams lp = this.mAttrs;
        if (lp == null) {
            return null;
        }
        try {
            int systemUiVisibility = lp.systemUiVisibility | lp.subtreeSystemUiVisibility;
            boolean isFullscreen = xpWindowManager.isFullscreen(systemUiVisibility, lp.flags, lp.xpFlags);
            boolean isApplication = xpWindowManager.isApplicationWindowType(lp.type);
            Rect displayBounds = this.mWindowFrames != null ? this.mWindowFrames.mDisplayFrame : null;
            if (isApplication && isFullscreen && xpWindowManager.rectValid(displayBounds)) {
                return new Rect(displayBounds);
            }
            if (this.mAppToken != null) {
                return this.mAppToken.getBounds();
            }
            return super.getBounds();
        } catch (Exception e) {
            return null;
        }
    }

    private Rect getOverrideTouchableRegion() {
        xpWindowManager.WindowInfo info = xpWindowManager.getWindowInfo(this.mAttrs);
        if (info != null) {
            return info.touchRegion;
        }
        return null;
    }

    private boolean isFullscreenAndFillsDisplay() {
        WindowManager.LayoutParams layoutParams = this.mAttrs;
        int windowType = layoutParams != null ? layoutParams.type : -1;
        if (SharedDisplayManager.enable()) {
            return windowType == 2043 || windowType == 2005;
        }
        return false;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public void setWindowAttrs(WindowManager.LayoutParams lp) {
        WindowManager.LayoutParams layoutParams = this.mAttrs;
        if (layoutParams != null && lp != null) {
            layoutParams.copyFrom(lp);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public WindowManager.LayoutParams getAppWindowAttrs() {
        WindowState win;
        AppWindowToken appWindowToken = this.mAppToken;
        if (appWindowToken != null && (win = appWindowToken.findMainWindow(true)) != null) {
            return win.mAttrs;
        }
        return null;
    }

    public void startAnimation(int type, Animation anim, Object extra) {
        if (type != 0) {
            if (type == 1) {
                startAnimation(anim);
                return;
            } else if (type == 2 && extra != null && (extra instanceof Point)) {
                Point point = (Point) extra;
                startMoveAnimation(point.x, point.y);
                return;
            } else {
                return;
            }
        }
        InsetsSourceProvider insetsSourceProvider = this.mInsetProvider;
        if (insetsSourceProvider == null || !insetsSourceProvider.isControllable()) {
            DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
            anim.initialize(this.mWindowFrames.mFrame.width(), this.mWindowFrames.mFrame.height(), displayInfo.appWidth, displayInfo.appHeight);
            WindowAnimationSpec spec = new WindowAnimationSpec(anim, this.mSurfacePosition, false, 0.0f);
            AnimationAdapter adapter = new LocalAnimationAdapter(spec, this.mWmService.mSurfaceAnimationRunner);
            startAnimation(getPendingTransaction(), adapter);
            commitPendingTransaction();
        }
    }

    public Point getSurfacePosition() {
        return this.mSurfacePosition;
    }
}
