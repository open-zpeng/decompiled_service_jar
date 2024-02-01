package com.android.server;

import android.app.ActivityManager;
import android.app.IApplicationThread;
import android.app.IUiModeManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.icu.impl.CalendarAstronomer;
import android.icu.util.Calendar;
import android.location.Location;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.Sandman;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Slog;
import com.android.internal.app.DisableCarModeActivity;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.server.pm.DumpState;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import java.io.FileDescriptor;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class UiModeManagerService extends SystemService {
    private static final boolean ENABLE_LAUNCH_DESK_DOCK_APP = true;
    private static final boolean LOG = false;
    private static final String TAG = UiModeManager.class.getSimpleName();
    private final BroadcastReceiver mBatteryReceiver;
    private int mCarModeEnableFlags;
    private boolean mCarModeEnabled;
    private boolean mCarModeKeepsScreenOn;
    private boolean mCharging;
    private boolean mComputedNightMode;
    private Configuration mConfiguration;
    int mCurUiMode;
    private int mDefaultUiModeType;
    private boolean mDeskModeKeepsScreenOn;
    private final BroadcastReceiver mDockModeReceiver;
    private int mDockState;
    private boolean mEnableCarDockLaunch;
    private final Handler mHandler;
    private boolean mHoldingConfiguration;
    private int mLastBroadcastState;
    final Object mLock;
    private int mNightMode;
    private boolean mNightModeLocked;
    private NotificationManager mNotificationManager;
    private final BroadcastReceiver mResultReceiver;
    private final IUiModeManager.Stub mService;
    private int mSetUiMode;
    private StatusBarManager mStatusBarManager;
    boolean mSystemReady;
    private boolean mTelevision;
    private UiThemeManager mThemeManager;
    private final TwilightListener mTwilightListener;
    private TwilightManager mTwilightManager;
    private boolean mUiModeLocked;
    private boolean mVrHeadset;
    private final IVrStateCallbacks mVrStateCallbacks;
    private PowerManager.WakeLock mWakeLock;
    private boolean mWatch;

    public UiModeManagerService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mDockState = 0;
        this.mLastBroadcastState = 0;
        this.mNightMode = 1;
        this.mCarModeEnabled = false;
        this.mCharging = false;
        this.mEnableCarDockLaunch = true;
        this.mUiModeLocked = false;
        this.mNightModeLocked = false;
        this.mCurUiMode = 0;
        this.mSetUiMode = 0;
        this.mHoldingConfiguration = false;
        this.mConfiguration = new Configuration();
        this.mHandler = new Handler();
        this.mResultReceiver = new BroadcastReceiver() { // from class: com.android.server.UiModeManagerService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if (getResultCode() != -1) {
                    return;
                }
                int enableFlags = intent.getIntExtra("enableFlags", 0);
                int disableFlags = intent.getIntExtra("disableFlags", 0);
                synchronized (UiModeManagerService.this.mLock) {
                    UiModeManagerService.this.updateAfterBroadcastLocked(intent.getAction(), enableFlags, disableFlags);
                }
            }
        };
        this.mDockModeReceiver = new BroadcastReceiver() { // from class: com.android.server.UiModeManagerService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                int state = intent.getIntExtra("android.intent.extra.DOCK_STATE", 0);
                UiModeManagerService.this.updateDockState(state);
            }
        };
        this.mBatteryReceiver = new BroadcastReceiver() { // from class: com.android.server.UiModeManagerService.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                UiModeManagerService.this.mCharging = intent.getIntExtra("plugged", 0) != 0;
                synchronized (UiModeManagerService.this.mLock) {
                    if (UiModeManagerService.this.mSystemReady) {
                        UiModeManagerService.this.updateLocked(0, 0);
                    }
                }
            }
        };
        this.mTwilightListener = new TwilightListener() { // from class: com.android.server.UiModeManagerService.4
            @Override // com.android.server.twilight.TwilightListener
            public void onTwilightStateChanged(TwilightState state) {
                synchronized (UiModeManagerService.this.mLock) {
                    if (UiModeManagerService.this.mNightMode == 0) {
                        UiModeManagerService.this.updateComputedNightModeLocked();
                        UiModeManagerService.this.updateLocked(0, 0);
                    }
                }
            }
        };
        this.mVrStateCallbacks = new IVrStateCallbacks.Stub() { // from class: com.android.server.UiModeManagerService.5
            public void onVrStateChanged(boolean enabled) {
                synchronized (UiModeManagerService.this.mLock) {
                    UiModeManagerService.this.mVrHeadset = enabled;
                    if (UiModeManagerService.this.mSystemReady) {
                        UiModeManagerService.this.updateLocked(0, 0);
                    }
                }
            }
        };
        this.mService = new IUiModeManager.Stub() { // from class: com.android.server.UiModeManagerService.6
            public void enableCarMode(int flags) {
                if (isUiModeLocked()) {
                    Slog.e(UiModeManagerService.TAG, "enableCarMode while UI mode is locked");
                    return;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.setCarModeLocked(true, flags);
                        if (UiModeManagerService.this.mSystemReady) {
                            UiModeManagerService.this.updateLocked(flags, 0);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public void disableCarMode(int flags) {
                if (isUiModeLocked()) {
                    Slog.e(UiModeManagerService.TAG, "disableCarMode while UI mode is locked");
                    return;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.setCarModeLocked(false, 0);
                        if (UiModeManagerService.this.mSystemReady) {
                            UiModeManagerService.this.updateLocked(0, flags);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public int getCurrentModeType() {
                int i;
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        i = UiModeManagerService.this.mCurUiMode & 15;
                    }
                    return i;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public void setNightMode(int mode) {
                if (isNightModeLocked() && UiModeManagerService.this.getContext().checkCallingOrSelfPermission("android.permission.MODIFY_DAY_NIGHT_MODE") != 0) {
                    Slog.e(UiModeManagerService.TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission");
                    return;
                }
                switch (mode) {
                    case 0:
                    case 1:
                    case 2:
                        long ident = Binder.clearCallingIdentity();
                        try {
                            synchronized (UiModeManagerService.this.mLock) {
                                if (UiModeManagerService.this.mNightMode != mode) {
                                    Settings.Secure.putInt(UiModeManagerService.this.getContext().getContentResolver(), "ui_night_mode", mode);
                                    UiModeManagerService.this.mNightMode = mode;
                                    UiModeManagerService.this.updateLocked(0, 0);
                                }
                            }
                            return;
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    default:
                        throw new IllegalArgumentException("Unknown mode: " + mode);
                }
            }

            public int getNightMode() {
                int i;
                synchronized (UiModeManagerService.this.mLock) {
                    i = UiModeManagerService.this.mNightMode;
                }
                return i;
            }

            public boolean isUiModeLocked() {
                boolean z;
                synchronized (UiModeManagerService.this.mLock) {
                    z = UiModeManagerService.this.mUiModeLocked;
                }
                return z;
            }

            public boolean isNightModeLocked() {
                boolean z;
                synchronized (UiModeManagerService.this.mLock) {
                    z = UiModeManagerService.this.mNightModeLocked;
                }
                return z;
            }

            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                new Shell(UiModeManagerService.this.mService).exec(UiModeManagerService.this.mService, in, out, err, args, callback, resultReceiver);
            }

            protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
                if (DumpUtils.checkDumpPermission(UiModeManagerService.this.getContext(), UiModeManagerService.TAG, pw)) {
                    UiModeManagerService.this.dumpImpl(pw);
                }
            }

            public int getThemeMode() {
                int themeMode;
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        themeMode = UiModeManagerService.this.mThemeManager.getThemeMode();
                    }
                    return themeMode;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public int getDayNightMode() {
                int dayNightMode;
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        dayNightMode = UiModeManagerService.this.mThemeManager.getDayNightMode();
                    }
                    return dayNightMode;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public int getDayNightAutoMode() {
                int dayNightAutoMode;
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        dayNightAutoMode = UiModeManagerService.this.mThemeManager.getDayNightAutoMode();
                    }
                    return dayNightAutoMode;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public void applyThemeMode(int themeMode) {
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.mThemeManager.applyThemeMode(themeMode);
                    }
                } catch (Exception e) {
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(ident);
                    throw th;
                }
                Binder.restoreCallingIdentity(ident);
            }

            public void applyDayNightMode(int daynightMode) {
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.mThemeManager.applyDayNightMode(daynightMode);
                    }
                } catch (Exception e) {
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(ident);
                    throw th;
                }
                Binder.restoreCallingIdentity(ident);
            }

            public boolean isThemeWorking() {
                boolean isThemeWorking;
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        isThemeWorking = UiModeManagerService.this.mThemeManager.isThemeWorking();
                    }
                    return isThemeWorking;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            public long[] getTwilightState(Location location, long timeMillis) {
                String str = UiModeManagerService.TAG;
                Slog.d(str, "getTwilightState: location=" + location + " " + timeMillis);
                if (location == null) {
                    Slog.i(UiModeManagerService.TAG, "getTwilightState: bad param");
                    return new long[]{0, 0};
                }
                CalendarAstronomer ca = new CalendarAstronomer(location.getLongitude(), location.getLatitude());
                Calendar noon = Calendar.getInstance();
                noon.setTimeInMillis(timeMillis);
                noon.set(11, 12);
                noon.set(12, 0);
                noon.set(13, 0);
                noon.set(14, 0);
                ca.setTime(noon.getTimeInMillis());
                long sunriseTimeMillis = ca.getSunRiseSet(true);
                long sunsetTimeMillis = ca.getSunRiseSet(false);
                if (sunsetTimeMillis < timeMillis) {
                    noon.add(5, 1);
                    ca.setTime(noon.getTimeInMillis());
                    sunriseTimeMillis = ca.getSunRiseSet(true);
                } else if (sunriseTimeMillis > timeMillis) {
                    noon.add(5, -1);
                    ca.setTime(noon.getTimeInMillis());
                    sunsetTimeMillis = ca.getSunRiseSet(false);
                }
                return new long[]{sunriseTimeMillis, sunsetTimeMillis};
            }

            public Bitmap screenshot(Rect sourceCrop, int width, int height, int rotation) {
                Bitmap screenshot;
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        screenshot = UiModeManagerService.this.mThemeManager.screenshot(sourceCrop, width, height, rotation);
                    }
                    return screenshot;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        };
    }

    private static Intent buildHomeIntent(String category) {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory(category);
        intent.setFlags(270532608);
        return intent;
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        Context context = getContext();
        PowerManager powerManager = (PowerManager) context.getSystemService("power");
        this.mWakeLock = powerManager.newWakeLock(26, TAG);
        context.registerReceiver(this.mDockModeReceiver, new IntentFilter("android.intent.action.DOCK_EVENT"));
        context.registerReceiver(this.mBatteryReceiver, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
        this.mConfiguration.setToDefaults();
        Resources res = context.getResources();
        this.mDefaultUiModeType = res.getInteger(17694773);
        boolean z = false;
        this.mCarModeKeepsScreenOn = res.getInteger(17694754) == 1;
        this.mDeskModeKeepsScreenOn = res.getInteger(17694775) == 1;
        this.mEnableCarDockLaunch = res.getBoolean(17956954);
        this.mUiModeLocked = res.getBoolean(17956995);
        this.mNightModeLocked = res.getBoolean(17956994);
        PackageManager pm = context.getPackageManager();
        if (pm.hasSystemFeature("android.hardware.type.television") || pm.hasSystemFeature("android.software.leanback")) {
            z = true;
        }
        this.mTelevision = z;
        this.mWatch = pm.hasSystemFeature("android.hardware.type.watch");
        int defaultNightMode = res.getInteger(17694768);
        this.mNightMode = Settings.Secure.getInt(context.getContentResolver(), "ui_night_mode", defaultNightMode);
        SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.-$$Lambda$UiModeManagerService$SMGExVQCkMpTx7aAoJee7KHGMA0
            @Override // java.lang.Runnable
            public final void run() {
                UiModeManagerService.lambda$onStart$0(UiModeManagerService.this);
            }
        }, TAG + ".onStart");
        publishBinderService("uimode", this.mService);
        this.mThemeManager = new UiThemeManager(context);
        this.mThemeManager.onStart();
    }

    public static /* synthetic */ void lambda$onStart$0(UiModeManagerService uiModeManagerService) {
        synchronized (uiModeManagerService.mLock) {
            uiModeManagerService.updateConfigurationLocked();
            uiModeManagerService.sendConfigurationLocked();
        }
    }

    void dumpImpl(PrintWriter pw) {
        synchronized (this.mLock) {
            pw.println("Current UI Mode Service state:");
            pw.print("  mDockState=");
            pw.print(this.mDockState);
            pw.print(" mLastBroadcastState=");
            pw.println(this.mLastBroadcastState);
            pw.print("  mNightMode=");
            pw.print(this.mNightMode);
            pw.print(" mNightModeLocked=");
            pw.print(this.mNightModeLocked);
            pw.print(" mCarModeEnabled=");
            pw.print(this.mCarModeEnabled);
            pw.print(" mComputedNightMode=");
            pw.print(this.mComputedNightMode);
            pw.print(" mCarModeEnableFlags=");
            pw.print(this.mCarModeEnableFlags);
            pw.print(" mEnableCarDockLaunch=");
            pw.println(this.mEnableCarDockLaunch);
            pw.print("  mCurUiMode=0x");
            pw.print(Integer.toHexString(this.mCurUiMode));
            pw.print(" mUiModeLocked=");
            pw.print(this.mUiModeLocked);
            pw.print(" mSetUiMode=0x");
            pw.println(Integer.toHexString(this.mSetUiMode));
            pw.print("  mHoldingConfiguration=");
            pw.print(this.mHoldingConfiguration);
            pw.print(" mSystemReady=");
            pw.println(this.mSystemReady);
            if (this.mTwilightManager != null) {
                pw.print("  mTwilightService.getLastTwilightState()=");
                pw.println(this.mTwilightManager.getLastTwilightState());
            }
        }
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            synchronized (this.mLock) {
                this.mTwilightManager = (TwilightManager) getLocalService(TwilightManager.class);
                boolean z = true;
                this.mSystemReady = true;
                if (this.mDockState != 2) {
                    z = false;
                }
                this.mCarModeEnabled = z;
                updateComputedNightModeLocked();
                registerVrStateListener();
                updateLocked(0, 0);
                this.mThemeManager.initTwilightManager(this.mTwilightManager);
            }
        }
    }

    void setCarModeLocked(boolean enabled, int flags) {
        if (this.mCarModeEnabled != enabled) {
            this.mCarModeEnabled = enabled;
        }
        this.mCarModeEnableFlags = flags;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateDockState(int newState) {
        synchronized (this.mLock) {
            if (newState != this.mDockState) {
                this.mDockState = newState;
                setCarModeLocked(this.mDockState == 2, 0);
                if (this.mSystemReady) {
                    updateLocked(1, 0);
                }
            }
        }
    }

    private static boolean isDeskDockState(int state) {
        if (state != 1) {
            switch (state) {
                case 3:
                case 4:
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    private void updateConfigurationLocked() {
        int uiMode;
        int uiMode2 = this.mDefaultUiModeType;
        if (!this.mUiModeLocked) {
            if (this.mTelevision) {
                uiMode2 = 4;
            } else if (this.mWatch) {
                uiMode2 = 6;
            } else if (this.mCarModeEnabled) {
                uiMode2 = 3;
            } else if (isDeskDockState(this.mDockState)) {
                uiMode2 = 2;
            } else if (this.mVrHeadset) {
                uiMode2 = 7;
            }
        }
        if (this.mNightMode == 0) {
            TwilightManager twilightManager = this.mTwilightManager;
            updateComputedNightModeLocked();
            uiMode = uiMode2 | (this.mComputedNightMode ? 32 : 16);
        } else {
            TwilightManager twilightManager2 = this.mTwilightManager;
            uiMode = uiMode2 | (this.mNightMode << 4);
        }
        this.mCurUiMode = uiMode;
        if (!this.mHoldingConfiguration) {
            this.mConfiguration.uiMode = uiMode;
            this.mConfiguration.uiMode |= 64;
        }
    }

    private void sendConfigurationLocked() {
        if (this.mSetUiMode != this.mConfiguration.uiMode) {
            this.mSetUiMode = this.mConfiguration.uiMode;
            try {
                ActivityManager.getService().updateConfiguration(this.mConfiguration);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure communicating with activity manager", e);
            }
        }
    }

    void updateLocked(int enableFlags, int disableFlags) {
        String action = null;
        String oldAction = null;
        if (this.mLastBroadcastState == 2) {
            adjustStatusBarCarModeLocked();
            oldAction = UiModeManager.ACTION_EXIT_CAR_MODE;
        } else if (isDeskDockState(this.mLastBroadcastState)) {
            oldAction = UiModeManager.ACTION_EXIT_DESK_MODE;
        }
        if (this.mCarModeEnabled) {
            if (this.mLastBroadcastState != 2) {
                adjustStatusBarCarModeLocked();
                if (oldAction != null) {
                    sendForegroundBroadcastToAllUsers(oldAction);
                }
                this.mLastBroadcastState = 2;
                action = UiModeManager.ACTION_ENTER_CAR_MODE;
            }
        } else if (isDeskDockState(this.mDockState)) {
            if (!isDeskDockState(this.mLastBroadcastState)) {
                if (oldAction != null) {
                    sendForegroundBroadcastToAllUsers(oldAction);
                }
                this.mLastBroadcastState = this.mDockState;
                action = UiModeManager.ACTION_ENTER_DESK_MODE;
            }
        } else {
            this.mLastBroadcastState = 0;
            action = oldAction;
        }
        boolean keepScreenOn = true;
        if (action != null) {
            Intent intent = new Intent(action);
            intent.putExtra("enableFlags", enableFlags);
            intent.putExtra("disableFlags", disableFlags);
            intent.addFlags(268435456);
            getContext().sendOrderedBroadcastAsUser(intent, UserHandle.CURRENT, null, this.mResultReceiver, null, -1, null, null);
            this.mHoldingConfiguration = true;
            updateConfigurationLocked();
        } else {
            String category = null;
            if (this.mCarModeEnabled) {
                if (this.mEnableCarDockLaunch && (enableFlags & 1) != 0) {
                    category = "android.intent.category.CAR_DOCK";
                }
            } else if (isDeskDockState(this.mDockState)) {
                if ((enableFlags & 1) != 0) {
                    category = "android.intent.category.DESK_DOCK";
                }
            } else if ((disableFlags & 1) != 0) {
                category = "android.intent.category.HOME";
            }
            sendConfigurationAndStartDreamOrDockAppLocked(category);
        }
        if (!this.mCharging || ((!this.mCarModeEnabled || !this.mCarModeKeepsScreenOn || (this.mCarModeEnableFlags & 2) != 0) && (this.mCurUiMode != 2 || !this.mDeskModeKeepsScreenOn))) {
            keepScreenOn = false;
        }
        if (keepScreenOn != this.mWakeLock.isHeld()) {
            if (keepScreenOn) {
                this.mWakeLock.acquire();
            } else {
                this.mWakeLock.release();
            }
        }
    }

    private void sendForegroundBroadcastToAllUsers(String action) {
        getContext().sendBroadcastAsUser(new Intent(action).addFlags(268435456), UserHandle.ALL);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateAfterBroadcastLocked(String action, int enableFlags, int disableFlags) {
        String category = null;
        if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(action)) {
            if (this.mEnableCarDockLaunch && (enableFlags & 1) != 0) {
                category = "android.intent.category.CAR_DOCK";
            }
        } else if (UiModeManager.ACTION_ENTER_DESK_MODE.equals(action)) {
            if ((enableFlags & 1) != 0) {
                category = "android.intent.category.DESK_DOCK";
            }
        } else if ((disableFlags & 1) != 0) {
            category = "android.intent.category.HOME";
        }
        sendConfigurationAndStartDreamOrDockAppLocked(category);
    }

    private void sendConfigurationAndStartDreamOrDockAppLocked(String category) {
        this.mHoldingConfiguration = false;
        updateConfigurationLocked();
        boolean dockAppStarted = false;
        if (category != null) {
            Intent homeIntent = buildHomeIntent(category);
            if (Sandman.shouldStartDockApp(getContext(), homeIntent)) {
                try {
                    int result = ActivityManager.getService().startActivityWithConfig((IApplicationThread) null, (String) null, homeIntent, (String) null, (IBinder) null, (String) null, 0, 0, this.mConfiguration, (Bundle) null, -2);
                    if (ActivityManager.isStartResultSuccessful(result)) {
                        dockAppStarted = true;
                    } else if (result != -91) {
                        String str = TAG;
                        Slog.e(str, "Could not start dock app: " + homeIntent + ", startActivityWithConfig result " + result);
                    }
                } catch (RemoteException ex) {
                    String str2 = TAG;
                    Slog.e(str2, "Could not start dock app: " + homeIntent, ex);
                }
            }
        }
        sendConfigurationLocked();
        if (category != null && !dockAppStarted) {
            Sandman.startDreamWhenDockedIfAppropriate(getContext());
        }
    }

    private void adjustStatusBarCarModeLocked() {
        int i;
        Context context = getContext();
        if (this.mStatusBarManager == null) {
            this.mStatusBarManager = (StatusBarManager) context.getSystemService("statusbar");
        }
        if (this.mStatusBarManager != null) {
            StatusBarManager statusBarManager = this.mStatusBarManager;
            if (this.mCarModeEnabled) {
                i = DumpState.DUMP_FROZEN;
            } else {
                i = 0;
            }
            statusBarManager.disable(i);
        }
        if (this.mNotificationManager == null) {
            this.mNotificationManager = (NotificationManager) context.getSystemService("notification");
        }
        if (this.mNotificationManager != null) {
            if (this.mCarModeEnabled) {
                Intent carModeOffIntent = new Intent(context, DisableCarModeActivity.class);
                Notification.Builder n = new Notification.Builder(context, SystemNotificationChannels.CAR_MODE).setSmallIcon(17303448).setDefaults(4).setOngoing(true).setWhen(0L).setColor(context.getColor(17170861)).setContentTitle(context.getString(17039609)).setContentText(context.getString(17039608)).setContentIntent(PendingIntent.getActivityAsUser(context, 0, carModeOffIntent, 0, null, UserHandle.CURRENT));
                this.mNotificationManager.notifyAsUser(null, 10, n.build(), UserHandle.ALL);
                return;
            }
            this.mNotificationManager.cancelAsUser(null, 10, UserHandle.ALL);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateComputedNightModeLocked() {
        if (this.mTwilightManager != null) {
            TwilightState state = this.mTwilightManager.getLastTwilightState();
            if (state != null) {
                this.mComputedNightMode = state.isNight();
            }
            this.mComputedNightMode = UiThemeManager.isTwilightNight(this.mTwilightManager);
        }
    }

    private void registerVrStateListener() {
        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService("vrmanager"));
        if (vrManager != null) {
            try {
                vrManager.registerListener(this.mVrStateCallbacks);
            } catch (RemoteException e) {
                String str = TAG;
                Slog.e(str, "Failed to register VR mode state listener: " + e);
            }
        }
    }

    /* loaded from: classes.dex */
    private static class Shell extends ShellCommand {
        public static final String NIGHT_MODE_STR_AUTO = "auto";
        public static final String NIGHT_MODE_STR_NO = "no";
        public static final String NIGHT_MODE_STR_UNKNOWN = "unknown";
        public static final String NIGHT_MODE_STR_YES = "yes";
        private final IUiModeManager mInterface;

        Shell(IUiModeManager iface) {
            this.mInterface = iface;
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("UiModeManager service (uimode) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  night [yes|no|auto]");
            pw.println("    Set or read night mode.");
        }

        /* JADX WARN: Removed duplicated region for block: B:14:0x0020 A[Catch: RemoteException -> 0x002a, TryCatch #0 {RemoteException -> 0x002a, blocks: (B:6:0x0008, B:14:0x0020, B:16:0x0025, B:9:0x0012), top: B:21:0x0008 }] */
        /* JADX WARN: Removed duplicated region for block: B:16:0x0025 A[Catch: RemoteException -> 0x002a, TRY_LEAVE, TryCatch #0 {RemoteException -> 0x002a, blocks: (B:6:0x0008, B:14:0x0020, B:16:0x0025, B:9:0x0012), top: B:21:0x0008 }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public int onCommand(java.lang.String r6) {
            /*
                r5 = this;
                if (r6 != 0) goto L7
                int r0 = r5.handleDefaultCommands(r6)
                return r0
            L7:
                r0 = -1
                int r1 = r6.hashCode()     // Catch: android.os.RemoteException -> L2a
                r2 = 104817688(0x63f6418, float:3.5996645E-35)
                if (r1 == r2) goto L12
                goto L1d
            L12:
                java.lang.String r1 = "night"
                boolean r1 = r6.equals(r1)     // Catch: android.os.RemoteException -> L2a
                if (r1 == 0) goto L1d
                r1 = 0
                goto L1e
            L1d:
                r1 = r0
            L1e:
                if (r1 == 0) goto L25
                int r1 = r5.handleDefaultCommands(r6)     // Catch: android.os.RemoteException -> L2a
                return r1
            L25:
                int r1 = r5.handleNightMode()     // Catch: android.os.RemoteException -> L2a
                return r1
            L2a:
                r1 = move-exception
                java.io.PrintWriter r2 = r5.getErrPrintWriter()
                java.lang.StringBuilder r3 = new java.lang.StringBuilder
                r3.<init>()
                java.lang.String r4 = "Remote exception: "
                r3.append(r4)
                r3.append(r1)
                java.lang.String r3 = r3.toString()
                r2.println(r3)
                return r0
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.UiModeManagerService.Shell.onCommand(java.lang.String):int");
        }

        private int handleNightMode() throws RemoteException {
            PrintWriter err = getErrPrintWriter();
            String modeStr = getNextArg();
            if (modeStr == null) {
                printCurrentNightMode();
                return 0;
            }
            int mode = strToNightMode(modeStr);
            if (mode >= 0) {
                this.mInterface.setNightMode(mode);
                printCurrentNightMode();
                return 0;
            }
            err.println("Error: mode must be 'yes', 'no', or 'auto'");
            return -1;
        }

        private void printCurrentNightMode() throws RemoteException {
            PrintWriter pw = getOutPrintWriter();
            int currMode = this.mInterface.getNightMode();
            String currModeStr = nightModeToStr(currMode);
            pw.println("Night mode: " + currModeStr);
        }

        private static String nightModeToStr(int mode) {
            switch (mode) {
                case 0:
                    return NIGHT_MODE_STR_AUTO;
                case 1:
                    return NIGHT_MODE_STR_NO;
                case 2:
                    return NIGHT_MODE_STR_YES;
                default:
                    return NIGHT_MODE_STR_UNKNOWN;
            }
        }

        private static int strToNightMode(String modeStr) {
            boolean z;
            int hashCode = modeStr.hashCode();
            if (hashCode == 3521) {
                if (modeStr.equals(NIGHT_MODE_STR_NO)) {
                    z = true;
                }
                z = true;
            } else if (hashCode != 119527) {
                if (hashCode == 3005871 && modeStr.equals(NIGHT_MODE_STR_AUTO)) {
                    z = true;
                }
                z = true;
            } else {
                if (modeStr.equals(NIGHT_MODE_STR_YES)) {
                    z = false;
                }
                z = true;
            }
            switch (z) {
                case false:
                    return 2;
                case true:
                    return 1;
                case true:
                    return 0;
                default:
                    return -1;
            }
        }
    }
}
