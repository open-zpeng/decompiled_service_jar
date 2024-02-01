package com.android.server.broadcastradio.hal2;

import android.hardware.broadcastradio.V2_0.ITunerCallback;
import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.broadcastradio.V2_0.ProgramSelector;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.os.RemoteException;
import android.util.Slog;
import java.util.ArrayList;
import java.util.Objects;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TunerCallback extends ITunerCallback.Stub {
    private static final String TAG = "BcRadio2Srv.cb";
    final android.hardware.radio.ITunerCallback mClientCb;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface RunnableThrowingRemoteException {
        void run() throws RemoteException;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public TunerCallback(android.hardware.radio.ITunerCallback clientCallback) {
        this.mClientCb = (android.hardware.radio.ITunerCallback) Objects.requireNonNull(clientCallback);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void dispatch(RunnableThrowingRemoteException func) {
        try {
            func.run();
        } catch (RemoteException ex) {
            Slog.e(TAG, "callback call failed", ex);
        }
    }

    @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
    public void onTuneFailed(final int result, final ProgramSelector selector) {
        dispatch(new RunnableThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerCallback$g9s81qLW621BQoiMM1ZJSz6j7o0
            @Override // com.android.server.broadcastradio.hal2.TunerCallback.RunnableThrowingRemoteException
            public final void run() {
                TunerCallback.this.mClientCb.onTuneFailed(result, Convert.programSelectorFromHal(selector));
            }
        });
    }

    @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
    public void onCurrentProgramInfoChanged(final ProgramInfo info) {
        dispatch(new RunnableThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerCallback$BVfZjikb4C0UELTivStyv5FL3oQ
            @Override // com.android.server.broadcastradio.hal2.TunerCallback.RunnableThrowingRemoteException
            public final void run() {
                TunerCallback.this.mClientCb.onCurrentProgramInfoChanged(Convert.programInfoFromHal(info));
            }
        });
    }

    @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
    public void onProgramListUpdated(final ProgramListChunk chunk) {
        dispatch(new RunnableThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerCallback$NBPSu3hnNZZM0d6dcfEzy6v1DdE
            @Override // com.android.server.broadcastradio.hal2.TunerCallback.RunnableThrowingRemoteException
            public final void run() {
                TunerCallback.this.mClientCb.onProgramListUpdated(Convert.programListChunkFromHal(chunk));
            }
        });
    }

    @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
    public void onAntennaStateChange(final boolean connected) {
        dispatch(new RunnableThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerCallback$4QBYui9TTe72lpu-ZEbezxJSKDg
            @Override // com.android.server.broadcastradio.hal2.TunerCallback.RunnableThrowingRemoteException
            public final void run() {
                TunerCallback.this.mClientCb.onAntennaState(connected);
            }
        });
    }

    @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
    public void onParametersUpdated(final ArrayList<VendorKeyValue> parameters) {
        dispatch(new RunnableThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerCallback$5J8F-hUEkbHptpTjfB5HrmIF-7w
            @Override // com.android.server.broadcastradio.hal2.TunerCallback.RunnableThrowingRemoteException
            public final void run() {
                TunerCallback.this.mClientCb.onParametersUpdated(Convert.vendorInfoFromHal(parameters));
            }
        });
    }
}
