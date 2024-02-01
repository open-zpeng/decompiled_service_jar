package com.android.server;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
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
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.icu.impl.CalendarAstronomer;
import android.icu.util.Calendar;
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.Sandman;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.DisableCarModeActivity;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.server.pm.DumpState;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import com.android.server.wm.WindowManagerInternal;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class UiModeManagerService extends SystemService {
    private static final boolean ENABLE_LAUNCH_DESK_DOCK_APP = true;
    private static final boolean LOG = false;
    protected static final String OVERRIDE_NIGHT_MODE = "ui_night_mode_override";
    private static final String SYSTEM_PROPERTY_DEVICE_THEME = "persist.sys.theme";
    private static final String TAG = UiModeManager.class.getSimpleName();
    private final BroadcastReceiver mBatteryReceiver;
    private boolean mCar;
    private int mCarModeEnableFlags;
    private boolean mCarModeEnabled;
    private boolean mCarModeKeepsScreenOn;
    private boolean mCharging;
    private boolean mComputedNightMode;
    private Configuration mConfiguration;
    int mCurUiMode;
    private final ContentObserver mDarkThemeObserver;
    private int mDefaultUiModeType;
    private boolean mDeskModeKeepsScreenOn;
    private final BroadcastReceiver mDockModeReceiver;
    private int mDockState;
    private boolean mEnableCarDockLaunch;
    private final Handler mHandler;
    private boolean mHoldingConfiguration;
    private int mLastBroadcastState;
    private final LocalService mLocalService;
    final Object mLock;
    private int mNightMode;
    private boolean mNightModeLocked;
    private int mNightModeOverride;
    private NotificationManager mNotificationManager;
    private final BroadcastReceiver mOnScreenOffHandler;
    private PowerManager mPowerManager;
    private boolean mPowerSave;
    private final BroadcastReceiver mResultReceiver;
    private final IUiModeManager.Stub mService;
    private int mSetUiMode;
    private boolean mSetupWizardComplete;
    private final ContentObserver mSetupWizardObserver;
    private StatusBarManager mStatusBarManager;
    boolean mSystemReady;
    private boolean mTelevision;
    private UiThemeManager mThemeManager;
    private final TwilightListener mTwilightListener;
    private TwilightManager mTwilightManager;
    private boolean mUiModeLocked;
    private boolean mVrHeadset;
    private final IVrStateCallbacks mVrStateCallbacks;
    private boolean mWaitForScreenOff;
    private PowerManager.WakeLock mWakeLock;
    private boolean mWatch;
    private WindowManagerInternal mWindowManager;

    public UiModeManagerService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mDockState = 0;
        this.mLastBroadcastState = 0;
        this.mNightMode = 1;
        this.mNightModeOverride = this.mNightMode;
        this.mCarModeEnabled = false;
        this.mCharging = false;
        this.mPowerSave = false;
        this.mWaitForScreenOff = false;
        this.mEnableCarDockLaunch = true;
        this.mUiModeLocked = false;
        this.mNightModeLocked = false;
        this.mCurUiMode = 0;
        this.mSetUiMode = 0;
        this.mHoldingConfiguration = false;
        this.mConfiguration = new Configuration();
        this.mHandler = new Handler();
        this.mLocalService = new LocalService();
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
                String action = intent.getAction();
                if (((action.hashCode() == -1538406691 && action.equals("android.intent.action.BATTERY_CHANGED")) ? (char) 0 : (char) 65535) == 0) {
                    UiModeManagerService.this.mCharging = intent.getIntExtra("plugged", 0) != 0;
                }
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
                        if (!UiModeManagerService.this.mCar) {
                            UiModeManagerService.this.registerScreenOffEvent();
                        } else {
                            UiModeManagerService.this.updateLocked(0, 0);
                        }
                    }
                }
            }
        };
        this.mOnScreenOffHandler = new BroadcastReceiver() { // from class: com.android.server.UiModeManagerService.5
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                synchronized (UiModeManagerService.this.mLock) {
                    UiModeManagerService.this.unregisterScreenOffEvent();
                    UiModeManagerService.this.updateLocked(0, 0);
                }
            }
        };
        this.mVrStateCallbacks = new IVrStateCallbacks.Stub() { // from class: com.android.server.UiModeManagerService.6
            public void onVrStateChanged(boolean enabled) {
                synchronized (UiModeManagerService.this.mLock) {
                    UiModeManagerService.this.mVrHeadset = enabled;
                    if (UiModeManagerService.this.mSystemReady) {
                        UiModeManagerService.this.updateLocked(0, 0);
                    }
                }
            }
        };
        this.mSetupWizardObserver = new ContentObserver(this.mHandler) { // from class: com.android.server.UiModeManagerService.7
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange, Uri uri) {
                if (UiModeManagerService.this.setupWizardCompleteForCurrentUser()) {
                    UiModeManagerService.this.mSetupWizardComplete = true;
                    UiModeManagerService.this.getContext().getContentResolver().unregisterContentObserver(UiModeManagerService.this.mSetupWizardObserver);
                    Context context2 = UiModeManagerService.this.getContext();
                    UiModeManagerService.this.updateNightModeFromSettings(context2, context2.getResources(), UserHandle.getCallingUserId());
                    UiModeManagerService.this.updateLocked(0, 0);
                }
            }
        };
        this.mDarkThemeObserver = new ContentObserver(this.mHandler) { // from class: com.android.server.UiModeManagerService.8
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange, Uri uri) {
                UiModeManagerService.this.lambda$onStart$2$UiModeManagerService();
            }
        };
        this.mService = new IUiModeManager.Stub() { // from class: com.android.server.UiModeManagerService.9
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
                if (!isNightModeLocked() || UiModeManagerService.this.getContext().checkCallingOrSelfPermission("android.permission.MODIFY_DAY_NIGHT_MODE") == 0) {
                    if (!UiModeManagerService.this.mSetupWizardComplete) {
                        Slog.d(UiModeManagerService.TAG, "Night mode cannot be changed before setup wizard completes.");
                        return;
                    } else if (mode != 0 && mode != 1 && mode != 2) {
                        throw new IllegalArgumentException("Unknown mode: " + mode);
                    } else {
                        int user = UserHandle.getCallingUserId();
                        long ident = Binder.clearCallingIdentity();
                        try {
                            synchronized (UiModeManagerService.this.mLock) {
                                if (UiModeManagerService.this.mNightMode != mode) {
                                    if (!UiModeManagerService.this.mCarModeEnabled) {
                                        Settings.Secure.putIntForUser(UiModeManagerService.this.getContext().getContentResolver(), "ui_night_mode", mode, user);
                                        if (UserManager.get(UiModeManagerService.this.getContext()).isPrimaryUser()) {
                                            SystemProperties.set(UiModeManagerService.SYSTEM_PROPERTY_DEVICE_THEME, Integer.toString(mode));
                                        }
                                    }
                                    UiModeManagerService.this.mNightMode = mode;
                                    UiModeManagerService.this.mNightModeOverride = mode;
                                    if (!UiModeManagerService.this.mCarModeEnabled) {
                                        UiModeManagerService.this.persistNightMode(user);
                                    }
                                    if (UiModeManagerService.this.mNightMode == 0 && !UiModeManagerService.this.shouldApplyAutomaticChangesImmediately()) {
                                        UiModeManagerService.this.registerScreenOffEvent();
                                    }
                                    UiModeManagerService.this.unregisterScreenOffEvent();
                                    UiModeManagerService.this.updateLocked(0, 0);
                                }
                            }
                            return;
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                }
                Slog.e(UiModeManagerService.TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission");
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

            public boolean setNightModeActivated(boolean active) {
                synchronized (UiModeManagerService.this.mLock) {
                    int user = UserHandle.getCallingUserId();
                    long ident = Binder.clearCallingIdentity();
                    int i = 2;
                    if (UiModeManagerService.this.mNightMode == 0) {
                        UiModeManagerService.this.unregisterScreenOffEvent();
                        UiModeManagerService uiModeManagerService = UiModeManagerService.this;
                        if (!active) {
                            i = 1;
                        }
                        uiModeManagerService.mNightModeOverride = i;
                    } else if (UiModeManagerService.this.mNightMode != 1 || !active) {
                        if (UiModeManagerService.this.mNightMode == 2 && !active) {
                            UiModeManagerService.this.mNightMode = 1;
                        }
                    } else {
                        UiModeManagerService.this.mNightMode = 2;
                    }
                    UiModeManagerService.this.updateConfigurationLocked();
                    UiModeManagerService.this.applyConfigurationExternallyLocked();
                    UiModeManagerService.this.persistNightMode(user);
                    Binder.restoreCallingIdentity(ident);
                }
                return true;
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

            public void applyThemeStyle(String style) {
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.mThemeManager.applyThemeStyle(style);
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

            public void applyDayNightModeEx(int daynightMode, boolean needAnimation) {
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.mThemeManager.applyDayNightMode(daynightMode, needAnimation);
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

    @VisibleForTesting
    protected UiModeManagerService(Context context, WindowManagerInternal wm, PowerManager.WakeLock wl, TwilightManager tm, PowerManager pm, boolean setupWizardComplete) {
        super(context);
        this.mLock = new Object();
        this.mDockState = 0;
        this.mLastBroadcastState = 0;
        this.mNightMode = 1;
        this.mNightModeOverride = this.mNightMode;
        this.mCarModeEnabled = false;
        this.mCharging = false;
        this.mPowerSave = false;
        this.mWaitForScreenOff = false;
        this.mEnableCarDockLaunch = true;
        this.mUiModeLocked = false;
        this.mNightModeLocked = false;
        this.mCurUiMode = 0;
        this.mSetUiMode = 0;
        this.mHoldingConfiguration = false;
        this.mConfiguration = new Configuration();
        this.mHandler = new Handler();
        this.mLocalService = new LocalService();
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
                String action = intent.getAction();
                if (((action.hashCode() == -1538406691 && action.equals("android.intent.action.BATTERY_CHANGED")) ? (char) 0 : (char) 65535) == 0) {
                    UiModeManagerService.this.mCharging = intent.getIntExtra("plugged", 0) != 0;
                }
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
                        if (!UiModeManagerService.this.mCar) {
                            UiModeManagerService.this.registerScreenOffEvent();
                        } else {
                            UiModeManagerService.this.updateLocked(0, 0);
                        }
                    }
                }
            }
        };
        this.mOnScreenOffHandler = new BroadcastReceiver() { // from class: com.android.server.UiModeManagerService.5
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                synchronized (UiModeManagerService.this.mLock) {
                    UiModeManagerService.this.unregisterScreenOffEvent();
                    UiModeManagerService.this.updateLocked(0, 0);
                }
            }
        };
        this.mVrStateCallbacks = new IVrStateCallbacks.Stub() { // from class: com.android.server.UiModeManagerService.6
            public void onVrStateChanged(boolean enabled) {
                synchronized (UiModeManagerService.this.mLock) {
                    UiModeManagerService.this.mVrHeadset = enabled;
                    if (UiModeManagerService.this.mSystemReady) {
                        UiModeManagerService.this.updateLocked(0, 0);
                    }
                }
            }
        };
        this.mSetupWizardObserver = new ContentObserver(this.mHandler) { // from class: com.android.server.UiModeManagerService.7
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange, Uri uri) {
                if (UiModeManagerService.this.setupWizardCompleteForCurrentUser()) {
                    UiModeManagerService.this.mSetupWizardComplete = true;
                    UiModeManagerService.this.getContext().getContentResolver().unregisterContentObserver(UiModeManagerService.this.mSetupWizardObserver);
                    Context context2 = UiModeManagerService.this.getContext();
                    UiModeManagerService.this.updateNightModeFromSettings(context2, context2.getResources(), UserHandle.getCallingUserId());
                    UiModeManagerService.this.updateLocked(0, 0);
                }
            }
        };
        this.mDarkThemeObserver = new ContentObserver(this.mHandler) { // from class: com.android.server.UiModeManagerService.8
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange, Uri uri) {
                UiModeManagerService.this.lambda$onStart$2$UiModeManagerService();
            }
        };
        this.mService = new IUiModeManager.Stub() { // from class: com.android.server.UiModeManagerService.9
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
                if (!isNightModeLocked() || UiModeManagerService.this.getContext().checkCallingOrSelfPermission("android.permission.MODIFY_DAY_NIGHT_MODE") == 0) {
                    if (!UiModeManagerService.this.mSetupWizardComplete) {
                        Slog.d(UiModeManagerService.TAG, "Night mode cannot be changed before setup wizard completes.");
                        return;
                    } else if (mode != 0 && mode != 1 && mode != 2) {
                        throw new IllegalArgumentException("Unknown mode: " + mode);
                    } else {
                        int user = UserHandle.getCallingUserId();
                        long ident = Binder.clearCallingIdentity();
                        try {
                            synchronized (UiModeManagerService.this.mLock) {
                                if (UiModeManagerService.this.mNightMode != mode) {
                                    if (!UiModeManagerService.this.mCarModeEnabled) {
                                        Settings.Secure.putIntForUser(UiModeManagerService.this.getContext().getContentResolver(), "ui_night_mode", mode, user);
                                        if (UserManager.get(UiModeManagerService.this.getContext()).isPrimaryUser()) {
                                            SystemProperties.set(UiModeManagerService.SYSTEM_PROPERTY_DEVICE_THEME, Integer.toString(mode));
                                        }
                                    }
                                    UiModeManagerService.this.mNightMode = mode;
                                    UiModeManagerService.this.mNightModeOverride = mode;
                                    if (!UiModeManagerService.this.mCarModeEnabled) {
                                        UiModeManagerService.this.persistNightMode(user);
                                    }
                                    if (UiModeManagerService.this.mNightMode == 0 && !UiModeManagerService.this.shouldApplyAutomaticChangesImmediately()) {
                                        UiModeManagerService.this.registerScreenOffEvent();
                                    }
                                    UiModeManagerService.this.unregisterScreenOffEvent();
                                    UiModeManagerService.this.updateLocked(0, 0);
                                }
                            }
                            return;
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                }
                Slog.e(UiModeManagerService.TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission");
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

            public boolean setNightModeActivated(boolean active) {
                synchronized (UiModeManagerService.this.mLock) {
                    int user = UserHandle.getCallingUserId();
                    long ident = Binder.clearCallingIdentity();
                    int i = 2;
                    if (UiModeManagerService.this.mNightMode == 0) {
                        UiModeManagerService.this.unregisterScreenOffEvent();
                        UiModeManagerService uiModeManagerService = UiModeManagerService.this;
                        if (!active) {
                            i = 1;
                        }
                        uiModeManagerService.mNightModeOverride = i;
                    } else if (UiModeManagerService.this.mNightMode != 1 || !active) {
                        if (UiModeManagerService.this.mNightMode == 2 && !active) {
                            UiModeManagerService.this.mNightMode = 1;
                        }
                    } else {
                        UiModeManagerService.this.mNightMode = 2;
                    }
                    UiModeManagerService.this.updateConfigurationLocked();
                    UiModeManagerService.this.applyConfigurationExternallyLocked();
                    UiModeManagerService.this.persistNightMode(user);
                    Binder.restoreCallingIdentity(ident);
                }
                return true;
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

            public void applyThemeStyle(String style) {
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.mThemeManager.applyThemeStyle(style);
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

            public void applyDayNightModeEx(int daynightMode, boolean needAnimation) {
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (UiModeManagerService.this.mLock) {
                        UiModeManagerService.this.mThemeManager.applyDayNightMode(daynightMode, needAnimation);
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
        this.mWindowManager = wm;
        this.mWakeLock = wl;
        this.mTwilightManager = tm;
        this.mPowerManager = pm;
        this.mSetupWizardComplete = setupWizardComplete;
    }

    private static Intent buildHomeIntent(String category) {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.addCategory(category);
        intent.setFlags(270532608);
        return intent;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: updateSystemProperties */
    public void lambda$onStart$2$UiModeManagerService() {
        int mode = Settings.Secure.getIntForUser(getContext().getContentResolver(), "ui_night_mode", this.mNightMode, 0);
        this.mNightMode = mode;
        if (mode == 0) {
            mode = 2;
        }
        SystemProperties.set(SYSTEM_PROPERTY_DEVICE_THEME, Integer.toString(mode));
    }

    @Override // com.android.server.SystemService
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);
        getContext().getContentResolver().unregisterContentObserver(this.mSetupWizardObserver);
        verifySetupWizardCompleted();
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        Context context = getContext();
        this.mPowerManager = (PowerManager) context.getSystemService("power");
        this.mWakeLock = this.mPowerManager.newWakeLock(26, TAG);
        this.mWindowManager = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
        verifySetupWizardCompleted();
        context.registerReceiver(this.mDockModeReceiver, new IntentFilter("android.intent.action.DOCK_EVENT"));
        IntentFilter batteryFilter = new IntentFilter("android.intent.action.BATTERY_CHANGED");
        context.registerReceiver(this.mBatteryReceiver, batteryFilter);
        PowerManagerInternal localPowerManager = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);
        this.mPowerSave = localPowerManager.getLowPowerState(16).batterySaverEnabled;
        localPowerManager.registerLowPowerModeObserver(16, new Consumer() { // from class: com.android.server.-$$Lambda$UiModeManagerService$vYS4_RzjAavNRF50rrGN0tXI5JM
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                UiModeManagerService.this.lambda$onStart$0$UiModeManagerService((PowerSaveState) obj);
            }
        });
        this.mConfiguration.setToDefaults();
        Resources res = context.getResources();
        this.mDefaultUiModeType = res.getInteger(17694783);
        boolean z = true;
        this.mCarModeKeepsScreenOn = res.getInteger(17694760) == 1;
        this.mDeskModeKeepsScreenOn = res.getInteger(17694785) == 1;
        this.mEnableCarDockLaunch = res.getBoolean(17891437);
        this.mUiModeLocked = res.getBoolean(17891479);
        this.mNightModeLocked = res.getBoolean(17891478);
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature("android.hardware.type.television") && !pm.hasSystemFeature("android.software.leanback")) {
            z = false;
        }
        this.mTelevision = z;
        this.mCar = pm.hasSystemFeature("android.hardware.type.automotive");
        this.mWatch = pm.hasSystemFeature("android.hardware.type.watch");
        updateNightModeFromSettings(context, res, UserHandle.getCallingUserId());
        SystemServerInitThreadPool.get().submit(new Runnable() { // from class: com.android.server.-$$Lambda$UiModeManagerService$vuGxqIEDBezs_Xyz-NAh0Bonp5g
            @Override // java.lang.Runnable
            public final void run() {
                UiModeManagerService.this.lambda$onStart$1$UiModeManagerService();
            }
        }, TAG + ".onStart");
        publishBinderService("uimode", this.mService);
        publishLocalService(UiModeManagerInternal.class, this.mLocalService);
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.USER_SWITCHED");
        context.registerReceiver(new UserSwitchedReceiver(), filter, null, this.mHandler);
        context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("ui_night_mode"), false, this.mDarkThemeObserver, 0);
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$UiModeManagerService$wttJpnJnECgc-2ud4hu2A5dSOPg
            @Override // java.lang.Runnable
            public final void run() {
                UiModeManagerService.this.lambda$onStart$2$UiModeManagerService();
            }
        });
        this.mThemeManager = new UiThemeManager(context);
        this.mThemeManager.onStart();
    }

    public /* synthetic */ void lambda$onStart$0$UiModeManagerService(PowerSaveState state) {
        synchronized (this.mLock) {
            if (this.mPowerSave == state.batterySaverEnabled) {
                return;
            }
            this.mPowerSave = state.batterySaverEnabled;
            if (this.mSystemReady) {
                updateLocked(0, 0);
            }
        }
    }

    public /* synthetic */ void lambda$onStart$1$UiModeManagerService() {
        synchronized (this.mLock) {
            updateConfigurationLocked();
            applyConfigurationExternallyLocked();
        }
    }

    @VisibleForTesting
    protected IUiModeManager getService() {
        return this.mService;
    }

    @VisibleForTesting
    protected Configuration getConfiguration() {
        return this.mConfiguration;
    }

    private void verifySetupWizardCompleted() {
        Context context = getContext();
        int userId = UserHandle.getCallingUserId();
        if (!setupWizardCompleteForCurrentUser()) {
            this.mSetupWizardComplete = false;
            context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("user_setup_complete"), false, this.mSetupWizardObserver, userId);
            return;
        }
        this.mSetupWizardComplete = true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean setupWizardCompleteForCurrentUser() {
        return Settings.Secure.getIntForUser(getContext().getContentResolver(), "user_setup_complete", 0, UserHandle.getCallingUserId()) == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updateNightModeFromSettings(Context context, Resources res, int userId) {
        int defaultNightMode = res.getInteger(17694775);
        int oldNightMode = this.mNightMode;
        if (this.mSetupWizardComplete) {
            this.mNightMode = Settings.Secure.getIntForUser(context.getContentResolver(), "ui_night_mode", defaultNightMode, userId);
            this.mNightModeOverride = Settings.Secure.getIntForUser(context.getContentResolver(), OVERRIDE_NIGHT_MODE, defaultNightMode, userId);
        } else {
            this.mNightMode = defaultNightMode;
            this.mNightModeOverride = defaultNightMode;
        }
        return oldNightMode != this.mNightMode;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void registerScreenOffEvent() {
        if (this.mPowerSave) {
            return;
        }
        this.mWaitForScreenOff = true;
        IntentFilter intentFilter = new IntentFilter("android.intent.action.SCREEN_OFF");
        getContext().registerReceiver(this.mOnScreenOffHandler, intentFilter);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void unregisterScreenOffEvent() {
        this.mWaitForScreenOff = false;
        try {
            getContext().unregisterReceiver(this.mOnScreenOffHandler);
        } catch (IllegalArgumentException e) {
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
            pw.print(" (");
            pw.print(Shell.nightModeToStr(this.mNightMode));
            pw.print(") ");
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
                registerVrStateListener();
                updateLocked(0, 0);
                this.mThemeManager.onSystemReady();
                this.mThemeManager.initTwilightManager(this.mTwilightManager);
            }
        }
    }

    void setCarModeLocked(boolean enabled, int flags) {
        if (this.mCarModeEnabled != enabled) {
            this.mCarModeEnabled = enabled;
            if (!this.mCarModeEnabled) {
                Context context = getContext();
                updateNightModeFromSettings(context, context.getResources(), UserHandle.getCallingUserId());
            }
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
        if (state == 1 || state == 3 || state == 4) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void persistNightMode(int user) {
        Settings.Secure.putIntForUser(getContext().getContentResolver(), "ui_night_mode", this.mNightMode, user);
        Settings.Secure.putIntForUser(getContext().getContentResolver(), OVERRIDE_NIGHT_MODE, this.mNightModeOverride, user);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateConfigurationLocked() {
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
        int i = this.mNightMode;
        if (i == 2 || i == 1) {
            this.mComputedNightMode = this.mNightMode == 2;
        }
        if (this.mNightMode == 0) {
            boolean activateNightMode = this.mComputedNightMode;
            TwilightManager twilightManager = this.mTwilightManager;
            if (twilightManager != null) {
                activateNightMode = UiThemeManager.isTwilightNight(twilightManager);
            }
            updateComputedNightModeLocked(activateNightMode);
        } else {
            TwilightManager twilightManager2 = this.mTwilightManager;
        }
        if (this.mPowerSave && !this.mCarModeEnabled) {
            uiMode = (uiMode2 & (-17)) | 32;
        } else {
            uiMode = getComputedUiModeConfiguration(uiMode2);
        }
        this.mCurUiMode = uiMode;
        if (!this.mHoldingConfiguration && (!this.mWaitForScreenOff || this.mPowerSave)) {
            Configuration configuration = this.mConfiguration;
            configuration.uiMode = uiMode;
            configuration.uiMode |= 64;
        }
        Slog.i(TAG, "updateConfigurationLocked: mDockState=" + this.mDockState + "; mCarMode=" + this.mCarModeEnabled + "; mNightMode=" + this.mNightMode + "; mHoldingConfiguration=" + this.mHoldingConfiguration + "; mWaitForScreenOff=" + this.mWaitForScreenOff + "; mPowerSave=" + this.mPowerSave + "; uiMode=" + uiMode + "; mConfiguration.uiMode=" + this.mConfiguration.uiMode);
    }

    private int getComputedUiModeConfiguration(int uiMode) {
        return (uiMode | (this.mComputedNightMode ? 32 : 16)) & (this.mComputedNightMode ? -17 : -33);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void applyConfigurationExternallyLocked() {
        if (this.mSetUiMode != this.mConfiguration.uiMode) {
            this.mSetUiMode = this.mConfiguration.uiMode;
            try {
                ActivityTaskManager.getService().updateConfiguration(this.mConfiguration);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure communicating with activity manager", e);
            } catch (SecurityException e2) {
                Slog.e(TAG, "Activity does not have the ", e2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean shouldApplyAutomaticChangesImmediately() {
        return this.mCar || !this.mPowerManager.isInteractive();
    }

    void updateLocked(int enableFlags, int disableFlags) {
        String action = null;
        String oldAction = null;
        int i = this.mLastBroadcastState;
        if (i == 2) {
            adjustStatusBarCarModeLocked();
            oldAction = UiModeManager.ACTION_EXIT_CAR_MODE;
        } else if (isDeskDockState(i)) {
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
        Intent homeIntent;
        this.mHoldingConfiguration = false;
        updateConfigurationLocked();
        boolean dockAppStarted = false;
        if (category != null) {
            Intent homeIntent2 = buildHomeIntent(category);
            if (Sandman.shouldStartDockApp(getContext(), homeIntent2)) {
                try {
                    homeIntent = homeIntent2;
                    try {
                        int result = ActivityTaskManager.getService().startActivityWithConfig((IApplicationThread) null, (String) null, homeIntent2, (String) null, (IBinder) null, (String) null, 0, 0, this.mConfiguration, (Bundle) null, -2);
                        if (ActivityManager.isStartResultSuccessful(result)) {
                            dockAppStarted = true;
                        } else if (result != -91) {
                            Slog.e(TAG, "Could not start dock app: " + homeIntent + ", startActivityWithConfig result " + result);
                        }
                    } catch (RemoteException e) {
                        ex = e;
                        Slog.e(TAG, "Could not start dock app: " + homeIntent, ex);
                        applyConfigurationExternallyLocked();
                        if (category == null) {
                        }
                        return;
                    }
                } catch (RemoteException e2) {
                    ex = e2;
                    homeIntent = homeIntent2;
                }
            }
        }
        applyConfigurationExternallyLocked();
        if (category == null && !dockAppStarted) {
            Sandman.startDreamWhenDockedIfAppropriate(getContext());
        }
    }

    private void adjustStatusBarCarModeLocked() {
        int i;
        Context context = getContext();
        if (this.mStatusBarManager == null) {
            this.mStatusBarManager = (StatusBarManager) context.getSystemService("statusbar");
        }
        StatusBarManager statusBarManager = this.mStatusBarManager;
        if (statusBarManager != null) {
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
        NotificationManager notificationManager = this.mNotificationManager;
        if (notificationManager != null) {
            if (this.mCarModeEnabled) {
                Intent carModeOffIntent = new Intent(context, DisableCarModeActivity.class);
                Notification.Builder n = new Notification.Builder(context, SystemNotificationChannels.CAR_MODE).setSmallIcon(17303515).setDefaults(4).setOngoing(true).setWhen(0L).setColor(context.getColor(17170460)).setContentTitle(context.getString(17039644)).setContentText(context.getString(17039643)).setContentIntent(PendingIntent.getActivityAsUser(context, 0, carModeOffIntent, 0, null, UserHandle.CURRENT));
                this.mNotificationManager.notifyAsUser(null, 10, n.build(), UserHandle.ALL);
                return;
            }
            notificationManager.cancelAsUser(null, 10, UserHandle.ALL);
        }
    }

    private void updateComputedNightModeLocked(boolean activate) {
        this.mComputedNightMode = activate;
        this.mNightModeOverride = this.mNightMode;
        int user = UserHandle.getCallingUserId();
        Settings.Secure.putIntForUser(getContext().getContentResolver(), OVERRIDE_NIGHT_MODE, this.mNightModeOverride, user);
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

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Shell extends ShellCommand {
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

        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            try {
                if (!((cmd.hashCode() == 104817688 && cmd.equals("night")) ? false : true)) {
                    return handleNightMode();
                }
                return handleDefaultCommands(cmd);
            } catch (RemoteException e) {
                PrintWriter err = getErrPrintWriter();
                err.println("Remote exception: " + e);
                return -1;
            }
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

        /* JADX INFO: Access modifiers changed from: private */
        public static String nightModeToStr(int mode) {
            if (mode != 0) {
                if (mode != 1) {
                    if (mode == 2) {
                        return NIGHT_MODE_STR_YES;
                    }
                    return NIGHT_MODE_STR_UNKNOWN;
                }
                return NIGHT_MODE_STR_NO;
            }
            return NIGHT_MODE_STR_AUTO;
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
            if (z) {
                if (!z) {
                    return !z ? -1 : 0;
                }
                return 1;
            }
            return 2;
        }
    }

    /* loaded from: classes.dex */
    public final class LocalService extends UiModeManagerInternal {
        public LocalService() {
        }

        @Override // com.android.server.UiModeManagerInternal
        public boolean isNightMode() {
            boolean isIt;
            synchronized (UiModeManagerService.this.mLock) {
                isIt = (UiModeManagerService.this.mConfiguration.uiMode & 32) != 0;
            }
            return isIt;
        }
    }

    /* loaded from: classes.dex */
    private final class UserSwitchedReceiver extends BroadcastReceiver {
        private UserSwitchedReceiver() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            synchronized (UiModeManagerService.this.mLock) {
                int currentId = intent.getIntExtra("android.intent.extra.user_handle", 0);
                if (UiModeManagerService.this.updateNightModeFromSettings(context, context.getResources(), currentId)) {
                    UiModeManagerService.this.updateLocked(0, 0);
                }
            }
        }
    }
}
