package com.android.server.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.accessibility.AbstractAccessibilityServiceConnection;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.pm.DumpState;
import com.android.server.wm.WindowManagerInternal;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.function.Consumer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class AccessibilityServiceConnection extends AbstractAccessibilityServiceConnection {
    private static final String LOG_TAG = "AccessibilityServiceConnection";
    final Intent mIntent;
    private final Handler mMainHandler;
    final WeakReference<AccessibilityManagerService.UserState> mUserStateWeakReference;
    private boolean mWasConnectedAndDied;

    public AccessibilityServiceConnection(AccessibilityManagerService.UserState userState, Context context, ComponentName componentName, AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler, Object lock, AccessibilityManagerService.SecurityPolicy securityPolicy, AbstractAccessibilityServiceConnection.SystemSupport systemSupport, WindowManagerInternal windowManagerInternal, GlobalActionPerformer globalActionPerfomer) {
        super(context, componentName, accessibilityServiceInfo, id, mainHandler, lock, securityPolicy, systemSupport, windowManagerInternal, globalActionPerfomer);
        this.mUserStateWeakReference = new WeakReference<>(userState);
        this.mIntent = new Intent().setComponent(this.mComponentName);
        this.mMainHandler = mainHandler;
        this.mIntent.putExtra("android.intent.extra.client_label", 17039421);
        long identity = Binder.clearCallingIdentity();
        try {
            this.mIntent.putExtra("android.intent.extra.client_intent", this.mSystemSupport.getPendingIntentActivity(this.mContext, 0, new Intent("android.settings.ACCESSIBILITY_SETTINGS"), 0));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void bindLocked() {
        AccessibilityManagerService.UserState userState = this.mUserStateWeakReference.get();
        if (userState == null) {
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            int flags = userState.mBindInstantServiceAllowed ? 33554433 | DumpState.DUMP_CHANGES : 33554433;
            if (this.mService == null && this.mContext.bindServiceAsUser(this.mIntent, this, flags, new UserHandle(userState.mUserId))) {
                userState.getBindingServicesLocked().add(this.mComponentName);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void unbindLocked() {
        this.mContext.unbindService(this);
        AccessibilityManagerService.UserState userState = this.mUserStateWeakReference.get();
        if (userState == null) {
            return;
        }
        userState.removeServiceLocked(this);
        resetLocked();
    }

    public boolean canRetrieveInteractiveWindowsLocked() {
        return this.mSecurityPolicy.canRetrieveWindowContentLocked(this) && this.mRetrieveInteractiveWindows;
    }

    public void disableSelf() {
        synchronized (this.mLock) {
            AccessibilityManagerService.UserState userState = this.mUserStateWeakReference.get();
            if (userState == null) {
                return;
            }
            if (userState.mEnabledServices.remove(this.mComponentName)) {
                long identity = Binder.clearCallingIdentity();
                this.mSystemSupport.persistComponentNamesToSettingLocked("enabled_accessibility_services", userState.mEnabledServices, userState.mUserId);
                Binder.restoreCallingIdentity(identity);
                this.mSystemSupport.onClientChange(false);
            }
        }
    }

    @Override // android.content.ServiceConnection
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        synchronized (this.mLock) {
            if (this.mService != service) {
                if (this.mService != null) {
                    this.mService.unlinkToDeath(this, 0);
                }
                this.mService = service;
                try {
                    this.mService.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Failed registering death link");
                    binderDied();
                    return;
                }
            }
            this.mServiceInterface = IAccessibilityServiceClient.Stub.asInterface(service);
            AccessibilityManagerService.UserState userState = this.mUserStateWeakReference.get();
            if (userState == null) {
                return;
            }
            userState.addServiceLocked(this);
            this.mSystemSupport.onClientChange(false);
            this.mMainHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.accessibility.-$$Lambda$AccessibilityServiceConnection$ASP9bmSvpeD7ZE_uJ8sm-9hCwiU
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AccessibilityServiceConnection) obj).initializeService();
                }
            }, this));
        }
    }

    @Override // com.android.server.accessibility.AbstractAccessibilityServiceConnection
    public AccessibilityServiceInfo getServiceInfo() {
        this.mAccessibilityServiceInfo.crashed = this.mWasConnectedAndDied;
        return this.mAccessibilityServiceInfo;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initializeService() {
        IAccessibilityServiceClient serviceInterface = null;
        synchronized (this.mLock) {
            AccessibilityManagerService.UserState userState = this.mUserStateWeakReference.get();
            if (userState == null) {
                return;
            }
            Set<ComponentName> bindingServices = userState.getBindingServicesLocked();
            if (bindingServices.contains(this.mComponentName) || this.mWasConnectedAndDied) {
                bindingServices.remove(this.mComponentName);
                this.mWasConnectedAndDied = false;
                serviceInterface = this.mServiceInterface;
            }
            if (serviceInterface == null) {
                binderDied();
                return;
            }
            try {
                serviceInterface.init(this, this.mId, this.mOverlayWindowToken);
            } catch (RemoteException re) {
                Slog.w(LOG_TAG, "Error while setting connection for service: " + serviceInterface, re);
                binderDied();
            }
        }
    }

    @Override // android.content.ServiceConnection
    public void onServiceDisconnected(ComponentName componentName) {
        binderDied();
    }

    @Override // com.android.server.accessibility.AbstractAccessibilityServiceConnection
    protected boolean isCalledForCurrentUserLocked() {
        int resolvedUserId = this.mSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(-2);
        return resolvedUserId == this.mSystemSupport.getCurrentUserIdLocked();
    }

    public boolean setSoftKeyboardShowMode(int showMode) {
        ComponentName componentName;
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                AccessibilityManagerService.UserState userState = this.mUserStateWeakReference.get();
                if (userState == null) {
                    return false;
                }
                long identity = Binder.clearCallingIdentity();
                if (showMode == 0) {
                    componentName = null;
                } else {
                    try {
                        componentName = this.mComponentName;
                    } catch (Throwable th) {
                        Binder.restoreCallingIdentity(identity);
                        throw th;
                    }
                }
                userState.mServiceChangingSoftKeyboardMode = componentName;
                Settings.Secure.putIntForUser(this.mContext.getContentResolver(), "accessibility_soft_keyboard_mode", showMode, userState.mUserId);
                Binder.restoreCallingIdentity(identity);
                return true;
            }
            return false;
        }
    }

    public boolean isAccessibilityButtonAvailable() {
        synchronized (this.mLock) {
            boolean z = false;
            if (isCalledForCurrentUserLocked()) {
                AccessibilityManagerService.UserState userState = this.mUserStateWeakReference.get();
                if (userState != null && isAccessibilityButtonAvailableLocked(userState)) {
                    z = true;
                }
                return z;
            }
            return false;
        }
    }

    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        synchronized (this.mLock) {
            if (isConnectedLocked()) {
                this.mWasConnectedAndDied = true;
                resetLocked();
                if (this.mId == this.mSystemSupport.getMagnificationController().getIdOfLastServiceToMagnify()) {
                    this.mSystemSupport.getMagnificationController().resetIfNeeded(true);
                }
                this.mSystemSupport.onClientChange(false);
            }
        }
    }

    public boolean isAccessibilityButtonAvailableLocked(AccessibilityManagerService.UserState userState) {
        if (this.mRequestAccessibilityButton && this.mSystemSupport.isAccessibilityButtonShown()) {
            if (userState.mIsNavBarMagnificationEnabled && userState.mIsNavBarMagnificationAssignedToAccessibilityButton) {
                return false;
            }
            int requestingServices = 0;
            for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
                AccessibilityServiceConnection service = userState.mBoundServices.get(i);
                if (service.mRequestAccessibilityButton) {
                    requestingServices++;
                }
            }
            if (requestingServices == 1 || userState.mServiceAssignedToAccessibilityButton == null) {
                return true;
            }
            return this.mComponentName.equals(userState.mServiceAssignedToAccessibilityButton);
        }
        return false;
    }

    @Override // com.android.server.accessibility.FingerprintGestureDispatcher.FingerprintGestureClient
    public boolean isCapturingFingerprintGestures() {
        return this.mServiceInterface != null && this.mSecurityPolicy.canCaptureFingerprintGestures(this) && this.mCaptureFingerprintGestures;
    }

    @Override // com.android.server.accessibility.FingerprintGestureDispatcher.FingerprintGestureClient
    public void onFingerprintGestureDetectionActiveChanged(boolean active) {
        IAccessibilityServiceClient serviceInterface;
        if (!isCapturingFingerprintGestures()) {
            return;
        }
        synchronized (this.mLock) {
            serviceInterface = this.mServiceInterface;
        }
        if (serviceInterface != null) {
            try {
                this.mServiceInterface.onFingerprintCapturingGesturesChanged(active);
            } catch (RemoteException e) {
            }
        }
    }

    @Override // com.android.server.accessibility.FingerprintGestureDispatcher.FingerprintGestureClient
    public void onFingerprintGesture(int gesture) {
        IAccessibilityServiceClient serviceInterface;
        if (!isCapturingFingerprintGestures()) {
            return;
        }
        synchronized (this.mLock) {
            serviceInterface = this.mServiceInterface;
        }
        if (serviceInterface != null) {
            try {
                this.mServiceInterface.onFingerprintGesture(gesture);
            } catch (RemoteException e) {
            }
        }
    }

    @Override // com.android.server.accessibility.AbstractAccessibilityServiceConnection
    public void sendGesture(int sequence, ParceledListSlice gestureSteps) {
        synchronized (this.mLock) {
            if (this.mSecurityPolicy.canPerformGestures(this)) {
                MotionEventInjector motionEventInjector = this.mSystemSupport.getMotionEventInjectorLocked();
                if (motionEventInjector != null) {
                    motionEventInjector.injectEvents(gestureSteps.getList(), this.mServiceInterface, sequence);
                } else {
                    try {
                        this.mServiceInterface.onPerformGestureResult(sequence, false);
                    } catch (RemoteException re) {
                        Slog.e(LOG_TAG, "Error sending motion event injection failure to " + this.mServiceInterface, re);
                    }
                }
            }
        }
    }
}
