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
import com.android.server.backup.BackupManagerConstants;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityInfo;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.xpWindowManager;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/* loaded from: classes.dex */
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
        if (lp != null) {
            boolean statusBarVisible = statusBar.isVisibleLw();
            boolean navigationBarVisible = navigationBar.isVisibleLw();
            boolean requestStatusBarVisible = !xpWindowManager.hasFullscreenFlag(lp.systemUiVisibility, lp.flags);
            boolean requestNavigationBarVisible = !xpWindowManager.hasHideNavigationFlag(lp.systemUiVisibility, lp.flags);
            if (statusBarVisible != requestStatusBarVisible || navigationBarVisible != requestNavigationBarVisible) {
                return true;
            }
            return false;
        }
        return false;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Removed duplicated region for block: B:32:0x00a4  */
    /* JADX WARN: Removed duplicated region for block: B:76:0x020b A[Catch: Exception -> 0x028e, TryCatch #0 {Exception -> 0x028e, blocks: (B:5:0x0008, B:9:0x001b, B:11:0x0047, B:23:0x0063, B:24:0x0066, B:25:0x0069, B:26:0x006c, B:27:0x006f, B:28:0x0072, B:30:0x007a, B:78:0x0213, B:80:0x0232, B:82:0x023c, B:84:0x0243, B:88:0x0256, B:96:0x0279, B:98:0x0281, B:90:0x025a, B:92:0x0266, B:93:0x026a, B:95:0x0276, B:99:0x0286, B:70:0x01ef, B:72:0x01fa, B:74:0x0203, B:73:0x0201, B:57:0x015d, B:76:0x020b, B:35:0x00bc, B:37:0x00ca, B:42:0x00dd, B:44:0x00eb, B:46:0x00f4, B:48:0x0107, B:50:0x010b, B:52:0x010f, B:58:0x0182, B:66:0x01cf, B:67:0x01dc, B:64:0x01ca, B:12:0x004a, B:60:0x01c2), top: B:105:0x0008, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:78:0x0213 A[Catch: Exception -> 0x028e, TryCatch #0 {Exception -> 0x028e, blocks: (B:5:0x0008, B:9:0x001b, B:11:0x0047, B:23:0x0063, B:24:0x0066, B:25:0x0069, B:26:0x006c, B:27:0x006f, B:28:0x0072, B:30:0x007a, B:78:0x0213, B:80:0x0232, B:82:0x023c, B:84:0x0243, B:88:0x0256, B:96:0x0279, B:98:0x0281, B:90:0x025a, B:92:0x0266, B:93:0x026a, B:95:0x0276, B:99:0x0286, B:70:0x01ef, B:72:0x01fa, B:74:0x0203, B:73:0x0201, B:57:0x015d, B:76:0x020b, B:35:0x00bc, B:37:0x00ca, B:42:0x00dd, B:44:0x00eb, B:46:0x00f4, B:48:0x0107, B:50:0x010b, B:52:0x010f, B:58:0x0182, B:66:0x01cf, B:67:0x01dc, B:64:0x01ca, B:12:0x004a, B:60:0x01c2), top: B:105:0x0008, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:80:0x0232 A[Catch: Exception -> 0x028e, TryCatch #0 {Exception -> 0x028e, blocks: (B:5:0x0008, B:9:0x001b, B:11:0x0047, B:23:0x0063, B:24:0x0066, B:25:0x0069, B:26:0x006c, B:27:0x006f, B:28:0x0072, B:30:0x007a, B:78:0x0213, B:80:0x0232, B:82:0x023c, B:84:0x0243, B:88:0x0256, B:96:0x0279, B:98:0x0281, B:90:0x025a, B:92:0x0266, B:93:0x026a, B:95:0x0276, B:99:0x0286, B:70:0x01ef, B:72:0x01fa, B:74:0x0203, B:73:0x0201, B:57:0x015d, B:76:0x020b, B:35:0x00bc, B:37:0x00ca, B:42:0x00dd, B:44:0x00eb, B:46:0x00f4, B:48:0x0107, B:50:0x010b, B:52:0x010f, B:58:0x0182, B:66:0x01cf, B:67:0x01dc, B:64:0x01ca, B:12:0x004a, B:60:0x01c2), top: B:105:0x0008, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:84:0x0243 A[Catch: Exception -> 0x028e, TryCatch #0 {Exception -> 0x028e, blocks: (B:5:0x0008, B:9:0x001b, B:11:0x0047, B:23:0x0063, B:24:0x0066, B:25:0x0069, B:26:0x006c, B:27:0x006f, B:28:0x0072, B:30:0x007a, B:78:0x0213, B:80:0x0232, B:82:0x023c, B:84:0x0243, B:88:0x0256, B:96:0x0279, B:98:0x0281, B:90:0x025a, B:92:0x0266, B:93:0x026a, B:95:0x0276, B:99:0x0286, B:70:0x01ef, B:72:0x01fa, B:74:0x0203, B:73:0x0201, B:57:0x015d, B:76:0x020b, B:35:0x00bc, B:37:0x00ca, B:42:0x00dd, B:44:0x00eb, B:46:0x00f4, B:48:0x0107, B:50:0x010b, B:52:0x010f, B:58:0x0182, B:66:0x01cf, B:67:0x01dc, B:64:0x01ca, B:12:0x004a, B:60:0x01c2), top: B:105:0x0008, inners: #1 }] */
    /* JADX WARN: Removed duplicated region for block: B:98:0x0281 A[Catch: Exception -> 0x028e, TryCatch #0 {Exception -> 0x028e, blocks: (B:5:0x0008, B:9:0x001b, B:11:0x0047, B:23:0x0063, B:24:0x0066, B:25:0x0069, B:26:0x006c, B:27:0x006f, B:28:0x0072, B:30:0x007a, B:78:0x0213, B:80:0x0232, B:82:0x023c, B:84:0x0243, B:88:0x0256, B:96:0x0279, B:98:0x0281, B:90:0x025a, B:92:0x0266, B:93:0x026a, B:95:0x0276, B:99:0x0286, B:70:0x01ef, B:72:0x01fa, B:74:0x0203, B:73:0x0201, B:57:0x015d, B:76:0x020b, B:35:0x00bc, B:37:0x00ca, B:42:0x00dd, B:44:0x00eb, B:46:0x00f4, B:48:0x0107, B:50:0x010b, B:52:0x010f, B:58:0x0182, B:66:0x01cf, B:67:0x01dc, B:64:0x01ca, B:12:0x004a, B:60:0x01c2), top: B:105:0x0008, inners: #1 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static com.xiaopeng.server.policy.xpPhoneWindowManager.WindowParams layoutWindowLw(com.xiaopeng.server.policy.xpPhoneWindowManager.WindowParams r20, com.android.server.wm.DisplayFrames r21) {
        /*
            Method dump skipped, instructions count: 742
            To view this dump add '--comments-level debug' option
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
            String token = lp.token != null ? lp.token.toString() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            Pattern pattern = Pattern.compile("privateFlags=(.*?) ");
            Matcher matcher = pattern.matcher(token);
            String flagValue = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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
                    rect = xpWindowManager.getRealFullscreenRect();
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
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:735)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMaker.processIf(RegionMaker.java:730)
        	at jadx.core.dex.visitors.regions.RegionMaker.traverse(RegionMaker.java:155)
        	at jadx.core.dex.visitors.regions.RegionMaker.makeRegion(RegionMaker.java:94)
        	at jadx.core.dex.visitors.regions.RegionMakerVisitor.visit(RegionMakerVisitor.java:52)
        */
    public static int getWindowLayerFromTypeLw(int r11, android.view.IWindowManager r12) {
        /*
            Method dump skipped, instructions count: 290
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.policy.xpPhoneWindowManager.getWindowLayerFromTypeLw(int, android.view.IWindowManager):int");
    }

    public static int getParentLayerFromTypeLw(int type) {
        switch (type) {
            case 1:
            case 2:
            case 5:
            case 6:
                return 2;
            case 2011:
            case 2012:
                return -1;
            case 2013:
                return 0;
            case 2039:
            case 2040:
                return 1;
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
                return highLevel;
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
            switch (type) {
                case 1:
                    if (compareRect != null) {
                        left = targetRect.left >= compareRect.left ? targetRect.left : compareRect.left;
                        top = targetRect.top >= compareRect.top ? targetRect.top : compareRect.top;
                        right = targetRect.right < compareRect.right ? targetRect.right : compareRect.right;
                        bottom = targetRect.bottom < compareRect.bottom ? targetRect.bottom : compareRect.bottom;
                        break;
                    }
                    break;
                case 2:
                    if (compareRect != null) {
                        left = targetRect.left >= compareRect.left ? compareRect.left : targetRect.left;
                        top = targetRect.top >= compareRect.top ? compareRect.top : targetRect.top;
                        right = targetRect.right < compareRect.right ? compareRect.right : targetRect.right;
                        bottom = targetRect.bottom < compareRect.bottom ? compareRect.bottom : targetRect.bottom;
                        break;
                    }
                    break;
            }
            return new Rect(left, top, right, bottom);
        }
        return null;
    }

    public static boolean hasFlag(int flags, int targetFlag) {
        return (flags & targetFlag) == targetFlag;
    }

    /* loaded from: classes.dex */
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

    /* loaded from: classes.dex */
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
