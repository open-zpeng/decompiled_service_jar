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
    private final Object mLock;
    private AbstractAccessibilityServiceConnection.SystemSupport mSystemSupport;
    private int mUiAutomationFlags;
    private UiAutomationService mUiAutomationService;
    private AccessibilityServiceInfo mUiAutomationServiceInfo;
    private IBinder mUiAutomationServiceOwner;
    private final IBinder.DeathRecipient mUiAutomationServiceOwnerDeathRecipient = new IBinder.DeathRecipient() { // from class: com.android.server.accessibility.UiAutomationManager.1
        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            UiAutomationManager.this.mUiAutomationServiceOwner.unlinkToDeath(this, 0);
            UiAutomationManager.this.mUiAutomationServiceOwner = null;
            UiAutomationManager.this.destroyUiAutomationService();
        }
    };

    /* JADX INFO: Access modifiers changed from: package-private */
    public UiAutomationManager(Object lock) {
        this.mLock = lock;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerUiTestAutomationServiceLocked(IBinder owner, IAccessibilityServiceClient serviceClient, Context context, AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler, AccessibilityManagerService.SecurityPolicy securityPolicy, AbstractAccessibilityServiceConnection.SystemSupport systemSupport, WindowManagerInternal windowManagerInternal, GlobalActionPerformer globalActionPerfomer, int flags) {
        Object obj;
        Object obj2 = this.mLock;
        synchronized (obj2) {
            try {
                try {
                    accessibilityServiceInfo.setComponentName(COMPONENT_NAME);
                    if (this.mUiAutomationService != null) {
                        throw new IllegalStateException("UiAutomationService " + serviceClient + "already registered!");
                    }
                    try {
                        owner.linkToDeath(this.mUiAutomationServiceOwnerDeathRecipient, 0);
                        this.mSystemSupport = systemSupport;
                        obj = obj2;
                        try {
                            this.mUiAutomationService = new UiAutomationService(context, accessibilityServiceInfo, id, mainHandler, this.mLock, securityPolicy, systemSupport, windowManagerInternal, globalActionPerfomer);
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
                        } catch (Throwable th) {
                            re = th;
                            throw re;
                        }
                    } catch (RemoteException re2) {
                        Slog.e(LOG_TAG, "Couldn't register for the death of a UiTestAutomationService!", re2);
                    }
                } catch (Throwable th2) {
                    re = th2;
                    obj = obj2;
                }
            } catch (Throwable th3) {
                re = th3;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterUiTestAutomationServiceLocked(IAccessibilityServiceClient serviceClient) {
        synchronized (this.mLock) {
            if (this.mUiAutomationService == null || serviceClient == null || this.mUiAutomationService.mServiceInterface == null || serviceClient.asBinder() != this.mUiAutomationService.mServiceInterface.asBinder()) {
                throw new IllegalStateException("UiAutomationService " + serviceClient + " not registered!");
            }
            destroyUiAutomationService();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendAccessibilityEventLocked(AccessibilityEvent event) {
        UiAutomationService uiAutomationService = this.mUiAutomationService;
        if (uiAutomationService != null) {
            uiAutomationService.notifyAccessibilityEvent(event);
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
        UiAutomationService uiAutomationService = this.mUiAutomationService;
        return uiAutomationService != null && uiAutomationService.mRequestTouchExplorationMode;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean canRetrieveInteractiveWindowsLocked() {
        UiAutomationService uiAutomationService = this.mUiAutomationService;
        return uiAutomationService != null && uiAutomationService.mRetrieveInteractiveWindows;
    }

    int getRequestedEventMaskLocked() {
        UiAutomationService uiAutomationService = this.mUiAutomationService;
        if (uiAutomationService == null) {
            return 0;
        }
        return uiAutomationService.mEventTypes;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRelevantEventTypes() {
        UiAutomationService uiAutomationService;
        synchronized (this.mLock) {
            uiAutomationService = this.mUiAutomationService;
        }
        if (uiAutomationService == null) {
            return 0;
        }
        return uiAutomationService.getRelevantEventTypes();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AccessibilityServiceInfo getServiceInfo() {
        UiAutomationService uiAutomationService;
        synchronized (this.mLock) {
            uiAutomationService = this.mUiAutomationService;
        }
        if (uiAutomationService == null) {
            return null;
        }
        return uiAutomationService.getServiceInfo();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpUiAutomationService(FileDescriptor fd, PrintWriter pw, String[] args) {
        UiAutomationService uiAutomationService;
        synchronized (this.mLock) {
            uiAutomationService = this.mUiAutomationService;
        }
        if (uiAutomationService != null) {
            uiAutomationService.dump(fd, pw, args);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroyUiAutomationService() {
        synchronized (this.mLock) {
            if (this.mUiAutomationService != null) {
                this.mUiAutomationService.mServiceInterface.asBinder().unlinkToDeath(this.mUiAutomationService, 0);
                this.mUiAutomationService.onRemoved();
                this.mUiAutomationService.resetLocked();
                this.mUiAutomationService = null;
                this.mUiAutomationFlags = 0;
                if (this.mUiAutomationServiceOwner != null) {
                    this.mUiAutomationServiceOwner.unlinkToDeath(this.mUiAutomationServiceOwnerDeathRecipient, 0);
                    this.mUiAutomationServiceOwner = null;
                }
                this.mSystemSupport.onClientChangeLocked(false);
            }
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
                    UiAutomationManager.UiAutomationService.this.lambda$connectServiceUnknownThread$0$UiAutomationManager$UiAutomationService();
                }
            });
        }

        public /* synthetic */ void lambda$connectServiceUnknownThread$0$UiAutomationManager$UiAutomationService() {
            IAccessibilityServiceClient serviceInterface;
            IBinder service;
            try {
                synchronized (this.mLock) {
                    serviceInterface = this.mServiceInterface;
                    this.mService = serviceInterface == null ? null : this.mServiceInterface.asBinder();
                    service = this.mService;
                }
                if (serviceInterface != null) {
                    service.linkToDeath(this, 0);
                    serviceInterface.init(this, this.mId, this.mOverlayWindowToken);
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

        public int getSoftKeyboardShowMode() {
            return 0;
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
