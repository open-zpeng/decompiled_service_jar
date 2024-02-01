package com.xiaopeng.server.ext;

import android.annotation.SuppressLint;
import android.car.ICar;
import android.car.hardware.XpVehicle.IXpVehicle;
import android.car.hardware.eps.IEpsEventListener;
import android.car.hardware.scu.IScuEventListener;
import android.car.hardware.vcu.IVcuEventListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.xuimanager.IXUIService;
import com.xiaopeng.xuimanager.xapp.IXApp;
import java.util.ArrayList;
import java.util.Iterator;
/* loaded from: classes.dex */
public class ExternalManagerService {
    public static final int TYPE_CAR = 1;
    public static final int TYPE_XUI = 2;
    private CarExternalService mCarService;
    private Context mContext;
    private final WorkHandler mHandler;
    private XuiExternalService mXuiService;
    private static final String TAG = "ExternalManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static ExternalManagerService sService = null;
    private final ArrayList<OnEventListener> mListeners = new ArrayList<>();
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);

    /* loaded from: classes.dex */
    public static final class EventId {
        public static final int ID_CAR_EPS_STEERING_ANGLE = 103001;
        public static final int ID_CAR_EPS_STEERING_ANGLE_SPEED = 103002;
        public static final int ID_CAR_SCU_ACC = 101001;
        public static final int ID_CAR_VCU_GEAR = 102002;
        public static final int ID_CAR_VCU_LCC = 102001;
        public static final int ID_CAR_VCU_RAW_SPEED = 102003;
        public static final int ID_XUI_APP_MODE = 201001;
    }

    /* loaded from: classes.dex */
    public interface OnEventListener {
        void onEventChanged(int i, Object obj);

        void onEventReady();
    }

    public ExternalManagerService(Context context) {
        this.mContext = null;
        this.mContext = context;
        this.mHandlerThread.start();
        this.mHandler = new WorkHandler(this.mHandlerThread.getLooper());
        this.mCarService = new CarExternalService(this.mContext);
        this.mXuiService = new XuiExternalService(this.mContext);
    }

    public static ExternalManagerService get(Context context) {
        if (sService == null) {
            synchronized (ExternalManagerService.class) {
                if (sService == null) {
                    sService = new ExternalManagerService(context);
                }
            }
        }
        return sService;
    }

    public void init() {
        this.mCarService.init();
        this.mXuiService.init();
    }

    public void systemReady() {
    }

    public Object getValue(int type, Object params, Object... value) {
        switch (type) {
            case 1:
                return this.mCarService.getValue(type, params, value);
            case 2:
                return this.mXuiService.getValue(type, params, value);
            default:
                return null;
        }
    }

    public void setValue(int type, Object params, Object... value) {
        switch (type) {
            case 1:
                this.mCarService.setValue(type, params, value);
                return;
            case 2:
                this.mXuiService.setValue(type, params, value);
                return;
            default:
                return;
        }
    }

    public void addListener(OnEventListener listener) {
        synchronized (this.mListeners) {
            this.mListeners.add(listener);
        }
    }

    public void removeListener(OnEventListener listener) {
        synchronized (this.mListeners) {
            this.mListeners.remove(listener);
        }
    }

    public static Object getArraysValue(int index, Object... value) {
        if (value != null && index >= 0 && index < value.length) {
            return value[index];
        }
        return null;
    }

    /* loaded from: classes.dex */
    private final class CarExternalService extends AbsExternalService {
        private static final String SERVICE_ACTION = "android.car.ICar";
        private static final String SERVICE_PACKAGE = "com.android.car";
        private static final String SERVICE_VEHICLE = "xp_vehicle";
        private IEpsEventListener mEpsListener;
        private IScuEventListener mScuListener;
        private ICar mService;
        private IVcuEventListener mVcuListener;
        private IXpVehicle mVehicle;

        public CarExternalService(Context context) {
            super(context);
            this.mScuListener = null;
            this.mVcuListener = null;
            this.mEpsListener = null;
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void init() {
            connect();
            initListener();
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void bind() {
            try {
                Intent intent = new Intent();
                intent.setPackage(SERVICE_PACKAGE);
                intent.setAction(SERVICE_ACTION);
                this.mContext.bindService(intent, this.mConnection, 1);
            } catch (Exception e) {
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void connect() {
            if (!isReady()) {
                bind();
                this.mHandler.postDelayed(new Runnable() { // from class: com.xiaopeng.server.ext.ExternalManagerService.CarExternalService.1
                    @Override // java.lang.Runnable
                    public void run() {
                        CarExternalService.this.connect();
                    }
                }, 2000L);
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void onConnected(ComponentName name, IBinder service) {
            this.mService = ICar.Stub.asInterface(service);
            try {
                IBinder binder = this.mService.getCarService(SERVICE_VEHICLE);
                if (binder != null) {
                    this.mVehicle = IXpVehicle.Stub.asInterface(binder);
                    registerListener();
                    if (ExternalManagerService.this.mListeners != null) {
                        Iterator it = ExternalManagerService.this.mListeners.iterator();
                        while (it.hasNext()) {
                            OnEventListener listener = (OnEventListener) it.next();
                            if (listener != null) {
                                listener.onEventReady();
                            }
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e(ExternalManagerService.TAG, e.toString());
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void onDisconnected(ComponentName name) {
            this.mVehicle = null;
            this.mService = null;
            connect();
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public boolean isReady() {
            return (this.mVehicle == null || this.mService == null) ? false : true;
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public Object getValue(int type, Object params, Object... value) {
            if (!isReady()) {
                connect();
                return null;
            }
            String var = params != null ? params.toString() : null;
            if (TextUtils.isEmpty(var)) {
                return null;
            }
            if (var.equals("isReady")) {
                return Boolean.valueOf(isReady());
            }
            if (var.equals("getAccStatus")) {
                return Integer.valueOf(this.mVehicle.getAccStatus());
            }
            if (var.equals("getVcuGearState")) {
                return Integer.valueOf(this.mVehicle.getVcuGearState());
            }
            if (var.equals("getVcuRawCarSpeed")) {
                return Float.valueOf(this.mVehicle.getVcuRawCarSpeed());
            }
            if (var.equals("getVcuCruiseControlStatus")) {
                return Integer.valueOf(this.mVehicle.getVcuCruiseControlStatus());
            }
            if (var.equals("getEpsSteeringAngle")) {
                return Float.valueOf(this.mVehicle.getEpsSteeringAngle());
            }
            if (var.equals("getEpsSteeringAngleSpeed")) {
                return Float.valueOf(this.mVehicle.getEpsSteeringAngleSpeed());
            }
            return null;
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void setValue(int type, Object params, Object... value) {
            if (!isReady()) {
                connect();
                return;
            }
            String var = params != null ? params.toString() : null;
            if (TextUtils.isEmpty(var)) {
                return;
            }
            try {
                if (var.equals("setIcmWheelkey")) {
                    int key = xpTextUtils.toInteger(ExternalManagerService.getArraysValue(0, value), -1).intValue();
                    if (key != -1) {
                        this.mVehicle.setIcmWheelkey(key);
                    }
                } else if (var.equals("setIcmSyncSignal")) {
                    String signal = xpTextUtils.toString(ExternalManagerService.getArraysValue(0, value));
                    if (!TextUtils.isEmpty(signal)) {
                        this.mVehicle.setIcmSyncSignal(signal);
                    }
                }
            } catch (Exception e) {
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void onEventChanged(int var, Object value) {
            if (ExternalManagerService.this.mListeners != null) {
                Iterator it = ExternalManagerService.this.mListeners.iterator();
                while (it.hasNext()) {
                    OnEventListener listener = (OnEventListener) it.next();
                    if (listener != null) {
                        listener.onEventChanged(var, value);
                    }
                }
            }
        }

        private void initListener() {
            this.mScuListener = new IScuEventListener.Stub() { // from class: com.xiaopeng.server.ext.ExternalManagerService.CarExternalService.2
                public void onAccEvent(int i) throws RemoteException {
                    CarExternalService.this.onEventChanged(EventId.ID_CAR_SCU_ACC, Integer.valueOf(i));
                }
            };
            this.mVcuListener = new IVcuEventListener.Stub() { // from class: com.xiaopeng.server.ext.ExternalManagerService.CarExternalService.3
                public void onVcuGearEvent(int i) throws RemoteException {
                    CarExternalService.this.onEventChanged(EventId.ID_CAR_VCU_GEAR, Integer.valueOf(i));
                }

                public void onVcuRawCarSpeedEvent(float v) throws RemoteException {
                    CarExternalService.this.onEventChanged(EventId.ID_CAR_VCU_RAW_SPEED, Float.valueOf(v));
                }

                public void onVcuCruiseControlStatusEvent(int i) throws RemoteException {
                    CarExternalService.this.onEventChanged(EventId.ID_CAR_VCU_LCC, Integer.valueOf(i));
                }

                public void onVcuChargeStatusEvent(int status) {
                }
            };
            this.mEpsListener = new IEpsEventListener.Stub() { // from class: com.xiaopeng.server.ext.ExternalManagerService.CarExternalService.4
                public void onEpsSteeringAngleEvent(float v) throws RemoteException {
                    CarExternalService.this.onEventChanged(EventId.ID_CAR_EPS_STEERING_ANGLE, Float.valueOf(v));
                }

                public void onEpsSteeringAngleSpeedEvent(float v) throws RemoteException {
                    CarExternalService.this.onEventChanged(EventId.ID_CAR_EPS_STEERING_ANGLE_SPEED, Float.valueOf(v));
                }
            };
        }

        private void registerListener() {
            if (this.mVehicle != null) {
                try {
                    this.mVehicle.registerScuListener(this.mScuListener);
                    this.mVehicle.registerVcuListener(this.mVcuListener);
                    this.mVehicle.registerEpsListener(this.mEpsListener);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        private void unregisterListener() {
            if (this.mVehicle != null) {
                try {
                    this.mVehicle.unregisterScuListener(this.mScuListener);
                    this.mVehicle.unregisterVcuListener(this.mVcuListener);
                    this.mVehicle.unregisterEpsListener(this.mEpsListener);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private final class XuiExternalService extends AbsExternalService {
        private static final String SERVICE_ACTION = "com.xiaopeng.xuimanager.IXUIService";
        private static final String SERVICE_APP = "xapp";
        private static final String SERVICE_PACKAGE = "com.xiaopeng.xuiservice";
        private IXApp mApp;
        private IXUIService mService;

        public XuiExternalService(Context context) {
            super(context);
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void init() {
            connect();
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void bind() {
            try {
                Intent intent = new Intent();
                intent.setPackage(SERVICE_PACKAGE);
                intent.setAction(SERVICE_ACTION);
                this.mContext.bindService(intent, this.mConnection, 1);
            } catch (Exception e) {
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void connect() {
            if (!isReady()) {
                bind();
                this.mHandler.postDelayed(new Runnable() { // from class: com.xiaopeng.server.ext.ExternalManagerService.XuiExternalService.1
                    @Override // java.lang.Runnable
                    public void run() {
                        XuiExternalService.this.connect();
                    }
                }, 2000L);
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void onConnected(ComponentName name, IBinder service) {
            this.mService = IXUIService.Stub.asInterface(service);
            try {
                IBinder binder = this.mService.getXUIService(SERVICE_APP);
                if (binder != null) {
                    this.mApp = IXApp.Stub.asInterface(binder);
                }
            } catch (RemoteException e) {
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void onDisconnected(ComponentName name) {
            this.mApp = null;
            this.mService = null;
            connect();
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public boolean isReady() {
            return (this.mApp == null || this.mService == null) ? false : true;
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public Object getValue(int type, Object params, Object... value) {
            if (!isReady()) {
                connect();
                return null;
            }
            String var = params != null ? params.toString() : null;
            if (TextUtils.isEmpty(var)) {
                return null;
            }
            if (var.equals("isReady")) {
                return Boolean.valueOf(isReady());
            }
            if (var.equals("getCarGearLevel")) {
                return Integer.valueOf(this.mApp.getCarGearLevel());
            }
            if (var.equals("checkAppStart")) {
                String packageName = xpTextUtils.toString(ExternalManagerService.getArraysValue(0, value));
                return Integer.valueOf(this.mApp.checkAppStart(packageName));
            }
            return null;
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void setValue(int type, Object params, Object... value) {
            if (!isReady()) {
                connect();
                return;
            }
            String var = params != null ? params.toString() : null;
            if (TextUtils.isEmpty(var)) {
                return;
            }
            try {
                if (var.equals("onAppModeChanged")) {
                    String packageName = xpTextUtils.toString(ExternalManagerService.getArraysValue(0, value));
                    Object infoObject = ExternalManagerService.getArraysValue(1, value);
                    if (infoObject == null) {
                        this.mApp.onAppModeChanged(packageName, (xpPackageInfo) null);
                    } else {
                        this.mApp.onAppModeChanged(packageName, (xpPackageInfo) infoObject);
                    }
                }
            } catch (Exception e) {
            }
        }

        @Override // com.xiaopeng.server.ext.AbsExternalService
        public void onEventChanged(int var, Object value) {
        }
    }

    /* loaded from: classes.dex */
    private final class WorkHandler extends Handler {
        @SuppressLint({"NewApi"})
        public WorkHandler(Looper looper) {
            super(looper, null);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
        }
    }
}
