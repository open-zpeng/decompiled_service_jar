package com.xiaopeng.server.app;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import com.android.server.backup.BackupManagerConstants;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.server.wallpaper.xpWallpaperManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import java.util.ArrayList;
/* loaded from: classes.dex */
public class xpHomeManagerService {
    private static final String KEY_HOME_STATE = "key_system_home_state";
    private static final int MSG_HOME_STATE_CHANGED = 100;
    public static final int STATE_HOME_ADDED = 1;
    public static final int STATE_HOME_PAUSED = 4;
    public static final int STATE_HOME_REMOVED = 2;
    public static final int STATE_HOME_RESUMED = 3;
    public static final int STATE_HOME_STOPPED = 5;
    private static final String TAG = "xpHomeManagerService";
    private Context mContext;
    public static final boolean DESKTOP_HOME_ENABLED = FeatureOption.FO_HOME_DESKTOP_SUPPORT;
    private static ComponentName sHomeComponent = null;
    private static final Object sHomeLock = new Object();
    private static final ArrayList<String> sHomeList = new ArrayList<>();
    private final Object mLock = new Object();
    private volatile boolean mHasHome = false;
    private volatile boolean mHomeStopped = false;
    private volatile boolean mHomePausingVisible = true;
    private volatile int mHomeSettings = 0;
    private volatile int mResumedActivityFlags = 0;
    private volatile int mResumedActivityWindow = 0;
    private volatile int mPausingActivityWindow = 0;
    private Handler mHandler = new Handler() { // from class: com.xiaopeng.server.app.xpHomeManagerService.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == 100) {
                xpHomeManagerService.this.onStateChanged((ComponentName) msg.obj, msg.arg1);
            }
        }
    };

    public xpHomeManagerService(Context context) {
        this.mContext = context;
    }

    public void setState(ComponentName component, int state) {
        if (DESKTOP_HOME_ENABLED) {
            this.mHandler.sendMessage(this.mHandler.obtainMessage(100, state, 0, component));
        }
    }

    public void setResumedActivity(ComponentName component, int window, int flags) {
        this.mResumedActivityFlags = flags;
        this.mPausingActivityWindow = this.mResumedActivityWindow;
        this.mResumedActivityWindow = window;
        if (window == 5) {
            boolean isUserHome = isUserHome(this.mContext, component);
            if (!isUserHome || !this.mHomeStopped) {
                return;
            }
            this.mHomeStopped = false;
        }
    }

    public void onStateChanged(ComponentName component, int state) {
        String value;
        String value2;
        boolean isUserHome = isUserHome(this.mContext, component);
        xpLogger.i(TAG, "onStateChanged state=" + state + " hasHome=" + this.mHasHome + " pausing=" + this.mPausingActivityWindow + " homePausingVisible=" + this.mHomePausingVisible + " stopped=" + this.mHomeStopped + " isUserHome=" + isUserHome);
        switch (state) {
            case 1:
                if (isUserHome) {
                    synchronized (sHomeList) {
                        if (component == null) {
                            value = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                        } else {
                            try {
                                value = component.flattenToString();
                            } finally {
                            }
                        }
                        if (!TextUtils.isEmpty(value)) {
                            sHomeList.add(value);
                        }
                        this.mHasHome = true ^ sHomeList.isEmpty();
                    }
                    setWallpaper(this.mHasHome);
                    return;
                }
                return;
            case 2:
                if (isUserHome) {
                    synchronized (sHomeList) {
                        if (component == null) {
                            value2 = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                        } else {
                            try {
                                value2 = component.flattenToString();
                            } finally {
                            }
                        }
                        if (!TextUtils.isEmpty(value2) && sHomeList.contains(value2)) {
                            sHomeList.remove(value2);
                        }
                        this.mHasHome = true ^ sHomeList.isEmpty();
                    }
                    setWallpaper(this.mHasHome);
                    return;
                }
                return;
            case 3:
                if (isUserHome && this.mHasHome && !this.mHomeStopped) {
                    setDrawState(true);
                    setWallpaper(true);
                    setHomeSettings(true);
                    return;
                }
                return;
            case 4:
                if (this.mHasHome) {
                    int pausingWindow = this.mHomePausingVisible ? 5 : this.mPausingActivityWindow;
                    int resumingFlags = this.mResumedActivityFlags;
                    boolean shouldVisible = xpActivityManager.shouldVisibleWhenPaused(pausingWindow, resumingFlags);
                    setDrawState(shouldVisible);
                    setWallpaper(shouldVisible);
                    this.mHomePausingVisible = shouldVisible;
                }
                setHomeSettings(false);
                return;
            case 5:
                if (isUserHome) {
                    this.mHomeStopped = true;
                    return;
                }
                return;
            default:
                return;
        }
    }

    public void setWallpaper(boolean shadow) {
        xpWallpaperManagerService.get(this.mContext).setHomeWallpaper(shadow);
    }

    public void setDrawState(boolean state) {
    }

    /* renamed from: com.xiaopeng.server.app.xpHomeManagerService$2  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass2 implements Runnable {
        AnonymousClass2() {
        }

        @Override // java.lang.Runnable
        public void run() {
            SystemProperties.set("persist.xp.home.draw.state", "0");
        }
    }

    public void setHomeSettings(boolean resumed) {
        boolean resumingFreeform = false;
        int settings = resumed ? 3 : 4;
        int resumingFlags = this.mResumedActivityFlags;
        if ((resumingFlags & 512) == 512) {
            resumingFreeform = true;
        }
        int settings2 = resumingFreeform ? 3 : settings;
        int settings3 = this.mHomeSettings;
        boolean changed = settings2 != settings3;
        if (changed) {
            Settings.Secure.putInt(this.mContext.getContentResolver(), KEY_HOME_STATE, settings2);
        }
        this.mHomeSettings = settings2;
    }

    public static boolean isUserHome(Context context, ComponentName component) {
        ComponentName home = getHome(context);
        if (home == null || TextUtils.isEmpty(home.getPackageName()) || component == null || TextUtils.isEmpty(component.getPackageName())) {
            return false;
        }
        return xpActivityManager.isComponentEqual(home, component) || TextUtils.equals(home.getPackageName(), component.getPackageName());
    }

    public static ComponentName getHome(Context context) {
        if (sHomeComponent == null) {
            synchronized (sHomeLock) {
                if (sHomeComponent == null) {
                    sHomeComponent = findHome(context);
                    xpLogger.i(TAG, "findHome user home=" + sHomeComponent);
                }
            }
        }
        return sHomeComponent;
    }

    public static ComponentName findHome(Context context) {
        return ActivityInfoManager.HomeInfo.findDefaultHome(context);
    }
}
