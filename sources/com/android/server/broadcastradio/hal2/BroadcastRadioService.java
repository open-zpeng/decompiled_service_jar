package com.android.server.broadcastradio.hal2;

import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.hidl.manager.V1_0.IServiceManager;
import android.os.RemoteException;
import android.util.Slog;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
/* loaded from: classes.dex */
public class BroadcastRadioService {
    private static final String TAG = "BcRadio2Srv";
    private final Map<Integer, RadioModule> mModules = new HashMap();

    private static List<String> listByInterface(String fqName) {
        try {
            IServiceManager manager = IServiceManager.getService();
            if (manager == null) {
                Slog.e(TAG, "Failed to get HIDL Service Manager");
                return Collections.emptyList();
            }
            List<String> list = manager.listByInterface(fqName);
            if (list == null) {
                Slog.e(TAG, "Didn't get interface list from HIDL Service Manager");
                return Collections.emptyList();
            }
            return list;
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed fetching interface list", ex);
            return Collections.emptyList();
        }
    }

    public Collection<RadioManager.ModuleProperties> loadModules(int idx) {
        Slog.v(TAG, "loadModules(" + idx + ")");
        for (String serviceName : listByInterface(IBroadcastRadio.kInterfaceName)) {
            Slog.v(TAG, "checking service: " + serviceName);
            RadioModule module = RadioModule.tryLoadingModule(idx, serviceName);
            if (module != null) {
                Slog.i(TAG, "loaded broadcast radio module " + idx + ": " + serviceName + " (HAL 2.0)");
                this.mModules.put(Integer.valueOf(idx), module);
                idx++;
            }
        }
        return (Collection) this.mModules.values().stream().map(new Function() { // from class: com.android.server.broadcastradio.hal2.-$$Lambda$BroadcastRadioService$Km0YQiJEv8afsLUzB5d2Fcgtk1g
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                RadioManager.ModuleProperties moduleProperties;
                moduleProperties = ((RadioModule) obj).mProperties;
                return moduleProperties;
            }
        }).collect(Collectors.toList());
    }

    public boolean hasModule(int id) {
        return this.mModules.containsKey(Integer.valueOf(id));
    }

    public boolean hasAnyModules() {
        return !this.mModules.isEmpty();
    }

    public ITuner openSession(int moduleId, RadioManager.BandConfig legacyConfig, boolean withAudio, ITunerCallback callback) throws RemoteException {
        Objects.requireNonNull(callback);
        if (!withAudio) {
            throw new IllegalArgumentException("Non-audio sessions not supported with HAL 2.x");
        }
        RadioModule module = this.mModules.get(Integer.valueOf(moduleId));
        if (module == null) {
            throw new IllegalArgumentException("Invalid module ID");
        }
        TunerSession session = module.openSession(callback);
        if (legacyConfig != null) {
            session.setConfiguration(legacyConfig);
        }
        return session;
    }

    public ICloseHandle addAnnouncementListener(int[] enabledTypes, IAnnouncementListener listener) {
        AnnouncementAggregator aggregator = new AnnouncementAggregator(listener);
        boolean anySupported = false;
        for (RadioModule module : this.mModules.values()) {
            try {
                aggregator.watchModule(module, enabledTypes);
                anySupported = true;
            } catch (UnsupportedOperationException ex) {
                Slog.v(TAG, "Announcements not supported for this module", ex);
            }
        }
        if (!anySupported) {
            Slog.i(TAG, "There are no HAL modules that support announcements");
        }
        return aggregator;
    }
}
