package com.android.server.textclassifier;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.textclassifier.ITextClassificationCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.ITextLinksCallback;
import android.service.textclassifier.ITextSelectionCallback;
import android.service.textclassifier.TextClassifierService;
import android.util.Slog;
import android.util.SparseArray;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationSessionId;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;
/* loaded from: classes.dex */
public final class TextClassificationManagerService extends ITextClassifierService.Stub {
    private static final String LOG_TAG = "TextClassificationManagerService";
    private final Context mContext;
    private final Object mLock;
    @GuardedBy("mLock")
    final SparseArray<UserState> mUserStates;

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        private final TextClassificationManagerService mManagerService;

        public Lifecycle(Context context) {
            super(context);
            this.mManagerService = new TextClassificationManagerService(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            try {
                publishBinderService("textclassification", this.mManagerService);
            } catch (Throwable t) {
                Slog.e(TextClassificationManagerService.LOG_TAG, "Could not start the TextClassificationManagerService.", t);
            }
        }

        @Override // com.android.server.SystemService
        public void onStartUser(int userId) {
            processAnyPendingWork(userId);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userId) {
            processAnyPendingWork(userId);
        }

        private void processAnyPendingWork(int userId) {
            synchronized (this.mManagerService.mLock) {
                this.mManagerService.getUserStateLocked(userId).bindIfHasPendingRequestsLocked();
            }
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userId) {
            synchronized (this.mManagerService.mLock) {
                UserState userState = this.mManagerService.peekUserStateLocked(userId);
                if (userState != null) {
                    userState.mConnection.cleanupService();
                    this.mManagerService.mUserStates.remove(userId);
                }
            }
        }
    }

    private TextClassificationManagerService(Context context) {
        this.mUserStates = new SparseArray<>();
        this.mContext = (Context) Preconditions.checkNotNull(context);
        this.mLock = new Object();
    }

