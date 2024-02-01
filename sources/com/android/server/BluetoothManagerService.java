package com.android.server;

import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothUtils;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothHeadset;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothProfileServiceConnection;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Slog;
import android.util.StatsLog;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.pm.DumpState;
import com.android.server.pm.UserRestrictionsUtils;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class BluetoothManagerService extends IBluetoothManager.Stub {
    private static final int ACTIVE_LOG_MAX_SIZE = 20;
    private static final int ADD_PROXY_DELAY_MS = 100;
    private static final String BLUETOOTH_ADMIN_PERM = "android.permission.BLUETOOTH_ADMIN";
    private static final int BLUETOOTH_OFF = 0;
    private static final int BLUETOOTH_ON_AIRPLANE = 2;
    private static final int BLUETOOTH_ON_BLUETOOTH = 1;
    private static final String BLUETOOTH_PERM = "android.permission.BLUETOOTH";
    private static final int BLUETOOTH_START_ON_BOOT_DELAY = 3000;
    private static final String BT_MODE = "bt_mode";
    private static final int CRASH_LOG_MAX_SIZE = 100;
    private static final boolean DBG = true;
    private static final int ERROR_RESTART_TIME_MS = 3000;
    private static final String IN_DFU_MODE = "dfu";
    private static final String IN_NORMAL_MODE = "normal";
    private static final int MAX_ERROR_RESTART_RETRIES = 6;
    private static final int MESSAGE_ADD_PROXY_DELAYED = 400;
    private static final int MESSAGE_BIND_PROFILE_SERVICE = 401;
    private static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    private static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    private static final int MESSAGE_BLUETOOTH_STATE_CHANGE = 60;
    private static final int MESSAGE_DISABLE = 2;
    private static final int MESSAGE_ENABLE = 1;
    private static final int MESSAGE_GET_NAME_AND_ADDRESS = 200;
    private static final int MESSAGE_REGISTER_ADAPTER = 20;
    private static final int MESSAGE_REGISTER_STATE_CHANGE_CALLBACK = 30;
    private static final int MESSAGE_RESTART_BLUETOOTH_SERVICE = 42;
    private static final int MESSAGE_RESTORE_USER_SETTING = 500;
    private static final int MESSAGE_TIMEOUT_BIND = 100;
    private static final int MESSAGE_TIMEOUT_UNBIND = 101;
    private static final int MESSAGE_UNREGISTER_ADAPTER = 21;
    private static final int MESSAGE_UNREGISTER_STATE_CHANGE_CALLBACK = 31;
    private static final int MESSAGE_USER_SWITCHED = 300;
    private static final int MESSAGE_USER_UNLOCKED = 301;
    private static final int RESTORE_SETTING_TO_OFF = 0;
    private static final int RESTORE_SETTING_TO_ON = 1;
    private static final String SECURE_SETTINGS_BLUETOOTH_ADDRESS = "bluetooth_address";
    private static final String SECURE_SETTINGS_BLUETOOTH_ADDR_VALID = "bluetooth_addr_valid";
    private static final String SECURE_SETTINGS_BLUETOOTH_NAME = "bluetooth_name";
    private static final int SERVICE_IBLUETOOTH = 1;
    private static final int SERVICE_IBLUETOOTHGATT = 2;
    private static final int SERVICE_RESTART_TIME_MS = 500;
    private static final String TAG = "BluetoothManagerService";
    private static final int TIMEOUT_BIND_MS = 3000;
    private static final int USER_SWITCHED_TIME_MS = 200;
    private static String mUsbConnectdDevice = "";
    private AppOpsManager mAppOps;
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private boolean mEnableExternal;
    private boolean mIsHearingAidProfileSupported;
    private long mLastEnabledTime;
    private final RemoteCallbackList<IBluetoothStateChangeCallback> mStateChangeCallbacks;
    private final int mSystemUiUid;
    private final boolean mWirelessConsentRequired;
    private final ReentrantReadWriteLock mBluetoothLock = new ReentrantReadWriteLock();
    private boolean mQuietEnable = false;
    private final LinkedList<ActiveLog> mActiveLogs = new LinkedList<>();
    private final LinkedList<Long> mCrashTimestamps = new LinkedList<>();
    private Map<IBinder, ClientDeathRecipient> mBleApps = new ConcurrentHashMap();
    private final Map<Integer, ProfileServiceConnections> mProfileServices = new HashMap();
    private final IBluetoothCallback mBluetoothCallback = new IBluetoothCallback.Stub() { // from class: com.android.server.BluetoothManagerService.1
        public void onBluetoothStateChange(int prevState, int newState) throws RemoteException {
            Message msg = BluetoothManagerService.this.mHandler.obtainMessage(60, prevState, newState);
            BluetoothManagerService.this.mHandler.sendMessage(msg);
        }
    };
    private final UserManagerInternal.UserRestrictionsListener mUserRestrictionsListener = new UserManagerInternal.UserRestrictionsListener() { // from class: com.android.server.BluetoothManagerService.2
        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions, Bundle prevRestrictions) {
            if (UserRestrictionsUtils.restrictionsChanged(prevRestrictions, newRestrictions, "no_bluetooth_sharing")) {
                BluetoothManagerService.this.updateOppLauncherComponentState(userId, newRestrictions.getBoolean("no_bluetooth_sharing"));
            }
            if (userId == 0 && UserRestrictionsUtils.restrictionsChanged(prevRestrictions, newRestrictions, "no_bluetooth")) {
                if (userId != 0 || !newRestrictions.getBoolean("no_bluetooth")) {
                    BluetoothManagerService.this.updateOppLauncherComponentState(userId, newRestrictions.getBoolean("no_bluetooth_sharing"));
                    return;
                }
                BluetoothManagerService.this.updateOppLauncherComponentState(userId, true);
                BluetoothManagerService bluetoothManagerService = BluetoothManagerService.this;
                bluetoothManagerService.sendDisableMsg(3, bluetoothManagerService.mContext.getPackageName());
            }
        }
    };
    private final ContentObserver mAirplaneModeObserver = new ContentObserver(null) { // from class: com.android.server.BluetoothManagerService.3
        @Override // android.database.ContentObserver
        public void onChange(boolean unused) {
            ReentrantReadWriteLock.ReadLock readLock;
            synchronized (this) {
                if (BluetoothManagerService.this.isBluetoothPersistedStateOn()) {
                    if (BluetoothManagerService.this.isAirplaneModeOn()) {
                        BluetoothManagerService.this.persistBluetoothSetting(2);
                    } else {
                        BluetoothManagerService.this.persistBluetoothSetting(1);
                    }
                }
                try {
                    BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                    int st = BluetoothManagerService.this.mBluetooth != null ? BluetoothManagerService.this.mBluetooth.getState() : 10;
                    BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                    Slog.d(BluetoothManagerService.TAG, "Airplane Mode change - current state:  " + BluetoothAdapter.nameForState(st) + ", isAirplaneModeOn()=" + BluetoothManagerService.this.isAirplaneModeOn());
                    if (BluetoothManagerService.this.isAirplaneModeOn()) {
                        BluetoothManagerService.this.clearBleApps();
                        if (st == 15) {
                            try {
                                BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                                if (BluetoothManagerService.this.mBluetooth != null) {
                                    BluetoothManagerService.this.addActiveLog(2, BluetoothManagerService.this.mContext.getPackageName(), false);
                                    BluetoothManagerService.this.mBluetooth.onBrEdrDown();
                                    BluetoothManagerService.this.mEnable = false;
                                    BluetoothManagerService.this.mEnableExternal = false;
                                }
                                readLock = BluetoothManagerService.this.mBluetoothLock.readLock();
                            } catch (RemoteException e) {
                                Slog.e(BluetoothManagerService.TAG, "Unable to call onBrEdrDown", e);
                                readLock = BluetoothManagerService.this.mBluetoothLock.readLock();
                            }
                            readLock.unlock();
                        } else if (st == 12) {
                            BluetoothManagerService.this.sendDisableMsg(2, BluetoothManagerService.this.mContext.getPackageName());
                        }
                    } else if (BluetoothManagerService.this.mEnableExternal) {
                        BluetoothManagerService.this.sendEnableMsg(BluetoothManagerService.this.mQuietEnableExternal, 2, BluetoothManagerService.this.mContext.getPackageName());
                    }
                } catch (RemoteException e2) {
                    Slog.e(BluetoothManagerService.TAG, "Unable to call getState", e2);
                    BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                }
            }
        }
    };
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.android.server.BluetoothManagerService.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.bluetooth.adapter.action.LOCAL_NAME_CHANGED".equals(action)) {
                String newName = intent.getStringExtra("android.bluetooth.adapter.extra.LOCAL_NAME");
                Slog.d(BluetoothManagerService.TAG, "Bluetooth Adapter name changed to " + newName);
                if (newName != null) {
                    BluetoothManagerService.this.storeNameAndAddress(newName, null);
                }
            } else if ("android.bluetooth.adapter.action.BLUETOOTH_ADDRESS_CHANGED".equals(action)) {
                String newAddress = intent.getStringExtra("android.bluetooth.adapter.extra.BLUETOOTH_ADDRESS");
                if (newAddress != null) {
                    Slog.d(BluetoothManagerService.TAG, "Bluetooth Adapter address changed to " + newAddress);
                    BluetoothManagerService.this.storeNameAndAddress(null, newAddress);
                    return;
                }
                Slog.e(BluetoothManagerService.TAG, "No Bluetooth Adapter address parameter found");
            } else if ("android.os.action.SETTING_RESTORED".equals(action)) {
                String name = intent.getStringExtra("setting_name");
                if ("bluetooth_on".equals(name)) {
                    String prevValue = intent.getStringExtra("previous_value");
                    String newValue = intent.getStringExtra("new_value");
                    Slog.d(BluetoothManagerService.TAG, "ACTION_SETTING_RESTORED with BLUETOOTH_ON, prevValue=" + prevValue + ", newValue=" + newValue);
                    if (newValue != null && prevValue != null && !prevValue.equals(newValue)) {
                        Message msg = BluetoothManagerService.this.mHandler.obtainMessage(SystemService.PHASE_SYSTEM_SERVICES_READY, newValue.equals("0") ? 0 : 1, 0);
                        BluetoothManagerService.this.mHandler.sendMessage(msg);
                    }
                }
            }
        }
    };
    private BluetoothServiceConnection mConnection = new BluetoothServiceConnection();
    private final BluetoothHandler mHandler = new BluetoothHandler(IoThread.get().getLooper());
    private int mCrashes = 0;
    private IBluetooth mBluetooth = null;
    private IBinder mBluetoothBinder = null;
    private IBluetoothGatt mBluetoothGatt = null;
    private boolean mBinding = false;
    private boolean mUnbinding = false;
    private boolean mEnable = false;
    private int mState = 10;
    private boolean mQuietEnableExternal = false;
    private String mAddress = null;
    private String mName = null;
    private int mErrorRecoveryRetryCounter = 0;

    /* JADX INFO: Access modifiers changed from: private */
    public static CharSequence timeToLog(long timestamp) {
        return DateFormat.format("MM-dd HH:mm:ss", timestamp);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ActiveLog {
        private boolean mEnable;
        private String mPackageName;
        private int mReason;
        private long mTimestamp;

        ActiveLog(int reason, String packageName, boolean enable, long timestamp) {
            this.mReason = reason;
            this.mPackageName = packageName;
            this.mEnable = enable;
            this.mTimestamp = timestamp;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append((Object) BluetoothManagerService.timeToLog(this.mTimestamp));
            sb.append(this.mEnable ? "  Enabled " : " Disabled ");
            sb.append(" due to ");
            sb.append(BluetoothManagerService.getEnableDisableReasonString(this.mReason));
            sb.append(" by ");
            sb.append(this.mPackageName);
            return sb.toString();
        }
    }

    public boolean onFactoryReset() {
        int state = getState();
        if ((state == 14 || state == 11 || state == 13) && !waitForState(new HashSet(Arrays.asList(15, 12)))) {
            return false;
        }
        clearBleApps();
        int state2 = getState();
        try {
            try {
                this.mBluetoothLock.readLock().lock();
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to shutdown Bluetooth", e);
            }
            if (this.mBluetooth == null) {
                return false;
            }
            if (state2 == 15) {
                addActiveLog(10, this.mContext.getPackageName(), false);
                this.mBluetooth.onBrEdrDown();
                return true;
            }
            if (state2 == 12) {
                addActiveLog(10, this.mContext.getPackageName(), false);
                this.mBluetooth.disable();
                return true;
            }
            return false;
        } finally {
            this.mBluetoothLock.readLock().unlock();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BluetoothManagerService(Context context) {
        this.mContext = context;
        this.mWirelessConsentRequired = context.getResources().getBoolean(17891607);
        this.mEnableExternal = false;
        this.mContentResolver = context.getContentResolver();
        registerForBleScanModeChange();
        this.mCallbacks = new RemoteCallbackList<>();
        this.mStateChangeCallbacks = new RemoteCallbackList<>();
        this.mIsHearingAidProfileSupported = context.getResources().getBoolean(17891469);
        String value = SystemProperties.get("persist.sys.fflag.override.settings_bluetooth_hearing_aid");
        if (!TextUtils.isEmpty(value)) {
            boolean isHearingAidEnabled = Boolean.parseBoolean(value);
            Log.v(TAG, "set feature flag HEARING_AID_SETTINGS to " + isHearingAidEnabled);
            FeatureFlagUtils.setEnabled(context, "settings_bluetooth_hearing_aid", isHearingAidEnabled);
            if (isHearingAidEnabled && !this.mIsHearingAidProfileSupported) {
                this.mIsHearingAidProfileSupported = true;
            }
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.bluetooth.adapter.action.LOCAL_NAME_CHANGED");
        filter.addAction("android.bluetooth.adapter.action.BLUETOOTH_ADDRESS_CHANGED");
        filter.addAction("android.os.action.SETTING_RESTORED");
        filter.setPriority(1000);
        this.mContext.registerReceiver(this.mReceiver, filter);
        loadStoredNameAndAddress();
        if (isBluetoothPersistedStateOn()) {
            Slog.d(TAG, "Startup: Bluetooth persisted state is ON.");
            this.mEnableExternal = true;
        }
        String airplaneModeRadios = Settings.Global.getString(this.mContentResolver, "airplane_mode_radios");
        if (airplaneModeRadios == null || airplaneModeRadios.contains("bluetooth")) {
            this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("airplane_mode_on"), true, this.mAirplaneModeObserver);
        }
        try {
            boolean noHome = this.mContext.getResources().getBoolean(17891491);
            systemUiUid = noHome ? -1 : this.mContext.getPackageManager().getPackageUidAsUser("com.android.systemui", DumpState.DUMP_DEXOPT, 0);
            Slog.d(TAG, "Detected SystemUiUid: " + Integer.toString(systemUiUid));
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Unable to resolve SystemUI's UID.", e);
        }
        this.mSystemUiUid = systemUiUid;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isAirplaneModeOn() {
        return Settings.Global.getInt(this.mContext.getContentResolver(), "airplane_mode_on", 0) == 1;
    }

    private boolean supportBluetoothPersistedState() {
        return this.mContext.getResources().getBoolean(17891534);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isBluetoothPersistedStateOn() {
        if (supportBluetoothPersistedState()) {
            int state = Settings.Global.getInt(this.mContentResolver, "bluetooth_on", -1);
            Slog.d(TAG, "Bluetooth persisted state: " + state);
            return state != 0;
        }
        return false;
    }

    private boolean isBluetoothPersistedStateOnBluetooth() {
        return supportBluetoothPersistedState() && Settings.Global.getInt(this.mContentResolver, "bluetooth_on", 1) == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void persistBluetoothSetting(int value) {
        Slog.d(TAG, "Persisting Bluetooth Setting: " + value);
        long callingIdentity = Binder.clearCallingIdentity();
        Settings.Global.putInt(this.mContext.getContentResolver(), "bluetooth_on", value);
        Binder.restoreCallingIdentity(callingIdentity);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isNameAndAddressSet() {
        String str = this.mName;
        return str != null && this.mAddress != null && str.length() > 0 && this.mAddress.length() > 0;
    }

    private void loadStoredNameAndAddress() {
        Slog.d(TAG, "Loading stored name and address");
        if (this.mContext.getResources().getBoolean(17891375) && Settings.Secure.getInt(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDR_VALID, 0) == 0) {
            Slog.d(TAG, "invalid bluetooth name and address stored");
            return;
        }
        this.mName = Settings.Secure.getString(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME);
        this.mAddress = Settings.Secure.getString(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS);
        Slog.d(TAG, "Stored bluetooth Name=" + this.mName + ",Address=" + this.mAddress);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void storeNameAndAddress(String name, String address) {
        if (name != null) {
            Settings.Secure.putString(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME, name);
            this.mName = name;
            Slog.d(TAG, "Stored Bluetooth name: " + Settings.Secure.getString(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_NAME));
        }
        if (address != null) {
            Settings.Secure.putString(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS, address);
            this.mAddress = address;
            Slog.d(TAG, "Stored Bluetoothaddress: " + Settings.Secure.getString(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDRESS));
        }
        if (name != null && address != null) {
            Settings.Secure.putInt(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDR_VALID, 1);
        }
    }

    public IBluetooth registerAdapter(IBluetoothManagerCallback callback) {
        if (callback == null) {
            Slog.w(TAG, "Callback is null in registerAdapter");
            return null;
        }
        Message msg = this.mHandler.obtainMessage(20);
        msg.obj = callback;
        this.mHandler.sendMessage(msg);
        return this.mBluetooth;
    }

    public void unregisterAdapter(IBluetoothManagerCallback callback) {
        if (callback == null) {
            Slog.w(TAG, "Callback is null in unregisterAdapter");
            return;
        }
        this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Message msg = this.mHandler.obtainMessage(21);
        msg.obj = callback;
        this.mHandler.sendMessage(msg);
    }

    public void registerStateChangeCallback(IBluetoothStateChangeCallback callback) {
        this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (callback == null) {
            Slog.w(TAG, "registerStateChangeCallback: Callback is null!");
            return;
        }
        Message msg = this.mHandler.obtainMessage(30);
        msg.obj = callback;
        this.mHandler.sendMessage(msg);
    }

    public void unregisterStateChangeCallback(IBluetoothStateChangeCallback callback) {
        this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (callback == null) {
            Slog.w(TAG, "unregisterStateChangeCallback: Callback is null!");
            return;
        }
        Message msg = this.mHandler.obtainMessage(31);
        msg.obj = callback;
        this.mHandler.sendMessage(msg);
    }

    public boolean isEnabled() {
        if (Binder.getCallingUid() != 1000 && !checkIfCallerIsForegroundUser()) {
            Slog.w(TAG, "isEnabled(): not allowed for non-active and non system user");
            return false;
        }
        try {
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth != null) {
                    return this.mBluetooth.isEnabled();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "isEnabled()", e);
            }
            return false;
        } finally {
            this.mBluetoothLock.readLock().unlock();
        }
    }

    public int getState() {
        if (Binder.getCallingUid() != 1000 && !checkIfCallerIsForegroundUser()) {
            Slog.w(TAG, "getState(): report OFF for non-active and non system user");
            return 10;
        }
        try {
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth != null) {
                    return this.mBluetooth.getState();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "getState()", e);
            }
            return 10;
        } finally {
            this.mBluetoothLock.readLock().unlock();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ClientDeathRecipient implements IBinder.DeathRecipient {
        private String mPackageName;

        ClientDeathRecipient(String packageName) {
            this.mPackageName = packageName;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Slog.d(BluetoothManagerService.TAG, "Binder is dead - unregister " + this.mPackageName);
            for (Map.Entry<IBinder, ClientDeathRecipient> entry : BluetoothManagerService.this.mBleApps.entrySet()) {
                IBinder token = entry.getKey();
                ClientDeathRecipient deathRec = entry.getValue();
                if (deathRec.equals(this)) {
                    BluetoothManagerService.this.updateBleAppCount(token, false, this.mPackageName);
                    return;
                }
            }
        }

        public String getPackageName() {
            return this.mPackageName;
        }
    }

    public boolean isBleScanAlwaysAvailable() {
        if (!isAirplaneModeOn() || this.mEnable) {
            try {
                return Settings.Global.getInt(this.mContentResolver, "ble_scan_always_enabled") != 0;
            } catch (Settings.SettingNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    public boolean isHearingAidProfileSupported() {
        return this.mIsHearingAidProfileSupported;
    }

    private void registerForBleScanModeChange() {
        ContentObserver contentObserver = new ContentObserver(null) { // from class: com.android.server.BluetoothManagerService.5
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                if (!BluetoothManagerService.this.isBleScanAlwaysAvailable()) {
                    BluetoothManagerService.this.disableBleScanMode();
                    BluetoothManagerService.this.clearBleApps();
                    try {
                        try {
                            BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                            if (BluetoothManagerService.this.mBluetooth != null) {
                                BluetoothManagerService.this.addActiveLog(1, BluetoothManagerService.this.mContext.getPackageName(), false);
                                BluetoothManagerService.this.mBluetooth.onBrEdrDown();
                            }
                        } catch (RemoteException e) {
                            Slog.e(BluetoothManagerService.TAG, "error when disabling bluetooth", e);
                        }
                    } finally {
                        BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                    }
                }
            }
        };
        this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("ble_scan_always_enabled"), false, contentObserver);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disableBleScanMode() {
        try {
            try {
                this.mBluetoothLock.writeLock().lock();
                if (this.mBluetooth != null && this.mBluetooth.getState() != 12) {
                    Slog.d(TAG, "Reseting the mEnable flag for clean disable");
                    this.mEnable = false;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "getState()", e);
            }
        } finally {
            this.mBluetoothLock.writeLock().unlock();
        }
    }

    public int updateBleAppCount(IBinder token, boolean enable, String packageName) {
        int callingUid = Binder.getCallingUid();
        boolean isCallerSystem = UserHandle.getAppId(callingUid) == 1000;
        if (!isCallerSystem) {
            checkPackage(callingUid, packageName);
        }
        ClientDeathRecipient r = this.mBleApps.get(token);
        if (r == null && enable) {
            ClientDeathRecipient deathRec = new ClientDeathRecipient(packageName);
            try {
                token.linkToDeath(deathRec, 0);
                this.mBleApps.put(token, deathRec);
                Slog.d(TAG, "Registered for death of " + packageName);
            } catch (RemoteException e) {
                throw new IllegalArgumentException("BLE app (" + packageName + ") already dead!");
            }
        } else if (!enable && r != null) {
            token.unlinkToDeath(r, 0);
            this.mBleApps.remove(token);
            Slog.d(TAG, "Unregistered for death of " + packageName);
        }
        int appCount = this.mBleApps.size();
        Slog.d(TAG, appCount + " registered Ble Apps");
        if (appCount == 0 && this.mEnable) {
            disableBleScanMode();
        }
        if (appCount == 0 && !this.mEnableExternal) {
            sendBrEdrDownCallback();
        }
        return appCount;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearBleApps() {
        this.mBleApps.clear();
    }

    public boolean isBleAppPresent() {
        Slog.d(TAG, "isBleAppPresent() count: " + this.mBleApps.size());
        return this.mBleApps.size() > 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void continueFromBleOnState() {
        Slog.d(TAG, "continueFromBleOnState()");
        try {
            try {
                this.mBluetoothLock.readLock().lock();
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to call onServiceUp", e);
            }
            if (this.mBluetooth == null) {
                Slog.e(TAG, "onBluetoothServiceUp: mBluetooth is null!");
                return;
            }
            if (isBluetoothPersistedStateOnBluetooth() || !isBleAppPresent()) {
                this.mBluetooth.onLeServiceUp();
                persistBluetoothSetting(1);
            }
        } finally {
            this.mBluetoothLock.readLock().unlock();
        }
    }

    private void sendBrEdrDownCallback() {
        Slog.d(TAG, "Calling sendBrEdrDownCallback callbacks");
        if (this.mBluetooth == null) {
            Slog.w(TAG, "Bluetooth handle is null");
            return;
        }
        try {
            if (isBleAppPresent()) {
                try {
                    this.mBluetoothGatt.unregAll();
                    return;
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to disconnect all apps.", e);
                    return;
                }
            }
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth != null) {
                    this.mBluetooth.onBrEdrDown();
                }
            } catch (RemoteException e2) {
                Slog.e(TAG, "Call to onBrEdrDown() failed.", e2);
            }
        } finally {
            this.mBluetoothLock.readLock().unlock();
        }
    }

    public boolean enableNoAutoConnect(String packageName) {
        if (isBluetoothDisallowed()) {
            Slog.d(TAG, "enableNoAutoConnect(): not enabling - bluetooth disallowed");
            return false;
        }
        int callingUid = Binder.getCallingUid();
        boolean isCallerSystem = UserHandle.getAppId(callingUid) == 1000;
        if (!isCallerSystem) {
            checkPackage(callingUid, packageName);
        }
        this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        Slog.d(TAG, "enableNoAutoConnect():  mBluetooth =" + this.mBluetooth + " mBinding = " + this.mBinding);
        int callingAppId = UserHandle.getAppId(callingUid);
        if (callingAppId != 1027) {
            throw new SecurityException("no permission to enable Bluetooth quietly");
        }
        synchronized (this.mReceiver) {
            this.mQuietEnableExternal = true;
            this.mEnableExternal = true;
            sendEnableMsg(true, 1, packageName);
        }
        return true;
    }

    public boolean enable(String packageName) throws RemoteException {
        int callingUid = Binder.getCallingUid();
        boolean callerSystem = UserHandle.getAppId(callingUid) == 1000;
        if (isBluetoothDisallowed()) {
            Slog.d(TAG, "enable(): not enabling - bluetooth disallowed");
            return false;
        }
        if (!callerSystem) {
            checkPackage(callingUid, packageName);
            if (!checkIfCallerIsForegroundUser()) {
                Slog.w(TAG, "enable(): not allowed for non-active and non system user");
                return false;
            }
            this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
            if (!isEnabled() && this.mWirelessConsentRequired && startConsentUiIfNeeded(packageName, callingUid, "android.bluetooth.adapter.action.REQUEST_ENABLE")) {
                return false;
            }
        }
        Slog.d(TAG, "enable(" + packageName + "):  mBluetooth =" + this.mBluetooth + " mBinding = " + this.mBinding + " mState = " + BluetoothAdapter.nameForState(this.mState));
        synchronized (this.mReceiver) {
            this.mQuietEnableExternal = false;
            this.mEnableExternal = true;
            sendEnableMsg(false, 1, packageName);
        }
        Slog.d(TAG, "enable returning");
        return true;
    }

    public boolean disable(String packageName, boolean persist) throws RemoteException {
        int callingUid = Binder.getCallingUid();
        boolean callerSystem = UserHandle.getAppId(callingUid) == 1000;
        if (!callerSystem) {
            checkPackage(callingUid, packageName);
            if (!checkIfCallerIsForegroundUser()) {
                Slog.w(TAG, "disable(): not allowed for non-active and non system user");
                return false;
            }
            this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
            if (isEnabled() && this.mWirelessConsentRequired && startConsentUiIfNeeded(packageName, callingUid, "android.bluetooth.adapter.action.REQUEST_DISABLE")) {
                return false;
            }
        }
        Slog.d(TAG, "disable(): mBluetooth = " + this.mBluetooth + " mBinding = " + this.mBinding);
        synchronized (this.mReceiver) {
            if (persist) {
                persistBluetoothSetting(0);
            }
            this.mEnableExternal = false;
            sendDisableMsg(1, packageName);
        }
        return true;
    }

    public boolean enableDevice(int deviceId, String packageName, boolean persist) throws RemoteException {
        boolean ret = false;
        if (deviceId == 1) {
            int oldPolicyMask = StrictMode.allowThreadDiskWritesMask();
            try {
                try {
                    FileUtils.stringToFile(new File("/sys/xpeng/bt/vice_bt_power"), "1");
                    ret = true;
                } catch (Exception e) {
                    Slog.i(TAG, "enableDevice failed" + e.getMessage());
                }
                if (persist) {
                    persistBluetoothSetting(deviceId, 1);
                }
            } finally {
                StrictMode.setThreadPolicyMask(oldPolicyMask);
            }
        }
        Slog.i(TAG, "enableDevice deviceId " + deviceId + " packageName " + packageName + " persist " + persist + " ret " + ret);
        return ret;
    }

    public boolean disableDevice(int deviceId, String packageName, boolean persist) throws RemoteException {
        boolean ret = false;
        if (deviceId == 1) {
            int oldPolicyMask = StrictMode.allowThreadDiskWritesMask();
            try {
                try {
                    FileUtils.stringToFile(new File("/sys/xpeng/bt/vice_bt_power"), "0");
                    ret = true;
                } catch (Exception e) {
                    Slog.i(TAG, "disableDevice failed" + e.getMessage());
                }
                if (persist) {
                    persistBluetoothSetting(deviceId, 0);
                }
            } finally {
                StrictMode.setThreadPolicyMask(oldPolicyMask);
            }
        }
        Slog.i(TAG, "disableDevice deviceId " + deviceId + " packageName " + packageName + " persist " + persist + " ret " + ret);
        return true;
    }

    private void persistBluetoothSetting(int deviceId, int value) {
        if (deviceId == 1) {
            long callingIdentity = Binder.clearCallingIdentity();
            Settings.Global.putInt(this.mContext.getContentResolver(), "second_bluetooth_on", value);
            Binder.restoreCallingIdentity(callingIdentity);
        }
        Slog.d(TAG, "Persisting Bluetooth deviceId " + deviceId + " Setting: " + value);
    }

    private boolean isBluetoothPersistedStateOn(int deviceId) {
        int state = 0;
        if (deviceId == 1) {
            state = Settings.Global.getInt(this.mContentResolver, "second_bluetooth_on", 0);
        }
        Slog.d(TAG, "Bluetooth persisted deviceId state " + state);
        return state != 0;
    }

    public boolean isDeviceEnabled(int deviceId) {
        boolean ret = false;
        if (deviceId == 1) {
            int oldPolicyMask = StrictMode.allowThreadDiskWritesMask();
            try {
                try {
                    String status = FileUtils.readTextFile(new File("/sys/xpeng/bt/vice_bt_power"), 0, null);
                    Slog.i(TAG, "isDeviceEnabled deviceId " + deviceId + "status" + status + status.length());
                    if (status.contains("1")) {
                        ret = true;
                    }
                } catch (Exception e) {
                    Slog.i(TAG, "isDeviceEnabled failed" + e.getMessage());
                }
            } finally {
                StrictMode.setThreadPolicyMask(oldPolicyMask);
            }
        }
        Slog.i(TAG, "isDeviceEnabled deviceId " + deviceId + ret);
        return ret;
    }

    public String getConnectedDevice(int deviceId) {
        if (deviceId == 1) {
            return mUsbConnectdDevice;
        }
        return "";
    }

    public void setConnectedDevice(int deviceId, String address) {
        Slog.i(TAG, "SetConnectedDevice deviceId " + deviceId + " addr " + address);
        if (deviceId == 1) {
            mUsbConnectdDevice = address;
        }
    }

    public String getModuleVersion() {
        String majorNo = SystemProperties.get("persist.psn.bluetooth.major.no", "0");
        String minorNo = SystemProperties.get("persist.psn.bluetooth.minor.no", "0");
        Slog.e(TAG, "first getModuleVersion,ver = " + (majorNo + "-" + minorNo));
        if (!isDeviceEnabled(1)) {
            Slog.e(TAG, "getModuleVersion ,vice bt not opened!");
            try {
                enableDevice(1, "BluetoohtManagerService", false);
            } catch (Exception e) {
            }
            SystemClock.sleep(2500L);
            try {
                disableDevice(1, "BluetoohtManagerService", false);
            } catch (Exception e2) {
            }
        } else {
            Slog.e(TAG, "getModuleVersion ,vice bt opened!");
        }
        String majorNo2 = SystemProperties.get("persist.psn.bluetooth.major.no", "0");
        String minorNo2 = SystemProperties.get("persist.psn.bluetooth.minor.no", "0");
        String version = majorNo2 + "-" + minorNo2;
        Slog.e(TAG, "second getModuleVersion,ver = " + version);
        return version;
    }

    public boolean startDownload(String path) {
        Slog.e(TAG, "startDownload begin");
        Settings.Global.putString(this.mContext.getContentResolver(), BT_MODE, IN_DFU_MODE);
        boolean result = BluetoothUtils.startDownload(path);
        Settings.Global.putString(this.mContext.getContentResolver(), BT_MODE, "normal");
        Slog.e(TAG, "startDownload end");
        return result;
    }

    public boolean startUpload() {
        Slog.e(TAG, "startUpload begin");
        Settings.Global.putString(this.mContext.getContentResolver(), BT_MODE, IN_DFU_MODE);
        boolean result = BluetoothUtils.startUpload();
        Settings.Global.putString(this.mContext.getContentResolver(), BT_MODE, "normal");
        Slog.e(TAG, "startUpload end");
        return result;
    }

    public int getDownloadProgress() {
        return BluetoothUtils.getDownloadProgress();
    }

    public int getUploadProgress() {
        return BluetoothUtils.getUploadProgress();
    }

    private boolean startConsentUiIfNeeded(String packageName, int callingUid, String intentAction) throws RemoteException {
        if (checkBluetoothPermissionWhenWirelessConsentRequired()) {
            return false;
        }
        try {
            ApplicationInfo applicationInfo = this.mContext.getPackageManager().getApplicationInfoAsUser(packageName, 268435456, UserHandle.getUserId(callingUid));
            if (applicationInfo.uid != callingUid) {
                throw new SecurityException("Package " + packageName + " not in uid " + callingUid);
            }
            Intent intent = new Intent(intentAction);
            intent.putExtra("android.intent.extra.PACKAGE_NAME", packageName);
            intent.setFlags(276824064);
            try {
                this.mContext.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "Intent to handle action " + intentAction + " missing");
                return false;
            }
        } catch (PackageManager.NameNotFoundException e2) {
            throw new RemoteException(e2.getMessage());
        }
    }

    private void checkPackage(int uid, String packageName) {
        AppOpsManager appOpsManager = this.mAppOps;
        if (appOpsManager == null) {
            Slog.w(TAG, "checkPackage(): called before system boot up, uid " + uid + ", packageName " + packageName);
            throw new IllegalStateException("System has not boot yet");
        } else if (packageName == null) {
            Slog.w(TAG, "checkPackage(): called with null packageName from " + uid);
        } else {
            try {
                appOpsManager.checkPackage(uid, packageName);
            } catch (SecurityException e) {
                Slog.w(TAG, "checkPackage(): " + packageName + " does not belong to uid " + uid);
                throw new SecurityException(e.getMessage());
            }
        }
    }

    private boolean checkBluetoothPermissionWhenWirelessConsentRequired() {
        int result = this.mContext.checkCallingPermission("android.permission.MANAGE_BLUETOOTH_WHEN_WIRELESS_CONSENT_REQUIRED");
        return result == 0;
    }

    public void unbindAndFinish() {
        Slog.d(TAG, "unbindAndFinish(): " + this.mBluetooth + " mBinding = " + this.mBinding + " mUnbinding = " + this.mUnbinding);
        try {
            this.mBluetoothLock.writeLock().lock();
            if (this.mUnbinding) {
                return;
            }
            this.mUnbinding = true;
            this.mHandler.removeMessages(60);
            this.mHandler.removeMessages(MESSAGE_BIND_PROFILE_SERVICE);
            if (this.mBluetooth != null) {
                try {
                    this.mBluetooth.unregisterCallback(this.mBluetoothCallback);
                } catch (RemoteException re) {
                    Slog.e(TAG, "Unable to unregister BluetoothCallback", re);
                }
                this.mBluetoothBinder = null;
                this.mBluetooth = null;
                this.mContext.unbindService(this.mConnection);
                this.mUnbinding = false;
                this.mBinding = false;
            } else {
                this.mUnbinding = false;
            }
            this.mBluetoothGatt = null;
        } finally {
            this.mBluetoothLock.writeLock().unlock();
        }
    }

    public IBluetoothGatt getBluetoothGatt() {
        return this.mBluetoothGatt;
    }

    public boolean bindBluetoothProfileService(int bluetoothProfile, IBluetoothProfileServiceConnection proxy) {
        if (!this.mEnable) {
            Slog.d(TAG, "Trying to bind to profile: " + bluetoothProfile + ", while Bluetooth was disabled");
            return false;
        }
        synchronized (this.mProfileServices) {
            if (this.mProfileServices.get(new Integer(bluetoothProfile)) == null) {
                Slog.d(TAG, "Creating new ProfileServiceConnections object for profile: " + bluetoothProfile);
                if (bluetoothProfile != 1) {
                    return false;
                }
                Intent intent = new Intent(IBluetoothHeadset.class.getName());
                ProfileServiceConnections psc = new ProfileServiceConnections(intent);
                if (!psc.bindService()) {
                    return false;
                }
                this.mProfileServices.put(new Integer(bluetoothProfile), psc);
            }
            Message addProxyMsg = this.mHandler.obtainMessage(MESSAGE_ADD_PROXY_DELAYED);
            addProxyMsg.arg1 = bluetoothProfile;
            addProxyMsg.obj = proxy;
            this.mHandler.sendMessageDelayed(addProxyMsg, 100L);
            return true;
        }
    }

    public void unbindBluetoothProfileService(int bluetoothProfile, IBluetoothProfileServiceConnection proxy) {
        synchronized (this.mProfileServices) {
            Integer profile = new Integer(bluetoothProfile);
            ProfileServiceConnections psc = this.mProfileServices.get(profile);
            if (psc == null) {
                return;
            }
            psc.removeProxy(proxy);
            if (psc.isEmpty()) {
                try {
                    this.mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                this.mProfileServices.remove(profile);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unbindAllBluetoothProfileServices() {
        synchronized (this.mProfileServices) {
            for (Integer i : this.mProfileServices.keySet()) {
                ProfileServiceConnections psc = this.mProfileServices.get(i);
                try {
                    this.mContext.unbindService(psc);
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Unable to unbind service with intent: " + psc.mIntent, e);
                }
                psc.removeAllProxies();
            }
            this.mProfileServices.clear();
        }
    }

    public void handleOnBootPhase() {
        Slog.d(TAG, "Bluetooth boot completed");
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        UserManagerInternal userManagerInternal = (UserManagerInternal) LocalServices.getService(UserManagerInternal.class);
        userManagerInternal.addUserRestrictionsListener(this.mUserRestrictionsListener);
        boolean isBluetoothDisallowed = isBluetoothDisallowed();
        if (isBluetoothDisallowed) {
            return;
        }
        if (this.mEnableExternal && isBluetoothPersistedStateOnBluetooth()) {
            Slog.d(TAG, "Auto-enabling Bluetooth.");
            sendEnableMsg(this.mQuietEnableExternal, 6, this.mContext.getPackageName());
        } else if (!isNameAndAddressSet()) {
            Slog.d(TAG, "Getting adapter name and address");
            Message getMsg = this.mHandler.obtainMessage(200);
            this.mHandler.sendMessage(getMsg);
        }
        try {
            if (isBluetoothPersistedStateOn(1)) {
                enableDevice(1, "BluetoohtManagerService", false);
            } else {
                disableDevice(1, "BluetoohtManagerService", false);
            }
        } catch (Exception e) {
        }
    }

    public void handleOnSwitchUser(int userHandle) {
        Slog.d(TAG, "User " + userHandle + " switched");
        this.mHandler.obtainMessage(300, userHandle, 0).sendToTarget();
    }

    public void handleOnUnlockUser(int userHandle) {
        Slog.d(TAG, "User " + userHandle + " unlocked");
        this.mHandler.obtainMessage(MESSAGE_USER_UNLOCKED, userHandle, 0).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ProfileServiceConnections implements ServiceConnection, IBinder.DeathRecipient {
        Intent mIntent;
        final RemoteCallbackList<IBluetoothProfileServiceConnection> mProxies = new RemoteCallbackList<>();
        boolean mInvokingProxyCallbacks = false;
        IBinder mService = null;
        ComponentName mClassName = null;

        ProfileServiceConnections(Intent intent) {
            this.mIntent = intent;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean bindService() {
            try {
                try {
                    BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                    int state = BluetoothManagerService.this.mBluetooth != null ? BluetoothManagerService.this.mBluetooth.getState() : 10;
                    BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                    if (!BluetoothManagerService.this.mEnable || state != 12) {
                        Slog.d(BluetoothManagerService.TAG, "Unable to bindService while Bluetooth is disabled");
                        return false;
                    }
                    Intent intent = this.mIntent;
                    if (intent != null && this.mService == null && BluetoothManagerService.this.doBind(intent, this, 0, UserHandle.CURRENT_OR_SELF)) {
                        Message msg = BluetoothManagerService.this.mHandler.obtainMessage(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE);
                        msg.obj = this;
                        BluetoothManagerService.this.mHandler.sendMessageDelayed(msg, BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
                        return true;
                    }
                    Slog.w(BluetoothManagerService.TAG, "Unable to bind with intent: " + this.mIntent);
                    return false;
                } catch (RemoteException e) {
                    Slog.e(BluetoothManagerService.TAG, "Unable to call getState", e);
                    BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                    return false;
                }
            } catch (Throwable th) {
                BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                throw th;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void addProxy(IBluetoothProfileServiceConnection proxy) {
            this.mProxies.register(proxy);
            IBinder iBinder = this.mService;
            if (iBinder == null) {
                if (!BluetoothManagerService.this.mHandler.hasMessages(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE, this)) {
                    Message msg = BluetoothManagerService.this.mHandler.obtainMessage(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE);
                    msg.obj = this;
                    BluetoothManagerService.this.mHandler.sendMessage(msg);
                    return;
                }
                return;
            }
            try {
                proxy.onServiceConnected(this.mClassName, iBinder);
            } catch (RemoteException e) {
                Slog.e(BluetoothManagerService.TAG, "Unable to connect to proxy", e);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void removeProxy(IBluetoothProfileServiceConnection proxy) {
            if (proxy == null) {
                Slog.w(BluetoothManagerService.TAG, "Trying to remove a null proxy");
            } else if (this.mProxies.unregister(proxy)) {
                try {
                    proxy.onServiceDisconnected(this.mClassName);
                } catch (RemoteException e) {
                    Slog.e(BluetoothManagerService.TAG, "Unable to disconnect proxy", e);
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void removeAllProxies() {
            onServiceDisconnected(this.mClassName);
            this.mProxies.kill();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isEmpty() {
            return this.mProxies.getRegisteredCallbackCount() == 0;
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName className, IBinder service) {
            BluetoothManagerService.this.mHandler.removeMessages(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE, this);
            this.mService = service;
            this.mClassName = className;
            try {
                this.mService.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(BluetoothManagerService.TAG, "Unable to linkToDeath", e);
            }
            if (this.mInvokingProxyCallbacks) {
                Slog.e(BluetoothManagerService.TAG, "Proxy callbacks already in progress.");
                return;
            }
            this.mInvokingProxyCallbacks = true;
            int n = this.mProxies.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    try {
                        this.mProxies.getBroadcastItem(i).onServiceConnected(className, service);
                    } catch (RemoteException e2) {
                        Slog.e(BluetoothManagerService.TAG, "Unable to connect to proxy", e2);
                    }
                } finally {
                    this.mProxies.finishBroadcast();
                    this.mInvokingProxyCallbacks = false;
                }
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName className) {
            IBinder iBinder = this.mService;
            if (iBinder == null) {
                return;
            }
            try {
                iBinder.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.e(BluetoothManagerService.TAG, "error unlinking to death", e);
            }
            this.mService = null;
            this.mClassName = null;
            if (this.mInvokingProxyCallbacks) {
                Slog.e(BluetoothManagerService.TAG, "Proxy callbacks already in progress.");
                return;
            }
            this.mInvokingProxyCallbacks = true;
            int n = this.mProxies.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    try {
                        this.mProxies.getBroadcastItem(i).onServiceDisconnected(className);
                    } catch (RemoteException e2) {
                        Slog.e(BluetoothManagerService.TAG, "Unable to disconnect from proxy", e2);
                    }
                } finally {
                    this.mProxies.finishBroadcast();
                    this.mInvokingProxyCallbacks = false;
                }
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Slog.w(BluetoothManagerService.TAG, "Profile service for profile: " + this.mClassName + " died.");
            onServiceDisconnected(this.mClassName);
            Message msg = BluetoothManagerService.this.mHandler.obtainMessage(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE);
            msg.obj = this;
            BluetoothManagerService.this.mHandler.sendMessageDelayed(msg, BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
        }
    }

    private void sendBluetoothStateCallback(boolean isUp) {
        try {
            int n = this.mStateChangeCallbacks.beginBroadcast();
            Slog.d(TAG, "Broadcasting onBluetoothStateChange(" + isUp + ") to " + n + " receivers.");
            for (int i = 0; i < n; i++) {
                try {
                    this.mStateChangeCallbacks.getBroadcastItem(i).onBluetoothStateChange(isUp);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to call onBluetoothStateChange() on callback #" + i, e);
                }
            }
        } finally {
            this.mStateChangeCallbacks.finishBroadcast();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBluetoothServiceUpCallback() {
        try {
            int n = this.mCallbacks.beginBroadcast();
            Slog.d(TAG, "Broadcasting onBluetoothServiceUp() to " + n + " receivers.");
            for (int i = 0; i < n; i++) {
                try {
                    this.mCallbacks.getBroadcastItem(i).onBluetoothServiceUp(this.mBluetooth);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
                }
            }
        } finally {
            this.mCallbacks.finishBroadcast();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendBluetoothServiceDownCallback() {
        try {
            int n = this.mCallbacks.beginBroadcast();
            Slog.d(TAG, "Broadcasting onBluetoothServiceDown() to " + n + " receivers.");
            for (int i = 0; i < n; i++) {
                try {
                    this.mCallbacks.getBroadcastItem(i).onBluetoothServiceDown();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to call onBluetoothServiceDown() on callback #" + i, e);
                }
            }
        } finally {
            this.mCallbacks.finishBroadcast();
        }
    }

    public String getAddress() {
        this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (Binder.getCallingUid() != 1000 && !checkIfCallerIsForegroundUser()) {
            Slog.w(TAG, "getAddress(): not allowed for non-active and non system user");
            return null;
        } else if (this.mContext.checkCallingOrSelfPermission("android.permission.LOCAL_MAC_ADDRESS") != 0) {
            return "02:00:00:00:00:00";
        } else {
            try {
                try {
                    this.mBluetoothLock.readLock().lock();
                    if (this.mBluetooth != null) {
                        return this.mBluetooth.getAddress();
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "getAddress(): Unable to retrieve address remotely. Returning cached address", e);
                }
                this.mBluetoothLock.readLock().unlock();
                return this.mAddress;
            } finally {
                this.mBluetoothLock.readLock().unlock();
            }
        }
    }

    public String getName() {
        this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if (Binder.getCallingUid() != 1000 && !checkIfCallerIsForegroundUser()) {
            Slog.w(TAG, "getName(): not allowed for non-active and non system user");
            return null;
        }
        try {
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth != null) {
                    return this.mBluetooth.getName();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "getName(): Unable to retrieve name remotely. Returning cached name", e);
            }
            this.mBluetoothLock.readLock().unlock();
            return this.mName;
        } finally {
            this.mBluetoothLock.readLock().unlock();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class BluetoothServiceConnection implements ServiceConnection {
        private BluetoothServiceConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            String name = componentName.getClassName();
            Slog.d(BluetoothManagerService.TAG, "BluetoothServiceConnection: " + name);
            Message msg = BluetoothManagerService.this.mHandler.obtainMessage(40);
            if (name.equals("com.android.bluetooth.btservice.AdapterService")) {
                msg.arg1 = 1;
            } else if (name.equals("com.android.bluetooth.gatt.GattService")) {
                msg.arg1 = 2;
            } else {
                Slog.e(BluetoothManagerService.TAG, "Unknown service connected: " + name);
                return;
            }
            msg.obj = service;
            BluetoothManagerService.this.mHandler.sendMessage(msg);
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName componentName) {
            String name = componentName.getClassName();
            Slog.d(BluetoothManagerService.TAG, "BluetoothServiceConnection, disconnected: " + name);
            Message msg = BluetoothManagerService.this.mHandler.obtainMessage(41);
            if (name.equals("com.android.bluetooth.btservice.AdapterService")) {
                msg.arg1 = 1;
            } else if (name.equals("com.android.bluetooth.gatt.GattService")) {
                msg.arg1 = 2;
            } else {
                Slog.e(BluetoothManagerService.TAG, "Unknown service disconnected: " + name);
                return;
            }
            BluetoothManagerService.this.mHandler.sendMessage(msg);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class BluetoothHandler extends Handler {
        boolean mGetNameAddressOnly;

        BluetoothHandler(Looper looper) {
            super(looper);
            this.mGetNameAddressOnly = false;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            BluetoothManagerService bluetoothManagerService;
            ReentrantReadWriteLock reentrantReadWriteLock;
            ReentrantReadWriteLock.WriteLock writeLock;
            int i = msg.what;
            if (i == 1) {
                Slog.d(BluetoothManagerService.TAG, "MESSAGE_ENABLE(" + msg.arg1 + "): mBluetooth = " + BluetoothManagerService.this.mBluetooth);
                BluetoothManagerService.this.mHandler.removeMessages(42);
                BluetoothManagerService.this.mEnable = true;
                try {
                    try {
                        BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                        if (BluetoothManagerService.this.mBluetooth != null) {
                            int state = BluetoothManagerService.this.mBluetooth.getState();
                            if (state == 15) {
                                Slog.w(BluetoothManagerService.TAG, "BT Enable in BLE_ON State, going to ON");
                                BluetoothManagerService.this.mBluetooth.onLeServiceUp();
                                BluetoothManagerService.this.persistBluetoothSetting(1);
                                return;
                            }
                        }
                    } finally {
                    }
                } catch (RemoteException e) {
                    Slog.e(BluetoothManagerService.TAG, "", e);
                }
                BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                BluetoothManagerService.this.mQuietEnable = msg.arg1 == 1;
                if (BluetoothManagerService.this.mBluetooth == null) {
                    BluetoothManagerService bluetoothManagerService2 = BluetoothManagerService.this;
                    bluetoothManagerService2.handleEnable(bluetoothManagerService2.mQuietEnable);
                    return;
                }
                BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(10)));
                Message restartMsg = BluetoothManagerService.this.mHandler.obtainMessage(42);
                BluetoothManagerService.this.mHandler.sendMessageDelayed(restartMsg, 1000L);
            } else if (i == 2) {
                Slog.d(BluetoothManagerService.TAG, "MESSAGE_DISABLE: mBluetooth = " + BluetoothManagerService.this.mBluetooth);
                BluetoothManagerService.this.mHandler.removeMessages(42);
                if (!BluetoothManagerService.this.mEnable || BluetoothManagerService.this.mBluetooth == null) {
                    BluetoothManagerService.this.mEnable = false;
                    BluetoothManagerService.this.handleDisable();
                    return;
                }
                BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(12)));
                BluetoothManagerService.this.mEnable = false;
                BluetoothManagerService.this.handleDisable();
                BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(10, 11, 13, 14, 15, 16)));
            } else if (i == 20) {
                IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                BluetoothManagerService.this.mCallbacks.register(callback);
            } else if (i == 21) {
                IBluetoothManagerCallback callback2 = (IBluetoothManagerCallback) msg.obj;
                BluetoothManagerService.this.mCallbacks.unregister(callback2);
            } else if (i == 30) {
                IBluetoothStateChangeCallback callback3 = (IBluetoothStateChangeCallback) msg.obj;
                BluetoothManagerService.this.mStateChangeCallbacks.register(callback3);
            } else if (i == 31) {
                IBluetoothStateChangeCallback callback4 = (IBluetoothStateChangeCallback) msg.obj;
                BluetoothManagerService.this.mStateChangeCallbacks.unregister(callback4);
            } else if (i == 60) {
                int prevState = msg.arg1;
                int newState = msg.arg2;
                Slog.d(BluetoothManagerService.TAG, "MESSAGE_BLUETOOTH_STATE_CHANGE: " + BluetoothAdapter.nameForState(prevState) + " > " + BluetoothAdapter.nameForState(newState));
                BluetoothManagerService.this.mState = newState;
                BluetoothManagerService.this.bluetoothStateChangeHandler(prevState, newState);
                if (prevState == 14 && newState == 10 && BluetoothManagerService.this.mBluetooth != null && BluetoothManagerService.this.mEnable) {
                    BluetoothManagerService.this.recoverBluetoothServiceFromError(false);
                }
                if (prevState == 11 && newState == 15 && BluetoothManagerService.this.mBluetooth != null && BluetoothManagerService.this.mEnable) {
                    BluetoothManagerService.this.recoverBluetoothServiceFromError(true);
                }
                if (prevState == 16 && newState == 10 && BluetoothManagerService.this.mEnable) {
                    Slog.d(BluetoothManagerService.TAG, "Entering STATE_OFF but mEnabled is true; restarting.");
                    BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(10)));
                    Message restartMsg2 = BluetoothManagerService.this.mHandler.obtainMessage(42);
                    BluetoothManagerService.this.mHandler.sendMessageDelayed(restartMsg2, 1000L);
                }
                if ((newState == 12 || newState == 15) && BluetoothManagerService.this.mErrorRecoveryRetryCounter != 0) {
                    Slog.w(BluetoothManagerService.TAG, "bluetooth is recovered from error");
                    BluetoothManagerService.this.mErrorRecoveryRetryCounter = 0;
                }
            } else if (i == 200) {
                Slog.d(BluetoothManagerService.TAG, "MESSAGE_GET_NAME_AND_ADDRESS");
                try {
                    BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                    if (BluetoothManagerService.this.mBluetooth == null && !BluetoothManagerService.this.mBinding) {
                        Slog.d(BluetoothManagerService.TAG, "Binding to service to get name and address");
                        this.mGetNameAddressOnly = true;
                        Message timeoutMsg = BluetoothManagerService.this.mHandler.obtainMessage(100);
                        BluetoothManagerService.this.mHandler.sendMessageDelayed(timeoutMsg, BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
                        Intent i2 = new Intent(IBluetooth.class.getName());
                        if (BluetoothManagerService.this.doBind(i2, BluetoothManagerService.this.mConnection, 65, UserHandle.CURRENT)) {
                            BluetoothManagerService.this.mBinding = true;
                        } else {
                            BluetoothManagerService.this.mHandler.removeMessages(100);
                        }
                    } else if (BluetoothManagerService.this.mBluetooth != null) {
                        try {
                            BluetoothManagerService.this.storeNameAndAddress(BluetoothManagerService.this.mBluetooth.getName(), BluetoothManagerService.this.mBluetooth.getAddress());
                        } catch (RemoteException re) {
                            Slog.e(BluetoothManagerService.TAG, "Unable to grab names", re);
                        }
                        if (this.mGetNameAddressOnly && !BluetoothManagerService.this.mEnable) {
                            BluetoothManagerService.this.unbindAndFinish();
                        }
                        this.mGetNameAddressOnly = false;
                    }
                } finally {
                }
            } else if (i == 500) {
                if (msg.arg1 == 0 && BluetoothManagerService.this.mEnable) {
                    Slog.d(BluetoothManagerService.TAG, "Restore Bluetooth state to disabled");
                    BluetoothManagerService.this.persistBluetoothSetting(0);
                    BluetoothManagerService.this.mEnableExternal = false;
                    BluetoothManagerService bluetoothManagerService3 = BluetoothManagerService.this;
                    bluetoothManagerService3.sendDisableMsg(9, bluetoothManagerService3.mContext.getPackageName());
                } else if (msg.arg1 != 1 || BluetoothManagerService.this.mEnable) {
                } else {
                    Slog.d(BluetoothManagerService.TAG, "Restore Bluetooth state to enabled");
                    BluetoothManagerService.this.mQuietEnableExternal = false;
                    BluetoothManagerService.this.mEnableExternal = true;
                    BluetoothManagerService bluetoothManagerService4 = BluetoothManagerService.this;
                    bluetoothManagerService4.sendEnableMsg(false, 9, bluetoothManagerService4.mContext.getPackageName());
                }
            } else if (i == 100) {
                Slog.e(BluetoothManagerService.TAG, "MESSAGE_TIMEOUT_BIND");
                BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                BluetoothManagerService.this.mBinding = false;
            } else if (i == 101) {
                Slog.e(BluetoothManagerService.TAG, "MESSAGE_TIMEOUT_UNBIND");
                BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                BluetoothManagerService.this.mUnbinding = false;
            } else if (i == 300) {
                Slog.d(BluetoothManagerService.TAG, "MESSAGE_USER_SWITCHED");
                BluetoothManagerService.this.mHandler.removeMessages(300);
                if (BluetoothManagerService.this.mBluetooth != null) {
                    try {
                        if (BluetoothManagerService.this.isEnabled()) {
                            try {
                                BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                                if (BluetoothManagerService.this.mBluetooth != null) {
                                    BluetoothManagerService.this.mBluetooth.unregisterCallback(BluetoothManagerService.this.mBluetoothCallback);
                                }
                            } catch (RemoteException re2) {
                                Slog.e(BluetoothManagerService.TAG, "Unable to unregister", re2);
                            }
                            BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                            if (BluetoothManagerService.this.mState == 13) {
                                BluetoothManagerService bluetoothManagerService5 = BluetoothManagerService.this;
                                bluetoothManagerService5.bluetoothStateChangeHandler(bluetoothManagerService5.mState, 10);
                                BluetoothManagerService.this.mState = 10;
                            }
                            if (BluetoothManagerService.this.mState == 10) {
                                BluetoothManagerService bluetoothManagerService6 = BluetoothManagerService.this;
                                bluetoothManagerService6.bluetoothStateChangeHandler(bluetoothManagerService6.mState, 11);
                                BluetoothManagerService.this.mState = 11;
                            }
                            BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(12)));
                            if (BluetoothManagerService.this.mState == 11) {
                                BluetoothManagerService bluetoothManagerService7 = BluetoothManagerService.this;
                                bluetoothManagerService7.bluetoothStateChangeHandler(bluetoothManagerService7.mState, 12);
                            }
                            BluetoothManagerService.this.unbindAllBluetoothProfileServices();
                            BluetoothManagerService bluetoothManagerService8 = BluetoothManagerService.this;
                            bluetoothManagerService8.addActiveLog(8, bluetoothManagerService8.mContext.getPackageName(), false);
                            BluetoothManagerService.this.handleDisable();
                            BluetoothManagerService.this.bluetoothStateChangeHandler(12, 13);
                            boolean didDisableTimeout = !BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(10)));
                            BluetoothManagerService.this.bluetoothStateChangeHandler(13, 10);
                            BluetoothManagerService.this.sendBluetoothServiceDownCallback();
                            try {
                                BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                                if (BluetoothManagerService.this.mBluetooth != null) {
                                    BluetoothManagerService.this.mBluetooth = null;
                                    BluetoothManagerService.this.mContext.unbindService(BluetoothManagerService.this.mConnection);
                                }
                                BluetoothManagerService.this.mBluetoothGatt = null;
                                if (didDisableTimeout) {
                                    SystemClock.sleep(BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
                                } else {
                                    SystemClock.sleep(100L);
                                }
                                BluetoothManagerService.this.mHandler.removeMessages(60);
                                BluetoothManagerService.this.mState = 10;
                                BluetoothManagerService bluetoothManagerService9 = BluetoothManagerService.this;
                                bluetoothManagerService9.addActiveLog(8, bluetoothManagerService9.mContext.getPackageName(), true);
                                BluetoothManagerService.this.mEnable = true;
                                BluetoothManagerService bluetoothManagerService10 = BluetoothManagerService.this;
                                bluetoothManagerService10.handleEnable(bluetoothManagerService10.mQuietEnable);
                                return;
                            } finally {
                            }
                        }
                    } finally {
                    }
                }
                if (BluetoothManagerService.this.mBinding || BluetoothManagerService.this.mBluetooth != null) {
                    Message userMsg = BluetoothManagerService.this.mHandler.obtainMessage(300);
                    userMsg.arg2 = msg.arg2 + 1;
                    BluetoothManagerService.this.mHandler.sendMessageDelayed(userMsg, 200L);
                    Slog.d(BluetoothManagerService.TAG, "Retry MESSAGE_USER_SWITCHED " + userMsg.arg2);
                }
            } else if (i == BluetoothManagerService.MESSAGE_USER_UNLOCKED) {
                Slog.d(BluetoothManagerService.TAG, "MESSAGE_USER_UNLOCKED");
                BluetoothManagerService.this.mHandler.removeMessages(300);
                if (BluetoothManagerService.this.mEnable && !BluetoothManagerService.this.mBinding && BluetoothManagerService.this.mBluetooth == null) {
                    Slog.d(BluetoothManagerService.TAG, "Enabled but not bound; retrying after unlock");
                    BluetoothManagerService bluetoothManagerService11 = BluetoothManagerService.this;
                    bluetoothManagerService11.handleEnable(bluetoothManagerService11.mQuietEnable);
                }
            } else if (i == BluetoothManagerService.MESSAGE_ADD_PROXY_DELAYED) {
                ProfileServiceConnections psc = (ProfileServiceConnections) BluetoothManagerService.this.mProfileServices.get(Integer.valueOf(msg.arg1));
                if (psc == null) {
                    return;
                }
                IBluetoothProfileServiceConnection proxy = (IBluetoothProfileServiceConnection) msg.obj;
                psc.addProxy(proxy);
            } else if (i == BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE) {
                ProfileServiceConnections psc2 = (ProfileServiceConnections) msg.obj;
                removeMessages(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE, msg.obj);
                if (psc2 == null) {
                    return;
                }
                psc2.bindService();
            } else {
                switch (i) {
                    case 40:
                        Slog.d(BluetoothManagerService.TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: " + msg.arg1);
                        IBinder service = (IBinder) msg.obj;
                        try {
                            BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                            if (msg.arg1 == 2) {
                                BluetoothManagerService.this.mBluetoothGatt = IBluetoothGatt.Stub.asInterface(Binder.allowBlocking(service));
                                BluetoothManagerService.this.continueFromBleOnState();
                                return;
                            }
                            BluetoothManagerService.this.mHandler.removeMessages(100);
                            BluetoothManagerService.this.mBinding = false;
                            BluetoothManagerService.this.mBluetoothBinder = service;
                            BluetoothManagerService.this.mBluetooth = IBluetooth.Stub.asInterface(Binder.allowBlocking(service));
                            if (!BluetoothManagerService.this.isNameAndAddressSet()) {
                                Message getMsg = BluetoothManagerService.this.mHandler.obtainMessage(200);
                                BluetoothManagerService.this.mHandler.sendMessage(getMsg);
                                if (this.mGetNameAddressOnly) {
                                    return;
                                }
                            }
                            try {
                                BluetoothManagerService.this.mBluetooth.registerCallback(BluetoothManagerService.this.mBluetoothCallback);
                            } catch (RemoteException re3) {
                                Slog.e(BluetoothManagerService.TAG, "Unable to register BluetoothCallback", re3);
                            }
                            BluetoothManagerService.this.sendBluetoothServiceUpCallback();
                            try {
                                if (BluetoothManagerService.this.mQuietEnable) {
                                    if (!BluetoothManagerService.this.mBluetooth.enableNoAutoConnect()) {
                                        Slog.e(BluetoothManagerService.TAG, "IBluetooth.enableNoAutoConnect() returned false");
                                    }
                                } else if (!BluetoothManagerService.this.mBluetooth.enable()) {
                                    Slog.e(BluetoothManagerService.TAG, "IBluetooth.enable() returned false");
                                }
                            } catch (RemoteException e2) {
                                Slog.e(BluetoothManagerService.TAG, "Unable to call enable()", e2);
                            }
                            BluetoothManagerService.this.mBluetoothLock.writeLock().unlock();
                            if (BluetoothManagerService.this.mEnable) {
                                return;
                            }
                            BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(12)));
                            BluetoothManagerService.this.handleDisable();
                            BluetoothManagerService.this.waitForState(new HashSet(Arrays.asList(10, 11, 13, 14, 15, 16)));
                            return;
                        } finally {
                        }
                    case 41:
                        Slog.e(BluetoothManagerService.TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED(" + msg.arg1 + ")");
                        try {
                            BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                            if (msg.arg1 == 1) {
                                if (BluetoothManagerService.this.mBluetooth != null) {
                                    BluetoothManagerService.this.mBluetooth = null;
                                    BluetoothManagerService.this.mBluetoothLock.writeLock().unlock();
                                    BluetoothManagerService.this.addCrashLog();
                                    BluetoothManagerService bluetoothManagerService12 = BluetoothManagerService.this;
                                    bluetoothManagerService12.addActiveLog(7, bluetoothManagerService12.mContext.getPackageName(), false);
                                    if (BluetoothManagerService.this.mEnable) {
                                        BluetoothManagerService.this.mEnable = false;
                                        Message restartMsg3 = BluetoothManagerService.this.mHandler.obtainMessage(42);
                                        BluetoothManagerService.this.mHandler.sendMessageDelayed(restartMsg3, 500L);
                                    }
                                    BluetoothManagerService.this.sendBluetoothServiceDownCallback();
                                    if (BluetoothManagerService.this.mState == 11 || BluetoothManagerService.this.mState == 12) {
                                        BluetoothManagerService.this.bluetoothStateChangeHandler(12, 13);
                                        BluetoothManagerService.this.mState = 13;
                                    }
                                    if (BluetoothManagerService.this.mState == 13) {
                                        BluetoothManagerService.this.bluetoothStateChangeHandler(13, 10);
                                    }
                                    BluetoothManagerService.this.mHandler.removeMessages(60);
                                    BluetoothManagerService.this.mState = 10;
                                    return;
                                }
                            } else if (msg.arg1 == 2) {
                                BluetoothManagerService.this.mBluetoothGatt = null;
                            } else {
                                Slog.e(BluetoothManagerService.TAG, "Unknown argument for service disconnect!");
                            }
                            return;
                        } finally {
                        }
                    case 42:
                        Slog.d(BluetoothManagerService.TAG, "MESSAGE_RESTART_BLUETOOTH_SERVICE");
                        BluetoothManagerService.this.mEnable = true;
                        BluetoothManagerService bluetoothManagerService13 = BluetoothManagerService.this;
                        bluetoothManagerService13.addActiveLog(4, bluetoothManagerService13.mContext.getPackageName(), true);
                        BluetoothManagerService bluetoothManagerService14 = BluetoothManagerService.this;
                        bluetoothManagerService14.handleEnable(bluetoothManagerService14.mQuietEnable);
                        return;
                    default:
                        return;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleEnable(boolean quietMode) {
        this.mQuietEnable = quietMode;
        try {
            this.mBluetoothLock.writeLock().lock();
            if (this.mBluetooth == null && !this.mBinding) {
                Message timeoutMsg = this.mHandler.obtainMessage(100);
                this.mHandler.sendMessageDelayed(timeoutMsg, BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
                Intent i = new Intent(IBluetooth.class.getName());
                if (!doBind(i, this.mConnection, 65, UserHandle.CURRENT)) {
                    this.mHandler.removeMessages(100);
                } else {
                    this.mBinding = true;
                }
            } else if (this.mBluetooth != null) {
                try {
                    if (!this.mQuietEnable) {
                        if (!this.mBluetooth.enable()) {
                            Slog.e(TAG, "IBluetooth.enable() returned false");
                        }
                    } else if (!this.mBluetooth.enableNoAutoConnect()) {
                        Slog.e(TAG, "IBluetooth.enableNoAutoConnect() returned false");
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to call enable()", e);
                }
            }
        } finally {
            this.mBluetoothLock.writeLock().unlock();
        }
    }

    boolean doBind(Intent intent, ServiceConnection conn, int flags, UserHandle user) {
        ComponentName comp = intent.resolveSystemService(this.mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !this.mContext.bindServiceAsUser(intent, conn, flags, user)) {
            Slog.e(TAG, "Fail to bind to: " + intent);
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDisable() {
        try {
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth != null) {
                    Slog.d(TAG, "Sending off request.");
                    if (!this.mBluetooth.disable()) {
                        Slog.e(TAG, "IBluetooth.disable() returned false");
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to call disable()", e);
            }
        } finally {
            this.mBluetoothLock.readLock().unlock();
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:19:0x003e A[Catch: all -> 0x0071, TRY_LEAVE, TryCatch #0 {all -> 0x0071, blocks: (B:7:0x0027, B:12:0x0033, B:19:0x003e), top: B:25:0x0027 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean checkIfCallerIsForegroundUser() {
        /*
            r13 = this;
            int r0 = android.os.UserHandle.getCallingUserId()
            int r1 = android.os.Binder.getCallingUid()
            long r2 = android.os.Binder.clearCallingIdentity()
            android.content.Context r4 = r13.mContext
            java.lang.String r5 = "user"
            java.lang.Object r4 = r4.getSystemService(r5)
            android.os.UserManager r4 = (android.os.UserManager) r4
            android.content.pm.UserInfo r5 = r4.getProfileParent(r0)
            if (r5 == 0) goto L20
            int r6 = r5.id
            goto L22
        L20:
            r6 = -10000(0xffffffffffffd8f0, float:NaN)
        L22:
            int r7 = android.os.UserHandle.getAppId(r1)
            r8 = 0
            int r9 = android.app.ActivityManager.getCurrentUser()     // Catch: java.lang.Throwable -> L71
            if (r0 == r9) goto L3a
            if (r6 == r9) goto L3a
            r10 = 1027(0x403, float:1.439E-42)
            if (r7 == r10) goto L3a
            int r10 = r13.mSystemUiUid     // Catch: java.lang.Throwable -> L71
            if (r7 != r10) goto L38
            goto L3a
        L38:
            r10 = 0
            goto L3b
        L3a:
            r10 = 1
        L3b:
            r8 = r10
            if (r8 != 0) goto L6c
            java.lang.String r10 = "BluetoothManagerService"
            java.lang.StringBuilder r11 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L71
            r11.<init>()     // Catch: java.lang.Throwable -> L71
            java.lang.String r12 = "checkIfCallerIsForegroundUser: valid="
            r11.append(r12)     // Catch: java.lang.Throwable -> L71
            r11.append(r8)     // Catch: java.lang.Throwable -> L71
            java.lang.String r12 = " callingUser="
            r11.append(r12)     // Catch: java.lang.Throwable -> L71
            r11.append(r0)     // Catch: java.lang.Throwable -> L71
            java.lang.String r12 = " parentUser="
            r11.append(r12)     // Catch: java.lang.Throwable -> L71
            r11.append(r6)     // Catch: java.lang.Throwable -> L71
            java.lang.String r12 = " foregroundUser="
            r11.append(r12)     // Catch: java.lang.Throwable -> L71
            r11.append(r9)     // Catch: java.lang.Throwable -> L71
            java.lang.String r11 = r11.toString()     // Catch: java.lang.Throwable -> L71
            android.util.Slog.d(r10, r11)     // Catch: java.lang.Throwable -> L71
        L6c:
            android.os.Binder.restoreCallingIdentity(r2)
            return r8
        L71:
            r9 = move-exception
            android.os.Binder.restoreCallingIdentity(r2)
            throw r9
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.BluetoothManagerService.checkIfCallerIsForegroundUser():boolean");
    }

    private void sendBleStateChanged(int prevState, int newState) {
        Slog.d(TAG, "Sending BLE State Change: " + BluetoothAdapter.nameForState(prevState) + " > " + BluetoothAdapter.nameForState(newState));
        Intent intent = new Intent("android.bluetooth.adapter.action.BLE_STATE_CHANGED");
        intent.putExtra("android.bluetooth.adapter.extra.PREVIOUS_STATE", prevState);
        intent.putExtra("android.bluetooth.adapter.extra.STATE", newState);
        intent.addFlags(67108864);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, BLUETOOTH_PERM);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void bluetoothStateChangeHandler(int prevState, int newState) {
        boolean isStandardBroadcast = true;
        if (prevState == newState) {
            return;
        }
        if (newState == 15 || newState == 10) {
            if (prevState != 13 || newState != 15) {
                isUp = false;
            }
            if (newState == 10) {
                Slog.d(TAG, "Bluetooth is complete send Service Down");
                sendBluetoothServiceDownCallback();
                unbindAndFinish();
                sendBleStateChanged(prevState, newState);
                isStandardBroadcast = false;
            } else if (!isUp) {
                Slog.d(TAG, "Bluetooth is in LE only mode");
                if (this.mBluetoothGatt == null && this.mContext.getPackageManager().hasSystemFeature("android.hardware.bluetooth_le")) {
                    Slog.d(TAG, "Binding Bluetooth GATT service");
                    Intent i = new Intent(IBluetoothGatt.class.getName());
                    doBind(i, this.mConnection, 65, UserHandle.CURRENT);
                } else {
                    continueFromBleOnState();
                }
                sendBleStateChanged(prevState, newState);
                isStandardBroadcast = false;
            } else if (isUp) {
                Slog.d(TAG, "Intermediate off, back to LE only mode");
                sendBleStateChanged(prevState, newState);
                sendBluetoothStateCallback(false);
                newState = 10;
                sendBrEdrDownCallback();
            }
        } else if (newState == 12) {
            isUp = newState == 12;
            sendBluetoothStateCallback(isUp);
            sendBleStateChanged(prevState, newState);
        } else if (newState == 14 || newState == 16) {
            sendBleStateChanged(prevState, newState);
            isStandardBroadcast = false;
        } else if (newState == 11 || newState == 13) {
            sendBleStateChanged(prevState, newState);
        }
        if (isStandardBroadcast) {
            if (prevState == 15) {
                prevState = 10;
            }
            Intent intent = new Intent("android.bluetooth.adapter.action.STATE_CHANGED");
            intent.putExtra("android.bluetooth.adapter.extra.PREVIOUS_STATE", prevState);
            intent.putExtra("android.bluetooth.adapter.extra.STATE", newState);
            intent.addFlags(67108864);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, BLUETOOTH_PERM);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean waitForState(Set<Integer> states) {
        int i = 0;
        while (true) {
            if (i >= 10) {
                break;
            }
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth == null) {
                    break;
                } else if (states.contains(Integer.valueOf(this.mBluetooth.getState()))) {
                    this.mBluetoothLock.readLock().unlock();
                    return true;
                } else {
                    this.mBluetoothLock.readLock().unlock();
                    SystemClock.sleep(300L);
                    i++;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "getState()", e);
                Slog.e(TAG, "waitForState " + states + " time out");
                return false;
            } finally {
                this.mBluetoothLock.readLock().unlock();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendDisableMsg(int reason, String packageName) {
        BluetoothHandler bluetoothHandler = this.mHandler;
        bluetoothHandler.sendMessage(bluetoothHandler.obtainMessage(2));
        addActiveLog(reason, packageName, false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendEnableMsg(boolean quietMode, int reason, String packageName) {
        if (6 == reason) {
            Slog.d(TAG, "bluetooth enable on boot");
            BluetoothHandler bluetoothHandler = this.mHandler;
            bluetoothHandler.sendMessageDelayed(bluetoothHandler.obtainMessage(1, quietMode ? 1 : 0, 0), BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
            addActiveLog(reason, packageName, true);
            this.mLastEnabledTime = SystemClock.elapsedRealtime() + BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS;
            return;
        }
        BluetoothHandler bluetoothHandler2 = this.mHandler;
        bluetoothHandler2.sendMessage(bluetoothHandler2.obtainMessage(1, quietMode ? 1 : 0, 0));
        addActiveLog(reason, packageName, true);
        this.mLastEnabledTime = SystemClock.elapsedRealtime();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addActiveLog(int reason, String packageName, boolean enable) {
        synchronized (this.mActiveLogs) {
            if (this.mActiveLogs.size() > 20) {
                this.mActiveLogs.remove();
            }
            this.mActiveLogs.add(new ActiveLog(reason, packageName, enable, System.currentTimeMillis()));
        }
        int state = enable ? 1 : 2;
        StatsLog.write_non_chained(67, Binder.getCallingUid(), null, state, reason, packageName);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void addCrashLog() {
        synchronized (this.mCrashTimestamps) {
            if (this.mCrashTimestamps.size() == 100) {
                this.mCrashTimestamps.removeFirst();
            }
            this.mCrashTimestamps.add(Long.valueOf(System.currentTimeMillis()));
            this.mCrashes++;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void recoverBluetoothServiceFromError(boolean clearBle) {
        Slog.e(TAG, "recoverBluetoothServiceFromError");
        try {
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth != null) {
                    this.mBluetooth.unregisterCallback(this.mBluetoothCallback);
                }
            } catch (Throwable th) {
                this.mBluetoothLock.readLock().unlock();
                throw th;
            }
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to unregister", re);
        }
        this.mBluetoothLock.readLock().unlock();
        SystemClock.sleep(500L);
        addActiveLog(5, this.mContext.getPackageName(), false);
        handleDisable();
        waitForState(new HashSet(Arrays.asList(10)));
        sendBluetoothServiceDownCallback();
        try {
            this.mBluetoothLock.writeLock().lock();
            if (this.mBluetooth != null) {
                this.mBluetooth = null;
                this.mContext.unbindService(this.mConnection);
            }
            this.mBluetoothGatt = null;
            this.mBluetoothLock.writeLock().unlock();
            this.mHandler.removeMessages(60);
            this.mState = 10;
            if (clearBle) {
                clearBleApps();
            }
            this.mEnable = false;
            int i = this.mErrorRecoveryRetryCounter;
            this.mErrorRecoveryRetryCounter = i + 1;
            if (i < 6) {
                Message restartMsg = this.mHandler.obtainMessage(42);
                this.mHandler.sendMessageDelayed(restartMsg, BackupAgentTimeoutParameters.DEFAULT_QUOTA_EXCEEDED_TIMEOUT_MILLIS);
            }
        } catch (Throwable th2) {
            this.mBluetoothLock.writeLock().unlock();
            throw th2;
        }
    }

    private boolean isBluetoothDisallowed() {
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            return ((UserManager) this.mContext.getSystemService(UserManager.class)).hasUserRestriction("no_bluetooth", UserHandle.SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateOppLauncherComponentState(int userId, boolean bluetoothSharingDisallowed) {
        ComponentName oppLauncherComponent = new ComponentName("com.android.bluetooth", "com.android.bluetooth.opp.BluetoothOppLauncherActivity");
        int newState = bluetoothSharingDisallowed ? 2 : 0;
        try {
            IPackageManager imp = AppGlobals.getPackageManager();
            imp.setComponentEnabledSetting(oppLauncherComponent, newState, 1, userId);
        } catch (Exception e) {
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:46:0x01f6  */
    /* JADX WARN: Removed duplicated region for block: B:47:0x01fb  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void dump(java.io.FileDescriptor r20, java.io.PrintWriter r21, java.lang.String[] r22) {
        /*
            Method dump skipped, instructions count: 527
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.BluetoothManagerService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String getEnableDisableReasonString(int reason) {
        switch (reason) {
            case 1:
                return "APPLICATION_REQUEST";
            case 2:
                return "AIRPLANE_MODE";
            case 3:
                return "DISALLOWED";
            case 4:
                return "RESTARTED";
            case 5:
                return "START_ERROR";
            case 6:
                return "SYSTEM_BOOT";
            case 7:
                return "CRASH";
            case 8:
                return "USER_SWITCH";
            case 9:
                return "RESTORE_USER_SETTING";
            case 10:
                return "FACTORY_RESET";
            default:
                return "UNKNOWN[" + reason + "]";
        }
    }
}
