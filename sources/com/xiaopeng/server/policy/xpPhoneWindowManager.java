package com.xiaopeng.server.policy;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityInfo;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.server.wm.WindowFrameController;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.WindowFrameModel;
import com.xiaopeng.view.xpWindowManager;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* loaded from: classes2.dex */
public class xpPhoneWindowManager {
    public static final boolean CFG_IMMERSIVE_POLICY_CONTROL_SUPPORT = false;
    public static final boolean CFG_OVERRIDE_BOOT_DIALOG_PROGRESS = true;
    public static final boolean CFG_XUI_SYSTEM_UI_SIZE_ENABLED = true;
    private static final boolean DEBUG = false;
    private static final String TAG = "xpPhoneWindowManager";
    private static final int TYPE_RECT_RESIZE_MAX = 2;
    private static final int TYPE_RECT_RESIZE_MIN = 1;
    private static final int TYPE_RECT_RESIZE_NONE = 0;
    private static boolean sLunchActivityFullscreen = false;
    private static ComponentName sLunchComponentName = null;
    private static xpPhoneWindowManager sManager = null;
    private final Context mContext;
    private final WorkHandler mHandler;
    private int mStatusBarHeight = 0;
    private int mNavigationBarWidth = 0;
    private int mNavigationBarHeight = 0;
    private int mNavigationBarType = 0;
    private final IWindowManager mWindowManager = getWindowManager();
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);

    private xpPhoneWindowManager(Context context) {
        this.mContext = context;
        this.mHandlerThread.start();
        this.mHandler = new WorkHandler(this.mHandlerThread.getLooper());
    }

    public static xpPhoneWindowManager get(Context context) {
        if (sManager == null) {
            synchronized (xpPhoneWindowManager.class) {
                if (sManager == null) {
                    sManager = new xpPhoneWindowManager(context);
                }
            }
        }
        return sManager;
    }

    public void init() {
        initWindowSize();
    }

    private void initWindowSize() {
        try {
            this.mNavigationBarType = this.mWindowManager.getXuiStyle();
            Rect statusBar = this.mWindowManager.getXuiRectByType(2000);
            Rect navigationBar = this.mWindowManager.getXuiRectByType(2019);
            if (statusBar != null) {
                this.mStatusBarHeight = statusBar.height();
            }
            if (navigationBar != null) {
                this.mNavigationBarWidth = navigationBar.width();
                this.mNavigationBarHeight = navigationBar.height();
            }
        } catch (Exception e) {
        }
    }

    public int getStatusBarHeight() {
        return this.mStatusBarHeight;
    }

    public int getNavigationBarWidth() {
        return this.mNavigationBarWidth;
    }

    public int getNavigationBarHeight() {
        return this.mNavigationBarHeight;
    }

    public int navigationBarPosition() {
        int type = this.mNavigationBarType;
        if (type != 2) {
            return 4;
        }
        return 1;
    }

    public int getDisplayHeight(int fullWidth, int fullHeight, int navigationBarHeight, boolean navigationBarCanMove) {
        Rect rect;
        try {
            int type = this.mNavigationBarType;
            if (type == 2) {
                if ((!navigationBarCanMove || fullWidth < fullHeight) && (rect = this.mWindowManager.getXuiRectByType(2046)) != null) {
                    int height = fullHeight - (rect.bottom - rect.top);
                    return height;
                }
                return fullHeight;
            } else if (navigationBarCanMove && fullWidth >= fullHeight) {
                return fullHeight;
            } else {
                int height2 = fullHeight - navigationBarHeight;
                return height2;
            }
        } catch (Exception e) {
            return fullWidth;
        }
    }

    public void setActivityLunchInfo(ComponentName component, Intent intent) {
        sLunchComponentName = component;
        if (component != null) {
            int flags = ActivityInfoManager.getActivityFlags(xpActivityInfo.create(intent, component.getPackageName(), component.getClassName()));
            sLunchActivityFullscreen = (flags & 64) == 64;
            return;
        }
        sLunchActivityFullscreen = false;
    }

    public static boolean isSystemUiVisibilityChanged(WindowManager.LayoutParams lp, WindowManagerPolicy.WindowState statusBar, WindowManagerPolicy.WindowState navigationBar) {
        if (statusBar == null || navigationBar == null || lp == null) {
            return true;
        }
        boolean statusBarVisible = statusBar.isVisibleLw();
        boolean navigationBarVisible = navigationBar.isVisibleLw();
        boolean requestStatusBarVisible = !xpWindowManager.hasFullscreenFlag(lp.systemUiVisibility, lp.flags);
        boolean requestNavigationBarVisible = !xpWindowManager.hasHideNavigationFlag(lp.systemUiVisibility, lp.flags);
        if (statusBarVisible != requestStatusBarVisible || navigationBarVisible != requestNavigationBarVisible) {
            return true;
        }
        return false;
    }

    /* JADX WARN: Removed duplicated region for block: B:109:0x027e A[Catch: Exception -> 0x0306, TryCatch #1 {Exception -> 0x0306, blocks: (B:5:0x0008, B:9:0x001c, B:11:0x004f, B:42:0x008c, B:43:0x008f, B:44:0x0092, B:46:0x009a, B:109:0x027e, B:111:0x029d, B:113:0x02a7, B:115:0x02ae, B:137:0x02f2, B:139:0x02f9, B:128:0x02cf, B:130:0x02d3, B:131:0x02d9, B:133:0x02e5, B:135:0x02ec, B:140:0x02fe, B:50:0x00d3, B:51:0x00fe, B:59:0x0144, B:60:0x0151, B:57:0x013f, B:62:0x0161, B:64:0x016c, B:66:0x0176, B:68:0x017a, B:71:0x0181, B:70:0x017e, B:72:0x0189, B:74:0x0191, B:75:0x01a1, B:77:0x01a5, B:78:0x01ad, B:80:0x01b5, B:81:0x01c5, B:83:0x01c9, B:85:0x01d4, B:87:0x01e2, B:91:0x01f4, B:93:0x0202, B:107:0x0276, B:96:0x0212, B:98:0x0225, B:100:0x0229, B:102:0x022d, B:12:0x0052, B:53:0x0136), top: B:148:0x0008, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:111:0x029d A[Catch: Exception -> 0x0306, TryCatch #1 {Exception -> 0x0306, blocks: (B:5:0x0008, B:9:0x001c, B:11:0x004f, B:42:0x008c, B:43:0x008f, B:44:0x0092, B:46:0x009a, B:109:0x027e, B:111:0x029d, B:113:0x02a7, B:115:0x02ae, B:137:0x02f2, B:139:0x02f9, B:128:0x02cf, B:130:0x02d3, B:131:0x02d9, B:133:0x02e5, B:135:0x02ec, B:140:0x02fe, B:50:0x00d3, B:51:0x00fe, B:59:0x0144, B:60:0x0151, B:57:0x013f, B:62:0x0161, B:64:0x016c, B:66:0x0176, B:68:0x017a, B:71:0x0181, B:70:0x017e, B:72:0x0189, B:74:0x0191, B:75:0x01a1, B:77:0x01a5, B:78:0x01ad, B:80:0x01b5, B:81:0x01c5, B:83:0x01c9, B:85:0x01d4, B:87:0x01e2, B:91:0x01f4, B:93:0x0202, B:107:0x0276, B:96:0x0212, B:98:0x0225, B:100:0x0229, B:102:0x022d, B:12:0x0052, B:53:0x0136), top: B:148:0x0008, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:115:0x02ae A[Catch: Exception -> 0x0306, TryCatch #1 {Exception -> 0x0306, blocks: (B:5:0x0008, B:9:0x001c, B:11:0x004f, B:42:0x008c, B:43:0x008f, B:44:0x0092, B:46:0x009a, B:109:0x027e, B:111:0x029d, B:113:0x02a7, B:115:0x02ae, B:137:0x02f2, B:139:0x02f9, B:128:0x02cf, B:130:0x02d3, B:131:0x02d9, B:133:0x02e5, B:135:0x02ec, B:140:0x02fe, B:50:0x00d3, B:51:0x00fe, B:59:0x0144, B:60:0x0151, B:57:0x013f, B:62:0x0161, B:64:0x016c, B:66:0x0176, B:68:0x017a, B:71:0x0181, B:70:0x017e, B:72:0x0189, B:74:0x0191, B:75:0x01a1, B:77:0x01a5, B:78:0x01ad, B:80:0x01b5, B:81:0x01c5, B:83:0x01c9, B:85:0x01d4, B:87:0x01e2, B:91:0x01f4, B:93:0x0202, B:107:0x0276, B:96:0x0212, B:98:0x0225, B:100:0x0229, B:102:0x022d, B:12:0x0052, B:53:0x0136), top: B:148:0x0008, inners: #0 }] */
    /* JADX WARN: Removed duplicated region for block: B:139:0x02f9 A[Catch: Exception -> 0x0306, TryCatch #1 {Exception -> 0x0306, blocks: (B:5:0x0008, B:9:0x001c, B:11:0x004f, B:42:0x008c, B:43:0x008f, B:44:0x0092, B:46:0x009a, B:109:0x027e, B:111:0x029d, B:113:0x02a7, B:115:0x02ae, B:137:0x02f2, B:139:0x02f9, B:128:0x02cf, B:130:0x02d3, B:131:0x02d9, B:133:0x02e5, B:135:0x02ec, B:140:0x02fe, B:50:0x00d3, B:51:0x00fe, B:59:0x0144, B:60:0x0151, B:57:0x013f, B:62:0x0161, B:64:0x016c, B:66:0x0176, B:68:0x017a, B:71:0x0181, B:70:0x017e, B:72:0x0189, B:74:0x0191, B:75:0x01a1, B:77:0x01a5, B:78:0x01ad, B:80:0x01b5, B:81:0x01c5, B:83:0x01c9, B:85:0x01d4, B:87:0x01e2, B:91:0x01f4, B:93:0x0202, B:107:0x0276, B:96:0x0212, B:98:0x0225, B:100:0x0229, B:102:0x022d, B:12:0x0052, B:53:0x0136), top: B:148:0x0008, inners: #0 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static com.xiaopeng.server.policy.xpPhoneWindowManager.WindowParams layoutWindowLw(com.xiaopeng.server.policy.xpPhoneWindowManager.WindowParams r20, com.android.server.wm.DisplayFrames r21) {
        /*
            Method dump skipped, instructions count: 828
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.policy.xpPhoneWindowManager.layoutWindowLw(com.xiaopeng.server.policy.xpPhoneWindowManager$WindowParams, com.android.server.wm.DisplayFrames):com.xiaopeng.server.policy.xpPhoneWindowManager$WindowParams");
    }

    private static WindowParams setWindowParamsRect(WindowParams params, Rect targetRect, int rectFlags, int resizeType) {
        if (params != null && targetRect != null) {
            boolean hasOsf = true;
            boolean hasPf = hasFlag(rectFlags, 1) || hasFlag(rectFlags, 2);
            boolean hasDf = hasFlag(rectFlags, 1) || hasFlag(rectFlags, 4);
            boolean hasOf = hasFlag(rectFlags, 1) || hasFlag(rectFlags, 8);
            boolean hasCf = hasFlag(rectFlags, 1) || hasFlag(rectFlags, 16);
            boolean hasVf = hasFlag(rectFlags, 1) || hasFlag(rectFlags, 32);
            boolean hasSf = hasFlag(rectFlags, 1) || hasFlag(rectFlags, 64);
            boolean hasDcf = hasFlag(rectFlags, 1) || hasFlag(rectFlags, 128);
            if (!hasFlag(rectFlags, 1) && !hasFlag(rectFlags, 256)) {
                hasOsf = false;
            }
            if (hasPf && params.pf != null) {
                params.pf.set(resizeRect(resizeType, targetRect, params.pf));
            }
            if (hasDf && params.df != null) {
                params.df.set(resizeRect(resizeType, targetRect, params.df));
            }
            if (hasOf && params.of != null) {
                params.of.set(resizeRect(resizeType, targetRect, params.of));
            }
            if (hasCf && params.cf != null) {
                params.cf.set(resizeRect(resizeType, targetRect, params.cf));
            }
            if (hasVf && params.vf != null) {
                params.vf.set(resizeRect(resizeType, targetRect, params.vf));
            }
            if (hasSf && params.sf != null) {
                params.sf.set(resizeRect(resizeType, targetRect, params.sf));
            }
            if (hasDcf && params.dcf != null) {
                params.dcf.set(resizeRect(resizeType, targetRect, params.dcf));
            }
            if (hasOsf && params.osf != null) {
                params.osf.set(resizeRect(resizeType, targetRect, params.osf));
            }
        }
        return params;
    }

    private static WindowParams setWindowParamsBottom(WindowParams params, Rect targetRect) {
        if (params != null && targetRect != null) {
            if (params.pf != null && params.pf.bottom >= targetRect.bottom) {
                params.pf.bottom = targetRect.bottom;
            }
            if (params.df != null && params.df.bottom >= targetRect.bottom) {
                params.df.bottom = targetRect.bottom;
            }
            if (params.of != null && params.of.bottom >= targetRect.bottom) {
                params.of.bottom = targetRect.bottom;
            }
            if (params.cf != null && params.cf.bottom >= targetRect.bottom) {
                params.cf.bottom = targetRect.bottom;
            }
            if (params.vf != null && params.vf.bottom >= targetRect.bottom) {
                params.vf.bottom = targetRect.bottom;
            }
            if (params.sf != null && params.sf.bottom >= targetRect.bottom) {
                params.sf.bottom = targetRect.bottom;
            }
            if (params.dcf != null && params.dcf.bottom >= targetRect.bottom) {
                params.dcf.bottom = targetRect.bottom;
            }
            if (params.osf != null && params.osf.bottom >= targetRect.bottom) {
                params.osf.bottom = targetRect.bottom;
            }
        }
        return params;
    }

    public static boolean isStartingWindowFullscreen(WindowManager.LayoutParams lp) {
        if (lp == null) {
            return false;
        }
        try {
            String flagValue = "";
            String token = lp.token != null ? lp.token.toString() : "";
            Pattern pattern = Pattern.compile("privateFlags=(.*?) ");
            Matcher matcher = pattern.matcher(token);
            while (matcher.find()) {
                flagValue = matcher.group(1);
            }
            int privateFlags = xpTextUtils.toInteger(flagValue, 0).intValue();
            boolean fullscreen = (privateFlags & 64) == 64;
            return fullscreen;
        } catch (Exception e) {
            return false;
        }
    }

    public static WindowManager.LayoutParams getStartingWindowLayoutParams(WindowManager.LayoutParams lp, WindowManager.LayoutParams alp) {
        IWindowManager wm = getWindowManager();
        if (wm != null && lp != null && lp.type == 3) {
            try {
                Rect rect = wm.getXuiRectByType(1);
                boolean fullscreen = isStartingWindowFullscreen(lp);
                StringBuffer buffer = new StringBuffer();
                buffer.append("getStartingWindowLayoutParams");
                buffer.append(" lp=" + lp);
                buffer.append(" alp=" + alp);
                if (alp != null) {
                    rect = wm.getXuiRectByType(alp.type);
                } else if ("com.xiaopeng.montecarlo".equals(lp.packageName)) {
                    rect = wm.getXuiRectByType(5);
                }
                if (fullscreen) {
                    WindowFrameModel model = WindowFrameController.getWindowFrame(lp);
                    rect = model != null ? model.unrestrictedBounds : xpWindowManager.getDisplayBounds();
                    lp.flags = xpWindowManager.getWindowFlags(lp.flags, true, true, true, true, false);
                    lp.systemUiVisibility = xpWindowManager.getSystemUiVisibility(lp.systemUiVisibility, true, true, true, true, false);
                }
                buffer.append(" packageName=" + lp.packageName);
                buffer.append(" rect=" + rect);
                if (rect != null) {
                    lp.x = rect.left;
                    lp.y = rect.top;
                    lp.width = rect.width();
                    lp.height = rect.height();
                }
                lp.gravity = 51;
                buffer.append(" nlp=" + lp);
            } catch (Exception e) {
            }
        }
        return lp;
    }

    /*  JADX ERROR: JadxRuntimeException in pass: RegionMakerVisitor
        jadx.core.utils.exceptions.JadxRuntimeException: Failed to find switch 'out' block
        	at jadx.core.dex.visitors.regions.RegionMaker.processSwitch(RegionMaker.java:817)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:160)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processSwitch(RegionMaker.java:856)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:160)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processSwitch(RegionMaker.java:856)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:160)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processSwitch(RegionMaker.java:856)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:160)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:730)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:730)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:730)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:735)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:730)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:730)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:735)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:730)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMakerVisitor.visit(RegionMakerVisitor.java:52)
        */
    public static int getWindowLayerFromTypeLw(int r10, android.view.IWindowManager r11) {
        /*
            Method dump skipped, instructions count: 282
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.policy.xpPhoneWindowManager.getWindowLayerFromTypeLw(int, android.view.IWindowManager):int");
    }

    public static int getParentLayerFromTypeLw(int type) {
        if (type == 1 || type == 2 || type == 5 || type == 6) {
            return 2;
        }
        if (type == 2039 || type == 2040) {
            return 1;
        }
        switch (type) {
            case 2011:
            case 2012:
                return -1;
            case 2013:
                return 0;
            default:
                return 3;
        }
    }

    public static boolean isHighLevelActivity(Context context, WindowManagerPolicy.WindowState focusWindow) {
        ActivityManager.RunningTaskInfo info;
        boolean highLevel = false;
        if (focusWindow != null) {
            WindowManager.LayoutParams params = focusWindow.getAttrs();
            PackageManager pm = context.getPackageManager();
            String packageName = focusWindow.getOwningPackage();
            if (params != null && pm != null && !TextUtils.isEmpty(packageName)) {
                try {
                    int winType = params.type;
                    PackageInfo pkgInfo = pm.getPackageInfo(packageName, 4096);
                    if (pkgInfo != null && winType == 6) {
                        boolean highActivity = false;
                        boolean hasPermission = false;
                        String[] permissions = pkgInfo.requestedPermissions;
                        if (permissions != null) {
                            int length = permissions.length;
                            int i = 0;
                            while (true) {
                                if (i >= length) {
                                    break;
                                }
                                String perms = permissions[i];
                                if (!"android.permission.SUPER_APPLICATION_RUNNING".equals(perms)) {
                                    i++;
                                } else {
                                    hasPermission = true;
                                    break;
                                }
                            }
                        }
                        if (hasPermission) {
                            try {
                                ActivityManager am = (ActivityManager) context.getSystemService("activity");
                                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                                if (tasks != null && tasks.size() == 1 && (info = tasks.get(0)) != null) {
                                    highActivity = xpActivityManagerService.isHighLevelActivity(info.topActivity);
                                }
                            } catch (Exception e) {
                            }
                        }
                        if (highActivity && hasPermission) {
                            highLevel = true;
                        }
                    }
                    Log.w(TAG, "isHighLevelActivity packageName=" + packageName + " winType=" + winType + " highLevel=" + highLevel);
                } catch (Exception e2) {
                }
            }
        }
        return highLevel;
    }

    public static IWindowManager getWindowManager() {
        IBinder b = ServiceManager.getService("window");
        return IWindowManager.Stub.asInterface(b);
    }

    public static void mergeRect(Rect rect, Rect newRect) {
        if (rect != null && newRect != null) {
            rect.set(newRect);
        }
    }

    private static Rect resizeRect(int type, Rect targetRect, Rect compareRect) {
        if (targetRect != null) {
            int top = targetRect.top;
            int left = targetRect.left;
            int bottom = targetRect.bottom;
            int right = targetRect.right;
            if (type != 0) {
                if (type == 1) {
                    if (compareRect != null) {
                        left = targetRect.left >= compareRect.left ? targetRect.left : compareRect.left;
                        top = targetRect.top >= compareRect.top ? targetRect.top : compareRect.top;
                        right = targetRect.right < compareRect.right ? targetRect.right : compareRect.right;
                        bottom = targetRect.bottom < compareRect.bottom ? targetRect.bottom : compareRect.bottom;
                    }
                } else if (type == 2 && compareRect != null) {
                    left = targetRect.left >= compareRect.left ? compareRect.left : targetRect.left;
                    top = targetRect.top >= compareRect.top ? compareRect.top : targetRect.top;
                    right = targetRect.right < compareRect.right ? compareRect.right : targetRect.right;
                    bottom = targetRect.bottom < compareRect.bottom ? compareRect.bottom : targetRect.bottom;
                }
            }
            return new Rect(left, top, right, bottom);
        }
        return null;
    }

    public static boolean hasFlag(int flags, int targetFlag) {
        return (flags & targetFlag) == targetFlag;
    }

    /* loaded from: classes2.dex */
    public static final class WindowParams {
        public static final int FLAG_AF = 1;
        public static final int FLAG_CF = 16;
        public static final int FLAG_DCF = 128;
        public static final int FLAG_DF = 4;
        public static final int FLAG_OF = 8;
        public static final int FLAG_OSF = 256;
        public static final int FLAG_PF = 2;
        public static final int FLAG_SF = 64;
        public static final int FLAG_VF = 32;
        final WindowManagerPolicy.WindowState attached;
        final WindowManager.LayoutParams attrs;
        public Rect cf;
        public Rect dcf;
        public Rect df;
        final int fl;
        public Rect of;
        public Rect osf;
        public Rect pf;
        final int pfl;
        public Rect sf;
        final int sysUiFl;
        final int type;
        public Rect vf;
        final WindowManagerPolicy.WindowState win;

        public WindowParams(WindowManagerPolicy.WindowState win, WindowManagerPolicy.WindowState attached, WindowManager.LayoutParams attrs, int type, int fl, int pfl, int sysUiFl) {
            this.win = win;
            this.attached = attached;
            this.attrs = attrs;
            this.type = type;
            this.fl = fl;
            this.pfl = pfl;
            this.sysUiFl = sysUiFl;
        }

        public void setRect(Rect pf, Rect df, Rect of, Rect cf, Rect vf, Rect dcf, Rect sf, Rect osf) {
            this.pf = pf;
            this.df = df;
            this.of = of;
            this.cf = cf;
            this.vf = vf;
            this.dcf = dcf;
            this.sf = sf;
            this.osf = osf;
        }
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
        }
    }
}
