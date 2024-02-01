package com.android.server.wm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.view.WindowManager;
import com.android.server.wm.SharedDisplayContainer;
import com.android.server.wm.SharedDisplayPolicy;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.app.xpPackageManager;
import com.xiaopeng.server.app.xpActivityManagerService;
import com.xiaopeng.server.ext.ExternalManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.policy.xpBootManagerPolicy;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.util.concurrent.ConcurrentHashMap;

/* loaded from: classes2.dex */
public class AppTaskPolicy {
    public static final int POLICY_FAIL = 1;
    public static final int POLICY_FULL_SCREEN_MODE_DENIED = 14;
    public static final int POLICY_NO_LIMIT = 100;
    public static final int POLICY_PASS = 0;
    public static final int STATE_DEFAULT = -1;
    public static final int STATE_DISABLE = 2;
    public static final int STATE_ENABLED = 1;
    private static final String TAG = "AppTaskPolicy";
    public static final int TYPE_APP_DEFAULT = 0;
    public static final int TYPE_APP_SLIDE = 2;
    public static final int TYPE_APP_START = 1;
    private SharedDisplayContainer mSharedDisplayContainer;
    private static final boolean PACKAGE_VERIFICATION = SystemProperties.getBoolean("persist.sys.xp.pm.package.verification", false);
    private static final ConcurrentHashMap<String, Integer> sPackageState = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AppEventRecord> sEventRecord = new ConcurrentHashMap<>();
    private static AppTaskPolicy sPolicy = null;
    private AppPolicy mDefaultPolicy = new AppDefaultPolicy();
    private AppPolicy mStartPolicy = new AppStartPolicy();
    private AppPolicy mSlidePolicy = new AppSlidePolicy();

    private AppTaskPolicy() {
    }

    public static AppTaskPolicy get() {
        if (sPolicy == null) {
            synchronized (AppTaskPolicy.class) {
                if (sPolicy == null) {
                    sPolicy = new AppTaskPolicy();
                }
            }
        }
        return sPolicy;
    }

    public void setSharedDisplayContainer(SharedDisplayContainer container) {
        this.mSharedDisplayContainer = container;
    }

    public synchronized int getAppPolicy(Context context, int type, Bundle extras, Bundle callback) {
        int policy;
        policy = 0;
        if (type == 0) {
            policy = this.mDefaultPolicy.getPolicy(context, extras, callback);
        } else if (type == 1) {
            policy = this.mStartPolicy.getPolicy(context, extras, callback);
        } else if (type == 2) {
            policy = this.mSlidePolicy.getPolicy(context, extras, callback);
        }
        return policy;
    }

    public static boolean checkNextPolicy(int policy) {
        return policy == 0;
    }

    public static boolean checkNextPolicy(int policy, boolean noLimit) {
        return !noLimit && policy == 0;
    }

    public static boolean checkNextCondition(boolean condition) {
        return condition;
    }

    public static int checkAppPolicy(Context context, String packageName, Bundle extras) {
        Object value = ExternalManagerService.get(context).getValue(2, "checkAppPolicy", packageName, extras);
        return xpTextUtils.toInteger(value, 0).intValue();
    }

    public static boolean preventAppFromMode(int mode) {
        boolean confirm = mode >= 0;
        if (SharedDisplayManager.isUnityUI()) {
            confirm = xpWindowManager.UnityWindowParams.shouldConfirmSlideEvent(mode);
        }
        return !confirm && mode >= 0;
    }

    public static boolean preventAppFromIntent(String packageName, Intent intent) {
        if (!TextUtils.isEmpty(packageName)) {
            SharedDisplayPolicy.PackageSettings settings = SharedDisplayPolicy.getPackageSettings(packageName);
            boolean noSlide = SharedDisplayPolicy.PackageSettings.hasFlag(settings, 512);
            if (noSlide) {
                return true;
            }
        }
        if (intent != null) {
            int privateFlags = intent.getPrivateFlags();
            boolean panel = (privateFlags & 256) == 256;
            boolean dialog = (privateFlags & 128) == 128;
            boolean physicalFullscreen = (privateFlags & 32) == 32;
            if (panel || dialog || physicalFullscreen) {
                return true;
            }
        }
        return false;
    }

