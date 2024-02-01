package android.net;

import android.content.Context;
import android.net.ipmemorystore.Blob;
import android.net.ipmemorystore.NetworkAttributes;
import android.net.ipmemorystore.OnBlobRetrievedListener;
import android.net.ipmemorystore.OnL2KeyResponseListener;
import android.net.ipmemorystore.OnNetworkAttributesRetrievedListener;
import android.net.ipmemorystore.OnSameL3NetworkResponseListener;
import android.net.ipmemorystore.OnStatusListener;
import android.net.ipmemorystore.Status;
import android.os.RemoteException;
import android.util.Log;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/* loaded from: classes.dex */
public abstract class IpMemoryStoreClient {
    private static final String TAG = IpMemoryStoreClient.class.getSimpleName();
    private final Context mContext;

    /* JADX INFO: Access modifiers changed from: private */
    @FunctionalInterface
    /* loaded from: classes.dex */
    public interface ThrowingRunnable {
        void run() throws RemoteException;
    }

    protected abstract void runWhenServiceReady(Consumer<IIpMemoryStore> consumer) throws ExecutionException;

    public IpMemoryStoreClient(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("missing context");
        }
        this.mContext = context;
    }

    private void ignoringRemoteException(ThrowingRunnable r) {
        ignoringRemoteException("Failed to execute remote procedure call", r);
    }

    private void ignoringRemoteException(String message, ThrowingRunnable r) {
        try {
            r.run();
        } catch (RemoteException e) {
            Log.e(TAG, message, e);
        }
    }

    public /* synthetic */ void lambda$storeNetworkAttributes$1$IpMemoryStoreClient(final String l2Key, final NetworkAttributes attributes, final OnStatusListener listener, final IIpMemoryStore service) {
        ignoringRemoteException(new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$4LLLcxcDI48Nnc_rkm7mdSQsa2U
            @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
            public final void run() {
                IIpMemoryStore.this.storeNetworkAttributes(l2Key, attributes.toParcelable(), OnStatusListener.toAIDL(listener));
            }
        });
    }

    public void storeNetworkAttributes(final String l2Key, final NetworkAttributes attributes, final OnStatusListener listener) {
        try {
            runWhenServiceReady(new Consumer() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$0LhXdcPG7yJtV5UggjyJkRbARKU
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    IpMemoryStoreClient.this.lambda$storeNetworkAttributes$1$IpMemoryStoreClient(l2Key, attributes, listener, (IIpMemoryStore) obj);
                }
            });
        } catch (ExecutionException e) {
            ignoringRemoteException("Error storing network attributes", new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$FjB7dm6lAwZ6pH1lqvrhxtLFOm8
                @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
                public final void run() {
                    OnStatusListener.this.onComplete(new Status(-5));
                }
            });
        }
    }

    public /* synthetic */ void lambda$storeBlob$4$IpMemoryStoreClient(final String l2Key, final String clientId, final String name, final Blob data, final OnStatusListener listener, final IIpMemoryStore service) {
        ignoringRemoteException(new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$4eqT-tDGA25PNMyU_1yqQCF2gOo
            @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
            public final void run() {
                IIpMemoryStore.this.storeBlob(l2Key, clientId, name, data, OnStatusListener.toAIDL(listener));
            }
        });
    }

    public void storeBlob(final String l2Key, final String clientId, final String name, final Blob data, final OnStatusListener listener) {
        try {
            runWhenServiceReady(new Consumer() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$OI4Zw2djhZoG0D4IE2ujC0Iv6G4
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    IpMemoryStoreClient.this.lambda$storeBlob$4$IpMemoryStoreClient(l2Key, clientId, name, data, listener, (IIpMemoryStore) obj);
                }
            });
        } catch (ExecutionException e) {
            ignoringRemoteException("Error storing blob", new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$Rs7okZ0ViR35WkNSGbyhqEXxJxc
                @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
                public final void run() {
                    OnStatusListener.this.onComplete(new Status(-5));
                }
            });
        }
    }

    public void findL2Key(final NetworkAttributes attributes, final OnL2KeyResponseListener listener) {
        try {
            runWhenServiceReady(new Consumer() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$uI7nYxd7GfJucRXO9KcNTbbWOlc
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    IpMemoryStoreClient.this.lambda$findL2Key$7$IpMemoryStoreClient(attributes, listener, (IIpMemoryStore) obj);
                }
            });
        } catch (ExecutionException e) {
            ignoringRemoteException("Error finding L2 Key", new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$xx1upXTRGTVc0003KEhaxwIwwN8
                @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
                public final void run() {
                    OnL2KeyResponseListener.this.onL2KeyResponse(new Status(-5), null);
                }
            });
        }
    }

    public /* synthetic */ void lambda$findL2Key$7$IpMemoryStoreClient(final NetworkAttributes attributes, final OnL2KeyResponseListener listener, final IIpMemoryStore service) {
        ignoringRemoteException(new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$2bQLFhsJeYf5bkZg0-91OSOTEJY
            @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
            public final void run() {
                IIpMemoryStore.this.findL2Key(attributes.toParcelable(), OnL2KeyResponseListener.toAIDL(listener));
            }
        });
    }

    public void isSameNetwork(final String l2Key1, final String l2Key2, final OnSameL3NetworkResponseListener listener) {
        try {
            runWhenServiceReady(new Consumer() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$uHUebZ3pZ1jD5N1tZNdyTy8zBCE
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    IpMemoryStoreClient.this.lambda$isSameNetwork$10$IpMemoryStoreClient(l2Key1, l2Key2, listener, (IIpMemoryStore) obj);
                }
            });
        } catch (ExecutionException e) {
            ignoringRemoteException("Error checking for network sameness", new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$V28n1xp79cKTZf0npS-vzf7FUo8
                @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
                public final void run() {
                    OnSameL3NetworkResponseListener.this.onSameL3NetworkResponse(new Status(-5), null);
                }
            });
        }
    }

    public /* synthetic */ void lambda$isSameNetwork$10$IpMemoryStoreClient(final String l2Key1, final String l2Key2, final OnSameL3NetworkResponseListener listener, final IIpMemoryStore service) {
        ignoringRemoteException(new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$A2hOjZriLOXFq3Aij0wHaYZQOSc
            @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
            public final void run() {
                IIpMemoryStore.this.isSameNetwork(l2Key1, l2Key2, OnSameL3NetworkResponseListener.toAIDL(listener));
            }
        });
    }

    public /* synthetic */ void lambda$retrieveNetworkAttributes$13$IpMemoryStoreClient(final String l2Key, final OnNetworkAttributesRetrievedListener listener, final IIpMemoryStore service) {
        ignoringRemoteException(new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$Uc0QFR5a_MhzwuvUoWpz73NAAEs
            @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
            public final void run() {
                IIpMemoryStore.this.retrieveNetworkAttributes(l2Key, OnNetworkAttributesRetrievedListener.toAIDL(listener));
            }
        });
    }

    public void retrieveNetworkAttributes(final String l2Key, final OnNetworkAttributesRetrievedListener listener) {
        try {
            runWhenServiceReady(new Consumer() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$OnrcybvxwSrQUBY_VqGsD_5lQfI
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    IpMemoryStoreClient.this.lambda$retrieveNetworkAttributes$13$IpMemoryStoreClient(l2Key, listener, (IIpMemoryStore) obj);
                }
            });
        } catch (ExecutionException e) {
            ignoringRemoteException("Error retrieving network attributes", new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$JTvBo0T3ntOmEDS60qZyBJUlJio
                @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
                public final void run() {
                    OnNetworkAttributesRetrievedListener.this.onNetworkAttributesRetrieved(new Status(-5), null, null);
                }
            });
        }
    }

    public /* synthetic */ void lambda$retrieveBlob$16$IpMemoryStoreClient(final String l2Key, final String clientId, final String name, final OnBlobRetrievedListener listener, final IIpMemoryStore service) {
        ignoringRemoteException(new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$284VFgqq7BBkkwVNFLIrF3c59Es
            @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
            public final void run() {
                IIpMemoryStore.this.retrieveBlob(l2Key, clientId, name, OnBlobRetrievedListener.toAIDL(listener));
            }
        });
    }

    public void retrieveBlob(final String l2Key, final String clientId, final String name, final OnBlobRetrievedListener listener) {
        try {
            runWhenServiceReady(new Consumer() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$3VeddAdCuqfXquVC2DlGvI3eVPM
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    IpMemoryStoreClient.this.lambda$retrieveBlob$16$IpMemoryStoreClient(l2Key, clientId, name, listener, (IIpMemoryStore) obj);
                }
            });
        } catch (ExecutionException e) {
            ignoringRemoteException("Error retrieving blob", new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$hPxh-gsDi3P-N7OFwwZBxGXYZTs
                @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
                public final void run() {
                    OnBlobRetrievedListener.this.onBlobRetrieved(new Status(-5), null, null, null);
                }
            });
        }
    }

    public void factoryReset() {
        try {
            runWhenServiceReady(new Consumer() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$jkIRcIYXFgUFqiYMobpnQ9tj1vc
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    IpMemoryStoreClient.this.lambda$factoryReset$19$IpMemoryStoreClient((IIpMemoryStore) obj);
                }
            });
        } catch (ExecutionException m) {
            Log.e(TAG, "Error executing factory reset", m);
        }
    }

    public /* synthetic */ void lambda$factoryReset$19$IpMemoryStoreClient(final IIpMemoryStore service) {
        ignoringRemoteException(new ThrowingRunnable() { // from class: android.net.-$$Lambda$IpMemoryStoreClient$y9CML5-H8l7LhlZfPXBMWllilSs
            @Override // android.net.IpMemoryStoreClient.ThrowingRunnable
            public final void run() {
                IIpMemoryStore.this.factoryReset();
            }
        });
    }
}