    public void onSuggestSelection(final TextClassificationSessionId sessionId, final TextSelection.Request request, final ITextSelectionCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        synchronized (this.mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onSuggestSelection(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$Oay4QGGKO1MM7dDcB0KN_1JmqZA
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.onSuggestSelection(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$m88mc8F7odBzfaVb5UMVTJhRQps
                    public final void runOrThrow() {
                        callback.onFailure();
                    }
                }, callback.asBinder(), this, userState));
            }
        }
    }

    public void onClassifyText(final TextClassificationSessionId sessionId, final TextClassification.Request request, final ITextClassificationCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        synchronized (this.mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onClassifyText(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$0ahBOnx4jsgbPYQhVmIdEMzPn5Q
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.onClassifyText(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$6tTWS9-rW6CtxVP0xKRcg3Q5kmI
                    public final void runOrThrow() {
                        callback.onFailure();
                    }
                }, callback.asBinder(), this, userState));
            }
        }
    }

    public void onGenerateLinks(final TextClassificationSessionId sessionId, final TextLinks.Request request, final ITextLinksCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        synchronized (this.mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onGenerateLinks(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$-O5SqJ3O93lhUbxb9PI9hMy-SaM
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.onGenerateLinks(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$WxMu2h-uKYpQBik6LDmBRWb9Y00
                    public final void runOrThrow() {
                        callback.onFailure();
                    }
                }, callback.asBinder(), this, userState));
            }
        }
    }

    public void onSelectionEvent(final TextClassificationSessionId sessionId, final SelectionEvent event) throws RemoteException {
        Preconditions.checkNotNull(event);
        validateInput(event.getPackageName(), this.mContext);
        synchronized (this.mLock) {
            UserState userState = getCallingUserStateLocked();
            if (userState.isBoundLocked()) {
                userState.mService.onSelectionEvent(sessionId, event);
            } else {
                userState.mPendingRequests.add(new PendingRequest(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$Xo8FJ3LmQoamgJ2foxZOcS-n70c
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.onSelectionEvent(sessionId, event);
                    }
                }, null, null, this, userState));
            }
        }
    }

    public void onCreateTextClassificationSession(final TextClassificationContext classificationContext, final TextClassificationSessionId sessionId) throws RemoteException {
        Preconditions.checkNotNull(sessionId);
        Preconditions.checkNotNull(classificationContext);
        validateInput(classificationContext.getPackageName(), this.mContext);
        synchronized (this.mLock) {
            UserState userState = getCallingUserStateLocked();
            if (userState.isBoundLocked()) {
                userState.mService.onCreateTextClassificationSession(classificationContext, sessionId);
            } else {
                userState.mPendingRequests.add(new PendingRequest(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$BPyCtyAA7AehDOdMNqubn2TPsH0
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.onCreateTextClassificationSession(classificationContext, sessionId);
                    }
                }, null, null, this, userState));
            }
        }
    }

    public void onDestroyTextClassificationSession(final TextClassificationSessionId sessionId) throws RemoteException {
        Preconditions.checkNotNull(sessionId);
        synchronized (this.mLock) {
            UserState userState = getCallingUserStateLocked();
            if (userState.isBoundLocked()) {
                userState.mService.onDestroyTextClassificationSession(sessionId);
            } else {
                userState.mPendingRequests.add(new PendingRequest(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$pc4ryoobAsvmL5rAUjkRjLM3K84
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.onDestroyTextClassificationSession(sessionId);
                    }
                }, null, null, this, userState));
            }
        }
    }

    private UserState getCallingUserStateLocked() {
        return getUserStateLocked(UserHandle.getCallingUserId());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public UserState getUserStateLocked(int userId) {
        UserState result = this.mUserStates.get(userId);
        if (result == null) {
            UserState result2 = new UserState(userId, this.mContext, this.mLock);
            this.mUserStates.put(userId, result2);
            return result2;
        }
        return result;
    }

    UserState peekUserStateLocked(int userId) {
        return this.mUserStates.get(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class PendingRequest implements IBinder.DeathRecipient {
        private final IBinder mBinder;
        private final Runnable mOnServiceFailure;
        @GuardedBy("mLock")
        private final UserState mOwningUser;
        private final Runnable mRequest;
        private final TextClassificationManagerService mService;

        PendingRequest(FunctionalUtils.ThrowingRunnable request, FunctionalUtils.ThrowingRunnable onServiceFailure, IBinder binder, TextClassificationManagerService service, UserState owningUser) {
            this.mRequest = TextClassificationManagerService.logOnFailure((FunctionalUtils.ThrowingRunnable) Preconditions.checkNotNull(request), "handling pending request");
            this.mOnServiceFailure = TextClassificationManagerService.logOnFailure(onServiceFailure, "notifying callback of service failure");
            this.mBinder = binder;
            this.mService = service;
            this.mOwningUser = owningUser;
            if (this.mBinder != null) {
                try {
                    this.mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (this.mService.mLock) {
                removeLocked();
            }
        }

        @GuardedBy("mLock")
        private void removeLocked() {
            this.mOwningUser.mPendingRequests.remove(this);
            if (this.mBinder != null) {
                this.mBinder.unlinkToDeath(this, 0);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Runnable logOnFailure(FunctionalUtils.ThrowingRunnable r, final String opDesc) {
        if (r == null) {
            return null;
        }
        return FunctionalUtils.handleExceptions(r, new Consumer() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$AlzZLOTDy6ySI7ijsc3zdoY2qPo
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                Throwable th = (Throwable) obj;
                Slog.d(TextClassificationManagerService.LOG_TAG, "Error " + opDesc + ": " + th.getMessage());
            }
        });
    }

    private static void validateInput(String packageName, Context context) throws RemoteException {
        try {
            int uid = context.getPackageManager().getPackageUid(packageName, 0);
            Preconditions.checkArgument(Binder.getCallingUid() == uid);
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException | NullPointerException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class UserState {
        @GuardedBy("mLock")
        boolean mBinding;
        final TextClassifierServiceConnection mConnection;
        private final Context mContext;
        private final Object mLock;
        @GuardedBy("mLock")
        final Queue<PendingRequest> mPendingRequests;
        @GuardedBy("mLock")
        ITextClassifierService mService;
        final int mUserId;

        private UserState(int userId, Context context, Object lock) {
            this.mConnection = new TextClassifierServiceConnection();
            this.mPendingRequests = new ArrayDeque();
            this.mUserId = userId;
            this.mContext = (Context) Preconditions.checkNotNull(context);
            this.mLock = Preconditions.checkNotNull(lock);
        }

        @GuardedBy("mLock")
        boolean isBoundLocked() {
            return this.mService != null;
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy("mLock")
        public void handlePendingRequestsLocked() {
            while (true) {
                PendingRequest request = this.mPendingRequests.poll();
                if (request != null) {
                    if (isBoundLocked()) {
                        request.mRequest.run();
                    } else if (request.mOnServiceFailure != null) {
                        request.mOnServiceFailure.run();
                    }
                    if (request.mBinder != null) {
                        request.mBinder.unlinkToDeath(request, 0);
                    }
                } else {
                    return;
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean bindIfHasPendingRequestsLocked() {
            return !this.mPendingRequests.isEmpty() && bindLocked();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean bindLocked() {
            if (isBoundLocked() || this.mBinding) {
                return true;
            }
            long identity = Binder.clearCallingIdentity();
            try {
                ComponentName componentName = TextClassifierService.getServiceComponentName(this.mContext);
                if (componentName != null) {
                    Intent serviceIntent = new Intent("android.service.textclassifier.TextClassifierService").setComponent(componentName);
                    Slog.d(TextClassificationManagerService.LOG_TAG, "Binding to " + serviceIntent.getComponent());
                    boolean willBind = this.mContext.bindServiceAsUser(serviceIntent, this.mConnection, 67108865, UserHandle.of(this.mUserId));
                    this.mBinding = willBind;
                    return willBind;
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public final class TextClassifierServiceConnection implements ServiceConnection {
            private TextClassifierServiceConnection() {
            }

            @Override // android.content.ServiceConnection
            public void onServiceConnected(ComponentName name, IBinder service) {
                init(ITextClassifierService.Stub.asInterface(service));
            }

            @Override // android.content.ServiceConnection
            public void onServiceDisconnected(ComponentName name) {
                cleanupService();
            }

            @Override // android.content.ServiceConnection
            public void onBindingDied(ComponentName name) {
                cleanupService();
            }

            @Override // android.content.ServiceConnection
            public void onNullBinding(ComponentName name) {
                cleanupService();
            }

            void cleanupService() {
                init(null);
            }

            private void init(ITextClassifierService service) {
                synchronized (UserState.this.mLock) {
                    UserState.this.mService = service;
                    UserState.this.mBinding = false;
                    UserState.this.handlePendingRequestsLocked();
                }
            }
        }
    }
}
