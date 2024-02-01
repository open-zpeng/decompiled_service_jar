package com.android.server.location;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.location.GeofenceHardwareService;
import android.hardware.location.IGeofenceHardware;
import android.location.IFusedGeofenceHardware;
import android.location.IGeofenceProvider;
import android.location.IGpsGeofenceHardware;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import com.android.server.FgThread;
import com.android.server.ServiceWatcher;

/* loaded from: classes.dex */
public final class GeofenceProxy {
    private static final String SERVICE_ACTION = "com.android.location.service.GeofenceProvider";
    private static final String TAG = "GeofenceProxy";
    private final Context mContext;
    private final IFusedGeofenceHardware mFusedGeofenceHardware;
    private final IGpsGeofenceHardware mGpsGeofenceHardware;
    private final ServiceWatcher mServiceWatcher;
    private final ServiceWatcher.BinderRunner mUpdateGeofenceHardware = new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.-$$Lambda$GeofenceProxy$nfSKchjbT2ANT9GbYwyAcTjzBwQ
        @Override // com.android.server.ServiceWatcher.BinderRunner
        public final void run(IBinder iBinder) {
            GeofenceProxy.this.lambda$new$0$GeofenceProxy(iBinder);
        }
    };
    private volatile IGeofenceHardware mGeofenceHardware = null;

    public /* synthetic */ void lambda$new$0$GeofenceProxy(IBinder binder) throws RemoteException {
        IGeofenceProvider provider = IGeofenceProvider.Stub.asInterface(binder);
        try {
            provider.setGeofenceHardware(this.mGeofenceHardware);
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
    }

    public static GeofenceProxy createAndBind(Context context, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, IGpsGeofenceHardware gpsGeofence, IFusedGeofenceHardware fusedGeofenceHardware) {
        GeofenceProxy proxy = new GeofenceProxy(context, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, gpsGeofence, fusedGeofenceHardware);
        if (proxy.bind()) {
            return proxy;
        }
        return null;
    }

    private GeofenceProxy(Context context, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, IGpsGeofenceHardware gpsGeofence, IFusedGeofenceHardware fusedGeofenceHardware) {
        this.mContext = context;
        this.mServiceWatcher = new ServiceWatcher(context, TAG, SERVICE_ACTION, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, FgThread.getHandler()) { // from class: com.android.server.location.GeofenceProxy.1
            @Override // com.android.server.ServiceWatcher
            protected void onBind() {
                runOnBinder(GeofenceProxy.this.mUpdateGeofenceHardware);
            }
        };
        this.mGpsGeofenceHardware = gpsGeofence;
        this.mFusedGeofenceHardware = fusedGeofenceHardware;
    }

    private boolean bind() {
        if (this.mServiceWatcher.start()) {
            Context context = this.mContext;
            context.bindServiceAsUser(new Intent(context, GeofenceHardwareService.class), new GeofenceProxyServiceConnection(), 1, UserHandle.SYSTEM);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class GeofenceProxyServiceConnection implements ServiceConnection {
        private GeofenceProxyServiceConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            IGeofenceHardware geofenceHardware = IGeofenceHardware.Stub.asInterface(service);
            try {
                if (GeofenceProxy.this.mGpsGeofenceHardware != null) {
                    geofenceHardware.setGpsGeofenceHardware(GeofenceProxy.this.mGpsGeofenceHardware);
                }
                if (GeofenceProxy.this.mFusedGeofenceHardware != null) {
                    geofenceHardware.setFusedGeofenceHardware(GeofenceProxy.this.mFusedGeofenceHardware);
                }
                GeofenceProxy.this.mGeofenceHardware = geofenceHardware;
                GeofenceProxy.this.mServiceWatcher.runOnBinder(GeofenceProxy.this.mUpdateGeofenceHardware);
            } catch (Exception e) {
                Log.w(GeofenceProxy.TAG, e);
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            GeofenceProxy.this.mGeofenceHardware = null;
            GeofenceProxy.this.mServiceWatcher.runOnBinder(GeofenceProxy.this.mUpdateGeofenceHardware);
        }
    }
}
