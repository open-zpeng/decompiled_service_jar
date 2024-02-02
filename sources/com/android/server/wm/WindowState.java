package com.android.server.wm;

import android.app.AppOpsManager;
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
import android.util.DisplayMetrics;
import android.util.MergedConfiguration;
import android.util.Slog;
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
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.input.InputWindowHandle;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.LocalAnimationAdapter;
import com.android.server.wm.utils.CoordinateTransforms;
import com.android.server.wm.utils.WmDisplayCutout;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WindowState extends WindowContainer<WindowState> implements WindowManagerPolicy.WindowState {
    private static final float DEFAULT_DIM_AMOUNT_DEAD_WINDOW = 0.5f;
    static final int MINIMUM_VISIBLE_HEIGHT_IN_DP = 32;
    static final int MINIMUM_VISIBLE_WIDTH_IN_DP = 48;
    static final int RESIZE_HANDLE_WIDTH_IN_DP = 30;
    static final String TAG = "WindowManager";
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
    final Rect mCompatFrame;
    final Rect mContainingFrame;
    boolean mContentChanged;
    private final Rect mContentFrame;
    final Rect mContentInsets;
    private boolean mContentInsetsChanged;
    final Context mContext;
    private DeadWindowEventReceiver mDeadWindowEventReceiver;
    final DeathRecipient mDeathRecipient;
    final Rect mDecorFrame;
    boolean mDestroying;
    WmDisplayCutout mDisplayCutout;
    private boolean mDisplayCutoutChanged;
    final Rect mDisplayFrame;
    private boolean mDragResizing;
    private boolean mDragResizingChangeReported;
    private PowerManager.WakeLock mDrawLock;
    private boolean mDrawnStateEvaluated;
    boolean mEnforceSizeCompat;
    long mFinishForcedSeamlessRotateFrameNumber;
    private RemoteCallbackList<IWindowFocusObserver> mFocusCallbacks;
    private boolean mForceHideNonSystemOverlayWindow;
    final boolean mForceSeamlesslyRotate;
    final Rect mFrame;
    private long mFrameNumber;
    private boolean mFrameSizeChanged;
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
    float mInvGlobalScale;
    private boolean mIsChildWindow;
    private boolean mIsDimming;
    private final boolean mIsFloatingLayer;
    final boolean mIsImWindow;
    final boolean mIsWallpaper;
    final Rect mLastContentInsets;
    private WmDisplayCutout mLastDisplayCutout;
    final Rect mLastFrame;
    int mLastFreezeDuration;
    float mLastHScale;
    private final Rect mLastOutsets;
    private final Rect mLastOverscanInsets;
    final Rect mLastRelayoutContentInsets;
    private final MergedConfiguration mLastReportedConfiguration;
    private int mLastRequestedHeight;
    private int mLastRequestedWidth;
    private final Rect mLastStableInsets;
    final Rect mLastSurfaceInsets;
    private CharSequence mLastTitle;
    float mLastVScale;
    private final Rect mLastVisibleInsets;
    int mLastVisibleLayoutRotation;
    int mLayer;
    final boolean mLayoutAttached;
    boolean mLayoutNeeded;
    int mLayoutSeq;
    private boolean mMovedByResize;
    boolean mObscured;
    private boolean mOrientationChangeTimedOut;
    private boolean mOrientationChanging;
    private final Rect mOutsetFrame;
    final Rect mOutsets;
    private boolean mOutsetsChanged;
    private final Rect mOverscanFrame;
    final Rect mOverscanInsets;
    private boolean mOverscanInsetsChanged;
    final boolean mOwnerCanAddInternalSystemWindow;
    final int mOwnerUid;
    final Rect mParentFrame;
    private boolean mParentFrameWasClippedByDisplayCutout;
    ForcedSeamlessRotator mPendingForcedSeamlessRotate;
    boolean mPermanentlyHidden;
    final WindowManagerPolicy mPolicy;
    boolean mPolicyVisibility;
    boolean mPolicyVisibilityAfterAnim;
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
    boolean mResizedWhileGone;
    boolean mSeamlesslyRotated;
    int mSeq;
    final Session mSession;
    private boolean mShowToOwnerOnly;
    boolean mSkipEnterAnimationForSeamlessReplacement;
    private final Rect mStableFrame;
    final Rect mStableInsets;
    private boolean mStableInsetsChanged;
    private String mStringNameCache;
    final int mSubLayer;
    private final Point mSurfacePosition;
    int mSystemUiVisibility;
    private TapExcludeRegionHolder mTapExcludeRegionHolder;
    final Matrix mTmpMatrix;
    private final Rect mTmpRect;
    WindowToken mToken;
    int mTouchableInsets;
    float mVScale;
    int mViewVisibility;
    final Rect mVisibleFrame;
    final Rect mVisibleInsets;
    private boolean mVisibleInsetsChanged;
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
    final WindowId mWindowId;
    boolean mWindowRemovalAllowed;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface PowerManagerWrapper {
        boolean isInteractive();

        void wakeUp(long j, String str);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forceSeamlesslyRotateIfAllowed(int oldRotation, int rotation) {
        if (this.mForceSeamlesslyRotate) {
            if (this.mPendingForcedSeamlessRotate != null) {
                oldRotation = this.mPendingForcedSeamlessRotate.getOldRotation();
            }
            this.mPendingForcedSeamlessRotate = new ForcedSeamlessRotator(oldRotation, rotation, getDisplayInfo());
            this.mPendingForcedSeamlessRotate.unrotate(this.mToken);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState(final WindowManagerService service, Session s, IWindow c, WindowToken token, WindowState parentWindow, int appOp, int seq, WindowManager.LayoutParams a, int viewVisibility, int ownerId, boolean ownerCanAddInternalSystemWindow) {
        this(service, s, c, token, parentWindow, appOp, seq, a, viewVisibility, ownerId, ownerCanAddInternalSystemWindow, new PowerManagerWrapper() { // from class: com.android.server.wm.WindowState.2
            @Override // com.android.server.wm.WindowState.PowerManagerWrapper
            public void wakeUp(long time, String reason) {
                WindowManagerService.this.mPowerManager.wakeUp(time, reason);
            }

            @Override // com.android.server.wm.WindowState.PowerManagerWrapper
            public boolean isInteractive() {
                return WindowManagerService.this.mPowerManager.isInteractive();
            }
        });
    }

    WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token, WindowState parentWindow, int appOp, int seq, WindowManager.LayoutParams a, int viewVisibility, int ownerId, boolean ownerCanAddInternalSystemWindow, PowerManagerWrapper powerManagerWrapper) {
        super(service);
        boolean z;
        this.mAttrs = new WindowManager.LayoutParams();
        this.mPolicyVisibility = true;
        this.mPolicyVisibilityAfterAnim = true;
        this.mAppOpVisibility = true;
        this.mHidden = true;
        this.mDragResizingChangeReported = true;
        this.mLayoutSeq = -1;
        this.mLastReportedConfiguration = new MergedConfiguration();
        this.mVisibleInsets = new Rect();
        this.mLastVisibleInsets = new Rect();
        this.mContentInsets = new Rect();
        this.mLastContentInsets = new Rect();
        this.mLastRelayoutContentInsets = new Rect();
        this.mOverscanInsets = new Rect();
        this.mLastOverscanInsets = new Rect();
        this.mStableInsets = new Rect();
        this.mLastStableInsets = new Rect();
        this.mOutsets = new Rect();
        this.mLastOutsets = new Rect();
        this.mOutsetsChanged = false;
        this.mDisplayCutout = WmDisplayCutout.NO_CUTOUT;
        this.mLastDisplayCutout = WmDisplayCutout.NO_CUTOUT;
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
        this.mFrame = new Rect();
        this.mLastFrame = new Rect();
        this.mFrameSizeChanged = false;
        this.mCompatFrame = new Rect();
        this.mContainingFrame = new Rect();
        this.mParentFrame = new Rect();
        this.mDisplayFrame = new Rect();
        this.mOverscanFrame = new Rect();
        this.mStableFrame = new Rect();
        this.mDecorFrame = new Rect();
        this.mContentFrame = new Rect();
        this.mVisibleFrame = new Rect();
        this.mOutsetFrame = new Rect();
        this.mInsetFrame = new Rect();
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
        this.mResizedWhileGone = false;
        this.mSeamlesslyRotated = false;
        this.mLastSurfaceInsets = new Rect();
        this.mSurfacePosition = new Point();
        this.mFrameNumber = -1L;
        this.mIsDimming = false;
        this.mSession = s;
        this.mClient = c;
        this.mAppOp = appOp;
        this.mToken = token;
        this.mAppToken = this.mToken.asAppWindowToken();
        this.mOwnerUid = ownerId;
        this.mOwnerCanAddInternalSystemWindow = ownerCanAddInternalSystemWindow;
        this.mWindowId = new WindowId();
        this.mAttrs.copyFrom(a);
        this.mLastSurfaceInsets.set(this.mAttrs.surfaceInsets);
        this.mViewVisibility = viewVisibility;
        this.mPolicy = this.mService.mPolicy;
        this.mContext = this.mService.mContext;
        DeathRecipient deathRecipient = new DeathRecipient();
        this.mSeq = seq;
        if ((this.mAttrs.privateFlags & 128) != 0) {
            z = true;
        } else {
            z = false;
        }
        this.mEnforceSizeCompat = z;
        this.mPowerManagerWrapper = powerManagerWrapper;
        this.mForceSeamlesslyRotate = token.mRoundedCornerOverlay;
        if (WindowManagerService.localLOGV) {
            Slog.v(TAG, "Window " + this + " client=" + c.asBinder() + " token=" + token + " (" + this.mAttrs.token + ") params=" + a);
        }
        try {
            c.asBinder().linkToDeath(deathRecipient, 0);
            this.mDeathRecipient = deathRecipient;
            if (this.mAttrs.type >= 1000 && this.mAttrs.type <= 1999) {
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
            } else {
                this.mBaseLayer = (this.mPolicy.getWindowLayerLw(this) * 10000) + 1000;
                this.mSubLayer = 0;
                this.mIsChildWindow = false;
                this.mLayoutAttached = false;
                this.mIsImWindow = this.mAttrs.type == 2011 || this.mAttrs.type == 2012;
                this.mIsWallpaper = this.mAttrs.type == 2013;
            }
            this.mIsFloatingLayer = this.mIsImWindow || this.mIsWallpaper;
            if (this.mAppToken != null && this.mAppToken.mShowForAllUsers) {
                this.mAttrs.flags |= DumpState.DUMP_FROZEN;
            }
            this.mWinAnimator = new WindowStateAnimator(this);
            this.mWinAnimator.mAlpha = a.alpha;
            this.mRequestedWidth = 0;
            this.mRequestedHeight = 0;
            this.mLastRequestedWidth = 0;
            this.mLastRequestedHeight = 0;
            this.mLayer = 0;
            this.mInputWindowHandle = new InputWindowHandle(this.mAppToken != null ? this.mAppToken.mInputApplicationHandle : null, this, c, getDisplayId());
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
    public boolean getDrawnStateEvaluated() {
        return this.mDrawnStateEvaluated;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDrawnStateEvaluated(boolean evaluated) {
        this.mDrawnStateEvaluated = evaluated;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onParentSet() {
        super.onParentSet();
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

    /* JADX WARN: Code restructure failed: missing block: B:158:0x05da, code lost:
        if (r24 == r28.mFrame.height()) goto L108;
     */
    /* JADX WARN: Removed duplicated region for block: B:114:0x03ed  */
    /* JADX WARN: Removed duplicated region for block: B:115:0x0459  */
    /* JADX WARN: Removed duplicated region for block: B:152:0x059c  */
    /* JADX WARN: Removed duplicated region for block: B:155:0x05ca  */
    /* JADX WARN: Removed duplicated region for block: B:164:0x05f8  */
    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void computeFrameLw(android.graphics.Rect r29, android.graphics.Rect r30, android.graphics.Rect r31, android.graphics.Rect r32, android.graphics.Rect r33, android.graphics.Rect r34, android.graphics.Rect r35, android.graphics.Rect r36, com.android.server.wm.utils.WmDisplayCutout r37, boolean r38) {
        /*
            Method dump skipped, instructions count: 1670
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowState.computeFrameLw(android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, android.graphics.Rect, com.android.server.wm.utils.WmDisplayCutout, boolean):void");
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public Rect getBounds() {
        if (isInMultiWindowMode()) {
            return getTask().getBounds();
        }
        if (this.mAppToken != null) {
            return this.mAppToken.getBounds();
        }
        return super.getBounds();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getFrameLw() {
        return this.mFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getDisplayFrameLw() {
        return this.mDisplayFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getOverscanFrameLw() {
        return this.mOverscanFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getContentFrameLw() {
        return this.mContentFrame;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public Rect getVisibleFrameLw() {
        return this.mVisibleFrame;
    }

    Rect getStableFrameLw() {
        return this.mStableFrame;
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
        if (this.mAppToken != null) {
            return this.mAppToken.appToken;
        }
        return null;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isVoiceInteraction() {
        return this.mAppToken != null && this.mAppToken.mVoiceInteraction;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setReportResizeHints() {
        this.mOverscanInsetsChanged |= !this.mLastOverscanInsets.equals(this.mOverscanInsets);
        this.mContentInsetsChanged |= !this.mLastContentInsets.equals(this.mContentInsets);
        this.mVisibleInsetsChanged |= !this.mLastVisibleInsets.equals(this.mVisibleInsets);
        this.mStableInsetsChanged |= !this.mLastStableInsets.equals(this.mStableInsets);
        this.mOutsetsChanged |= !this.mLastOutsets.equals(this.mOutsets);
        this.mFrameSizeChanged |= (this.mLastFrame.width() == this.mFrame.width() && this.mLastFrame.height() == this.mFrame.height()) ? false : true;
        this.mDisplayCutoutChanged |= !this.mLastDisplayCutout.equals(this.mDisplayCutout);
        return this.mOverscanInsetsChanged || this.mContentInsetsChanged || this.mVisibleInsetsChanged || this.mOutsetsChanged || this.mFrameSizeChanged || this.mDisplayCutoutChanged;
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
        setReportResizeHints();
        boolean configChanged = isConfigChanged();
        if (WindowManagerDebugConfig.DEBUG_CONFIGURATION && configChanged) {
            Slog.v(TAG, "Win " + this + " config changed: " + getConfiguration());
        }
        boolean dragResizingChanged = isDragResizeChanged() && !isDragResizingChangeReported();
        if (WindowManagerService.localLOGV) {
            Slog.v(TAG, "Resizing " + this + ": configChanged=" + configChanged + " dragResizingChanged=" + dragResizingChanged + " last=" + this.mLastFrame + " frame=" + this.mFrame);
        }
        this.mLastFrame.set(this.mFrame);
        if (this.mContentInsetsChanged || this.mVisibleInsetsChanged || this.mStableInsetsChanged || winAnimator.mSurfaceResized || this.mOutsetsChanged || this.mFrameSizeChanged || this.mDisplayCutoutChanged || configChanged || dragResizingChanged || this.mReportOrientationChanged) {
            if (WindowManagerDebugConfig.DEBUG_RESIZE || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Resize reasons for w=" + this + ":  contentInsetsChanged=" + this.mContentInsetsChanged + " " + this.mContentInsets.toShortString() + " visibleInsetsChanged=" + this.mVisibleInsetsChanged + " " + this.mVisibleInsets.toShortString() + " stableInsetsChanged=" + this.mStableInsetsChanged + " " + this.mStableInsets.toShortString() + " outsetsChanged=" + this.mOutsetsChanged + " " + this.mOutsets.toShortString() + " surfaceResized=" + winAnimator.mSurfaceResized + " configChanged=" + configChanged + " dragResizingChanged=" + dragResizingChanged + " reportOrientationChanged=" + this.mReportOrientationChanged + " displayCutoutChanged=" + this.mDisplayCutoutChanged);
            }
            if (this.mAppToken != null && this.mAppDied) {
                this.mAppToken.removeDeadWindows();
                return;
            }
            updateLastInsetValues();
            this.mService.makeWindowFreezingScreenIfNeededLocked(this);
            if (getOrientationChanging() || dragResizingChanged) {
                if (WindowManagerDebugConfig.DEBUG_ANIM || WindowManagerDebugConfig.DEBUG_ORIENTATION || WindowManagerDebugConfig.DEBUG_RESIZE) {
                    Slog.v(TAG, "Orientation or resize start waiting for draw, mDrawState=DRAW_PENDING in " + this + ", surfaceController " + winAnimator.mSurfaceController);
                }
                winAnimator.mDrawState = 1;
                if (this.mAppToken != null) {
                    this.mAppToken.clearAllDrawn();
                }
            }
            if (!this.mService.mResizingWindows.contains(this)) {
                if (WindowManagerDebugConfig.DEBUG_RESIZE || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                    Slog.v(TAG, "Resizing window " + this);
                }
                this.mService.mResizingWindows.add(this);
            }
        } else if (getOrientationChanging() && isDrawnLw()) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Orientation not waiting for draw in " + this + ", surfaceController " + winAnimator.mSurfaceController);
            }
            setOrientationChanging(false);
            this.mLastFreezeDuration = (int) (SystemClock.elapsedRealtime() - this.mService.mDisplayFreezeTime);
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
    public DisplayContent getDisplayContent() {
        return this.mToken.getDisplayContent();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onDisplayChanged(DisplayContent dc) {
        super.onDisplayChanged(dc);
        if (dc != null) {
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
        if (this.mAppToken != null) {
            return this.mAppToken.getTask();
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
        bounds.set(this.mVisibleFrame);
        if (intersectWithStackBounds) {
            bounds.intersect(this.mTmpRect);
        }
        if (bounds.isEmpty()) {
            bounds.set(this.mFrame);
            if (intersectWithStackBounds) {
                bounds.intersect(this.mTmpRect);
            }
        }
    }

    public long getInputDispatchingTimeoutNanos() {
        if (this.mAppToken != null) {
            return this.mAppToken.mInputDispatchingTimeoutNanos;
        }
        return 5000000000L;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean hasAppShownWindows() {
        return this.mAppToken != null && (this.mAppToken.firstWindowDrawn || this.mAppToken.startingDisplayed);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isIdentityMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
        return dsdx >= 0.99999f && dsdx <= 1.00001f && dtdy >= 0.99999f && dtdy <= 1.00001f && dtdx >= -1.0E-6f && dtdx <= 1.0E-6f && dsdy >= -1.0E-6f && dsdy <= 1.0E-6f;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prelayout() {
        if (this.mEnforceSizeCompat) {
            this.mGlobalScale = getDisplayContent().mCompatibleScreenScale;
            this.mInvGlobalScale = 1.0f / this.mGlobalScale;
            return;
        }
        this.mInvGlobalScale = 1.0f;
        this.mGlobalScale = 1.0f;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean hasContentToDisplay() {
        if (!this.mAppFreezing && isDrawnLw()) {
            if (this.mViewVisibility != 0) {
                if (this.mWinAnimator.isAnimationSet() && !this.mService.mAppTransition.isTransitionSet()) {
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
        return wouldBeVisibleIfPolicyIgnored() && this.mPolicyVisibility;
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
        return (this.mAppToken == null || !this.mAppToken.hiddenRequested || this.mAppToken.isSelfAnimating()) && isVisible();
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
        return (this.mHasSurface || (!this.mRelayoutCalled && this.mViewVisibility == 0)) && this.mPolicyVisibility && !isParentWindowHidden() && !((atoken != null && atoken.hiddenRequested) || this.mAnimatingExit || this.mDestroying);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isOnScreen() {
        if (this.mHasSurface && !this.mDestroying && this.mPolicyVisibility) {
            AppWindowToken atoken = this.mAppToken;
            return atoken != null ? !(isParentWindowHidden() || atoken.hiddenRequested) || this.mWinAnimator.isAnimationSet() : !isParentWindowHidden() || this.mWinAnimator.isAnimationSet();
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean mightAffectAllDrawn() {
        boolean isAppType = this.mWinAnimator.mAttrType == 1 || this.mWinAnimator.mAttrType == 5 || this.mWinAnimator.mAttrType == 12 || this.mWinAnimator.mAttrType == 6 || this.mWinAnimator.mAttrType == 4;
        return ((!isOnScreen() && !isAppType) || this.mAnimatingExit || this.mDestroying) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInteresting() {
        return (this.mAppToken == null || this.mAppDied || (this.mAppToken.isFreezingScreen() && this.mAppFreezing)) ? false : true;
    }

    boolean isReadyForDisplay() {
        if (!(this.mToken.waitingToShow && this.mService.mAppTransition.isTransitionSet()) && this.mHasSurface && this.mPolicyVisibility && !this.mDestroying) {
            return !(isParentWindowHidden() || this.mViewVisibility != 0 || this.mToken.isHidden()) || this.mWinAnimator.isAnimationSet();
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
        return isDrawnLw() && this.mPolicyVisibility && ((!isParentWindowHidden() && (atoken == null || !atoken.hiddenRequested)) || this.mWinAnimator.isAnimationSet());
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isAnimatingLw() {
        return isAnimating();
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isGoneForLayoutLw() {
        AppWindowToken atoken = this.mAppToken;
        return this.mViewVisibility == 8 || !this.mRelayoutCalled || (atoken == null && this.mToken.isHidden()) || ((atoken != null && atoken.hiddenRequested) || isParentWindowHidden() || ((this.mAnimatingExit && !isAnimatingLw()) || this.mDestroying));
    }

    public boolean isDrawFinishedLw() {
        return this.mHasSurface && !this.mDestroying && (this.mWinAnimator.mDrawState == 2 || this.mWinAnimator.mDrawState == 3 || this.mWinAnimator.mDrawState == 4);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isDrawnLw() {
        return this.mHasSurface && !this.mDestroying && (this.mWinAnimator.mDrawState == 3 || this.mWinAnimator.mDrawState == 4);
    }

    private boolean isOpaqueDrawn() {
        return ((!this.mIsWallpaper && this.mAttrs.format == -1) || (this.mIsWallpaper && this.mWallpaperVisible)) && isDrawnLw() && !this.mWinAnimator.isAnimationSet();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onMovedByResize() {
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
                AccessibilityController accessibilityController = this.mService.mAccessibilityController;
                this.mWinAnimator.applyAnimationLocked(2, false);
                if (accessibilityController != null && getDisplayId() == 0) {
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
            if (this.mService.mAccessibilityController != null && isDefaultDisplay()) {
                this.mService.mAccessibilityController.onWindowTransitionLocked(this, 2);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void onResize() {
        ArrayList<WindowState> resizingWindows = this.mService.mResizingWindows;
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
        if (!this.mService.mResizingWindows.contains(this)) {
            this.mService.mResizingWindows.add(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleWindowMovedIfNeeded() {
        if (!hasMoved()) {
            return;
        }
        int left = this.mFrame.left;
        int top = this.mFrame.top;
        Task task = getTask();
        boolean adjustedForMinimizedDockOrIme = task != null && (task.mStack.isAdjustedForMinimizedDockedStack() || task.mStack.isAdjustedForIme());
        if (this.mToken.okToAnimate() && (this.mAttrs.privateFlags & 64) == 0 && !isDragResizing() && !adjustedForMinimizedDockOrIme && getWindowConfiguration().hasMovementAnimations() && !this.mWinAnimator.mLastHidden && !this.mSeamlesslyRotated) {
            startMoveAnimation(left, top);
        }
        if (this.mService.mAccessibilityController != null && getDisplayContent().getDisplayId() == 0) {
            this.mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
        }
        try {
            this.mClient.moved(left, top);
        } catch (RemoteException e) {
        }
        this.mMovedByResize = false;
    }

    private boolean hasMoved() {
        return this.mHasSurface && !((!this.mContentChanged && !this.mMovedByResize) || this.mAnimatingExit || ((this.mFrame.top == this.mLastFrame.top && this.mFrame.left == this.mLastFrame.left) || (this.mIsChildWindow && getParentWindow().hasMoved())));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isObscuringDisplay() {
        Task task = getTask();
        return (task == null || task.mStack == null || task.mStack.fillsParent()) && isOpaqueDrawn() && fillsDisplay();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean fillsDisplay() {
        DisplayInfo displayInfo = getDisplayInfo();
        return this.mFrame.left <= 0 && this.mFrame.top <= 0 && this.mFrame.right >= displayInfo.appWidth && this.mFrame.bottom >= displayInfo.appHeight;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isConfigChanged() {
        return !getLastReportedConfiguration().equals(getConfiguration());
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void forceWindowsScaleableInTransaction(boolean force) {
        if (this.mWinAnimator != null && this.mWinAnimator.hasSurface()) {
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
        if (this.mReplacementWindow != null) {
            this.mReplacementWindow.mSkipEnterAnimationForSeamlessReplacement = false;
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
        this.mPolicy.removeWindowLw(this);
        disposeInputChannel();
        this.mWinAnimator.destroyDeferredSurfaceLocked();
        this.mWinAnimator.destroySurfaceLocked();
        this.mSession.windowRemovedLocked();
        try {
            this.mClient.asBinder().unlinkToDeath(this.mDeathRecipient, 0);
        } catch (RuntimeException e) {
        }
        this.mService.postWindowRemoveCleanupLocked(this);
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
        if (WindowManagerService.localLOGV || WindowManagerDebugConfig.DEBUG_FOCUS || (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT && this == this.mService.mCurrentFocus)) {
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
                sb.append(" isAnimationSet=");
                sb.append(this.mWinAnimator.isAnimationSet());
                sb.append(" app-animation=");
                sb.append(this.mAppToken != null ? Boolean.valueOf(this.mAppToken.isSelfAnimating()) : "false");
                sb.append(" mWillReplaceWindow=");
                sb.append(this.mWillReplaceWindow);
                sb.append(" inPendingTransaction=");
                sb.append(this.mAppToken != null ? this.mAppToken.inPendingTransaction : false);
                sb.append(" mDisplayFrozen=");
                sb.append(this.mService.mDisplayFrozen);
                sb.append(" callers=");
                sb.append(Debug.getCallers(6));
                Slog.v(TAG, sb.toString());
            }
            boolean wasVisible = false;
            int displayId = getDisplayId();
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
                    this.mService.mWindowPlacerLocked.performSurfacePlacement();
                    openInputChannel(null);
                    this.mService.mInputMonitor.updateInputWindowsLw(true);
                    return;
                }
                if (wasVisible) {
                    int transit = startingWindow ? 5 : 2;
                    if (this.mWinAnimator.applyAnimationLocked(transit, false)) {
                        this.mAnimatingExit = true;
                        setDisplayLayoutNeeded();
                        this.mService.requestTraversal();
                    }
                    if (this.mService.mAccessibilityController != null && displayId == 0) {
                        this.mService.mAccessibilityController.onWindowTransitionLocked(this, transit);
                    }
                }
                boolean isAnimating = this.mWinAnimator.isAnimationSet() && (this.mAppToken == null || !this.mAppToken.isWaitingForTransitionStart());
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
            if (wasVisible && this.mService.updateOrientationFromAppTokensLocked(displayId)) {
                this.mService.mH.obtainMessage(18, Integer.valueOf(displayId)).sendToTarget();
            }
            this.mService.updateFocusedWindowLocked(0, true);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void setupWindowForRemoveOnExit() {
        this.mRemoveOnExit = true;
        setDisplayLayoutNeeded();
        boolean focusChanged = this.mService.updateFocusedWindowLocked(3, false);
        this.mService.mWindowPlacerLocked.performSurfacePlacement();
        if (focusChanged) {
            this.mService.mInputMonitor.updateInputWindowsLw(false);
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
        boolean windowsAreFocusable = this.mAppToken == null || this.mAppToken.windowsAreFocusable();
        if (windowsAreFocusable) {
            int fl = this.mAttrs.flags & 131080;
            int type = this.mAttrs.type;
            if (fl == 0 || fl == 131080 || type == 3) {
                if (WindowManagerDebugConfig.DEBUG_INPUT_METHOD) {
                    Slog.i(TAG, "isVisibleOrAdding " + this + ": " + isVisibleOrAdding());
                    if (!isVisibleOrAdding()) {
                        Slog.i(TAG, "  mSurfaceController=" + this.mWinAnimator.mSurfaceController + " relayoutCalled=" + this.mRelayoutCalled + " viewVis=" + this.mViewVisibility + " policyVis=" + this.mPolicyVisibility + " policyVisAfterAnim=" + this.mPolicyVisibilityAfterAnim + " parentHidden=" + isParentWindowHidden() + " exiting=" + this.mAnimatingExit + " destroying=" + this.mDestroying);
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
    /* loaded from: classes.dex */
    public final class DeadWindowEventReceiver extends InputEventReceiver {
        DeadWindowEventReceiver(InputChannel inputChannel) {
            super(inputChannel, WindowState.this.mService.mH.getLooper());
        }

        public void onInputEvent(InputEvent event, int displayId) {
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
        this.mInputWindowHandle.inputChannel = inputChannels[0];
        if (outInputChannel != null) {
            this.mClientChannel.transferTo(outInputChannel);
            this.mClientChannel.dispose();
            this.mClientChannel = null;
        } else {
            this.mDeadWindowEventReceiver = new DeadWindowEventReceiver(this.mClientChannel);
        }
        this.mService.mInputManager.registerInputChannel(this.mInputChannel, this.mInputWindowHandle);
    }

    void disposeInputChannel() {
        if (this.mDeadWindowEventReceiver != null) {
            this.mDeadWindowEventReceiver.dispose();
            this.mDeadWindowEventReceiver = null;
        }
        if (this.mInputChannel != null) {
            this.mService.mInputManager.unregisterInputChannel(this.mInputChannel);
            this.mInputChannel.dispose();
            this.mInputChannel = null;
        }
        if (this.mClientChannel != null) {
            this.mClientChannel.dispose();
            this.mClientChannel = null;
        }
        this.mInputWindowHandle.inputChannel = null;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void switchUser() {
        super.switchUser();
        if (isHiddenFromUserLocked()) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.w(TAG, "user changing, hiding " + this + ", attrs=" + this.mAttrs.type + ", belonging to " + this.mOwnerUid);
            }
            hideLw(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getTouchableRegion(Region region, int flags) {
        boolean modal = (flags & 40) == 0;
        if (modal && this.mAppToken != null) {
            flags |= 32;
            Task task = getTask();
            if (task != null) {
                task.getDimBounds(this.mTmpRect);
            } else {
                getStack().getDimBounds(this.mTmpRect);
            }
            if (inFreeformWindowingMode()) {
                DisplayMetrics displayMetrics = getDisplayContent().getDisplayMetrics();
                int delta = WindowManagerService.dipToPixel(30, displayMetrics);
                this.mTmpRect.inset(-delta, -delta);
            }
            region.set(this.mTmpRect);
            Rect rect = getOverrideTouchableRegion();
            if (rect != null) {
                region.set(rect);
                return flags;
            }
            if (this.mAttrs.type != 6) {
                getTouchableRegion(region);
            }
            cropRegionToStackBoundsIfNeeded(region);
        } else {
            getTouchableRegion(region);
        }
        return flags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkPolicyVisibilityChange() {
        if (this.mPolicyVisibility != this.mPolicyVisibilityAfterAnim) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility changing after anim in " + this.mWinAnimator + ": " + this.mPolicyVisibilityAfterAnim);
            }
            this.mPolicyVisibility = this.mPolicyVisibilityAfterAnim;
            if (!this.mPolicyVisibility) {
                this.mWinAnimator.hide("checkPolicyVisibilityChange");
                if (this.mService.mCurrentFocus == this) {
                    if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                        Slog.i(TAG, "setAnimationLocked: setting mFocusMayChange true");
                    }
                    this.mService.mFocusMayChange = true;
                }
                setDisplayLayoutNeeded();
                this.mService.enableScreenIfNeededLocked();
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
        boolean allowTheaterMode = this.mService.mAllowTheaterModeWakeFromLayout || Settings.Global.getInt(this.mService.mContext.getContentResolver(), "theater_mode_on", 0) == 0;
        boolean canTurnScreenOn = this.mAppToken == null || this.mAppToken.canTurnScreenOn();
        if (hasTurnScreenOnFlag) {
            if (allowTheaterMode && canTurnScreenOn && !this.mPowerManagerWrapper.isInteractive()) {
                if (WindowManagerDebugConfig.DEBUG_VISIBILITY || WindowManagerDebugConfig.DEBUG_POWER) {
                    Slog.v(TAG, "Relayout window turning screen on: " + this);
                }
                this.mPowerManagerWrapper.wakeUp(SystemClock.uptimeMillis(), "android.server.wm:TURN_ON");
            }
            if (this.mAppToken != null) {
                this.mAppToken.setCanTurnScreenOn(false);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getMergedConfiguration(MergedConfiguration outConfiguration) {
        Configuration globalConfig = this.mService.mRoot.getConfiguration();
        Configuration overrideConfig = getMergedOverrideConfiguration();
        outConfiguration.setConfiguration(globalConfig, overrideConfig);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastReportedMergedConfiguration(MergedConfiguration config) {
        this.mLastReportedConfiguration.setTo(config);
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
        if ((this.mAttrs.type == 1 || this.mAttrs.type == 5 || this.mAttrs.type == 12 || this.mAttrs.type == 6) && this.mAppToken != null && this.mAppToken.startingWindow != null) {
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
    /* loaded from: classes.dex */
    public class DeathRecipient implements IBinder.DeathRecipient {
        private DeathRecipient() {
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            boolean resetSplitScreenResizing = false;
            try {
                synchronized (WindowState.this.mService.mWindowMap) {
                    WindowManagerService.boostPriorityForLockedSection();
                    WindowState win = WindowState.this.mService.windowForClientLocked(WindowState.this.mSession, WindowState.this.mClient, false);
                    Slog.i(WindowState.TAG, "WIN DEATH: " + win);
                    if (win != null) {
                        DisplayContent dc = WindowState.this.getDisplayContent();
                        if (win.mAppToken != null && win.mAppToken.findMainWindow() == win) {
                            WindowState.this.mService.mTaskSnapshotController.onAppDied(win.mAppToken);
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
                        WindowState.this.mService.mActivityManager.setSplitScreenResizing(false);
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
        if (!isWinVisibleLw() || this.mAppToken == null || this.mAppToken.isClientHidden() || this.mAttrs.token != this.mClient.asBinder() || this.mAttrs.type == 3) {
            return false;
        }
        return getWindowConfiguration().keepVisibleDeadAppWindowOnScreen();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canReceiveKeys() {
        return isVisibleOrAdding() && this.mViewVisibility == 0 && !this.mRemoveOnExit && (this.mAttrs.flags & 8) == 0 && (this.mAppToken == null || this.mAppToken.windowsAreFocusable()) && !canReceiveTouchInput();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canReceiveTouchInput() {
        return (this.mAppToken == null || this.mAppToken.getTask() == null || !this.mAppToken.getTask().mStack.shouldIgnoreInput()) ? false : true;
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
        if (isHiddenFromUserLocked() || !this.mAppOpVisibility || this.mPermanentlyHidden || this.mHiddenWhileSuspended || this.mForceHideNonSystemOverlayWindow) {
            return false;
        }
        if (this.mPolicyVisibility && this.mPolicyVisibilityAfterAnim) {
            return false;
        }
        if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
            Slog.v(TAG, "Policy visibility true: " + this);
        }
        if (doAnimation) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "doAnimation: mPolicyVisibility=" + this.mPolicyVisibility + " isAnimationSet=" + this.mWinAnimator.isAnimationSet());
            }
            if (!this.mToken.okToAnimate()) {
                doAnimation = false;
            } else if (this.mPolicyVisibility && !this.mWinAnimator.isAnimationSet()) {
                doAnimation = false;
            }
        }
        this.mPolicyVisibility = true;
        this.mPolicyVisibilityAfterAnim = true;
        if (doAnimation) {
            this.mWinAnimator.applyAnimationLocked(1, true);
        }
        if (requestAnim) {
            this.mService.scheduleAnimationLocked();
        }
        if ((this.mAttrs.flags & 8) == 0) {
            this.mService.updateFocusedWindowLocked(0, false);
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
        boolean current = doAnimation ? this.mPolicyVisibilityAfterAnim : this.mPolicyVisibility;
        if (!current) {
            return false;
        }
        if (doAnimation) {
            this.mWinAnimator.applyAnimationLocked(2, false);
            if (!this.mWinAnimator.isAnimationSet()) {
                doAnimation = false;
            }
        }
        this.mPolicyVisibilityAfterAnim = false;
        if (!doAnimation) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.v(TAG, "Policy visibility false: " + this);
            }
            this.mPolicyVisibility = false;
            this.mService.enableScreenIfNeededLocked();
            if (this.mService.mCurrentFocus == this) {
                if (WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT) {
                    Slog.i(TAG, "WindowState.hideLw: setting mFocusMayChange true");
                }
                this.mService.mFocusMayChange = true;
            }
        }
        if (requestAnim) {
            this.mService.scheduleAnimationLocked();
        }
        if (this.mService.mCurrentFocus == this) {
            this.mService.updateFocusedWindowLocked(0, false);
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
        if (this.mAppOp != -1 && this.mAppOpVisibility && (mode = this.mService.mAppOps.startOpNoThrow(this.mAppOp, getOwningUid(), getOwningPackage(), true)) != 0 && mode != 3) {
            setAppOpVisibilityLw(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetAppOpsState() {
        if (this.mAppOp != -1 && this.mAppOpVisibility) {
            this.mService.mAppOps.finishOp(this.mAppOp, getOwningUid(), getOwningPackage());
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
            int mode = this.mService.mAppOps.checkOpNoThrow(this.mAppOp, uid, packageName);
            if (mode != 0 && mode != 3) {
                this.mService.mAppOps.finishOp(this.mAppOp, uid, packageName);
                setAppOpVisibilityLw(false);
                return;
            }
            return;
        }
        int mode2 = this.mService.mAppOps.startOpNoThrow(this.mAppOp, uid, packageName, true);
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
                PowerManager powerManager = this.mService.mPowerManager;
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
        return this.mAnimatingExit || (this.mAppToken != null && this.mAppToken.isClosingOrEnteringPip());
    }

    void addWinAnimatorToList(ArrayList<WindowStateAnimator> animators) {
        animators.add(this.mWinAnimator);
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.addWinAnimatorToList(animators);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void sendAppVisibilityToClients() {
        super.sendAppVisibilityToClients();
        boolean clientHidden = this.mAppToken.isClientHidden();
        if (this.mAttrs.type == 3 && clientHidden) {
            return;
        }
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
            this.mClient.dispatchAppVisibility(!clientHidden);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onStartFreezingScreen() {
        this.mAppFreezing = true;
        int i = this.mChildren.size() - 1;
        while (true) {
            int i2 = i;
            if (i2 >= 0) {
                WindowState c = (WindowState) this.mChildren.get(i2);
                c.onStartFreezingScreen();
                i = i2 - 1;
            } else {
                return;
            }
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
        if (this.mHasSurface && !getOrientationChanging() && this.mService.mWindowsFreezingScreen != 2) {
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "set mOrientationChanging of " + this);
            }
            setOrientationChanging(true);
            this.mService.mRoot.mOrientationChangeComplete = false;
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
            return true;
        }
        return destroyedSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroySurfaceUnchecked() {
        this.mWinAnimator.destroySurfaceLocked();
        this.mAnimatingExit = false;
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
        WindowState win = getTopParentWindow();
        return (win.mAttrs.type >= 2000 || win.mAppToken == null || !win.mAppToken.mShowForAllUsers || win.mFrame.left > win.mDisplayFrame.left || win.mFrame.top > win.mDisplayFrame.top || win.mFrame.right < win.mStableFrame.right || win.mFrame.bottom < win.mStableFrame.bottom) && win.mShowToOwnerOnly && !this.mService.isCurrentProfileLocked(UserHandle.getUserId(win.mOwnerUid));
    }

    private static void applyInsets(Region outRegion, Rect frame, Rect inset) {
        outRegion.set(frame.left + inset.left, frame.top + inset.top, frame.right - inset.right, frame.bottom - inset.bottom);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getTouchableRegion(Region outRegion) {
        Rect frame = this.mFrame;
        switch (this.mTouchableInsets) {
            case 1:
                applyInsets(outRegion, frame, this.mGivenContentInsets);
                break;
            case 2:
                applyInsets(outRegion, frame, this.mGivenVisibleInsets);
                break;
            case 3:
                outRegion.set(this.mGivenTouchableRegion);
                outRegion.translate(frame.left, frame.top);
                break;
            default:
                outRegion.set(frame);
                break;
        }
        cropRegionToStackBoundsIfNeeded(outRegion);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportFocusChangedSerialized(boolean focused, boolean inTouchMode) {
        try {
            this.mClient.windowFocusChanged(focused, inTouchMode);
        } catch (RemoteException e) {
        }
        if (this.mFocusCallbacks != null) {
            int N = this.mFocusCallbacks.beginBroadcast();
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
        if (this.mAppToken != null && this.mAppToken.mFrozenMergedConfig.size() > 0) {
            return this.mAppToken.mFrozenMergedConfig.peek();
        }
        return super.getConfiguration();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportResized() {
        WindowState windowState;
        boolean z;
        Trace.traceBegin(32L, "wm.reportResized_" + ((Object) getWindowTag()));
        try {
            if (WindowManagerDebugConfig.DEBUG_RESIZE || WindowManagerDebugConfig.DEBUG_ORIENTATION) {
                Slog.v(TAG, "Reporting new frame to " + this + ": " + this.mCompatFrame);
            }
            final MergedConfiguration mergedConfiguration = new MergedConfiguration(this.mService.mRoot.getConfiguration(), getMergedOverrideConfiguration());
            setLastReportedMergedConfiguration(mergedConfiguration);
            final boolean reportDraw = true;
            if (WindowManagerDebugConfig.DEBUG_ORIENTATION && this.mWinAnimator.mDrawState == 1) {
                Slog.i(TAG, "Resizing " + this + " WITH DRAW PENDING");
            }
            final Rect frame = this.mFrame;
            final Rect overscanInsets = this.mLastOverscanInsets;
            final Rect contentInsets = this.mLastContentInsets;
            final Rect visibleInsets = this.mLastVisibleInsets;
            final Rect stableInsets = this.mLastStableInsets;
            final Rect outsets = this.mLastOutsets;
            if (this.mWinAnimator.mDrawState != 1) {
                reportDraw = false;
            }
            final boolean reportOrientation = this.mReportOrientationChanged;
            final int displayId = getDisplayId();
            final DisplayCutout displayCutout = this.mDisplayCutout.getDisplayCutout();
            if (this.mAttrs.type != 3 && (this.mClient instanceof IWindow.Stub)) {
                try {
                    this.mService.mH.post(new Runnable() { // from class: com.android.server.wm.WindowState.3
                        @Override // java.lang.Runnable
                        public void run() {
                            try {
                                WindowState.this.dispatchResized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets, outsets, reportDraw, mergedConfiguration, reportOrientation, displayId, displayCutout);
                            } catch (RemoteException e) {
                            }
                        }
                    });
                    z = false;
                    windowState = this;
                } catch (RemoteException e) {
                    z = false;
                    windowState = this;
                    windowState.setOrientationChanging(z);
                    windowState.mLastFreezeDuration = (int) (SystemClock.elapsedRealtime() - windowState.mService.mDisplayFreezeTime);
                    Slog.w(TAG, "Failed to report 'resized' to the client of " + windowState + ", removing this window.");
                    windowState.mService.mPendingRemove.add(windowState);
                    windowState.mService.mWindowPlacerLocked.requestTraversal();
                    Trace.traceEnd(32L);
                }
            } else {
                z = false;
                windowState = this;
                try {
                    dispatchResized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets, outsets, reportDraw, mergedConfiguration, reportOrientation, displayId, displayCutout);
                } catch (RemoteException e2) {
                    windowState.setOrientationChanging(z);
                    windowState.mLastFreezeDuration = (int) (SystemClock.elapsedRealtime() - windowState.mService.mDisplayFreezeTime);
                    Slog.w(TAG, "Failed to report 'resized' to the client of " + windowState + ", removing this window.");
                    windowState.mService.mPendingRemove.add(windowState);
                    windowState.mService.mWindowPlacerLocked.requestTraversal();
                    Trace.traceEnd(32L);
                }
            }
            if (windowState.mService.mAccessibilityController != null && getDisplayId() == 0) {
                windowState.mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
            }
            windowState.mOverscanInsetsChanged = z;
            windowState.mContentInsetsChanged = z;
            windowState.mVisibleInsetsChanged = z;
            windowState.mStableInsetsChanged = z;
            windowState.mOutsetsChanged = z;
            windowState.mFrameSizeChanged = z;
            windowState.mDisplayCutoutChanged = z;
            windowState.mWinAnimator.mSurfaceResized = z;
            windowState.mReportOrientationChanged = z;
        } catch (RemoteException e3) {
            windowState = this;
            z = false;
        }
        Trace.traceEnd(32L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getBackdropFrame(Rect frame) {
        boolean resizing = isDragResizing() || isDragResizeChanged();
        if (getWindowConfiguration().useWindowFrameForBackdrop() || !resizing) {
            return frame;
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
        this.mClient.resized(frame, overscanInsets, contentInsets, visibleInsets, stableInsets, outsets, reportDraw, mergedConfiguration, getBackdropFrame(frame), forceRelayout, this.mPolicy.isNavBarForcedShownLw(this), displayId, new DisplayCutout.ParcelableWrapper(displayCutout));
        this.mDragResizingChangeReported = true;
    }

    public void registerFocusObserver(IWindowFocusObserver observer) {
        synchronized (this.mService.mWindowMap) {
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
        synchronized (this.mService.mWindowMap) {
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

    public boolean isFocused() {
        boolean z;
        synchronized (this.mService.mWindowMap) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                z = this.mService.mCurrentFocus == this;
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return z;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isInMultiWindowMode() {
        Task task = getTask();
        return (task == null || task.isFullscreen()) ? false : true;
    }

    private boolean inFullscreenContainer() {
        return this.mAppToken == null || (this.mAppToken.matchParentBounds() && !isInMultiWindowMode()) || this.mAttrs.type == 12;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLetterboxedAppWindow() {
        return false;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isLetterboxedForDisplayCutoutLw() {
        return false;
    }

    private boolean frameCoversEntireAppTokenBounds() {
        this.mTmpRect.set(this.mAppToken.getBounds());
        this.mTmpRect.intersectUnchecked(this.mFrame);
        return this.mAppToken.getBounds().equals(this.mTmpRect);
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isLetterboxedOverlappingWith(Rect rect) {
        return this.mAppToken != null && this.mAppToken.isLetterboxOverlappingWith(rect);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDragResizeChanged() {
        return this.mDragResizing != computeDragResizing();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void setWaitingForDrawnIfResizingChanged() {
        if (isDragResizeChanged()) {
            this.mService.mWaitingForDrawn.add(this);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getResizeMode() {
        return this.mResizeMode;
    }

    private boolean computeDragResizing() {
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
        return ((!getDisplayContent().mDividerControllerLocked.isResizing() && (this.mAppToken == null || this.mAppToken.mFrozenBounds.isEmpty())) || task.inFreeformWindowingMode() || isGoneForLayoutLw()) ? false : true;
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
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, trim);
        writeIdentifierToProto(proto, 1146756268034L);
        proto.write(1120986464259L, getDisplayId());
        proto.write(1120986464260L, getStackId());
        this.mAttrs.writeToProto(proto, 1146756268037L);
        this.mGivenContentInsets.writeToProto(proto, 1146756268038L);
        this.mFrame.writeToProto(proto, 1146756268039L);
        this.mContainingFrame.writeToProto(proto, 1146756268040L);
        this.mParentFrame.writeToProto(proto, 1146756268041L);
        this.mContentFrame.writeToProto(proto, 1146756268042L);
        this.mContentInsets.writeToProto(proto, 1146756268043L);
        this.mAttrs.surfaceInsets.writeToProto(proto, 1146756268044L);
        this.mSurfacePosition.writeToProto(proto, 1146756268048L);
        this.mWinAnimator.writeToProto(proto, 1146756268045L);
        proto.write(1133871366158L, this.mAnimatingExit);
        for (int i = 0; i < this.mChildren.size(); i++) {
            ((WindowState) this.mChildren.get(i)).writeToProto(proto, 2246267895823L, trim);
        }
        proto.write(1120986464274L, this.mRequestedWidth);
        proto.write(1120986464275L, this.mRequestedHeight);
        proto.write(1120986464276L, this.mViewVisibility);
        proto.write(1120986464277L, this.mSystemUiVisibility);
        proto.write(1133871366166L, this.mHasSurface);
        proto.write(1133871366167L, isReadyForDisplay());
        this.mDisplayFrame.writeToProto(proto, 1146756268056L);
        this.mOverscanFrame.writeToProto(proto, 1146756268057L);
        this.mVisibleFrame.writeToProto(proto, 1146756268058L);
        this.mDecorFrame.writeToProto(proto, 1146756268059L);
        this.mOutsetFrame.writeToProto(proto, 1146756268060L);
        this.mOverscanInsets.writeToProto(proto, 1146756268061L);
        this.mVisibleInsets.writeToProto(proto, 1146756268062L);
        this.mStableInsets.writeToProto(proto, 1146756268063L);
        this.mOutsets.writeToProto(proto, 1146756268064L);
        this.mDisplayCutout.getDisplayCutout().writeToProto(proto, 1146756268065L);
        proto.write(1133871366178L, this.mRemoveOnExit);
        proto.write(1133871366179L, this.mDestroying);
        proto.write(1133871366180L, this.mRemoved);
        proto.write(1133871366181L, isOnScreen());
        proto.write(1133871366182L, isVisible());
        if (this.mForceSeamlesslyRotate) {
            proto.write(1133871366183L, this.mPendingForcedSeamlessRotate != null);
            proto.write(1112396529704L, this.mFinishForcedSeamlessRotateFrameNumber);
        }
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
        pw.print(prefix);
        pw.print("mDisplayId=");
        pw.print(getDisplayId());
        if (stack != null) {
            pw.print(" stackId=");
            pw.print(stack.mStackId);
        }
        pw.print(" mSession=");
        pw.print(this.mSession);
        pw.print(" mClient=");
        pw.println(this.mClient.asBinder());
        pw.print(prefix);
        pw.print("mOwnerUid=");
        pw.print(this.mOwnerUid);
        pw.print(" mShowToOwnerOnly=");
        pw.print(this.mShowToOwnerOnly);
        pw.print(" package=");
        pw.print(this.mAttrs.packageName);
        pw.print(" appop=");
        pw.println(AppOpsManager.opToName(this.mAppOp));
        pw.print(prefix);
        pw.print("mAttrs=");
        pw.println(this.mAttrs.toString(prefix));
        pw.print(prefix);
        pw.print("Requested w=");
        pw.print(this.mRequestedWidth);
        pw.print(" h=");
        pw.print(this.mRequestedHeight);
        pw.print(" mLayoutSeq=");
        pw.println(this.mLayoutSeq);
        if (this.mRequestedWidth != this.mLastRequestedWidth || this.mRequestedHeight != this.mLastRequestedHeight) {
            pw.print(prefix);
            pw.print("LastRequested w=");
            pw.print(this.mLastRequestedWidth);
            pw.print(" h=");
            pw.println(this.mLastRequestedHeight);
        }
        if (this.mIsChildWindow || this.mLayoutAttached) {
            pw.print(prefix);
            pw.print("mParentWindow=");
            pw.print(getParentWindow());
            pw.print(" mLayoutAttached=");
            pw.println(this.mLayoutAttached);
        }
        if (this.mIsImWindow || this.mIsWallpaper || this.mIsFloatingLayer) {
            pw.print(prefix);
            pw.print("mIsImWindow=");
            pw.print(this.mIsImWindow);
            pw.print(" mIsWallpaper=");
            pw.print(this.mIsWallpaper);
            pw.print(" mIsFloatingLayer=");
            pw.print(this.mIsFloatingLayer);
            pw.print(" mWallpaperVisible=");
            pw.println(this.mWallpaperVisible);
        }
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mBaseLayer=");
            pw.print(this.mBaseLayer);
            pw.print(" mSubLayer=");
            pw.print(this.mSubLayer);
            pw.print(" mAnimLayer=");
            pw.print(this.mLayer);
            pw.print("+");
            pw.print("=");
            pw.print(this.mWinAnimator.mAnimLayer);
            pw.print(" mLastLayer=");
            pw.println(this.mWinAnimator.mLastLayer);
        }
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mToken=");
            pw.println(this.mToken);
            if (this.mAppToken != null) {
                pw.print(prefix);
                pw.print("mAppToken=");
                pw.println(this.mAppToken);
                pw.print(prefix);
                pw.print(" isAnimatingWithSavedSurface()=");
                pw.print(" mAppDied=");
                pw.print(this.mAppDied);
                pw.print(prefix);
                pw.print("drawnStateEvaluated=");
                pw.print(getDrawnStateEvaluated());
                pw.print(prefix);
                pw.print("mightAffectAllDrawn=");
                pw.println(mightAffectAllDrawn());
            }
            pw.print(prefix);
            pw.print("mViewVisibility=0x");
            pw.print(Integer.toHexString(this.mViewVisibility));
            pw.print(" mHaveFrame=");
            pw.print(this.mHaveFrame);
            pw.print(" mObscured=");
            pw.println(this.mObscured);
            pw.print(prefix);
            pw.print("mSeq=");
            pw.print(this.mSeq);
            pw.print(" mSystemUiVisibility=0x");
            pw.println(Integer.toHexString(this.mSystemUiVisibility));
        }
        if (!this.mPolicyVisibility || !this.mPolicyVisibilityAfterAnim || !this.mAppOpVisibility || isParentWindowHidden() || this.mPermanentlyHidden || this.mForceHideNonSystemOverlayWindow || this.mHiddenWhileSuspended) {
            pw.print(prefix);
            pw.print("mPolicyVisibility=");
            pw.print(this.mPolicyVisibility);
            pw.print(" mPolicyVisibilityAfterAnim=");
            pw.print(this.mPolicyVisibilityAfterAnim);
            pw.print(" mAppOpVisibility=");
            pw.print(this.mAppOpVisibility);
            pw.print(" parentHidden=");
            pw.print(isParentWindowHidden());
            pw.print(" mPermanentlyHidden=");
            pw.print(this.mPermanentlyHidden);
            pw.print(" mHiddenWhileSuspended=");
            pw.print(this.mHiddenWhileSuspended);
            pw.print(" mForceHideNonSystemOverlayWindow=");
            pw.println(this.mForceHideNonSystemOverlayWindow);
        }
        if (!this.mRelayoutCalled || this.mLayoutNeeded) {
            pw.print(prefix);
            pw.print("mRelayoutCalled=");
            pw.print(this.mRelayoutCalled);
            pw.print(" mLayoutNeeded=");
            pw.println(this.mLayoutNeeded);
        }
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mGivenContentInsets=");
            this.mGivenContentInsets.printShortString(pw);
            pw.print(" mGivenVisibleInsets=");
            this.mGivenVisibleInsets.printShortString(pw);
            pw.println();
            if (this.mTouchableInsets != 0 || this.mGivenInsetsPending) {
                pw.print(prefix);
                pw.print("mTouchableInsets=");
                pw.print(this.mTouchableInsets);
                pw.print(" mGivenInsetsPending=");
                pw.println(this.mGivenInsetsPending);
                Region region = new Region();
                getTouchableRegion(region);
                pw.print(prefix);
                pw.print("touchable region=");
                pw.println(region);
            }
            pw.print(prefix);
            pw.print("mFullConfiguration=");
            pw.println(getConfiguration());
            pw.print(prefix);
            pw.print("mLastReportedConfiguration=");
            pw.println(getLastReportedConfiguration());
        }
        pw.print(prefix);
        pw.print("mHasSurface=");
        pw.print(this.mHasSurface);
        pw.print(" isReadyForDisplay()=");
        pw.print(isReadyForDisplay());
        pw.print(" mWindowRemovalAllowed=");
        pw.println(this.mWindowRemovalAllowed);
        if (dumpAll) {
            pw.print(prefix);
            pw.print("mFrame=");
            this.mFrame.printShortString(pw);
            pw.print(" last=");
            this.mLastFrame.printShortString(pw);
            pw.println();
        }
        if (this.mEnforceSizeCompat) {
            pw.print(prefix);
            pw.print("mCompatFrame=");
            this.mCompatFrame.printShortString(pw);
            pw.println();
        }
        if (dumpAll) {
            pw.print(prefix);
            pw.print("Frames: containing=");
            this.mContainingFrame.printShortString(pw);
            pw.print(" parent=");
            this.mParentFrame.printShortString(pw);
            pw.println();
            pw.print(prefix);
            pw.print("    display=");
            this.mDisplayFrame.printShortString(pw);
            pw.print(" overscan=");
            this.mOverscanFrame.printShortString(pw);
            pw.println();
            pw.print(prefix);
            pw.print("    content=");
            this.mContentFrame.printShortString(pw);
            pw.print(" visible=");
            this.mVisibleFrame.printShortString(pw);
            pw.println();
            pw.print(prefix);
            pw.print("    decor=");
            this.mDecorFrame.printShortString(pw);
            pw.println();
            pw.print(prefix);
            pw.print("    outset=");
            this.mOutsetFrame.printShortString(pw);
            pw.println();
            pw.print(prefix);
            pw.print("Cur insets: overscan=");
            this.mOverscanInsets.printShortString(pw);
            pw.print(" content=");
            this.mContentInsets.printShortString(pw);
            pw.print(" visible=");
            this.mVisibleInsets.printShortString(pw);
            pw.print(" stable=");
            this.mStableInsets.printShortString(pw);
            pw.print(" surface=");
            this.mAttrs.surfaceInsets.printShortString(pw);
            pw.print(" outsets=");
            this.mOutsets.printShortString(pw);
            pw.print(" cutout=" + this.mDisplayCutout.getDisplayCutout());
            pw.println();
            pw.print(prefix);
            pw.print("Lst insets: overscan=");
            this.mLastOverscanInsets.printShortString(pw);
            pw.print(" content=");
            this.mLastContentInsets.printShortString(pw);
            pw.print(" visible=");
            this.mLastVisibleInsets.printShortString(pw);
            pw.print(" stable=");
            this.mLastStableInsets.printShortString(pw);
            pw.print(" physical=");
            this.mLastOutsets.printShortString(pw);
            pw.print(" outset=");
            this.mLastOutsets.printShortString(pw);
            pw.print(" cutout=" + this.mLastDisplayCutout);
            pw.println();
        }
        super.dump(pw, prefix, dumpAll);
        pw.print(prefix);
        pw.print(this.mWinAnimator);
        pw.println(":");
        WindowStateAnimator windowStateAnimator = this.mWinAnimator;
        windowStateAnimator.dump(pw, prefix + "  ", dumpAll);
        if (this.mAnimatingExit || this.mRemoveOnExit || this.mDestroying || this.mRemoved) {
            pw.print(prefix);
            pw.print("mAnimatingExit=");
            pw.print(this.mAnimatingExit);
            pw.print(" mRemoveOnExit=");
            pw.print(this.mRemoveOnExit);
            pw.print(" mDestroying=");
            pw.print(this.mDestroying);
            pw.print(" mRemoved=");
            pw.println(this.mRemoved);
        }
        if (getOrientationChanging() || this.mAppFreezing || this.mReportOrientationChanged) {
            pw.print(prefix);
            pw.print("mOrientationChanging=");
            pw.print(this.mOrientationChanging);
            pw.print(" configOrientationChanging=");
            pw.print(getLastReportedConfiguration().orientation != getConfiguration().orientation);
            pw.print(" mAppFreezing=");
            pw.print(this.mAppFreezing);
            pw.print(" mReportOrientationChanged=");
            pw.println(this.mReportOrientationChanged);
        }
        if (this.mLastFreezeDuration != 0) {
            pw.print(prefix);
            pw.print("mLastFreezeDuration=");
            TimeUtils.formatDuration(this.mLastFreezeDuration, pw);
            pw.println();
        }
        if (this.mForceSeamlesslyRotate) {
            pw.print(prefix);
            pw.print("forceSeamlesslyRotate: pending=");
            if (this.mPendingForcedSeamlessRotate != null) {
                this.mPendingForcedSeamlessRotate.dump(pw);
            } else {
                pw.print("null");
            }
            pw.print(" finishedFrameNumber=");
            pw.print(this.mFinishForcedSeamlessRotateFrameNumber);
            pw.println();
        }
        if (this.mHScale != 1.0f || this.mVScale != 1.0f) {
            pw.print(prefix);
            pw.print("mHScale=");
            pw.print(this.mHScale);
            pw.print(" mVScale=");
            pw.println(this.mVScale);
        }
        if (this.mWallpaperX != -1.0f || this.mWallpaperY != -1.0f) {
            pw.print(prefix);
            pw.print("mWallpaperX=");
            pw.print(this.mWallpaperX);
            pw.print(" mWallpaperY=");
            pw.println(this.mWallpaperY);
        }
        if (this.mWallpaperXStep != -1.0f || this.mWallpaperYStep != -1.0f) {
            pw.print(prefix);
            pw.print("mWallpaperXStep=");
            pw.print(this.mWallpaperXStep);
            pw.print(" mWallpaperYStep=");
            pw.println(this.mWallpaperYStep);
        }
        if (this.mWallpaperDisplayOffsetX != Integer.MIN_VALUE || this.mWallpaperDisplayOffsetY != Integer.MIN_VALUE) {
            pw.print(prefix);
            pw.print("mWallpaperDisplayOffsetX=");
            pw.print(this.mWallpaperDisplayOffsetX);
            pw.print(" mWallpaperDisplayOffsetY=");
            pw.println(this.mWallpaperDisplayOffsetY);
        }
        if (this.mDrawLock != null) {
            pw.print(prefix);
            pw.println("mDrawLock=" + this.mDrawLock);
        }
        if (isDragResizing()) {
            pw.print(prefix);
            pw.println("isDragResizing=" + isDragResizing());
        }
        if (computeDragResizing()) {
            pw.print(prefix);
            pw.println("computeDragResizing=" + computeDragResizing());
        }
        pw.print(prefix);
        pw.println("isOnScreen=" + isOnScreen());
        pw.print(prefix);
        pw.println("isVisible=" + isVisible());
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
        if (this.mHScale >= 0.0f) {
            clipRect.left = (int) (clipRect.left / this.mHScale);
            clipRect.right = (int) Math.ceil(clipRect.right / this.mHScale);
        }
        if (this.mVScale >= 0.0f) {
            clipRect.top = (int) (clipRect.top / this.mVScale);
            clipRect.bottom = (int) Math.ceil(clipRect.bottom / this.mVScale);
        }
    }

    void applyGravityAndUpdateFrame(Rect containingFrame, Rect displayFrame) {
        int w;
        int h;
        float x;
        float y;
        int pw = containingFrame.width();
        int ph = containingFrame.height();
        Task task = getTask();
        boolean fitToDisplay = true;
        boolean inNonFullscreenContainer = !inFullscreenContainer();
        boolean noLimits = (this.mAttrs.flags & 512) != 0;
        if (task != null && inNonFullscreenContainer && (this.mAttrs.type == 1 || this.mAttrs.type == 5 || this.mAttrs.type == 12 || this.mAttrs.type == 6 || noLimits)) {
            fitToDisplay = false;
        }
        if ((this.mAttrs.flags & 16384) != 0) {
            if (this.mAttrs.width < 0) {
                w = pw;
            } else if (this.mEnforceSizeCompat) {
                w = (int) ((this.mAttrs.width * this.mGlobalScale) + 0.5f);
            } else {
                w = this.mAttrs.width;
            }
            if (this.mAttrs.height < 0) {
                h = ph;
            } else if (this.mEnforceSizeCompat) {
                h = (int) ((this.mAttrs.height * this.mGlobalScale) + 0.5f);
            } else {
                h = this.mAttrs.height;
            }
        } else {
            if (this.mAttrs.width == -1) {
                w = pw;
            } else if (this.mEnforceSizeCompat) {
                w = (int) ((this.mRequestedWidth * this.mGlobalScale) + 0.5f);
            } else {
                w = this.mRequestedWidth;
            }
            if (this.mAttrs.height == -1) {
                h = ph;
            } else if (this.mEnforceSizeCompat) {
                h = (int) ((this.mRequestedHeight * this.mGlobalScale) + 0.5f);
            } else {
                h = this.mRequestedHeight;
            }
        }
        if (this.mEnforceSizeCompat) {
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
        Gravity.apply(this.mAttrs.gravity, w, h, containingFrame, (int) ((this.mAttrs.horizontalMargin * pw) + x), (int) ((this.mAttrs.verticalMargin * ph) + y), this.mFrame);
        if (fitToDisplay) {
            Gravity.applyDisplay(this.mAttrs.gravity, displayFrame, this.mFrame);
        }
        this.mCompatFrame.set(this.mFrame);
        if (this.mEnforceSizeCompat) {
            this.mCompatFrame.scale(this.mInvGlobalScale);
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
        WindowState topParent = this;
        WindowState current = topParent;
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
        if (dc != null && (this.mAttrs.flags & 1048576) != 0) {
            dc.pendingLayoutChanges |= 4;
            dc.setLayoutNeeded();
            this.mService.mWindowPlacerLocked.requestTraversal();
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            c.requestUpdateWallpaperIfNeeded();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float translateToWindowX(float x) {
        float winX = x - this.mFrame.left;
        if (this.mEnforceSizeCompat) {
            return winX * this.mGlobalScale;
        }
        return winX;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float translateToWindowY(float y) {
        float winY = y - this.mFrame.top;
        if (this.mEnforceSizeCompat) {
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
        if (this.mAppToken != null) {
            return this.mAppToken.mRotationAnimationHint;
        }
        return -1;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isInputMethodWindow() {
        return this.mIsImWindow;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean performShowLocked() {
        if (isHiddenFromUserLocked()) {
            if (WindowManagerDebugConfig.DEBUG_VISIBILITY) {
                Slog.w(TAG, "hiding " + this + ", belonging to " + this.mOwnerUid);
            }
            hideLw(false);
            return false;
        }
        logPerformShow("performShow on ");
        int drawState = this.mWinAnimator.mDrawState;
        if ((drawState == 4 || drawState == 3) && this.mAttrs.type != 3 && this.mAppToken != null) {
            this.mAppToken.onFirstWindowDrawn(this, this.mWinAnimator);
        }
        if (this.mWinAnimator.mDrawState == 3 && isReadyForDisplay()) {
            logPerformShow("Showing ");
            this.mService.enableScreenIfNeededLocked();
            this.mWinAnimator.applyEnterAnimationLocked();
            this.mWinAnimator.mLastAlpha = -1.0f;
            if (WindowManagerDebugConfig.DEBUG_ANIM) {
                Slog.v(TAG, "performShowLocked: mDrawState=HAS_DRAWN in " + this);
            }
            this.mWinAnimator.mDrawState = 4;
            this.mService.scheduleAnimationLocked();
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
            boolean z = false;
            sb.append(this.mAttrs.type == 3);
            sb.append(" during animation: policyVis=");
            sb.append(this.mPolicyVisibility);
            sb.append(" parentHidden=");
            sb.append(isParentWindowHidden());
            sb.append(" tok.hiddenRequested=");
            sb.append(this.mAppToken != null && this.mAppToken.hiddenRequested);
            sb.append(" tok.hidden=");
            sb.append(this.mAppToken != null && this.mAppToken.isHidden());
            sb.append(" animationSet=");
            sb.append(this.mWinAnimator.isAnimationSet());
            sb.append(" tok animating=");
            if (this.mAppToken != null && this.mAppToken.isSelfAnimating()) {
                z = true;
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
        if (this.mAppToken != null) {
            windowInfo.activityToken = this.mAppToken.appToken.asBinder();
        }
        windowInfo.title = this.mAttrs.accessibilityTitle;
        boolean z = true;
        boolean isPanelWindow = this.mAttrs.type >= 1000 && this.mAttrs.type <= 1999;
        boolean isAccessibilityOverlay = windowInfo.type == 2032;
        if (TextUtils.isEmpty(windowInfo.title) && (isPanelWindow || isAccessibilityOverlay)) {
            windowInfo.title = this.mAttrs.getTitle();
        }
        windowInfo.accessibilityIdOfAnchor = this.mAttrs.accessibilityIdOfAnchor;
        windowInfo.focused = isFocused();
        Task task = getTask();
        windowInfo.inPictureInPicture = (task == null || !task.inPinnedWindowingMode()) ? false : false;
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getHighestAnimLayer() {
        int highest = this.mWinAnimator.mAnimLayer;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState c = (WindowState) this.mChildren.get(i);
            int childLayer = c.getHighestAnimLayer();
            if (childLayer > highest) {
                highest = childLayer;
            }
        }
        return highest;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
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
        To view partially-correct add '--show-bad-code' argument
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public WindowState getWindow(Predicate<WindowState> callback) {
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
            this.mService.requestTraversal();
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
        if (this.mService.mAccessibilityController != null && getDisplayId() == 0) {
            this.mService.mAccessibilityController.onSomeWindowResizedOrMovedLocked();
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
        if (this.mAppToken != null) {
            this.mAppToken.destroySurfaces();
        } else {
            if (hasSurface) {
                this.mService.mDestroySurface.add(this);
            }
            if (this.mRemoveOnExit) {
                this.mService.mPendingRemove.add(this);
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
                this.mService.mDestroySurface.remove(this);
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
                    this.mService.mWindowPlacerLocked.debugLayoutRepeats("hideWallpaperWindow " + this, displayContent.pendingLayoutChanges);
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
            Slog.v(TAG, "Win " + this + ": isDrawn=" + isDrawnLw() + ", isAnimationSet=" + this.mWinAnimator.isAnimationSet());
            if (!isDrawnLw()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Not displayed: s=");
                sb.append(this.mWinAnimator.mSurfaceController);
                sb.append(" pv=");
                sb.append(this.mPolicyVisibility);
                sb.append(" mDrawState=");
                sb.append(this.mWinAnimator.mDrawState);
                sb.append(" ph=");
                sb.append(isParentWindowHidden());
                sb.append(" th=");
                sb.append(this.mAppToken != null ? this.mAppToken.hiddenRequested : false);
                sb.append(" a=");
                sb.append(this.mWinAnimator.isAnimationSet());
                Slog.v(TAG, sb.toString());
            }
        }
        results.numInteresting++;
        if (isDrawnLw()) {
            results.numDrawn++;
            if (!this.mWinAnimator.isAnimationSet()) {
                results.numVisible++;
            }
            results.nowGone = false;
        } else if (this.mWinAnimator.isAnimationSet()) {
            results.nowGone = false;
        }
    }

    private boolean skipDecorCrop() {
        if (this.mDecorFrame.isEmpty()) {
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
        DisplayInfo displayInfo = displayContent.getDisplayInfo();
        if (!isDefaultDisplay()) {
            policyCrop.set(0, 0, this.mCompatFrame.width(), this.mCompatFrame.height());
            policyCrop.intersect(-this.mCompatFrame.left, -this.mCompatFrame.top, displayInfo.logicalWidth - this.mCompatFrame.left, displayInfo.logicalHeight - this.mCompatFrame.top);
        } else if (skipDecorCrop()) {
            policyCrop.set(0, 0, this.mCompatFrame.width(), this.mCompatFrame.height());
        } else {
            calculateSystemDecorRect(policyCrop);
        }
    }

    private void calculateSystemDecorRect(Rect systemDecorRect) {
        Rect decorRect = this.mDecorFrame;
        int width = this.mFrame.width();
        int height = this.mFrame.height();
        int left = this.mFrame.left;
        int top = this.mFrame.top;
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
        if (this.mEnforceSizeCompat && this.mInvGlobalScale != 1.0f) {
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
    public int relayoutVisibleWindow(int result, int attrChanges, int oldVisibility) {
        boolean wasVisible = isVisibleLw();
        int i = 0;
        int result2 = result | ((wasVisible && isDrawnLw()) ? 0 : 2);
        if (this.mAnimatingExit) {
            Slog.d(TAG, "relayoutVisibleWindow: " + this + " mAnimatingExit=true, mRemoveOnExit=" + this.mRemoveOnExit + ", mDestroying=" + this.mDestroying);
            this.mWinAnimator.cancelExitAnimationForNextAnimationLocked();
            this.mAnimatingExit = false;
        }
        if (this.mDestroying) {
            this.mDestroying = false;
            this.mService.mDestroySurface.remove(this);
        }
        boolean dockedResizing = true;
        if (oldVisibility == 8) {
            this.mWinAnimator.mEnterAnimationPending = true;
        }
        this.mLastVisibleLayoutRotation = getDisplayContent().getRotation();
        this.mWinAnimator.mEnteringAnimation = true;
        prepareWindowToDisplayDuringRelayout(wasVisible);
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
        boolean freeformResizing = isDragResizing() && getResizeMode() == 0;
        if (!isDragResizing() || getResizeMode() != 1) {
            dockedResizing = false;
        }
        int result3 = result2 | (freeformResizing ? 16 : 0);
        if (dockedResizing) {
            i = 8;
        }
        return result3 | i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLaidOut() {
        return this.mLayoutSeq != -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateLastInsetValues() {
        this.mLastOverscanInsets.set(this.mOverscanInsets);
        this.mLastContentInsets.set(this.mContentInsets);
        this.mLastVisibleInsets.set(this.mVisibleInsets);
        this.mLastStableInsets.set(this.mStableInsets);
        this.mLastOutsets.set(this.mOutsets);
        this.mLastDisplayCutout = this.mDisplayCutout;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startAnimation(Animation anim) {
        DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
        anim.initialize(this.mFrame.width(), this.mFrame.height(), displayInfo.appWidth, displayInfo.appHeight);
        anim.restrictDuration(JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        anim.scaleCurrentDuration(this.mService.getWindowAnimationScaleLocked());
        AnimationAdapter adapter = new LocalAnimationAdapter(new WindowAnimationSpec(anim, this.mSurfacePosition, false), this.mService.mSurfaceAnimationRunner);
        startAnimation(this.mPendingTransaction, adapter);
        commitPendingTransaction();
    }

    private void startMoveAnimation(int left, int top) {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "Setting move animation on " + this);
        }
        Point oldPosition = new Point();
        Point newPosition = new Point();
        transformFrameToSurfacePosition(this.mLastFrame.left, this.mLastFrame.top, oldPosition);
        transformFrameToSurfacePosition(left, top, newPosition);
        AnimationAdapter adapter = new LocalAnimationAdapter(new MoveAnimationSpec(oldPosition.x, oldPosition.y, newPosition.x, newPosition.y), this.mService.mSurfaceAnimationRunner);
        startAnimation(getPendingTransaction(), adapter);
    }

    private void startAnimation(SurfaceControl.Transaction t, AnimationAdapter adapter) {
        startAnimation(t, adapter, this.mWinAnimator.mLastHidden);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.WindowContainer
    public void onAnimationFinished() {
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
        WindowContainer parent = getParent();
        if (isChildWindow()) {
            WindowState parentWindow = getParentWindow();
            x += parentWindow.mFrame.left - parentWindow.mAttrs.surfaceInsets.left;
            y += parentWindow.mFrame.top - parentWindow.mAttrs.surfaceInsets.top;
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
    /* loaded from: classes.dex */
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
    /* loaded from: classes.dex */
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
            WindowState outer = this.mOuter.get();
            return outer != null && outer.isFocused();
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public SurfaceSession getSession() {
        if (this.mSession.mSurfaceSession != null) {
            return this.mSession.mSurfaceSession;
        }
        return getParent().getSession();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public boolean needsZBoost() {
        AppWindowToken appToken;
        if (this.mIsImWindow && this.mService.mInputMethodTarget != null && (appToken = this.mService.mInputMethodTarget.mAppToken) != null) {
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

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void prepareSurfaces() {
        Dimmer dimmer = getDimmer();
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
    public void onAnimationLeashDestroyed(SurfaceControl.Transaction t) {
        super.onAnimationLeashDestroyed(t);
        updateSurfacePosition(t);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void updateSurfacePosition() {
        updateSurfacePosition(getPendingTransaction());
    }

    private void updateSurfacePosition(SurfaceControl.Transaction t) {
        if (this.mSurfaceControl == null) {
            return;
        }
        transformFrameToSurfacePosition(this.mFrame.left, this.mFrame.top, this.mSurfacePosition);
        if (!this.mSurfaceAnimator.hasLeash() && this.mPendingForcedSeamlessRotate == null && !this.mLastSurfacePosition.equals(this.mSurfacePosition)) {
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
            outPoint.offset((-parent.mFrame.left) + parent.mAttrs.surfaceInsets.left, (-parent.mFrame.top) + parent.mAttrs.surfaceInsets.top);
        } else if (parentWindowContainer != null) {
            Rect parentBounds = parentWindowContainer.getBounds();
            outPoint.offset(-parentBounds.left, -parentBounds.top);
        }
        TaskStack stack = getStack();
        if (stack != null) {
            int outset = stack.getStackOutset();
            outPoint.offset(outset, outset);
        }
        outPoint.offset(-this.mAttrs.surfaceInsets.left, -this.mAttrs.surfaceInsets.top);
    }

    boolean needsRelativeLayeringToIme() {
        WindowState imeTarget;
        if (inSplitScreenWindowingMode()) {
            if (!isChildWindow()) {
                return (this.mAppToken == null || (imeTarget = this.mService.mInputMethodTarget) == null || imeTarget == this || imeTarget.mToken != this.mToken || imeTarget.compareTo((WindowContainer) this) > 0) ? false : true;
            } else if (getParentWindow().isInputMethodTarget()) {
                return true;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void assignLayer(SurfaceControl.Transaction t, int layer) {
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
        int layer = 1;
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
    public void updateTapExcludeRegion(int regionId, int left, int top, int width, int height) {
        DisplayContent currentDisplay = getDisplayContent();
        if (currentDisplay == null) {
            throw new IllegalStateException("Trying to update window not attached to any display.");
        }
        if (this.mTapExcludeRegionHolder == null) {
            this.mTapExcludeRegionHolder = new TapExcludeRegionHolder();
            currentDisplay.mTapExcludeProvidingWindows.add(this);
        }
        this.mTapExcludeRegionHolder.updateRegion(regionId, left, top, width, height);
        boolean isAppFocusedOnDisplay = this.mService.mFocusedApp != null && this.mService.mFocusedApp.getDisplayContent() == currentDisplay;
        currentDisplay.setTouchExcludeRegion(isAppFocusedOnDisplay ? this.mService.mFocusedApp.getTask() : null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void amendTapExcludeRegion(Region region) {
        this.mTapExcludeRegionHolder.amendRegion(region, getBounds());
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public boolean isInputMethodTarget() {
        return this.mService.mInputMethodTarget == this;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getFrameNumber() {
        return this.mFrameNumber;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFrameNumber(long frameNumber) {
        this.mFrameNumber = frameNumber;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowContainer
    public void seamlesslyRotate(SurfaceControl.Transaction t, int oldRotation, int newRotation) {
        if (!isVisibleNow() || this.mIsWallpaper || this.mForceSeamlesslyRotate) {
            return;
        }
        Matrix transform = this.mTmpMatrix;
        this.mService.markForSeamlessRotation(this, true);
        CoordinateTransforms.transformToRotation(oldRotation, newRotation, getDisplayInfo(), transform);
        CoordinateTransforms.transformRect(transform, this.mFrame, null);
        updateSurfacePosition(t);
        this.mWinAnimator.seamlesslyRotate(t, oldRotation, newRotation);
        super.seamlesslyRotate(t, oldRotation, newRotation);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class MoveAnimationSpec implements LocalAnimationAdapter.AnimationSpec {
        private final long mDuration;
        private Point mFrom;
        private Interpolator mInterpolator;
        private Point mTo;

        private MoveAnimationSpec(int fromX, int fromY, int toX, int toY) {
            this.mFrom = new Point();
            this.mTo = new Point();
            Animation anim = AnimationUtils.loadAnimation(WindowState.this.mContext, 17432758);
            this.mDuration = ((float) anim.computeDurationHint()) * WindowState.this.mService.getWindowAnimationScaleLocked();
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
            pw.print(prefix);
            pw.print("from=");
            pw.print(this.mFrom);
            pw.print(" to=");
            pw.print(this.mTo);
            pw.print(" duration=");
            pw.println(this.mDuration);
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

    private Rect getOverrideTouchableRegion() {
        xpWindowManager.WindowInfo info = xpWindowManager.getWindowInfo(this.mAttrs);
        if (info != null) {
            return info.touchRegion;
        }
        return null;
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public void setWindowAttrs(WindowManager.LayoutParams lp) {
        if (this.mAttrs != null && lp != null) {
            this.mAttrs.copyFrom(lp);
        }
    }

    @Override // com.android.server.policy.WindowManagerPolicy.WindowState
    public WindowManager.LayoutParams getAppWindowAttrs() {
        WindowState win;
        if (this.mAppToken != null && (win = this.mAppToken.findMainWindow(true)) != null) {
            return win.mAttrs;
        }
        return null;
    }
}
