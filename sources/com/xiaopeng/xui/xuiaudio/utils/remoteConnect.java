package com.xiaopeng.xui.xuiaudio.utils;

import android.car.ICar;
import android.car.hardware.XpVehicle.IXpVehicle;
import android.car.media.ICarAudio;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import com.xiaopeng.xuimanager.IXUIService;
import com.xiaopeng.xuimanager.mediacenter.IMediaCenter;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
public class remoteConnect {
    private static final String CAR_AUDIO_SERVICE = "audio";
    private static final String CAR_SERVICE_INTERFACE_NAME = "android.car.ICar";
    private static final String CAR_SERVICE_PACKAGE = "com.android.car";
    private static final String MEDIACENTER_SERVICE = "mediacenter";
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_DISCONNECTED = 0;
    private static final String TAG = "remoteConnect";
    private static final String XP_VEHICLE_SERVICE = "xp_vehicle";
    private static final String XUI_SERVICE_CLASS = "com.xiaopeng.xuiservice.XUIService";
    private static final String XUI_SERVICE_INTERFACE_NAME = "com.xiaopeng.xuimanager.IXUIService";
    private static final String XUI_SERVICE_PACKAGE = "com.xiaopeng.xuiservice";
    private static Context mContext;
    private ICarAudio mCarAudio;
    private ICar mCarService;
    private int mConnectionState;
    private IMediaCenter mMcService;
    private IXUIService mXUIService;
    private static remoteConnect instance = null;
    private static int AudioFeature = 0;
    private static long lastBindXuiServiceTime = 0;
    private static long lastBindCarServiceTime = 0;
    private List<RemoteConnectListerer> mRemoteConnectListerers = new ArrayList();
    private IXpVehicle mXpVehicle = null;
    private xuiAudioPolicy mXuiAudioPolicy = null;
    private final ServiceConnection mCarServiceConnectionListener = new ServiceConnection() { // from class: com.xiaopeng.xui.xuiaudio.utils.remoteConnect.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            remoteConnect.this.mCarService = ICar.Stub.asInterface(service);
            try {
                IBinder binder = remoteConnect.this.mCarService.getCarService(remoteConnect.CAR_AUDIO_SERVICE);
                if (binder != null) {
                    remoteConnect.this.mCarAudio = ICarAudio.Stub.asInterface(binder);
                }
                CarServiceDeathRecipient deathRecipient = new CarServiceDeathRecipient(binder);
                binder.linkToDeath(deathRecipient, 0);
                IBinder binder2 = remoteConnect.this.mCarService.getCarService(remoteConnect.XP_VEHICLE_SERVICE);
                if (binder2 != null) {
                    remoteConnect.this.mXpVehicle = IXpVehicle.Stub.asInterface(binder2);
                }
                remoteConnect.this.callOnCarServiceConnected();
            } catch (Exception e) {
                Log.e(remoteConnect.TAG, e.toString());
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            remoteConnect.this.mCarService = null;
            remoteConnect.this.mCarAudio = null;
            remoteConnect.this.mXpVehicle = null;
        }
    };
    private final ServiceConnection mMediaServiceConnectionListener = new ServiceConnection() { // from class: com.xiaopeng.xui.xuiaudio.utils.remoteConnect.2
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            remoteConnect.this.mXUIService = IXUIService.Stub.asInterface(service);
            remoteConnect.this.mConnectionState = 2;
            IBinder binder = null;
            Log.d(remoteConnect.TAG, "xuiservice onServiceConnected!!!!");
            try {
                binder = remoteConnect.this.mXUIService.getXUIService(remoteConnect.MEDIACENTER_SERVICE);
            } catch (RemoteException e) {
                remoteConnect.handleException(e);
            }
            if (binder != null) {
                remoteConnect.this.mMcService = IMediaCenter.Stub.asInterface(binder);
            }
            remoteConnect.this.callOnXuiServiceConnected();
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            remoteConnect.this.mXUIService = null;
            remoteConnect.this.mConnectionState = 0;
        }
    };

    /* loaded from: classes.dex */
    public interface RemoteConnectListerer {
        void onCarServiceConnected();

        void onXuiServiceConnected();
    }

    public remoteConnect(Context context) {
        mContext = context;
        startExternalServices();
    }

    public static remoteConnect getInstance(Context context) {
        if (instance == null) {
            instance = new remoteConnect(context);
        }
        return instance;
    }

    /* loaded from: classes.dex */
    class CarServiceDeathRecipient implements IBinder.DeathRecipient {
        private static final String TAG = "xpAudio.Binder";
        private IBinder mrBinder;

        CarServiceDeathRecipient(IBinder binder) {
            this.mrBinder = binder;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.d(TAG, "binderDied " + this.mrBinder);
            remoteConnect.this.BindCarService();
        }

        void release() {
            this.mrBinder.unlinkToDeath(this, 0);
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.xiaopeng.xui.xuiaudio.utils.remoteConnect$3] */
    private void startExternalServices() {
        new Thread() { // from class: com.xiaopeng.xui.xuiaudio.utils.remoteConnect.3
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                try {
                    Log.d(remoteConnect.TAG, "startExternalServices()");
                    Thread.sleep(5000L);
                    if (remoteConnect.this.mCarService == null) {
                        remoteConnect.this.BindCarService();
                    }
                    Log.d(remoteConnect.TAG, "startExternalServices()  BindXUIService");
                    if (remoteConnect.this.mXUIService == null) {
                        remoteConnect.this.BindXUIService();
                    }
                } catch (Exception e) {
                }
            }
        }.start();
    }

    public void BindCarService() {
        long currentTime = System.currentTimeMillis();
        Log.i(TAG, "BindCarService() " + currentTime);
        if (this.mCarService == null && Math.abs(currentTime - lastBindCarServiceTime) > 5000) {
            Intent intent = new Intent();
            intent.setPackage(CAR_SERVICE_PACKAGE);
            intent.setAction(CAR_SERVICE_INTERFACE_NAME);
            mContext.bindService(intent, this.mCarServiceConnectionListener, 1);
            lastBindCarServiceTime = currentTime;
        }
    }

    public void BindXUIService() {
        long currentTime = System.currentTimeMillis();
        Log.i(TAG, "BindXUIService() " + currentTime);
        if (Math.abs(currentTime - lastBindXuiServiceTime) > 5000) {
            Intent intent = new Intent();
            intent.setPackage(XUI_SERVICE_PACKAGE);
            intent.setAction(XUI_SERVICE_INTERFACE_NAME);
            mContext.bindService(intent, this.mMediaServiceConnectionListener, 1);
            lastBindXuiServiceTime = currentTime;
        }
    }

    public IXpVehicle getXpVehicle() {
        if (this.mXpVehicle == null) {
            BindCarService();
        }
        return this.mXpVehicle;
    }

    public ICarAudio getCarAudio() {
        if (this.mCarAudio == null) {
            BindCarService();
        }
        return this.mCarAudio;
    }

    public IMediaCenter getMediaCenter() {
        if (this.mMcService == null) {
            BindXUIService();
        }
        return this.mMcService;
    }

    public void registerListener(RemoteConnectListerer listener) {
        if (!this.mRemoteConnectListerers.contains(listener)) {
            this.mRemoteConnectListerers.add(listener);
        }
    }

    public void callOnCarServiceConnected() {
        for (int i = 0; i < this.mRemoteConnectListerers.size(); i++) {
            RemoteConnectListerer temp = this.mRemoteConnectListerers.get(i);
            if (temp != null) {
                temp.onCarServiceConnected();
            }
        }
    }

    public void callOnXuiServiceConnected() {
        for (int i = 0; i < this.mRemoteConnectListerers.size(); i++) {
            RemoteConnectListerer temp = this.mRemoteConnectListerers.get(i);
            if (temp != null) {
                temp.onXuiServiceConnected();
            }
        }
    }

    public boolean isXuiConnected() {
        return this.mConnectionState == 2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void handleException(Exception e) {
        Log.d(TAG, "handleException " + e);
    }
}
