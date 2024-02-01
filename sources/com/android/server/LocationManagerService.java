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
import android.location.IBatchedLocationCallback;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.IGnssStatusProvider;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
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
import com.android.internal.content.PackageMonitor;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.location.ActivityRecognitionProxy;
import com.android.server.location.GeocoderProxy;
import com.android.server.location.GeofenceManager;
import com.android.server.location.GeofenceProxy;
import com.android.server.location.GnssBatchingProvider;
import com.android.server.location.GnssLocationProvider;
import com.android.server.location.GnssMeasurementsProvider;
import com.android.server.location.GnssNavigationMessageProvider;
import com.android.server.location.LocationBlacklist;
import com.android.server.location.LocationFudger;
import com.android.server.location.LocationProviderInterface;
import com.android.server.location.LocationProviderProxy;
import com.android.server.location.LocationRequestStatistics;
import com.android.server.location.MockProvider;
import com.android.server.location.PassiveProvider;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
/* loaded from: classes.dex */
public class LocationManagerService extends ILocationManager.Stub {
    private static final String ACCESS_LOCATION_EXTRA_COMMANDS = "android.permission.ACCESS_LOCATION_EXTRA_COMMANDS";
    private static final String ACCESS_MOCK_LOCATION = "android.permission.ACCESS_MOCK_LOCATION";
    private static final long DEFAULT_BACKGROUND_THROTTLE_INTERVAL_MS = 1800000;
    private static final int FOREGROUND_IMPORTANCE_CUTOFF = 125;
    private static final String FUSED_LOCATION_SERVICE_ACTION = "com.android.location.service.FusedLocationProvider";
    private static final long HIGH_POWER_INTERVAL_MS = 300000;
    private static final String INSTALL_LOCATION_PROVIDER = "android.permission.INSTALL_LOCATION_PROVIDER";
    private static final int MAX_PROVIDER_SCHEDULING_JITTER_MS = 100;
    private static final int MESSAGE_PRINT_INTERVAL = 10;
    private static final int MSG_LOCATION_CHANGED = 1;
    private static final long NANOS_PER_MILLI = 1000000;
    private static final String NETWORK_LOCATION_SERVICE_ACTION = "com.android.location.service.v3.NetworkLocationProvider";
    private static final int RESOLUTION_LEVEL_COARSE = 1;
    private static final int RESOLUTION_LEVEL_FINE = 2;
    private static final int RESOLUTION_LEVEL_NONE = 0;
    private static final String WAKELOCK_KEY = "*location*";
    private ActivityManager mActivityManager;
    private final AppOpsManager mAppOps;
    private LocationBlacklist mBlacklist;
    private final Context mContext;
    private GeocoderProxy mGeocodeProvider;
    private GeofenceManager mGeofenceManager;
    private IBatchedLocationCallback mGnssBatchingCallback;
    private LinkedCallback mGnssBatchingDeathCallback;
    private GnssBatchingProvider mGnssBatchingProvider;
    private GnssMeasurementsProvider mGnssMeasurementsProvider;
    private GnssLocationProvider.GnssMetricsProvider mGnssMetricsProvider;
    private GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private IGnssStatusProvider mGnssStatusProvider;
    private GnssLocationProvider.GnssSystemInfoProvider mGnssSystemInfoProvider;
    private IGpsGeofenceHardware mGpsGeofenceProxy;
    private LocationFudger mLocationFudger;
    private LocationWorkerHandler mLocationHandler;
    private INetInitiatedListener mNetInitiatedListener;
    private PackageManager mPackageManager;
    private PassiveProvider mPassiveProvider;
    private PowerManager mPowerManager;
    private UserManager mUserManager;
    private static final String TAG = "LocationManagerService";
    public static final boolean D = Log.isLoggable(TAG, 3);
    private static final LocationRequest DEFAULT_LOCATION_REQUEST = new LocationRequest();
    private final Object mLock = new Object();
    private final Set<String> mEnabledProviders = new HashSet();
    private final Set<String> mDisabledProviders = new HashSet();
    private final HashMap<String, MockProvider> mMockProviders = new HashMap<>();
    private final HashMap<Object, Receiver> mReceivers = new HashMap<>();
    private final ArrayList<LocationProviderInterface> mProviders = new ArrayList<>();
    private final HashMap<String, LocationProviderInterface> mRealProviders = new HashMap<>();
    private final HashMap<String, LocationProviderInterface> mProvidersByName = new HashMap<>();
    private final HashMap<String, ArrayList<UpdateRecord>> mRecordsByProvider = new HashMap<>();
    private final LocationRequestStatistics mRequestStatistics = new LocationRequestStatistics();
    private final HashMap<String, Location> mLastLocation = new HashMap<>();
    private final HashMap<String, Location> mLastLocationCoarseInterval = new HashMap<>();
    private final ArrayList<LocationProviderProxy> mProxyProviders = new ArrayList<>();
    private final ArraySet<String> mBackgroundThrottlePackageWhitelist = new ArraySet<>();
    private final ArrayMap<IBinder, Identity> mGnssMeasurementsListeners = new ArrayMap<>();
    private final ArrayMap<IBinder, Identity> mGnssNavigationMessageListeners = new ArrayMap<>();
    private int mCurrentUserId = 0;
    private int[] mCurrentUserProfiles = {0};
    private boolean mGnssBatchingInProgress = false;
    private final AtomicInteger mLocationPrintCount = new AtomicInteger(0);
    private final PackageMonitor mPackageMonitor = new PackageMonitor() { // from class: com.android.server.LocationManagerService.9
        public void onPackageDisappeared(String packageName, int reason) {
            synchronized (LocationManagerService.this.mLock) {
                ArrayList<Receiver> deadReceivers = null;
                for (Receiver receiver : LocationManagerService.this.mReceivers.values()) {
                    if (receiver.mIdentity.mPackageName.equals(packageName)) {
                        if (deadReceivers == null) {
                            deadReceivers = new ArrayList<>();
                        }
                        deadReceivers.add(receiver);
                    }
                }
                if (deadReceivers != null) {
                    Iterator<Receiver> it = deadReceivers.iterator();
                    while (it.hasNext()) {
                        LocationManagerService.this.removeUpdatesLocked(it.next());
                    }
                }
            }
        }
    };

