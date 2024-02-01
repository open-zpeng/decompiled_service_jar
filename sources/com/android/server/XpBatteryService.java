package com.android.server;

import android.app.ActivityManager;
import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.vcu.CarVcuManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import java.util.Collections;

/* loaded from: classes.dex */
public final class XpBatteryService extends SystemService {
    private static final int BATTERY_PLUGGED_NONE = 0;
    private static final String TAG = XpBatteryService.class.getSimpleName();
    private Car mCar;
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
                Slog.i(XpBatteryService.TAG, "onServiceConnected, name: " + componentName + ", service: " + iBinder);
                CarVcuManager carVcuManager = (CarVcuManager) XpBatteryService.this.mCar.getCarManager("xp_vcu");
                int chargeStatus = 4;
                if (carVcuManager == null) {
                    Slog.i(XpBatteryService.TAG, "onServiceConnected, carVcuManager is null");
                    return;
                }
                try {
                    chargeStatus = carVcuManager.getChargeStatus();
                } catch (Exception e) {
                }
                int i = 1;
                int i2 = 2;
                boolean isPowered = chargeStatus == 2;
                try {
                    XpBatteryService xpBatteryService = XpBatteryService.this;
                    if (!isPowered) {
                        i2 = 4;
                    }
                    if (!isPowered) {
                        i = 0;
                    }
                    xpBatteryService.sendBatteryChangedIntentLocked(i2, i);
                    carVcuManager.registerPropCallback(Collections.singletonList(557847081), new CarVcuManager.CarVcuEventCallback() { // from class: com.android.server.XpBatteryService.1.1
                        public void onChangeEvent(CarPropertyValue carPropertyValue) {
                            int id = carPropertyValue.getPropertyId();
                            if (id == 557847081) {
                                int value = ((Integer) carPropertyValue.getValue()).intValue();
                                XpBatteryService.this.handlePowerChanged(value);
                            }
                        }

                        public void onErrorEvent(int propertyId, int errorCode) {
                        }
                    });
                } catch (Exception e2) {
                    Slog.i(XpBatteryService.TAG, "exception: " + e2.getMessage());
                }
            }

            @Override // android.content.ServiceConnection
            public void onServiceDisconnected(ComponentName componentName) {
                String str = XpBatteryService.TAG;
                Slog.i(str, "onServiceDisconnected, name: " + componentName);
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
            this.mCar = Car.createCar(getContext(), this.mCarServiceConnection);
            this.mCar.connect();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBatteryChangedIntentLocked(int batteryStatus, int plugType) {
        final Intent intent = new Intent("android.intent.action.BATTERY_CHANGED");
        intent.addFlags(1610612736);
        intent.putExtra("status", batteryStatus);
        intent.putExtra("health", 2);
        intent.putExtra("present", true);
        intent.putExtra("level", 100);
        intent.putExtra("scale", 100);
        intent.putExtra("plugged", plugType);
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
        int i = 1;
        int i2 = 2;
        boolean isPowered = chargeStatus == 2;
        synchronized (this.mLock) {
            if (this.mIsPowered ^ isPowered) {
                this.mIsPowered = isPowered;
                action = this.mIsPowered ? "android.os.action.CHARGING" : "android.os.action.DISCHARGING";
                if (!isPowered) {
                    i2 = 4;
                }
                if (!isPowered) {
                    i = 0;
                }
                sendBatteryChangedIntentLocked(i2, i);
            } else {
                action = null;
            }
        }
        if (!TextUtils.isEmpty(action)) {
            final Intent intent = new Intent(action);
            intent.addFlags(67108864);
            this.mHandler.post(new Runnable() { // from class: com.android.server.XpBatteryService.2
                @Override // java.lang.Runnable
                public void run() {
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
