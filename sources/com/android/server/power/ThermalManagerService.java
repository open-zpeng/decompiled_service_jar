package com.android.server.power;

import android.content.Context;
import android.hardware.thermal.V1_0.IThermal;
import android.hardware.thermal.V1_0.ThermalStatus;
import android.hardware.thermal.V1_1.IThermalCallback;
import android.hardware.thermal.V2_0.IThermal;
import android.hardware.thermal.V2_0.IThermalChangedCallback;
import android.os.Binder;
import android.os.CoolingDevice;
import android.os.IHwBinder;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.IThermalStatusListener;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.Temperature;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.UiModeManagerService;
import com.android.server.power.ThermalManagerService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/* loaded from: classes.dex */
public class ThermalManagerService extends SystemService {
    private static final int INVALID_THROTTLING = Integer.MIN_VALUE;
    private static final String TAG = ThermalManagerService.class.getSimpleName();
    private final AtomicBoolean mHalReady;
    private ThermalHalWrapper mHalWrapper;
    @GuardedBy({"mLock"})
    private boolean mIsStatusOverride;
    private final Object mLock;
    @VisibleForTesting
    final IThermalService.Stub mService;
    @GuardedBy({"mLock"})
    private int mStatus;
    @GuardedBy({"mLock"})
    private ArrayMap<String, Temperature> mTemperatureMap;
    @GuardedBy({"mLock"})
    private final RemoteCallbackList<IThermalEventListener> mThermalEventListeners;
    @GuardedBy({"mLock"})
    private final RemoteCallbackList<IThermalStatusListener> mThermalStatusListeners;

    public ThermalManagerService(Context context) {
        this(context, null);
    }

