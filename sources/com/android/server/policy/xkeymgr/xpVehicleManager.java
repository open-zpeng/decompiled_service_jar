package com.android.server.policy.xkeymgr;

import android.car.ICar;
import android.car.hardware.XpVehicle.IXpVehicle;
import android.car.hardware.eps.IEpsEventListener;
import android.car.hardware.scu.IScuEventListener;
import android.car.hardware.vcu.IVcuEventListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.xiaopeng.util.xpLogger;
import java.lang.ref.WeakReference;
/* loaded from: classes.dex */
public class xpVehicleManager {
    private static final String CAR_SERVICE_INTERFACE_NAME = "android.car.ICar";
    private static final String CAR_SERVICE_PACKAGE = "com.android.car";
    public static final int CRUISE_CONTROL_STATUS_CONTROLLING = 2;
    public static final int CRUISE_CONTROL_STATUS_ERROR = 3;
    public static final int CRUISE_CONTROL_STATUS_OFF = 0;
    public static final int CRUISE_CONTROL_STATUS_ON = 1;
    private static final int GEAR_LEVEL_P = 4;
    public static final int SCU_ACC_ACTIVE = 3;
    public static final int SCU_ACC_BRAKE_ONLY = 4;
    public static final int SCU_ACC_OFF = 0;
    public static final int SCU_ACC_OVERRIDE = 5;
    public static final int SCU_ACC_PASSIVE = 1;
    public static final int SCU_ACC_PERMANENT_FAILURE = 9;
    public static final int SCU_ACC_STANDACTIVE = 6;
    public static final int SCU_ACC_STANDBY = 2;
    public static final int SCU_ACC_STANDWAIT = 7;
    public static final int SCU_ACC_TEMPORARY_FAILURE = 8;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_DISCONNECTED = 0;
    private static final String TAG = "xpVehicleManager";
    private static final String XP_VEHICLE_SERVICE = "xp_vehicle";
    private ICar mCarService;
    private Context mContext;
    private EpsEventListener mEpsEventListener;
    private ScuEventListener mScuEventListener;
    private VcuEventListener mVcuEventListener;
    private IXpVehicle mXpVehicle;
    private int ccStatus = 0;
    private int accStatus = 0;
    private int carGear = 4;
    private float carRawSpeed = 0.0f;
    private float carSteeringAngle = 0.0f;
    private float carSteeringAngleSpeed = 0.0f;
    private final ServiceConnection mCarServiceConnectionListener = new ServiceConnection() { // from class: com.android.server.policy.xkeymgr.xpVehicleManager.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            xpVehicleManager.this.mCarService = ICar.Stub.asInterface(service);
            try {
                IBinder binder = xpVehicleManager.this.mCarService.getCarService(xpVehicleManager.XP_VEHICLE_SERVICE);
                if (binder != null) {
                    xpVehicleManager.this.mXpVehicle = IXpVehicle.Stub.asInterface(binder);
                    xpVehicleManager.this.registerScuListener(xpVehicleManager.this.mScuEventListener);
                    xpVehicleManager.this.registerVcuListener(xpVehicleManager.this.mVcuEventListener);
                    xpVehicleManager.this.registerEpsListener(xpVehicleManager.this.mEpsEventListener);
                }
            } catch (RemoteException e) {
                Log.e(xpVehicleManager.TAG, e.toString());
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            xpVehicleManager.this.mCarService = null;
        }
    };

