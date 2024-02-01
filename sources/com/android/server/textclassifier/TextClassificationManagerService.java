package com.android.server.textclassifier;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.textclassifier.ITextClassifierCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.TextClassifierService;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassificationSessionId;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextLanguage;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;

/* loaded from: classes2.dex */
public final class TextClassificationManagerService extends ITextClassifierService.Stub {
    private static final String LOG_TAG = "TextClassificationManagerService";
    private final Context mContext;
    private final Object mLock;
    @GuardedBy({"mLock"})
    private final Map<TextClassificationSessionId, Integer> mSessionUserIds;
    @GuardedBy({"mLock"})
    final SparseArray<UserState> mUserStates;

    /* loaded from: classes2.dex */
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
        this.mSessionUserIds = new ArrayMap();
        this.mContext = (Context) Preconditions.checkNotNull(context);
        this.mLock = new Object();
    }

    /* renamed from: onSuggestSelection */
    public void lambda$onSuggestSelection$0$TextClassificationManagerService(final TextClassificationSessionId sessionId, final TextSelection.Request request, final ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        int userId = request.getUserId();
        validateInput(this.mContext, request.getCallingPackageName(), userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onSuggestSelection(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$Fy5j26FLkbnEPhoh1kWzQnYhcm8
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onSuggestSelection$0$TextClassificationManagerService(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new $$Lambda$k7KcqZH2A0AukChaKa6Xru13_Q(callback), callback.asBinder(), this, userState));
            }
        }
    }

    /* renamed from: onClassifyText */
    public void lambda$onClassifyText$1$TextClassificationManagerService(final TextClassificationSessionId sessionId, final TextClassification.Request request, final ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        int userId = request.getUserId();
        validateInput(this.mContext, request.getCallingPackageName(), userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onClassifyText(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$aNIcwykiT4wOQ8InWE4Im6x6k-E
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onClassifyText$1$TextClassificationManagerService(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new $$Lambda$k7KcqZH2A0AukChaKa6Xru13_Q(callback), callback.asBinder(), this, userState));
            }
        }
    }

    /* renamed from: onGenerateLinks */
    public void lambda$onGenerateLinks$2$TextClassificationManagerService(final TextClassificationSessionId sessionId, final TextLinks.Request request, final ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        int userId = request.getUserId();
        validateInput(this.mContext, request.getCallingPackageName(), userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onGenerateLinks(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$1N5hVEv-gYS5VzkBAP5HLq01CQI
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onGenerateLinks$2$TextClassificationManagerService(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new $$Lambda$k7KcqZH2A0AukChaKa6Xru13_Q(callback), callback.asBinder(), this, userState));
            }
        }
    }

    /* renamed from: onSelectionEvent */
    public void lambda$onSelectionEvent$3$TextClassificationManagerService(final TextClassificationSessionId sessionId, final SelectionEvent event) throws RemoteException {
        Preconditions.checkNotNull(event);
        int userId = event.getUserId();
        validateInput(this.mContext, event.getPackageName(), userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                userState.mService.onSelectionEvent(sessionId, event);
            } else {
                userState.mPendingRequests.add(new PendingRequest(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$Xo8FJ3LmQoamgJ2foxZOcS-n70c
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onSelectionEvent$3$TextClassificationManagerService(sessionId, event);
                    }
                }, null, null, this, userState));
            }
        }
    }

    /* renamed from: onTextClassifierEvent */
    public void lambda$onTextClassifierEvent$4$TextClassificationManagerService(final TextClassificationSessionId sessionId, final TextClassifierEvent event) throws RemoteException {
        String packageName;
        int userId;
        Preconditions.checkNotNull(event);
        if (event.getEventContext() == null) {
            packageName = null;
        } else {
            packageName = event.getEventContext().getPackageName();
        }
        if (event.getEventContext() == null) {
            userId = UserHandle.getCallingUserId();
        } else {
            userId = event.getEventContext().getUserId();
        }
        validateInput(this.mContext, packageName, userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                userState.mService.onTextClassifierEvent(sessionId, event);
            } else {
                userState.mPendingRequests.add(new PendingRequest(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$sMLFGuslbXgLyLQJD4NeR5KkZn0
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onTextClassifierEvent$4$TextClassificationManagerService(sessionId, event);
                    }
                }, null, null, this, userState));
            }
        }
    }

    /* renamed from: onDetectLanguage */
    public void lambda$onDetectLanguage$5$TextClassificationManagerService(final TextClassificationSessionId sessionId, final TextLanguage.Request request, final ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        int userId = request.getUserId();
        validateInput(this.mContext, request.getCallingPackageName(), userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onDetectLanguage(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$yB5oS3bxsmWcPiI9f0QxOl0chLs
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onDetectLanguage$5$TextClassificationManagerService(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new $$Lambda$k7KcqZH2A0AukChaKa6Xru13_Q(callback), callback.asBinder(), this, userState));
            }
        }
    }

    /* renamed from: onSuggestConversationActions */
    public void lambda$onSuggestConversationActions$6$TextClassificationManagerService(final TextClassificationSessionId sessionId, final ConversationActions.Request request, final ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        int userId = request.getUserId();
        validateInput(this.mContext, request.getCallingPackageName(), userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onSuggestConversationActions(sessionId, request, callback);
            } else {
                Queue<PendingRequest> queue = userState.mPendingRequests;
                FunctionalUtils.ThrowingRunnable throwingRunnable = new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$8JdB0qZEYu-RmsTmNRpxWLWnRgs
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onSuggestConversationActions$6$TextClassificationManagerService(sessionId, request, callback);
                    }
                };
                Objects.requireNonNull(callback);
                queue.add(new PendingRequest(throwingRunnable, new $$Lambda$k7KcqZH2A0AukChaKa6Xru13_Q(callback), callback.asBinder(), this, userState));
            }
        }
    }

    /* renamed from: onCreateTextClassificationSession */
    public void lambda$onCreateTextClassificationSession$7$TextClassificationManagerService(final TextClassificationContext classificationContext, final TextClassificationSessionId sessionId) throws RemoteException {
        Preconditions.checkNotNull(sessionId);
        Preconditions.checkNotNull(classificationContext);
        int userId = classificationContext.getUserId();
        validateInput(this.mContext, classificationContext.getPackageName(), userId);
        synchronized (this.mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                userState.mService.onCreateTextClassificationSession(classificationContext, sessionId);
                this.mSessionUserIds.put(sessionId, Integer.valueOf(userId));
            } else {
                userState.mPendingRequests.add(new PendingRequest(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$YjZl5O2nzrq_4fvkOEzBc8WS3aY
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onCreateTextClassificationSession$7$TextClassificationManagerService(classificationContext, sessionId);
                    }
                }, null, null, this, userState));
            }
        }
    }

    /* renamed from: onDestroyTextClassificationSession */
    public void lambda$onDestroyTextClassificationSession$8$TextClassificationManagerService(final TextClassificationSessionId sessionId) throws RemoteException {
        int userId;
        Preconditions.checkNotNull(sessionId);
        synchronized (this.mLock) {
            if (this.mSessionUserIds.containsKey(sessionId)) {
                userId = this.mSessionUserIds.get(sessionId).intValue();
            } else {
                userId = UserHandle.getCallingUserId();
            }
            validateInput(this.mContext, null, userId);
            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                userState.mService.onDestroyTextClassificationSession(sessionId);
                this.mSessionUserIds.remove(sessionId);
            } else {
                userState.mPendingRequests.add(new PendingRequest(new FunctionalUtils.ThrowingRunnable() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$IiiA6SYq7BOE-U1FJlf97_wO-k4
                    public final void runOrThrow() {
                        TextClassificationManagerService.this.lambda$onDestroyTextClassificationSession$8$TextClassificationManagerService(sessionId);
                    }
                }, null, null, this, userState));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public UserState getUserStateLocked(int userId) {
        UserState result = this.mUserStates.get(userId);
        if (result == null) {
            UserState result2 = new UserState(userId, this.mContext, this.mLock);
            this.mUserStates.put(userId, result2);
            return result2;
        }
        return result;
    }

    @GuardedBy({"mLock"})
    UserState peekUserStateLocked(int userId) {
        return this.mUserStates.get(userId);
    }

    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, LOG_TAG, fout)) {
            IndentingPrintWriter pw = new IndentingPrintWriter(fout, "  ");
            TextClassificationManager tcm = (TextClassificationManager) this.mContext.getSystemService(TextClassificationManager.class);
            tcm.dump(pw);
            pw.printPair("context", this.mContext);
            pw.println();
            synchronized (this.mLock) {
                int size = this.mUserStates.size();
                pw.print("Number user states: ");
                pw.println(size);
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        pw.increaseIndent();
                        UserState userState = this.mUserStates.valueAt(i);
                        pw.print(i);
                        pw.print(":");
                        userState.dump(pw);
                        pw.println();
                        pw.decreaseIndent();
                    }
                }
                pw.println("Number of active sessions: " + this.mSessionUserIds.size());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class PendingRequest implements IBinder.DeathRecipient {
        private final IBinder mBinder;
        private final Runnable mOnServiceFailure;
        @GuardedBy({"mLock"})
        private final UserState mOwningUser;
        private final Runnable mRequest;
        private final TextClassificationManagerService mService;

        PendingRequest(FunctionalUtils.ThrowingRunnable request, FunctionalUtils.ThrowingRunnable onServiceFailure, IBinder binder, TextClassificationManagerService service, UserState owningUser) {
            this.mRequest = TextClassificationManagerService.logOnFailure((FunctionalUtils.ThrowingRunnable) Preconditions.checkNotNull(request), "handling pending request");
            this.mOnServiceFailure = TextClassificationManagerService.logOnFailure(onServiceFailure, "notifying callback of service failure");
            this.mBinder = binder;
            this.mService = service;
            this.mOwningUser = owningUser;
            IBinder iBinder = this.mBinder;
            if (iBinder != null) {
                try {
                    iBinder.linkToDeath(this, 0);
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

        @GuardedBy({"mLock"})
        private void removeLocked() {
            this.mOwningUser.mPendingRequests.remove(this);
            IBinder iBinder = this.mBinder;
            if (iBinder != null) {
                iBinder.unlinkToDeath(this, 0);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Runnable logOnFailure(FunctionalUtils.ThrowingRunnable r, final String opDesc) {
        if (r == null) {
            return null;
        }
        return FunctionalUtils.handleExceptions(r, new Consumer() { // from class: com.android.server.textclassifier.-$$Lambda$TextClassificationManagerService$R4aPVSf5_OfouCzD96pPpSsbUOs
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                Throwable th = (Throwable) obj;
                Slog.d(TextClassificationManagerService.LOG_TAG, "Error " + opDesc + ": " + th.getMessage());
            }
        });
    }

    private static void validateInput(Context context, String packageName, int userId) throws RemoteException {
        boolean z;
        if (packageName != null) {
            try {
                int packageUid = context.getPackageManager().getPackageUidAsUser(packageName, UserHandle.getCallingUserId());
                int callingUid = Binder.getCallingUid();
                if (callingUid != packageUid && callingUid != 1000) {
                    z = false;
                    Preconditions.checkArgument(z, "Invalid package name. Package=" + packageName + ", CallingUid=" + callingUid);
                }
                z = true;
                Preconditions.checkArgument(z, "Invalid package name. Package=" + packageName + ", CallingUid=" + callingUid);
            } catch (Exception e) {
                throw new RemoteException("Invalid request: " + e.getMessage(), e, true, true);
            }
        }
        Preconditions.checkArgument(userId != -10000, "Null userId");
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId) {
            context.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL", "Invalid userId. UserId=" + userId + ", CallingUserId=" + callingUserId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class UserState {
        @GuardedBy({"mLock"})
        boolean mBinding;
        final TextClassifierServiceConnection mConnection;
        private final Context mContext;
        private final Object mLock;
        @GuardedBy({"mLock"})
        final Queue<PendingRequest> mPendingRequests;
        @GuardedBy({"mLock"})
        ITextClassifierService mService;
        final int mUserId;

        private UserState(int userId, Context context, Object lock) {
            this.mConnection = new TextClassifierServiceConnection();
            this.mPendingRequests = new ArrayDeque();
            this.mUserId = userId;
            this.mContext = (Context) Preconditions.checkNotNull(context);
            this.mLock = Preconditions.checkNotNull(lock);
        }

        @GuardedBy({"mLock"})
        boolean isBoundLocked() {
            return this.mService != null;
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy({"mLock"})
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
        @GuardedBy({"mLock"})
        public boolean bindIfHasPendingRequestsLocked() {
            return !this.mPendingRequests.isEmpty() && bindLocked();
        }

        /* JADX INFO: Access modifiers changed from: private */
        @GuardedBy({"mLock"})
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
                    boolean willBind = this.mContext.bindServiceAsUser(serviceIntent, this.mConnection, 69206017, UserHandle.of(this.mUserId));
                    this.mBinding = willBind;
                    return willBind;
                }
                return false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dump(IndentingPrintWriter pw) {
            pw.printPair("context", this.mContext);
            pw.printPair("userId", Integer.valueOf(this.mUserId));
            synchronized (this.mLock) {
                pw.printPair("binding", Boolean.valueOf(this.mBinding));
                pw.printPair("numberRequests", Integer.valueOf(this.mPendingRequests.size()));
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes2.dex */
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
