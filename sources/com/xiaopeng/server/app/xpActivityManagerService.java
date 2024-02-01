package com.xiaopeng.server.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
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
import com.xiaopeng.view.xpWindowManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class xpActivityManagerService {
    private static xpActivityIntentInfo sTopActivityInfo;
    private final Context mContext;
    private final WorkHandler mHandler;
    private xpHomeManagerService mHomeManager;
    private xpPackageInfo mInfo;
    private static final String TAG = "xpActivityManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final String[] GRANT_FOLDERS = {"/data/Log"};
    private static ComponentName sLastComponent = null;
    private static ComponentName sCurrentComponent = null;
    private static ComponentName sSubHighLevelComponent = null;
    private static boolean sSubHighLevelActivity = false;
    private static boolean sTopActivityFullscreen = false;
    private static int sDialogEvent = 2;
    private static ArrayList<xpActivityRecord> sActivityRecordHistory = new ArrayList<>();
    private static HashMap<Integer, xpDialogInfo> sDialogRecord = new HashMap<>();
    private static xpActivityManagerService sService = null;
    private IProcessObserver.Stub sProcessObserver = new IProcessObserver.Stub() { // from class: com.xiaopeng.server.app.xpActivityManagerService.5
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

    public void handleActivityChanged(Bundle bundle) {
        xpActivityRecord r = convertActivityRecord(bundle);
        if (r != null && r.intentInfo != null && r.intentInfo.component != null) {
            try {
                Message msg = new Message();
                msg.what = 100;
                Bundle data = new Bundle(bundle);
                msg.setData(data);
                this.mHandler.sendMessageDelayed(msg, ActivityManager.isUserAMonkey() ? 500L : 200L);
                updateActivityRecordHistory(r);
            } catch (Exception e) {
                xpLogger.log(TAG, "handleActivityChanged e=" + e);
            }
        }
    }

    public void handleActivityChangedLocked(Message msg) {
        xpActivityRecord ar;
        if (msg != null) {
            try {
                xpActivityIntentInfo info = new xpActivityIntentInfo();
                Bundle bundle = msg.getData();
                if (bundle != null) {
                    String component = bundle.getString("component", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    if (!TextUtils.isEmpty(component)) {
                        ComponentName componentName = ComponentName.unflattenFromString(component);
                        String packageName = componentName != null ? componentName.getPackageName() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                        boolean match = false;
                        int flags = bundle.getInt(xpInputManagerService.InputPolicyKey.KEY_FLAGS, 0);
                        boolean toppingActivity = (flags & 16) == 16;
                        info.component = component;
                        info.flags = flags;
                        info.token = bundle.getBinder("token");
                        info.data = bundle.getString("data", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                        info.window = bundle.getInt("window", 0);
                        info.dimAmount = bundle.getFloat("dimAmount", 0.0f);
                        info.fullscreen = bundle.getBoolean("fullscreen", false);
                        info.windowLevel = toppingActivity ? 1 : 0;
                        info.hasMiniProgram = MiniProgramManager.isMiniProgramPackage(packageName);
                        ComponentName currentTopComponent = getTopComponent(this.mContext);
                        String currentTopComponentName = currentTopComponent != null ? currentTopComponent.flattenToString() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                        if (!TextUtils.isEmpty(currentTopComponentName) && currentTopComponentName.equals(component)) {
                            match = true;
                        }
                        if (xpActivityManager.isComponentValid(currentTopComponent) && !match && (ar = getActivityRecordFromHistory(ComponentName.unflattenFromString(currentTopComponentName))) != null && ar.intentInfo != null && ar.intentInfo.component != null) {
                            final xpActivityIntentInfo i = ar.intentInfo;
                            this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.app.xpActivityManagerService.1
                                @Override // java.lang.Runnable
                                public void run() {
                                    xpActivityManagerService.this.handleActivityChanged(i.toBundle());
                                }
                            });
                        }
                        sTopActivityFullscreen = info.fullscreen;
                        StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                        buffer.append("handleActivityChangedLocked ");
                        buffer.append(" component=" + component);
                        buffer.append(" " + info.toString());
                        buffer.append(" currentTopComponent=" + currentTopComponent);
                        xpLogger.i(TAG, buffer.toString());
                        setHomeResumedActivity(componentName, info.window, info.flags);
                        setHomeState(componentName, info.window == 5 ? 3 : 4);
                        xpBootManagerPolicy.get(this.mContext).onActivityChanged(componentName);
                        sendActivityChangedBroadcast(info);
                        updateResumedActivity(componentName);
                        WorkHandler workHandler = this.mHandler;
                        WorkHandler workHandler2 = this.mHandler;
                        WorkHandler workHandler3 = this.mHandler;
                        workHandler.sendMessage(workHandler2.obtainMessage(101, packageName));
                    }
                }
                sTopActivityInfo = info;
            } catch (Exception e) {
                xpLogger.e(TAG, "handleActivityChangedLocked e=" + e);
            }
        }
    }

    public static String getTopWindow() {
        if (sTopActivityInfo != null) {
            return createTopActivityContent(sTopActivityInfo.component, sTopActivityInfo.flags);
        }
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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

    private static String createTopActivityContent(String component, int flags) {
        try {
            JSONArray array = new JSONArray();
            JSONObject content = new JSONObject();
            content.put("screenId", 0);
            content.put("component", component);
            content.put(xpInputManagerService.InputPolicyKey.KEY_FLAGS, flags);
            JSONObject object = new JSONObject(content.toString());
            array.put(object);
            return array.toString();
        } catch (Exception e) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
    }

    private void updateResumedActivity(ComponentName component) {
        if (component != null) {
            boolean secondaryHome = ActivityInfoManager.isSecondaryHome(this.mContext, xpActivityInfo.create(component.getPackageName(), component.getClassName()));
            if (secondaryHome) {
                return;
            }
        }
        sLastComponent = sCurrentComponent != null ? sCurrentComponent.clone() : null;
        sCurrentComponent = component != null ? component.clone() : null;
        setSubHighLevelActivity(false, component);
        setDialogEvent(this.mContext, sDialogEvent);
        sDialogEvent = 2;
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
                try {
                    Iterator<xpActivityRecord> it = sActivityRecordHistory.iterator();
                    while (it.hasNext()) {
                        xpActivityRecord r = it.next();
                        if (r != null && cn.equals(r.realActivity)) {
                            return r;
                        }
                    }
                } catch (Throwable th) {
                    throw th;
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
                String var = bundle.getString("component", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                ComponentName component = ComponentName.unflattenFromString(var);
                if (component != null) {
                    xpActivityRecord r = new xpActivityRecord(component);
                    xpActivityIntentInfo info = new xpActivityIntentInfo();
                    info.component = var;
                    info.token = bundle.getBinder("token");
                    info.data = bundle.getString("data", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:73:0x0145
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public java.util.List<java.lang.String> getSpeechObserver() {
        /*
            Method dump skipped, instructions count: 364
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.app.xpActivityManagerService.getSpeechObserver():java.util.List");
    }

    public void dismissDialog(int type) {
        switch (type) {
            case 0:
                setDialogEvent(this.mContext, 1);
                return;
            case 1:
                setDialogEvent(this.mContext, 8);
                return;
            case 2:
                setDialogEvent(this.mContext, 16);
                return;
            default:
                return;
        }
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
            if (!sDialogRecord.isEmpty()) {
                return new ArrayList(sDialogRecord.values());
            }
            return null;
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
        this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.app.xpActivityManagerService.2
            @Override // java.lang.Runnable
            public void run() {
                xpActivityManagerService.this.sendDialogChangedBroadcast(info);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onDialogProcessChanged(int pid, int uid, boolean died, boolean hasForegroundActivities) {
        synchronized (sDialogRecord) {
            List<Integer> list = new ArrayList<>();
            for (Integer num : sDialogRecord.keySet()) {
                int hashcode = num.intValue();
                xpDialogInfo info = sDialogRecord.get(Integer.valueOf(hashcode));
                if (info != null && info.pid == pid && info.uid == uid) {
                    if (died) {
                        list.add(Integer.valueOf(hashcode));
                    } else if (!hasForegroundActivities) {
                        info.foreground = false;
                    }
                }
            }
            if (list.size() > 0) {
                for (Integer hashcode2 : list) {
                    if (sDialogRecord.containsKey(hashcode2)) {
                        final xpDialogInfo di = xpDialogInfo.clone(sDialogRecord.get(hashcode2));
                        sDialogRecord.remove(hashcode2);
                        this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.app.xpActivityManagerService.3
                            @Override // java.lang.Runnable
                            public void run() {
                                xpActivityManagerService.this.sendDialogChangedBroadcast(di);
                            }
                        });
                    }
                }
            }
        }
    }

    private void setDialogEvent(Context context, int event) {
        int event2;
        long origId = Binder.clearCallingIdentity();
        try {
            int value = Settings.Secure.getInt(context.getContentResolver(), "key_dialog_event", 0);
            if ((value & Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                event2 = event & Integer.MAX_VALUE;
            } else {
                event2 = event | Integer.MIN_VALUE;
            }
            Settings.Secure.putInt(context.getContentResolver(), "key_dialog_event", event2);
        } catch (Exception e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(origId);
            throw th;
        }
        Binder.restoreCallingIdentity(origId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendDialogChangedBroadcast(xpDialogInfo info) {
        if (info != null) {
            xpDialogInfo top = getDialogRecorder();
            xpDialogIntentInfo.sendBroadcast(this.mContext, info, top);
            StringBuilder sb = new StringBuilder();
            sb.append("sendDialogChangedBroadcast info=");
            sb.append(info.toString());
            sb.append(" top=");
            sb.append(top != null ? top.toString() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            xpLogger.i(TAG, sb.toString());
        }
    }

    public void setHomeState(ComponentName component, int state) {
        synchronized (this.mHomeManager) {
            if (this.mHomeManager != null) {
                this.mHomeManager.setState(component, state);
            }
        }
    }

    private void setHomeResumedActivity(ComponentName component, int window, int flags) {
        synchronized (this.mHomeManager) {
            if (this.mHomeManager != null) {
                this.mHomeManager.setResumedActivity(component, window, flags);
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

    private static boolean isSubHighLevelActivity() {
        return sSubHighLevelActivity;
    }

    private static void setSubHighLevelActivity(boolean highLevelActivity, ComponentName component) {
        if (highLevelActivity) {
            sSubHighLevelActivity = true;
            sSubHighLevelComponent = component;
        } else if (xpActivityManager.isComponentValid(component) && !component.equals(sSubHighLevelComponent)) {
            sSubHighLevelActivity = false;
            sSubHighLevelComponent = null;
        }
    }

    private static void resetRunningComponentWhenChanged() {
        try {
            IActivityManager am = xpActivityManager.getActivityManager();
            List<ActivityManager.RunningTaskInfo> tasks = am.getTasks(1);
            StringBuffer buffer = new StringBuffer();
            buffer.append("resetRunningComponentWhenChanged ");
            if (tasks != null && tasks.size() > 0) {
                ComponentName cn = tasks.get(0).topActivity;
                buffer.append(" topActivity = " + cn);
                buffer.append(" currentComponent = " + sCurrentComponent);
                if (xpActivityManager.isComponentValid(cn) && !cn.equals(sCurrentComponent)) {
                    buffer.append(" reset for not equals");
                    sCurrentComponent = null;
                    sLastComponent = null;
                }
            } else {
                buffer.append(" reset for no tasks");
                sCurrentComponent = null;
                sLastComponent = null;
            }
            xpLogger.log(TAG, buffer.toString());
        } catch (Exception e) {
        }
    }

    public static boolean abortActivityStarterIfNeed(final ActivityInfo ai, int flags, final Context context) {
        boolean ret = true;
        int callingWindowLevel = 0;
        int currentWindowLevel = 0;
        int secondsWindowLevel = 0;
        boolean noLimit = (flags & 1024) == 1024;
        if (ai != null) {
            try {
                ComponentName lastComponent = getLastComponent();
                ComponentName currentComponent = getCurrentComponent();
                callingWindowLevel = ActivityInfoManager.getActivityLevel(xpActivityInfo.create(ai.getComponentName()));
                currentWindowLevel = ActivityInfoManager.getActivityLevel(xpActivityInfo.create(currentComponent));
                secondsWindowLevel = ActivityInfoManager.getActivityLevel(xpActivityInfo.create(lastComponent));
                boolean abort = xpBootManagerPolicy.get(context).abortActivityIfNeed(context, ai.getComponentName());
                if (abort) {
                    return false;
                }
            } catch (Exception e) {
            }
        }
        if (callingWindowLevel > 0) {
            sDialogEvent = 4;
        }
        if (currentWindowLevel > 0 && currentWindowLevel > callingWindowLevel) {
            ret = false;
            if (noLimit) {
                ret = true;
                setSubHighLevelActivity(true, ai.getComponentName());
            }
        }
        if (secondsWindowLevel > 0 && isSubHighLevelActivity() && secondsWindowLevel > callingWindowLevel) {
            ret = false;
            if (noLimit && currentWindowLevel > 0) {
                ret = true;
            }
        }
        if (!ret) {
            new Thread(new Runnable() { // from class: com.xiaopeng.server.app.xpActivityManagerService.4
                @Override // java.lang.Runnable
                public void run() {
                    xpWindowManager.triggerWindowErrorEvent(context, ai);
                }
            }).start();
        }
        if (!ret) {
            try {
                resetRunningComponentWhenChanged();
                if (getCurrentComponent() == null) {
                    ret = true;
                }
            } catch (Exception e2) {
            }
            ComponentName componentName = ai != null ? ai.getComponentName() : null;
            if (componentName != null) {
                boolean isSecondaryHome = ActivityInfoManager.isSecondaryHome(context, xpActivityInfo.create(componentName.getPackageName(), componentName.getClassName()));
                if (isSecondaryHome) {
                    ret = true;
                }
            }
        }
        boolean system = ActivityInfoManager.isSystemApplication(ai.applicationInfo.packageName);
        int checkAppStart = system ? 0 : checkAppStart(context, ai.applicationInfo.packageName);
        boolean ret2 = checkAppStart == 0 ? ret : false;
        StringBuffer buffer = new StringBuffer();
        buffer.append("abortActivityStarterIfNeed ");
        StringBuilder sb = new StringBuilder();
        sb.append(" componentName = ");
        sb.append(ai != null ? ai.getComponentName() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        buffer.append(sb.toString());
        buffer.append(" subHighLevel = " + isSubHighLevelActivity());
        buffer.append(" callingLevel = " + callingWindowLevel);
        buffer.append(" currentLevel = " + currentWindowLevel);
        buffer.append(" secondsLevel = " + secondsWindowLevel);
        buffer.append(" checkAppStart = " + checkAppStart);
        buffer.append(" ret = " + ret2);
        xpLogger.log(TAG, buffer.toString());
        return ret2;
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

    public static ComponentName getLastComponent() {
        return sLastComponent;
    }

    public static ComponentName getCurrentComponent() {
        return sCurrentComponent;
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
        if (this.mInfo != null && info != null && this.mInfo.executeMode == info.executeMode && this.mInfo.navigationKey == info.navigationKey && this.mInfo.requestAudioFocus == info.requestAudioFocus) {
            this.mInfo = info;
            return;
        }
        this.mInfo = info;
        onAppModeChanged(packageName, info);
    }

    public static int checkAppStart(Context context, String packageName) {
        Object value = ExternalManagerService.get(context).getValue(2, "checkAppStart", packageName);
        return xpTextUtils.toInteger(value, 0).intValue();
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

    /* loaded from: classes.dex */
    public static final class xpActivityRecord {
        public boolean fullscreen;
        public Intent intent;
        public xpActivityIntentInfo intentInfo;
        public ComponentName realActivity;

        public xpActivityRecord(ComponentName realActivity) {
            this.realActivity = realActivity;
        }
    }

    /* loaded from: classes.dex */
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
                if (changed != null) {
                    intent.putExtra(EXTRA_CHANGED_DIALOG, (Parcelable) changed);
                }
                context.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        }
    }

    /* loaded from: classes.dex */
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
        public boolean hasMiniProgram;
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
            intent.putExtra(EXTRA_MINI_PROGRAM, this.hasMiniProgram);
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
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            buffer.append("ActivityIntent");
            buffer.append(" window=" + this.window);
            buffer.append(" flags=" + Integer.toHexString(this.flags));
            buffer.append(" component=" + this.component);
            buffer.append(" fullscreen=" + this.fullscreen);
            buffer.append(" dimAmount=" + this.dimAmount);
            buffer.append(" windowLevel=" + this.windowLevel);
            buffer.append(" hasMiniProgram=" + this.hasMiniProgram);
            return buffer.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
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
            switch (msg.what) {
                case 100:
                    xpActivityManagerService.this.handleActivityChangedLocked(msg);
                    return;
                case 101:
                    String packageName = xpTextUtils.toString(msg.obj);
                    xpActivityManagerService.this.handleActivityModeChanged(packageName);
                    return;
                default:
                    return;
            }
        }
    }
}
