package com.android.server.autofill;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.service.autofill.IFillCallback;
import android.service.autofill.ISaveCallback;
import android.service.autofill.SaveRequest;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.autofill.RemoteFillService;
import com.android.server.pm.DumpState;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class RemoteFillService implements IBinder.DeathRecipient {
    private static final String LOG_TAG = "RemoteFillService";
    private static final int MSG_UNBIND = 3;
    private static final long TIMEOUT_IDLE_BIND_MILLIS = 5000;
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 5000;
    private IAutoFillService mAutoFillService;
    private final boolean mBindInstantServiceAllowed;
    private boolean mBinding;
    private final FillServiceCallbacks mCallbacks;
    private boolean mCompleted;
    private final ComponentName mComponentName;
    private final Context mContext;
    private boolean mDestroyed;
    private final Intent mIntent;
    private PendingRequest mPendingRequest;
    private boolean mServiceDied;
    private final int mUserId;
    private final ServiceConnection mServiceConnection = new RemoteServiceConnection();
    private final Handler mHandler = new Handler(FgThread.getHandler().getLooper());

    /* loaded from: classes.dex */
    public interface FillServiceCallbacks {
        void onFillRequestFailure(int i, CharSequence charSequence, String str);

        void onFillRequestSuccess(int i, FillResponse fillResponse, String str, int i2);

        void onFillRequestTimeout(int i, String str);

        void onSaveRequestFailure(CharSequence charSequence, String str);

        void onSaveRequestSuccess(String str, IntentSender intentSender);

        void onServiceDied(RemoteFillService remoteFillService);
    }

    public RemoteFillService(Context context, ComponentName componentName, int userId, FillServiceCallbacks callbacks, boolean bindInstantServiceAllowed) {
        this.mContext = context;
        this.mCallbacks = callbacks;
        this.mComponentName = componentName;
        this.mIntent = new Intent("android.service.autofill.AutofillService").setComponent(this.mComponentName);
        this.mUserId = userId;
        this.mBindInstantServiceAllowed = bindInstantServiceAllowed;
    }

    public void destroy() {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$KN9CcjjmJTg_PJcamzzLgVvQt9M
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((RemoteFillService) obj).handleDestroy();
            }
        }, this));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDestroy() {
        if (checkIfDestroyed()) {
            return;
        }
        if (this.mPendingRequest != null) {
            this.mPendingRequest.cancel();
            this.mPendingRequest = null;
        }
        ensureUnbound();
        this.mDestroyed = true;
    }

    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$1sGSxm1GNkRnOTqlIJFPKrlV6Bk
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((RemoteFillService) obj).handleBinderDied();
            }
        }, this));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleBinderDied() {
        if (checkIfDestroyed()) {
            return;
        }
        if (this.mAutoFillService != null) {
            this.mAutoFillService.asBinder().unlinkToDeath(this, 0);
        }
        this.mAutoFillService = null;
        this.mServiceDied = true;
        this.mCallbacks.onServiceDied(this);
    }

    public int cancelCurrentRequest() {
        if (this.mDestroyed) {
            return Integer.MIN_VALUE;
        }
        int requestId = Integer.MIN_VALUE;
        if (this.mPendingRequest != null) {
            if (this.mPendingRequest instanceof PendingFillRequest) {
                requestId = ((PendingFillRequest) this.mPendingRequest).mRequest.getId();
            }
            this.mPendingRequest.cancel();
            this.mPendingRequest = null;
        }
        return requestId;
    }

    public void onFillRequest(FillRequest request) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingFillRequest(request, this));
    }

    public void onSaveRequest(SaveRequest request) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingSaveRequest(request, this));
    }

    private void scheduleRequest(PendingRequest pendingRequest) {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$h6FPsdmILphrDZs953cJIyumyqg
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((RemoteFillService) obj).handlePendingRequest((RemoteFillService.PendingRequest) obj2);
            }
        }, this, pendingRequest));
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.append((CharSequence) prefix).append("service:").println();
        pw.append((CharSequence) prefix).append("  ").append("userId=").append((CharSequence) String.valueOf(this.mUserId)).println();
        pw.append((CharSequence) prefix).append("  ").append("componentName=").append((CharSequence) this.mComponentName.flattenToString()).println();
        pw.append((CharSequence) prefix).append("  ").append("destroyed=").append((CharSequence) String.valueOf(this.mDestroyed)).println();
        pw.append((CharSequence) prefix).append("  ").append("bound=").append((CharSequence) String.valueOf(isBound())).println();
        pw.append((CharSequence) prefix).append("  ").append("hasPendingRequest=").append((CharSequence) String.valueOf(this.mPendingRequest != null)).println();
        pw.append((CharSequence) prefix).append("mBindInstantServiceAllowed=").println(this.mBindInstantServiceAllowed);
        pw.println();
    }

    private void cancelScheduledUnbind() {
        this.mHandler.removeMessages(3);
    }

    private void scheduleUnbind() {
        cancelScheduledUnbind();
        this.mHandler.sendMessageDelayed(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$YjPsINV7QuCehWwsB0GTTg1hvr4
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((RemoteFillService) obj).handleUnbind();
            }
        }, this).setWhat(3), 5000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUnbind() {
        if (checkIfDestroyed()) {
            return;
        }
        ensureUnbound();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePendingRequest(PendingRequest pendingRequest) {
        if (checkIfDestroyed() || this.mCompleted) {
            return;
        }
        if (!isBound()) {
            if (this.mPendingRequest != null) {
                this.mPendingRequest.cancel();
            }
            this.mPendingRequest = pendingRequest;
            ensureBound();
            return;
        }
        if (Helper.sVerbose) {
            Slog.v(LOG_TAG, "[user: " + this.mUserId + "] handlePendingRequest()");
        }
        pendingRequest.run();
        if (pendingRequest.isFinal()) {
            this.mCompleted = true;
        }
    }

    private boolean isBound() {
        return this.mAutoFillService != null;
    }

    private void ensureBound() {
        if (isBound() || this.mBinding) {
            return;
        }
        if (Helper.sVerbose) {
            Slog.v(LOG_TAG, "[user: " + this.mUserId + "] ensureBound()");
        }
        this.mBinding = true;
        int flags = this.mBindInstantServiceAllowed ? 67108865 | DumpState.DUMP_CHANGES : 67108865;
        boolean willBind = this.mContext.bindServiceAsUser(this.mIntent, this.mServiceConnection, flags, new UserHandle(this.mUserId));
        if (!willBind) {
            Slog.w(LOG_TAG, "[user: " + this.mUserId + "] could not bind to " + this.mIntent + " using flags " + flags);
            this.mBinding = false;
            if (!this.mServiceDied) {
                handleBinderDied();
            }
        }
    }

    private void ensureUnbound() {
        if (!isBound() && !this.mBinding) {
            return;
        }
        if (Helper.sVerbose) {
            Slog.v(LOG_TAG, "[user: " + this.mUserId + "] ensureUnbound()");
        }
        this.mBinding = false;
        if (isBound()) {
            try {
                this.mAutoFillService.onConnectedStateChanged(false);
            } catch (Exception e) {
                Slog.w(LOG_TAG, "Exception calling onDisconnected(): " + e);
            }
            if (this.mAutoFillService != null) {
                this.mAutoFillService.asBinder().unlinkToDeath(this, 0);
                this.mAutoFillService = null;
            }
        }
        this.mContext.unbindService(this.mServiceConnection);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillRequestSuccess(final PendingFillRequest pendingRequest, final FillResponse response, final int requestFlags) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$_5v43Gwb-Yar1uuVIqDgfleCP_4
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.lambda$dispatchOnFillRequestSuccess$0(RemoteFillService.this, pendingRequest, response, requestFlags);
            }
        });
    }

    public static /* synthetic */ void lambda$dispatchOnFillRequestSuccess$0(RemoteFillService remoteFillService, PendingFillRequest pendingRequest, FillResponse response, int requestFlags) {
        if (remoteFillService.handleResponseCallbackCommon(pendingRequest)) {
            remoteFillService.mCallbacks.onFillRequestSuccess(pendingRequest.mRequest.getId(), response, remoteFillService.mComponentName.getPackageName(), requestFlags);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillRequestFailure(final PendingFillRequest pendingRequest, final CharSequence message) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$17MhbU6HKTSEi1dUKhwTRwYg2xA
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.lambda$dispatchOnFillRequestFailure$1(RemoteFillService.this, pendingRequest, message);
            }
        });
    }

    public static /* synthetic */ void lambda$dispatchOnFillRequestFailure$1(RemoteFillService remoteFillService, PendingFillRequest pendingRequest, CharSequence message) {
        if (remoteFillService.handleResponseCallbackCommon(pendingRequest)) {
            remoteFillService.mCallbacks.onFillRequestFailure(pendingRequest.mRequest.getId(), message, remoteFillService.mComponentName.getPackageName());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillRequestTimeout(final PendingFillRequest pendingRequest) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$PKEfnjx72TG33VenAsL_32TGLPg
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.lambda$dispatchOnFillRequestTimeout$2(RemoteFillService.this, pendingRequest);
            }
        });
    }

    public static /* synthetic */ void lambda$dispatchOnFillRequestTimeout$2(RemoteFillService remoteFillService, PendingFillRequest pendingRequest) {
        if (remoteFillService.handleResponseCallbackCommon(pendingRequest)) {
            remoteFillService.mCallbacks.onFillRequestTimeout(pendingRequest.mRequest.getId(), remoteFillService.mComponentName.getPackageName());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnFillTimeout(final ICancellationSignal cancellationSignal) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$lfx4anCMpwM99MhvsITDjU9sFRA
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.lambda$dispatchOnFillTimeout$3(cancellationSignal);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dispatchOnFillTimeout$3(ICancellationSignal cancellationSignal) {
        try {
            cancellationSignal.cancel();
        } catch (RemoteException e) {
            Slog.w(LOG_TAG, "Error calling cancellation signal: " + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnSaveRequestSuccess(final PendingRequest pendingRequest, final IntentSender intentSender) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$XMU-2wAMieOoEHWM96VKmbAYfUo
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.lambda$dispatchOnSaveRequestSuccess$4(RemoteFillService.this, pendingRequest, intentSender);
            }
        });
    }

    public static /* synthetic */ void lambda$dispatchOnSaveRequestSuccess$4(RemoteFillService remoteFillService, PendingRequest pendingRequest, IntentSender intentSender) {
        if (remoteFillService.handleResponseCallbackCommon(pendingRequest)) {
            remoteFillService.mCallbacks.onSaveRequestSuccess(remoteFillService.mComponentName.getPackageName(), intentSender);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void dispatchOnSaveRequestFailure(final PendingRequest pendingRequest, final CharSequence message) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$-MTWVawYUlWYzdF5tucVgNj4nNY
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.lambda$dispatchOnSaveRequestFailure$5(RemoteFillService.this, pendingRequest, message);
            }
        });
    }

    public static /* synthetic */ void lambda$dispatchOnSaveRequestFailure$5(RemoteFillService remoteFillService, PendingRequest pendingRequest, CharSequence message) {
        if (remoteFillService.handleResponseCallbackCommon(pendingRequest)) {
            remoteFillService.mCallbacks.onSaveRequestFailure(message, remoteFillService.mComponentName.getPackageName());
        }
    }

    private boolean handleResponseCallbackCommon(PendingRequest pendingRequest) {
        if (this.mDestroyed) {
            return false;
        }
        if (this.mPendingRequest == pendingRequest) {
            this.mPendingRequest = null;
        }
        if (this.mPendingRequest == null) {
            scheduleUnbind();
            return true;
        }
        return true;
    }

    /* loaded from: classes.dex */
    private class RemoteServiceConnection implements ServiceConnection {
        private RemoteServiceConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!RemoteFillService.this.mDestroyed && RemoteFillService.this.mBinding) {
                RemoteFillService.this.mBinding = false;
                RemoteFillService.this.mAutoFillService = IAutoFillService.Stub.asInterface(service);
                try {
                    service.linkToDeath(RemoteFillService.this, 0);
                    try {
                        RemoteFillService.this.mAutoFillService.onConnectedStateChanged(true);
                    } catch (RemoteException e) {
                        Slog.w(RemoteFillService.LOG_TAG, "Exception calling onConnected(): " + e);
                    }
                    if (RemoteFillService.this.mPendingRequest != null) {
                        PendingRequest pendingRequest = RemoteFillService.this.mPendingRequest;
                        RemoteFillService.this.mPendingRequest = null;
                        RemoteFillService.this.handlePendingRequest(pendingRequest);
                    }
                    RemoteFillService.this.mServiceDied = false;
                    return;
                } catch (RemoteException e2) {
                    RemoteFillService.this.handleBinderDied();
                    return;
                }
            }
            Slog.wtf(RemoteFillService.LOG_TAG, "onServiceConnected was dispatched after unbindService.");
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            RemoteFillService.this.mBinding = true;
            RemoteFillService.this.mAutoFillService = null;
        }
    }

    private boolean checkIfDestroyed() {
        if (this.mDestroyed && Helper.sVerbose) {
            Slog.v(LOG_TAG, "Not handling operation as service for " + this.mComponentName + " is already destroyed");
        }
        return this.mDestroyed;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static abstract class PendingRequest implements Runnable {
        @GuardedBy("mLock")
        private boolean mCancelled;
        @GuardedBy("mLock")
        private boolean mCompleted;
        private final Handler mServiceHandler;
        private final WeakReference<RemoteFillService> mWeakService;
        protected final Object mLock = new Object();
        private final Runnable mTimeoutTrigger = new Runnable() { // from class: com.android.server.autofill.-$$Lambda$RemoteFillService$PendingRequest$Wzl5nwSdboq2CuUeWvFraQLBZk8
            @Override // java.lang.Runnable
            public final void run() {
                RemoteFillService.PendingRequest.lambda$new$0(RemoteFillService.PendingRequest.this);
            }
        };

        abstract void onTimeout(RemoteFillService remoteFillService);

        PendingRequest(RemoteFillService service) {
            this.mWeakService = new WeakReference<>(service);
            this.mServiceHandler = service.mHandler;
            this.mServiceHandler.postAtTime(this.mTimeoutTrigger, SystemClock.uptimeMillis() + 5000);
        }

        public static /* synthetic */ void lambda$new$0(PendingRequest pendingRequest) {
            synchronized (pendingRequest.mLock) {
                if (pendingRequest.mCancelled) {
                    return;
                }
                pendingRequest.mCompleted = true;
                Slog.w(RemoteFillService.LOG_TAG, pendingRequest.getClass().getSimpleName() + " timed out");
                RemoteFillService remoteService = pendingRequest.mWeakService.get();
                if (remoteService != null) {
                    Slog.w(RemoteFillService.LOG_TAG, pendingRequest.getClass().getSimpleName() + " timed out after 5000 ms");
                    pendingRequest.onTimeout(remoteService);
                }
            }
        }

        protected RemoteFillService getService() {
            return this.mWeakService.get();
        }

        protected final boolean finish() {
            synchronized (this.mLock) {
                if (!this.mCompleted && !this.mCancelled) {
                    this.mCompleted = true;
                    this.mServiceHandler.removeCallbacks(this.mTimeoutTrigger);
                    return true;
                }
                return false;
            }
        }

        @GuardedBy("mLock")
        protected boolean isCancelledLocked() {
            return this.mCancelled;
        }

        boolean cancel() {
            synchronized (this.mLock) {
                if (!this.mCancelled && !this.mCompleted) {
                    this.mCancelled = true;
                    this.mServiceHandler.removeCallbacks(this.mTimeoutTrigger);
                    return true;
                }
                return false;
            }
        }

        boolean isFinal() {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class PendingFillRequest extends PendingRequest {
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
                                Slog.e(RemoteFillService.LOG_TAG, "Error requesting a cancellation", e);
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

        @Override // com.android.server.autofill.RemoteFillService.PendingRequest
        void onTimeout(RemoteFillService remoteService) {
            ICancellationSignal cancellation;
            synchronized (this.mLock) {
                cancellation = this.mCancellation;
            }
            if (cancellation != null) {
                remoteService.dispatchOnFillTimeout(cancellation);
            }
            remoteService.dispatchOnFillRequestTimeout(this);
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (this.mLock) {
                if (isCancelledLocked()) {
                    if (Helper.sDebug) {
                        Slog.d(RemoteFillService.LOG_TAG, "run() called after canceled: " + this.mRequest);
                    }
                    return;
                }
                RemoteFillService remoteService = getService();
                if (remoteService != null) {
                    try {
                        remoteService.mAutoFillService.onFillRequest(this.mRequest, this.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(RemoteFillService.LOG_TAG, "Error calling on fill request", e);
                        remoteService.dispatchOnFillRequestFailure(this, null);
                    }
                }
            }
        }

        @Override // com.android.server.autofill.RemoteFillService.PendingRequest
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
                        Slog.e(RemoteFillService.LOG_TAG, "Error cancelling a fill request", e);
                        return true;
                    }
                }
                return true;
            }
            return false;
        }
    }

    /* loaded from: classes.dex */
    private static final class PendingSaveRequest extends PendingRequest {
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

        @Override // com.android.server.autofill.RemoteFillService.PendingRequest
        void onTimeout(RemoteFillService remoteService) {
            remoteService.dispatchOnSaveRequestFailure(this, null);
        }

        @Override // java.lang.Runnable
        public void run() {
            RemoteFillService remoteService = getService();
            if (remoteService != null) {
                try {
                    remoteService.mAutoFillService.onSaveRequest(this.mRequest, this.mCallback);
                } catch (RemoteException e) {
                    Slog.e(RemoteFillService.LOG_TAG, "Error calling on save request", e);
                    remoteService.dispatchOnSaveRequestFailure(this, null);
                }
            }
        }

        @Override // com.android.server.autofill.RemoteFillService.PendingRequest
        public boolean isFinal() {
            return true;
        }
    }
}
