package com.android.server.wm;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Region;
import android.graphics.drawable.ColorDrawable;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.FrameLayout;

/* loaded from: classes2.dex */
public class ImmersiveModeConfirmation {
    private static final String CONFIRMED = "confirmed";
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_SHOW_EVERY_TIME = false;
    private static final String TAG = "ImmersiveModeConfirmation";
    private static boolean sConfirmed;
    private ClingWindowView mClingWindow;
    private final Context mContext;
    private final H mHandler;
    private final long mPanicThresholdMs;
    private long mPanicTime;
    private final long mShowDelayMs;
    private boolean mVrModeEnabled;
    private WindowManager mWindowManager;
    private final IBinder mWindowToken = new Binder();
    private int mLockTaskState = 0;
    private final Runnable mConfirm = new Runnable() { // from class: com.android.server.wm.ImmersiveModeConfirmation.1
        @Override // java.lang.Runnable
        public void run() {
            Slog.d(ImmersiveModeConfirmation.TAG, "mConfirm.run()");
            if (!ImmersiveModeConfirmation.sConfirmed) {
                boolean unused = ImmersiveModeConfirmation.sConfirmed = true;
                ImmersiveModeConfirmation.saveSetting(ImmersiveModeConfirmation.this.mContext);
            }
            ImmersiveModeConfirmation.this.handleHide();
        }
    };

    /* JADX INFO: Access modifiers changed from: package-private */
    public ImmersiveModeConfirmation(Context context, Looper looper, boolean vrModeEnabled) {
        Display display = context.getDisplay();
        Context uiContext = ActivityThread.currentActivityThread().getSystemUiContext();
        this.mContext = display.getDisplayId() == 0 ? uiContext : uiContext.createDisplayContext(display);
        this.mHandler = new H(looper);
        this.mShowDelayMs = getNavBarExitDuration() * 3;
        this.mPanicThresholdMs = context.getResources().getInteger(17694814);
        this.mVrModeEnabled = vrModeEnabled;
    }

