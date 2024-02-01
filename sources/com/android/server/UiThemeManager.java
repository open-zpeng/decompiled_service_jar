package com.android.server;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.icu.impl.CalendarAstronomer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import com.xiaopeng.server.ext.ExternalManagerService;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class UiThemeManager {
    private static final String ACTION_SWITCH_DAYNIGHT = "com.xiaopeng.intent.action.SWITCH_DAYNIGHT";
    private static final String ACTION_SWITCH_THEME = "com.xiaopeng.intent.action.SWITCH_THEME";
    private static final boolean DEBUG = true;
    private static final String KEY_DAYNIGHT_MODE = "ui_night_mode";
    private static final String KEY_THEME_MODE = "key_theme_mode";
    private static final String KEY_THEME_STATE = "key_theme_type";
    private static final int MSG_THEME_TWILIGHT = 101;
    private static final String PROP_ANIMATION_DELAY = "persist.sys.theme.anim.delay";
    private static final String PROP_THEME_CACHE = "xpeng.sys.theme.cache";
    private static final String PROP_THEME_ID = "persist.sys.theme.id";
    private static final String PROP_THEME_INTERVAL = "persist.sys.theme.interval";
    private static final String PROP_THEME_LATITUDE = "persist.sys.theme.latitude";
    private static final String PROP_THEME_LOGGER = "persist.sys.theme.logger";
    private static final String PROP_THEME_LONGITUDE = "persist.sys.theme.longitude";
    private static final String PROP_THEME_STATE = "persist.sys.theme.state";
    private static final int R_GEAR_LEVEL = 3;
    private static final int STATE_THEME_CHANGED = 2;
    private static final int STATE_THEME_PREPARE = 1;
    private static final int STATE_THEME_UNKNOWN = 0;
    private static final int SUNRISE = 6;
    private static final int SUNSET = 20;
    private static final String TAG = UiThemeManager.class.getSimpleName();
    private static final boolean THEME;
    private static final long THEME_ANIMATION_DELAY;
    private static final long THEME_ANIMATION_INTERVAL;
    private static final int UI_MODE_AUTO = 0;
    private static final int UI_MODE_DAY = 1;
    private static final int UI_MODE_NIGHT = 2;
    private static final int UI_MODE_THEME_CLEAR = 63;
    private static final int UI_MODE_THEME_MASK = 192;
    private static final int UI_MODE_THEME_UNDEFINED = 0;
    private static final double gLatitude = 31.40527d;
    private static final double gLongitude = 121.48941d;
    private Context mContext;
    private ThemeWindowView mThemeWindowView;
    private TwilightManager mTwilightManager;
    private TwilightState mTwilightState;
    private WindowManager mWindowManager;
    private Configuration mConfiguration = new Configuration();
    private int mThemeMode = -1;
    private int mDayNightMode = -1;
    private int mDayNightRealMode = -1;
    private int mCurrentState = 0;
    private Rect mThemeWindowRect = null;
    private int mThemeWindowRotation = -1;
    private final Object mLock = new Object();
    private final BroadcastReceiver mThemeReceiver = new BroadcastReceiver() { // from class: com.android.server.UiThemeManager.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            UiThemeManager.this.handleThemeIntent(intent);
        }
    };
    private final TwilightListener mTwilightListener = new TwilightListener() { // from class: com.android.server.UiThemeManager.2
        @Override // com.android.server.twilight.TwilightListener
        public void onTwilightStateChanged(TwilightState state) {
            UiThemeManager.this.mTwilightState = state;
            UiThemeManager.this.mHandler.removeMessages(101);
            UiThemeManager.this.mHandler.sendEmptyMessageDelayed(101, 2000L);
        }
    };
    private final ThemeWindowView.OnViewListener mThemeViewListener = new ThemeWindowView.OnViewListener() { // from class: com.android.server.UiThemeManager.3
        @Override // com.android.server.UiThemeManager.ThemeWindowView.OnViewListener
        public void onAttachedToWindow() {
        }

        @Override // com.android.server.UiThemeManager.ThemeWindowView.OnViewListener
        public void onDetachedFromWindow() {
        }

        @Override // com.android.server.UiThemeManager.ThemeWindowView.OnViewListener
        public void onConfigurationChanged(Configuration newConfig) {
        }

        @Override // com.android.server.UiThemeManager.ThemeWindowView.OnViewListener
        public void onWindowFocusChanged(boolean hasWindowFocus) {
        }

        @Override // com.android.server.UiThemeManager.ThemeWindowView.OnViewListener
        public void onWindowVisibilityChanged(int visibility) {
        }
    };
    private final Runnable mThemeWindowRunnable = new Runnable() { // from class: com.android.server.UiThemeManager.4
        @Override // java.lang.Runnable
        public void run() {
            try {
                Rect rect = UiThemeManager.this.getThemeWindowRect();
                int rotation = UiThemeManager.this.getThemeWindowRotation();
                Bitmap bitmap = UiThemeManager.this.createScreenshot(rect, rotation);
                for (int retryCount = 0; bitmap == null && retryCount <= 5; retryCount++) {
                    try {
                        Thread.sleep(50L);
                    } catch (Exception e) {
                    }
                    bitmap = UiThemeManager.this.createScreenshot(rect, rotation);
                }
                UiThemeManager.this.handleThemeWindow(UiThemeManager.this.mContext, UiThemeManager.this.mWindowManager, bitmap, rect);
            } catch (Exception e2) {
            }
        }
    };
    private final Runnable mThemeTimeoutRunnable = new Runnable() { // from class: com.android.server.UiThemeManager.5
        @Override // java.lang.Runnable
        public void run() {
            try {
                UiThemeManager.log("ThemeTimeoutRunnable isThemeWorking=" + UiThemeManager.this.isThemeWorking() + " currentState=" + UiThemeManager.this.mCurrentState);
                if (UiThemeManager.this.isThemeWorking()) {
                    switch (UiThemeManager.this.mCurrentState) {
                        case 1:
                            UiThemeManager.this.handleThemeTask(2);
                            break;
                        case 2:
                            UiThemeManager.this.handleThemeTask(0);
                            break;
                    }
                }
            } catch (Exception e) {
            }
        }
    };
    private final Handler mHandler = new Handler() { // from class: com.android.server.UiThemeManager.6
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == 101) {
                UiThemeManager.this.handleThemeTwilight(UiThemeManager.this.mTwilightState);
            }
        }
    };

    static {
        THEME = SystemProperties.getInt(PROP_THEME_LOGGER, 0) == 1;
        THEME_ANIMATION_DELAY = SystemProperties.getLong(PROP_ANIMATION_DELAY, 1000L);
        THEME_ANIMATION_INTERVAL = UiModeManager.THEME_ANIMATION_INTERVAL;
    }

    public UiThemeManager(Context context) {
        this.mContext = context;
    }

    public void onStart() {
        Context context = this.mContext;
        Resources res = context.getResources();
        int defaultNightMode = res.getInteger(17694768);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SWITCH_THEME);
        filter.addAction(ACTION_SWITCH_DAYNIGHT);
        context.registerReceiver(this.mThemeReceiver, filter);
        this.mThemeMode = getSettingsSecureInt(KEY_THEME_MODE, 0);
        this.mDayNightMode = getSettingsSecureInt(KEY_DAYNIGHT_MODE, defaultNightMode);
        this.mCurrentState = getSettingsSecureInt(KEY_THEME_STATE, 0);
        this.mWindowManager = (WindowManager) context.getSystemService("window");
        setThemeState(0);
    }

    public void initTwilightManager(TwilightManager manager) {
        this.mTwilightManager = manager;
        this.mDayNightRealMode = getDayNightRealMode(this.mDayNightMode);
        updateTwilightListener(this.mDayNightMode);
    }

    public int getThemeMode() {
        return this.mThemeMode;
    }

    public int getDayNightMode() {
        return this.mDayNightMode;
    }

    public int getDayNightAutoMode() {
        return this.mDayNightRealMode;
    }

    public void applyThemeMode(int themeMode) {
        if (themeMode != this.mThemeMode && !isThemeWorking()) {
            setThemeMode(themeMode);
            handleThemeTask(1);
        }
    }

    public void applyDayNightMode(int daynightMode) {
        boolean isThemeWorking = isThemeWorking();
        log("applyDayNightMode targetMode=" + daynightMode + " currentMode=" + this.mDayNightMode + " realMode=" + this.mDayNightRealMode + " themeWorking=" + isThemeWorking);
        if (daynightMode != this.mDayNightMode) {
            int realMode = this.mDayNightRealMode;
            switch (daynightMode) {
                case 0:
                    setDayNightMode(daynightMode);
                    if (realMode != this.mDayNightRealMode) {
                        handleThemeTask(1);
                        break;
                    }
                    break;
                case 1:
                case 2:
                    if (!isThemeWorking) {
                        setDayNightMode(daynightMode);
                        if (realMode != this.mDayNightRealMode) {
                            handleThemeTask(1);
                            break;
                        }
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown daynightMode: " + daynightMode);
            }
            updateTwilightListener(daynightMode);
        }
    }

    public void performThemeTask(int state) {
        log("performThemeTask state=" + state);
        handleThemeTask(state);
    }

    public boolean isThemeWorking() {
        return this.mCurrentState != 0;
    }

    public Bitmap screenshot(Rect sourceCrop, int width, int height, int rotation) {
        long ident = Binder.clearCallingIdentity();
        try {
            return SurfaceControl.screenshot(sourceCrop, width, height, rotation);
        } catch (Exception e) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Bitmap createScreenshot(Rect rect, int rotation) {
        if (rect != null) {
            try {
                int width = rect.right - rect.left;
                int height = rect.bottom - rect.top;
                log("createScreenshot createScreenshot rect =" + rect.toString());
                return screenshot(rect, width, height, rotation);
            } catch (Exception e) {
                log("createScreenshot e=" + e);
                return null;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleThemeTask(int state) {
        log("handleThemeTask state=" + state + " currentState=" + this.mCurrentState);
        if (state != this.mCurrentState) {
            switch (state) {
                case 0:
                    setThemeState(0);
                    return;
                case 1:
                    setThemeState(1);
                    if (isBootAnimaExit() && !isInAutopilot()) {
                        postThemeWindowRunnable();
                    }
                    postThemeTimeoutRunnable(UiModeManager.THEME_TIMEOUT_DELAY);
                    return;
                case 2:
                    setThemeState(2);
                    if (!setConfigurationLocked(this.mContext, createUiMode(this.mContext, this.mDayNightMode, this.mDayNightRealMode))) {
                        setConfigurationLocked(this.mContext, createUiMode(this.mContext, this.mDayNightMode, this.mDayNightRealMode));
                    }
                    postThemeTimeoutRunnable(UiModeManager.THEME_TIMEOUT_DELAY);
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleThemeIntent(Intent intent) {
        int id;
        try {
            boolean isUserMode = "user".equals(Build.TYPE);
            if (intent != null && !isUserMode) {
                String action = intent.getAction();
                if (ACTION_SWITCH_DAYNIGHT.equals(action)) {
                    int realMode = this.mDayNightRealMode;
                    int toMode = 2;
                    switch (realMode) {
                        case 1:
                            toMode = 2;
                            break;
                        case 2:
                            toMode = 1;
                            break;
                    }
                    log("handleThemeIntent toMode=" + toMode + " currentMode=" + realMode);
                    applyDayNightMode(toMode);
                } else if (ACTION_SWITCH_THEME.equals(action) && (id = intent.getIntExtra("themeid", 0)) > 0) {
                    applyThemeMode(id);
                }
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleThemeWindow(Context context, final WindowManager wm, Bitmap bitmap, final Rect rect) {
        if (wm != null && bitmap != null && rect != null) {
            try {
                if (this.mThemeWindowView == null) {
                    int width = rect.right - rect.left;
                    int height = rect.bottom - rect.top;
                    this.mThemeWindowView = ThemeWindowHelper.createThemeWindow(context, width, height);
                }
                if (this.mThemeWindowView != null) {
                    BitmapDrawable drawable = new BitmapDrawable(bitmap);
                    final ThemeAlphaDrawable alphaDrawable = new ThemeAlphaDrawable(drawable);
                    this.mThemeWindowView.setBackground(alphaDrawable);
                    this.mThemeWindowView.setViewListener(this.mThemeViewListener);
                    ThemeWindowHelper.handleThemeView(wm, this.mThemeWindowView, rect, 1);
                    this.mThemeWindowView.postDelayed(new Runnable() { // from class: com.android.server.UiThemeManager.7
                        @Override // java.lang.Runnable
                        public void run() {
                            UiThemeManager.this.handleThemeTask(2);
                        }
                    }, 10L);
                    this.mThemeWindowView.postDelayed(new Runnable() { // from class: com.android.server.UiThemeManager.8
                        @Override // java.lang.Runnable
                        public void run() {
                            UiThemeManager.this.mThemeWindowView.clearAnimation();
                            alphaDrawable.startAnimation(UiThemeManager.THEME_ANIMATION_INTERVAL, 255, 0);
                        }
                    }, THEME_ANIMATION_DELAY);
                    this.mThemeWindowView.postDelayed(new Runnable() { // from class: com.android.server.UiThemeManager.9
                        @Override // java.lang.Runnable
                        public void run() {
                            ThemeWindowHelper.handleThemeView(wm, UiThemeManager.this.mThemeWindowView, rect, 2);
                        }
                    }, THEME_ANIMATION_INTERVAL + THEME_ANIMATION_DELAY);
                }
            } catch (Exception e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleThemeTwilight(TwilightState state) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                if (this.mDayNightMode == 0) {
                    int realMode = getDayNightRealMode(this.mDayNightMode);
                    long delay = isThemeWorking() ? UiModeManager.THEME_TIMEOUT_DELAY : 0L;
                    Runnable runnable = new Runnable() { // from class: com.android.server.UiThemeManager.10
                        @Override // java.lang.Runnable
                        public void run() {
                            try {
                                UiThemeManager.this.handleThemeTask(1);
                            } catch (Exception e) {
                            }
                        }
                    };
                    this.mHandler.postDelayed(runnable, delay);
                    log("handleThemeTwilight mode=" + this.mDayNightMode + " realMode=" + realMode + " dayNightRealMode=" + this.mDayNightRealMode + " delay=" + delay);
                    this.mDayNightRealMode = realMode;
                }
            }
        } catch (Exception e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    private void setThemeMode(int value) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                if (this.mThemeMode != value) {
                    this.mThemeMode = value;
                    putSettingsSecureInt(KEY_THEME_MODE, value);
                    SystemProperties.set(PROP_THEME_ID, String.valueOf(value));
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setDayNightMode(int value) {
        switch (value) {
            case 0:
            case 1:
            case 2:
                long ident = Binder.clearCallingIdentity();
                try {
                    synchronized (this.mLock) {
                        if (this.mDayNightMode != value) {
                            this.mDayNightMode = value;
                            this.mDayNightRealMode = getDayNightRealMode(this.mDayNightMode);
                            putSettingsSecureInt(KEY_DAYNIGHT_MODE, value);
                        }
                        log("setDayNightMode value=" + value + " mode=" + this.mDayNightMode + " realMode=" + this.mDayNightRealMode);
                    }
                    return;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            default:
                throw new IllegalArgumentException("Unknown value: " + value);
        }
    }

    private void setThemeState(int state) {
        log("setThemeState state=" + state + " currentState=" + this.mCurrentState);
        if (this.mCurrentState != state) {
            switch (state) {
                case 0:
                case 1:
                case 2:
                    this.mCurrentState = state;
                    putSettingsSecureInt(KEY_THEME_STATE, state);
                    return;
                default:
                    return;
            }
        }
    }

    private int getThemeState() {
        return this.mCurrentState;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Rect getThemeWindowRect() {
        if (this.mThemeWindowRect == null) {
            this.mThemeWindowRect = ThemeWindowHelper.getDisplayRect(this.mWindowManager);
        }
        return this.mThemeWindowRect;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getThemeWindowRotation() {
        if (this.mThemeWindowRotation == -1) {
            this.mThemeWindowRotation = ThemeWindowHelper.getDisplayRotation(this.mWindowManager);
        }
        return this.mThemeWindowRotation;
    }

    private boolean setConfigurationLocked(Context context, int uiMode) {
        boolean ret;
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                Configuration configuration = context.getResources().getConfiguration();
                Configuration newConfig = new Configuration(configuration);
                newConfig.uiMode = uiMode;
                ret = ActivityManager.getService().updateConfiguration(newConfig);
                log("setConfigurationLocked uiMode=" + uiMode + " ret=" + ret + " isNight=" + isNightMode(configuration));
            }
            return ret;
        } catch (Exception e) {
            log("setConfigurationLocked e=" + e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int createUiMode(Context context, int daynightMode, int daynightRealMode, int state) {
        Configuration configuration = context.getResources().getConfiguration();
        int uiMode = configuration.uiMode & UI_MODE_THEME_CLEAR;
        switch (state) {
            case 1:
                uiMode |= 64;
                switch (daynightMode) {
                    case 0:
                        switch (daynightRealMode) {
                            case 1:
                                uiMode = (uiMode & 207) | 16;
                                break;
                            case 2:
                                uiMode = (uiMode & 207) | 32;
                                break;
                        }
                    case 1:
                        uiMode = (uiMode & 207) | 16;
                        break;
                    case 2:
                        uiMode = (uiMode & 207) | 32;
                        break;
                }
            case 2:
                uiMode |= 128;
                break;
        }
        log("createUiMode uiMode=" + uiMode + " state=" + state + " mode=" + daynightMode + " realMode=" + daynightRealMode);
        return uiMode;
    }

    private int createUiMode(Context context, int daynightMode, int daynightRealMode) {
        Configuration configuration = context.getResources().getConfiguration();
        int uiMode = configuration.uiMode;
        int themeMode = uiMode & UI_MODE_THEME_MASK;
        int uiMode2 = (uiMode & UI_MODE_THEME_CLEAR) | (themeMode == 64 ? 128 : 64);
        switch (daynightMode) {
            case 0:
                switch (daynightRealMode) {
                    case 1:
                        uiMode2 = (uiMode2 & 207) | 16;
                        break;
                    case 2:
                        uiMode2 = (uiMode2 & 207) | 32;
                        break;
                }
            case 1:
                uiMode2 = (uiMode2 & 207) | 16;
                break;
            case 2:
                uiMode2 = (uiMode2 & 207) | 32;
                break;
        }
        log("createUiMode uiMode=" + uiMode2 + " mode=" + daynightMode + " realMode=" + daynightRealMode);
        return uiMode2;
    }

    private void postThemeWindowRunnable() {
        log("postThemeWindowRunnable");
        this.mHandler.removeCallbacks(this.mThemeWindowRunnable);
        this.mHandler.postDelayed(this.mThemeWindowRunnable, 0L);
    }

    private void postThemeTimeoutRunnable(long delay) {
        log("postThemeTimeoutRunnable delay=" + delay);
        this.mHandler.removeCallbacks(this.mThemeTimeoutRunnable);
        this.mHandler.postDelayed(this.mThemeTimeoutRunnable, delay);
    }

    private void putSettingsSecureInt(String key, int value) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this.mLock) {
                Context context = this.mContext;
                if (context != null && !TextUtils.isEmpty(key)) {
                    Settings.Secure.putInt(context.getContentResolver(), key, value);
                }
            }
        } catch (Exception e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    private int getSettingsSecureInt(String key, int defValue) {
        long ident = Binder.clearCallingIdentity();
        try {
        } catch (Exception e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        synchronized (this.mLock) {
            Context context = this.mContext;
            if (context != null && !TextUtils.isEmpty(key)) {
                int i = Settings.Secure.getInt(context.getContentResolver(), key, defValue);
                Binder.restoreCallingIdentity(ident);
                return i;
            }
            Binder.restoreCallingIdentity(ident);
            return defValue;
        }
    }

    private int getDayNightRealMode(int daynightMode) {
        int realMode = 1;
        switch (daynightMode) {
            case 0:
                realMode = isTwilightNight(this.mTwilightManager) ? 2 : 1;
                break;
            case 1:
            case 2:
                realMode = daynightMode;
                break;
        }
        log("getDayNightRealMode mode=" + daynightMode + " realMode=" + realMode);
        return realMode;
    }

    private void updateTwilightListener(int daynightMode) {
        try {
            if (this.mTwilightManager != null) {
                log("updateTwilightListener mode=" + daynightMode);
                if (daynightMode == 0) {
                    this.mTwilightManager.registerListener(this.mTwilightListener, this.mHandler);
                } else {
                    this.mTwilightManager.unregisterListener(this.mTwilightListener);
                }
            }
        } catch (Exception e) {
        }
    }

    public static boolean isTwilightNight(TwilightManager manager) {
        boolean isNight = false;
        String country = Locale.getDefault().getCountry();
        if (!"CN".equals(country)) {
            String longitude = SystemProperties.get(PROP_THEME_LONGITUDE, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            if (BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS.equals(longitude)) {
                Calendar calendar = Calendar.getInstance();
                int hour = calendar.get(11);
                return hour < 6 || hour >= 20;
            }
        }
        TwilightState lastState = manager != null ? manager.getLastTwilightState() : null;
        TwilightState calcState = calculateTwilightState(getLongitude(), getLatitude());
        boolean lastNight = lastState != null ? lastState.isNight() : false;
        boolean calcNight = calcState != null ? calcState.isNight() : false;
        if (calcState != null) {
            isNight = calcNight;
        } else if (lastState != null) {
            isNight = lastNight;
        }
        printTwilightState("lastState", lastState);
        printTwilightState("calcState", calcState);
        StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        buffer.append("isTwilightNight");
        buffer.append(" lastNonNull = ");
        buffer.append(lastState != null);
        buffer.append(" calcNonNull = ");
        buffer.append(calcState != null);
        buffer.append(" lastNight = " + lastNight);
        buffer.append(" calcNight = " + calcNight);
        buffer.append(" isNight = " + isNight);
        log(buffer.toString());
        return isNight;
    }

    private static double getLatitude() {
        try {
            double latitude = Double.valueOf(SystemProperties.get(PROP_THEME_LATITUDE)).doubleValue();
            return latitude;
        } catch (Exception e) {
            return gLatitude;
        }
    }

    private static double getLongitude() {
        try {
            double longitude = Double.valueOf(SystemProperties.get(PROP_THEME_LONGITUDE)).doubleValue();
            return longitude;
        } catch (Exception e) {
            return gLongitude;
        }
    }

    private static void printTwilightState(String msg, TwilightState state) {
        StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        buffer.append("printTwilightState");
        buffer.append(" " + msg);
        buffer.append(" timezone=" + TimeZone.getDefault().getID());
        if (state != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            buffer.append(" isNight = " + state.isNight());
            buffer.append(" systemtime = " + System.currentTimeMillis());
            buffer.append(" sunriseTimeMillis = " + sdf.format(Long.valueOf(state.sunriseTimeMillis())));
            buffer.append(" sunsetTimeMillis = " + sdf.format(Long.valueOf(state.sunsetTimeMillis())));
        } else {
            buffer.append(" state = null");
        }
        log(buffer.toString());
    }

    private static TwilightState calculateTwilightState(double longitude, double latitude) {
        return calculateTwilightState(longitude, latitude, System.currentTimeMillis());
    }

    private static TwilightState calculateTwilightState(double longitude, double latitude, long timeMillis) {
        CalendarAstronomer ca = new CalendarAstronomer(longitude, latitude);
        android.icu.util.Calendar noon = android.icu.util.Calendar.getInstance();
        try {
            String timezone = TimeZone.getDefault().getID();
            if (!TextUtils.isEmpty(timezone)) {
                noon.setTimeZone(android.icu.util.TimeZone.getTimeZone(timezone));
            }
        } catch (Exception e) {
        }
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
        return new TwilightState(sunriseTimeMillis, sunsetTimeMillis);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ThemeWindowView extends FrameLayout {
        private OnViewListener mViewListener;

        /* loaded from: classes.dex */
        public interface OnViewListener {
            default void onAttachedToWindow() {
            }

            default void onDetachedFromWindow() {
            }

            default void onConfigurationChanged(Configuration newConfig) {
            }

            default void onWindowVisibilityChanged(int visibility) {
            }

            default void onWindowFocusChanged(boolean hasWindowFocus) {
            }
        }

        public ThemeWindowView(Context context) {
            super(context);
        }

        public ThemeWindowView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public ThemeWindowView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        @Override // android.view.ViewGroup, android.view.View
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            UiThemeManager.log("ThemeWindowView onAttachedToWindow");
            if (this.mViewListener != null) {
                this.mViewListener.onAttachedToWindow();
            }
        }

        @Override // android.view.ViewGroup, android.view.View
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            UiThemeManager.log("ThemeWindowView onDetachedFromWindow");
            if (this.mViewListener != null) {
                this.mViewListener.onDetachedFromWindow();
            }
        }

        @Override // android.view.View
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            UiThemeManager.log("ThemeWindowView onConfigurationChanged isNight=" + UiThemeManager.isNightMode(newConfig) + " uiMode=" + newConfig.uiMode);
            if (this.mViewListener != null) {
                this.mViewListener.onConfigurationChanged(newConfig);
            }
        }

        @Override // android.view.View
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            UiThemeManager.log("ThemeWindowView onWindowVisibilityChanged visibility=" + visibility);
            if (this.mViewListener != null) {
                this.mViewListener.onWindowVisibilityChanged(visibility);
            }
        }

        @Override // android.view.View
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            UiThemeManager.log("ThemeWindowView onWindowFocusChanged hasWindowFocus=" + hasWindowFocus);
            if (this.mViewListener != null) {
                this.mViewListener.onWindowFocusChanged(hasWindowFocus);
            }
        }

        @Override // android.view.View
        public void setBackground(Drawable background) {
            super.setBackground(background);
        }

        public void setViewListener(OnViewListener listener) {
            this.mViewListener = listener;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ThemeWindowHelper {
        public static final int TYPE_VIEW_ATTACH = 1;
        public static final int TYPE_VIEW_DETACH = 2;

        private ThemeWindowHelper() {
        }

        public static Rect getDisplayRect(WindowManager wm) {
            if (wm != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(metrics);
                return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
            }
            return null;
        }

        public static int getDisplayRotation(WindowManager wm) {
            Display display;
            if (wm != null && (display = wm.getDefaultDisplay()) != null) {
                return display.getRotation();
            }
            return 0;
        }

        public static Rect getViewRect(View view) {
            if (view != null) {
                int width = view.getWidth();
                int height = view.getHeight();
                int[] location = getViewLocation(view);
                if (location != null && location.length == 2) {
                    Rect rect = new Rect(location[0], location[1], location[0] + width, location[1] + height);
                    return rect;
                }
                return null;
            }
            return null;
        }

        public static int[] getViewLocation(View view) {
            int[] location = new int[2];
            if (view != null) {
                view.getLocationOnScreen(location);
            }
            return location;
        }

        public static void handleThemeView(WindowManager wm, View view, Rect rect, int type) {
            if (wm != null && view != null) {
                switch (type) {
                    case 1:
                        if (!view.isAttachedToWindow() && rect != null) {
                            try {
                                int x = rect.left;
                                int y = rect.top;
                                int width = rect.right - rect.left;
                                int height = rect.bottom - rect.top;
                                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height, 2036, 8650792, -3);
                                lp.setTitle("ThemeView");
                                lp.gravity = 51;
                                lp.format = 1;
                                lp.x = x;
                                lp.y = y;
                                lp.width = width;
                                lp.height = height;
                                lp.windowAnimations = 0;
                                wm.addView(view, lp);
                                return;
                            } catch (Exception e) {
                                return;
                            }
                        }
                        return;
                    case 2:
                        try {
                            if (view.isAttachedToWindow()) {
                                wm.removeViewImmediate(view);
                                return;
                            }
                            return;
                        } catch (Exception e2) {
                            return;
                        }
                    default:
                        return;
                }
            }
        }

        public static ImageView createThemeView(Context context, int width, int height) {
            ImageView imageView = new ImageView(context);
            imageView.setMaxWidth(width);
            imageView.setMaxHeight(height);
            imageView.setBackgroundColor(0);
            return imageView;
        }

        public static ViewGroup createThemeLayout(Context context, View view, int width, int height) {
            if (view != null) {
                FrameLayout layout = new FrameLayout(context);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
                layout.setLayoutParams(lp);
                layout.addView(view, lp);
                return layout;
            }
            return null;
        }

        public static ThemeWindowView createThemeWindow(Context context, int width, int height) {
            ThemeWindowView layout = new ThemeWindowView(context);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
            layout.setLayoutParams(lp);
            layout.setBackgroundColor(0);
            return layout;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isNightMode(Configuration configuration) {
        return configuration != null && (configuration.uiMode & 48) == 32;
    }

    private boolean isBootAnimaExit() {
        return "1".equals(SystemProperties.get("service.bootanim.exit")) || "stopped".equals(SystemProperties.get("init.svc.bootanim"));
    }

    private boolean isInAutopilot() {
        try {
            if (3 == xpTextUtils.toInteger(ExternalManagerService.get(this.mContext).getValue(2, "getCarGearLevel", new Object[0]), 0).intValue()) {
                return true;
            }
            return isForeground(this.mContext, "com.xiaopeng.autopilot");
        } catch (Exception e) {
            log("isInAutopilot e=" + e);
            return true;
        }
    }

    public static boolean isForeground(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return false;
        }
        ActivityManager am = (ActivityManager) context.getSystemService("activity");
        List<ActivityManager.RunningTaskInfo> list = am.getRunningTasks(1);
        if (list != null && list.size() > 0) {
            ComponentName cpn = list.get(0).topActivity;
            if (packageName.equals(cpn.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void log(String msg) {
        xpLogger.slog(TAG, msg);
    }
}
