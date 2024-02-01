package com.android.server.location;

import android.content.Context;
import android.location.GnssNavigationMessage;
import android.location.IGnssNavigationMessageListener;
import android.os.Handler;
import android.os.IInterface;
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

    /* JADX INFO: Access modifiers changed from: protected */
    public GnssNavigationMessageProvider(Context context, Handler handler) {
        this(context, handler, new GnssNavigationMessageProviderNative());
    }

    @VisibleForTesting
    GnssNavigationMessageProvider(Context context, Handler handler, GnssNavigationMessageProviderNative aNative) {
        super(context, handler, TAG);
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
        foreach(new RemoteListenerHelper.ListenerOperation() { // from class: com.android.server.location.-$$Lambda$GnssNavigationMessageProvider$FPgP5DRMyheqM1CQ4z7jkoJwIFg
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public final void execute(IInterface iInterface, CallerIdentity callerIdentity) {
                ((IGnssNavigationMessageListener) iInterface).onGnssNavigationMessageReceived(event);
            }
        });
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
        if (result == 0) {
            status = 1;
        } else {
            if (result != 1 && result != 2) {
                if (result == 3) {
                    status = 2;
                } else if (result != 4) {
                    if (result != 5) {
                        Log.v(TAG, "Unhandled addListener result: " + result);
                        return null;
                    }
                    return null;
                }
            }
            status = 0;
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
        public void execute(IGnssNavigationMessageListener listener, CallerIdentity callerIdentity) throws RemoteException {
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
