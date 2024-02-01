package com.android.server.autofill;

import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.service.autofill.IFillCallback;
import android.service.autofill.ISaveCallback;
import android.service.autofill.SaveRequest;
import android.util.Slog;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.AbstractSinglePendingRequestRemoteService;
import com.android.server.pm.DumpState;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class RemoteFillService extends AbstractSinglePendingRequestRemoteService<RemoteFillService, IAutoFillService> {
    private static final long TIMEOUT_IDLE_BIND_MILLIS = 5000;
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 5000;
    private final FillServiceCallbacks mCallbacks;

    /* loaded from: classes.dex */
    public interface FillServiceCallbacks extends AbstractRemoteService.VultureCallback<RemoteFillService> {
        void onFillRequestFailure(int i, CharSequence charSequence);

        void onFillRequestSuccess(int i, FillResponse fillResponse, String str, int i2);

        void onFillRequestTimeout(int i);

        void onSaveRequestFailure(CharSequence charSequence, String str);

        void onSaveRequestSuccess(String str, IntentSender intentSender);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RemoteFillService(Context context, ComponentName componentName, int userId, FillServiceCallbacks callbacks, boolean bindInstantServiceAllowed) {
        super(context, "android.service.autofill.AutofillService", componentName, userId, callbacks, context.getMainThreadHandler(), (bindInstantServiceAllowed ? DumpState.DUMP_CHANGES : 0) | DumpState.DUMP_DEXOPT, Helper.sVerbose);
        this.mCallbacks = callbacks;
    }

    protected void handleOnConnectedStateChanged(boolean state) {
        if (this.mService == null) {
            Slog.w(this.mTag, "onConnectedStateChanged(): null service");
            return;
        }
        try {
            this.mService.onConnectedStateChanged(state);
        } catch (Exception e) {
            String str = this.mTag;
            Slog.w(str, "Exception calling onConnectedStateChanged(" + state + "): " + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public IAutoFillService getServiceInterface(IBinder service) {
        return IAutoFillService.Stub.asInterface(service);
    }

    protected long getTimeoutIdleBindMillis() {
        return 5000L;
    }

    protected long getRemoteRequestMillis() {
        return 5000L;
    }

    public CompletableFuture<Integer> cancelCurrentRequest() {
        Supplier supplier = new Supplier() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$_BUUnv78CuBw5KA9LSgPsdJ9MjM
            @Override // java.util.function.Supplier
            public final Object get() {
                return RemoteFillService.this.lambda$cancelCurrentRequest$0$RemoteFillService();
            }
        };
        final Handler handler = this.mHandler;
        Objects.requireNonNull(handler);
        return CompletableFuture.supplyAsync(supplier, new Executor() { // from class: com.android.server.autofill.-$$Lambda$LfzJt661qZfn2w-6SYHFbD3aMy0
            @Override // java.util.concurrent.Executor
            public final void execute(Runnable runnable) {
                handler.post(runnable);
            }
        });
    }

    public /* synthetic */ Integer lambda$cancelCurrentRequest$0$RemoteFillService() {
        if (isDestroyed()) {
            return Integer.MIN_VALUE;
        }
        PendingFillRequest handleCancelPendingRequest = handleCancelPendingRequest();
        return Integer.valueOf(handleCancelPendingRequest instanceof PendingFillRequest ? handleCancelPendingRequest.mRequest.getId() : Integer.MIN_VALUE);
    }

    public void onFillRequest(FillRequest request) {
        scheduleRequest(new PendingFillRequest(request, this));
    }

    public void onSaveRequest(SaveRequest request) {
        scheduleRequest(new PendingSaveRequest(request, this));
    }

    private boolean handleResponseCallbackCommon(AbstractRemoteService.PendingRequest<RemoteFillService, IAutoFillService> pendingRequest) {
        if (isDestroyed()) {
            return false;
        }
        if (this.mPendingRequest == pendingRequest) {
            this.mPendingRequest = null;
            return true;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillRequestSuccess(final PendingFillRequest pendingRequest, final FillResponse response, final int requestFlags) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$nNsNqySgqQYv3OSs9eiVuCXLs9E
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.this.lambda$dispatchOnFillRequestSuccess$1$RemoteFillService(pendingRequest, response, requestFlags);
            }
        });
    }

    public /* synthetic */ void lambda$dispatchOnFillRequestSuccess$1$RemoteFillService(PendingFillRequest pendingRequest, FillResponse response, int requestFlags) {
        if (handleResponseCallbackCommon(pendingRequest)) {
            this.mCallbacks.onFillRequestSuccess(pendingRequest.mRequest.getId(), response, this.mComponentName.getPackageName(), requestFlags);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillRequestFailure(final PendingFillRequest pendingRequest, final CharSequence message) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$uMtdVaR6ZSsnb2B7-3JV-KnL1_w
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.this.lambda$dispatchOnFillRequestFailure$2$RemoteFillService(pendingRequest, message);
            }
        });
    }

    public /* synthetic */ void lambda$dispatchOnFillRequestFailure$2$RemoteFillService(PendingFillRequest pendingRequest, CharSequence message) {
        if (!handleResponseCallbackCommon(pendingRequest)) {
            return;
        }
        this.mCallbacks.onFillRequestFailure(pendingRequest.mRequest.getId(), message);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillRequestTimeout(final PendingFillRequest pendingRequest) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$17ODPUArCJOdtrnekJFErsoLsNA
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.this.lambda$dispatchOnFillRequestTimeout$3$RemoteFillService(pendingRequest);
            }
        });
    }

    public /* synthetic */ void lambda$dispatchOnFillRequestTimeout$3$RemoteFillService(PendingFillRequest pendingRequest) {
        if (!handleResponseCallbackCommon(pendingRequest)) {
            return;
        }
        this.mCallbacks.onFillRequestTimeout(pendingRequest.mRequest.getId());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillTimeout(final ICancellationSignal cancellationSignal) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$cFdxAsb2okq_1ntxSWIoefN2D0Y
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.this.lambda$dispatchOnFillTimeout$4$RemoteFillService(cancellationSignal);
            }
        });
    }

    public /* synthetic */ void lambda$dispatchOnFillTimeout$4$RemoteFillService(ICancellationSignal cancellationSignal) {
        try {
            cancellationSignal.cancel();
        } catch (RemoteException e) {
            String str = this.mTag;
            Slog.w(str, "Error calling cancellation signal: " + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnSaveRequestSuccess(final PendingSaveRequest pendingRequest, final IntentSender intentSender) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$nNE3l9bMJ5YfGBwv5fnJX_ib1VQ
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.this.lambda$dispatchOnSaveRequestSuccess$5$RemoteFillService(pendingRequest, intentSender);
            }
        });
    }

    public /* synthetic */ void lambda$dispatchOnSaveRequestSuccess$5$RemoteFillService(PendingSaveRequest pendingRequest, IntentSender intentSender) {
        if (handleResponseCallbackCommon(pendingRequest)) {
            this.mCallbacks.onSaveRequestSuccess(this.mComponentName.getPackageName(), intentSender);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnSaveRequestFailure(final PendingSaveRequest pendingRequest, final CharSequence message) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$1eWrRA9nIGIKrCDRbK04sVnr0uo
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.this.lambda$dispatchOnSaveRequestFailure$6$RemoteFillService(pendingRequest, message);
            }
        });
    }

    public /* synthetic */ void lambda$dispatchOnSaveRequestFailure$6$RemoteFillService(PendingSaveRequest pendingRequest, CharSequence message) {
        if (handleResponseCallbackCommon(pendingRequest)) {
            this.mCallbacks.onSaveRequestFailure(message, this.mComponentName.getPackageName());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class PendingFillRequest extends AbstractRemoteService.PendingRequest<RemoteFillService, IAutoFillService> {
        private final IFillCallback mCallback;
        private ICancellationSignal mCancellation;
        private final FillRequest mRequest;

        public PendingFillRequest(final FillRequest request, RemoteFillService service) {
            super(service);
            this.mRequest = request;
            this.mCallback = new IFillCallback.Stub() { // from class: com.android.server.autofill.RemoteFillService.PendingFillRequest.1
                public void onCancellable(ICancellationSignal cancellation) {
                    boolean cancelled;
                    synchronized (PendingFillRequest.this.mLock) {
                        synchronized (PendingFillRequest.this.mLock) {
                            PendingFillRequest.this.mCancellation = cancellation;
                            cancelled = PendingFillRequest.this.isCancelledLocked();
                        }
                        if (cancelled) {
                            try {
                                cancellation.cancel();
                            } catch (RemoteException e) {
                                Slog.e(PendingFillRequest.this.mTag, "Error requesting a cancellation", e);
                            }
                        }
                    }
                }

                public void onSuccess(FillResponse response) {
                    RemoteFillService remoteService;
                    if (PendingFillRequest.this.finish() && (remoteService = PendingFillRequest.this.getService()) != null) {
                        remoteService.dispatchOnFillRequestSuccess(PendingFillRequest.this, response, request.getFlags());
                    }
                }

                public void onFailure(int requestId, CharSequence message) {
                    RemoteFillService remoteService;
                    if (PendingFillRequest.this.finish() && (remoteService = PendingFillRequest.this.getService()) != null) {
                        remoteService.dispatchOnFillRequestFailure(PendingFillRequest.this, message);
                    }
                }
            };
        }

        /* JADX INFO: Access modifiers changed from: protected */
        public void onTimeout(RemoteFillService remoteService) {
            ICancellationSignal cancellation;
            synchronized (this.mLock) {
                cancellation = this.mCancellation;
            }
            if (cancellation != null) {
                remoteService.dispatchOnFillTimeout(cancellation);
            }
            remoteService.dispatchOnFillRequestTimeout(this);
        }

        public void run() {
            synchronized (this.mLock) {
                if (isCancelledLocked()) {
                    if (Helper.sDebug) {
                        String str = this.mTag;
                        Slog.d(str, "run() called after canceled: " + this.mRequest);
                    }
                    return;
                }
                RemoteFillService remoteService = getService();
                if (remoteService != null) {
                    if (Helper.sVerbose) {
                        String str2 = this.mTag;
                        Slog.v(str2, "calling onFillRequest() for id=" + this.mRequest.getId());
                    }
                    try {
                        remoteService.mService.onFillRequest(this.mRequest, this.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(this.mTag, "Error calling on fill request", e);
                        remoteService.dispatchOnFillRequestFailure(this, null);
                    }
                }
            }
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
                        Slog.e(this.mTag, "Error cancelling a fill request", e);
                        return true;
                    }
                }
                return true;
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class PendingSaveRequest extends AbstractRemoteService.PendingRequest<RemoteFillService, IAutoFillService> {
        private final ISaveCallback mCallback;
        private final SaveRequest mRequest;

        public PendingSaveRequest(SaveRequest request, RemoteFillService service) {
            super(service);
            this.mRequest = request;
            this.mCallback = new ISaveCallback.Stub() { // from class: com.android.server.autofill.RemoteFillService.PendingSaveRequest.1
                public void onSuccess(IntentSender intentSender) {
                    RemoteFillService remoteService;
                    if (PendingSaveRequest.this.finish() && (remoteService = PendingSaveRequest.this.getService()) != null) {
                        remoteService.dispatchOnSaveRequestSuccess(PendingSaveRequest.this, intentSender);
                    }
                }

                public void onFailure(CharSequence message) {
                    RemoteFillService remoteService;
                    if (PendingSaveRequest.this.finish() && (remoteService = PendingSaveRequest.this.getService()) != null) {
                        remoteService.dispatchOnSaveRequestFailure(PendingSaveRequest.this, message);
                    }
                }
            };
        }

        /* JADX INFO: Access modifiers changed from: protected */
        public void onTimeout(RemoteFillService remoteService) {
            remoteService.dispatchOnSaveRequestFailure(this, null);
        }

        public void run() {
            RemoteFillService remoteService = getService();
            if (remoteService != null) {
                if (Helper.sVerbose) {
                    Slog.v(this.mTag, "calling onSaveRequest()");
                }
                try {
                    remoteService.mService.onSaveRequest(this.mRequest, this.mCallback);
                } catch (RemoteException e) {
                    Slog.e(this.mTag, "Error calling on save request", e);
                    remoteService.dispatchOnSaveRequestFailure(this, null);
                }
            }
        }

        public boolean isFinal() {
            return true;
        }
    }
}
