package com.xiaopeng.server.wm;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.net.util.NetworkConstants;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.xpWindowManager;
import java.io.File;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class xpWindowManagerService {
    private static int sImmPosition;
    private static int sWindowStyle;
    private final Context mContext;
    private final WorkHandler mHandler;
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);
    private static final String TAG = "xpWindowManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final HashMap<Integer, WindowConfigInfo> sWindowConfigs = new HashMap<>();
    private static xpWindowManagerService sService = null;

    /* loaded from: classes.dex */
    public static final class WindowConfigJson {
        public static final String KEY_CONFIG = "config";
        public static final String KEY_DISPLAY_LAYER = "displayLayer";
        public static final String KEY_IMM_POSITION = "immposition";
        public static final String KEY_LAYER = "layer";
        public static final String KEY_LAYER_NAME = "LayerName";
        public static final String KEY_RECT = "rect";
        public static final String KEY_ROUND_CORNER = "roundCorner";
        public static final String KEY_SUB_LAYER = "mLayer";
        public static final String KEY_TYPE = "type";
        public static final String KEY_VERSION = "Version";
        public static final String KEY_XUI_STYLE = "XuiStyle";
    }

    private xpWindowManagerService(Context context) {
        this.mContext = context;
        this.mHandlerThread.start();
        this.mHandler = new WorkHandler(this.mHandlerThread.getLooper());
    }

    public static xpWindowManagerService get(Context context) {
        if (sService == null) {
            synchronized (xpWindowManagerService.class) {
                if (sService == null) {
                    sService = new xpWindowManagerService(context);
                }
            }
        }
        return sService;
    }

    public void init() {
        int oldMask = StrictMode.allowThreadDiskReadsMask();
        initWindowConfigList();
        StrictMode.setThreadPolicyMask(oldMask);
    }

    public Configuration getXuiConfiguration(String packageName, Configuration config) {
        xpPackageInfo xpi = xpPackageManagerService.get(this.mContext).getXpPackageInfo(packageName);
        if (xpi != null) {
            return xpPackageManagerService.get(this.mContext).getOverlayConfiguration(config, xpi, getXuiRectByType(2));
        }
        return null;
    }

    public WindowManager.LayoutParams getXuiLayoutParams(WindowManager.LayoutParams attrs) {
        WindowConfigInfo info;
        if (attrs != null && (info = sWindowConfigs.get(Integer.valueOf(attrs.type))) != null && info.rect != null && info.exist != 0) {
            WindowManager.LayoutParams nlp = new WindowManager.LayoutParams();
            nlp.copyFrom(attrs);
            if (info.x != 65535) {
                nlp.x = info.x;
            }
            if (info.y != 65535) {
                nlp.y = info.y;
            }
            if (info.w != 65535) {
                nlp.width = info.w;
            }
            if (info.h != 65535) {
                nlp.height = info.h;
            }
            if (info.gravity != 65535) {
                nlp.gravity = info.gravity;
            }
            return nlp;
        }
        return null;
    }

    public boolean isImeLayerExist() {
        if (getXuiLayer(2011) != 65535) {
            return true;
        }
        return false;
    }

    public Rect getXuiRectByType(int type) {
        WindowConfigInfo info = sWindowConfigs.get(Integer.valueOf(type));
        if (info != null && info.rect != null) {
            return new Rect(info.rect);
        }
        return null;
    }

    public Rect getRealDisplayRect() {
        return xpWindowManager.getRealDisplayRect(this.mContext);
    }

    public int getXuiStyle() {
        return sWindowStyle;
    }

    public int getImmPosition() {
        return sImmPosition;
    }

    public int getXuiLayer(int type) {
        WindowConfigInfo info = sWindowConfigs.get(Integer.valueOf(type));
        if (info != null) {
            return info.layer;
        }
        return NetworkConstants.ARP_HWTYPE_RESERVED_HI;
    }

    public int getXuiSubLayer(int type) {
        WindowConfigInfo info = sWindowConfigs.get(Integer.valueOf(type));
        if (info != null) {
            return info.subLayer;
        }
        return NetworkConstants.ARP_HWTYPE_RESERVED_HI;
    }

    public int[] getXuiRoundCorner(int type) {
        WindowConfigInfo info;
        if (sWindowConfigs != null && (info = sWindowConfigs.get(Integer.valueOf(type))) != null && info.roundCorner != null) {
            int[] data = {info.roundCorner.flag, info.roundCorner.type, info.roundCorner.radius};
            return data;
        }
        return null;
    }

    public void setFocusedAppNoChecked(IBinder token, boolean moveFocusNow) {
    }

    public int getAppTaskId(IBinder token) {
        return 0;
    }

    public void onWindowAdded(WindowManager.LayoutParams attrs) {
        if (attrs != null) {
            int type = attrs.type;
            String packageName = attrs.packageName;
            xpLogger.i(TAG, "onWindowAdded type=" + type + " packageName=" + packageName);
            if (type == 5) {
                try {
                    ComponentName component = ComponentName.createRelative(packageName, "null");
                    xpActivityManagerService.get(this.mContext).setHomeState(component, 1);
                } catch (Exception e) {
                }
            }
        }
    }

    public void onWindowRemoved(WindowManager.LayoutParams attrs) {
        if (attrs != null) {
            int type = attrs.type;
            String packageName = attrs.packageName;
            xpLogger.i(TAG, "onWindowRemoved type=" + type + " packageName=" + packageName);
            if (type == 5) {
                try {
                    ComponentName component = ComponentName.createRelative(packageName, "null");
                    xpActivityManagerService.get(this.mContext).setHomeState(component, 2);
                } catch (Exception e) {
                }
            }
        }
    }

    public static Bitmap screenshot(int screenId, Bundle extras) {
        try {
            Rect sourceCrop = new Rect();
            Rect displayBounds = new Rect();
            DisplayMetrics metrics = new DisplayMetrics();
            Display display = DisplayManagerGlobal.getInstance().getRealDisplay(0);
            int rotation = display.getRotation();
            display.getRealMetrics(metrics);
            displayBounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
            sourceCrop.set(displayBounds);
            int width = sourceCrop.width();
            int height = sourceCrop.height();
            return SurfaceControl.screenshot(sourceCrop, width, height, rotation);
        } catch (Exception e) {
            return null;
        }
    }

    public void initWindowConfigList() {
        File dataFile = new File("/data/xuiservice/xuiwindowconfig.json");
        File systemFile = new File("/system/etc/xuiwindowconfig.json");
        String content = xpTextUtils.getValueByVersion(dataFile, systemFile, WindowConfigJson.KEY_VERSION);
        if (TextUtils.isEmpty(content)) {
            return;
        }
        try {
            JSONObject jObject = new JSONObject(content);
            sWindowStyle = jObject.getInt(WindowConfigJson.KEY_XUI_STYLE);
            sImmPosition = jObject.getInt(WindowConfigJson.KEY_IMM_POSITION);
            JSONArray jArray = jObject.getJSONArray(WindowConfigJson.KEY_CONFIG);
            if (jArray == null) {
                return;
            }
            int length = jArray.length();
            int i = 0;
            while (true) {
                int i2 = i;
                if (i2 >= length) {
                    return;
                }
                WindowConfigInfo info = new WindowConfigInfo();
                JSONObject object = jArray.getJSONObject(i2);
                if (object.has("type")) {
                    info.type = object.getInt("type");
                }
                if (object.has(WindowConfigJson.KEY_SUB_LAYER)) {
                    info.subLayer = object.getInt(WindowConfigJson.KEY_SUB_LAYER);
                }
                if (object.has(WindowConfigJson.KEY_RECT)) {
                    JSONObject json = object.getJSONObject(WindowConfigJson.KEY_RECT);
                    try {
                        info.exist = json.getInt("exist");
                        info.gravity = json.getInt("gravity");
                        info.x = json.getInt("x");
                        info.y = json.getInt("y");
                        info.w = json.getInt("w");
                        info.h = json.getInt("h");
                        info.rect = new Rect(0, 0, 0, 0);
                        info.rect.left = info.x;
                        info.rect.top = info.y;
                        info.rect.right = info.x + info.w;
                        info.rect.bottom = info.y + info.h;
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
                if (object.has(WindowConfigJson.KEY_DISPLAY_LAYER)) {
                    JSONObject json2 = object.getJSONObject(WindowConfigJson.KEY_DISPLAY_LAYER);
                    try {
                        info.layerName = json2.getString(WindowConfigJson.KEY_LAYER_NAME);
                        info.layer = json2.getInt(WindowConfigJson.KEY_LAYER);
                    } catch (JSONException e2) {
                        e2.printStackTrace();
                    }
                }
                if (object.has(WindowConfigJson.KEY_ROUND_CORNER)) {
                    JSONObject json3 = object.getJSONObject(WindowConfigJson.KEY_ROUND_CORNER);
                    try {
                        info.roundCorner = new WindowRoundCorner();
                        info.roundCorner.flag = json3.getInt("flag");
                        info.roundCorner.type = json3.getInt("type");
                        info.roundCorner.radius = json3.getInt("radius");
                    } catch (JSONException e3) {
                        e3.printStackTrace();
                    }
                }
                xpLogger.i(TAG, "initWindowConfigList info=" + info.toString());
                if (info.type > 0) {
                    sWindowConfigs.put(Integer.valueOf(info.type), info);
                }
                i = i2 + 1;
            }
        } catch (Exception e4) {
        }
    }

    /* loaded from: classes.dex */
    public static final class WindowConfigInfo {
        public int exist;
        public int gravity;
        public int h;
        public int layer;
        public String layerName;
        public Rect rect;
        public WindowRoundCorner roundCorner;
        public int subLayer;
        public int type;
        public int w;
        public int x;
        public int y;

        public String toString() {
            return "WindowConfigInfo { type=" + this.type + " layerName=" + this.layerName + " layer=" + this.layer + " subLayer=" + this.subLayer + " rect=" + this.rect + " x=" + this.x + " y=" + this.y + " w=" + this.w + " h=" + this.h + " exist=" + this.exist + " }";
        }
    }

    /* loaded from: classes.dex */
    public static final class WindowRoundCorner {
        public int flag;
        public int radius;
        public int type;

        public String toString() {
            return "WindowRoundCorner { flag=" + this.flag + " type=" + this.type + " radius=" + this.radius + " }";
        }
    }

    /* loaded from: classes.dex */
    private final class WorkHandler extends Handler {
        @SuppressLint({"NewApi"})
        public WorkHandler(Looper looper) {
            super(looper, null);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int i = msg.what;
        }
    }
}
