package com.android.server.location;

import android.location.IGnssStatusListener;
import android.os.Handler;
import android.os.RemoteException;
import com.android.server.location.RemoteListenerHelper;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public abstract class GnssStatusListenerHelper extends RemoteListenerHelper<IGnssStatusListener> {

    /* loaded from: classes.dex */
    private interface Operation extends RemoteListenerHelper.ListenerOperation<IGnssStatusListener> {
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public GnssStatusListenerHelper(Handler handler) {
        super(handler, "GnssStatusListenerHelper");
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
        Operation operation;
        if (isNavigating) {
            operation = new Operation() { // from class: com.android.server.location.GnssStatusListenerHelper.1
                @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
                public void execute(IGnssStatusListener listener) throws RemoteException {
                    listener.onGnssStarted();
                }
            };
        } else {
            operation = new Operation() { // from class: com.android.server.location.GnssStatusListenerHelper.2
                @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
                public void execute(IGnssStatusListener listener) throws RemoteException {
                    listener.onGnssStopped();
                }
            };
        }
        foreach(operation);
    }

    public void onFirstFix(final int timeToFirstFix) {
        Operation operation = new Operation() { // from class: com.android.server.location.GnssStatusListenerHelper.3
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public void execute(IGnssStatusListener listener) throws RemoteException {
                listener.onFirstFix(timeToFirstFix);
            }
        };
        foreach(operation);
    }

    public void onSvStatusChanged(final int svCount, final int[] prnWithFlags, final float[] cn0s, final float[] elevations, final float[] azimuths, final float[] carrierFreqs) {
        Operation operation = new Operation() { // from class: com.android.server.location.GnssStatusListenerHelper.4
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public void execute(IGnssStatusListener listener) throws RemoteException {
                listener.onSvStatusChanged(svCount, prnWithFlags, cn0s, elevations, azimuths, carrierFreqs);
            }
        };
        foreach(operation);
    }

    public void onNmeaReceived(final long timestamp, final String nmea) {
        Operation operation = new Operation() { // from class: com.android.server.location.GnssStatusListenerHelper.5
            @Override // com.android.server.location.RemoteListenerHelper.ListenerOperation
            public void execute(IGnssStatusListener listener) throws RemoteException {
                listener.onNmeaReceived(timestamp, nmea);
            }
        };
        foreach(operation);
    }
}
