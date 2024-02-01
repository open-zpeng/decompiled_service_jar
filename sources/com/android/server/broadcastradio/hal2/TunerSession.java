package com.android.server.broadcastradio.hal2;

import android.graphics.Bitmap;
import android.hardware.broadcastradio.V2_0.ConfigFlag;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.radio.ITuner;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;
import android.util.MutableBoolean;
import android.util.MutableInt;
import android.util.Slog;
import com.android.server.broadcastradio.hal2.TunerCallback;
import com.android.server.broadcastradio.hal2.Utils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class TunerSession extends ITuner.Stub {
    private static final String TAG = "BcRadio2Srv.session";
    private static final String kAudioDeviceName = "Radio tuner source";
    private final TunerCallback mCallback;
    private final ITunerSession mHwSession;
    private final RadioModule mModule;
    private final Object mLock = new Object();
    private boolean mIsClosed = false;
    private boolean mIsMuted = false;
    private RadioManager.BandConfig mDummyConfig = null;

    /* JADX INFO: Access modifiers changed from: package-private */
    public TunerSession(RadioModule module, ITunerSession hwSession, TunerCallback callback) {
        this.mModule = (RadioModule) Objects.requireNonNull(module);
        this.mHwSession = (ITunerSession) Objects.requireNonNull(hwSession);
        this.mCallback = (TunerCallback) Objects.requireNonNull(callback);
    }

    public void close() {
        synchronized (this.mLock) {
            if (this.mIsClosed) {
                return;
            }
            try {
                this.mHwSession.close();
                this.mIsClosed = true;
            } catch (RemoteException ex) {
                throw new RuntimeException("Failed to close the tuner", ex);
            }
        }
    }

    public boolean isClosed() {
        return this.mIsClosed;
    }

    private void checkNotClosedLocked() {
        if (this.mIsClosed) {
            throw new IllegalStateException("Tuner is closed, no further operations are allowed");
        }
    }

    public void setConfiguration(final RadioManager.BandConfig config) {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            this.mDummyConfig = (RadioManager.BandConfig) Objects.requireNonNull(config);
            Slog.i(TAG, "Ignoring setConfiguration - not applicable for broadcastradio HAL 2.x");
            TunerCallback.dispatch(new TunerCallback.RunnableThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerSession$zciYeXvEvoYmcVVXon2g963Oe64
                @Override // com.android.server.broadcastradio.hal2.TunerCallback.RunnableThrowingRemoteException
                public final void run() {
                    TunerSession.this.mCallback.mClientCb.onConfigurationChanged(config);
                }
            });
        }
    }

    public RadioManager.BandConfig getConfiguration() {
        RadioManager.BandConfig bandConfig;
        synchronized (this.mLock) {
            checkNotClosedLocked();
            bandConfig = this.mDummyConfig;
        }
        return bandConfig;
    }

    public void setMuted(boolean mute) {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            if (this.mIsMuted == mute) {
                return;
            }
            this.mIsMuted = mute;
            Slog.w(TAG, "Mute via RadioService is not implemented - please handle it via app");
        }
    }

    public boolean isMuted() {
        boolean z;
        synchronized (this.mLock) {
            checkNotClosedLocked();
            z = this.mIsMuted;
        }
        return z;
    }

    public void step(boolean directionDown, boolean skipSubChannel) throws RemoteException {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            int halResult = this.mHwSession.step(!directionDown);
            Convert.throwOnError("step", halResult);
        }
    }

    public void scan(boolean directionDown, boolean skipSubChannel) throws RemoteException {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            int halResult = this.mHwSession.scan(!directionDown, skipSubChannel);
            Convert.throwOnError("step", halResult);
        }
    }

    public void tune(ProgramSelector selector) throws RemoteException {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            int halResult = this.mHwSession.tune(Convert.programSelectorToHal(selector));
            Convert.throwOnError("tune", halResult);
        }
    }

    public void cancel() {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            final ITunerSession iTunerSession = this.mHwSession;
            Objects.requireNonNull(iTunerSession);
            Utils.maybeRethrow(new Utils.VoidFuncThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$YOfksBuizvGCCXXC3xdyOet2Yr8
                @Override // com.android.server.broadcastradio.hal2.Utils.VoidFuncThrowingRemoteException
                public final void exec() {
                    ITunerSession.this.cancel();
                }
            });
        }
    }

    public void cancelAnnouncement() {
        Slog.i(TAG, "Announcements control doesn't involve cancelling at the HAL level in 2.x");
    }

    public Bitmap getImage(int id) {
        return this.mModule.getImage(id);
    }

    public boolean startBackgroundScan() {
        Slog.i(TAG, "Explicit background scan trigger is not supported with HAL 2.x");
        TunerCallback.dispatch(new TunerCallback.RunnableThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerSession$mmncPOeo4VmHBIRo77qp7XrwQeA
            @Override // com.android.server.broadcastradio.hal2.TunerCallback.RunnableThrowingRemoteException
            public final void run() {
                TunerSession.this.mCallback.mClientCb.onBackgroundScanComplete();
            }
        });
        return true;
    }

    public void startProgramListUpdates(ProgramList.Filter filter) throws RemoteException {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            int halResult = this.mHwSession.startProgramListUpdates(Convert.programFilterToHal(filter));
            Convert.throwOnError("startProgramListUpdates", halResult);
        }
    }

    public void stopProgramListUpdates() throws RemoteException {
        synchronized (this.mLock) {
            checkNotClosedLocked();
            this.mHwSession.stopProgramListUpdates();
        }
    }

    public boolean isConfigFlagSupported(int flag) {
        try {
            isConfigFlagSet(flag);
            return true;
        } catch (IllegalStateException e) {
            return true;
        } catch (UnsupportedOperationException e2) {
            return false;
        }
    }

    public boolean isConfigFlagSet(int flag) {
        boolean z;
        Slog.v(TAG, "isConfigFlagSet " + ConfigFlag.toString(flag));
        synchronized (this.mLock) {
            checkNotClosedLocked();
            final MutableInt halResult = new MutableInt(1);
            final MutableBoolean flagState = new MutableBoolean(false);
            try {
                this.mHwSession.isConfigFlagSet(flag, new ITunerSession.isConfigFlagSetCallback() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerSession$ypybq6SvfCU67BzHDgrQ7oDdspw
                    @Override // android.hardware.broadcastradio.V2_0.ITunerSession.isConfigFlagSetCallback
                    public final void onValues(int i, boolean z2) {
                        TunerSession.lambda$isConfigFlagSet$2(halResult, flagState, i, z2);
                    }
                });
                Convert.throwOnError("isConfigFlagSet", halResult.value);
                z = flagState.value;
            } catch (RemoteException ex) {
                throw new RuntimeException("Failed to check flag " + ConfigFlag.toString(flag), ex);
            }
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$isConfigFlagSet$2(MutableInt halResult, MutableBoolean flagState, int result, boolean value) {
        halResult.value = result;
        flagState.value = value;
    }

    public void setConfigFlag(int flag, boolean value) throws RemoteException {
        Slog.v(TAG, "setConfigFlag " + ConfigFlag.toString(flag) + " = " + value);
        synchronized (this.mLock) {
            checkNotClosedLocked();
            int halResult = this.mHwSession.setConfigFlag(flag, value);
            Convert.throwOnError("setConfigFlag", halResult);
        }
    }

    public Map setParameters(final Map parameters) {
        Map<String, String> vendorInfoFromHal;
        synchronized (this.mLock) {
            checkNotClosedLocked();
            vendorInfoFromHal = Convert.vendorInfoFromHal((List) Utils.maybeRethrow(new Utils.FuncThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerSession$hsnpNw6T-T5c0D5uUev9VuiIUUg
                @Override // com.android.server.broadcastradio.hal2.Utils.FuncThrowingRemoteException
                public final Object exec() {
                    ArrayList parameters2;
                    parameters2 = TunerSession.this.mHwSession.setParameters(Convert.vendorInfoToHal(parameters));
                    return parameters2;
                }
            }));
        }
        return vendorInfoFromHal;
    }

    public Map getParameters(final List<String> keys) {
        Map<String, String> vendorInfoFromHal;
        synchronized (this.mLock) {
            checkNotClosedLocked();
            vendorInfoFromHal = Convert.vendorInfoFromHal((List) Utils.maybeRethrow(new Utils.FuncThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$TunerSession$UmZx38YMX_OHk94g5WH0WyZPNu0
                @Override // com.android.server.broadcastradio.hal2.Utils.FuncThrowingRemoteException
                public final Object exec() {
                    ArrayList parameters;
                    parameters = TunerSession.this.mHwSession.getParameters(Convert.listToArrayList(keys));
                    return parameters;
                }
            }));
        }
        return vendorInfoFromHal;
    }
}
