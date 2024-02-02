package com.android.server;

import android.app.AppGlobals;
import android.bluetooth.BluetoothAdapter;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
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
import com.android.internal.util.DumpUtils;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.display.DisplayTransformManager;
import com.android.server.pm.UserRestrictionsUtils;
import com.android.server.utils.PriorityDump;
import com.xiaopeng.server.aftersales.AfterSalesService;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
    private static final int CRASH_LOG_MAX_SIZE = 100;
    private static final boolean DBG = true;
    private static final int ERROR_RESTART_TIME_MS = 3000;
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
    private static final int SERVICE_RESTART_TIME_MS = 200;
    private static final String TAG = "BluetoothManagerService";
    private static final int TIMEOUT_BIND_MS = 3000;
    private static final int USER_SWITCHED_TIME_MS = 200;
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private boolean mEnableExternal;
    private long mLastEnabledTime;
    private final boolean mPermissionReviewRequired;
    private final RemoteCallbackList<IBluetoothStateChangeCallback> mStateChangeCallbacks;
    private final int mSystemUiUid;
    private boolean mScreenOn = false;
    final Executor executor = new Executor() { // from class: com.android.server.BluetoothManagerService.1
        @Override // java.util.concurrent.Executor
        public void execute(Runnable command) {
            if (command != null) {
                command.run();
            } else {
                Log.i(BluetoothManagerService.TAG, "command null");
            }
        }
    };
    private final ReentrantReadWriteLock mBluetoothLock = new ReentrantReadWriteLock();
    private boolean mQuietEnable = false;
    private final LinkedList<ActiveLog> mActiveLogs = new LinkedList<>();
    private final LinkedList<Long> mCrashTimestamps = new LinkedList<>();
    private Map<IBinder, ClientDeathRecipient> mBleApps = new ConcurrentHashMap();
    private final Map<Integer, ProfileServiceConnections> mProfileServices = new HashMap();
    private final IBluetoothCallback mBluetoothCallback = new IBluetoothCallback.Stub() { // from class: com.android.server.BluetoothManagerService.2
        public void onBluetoothStateChange(int prevState, int newState) throws RemoteException {
            Slog.i(BluetoothManagerService.TAG, "onBluetoothStateChange, prevState : " + prevState + " , newState : " + newState);
            Message msg = BluetoothManagerService.this.mHandler.obtainMessage(60, prevState, newState);
            BluetoothManagerService.this.mHandler.sendMessage(msg);
        }
    };
    private final UserManagerInternal.UserRestrictionsListener mUserRestrictionsListener = new UserManagerInternal.UserRestrictionsListener() { // from class: com.android.server.BluetoothManagerService.3
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
                BluetoothManagerService.this.sendDisableMsg(3, BluetoothManagerService.this.mContext.getPackageName());
            }
        }
    };
    private final ContentObserver mAirplaneModeObserver = new ContentObserver(null) { // from class: com.android.server.BluetoothManagerService.4
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
                    Slog.i(BluetoothManagerService.TAG, "Airplane Mode change : " + BluetoothManagerService.this.isAirplaneModeOn() + " ,  current state:  " + BluetoothAdapter.nameForState(st));
                    if (BluetoothManagerService.this.isAirplaneModeOn()) {
                        BluetoothManagerService.this.clearBleApps();
                        if (st == 15) {
                            try {
                                BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                                if (BluetoothManagerService.this.mBluetooth != null) {
                                    BluetoothManagerService.this.addActiveLog(2, BluetoothManagerService.this.mContext.getPackageName(), false);
                                    BluetoothManagerService.this.mBluetooth.onBrEdrDown();
                                    BluetoothManagerService.this.mEnable = false;
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
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.android.server.BluetoothManagerService.5
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
                        Message msg = BluetoothManagerService.this.mHandler.obtainMessage(500, newValue.equals("0") ? 0 : 1, 0);
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public BluetoothManagerService(Context context) {
        this.mContext = context;
        this.mPermissionReviewRequired = context.getResources().getBoolean(17957005);
        this.mEnableExternal = false;
        this.mContentResolver = context.getContentResolver();
        registerForBleScanModeChange();
        this.mCallbacks = new RemoteCallbackList<>();
        this.mStateChangeCallbacks = new RemoteCallbackList<>();
        String value = SystemProperties.get("persist.sys.fflag.override.settings_bluetooth_hearing_aid");
        if (!TextUtils.isEmpty(value)) {
            boolean isHearingAidEnabled = Boolean.parseBoolean(value);
            Log.v(TAG, "set feature flag HEARING_AID_SETTINGS to " + isHearingAidEnabled);
            FeatureFlagUtils.setEnabled(context, "settings_bluetooth_hearing_aid", isHearingAidEnabled);
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
        IntentFilter filter1 = new IntentFilter();
        filter1.addAction("android.intent.action.SCREEN_ON");
        filter1.addAction("android.intent.action.SCREEN_OFF");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.BluetoothManagerService.6
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                String action = intent.getAction();
                if (action.equals("android.intent.action.SCREEN_ON")) {
                    Slog.i(BluetoothManagerService.TAG, "recv screen on");
                    BluetoothManagerService.this.mScreenOn = true;
                } else if (action.equals("android.intent.action.SCREEN_OFF")) {
                    Slog.i(BluetoothManagerService.TAG, "recv screen off");
                    BluetoothManagerService.this.mScreenOn = false;
                }
            }
        }, filter1);
        try {
            boolean noHome = this.mContext.getResources().getBoolean(17957001);
            systemUiUid = noHome ? -1 : this.mContext.getPackageManager().getPackageUidAsUser(AfterSalesService.PackgeName.SYSTEMUI, 1048576, 0);
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
        return this.mContext.getResources().getBoolean(17957041);
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
        return this.mName != null && this.mAddress != null && this.mName.length() > 0 && this.mAddress.length() > 0;
    }

    private void loadStoredNameAndAddress() {
        Slog.d(TAG, "Loading stored name and address");
        if (this.mContext.getResources().getBoolean(17956899) && Settings.Secure.getInt(this.mContentResolver, SECURE_SETTINGS_BLUETOOTH_ADDR_VALID, 0) == 0) {
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

    private void registerForBleScanModeChange() {
        ContentObserver contentObserver = new ContentObserver(null) { // from class: com.android.server.BluetoothManagerService.7
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
        } else if (isBleAppPresent()) {
            try {
                this.mBluetoothGatt.unregAll();
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to disconnect all apps.", e);
            }
        } else {
            try {
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
    }

    public boolean enableNoAutoConnect(String packageName) {
        if (isBluetoothDisallowed()) {
            Slog.d(TAG, "enableNoAutoConnect(): not enabling - bluetooth disallowed");
            return false;
        }
        this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        Slog.d(TAG, "enableNoAutoConnect():  mBluetooth =" + this.mBluetooth + " mBinding = " + this.mBinding);
        int callingAppId = UserHandle.getAppId(Binder.getCallingUid());
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
            if (!checkIfCallerIsForegroundUser()) {
                Slog.w(TAG, "enable(): not allowed for non-active and non system user");
                return false;
            }
            this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
            if (!isEnabled() && this.mPermissionReviewRequired && startConsentUiIfNeeded(packageName, callingUid, "android.bluetooth.adapter.action.REQUEST_ENABLE")) {
                return false;
            }
        }
        Slog.i(TAG, "enable(" + packageName + "):  mBluetooth =" + this.mBluetooth + " mBinding = " + this.mBinding + " mState = " + BluetoothAdapter.nameForState(this.mState));
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
            if (!checkIfCallerIsForegroundUser()) {
                Slog.w(TAG, "disable(): not allowed for non-active and non system user");
                return false;
            }
            this.mContext.enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
            if (isEnabled() && this.mPermissionReviewRequired && startConsentUiIfNeeded(packageName, callingUid, "android.bluetooth.adapter.action.REQUEST_DISABLE")) {
                return false;
            }
        }
        Slog.i(TAG, "disable(): mBluetooth = " + this.mBluetooth + " mBinding = " + this.mBinding + ",package = " + packageName);
        synchronized (this.mReceiver) {
            if (persist) {
                try {
                    persistBluetoothSetting(0);
                } catch (Throwable th) {
                    throw th;
                }
            }
            this.mEnableExternal = false;
            sendDisableMsg(1, packageName);
        }
        return true;
    }

    private boolean startConsentUiIfNeeded(String packageName, int callingUid, String intentAction) throws RemoteException {
        if (checkBluetoothPermissionWhenPermissionReviewRequired()) {
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

    private boolean checkBluetoothPermissionWhenPermissionReviewRequired() {
        if (this.mPermissionReviewRequired) {
            int result = this.mContext.checkCallingPermission("android.permission.MANAGE_BLUETOOTH_WHEN_PERMISSION_REVIEW_REQUIRED");
            return result == 0;
        }
        return false;
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
            ProfileServiceConnections psc = this.mProfileServices.get(new Integer(bluetoothProfile));
            if (psc == null) {
                return;
            }
            psc.removeProxy(proxy);
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
            Message getMsg = this.mHandler.obtainMessage(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);
            this.mHandler.sendMessage(getMsg);
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
                    } else if (this.mIntent != null && this.mService == null && BluetoothManagerService.this.doBind(this.mIntent, this, 0, UserHandle.CURRENT_OR_SELF)) {
                        Message msg = BluetoothManagerService.this.mHandler.obtainMessage(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE);
                        msg.obj = this;
                        BluetoothManagerService.this.mHandler.sendMessageDelayed(msg, 3000L);
                        return true;
                    } else {
                        Slog.w(BluetoothManagerService.TAG, "Unable to bind with intent: " + this.mIntent);
                        return false;
                    }
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
            if (this.mService == null) {
                if (!BluetoothManagerService.this.mHandler.hasMessages(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE, this)) {
                    Message msg = BluetoothManagerService.this.mHandler.obtainMessage(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE);
                    msg.obj = this;
                    BluetoothManagerService.this.mHandler.sendMessage(msg);
                    return;
                }
                return;
            }
            try {
                proxy.onServiceConnected(this.mClassName, this.mService);
            } catch (RemoteException e) {
                Slog.e(BluetoothManagerService.TAG, "Unable to connect to proxy", e);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void removeProxy(IBluetoothProfileServiceConnection proxy) {
            if (proxy != null) {
                if (this.mProxies.unregister(proxy)) {
                    try {
                        proxy.onServiceDisconnected(this.mClassName);
                        return;
                    } catch (RemoteException e) {
                        Slog.e(BluetoothManagerService.TAG, "Unable to disconnect proxy", e);
                        return;
                    }
                }
                return;
            }
            Slog.w(BluetoothManagerService.TAG, "Trying to remove a null proxy");
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void removeAllProxies() {
            onServiceDisconnected(this.mClassName);
            this.mProxies.kill();
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
            if (this.mService == null) {
                return;
            }
            try {
                this.mService.unlinkToDeath(this, 0);
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
            BluetoothManagerService.this.mHandler.sendMessageDelayed(msg, 3000L);
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
            Slog.i(BluetoothManagerService.TAG, "BluetoothServiceConnection, connected: " + name);
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
            Slog.i(BluetoothManagerService.TAG, "BluetoothServiceConnection, disconnected: " + name);
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
            switch (msg.what) {
                case 1:
                    Slog.i(BluetoothManagerService.TAG, "MESSAGE_ENABLE(" + msg.arg1 + "): mBluetooth = " + BluetoothManagerService.this.mBluetooth);
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
                        Slog.e(BluetoothManagerService.TAG, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, e);
                    }
                    BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                    BluetoothManagerService.this.mQuietEnable = msg.arg1 == 1;
                    if (BluetoothManagerService.this.mBluetooth == null) {
                        BluetoothManagerService.this.handleEnable(BluetoothManagerService.this.mQuietEnable);
                        return;
                    }
                    BluetoothManagerService.this.waitForOnOff(false, true);
                    Message restartMsg = BluetoothManagerService.this.mHandler.obtainMessage(42);
                    BluetoothManagerService.this.mHandler.sendMessageDelayed(restartMsg, 400L);
                    return;
                case 2:
                    Slog.i(BluetoothManagerService.TAG, "MESSAGE_DISABLE: mBluetooth = " + BluetoothManagerService.this.mBluetooth);
                    BluetoothManagerService.this.mHandler.removeMessages(42);
                    if (!BluetoothManagerService.this.mEnable || BluetoothManagerService.this.mBluetooth == null) {
                        BluetoothManagerService.this.mEnable = false;
                        BluetoothManagerService.this.handleDisable();
                        return;
                    }
                    BluetoothManagerService.this.waitForOnOff(true, false);
                    BluetoothManagerService.this.mEnable = false;
                    BluetoothManagerService.this.handleDisable();
                    BluetoothManagerService.this.waitForOnOff(false, false);
                    return;
                case 20:
                    IBluetoothManagerCallback callback = (IBluetoothManagerCallback) msg.obj;
                    BluetoothManagerService.this.mCallbacks.register(callback);
                    return;
                case 21:
                    IBluetoothManagerCallback callback2 = (IBluetoothManagerCallback) msg.obj;
                    BluetoothManagerService.this.mCallbacks.unregister(callback2);
                    return;
                case 30:
                    IBluetoothStateChangeCallback callback3 = (IBluetoothStateChangeCallback) msg.obj;
                    BluetoothManagerService.this.mStateChangeCallbacks.register(callback3);
                    return;
                case 31:
                    IBluetoothStateChangeCallback callback4 = (IBluetoothStateChangeCallback) msg.obj;
                    BluetoothManagerService.this.mStateChangeCallbacks.unregister(callback4);
                    return;
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
                            Message getMsg = BluetoothManagerService.this.mHandler.obtainMessage(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE);
                            BluetoothManagerService.this.mHandler.sendMessage(getMsg);
                            if (this.mGetNameAddressOnly) {
                                return;
                            }
                        }
                        try {
                            BluetoothManagerService.this.mBluetooth.registerCallback(BluetoothManagerService.this.mBluetoothCallback);
                        } catch (RemoteException re) {
                            Slog.e(BluetoothManagerService.TAG, "Unable to register BluetoothCallback", re);
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
                        BluetoothManagerService.this.waitForOnOff(true, false);
                        BluetoothManagerService.this.handleDisable();
                        BluetoothManagerService.this.waitForOnOff(false, false);
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
                                BluetoothManagerService.this.addActiveLog(7, BluetoothManagerService.this.mContext.getPackageName(), false);
                                if (BluetoothManagerService.this.mEnable) {
                                    BluetoothManagerService.this.mEnable = false;
                                    Message restartMsg2 = BluetoothManagerService.this.mHandler.obtainMessage(42);
                                    BluetoothManagerService.this.mHandler.sendMessageDelayed(restartMsg2, 200L);
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
                    Slog.i(BluetoothManagerService.TAG, "MESSAGE_RESTART_BLUETOOTH_SERVICE");
                    BluetoothManagerService.this.mEnable = true;
                    BluetoothManagerService.this.addActiveLog(4, BluetoothManagerService.this.mContext.getPackageName(), true);
                    BluetoothManagerService.this.handleEnable(BluetoothManagerService.this.mQuietEnable);
                    return;
                case 60:
                    int prevState = msg.arg1;
                    int newState = msg.arg2;
                    Slog.i(BluetoothManagerService.TAG, "MESSAGE_BLUETOOTH_STATE_CHANGE: " + BluetoothAdapter.nameForState(prevState) + " > " + BluetoothAdapter.nameForState(newState));
                    BluetoothManagerService.this.mState = newState;
                    BluetoothManagerService.this.bluetoothStateChangeHandler(prevState, newState);
                    if (prevState == 14 && newState == 10 && BluetoothManagerService.this.mBluetooth != null && BluetoothManagerService.this.mEnable) {
                        BluetoothManagerService.this.recoverBluetoothServiceFromError(false);
                    }
                    if (prevState == 11 && newState == 15 && BluetoothManagerService.this.mBluetooth != null && BluetoothManagerService.this.mEnable) {
                        BluetoothManagerService.this.recoverBluetoothServiceFromError(true);
                    }
                    if (prevState == 16 && newState == 10 && BluetoothManagerService.this.mEnable) {
                        Slog.i(BluetoothManagerService.TAG, "Entering STATE_OFF but mEnabled is true; restarting.");
                        BluetoothManagerService.this.waitForOnOff(false, true);
                        Message restartMsg3 = BluetoothManagerService.this.mHandler.obtainMessage(42);
                        BluetoothManagerService.this.mHandler.sendMessageDelayed(restartMsg3, 400L);
                    }
                    if ((newState == 12 || newState == 15) && BluetoothManagerService.this.mErrorRecoveryRetryCounter != 0) {
                        Slog.w(BluetoothManagerService.TAG, "bluetooth is recovered from error");
                        BluetoothManagerService.this.mErrorRecoveryRetryCounter = 0;
                    }
                    if (((newState == 12 && prevState == 11) || (newState == 15 && prevState == 14)) && !BluetoothManagerService.this.mScreenOn && BluetoothManagerService.this.isAirplaneModeOn()) {
                        Slog.i(BluetoothManagerService.TAG, "bt/ble on while sceen off and airplane mode on, shutdown bt !");
                        BluetoothManagerService.this.clearBleApps();
                        try {
                            if (newState != 15) {
                                if (newState == 12) {
                                    BluetoothManagerService.this.sendDisableMsg(2, BluetoothManagerService.this.mContext.getPackageName());
                                    return;
                                }
                                return;
                            }
                            try {
                                BluetoothManagerService.this.mBluetoothLock.readLock().lock();
                                if (BluetoothManagerService.this.mBluetooth != null) {
                                    BluetoothManagerService.this.addActiveLog(2, BluetoothManagerService.this.mContext.getPackageName(), false);
                                    BluetoothManagerService.this.mBluetooth.onBrEdrDown();
                                    BluetoothManagerService.this.mEnable = false;
                                }
                            } catch (RemoteException e3) {
                                Slog.e(BluetoothManagerService.TAG, "Unable to call onBrEdrDown", e3);
                            }
                            return;
                        } finally {
                        }
                    }
                    return;
                case 100:
                    Slog.e(BluetoothManagerService.TAG, "MESSAGE_TIMEOUT_BIND");
                    BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                    BluetoothManagerService.this.mBinding = false;
                    return;
                case 101:
                    Slog.e(BluetoothManagerService.TAG, "MESSAGE_TIMEOUT_UNBIND");
                    BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                    BluetoothManagerService.this.mUnbinding = false;
                    return;
                case DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE /* 200 */:
                    Slog.d(BluetoothManagerService.TAG, "MESSAGE_GET_NAME_AND_ADDRESS");
                    try {
                        BluetoothManagerService.this.mBluetoothLock.writeLock().lock();
                        if (BluetoothManagerService.this.mBluetooth == null && !BluetoothManagerService.this.mBinding) {
                            Slog.d(BluetoothManagerService.TAG, "Binding to service to get name and address");
                            this.mGetNameAddressOnly = true;
                            Message timeoutMsg = BluetoothManagerService.this.mHandler.obtainMessage(100);
                            BluetoothManagerService.this.mHandler.sendMessageDelayed(timeoutMsg, 3000L);
                            Intent i = new Intent(IBluetooth.class.getName());
                            if (BluetoothManagerService.this.doBind(i, BluetoothManagerService.this.mConnection, 65, UserHandle.CURRENT)) {
                                BluetoothManagerService.this.mBinding = true;
                            } else {
                                BluetoothManagerService.this.mHandler.removeMessages(100);
                            }
                        } else if (BluetoothManagerService.this.mBluetooth != null) {
                            try {
                                BluetoothManagerService.this.storeNameAndAddress(BluetoothManagerService.this.mBluetooth.getName(), BluetoothManagerService.this.mBluetooth.getAddress());
                            } catch (RemoteException re2) {
                                Slog.e(BluetoothManagerService.TAG, "Unable to grab names", re2);
                            }
                            if (this.mGetNameAddressOnly && !BluetoothManagerService.this.mEnable) {
                                BluetoothManagerService.this.unbindAndFinish();
                            }
                            this.mGetNameAddressOnly = false;
                        }
                        return;
                    } finally {
                    }
                case 300:
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
                                } catch (RemoteException re3) {
                                    Slog.e(BluetoothManagerService.TAG, "Unable to unregister", re3);
                                }
                                BluetoothManagerService.this.mBluetoothLock.readLock().unlock();
                                if (BluetoothManagerService.this.mState == 13) {
                                    BluetoothManagerService.this.bluetoothStateChangeHandler(BluetoothManagerService.this.mState, 10);
                                    BluetoothManagerService.this.mState = 10;
                                }
                                if (BluetoothManagerService.this.mState == 10) {
                                    BluetoothManagerService.this.bluetoothStateChangeHandler(BluetoothManagerService.this.mState, 11);
                                    BluetoothManagerService.this.mState = 11;
                                }
                                BluetoothManagerService.this.waitForOnOff(true, false);
                                if (BluetoothManagerService.this.mState == 11) {
                                    BluetoothManagerService.this.bluetoothStateChangeHandler(BluetoothManagerService.this.mState, 12);
                                }
                                BluetoothManagerService.this.unbindAllBluetoothProfileServices();
                                BluetoothManagerService.this.addActiveLog(8, BluetoothManagerService.this.mContext.getPackageName(), false);
                                BluetoothManagerService.this.handleDisable();
                                BluetoothManagerService.this.bluetoothStateChangeHandler(12, 13);
                                boolean didDisableTimeout = !BluetoothManagerService.this.waitForOnOff(false, true);
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
                                        SystemClock.sleep(3000L);
                                    } else {
                                        SystemClock.sleep(100L);
                                    }
                                    BluetoothManagerService.this.mHandler.removeMessages(60);
                                    BluetoothManagerService.this.mState = 10;
                                    BluetoothManagerService.this.addActiveLog(8, BluetoothManagerService.this.mContext.getPackageName(), true);
                                    BluetoothManagerService.this.mEnable = true;
                                    BluetoothManagerService.this.handleEnable(BluetoothManagerService.this.mQuietEnable);
                                    return;
                                } finally {
                                }
                            }
                        } finally {
                        }
                    }
                    if (BluetoothManagerService.this.mBinding || BluetoothManagerService.this.mBluetooth != null) {
                        Message userMsg = BluetoothManagerService.this.mHandler.obtainMessage(300);
                        userMsg.arg2 = 1 + msg.arg2;
                        BluetoothManagerService.this.mHandler.sendMessageDelayed(userMsg, 200L);
                        Slog.d(BluetoothManagerService.TAG, "Retry MESSAGE_USER_SWITCHED " + userMsg.arg2);
                        return;
                    }
                    return;
                case BluetoothManagerService.MESSAGE_USER_UNLOCKED /* 301 */:
                    Slog.d(BluetoothManagerService.TAG, "MESSAGE_USER_UNLOCKED");
                    BluetoothManagerService.this.mHandler.removeMessages(300);
                    if (BluetoothManagerService.this.mEnable && !BluetoothManagerService.this.mBinding && BluetoothManagerService.this.mBluetooth == null) {
                        Slog.d(BluetoothManagerService.TAG, "Enabled but not bound; retrying after unlock");
                        BluetoothManagerService.this.handleEnable(BluetoothManagerService.this.mQuietEnable);
                        return;
                    }
                    return;
                case BluetoothManagerService.MESSAGE_ADD_PROXY_DELAYED /* 400 */:
                    ProfileServiceConnections psc = (ProfileServiceConnections) BluetoothManagerService.this.mProfileServices.get(Integer.valueOf(msg.arg1));
                    if (psc == null) {
                        return;
                    }
                    IBluetoothProfileServiceConnection proxy = (IBluetoothProfileServiceConnection) msg.obj;
                    psc.addProxy(proxy);
                    return;
                case BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE /* 401 */:
                    ProfileServiceConnections psc2 = (ProfileServiceConnections) msg.obj;
                    removeMessages(BluetoothManagerService.MESSAGE_BIND_PROFILE_SERVICE, msg.obj);
                    if (psc2 == null) {
                        return;
                    }
                    psc2.bindService();
                    return;
                case 500:
                    if (msg.arg1 == 0 && BluetoothManagerService.this.mEnable) {
                        Slog.d(BluetoothManagerService.TAG, "Restore Bluetooth state to disabled");
                        BluetoothManagerService.this.persistBluetoothSetting(0);
                        BluetoothManagerService.this.mEnableExternal = false;
                        BluetoothManagerService.this.sendDisableMsg(9, BluetoothManagerService.this.mContext.getPackageName());
                        return;
                    } else if (msg.arg1 != 1 || BluetoothManagerService.this.mEnable) {
                        return;
                    } else {
                        Slog.d(BluetoothManagerService.TAG, "Restore Bluetooth state to enabled");
                        BluetoothManagerService.this.mQuietEnableExternal = false;
                        BluetoothManagerService.this.mEnableExternal = true;
                        BluetoothManagerService.this.sendEnableMsg(false, 9, BluetoothManagerService.this.mContext.getPackageName());
                        return;
                    }
                default:
                    return;
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
                this.mHandler.sendMessageDelayed(timeoutMsg, 3000L);
                Intent i = new Intent(IBluetooth.class.getName());
                if (!doBind(i, this.mConnection, 65, UserHandle.CURRENT)) {
                    this.mHandler.removeMessages(100);
                } else {
                    this.mBinding = true;
                }
            } else if (this.mBluetooth != null) {
                try {
                    if (!this.mQuietEnable) {
                        Slog.i(TAG, "handleEnable()");
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
        if (comp == null || !this.mContext.bindServiceAsUser(intent, conn, flags, this.executor, user)) {
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
                    Slog.i(TAG, "Sending off request.");
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

    /* JADX WARN: Removed duplicated region for block: B:19:0x003e A[Catch: all -> 0x0072, TRY_LEAVE, TryCatch #0 {all -> 0x0072, blocks: (B:7:0x0028, B:12:0x0034, B:19:0x003e), top: B:25:0x0028 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
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
            r9 = r8
            int r10 = android.app.ActivityManager.getCurrentUser()     // Catch: java.lang.Throwable -> L72
            if (r0 == r10) goto L3a
            if (r6 == r10) goto L3a
            r11 = 1027(0x403, float:1.439E-42)
            if (r7 == r11) goto L3a
            int r11 = r13.mSystemUiUid     // Catch: java.lang.Throwable -> L72
            if (r7 != r11) goto L39
            goto L3a
        L39:
            goto L3b
        L3a:
            r8 = 1
        L3b:
            r9 = r8
            if (r9 != 0) goto L6c
            java.lang.String r8 = "BluetoothManagerService"
            java.lang.StringBuilder r11 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L72
            r11.<init>()     // Catch: java.lang.Throwable -> L72
            java.lang.String r12 = "checkIfCallerIsForegroundUser: valid="
            r11.append(r12)     // Catch: java.lang.Throwable -> L72
            r11.append(r9)     // Catch: java.lang.Throwable -> L72
            java.lang.String r12 = " callingUser="
            r11.append(r12)     // Catch: java.lang.Throwable -> L72
            r11.append(r0)     // Catch: java.lang.Throwable -> L72
            java.lang.String r12 = " parentUser="
            r11.append(r12)     // Catch: java.lang.Throwable -> L72
            r11.append(r6)     // Catch: java.lang.Throwable -> L72
            java.lang.String r12 = " foregroundUser="
            r11.append(r12)     // Catch: java.lang.Throwable -> L72
            r11.append(r10)     // Catch: java.lang.Throwable -> L72
            java.lang.String r11 = r11.toString()     // Catch: java.lang.Throwable -> L72
            android.util.Slog.d(r8, r11)     // Catch: java.lang.Throwable -> L72
        L6c:
            android.os.Binder.restoreCallingIdentity(r2)
            r8 = r10
            return r9
        L72:
            r8 = move-exception
            android.os.Binder.restoreCallingIdentity(r2)
            throw r8
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
                r2 = false;
            }
            boolean intermediate_off = r2;
            if (newState == 10) {
                Slog.d(TAG, "Bluetooth is complete send Service Down");
                sendBluetoothServiceDownCallback();
                unbindAndFinish();
                sendBleStateChanged(prevState, newState);
                isStandardBroadcast = false;
            } else if (!intermediate_off) {
                Slog.d(TAG, "Bluetooth is in LE only mode");
                if (this.mBluetoothGatt != null || !this.mContext.getPackageManager().hasSystemFeature("android.hardware.bluetooth_le")) {
                    continueFromBleOnState();
                } else {
                    Slog.d(TAG, "Binding Bluetooth GATT service");
                    Intent i = new Intent(IBluetoothGatt.class.getName());
                    doBind(i, this.mConnection, 65, UserHandle.CURRENT);
                }
                sendBleStateChanged(prevState, newState);
                isStandardBroadcast = false;
            } else if (intermediate_off) {
                Slog.d(TAG, "Intermediate off, back to LE only mode");
                sendBleStateChanged(prevState, newState);
                sendBluetoothStateCallback(false);
                newState = 10;
                sendBrEdrDownCallback();
            }
        } else if (newState == 12) {
            boolean isUp = newState == 12;
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
    public boolean waitForOnOff(boolean on, boolean off) {
        int i = 0;
        while (true) {
            if (i >= 10) {
                break;
            }
            try {
                this.mBluetoothLock.readLock().lock();
                if (this.mBluetooth == null) {
                    break;
                }
                if (on) {
                    if (this.mBluetooth.getState() == 12) {
                        return true;
                    }
                } else if (off) {
                    if (this.mBluetooth.getState() == 10) {
                        return true;
                    }
                } else if (this.mBluetooth.getState() != 12) {
                    return true;
                }
                this.mBluetoothLock.readLock().unlock();
                if (on || off) {
                    SystemClock.sleep(300L);
                } else {
                    SystemClock.sleep(50L);
                }
                i++;
            } catch (RemoteException e) {
                Slog.e(TAG, "getState()", e);
                Slog.e(TAG, "waitForOnOff time out");
                return false;
            } finally {
                this.mBluetoothLock.readLock().unlock();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendDisableMsg(int reason, String packageName) {
        Slog.i(TAG, "sendDisableMsg, reason : " + reason + ", packageName :" + packageName);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(2));
        addActiveLog(reason, packageName, false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendEnableMsg(boolean quietMode, int reason, String packageName) {
        Slog.i(TAG, "sendEnableMsg, packageName :" + packageName + " , quietMode : " + quietMode);
        this.mHandler.sendMessage(this.mHandler.obtainMessage(1, quietMode ? 1 : 0, 0));
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
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to unregister", re);
            }
            this.mBluetoothLock.readLock().unlock();
            SystemClock.sleep(500L);
            addActiveLog(5, this.mContext.getPackageName(), false);
            handleDisable();
            waitForOnOff(false, true);
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
                    this.mHandler.sendMessageDelayed(restartMsg, 3000L);
                }
            } catch (Throwable th) {
                this.mBluetoothLock.writeLock().unlock();
                throw th;
            }
        } catch (Throwable th2) {
            this.mBluetoothLock.readLock().unlock();
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

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        String[] args2 = args;
        if (!DumpUtils.checkDumpPermission(this.mContext, TAG, writer)) {
            return;
        }
        String errorMsg = null;
        boolean protoOut = args2.length > 0 && args2[0].startsWith(PriorityDump.PROTO_ARG);
        if (!protoOut) {
            writer.println("Bluetooth Status");
            writer.println("  enabled: " + isEnabled());
            writer.println("  state: " + BluetoothAdapter.nameForState(this.mState));
            writer.println("  address: " + this.mAddress);
            writer.println("  name: " + this.mName);
            if (this.mEnable) {
                long onDuration = SystemClock.elapsedRealtime() - this.mLastEnabledTime;
                String onDurationString = String.format(Locale.US, "%02d:%02d:%02d.%03d", Integer.valueOf((int) (onDuration / 3600000)), Integer.valueOf((int) ((onDuration / 60000) % 60)), Integer.valueOf((int) ((onDuration / 1000) % 60)), Integer.valueOf((int) (onDuration % 1000)));
                writer.println("  time since enabled: " + onDurationString);
            }
            if (this.mActiveLogs.size() == 0) {
                writer.println("\nBluetooth never enabled!");
            } else {
                writer.println("\nEnable log:");
                Iterator<ActiveLog> it = this.mActiveLogs.iterator();
                while (it.hasNext()) {
                    ActiveLog log = it.next();
                    writer.println("  " + log);
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("\nBluetooth crashed ");
            sb.append(this.mCrashes);
            sb.append(" time");
            sb.append(this.mCrashes == 1 ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "s");
            writer.println(sb.toString());
            if (this.mCrashes == 100) {
                writer.println("(last 100)");
            }
            Iterator<Long> it2 = this.mCrashTimestamps.iterator();
            while (it2.hasNext()) {
                Long time = it2.next();
                writer.println("  " + ((Object) timeToLog(time.longValue())));
            }
            StringBuilder sb2 = new StringBuilder();
            sb2.append("\n");
            sb2.append(this.mBleApps.size());
            sb2.append(" BLE app");
            sb2.append(this.mBleApps.size() == 1 ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "s");
            sb2.append("registered");
            writer.println(sb2.toString());
            for (ClientDeathRecipient app : this.mBleApps.values()) {
                writer.println("  " + app.getPackageName());
            }
            writer.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            writer.flush();
            if (args2.length == 0) {
                args2 = new String[]{"--print"};
            }
        }
        String[] args3 = args2;
        if (this.mBluetoothBinder == null) {
            errorMsg = "Bluetooth Service not connected";
        } else {
            try {
                this.mBluetoothBinder.dump(fd, args3);
            } catch (RemoteException e) {
                errorMsg = "RemoteException while dumping Bluetooth Service";
                if (errorMsg != null) {
                    return;
                }
                return;
            }
        }
        if (errorMsg != null || protoOut) {
            return;
        }
        writer.println(errorMsg);
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
            default:
                return "UNKNOWN[" + reason + "]";
        }
    }
}
