package com.android.server.autofill;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.autofill.AutofillServiceInfo;
import android.service.autofill.FieldClassification;
import android.service.autofill.FillEventHistory;
import android.service.autofill.FillResponse;
import android.service.autofill.UserData;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.server.LocalServices;
import com.android.server.autofill.AutofillManagerService;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.job.controllers.JobStatus;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class AutofillManagerServiceImpl {
    private static final int MAX_ABANDONED_SESSION_MILLIS = 30000;
    private static final int MAX_SESSION_ID_CREATE_TRIES = 2048;
    private static final String TAG = "AutofillManagerServiceImpl";
    private static final Random sRandom = new Random();
    private final AutofillManagerService.AutofillCompatState mAutofillCompatState;
    @GuardedBy("mLock")
    private RemoteCallbackList<IAutoFillManagerClient> mClients;
    private final Context mContext;
    @GuardedBy("mLock")
    private boolean mDisabled;
    @GuardedBy("mLock")
    private ArrayMap<ComponentName, Long> mDisabledActivities;
    @GuardedBy("mLock")
    private ArrayMap<String, Long> mDisabledApps;
    @GuardedBy("mLock")
    private FillEventHistory mEventHistory;
    private final FieldClassificationStrategy mFieldClassificationStrategy;
    @GuardedBy("mLock")
    private AutofillServiceInfo mInfo;
    private final Object mLock;
    private final LocalLog mRequestsHistory;
    @GuardedBy("mLock")
    private boolean mSetupComplete;
    private final AutoFillUI mUi;
    private final LocalLog mUiLatencyHistory;
    @GuardedBy("mLock")
    private UserData mUserData;
    private final int mUserId;
    private final LocalLog mWtfHistory;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private final Handler mHandler = new Handler(Looper.getMainLooper(), null, true);
    @GuardedBy("mLock")
    private final SparseArray<Session> mSessions = new SparseArray<>();
    private long mLastPrune = 0;

    /* JADX INFO: Access modifiers changed from: package-private */
    public AutofillManagerServiceImpl(Context context, Object lock, LocalLog requestsHistory, LocalLog uiLatencyHistory, LocalLog wtfHistory, int userId, AutoFillUI ui, AutofillManagerService.AutofillCompatState autofillCompatState, boolean disabled) {
        this.mContext = context;
        this.mLock = lock;
        this.mRequestsHistory = requestsHistory;
        this.mUiLatencyHistory = uiLatencyHistory;
        this.mWtfHistory = wtfHistory;
        this.mUserId = userId;
        this.mUi = ui;
        this.mFieldClassificationStrategy = new FieldClassificationStrategy(context, userId);
        this.mAutofillCompatState = autofillCompatState;
        updateLocked(disabled);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public CharSequence getServiceName() {
        String packageName = getServicePackageName();
        if (packageName == null) {
            return null;
        }
        try {
            PackageManager pm = this.mContext.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info);
        } catch (Exception e) {
            Slog.e(TAG, "Could not get label for " + packageName + ": " + e);
            return packageName;
        }
    }

    @GuardedBy("mLock")
    private int getServiceUidLocked() {
        if (this.mInfo == null) {
            Slog.w(TAG, "getServiceUidLocked(): no mInfo");
            return -1;
        }
        return this.mInfo.getServiceInfo().applicationInfo.uid;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String[] getUrlBarResourceIdsForCompatMode(String packageName) {
        return this.mAutofillCompatState.getUrlBarResourceIds(packageName, this.mUserId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getServicePackageName() {
        ComponentName serviceComponent = getServiceComponentName();
        if (serviceComponent != null) {
            return serviceComponent.getPackageName();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ComponentName getServiceComponentName() {
        synchronized (this.mLock) {
            if (this.mInfo == null) {
                return null;
            }
            return this.mInfo.getServiceInfo().getComponentName();
        }
    }

    private boolean isSetupCompletedLocked() {
        String setupComplete = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "user_setup_complete", this.mUserId);
        return "1".equals(setupComplete);
    }

    private String getComponentNameFromSettings() {
        return Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "autofill_service", this.mUserId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void updateLocked(boolean disabled) {
        boolean wasEnabled = isEnabledLocked();
        if (Helper.sVerbose) {
            Slog.v(TAG, "updateLocked(u=" + this.mUserId + "): wasEnabled=" + wasEnabled + ", mSetupComplete= " + this.mSetupComplete + ", disabled=" + disabled + ", mDisabled=" + this.mDisabled);
        }
        this.mSetupComplete = isSetupCompletedLocked();
        this.mDisabled = disabled;
        ComponentName serviceComponent = null;
        ServiceInfo serviceInfo = null;
        String componentName = getComponentNameFromSettings();
        if (!TextUtils.isEmpty(componentName)) {
            try {
                serviceComponent = ComponentName.unflattenFromString(componentName);
                serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, 0, this.mUserId);
                if (serviceInfo == null) {
                    Slog.e(TAG, "Bad AutofillService name: " + componentName);
                }
            } catch (RemoteException | RuntimeException e) {
                Slog.e(TAG, "Error getting service info for '" + componentName + "': " + e);
                serviceInfo = null;
            }
        }
        try {
            if (serviceInfo != null) {
                this.mInfo = new AutofillServiceInfo(this.mContext, serviceComponent, this.mUserId);
                if (Helper.sDebug) {
                    Slog.d(TAG, "Set component for user " + this.mUserId + " as " + this.mInfo);
                }
            } else {
                this.mInfo = null;
                if (Helper.sDebug) {
                    Slog.d(TAG, "Reset component for user " + this.mUserId + " (" + componentName + ")");
                }
            }
        } catch (Exception e2) {
            Slog.e(TAG, "Bad AutofillServiceInfo for '" + componentName + "': " + e2);
            this.mInfo = null;
        }
        boolean isEnabled = isEnabledLocked();
        if (wasEnabled != isEnabled) {
            if (!isEnabled) {
                int sessionCount = this.mSessions.size();
                for (int i = sessionCount - 1; i >= 0; i--) {
                    Session session = this.mSessions.valueAt(i);
                    session.removeSelfLocked();
                }
            }
            sendStateToClients(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public boolean addClientLocked(IAutoFillManagerClient client) {
        if (this.mClients == null) {
            this.mClients = new RemoteCallbackList<>();
        }
        this.mClients.register(client);
        return isEnabledLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void removeClientLocked(IAutoFillManagerClient client) {
        if (this.mClients != null) {
            this.mClients.unregister(client);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void setAuthenticationResultLocked(Bundle data, int sessionId, int authenticationId, int uid) {
        Session session;
        if (isEnabledLocked() && (session = this.mSessions.get(sessionId)) != null && uid == session.uid) {
            session.setAuthenticationResultLocked(data, authenticationId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasCallback(int sessionId, int uid, boolean hasIt) {
        Session session;
        if (isEnabledLocked() && (session = this.mSessions.get(sessionId)) != null && uid == session.uid) {
            synchronized (this.mLock) {
                session.setHasCallbackLocked(hasIt);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public int startSessionLocked(IBinder activityToken, int uid, IBinder appCallbackToken, AutofillId autofillId, Rect virtualBounds, AutofillValue value, boolean hasCallback, ComponentName componentName, boolean compatMode, boolean bindInstantServiceAllowed, int flags) {
        IBinder iBinder;
        if (!isEnabledLocked()) {
            return 0;
        }
        String shortComponentName = componentName.toShortString();
        if (isAutofillDisabledLocked(componentName)) {
            if (Helper.sDebug) {
                Slog.d(TAG, "startSession(" + shortComponentName + "): ignored because disabled by service");
            }
            IAutoFillManagerClient client = IAutoFillManagerClient.Stub.asInterface(appCallbackToken);
            try {
                client.setSessionFinished(4);
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not notify " + shortComponentName + " that it's disabled: " + e);
            }
            return Integer.MIN_VALUE;
        }
        if (Helper.sVerbose) {
            StringBuilder sb = new StringBuilder();
            sb.append("startSession(): token=");
            iBinder = activityToken;
            sb.append(iBinder);
            sb.append(", flags=");
            sb.append(flags);
            Slog.v(TAG, sb.toString());
        } else {
            iBinder = activityToken;
        }
        pruneAbandonedSessionsLocked();
        Session newSession = createSessionByTokenLocked(iBinder, uid, appCallbackToken, hasCallback, componentName, compatMode, bindInstantServiceAllowed, flags);
        if (newSession == null) {
            return Integer.MIN_VALUE;
        }
        String historyItem = "id=" + newSession.id + " uid=" + uid + " a=" + shortComponentName + " s=" + this.mInfo.getServiceInfo().packageName + " u=" + this.mUserId + " i=" + autofillId + " b=" + virtualBounds + " hc=" + hasCallback + " f=" + flags;
        this.mRequestsHistory.log(historyItem);
        newSession.updateLocked(autofillId, virtualBounds, value, 1, flags);
        return newSession.id;
    }

    @GuardedBy("mLock")
    private void pruneAbandonedSessionsLocked() {
        long now = System.currentTimeMillis();
        if (this.mLastPrune < now - 30000) {
            this.mLastPrune = now;
            if (this.mSessions.size() > 0) {
                new PruneTask().execute(new Void[0]);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void setAutofillFailureLocked(int sessionId, int uid, List<AutofillId> ids) {
        if (!isEnabledLocked()) {
            return;
        }
        Session session = this.mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            Slog.v(TAG, "setAutofillFailure(): no session for " + sessionId + "(" + uid + ")");
            return;
        }
        session.setAutofillFailureLocked(ids);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void finishSessionLocked(int sessionId, int uid) {
        if (!isEnabledLocked()) {
            return;
        }
        Session session = this.mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "finishSessionLocked(): no session for " + sessionId + "(" + uid + ")");
                return;
            }
            return;
        }
        session.logContextCommitted();
        boolean finished = session.showSaveLocked();
        if (Helper.sVerbose) {
            Slog.v(TAG, "finishSessionLocked(): session finished on save? " + finished);
        }
        if (finished) {
            session.removeSelfLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void cancelSessionLocked(int sessionId, int uid) {
        if (!isEnabledLocked()) {
            return;
        }
        Session session = this.mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            Slog.w(TAG, "cancelSessionLocked(): no session for " + sessionId + "(" + uid + ")");
            return;
        }
        session.removeSelfLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void disableOwnedAutofillServicesLocked(int uid) {
        Slog.i(TAG, "disableOwnedServices(" + uid + "): " + this.mInfo);
        if (this.mInfo == null) {
            return;
        }
        ServiceInfo serviceInfo = this.mInfo.getServiceInfo();
        if (serviceInfo.applicationInfo.uid != uid) {
            Slog.w(TAG, "disableOwnedServices(): ignored when called by UID " + uid + " instead of " + serviceInfo.applicationInfo.uid + " for service " + this.mInfo);
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            String autoFillService = getComponentNameFromSettings();
            ComponentName componentName = serviceInfo.getComponentName();
            if (componentName.equals(ComponentName.unflattenFromString(autoFillService))) {
                this.mMetricsLogger.action(1135, componentName.getPackageName());
                Settings.Secure.putStringForUser(this.mContext.getContentResolver(), "autofill_service", null, this.mUserId);
                destroySessionsLocked();
            } else {
                Slog.w(TAG, "disableOwnedServices(): ignored because current service (" + serviceInfo + ") does not match Settings (" + autoFillService + ")");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mLock")
    private Session createSessionByTokenLocked(IBinder activityToken, int uid, IBinder appCallbackToken, boolean hasCallback, ComponentName componentName, boolean compatMode, boolean bindInstantServiceAllowed, int flags) {
        AutofillManagerServiceImpl autofillManagerServiceImpl = this;
        int tries = 0;
        while (true) {
            int tries2 = tries + 1;
            if (tries2 > 2048) {
                Slog.w(TAG, "Cannot create session in 2048 tries");
                return null;
            }
            int sessionId = sRandom.nextInt();
            if (sessionId == Integer.MIN_VALUE || autofillManagerServiceImpl.mSessions.indexOfKey(sessionId) >= 0) {
                autofillManagerServiceImpl = autofillManagerServiceImpl;
                tries = tries2;
            } else {
                autofillManagerServiceImpl.assertCallerLocked(componentName, compatMode);
                Session newSession = new Session(autofillManagerServiceImpl, autofillManagerServiceImpl.mUi, autofillManagerServiceImpl.mContext, autofillManagerServiceImpl.mHandler, autofillManagerServiceImpl.mUserId, autofillManagerServiceImpl.mLock, sessionId, uid, activityToken, appCallbackToken, hasCallback, autofillManagerServiceImpl.mUiLatencyHistory, autofillManagerServiceImpl.mWtfHistory, autofillManagerServiceImpl.mInfo.getServiceInfo().getComponentName(), componentName, compatMode, bindInstantServiceAllowed, flags);
                this.mSessions.put(newSession.id, newSession);
                return newSession;
            }
        }
    }

    private void assertCallerLocked(ComponentName componentName, boolean compatMode) {
        String callingPackage;
        String packageName = componentName.getPackageName();
        PackageManager pm = this.mContext.getPackageManager();
        int callingUid = Binder.getCallingUid();
        try {
            int packageUid = pm.getPackageUidAsUser(packageName, UserHandle.getCallingUserId());
            if (callingUid != packageUid && !((ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class)).hasRunningActivity(callingUid, packageName)) {
                String[] packages = pm.getPackagesForUid(callingUid);
                if (packages != null) {
                    callingPackage = packages[0];
                } else {
                    callingPackage = "uid-" + callingUid;
                }
                Slog.w(TAG, "App (package=" + callingPackage + ", UID=" + callingUid + ") passed component (" + componentName + ") owned by UID " + packageUid);
                LogMaker log = new LogMaker(948).setPackageName(callingPackage).addTaggedData(908, getServicePackageName()).addTaggedData(949, componentName == null ? "null" : componentName.flattenToShortString());
                if (compatMode) {
                    log.addTaggedData(1414, 1);
                }
                this.mMetricsLogger.write(log);
                throw new SecurityException("Invalid component: " + componentName);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("Could not verify UID for " + componentName);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean restoreSession(int sessionId, int uid, IBinder activityToken, IBinder appCallback) {
        Session session = this.mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            return false;
        }
        session.switchActivity(activityToken, appCallback);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public boolean updateSessionLocked(int sessionId, int uid, AutofillId autofillId, Rect virtualBounds, AutofillValue value, int action, int flags) {
        Session session = this.mSessions.get(sessionId);
        if (session == null || session.uid != uid) {
            if ((flags & 1) != 0) {
                if (Helper.sDebug) {
                    Slog.d(TAG, "restarting session " + sessionId + " due to manual request on " + autofillId);
                    return true;
                }
                return true;
            }
            if (Helper.sVerbose) {
                Slog.v(TAG, "updateSessionLocked(): session gone for " + sessionId + "(" + uid + ")");
            }
            return false;
        }
        session.updateLocked(autofillId, virtualBounds, value, action, flags);
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void removeSessionLocked(int sessionId) {
        this.mSessions.remove(sessionId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleSessionSave(Session session) {
        synchronized (this.mLock) {
            if (this.mSessions.get(session.id) == null) {
                Slog.w(TAG, "handleSessionSave(): already gone: " + session.id);
                return;
            }
            session.callSaveLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPendingSaveUi(int operation, IBinder token) {
        if (Helper.sVerbose) {
            Slog.v(TAG, "onPendingSaveUi(" + operation + "): " + token);
        }
        synchronized (this.mLock) {
            int sessionCount = this.mSessions.size();
            for (int i = sessionCount - 1; i >= 0; i--) {
                Session session = this.mSessions.valueAt(i);
                if (session.isSaveUiPendingForTokenLocked(token)) {
                    session.onPendingSaveUi(operation, token);
                    return;
                }
            }
            if (Helper.sDebug) {
                Slog.d(TAG, "No pending Save UI for token " + token + " and operation " + DebugUtils.flagsToString(AutofillManager.class, "PENDING_UI_OPERATION_", operation));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void handlePackageUpdateLocked(String packageName) {
        ServiceInfo serviceInfo = this.mFieldClassificationStrategy.getServiceInfo();
        if (serviceInfo != null && serviceInfo.packageName.equals(packageName)) {
            resetExtServiceLocked();
        }
    }

    @GuardedBy("mLock")
    void resetExtServiceLocked() {
        if (Helper.sVerbose) {
            Slog.v(TAG, "reset autofill service.");
        }
        this.mFieldClassificationStrategy.reset();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void destroyLocked() {
        if (Helper.sVerbose) {
            Slog.v(TAG, "destroyLocked()");
        }
        resetExtServiceLocked();
        int numSessions = this.mSessions.size();
        ArraySet<RemoteFillService> remoteFillServices = new ArraySet<>(numSessions);
        for (int i = 0; i < numSessions; i++) {
            RemoteFillService remoteFillService = this.mSessions.valueAt(i).destroyLocked();
            if (remoteFillService != null) {
                remoteFillServices.add(remoteFillService);
            }
        }
        this.mSessions.clear();
        for (int i2 = 0; i2 < remoteFillServices.size(); i2++) {
            remoteFillServices.valueAt(i2).destroy();
        }
        sendStateToClients(true);
        if (this.mClients != null) {
            this.mClients.kill();
            this.mClients = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public CharSequence getServiceLabel() {
        CharSequence label = this.mInfo.getServiceInfo().loadSafeLabel(this.mContext.getPackageManager(), 0.0f, 5);
        return label;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Drawable getServiceIcon() {
        return this.mInfo.getServiceInfo().loadIcon(this.mContext.getPackageManager());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setLastResponse(int sessionId, FillResponse response) {
        synchronized (this.mLock) {
            this.mEventHistory = new FillEventHistory(sessionId, response.getClientState());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetLastResponse() {
        synchronized (this.mLock) {
            this.mEventHistory = null;
        }
    }

    @GuardedBy("mLock")
    private boolean isValidEventLocked(String method, int sessionId) {
        if (this.mEventHistory == null) {
            Slog.w(TAG, method + ": not logging event because history is null");
            return false;
        } else if (sessionId != this.mEventHistory.getSessionId()) {
            if (Helper.sDebug) {
                Slog.d(TAG, method + ": not logging event for session " + sessionId + " because tracked session is " + this.mEventHistory.getSessionId());
            }
            return false;
        } else {
            return true;
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:13:0x002f
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    void setAuthenticationSelected(int r19, android.os.Bundle r20) {
        /*
            r18 = this;
            r1 = r18
            java.lang.Object r2 = r1.mLock
            monitor-enter(r2)
            java.lang.String r0 = "setAuthenticationSelected()"
            r3 = r19
            boolean r0 = r1.isValidEventLocked(r0, r3)     // Catch: java.lang.Throwable -> L2d
            if (r0 == 0) goto L2b
            android.service.autofill.FillEventHistory r0 = r1.mEventHistory     // Catch: java.lang.Throwable -> L2d
            android.service.autofill.FillEventHistory$Event r15 = new android.service.autofill.FillEventHistory$Event     // Catch: java.lang.Throwable -> L2d
            r5 = 2
            r6 = 0
            r8 = 0
            r9 = 0
            r10 = 0
            r11 = 0
            r12 = 0
            r13 = 0
            r14 = 0
            r16 = 0
            r4 = r15
            r7 = r20
            r1 = r15
            r15 = r16
            r4.<init>(r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15)     // Catch: java.lang.Throwable -> L2d
            r0.addEvent(r1)     // Catch: java.lang.Throwable -> L2d
        L2b:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2d
            return
        L2d:
            r0 = move-exception
            goto L32
        L2f:
            r0 = move-exception
            r3 = r19
        L32:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2d
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.autofill.AutofillManagerServiceImpl.setAuthenticationSelected(int, android.os.Bundle):void");
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:13:0x0030
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    void logDatasetAuthenticationSelected(java.lang.String r19, int r20, android.os.Bundle r21) {
        /*
            r18 = this;
            r1 = r18
            java.lang.Object r2 = r1.mLock
            monitor-enter(r2)
            java.lang.String r0 = "logDatasetAuthenticationSelected()"
            r3 = r20
            boolean r0 = r1.isValidEventLocked(r0, r3)     // Catch: java.lang.Throwable -> L2e
            if (r0 == 0) goto L2c
            android.service.autofill.FillEventHistory r0 = r1.mEventHistory     // Catch: java.lang.Throwable -> L2e
            android.service.autofill.FillEventHistory$Event r15 = new android.service.autofill.FillEventHistory$Event     // Catch: java.lang.Throwable -> L2e
            r5 = 1
            r8 = 0
            r9 = 0
            r10 = 0
            r11 = 0
            r12 = 0
            r13 = 0
            r14 = 0
            r16 = 0
            r4 = r15
            r6 = r19
            r7 = r21
            r1 = r15
            r15 = r16
            r4.<init>(r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15)     // Catch: java.lang.Throwable -> L2e
            r0.addEvent(r1)     // Catch: java.lang.Throwable -> L2e
        L2c:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2e
            return
        L2e:
            r0 = move-exception
            goto L33
        L30:
            r0 = move-exception
            r3 = r20
        L33:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2e
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.autofill.AutofillManagerServiceImpl.logDatasetAuthenticationSelected(java.lang.String, int, android.os.Bundle):void");
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:13:0x002f
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    void logSaveShown(int r19, android.os.Bundle r20) {
        /*
            r18 = this;
            r1 = r18
            java.lang.Object r2 = r1.mLock
            monitor-enter(r2)
            java.lang.String r0 = "logSaveShown()"
            r3 = r19
            boolean r0 = r1.isValidEventLocked(r0, r3)     // Catch: java.lang.Throwable -> L2d
            if (r0 == 0) goto L2b
            android.service.autofill.FillEventHistory r0 = r1.mEventHistory     // Catch: java.lang.Throwable -> L2d
            android.service.autofill.FillEventHistory$Event r15 = new android.service.autofill.FillEventHistory$Event     // Catch: java.lang.Throwable -> L2d
            r5 = 3
            r6 = 0
            r8 = 0
            r9 = 0
            r10 = 0
            r11 = 0
            r12 = 0
            r13 = 0
            r14 = 0
            r16 = 0
            r4 = r15
            r7 = r20
            r1 = r15
            r15 = r16
            r4.<init>(r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15)     // Catch: java.lang.Throwable -> L2d
            r0.addEvent(r1)     // Catch: java.lang.Throwable -> L2d
        L2b:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2d
            return
        L2d:
            r0 = move-exception
            goto L32
        L2f:
            r0 = move-exception
            r3 = r19
        L32:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2d
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.autofill.AutofillManagerServiceImpl.logSaveShown(int, android.os.Bundle):void");
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:13:0x0030
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    void logDatasetSelected(java.lang.String r19, int r20, android.os.Bundle r21) {
        /*
            r18 = this;
            r1 = r18
            java.lang.Object r2 = r1.mLock
            monitor-enter(r2)
            java.lang.String r0 = "logDatasetSelected()"
            r3 = r20
            boolean r0 = r1.isValidEventLocked(r0, r3)     // Catch: java.lang.Throwable -> L2e
            if (r0 == 0) goto L2c
            android.service.autofill.FillEventHistory r0 = r1.mEventHistory     // Catch: java.lang.Throwable -> L2e
            android.service.autofill.FillEventHistory$Event r15 = new android.service.autofill.FillEventHistory$Event     // Catch: java.lang.Throwable -> L2e
            r5 = 0
            r8 = 0
            r9 = 0
            r10 = 0
            r11 = 0
            r12 = 0
            r13 = 0
            r14 = 0
            r16 = 0
            r4 = r15
            r6 = r19
            r7 = r21
            r1 = r15
            r15 = r16
            r4.<init>(r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15)     // Catch: java.lang.Throwable -> L2e
            r0.addEvent(r1)     // Catch: java.lang.Throwable -> L2e
        L2c:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2e
            return
        L2e:
            r0 = move-exception
            goto L33
        L30:
            r0 = move-exception
            r3 = r20
        L33:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L2e
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.autofill.AutofillManagerServiceImpl.logDatasetSelected(java.lang.String, int, android.os.Bundle):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void logContextCommittedLocked(int sessionId, Bundle clientState, ArrayList<String> selectedDatasets, ArraySet<String> ignoredDatasets, ArrayList<AutofillId> changedFieldIds, ArrayList<String> changedDatasetIds, ArrayList<AutofillId> manuallyFilledFieldIds, ArrayList<ArrayList<String>> manuallyFilledDatasetIds, ComponentName appComponentName, boolean compatMode) {
        logContextCommittedLocked(sessionId, clientState, selectedDatasets, ignoredDatasets, changedFieldIds, changedDatasetIds, manuallyFilledFieldIds, manuallyFilledDatasetIds, null, null, appComponentName, compatMode);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void logContextCommittedLocked(int sessionId, Bundle clientState, ArrayList<String> selectedDatasets, ArraySet<String> ignoredDatasets, ArrayList<AutofillId> changedFieldIds, ArrayList<String> changedDatasetIds, ArrayList<AutofillId> manuallyFilledFieldIds, ArrayList<ArrayList<String>> manuallyFilledDatasetIds, ArrayList<AutofillId> detectedFieldIdsList, ArrayList<FieldClassification> detectedFieldClassificationsList, ComponentName appComponentName, boolean compatMode) {
        ArrayList<String> arrayList;
        ArraySet<String> arraySet;
        AutofillId[] detectedFieldsIds;
        if (isValidEventLocked("logDatasetNotSelected()", sessionId)) {
            if (Helper.sVerbose) {
                StringBuilder sb = new StringBuilder();
                sb.append("logContextCommitted() with FieldClassification: id=");
                sb.append(sessionId);
                sb.append(", selectedDatasets=");
                arrayList = selectedDatasets;
                sb.append(arrayList);
                sb.append(", ignoredDatasetIds=");
                arraySet = ignoredDatasets;
                sb.append(arraySet);
                sb.append(", changedAutofillIds=");
                sb.append(changedFieldIds);
                sb.append(", changedDatasetIds=");
                sb.append(changedDatasetIds);
                sb.append(", manuallyFilledFieldIds=");
                sb.append(manuallyFilledFieldIds);
                sb.append(", detectedFieldIds=");
                sb.append(detectedFieldIdsList);
                sb.append(", detectedFieldClassifications=");
                sb.append(detectedFieldClassificationsList);
                sb.append(", appComponentName=");
                sb.append(appComponentName.toShortString());
                sb.append(", compatMode=");
                sb.append(compatMode);
                Slog.v(TAG, sb.toString());
            } else {
                arrayList = selectedDatasets;
                arraySet = ignoredDatasets;
            }
            AutofillId[] detectedFieldsIds2 = null;
            FieldClassification[] detectedFieldClassifications = null;
            if (detectedFieldIdsList != null) {
                AutofillId[] detectedFieldsIds3 = new AutofillId[detectedFieldIdsList.size()];
                detectedFieldIdsList.toArray(detectedFieldsIds3);
                detectedFieldClassifications = new FieldClassification[detectedFieldClassificationsList.size()];
                detectedFieldClassificationsList.toArray(detectedFieldClassifications);
                int numberFields = detectedFieldsIds3.length;
                float totalScore = 0.0f;
                int totalSize = 0;
                int totalSize2 = 0;
                while (totalSize2 < numberFields) {
                    FieldClassification fc = detectedFieldClassifications[totalSize2];
                    List<FieldClassification.Match> matches = fc.getMatches();
                    int size = matches.size();
                    totalSize += size;
                    float totalScore2 = totalScore;
                    int j = 0;
                    while (true) {
                        int j2 = j;
                        detectedFieldsIds = detectedFieldsIds3;
                        if (j2 < size) {
                            totalScore2 += matches.get(j2).getScore();
                            j = j2 + 1;
                            detectedFieldsIds3 = detectedFieldsIds;
                            matches = matches;
                        }
                    }
                    totalSize2++;
                    totalScore = totalScore2;
                    detectedFieldsIds3 = detectedFieldsIds;
                }
                int averageScore = (int) ((100.0f * totalScore) / totalSize);
                this.mMetricsLogger.write(Helper.newLogMaker(1273, appComponentName, getServicePackageName(), sessionId, compatMode).setCounterValue(numberFields).addTaggedData(1274, Integer.valueOf(averageScore)));
                detectedFieldsIds2 = detectedFieldsIds3;
            }
            this.mEventHistory.addEvent(new FillEventHistory.Event(4, null, clientState, arrayList, arraySet, changedFieldIds, changedDatasetIds, manuallyFilledFieldIds, manuallyFilledDatasetIds, detectedFieldsIds2, detectedFieldClassifications));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public FillEventHistory getFillEventHistory(int callingUid) {
        synchronized (this.mLock) {
            if (this.mEventHistory != null && isCalledByServiceLocked("getFillEventHistory", callingUid)) {
                return this.mEventHistory;
            }
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserData getUserData() {
        UserData userData;
        synchronized (this.mLock) {
            userData = this.mUserData;
        }
        return userData;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserData getUserData(int callingUid) {
        synchronized (this.mLock) {
            if (isCalledByServiceLocked("getUserData", callingUid)) {
                return this.mUserData;
            }
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setUserData(int callingUid, UserData userData) {
        synchronized (this.mLock) {
            if (isCalledByServiceLocked("setUserData", callingUid)) {
                this.mUserData = userData;
                int numberFields = this.mUserData == null ? 0 : this.mUserData.getCategoryIds().length;
                this.mMetricsLogger.write(new LogMaker(1272).setPackageName(getServicePackageName()).addTaggedData(914, Integer.valueOf(numberFields)));
            }
        }
    }

    @GuardedBy("mLock")
    private boolean isCalledByServiceLocked(String methodName, int callingUid) {
        if (getServiceUidLocked() != callingUid) {
            Slog.w(TAG, methodName + "() called by UID " + callingUid + ", but service UID is " + getServiceUidLocked());
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void dumpLocked(String prefix, PrintWriter pw) {
        String prefix2 = prefix + "  ";
        pw.print(prefix);
        pw.print("User: ");
        pw.println(this.mUserId);
        pw.print(prefix);
        pw.print("UID: ");
        pw.println(getServiceUidLocked());
        pw.print(prefix);
        pw.print("Autofill Service Info: ");
        if (this.mInfo == null) {
            pw.println("N/A");
        } else {
            pw.println();
            this.mInfo.dump(prefix2, pw);
            pw.print(prefix);
            pw.print("Service Label: ");
            pw.println(getServiceLabel());
        }
        pw.print(prefix);
        pw.print("Component from settings: ");
        pw.println(getComponentNameFromSettings());
        pw.print(prefix);
        pw.print("Default component: ");
        pw.println(this.mContext.getString(17039654));
        pw.print(prefix);
        pw.print("Disabled: ");
        pw.println(this.mDisabled);
        pw.print(prefix);
        pw.print("Field classification enabled: ");
        pw.println(isFieldClassificationEnabledLocked());
        pw.print(prefix);
        pw.print("Compat pkgs: ");
        ArrayMap<String, Long> compatPkgs = getCompatibilityPackagesLocked();
        if (compatPkgs == null) {
            pw.println("N/A");
        } else {
            pw.println(compatPkgs);
        }
        pw.print(prefix);
        pw.print("Setup complete: ");
        pw.println(this.mSetupComplete);
        pw.print(prefix);
        pw.print("Last prune: ");
        pw.println(this.mLastPrune);
        pw.print(prefix);
        pw.print("Disabled apps: ");
        if (this.mDisabledApps == null) {
            pw.println("N/A");
        } else {
            int size = this.mDisabledApps.size();
            pw.println(size);
            StringBuilder builder = new StringBuilder();
            long now = SystemClock.elapsedRealtime();
            for (int i = 0; i < size; i++) {
                String packageName = this.mDisabledApps.keyAt(i);
                long expiration = this.mDisabledApps.valueAt(i).longValue();
                builder.append(prefix);
                builder.append(prefix);
                builder.append(i);
                builder.append(". ");
                builder.append(packageName);
                builder.append(": ");
                TimeUtils.formatDuration(expiration - now, builder);
                builder.append('\n');
            }
            pw.println(builder);
        }
        pw.print(prefix);
        pw.print("Disabled activities: ");
        if (this.mDisabledActivities == null) {
            pw.println("N/A");
        } else {
            int size2 = this.mDisabledActivities.size();
            pw.println(size2);
            StringBuilder builder2 = new StringBuilder();
            long now2 = SystemClock.elapsedRealtime();
            for (int i2 = 0; i2 < size2; i2++) {
                ComponentName component = this.mDisabledActivities.keyAt(i2);
                long expiration2 = this.mDisabledActivities.valueAt(i2).longValue();
                builder2.append(prefix);
                builder2.append(prefix);
                builder2.append(i2);
                builder2.append(". ");
                builder2.append(component);
                builder2.append(": ");
                TimeUtils.formatDuration(expiration2 - now2, builder2);
                builder2.append('\n');
            }
            pw.println(builder2);
        }
        int size3 = this.mSessions.size();
        if (size3 == 0) {
            pw.print(prefix);
            pw.println("No sessions");
        } else {
            pw.print(prefix);
            pw.print(size3);
            pw.println(" sessions:");
            for (int i3 = 0; i3 < size3; i3++) {
                pw.print(prefix);
                pw.print(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
                pw.println(i3 + 1);
                this.mSessions.valueAt(i3).dumpLocked(prefix2, pw);
            }
        }
        pw.print(prefix);
        pw.print("Clients: ");
        if (this.mClients == null) {
            pw.println("N/A");
        } else {
            pw.println();
            this.mClients.dump(pw, prefix2);
        }
        if (this.mEventHistory == null || this.mEventHistory.getEvents() == null || this.mEventHistory.getEvents().size() == 0) {
            pw.print(prefix);
            pw.println("No event on last fill response");
        } else {
            pw.print(prefix);
            pw.println("Events of last fill response:");
            pw.print(prefix);
            int numEvents = this.mEventHistory.getEvents().size();
            int i4 = 0;
            while (true) {
                int i5 = i4;
                if (i5 >= numEvents) {
                    break;
                }
                FillEventHistory.Event event = this.mEventHistory.getEvents().get(i5);
                pw.println("  " + i5 + ": eventType=" + event.getType() + " datasetId=" + event.getDatasetId());
                i4 = i5 + 1;
            }
        }
        pw.print(prefix);
        pw.print("User data: ");
        if (this.mUserData == null) {
            pw.println("N/A");
        } else {
            pw.println();
            this.mUserData.dump(prefix2, pw);
        }
        pw.print(prefix);
        pw.println("Field Classification strategy: ");
        this.mFieldClassificationStrategy.dump(prefix2, pw);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void destroySessionsLocked() {
        if (this.mSessions.size() == 0) {
            this.mUi.destroyAll(null, null, false);
            return;
        }
        while (this.mSessions.size() > 0) {
            this.mSessions.valueAt(0).forceRemoveSelfLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void destroyFinishedSessionsLocked() {
        int sessionCount = this.mSessions.size();
        for (int i = sessionCount - 1; i >= 0; i--) {
            Session session = this.mSessions.valueAt(i);
            if (session.isSavingLocked()) {
                if (Helper.sDebug) {
                    Slog.d(TAG, "destroyFinishedSessionsLocked(): " + session.id);
                }
                session.forceRemoveSelfLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void listSessionsLocked(ArrayList<String> output) {
        int numSessions = this.mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append(this.mInfo != null ? this.mInfo.getServiceInfo().getComponentName() : null);
            sb.append(":");
            sb.append(this.mSessions.keyAt(i));
            output.add(sb.toString());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public ArrayMap<String, Long> getCompatibilityPackagesLocked() {
        if (this.mInfo != null) {
            return this.mInfo.getCompatibilityPackages();
        }
        return null;
    }

    private void sendStateToClients(boolean resetClient) {
        boolean resetSession;
        boolean isEnabled;
        synchronized (this.mLock) {
            if (this.mClients == null) {
                return;
            }
            RemoteCallbackList<IAutoFillManagerClient> clients = this.mClients;
            int userClientCount = clients.beginBroadcast();
            for (int i = 0; i < userClientCount; i++) {
                try {
                    IAutoFillManagerClient client = clients.getBroadcastItem(i);
                    try {
                        synchronized (this.mLock) {
                            if (!resetClient) {
                                try {
                                    if (!isClientSessionDestroyedLocked(client)) {
                                        resetSession = false;
                                        isEnabled = isEnabledLocked();
                                    }
                                } catch (Throwable th) {
                                    throw th;
                                    break;
                                }
                            }
                            resetSession = true;
                            isEnabled = isEnabledLocked();
                        }
                        int flags = 0;
                        if (isEnabled) {
                            flags = 0 | 1;
                        }
                        if (resetSession) {
                            flags |= 2;
                        }
                        if (resetClient) {
                            flags |= 4;
                        }
                        if (Helper.sDebug) {
                            flags |= 8;
                        }
                        if (Helper.sVerbose) {
                            flags |= 16;
                        }
                        client.setState(flags);
                    } catch (RemoteException e) {
                    }
                } finally {
                    clients.finishBroadcast();
                }
            }
        }
    }

    @GuardedBy("mLock")
    private boolean isClientSessionDestroyedLocked(IAutoFillManagerClient client) {
        int sessionCount = this.mSessions.size();
        for (int i = 0; i < sessionCount; i++) {
            Session session = this.mSessions.valueAt(i);
            if (session.getClient().equals(client)) {
                return session.isDestroyed();
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public boolean isEnabledLocked() {
        return (!this.mSetupComplete || this.mInfo == null || this.mDisabled) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void disableAutofillForApp(String packageName, long duration, int sessionId, boolean compatMode) {
        synchronized (this.mLock) {
            if (this.mDisabledApps == null) {
                this.mDisabledApps = new ArrayMap<>(1);
            }
            long expiration = SystemClock.elapsedRealtime() + duration;
            if (expiration < 0) {
                expiration = JobStatus.NO_LATEST_RUNTIME;
            }
            this.mDisabledApps.put(packageName, Long.valueOf(expiration));
            int intDuration = duration > 2147483647L ? Integer.MAX_VALUE : (int) duration;
            this.mMetricsLogger.write(Helper.newLogMaker(1231, packageName, getServicePackageName(), sessionId, compatMode).addTaggedData(1145, Integer.valueOf(intDuration)));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void disableAutofillForActivity(ComponentName componentName, long duration, int sessionId, boolean compatMode) {
        int intDuration;
        synchronized (this.mLock) {
            if (this.mDisabledActivities == null) {
                this.mDisabledActivities = new ArrayMap<>(1);
            }
            long expiration = SystemClock.elapsedRealtime() + duration;
            if (expiration < 0) {
                expiration = JobStatus.NO_LATEST_RUNTIME;
            }
            this.mDisabledActivities.put(componentName, Long.valueOf(expiration));
            if (duration > 2147483647L) {
                intDuration = Integer.MAX_VALUE;
            } else {
                intDuration = (int) duration;
            }
            LogMaker log = new LogMaker(1232).setComponentName(componentName).addTaggedData(908, getServicePackageName()).addTaggedData(1145, Integer.valueOf(intDuration)).addTaggedData(1456, Integer.valueOf(sessionId));
            if (compatMode) {
                log.addTaggedData(1414, 1);
            }
            this.mMetricsLogger.write(log);
        }
    }

    @GuardedBy("mLock")
    private boolean isAutofillDisabledLocked(ComponentName componentName) {
        Long expiration;
        long elapsedTime = 0;
        if (this.mDisabledActivities != null) {
            elapsedTime = SystemClock.elapsedRealtime();
            Long expiration2 = this.mDisabledActivities.get(componentName);
            if (expiration2 != null) {
                if (expiration2.longValue() >= elapsedTime) {
                    return true;
                }
                if (Helper.sVerbose) {
                    Slog.v(TAG, "Removing " + componentName.toShortString() + " from disabled list");
                }
                this.mDisabledActivities.remove(componentName);
            }
        }
        String packageName = componentName.getPackageName();
        if (this.mDisabledApps == null || (expiration = this.mDisabledApps.get(packageName)) == null) {
            return false;
        }
        if (elapsedTime == 0) {
            elapsedTime = SystemClock.elapsedRealtime();
        }
        if (expiration.longValue() >= elapsedTime) {
            return true;
        }
        if (Helper.sVerbose) {
            Slog.v(TAG, "Removing " + packageName + " from disabled list");
        }
        this.mDisabledApps.remove(packageName);
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFieldClassificationEnabled(int callingUid) {
        synchronized (this.mLock) {
            if (!isCalledByServiceLocked("isFieldClassificationEnabled", callingUid)) {
                return false;
            }
            return isFieldClassificationEnabledLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFieldClassificationEnabledLocked() {
        return Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "autofill_field_classification", 1, this.mUserId) == 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public FieldClassificationStrategy getFieldClassificationStrategy() {
        return this.mFieldClassificationStrategy;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String[] getAvailableFieldClassificationAlgorithms(int callingUid) {
        synchronized (this.mLock) {
            if (!isCalledByServiceLocked("getFCAlgorithms()", callingUid)) {
                return null;
            }
            return this.mFieldClassificationStrategy.getAvailableAlgorithms();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getDefaultFieldClassificationAlgorithm(int callingUid) {
        synchronized (this.mLock) {
            if (!isCalledByServiceLocked("getDefaultFCAlgorithm()", callingUid)) {
                return null;
            }
            return this.mFieldClassificationStrategy.getDefaultAlgorithm();
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AutofillManagerServiceImpl: [userId=");
        sb.append(this.mUserId);
        sb.append(", component=");
        sb.append(this.mInfo != null ? this.mInfo.getServiceInfo().getComponentName() : null);
        sb.append("]");
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PruneTask extends AsyncTask<Void, Void, Void> {
        private PruneTask() {
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // android.os.AsyncTask
        public Void doInBackground(Void... ignored) {
            int numSessionsToRemove;
            SparseArray<IBinder> sessionsToRemove;
            int i;
            synchronized (AutofillManagerServiceImpl.this.mLock) {
                numSessionsToRemove = AutofillManagerServiceImpl.this.mSessions.size();
                sessionsToRemove = new SparseArray<>(numSessionsToRemove);
                i = 0;
                for (int i2 = 0; i2 < numSessionsToRemove; i2++) {
                    Session session = (Session) AutofillManagerServiceImpl.this.mSessions.valueAt(i2);
                    sessionsToRemove.put(session.id, session.getActivityTokenLocked());
                }
            }
            IActivityManager am = ActivityManager.getService();
            int numSessionsToRemove2 = numSessionsToRemove;
            int i3 = 0;
            while (i3 < numSessionsToRemove2) {
                try {
                    if (am.getActivityClassForToken(sessionsToRemove.valueAt(i3)) != null) {
                        sessionsToRemove.removeAt(i3);
                        i3--;
                        numSessionsToRemove2--;
                    }
                } catch (RemoteException e) {
                    Slog.w(AutofillManagerServiceImpl.TAG, "Cannot figure out if activity is finished", e);
                }
                i3++;
            }
            synchronized (AutofillManagerServiceImpl.this.mLock) {
                while (true) {
                    int i4 = i;
                    if (i4 < numSessionsToRemove2) {
                        try {
                            Session sessionToRemove = (Session) AutofillManagerServiceImpl.this.mSessions.get(sessionsToRemove.keyAt(i4));
                            if (sessionToRemove != null && sessionsToRemove.valueAt(i4) == sessionToRemove.getActivityTokenLocked()) {
                                if (sessionToRemove.isSavingLocked()) {
                                    if (Helper.sVerbose) {
                                        Slog.v(AutofillManagerServiceImpl.TAG, "Session " + sessionToRemove.id + " is saving");
                                    }
                                } else {
                                    if (Helper.sDebug) {
                                        Slog.i(AutofillManagerServiceImpl.TAG, "Prune session " + sessionToRemove.id + " (" + sessionToRemove.getActivityTokenLocked() + ")");
                                    }
                                    sessionToRemove.removeSelfLocked();
                                }
                            }
                            i = i4 + 1;
                        } finally {
                        }
                    }
                }
            }
            return null;
        }
    }
}
