package com.android.server.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import com.android.internal.util.DumpUtils;
import com.android.server.accessibility.AbstractAccessibilityServiceConnection;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.UiAutomationManager;
import com.android.server.wm.WindowManagerInternal;
import java.io.FileDescriptor;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class UiAutomationManager {
    private static final ComponentName COMPONENT_NAME = new ComponentName("com.android.server.accessibility", "UiAutomation");
    private static final String LOG_TAG = "UiAutomationManager";
    private AbstractAccessibilityServiceConnection.SystemSupport mSystemSupport;
    private int mUiAutomationFlags;
    private UiAutomationService mUiAutomationService;
    private AccessibilityServiceInfo mUiAutomationServiceInfo;
    private IBinder mUiAutomationServiceOwner;
    private final Object mServiceLock = new Object();
    private final IBinder.DeathRecipient mUiAutomationServiceOwnerDeathRecipient = new IBinder.DeathRecipient() { // from class: com.android.server.accessibility.UiAutomationManager.1
        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            UiAutomationManager.this.mUiAutomationServiceOwner.unlinkToDeath(this, 0);
            UiAutomationManager.this.mUiAutomationServiceOwner = null;
            if (UiAutomationManager.this.mUiAutomationService != null) {
                UiAutomationManager.this.destroyUiAutomationService();
            }
        }
    };

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerUiTestAutomationServiceLocked(IBinder owner, IAccessibilityServiceClient serviceClient, Context context, AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler, Object lock, AccessibilityManagerService.SecurityPolicy securityPolicy, AbstractAccessibilityServiceConnection.SystemSupport systemSupport, WindowManagerInternal windowManagerInternal, GlobalActionPerformer globalActionPerfomer, int flags) {
        accessibilityServiceInfo.setComponentName(COMPONENT_NAME);
        if (this.mUiAutomationService != null) {
            throw new IllegalStateException("UiAutomationService " + serviceClient + "already registered!");
        }
        try {
            owner.linkToDeath(this.mUiAutomationServiceOwnerDeathRecipient, 0);
            this.mSystemSupport = systemSupport;
            this.mUiAutomationService = new UiAutomationService(context, accessibilityServiceInfo, id, mainHandler, lock, securityPolicy, systemSupport, windowManagerInternal, globalActionPerfomer);
            this.mUiAutomationServiceOwner = owner;
            this.mUiAutomationFlags = flags;
            this.mUiAutomationServiceInfo = accessibilityServiceInfo;
            this.mUiAutomationService.mServiceInterface = serviceClient;
            this.mUiAutomationService.onAdded();
            try {
                this.mUiAutomationService.mServiceInterface.asBinder().linkToDeath(this.mUiAutomationService, 0);
                this.mUiAutomationService.connectServiceUnknownThread();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed registering death link: " + re);
                destroyUiAutomationService();
            }
        } catch (RemoteException re2) {
            Slog.e(LOG_TAG, "Couldn't register for the death of a UiTestAutomationService!", re2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterUiTestAutomationServiceLocked(IAccessibilityServiceClient serviceClient) {
        if (this.mUiAutomationService == null || serviceClient == null || this.mUiAutomationService.mServiceInterface == null || serviceClient.asBinder() != this.mUiAutomationService.mServiceInterface.asBinder()) {
            throw new IllegalStateException("UiAutomationService " + serviceClient + " not registered!");
        }
        destroyUiAutomationService();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendAccessibilityEventLocked(AccessibilityEvent event) {
        if (this.mUiAutomationService != null) {
            this.mUiAutomationService.notifyAccessibilityEvent(event);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isUiAutomationRunningLocked() {
        return this.mUiAutomationService != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean suppressingAccessibilityServicesLocked() {
        return this.mUiAutomationService != null && (this.mUiAutomationFlags & 1) == 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isTouchExplorationEnabledLocked() {
        return this.mUiAutomationService != null && this.mUiAutomationService.mRequestTouchExplorationMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canRetrieveInteractiveWindowsLocked() {
        return this.mUiAutomationService != null && this.mUiAutomationService.mRetrieveInteractiveWindows;
    }

    int getRequestedEventMaskLocked() {
        if (this.mUiAutomationService == null) {
            return 0;
        }
        return this.mUiAutomationService.mEventTypes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRelevantEventTypes() {
        if (this.mUiAutomationService == null) {
            return 0;
        }
        return this.mUiAutomationService.getRelevantEventTypes();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AccessibilityServiceInfo getServiceInfo() {
        if (this.mUiAutomationService == null) {
            return null;
        }
        return this.mUiAutomationService.getServiceInfo();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpUiAutomationService(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (this.mUiAutomationService != null) {
            this.mUiAutomationService.dump(fd, pw, args);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroyUiAutomationService() {
        synchronized (this.mServiceLock) {
            if (this.mUiAutomationService == null) {
                return;
            }
            this.mUiAutomationService.mServiceInterface.asBinder().unlinkToDeath(this.mUiAutomationService, 0);
            this.mUiAutomationService.onRemoved();
            this.mUiAutomationService.resetLocked();
            this.mUiAutomationService = null;
            this.mUiAutomationFlags = 0;
            if (this.mUiAutomationServiceOwner != null) {
                this.mUiAutomationServiceOwner.unlinkToDeath(this.mUiAutomationServiceOwnerDeathRecipient, 0);
                this.mUiAutomationServiceOwner = null;
            }
            this.mSystemSupport.onClientChange(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UiAutomationService extends AbstractAccessibilityServiceConnection {
        private final Handler mMainHandler;

        UiAutomationService(Context context, AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler, Object lock, AccessibilityManagerService.SecurityPolicy securityPolicy, AbstractAccessibilityServiceConnection.SystemSupport systemSupport, WindowManagerInternal windowManagerInternal, GlobalActionPerformer globalActionPerfomer) {
            super(context, UiAutomationManager.COMPONENT_NAME, accessibilityServiceInfo, id, mainHandler, lock, securityPolicy, systemSupport, windowManagerInternal, globalActionPerfomer);
            this.mMainHandler = mainHandler;
        }

        void connectServiceUnknownThread() {
            this.mMainHandler.post(new Runnable() { // from class: com.android.server.accessibility.-$$Lambda$UiAutomationManager$UiAutomationService$z2oxrodQt4ZxyzsfB6p_GYgwxqk
                @Override // java.lang.Runnable
                public final void run() {
                    UiAutomationManager.UiAutomationService.lambda$connectServiceUnknownThread$0(UiAutomationManager.UiAutomationService.this);
                }
            });
        }

        public static /* synthetic */ void lambda$connectServiceUnknownThread$0(UiAutomationService uiAutomationService) {
            IAccessibilityServiceClient serviceInterface;
            IBinder service;
            try {
                synchronized (uiAutomationService.mLock) {
                    serviceInterface = uiAutomationService.mServiceInterface;
                    uiAutomationService.mService = serviceInterface == null ? null : uiAutomationService.mServiceInterface.asBinder();
                    service = uiAutomationService.mService;
                }
                if (serviceInterface != null) {
                    service.linkToDeath(uiAutomationService, 0);
                    serviceInterface.init(uiAutomationService, uiAutomationService.mId, uiAutomationService.mOverlayWindowToken);
                }
            } catch (RemoteException re) {
                Slog.w(UiAutomationManager.LOG_TAG, "Error initialized connection", re);
                UiAutomationManager.this.destroyUiAutomationService();
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            UiAutomationManager.this.destroyUiAutomationService();
        }

        @Override // com.android.server.accessibility.AbstractAccessibilityServiceConnection
        protected boolean isCalledForCurrentUserLocked() {
            return true;
        }

        @Override // com.android.server.accessibility.AbstractAccessibilityServiceConnection
        protected boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
            return true;
        }

        @Override // com.android.server.accessibility.AbstractAccessibilityServiceConnection
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (DumpUtils.checkDumpPermission(this.mContext, UiAutomationManager.LOG_TAG, pw)) {
                synchronized (this.mLock) {
                    pw.append((CharSequence) ("Ui Automation[eventTypes=" + AccessibilityEvent.eventTypeToString(this.mEventTypes)));
                    pw.append((CharSequence) (", notificationTimeout=" + this.mNotificationTimeout));
                    pw.append("]");
                }
            }
        }

        public boolean setSoftKeyboardShowMode(int mode) {
            return false;
        }

        public boolean isAccessibilityButtonAvailable() {
            return false;
        }

        public void disableSelf() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName componentName, IBinder service) {
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName componentName) {
        }

        @Override // com.android.server.accessibility.FingerprintGestureDispatcher.FingerprintGestureClient
        public boolean isCapturingFingerprintGestures() {
            return false;
        }

        @Override // com.android.server.accessibility.FingerprintGestureDispatcher.FingerprintGestureClient
        public void onFingerprintGestureDetectionActiveChanged(boolean active) {
        }

        @Override // com.android.server.accessibility.FingerprintGestureDispatcher.FingerprintGestureClient
        public void onFingerprintGesture(int gesture) {
        }
    }
}
