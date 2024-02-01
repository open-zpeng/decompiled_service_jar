package com.android.server.location;

import android.content.Context;
import android.location.Address;
import android.location.GeocoderParams;
import android.location.IGeocodeProvider;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.server.ServiceWatcher;
import java.util.List;
/* loaded from: classes.dex */
public class GeocoderProxy {
    private static final String SERVICE_ACTION = "com.android.location.service.GeocodeProvider";
    private static final String TAG = "GeocoderProxy";
    private final Context mContext;
    private final ServiceWatcher mServiceWatcher;

    public static GeocoderProxy createAndBind(Context context, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, Handler handler) {
        GeocoderProxy proxy = new GeocoderProxy(context, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, handler);
        if (proxy.bind()) {
            return proxy;
        }
        return null;
    }

    private GeocoderProxy(Context context, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, Handler handler) {
        this.mContext = context;
        this.mServiceWatcher = new ServiceWatcher(this.mContext, TAG, SERVICE_ACTION, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, null, handler);
    }

    private boolean bind() {
        return this.mServiceWatcher.start();
    }

    public String getConnectedPackageName() {
        return this.mServiceWatcher.getBestPackageName();
    }

    public String getFromLocation(final double latitude, final double longitude, final int maxResults, final GeocoderParams params, final List<Address> addrs) {
        final String[] result = {"Service not Available"};
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.GeocoderProxy.1
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
                try {
                    result[0] = provider.getFromLocation(latitude, longitude, maxResults, params, addrs);
                } catch (RemoteException e) {
                    Log.w(GeocoderProxy.TAG, e);
                }
            }
        });
        return result[0];
    }

    public String getFromLocationName(final String locationName, final double lowerLeftLatitude, final double lowerLeftLongitude, final double upperRightLatitude, final double upperRightLongitude, final int maxResults, final GeocoderParams params, final List<Address> addrs) {
        final String[] result = {"Service not Available"};
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.GeocoderProxy.2
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                IGeocodeProvider provider = IGeocodeProvider.Stub.asInterface(binder);
                try {
                    result[0] = provider.getFromLocationName(locationName, lowerLeftLatitude, lowerLeftLongitude, upperRightLatitude, upperRightLongitude, maxResults, params, addrs);
                } catch (RemoteException e) {
                    Log.w(GeocoderProxy.TAG, e);
                }
            }
        });
        return result[0];
    }
}
