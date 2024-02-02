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
import android.view.DisplayCutout;
import android.view.IApplicationToken;
import android.view.IWindowManager;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.view.animation.Animation;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.wm.DisplayFrames;
import com.android.server.wm.utils.WmDisplayCutout;
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

    int adjustSystemUiVisibilityLw(int i);

    void adjustWindowParamsLw(WindowState windowState, WindowManager.LayoutParams layoutParams, boolean z);

    boolean allowAppAnimationsLw();

    void applyPostLayoutPolicyLw(WindowState windowState, WindowManager.LayoutParams layoutParams, WindowState windowState2, WindowState windowState3);

    void beginPostLayoutPolicyLw(int i, int i2);

    boolean canBeHiddenByKeyguardLw(WindowState windowState);

    boolean canDismissBootAnimation();

    int checkAddPermission(WindowManager.LayoutParams layoutParams, int[] iArr);

    boolean checkShowToOwnerOnly(WindowManager.LayoutParams layoutParams);

    Animation createHiddenByKeyguardExit(boolean z, boolean z2);

    Animation createKeyguardWallpaperExit(boolean z);

    void dismissKeyguardLw(IKeyguardDismissCallback iKeyguardDismissCallback, CharSequence charSequence);

    KeyEvent dispatchUnhandledKey(WindowState windowState, KeyEvent keyEvent, int i);

    void dump(String str, PrintWriter printWriter, String[] strArr);

    void enableKeyguard(boolean z);

    void enableScreenAfterBoot();

    void exitKeyguardSecurely(OnKeyguardExitResult onKeyguardExitResult);

    int finishPostLayoutPolicyLw();

    void finishedGoingToSleep(int i);

    void finishedWakingUp();

    int focusChangedLw(WindowState windowState, WindowState windowState2);

    int getConfigDisplayHeight(int i, int i2, int i3, int i4, int i5, DisplayCutout displayCutout);

    int getConfigDisplayWidth(int i, int i2, int i3, int i4, int i5, DisplayCutout displayCutout);

    int getMaxWallpaperLayer();

    int getNavBarPosition();

    int getNonDecorDisplayHeight(int i, int i2, int i3, int i4, int i5, DisplayCutout displayCutout);

    int getNonDecorDisplayWidth(int i, int i2, int i3, int i4, int i5, DisplayCutout displayCutout);

    void getNonDecorInsetsLw(int i, int i2, int i3, DisplayCutout displayCutout, Rect rect);

    void getStableInsetsLw(int i, int i2, int i3, DisplayCutout displayCutout, Rect rect);

    int getSystemDecorLayerLw();

    int getUserRotationMode();

    boolean hasNavigationBar();

    void hideBootMessages();

    boolean inKeyguardRestrictedKeyInputMode();

    void init(Context context, IWindowManager iWindowManager, WindowManagerFuncs windowManagerFuncs);

    long interceptKeyBeforeDispatching(WindowState windowState, KeyEvent keyEvent, int i);

    int interceptKeyBeforeQueueing(KeyEvent keyEvent, int i);

    int interceptMotionBeforeQueueingNonInteractive(long j, int i);

    boolean isDefaultOrientationForced();

    boolean isDockSideAllowed(int i, int i2, int i3, int i4, int i5);

    boolean isKeyguardDrawnLw();

    boolean isKeyguardHostWindow(WindowManager.LayoutParams layoutParams);

    boolean isKeyguardLocked();

    boolean isKeyguardOccluded();

    boolean isKeyguardSecure(int i);

    boolean isKeyguardShowingAndNotOccluded();

    boolean isKeyguardTrustedLw();

    boolean isNavBarForcedShownLw(WindowState windowState);

    boolean isScreenOn();

    boolean isShowingDreamLw();

    boolean isTopLevelWindow(int i);

    void keepScreenOnStartedLw();

    void keepScreenOnStoppedLw();

    void lockNow(Bundle bundle);

    void notifyCameraLensCoverSwitchChanged(long j, boolean z);

    void notifyLidSwitchChanged(long j, boolean z);

    boolean okToAnimate();

    void onConfigurationChanged();

    void onKeyguardOccludedChangedLw(boolean z);

    void onLockTaskStateChangedLw(int i);

    void onSystemUiStarted();

    boolean performHapticFeedbackLw(WindowState windowState, int i, boolean z);

    int prepareAddWindowLw(WindowState windowState, WindowManager.LayoutParams layoutParams);

    void registerShortcutKey(long j, IShortcutService iShortcutService) throws RemoteException;

    void removeWindowLw(WindowState windowState);

    void requestUserActivityNotification();

    int rotationForOrientationLw(int i, int i2, boolean z);

    boolean rotationHasCompatibleMetricsLw(int i, int i2);

    void screenTurnedOff();

    void screenTurnedOn();

    void screenTurningOff(ScreenOffListener screenOffListener);

    void screenTurningOn(ScreenOnListener screenOnListener);

    int selectAnimationLw(WindowState windowState, int i);

    void selectRotationAnimationLw(int[] iArr);

    boolean setAodShowing(boolean z);

    void setCurrentOrientationLw(int i);

    void setCurrentUserLw(int i);

    void setInitialDisplaySize(Display display, int i, int i2, int i3);

    void setLastInputMethodWindowLw(WindowState windowState, WindowState windowState2);

    void setNavBarVirtualKeyHapticFeedbackEnabledLw(boolean z);

    void setPipVisibilityLw(boolean z);

    void setRecentsVisibilityLw(boolean z);

    void setRotationLw(int i);

    void setSafeMode(boolean z);

    void setSwitchingUser(boolean z);

    void setUserRotationMode(int i, int i2);

    boolean shouldRotateSeamlessly(int i, int i2);

    void showBootMessage(CharSequence charSequence, boolean z);

    void showGlobalActions();

    void showRecentApps();

    void startKeyguardExitAnimation(long j, long j2);

    void startedGoingToSleep(int i);

    void startedWakingUp();

    void systemBooted();

    void systemReady();

    void userActivity();

    boolean validateRotationAnimationLw(int i, int i2, boolean z);

    void writeToProto(ProtoOutputStream protoOutputStream, long j);

    default void onOverlayChangedLw() {
    }

    /* loaded from: classes.dex */
    public interface WindowState {
        boolean canAcquireSleepToken();

        boolean canAffectSystemUiFlags();

        void computeFrameLw(Rect rect, Rect rect2, Rect rect3, Rect rect4, Rect rect5, Rect rect6, Rect rect7, Rect rect8, WmDisplayCutout wmDisplayCutout, boolean z);

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

        int getWindowingMode();

        boolean hasAppShownWindows();

        @Deprecated
        boolean hasDrawnLw();

        boolean hideLw(boolean z);

        boolean isAlive();

        boolean isAnimatingLw();

        boolean isDefaultDisplay();

        boolean isDimming();

        boolean isDisplayedLw();

        boolean isDrawnLw();

        boolean isGoneForLayoutLw();

        boolean isInMultiWindowMode();

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
        public static final int LID_CLOSED = 0;
        public static final int LID_OPEN = 1;

        InputConsumer createInputConsumer(Looper looper, String str, InputEventReceiver.Factory factory);

        int getCameraLensCoverState();

        int getDockedDividerInsetsLw();

        WindowState getInputMethodWindowLw();

        int getLidState();

        void getStackBounds(int i, int i2, Rect rect);

        Object getWindowManagerLock();

        void lockDeviceNow();

        void notifyKeyguardTrustedChanged();

        void notifyShowingDreamChanged();

        void onKeyguardShowingAndNotOccludedChanged();

        void reboot(boolean z);

        void rebootSafeMode(boolean z);

        void reevaluateStatusBarVisibility();

        void registerPointerEventListener(WindowManagerPolicyConstants.PointerEventListener pointerEventListener);

        void screenTurningOff(ScreenOffListener screenOffListener);

        void shutdown(boolean z);

        void switchInputMethod(boolean z);

        void switchKeyboardLayout(int i, int i2);

        void triggerAnimationFailsafe();

        void unregisterPointerEventListener(WindowManagerPolicyConstants.PointerEventListener pointerEventListener);

        static String lidStateToString(int lid) {
            switch (lid) {
                case -1:
                    return "LID_ABSENT";
                case 0:
                    return "LID_CLOSED";
                case 1:
                    return "LID_OPEN";
                default:
                    return Integer.toString(lid);
            }
        }

        static String cameraLensStateToString(int lens) {
            switch (lens) {
                case -1:
                    return "CAMERA_LENS_COVER_ABSENT";
                case 0:
                    return "CAMERA_LENS_UNCOVERED";
                case 1:
                    return "CAMERA_LENS_COVERED";
                default:
                    return Integer.toString(lens);
            }
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
                    return canAddInternalSystemWindow ? 11 : 10;
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
                    return 14;
                case 2012:
                    return 15;
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
                case 2020:
                    return 21;
                case 2021:
                    return 32;
                case 2022:
                    return 6;
                case 2023:
                    return 13;
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

    default void beginLayoutLw(DisplayFrames displayFrames, int uiMode) {
    }

    default void layoutWindowLw(WindowState win, WindowState attached, DisplayFrames displayFrames) {
    }

    default boolean getLayoutHintLw(WindowManager.LayoutParams attrs, Rect taskBounds, DisplayFrames displayFrames, Rect outFrame, Rect outContentInsets, Rect outStableInsets, Rect outOutsets, DisplayCutout.ParcelableWrapper outDisplayCutout) {
        return false;
    }

    default void setDismissImeOnBackKeyPressed(boolean newValue) {
    }

    static String userRotationModeToString(int mode) {
        switch (mode) {
            case 0:
                return "USER_ROTATION_FREE";
            case 1:
                return "USER_ROTATION_LOCKED";
            default:
                return Integer.toString(mode);
        }
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
}