    @VisibleForTesting
    ThermalManagerService(Context context, ThermalHalWrapper halWrapper) {
        super(context);
        this.mLock = new Object();
        this.mThermalEventListeners = new RemoteCallbackList<>();
        this.mThermalStatusListeners = new RemoteCallbackList<>();
        this.mTemperatureMap = new ArrayMap<>();
        this.mHalReady = new AtomicBoolean();
        this.mService = new IThermalService.Stub() { // from class: com.android.server.power.ThermalManagerService.1
            public boolean registerThermalEventListener(IThermalEventListener listener) {
                ThermalManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                synchronized (ThermalManagerService.this.mLock) {
                    long token = Binder.clearCallingIdentity();
                    if (ThermalManagerService.this.mThermalEventListeners.register(listener, null)) {
                        ThermalManagerService.this.postEventListenerCurrentTemperatures(listener, null);
                        Binder.restoreCallingIdentity(token);
                        return true;
                    }
                    Binder.restoreCallingIdentity(token);
                    return false;
                }
            }

            public boolean registerThermalEventListenerWithType(IThermalEventListener listener, int type) {
                ThermalManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                synchronized (ThermalManagerService.this.mLock) {
                    long token = Binder.clearCallingIdentity();
                    if (ThermalManagerService.this.mThermalEventListeners.register(listener, new Integer(type))) {
                        ThermalManagerService.this.postEventListenerCurrentTemperatures(listener, new Integer(type));
                        Binder.restoreCallingIdentity(token);
                        return true;
                    }
                    Binder.restoreCallingIdentity(token);
                    return false;
                }
            }

            public boolean unregisterThermalEventListener(IThermalEventListener listener) {
                boolean unregister;
                ThermalManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                synchronized (ThermalManagerService.this.mLock) {
                    long token = Binder.clearCallingIdentity();
                    unregister = ThermalManagerService.this.mThermalEventListeners.unregister(listener);
                    Binder.restoreCallingIdentity(token);
                }
                return unregister;
            }

            public List<Temperature> getCurrentTemperatures() {
                ThermalManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                long token = Binder.clearCallingIdentity();
                try {
                    if (ThermalManagerService.this.mHalReady.get()) {
                        return ThermalManagerService.this.mHalWrapper.getCurrentTemperatures(false, 0);
                    }
                    return new ArrayList();
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            public List<Temperature> getCurrentTemperaturesWithType(int type) {
                ThermalManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                long token = Binder.clearCallingIdentity();
                try {
                    if (ThermalManagerService.this.mHalReady.get()) {
                        return ThermalManagerService.this.mHalWrapper.getCurrentTemperatures(true, type);
                    }
                    return new ArrayList();
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            public boolean registerThermalStatusListener(IThermalStatusListener listener) {
                synchronized (ThermalManagerService.this.mLock) {
                    long token = Binder.clearCallingIdentity();
                    if (ThermalManagerService.this.mThermalStatusListeners.register(listener)) {
                        ThermalManagerService.this.postStatusListener(listener);
                        Binder.restoreCallingIdentity(token);
                        return true;
                    }
                    Binder.restoreCallingIdentity(token);
                    return false;
                }
            }

            public boolean unregisterThermalStatusListener(IThermalStatusListener listener) {
                boolean unregister;
                synchronized (ThermalManagerService.this.mLock) {
                    long token = Binder.clearCallingIdentity();
                    unregister = ThermalManagerService.this.mThermalStatusListeners.unregister(listener);
                    Binder.restoreCallingIdentity(token);
                }
                return unregister;
            }

            public int getCurrentThermalStatus() {
                int i;
                synchronized (ThermalManagerService.this.mLock) {
                    long token = Binder.clearCallingIdentity();
                    if (Temperature.isValidStatus(ThermalManagerService.this.mStatus)) {
                        i = ThermalManagerService.this.mStatus;
                    } else {
                        i = 0;
                    }
                    Binder.restoreCallingIdentity(token);
                }
                return i;
            }

            public List<CoolingDevice> getCurrentCoolingDevices() {
                ThermalManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                long token = Binder.clearCallingIdentity();
                try {
                    if (ThermalManagerService.this.mHalReady.get()) {
                        return ThermalManagerService.this.mHalWrapper.getCurrentCoolingDevices(false, 0);
                    }
                    return new ArrayList();
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            public List<CoolingDevice> getCurrentCoolingDevicesWithType(int type) {
                ThermalManagerService.this.getContext().enforceCallingOrSelfPermission("android.permission.DEVICE_POWER", null);
                long token = Binder.clearCallingIdentity();
                try {
                    if (ThermalManagerService.this.mHalReady.get()) {
                        return ThermalManagerService.this.mHalWrapper.getCurrentCoolingDevices(true, type);
                    }
                    return new ArrayList();
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            private void dumpItemsLocked(PrintWriter pw, String prefix, Collection<?> items) {
                Iterator iterator = items.iterator();
                while (iterator.hasNext()) {
                    pw.println(prefix + iterator.next().toString());
                }
            }

            public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
                if (!DumpUtils.checkDumpPermission(ThermalManagerService.this.getContext(), ThermalManagerService.TAG, pw)) {
                    return;
                }
                long token = Binder.clearCallingIdentity();
                try {
                    synchronized (ThermalManagerService.this.mLock) {
                        pw.println("IsStatusOverride: " + ThermalManagerService.this.mIsStatusOverride);
                        pw.println("ThermalEventListeners:");
                        ThermalManagerService.this.mThermalEventListeners.dump(pw, "\t");
                        pw.println("ThermalStatusListeners:");
                        ThermalManagerService.this.mThermalStatusListeners.dump(pw, "\t");
                        pw.println("Thermal Status: " + ThermalManagerService.this.mStatus);
                        pw.println("Cached temperatures:");
                        dumpItemsLocked(pw, "\t", ThermalManagerService.this.mTemperatureMap.values());
                        pw.println("HAL Ready: " + ThermalManagerService.this.mHalReady.get());
                        if (ThermalManagerService.this.mHalReady.get()) {
                            pw.println("HAL connection:");
                            ThermalManagerService.this.mHalWrapper.dump(pw, "\t");
                            pw.println("Current temperatures from HAL:");
                            dumpItemsLocked(pw, "\t", ThermalManagerService.this.mHalWrapper.getCurrentTemperatures(false, 0));
                            pw.println("Current cooling devices from HAL:");
                            dumpItemsLocked(pw, "\t", ThermalManagerService.this.mHalWrapper.getCurrentCoolingDevices(false, 0));
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }

            private boolean isCallerShell() {
                int callingUid = Binder.getCallingUid();
                return callingUid == 2000 || callingUid == 0;
            }

            /* JADX WARN: Multi-variable type inference failed */
            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                if (!isCallerShell()) {
                    Slog.w(ThermalManagerService.TAG, "Only shell is allowed to call thermalservice shell commands");
                } else {
                    new ThermalShellCommand().exec(this, in, out, err, args, callback, resultReceiver);
                }
            }
        };
        this.mHalWrapper = halWrapper;
        this.mStatus = Integer.MIN_VALUE;
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("thermalservice", this.mService);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 550) {
            onActivityManagerReady();
        }
    }

    private void onActivityManagerReady() {
        synchronized (this.mLock) {
            boolean halConnected = this.mHalWrapper != null;
            if (!halConnected) {
                this.mHalWrapper = new ThermalHal20Wrapper();
                halConnected = this.mHalWrapper.connectToHal();
            }
            if (!halConnected) {
                this.mHalWrapper = new ThermalHal11Wrapper();
                halConnected = this.mHalWrapper.connectToHal();
            }
            if (!halConnected) {
                this.mHalWrapper = new ThermalHal10Wrapper();
                halConnected = this.mHalWrapper.connectToHal();
            }
            this.mHalWrapper.setCallback(new ThermalHalWrapper.TemperatureChangedCallback() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$9JFHCKCwrnUIYoXDsqNamhlY5VU
                @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper.TemperatureChangedCallback
                public final void onValues(Temperature temperature) {
                    ThermalManagerService.this.onTemperatureChangedCallback(temperature);
                }
            });
            if (halConnected) {
                List<Temperature> temperatures = this.mHalWrapper.getCurrentTemperatures(false, 0);
                int count = temperatures.size();
                for (int i = 0; i < count; i++) {
                    onTemperatureChanged(temperatures.get(i), false);
                }
                onTemperatureMapChangedLocked();
                this.mHalReady.set(true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postStatusListener(final IThermalStatusListener listener) {
        boolean thermalCallbackQueued = FgThread.getHandler().post(new Runnable() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$ZPQKzo9ZjU-hL4QYH693hWuTqjk
            @Override // java.lang.Runnable
            public final void run() {
                ThermalManagerService.this.lambda$postStatusListener$0$ThermalManagerService(listener);
            }
        });
        if (!thermalCallbackQueued) {
            Slog.e(TAG, "Thermal callback failed to queue");
        }
    }

    public /* synthetic */ void lambda$postStatusListener$0$ThermalManagerService(IThermalStatusListener listener) {
        try {
            listener.onStatusChange(this.mStatus);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Thermal callback failed to call", e);
        }
    }

    private void notifyStatusListenersLocked() {
        if (!Temperature.isValidStatus(this.mStatus)) {
            return;
        }
        int length = this.mThermalStatusListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                IThermalStatusListener listener = this.mThermalStatusListeners.getBroadcastItem(i);
                postStatusListener(listener);
            } finally {
                this.mThermalStatusListeners.finishBroadcast();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onTemperatureMapChangedLocked() {
        int newStatus = Integer.MIN_VALUE;
        int count = this.mTemperatureMap.size();
        for (int i = 0; i < count; i++) {
            Temperature t = this.mTemperatureMap.valueAt(i);
            if (t.getStatus() >= newStatus) {
                newStatus = t.getStatus();
            }
        }
        if (!this.mIsStatusOverride) {
            setStatusLocked(newStatus);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setStatusLocked(int newStatus) {
        if (newStatus != this.mStatus) {
            this.mStatus = newStatus;
            notifyStatusListenersLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postEventListenerCurrentTemperatures(IThermalEventListener listener, Integer type) {
        synchronized (this.mLock) {
            int count = this.mTemperatureMap.size();
            for (int i = 0; i < count; i++) {
                postEventListener(this.mTemperatureMap.valueAt(i), listener, type);
            }
        }
    }

    private void postEventListener(final Temperature temperature, final IThermalEventListener listener, Integer type) {
        if (type != null && type.intValue() != temperature.getType()) {
            return;
        }
        boolean thermalCallbackQueued = FgThread.getHandler().post(new Runnable() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$x5obtNvJKZxnpguOiQsFBDmBZ4k
            @Override // java.lang.Runnable
            public final void run() {
                ThermalManagerService.lambda$postEventListener$1(listener, temperature);
            }
        });
        if (!thermalCallbackQueued) {
            Slog.e(TAG, "Thermal callback failed to queue");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$postEventListener$1(IThermalEventListener listener, Temperature temperature) {
        try {
            listener.notifyThrottling(temperature);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Thermal callback failed to call", e);
        }
    }

    private void notifyEventListenersLocked(Temperature temperature) {
        int length = this.mThermalEventListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                IThermalEventListener listener = this.mThermalEventListeners.getBroadcastItem(i);
                Integer type = (Integer) this.mThermalEventListeners.getBroadcastCookie(i);
                postEventListener(temperature, listener, type);
            } catch (Throwable th) {
                this.mThermalEventListeners.finishBroadcast();
                throw th;
            }
        }
        this.mThermalEventListeners.finishBroadcast();
        EventLog.writeEvent((int) EventLogTags.THERMAL_CHANGED, temperature.getName(), Integer.valueOf(temperature.getType()), Float.valueOf(temperature.getValue()), Integer.valueOf(temperature.getStatus()), Integer.valueOf(this.mStatus));
    }

    private void shutdownIfNeeded(Temperature temperature) {
        if (temperature.getStatus() != 6) {
            return;
        }
        PowerManager powerManager = (PowerManager) getContext().getSystemService(PowerManager.class);
        int type = temperature.getType();
        if (type != 0 && type != 1) {
            if (type == 2) {
                powerManager.shutdown(false, "thermal,battery", false);
                return;
            } else if (type != 3 && type != 9) {
                return;
            }
        }
        powerManager.shutdown(false, "thermal", false);
    }

    private void onTemperatureChanged(Temperature temperature, boolean sendStatus) {
        shutdownIfNeeded(temperature);
        synchronized (this.mLock) {
            Temperature old = this.mTemperatureMap.put(temperature.getName(), temperature);
            if (old != null) {
                if (old.getStatus() != temperature.getStatus()) {
                    notifyEventListenersLocked(temperature);
                }
            } else {
                notifyEventListenersLocked(temperature);
            }
            if (sendStatus) {
                onTemperatureMapChangedLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onTemperatureChangedCallback(Temperature temperature) {
        long token = Binder.clearCallingIdentity();
        try {
            onTemperatureChanged(temperature, true);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /* loaded from: classes.dex */
    class ThermalShellCommand extends ShellCommand {
        ThermalShellCommand() {
        }

        public int onCommand(String cmd) {
            String str = cmd != null ? cmd : "";
            char c = 65535;
            int hashCode = str.hashCode();
            if (hashCode != 108404047) {
                if (hashCode == 385515795 && str.equals("override-status")) {
                    c = 0;
                }
            } else if (str.equals("reset")) {
                c = 1;
            }
            if (c != 0) {
                if (c == 1) {
                    return runReset();
                }
                return handleDefaultCommands(cmd);
            }
            return runOverrideStatus();
        }

        private int runReset() {
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (ThermalManagerService.this.mLock) {
                    ThermalManagerService.this.mIsStatusOverride = false;
                    ThermalManagerService.this.onTemperatureMapChangedLocked();
                }
                return 0;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private int runOverrideStatus() {
            PrintWriter pw;
            long token = Binder.clearCallingIdentity();
            try {
                pw = getOutPrintWriter();
                int status = Integer.parseInt(getNextArgRequired());
                if (Temperature.isValidStatus(status)) {
                    synchronized (ThermalManagerService.this.mLock) {
                        ThermalManagerService.this.mIsStatusOverride = true;
                        ThermalManagerService.this.setStatusLocked(status);
                    }
                    return 0;
                }
                pw.println("Invalid status: " + status);
                return -1;
            } catch (RuntimeException ex) {
                pw.println("Error: " + ex.toString());
                return -1;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Thermal service (thermalservice) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("");
            pw.println("  override-status STATUS");
            pw.println("    sets and locks the thermal status of the device to STATUS.");
            pw.println("    status code is defined in android.os.Temperature.");
            pw.println("  reset");
            pw.println("    unlocks the thermal status of the device.");
            pw.println();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static abstract class ThermalHalWrapper {
        protected static final String TAG = ThermalHalWrapper.class.getSimpleName();
        protected static final int THERMAL_HAL_DEATH_COOKIE = 5612;
        protected TemperatureChangedCallback mCallback;
        protected final Object mHalLock = new Object();

        /* JADX INFO: Access modifiers changed from: package-private */
        @FunctionalInterface
        /* loaded from: classes.dex */
        public interface TemperatureChangedCallback {
            void onValues(Temperature temperature);
        }

        protected abstract boolean connectToHal();

        protected abstract void dump(PrintWriter printWriter, String str);

        protected abstract List<CoolingDevice> getCurrentCoolingDevices(boolean z, int i);

        protected abstract List<Temperature> getCurrentTemperatures(boolean z, int i);

        ThermalHalWrapper() {
        }

        @VisibleForTesting
        protected void setCallback(TemperatureChangedCallback cb) {
            this.mCallback = cb;
        }

        protected void resendCurrentTemperatures() {
            synchronized (this.mHalLock) {
                List<Temperature> temperatures = getCurrentTemperatures(false, 0);
                int count = temperatures.size();
                for (int i = 0; i < count; i++) {
                    this.mCallback.onValues(temperatures.get(i));
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* loaded from: classes.dex */
        public final class DeathRecipient implements IHwBinder.DeathRecipient {
            DeathRecipient() {
            }

            public void serviceDied(long cookie) {
                if (cookie == 5612) {
                    String str = ThermalHalWrapper.TAG;
                    Slog.e(str, "Thermal HAL service died cookie: " + cookie);
                    synchronized (ThermalHalWrapper.this.mHalLock) {
                        ThermalHalWrapper.this.connectToHal();
                        ThermalHalWrapper.this.resendCurrentTemperatures();
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class ThermalHal10Wrapper extends ThermalHalWrapper {
        @GuardedBy({"mHalLock"})
        private IThermal mThermalHal10 = null;

        ThermalHal10Wrapper() {
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected List<Temperature> getCurrentTemperatures(final boolean shouldFilter, final int type) {
            synchronized (this.mHalLock) {
                final List<Temperature> ret = new ArrayList<>();
                if (this.mThermalHal10 == null) {
                    return ret;
                }
                try {
                    this.mThermalHal10.getTemperatures(new IThermal.getTemperaturesCallback() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$ThermalHal10Wrapper$ZThS0zQGVCG_VVOcIkHbcXIsWAI
                        public final void onValues(ThermalStatus thermalStatus, ArrayList arrayList) {
                            ThermalManagerService.ThermalHal10Wrapper.lambda$getCurrentTemperatures$0(shouldFilter, type, ret, thermalStatus, arrayList);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentTemperatures, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$getCurrentTemperatures$0(boolean shouldFilter, int type, List ret, ThermalStatus status, ArrayList temperatures) {
            if (status.code == 0) {
                Iterator it = temperatures.iterator();
                while (it.hasNext()) {
                    android.hardware.thermal.V1_0.Temperature temperature = (android.hardware.thermal.V1_0.Temperature) it.next();
                    if (!shouldFilter || type == temperature.type) {
                        ret.add(new Temperature(temperature.currentValue, temperature.type, temperature.name, 0));
                    }
                }
                return;
            }
            String str = TAG;
            Slog.e(str, "Couldn't get temperatures because of HAL error: " + status.debugMessage);
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected List<CoolingDevice> getCurrentCoolingDevices(final boolean shouldFilter, final int type) {
            synchronized (this.mHalLock) {
                final List<CoolingDevice> ret = new ArrayList<>();
                if (this.mThermalHal10 == null) {
                    return ret;
                }
                try {
                    this.mThermalHal10.getCoolingDevices(new IThermal.getCoolingDevicesCallback() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$ThermalHal10Wrapper$sQ56quU7WPYADl2qUYrc2aj5zVg
                        public final void onValues(ThermalStatus thermalStatus, ArrayList arrayList) {
                            ThermalManagerService.ThermalHal10Wrapper.lambda$getCurrentCoolingDevices$1(shouldFilter, type, ret, thermalStatus, arrayList);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$getCurrentCoolingDevices$1(boolean shouldFilter, int type, List ret, ThermalStatus status, ArrayList coolingDevices) {
            if (status.code == 0) {
                Iterator it = coolingDevices.iterator();
                while (it.hasNext()) {
                    android.hardware.thermal.V1_0.CoolingDevice coolingDevice = (android.hardware.thermal.V1_0.CoolingDevice) it.next();
                    if (!shouldFilter || type == coolingDevice.type) {
                        ret.add(new CoolingDevice(coolingDevice.currentValue, coolingDevice.type, coolingDevice.name));
                    }
                }
                return;
            }
            String str = TAG;
            Slog.e(str, "Couldn't get cooling device because of HAL error: " + status.debugMessage);
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected boolean connectToHal() {
            boolean z;
            synchronized (this.mHalLock) {
                try {
                    this.mThermalHal10 = IThermal.getService();
                    this.mThermalHal10.linkToDeath(new ThermalHalWrapper.DeathRecipient(), 5612L);
                    Slog.i(TAG, "Thermal HAL 1.0 service connected, no thermal call back will be called due to legacy API.");
                } catch (RemoteException | NoSuchElementException e) {
                    Slog.e(TAG, "Thermal HAL 1.0 service not connected.");
                    this.mThermalHal10 = null;
                }
                z = this.mThermalHal10 != null;
            }
            return z;
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected void dump(PrintWriter pw, String prefix) {
            synchronized (this.mHalLock) {
                pw.print(prefix);
                StringBuilder sb = new StringBuilder();
                sb.append("ThermalHAL 1.0 connected: ");
                sb.append(this.mThermalHal10 != null ? UiModeManagerService.Shell.NIGHT_MODE_STR_YES : UiModeManagerService.Shell.NIGHT_MODE_STR_NO);
                pw.println(sb.toString());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class ThermalHal11Wrapper extends ThermalHalWrapper {
        @GuardedBy({"mHalLock"})
        private android.hardware.thermal.V1_1.IThermal mThermalHal11 = null;
        private final IThermalCallback.Stub mThermalCallback11 = new IThermalCallback.Stub() { // from class: com.android.server.power.ThermalManagerService.ThermalHal11Wrapper.1
            public void notifyThrottling(boolean isThrottling, android.hardware.thermal.V1_0.Temperature temperature) {
                Temperature thermalSvcTemp = new Temperature(temperature.currentValue, temperature.type, temperature.name, isThrottling ? 3 : 0);
                long token = Binder.clearCallingIdentity();
                try {
                    ThermalHal11Wrapper.this.mCallback.onValues(thermalSvcTemp);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };

        ThermalHal11Wrapper() {
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected List<Temperature> getCurrentTemperatures(final boolean shouldFilter, final int type) {
            synchronized (this.mHalLock) {
                final List<Temperature> ret = new ArrayList<>();
                if (this.mThermalHal11 == null) {
                    return ret;
                }
                try {
                    this.mThermalHal11.getTemperatures(new IThermal.getTemperaturesCallback() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$ThermalHal11Wrapper$ewP6gmklD7-k-bU5IqeZFG8-BZk
                        public final void onValues(ThermalStatus thermalStatus, ArrayList arrayList) {
                            ThermalManagerService.ThermalHal11Wrapper.lambda$getCurrentTemperatures$0(shouldFilter, type, ret, thermalStatus, arrayList);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentTemperatures, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$getCurrentTemperatures$0(boolean shouldFilter, int type, List ret, ThermalStatus status, ArrayList temperatures) {
            if (status.code == 0) {
                Iterator it = temperatures.iterator();
                while (it.hasNext()) {
                    android.hardware.thermal.V1_0.Temperature temperature = (android.hardware.thermal.V1_0.Temperature) it.next();
                    if (!shouldFilter || type == temperature.type) {
                        ret.add(new Temperature(temperature.currentValue, temperature.type, temperature.name, 0));
                    }
                }
                return;
            }
            String str = TAG;
            Slog.e(str, "Couldn't get temperatures because of HAL error: " + status.debugMessage);
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected List<CoolingDevice> getCurrentCoolingDevices(final boolean shouldFilter, final int type) {
            synchronized (this.mHalLock) {
                final List<CoolingDevice> ret = new ArrayList<>();
                if (this.mThermalHal11 == null) {
                    return ret;
                }
                try {
                    this.mThermalHal11.getCoolingDevices(new IThermal.getCoolingDevicesCallback() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$ThermalHal11Wrapper$h5fsSA4CwK_Xli1jHnpETKQH7nA
                        public final void onValues(ThermalStatus thermalStatus, ArrayList arrayList) {
                            ThermalManagerService.ThermalHal11Wrapper.lambda$getCurrentCoolingDevices$1(shouldFilter, type, ret, thermalStatus, arrayList);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$getCurrentCoolingDevices$1(boolean shouldFilter, int type, List ret, ThermalStatus status, ArrayList coolingDevices) {
            if (status.code == 0) {
                Iterator it = coolingDevices.iterator();
                while (it.hasNext()) {
                    android.hardware.thermal.V1_0.CoolingDevice coolingDevice = (android.hardware.thermal.V1_0.CoolingDevice) it.next();
                    if (!shouldFilter || type == coolingDevice.type) {
                        ret.add(new CoolingDevice(coolingDevice.currentValue, coolingDevice.type, coolingDevice.name));
                    }
                }
                return;
            }
            String str = TAG;
            Slog.e(str, "Couldn't get cooling device because of HAL error: " + status.debugMessage);
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected boolean connectToHal() {
            boolean z;
            synchronized (this.mHalLock) {
                try {
                    this.mThermalHal11 = android.hardware.thermal.V1_1.IThermal.getService();
                    this.mThermalHal11.linkToDeath(new ThermalHalWrapper.DeathRecipient(), 5612L);
                    this.mThermalHal11.registerThermalCallback(this.mThermalCallback11);
                    Slog.i(TAG, "Thermal HAL 1.1 service connected, limited thermal functions due to legacy API.");
                } catch (RemoteException | NoSuchElementException e) {
                    Slog.e(TAG, "Thermal HAL 1.1 service not connected.");
                    this.mThermalHal11 = null;
                }
                z = this.mThermalHal11 != null;
            }
            return z;
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected void dump(PrintWriter pw, String prefix) {
            synchronized (this.mHalLock) {
                pw.print(prefix);
                StringBuilder sb = new StringBuilder();
                sb.append("ThermalHAL 1.1 connected: ");
                sb.append(this.mThermalHal11 != null ? UiModeManagerService.Shell.NIGHT_MODE_STR_YES : UiModeManagerService.Shell.NIGHT_MODE_STR_NO);
                pw.println(sb.toString());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class ThermalHal20Wrapper extends ThermalHalWrapper {
        @GuardedBy({"mHalLock"})
        private android.hardware.thermal.V2_0.IThermal mThermalHal20 = null;
        private final IThermalChangedCallback.Stub mThermalCallback20 = new IThermalChangedCallback.Stub() { // from class: com.android.server.power.ThermalManagerService.ThermalHal20Wrapper.1
            public void notifyThrottling(android.hardware.thermal.V2_0.Temperature temperature) {
                Temperature thermalSvcTemp = new Temperature(temperature.value, temperature.type, temperature.name, temperature.throttlingStatus);
                long token = Binder.clearCallingIdentity();
                try {
                    ThermalHal20Wrapper.this.mCallback.onValues(thermalSvcTemp);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };

        ThermalHal20Wrapper() {
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected List<Temperature> getCurrentTemperatures(boolean shouldFilter, int type) {
            synchronized (this.mHalLock) {
                final List<Temperature> ret = new ArrayList<>();
                if (this.mThermalHal20 == null) {
                    return ret;
                }
                try {
                    this.mThermalHal20.getCurrentTemperatures(shouldFilter, type, new IThermal.getCurrentTemperaturesCallback() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$ThermalHal20Wrapper$R9S8YWn8x1F3V2TOvXtmky1V44Q
                        public final void onValues(ThermalStatus thermalStatus, ArrayList arrayList) {
                            ThermalManagerService.ThermalHal20Wrapper.lambda$getCurrentTemperatures$0(ret, thermalStatus, arrayList);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentTemperatures, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$getCurrentTemperatures$0(List ret, ThermalStatus status, ArrayList temperatures) {
            if (status.code == 0) {
                Iterator it = temperatures.iterator();
                while (it.hasNext()) {
                    android.hardware.thermal.V2_0.Temperature temperature = (android.hardware.thermal.V2_0.Temperature) it.next();
                    ret.add(new Temperature(temperature.value, temperature.type, temperature.name, temperature.throttlingStatus));
                }
                return;
            }
            String str = TAG;
            Slog.e(str, "Couldn't get temperatures because of HAL error: " + status.debugMessage);
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected List<CoolingDevice> getCurrentCoolingDevices(boolean shouldFilter, int type) {
            synchronized (this.mHalLock) {
                final List<CoolingDevice> ret = new ArrayList<>();
                if (this.mThermalHal20 == null) {
                    return ret;
                }
                try {
                    this.mThermalHal20.getCurrentCoolingDevices(shouldFilter, type, new IThermal.getCurrentCoolingDevicesCallback() { // from class: com.android.server.power.-$$Lambda$ThermalManagerService$ThermalHal20Wrapper$dRsq86SXVH7z342fbs2U36cr67I
                        public final void onValues(ThermalStatus thermalStatus, ArrayList arrayList) {
                            ThermalManagerService.ThermalHal20Wrapper.lambda$getCurrentCoolingDevices$1(ret, thermalStatus, arrayList);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't getCurrentCoolingDevices, reconnecting...", e);
                    connectToHal();
                }
                return ret;
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$getCurrentCoolingDevices$1(List ret, ThermalStatus status, ArrayList coolingDevices) {
            if (status.code == 0) {
                Iterator it = coolingDevices.iterator();
                while (it.hasNext()) {
                    android.hardware.thermal.V2_0.CoolingDevice coolingDevice = (android.hardware.thermal.V2_0.CoolingDevice) it.next();
                    ret.add(new CoolingDevice(coolingDevice.value, coolingDevice.type, coolingDevice.name));
                }
                return;
            }
            String str = TAG;
            Slog.e(str, "Couldn't get cooling device because of HAL error: " + status.debugMessage);
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected boolean connectToHal() {
            boolean z;
            synchronized (this.mHalLock) {
                try {
                    this.mThermalHal20 = android.hardware.thermal.V2_0.IThermal.getService();
                    this.mThermalHal20.linkToDeath(new ThermalHalWrapper.DeathRecipient(), 5612L);
                    this.mThermalHal20.registerThermalChangedCallback(this.mThermalCallback20, false, 0);
                    Slog.i(TAG, "Thermal HAL 2.0 service connected.");
                } catch (RemoteException | NoSuchElementException e) {
                    Slog.e(TAG, "Thermal HAL 2.0 service not connected.");
                    this.mThermalHal20 = null;
                }
                z = this.mThermalHal20 != null;
            }
            return z;
        }

        @Override // com.android.server.power.ThermalManagerService.ThermalHalWrapper
        protected void dump(PrintWriter pw, String prefix) {
            synchronized (this.mHalLock) {
                pw.print(prefix);
                StringBuilder sb = new StringBuilder();
                sb.append("ThermalHAL 2.0 connected: ");
                sb.append(this.mThermalHal20 != null ? UiModeManagerService.Shell.NIGHT_MODE_STR_YES : UiModeManagerService.Shell.NIGHT_MODE_STR_NO);
                pw.println(sb.toString());
            }
        }
    }
}
