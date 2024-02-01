package com.android.server.location;

import android.content.Context;
import android.hardware.location.ActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareClient;
import android.hardware.location.IActivityRecognitionHardwareWatcher;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.server.ServiceWatcher;
/* loaded from: classes.dex */
public class ActivityRecognitionProxy {
    private static final String TAG = "ActivityRecognitionProxy";
    private final ActivityRecognitionHardware mInstance;
    private final boolean mIsSupported;
    private final ServiceWatcher mServiceWatcher;

    private ActivityRecognitionProxy(Context context, Handler handler, boolean activityRecognitionHardwareIsSupported, ActivityRecognitionHardware activityRecognitionHardware, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNameResId) {
        this.mIsSupported = activityRecognitionHardwareIsSupported;
        this.mInstance = activityRecognitionHardware;
        Runnable newServiceWork = new Runnable() { // from class: com.android.server.location.ActivityRecognitionProxy.1
            @Override // java.lang.Runnable
            public void run() {
                ActivityRecognitionProxy.this.bindProvider();
            }
        };
        this.mServiceWatcher = new ServiceWatcher(context, TAG, "com.android.location.service.ActivityRecognitionProvider", overlaySwitchResId, defaultServicePackageNameResId, initialPackageNameResId, newServiceWork, handler);
    }

    public static ActivityRecognitionProxy createAndBind(Context context, Handler handler, boolean activityRecognitionHardwareIsSupported, ActivityRecognitionHardware activityRecognitionHardware, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNameResId) {
        ActivityRecognitionProxy activityRecognitionProxy = new ActivityRecognitionProxy(context, handler, activityRecognitionHardwareIsSupported, activityRecognitionHardware, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNameResId);
        if (activityRecognitionProxy.mServiceWatcher.start()) {
            return activityRecognitionProxy;
        }
        Log.e(TAG, "ServiceWatcher could not start.");
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void bindProvider() {
        if (!this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.ActivityRecognitionProxy.2
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                try {
                    String descriptor = binder.getInterfaceDescriptor();
                    if (IActivityRecognitionHardwareWatcher.class.getCanonicalName().equals(descriptor)) {
                        IActivityRecognitionHardwareWatcher watcher = IActivityRecognitionHardwareWatcher.Stub.asInterface(binder);
                        if (watcher != null) {
                            if (ActivityRecognitionProxy.this.mInstance != null) {
                                try {
                                    watcher.onInstanceChanged(ActivityRecognitionProxy.this.mInstance);
                                    return;
                                } catch (RemoteException e) {
                                    Log.e(ActivityRecognitionProxy.TAG, "Error delivering hardware interface to watcher.", e);
                                    return;
                                }
                            }
                            Log.d(ActivityRecognitionProxy.TAG, "AR HW instance not available, binding will be a no-op.");
                            return;
                        }
                        Log.e(ActivityRecognitionProxy.TAG, "No watcher found on connection.");
                    } else if (IActivityRecognitionHardwareClient.class.getCanonicalName().equals(descriptor)) {
                        IActivityRecognitionHardwareClient client = IActivityRecognitionHardwareClient.Stub.asInterface(binder);
                        if (client != null) {
                            try {
                                client.onAvailabilityChanged(ActivityRecognitionProxy.this.mIsSupported, ActivityRecognitionProxy.this.mInstance);
                                return;
                            } catch (RemoteException e2) {
                                Log.e(ActivityRecognitionProxy.TAG, "Error delivering hardware interface to client.", e2);
                                return;
                            }
                        }
                        Log.e(ActivityRecognitionProxy.TAG, "No client found on connection.");
                    } else {
                        Log.e(ActivityRecognitionProxy.TAG, "Invalid descriptor found on connection: " + descriptor);
                    }
                } catch (RemoteException e3) {
                    Log.e(ActivityRecognitionProxy.TAG, "Unable to get interface descriptor.", e3);
                }
            }
        })) {
            Log.e(TAG, "Null binder found on connection.");
        }
    }
}
