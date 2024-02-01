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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ContextHubClientManager {
    private static final boolean DEBUG_LOG_ENABLED = true;
    private static final int MAX_CLIENT_ID = 32767;
    private static final String TAG = "ContextHubClientManager";
    private final Context mContext;
    private final IContexthub mContextHubProxy;
    private final ConcurrentHashMap<Short, ContextHubClientBroker> mHostEndPointIdToClientMap = new ConcurrentHashMap<>();
    private int mNextHostEndpointId = 0;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubClientManager(Context context, IContexthub contextHubProxy) {
        this.mContext = context;
        this.mContextHubProxy = contextHubProxy;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.location.ContextHubClientBroker, android.os.IBinder] */
    public IContextHubClient registerClient(IContextHubClientCallback clientCallback, int contextHubId) {
        ?? createNewClientBroker = createNewClientBroker(clientCallback, contextHubId);
        try {
            createNewClientBroker.attachDeathRecipient();
            Log.d(TAG, "Registered client with host endpoint ID " + ((int) createNewClientBroker.getHostEndPointId()));
            return IContextHubClient.Stub.asInterface((IBinder) createNewClientBroker);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to attach death recipient to client");
            createNewClientBroker.close();
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onMessageFromNanoApp(int contextHubId, ContextHubMsg message) {
        NanoAppMessage clientMessage = ContextHubServiceUtil.createNanoAppMessage(message);
        Log.v(TAG, "Received " + clientMessage);
        if (clientMessage.isBroadcastMessage()) {
            broadcastMessage(contextHubId, clientMessage);
            return;
        }
        ContextHubClientBroker proxy = this.mHostEndPointIdToClientMap.get(Short.valueOf(message.hostEndPoint));
        if (proxy != null) {
            proxy.sendMessageToClient(clientMessage);
            return;
        }
        Log.e(TAG, "Cannot send message to unregistered client (host endpoint ID = " + ((int) message.hostEndPoint) + ")");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterClient(short hostEndPointId) {
        if (this.mHostEndPointIdToClientMap.remove(Short.valueOf(hostEndPointId)) != null) {
            Log.d(TAG, "Unregistered client with host endpoint ID " + ((int) hostEndPointId));
            return;
        }
        Log.e(TAG, "Cannot unregister non-existing client with host endpoint ID " + ((int) hostEndPointId));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNanoAppLoaded(int contextHubId, final long nanoAppId) {
        forEachClientOfHub(contextHubId, new Consumer() { // from class: com.android.server.location.-$$Lambda$ContextHubClientManager$VPD5ebhe8Z67S8QKuTR4KzeshK8
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((ContextHubClientBroker) obj).onNanoAppLoaded(nanoAppId);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNanoAppUnloaded(int contextHubId, final long nanoAppId) {
        forEachClientOfHub(contextHubId, new Consumer() { // from class: com.android.server.location.-$$Lambda$ContextHubClientManager$gN_vRogwyzr9qBjrQpKwwHzrFAo
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((ContextHubClientBroker) obj).onNanoAppUnloaded(nanoAppId);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onHubReset(int contextHubId) {
        forEachClientOfHub(contextHubId, new Consumer() { // from class: com.android.server.location.-$$Lambda$ContextHubClientManager$aRAV9Gn84ao-4XOiN6tFizfZjHo
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((ContextHubClientBroker) obj).onHubReset();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onNanoAppAborted(int contextHubId, final long nanoAppId, final int abortCode) {
        forEachClientOfHub(contextHubId, new Consumer() { // from class: com.android.server.location.-$$Lambda$ContextHubClientManager$WHzSH2f-YJ3FaiF7JXPP-7oX9EE
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((ContextHubClientBroker) obj).onNanoAppAborted(nanoAppId, abortCode);
            }
        });
    }

    private synchronized ContextHubClientBroker createNewClientBroker(IContextHubClientCallback clientCallback, int contextHubId) {
        ContextHubClientBroker broker;
        if (this.mHostEndPointIdToClientMap.size() == 32768) {
            throw new IllegalStateException("Could not register client - max limit exceeded");
        }
        broker = null;
        int id = this.mNextHostEndpointId;
        int i = 0;
        int id2 = id;
        int id3 = 0;
        while (true) {
            if (id3 > MAX_CLIENT_ID) {
                break;
            } else if (!this.mHostEndPointIdToClientMap.containsKey(Short.valueOf((short) id2))) {
                broker = new ContextHubClientBroker(this.mContext, this.mContextHubProxy, this, contextHubId, (short) id2, clientCallback);
                this.mHostEndPointIdToClientMap.put(Short.valueOf((short) id2), broker);
                if (id2 != MAX_CLIENT_ID) {
                    i = id2 + 1;
                }
                this.mNextHostEndpointId = i;
            } else {
                id2 = id2 == MAX_CLIENT_ID ? 0 : id2 + 1;
                id3++;
            }
        }
        return broker;
    }

    private void broadcastMessage(int contextHubId, final NanoAppMessage message) {
        forEachClientOfHub(contextHubId, new Consumer() { // from class: com.android.server.location.-$$Lambda$ContextHubClientManager$f15OSYbsSONpkXn7GinnrBPeumw
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((ContextHubClientBroker) obj).sendMessageToClient(message);
            }
        });
    }

    private void forEachClientOfHub(int contextHubId, Consumer<ContextHubClientBroker> callback) {
        for (ContextHubClientBroker broker : this.mHostEndPointIdToClientMap.values()) {
            if (broker.getAttachedContextHubId() == contextHubId) {
                callback.accept(broker);
            }
        }
    }
}
