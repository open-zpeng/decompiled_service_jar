package com.android.server.statusbar;

import android.app.ActivityThread;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationDelegate;
import com.android.server.policy.GlobalActionsProvider;
import com.android.server.power.ShutdownThread;
import com.android.server.wm.WindowManagerService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/* loaded from: classes2.dex */
public class StatusBarManagerService extends IStatusBarService.Stub implements DisplayManager.DisplayListener {
    private static final boolean SPEW = false;
    private static final String TAG = "StatusBarManagerService";
    private volatile IStatusBar mBar;
    private final Context mContext;
    private int mCurrentUserId;
    private GlobalActionsProvider.GlobalActionsListener mGlobalActionListener;
    private NotificationDelegate mNotificationDelegate;
    private final WindowManagerService mWindowManager;
    private Handler mHandler = new Handler();
    private ArrayMap<String, StatusBarIcon> mIcons = new ArrayMap<>();
    private final ArrayList<DisableRecord> mDisableRecords = new ArrayList<>();
    private IBinder mSysUiVisToken = new Binder();
    private final Object mLock = new Object();
    private final DeathRecipient mDeathRecipient = new DeathRecipient();
    private SparseArray<UiState> mDisplayUiState = new SparseArray<>();
    private final StatusBarManagerInternal mInternalService = new StatusBarManagerInternal() { // from class: com.android.server.statusbar.StatusBarManagerService.1
        private boolean mNotificationLightOn;

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void setNotificationDelegate(NotificationDelegate delegate) {
            StatusBarManagerService.this.mNotificationDelegate = delegate;
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void showScreenPinningRequest(int taskId) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.showScreenPinningRequest(taskId);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void showAssistDisclosure() {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.showAssistDisclosure();
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void startAssist(Bundle args) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.startAssist(args);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void onCameraLaunchGestureDetected(int source) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.onCameraLaunchGestureDetected(source);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void topAppWindowChanged(int displayId, boolean menuVisible) {
            StatusBarManagerService.this.topAppWindowChanged(displayId, menuVisible);
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void setSystemUiVisibility(int displayId, int vis, int fullscreenStackVis, int dockedStackVis, int mask, Rect fullscreenBounds, Rect dockedBounds, boolean isNavbarColorManagedByIme, String cause) {
            StatusBarManagerService.this.setSystemUiVisibility(displayId, vis, fullscreenStackVis, dockedStackVis, mask, fullscreenBounds, dockedBounds, isNavbarColorManagedByIme, cause);
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void toggleSplitScreen() {
            StatusBarManagerService.this.enforceStatusBarService();
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.toggleSplitScreen();
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void appTransitionFinished(int displayId) {
            StatusBarManagerService.this.enforceStatusBarService();
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.appTransitionFinished(displayId);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void toggleRecentApps() {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.toggleRecentApps();
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void setCurrentUser(int newUserId) {
            StatusBarManagerService.this.mCurrentUserId = newUserId;
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void preloadRecentApps() {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.preloadRecentApps();
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void cancelPreloadRecentApps() {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.cancelPreloadRecentApps();
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void showRecentApps(boolean triggeredFromAltTab) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.showRecentApps(triggeredFromAltTab);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.hideRecentApps(triggeredFromAltTab, triggeredFromHomeKey);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void dismissKeyboardShortcutsMenu() {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.dismissKeyboardShortcutsMenu();
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void toggleKeyboardShortcutsMenu(int deviceId) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.toggleKeyboardShortcutsMenu(deviceId);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void showChargingAnimation(int batteryLevel) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.showWirelessChargingAnimation(batteryLevel);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void showPictureInPictureMenu() {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.showPictureInPictureMenu();
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void setWindowState(int displayId, int window, int state) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.setWindowState(displayId, window, state);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void appTransitionPending(int displayId) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.appTransitionPending(displayId);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void appTransitionCancelled(int displayId) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.appTransitionCancelled(displayId);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void appTransitionStarting(int displayId, long statusBarAnimationsStartTime, long statusBarAnimationsDuration) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.appTransitionStarting(displayId, statusBarAnimationsStartTime, statusBarAnimationsDuration);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void setTopAppHidesStatusBar(boolean hidesStatusBar) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.setTopAppHidesStatusBar(hidesStatusBar);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public boolean showShutdownUi(boolean isReboot, String reason) {
            if (StatusBarManagerService.this.mContext.getResources().getBoolean(17891518) && StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.showShutdownUi(isReboot, reason);
                    return true;
                } catch (RemoteException e) {
                }
            }
            return false;
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void onProposedRotationChanged(int rotation, boolean isValid) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.onProposedRotationChanged(rotation, isValid);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void onDisplayReady(int displayId) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.onDisplayReady(displayId);
                } catch (RemoteException e) {
                }
            }
        }

        @Override // com.android.server.statusbar.StatusBarManagerInternal
        public void onRecentsAnimationStateChanged(boolean running) {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.onRecentsAnimationStateChanged(running);
                } catch (RemoteException e) {
                }
            }
        }
    };
    private final GlobalActionsProvider mGlobalActionsProvider = new GlobalActionsProvider() { // from class: com.android.server.statusbar.StatusBarManagerService.2
        @Override // com.android.server.policy.GlobalActionsProvider
        public boolean isGlobalActionsDisabled() {
            int disabled2 = ((UiState) StatusBarManagerService.this.mDisplayUiState.get(0)).getDisabled2();
            return (disabled2 & 8) != 0;
        }

        @Override // com.android.server.policy.GlobalActionsProvider
        public void setGlobalActionsListener(GlobalActionsProvider.GlobalActionsListener listener) {
            StatusBarManagerService.this.mGlobalActionListener = listener;
            StatusBarManagerService.this.mGlobalActionListener.onGlobalActionsAvailableChanged(StatusBarManagerService.this.mBar != null);
        }

        @Override // com.android.server.policy.GlobalActionsProvider
        public void showGlobalActions() {
            if (StatusBarManagerService.this.mBar != null) {
                try {
                    StatusBarManagerService.this.mBar.showGlobalActionsMenu();
                } catch (RemoteException e) {
                }
            }
        }
    };

    /* loaded from: classes2.dex */
    private class DeathRecipient implements IBinder.DeathRecipient {
        private DeathRecipient() {
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            StatusBarManagerService.this.mBar.asBinder().unlinkToDeath(this, 0);
            StatusBarManagerService.this.mBar = null;
            StatusBarManagerService.this.notifyBarAttachChanged();
        }

        public void linkToDeath() {
            try {
                StatusBarManagerService.this.mBar.asBinder().linkToDeath(StatusBarManagerService.this.mDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.e(StatusBarManagerService.TAG, "Unable to register Death Recipient for status bar", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class DisableRecord implements IBinder.DeathRecipient {
        String pkg;
        IBinder token;
        int userId;
        int what1;
        int what2;

        public DisableRecord(int userId, IBinder token) {
            this.userId = userId;
            this.token = token;
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Slog.i(StatusBarManagerService.TAG, "binder died for pkg=" + this.pkg);
            StatusBarManagerService.this.disableForUser(0, this.token, this.pkg, this.userId);
            StatusBarManagerService.this.disable2ForUser(0, this.token, this.pkg, this.userId);
            this.token.unlinkToDeath(this, 0);
        }

        public void setFlags(int what, int which, String pkg) {
            if (which == 1) {
                this.what1 = what;
            } else if (which == 2) {
                this.what2 = what;
            } else {
                Slog.w(StatusBarManagerService.TAG, "Can't set unsupported disable flag " + which + ": 0x" + Integer.toHexString(what));
            }
            this.pkg = pkg;
        }

        public int getFlags(int which) {
            if (which != 1) {
                if (which == 2) {
                    return this.what2;
                }
                Slog.w(StatusBarManagerService.TAG, "Can't get unsupported disable flag " + which);
                return 0;
            }
            return this.what1;
        }

        public boolean isEmpty() {
            return this.what1 == 0 && this.what2 == 0;
        }

        public String toString() {
            return String.format("userId=%d what1=0x%08X what2=0x%08X pkg=%s token=%s", Integer.valueOf(this.userId), Integer.valueOf(this.what1), Integer.valueOf(this.what2), this.pkg, this.token);
        }
    }

    public StatusBarManagerService(Context context, WindowManagerService windowManager) {
        this.mContext = context;
        this.mWindowManager = windowManager;
        LocalServices.addService(StatusBarManagerInternal.class, this.mInternalService);
        LocalServices.addService(GlobalActionsProvider.class, this.mGlobalActionsProvider);
        UiState state = new UiState();
        this.mDisplayUiState.put(0, state);
        DisplayManager displayManager = (DisplayManager) context.getSystemService("display");
        displayManager.registerDisplayListener(this, this.mHandler);
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayAdded(int displayId) {
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayRemoved(int displayId) {
        synchronized (this.mLock) {
            this.mDisplayUiState.remove(displayId);
        }
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayChanged(int displayId) {
    }

    public void expandNotificationsPanel() {
        enforceExpandStatusBar();
        if (this.mBar != null) {
            try {
                this.mBar.animateExpandNotificationsPanel();
            } catch (RemoteException e) {
            }
        }
    }

    public void collapsePanels() {
        enforceExpandStatusBar();
        if (this.mBar != null) {
            try {
                this.mBar.animateCollapsePanels();
            } catch (RemoteException e) {
            }
        }
    }

    public void togglePanel() {
        enforceExpandStatusBar();
        if (this.mBar != null) {
            try {
                this.mBar.togglePanel();
            } catch (RemoteException e) {
            }
        }
    }

    public void expandSettingsPanel(String subPanel) {
        enforceExpandStatusBar();
        if (this.mBar != null) {
            try {
                this.mBar.animateExpandSettingsPanel(subPanel);
            } catch (RemoteException e) {
            }
        }
    }

    public void addTile(ComponentName component) {
        enforceStatusBarOrShell();
        if (this.mBar != null) {
            try {
                this.mBar.addQsTile(component);
            } catch (RemoteException e) {
            }
        }
    }

    public void remTile(ComponentName component) {
        enforceStatusBarOrShell();
        if (this.mBar != null) {
            try {
                this.mBar.remQsTile(component);
            } catch (RemoteException e) {
            }
        }
    }

    public void clickTile(ComponentName component) {
        enforceStatusBarOrShell();
        if (this.mBar != null) {
            try {
                this.mBar.clickQsTile(component);
            } catch (RemoteException e) {
            }
        }
    }

    public void handleSystemKey(int key) throws RemoteException {
        enforceExpandStatusBar();
        if (this.mBar != null) {
            try {
                this.mBar.handleSystemKey(key);
            } catch (RemoteException e) {
            }
        }
    }

    public void showPinningEnterExitToast(boolean entering) throws RemoteException {
        if (this.mBar != null) {
            try {
                this.mBar.showPinningEnterExitToast(entering);
            } catch (RemoteException e) {
            }
        }
    }

    public void showPinningEscapeToast() throws RemoteException {
        if (this.mBar != null) {
            try {
                this.mBar.showPinningEscapeToast();
            } catch (RemoteException e) {
            }
        }
    }

    public void showBiometricDialog(Bundle bundle, IBiometricServiceReceiverInternal receiver, int type, boolean requireConfirmation, int userId) {
        enforceBiometricDialog();
        if (this.mBar != null) {
            try {
                this.mBar.showBiometricDialog(bundle, receiver, type, requireConfirmation, userId);
            } catch (RemoteException e) {
            }
        }
    }

    public void onBiometricAuthenticated(boolean authenticated, String failureReason) {
        enforceBiometricDialog();
        if (this.mBar != null) {
            try {
                this.mBar.onBiometricAuthenticated(authenticated, failureReason);
            } catch (RemoteException e) {
            }
        }
    }

    public void onBiometricHelp(String message) {
        enforceBiometricDialog();
        if (this.mBar != null) {
            try {
                this.mBar.onBiometricHelp(message);
            } catch (RemoteException e) {
            }
        }
    }

    public void onBiometricError(String error) {
        enforceBiometricDialog();
        if (this.mBar != null) {
            try {
                this.mBar.onBiometricError(error);
            } catch (RemoteException e) {
            }
        }
    }

    public void hideBiometricDialog() {
        enforceBiometricDialog();
        if (this.mBar != null) {
            try {
                this.mBar.hideBiometricDialog();
            } catch (RemoteException e) {
            }
        }
    }

    public void disable(int what, IBinder token, String pkg) {
        disableForUser(what, token, pkg, this.mCurrentUserId);
    }

    public void disableForUser(int what, IBinder token, String pkg, int userId) {
        enforceStatusBar();
        synchronized (this.mLock) {
            disableLocked(0, userId, what, token, pkg, 1);
        }
    }

    public void disable2(int what, IBinder token, String pkg) {
        disable2ForUser(what, token, pkg, this.mCurrentUserId);
    }

    public void disable2ForUser(int what, IBinder token, String pkg, int userId) {
        enforceStatusBar();
        synchronized (this.mLock) {
            disableLocked(0, userId, what, token, pkg, 2);
        }
    }

    private void disableLocked(int displayId, int userId, int what, IBinder token, String pkg, int whichFlag) {
        manageDisableListLocked(userId, what, token, pkg, whichFlag);
        final int net1 = gatherDisableActionsLocked(this.mCurrentUserId, 1);
        int net2 = gatherDisableActionsLocked(this.mCurrentUserId, 2);
        UiState state = getUiState(displayId);
        if (state.disableEquals(net1, net2)) {
            return;
        }
        state.setDisabled(net1, net2);
        this.mHandler.post(new Runnable() { // from class: com.android.server.statusbar.-$$Lambda$StatusBarManagerService$yr21OX4Hyd_XfExwnVnVIn3Jfe4
            @Override // java.lang.Runnable
            public final void run() {
                StatusBarManagerService.this.lambda$disableLocked$0$StatusBarManagerService(net1);
            }
        });
        if (this.mBar != null) {
            try {
                this.mBar.disable(displayId, net1, net2);
            } catch (RemoteException e) {
            }
        }
    }

    public /* synthetic */ void lambda$disableLocked$0$StatusBarManagerService(int net1) {
        this.mNotificationDelegate.onSetDisabled(net1);
    }

    public int[] getDisableFlags(IBinder token, int userId) {
        enforceStatusBar();
        int disable1 = 0;
        int disable2 = 0;
        synchronized (this.mLock) {
            DisableRecord record = (DisableRecord) findMatchingRecordLocked(token, userId).second;
            if (record != null) {
                disable1 = record.what1;
                disable2 = record.what2;
            }
        }
        return new int[]{disable1, disable2};
    }

    public void setIcon(String slot, String iconPackage, int iconId, int iconLevel, String contentDescription) {
        enforceStatusBar();
        synchronized (this.mIcons) {
            StatusBarIcon icon = new StatusBarIcon(iconPackage, UserHandle.SYSTEM, iconId, iconLevel, 0, contentDescription);
            this.mIcons.put(slot, icon);
            if (this.mBar != null) {
                try {
                    this.mBar.setIcon(slot, icon);
                } catch (RemoteException e) {
                }
            }
        }
    }

    public void setIconVisibility(String slot, boolean visibility) {
        enforceStatusBar();
        synchronized (this.mIcons) {
            StatusBarIcon icon = this.mIcons.get(slot);
            if (icon == null) {
                return;
            }
            if (icon.visible != visibility) {
                icon.visible = visibility;
                if (this.mBar != null) {
                    try {
                        this.mBar.setIcon(slot, icon);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    public void removeIcon(String slot) {
        enforceStatusBar();
        synchronized (this.mIcons) {
            this.mIcons.remove(slot);
            if (this.mBar != null) {
                try {
                    this.mBar.removeIcon(slot);
                } catch (RemoteException e) {
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void topAppWindowChanged(final int displayId, final boolean menuVisible) {
        enforceStatusBar();
        synchronized (this.mLock) {
            getUiState(displayId).setMenuVisible(menuVisible);
            this.mHandler.post(new Runnable() { // from class: com.android.server.statusbar.-$$Lambda$StatusBarManagerService$Ex4WQoiXjzWDsRHD7oXCkXIQBB4
                @Override // java.lang.Runnable
                public final void run() {
                    StatusBarManagerService.this.lambda$topAppWindowChanged$1$StatusBarManagerService(displayId, menuVisible);
                }
            });
        }
    }

    public /* synthetic */ void lambda$topAppWindowChanged$1$StatusBarManagerService(int displayId, boolean menuVisible) {
        if (this.mBar != null) {
            try {
                this.mBar.topAppWindowChanged(displayId, menuVisible);
            } catch (RemoteException e) {
            }
        }
    }

    public void setImeWindowStatus(final int displayId, final IBinder token, final int vis, final int backDisposition, final boolean showImeSwitcher) {
        enforceStatusBar();
        synchronized (this.mLock) {
            getUiState(displayId).setImeWindowState(vis, backDisposition, showImeSwitcher, token);
            this.mHandler.post(new Runnable() { // from class: com.android.server.statusbar.-$$Lambda$StatusBarManagerService$UEYsZUbySBvjdjRhx8OmRQFMSn4
                @Override // java.lang.Runnable
                public final void run() {
                    StatusBarManagerService.this.lambda$setImeWindowStatus$2$StatusBarManagerService(displayId, token, vis, backDisposition, showImeSwitcher);
                }
            });
        }
    }

    public /* synthetic */ void lambda$setImeWindowStatus$2$StatusBarManagerService(int displayId, IBinder token, int vis, int backDisposition, boolean showImeSwitcher) {
        if (this.mBar == null) {
            return;
        }
        try {
            this.mBar.setImeWindowStatus(displayId, token, vis, backDisposition, showImeSwitcher);
        } catch (RemoteException e) {
        }
    }

    public void setSystemUiVisibility(int displayId, int vis, int mask, String cause) {
        UiState state = getUiState(displayId);
        setSystemUiVisibility(displayId, vis, 0, 0, mask, state.mFullscreenStackBounds, state.mDockedStackBounds, state.mNavbarColorManagedByIme, cause);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setSystemUiVisibility(int displayId, int vis, int fullscreenStackVis, int dockedStackVis, int mask, Rect fullscreenBounds, Rect dockedBounds, boolean isNavbarColorManagedByIme, String cause) {
        enforceStatusBarService();
        synchronized (this.mLock) {
            updateUiVisibilityLocked(displayId, vis, fullscreenStackVis, dockedStackVis, mask, fullscreenBounds, dockedBounds, isNavbarColorManagedByIme);
            disableLocked(displayId, this.mCurrentUserId, vis & 67043328, this.mSysUiVisToken, cause, 1);
        }
    }

    private void updateUiVisibilityLocked(final int displayId, final int vis, final int fullscreenStackVis, final int dockedStackVis, final int mask, final Rect fullscreenBounds, final Rect dockedBounds, final boolean isNavbarColorManagedByIme) {
        UiState state = getUiState(displayId);
        if (state.systemUiStateEquals(vis, fullscreenStackVis, dockedStackVis, fullscreenBounds, dockedBounds, isNavbarColorManagedByIme)) {
            return;
        }
        state.setSystemUiState(vis, fullscreenStackVis, dockedStackVis, fullscreenBounds, dockedBounds, isNavbarColorManagedByIme);
        this.mHandler.post(new Runnable() { // from class: com.android.server.statusbar.-$$Lambda$StatusBarManagerService$u5u_W7qW5cMnzk9Qhp_oReST4Dc
            @Override // java.lang.Runnable
            public final void run() {
                StatusBarManagerService.this.lambda$updateUiVisibilityLocked$3$StatusBarManagerService(displayId, vis, fullscreenStackVis, dockedStackVis, mask, fullscreenBounds, dockedBounds, isNavbarColorManagedByIme);
            }
        });
    }

    public /* synthetic */ void lambda$updateUiVisibilityLocked$3$StatusBarManagerService(int displayId, int vis, int fullscreenStackVis, int dockedStackVis, int mask, Rect fullscreenBounds, Rect dockedBounds, boolean isNavbarColorManagedByIme) {
        if (this.mBar != null) {
            try {
                this.mBar.setSystemUiVisibility(displayId, vis, fullscreenStackVis, dockedStackVis, mask, fullscreenBounds, dockedBounds, isNavbarColorManagedByIme);
            } catch (RemoteException e) {
                Log.w(TAG, "Can not get StatusBar!");
            }
        }
    }

    private UiState getUiState(int displayId) {
        UiState state = this.mDisplayUiState.get(displayId);
        if (state == null) {
            UiState state2 = new UiState();
            this.mDisplayUiState.put(displayId, state2);
            return state2;
        }
        return state;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class UiState {
        private int mDisabled1;
        private int mDisabled2;
        private final Rect mDockedStackBounds;
        private int mDockedStackSysUiVisibility;
        private final Rect mFullscreenStackBounds;
        private int mFullscreenStackSysUiVisibility;
        private int mImeBackDisposition;
        private IBinder mImeToken;
        private int mImeWindowVis;
        private boolean mMenuVisible;
        private boolean mNavbarColorManagedByIme;
        private boolean mShowImeSwitcher;
        private int mSystemUiVisibility;

        private UiState() {
            this.mSystemUiVisibility = 0;
            this.mFullscreenStackSysUiVisibility = 0;
            this.mDockedStackSysUiVisibility = 0;
            this.mFullscreenStackBounds = new Rect();
            this.mDockedStackBounds = new Rect();
            this.mMenuVisible = false;
            this.mDisabled1 = 0;
            this.mDisabled2 = 0;
            this.mImeWindowVis = 0;
            this.mImeBackDisposition = 0;
            this.mShowImeSwitcher = false;
            this.mImeToken = null;
            this.mNavbarColorManagedByIme = false;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public int getDisabled1() {
            return this.mDisabled1;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public int getDisabled2() {
            return this.mDisabled2;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void setDisabled(int disabled1, int disabled2) {
            this.mDisabled1 = disabled1;
            this.mDisabled2 = disabled2;
        }

        private boolean isMenuVisible() {
            return this.mMenuVisible;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void setMenuVisible(boolean menuVisible) {
            this.mMenuVisible = menuVisible;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean disableEquals(int disabled1, int disabled2) {
            return this.mDisabled1 == disabled1 && this.mDisabled2 == disabled2;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void setSystemUiState(int vis, int fullscreenStackVis, int dockedStackVis, Rect fullscreenBounds, Rect dockedBounds, boolean navbarColorManagedByIme) {
            this.mSystemUiVisibility = vis;
            this.mFullscreenStackSysUiVisibility = fullscreenStackVis;
            this.mDockedStackSysUiVisibility = dockedStackVis;
            this.mFullscreenStackBounds.set(fullscreenBounds);
            this.mDockedStackBounds.set(dockedBounds);
            this.mNavbarColorManagedByIme = navbarColorManagedByIme;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean systemUiStateEquals(int vis, int fullscreenStackVis, int dockedStackVis, Rect fullscreenBounds, Rect dockedBounds, boolean navbarColorManagedByIme) {
            return this.mSystemUiVisibility == vis && this.mFullscreenStackSysUiVisibility == fullscreenStackVis && this.mDockedStackSysUiVisibility == dockedStackVis && this.mFullscreenStackBounds.equals(fullscreenBounds) && this.mDockedStackBounds.equals(dockedBounds) && this.mNavbarColorManagedByIme == navbarColorManagedByIme;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void setImeWindowState(int vis, int backDisposition, boolean showImeSwitcher, IBinder token) {
            this.mImeWindowVis = vis;
            this.mImeBackDisposition = backDisposition;
            this.mShowImeSwitcher = showImeSwitcher;
            this.mImeToken = token;
        }
    }

    private void enforceStatusBarOrShell() {
        if (Binder.getCallingUid() == 2000) {
            return;
        }
        enforceStatusBar();
    }

    private void enforceStatusBar() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.STATUS_BAR", TAG);
    }

    private void enforceExpandStatusBar() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.EXPAND_STATUS_BAR", TAG);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceStatusBarService() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.STATUS_BAR_SERVICE", TAG);
    }

    private void enforceBiometricDialog() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_BIOMETRIC_DIALOG", TAG);
    }

    public RegisterStatusBarResult registerStatusBar(IStatusBar bar) {
        ArrayMap<String, StatusBarIcon> icons;
        RegisterStatusBarResult registerStatusBarResult;
        enforceStatusBarService();
        Slog.i(TAG, "registerStatusBar bar=" + bar);
        this.mBar = bar;
        this.mDeathRecipient.linkToDeath();
        notifyBarAttachChanged();
        synchronized (this.mIcons) {
            icons = new ArrayMap<>(this.mIcons);
        }
        synchronized (this.mLock) {
            UiState state = this.mDisplayUiState.get(0);
            registerStatusBarResult = new RegisterStatusBarResult(icons, gatherDisableActionsLocked(this.mCurrentUserId, 1), state.mSystemUiVisibility, state.mMenuVisible, state.mImeWindowVis, state.mImeBackDisposition, state.mShowImeSwitcher, gatherDisableActionsLocked(this.mCurrentUserId, 2), state.mFullscreenStackSysUiVisibility, state.mDockedStackSysUiVisibility, state.mImeToken, state.mFullscreenStackBounds, state.mDockedStackBounds, state.mNavbarColorManagedByIme);
        }
        return registerStatusBarResult;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyBarAttachChanged() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.statusbar.-$$Lambda$StatusBarManagerService$UR67Ud0NgV9VHyelUmYzZNtR6O4
            @Override // java.lang.Runnable
            public final void run() {
                StatusBarManagerService.this.lambda$notifyBarAttachChanged$4$StatusBarManagerService();
            }
        });
    }

    public /* synthetic */ void lambda$notifyBarAttachChanged$4$StatusBarManagerService() {
        GlobalActionsProvider.GlobalActionsListener globalActionsListener = this.mGlobalActionListener;
        if (globalActionsListener == null) {
            return;
        }
        globalActionsListener.onGlobalActionsAvailableChanged(this.mBar != null);
    }

    public void onPanelRevealed(boolean clearNotificationEffects, int numItems) {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onPanelRevealed(clearNotificationEffects, numItems);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void clearNotificationEffects() throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.clearEffects();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onPanelHidden() throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onPanelHidden();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void shutdown() {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mHandler.post(new Runnable() { // from class: com.android.server.statusbar.-$$Lambda$StatusBarManagerService$UDezjj1c1F0KKrp-AAYUhMa21kk
                @Override // java.lang.Runnable
                public final void run() {
                    ShutdownThread.shutdown(StatusBarManagerService.getUiContext(), "userrequested", false);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void reboot(final boolean safeMode) {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mHandler.post(new Runnable() { // from class: com.android.server.statusbar.-$$Lambda$StatusBarManagerService$xttpOmITnH77D9l5GEy2ZzWEGAg
                @Override // java.lang.Runnable
                public final void run() {
                    StatusBarManagerService.lambda$reboot$6(safeMode);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$reboot$6(boolean safeMode) {
        if (safeMode) {
            ShutdownThread.rebootSafeMode(getUiContext(), true);
        } else {
            ShutdownThread.reboot(getUiContext(), "userrequested", false);
        }
    }

    public void onGlobalActionsShown() {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            if (this.mGlobalActionListener == null) {
                return;
            }
            this.mGlobalActionListener.onGlobalActionsShown();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onGlobalActionsHidden() {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            if (this.mGlobalActionListener == null) {
                return;
            }
            this.mGlobalActionListener.onGlobalActionsDismissed();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationClick(String key, NotificationVisibility nv) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationClick(callingUid, callingPid, key, nv);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationActionClick(String key, int actionIndex, Notification.Action action, NotificationVisibility nv, boolean generatedByAssistant) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationActionClick(callingUid, callingPid, key, actionIndex, action, nv, generatedByAssistant);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationError(String pkg, String tag, int id, int uid, int initialPid, String message, int userId) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationError(callingUid, callingPid, pkg, tag, id, uid, initialPid, message, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationClear(String pkg, String tag, int id, int userId, String key, int dismissalSurface, int dismissalSentiment, NotificationVisibility nv) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationClear(callingUid, callingPid, pkg, tag, id, userId, key, dismissalSurface, dismissalSentiment, nv);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationVisibilityChanged(NotificationVisibility[] newlyVisibleKeys, NotificationVisibility[] noLongerVisibleKeys) throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationVisibilityChanged(newlyVisibleKeys, noLongerVisibleKeys);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationExpansionChanged(String key, boolean userAction, boolean expanded, int location) throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationExpansionChanged(key, userAction, expanded, location);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationDirectReplied(String key) throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationDirectReplied(key);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationSmartSuggestionsAdded(String key, int smartReplyCount, int smartActionCount, boolean generatedByAssistant, boolean editBeforeSending) {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationSmartSuggestionsAdded(key, smartReplyCount, smartActionCount, generatedByAssistant, editBeforeSending);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationSmartReplySent(String key, int replyIndex, CharSequence reply, int notificationLocation, boolean modifiedBeforeSending) throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationSmartReplySent(key, replyIndex, reply, notificationLocation, modifiedBeforeSending);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationSettingsViewed(String key) throws RemoteException {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationSettingsViewed(key);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onClearAllNotifications(int userId) {
        enforceStatusBarService();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onClearAll(callingUid, callingPid, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onNotificationBubbleChanged(String key, boolean isBubble) {
        enforceStatusBarService();
        long identity = Binder.clearCallingIdentity();
        try {
            this.mNotificationDelegate.onNotificationBubbleChanged(key, isBubble);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new StatusBarShellCommand(this, this.mContext).exec(this, in, out, err, args, callback, resultReceiver);
    }

    public String[] getStatusBarIcons() {
        return this.mContext.getResources().getStringArray(17236073);
    }

    void manageDisableListLocked(int userId, int what, IBinder token, String pkg, int which) {
        Pair<Integer, DisableRecord> match = findMatchingRecordLocked(token, userId);
        int i = ((Integer) match.first).intValue();
        DisableRecord record = (DisableRecord) match.second;
        if (!token.isBinderAlive()) {
            if (record != null) {
                this.mDisableRecords.remove(i);
                record.token.unlinkToDeath(record, 0);
            }
        } else if (record != null) {
            record.setFlags(what, which, pkg);
            if (record.isEmpty()) {
                this.mDisableRecords.remove(i);
                record.token.unlinkToDeath(record, 0);
            }
        } else {
            DisableRecord record2 = new DisableRecord(userId, token);
            record2.setFlags(what, which, pkg);
            this.mDisableRecords.add(record2);
        }
    }

    @GuardedBy({"mLock"})
    private Pair<Integer, DisableRecord> findMatchingRecordLocked(IBinder token, int userId) {
        int numRecords = this.mDisableRecords.size();
        DisableRecord record = null;
        int i = 0;
        while (true) {
            if (i >= numRecords) {
                break;
            }
            DisableRecord r = this.mDisableRecords.get(i);
            if (r.token != token || r.userId != userId) {
                i++;
            } else {
                record = r;
                break;
            }
        }
        return new Pair<>(Integer.valueOf(i), record);
    }

    int gatherDisableActionsLocked(int userId, int which) {
        int N = this.mDisableRecords.size();
        int net = 0;
        for (int i = 0; i < N; i++) {
            DisableRecord rec = this.mDisableRecords.get(i);
            if (rec.userId == userId) {
                net |= rec.getFlags(which);
            }
        }
        return net;
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            synchronized (this.mLock) {
                for (int i = 0; i < this.mDisplayUiState.size(); i++) {
                    int key = this.mDisplayUiState.keyAt(i);
                    UiState state = this.mDisplayUiState.get(key);
                    pw.println("  displayId=" + key);
                    pw.println("    mDisabled1=0x" + Integer.toHexString(state.getDisabled1()));
                    pw.println("    mDisabled2=0x" + Integer.toHexString(state.getDisabled2()));
                }
                int N = this.mDisableRecords.size();
                pw.println("  mDisableRecords.size=" + N);
                for (int i2 = 0; i2 < N; i2++) {
                    DisableRecord tok = this.mDisableRecords.get(i2);
                    pw.println("    [" + i2 + "] " + tok);
                }
                pw.println("  mCurrentUserId=" + this.mCurrentUserId);
                pw.println("  mIcons=");
                for (String slot : this.mIcons.keySet()) {
                    pw.println("    ");
                    pw.print(slot);
                    pw.print(" -> ");
                    StatusBarIcon icon = this.mIcons.get(slot);
                    pw.print(icon);
                    if (!TextUtils.isEmpty(icon.contentDescription)) {
                        pw.print(" \"");
                        pw.print(icon.contentDescription);
                        pw.print("\"");
                    }
                    pw.println();
                }
            }
        }
    }

    private static final Context getUiContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }
}
