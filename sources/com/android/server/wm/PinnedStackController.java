package com.android.server.wm;

import android.app.RemoteAction;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.Slog;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;
import com.android.internal.policy.PipSnapAlgorithm;
import com.android.internal.util.Preconditions;
import com.android.server.UiThread;
import com.android.server.wm.PinnedStackController;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class PinnedStackController {
    public static final float INVALID_SNAP_FRACTION = -1.0f;
    private static final String TAG = "WindowManager";
    private float mAspectRatio;
    private int mCurrentMinSize;
    private float mDefaultAspectRatio;
    private int mDefaultMinSize;
    private int mDefaultStackGravity;
    private final DisplayContent mDisplayContent;
    private int mImeHeight;
    private boolean mIsImeShowing;
    private boolean mIsMinimized;
    private boolean mIsShelfShowing;
    private float mMaxAspectRatio;
    private float mMinAspectRatio;
    private IPinnedStackListener mPinnedStackListener;
    private Point mScreenEdgeInsets;
    private final WindowManagerService mService;
    private int mShelfHeight;
    private final PipSnapAlgorithm mSnapAlgorithm;
    private final Handler mHandler = UiThread.getHandler();
    private final PinnedStackListenerDeathHandler mPinnedStackListenerDeathHandler = new PinnedStackListenerDeathHandler();
    private final PinnedStackControllerCallback mCallbacks = new PinnedStackControllerCallback();
    private ArrayList<RemoteAction> mActions = new ArrayList<>();
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Rect mStableInsets = new Rect();
    private float mReentrySnapFraction = -1.0f;
    private WeakReference<AppWindowToken> mLastPipActivity = null;
    private final DisplayMetrics mTmpMetrics = new DisplayMetrics();
    private final Rect mTmpInsets = new Rect();
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpAnimatingBoundsRect = new Rect();
    private final Point mTmpDisplaySize = new Point();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class PinnedStackControllerCallback extends IPinnedStackController.Stub {
        private PinnedStackControllerCallback() {
        }

        public void setIsMinimized(final boolean isMinimized) {
            PinnedStackController.this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$PinnedStackController$PinnedStackControllerCallback$0SANOJyiLP67Pkj3NbDS5B-egBU
                @Override // java.lang.Runnable
                public final void run() {
                    PinnedStackController.PinnedStackControllerCallback.this.lambda$setIsMinimized$0$PinnedStackController$PinnedStackControllerCallback(isMinimized);
                }
            });
        }

        public /* synthetic */ void lambda$setIsMinimized$0$PinnedStackController$PinnedStackControllerCallback(boolean isMinimized) {
            PinnedStackController.this.mIsMinimized = isMinimized;
            PinnedStackController.this.mSnapAlgorithm.setMinimized(isMinimized);
        }

        public void setMinEdgeSize(final int minEdgeSize) {
            PinnedStackController.this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$PinnedStackController$PinnedStackControllerCallback$MdGjZinCTxKrX3GJTl1CXkAuFro
                @Override // java.lang.Runnable
                public final void run() {
                    PinnedStackController.PinnedStackControllerCallback.this.lambda$setMinEdgeSize$1$PinnedStackController$PinnedStackControllerCallback(minEdgeSize);
                }
            });
        }

        public /* synthetic */ void lambda$setMinEdgeSize$1$PinnedStackController$PinnedStackControllerCallback(int minEdgeSize) {
            PinnedStackController pinnedStackController = PinnedStackController.this;
            pinnedStackController.mCurrentMinSize = Math.max(pinnedStackController.mDefaultMinSize, minEdgeSize);
        }

        public int getDisplayRotation() {
            int i;
            synchronized (PinnedStackController.this.mService.mGlobalLock) {
                try {
                    WindowManagerService.boostPriorityForLockedSection();
                    i = PinnedStackController.this.mDisplayInfo.rotation;
                } catch (Throwable th) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            WindowManagerService.resetPriorityAfterLockedSection();
            return i;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class PinnedStackListenerDeathHandler implements IBinder.DeathRecipient {
        private PinnedStackListenerDeathHandler() {
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (PinnedStackController.this.mPinnedStackListener != null) {
                PinnedStackController.this.mPinnedStackListener.asBinder().unlinkToDeath(PinnedStackController.this.mPinnedStackListenerDeathHandler, 0);
            }
            PinnedStackController.this.mPinnedStackListener = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PinnedStackController(WindowManagerService service, DisplayContent displayContent) {
        this.mAspectRatio = -1.0f;
        this.mService = service;
        this.mDisplayContent = displayContent;
        this.mSnapAlgorithm = new PipSnapAlgorithm(service.mContext);
        this.mDisplayInfo.copyFrom(this.mDisplayContent.getDisplayInfo());
        reloadResources();
        this.mAspectRatio = this.mDefaultAspectRatio;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onConfigurationChanged() {
        reloadResources();
    }

    private void reloadResources() {
        Size screenEdgeInsetsDp;
        Resources res = this.mService.mContext.getResources();
        this.mDefaultMinSize = res.getDimensionPixelSize(17105126);
        this.mCurrentMinSize = this.mDefaultMinSize;
        this.mDefaultAspectRatio = res.getFloat(17105068);
        String screenEdgeInsetsDpString = res.getString(17039711);
        if (!screenEdgeInsetsDpString.isEmpty()) {
            screenEdgeInsetsDp = Size.parseSize(screenEdgeInsetsDpString);
        } else {
            screenEdgeInsetsDp = null;
        }
        this.mDefaultStackGravity = res.getInteger(17694780);
        this.mDisplayContent.getDisplay().getRealMetrics(this.mTmpMetrics);
        this.mScreenEdgeInsets = screenEdgeInsetsDp == null ? new Point() : new Point(dpToPx(screenEdgeInsetsDp.getWidth(), this.mTmpMetrics), dpToPx(screenEdgeInsetsDp.getHeight(), this.mTmpMetrics));
        this.mMinAspectRatio = res.getFloat(17105071);
        this.mMaxAspectRatio = res.getFloat(17105070);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerPinnedStackListener(IPinnedStackListener listener) {
        try {
            listener.asBinder().linkToDeath(this.mPinnedStackListenerDeathHandler, 0);
            listener.onListenerRegistered(this.mCallbacks);
            this.mPinnedStackListener = listener;
            notifyImeVisibilityChanged(this.mIsImeShowing, this.mImeHeight);
            notifyShelfVisibilityChanged(this.mIsShelfShowing, this.mShelfHeight);
            notifyMovementBoundsChanged(false, false);
            notifyActionsChanged(this.mActions);
            notifyMinimizeChanged(this.mIsMinimized);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
    }

    public boolean isValidPictureInPictureAspectRatio(float aspectRatio) {
        return Float.compare(this.mMinAspectRatio, aspectRatio) <= 0 && Float.compare(aspectRatio, this.mMaxAspectRatio) <= 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect transformBoundsToAspectRatio(Rect stackBounds, float aspectRatio, boolean useCurrentMinEdgeSize) {
        float snapFraction = this.mSnapAlgorithm.getSnapFraction(stackBounds, getMovementBounds(stackBounds));
        int minEdgeSize = useCurrentMinEdgeSize ? this.mCurrentMinSize : this.mDefaultMinSize;
        Size size = this.mSnapAlgorithm.getSizeForAspectRatio(aspectRatio, minEdgeSize, this.mDisplayInfo.logicalWidth, this.mDisplayInfo.logicalHeight);
        int left = (int) (stackBounds.centerX() - (size.getWidth() / 2.0f));
        int top = (int) (stackBounds.centerY() - (size.getHeight() / 2.0f));
        stackBounds.set(left, top, size.getWidth() + left, size.getHeight() + top);
        this.mSnapAlgorithm.applySnapFraction(stackBounds, getMovementBounds(stackBounds), snapFraction);
        if (this.mIsMinimized) {
            applyMinimizedOffset(stackBounds, getMovementBounds(stackBounds));
        }
        return stackBounds;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveReentrySnapFraction(AppWindowToken token, Rect stackBounds) {
        this.mReentrySnapFraction = getSnapFraction(stackBounds);
        this.mLastPipActivity = new WeakReference<>(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetReentrySnapFraction(AppWindowToken token) {
        WeakReference<AppWindowToken> weakReference = this.mLastPipActivity;
        if (weakReference != null && weakReference.get() == token) {
            this.mReentrySnapFraction = -1.0f;
            this.mLastPipActivity = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getDefaultOrLastSavedBounds() {
        return getDefaultBounds(this.mReentrySnapFraction);
    }

    Rect getDefaultBounds(float snapFraction) {
        Rect defaultBounds;
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                Rect insetBounds = new Rect();
                getInsetBounds(insetBounds);
                defaultBounds = new Rect();
                Size size = this.mSnapAlgorithm.getSizeForAspectRatio(this.mDefaultAspectRatio, this.mDefaultMinSize, this.mDisplayInfo.logicalWidth, this.mDisplayInfo.logicalHeight);
                if (snapFraction != -1.0f) {
                    defaultBounds.set(0, 0, size.getWidth(), size.getHeight());
                    Rect movementBounds = getMovementBounds(defaultBounds);
                    this.mSnapAlgorithm.applySnapFraction(defaultBounds, movementBounds, snapFraction);
                } else {
                    Gravity.apply(this.mDefaultStackGravity, size.getWidth(), size.getHeight(), insetBounds, 0, Math.max(this.mIsImeShowing ? this.mImeHeight : 0, this.mIsShelfShowing ? this.mShelfHeight : 0), defaultBounds);
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return defaultBounds;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onDisplayInfoChanged(DisplayInfo displayInfo) {
        this.mDisplayInfo.copyFrom(displayInfo);
        notifyMovementBoundsChanged(false, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean onTaskStackBoundsChanged(Rect targetBounds, Rect outBounds) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                DisplayInfo displayInfo = this.mDisplayContent.getDisplayInfo();
                if (isSameDimensionAndRotation(this.mDisplayInfo, displayInfo)) {
                    outBounds.setEmpty();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } else if (targetBounds.isEmpty()) {
                    this.mDisplayInfo.copyFrom(displayInfo);
                    outBounds.setEmpty();
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return false;
                } else {
                    this.mTmpRect.set(targetBounds);
                    Rect postChangeStackBounds = this.mTmpRect;
                    float snapFraction = getSnapFraction(postChangeStackBounds);
                    this.mDisplayInfo.copyFrom(displayInfo);
                    Rect postChangeMovementBounds = getMovementBounds(postChangeStackBounds, false, false);
                    this.mSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds, snapFraction);
                    if (this.mIsMinimized) {
                        applyMinimizedOffset(postChangeStackBounds, postChangeMovementBounds);
                    }
                    notifyMovementBoundsChanged(false, false);
                    outBounds.set(postChangeStackBounds);
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return true;
                }
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAdjustedForIme(boolean adjustedForIme, int imeHeight) {
        boolean imeShowing = adjustedForIme && imeHeight > 0;
        int imeHeight2 = imeShowing ? imeHeight : 0;
        if (imeShowing == this.mIsImeShowing && imeHeight2 == this.mImeHeight) {
            return;
        }
        this.mIsImeShowing = imeShowing;
        this.mImeHeight = imeHeight2;
        notifyImeVisibilityChanged(imeShowing, imeHeight2);
        notifyMovementBoundsChanged(true, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAdjustedForShelf(boolean adjustedForShelf, int shelfHeight) {
        boolean shelfShowing = adjustedForShelf && shelfHeight > 0;
        if (shelfShowing == this.mIsShelfShowing && shelfHeight == this.mShelfHeight) {
            return;
        }
        this.mIsShelfShowing = shelfShowing;
        this.mShelfHeight = shelfHeight;
        notifyShelfVisibilityChanged(shelfShowing, shelfHeight);
        notifyMovementBoundsChanged(false, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAspectRatio(float aspectRatio) {
        if (Float.compare(this.mAspectRatio, aspectRatio) != 0) {
            this.mAspectRatio = aspectRatio;
            notifyMovementBoundsChanged(false, false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public float getAspectRatio() {
        return this.mAspectRatio;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setActions(List<RemoteAction> actions) {
        this.mActions.clear();
        if (actions != null) {
            this.mActions.addAll(actions);
        }
        notifyActionsChanged(this.mActions);
    }

    private boolean isSameDimensionAndRotation(DisplayInfo display1, DisplayInfo display2) {
        Preconditions.checkNotNull(display1);
        Preconditions.checkNotNull(display2);
        return display1.rotation == display2.rotation && display1.logicalWidth == display2.logicalWidth && display1.logicalHeight == display2.logicalHeight;
    }

    private void notifyImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        IPinnedStackListener iPinnedStackListener = this.mPinnedStackListener;
        if (iPinnedStackListener != null) {
            try {
                iPinnedStackListener.onImeVisibilityChanged(imeVisible, imeHeight);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering bounds changed event.", e);
            }
        }
    }

    private void notifyShelfVisibilityChanged(boolean shelfVisible, int shelfHeight) {
        IPinnedStackListener iPinnedStackListener = this.mPinnedStackListener;
        if (iPinnedStackListener != null) {
            try {
                iPinnedStackListener.onShelfVisibilityChanged(shelfVisible, shelfHeight);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering bounds changed event.", e);
            }
        }
    }

    private void notifyMinimizeChanged(boolean isMinimized) {
        IPinnedStackListener iPinnedStackListener = this.mPinnedStackListener;
        if (iPinnedStackListener != null) {
            try {
                iPinnedStackListener.onMinimizedStateChanged(isMinimized);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering minimize changed event.", e);
            }
        }
    }

    private void notifyActionsChanged(List<RemoteAction> actions) {
        IPinnedStackListener iPinnedStackListener = this.mPinnedStackListener;
        if (iPinnedStackListener != null) {
            try {
                iPinnedStackListener.onActionsChanged(new ParceledListSlice(actions));
            } catch (RemoteException e) {
                Slog.e(TAG, "Error delivering actions changed event.", e);
            }
        }
    }

    private void notifyMovementBoundsChanged(boolean fromImeAdjustment, boolean fromShelfAdjustment) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                if (this.mPinnedStackListener == null) {
                    WindowManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                try {
                    Rect insetBounds = new Rect();
                    getInsetBounds(insetBounds);
                    Rect normalBounds = getDefaultBounds(-1.0f);
                    if (isValidPictureInPictureAspectRatio(this.mAspectRatio)) {
                        transformBoundsToAspectRatio(normalBounds, this.mAspectRatio, false);
                    }
                    Rect animatingBounds = this.mTmpAnimatingBoundsRect;
                    TaskStack pinnedStack = this.mDisplayContent.getPinnedStack();
                    if (pinnedStack != null) {
                        pinnedStack.getAnimationOrCurrentBounds(animatingBounds);
                    } else {
                        animatingBounds.set(normalBounds);
                    }
                    this.mPinnedStackListener.onMovementBoundsChanged(insetBounds, normalBounds, animatingBounds, fromImeAdjustment, fromShelfAdjustment, this.mDisplayInfo.rotation);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error delivering actions changed event.", e);
                }
                WindowManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private void getInsetBounds(Rect outRect) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mDisplayContent.getDisplayPolicy().getStableInsetsLw(this.mDisplayInfo.rotation, this.mDisplayInfo.logicalWidth, this.mDisplayInfo.logicalHeight, this.mDisplayInfo.displayCutout, this.mTmpInsets);
                outRect.set(this.mTmpInsets.left + this.mScreenEdgeInsets.x, this.mTmpInsets.top + this.mScreenEdgeInsets.y, (this.mDisplayInfo.logicalWidth - this.mTmpInsets.right) - this.mScreenEdgeInsets.x, (this.mDisplayInfo.logicalHeight - this.mTmpInsets.bottom) - this.mScreenEdgeInsets.y);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    private Rect getMovementBounds(Rect stackBounds) {
        Rect movementBounds;
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                movementBounds = getMovementBounds(stackBounds, true, true);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return movementBounds;
    }

    private Rect getMovementBounds(Rect stackBounds, boolean adjustForIme, boolean adjustForShelf) {
        Rect movementBounds;
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                movementBounds = new Rect();
                getInsetBounds(movementBounds);
                PipSnapAlgorithm pipSnapAlgorithm = this.mSnapAlgorithm;
                int i = 0;
                int i2 = (adjustForIme && this.mIsImeShowing) ? this.mImeHeight : 0;
                if (adjustForShelf && this.mIsShelfShowing) {
                    i = this.mShelfHeight;
                }
                pipSnapAlgorithm.getMovementBounds(stackBounds, movementBounds, movementBounds, Math.max(i2, i));
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
        return movementBounds;
    }

    private void applyMinimizedOffset(Rect stackBounds, Rect movementBounds) {
        synchronized (this.mService.mGlobalLock) {
            try {
                WindowManagerService.boostPriorityForLockedSection();
                this.mTmpDisplaySize.set(this.mDisplayInfo.logicalWidth, this.mDisplayInfo.logicalHeight);
                this.mService.getStableInsetsLocked(this.mDisplayContent.getDisplayId(), this.mStableInsets);
                this.mSnapAlgorithm.applyMinimizedOffset(stackBounds, movementBounds, this.mTmpDisplaySize, this.mStableInsets);
            } catch (Throwable th) {
                WindowManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        WindowManagerService.resetPriorityAfterLockedSection();
    }

    private float getSnapFraction(Rect stackBounds) {
        return this.mSnapAlgorithm.getSnapFraction(stackBounds, getMovementBounds(stackBounds));
    }

    private int dpToPx(float dpValue, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(1, dpValue, dm);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "PinnedStackController");
        pw.print(prefix + "  defaultBounds=");
        getDefaultBounds(-1.0f).printShortString(pw);
        pw.println();
        pw.println(prefix + "  mDefaultMinSize=" + this.mDefaultMinSize);
        pw.println(prefix + "  mDefaultStackGravity=" + this.mDefaultStackGravity);
        pw.println(prefix + "  mDefaultAspectRatio=" + this.mDefaultAspectRatio);
        this.mService.getStackBounds(2, 1, this.mTmpRect);
        pw.print(prefix + "  movementBounds=");
        getMovementBounds(this.mTmpRect).printShortString(pw);
        pw.println();
        pw.println(prefix + "  mIsImeShowing=" + this.mIsImeShowing);
        pw.println(prefix + "  mImeHeight=" + this.mImeHeight);
        pw.println(prefix + "  mIsShelfShowing=" + this.mIsShelfShowing);
        pw.println(prefix + "  mShelfHeight=" + this.mShelfHeight);
        pw.println(prefix + "  mReentrySnapFraction=" + this.mReentrySnapFraction);
        pw.println(prefix + "  mIsMinimized=" + this.mIsMinimized);
        pw.println(prefix + "  mAspectRatio=" + this.mAspectRatio);
        pw.println(prefix + "  mMinAspectRatio=" + this.mMinAspectRatio);
        pw.println(prefix + "  mMaxAspectRatio=" + this.mMaxAspectRatio);
        if (this.mActions.isEmpty()) {
            pw.println(prefix + "  mActions=[]");
        } else {
            pw.println(prefix + "  mActions=[");
            for (int i = 0; i < this.mActions.size(); i++) {
                RemoteAction action = this.mActions.get(i);
                pw.print(prefix + "    Action[" + i + "]: ");
                action.dump("", pw);
            }
            pw.println(prefix + "  ]");
        }
        pw.println(prefix + "  mDisplayInfo=" + this.mDisplayInfo);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        getDefaultBounds(-1.0f).writeToProto(proto, 1146756268033L);
        this.mService.getStackBounds(2, 1, this.mTmpRect);
        getMovementBounds(this.mTmpRect).writeToProto(proto, 1146756268034L);
        proto.end(token);
    }
}