    private long getNavBarExitDuration() {
        Animation exit = AnimationUtils.loadAnimation(this.mContext, 17432615);
        if (exit != null) {
            return exit.getDuration();
        }
        return 0L;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean loadSetting(int currentUserId, Context context) {
        boolean wasConfirmed = sConfirmed;
        sConfirmed = false;
        Slog.d(TAG, String.format("loadSetting() currentUserId=%d", Integer.valueOf(currentUserId)));
        String value = null;
        try {
            value = Settings.Secure.getStringForUser(context.getContentResolver(), "immersive_mode_confirmations", -2);
            sConfirmed = CONFIRMED.equals(value);
            Slog.d(TAG, "Loaded sConfirmed=" + sConfirmed);
        } catch (Throwable t) {
            Slog.w(TAG, "Error loading confirmations, value=" + value, t);
        }
        if (sConfirmed == wasConfirmed) {
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void saveSetting(Context context) {
        Slog.d(TAG, "saveSetting()");
        try {
            String value = sConfirmed ? CONFIRMED : null;
            Settings.Secure.putStringForUser(context.getContentResolver(), "immersive_mode_confirmations", value, -2);
            Slog.d(TAG, "Saved value=" + value);
        } catch (Throwable t) {
            Slog.w(TAG, "Error saving confirmations, sConfirmed=" + sConfirmed, t);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void immersiveModeChangedLw(String pkg, boolean isImmersiveMode, boolean userSetupComplete, boolean navBarEmpty) {
        this.mHandler.removeMessages(1);
        if (!isImmersiveMode) {
            this.mHandler.sendEmptyMessage(2);
            return;
        }
        boolean disabled = PolicyControl.disableImmersiveConfirmation(pkg);
        Slog.d(TAG, String.format("immersiveModeChanged() disabled=%s sConfirmed=%s", Boolean.valueOf(disabled), Boolean.valueOf(sConfirmed)));
        if (!disabled && !sConfirmed && userSetupComplete && !this.mVrModeEnabled && !navBarEmpty && !UserManager.isDeviceInDemoMode(this.mContext) && this.mLockTaskState != 1) {
            this.mHandler.sendEmptyMessageDelayed(1, this.mShowDelayMs);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean onPowerKeyDown(boolean isScreenOn, long time, boolean inImmersiveMode, boolean navBarEmpty) {
        if (!isScreenOn && time - this.mPanicTime < this.mPanicThresholdMs) {
            return this.mClingWindow == null;
        }
        if (isScreenOn && inImmersiveMode && !navBarEmpty) {
            this.mPanicTime = time;
        } else {
            this.mPanicTime = 0L;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void confirmCurrentPrompt() {
        if (this.mClingWindow != null) {
            Slog.d(TAG, "confirmCurrentPrompt()");
            this.mHandler.post(this.mConfirm);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleHide() {
        if (this.mClingWindow != null) {
            Slog.d(TAG, "Hiding immersive mode confirmation");
            getWindowManager().removeView(this.mClingWindow);
            this.mClingWindow = null;
        }
    }

    private WindowManager.LayoutParams getClingWindowLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, 2014, 16777504, -3);
        lp.privateFlags |= 16;
        lp.setTitle(TAG);
        lp.windowAnimations = 16974586;
        lp.token = getWindowToken();
        return lp;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public FrameLayout.LayoutParams getBubbleLayoutParams() {
        return new FrameLayout.LayoutParams(this.mContext.getResources().getDimensionPixelSize(17105198), -2, 49);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IBinder getWindowToken() {
        return this.mWindowToken;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class ClingWindowView extends FrameLayout {
        private static final int ANIMATION_DURATION = 250;
        private static final int BGCOLOR = Integer.MIN_VALUE;
        private static final int OFFSET_DP = 96;
        private ViewGroup mClingLayout;
        private final ColorDrawable mColor;
        private ValueAnimator mColorAnim;
        private final Runnable mConfirm;
        private ViewTreeObserver.OnComputeInternalInsetsListener mInsetsListener;
        private final Interpolator mInterpolator;
        private BroadcastReceiver mReceiver;
        private Runnable mUpdateLayoutRunnable;

        ClingWindowView(Context context, Runnable confirm) {
            super(context);
            this.mColor = new ColorDrawable(0);
            this.mUpdateLayoutRunnable = new Runnable() { // from class: com.android.server.wm.ImmersiveModeConfirmation.ClingWindowView.1
                @Override // java.lang.Runnable
                public void run() {
                    if (ClingWindowView.this.mClingLayout != null && ClingWindowView.this.mClingLayout.getParent() != null) {
                        ClingWindowView.this.mClingLayout.setLayoutParams(ImmersiveModeConfirmation.this.getBubbleLayoutParams());
                    }
                }
            };
            this.mInsetsListener = new ViewTreeObserver.OnComputeInternalInsetsListener() { // from class: com.android.server.wm.ImmersiveModeConfirmation.ClingWindowView.2
                private final int[] mTmpInt2 = new int[2];

                public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
                    ClingWindowView.this.mClingLayout.getLocationInWindow(this.mTmpInt2);
                    inoutInfo.setTouchableInsets(3);
                    Region region = inoutInfo.touchableRegion;
                    int[] iArr = this.mTmpInt2;
                    region.set(iArr[0], iArr[1], iArr[0] + ClingWindowView.this.mClingLayout.getWidth(), this.mTmpInt2[1] + ClingWindowView.this.mClingLayout.getHeight());
                }
            };
            this.mReceiver = new BroadcastReceiver() { // from class: com.android.server.wm.ImmersiveModeConfirmation.ClingWindowView.3
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context2, Intent intent) {
                    if (intent.getAction().equals("android.intent.action.CONFIGURATION_CHANGED")) {
                        ClingWindowView clingWindowView = ClingWindowView.this;
                        clingWindowView.post(clingWindowView.mUpdateLayoutRunnable);
                    }
                }
            };
            this.mConfirm = confirm;
            setBackground(this.mColor);
            setImportantForAccessibility(2);
            this.mInterpolator = AnimationUtils.loadInterpolator(this.mContext, 17563662);
        }

        @Override // android.view.ViewGroup, android.view.View
        public void onAttachedToWindow() {
            super.onAttachedToWindow();
            DisplayMetrics metrics = new DisplayMetrics();
            ImmersiveModeConfirmation.this.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            float density = metrics.density;
            getViewTreeObserver().addOnComputeInternalInsetsListener(this.mInsetsListener);
            this.mClingLayout = (ViewGroup) View.inflate(getContext(), 17367168, null);
            Button ok = (Button) this.mClingLayout.findViewById(16909289);
            ok.setOnClickListener(new View.OnClickListener() { // from class: com.android.server.wm.ImmersiveModeConfirmation.ClingWindowView.4
                @Override // android.view.View.OnClickListener
                public void onClick(View v) {
                    ClingWindowView.this.mConfirm.run();
                }
            });
            addView(this.mClingLayout, ImmersiveModeConfirmation.this.getBubbleLayoutParams());
            if (ActivityManager.isHighEndGfx()) {
                final View cling = this.mClingLayout;
                cling.setAlpha(0.0f);
                cling.setTranslationY((-96.0f) * density);
                postOnAnimation(new Runnable() { // from class: com.android.server.wm.ImmersiveModeConfirmation.ClingWindowView.5
                    @Override // java.lang.Runnable
                    public void run() {
                        cling.animate().alpha(1.0f).translationY(0.0f).setDuration(250L).setInterpolator(ClingWindowView.this.mInterpolator).withLayer().start();
                        ClingWindowView.this.mColorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), 0, Integer.MIN_VALUE);
                        ClingWindowView.this.mColorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.android.server.wm.ImmersiveModeConfirmation.ClingWindowView.5.1
                            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
                            public void onAnimationUpdate(ValueAnimator animation) {
                                int c = ((Integer) animation.getAnimatedValue()).intValue();
                                ClingWindowView.this.mColor.setColor(c);
                            }
                        });
                        ClingWindowView.this.mColorAnim.setDuration(250L);
                        ClingWindowView.this.mColorAnim.setInterpolator(ClingWindowView.this.mInterpolator);
                        ClingWindowView.this.mColorAnim.start();
                    }
                });
            } else {
                this.mColor.setColor(Integer.MIN_VALUE);
            }
            this.mContext.registerReceiver(this.mReceiver, new IntentFilter("android.intent.action.CONFIGURATION_CHANGED"));
        }

        @Override // android.view.ViewGroup, android.view.View
        public void onDetachedFromWindow() {
            this.mContext.unregisterReceiver(this.mReceiver);
        }

        @Override // android.view.View
        public boolean onTouchEvent(MotionEvent motion) {
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public WindowManager getWindowManager() {
        if (this.mWindowManager == null) {
            this.mWindowManager = (WindowManager) this.mContext.getSystemService("window");
        }
        return this.mWindowManager;
    }

    private void handleShow() {
        Slog.d(TAG, "Showing immersive mode confirmation mClingWindow=" + this.mClingWindow);
        if (this.mClingWindow == null) {
            this.mClingWindow = new ClingWindowView(this.mContext, this.mConfirm);
            this.mClingWindow.setSystemUiVisibility(768);
            WindowManager.LayoutParams lp = getClingWindowLayoutParams();
            getWindowManager().addView(this.mClingWindow, lp);
        }
    }

    /* loaded from: classes2.dex */
    private final class H extends Handler {
        private static final int HIDE = 2;
        private static final int SHOW = 1;

        H(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what != 1) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onVrStateChangedLw(boolean enabled) {
        this.mVrModeEnabled = enabled;
        if (this.mVrModeEnabled) {
            this.mHandler.removeMessages(1);
            this.mHandler.sendEmptyMessage(2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onLockTaskModeChangedLw(int lockTaskState) {
        this.mLockTaskState = lockTaskState;
    }
}
