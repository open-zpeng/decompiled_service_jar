package com.android.server.location;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.location.GeofenceHardwareImpl;
import android.location.FusedBatchOptions;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.IGnssStatusListener;
import android.location.IGnssStatusProvider;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationManager;
import android.location.INetInitiatedListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.util.NetworkConstants;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.app.IBatteryStats;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.location.gnssmetrics.GnssMetrics;
import com.android.server.UiModeManagerService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.location.GnssLocationProvider;
import com.android.server.location.GnssSatelliteBlacklistHelper;
import com.android.server.location.NtpTimeHelper;
import com.xiaopeng.server.input.xpInputActionHandler;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import libcore.io.IoUtils;
/* loaded from: classes.dex */
public class GnssLocationProvider implements LocationProviderInterface, NtpTimeHelper.InjectNtpTimeCallback, GnssSatelliteBlacklistHelper.GnssSatelliteBlacklistCallback {
    private static final int ADD_LISTENER = 8;
    private static final int AGPS_DATA_CONNECTION_CLOSED = 0;
    private static final int AGPS_DATA_CONNECTION_OPEN = 2;
    private static final int AGPS_DATA_CONNECTION_OPENING = 1;
    private static final int AGPS_REF_LOCATION_TYPE_GSM_CELLID = 1;
    private static final int AGPS_REF_LOCATION_TYPE_UMTS_CELLID = 2;
    private static final int AGPS_RIL_REQUEST_SETID_IMSI = 1;
    private static final int AGPS_RIL_REQUEST_SETID_MSISDN = 2;
    private static final int AGPS_SETID_TYPE_IMSI = 1;
    private static final int AGPS_SETID_TYPE_MSISDN = 2;
    private static final int AGPS_SETID_TYPE_NONE = 0;
    private static final int AGPS_SUPL_MODE_MSA = 2;
    private static final int AGPS_SUPL_MODE_MSB = 1;
    private static final int AGPS_TYPE_C2K = 2;
    private static final int AGPS_TYPE_SUPL = 1;
    private static final String ALARM_TIMEOUT = "com.android.internal.location.ALARM_TIMEOUT";
    private static final String ALARM_WAKEUP = "com.android.internal.location.ALARM_WAKEUP";
    private static final int APN_INVALID = 0;
    private static final int APN_IPV4 = 1;
    private static final int APN_IPV4V6 = 3;
    private static final int APN_IPV6 = 2;
    private static final int CHECK_LOCATION = 1;
    private static final String DEBUG_PROPERTIES_FILE = "/etc/gps_debug.conf";
    private static final String DOWNLOAD_EXTRA_WAKELOCK_KEY = "GnssLocationProviderXtraDownload";
    private static final int DOWNLOAD_XTRA_DATA = 6;
    private static final int DOWNLOAD_XTRA_DATA_FINISHED = 11;
    private static final long DOWNLOAD_XTRA_DATA_TIMEOUT_MS = 60000;
    private static final int ENABLE = 2;
    private static final int GPS_AGPS_DATA_CONNECTED = 3;
    private static final int GPS_AGPS_DATA_CONN_DONE = 4;
    private static final int GPS_AGPS_DATA_CONN_FAILED = 5;
    private static final int GPS_CAPABILITY_GEOFENCING = 32;
    private static final int GPS_CAPABILITY_MEASUREMENTS = 64;
    private static final int GPS_CAPABILITY_MSA = 4;
    private static final int GPS_CAPABILITY_MSB = 2;
    private static final int GPS_CAPABILITY_NAV_MESSAGES = 128;
    private static final int GPS_CAPABILITY_ON_DEMAND_TIME = 16;
    private static final int GPS_CAPABILITY_SCHEDULING = 1;
    private static final int GPS_CAPABILITY_SINGLE_SHOT = 8;
    private static final int GPS_DELETE_ALL = 65535;
    private static final int GPS_DELETE_ALMANAC = 2;
    private static final int GPS_DELETE_CELLDB_INFO = 32768;
    private static final int GPS_DELETE_EPHEMERIS = 1;
    private static final int GPS_DELETE_HEALTH = 64;
    private static final int GPS_DELETE_IONO = 16;
    private static final int GPS_DELETE_POSITION = 4;
    private static final int GPS_DELETE_RTI = 1024;
    private static final int GPS_DELETE_SADATA = 512;
    private static final int GPS_DELETE_SVDIR = 128;
    private static final int GPS_DELETE_SVSTEER = 256;
    private static final int GPS_DELETE_TIME = 8;
    private static final int GPS_DELETE_UTC = 32;
    private static final int GPS_GEOFENCE_AVAILABLE = 2;
    private static final int GPS_GEOFENCE_ERROR_GENERIC = -149;
    private static final int GPS_GEOFENCE_ERROR_ID_EXISTS = -101;
    private static final int GPS_GEOFENCE_ERROR_ID_UNKNOWN = -102;
    private static final int GPS_GEOFENCE_ERROR_INVALID_TRANSITION = -103;
    private static final int GPS_GEOFENCE_ERROR_TOO_MANY_GEOFENCES = 100;
    private static final int GPS_GEOFENCE_OPERATION_SUCCESS = 0;
    private static final int GPS_GEOFENCE_UNAVAILABLE = 1;
    private static final int GPS_POLLING_THRESHOLD_INTERVAL = 10000;
    private static final int GPS_POSITION_MODE_MS_ASSISTED = 2;
    private static final int GPS_POSITION_MODE_MS_BASED = 1;
    private static final int GPS_POSITION_MODE_STANDALONE = 0;
    private static final int GPS_POSITION_RECURRENCE_PERIODIC = 0;
    private static final int GPS_POSITION_RECURRENCE_SINGLE = 1;
    private static final int GPS_RELEASE_AGPS_DATA_CONN = 2;
    private static final int GPS_REQUEST_AGPS_DATA_CONN = 1;
    private static final int GPS_STATUS_ENGINE_OFF = 4;
    private static final int GPS_STATUS_ENGINE_ON = 3;
    private static final int GPS_STATUS_NONE = 0;
    private static final int GPS_STATUS_SESSION_BEGIN = 1;
    private static final int GPS_STATUS_SESSION_END = 2;
    private static final int INITIALIZE_HANDLER = 13;
    private static final int INJECT_NTP_TIME = 5;
    private static final float ITAR_SPEED_LIMIT_METERS_PER_SECOND = 400.0f;
    private static final int LOCATION_HAS_ALTITUDE = 2;
    private static final int LOCATION_HAS_BEARING = 8;
    private static final int LOCATION_HAS_BEARING_ACCURACY = 128;
    private static final int LOCATION_HAS_HORIZONTAL_ACCURACY = 16;
    private static final int LOCATION_HAS_LAT_LONG = 1;
    private static final int LOCATION_HAS_SPEED = 4;
    private static final int LOCATION_HAS_SPEED_ACCURACY = 64;
    private static final int LOCATION_HAS_VERTICAL_ACCURACY = 32;
    private static final int LOCATION_INVALID = 0;
    private static final long LOCATION_UPDATE_DURATION_MILLIS = 10000;
    private static final long LOCATION_UPDATE_MIN_TIME_INTERVAL_MILLIS = 1000;
    private static final String LPP_PROFILE = "persist.sys.gps.lpp";
    private static final long MAX_RETRY_INTERVAL = 14400000;
    private static final int MESSAGE_PRINT_INTERVAL = 10;
    private static final int NO_FIX_TIMEOUT = 60000;
    private static final long RECENT_FIX_TIMEOUT = 10000;
    private static final int RELEASE_SUPL_CONNECTION = 15;
    private static final int REMOVE_LISTENER = 9;
    private static final int REPORT_LOCATION = 17;
    private static final int REPORT_SV_STATUS = 18;
    private static final int REQUEST_LOCATION = 16;
    private static final int REQUEST_SUPL_CONNECTION = 14;
    private static final long RETRY_INTERVAL = 300000;
    private static final int SET_REQUEST = 3;
    private static final String SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED";
    private static final int STATE_DOWNLOADING = 1;
    private static final int STATE_IDLE = 2;
    private static final int STATE_PENDING_NETWORK = 0;
    private static final int SUBSCRIPTION_OR_SIM_CHANGED = 12;
    private static final String TAG = "GnssLocationProvider";
    private static final int TCP_MAX_PORT = 65535;
    private static final int TCP_MIN_PORT = 0;
    private static final int UPDATE_LOCATION = 7;
    private static final int UPDATE_NETWORK_STATE = 4;
    private static final String WAKELOCK_KEY = "GnssLocationProvider";
    private InetAddress mAGpsDataConnectionIpAddr;
    private int mAGpsDataConnectionState;
    private final AlarmManager mAlarmManager;
    private final AppOpsManager mAppOps;
    private final IBatteryStats mBatteryStats;
    private String mC2KServerHost;
    private int mC2KServerPort;
    private final ConnectivityManager mConnMgr;
    private final Context mContext;
    private final PowerManager.WakeLock mDownloadXtraWakeLock;
    private boolean mEnabled;
    private int mEngineCapabilities;
    private boolean mEngineOn;
    private GeofenceHardwareImpl mGeofenceHardwareImpl;
    private final GnssBatchingProvider mGnssBatchingProvider;
    private final GnssGeofenceProvider mGnssGeofenceProvider;
    private final GnssMeasurementsProvider mGnssMeasurementsProvider;
    private GnssMetrics mGnssMetrics;
    private final GnssNavigationMessageProvider mGnssNavigationMessageProvider;
    private final GnssSatelliteBlacklistHelper mGnssSatelliteBlacklistHelper;
    private SparseArray<AtomicInteger> mHandleMessageArray;
    private Handler mHandler;
    private volatile String mHardwareModelName;
    private final ILocationManager mILocationManager;
    private long mLastFixTime;
    private final GnssStatusListenerHelper mListenerHelper;
    private final GpsNetInitiatedHandler mNIHandler;
    private boolean mNavigating;
    private final NtpTimeHelper mNtpTimeHelper;
    private int mPositionMode;
    private final PowerManager mPowerManager;
    private Properties mProperties;
    private boolean mSingleShot;
    private boolean mStarted;
    private String mSuplServerHost;
    private boolean mSupportsXtra;
    private final PendingIntent mTimeoutIntent;
    private final PowerManager.WakeLock mWakeLock;
    private final PendingIntent mWakeupIntent;
    private static final boolean DEBUG = Log.isLoggable("GnssLocationProvider", 3);
    private static final boolean VERBOSE = Log.isLoggable("GnssLocationProvider", 2);
    private static final ProviderProperties PROPERTIES = new ProviderProperties(true, true, false, false, true, true, true, 3, 1);
    private final Object mLock = new Object();
    private int mStatus = 1;
    private long mStatusUpdateTime = SystemClock.elapsedRealtime();
    private final ExponentialBackOff mXtraBackOff = new ExponentialBackOff(300000, 14400000);
    private int mDownloadXtraDataPending = 0;
    private int mFixInterval = 1000;
    private boolean mLowPowerMode = false;
    private long mFixRequestTime = 0;
    private int mTimeToFirstFix = 0;
    private ProviderRequest mProviderRequest = null;
    private WorkSource mWorkSource = null;
    private boolean mDisableGps = false;
    private int mSuplServerPort = 0;
    private boolean mSuplEsEnabled = false;
    private final LocationExtras mLocationExtras = new LocationExtras();
    private final LocationChangeListener mNetworkLocationListener = new NetworkLocationListener();
    private final LocationChangeListener mFusedLocationListener = new FusedLocationListener();
    private WorkSource mClientSource = new WorkSource();
    private volatile int mHardwareYear = 0;
    private volatile boolean mItarSpeedLimitExceeded = false;
    private final IGnssStatusProvider mGnssStatusProvider = new IGnssStatusProvider.Stub() { // from class: com.android.server.location.GnssLocationProvider.1
        public void registerGnssStatusCallback(IGnssStatusListener callback) {
            GnssLocationProvider.this.mListenerHelper.addListener(callback);
        }

        public void unregisterGnssStatusCallback(IGnssStatusListener callback) {
            GnssLocationProvider.this.mListenerHelper.removeListener(callback);
        }
    };
    private final ConnectivityManager.NetworkCallback mNetworkConnectivityCallback = new ConnectivityManager.NetworkCallback() { // from class: com.android.server.location.GnssLocationProvider.2
        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onAvailable(Network network) {
            GnssLocationProvider.this.mNtpTimeHelper.onNetworkAvailable();
            if (GnssLocationProvider.this.mDownloadXtraDataPending == 0 && GnssLocationProvider.this.mSupportsXtra) {
                GnssLocationProvider.this.xtraDownloadRequest();
            }
            GnssLocationProvider.this.sendMessage(4, 0, network);
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onLost(Network network) {
            GnssLocationProvider.this.sendMessage(4, 0, network);
        }
    };
    private final ConnectivityManager.NetworkCallback mSuplConnectivityCallback = new ConnectivityManager.NetworkCallback() { // from class: com.android.server.location.GnssLocationProvider.3
        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onAvailable(Network network) {
            GnssLocationProvider.this.sendMessage(4, 0, network);
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onLost(Network network) {
            GnssLocationProvider.this.releaseSuplConnection(2);
        }
    };
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.location.GnssLocationProvider.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (GnssLocationProvider.DEBUG) {
                Log.d("GnssLocationProvider", "receive broadcast intent, action: " + action);
            }
            if (action == null) {
                return;
            }
            if (action.equals(GnssLocationProvider.ALARM_WAKEUP)) {
                GnssLocationProvider.this.startNavigating(false);
            } else if (action.equals(GnssLocationProvider.ALARM_TIMEOUT)) {
                GnssLocationProvider.this.hibernate();
            } else if ("android.os.action.POWER_SAVE_MODE_CHANGED".equals(action) || "android.os.action.DEVICE_IDLE_MODE_CHANGED".equals(action) || "android.intent.action.SCREEN_OFF".equals(action) || "android.intent.action.SCREEN_ON".equals(action)) {
                GnssLocationProvider.this.updateLowPowerMode();
            } else if (action.equals(GnssLocationProvider.SIM_STATE_CHANGED)) {
                GnssLocationProvider.this.subscriptionOrSimChanged(context);
            }
        }
    };
    private final SubscriptionManager.OnSubscriptionsChangedListener mOnSubscriptionsChangedListener = new SubscriptionManager.OnSubscriptionsChangedListener() { // from class: com.android.server.location.GnssLocationProvider.5
        @Override // android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
        public void onSubscriptionsChanged() {
            GnssLocationProvider.this.sendMessage(12, 0, null);
        }
    };
    private final INetInitiatedListener mNetInitiatedListener = new INetInitiatedListener.Stub() { // from class: com.android.server.location.GnssLocationProvider.16
        public boolean sendNiResponse(int notificationId, int userResponse) {
            if (GnssLocationProvider.DEBUG) {
                Log.d("GnssLocationProvider", "sendNiResponse, notifId: " + notificationId + ", response: " + userResponse);
            }
            GnssLocationProvider.this.native_send_ni_response(notificationId, userResponse);
            return true;
        }
    };
    private byte[] mNmeaBuffer = new byte[120];
    private SparseArray<AtomicInteger> mSendMessageArray = new SparseArray<>(3);

