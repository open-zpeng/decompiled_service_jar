package com.xiaopeng.server.app;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.WindowManager;
import com.android.server.wm.SharedDisplayContainer;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.server.wallpaper.xpWallpaperManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.SharedDisplayListener;
import com.xiaopeng.view.xpWindowManager;
import java.util.ArrayList;

/* loaded from: classes2.dex */
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
    private WindowManager mWindowManager;
    public static final boolean DESKTOP_HOME_ENABLED = FeatureOption.FO_HOME_DESKTOP_SUPPORT;
    private static ComponentName sHomeComponent = null;
    private static final Object sHomeLock = new Object();
    private static final ArrayList<String> sHomeList = new ArrayList<>();
    private final Object mLock = new Object();
    private volatile boolean mHasHome = false;
    private String mCurrentComponent = "";
    private String mHistoryComponent = "";
    private SharedDisplayListenerImpl mSharedDisplayListener = new SharedDisplayListenerImpl();
    private Handler mHandler = new Handler() { // from class: com.xiaopeng.server.app.xpHomeManagerService.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == 100) {
                xpHomeManagerService.this.onHomeChanged(msg.obj, msg.arg1);
            }
        }
    };

    public xpHomeManagerService(Context context) {
        this.mContext = context;
    }

    public void systemReady() {
        this.mWindowManager = (WindowManager) this.mContext.getSystemService("window");
        WindowManager windowManager = this.mWindowManager;
        if (windowManager != null) {
            windowManager.registerSharedListener(this.mSharedDisplayListener);
        }
    }

    public void setHomeState(ComponentName component, int state) {
        if (DESKTOP_HOME_ENABLED) {
            Handler handler = this.mHandler;
            handler.sendMessage(handler.obtainMessage(100, state, 0, component));
        }
    }

    public void setHomeState(WindowManager.LayoutParams lp, int state) {
        if (DESKTOP_HOME_ENABLED) {
            Handler handler = this.mHandler;
            handler.sendMessage(handler.obtainMessage(100, state, 0, lp));
        }
    }

    public void onHomeChanged(Object obj, int state) {
        if (obj == null) {
            return;
        }
        ComponentName component = null;
        if (obj instanceof ComponentName) {
            component = (ComponentName) obj;
        } else if (obj instanceof WindowManager.LayoutParams) {
            try {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) obj;
                String componentName = SharedDisplayContainer.parseTokenName(lp);
                if (!TextUtils.isEmpty(componentName)) {
                    component = ComponentName.unflattenFromString(componentName);
                }
            } catch (Exception e) {
            }
        }
        if (component == null) {
            return;
        }
        boolean isUserHome = isUserHome(this.mContext, component);
        xpLogger.i(TAG, "onStateChanged state=" + state + " hasHome=" + this.mHasHome + " isUserHome=" + isUserHome + " component=" + component);
        if (state == 1) {
            if (isUserHome) {
                synchronized (sHomeList) {
                    String value = component.flattenToString();
                    if (!TextUtils.isEmpty(value)) {
                        sHomeList.add(value);
                    }
                    shouldVisible = sHomeList.isEmpty() ? false : true;
                    this.mHasHome = shouldVisible;
                }
                setWallpaper(this.mHasHome);
            }
        } else if (state == 2) {
            if (isUserHome) {
                synchronized (sHomeList) {
                    String value2 = component.flattenToString();
                    if (!TextUtils.isEmpty(value2) && sHomeList.contains(value2)) {
                        sHomeList.remove(value2);
                    }
                    shouldVisible = sHomeList.isEmpty() ? false : true;
                    this.mHasHome = shouldVisible;
                }
                setWallpaper(this.mHasHome);
            }
        } else if (state == 3) {
            if (isUserHome && this.mHasHome) {
                setDrawState(true);
                setWallpaper(true);
                setHomeSettings(state, component);
            }
        } else if (state == 4) {
            boolean isHomePaused = isUserHome(this.mContext, getComponentName(this.mHistoryComponent));
            if (isHomePaused && xpWindowManager.shouldVisibleWhenPaused(this.mContext, component)) {
                shouldVisible = true;
            }
            setDrawState(shouldVisible);
            setWallpaper(shouldVisible);
            setHomeSettings(state, component);
        }
    }

    public void onActivityChanged() {
        String primary = this.mWindowManager.getTopActivity(0, 0);
        String secondary = this.mWindowManager.getTopActivity(0, 1);
        ComponentName primaryComponent = !TextUtils.isEmpty(primary) ? ComponentName.unflattenFromString(primary) : null;
        if (!TextUtils.isEmpty(secondary)) {
            ComponentName.unflattenFromString(secondary);
        }
        xpLogger.i(TAG, "onActivityChanged primary=" + primary + " secondary=" + secondary);
        boolean isUserHome = isUserHome(this.mContext, primaryComponent);
        this.mHistoryComponent = this.mCurrentComponent;
        this.mCurrentComponent = primary;
        if (isUserHome) {
            setHomeState(primaryComponent, 3);
        } else {
            setHomeState(primaryComponent, 4);
        }
    }

    public void setWallpaper(boolean shadow) {
        xpWallpaperManagerService.get(this.mContext).setHomeWallpaper(shadow);
    }

    public void setDrawState(boolean state) {
    }

    /* renamed from: com.xiaopeng.server.app.xpHomeManagerService$2  reason: invalid class name */
    /* loaded from: classes2.dex */
    class AnonymousClass2 implements Runnable {
        AnonymousClass2() {
        }

        @Override // java.lang.Runnable
        public void run() {
            SystemProperties.set("persist.xp.home.draw.state", "0");
        }
    }

    public void setHomeSettings(int state, ComponentName component) {
        Settings.Secure.putInt(this.mContext.getContentResolver(), KEY_HOME_STATE, state);
    }

    public static ComponentName getComponentName(String component) {
        if (TextUtils.isEmpty(component)) {
            return null;
        }
        return ComponentName.unflattenFromString(component);
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

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class SharedDisplayListenerImpl extends SharedDisplayListener {
        private SharedDisplayListenerImpl() {
        }

        public void onChanged(String s, int i) throws RemoteException {
        }

        public void onPositionChanged(String packageName, int event, int from, int to) throws RemoteException {
            super.onPositionChanged(packageName, event, from, to);
            if (event != 1 && event != 2 && event == 4) {
                xpHomeManagerService.this.onActivityChanged();
            }
        }

        public void onActivityChanged(int screenId, String property) throws RemoteException {
            super.onActivityChanged(screenId, property);
            xpHomeManagerService.this.onActivityChanged();
        }
    }
}