    public static boolean preventAppFromResized(Context context, AppPolicy policy, String packageName, Intent intent) {
        if (!SharedDisplayManager.enable() || context == null || policy == null || TextUtils.isEmpty(packageName) || intent == null) {
            return false;
        }
        boolean taskResizing = "1".equals(SystemProperties.get("xui.sys.shared.display.task.resizing", "0"));
        if (taskResizing) {
            return true;
        }
        boolean prevent = false;
        final int toSharedId = SharedDisplayManager.sharedValid(intent.getSharedId()) ? intent.getSharedId() : 0;
        int toScreenId = SharedDisplayManager.findScreenId(toSharedId);
        try {
            final int fromScreenId = SharedDisplayManager.generateNextId(0, toScreenId);
            int fromSharedId = SharedDisplayManager.generateNextId(1, toSharedId);
            String top = SharedDisplayContainer.SharedDisplayImpl.getTopActivity(fromScreenId);
            ComponentName component = top != null ? ComponentName.unflattenFromString(top) : null;
            String _packageName = component != null ? component.getPackageName() : "";
            if (packageName.equals(_packageName)) {
                Bundle extras = new Bundle();
                SharedDisplayFactory.addBundle(extras, "intent", intent);
                SharedDisplayFactory.addBundle(extras, "packageName", packageName);
                SharedDisplayFactory.addBundle(extras, "sharedId", Integer.valueOf(fromSharedId));
                SharedDisplayFactory.addBundle(extras, "screenId", Integer.valueOf(fromScreenId));
                SharedDisplayFactory.addBundle(extras, "extra", "user");
                boolean resizeable = intent.getBooleanExtra("resizeable", true);
                boolean slideEnabled = policy.getPolicy(context, extras, null) == 0;
                if (resizeable && slideEnabled) {
                    final WindowManager wm = (WindowManager) context.getSystemService("window");
                    new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$AppTaskPolicy$sSBS3qFxEqG0OV3CtEQus5Sgd0U
                        @Override // java.lang.Runnable
                        public final void run() {
                            AppTaskPolicy.lambda$preventAppFromResized$0(fromScreenId, wm, toSharedId);
                        }
                    });
                    prevent = true;
                } else {
                    xpActivityManager.getActivityTaskManager().finishPackageActivity(packageName);
                }
            } else {
                boolean isVisibleInScreen = SharedDisplayContainer.SharedDisplayImpl.isVisibleInScreen(packageName, fromScreenId);
                if (isVisibleInScreen) {
                    xpActivityManager.getActivityTaskManager().finishPackageActivity(packageName);
                }
            }
        } catch (Exception e) {
            xpLogger.i(TAG, "resizeActivityTask e=" + e);
        }
        return prevent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$preventAppFromResized$0(int fromScreenId, WindowManager wm, int toSharedId) {
        Bundle bundle = new Bundle();
        SharedDisplayFactory.addBundle(bundle, "topOnly", true);
        SharedDisplayFactory.addBundle(bundle, "screenId", Integer.valueOf(fromScreenId));
        wm.dismissDialog(bundle);
        wm.setSharedEvent(0, toSharedId, "user");
    }

    /* JADX WARN: Removed duplicated region for block: B:22:0x004a A[Catch: Exception -> 0x0084, TRY_LEAVE, TryCatch #2 {Exception -> 0x0084, blocks: (B:20:0x0044, B:22:0x004a), top: B:56:0x0044 }] */
    /* JADX WARN: Removed duplicated region for block: B:26:0x0052  */
    /* JADX WARN: Removed duplicated region for block: B:39:0x006b A[Catch: Exception -> 0x0064, TRY_LEAVE, TryCatch #1 {Exception -> 0x0064, blocks: (B:30:0x0059, B:39:0x006b), top: B:54:0x0059 }] */
    /* JADX WARN: Removed duplicated region for block: B:42:0x0082  */
    /* JADX WARN: Removed duplicated region for block: B:54:0x0059 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:58:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static boolean preventAppFromInterval(int r18, java.lang.String r19, int r20) {
        /*
            r7 = r18
            boolean r0 = com.xiaopeng.view.SharedDisplayManager.enable()
            r8 = 0
            if (r0 != 0) goto La
            return r8
        La:
            r0 = 500(0x1f4, double:2.47E-321)
            r9 = 1
            if (r7 == 0) goto L1a
            if (r7 == r9) goto L16
            r2 = 2
            if (r7 == r2) goto L1a
            r10 = r0
            goto L1d
        L16:
            r0 = 1500(0x5dc, double:7.41E-321)
            r10 = r0
            goto L1d
        L1a:
            r0 = 1000(0x3e8, double:4.94E-321)
            r10 = r0
        L1d:
            java.util.concurrent.ConcurrentHashMap<java.lang.Integer, com.android.server.wm.AppTaskPolicy$AppEventRecord> r0 = com.android.server.wm.AppTaskPolicy.sEventRecord     // Catch: java.lang.Exception -> L8b
            java.lang.Integer r1 = java.lang.Integer.valueOf(r18)     // Catch: java.lang.Exception -> L8b
            com.android.server.wm.AppTaskPolicy$AppEventRecord r2 = new com.android.server.wm.AppTaskPolicy$AppEventRecord     // Catch: java.lang.Exception -> L8b
            r2.<init>()     // Catch: java.lang.Exception -> L8b
            java.lang.Object r0 = r0.getOrDefault(r1, r2)     // Catch: java.lang.Exception -> L8b
            com.android.server.wm.AppTaskPolicy$AppEventRecord r0 = (com.android.server.wm.AppTaskPolicy.AppEventRecord) r0     // Catch: java.lang.Exception -> L8b
            if (r0 == 0) goto L86
            boolean r1 = android.text.TextUtils.isEmpty(r19)     // Catch: java.lang.Exception -> L8b
            if (r1 == 0) goto L3b
            r14 = r19
            r15 = r20
            goto L8a
        L3b:
            long r1 = android.os.SystemClock.elapsedRealtime()     // Catch: java.lang.Exception -> L8b
            r12 = r1
            java.lang.String r1 = r0.packageName     // Catch: java.lang.Exception -> L8b
            r14 = r19
            boolean r1 = android.text.TextUtils.equals(r14, r1)     // Catch: java.lang.Exception -> L84
            if (r1 == 0) goto L52
            int r1 = r0.screenId     // Catch: java.lang.Exception -> L84
            r15 = r20
            if (r15 == r1) goto L54
            r1 = r9
            goto L55
        L52:
            r15 = r20
        L54:
            r1 = r8
        L55:
            r16 = r1
            if (r16 == 0) goto L66
            long r1 = r0.eventMillis     // Catch: java.lang.Exception -> L64
            long r1 = r12 - r1
            int r1 = (r1 > r10 ? 1 : (r1 == r10 ? 0 : -1))
            if (r1 <= 0) goto L62
            goto L66
        L62:
            r1 = r8
            goto L67
        L64:
            r0 = move-exception
            goto L90
        L66:
            r1 = r9
        L67:
            r17 = r1
            if (r17 == 0) goto L7f
            r1 = r0
            r2 = r20
            r3 = r19
            r4 = r18
            r5 = r12
            r1.set(r2, r3, r4, r5)     // Catch: java.lang.Exception -> L64
            java.util.concurrent.ConcurrentHashMap<java.lang.Integer, com.android.server.wm.AppTaskPolicy$AppEventRecord> r1 = com.android.server.wm.AppTaskPolicy.sEventRecord     // Catch: java.lang.Exception -> L64
            java.lang.Integer r2 = java.lang.Integer.valueOf(r18)     // Catch: java.lang.Exception -> L64
            r1.put(r2, r0)     // Catch: java.lang.Exception -> L64
        L7f:
            if (r17 == 0) goto L82
            goto L83
        L82:
            r8 = r9
        L83:
            return r8
        L84:
            r0 = move-exception
            goto L8e
        L86:
            r14 = r19
            r15 = r20
        L8a:
            return r8
        L8b:
            r0 = move-exception
            r14 = r19
        L8e:
            r15 = r20
        L90:
            return r8
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.AppTaskPolicy.preventAppFromInterval(int, java.lang.String, int):boolean");
    }

    public static boolean preventAppFromVerification(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName) || !PACKAGE_VERIFICATION || !Build.IS_USER) {
            return false;
        }
        int state = sPackageState.getOrDefault(packageName, -1).intValue();
        if (state != -1) {
            if (state == 1 || state != 2) {
                return false;
            }
            return true;
        }
        int currentState = 1;
        try {
            boolean system = ActivityInfoManager.isSystemApplication(packageName);
            if (!system) {
                xpPackageInfo xpi = xpPackageManager.getPackageManager().getXpPackageInfo(packageName);
                if (xpi != null) {
                    String pkgSign = "";
                    try {
                        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(packageName, 64);
                        Signature[] signs = packageInfo.signatures;
                        pkgSign = signs[0].toCharsString();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (xpi.apkSignInfo != null && !pkgSign.equals(xpi.apkSignInfo)) {
                        currentState = 2;
                    }
                } else {
                    currentState = 2;
                }
            }
            sPackageState.put(packageName, Integer.valueOf(currentState));
            return currentState == 2;
        } catch (Exception e2) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class AppPolicy {
        private AppPolicy() {
        }

        public int getType() {
            return -1;
        }

        public int getPolicy(Context context, Bundle extras, Bundle callback) {
            return 0;
        }
    }

    /* loaded from: classes2.dex */
    private class AppDefaultPolicy extends AppPolicy {
        private AppDefaultPolicy() {
            super();
        }

        @Override // com.android.server.wm.AppTaskPolicy.AppPolicy
        public int getType() {
            return 0;
        }

        @Override // com.android.server.wm.AppTaskPolicy.AppPolicy
        public int getPolicy(Context context, Bundle extras, Bundle callback) {
            int policy = super.getPolicy(context, extras, callback);
            if (context == null || extras == null) {
                return policy;
            }
            String packageName = (String) SharedDisplayFactory.getBundle(extras, "packageName", "");
            int sharedId = ((Integer) SharedDisplayFactory.getBundle(extras, "sharedId", 0)).intValue();
            if (TextUtils.isEmpty(packageName) || AppTaskPolicy.this.mSharedDisplayContainer == null) {
                return 1;
            }
            int mode = SharedDisplayContainer.SharedDisplayImpl.getMode(sharedId);
            int type = SharedDisplayContainer.SharedDisplayImpl.getType(sharedId);
            String mixedType = SharedDisplayContainer.SharedDisplayImpl.getMixedType(sharedId);
            String reason = "";
            if (AppTaskPolicy.checkNextPolicy(policy)) {
                boolean screenEnabled = SharedDisplayContainer.SharedDisplayImpl.isSharedScreenEnabled(SharedDisplayManager.findScreenId(sharedId));
                if (!screenEnabled) {
                    policy = 1;
                }
                if (policy == 1) {
                    reason = "failFromScreenEnabled";
                }
            }
            boolean screenEnabled2 = AppTaskPolicy.checkNextPolicy(policy);
            if (screenEnabled2) {
                int activityLevel = SharedDisplayContainer.SharedDisplayImpl.getActivityLevel(SharedDisplayManager.findScreenId(sharedId));
                boolean preventFromActivityLevel = activityLevel > 0;
                if (preventFromActivityLevel) {
                    policy = 1;
                }
                if (policy == 1) {
                    reason = "failFromActivityLevel";
                }
            }
            boolean preventFromActivityLevel2 = AppTaskPolicy.checkNextPolicy(policy);
            if (preventFromActivityLevel2) {
                SharedDisplayFactory.addBundle(extras, xpInputManagerService.InputPolicyKey.KEY_MODE, Integer.valueOf(mode));
                SharedDisplayFactory.addBundle(extras, "type", Integer.valueOf(type));
                SharedDisplayFactory.addBundle(extras, "mixedType", mixedType);
                SharedDisplayFactory.addBundle(extras, "policyType", Integer.valueOf(getType()));
                SharedDisplayFactory.addBundle(extras, "sharedId", Integer.valueOf(sharedId));
                SharedDisplayFactory.addBundle(extras, "screenId", Integer.valueOf(SharedDisplayManager.findScreenId(sharedId)));
                int appPolicy = AppTaskPolicy.checkAppPolicy(context, packageName, extras);
                if (appPolicy != 0) {
                    policy = 1;
                }
                if (appPolicy == 100) {
                    policy = 0;
                }
                if (policy == 1) {
                    SharedDisplayFactory.addBundle(callback, "failFromRemote", true);
                }
                if (policy == 1) {
                    reason = "failFromRemote:" + appPolicy;
                }
            }
            StringBuffer buffer = new StringBuffer();
            buffer.append("getAppDefaultPolicy");
            buffer.append(" packageName = " + packageName);
            buffer.append(" policy = " + policy);
            buffer.append(" reason = " + reason);
            buffer.append(" sharedId = " + sharedId);
            xpLogger.log(AppTaskPolicy.TAG, buffer.toString());
            return policy;
        }
    }

    /* loaded from: classes2.dex */
    private class AppStartPolicy extends AppPolicy {
        private AppStartPolicy() {
            super();
        }

        @Override // com.android.server.wm.AppTaskPolicy.AppPolicy
        public int getType() {
            return 1;
        }

        @Override // com.android.server.wm.AppTaskPolicy.AppPolicy
        public int getPolicy(Context context, Bundle extras, Bundle callback) {
            boolean noLimit;
            String reason;
            int policy = super.getPolicy(context, extras, callback);
            if (context == null || extras == null) {
                return policy;
            }
            ActivityInfo ai = (ActivityInfo) SharedDisplayFactory.getBundle(extras, "ai", null);
            Intent intent = (Intent) SharedDisplayFactory.getBundle(extras, "intent", null);
            String packageName = (String) SharedDisplayFactory.getBundle(extras, "packageName", "");
            int sharedId = ((Integer) SharedDisplayFactory.getBundle(extras, "sharedId", 0)).intValue();
            int screenId = ((Integer) SharedDisplayFactory.getBundle(extras, "screenId", 0)).intValue();
            if (!TextUtils.isEmpty(packageName) && ai != null) {
                if (intent != null) {
                    int _id = intent.getSharedId();
                    int mode = SharedDisplayContainer.SharedDisplayImpl.getMode(sharedId);
                    int type = SharedDisplayContainer.SharedDisplayImpl.getType(sharedId);
                    String mixedType = SharedDisplayContainer.SharedDisplayImpl.getMixedType(sharedId);
                    int flags = intent.getFlags();
                    int privateFlags = intent.getPrivateFlags();
                    boolean isHome = ActivityInfoManager.hasHomeFlags(privateFlags);
                    SharedDisplayPolicy.PackageSettings settings = SharedDisplayPolicy.getPackageSettings(packageName);
                    boolean noLimitFromIntent = (flags & 1024) == 1024;
                    boolean noLimitFromSettings = SharedDisplayPolicy.PackageSettings.hasFlag(settings, 1024, screenId);
                    boolean primary = screenId == 0;
                    ActivityInfoManager.isSystemApplication(packageName);
                    String reason2 = "";
                    boolean noLimitFromApp = false;
                    boolean noLimit2 = isHome || noLimitFromIntent || noLimitFromSettings;
                    if (AppTaskPolicy.checkNextPolicy(policy)) {
                        boolean failFromBoot = xpBootManagerPolicy.get(context).abortActivityIfNeed(context, ai.getComponentName());
                        if (failFromBoot) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromBoot";
                        }
                    }
                    boolean noLimitFromIntent2 = AppTaskPolicy.checkNextPolicy(policy);
                    if (noLimitFromIntent2) {
                        boolean failFromVerification = AppTaskPolicy.preventAppFromVerification(context, packageName);
                        if (failFromVerification) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromVerification";
                        }
                    }
                    boolean failFromVerification2 = AppTaskPolicy.checkNextPolicy(policy);
                    if (!failFromVerification2) {
                        noLimit = noLimit2;
                    } else {
                        SharedDisplayFactory.addBundle(extras, xpInputManagerService.InputPolicyKey.KEY_MODE, Integer.valueOf(mode));
                        SharedDisplayFactory.addBundle(extras, "type", Integer.valueOf(type));
                        SharedDisplayFactory.addBundle(extras, "noLimit", Boolean.valueOf(noLimit2));
                        SharedDisplayFactory.addBundle(extras, "mixedType", mixedType);
                        SharedDisplayFactory.addBundle(extras, "policyType", Integer.valueOf(getType()));
                        int appPolicy = AppTaskPolicy.checkAppPolicy(context, packageName, extras);
                        if (appPolicy != 0) {
                            policy = 1;
                        }
                        if (appPolicy == 100) {
                            policy = 0;
                            noLimitFromApp = true;
                        }
                        boolean noLimit3 = noLimit2 || noLimitFromApp;
                        if (policy == 1) {
                            reason2 = "failFromAppPolicy:" + appPolicy;
                        }
                        noLimit = noLimit3;
                    }
                    if (AppTaskPolicy.checkNextPolicy(policy, noLimit) && !noLimit) {
                        boolean screenEnabled = SharedDisplayContainer.SharedDisplayImpl.isSharedScreenEnabled(1, screenId);
                        if (!screenEnabled) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromScreenEnabled";
                        }
                    }
                    if (AppTaskPolicy.checkNextPolicy(policy, noLimit)) {
                        boolean failFromActivityLevel = xpActivityManagerService.preventAppFromActivityLevel(context, ai, intent);
                        if (failFromActivityLevel) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromActivityLevel";
                        }
                    }
                    boolean failFromActivityLevel2 = AppTaskPolicy.checkNextPolicy(policy);
                    if (failFromActivityLevel2) {
                        boolean failFromResized = AppTaskPolicy.preventAppFromResized(context, AppTaskPolicy.this.mSlidePolicy, packageName, intent);
                        if (failFromResized) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromResized";
                        }
                    }
                    if (!AppTaskPolicy.checkNextPolicy(policy)) {
                        reason = reason2;
                    } else {
                        boolean preventFromInterval = AppTaskPolicy.preventAppFromInterval(getType(), packageName, screenId);
                        if (preventFromInterval) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromInterval";
                        }
                        reason = reason2;
                    }
                    StringBuffer buffer = new StringBuffer();
                    buffer.append("getAppStartPolicy");
                    buffer.append(" component = " + ai.getComponentName());
                    buffer.append(" policy = " + policy);
                    buffer.append(" reason = " + reason);
                    buffer.append(" sharedId = " + _id);
                    buffer.append(" flags = 0x" + Integer.toHexString(flags));
                    buffer.append(" privateFlags = 0x" + Integer.toHexString(privateFlags));
                    xpLogger.log(AppTaskPolicy.TAG, buffer.toString());
                    return policy;
                }
            }
            return policy;
        }
    }

    /* loaded from: classes2.dex */
    private class AppSlidePolicy extends AppPolicy {
        private AppSlidePolicy() {
            super();
        }

        @Override // com.android.server.wm.AppTaskPolicy.AppPolicy
        public int getType() {
            return 2;
        }

        @Override // com.android.server.wm.AppTaskPolicy.AppPolicy
        public int getPolicy(Context context, Bundle extras, Bundle callback) {
            boolean noLimit;
            int policy;
            String reason;
            int policy2 = super.getPolicy(context, extras, callback);
            if (context != null) {
                if (extras == null) {
                    return policy2;
                }
                String extra = (String) SharedDisplayFactory.getBundle(extras, "extra", "");
                String packageName = (String) SharedDisplayFactory.getBundle(extras, "packageName", "");
                int from = ((Integer) SharedDisplayFactory.getBundle(extras, "sharedId", 0)).intValue();
                int to = SharedDisplayContainer.generateNextSharedId(from);
                SharedDisplayManager.findScreenId(from);
                int toScreenId = SharedDisplayManager.findScreenId(to);
                if (!TextUtils.isEmpty(packageName) && AppTaskPolicy.this.mSharedDisplayContainer != null) {
                    int mode = SharedDisplayContainer.SharedDisplayImpl.getMode(to);
                    int type = SharedDisplayContainer.SharedDisplayImpl.getType(to);
                    String mixedType = SharedDisplayContainer.SharedDisplayImpl.getMixedType(to);
                    Intent intent = (Intent) extras.getParcelable("intent");
                    String reason2 = "";
                    boolean noLimitFromApp = false;
                    if (!AppTaskPolicy.checkNextPolicy(policy2)) {
                        noLimit = false;
                        policy = policy2;
                    } else {
                        SharedDisplayFactory.addBundle(extras, xpInputManagerService.InputPolicyKey.KEY_MODE, Integer.valueOf(mode));
                        SharedDisplayFactory.addBundle(extras, "type", Integer.valueOf(type));
                        SharedDisplayFactory.addBundle(extras, "mixedType", mixedType);
                        SharedDisplayFactory.addBundle(extras, "policyType", Integer.valueOf(getType()));
                        SharedDisplayFactory.addBundle(extras, "from", extra);
                        SharedDisplayFactory.addBundle(extras, "sharedId", Integer.valueOf(to));
                        SharedDisplayFactory.addBundle(extras, "screenId", Integer.valueOf(toScreenId));
                        int appPolicy = AppTaskPolicy.checkAppPolicy(context, packageName, extras);
                        policy = appPolicy != 0 ? 1 : policy2;
                        if (appPolicy == 100) {
                            policy = 0;
                            noLimitFromApp = true;
                        }
                        boolean noLimit2 = 0 != 0 || noLimitFromApp;
                        if (policy == 1) {
                            SharedDisplayFactory.addBundle(callback, "appPolicy", Integer.valueOf(appPolicy));
                        }
                        if (policy == 1) {
                            SharedDisplayFactory.addBundle(callback, "failFromRemote", true);
                        }
                        if (policy == 1) {
                            reason2 = "failFromRemote:" + appPolicy;
                        }
                        noLimit = noLimit2;
                    }
                    if (AppTaskPolicy.checkNextPolicy(policy, noLimit)) {
                        boolean screenEnabled = SharedDisplayContainer.SharedDisplayImpl.isSharedScreenEnabled(0) && SharedDisplayContainer.SharedDisplayImpl.isSharedScreenEnabled(1);
                        if (!screenEnabled) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromScreenEnabled";
                        }
                    }
                    if (AppTaskPolicy.checkNextPolicy(policy, noLimit)) {
                        int activityLevel = SharedDisplayContainer.SharedDisplayImpl.getActivityLevel(SharedDisplayManager.findScreenId(to));
                        boolean preventFromActivityLevel = activityLevel > 0;
                        if (preventFromActivityLevel) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromActivityLevel";
                        }
                    }
                    boolean noLimit3 = AppTaskPolicy.checkNextPolicy(policy);
                    if (noLimit3) {
                        boolean packageEnabled = SharedDisplayContainer.SharedDisplayImpl.isSharedPackageEnabled(packageName);
                        if (!packageEnabled) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromPackageEnabled";
                        }
                    }
                    boolean packageEnabled2 = AppTaskPolicy.checkNextPolicy(policy);
                    if (packageEnabled2) {
                        boolean preventFromIntent = AppTaskPolicy.preventAppFromIntent(packageName, intent);
                        if (preventFromIntent) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromIntent";
                        }
                    }
                    boolean preventFromIntent2 = AppTaskPolicy.checkNextPolicy(policy);
                    if (preventFromIntent2) {
                        boolean allowResizeTask = AppTaskPolicy.this.mSharedDisplayContainer.allowResizeTask(from, callback);
                        if (!allowResizeTask) {
                            policy = 1;
                        }
                        if (policy == 1) {
                            reason2 = "failFromResizeTask";
                        }
                    }
                    boolean allowResizeTask2 = AppTaskPolicy.checkNextPolicy(policy);
                    if (allowResizeTask2) {
                        boolean preventFromInterval = AppTaskPolicy.preventAppFromInterval(getType(), packageName, toScreenId);
                        if (preventFromInterval) {
                            policy = 1;
                        }
                        if (preventFromInterval) {
                            SharedDisplayFactory.addBundle(callback, "ignoreWarning", true);
                        }
                        if (policy == 1) {
                            reason2 = "failFromInterval";
                        }
                        reason = reason2;
                    } else {
                        reason = reason2;
                    }
                    StringBuffer buffer = new StringBuffer();
                    buffer.append("getAppSlidePolicy");
                    buffer.append(" packageName = " + packageName);
                    buffer.append(" policy = " + policy);
                    buffer.append(" reason = " + reason);
                    buffer.append(" from = " + from);
                    buffer.append(" to = " + to);
                    xpLogger.log(AppTaskPolicy.TAG, buffer.toString());
                    return policy;
                }
                return 1;
            }
            return policy2;
        }
    }

    /* loaded from: classes2.dex */
    public static final class AppEventRecord {
        public String packageName;
        public int screenId = 0;
        public int eventType = 0;
        public long eventMillis = 0;

        public void set(int screenId, String packageName, int eventType, long eventMillis) {
            this.screenId = screenId;
            this.packageName = packageName;
            this.eventType = eventType;
            this.eventMillis = eventMillis;
        }
    }
}
