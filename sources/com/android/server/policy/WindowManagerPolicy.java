package com.android.server.policy;

import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.IApplicationToken;
import android.view.IDisplayFoldListener;
import android.view.IWindowManager;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.view.animation.Animation;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.notification.NotificationShellCmd;
import com.android.server.wm.DisplayRotation;
import com.android.server.wm.WindowFrames;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/* loaded from: classes.dex */
public interface WindowManagerPolicy extends WindowManagerPolicyConstants {
    public static final int ACTION_PASS_TO_USER = 1;
    public static final int COLOR_FADE_LAYER = 1073741825;
    public static final int FINISH_LAYOUT_REDO_ANIM = 8;
    public static final int FINISH_LAYOUT_REDO_CONFIG = 2;
    public static final int FINISH_LAYOUT_REDO_LAYOUT = 1;
    public static final int FINISH_LAYOUT_REDO_WALLPAPER = 4;
    public static final int TRANSIT_ENTER = 1;
    public static final int TRANSIT_EXIT = 2;
    public static final int TRANSIT_HIDE = 4;
    public static final int TRANSIT_PREVIEW_DONE = 5;
    public static final int TRANSIT_SHOW = 3;
    public static final int USER_ROTATION_FREE = 0;
    public static final int USER_ROTATION_LOCKED = 1;

    /* loaded from: classes.dex */
    public interface DisplayContentInfo {
        Display getDisplay();

        DisplayRotation getDisplayRotation();
    }

    /* loaded from: classes.dex */
    public interface InputConsumer {
        void dismiss();
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface NavigationBarPosition {
    }

    /* loaded from: classes.dex */
    public interface OnKeyguardExitResult {
        void onKeyguardExitResult(boolean z);
    }

    /* loaded from: classes.dex */
    public interface RotationSource {
        int getProposedRotation();

        void setCurrentRotation(int i);
    }

    /* loaded from: classes.dex */
    public interface ScreenOffListener {
        void onScreenOff();
    }

    /* loaded from: classes.dex */
    public interface ScreenOnListener {
        void onScreenOn();
    }

    /* loaded from: classes.dex */
    public interface StartingSurface {
        void remove();
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface UserRotationMode {
    }

    StartingSurface addSplashScreen(IBinder iBinder, String str, int i, CompatibilityInfo compatibilityInfo, CharSequence charSequence, int i2, int i3, int i4, int i5, Configuration configuration, int i6);

    void adjustConfigurationLw(Configuration configuration, int i, int i2);

    void applyKeyguardPolicyLw(WindowState windowState, WindowState windowState2);

    boolean canBeHiddenByKeyguardLw(WindowState windowState);

    boolean canDismissBootAnimation();

    int checkAddPermission(WindowManager.LayoutParams layoutParams, int[] iArr);

    boolean checkShowToOwnerOnly(WindowManager.LayoutParams layoutParams);

    Animation createHiddenByKeyguardExit(boolean z, boolean z2, boolean z3);

    Animation createKeyguardWallpaperExit(boolean z);

    void dismissKeyguardLw(IKeyguardDismissCallback iKeyguardDismissCallback, CharSequence charSequence);

    KeyEvent dispatchUnhandledKey(WindowState windowState, KeyEvent keyEvent, int i);

    void dump(String str, PrintWriter printWriter, String[] strArr);

    void enableKeyguard(boolean z);

    void enableScreenAfterBoot();

    void exitKeyguardSecurely(OnKeyguardExitResult onKeyguardExitResult);

    void finishedGoingToSleep(int i);

    void finishedWakingUp(int i);

    int getMaxWallpaperLayer();

    int getUiMode();

    boolean hasNavigationBar();

    void hideBootMessages();

    boolean inKeyguardRestrictedKeyInputMode();

    void init(Context context, IWindowManager iWindowManager, WindowManagerFuncs windowManagerFuncs);

    long interceptKeyBeforeDispatching(WindowState windowState, KeyEvent keyEvent, int i);

    int interceptKeyBeforeQueueing(KeyEvent keyEvent, int i);

    int interceptMotionBeforeQueueingNonInteractive(int i, long j, int i2);

    boolean isKeyguardDrawnLw();

    boolean isKeyguardHostWindow(WindowManager.LayoutParams layoutParams);

    boolean isKeyguardLocked();

    boolean isKeyguardOccluded();

    boolean isKeyguardSecure(int i);

    boolean isKeyguardShowingAndNotOccluded();

    boolean isKeyguardTrustedLw();

    boolean isScreenOn();

    boolean isTopLevelWindow(int i);

    boolean isUserSetupComplete();

    void keepScreenOnStartedLw();

    void keepScreenOnStoppedLw();

    void lockNow(Bundle bundle);

    void notifyCameraLensCoverSwitchChanged(long j, boolean z);

    void notifyLidSwitchChanged(long j, boolean z);