    /* loaded from: classes.dex */
    public interface GnssMetricsProvider {
        String getGnssMetricsAsProtoString();
    }

    /* loaded from: classes.dex */
    public interface GnssSystemInfoProvider {
        String getGnssHardwareModelName();

        int getGnssYearOfHardware();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface SetCarrierProperty {
        boolean set(int i);
    }

    private static native void class_init_native();

    private native void native_agps_data_conn_closed();

    private native void native_agps_data_conn_failed();

    private native void native_agps_data_conn_open(String str, int i);

    private native void native_agps_ni_message(byte[] bArr, int i);

    private native void native_agps_set_id(int i, String str);

    private native void native_agps_set_ref_location_cellid(int i, int i2, int i3, int i4, int i5);

    /* JADX INFO: Access modifiers changed from: private */
    public native void native_cleanup();

    private native void native_delete_aiding_data(int i);

    private native String native_get_internal_state();

    /* JADX INFO: Access modifiers changed from: private */
    public native boolean native_init();

    /* JADX INFO: Access modifiers changed from: private */
    public static native void native_init_once();

    private native void native_inject_best_location(int i, double d, double d2, double d3, float f, float f2, float f3, float f4, float f5, float f6, long j);

    private native void native_inject_location(double d, double d2, float f);

    private native void native_inject_time(long j, long j2, int i);

    /* JADX INFO: Access modifiers changed from: private */
    public native void native_inject_xtra_data(byte[] bArr, int i);

    private static native boolean native_is_agps_ril_supported();

    private static native boolean native_is_gnss_configuration_supported();

    private static native boolean native_is_supported();

    private native int native_read_nmea(byte[] bArr, int i);

    /* JADX INFO: Access modifiers changed from: private */
    public native void native_send_ni_response(int i, int i2);

    private native void native_set_agps_server(int i, String str, int i2);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_emergency_supl_pdn(int i);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_gnss_pos_protocol_select(int i);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_gps_lock(int i);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_lpp_profile(int i);

    private native boolean native_set_position_mode(int i, int i2, int i3, int i4, int i5, boolean z);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_satellite_blacklist(int[] iArr, int[] iArr2);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_supl_es(int i);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_supl_mode(int i);

    /* JADX INFO: Access modifiers changed from: private */
    public static native boolean native_set_supl_version(int i);

    private native boolean native_start();

    private native boolean native_stop();

    private native boolean native_supports_xtra();

    private native void native_update_network_state(boolean z, int i, boolean z2, boolean z3, String str, String str2);

    static /* synthetic */ boolean access$5800() {
        return native_is_agps_ril_supported();
    }

    static {
        class_init_native();
    }

    /* loaded from: classes.dex */
    private static class GpsRequest {
        public ProviderRequest request;
        public WorkSource source;

