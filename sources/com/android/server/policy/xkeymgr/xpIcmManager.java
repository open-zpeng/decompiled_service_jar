package com.android.server.policy.xkeymgr;

import android.car.ICar;
import android.car.hardware.XpVehicle.IXpVehicle;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
/* loaded from: classes.dex */
public class xpIcmManager {
    private static final String CAR_SERVICE_INTERFACE_NAME = "android.car.ICar";
    private static final String CAR_SERVICE_PACKAGE = "com.android.car";
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_DISCONNECTED = 0;
    private static final String TAG = "xpIcmManager";
    private static final String XP_VEHICLE_SERVICE = "xp_vehicle";
    private ICar mCarService;
    private final ServiceConnection mCarServiceConnectionListener = new ServiceConnection() { // from class: com.android.server.policy.xkeymgr.xpIcmManager.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            xpIcmManager.this.mCarService = ICar.Stub.asInterface(service);
            try {
                IBinder binder = xpIcmManager.this.mCarService.getCarService(xpIcmManager.XP_VEHICLE_SERVICE);
                if (binder != null) {
                    xpIcmManager.this.mXpVehicle = IXpVehicle.Stub.asInterface(binder);
                }
            } catch (RemoteException e) {
                Log.e(xpIcmManager.TAG, e.toString());
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            xpIcmManager.this.mCarService = null;
        }
    };
    private Context mContext;
    private IXpVehicle mXpVehicle;

    public xpIcmManager(Context context) {
        this.mContext = context;
        if (this.mCarService == null) {
            Intent intent = new Intent();
            intent.setPackage(CAR_SERVICE_PACKAGE);
            intent.setAction(CAR_SERVICE_INTERFACE_NAME);
            context.bindService(intent, this.mCarServiceConnectionListener, 1);
        }
    }

    public IXpVehicle getXpVehicle() {
        return this.mXpVehicle;
    }

    public void setIcmWheelkey(int key) throws RemoteException {
        if (this.mXpVehicle != null) {
            this.mXpVehicle.setIcmWheelkey(key);
        }
    }

    public void setIcmSyncSignal(String signal) throws RemoteException {
        if (this.mXpVehicle != null) {
            this.mXpVehicle.setIcmSyncSignal(signal);
        }
    }
}
