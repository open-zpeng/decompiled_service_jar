package com.android.server.location;

import android.content.Context;
import android.location.GnssMeasurementsEvent;
import android.location.IGnssMeasurementsListener;
import android.os.Handler;
import android.os.IInterface;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.RemoteListenerHelper;

/* loaded from: classes.dex */
public abstract class GnssMeasurementsProvider extends RemoteListenerHelper<IGnssMeasurementsListener> {
    private boolean mEnableFullTracking;
    private boolean mIsCollectionStarted;
    private final GnssMeasurementProviderNative mNative;
    private static final String TAG = "GnssMeasurementsProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    private static native boolean native_is_measurement_supported();

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_start_measurement_collection(boolean z);

    private static native boolean native_stop_measurement_collection();

    static /* synthetic */ boolean access$000() {
        return native_is_measurement_supported();
    }

    static /* synthetic */ boolean access$200() {
        return native_stop_measurement_collection();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public GnssMeasurementsProvider(Context context, Handler handler) {
        this(context, handler, new GnssMeasurementProviderNative());
    }

    @VisibleForTesting
    GnssMeasurementsProvider(Context context, Handler handler, GnssMeasurementProviderNative aNative) {
        super(context, handler, TAG);
        this.mNative = aNative;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        if (this.mIsCollectionStarted) {
            this.mNative.startMeasurementCollection(this.mEnableFullTracking);
        }
    }

    @Override // com.android.server.location.RemoteListenerHelper
    public boolean isAvailableInPlatform() {
        return this.mNative.isMeasurementSupported();
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected int registerWithService() {
        int devOptions = Settings.Secure.getInt(this.mContext.getContentResolver(), "development_settings_enabled", 0);
        int fullTrackingToggled = Settings.Global.getInt(this.mContext.getContentResolver(), "enable_gnss_raw_meas_full_tracking", 0);
        boolean enableFullTracking = devOptions == 1 && fullTrackingToggled == 1;
        boolean result = this.mNative.startMeasurementCollection(enableFullTracking);
        if (result) {
            this.mIsCollectionStarted = true;
            this.mEnableFullTracking = enableFullTracking;
            return 0;
        }
        return 4;
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected void unregisterFromService() {
        boolean stopped = this.mNative.stopMeasurementCollection();
        if (stopped) {
            this.mIsCollectionStarted = false;
        }
    }

    public void onMeasurementsAvailable(final GnssMeasurementsEvent event) {
        foreach(new RemoteListenerHelper.ListenerOperation() { // from class: com.android.server.location.-$$Lambda$GnssMeasurementsProvider$Qlkb-fzzYggD17FlZmrylRJr2vE
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public final void execute(IInterface iInterface, CallerIdentity callerIdentity) {
                GnssMeasurementsProvider.this.lambda$onMeasurementsAvailable$0$GnssMeasurementsProvider(event, (IGnssMeasurementsListener) iInterface, callerIdentity);
            }
        });
    }

    public /* synthetic */ void lambda$onMeasurementsAvailable$0$GnssMeasurementsProvider(GnssMeasurementsEvent event, IGnssMeasurementsListener listener, CallerIdentity callerIdentity) throws RemoteException {
        if (!hasPermission(this.mContext, callerIdentity)) {
            logPermissionDisabledEventNotReported(TAG, callerIdentity.mPackageName, "GNSS measurements");
        } else {
            listener.onGnssMeasurementsReceived(event);
        }
    }

    public void onCapabilitiesUpdated(boolean isGnssMeasurementsSupported) {
        setSupported(isGnssMeasurementsSupported);
        updateResult();
    }

    public void onGpsEnabledChanged() {
        tryUpdateRegistrationWithService();
        updateResult();
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected RemoteListenerHelper.ListenerOperation<IGnssMeasurementsListener> getHandlerOperation(int result) {
        int status;
        switch (result) {
            case 0:
                status = 1;
                break;
            case 1:
            case 2:
            case 4:
                status = 0;
                break;
            case 3:
                status = 2;
                break;
            case 5:
                return null;
            case 6:
                status = 3;
                break;
            default:
                Log.v(TAG, "Unhandled addListener result: " + result);
                return null;
        }
        return new StatusChangedOperation(status);
    }

    /* loaded from: classes.dex */
    private static class StatusChangedOperation implements RemoteListenerHelper.ListenerOperation<IGnssMeasurementsListener> {
        private final int mStatus;

        public StatusChangedOperation(int status) {
            this.mStatus = status;
        }

        @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
        public void execute(IGnssMeasurementsListener listener, CallerIdentity callerIdentity) throws RemoteException {
            listener.onStatusChanged(this.mStatus);
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    static class GnssMeasurementProviderNative {
        GnssMeasurementProviderNative() {
        }

        public boolean isMeasurementSupported() {
            return GnssMeasurementsProvider.access$000();
        }

        public boolean startMeasurementCollection(boolean enableFullTracking) {
            return GnssMeasurementsProvider.native_start_measurement_collection(enableFullTracking);
        }

        public boolean stopMeasurementCollection() {
            return GnssMeasurementsProvider.access$200();
        }
    }
}
