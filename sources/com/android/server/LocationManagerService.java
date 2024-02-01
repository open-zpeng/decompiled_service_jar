package com.android.server;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.location.ActivityRecognitionHardware;
import android.location.Address;
import android.location.Criteria;
import android.location.GeocoderParams;
import android.location.Geofence;
import android.location.GnssMeasurementCorrections;
import android.location.IBatchedLocationCallback;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocationManagerService;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.location.AbstractLocationProvider;
import com.android.server.location.ActivityRecognitionProxy;
import com.android.server.location.CallerIdentity;
import com.android.server.location.GeocoderProxy;
import com.android.server.location.GeofenceManager;
import com.android.server.location.GeofenceProxy;
import com.android.server.location.GnssBatchingProvider;
import com.android.server.location.GnssCapabilitiesProvider;
import com.android.server.location.GnssLocationProvider;
import com.android.server.location.GnssMeasurementCorrectionsProvider;
import com.android.server.location.GnssMeasurementsProvider;
import com.android.server.location.GnssNavigationMessageProvider;
import com.android.server.location.GnssStatusListenerHelper;
import com.android.server.location.LocationBlacklist;
import com.android.server.location.LocationFudger;
import com.android.server.location.LocationProviderProxy;
import com.android.server.location.LocationRequestStatistics;
import com.android.server.location.MockProvider;
import com.android.server.location.PassiveProvider;
import com.android.server.location.RemoteListenerHelper;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Function;

/* loaded from: classes.dex */
public class LocationManagerService extends ILocationManager.Stub {
    private static final String ACCESS_LOCATION_EXTRA_COMMANDS = "android.permission.ACCESS_LOCATION_EXTRA_COMMANDS";
    private static final long DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS = 1800000;
    private static final long DEFAULT_LAST_LOCATION_MAX_AGE_MS = 1200000;
    private static final int FOREGROUND_IMPORTANCE_CUTOFF = 125;
    private static final String FUSED_LOCATION_SERVICE_ACTION = "com.android.location.service.FusedLocationProvider";
    private static final long HIGH_POWER_INTERVAL_MS = 300000;
    private static final int MAX_PROVIDER_SCHEDULING_JITTER_MS = 100;
    private static final int MESSAGE_PRINT_INTERVAL = 15;
    private static final long NANOS_PER_MILLI = 1000000;
    private static final String NETWORK_LOCATION_SERVICE_ACTION = "com.android.location.service.v3.NetworkLocationProvider";
    private static final int RESOLUTION_LEVEL_COARSE = 1;
    private static final int RESOLUTION_LEVEL_FINE = 2;
    private static final int RESOLUTION_LEVEL_NONE = 0;
    private static final String WAKELOCK_KEY = "*location*";
    private ActivityManager mActivityManager;
    private AppOpsManager mAppOps;
    @GuardedBy({"mLock"})
    private int mBatterySaverMode;
    private LocationBlacklist mBlacklist;
    private final Context mContext;
    @GuardedBy({"mLock"})
    private String mExtraLocationControllerPackage;
    private boolean mExtraLocationControllerPackageEnabled;
    private GeocoderProxy mGeocodeProvider;
    private GeofenceManager mGeofenceManager;
    @GuardedBy({"mLock"})
    private IBatchedLocationCallback mGnssBatchingCallback;
    @GuardedBy({"mLock"})
    private LinkedListener<IBatchedLocationCallback> mGnssBatchingDeathCallback;
    private GnssBatchingProvider mGnssBatchingProvider;
    private GnssCapabilitiesProvider mGnssCapabilitiesProvider;
    private GnssMeasurementCorrectionsProvider mGnssMeasurementCorrectionsProvider;
    private GnssMeasurementsProvider mGnssMeasurementsProvider;
    private GnssLocationProvider.GnssMetricsProvider mGnssMetricsProvider;
    private GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private GnssStatusListenerHelper mGnssStatusProvider;
    private GnssLocationProvider.GnssSystemInfoProvider mGnssSystemInfoProvider;
    private IGpsGeofenceHardware mGpsGeofenceProxy;
    private LocationFudger mLocationFudger;
    private INetInitiatedListener mNetInitiatedListener;
    private PackageManager mPackageManager;
    private PassiveProvider mPassiveProvider;
    private PowerManager mPowerManager;
    private UserManager mUserManager;
    private static final String TAG = "LocationManagerService";
    public static final boolean D = Log.isLoggable(TAG, 3);
    private static final LocationRequest DEFAULT_LOCATION_REQUEST = new LocationRequest();
    private final Object mLock = new Object();
    @GuardedBy({"mLock"})
    private final ArrayList<LocationProvider> mProviders = new ArrayList<>();
    @GuardedBy({"mLock"})
    private final ArrayList<LocationProvider> mRealProviders = new ArrayList<>();
    @GuardedBy({"mLock"})
    private final HashMap<Object, Receiver> mReceivers = new HashMap<>();
    private final HashMap<String, ArrayList<UpdateRecord>> mRecordsByProvider = new HashMap<>();
    private final LocationRequestStatistics mRequestStatistics = new LocationRequestStatistics();
    @GuardedBy({"mLock"})
    private final HashMap<String, Location> mLastLocation = new HashMap<>();
    @GuardedBy({"mLock"})
    private final HashMap<String, Location> mLastLocationCoarseInterval = new HashMap<>();
    private final ArraySet<String> mBackgroundThrottlePackageWhitelist = new ArraySet<>();
    private final ArraySet<String> mIgnoreSettingsPackageWhitelist = new ArraySet<>();
    @GuardedBy({"mLock"})
    private final ArrayMap<IBinder, LinkedListener<IGnssMeasurementsListener>> mGnssMeasurementsListeners = new ArrayMap<>();
    @GuardedBy({"mLock"})
    private final ArrayMap<IBinder, LinkedListener<IGnssNavigationMessageListener>> mGnssNavigationMessageListeners = new ArrayMap<>();
    @GuardedBy({"mLock"})
    private final ArrayMap<IBinder, LinkedListener<IGnssStatusListener>> mGnssStatusListeners = new ArrayMap<>();
    private int mCurrentUserId = 0;
    private int[] mCurrentUserProfiles = {0};
    @GuardedBy({"mLock"})
    private boolean mGnssBatchingInProgress = false;
    @GuardedBy({"mLock"})
    private int mDroppingPrint = 0;
    private final Handler mHandler = FgThread.getHandler();
    @GuardedBy({"mLock"})
    private final LocationUsageLogger mLocationUsageLogger = new LocationUsageLogger();

