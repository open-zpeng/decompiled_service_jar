package com.android.server.location;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.StatsLog;
import com.android.internal.location.GpsNetInitiatedHandler;
import com.android.internal.notification.SystemNotificationChannels;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class GnssVisibilityControl {
    private static final int ARRAY_MAP_INITIAL_CAPACITY_PROXY_APPS_STATE = 5;
    private static final long LOCATION_ICON_DISPLAY_DURATION_MILLIS = 5000;
    private static final String LOCATION_PERMISSION_NAME = "android.permission.ACCESS_FINE_LOCATION";
    private static final long ON_GPS_ENABLED_CHANGED_TIMEOUT_MILLIS = 3000;
    private static final String TAG = "GnssVisibilityControl";
    private static final String WAKELOCK_KEY = "GnssVisibilityControl";
    private static final long WAKELOCK_TIMEOUT_MILLIS = 60000;
    private final AppOpsManager mAppOps;
    private final Context mContext;
    private final Handler mHandler;
    private boolean mIsGpsEnabled;
    private final GpsNetInitiatedHandler mNiHandler;
    private final PackageManager mPackageManager;
    private final PowerManager.WakeLock mWakeLock;
    private static final boolean DEBUG = Log.isLoggable("GnssVisibilityControl", 3);
    private static final String[] NO_LOCATION_ENABLED_PROXY_APPS = new String[0];
    private ArrayMap<String, ProxyAppState> mProxyAppsState = new ArrayMap<>(5);
    private PackageManager.OnPermissionsChangedListener mOnPermissionsChangedListener = new PackageManager.OnPermissionsChangedListener() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$cq648s0kLZajRjefd-RR_iUZoiQ
        public final void onPermissionsChanged(int i) {
            GnssVisibilityControl.this.lambda$new$1$GnssVisibilityControl(i);
        }
    };

    private native boolean native_enable_nfw_location_access(String[] strArr);

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ProxyAppState {
        private boolean mHasLocationPermission;
        private boolean mIsLocationIconOn;

        private ProxyAppState(boolean hasLocationPermission) {
            this.mHasLocationPermission = hasLocationPermission;
        }
    }

    public /* synthetic */ void lambda$new$1$GnssVisibilityControl(final int uid) {
        runOnHandler(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$nmfWkQtbYmj8KoGmFncGZnuzWS0
            @Override // java.lang.Runnable
            public final void run() {
                GnssVisibilityControl.this.lambda$new$0$GnssVisibilityControl(uid);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public GnssVisibilityControl(Context context, Looper looper, GpsNetInitiatedHandler niHandler) {
        this.mContext = context;
        PowerManager powerManager = (PowerManager) context.getSystemService("power");
        this.mWakeLock = powerManager.newWakeLock(1, "GnssVisibilityControl");
        this.mHandler = new Handler(looper);
        this.mNiHandler = niHandler;
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mPackageManager = this.mContext.getPackageManager();
        runOnHandler(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$FLGfeDaxF8J3CE9m-TcOXh5j6ow
            @Override // java.lang.Runnable
            public final void run() {
                GnssVisibilityControl.this.handleInitialize();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onGpsEnabledChanged(final boolean isEnabled) {
        if (!this.mHandler.runWithScissors(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$WNe_V-oiVnZtOTinPJBWWgUSctQ
            @Override // java.lang.Runnable
            public final void run() {
                GnssVisibilityControl.this.lambda$onGpsEnabledChanged$2$GnssVisibilityControl(isEnabled);
            }
        }, 3000L) && !isEnabled) {
            Log.w("GnssVisibilityControl", "Native call to disable non-framework location access in GNSS HAL may get executed after native_cleanup().");
        }
    }

    public /* synthetic */ void lambda$reportNfwNotification$3$GnssVisibilityControl(String proxyAppPackageName, byte protocolStack, String otherProtocolStackName, byte requestor, String requestorId, byte responseType, boolean inEmergencyMode, boolean isCachedLocation) {
        handleNfwNotification(new NfwNotification(proxyAppPackageName, protocolStack, otherProtocolStackName, requestor, requestorId, responseType, inEmergencyMode, isCachedLocation));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportNfwNotification(final String proxyAppPackageName, final byte protocolStack, final String otherProtocolStackName, final byte requestor, final String requestorId, final byte responseType, final boolean inEmergencyMode, final boolean isCachedLocation) {
        runOnHandler(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$rgPyvoFYNphS-9zV3fbeQCNLxa8
            @Override // java.lang.Runnable
            public final void run() {
                GnssVisibilityControl.this.lambda$reportNfwNotification$3$GnssVisibilityControl(proxyAppPackageName, protocolStack, otherProtocolStackName, requestor, requestorId, responseType, inEmergencyMode, isCachedLocation);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onConfigurationUpdated(GnssConfiguration configuration) {
        final List<String> nfwLocationAccessProxyApps = configuration.getProxyApps();
        runOnHandler(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$YLPk0FuuEUrv7lfRNYvhNb6uKic
            @Override // java.lang.Runnable
            public final void run() {
                GnssVisibilityControl.this.lambda$onConfigurationUpdated$4$GnssVisibilityControl(nfwLocationAccessProxyApps);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleInitialize() {
        listenForProxyAppsPackageUpdates();
    }

    private void listenForProxyAppsPackageUpdates() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.PACKAGE_ADDED");
        intentFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        intentFilter.addAction("android.intent.action.PACKAGE_REPLACED");
        intentFilter.addAction("android.intent.action.PACKAGE_CHANGED");
        intentFilter.addDataScheme("package");
        this.mContext.registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.location.GnssVisibilityControl.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                char c = 65535;
                switch (action.hashCode()) {
                    case -810471698:
                        if (action.equals("android.intent.action.PACKAGE_REPLACED")) {
                            c = 2;
                            break;
                        }
                        break;
                    case 172491798:
                        if (action.equals("android.intent.action.PACKAGE_CHANGED")) {
                            c = 3;
                            break;
                        }
                        break;
                    case 525384130:
                        if (action.equals("android.intent.action.PACKAGE_REMOVED")) {
                            c = 1;
                            break;
                        }
                        break;
                    case 1544582882:
                        if (action.equals("android.intent.action.PACKAGE_ADDED")) {
                            c = 0;
                            break;
                        }
                        break;
                }
                if (c == 0 || c == 1 || c == 2 || c == 3) {
                    String pkgName = intent.getData().getEncodedSchemeSpecificPart();
                    GnssVisibilityControl.this.handleProxyAppPackageUpdate(pkgName, action);
                }
            }
        }, UserHandle.ALL, intentFilter, null, this.mHandler);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleProxyAppPackageUpdate(String pkgName, String action) {
        ProxyAppState proxyAppState = this.mProxyAppsState.get(pkgName);
        if (proxyAppState == null) {
            return;
        }
        if (DEBUG) {
            Log.d("GnssVisibilityControl", "Proxy app " + pkgName + " package changed: " + action);
        }
        boolean updatedLocationPermission = shouldEnableLocationPermissionInGnssHal(pkgName);
        if (proxyAppState.mHasLocationPermission != updatedLocationPermission) {
            Log.i("GnssVisibilityControl", "Proxy app " + pkgName + " location permission changed. IsLocationPermissionEnabled: " + updatedLocationPermission);
            proxyAppState.mHasLocationPermission = updatedLocationPermission;
            updateNfwLocationAccessProxyAppsInGnssHal();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: handleUpdateProxyApps */
    public void lambda$onConfigurationUpdated$4$GnssVisibilityControl(List<String> nfwLocationAccessProxyApps) {
        if (!isProxyAppListUpdated(nfwLocationAccessProxyApps)) {
            return;
        }
        if (nfwLocationAccessProxyApps.isEmpty()) {
            if (!this.mProxyAppsState.isEmpty()) {
                this.mPackageManager.removeOnPermissionsChangeListener(this.mOnPermissionsChangedListener);
                resetProxyAppsState();
                updateNfwLocationAccessProxyAppsInGnssHal();
                return;
            }
            return;
        }
        if (this.mProxyAppsState.isEmpty()) {
            this.mPackageManager.addOnPermissionsChangeListener(this.mOnPermissionsChangedListener);
        } else {
            resetProxyAppsState();
        }
        for (String proxyAppPkgName : nfwLocationAccessProxyApps) {
            ProxyAppState proxyAppState = new ProxyAppState(shouldEnableLocationPermissionInGnssHal(proxyAppPkgName));
            this.mProxyAppsState.put(proxyAppPkgName, proxyAppState);
        }
        updateNfwLocationAccessProxyAppsInGnssHal();
    }

    private void resetProxyAppsState() {
        for (Map.Entry<String, ProxyAppState> entry : this.mProxyAppsState.entrySet()) {
            ProxyAppState proxyAppState = entry.getValue();
            if (proxyAppState.mIsLocationIconOn) {
                this.mHandler.removeCallbacksAndMessages(proxyAppState);
                ApplicationInfo proxyAppInfo = getProxyAppInfo(entry.getKey());
                if (proxyAppInfo != null) {
                    clearLocationIcon(proxyAppState, proxyAppInfo.uid, entry.getKey());
                }
            }
        }
        this.mProxyAppsState.clear();
    }

    private boolean isProxyAppListUpdated(List<String> nfwLocationAccessProxyApps) {
        if (nfwLocationAccessProxyApps.size() != this.mProxyAppsState.size()) {
            return true;
        }
        for (String nfwLocationAccessProxyApp : nfwLocationAccessProxyApps) {
            if (!this.mProxyAppsState.containsKey(nfwLocationAccessProxyApp)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: handleGpsEnabledChanged */
    public void lambda$onGpsEnabledChanged$2$GnssVisibilityControl(boolean isGpsEnabled) {
        if (DEBUG) {
            Log.d("GnssVisibilityControl", "handleGpsEnabledChanged, mIsGpsEnabled: " + this.mIsGpsEnabled + ", isGpsEnabled: " + isGpsEnabled);
        }
        this.mIsGpsEnabled = isGpsEnabled;
        if (!this.mIsGpsEnabled) {
            disableNfwLocationAccess();
        } else {
            setNfwLocationAccessProxyAppsInGnssHal(getLocationPermissionEnabledProxyApps());
        }
    }

    private void disableNfwLocationAccess() {
        setNfwLocationAccessProxyAppsInGnssHal(NO_LOCATION_ENABLED_PROXY_APPS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class NfwNotification {
        private static final byte NFW_RESPONSE_TYPE_ACCEPTED_LOCATION_PROVIDED = 2;
        private static final byte NFW_RESPONSE_TYPE_ACCEPTED_NO_LOCATION_PROVIDED = 1;
        private static final byte NFW_RESPONSE_TYPE_REJECTED = 0;
        private final boolean mInEmergencyMode;
        private final boolean mIsCachedLocation;
        private final String mOtherProtocolStackName;
        private final byte mProtocolStack;
        private final String mProxyAppPackageName;
        private final byte mRequestor;
        private final String mRequestorId;
        private final byte mResponseType;

        private NfwNotification(String proxyAppPackageName, byte protocolStack, String otherProtocolStackName, byte requestor, String requestorId, byte responseType, boolean inEmergencyMode, boolean isCachedLocation) {
            this.mProxyAppPackageName = proxyAppPackageName;
            this.mProtocolStack = protocolStack;
            this.mOtherProtocolStackName = otherProtocolStackName;
            this.mRequestor = requestor;
            this.mRequestorId = requestorId;
            this.mResponseType = responseType;
            this.mInEmergencyMode = inEmergencyMode;
            this.mIsCachedLocation = isCachedLocation;
        }

        @SuppressLint({"DefaultLocale"})
        public String toString() {
            return String.format("{proxyAppPackageName: %s, protocolStack: %d, otherProtocolStackName: %s, requestor: %d, requestorId: %s, responseType: %s, inEmergencyMode: %b, isCachedLocation: %b}", this.mProxyAppPackageName, Byte.valueOf(this.mProtocolStack), this.mOtherProtocolStackName, Byte.valueOf(this.mRequestor), this.mRequestorId, getResponseTypeAsString(), Boolean.valueOf(this.mInEmergencyMode), Boolean.valueOf(this.mIsCachedLocation));
        }

        /* JADX INFO: Access modifiers changed from: private */
        public String getResponseTypeAsString() {
            byte b = this.mResponseType;
            if (b != 0) {
                if (b != 1) {
                    if (b == 2) {
                        return "ACCEPTED_LOCATION_PROVIDED";
                    }
                    return "<Unknown>";
                }
                return "ACCEPTED_NO_LOCATION_PROVIDED";
            }
            return "REJECTED";
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isRequestAccepted() {
            return this.mResponseType != 0;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isLocationProvided() {
            return this.mResponseType == 2;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isRequestAttributedToProxyApp() {
            return !TextUtils.isEmpty(this.mProxyAppPackageName);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isEmergencyRequestNotification() {
            return this.mInEmergencyMode && !isRequestAttributedToProxyApp();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: handlePermissionsChanged */
    public void lambda$new$0$GnssVisibilityControl(int uid) {
        if (this.mProxyAppsState.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ProxyAppState> entry : this.mProxyAppsState.entrySet()) {
            String proxyAppPkgName = entry.getKey();
            ApplicationInfo proxyAppInfo = getProxyAppInfo(proxyAppPkgName);
            if (proxyAppInfo != null && proxyAppInfo.uid == uid) {
                boolean isLocationPermissionEnabled = shouldEnableLocationPermissionInGnssHal(proxyAppPkgName);
                ProxyAppState proxyAppState = entry.getValue();
                if (isLocationPermissionEnabled != proxyAppState.mHasLocationPermission) {
                    Log.i("GnssVisibilityControl", "Proxy app " + proxyAppPkgName + " location permission changed. IsLocationPermissionEnabled: " + isLocationPermissionEnabled);
                    proxyAppState.mHasLocationPermission = isLocationPermissionEnabled;
                    updateNfwLocationAccessProxyAppsInGnssHal();
                    return;
                }
                return;
            }
        }
    }

    private ApplicationInfo getProxyAppInfo(String proxyAppPkgName) {
        try {
            return this.mPackageManager.getApplicationInfo(proxyAppPkgName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Log.d("GnssVisibilityControl", "Proxy app " + proxyAppPkgName + " is not found.");
                return null;
            }
            return null;
        }
    }

    private boolean shouldEnableLocationPermissionInGnssHal(String proxyAppPkgName) {
        return isProxyAppInstalled(proxyAppPkgName) && hasLocationPermission(proxyAppPkgName);
    }

    private boolean isProxyAppInstalled(String pkgName) {
        ApplicationInfo proxyAppInfo = getProxyAppInfo(pkgName);
        return proxyAppInfo != null && proxyAppInfo.enabled;
    }

    private boolean hasLocationPermission(String pkgName) {
        return this.mPackageManager.checkPermission(LOCATION_PERMISSION_NAME, pkgName) == 0;
    }

    private void updateNfwLocationAccessProxyAppsInGnssHal() {
        if (!this.mIsGpsEnabled) {
            return;
        }
        setNfwLocationAccessProxyAppsInGnssHal(getLocationPermissionEnabledProxyApps());
    }

    private void setNfwLocationAccessProxyAppsInGnssHal(String[] locationPermissionEnabledProxyApps) {
        String proxyAppsStr = Arrays.toString(locationPermissionEnabledProxyApps);
        Log.i("GnssVisibilityControl", "Updating non-framework location access proxy apps in the GNSS HAL to: " + proxyAppsStr);
        boolean result = native_enable_nfw_location_access(locationPermissionEnabledProxyApps);
        if (!result) {
            Log.e("GnssVisibilityControl", "Failed to update non-framework location access proxy apps in the GNSS HAL to: " + proxyAppsStr);
        }
    }

    private String[] getLocationPermissionEnabledProxyApps() {
        int countLocationPermissionEnabledProxyApps = 0;
        for (ProxyAppState proxyAppState : this.mProxyAppsState.values()) {
            if (proxyAppState.mHasLocationPermission) {
                countLocationPermissionEnabledProxyApps++;
            }
        }
        int i = 0;
        String[] locationPermissionEnabledProxyApps = new String[countLocationPermissionEnabledProxyApps];
        for (Map.Entry<String, ProxyAppState> entry : this.mProxyAppsState.entrySet()) {
            String proxyApp = entry.getKey();
            if (entry.getValue().mHasLocationPermission) {
                locationPermissionEnabledProxyApps[i] = proxyApp;
                i++;
            }
        }
        return locationPermissionEnabledProxyApps;
    }

    private void handleNfwNotification(NfwNotification nfwNotification) {
        if (DEBUG) {
            Log.d("GnssVisibilityControl", "Non-framework location access notification: " + nfwNotification);
        }
        if (!nfwNotification.isEmergencyRequestNotification()) {
            String proxyAppPkgName = nfwNotification.mProxyAppPackageName;
            ProxyAppState proxyAppState = this.mProxyAppsState.get(proxyAppPkgName);
            boolean isLocationRequestAccepted = nfwNotification.isRequestAccepted();
            boolean isPermissionMismatched = isPermissionMismatched(proxyAppState, nfwNotification);
            logEvent(nfwNotification, isPermissionMismatched);
            if (!nfwNotification.isRequestAttributedToProxyApp()) {
                if (!isLocationRequestAccepted) {
                    if (DEBUG) {
                        Log.d("GnssVisibilityControl", "Non-framework location request rejected. ProxyAppPackageName field is not set in the notification: " + nfwNotification + ". Number of configured proxy apps: " + this.mProxyAppsState.size());
                        return;
                    }
                    return;
                }
                Log.e("GnssVisibilityControl", "ProxyAppPackageName field is not set. AppOps service not notified for notification: " + nfwNotification);
                return;
            } else if (proxyAppState == null) {
                Log.w("GnssVisibilityControl", "Could not find proxy app " + proxyAppPkgName + " in the value specified for config parameter: " + GnssConfiguration.CONFIG_NFW_PROXY_APPS + ". AppOps service not notified for notification: " + nfwNotification);
                return;
            } else {
                ApplicationInfo proxyAppInfo = getProxyAppInfo(proxyAppPkgName);
                if (proxyAppInfo != null) {
                    if (nfwNotification.isLocationProvided()) {
                        showLocationIcon(proxyAppState, nfwNotification, proxyAppInfo.uid, proxyAppPkgName);
                        this.mAppOps.noteOpNoThrow(1, proxyAppInfo.uid, proxyAppPkgName);
                    }
                    if (isPermissionMismatched) {
                        Log.w("GnssVisibilityControl", "Permission mismatch. Proxy app " + proxyAppPkgName + " location permission is set to " + proxyAppState.mHasLocationPermission + " and GNSS HAL enabled is set to " + this.mIsGpsEnabled + " but GNSS non-framework location access response type is " + nfwNotification.getResponseTypeAsString() + " for notification: " + nfwNotification);
                        return;
                    }
                    return;
                }
                Log.e("GnssVisibilityControl", "Proxy app " + proxyAppPkgName + " is not found. AppOps service not notified for notification: " + nfwNotification);
                return;
            }
        }
        handleEmergencyNfwNotification(nfwNotification);
    }

    private boolean isPermissionMismatched(ProxyAppState proxyAppState, NfwNotification nfwNotification) {
        boolean isLocationRequestAccepted = nfwNotification.isRequestAccepted();
        if (proxyAppState == null || !this.mIsGpsEnabled) {
            return isLocationRequestAccepted;
        }
        return proxyAppState.mHasLocationPermission != isLocationRequestAccepted;
    }

    private void showLocationIcon(ProxyAppState proxyAppState, NfwNotification nfwNotification, int uid, final String proxyAppPkgName) {
        boolean isLocationIconOn = proxyAppState.mIsLocationIconOn;
        if (!isLocationIconOn) {
            if (updateLocationIcon(true, uid, proxyAppPkgName)) {
                proxyAppState.mIsLocationIconOn = true;
            } else {
                Log.w("GnssVisibilityControl", "Failed to show Location icon for notification: " + nfwNotification);
                return;
            }
        } else {
            this.mHandler.removeCallbacksAndMessages(proxyAppState);
        }
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("Location icon on. ");
            sb.append(isLocationIconOn ? "Extending" : "Setting");
            sb.append(" icon display timer. Uid: ");
            sb.append(uid);
            sb.append(", proxyAppPkgName: ");
            sb.append(proxyAppPkgName);
            Log.d("GnssVisibilityControl", sb.toString());
        }
        if (!this.mHandler.postDelayed(new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$3hQO4NR8YgRdTo_ZUTbEKP4-TIU
            @Override // java.lang.Runnable
            public final void run() {
                GnssVisibilityControl.this.lambda$showLocationIcon$5$GnssVisibilityControl(proxyAppPkgName);
            }
        }, proxyAppState, LOCATION_ICON_DISPLAY_DURATION_MILLIS)) {
            clearLocationIcon(proxyAppState, uid, proxyAppPkgName);
            Log.w("GnssVisibilityControl", "Failed to show location icon for the full duration for notification: " + nfwNotification);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: handleLocationIconTimeout */
    public void lambda$showLocationIcon$5$GnssVisibilityControl(String proxyAppPkgName) {
        ApplicationInfo proxyAppInfo = getProxyAppInfo(proxyAppPkgName);
        if (proxyAppInfo != null) {
            clearLocationIcon(this.mProxyAppsState.get(proxyAppPkgName), proxyAppInfo.uid, proxyAppPkgName);
        }
    }

    private void clearLocationIcon(ProxyAppState proxyAppState, int uid, String proxyAppPkgName) {
        updateLocationIcon(false, uid, proxyAppPkgName);
        if (proxyAppState != null) {
            proxyAppState.mIsLocationIconOn = false;
        }
        if (DEBUG) {
            Log.d("GnssVisibilityControl", "Location icon off. Uid: " + uid + ", proxyAppPkgName: " + proxyAppPkgName);
        }
    }

    private boolean updateLocationIcon(boolean displayLocationIcon, int uid, String proxyAppPkgName) {
        if (displayLocationIcon) {
            if (this.mAppOps.startOpNoThrow(41, uid, proxyAppPkgName) != 0) {
                return false;
            }
            if (this.mAppOps.startOpNoThrow(42, uid, proxyAppPkgName) != 0) {
                this.mAppOps.finishOp(41, uid, proxyAppPkgName);
                return false;
            }
        } else {
            this.mAppOps.finishOp(41, uid, proxyAppPkgName);
            this.mAppOps.finishOp(42, uid, proxyAppPkgName);
        }
        sendHighPowerMonitoringBroadcast();
        return true;
    }

    private void sendHighPowerMonitoringBroadcast() {
        Intent intent = new Intent("android.location.HIGH_POWER_REQUEST_CHANGE");
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void handleEmergencyNfwNotification(NfwNotification nfwNotification) {
        boolean isPermissionMismatched = false;
        if (!nfwNotification.isRequestAccepted()) {
            Log.e("GnssVisibilityControl", "Emergency non-framework location request incorrectly rejected. Notification: " + nfwNotification);
            isPermissionMismatched = true;
        }
        if (!this.mNiHandler.getInEmergency()) {
            Log.w("GnssVisibilityControl", "Emergency state mismatch. Device currently not in user initiated emergency session. Notification: " + nfwNotification);
            isPermissionMismatched = true;
        }
        logEvent(nfwNotification, isPermissionMismatched);
        if (nfwNotification.isLocationProvided()) {
            postEmergencyLocationUserNotification(nfwNotification);
        }
    }

    private void postEmergencyLocationUserNotification(NfwNotification nfwNotification) {
        NotificationManager notificationManager = (NotificationManager) this.mContext.getSystemService("notification");
        if (notificationManager == null) {
            Log.w("GnssVisibilityControl", "Could not notify user of emergency location request. Notification: " + nfwNotification);
            return;
        }
        notificationManager.notifyAsUser(null, 0, createEmergencyLocationUserNotification(this.mContext), UserHandle.ALL);
    }

    private static Notification createEmergencyLocationUserNotification(Context context) {
        String firstLineText = context.getString(17040080);
        String secondLineText = context.getString(17040062);
        String accessibilityServicesText = firstLineText + " (" + secondLineText + ")";
        return new Notification.Builder(context, SystemNotificationChannels.NETWORK_ALERTS).setSmallIcon(17303567).setWhen(0L).setOngoing(false).setAutoCancel(true).setColor(context.getColor(17170460)).setDefaults(0).setTicker(accessibilityServicesText).setContentTitle(firstLineText).setContentText(secondLineText).build();
    }

    private void logEvent(NfwNotification notification, boolean isPermissionMismatched) {
        StatsLog.write(131, notification.mProxyAppPackageName, notification.mProtocolStack, notification.mOtherProtocolStackName, notification.mRequestor, notification.mRequestorId, notification.mResponseType, notification.mInEmergencyMode, notification.mIsCachedLocation, isPermissionMismatched);
    }

    private void runOnHandler(Runnable event) {
        this.mWakeLock.acquire(60000L);
        if (!this.mHandler.post(runEventAndReleaseWakeLock(event))) {
            this.mWakeLock.release();
        }
    }

    private Runnable runEventAndReleaseWakeLock(final Runnable event) {
        return new Runnable() { // from class: com.android.server.location.-$$Lambda$GnssVisibilityControl$ezKd0QctWKgyrEvPFQUXWNBxlNg
            @Override // java.lang.Runnable
            public final void run() {
                GnssVisibilityControl.this.lambda$runEventAndReleaseWakeLock$6$GnssVisibilityControl(event);
            }
        };
    }

    public /* synthetic */ void lambda$runEventAndReleaseWakeLock$6$GnssVisibilityControl(Runnable event) {
        try {
            event.run();
        } finally {
            this.mWakeLock.release();
        }
    }
}
