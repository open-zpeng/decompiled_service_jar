package com.android.server.location;

import android.location.GnssNavigationMessage;
import android.location.IGnssNavigationMessageListener;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.RemoteListenerHelper;
/* loaded from: classes.dex */
public abstract class GnssNavigationMessageProvider extends RemoteListenerHelper<IGnssNavigationMessageListener> {
    private boolean mCollectionStarted;
    private final GnssNavigationMessageProviderNative mNative;
    private static final String TAG = "GnssNavigationMessageProvider";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    private static native boolean native_is_navigation_message_supported();

    private static native boolean native_start_navigation_message_collection();

    private static native boolean native_stop_navigation_message_collection();

    static /* synthetic */ boolean access$000() {
        return native_is_navigation_message_supported();
    }

    static /* synthetic */ boolean access$100() {
        return native_start_navigation_message_collection();
    }

    static /* synthetic */ boolean access$200() {
        return native_stop_navigation_message_collection();
    }

    @Override // com.android.server.location.RemoteListenerHelper
    public /* bridge */ /* synthetic */ boolean isRegistered() {
        return super.isRegistered();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public GnssNavigationMessageProvider(Handler handler) {
        this(handler, new GnssNavigationMessageProviderNative());
    }

    @VisibleForTesting
    GnssNavigationMessageProvider(Handler handler, GnssNavigationMessageProviderNative aNative) {
        super(handler, TAG);
        this.mNative = aNative;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resumeIfStarted() {
        if (DEBUG) {
            Log.d(TAG, "resumeIfStarted");
        }
        if (this.mCollectionStarted) {
            this.mNative.startNavigationMessageCollection();
        }
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected boolean isAvailableInPlatform() {
        return this.mNative.isNavigationMessageSupported();
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected int registerWithService() {
        boolean result = this.mNative.startNavigationMessageCollection();
        if (result) {
            this.mCollectionStarted = true;
            return 0;
        }
        return 4;
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected void unregisterFromService() {
        boolean stopped = this.mNative.stopNavigationMessageCollection();
        if (stopped) {
            this.mCollectionStarted = false;
        }
    }

    public void onNavigationMessageAvailable(final GnssNavigationMessage event) {
        RemoteListenerHelper.ListenerOperation<IGnssNavigationMessageListener> operation = new RemoteListenerHelper.ListenerOperation<IGnssNavigationMessageListener>() { // from class: com.android.server.location.GnssNavigationMessageProvider.1
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public void execute(IGnssNavigationMessageListener listener) throws RemoteException {
                listener.onGnssNavigationMessageReceived(event);
            }
        };
        foreach(operation);
    }

    public void onCapabilitiesUpdated(boolean isGnssNavigationMessageSupported) {
        setSupported(isGnssNavigationMessageSupported);
        updateResult();
    }

    public void onGpsEnabledChanged() {
        tryUpdateRegistrationWithService();
        updateResult();
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected RemoteListenerHelper.ListenerOperation<IGnssNavigationMessageListener> getHandlerOperation(int result) {
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
            default:
                Log.v(TAG, "Unhandled addListener result: " + result);
                return null;
        }
        return new StatusChangedOperation(status);
    }

    /* loaded from: classes.dex */
    private static class StatusChangedOperation implements RemoteListenerHelper.ListenerOperation<IGnssNavigationMessageListener> {
        private final int mStatus;

        public StatusChangedOperation(int status) {
            this.mStatus = status;
        }

        @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
        public void execute(IGnssNavigationMessageListener listener) throws RemoteException {
            listener.onStatusChanged(this.mStatus);
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    static class GnssNavigationMessageProviderNative {
        GnssNavigationMessageProviderNative() {
        }

        public boolean isNavigationMessageSupported() {
            return GnssNavigationMessageProvider.access$000();
        }

        public boolean startNavigationMessageCollection() {
            return GnssNavigationMessageProvider.access$100();
        }

        public boolean stopNavigationMessageCollection() {
            return GnssNavigationMessageProvider.access$200();
        }
    }
}