    boolean okToAnimate();

    void onKeyguardOccludedChangedLw(boolean z);

    void onSystemUiStarted();

    boolean performHapticFeedback(int i, String str, int i2, boolean z, String str2);

    void registerShortcutKey(long j, IShortcutService iShortcutService) throws RemoteException;

    void requestUserActivityNotification();

    void screenTurnedOff();

    void screenTurnedOn();

    void screenTurningOff(ScreenOffListener screenOffListener);

    void screenTurningOn(ScreenOnListener screenOnListener);

    void setAllowLockscreenWhenOn(int i, boolean z);

    boolean setAodShowing(boolean z);

    void setCurrentUserLw(int i);

    void setDefaultDisplay(DisplayContentInfo displayContentInfo);

    void setKeyguardCandidateLw(WindowState windowState);

    void setNavBarVirtualKeyHapticFeedbackEnabledLw(boolean z);

    void setPipVisibilityLw(boolean z);

    void setRecentsVisibilityLw(boolean z);

    void setSafeMode(boolean z);

    void setSwitchingUser(boolean z);

    void setTopFocusedDisplay(int i);

    void showBootMessage(CharSequence charSequence, boolean z);

    void showGlobalActions();

    void showRecentApps();

    void startKeyguardExitAnimation(long j, long j2);

    void startedGoingToSleep(int i);

    void startedWakingUp(int i);

    void systemBooted();

    void systemReady();

    void userActivity();

    void writeToProto(ProtoOutputStream protoOutputStream, long j);

    /* loaded from: classes.dex */
    public interface WindowState {
        boolean canAcquireSleepToken();

        boolean canAffectSystemUiFlags();

        boolean canReceiveKeys();

        boolean canShowWhenLocked();

        void computeFrameLw();

        IApplicationToken getAppToken();

        WindowManager.LayoutParams getAttrs();

        int getBaseType();

        Rect getContentFrameLw();

        Rect getDisplayFrameLw();

        int getDisplayId();

        Rect getFrameLw();

        Rect getGivenContentInsetsLw();

        boolean getGivenInsetsPendingLw();

        Rect getGivenVisibleInsetsLw();

        boolean getNeedsMenuLw(WindowState windowState);

        Rect getOverscanFrameLw();

        String getOwningPackage();

        int getOwningUid();

        int getRotationAnimationHint();

        int getSurfaceLayer();

        int getSystemUiVisibility();

        Rect getVisibleFrameLw();

        WindowFrames getWindowFrames();

        int getWindowingMode();

        boolean hasAppShownWindows();

        @Deprecated
        boolean hasDrawnLw();

        boolean hideLw(boolean z);

        boolean inMultiWindowMode();

        boolean isAlive();

        boolean isAnimatingLw();

        boolean isDefaultDisplay();

        boolean isDimming();

        boolean isDisplayedLw();

        boolean isDrawnLw();

        boolean isGoneForLayoutLw();

        boolean isInputMethodTarget();

        boolean isInputMethodWindow();

        boolean isVisibleLw();

        boolean isVoiceInteraction();

        boolean showLw(boolean z);

        void writeIdentifierToProto(ProtoOutputStream protoOutputStream, long j);

        default boolean isLetterboxedForDisplayCutoutLw() {
            return false;
        }

        default boolean isLetterboxedOverlappingWith(Rect rect) {
            return false;
        }

        default int getActivityType() {
            return 0;
        }

        default boolean canAddInternalSystemWindow() {
            return false;
        }

        default void setWindowAttrs(WindowManager.LayoutParams lp) {
        }

        default WindowManager.LayoutParams getAppWindowAttrs() {
            return null;
        }
    }

    /* loaded from: classes.dex */
    public interface WindowManagerFuncs {
        public static final int CAMERA_LENS_COVERED = 1;
        public static final int CAMERA_LENS_COVER_ABSENT = -1;
        public static final int CAMERA_LENS_UNCOVERED = 0;
        public static final int LID_ABSENT = -1;
        public static final int LID_BEHAVIOR_LOCK = 2;
        public static final int LID_BEHAVIOR_NONE = 0;
        public static final int LID_BEHAVIOR_SLEEP = 1;
        public static final int LID_CLOSED = 0;
        public static final int LID_OPEN = 1;

        InputConsumer createInputConsumer(Looper looper, String str, InputEventReceiver.Factory factory, int i);

        int getCameraLensCoverState();

        WindowState getInputMethodWindowLw();

        int getLidState();

        void getStackBounds(int i, int i2, Rect rect);

        Object getWindowManagerLock();

        void lockDeviceNow();

        void moveDisplayToTop(int i);

        void notifyKeyguardTrustedChanged();

        void onKeyguardShowingAndNotOccludedChanged();

        void onPowerKeyDown(boolean z);

        void onUserSwitched();

        void reboot(boolean z);

