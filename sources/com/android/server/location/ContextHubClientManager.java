package com.android.server.location;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.location.ContextHubInfo;
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
    private static final boolean DEBUG_LOG_ENABLED = false;
    private static final int MAX_CLIENT_ID = 32767;
    private static final String TAG = "ContextHubClientManager";
    private final Context mContext;
    private final IContexthub mContextHubProxy;
    private final ConcurrentHashMap<Short, ContextHubClientBroker> mHostEndPointIdToClientMap = new ConcurrentHashMap<>();
    private int mNextHostEndPointId = 0;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContextHubClientManager(Context context, IContexthub contextHubProxy) {
        this.mContext = context;
        this.mContextHubProxy = contextHubProxy;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r7v0, types: [com.android.server.location.ContextHubClientBroker, java.lang.Object, android.os.IBinder] */
    public IContextHubClient registerClient(ContextHubInfo contextHubInfo, IContextHubClientCallback clientCallback) {
        ?? contextHubClientBroker;
        synchronized (this) {
            short hostEndPointId = getHostEndPointId();
            contextHubClientBroker = new ContextHubClientBroker(this.mContext, this.mContextHubProxy, this, contextHubInfo, hostEndPointId, clientCallback);
            this.mHostEndPointIdToClientMap.put(Short.valueOf(hostEndPointId), contextHubClientBroker);
        }
        try {
            contextHubClientBroker.attachDeathRecipient();
            Log.d(TAG, "Registered client with host endpoint ID " + ((int) contextHubClientBroker.getHostEndPointId()));
            return IContextHubClient.Stub.asInterface((IBinder) contextHubClientBroker);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to attach death recipient to client");
            contextHubClientBroker.close();
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Type inference failed for: r0v5, types: [com.android.server.location.ContextHubClientBroker, android.os.IBinder] */
    /* JADX WARN: Type inference failed for: r0v7 */
    /* JADX WARN: Type inference failed for: r0v8 */
    public IContextHubClient registerClient(ContextHubInfo contextHubInfo, PendingIntent pendingIntent, long nanoAppId) {
        String registerString = "Regenerated";
        synchronized (this) {
            try {
                try {
                    ContextHubClientBroker broker = getClientBroker(contextHubInfo.getId(), pendingIntent, nanoAppId);
                    ?? r0 = broker;
                    if (broker == null) {
                        short hostEndPointId = getHostEndPointId();
                        ContextHubClientBroker broker2 = new ContextHubClientBroker(this.mContext, this.mContextHubProxy, this, contextHubInfo, hostEndPointId, pendingIntent, nanoAppId);
                        this.mHostEndPointIdToClientMap.put(Short.valueOf(hostEndPointId), broker2);
                        registerString = "Registered";
                        r0 = broker2;
                    }
                    Log.d(TAG, registerString + " client with host endpoint ID " + ((int) r0.getHostEndPointId()));
                    return IContextHubClient.Stub.asInterface((IBinder) r0);
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onMessageFromNanoApp(int contextHubId, ContextHubMsg message) {
        NanoAppMessage clientMessage = ContextHubServiceUtil.createNanoAppMessage(message);
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

    private short getHostEndPointId() {
        if (this.mHostEndPointIdToClientMap.size() == 32768) {
            throw new IllegalStateException("Could not register client - max limit exceeded");
        }
        int id = this.mNextHostEndPointId;
        int i = 0;
        while (true) {
            if (i > MAX_CLIENT_ID) {
                break;
            }
            if (!this.mHostEndPointIdToClientMap.containsKey(Short.valueOf((short) id))) {
                this.mNextHostEndPointId = id != MAX_CLIENT_ID ? id + 1 : 0;
            } else {
                if (id != MAX_CLIENT_ID) {
                    r4 = id + 1;
                }
                id = r4;
                i++;
            }
        }
        return (short) id;
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

    private ContextHubClientBroker getClientBroker(int contextHubId, PendingIntent pendingIntent, long nanoAppId) {
        for (ContextHubClientBroker broker : this.mHostEndPointIdToClientMap.values()) {
            if (broker.hasPendingIntent(pendingIntent, nanoAppId) && broker.getAttachedContextHubId() == contextHubId) {
                return broker;
            }
        }
        return null;
    }
}
