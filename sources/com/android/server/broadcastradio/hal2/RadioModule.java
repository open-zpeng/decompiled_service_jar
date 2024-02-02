package com.android.server.broadcastradio.hal2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.broadcastradio.V2_0.AmFmRegionConfig;
import android.hardware.broadcastradio.V2_0.Announcement;
import android.hardware.broadcastradio.V2_0.DabTableEntry;
import android.hardware.broadcastradio.V2_0.IAnnouncementListener;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.ITunerSession;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;
import android.util.MutableInt;
import android.util.Slog;
import com.android.server.broadcastradio.hal2.Utils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RadioModule {
    private static final String TAG = "BcRadio2Srv.module";
    public final RadioManager.ModuleProperties mProperties;
    private final IBroadcastRadio mService;

    private RadioModule(IBroadcastRadio service, RadioManager.ModuleProperties properties) {
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

    public TunerSession openSession(ITunerCallback userCb) throws RemoteException {
        TunerCallback cb = new TunerCallback((ITunerCallback) Objects.requireNonNull(userCb));
        final Mutable<ITunerSession> hwSession = new Mutable<>();
        final MutableInt halResult = new MutableInt(1);
        synchronized (this.mService) {
            this.mService.openSession(cb, new IBroadcastRadio.openSessionCallback() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$sutdDRJCfrrZR0pspDNZ8ndK1TY
                @Override // android.hardware.broadcastradio.V2_0.IBroadcastRadio.openSessionCallback
                public final void onValues(int i, ITunerSession iTunerSession) {
                    RadioModule.lambda$openSession$2(Mutable.this, halResult, i, iTunerSession);
                }
            });
        }
        Convert.throwOnError("openSession", halResult.value);
        Objects.requireNonNull((ITunerSession) hwSession.value);
        return new TunerSession(this, (ITunerSession) hwSession.value, cb);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    public static /* synthetic */ void lambda$openSession$2(Mutable hwSession, MutableInt halResult, int result, ITunerSession session) {
        hwSession.value = session;
        halResult.value = result;
    }

    public ICloseHandle addAnnouncementListener(int[] enabledTypes, IAnnouncementListener listener) throws RemoteException {
        ArrayList<Byte> enabledList = new ArrayList<>();
        for (int type : enabledTypes) {
            enabledList.add(Byte.valueOf((byte) type));
        }
        final MutableInt halResult = new MutableInt(1);
        final Mutable<android.hardware.broadcastradio.V2_0.ICloseHandle> hwCloseHandle = new Mutable<>();
        android.hardware.broadcastradio.V2_0.IAnnouncementListener hwListener = new AnonymousClass1(listener);
        synchronized (this.mService) {
            this.mService.registerAnnouncementListener(enabledList, hwListener, new IBroadcastRadio.registerAnnouncementListenerCallback() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$yXMJGeC9UEM7jTA1nM5DH22CG8U
                @Override // android.hardware.broadcastradio.V2_0.IBroadcastRadio.registerAnnouncementListenerCallback
                public final void onValues(int i, android.hardware.broadcastradio.V2_0.ICloseHandle iCloseHandle) {
                    RadioModule.lambda$addAnnouncementListener$3(halResult, hwCloseHandle, i, iCloseHandle);
                }
            });
        }
        Convert.throwOnError("addAnnouncementListener", halResult.value);
        return new ICloseHandle.Stub() { // from class: com.android.server.broadcastradio.hal2.RadioModule.2
            public void close() {
                try {
                    ((android.hardware.broadcastradio.V2_0.ICloseHandle) hwCloseHandle.value).close();
                } catch (RemoteException ex) {
                    Slog.e(RadioModule.TAG, "Failed closing announcement listener", ex);
                }
            }
        };
    }

    /* renamed from: com.android.server.broadcastradio.hal2.RadioModule$1  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass1 extends IAnnouncementListener.Stub {
        final /* synthetic */ android.hardware.radio.IAnnouncementListener val$listener;

        AnonymousClass1(android.hardware.radio.IAnnouncementListener iAnnouncementListener) {
            this.val$listener = iAnnouncementListener;
        }

        @Override // android.hardware.broadcastradio.V2_0.IAnnouncementListener
        public void onListUpdated(ArrayList<Announcement> hwAnnouncements) throws RemoteException {
            this.val$listener.onListUpdated((List) hwAnnouncements.stream().map(new Function() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$1$WyDVzz-rauof7mi7PTqGNFI3b3E
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
    public static /* synthetic */ void lambda$addAnnouncementListener$3(MutableInt halResult, Mutable hwCloseHandle, int result, android.hardware.broadcastradio.V2_0.ICloseHandle closeHnd) {
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
            List<Byte> rawList = (List) Utils.maybeRethrow(new Utils.FuncThrowingRemoteException() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$RadioModule$WfmM4W7QS3OnfFwUksITHnpM74g
                @Override // com.android.server.broadcastradio.hal2.Utils.FuncThrowingRemoteException
                public final Object exec() {
                    ArrayList image;
                    image = RadioModule.this.mService.getImage(id);
                    return image;
                }
            });
            rawImage = new byte[rawList.size()];
            for (int i = 0; i < rawList.size(); i++) {
                rawImage[i] = rawList.get(i).byteValue();
            }
        }
        if (rawImage != null && rawImage.length != 0) {
            return BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length);
        }
        return null;
    }
}
