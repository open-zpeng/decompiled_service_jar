package com.android.server.autofill;

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.autofill.augmented.IAugmentedAutofillService;
import android.service.autofill.augmented.IFillCallback;
import android.util.Pair;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.AbstractSinglePendingRequestRemoteService;
import com.android.internal.os.IResultReceiver;
import com.android.server.pm.DumpState;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class RemoteAugmentedAutofillService extends AbstractSinglePendingRequestRemoteService<RemoteAugmentedAutofillService, IAugmentedAutofillService> {
    private static final String TAG = RemoteAugmentedAutofillService.class.getSimpleName();
    private final int mIdleUnbindTimeoutMs;
    private final int mRequestTimeoutMs;

    /* loaded from: classes.dex */
    public interface RemoteAugmentedAutofillServiceCallbacks extends AbstractRemoteService.VultureCallback<RemoteAugmentedAutofillService> {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteAugmentedAutofillService(Context context, ComponentName serviceName, int userId, RemoteAugmentedAutofillServiceCallbacks callbacks, boolean bindInstantServiceAllowed, boolean verbose, int idleUnbindTimeoutMs, int requestTimeoutMs) {
        super(context, "android.service.autofill.augmented.AugmentedAutofillService", serviceName, userId, callbacks, context.getMainThreadHandler(), bindInstantServiceAllowed ? DumpState.DUMP_CHANGES : 0, verbose);
        this.mIdleUnbindTimeoutMs = idleUnbindTimeoutMs;
        this.mRequestTimeoutMs = requestTimeoutMs;
        scheduleBind();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Pair<ServiceInfo, ComponentName> getComponentName(String componentName, int userId, boolean isTemporary) {
        int flags = isTemporary ? 128 : 128 | DumpState.DUMP_DEXOPT;
        try {
            ComponentName serviceComponent = ComponentName.unflattenFromString(componentName);
            ServiceInfo serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, flags, userId);
            if (serviceInfo == null) {
                String str = TAG;
                Slog.e(str, "Bad service name for flags " + flags + ": " + componentName);
                return null;
            }
            return new Pair<>(serviceInfo, serviceComponent);
        } catch (Exception e) {
            String str2 = TAG;
            Slog.e(str2, "Error getting service info for '" + componentName + "': " + e);
            return null;
        }
    }

    protected void handleOnConnectedStateChanged(boolean state) {
        if (state && getTimeoutIdleBindMillis() != 0) {
            scheduleUnbind();
        }
        try {
            if (state) {
                this.mService.onConnected(Helper.sDebug, Helper.sVerbose);
            } else {
                this.mService.onDisconnected();
            }
        } catch (Exception e) {
            String str = this.mTag;
            Slog.w(str, "Exception calling onConnectedStateChanged(" + state + "): " + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public IAugmentedAutofillService getServiceInterface(IBinder service) {
        return IAugmentedAutofillService.Stub.asInterface(service);
    }

    protected long getTimeoutIdleBindMillis() {
        return this.mIdleUnbindTimeoutMs;
    }

    protected long getRemoteRequestMillis() {
        return this.mRequestTimeoutMs;
    }

    public void onRequestAutofillLocked(int sessionId, IAutoFillManagerClient client, int taskId, ComponentName activityComponent, AutofillId focusedId, AutofillValue focusedValue) {
        scheduleRequest(new PendingAutofillRequest(this, sessionId, client, taskId, activityComponent, focusedId, focusedValue));
    }

    public String toString() {
        return "RemoteAugmentedAutofillService[" + ComponentName.flattenToShortString(getComponentName()) + "]";
    }

    public void onDestroyAutofillWindowsRequest() {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.autofill.-$$Lambda$RemoteAugmentedAutofillService$e7zSmzv77rBdYV5oCl-Y8EJj9dY
            public final void run(IInterface iInterface) {
                ((IAugmentedAutofillService) iInterface).onDestroyAllFillWindowsRequest();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillTimeout(final ICancellationSignal cancellation) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteAugmentedAutofillService$3YjensAPYJHBJpP8njsOCNRhSYw
            @Override // java.lang.Runnable
            public final void run() {
                RemoteAugmentedAutofillService.this.lambda$dispatchOnFillTimeout$1$RemoteAugmentedAutofillService(cancellation);
            }
        });
    }

    public /* synthetic */ void lambda$dispatchOnFillTimeout$1$RemoteAugmentedAutofillService(ICancellationSignal cancellation) {
        try {
            cancellation.cancel();
        } catch (RemoteException e) {
            String str = this.mTag;
            Slog.w(str, "Error calling cancellation signal: " + e);
        }
    }

    /* loaded from: classes.dex */
    private static abstract class MyPendingRequest extends AbstractRemoteService.PendingRequest<RemoteAugmentedAutofillService, IAugmentedAutofillService> {
        protected final int mSessionId;

        private MyPendingRequest(RemoteAugmentedAutofillService service, int sessionId) {
            super(service);
            this.mSessionId = sessionId;
        }
    }

    /* loaded from: classes.dex */
    private static final class PendingAutofillRequest extends MyPendingRequest {
        private final ComponentName mActivityComponent;
        private final IFillCallback mCallback;
        private ICancellationSignal mCancellation;
        private final IAutoFillManagerClient mClient;
        private final AutofillId mFocusedId;
        private final AutofillValue mFocusedValue;
        private final long mRequestTime;
        private final int mSessionId;
        private final int mTaskId;

        protected PendingAutofillRequest(RemoteAugmentedAutofillService service, int sessionId, IAutoFillManagerClient client, int taskId, ComponentName activityComponent, AutofillId focusedId, AutofillValue focusedValue) {
            super(sessionId);
            this.mRequestTime = SystemClock.elapsedRealtime();
            this.mClient = client;
            this.mSessionId = sessionId;
            this.mTaskId = taskId;
            this.mActivityComponent = activityComponent;
            this.mFocusedId = focusedId;
            this.mFocusedValue = focusedValue;
            this.mCallback = new IFillCallback.Stub() { // from class: com.android.server.autofill.RemoteAugmentedAutofillService.PendingAutofillRequest.1
                public void onSuccess() {
                    if (PendingAutofillRequest.this.finish()) {
                    }
                }

                public void onCancellable(ICancellationSignal cancellation) {
                    boolean cancelled;
                    synchronized (PendingAutofillRequest.this.mLock) {
                        synchronized (PendingAutofillRequest.this.mLock) {
                            PendingAutofillRequest.this.mCancellation = cancellation;
                            cancelled = PendingAutofillRequest.this.isCancelledLocked();
                        }
                        if (cancelled) {
                            try {
                                cancellation.cancel();
                            } catch (RemoteException e) {
                                Slog.e(PendingAutofillRequest.this.mTag, "Error requesting a cancellation", e);
                            }
                        }
                    }
                }

                public boolean isCompleted() {
                    return PendingAutofillRequest.this.isRequestCompleted();
                }

                public void cancel() {
                    PendingAutofillRequest.this.cancel();
                }
            };
        }

        public void run() {
            synchronized (this.mLock) {
                if (isCancelledLocked()) {
                    if (Helper.sDebug) {
                        Slog.d(this.mTag, "run() called after canceled");
                    }
                    return;
                }
                final RemoteAugmentedAutofillService remoteService = getService();
                if (remoteService == null) {
                    return;
                }
                try {
                    this.mClient.getAugmentedAutofillClient(new IResultReceiver.Stub() { // from class: com.android.server.autofill.RemoteAugmentedAutofillService.PendingAutofillRequest.2
                        public void send(int resultCode, Bundle resultData) throws RemoteException {
                            IBinder realClient = resultData.getBinder("android.view.autofill.extra.AUGMENTED_AUTOFILL_CLIENT");
                            remoteService.mService.onFillRequest(PendingAutofillRequest.this.mSessionId, realClient, PendingAutofillRequest.this.mTaskId, PendingAutofillRequest.this.mActivityComponent, PendingAutofillRequest.this.mFocusedId, PendingAutofillRequest.this.mFocusedValue, PendingAutofillRequest.this.mRequestTime, PendingAutofillRequest.this.mCallback);
                        }
                    });
                } catch (RemoteException e) {
                    String str = RemoteAugmentedAutofillService.TAG;
                    Slog.e(str, "exception handling getAugmentedAutofillClient() for " + this.mSessionId + ": " + e);
                    finish();
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: protected */
        public void onTimeout(RemoteAugmentedAutofillService remoteService) {
            ICancellationSignal cancellation;
            String str = RemoteAugmentedAutofillService.TAG;
            Slog.w(str, "PendingAutofillRequest timed out (" + remoteService.mRequestTimeoutMs + "ms) for " + remoteService);
            synchronized (this.mLock) {
                cancellation = this.mCancellation;
            }
            if (cancellation != null) {
                remoteService.dispatchOnFillTimeout(cancellation);
            }
            finish();
            android.service.autofill.augmented.Helper.logResponse(15, remoteService.getComponentName().getPackageName(), this.mActivityComponent, this.mSessionId, remoteService.mRequestTimeoutMs);
        }

        public boolean cancel() {
            ICancellationSignal cancellation;
            if (super.cancel()) {
                synchronized (this.mLock) {
                    cancellation = this.mCancellation;
                }
                if (cancellation != null) {
                    try {
                        cancellation.cancel();
                        return true;
                    } catch (RemoteException e) {
                        Slog.e(this.mTag, "Error cancelling an augmented fill request", e);
                        return true;
                    }
                }
                return true;
            }
            return false;
        }
    }
}
