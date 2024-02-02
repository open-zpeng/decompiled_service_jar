package com.android.server.location;

import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.NanoAppMessage;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
/* loaded from: classes.dex */
public class ContextHubClientBroker extends IContextHubClient.Stub implements IBinder.DeathRecipient {
    private static final String TAG = "ContextHubClientBroker";
    private final int mAttachedContextHubId;
    private final IContextHubClientCallback mCallbackInterface;
    private final ContextHubClientManager mClientManager;
    private final AtomicBoolean mConnectionOpen = new AtomicBoolean(true);
    private final Context mContext;
    private final IContexthub mContextHubProxy;
    private final short mHostEndPointId;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubClientBroker(Context context, IContexthub contextHubProxy, ContextHubClientManager clientManager, int contextHubId, short hostEndPointId, IContextHubClientCallback callback) {
        this.mContext = context;
        this.mContextHubProxy = contextHubProxy;
        this.mClientManager = clientManager;
        this.mAttachedContextHubId = contextHubId;
        this.mHostEndPointId = hostEndPointId;
        this.mCallbackInterface = callback;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void attachDeathRecipient() throws RemoteException {
        this.mCallbackInterface.asBinder().linkToDeath(this, 0);
    }

    public int sendMessageToNanoApp(NanoAppMessage message) {
        ContextHubServiceUtil.checkPermissions(this.mContext);
        int result = 1;
        if (this.mConnectionOpen.get()) {
            ContextHubMsg messageToNanoApp = ContextHubServiceUtil.createHidlContextHubMessage(this.mHostEndPointId, message);
            try {
                result = this.mContextHubProxy.sendMessageToHub(this.mAttachedContextHubId, messageToNanoApp);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in sendMessageToNanoApp (target hub ID = " + this.mAttachedContextHubId + ")", e);
            }
        } else {
            Log.e(TAG, "Failed to send message to nanoapp: client connection is closed");
        }
        return ContextHubServiceUtil.toTransactionResult(result);
    }

    public void close() {
        if (this.mConnectionOpen.getAndSet(false)) {
            this.mClientManager.unregisterClient(this.mHostEndPointId);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    @Override // android.os.IBinder.DeathRecipient
    public void binderDied() {
        try {
            IContextHubClient.Stub.asInterface(this).close();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while closing client on death", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getAttachedContextHubId() {
        return this.mAttachedContextHubId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public short getHostEndPointId() {
        return this.mHostEndPointId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendMessageToClient(NanoAppMessage message) {
        if (this.mConnectionOpen.get()) {
            try {
                this.mCallbackInterface.onMessageFromNanoApp(message);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while sending message to client (host endpoint ID = " + ((int) this.mHostEndPointId) + ")", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNanoAppLoaded(long nanoAppId) {
        if (this.mConnectionOpen.get()) {
            try {
                this.mCallbackInterface.onNanoAppLoaded(nanoAppId);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onNanoAppLoaded on client (host endpoint ID = " + ((int) this.mHostEndPointId) + ")", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNanoAppUnloaded(long nanoAppId) {
        if (this.mConnectionOpen.get()) {
            try {
                this.mCallbackInterface.onNanoAppUnloaded(nanoAppId);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onNanoAppUnloaded on client (host endpoint ID = " + ((int) this.mHostEndPointId) + ")", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onHubReset() {
        if (this.mConnectionOpen.get()) {
            try {
                this.mCallbackInterface.onHubReset();
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onHubReset on client (host endpoint ID = " + ((int) this.mHostEndPointId) + ")", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNanoAppAborted(long nanoAppId, int abortCode) {
        if (this.mConnectionOpen.get()) {
            try {
                this.mCallbackInterface.onNanoAppAborted(nanoAppId, abortCode);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onNanoAppAborted on client (host endpoint ID = " + ((int) this.mHostEndPointId) + ")", e);
            }
        }
    }
}
