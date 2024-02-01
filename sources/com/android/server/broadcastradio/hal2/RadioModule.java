package com.android.server.broadcastradio.hal2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.broadcastradio.V2_0.AmFmRegionConfig;
import android.hardware.broadcastradio.V2_0.Announcement;
import android.hardware.broadcastradio.V2_0.DabTableEntry;
import android.hardware.broadcastradio.V2_0.IAnnouncementListener;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.ITunerCallback;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.broadcastradio.V2_0.ProgramSelector;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ProgramList;
import android.hardware.radio.RadioManager;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.MutableInt;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.server.broadcastradio.hal2.RadioModule;
import com.android.server.broadcastradio.hal2.Utils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RadioModule {
    private static final String TAG = "BcRadio2Srv.module";
    @GuardedBy({"mLock"})
    private ITunerSession mHalTunerSession;
    public final RadioManager.ModuleProperties mProperties;
    private final IBroadcastRadio mService;
    private final Object mLock = new Object();
    @GuardedBy({"mLock"})
    private Boolean mAntennaConnected = null;
    @GuardedBy({"mLock"})
    private RadioManager.ProgramInfo mProgramInfo = null;
    private final ITunerCallback mHalTunerCallback = new AnonymousClass1();
    @GuardedBy({"mLock"})
    private final Set<TunerSession> mAidlTunerSessions = new HashSet();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface AidlCallbackRunnable {
        void run(android.hardware.radio.ITunerCallback iTunerCallback) throws RemoteException;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.broadcastradio.hal2.RadioModule$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 extends ITunerCallback.Stub {
        AnonymousClass1() {
        }

        @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
        public void onTuneFailed(final int result, final ProgramSelector programSelector) {
            RadioModule.this.lockAndFireLater(new Runnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$oUY5YASjXdzm7j6NkozI56hILxU
                @Override // java.lang.Runnable
                public final void run() {
                    RadioModule.AnonymousClass1.this.lambda$onTuneFailed$1$RadioModule$1(programSelector, result);
                }
            });
        }

        public /* synthetic */ void lambda$onTuneFailed$1$RadioModule$1(ProgramSelector programSelector, final int result) {
            final android.hardware.radio.ProgramSelector csel = Convert.programSelectorFromHal(programSelector);
            RadioModule.this.lambda$fanoutAidlCallback$4$RadioModule(new AidlCallbackRunnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$59H6y7GZA-_7GDPEgL2E26h7WiE
                @Override // com.android.server.broadcastradio.hal2.RadioModule.AidlCallbackRunnable
                public final void run(android.hardware.radio.ITunerCallback iTunerCallback) {
                    iTunerCallback.onTuneFailed(result, csel);
                }
            });
        }

        @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
        public void onCurrentProgramInfoChanged(final ProgramInfo halProgramInfo) {
            RadioModule.this.lockAndFireLater(new Runnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$DJu-ip-lyw364IIvgG3EXOTSGvY
                @Override // java.lang.Runnable
                public final void run() {
                    RadioModule.AnonymousClass1.this.lambda$onCurrentProgramInfoChanged$3$RadioModule$1(halProgramInfo);
                }
            });
        }

        public /* synthetic */ void lambda$onCurrentProgramInfoChanged$3$RadioModule$1(ProgramInfo halProgramInfo) {
            RadioModule.this.mProgramInfo = Convert.programInfoFromHal(halProgramInfo);
            RadioModule.this.lambda$fanoutAidlCallback$4$RadioModule(new AidlCallbackRunnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$iKwhPFaskH3rDDMX9COUVaItuzk
                @Override // com.android.server.broadcastradio.hal2.RadioModule.AidlCallbackRunnable
                public final void run(android.hardware.radio.ITunerCallback iTunerCallback) {
                    RadioModule.AnonymousClass1.this.lambda$onCurrentProgramInfoChanged$2$RadioModule$1(iTunerCallback);
                }
            });
        }

        public /* synthetic */ void lambda$onCurrentProgramInfoChanged$2$RadioModule$1(android.hardware.radio.ITunerCallback cb) throws RemoteException {
            cb.onCurrentProgramInfoChanged(RadioModule.this.mProgramInfo);
        }

        @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
        public void onProgramListUpdated(final ProgramListChunk programListChunk) {
            RadioModule.this.lockAndFireLater(new Runnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$mkiQxR7x6pr3eBeJFIlov-WnSjM
                @Override // java.lang.Runnable
                public final void run() {
                    RadioModule.AnonymousClass1.this.lambda$onProgramListUpdated$5$RadioModule$1(programListChunk);
                }
            });
        }

        public /* synthetic */ void lambda$onProgramListUpdated$5$RadioModule$1(ProgramListChunk programListChunk) {
            final ProgramList.Chunk chunk = Convert.programListChunkFromHal(programListChunk);
            RadioModule.this.lambda$fanoutAidlCallback$4$RadioModule(new AidlCallbackRunnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$iPNL1BzYB0xOLiNRumbVqM98axU
                @Override // com.android.server.broadcastradio.hal2.RadioModule.AidlCallbackRunnable
                public final void run(android.hardware.radio.ITunerCallback iTunerCallback) {
                    iTunerCallback.onProgramListUpdated(chunk);
                }
            });
        }

        @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
        public void onAntennaStateChange(final boolean connected) {
            RadioModule.this.lockAndFireLater(new Runnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$CO7kpcAsEI0JRq4Sg4VMiBZSLnU
                @Override // java.lang.Runnable
                public final void run() {
                    RadioModule.AnonymousClass1.this.lambda$onAntennaStateChange$7$RadioModule$1(connected);
                }
            });
        }

        public /* synthetic */ void lambda$onAntennaStateChange$7$RadioModule$1(final boolean connected) {
            RadioModule.this.mAntennaConnected = Boolean.valueOf(connected);
            RadioModule.this.lambda$fanoutAidlCallback$4$RadioModule(new AidlCallbackRunnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$isrDghFyiU3A-wFTahTqlalIBVo
                @Override // com.android.server.broadcastradio.hal2.RadioModule.AidlCallbackRunnable
                public final void run(android.hardware.radio.ITunerCallback iTunerCallback) {
                    iTunerCallback.onAntennaState(connected);
                }
            });
        }

        @Override // android.hardware.broadcastradio.V2_0.ITunerCallback
        public void onParametersUpdated(final ArrayList<VendorKeyValue> parameters) {
            RadioModule.this.lockAndFireLater(new Runnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$Zuu6z2oPYUITcmI7bBh9YX3UmjQ
                @Override // java.lang.Runnable
                public final void run() {
                    RadioModule.AnonymousClass1.this.lambda$onParametersUpdated$9$RadioModule$1(parameters);
                }
            });
        }

        public /* synthetic */ void lambda$onParametersUpdated$9$RadioModule$1(ArrayList parameters) {
            final Map<String, String> cparam = Convert.vendorInfoFromHal(parameters);
            RadioModule.this.lambda$fanoutAidlCallback$4$RadioModule(new AidlCallbackRunnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$VLrys0HkTmiHG7kZ1NCqVx7lA0s
                @Override // com.android.server.broadcastradio.hal2.RadioModule.AidlCallbackRunnable
                public final void run(android.hardware.radio.ITunerCallback iTunerCallback) {
                    iTunerCallback.onParametersUpdated(cparam);
                }
            });
        }
    }

    private RadioModule(IBroadcastRadio service, RadioManager.ModuleProperties properties) throws RemoteException {
        this.mProperties = (RadioManager.ModuleProperties) Objects.requireNonNull(properties);
        this.mService = (IBroadcastRadio) Objects.requireNonNull(service);
    }

    public static RadioModule tryLoadingModule(int idx, String fqName) {
        try {
            IBroadcastRadio service = IBroadcastRadio.getService(fqName);
            if (service == null) {
                return null;
            }
            final Mutable<AmFmRegionConfig> amfmConfig = new Mutable<>();
            service.getAmFmRegionConfig(false, new IBroadcastRadio.getAmFmRegionConfigCallback() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$Ps8Vj3Q777LToAxaWE_cyahZ1Is
                @Override // android.hardware.broadcastradio.V2_0.IBroadcastRadio.getAmFmRegionConfigCallback
                public final void onValues(int i, AmFmRegionConfig amFmRegionConfig) {
                    RadioModule.lambda$tryLoadingModule$0(Mutable.this, i, amFmRegionConfig);
                }
            });
            final Mutable<List<DabTableEntry>> dabConfig = new Mutable<>();
            service.getDabRegionConfig(new IBroadcastRadio.getDabRegionConfigCallback() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$8XDiZOu4emnvYISlRsAgvceyPhA
                @Override // android.hardware.broadcastradio.V2_0.IBroadcastRadio.getDabRegionConfigCallback
                public final void onValues(int i, ArrayList arrayList) {
                    RadioModule.lambda$tryLoadingModule$1(Mutable.this, i, arrayList);
                }
            });
            RadioManager.ModuleProperties prop = Convert.propertiesFromHal(idx, fqName, service.getProperties(), (AmFmRegionConfig) amfmConfig.value, (List) dabConfig.value);
            return new RadioModule(service, prop);
        } catch (RemoteException ex) {
            Slog.e(TAG, "failed to load module " + fqName, ex);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    public static /* synthetic */ void lambda$tryLoadingModule$0(Mutable amfmConfig, int result, AmFmRegionConfig config) {
        if (result == 0) {
            amfmConfig.value = config;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    public static /* synthetic */ void lambda$tryLoadingModule$1(Mutable dabConfig, int result, ArrayList config) {
        if (result == 0) {
            dabConfig.value = config;
        }
    }

    public IBroadcastRadio getService() {
        return this.mService;
    }

    public TunerSession openSession(android.hardware.radio.ITunerCallback userCb) throws RemoteException {
        TunerSession tunerSession;
        synchronized (this.mLock) {
            if (this.mHalTunerSession == null) {
                final Mutable<ITunerSession> hwSession = new Mutable<>();
                this.mService.openSession(this.mHalTunerCallback, new IBroadcastRadio.openSessionCallback() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$PbMDkfYDR1vn12EtbZSZscvNDM8
                    @Override // android.hardware.broadcastradio.V2_0.IBroadcastRadio.openSessionCallback
                    public final void onValues(int i, ITunerSession iTunerSession) {
                        RadioModule.lambda$openSession$2(Mutable.this, i, iTunerSession);
                    }
                });
                this.mHalTunerSession = (ITunerSession) Objects.requireNonNull((ITunerSession) hwSession.value);
            }
            tunerSession = new TunerSession(this, this.mHalTunerSession, userCb);
            this.mAidlTunerSessions.add(tunerSession);
            if (this.mAntennaConnected != null) {
                userCb.onAntennaState(this.mAntennaConnected.booleanValue());
            }
            if (this.mProgramInfo != null) {
                userCb.onCurrentProgramInfoChanged(this.mProgramInfo);
            }
        }
        return tunerSession;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    public static /* synthetic */ void lambda$openSession$2(Mutable hwSession, int result, ITunerSession session) {
        Convert.throwOnError("openSession", result);
        hwSession.value = session;
    }

    public void closeSessions(Integer error) {
        TunerSession[] tunerSessions;
        synchronized (this.mLock) {
            tunerSessions = new TunerSession[this.mAidlTunerSessions.size()];
            this.mAidlTunerSessions.toArray(tunerSessions);
            this.mAidlTunerSessions.clear();
        }
        for (TunerSession tunerSession : tunerSessions) {
            tunerSession.close(error);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTunerSessionClosed(TunerSession tunerSession) {
        synchronized (this.mLock) {
            this.mAidlTunerSessions.remove(tunerSession);
            if (this.mAidlTunerSessions.isEmpty() && this.mHalTunerSession != null) {
                Slog.v(TAG, "closing HAL tuner session");
                try {
                    this.mHalTunerSession.close();
                } catch (RemoteException ex) {
                    Slog.e(TAG, "mHalTunerSession.close() failed: ", ex);
                }
                this.mHalTunerSession = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void lockAndFireLater(final Runnable r) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$tYabuulPwbVXcGUDicZUG2Lh3f8
            @Override // java.lang.Runnable
            public final void run() {
                RadioModule.this.lambda$lockAndFireLater$3$RadioModule(r);
            }
        });
    }

    public /* synthetic */ void lambda$lockAndFireLater$3$RadioModule(Runnable r) {
        synchronized (this.mLock) {
            r.run();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void fanoutAidlCallback(final AidlCallbackRunnable runnable) {
        lockAndFireLater(new Runnable() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$tiXDbuKN6VpLu_LpIYLaGBekDJU
            @Override // java.lang.Runnable
            public final void run() {
                RadioModule.this.lambda$fanoutAidlCallback$4$RadioModule(runnable);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: fanoutAidlCallbackLocked */
    public void lambda$fanoutAidlCallback$4$RadioModule(AidlCallbackRunnable runnable) {
        List<TunerSession> deadSessions = null;
        for (TunerSession tunerSession : this.mAidlTunerSessions) {
            try {
                runnable.run(tunerSession.mCallback);
            } catch (DeadObjectException e) {
                Slog.e(TAG, "Removing dead TunerSession");
                if (deadSessions == null) {
                    deadSessions = new ArrayList<>();
                }
                deadSessions.add(tunerSession);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to invoke ITunerCallback: ", ex);
            }
        }
        if (deadSessions != null) {
            this.mAidlTunerSessions.removeAll(deadSessions);
        }
    }

    public ICloseHandle addAnnouncementListener(int[] enabledTypes, IAnnouncementListener listener) throws RemoteException {
        ArrayList<Byte> enabledList = new ArrayList<>();
        for (int type : enabledTypes) {
            enabledList.add(Byte.valueOf((byte) type));
        }
        final MutableInt halResult = new MutableInt(1);
        final Mutable<android.hardware.broadcastradio.V2_0.ICloseHandle> hwCloseHandle = new Mutable<>();
        android.hardware.broadcastradio.V2_0.IAnnouncementListener hwListener = new AnonymousClass2(listener);
        synchronized (this.mService) {
            this.mService.registerAnnouncementListener(enabledList, hwListener, new IBroadcastRadio.registerAnnouncementListenerCallback() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$QlTfH9411GzmESnxSlsnKbLa8hw
                @Override // android.hardware.broadcastradio.V2_0.IBroadcastRadio.registerAnnouncementListenerCallback
                public final void onValues(int i, android.hardware.broadcastradio.V2_0.ICloseHandle iCloseHandle) {
                    RadioModule.lambda$addAnnouncementListener$5(halResult, hwCloseHandle, i, iCloseHandle);
                }
            });
        }
        Convert.throwOnError("addAnnouncementListener", halResult.value);
        return new ICloseHandle.Stub() { // from class: com.android.server.broadcastradio.hal2.RadioModule.3
            public void close() {
                try {
                    ((android.hardware.broadcastradio.V2_0.ICloseHandle) hwCloseHandle.value).close();
                } catch (RemoteException ex) {
                    Slog.e(RadioModule.TAG, "Failed closing announcement listener", ex);
                }
            }
        };
    }

    /* renamed from: com.android.server.broadcastradio.hal2.RadioModule$2  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass2 extends IAnnouncementListener.Stub {
        final /* synthetic */ android.hardware.radio.IAnnouncementListener val$listener;

        AnonymousClass2(android.hardware.radio.IAnnouncementListener iAnnouncementListener) {
            this.val$listener = iAnnouncementListener;
        }

        @Override // android.hardware.broadcastradio.V2_0.IAnnouncementListener
        public void onListUpdated(ArrayList<Announcement> hwAnnouncements) throws RemoteException {
            this.val$listener.onListUpdated((List) hwAnnouncements.stream().map(new Function() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$2$06udTLOtrtIC_bWC-WpXUXkuLVM
                @Override // java.util.function.Function
                public final Object apply(Object obj) {
                    android.hardware.radio.Announcement announcementFromHal;
                    announcementFromHal = Convert.announcementFromHal((Announcement) obj);
                    return announcementFromHal;
                }
            }).collect(Collectors.toList()));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    public static /* synthetic */ void lambda$addAnnouncementListener$5(MutableInt halResult, Mutable hwCloseHandle, int result, android.hardware.broadcastradio.V2_0.ICloseHandle closeHnd) {
        halResult.value = result;
        hwCloseHandle.value = closeHnd;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Bitmap getImage(final int id) {
        byte[] rawImage;
        if (id == 0) {
            throw new IllegalArgumentException("Image ID is missing");
        }
        synchronized (this.mService) {
            List<Byte> rawList = (List) Utils.maybeRethrow(new Utils.FuncThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$7jNXPMuvH-DQ1rLv9_WZ2s1UiPQ
                @Override // com.android.server.broadcastradio.hal2.Utils.FuncThrowingRemoteException
                public final Object exec() {
                    return RadioModule.this.lambda$getImage$6$RadioModule(id);
                }
            });
            rawImage = new byte[rawList.size()];
            for (int i = 0; i < rawList.size(); i++) {
                rawImage[i] = rawList.get(i).byteValue();
            }
        }
        if (rawImage.length == 0) {
            return null;
        }
        return BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length);
    }

    public /* synthetic */ ArrayList lambda$getImage$6$RadioModule(int id) throws RemoteException {
        return this.mService.getImage(id);
    }
}
