package com.android.server.location;

import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.location.IContextHubTransactionCallback;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppState;
import android.os.RemoteException;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ContextHubTransactionManager {
    private static final int MAX_PENDING_REQUESTS = 10000;
    private static final String TAG = "ContextHubTransactionManager";
    private final ContextHubClientManager mClientManager;
    private final IContexthub mContextHubProxy;
    private final NanoAppStateManager mNanoAppStateManager;
    private final ArrayDeque<ContextHubServiceTransaction> mTransactionQueue = new ArrayDeque<>();
    private final AtomicInteger mNextAvailableId = new AtomicInteger();
    private final ScheduledThreadPoolExecutor mTimeoutExecutor = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> mTimeoutFuture = null;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubTransactionManager(IContexthub contextHubProxy, ContextHubClientManager clientManager, NanoAppStateManager nanoAppStateManager) {
        this.mContextHubProxy = contextHubProxy;
        this.mClientManager = clientManager;
        this.mNanoAppStateManager = nanoAppStateManager;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubServiceTransaction createLoadTransaction(final int contextHubId, final NanoAppBinary nanoAppBinary, final IContextHubTransactionCallback onCompleteCallback) {
        return new ContextHubServiceTransaction(this.mNextAvailableId.getAndIncrement(), 0) { // from class: com.android.server.location.ContextHubTransactionManager.1
            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public int onTransact() {
                android.hardware.contexthub.V1_0.NanoAppBinary hidlNanoAppBinary = ContextHubServiceUtil.createHidlNanoAppBinary(nanoAppBinary);
                try {
                    return ContextHubTransactionManager.this.mContextHubProxy.loadNanoApp(contextHubId, hidlNanoAppBinary, getTransactionId());
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while trying to load nanoapp with ID 0x" + Long.toHexString(nanoAppBinary.getNanoAppId()), e);
                    return 1;
                }
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public void onTransactionComplete(int result) {
                if (result == 0) {
                    ContextHubTransactionManager.this.mNanoAppStateManager.addNanoAppInstance(contextHubId, nanoAppBinary.getNanoAppId(), nanoAppBinary.getNanoAppVersion());
                }
                try {
                    onCompleteCallback.onTransactionComplete(result);
                    if (result == 0) {
                        ContextHubTransactionManager.this.mClientManager.onNanoAppLoaded(contextHubId, nanoAppBinary.getNanoAppId());
                    }
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubServiceTransaction createUnloadTransaction(final int contextHubId, final long nanoAppId, final IContextHubTransactionCallback onCompleteCallback) {
        return new ContextHubServiceTransaction(this.mNextAvailableId.getAndIncrement(), 1) { // from class: com.android.server.location.ContextHubTransactionManager.2
            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public int onTransact() {
                try {
                    return ContextHubTransactionManager.this.mContextHubProxy.unloadNanoApp(contextHubId, nanoAppId, getTransactionId());
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while trying to unload nanoapp with ID 0x" + Long.toHexString(nanoAppId), e);
                    return 1;
                }
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public void onTransactionComplete(int result) {
                if (result == 0) {
                    ContextHubTransactionManager.this.mNanoAppStateManager.removeNanoAppInstance(contextHubId, nanoAppId);
                }
                try {
                    onCompleteCallback.onTransactionComplete(result);
                    if (result == 0) {
                        ContextHubTransactionManager.this.mClientManager.onNanoAppUnloaded(contextHubId, nanoAppId);
                    }
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubServiceTransaction createEnableTransaction(final int contextHubId, final long nanoAppId, final IContextHubTransactionCallback onCompleteCallback) {
        return new ContextHubServiceTransaction(this.mNextAvailableId.getAndIncrement(), 2) { // from class: com.android.server.location.ContextHubTransactionManager.3
            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public int onTransact() {
                try {
                    return ContextHubTransactionManager.this.mContextHubProxy.enableNanoApp(contextHubId, nanoAppId, getTransactionId());
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while trying to enable nanoapp with ID 0x" + Long.toHexString(nanoAppId), e);
                    return 1;
                }
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public void onTransactionComplete(int result) {
                try {
                    onCompleteCallback.onTransactionComplete(result);
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubServiceTransaction createDisableTransaction(final int contextHubId, final long nanoAppId, final IContextHubTransactionCallback onCompleteCallback) {
        return new ContextHubServiceTransaction(this.mNextAvailableId.getAndIncrement(), 3) { // from class: com.android.server.location.ContextHubTransactionManager.4
            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public int onTransact() {
                try {
                    return ContextHubTransactionManager.this.mContextHubProxy.disableNanoApp(contextHubId, nanoAppId, getTransactionId());
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while trying to disable nanoapp with ID 0x" + Long.toHexString(nanoAppId), e);
                    return 1;
                }
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public void onTransactionComplete(int result) {
                try {
                    onCompleteCallback.onTransactionComplete(result);
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while calling client onTransactionComplete", e);
                }
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubServiceTransaction createQueryTransaction(final int contextHubId, final IContextHubTransactionCallback onCompleteCallback) {
        return new ContextHubServiceTransaction(this.mNextAvailableId.getAndIncrement(), 4) { // from class: com.android.server.location.ContextHubTransactionManager.5
            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public int onTransact() {
                try {
                    return ContextHubTransactionManager.this.mContextHubProxy.queryApps(contextHubId);
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while trying to query for nanoapps", e);
                    return 1;
                }
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public void onTransactionComplete(int result) {
                onQueryResponse(result, Collections.emptyList());
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.location.ContextHubServiceTransaction
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
                try {
                    onCompleteCallback.onQueryResponse(result, nanoAppStateList);
                } catch (RemoteException e) {
                    Log.e(ContextHubTransactionManager.TAG, "RemoteException while calling client onQueryComplete", e);
                }
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void addTransaction(ContextHubServiceTransaction transaction) throws IllegalStateException {
        if (this.mTransactionQueue.size() == 10000) {
            throw new IllegalStateException("Transaction queue is full (capacity = 10000)");
        }
        this.mTransactionQueue.add(transaction);
        if (this.mTransactionQueue.size() == 1) {
            startNextTransaction();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onTransactionResponse(int transactionId, int result) {
        int i;
        ContextHubServiceTransaction transaction = this.mTransactionQueue.peek();
        if (transaction == null) {
            Log.w(TAG, "Received unexpected transaction response (no transaction pending)");
        } else if (transaction.getTransactionId() != transactionId) {
            Log.w(TAG, "Received unexpected transaction response (expected ID = " + transaction.getTransactionId() + ", received ID = " + transactionId + ")");
        } else {
            if (result == 0) {
                i = 0;
            } else {
                i = 5;
            }
            transaction.onTransactionComplete(i);
            removeTransactionAndStartNext();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onQueryResponse(List<NanoAppState> nanoAppStateList) {
        ContextHubServiceTransaction transaction = this.mTransactionQueue.peek();
        if (transaction == null) {
            Log.w(TAG, "Received unexpected query response (no transaction pending)");
        } else if (transaction.getTransactionType() != 4) {
            Log.w(TAG, "Received unexpected query response (expected " + transaction + ")");
        } else {
            transaction.onQueryResponse(0, nanoAppStateList);
            removeTransactionAndStartNext();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public synchronized void onHubReset() {
        ContextHubServiceTransaction transaction = this.mTransactionQueue.peek();
        if (transaction == null) {
            return;
        }
        removeTransactionAndStartNext();
    }

    private void removeTransactionAndStartNext() {
        this.mTimeoutFuture.cancel(false);
        ContextHubServiceTransaction transaction = this.mTransactionQueue.remove();
        transaction.setComplete();
        if (!this.mTransactionQueue.isEmpty()) {
            startNextTransaction();
        }
    }

    private void startNextTransaction() {
        int result = 1;
        while (result != 0 && !this.mTransactionQueue.isEmpty()) {
            final ContextHubServiceTransaction transaction = this.mTransactionQueue.peek();
            result = transaction.onTransact();
            if (result == 0) {
                Runnable onTimeoutFunc = new Runnable() { // from class: com.android.server.location.-$$Lambda$ContextHubTransactionManager$sHbjr4TaLEATkCX_yhD2L7ebuxE
                    @Override // java.lang.Runnable
                    public final void run() {
                        ContextHubTransactionManager.lambda$startNextTransaction$0(ContextHubTransactionManager.this, transaction);
                    }
                };
                long timeoutSeconds = transaction.getTimeout(TimeUnit.SECONDS);
                this.mTimeoutFuture = this.mTimeoutExecutor.schedule(onTimeoutFunc, timeoutSeconds, TimeUnit.SECONDS);
            } else {
                transaction.onTransactionComplete(ContextHubServiceUtil.toTransactionResult(result));
                this.mTransactionQueue.remove();
            }
        }
    }

    public static /* synthetic */ void lambda$startNextTransaction$0(ContextHubTransactionManager contextHubTransactionManager, ContextHubServiceTransaction transaction) {
        synchronized (contextHubTransactionManager) {
            if (!transaction.isComplete()) {
                Log.d(TAG, transaction + " timed out");
                transaction.onTransactionComplete(6);
                contextHubTransactionManager.removeTransactionAndStartNext();
            }
        }
    }
}
