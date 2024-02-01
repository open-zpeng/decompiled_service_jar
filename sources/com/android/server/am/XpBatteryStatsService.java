package com.android.server.am;

import android.bluetooth.BluetoothActivityEnergyInfo;
import android.content.Context;
import android.net.wifi.WifiActivityEnergyInfo;
import android.os.BatteryManagerInternal;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerSaveState;
import android.os.ServiceManager;
import android.os.WorkSource;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.connectivity.WifiBatteryStats;
import android.os.health.HealthStatsParceler;
import android.os.health.HealthStatsWriter;
import android.os.health.UidHealthStats;
import android.telephony.ModemActivityInfo;
import android.telephony.SignalStrength;
import android.util.Slog;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.RailStats;
import com.android.internal.os.RpmStats;
import com.android.internal.os.XpBatteryStatsImpl;
import com.android.server.LocalServices;
import java.io.File;
import libcore.util.EmptyArray;

/* loaded from: classes.dex */
public final class XpBatteryStatsService extends BatteryStatsService {
    static final String TAG = "XpBatteryStatsService";
    private static IBatteryStats sService;
    private final Context mContext;
    final XpBatteryStatsImpl mStats = new XpBatteryStatsImpl();
    private final BatteryExternalStatsWorker mWorker;

    /* JADX INFO: Access modifiers changed from: package-private */
    public XpBatteryStatsService(Context context, File systemDir, Handler handler) {
        this.mContext = context;
        this.mWorker = new BatteryExternalStatsWorker(context, this.mStats);
        this.mStats.setExternalStatsSyncLocked(this.mWorker);
        this.mStats.setRadioScanningTimeoutLocked(this.mContext.getResources().getInteger(17694876) * 1000);
        this.mStats.setPowerProfileLocked(new PowerProfile(context));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void noteProcessStart(String name, int uid) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void noteProcessCrash(String name, int uid) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void noteProcessAnr(String name, int uid) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void noteUidProcessState(int uid, int state) {
    }

    void noteJobsDeferred(int uid, int numDeferred, long sinceLast) {
    }

    public void noteWakupAlarm(String name, int uid, WorkSource workSource, String tag) {
    }

    public void noteAlarmStart(String name, WorkSource workSource, int uid) {
    }

    public void noteAlarmFinish(String name, WorkSource workSource, int uid) {
    }

    public void notePackageInstalled(String pkgName, long versionCode) {
    }

    public void notePackageUninstalled(String pkgName) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void noteProcessFinish(String name, int uid) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeUid(int uid) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onCleanupUser(int userId) {
    }

    void onUserRemoved(int userId) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addIsolatedUid(int isolatedUid, int appUid) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeIsolatedUid(int isolatedUid, int appUid) {
    }

    public void publish() {
        LocalServices.addService(BatteryStatsInternal.class, new LocalService());
        ServiceManager.addService("batterystats", asBinder());
    }

    public void systemServicesReady() {
    }

    public void initPowerManagement() {
    }

    public void shutdown() {
    }

    public BatteryStatsImpl getActiveStatistics() {
        return this.mStats;
    }

    public boolean isOnBattery() {
        return this.mStats.isOnBattery();
    }

    public void scheduleWriteToDisk() {
    }

    public static IBatteryStats getService() {
        IBatteryStats iBatteryStats = sService;
        if (iBatteryStats != null) {
            return iBatteryStats;
        }
        IBinder b = ServiceManager.getService("batterystats");
        sService = asInterface(b);
        return sService;
    }

    public int getServiceType() {
        return 9;
    }

    public void onLowPowerModeChanged(PowerSaveState state) {
    }

    public void noteStartSensor(int uid, int sensor) {
    }

    public void noteStopSensor(int uid, int sensor) {
    }

    public void noteStartVideo(int uid) {
    }

    public void noteStopVideo(int uid) {
    }

    public void noteStartAudio(int uid) {
    }

    public void noteStopAudio(int uid) {
    }

    public void noteResetVideo() {
    }

    public void noteResetAudio() {
    }

    public void noteFlashlightOn(int uid) {
    }

    public void noteFlashlightOff(int uid) {
    }

    public void noteStartCamera(int uid) {
    }

    public void noteStopCamera(int uid) {
    }

    public void noteResetCamera() {
    }

    public void noteResetFlashlight() {
    }

    public byte[] getStatistics() {
        return new byte[0];
    }

    public ParcelFileDescriptor getStatisticsStream() {
        return null;
    }

    public boolean isCharging() {
        return ((BatteryManagerInternal) LocalServices.getService(BatteryManagerInternal.class)).isPowered(7);
    }

    public long computeBatteryTimeRemaining() {
        return 0L;
    }

    public long computeChargeTimeRemaining() {
        return 0L;
    }

    public void noteEvent(int code, String name, int uid) {
    }

    public void noteSyncStart(String name, int uid) {
    }

    public void noteSyncFinish(String name, int uid) {
    }

    public void noteJobStart(String name, int uid, int standbyBucket, int jobid) {
    }

    public void noteJobFinish(String name, int uid, int stopReason, int standbyBucket, int jobid) {
    }

    public void noteStartWakelock(int uid, int pid, String name, String historyName, int type, boolean unimportantForLogging) {
    }

    public void noteStopWakelock(int uid, int pid, String name, String historyName, int type) {
    }

    public void noteStartWakelockFromSource(WorkSource ws, int pid, String name, String historyName, int type, boolean unimportantForLogging) {
    }

    public void noteChangeWakelockFromSource(WorkSource ws, int pid, String name, String histyoryName, int type, WorkSource newWs, int newPid, String newName, String newHistoryName, int newType, boolean newUnimportantForLogging) {
    }

    public void noteStopWakelockFromSource(WorkSource ws, int pid, String name, String historyName, int type) {
    }

    public void noteLongPartialWakelockStart(String name, String historyName, int uid) {
    }

    public void noteLongPartialWakelockStartFromSource(String name, String historyName, WorkSource workSource) {
    }

    public void noteLongPartialWakelockFinish(String name, String historyName, int uid) {
    }

    public void noteLongPartialWakelockFinishFromSource(String name, String historyName, WorkSource workSource) {
    }

    public void noteVibratorOn(int uid, long durationMillis) {
    }

    public void noteVibratorOff(int uid) {
    }

    public void noteGpsChanged(WorkSource oldSource, WorkSource newSource) {
    }

    public void noteGpsSignalQuality(int signalLevel) {
    }

    public void noteScreenState(int state) {
    }

    public void noteScreenBrightness(int brightness) {
    }

    public void noteUserActivity(int uid, int event) {
    }

    public void noteWakeUp(String reason, int reasonUid) {
    }

    public void noteInteractive(boolean interactive) {
    }

    public void noteConnectivityChanged(int type, String extra) {
    }

    public void noteMobileRadioPowerState(int powerState, long timestampNs, int uid) {
    }

    public void notePhoneOn() {
    }

    public void notePhoneOff() {
    }

    public void notePhoneSignalStrength(SignalStrength signalStrength) {
    }

    public void notePhoneDataConnectionState(int dataType, boolean hasData, int serviceType) {
    }

    public void notePhoneState(int phoneState) {
    }

    public void noteWifiOn() {
    }

    public void noteWifiOff() {
    }

    public void noteWifiRunning(WorkSource ws) {
    }

    public void noteWifiRunningChanged(WorkSource oldWs, WorkSource newWs) {
    }

    public void noteWifiStopped(WorkSource ws) {
    }

    public void noteWifiState(int wifiState, String accessPoint) {
    }

    public void noteWifiSupplicantStateChanged(int supplState, boolean failedAuth) {
    }

    public void noteWifiRssiChanged(int newRssi) {
    }

    public void noteFullWifiLockAcquired(int uid) {
    }

    public void noteFullWifiLockReleased(int uid) {
    }

    public void noteWifiScanStarted(int uid) {
    }

    public void noteWifiScanStopped(int uid) {
    }

    public void noteWifiMulticastEnabled(int uid) {
    }

    public void noteWifiMulticastDisabled(int uid) {
    }

    public void noteFullWifiLockAcquiredFromSource(WorkSource ws) {
    }

    public void noteFullWifiLockReleasedFromSource(WorkSource ws) {
    }

    public void noteWifiScanStartedFromSource(WorkSource ws) {
    }

    public void noteWifiScanStoppedFromSource(WorkSource ws) {
    }

    public void noteWifiBatchedScanStartedFromSource(WorkSource ws, int csph) {
    }

    public void noteWifiBatchedScanStoppedFromSource(WorkSource ws) {
    }

    public void noteWifiRadioPowerState(int powerState, long timestampNs, int uid) {
    }

    public void noteNetworkInterfaceType(String iface, int type) {
    }

    public void noteNetworkStatsEnabled() {
    }

    public void noteDeviceIdleMode(int mode, String activeReason, int activeUid) {
    }

    public void setBatteryState(int status, int health, int plugType, int level, int temp, int volt, int chargeUAh, int chargeFullUAh) {
    }

    public long getAwakeTimeBattery() {
        return 0L;
    }

    public long getAwakeTimePlugged() {
        return 0L;
    }

    public void noteBleScanStarted(WorkSource ws, boolean isUnoptimized) {
    }

    public void noteBleScanStopped(WorkSource ws, boolean isUnoptimized) {
    }

    public void noteResetBleScan() {
    }

    public void noteBleScanResults(WorkSource ws, int numNewResults) {
    }

    public CellularBatteryStats getCellularBatteryStats() {
        return null;
    }

    public WifiBatteryStats getWifiBatteryStats() {
        return null;
    }

    public GpsBatteryStats getGpsBatteryStats() {
        return null;
    }

    public HealthStatsParceler takeUidSnapshot(int requestUid) {
        HealthStatsParceler healthStatsForUidLocked;
        if (requestUid != Binder.getCallingUid()) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.BATTERY_STATS", null);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            try {
                synchronized (this.mStats) {
                    healthStatsForUidLocked = getHealthStatsForUidLocked(requestUid);
                }
                return healthStatsForUidLocked;
            } catch (Exception ex) {
                Slog.w(TAG, "Crashed while writing for takeUidSnapshot(" + requestUid + ")", ex);
                throw ex;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    HealthStatsParceler getHealthStatsForUidLocked(int requestUid) {
        HealthStatsBatteryStatsWriter writer = new HealthStatsBatteryStatsWriter();
        HealthStatsWriter uidWriter = new HealthStatsWriter(UidHealthStats.CONSTANTS);
        BatteryStats.Uid uid = (BatteryStats.Uid) this.mStats.getUidStats().get(requestUid);
        if (uid != null) {
            writer.writeUid(uidWriter, this.mStats, uid);
        }
        return new HealthStatsParceler(uidWriter);
    }

    public HealthStatsParceler[] takeUidSnapshots(int[] requestUids) {
        HealthStatsParceler[] healthStatsParcelerArr;
        if (!onlyCaller(requestUids)) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.BATTERY_STATS", null);
        }
        long ident = Binder.clearCallingIdentity();
        try {
            try {
                synchronized (this.mStats) {
                    healthStatsParcelerArr = new HealthStatsParceler[requestUids.length];
                }
                return healthStatsParcelerArr;
            } catch (Exception ex) {
                throw ex;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static boolean onlyCaller(int[] requestUids) {
        int caller = Binder.getCallingUid();
        for (int i : requestUids) {
            if (i != caller) {
                return false;
            }
        }
        return true;
    }

    public void noteBluetoothControllerActivity(BluetoothActivityEnergyInfo info) {
    }

    public void noteModemControllerActivity(ModemActivityInfo info) {
    }

    public void noteWifiControllerActivity(WifiActivityEnergyInfo info) {
    }

    public boolean setChargingStateUpdateDelayMillis(int delay) {
        return false;
    }

    public void fillLowPowerStats(RpmStats rpmStats) {
    }

    public String getPlatformLowPowerStats() {
        return null;
    }

    public String getSubsystemLowPowerStats() {
        return null;
    }

    public void fillRailDataStats(RailStats railStats) {
    }

    /* loaded from: classes.dex */
    private final class LocalService extends BatteryStatsInternal {
        private LocalService() {
        }

        public String[] getWifiIfaces() {
            return EmptyArray.STRING;
        }

        public String[] getMobileIfaces() {
            return EmptyArray.STRING;
        }

        public void noteJobsDeferred(int uid, int numDeferred, long sinceLast) {
        }
    }
}
