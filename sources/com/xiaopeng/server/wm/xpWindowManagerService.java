package com.xiaopeng.server.wm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.StrictMode;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.util.HashMap;

/* loaded from: classes2.dex */
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

    /* loaded from: classes2.dex */
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
        initWindowFrameBounds();
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

    public Rect getDisplayBounds() {
        return xpWindowManager.getDisplayBounds(this.mContext);
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
        return 65535;
    }

    public int getXuiSubLayer(int type) {
        WindowConfigInfo info = sWindowConfigs.get(Integer.valueOf(type));
        if (info != null) {
            return info.subLayer;
        }
        return 65535;
    }

    public int[] getXuiRoundCorner(int type) {
        WindowConfigInfo info;
        HashMap<Integer, WindowConfigInfo> hashMap = sWindowConfigs;
        if (hashMap != null && (info = hashMap.get(Integer.valueOf(type))) != null && info.roundCorner != null) {
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

    public void onWindowAdded(WindowManager.LayoutParams lp) {
        if (lp != null) {
            int type = lp.type;
            String packageName = lp.packageName;
            xpLogger.i(TAG, "onWindowAdded type=" + type + " packageName=" + packageName);
            if (ActivityInfoManager.isHomeActivity(lp)) {
                xpActivityManagerService.get(this.mContext).setHomeState(lp, 1);
            }
        }
    }

    public void onWindowRemoved(WindowManager.LayoutParams lp) {
        if (lp != null) {
            int type = lp.type;
            String packageName = lp.packageName;
            xpLogger.i(TAG, "onWindowRemoved type=" + type + " packageName=" + packageName);
            if (ActivityInfoManager.isHomeActivity(lp)) {
                xpActivityManagerService.get(this.mContext).setHomeState(lp, 2);
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
            boolean withSharedDisplay = SharedDisplayManager.enable() && SharedDisplayManager.screenValid(screenId);
            if (withSharedDisplay) {
                if (screenId == 0) {
                    sourceCrop.right = sourceCrop.width() / 2;
                } else if (screenId == 1) {
                    sourceCrop.left = sourceCrop.width() / 2;
                }
            }
            int width = sourceCrop.width();
            int height = sourceCrop.height();
            return SurfaceControl.screenshotEx(0, sourceCrop, width, height, rotation);
        } catch (Exception e) {
            return null;
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:35:0x00f0
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public void initWindowConfigList() {
        /*
            Method dump skipped, instructions count: 399
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.wm.xpWindowManagerService.initWindowConfigList():void");
    }

    /* loaded from: classes2.dex */
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

    /* loaded from: classes2.dex */
    public static final class WindowRoundCorner {
        public int flag;
        public int radius;
        public int type;

        public String toString() {
            return "WindowRoundCorner { flag=" + this.flag + " type=" + this.type + " radius=" + this.radius + " }";
        }
    }

    private void initWindowFrameBounds() {
        HashMap<Integer, Rect> windowBounds = new HashMap<>();
        for (WindowConfigInfo info : sWindowConfigs.values()) {
            if (info != null) {
                windowBounds.put(Integer.valueOf(info.type), new Rect(info.rect));
            }
        }
        WindowFrameController.get().setWindowBounds(windowBounds);
    }

    /* loaded from: classes2.dex */
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
