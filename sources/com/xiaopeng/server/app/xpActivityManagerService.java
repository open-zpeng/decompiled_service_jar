package com.xiaopeng.server.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.IProcessObserver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager;
import com.android.server.wm.AppTaskPolicy;
import com.android.server.wm.SharedDisplayContainer;
import com.android.server.wm.SharedDisplayFactory;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.MiniProgramManager;
import com.xiaopeng.app.xpActivityInfo;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.app.xpDialogInfo;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.ext.ExternalManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.policy.xpBootManagerPolicy;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class xpActivityManagerService {
    private final Context mContext;
    private final WorkHandler mHandler;
    private xpHomeManagerService mHomeManager;
    private xpPackageInfo mInfo;
    private static final String TAG = "xpActivityManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final String[] GRANT_FOLDERS = {"/data/Log"};
    private static final ConcurrentHashMap<Integer, ComponentName> sTopComponent = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> sNoLimitedHistory = new ConcurrentHashMap<>();
    private static boolean sTopActivityFullscreen = false;
    private static String sDialogEvent = "fromResumed";
    private static ArrayList<xpActivityRecord> sActivityRecordHistory = new ArrayList<>();
    private static HashMap<Integer, xpDialogInfo> sDialogRecord = new HashMap<>();
    private static xpActivityManagerService sService = null;
    private IProcessObserver.Stub sProcessObserver = new IProcessObserver.Stub() { // from class: com.xiaopeng.server.app.xpActivityManagerService.1
        public void onForegroundActivitiesChanged(int pid, int uid, boolean hasForegroundActivities) throws RemoteException {
            xpActivityManagerService.this.onDialogProcessChanged(pid, uid, false, hasForegroundActivities);
        }

        public void onForegroundServicesChanged(int i, int i1, int i2) throws RemoteException {
        }

        public void onProcessDied(int pid, int uid) throws RemoteException {
            xpActivityManagerService.this.onDialogProcessChanged(pid, uid, true, false);
        }
    };
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);

    private xpActivityManagerService(Context context) {
        this.mContext = context;
        this.mHandlerThread.start();
        this.mHandler = new WorkHandler(this.mHandlerThread.getLooper());
        this.mHomeManager = new xpHomeManagerService(context);
    }

    public static xpActivityManagerService get(Context context) {
        if (sService == null) {
            synchronized (xpActivityManagerService.class) {
                if (sService == null) {
                    sService = new xpActivityManagerService(context);
                }
            }
        }
        return sService;
    }

    public void onStart() {
    }

    public void onStop() {
    }

    public void systemReady() {
        registerProcessObserver();
        xpHomeManagerService xphomemanagerservice = this.mHomeManager;
        if (xphomemanagerservice != null) {
            xphomemanagerservice.systemReady();
        }
    }

    public Context getContext() {
        return this.mContext;
    }

    public void onActivityCreated(xpActivityRecord r) {
    }

    public void onActivityResumed(xpActivityRecord r) {
        if (DEBUG) {
            Log.d(TAG, "onActivityResumed r=" + r.realActivity);
        }
        updateResumedActivity(r.realActivity);
    }

    public void onActivityPaused(xpActivityRecord r) {
    }

    public void onActivityStopped(xpActivityRecord r) {
    }

    public void performActivityChanged(Bundle extras) {
        xpActivityRecord r;
        if (extras == null || (r = convertActivityRecord(extras)) == null || r.intentInfo == null || r.intentInfo.component == null) {
            return;
        }
        try {
            Message msg = new Message();
            msg.what = 100;
            Bundle data = new Bundle(extras);
            msg.setData(data);
            this.mHandler.sendMessageDelayed(msg, 200L);
            updateActivityRecordHistory(r);
        } catch (Exception e) {
            xpLogger.log(TAG, "handleActivityChanged e=" + e);
        }
    }

    public void performTopActivityChanged(Bundle extras) {
    }

    public void handleActivityChanged(Message msg) {
        Bundle bundle;
        String packageName;
        if (msg == null || (bundle = msg.getData()) == null) {
            return;
        }
        String component = bundle.getString("component", "");
        if (TextUtils.isEmpty(component)) {
            return;
        }
        try {
            xpActivityIntentInfo info = new xpActivityIntentInfo();
            ComponentName componentName = ComponentName.unflattenFromString(component);
            if (componentName == null) {
                packageName = "";
            } else {
                try {
                    packageName = componentName.getPackageName();
                } catch (Exception e) {
                    e = e;
                    xpLogger.e(TAG, "handleActivityChangedLocked e=" + e);
                    return;
                }
            }
            boolean match = false;
            int flags = bundle.getInt(xpInputManagerService.InputPolicyKey.KEY_FLAGS, 0);
            boolean toppingActivity = (flags & 16) == 16;
            info.component = component;
            info.flags = flags;
            info.token = bundle.getBinder("token");
            info.data = bundle.getString("data", "");
            info.window = bundle.getInt("window", 0);
            info.dimAmount = bundle.getFloat("dimAmount", 0.0f);
            info.fullscreen = bundle.getBoolean("fullscreen", false);
            info.windowLevel = toppingActivity ? 1 : 0;
            info.miniProgram = MiniProgramManager.isMiniProgram(packageName);
            ComponentName currentTopComponent = getTopComponent(this.mContext);
            String currentTopComponentName = currentTopComponent != null ? currentTopComponent.flattenToString() : "";
            if (!TextUtils.isEmpty(currentTopComponentName)) {
                if (currentTopComponentName.equals(component)) {
                    match = true;
                }
            }
            if (xpActivityManager.isComponentValid(currentTopComponent) && !match) {
                xpActivityRecord ar = getActivityRecordFromHistory(ComponentName.unflattenFromString(currentTopComponentName));
                if (ar != null && ar.intentInfo != null && ar.intentInfo.component != null) {
                    final xpActivityIntentInfo i = ar.intentInfo;
                    try {
                        this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.app.-$$Lambda$xpActivityManagerService$pLcEZqrO0EX22Wgfjmz3nehi1-A
                            @Override // java.lang.Runnable
                            public final void run() {
                                xpActivityManagerService.this.lambda$handleActivityChanged$0$xpActivityManagerService(i);
                            }
                        });
                    } catch (Exception e2) {
                        e = e2;
                        xpLogger.e(TAG, "handleActivityChangedLocked e=" + e);
                        return;
                    }
                }
            }
            sTopActivityFullscreen = info.fullscreen;
            StringBuffer buffer = new StringBuffer("");
            buffer.append("handleActivityChangedLocked ");
            buffer.append(" component=" + component);
            buffer.append(" " + info.toString());
            buffer.append(" currentTopComponent=" + currentTopComponent);
            xpLogger.i(TAG, buffer.toString());
            xpBootManagerPolicy.get(this.mContext).onActivityChanged(componentName);
            sendActivityChangedBroadcast(info);
            updateResumedActivity(componentName);
            WorkHandler workHandler = this.mHandler;
            WorkHandler workHandler2 = this.mHandler;
            WorkHandler workHandler3 = this.mHandler;
            workHandler.sendMessage(workHandler2.obtainMessage(101, packageName));
        } catch (Exception e3) {
            e = e3;
        }
    }

    public /* synthetic */ void lambda$handleActivityChanged$0$xpActivityManagerService(xpActivityIntentInfo i) {
        performActivityChanged(i.toBundle());
    }

    public void handleTopActivityChanged(Message msg) {
    }

    private void sendActivityChangedBroadcast(xpActivityIntentInfo info) {
        if (info != null) {
            try {
                info.sendBroadcast(this.mContext);
            } catch (Exception e) {
                xpLogger.e(TAG, "sendActivityChangedBroadcast e=" + e);
            }
        }
    }

    private void updateResumedActivity(ComponentName component) {
        if (component == null) {
            return;
        }
        boolean defaultHome = isDefaultHome(this.mContext, component);
        boolean secondaryHome = isSecondaryHome(this.mContext, component);
        if (secondaryHome) {
            return;
        }
        int screenId = 0;
        if (SharedDisplayManager.enable()) {
            try {
                IWindowManager wm = xpWindowManager.getWindowManager();
                screenId = wm.getScreenId(component.getPackageName());
            } catch (Exception e) {
            }
        }
        ComponentName last = sTopComponent.getOrDefault(Integer.valueOf(screenId), null);
        sTopComponent.put(Integer.valueOf(screenId), component.clone());
        boolean dismissDialog = true;
        boolean componentChanged = !componentEquals(last, component);
        if (!componentChanged || (SharedDisplayManager.isUnityUI() && defaultHome)) {
            dismissDialog = false;
        }
        setActivityLevel(component);
        if (dismissDialog) {
            dismissDialog(component, sDialogEvent);
        }
        sDialogEvent = "fromResumed";
    }

    private static boolean componentEquals(ComponentName a, ComponentName b) {
        String aComponent = a != null ? a.flattenToString() : "";
        String bComponent = b != null ? b.flattenToString() : "";
        return TextUtils.equals(aComponent, bComponent);
    }

    private void updateActivityRecordHistory(xpActivityRecord r) {
        synchronized (sActivityRecordHistory) {
            if (sActivityRecordHistory.size() > 10) {
                sActivityRecordHistory.remove(0);
            }
            sActivityRecordHistory.add(r);
        }
    }

    private xpActivityRecord getActivityRecordFromHistory(ComponentName cn) {
        synchronized (sActivityRecordHistory) {
            if (cn != null) {
                Iterator<xpActivityRecord> it = sActivityRecordHistory.iterator();
                while (it.hasNext()) {
                    xpActivityRecord r = it.next();
                    if (r != null && cn.equals(r.realActivity)) {
                        return r;
                    }
                }
            }
            return null;
        }
    }

    public boolean isTopActivityFullscreen() {
        return sTopActivityFullscreen;
    }

    private xpActivityRecord convertActivityRecord(Bundle bundle) {
        if (bundle != null) {
            try {
                String var = bundle.getString("component", "");
                ComponentName component = ComponentName.unflattenFromString(var);
                if (component != null) {
                    xpActivityRecord r = new xpActivityRecord(component);
                    xpActivityIntentInfo info = new xpActivityIntentInfo();
                    info.component = var;
                    info.token = bundle.getBinder("token");
                    info.data = bundle.getString("data", "");
                    info.flags = bundle.getInt(xpInputManagerService.InputPolicyKey.KEY_FLAGS, 0);
                    info.window = bundle.getInt("window", 0);
                    info.dimAmount = bundle.getFloat("dimAmount", 0.0f);
                    info.fullscreen = bundle.getBoolean("fullscreen", false);
                    r.intentInfo = info;
                    return r;
                }
                return null;
            } catch (Exception e) {
                xpLogger.i(TAG, "convertActivityRecord e=" + e);
                return null;
            }
        }
        return null;
    }

    public List<String> getSpeechObserver() {
        List<ApplicationInfo> apps;
        Set<String> set;
        String str;
        String str2;
        String SPEECH_EVENTS;
        String PREFIX_SPEECH_EVENTS;
        String key;
        String events;
        String str3 = "SPEECH_EVENTS_";
        String str4 = "SPEECH_OBSERVER_";
        List<String> list = new ArrayList<>();
        String SPEECH_EVENTS2 = "SPEECH_EVENTS";
        String SPEECH_OBSERVER = "SPEECH_OBSERVER";
        String PREFIX_SPEECH_EVENTS2 = "SPEECH_EVENTS_";
        try {
            PackageManager pm = this.mContext.getPackageManager();
            apps = pm.getInstalledApplications(128);
        } catch (Exception e) {
        }
        if (apps != null && !apps.isEmpty()) {
            for (ApplicationInfo ai : apps) {
                if (ai != null && ai.metaData != null && (set = ai.metaData.keySet()) != null && !set.isEmpty()) {
                    for (String key2 : set) {
                        try {
                        } catch (Exception e2) {
                            str = str3;
                            str2 = str4;
                            SPEECH_EVENTS = SPEECH_EVENTS2;
                            PREFIX_SPEECH_EVENTS = PREFIX_SPEECH_EVENTS2;
                            key = SPEECH_OBSERVER;
                        }
                        if (!TextUtils.isEmpty(key2)) {
                            SPEECH_EVENTS = SPEECH_EVENTS2;
                            try {
                                key = SPEECH_OBSERVER;
                            } catch (Exception e3) {
                                str = str3;
                                str2 = str4;
                                key = SPEECH_OBSERVER;
                                PREFIX_SPEECH_EVENTS = PREFIX_SPEECH_EVENTS2;
                            }
                            if ("SPEECH_OBSERVER".equals(key2)) {
                                try {
                                    events = ai.metaData.getString("SPEECH_EVENTS", "");
                                    PREFIX_SPEECH_EVENTS = PREFIX_SPEECH_EVENTS2;
                                } catch (Exception e4) {
                                    PREFIX_SPEECH_EVENTS = PREFIX_SPEECH_EVENTS2;
                                    str = str3;
                                    str2 = str4;
                                }
                                try {
                                    String observer = ai.metaData.getString("SPEECH_OBSERVER", "");
                                    if (!TextUtils.isEmpty(events) && !TextUtils.isEmpty(observer)) {
                                        JSONObject object = new JSONObject();
                                        object.put("SPEECH_OBSERVER", observer);
                                        object.put("SPEECH_EVENTS", events);
                                        list.add(object.toString());
                                    }
                                    str = str3;
                                    str2 = str4;
                                } catch (Exception e5) {
                                    str = str3;
                                    str2 = str4;
                                    SPEECH_OBSERVER = key;
                                    SPEECH_EVENTS2 = SPEECH_EVENTS;
                                    PREFIX_SPEECH_EVENTS2 = PREFIX_SPEECH_EVENTS;
                                    str3 = str;
                                    str4 = str2;
                                }
                                SPEECH_OBSERVER = key;
                                SPEECH_EVENTS2 = SPEECH_EVENTS;
                                PREFIX_SPEECH_EVENTS2 = PREFIX_SPEECH_EVENTS;
                                str3 = str;
                                str4 = str2;
                            } else {
                                PREFIX_SPEECH_EVENTS = PREFIX_SPEECH_EVENTS2;
                                if (!key2.startsWith(str4)) {
                                    str = str3;
                                    str2 = str4;
                                } else {
                                    String suffix = key2.substring(str4.length());
                                    if (TextUtils.isEmpty(suffix)) {
                                        str = str3;
                                        str2 = str4;
                                    } else {
                                        String eventsKey = str3 + suffix;
                                        String events2 = ai.metaData.getString(eventsKey, "");
                                        str = str3;
                                        try {
                                            str2 = str4;
                                            try {
                                                String observer2 = ai.metaData.getString(key2, "");
                                                if (!TextUtils.isEmpty(events2) && !TextUtils.isEmpty(observer2)) {
                                                    JSONObject object2 = new JSONObject();
                                                    object2.put(key2, observer2);
                                                    object2.put(eventsKey, events2);
                                                    list.add(object2.toString());
                                                }
                                            } catch (Exception e6) {
                                            }
                                        } catch (Exception e7) {
                                            str2 = str4;
                                            SPEECH_OBSERVER = key;
                                            SPEECH_EVENTS2 = SPEECH_EVENTS;
                                            PREFIX_SPEECH_EVENTS2 = PREFIX_SPEECH_EVENTS;
                                            str3 = str;
                                            str4 = str2;
                                        }
                                    }
                                }
                                SPEECH_OBSERVER = key;
                                SPEECH_EVENTS2 = SPEECH_EVENTS;
                                PREFIX_SPEECH_EVENTS2 = PREFIX_SPEECH_EVENTS;
                                str3 = str;
                                str4 = str2;
                            }
                        }
                    }
                }
            }
            if (DEBUG) {
                xpLogger.d(TAG, "getSpeechObserver list=" + Arrays.toString(list.toArray()));
            }
            return list;
        }
        return list;
    }

    public void dismissDialog(int type) {
        Bundle extras = new Bundle();
        extras.putBoolean("topOnly", false);
        if (type == 0) {
            extras.putString("fromReason", "fromDefault");
        } else if (type == 1) {
            extras.putBoolean("systemOnly", true);
        } else if (type == 2) {
            extras.putBoolean("applicationOnly", true);
        }
        dismissDialog(xpWindowManager.getWindowManager(), extras);
    }

    public void dismissDialog(ComponentName component, String event) {
        String packageName;
        if (component != null) {
            try {
                packageName = component.getPackageName();
            } catch (Exception e) {
                return;
            }
        } else {
            packageName = "";
        }
        IWindowManager wm = xpWindowManager.getWindowManager();
        int screenId = wm.getScreenId(packageName);
        Bundle extras = new Bundle();
        extras.putInt("screenId", screenId);
        extras.putBoolean("topOnly", false);
        extras.putString("fromReason", event);
        dismissDialog(wm, extras);
    }

    public void dismissDialog(final IWindowManager wm, final Bundle extras) {
        this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.app.-$$Lambda$xpActivityManagerService$hfU6rIOIhv3ttMbp1cKq-CBMUYM
            @Override // java.lang.Runnable
            public final void run() {
                wm.dismissDialog(extras);
            }
        });
    }

    public List<xpDialogInfo> getDialogRecorder(boolean topOnly) {
        if (topOnly) {
            xpDialogInfo info = getDialogRecorder();
            if (info != null) {
                return Arrays.asList(info);
            }
            return null;
        }
        synchronized (sDialogRecord) {
            if (sDialogRecord.isEmpty()) {
                return null;
            }
            return new ArrayList(sDialogRecord.values());
        }
    }

    public xpDialogInfo getDialogRecorder() {
        synchronized (sDialogRecord) {
            for (Integer num : sDialogRecord.keySet()) {
                int hashcode = num.intValue();
                xpDialogInfo info = sDialogRecord.get(Integer.valueOf(hashcode));
                if (info != null && info.topVisible()) {
                    return info;
                }
            }
            return null;
        }
    }

    public void setDialogRecorder(final xpDialogInfo info) {
        if (info == null) {
            return;
        }
        synchronized (sDialogRecord) {
            boolean needRemove = info.needRemove();
            boolean exist = sDialogRecord.containsKey(Integer.valueOf(info.hashCode));
            if (needRemove) {
                if (exist) {
                    sDialogRecord.remove(Integer.valueOf(info.hashCode));
                }
            } else {
                sDialogRecord.put(Integer.valueOf(info.hashCode), info);
            }
        }
        this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.app.-$$Lambda$xpActivityManagerService$4zcaog_hwf9vr3XCcaXVgl47-1Y
            @Override // java.lang.Runnable
            public final void run() {
                xpActivityManagerService.this.lambda$setDialogRecorder$2$xpActivityManagerService(info);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:21:0x0048 A[Catch: all -> 0x008e, TryCatch #0 {, blocks: (B:4:0x0003, B:5:0x0012, B:7:0x0018, B:9:0x0030, B:11:0x0034, B:14:0x003a, B:21:0x0048, B:23:0x0050, B:25:0x0056, B:27:0x0058, B:28:0x005c, B:30:0x0062, B:32:0x0070, B:34:0x008c), top: B:39:0x0003 }] */
    /* JADX WARN: Removed duplicated region for block: B:45:0x004f A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void onDialogProcessChanged(int r8, int r9, boolean r10, boolean r11) {
        /*
            r7 = this;
            java.util.HashMap<java.lang.Integer, com.xiaopeng.app.xpDialogInfo> r0 = com.xiaopeng.server.app.xpActivityManagerService.sDialogRecord
            monitor-enter(r0)
            java.util.ArrayList r1 = new java.util.ArrayList     // Catch: java.lang.Throwable -> L8e
            r1.<init>()     // Catch: java.lang.Throwable -> L8e
            java.util.HashMap<java.lang.Integer, com.xiaopeng.app.xpDialogInfo> r2 = com.xiaopeng.server.app.xpActivityManagerService.sDialogRecord     // Catch: java.lang.Throwable -> L8e
            java.util.Set r2 = r2.keySet()     // Catch: java.lang.Throwable -> L8e
            java.util.Iterator r2 = r2.iterator()     // Catch: java.lang.Throwable -> L8e
        L12:
            boolean r3 = r2.hasNext()     // Catch: java.lang.Throwable -> L8e
            if (r3 == 0) goto L50
            java.lang.Object r3 = r2.next()     // Catch: java.lang.Throwable -> L8e
            java.lang.Integer r3 = (java.lang.Integer) r3     // Catch: java.lang.Throwable -> L8e
            int r3 = r3.intValue()     // Catch: java.lang.Throwable -> L8e
            java.util.HashMap<java.lang.Integer, com.xiaopeng.app.xpDialogInfo> r4 = com.xiaopeng.server.app.xpActivityManagerService.sDialogRecord     // Catch: java.lang.Throwable -> L8e
            java.lang.Integer r5 = java.lang.Integer.valueOf(r3)     // Catch: java.lang.Throwable -> L8e
            java.lang.Object r4 = r4.get(r5)     // Catch: java.lang.Throwable -> L8e
            com.xiaopeng.app.xpDialogInfo r4 = (com.xiaopeng.app.xpDialogInfo) r4     // Catch: java.lang.Throwable -> L8e
            if (r4 == 0) goto L4f
            int r5 = r4.pid     // Catch: java.lang.Throwable -> L8e
            if (r5 != r8) goto L4f
            int r5 = r4.uid     // Catch: java.lang.Throwable -> L8e
            if (r5 != r9) goto L4f
            if (r10 != 0) goto L45
            boolean r5 = r4.isSystemDialog()     // Catch: java.lang.Throwable -> L8e
            if (r5 != 0) goto L43
            if (r11 != 0) goto L43
            goto L45
        L43:
            r5 = 0
            goto L46
        L45:
            r5 = 1
        L46:
            if (r5 == 0) goto L4f
            java.lang.Integer r6 = java.lang.Integer.valueOf(r3)     // Catch: java.lang.Throwable -> L8e
            r1.add(r6)     // Catch: java.lang.Throwable -> L8e
        L4f:
            goto L12
        L50:
            boolean r2 = r1.isEmpty()     // Catch: java.lang.Throwable -> L8e
            if (r2 == 0) goto L58
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L8e
            return
        L58:
            java.util.Iterator r2 = r1.iterator()     // Catch: java.lang.Throwable -> L8e
        L5c:
            boolean r3 = r2.hasNext()     // Catch: java.lang.Throwable -> L8e
            if (r3 == 0) goto L8c
            java.lang.Object r3 = r2.next()     // Catch: java.lang.Throwable -> L8e
            java.lang.Integer r3 = (java.lang.Integer) r3     // Catch: java.lang.Throwable -> L8e
            java.util.HashMap<java.lang.Integer, com.xiaopeng.app.xpDialogInfo> r4 = com.xiaopeng.server.app.xpActivityManagerService.sDialogRecord     // Catch: java.lang.Throwable -> L8e
            boolean r4 = r4.containsKey(r3)     // Catch: java.lang.Throwable -> L8e
            if (r4 == 0) goto L8b
            java.util.HashMap<java.lang.Integer, com.xiaopeng.app.xpDialogInfo> r4 = com.xiaopeng.server.app.xpActivityManagerService.sDialogRecord     // Catch: java.lang.Throwable -> L8e
            java.lang.Object r4 = r4.get(r3)     // Catch: java.lang.Throwable -> L8e
            com.xiaopeng.app.xpDialogInfo r4 = (com.xiaopeng.app.xpDialogInfo) r4     // Catch: java.lang.Throwable -> L8e
            com.xiaopeng.app.xpDialogInfo r4 = com.xiaopeng.app.xpDialogInfo.clone(r4)     // Catch: java.lang.Throwable -> L8e
            java.util.HashMap<java.lang.Integer, com.xiaopeng.app.xpDialogInfo> r5 = com.xiaopeng.server.app.xpActivityManagerService.sDialogRecord     // Catch: java.lang.Throwable -> L8e
            r5.remove(r3)     // Catch: java.lang.Throwable -> L8e
            com.xiaopeng.server.app.xpActivityManagerService$WorkHandler r5 = r7.mHandler     // Catch: java.lang.Throwable -> L8e
            com.xiaopeng.server.app.-$$Lambda$xpActivityManagerService$1adJip2drA40PGVo657Fjs9mPhg r6 = new com.xiaopeng.server.app.-$$Lambda$xpActivityManagerService$1adJip2drA40PGVo657Fjs9mPhg     // Catch: java.lang.Throwable -> L8e
            r6.<init>()     // Catch: java.lang.Throwable -> L8e
            r5.post(r6)     // Catch: java.lang.Throwable -> L8e
        L8b:
            goto L5c
        L8c:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L8e
            return
        L8e:
            r1 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L8e
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.app.xpActivityManagerService.onDialogProcessChanged(int, int, boolean, boolean):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: sendDialogChangedBroadcast */
    public void lambda$setDialogRecorder$2$xpActivityManagerService(xpDialogInfo info) {
        if (info != null) {
            xpDialogInfo top = getDialogRecorder();
            xpDialogIntentInfo.sendBroadcast(this.mContext, info, top);
            StringBuilder sb = new StringBuilder();
            sb.append("sendDialogChangedBroadcast info=");
            sb.append(info.toString());
            sb.append(" top=");
            sb.append(top != null ? top.toString() : "");
            xpLogger.i(TAG, sb.toString());
        }
    }

    public void setHomeState(ComponentName component, int state) {
        synchronized (this.mHomeManager) {
            if (this.mHomeManager != null) {
                this.mHomeManager.setHomeState(component, state);
            }
        }
    }

    public void setHomeState(WindowManager.LayoutParams lp, int state) {
        synchronized (this.mHomeManager) {
            if (this.mHomeManager != null) {
                this.mHomeManager.setHomeState(lp, state);
            }
        }
    }

    public void grantFolderPermission(String path) {
        String[] strArr;
        if (TextUtils.isEmpty(path)) {
            return;
        }
        boolean allowGrant = false;
        for (String folder : GRANT_FOLDERS) {
            if (path.startsWith(folder)) {
                allowGrant = true;
            }
        }
        if (allowGrant) {
            grantFolder(new File(path));
        }
        xpLogger.i(TAG, "grantFolderPermission path=" + path + " allowGrant=" + allowGrant);
    }

    private void grantFolder(File root) {
        File[] listFiles;
        if (root != null) {
            try {
                root.setExecutable(true);
                root.setReadable(true);
                root.setWritable(true);
                root.setExecutable(true, false);
                root.setReadable(true, false);
                root.setWritable(true, false);
                if (root.isDirectory()) {
                    for (File file : root.listFiles()) {
                        grantFolder(file);
                    }
                }
            } catch (Exception e) {
                xpLogger.i(TAG, "grantFolder e=" + e);
            }
        }
    }

    private static void setActivityLevel(ComponentName component) {
        if (component == null) {
            return;
        }
        try {
            String packageName = component.getPackageName();
            String className = component.getClassName();
            int sharedId = xpWindowManager.getWindowManager().getSharedId(packageName);
            int screenId = SharedDisplayManager.findScreenId(sharedId);
            int lastLevel = SharedDisplayContainer.SharedDisplayImpl.getActivityLevel(screenId);
            int nextLevel = ActivityInfoManager.getActivityLevel(xpActivityInfo.create(component));
            String noLimitedClassName = sNoLimitedHistory.getOrDefault(Integer.valueOf(sharedId), "");
            boolean skip = lastLevel > 0 && TextUtils.equals(className, noLimitedClassName);
            if (!skip) {
                SharedDisplayContainer.SharedDisplayImpl.setActivityLevel(screenId, nextLevel, component);
            }
        } catch (Exception e) {
        }
    }

    public static boolean preventAppFromActivityLevel(final Context context, final ActivityInfo ai, Intent intent) {
        ComponentName component;
        if (ai == null || intent == null || context == null || (component = ai.getComponentName()) == null) {
            return false;
        }
        int flags = intent.getFlags();
        int sharedId = intent.getSharedId();
        boolean noLimit = (flags & 1024) == 1024;
        int callingActivityLevel = ActivityInfoManager.getActivityLevel(xpActivityInfo.create(component));
        int currentActivityLevel = SharedDisplayContainer.SharedDisplayImpl.getActivityLevel(SharedDisplayManager.findScreenId(sharedId));
        if (callingActivityLevel > 0) {
            sDialogEvent = "fromTopping";
        }
        boolean prevent = callingActivityLevel < currentActivityLevel;
        if (noLimit) {
            prevent = false;
        }
        sNoLimitedHistory.put(Integer.valueOf(sharedId), noLimit ? component.getClassName() : "");
        if (prevent) {
            if (getTopComponent(SharedDisplayManager.findScreenId(sharedId)) == null) {
                prevent = false;
            }
            boolean isSecondaryHome = isSecondaryHome(context, ai.getComponentName());
            if (isSecondaryHome) {
                prevent = false;
            }
        }
        if (prevent) {
            new Thread(new Runnable() { // from class: com.xiaopeng.server.app.-$$Lambda$xpActivityManagerService$8681WD2eJ2T4aLvwZR91vqYMSEc
                @Override // java.lang.Runnable
                public final void run() {
                    xpWindowManager.triggerWindowErrorEvent(context, ai);
                }
            }).start();
        }
        return prevent;
    }

    public static boolean abortActivityStarterIfNeed(ActivityInfo ai, Intent intent, final Context context) {
        if (ai == null || intent == null || context == null) {
            return false;
        }
        final int sharedId = intent.getSharedId();
        Handler handler = new Handler(Looper.getMainLooper());
        Bundle extras = new Bundle();
        SharedDisplayFactory.addBundle(extras, "ai", ai);
        SharedDisplayFactory.addBundle(extras, "intent", intent);
        SharedDisplayFactory.addBundle(extras, "packageName", ai.packageName);
        SharedDisplayFactory.addBundle(extras, "sharedId", Integer.valueOf(sharedId));
        SharedDisplayFactory.addBundle(extras, "screenId", Integer.valueOf(SharedDisplayManager.findScreenId(sharedId)));
        int policy = AppTaskPolicy.get().getAppPolicy(context, 1, extras, null);
        if (policy == 0) {
            try {
                if (SharedDisplayManager.sharedValid(sharedId)) {
                    xpWindowManager.getWindowManager().setSharedId(ai.packageName, sharedId);
                }
                handler.post(new Runnable() { // from class: com.xiaopeng.server.app.-$$Lambda$xpActivityManagerService$CZJ5UlNql7bG6dPlsxlMEerqK9g
                    @Override // java.lang.Runnable
                    public final void run() {
                        SharedDisplayManager.enableScreenIfNeed(context, SharedDisplayManager.findScreenId(sharedId));
                    }
                });
            } catch (Exception e) {
            }
        }
        return policy == 1;
    }

    private static boolean isDefaultHome(Context context, ComponentName component) {
        if (component == null) {
            return false;
        }
        String packageName = component.getPackageName();
        String className = component.getClassName();
        return ActivityInfoManager.isDefaultHome(context, xpActivityInfo.create(packageName, className));
    }

    private static boolean isSecondaryHome(Context context, ComponentName component) {
        if (component == null) {
            return false;
        }
        String packageName = component.getPackageName();
        String className = component.getClassName();
        return ActivityInfoManager.isSecondaryHome(context, xpActivityInfo.create(packageName, className));
    }

    public static boolean isHighLevelActivity(ComponentName component) {
        return ActivityInfoManager.getActivityLevel(xpActivityInfo.create(component)) > 0;
    }

    public static ComponentName getTopComponent(Context context) {
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService("activity");
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && tasks.size() > 0) {
                    return tasks.get(0).topActivity;
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static ComponentName getTopComponent(int screenId) {
        return sTopComponent.getOrDefault(Integer.valueOf(screenId), null);
    }

    public int getGearLevel() {
        Object value = ExternalManagerService.get(this.mContext).getValue(2, "getCarGearLevel", new Object[0]);
        return xpTextUtils.toInteger(value, 0).intValue();
    }

    public void onAppModeChanged(String packageName, xpPackageInfo info) {
        ExternalManagerService.get(this.mContext).setValue(2, "onAppModeChanged", packageName, info);
    }

    public void handleActivityModeChanged(String packageName) {
        xpPackageInfo info = xpPackageManagerService.get(this.mContext).getXpPackageInfo(packageName);
        if (this.mInfo == null && info == null) {
            return;
        }
        xpPackageInfo xppackageinfo = this.mInfo;
        if (xppackageinfo != null && info != null && xppackageinfo.executeMode == info.executeMode && this.mInfo.navigationKey == info.navigationKey && this.mInfo.requestAudioFocus == info.requestAudioFocus) {
            this.mInfo = info;
            return;
        }
        this.mInfo = info;
        onAppModeChanged(packageName, info);
    }

    public void registerProcessObserver() {
        try {
            ActivityManager.getService().registerProcessObserver(this.sProcessObserver);
        } catch (Exception e) {
            xpLogger.i(TAG, "registerProcessObserver e=" + e);
        }
    }

    public void unregisterProcessObserver() {
        try {
            ActivityManager.getService().unregisterProcessObserver(this.sProcessObserver);
        } catch (Exception e) {
            xpLogger.i(TAG, "unregisterProcessObserver e=" + e);
        }
    }

    /* loaded from: classes2.dex */
    public static final class xpActivityRecord {
        public boolean fullscreen;
        public Intent intent;
        public xpActivityIntentInfo intentInfo;
        public ComponentName realActivity;

        public xpActivityRecord(ComponentName realActivity) {
            this.realActivity = realActivity;
        }

        public xpActivityRecord(ComponentName realActivity, Intent intent) {
            this.realActivity = realActivity;
            this.intent = intent;
        }
    }

    /* loaded from: classes2.dex */
    public static final class xpDialogIntentInfo {
        private static final String ACTION_DIALOG_CHANGED = "com.xiaopeng.intent.action.XUI_DIALOG_CHANGED";
        private static final String EXTRA_CHANGED_DIALOG = "android.intent.extra.current.DIALOG_INFO";
        private static final String EXTRA_DIM_AMOUNT = "android.intent.extra.DIM_AMOUNT";
        private static final String EXTRA_FOCUS = "android.intent.extra.current.FOCUS";
        private static final String EXTRA_FULLSCREEN = "android.intent.extra.current.FULLSCREEN";
        private static final String EXTRA_HAS_VISIBLE_DIALOG = "android.intent.extra.HAS_VISIBLE_DIALOG";
        private static final String EXTRA_ID = "android.intent.extra.current.ID";
        private static final String EXTRA_PACKAGE_NAME = "android.intent.extra.current.PACKAGE_NAME";
        private static final String EXTRA_TOPPING_DIALOG = "android.intent.extra.topping.DIALOG_INFO";
        private static final String EXTRA_TYPE = "android.intent.extra.current.TYPE";

        public static void sendBroadcast(Context context, xpDialogInfo changed, xpDialogInfo topping) {
            if (context != null && changed != null) {
                Intent intent = new Intent(ACTION_DIALOG_CHANGED);
                intent.addFlags(1344274432);
                intent.putExtra(EXTRA_ID, changed.hashCode);
                intent.putExtra(EXTRA_TYPE, changed.windowType);
                intent.putExtra(EXTRA_FOCUS, changed.hasFocus);
                intent.putExtra(EXTRA_DIM_AMOUNT, changed.dimAmount);
                intent.putExtra(EXTRA_FULLSCREEN, changed.fullscreen);
                intent.putExtra(EXTRA_PACKAGE_NAME, changed.packageName);
                intent.putExtra(EXTRA_HAS_VISIBLE_DIALOG, topping != null ? topping.topVisible() : false);
                if (topping != null) {
                    intent.putExtra(EXTRA_TOPPING_DIALOG, (Parcelable) topping);
                }
                intent.putExtra(EXTRA_CHANGED_DIALOG, (Parcelable) changed);
                context.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        }
    }

    /* loaded from: classes2.dex */
    public static final class xpActivityIntentInfo {
        private static final String ACTION_ACTIVITY_CHANGED = "com.xiaopeng.intent.action.ACTIVITY_CHANGED";
        private static final String EXTRA_COMPONENT = "android.intent.extra.COMPONENT";
        private static final String EXTRA_DATA = "android.intent.extra.DATA";
        private static final String EXTRA_DIM_AMOUNT = "android.intent.extra.DIM_AMOUNT";
        private static final String EXTRA_FLAGS = "android.intent.extra.FLAGS";
        private static final String EXTRA_FULLSCREEN = "android.intent.extra.FULLSCREEN";
        private static final String EXTRA_MINI_PROGRAM = "android.intent.extra.mini.PROGRAM";
        private static final String EXTRA_TOKEN = "android.intent.extra.TOKEN";
        private static final String EXTRA_WINDOW_LEVEL = "android.intent.extra.WINDOW_LEVEL";
        public String component;
        public String data;
        public float dimAmount;
        public int flags;
        public boolean fullscreen;
        public boolean miniProgram;
        public IBinder token;
        public int window;
        public int windowLevel;

        public void sendBroadcast(Context context) {
            Intent intent = new Intent(ACTION_ACTIVITY_CHANGED);
            intent.addFlags(1344274432);
            intent.putExtra(EXTRA_TOKEN, this.token);
            intent.putExtra(EXTRA_DATA, this.data);
            intent.putExtra(EXTRA_FLAGS, this.flags);
            intent.putExtra(EXTRA_COMPONENT, this.component);
            intent.putExtra(EXTRA_FULLSCREEN, this.fullscreen);
            intent.putExtra(EXTRA_DIM_AMOUNT, this.dimAmount);
            intent.putExtra(EXTRA_WINDOW_LEVEL, this.windowLevel);
            intent.putExtra(EXTRA_MINI_PROGRAM, this.miniProgram);
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putString("data", this.data);
            bundle.putInt("window", this.window);
            bundle.putInt(xpInputManagerService.InputPolicyKey.KEY_FLAGS, this.flags);
            bundle.putString("component", this.component);
            bundle.putBoolean("fullscreen", this.fullscreen);
            bundle.putFloat("dimAmount", this.dimAmount);
            bundle.putBinder("token", this.token);
            return bundle;
        }

        public String toString() {
            StringBuffer buffer = new StringBuffer("");
            buffer.append("ActivityIntent");
            buffer.append(" window=" + this.window);
            buffer.append(" flags=" + Integer.toHexString(this.flags));
            buffer.append(" component=" + this.component);
            buffer.append(" fullscreen=" + this.fullscreen);
            buffer.append(" dimAmount=" + this.dimAmount);
            buffer.append(" windowLevel=" + this.windowLevel);
            buffer.append(" miniProgram=" + this.miniProgram);
            return buffer.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class WorkHandler extends Handler {
        public static final int MSG_ACTIVITY_CHANGED = 100;
        public static final int MSG_ACTIVITY_MODE_CHANGED = 101;

        @SuppressLint({"NewApi"})
        public WorkHandler(Looper looper) {
            super(looper, null);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int i = msg.what;
            if (i == 100) {
                xpActivityManagerService.this.handleActivityChanged(msg);
            } else if (i == 101) {
                String packageName = xpTextUtils.toString(msg.obj);
                xpActivityManagerService.this.handleActivityModeChanged(packageName);
            }
        }
    }
}