        public GpsRequest(ProviderRequest request, WorkSource source) {
            this.request = request;
            this.source = source;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class LocationExtras {
        private final Bundle mBundle = new Bundle();
        private int mMaxCn0;
        private int mMeanCn0;
        private int mSvCount;

        public void set(int svCount, int meanCn0, int maxCn0) {
            synchronized (this) {
                this.mSvCount = svCount;
                this.mMeanCn0 = meanCn0;
                this.mMaxCn0 = maxCn0;
            }
            setBundle(this.mBundle);
        }

        public void reset() {
            set(0, 0, 0);
        }

        public void setBundle(Bundle extras) {
            if (extras != null) {
                synchronized (this) {
                    extras.putInt("satellites", this.mSvCount);
                    extras.putInt("meanCn0", this.mMeanCn0);
                    extras.putInt("maxCn0", this.mMaxCn0);
                }
            }
        }

        public Bundle getBundle() {
            Bundle bundle;
            synchronized (this) {
                bundle = new Bundle(this.mBundle);
            }
            return bundle;
        }
    }

    public IGnssStatusProvider getGnssStatusProvider() {
        return this.mGnssStatusProvider;
    }

    public IGpsGeofenceHardware getGpsGeofenceProxy() {
        return this.mGnssGeofenceProvider;
    }

    public GnssMeasurementsProvider getGnssMeasurementsProvider() {
        return this.mGnssMeasurementsProvider;
    }

    public GnssNavigationMessageProvider getGnssNavigationMessageProvider() {
        return this.mGnssNavigationMessageProvider;
    }

    @Override // com.android.server.location.GnssSatelliteBlacklistHelper.GnssSatelliteBlacklistCallback
    public void onUpdateSatelliteBlacklist(final int[] constellations, final int[] svids) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$2m3d6BkqWO0fZAJAijxHyPDHfxI
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.native_set_satellite_blacklist(constellations, svids);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void subscriptionOrSimChanged(Context context) {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "received SIM related action: ");
        }
        TelephonyManager phone = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
        CarrierConfigManager configManager = (CarrierConfigManager) this.mContext.getSystemService("carrier_config");
        String mccMnc = phone.getSimOperator();
        boolean isKeepLppProfile = false;
        if (!TextUtils.isEmpty(mccMnc)) {
            if (DEBUG) {
                Log.d("GnssLocationProvider", "SIM MCC/MNC is available: " + mccMnc);
            }
            synchronized (this.mLock) {
                if (configManager != null) {
                    try {
                        PersistableBundle b = configManager.getConfig();
                        if (b != null) {
                            isKeepLppProfile = b.getBoolean("persist_lpp_mode_bool");
                        }
                    } finally {
                    }
                }
                if (isKeepLppProfile) {
                    loadPropertiesFromResource(context, this.mProperties);
                    String lpp_profile = this.mProperties.getProperty("LPP_PROFILE");
                    if (lpp_profile != null) {
                        SystemProperties.set(LPP_PROFILE, lpp_profile);
                    }
                } else {
                    SystemProperties.set(LPP_PROFILE, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                }
                reloadGpsProperties(context, this.mProperties);
                this.mNIHandler.setSuplEsEnabled(this.mSuplEsEnabled);
            }
        } else if (DEBUG) {
            Log.d("GnssLocationProvider", "SIM MCC/MNC is still not available");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateLowPowerMode() {
        boolean disableGps = this.mPowerManager.isDeviceIdleMode();
        boolean z = true;
        PowerSaveState result = this.mPowerManager.getPowerSaveState(1);
        if (result.gpsMode == 1) {
            disableGps |= (!result.batterySaverEnabled || this.mPowerManager.isInteractive()) ? false : false;
        }
        if (disableGps != this.mDisableGps) {
            this.mDisableGps = disableGps;
            updateRequirements();
        }
    }

    public static boolean isSupported() {
        return native_is_supported();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadGpsProperties(Context context, Properties properties) {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "Reset GPS properties, previous size = " + properties.size());
        }
        loadPropertiesFromResource(context, properties);
        String lpp_prof = SystemProperties.get(LPP_PROFILE);
        if (!TextUtils.isEmpty(lpp_prof)) {
            properties.setProperty("LPP_PROFILE", lpp_prof);
        }
        loadPropertiesFromFile(DEBUG_PROPERTIES_FILE, properties);
        setSuplHostPort(properties.getProperty("SUPL_HOST"), properties.getProperty("SUPL_PORT"));
        this.mC2KServerHost = properties.getProperty("C2K_HOST");
        String portString = properties.getProperty("C2K_PORT");
        if (this.mC2KServerHost != null && portString != null) {
            try {
                this.mC2KServerPort = Integer.parseInt(portString);
            } catch (NumberFormatException e) {
                Log.e("GnssLocationProvider", "unable to parse C2K_PORT: " + portString);
            }
        }
        if (native_is_gnss_configuration_supported()) {
            Map<String, SetCarrierProperty> map = new AnonymousClass6();
            for (Map.Entry<String, SetCarrierProperty> entry : map.entrySet()) {
                String propertyName = entry.getKey();
                String propertyValueString = properties.getProperty(propertyName);
                if (propertyValueString != null) {
                    try {
                        int propertyValueInt = Integer.decode(propertyValueString).intValue();
                        boolean result = entry.getValue().set(propertyValueInt);
                        if (!result) {
                            Log.e("GnssLocationProvider", "Unable to set " + propertyName);
                        }
                    } catch (NumberFormatException e2) {
                        Log.e("GnssLocationProvider", "unable to parse propertyName: " + propertyValueString);
                    }
                }
            }
        } else if (DEBUG) {
            Log.d("GnssLocationProvider", "Skipped configuration update because GNSS configuration in GPS HAL is not supported");
        }
        String suplESProperty = this.mProperties.getProperty("SUPL_ES");
        if (suplESProperty != null) {
            try {
                boolean z = true;
                if (Integer.parseInt(suplESProperty) != 1) {
                    z = false;
                }
                this.mSuplEsEnabled = z;
            } catch (NumberFormatException e3) {
                Log.e("GnssLocationProvider", "unable to parse SUPL_ES: " + suplESProperty);
            }
        }
        String emergencyExtensionSecondsString = properties.getProperty("ES_EXTENSION_SEC", "0");
        try {
            int emergencyExtensionSeconds = Integer.parseInt(emergencyExtensionSecondsString);
            this.mNIHandler.setEmergencyExtensionSeconds(emergencyExtensionSeconds);
        } catch (NumberFormatException e4) {
            Log.e("GnssLocationProvider", "unable to parse ES_EXTENSION_SEC: " + emergencyExtensionSecondsString);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.location.GnssLocationProvider$6  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass6 extends HashMap<String, SetCarrierProperty> {
        AnonymousClass6() {
            put("SUPL_VER", new SetCarrierProperty() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6$d34_RfOwt4eW2WTSkMsS8UoXSqY
                @Override // com.android.server.location.GnssLocationProvider.SetCarrierProperty
                public final boolean set(int i) {
                    boolean native_set_supl_version;
                    native_set_supl_version = GnssLocationProvider.native_set_supl_version(i);
                    return native_set_supl_version;
                }
            });
            put("SUPL_MODE", new SetCarrierProperty() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6$7ITcPSS3RLwdJLvqPT1qDZbuYgU
                @Override // com.android.server.location.GnssLocationProvider.SetCarrierProperty
                public final boolean set(int i) {
                    boolean native_set_supl_mode;
                    native_set_supl_mode = GnssLocationProvider.native_set_supl_mode(i);
                    return native_set_supl_mode;
                }
            });
            put("SUPL_ES", new SetCarrierProperty() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6$pJxRP_yDkUU0yl--Fw431I8fN70
                @Override // com.android.server.location.GnssLocationProvider.SetCarrierProperty
                public final boolean set(int i) {
                    boolean native_set_supl_es;
                    native_set_supl_es = GnssLocationProvider.native_set_supl_es(i);
                    return native_set_supl_es;
                }
            });
            put("LPP_PROFILE", new SetCarrierProperty() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6$vt8zMIL_RIFwKcgd1rz4Y33NVyk
                @Override // com.android.server.location.GnssLocationProvider.SetCarrierProperty
                public final boolean set(int i) {
                    boolean native_set_lpp_profile;
                    native_set_lpp_profile = GnssLocationProvider.native_set_lpp_profile(i);
                    return native_set_lpp_profile;
                }
            });
            put("A_GLONASS_POS_PROTOCOL_SELECT", new SetCarrierProperty() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6$fIEuYdSEFZVtEQQ5H4O-bTmj-LE
                @Override // com.android.server.location.GnssLocationProvider.SetCarrierProperty
                public final boolean set(int i) {
                    boolean native_set_gnss_pos_protocol_select;
                    native_set_gnss_pos_protocol_select = GnssLocationProvider.native_set_gnss_pos_protocol_select(i);
                    return native_set_gnss_pos_protocol_select;
                }
            });
            put("USE_EMERGENCY_PDN_FOR_EMERGENCY_SUPL", new SetCarrierProperty() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6$M4Zfb6dp_EFsOdGGju4tOPs-lc4
                @Override // com.android.server.location.GnssLocationProvider.SetCarrierProperty
                public final boolean set(int i) {
                    boolean native_set_emergency_supl_pdn;
                    native_set_emergency_supl_pdn = GnssLocationProvider.native_set_emergency_supl_pdn(i);
                    return native_set_emergency_supl_pdn;
                }
            });
            put("GPS_LOCK", new SetCarrierProperty() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6$0TBIDASC8cGFJxhCk2blveu19LI
                @Override // com.android.server.location.GnssLocationProvider.SetCarrierProperty
                public final boolean set(int i) {
                    boolean native_set_gps_lock;
                    native_set_gps_lock = GnssLocationProvider.native_set_gps_lock(i);
                    return native_set_gps_lock;
                }
            });
        }
    }

    private void loadPropertiesFromResource(Context context, Properties properties) {
        String[] configValues = context.getResources().getStringArray(17236013);
        for (String item : configValues) {
            if (DEBUG) {
                Log.d("GnssLocationProvider", "GpsParamsResource: " + item);
            }
            int index = item.indexOf("=");
            if (index > 0 && index + 1 < item.length()) {
                String key = item.substring(0, index);
                String value = item.substring(index + 1);
                properties.setProperty(key.trim().toUpperCase(), value);
            } else {
                Log.w("GnssLocationProvider", "malformed contents: " + item);
            }
        }
    }

    private boolean loadPropertiesFromFile(String filename, Properties properties) {
        try {
            File file = new File(filename);
            FileInputStream stream = new FileInputStream(file);
            properties.load(stream);
            IoUtils.closeQuietly(stream);
            return true;
        } catch (IOException e) {
            if (DEBUG) {
                Log.d("GnssLocationProvider", "Could not open GPS configuration file " + filename);
                return false;
            }
            return false;
        }
    }

    public GnssLocationProvider(Context context, ILocationManager ilocationManager, Looper looper) {
        this.mContext = context;
        this.mILocationManager = ilocationManager;
        this.mSendMessageArray.put(17, new AtomicInteger(0));
        this.mSendMessageArray.put(18, new AtomicInteger(0));
        this.mHandleMessageArray = new SparseArray<>(3);
        this.mHandleMessageArray.put(17, new AtomicInteger(0));
        this.mHandleMessageArray.put(18, new AtomicInteger(0));
        this.mPowerManager = (PowerManager) this.mContext.getSystemService("power");
        this.mWakeLock = this.mPowerManager.newWakeLock(1, "GnssLocationProvider");
        this.mWakeLock.setReferenceCounted(true);
        this.mDownloadXtraWakeLock = this.mPowerManager.newWakeLock(1, DOWNLOAD_EXTRA_WAKELOCK_KEY);
        this.mDownloadXtraWakeLock.setReferenceCounted(true);
        this.mAlarmManager = (AlarmManager) this.mContext.getSystemService("alarm");
        this.mWakeupIntent = PendingIntent.getBroadcast(this.mContext, 0, new Intent(ALARM_WAKEUP), 0);
        this.mTimeoutIntent = PendingIntent.getBroadcast(this.mContext, 0, new Intent(ALARM_TIMEOUT), 0);
        this.mConnMgr = (ConnectivityManager) context.getSystemService("connectivity");
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService("batterystats"));
        this.mHandler = new ProviderHandler(looper);
        this.mProperties = new Properties();
        this.mNIHandler = new GpsNetInitiatedHandler(context, this.mNetInitiatedListener, this.mSuplEsEnabled);
        sendMessage(13, 0, null);
        this.mListenerHelper = new GnssStatusListenerHelper(this.mHandler) { // from class: com.android.server.location.GnssLocationProvider.7
            @Override // com.android.server.location.RemoteListenerHelper
            protected boolean isAvailableInPlatform() {
                return GnssLocationProvider.isSupported();
            }

            @Override // com.android.server.location.RemoteListenerHelper
            protected boolean isGpsEnabled() {
                return GnssLocationProvider.this.isEnabled();
            }
        };
        this.mGnssMeasurementsProvider = new GnssMeasurementsProvider(this.mContext, this.mHandler) { // from class: com.android.server.location.GnssLocationProvider.8
            @Override // com.android.server.location.RemoteListenerHelper
            protected boolean isGpsEnabled() {
                return GnssLocationProvider.this.isEnabled();
            }
        };
        this.mGnssNavigationMessageProvider = new GnssNavigationMessageProvider(this.mHandler) { // from class: com.android.server.location.GnssLocationProvider.9
            @Override // com.android.server.location.RemoteListenerHelper
            protected boolean isGpsEnabled() {
                return GnssLocationProvider.this.isEnabled();
            }
        };
        this.mGnssMetrics = new GnssMetrics(this.mBatteryStats);
        this.mNtpTimeHelper = new NtpTimeHelper(this.mContext, looper, this);
        this.mGnssSatelliteBlacklistHelper = new GnssSatelliteBlacklistHelper(this.mContext, looper, this);
        Handler handler = this.mHandler;
        final GnssSatelliteBlacklistHelper gnssSatelliteBlacklistHelper = this.mGnssSatelliteBlacklistHelper;
        Objects.requireNonNull(gnssSatelliteBlacklistHelper);
        handler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$5U-_NhZgxqnYDZhpyacq4qBxh8k
            @Override // java.lang.Runnable
            public final void run() {
                GnssSatelliteBlacklistHelper.this.updateSatelliteBlacklist();
            }
        });
        this.mGnssBatchingProvider = new GnssBatchingProvider();
        this.mGnssGeofenceProvider = new GnssGeofenceProvider();
    }

    @Override // com.android.server.location.LocationProviderInterface
    public String getName() {
        return "gps";
    }

    @Override // com.android.server.location.LocationProviderInterface
    public ProviderProperties getProperties() {
        return PROPERTIES;
    }

    @Override // com.android.server.location.NtpTimeHelper.InjectNtpTimeCallback
    public void injectTime(long time, long timeReference, int uncertainty) {
        native_inject_time(time, timeReference, uncertainty);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUpdateNetworkState(Network network) {
        int i;
        NetworkInfo info = this.mConnMgr.getNetworkInfo(network);
        boolean networkAvailable = false;
        boolean isConnected = false;
        int type = -1;
        boolean isRoaming = false;
        String apnName = null;
        if (info != null) {
            networkAvailable = info.isAvailable() && TelephonyManager.getDefault().getDataEnabled();
            isConnected = info.isConnected();
            type = info.getType();
            isRoaming = info.isRoaming();
            apnName = info.getExtraInfo();
        }
        boolean networkAvailable2 = networkAvailable;
        boolean isConnected2 = isConnected;
        int type2 = type;
        boolean isRoaming2 = isRoaming;
        String apnName2 = apnName;
        if (DEBUG) {
            String message = String.format("UpdateNetworkState, state=%s, connected=%s, info=%s, capabilities=%S", agpsDataConnStateAsString(), Boolean.valueOf(isConnected2), info, this.mConnMgr.getNetworkCapabilities(network));
            Log.d("GnssLocationProvider", message);
        }
        if (native_is_agps_ril_supported()) {
            String defaultApn = getSelectedApn();
            if (defaultApn == null) {
                defaultApn = "dummy-apn";
            }
            i = 2;
            native_update_network_state(isConnected2, type2, isRoaming2, networkAvailable2, apnName2, defaultApn);
        } else {
            i = 2;
            if (DEBUG) {
                Log.d("GnssLocationProvider", "Skipped network state update because GPS HAL AGPS-RIL is not  supported");
            }
        }
        if (this.mAGpsDataConnectionState == 1) {
            if (isConnected2) {
                if (apnName2 == null) {
                    apnName2 = "dummy-apn";
                }
                String apnName3 = apnName2;
                int apnIpType = getApnIpType(apnName3);
                setRouting();
                if (DEBUG) {
                    Object[] objArr = new Object[i];
                    objArr[0] = apnName3;
                    objArr[1] = Integer.valueOf(apnIpType);
                    String message2 = String.format("native_agps_data_conn_open: mAgpsApn=%s, mApnIpType=%s", objArr);
                    Log.d("GnssLocationProvider", message2);
                }
                native_agps_data_conn_open(apnName3, apnIpType);
                this.mAGpsDataConnectionState = i;
                return;
            }
            handleReleaseSuplConnection(5);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRequestSuplConnection(InetAddress address) {
        if (DEBUG) {
            String message = String.format("requestSuplConnection, state=%s, address=%s", agpsDataConnStateAsString(), address);
            Log.d("GnssLocationProvider", message);
        }
        if (this.mAGpsDataConnectionState != 0) {
            return;
        }
        this.mAGpsDataConnectionIpAddr = address;
        this.mAGpsDataConnectionState = 1;
        NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(0);
        requestBuilder.addCapability(1);
        NetworkRequest request = requestBuilder.build();
        this.mConnMgr.requestNetwork(request, this.mSuplConnectivityCallback);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReleaseSuplConnection(int agpsDataConnStatus) {
        if (DEBUG) {
            String message = String.format("releaseSuplConnection, state=%s, status=%s", agpsDataConnStateAsString(), agpsDataConnStatusAsString(agpsDataConnStatus));
            Log.d("GnssLocationProvider", message);
        }
        if (this.mAGpsDataConnectionState == 0) {
            return;
        }
        this.mAGpsDataConnectionState = 0;
        this.mConnMgr.unregisterNetworkCallback(this.mSuplConnectivityCallback);
        if (agpsDataConnStatus == 2) {
            native_agps_data_conn_closed();
        } else if (agpsDataConnStatus == 5) {
            native_agps_data_conn_failed();
        } else {
            Log.e("GnssLocationProvider", "Invalid status to release SUPL connection: " + agpsDataConnStatus);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRequestLocation(boolean independentFromGnss) {
        String provider;
        LocationChangeListener locationListener;
        if (isRequestLocationRateLimited()) {
            if (DEBUG) {
                Log.d("GnssLocationProvider", "RequestLocation is denied due to too frequent requests.");
                return;
            }
            return;
        }
        ContentResolver resolver = this.mContext.getContentResolver();
        long durationMillis = Settings.Global.getLong(resolver, "gnss_hal_location_request_duration_millis", JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        if (durationMillis == 0) {
            Log.i("GnssLocationProvider", "GNSS HAL location request is disabled by Settings.");
            return;
        }
        final LocationManager locationManager = (LocationManager) this.mContext.getSystemService("location");
        if (independentFromGnss) {
            provider = "network";
            locationListener = this.mNetworkLocationListener;
        } else {
            provider = "fused";
            locationListener = this.mFusedLocationListener;
        }
        final String provider2 = provider;
        final LocationChangeListener locationListener2 = locationListener;
        Log.i("GnssLocationProvider", String.format("GNSS HAL Requesting location updates from %s provider for %d millis.", provider2, Long.valueOf(durationMillis)));
        try {
            locationManager.requestLocationUpdates(provider2, 1000L, 0.0f, locationListener2, this.mHandler.getLooper());
            locationListener2.numLocationUpdateRequest++;
            this.mHandler.postDelayed(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$oV78CWPlpzb195CgVgv-_YipNWw
                @Override // java.lang.Runnable
                public final void run() {
                    GnssLocationProvider.lambda$handleRequestLocation$1(GnssLocationProvider.LocationChangeListener.this, provider2, locationManager);
                }
            }, durationMillis);
        } catch (IllegalArgumentException e) {
            Log.w("GnssLocationProvider", "Unable to request location.", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$handleRequestLocation$1(LocationChangeListener locationListener, String provider, LocationManager locationManager) {
        int i = locationListener.numLocationUpdateRequest - 1;
        locationListener.numLocationUpdateRequest = i;
        if (i == 0) {
            Log.i("GnssLocationProvider", String.format("Removing location updates from %s provider.", provider));
            locationManager.removeUpdates(locationListener);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void injectBestLocation(Location location) {
        int gnssLocationFlags = (location.hasAltitude() ? 2 : 0) | 1 | (location.hasSpeed() ? 4 : 0) | (location.hasBearing() ? 8 : 0) | (location.hasAccuracy() ? 16 : 0) | (location.hasVerticalAccuracy() ? 32 : 0) | (location.hasSpeedAccuracy() ? 64 : 0) | (location.hasBearingAccuracy() ? 128 : 0);
        double latitudeDegrees = location.getLatitude();
        double longitudeDegrees = location.getLongitude();
        double altitudeMeters = location.getAltitude();
        float speedMetersPerSec = location.getSpeed();
        float bearingDegrees = location.getBearing();
        float horizontalAccuracyMeters = location.getAccuracy();
        float verticalAccuracyMeters = location.getVerticalAccuracyMeters();
        float speedAccuracyMetersPerSecond = location.getSpeedAccuracyMetersPerSecond();
        float bearingAccuracyDegrees = location.getBearingAccuracyDegrees();
        long timestamp = location.getTime();
        native_inject_best_location(gnssLocationFlags, latitudeDegrees, longitudeDegrees, altitudeMeters, speedMetersPerSec, bearingDegrees, horizontalAccuracyMeters, verticalAccuracyMeters, speedAccuracyMetersPerSecond, bearingAccuracyDegrees, timestamp);
    }

    private boolean isRequestLocationRateLimited() {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDownloadXtraData() {
        if (!this.mSupportsXtra) {
            Log.d("GnssLocationProvider", "handleDownloadXtraData() called when Xtra not supported");
        } else if (this.mDownloadXtraDataPending == 1) {
        } else {
            if (!isDataNetworkConnected()) {
                this.mDownloadXtraDataPending = 0;
                return;
            }
            this.mDownloadXtraDataPending = 1;
            this.mDownloadXtraWakeLock.acquire(60000L);
            Log.i("GnssLocationProvider", "WakeLock acquired by handleDownloadXtraData()");
            AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() { // from class: com.android.server.location.GnssLocationProvider.10
                @Override // java.lang.Runnable
                public void run() {
                    GpsXtraDownloader xtraDownloader = new GpsXtraDownloader(GnssLocationProvider.this.mProperties);
                    byte[] data = xtraDownloader.downloadXtraData();
                    if (data != null) {
                        if (GnssLocationProvider.DEBUG) {
                            Log.d("GnssLocationProvider", "calling native_inject_xtra_data");
                        }
                        GnssLocationProvider.this.native_inject_xtra_data(data, data.length);
                        GnssLocationProvider.this.mXtraBackOff.reset();
                    }
                    GnssLocationProvider.this.sendMessage(11, 0, null);
                    if (data == null) {
                        GnssLocationProvider.this.mHandler.sendEmptyMessageDelayed(6, GnssLocationProvider.this.mXtraBackOff.nextBackoffMillis());
                    }
                    synchronized (GnssLocationProvider.this.mLock) {
                        if (GnssLocationProvider.this.mDownloadXtraWakeLock.isHeld()) {
                            try {
                                GnssLocationProvider.this.mDownloadXtraWakeLock.release();
                                if (GnssLocationProvider.DEBUG) {
                                    Log.d("GnssLocationProvider", "WakeLock released by handleDownloadXtraData()");
                                }
                            } catch (Exception e) {
                                Log.i("GnssLocationProvider", "Wakelock timeout & release race exception in handleDownloadXtraData()", e);
                            }
                        } else {
                            Log.e("GnssLocationProvider", "WakeLock expired before release in handleDownloadXtraData()");
                        }
                    }
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUpdateLocation(Location location) {
        if (location.hasAccuracy()) {
            native_inject_location(location.getLatitude(), location.getLongitude(), location.getAccuracy());
        }
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void enable() {
        synchronized (this.mLock) {
            if (this.mEnabled) {
                return;
            }
            this.mEnabled = true;
            sendMessage(2, 1, null);
        }
    }

    private void setSuplHostPort(String hostString, String portString) {
        if (hostString != null) {
            this.mSuplServerHost = hostString;
        }
        if (portString != null) {
            try {
                this.mSuplServerPort = Integer.parseInt(portString);
            } catch (NumberFormatException e) {
                Log.e("GnssLocationProvider", "unable to parse SUPL_PORT: " + portString);
            }
        }
        if (this.mSuplServerHost != null && this.mSuplServerPort > 0 && this.mSuplServerPort <= 65535) {
            native_set_agps_server(1, this.mSuplServerHost, this.mSuplServerPort);
        }
    }

    private int getSuplMode(Properties properties, boolean agpsEnabled, boolean singleShot) {
        if (agpsEnabled) {
            String modeString = properties.getProperty("SUPL_MODE");
            int suplMode = 0;
            if (!TextUtils.isEmpty(modeString)) {
                try {
                    suplMode = Integer.parseInt(modeString);
                } catch (NumberFormatException e) {
                    Log.e("GnssLocationProvider", "unable to parse SUPL_MODE: " + modeString);
                    return 0;
                }
            }
            if (hasCapability(2) && (suplMode & 1) != 0) {
                return 1;
            }
            if (singleShot && hasCapability(4) && (suplMode & 2) != 0) {
                return 2;
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleEnable() {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "handleEnable");
        }
        boolean enabled = native_init();
        if (enabled) {
            this.mSupportsXtra = native_supports_xtra();
            if (this.mSuplServerHost != null) {
                native_set_agps_server(1, this.mSuplServerHost, this.mSuplServerPort);
            }
            if (this.mC2KServerHost != null) {
                native_set_agps_server(2, this.mC2KServerHost, this.mC2KServerPort);
            }
            this.mGnssMeasurementsProvider.onGpsEnabledChanged();
            this.mGnssNavigationMessageProvider.onGpsEnabledChanged();
            this.mGnssBatchingProvider.enable();
            return;
        }
        synchronized (this.mLock) {
            this.mEnabled = false;
        }
        Log.w("GnssLocationProvider", "Failed to enable location provider");
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void disable() {
        synchronized (this.mLock) {
            if (this.mEnabled) {
                this.mEnabled = false;
                sendMessage(2, 0, null);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDisable() {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "handleDisable");
        }
        updateClientUids(new WorkSource());
        stopNavigating();
        this.mAlarmManager.cancel(this.mWakeupIntent);
        this.mAlarmManager.cancel(this.mTimeoutIntent);
        this.mGnssBatchingProvider.disable();
        native_cleanup();
        this.mGnssMeasurementsProvider.onGpsEnabledChanged();
        this.mGnssNavigationMessageProvider.onGpsEnabledChanged();
    }

    @Override // com.android.server.location.LocationProviderInterface
    public boolean isEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mEnabled;
        }
        return z;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public int getStatus(Bundle extras) {
        this.mLocationExtras.setBundle(extras);
        return this.mStatus;
    }

    private void updateStatus(int status) {
        if (status != this.mStatus) {
            this.mStatus = status;
            this.mStatusUpdateTime = SystemClock.elapsedRealtime();
        }
    }

    @Override // com.android.server.location.LocationProviderInterface
    public long getStatusUpdateTime() {
        return this.mStatusUpdateTime;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void setRequest(ProviderRequest request, WorkSource source) {
        sendMessage(3, 0, new GpsRequest(request, source));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSetRequest(ProviderRequest request, WorkSource source) {
        this.mProviderRequest = request;
        this.mWorkSource = source;
        updateRequirements();
    }

    private void updateRequirements() {
        if (this.mProviderRequest == null || this.mWorkSource == null) {
            return;
        }
        boolean singleShot = false;
        if (this.mProviderRequest.locationRequests != null && this.mProviderRequest.locationRequests.size() > 0) {
            singleShot = true;
            for (LocationRequest lr : this.mProviderRequest.locationRequests) {
                if (lr.getNumUpdates() != 1) {
                    singleShot = false;
                }
            }
        }
        if (DEBUG) {
            Log.d("GnssLocationProvider", "setRequest " + this.mProviderRequest);
        }
        if (this.mProviderRequest.reportLocation && !this.mDisableGps && isEnabled()) {
            updateClientUids(this.mWorkSource);
            this.mFixInterval = (int) this.mProviderRequest.interval;
            this.mLowPowerMode = this.mProviderRequest.lowPowerMode;
            if (this.mFixInterval != this.mProviderRequest.interval) {
                Log.w("GnssLocationProvider", "interval overflow: " + this.mProviderRequest.interval);
                this.mFixInterval = Integer.MAX_VALUE;
            }
            if (this.mStarted && hasCapability(1)) {
                if (!native_set_position_mode(this.mPositionMode, 0, this.mFixInterval, 0, 0, this.mLowPowerMode)) {
                    Log.e("GnssLocationProvider", "set_position_mode failed in updateRequirements");
                    return;
                }
                return;
            } else if (!this.mStarted) {
                startNavigating(singleShot);
                return;
            } else {
                this.mAlarmManager.cancel(this.mTimeoutIntent);
                if (this.mFixInterval >= NO_FIX_TIMEOUT) {
                    this.mAlarmManager.set(2, SystemClock.elapsedRealtime() + 60000, this.mTimeoutIntent);
                    return;
                }
                return;
            }
        }
        updateClientUids(new WorkSource());
        stopNavigating();
        this.mAlarmManager.cancel(this.mWakeupIntent);
        this.mAlarmManager.cancel(this.mTimeoutIntent);
    }

    private void updateClientUids(WorkSource source) {
        if (source.equals(this.mClientSource)) {
            return;
        }
        try {
            this.mBatteryStats.noteGpsChanged(this.mClientSource, source);
        } catch (RemoteException e) {
            Log.w("GnssLocationProvider", "RemoteException", e);
        }
        List<WorkSource.WorkChain>[] diffs = WorkSource.diffChains(this.mClientSource, source);
        if (diffs != null) {
            List<WorkSource.WorkChain> newChains = diffs[0];
            List<WorkSource.WorkChain> goneChains = diffs[1];
            if (newChains != null) {
                for (int i = 0; i < newChains.size(); i++) {
                    WorkSource.WorkChain newChain = newChains.get(i);
                    this.mAppOps.startOpNoThrow(2, newChain.getAttributionUid(), newChain.getAttributionTag());
                }
            }
            if (goneChains != null) {
                for (int i2 = 0; i2 < goneChains.size(); i2++) {
                    WorkSource.WorkChain goneChain = goneChains.get(i2);
                    this.mAppOps.finishOp(2, goneChain.getAttributionUid(), goneChain.getAttributionTag());
                }
            }
            this.mClientSource.transferWorkChains(source);
        }
        WorkSource[] changes = this.mClientSource.setReturningDiffs(source);
        if (changes != null) {
            WorkSource newWork = changes[0];
            WorkSource goneWork = changes[1];
            if (newWork != null) {
                for (int i3 = 0; i3 < newWork.size(); i3++) {
                    this.mAppOps.startOpNoThrow(2, newWork.get(i3), newWork.getName(i3));
                }
            }
            if (goneWork != null) {
                for (int i4 = 0; i4 < goneWork.size(); i4++) {
                    this.mAppOps.finishOp(2, goneWork.get(i4), goneWork.getName(i4));
                }
            }
        }
    }

    @Override // com.android.server.location.LocationProviderInterface
    public boolean sendExtraCommand(String command, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        boolean result = false;
        try {
            if ("delete_aiding_data".equals(command)) {
                result = deleteAidingData(extras);
            } else if ("force_time_injection".equals(command)) {
                requestUtcTime();
                result = true;
            } else if ("force_xtra_injection".equals(command)) {
                if (this.mSupportsXtra) {
                    xtraDownloadRequest();
                    result = true;
                }
            } else {
                Log.w("GnssLocationProvider", "sendExtraCommand: unknown command " + command);
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean deleteAidingData(Bundle extras) {
        int flags;
        if (extras == null) {
            flags = NetworkConstants.ARP_HWTYPE_RESERVED_HI;
        } else {
            flags = extras.getBoolean("ephemeris") ? 0 | 1 : 0;
            if (extras.getBoolean("almanac")) {
                flags |= 2;
            }
            if (extras.getBoolean("position")) {
                flags |= 4;
            }
            if (extras.getBoolean("time")) {
                flags |= 8;
            }
            if (extras.getBoolean("iono")) {
                flags |= 16;
            }
            if (extras.getBoolean("utc")) {
                flags |= 32;
            }
            if (extras.getBoolean("health")) {
                flags |= 64;
            }
            if (extras.getBoolean("svdir")) {
                flags |= 128;
            }
            if (extras.getBoolean("svsteer")) {
                flags |= 256;
            }
            if (extras.getBoolean("sadata")) {
                flags |= 512;
            }
            if (extras.getBoolean("rti")) {
                flags |= 1024;
            }
            if (extras.getBoolean("celldb-info")) {
                flags |= 32768;
            }
            if (extras.getBoolean("all")) {
                flags |= NetworkConstants.ARP_HWTYPE_RESERVED_HI;
            }
        }
        if (flags != 0) {
            native_delete_aiding_data(flags);
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startNavigating(boolean singleShot) {
        boolean agpsEnabled;
        String mode;
        if (!this.mStarted) {
            if (DEBUG) {
                Log.d("GnssLocationProvider", "startNavigating, singleShot is " + singleShot);
            }
            this.mTimeToFirstFix = 0;
            this.mLastFixTime = 0L;
            this.mStarted = true;
            this.mSingleShot = singleShot;
            this.mPositionMode = 0;
            if (this.mItarSpeedLimitExceeded) {
                Log.i("GnssLocationProvider", "startNavigating with ITAR limit in place. Output limited  until slow enough speed reported.");
            }
            if (Settings.Global.getInt(this.mContext.getContentResolver(), "assisted_gps_enabled", 1) == 0) {
                agpsEnabled = false;
            } else {
                agpsEnabled = true;
            }
            this.mPositionMode = getSuplMode(this.mProperties, agpsEnabled, singleShot);
            if (DEBUG) {
                switch (this.mPositionMode) {
                    case 0:
                        mode = "standalone";
                        break;
                    case 1:
                        mode = "MS_BASED";
                        break;
                    case 2:
                        mode = "MS_ASSISTED";
                        break;
                    default:
                        mode = UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
                        break;
                }
                Log.d("GnssLocationProvider", "setting position_mode to " + mode);
            }
            int interval = hasCapability(1) ? this.mFixInterval : 1000;
            this.mLowPowerMode = this.mProviderRequest.lowPowerMode;
            if (!native_set_position_mode(this.mPositionMode, 0, interval, 0, 0, this.mLowPowerMode)) {
                this.mStarted = false;
                Log.e("GnssLocationProvider", "set_position_mode failed in startNavigating()");
            } else if (!native_start()) {
                this.mStarted = false;
                Log.e("GnssLocationProvider", "native_start failed in startNavigating()");
            } else {
                updateStatus(1);
                this.mLocationExtras.reset();
                this.mFixRequestTime = SystemClock.elapsedRealtime();
                if (!hasCapability(1) && this.mFixInterval >= NO_FIX_TIMEOUT) {
                    this.mAlarmManager.set(2, SystemClock.elapsedRealtime() + 60000, this.mTimeoutIntent);
                }
            }
        }
    }

    private void stopNavigating() {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "stopNavigating");
        }
        if (this.mStarted) {
            this.mStarted = false;
            this.mSingleShot = false;
            native_stop();
            this.mLastFixTime = 0L;
            updateStatus(1);
            this.mLocationExtras.reset();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hibernate() {
        stopNavigating();
        this.mAlarmManager.cancel(this.mTimeoutIntent);
        this.mAlarmManager.cancel(this.mWakeupIntent);
        long now = SystemClock.elapsedRealtime();
        this.mAlarmManager.set(2, this.mFixInterval + now, this.mWakeupIntent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean hasCapability(int capability) {
        return (this.mEngineCapabilities & capability) != 0;
    }

    private void reportLocation(boolean hasLatLong, Location location) {
        sendMessage(17, hasLatLong ? 1 : 0, location);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReportLocation(boolean hasLatLong, Location location) {
        if (location.hasSpeed()) {
            this.mItarSpeedLimitExceeded = location.getSpeed() > ITAR_SPEED_LIMIT_METERS_PER_SECOND;
        }
        if (this.mItarSpeedLimitExceeded) {
            Log.i("GnssLocationProvider", "Hal reported a speed in excess of ITAR limit.  GPS/GNSS Navigation output blocked.");
            if (this.mStarted) {
                this.mGnssMetrics.logReceivedLocationStatus(false);
                return;
            }
            return;
        }
        if (VERBOSE) {
            Log.v("GnssLocationProvider", "reportLocation " + location.toString());
        }
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setExtras(this.mLocationExtras.getBundle());
        try {
            this.mILocationManager.reportLocation(location, false);
        } catch (RemoteException e) {
            Log.e("GnssLocationProvider", "RemoteException calling reportLocation");
        }
        if (this.mStarted) {
            this.mGnssMetrics.logReceivedLocationStatus(hasLatLong);
            if (hasLatLong) {
                if (location.hasAccuracy()) {
                    this.mGnssMetrics.logPositionAccuracyMeters(location.getAccuracy());
                }
                if (this.mTimeToFirstFix > 0) {
                    int timeBetweenFixes = (int) (SystemClock.elapsedRealtime() - this.mLastFixTime);
                    this.mGnssMetrics.logMissedReports(this.mFixInterval, timeBetweenFixes);
                }
            }
        }
        this.mLastFixTime = SystemClock.elapsedRealtime();
        if (this.mTimeToFirstFix == 0 && hasLatLong) {
            this.mTimeToFirstFix = (int) (this.mLastFixTime - this.mFixRequestTime);
            if (DEBUG) {
                Log.d("GnssLocationProvider", "TTFF: " + this.mTimeToFirstFix);
            }
            if (this.mStarted) {
                this.mGnssMetrics.logTimeToFirstFixMilliSecs(this.mTimeToFirstFix);
            }
            this.mListenerHelper.onFirstFix(this.mTimeToFirstFix);
        }
        if (this.mSingleShot) {
            stopNavigating();
        }
        if (this.mStarted && this.mStatus != 2) {
            if (!hasCapability(1) && this.mFixInterval < NO_FIX_TIMEOUT) {
                this.mAlarmManager.cancel(this.mTimeoutIntent);
            }
            Intent intent = new Intent("android.location.GPS_FIX_CHANGE");
            intent.putExtra("enabled", true);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            updateStatus(2);
        }
        if (!hasCapability(1) && this.mStarted && this.mFixInterval > 10000) {
            if (DEBUG) {
                Log.d("GnssLocationProvider", "got fix, hibernating");
            }
            hibernate();
        }
    }

    private void reportStatus(int status) {
        if (DEBUG) {
            Log.v("GnssLocationProvider", "reportStatus status: " + status);
        }
        boolean wasNavigating = this.mNavigating;
        switch (status) {
            case 1:
                this.mNavigating = true;
                this.mEngineOn = true;
                break;
            case 2:
                this.mNavigating = false;
                break;
            case 3:
                this.mEngineOn = true;
                break;
            case 4:
                this.mEngineOn = false;
                this.mNavigating = false;
                break;
        }
        if (wasNavigating != this.mNavigating) {
            this.mListenerHelper.onStatusChanged(this.mNavigating);
            Intent intent = new Intent("android.location.GPS_ENABLED_CHANGE");
            intent.putExtra("enabled", this.mNavigating);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class SvStatusInfo {
        public float[] mCn0s;
        public float[] mSvAzimuths;
        public float[] mSvCarrierFreqs;
        public int mSvCount;
        public float[] mSvElevations;
        public int[] mSvidWithFlags;

        private SvStatusInfo() {
        }
    }

    private void reportSvStatus(int svCount, int[] svidWithFlags, float[] cn0s, float[] svElevations, float[] svAzimuths, float[] svCarrierFreqs) {
        SvStatusInfo svStatusInfo = new SvStatusInfo();
        svStatusInfo.mSvCount = svCount;
        svStatusInfo.mSvidWithFlags = svidWithFlags;
        svStatusInfo.mCn0s = cn0s;
        svStatusInfo.mSvElevations = svElevations;
        svStatusInfo.mSvAzimuths = svAzimuths;
        svStatusInfo.mSvCarrierFreqs = svCarrierFreqs;
        sendMessage(18, 0, svStatusInfo);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleReportSvStatus(SvStatusInfo info) {
        this.mListenerHelper.onSvStatusChanged(info.mSvCount, info.mSvidWithFlags, info.mCn0s, info.mSvElevations, info.mSvAzimuths, info.mSvCarrierFreqs);
        this.mGnssMetrics.logCn0(info.mCn0s, info.mSvCount);
        if (VERBOSE) {
            Log.v("GnssLocationProvider", "SV count: " + info.mSvCount);
        }
        int meanCn0 = 0;
        int maxCn0 = 0;
        int usedInFixCount = 0;
        for (int usedInFixCount2 = 0; usedInFixCount2 < info.mSvCount; usedInFixCount2++) {
            if ((info.mSvidWithFlags[usedInFixCount2] & 4) != 0) {
                usedInFixCount++;
                if (info.mCn0s[usedInFixCount2] > maxCn0) {
                    maxCn0 = (int) info.mCn0s[usedInFixCount2];
                }
                meanCn0 = (int) (meanCn0 + info.mCn0s[usedInFixCount2]);
            }
            if (VERBOSE) {
                StringBuilder sb = new StringBuilder();
                sb.append("svid: ");
                sb.append(info.mSvidWithFlags[usedInFixCount2] >> 8);
                sb.append(" cn0: ");
                sb.append(info.mCn0s[usedInFixCount2]);
                sb.append(" elev: ");
                sb.append(info.mSvElevations[usedInFixCount2]);
                sb.append(" azimuth: ");
                sb.append(info.mSvAzimuths[usedInFixCount2]);
                sb.append(" carrier frequency: ");
                sb.append(info.mSvCarrierFreqs[usedInFixCount2]);
                sb.append((1 & info.mSvidWithFlags[usedInFixCount2]) == 0 ? "  " : " E");
                sb.append((2 & info.mSvidWithFlags[usedInFixCount2]) == 0 ? "  " : " A");
                sb.append((info.mSvidWithFlags[usedInFixCount2] & 4) == 0 ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "U");
                sb.append((info.mSvidWithFlags[usedInFixCount2] & 8) == 0 ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "F");
                Log.v("GnssLocationProvider", sb.toString());
            }
        }
        if (usedInFixCount > 0) {
            meanCn0 /= usedInFixCount;
        }
        this.mLocationExtras.set(usedInFixCount, meanCn0, maxCn0);
        if (this.mNavigating && this.mStatus == 2 && this.mLastFixTime > 0 && SystemClock.elapsedRealtime() - this.mLastFixTime > JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY) {
            Intent intent = new Intent("android.location.GPS_FIX_CHANGE");
            intent.putExtra("enabled", false);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            updateStatus(1);
        }
    }

    private void reportAGpsStatus(int type, int status, byte[] ipaddr) {
        switch (status) {
            case 1:
                if (DEBUG) {
                    Log.d("GnssLocationProvider", "GPS_REQUEST_AGPS_DATA_CONN");
                }
                Log.v("GnssLocationProvider", "Received SUPL IP addr[]: " + Arrays.toString(ipaddr));
                InetAddress connectionIpAddress = null;
                if (ipaddr != null) {
                    try {
                        connectionIpAddress = InetAddress.getByAddress(ipaddr);
                        if (DEBUG) {
                            Log.d("GnssLocationProvider", "IP address converted to: " + connectionIpAddress);
                        }
                    } catch (UnknownHostException e) {
                        Log.e("GnssLocationProvider", "Bad IP Address: " + ipaddr, e);
                    }
                }
                sendMessage(14, 0, connectionIpAddress);
                return;
            case 2:
                if (DEBUG) {
                    Log.d("GnssLocationProvider", "GPS_RELEASE_AGPS_DATA_CONN");
                }
                releaseSuplConnection(2);
                return;
            case 3:
                if (DEBUG) {
                    Log.d("GnssLocationProvider", "GPS_AGPS_DATA_CONNECTED");
                    return;
                }
                return;
            case 4:
                if (DEBUG) {
                    Log.d("GnssLocationProvider", "GPS_AGPS_DATA_CONN_DONE");
                    return;
                }
                return;
            case 5:
                if (DEBUG) {
                    Log.d("GnssLocationProvider", "GPS_AGPS_DATA_CONN_FAILED");
                    return;
                }
                return;
            default:
                if (DEBUG) {
                    Log.d("GnssLocationProvider", "Received Unknown AGPS status: " + status);
                    return;
                }
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void releaseSuplConnection(int connStatus) {
        sendMessage(15, connStatus, null);
    }

    private void reportNmea(long timestamp) {
        if (!this.mItarSpeedLimitExceeded) {
            int length = native_read_nmea(this.mNmeaBuffer, this.mNmeaBuffer.length);
            String nmea = new String(this.mNmeaBuffer, 0, length);
            this.mListenerHelper.onNmeaReceived(timestamp, nmea);
        }
    }

    private void reportMeasurementData(final GnssMeasurementsEvent event) {
        if (!this.mItarSpeedLimitExceeded) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.location.GnssLocationProvider.11
                @Override // java.lang.Runnable
                public void run() {
                    GnssLocationProvider.this.mGnssMeasurementsProvider.onMeasurementsAvailable(event);
                }
            });
        }
    }

    private void reportNavigationMessage(final GnssNavigationMessage event) {
        if (!this.mItarSpeedLimitExceeded) {
            this.mHandler.post(new Runnable() { // from class: com.android.server.location.GnssLocationProvider.12
                @Override // java.lang.Runnable
                public void run() {
                    GnssLocationProvider.this.mGnssNavigationMessageProvider.onNavigationMessageAvailable(event);
                }
            });
        }
    }

    private void setEngineCapabilities(final int capabilities) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.GnssLocationProvider.13
            @Override // java.lang.Runnable
            public void run() {
                GnssLocationProvider.this.mEngineCapabilities = capabilities;
                if (GnssLocationProvider.this.hasCapability(16)) {
                    GnssLocationProvider.this.mNtpTimeHelper.enablePeriodicTimeInjection();
                    GnssLocationProvider.this.requestUtcTime();
                }
                GnssLocationProvider.this.mGnssMeasurementsProvider.onCapabilitiesUpdated(GnssLocationProvider.this.hasCapability(64));
                GnssLocationProvider.this.mGnssNavigationMessageProvider.onCapabilitiesUpdated(GnssLocationProvider.this.hasCapability(128));
                GnssLocationProvider.this.restartRequests();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void restartRequests() {
        Log.i("GnssLocationProvider", "restartRequests");
        restartLocationRequest();
        this.mGnssMeasurementsProvider.resumeIfStarted();
        this.mGnssNavigationMessageProvider.resumeIfStarted();
        this.mGnssBatchingProvider.resumeIfStarted();
        this.mGnssGeofenceProvider.resumeIfStarted();
    }

    private void restartLocationRequest() {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "restartLocationRequest");
        }
        this.mStarted = false;
        updateRequirements();
    }

    private void setGnssYearOfHardware(int yearOfHardware) {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "setGnssYearOfHardware called with " + yearOfHardware);
        }
        this.mHardwareYear = yearOfHardware;
    }

    private void setGnssHardwareModelName(String modelName) {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "setGnssModelName called with " + modelName);
        }
        this.mHardwareModelName = modelName;
    }

    private void reportGnssServiceDied() {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "reportGnssServiceDied");
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$DbwtCzCTIv9vxK6aWV22ONkgWSg
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.lambda$reportGnssServiceDied$2(GnssLocationProvider.this);
            }
        });
    }

    public static /* synthetic */ void lambda$reportGnssServiceDied$2(GnssLocationProvider gnssLocationProvider) {
        class_init_native();
        native_init_once();
        if (gnssLocationProvider.isEnabled()) {
            gnssLocationProvider.handleEnable();
            gnssLocationProvider.reloadGpsProperties(gnssLocationProvider.mContext, gnssLocationProvider.mProperties);
        }
    }

    public GnssSystemInfoProvider getGnssSystemInfoProvider() {
        return new GnssSystemInfoProvider() { // from class: com.android.server.location.GnssLocationProvider.14
            @Override // com.android.server.location.GnssLocationProvider.GnssSystemInfoProvider
            public int getGnssYearOfHardware() {
                return GnssLocationProvider.this.mHardwareYear;
            }

            @Override // com.android.server.location.GnssLocationProvider.GnssSystemInfoProvider
            public String getGnssHardwareModelName() {
                return GnssLocationProvider.this.mHardwareModelName;
            }
        };
    }

    public GnssBatchingProvider getGnssBatchingProvider() {
        return this.mGnssBatchingProvider;
    }

    public GnssMetricsProvider getGnssMetricsProvider() {
        return new GnssMetricsProvider() { // from class: com.android.server.location.GnssLocationProvider.15
            @Override // com.android.server.location.GnssLocationProvider.GnssMetricsProvider
            public String getGnssMetricsAsProtoString() {
                return GnssLocationProvider.this.mGnssMetrics.dumpGnssMetricsAsProtoString();
            }
        };
    }

    private void reportLocationBatch(Location[] locationArray) {
        List<Location> locations = new ArrayList<>(Arrays.asList(locationArray));
        if (DEBUG) {
            Log.d("GnssLocationProvider", "Location batch of size " + locationArray.length + " reported");
        }
        try {
            this.mILocationManager.reportLocationBatch(locations);
        } catch (RemoteException e) {
            Log.e("GnssLocationProvider", "RemoteException calling reportLocationBatch");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void xtraDownloadRequest() {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "xtraDownloadRequest");
        }
        sendMessage(6, 0, null);
    }

    private static int getGeofenceStatus(int status) {
        if (status != GPS_GEOFENCE_ERROR_GENERIC) {
            if (status != 0) {
                if (status != 100) {
                    switch (status) {
                        case GPS_GEOFENCE_ERROR_INVALID_TRANSITION /* -103 */:
                            return 4;
                        case GPS_GEOFENCE_ERROR_ID_UNKNOWN /* -102 */:
                            return 3;
                        case GPS_GEOFENCE_ERROR_ID_EXISTS /* -101 */:
                            return 2;
                        default:
                            return -1;
                    }
                }
                return 1;
            }
            return 0;
        }
        return 5;
    }

    private void reportGeofenceTransition(final int geofenceId, final Location location, final int transition, final long transitionTimestamp) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$4Do0xnhjzdvNA_aS_B3Zkp4KLGM
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.lambda$reportGeofenceTransition$3(GnssLocationProvider.this, geofenceId, location, transition, transitionTimestamp);
            }
        });
    }

    public static /* synthetic */ void lambda$reportGeofenceTransition$3(GnssLocationProvider gnssLocationProvider, int geofenceId, Location location, int transition, long transitionTimestamp) {
        if (gnssLocationProvider.mGeofenceHardwareImpl == null) {
            gnssLocationProvider.mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(gnssLocationProvider.mContext);
        }
        gnssLocationProvider.mGeofenceHardwareImpl.reportGeofenceTransition(geofenceId, location, transition, transitionTimestamp, 0, FusedBatchOptions.SourceTechnologies.GNSS);
    }

    private void reportGeofenceStatus(final int status, final Location location) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$EL4pL8gX10WekdNxDWcMifRL2FI
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.lambda$reportGeofenceStatus$4(GnssLocationProvider.this, status, location);
            }
        });
    }

    public static /* synthetic */ void lambda$reportGeofenceStatus$4(GnssLocationProvider gnssLocationProvider, int status, Location location) {
        if (gnssLocationProvider.mGeofenceHardwareImpl == null) {
            gnssLocationProvider.mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(gnssLocationProvider.mContext);
        }
        int monitorStatus = 1;
        if (status == 2) {
            monitorStatus = 0;
        }
        gnssLocationProvider.mGeofenceHardwareImpl.reportGeofenceMonitorStatus(0, monitorStatus, location, FusedBatchOptions.SourceTechnologies.GNSS);
    }

    private void reportGeofenceAddStatus(final int geofenceId, final int status) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$6aXuEJSjJQY5HYue8u76FyAkUuE
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.lambda$reportGeofenceAddStatus$5(GnssLocationProvider.this, geofenceId, status);
            }
        });
    }

    public static /* synthetic */ void lambda$reportGeofenceAddStatus$5(GnssLocationProvider gnssLocationProvider, int geofenceId, int status) {
        if (gnssLocationProvider.mGeofenceHardwareImpl == null) {
            gnssLocationProvider.mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(gnssLocationProvider.mContext);
        }
        gnssLocationProvider.mGeofenceHardwareImpl.reportGeofenceAddStatus(geofenceId, getGeofenceStatus(status));
    }

    private void reportGeofenceRemoveStatus(final int geofenceId, final int status) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$4-mDK9hpoeXV8YKmPvvgRI5lM_I
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.lambda$reportGeofenceRemoveStatus$6(GnssLocationProvider.this, geofenceId, status);
            }
        });
    }

    public static /* synthetic */ void lambda$reportGeofenceRemoveStatus$6(GnssLocationProvider gnssLocationProvider, int geofenceId, int status) {
        if (gnssLocationProvider.mGeofenceHardwareImpl == null) {
            gnssLocationProvider.mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(gnssLocationProvider.mContext);
        }
        gnssLocationProvider.mGeofenceHardwareImpl.reportGeofenceRemoveStatus(geofenceId, getGeofenceStatus(status));
    }

    private void reportGeofencePauseStatus(final int geofenceId, final int status) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$NoAxtlhaLrem9jUt55B6d87zhNk
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.lambda$reportGeofencePauseStatus$7(GnssLocationProvider.this, geofenceId, status);
            }
        });
    }

    public static /* synthetic */ void lambda$reportGeofencePauseStatus$7(GnssLocationProvider gnssLocationProvider, int geofenceId, int status) {
        if (gnssLocationProvider.mGeofenceHardwareImpl == null) {
            gnssLocationProvider.mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(gnssLocationProvider.mContext);
        }
        gnssLocationProvider.mGeofenceHardwareImpl.reportGeofencePauseStatus(geofenceId, getGeofenceStatus(status));
    }

    private void reportGeofenceResumeStatus(final int geofenceId, final int status) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssLocationProvider$g3-znpwQ1xH_MSL33zqQm7kjVUk
            @Override // java.lang.Runnable
            public final void run() {
                GnssLocationProvider.lambda$reportGeofenceResumeStatus$8(GnssLocationProvider.this, geofenceId, status);
            }
        });
    }

    public static /* synthetic */ void lambda$reportGeofenceResumeStatus$8(GnssLocationProvider gnssLocationProvider, int geofenceId, int status) {
        if (gnssLocationProvider.mGeofenceHardwareImpl == null) {
            gnssLocationProvider.mGeofenceHardwareImpl = GeofenceHardwareImpl.getInstance(gnssLocationProvider.mContext);
        }
        gnssLocationProvider.mGeofenceHardwareImpl.reportGeofenceResumeStatus(geofenceId, getGeofenceStatus(status));
    }

    public INetInitiatedListener getNetInitiatedListener() {
        return this.mNetInitiatedListener;
    }

    public void reportNiNotification(int notificationId, int niType, int notifyFlags, int timeout, int defaultResponse, String requestorId, String text, int requestorIdEncoding, int textEncoding) {
        Log.i("GnssLocationProvider", "reportNiNotification: entered");
        Log.i("GnssLocationProvider", "notificationId: " + notificationId + ", niType: " + niType + ", notifyFlags: " + notifyFlags + ", timeout: " + timeout + ", defaultResponse: " + defaultResponse);
        StringBuilder sb = new StringBuilder();
        sb.append("requestorId: ");
        sb.append(requestorId);
        sb.append(", text: ");
        sb.append(text);
        sb.append(", requestorIdEncoding: ");
        sb.append(requestorIdEncoding);
        sb.append(", textEncoding: ");
        sb.append(textEncoding);
        Log.i("GnssLocationProvider", sb.toString());
        GpsNetInitiatedHandler.GpsNiNotification notification = new GpsNetInitiatedHandler.GpsNiNotification();
        notification.notificationId = notificationId;
        notification.niType = niType;
        notification.needNotify = (notifyFlags & 1) != 0;
        notification.needVerify = (notifyFlags & 2) != 0;
        notification.privacyOverride = (notifyFlags & 4) != 0;
        notification.timeout = timeout;
        notification.defaultResponse = defaultResponse;
        notification.requestorId = requestorId;
        notification.text = text;
        notification.requestorIdEncoding = requestorIdEncoding;
        notification.textEncoding = textEncoding;
        this.mNIHandler.handleNiNotification(notification);
    }

    private void requestSetID(int flags) {
        String data_temp;
        TelephonyManager phone = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
        int type = 0;
        String data = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        if ((flags & 1) == 1) {
            String data_temp2 = phone.getSubscriberId();
            if (data_temp2 != null) {
                data = data_temp2;
                type = 1;
            }
        } else if ((flags & 2) == 2 && (data_temp = phone.getLine1Number()) != null) {
            data = data_temp;
            type = 2;
        }
        native_agps_set_id(type, data);
    }

    private void requestLocation(boolean independentFromGnss) {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "requestLocation. independentFromGnss: " + independentFromGnss);
        }
        sendMessage(16, 0, Boolean.valueOf(independentFromGnss));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestUtcTime() {
        if (DEBUG) {
            Log.d("GnssLocationProvider", "utcTimeRequest");
        }
        sendMessage(5, 0, null);
    }

    private void requestRefLocation() {
        TelephonyManager phone = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
        int phoneType = phone.getPhoneType();
        int i = 1;
        if (phoneType != 1) {
            if (phoneType == 2) {
                Log.e("GnssLocationProvider", "CDMA not supported.");
                return;
            }
            return;
        }
        GsmCellLocation gsm_cell = (GsmCellLocation) phone.getCellLocation();
        if (gsm_cell != null && phone.getNetworkOperator() != null && phone.getNetworkOperator().length() > 3) {
            int mcc = Integer.parseInt(phone.getNetworkOperator().substring(0, 3));
            int mnc = Integer.parseInt(phone.getNetworkOperator().substring(3));
            int networkType = phone.getNetworkType();
            int type = (networkType == 3 || networkType == 8 || networkType == 9 || networkType == 10 || networkType == 15) ? 2 : 2;
            native_agps_set_ref_location_cellid(type, mcc, mnc, gsm_cell.getLac(), gsm_cell.getCid());
            return;
        }
        Log.e("GnssLocationProvider", "Error getting cell location info.");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendMessage(int message, int arg, Object obj) {
        this.mWakeLock.acquire();
        if (Log.isLoggable("GnssLocationProvider", 4)) {
            AtomicInteger atomicInteger = this.mSendMessageArray.get(message);
            boolean shouldPrint = isShouldPrint(atomicInteger);
            if (shouldPrint) {
                Log.i("GnssLocationProvider", "WakeLock acquired by sendMessage(" + messageIdAsString(message) + ", " + arg + ", " + obj + ")");
            }
        }
        this.mHandler.obtainMessage(message, arg, 1, obj).sendToTarget();
    }

    /* loaded from: classes.dex */
    private final class ProviderHandler extends Handler {
        public ProviderHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int message = msg.what;
            switch (message) {
                case 2:
                    if (msg.arg1 == 1) {
                        GnssLocationProvider.this.handleEnable();
                        break;
                    } else {
                        GnssLocationProvider.this.handleDisable();
                        break;
                    }
                case 3:
                    GpsRequest gpsRequest = (GpsRequest) msg.obj;
                    GnssLocationProvider.this.handleSetRequest(gpsRequest.request, gpsRequest.source);
                    break;
                case 4:
                    GnssLocationProvider.this.handleUpdateNetworkState((Network) msg.obj);
                    break;
                case 5:
                    GnssLocationProvider.this.mNtpTimeHelper.retrieveAndInjectNtpTime();
                    break;
                case 6:
                    GnssLocationProvider.this.handleDownloadXtraData();
                    break;
                case 7:
                    GnssLocationProvider.this.handleUpdateLocation((Location) msg.obj);
                    break;
                case 11:
                    GnssLocationProvider.this.mDownloadXtraDataPending = 2;
                    break;
                case 12:
                    GnssLocationProvider.this.subscriptionOrSimChanged(GnssLocationProvider.this.mContext);
                    break;
                case 13:
                    handleInitialize();
                    break;
                case 14:
                    GnssLocationProvider.this.handleRequestSuplConnection((InetAddress) msg.obj);
                    break;
                case 15:
                    GnssLocationProvider.this.handleReleaseSuplConnection(msg.arg1);
                    break;
                case 16:
                    GnssLocationProvider.this.handleRequestLocation(((Boolean) msg.obj).booleanValue());
                    break;
                case 17:
                    GnssLocationProvider.this.handleReportLocation(msg.arg1 == 1, (Location) msg.obj);
                    break;
                case 18:
                    GnssLocationProvider.this.handleReportSvStatus((SvStatusInfo) msg.obj);
                    break;
            }
            if (msg.arg2 == 1) {
                GnssLocationProvider.this.mWakeLock.release();
                if (Log.isLoggable("GnssLocationProvider", 4)) {
                    GnssLocationProvider.this.mHandleMessageArray.get(message);
                    if (0 != 0) {
                        Log.i("GnssLocationProvider", "WakeLock released by handleMessage(" + GnssLocationProvider.this.messageIdAsString(message) + ", " + msg.arg1 + ", " + msg.obj + ")");
                    }
                }
            }
        }

        private void handleInitialize() {
            GnssLocationProvider.native_init_once();
            boolean isInitialized = GnssLocationProvider.this.native_init();
            if (isInitialized) {
                GnssLocationProvider.this.native_cleanup();
            } else {
                Log.w("GnssLocationProvider", "Native initialization failed at bootup");
            }
            GnssLocationProvider.this.reloadGpsProperties(GnssLocationProvider.this.mContext, GnssLocationProvider.this.mProperties);
            SubscriptionManager.from(GnssLocationProvider.this.mContext).addOnSubscriptionsChangedListener(GnssLocationProvider.this.mOnSubscriptionsChangedListener);
            if (!GnssLocationProvider.access$5800()) {
                if (GnssLocationProvider.DEBUG) {
                    Log.d("GnssLocationProvider", "Skipped registration for SMS/WAP-PUSH messages because AGPS Ril in GPS HAL is not supported");
                }
            } else {
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction("android.intent.action.DATA_SMS_RECEIVED");
                intentFilter.addDataScheme("sms");
                intentFilter.addDataAuthority("localhost", "7275");
                GnssLocationProvider.this.mContext.registerReceiver(GnssLocationProvider.this.mBroadcastReceiver, intentFilter, null, this);
                IntentFilter intentFilter2 = new IntentFilter();
                intentFilter2.addAction("android.provider.Telephony.WAP_PUSH_RECEIVED");
                try {
                    intentFilter2.addDataType("application/vnd.omaloc-supl-init");
                } catch (IntentFilter.MalformedMimeTypeException e) {
                    Log.w("GnssLocationProvider", "Malformed SUPL init mime type");
                }
                GnssLocationProvider.this.mContext.registerReceiver(GnssLocationProvider.this.mBroadcastReceiver, intentFilter2, null, this);
            }
            IntentFilter intentFilter3 = new IntentFilter();
            intentFilter3.addAction(GnssLocationProvider.ALARM_WAKEUP);
            intentFilter3.addAction(GnssLocationProvider.ALARM_TIMEOUT);
            intentFilter3.addAction("android.os.action.POWER_SAVE_MODE_CHANGED");
            intentFilter3.addAction("android.os.action.DEVICE_IDLE_MODE_CHANGED");
            intentFilter3.addAction("android.intent.action.SCREEN_OFF");
            intentFilter3.addAction("android.intent.action.SCREEN_ON");
            intentFilter3.addAction(GnssLocationProvider.SIM_STATE_CHANGED);
            GnssLocationProvider.this.mContext.registerReceiver(GnssLocationProvider.this.mBroadcastReceiver, intentFilter3, null, this);
            NetworkRequest.Builder networkRequestBuilder = new NetworkRequest.Builder();
            networkRequestBuilder.addCapability(12);
            networkRequestBuilder.addCapability(16);
            networkRequestBuilder.removeCapability(15);
            NetworkRequest networkRequest = networkRequestBuilder.build();
            GnssLocationProvider.this.mConnMgr.registerNetworkCallback(networkRequest, GnssLocationProvider.this.mNetworkConnectivityCallback);
            LocationManager locManager = (LocationManager) GnssLocationProvider.this.mContext.getSystemService("location");
            LocationRequest request = LocationRequest.createFromDeprecatedProvider("passive", 0L, 0.0f, false);
            request.setHideFromAppOps(true);
            locManager.requestLocationUpdates(request, new NetworkLocationListener(), getLooper());
        }
    }

    private boolean isShouldPrint(AtomicInteger atomicInteger) {
        boolean shouldPrint = true;
        if (atomicInteger != null) {
            if (atomicInteger.get() % 10 != 0) {
                shouldPrint = false;
            }
            if (shouldPrint) {
                atomicInteger.compareAndSet(10, 0);
            }
            atomicInteger.getAndAdd(1);
        }
        return shouldPrint;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class LocationChangeListener implements LocationListener {
        int numLocationUpdateRequest;

        private LocationChangeListener() {
        }

        @Override // android.location.LocationListener
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override // android.location.LocationListener
        public void onProviderEnabled(String provider) {
        }

        @Override // android.location.LocationListener
        public void onProviderDisabled(String provider) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class NetworkLocationListener extends LocationChangeListener {
        private NetworkLocationListener() {
            super();
        }

        @Override // android.location.LocationListener
        public void onLocationChanged(Location location) {
            if ("network".equals(location.getProvider())) {
                GnssLocationProvider.this.handleUpdateLocation(location);
            }
        }
    }

    /* loaded from: classes.dex */
    private final class FusedLocationListener extends LocationChangeListener {
        private FusedLocationListener() {
            super();
        }

        @Override // android.location.LocationListener
        public void onLocationChanged(Location location) {
            if ("fused".equals(location.getProvider())) {
                GnssLocationProvider.this.injectBestLocation(location);
            }
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:12:0x0039, code lost:
        if (r8 != null) goto L15;
     */
    /* JADX WARN: Code restructure failed: missing block: B:13:0x003b, code lost:
        r8.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:18:0x0049, code lost:
        if (0 == 0) goto L16;
     */
    /* JADX WARN: Code restructure failed: missing block: B:20:0x004c, code lost:
        return null;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.lang.String getSelectedApn() {
        /*
            r9 = this;
            java.lang.String r0 = "content://telephony/carriers/preferapn"
            android.net.Uri r0 = android.net.Uri.parse(r0)
            r7 = 0
            r8 = r7
            android.content.Context r1 = r9.mContext     // Catch: java.lang.Throwable -> L3f java.lang.Exception -> L41
            android.content.ContentResolver r1 = r1.getContentResolver()     // Catch: java.lang.Throwable -> L3f java.lang.Exception -> L41
            java.lang.String r2 = "apn"
            java.lang.String[] r3 = new java.lang.String[]{r2}     // Catch: java.lang.Throwable -> L3f java.lang.Exception -> L41
            r4 = 0
            r5 = 0
            java.lang.String r6 = "name ASC"
            r2 = r0
            android.database.Cursor r1 = r1.query(r2, r3, r4, r5, r6)     // Catch: java.lang.Throwable -> L3f java.lang.Exception -> L41
            r8 = r1
            if (r8 == 0) goto L32
            boolean r1 = r8.moveToFirst()     // Catch: java.lang.Throwable -> L3f java.lang.Exception -> L41
            if (r1 == 0) goto L32
            r1 = 0
            java.lang.String r1 = r8.getString(r1)     // Catch: java.lang.Throwable -> L3f java.lang.Exception -> L41
            if (r8 == 0) goto L31
            r8.close()
        L31:
            return r1
        L32:
            java.lang.String r1 = "GnssLocationProvider"
            java.lang.String r2 = "No APN found to select."
            android.util.Log.e(r1, r2)     // Catch: java.lang.Throwable -> L3f java.lang.Exception -> L41
            if (r8 == 0) goto L4c
        L3b:
            r8.close()
            goto L4c
        L3f:
            r1 = move-exception
            goto L4d
        L41:
            r1 = move-exception
            java.lang.String r2 = "GnssLocationProvider"
            java.lang.String r3 = "Error encountered on selecting the APN."
            android.util.Log.e(r2, r3, r1)     // Catch: java.lang.Throwable -> L3f
            if (r8 == 0) goto L4c
            goto L3b
        L4c:
            return r7
        L4d:
            if (r8 == 0) goto L52
            r8.close()
        L52:
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.location.GnssLocationProvider.getSelectedApn():java.lang.String");
    }

    /* JADX WARN: Code restructure failed: missing block: B:15:0x0058, code lost:
        if (r2 != null) goto L18;
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x005a, code lost:
        r2.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:21:0x0077, code lost:
        if (0 == 0) goto L19;
     */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x007a, code lost:
        return 0;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int getApnIpType(java.lang.String r10) {
        /*
            r9 = this;
            r9.ensureInHandlerThread()
            r0 = 0
            if (r10 != 0) goto L7
            return r0
        L7:
            java.lang.String r1 = "current = 1 and apn = '%s' and carrier_enabled = 1"
            r2 = 1
            java.lang.Object[] r2 = new java.lang.Object[r2]
            r2[r0] = r10
            java.lang.String r1 = java.lang.String.format(r1, r2)
            r2 = 0
            android.content.Context r3 = r9.mContext     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            android.content.ContentResolver r3 = r3.getContentResolver()     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            android.net.Uri r4 = android.provider.Telephony.Carriers.CONTENT_URI     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            java.lang.String r5 = "protocol"
            java.lang.String[] r5 = new java.lang.String[]{r5}     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            r7 = 0
            java.lang.String r8 = "name ASC"
            r6 = r1
            android.database.Cursor r3 = r3.query(r4, r5, r6, r7, r8)     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            r2 = r3
            if (r2 == 0) goto L42
            boolean r3 = r2.moveToFirst()     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            if (r3 == 0) goto L42
            java.lang.String r3 = r2.getString(r0)     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            int r3 = r9.translateToApnIpType(r3, r10)     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            if (r2 == 0) goto L41
            r2.close()
        L41:
            return r3
        L42:
            java.lang.String r3 = "GnssLocationProvider"
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            r4.<init>()     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            java.lang.String r5 = "No entry found in query for APN: "
            r4.append(r5)     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            r4.append(r10)     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            android.util.Log.e(r3, r4)     // Catch: java.lang.Throwable -> L5e java.lang.Exception -> L60
            if (r2 == 0) goto L7a
        L5a:
            r2.close()
            goto L7a
        L5e:
            r0 = move-exception
            goto L7b
        L60:
            r3 = move-exception
            java.lang.String r4 = "GnssLocationProvider"
            java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L5e
            r5.<init>()     // Catch: java.lang.Throwable -> L5e
            java.lang.String r6 = "Error encountered on APN query for: "
            r5.append(r6)     // Catch: java.lang.Throwable -> L5e
            r5.append(r10)     // Catch: java.lang.Throwable -> L5e
            java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L5e
            android.util.Log.e(r4, r5, r3)     // Catch: java.lang.Throwable -> L5e
            if (r2 == 0) goto L7a
            goto L5a
        L7a:
            return r0
        L7b:
            if (r2 == 0) goto L80
            r2.close()
        L80:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.location.GnssLocationProvider.getApnIpType(java.lang.String):int");
    }

    private int translateToApnIpType(String ipProtocol, String apn) {
        if ("IP".equals(ipProtocol)) {
            return 1;
        }
        if ("IPV6".equals(ipProtocol)) {
            return 2;
        }
        if ("IPV4V6".equals(ipProtocol)) {
            return 3;
        }
        String message = String.format("Unknown IP Protocol: %s, for APN: %s", ipProtocol, apn);
        Log.e("GnssLocationProvider", message);
        return 0;
    }

    private void setRouting() {
        if (this.mAGpsDataConnectionIpAddr == null) {
            return;
        }
        boolean result = this.mConnMgr.requestRouteToHostAddress(3, this.mAGpsDataConnectionIpAddr);
        if (!result) {
            Log.e("GnssLocationProvider", "Error requesting route to host: " + this.mAGpsDataConnectionIpAddr);
        } else if (DEBUG) {
            Log.d("GnssLocationProvider", "Successfully requested route to host: " + this.mAGpsDataConnectionIpAddr);
        }
    }

    private boolean isDataNetworkConnected() {
        NetworkInfo activeNetworkInfo = this.mConnMgr.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void ensureInHandlerThread() {
        if (this.mHandler != null && Looper.myLooper() == this.mHandler.getLooper()) {
            return;
        }
        throw new RuntimeException("This method must run on the Handler thread.");
    }

    private String agpsDataConnStateAsString() {
        switch (this.mAGpsDataConnectionState) {
            case 0:
                return "CLOSED";
            case 1:
                return "OPENING";
            case 2:
                return "OPEN";
            default:
                return "<Unknown>";
        }
    }

    private String agpsDataConnStatusAsString(int agpsDataConnStatus) {
        switch (agpsDataConnStatus) {
            case 1:
                return "REQUEST";
            case 2:
                return "RELEASE";
            case 3:
                return "CONNECTED";
            case 4:
                return "DONE";
            case 5:
                return "FAILED";
            default:
                return "<Unknown>";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String messageIdAsString(int message) {
        switch (message) {
            case 2:
                return "ENABLE";
            case 3:
                return "SET_REQUEST";
            case 4:
                return "UPDATE_NETWORK_STATE";
            case 5:
                return "INJECT_NTP_TIME";
            case 6:
                return "DOWNLOAD_XTRA_DATA";
            case 7:
                return "UPDATE_LOCATION";
            case 8:
            case 9:
            case 10:
            default:
                return "<Unknown>";
            case 11:
                return "DOWNLOAD_XTRA_DATA_FINISHED";
            case 12:
                return "SUBSCRIPTION_OR_SIM_CHANGED";
            case 13:
                return "INITIALIZE_HANDLER";
            case 14:
                return "REQUEST_SUPL_CONNECTION";
            case 15:
                return "RELEASE_SUPL_CONNECTION";
            case 16:
                return "REQUEST_LOCATION";
            case 17:
                return "REPORT_LOCATION";
            case 18:
                return "REPORT_SV_STATUS";
        }
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        StringBuilder s = new StringBuilder();
        s.append("  mStarted=");
        s.append(this.mStarted);
        s.append('\n');
        s.append("  mFixInterval=");
        s.append(this.mFixInterval);
        s.append('\n');
        s.append("  mLowPowerMode=");
        s.append(this.mLowPowerMode);
        s.append('\n');
        s.append("  mGnssMeasurementsProvider.isRegistered()=");
        s.append(this.mGnssMeasurementsProvider.isRegistered());
        s.append('\n');
        s.append("  mGnssNavigationMessageProvider.isRegistered()=");
        s.append(this.mGnssNavigationMessageProvider.isRegistered());
        s.append('\n');
        s.append("  mDisableGps (battery saver mode)=");
        s.append(this.mDisableGps);
        s.append('\n');
        s.append("  mEngineCapabilities=0x");
        s.append(Integer.toHexString(this.mEngineCapabilities));
        s.append(" ( ");
        if (hasCapability(1)) {
            s.append("SCHEDULING ");
        }
        if (hasCapability(2)) {
            s.append("MSB ");
        }
        if (hasCapability(4)) {
            s.append("MSA ");
        }
        if (hasCapability(8)) {
            s.append("SINGLE_SHOT ");
        }
        if (hasCapability(16)) {
            s.append("ON_DEMAND_TIME ");
        }
        if (hasCapability(32)) {
            s.append("GEOFENCING ");
        }
        if (hasCapability(64)) {
            s.append("MEASUREMENTS ");
        }
        if (hasCapability(128)) {
            s.append("NAV_MESSAGES ");
        }
        s.append(")\n");
        s.append(this.mGnssMetrics.dumpGnssMetricsAsText());
        s.append("  native internal state: ");
        s.append(native_get_internal_state());
        s.append("\n");
        pw.append((CharSequence) s);
    }
}
