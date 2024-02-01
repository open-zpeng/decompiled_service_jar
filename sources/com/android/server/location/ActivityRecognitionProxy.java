package com.android.server.location;

import android.content.Context;
import android.hardware.location.ActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareClient;
import android.hardware.location.IActivityRecognitionHardwareWatcher;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.server.FgThread;
import com.android.server.ServiceWatcher;

/* loaded from: classes.dex */
public class ActivityRecognitionProxy {
    private static final String TAG = "ActivityRecognitionProxy";
    private final ActivityRecognitionHardware mInstance;
    private final boolean mIsSupported;
    private final ServiceWatcher mServiceWatcher;

    public static ActivityRecognitionProxy createAndBind(Context context, boolean activityRecognitionHardwareIsSupported, ActivityRecognitionHardware activityRecognitionHardware, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNameResId) {
        ActivityRecognitionProxy activityRecognitionProxy = new ActivityRecognitionProxy(context, activityRecognitionHardwareIsSupported, activityRecognitionHardware, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNameResId);
        if (activityRecognitionProxy.mServiceWatcher.start()) {
            return activityRecognitionProxy;
        }
        return null;
    }

    private ActivityRecognitionProxy(Context context, boolean activityRecognitionHardwareIsSupported, ActivityRecognitionHardware activityRecognitionHardware, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNameResId) {
        this.mIsSupported = activityRecognitionHardwareIsSupported;
        this.mInstance = activityRecognitionHardware;
        this.mServiceWatcher = new AnonymousClass1(context, TAG, "com.android.location.service.ActivityRecognitionProvider", overlaySwitchResId, defaultServicePackageNameResId, initialPackageNameResId, FgThread.getHandler());
    }

    /* renamed from: com.android.server.location.ActivityRecognitionProxy$1  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass1 extends ServiceWatcher {
        AnonymousClass1(Context context, String logTag, String action, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, Handler handler) {
            super(context, logTag, action, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, handler);
        }

        @Override // com.android.server.ServiceWatcher
        protected void onBind() {
            final ActivityRecognitionProxy activityRecognitionProxy = ActivityRecognitionProxy.this;
            runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.-$$Lambda$ActivityRecognitionProxy$1$d2hvjp-Sk2zwb2N0mtEiubZ0jBE
                @Override // com.android.server.ServiceWatcher.BinderRunner
                public final void run(IBinder iBinder) {
                    ActivityRecognitionProxy.this.initializeService(iBinder);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initializeService(IBinder binder) {
        try {
            String descriptor = binder.getInterfaceDescriptor();
            if (IActivityRecognitionHardwareWatcher.class.getCanonicalName().equals(descriptor)) {
                IActivityRecognitionHardwareWatcher watcher = IActivityRecognitionHardwareWatcher.Stub.asInterface(binder);
                if (this.mInstance != null) {
                    watcher.onInstanceChanged(this.mInstance);
                }
            } else if (IActivityRecognitionHardwareClient.class.getCanonicalName().equals(descriptor)) {
                IActivityRecognitionHardwareClient client = IActivityRecognitionHardwareClient.Stub.asInterface(binder);
                client.onAvailabilityChanged(this.mIsSupported, this.mInstance);
            } else {
                Log.e(TAG, "Invalid descriptor found on connection: " + descriptor);
            }
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
    }
}
