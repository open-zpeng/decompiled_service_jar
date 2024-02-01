package com.xiaopeng.server.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageParser;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.StrictMode;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.IWindowManager;
import com.android.server.pm.PackageManagerService;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.app.xpPackageManager;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.xpWindowManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class xpPackageManagerService {
    private static final String ACTION_REFRESH_APP_CONFIG = "com.xiaopeng.xui.REFRESH_APP_CONFIG";
    private static final String PRODUCT_DEVICE_NAME = "ro.product.device";
    private static final int P_GEAR_LEVEL = 4;
    private static final String TAG = "XP.PackageManagerService";
    private static final String XP_ICONS_DIR = "/system/etc/xuiservice/icons/";
    private static final String XP_ICONS_NIGHT_DIR = "/system/etc/xuiservice/icons/night/";
    private static final String XP_ICON_SUFFIX = ".png";
    private static final String XP_PACKAGES_DEFAULT = "default";
    private static final String XP_PACKAGES_DIR = "/data/xuiservice/packages/";
    private static final String XP_PACKAGES_SUFFIX = ".json";
    private static final String XP_PRE_PACKAGES_DIR = "/system/etc/packages/";
    private boolean mBoot;
    private final Context mContext;
    private final WorkHandler mHandler;
    private BroadcastReceiver mRefreshReceiver;
    private static final boolean DEBUG = Log.isLoggable("XP.PackageManagerService.DEBUG", 3);
    private static xpPackageManagerService sService = null;
    private int mCurrentGearLevel = 4;
    private final HashMap<String, xpPackageInfo> mOverlayPackages = new HashMap<>();
    private final HashMap<String, Bitmap> mOverlayIcons = new HashMap<>();
    private final HashMap<String, Bitmap> mOverlayNightIcons = new HashMap<>();
    private volatile int mAppWidth = 0;
    private volatile int mAppHeight = 0;
    private volatile int mDisplayWidth = 0;
    private volatile int mDisplayHeight = 0;
    private BroadcastReceiver mPackageReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.server.app.xpPackageManagerService.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            IPackageManager pm = xpPackageManager.getPackageManager();
            int uid = intent.getIntExtra("android.intent.extra.UID", -1);
            if (uid != -1 && pm != null && "android.intent.action.PACKAGE_ADDED".equals(action)) {
                try {
                    Uri uri = intent.getData();
                    String packageName = uri != null ? uri.getSchemeSpecificPart() : null;
                    xpLogger.i(xpPackageManagerService.TAG, "onReceive setPackageNotLaunched packageName=" + packageName + " uid=" + uid);
                    pm.setPackageNotLaunched(packageName, true, uid);
                } catch (Exception e) {
                }
            }
        }
    };
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);

    private xpPackageManagerService(Context context) {
        this.mContext = context;
        this.mHandlerThread.start();
        this.mHandler = new WorkHandler(this.mHandlerThread.getLooper());
        initScreenSize();
    }

    public static xpPackageManagerService get(Context context) {
        if (sService == null) {
            synchronized (xpPackageManagerService.class) {
                if (sService == null) {
                    sService = new xpPackageManagerService(context);
                }
            }
        }
        return sService;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initScreenSize() {
        boolean appInit = false;
        boolean displayInit = false;
        IWindowManager wm = xpWindowManager.getWindowManager();
        if (wm != null) {
            try {
                Rect appRect = wm.getXuiRectByType(1);
                Rect displayRect = xpWindowManager.getDisplayBounds(this.mContext);
                if (appRect != null) {
                    this.mAppWidth = appRect.width();
                    this.mAppHeight = appRect.height();
                    appInit = true;
                }
                if (displayRect != null) {
                    this.mDisplayWidth = displayRect.width();
                    this.mDisplayHeight = displayRect.height();
                    displayInit = true;
                }
            } catch (Exception e) {
            }
        }
        xpLogger.i(TAG, "initScreenSize appWidth=" + this.mAppWidth + " appHeight=" + this.mAppHeight + " displayWidth=" + this.mDisplayWidth + " displayHeight=" + this.mDisplayHeight);
        if (!appInit || !displayInit) {
            this.mHandler.postDelayed(new Runnable() { // from class: com.xiaopeng.server.app.xpPackageManagerService.1
                @Override // java.lang.Runnable
                public void run() {
                    xpPackageManagerService.this.initScreenSize();
                }
            }, 1000L);
        }
    }

    public void onStart() {
        Runnable runnable = new Runnable() { // from class: com.xiaopeng.server.app.xpPackageManagerService.2
            @Override // java.lang.Runnable
            public void run() {
                xpPackageManagerService.this.loadOverlayPackageInfo(xpPackageManagerService.XP_PRE_PACKAGES_DIR);
                xpPackageManagerService.this.loadOverlayPackageInfo(xpPackageManagerService.XP_PACKAGES_DIR);
                if (FeatureOption.FO_APP_ICON_OVERLAY_ENABLED) {
                    xpPackageManagerService.this.loadOverlayIcons();
                    xpPackageManagerService.this.loadOverlayNightIcons();
                }
                xpPackageManagerService.this.mBoot = true;
            }
        };
        this.mHandler.post(runnable);
    }

    public void onStop() {
    }

    public void systemReady() {
        initRefreshReceiver();
        initPackageReceiver();
        onStart();
    }

    public Configuration getOverlayConfiguration(Configuration config, xpPackageInfo info, Rect appRect) {
        if (config != null && info != null) {
            config.fontScale = info.fontScale >= 0.0f ? info.fontScale : config.fontScale;
            config.screenLayout = info.screenLayout >= 0 ? info.screenLayout : config.screenLayout;
            config.navigation = info.navigation >= 0 ? info.navigation : config.navigation;
            config.orientation = info.orientation >= 0 ? info.orientation : config.orientation;
            config.screenWidthDp = info.screenWidthDp >= 0 ? info.screenWidthDp : config.screenWidthDp;
            config.screenHeightDp = info.screenHeightDp >= 0 ? info.screenHeightDp : config.screenHeightDp;
            config.densityDpi = info.densityDpi >= 0 ? info.densityDpi : config.densityDpi;
            if (appRect != null) {
                config.windowConfiguration.setAppBounds(appRect.right - config.screenWidthDp, appRect.bottom - config.screenHeightDp, config.screenWidthDp, config.screenHeightDp);
            }
        }
        return config;
    }

    public xpPackageInfo getXpPackageInfo(String packageName) {
        int oldPolicyMask = StrictMode.allowThreadDiskReadsMask();
        try {
            xpPackageInfo packageInfo = getXpPackageInfoInner(packageName);
            return packageInfo;
        } finally {
            StrictMode.setThreadPolicyMask(oldPolicyMask);
        }
    }

    public List<xpPackageInfo> getAllXpPackageInfos() {
        List<xpPackageInfo> result = new ArrayList<>();
        Set<Map.Entry<String, xpPackageInfo>> eSet = this.mOverlayPackages.entrySet();
        for (Map.Entry<String, xpPackageInfo> entry : eSet) {
            result.add(entry.getValue());
        }
        return result;
    }

    private xpPackageInfo getXpPackageInfoInner(String packageName) {
        if (isPackageOverlayEnable(packageName)) {
            synchronized (this.mOverlayPackages) {
                if (!TextUtils.isEmpty(packageName)) {
                    if (this.mOverlayPackages.containsKey(packageName)) {
                        return this.mOverlayPackages.get(packageName);
                    } else if (this.mOverlayPackages.containsKey("default")) {
                        return this.mOverlayPackages.get("default");
                    } else if (!this.mBoot && isConfigFileExsit(XP_PACKAGES_DIR, packageName)) {
                        return getXpPackageInfoFromFile(XP_PACKAGES_DIR, packageName);
                    } else if (!this.mBoot && isConfigFileExsit(XP_PRE_PACKAGES_DIR, packageName)) {
                        return getXpPackageInfoFromFile(XP_PRE_PACKAGES_DIR, packageName);
                    }
                }
                return null;
            }
        }
        return null;
    }

    private void changeScreenFlagForAdaption(xpPackageInfo info) {
        if (info.screenAdaption == 1) {
            this.mCurrentGearLevel = xpActivityManagerService.get(this.mContext).getGearLevel();
            info.screenFlag = this.mCurrentGearLevel == 4 ? 7 : 0;
            info.screenWidthDp = this.mCurrentGearLevel == 4 ? this.mDisplayWidth : this.mAppWidth;
            info.screenHeightDp = this.mCurrentGearLevel == 4 ? this.mDisplayHeight : this.mAppWidth;
        }
    }

    public boolean hasXpPackageInfo() {
        boolean z;
        synchronized (this.mOverlayPackages) {
            z = this.mOverlayPackages.size() > 0;
        }
        return z;
    }

    public String getOverlayPackageName(String fileName) {
        int LENGTH = XP_PACKAGES_SUFFIX.length();
        if (!TextUtils.isEmpty(fileName) && fileName.length() > LENGTH) {
            return fileName.substring(0, fileName.lastIndexOf("."));
        }
        return null;
    }

    private String getOverlayIconPackageName(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            return fileName.substring(0, fileName.lastIndexOf("."));
        }
        return null;
    }

    public String getOverlayPackageJson(String dir, String packageName) {
        String str = TAG;
        if (!TextUtils.isEmpty(packageName)) {
            try {
                File file = new File(dir + packageName + XP_PACKAGES_SUFFIX);
                if (file.exists()) {
                    StringBuilder builder = new StringBuilder();
                    InputStreamReader sr = new InputStreamReader(new FileInputStream(file));
                    BufferedReader br = new BufferedReader(sr);
                    while (true) {
                        try {
                            String line = br.readLine();
                            if (line == null) {
                                break;
                            }
                            builder.append(line);
                        } catch (Exception e) {
                            Log.d(TAG, "loadPackageInfo read e=" + e);
                            br.close();
                        }
                    }
                    br.close();
                    sr.close();
                    str = builder.toString();
                    return str;
                }
                return null;
            } catch (Exception e2) {
                Log.d(str, "loadPackageInfo e=" + e2);
                return null;
            }
        }
        return null;
    }

    public boolean isPackageOverlayEnable(String packageName) {
        if (!TextUtils.isEmpty(packageName)) {
            if (packageName.startsWith("com.xiaopeng") || packageName.startsWith(PackageManagerService.PLATFORM_PACKAGE_NAME)) {
                return false;
            }
            return true;
        }
        return true;
    }

    public ActivityInfo getOverlayActivityInfo(ActivityInfo ai) {
        xpPackageInfo info = getXpPackageInfo(ai.packageName);
        if (info != null) {
            ai.maxAspectRatio = info.maxAspectRatio >= 0.0f ? info.maxAspectRatio : ai.maxAspectRatio;
        }
        return ai;
    }

    public ApplicationInfo getOverlayApplicationInfo(ApplicationInfo ai) {
        xpPackageInfo info = getXpPackageInfo(ai.packageName);
        if (info != null) {
            ai.maxAspectRatio = info.maxAspectRatio >= 0.0f ? info.maxAspectRatio : ai.maxAspectRatio;
            ai.versionCode = info.versionCode >= 0 ? info.versionCode : ai.versionCode;
        }
        return ai;
    }

    public void loadOverlayPackageInfo(String pkgDir) {
        try {
            File dir = new File(pkgDir);
            if (dir.isDirectory()) {
                String[] list = dir.list();
                xpLogger.log(TAG, "loadPackageInfo pkgDir:" + pkgDir + " &list=" + Arrays.toString(list));
                if (list != null && list.length > 0) {
                    for (String item : list) {
                        String packageName = getOverlayPackageName(item);
                        xpPackageInfo info = getXpPackageInfoFromFile(pkgDir, packageName);
                        if (info != null) {
                            updateOverlayPackages(info);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "loadPackageInfo e=" + e);
        }
    }

    public void setXpPackageInfo(String packageName, String packageInfo) {
        xpPackageInfo info;
        Log.w(TAG, "packageName:" + packageName + " &packageInfo:" + packageInfo);
        if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(packageInfo) && (info = parseOverlayPackageInfo(packageInfo)) != null) {
            updateOverlayPackages(info);
            saveXpPackageInfoAsFile(info.packageName, packageInfo);
        }
    }

    public void updateAppScreenFlag(int gearLevel) {
        Log.d(TAG, "updateAppScreenFlag gearLevel:" + gearLevel);
        this.mCurrentGearLevel = gearLevel;
        synchronized (this.mOverlayPackages) {
            for (xpPackageInfo info : this.mOverlayPackages.values()) {
                if (info.screenAdaption == 1) {
                    info.screenFlag = gearLevel == 4 ? 7 : 0;
                    info.screenWidthDp = gearLevel == 4 ? this.mDisplayWidth : this.mAppWidth;
                    info.screenHeightDp = gearLevel == 4 ? this.mDisplayHeight : this.mAppWidth;
                    Log.d(TAG, "updateAppScreenFlag new screenFlag : " + info.screenFlag + " &screenWidthDp:" + info.screenWidthDp + " &screenHeightDp:" + info.screenHeightDp);
                }
            }
        }
    }

    private void sendActivitySizeBroadcast(int gearLevel) {
        boolean fullscreen = gearLevel == 4;
        Intent intent = new Intent();
        intent.setAction("com.xiaopeng.intent.action.ACTIVITY_SIZE_CHANGED");
        intent.putExtra("fullscreen", fullscreen ? 1 : 0);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        xpLogger.i(TAG, "sendActivitySizeBroadcast fullscreen=" + fullscreen);
    }

    private void updateOverlayPackages(xpPackageInfo info) {
        synchronized (this.mOverlayPackages) {
            this.mOverlayPackages.put(info.packageName, info);
        }
    }

    private void saveXpPackageInfoAsFile(String packageName, String packageInfo) {
        try {
            File dir = new File(XP_PACKAGES_DIR);
            if (!dir.exists()) {
                boolean result = dir.mkdirs();
                boolean canWrite = dir.canWrite();
                Log.d(TAG, "dir not exists result:" + result + " &canWrite:" + canWrite);
            }
            boolean result2 = dir.isDirectory();
            if (result2) {
                FileUtils.stringToFile(XP_PACKAGES_DIR + packageName + XP_PACKAGES_SUFFIX, packageInfo);
            }
        } catch (Exception e) {
            Log.d(TAG, "loadPackageInfo e=" + e);
        }
    }

    public xpPackageInfo parseOverlayPackageInfo(String packageInfo) {
        if (!TextUtils.isEmpty(packageInfo)) {
            try {
                JSONObject objectAll = new JSONObject(packageInfo);
                JSONArray pkgConfig = objectAll.getJSONArray("packages");
                for (int i = 0; i < pkgConfig.length(); i++) {
                    JSONObject object = (JSONObject) pkgConfig.get(i);
                    String packageName = object.getString("packageName");
                    if (!TextUtils.isEmpty(packageName)) {
                        xpPackageInfo info = new xpPackageInfo(packageName);
                        info.versionName = getStringValue(object, "versionName");
                        info.versionCode = getIntValue(object, "versionCode");
                        info.executeMode = getIntValue(object, "executeMode");
                        info.maxAspectRatio = getFloatValue(object, "maxAspectRatio").floatValue();
                        info.screenFlag = getIntValue(object, "screenFlag");
                        info.permissionGrant = getIntValue(object, "permissionGrant");
                        info.navigationKey = getIntValue(object, "navigationKey");
                        info.floatingWindow = getIntValue(object, "floatingWindow");
                        info.gearLevel = getIntValue(object, "gearLevel");
                        info.notificationFlag = getIntValue(object, "notificationFlag");
                        info.permissionGroup = getStringValue(object, "permissionGroup");
                        info.backgroundStatus = getIntValue(object, "backgroundStatus");
                        info.enableVisualizer = getIntValue(object, "enableVisualizer");
                        info.screenAdaption = getIntValue(object, "screenAdaption");
                        info.limitFullScreenInDGear = getIntValue(object, "limitFullScreenInDGear");
                        JSONObject configObject = object.getJSONObject("configuration");
                        info.fontScale = getFloatValue(configObject, "fontScale").floatValue();
                        info.screenLayout = getIntValue(configObject, "screenLayout");
                        info.navigation = getIntValue(configObject, "navigation");
                        info.orientation = getIntValue(configObject, "orientation");
                        info.screenWidthDp = getIntValue(configObject, "screenWidthDp");
                        info.screenHeightDp = getIntValue(configObject, "screenHeightDp");
                        info.densityDpi = getIntValue(configObject, "densityDpi");
                        info.keyEvent = object.getJSONObject("keyEvent").toString();
                        info.installPermission = getIntValue(object, "installPermission");
                        info.gearLevelFlags = getIntValue(object, "gearLevelFlags");
                        info.requestAudioFocus = getIntValue(object, "requestAudioFocus");
                        info.controlFlags = getIntValue(object, "controlFlags");
                        info.moduleAuthFlags = getLongValue(object, "moduleAuthFlags");
                        info.gameImageUri = getStringValue(object, "gameImageUri");
                        info.supportGamePad = getIntValue(object, "supportGamePad");
                        info.adjustVolumeValue = getIntValue(object, "adjustVolumeValue");
                        info.apkSignInfo = getStringValue(object, "apkSignInfo");
                        JSONObject mediaInfoObject = object.optJSONObject("mediaInfo");
                        if (mediaInfoObject != null) {
                            info.appType = getIntValue(object, "appType");
                            info.mediaTitleKey = getStringValue(mediaInfoObject, "mediaTitleKey");
                            info.mediaArtistKey = getStringValue(mediaInfoObject, "mediaArtistKey");
                        }
                        return info;
                    }
                }
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }

    private int getIntValue(JSONObject object, String tag) {
        return Integer.valueOf(getStringValue(object, tag)).intValue();
    }

    private long getLongValue(JSONObject object, String tag) {
        return Long.parseLong(getStringValue(object, tag));
    }

    private Float getFloatValue(JSONObject object, String tag) {
        return Float.valueOf(getStringValue(object, tag));
    }

    private String getStringValue(JSONObject object, String tag) {
        try {
            return object.getString(tag);
        } catch (Exception e) {
            return "0";
        }
    }

    private boolean isConfigFileExsit(String dirPath, String packageName) {
        File dir = new File(dirPath);
        File file = new File(dirPath + packageName + XP_PACKAGES_SUFFIX);
        return dir.isDirectory() && file.exists();
    }

    private xpPackageInfo getXpPackageInfoFromFile(String dir, String packageName) {
        String packageJson = getOverlayPackageJson(dir, packageName);
        xpPackageInfo info = parseOverlayPackageInfo(packageJson);
        changeScreenFlagForAdaption(info);
        synchronized (this.mOverlayPackages) {
            if (info != null) {
                this.mOverlayPackages.put(packageName, info);
            }
        }
        Log.w(TAG, "getXpPackageInfoFromFile loadPackageInfo dir:" + dir + " &packageJson=" + packageJson);
        return info;
    }

    private void initRefreshReceiver() {
        if (this.mRefreshReceiver == null) {
            this.mRefreshReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.server.app.xpPackageManagerService.3
                @Override // android.content.BroadcastReceiver
                public void onReceive(Context context, Intent intent) {
                    Slog.d(xpPackageManagerService.TAG, "onReceive: " + intent.getAction());
                    String action = intent.getAction();
                    if (((action.hashCode() == 1249595450 && action.equals(xpPackageManagerService.ACTION_REFRESH_APP_CONFIG)) ? (char) 0 : (char) 65535) == 0) {
                        xpPackageManagerService.this.mOverlayPackages.clear();
                        xpPackageManagerService.this.onStart();
                    }
                }
            };
            this.mContext.registerReceiver(this.mRefreshReceiver, new IntentFilter(ACTION_REFRESH_APP_CONFIG));
        }
    }

    private void initPackageReceiver() {
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
        packageFilter.addDataScheme("package");
        this.mContext.registerReceiver(this.mPackageReceiver, packageFilter, null, this.mHandler);
    }

    public Bitmap getXpAppIcon(String packageName) {
        return getXpAppIconInner(packageName);
    }

    private Bitmap getXpAppIconInner(String packageName) {
        Bitmap nightIcon;
        if (isNight()) {
            synchronized (this.mOverlayNightIcons) {
                if (this.mOverlayNightIcons.containsKey(packageName) && (nightIcon = this.mOverlayNightIcons.get(packageName)) != null) {
                    return nightIcon;
                }
            }
        }
        synchronized (this.mOverlayIcons) {
            if (this.mOverlayIcons.containsKey(packageName)) {
                return this.mOverlayIcons.get(packageName);
            }
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadOverlayIcons() {
        String packageName;
        Bitmap bitmap;
        try {
            File dir = new File(XP_ICONS_DIR);
            if (dir.isDirectory()) {
                String[] list = dir.list();
                Log.d(TAG, "loadOverlayIcons list=" + Arrays.toString(list));
                if (list != null && list.length > 0) {
                    for (String item : list) {
                        File itemFile = new File(XP_ICONS_DIR + item);
                        if (!itemFile.isDirectory() && (bitmap = getXpIconFromFile(XP_ICONS_DIR, (packageName = getOverlayIconPackageName(item)))) != null) {
                            updateOverlayIcons(packageName, bitmap);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "loadOverlayIcons e=" + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void loadOverlayNightIcons() {
        try {
            File dir = new File(XP_ICONS_NIGHT_DIR);
            if (dir.isDirectory()) {
                String[] list = dir.list();
                Log.d(TAG, "loadOverlayNightIcons list=" + Arrays.toString(list));
                if (list != null && list.length > 0) {
                    for (String item : list) {
                        String packageName = getOverlayIconPackageName(item);
                        Bitmap bitmap = getXpIconFromFile(XP_ICONS_NIGHT_DIR, packageName);
                        if (bitmap != null) {
                            updateOverlayNightIcons(packageName, bitmap);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "loadOverlayNightIcons e=" + e);
        }
    }

    private Bitmap getXpIconFromFile(String path, String packageName) {
        if (!TextUtils.isEmpty(packageName)) {
            try {
                File file = new File(path + packageName + XP_ICON_SUFFIX);
                FileInputStream fis = null;
                if (file.exists()) {
                    try {
                        fis = new FileInputStream(file);
                        Bitmap bitmap = BitmapFactory.decodeStream(fis);
                        fis.close();
                        return bitmap;
                    } catch (Exception e) {
                        Log.d(TAG, "loadXpIconFromFile failed e=" + e);
                        fis.close();
                        return null;
                    }
                }
                return null;
            } catch (Exception e2) {
                Log.d(TAG, "loadPackageInfo e=" + e2);
                return null;
            }
        }
        return null;
    }

    private void updateOverlayIcons(String packageName, Bitmap bitmap) {
        synchronized (this.mOverlayIcons) {
            Log.d(TAG, "updateOverlayIcons packageName=" + packageName);
            this.mOverlayIcons.put(packageName, bitmap);
        }
    }

    private void updateOverlayNightIcons(String packageName, Bitmap bitmap) {
        synchronized (this.mOverlayNightIcons) {
            Log.d(TAG, "updateOverlayNightIcons packageName=" + packageName);
            this.mOverlayNightIcons.put(packageName, bitmap);
        }
    }

    private boolean isNight() {
        Configuration configuration = this.mContext.getResources().getConfiguration();
        if (configuration == null) {
            return false;
        }
        boolean isNight = (configuration.uiMode & 48) == 32;
        return isNight;
    }

    public static void overridePackageSettings(PackageManagerService service, PackageParser.Package pkg) {
        String packageName;
        xpPackageInfo pi;
        if (pkg != null && service != null) {
            boolean system = pkg.isSystem() || pkg.isUpdatedSystemApp();
            if (!system && (pi = service.getXpPackageInfo((packageName = pkg.packageName))) != null) {
                int screenOrientation = -2;
                int i = pi.orientation;
                if (i != 0) {
                    if (i == 1) {
                        screenOrientation = 1;
                    } else if (i == 2) {
                        screenOrientation = 0;
                    }
                }
                if (DEBUG) {
                    xpLogger.i(TAG, "overridePackageSettings packageName=" + packageName + " screenOrientation=" + screenOrientation + " orientation=" + pi.orientation + " pi=" + pi.toString());
                }
                if (screenOrientation != -2) {
                    try {
                        if (pkg.activities.size() > 0) {
                            Iterator it = pkg.activities.iterator();
                            while (it.hasNext()) {
                                PackageParser.Activity activity = (PackageParser.Activity) it.next();
                                if (activity != null && activity.info != null) {
                                    activity.info.screenOrientation = screenOrientation;
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class WorkHandler extends Handler {
        public WorkHandler(Looper looper) {
            super(looper, null);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    }
}