        void rebootSafeMode(boolean z);

        void registerPointerEventListener(WindowManagerPolicyConstants.PointerEventListener pointerEventListener, int i);

        void screenTurningOff(ScreenOffListener screenOffListener);

        void shutdown(boolean z);

        void switchKeyboardLayout(int i, int i2);

        void triggerAnimationFailsafe();

        void unregisterPointerEventListener(WindowManagerPolicyConstants.PointerEventListener pointerEventListener, int i);

        static String lidStateToString(int lid) {
            if (lid != -1) {
                if (lid != 0) {
                    if (lid == 1) {
                        return "LID_OPEN";
                    }
                    return Integer.toString(lid);
                }
                return "LID_CLOSED";
            }
            return "LID_ABSENT";
        }

        static String cameraLensStateToString(int lens) {
            if (lens != -1) {
                if (lens != 0) {
                    if (lens == 1) {
                        return "CAMERA_LENS_COVERED";
                    }
                    return Integer.toString(lens);
                }
                return "CAMERA_LENS_UNCOVERED";
            }
            return "CAMERA_LENS_COVER_ABSENT";
        }
    }

    default int getWindowLayerLw(WindowState win) {
        return getWindowLayerFromTypeLw(win.getBaseType(), win.canAddInternalSystemWindow());
    }

    default int getWindowLayerFromTypeLw(int type) {
        if (WindowManager.LayoutParams.isSystemAlertWindowType(type)) {
            throw new IllegalArgumentException("Use getWindowLayerFromTypeLw() or getWindowLayerLw() for alert window types");
        }
        return getWindowLayerFromTypeLw(type, false);
    }

    default int getWindowLayerFromTypeLw(int type, boolean canAddInternalSystemWindow) {
        if (type < 1 || type > 99) {
            switch (type) {
                case 2000:
                    return 17;
                case 2001:
                case 2033:
                    return 4;
                case 2002:
                    return 3;
                case 2003:
                    return canAddInternalSystemWindow ? 13 : 10;
                case 2004:
                case 2025:
                case 2028:
                case 2029:
                default:
                    Slog.e("WindowManager", "Unknown window type: " + type);
                    return 2;
                case 2005:
                    return 8;
                case 2006:
                    return canAddInternalSystemWindow ? 22 : 11;
                case 2007:
                    return 9;
                case 2008:
                    return 7;
                case 2009:
                    return 20;
                case 2010:
                    return canAddInternalSystemWindow ? 26 : 10;
                case 2011:
                    return 15;
                case 2012:
                    return 16;
                case 2013:
                    return 1;
                case 2014:
                    return 18;
                case 2015:
                    return 31;
                case 2016:
                    return 29;
                case 2017:
                    return 19;
                case 2018:
                    return 33;
                case 2019:
                    return 23;
                case NotificationShellCmd.NOTIFICATION_ID /* 2020 */:
                    return 21;
                case 2021:
                    return 32;
                case 2022:
                    return 6;
                case 2023:
                    return 14;
                case 2024:
                    return 24;
                case 2026:
                    return 28;
                case 2027:
                    return 27;
                case 2030:
                case 2037:
                    return 2;
                case 2031:
                    return 5;
                case 2032:
                    return 30;
                case 2034:
                    return 2;
                case 2035:
                    return 2;
                case 2036:
                    return 25;
                case 2038:
                    return 12;
            }
        }
        return 2;
    }

    default int getSubWindowLayerFromTypeLw(int type) {
        switch (type) {
            case 1000:
            case 1003:
                return 1;
            case NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE /* 1001 */:
                return -2;
            case 1002:
                return 2;
            case 1004:
                return -1;
            case 1005:
                return 3;
            default:
                Slog.e("WindowManager", "Unknown sub-window type: " + type);
                return 0;
        }
    }

    default void setDismissImeOnBackKeyPressed(boolean newValue) {
    }

    static String userRotationModeToString(int mode) {
        if (mode != 0) {
            if (mode == 1) {
                return "USER_ROTATION_LOCKED";
            }
            return Integer.toString(mode);
        }
        return "USER_ROTATION_FREE";
    }

    default void registerDisplayFoldListener(IDisplayFoldListener listener) {
    }

    default void unregisterDisplayFoldListener(IDisplayFoldListener listener) {
    }

    default void setOverrideFoldedArea(Rect area) {
    }

    default Rect getFoldedArea() {
        return new Rect();
    }

    default void onDefaultDisplayFocusChangedLw(WindowState newFocus) {
    }

    default boolean allowFinishBootMessages() {
        return true;
    }

    default boolean allowFinishBootAnimation() {
        return true;
    }

    default void onBootChanged(boolean systemBooted, boolean showingBootMessages) {
    }

    default boolean checkBootAnimationCompleted() {
        return false;
    }

    default int requestInputPolicy(InputEvent event, int flags) {
        return -1;
    }
}
