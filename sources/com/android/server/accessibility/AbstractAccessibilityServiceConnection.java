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

        void onClientChange(boolean z);

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
        if (!this.mRequestFilterKeyEvents || this.mServiceInterface == null || (this.mAccessibilityServiceInfo.getCapabilities() & 8) == 0) {
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
        return (this.mUsesAccessibilityCache ? 4307005 : 0) | this.mEventTypes;
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
                this.mSystemSupport.onClientChange(true);
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
    }

    public AccessibilityWindowInfo getWindow(int windowId) {
        this.mSystemSupport.ensureWindowsAvailableTimed();
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                boolean permissionGranted = this.mSecurityPolicy.canRetrieveWindowsLocked(this);
                if (permissionGranted) {
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
                    int interrogatingPid = Binder.getCallingPid();
                    IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                    int callingUid = Binder.getCallingUid();
                    long identityToken = Binder.clearCallingIdentity();
                    try {
                        connection.getRemote().findAccessibilityNodeInfosByViewId(accessibilityNodeId, viewIdResName, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                        String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(callingUid, connection.getPackageName(), connection.getUid());
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
                    int interrogatingPid = Binder.getCallingPid();
                    IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                    int callingUid = Binder.getCallingUid();
                    long identityToken = Binder.clearCallingIdentity();
                    try {
                        connection.getRemote().findAccessibilityNodeInfosByText(accessibilityNodeId, text, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                        String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(callingUid, connection.getPackageName(), connection.getUid());
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
                    int interrogatingPid = Binder.getCallingPid();
                    IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                    int callingUid = Binder.getCallingUid();
                    long identityToken = Binder.clearCallingIdentity();
                    try {
                        connection.getRemote().findAccessibilityNodeInfoByAccessibilityId(accessibilityNodeId, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags | flags, interrogatingPid, interrogatingTid, spec, arguments);
                        String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(callingUid, connection.getPackageName(), connection.getUid());
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
    }

    public String[] findFocus(int accessibilityWindowId, long accessibilityNodeId, int focusType, int interactionId, IAccessibilityInteractionConnectionCallback callback, long interrogatingTid) throws RemoteException {
        Region partialInteractiveRegion;
        long identityToken;
        AccessibilityManagerService.RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion2;
        long identityToken2;
        Region partialInteractiveRegion3 = Region.obtain();
        synchronized (this.mLock) {
            try {
                if (!isCalledForCurrentUserLocked()) {
                    return null;
                }
                int resolvedWindowId = resolveAccessibilityWindowIdForFindFocusLocked(accessibilityWindowId, focusType);
                boolean permissionGranted = this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
                if (!permissionGranted) {
                    return null;
                }
                AccessibilityManagerService.RemoteAccessibilityConnection connection2 = this.mSystemSupport.getConnectionLocked(resolvedWindowId);
                if (connection2 == null) {
                    return null;
                }
                if (!this.mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(resolvedWindowId, partialInteractiveRegion3)) {
                    partialInteractiveRegion3.recycle();
                    partialInteractiveRegion3 = null;
                }
                try {
                    MagnificationSpec spec = this.mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
                    int interrogatingPid = Binder.getCallingPid();
                    IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                    int callingUid = Binder.getCallingUid();
                    long identityToken3 = Binder.clearCallingIdentity();
                    try {
                        Region partialInteractiveRegion4 = partialInteractiveRegion3;
                        connection = connection2;
                        try {
                            connection2.getRemote().findFocus(accessibilityNodeId, focusType, partialInteractiveRegion3, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                            String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(callingUid, connection.getPackageName(), connection.getUid());
                            Binder.restoreCallingIdentity(identityToken3);
                            if (partialInteractiveRegion4 != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion4.recycle();
                            }
                            return computeValidReportedPackages;
                        } catch (RemoteException e) {
                            partialInteractiveRegion2 = partialInteractiveRegion4;
                            identityToken2 = identityToken3;
                            Binder.restoreCallingIdentity(identityToken2);
                            if (partialInteractiveRegion2 == null || !Binder.isProxy(connection.getRemote())) {
                                return null;
                            }
                            partialInteractiveRegion2.recycle();
                            return null;
                        } catch (Throwable th) {
                            th = th;
                            partialInteractiveRegion = partialInteractiveRegion4;
                            identityToken = identityToken3;
                            Binder.restoreCallingIdentity(identityToken);
                            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                                partialInteractiveRegion.recycle();
                            }
                            throw th;
                        }
                    } catch (RemoteException e2) {
                        partialInteractiveRegion2 = partialInteractiveRegion3;
                        identityToken2 = identityToken3;
                        connection = connection2;
                    } catch (Throwable th2) {
                        th = th2;
                        partialInteractiveRegion = partialInteractiveRegion3;
                        identityToken = identityToken3;
                        connection = connection2;
                    }
                } catch (Throwable th3) {
                    th = th3;
                    throw th;
                }
            } catch (Throwable th4) {
                th = th4;
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
                    int interrogatingPid = Binder.getCallingPid();
                    IAccessibilityInteractionConnectionCallback callback2 = this.mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId, interrogatingPid, interrogatingTid);
                    int callingUid = Binder.getCallingUid();
                    long identityToken = Binder.clearCallingIdentity();
                    try {
                        connection.getRemote().focusSearch(accessibilityNodeId, direction, partialInteractiveRegion, interactionId, callback2, this.mFetchFlags, interrogatingPid, interrogatingTid, spec);
                        String[] computeValidReportedPackages = this.mSecurityPolicy.computeValidReportedPackages(callingUid, connection.getPackageName(), connection.getUid());
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
    }

    public void sendGesture(int sequence, ParceledListSlice gestureSteps) {
    }

    public boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId, int action, Bundle arguments, int interactionId, IAccessibilityInteractionConnectionCallback callback, long interrogatingTid) throws RemoteException {
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                int resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
                if (this.mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId)) {
                    boolean returnValue = this.mSystemSupport.performAccessibilityAction(resolvedWindowId, accessibilityNodeId, action, arguments, interactionId, callback, this.mFetchFlags, interrogatingTid);
                    return returnValue;
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

    public float getMagnificationScale() {
        synchronized (this.mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 1.0f;
            }
            long identity = Binder.clearCallingIdentity();
            try {
                return this.mSystemSupport.getMagnificationController().getScale();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public Region getMagnificationRegion() {
        synchronized (this.mLock) {
            Region region = Region.obtain();
            if (isCalledForCurrentUserLocked()) {
                MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
                boolean registeredJustForThisCall = registerMagnificationIfNeeded(magnificationController);
                long identity = Binder.clearCallingIdentity();
                magnificationController.getMagnificationRegion(region);
                Binder.restoreCallingIdentity(identity);
                if (registeredJustForThisCall) {
                    magnificationController.unregister();
                }
                return region;
            }
            return region;
        }
    }

    public float getMagnificationCenterX() {
        synchronized (this.mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
            boolean registeredJustForThisCall = registerMagnificationIfNeeded(magnificationController);
            long identity = Binder.clearCallingIdentity();
            float centerX = magnificationController.getCenterX();
            Binder.restoreCallingIdentity(identity);
            if (registeredJustForThisCall) {
                magnificationController.unregister();
            }
            return centerX;
        }
    }

    public float getMagnificationCenterY() {
        synchronized (this.mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
            boolean registeredJustForThisCall = registerMagnificationIfNeeded(magnificationController);
            long identity = Binder.clearCallingIdentity();
            float centerY = magnificationController.getCenterY();
            Binder.restoreCallingIdentity(identity);
            if (registeredJustForThisCall) {
                magnificationController.unregister();
            }
            return centerY;
        }
    }

    private boolean registerMagnificationIfNeeded(MagnificationController magnificationController) {
        if (!magnificationController.isRegisteredLocked() && this.mSecurityPolicy.canControlMagnification(this)) {
            magnificationController.register();
            return true;
        }
        return false;
    }

    public boolean resetMagnification(boolean animate) {
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                if (this.mSecurityPolicy.canControlMagnification(this)) {
                    long identity = Binder.clearCallingIdentity();
                    try {
                        return this.mSystemSupport.getMagnificationController().reset(animate);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
                return false;
            }
            return false;
        }
    }

    public boolean setMagnificationScaleAndCenter(float scale, float centerX, float centerY, boolean animate) {
        synchronized (this.mLock) {
            if (isCalledForCurrentUserLocked()) {
                if (this.mSecurityPolicy.canControlMagnification(this)) {
                    long identity = Binder.clearCallingIdentity();
                    MagnificationController magnificationController = this.mSystemSupport.getMagnificationController();
                    if (!magnificationController.isRegisteredLocked()) {
                        magnificationController.register();
                    }
                    boolean scaleAndCenter = magnificationController.setScaleAndCenter(scale, centerX, centerY, animate, this.mId);
                    Binder.restoreCallingIdentity(identity);
                    return scaleAndCenter;
                }
                return false;
            }
            return false;
        }
    }

    public void setMagnificationCallbackEnabled(boolean enabled) {
        this.mInvocationHandler.setMagnificationCallbackEnabled(enabled);
    }

    public boolean isMagnificationCallbackEnabled() {
        return this.mInvocationHandler.mIsMagnificationCallbackEnabled;
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
        if (this.mService != null) {
            this.mService.unlinkToDeath(this, 0);
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
            boolean requiredForCacheConsistency = this.mUsesAccessibilityCache && (4307005 & eventType) != 0;
            if (serviceWantsEvent || requiredForCacheConsistency) {
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
                message.arg1 = serviceWantsEvent ? 1 : 0;
                this.mEventDispatchHandler.sendMessageDelayed(message, this.mNotificationTimeout);
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

    public void notifyMagnificationChangedLocked(Region region, float scale, float centerX, float centerY) {
        this.mInvocationHandler.notifyMagnificationChangedLocked(region, scale, centerX, centerY);
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
    public void notifyMagnificationChangedInternal(Region region, float scale, float centerX, float centerY) {
        IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onMagnificationChanged(region, scale, centerX, centerY);
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
        private boolean mIsMagnificationCallbackEnabled;
        private boolean mIsSoftKeyboardCallbackEnabled;

        public InvocationHandler(Looper looper) {
            super(looper, null, true);
            this.mIsMagnificationCallbackEnabled = false;
            this.mIsSoftKeyboardCallbackEnabled = false;
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            int type = message.what;
            switch (type) {
                case 1:
                    int gestureId = message.arg1;
                    AbstractAccessibilityServiceConnection.this.notifyGestureInternal(gestureId);
                    return;
                case 2:
                    AbstractAccessibilityServiceConnection.this.notifyClearAccessibilityCacheInternal();
                    return;
                case 3:
                case 4:
                default:
                    throw new IllegalArgumentException("Unknown message: " + type);
                case 5:
                    SomeArgs args = (SomeArgs) message.obj;
                    Region region = (Region) args.arg1;
                    float scale = ((Float) args.arg2).floatValue();
                    float centerX = ((Float) args.arg3).floatValue();
                    float centerY = ((Float) args.arg4).floatValue();
                    AbstractAccessibilityServiceConnection.this.notifyMagnificationChangedInternal(region, scale, centerX, centerY);
                    args.recycle();
                    return;
                case 6:
                    int showState = message.arg1;
                    AbstractAccessibilityServiceConnection.this.notifySoftKeyboardShowModeChangedInternal(showState);
                    return;
                case 7:
                    AbstractAccessibilityServiceConnection.this.notifyAccessibilityButtonClickedInternal();
                    return;
                case 8:
                    boolean available = message.arg1 != 0;
                    AbstractAccessibilityServiceConnection.this.notifyAccessibilityButtonAvailabilityChangedInternal(available);
                    return;
            }
        }

        public void notifyMagnificationChangedLocked(Region region, float scale, float centerX, float centerY) {
            if (!this.mIsMagnificationCallbackEnabled) {
                return;
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = region;
            args.arg2 = Float.valueOf(scale);
            args.arg3 = Float.valueOf(centerX);
            args.arg4 = Float.valueOf(centerY);
            Message msg = obtainMessage(5, args);
            msg.sendToTarget();
        }

        public void setMagnificationCallbackEnabled(boolean enabled) {
            this.mIsMagnificationCallbackEnabled = enabled;
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
