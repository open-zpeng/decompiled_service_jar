package com.android.server.location;

import android.content.Context;
import android.location.IGnssStatusListener;
import android.os.Handler;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;
import com.android.server.location.RemoteListenerHelper;

/* loaded from: classes.dex */
public abstract class GnssStatusListenerHelper extends RemoteListenerHelper<IGnssStatusListener> {
    private static final String TAG = "GnssStatusListenerHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);

    /* JADX INFO: Access modifiers changed from: protected */
    public GnssStatusListenerHelper(Context context, Handler handler) {
        super(context, handler, TAG);
        setSupported(GnssLocationProvider.isSupported());
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected int registerWithService() {
        return 0;
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected void unregisterFromService() {
    }

    @Override // com.android.server.location.RemoteListenerHelper
    protected RemoteListenerHelper.ListenerOperation<IGnssStatusListener> getHandlerOperation(int result) {
        return null;
    }

    public void onStatusChanged(boolean isNavigating) {
        if (isNavigating) {
            foreach(new RemoteListenerHelper.ListenerOperation() { // from class: com.android.server.location.-$$Lambda$GnssStatusListenerHelper$H9Tg_OtCE9BSJiAQYs_ITHFpiHU
                @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
                public final void execute(IInterface iInterface, CallerIdentity callerIdentity) {
                    ((IGnssStatusListener) iInterface).onGnssStarted();
                }
            });
        } else {
            foreach(new RemoteListenerHelper.ListenerOperation() { // from class: com.android.server.location.-$$Lambda$GnssStatusListenerHelper$6s2HBSMgP5pXrugfCvtIf9QHndI
                @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
                public final void execute(IInterface iInterface, CallerIdentity callerIdentity) {
                    ((IGnssStatusListener) iInterface).onGnssStopped();
                }
            });
        }
    }

    public void onFirstFix(final int timeToFirstFix) {
        foreach(new RemoteListenerHelper.ListenerOperation() { // from class: com.android.server.location.-$$Lambda$GnssStatusListenerHelper$0MNjUouf1HJVcFD10rzoJIkzCrw
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public final void execute(IInterface iInterface, CallerIdentity callerIdentity) {
                ((IGnssStatusListener) iInterface).onFirstFix(timeToFirstFix);
            }
        });
    }

    public void onSvStatusChanged(final int svCount, final int[] prnWithFlags, final float[] cn0s, final float[] elevations, final float[] azimuths, final float[] carrierFreqs) {
        foreach(new RemoteListenerHelper.ListenerOperation() { // from class: com.android.server.location.-$$Lambda$GnssStatusListenerHelper$68FOYPQxCAVSdtoWmmZNfYGGIJE
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public final void execute(IInterface iInterface, CallerIdentity callerIdentity) {
                GnssStatusListenerHelper.this.lambda$onSvStatusChanged$3$GnssStatusListenerHelper(svCount, prnWithFlags, cn0s, elevations, azimuths, carrierFreqs, (IGnssStatusListener) iInterface, callerIdentity);
            }
        });
    }

    public /* synthetic */ void lambda$onSvStatusChanged$3$GnssStatusListenerHelper(int svCount, int[] prnWithFlags, float[] cn0s, float[] elevations, float[] azimuths, float[] carrierFreqs, IGnssStatusListener listener, CallerIdentity callerIdentity) throws RemoteException {
        if (!hasPermission(this.mContext, callerIdentity)) {
            logPermissionDisabledEventNotReported(TAG, callerIdentity.mPackageName, "GNSS status");
        } else {
            listener.onSvStatusChanged(svCount, prnWithFlags, cn0s, elevations, azimuths, carrierFreqs);
        }
    }

    public void onNmeaReceived(final long timestamp, final String nmea) {
        foreach(new RemoteListenerHelper.ListenerOperation() { // from class: com.android.server.location.-$$Lambda$GnssStatusListenerHelper$AtHI8E6PAjonHH1N0ZGabW0VF6c
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public final void execute(IInterface iInterface, CallerIdentity callerIdentity) {
                GnssStatusListenerHelper.this.lambda$onNmeaReceived$4$GnssStatusListenerHelper(timestamp, nmea, (IGnssStatusListener) iInterface, callerIdentity);
            }
        });
    }

    public /* synthetic */ void lambda$onNmeaReceived$4$GnssStatusListenerHelper(long timestamp, String nmea, IGnssStatusListener listener, CallerIdentity callerIdentity) throws RemoteException {
        if (!hasPermission(this.mContext, callerIdentity)) {
            logPermissionDisabledEventNotReported(TAG, callerIdentity.mPackageName, "NMEA");
        } else {
            listener.onNmeaReceived(timestamp, nmea);
        }
    }
}
