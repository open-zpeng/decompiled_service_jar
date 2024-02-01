package android.net;

import android.content.Context;
import android.net.IIpMemoryStoreCallbacks;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/* loaded from: classes.dex */
public class IpMemoryStore extends IpMemoryStoreClient {
    private static final String TAG = IpMemoryStore.class.getSimpleName();
    private final CompletableFuture<IIpMemoryStore> mService;
    private final AtomicReference<CompletableFuture<IIpMemoryStore>> mTailNode;

    public IpMemoryStore(Context context) {
        super(context);
        this.mService = new CompletableFuture<>();
        this.mTailNode = new AtomicReference<>(this.mService);
        getNetworkStackClient().fetchIpMemoryStore(new IIpMemoryStoreCallbacks.Stub() { // from class: android.net.IpMemoryStore.1
            @Override // android.net.IIpMemoryStoreCallbacks
            public void onIpMemoryStoreFetched(IIpMemoryStore memoryStore) {
                IpMemoryStore.this.mService.complete(memoryStore);
            }

            @Override // android.net.IIpMemoryStoreCallbacks
            public int getInterfaceVersion() {
                return 3;
            }
        });
    }

    @Override // android.net.IpMemoryStoreClient
    protected void runWhenServiceReady(final Consumer<IIpMemoryStore> cb) throws ExecutionException {
        this.mTailNode.getAndUpdate(new UnaryOperator() { // from class: android.net.-$$Lambda$IpMemoryStore$LPW97BoNSL4rh_RVPiAHfCbmGHU
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                CompletableFuture handle;
                handle = ((CompletableFuture) obj).handle(new BiFunction() { // from class: android.net.-$$Lambda$IpMemoryStore$pFctTFAvh_Nxb_aTb0gjNsixGeM
                    @Override // java.util.function.BiFunction
                    public final Object apply(Object obj2, Object obj3) {
                        return IpMemoryStore.lambda$runWhenServiceReady$0(r1, (IIpMemoryStore) obj2, (Throwable) obj3);
                    }
                });
                return handle;
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ IIpMemoryStore lambda$runWhenServiceReady$0(Consumer cb, IIpMemoryStore store, Throwable exception) {
        if (exception != null) {
            Log.wtf(TAG, "Error fetching IpMemoryStore", exception);
            return store;
        }
        try {
            cb.accept(store);
        } catch (Exception e) {
            String str = TAG;
            Log.wtf(str, "Exception occured: " + e.getMessage());
        }
        return store;
    }

    @VisibleForTesting
    protected NetworkStackClient getNetworkStackClient() {
        return NetworkStackClient.getInstance();
    }

    public static IpMemoryStore getMemoryStore(Context context) {
        return new IpMemoryStore(context);
    }
}
