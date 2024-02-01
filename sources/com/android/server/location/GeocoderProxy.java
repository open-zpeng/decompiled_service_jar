package com.android.server.location;

import android.content.Context;
import android.location.Address;
import android.location.GeocoderParams;
import android.location.IGeocodeProvider;
import android.os.IBinder;
import android.os.RemoteException;
import com.android.server.FgThread;
import com.android.server.ServiceWatcher;
import java.util.List;

/* loaded from: classes.dex */
public class GeocoderProxy {
    private static final String SERVICE_ACTION = "com.android.location.service.GeocodeProvider";
    private static final String TAG = "GeocoderProxy";
    private final ServiceWatcher mServiceWatcher;

    public static GeocoderProxy createAndBind(Context context, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId) {
        GeocoderProxy proxy = new GeocoderProxy(context, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId);
        if (proxy.bind()) {
            return proxy;
        }
        return null;
    }

    private GeocoderProxy(Context context, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId) {
        this.mServiceWatcher = new ServiceWatcher(context, TAG, SERVICE_ACTION, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, FgThread.getHandler());
    }

    private boolean bind() {
        return this.mServiceWatcher.start();
    }

    public String getConnectedPackageName() {
        return this.mServiceWatcher.getCurrentPackageName();
    }

    public String getFromLocation(final double latitude, final double longitude, final int maxResults, final GeocoderParams params, final List<Address> addrs) {
        return (String) this.mServiceWatcher.runOnBinderBlocking(new ServiceWatcher.BlockingBinderRunner() { // from class: com.android.server.location.-$$Lambda$GeocoderProxy$jfLn3HL2BzwsKdoI6ZZeFfEe10k
            @Override // com.android.server.ServiceWatcher.BlockingBinderRunner
            public final Object run(IBinder iBinder) {
                return GeocoderProxy.lambda$getFromLocation$0(latitude, longitude, maxResults, params, addrs, iBinder);
            }
        }, "Service not Available");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ String lambda$getFromLocation$0(double latitude, double longitude, int maxResults, GeocoderParams params, List addrs, IBinder binder) throws RemoteException {
        IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
        return provider.getFromLocation(latitude, longitude, maxResults, params, addrs);
    }

    public String getFromLocationName(final String locationName, final double lowerLeftLatitude, final double lowerLeftLongitude, final double upperRightLatitude, final double upperRightLongitude, final int maxResults, final GeocoderParams params, final List<Address> addrs) {
        return (String) this.mServiceWatcher.runOnBinderBlocking(new ServiceWatcher.BlockingBinderRunner() { // from class: com.android.server.location.-$$Lambda$GeocoderProxy$l4GRjTzjcqxZJILrVLX5qayXBE0
            @Override // com.android.server.ServiceWatcher.BlockingBinderRunner
            public final Object run(IBinder iBinder) {
                return GeocoderProxy.lambda$getFromLocationName$1(locationName, lowerLeftLatitude, lowerLeftLongitude, upperRightLatitude, upperRightLongitude, maxResults, params, addrs, iBinder);
            }
        }, "Service not Available");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ String lambda$getFromLocationName$1(String locationName, double lowerLeftLatitude, double lowerLeftLongitude, double upperRightLatitude, double upperRightLongitude, int maxResults, GeocoderParams params, List addrs, IBinder binder) throws RemoteException {
        IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
        return provider.getFromLocationName(locationName, lowerLeftLatitude, lowerLeftLongitude, upperRightLatitude, upperRightLongitude, maxResults, params, addrs);
    }
}
