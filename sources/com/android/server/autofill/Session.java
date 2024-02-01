package com.android.server.autofill;

import android.app.ActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.autofill.CompositeUserData;
import android.service.autofill.Dataset;
import android.service.autofill.FieldClassification;
import android.service.autofill.FieldClassificationUserData;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.InternalSanitizer;
import android.service.autofill.InternalValidator;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.service.autofill.UserData;
import android.service.autofill.ValueFinder;
import android.text.TextUtils;
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
import com.android.server.slice.SliceClientPermissions;
import com.android.server.wm.ActivityTaskManagerInternal;
import java.io.PrintWriter;
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
    @GuardedBy({"mLock"})
    private IBinder mActivityToken;
    @GuardedBy({"mLock"})
    private Runnable mAugmentedAutofillDestroyer;
    @GuardedBy({"mLock"})
    private ArrayList<AutofillId> mAugmentedAutofillableIds;
    @GuardedBy({"mLock"})
    private ArrayList<LogMaker> mAugmentedRequestsLogs;
    @GuardedBy({"mLock"})
    private IAutoFillManagerClient mClient;
    @GuardedBy({"mLock"})
    private Bundle mClientState;
    @GuardedBy({"mLock"})
    private IBinder.DeathRecipient mClientVulture;
    private final boolean mCompatMode;
    private final ComponentName mComponentName;
    @GuardedBy({"mLock"})
    private ArrayList<FillContext> mContexts;
    @GuardedBy({"mLock"})
    private AutofillId mCurrentViewId;
    @GuardedBy({"mLock"})
    private boolean mDestroyed;
    public final int mFlags;
    @GuardedBy({"mLock"})
    private boolean mForAugmentedAutofillOnly;
    private final Handler mHandler;
    private boolean mHasCallback;
    @GuardedBy({"mLock"})
    private boolean mIsSaving;
    private final Object mLock;
    @GuardedBy({"mLock"})
    private PendingUi mPendingSaveUi;
    private final RemoteFillService mRemoteFillService;
    @GuardedBy({"mLock"})
    private SparseArray<FillResponse> mResponses;
    @GuardedBy({"mLock"})
    private boolean mSaveOnAllViewsInvisible;
    @GuardedBy({"mLock"})
    private ArrayList<String> mSelectedDatasetIds;
    private final AutofillManagerServiceImpl mService;
    private final long mStartTime;
    private final AutoFillUI mUi;
    @GuardedBy({"mLock"})
    private final LocalLog mUiLatencyHistory;
    @GuardedBy({"mLock"})
    private long mUiShownTime;
    @GuardedBy({"mLock"})
    private AssistStructure.ViewNode mUrlBar;
    @GuardedBy({"mLock"})
    private final LocalLog mWtfHistory;
    public final int taskId;
    public final int uid;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    @GuardedBy({"mLock"})
    private final ArrayMap<AutofillId, ViewState> mViewStates = new ArrayMap<>();
    @GuardedBy({"mLock"})
    private final SparseArray<LogMaker> mRequestLogs = new SparseArray<>(1);
    private final IAssistDataReceiver mAssistReceiver = new IAssistDataReceiver.Stub() { // from class: com.android.server.autofill.Session.1
        public void onHandleAssistData(Bundle resultData) throws RemoteException {
            FillRequest request;
            if (Session.this.mRemoteFillService == null) {
                Session session = Session.this;
                session.wtf(null, "onHandleAssistData() called without a remote service. mForAugmentedAutofillOnly: %s", Boolean.valueOf(session.mForAugmentedAutofillOnly));
                return;
            }
            AssistStructure structure = (AssistStructure) resultData.getParcelable(ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE);
            if (structure == null) {
                Slog.e(Session.TAG, "No assist structure - app might have crashed providing it");
                return;
            }
            Bundle receiverExtras = resultData.getBundle(ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS);
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
                    ArrayList<AutofillId> ids = Helper.getAutofillIds(structure, false);
                    for (int i = 0; i < ids.size(); i++) {
                        ids.get(i).setSessionId(Session.this.id);
                    }
                    int flags = structure.getFlags();
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
                                ViewState viewState = new ViewState(urlBarId, Session.this, 512);
                                Session.this.mViewStates.put(urlBarId, viewState);
                            }
                        }
                        flags |= 2;
                    }
                    structure.sanitizeForParceling(true);
                    if (Session.this.mContexts == null) {
                        Session.this.mContexts = new ArrayList(1);
                    }
                    Session.this.mContexts.add(new FillContext(requestId, structure, Session.this.mCurrentViewId));
                    Session.this.cancelCurrentRequestLocked();
                    int numContexts = Session.this.mContexts.size();
                    for (int i2 = 0; i2 < numContexts; i2++) {
                        Session.this.fillContextWithAllowedValuesLocked((FillContext) Session.this.mContexts.get(i2), flags);
                    }
                    ArrayList<FillContext> contexts = Session.this.mergePreviousSessionLocked(false);
                    request = new FillRequest(requestId, contexts, Session.this.mClientState, flags);
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

    @GuardedBy({"mLock"})
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

    @GuardedBy({"mLock"})
    private AutofillValue findValueLocked(AutofillId autofillId) {
        AutofillValue value = findValueFromThisSessionOnlyLocked(autofillId);
        if (value != null) {
            return getSanitizedValue(createSanitizers(getSaveInfoLocked()), autofillId, value);
        }
        ArrayList<Session> previousSessions = this.mService.getPreviousSessionsLocked(this);
        if (previousSessions != null) {
            if (Helper.sDebug) {
                Slog.d(TAG, "findValueLocked(): looking on " + previousSessions.size() + " previous sessions for autofillId " + autofillId);
            }
            for (int i = 0; i < previousSessions.size(); i++) {
                Session previousSession = previousSessions.get(i);
                AutofillValue previousValue = previousSession.findValueFromThisSessionOnlyLocked(autofillId);
                if (previousValue != null) {
                    return getSanitizedValue(createSanitizers(previousSession.getSaveInfoLocked()), autofillId, previousValue);
                }
            }
            return null;
        }
        return null;
    }

    private AutofillValue findValueFromThisSessionOnlyLocked(AutofillId autofillId) {
        ViewState state = this.mViewStates.get(autofillId);
        if (state == null) {
            if (Helper.sDebug) {
                Slog.d(TAG, "findValueLocked(): no view state for " + autofillId);
                return null;
            }
            return null;
        }
        AutofillValue value = state.getCurrentValue();
        if (value == null) {
            if (Helper.sDebug) {
                Slog.d(TAG, "findValueLocked(): no current value for " + autofillId);
            }
            return getValueFromContextsLocked(autofillId);
        }
        return value;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
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
                AutofillId autofillId = this.mCurrentViewId;
                if (autofillId != null) {
                    overlay.focused = autofillId.equals(viewState.id);
                    if (overlay.focused && (flags & 1) != 0) {
                        overlay.value = currentValue;
                    }
                }
                node.setAutofillOverlay(overlay);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void cancelCurrentRequestLocked() {
        RemoteFillService remoteFillService = this.mRemoteFillService;
        if (remoteFillService == null) {
            wtf(null, "cancelCurrentRequestLocked() called without a remote service. mForAugmentedAutofillOnly: %s", Boolean.valueOf(this.mForAugmentedAutofillOnly));
        } else {
            remoteFillService.cancelCurrentRequest().whenComplete(new BiConsumer() { // from class: com.android.server.autofill.-$$Lambda$Session$PRbkIjhZfKjMPS1K8XiwST8ILPc
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    Session.this.lambda$cancelCurrentRequestLocked$0$Session((Integer) obj, (Throwable) obj2);
                }
            });
        }
    }

    public /* synthetic */ void lambda$cancelCurrentRequestLocked$0$Session(Integer canceledRequest, Throwable err) {
        ArrayList<FillContext> arrayList;
        if (err != null) {
            Slog.e(TAG, "cancelCurrentRequest(): unexpected exception", err);
        } else if (canceledRequest.intValue() != Integer.MIN_VALUE && (arrayList = this.mContexts) != null) {
            int numContexts = arrayList.size();
            for (int i = numContexts - 1; i >= 0; i--) {
                if (this.mContexts.get(i).getRequestId() == canceledRequest.intValue()) {
                    if (Helper.sDebug) {
                        Slog.d(TAG, "cancelCurrentRequest(): id = " + canceledRequest);
                    }
                    this.mContexts.remove(i);
                    return;
                }
            }
        }
    }

    @GuardedBy({"mLock"})
    private void requestNewFillResponseLocked(ViewState viewState, int newState, int flags) {
        int requestId;
        if (this.mForAugmentedAutofillOnly || this.mRemoteFillService == null) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "requestNewFillResponse(): triggering augmented autofill instead (mForAugmentedAutofillOnly=" + this.mForAugmentedAutofillOnly + ", flags=" + flags + ")");
            }
            this.mForAugmentedAutofillOnly = true;
            triggerAugmentedAutofillLocked(flags);
            return;
        }
        viewState.setState(newState);
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
            if (!ActivityTaskManager.getService().requestAutofillData(this.mAssistReceiver, receiverExtras, this.mActivityToken, flags)) {
                Slog.w(TAG, "failed to request autofill data for " + this.mActivityToken);
            }
            Binder.restoreCallingIdentity(identity);
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Session(AutofillManagerServiceImpl service, AutoFillUI ui, Context context, Handler handler, int userId, Object lock, int sessionId, int taskId, int uid, IBinder activityToken, IBinder client, boolean hasCallback, LocalLog uiLatencyHistory, LocalLog wtfHistory, ComponentName serviceComponentName, ComponentName componentName, boolean compatMode, boolean bindInstantServiceAllowed, boolean forAugmentedAutofillOnly, int flags) {
        RemoteFillService remoteFillService;
        if (sessionId < 0) {
            wtf(null, "Non-positive sessionId: %s", Integer.valueOf(sessionId));
        }
        this.id = sessionId;
        this.mFlags = flags;
        this.taskId = taskId;
        this.uid = uid;
        this.mStartTime = SystemClock.elapsedRealtime();
        this.mService = service;
        this.mLock = lock;
        this.mUi = ui;
        this.mHandler = handler;
        if (serviceComponentName == null) {
            remoteFillService = null;
        } else {
            remoteFillService = new RemoteFillService(context, serviceComponentName, userId, this, bindInstantServiceAllowed);
        }
        this.mRemoteFillService = remoteFillService;
        this.mActivityToken = activityToken;
        this.mHasCallback = hasCallback;
        this.mUiLatencyHistory = uiLatencyHistory;
        this.mWtfHistory = wtfHistory;
        this.mComponentName = componentName;
        this.mCompatMode = compatMode;
        this.mForAugmentedAutofillOnly = forAugmentedAutofillOnly;
        setClientLocked(client);
        this.mMetricsLogger.write(newLogMaker(906).addTaggedData(1452, Integer.valueOf(flags)));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
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

    @GuardedBy({"mLock"})
    private void setClientLocked(IBinder client) {
        unlinkClientVultureLocked();
        this.mClient = IAutoFillManagerClient.Stub.asInterface(client);
        this.mClientVulture = new IBinder.DeathRecipient() { // from class: com.android.server.autofill.-$$Lambda$Session$pnp5H13_WJpAwp-_PPOjh_vYbqs
            @Override // android.os.IBinder.DeathRecipient
            public final void binderDied() {
                Session.this.lambda$setClientLocked$1$Session();
            }
        };
        try {
            this.mClient.asBinder().linkToDeath(this.mClientVulture, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "could not set binder death listener on autofill client: " + e);
            this.mClientVulture = null;
        }
    }

    public /* synthetic */ void lambda$setClientLocked$1$Session() {
        Slog.d(TAG, "handling death of " + this.mActivityToken + " when saving=" + this.mIsSaving);
        synchronized (this.mLock) {
            if (this.mIsSaving) {
                this.mUi.hideFillUi(this);
            } else {
                this.mUi.destroyAll(this.mPendingSaveUi, this, false);
            }
        }
    }

    @GuardedBy({"mLock"})
    private void unlinkClientVultureLocked() {
        IAutoFillManagerClient iAutoFillManagerClient = this.mClient;
        if (iAutoFillManagerClient != null && this.mClientVulture != null) {
            boolean unlinked = iAutoFillManagerClient.asBinder().unlinkToDeath(this.mClientVulture, 0);
            if (!unlinked) {
                Slog.w(TAG, "unlinking vulture from death failed for " + this.mActivityToken);
            }
            this.mClientVulture = null;
        }
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onFillRequestSuccess(int requestId, FillResponse response, String servicePackageName, int requestFlags) {
        int sessionFinishedState;
        int flags;
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
                processNullResponseLocked(requestId, requestFlags);
                return;
            }
            AutofillId[] fieldClassificationIds = response.getFieldClassificationIds();
            if (fieldClassificationIds == null || this.mService.isFieldClassificationEnabledLocked()) {
                this.mService.setLastResponse(this.id, response);
                long disableDuration = response.getDisableDuration();
                if (disableDuration <= 0) {
                    sessionFinishedState = 0;
                } else {
                    int flags2 = response.getFlags();
                    if ((flags2 & 2) != 0) {
                        flags = flags2;
                        this.mService.disableAutofillForActivity(this.mComponentName, disableDuration, this.id, this.mCompatMode);
                    } else {
                        flags = flags2;
                        this.mService.disableAutofillForApp(this.mComponentName.getPackageName(), disableDuration, this.id, this.mCompatMode);
                    }
                    if (triggerAugmentedAutofillLocked(requestFlags) != null) {
                        this.mForAugmentedAutofillOnly = true;
                        if (Helper.sDebug) {
                            Slog.d(TAG, "Service disabled autofill for " + this.mComponentName + ", but session is kept for augmented autofill only");
                            return;
                        }
                        return;
                    }
                    if (Helper.sDebug) {
                        StringBuilder sb = new StringBuilder("Service disabled autofill for ");
                        sb.append(this.mComponentName);
                        sb.append(": flags=");
                        sb.append(flags);
                        StringBuilder message = sb.append(", duration=");
                        TimeUtils.formatDuration(disableDuration, message);
                        Slog.d(TAG, message.toString());
                    }
                    sessionFinishedState = 4;
                }
                if (((response.getDatasets() == null || response.getDatasets().isEmpty()) && response.getAuthentication() == null) || disableDuration > 0) {
                    notifyUnavailableToClient(sessionFinishedState, null);
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
                return;
            }
            Slog.w(TAG, "Ignoring " + response + " because field detection is disabled");
            processNullResponseLocked(requestId, requestFlags);
        }
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onFillRequestFailure(int requestId, CharSequence message) {
        onFillRequestFailureOrTimeout(requestId, false, message);
    }

    @Override // com.android.server.autofill.RemoteFillService.FillServiceCallbacks
    public void onFillRequestTimeout(int requestId) {
        onFillRequestFailureOrTimeout(requestId, true, null);
    }

    private void onFillRequestFailureOrTimeout(int requestId, boolean timedOut, CharSequence message) {
        boolean showMessage = !TextUtils.isEmpty(message);
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestFailureOrTimeout(req=" + requestId + ") rejected - session: " + this.id + " destroyed");
                return;
            }
            if (Helper.sDebug) {
                StringBuilder sb = new StringBuilder();
                sb.append("finishing session due to service ");
                sb.append(timedOut ? "timeout" : "failure");
                Slog.d(TAG, sb.toString());
            }
            this.mService.resetLastResponse();
            LogMaker requestLog = this.mRequestLogs.get(requestId);
            if (requestLog == null) {
                Slog.w(TAG, "onFillRequestFailureOrTimeout(): no log for id " + requestId);
            } else {
                requestLog.setType(timedOut ? 2 : 11);
            }
            if (showMessage) {
                int targetSdk = this.mService.getTargedSdkLocked();
                if (targetSdk >= 29) {
                    showMessage = false;
                    Slog.w(TAG, "onFillRequestFailureOrTimeout(): not showing '" + ((Object) message) + "' because service's targetting API " + targetSdk);
                }
                if (message != null) {
                    requestLog.addTaggedData(1572, Integer.valueOf(message.length()));
                }
            }
            notifyUnavailableToClient(6, null);
            if (showMessage) {
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
        int targetSdk;
        boolean showMessage = !TextUtils.isEmpty(message);
        synchronized (this.mLock) {
            this.mIsSaving = false;
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onSaveRequestFailure() rejected - session: " + this.id + " destroyed");
                return;
            }
            if (showMessage && (targetSdk = this.mService.getTargedSdkLocked()) >= 29) {
                showMessage = false;
                Slog.w(TAG, "onSaveRequestFailure(): not showing '" + ((Object) message) + "' because service's targetting API " + targetSdk);
            }
            LogMaker log = newLogMaker(918, servicePackageName).setType(11);
            if (message != null) {
                log.addTaggedData(1572, Integer.valueOf(message.length()));
            }
            this.mMetricsLogger.write(log);
            if (showMessage) {
                getUiForShowing().showError(message, this);
            }
            removeSelf();
        }
    }

    @GuardedBy({"mLock"})
    private FillContext getFillContextByRequestIdLocked(int requestId) {
        ArrayList<FillContext> arrayList = this.mContexts;
        if (arrayList == null) {
            return null;
        }
        int numContexts = arrayList.size();
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

    public void onServiceDied(RemoteFillService service) {
        Slog.w(TAG, "removing session because service died");
        forceRemoveSelfLocked();
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
    @GuardedBy({"mLock"})
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
                Slog.w(TAG, "no authenticated response");
                removeSelf();
                return;
            }
            int datasetIdx = AutofillManager.getDatasetIdFromAuthenticationId(authenticationId);
            if (datasetIdx != 65535 && ((Dataset) authenticatedResponse.getDatasets().get(datasetIdx)) == null) {
                Slog.w(TAG, "no dataset with index " + datasetIdx + " on fill response");
                removeSelf();
                return;
            }
            Parcelable result = data.getParcelable("android.view.autofill.extra.AUTHENTICATION_RESULT");
            Bundle newClientState = data.getBundle("android.view.autofill.extra.CLIENT_STATE");
            if (Helper.sDebug) {
                Slog.d(TAG, "setAuthenticationResultLocked(): result=" + result + ", clientState=" + newClientState + ", authenticationId=" + authenticationId);
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
                Slog.w(TAG, "invalid index (" + datasetIdx + ") for authentication id " + authenticationId);
                logAuthenticationStatusLocked(requestId, 1127);
            } else {
                if (result != null) {
                    Slog.w(TAG, "service returned invalid auth type: " + result);
                }
                logAuthenticationStatusLocked(requestId, 1128);
                processNullResponseLocked(requestId, 0);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void setHasCallbackLocked(boolean hasIt) {
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#setHasCallbackLocked() rejected - session: " + this.id + " destroyed");
            return;
        }
        this.mHasCallback = hasIt;
    }

    @GuardedBy({"mLock"})
    private FillResponse getLastResponseLocked(String logPrefixFmt) {
        String logPrefix = (!Helper.sDebug || logPrefixFmt == null) ? null : String.format(logPrefixFmt, Integer.valueOf(this.id));
        if (this.mContexts == null) {
            if (logPrefix != null) {
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

    @GuardedBy({"mLock"})
    private SaveInfo getSaveInfoLocked() {
        FillResponse response = getLastResponseLocked(null);
        if (response == null) {
            return null;
        }
        return response.getSaveInfo();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public int getSaveInfoFlagsLocked() {
        SaveInfo saveInfo = getSaveInfoLocked();
        if (saveInfo == null) {
            return 0;
        }
        return saveInfo.getFlags();
    }

    public void logContextCommitted() {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.autofill.-$$Lambda$Session$v6ZVyksJuHdWgJ1F8aoa_1LJWPo
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((Session) obj).handleLogContextCommitted();
            }
        }, this));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLogContextCommitted() {
        FillResponse lastResponse;
        FieldClassificationUserData userData;
        synchronized (this.mLock) {
            lastResponse = getLastResponseLocked("logContextCommited(%s)");
        }
        if (lastResponse == null) {
            Slog.w(TAG, "handleLogContextCommitted(): last response is null");
            return;
        }
        UserData genericUserData = this.mService.getUserData();
        FieldClassificationUserData userData2 = lastResponse.getUserData();
        if (userData2 == null && genericUserData == null) {
            userData = null;
        } else if (userData2 != null && genericUserData != null) {
            userData = new CompositeUserData(genericUserData, userData2);
        } else if (userData2 != null) {
            userData = userData2;
        } else {
            userData = this.mService.getUserData();
        }
        FieldClassificationStrategy fcStrategy = this.mService.getFieldClassificationStrategy();
        if (userData != null && fcStrategy != null) {
            logFieldClassificationScore(fcStrategy, userData);
        } else {
            logContextCommitted(null, null);
        }
    }

    private void logContextCommitted(ArrayList<AutofillId> detectedFieldIds, ArrayList<FieldClassification> detectedFieldClassifications) {
        synchronized (this.mLock) {
            logContextCommittedLocked(detectedFieldIds, detectedFieldClassifications);
        }
    }

    @GuardedBy({"mLock"})
    private void logContextCommittedLocked(ArrayList<AutofillId> detectedFieldIds, ArrayList<FieldClassification> detectedFieldClassifications) {
        String str;
        FillResponse lastResponse;
        AutofillId[] fieldClassificationIds;
        boolean hasAtLeastOneDataset;
        int responseCount;
        String str2;
        AutofillValue currentValue;
        boolean hasAtLeastOneDataset2;
        int responseCount2;
        String str3;
        int responseCount3;
        String str4;
        AutofillValue currentValue2;
        ArraySet<String> ignoredDatasets;
        AutofillValue currentValue3;
        ArrayList<AutofillValue> values;
        ArrayMap<AutofillId, ArraySet<String>> manuallyFilledIds;
        int flags;
        ArrayList<AutofillId> changedFieldIds;
        ArrayList<String> changedDatasetIds;
        ArrayList<String> changedDatasetIds2;
        FillResponse lastResponse2 = getLastResponseLocked("logContextCommited(%s)");
        if (lastResponse2 == null) {
            return;
        }
        int flags2 = lastResponse2.getFlags();
        if ((flags2 & 1) == 0) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "logContextCommittedLocked(): ignored by flags " + flags2);
                return;
            }
            return;
        }
        ArraySet<String> ignoredDatasets2 = null;
        ArrayList<AutofillId> changedFieldIds2 = null;
        ArrayList<String> changedDatasetIds3 = null;
        ArrayMap<AutofillId, ArraySet<String>> manuallyFilledIds2 = null;
        boolean hasAtLeastOneDataset3 = false;
        int responseCount4 = this.mResponses.size();
        int i = 0;
        while (true) {
            str = "logContextCommitted() skipping idless dataset ";
            if (i >= responseCount4) {
                break;
            }
            FillResponse response = this.mResponses.valueAt(i);
            List<Dataset> datasets = response.getDatasets();
            if (datasets == null) {
                flags = flags2;
                changedFieldIds = changedFieldIds2;
                changedDatasetIds = changedDatasetIds3;
            } else if (datasets.isEmpty()) {
                flags = flags2;
                changedFieldIds = changedFieldIds2;
                changedDatasetIds = changedDatasetIds3;
            } else {
                int j = 0;
                while (true) {
                    flags = flags2;
                    if (j >= datasets.size()) {
                        break;
                    }
                    Dataset dataset = datasets.get(j);
                    ArrayList<AutofillId> changedFieldIds3 = changedFieldIds2;
                    String datasetId = dataset.getId();
                    if (datasetId == null) {
                        if (!Helper.sVerbose) {
                            changedDatasetIds2 = changedDatasetIds3;
                        } else {
                            changedDatasetIds2 = changedDatasetIds3;
                            Slog.v(TAG, "logContextCommitted() skipping idless dataset " + dataset);
                        }
                    } else {
                        changedDatasetIds2 = changedDatasetIds3;
                        ArrayList<String> arrayList = this.mSelectedDatasetIds;
                        if (arrayList == null || !arrayList.contains(datasetId)) {
                            if (Helper.sVerbose) {
                                Slog.v(TAG, "adding ignored dataset " + datasetId);
                            }
                            if (ignoredDatasets2 == null) {
                                ignoredDatasets2 = new ArraySet<>();
                            }
                            ignoredDatasets2.add(datasetId);
                            hasAtLeastOneDataset3 = true;
                        } else {
                            hasAtLeastOneDataset3 = true;
                        }
                    }
                    j++;
                    flags2 = flags;
                    changedFieldIds2 = changedFieldIds3;
                    changedDatasetIds3 = changedDatasetIds2;
                }
                changedFieldIds = changedFieldIds2;
                changedDatasetIds = changedDatasetIds3;
                i++;
                flags2 = flags;
                changedFieldIds2 = changedFieldIds;
                changedDatasetIds3 = changedDatasetIds;
            }
            if (Helper.sVerbose) {
                Slog.v(TAG, "logContextCommitted() no datasets at " + i);
            }
            i++;
            flags2 = flags;
            changedFieldIds2 = changedFieldIds;
            changedDatasetIds3 = changedDatasetIds;
        }
        ArrayList<AutofillId> changedFieldIds4 = changedFieldIds2;
        ArrayList<String> changedDatasetIds4 = changedDatasetIds3;
        AutofillId[] fieldClassificationIds2 = lastResponse2.getFieldClassificationIds();
        if (!hasAtLeastOneDataset3 && fieldClassificationIds2 == null) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "logContextCommittedLocked(): skipped (no datasets nor fields classification ids)");
                return;
            }
            return;
        }
        int i2 = 0;
        ArrayList<AutofillId> changedFieldIds5 = changedFieldIds4;
        ArrayList<String> changedDatasetIds5 = changedDatasetIds4;
        while (i2 < this.mViewStates.size()) {
            ViewState viewState = this.mViewStates.valueAt(i2);
            int state = viewState.getState();
            if ((state & 8) == 0) {
                lastResponse = lastResponse2;
                fieldClassificationIds = fieldClassificationIds2;
                hasAtLeastOneDataset = hasAtLeastOneDataset3;
                responseCount = responseCount4;
                str2 = str;
            } else {
                lastResponse = lastResponse2;
                if ((state & 2048) != 0) {
                    String datasetId2 = viewState.getDatasetId();
                    if (datasetId2 == null) {
                        fieldClassificationIds = fieldClassificationIds2;
                        Slog.w(TAG, "logContextCommitted(): no dataset id on " + viewState);
                        hasAtLeastOneDataset = hasAtLeastOneDataset3;
                        responseCount = responseCount4;
                        str2 = str;
                    } else {
                        fieldClassificationIds = fieldClassificationIds2;
                        AutofillValue autofilledValue = viewState.getAutofilledValue();
                        AutofillValue currentValue4 = viewState.getCurrentValue();
                        if (autofilledValue != null && autofilledValue.equals(currentValue4)) {
                            if (Helper.sDebug) {
                                Slog.d(TAG, "logContextCommitted(): ignoring changed " + viewState + " because it has same value that was autofilled");
                                hasAtLeastOneDataset = hasAtLeastOneDataset3;
                                responseCount = responseCount4;
                                str2 = str;
                            } else {
                                hasAtLeastOneDataset = hasAtLeastOneDataset3;
                                responseCount = responseCount4;
                                str2 = str;
                            }
                        } else {
                            if (Helper.sDebug) {
                                Slog.d(TAG, "logContextCommitted() found changed state: " + viewState);
                            }
                            if (changedFieldIds5 == null) {
                                changedFieldIds5 = new ArrayList<>();
                                changedDatasetIds5 = new ArrayList<>();
                            }
                            changedFieldIds5.add(viewState.id);
                            changedDatasetIds5.add(datasetId2);
                            hasAtLeastOneDataset = hasAtLeastOneDataset3;
                            responseCount = responseCount4;
                            str2 = str;
                        }
                    }
                } else {
                    fieldClassificationIds = fieldClassificationIds2;
                    AutofillValue currentValue5 = viewState.getCurrentValue();
                    if (currentValue5 == null) {
                        if (Helper.sDebug) {
                            Slog.d(TAG, "logContextCommitted(): skipping view without current value ( " + viewState + ")");
                            hasAtLeastOneDataset = hasAtLeastOneDataset3;
                            responseCount = responseCount4;
                            str2 = str;
                        } else {
                            hasAtLeastOneDataset = hasAtLeastOneDataset3;
                            responseCount = responseCount4;
                            str2 = str;
                        }
                    } else if (hasAtLeastOneDataset3) {
                        int j2 = 0;
                        while (j2 < responseCount4) {
                            FillResponse response2 = this.mResponses.valueAt(j2);
                            ArraySet<String> ignoredDatasets3 = ignoredDatasets2;
                            List<Dataset> datasets2 = response2.getDatasets();
                            if (datasets2 == null) {
                                currentValue = currentValue5;
                                hasAtLeastOneDataset2 = hasAtLeastOneDataset3;
                                responseCount2 = responseCount4;
                                str3 = str;
                            } else if (datasets2.isEmpty()) {
                                currentValue = currentValue5;
                                hasAtLeastOneDataset2 = hasAtLeastOneDataset3;
                                responseCount2 = responseCount4;
                                str3 = str;
                            } else {
                                ArrayMap<AutofillId, ArraySet<String>> manuallyFilledIds3 = manuallyFilledIds2;
                                int k = 0;
                                while (true) {
                                    hasAtLeastOneDataset2 = hasAtLeastOneDataset3;
                                    if (k >= datasets2.size()) {
                                        break;
                                    }
                                    Dataset dataset2 = datasets2.get(k);
                                    List<Dataset> datasets3 = datasets2;
                                    String datasetId3 = dataset2.getId();
                                    if (datasetId3 == null) {
                                        if (!Helper.sVerbose) {
                                            responseCount3 = responseCount4;
                                        } else {
                                            responseCount3 = responseCount4;
                                            Slog.v(TAG, str + dataset2);
                                        }
                                        currentValue2 = currentValue5;
                                        str4 = str;
                                    } else {
                                        responseCount3 = responseCount4;
                                        ArrayList<AutofillValue> values2 = dataset2.getFieldValues();
                                        int l = 0;
                                        while (true) {
                                            str4 = str;
                                            if (l >= values2.size()) {
                                                break;
                                            }
                                            AutofillValue candidate = values2.get(l);
                                            if (!currentValue5.equals(candidate)) {
                                                currentValue3 = currentValue5;
                                                values = values2;
                                            } else {
                                                if (!Helper.sDebug) {
                                                    currentValue3 = currentValue5;
                                                    values = values2;
                                                } else {
                                                    currentValue3 = currentValue5;
                                                    StringBuilder sb = new StringBuilder();
                                                    values = values2;
                                                    sb.append("field ");
                                                    sb.append(viewState.id);
                                                    sb.append(" was manually filled with value set by dataset ");
                                                    sb.append(datasetId3);
                                                    Slog.d(TAG, sb.toString());
                                                }
                                                if (manuallyFilledIds3 != null) {
                                                    manuallyFilledIds = manuallyFilledIds3;
                                                } else {
                                                    manuallyFilledIds = new ArrayMap<>();
                                                }
                                                ArraySet<String> datasetIds = manuallyFilledIds.get(viewState.id);
                                                if (datasetIds == null) {
                                                    datasetIds = new ArraySet<>(1);
                                                    manuallyFilledIds.put(viewState.id, datasetIds);
                                                }
                                                datasetIds.add(datasetId3);
                                                manuallyFilledIds3 = manuallyFilledIds;
                                            }
                                            l++;
                                            str = str4;
                                            currentValue5 = currentValue3;
                                            values2 = values;
                                        }
                                        currentValue2 = currentValue5;
                                        ArrayList<String> arrayList2 = this.mSelectedDatasetIds;
                                        if (arrayList2 == null || !arrayList2.contains(datasetId3)) {
                                            if (Helper.sVerbose) {
                                                Slog.v(TAG, "adding ignored dataset " + datasetId3);
                                            }
                                            if (ignoredDatasets3 != null) {
                                                ignoredDatasets = ignoredDatasets3;
                                            } else {
                                                ignoredDatasets = new ArraySet<>();
                                            }
                                            ignoredDatasets.add(datasetId3);
                                            ignoredDatasets3 = ignoredDatasets;
                                        }
                                    }
                                    k++;
                                    datasets2 = datasets3;
                                    str = str4;
                                    currentValue5 = currentValue2;
                                    hasAtLeastOneDataset3 = hasAtLeastOneDataset2;
                                    responseCount4 = responseCount3;
                                }
                                currentValue = currentValue5;
                                responseCount2 = responseCount4;
                                str3 = str;
                                ignoredDatasets2 = ignoredDatasets3;
                                manuallyFilledIds2 = manuallyFilledIds3;
                                j2++;
                                str = str3;
                                currentValue5 = currentValue;
                                hasAtLeastOneDataset3 = hasAtLeastOneDataset2;
                                responseCount4 = responseCount2;
                            }
                            if (Helper.sVerbose) {
                                Slog.v(TAG, "logContextCommitted() no datasets at " + j2);
                            }
                            ignoredDatasets2 = ignoredDatasets3;
                            j2++;
                            str = str3;
                            currentValue5 = currentValue;
                            hasAtLeastOneDataset3 = hasAtLeastOneDataset2;
                            responseCount4 = responseCount2;
                        }
                        hasAtLeastOneDataset = hasAtLeastOneDataset3;
                        responseCount = responseCount4;
                        str2 = str;
                    } else {
                        hasAtLeastOneDataset = hasAtLeastOneDataset3;
                        responseCount = responseCount4;
                        str2 = str;
                    }
                }
            }
            i2++;
            str = str2;
            lastResponse2 = lastResponse;
            fieldClassificationIds2 = fieldClassificationIds;
            hasAtLeastOneDataset3 = hasAtLeastOneDataset;
            responseCount4 = responseCount;
        }
        ArrayList<AutofillId> manuallyFilledFieldIds = null;
        ArrayList<ArrayList<String>> manuallyFilledDatasetIds = null;
        if (manuallyFilledIds2 != null) {
            int size = manuallyFilledIds2.size();
            manuallyFilledFieldIds = new ArrayList<>(size);
            manuallyFilledDatasetIds = new ArrayList<>(size);
            for (int i3 = 0; i3 < size; i3++) {
                AutofillId fieldId = manuallyFilledIds2.keyAt(i3);
                manuallyFilledFieldIds.add(fieldId);
                manuallyFilledDatasetIds.add(new ArrayList<>(manuallyFilledIds2.valueAt(i3)));
            }
        }
        this.mService.logContextCommittedLocked(this.id, this.mClientState, this.mSelectedDatasetIds, ignoredDatasets2, changedFieldIds5, changedDatasetIds5, manuallyFilledFieldIds, manuallyFilledDatasetIds, detectedFieldIds, detectedFieldClassifications, this.mComponentName, this.mCompatMode);
    }

    private void logFieldClassificationScore(FieldClassificationStrategy fcStrategy, FieldClassificationUserData userData) {
        String[] categoryIds;
        String[] userValues;
        Collection<ViewState> viewStates;
        final String[] userValues2 = userData.getValues();
        final String[] categoryIds2 = userData.getCategoryIds();
        String defaultAlgorithm = userData.getFieldClassificationAlgorithm();
        Bundle defaultArgs = userData.getDefaultFieldClassificationArgs();
        ArrayMap<String, String> algorithms = userData.getFieldClassificationAlgorithms();
        ArrayMap<String, Bundle> args = userData.getFieldClassificationArgs();
        if (userValues2 == null || categoryIds2 == null) {
            categoryIds = categoryIds2;
            userValues = userValues2;
        } else if (userValues2.length == categoryIds2.length) {
            int maxFieldsSize = UserData.getMaxFieldClassificationIdsSize();
            final ArrayList<AutofillId> detectedFieldIds = new ArrayList<>(maxFieldsSize);
            final ArrayList<FieldClassification> detectedFieldClassifications = new ArrayList<>(maxFieldsSize);
            synchronized (this.mLock) {
                try {
                    viewStates = this.mViewStates.values();
                } catch (Throwable th) {
                    th = th;
                    while (true) {
                        try {
                            break;
                        } catch (Throwable th2) {
                            th = th2;
                        }
                    }
                    throw th;
                }
            }
            final int viewsSize = viewStates.size();
            final AutofillId[] autofillIds = new AutofillId[viewsSize];
            ArrayList<AutofillValue> currentValues = new ArrayList<>(viewsSize);
            int k = 0;
            for (ViewState viewState : viewStates) {
                currentValues.add(viewState.getCurrentValue());
                autofillIds[k] = viewState.id;
                k++;
            }
            RemoteCallback callback = new RemoteCallback(new RemoteCallback.OnResultListener() { // from class: com.android.server.autofill.-$$Lambda$Session$PBwPPZBgjCZzQ_ztfoUbwBZupu8
                public final void onResult(Bundle bundle) {
                    Session.this.lambda$logFieldClassificationScore$2$Session(viewsSize, autofillIds, userValues2, categoryIds2, detectedFieldIds, detectedFieldClassifications, bundle);
                }
            });
            fcStrategy.calculateScores(callback, currentValues, userValues2, categoryIds2, defaultAlgorithm, defaultArgs, algorithms, args);
            return;
        } else {
            categoryIds = categoryIds2;
            userValues = userValues2;
        }
        int valuesLength = userValues == null ? -1 : userValues.length;
        int idsLength = categoryIds != null ? categoryIds.length : -1;
        Slog.w(TAG, "setScores(): user data mismatch: values.length = " + valuesLength + ", ids.length = " + idsLength);
    }

    /* JADX WARN: Incorrect condition in loop: B:45:0x0116 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public /* synthetic */ void lambda$logFieldClassificationScore$2$Session(int r17, android.view.autofill.AutofillId[] r18, java.lang.String[] r19, java.lang.String[] r20, java.util.ArrayList r21, java.util.ArrayList r22, android.os.Bundle r23) {
        /*
            Method dump skipped, instructions count: 362
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.autofill.Session.lambda$logFieldClassificationScore$2$Session(int, android.view.autofill.AutofillId[], java.lang.String[], java.lang.String[], java.util.ArrayList, java.util.ArrayList, android.os.Bundle):void");
    }

    @GuardedBy({"mLock"})
    public boolean showSaveLocked() {
        boolean allRequiredAreNotEmpty;
        boolean z;
        boolean atLeastOneChanged;
        boolean isUpdate;
        boolean z2;
        CharSequence serviceLabel;
        Drawable serviceIcon;
        boolean z3;
        int i;
        InternalValidator validator;
        AutofillId[] requiredIds;
        ArrayMap<AutofillId, AutofillValue> datasetValues;
        int i2;
        boolean atLeastOneChanged2;
        boolean isUpdate2;
        boolean allRequiredAreNotEmpty2;
        boolean z4;
        boolean atLeastOneChanged3;
        if (!this.mDestroyed) {
            FillResponse response = getLastResponseLocked("showSaveLocked(%s)");
            SaveInfo saveInfo = response == null ? null : response.getSaveInfo();
            if (saveInfo == null) {
                if (Helper.sVerbose) {
                    Slog.v(TAG, "showSaveLocked(" + this.id + "): no saveInfo from service");
                }
                return true;
            } else if ((saveInfo.getFlags() & 4) != 0) {
                if (Helper.sDebug) {
                    Slog.v(TAG, "showSaveLocked(" + this.id + "): service asked to delay save");
                }
                return false;
            } else {
                ArrayMap<AutofillId, InternalSanitizer> sanitizers = createSanitizers(saveInfo);
                ArrayMap<AutofillId, AutofillValue> currentValues = new ArrayMap<>();
                ArraySet<AutofillId> savableIds = new ArraySet<>();
                AutofillId[] requiredIds2 = saveInfo.getRequiredIds();
                boolean allRequiredAreNotEmpty3 = true;
                boolean atLeastOneChanged4 = false;
                boolean isUpdate3 = false;
                if (requiredIds2 == null) {
                    allRequiredAreNotEmpty = true;
                } else {
                    int i3 = 0;
                    while (true) {
                        if (i3 >= requiredIds2.length) {
                            allRequiredAreNotEmpty = allRequiredAreNotEmpty3;
                            break;
                        }
                        AutofillId id = requiredIds2[i3];
                        if (id == null) {
                            Slog.w(TAG, "null autofill id on " + Arrays.toString(requiredIds2));
                            allRequiredAreNotEmpty2 = allRequiredAreNotEmpty3;
                            z4 = atLeastOneChanged4;
                        } else {
                            savableIds.add(id);
                            ViewState viewState = this.mViewStates.get(id);
                            if (viewState == null) {
                                Slog.w(TAG, "showSaveLocked(): no ViewState for required " + id);
                                allRequiredAreNotEmpty = false;
                                break;
                            }
                            AutofillValue value = viewState.getCurrentValue();
                            if (value == null || value.isEmpty()) {
                                AutofillValue initialValue = getValueFromContextsLocked(id);
                                if (initialValue != null) {
                                    if (!Helper.sDebug) {
                                        allRequiredAreNotEmpty2 = allRequiredAreNotEmpty3;
                                    } else {
                                        StringBuilder sb = new StringBuilder();
                                        allRequiredAreNotEmpty2 = allRequiredAreNotEmpty3;
                                        sb.append("Value of required field ");
                                        sb.append(id);
                                        sb.append(" didn't change; using initial value (");
                                        sb.append(initialValue);
                                        sb.append(") instead");
                                        Slog.d(TAG, sb.toString());
                                    }
                                    value = initialValue;
                                } else {
                                    boolean atLeastOneChanged5 = atLeastOneChanged4;
                                    if (Helper.sDebug) {
                                        Slog.d(TAG, "empty value for required " + id);
                                    }
                                    allRequiredAreNotEmpty = false;
                                    atLeastOneChanged4 = atLeastOneChanged5;
                                }
                            } else {
                                allRequiredAreNotEmpty2 = allRequiredAreNotEmpty3;
                            }
                            AutofillValue value2 = getSanitizedValue(sanitizers, id, value);
                            if (value2 == null) {
                                if (Helper.sDebug) {
                                    Slog.d(TAG, "value of required field " + id + " failed sanitization");
                                }
                                allRequiredAreNotEmpty = false;
                            } else {
                                viewState.setSanitizedValue(value2);
                                currentValues.put(id, value2);
                                AutofillValue filledValue = viewState.getAutofilledValue();
                                if (value2.equals(filledValue)) {
                                    z4 = atLeastOneChanged4;
                                } else {
                                    boolean changed = true;
                                    if (filledValue == null) {
                                        AutofillValue initialValue2 = getValueFromContextsLocked(id);
                                        if (initialValue2 == null || !initialValue2.equals(value2)) {
                                            atLeastOneChanged3 = atLeastOneChanged4;
                                        } else {
                                            if (Helper.sDebug) {
                                                StringBuilder sb2 = new StringBuilder();
                                                atLeastOneChanged3 = atLeastOneChanged4;
                                                sb2.append("id ");
                                                sb2.append(id);
                                                sb2.append(" is part of dataset but initial value didn't change: ");
                                                sb2.append(value2);
                                                Slog.d(TAG, sb2.toString());
                                            } else {
                                                atLeastOneChanged3 = atLeastOneChanged4;
                                            }
                                            changed = false;
                                        }
                                    } else {
                                        atLeastOneChanged3 = atLeastOneChanged4;
                                        isUpdate3 = true;
                                    }
                                    if (!changed) {
                                        atLeastOneChanged4 = atLeastOneChanged3;
                                    } else {
                                        if (Helper.sDebug) {
                                            Slog.d(TAG, "found a change on required " + id + ": " + filledValue + " => " + value2);
                                        }
                                        atLeastOneChanged4 = true;
                                    }
                                    i3++;
                                    allRequiredAreNotEmpty3 = allRequiredAreNotEmpty2;
                                }
                            }
                        }
                        atLeastOneChanged4 = z4;
                        i3++;
                        allRequiredAreNotEmpty3 = allRequiredAreNotEmpty2;
                    }
                }
                AutofillId[] optionalIds = saveInfo.getOptionalIds();
                if (Helper.sVerbose) {
                    StringBuilder sb3 = new StringBuilder();
                    sb3.append("allRequiredAreNotEmpty: ");
                    sb3.append(allRequiredAreNotEmpty);
                    sb3.append(" hasOptional: ");
                    sb3.append(optionalIds != null);
                    Slog.v(TAG, sb3.toString());
                }
                if (allRequiredAreNotEmpty) {
                    if (optionalIds != null && (!atLeastOneChanged4 || !isUpdate3)) {
                        for (AutofillId id2 : optionalIds) {
                            savableIds.add(id2);
                            ViewState viewState2 = this.mViewStates.get(id2);
                            if (viewState2 == null) {
                                Slog.w(TAG, "no ViewState for optional " + id2);
                                atLeastOneChanged2 = atLeastOneChanged4;
                                isUpdate2 = isUpdate3;
                            } else if ((viewState2.getState() & 8) != 0) {
                                AutofillValue value3 = getSanitizedValue(sanitizers, id2, viewState2.getCurrentValue());
                                if (value3 == null) {
                                    if (!Helper.sDebug) {
                                        atLeastOneChanged2 = atLeastOneChanged4;
                                        isUpdate2 = isUpdate3;
                                    } else {
                                        atLeastOneChanged2 = atLeastOneChanged4;
                                        StringBuilder sb4 = new StringBuilder();
                                        isUpdate2 = isUpdate3;
                                        sb4.append("value of opt. field ");
                                        sb4.append(id2);
                                        sb4.append(" failed sanitization");
                                        Slog.d(TAG, sb4.toString());
                                    }
                                } else {
                                    boolean atLeastOneChanged6 = atLeastOneChanged4;
                                    boolean isUpdate4 = isUpdate3;
                                    currentValues.put(id2, value3);
                                    AutofillValue filledValue2 = viewState2.getAutofilledValue();
                                    if (!value3.equals(filledValue2)) {
                                        if (Helper.sDebug) {
                                            Slog.d(TAG, "found a change on optional " + id2 + ": " + filledValue2 + " => " + value3);
                                        }
                                        if (filledValue2 == null) {
                                            isUpdate3 = isUpdate4;
                                        } else {
                                            isUpdate3 = true;
                                        }
                                        atLeastOneChanged6 = true;
                                    } else {
                                        isUpdate3 = isUpdate4;
                                    }
                                    atLeastOneChanged4 = atLeastOneChanged6;
                                }
                            } else {
                                atLeastOneChanged2 = atLeastOneChanged4;
                                isUpdate2 = isUpdate3;
                                AutofillValue initialValue3 = getValueFromContextsLocked(id2);
                                if (Helper.sDebug) {
                                    Slog.d(TAG, "no current value for " + id2 + "; initial value is " + initialValue3);
                                }
                                if (initialValue3 != null) {
                                    currentValues.put(id2, initialValue3);
                                }
                            }
                            atLeastOneChanged4 = atLeastOneChanged2;
                            isUpdate3 = isUpdate2;
                        }
                        atLeastOneChanged = atLeastOneChanged4;
                        isUpdate = isUpdate3;
                    } else {
                        atLeastOneChanged = atLeastOneChanged4;
                        isUpdate = isUpdate3;
                    }
                    if (atLeastOneChanged) {
                        if (Helper.sDebug) {
                            Slog.d(TAG, "at least one field changed, validate fields for save UI");
                        }
                        InternalValidator validator2 = saveInfo.getValidator();
                        if (validator2 != null) {
                            LogMaker log = newLogMaker(1133);
                            try {
                                boolean isValid = validator2.isValid(this);
                                if (Helper.sDebug) {
                                    Slog.d(TAG, validator2 + " returned " + isValid);
                                }
                                if (isValid) {
                                    i2 = 10;
                                } else {
                                    i2 = 5;
                                }
                                log.setType(i2);
                                this.mMetricsLogger.write(log);
                                if (!isValid) {
                                    Slog.i(TAG, "not showing save UI because fields failed validation");
                                    return true;
                                }
                            } catch (Exception e) {
                                Slog.e(TAG, "Not showing save UI because validation failed:", e);
                                log.setType(11);
                                this.mMetricsLogger.write(log);
                                return true;
                            }
                        }
                        List<Dataset> datasets = response.getDatasets();
                        if (datasets != null) {
                            int i4 = 0;
                            while (i4 < datasets.size()) {
                                Dataset dataset = datasets.get(i4);
                                ArrayMap<AutofillId, AutofillValue> datasetValues2 = Helper.getFields(dataset);
                                if (Helper.sVerbose) {
                                    Slog.v(TAG, "Checking if saved fields match contents of dataset #" + i4 + ": " + dataset + "; savableIds=" + savableIds);
                                }
                                int j = 0;
                                while (j < savableIds.size()) {
                                    AutofillId id3 = savableIds.valueAt(j);
                                    List<Dataset> datasets2 = datasets;
                                    AutofillValue currentValue = currentValues.get(id3);
                                    if (currentValue == null) {
                                        if (!Helper.sDebug) {
                                            validator = validator2;
                                            requiredIds = requiredIds2;
                                            datasetValues = datasetValues2;
                                        } else {
                                            validator = validator2;
                                            StringBuilder sb5 = new StringBuilder();
                                            requiredIds = requiredIds2;
                                            sb5.append("dataset has value for field that is null: ");
                                            sb5.append(id3);
                                            Slog.d(TAG, sb5.toString());
                                            datasetValues = datasetValues2;
                                        }
                                    } else {
                                        validator = validator2;
                                        requiredIds = requiredIds2;
                                        AutofillValue datasetValue = datasetValues2.get(id3);
                                        if (!currentValue.equals(datasetValue)) {
                                            if (Helper.sDebug) {
                                                Slog.d(TAG, "found a dataset change on id " + id3 + ": from " + datasetValue + " to " + currentValue);
                                            }
                                            i4++;
                                            validator2 = validator;
                                            datasets = datasets2;
                                            requiredIds2 = requiredIds;
                                        } else {
                                            datasetValues = datasetValues2;
                                            if (Helper.sVerbose) {
                                                Slog.v(TAG, "no dataset changes for id " + id3);
                                            }
                                        }
                                    }
                                    j++;
                                    validator2 = validator;
                                    datasets = datasets2;
                                    requiredIds2 = requiredIds;
                                    datasetValues2 = datasetValues;
                                }
                                if (Helper.sDebug) {
                                    Slog.d(TAG, "ignoring Save UI because all fields match contents of dataset #" + i4 + ": " + dataset);
                                    return true;
                                }
                                return true;
                            }
                            z2 = true;
                        } else {
                            z2 = true;
                        }
                        if (Helper.sDebug) {
                            Slog.d(TAG, "Good news, everyone! All checks passed, show save UI for " + this.id + "!");
                        }
                        this.mHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.autofill.-$$Lambda$Session$NtvZwhlT1c4eLjg2qI6EER2oCtY
                            @Override // java.util.function.Consumer
                            public final void accept(Object obj) {
                                ((Session) obj).logSaveShown();
                            }
                        }, this));
                        IAutoFillManagerClient client = getClient();
                        this.mPendingSaveUi = new PendingUi(this.mActivityToken, this.id, client);
                        synchronized (this.mLock) {
                            try {
                                serviceLabel = this.mService.getServiceLabelLocked();
                                serviceIcon = this.mService.getServiceIconLocked();
                            } catch (Throwable th) {
                                th = th;
                                while (true) {
                                    try {
                                        break;
                                    } catch (Throwable th2) {
                                        th = th2;
                                    }
                                }
                                throw th;
                            }
                        }
                        if (serviceLabel == null) {
                            z3 = z2;
                            i = 0;
                        } else if (serviceIcon != null) {
                            boolean z5 = z2;
                            getUiForShowing().showSaveUi(serviceLabel, serviceIcon, this.mService.getServicePackageName(), saveInfo, this, this.mComponentName, this, this.mPendingSaveUi, isUpdate, this.mCompatMode);
                            if (client != null) {
                                try {
                                    client.setSaveUiState(this.id, z5);
                                } catch (RemoteException e2) {
                                    Slog.e(TAG, "Error notifying client to set save UI state to shown: " + e2);
                                }
                            }
                            this.mIsSaving = z5;
                            return false;
                        } else {
                            z3 = z2;
                            i = 0;
                        }
                        wtf(null, "showSaveLocked(): no service label or icon", new Object[i]);
                        return z3;
                    }
                    z = true;
                    atLeastOneChanged4 = atLeastOneChanged;
                } else {
                    z = true;
                }
                if (Helper.sDebug) {
                    Slog.d(TAG, "showSaveLocked(" + this.id + "): with no changes, comes no responsibilities.allRequiredAreNotNull=" + allRequiredAreNotEmpty + ", atLeastOneChanged=" + atLeastOneChanged4);
                }
                return z;
            }
        }
        Slog.w(TAG, "Call to Session#showSaveLocked() rejected - session: " + this.id + " destroyed");
        return false;
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
        if (sanitizers == null || value == null) {
            return value;
        }
        ViewState state = this.mViewStates.get(id);
        AutofillValue sanitized = state == null ? null : state.getSanitizedValue();
        if (sanitized == null) {
            InternalSanitizer sanitizer = sanitizers.get(id);
            if (sanitizer == null) {
                return value;
            }
            sanitized = sanitizer.sanitize(value);
            if (Helper.sDebug) {
                Slog.d(TAG, "Value for " + id + "(" + value + ") sanitized to " + sanitized);
            }
            if (state != null) {
                state.setSanitizedValue(sanitized);
            }
        }
        return sanitized;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public boolean isSavingLocked() {
        return this.mIsSaving;
    }

    @GuardedBy({"mLock"})
    private AutofillValue getValueFromContextsLocked(AutofillId autofillId) {
        int numContexts = this.mContexts.size();
        for (int i = numContexts - 1; i >= 0; i--) {
            FillContext context = this.mContexts.get(i);
            AssistStructure.ViewNode node = Helper.findViewNodeByAutofillId(context.getStructure(), autofillId);
            if (node != null) {
                AutofillValue value = node.getAutofillValue();
                if (Helper.sDebug) {
                    Slog.d(TAG, "getValueFromContexts(" + this.id + SliceClientPermissions.SliceAuthority.DELIMITER + autofillId + ") at " + i + ": " + value);
                }
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    @GuardedBy({"mLock"})
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

    private void updateValuesForSaveLocked() {
        ArrayMap<AutofillId, InternalSanitizer> sanitizers = createSanitizers(getSaveInfoLocked());
        int numContexts = this.mContexts.size();
        for (int contextNum = 0; contextNum < numContexts; contextNum++) {
            FillContext context = this.mContexts.get(contextNum);
            AssistStructure.ViewNode[] nodes = context.findViewNodesByAutofillIds(getIdsOfAllViewStatesLocked());
            if (Helper.sVerbose) {
                Slog.v(TAG, "updateValuesForSaveLocked(): updating " + context);
            }
            for (int viewStateNum = 0; viewStateNum < this.mViewStates.size(); viewStateNum++) {
                ViewState viewState = this.mViewStates.valueAt(viewStateNum);
                AutofillId id = viewState.id;
                AutofillValue value = viewState.getCurrentValue();
                if (value == null) {
                    if (Helper.sVerbose) {
                        Slog.v(TAG, "updateValuesForSaveLocked(): skipping " + id);
                    }
                } else {
                    AssistStructure.ViewNode node = nodes[viewStateNum];
                    if (node == null) {
                        Slog.w(TAG, "callSaveLocked(): did not find node with id " + id);
                    } else {
                        if (Helper.sVerbose) {
                            Slog.v(TAG, "updateValuesForSaveLocked(): updating " + id + " to " + value);
                        }
                        AutofillValue sanitizedValue = viewState.getSanitizedValue();
                        if (sanitizedValue == null) {
                            sanitizedValue = getSanitizedValue(sanitizers, id, value);
                        }
                        if (sanitizedValue != null) {
                            node.updateAutofillValue(sanitizedValue);
                        } else if (Helper.sDebug) {
                            Slog.d(TAG, "updateValuesForSaveLocked(): not updating field " + id + " because it failed sanitization");
                        }
                    }
                }
            }
            context.getStructure().sanitizeForParceling(false);
            if (Helper.sVerbose) {
                Slog.v(TAG, "updateValuesForSaveLocked(): dumping structure of " + context + " before calling service.save()");
                context.getStructure().dump(false);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void callSaveLocked() {
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#callSaveLocked() rejected - session: " + this.id + " destroyed");
        } else if (this.mRemoteFillService == null) {
            wtf(null, "callSaveLocked() called without a remote service. mForAugmentedAutofillOnly: %s", Boolean.valueOf(this.mForAugmentedAutofillOnly));
        } else {
            if (Helper.sVerbose) {
                Slog.v(TAG, "callSaveLocked(" + this.id + "): mViewStates=" + this.mViewStates);
            }
            if (this.mContexts == null) {
                Slog.w(TAG, "callSaveLocked(): no contexts");
                return;
            }
            updateValuesForSaveLocked();
            cancelCurrentRequestLocked();
            ArrayList<FillContext> contexts = mergePreviousSessionLocked(true);
            SaveRequest saveRequest = new SaveRequest(contexts, this.mClientState, this.mSelectedDatasetIds);
            this.mRemoteFillService.onSaveRequest(saveRequest);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ArrayList<FillContext> mergePreviousSessionLocked(boolean forSave) {
        ArrayList<Session> previousSessions = this.mService.getPreviousSessionsLocked(this);
        if (previousSessions != null) {
            if (Helper.sDebug) {
                Slog.d(TAG, "mergeSessions(" + this.id + "): Merging the content of " + previousSessions.size() + " sessions for task " + this.taskId);
            }
            ArrayList<FillContext> contexts = new ArrayList<>();
            for (int i = 0; i < previousSessions.size(); i++) {
                Session previousSession = previousSessions.get(i);
                ArrayList<FillContext> previousContexts = previousSession.mContexts;
                if (previousContexts == null) {
                    Slog.w(TAG, "mergeSessions(" + this.id + "): Not merging null contexts from " + previousSession.id);
                } else {
                    if (forSave) {
                        previousSession.updateValuesForSaveLocked();
                    }
                    if (Helper.sDebug) {
                        Slog.d(TAG, "mergeSessions(" + this.id + "): adding " + previousContexts.size() + " context from previous session #" + previousSession.id);
                    }
                    contexts.addAll(previousContexts);
                    if (this.mClientState == null && previousSession.mClientState != null) {
                        if (Helper.sDebug) {
                            Slog.d(TAG, "mergeSessions(" + this.id + "): setting client state from previous session" + previousSession.id);
                        }
                        this.mClientState = previousSession.mClientState;
                    }
                }
            }
            contexts.addAll(this.mContexts);
            return contexts;
        }
        return new ArrayList<>(this.mContexts);
    }

    @GuardedBy({"mLock"})
    private void requestNewFillResponseOnViewEnteredIfNecessaryLocked(AutofillId id, ViewState viewState, int flags) {
        if ((flags & 1) != 0) {
            this.mForAugmentedAutofillOnly = false;
            if (Helper.sDebug) {
                Slog.d(TAG, "Re-starting session on view " + id + " and flags " + flags);
            }
            requestNewFillResponseLocked(viewState, 256, flags);
        } else if (shouldStartNewPartitionLocked(id)) {
            if (Helper.sDebug) {
                Slog.d(TAG, "Starting partition or augmented request for view id " + id + ": " + viewState.getStateAsString());
            }
            requestNewFillResponseLocked(viewState, 32, flags);
        } else if (Helper.sVerbose) {
            Slog.v(TAG, "Not starting new partition for view " + id + ": " + viewState.getStateAsString());
        }
    }

    @GuardedBy({"mLock"})
    private boolean shouldStartNewPartitionLocked(AutofillId id) {
        SparseArray<FillResponse> sparseArray = this.mResponses;
        if (sparseArray == null) {
            return true;
        }
        int numResponses = sparseArray.size();
        if (numResponses >= AutofillManagerService.getPartitionMaxCount()) {
            Slog.e(TAG, "Not starting a new partition on " + id + " because session " + this.id + " reached maximum of " + AutofillManagerService.getPartitionMaxCount());
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
    @GuardedBy({"mLock"})
    public void updateLocked(AutofillId id, Rect virtualBounds, AutofillValue value, int action, int flags) {
        ArrayList<AutofillId> arrayList;
        String filterText;
        if (this.mDestroyed) {
            Slog.w(TAG, "Call to Session#updateLocked() rejected - session: " + id + " destroyed");
            return;
        }
        id.setSessionId(this.id);
        if (Helper.sVerbose) {
            Slog.v(TAG, "updateLocked(" + this.id + "): id=" + id + ", action=" + actionAsString(action) + ", flags=" + flags);
        }
        ViewState viewState = this.mViewStates.get(id);
        if (viewState == null) {
            if (action == 1 || action == 4 || action == 2) {
                if (Helper.sVerbose) {
                    Slog.v(TAG, "Creating viewState for " + id);
                }
                boolean isIgnored = isIgnoredLocked(id);
                viewState = new ViewState(id, this, isIgnored ? 128 : 1);
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
        if (action != 1) {
            if (action == 2) {
                if (Helper.sVerbose && virtualBounds != null) {
                    Slog.v(TAG, "entered on virtual child " + id + ": " + virtualBounds);
                }
                this.mCurrentViewId = viewState.id;
                if (value != null) {
                    viewState.setCurrentValue(value);
                }
                if (this.mCompatMode && (viewState.getState() & 512) != 0) {
                    if (Helper.sDebug) {
                        Slog.d(TAG, "Ignoring VIEW_ENTERED on URL BAR (id=" + id + ")");
                        return;
                    }
                    return;
                } else if ((flags & 1) == 0 && (arrayList = this.mAugmentedAutofillableIds) != null && arrayList.contains(id)) {
                    if (Helper.sDebug) {
                        Slog.d(TAG, "updateLocked(" + id + "): augmented-autofillable");
                    }
                    triggerAugmentedAutofillLocked(flags);
                    return;
                } else {
                    requestNewFillResponseOnViewEnteredIfNecessaryLocked(id, viewState, flags);
                    if (!Objects.equals(this.mCurrentViewId, viewState.id)) {
                        this.mUi.hideFillUi(this);
                        this.mCurrentViewId = viewState.id;
                        hideAugmentedAutofillLocked(viewState);
                    }
                    viewState.update(value, virtualBounds, flags);
                    return;
                }
            }
            if (action == 3) {
                if (Objects.equals(this.mCurrentViewId, viewState.id)) {
                    if (Helper.sVerbose) {
                        Slog.v(TAG, "Exiting view " + id);
                    }
                    this.mUi.hideFillUi(this);
                    hideAugmentedAutofillLocked(viewState);
                    this.mCurrentViewId = null;
                    return;
                }
                return;
            } else if (action == 4) {
                if (this.mCompatMode && (viewState.getState() & 512) != 0) {
                    AssistStructure.ViewNode viewNode = this.mUrlBar;
                    String currentUrl = viewNode == null ? null : viewNode.getText().toString().trim();
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
                    if (filledValue != null) {
                        if (filledValue.equals(value)) {
                            if (Helper.sVerbose) {
                                Slog.v(TAG, "ignoring autofilled change on id " + id);
                            }
                            viewState.resetState(8);
                            return;
                        } else if (viewState.id.equals(this.mCurrentViewId) && (viewState.getState() & 4) != 0) {
                            if (Helper.sVerbose) {
                                Slog.v(TAG, "field changed after autofill on id " + id);
                            }
                            viewState.resetState(4);
                            ViewState currentView = this.mViewStates.get(this.mCurrentViewId);
                            currentView.maybeCallOnFillReady(flags);
                        }
                    }
                    viewState.setState(8);
                    if (value == null || !value.isText()) {
                        filterText = null;
                    } else {
                        CharSequence text = value.getTextValue();
                        filterText = text != null ? text.toString() : null;
                    }
                    getUiForShowing().filterFillUi(filterText, this);
                    return;
                } else {
                    return;
                }
            } else {
                Slog.w(TAG, "updateLocked(): unknown action: " + action);
                return;
            }
        }
        this.mCurrentViewId = viewState.id;
        viewState.update(value, virtualBounds, flags);
        requestNewFillResponseLocked(viewState, 16, flags);
    }

    @GuardedBy({"mLock"})
    private void hideAugmentedAutofillLocked(ViewState viewState) {
        if ((viewState.getState() & 4096) != 0) {
            viewState.resetState(4096);
            cancelAugmentedAutofillLocked();
        }
    }

    @GuardedBy({"mLock"})
    private boolean isIgnoredLocked(AutofillId id) {
        FillResponse response = getLastResponseLocked(null);
        if (response == null) {
            return false;
        }
        return ArrayUtils.contains(response.getIgnoredIds(), id);
    }

    @Override // com.android.server.autofill.ViewState.Listener
    public void onFillReady(FillResponse response, AutofillId filledId, AutofillValue value) {
        String filterText;
        CharSequence serviceLabel;
        Drawable serviceIcon;
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillReady() rejected - session: " + this.id + " destroyed");
                return;
            }
            if (value != null && value.isText()) {
                String filterText2 = value.getTextValue().toString();
                filterText = filterText2;
            } else {
                filterText = null;
            }
            synchronized (this.mLock) {
                serviceLabel = this.mService.getServiceLabelLocked();
                serviceIcon = this.mService.getServiceIconLocked();
            }
            if (serviceLabel == null || serviceIcon == null) {
                wtf(null, "onFillReady(): no service label or icon", new Object[0]);
                return;
            }
            getUiForShowing().showFillUi(filledId, response, filterText, this.mService.getServicePackageName(), this.mComponentName, serviceLabel, serviceIcon, this, this.id, this.mCompatMode);
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

    private void notifyUnavailableToClient(int sessionFinishedState, ArrayList<AutofillId> autofillableIds) {
        synchronized (this.mLock) {
            if (this.mCurrentViewId == null) {
                return;
            }
            try {
                if (this.mHasCallback) {
                    this.mClient.notifyNoFillUi(this.id, this.mCurrentViewId, sessionFinishedState);
                } else if (sessionFinishedState != 0) {
                    this.mClient.setSessionFinished(sessionFinishedState, autofillableIds);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client no fill UI: id=" + this.mCurrentViewId, e);
            }
        }
    }

    @GuardedBy({"mLock"})
    private void updateTrackedIdsLocked() {
        boolean saveOnFinish;
        AutofillId saveTriggerId;
        int flags;
        ArraySet<AutofillId> trackedViews;
        ArraySet<AutofillId> fillableIds;
        boolean z;
        FillResponse response = getLastResponseLocked(null);
        if (response == null) {
            return;
        }
        ArraySet<AutofillId> trackedViews2 = null;
        this.mSaveOnAllViewsInvisible = false;
        SaveInfo saveInfo = response.getSaveInfo();
        boolean z2 = true;
        if (saveInfo == null) {
            saveOnFinish = true;
            saveTriggerId = null;
            flags = 0;
            trackedViews = null;
        } else {
            AutofillId saveTriggerId2 = saveInfo.getTriggerId();
            if (saveTriggerId2 != null) {
                writeLog(1228);
            }
            int flags2 = saveInfo.getFlags();
            if ((flags2 & 1) == 0) {
                z = false;
            } else {
                z = true;
            }
            this.mSaveOnAllViewsInvisible = z;
            if (this.mSaveOnAllViewsInvisible) {
                if (0 == 0) {
                    trackedViews2 = new ArraySet<>();
                }
                if (saveInfo.getRequiredIds() != null) {
                    Collections.addAll(trackedViews2, saveInfo.getRequiredIds());
                }
                if (saveInfo.getOptionalIds() != null) {
                    Collections.addAll(trackedViews2, saveInfo.getOptionalIds());
                }
            }
            if ((flags2 & 2) == 0) {
                saveOnFinish = true;
                saveTriggerId = saveTriggerId2;
                flags = flags2;
                trackedViews = trackedViews2;
            } else {
                saveOnFinish = false;
                saveTriggerId = saveTriggerId2;
                flags = flags2;
                trackedViews = trackedViews2;
            }
        }
        List<Dataset> datasets = response.getDatasets();
        ArraySet<AutofillId> fillableIds2 = null;
        if (datasets == null) {
            fillableIds = null;
        } else {
            for (int i = 0; i < datasets.size(); i++) {
                Dataset dataset = datasets.get(i);
                ArrayList<AutofillId> fieldIds = dataset.getFieldIds();
                if (fieldIds != null) {
                    for (int j = 0; j < fieldIds.size(); j++) {
                        AutofillId id = fieldIds.get(j);
                        if (trackedViews == null || !trackedViews.contains(id)) {
                            fillableIds2 = ArrayUtils.add(fillableIds2, id);
                        }
                    }
                }
            }
            fillableIds = fillableIds2;
        }
        try {
            if (Helper.sVerbose) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("updateTrackedIdsLocked(): ");
                    sb.append(trackedViews);
                    sb.append(" => ");
                    sb.append(fillableIds);
                    sb.append(" triggerId: ");
                    sb.append(saveTriggerId);
                    sb.append(" saveOnFinish:");
                    sb.append(saveOnFinish);
                    sb.append(" flags: ");
                    sb.append(flags);
                    sb.append(" hasSaveInfo: ");
                    if (saveInfo == null) {
                        z2 = false;
                    }
                    sb.append(z2);
                    Slog.v(TAG, sb.toString());
                } catch (RemoteException e) {
                    e = e;
                    Slog.w(TAG, "Cannot set tracked ids", e);
                }
            }
        } catch (RemoteException e2) {
            e = e2;
        }
        try {
            this.mClient.setTrackedViews(this.id, Helper.toArray(trackedViews), this.mSaveOnAllViewsInvisible, saveOnFinish, Helper.toArray(fillableIds), saveTriggerId);
        } catch (RemoteException e3) {
            e = e3;
            Slog.w(TAG, "Cannot set tracked ids", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
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

    @GuardedBy({"mLock"})
    private void replaceResponseLocked(FillResponse oldResponse, FillResponse newResponse, Bundle newClientState) {
        setViewStatesLocked(oldResponse, 1, true);
        newResponse.setRequestId(oldResponse.getRequestId());
        this.mResponses.put(newResponse.getRequestId(), newResponse);
        processResponseLocked(newResponse, newClientState, 0);
    }

    @GuardedBy({"mLock"})
    private void processNullResponseLocked(int requestId, int flags) {
        ArrayList<AutofillId> autofillableIds;
        if ((flags & 1) != 0) {
            getUiForShowing().showError(17039545, this);
        }
        FillContext context = getFillContextByRequestIdLocked(requestId);
        if (context != null) {
            AssistStructure structure = context.getStructure();
            autofillableIds = Helper.getAutofillIds(structure, true);
        } else {
            Slog.w(TAG, "processNullResponseLocked(): no context for req " + requestId);
            autofillableIds = null;
        }
        this.mService.resetLastResponse();
        this.mAugmentedAutofillDestroyer = triggerAugmentedAutofillLocked(flags);
        if (this.mAugmentedAutofillDestroyer == null && (flags & 4) == 0) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "canceling session " + this.id + " when service returned null and it cannot be augmented. AutofillableIds: " + autofillableIds);
            }
            notifyUnavailableToClient(2, autofillableIds);
            removeSelf();
            return;
        }
        if (Helper.sVerbose) {
            if ((flags & 4) != 0) {
                Slog.v(TAG, "keeping session " + this.id + " when service returned null and augmented service is disabled for password fields. AutofillableIds: " + autofillableIds);
            } else {
                Slog.v(TAG, "keeping session " + this.id + " when service returned null but it can be augmented. AutofillableIds: " + autofillableIds);
            }
        }
        this.mAugmentedAutofillableIds = autofillableIds;
        try {
            this.mClient.setState(32);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error setting client to autofill-only", e);
        }
    }

    @GuardedBy({"mLock"})
    private Runnable triggerAugmentedAutofillLocked(int flags) {
        if ((flags & 4) != 0) {
            return null;
        }
        int supportedModes = this.mService.getSupportedSmartSuggestionModesLocked();
        if (supportedModes == 0) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "triggerAugmentedAutofillLocked(): no supported modes");
            }
            return null;
        }
        final RemoteAugmentedAutofillService remoteService = this.mService.getRemoteAugmentedAutofillServiceLocked();
        if (remoteService == null) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "triggerAugmentedAutofillLocked(): no service for user");
            }
            return null;
        } else if ((supportedModes & 1) == 0) {
            Slog.w(TAG, "Unsupported Smart Suggestion mode: " + supportedModes);
            return null;
        } else if (this.mCurrentViewId == null) {
            Slog.w(TAG, "triggerAugmentedAutofillLocked(): no view currently focused");
            return null;
        } else {
            boolean isWhitelisted = this.mService.isWhitelistedForAugmentedAutofillLocked(this.mComponentName);
            String historyItem = "aug:id=" + this.id + " u=" + this.uid + " m=1 a=" + ComponentName.flattenToShortString(this.mComponentName) + " f=" + this.mCurrentViewId + " s=" + remoteService.getComponentName() + " w=" + isWhitelisted;
            this.mService.getMaster().logRequestLocked(historyItem);
            if (!isWhitelisted) {
                if (Helper.sVerbose) {
                    Slog.v(TAG, "triggerAugmentedAutofillLocked(): " + ComponentName.flattenToShortString(this.mComponentName) + " not whitelisted ");
                }
                return null;
            }
            if (Helper.sVerbose) {
                Slog.v(TAG, "calling Augmented Autofill Service (" + ComponentName.flattenToShortString(remoteService.getComponentName()) + ") on view " + this.mCurrentViewId + " using suggestion mode " + AutofillManager.getSmartSuggestionModeToString(1) + " when server returned null for session " + this.id);
            }
            ViewState viewState = this.mViewStates.get(this.mCurrentViewId);
            viewState.setState(4096);
            AutofillValue currentValue = viewState.getCurrentValue();
            if (this.mAugmentedRequestsLogs == null) {
                this.mAugmentedRequestsLogs = new ArrayList<>();
            }
            LogMaker log = newLogMaker(1630, remoteService.getComponentName().getPackageName());
            this.mAugmentedRequestsLogs.add(log);
            AutofillId focusedId = AutofillId.withoutSession(this.mCurrentViewId);
            remoteService.onRequestAutofillLocked(this.id, this.mClient, this.taskId, this.mComponentName, focusedId, currentValue);
            if (this.mAugmentedAutofillDestroyer == null) {
                this.mAugmentedAutofillDestroyer = new Runnable() { // from class: com.android.server.autofill.-$$Lambda$Session$dezqLt87MD2Cwsac8Jv6xKKv0sw
                    @Override // java.lang.Runnable
                    public final void run() {
                        RemoteAugmentedAutofillService.this.onDestroyAutofillWindowsRequest();
                    }
                };
            }
            return this.mAugmentedAutofillDestroyer;
        }
    }

    @GuardedBy({"mLock"})
    private void cancelAugmentedAutofillLocked() {
        RemoteAugmentedAutofillService remoteService = this.mService.getRemoteAugmentedAutofillServiceLocked();
        if (remoteService == null) {
            Slog.w(TAG, "cancelAugmentedAutofillLocked(): no service for user");
            return;
        }
        if (Helper.sVerbose) {
            Slog.v(TAG, "cancelAugmentedAutofillLocked() on " + this.mCurrentViewId);
        }
        remoteService.onDestroyAutofillWindowsRequest();
    }

    @GuardedBy({"mLock"})
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
        AutofillId autofillId = this.mCurrentViewId;
        if (autofillId == null) {
            return;
        }
        ViewState currentView = this.mViewStates.get(autofillId);
        currentView.maybeCallOnFillReady(flags);
    }

    @GuardedBy({"mLock"})
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

    @GuardedBy({"mLock"})
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

    @GuardedBy({"mLock"})
    private ViewState createOrUpdateViewStateLocked(AutofillId id, int state, AutofillValue value) {
        ViewState viewState = this.mViewStates.get(id);
        if (viewState != null) {
            viewState.setState(state);
        } else {
            viewState = new ViewState(id, this, state);
            if (Helper.sVerbose) {
                Slog.v(TAG, "Adding autofillable view with id " + id + " and state " + state);
            }
            viewState.setCurrentValue(findValueLocked(id));
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

    @GuardedBy({"mLock"})
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
    @GuardedBy({"mLock"})
    public void dumpLocked(String prefix, PrintWriter pw) {
        String prefix2 = prefix + "  ";
        pw.print(prefix);
        pw.print("id: ");
        pw.println(this.id);
        pw.print(prefix);
        pw.print("uid: ");
        pw.println(this.uid);
        pw.print(prefix);
        pw.print("taskId: ");
        pw.println(this.taskId);
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
        long j = this.mUiShownTime;
        if (j == 0) {
            pw.println("N/A");
        } else {
            TimeUtils.formatDuration(j - this.mStartTime, pw);
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
        SparseArray<FillResponse> sparseArray = this.mResponses;
        if (sparseArray == null) {
            pw.println("null");
        } else {
            pw.println(sparseArray.size());
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
        ArrayList<FillContext> arrayList = this.mContexts;
        if (arrayList != null) {
            int numContexts = arrayList.size();
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
        if (this.mForAugmentedAutofillOnly) {
            pw.print(prefix);
            pw.println("For Augmented Autofill Only");
        }
        if (this.mAugmentedAutofillDestroyer != null) {
            pw.print(prefix);
            pw.println("has mAugmentedAutofillDestroyer");
        }
        if (this.mAugmentedRequestsLogs != null) {
            pw.print(prefix);
            pw.print("number augmented requests: ");
            pw.println(this.mAugmentedRequestsLogs.size());
        }
        if (this.mAugmentedAutofillableIds != null) {
            pw.print(prefix);
            pw.print("mAugmentedAutofillableIds: ");
            pw.println(this.mAugmentedAutofillableIds);
        }
        RemoteFillService remoteFillService = this.mRemoteFillService;
        if (remoteFillService != null) {
            remoteFillService.dump(prefix, pw);
        }
    }

    private static void dumpRequestLog(PrintWriter pw, LogMaker log) {
        pw.print("CAT=");
        pw.print(log.getCategory());
        pw.print(", TYPE=");
        int type = log.getType();
        if (type == 2) {
            pw.print("CLOSE");
        } else if (type == 10) {
            pw.print("SUCCESS");
        } else if (type == 11) {
            pw.print("FAILURE");
        } else {
            pw.print("UNSUPPORTED");
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
    @GuardedBy({"mLock"})
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
                this.mMetricsLogger.write(this.mRequestLogs.valueAt(i));
            }
        }
        ArrayList<LogMaker> arrayList = this.mAugmentedRequestsLogs;
        int totalAugmentedRequests = arrayList == null ? 0 : arrayList.size();
        if (totalAugmentedRequests > 0) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "destroyLocked(): logging " + totalRequests + " augmented requests");
            }
            for (int i2 = 0; i2 < totalAugmentedRequests; i2++) {
                this.mMetricsLogger.write(this.mAugmentedRequestsLogs.get(i2));
            }
        }
        LogMaker log = newLogMaker(919).addTaggedData(1455, Integer.valueOf(totalRequests));
        if (totalAugmentedRequests > 0) {
            log.addTaggedData(1631, Integer.valueOf(totalAugmentedRequests));
        }
        if (this.mForAugmentedAutofillOnly) {
            log.addTaggedData(1720, 1);
        }
        this.mMetricsLogger.write(log);
        return this.mRemoteFillService;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void forceRemoveSelfLocked() {
        forceRemoveSelfLocked(0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void forceRemoveSelfIfForAugmentedAutofillOnlyLocked() {
        if (Helper.sVerbose) {
            Slog.v(TAG, "forceRemoveSelfIfForAugmentedAutofillOnly(" + this.id + "): " + this.mForAugmentedAutofillOnly);
        }
        if (this.mForAugmentedAutofillOnly) {
            forceRemoveSelfLocked();
        }
    }

    @GuardedBy({"mLock"})
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
                this.mClient.setSessionFinished(clientState, (List) null);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client to finish session", e);
            }
        }
        destroyAugmentedAutofillWindowsLocked();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void destroyAugmentedAutofillWindowsLocked() {
        Runnable runnable = this.mAugmentedAutofillDestroyer;
        if (runnable != null) {
            runnable.run();
            this.mAugmentedAutofillDestroyer = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeSelf() {
        synchronized (this.mLock) {
            removeSelfLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void removeSelfLocked() {
        if (Helper.sVerbose) {
            Slog.v(TAG, "removeSelfLocked(" + this.id + "): " + this.mPendingSaveUi);
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
    @GuardedBy({"mLock"})
    public boolean isSaveUiPendingForTokenLocked(IBinder token) {
        return isSaveUiPendingLocked() && token.equals(this.mPendingSaveUi.getToken());
    }

    @GuardedBy({"mLock"})
    private boolean isSaveUiPendingLocked() {
        PendingUi pendingUi = this.mPendingSaveUi;
        return pendingUi != null && pendingUi.getState() == 2;
    }

    @GuardedBy({"mLock"})
    private int getLastResponseIndexLocked() {
        int lastResponseIdx = -1;
        SparseArray<FillResponse> sparseArray = this.mResponses;
        if (sparseArray != null) {
            int responseCount = sparseArray.size();
            for (int i = 0; i < responseCount; i++) {
                if (this.mResponses.keyAt(i) > -1) {
                    lastResponseIdx = i;
                }
            }
        }
        return lastResponseIdx;
    }

    private LogMaker newLogMaker(int category) {
        return newLogMaker(category, this.mService.getServicePackageName());
    }

    private LogMaker newLogMaker(int category, String servicePackageName) {
        return Helper.newLogMaker(category, this.mComponentName, servicePackageName, this.id, this.mCompatMode);
    }

    private void writeLog(int category) {
        this.mMetricsLogger.write(newLogMaker(category));
    }

    @GuardedBy({"mLock"})
    private void logAuthenticationStatusLocked(int requestId, int status) {
        addTaggedDataToRequestLogLocked(requestId, 1453, Integer.valueOf(status));
    }

    @GuardedBy({"mLock"})
    private void addTaggedDataToRequestLogLocked(int requestId, int tag, Object value) {
        LogMaker requestLog = this.mRequestLogs.get(requestId);
        if (requestLog == null) {
            Slog.w(TAG, "addTaggedDataToRequestLogLocked(tag=" + tag + "): no log for id " + requestId);
            return;
        }
        requestLog.addTaggedData(tag, value);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void wtf(Exception e, String fmt, Object... args) {
        String message = String.format(fmt, args);
        synchronized (this.mLock) {
            this.mWtfHistory.log(message);
        }
        if (e != null) {
            Slog.wtf(TAG, message, e);
        } else {
            Slog.wtf(TAG, message);
        }
    }

    private static String actionAsString(int action) {
        if (action != 1) {
            if (action != 2) {
                if (action != 3) {
                    if (action == 4) {
                        return "VALUE_CHANGED";
                    }
                    return "UNKNOWN_" + action;
                }
                return "VIEW_EXITED";
            }
            return "VIEW_ENTERED";
        }
        return "START_SESSION";
    }
}