    public LocationManagerService(Context context) {
        this.mContext = context;
        this.mAppOps = (AppOpsManager) context.getSystemService("appops");
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.setLocationPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.LocationManagerService.1
            public String[] getPackages(int userId) {
                return LocationManagerService.this.mContext.getResources().getStringArray(17236016);
            }
        });
        if (D) {
            Log.d(TAG, "Constructed");
        }
    }

    public void systemRunning() {
        synchronized (this.mLock) {
            if (D) {
                Log.d(TAG, "systemRunning()");
            }
            this.mPackageManager = this.mContext.getPackageManager();
            this.mPowerManager = (PowerManager) this.mContext.getSystemService("power");
            this.mActivityManager = (ActivityManager) this.mContext.getSystemService("activity");
            this.mLocationHandler = new LocationWorkerHandler(BackgroundThread.get().getLooper());
            this.mLocationFudger = new LocationFudger(this.mContext, this.mLocationHandler);
            this.mBlacklist = new LocationBlacklist(this.mContext, this.mLocationHandler);
            this.mBlacklist.init();
            this.mGeofenceManager = new GeofenceManager(this.mContext, this.mBlacklist);
            AppOpsManager.OnOpChangedListener callback = new AppOpsManager.OnOpChangedInternalListener() { // from class: com.android.server.LocationManagerService.2
                public void onOpChanged(int op, String packageName) {
                    synchronized (LocationManagerService.this.mLock) {
                        for (Receiver receiver : LocationManagerService.this.mReceivers.values()) {
                            receiver.updateMonitoring(true);
                        }
                        LocationManagerService.this.applyAllProviderRequirementsLocked();
                    }
                }
            };
            this.mAppOps.startWatchingMode(0, (String) null, callback);
            PackageManager.OnPermissionsChangedListener permissionListener = new PackageManager.OnPermissionsChangedListener() { // from class: com.android.server.LocationManagerService.3
                public void onPermissionsChanged(int uid) {
                    synchronized (LocationManagerService.this.mLock) {
                        LocationManagerService.this.applyAllProviderRequirementsLocked();
                    }
                }
            };
            this.mPackageManager.addOnPermissionsChangeListener(permissionListener);
            ActivityManager.OnUidImportanceListener uidImportanceListener = new ActivityManager.OnUidImportanceListener() { // from class: com.android.server.LocationManagerService.4
                public void onUidImportance(final int uid, final int importance) {
                    LocationManagerService.this.mLocationHandler.post(new Runnable() { // from class: com.android.server.LocationManagerService.4.1
                        @Override // java.lang.Runnable
                        public void run() {
                            LocationManagerService.this.onUidImportanceChanged(uid, importance);
                        }
                    });
                }
            };
            this.mActivityManager.addOnUidImportanceListener(uidImportanceListener, FOREGROUND_IMPORTANCE_CUTOFF);
            this.mUserManager = (UserManager) this.mContext.getSystemService("user");
            updateUserProfiles(this.mCurrentUserId);
            updateBackgroundThrottlingWhitelistLocked();
            loadProvidersLocked();
            updateProvidersLocked();
        }
        this.mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("location_providers_allowed"), true, new ContentObserver(this.mLocationHandler) { // from class: com.android.server.LocationManagerService.5
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.updateProvidersLocked();
                }
            }
        }, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("location_background_throttle_interval_ms"), true, new ContentObserver(this.mLocationHandler) { // from class: com.android.server.LocationManagerService.6
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.updateProvidersLocked();
                }
            }
        }, -1);
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("location_background_throttle_package_whitelist"), true, new ContentObserver(this.mLocationHandler) { // from class: com.android.server.LocationManagerService.7
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                synchronized (LocationManagerService.this.mLock) {
                    LocationManagerService.this.updateBackgroundThrottlingWhitelistLocked();
                    LocationManagerService.this.updateProvidersLocked();
                }
            }
        }, -1);
        this.mPackageMonitor.register(this.mContext, this.mLocationHandler.getLooper(), true);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.USER_SWITCHED");
        intentFilter.addAction("android.intent.action.MANAGED_PROFILE_ADDED");
        intentFilter.addAction("android.intent.action.MANAGED_PROFILE_REMOVED");
        intentFilter.addAction("android.intent.action.ACTION_SHUTDOWN");
        this.mContext.registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.LocationManagerService.8
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("android.intent.action.USER_SWITCHED".equals(action)) {
                    LocationManagerService.this.switchUser(intent.getIntExtra("android.intent.extra.user_handle", 0));
                } else if ("android.intent.action.MANAGED_PROFILE_ADDED".equals(action) || "android.intent.action.MANAGED_PROFILE_REMOVED".equals(action)) {
                    LocationManagerService.this.updateUserProfiles(LocationManagerService.this.mCurrentUserId);
                } else if ("android.intent.action.ACTION_SHUTDOWN".equals(action)) {
                    if (LocationManagerService.D) {
                        Log.d(LocationManagerService.TAG, "Shutdown received with UserId: " + getSendingUserId());
                    }
                    if (getSendingUserId() == -1) {
                        LocationManagerService.this.shutdownComponents();
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, this.mLocationHandler);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUidImportanceChanged(int uid, int importance) {
        boolean foreground = isImportanceForeground(importance);
        HashSet<String> affectedProviders = new HashSet<>(this.mRecordsByProvider.size());
        synchronized (this.mLock) {
            for (Map.Entry<String, ArrayList<UpdateRecord>> entry : this.mRecordsByProvider.entrySet()) {
                String provider = entry.getKey();
                Iterator<UpdateRecord> it = entry.getValue().iterator();
                while (it.hasNext()) {
                    UpdateRecord record = it.next();
                    if (record.mReceiver.mIdentity.mUid == uid && record.mIsForegroundUid != foreground) {
                        if (D) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("request from uid ");
                            sb.append(uid);
                            sb.append(" is now ");
                            sb.append(foreground ? "foreground" : "background)");
                            Log.d(TAG, sb.toString());
                        }
                        record.updateForeground(foreground);
                        if (!isThrottlingExemptLocked(record.mReceiver.mIdentity)) {
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
            for (Map.Entry<IBinder, Identity> entry2 : this.mGnssMeasurementsListeners.entrySet()) {
                if (entry2.getValue().mUid == uid) {
                    if (D) {
                        StringBuilder sb2 = new StringBuilder();
                        sb2.append("gnss measurements listener from uid ");
                        sb2.append(uid);
                        sb2.append(" is now ");
                        sb2.append(foreground ? "foreground" : "background)");
                        Log.d(TAG, sb2.toString());
                    }
                    if (!foreground && !isThrottlingExemptLocked(entry2.getValue())) {
                        this.mGnssMeasurementsProvider.removeListener(IGnssMeasurementsListener.Stub.asInterface(entry2.getKey()));
                    }
                    this.mGnssMeasurementsProvider.addListener(IGnssMeasurementsListener.Stub.asInterface(entry2.getKey()));
                }
            }
            for (Map.Entry<IBinder, Identity> entry3 : this.mGnssNavigationMessageListeners.entrySet()) {
                if (entry3.getValue().mUid == uid) {
                    if (D) {
                        StringBuilder sb3 = new StringBuilder();
                        sb3.append("gnss navigation message listener from uid ");
                        sb3.append(uid);
                        sb3.append(" is now ");
                        sb3.append(foreground ? "foreground" : "background)");
                        Log.d(TAG, sb3.toString());
                    }
                    if (!foreground && !isThrottlingExemptLocked(entry3.getValue())) {
                        this.mGnssNavigationMessageProvider.removeListener(IGnssNavigationMessageListener.Stub.asInterface(entry3.getKey()));
                    }
                    this.mGnssNavigationMessageProvider.addListener(IGnssNavigationMessageListener.Stub.asInterface(entry3.getKey()));
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isImportanceForeground(int importance) {
        return importance <= FOREGROUND_IMPORTANCE_CUTOFF;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void shutdownComponents() {
        if (D) {
            Log.d(TAG, "Shutting down components...");
        }
        LocationProviderInterface gpsProvider = this.mProvidersByName.get("gps");
        if (gpsProvider != null && gpsProvider.isEnabled()) {
            gpsProvider.disable();
        }
    }

    void updateUserProfiles(int currentUserId) {
        int[] profileIds = this.mUserManager.getProfileIdsWithDisabled(currentUserId);
        synchronized (this.mLock) {
            this.mCurrentUserProfiles = profileIds;
        }
    }

    private boolean isCurrentProfile(int userId) {
        boolean contains;
        synchronized (this.mLock) {
            contains = ArrayUtils.contains(this.mCurrentUserProfiles, userId);
        }
        return contains;
    }

    private void ensureFallbackFusedProviderPresentLocked(ArrayList<String> pkgs) {
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

    private void loadProvidersLocked() {
        PassiveProvider passiveProvider = new PassiveProvider(this);
        addProviderLocked(passiveProvider);
        this.mEnabledProviders.add(passiveProvider.getName());
        this.mPassiveProvider = passiveProvider;
        if (GnssLocationProvider.isSupported()) {
            GnssLocationProvider gnssProvider = new GnssLocationProvider(this.mContext, this, this.mLocationHandler.getLooper());
            this.mGnssSystemInfoProvider = gnssProvider.getGnssSystemInfoProvider();
            this.mGnssBatchingProvider = gnssProvider.getGnssBatchingProvider();
            this.mGnssMetricsProvider = gnssProvider.getGnssMetricsProvider();
            this.mGnssStatusProvider = gnssProvider.getGnssStatusProvider();
            this.mNetInitiatedListener = gnssProvider.getNetInitiatedListener();
            addProviderLocked(gnssProvider);
            this.mRealProviders.put("gps", gnssProvider);
            this.mGnssMeasurementsProvider = gnssProvider.getGnssMeasurementsProvider();
            this.mGnssNavigationMessageProvider = gnssProvider.getGnssNavigationMessageProvider();
            this.mGpsGeofenceProxy = gnssProvider.getGpsGeofenceProxy();
        }
        Resources resources = this.mContext.getResources();
        ArrayList<String> providerPackageNames = new ArrayList<>();
        String[] pkgs = resources.getStringArray(17236016);
        if (D) {
            Log.d(TAG, "certificates for location providers pulled from: " + Arrays.toString(pkgs));
        }
        if (pkgs != null) {
            providerPackageNames.addAll(Arrays.asList(pkgs));
        }
        ensureFallbackFusedProviderPresentLocked(providerPackageNames);
        LocationProviderProxy networkProvider = LocationProviderProxy.createAndBind(this.mContext, "network", NETWORK_LOCATION_SERVICE_ACTION, 17956965, 17039708, 17236016, this.mLocationHandler);
        if (networkProvider != null) {
            this.mRealProviders.put("network", networkProvider);
            this.mProxyProviders.add(networkProvider);
            addProviderLocked(networkProvider);
        } else {
            Slog.w(TAG, "no network location provider found");
        }
        LocationProviderProxy fusedLocationProvider = LocationProviderProxy.createAndBind(this.mContext, "fused", FUSED_LOCATION_SERVICE_ACTION, 17956956, 17039680, 17236016, this.mLocationHandler);
        if (fusedLocationProvider != null) {
            addProviderLocked(fusedLocationProvider);
            this.mProxyProviders.add(fusedLocationProvider);
            this.mEnabledProviders.add(fusedLocationProvider.getName());
            this.mRealProviders.put("fused", fusedLocationProvider);
        } else {
            Slog.e(TAG, "no fused location provider found", new IllegalStateException("Location service needs a fused location provider"));
        }
        this.mGeocodeProvider = GeocoderProxy.createAndBind(this.mContext, 17956957, 17039681, 17236016, this.mLocationHandler);
        if (this.mGeocodeProvider == null) {
            Slog.e(TAG, "no geocoder provider found");
        }
        GeofenceProxy provider = GeofenceProxy.createAndBind(this.mContext, 17956958, 17039682, 17236016, this.mLocationHandler, this.mGpsGeofenceProxy, null);
        if (provider == null) {
            Slog.d(TAG, "Unable to bind FLP Geofence proxy.");
        }
        boolean activityRecognitionHardwareIsSupported = ActivityRecognitionHardware.isSupported();
        ActivityRecognitionHardware activityRecognitionHardware = null;
        if (activityRecognitionHardwareIsSupported) {
            activityRecognitionHardware = ActivityRecognitionHardware.getInstance(this.mContext);
        } else {
            Slog.d(TAG, "Hardware Activity-Recognition not supported.");
        }
        ActivityRecognitionProxy proxy = ActivityRecognitionProxy.createAndBind(this.mContext, this.mLocationHandler, activityRecognitionHardwareIsSupported, activityRecognitionHardware, 17956950, 17039633, 17236016);
        if (proxy == null) {
            Slog.d(TAG, "Unable to bind ActivityRecognitionProxy.");
        }
        String[] testProviderStrings = resources.getStringArray(17236047);
        int length = testProviderStrings.length;
        int i = 0;
        while (i < length) {
            String testProviderString = testProviderStrings[i];
            String[] fragments = testProviderString.split(",");
            PassiveProvider passiveProvider2 = passiveProvider;
            String name = fragments[0].trim();
            Resources resources2 = resources;
            if (this.mProvidersByName.get(name) != null) {
                throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
            }
            ProviderProperties properties = new ProviderProperties(Boolean.parseBoolean(fragments[1]), Boolean.parseBoolean(fragments[2]), Boolean.parseBoolean(fragments[3]), Boolean.parseBoolean(fragments[4]), Boolean.parseBoolean(fragments[5]), Boolean.parseBoolean(fragments[6]), Boolean.parseBoolean(fragments[7]), Integer.parseInt(fragments[8]), Integer.parseInt(fragments[9]));
            addTestProviderLocked(name, properties);
            i++;
            passiveProvider = passiveProvider2;
            resources = resources2;
            providerPackageNames = providerPackageNames;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void switchUser(int userId) {
        if (this.mCurrentUserId == userId) {
            return;
        }
        this.mBlacklist.switchUser(userId);
        this.mLocationHandler.removeMessages(1);
        synchronized (this.mLock) {
            this.mLastLocation.clear();
            this.mLastLocationCoarseInterval.clear();
            Iterator<LocationProviderInterface> it = this.mProviders.iterator();
            while (it.hasNext()) {
                LocationProviderInterface p = it.next();
                updateProviderListenersLocked(p.getName(), false);
            }
            this.mCurrentUserId = userId;
            updateUserProfiles(userId);
            updateProvidersLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class Identity {
        final String mPackageName;
        final int mPid;
        final int mUid;

        Identity(int uid, int pid, String packageName) {
            this.mUid = uid;
            this.mPid = pid;
            this.mPackageName = packageName;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Receiver implements IBinder.DeathRecipient, PendingIntent.OnFinished {
        final int mAllowedResolutionLevel;
        final boolean mHideFromAppOps;
        final Identity mIdentity;
        final Object mKey;
        final ILocationListener mListener;
        boolean mOpHighPowerMonitoring;
        boolean mOpMonitoring;
        int mPendingBroadcasts;
        final PendingIntent mPendingIntent;
        final HashMap<String, UpdateRecord> mUpdateRecords = new HashMap<>();
        PowerManager.WakeLock mWakeLock;
        final WorkSource mWorkSource;

        Receiver(ILocationListener listener, PendingIntent intent, int pid, int uid, String packageName, WorkSource workSource, boolean hideFromAppOps) {
            this.mListener = listener;
            this.mPendingIntent = intent;
            if (listener != null) {
                this.mKey = listener.asBinder();
            } else {
                this.mKey = intent;
            }
            this.mAllowedResolutionLevel = LocationManagerService.this.getAllowedResolutionLevel(pid, uid);
            this.mIdentity = new Identity(uid, pid, packageName);
            if (workSource != null && workSource.isEmpty()) {
                workSource = null;
            }
            this.mWorkSource = workSource;
            this.mHideFromAppOps = hideFromAppOps;
            updateMonitoring(true);
            this.mWakeLock = LocationManagerService.this.mPowerManager.newWakeLock(1, LocationManagerService.WAKELOCK_KEY);
            this.mWakeLock.setWorkSource(workSource == null ? new WorkSource(this.mIdentity.mUid, this.mIdentity.mPackageName) : workSource);
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
                    if (LocationManagerService.this.isAllowedByCurrentUserSettingsLocked(updateRecord.mProvider)) {
                        requestingLocation = true;
                        LocationProviderInterface locationProvider = (LocationProviderInterface) LocationManagerService.this.mProvidersByName.get(updateRecord.mProvider);
                        ProviderProperties properties = locationProvider != null ? locationProvider.getProperties() : null;
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
                    return LocationManagerService.this.mAppOps.startOpNoThrow(op, this.mIdentity.mUid, this.mIdentity.mPackageName) == 0;
                }
            } else if (!allowMonitoring || LocationManagerService.this.mAppOps.checkOpNoThrow(op, this.mIdentity.mUid, this.mIdentity.mPackageName) != 0) {
                LocationManagerService.this.mAppOps.finishOp(op, this.mIdentity.mUid, this.mIdentity.mPackageName);
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
            if (this.mListener != null) {
                return this.mListener;
            }
            throw new IllegalStateException("Request for non-existent listener");
        }

        public boolean callStatusChangedLocked(String provider, int status, Bundle extras) {
            if (this.mListener != null) {
                try {
                    synchronized (this) {
                        this.mListener.onStatusChanged(provider, status, extras);
                        incrementPendingBroadcastsLocked();
                    }
                    return true;
                } catch (RemoteException e) {
                    return false;
                }
            }
            Intent statusChanged = new Intent();
            statusChanged.putExtras(new Bundle(extras));
            statusChanged.putExtra("status", status);
            try {
                synchronized (this) {
                    this.mPendingIntent.send(LocationManagerService.this.mContext, 0, statusChanged, this, LocationManagerService.this.mLocationHandler, LocationManagerService.this.getResolutionPermission(this.mAllowedResolutionLevel), PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                    incrementPendingBroadcastsLocked();
                }
                return true;
            } catch (PendingIntent.CanceledException e2) {
                return false;
            }
        }

        public boolean callLocationChangedLocked(Location location) {
            if (this.mListener != null) {
                try {
                    synchronized (this) {
                        this.mListener.onLocationChanged(new Location(location));
                        incrementPendingBroadcastsLocked();
                    }
                    return true;
                } catch (RemoteException e) {
                    return false;
                }
            }
            Intent locationChanged = new Intent();
            locationChanged.putExtra("location", new Location(location));
            try {
                synchronized (this) {
                    this.mPendingIntent.send(LocationManagerService.this.mContext, 0, locationChanged, this, LocationManagerService.this.mLocationHandler, LocationManagerService.this.getResolutionPermission(this.mAllowedResolutionLevel), PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                    incrementPendingBroadcastsLocked();
                }
                return true;
            } catch (PendingIntent.CanceledException e2) {
                return false;
            }
        }

        public boolean callProviderEnabledLocked(String provider, boolean enabled) {
            updateMonitoring(true);
            if (this.mListener != null) {
                try {
                    synchronized (this) {
                        if (enabled) {
                            this.mListener.onProviderEnabled(provider);
                        } else {
                            this.mListener.onProviderDisabled(provider);
                        }
                        incrementPendingBroadcastsLocked();
                    }
                } catch (RemoteException e) {
                    return false;
                }
            } else {
                Intent providerIntent = new Intent();
                providerIntent.putExtra("providerEnabled", enabled);
                try {
                    synchronized (this) {
                        this.mPendingIntent.send(LocationManagerService.this.mContext, 0, providerIntent, this, LocationManagerService.this.mLocationHandler, LocationManagerService.this.getResolutionPermission(this.mAllowedResolutionLevel), PendingIntentUtils.createDontSendToRestrictedAppsBundle(null));
                        incrementPendingBroadcastsLocked();
                    }
                } catch (PendingIntent.CanceledException e2) {
                    return false;
                }
            }
            return true;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, "Location listener died");
            }
            synchronized (LocationManagerService.this.mLock) {
                LocationManagerService.this.removeUpdatesLocked(this);
            }
            synchronized (this) {
                clearPendingBroadcastsLocked();
            }
        }

        @Override // android.app.PendingIntent.OnFinished
        public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras) {
            synchronized (this) {
                decrementPendingBroadcastsLocked();
            }
        }

        private void incrementPendingBroadcastsLocked() {
            int i = this.mPendingBroadcasts;
            this.mPendingBroadcasts = i + 1;
            if (i == 0) {
                this.mWakeLock.acquire();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void decrementPendingBroadcastsLocked() {
            int i = this.mPendingBroadcasts - 1;
            this.mPendingBroadcasts = i;
            if (i == 0 && this.mWakeLock.isHeld()) {
                this.mWakeLock.release();
            }
        }

        public void clearPendingBroadcastsLocked() {
            if (this.mPendingBroadcasts > 0) {
                this.mPendingBroadcasts = 0;
                if (this.mWakeLock.isHeld()) {
                    this.mWakeLock.release();
                }
            }
        }
    }

    public void locationCallbackFinished(ILocationListener listener) {
        synchronized (this.mLock) {
            IBinder binder = listener.asBinder();
            Receiver receiver = this.mReceivers.get(binder);
            if (receiver != null) {
                synchronized (receiver) {
                    long identity = Binder.clearCallingIdentity();
                    receiver.decrementPendingBroadcastsLocked();
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    public int getGnssYearOfHardware() {
        if (this.mGnssSystemInfoProvider != null) {
            return this.mGnssSystemInfoProvider.getGnssYearOfHardware();
        }
        return 0;
    }

    public String getGnssHardwareModelName() {
        if (this.mGnssSystemInfoProvider != null) {
            return this.mGnssSystemInfoProvider.getGnssHardwareModelName();
        }
        return null;
    }

    private boolean hasGnssPermissions(String packageName) {
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkResolutionLevelIsSufficientForProviderUse(allowedResolutionLevel, "gps");
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            boolean hasLocationAccess = checkLocationAccess(pid, uid, packageName, allowedResolutionLevel);
            return hasLocationAccess;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public int getGnssBatchSize(String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (hasGnssPermissions(packageName) && this.mGnssBatchingProvider != null) {
            return this.mGnssBatchingProvider.getBatchSize();
        }
        return 0;
    }

    public boolean addGnssBatchingCallback(IBatchedLocationCallback callback, String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (!hasGnssPermissions(packageName) || this.mGnssBatchingProvider == null) {
            return false;
        }
        this.mGnssBatchingCallback = callback;
        this.mGnssBatchingDeathCallback = new LinkedCallback(callback);
        try {
            callback.asBinder().linkToDeath(this.mGnssBatchingDeathCallback, 0);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Remote listener already died.", e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LinkedCallback implements IBinder.DeathRecipient {
        private final IBatchedLocationCallback mCallback;

        public LinkedCallback(IBatchedLocationCallback callback) {
            this.mCallback = callback;
        }

        public IBatchedLocationCallback getUnderlyingListener() {
            return this.mCallback;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.d(LocationManagerService.TAG, "Remote Batching Callback died: " + this.mCallback);
            LocationManagerService.this.stopGnssBatch();
            LocationManagerService.this.removeGnssBatchingCallback();
        }
    }

    public void removeGnssBatchingCallback() {
        try {
            this.mGnssBatchingCallback.asBinder().unlinkToDeath(this.mGnssBatchingDeathCallback, 0);
        } catch (NoSuchElementException e) {
            Log.e(TAG, "Couldn't unlink death callback.", e);
        }
        this.mGnssBatchingCallback = null;
        this.mGnssBatchingDeathCallback = null;
    }

    public boolean startGnssBatch(long periodNanos, boolean wakeOnFifoFull, String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (!hasGnssPermissions(packageName) || this.mGnssBatchingProvider == null) {
            return false;
        }
        if (this.mGnssBatchingInProgress) {
            Log.e(TAG, "startGnssBatch unexpectedly called w/o stopping prior batch");
            stopGnssBatch();
        }
        this.mGnssBatchingInProgress = true;
        return this.mGnssBatchingProvider.start(periodNanos, wakeOnFifoFull);
    }

    public void flushGnssBatch(String packageName) {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (!hasGnssPermissions(packageName)) {
            Log.e(TAG, "flushGnssBatch called without GNSS permissions");
            return;
        }
        if (!this.mGnssBatchingInProgress) {
            Log.w(TAG, "flushGnssBatch called with no batch in progress");
        }
        if (this.mGnssBatchingProvider != null) {
            this.mGnssBatchingProvider.flush();
        }
    }

    public boolean stopGnssBatch() {
        this.mContext.enforceCallingPermission("android.permission.LOCATION_HARDWARE", "Location Hardware permission not granted to access hardware batching");
        if (this.mGnssBatchingProvider != null) {
            this.mGnssBatchingInProgress = false;
            return this.mGnssBatchingProvider.stop();
        }
        return false;
    }

    public void reportLocationBatch(List<Location> locations) {
        checkCallerIsProvider();
        if (isAllowedByCurrentUserSettingsLocked("gps")) {
            if (this.mGnssBatchingCallback == null) {
                Slog.e(TAG, "reportLocationBatch() called without active Callback");
                return;
            }
            try {
                this.mGnssBatchingCallback.onLocationBatch(locations);
                return;
            } catch (RemoteException e) {
                Slog.e(TAG, "mGnssBatchingCallback.onLocationBatch failed", e);
                return;
            }
        }
        Slog.w(TAG, "reportLocationBatch() called without user permission, locations blocked");
    }

    private void addProviderLocked(LocationProviderInterface provider) {
        this.mProviders.add(provider);
        this.mProvidersByName.put(provider.getName(), provider);
    }

    private void removeProviderLocked(LocationProviderInterface provider) {
        provider.disable();
        this.mProviders.remove(provider);
        this.mProvidersByName.remove(provider.getName());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isAllowedByCurrentUserSettingsLocked(String provider) {
        return isAllowedByUserSettingsLockedForUser(provider, this.mCurrentUserId);
    }

    private boolean isAllowedByUserSettingsLockedForUser(String provider, int userId) {
        if (this.mEnabledProviders.contains(provider)) {
            return true;
        }
        if (this.mDisabledProviders.contains(provider)) {
            return false;
        }
        return isLocationProviderEnabledForUser(provider, userId);
    }

    private boolean isAllowedByUserSettingsLocked(String provider, int uid, int userId) {
        if (!isCurrentProfile(UserHandle.getUserId(uid)) && !isUidALocationProvider(uid)) {
            return false;
        }
        return isAllowedByUserSettingsLockedForUser(provider, userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getResolutionPermission(int resolutionLevel) {
        switch (resolutionLevel) {
            case 1:
                return "android.permission.ACCESS_COARSE_LOCATION";
            case 2:
                return "android.permission.ACCESS_FINE_LOCATION";
            default:
                return null;
        }
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

    private int getMinimumResolutionLevelForProviderUse(String provider) {
        ProviderProperties properties;
        if ("gps".equals(provider) || "passive".equals(provider)) {
            return 2;
        }
        if ("network".equals(provider) || "fused".equals(provider)) {
            return 1;
        }
        LocationProviderInterface lp = this.mMockProviders.get(provider);
        if (lp == null || (properties = lp.getProperties()) == null || properties.mRequiresSatellite) {
            return 2;
        }
        return (properties.mRequiresNetwork || properties.mRequiresCell) ? 1 : 2;
    }

    private void checkResolutionLevelIsSufficientForProviderUse(int allowedResolutionLevel, String providerName) {
        int requiredResolutionLevel = getMinimumResolutionLevelForProviderUse(providerName);
        if (allowedResolutionLevel < requiredResolutionLevel) {
            switch (requiredResolutionLevel) {
                case 1:
                    throw new SecurityException("\"" + providerName + "\" location provider requires ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION permission.");
                case 2:
                    throw new SecurityException("\"" + providerName + "\" location provider requires ACCESS_FINE_LOCATION permission.");
                default:
                    throw new SecurityException("Insufficient permission for \"" + providerName + "\" location provider.");
            }
        }
    }

    private void checkDeviceStatsAllowed() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_DEVICE_STATS", null);
    }

    private void checkUpdateAppOpsAllowed() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_APP_OPS_STATS", null);
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

    boolean reportLocationAccessNoThrow(int pid, int uid, String packageName, int allowedResolutionLevel) {
        int op = resolutionLevelToOp(allowedResolutionLevel);
        return (op < 0 || this.mAppOps.noteOpNoThrow(op, uid, packageName) == 0) && getAllowedResolutionLevel(pid, uid) >= allowedResolutionLevel;
    }

    boolean checkLocationAccess(int pid, int uid, String packageName, int allowedResolutionLevel) {
        int op = resolutionLevelToOp(allowedResolutionLevel);
        return (op < 0 || this.mAppOps.checkOp(op, uid, packageName) == 0) && getAllowedResolutionLevel(pid, uid) >= allowedResolutionLevel;
    }

    public List<String> getAllProviders() {
        ArrayList<String> out;
        synchronized (this.mLock) {
            out = new ArrayList<>(this.mProviders.size());
            Iterator<LocationProviderInterface> it = this.mProviders.iterator();
            while (it.hasNext()) {
                LocationProviderInterface provider = it.next();
                String name = provider.getName();
                if (!"fused".equals(name)) {
                    out.add(name);
                }
            }
        }
        if (D) {
            Log.d(TAG, "getAllProviders()=" + out);
        }
        return out;
    }

    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        ArrayList<String> out;
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        int uid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                out = new ArrayList<>(this.mProviders.size());
                Iterator<LocationProviderInterface> it = this.mProviders.iterator();
                while (it.hasNext()) {
                    LocationProviderInterface provider = it.next();
                    String name = provider.getName();
                    if (!"fused".equals(name)) {
                        if (allowedResolutionLevel >= getMinimumResolutionLevelForProviderUse(name)) {
                            if (!enabledOnly || isAllowedByUserSettingsLocked(name, uid, this.mCurrentUserId)) {
                                if (criteria == null || LocationProvider.propertiesMeetCriteria(name, provider.getProperties(), criteria)) {
                                    out.add(name);
                                }
                            }
                        }
                    }
                }
            }
            Binder.restoreCallingIdentity(identity);
            if (D) {
                Log.d(TAG, "getProviders()=" + out);
            }
            return out;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
    }

    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> providers = getProviders(criteria, enabledOnly);
        if (!providers.isEmpty()) {
            String result = pickBest(providers);
            if (D) {
                Log.d(TAG, "getBestProvider(" + criteria + ", " + enabledOnly + ")=" + result);
            }
            return result;
        }
        List<String> providers2 = getProviders(null, enabledOnly);
        if (!providers2.isEmpty()) {
            String result2 = pickBest(providers2);
            if (D) {
                Log.d(TAG, "getBestProvider(" + criteria + ", " + enabledOnly + ")=" + result2);
            }
            return result2;
        }
        if (D) {
            Log.d(TAG, "getBestProvider(" + criteria + ", " + enabledOnly + ")=" + ((String) null));
        }
        return null;
    }

    private String pickBest(List<String> providers) {
        if (providers.contains("gps")) {
            return "gps";
        }
        if (providers.contains("network")) {
            return "network";
        }
        return providers.get(0);
    }

    public boolean providerMeetsCriteria(String provider, Criteria criteria) {
        LocationProviderInterface p = this.mProvidersByName.get(provider);
        if (p == null) {
            throw new IllegalArgumentException("provider=" + provider);
        }
        boolean result = LocationProvider.propertiesMeetCriteria(p.getName(), p.getProperties(), criteria);
        if (D) {
            Log.d(TAG, "providerMeetsCriteria(" + provider + ", " + criteria + ")=" + result);
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateProvidersLocked() {
        boolean changesMade = false;
        for (int i = this.mProviders.size() - 1; i >= 0; i--) {
            LocationProviderInterface p = this.mProviders.get(i);
            boolean isEnabled = p.isEnabled();
            String name = p.getName();
            boolean shouldBeEnabled = isAllowedByCurrentUserSettingsLocked(name);
            if (isEnabled && !shouldBeEnabled) {
                updateProviderListenersLocked(name, false);
                this.mLastLocation.clear();
                this.mLastLocationCoarseInterval.clear();
                changesMade = true;
            } else if (!isEnabled && shouldBeEnabled) {
                updateProviderListenersLocked(name, true);
                changesMade = true;
            }
        }
        if (changesMade) {
            this.mContext.sendBroadcastAsUser(new Intent("android.location.PROVIDERS_CHANGED"), UserHandle.ALL);
            this.mContext.sendBroadcastAsUser(new Intent("android.location.MODE_CHANGED"), UserHandle.ALL);
        }
    }

    private void updateProviderListenersLocked(String provider, boolean enabled) {
        int listeners = 0;
        LocationProviderInterface p = this.mProvidersByName.get(provider);
        if (p == null) {
            return;
        }
        ArrayList<Receiver> deadReceivers = null;
        ArrayList<UpdateRecord> records = this.mRecordsByProvider.get(provider);
        if (records != null) {
            Iterator<UpdateRecord> it = records.iterator();
            while (it.hasNext()) {
                UpdateRecord record = it.next();
                if (isCurrentProfile(UserHandle.getUserId(record.mReceiver.mIdentity.mUid))) {
                    if (!record.mReceiver.callProviderEnabledLocked(provider, enabled)) {
                        if (deadReceivers == null) {
                            deadReceivers = new ArrayList<>();
                        }
                        deadReceivers.add(record.mReceiver);
                    }
                    listeners++;
                }
            }
        }
        if (deadReceivers != null) {
            for (int i = deadReceivers.size() - 1; i >= 0; i--) {
                removeUpdatesLocked(deadReceivers.get(i));
            }
        }
        if (enabled) {
            p.enable();
            if (listeners > 0) {
                applyRequirementsLocked(provider);
                return;
            }
            return;
        }
        p.disable();
    }

    private void applyRequirementsLocked(String provider) {
        Iterator<UpdateRecord> it;
        LocationProviderInterface p = this.mProvidersByName.get(provider);
        if (p == null) {
            return;
        }
        ArrayList<UpdateRecord> records = this.mRecordsByProvider.get(provider);
        WorkSource worksource = new WorkSource();
        ProviderRequest providerRequest = new ProviderRequest();
        ContentResolver resolver = this.mContext.getContentResolver();
        long backgroundThrottleInterval = Settings.Global.getLong(resolver, "location_background_throttle_interval_ms", 1800000L);
        providerRequest.lowPowerMode = true;
        if (records != null) {
            Iterator<UpdateRecord> it2 = records.iterator();
            while (it2.hasNext()) {
                UpdateRecord record = it2.next();
                if (isCurrentProfile(UserHandle.getUserId(record.mReceiver.mIdentity.mUid)) && checkLocationAccess(record.mReceiver.mIdentity.mPid, record.mReceiver.mIdentity.mUid, record.mReceiver.mIdentity.mPackageName, record.mReceiver.mAllowedResolutionLevel)) {
                    LocationRequest locationRequest = record.mRealRequest;
                    long interval = locationRequest.getInterval();
                    if (!isThrottlingExemptLocked(record.mReceiver.mIdentity)) {
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
                    it = it2;
                    if (interval < providerRequest.interval) {
                        providerRequest.reportLocation = true;
                        providerRequest.interval = interval;
                    }
                } else {
                    it = it2;
                }
                it2 = it;
            }
            if (providerRequest.reportLocation) {
                long thresholdInterval = ((providerRequest.interval + 1000) * 3) / 2;
                Iterator<UpdateRecord> it3 = records.iterator();
                while (it3.hasNext()) {
                    UpdateRecord record2 = it3.next();
                    if (isCurrentProfile(UserHandle.getUserId(record2.mReceiver.mIdentity.mUid))) {
                        LocationRequest locationRequest2 = record2.mRequest;
                        if (providerRequest.locationRequests.contains(locationRequest2) && locationRequest2.getInterval() <= thresholdInterval) {
                            if (record2.mReceiver.mWorkSource != null && isValidWorkSource(record2.mReceiver.mWorkSource)) {
                                worksource.add(record2.mReceiver.mWorkSource);
                            } else {
                                worksource.add(record2.mReceiver.mIdentity.mUid, record2.mReceiver.mIdentity.mPackageName);
                            }
                        }
                    }
                }
            }
        }
        if (D) {
            Log.d(TAG, "provider request: " + provider + " " + providerRequest);
        }
        p.setRequest(providerRequest, worksource);
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
            strArr = (String[]) this.mBackgroundThrottlePackageWhitelist.toArray(new String[this.mBackgroundThrottlePackageWhitelist.size()]);
        }
        return strArr;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBackgroundThrottlingWhitelistLocked() {
        String setting = Settings.Global.getString(this.mContext.getContentResolver(), "location_background_throttle_package_whitelist");
        if (setting == null) {
            setting = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        this.mBackgroundThrottlePackageWhitelist.clear();
        this.mBackgroundThrottlePackageWhitelist.addAll(SystemConfig.getInstance().getAllowUnthrottledLocation());
        this.mBackgroundThrottlePackageWhitelist.addAll(Arrays.asList(setting.split(",")));
    }

    private boolean isThrottlingExemptLocked(Identity identity) {
        if (identity.mUid == 1000 || this.mBackgroundThrottlePackageWhitelist.contains(identity.mPackageName)) {
            return true;
        }
        Iterator<LocationProviderProxy> it = this.mProxyProviders.iterator();
        while (it.hasNext()) {
            LocationProviderProxy provider = it.next();
            if (identity.mPackageName.equals(provider.getConnectedPackageName())) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class UpdateRecord {
        boolean mIsForegroundUid;
        Location mLastFixBroadcast;
        long mLastStatusBroadcast;
        final String mProvider;
        final LocationRequest mRealRequest;
        final Receiver mReceiver;
        LocationRequest mRequest;

        UpdateRecord(String provider, LocationRequest request, Receiver receiver) {
            this.mProvider = provider;
            this.mRealRequest = request;
            this.mRequest = request;
            this.mReceiver = receiver;
            this.mIsForegroundUid = LocationManagerService.isImportanceForeground(LocationManagerService.this.mActivityManager.getPackageImportance(this.mReceiver.mIdentity.mPackageName));
            ArrayList<UpdateRecord> records = (ArrayList) LocationManagerService.this.mRecordsByProvider.get(provider);
            if (records == null) {
                records = new ArrayList<>();
                LocationManagerService.this.mRecordsByProvider.put(provider, records);
            }
            if (!records.contains(this)) {
                records.add(this);
            }
            LocationManagerService.this.mRequestStatistics.startRequesting(this.mReceiver.mIdentity.mPackageName, provider, request.getInterval(), this.mIsForegroundUid);
        }

        void updateForeground(boolean isForeground) {
            this.mIsForegroundUid = isForeground;
            LocationManagerService.this.mRequestStatistics.updateForeground(this.mReceiver.mIdentity.mPackageName, this.mProvider, isForeground);
        }

        void disposeLocked(boolean removeReceiver) {
            HashMap<String, UpdateRecord> receiverRecords;
            LocationManagerService.this.mRequestStatistics.stopRequesting(this.mReceiver.mIdentity.mPackageName, this.mProvider);
            ArrayList<UpdateRecord> globalRecords = (ArrayList) LocationManagerService.this.mRecordsByProvider.get(this.mProvider);
            if (globalRecords != null) {
                globalRecords.remove(this);
            }
            if (removeReceiver && (receiverRecords = this.mReceiver.mUpdateRecords) != null) {
                receiverRecords.remove(this.mProvider);
                if (receiverRecords.size() == 0) {
                    LocationManagerService.this.removeUpdatesLocked(this.mReceiver);
                }
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UpdateRecord[");
            sb.append(this.mProvider);
            sb.append(" ");
            sb.append(this.mReceiver.mIdentity.mPackageName);
            sb.append("(");
            sb.append(this.mReceiver.mIdentity.mUid);
            sb.append(this.mIsForegroundUid ? " foreground" : " background");
            sb.append(") ");
            sb.append(this.mRealRequest);
            sb.append("]");
            return sb.toString();
        }
    }

    private Receiver getReceiverLocked(ILocationListener listener, int pid, int uid, String packageName, WorkSource workSource, boolean hideFromAppOps) {
        IBinder binder = listener.asBinder();
        Receiver receiver = this.mReceivers.get(binder);
        if (receiver == null) {
            Receiver receiver2 = new Receiver(listener, null, pid, uid, packageName, workSource, hideFromAppOps);
            try {
                receiver2.getListener().asBinder().linkToDeath(receiver2, 0);
                this.mReceivers.put(binder, receiver2);
                return receiver2;
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath failed:", e);
                return null;
            }
        }
        return receiver;
    }

    private Receiver getReceiverLocked(PendingIntent intent, int pid, int uid, String packageName, WorkSource workSource, boolean hideFromAppOps) {
        Receiver receiver = this.mReceivers.get(intent);
        if (receiver == null) {
            Receiver receiver2 = new Receiver(null, intent, pid, uid, packageName, workSource, hideFromAppOps);
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
            request.setFastestInterval(request.getInterval());
        }
        return sanitizedRequest;
    }

    private void checkPackageName(String packageName) {
        if (packageName == null) {
            throw new SecurityException("invalid package name: " + packageName);
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

    private void checkPendingIntent(PendingIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("invalid pending intent: " + intent);
        }
    }

    private Receiver checkListenerOrIntentLocked(ILocationListener listener, PendingIntent intent, int pid, int uid, String packageName, WorkSource workSource, boolean hideFromAppOps) {
        if (intent == null && listener == null) {
            throw new IllegalArgumentException("need either listener or intent");
        }
        if (intent != null && listener != null) {
            throw new IllegalArgumentException("cannot register both listener and intent");
        }
        if (intent != null) {
            checkPendingIntent(intent);
            return getReceiverLocked(intent, pid, uid, packageName, workSource, hideFromAppOps);
        }
        return getReceiverLocked(listener, pid, uid, packageName, workSource, hideFromAppOps);
    }

    public void requestLocationUpdates(LocationRequest request, ILocationListener listener, PendingIntent intent, String packageName) {
        long identity;
        LocationRequest request2 = request == null ? DEFAULT_LOCATION_REQUEST : request;
        checkPackageName(packageName);
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkResolutionLevelIsSufficientForProviderUse(allowedResolutionLevel, request2.getProvider());
        WorkSource workSource = request2.getWorkSource();
        if (workSource != null && !workSource.isEmpty()) {
            checkDeviceStatsAllowed();
        }
        boolean hideFromAppOps = request2.getHideFromAppOps();
        if (hideFromAppOps) {
            checkUpdateAppOpsAllowed();
        }
        boolean callerHasLocationHardwarePermission = this.mContext.checkCallingPermission("android.permission.LOCATION_HARDWARE") == 0;
        LocationRequest sanitizedRequest = createSanitizedRequest(request2, allowedResolutionLevel, callerHasLocationHardwarePermission);
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        long identity2 = Binder.clearCallingIdentity();
        try {
            checkLocationAccess(pid, uid, packageName, allowedResolutionLevel);
            synchronized (this.mLock) {
                identity = identity2;
                try {
                    Receiver recevier = checkListenerOrIntentLocked(listener, intent, pid, uid, packageName, workSource, hideFromAppOps);
                    requestLocationUpdatesLocked(sanitizedRequest, recevier, pid, uid, packageName);
                } catch (Throwable th) {
                    try {
                        throw th;
                    } catch (Throwable th2) {
                        th = th2;
                        Binder.restoreCallingIdentity(identity);
                        throw th;
                    }
                }
            }
            Binder.restoreCallingIdentity(identity);
        } catch (Throwable th3) {
            th = th3;
            identity = identity2;
        }
    }

    private void requestLocationUpdatesLocked(LocationRequest request, Receiver receiver, int pid, int uid, String packageName) {
        if (request == null) {
            request = DEFAULT_LOCATION_REQUEST;
        }
        String name = request.getProvider();
        if (name == null) {
            throw new IllegalArgumentException("provider name must not be null");
        }
        LocationProviderInterface provider = this.mProvidersByName.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + name);
        }
        UpdateRecord record = new UpdateRecord(name, request, receiver);
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
            sb.append(isThrottlingExemptLocked(receiver.mIdentity) ? " [whitelisted]" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb.append(")");
            Log.d(TAG, sb.toString());
        }
        UpdateRecord oldRecord = receiver.mUpdateRecords.put(name, record);
        if (oldRecord != null) {
            oldRecord.disposeLocked(false);
        }
        boolean isProviderEnabled = isAllowedByUserSettingsLocked(name, uid, this.mCurrentUserId);
        if (isProviderEnabled) {
            applyRequirementsLocked(name);
        } else {
            receiver.callProviderEnabledLocked(name, false);
        }
        receiver.updateMonitoring(true);
    }

    public void removeUpdates(ILocationListener listener, PendingIntent intent, String packageName) {
        checkPackageName(packageName);
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        synchronized (this.mLock) {
            Receiver receiver = checkListenerOrIntentLocked(listener, intent, pid, uid, packageName, null, false);
            long identity = Binder.clearCallingIdentity();
            removeUpdatesLocked(receiver);
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeUpdatesLocked(Receiver receiver) {
        if (D) {
            Log.i(TAG, "remove " + Integer.toHexString(System.identityHashCode(receiver)));
        }
        if (this.mReceivers.remove(receiver.mKey) != null && receiver.isListener()) {
            receiver.getListener().asBinder().unlinkToDeath(receiver, 0);
            synchronized (receiver) {
                receiver.clearPendingBroadcastsLocked();
            }
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
            if (isAllowedByCurrentUserSettingsLocked(provider)) {
                applyRequirementsLocked(provider);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applyAllProviderRequirementsLocked() {
        Iterator<LocationProviderInterface> it = this.mProviders.iterator();
        while (it.hasNext()) {
            LocationProviderInterface p = it.next();
            if (isAllowedByCurrentUserSettingsLocked(p.getName())) {
                applyRequirementsLocked(p.getName());
            }
        }
    }

    public Location getLastLocation(LocationRequest request, String packageName) {
        if (D) {
            Log.d(TAG, "getLastLocation: " + request);
        }
        if (request == null) {
            request = DEFAULT_LOCATION_REQUEST;
        }
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkPackageName(packageName);
        checkResolutionLevelIsSufficientForProviderUse(allowedResolutionLevel, request.getProvider());
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        long identity = Binder.clearCallingIdentity();
        try {
            if (this.mBlacklist.isBlacklisted(packageName)) {
                if (D) {
                    Log.d(TAG, "not returning last loc for blacklisted app: " + packageName);
                }
                return null;
            } else if (!reportLocationAccessNoThrow(pid, uid, packageName, allowedResolutionLevel)) {
                if (D) {
                    Log.d(TAG, "not returning last loc for no op app: " + packageName);
                }
                return null;
            } else {
                synchronized (this.mLock) {
                    String name = request.getProvider();
                    if (name == null) {
                        name = "fused";
                    }
                    LocationProviderInterface provider = this.mProvidersByName.get(name);
                    if (provider == null) {
                        return null;
                    }
                    if (isAllowedByUserSettingsLocked(name, uid, this.mCurrentUserId)) {
                        Location location = allowedResolutionLevel < 2 ? this.mLastLocationCoarseInterval.get(name) : this.mLastLocation.get(name);
                        if (location == null) {
                            return null;
                        }
                        if (allowedResolutionLevel >= 2) {
                            return new Location(location);
                        }
                        Location noGPSLocation = location.getExtraLocation("noGPSLocation");
                        if (noGPSLocation != null) {
                            return new Location(this.mLocationFudger.getOrCreate(noGPSLocation));
                        }
                        return null;
                    }
                    return null;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
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
        LocationProviderInterface p = null;
        String provider = location.getProvider();
        if (provider != null) {
            LocationProviderInterface p2 = this.mProvidersByName.get(provider);
            p = p2;
        }
        if (p == null) {
            if (D) {
                Log.d(TAG, "injectLocation(): unknown provider");
            }
            return false;
        }
        synchronized (this.mLock) {
            if (!isAllowedByCurrentUserSettingsLocked(provider)) {
                if (D) {
                    Log.d(TAG, "Location disabled in Settings for current user:" + this.mCurrentUserId);
                }
                return false;
            } else if (this.mLastLocation.get(provider) == null) {
                updateLastLocationLocked(location, provider);
                return true;
            } else {
                if (D) {
                    Log.d(TAG, "injectLocation(): Location exists. Not updating");
                }
                return false;
            }
        }
    }

    public void requestGeofence(LocationRequest request, Geofence geofence, PendingIntent intent, String packageName) {
        Geofence geofence2;
        long identity;
        LocationRequest request2 = request == null ? DEFAULT_LOCATION_REQUEST : request;
        int allowedResolutionLevel = getCallerAllowedResolutionLevel();
        checkResolutionLevelIsSufficientForGeofenceUse(allowedResolutionLevel);
        checkPendingIntent(intent);
        checkPackageName(packageName);
        checkResolutionLevelIsSufficientForProviderUse(allowedResolutionLevel, request2.getProvider());
        boolean callerHasLocationHardwarePermission = this.mContext.checkCallingPermission("android.permission.LOCATION_HARDWARE") == 0;
        LocationRequest sanitizedRequest = createSanitizedRequest(request2, allowedResolutionLevel, callerHasLocationHardwarePermission);
        if (D) {
            StringBuilder sb = new StringBuilder();
            sb.append("requestGeofence: ");
            sb.append(sanitizedRequest);
            sb.append(" ");
            geofence2 = geofence;
            sb.append(geofence2);
            sb.append(" ");
            sb.append(intent);
            Log.d(TAG, sb.toString());
        } else {
            geofence2 = geofence;
        }
        int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != 0) {
            Log.w(TAG, "proximity alerts are currently available only to the primary user");
            return;
        }
        long identity2 = Binder.clearCallingIdentity();
        try {
            identity = identity2;
            try {
                this.mGeofenceManager.addFence(sanitizedRequest, geofence2, intent, allowedResolutionLevel, uid, packageName);
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                th = th;
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identity = identity2;
        }
    }

    public void removeGeofence(Geofence geofence, PendingIntent intent, String packageName) {
        checkPendingIntent(intent);
        checkPackageName(packageName);
        if (D) {
            Log.d(TAG, "removeGeofence: " + geofence + " " + intent);
        }
        long identity = Binder.clearCallingIdentity();
        try {
            this.mGeofenceManager.removeFence(geofence, intent);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean registerGnssStatusCallback(IGnssStatusListener callback, String packageName) {
        if (!hasGnssPermissions(packageName) || this.mGnssStatusProvider == null) {
            return false;
        }
        try {
            this.mGnssStatusProvider.registerGnssStatusCallback(callback);
            return true;
        } catch (RemoteException e) {
            Slog.e(TAG, "mGpsStatusProvider.registerGnssStatusCallback failed", e);
            return false;
        }
    }

    public void unregisterGnssStatusCallback(IGnssStatusListener callback) {
        synchronized (this.mLock) {
            try {
                this.mGnssStatusProvider.unregisterGnssStatusCallback(callback);
            } catch (Exception e) {
                Slog.e(TAG, "mGpsStatusProvider.unregisterGnssStatusCallback failed", e);
            }
        }
    }

    public boolean addGnssMeasurementsListener(IGnssMeasurementsListener listener, String packageName) {
        if (!hasGnssPermissions(packageName) || this.mGnssMeasurementsProvider == null) {
            return false;
        }
        synchronized (this.mLock) {
            Identity callerIdentity = new Identity(Binder.getCallingUid(), Binder.getCallingPid(), packageName);
            this.mGnssMeasurementsListeners.put(listener.asBinder(), callerIdentity);
            long identity = Binder.clearCallingIdentity();
            if (isThrottlingExemptLocked(callerIdentity) || isImportanceForeground(this.mActivityManager.getPackageImportance(packageName))) {
                boolean addListener = this.mGnssMeasurementsProvider.addListener(listener);
                Binder.restoreCallingIdentity(identity);
                return addListener;
            }
            Binder.restoreCallingIdentity(identity);
            return true;
        }
    }

    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        if (this.mGnssMeasurementsProvider != null) {
            synchronized (this.mLock) {
                this.mGnssMeasurementsListeners.remove(listener.asBinder());
                this.mGnssMeasurementsProvider.removeListener(listener);
            }
        }
    }

    public boolean addGnssNavigationMessageListener(IGnssNavigationMessageListener listener, String packageName) {
        if (!hasGnssPermissions(packageName) || this.mGnssNavigationMessageProvider == null) {
            return false;
        }
        synchronized (this.mLock) {
            Identity callerIdentity = new Identity(Binder.getCallingUid(), Binder.getCallingPid(), packageName);
            this.mGnssNavigationMessageListeners.put(listener.asBinder(), callerIdentity);
            long identity = Binder.clearCallingIdentity();
            if (isThrottlingExemptLocked(callerIdentity) || isImportanceForeground(this.mActivityManager.getPackageImportance(packageName))) {
                boolean addListener = this.mGnssNavigationMessageProvider.addListener(listener);
                Binder.restoreCallingIdentity(identity);
                return addListener;
            }
            Binder.restoreCallingIdentity(identity);
            return true;
        }
    }

    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        if (this.mGnssNavigationMessageProvider != null) {
            synchronized (this.mLock) {
                this.mGnssNavigationMessageListeners.remove(listener.asBinder());
                this.mGnssNavigationMessageProvider.removeListener(listener);
            }
        }
    }

    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        if (provider == null) {
            throw new NullPointerException();
        }
        checkResolutionLevelIsSufficientForProviderUse(getCallerAllowedResolutionLevel(), provider);
        if (this.mContext.checkCallingOrSelfPermission(ACCESS_LOCATION_EXTRA_COMMANDS) != 0) {
            throw new SecurityException("Requires ACCESS_LOCATION_EXTRA_COMMANDS permission");
        }
        synchronized (this.mLock) {
            LocationProviderInterface p = this.mProvidersByName.get(provider);
            if (p == null) {
                return false;
            }
            return p.sendExtraCommand(command, extras);
        }
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

    public ProviderProperties getProviderProperties(String provider) {
        LocationProviderInterface p;
        if (this.mProvidersByName.get(provider) == null) {
            return null;
        }
        checkResolutionLevelIsSufficientForProviderUse(getCallerAllowedResolutionLevel(), provider);
        synchronized (this.mLock) {
            p = this.mProvidersByName.get(provider);
        }
        if (p == null) {
            return null;
        }
        return p.getProperties();
    }

    public String getNetworkProviderPackage() {
        synchronized (this.mLock) {
            if (this.mProvidersByName.get("network") == null) {
                return null;
            }
            LocationProviderInterface p = this.mProvidersByName.get("network");
            if (p instanceof LocationProviderProxy) {
                return ((LocationProviderProxy) p).getConnectedPackageName();
            }
            return null;
        }
    }

    public boolean isLocationEnabledForUser(int userId) {
        checkInteractAcrossUsersPermission(userId);
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                String allowedProviders = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "location_providers_allowed", userId);
                if (allowedProviders == null) {
                    return false;
                }
                List<String> providerList = Arrays.asList(allowedProviders.split(","));
                for (String provider : this.mRealProviders.keySet()) {
                    if (!provider.equals("passive") && !provider.equals("fused") && providerList.contains(provider)) {
                        return true;
                    }
                }
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void setLocationEnabledForUser(boolean enabled, int userId) {
        this.mContext.enforceCallingPermission("android.permission.WRITE_SECURE_SETTINGS", "Requires WRITE_SECURE_SETTINGS permission");
        checkInteractAcrossUsersPermission(userId);
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                Set<String> allRealProviders = this.mRealProviders.keySet();
                Set<String> allProvidersSet = new ArraySet<>(allRealProviders.size() + 2);
                allProvidersSet.addAll(allRealProviders);
                if (!enabled) {
                    allProvidersSet.add("gps");
                    allProvidersSet.add("network");
                }
                if (allProvidersSet.isEmpty()) {
                    return;
                }
                String prefix = enabled ? "+" : "-";
                StringBuilder locationProvidersAllowed = new StringBuilder();
                for (String provider : allProvidersSet) {
                    if (!provider.equals("passive") && !provider.equals("fused")) {
                        locationProvidersAllowed.append(prefix);
                        locationProvidersAllowed.append(provider);
                        locationProvidersAllowed.append(",");
                    }
                }
                locationProvidersAllowed.setLength(locationProvidersAllowed.length() - 1);
                Settings.Secure.putStringForUser(this.mContext.getContentResolver(), "location_providers_allowed", locationProvidersAllowed.toString(), userId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public boolean isProviderEnabledForUser(String provider, int userId) {
        checkInteractAcrossUsersPermission(userId);
        boolean z = false;
        if ("fused".equals(provider)) {
            return false;
        }
        int uid = Binder.getCallingUid();
        synchronized (this.mLock) {
            LocationProviderInterface p = this.mProvidersByName.get(provider);
            if (p != null && isAllowedByUserSettingsLocked(provider, uid, userId)) {
                z = true;
            }
        }
        return z;
    }

    public boolean setProviderEnabledForUser(String provider, boolean enabled, int userId) {
        this.mContext.enforceCallingPermission("android.permission.WRITE_SECURE_SETTINGS", "Requires WRITE_SECURE_SETTINGS permission");
        checkInteractAcrossUsersPermission(userId);
        if ("fused".equals(provider)) {
            return false;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                if (this.mProvidersByName.containsKey(provider)) {
                    if (this.mMockProviders.containsKey(provider)) {
                        setTestProviderEnabled(provider, enabled);
                        return true;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(enabled ? "+" : "-");
                    sb.append(provider);
                    String providerChange = sb.toString();
                    return Settings.Secure.putStringForUser(this.mContext.getContentResolver(), "location_providers_allowed", providerChange, userId);
                }
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isLocationProviderEnabledForUser(String provider, int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            ContentResolver cr = this.mContext.getContentResolver();
            String allowedProviders = Settings.Secure.getStringForUser(cr, "location_providers_allowed", userId);
            return TextUtils.delimitedStringContains(allowedProviders, ',', provider);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void checkInteractAcrossUsersPermission(int userId) {
        int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != userId && ActivityManager.checkComponentPermission("android.permission.INTERACT_ACROSS_USERS", uid, -1, true) != 0) {
            throw new SecurityException("Requires INTERACT_ACROSS_USERS permission");
        }
    }

    private boolean isUidALocationProvider(int uid) {
        if (uid == 1000) {
            return true;
        }
        if (this.mGeocodeProvider != null && doesUidHavePackage(uid, this.mGeocodeProvider.getConnectedPackageName())) {
            return true;
        }
        Iterator<LocationProviderProxy> it = this.mProxyProviders.iterator();
        while (it.hasNext()) {
            LocationProviderProxy proxy = it.next();
            if (doesUidHavePackage(uid, proxy.getConnectedPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void checkCallerIsProvider() {
        if (this.mContext.checkCallingOrSelfPermission(INSTALL_LOCATION_PROVIDER) == 0 || isUidALocationProvider(Binder.getCallingUid())) {
            return;
        }
        throw new SecurityException("need INSTALL_LOCATION_PROVIDER permission, or UID of a currently bound location provider");
    }

    private boolean doesUidHavePackage(int uid, String packageName) {
        String[] packageNames;
        if (packageName == null || (packageNames = this.mPackageManager.getPackagesForUid(uid)) == null) {
            return false;
        }
        for (String name : packageNames) {
            if (packageName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public void reportLocation(Location location, boolean passive) {
        checkCallerIsProvider();
        if (!location.isComplete()) {
            if (this.mLocationPrintCount.get() % 10 == 0) {
                Log.w(TAG, "Dropping incomplete location: " + location);
                this.mLocationPrintCount.compareAndSet(10, 0);
            }
            this.mLocationPrintCount.getAndAdd(1);
            return;
        }
        this.mLocationHandler.removeMessages(1, location);
        Message m = Message.obtain(this.mLocationHandler, 1, location);
        m.arg1 = passive ? 1 : 0;
        this.mLocationHandler.sendMessageAtFrontOfQueue(m);
    }

    private static boolean shouldBroadcastSafe(Location loc, Location lastLoc, UpdateRecord record, long now) {
        if (lastLoc == null) {
            return true;
        }
        long minTime = record.mRealRequest.getFastestInterval();
        long delta = (loc.getElapsedRealtimeNanos() - lastLoc.getElapsedRealtimeNanos()) / NANOS_PER_MILLI;
        if (delta < minTime - 100) {
            return false;
        }
        double minDistance = record.mRealRequest.getSmallestDisplacement();
        return (minDistance <= 0.0d || ((double) loc.distanceTo(lastLoc)) > minDistance) && record.mRealRequest.getNumUpdates() > 0 && record.mRealRequest.getExpireAt() >= now;
    }

    /* JADX WARN: Removed duplicated region for block: B:100:0x026f  */
    /* JADX WARN: Removed duplicated region for block: B:91:0x024f  */
    /* JADX WARN: Removed duplicated region for block: B:94:0x025a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void handleLocationChangedLocked(android.location.Location r32, boolean r33) {
        /*
            Method dump skipped, instructions count: 713
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.LocationManagerService.handleLocationChangedLocked(android.location.Location, boolean):void");
    }

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

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LocationWorkerHandler extends Handler {
        public LocationWorkerHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                LocationManagerService.this.handleLocationChanged((Location) msg.obj, msg.arg1 == 1);
            }
        }
    }

    private boolean isMockProvider(String provider) {
        boolean containsKey;
        synchronized (this.mLock) {
            containsKey = this.mMockProviders.containsKey(provider);
        }
        return containsKey;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLocationChanged(Location location, boolean passive) {
        Location myLocation = new Location(location);
        String provider = myLocation.getProvider();
        if (!myLocation.isFromMockProvider() && isMockProvider(provider)) {
            myLocation.setIsFromMockProvider(true);
        }
        synchronized (this.mLock) {
            if (isAllowedByCurrentUserSettingsLocked(provider)) {
                if (!passive) {
                    this.mPassiveProvider.updateLocation(myLocation);
                }
                handleLocationChangedLocked(myLocation, passive);
            }
        }
    }

    public boolean geocoderIsPresent() {
        return this.mGeocodeProvider != null;
    }

    public String getFromLocation(double latitude, double longitude, int maxResults, GeocoderParams params, List<Address> addrs) {
        if (this.mGeocodeProvider != null) {
            return this.mGeocodeProvider.getFromLocation(latitude, longitude, maxResults, params, addrs);
        }
        return null;
    }

    public String getFromLocationName(String locationName, double lowerLeftLatitude, double lowerLeftLongitude, double upperRightLatitude, double upperRightLongitude, int maxResults, GeocoderParams params, List<Address> addrs) {
        if (this.mGeocodeProvider != null) {
            return this.mGeocodeProvider.getFromLocationName(locationName, lowerLeftLatitude, lowerLeftLongitude, upperRightLatitude, upperRightLongitude, maxResults, params, addrs);
        }
        return null;
    }

    private boolean canCallerAccessMockLocation(String opPackageName) {
        return this.mAppOps.noteOp(58, Binder.getCallingUid(), opPackageName) == 0;
    }

    public void addTestProvider(String name, ProviderProperties properties, String opPackageName) {
        LocationProviderInterface p;
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        if ("passive".equals(name)) {
            throw new IllegalArgumentException("Cannot mock the passive location provider");
        }
        long identity = Binder.clearCallingIdentity();
        synchronized (this.mLock) {
            if (("gps".equals(name) || "network".equals(name) || "fused".equals(name)) && (p = this.mProvidersByName.get(name)) != null) {
                removeProviderLocked(p);
            }
            addTestProviderLocked(name, properties);
            updateProvidersLocked();
        }
        Binder.restoreCallingIdentity(identity);
    }

    private void addTestProviderLocked(String name, ProviderProperties properties) {
        if (this.mProvidersByName.get(name) != null) {
            throw new IllegalArgumentException("Provider \"" + name + "\" already exists");
        }
        MockProvider provider = new MockProvider(name, this, properties);
        addProviderLocked(provider);
        this.mMockProviders.put(name, provider);
        this.mLastLocation.put(name, null);
        this.mLastLocationCoarseInterval.put(name, null);
    }

    public void removeTestProvider(String provider, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            clearTestProviderEnabled(provider, opPackageName);
            clearTestProviderLocation(provider, opPackageName);
            clearTestProviderStatus(provider, opPackageName);
            MockProvider mockProvider = this.mMockProviders.remove(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            long identity = Binder.clearCallingIdentity();
            removeProviderLocked(this.mProvidersByName.get(provider));
            LocationProviderInterface realProvider = this.mRealProviders.get(provider);
            if (realProvider != null) {
                addProviderLocked(realProvider);
            }
            this.mLastLocation.put(provider, null);
            this.mLastLocationCoarseInterval.put(provider, null);
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void setTestProviderLocation(String provider, Location loc, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            MockProvider mockProvider = this.mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            Location mock = new Location(loc);
            mock.setIsFromMockProvider(true);
            if (!TextUtils.isEmpty(loc.getProvider()) && !provider.equals(loc.getProvider())) {
                EventLog.writeEvent(1397638484, "33091107", Integer.valueOf(Binder.getCallingUid()), provider + "!=" + loc.getProvider());
            }
            long identity = Binder.clearCallingIdentity();
            mockProvider.setLocation(mock);
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void clearTestProviderLocation(String provider, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            MockProvider mockProvider = this.mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mockProvider.clearLocation();
        }
    }

    public void setTestProviderEnabled(String provider, boolean enabled, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        setTestProviderEnabled(provider, enabled);
    }

    private void setTestProviderEnabled(String provider, boolean enabled) {
        synchronized (this.mLock) {
            MockProvider mockProvider = this.mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            long identity = Binder.clearCallingIdentity();
            if (enabled) {
                mockProvider.enable();
                this.mEnabledProviders.add(provider);
                this.mDisabledProviders.remove(provider);
            } else {
                mockProvider.disable();
                this.mEnabledProviders.remove(provider);
                this.mDisabledProviders.add(provider);
            }
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void clearTestProviderEnabled(String provider, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            MockProvider mockProvider = this.mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            long identity = Binder.clearCallingIdentity();
            this.mEnabledProviders.remove(provider);
            this.mDisabledProviders.remove(provider);
            updateProvidersLocked();
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void setTestProviderStatus(String provider, int status, Bundle extras, long updateTime, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            MockProvider mockProvider = this.mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mockProvider.setStatus(status, extras, updateTime);
        }
    }

    public void clearTestProviderStatus(String provider, String opPackageName) {
        if (!canCallerAccessMockLocation(opPackageName)) {
            return;
        }
        synchronized (this.mLock) {
            MockProvider mockProvider = this.mMockProviders.get(provider);
            if (mockProvider == null) {
                throw new IllegalArgumentException("Provider \"" + provider + "\" unknown");
            }
            mockProvider.clearStatus();
        }
    }

    private void log(String log) {
        if (Log.isLoggable(TAG, 2)) {
            Slog.d(TAG, log);
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
                for (Identity identity : this.mGnssMeasurementsListeners.values()) {
                    pw.println("    " + identity.mPid + " " + identity.mUid + " " + identity.mPackageName + ": " + isThrottlingExemptLocked(identity));
                }
                pw.println("  Active GnssNavigationMessage Listeners:");
                for (Identity identity2 : this.mGnssNavigationMessageListeners.values()) {
                    pw.println("    " + identity2.mPid + " " + identity2.mUid + " " + identity2.mPackageName + ": " + isThrottlingExemptLocked(identity2));
                }
                pw.println("  Overlay Provider Packages:");
                Iterator<LocationProviderInterface> it2 = this.mProviders.iterator();
                while (it2.hasNext()) {
                    LocationProviderInterface provider = it2.next();
                    if (provider instanceof LocationProviderProxy) {
                        pw.println("    " + provider.getName() + ": " + ((LocationProviderProxy) provider).getConnectedPackageName());
                    }
                }
                pw.println("  Historical Records by Provider:");
                for (Map.Entry<LocationRequestStatistics.PackageProviderKey, LocationRequestStatistics.PackageStatistics> entry2 : this.mRequestStatistics.statistics.entrySet()) {
                    LocationRequestStatistics.PackageProviderKey key = entry2.getKey();
                    LocationRequestStatistics.PackageStatistics stats = entry2.getValue();
                    pw.println("    " + key.packageName + ": " + key.providerName + ": " + stats);
                }
                pw.println("  Last Known Locations:");
                for (Map.Entry<String, Location> entry3 : this.mLastLocation.entrySet()) {
                    Location location = entry3.getValue();
                    pw.println("    " + entry3.getKey() + ": " + location);
                }
                pw.println("  Last Known Locations Coarse Intervals:");
                for (Map.Entry<String, Location> entry4 : this.mLastLocationCoarseInterval.entrySet()) {
                    Location location2 = entry4.getValue();
                    pw.println("    " + entry4.getKey() + ": " + location2);
                }
                this.mGeofenceManager.dump(pw);
                if (this.mEnabledProviders.size() > 0) {
                    pw.println("  Enabled Providers:");
                    for (String i : this.mEnabledProviders) {
                        pw.println("    " + i);
                    }
                }
                if (this.mDisabledProviders.size() > 0) {
                    pw.println("  Disabled Providers:");
                    for (String i2 : this.mDisabledProviders) {
                        pw.println("    " + i2);
                    }
                }
                pw.append("  ");
                this.mBlacklist.dump(pw);
                if (this.mMockProviders.size() > 0) {
                    pw.println("  Mock Providers:");
                    for (Map.Entry<String, MockProvider> i3 : this.mMockProviders.entrySet()) {
                        i3.getValue().dump(pw, "      ");
                    }
                }
                if (!this.mBackgroundThrottlePackageWhitelist.isEmpty()) {
                    pw.println("  Throttling Whitelisted Packages:");
                    Iterator<String> it3 = this.mBackgroundThrottlePackageWhitelist.iterator();
                    while (it3.hasNext()) {
                        String packageName = it3.next();
                        pw.println("    " + packageName);
                    }
                }
                pw.append("  fudger: ");
                this.mLocationFudger.dump(fd, pw, args);
                if (args.length <= 0 || !"short".equals(args[0])) {
                    Iterator<LocationProviderInterface> it4 = this.mProviders.iterator();
                    while (it4.hasNext()) {
                        LocationProviderInterface provider2 = it4.next();
                        pw.print(provider2.getName() + " Internal State");
                        if (provider2 instanceof LocationProviderProxy) {
                            LocationProviderProxy proxy = (LocationProviderProxy) provider2;
                            pw.print(" (" + proxy.getConnectedPackageName() + ")");
                        }
                        pw.println(":");
                        provider2.dump(fd, pw, args);
                    }
                    if (this.mGnssBatchingInProgress) {
                        pw.println("  GNSS batching in progress");
                    }
                }
            }
        }
    }
}
