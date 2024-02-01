package com.android.server.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ParceledListSlice;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.DumpUtils;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.FingerprintGestureDispatcher;
import com.android.server.accessibility.KeyEventDispatcher;
import com.android.server.wm.WindowManagerInternal;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public abstract class AbstractAccessibilityServiceConnection extends IAccessibilityServiceConnection.Stub implements ServiceConnection, IBinder.DeathRecipient, KeyEventDispatcher.KeyEventFilter, FingerprintGestureDispatcher.FingerprintGestureClient {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "AbstractAccessibilityServiceConnection";
    protected final AccessibilityServiceInfo mAccessibilityServiceInfo;
    boolean mCaptureFingerprintGestures;
    final ComponentName mComponentName;
    protected final Context mContext;
    public Handler mEventDispatchHandler;
    int mEventTypes;
    int mFeedbackType;
    int mFetchFlags;
    private final GlobalActionPerformer mGlobalActionPerformer;
    final int mId;
    public final InvocationHandler mInvocationHandler;
    boolean mIsDefault;
    boolean mLastAccessibilityButtonCallbackState;
    protected final Object mLock;
    long mNotificationTimeout;
    boolean mReceivedAccessibilityButtonCallbackSinceBind;
    boolean mRequestAccessibilityButton;
    boolean mRequestFilterKeyEvents;
    boolean mRequestTouchExplorationMode;
    boolean mRetrieveInteractiveWindows;
    protected final AccessibilityManagerService.SecurityPolicy mSecurityPolicy;
    IBinder mService;
    IAccessibilityServiceClient mServiceInterface;
    protected final SystemSupport mSystemSupport;
    private final WindowManagerInternal mWindowManagerService;
    Set<String> mPackageNames = new HashSet();
    final SparseArray<AccessibilityEvent> mPendingEvents = new SparseArray<>();
    boolean mUsesAccessibilityCache = false;
    final IBinder mOverlayWindowToken = new Binder();

    /* loaded from: classes.dex */
    public interface SystemSupport {
        void ensureWindowsAvailableTimed();

        MagnificationSpec getCompatibleMagnificationSpecLocked(int i);

        AccessibilityManagerService.RemoteAccessibilityConnection getConnectionLocked(int i);

        int getCurrentUserIdLocked();

        FingerprintGestureDispatcher getFingerprintGestureDispatcher();

        KeyEventDispatcher getKeyEventDispatcher();

        MagnificationController getMagnificationController();

        MotionEventInjector getMotionEventInjectorLocked();

        PendingIntent getPendingIntentActivity(Context context, int i, Intent intent, int i2);

        boolean isAccessibilityButtonShown();

        void onClientChangeLocked(boolean z);

        boolean performAccessibilityAction(int i, long j, int i2, Bundle bundle, int i3, IAccessibilityInteractionConnectionCallback iAccessibilityInteractionConnectionCallback, int i4, long j2);

        void persistComponentNamesToSettingLocked(String str, Set<ComponentName> set, int i);

        IAccessibilityInteractionConnectionCallback replaceCallbackIfNeeded(IAccessibilityInteractionConnectionCallback iAccessibilityInteractionConnectionCallback, int i, int i2, int i3, long j);
    }

    protected abstract boolean isCalledForCurrentUserLocked();

    public AbstractAccessibilityServiceConnection(Context context, ComponentName componentName, AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler, Object lock, AccessibilityManagerService.SecurityPolicy securityPolicy, SystemSupport systemSupport, WindowManagerInternal windowManagerInternal, GlobalActionPerformer globalActionPerfomer) {
        this.mContext = context;
        this.mWindowManagerService = windowManagerInternal;
        this.mId = id;
        this.mComponentName = componentName;
        this.mAccessibilityServiceInfo = accessibilityServiceInfo;
        this.mLock = lock;
        this.mSecurityPolicy = securityPolicy;
        this.mGlobalActionPerformer = globalActionPerfomer;
        this.mSystemSupport = systemSupport;
        this.mInvocationHandler = new InvocationHandler(mainHandler.getLooper());
        this.mEventDispatchHandler = new Handler(mainHandler.getLooper()) { // from class: com.android.server.accessibility.AbstractAccessibilityServiceConnection.1
            @Override // android.os.Handler
            public void handleMessage(Message message) {
                int eventType = message.what;
                AccessibilityEvent event = (AccessibilityEvent) message.obj;
                boolean serviceWantsEvent = message.arg1 != 0;
                AbstractAccessibilityServiceConnection.this.notifyAccessibilityEventInternal(eventType, event, serviceWantsEvent);
            }
        };
        setDynamicallyConfigurableProperties(accessibilityServiceInfo);
    }

    @Override // com.android.server.accessibility.KeyEventDispatcher.KeyEventFilter
    public boolean onKeyEvent(KeyEvent keyEvent, int sequenceNumber) {
        if (!this.mRequestFilterKeyEvents || this.mServiceInterface == null || (this.mAccessibilityServiceInfo.getCapabilities() & 8) == 0 || !this.mSecurityPolicy.checkAccessibilityAccess(this)) {
            return false;
        }
        try {
            this.mServiceInterface.onKeyEvent(keyEvent, sequenceNumber);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public void setDynamicallyConfigurableProperties(AccessibilityServiceInfo info) {
        this.mEventTypes = info.eventTypes;
        this.mFeedbackType = info.feedbackType;
        String[] packageNames = info.packageNames;
        if (packageNames != null) {
            this.mPackageNames.addAll(Arrays.asList(packageNames));
        }
        this.mNotificationTimeout = info.notificationTimeout;
        this.mIsDefault = (info.flags & 1) != 0;
        if (supportsFlagForNotImportantViews(info)) {
            if ((info.flags & 2) != 0) {
                this.mFetchFlags |= 8;
            } else {
                this.mFetchFlags &= -9;
            }
        }
        if ((info.flags & 16) != 0) {
            this.mFetchFlags |= 16;
        } else {
            this.mFetchFlags &= -17;
        }
        this.mRequestTouchExplorationMode = (info.flags & 4) != 0;
        this.mRequestFilterKeyEvents = (info.flags & 32) != 0;
        this.mRetrieveInteractiveWindows = (info.flags & 64) != 0;
        this.mCaptureFingerprintGestures = (info.flags & 512) != 0;
        this.mRequestAccessibilityButton = (info.flags & 256) != 0;
    }

    protected boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
        return info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion >= 16;
    }

    public boolean canReceiveEventsLocked() {
        return (this.mEventTypes == 0 || this.mFeedbackType == 0 || this.mService == null) ? false : true;
    }

    public void setOnKeyEventResult(boolean handled, int sequence) {
        this.mSystemSupport.getKeyEventDispatcher().setOnKeyEventResult(this, handled, sequence);
    }

    public AccessibilityServiceInfo getServiceInfo() {
        AccessibilityServiceInfo accessibilityServiceInfo;
        synchronized (this.mLock) {
            accessibilityServiceInfo = this.mAccessibilityServiceInfo;
        }
        return accessibilityServiceInfo;
    }

    public int getCapabilities() {
        return this.mAccessibilityServiceInfo.getCapabilities();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getRelevantEventTypes() {
        return (this.mUsesAccessibilityCache ? 4307005 : 32) | this.mEventTypes;
    }

    public void setServiceInfo(AccessibilityServiceInfo info) {
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                AccessibilityServiceInfo oldInfo = this.mAccessibilityServiceInfo;
                if (oldInfo != null) {
                    oldInfo.updateDynamicallyConfigurableProperties(info);
                    setDynamicallyConfigurableProperties(oldInfo);
                } else {
                    setDynamicallyConfigurableProperties(info);
                }
                this.mSystemSupport.onClientChangeLocked(true);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public List<AccessibilityWindowInfo> getWindows() {
        this.mSystemSupport.ensureWindowsAvailableTimed();
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                boolean permissionGranted = this.mSecurityPolicy.canRetrieveWindowsLocked(this);
                if (permissionGranted) {
                    if (this.mSecurityPolicy.mWindows == null) {
                        return null;
                    }
                    if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                        List<AccessibilityWindowInfo> windows = new ArrayList<>();
                        int windowCount = this.mSecurityPolicy.mWindows.size();
                        for (int i = 0; i < windowCount; i++) {
                            AccessibilityWindowInfo window = this.mSecurityPolicy.mWindows.get(i);
                            AccessibilityWindowInfo windowClone = AccessibilityWindowInfo.obtain(window);
                            windowClone.setConnectionId(this.mId);
                            windows.add(windowClone);
                        }
                        return windows;
                    }
                    return null;
                }
                return null;
            }
            return null;
        }
    }

    public AccessibilityWindowInfo getWindow(int windowId) {
        this.mSystemSupport.ensureWindowsAvailableTimed();
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                boolean permissionGranted = this.mSecurityPolicy.canRetrieveWindowsLocked(this);
                if (permissionGranted) {
                    if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                        AccessibilityWindowInfo window = this.mSecurityPolicy.findA11yWindowInfoById(windowId);
                        if (window != null) {
                            AccessibilityWindowInfo windowClone = AccessibilityWindowInfo.obtain(window);
                            windowClone.setConnectionId(this.mId);
                            return windowClone;
                        }
                        return null;
                    }
                    return null;
                }
                return null;
            }
            return null;
        }
    }

    public String[] findAccessibilityNodeInfosByViewId(int accessibilityWindowId, long accessibilityNodeId, String viewIdResName, int interactionId, IAccessibilityInteractionConnectionCallback callback, long interrogatingTid) throws RemoteException {
        Region partialInteractiveRegion = Region.obtain();
        synchronized (this.mLock) {
            this.mUsesAccessibilityCache = true;
            if (isCalledForCurrentUserLocked()) {
                int resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                boolean permissionGranted = this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (permissionGranted) {
                    AccessibilityManagerService.RemoteAccessibilityConnection connection = this.mSystemSupport.getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                    if (!this.mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(resolvedWindowId, partialInteractiveRegion)) {
                        partialInteractiveRegion.recycle();
                        partialInteractiveRegion = null;
                    }
                    MagnificationSpec spec = this.mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
                    if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                        int interrogatingPid = Binder.getCallingPid();
                        IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                        long identityToken = Binder.clearCallingIdentity();
                        try {
                            connection.getRemote().findAccessibilityNodeInfosByViewId(accessibilityNodeId, viewIdResName, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                            String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(connection.getPackageName(), connection.getUid());
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return computeValidReportedPackages;
                        } catch (RemoteException e) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return null;
                        } catch (Throwable th) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            throw th;
                        }
                    }
                    return null;
                }
                return null;
            }
            return null;
        }
    }

    public String[] findAccessibilityNodeInfosByText(int accessibilityWindowId, long accessibilityNodeId, String text, int interactionId, IAccessibilityInteractionConnectionCallback callback, long interrogatingTid) throws RemoteException {
        Region partialInteractiveRegion = Region.obtain();
        synchronized (this.mLock) {
            this.mUsesAccessibilityCache = true;
            if (isCalledForCurrentUserLocked()) {
                int resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                boolean permissionGranted = this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (permissionGranted) {
                    AccessibilityManagerService.RemoteAccessibilityConnection connection = this.mSystemSupport.getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                    if (!this.mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(resolvedWindowId, partialInteractiveRegion)) {
                        partialInteractiveRegion.recycle();
                        partialInteractiveRegion = null;
                    }
                    MagnificationSpec spec = this.mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
                    if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                        int interrogatingPid = Binder.getCallingPid();
                        IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                        long identityToken = Binder.clearCallingIdentity();
                        try {
                            connection.getRemote().findAccessibilityNodeInfosByText(accessibilityNodeId, text, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                            String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(connection.getPackageName(), connection.getUid());
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return computeValidReportedPackages;
                        } catch (RemoteException e) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return null;
                        } catch (Throwable th) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            throw th;
                        }
                    }
                    return null;
                }
                return null;
            }
            return null;
        }
    }

    public String[] findAccessibilityNodeInfoByAccessibilityId(int accessibilityWindowId, long accessibilityNodeId, int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags, long interrogatingTid, Bundle arguments) throws RemoteException {
        Region partialInteractiveRegion = Region.obtain();
        synchronized (this.mLock) {
            this.mUsesAccessibilityCache = true;
            if (isCalledForCurrentUserLocked()) {
                int resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                boolean permissionGranted = this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (permissionGranted) {
                    AccessibilityManagerService.RemoteAccessibilityConnection connection = this.mSystemSupport.getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                    if (!this.mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(resolvedWindowId, partialInteractiveRegion)) {
                        partialInteractiveRegion.recycle();
                        partialInteractiveRegion = null;
                    }
                    MagnificationSpec spec = this.mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
                    if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                        int interrogatingPid = Binder.getCallingPid();
                        IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                        long identityToken = Binder.clearCallingIdentity();
                        try {
                            connection.getRemote().findAccessibilityNodeInfoByAccessibilityId(accessibilityNodeId, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags | flags, interrogatingPid, interrogatingTid, spec, arguments);
                            String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(connection.getPackageName(), connection.getUid());
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return computeValidReportedPackages;
                        } catch (RemoteException e) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return null;
                        } catch (Throwable th) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            throw th;
                        }
                    }
                    return null;
                }
                return null;
            }
            return null;
        }
    }

    public String[] findFocus(int accessibilityWindowId, long accessibilityNodeId, int focusType, int interactionId, IAccessibilityInteractionConnectionCallback callback, long interrogatingTid) throws RemoteException {
        Region partialInteractiveRegion = Region.obtain();
        synchronized (this.mLock) {
            try {
                try {
                    if (isCalledForCurrentUserLocked()) {
                        int resolvedWindowId = resolveAccessibilityWindowIdForFindFocusLocked(accessibilityWindowId, focusType);
                        boolean permissionGranted = this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                        if (permissionGranted) {
                            AccessibilityManagerService.RemoteAccessibilityConnection connection = this.mSystemSupport.getConnectionLocked(resolvedWindowId);
                            if (connection == null) {
                                return null;
                            }
                            if (!this.mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(resolvedWindowId, partialInteractiveRegion)) {
                                partialInteractiveRegion.recycle();
                                partialInteractiveRegion = null;
                            }
                            MagnificationSpec spec = this.mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
                            if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                                int interrogatingPid = Binder.getCallingPid();
                                IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                                long identityToken = Binder.clearCallingIdentity();
                                try {
                                } catch (RemoteException e) {
                                } catch (Throwable th) {
                                    th = th;
                                }
                                try {
                                    connection.getRemote().findFocus(accessibilityNodeId, focusType, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                                    String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(connection.getPackageName(), connection.getUid());
                                    Binder.restoreCallingIdentity(identityToken);
                                    if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                        partialInteractiveRegion.recycle();
                                    }
                                    return computeValidReportedPackages;
                                } catch (RemoteException e2) {
                                    Binder.restoreCallingIdentity(identityToken);
                                    if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                        partialInteractiveRegion.recycle();
                                    }
                                    return null;
                                } catch (Throwable th2) {
                                    th = th2;
                                    Binder.restoreCallingIdentity(identityToken);
                                    if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                        partialInteractiveRegion.recycle();
                                    }
                                    throw th;
                                }
                            }
                            return null;
                        }
                        return null;
                    }
                    return null;
                } catch (Throwable th3) {
                    th = th3;
                    throw th;
                }
            } catch (Throwable th4) {
                th = th4;
                throw th;
            }
        }
    }

    public String[] focusSearch(int accessibilityWindowId, long accessibilityNodeId, int direction, int interactionId, IAccessibilityInteractionConnectionCallback callback, long interrogatingTid) throws RemoteException {
        Region partialInteractiveRegion = Region.obtain();
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                int resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                boolean permissionGranted = this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (permissionGranted) {
                    AccessibilityManagerService.RemoteAccessibilityConnection connection = this.mSystemSupport.getConnectionLocked(resolvedWindowId);
                    if (connection == null) {
                        return null;
                    }
                    if (!this.mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(resolvedWindowId, partialInteractiveRegion)) {
                        partialInteractiveRegion.recycle();
                        partialInteractiveRegion = null;
                    }
                    MagnificationSpec spec = this.mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
                    if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                        int interrogatingPid = Binder.getCallingPid();
                        IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                        long identityToken = Binder.clearCallingIdentity();
                        try {
                            connection.getRemote().focusSearch(accessibilityNodeId, direction, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                            String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(connection.getPackageName(), connection.getUid());
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return computeValidReportedPackages;
                        } catch (RemoteException e) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            return null;
                        } catch (Throwable th) {
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            throw th;
                        }
                    }
                    return null;
                }
                return null;
            }
            return null;
        }
    }

    public void sendGesture(int sequence, ParceledListSlice gestureSteps) {
    }

    public boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId, int action, Bundle arguments, int interactionId, IAccessibilityInteractionConnectionCallback callback, long interrogatingTid) throws RemoteException {
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                int resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                if (this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId)) {
                    if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                        boolean returnValue = this.mSystemSupport.performAccessibilityAction(resolvedWindowId, accessibilityNodeId, action, arguments, interactionId, callback, this.mFetchFlags, interrogatingTid);
                        return returnValue;
                    }
                    return false;
                }
                return false;
            }
            return false;
        }
    }

    public boolean performGlobalAction(int action) {
        synchronized (this.mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return false;
            }
            return this.mGlobalActionPerformer.performGlobalAction(action);
        }
    }

    public boolean isFingerprintGestureDetectionAvailable() {
        FingerprintGestureDispatcher dispatcher;
        return this.mContext.getPackageManager().hasSystemFeature("android.hardware.fingerprint") && isCapturingFingerprintGestures() && (dispatcher = this.mSystemSupport.getFingerprintGestureDispatcher()) != null && dispatcher.isFingerprintGestureDetectionAvailable();
    }

    public float getMagnificationScale(int displayId) {
        synchronized (this.mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 1.0f;
            }
            long identity = Binder.clearCallingIdentity();
            try {
                return this.mSystemSupport.getMagnificationController().getScale(displayId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public Region getMagnificationRegion(int displayId) {
        synchronized (this.mLock) {
            Region region = Region.obtain();
            if (isCalledForCurrentUserLocked()) {
                MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
                boolean registeredJustForThisCall = registerMagnificationIfNeeded(displayId, magnificationController);
                long identity = Binder.clearCallingIdentity();
                magnificationController.getMagnificationRegion(displayId, region);
                Binder.restoreCallingIdentity(identity);
                if (registeredJustForThisCall) {
                    magnificationController.unregister(displayId);
                }
                return region;
            }
            return region;
        }
    }

    public float getMagnificationCenterX(int displayId) {
        synchronized (this.mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
            boolean registeredJustForThisCall = registerMagnificationIfNeeded(displayId, magnificationController);
            long identity = Binder.clearCallingIdentity();
            float centerX = magnificationController.getCenterX(displayId);
            Binder.restoreCallingIdentity(identity);
            if (registeredJustForThisCall) {
                magnificationController.unregister(displayId);
            }
            return centerX;
        }
    }

    public float getMagnificationCenterY(int displayId) {
        synchronized (this.mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
            boolean registeredJustForThisCall = registerMagnificationIfNeeded(displayId, magnificationController);
            long identity = Binder.clearCallingIdentity();
            float centerY = magnificationController.getCenterY(displayId);
            Binder.restoreCallingIdentity(identity);
            if (registeredJustForThisCall) {
                magnificationController.unregister(displayId);
            }
            return centerY;
        }
    }

    private boolean registerMagnificationIfNeeded(int displayId, MagnificationController magnificationController) {
        if (!magnificationController.isRegistered(displayId) && this.mSecurityPolicy.canControlMagnification(this)) {
            magnificationController.register(displayId);
            return true;
        }
        return false;
    }

    /* JADX WARN: Code restructure failed: missing block: B:17:0x002b, code lost:
        if (r3.isMagnifying(r6) == false) goto L23;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean resetMagnification(int r6, boolean r7) {
        /*
            r5 = this;
            java.lang.Object r0 = r5.mLock
            monitor-enter(r0)
            boolean r1 = r5.isCalledForCurrentUserLocked()     // Catch: java.lang.Throwable -> L37
            r2 = 0
            if (r1 != 0) goto Lc
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L37
            return r2
        Lc:
            com.android.server.accessibility.AccessibilityManagerService$SecurityPolicy r1 = r5.mSecurityPolicy     // Catch: java.lang.Throwable -> L37
            boolean r1 = r1.canControlMagnification(r5)     // Catch: java.lang.Throwable -> L37
            if (r1 != 0) goto L16
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L37
            return r2
        L16:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L37
            long r0 = android.os.Binder.clearCallingIdentity()
            com.android.server.accessibility.AbstractAccessibilityServiceConnection$SystemSupport r3 = r5.mSystemSupport     // Catch: java.lang.Throwable -> L32
            com.android.server.accessibility.MagnificationController r3 = r3.getMagnificationController()     // Catch: java.lang.Throwable -> L32
            boolean r4 = r3.reset(r6, r7)     // Catch: java.lang.Throwable -> L32
            if (r4 != 0) goto L2d
            boolean r4 = r3.isMagnifying(r6)     // Catch: java.lang.Throwable -> L32
            if (r4 != 0) goto L2e
        L2d:
            r2 = 1
        L2e:
            android.os.Binder.restoreCallingIdentity(r0)
            return r2
        L32:
            r2 = move-exception
            android.os.Binder.restoreCallingIdentity(r0)
            throw r2
        L37:
            r1 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L37
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.accessibility.AbstractAccessibilityServiceConnection.resetMagnification(int, boolean):boolean");
    }

    public boolean setMagnificationScaleAndCenter(int displayId, float scale, float centerX, float centerY, boolean animate) {
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                if (this.mSecurityPolicy.canControlMagnification(this)) {
                    long identity = Binder.clearCallingIdentity();
                    MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
                    if (!magnificationController.isRegistered(displayId)) {
                        magnificationController.register(displayId);
                    }
                    boolean scaleAndCenter = magnificationController.setScaleAndCenter(displayId, scale, centerX, centerY, animate, this.mId);
                    Binder.restoreCallingIdentity(identity);
                    return scaleAndCenter;
                }
                return false;
            }
            return false;
        }
    }

    public void setMagnificationCallbackEnabled(int displayId, boolean enabled) {
        this.mInvocationHandler.setMagnificationCallbackEnabled(displayId, enabled);
    }

    public boolean isMagnificationCallbackEnabled(int displayId) {
        return this.mInvocationHandler.isMagnificationCallbackEnabled(displayId);
    }

    public void setSoftKeyboardCallbackEnabled(boolean enabled) {
        this.mInvocationHandler.setSoftKeyboardCallbackEnabled(enabled);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, LOG_TAG, pw)) {
            synchronized (this.mLock) {
                pw.append((CharSequence) ("Service[label=" + ((Object) this.mAccessibilityServiceInfo.getResolveInfo().loadLabel(this.mContext.getPackageManager()))));
                pw.append((CharSequence) (", feedbackType" + AccessibilityServiceInfo.feedbackTypeToString(this.mFeedbackType)));
                pw.append((CharSequence) (", capabilities=" + this.mAccessibilityServiceInfo.getCapabilities()));
                pw.append((CharSequence) (", eventTypes=" + AccessibilityEvent.eventTypeToString(this.mEventTypes)));
                pw.append((CharSequence) (", notificationTimeout=" + this.mNotificationTimeout));
                pw.append("]");
            }
        }
    }

    public void onAdded() {
        long identity = Binder.clearCallingIdentity();
        try {
            this.mWindowManagerService.addWindowToken(this.mOverlayWindowToken, 2032, 0);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onRemoved() {
        long identity = Binder.clearCallingIdentity();
        try {
            this.mWindowManagerService.removeWindowToken(this.mOverlayWindowToken, true, 0);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void resetLocked() {
        this.mSystemSupport.getKeyEventDispatcher().flush(this);
        try {
            if (this.mServiceInterface != null) {
                this.mServiceInterface.init((IAccessibilityServiceConnection) null, this.mId, (IBinder) null);
            }
        } catch (RemoteException e) {
        }
        IBinder iBinder = this.mService;
        if (iBinder != null) {
            iBinder.unlinkToDeath(this, 0);
            this.mService = null;
        }
        this.mServiceInterface = null;
        this.mReceivedAccessibilityButtonCallbackSinceBind = false;
    }

    public boolean isConnectedLocked() {
        return this.mService != null;
    }

    public void notifyAccessibilityEvent(AccessibilityEvent event) {
        Message message;
        synchronized (this.mLock) {
            int eventType = event.getEventType();
            boolean serviceWantsEvent = wantsEventLocked(event);
            int i = 1;
            boolean requiredForCacheConsistency = this.mUsesAccessibilityCache && (4307005 & eventType) != 0;
            if (serviceWantsEvent || requiredForCacheConsistency) {
                if (this.mSecurityPolicy.checkAccessibilityAccess(this)) {
                    AccessibilityEvent newEvent = AccessibilityEvent.obtain(event);
                    if (this.mNotificationTimeout > 0 && eventType != 2048) {
                        AccessibilityEvent oldEvent = this.mPendingEvents.get(eventType);
                        this.mPendingEvents.put(eventType, newEvent);
                        if (oldEvent != null) {
                            this.mEventDispatchHandler.removeMessages(eventType);
                            oldEvent.recycle();
                        }
                        message = this.mEventDispatchHandler.obtainMessage(eventType);
                    } else {
                        message = this.mEventDispatchHandler.obtainMessage(eventType, newEvent);
                    }
                    if (!serviceWantsEvent) {
                        i = 0;
                    }
                    message.arg1 = i;
                    this.mEventDispatchHandler.sendMessageDelayed(message, this.mNotificationTimeout);
                }
            }
        }
    }

    private boolean wantsEventLocked(AccessibilityEvent event) {
        if (canReceiveEventsLocked()) {
            if (event.getWindowId() == -1 || event.isImportantForAccessibility() || (this.mFetchFlags & 8) != 0) {
                int eventType = event.getEventType();
                if ((this.mEventTypes & eventType) != eventType) {
                    return false;
                }
                Set<String> packageNames = this.mPackageNames;
                String packageName = event.getPackageName() != null ? event.getPackageName().toString() : null;
                return packageNames.isEmpty() || packageNames.contains(packageName);
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyAccessibilityEventInternal(int eventType, AccessibilityEvent event, boolean serviceWantsEvent) {
        synchronized (this.mLock) {
            IAccessibilityServiceClient listener = this.mServiceInterface;
            if (listener == null) {
                return;
            }
            if (event == null) {
                event = this.mPendingEvents.get(eventType);
                if (event == null) {
                    return;
                }
                this.mPendingEvents.remove(eventType);
            }
            if (this.mSecurityPolicy.canRetrieveWindowContentLocked(this)) {
                event.setConnectionId(this.mId);
            } else {
                event.setSource(null);
            }
            event.setSealed(true);
            try {
                try {
                    listener.onAccessibilityEvent(event, serviceWantsEvent);
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error during sending " + event + " to " + listener, re);
                }
            } finally {
                event.recycle();
            }
        }
    }

    public void notifyGesture(int gestureId) {
        this.mInvocationHandler.obtainMessage(1, gestureId, 0).sendToTarget();
    }

    public void notifyClearAccessibilityNodeInfoCache() {
        this.mInvocationHandler.sendEmptyMessage(2);
    }

    public void notifyMagnificationChangedLocked(int displayId, Region region, float scale, float centerX, float centerY) {
        this.mInvocationHandler.notifyMagnificationChangedLocked(displayId, region, scale, centerX, centerY);
    }

    public void notifySoftKeyboardShowModeChangedLocked(int showState) {
        this.mInvocationHandler.notifySoftKeyboardShowModeChangedLocked(showState);
    }

    public void notifyAccessibilityButtonClickedLocked() {
        this.mInvocationHandler.notifyAccessibilityButtonClickedLocked();
    }

    public void notifyAccessibilityButtonAvailabilityChangedLocked(boolean available) {
        this.mInvocationHandler.notifyAccessibilityButtonAvailabilityChangedLocked(available);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyMagnificationChangedInternal(int displayId, Region region, float scale, float centerX, float centerY) {
        IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onMagnificationChanged(displayId, region, scale, centerX, centerY);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending magnification changes to " + this.mService, re);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifySoftKeyboardShowModeChangedInternal(int showState) {
        IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onSoftKeyboardShowModeChanged(showState);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending soft keyboard show mode changes to " + this.mService, re);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyAccessibilityButtonClickedInternal() {
        IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onAccessibilityButtonClicked();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending accessibility button click to " + this.mService, re);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyAccessibilityButtonAvailabilityChangedInternal(boolean available) {
        if (this.mReceivedAccessibilityButtonCallbackSinceBind && this.mLastAccessibilityButtonCallbackState == available) {
            return;
        }
        this.mReceivedAccessibilityButtonCallbackSinceBind = true;
        this.mLastAccessibilityButtonCallbackState = available;
        IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onAccessibilityButtonAvailabilityChanged(available);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending accessibility button availability change to " + this.mService, re);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyGestureInternal(int gestureId) {
        IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onGesture(gestureId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during sending gesture " + gestureId + " to " + this.mService, re);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyClearAccessibilityCacheInternal() {
        IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.clearAccessibilityCache();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during requesting accessibility info cache to be cleared.", re);
            }
        }
    }

    private IAccessibilityServiceClient getServiceInterfaceSafely() {
        IAccessibilityServiceClient iAccessibilityServiceClient;
        synchronized (this.mLock) {
            iAccessibilityServiceClient = this.mServiceInterface;
        }
        return iAccessibilityServiceClient;
    }

    private int resolveAccessibilityWindowIdLocked(int accessibilityWindowId) {
        if (accessibilityWindowId == Integer.MAX_VALUE) {
            return this.mSecurityPolicy.getActiveWindowId();
        }
        return accessibilityWindowId;
    }

    private int resolveAccessibilityWindowIdForFindFocusLocked(int windowId, int focusType) {
        if (windowId == Integer.MAX_VALUE) {
            return this.mSecurityPolicy.mActiveWindowId;
        }
        if (windowId == -2) {
            if (focusType == 1) {
                return this.mSecurityPolicy.mFocusedWindowId;
            }
            if (focusType == 2) {
                return this.mSecurityPolicy.mAccessibilityFocusedWindowId;
            }
        }
        return windowId;
    }

    public ComponentName getComponentName() {
        return this.mComponentName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InvocationHandler extends Handler {
        public static final int MSG_CLEAR_ACCESSIBILITY_CACHE = 2;
        private static final int MSG_ON_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED = 8;
        private static final int MSG_ON_ACCESSIBILITY_BUTTON_CLICKED = 7;
        public static final int MSG_ON_GESTURE = 1;
        private static final int MSG_ON_MAGNIFICATION_CHANGED = 5;
        private static final int MSG_ON_SOFT_KEYBOARD_STATE_CHANGED = 6;
        private boolean mIsSoftKeyboardCallbackEnabled;
        @GuardedBy({"mlock"})
        private final SparseArray<Boolean> mMagnificationCallbackState;

        public InvocationHandler(Looper looper) {
            super(looper, null, true);
            this.mMagnificationCallbackState = new SparseArray<>(0);
            this.mIsSoftKeyboardCallbackEnabled = false;
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            int type = message.what;
            if (type == 1) {
                int gestureId = message.arg1;
                AbstractAccessibilityServiceConnection.this.notifyGestureInternal(gestureId);
            } else if (type == 2) {
                AbstractAccessibilityServiceConnection.this.notifyClearAccessibilityCacheInternal();
            } else if (type == 5) {
                SomeArgs args = (SomeArgs) message.obj;
                Region region = (Region) args.arg1;
                float scale = ((Float) args.arg2).floatValue();
                float centerX = ((Float) args.arg3).floatValue();
                float centerY = ((Float) args.arg4).floatValue();
                int displayId = args.argi1;
                AbstractAccessibilityServiceConnection.this.notifyMagnificationChangedInternal(displayId, region, scale, centerX, centerY);
                args.recycle();
            } else if (type == 6) {
                int showState = message.arg1;
                AbstractAccessibilityServiceConnection.this.notifySoftKeyboardShowModeChangedInternal(showState);
            } else if (type == 7) {
                AbstractAccessibilityServiceConnection.this.notifyAccessibilityButtonClickedInternal();
            } else if (type == 8) {
                boolean available = message.arg1 != 0;
                AbstractAccessibilityServiceConnection.this.notifyAccessibilityButtonAvailabilityChangedInternal(available);
            } else {
                throw new IllegalArgumentException("Unknown message: " + type);
            }
        }

        public void notifyMagnificationChangedLocked(int displayId, Region region, float scale, float centerX, float centerY) {
            synchronized (AbstractAccessibilityServiceConnection.this.mLock) {
                if (this.mMagnificationCallbackState.get(displayId) == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = region;
                args.arg2 = Float.valueOf(scale);
                args.arg3 = Float.valueOf(centerX);
                args.arg4 = Float.valueOf(centerY);
                args.argi1 = displayId;
                Message msg = obtainMessage(5, args);
                msg.sendToTarget();
            }
        }

        public void setMagnificationCallbackEnabled(int displayId, boolean enabled) {
            synchronized (AbstractAccessibilityServiceConnection.this.mLock) {
                if (enabled) {
                    this.mMagnificationCallbackState.put(displayId, true);
                } else {
                    this.mMagnificationCallbackState.remove(displayId);
                }
            }
        }

        public boolean isMagnificationCallbackEnabled(int displayId) {
            boolean z;
            synchronized (AbstractAccessibilityServiceConnection.this.mLock) {
                z = this.mMagnificationCallbackState.get(displayId) != null;
            }
            return z;
        }

        public void notifySoftKeyboardShowModeChangedLocked(int showState) {
            if (!this.mIsSoftKeyboardCallbackEnabled) {
                return;
            }
            Message msg = obtainMessage(6, showState, 0);
            msg.sendToTarget();
        }

        public void setSoftKeyboardCallbackEnabled(boolean enabled) {
            this.mIsSoftKeyboardCallbackEnabled = enabled;
        }

        public void notifyAccessibilityButtonClickedLocked() {
            Message msg = obtainMessage(7);
            msg.sendToTarget();
        }

        public void notifyAccessibilityButtonAvailabilityChangedLocked(boolean available) {
            Message msg = obtainMessage(8, available ? 1 : 0, 0);
            msg.sendToTarget();
        }
    }
}