    public LocationManagerService(Context context) {
        this.mContext = context;
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.setLocationPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.-$$Lambda$LocationManagerService$bojY6dMaI07zh6_sF7ERxgmk6U0
            public final String[] getPackages(int i) {
                return LocationManagerService.this.lambda$new$0$LocationManagerService(i);
            }
        });
        packageManagerInternal.setLocationExtraPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.-$$Lambda$LocationManagerService$pUnNobtfzLC9eAlVqCMKySwbo3U
            public final String[] getPackages(int i) {
                return LocationManagerService.this.lambda$new$1$LocationManagerService(i);
            }
        });
    }

    public /* synthetic */ String[] lambda$new$0$LocationManagerService(int userId) {
        return this.mContext.getResources().getStringArray(17236043);
    }

    public /* synthetic */ String[] lambda$new$1$LocationManagerService(int userId) {
        return this.mContext.getResources().getStringArray(17236042);
    }

    public void systemRunning() {
        synchronized (this.mLock) {
            initializeLocked();
        }
    }

    /* JADX WARN: Type inference failed for: r1v21, types: [com.android.server.LocationManagerService$7] */
    @GuardedBy({"mLock"})
    private void initializeLocked() {
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService("appops");
        this.mPackageManager = this.mContext.getPackageManager();
        this.mPowerManager = (PowerManager) this.mContext.getSystemService("power");
        this.mActivityManager = (ActivityManager) this.mContext.getSystemService("activity");
        this.mUserManager = (UserManager) this.mContext.getSystemService("user");
        this.mLocationFudger = new LocationFudger(this.mContext, this.mHandler);
        this.mBlacklist = new LocationBlacklist(this.mContext, this.mHandler);
        this.mBlacklist.init();
        this.mGeofenceManager = new GeofenceManager(this.mContext, this.mBlacklist);
        initializeProvidersLocked();
        this.mAppOps.startWatchingMode(0, (String) null, 1, (AppOpsManager.OnOpChangedListener) new AnonymousClass1());
        this.mPackageManager.addOnPermissionsChangeListener(new PackageManager.OnPermissionsChangedListener() { // from class: com.android.server.-$$Lambda$LocationManagerService$2PZQdsle7L3JDh5TZyL5YAyDqTk
            public final void onPermissionsChanged(int i) {
                LocationManagerService.this.lambda$initializeLocked$3$LocationManagerService(i);
            }
        });
        this.mActivityManager.addOnUidImportanceListener(new ActivityManager.OnUidImportanceListener() { // from class: com.android.server.-$$Lambda$LocationManagerService$tHPgS5c0niUhGntiX8gOnWrZpg8
            public final void onUidImportance(int i, int i2) {
                LocationManagerService.this.lambda$initializeLocked$5$LocationManagerService(i, i2);
            }
        }, 125);
        this.mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("location_mode"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.LocationManagerService.2
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.onLocationModeChangedLocked(true);
                }
            }
        }, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("location_providers_allowed"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.LocationManagerService.3
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.onProviderAllowedChangedLocked();
                }
            }
        }, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("location_background_throttle_interval_ms"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.LocationManagerService.4
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.onBackgroundThrottleIntervalChangedLocked();
                }
            }
        }, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("location_background_throttle_package_whitelist"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.LocationManagerService.5
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.onBackgroundThrottleWhitelistChangedLocked();
                }
            }
        }, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("location_ignore_settings_package_whitelist"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.LocationManagerService.6
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.onIgnoreSettingsWhitelistChangedLocked();
                }
            }
        }, -1);
        PowerManagerInternal localPowerManager = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
        localPowerManager.registerLowPowerModeObserver(1, new Consumer() { // from class: com.android.server.-$$Lambda$LocationManagerService$g2YvHnuXGNr_JWSge7Toq3BS9cY
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                LocationManagerService.this.lambda$initializeLocked$7$LocationManagerService((PowerSaveState) obj);
            }
        });
        new PackageMonitor() { // from class: com.android.server.LocationManagerService.7
            public void onPackageDisappeared(String packageName, int reason) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.onPackageDisappearedLocked(packageName);
                }
            }
        }.register(this.mContext, this.mHandler.getLooper(), true);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.USER_SWITCHED");
        intentFilter.addAction("android.intent.action.MANAGED_PROFILE_ADDED");
        intentFilter.addAction("android.intent.action.MANAGED_PROFILE_REMOVED");
        intentFilter.addAction("android.intent.action.SCREEN_OFF");
        intentFilter.addAction("android.intent.action.SCREEN_ON");
        this.mContext.registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.LocationManagerService.8
            /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    synchronized (LocationManagerService.this.mLock) {
                        char c = 65535;
                        switch (action.hashCode()) {
                            case -2128145023:
                                if (action.equals("android.intent.action.SCREEN_OFF")) {
                                    c = 4;
                                    break;
                                }
                                break;
                            case -1454123155:
                                if (action.equals("android.intent.action.SCREEN_ON")) {
                                    c = 3;
                                    break;
                                }
                                break;
                            case -385593787:
                                if (action.equals("android.intent.action.MANAGED_PROFILE_ADDED")) {
                                    c = 1;
                                    break;
                                }
                                break;
                            case 959232034:
                                if (action.equals("android.intent.action.USER_SWITCHED")) {
                                    c = 0;
                                    break;
                                }
                                break;
                            case 1051477093:
                                if (action.equals("android.intent.action.MANAGED_PROFILE_REMOVED")) {
                                    c = 2;
                                    break;
                                }
                                break;
                        }
                        if (c == 0) {
                            LocationManagerService.this.onUserChangedLocked(intent.getIntExtra("android.intent.extra.user_handle", 0));
                        } else if (c == 1 || c == 2) {
                            LocationManagerService.this.onUserProfilesChangedLocked();
                        } else if (c == 3 || c == 4) {
                            LocationManagerService.this.onScreenStateChangedLocked();
                        }
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, this.mHandler);
        this.mCurrentUserId = -10000;
        onUserChangedLocked(ActivityManager.getCurrentUser());
        onBackgroundThrottleWhitelistChangedLocked();
        onIgnoreSettingsWhitelistChangedLocked();
        onBatterySaverModeChangedLocked(this.mPowerManager.getLocationPowerSaveMode());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.LocationManagerService$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 extends AppOpsManager.OnOpChangedInternalListener {
        AnonymousClass1() {
        }

        public void onOpChanged(int op, String packageName) {
            LocationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$LocationManagerService$1$HAAnoF9DI9FvCHK_geH89--2z2I
                @Override // java.lang.Runnable
                public final void run() {
                    LocationManagerService.AnonymousClass1.this.lambda$onOpChanged$0$LocationManagerService$1();
                }
            });
        }

        public /* synthetic */ void lambda$onOpChanged$0$LocationManagerService$1() {
            synchronized (LocationManagerService.this.mLock) {
                LocationManagerService.this.onAppOpChangedLocked();
            }
        }
    }

    public /* synthetic */ void lambda$initializeLocked$3$LocationManagerService(int uid) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$LocationManagerService$G-JjItJofmJkJhbftqezuIe8Sio
            @Override // java.lang.Runnable
            public final void run() {
                LocationManagerService.this.lambda$initializeLocked$2$LocationManagerService();
            }
        });
    }

    public /* synthetic */ void lambda$initializeLocked$2$LocationManagerService() {
        synchronized (this.mLock) {
            onPermissionsChangedLocked();
        }
    }

    public /* synthetic */ void lambda$initializeLocked$5$LocationManagerService(final int uid, final int importance) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$LocationManagerService$GVLGDgL1Vk3AKo-zMjRmo3-OLpQ
            @Override // java.lang.Runnable
            public final void run() {
                LocationManagerService.this.lambda$initializeLocked$4$LocationManagerService(uid, importance);
            }
        });
    }

    public /* synthetic */ void lambda$initializeLocked$4$LocationManagerService(int uid, int importance) {
        synchronized (this.mLock) {
            onUidImportanceChangedLocked(uid, importance);
        }
    }

    public /* synthetic */ void lambda$initializeLocked$7$LocationManagerService(final PowerSaveState state) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$LocationManagerService$wT7D5HWSJcE1hXhYNGDPH6IVDx0
            @Override // java.lang.Runnable
            public final void run() {
                LocationManagerService.this.lambda$initializeLocked$6$LocationManagerService(state);
            }
        });
    }

    public /* synthetic */ void lambda$initializeLocked$6$LocationManagerService(PowerSaveState state) {
        synchronized (this.mLock) {
            onBatterySaverModeChangedLocked(state.locationMode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onAppOpChangedLocked() {
        for (Receiver receiver : this.mReceivers.values()) {
            receiver.updateMonitoring(true);
        }
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            applyRequirementsLocked(p);
        }
    }

    @GuardedBy({"mLock"})
    private void onPermissionsChangedLocked() {
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            applyRequirementsLocked(p);
        }
    }

    @GuardedBy({"mLock"})
    private void onBatterySaverModeChangedLocked(int newLocationMode) {
        if (D) {
            Slog.d(TAG, "Battery Saver location mode changed from " + PowerManager.locationPowerSaveModeToString(this.mBatterySaverMode) + " to " + PowerManager.locationPowerSaveModeToString(newLocationMode));
        }
        if (this.mBatterySaverMode == newLocationMode) {
            return;
        }
        this.mBatterySaverMode = newLocationMode;
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            applyRequirementsLocked(p);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onScreenStateChangedLocked() {
        if (this.mBatterySaverMode == 4) {
            Iterator<LocationProvider> it = this.mProviders.iterator();
            while (it.hasNext()) {
                LocationProvider p = it.next();
                applyRequirementsLocked(p);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onLocationModeChangedLocked(boolean broadcast) {
        if (D) {
            Log.d(TAG, "location enabled is now " + isLocationEnabled());
        }
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            p.onLocationModeChangedLocked();
        }
        if (broadcast) {
            this.mContext.sendBroadcastAsUser(new Intent("android.location.MODE_CHANGED"), UserHandle.ALL);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onProviderAllowedChangedLocked() {
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            p.onAllowedChangedLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onPackageDisappearedLocked(String packageName) {
        ArrayList<Receiver> deadReceivers = null;
        for (Receiver receiver : this.mReceivers.values()) {
            if (receiver.mCallerIdentity.mPackageName.equals(packageName)) {
                if (deadReceivers == null) {
                    deadReceivers = new ArrayList<>();
                }
                deadReceivers.add(receiver);
            }
        }
        if (deadReceivers != null) {
            Iterator<Receiver> it = deadReceivers.iterator();
            while (it.hasNext()) {
                removeUpdatesLocked(it.next());
            }
        }
    }

    @GuardedBy({"mLock"})
    private void onUidImportanceChangedLocked(int uid, int importance) {
        boolean foreground = isImportanceForeground(importance);
        HashSet<String> affectedProviders = new HashSet<>(this.mRecordsByProvider.size());
        for (Map.Entry<String, ArrayList<UpdateRecord>> entry : this.mRecordsByProvider.entrySet()) {
            String provider = entry.getKey();
            Iterator<UpdateRecord> it = entry.getValue().iterator();
            while (it.hasNext()) {
                UpdateRecord record = it.next();
                if (record.mReceiver.mCallerIdentity.mUid == uid && record.mIsForegroundUid != foreground) {
                    if (D) {
                        Log.d(TAG, "request from uid " + uid + " is now " + foregroundAsString(foreground));
                    }
                    record.updateForeground(foreground);
                    if (!isThrottlingExemptLocked(record.mReceiver.mCallerIdentity)) {
                        affectedProviders.add(provider);
                    }
                }
            }
        }
        Iterator<String> it2 = affectedProviders.iterator();
        while (it2.hasNext()) {
            String provider2 = it2.next();
            applyRequirementsLocked(provider2);
        }
        updateGnssDataProviderOnUidImportanceChangedLocked(this.mGnssMeasurementsListeners, this.mGnssMeasurementsProvider, new Function() { // from class: com.android.server.-$$Lambda$qoNbXUvSu3yuTPVXPUfZW_HDrTQ
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return IGnssMeasurementsListener.Stub.asInterface((IBinder) obj);
            }
        }, uid, foreground);
        updateGnssDataProviderOnUidImportanceChangedLocked(this.mGnssNavigationMessageListeners, this.mGnssNavigationMessageProvider, new Function() { // from class: com.android.server.-$$Lambda$HALkbmbB2IPr_wdFkPjiIWCzJsY
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return IGnssNavigationMessageListener.Stub.asInterface((IBinder) obj);
            }
        }, uid, foreground);
        updateGnssDataProviderOnUidImportanceChangedLocked(this.mGnssStatusListeners, this.mGnssStatusProvider, new Function() { // from class: com.android.server.-$$Lambda$hu439-4T6QBT8QyZnspMtXqICWs
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return IGnssStatusListener.Stub.asInterface((IBinder) obj);
            }
        }, uid, foreground);
    }

    @GuardedBy({"mLock"})
    private <TListener extends IInterface> void updateGnssDataProviderOnUidImportanceChangedLocked(ArrayMap<IBinder, ? extends LinkedListenerBase> gnssDataListeners, RemoteListenerHelper<TListener> gnssDataProvider, Function<IBinder, TListener> mapBinderToListener, int uid, boolean foreground) {
        for (Map.Entry<IBinder, ? extends LinkedListenerBase> entry : gnssDataListeners.entrySet()) {
            LinkedListenerBase linkedListener = entry.getValue();
            CallerIdentity callerIdentity = linkedListener.mCallerIdentity;
            if (callerIdentity.mUid == uid) {
                if (D) {
                    Log.d(TAG, linkedListener.mListenerName + " from uid " + uid + " is now " + foregroundAsString(foreground));
                }
                TListener listener = mapBinderToListener.apply(entry.getKey());
                if (foreground || isThrottlingExemptLocked(callerIdentity)) {
                    gnssDataProvider.addListener(listener, callerIdentity);
                } else {
                    gnssDataProvider.removeListener(listener);
                }
            }
        }
    }

    private static String foregroundAsString(boolean foreground) {
        return foreground ? "foreground" : "background";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isImportanceForeground(int importance) {
        return importance <= 125;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onBackgroundThrottleIntervalChangedLocked() {
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider provider = it.next();
            applyRequirementsLocked(provider);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onBackgroundThrottleWhitelistChangedLocked() {
        this.mBackgroundThrottlePackageWhitelist.clear();
        this.mBackgroundThrottlePackageWhitelist.addAll(SystemConfig.getInstance().getAllowUnthrottledLocation());
        String setting = Settings.Global.getString(this.mContext.getContentResolver(), "location_background_throttle_package_whitelist");
        if (!TextUtils.isEmpty(setting)) {
            this.mBackgroundThrottlePackageWhitelist.addAll(Arrays.asList(setting.split(",")));
        }
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            applyRequirementsLocked(p);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"lock"})
    public void onIgnoreSettingsWhitelistChangedLocked() {
        this.mIgnoreSettingsPackageWhitelist.clear();
        this.mIgnoreSettingsPackageWhitelist.addAll(SystemConfig.getInstance().getAllowIgnoreLocationSettings());
        String setting = Settings.Global.getString(this.mContext.getContentResolver(), "location_ignore_settings_package_whitelist");
        if (!TextUtils.isEmpty(setting)) {
            this.mIgnoreSettingsPackageWhitelist.addAll(Arrays.asList(setting.split(",")));
        }
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            applyRequirementsLocked(p);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onUserProfilesChangedLocked() {
        this.mCurrentUserProfiles = this.mUserManager.getProfileIdsWithDisabled(this.mCurrentUserId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public boolean isCurrentProfileLocked(int userId) {
        return ArrayUtils.contains(this.mCurrentUserProfiles, userId);
    }

    @GuardedBy({"mLock"})
    private void ensureFallbackFusedProviderPresentLocked(String[] pkgs) {
        PackageManager pm = this.mContext.getPackageManager();
        String systemPackageName = this.mContext.getPackageName();
        ArrayList<HashSet<Signature>> sigSets = ServiceWatcher.getSignatureSets(this.mContext, pkgs);
        List<ResolveInfo> rInfos = pm.queryIntentServicesAsUser(new Intent(FUSED_LOCATION_SERVICE_ACTION), 128, this.mCurrentUserId);
        for (ResolveInfo rInfo : rInfos) {
            String packageName = rInfo.serviceInfo.packageName;
            try {
                PackageInfo pInfo = pm.getPackageInfo(packageName, 64);
                if (!ServiceWatcher.isSignatureMatch(pInfo.signatures, sigSets)) {
                    Log.w(TAG, packageName + " resolves service " + FUSED_LOCATION_SERVICE_ACTION + ", but has wrong signature, ignoring");
                } else if (rInfo.serviceInfo.metaData == null) {
                    Log.w(TAG, "Found fused provider without metadata: " + packageName);
                } else {
                    int version = rInfo.serviceInfo.metaData.getInt(ServiceWatcher.EXTRA_SERVICE_VERSION, -1);
                    if (version == 0) {
                        if ((rInfo.serviceInfo.applicationInfo.flags & 1) == 0) {
                            if (D) {
                                Log.d(TAG, "Fallback candidate not in /system: " + packageName);
                            }
                        } else if (pm.checkSignatures(systemPackageName, packageName) != 0) {
                            if (D) {
                                Log.d(TAG, "Fallback candidate not signed the same as system: " + packageName);
                            }
                        } else if (D) {
                            Log.d(TAG, "Found fallback provider: " + packageName);
                            return;
                        } else {
                            return;
                        }
                    } else if (D) {
                        Log.d(TAG, "Fallback candidate not version 0: " + packageName);
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "missing package: " + packageName);
            }
        }
        throw new IllegalStateException("Unable to find a fused location provider that is in the system partition with version 0 and signed with the platform certificate. Such a package is needed to provide a default fused location provider in the event that no other fused location provider has been installed or is currently available. For example, coreOnly boot mode when decrypting the data partition. The fallback must also be marked coreApp=\"true\" in the manifest");
    }

    @GuardedBy({"mLock"})
    private void initializeProvidersLocked() {
        ActivityRecognitionHardware activityRecognitionHardware;
        LocationManagerService locationManagerService = this;
        LocationProvider passiveProviderManager = new LocationProvider(locationManagerService, "passive", (AnonymousClass1) null);
        locationManagerService.addProviderLocked(passiveProviderManager);
        locationManagerService.mPassiveProvider = new PassiveProvider(locationManagerService.mContext, passiveProviderManager);
        passiveProviderManager.attachLocked(locationManagerService.mPassiveProvider);
        if (GnssLocationProvider.isSupported()) {
            LocationProvider gnssProviderManager = new LocationProvider(locationManagerService, "gps", true, null);
            locationManagerService.mRealProviders.add(gnssProviderManager);
            locationManagerService.addProviderLocked(gnssProviderManager);
            GnssLocationProvider gnssProvider = new GnssLocationProvider(locationManagerService.mContext, gnssProviderManager, locationManagerService.mHandler.getLooper());
            gnssProviderManager.attachLocked(gnssProvider);
            locationManagerService.mGnssSystemInfoProvider = gnssProvider.getGnssSystemInfoProvider();
            locationManagerService.mGnssBatchingProvider = gnssProvider.getGnssBatchingProvider();
            locationManagerService.mGnssMetricsProvider = gnssProvider.getGnssMetricsProvider();
            locationManagerService.mGnssCapabilitiesProvider = gnssProvider.getGnssCapabilitiesProvider();
            locationManagerService.mGnssStatusProvider = gnssProvider.getGnssStatusProvider();
            locationManagerService.mNetInitiatedListener = gnssProvider.getNetInitiatedListener();
            locationManagerService.mGnssMeasurementsProvider = gnssProvider.getGnssMeasurementsProvider();
            locationManagerService.mGnssMeasurementCorrectionsProvider = gnssProvider.getGnssMeasurementCorrectionsProvider();
            locationManagerService.mGnssNavigationMessageProvider = gnssProvider.getGnssNavigationMessageProvider();
            locationManagerService.mGpsGeofenceProxy = gnssProvider.getGpsGeofenceProxy();
        }
        Resources resources = locationManagerService.mContext.getResources();
        String[] pkgs = resources.getStringArray(17236043);
        if (D) {
            Log.d(TAG, "certificates for location providers pulled from: " + Arrays.toString(pkgs));
        }
        locationManagerService.ensureFallbackFusedProviderPresentLocked(pkgs);
        LocationProvider networkProviderManager = new LocationProvider(locationManagerService, "network", true, null);
        LocationProviderProxy networkProvider = LocationProviderProxy.createAndBind(locationManagerService.mContext, networkProviderManager, NETWORK_LOCATION_SERVICE_ACTION, 17891447, 17039765, 17236043);
        if (networkProvider != null) {
            locationManagerService.mRealProviders.add(networkProviderManager);
            locationManagerService.addProviderLocked(networkProviderManager);
            networkProviderManager.attachLocked(networkProvider);
        } else {
            Slog.w(TAG, "no network location provider found");
        }
        LocationProvider fusedProviderManager = new LocationProvider(locationManagerService, "fused", (AnonymousClass1) null);
        LocationProviderProxy fusedProvider = LocationProviderProxy.createAndBind(locationManagerService.mContext, fusedProviderManager, FUSED_LOCATION_SERVICE_ACTION, 17891439, 17039738, 17236043);
        if (fusedProvider != null) {
            locationManagerService.mRealProviders.add(fusedProviderManager);
            locationManagerService.addProviderLocked(fusedProviderManager);
            fusedProviderManager.attachLocked(fusedProvider);
        } else {
            Slog.e(TAG, "no fused location provider found", new IllegalStateException("Location service needs a fused location provider"));
        }
        locationManagerService.mGeocodeProvider = GeocoderProxy.createAndBind(locationManagerService.mContext, 17891440, 17039739, 17236043);
        if (locationManagerService.mGeocodeProvider == null) {
            Slog.e(TAG, "no geocoder provider found");
        }
        GeofenceProxy provider = GeofenceProxy.createAndBind(locationManagerService.mContext, 17891441, 17039740, 17236043, locationManagerService.mGpsGeofenceProxy, null);
        if (provider == null) {
            Slog.d(TAG, "Unable to bind FLP Geofence proxy.");
        }
        boolean activityRecognitionHardwareIsSupported = ActivityRecognitionHardware.isSupported();
        if (activityRecognitionHardwareIsSupported) {
            ActivityRecognitionHardware activityRecognitionHardware2 = ActivityRecognitionHardware.getInstance(locationManagerService.mContext);
            activityRecognitionHardware = activityRecognitionHardware2;
        } else {
            Slog.d(TAG, "Hardware Activity-Recognition not supported.");
            activityRecognitionHardware = null;
        }
        ActivityRecognitionProxy proxy = ActivityRecognitionProxy.createAndBind(locationManagerService.mContext, activityRecognitionHardwareIsSupported, activityRecognitionHardware, 17891432, 17039670, 17236043);
        if (proxy == null) {
            Slog.d(TAG, "Unable to bind ActivityRecognitionProxy.");
        }
        String[] testProviderStrings = resources.getStringArray(17236077);
        int length = testProviderStrings.length;
        int i = 0;
        while (i < length) {
            String testProviderString = testProviderStrings[i];
            String[] fragments = testProviderString.split(",");
            LocationProvider passiveProviderManager2 = passiveProviderManager;
            String name = fragments[0].trim();
            ProviderProperties properties = new ProviderProperties(Boolean.parseBoolean(fragments[1]), Boolean.parseBoolean(fragments[2]), Boolean.parseBoolean(fragments[3]), Boolean.parseBoolean(fragments[4]), Boolean.parseBoolean(fragments[5]), Boolean.parseBoolean(fragments[6]), Boolean.parseBoolean(fragments[7]), Integer.parseInt(fragments[8]), Integer.parseInt(fragments[9]));
            Resources resources2 = resources;
            LocationProvider testProviderManager = new LocationProvider(locationManagerService, name, (AnonymousClass1) null);
            locationManagerService.addProviderLocked(testProviderManager);
            new MockProvider(locationManagerService.mContext, testProviderManager, properties);
            i++;
            locationManagerService = this;
            resources = resources2;
            passiveProviderManager = passiveProviderManager2;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void onUserChangedLocked(int userId) {
        if (this.mCurrentUserId == userId) {
            return;
        }
        if (D) {
            Log.d(TAG, "foreground user is changing to " + userId);
        }
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider p = it.next();
            p.onUserChangingLocked();
        }
        this.mCurrentUserId = userId;
        onUserProfilesChangedLocked();
        this.mBlacklist.switchUser(userId);
        onLocationModeChangedLocked(false);
        onProviderAllowedChangedLocked();
        Iterator<LocationProvider> it2 = this.mProviders.iterator();
        while (it2.hasNext()) {
            LocationProvider p2 = it2.next();
            p2.onUseableChangedLocked(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LocationProvider implements AbstractLocationProvider.LocationProviderManager {
        @GuardedBy({"mLock"})
        private boolean mAllowed;
        @GuardedBy({"mLock"})
        private boolean mEnabled;
        private final boolean mIsManagedBySettings;
        private final String mName;
        @GuardedBy({"mLock"})
        private ProviderProperties mProperties;
        @GuardedBy({"mLock"})
        protected AbstractLocationProvider mProvider;
        @GuardedBy({"mLock"})
        private boolean mUseable;

        /* synthetic */ LocationProvider(LocationManagerService x0, String x1, AnonymousClass1 x2) {
            this(x0, x1);
        }

        /* synthetic */ LocationProvider(LocationManagerService x0, String x1, boolean x2, AnonymousClass1 x3) {
            this(x1, x2);
        }

        private LocationProvider(LocationManagerService locationManagerService, String name) {
            this(name, false);
        }

        private LocationProvider(String name, boolean isManagedBySettings) {
            this.mName = name;
            this.mIsManagedBySettings = isManagedBySettings;
            this.mProvider = null;
            this.mUseable = false;
            boolean z = this.mIsManagedBySettings;
            this.mAllowed = !z;
            this.mEnabled = false;
            this.mProperties = null;
            if (z) {
                ContentResolver contentResolver = LocationManagerService.this.mContext.getContentResolver();
                Settings.Secure.putStringForUser(contentResolver, "location_providers_allowed", "-" + this.mName, LocationManagerService.this.mCurrentUserId);
            }
        }

        @GuardedBy({"mLock"})
        public void attachLocked(AbstractLocationProvider provider) {
            Preconditions.checkNotNull(provider);
            Preconditions.checkState(this.mProvider == null);
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, this.mName + " provider attached");
            }
            this.mProvider = provider;
            onUseableChangedLocked(false);
        }

        public String getName() {
            return this.mName;
        }

        @GuardedBy({"mLock"})
        public List<String> getPackagesLocked() {
            AbstractLocationProvider abstractLocationProvider = this.mProvider;
            if (abstractLocationProvider == null) {
                return Collections.emptyList();
            }
            return abstractLocationProvider.getProviderPackages();
        }

        public boolean isMock() {
            return false;
        }

        @GuardedBy({"mLock"})
        public boolean isPassiveLocked() {
            return this.mProvider == LocationManagerService.this.mPassiveProvider;
        }

        @GuardedBy({"mLock"})
        public ProviderProperties getPropertiesLocked() {
            return this.mProperties;
        }

        @GuardedBy({"mLock"})
        public void setRequestLocked(ProviderRequest request, WorkSource workSource) {
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    this.mProvider.setRequest(request, workSource);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy({"mLock"})
        public void dumpLocked(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.print("  " + this.mName + " provider");
            if (isMock()) {
                pw.print(" [mock]");
            }
            pw.println(":");
            pw.println("    useable=" + this.mUseable);
            if (!this.mUseable) {
                StringBuilder sb = new StringBuilder();
                sb.append("    attached=");
                sb.append(this.mProvider != null);
                pw.println(sb.toString());
                if (this.mIsManagedBySettings) {
                    pw.println("    allowed=" + this.mAllowed);
                }
                pw.println("    enabled=" + this.mEnabled);
            }
            pw.println("    properties=" + this.mProperties);
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    this.mProvider.dump(fd, pw, args);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy({"mLock"})
        public long getStatusUpdateTimeLocked() {
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return this.mProvider.getStatusUpdateTime();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return 0L;
        }

        @GuardedBy({"mLock"})
        public int getStatusLocked(Bundle extras) {
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    return this.mProvider.getStatus(extras);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return 2;
        }

        @GuardedBy({"mLock"})
        public void sendExtraCommandLocked(String command, Bundle extras) {
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    this.mProvider.sendExtraCommand(command, extras);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override // com.android.server.location.AbstractLocationProvider.LocationProviderManager
        public void onReportLocation(final Location location) {
            LocationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$LocationManagerService$LocationProvider$R123rmQLJrCf8yBSKrQD6XPhpZs
                @Override // java.lang.Runnable
                public final void run() {
                    LocationManagerService.LocationProvider.this.lambda$onReportLocation$0$LocationManagerService$LocationProvider(location);
                }
            });
        }

        public /* synthetic */ void lambda$onReportLocation$0$LocationManagerService$LocationProvider(Location location) {
            synchronized (LocationManagerService.this.mLock) {
                LocationManagerService.this.handleLocationChangedLocked(location, this);
            }
        }

        @Override // com.android.server.location.AbstractLocationProvider.LocationProviderManager
        public void onReportLocation(final List<Location> locations) {
            LocationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$LocationManagerService$LocationProvider$UwV519Q998DTiPhy1rbdXyO3Geo
                @Override // java.lang.Runnable
                public final void run() {
                    LocationManagerService.LocationProvider.this.lambda$onReportLocation$1$LocationManagerService$LocationProvider(locations);
                }
            });
        }

        public /* synthetic */ void lambda$onReportLocation$1$LocationManagerService$LocationProvider(List locations) {
            synchronized (LocationManagerService.this.mLock) {
                LocationProvider gpsProvider = LocationManagerService.this.getLocationProviderLocked("gps");
                if (gpsProvider != null && gpsProvider.isUseableLocked()) {
                    if (LocationManagerService.this.mGnssBatchingCallback != null) {
                        try {
                            LocationManagerService.this.mGnssBatchingCallback.onLocationBatch(locations);
                        } catch (RemoteException e) {
                            Slog.e(LocationManagerService.TAG, "mGnssBatchingCallback.onLocationBatch failed", e);
                        }
                        return;
                    }
                    Slog.e(LocationManagerService.TAG, "reportLocationBatch() called without active Callback");
                    return;
                }
                Slog.w(LocationManagerService.TAG, "reportLocationBatch() called without user permission");
            }
        }

        @Override // com.android.server.location.AbstractLocationProvider.LocationProviderManager
        public void onSetEnabled(final boolean enabled) {
            LocationManagerService.this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$LocationManagerService$LocationProvider$nsL4uwojBLPzs1TzMfpQIBSm7p0
                @Override // java.lang.Runnable
                public final void run() {
                    LocationManagerService.LocationProvider.this.lambda$onSetEnabled$2$LocationManagerService$LocationProvider(enabled);
                }
            });
        }

        public /* synthetic */ void lambda$onSetEnabled$2$LocationManagerService$LocationProvider(boolean enabled) {
            synchronized (LocationManagerService.this.mLock) {
                if (enabled == this.mEnabled) {
                    return;
                }
                if (LocationManagerService.D) {
                    Log.d(LocationManagerService.TAG, this.mName + " provider enabled is now " + this.mEnabled);
                }
                this.mEnabled = enabled;
                onUseableChangedLocked(false);
            }
        }

        @Override // com.android.server.location.AbstractLocationProvider.LocationProviderManager
        public void onSetProperties(ProviderProperties properties) {
            synchronized (LocationManagerService.this.mLock) {
                this.mProperties = properties;
            }
        }

        @GuardedBy({"mLock"})
        public void onLocationModeChangedLocked() {
            onUseableChangedLocked(false);
        }

        @GuardedBy({"mLock"})
        public void onAllowedChangedLocked() {
            if (this.mIsManagedBySettings) {
                String allowedProviders = Settings.Secure.getStringForUser(LocationManagerService.this.mContext.getContentResolver(), "location_providers_allowed", LocationManagerService.this.mCurrentUserId);
                boolean allowed = TextUtils.delimitedStringContains(allowedProviders, ',', this.mName);
                if (allowed == this.mAllowed) {
                    return;
                }
                if (LocationManagerService.D) {
                    Log.d(LocationManagerService.TAG, this.mName + " provider allowed is now " + this.mAllowed);
                }
                this.mAllowed = allowed;
                onUseableChangedLocked(true);
            }
        }

        @GuardedBy({"mLock"})
        public boolean isUseableLocked() {
            return isUseableForUserLocked(LocationManagerService.this.mCurrentUserId);
        }

        @GuardedBy({"mLock"})
        public boolean isUseableForUserLocked(int userId) {
            return LocationManagerService.this.isCurrentProfileLocked(userId) && this.mUseable;
        }

        @GuardedBy({"mLock"})
        private boolean isUseableIgnoringAllowedLocked() {
            return this.mProvider != null && LocationManagerService.this.mProviders.contains(this) && LocationManagerService.this.isLocationEnabled() && this.mEnabled;
        }

        @GuardedBy({"mLock"})
        public void onUseableChangedLocked(boolean isAllowedChanged) {
            boolean useableIgnoringAllowed = isUseableIgnoringAllowedLocked();
            boolean useable = useableIgnoringAllowed && this.mAllowed;
            if (this.mIsManagedBySettings) {
                if (useableIgnoringAllowed && !isAllowedChanged) {
                    ContentResolver contentResolver = LocationManagerService.this.mContext.getContentResolver();
                    Settings.Secure.putStringForUser(contentResolver, "location_providers_allowed", "+" + this.mName, LocationManagerService.this.mCurrentUserId);
                } else if (!useableIgnoringAllowed) {
                    ContentResolver contentResolver2 = LocationManagerService.this.mContext.getContentResolver();
                    Settings.Secure.putStringForUser(contentResolver2, "location_providers_allowed", "-" + this.mName, LocationManagerService.this.mCurrentUserId);
                }
                Intent intent = new Intent("android.location.PROVIDERS_CHANGED");
                intent.putExtra("android.location.extra.PROVIDER_NAME", this.mName);
                LocationManagerService.this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
            if (useable == this.mUseable) {
                return;
            }
            this.mUseable = useable;
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, this.mName + " provider useable is now " + this.mUseable);
            }
            if (!this.mUseable) {
                LocationManagerService.this.mLastLocation.clear();
                LocationManagerService.this.mLastLocationCoarseInterval.clear();
            }
            LocationManagerService.this.updateProviderUseableLocked(this);
        }

        @GuardedBy({"mLock"})
        public void onUserChangingLocked() {
            this.mUseable = false;
            LocationManagerService.this.updateProviderUseableLocked(this);
        }
    }

    /* loaded from: classes.dex */
    private class MockLocationProvider extends LocationProvider {
        private ProviderRequest mCurrentRequest;

        /* synthetic */ MockLocationProvider(LocationManagerService x0, String x1, AnonymousClass1 x2) {
            this(x1);
        }

        private MockLocationProvider(String name) {
            super(LocationManagerService.this, name, (AnonymousClass1) null);
        }

        @Override // com.android.server.LocationManagerService.LocationProvider
        public void attachLocked(AbstractLocationProvider provider) {
            Preconditions.checkState(provider instanceof MockProvider);
            super.attachLocked(provider);
        }

        @Override // com.android.server.LocationManagerService.LocationProvider
        public boolean isMock() {
            return true;
        }

        @GuardedBy({"mLock"})
        public void setEnabledLocked(boolean enabled) {
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    ((MockProvider) this.mProvider).setEnabled(enabled);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @GuardedBy({"mLock"})
        public void setLocationLocked(Location location) {
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    ((MockProvider) this.mProvider).setLocation(location);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override // com.android.server.LocationManagerService.LocationProvider
        @GuardedBy({"mLock"})
        public void setRequestLocked(ProviderRequest request, WorkSource workSource) {
            super.setRequestLocked(request, workSource);
            this.mCurrentRequest = request;
        }

        @GuardedBy({"mLock"})
        public void setStatusLocked(int status, Bundle extras, long updateTime) {
            if (this.mProvider != null) {
                long identity = Binder.clearCallingIdentity();
                try {
                    ((MockProvider) this.mProvider).setStatus(status, extras, updateTime);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Receiver extends LinkedListenerBase implements PendingIntent.OnFinished {
        private static final long WAKELOCK_TIMEOUT_MILLIS = 60000;
        private final int mAllowedResolutionLevel;
        private final boolean mHideFromAppOps;
        private final Object mKey;
        private final ILocationListener mListener;
        private boolean mOpHighPowerMonitoring;
        private boolean mOpMonitoring;
        private int mPendingBroadcasts;
        final PendingIntent mPendingIntent;
        final HashMap<String, UpdateRecord> mUpdateRecords;
        PowerManager.WakeLock mWakeLock;
        final WorkSource mWorkSource;

        /* synthetic */ Receiver(LocationManagerService x0, ILocationListener x1, PendingIntent x2, int x3, int x4, String x5, WorkSource x6, boolean x7, AnonymousClass1 x8) {
            this(x1, x2, x3, x4, x5, x6, x7);
        }

        private Receiver(ILocationListener listener, PendingIntent intent, int pid, int uid, String packageName, WorkSource workSource, boolean hideFromAppOps) {
            super(new CallerIdentity(uid, pid, packageName), "LocationListener", null);
            this.mUpdateRecords = new HashMap<>();
            this.mListener = listener;
            this.mPendingIntent = intent;
            if (listener != null) {
                this.mKey = listener.asBinder();
            } else {
                this.mKey = intent;
            }
            this.mAllowedResolutionLevel = LocationManagerService.this.getAllowedResolutionLevel(pid, uid);
            if (workSource != null && workSource.isEmpty()) {
                workSource = null;
            }
            this.mWorkSource = workSource;
            this.mHideFromAppOps = hideFromAppOps;
            updateMonitoring(true);
            this.mWakeLock = LocationManagerService.this.mPowerManager.newWakeLock(1, LocationManagerService.WAKELOCK_KEY);
            this.mWakeLock.setWorkSource(workSource == null ? new WorkSource(this.mCallerIdentity.mUid, this.mCallerIdentity.mPackageName) : workSource);
            this.mWakeLock.setReferenceCounted(false);
        }

        public boolean equals(Object otherObj) {
            return (otherObj instanceof Receiver) && this.mKey.equals(((Receiver) otherObj).mKey);
        }

        public int hashCode() {
            return this.mKey.hashCode();
        }

        public String toString() {
            StringBuilder s = new StringBuilder();
            s.append("Reciever[");
            s.append(Integer.toHexString(System.identityHashCode(this)));
            if (this.mListener != null) {
                s.append(" listener");
            } else {
                s.append(" intent");
            }
            for (String p : this.mUpdateRecords.keySet()) {
                s.append(" ");
                s.append(this.mUpdateRecords.get(p).toString());
            }
            s.append(" monitoring location: ");
            s.append(this.mOpMonitoring);
            s.append("]");
            return s.toString();
        }

        public void updateMonitoring(boolean allow) {
            if (this.mHideFromAppOps) {
                return;
            }
            boolean requestingLocation = false;
            boolean requestingHighPowerLocation = false;
            if (allow) {
                Iterator<UpdateRecord> it = this.mUpdateRecords.values().iterator();
                while (true) {
                    if (!it.hasNext()) {
                        break;
                    }
                    UpdateRecord updateRecord = it.next();
                    LocationProvider provider = LocationManagerService.this.getLocationProviderLocked(updateRecord.mProvider);
                    if (provider != null && (provider.isUseableLocked() || LocationManagerService.this.isSettingsExemptLocked(updateRecord))) {
                        requestingLocation = true;
                        ProviderProperties properties = provider.getPropertiesLocked();
                        if (properties != null && properties.mPowerRequirement == 3 && updateRecord.mRequest.getInterval() < 300000) {
                            requestingHighPowerLocation = true;
                            break;
                        }
                    }
                }
            }
            this.mOpMonitoring = updateMonitoring(requestingLocation, this.mOpMonitoring, 41);
            boolean wasHighPowerMonitoring = this.mOpHighPowerMonitoring;
            this.mOpHighPowerMonitoring = updateMonitoring(requestingHighPowerLocation, this.mOpHighPowerMonitoring, 42);
            if (this.mOpHighPowerMonitoring != wasHighPowerMonitoring) {
                Intent intent = new Intent("android.location.HIGH_POWER_REQUEST_CHANGE");
                LocationManagerService.this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        }

        private boolean updateMonitoring(boolean allowMonitoring, boolean currentlyMonitoring, int op) {
            if (!currentlyMonitoring) {
                if (allowMonitoring) {
                    return LocationManagerService.this.mAppOps.startOpNoThrow(op, this.mCallerIdentity.mUid, this.mCallerIdentity.mPackageName) == 0;
                }
            } else if (!allowMonitoring || LocationManagerService.this.mAppOps.checkOpNoThrow(op, this.mCallerIdentity.mUid, this.mCallerIdentity.mPackageName) != 0) {
                LocationManagerService.this.mAppOps.finishOp(op, this.mCallerIdentity.mUid, this.mCallerIdentity.mPackageName);
                return false;
            }
            return currentlyMonitoring;
        }

        public boolean isListener() {
            return this.mListener != null;
        }

        public boolean isPendingIntent() {
            return this.mPendingIntent != null;
        }

        public ILocationListener getListener() {
            ILocationListener iLocationListener = this.mListener;
            if (iLocationListener != null) {
                return iLocationListener;
            }
            throw new IllegalStateException("Request for non-existent listener");
        }

        public boolean callStatusChangedLocked(String provider, int status, Bundle extras) {
            ILocationListener iLocationListener = this.mListener;
            if (iLocationListener != null) {
                try {
                    iLocationListener.onStatusChanged(provider, status, extras);
                    incrementPendingBroadcastsLocked();
                    return true;
                } catch (RemoteException e) {
                    return false;
                }
            }
            Intent statusChanged = new Intent();
            statusChanged.putExtras(new Bundle(extras));
            statusChanged.putExtra("status", status);
            try {
                this.mPendingIntent.send(LocationManagerService.this.mContext, 0, statusChanged, this, LocationManagerService.this.mHandler, LocationManagerService.this.getResolutionPermission(this.mAllowedResolutionLevel), PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                incrementPendingBroadcastsLocked();
                return true;
            } catch (PendingIntent.CanceledException e2) {
                return false;
            }
        }

        public boolean callLocationChangedLocked(Location location) {
            ILocationListener iLocationListener = this.mListener;
            if (iLocationListener != null) {
                try {
                    iLocationListener.onLocationChanged(new Location(location));
                    incrementPendingBroadcastsLocked();
                    return true;
                } catch (RemoteException e) {
                    return false;
                }
            }
            Intent locationChanged = new Intent();
            locationChanged.putExtra("location", new Location(location));
            try {
                this.mPendingIntent.send(LocationManagerService.this.mContext, 0, locationChanged, this, LocationManagerService.this.mHandler, LocationManagerService.this.getResolutionPermission(this.mAllowedResolutionLevel), PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                incrementPendingBroadcastsLocked();
                return true;
            } catch (PendingIntent.CanceledException e2) {
                return false;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean callProviderEnabledLocked(String provider, boolean enabled) {
            updateMonitoring(true);
            ILocationListener iLocationListener = this.mListener;
            if (iLocationListener != null) {
                try {
                    if (enabled) {
                        iLocationListener.onProviderEnabled(provider);
                    } else {
                        iLocationListener.onProviderDisabled(provider);
                    }
                    incrementPendingBroadcastsLocked();
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent providerIntent = new Intent();
                providerIntent.putExtra("providerEnabled", enabled);
                try {
                    this.mPendingIntent.send(LocationManagerService.this.mContext, 0, providerIntent, this, LocationManagerService.this.mHandler, LocationManagerService.this.getResolutionPermission(this.mAllowedResolutionLevel), PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                    incrementPendingBroadcastsLocked();
                } catch (PendingIntent.CanceledException e2) {
                    return false;
                }
            }
            return true;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, "Remote " + this.mListenerName + " died.");
            }
            synchronized (LocationManagerService.this.mLock) {
                LocationManagerService.this.removeUpdatesLocked(this);
                clearPendingBroadcastsLocked();
            }
        }

        @Override // android.app.PendingIntent.OnFinished
        public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
            synchronized (LocationManagerService.this.mLock) {
                decrementPendingBroadcastsLocked();
            }
        }

        private void incrementPendingBroadcastsLocked() {
            this.mPendingBroadcasts++;
            long identity = Binder.clearCallingIdentity();
            try {
                this.mWakeLock.acquire(60000L);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void decrementPendingBroadcastsLocked() {
            int i = this.mPendingBroadcasts - 1;
            this.mPendingBroadcasts = i;
            if (i == 0) {
                long identity = Binder.clearCallingIdentity();
                try {
                    if (this.mWakeLock.isHeld()) {
                        this.mWakeLock.release();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        public void clearPendingBroadcastsLocked() {
            if (this.mPendingBroadcasts > 0) {
                this.mPendingBroadcasts = 0;
                long identity = Binder.clearCallingIdentity();
                try {
                    if (this.mWakeLock.isHeld()) {
                        this.mWakeLock.release();
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    public void locationCallbackFinished(ILocationListener listener) {
        synchronized (this.mLock) {
            Receiver receiver = this.mReceivers.get(listener.asBinder());
            if (receiver != null) {
                receiver.decrementPendingBroadcastsLocked();
            }
        }
    }

    public int getGnssYearOfHardware() {
        GnssLocationProvider.GnssSystemInfoProvider gnssSystemInfoProvider = this.mGnssSystemInfoProvider;
        if (gnssSystemInfoProvider != null) {
            return gnssSystemInfoProvider.getGnssYearOfHardware();
        }
        return 0;
    }

    public String getGnssHardwareModelName() {
        GnssLocationProvider.GnssSystemInfoProvider gnssSystemInfoProvider = this.mGnssSystemInfoProvider;
        if (gnssSystemInfoProvider != null) {
            return gnssSystemInfoProvider.getGnssHardwareModelName();
        }
        return null;
    }

    private boolean hasGnssPermissions(String packageName) {
        boolean checkLocationAccess;
        synchronized (this.mLock) {
            int allowedResolutionLevel = getCallerAllowedResolutionLevel();
            checkResolutionLevelIsSufficientForProviderUseLocked(allowedResolutionLevel, "gps");
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long identity = Binder.clearCallingIdentity();
            checkLocationAccess = checkLocationAccess(pid, uid, packageName, allowedResolutionLevel);
            Binder.restoreCallingIdentity(identity);
        }
        return checkLocationAccess;
    }

    public int getGnssBatchSize(String packageName) {
        GnssBatchingProvider gnssBatchingProvider;
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (hasGnssPermissions(packageName) && (gnssBatchingProvider = this.mGnssBatchingProvider) != null) {
            return gnssBatchingProvider.getBatchSize();
        }
        return 0;
    }

    public boolean addGnssBatchingCallback(IBatchedLocationCallback callback, String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (!hasGnssPermissions(packageName) || this.mGnssBatchingProvider == null) {
            return false;
        }
        CallerIdentity callerIdentity = new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid(), packageName);
        synchronized (this.mLock) {
            this.mGnssBatchingCallback = callback;
            this.mGnssBatchingDeathCallback = new LinkedListener<>(callback, "BatchedLocationCallback", callerIdentity, new Consumer() { // from class: com.android.server.-$$Lambda$LocationManagerService$ma_5PjwiFAbM39eIaW8jFG89f1w
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    LocationManagerService.this.lambda$addGnssBatchingCallback$8$LocationManagerService((IBatchedLocationCallback) obj);
                }
            }, null);
            return linkToListenerDeathNotificationLocked(callback.asBinder(), this.mGnssBatchingDeathCallback);
        }
    }

    public /* synthetic */ void lambda$addGnssBatchingCallback$8$LocationManagerService(IBatchedLocationCallback listener) {
        stopGnssBatch();
        removeGnssBatchingCallback();
    }

    public void removeGnssBatchingCallback() {
        synchronized (this.mLock) {
            unlinkFromListenerDeathNotificationLocked(this.mGnssBatchingCallback.asBinder(), this.mGnssBatchingDeathCallback);
            this.mGnssBatchingCallback = null;
            this.mGnssBatchingDeathCallback = null;
        }
    }

    public boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName) {
        boolean start;
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (!hasGnssPermissions(packageName) || this.mGnssBatchingProvider == null) {
            return false;
        }
        synchronized (this.mLock) {
            if (this.mGnssBatchingInProgress) {
                Log.e(TAG, "startGnssBatch unexpectedly called w/o stopping prior batch");
                stopGnssBatch();
            }
            this.mGnssBatchingInProgress = true;
            start = this.mGnssBatchingProvider.start(periodNanos, wakeOnFifoFull);
        }
        return start;
    }

    public void flushGnssBatch(String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "flushGnssBatch called without GNSS permissions");
            return;
        }
        synchronized (this.mLock) {
            if (!this.mGnssBatchingInProgress) {
                Log.w(TAG, "flushGnssBatch called with no batch in progress");
            }
            if (this.mGnssBatchingProvider != null) {
                this.mGnssBatchingProvider.flush();
            }
        }
    }

    public boolean stopGnssBatch() {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        synchronized (this.mLock) {
            if (this.mGnssBatchingProvider != null) {
                this.mGnssBatchingInProgress = false;
                return this.mGnssBatchingProvider.stop();
            }
            return false;
        }
    }

    @GuardedBy({"mLock"})
    private void addProviderLocked(LocationProvider provider) {
        Preconditions.checkState(getLocationProviderLocked(provider.getName()) == null);
        this.mProviders.add(provider);
        provider.onAllowedChangedLocked();
        provider.onUseableChangedLocked(false);
    }

    @GuardedBy({"mLock"})
    private void removeProviderLocked(LocationProvider provider) {
        if (this.mProviders.remove(provider)) {
            provider.onUseableChangedLocked(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public LocationProvider getLocationProviderLocked(String providerName) {
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider provider = it.next();
            if (providerName.equals(provider.getName())) {
                return provider;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getResolutionPermission(int resolutionLevel) {
        if (resolutionLevel != 1) {
            if (resolutionLevel == 2) {
                return "android.permission.ACCESS_FINE_LOCATION";
            }
            return null;
        }
        return "android.permission.ACCESS_COARSE_LOCATION";
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getAllowedResolutionLevel(int pid, int uid) {
        if (this.mContext.checkPermission("android.permission.ACCESS_FINE_LOCATION", pid, uid) == 0) {
            return 2;
        }
        if (this.mContext.checkPermission("android.permission.ACCESS_COARSE_LOCATION", pid, uid) == 0) {
            return 1;
        }
        return 0;
    }

    private int getCallerAllowedResolutionLevel() {
        return getAllowedResolutionLevel(Binder.getCallingPid(), Binder.getCallingUid());
    }

    private void checkResolutionLevelIsSufficientForGeofenceUse(int allowedResolutionLevel) {
        if (allowedResolutionLevel < 2) {
            throw new SecurityException("Geofence usage requires ACCESS_FINE_LOCATION permission");
        }
    }

    @GuardedBy({"mLock"})
    private int getMinimumResolutionLevelForProviderUseLocked(String provider) {
        ProviderProperties properties;
        if ("gps".equals(provider) || "passive".equals(provider)) {
            return 2;
        }
        if ("network".equals(provider) || "fused".equals(provider)) {
            return 1;
        }
        Iterator<LocationProvider> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProvider lp = it.next();
            if (lp.getName().equals(provider) && (properties = lp.getPropertiesLocked()) != null) {
                if (properties.mRequiresSatellite) {
                    return 2;
                }
                if (properties.mRequiresNetwork || properties.mRequiresCell) {
                    return 1;
                }
            }
        }
        return 2;
    }

    @GuardedBy({"mLock"})
    private void checkResolutionLevelIsSufficientForProviderUseLocked(int allowedResolutionLevel, String providerName) {
        int requiredResolutionLevel = getMinimumResolutionLevelForProviderUseLocked(providerName);
        if (allowedResolutionLevel < requiredResolutionLevel) {
            if (requiredResolutionLevel == 1) {
                throw new SecurityException("\"" + providerName + "\" location provider requires ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION permission.");
            } else if (requiredResolutionLevel == 2) {
                throw new SecurityException("\"" + providerName + "\" location provider requires ACCESS_FINE_LOCATION permission.");
            } else {
                throw new SecurityException("Insufficient permission for \"" + providerName + "\" location provider.");
            }
        }
    }

    public static int resolutionLevelToOp(int allowedResolutionLevel) {
        if (allowedResolutionLevel != 0) {
            if (allowedResolutionLevel != 1) {
                return 1;
            }
            return 0;
        }
        return -1;
    }

    private static String resolutionLevelToOpStr(int allowedResolutionLevel) {
        if (allowedResolutionLevel != 0) {
            if (allowedResolutionLevel != 1) {
                return allowedResolutionLevel != 2 ? "android:fine_location" : "android:fine_location";
            }
            return "android:coarse_location";
        }
        return "android:fine_location";
    }

    private boolean reportLocationAccessNoThrow(int pid, int uid, String packageName, int allowedResolutionLevel) {
        int op = resolutionLevelToOp(allowedResolutionLevel);
        return (op < 0 || this.mAppOps.noteOpNoThrow(op, uid, packageName) == 0) && getAllowedResolutionLevel(pid, uid) >= allowedResolutionLevel;
    }

    private boolean checkLocationAccess(int pid, int uid, String packageName, int allowedResolutionLevel) {
        int op = resolutionLevelToOp(allowedResolutionLevel);
        return (op < 0 || this.mAppOps.checkOp(op, uid, packageName) == 0) && getAllowedResolutionLevel(pid, uid) >= allowedResolutionLevel;
    }

    public List<String> getAllProviders() {
        ArrayList<String> providers;
        synchronized (this.mLock) {
            providers = new ArrayList<>(this.mProviders.size());
            Iterator<LocationProvider> it = this.mProviders.iterator();
            while (it.hasNext()) {
                LocationProvider provider = it.next();
                String name = provider.getName();
                if (!"fused".equals(name)) {
                    providers.add(name);
                }
            }
        }
        return providers;
    }

    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        ArrayList<String> providers;
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        synchronized (this.mLock) {
            providers = new ArrayList<>(this.mProviders.size());
            Iterator<LocationProvider> it = this.mProviders.iterator();
            while (it.hasNext()) {
                LocationProvider provider = it.next();
                String name = provider.getName();
                if (!"fused".equals(name) && allowedResolutionLevel >= getMinimumResolutionLevelForProviderUseLocked(name) && (!enabledOnly || provider.isUseableLocked())) {
                    if (criteria == null || android.location.LocationProvider.propertiesMeetCriteria(name, provider.getPropertiesLocked(), criteria)) {
                        providers.add(name);
                    }
                }
            }
        }
        return providers;
    }

    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> providers = getProviders(criteria, enabledOnly);
        if (providers.isEmpty()) {
            providers = getProviders(null, enabledOnly);
        }
        if (providers.isEmpty()) {
            return null;
        }
        if (providers.contains("gps")) {
            return "gps";
        }
        if (providers.contains("network")) {
            return "network";
        }
        return providers.get(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void updateProviderUseableLocked(LocationProvider provider) {
        boolean useable = provider.isUseableLocked();
        ArrayList<Receiver> deadReceivers = null;
        ArrayList<UpdateRecord> records = this.mRecordsByProvider.get(provider.getName());
        if (records != null) {
            Iterator<UpdateRecord> it = records.iterator();
            while (it.hasNext()) {
                UpdateRecord record = it.next();
                if (isCurrentProfileLocked(UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid)) && !isSettingsExemptLocked(record) && !record.mReceiver.callProviderEnabledLocked(provider.getName(), useable)) {
                    if (deadReceivers == null) {
                        deadReceivers = new ArrayList<>();
                    }
                    deadReceivers.add(record.mReceiver);
                }
            }
        }
        if (deadReceivers != null) {
            for (int i = deadReceivers.size() - 1; i >= 0; i--) {
                removeUpdatesLocked(deadReceivers.get(i));
            }
        }
        applyRequirementsLocked(provider);
    }

    @GuardedBy({"mLock"})
    private void applyRequirementsLocked(String providerName) {
        LocationProvider provider = getLocationProviderLocked(providerName);
        if (provider != null) {
            applyRequirementsLocked(provider);
        }
    }

    @GuardedBy({"mLock"})
    private void applyRequirementsLocked(LocationProvider provider) {
        boolean z;
        ArrayList<UpdateRecord> records = this.mRecordsByProvider.get(provider.getName());
        WorkSource worksource = new WorkSource();
        ProviderRequest providerRequest = new ProviderRequest();
        if (this.mProviders.contains(provider) && records != null && !records.isEmpty()) {
            long identity = Binder.clearCallingIdentity();
            try {
                long backgroundThrottleInterval = Settings.Global.getLong(this.mContext.getContentResolver(), "location_background_throttle_interval_ms", 1800000L);
                Binder.restoreCallingIdentity(identity);
                boolean isForegroundOnlyMode = this.mBatterySaverMode == 3;
                boolean shouldThrottleRequests = this.mBatterySaverMode == 4 && !this.mPowerManager.isInteractive();
                providerRequest.lowPowerMode = true;
                Iterator<UpdateRecord> it = records.iterator();
                while (it.hasNext()) {
                    UpdateRecord record = it.next();
                    Log.i(TAG, "applyRequirementsLocked UpdateRecord " + record);
                    if (!isCurrentProfileLocked(UserHandle.getUserId(record.mReceiver.mCallerIdentity.mUid))) {
                        Log.i(TAG, "Record is NOT CurrentProfileLocked");
                    } else {
                        Iterator<UpdateRecord> it2 = it;
                        if (!checkLocationAccess(record.mReceiver.mCallerIdentity.mPid, record.mReceiver.mCallerIdentity.mUid, record.mReceiver.mCallerIdentity.mPackageName, record.mReceiver.mAllowedResolutionLevel)) {
                            Log.i(TAG, "Record is NOT allowed");
                            it = it2;
                        } else {
                            boolean isBatterySaverDisablingLocation = shouldThrottleRequests || (isForegroundOnlyMode && !record.mIsForegroundUid);
                            if (!provider.isUseableLocked() || isBatterySaverDisablingLocation) {
                                if (isSettingsExemptLocked(record)) {
                                    providerRequest.locationSettingsIgnored = true;
                                    providerRequest.lowPowerMode = false;
                                } else {
                                    Log.i(TAG, "Record is NOT SettingsExemptLocked");
                                    it = it2;
                                    backgroundThrottleInterval = backgroundThrottleInterval;
                                }
                            }
                            LocationRequest locationRequest = record.mRealRequest;
                            long interval = locationRequest.getInterval();
                            if (!providerRequest.locationSettingsIgnored && !isThrottlingExemptLocked(record.mReceiver.mCallerIdentity)) {
                                if (!record.mIsForegroundUid) {
                                    interval = Math.max(interval, backgroundThrottleInterval);
                                }
                                if (interval != locationRequest.getInterval()) {
                                    locationRequest = new LocationRequest(locationRequest);
                                    locationRequest.setInterval(interval);
                                }
                            }
                            record.mRequest = locationRequest;
                            providerRequest.locationRequests.add(locationRequest);
                            if (!locationRequest.isLowPowerMode()) {
                                providerRequest.lowPowerMode = false;
                            }
                            long backgroundThrottleInterval2 = backgroundThrottleInterval;
                            long backgroundThrottleInterval3 = providerRequest.interval;
                            if (interval >= backgroundThrottleInterval3) {
                                z = true;
                            } else {
                                z = true;
                                providerRequest.reportLocation = true;
                                providerRequest.interval = interval;
                            }
                            it = it2;
                            backgroundThrottleInterval = backgroundThrottleInterval2;
                        }
                    }
                }
                Log.i(TAG, "applyRequirementsLocked " + providerRequest);
                if (providerRequest.reportLocation) {
                    long thresholdInterval = ((providerRequest.interval + 1000) * 3) / 2;
                    Iterator<UpdateRecord> it3 = records.iterator();
                    while (it3.hasNext()) {
                        UpdateRecord record2 = it3.next();
                        if (isCurrentProfileLocked(UserHandle.getUserId(record2.mReceiver.mCallerIdentity.mUid))) {
                            LocationRequest locationRequest2 = record2.mRequest;
                            if (providerRequest.locationRequests.contains(locationRequest2) && locationRequest2.getInterval() <= thresholdInterval) {
                                if (record2.mReceiver.mWorkSource == null || !isValidWorkSource(record2.mReceiver.mWorkSource)) {
                                    worksource.add(record2.mReceiver.mCallerIdentity.mUid, record2.mReceiver.mCallerIdentity.mPackageName);
                                } else {
                                    worksource.add(record2.mReceiver.mWorkSource);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
        provider.setRequestLocked(providerRequest, worksource);
    }

    private static boolean isValidWorkSource(WorkSource workSource) {
        if (workSource.size() > 0) {
            return workSource.getName(0) != null;
        }
        ArrayList<WorkSource.WorkChain> workChains = workSource.getWorkChains();
        return (workChains == null || workChains.isEmpty() || workChains.get(0).getAttributionTag() == null) ? false : true;
    }

    public String[] getBackgroundThrottlingWhitelist() {
        String[] strArr;
        synchronized (this.mLock) {
            strArr = (String[]) this.mBackgroundThrottlePackageWhitelist.toArray(new String[0]);
        }
        return strArr;
    }

    public String[] getIgnoreSettingsWhitelist() {
        String[] strArr;
        synchronized (this.mLock) {
            strArr = (String[]) this.mIgnoreSettingsPackageWhitelist.toArray(new String[0]);
        }
        return strArr;
    }

    @GuardedBy({"mLock"})
    private boolean isThrottlingExemptLocked(CallerIdentity callerIdentity) {
        if (callerIdentity.mUid == 1000 || this.mBackgroundThrottlePackageWhitelist.contains(callerIdentity.mPackageName)) {
            return true;
        }
        return isProviderPackage(callerIdentity.mPackageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public boolean isSettingsExemptLocked(UpdateRecord record) {
        if (!record.mRealRequest.isLocationSettingsIgnored()) {
            return false;
        }
        if (this.mIgnoreSettingsPackageWhitelist.contains(record.mReceiver.mCallerIdentity.mPackageName)) {
            return true;
        }
        return isProviderPackage(record.mReceiver.mCallerIdentity.mPackageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UpdateRecord {
        private boolean mIsForegroundUid;
        private Location mLastFixBroadcast;
        private long mLastStatusBroadcast;
        final String mProvider;
        private final LocationRequest mRealRequest;
        private final Receiver mReceiver;
        LocationRequest mRequest;
        private Throwable mStackTrace;

        /* synthetic */ UpdateRecord(LocationManagerService x0, String x1, LocationRequest x2, Receiver x3, AnonymousClass1 x4) {
            this(x1, x2, x3);
        }

        private UpdateRecord(String provider, LocationRequest request, Receiver receiver) {
            this.mProvider = provider;
            this.mRealRequest = request;
            this.mRequest = request;
            this.mReceiver = receiver;
            this.mIsForegroundUid = LocationManagerService.isImportanceForeground(LocationManagerService.this.mActivityManager.getPackageImportance(this.mReceiver.mCallerIdentity.mPackageName));
            if (LocationManagerService.D && receiver.mCallerIdentity.mPid == Process.myPid()) {
                this.mStackTrace = new Throwable();
            }
            ArrayList<UpdateRecord> records = (ArrayList) LocationManagerService.this.mRecordsByProvider.get(provider);
            if (records == null) {
                records = new ArrayList<>();
                LocationManagerService.this.mRecordsByProvider.put(provider, records);
            }
            if (!records.contains(this)) {
                records.add(this);
            }
            LocationManagerService.this.mRequestStatistics.startRequesting(this.mReceiver.mCallerIdentity.mPackageName, provider, request.getInterval(), this.mIsForegroundUid);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void updateForeground(boolean isForeground) {
            this.mIsForegroundUid = isForeground;
            LocationManagerService.this.mRequestStatistics.updateForeground(this.mReceiver.mCallerIdentity.mPackageName, this.mProvider, isForeground);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void disposeLocked(boolean removeReceiver) {
            String packageName = this.mReceiver.mCallerIdentity.mPackageName;
            LocationManagerService.this.mRequestStatistics.stopRequesting(packageName, this.mProvider);
            LocationManagerService.this.mLocationUsageLogger.logLocationApiUsage(1, 1, packageName, this.mRealRequest, this.mReceiver.isListener(), this.mReceiver.isPendingIntent(), null, LocationManagerService.this.mActivityManager.getPackageImportance(packageName));
            ArrayList<UpdateRecord> globalRecords = (ArrayList) LocationManagerService.this.mRecordsByProvider.get(this.mProvider);
            if (globalRecords != null) {
                globalRecords.remove(this);
            }
            if (removeReceiver) {
                HashMap<String, UpdateRecord> receiverRecords = this.mReceiver.mUpdateRecords;
                receiverRecords.remove(this.mProvider);
                if (receiverRecords.size() == 0) {
                    LocationManagerService.this.removeUpdatesLocked(this.mReceiver);
                }
            }
        }

        public String toString() {
            StringBuilder b = new StringBuilder("UpdateRecord[");
            b.append(this.mProvider);
            b.append(" ");
            b.append(this.mReceiver.mCallerIdentity.mPackageName);
            b.append("(");
            b.append(this.mReceiver.mCallerIdentity.mUid);
            if (this.mIsForegroundUid) {
                b.append(" foreground");
            } else {
                b.append(" background");
            }
            b.append(") ");
            b.append(this.mRealRequest);
            b.append(" ");
            b.append(this.mReceiver.mWorkSource);
            if (this.mStackTrace != null) {
                ByteArrayOutputStream tmp = new ByteArrayOutputStream();
                this.mStackTrace.printStackTrace(new PrintStream(tmp));
                b.append("\n\n");
                b.append(tmp.toString());
                b.append("\n");
            }
            b.append("]");
            return b.toString();
        }
    }

    @GuardedBy({"mLock"})
    private Receiver getReceiverLocked(ILocationListener listener, int pid, int uid, String packageName, WorkSource workSource, boolean hideFromAppOps) {
        IBinder binder = listener.asBinder();
        Receiver receiver = this.mReceivers.get(binder);
        if (receiver == null) {
            receiver = new Receiver(this, listener, null, pid, uid, packageName, workSource, hideFromAppOps, null);
            if (!linkToListenerDeathNotificationLocked(receiver.getListener().asBinder(), receiver)) {
                return null;
            }
            this.mReceivers.put(binder, receiver);
        }
        return receiver;
    }

    @GuardedBy({"mLock"})
    private Receiver getReceiverLocked(PendingIntent intent, int pid, int uid, String packageName, WorkSource workSource, boolean hideFromAppOps) {
        Receiver receiver = this.mReceivers.get(intent);
        if (receiver == null) {
            Receiver receiver2 = new Receiver(this, null, intent, pid, uid, packageName, workSource, hideFromAppOps, null);
            this.mReceivers.put(intent, receiver2);
            return receiver2;
        }
        return receiver;
    }

    private LocationRequest createSanitizedRequest(LocationRequest request, int resolutionLevel, boolean callerHasLocationHardwarePermission) {
        LocationRequest sanitizedRequest = new LocationRequest(request);
        if (!callerHasLocationHardwarePermission) {
            sanitizedRequest.setLowPowerMode(false);
        }
        if (resolutionLevel < 2) {
            int quality = sanitizedRequest.getQuality();
            if (quality == 100) {
                sanitizedRequest.setQuality(HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION);
            } else if (quality == 203) {
                sanitizedRequest.setQuality(201);
            }
            if (sanitizedRequest.getInterval() < 600000) {
                sanitizedRequest.setInterval(600000L);
            }
            if (sanitizedRequest.getFastestInterval() < 600000) {
                sanitizedRequest.setFastestInterval(600000L);
            }
        }
        if (sanitizedRequest.getFastestInterval() > sanitizedRequest.getInterval()) {
            sanitizedRequest.setFastestInterval(request.getInterval());
        }
        return sanitizedRequest;
    }

    private void checkPackageName(String packageName) {
        if (packageName == null) {
            throw new SecurityException("invalid package name: " + ((Object) null));
        }
        int uid = Binder.getCallingUid();
        String[] packages = this.mPackageManager.getPackagesForUid(uid);
        if (packages == null) {
            throw new SecurityException("invalid UID " + uid);
        }
        for (String pkg : packages) {
            if (packageName.equals(pkg)) {
                return;
            }
        }
        throw new SecurityException("invalid package name: " + packageName);
    }

    public void requestLocationUpdates(LocationRequest request, ILocationListener listener, PendingIntent intent, String packageName) {
        Object obj;
        LocationRequest request2;
        int uid;
        Object obj2;
        String str;
        Receiver receiver;
        Object obj3 = this.mLock;
        synchronized (obj3) {
            try {
                if (request == null) {
                    try {
                        request2 = DEFAULT_LOCATION_REQUEST;
                    } catch (Throwable th) {
                        th = th;
                        obj = obj3;
                        throw th;
                    }
                } else {
                    request2 = request;
                }
                try {
                    checkPackageName(packageName);
                    int allowedResolutionLevel = getCallerAllowedResolutionLevel();
                    checkResolutionLevelIsSufficientForProviderUseLocked(allowedResolutionLevel, request2.getProvider());
                    WorkSource workSource = request2.getWorkSource();
                    if (workSource != null) {
                        try {
                            if (!workSource.isEmpty()) {
                                this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_DEVICE_STATS", null);
                            }
                        } catch (Throwable th2) {
                            th = th2;
                            obj = obj3;
                            throw th;
                        }
                    }
                    boolean hideFromAppOps = request2.getHideFromAppOps();
                    if (hideFromAppOps) {
                        this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_APP_OPS_STATS", null);
                    }
                    if (request2.isLocationSettingsIgnored()) {
                        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS", null);
                    }
                    boolean z = true;
                    boolean callerHasLocationHardwarePermission = this.mContext.checkCallingPermission("android.permission.LOCATION_HARDWARE") == 0;
                    LocationRequest sanitizedRequest = createSanitizedRequest(request2, allowedResolutionLevel, callerHasLocationHardwarePermission);
                    int pid = Binder.getCallingPid();
                    int uid2 = Binder.getCallingUid();
                    long identity = Binder.clearCallingIdentity();
                    try {
                        checkLocationAccess(pid, uid2, packageName, allowedResolutionLevel);
                        try {
                            if (intent == null && listener == null) {
                                throw new IllegalArgumentException("need either listener or intent");
                            }
                            if (intent != null && listener != null) {
                                throw new IllegalArgumentException("cannot register both listener and intent");
                            }
                            LocationUsageLogger locationUsageLogger = this.mLocationUsageLogger;
                            boolean z2 = listener != null;
                            if (intent == null) {
                                z = false;
                            }
                            try {
                                locationUsageLogger.logLocationApiUsage(0, 1, packageName, request2, z2, z, null, this.mActivityManager.getPackageImportance(packageName));
                                if (intent != null) {
                                    uid = uid2;
                                    obj2 = obj3;
                                    str = packageName;
                                    try {
                                        receiver = getReceiverLocked(intent, pid, uid, packageName, workSource, hideFromAppOps);
                                    } catch (Throwable th3) {
                                        th = th3;
                                        Binder.restoreCallingIdentity(identity);
                                        throw th;
                                    }
                                } else {
                                    uid = uid2;
                                    obj2 = obj3;
                                    str = packageName;
                                    try {
                                        receiver = getReceiverLocked(listener, pid, uid, packageName, workSource, hideFromAppOps);
                                    } catch (Throwable th4) {
                                        th = th4;
                                        Binder.restoreCallingIdentity(identity);
                                        throw th;
                                    }
                                }
                            } catch (Throwable th5) {
                                th = th5;
                            }
                            try {
                                requestLocationUpdatesLocked(sanitizedRequest, receiver, uid, str);
                                Binder.restoreCallingIdentity(identity);
                            } catch (Throwable th6) {
                                th = th6;
                                Binder.restoreCallingIdentity(identity);
                                throw th;
                            }
                        } catch (Throwable th7) {
                            th = th7;
                        }
                    } catch (Throwable th8) {
                        th = th8;
                    }
                } catch (Throwable th9) {
                    th = th9;
                    obj = obj3;
                }
            } catch (Throwable th10) {
                th = th10;
            }
        }
    }

    @GuardedBy({"mLock"})
    private void requestLocationUpdatesLocked(LocationRequest request, Receiver receiver, int uid, String packageName) {
        if (request == null) {
            request = DEFAULT_LOCATION_REQUEST;
        }
        String name = request.getProvider();
        if (name == null) {
            throw new IllegalArgumentException("provider name must not be null");
        }
        LocationProvider provider = getLocationProviderLocked(name);
        if (provider == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + name);
        }
        UpdateRecord record = new UpdateRecord(this, name, request, receiver, null);
        if (D) {
            StringBuilder sb = new StringBuilder();
            sb.append("request ");
            sb.append(Integer.toHexString(System.identityHashCode(receiver)));
            sb.append(" ");
            sb.append(name);
            sb.append(" ");
            sb.append(request);
            sb.append(" from ");
            sb.append(packageName);
            sb.append("(");
            sb.append(uid);
            sb.append(" ");
            sb.append(record.mIsForegroundUid ? "foreground" : "background");
            sb.append(isThrottlingExemptLocked(receiver.mCallerIdentity) ? " [whitelisted]" : "");
            sb.append(")");
            Log.d(TAG, sb.toString());
        }
        UpdateRecord oldRecord = receiver.mUpdateRecords.put(name, record);
        if (oldRecord != null) {
            oldRecord.disposeLocked(false);
        }
        if (!provider.isUseableLocked() && !isSettingsExemptLocked(record)) {
            receiver.callProviderEnabledLocked(name, false);
        }
        applyRequirementsLocked(name);
        receiver.updateMonitoring(true);
    }

    public void removeUpdates(ILocationListener listener, PendingIntent intent, String packageName) {
        Receiver receiver;
        checkPackageName(packageName);
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        if (intent == null && listener == null) {
            throw new IllegalArgumentException("need either listener or intent");
        }
        if (intent != null && listener != null) {
            throw new IllegalArgumentException("cannot register both listener and intent");
        }
        synchronized (this.mLock) {
            if (intent != null) {
                receiver = getReceiverLocked(intent, pid, uid, packageName, (WorkSource) null, false);
            } else {
                receiver = getReceiverLocked(listener, pid, uid, packageName, (WorkSource) null, false);
            }
            long identity = Binder.clearCallingIdentity();
            removeUpdatesLocked(receiver);
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void removeUpdatesLocked(Receiver receiver) {
        if (D) {
            Log.i(TAG, "remove " + Integer.toHexString(System.identityHashCode(receiver)));
        }
        if (this.mReceivers.remove(receiver.mKey) != null && receiver.isListener()) {
            unlinkFromListenerDeathNotificationLocked(receiver.getListener().asBinder(), receiver);
            receiver.clearPendingBroadcastsLocked();
        }
        receiver.updateMonitoring(false);
        HashSet<String> providers = new HashSet<>();
        HashMap<String, UpdateRecord> oldRecords = receiver.mUpdateRecords;
        if (oldRecords != null) {
            for (UpdateRecord record : oldRecords.values()) {
                record.disposeLocked(false);
            }
            providers.addAll(oldRecords.keySet());
        }
        Iterator<String> it = providers.iterator();
        while (it.hasNext()) {
            String provider = it.next();
            applyRequirementsLocked(provider);
        }
    }

    public Location getLastLocation(LocationRequest r, String packageName) {
        synchronized (this.mLock) {
            LocationRequest request = r != null ? r : DEFAULT_LOCATION_REQUEST;
            int allowedResolutionLevel = getCallerAllowedResolutionLevel();
            checkPackageName(packageName);
            checkResolutionLevelIsSufficientForProviderUseLocked(allowedResolutionLevel, request.getProvider());
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            long identity = Binder.clearCallingIdentity();
            try {
                try {
                    if (this.mBlacklist.isBlacklisted(packageName)) {
                        if (D) {
                            Log.d(TAG, "not returning last loc for blacklisted app: " + packageName);
                        }
                        Binder.restoreCallingIdentity(identity);
                        return null;
                    }
                    String name = request.getProvider();
                    if (name == null) {
                        name = "fused";
                    }
                    LocationProvider provider = getLocationProviderLocked(name);
                    if (provider == null) {
                        Binder.restoreCallingIdentity(identity);
                        return null;
                    } else if (!isCurrentProfileLocked(UserHandle.getUserId(uid)) && !isProviderPackage(packageName)) {
                        Binder.restoreCallingIdentity(identity);
                        return null;
                    } else if (!provider.isUseableLocked()) {
                        Binder.restoreCallingIdentity(identity);
                        return null;
                    } else {
                        Location location = allowedResolutionLevel < 2 ? this.mLastLocationCoarseInterval.get(name) : this.mLastLocation.get(name);
                        if (location == null) {
                            Binder.restoreCallingIdentity(identity);
                            return null;
                        }
                        String op = resolutionLevelToOpStr(allowedResolutionLevel);
                        long locationAgeMs = SystemClock.elapsedRealtime() - (location.getElapsedRealtimeNanos() / NANOS_PER_MILLI);
                        try {
                            if (locationAgeMs > Settings.Global.getLong(this.mContext.getContentResolver(), "location_last_location_max_age_millis", DEFAULT_LAST_LOCATION_MAX_AGE_MS)) {
                                try {
                                    if (this.mAppOps.unsafeCheckOp(op, uid, packageName) == 4) {
                                        Binder.restoreCallingIdentity(identity);
                                        return null;
                                    }
                                } catch (Throwable th) {
                                    th = th;
                                    Binder.restoreCallingIdentity(identity);
                                    throw th;
                                }
                            }
                            Location lastLocation = null;
                            try {
                                if (allowedResolutionLevel < 2) {
                                    Location noGPSLocation = location.getExtraLocation("noGPSLocation");
                                    if (noGPSLocation != null) {
                                        lastLocation = new Location(this.mLocationFudger.getOrCreate(noGPSLocation));
                                    }
                                } else {
                                    lastLocation = new Location(location);
                                }
                                if (lastLocation != null && !reportLocationAccessNoThrow(pid, uid, packageName, allowedResolutionLevel)) {
                                    if (D) {
                                        Log.d(TAG, "not returning last loc for no op app: " + packageName);
                                    }
                                    lastLocation = null;
                                }
                                Binder.restoreCallingIdentity(identity);
                                return lastLocation;
                            } catch (Throwable th2) {
                                th = th2;
                                Binder.restoreCallingIdentity(identity);
                                throw th;
                            }
                        } catch (Throwable th3) {
                            th = th3;
                        }
                    }
                } catch (Throwable th4) {
                    th = th4;
                }
            } catch (Throwable th5) {
                th = th5;
            }
        }
    }

    public LocationTime getGnssTimeMillis() {
        synchronized (this.mLock) {
            Location location = this.mLastLocation.get("gps");
            if (location == null) {
                return null;
            }
            long currentNanos = SystemClock.elapsedRealtimeNanos();
            long deltaMs = (currentNanos - location.getElapsedRealtimeNanos()) / NANOS_PER_MILLI;
            return new LocationTime(location.getTime() + deltaMs, currentNanos);
        }
    }

    public boolean injectLocation(Location location) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to inject location");
        this.mContext.enforceCallingPermission("android.permission.ACCESS_FINE_LOCATION", "Access Fine Location permission not granted to inject Location");
        if (location == null) {
            if (D) {
                Log.d(TAG, "injectLocation(): called with null location");
            }
            return false;
        }
        synchronized (this.mLock) {
            LocationProvider provider = getLocationProviderLocked(location.getProvider());
            if (provider != null && provider.isUseableLocked()) {
                if (this.mLastLocation.get(provider.getName()) != null) {
                    return false;
                }
                updateLastLocationLocked(location, provider.getName());
                return true;
            }
            return false;
        }
    }

    public void requestGeofence(LocationRequest request, Geofence geofence, PendingIntent intent, String packageName) {
        LocationRequest request2;
        if (request2 == null) {
            request2 = DEFAULT_LOCATION_REQUEST;
        }
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkResolutionLevelIsSufficientForGeofenceUse(allowedResolutionLevel);
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + ((Object) null));
        }
        checkPackageName(packageName);
        synchronized (this.mLock) {
            try {
                checkResolutionLevelIsSufficientForProviderUseLocked(allowedResolutionLevel, request2.getProvider());
            } finally {
                th = th;
                while (true) {
                    try {
                        break;
                    } catch (Throwable th) {
                        th = th;
                    }
                }
            }
        }
        boolean callerHasLocationHardwarePermission = this.mContext.checkCallingPermission("android.permission.LOCATION_HARDWARE") == 0;
        LocationRequest sanitizedRequest = createSanitizedRequest(request2, allowedResolutionLevel, callerHasLocationHardwarePermission);
        if (D) {
            Log.d(TAG, "requestGeofence: " + sanitizedRequest + " " + geofence + " " + intent);
        }
        int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != 0) {
            Log.w(TAG, "proximity alerts are currently available only to the primary user");
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                try {
                    this.mLocationUsageLogger.logLocationApiUsage(0, 4, packageName, request2, false, true, geofence, this.mActivityManager.getPackageImportance(packageName));
                } catch (Throwable th2) {
                    th = th2;
                    Binder.restoreCallingIdentity(identity);
                    throw th;
                }
            }
            this.mGeofenceManager.addFence(sanitizedRequest, geofence, intent, allowedResolutionLevel, uid, packageName);
            Binder.restoreCallingIdentity(identity);
        } catch (Throwable th3) {
            th = th3;
        }
    }

    public void removeGeofence(Geofence geofence, PendingIntent intent, String packageName) {
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + ((Object) null));
        }
        checkPackageName(packageName);
        if (D) {
            Log.d(TAG, "removeGeofence: " + geofence + " " + intent);
        }
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                this.mLocationUsageLogger.logLocationApiUsage(1, 4, packageName, null, false, true, geofence, this.mActivityManager.getPackageImportance(packageName));
            }
            this.mGeofenceManager.removeFence(geofence, intent);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean registerGnssStatusCallback(IGnssStatusListener listener, String packageName) {
        return addGnssDataListener(listener, packageName, "GnssStatusListener", this.mGnssStatusProvider, this.mGnssStatusListeners, new Consumer() { // from class: com.android.server.-$$Lambda$1kw1pGRY14l4iRI8vioJeswbbZ0
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                LocationManagerService.this.unregisterGnssStatusCallback((IGnssStatusListener) obj);
            }
        });
    }

    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        removeGnssDataListener(listener, this.mGnssStatusProvider, this.mGnssStatusListeners);
    }

    public boolean addGnssMeasurementsListener(IGnssMeasurementsListener listener, String packageName) {
        return addGnssDataListener(listener, packageName, "GnssMeasurementsListener", this.mGnssMeasurementsProvider, this.mGnssMeasurementsListeners, new Consumer() { // from class: com.android.server.-$$Lambda$XnEj1qgrS2tLlw6uNlntfcuKl88
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                LocationManagerService.this.removeGnssMeasurementsListener((IGnssMeasurementsListener) obj);
            }
        });
    }

    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        removeGnssDataListener(listener, this.mGnssMeasurementsProvider, this.mGnssMeasurementsListeners);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static abstract class LinkedListenerBase implements IBinder.DeathRecipient {
        protected final CallerIdentity mCallerIdentity;
        protected final String mListenerName;

        /* synthetic */ LinkedListenerBase(CallerIdentity x0, String x1, AnonymousClass1 x2) {
            this(x0, x1);
        }

        private LinkedListenerBase(CallerIdentity callerIdentity, String listenerName) {
            this.mCallerIdentity = callerIdentity;
            this.mListenerName = listenerName;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class LinkedListener<TListener> extends LinkedListenerBase {
        private final Consumer<TListener> mBinderDeathCallback;
        private final TListener mListener;

        /* synthetic */ LinkedListener(Object x0, String x1, CallerIdentity x2, Consumer x3, AnonymousClass1 x4) {
            this(x0, x1, x2, x3);
        }

        private LinkedListener(TListener listener, String listenerName, CallerIdentity callerIdentity, Consumer<TListener> binderDeathCallback) {
            super(callerIdentity, listenerName, null);
            this.mListener = listener;
            this.mBinderDeathCallback = binderDeathCallback;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, "Remote " + this.mListenerName + " died.");
            }
            this.mBinderDeathCallback.accept(this.mListener);
        }
    }

    /* JADX WARN: Can't wrap try/catch for region: R(14:15|16|17|18|(7:(10:23|24|(2:26|(5:28|29|30|31|32))|38|39|40|29|30|31|32)|39|40|29|30|31|32)|44|(1:46)(1:52)|47|48|49|50|24|(0)|38) */
    /* JADX WARN: Code restructure failed: missing block: B:41:0x0099, code lost:
        r0 = th;
     */
    /* JADX WARN: Removed duplicated region for block: B:29:0x007c A[Catch: all -> 0x0099, TRY_LEAVE, TryCatch #2 {all -> 0x0099, blocks: (B:27:0x0076, B:29:0x007c, B:26:0x0073), top: B:58:0x0073 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private <TListener extends android.os.IInterface> boolean addGnssDataListener(TListener r21, java.lang.String r22, java.lang.String r23, com.android.server.location.RemoteListenerHelper<TListener> r24, android.util.ArrayMap<android.os.IBinder, com.android.server.LocationManagerService.LinkedListener<TListener>> r25, java.util.function.Consumer<TListener> r26) {
        /*
            r20 = this;
            r1 = r20
            r11 = r22
            r12 = r24
            boolean r0 = r1.hasGnssPermissions(r11)
            r2 = 0
            if (r0 == 0) goto Laf
            if (r12 != 0) goto L13
            r3 = r21
            goto Lb1
        L13:
            com.android.server.location.CallerIdentity r0 = new com.android.server.location.CallerIdentity
            int r3 = android.os.Binder.getCallingUid()
            int r4 = android.os.Binder.getCallingPid()
            r0.<init>(r3, r4, r11)
            r13 = r0
            com.android.server.LocationManagerService$LinkedListener r0 = new com.android.server.LocationManagerService$LinkedListener
            r10 = 0
            r5 = r0
            r6 = r21
            r7 = r23
            r8 = r13
            r9 = r26
            r5.<init>(r6, r7, r8, r9, r10)
            r14 = r0
            android.os.IBinder r15 = r21.asBinder()
            java.lang.Object r10 = r1.mLock
            monitor-enter(r10)
            boolean r0 = r1.linkToListenerDeathNotificationLocked(r15, r14)     // Catch: java.lang.Throwable -> La6
            if (r0 != 0) goto L3f
            monitor-exit(r10)     // Catch: java.lang.Throwable -> La6
            return r2
        L3f:
            r9 = r25
            r9.put(r15, r14)     // Catch: java.lang.Throwable -> La6
            long r2 = android.os.Binder.clearCallingIdentity()     // Catch: java.lang.Throwable -> La6
            r16 = r2
            com.android.server.location.GnssMeasurementsProvider r0 = r1.mGnssMeasurementsProvider     // Catch: java.lang.Throwable -> L9d
            if (r12 == r0) goto L56
            com.android.server.location.GnssStatusListenerHelper r0 = r1.mGnssStatusProvider     // Catch: java.lang.Throwable -> L9d
            if (r12 != r0) goto L53
            goto L56
        L53:
            r19 = r10
            goto L76
        L56:
            com.android.server.LocationUsageLogger r2 = r1.mLocationUsageLogger     // Catch: java.lang.Throwable -> L9d
            r3 = 0
            com.android.server.location.GnssMeasurementsProvider r0 = r1.mGnssMeasurementsProvider     // Catch: java.lang.Throwable -> L9d
            if (r12 != r0) goto L60
            r0 = 2
            r4 = r0
            goto L62
        L60:
            r0 = 3
            r4 = r0
        L62:
            r6 = 0
            r7 = 1
            r8 = 0
            r0 = 0
            android.app.ActivityManager r5 = r1.mActivityManager     // Catch: java.lang.Throwable -> L9d
            int r18 = r5.getPackageImportance(r11)     // Catch: java.lang.Throwable -> L9d
            r5 = r22
            r9 = r0
            r19 = r10
            r10 = r18
            r2.logLocationApiUsage(r3, r4, r5, r6, r7, r8, r9, r10)     // Catch: java.lang.Throwable -> L99
        L76:
            boolean r0 = r1.isThrottlingExemptLocked(r13)     // Catch: java.lang.Throwable -> L99
            if (r0 != 0) goto L8c
            android.app.ActivityManager r0 = r1.mActivityManager     // Catch: java.lang.Throwable -> L99
            int r0 = r0.getPackageImportance(r11)     // Catch: java.lang.Throwable -> L99
            boolean r0 = isImportanceForeground(r0)     // Catch: java.lang.Throwable -> L99
            if (r0 == 0) goto L89
            goto L8c
        L89:
            r3 = r21
            goto L91
        L8c:
            r3 = r21
            r12.addListener(r3, r13)     // Catch: java.lang.Throwable -> L97
        L91:
            r0 = 1
            android.os.Binder.restoreCallingIdentity(r16)     // Catch: java.lang.Throwable -> Lad
            monitor-exit(r19)     // Catch: java.lang.Throwable -> Lad
            return r0
        L97:
            r0 = move-exception
            goto La2
        L99:
            r0 = move-exception
            r3 = r21
            goto La2
        L9d:
            r0 = move-exception
            r3 = r21
            r19 = r10
        La2:
            android.os.Binder.restoreCallingIdentity(r16)     // Catch: java.lang.Throwable -> Lad
            throw r0     // Catch: java.lang.Throwable -> Lad
        La6:
            r0 = move-exception
            r3 = r21
            r19 = r10
        Lab:
            monitor-exit(r19)     // Catch: java.lang.Throwable -> Lad
            throw r0
        Lad:
            r0 = move-exception
            goto Lab
        Laf:
            r3 = r21
        Lb1:
            return r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.LocationManagerService.addGnssDataListener(android.os.IInterface, java.lang.String, java.lang.String, com.android.server.location.RemoteListenerHelper, android.util.ArrayMap, java.util.function.Consumer):boolean");
    }

    private <TListener extends IInterface> void removeGnssDataListener(TListener listener, RemoteListenerHelper<TListener> gnssDataProvider, ArrayMap<IBinder, LinkedListener<TListener>> gnssDataListeners) {
        int i;
        if (gnssDataProvider == null) {
            return;
        }
        IBinder binder = listener.asBinder();
        synchronized (this.mLock) {
            try {
                try {
                    LinkedListener<TListener> linkedListener = gnssDataListeners.remove(binder);
                    if (linkedListener == null) {
                        return;
                    }
                    long identity = Binder.clearCallingIdentity();
                    if (gnssDataProvider == this.mGnssMeasurementsProvider || gnssDataProvider == this.mGnssStatusProvider) {
                        LocationUsageLogger locationUsageLogger = this.mLocationUsageLogger;
                        if (gnssDataProvider == this.mGnssMeasurementsProvider) {
                            i = 2;
                        } else {
                            i = 3;
                        }
                        locationUsageLogger.logLocationApiUsage(1, i, linkedListener.mCallerIdentity.mPackageName, null, true, false, null, this.mActivityManager.getPackageImportance(linkedListener.mCallerIdentity.mPackageName));
                    }
                    Binder.restoreCallingIdentity(identity);
                    unlinkFromListenerDeathNotificationLocked(binder, linkedListener);
                    gnssDataProvider.removeListener(listener);
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    private boolean linkToListenerDeathNotificationLocked(IBinder binder, LinkedListenerBase linkedListener) {
        try {
            binder.linkToDeath(linkedListener, 0);
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "Could not link " + linkedListener.mListenerName + " death callback.", e);
            return false;
        }
    }

    private boolean unlinkFromListenerDeathNotificationLocked(IBinder binder, LinkedListenerBase linkedListener) {
        try {
            binder.unlinkToDeath(linkedListener, 0);
            return true;
        } catch (NoSuchElementException e) {
            Log.w(TAG, "Could not unlink " + linkedListener.mListenerName + " death callback.", e);
            return false;
        }
    }

    public void injectGnssMeasurementCorrections(GnssMeasurementCorrections measurementCorrections, String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to inject GNSS measurement corrections.");
        if (!hasGnssPermissions(packageName)) {
            Slog.e(TAG, "Can not inject GNSS corrections due to no permission.");
            return;
        }
        GnssMeasurementCorrectionsProvider gnssMeasurementCorrectionsProvider = this.mGnssMeasurementCorrectionsProvider;
        if (gnssMeasurementCorrectionsProvider == null) {
            Slog.e(TAG, "Can not inject GNSS corrections. GNSS measurement corrections provider not available.");
        } else {
            gnssMeasurementCorrectionsProvider.injectGnssMeasurementCorrections(measurementCorrections);
        }
    }

    public long getGnssCapabilities(String packageName) {
        GnssCapabilitiesProvider gnssCapabilitiesProvider;
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to obtain GNSS chipset capabilities.");
        if (!hasGnssPermissions(packageName) || (gnssCapabilitiesProvider = this.mGnssCapabilitiesProvider) == null) {
            return -1L;
        }
        return gnssCapabilitiesProvider.getGnssCapabilities();
    }

    public boolean addGnssNavigationMessageListener(IGnssNavigationMessageListener listener, String packageName) {
        return addGnssDataListener(listener, packageName, "GnssNavigationMessageListener", this.mGnssNavigationMessageProvider, this.mGnssNavigationMessageListeners, new Consumer() { // from class: com.android.server.-$$Lambda$wg7j1ZorSDGIu2L17I_NmjcwgzQ
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                LocationManagerService.this.removeGnssNavigationMessageListener((IGnssNavigationMessageListener) obj);
            }
        });
    }

    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        removeGnssDataListener(listener, this.mGnssNavigationMessageProvider, this.mGnssNavigationMessageListeners);
    }

    public boolean sendExtraCommand(String providerName, String command, Bundle extras) {
        if (providerName == null) {
            throw new NullPointerException();
        }
        synchronized (this.mLock) {
            checkResolutionLevelIsSufficientForProviderUseLocked(getCallerAllowedResolutionLevel(), providerName);
            this.mLocationUsageLogger.logLocationApiUsage(0, 5, providerName);
            if (this.mContext.checkCallingOrSelfPermission(ACCESS_LOCATION_EXTRA_COMMANDS) != 0) {
                throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
            }
            LocationProvider provider = getLocationProviderLocked(providerName);
            if (provider != null) {
                provider.sendExtraCommandLocked(command, extras);
            }
            this.mLocationUsageLogger.logLocationApiUsage(1, 5, providerName);
        }
        return true;
    }

    public boolean sendNiResponse(int notifId, int userResponse) {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw new SecurityException("calling sendNiResponse from outside of the system is not allowed");
        }
        try {
            return this.mNetInitiatedListener.sendNiResponse(notifId, userResponse);
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException in LocationManagerService.sendNiResponse");
            return false;
        }
    }

    public ProviderProperties getProviderProperties(String providerName) {
        synchronized (this.mLock) {
            checkResolutionLevelIsSufficientForProviderUseLocked(getCallerAllowedResolutionLevel(), providerName);
            LocationProvider provider = getLocationProviderLocked(providerName);
            if (provider == null) {
                return null;
            }
            return provider.getPropertiesLocked();
        }
    }

    public boolean isProviderPackage(String packageName) {
        synchronized (this.mLock) {
            Iterator<LocationProvider> it = this.mProviders.iterator();
            while (it.hasNext()) {
                LocationProvider provider = it.next();
                if (provider.getPackagesLocked().contains(packageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void setExtraLocationControllerPackage(String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "android.permission.LOCATION_HARDWARE permission required");
        synchronized (this.mLock) {
            this.mExtraLocationControllerPackage = packageName;
        }
    }

    public String getExtraLocationControllerPackage() {
        String str;
        synchronized (this.mLock) {
            str = this.mExtraLocationControllerPackage;
        }
        return str;
    }

    public void setExtraLocationControllerPackageEnabled(boolean enabled) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "android.permission.LOCATION_HARDWARE permission required");
        synchronized (this.mLock) {
            this.mExtraLocationControllerPackageEnabled = enabled;
        }
    }

    public boolean isExtraLocationControllerPackageEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mExtraLocationControllerPackageEnabled && this.mExtraLocationControllerPackage != null;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isLocationEnabled() {
        return isLocationEnabledForUser(this.mCurrentUserId);
    }

    public boolean isLocationEnabledForUser(int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS", "Requires INTERACT_ACROSS_USERS permission");
        }
        long identity = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "location_mode", 0, userId) != 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean isProviderEnabledForUser(String providerName, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS", "Requires INTERACT_ACROSS_USERS permission");
        }
        boolean z = false;
        if ("fused".equals(providerName)) {
            return false;
        }
        synchronized (this.mLock) {
            LocationProvider provider = getLocationProviderLocked(providerName);
            if (provider != null && provider.isUseableForUserLocked(userId)) {
                z = true;
            }
        }
        return z;
    }

    @GuardedBy({"mLock"})
    private static boolean shouldBroadcastSafeLocked(Location loc, Location lastLoc, UpdateRecord record, long now) {
        if (lastLoc != null) {
            long minTime = record.mRealRequest.getFastestInterval();
            long delta = (loc.getElapsedRealtimeNanos() - lastLoc.getElapsedRealtimeNanos()) / NANOS_PER_MILLI;
            if (delta < minTime - 100) {
                return false;
            }
            double minDistance = record.mRealRequest.getSmallestDisplacement();
            return (minDistance <= 0.0d || ((double) loc.distanceTo(lastLoc)) > minDistance) && record.mRealRequest.getNumUpdates() > 0 && record.mRealRequest.getExpireAt() >= now;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:94:0x0272, code lost:
        if (r11 != 2) goto L91;
     */
    @com.android.internal.annotations.GuardedBy({"mLock"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void handleLocationChangedLocked(android.location.Location r30, com.android.server.LocationManagerService.LocationProvider r31) {
        /*
            Method dump skipped, instructions count: 810
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.LocationManagerService.handleLocationChangedLocked(android.location.Location, com.android.server.LocationManagerService$LocationProvider):void");
    }

    @GuardedBy({"mLock"})
    private void updateLastLocationLocked(Location location, String provider) {
        Location noGPSLocation = location.getExtraLocation("noGPSLocation");
        Location lastLocation = this.mLastLocation.get(provider);
        if (lastLocation == null) {
            lastLocation = new Location(provider);
            this.mLastLocation.put(provider, lastLocation);
        } else {
            Location lastNoGPSLocation = lastLocation.getExtraLocation("noGPSLocation");
            if (noGPSLocation == null && lastNoGPSLocation != null) {
                location.setExtraLocation("noGPSLocation", lastNoGPSLocation);
            }
        }
        lastLocation.set(location);
    }

    public boolean geocoderIsPresent() {
        return this.mGeocodeProvider != null;
    }

    public String getFromLocation(double latitude, double longitude, int maxResults, GeocoderParams params, List<Address> addrs) {
        GeocoderProxy geocoderProxy = this.mGeocodeProvider;
        if (geocoderProxy != null) {
            return geocoderProxy.getFromLocation(latitude, longitude, maxResults, params, addrs);
        }
        return null;
    }

    public String getFromLocationName(String locationName, double lowerLeftLatitude, double lowerLeftLongitude, double upperRightLatitude, double upperRightLongitude, int maxResults, GeocoderParams params, List<Address> addrs) {
        GeocoderProxy geocoderProxy = this.mGeocodeProvider;
        if (geocoderProxy != null) {
            return geocoderProxy.getFromLocationName(locationName, lowerLeftLatitude, lowerLeftLongitude, upperRightLatitude, upperRightLongitude, maxResults, params, addrs);
        }
        return null;
    }

    private boolean canCallerAccessMockLocation(String opPackageName) {
        return this.mAppOps.checkOp(58, Binder.getCallingUid(), opPackageName) == 0;
    }

    public void addTestProvider(String name, ProviderProperties properties, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        if ("passive".equals(name)) {
            throw new IllegalArgumentException("Cannot mock the passive location provider");
        }
        synchronized (this.mLock) {
            long identity = Binder.clearCallingIdentity();
            LocationProvider oldProvider = getLocationProviderLocked(name);
            if (oldProvider != null) {
                if (oldProvider.isMock()) {
                    throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
                }
                removeProviderLocked(oldProvider);
            }
            MockLocationProvider mockProviderManager = new MockLocationProvider(this, name, null);
            addProviderLocked(mockProviderManager);
            mockProviderManager.attachLocked(new MockProvider(this.mContext, mockProviderManager, properties));
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void removeTestProvider(String name, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            long identity = Binder.clearCallingIdentity();
            LocationProvider testProvider = getLocationProviderLocked(name);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + name + "\" unknown");
            }
            removeProviderLocked(testProvider);
            LocationProvider realProvider = null;
            Iterator<LocationProvider> it = this.mRealProviders.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                LocationProvider provider = it.next();
                if (name.equals(provider.getName())) {
                    realProvider = provider;
                    break;
                }
            }
            if (realProvider != null) {
                addProviderLocked(realProvider);
            }
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void setTestProviderLocation(String providerName, Location location, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            LocationProvider testProvider = getLocationProviderLocked(providerName);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + providerName + "\" unknown");
            }
            String locationProvider = location.getProvider();
            if (!TextUtils.isEmpty(locationProvider) && !providerName.equals(locationProvider)) {
                EventLog.writeEvent(1397638484, "33091107", Integer.valueOf(Binder.getCallingUid()), providerName + "!=" + location.getProvider());
            }
            ((MockLocationProvider) testProvider).setLocationLocked(location);
        }
    }

    public void setTestProviderEnabled(String providerName, boolean enabled, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            LocationProvider testProvider = getLocationProviderLocked(providerName);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + providerName + "\" unknown");
            }
            ((MockLocationProvider) testProvider).setEnabledLocked(enabled);
        }
    }

    public void setTestProviderStatus(String providerName, int status, Bundle extras, long updateTime, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            LocationProvider testProvider = getLocationProviderLocked(providerName);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + providerName + "\" unknown");
            }
            ((MockLocationProvider) testProvider).setStatusLocked(status, extras, updateTime);
        }
    }

    public List<LocationRequest> getTestProviderCurrentRequests(String providerName, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return Collections.emptyList();
        }
        synchronized (this.mLock) {
            LocationProvider testProvider = getLocationProviderLocked(providerName);
            if (testProvider == null || !testProvider.isMock()) {
                throw new IllegalArgumentException("Provider \"" + providerName + "\" unknown");
            }
            MockLocationProvider provider = (MockLocationProvider) testProvider;
            if (provider.mCurrentRequest == null) {
                return Collections.emptyList();
            }
            List<LocationRequest> requests = new ArrayList<>();
            for (LocationRequest request : provider.mCurrentRequest.locationRequests) {
                requests.add(new LocationRequest(request));
            }
            return requests;
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            synchronized (this.mLock) {
                if (args.length > 0 && args[0].equals("--gnssmetrics")) {
                    if (this.mGnssMetricsProvider != null) {
                        pw.append((CharSequence) this.mGnssMetricsProvider.getGnssMetricsAsProtoString());
                    }
                    return;
                }
                pw.println("Current Location Manager state:");
                pw.print("  Current System Time: " + TimeUtils.logTimeOfDay(System.currentTimeMillis()));
                pw.println(", Current Elapsed Time: " + TimeUtils.formatDuration(SystemClock.elapsedRealtime()));
                pw.println("  Current user: " + this.mCurrentUserId + " " + Arrays.toString(this.mCurrentUserProfiles));
                StringBuilder sb = new StringBuilder();
                sb.append("  Location mode: ");
                sb.append(isLocationEnabled());
                pw.println(sb.toString());
                pw.println("  Battery Saver Location Mode: " + PowerManager.locationPowerSaveModeToString(this.mBatterySaverMode));
                pw.println("  Location Listeners:");
                for (Receiver receiver : this.mReceivers.values()) {
                    pw.println("    " + receiver);
                }
                pw.println("  Active Records by Provider:");
                for (Map.Entry<String, ArrayList<UpdateRecord>> entry : this.mRecordsByProvider.entrySet()) {
                    pw.println("    " + entry.getKey() + ":");
                    Iterator<UpdateRecord> it = entry.getValue().iterator();
                    while (it.hasNext()) {
                        UpdateRecord record = it.next();
                        pw.println("      " + record);
                    }
                }
                pw.println("  Active GnssMeasurement Listeners:");
                dumpGnssDataListenersLocked(pw, this.mGnssMeasurementsListeners);
                pw.println("  Active GnssNavigationMessage Listeners:");
                dumpGnssDataListenersLocked(pw, this.mGnssNavigationMessageListeners);
                pw.println("  Active GnssStatus Listeners:");
                dumpGnssDataListenersLocked(pw, this.mGnssStatusListeners);
                pw.println("  Historical Records by Provider:");
                for (Map.Entry<LocationRequestStatistics.PackageProviderKey, LocationRequestStatistics.PackageStatistics> entry2 : this.mRequestStatistics.statistics.entrySet()) {
                    LocationRequestStatistics.PackageProviderKey key = entry2.getKey();
                    LocationRequestStatistics.PackageStatistics stats = entry2.getValue();
                    pw.println("    " + key.packageName + ": " + key.providerName + ": " + stats);
                }
                pw.println("  Last Known Locations:");
                for (Map.Entry<String, Location> entry3 : this.mLastLocation.entrySet()) {
                    String provider = entry3.getKey();
                    Location location = entry3.getValue();
                    pw.println("    " + provider + ": " + location);
                }
                pw.println("  Last Known Locations Coarse Intervals:");
                for (Map.Entry<String, Location> entry4 : this.mLastLocationCoarseInterval.entrySet()) {
                    String provider2 = entry4.getKey();
                    Location location2 = entry4.getValue();
                    pw.println("    " + provider2 + ": " + location2);
                }
                if (this.mGeofenceManager != null) {
                    this.mGeofenceManager.dump(pw);
                } else {
                    pw.println("  Geofences: null");
                }
                if (this.mBlacklist != null) {
                    pw.append("  ");
                    this.mBlacklist.dump(pw);
                } else {
                    pw.println("  mBlacklist=null");
                }
                if (this.mExtraLocationControllerPackage != null) {
                    pw.println(" Location controller extra package: " + this.mExtraLocationControllerPackage + " enabled: " + this.mExtraLocationControllerPackageEnabled);
                }
                if (!this.mBackgroundThrottlePackageWhitelist.isEmpty()) {
                    pw.println("  Throttling Whitelisted Packages:");
                    Iterator<String> it2 = this.mBackgroundThrottlePackageWhitelist.iterator();
                    while (it2.hasNext()) {
                        String packageName = it2.next();
                        pw.println("    " + packageName);
                    }
                }
                if (!this.mIgnoreSettingsPackageWhitelist.isEmpty()) {
                    pw.println("  Bypass Whitelisted Packages:");
                    Iterator<String> it3 = this.mIgnoreSettingsPackageWhitelist.iterator();
                    while (it3.hasNext()) {
                        String packageName2 = it3.next();
                        pw.println("    " + packageName2);
                    }
                }
                if (this.mLocationFudger != null) {
                    pw.append("  fudger: ");
                    this.mLocationFudger.dump(fd, pw, args);
                } else {
                    pw.println("  fudger: null");
                }
                if (args.length <= 0 || !"short".equals(args[0])) {
                    Iterator<LocationProvider> it4 = this.mProviders.iterator();
                    while (it4.hasNext()) {
                        LocationProvider provider3 = it4.next();
                        provider3.dumpLocked(fd, pw, args);
                    }
                    if (this.mGnssBatchingInProgress) {
                        pw.println("  GNSS batching in progress");
                    }
                }
            }
        }
    }

    @GuardedBy({"mLock"})
    private void dumpGnssDataListenersLocked(PrintWriter pw, ArrayMap<IBinder, ? extends LinkedListenerBase> gnssDataListeners) {
        for (LinkedListenerBase listener : gnssDataListeners.values()) {
            CallerIdentity callerIdentity = listener.mCallerIdentity;
            pw.println("    " + callerIdentity.mPid + " " + callerIdentity.mUid + " " + callerIdentity.mPackageName + ": " + isThrottlingExemptLocked(callerIdentity));
        }
    }
}
