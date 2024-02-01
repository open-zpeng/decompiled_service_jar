package com.android.server.autofill;

import android.app.ActivityManager;
import android.app.IAssistDataReceiver;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.autofill.Dataset;
import android.service.autofill.FieldClassification;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.InternalSanitizer;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.service.autofill.UserData;
import android.service.autofill.ValueFinder;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.KeyEvent;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import android.view.autofill.IAutofillWindowPresenter;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.autofill.RemoteFillService;
import com.android.server.autofill.ViewState;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.autofill.ui.PendingUi;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class Session implements RemoteFillService.FillServiceCallbacks, ViewState.Listener, AutoFillUI.AutoFillUiCallback, ValueFinder {
    private static final String EXTRA_REQUEST_ID = "android.service.autofill.extra.REQUEST_ID";
    private static final String TAG = "AutofillSession";
    private static AtomicInteger sIdCounter = new AtomicInteger();
    public final int id;
    @GuardedBy("mLock")
    private IBinder mActivityToken;
    @GuardedBy("mLock")
    private IAutoFillManagerClient mClient;
    @GuardedBy("mLock")
    private Bundle mClientState;
    @GuardedBy("mLock")
    private IBinder.DeathRecipient mClientVulture;
    private final boolean mCompatMode;
    private final ComponentName mComponentName;
    @GuardedBy("mLock")
    private ArrayList<FillContext> mContexts;
    @GuardedBy("mLock")
    private AutofillId mCurrentViewId;
    @GuardedBy("mLock")
    private boolean mDestroyed;
    public final int mFlags;
    private final Handler mHandler;
    private boolean mHasCallback;
    @GuardedBy("mLock")
    private boolean mIsSaving;
    private final Object mLock;
    @GuardedBy("mLock")
    private PendingUi mPendingSaveUi;
    private final RemoteFillService mRemoteFillService;
    @GuardedBy("mLock")
    private SparseArray<FillResponse> mResponses;
    @GuardedBy("mLock")
    private boolean mSaveOnAllViewsInvisible;
    @GuardedBy("mLock")
    private ArrayList<String> mSelectedDatasetIds;
    private final AutofillManagerServiceImpl mService;
    private final AutoFillUI mUi;
    @GuardedBy("mLock")
    private final LocalLog mUiLatencyHistory;
    @GuardedBy("mLock")
    private long mUiShownTime;
    @GuardedBy("mLock")
    private AssistStructure.ViewNode mUrlBar;
    @GuardedBy("mLock")
    private final LocalLog mWtfHistory;
    public final int uid;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    @GuardedBy("mLock")
    private final ArrayMap<AutofillId, ViewState> mViewStates = new ArrayMap<>();
    @GuardedBy("mLock")
    private final SparseArray<LogMaker> mRequestLogs = new SparseArray<>(1);
    private final IAssistDataReceiver mAssistReceiver = new IAssistDataReceiver.Stub() { // from class: com.android.server.autofill.Session.1
        public void onHandleAssistData(Bundle resultData) throws RemoteException {
            FillRequest request;
            AssistStructure structure = (AssistStructure) resultData.getParcelable("structure");
            if (structure == null) {
                Slog.e(Session.TAG, "No assist structure - app might have crashed providing it");
                return;
            }
            Bundle receiverExtras = resultData.getBundle("receiverExtras");
            if (receiverExtras == null) {
                Slog.e(Session.TAG, "No receiver extras - app might have crashed providing it");
                return;
            }
            int requestId = receiverExtras.getInt(Session.EXTRA_REQUEST_ID);
            if (Helper.sVerbose) {
                Slog.v(Session.TAG, "New structure for requestId " + requestId + ": " + structure);
            }
            synchronized (Session.this.mLock) {
                try {
                    structure.ensureDataForAutofill();
                    ComponentName componentNameFromApp = structure.getActivityComponent();
                    if (componentNameFromApp == null || !Session.this.mComponentName.getPackageName().equals(componentNameFromApp.getPackageName())) {
                        Slog.w(Session.TAG, "Activity " + Session.this.mComponentName + " forged different component on AssistStructure: " + componentNameFromApp);
                        structure.setActivityComponent(Session.this.mComponentName);
                        Session.this.mMetricsLogger.write(Session.this.newLogMaker(948).addTaggedData(949, componentNameFromApp == null ? "null" : componentNameFromApp.flattenToShortString()));
                    }
                    if (Session.this.mCompatMode) {
                        String[] urlBarIds = Session.this.mService.getUrlBarResourceIdsForCompatMode(Session.this.mComponentName.getPackageName());
                        if (Helper.sDebug) {
                            Slog.d(Session.TAG, "url_bars in compat mode: " + Arrays.toString(urlBarIds));
                        }
                        if (urlBarIds != null) {
                            Session.this.mUrlBar = Helper.sanitizeUrlBar(structure, urlBarIds);
                            if (Session.this.mUrlBar != null) {
                                AutofillId urlBarId = Session.this.mUrlBar.getAutofillId();
                                if (Helper.sDebug) {
                                    Slog.d(Session.TAG, "Setting urlBar as id=" + urlBarId + " and domain " + Session.this.mUrlBar.getWebDomain());
                                }
                                ViewState viewState = new ViewState(Session.this, urlBarId, Session.this, 512);
                                Session.this.mViewStates.put(urlBarId, viewState);
                            }
                        }
                    }
                    structure.sanitizeForParceling(true);
                    int flags = structure.getFlags();
                    if (Session.this.mContexts == null) {
                        Session.this.mContexts = new ArrayList(1);
                    }
                    Session.this.mContexts.add(new FillContext(requestId, structure));
                    Session.this.cancelCurrentRequestLocked();
                    int numContexts = Session.this.mContexts.size();
                    for (int i = 0; i < numContexts; i++) {
                        Session.this.fillContextWithAllowedValuesLocked((FillContext) Session.this.mContexts.get(i), flags);
                    }
                    request = new FillRequest(requestId, new ArrayList(Session.this.mContexts), Session.this.mClientState, flags);
                } catch (RuntimeException e) {
                    Session.this.wtf(e, "Exception lazy loading assist structure for %s: %s", structure.getActivityComponent(), e);
                    return;
                }
            }
            Session.this.mRemoteFillService.onFillRequest(request);
        }

        public void onHandleAssistScreenshot(Bitmap screenshot) {
        }
    };
    private final long mStartTime = SystemClock.elapsedRealtime();

    @GuardedBy("mLock")
    private AutofillId[] getIdsOfAllViewStatesLocked() {
        int numViewState = this.mViewStates.size();
        AutofillId[] ids = new AutofillId[numViewState];
        for (int i = 0; i < numViewState; i++) {
            ids[i] = this.mViewStates.valueAt(i).id;
        }
        return ids;
    }

    public String findByAutofillId(AutofillId id) {
        synchronized (this.mLock) {
            AutofillValue value = findValueLocked(id);
            if (value != null) {
                if (value.isText()) {
                    return value.getTextValue().toString();
                } else if (value.isList()) {
                    CharSequence[] options = getAutofillOptionsFromContextsLocked(id);
                    if (options != null) {
                        int index = value.getListValue();
                        CharSequence option = options[index];
                        return option != null ? option.toString() : null;
                    }
                    Slog.w(TAG, "findByAutofillId(): no autofill options for id " + id);
                }
            }
            return null;
        }
    }

    public AutofillValue findRawValueByAutofillId(AutofillId id) {
        AutofillValue findValueLocked;
        synchronized (this.mLock) {
            findValueLocked = findValueLocked(id);
        }
        return findValueLocked;
    }

    @GuardedBy("mLock")
    private AutofillValue findValueLocked(AutofillId id) {
        ViewState state = this.mViewStates.get(id);
        if (state == null) {
            if (Helper.sDebug) {
                Slog.d(TAG, "findValueLocked(): no view state for " + id);
                return null;
            }
            return null;
        }
        AutofillValue value = state.getCurrentValue();
        if (value == null) {
            if (Helper.sDebug) {
                Slog.d(TAG, "findValueLocked(): no current value for " + id);
            }
            return getValueFromContextsLocked(id);
        }
        return value;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mLock")
    public void fillContextWithAllowedValuesLocked(FillContext fillContext, int flags) {
        AssistStructure.ViewNode[] nodes = fillContext.findViewNodesByAutofillIds(getIdsOfAllViewStatesLocked());
        int numViewState = this.mViewStates.size();
        for (int i = 0; i < numViewState; i++) {
            ViewState viewState = this.mViewStates.valueAt(i);
            AssistStructure.ViewNode node = nodes[i];
            if (node == null) {
                if (Helper.sVerbose) {
                    Slog.v(TAG, "fillContextWithAllowedValuesLocked(): no node for " + viewState.id);
                }
            } else {
                AutofillValue currentValue = viewState.getCurrentValue();
                AutofillValue filledValue = viewState.getAutofilledValue();
                AssistStructure.AutofillOverlay overlay = new AssistStructure.AutofillOverlay();
                if (filledValue != null && filledValue.equals(currentValue)) {
                    overlay.value = currentValue;
                }
                if (this.mCurrentViewId != null) {
                    overlay.focused = this.mCurrentViewId.equals(viewState.id);
                    if (overlay.focused && (flags & 1) != 0) {
                        overlay.value = currentValue;
                    }
                }
                node.setAutofillOverlay(overlay);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mLock")
    public void cancelCurrentRequestLocked() {
        int canceledRequest = this.mRemoteFillService.cancelCurrentRequest();
        if (canceledRequest != Integer.MIN_VALUE && this.mContexts != null) {
            int numContexts = this.mContexts.size();
            for (int i = numContexts - 1; i >= 0; i--) {
                if (this.mContexts.get(i).getRequestId() == canceledRequest) {
                    if (Helper.sDebug) {
                        Slog.d(TAG, "cancelCurrentRequest(): id = " + canceledRequest);
                    }
                    this.mContexts.remove(i);
                    return;
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void requestNewFillResponseLocked(int flags) {
        int requestId;
        do {
            requestId = sIdCounter.getAndIncrement();
        } while (requestId == Integer.MIN_VALUE);
        int ordinal = this.mRequestLogs.size() + 1;
        LogMaker log = newLogMaker(907).addTaggedData(1454, Integer.valueOf(ordinal));
        if (flags != 0) {
            log.addTaggedData(1452, Integer.valueOf(flags));
        }
        this.mRequestLogs.put(requestId, log);
        if (Helper.sVerbose) {
            Slog.v(TAG, "Requesting structure for request #" + ordinal + " ,requestId=" + requestId + ", flags=" + flags);
        }
        cancelCurrentRequestLocked();
        try {
            Bundle receiverExtras = new Bundle();
            receiverExtras.putInt(EXTRA_REQUEST_ID, requestId);
            long identity = Binder.clearCallingIdentity();
            if (!ActivityManager.getService().requestAutofillData(this.mAssistReceiver, receiverExtras, this.mActivityToken, flags)) {
                Slog.w(TAG, "failed to request autofill data for " + this.mActivityToken);
            }
            Binder.restoreCallingIdentity(identity);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Session(AutofillManagerServiceImpl service, AutoFillUI ui, Context context, Handler handler, int userId, Object lock, int sessionId, int uid, IBinder activityToken, IBinder client, boolean hasCallback, LocalLog uiLatencyHistory, LocalLog wtfHistory, ComponentName serviceComponentName, ComponentName componentName, boolean compatMode, boolean bindInstantServiceAllowed, int flags) {
        this.id = sessionId;
        this.mFlags = flags;
        this.uid = uid;
        this.mService = service;
        this.mLock = lock;
        this.mUi = ui;
        this.mHandler = handler;
        this.mRemoteFillService = new RemoteFillService(context, serviceComponentName, userId, this, bindInstantServiceAllowed);
        this.mActivityToken = activityToken;
        this.mHasCallback = hasCallback;
        this.mUiLatencyHistory = uiLatencyHistory;
        this.mWtfHistory = wtfHistory;
        this.mComponentName = componentName;
        this.mCompatMode = compatMode;
        setClientLocked(client);
        this.mMetricsLogger.write(newLogMaker(906).addTaggedData(1452, Integer.valueOf(flags)));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public IBinder getActivityTokenLocked() {
        return this.mActivityToken;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void switchActivity(IBinder newActivity, IBinder newClient) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#switchActivity() rejected - session: " + this.id + " destroyed");
                return;
            }
            this.mActivityToken = newActivity;
            setClientLocked(newClient);
            updateTrackedIdsLocked();
        }
    }

    @GuardedBy("mLock")
    private void setClientLocked(IBinder client) {
        unlinkClientVultureLocked();
        this.mClient = IAutoFillManagerClient.Stub.asInterface(client);
        this.mClientVulture = new IBinder.DeathRecipient() { // from class: com.android.server.autofill.-$$Lambda$Session$xw4trZ-LA7gCvZvpKJ93vf377ak
            @Override // android.os.IBinder.DeathRecipient
            public final void binderDied() {
                Session.lambda$setClientLocked$0(Session.this);
            }
        };
        try {
            this.mClient.asBinder().linkToDeath(this.mClientVulture, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "could not set binder death listener on autofill client: " + e);
        }
    }

    public static /* synthetic */ void lambda$setClientLocked$0(Session session) {
        Slog.d(TAG, "handling death of " + session.mActivityToken + " when saving=" + session.mIsSaving);
        synchronized (session.mLock) {
            if (session.mIsSaving) {
                session.mUi.hideFillUi(session);
            } else {
                session.mUi.destroyAll(session.mPendingSaveUi, session, false);
            }
        }
    }

    @GuardedBy("mLock")
    private void unlinkClientVultureLocked() {
        if (this.mClient != null && this.mClientVulture != null) {
            boolean unlinked = this.mClient.asBinder().unlinkToDeath(this.mClientVulture, 0);
            if (!unlinked) {
                Slog.w(TAG, "unlinking vulture from death failed for " + this.mActivityToken);
            }
        }
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onFillRequestSuccess(int requestId, FillResponse response, String servicePackageName, int requestFlags) {
        long disableDuration;
        int sessionFinishedState;
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestSuccess() rejected - session: " + this.id + " destroyed");
                return;
            }
            LogMaker requestLog = this.mRequestLogs.get(requestId);
            if (requestLog != null) {
                requestLog.setType(10);
            } else {
                Slog.w(TAG, "onFillRequestSuccess(): no request log for id " + requestId);
            }
            if (response == null) {
                if (requestLog != null) {
                    requestLog.addTaggedData(909, -1);
                }
                processNullResponseLocked(requestFlags);
                return;
            }
            AutofillId[] fieldClassificationIds = response.getFieldClassificationIds();
            if (fieldClassificationIds != null && !this.mService.isFieldClassificationEnabledLocked()) {
                Slog.w(TAG, "Ignoring " + response + " because field detection is disabled");
                processNullResponseLocked(requestFlags);
                return;
            }
            this.mService.setLastResponse(this.id, response);
            long disableDuration2 = response.getDisableDuration();
            if (disableDuration2 > 0) {
                int flags = response.getFlags();
                if (Helper.sDebug) {
                    StringBuilder sb = new StringBuilder("Service disabled autofill for ");
                    sb.append(this.mComponentName);
                    sb.append(": flags=");
                    sb.append(flags);
                    StringBuilder message = sb.append(", duration=");
                    TimeUtils.formatDuration(disableDuration2, message);
                    Slog.d(TAG, message.toString());
                }
                if ((flags & 2) != 0) {
                    disableDuration = disableDuration2;
                    this.mService.disableAutofillForActivity(this.mComponentName, disableDuration2, this.id, this.mCompatMode);
                } else {
                    disableDuration = disableDuration2;
                    this.mService.disableAutofillForApp(this.mComponentName.getPackageName(), disableDuration, this.id, this.mCompatMode);
                }
                sessionFinishedState = 4;
            } else {
                disableDuration = disableDuration2;
                sessionFinishedState = 0;
            }
            if (((response.getDatasets() == null || response.getDatasets().isEmpty()) && response.getAuthentication() == null) || disableDuration > 0) {
                notifyUnavailableToClient(sessionFinishedState);
            }
            if (requestLog != null) {
                requestLog.addTaggedData(909, Integer.valueOf(response.getDatasets() == null ? 0 : response.getDatasets().size()));
                if (fieldClassificationIds != null) {
                    requestLog.addTaggedData(1271, Integer.valueOf(fieldClassificationIds.length));
                }
            }
            synchronized (this.mLock) {
                processResponseLocked(response, null, requestFlags);
            }
        }
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onFillRequestFailure(int requestId, CharSequence message, String servicePackageName) {
        onFillRequestFailureOrTimeout(requestId, false, message, servicePackageName);
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onFillRequestTimeout(int requestId, String servicePackageName) {
        onFillRequestFailureOrTimeout(requestId, true, null, servicePackageName);
    }

    private void onFillRequestFailureOrTimeout(int requestId, boolean timedOut, CharSequence message, String servicePackageName) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestFailureOrTimeout(req=" + requestId + ") rejected - session: " + this.id + " destroyed");
                return;
            }
            this.mService.resetLastResponse();
            LogMaker requestLog = this.mRequestLogs.get(requestId);
            if (requestLog == null) {
                Slog.w(TAG, "onFillRequestFailureOrTimeout(): no log for id " + requestId);
            } else {
                requestLog.setType(timedOut ? 2 : 11);
            }
            if (message != null) {
                getUiForShowing().showError(message, this);
            }
            removeSelf();
        }
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onSaveRequestSuccess(String servicePackageName, IntentSender intentSender) {
        synchronized (this.mLock) {
            this.mIsSaving = false;
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onSaveRequestSuccess() rejected - session: " + this.id + " destroyed");
                return;
            }
            LogMaker log = newLogMaker(918, servicePackageName).setType(intentSender == null ? 10 : 1);
            this.mMetricsLogger.write(log);
            if (intentSender != null) {
                if (Helper.sDebug) {
                    Slog.d(TAG, "Starting intent sender on save()");
                }
                startIntentSender(intentSender);
            }
            removeSelf();
        }
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onSaveRequestFailure(CharSequence message, String servicePackageName) {
        synchronized (this.mLock) {
            this.mIsSaving = false;
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onSaveRequestFailure() rejected - session: " + this.id + " destroyed");
                return;
            }
            LogMaker log = newLogMaker(918, servicePackageName).setType(11);
            this.mMetricsLogger.write(log);
            getUiForShowing().showError(message, this);
            removeSelf();
        }
    }

    @GuardedBy("mLock")
    private FillContext getFillContextByRequestIdLocked(int requestId) {
        if (this.mContexts == null) {
            return null;
        }
        int numContexts = this.mContexts.size();
        for (int i = 0; i < numContexts; i++) {
            FillContext context = this.mContexts.get(i);
            if (context.getRequestId() == requestId) {
                return context;
            }
        }
        return null;
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void authenticate(int requestId, int datasetIndex, IntentSender intent, Bundle extras) {
        if (Helper.sDebug) {
            Slog.d(TAG, "authenticate(): requestId=" + requestId + "; datasetIdx=" + datasetIndex + "; intentSender=" + intent);
        }
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#authenticate() rejected - session: " + this.id + " destroyed");
                return;
            }
            Intent fillInIntent = createAuthFillInIntentLocked(requestId, extras);
            if (fillInIntent == null) {
                forceRemoveSelfLocked();
                return;
            }
            this.mService.setAuthenticationSelected(this.id, this.mClientState);
            int authenticationId = AutofillManager.makeAuthenticationId(requestId, datasetIndex);
            this.mHandler.sendMessage(PooledLambda.obtainMessage(new QuadConsumer() { // from class: com.android.server.autofill.-$$Lambda$Session$LM4xf4dbxH_NTutQzBkaQNxKbV0
                public final void accept(Object obj, Object obj2, Object obj3, Object obj4) {
                    ((Session) obj).startAuthentication(((Integer) obj2).intValue(), (IntentSender) obj3, (Intent) obj4);
                }
            }, this, Integer.valueOf(authenticationId), intent, fillInIntent));
        }
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onServiceDied(RemoteFillService service) {
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void fill(int requestId, int datasetIndex, Dataset dataset) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#fill() rejected - session: " + this.id + " destroyed");
                return;
            }
            this.mHandler.sendMessage(PooledLambda.obtainMessage(new QuintConsumer() { // from class: com.android.server.autofill.-$$Lambda$knR7oLyPSG_CoFAxBA_nqSw3JBo
                public final void accept(Object obj, Object obj2, Object obj3, Object obj4, Object obj5) {
                    ((Session) obj).autoFill(((Integer) obj2).intValue(), ((Integer) obj3).intValue(), (Dataset) obj4, ((Boolean) obj5).booleanValue());
                }
            }, this, Integer.valueOf(requestId), Integer.valueOf(datasetIndex), dataset, true));
        }
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void save() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#save() rejected - session: " + this.id + " destroyed");
                return;
            }
            this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.autofill.-$$Lambda$Z6K-VL097A8ARGd4URY-lOvvM48
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((AutofillManagerServiceImpl) obj).handleSessionSave((Session) obj2);
                }
            }, this.mService, this));
        }
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void cancelSave() {
        synchronized (this.mLock) {
            this.mIsSaving = false;
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#cancelSave() rejected - session: " + this.id + " destroyed");
                return;
            }
            this.mHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.autofill.-$$Lambda$Session$cYu1t6lYVopApYW-vct82-7slZk
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((Session) obj).removeSelf();
                }
            }, this));
        }
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void requestShowFillUi(AutofillId id, int width, int height, IAutofillWindowPresenter presenter) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#requestShowFillUi() rejected - session: " + id + " destroyed");
                return;
            }
            if (id.equals(this.mCurrentViewId)) {
                try {
                    ViewState view = this.mViewStates.get(id);
                    this.mClient.requestShowFillUi(this.id, id, width, height, view.getVirtualBounds(), presenter);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error requesting to show fill UI", e);
                }
            } else if (Helper.sDebug) {
                Slog.d(TAG, "Do not show full UI on " + id + " as it is not the current view (" + this.mCurrentViewId + ") anymore");
            }
        }
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void dispatchUnhandledKey(AutofillId id, KeyEvent keyEvent) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#dispatchUnhandledKey() rejected - session: " + id + " destroyed");
                return;
            }
            if (id.equals(this.mCurrentViewId)) {
                try {
                    this.mClient.dispatchUnhandledKey(this.id, id, keyEvent);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error requesting to dispatch unhandled key", e);
                }
            } else {
                Slog.w(TAG, "Do not dispatch unhandled key on " + id + " as it is not the current view (" + this.mCurrentViewId + ") anymore");
            }
        }
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void requestHideFillUi(AutofillId id) {
        synchronized (this.mLock) {
            try {
                this.mClient.requestHideFillUi(this.id, id);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error requesting to hide fill UI", e);
            }
        }
    }

    @Override // com.android.server.autofill.ui.AutoFillUI.AutoFillUiCallback
    public void startIntentSender(IntentSender intentSender) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#startIntentSender() rejected - session: " + this.id + " destroyed");
                return;
            }
            removeSelfLocked();
            this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.autofill.-$$Lambda$Session$dldcS_opIdRI25w0DM6rSIaHIoc
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((Session) obj).doStartIntentSender((IntentSender) obj2);
                }
            }, this, intentSender));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doStartIntentSender(IntentSender intentSender) {
        try {
            synchronized (this.mLock) {
                this.mClient.startIntentSender(intentSender, (Intent) null);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Error launching auth intent", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void setAuthenticationResultLocked(Bundle data, int authenticationId) {
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#setAuthenticationResultLocked() rejected - session: " + this.id + " destroyed");
        } else if (this.mResponses == null) {
            Slog.w(TAG, "setAuthenticationResultLocked(" + authenticationId + "): no responses");
            removeSelf();
        } else {
            int requestId = AutofillManager.getRequestIdFromAuthenticationId(authenticationId);
            FillResponse authenticatedResponse = this.mResponses.get(requestId);
            if (authenticatedResponse == null || data == null) {
                removeSelf();
                return;
            }
            int datasetIdx = AutofillManager.getDatasetIdFromAuthenticationId(authenticationId);
            if (datasetIdx != 65535 && ((Dataset) authenticatedResponse.getDatasets().get(datasetIdx)) == null) {
                removeSelf();
                return;
            }
            Parcelable result = data.getParcelable("android.view.autofill.extra.AUTHENTICATION_RESULT");
            Bundle newClientState = data.getBundle("android.view.autofill.extra.CLIENT_STATE");
            if (Helper.sDebug) {
                Slog.d(TAG, "setAuthenticationResultLocked(): result=" + result + ", clientState=" + newClientState);
            }
            if (result instanceof FillResponse) {
                logAuthenticationStatusLocked(requestId, 912);
                replaceResponseLocked(authenticatedResponse, (FillResponse) result, newClientState);
            } else if (result instanceof Dataset) {
                if (datasetIdx != 65535) {
                    logAuthenticationStatusLocked(requestId, 1126);
                    if (newClientState != null) {
                        if (Helper.sDebug) {
                            Slog.d(TAG, "Updating client state from auth dataset");
                        }
                        this.mClientState = newClientState;
                    }
                    Dataset dataset = (Dataset) result;
                    authenticatedResponse.getDatasets().set(datasetIdx, dataset);
                    autoFill(requestId, datasetIdx, dataset, false);
                    return;
                }
                logAuthenticationStatusLocked(requestId, 1127);
            } else {
                if (result != null) {
                    Slog.w(TAG, "service returned invalid auth type: " + result);
                }
                logAuthenticationStatusLocked(requestId, 1128);
                processNullResponseLocked(0);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void setHasCallbackLocked(boolean hasIt) {
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#setHasCallbackLocked() rejected - session: " + this.id + " destroyed");
            return;
        }
        this.mHasCallback = hasIt;
    }

    @GuardedBy("mLock")
    private FillResponse getLastResponseLocked(String logPrefix) {
        if (this.mContexts == null) {
            if (Helper.sDebug && logPrefix != null) {
                Slog.d(TAG, logPrefix + ": no contexts");
            }
            return null;
        } else if (this.mResponses == null) {
            if (Helper.sVerbose && logPrefix != null) {
                Slog.v(TAG, logPrefix + ": no responses on session");
            }
            return null;
        } else {
            int lastResponseIdx = getLastResponseIndexLocked();
            if (lastResponseIdx < 0) {
                if (logPrefix != null) {
                    Slog.w(TAG, logPrefix + ": did not get last response. mResponses=" + this.mResponses + ", mViewStates=" + this.mViewStates);
                }
                return null;
            }
            FillResponse response = this.mResponses.valueAt(lastResponseIdx);
            if (Helper.sVerbose && logPrefix != null) {
                Slog.v(TAG, logPrefix + ": mResponses=" + this.mResponses + ", mContexts=" + this.mContexts + ", mViewStates=" + this.mViewStates);
            }
            return response;
        }
    }

    @GuardedBy("mLock")
    private SaveInfo getSaveInfoLocked() {
        FillResponse response = getLastResponseLocked(null);
        if (response == null) {
            return null;
        }
        return response.getSaveInfo();
    }

    public void logContextCommitted() {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.autofill.-$$Lambda$Session$0VAc60LP16186Azy3Ov7dL7BsAE
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((Session) obj).doLogContextCommitted();
            }
        }, this));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doLogContextCommitted() {
        synchronized (this.mLock) {
            logContextCommittedLocked();
        }
    }

    @GuardedBy("mLock")
    private void logContextCommittedLocked() {
        AutofillValue currentValue;
        FillResponse lastResponse;
        List<Dataset> datasets;
        ArrayMap<AutofillId, ArraySet<String>> manuallyFilledIds;
        FillResponse lastResponse2;
        AutofillValue currentValue2;
        AutofillValue currentValue3;
        ArrayList<AutofillValue> values;
        Dataset dataset;
        ArrayList<AutofillId> changedFieldIds;
        ArrayList<String> changedDatasetIds;
        ArrayList<AutofillId> changedFieldIds2;
        ArrayList<String> changedDatasetIds2;
        FillResponse lastResponse3 = getLastResponseLocked("logContextCommited()");
        if (lastResponse3 == null) {
            return;
        }
        int flags = lastResponse3.getFlags();
        if ((flags & 1) == 0) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "logContextCommittedLocked(): ignored by flags " + flags);
                return;
            }
            return;
        }
        ArrayList<AutofillId> changedFieldIds3 = null;
        ArrayList<String> changedDatasetIds3 = null;
        int responseCount = this.mResponses.size();
        boolean hasAtLeastOneDataset = false;
        ArraySet<String> ignoredDatasets = null;
        int i = 0;
        while (i < responseCount) {
            List<Dataset> datasets2 = this.mResponses.valueAt(i).getDatasets();
            if (datasets2 == null) {
                changedFieldIds = changedFieldIds3;
                changedDatasetIds = changedDatasetIds3;
            } else if (datasets2.isEmpty()) {
                changedFieldIds = changedFieldIds3;
                changedDatasetIds = changedDatasetIds3;
            } else {
                ArraySet<String> ignoredDatasets2 = ignoredDatasets;
                int j = 0;
                while (j < datasets2.size()) {
                    Dataset dataset2 = datasets2.get(j);
                    String datasetId = dataset2.getId();
                    if (datasetId != null) {
                        changedFieldIds2 = changedFieldIds3;
                        changedDatasetIds2 = changedDatasetIds3;
                        if (this.mSelectedDatasetIds == null || !this.mSelectedDatasetIds.contains(datasetId)) {
                            if (Helper.sVerbose) {
                                Slog.v(TAG, "adding ignored dataset " + datasetId);
                            }
                            if (ignoredDatasets2 == null) {
                                ignoredDatasets2 = new ArraySet<>();
                            }
                            ignoredDatasets2.add(datasetId);
                        }
                        hasAtLeastOneDataset = true;
                    } else if (Helper.sVerbose) {
                        changedFieldIds2 = changedFieldIds3;
                        StringBuilder sb = new StringBuilder();
                        changedDatasetIds2 = changedDatasetIds3;
                        sb.append("logContextCommitted() skipping idless dataset ");
                        sb.append(dataset2);
                        Slog.v(TAG, sb.toString());
                    } else {
                        changedFieldIds2 = changedFieldIds3;
                        changedDatasetIds2 = changedDatasetIds3;
                    }
                    j++;
                    changedFieldIds3 = changedFieldIds2;
                    changedDatasetIds3 = changedDatasetIds2;
                }
                changedFieldIds = changedFieldIds3;
                changedDatasetIds = changedDatasetIds3;
                ignoredDatasets = ignoredDatasets2;
                i++;
                changedFieldIds3 = changedFieldIds;
                changedDatasetIds3 = changedDatasetIds;
            }
            if (Helper.sVerbose) {
                Slog.v(TAG, "logContextCommitted() no datasets at " + i);
            }
            i++;
            changedFieldIds3 = changedFieldIds;
            changedDatasetIds3 = changedDatasetIds;
        }
        ArrayList<AutofillId> changedFieldIds4 = changedFieldIds3;
        ArrayList<String> changedDatasetIds4 = changedDatasetIds3;
        AutofillId[] fieldClassificationIds = lastResponse3.getFieldClassificationIds();
        if (!hasAtLeastOneDataset && fieldClassificationIds == null) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "logContextCommittedLocked(): skipped (no datasets nor fields classification ids)");
                return;
            }
            return;
        }
        UserData userData = this.mService.getUserData();
        ArrayMap<AutofillId, ArraySet<String>> manuallyFilledIds2 = null;
        ArraySet<String> ignoredDatasets3 = ignoredDatasets;
        ArrayList<AutofillId> changedFieldIds5 = changedFieldIds4;
        ArrayList<String> changedDatasetIds5 = changedDatasetIds4;
        int i2 = 0;
        while (i2 < this.mViewStates.size()) {
            ViewState viewState = this.mViewStates.valueAt(i2);
            int state = viewState.getState();
            if ((state & 8) != 0) {
                if ((state & 4) != 0) {
                    String datasetId2 = viewState.getDatasetId();
                    if (datasetId2 == null) {
                        Slog.w(TAG, "logContextCommitted(): no dataset id on " + viewState);
                    } else {
                        AutofillValue autofilledValue = viewState.getAutofilledValue();
                        AutofillValue currentValue4 = viewState.getCurrentValue();
                        if (autofilledValue == null || !autofilledValue.equals(currentValue4)) {
                            if (Helper.sDebug) {
                                Slog.d(TAG, "logContextCommitted() found changed state: " + viewState);
                            }
                            if (changedFieldIds5 == null) {
                                changedFieldIds5 = new ArrayList<>();
                                changedDatasetIds5 = new ArrayList<>();
                            }
                            ArrayList<AutofillId> changedFieldIds6 = changedFieldIds5;
                            ArrayList<String> changedDatasetIds6 = changedDatasetIds5;
                            changedFieldIds6.add(viewState.id);
                            changedDatasetIds6.add(datasetId2);
                            changedFieldIds5 = changedFieldIds6;
                            changedDatasetIds5 = changedDatasetIds6;
                        } else if (Helper.sDebug) {
                            Slog.d(TAG, "logContextCommitted(): ignoring changed " + viewState + " because it has same value that was autofilled");
                        }
                    }
                } else {
                    AutofillValue currentValue5 = viewState.getCurrentValue();
                    if (currentValue5 == null) {
                        if (Helper.sDebug) {
                            Slog.d(TAG, "logContextCommitted(): skipping view without current value ( " + viewState + ")");
                        }
                    } else if (hasAtLeastOneDataset) {
                        int j2 = 0;
                        while (j2 < responseCount) {
                            FillResponse response = this.mResponses.valueAt(j2);
                            List<Dataset> datasets3 = response.getDatasets();
                            if (datasets3 == null) {
                                currentValue = currentValue5;
                                lastResponse = lastResponse3;
                            } else if (datasets3.isEmpty()) {
                                currentValue = currentValue5;
                                lastResponse = lastResponse3;
                            } else {
                                int k = 0;
                                while (k < datasets3.size()) {
                                    Dataset dataset3 = datasets3.get(k);
                                    FillResponse response2 = response;
                                    String datasetId3 = dataset3.getId();
                                    if (datasetId3 == null) {
                                        if (Helper.sVerbose) {
                                            datasets = datasets3;
                                            manuallyFilledIds = manuallyFilledIds2;
                                            StringBuilder sb2 = new StringBuilder();
                                            lastResponse2 = lastResponse3;
                                            sb2.append("logContextCommitted() skipping idless dataset ");
                                            sb2.append(dataset3);
                                            Slog.v(TAG, sb2.toString());
                                        } else {
                                            datasets = datasets3;
                                            manuallyFilledIds = manuallyFilledIds2;
                                            lastResponse2 = lastResponse3;
                                        }
                                        currentValue2 = currentValue5;
                                    } else {
                                        datasets = datasets3;
                                        manuallyFilledIds = manuallyFilledIds2;
                                        lastResponse2 = lastResponse3;
                                        ArrayList<AutofillValue> values2 = dataset3.getFieldValues();
                                        int l = 0;
                                        while (l < values2.size()) {
                                            AutofillValue candidate = values2.get(l);
                                            if (currentValue5.equals(candidate)) {
                                                if (Helper.sDebug) {
                                                    currentValue3 = currentValue5;
                                                    values = values2;
                                                    StringBuilder sb3 = new StringBuilder();
                                                    dataset = dataset3;
                                                    sb3.append("field ");
                                                    sb3.append(viewState.id);
                                                    sb3.append(" was manually filled with value set by dataset ");
                                                    sb3.append(datasetId3);
                                                    Slog.d(TAG, sb3.toString());
                                                } else {
                                                    currentValue3 = currentValue5;
                                                    values = values2;
                                                    dataset = dataset3;
                                                }
                                                ArrayMap<AutofillId, ArraySet<String>> manuallyFilledIds3 = manuallyFilledIds == null ? new ArrayMap<>() : manuallyFilledIds;
                                                ArraySet<String> datasetIds = manuallyFilledIds3.get(viewState.id);
                                                if (datasetIds == null) {
                                                    datasetIds = new ArraySet<>(1);
                                                    manuallyFilledIds3.put(viewState.id, datasetIds);
                                                }
                                                datasetIds.add(datasetId3);
                                                manuallyFilledIds = manuallyFilledIds3;
                                            } else {
                                                currentValue3 = currentValue5;
                                                values = values2;
                                                dataset = dataset3;
                                            }
                                            l++;
                                            currentValue5 = currentValue3;
                                            values2 = values;
                                            dataset3 = dataset;
                                        }
                                        currentValue2 = currentValue5;
                                        if (this.mSelectedDatasetIds == null || !this.mSelectedDatasetIds.contains(datasetId3)) {
                                            if (Helper.sVerbose) {
                                                Slog.v(TAG, "adding ignored dataset " + datasetId3);
                                            }
                                            ArraySet<String> ignoredDatasets4 = ignoredDatasets3 == null ? new ArraySet<>() : ignoredDatasets3;
                                            ignoredDatasets4.add(datasetId3);
                                            ignoredDatasets3 = ignoredDatasets4;
                                        }
                                    }
                                    manuallyFilledIds2 = manuallyFilledIds;
                                    k++;
                                    response = response2;
                                    datasets3 = datasets;
                                    lastResponse3 = lastResponse2;
                                    currentValue5 = currentValue2;
                                }
                                currentValue = currentValue5;
                                lastResponse = lastResponse3;
                                j2++;
                                lastResponse3 = lastResponse;
                                currentValue5 = currentValue;
                            }
                            if (Helper.sVerbose) {
                                Slog.v(TAG, "logContextCommitted() no datasets at " + j2);
                            }
                            j2++;
                            lastResponse3 = lastResponse;
                            currentValue5 = currentValue;
                        }
                    }
                }
            }
            i2++;
            lastResponse3 = lastResponse3;
        }
        ArrayList<AutofillId> manuallyFilledFieldIds = null;
        ArrayList<ArrayList<String>> manuallyFilledDatasetIds = null;
        if (manuallyFilledIds2 != null) {
            int size = manuallyFilledIds2.size();
            manuallyFilledFieldIds = new ArrayList<>(size);
            manuallyFilledDatasetIds = new ArrayList<>(size);
            int i3 = 0;
            while (true) {
                int i4 = i3;
                if (i4 >= size) {
                    break;
                }
                AutofillId fieldId = manuallyFilledIds2.keyAt(i4);
                manuallyFilledFieldIds.add(fieldId);
                manuallyFilledDatasetIds.add(new ArrayList<>(manuallyFilledIds2.valueAt(i4)));
                i3 = i4 + 1;
            }
        }
        ArrayList<AutofillId> manuallyFilledFieldIds2 = manuallyFilledFieldIds;
        ArrayList<ArrayList<String>> manuallyFilledDatasetIds2 = manuallyFilledDatasetIds;
        FieldClassificationStrategy fcStrategy = this.mService.getFieldClassificationStrategy();
        if (userData == null || fcStrategy == null) {
            this.mService.logContextCommittedLocked(this.id, this.mClientState, this.mSelectedDatasetIds, ignoredDatasets3, changedFieldIds5, changedDatasetIds5, manuallyFilledFieldIds2, manuallyFilledDatasetIds2, this.mComponentName, this.mCompatMode);
        } else {
            logFieldClassificationScoreLocked(fcStrategy, ignoredDatasets3, changedFieldIds5, changedDatasetIds5, manuallyFilledFieldIds2, manuallyFilledDatasetIds2, userData, this.mViewStates.values());
        }
    }

    private void logFieldClassificationScoreLocked(FieldClassificationStrategy fcStrategy, final ArraySet<String> ignoredDatasets, final ArrayList<AutofillId> changedFieldIds, final ArrayList<String> changedDatasetIds, final ArrayList<AutofillId> manuallyFilledFieldIds, final ArrayList<ArrayList<String>> manuallyFilledDatasetIds, UserData userData, Collection<ViewState> viewStates) {
        final String[] userValues = userData.getValues();
        final String[] categoryIds = userData.getCategoryIds();
        if (userValues == null || categoryIds == null || userValues.length != categoryIds.length) {
            int valuesLength = userValues == null ? -1 : userValues.length;
            int idsLength = categoryIds != null ? categoryIds.length : -1;
            Slog.w(TAG, "setScores(): user data mismatch: values.length = " + valuesLength + ", ids.length = " + idsLength);
            return;
        }
        int maxFieldsSize = UserData.getMaxFieldClassificationIdsSize();
        final ArrayList<AutofillId> detectedFieldIds = new ArrayList<>(maxFieldsSize);
        final ArrayList<FieldClassification> detectedFieldClassifications = new ArrayList<>(maxFieldsSize);
        String algorithm = userData.getFieldClassificationAlgorithm();
        Bundle algorithmArgs = userData.getAlgorithmArgs();
        final int viewsSize = viewStates.size();
        final AutofillId[] autofillIds = new AutofillId[viewsSize];
        ArrayList<AutofillValue> currentValues = new ArrayList<>(viewsSize);
        int k = 0;
        for (ViewState viewState : viewStates) {
            currentValues.add(viewState.getCurrentValue());
            autofillIds[k] = viewState.id;
            k++;
        }
        RemoteCallback callback = new RemoteCallback(new RemoteCallback.OnResultListener() { // from class: com.android.server.autofill.-$$Lambda$Session$mm9ZGBWriIznaZv8NlUB1a4AvJI
            public final void onResult(Bundle bundle) {
                Session.lambda$logFieldClassificationScoreLocked$1(Session.this, ignoredDatasets, changedFieldIds, changedDatasetIds, manuallyFilledFieldIds, manuallyFilledDatasetIds, viewsSize, autofillIds, userValues, categoryIds, detectedFieldIds, detectedFieldClassifications, bundle);
            }
        });
        fcStrategy.getScores(callback, algorithm, algorithmArgs, currentValues, userValues);
    }

    /* JADX WARN: Incorrect condition in loop: B:45:0x0139 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static /* synthetic */ void lambda$logFieldClassificationScoreLocked$1(com.android.server.autofill.Session r29, android.util.ArraySet r30, java.util.ArrayList r31, java.util.ArrayList r32, java.util.ArrayList r33, java.util.ArrayList r34, int r35, android.view.autofill.AutofillId[] r36, java.lang.String[] r37, java.lang.String[] r38, java.util.ArrayList r39, java.util.ArrayList r40, android.os.Bundle r41) {
        /*
            Method dump skipped, instructions count: 448
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.autofill.Session.lambda$logFieldClassificationScoreLocked$1(com.android.server.autofill.Session, android.util.ArraySet, java.util.ArrayList, java.util.ArrayList, java.util.ArrayList, java.util.ArrayList, int, android.view.autofill.AutofillId[], java.lang.String[], java.lang.String[], java.util.ArrayList, java.util.ArrayList, android.os.Bundle):void");
    }

    /* JADX WARN: Removed duplicated region for block: B:104:0x02a6  */
    /* JADX WARN: Removed duplicated region for block: B:168:0x04d0  */
    @com.android.internal.annotations.GuardedBy("mLock")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean showSaveLocked() {
        /*
            Method dump skipped, instructions count: 1306
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.autofill.Session.showSaveLocked():boolean");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logSaveShown() {
        this.mService.logSaveShown(this.id, this.mClientState);
    }

    private ArrayMap<AutofillId, InternalSanitizer> createSanitizers(SaveInfo saveInfo) {
        InternalSanitizer[] sanitizerKeys;
        if (saveInfo == null || (sanitizerKeys = saveInfo.getSanitizerKeys()) == null) {
            return null;
        }
        int size = sanitizerKeys.length;
        ArrayMap<AutofillId, InternalSanitizer> sanitizers = new ArrayMap<>(size);
        if (Helper.sDebug) {
            Slog.d(TAG, "Service provided " + size + " sanitizers");
        }
        AutofillId[][] sanitizerValues = saveInfo.getSanitizerValues();
        for (int i = 0; i < size; i++) {
            InternalSanitizer sanitizer = sanitizerKeys[i];
            AutofillId[] ids = sanitizerValues[i];
            if (Helper.sDebug) {
                Slog.d(TAG, "sanitizer #" + i + " (" + sanitizer + ") for ids " + Arrays.toString(ids));
            }
            for (AutofillId id : ids) {
                sanitizers.put(id, sanitizer);
            }
        }
        return sanitizers;
    }

    private AutofillValue getSanitizedValue(ArrayMap<AutofillId, InternalSanitizer> sanitizers, AutofillId id, AutofillValue value) {
        if (sanitizers == null) {
            return value;
        }
        InternalSanitizer sanitizer = sanitizers.get(id);
        if (sanitizer == null) {
            return value;
        }
        AutofillValue sanitized = sanitizer.sanitize(value);
        if (Helper.sDebug) {
            Slog.d(TAG, "Value for " + id + "(" + value + ") sanitized to " + sanitized);
        }
        return sanitized;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public boolean isSavingLocked() {
        return this.mIsSaving;
    }

    @GuardedBy("mLock")
    private AutofillValue getValueFromContextsLocked(AutofillId id) {
        int numContexts = this.mContexts.size();
        for (int i = numContexts - 1; i >= 0; i--) {
            FillContext context = this.mContexts.get(i);
            AssistStructure.ViewNode node = Helper.findViewNodeByAutofillId(context.getStructure(), id);
            if (node != null) {
                AutofillValue value = node.getAutofillValue();
                if (Helper.sDebug) {
                    Slog.d(TAG, "getValueFromContexts(" + id + ") at " + i + ": " + value);
                }
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    @GuardedBy("mLock")
    private CharSequence[] getAutofillOptionsFromContextsLocked(AutofillId id) {
        int numContexts = this.mContexts.size();
        for (int i = numContexts - 1; i >= 0; i--) {
            FillContext context = this.mContexts.get(i);
            AssistStructure.ViewNode node = Helper.findViewNodeByAutofillId(context.getStructure(), id);
            if (node != null && node.getAutofillOptions() != null) {
                return node.getAutofillOptions();
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void callSaveLocked() {
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#callSaveLocked() rejected - session: " + this.id + " destroyed");
            return;
        }
        if (Helper.sVerbose) {
            Slog.v(TAG, "callSaveLocked(): mViewStates=" + this.mViewStates);
        }
        if (this.mContexts == null) {
            Slog.w(TAG, "callSaveLocked(): no contexts");
            return;
        }
        ArrayMap<AutofillId, InternalSanitizer> sanitizers = createSanitizers(getSaveInfoLocked());
        int numContexts = this.mContexts.size();
        for (int contextNum = 0; contextNum < numContexts; contextNum++) {
            FillContext context = this.mContexts.get(contextNum);
            AssistStructure.ViewNode[] nodes = context.findViewNodesByAutofillIds(getIdsOfAllViewStatesLocked());
            if (Helper.sVerbose) {
                Slog.v(TAG, "callSaveLocked(): updating " + context);
            }
            for (int viewStateNum = 0; viewStateNum < this.mViewStates.size(); viewStateNum++) {
                ViewState viewState = this.mViewStates.valueAt(viewStateNum);
                AutofillId id = viewState.id;
                AutofillValue value = viewState.getCurrentValue();
                if (value == null) {
                    if (Helper.sVerbose) {
                        Slog.v(TAG, "callSaveLocked(): skipping " + id);
                    }
                } else {
                    AssistStructure.ViewNode node = nodes[viewStateNum];
                    if (node == null) {
                        Slog.w(TAG, "callSaveLocked(): did not find node with id " + id);
                    } else {
                        if (Helper.sVerbose) {
                            Slog.v(TAG, "callSaveLocked(): updating " + id + " to " + value);
                        }
                        AutofillValue sanitizedValue = viewState.getSanitizedValue();
                        if (sanitizedValue == null) {
                            sanitizedValue = getSanitizedValue(sanitizers, id, value);
                        }
                        if (sanitizedValue != null) {
                            node.updateAutofillValue(sanitizedValue);
                        } else if (Helper.sDebug) {
                            Slog.d(TAG, "Not updating field " + id + " because it failed sanitization");
                        }
                    }
                }
            }
            context.getStructure().sanitizeForParceling(false);
            if (Helper.sVerbose) {
                Slog.v(TAG, "Dumping structure of " + context + " before calling service.save()");
                context.getStructure().dump(false);
            }
        }
        cancelCurrentRequestLocked();
        SaveRequest saveRequest = new SaveRequest(new ArrayList(this.mContexts), this.mClientState, this.mSelectedDatasetIds);
        this.mRemoteFillService.onSaveRequest(saveRequest);
    }

    @GuardedBy("mLock")
    private void requestNewFillResponseOnViewEnteredIfNecessaryLocked(AutofillId id, ViewState viewState, int flags) {
        if ((flags & 1) != 0) {
            if (Helper.sDebug) {
                Slog.d(TAG, "Re-starting session on view " + id + " and flags " + flags);
            }
            viewState.setState(256);
            requestNewFillResponseLocked(flags);
        } else if (shouldStartNewPartitionLocked(id)) {
            if (Helper.sDebug) {
                Slog.d(TAG, "Starting partition for view id " + id + ": " + viewState.getStateAsString());
            }
            viewState.setState(32);
            requestNewFillResponseLocked(flags);
        } else if (Helper.sVerbose) {
            Slog.v(TAG, "Not starting new partition for view " + id + ": " + viewState.getStateAsString());
        }
    }

    @GuardedBy("mLock")
    private boolean shouldStartNewPartitionLocked(AutofillId id) {
        if (this.mResponses == null) {
            return true;
        }
        int numResponses = this.mResponses.size();
        if (numResponses >= Helper.sPartitionMaxCount) {
            Slog.e(TAG, "Not starting a new partition on " + id + " because session " + this.id + " reached maximum of " + Helper.sPartitionMaxCount);
            return false;
        }
        for (int responseNum = 0; responseNum < numResponses; responseNum++) {
            FillResponse response = this.mResponses.valueAt(responseNum);
            if (ArrayUtils.contains(response.getIgnoredIds(), id)) {
                return false;
            }
            SaveInfo saveInfo = response.getSaveInfo();
            if (saveInfo != null && (ArrayUtils.contains(saveInfo.getOptionalIds(), id) || ArrayUtils.contains(saveInfo.getRequiredIds(), id))) {
                return false;
            }
            List<Dataset> datasets = response.getDatasets();
            if (datasets != null) {
                int numDatasets = datasets.size();
                for (int dataSetNum = 0; dataSetNum < numDatasets; dataSetNum++) {
                    ArrayList<AutofillId> fields = datasets.get(dataSetNum).getFieldIds();
                    if (fields != null && fields.contains(id)) {
                        return false;
                    }
                }
            }
            if (ArrayUtils.contains(response.getAuthenticationIds(), id)) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void updateLocked(AutofillId id, Rect virtualBounds, AutofillValue value, int action, int flags) {
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#updateLocked() rejected - session: " + id + " destroyed");
            return;
        }
        if (Helper.sVerbose) {
            Slog.v(TAG, "updateLocked(): id=" + id + ", action=" + actionAsString(action) + ", flags=" + flags);
        }
        ViewState viewState = this.mViewStates.get(id);
        if (viewState == null) {
            if (action == 1 || action == 4 || action == 2) {
                if (Helper.sVerbose) {
                    Slog.v(TAG, "Creating viewState for " + id);
                }
                boolean isIgnored = isIgnoredLocked(id);
                viewState = new ViewState(this, id, this, isIgnored ? 128 : 1);
                this.mViewStates.put(id, viewState);
                if (isIgnored) {
                    if (Helper.sDebug) {
                        Slog.d(TAG, "updateLocked(): ignoring view " + viewState);
                        return;
                    }
                    return;
                }
            } else if (Helper.sVerbose) {
                Slog.v(TAG, "Ignoring specific action when viewState=null");
                return;
            } else {
                return;
            }
        }
        String filterText = null;
        switch (action) {
            case 1:
                this.mCurrentViewId = viewState.id;
                viewState.update(value, virtualBounds, flags);
                viewState.setState(16);
                requestNewFillResponseLocked(flags);
                return;
            case 2:
                if (Helper.sVerbose && virtualBounds != null) {
                    Slog.v(TAG, "entered on virtual child " + id + ": " + virtualBounds);
                }
                if (this.mCompatMode && (viewState.getState() & 512) != 0) {
                    if (Helper.sDebug) {
                        Slog.d(TAG, "Ignoring VIEW_ENTERED on URL BAR (id=" + id + ")");
                        return;
                    }
                    return;
                }
                requestNewFillResponseOnViewEnteredIfNecessaryLocked(id, viewState, flags);
                if (this.mCurrentViewId != viewState.id) {
                    this.mUi.hideFillUi(this);
                    this.mCurrentViewId = viewState.id;
                }
                viewState.update(value, virtualBounds, flags);
                return;
            case 3:
                if (this.mCurrentViewId == viewState.id) {
                    if (Helper.sVerbose) {
                        Slog.d(TAG, "Exiting view " + id);
                    }
                    this.mUi.hideFillUi(this);
                    this.mCurrentViewId = null;
                    return;
                }
                return;
            case 4:
                if (this.mCompatMode && (viewState.getState() & 512) != 0) {
                    String currentUrl = this.mUrlBar == null ? null : this.mUrlBar.getText().toString().trim();
                    if (currentUrl == null) {
                        wtf(null, "URL bar value changed, but current value is null", new Object[0]);
                        return;
                    } else if (value == null || !value.isText()) {
                        wtf(null, "URL bar value changed to null or non-text: %s", value);
                        return;
                    } else {
                        String newUrl = value.getTextValue().toString();
                        if (newUrl.equals(currentUrl)) {
                            if (Helper.sDebug) {
                                Slog.d(TAG, "Ignoring change on URL bar as it's the same");
                                return;
                            }
                            return;
                        } else if (this.mSaveOnAllViewsInvisible) {
                            if (Helper.sDebug) {
                                Slog.d(TAG, "Ignoring change on URL because session will finish when views are gone");
                                return;
                            }
                            return;
                        } else {
                            if (Helper.sDebug) {
                                Slog.d(TAG, "Finishing session because URL bar changed");
                            }
                            forceRemoveSelfLocked(5);
                            return;
                        }
                    }
                } else if (!Objects.equals(value, viewState.getCurrentValue())) {
                    if ((value == null || value.isEmpty()) && viewState.getCurrentValue() != null && viewState.getCurrentValue().isText() && viewState.getCurrentValue().getTextValue() != null && getSaveInfoLocked() != null) {
                        int length = viewState.getCurrentValue().getTextValue().length();
                        if (Helper.sDebug) {
                            Slog.d(TAG, "updateLocked(" + id + "): resetting value that was " + length + " chars long");
                        }
                        LogMaker log = newLogMaker(1124).addTaggedData(1125, Integer.valueOf(length));
                        this.mMetricsLogger.write(log);
                    }
                    viewState.setCurrentValue(value);
                    AutofillValue filledValue = viewState.getAutofilledValue();
                    if (filledValue != null && filledValue.equals(value)) {
                        if (Helper.sVerbose) {
                            Slog.v(TAG, "ignoring autofilled change on id " + id);
                            return;
                        }
                        return;
                    }
                    viewState.setState(8);
                    if (value == null || !value.isText()) {
                        filterText = null;
                    } else {
                        CharSequence text = value.getTextValue();
                        if (text != null) {
                            filterText = text.toString();
                        }
                    }
                    getUiForShowing().filterFillUi(filterText, this);
                    return;
                } else {
                    return;
                }
            default:
                Slog.w(TAG, "updateLocked(): unknown action: " + action);
                return;
        }
    }

    @GuardedBy("mLock")
    private boolean isIgnoredLocked(AutofillId id) {
        FillResponse response = getLastResponseLocked(null);
        if (response == null) {
            return false;
        }
        return ArrayUtils.contains(response.getIgnoredIds(), id);
    }

    @Override // com.android.server.autofill.ViewState.Listener
    public void onFillReady(FillResponse response, AutofillId filledId, AutofillValue value) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillReady() rejected - session: " + this.id + " destroyed");
                return;
            }
            String filterText = null;
            if (value != null && value.isText()) {
                filterText = value.getTextValue().toString();
            }
            getUiForShowing().showFillUi(filledId, response, filterText, this.mService.getServicePackageName(), this.mComponentName, this.mService.getServiceLabel(), this.mService.getServiceIcon(), this, this.id, this.mCompatMode);
            synchronized (this.mLock) {
                if (this.mUiShownTime == 0) {
                    this.mUiShownTime = SystemClock.elapsedRealtime();
                    long duration = this.mUiShownTime - this.mStartTime;
                    if (Helper.sDebug) {
                        StringBuilder msg = new StringBuilder("1st UI for ");
                        msg.append(this.mActivityToken);
                        msg.append(" shown in ");
                        TimeUtils.formatDuration(duration, msg);
                        Slog.d(TAG, msg.toString());
                    }
                    StringBuilder historyLog = new StringBuilder("id=");
                    historyLog.append(this.id);
                    historyLog.append(" app=");
                    historyLog.append(this.mActivityToken);
                    historyLog.append(" svc=");
                    historyLog.append(this.mService.getServicePackageName());
                    historyLog.append(" latency=");
                    TimeUtils.formatDuration(duration, historyLog);
                    this.mUiLatencyHistory.log(historyLog.toString());
                    addTaggedDataToRequestLogLocked(response.getRequestId(), 1145, Long.valueOf(duration));
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isDestroyed() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mDestroyed;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IAutoFillManagerClient getClient() {
        IAutoFillManagerClient iAutoFillManagerClient;
        synchronized (this.mLock) {
            iAutoFillManagerClient = this.mClient;
        }
        return iAutoFillManagerClient;
    }

    private void notifyUnavailableToClient(int sessionFinishedState) {
        synchronized (this.mLock) {
            if (this.mCurrentViewId == null) {
                return;
            }
            try {
                if (this.mHasCallback) {
                    this.mClient.notifyNoFillUi(this.id, this.mCurrentViewId, sessionFinishedState);
                } else if (sessionFinishedState != 0) {
                    this.mClient.setSessionFinished(sessionFinishedState);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client no fill UI: id=" + this.mCurrentViewId, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void updateTrackedIdsLocked() {
        ArraySet<AutofillId> fillableIds;
        AutofillId saveTriggerId = null;
        FillResponse response = getLastResponseLocked(null);
        if (response == null) {
            return;
        }
        this.mSaveOnAllViewsInvisible = false;
        boolean saveOnFinish = true;
        SaveInfo saveInfo = response.getSaveInfo();
        if (saveInfo != null) {
            saveTriggerId = saveInfo.getTriggerId();
            if (saveTriggerId != null) {
                writeLog(1228);
            }
            this.mSaveOnAllViewsInvisible = (saveInfo.getFlags() & 1) != 0;
            if (this.mSaveOnAllViewsInvisible) {
                trackedViews = 0 == 0 ? new ArraySet<>() : null;
                if (saveInfo.getRequiredIds() != null) {
                    Collections.addAll(trackedViews, saveInfo.getRequiredIds());
                }
                if (saveInfo.getOptionalIds() != null) {
                    Collections.addAll(trackedViews, saveInfo.getOptionalIds());
                }
            }
            if ((saveInfo.getFlags() & 2) != 0) {
                saveOnFinish = false;
            }
        }
        List<Dataset> datasets = response.getDatasets();
        if (datasets != null) {
            ArraySet<AutofillId> fillableIds2 = null;
            for (int i = 0; i < datasets.size(); i++) {
                Dataset dataset = datasets.get(i);
                ArrayList<AutofillId> fieldIds = dataset.getFieldIds();
                if (fieldIds != null) {
                    ArraySet<AutofillId> fillableIds3 = fillableIds2;
                    for (int j = 0; j < fieldIds.size(); j++) {
                        AutofillId id = fieldIds.get(j);
                        if (trackedViews == null || !trackedViews.contains(id)) {
                            fillableIds3 = ArrayUtils.add(fillableIds3, id);
                        }
                    }
                    fillableIds2 = fillableIds3;
                }
            }
            fillableIds = fillableIds2;
        } else {
            fillableIds = null;
        }
        try {
            if (Helper.sVerbose) {
                Slog.v(TAG, "updateTrackedIdsLocked(): " + trackedViews + " => " + fillableIds + " triggerId: " + saveTriggerId + " saveOnFinish:" + saveOnFinish);
            }
            this.mClient.setTrackedViews(this.id, Helper.toArray(trackedViews), this.mSaveOnAllViewsInvisible, saveOnFinish, Helper.toArray(fillableIds), saveTriggerId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Cannot set tracked ids", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void setAutofillFailureLocked(List<AutofillId> ids) {
        for (int i = 0; i < ids.size(); i++) {
            AutofillId id = ids.get(i);
            ViewState viewState = this.mViewStates.get(id);
            if (viewState == null) {
                Slog.w(TAG, "setAutofillFailure(): no view for id " + id);
            } else {
                viewState.resetState(4);
                int state = viewState.getState();
                viewState.setState(state | 1024);
                if (Helper.sVerbose) {
                    Slog.v(TAG, "Changed state of " + id + " to " + viewState.getStateAsString());
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void replaceResponseLocked(FillResponse oldResponse, FillResponse newResponse, Bundle newClientState) {
        setViewStatesLocked(oldResponse, 1, true);
        newResponse.setRequestId(oldResponse.getRequestId());
        this.mResponses.put(newResponse.getRequestId(), newResponse);
        processResponseLocked(newResponse, newClientState, 0);
    }

    private void processNullResponseLocked(int flags) {
        if (Helper.sVerbose) {
            Slog.v(TAG, "canceling session " + this.id + " when server returned null");
        }
        if ((flags & 1) != 0) {
            getUiForShowing().showError(17039528, this);
        }
        this.mService.resetLastResponse();
        notifyUnavailableToClient(2);
        removeSelf();
    }

    @GuardedBy("mLock")
    private void processResponseLocked(FillResponse newResponse, Bundle newClientState, int flags) {
        this.mUi.hideAll(this);
        int requestId = newResponse.getRequestId();
        if (Helper.sVerbose) {
            Slog.v(TAG, "processResponseLocked(): mCurrentViewId=" + this.mCurrentViewId + ",flags=" + flags + ", reqId=" + requestId + ", resp=" + newResponse + ",newClientState=" + newClientState);
        }
        if (this.mResponses == null) {
            this.mResponses = new SparseArray<>(2);
        }
        this.mResponses.put(requestId, newResponse);
        this.mClientState = newClientState != null ? newClientState : newResponse.getClientState();
        setViewStatesLocked(newResponse, 2, false);
        updateTrackedIdsLocked();
        if (this.mCurrentViewId == null) {
            return;
        }
        ViewState currentView = this.mViewStates.get(this.mCurrentViewId);
        currentView.maybeCallOnFillReady(flags);
    }

    @GuardedBy("mLock")
    private void setViewStatesLocked(FillResponse response, int state, boolean clearResponse) {
        AutofillId[] authenticationIds;
        List<Dataset> datasets = response.getDatasets();
        if (datasets != null) {
            for (int i = 0; i < datasets.size(); i++) {
                Dataset dataset = datasets.get(i);
                if (dataset == null) {
                    Slog.w(TAG, "Ignoring null dataset on " + datasets);
                } else {
                    setViewStatesLocked(response, dataset, state, clearResponse);
                }
            }
        } else if (response.getAuthentication() != null) {
            for (AutofillId autofillId : response.getAuthenticationIds()) {
                ViewState viewState = createOrUpdateViewStateLocked(autofillId, state, null);
                if (!clearResponse) {
                    viewState.setResponse(response);
                } else {
                    viewState.setResponse(null);
                }
            }
        }
        SaveInfo saveInfo = response.getSaveInfo();
        if (saveInfo != null) {
            AutofillId[] requiredIds = saveInfo.getRequiredIds();
            if (requiredIds != null) {
                for (AutofillId id : requiredIds) {
                    createOrUpdateViewStateLocked(id, state, null);
                }
            }
            AutofillId[] optionalIds = saveInfo.getOptionalIds();
            if (optionalIds != null) {
                for (AutofillId id2 : optionalIds) {
                    createOrUpdateViewStateLocked(id2, state, null);
                }
            }
        }
        AutofillId[] authIds = response.getAuthenticationIds();
        if (authIds != null) {
            for (AutofillId id3 : authIds) {
                createOrUpdateViewStateLocked(id3, state, null);
            }
        }
    }

    @GuardedBy("mLock")
    private void setViewStatesLocked(FillResponse response, Dataset dataset, int state, boolean clearResponse) {
        ArrayList<AutofillId> ids = dataset.getFieldIds();
        ArrayList<AutofillValue> values = dataset.getFieldValues();
        for (int j = 0; j < ids.size(); j++) {
            AutofillId id = ids.get(j);
            AutofillValue value = values.get(j);
            ViewState viewState = createOrUpdateViewStateLocked(id, state, value);
            String datasetId = dataset.getId();
            if (datasetId != null) {
                viewState.setDatasetId(datasetId);
            }
            if (response != null) {
                viewState.setResponse(response);
            } else if (clearResponse) {
                viewState.setResponse(null);
            }
        }
    }

    @GuardedBy("mLock")
    private ViewState createOrUpdateViewStateLocked(AutofillId id, int state, AutofillValue value) {
        ViewState viewState = this.mViewStates.get(id);
        if (viewState != null) {
            viewState.setState(state);
        } else {
            viewState = new ViewState(this, id, this, state);
            if (Helper.sVerbose) {
                Slog.v(TAG, "Adding autofillable view with id " + id + " and state " + state);
            }
            this.mViewStates.put(id, viewState);
        }
        if ((state & 4) != 0) {
            viewState.setAutofilledValue(value);
        }
        return viewState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void autoFill(int requestId, int datasetIndex, Dataset dataset, boolean generateEvent) {
        if (Helper.sDebug) {
            Slog.d(TAG, "autoFill(): requestId=" + requestId + "; datasetIdx=" + datasetIndex + "; dataset=" + dataset);
        }
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#autoFill() rejected - session: " + this.id + " destroyed");
            } else if (dataset.getAuthentication() == null) {
                if (generateEvent) {
                    this.mService.logDatasetSelected(dataset.getId(), this.id, this.mClientState);
                }
                autoFillApp(dataset);
            } else {
                this.mService.logDatasetAuthenticationSelected(dataset.getId(), this.id, this.mClientState);
                setViewStatesLocked(null, dataset, 64, false);
                Intent fillInIntent = createAuthFillInIntentLocked(requestId, this.mClientState);
                if (fillInIntent == null) {
                    forceRemoveSelfLocked();
                    return;
                }
                int authenticationId = AutofillManager.makeAuthenticationId(requestId, datasetIndex);
                startAuthentication(authenticationId, dataset.getAuthentication(), fillInIntent);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public CharSequence getServiceName() {
        CharSequence serviceName;
        synchronized (this.mLock) {
            serviceName = this.mService.getServiceName();
        }
        return serviceName;
    }

    @GuardedBy("mLock")
    private Intent createAuthFillInIntentLocked(int requestId, Bundle extras) {
        Intent fillInIntent = new Intent();
        FillContext context = getFillContextByRequestIdLocked(requestId);
        if (context == null) {
            wtf(null, "createAuthFillInIntentLocked(): no FillContext. requestId=%d; mContexts=%s", Integer.valueOf(requestId), this.mContexts);
            return null;
        }
        fillInIntent.putExtra("android.view.autofill.extra.ASSIST_STRUCTURE", context.getStructure());
        fillInIntent.putExtra("android.view.autofill.extra.CLIENT_STATE", extras);
        return fillInIntent;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startAuthentication(int authenticationId, IntentSender intent, Intent fillInIntent) {
        try {
            synchronized (this.mLock) {
                this.mClient.authenticate(this.id, authenticationId, intent, fillInIntent);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Error launching auth intent", e);
        }
    }

    public String toString() {
        return "Session: [id=" + this.id + ", component=" + this.mComponentName + "]";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void dumpLocked(String prefix, PrintWriter pw) {
        String prefix2 = prefix + "  ";
        pw.print(prefix);
        pw.print("id: ");
        pw.println(this.id);
        pw.print(prefix);
        pw.print("uid: ");
        pw.println(this.uid);
        pw.print(prefix);
        pw.print("flags: ");
        pw.println(this.mFlags);
        pw.print(prefix);
        pw.print("mComponentName: ");
        pw.println(this.mComponentName);
        pw.print(prefix);
        pw.print("mActivityToken: ");
        pw.println(this.mActivityToken);
        pw.print(prefix);
        pw.print("mStartTime: ");
        pw.println(this.mStartTime);
        pw.print(prefix);
        pw.print("Time to show UI: ");
        if (this.mUiShownTime == 0) {
            pw.println("N/A");
        } else {
            TimeUtils.formatDuration(this.mUiShownTime - this.mStartTime, pw);
            pw.println();
        }
        int requestLogsSizes = this.mRequestLogs.size();
        pw.print(prefix);
        pw.print("mSessionLogs: ");
        pw.println(requestLogsSizes);
        for (int i = 0; i < requestLogsSizes; i++) {
            int requestId = this.mRequestLogs.keyAt(i);
            LogMaker log = this.mRequestLogs.valueAt(i);
            pw.print(prefix2);
            pw.print('#');
            pw.print(i);
            pw.print(": req=");
            pw.print(requestId);
            pw.print(", log=");
            dumpRequestLog(pw, log);
            pw.println();
        }
        pw.print(prefix);
        pw.print("mResponses: ");
        if (this.mResponses == null) {
            pw.println("null");
        } else {
            pw.println(this.mResponses.size());
            for (int i2 = 0; i2 < this.mResponses.size(); i2++) {
                pw.print(prefix2);
                pw.print('#');
                pw.print(i2);
                pw.print(' ');
                pw.println(this.mResponses.valueAt(i2));
            }
        }
        pw.print(prefix);
        pw.print("mCurrentViewId: ");
        pw.println(this.mCurrentViewId);
        pw.print(prefix);
        pw.print("mDestroyed: ");
        pw.println(this.mDestroyed);
        pw.print(prefix);
        pw.print("mIsSaving: ");
        pw.println(this.mIsSaving);
        pw.print(prefix);
        pw.print("mPendingSaveUi: ");
        pw.println(this.mPendingSaveUi);
        int numberViews = this.mViewStates.size();
        pw.print(prefix);
        pw.print("mViewStates size: ");
        pw.println(this.mViewStates.size());
        for (int i3 = 0; i3 < numberViews; i3++) {
            pw.print(prefix);
            pw.print("ViewState at #");
            pw.println(i3);
            this.mViewStates.valueAt(i3).dump(prefix2, pw);
        }
        pw.print(prefix);
        pw.print("mContexts: ");
        if (this.mContexts != null) {
            int numContexts = this.mContexts.size();
            for (int i4 = 0; i4 < numContexts; i4++) {
                FillContext context = this.mContexts.get(i4);
                pw.print(prefix2);
                pw.print(context);
                if (Helper.sVerbose) {
                    pw.println("AssistStructure dumped at logcat)");
                    context.getStructure().dump(false);
                }
            }
        } else {
            pw.println("null");
        }
        pw.print(prefix);
        pw.print("mHasCallback: ");
        pw.println(this.mHasCallback);
        if (this.mClientState != null) {
            pw.print(prefix);
            pw.print("mClientState: ");
            pw.print(this.mClientState.getSize());
            pw.println(" bytes");
        }
        pw.print(prefix);
        pw.print("mCompatMode: ");
        pw.println(this.mCompatMode);
        pw.print(prefix);
        pw.print("mUrlBar: ");
        if (this.mUrlBar == null) {
            pw.println("N/A");
        } else {
            pw.print("id=");
            pw.print(this.mUrlBar.getAutofillId());
            pw.print(" domain=");
            pw.print(this.mUrlBar.getWebDomain());
            pw.print(" text=");
            Helper.printlnRedactedText(pw, this.mUrlBar.getText());
        }
        pw.print(prefix);
        pw.print("mSaveOnAllViewsInvisible: ");
        pw.println(this.mSaveOnAllViewsInvisible);
        pw.print(prefix);
        pw.print("mSelectedDatasetIds: ");
        pw.println(this.mSelectedDatasetIds);
        this.mRemoteFillService.dump(prefix, pw);
    }

    private static void dumpRequestLog(PrintWriter pw, LogMaker log) {
        pw.print("CAT=");
        pw.print(log.getCategory());
        pw.print(", TYPE=");
        int type = log.getType();
        if (type != 2) {
            switch (type) {
                case 10:
                    pw.print("SUCCESS");
                    break;
                case 11:
                    pw.print("FAILURE");
                    break;
                default:
                    pw.print("UNSUPPORTED");
                    break;
            }
        } else {
            pw.print("CLOSE");
        }
        pw.print('(');
        pw.print(type);
        pw.print(')');
        pw.print(", PKG=");
        pw.print(log.getPackageName());
        pw.print(", SERVICE=");
        pw.print(log.getTaggedData(908));
        pw.print(", ORDINAL=");
        pw.print(log.getTaggedData(1454));
        dumpNumericValue(pw, log, "FLAGS", 1452);
        dumpNumericValue(pw, log, "NUM_DATASETS", 909);
        dumpNumericValue(pw, log, "UI_LATENCY", 1145);
        int authStatus = Helper.getNumericValue(log, 1453);
        if (authStatus != 0) {
            pw.print(", AUTH_STATUS=");
            if (authStatus == 912) {
                pw.print("AUTHENTICATED");
            } else {
                switch (authStatus) {
                    case 1126:
                        pw.print("DATASET_AUTHENTICATED");
                        break;
                    case 1127:
                        pw.print("INVALID_DATASET_AUTHENTICATION");
                        break;
                    case 1128:
                        pw.print("INVALID_AUTHENTICATION");
                        break;
                    default:
                        pw.print("UNSUPPORTED");
                        break;
                }
            }
            pw.print('(');
            pw.print(authStatus);
            pw.print(')');
        }
        dumpNumericValue(pw, log, "FC_IDS", 1271);
        dumpNumericValue(pw, log, "COMPAT_MODE", 1414);
    }

    private static void dumpNumericValue(PrintWriter pw, LogMaker log, String field, int tag) {
        int value = Helper.getNumericValue(log, tag);
        if (value != 0) {
            pw.print(", ");
            pw.print(field);
            pw.print('=');
            pw.print(value);
        }
    }

    void autoFillApp(Dataset dataset) {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#autoFillApp() rejected - session: " + this.id + " destroyed");
                return;
            }
            try {
                int entryCount = dataset.getFieldIds().size();
                List<AutofillId> ids = new ArrayList<>(entryCount);
                List<AutofillValue> values = new ArrayList<>(entryCount);
                boolean waitingDatasetAuth = false;
                for (int i = 0; i < entryCount; i++) {
                    if (dataset.getFieldValues().get(i) != null) {
                        AutofillId viewId = (AutofillId) dataset.getFieldIds().get(i);
                        ids.add(viewId);
                        values.add((AutofillValue) dataset.getFieldValues().get(i));
                        ViewState viewState = this.mViewStates.get(viewId);
                        if (viewState != null && (viewState.getState() & 64) != 0) {
                            if (Helper.sVerbose) {
                                Slog.v(TAG, "autofillApp(): view " + viewId + " waiting auth");
                            }
                            waitingDatasetAuth = true;
                            viewState.resetState(64);
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    if (waitingDatasetAuth) {
                        this.mUi.hideFillUi(this);
                    }
                    if (Helper.sDebug) {
                        Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                    }
                    this.mClient.autofill(this.id, ids, values);
                    if (dataset.getId() != null) {
                        if (this.mSelectedDatasetIds == null) {
                            this.mSelectedDatasetIds = new ArrayList<>();
                        }
                        this.mSelectedDatasetIds.add(dataset.getId());
                    }
                    setViewStatesLocked(null, dataset, 4, false);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Error autofilling activity: " + e);
            }
        }
    }

    private AutoFillUI getUiForShowing() {
        AutoFillUI autoFillUI;
        synchronized (this.mLock) {
            this.mUi.setCallback(this);
            autoFillUI = this.mUi;
        }
        return autoFillUI;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public RemoteFillService destroyLocked() {
        if (this.mDestroyed) {
            return null;
        }
        unlinkClientVultureLocked();
        this.mUi.destroyAll(this.mPendingSaveUi, this, true);
        this.mUi.clearCallback(this);
        this.mDestroyed = true;
        int totalRequests = this.mRequestLogs.size();
        if (totalRequests > 0) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "destroyLocked(): logging " + totalRequests + " requests");
            }
            for (int i = 0; i < totalRequests; i++) {
                LogMaker log = this.mRequestLogs.valueAt(i);
                this.mMetricsLogger.write(log);
            }
        }
        this.mMetricsLogger.write(newLogMaker(919).addTaggedData(1455, Integer.valueOf(totalRequests)));
        return this.mRemoteFillService;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void forceRemoveSelfLocked() {
        forceRemoveSelfLocked(0);
    }

    @GuardedBy("mLock")
    void forceRemoveSelfLocked(int clientState) {
        if (Helper.sVerbose) {
            Slog.v(TAG, "forceRemoveSelfLocked(): " + this.mPendingSaveUi);
        }
        boolean isPendingSaveUi = isSaveUiPendingLocked();
        this.mPendingSaveUi = null;
        removeSelfLocked();
        this.mUi.destroyAll(this.mPendingSaveUi, this, false);
        if (!isPendingSaveUi) {
            try {
                this.mClient.setSessionFinished(clientState);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client to finish session", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeSelf() {
        synchronized (this.mLock) {
            removeSelfLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public void removeSelfLocked() {
        if (Helper.sVerbose) {
            Slog.v(TAG, "removeSelfLocked(): " + this.mPendingSaveUi);
        }
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#removeSelfLocked() rejected - session: " + this.id + " destroyed");
        } else if (isSaveUiPendingLocked()) {
            Slog.i(TAG, "removeSelfLocked() ignored, waiting for pending save ui");
        } else {
            RemoteFillService remoteFillService = destroyLocked();
            this.mService.removeSessionLocked(this.id);
            if (remoteFillService != null) {
                remoteFillService.destroy();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPendingSaveUi(int operation, IBinder token) {
        getUiForShowing().onPendingSaveUi(operation, token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("mLock")
    public boolean isSaveUiPendingForTokenLocked(IBinder token) {
        return isSaveUiPendingLocked() && token.equals(this.mPendingSaveUi.getToken());
    }

    @GuardedBy("mLock")
    private boolean isSaveUiPendingLocked() {
        return this.mPendingSaveUi != null && this.mPendingSaveUi.getState() == 2;
    }

    @GuardedBy("mLock")
    private int getLastResponseIndexLocked() {
        int lastResponseIdx = -1;
        if (this.mResponses != null) {
            int responseCount = this.mResponses.size();
            for (int i = 0; i < responseCount; i++) {
                if (this.mResponses.keyAt(i) > -1) {
                    lastResponseIdx = i;
                }
            }
        }
        return lastResponseIdx;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public LogMaker newLogMaker(int category) {
        return newLogMaker(category, this.mService.getServicePackageName());
    }

    private LogMaker newLogMaker(int category, String servicePackageName) {
        return Helper.newLogMaker(category, this.mComponentName, servicePackageName, this.id, this.mCompatMode);
    }

    private void writeLog(int category) {
        this.mMetricsLogger.write(newLogMaker(category));
    }

    private void logAuthenticationStatusLocked(int requestId, int status) {
        addTaggedDataToRequestLogLocked(requestId, 1453, Integer.valueOf(status));
    }

    private void addTaggedDataToRequestLogLocked(int requestId, int tag, Object value) {
        LogMaker requestLog = this.mRequestLogs.get(requestId);
        if (requestLog == null) {
            Slog.w(TAG, "addTaggedDataToRequestLogLocked(tag=" + tag + "): no log for id " + requestId);
            return;
        }
        requestLog.addTaggedData(tag, value);
    }

    private static String requestLogToString(LogMaker log) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        dumpRequestLog(pw, log);
        pw.flush();
        return sw.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void wtf(Exception e, String fmt, Object... args) {
        String message = String.format(fmt, args);
        this.mWtfHistory.log(message);
        if (e != null) {
            Slog.wtf(TAG, message, e);
        } else {
            Slog.wtf(TAG, message);
        }
    }

    private static String actionAsString(int action) {
        switch (action) {
            case 1:
                return "START_SESSION";
            case 2:
                return "VIEW_ENTERED";
            case 3:
                return "VIEW_EXITED";
            case 4:
                return "VALUE_CHANGED";
            default:
                return "UNKNOWN_" + action;
        }
    }
}