    public xpVehicleManager(Context context) {
        this.mScuEventListener = null;
        this.mVcuEventListener = null;
        this.mEpsEventListener = null;
        this.mContext = context;
        this.mScuEventListener = new ScuEventListener(this);
        this.mVcuEventListener = new VcuEventListener(this);
        this.mEpsEventListener = new EpsEventListener(this);
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

    public boolean isInAccOrCcStatus() {
        return isInAccStatus() || isInCcStatus();
    }

    public boolean isInAccStatus() {
        xpLogger.i(TAG, "isInAccStatus(): accStatus=" + this.accStatus);
        if (this.accStatus >= 3 && this.accStatus <= 7) {
            return true;
        }
        return false;
    }

    public boolean isInCcStatus() {
        boolean ret = false;
        int ccStatus = getCcStatus();
        switch (ccStatus) {
            case 0:
            case 3:
                ret = false;
                break;
            case 1:
            case 2:
                ret = true;
                break;
        }
        xpLogger.i(TAG, "isInCcStatus(): ccStatus=" + this.accStatus + " ret=" + ret);
        return ret;
    }

    public void registerScuListener(IScuEventListener listener) {
        try {
            if (this.mXpVehicle != null) {
                this.mXpVehicle.registerScuListener(listener);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
    }

    public void unregisterScuListener(IScuEventListener listener) {
        try {
            if (this.mXpVehicle != null) {
                this.mXpVehicle.unregisterScuListener(listener);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setStatus(int acc) {
        this.accStatus = acc;
    }

    private int getCcStatus() {
        return this.ccStatus;
    }

    public void registerVcuListener(IVcuEventListener listener) {
        try {
            if (this.mXpVehicle != null) {
                this.mXpVehicle.registerVcuListener(listener);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
    }

    public void unregisterVcuListener(IVcuEventListener listener) {
        try {
            if (this.mXpVehicle != null) {
                this.mXpVehicle.unregisterVcuListener(listener);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
    }

    public int getCarGear() {
        xpLogger.i(TAG, "getCarGear() carGear:" + this.carGear);
        return this.carGear;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setCarGear(int gear) {
        this.carGear = gear;
    }

    public float getRawSpeed() {
        xpLogger.i(TAG, "getRawSpeed()" + this.carRawSpeed);
        return this.carRawSpeed;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setRawSpeed(float speed) {
        this.carRawSpeed = speed;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setCcStatus(int status) {
        this.ccStatus = status;
    }

    public void registerEpsListener(IEpsEventListener listener) {
        try {
            if (this.mXpVehicle != null) {
                this.mXpVehicle.registerEpsListener(listener);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
    }

    public void unregisterEpsListener(IEpsEventListener listener) {
        try {
            if (this.mXpVehicle != null) {
                this.mXpVehicle.unregisterEpsListener(listener);
            }
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }
    }

    public float getCarSteeringAngle() {
        xpLogger.i(TAG, "getCarSteeringAngle(): " + this.carSteeringAngle);
        return this.carSteeringAngle;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setCarSteeringAngle(float angle) {
        this.carSteeringAngle = angle;
    }

    public float getCarSteeringAngleSpeed() {
        xpLogger.i(TAG, "getCarSteeringAngleSpeed():" + this.carSteeringAngleSpeed);
        return this.carSteeringAngleSpeed;
    }

    public void setCarSteeringAngleSpeed(float angleSpeed) {
        this.carSteeringAngleSpeed = angleSpeed;
    }

    /* loaded from: classes.dex */
    private static class ScuEventListener extends IScuEventListener.Stub {
        private final WeakReference<xpVehicleManager> mManager;

        public ScuEventListener(xpVehicleManager manager) {
            this.mManager = new WeakReference<>(manager);
        }

        public void onAccEvent(int acc) {
            xpVehicleManager manager = this.mManager.get();
            if (manager != null) {
                manager.setStatus(acc);
            }
        }
    }

    /* loaded from: classes.dex */
    private static class VcuEventListener extends IVcuEventListener.Stub {
        private final WeakReference<xpVehicleManager> mManager;

        public VcuEventListener(xpVehicleManager manager) {
            this.mManager = new WeakReference<>(manager);
        }

        public void onVcuGearEvent(int gear) {
            xpVehicleManager manager = this.mManager.get();
            if (manager != null) {
                manager.setCarGear(gear);
            }
        }

        public void onVcuRawCarSpeedEvent(float speed) {
            xpVehicleManager manager = this.mManager.get();
            if (manager != null) {
                manager.setRawSpeed(speed);
            }
        }

        public void onVcuCruiseControlStatusEvent(int status) {
            xpVehicleManager manager = this.mManager.get();
            if (manager != null) {
                xpLogger.i(xpVehicleManager.TAG, "onVcuCruiseControlStatusEvent status=" + status);
                manager.setCcStatus(status);
            }
        }
    }

    /* loaded from: classes.dex */
    private static class EpsEventListener extends IEpsEventListener.Stub {
        private final WeakReference<xpVehicleManager> mManager;

        public EpsEventListener(xpVehicleManager manager) {
            this.mManager = new WeakReference<>(manager);
        }

        public void onEpsSteeringAngleEvent(float angle) {
            xpVehicleManager manager = this.mManager.get();
            if (manager != null) {
                manager.setCarSteeringAngle(angle);
            }
        }

        public void onEpsSteeringAngleSpeedEvent(float angleSpeed) {
            xpVehicleManager manager = this.mManager.get();
            if (manager != null) {
                manager.setCarSteeringAngleSpeed(angleSpeed);
            }
        }
    }
}
