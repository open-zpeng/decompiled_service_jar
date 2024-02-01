package com.android.server;

import android.app.ActivityManager;
import android.car.ICar;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarProperty;
import android.car.hardware.property.ICarPropertyEventListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.storage.DeviceStorageMonitorService;
import java.util.List;
/* loaded from: classes.dex */
public final class XpBatteryService extends SystemService {
    private static final int BATTERY_PLUGGED_NONE = 0;
    private static final String CAR_SERVICE_INTERFACE = "android.car.ICar";
    private static final String TAG = XpBatteryService.class.getSimpleName();
    private ICarProperty mCarProperty;
    private ICarPropertyEventListener mCarPropertyListener;
    private final ServiceConnection mCarServiceConnection;
    private final Context mContext;
    private final Handler mHandler;
    private boolean mIsPowered;
    private final Object mLock;

    public XpBatteryService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mCarServiceConnection = new ServiceConnection() { // from class: com.android.server.XpBatteryService.1
            @Override // android.content.ServiceConnection
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                String str = XpBatteryService.TAG;
                Slog.i(str, "onServiceConnected:" + iBinder);
                ICar iCar = ICar.Stub.asInterface(iBinder);
                if (iCar != null) {
                    try {
                        XpBatteryService.this.mCarProperty = ICarProperty.Stub.asInterface(iCar.getCarService("xp_vcu"));
                        XpBatteryService.this.mCarProperty.registerListener(557847081, 0.0f, XpBatteryService.this.mCarPropertyListener);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override // android.content.ServiceConnection
            public void onServiceDisconnected(ComponentName componentName) {
                XpBatteryService.this.mCarProperty = null;
            }
        };
        this.mContext = context;
        this.mHandler = new Handler(true);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishLocalService(BatteryManagerInternal.class, new LocalService());
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 550) {
            this.mCarPropertyListener = new ICarPropertyEventListener.Stub() { // from class: com.android.server.XpBatteryService.2
                public void onEvent(List<CarPropertyEvent> events) {
                    for (CarPropertyEvent event : events) {
                        if (event != null && event.getEventType() == 0) {
                            CarPropertyValue value = event.getCarPropertyValue();
                            if (value.getPropertyId() == 557847081) {
                                XpBatteryService.this.handlePowerChanged(((Integer) value.getValue()).intValue());
                            }
                        }
                    }
                }
            };
            Intent intent = new Intent();
            intent.setPackage("com.android.car");
            intent.setAction(CAR_SERVICE_INTERFACE);
            if (!getContext().bindServiceAsUser(intent, this.mCarServiceConnection, 1, UserHandle.SYSTEM)) {
                Slog.wtf(TAG, "cannot start car service");
            }
            sendBatteryChangedIntentLocked();
        }
    }

    private void sendBatteryChangedIntentLocked() {
        final Intent intent = new Intent("android.intent.action.BATTERY_CHANGED");
        intent.addFlags(1610612736);
        intent.putExtra(DeviceStorageMonitorService.EXTRA_SEQUENCE, 1);
        intent.putExtra("status", 1);
        intent.putExtra("health", 1);
        intent.putExtra("present", false);
        intent.putExtra("level", 100);
        intent.putExtra("battery_low", false);
        intent.putExtra("scale", 100);
        intent.putExtra("icon-small", 17303460);
        intent.putExtra("plugged", 1);
        intent.putExtra("voltage", 0);
        intent.putExtra("temperature", 0);
        intent.putExtra("technology", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        intent.putExtra("invalid_charger", 0);
        intent.putExtra("max_charging_current", 0);
        intent.putExtra("max_charging_voltage", 0);
        intent.putExtra("charge_counter", 0);
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$XpBatteryService$4weY02GhuZEkdQLNmT3Tebnn-iQ
            @Override // java.lang.Runnable
            public final void run() {
                ActivityManager.broadcastStickyIntent(intent, -1);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePowerChanged(int chargeStatus) {
        String action;
        boolean isPowered = chargeStatus == 2;
        synchronized (this.mLock) {
            if (this.mIsPowered ^ isPowered) {
                this.mIsPowered = isPowered;
                action = this.mIsPowered ? "android.os.action.CHARGING" : "android.os.action.DISCHARGING";
            } else {
                action = null;
            }
        }
        if (!TextUtils.isEmpty(action)) {
            final Intent intent = new Intent(action);
            intent.addFlags(67108864);
            this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$XpBatteryService$FNdJv358ttTODS58IpZ6XVhHEjU
                @Override // java.lang.Runnable
                public final void run() {
                    XpBatteryService.this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                }
            });
        }
    }

    /* loaded from: classes.dex */
    private final class LocalService extends BatteryManagerInternal {
        private LocalService() {
        }

        public boolean isPowered(int plugTypeSet) {
            boolean z;
            synchronized (XpBatteryService.this.mLock) {
                z = XpBatteryService.this.mIsPowered;
            }
            return z;
        }

        public int getPlugType() {
            synchronized (XpBatteryService.this.mLock) {
                if (XpBatteryService.this.mIsPowered) {
                    return 1;
                }
                return 0;
            }
        }

        public int getBatteryLevel() {
            return 100;
        }

        public int getBatteryChargeCounter() {
            return 0;
        }

        public int getBatteryFullCharge() {
            return 0;
        }

        public boolean getBatteryLevelLow() {
            return false;
        }

        public int getInvalidCharger() {
            return 0;
        }
    }
}
