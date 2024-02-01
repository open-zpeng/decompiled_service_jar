package com.android.server.wm;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IProcessObserver;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.IWindow;
import android.view.IWindowManager;
import android.view.WindowManager;
import com.android.internal.view.IInputMethodManager;
import com.android.server.EventLogTags;
import com.android.server.am.AssistDataRequester;
import com.android.server.pm.PackageManagerService;
import com.android.server.wm.ActivityRecord;
import com.android.server.wm.ActivityStack;
import com.android.server.wm.SharedDisplayContainer;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.app.xpActivityManager;
import com.xiaopeng.app.xpDialogInfo;
import com.xiaopeng.app.xpPackageManager;
import com.xiaopeng.bi.BiDataManager;
import com.xiaopeng.input.xpInputManager;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.wm.WindowFrameController;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.ISharedDisplayListener;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.WindowFrameModel;
import com.xiaopeng.view.xpWindowManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class SharedDisplayContainer {
    private static final String ACTION_ANIM_TASK = "com.xiaopeng.intent.action.ANIM_TASK";
    private static final String ACTION_DIALOG_CHANGED = "com.xiaopeng.intent.action.XUI_DIALOG_CHANGED";
    private static final String ACTION_POWER_STATE_CHANGED = "com.xiaopeng.intent.action.XUI_POWER_STATE_CHANGED";
    private static final boolean DEBUG = false;
    public static final int MSG_ACTIVITY_CHANGED = 5;
    public static final int MSG_ACTIVITY_RESUMED = 3;
    public static final int MSG_ACTIVITY_STOPPED = 4;
    public static final int MSG_POSITION_CHANGED = 7;
    public static final int MSG_PROCESS_DIED = 8;
    public static final int MSG_TOP_ACTIVITY_CHANGED = 6;
    public static final int MSG_WINDOW_ADDED = 1;
    public static final int MSG_WINDOW_REMOVED = 2;
    private static final String TAG = "SharedDisplayContainer";
    private static volatile String sPrimaryHistoryComponent;
    private static volatile String sPrimaryTopComponent;
    private static volatile String sSecondaryHistoryComponent;
    private static volatile String sSecondaryTopComponent;
    private ActivityTaskManagerService mAtmService;
    private Context mContext;
    private WorkHandler mHandler;
    private SharedRecord mPrimaryTopActivity;
    private WindowFrameModel mPrimaryWindowModel;
    private SharedRecord mSecondaryTopActivity;
    private WindowFrameModel mSecondaryWindowModel;
    private SharedDisplayPolicy mSharedDisplayPolicy;
    private WindowManagerService mWinService;
    private static final ConcurrentHashMap<String, Integer> sSharedPackages = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> sVirtualPackages = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, SharedDevice> sSharedDevices = new ConcurrentHashMap<>();
    private static volatile String sHomePackageName = "";
    private static final ConcurrentHashMap<Integer, Boolean> sDialogHistory = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> sProcessHistory = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Integer> sModeHistory = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> sHomeTopActivity = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LaunchIntent> sLaunchIntentHistory = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, ActivityLevel> sActivityLevelHistory = new ConcurrentHashMap<>();
    private static volatile boolean sTaskResizing = false;
    private static volatile String sTaskResizingProperties = "";
    private static final RemoteCallbackList<ISharedDisplayListener> sCallbacks = new RemoteCallbackList<>();
    private final Object mLock = new Object();
    private final Handler mUiHandler = new Handler(Looper.getMainLooper()) { // from class: com.android.server.wm.SharedDisplayContainer.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                case 8:
                    SharedDisplayContainer.this.performEventChanged(msg.what, (SharedRecord) msg.obj);
                    return;
                default:
                    return;
            }
        }
    };
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.wm.SharedDisplayContainer.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            boolean z;
            xpLogger.i(SharedDisplayContainer.TAG, "onReceive intent=" + intent);
            String action = intent.getAction();
            int hashCode = action.hashCode();
            if (hashCode == -931550215) {
                if (action.equals(SharedDisplayContainer.ACTION_ANIM_TASK)) {
                    z = false;
                }
                z = true;
            } else if (hashCode != 940854282) {
                if (hashCode == 1048223135 && action.equals(SharedDisplayContainer.ACTION_POWER_STATE_CHANGED)) {
                    z = true;
                }
                z = true;
            } else {
                if (action.equals(SharedDisplayContainer.ACTION_DIALOG_CHANGED)) {
                    z = true;
                }
                z = true;
            }
            if (!z) {
                int sharedId = intent.getIntExtra("sharedId", -1);
                if (SharedDisplayManager.sharedValid(sharedId)) {
                    SharedDisplayContainer.this.animTask(sharedId, "");
                }
            } else if (z) {
                SharedDisplayContainer.this.onDialogChanged();
            } else if (z) {
                int igState = intent.getIntExtra("android.intent.extra.IG_STATE", -1);
                int powerState = intent.getIntExtra("android.intent.extra.POWER_STATE", -1);
                int bootReason = intent.getIntExtra("android.intent.extra.BOOT_REASON", -1);
                SharedDisplayContainer.this.onPowerStateChanged(igState, powerState, bootReason);
            }
        }
    };
    private IProcessObserver.Stub sProcessObserver = new IProcessObserver.Stub() { // from class: com.android.server.wm.SharedDisplayContainer.3
        public void onForegroundActivitiesChanged(int pid, int uid, boolean hasForegroundActivities) throws RemoteException {
        }

        public void onForegroundServicesChanged(int i, int i1, int i2) throws RemoteException {
        }

        public void onProcessDied(int pid, int uid) throws RemoteException {
            SharedDisplayContainer sharedDisplayContainer = SharedDisplayContainer.this;
            sharedDisplayContainer.onProcessDied(sharedDisplayContainer.mContext, pid, uid);
        }
    };
    private final Runnable mDialogHistoryRunnable = new Runnable() { // from class: com.android.server.wm.SharedDisplayContainer.4
        @Override // java.lang.Runnable
        public void run() {
            try {
                ConcurrentHashMap<Integer, Boolean> history = new ConcurrentHashMap<>();
                HashMap<Integer, xpDialogInfo> map = SharedDisplayFactory.getTopDialog(SharedDisplayContainer.this.mContext);
                if (map != null && !map.isEmpty()) {
                    for (Integer num : map.keySet()) {
                        int sharedId = num.intValue();
                        xpDialogInfo dialogInfo = map.get(Integer.valueOf(sharedId));
                        if (dialogInfo != null) {
                            history.put(Integer.valueOf(sharedId), true);
                        }
                    }
                }
                SharedDisplayContainer.sDialogHistory.clear();
                SharedDisplayContainer.sDialogHistory.putAll(history);
                xpLogger.i(SharedDisplayContainer.TAG, "onDialogChanged history=" + SharedDisplayContainer.sDialogHistory.toString());
            } catch (Exception e) {
            }
        }
    };
    private HandlerThread mHandlerThread = new HandlerThread(TAG, 10);

    /* loaded from: classes2.dex */
    public static final class ActivityLevel {
        public String className;
        public int level;
        public String packageName;
    }

    public SharedDisplayContainer() {
        this.mHandlerThread.start();
        this.mHandler = new WorkHandler(this.mHandlerThread.getLooper());
        this.mSharedDisplayPolicy = new SharedDisplayPolicy();
    }

    public void set(WindowManagerService winService, ActivityTaskManagerService atmService) {
        this.mWinService = winService;
        this.mAtmService = atmService;
        this.mContext = winService != null ? winService.mContext : null;
    }

    public void init() {
        initDevice();
        initBroadcastReceiver();
        initProcessObserver(true);
    }

    public void initDevice() {
        sSharedDevices.put(0, new SharedDevice(this.mUiHandler, 0));
        sSharedDevices.put(1, new SharedDevice(this.mUiHandler, 1));
    }

    private void initBroadcastReceiver() {
        if (this.mContext != null) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_ANIM_TASK);
                filter.addAction(ACTION_DIALOG_CHANGED);
                filter.addAction(ACTION_POWER_STATE_CHANGED);
                this.mContext.registerReceiverAsUser(this.mBroadcastReceiver, UserHandle.ALL, filter, null, null);
            } catch (Exception e) {
            }
        }
    }

    private void initProcessObserver(boolean register) {
        try {
            if (register) {
                ActivityManager.getService().registerProcessObserver(this.sProcessObserver);
            } else {
                ActivityManager.getService().unregisterProcessObserver(this.sProcessObserver);
            }
        } catch (Exception e) {
        }
    }

    public boolean allowResizeTask(int sharedId, Bundle callback) {
        return allowResizeTask(sharedId, this.mWinService.getVisibleWindows(1, sharedId), callback);
    }

    public void animTask(final int sharedId, final String extra) {
        if (SharedDisplayManager.enable() && !sTaskResizing) {
            final int to = generateNextSharedId(sharedId);
            Runnable runnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$Keh1LOysG-5_0Pjgu6UUcfVF9QU
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.this.lambda$animTask$3$SharedDisplayContainer(sharedId, extra, to, sharedId);
                }
            };
            this.mUiHandler.post(runnable);
        }
    }

    public /* synthetic */ void lambda$animTask$3$SharedDisplayContainer(final int sharedId, String extra, final int to, int from) {
        ArrayList<SharedRecord> windowRecorder;
        boolean resumed;
        ArrayList<SharedRecord> windowRecorder2 = this.mWinService.getSharedRecord();
        ArrayList<SharedRecord> activityRecorder = this.mAtmService.getSharedRecord();
        TaskPositioningController controller = this.mWinService.mTaskPositioningController;
        if (windowRecorder2 == null || windowRecorder2.isEmpty() || activityRecorder == null || activityRecorder.isEmpty() || controller == null) {
            return;
        }
        SharedWindow resizeWindow = null;
        SharedActivity resizeActivity = null;
        Iterator<SharedRecord> it = windowRecorder2.iterator();
        while (it.hasNext()) {
            SharedRecord wr = it.next();
            if (wr == null || wr.sharedId != sharedId) {
                windowRecorder = windowRecorder2;
            } else {
                Iterator<SharedRecord> it2 = activityRecorder.iterator();
                while (true) {
                    if (!it2.hasNext()) {
                        windowRecorder = windowRecorder2;
                        break;
                    }
                    SharedRecord ar = it2.next();
                    SharedWindow _wr = (SharedWindow) wr;
                    SharedActivity _ar = (SharedActivity) ar;
                    boolean visible = _ar != null && _ar.visible;
                    if (_ar != null) {
                        windowRecorder = windowRecorder2;
                        if (_ar.activityState == ActivityStack.ActivityState.RESUMED && _ar.appToken == _wr.appToken) {
                            resumed = true;
                            if (!resumed && visible) {
                                resizeWindow = _wr;
                                resizeActivity = _ar;
                                break;
                            }
                            windowRecorder2 = windowRecorder;
                        }
                    } else {
                        windowRecorder = windowRecorder2;
                    }
                    resumed = false;
                    if (!resumed) {
                    }
                    windowRecorder2 = windowRecorder;
                }
                if (resizeWindow != null && resizeActivity != null) {
                    break;
                }
            }
            windowRecorder2 = windowRecorder;
        }
        if (resizeWindow != null && resizeActivity != null) {
            Bundle callback = new Bundle();
            String packageName = resizeWindow.packageName;
            Bundle extras = new Bundle();
            SharedDisplayFactory.addBundle(extras, "packageName", packageName);
            SharedDisplayFactory.addBundle(extras, "extra", extra);
            SharedDisplayFactory.addBundle(extras, "sharedId", Integer.valueOf(sharedId));
            SharedDisplayFactory.addBundle(extras, "screenId", Integer.valueOf(SharedDisplayManager.findScreenId(sharedId)));
            int policy = AppTaskPolicy.get().getAppPolicy(this.mContext, 2, extras, callback);
            boolean ignoreWarning = ((Boolean) SharedDisplayFactory.getBundle(callback, "ignoreWarning", false)).booleanValue();
            boolean failFromRemote = ((Boolean) SharedDisplayFactory.getBundle(callback, "failFromRemote", false)).booleanValue();
            boolean enabled = policy == 0;
            if (enabled) {
                this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$x_6y4YITTYWTMJp1IUx1sEAMXck
                    @Override // java.lang.Runnable
                    public final void run() {
                        SharedDisplayContainer.this.lambda$animTask$0$SharedDisplayContainer(to);
                    }
                });
                HashMap<String, String> values = new HashMap<>();
                values.put("noHomeEvent", "1");
                values.put("excludePackageName", packageName);
                SharedDisplayImpl.setSharedEvent(this, 3, to, xpTextUtils.toJsonString(values));
                controller.startMovingTask(resizeWindow.client, -1.0f, -1.0f);
            } else if (!ignoreWarning) {
                boolean ignoreSoundEffect = ((Integer) SharedDisplayFactory.getBundle(callback, "appPolicy", -1)).intValue() == 14;
                if (!ignoreSoundEffect) {
                    SharedDisplayFactory.playSoundEffect(this.mContext, this.mHandler);
                }
                if (!failFromRemote) {
                    this.mUiHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$P7wZ8rqLv61q4XYsngK3N5jGURQ
                        @Override // java.lang.Runnable
                        public final void run() {
                            SharedDisplayContainer.this.lambda$animTask$1$SharedDisplayContainer(sharedId);
                        }
                    });
                }
            }
            final Bundle property = new Bundle();
            property.putInt("from", from);
            property.putInt("to", to);
            property.putBoolean("enabled", enabled);
            property.putString("extras", sTaskResizingProperties);
            property.putString("component", resizeWindow.getActivityComponent());
            sTaskResizingProperties = "";
            this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$XoC_-T9N6wyqsAJ7bvD6huQvFjM
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.this.lambda$animTask$2$SharedDisplayContainer(property);
                }
            });
            xpLogger.i(TAG, "animTask enabled=" + enabled + " policy=" + policy + " ignoreWarning=" + ignoreWarning + " failFromRemote=" + failFromRemote + " wr=" + resizeWindow.toString());
        }
    }

    public /* synthetic */ void lambda$animTask$0$SharedDisplayContainer(int to) {
        SharedDisplayManager.enableScreenIfNeed(this.mContext, to);
    }

    public /* synthetic */ void lambda$animTask$1$SharedDisplayContainer(int sharedId) {
        String text = this.mContext.getText(17040456).toString();
        showToast(this.mContext, text, 1, sharedId);
    }

    public /* synthetic */ void lambda$animTask$2$SharedDisplayContainer(Bundle property) {
        SharedDisplayImpl.setTaskResizingBiDataEvent(this.mContext, property);
    }

    public void onDialogChanged() {
        this.mHandler.removeCallbacks(this.mDialogHistoryRunnable);
        this.mHandler.postDelayed(this.mDialogHistoryRunnable, 100L);
    }

    public void onPowerStateChanged(int igState, int powerState, int bootReason) {
        if (igState == 0) {
            boolean isUnityUI = SharedDisplayManager.isUnityUI();
            String top = sPrimaryTopComponent;
            if (isUnityUI && !SharedDisplayManager.enable() && !TextUtils.isEmpty(top) && !SharedDisplayManager.isFactoryMode()) {
                try {
                    String home = getHomePackageName(this.mContext);
                    ComponentName component = ComponentName.unflattenFromString(top);
                    try {
                        StringBuilder sb = new StringBuilder();
                        try {
                            sb.append("onPowerStateChanged home=");
                            sb.append(home);
                            sb.append(" component=");
                            sb.append(component);
                            xpLogger.i(TAG, sb.toString());
                            if (!TextUtils.isEmpty(home) && component != null && !home.equals(component.getPackageName())) {
                                this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$fUJ8JH7Smomfejl1LXq7LpKGS44
                                    @Override // java.lang.Runnable
                                    public final void run() {
                                        SharedDisplayContainer.relaunchHome();
                                    }
                                });
                            }
                        } catch (Exception e) {
                        }
                    } catch (Exception e2) {
                    }
                } catch (Exception e3) {
                }
            }
        }
    }

    public void onLaunchParamsLoaded(final ArrayList<LaunchParams> list) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$KcbdSgLaV3mqlTdgxOPseIA1qdw
            @Override // java.lang.Runnable
            public final void run() {
                SharedDisplayContainer.lambda$onLaunchParamsLoaded$5(list);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$onLaunchParamsLoaded$5(ArrayList list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Iterator it = list.iterator();
        while (it.hasNext()) {
            LaunchParams lp = (LaunchParams) it.next();
            if (lp != null) {
                String packageName = lp.packageName;
                int sharedId = SharedDisplayManager.findSharedId(lp.bounds, lp.windowingMode);
                if (SharedDisplayManager.sharedValid(sharedId)) {
                    setSharedId(packageName, sharedId);
                }
            }
        }
    }

    public void onWindowAdded(WindowState win) {
        if (win != null && win.getDisplayId() == 0) {
            boolean isApplicationWindowType = xpWindowManager.isApplicationWindowType(win.mAttrs);
            if (isApplicationWindowType) {
                Handler handler = this.mUiHandler;
                handler.sendMessage(handler.obtainMessage(1, createRecorder(win)));
            }
        }
    }

    public void onWindowRemoved(WindowState win) {
        if (win != null && win.getDisplayId() == 0) {
            boolean isApplicationWindowType = xpWindowManager.isApplicationWindowType(win.mAttrs);
            if (isApplicationWindowType) {
                Handler handler = this.mUiHandler;
                handler.sendMessage(handler.obtainMessage(2, createRecorder(win)));
            }
        }
    }

    public void onActivityResumed(ActivityRecord r) {
        if (r == null) {
            return;
        }
        int pid = r.app != null ? r.app.getPid() : -1;
        Bundle extras = new Bundle();
        SharedDisplayFactory.addBundle(extras, "pid", Integer.valueOf(pid));
        SharedDisplayFactory.addBundle(extras, "packageName", r.packageName);
        refreshProcessChangedEvent(1, this.mContext, extras);
        if (r.getDisplayId() == 0 && !r.isActivityTypeHome()) {
            Handler handler = this.mUiHandler;
            handler.sendMessage(handler.obtainMessage(3, createRecorder(r)));
        }
    }

    public void onActivityStopped(ActivityRecord r) {
        if (r == null || r.getDisplayId() != 0 || r.isActivityTypeHome()) {
            return;
        }
        Handler handler = this.mUiHandler;
        handler.sendMessage(handler.obtainMessage(4, createRecorder(r)));
    }

    public void onActivityChanged(Bundle bundle) {
        if (bundle == null) {
            return;
        }
        try {
            String var = bundle.getString("component", "");
            ComponentName component = ComponentName.unflattenFromString(var);
            if (xpWindowManager.isComponentValid(component)) {
                IBinder token = bundle.getBinder("token");
                int flags = bundle.getInt(xpInputManagerService.InputPolicyKey.KEY_FLAGS, 0);
                int window = bundle.getInt("window", 0);
                boolean fullscreen = bundle.getBoolean("fullscreen", false);
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                LaunchIntent li = new LaunchIntent(component.getClassName(), flags, window, fullscreen);
                refreshLaunchIntentHistory(this.mHandler, component.getClassName(), li);
                if (r != null && r.getDisplayId() == 0) {
                    this.mUiHandler.sendMessage(this.mUiHandler.obtainMessage(5, createRecorder(r)));
                }
            }
        } catch (Exception e) {
        }
    }

    public void onTopActivityChanged(Bundle bundle) {
        if (bundle == null) {
            return;
        }
        try {
            String var = bundle.getString("component", "");
            ComponentName component = ComponentName.unflattenFromString(var);
            if (xpWindowManager.isComponentValid(component)) {
                IBinder token = bundle.getBinder("token");
                ActivityRecord r = ActivityRecord.forTokenLocked(token);
                if (r != null && r.getDisplayId() == 0) {
                    this.mUiHandler.sendMessage(this.mUiHandler.obtainMessage(6, createRecorder(r)));
                }
            }
        } catch (Exception e) {
        }
    }

    /* renamed from: onTopActivityChanged */
    public void lambda$performEventChanged$12$SharedDisplayContainer(SharedDevice primary, SharedDevice secondary) {
        SharedRecord primaryTop = primary != null ? primary.getTopActivity() : null;
        SharedRecord secondaryTop = secondary != null ? secondary.getTopActivity() : null;
        if (primaryTop != this.mPrimaryTopActivity || secondaryTop != this.mSecondaryTopActivity) {
            dispatchWindowChanged(primaryTop, secondaryTop);
        }
        sPrimaryHistoryComponent = sPrimaryTopComponent;
        sSecondaryHistoryComponent = sSecondaryTopComponent;
        this.mPrimaryTopActivity = primaryTop;
        this.mSecondaryTopActivity = secondaryTop;
        sPrimaryTopComponent = primaryTop != null ? primaryTop.getActivityComponent() : "";
        sSecondaryTopComponent = secondaryTop != null ? secondaryTop.getActivityComponent() : "";
        boolean primaryActivityChanged = topActivityChanged(sPrimaryTopComponent, sPrimaryHistoryComponent);
        boolean secondaryActivityChanged = topActivityChanged(sSecondaryTopComponent, sSecondaryHistoryComponent);
        if (primaryActivityChanged) {
            dispatchActivityChanged(0, primaryTop);
        }
        if (secondaryActivityChanged) {
            dispatchActivityChanged(1, secondaryTop);
        }
        boolean primaryPackageChanged = topPackageChanged(sPrimaryTopComponent, sPrimaryHistoryComponent);
        boolean secondaryPackageChanged = topPackageChanged(sSecondaryTopComponent, sSecondaryHistoryComponent);
        if (primaryPackageChanged || secondaryPackageChanged) {
            onTopPackageChanged(primaryPackageChanged, secondaryPackageChanged);
        }
        xpLogger.i(TAG, "onTopActivityChanged primaryPackageChanged=" + primaryPackageChanged + " secondaryPackageChanged=" + secondaryPackageChanged + " primaryActivityChanged=" + primaryActivityChanged + " secondaryActivityChanged=" + secondaryActivityChanged + " primaryTop=" + this.mPrimaryTopActivity + " secondaryTop=" + this.mSecondaryTopActivity + " primary=" + sPrimaryTopComponent + " secondary=" + sSecondaryTopComponent + " primaryHistory=" + sPrimaryHistoryComponent + " secondaryHistory=" + sSecondaryHistoryComponent);
    }

    public void onProcessDied(Context context, int pid, int uid) {
        String packageName = SharedDisplayImpl.getPackageName(pid);
        if (!TextUtils.isEmpty(packageName)) {
            Message msg = this.mUiHandler.obtainMessage(8, createRecorder(packageName));
            this.mUiHandler.sendMessageDelayed(msg, 500L);
        }
        Bundle extras = new Bundle();
        SharedDisplayFactory.addBundle(extras, "pid", Integer.valueOf(pid));
        refreshProcessChangedEvent(0, this.mContext, extras);
    }

    public void onPositionEventChanged(String packageName, int event, int from, int to, WindowState win, WindowHashMap map) {
        if (event == 1) {
            sTaskResizing = true;
            SystemProperties.set("xui.sys.shared.display.task.resizing", "1");
            SharedDisplayImpl.handlePositionChanged(packageName, event, from, to);
        } else if (event == 2) {
            sTaskResizing = false;
            SystemProperties.set("xui.sys.shared.display.task.resizing", "0");
            SharedDisplayImpl.handlePositionChanged(packageName, event, from, to);
            if (win != null && map != null && win.mAttrs != null) {
                WindowManager.LayoutParams lp = win.mAttrs;
                int sharedId = lp.sharedId;
                String pkg = lp.packageName;
                setSharedLp(win, lp, map);
                setSharedId(pkg, sharedId);
                Handler handler = this.mUiHandler;
                handler.sendMessage(handler.obtainMessage(7, createRecorder(win)));
            }
        }
    }

    public void onTopPackageChanged(boolean primaryChanged, boolean secondaryChanged) {
        if (!isPackageSettingsEmpty()) {
            refreshPackageSettings(this.mHandler, primaryChanged, secondaryChanged);
        }
    }

    public void performActivityChanged(Bundle extras) {
        onActivityChanged(extras);
    }

    public void performTopActivityChanged(Bundle extras) {
        onTopActivityChanged(extras);
    }

    public void dispatchWindowChanged(SharedRecord primaryTop, SharedRecord secondaryTop) {
        List<SharedDisplayData> list = new ArrayList<>();
        SharedDisplayData primaryData = createData(primaryTop);
        SharedDisplayData secondaryData = createData(secondaryTop);
        if (primaryData != null) {
            list.add(primaryData);
        }
        if (secondaryData != null) {
            list.add(secondaryData);
        }
        if (SharedDisplayManager.isUnityUI()) {
            if (primaryTop != null && (primaryTop instanceof SharedActivity)) {
                boolean isHome = ((SharedActivity) primaryTop).isHome();
                if (!isHome) {
                    SharedDisplayImpl.setSharedScreenEnabled(primaryTop.screenId, true);
                }
            }
            if (secondaryTop != null && (secondaryTop instanceof SharedActivity)) {
                boolean isHome2 = ((SharedActivity) secondaryTop).isHome();
                if (!isHome2) {
                    SharedDisplayImpl.setSharedScreenEnabled(secondaryTop.screenId, true);
                }
            }
        }
        SharedDisplayImpl.handleEventChanged(100, SharedDisplayData.toJson(list));
    }

    public void dispatchActivityChanged(int screenId, SharedRecord record) {
        String packageName = "";
        int sharedId = screenId;
        int intentFlags = 0;
        String component = "";
        if (record != null) {
            try {
                sharedId = record.sharedId;
                intentFlags = record.intentFlags;
                packageName = record.packageName;
                component = record.getActivityComponent();
            } catch (Exception e) {
                return;
            }
        }
        String className = getClassName(component);
        LaunchIntent li = getLaunchIntent(className);
        int privateFlags = intentFlags;
        if (li != null && li.privateFlags > 0) {
            privateFlags = li.privateFlags;
        }
        boolean fullscreen = false | xpWindowManager.isFullscreen((WindowManager.LayoutParams) null, privateFlags);
        boolean fullscreen2 = fullscreen | (li != null ? li.fullscreen : false);
        JSONObject object = new JSONObject();
        object.put("packageName", packageName);
        object.put("sharedId", sharedId);
        object.put("screenId", screenId);
        object.put("windowType", li != null ? li.windowType : 0);
        object.put("fullscreen", fullscreen2);
        object.put("privateFlags", privateFlags);
        object.put("component", component);
        object.put("when", SystemClock.elapsedRealtime());
        SharedDisplayImpl.handleActivityChanged(screenId, object.toString());
        SharedDisplayImpl.removeActivityLevel(screenId, TextUtils.isEmpty(component) ? null : ComponentName.unflattenFromString(component));
    }

    public static void setSharedLp(WindowState win, WindowManager.LayoutParams lp, WindowHashMap map) {
        if (win == null || map == null || lp == null || !SharedDisplayManager.enable()) {
            return;
        }
        try {
            if (win.mClient != null && win.mAttrs != null) {
                int sharedId = lp.sharedId;
                String packageName = lp.packageName;
                IBinder token = win.mClient.asBinder();
                WindowState w = map.get(token);
                boolean valid = SharedDisplayManager.sharedValid(sharedId);
                if (valid && w != null && w.mAttrs != null && !TextUtils.isEmpty(packageName)) {
                    w.mAttrs.sharedId = sharedId;
                    setSharedId(packageName, sharedId, map);
                }
            }
        } catch (Exception e) {
        }
    }

    public static void setSharedId(String packageName, int sharedId, WindowHashMap map) {
        if (SharedDisplayManager.enable()) {
            try {
                if (!TextUtils.isEmpty(packageName) && map != null) {
                    for (WindowState win : map.values()) {
                        if (win != null && win.mAttrs != null && packageName.equals(win.mAttrs.packageName) && !SharedDisplayManager.hasDynamicScreenFlags(win.mAttrs)) {
                            win.mAttrs.sharedId = sharedId;
                        }
                    }
                }
            } catch (Exception e) {
            }
        }
    }

    public static void setSharedId(String packageName, int sharedId) {
        if (SharedDisplayManager.enable() && !TextUtils.isEmpty(packageName) && SharedDisplayManager.sharedValid(sharedId)) {
            int _sharedId = sSharedPackages.getOrDefault(packageName, -1).intValue();
            if (sharedId != _sharedId) {
                sSharedPackages.put(packageName, Integer.valueOf(sharedId));
                SharedDisplayImpl.handleChanged(packageName, sharedId);
            }
        }
    }

    public static int getSharedId(ActivityRecord r) {
        if (r == null || TextUtils.isEmpty(r.packageName)) {
            return -1;
        }
        int sharedId = getSharedId(r.packageName);
        return sharedId;
    }

    public static int getSharedId(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return -1;
        }
        return sSharedPackages.getOrDefault(packageName, -1).intValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDesktopEvent(final int sharedId, String extras) {
        SharedDevice device;
        boolean noHomeEvent = "1".equals(xpTextUtils.getValue("noHomeEvent", extras, ""));
        String excludePackageName = (String) xpTextUtils.getValue("excludePackageName", extras, "");
        boolean cleanPrimary = false;
        boolean cleanSecondary = false;
        if (sharedId == -1) {
            cleanPrimary = SharedDisplayImpl.getActivityLevel(0) <= 0;
            cleanSecondary = SharedDisplayImpl.getActivityLevel(1) <= 0;
        } else if (sharedId == 0) {
            boolean cleanable = SharedDisplayImpl.getActivityLevel(0) <= 0;
            if (cleanable) {
                cleanPrimary = true;
            }
        } else if (sharedId == 1) {
            boolean cleanable2 = SharedDisplayImpl.getActivityLevel(1) <= 0;
            if (cleanable2) {
                cleanSecondary = true;
            }
        }
        List<SharedRecord> list = new ArrayList<>();
        if (cleanPrimary) {
            if (!SharedDisplayManager.isUnityUI() && !noHomeEvent) {
                xpInputManager.sendEvent(3);
            }
            SharedDevice device2 = sSharedDevices.getOrDefault(0, null);
            if (device2 != null && device2.activities != null && !device2.activities.isEmpty()) {
                list.addAll(device2.activities);
            }
        }
        if (cleanSecondary && (device = sSharedDevices.getOrDefault(1, null)) != null && device.activities != null && !device.activities.isEmpty()) {
            list.addAll(device.activities);
        }
        for (SharedRecord r : list) {
            if (r != null) {
                final String packageName = r.packageName;
                boolean isHome = false;
                if (r.type == 2) {
                    SharedActivity _r = (SharedActivity) r;
                    isHome = _r.isHome();
                }
                if (!isHome && !TextUtils.isEmpty(packageName) && !packageName.equals(excludePackageName)) {
                    this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$3OY6zUQIejt6up-yttKypTf4Ifg
                        @Override // java.lang.Runnable
                        public final void run() {
                            xpActivityManager.getActivityTaskManager().finishPackageActivity(packageName);
                        }
                    });
                }
            }
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$8p5TqN71ZY3Kj6vj29BxR1dzWy4
            @Override // java.lang.Runnable
            public final void run() {
                SharedDisplayContainer.this.lambda$handleDesktopEvent$7$SharedDisplayContainer(sharedId);
            }
        });
    }

    public /* synthetic */ void lambda$handleDesktopEvent$7$SharedDisplayContainer(int sharedId) {
        Bundle bundle = new Bundle();
        SharedDisplayFactory.addBundle(bundle, "topOnly", true);
        SharedDisplayFactory.addBundle(bundle, "screenId", Integer.valueOf(SharedDisplayManager.findScreenId(sharedId)));
        SharedDisplayFactory.dismissDialog(this.mContext, bundle);
        SharedDisplayFactory.setQuickPanelEvent(this.mContext, sharedId);
    }

    public void setWindowMap(String packageName, int sharedId) {
        WindowHashMap map = this.mWinService.mWindowMap;
        if (map == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        synchronized (this.mWinService.mWindowMap) {
            setSharedId(packageName, sharedId, map);
        }
    }

    public String getTopWindow() {
        ArrayList<WindowState> list;
        SharedDisplayData data;
        WindowManagerService windowManagerService = this.mWinService;
        if (windowManagerService == null || (list = windowManagerService.getVisibleWindows(-1, 0)) == null || list.isEmpty()) {
            return "";
        }
        SharedDisplayData topData = null;
        SharedDisplayData physicalData = null;
        SharedDisplayData primaryData = null;
        SharedDisplayData secondaryData = null;
        int size = list.size();
        for (int i = 0; i < size; i++) {
            WindowState win = list.get(i);
            if (win != null && win.mAttrs != null) {
                boolean isDialog = xpWindowManager.isDialogWindowType(win.mAttrs);
                boolean isApplication = xpWindowManager.isApplicationWindowType(win.mAttrs);
                if ((isDialog || isApplication) && (data = SharedDisplayData.createData(win)) != null) {
                    data.layer = i;
                    int sharedId = win.mAttrs.sharedId;
                    int screenId = SharedDisplayManager.findScreenId(sharedId);
                    if (topData == null) {
                        topData = SharedDisplayData.clone(data);
                    }
                    if (physicalData == null && data.physical) {
                        physicalData = SharedDisplayData.clone(data);
                    }
                    if (primaryData == null && screenId == 0) {
                        primaryData = SharedDisplayData.clone(data);
                    }
                    if (secondaryData == null && screenId == 1) {
                        secondaryData = SharedDisplayData.clone(data);
                    }
                }
            }
        }
        List<SharedDisplayData> data2 = new ArrayList<>();
        boolean isUnityUI = SharedDisplayManager.isUnityUI();
        boolean isSharedDevice = SharedDisplayManager.enable();
        if (isSharedDevice) {
            boolean hasPhysical = physicalData != null;
            if (hasPhysical) {
                if (primaryData == null) {
                    primaryData = SharedDisplayData.clone(physicalData, 0);
                }
                if (secondaryData == null) {
                    secondaryData = SharedDisplayData.clone(physicalData, 1);
                }
                if (primaryData.layer > physicalData.layer) {
                    primaryData = SharedDisplayData.clone(physicalData, 0);
                }
                if (secondaryData.layer > physicalData.layer) {
                    secondaryData = SharedDisplayData.clone(physicalData, 1);
                }
                physicalData.id = -1;
                physicalData.screenId = -1;
            }
            if (isUnityUI) {
                if (primaryData != null && primaryData.isHome()) {
                    String text = sHomeTopActivity.getOrDefault(0, "");
                    if (!TextUtils.isEmpty(text)) {
                        primaryData = SharedDisplayData.fromJson(text);
                    }
                }
                if (secondaryData != null && secondaryData.isHome()) {
                    String text2 = sHomeTopActivity.getOrDefault(1, "");
                    if (!TextUtils.isEmpty(text2)) {
                        secondaryData = SharedDisplayData.fromJson(text2);
                    }
                }
            }
            if (physicalData != null) {
                data2.add(physicalData);
            }
            if (primaryData != null) {
                data2.add(primaryData);
            }
            if (secondaryData != null) {
                data2.add(secondaryData);
            }
        } else if (topData != null) {
            if (isUnityUI && topData.isHome()) {
                String text3 = sHomeTopActivity.getOrDefault(0, "");
                if (!TextUtils.isEmpty(text3)) {
                    topData = SharedDisplayData.fromJson(text3);
                }
            }
            data2.add(topData);
        }
        return SharedDisplayData.toJson(data2);
    }

    public boolean isTopActivityRemoved(int screenId, SharedRecord removedRecord, Bundle extras) {
        if (removedRecord == null) {
            return false;
        }
        String topComponent = "";
        SharedRecord topRecord = null;
        if (screenId != 0) {
            if (screenId == 1) {
                topRecord = this.mSecondaryTopActivity;
                topComponent = sSecondaryTopComponent;
            }
        } else {
            topRecord = this.mPrimaryTopActivity;
            topComponent = sPrimaryTopComponent;
        }
        if (topRecord == null || TextUtils.isEmpty(topComponent)) {
            return false;
        }
        ComponentName topComponentName = ComponentName.unflattenFromString(topComponent);
        boolean processDied = ((Boolean) SharedDisplayFactory.getBundle(extras, "processDied", false)).booleanValue();
        boolean windowRemoved = ((Boolean) SharedDisplayFactory.getBundle(extras, "windowRemoved", false)).booleanValue();
        boolean topRemoved = false;
        if (processDied) {
            boolean topRemoved2 = false | TextUtils.equals(removedRecord.packageName, topRecord.packageName);
            topRemoved = topRemoved2 | TextUtils.equals(removedRecord.packageName, topComponentName.getPackageName());
        }
        if (windowRemoved) {
            topRemoved |= removedRecord.equalsActivity(topRecord);
        }
        if (!topRemoved) {
            return false;
        }
        return true;
    }

    public Rect getActivityBounds(String packageName, boolean fullscreen) {
        int sharedId = getSharedId(packageName);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        if (SharedDisplayManager.enable()) {
            lp.type = 10;
        } else {
            lp.type = fullscreen ? 6 : 1;
        }
        lp.packageName = packageName;
        lp.sharedId = sharedId;
        lp.flags = xpWindowManager.getWindowFlags(lp.flags, fullscreen, fullscreen, fullscreen, fullscreen, false);
        lp.systemUiVisibility = xpWindowManager.getSystemUiVisibility(lp.systemUiVisibility, fullscreen, fullscreen, fullscreen, fullscreen, false);
        WindowFrameModel model = WindowFrameController.getWindowFrame(lp);
        if (model != null && xpWindowManager.rectValid(model.applicationBounds)) {
            return new Rect(model.applicationBounds);
        }
        return null;
    }

    public Rect getActivityBounds(String packageName, int sharedId, boolean fullscreen) {
        WindowFrameModel model;
        int screenId = SharedDisplayManager.findScreenId(sharedId);
        if (screenId != 0) {
            if (screenId != 1) {
                model = null;
            } else {
                WindowFrameModel model2 = this.mSecondaryWindowModel;
                model = model2;
            }
        } else {
            WindowFrameModel model3 = this.mPrimaryWindowModel;
            model = model3;
        }
        if (model == null) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            if (SharedDisplayManager.enable()) {
                lp.type = 10;
            } else {
                lp.type = fullscreen ? 6 : 1;
            }
            lp.packageName = packageName;
            lp.sharedId = sharedId;
            lp.flags = xpWindowManager.getWindowFlags(lp.flags, fullscreen, fullscreen, fullscreen, fullscreen, false);
            lp.systemUiVisibility = xpWindowManager.getSystemUiVisibility(lp.systemUiVisibility, fullscreen, fullscreen, fullscreen, fullscreen, false);
            model = WindowFrameController.getWindowFrame(lp);
            if (screenId != 0) {
                if (screenId == 1) {
                    this.mSecondaryWindowModel = model;
                }
            } else {
                this.mPrimaryWindowModel = model;
            }
        }
        if (fullscreen) {
            if (model != null && xpWindowManager.rectValid(model.unrestrictedBounds)) {
                return new Rect(model.unrestrictedBounds);
            }
            return null;
        } else if (model != null && xpWindowManager.rectValid(model.applicationBounds)) {
            return new Rect(model.applicationBounds);
        } else {
            return null;
        }
    }

    public SharedDisplayData createData(SharedRecord record) {
        if (record == null || !(record instanceof SharedActivity)) {
            return null;
        }
        SharedActivity activity = (SharedActivity) record;
        if (activity.isHome()) {
            return null;
        }
        boolean fullscreen = xpWindowManager.isFullscreen((WindowManager.LayoutParams) null, record.intentFlags);
        Rect bounds = getActivityBounds(activity.packageName, activity.sharedId, fullscreen);
        SharedDisplayData data = new SharedDisplayData();
        data.id = record.sharedId;
        data.type = 10;
        data.mode = -1;
        data.state = 0;
        data.enable = SharedDisplayImpl.isSharedScreenEnabled(SharedDisplayManager.findScreenId(record.sharedId));
        data.resizing = sTaskResizing;
        data.fullscreen = fullscreen;
        data.packageName = record.packageName;
        if (bounds != null) {
            data.x = bounds.left;
            data.y = bounds.top;
            data.width = bounds.width();
            data.height = bounds.height();
        }
        return data;
    }

    public static int generateNextSharedId(int sharedId) {
        if (sharedId != 0) {
            if (sharedId != 1) {
                return sharedId;
            }
            return 0;
        }
        return 1;
    }

    public static int generateNextScreenId(int screenId) {
        if (screenId != 0) {
            if (screenId != 1) {
                return screenId;
            }
            return 0;
        }
        return 1;
    }

    public static Rect getBounds(WindowManager.LayoutParams lp) {
        if (lp != null) {
            Rect bounds = new Rect();
            WindowFrameController.get();
            WindowFrameModel model = WindowFrameController.getWindowFrame(lp);
            if (model != null && model.contentBounds != null) {
                bounds.set(model.contentBounds);
            }
            return bounds;
        }
        return null;
    }

    public static String getClassName(String component) {
        if (TextUtils.isEmpty(component)) {
            return "";
        }
        try {
            ComponentName componentName = ComponentName.unflattenFromString(component);
            return componentName != null ? componentName.getClassName() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static String getPackageName(String component) {
        ComponentName componentName;
        return (TextUtils.isEmpty(component) || (componentName = ComponentName.unflattenFromString(component)) == null) ? "" : componentName.getPackageName();
    }

    public static LaunchIntent getLaunchIntent(String className) {
        if (TextUtils.isEmpty(className)) {
            return null;
        }
        return sLaunchIntentHistory.getOrDefault(className, null);
    }

    public static void refreshLaunchIntentHistory(Handler handler, String className, LaunchIntent intent) {
        if (TextUtils.isEmpty(className) || intent == null) {
            return;
        }
        sLaunchIntentHistory.put(className, intent);
        int size = sLaunchIntentHistory.size();
        if (size >= 1000 && handler != null) {
            final Comparator<LaunchIntent> comparator = new Comparator<LaunchIntent>() { // from class: com.android.server.wm.SharedDisplayContainer.5
                @Override // java.util.Comparator
                public final int compare(LaunchIntent a, LaunchIntent b) {
                    if (a == null || b == null || a.lunchTime == b.lunchTime) {
                        return 0;
                    }
                    return a.lunchTime < b.lunchTime ? -1 : 1;
                }
            };
            handler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$0ImOPkzjAxl5v8x78tj7m_JlX9Y
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.lambda$refreshLaunchIntentHistory$8(comparator);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$refreshLaunchIntentHistory$8(Comparator comparator) {
        try {
            List<LaunchIntent> list = new ArrayList<>();
            list.addAll(sLaunchIntentHistory.values());
            list.sort(comparator);
            for (int i = 0; i < 500; i++) {
                LaunchIntent li = list.get(i);
                if (li != null && !TextUtils.isEmpty(li.className)) {
                    sLaunchIntentHistory.remove(li.className);
                }
            }
        } catch (Exception e) {
        }
    }

    public static void stopActivity(ActivityRecord r) {
        ActivityStack stack;
        if (r != null) {
            try {
                if (r.getState() != ActivityStack.ActivityState.STOPPED && (stack = r.getActivityStack()) != null) {
                    stack.stopActivityLocked(r);
                }
            } catch (Exception e) {
            }
        }
    }

    public static void pauseActivity(ActivityRecord r) {
        ActivityStack stack;
        if (r != null) {
            try {
                if (r.getState() != ActivityStack.ActivityState.PAUSED && (stack = r.getActivityStack()) != null) {
                    stack.pauseActivityLocked(r);
                }
            } catch (Exception e) {
            }
        }
    }

    public static void resumeActivity(ActivityRecord r) {
        ActivityStack stack;
        if (r != null) {
            try {
                if (r.getState() != ActivityStack.ActivityState.RESUMED && (stack = r.getActivityStack()) != null) {
                    stack.moveToFrontAndResumeStateIfNeeded(r, true, true, false, "sd:resumeActivity");
                }
            } catch (Exception e) {
            }
        }
    }

    private boolean hasActivityRecord(ActivityRecord r) {
        if (r != null) {
            int sharedId = getSharedId(r);
            int screenId = SharedDisplayManager.findScreenId(sharedId);
            SharedDevice device = sSharedDevices.getOrDefault(Integer.valueOf(screenId), null);
            if (device != null) {
                return device.hasActivityRecord(r.mAppWindowToken, r.getState());
            }
            return true;
        }
        return true;
    }

    private static boolean shouldUpdateSharedId(int msg) {
        switch (msg) {
            case 1:
            case 3:
            case 6:
            case 7:
                return true;
            case 2:
            case 4:
            case 8:
                return false;
            case 5:
            default:
                return true;
        }
    }

    private static boolean allowResizeTask(int sharedId, ArrayList<WindowState> output, Bundle callback) {
        if (!SharedDisplayManager.sharedValid(sharedId) || output == null || output.isEmpty()) {
            return false;
        }
        Iterator<WindowState> it = output.iterator();
        while (it.hasNext()) {
            WindowState win = it.next();
            if (win != null && win.mAttrs != null) {
                WindowManager.LayoutParams lp = win.mAttrs;
                boolean isAlert = xpWindowManager.isDialogWindowType(lp);
                boolean isApplication = xpWindowManager.isApplicationWindowType(lp);
                if (isAlert) {
                    SharedDisplayFactory.addBundle(callback, "ignoreWarning", true);
                }
                if (isAlert || isApplication) {
                    int intentFlags = lp.intentFlags;
                    boolean panel = (intentFlags & 256) == 256;
                    boolean dialog = (intentFlags & 128) == 128;
                    boolean physicalFullscreen = (intentFlags & 32) == 32;
                    return (isAlert || panel || dialog || physicalFullscreen) ? false : true;
                }
            }
        }
        return false;
    }

    private static void refreshPackageSettings(Handler handler, boolean primaryChanged, boolean secondaryChanged) {
        if (handler == null) {
            return;
        }
        if (primaryChanged) {
            final String packageName = getPackageName(sPrimaryTopComponent);
            handler.postDelayed(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$YMepb6WG2cPYJxI0Y27_O6m7zIU
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.removePackageSettings(0, packageName);
                }
            }, 1000L);
        }
        if (secondaryChanged) {
            final String packageName2 = getPackageName(sSecondaryTopComponent);
            handler.postDelayed(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$xqKsibvWQgkeOm2n0_DebuDdc8A
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.removePackageSettings(1, packageName2);
                }
            }, 1000L);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void removePackageSettings(int screenId, String topPackageName) {
        String currentTopPackageName = "";
        if (screenId == 0) {
            currentTopPackageName = getPackageName(sPrimaryTopComponent);
        } else if (screenId == 1) {
            currentTopPackageName = getPackageName(sSecondaryTopComponent);
        }
        if (!topPackageChanged(currentTopPackageName, topPackageName)) {
            SharedDisplayPolicy.removePackageSettings(screenId, topPackageName);
        }
    }

    private static boolean isPackageSettingsEmpty() {
        return SharedDisplayPolicy.isPackageSettingsEmpty();
    }

    private static boolean topActivityChanged(String top, String history) {
        if (TextUtils.isEmpty(top) && TextUtils.isEmpty(history)) {
            return false;
        }
        if (TextUtils.isEmpty(top) || TextUtils.isEmpty(history)) {
            return true;
        }
        return !TextUtils.equals(top, history);
    }

    private static boolean topPackageChanged(String top, String history) {
        if (TextUtils.isEmpty(top) && TextUtils.isEmpty(history)) {
            return false;
        }
        if (TextUtils.isEmpty(top) || TextUtils.isEmpty(history)) {
            return true;
        }
        try {
            String topPackage = getPackageName(top);
            String historyPackage = getPackageName(history);
            if (TextUtils.isEmpty(topPackage) && TextUtils.isEmpty(historyPackage)) {
                return false;
            }
            if (!TextUtils.isEmpty(topPackage) && !TextUtils.isEmpty(historyPackage)) {
                return !topPackage.equals(historyPackage);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean shouldPauseActivity(boolean userLeaving, boolean uiSleeping, ActivityRecord resuming, ActivityRecord prev, boolean pauseImmediately) {
        if (uiSleeping) {
            return true;
        }
        if (SharedDisplayManager.isUnityUI() && prev != null && prev.isActivityTypeHome()) {
            return false;
        }
        if (SharedDisplayManager.enable() && userLeaving && resuming != null && prev != null) {
            int resumingScreenId = SharedDisplayManager.findScreenId(getSharedId(resuming));
            int prevScreenId = SharedDisplayManager.findScreenId(getSharedId(prev));
            if (resumingScreenId != prevScreenId) {
                return false;
            }
        }
        return true;
    }

    public static ActivityStack.ActivityState activityState(IBinder token) {
        ActivityRecord r = findActivityRecord(token);
        return r != null ? r.getState() : ActivityStack.ActivityState.DESTROYED;
    }

    public static boolean isState(ActivityStack.ActivityState target, ActivityStack.ActivityState state1) {
        return state1 == target;
    }

    public static boolean isState(ActivityStack.ActivityState target, ActivityStack.ActivityState state1, ActivityStack.ActivityState state2) {
        return state1 == target || state2 == target;
    }

    public static boolean isState(ActivityStack.ActivityState target, ActivityStack.ActivityState state1, ActivityStack.ActivityState state2, ActivityStack.ActivityState state3) {
        return state1 == target || state2 == target || state3 == target;
    }

    public static ActivityRecord findActivityRecord(IBinder token) {
        return ActivityRecord.forTokenLocked(token);
    }

    public static ActivityRecord findActivityRecord(AppWindowToken token, RootActivityContainer root) {
        int count;
        if (root != null && token != null) {
            try {
                ActivityDisplay ad = root.getActivityDisplay(0);
                if (ad == null || (count = ad.getChildCount()) <= 0) {
                    return null;
                }
                for (int i = 0; i < count; i++) {
                    ActivityStack stack = ad.getChildAt(i);
                    if (stack != null) {
                        ArrayList<TaskRecord> tasks = stack.getAllTasks();
                        Iterator<TaskRecord> it = tasks.iterator();
                        while (it.hasNext()) {
                            TaskRecord tr = it.next();
                            if (tr != null) {
                                ArrayList<ActivityRecord> activities = tr.mActivities;
                                Iterator<ActivityRecord> it2 = activities.iterator();
                                while (it2.hasNext()) {
                                    ActivityRecord r = it2.next();
                                    if (r != null && r.mAppWindowToken == token) {
                                        return r;
                                    }
                                }
                                continue;
                            }
                        }
                        continue;
                    }
                }
            } catch (Exception e) {
            }
        }
        return null;
    }

    public static ArrayList<SharedRecord> getSharedRecord(RootWindowContainer root) {
        if (root != null) {
            ArrayList<WindowState> output = new ArrayList<>();
            root.getWindowsByType(output, 10);
            if (!output.isEmpty()) {
                ArrayList<SharedRecord> recorders = new ArrayList<>();
                Iterator<WindowState> it = output.iterator();
                while (it.hasNext()) {
                    WindowState win = it.next();
                    if (win != null) {
                        recorders.add(createRecorder(win));
                    }
                }
                return recorders;
            }
            return null;
        }
        return null;
    }

    public static ArrayList<SharedRecord> getSharedRecord(RootActivityContainer root) {
        int count;
        if (root != null) {
            try {
                ActivityDisplay ad = root.getActivityDisplay(0);
                if (ad == null || (count = ad.getChildCount()) <= 0) {
                    return null;
                }
                ArrayList<SharedRecord> recorders = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    ActivityStack stack = ad.getChildAt(i);
                    if (stack != null) {
                        ArrayList<TaskRecord> tasks = stack.getAllTasks();
                        Iterator<TaskRecord> it = tasks.iterator();
                        while (it.hasNext()) {
                            TaskRecord tr = it.next();
                            if (tr != null) {
                                ArrayList<ActivityRecord> activities = tr.mActivities;
                                Iterator<ActivityRecord> it2 = activities.iterator();
                                while (it2.hasNext()) {
                                    ActivityRecord r = it2.next();
                                    if (r != null && r.visible) {
                                        recorders.add(createRecorder(r));
                                    }
                                }
                            }
                        }
                    }
                }
                return recorders;
            } catch (Exception e) {
            }
        }
        return null;
    }

    public static ArrayList<ActivityRecord> getHistoryActivitiesLocked(RootActivityContainer root) {
        ArrayList<ActivityRecord> list;
        ActivityDisplay display = root.getDefaultDisplay();
        if (display == null) {
            return null;
        }
        ArrayList<ActivityRecord> activities = new ArrayList<>();
        for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; stackNdx--) {
            ActivityStack stack = display.getChildAt(stackNdx);
            if (stack != null && stack.getChildCount() != 0 && stack.getChildCount() != 0 && (list = stack.getHistoryActivitiesLocked()) != null && list.size() != 0) {
                activities.addAll(list);
            }
        }
        return activities;
    }

    public static SharedRecord createRecorder(String packageName) {
        if (!TextUtils.isEmpty(packageName)) {
            try {
                SharedRecord record = new SharedRecord(0);
                record.appToken = null;
                record.sharedId = getSharedId(packageName);
                record.screenId = SharedDisplayManager.findScreenId(record.sharedId);
                record.packageName = packageName;
                record.visible = true;
                return record;
            } catch (Exception e) {
            }
        }
        return null;
    }

    public static SharedRecord createRecorder(ActivityRecord r) {
        if (r != null) {
            try {
                SharedActivity record = new SharedActivity(2);
                record.appToken = r.mAppWindowToken;
                record.sharedId = getSharedId(r);
                record.screenId = SharedDisplayManager.findScreenId(record.sharedId);
                record.packageName = r.packageName;
                record.visible = r.visible;
                record.intentFlags = r.intent != null ? r.intent.getPrivateFlags() : 0;
                record.taskId = r.getTaskRecord().taskId;
                record.stackId = r.getStackId();
                record.activityType = r.getActivityType();
                record.activityState = r.getState();
                return record;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static SharedRecord createRecorder(WindowState win) {
        if (win != null) {
            try {
                boolean z = true;
                SharedWindow record = new SharedWindow(1);
                record.appToken = win.mAppToken;
                record.sharedId = win.mAttrs.sharedId;
                record.screenId = SharedDisplayManager.findScreenId(record.sharedId);
                record.packageName = win.mAttrs.packageName;
                if (!win.isVisible() && !win.isVisibleNow()) {
                    z = false;
                }
                record.visible = z;
                record.intentFlags = win.mAttrs != null ? win.mAttrs.intentFlags : 0;
                record.windowType = win.mAttrs.type;
                record.token = win.mClient.asBinder();
                record.client = win.mClient;
                return record;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static void updateRecorder(SharedRecord record, ActivityRecord activity) {
        if (record != null && activity != null) {
            try {
                record.appToken = activity.mAppWindowToken;
                record.sharedId = getSharedId(activity);
                record.screenId = SharedDisplayManager.findScreenId(record.sharedId);
                record.packageName = activity.packageName;
                record.visible = activity.visible;
                if (record instanceof SharedActivity) {
                    ((SharedActivity) record).taskId = activity.getTaskRecord().taskId;
                    ((SharedActivity) record).stackId = activity.getStackId();
                    ((SharedActivity) record).activityType = activity.getActivityType();
                    ((SharedActivity) record).activityState = activity.getState();
                }
            } catch (Exception e) {
            }
        }
    }

    public static String parseTokenName(WindowManager.LayoutParams lp) {
        if (lp == null) {
            return null;
        }
        try {
            ActivityRecord.Token token = (ActivityRecord.Token) lp.token;
            if (token != null) {
                return token.getName();
            }
        } catch (Exception e) {
        }
        return null;
    }

    public static Rect getDisplayBounds(int displayId) {
        try {
            Rect bounds = new Rect();
            DisplayMetrics metrics = new DisplayMetrics();
            Display display = DisplayManagerGlobal.getInstance().getRealDisplay(displayId);
            if (display != null) {
                display.getRealMetrics(metrics);
                bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
                return bounds;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void showToast(Context context, String text, int duration, int sharedId) {
        try {
            Intent intent = new Intent("com.xiaopeng.xui.intent.action.TOAST");
            intent.putExtra("text", text);
            intent.putExtra("duration", duration);
            intent.putExtra("sharedId", sharedId);
            intent.putExtra("caller", PackageManagerService.PLATFORM_PACKAGE_NAME);
            intent.addFlags(20971552);
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        } catch (Exception e) {
        }
    }

    public static void initLaunchActivityParams(Context context, ActivityInfo activity, Intent intent, ApplicationInfo caller) {
        ComponentName top;
        if (!SharedDisplayManager.enable() || context == null || activity == null || intent == null || caller == null) {
            return;
        }
        String packageName = activity.packageName;
        ComponentName component = activity.getComponentName();
        if (TextUtils.isEmpty(packageName) || component == null) {
            return;
        }
        boolean packageEnabled = SharedDisplayImpl.isSharedPackageEnabled(packageName);
        if (packageEnabled) {
            int requestId = SharedDisplayManager.getOverrideSharedId(SharedDisplayManager.SharedParams.create(packageName, intent, (WindowManager.LayoutParams) null));
            int currentId = getSharedId(packageName);
            if (currentId != requestId) {
                try {
                    String content = xpWindowManager.getWindowManager().getTopActivity(0, currentId);
                    if (!TextUtils.isEmpty(content) && (top = ComponentName.unflattenFromString(content)) != null && packageName.equals(top.getPackageName())) {
                        xpActivityManager.getActivityTaskManager().finishPackageActivity(packageName);
                    }
                } catch (Exception e) {
                }
            }
        }
    }

    public static void refreshProcessChangedEvent(int event, Context context, Bundle extras) {
        if (extras == null) {
            return;
        }
        if (event != 0) {
            if (event == 1) {
                int pid = extras.getInt("pid", -1);
                String packageName = extras.getString("packageName", "");
                if (pid > 0 && !TextUtils.isEmpty(packageName)) {
                    sProcessHistory.put(Integer.valueOf(pid), packageName);
                    return;
                }
                return;
            }
            return;
        }
        int pid2 = extras.getInt("pid", -1);
        String packageName2 = sProcessHistory.getOrDefault(Integer.valueOf(pid2), "");
        if (pid2 > 0 && !TextUtils.isEmpty(packageName2)) {
            if (SharedDisplayManager.isUnityUI()) {
                boolean isHome = TextUtils.equals(packageName2, getHomePackageName(context));
                if (isHome) {
                    relaunchHome();
                }
            }
            SharedDisplayImpl.removeActivityLevel(packageName2);
            sProcessHistory.remove(Integer.valueOf(pid2));
        }
    }

    public static void relaunchHome() {
        try {
            xpLogger.i(TAG, "relaunchHome");
            xpWindowManager.getWindowManager().setSharedEvent(3, -1, "");
            ThreadExecutor.execute(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$68C2fjVb21ePj7XvPiHuG-djJXk
                @Override // java.lang.Runnable
                public final void run() {
                    xpInputManager.sendEvent(3);
                }
            });
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String getHomePackageName(Context context) {
        if (TextUtils.isEmpty(sHomePackageName)) {
            ComponentName component = ActivityInfoManager.HomeInfo.findDefaultHome(context);
            sHomePackageName = component != null ? component.getPackageName() : "";
            xpLogger.i(TAG, "getHomePackageName home=" + sHomePackageName);
        }
        return sHomePackageName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:128:? A[RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:48:0x009b A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:56:0x00ba A[Catch: all -> 0x007a, TryCatch #1 {all -> 0x007a, blocks: (B:31:0x004d, B:35:0x005b, B:39:0x006a, B:49:0x009d, B:51:0x00aa, B:53:0x00b0, B:54:0x00b4, B:56:0x00ba, B:59:0x00c3, B:63:0x00db, B:65:0x00e0), top: B:107:0x004d }] */
    /* JADX WARN: Removed duplicated region for block: B:67:0x00eb A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:91:0x0150 A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:95:0x015f  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void performEventChanged(int r23, com.android.server.wm.SharedDisplayContainer.SharedRecord r24) {
        /*
            Method dump skipped, instructions count: 392
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.SharedDisplayContainer.performEventChanged(int, com.android.server.wm.SharedDisplayContainer$SharedRecord):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$performEventChanged$13(int sharedId, String packageName) {
        int from = generateNextSharedId(sharedId);
        SharedDisplayImpl.handlePositionChanged(packageName, 4, from, sharedId);
    }

    /* loaded from: classes2.dex */
    public static final class ThreadExecutor {
        private static final ExecutorService sFixedThreadPool = Executors.newFixedThreadPool(4);
        private static final ExecutorService sSingleThreadExecutor = Executors.newSingleThreadExecutor();

        public static void execute(final Runnable runnable, final Runnable callback, final int priority) {
            try {
                if (!sFixedThreadPool.isShutdown()) {
                    sFixedThreadPool.execute(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$ThreadExecutor$s884lUvbrCxyJJYANhk4ju3b17o
                        @Override // java.lang.Runnable
                        public final void run() {
                            SharedDisplayContainer.ThreadExecutor.lambda$execute$0(priority, runnable, callback);
                        }
                    });
                }
            } catch (Exception e) {
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$execute$0(int priority, Runnable runnable, Runnable callback) {
            Process.setThreadPriority(priority);
            runnable.run();
            if (callback != null) {
                new Handler(Looper.myLooper()).post(callback);
            }
        }

        public static void execute(Runnable runnable) {
            execute(runnable, null, 10);
        }

        public static void execute(Runnable runnable, boolean blocking) {
            if (blocking) {
                sSingleThreadExecutor.execute(runnable);
            } else {
                execute(runnable);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
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

    /* loaded from: classes2.dex */
    public static final class SharedDevice {
        public Handler handler;
        public SharedRecord home;
        public RootActivityContainer root;
        public int screenId;
        public SharedRecord top;
        public List<SharedRecord> windows = new ArrayList();
        public List<SharedRecord> activities = new ArrayList();

        public SharedDevice(Handler handler, int screenId) {
            this.handler = handler;
            this.screenId = screenId;
        }

        public void setActivities(List<SharedRecord> list) {
            synchronized (this.activities) {
                this.activities.clear();
                if (list != null || !list.isEmpty()) {
                    this.activities.addAll(list);
                }
            }
        }

        public void setContainer(RootActivityContainer root) {
            this.root = root;
        }

        public void update() {
            synchronized (this.activities) {
                this.top = null;
                this.home = null;
                int size = this.activities.size();
                for (int i = 0; i < size; i++) {
                    try {
                        SharedActivity activity = (SharedActivity) this.activities.get(i);
                        if (activity != null && activity.appToken != null) {
                            boolean resumed = SharedDisplayContainer.isState(activity.activityState, ActivityStack.ActivityState.RESUMED);
                            if (this.top == null && resumed) {
                                this.top = activity.mo46clone();
                            }
                            if (activity.isHome()) {
                                this.home = activity.mo46clone();
                            }
                            ActivityRecord record = SharedDisplayContainer.findActivityRecord(activity.appToken, this.root);
                            if (record != null && record.appToken != null) {
                                boolean adjustLifecycle = adjustLifecycle(this.root, activity, this.top, this.screenId);
                                if (adjustLifecycle) {
                                    xpLogger.i(SharedDisplayContainer.TAG, "update adjustLifecycle screenId=" + this.screenId + " record=" + record.mActivityComponent);
                                    ActivityTaskManager.getService().finishActivityAffinity(record.appToken.asBinder());
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        xpLogger.i(SharedDisplayContainer.TAG, "SharedDevice update e=" + e);
                    }
                }
            }
        }

        public SharedRecord getTopActivity() {
            SharedRecord sharedRecord;
            synchronized (this.activities) {
                sharedRecord = this.top;
            }
            return sharedRecord;
        }

        public List<SharedRecord> getResumedActivity() {
            List<SharedRecord> list = new ArrayList<>();
            synchronized (this.activities) {
                for (SharedRecord record : this.activities) {
                    if (record != null) {
                        if (record instanceof SharedActivity) {
                            SharedActivity activity = (SharedActivity) record;
                            if (activity.activityState == ActivityStack.ActivityState.RESUMED) {
                                list.add(record);
                            }
                        }
                    }
                }
            }
            return list;
        }

        public boolean hasResumedActivity() {
            List<SharedRecord> list = getResumedActivity();
            return (list == null || list.isEmpty()) ? false : true;
        }

        public boolean hasActivityRecord(AppWindowToken appToken, ActivityStack.ActivityState state) {
            synchronized (this.activities) {
                for (SharedRecord record : this.activities) {
                    if (record != null) {
                        if (record instanceof SharedActivity) {
                            SharedActivity activity = (SharedActivity) record;
                            if (appToken == activity.appToken && activity.activityState == state) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }
        }

        public static boolean isFloatingActivity(int flags) {
            boolean panel = (flags & 256) == 256;
            boolean dialog = (flags & 128) == 128;
            return panel || dialog;
        }

        public static boolean adjustLifecycle(RootActivityContainer root, SharedActivity activity, SharedRecord top, int screenId) {
            if (SharedDisplayManager.enable()) {
                if (root != null && activity != null) {
                    if (top != null) {
                        boolean resumed = SharedDisplayContainer.isState(activity.activityState, ActivityStack.ActivityState.RESUMED);
                        if (resumed && !activity.equals(top)) {
                            ActivityRecord record = SharedDisplayContainer.findActivityRecord(activity.appToken, root);
                            TaskRecord task = record != null ? record.getTaskRecord() : null;
                            if (record != null && task != null) {
                                boolean topFloating = isFloatingActivity(top.intentFlags);
                                if (topFloating) {
                                    boolean activityFloating = isFloatingActivity(activity.intentFlags);
                                    if (!activityFloating) {
                                        return false;
                                    }
                                }
                                boolean isHome = record.isActivityTypeHome();
                                if (isHome) {
                                    return false;
                                }
                                boolean hasHome = hasHomeActivityRecord(root);
                                int sharedId = SharedDisplayContainer.getSharedId(record);
                                boolean visible = record.nowVisible || task.isVisible();
                                boolean resizing = SharedDisplayContainer.sTaskResizing;
                                boolean recordResumed = record.isState(ActivityStack.ActivityState.RESUMED);
                                boolean screenChanged = screenId != SharedDisplayManager.findScreenId(sharedId);
                                boolean adjustLifecycle = recordResumed && visible && hasHome && !resizing && !screenChanged;
                                return adjustLifecycle;
                            }
                            return false;
                        }
                        return false;
                    }
                }
                return false;
            }
            return false;
        }

        public static boolean hasHomeActivityRecord(RootActivityContainer root) {
            if (root == null) {
                return false;
            }
            ActivityRecord r = root.getDefaultDisplayHomeActivity();
            return r != null;
        }
    }

    /* loaded from: classes2.dex */
    public static final class SharedWindow extends SharedRecord {
        public IWindow client;
        public IBinder token;
        public int windowType;

        public SharedWindow(int type) {
            super(type);
        }

        @Override // com.android.server.wm.SharedDisplayContainer.SharedRecord
        /* renamed from: clone */
        public SharedWindow mo46clone() {
            SharedWindow record = new SharedWindow(this.type);
            record.type = this.type;
            record.appToken = this.appToken;
            record.sharedId = this.sharedId;
            record.screenId = this.screenId;
            record.packageName = this.packageName;
            record.visible = this.visible;
            record.intentFlags = this.intentFlags;
            record.windowType = this.windowType;
            record.token = this.token;
            record.client = this.client;
            return record;
        }
    }

    /* loaded from: classes2.dex */
    public static final class SharedActivity extends SharedRecord {
        public ActivityStack.ActivityState activityState;
        public int activityType;
        public int stackId;
        public int taskId;

        public SharedActivity(int type) {
            super(type);
        }

        public boolean isHome() {
            return this.activityType == 2;
        }

        @Override // com.android.server.wm.SharedDisplayContainer.SharedRecord
        /* renamed from: clone */
        public SharedActivity mo46clone() {
            SharedActivity record = new SharedActivity(this.type);
            record.type = this.type;
            record.appToken = this.appToken;
            record.sharedId = this.sharedId;
            record.screenId = this.screenId;
            record.packageName = this.packageName;
            record.visible = this.visible;
            record.intentFlags = this.intentFlags;
            record.taskId = this.taskId;
            record.stackId = this.stackId;
            record.activityType = this.activityType;
            record.activityState = this.activityState;
            return record;
        }
    }

    /* loaded from: classes2.dex */
    public static class SharedRecord {
        public static final int TYPE_ACTIVITY = 2;
        public static final int TYPE_UNKNOWN = 0;
        public static final int TYPE_WINDOW = 1;
        public AppWindowToken appToken;
        public int intentFlags;
        public String packageName;
        public int screenId;
        public int sharedId;
        public int type;
        public boolean visible;

        public SharedRecord(int type) {
            this.type = type;
        }

        public IBinder asBinder() {
            AppWindowToken appWindowToken = this.appToken;
            if (appWindowToken != null && appWindowToken.appToken != null) {
                return this.appToken.appToken.asBinder();
            }
            return null;
        }

        public String toString() {
            StringBuffer buffer = new StringBuffer("");
            buffer.append("SharedRecord:{");
            buffer.append("sharedId=" + this.sharedId);
            buffer.append(";screenId=" + this.screenId);
            buffer.append(";packageName=" + this.packageName);
            buffer.append(";type=" + this.type);
            buffer.append(";visible=" + this.visible);
            buffer.append(";intentFlags=0x" + Integer.toHexString(this.intentFlags));
            buffer.append(";appToken=" + this.appToken);
            buffer.append("}");
            return buffer.toString();
        }

        @Override // 
        /* renamed from: clone */
        public SharedRecord mo46clone() {
            SharedRecord record = new SharedRecord(this.type);
            record.type = this.type;
            record.appToken = this.appToken;
            record.sharedId = this.sharedId;
            record.screenId = this.screenId;
            record.packageName = this.packageName;
            record.visible = this.visible;
            record.intentFlags = this.intentFlags;
            return record;
        }

        public boolean equals(SharedRecord r) {
            return r != null && r.appToken == this.appToken;
        }

        public boolean equalsActivity(SharedRecord r) {
            if (this.appToken.mActivityComponent != null && r != null && r.appToken != null) {
                return xpActivityManager.isComponentEqual(this.appToken.mActivityComponent, r.appToken.mActivityComponent);
            }
            return false;
        }

        public String getActivityComponent() {
            ComponentName component;
            AppWindowToken appWindowToken = this.appToken;
            return (appWindowToken == null || (component = appWindowToken.mActivityComponent) == null) ? "" : component.flattenToString();
        }

        public static boolean isHome(SharedRecord r) {
            return r != null && (r instanceof SharedWindow) && ((SharedWindow) r).windowType == 5;
        }

        public static boolean isTopChanged(SharedRecord top, SharedRecord record) {
            if (top == null && record == null) {
                return false;
            }
            if (top == null || record == null) {
                return true;
            }
            return true ^ top.equalsActivity(record);
        }
    }

    /* loaded from: classes2.dex */
    public static final class LaunchIntent {
        public String className;
        public boolean fullscreen;
        public long lunchTime = System.currentTimeMillis();
        public int privateFlags;
        public int windowType;

        public LaunchIntent(String className, int privateFlags, int windowType, boolean fullscreen) {
            this.privateFlags = privateFlags;
            this.windowType = windowType;
            this.fullscreen = fullscreen;
            this.className = className;
        }
    }

    /* loaded from: classes2.dex */
    public static final class LaunchParams {
        public Rect bounds;
        public String packageName;
        public int windowingMode;

        private LaunchParams(String packageName, int windowingMode, Rect bounds) {
            this.packageName = packageName;
            this.windowingMode = windowingMode;
            this.bounds = new Rect(bounds);
        }

        public static LaunchParams create(String packageName, int windowingMode, Rect bounds) {
            if (!TextUtils.isEmpty(packageName) && windowingMode == 5 && bounds != null && !bounds.isEmpty()) {
                return new LaunchParams(packageName, windowingMode, bounds);
            }
            return null;
        }
    }

    /* loaded from: classes2.dex */
    public static final class SharedDisplayData {
        public String component;
        public int height;
        public int id;
        public String label;
        public int layer;
        public String mixedType;
        public int mode;
        public String packageName;
        public int screenId;
        public int sharedId;
        public int state;
        public String token;
        public int type;
        public int width;
        public int x;
        public int y;
        public boolean enable = true;
        public boolean resizing = false;
        public boolean fullscreen = false;
        public boolean physical = false;

        public boolean isHome() {
            return this.type == 5;
        }

        public static String toJson(List<SharedDisplayData> list) {
            if (list == null || list.size() == 0) {
                return "";
            }
            try {
                JSONArray array = new JSONArray();
                for (SharedDisplayData data : list) {
                    if (data != null) {
                        JSONObject object = new JSONObject(toJson(data));
                        array.put(object);
                    }
                }
                return array.toString();
            } catch (Exception e) {
                return "";
            }
        }

        public static String toJson(SharedDisplayData data) {
            if (data == null) {
                return "";
            }
            try {
                JSONObject object = new JSONObject();
                object.put("id", data.id);
                object.put("sharedId", data.sharedId);
                object.put("screenId", data.screenId);
                object.put("type", data.type);
                object.put(xpInputManagerService.InputPolicyKey.KEY_MODE, data.mode);
                object.put("state", data.state);
                object.put(xpInputManagerService.InputPolicyKey.KEY_ENABLE, data.enable);
                object.put("resizing", data.resizing);
                object.put("fullscreen", data.fullscreen);
                object.put("physical", data.physical);
                object.put("packageName", data.packageName);
                object.put("component", data.component);
                object.put("label", data.label);
                object.put("token", data.token);
                object.put("mixedType", data.mixedType);
                return object.toString();
            } catch (Exception e) {
                return "";
            }
        }

        public static SharedDisplayData fromJson(String json) {
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            try {
                try {
                    JSONObject object = new JSONObject(json);
                    if (object.has("id")) {
                        SharedDisplayData data = new SharedDisplayData();
                        data.id = object.getInt("id");
                        data.sharedId = data.id;
                        data.screenId = SharedDisplayManager.findScreenId(data.id);
                        data.type = object.has("type") ? object.getInt("type") : 0;
                        data.mode = object.has(xpInputManagerService.InputPolicyKey.KEY_MODE) ? object.getInt(xpInputManagerService.InputPolicyKey.KEY_MODE) : 0;
                        data.state = object.has("state") ? object.getInt("state") : 0;
                        data.enable = object.has(xpInputManagerService.InputPolicyKey.KEY_ENABLE) ? object.getBoolean(xpInputManagerService.InputPolicyKey.KEY_ENABLE) : true;
                        data.resizing = object.has("resizing") ? object.getBoolean("resizing") : false;
                        data.fullscreen = object.has("fullscreen") ? object.getBoolean("fullscreen") : false;
                        data.physical = object.has("physical") ? object.getBoolean("physical") : false;
                        data.packageName = object.has("packageName") ? object.getString("packageName") : "";
                        data.component = object.has("component") ? object.getString("component") : "";
                        data.label = object.has("label") ? object.getString("label") : "home";
                        data.mixedType = object.has("mixedType") ? object.getString("mixedType") : "";
                        return data;
                    }
                } catch (Exception e) {
                }
            } catch (Exception e2) {
            }
            return null;
        }

        public static SharedDisplayData createData(WindowState win) {
            if (win == null || win.mAttrs == null) {
                return null;
            }
            WindowManager.LayoutParams lp = win.mAttrs;
            int intentFlags = lp.intentFlags;
            int i = lp.systemUiVisibility | lp.subtreeSystemUiVisibility;
            int sharedId = lp.sharedId;
            int screenId = SharedDisplayManager.findScreenId(sharedId);
            boolean physical = (intentFlags & 32) == 32;
            SharedDisplayData data = new SharedDisplayData();
            data.id = sharedId;
            data.sharedId = sharedId;
            data.screenId = screenId;
            data.type = lp.type;
            data.mode = -1;
            data.state = 0;
            data.enable = SharedDisplayImpl.isSharedScreenEnabled(screenId);
            data.resizing = SharedDisplayContainer.sTaskResizing;
            data.fullscreen = xpWindowManager.isFullscreen(lp, intentFlags);
            data.physical = physical;
            data.packageName = lp.packageName;
            data.component = getActivityComponent(win);
            data.label = xpWindowManager.isAlertWindowType(lp) ? "dialog" : "application";
            data.token = lp.winToken;
            return data;
        }

        public static SharedDisplayData clone(SharedDisplayData data) {
            if (data == null) {
                return null;
            }
            SharedDisplayData sdd = new SharedDisplayData();
            sdd.id = data.id;
            sdd.sharedId = data.sharedId;
            sdd.screenId = data.screenId;
            sdd.type = data.type;
            sdd.mode = data.mode;
            sdd.state = data.state;
            sdd.enable = data.enable;
            sdd.resizing = data.resizing;
            sdd.fullscreen = data.fullscreen;
            sdd.physical = data.physical;
            sdd.x = data.x;
            sdd.y = data.y;
            sdd.width = data.width;
            sdd.height = data.height;
            sdd.layer = data.layer;
            sdd.packageName = data.packageName;
            sdd.component = data.component;
            sdd.label = data.label;
            sdd.token = data.token;
            sdd.mixedType = data.mixedType;
            return sdd;
        }

        public static SharedDisplayData clone(SharedDisplayData data, int sharedId) {
            SharedDisplayData sdd = clone(data);
            if (sdd != null) {
                sdd.id = sharedId;
                sdd.sharedId = sharedId;
                sdd.screenId = SharedDisplayManager.findScreenId(sharedId);
            }
            return sdd;
        }

        private static String getActivityComponent(WindowState win) {
            if (win != null && win.mAppToken != null && win.mAppToken.mActivityComponent != null) {
                return win.mAppToken.mActivityComponent.flattenToString();
            }
            return "";
        }
    }

    /* loaded from: classes2.dex */
    public static final class SharedDisplayImpl {
        public static final int MULTI_SWIPE_FROM_BOTTOM = 2;
        public static final int MULTI_SWIPE_FROM_LEFT = 4;
        public static final int MULTI_SWIPE_FROM_NONE = 0;
        public static final int MULTI_SWIPE_FROM_RIGHT = 3;
        public static final int MULTI_SWIPE_FROM_TOP = 1;
        private static final Object sLockImpl = new Object();

        public static void registerSharedListener(ISharedDisplayListener listener) {
            synchronized (SharedDisplayContainer.sCallbacks) {
                if (listener != null) {
                    int callingPid = Binder.getCallingPid();
                    SharedDisplayContainer.sCallbacks.register(listener, Integer.valueOf(callingPid));
                }
            }
        }

        public static void unregisterSharedListener(ISharedDisplayListener listener) {
            synchronized (SharedDisplayContainer.sCallbacks) {
                if (listener != null) {
                    SharedDisplayContainer.sCallbacks.unregister(listener);
                }
            }
        }

        public static void handleChanged(final String packageName, final int sharedId) {
            Runnable runnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$SharedDisplayImpl$XsvBeU8mOQUZkcQHiNCAJKvmIpE
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.SharedDisplayImpl.lambda$handleChanged$0(packageName, sharedId);
                }
            };
            ThreadExecutor.execute(runnable);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$handleChanged$0(String packageName, int sharedId) {
            synchronized (SharedDisplayContainer.sCallbacks) {
                int count = SharedDisplayContainer.sCallbacks.getRegisteredCallbackCount();
                if (count <= 0) {
                    return;
                }
                int size = SharedDisplayContainer.sCallbacks.beginBroadcast();
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        try {
                            SharedDisplayContainer.sCallbacks.getBroadcastItem(i).onChanged(packageName, sharedId);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                SharedDisplayContainer.sCallbacks.finishBroadcast();
            }
        }

        public static void handlePositionChanged(final String packageName, final int event, final int from, final int to) {
            Runnable runnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$SharedDisplayImpl$YzHmSshQUo2c6hNmT54mokBDpTQ
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.SharedDisplayImpl.lambda$handlePositionChanged$1(packageName, event, from, to);
                }
            };
            ThreadExecutor.execute(runnable);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$handlePositionChanged$1(String packageName, int event, int from, int to) {
            synchronized (SharedDisplayContainer.sCallbacks) {
                int count = SharedDisplayContainer.sCallbacks.getRegisteredCallbackCount();
                if (count <= 0) {
                    return;
                }
                int size = SharedDisplayContainer.sCallbacks.beginBroadcast();
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        try {
                            SharedDisplayContainer.sCallbacks.getBroadcastItem(i).onPositionChanged(packageName, event, from, to);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                SharedDisplayContainer.sCallbacks.finishBroadcast();
            }
        }

        public static void handleActivityChanged(final int screenId, final String property) {
            Runnable runnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$SharedDisplayImpl$YSWBb4LdmbvCJfww74kSqcUQJN4
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.SharedDisplayImpl.lambda$handleActivityChanged$2(screenId, property);
                }
            };
            ThreadExecutor.execute(runnable, true);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$handleActivityChanged$2(int screenId, String property) {
            synchronized (SharedDisplayContainer.sCallbacks) {
                int count = SharedDisplayContainer.sCallbacks.getRegisteredCallbackCount();
                if (count <= 0) {
                    return;
                }
                int size = SharedDisplayContainer.sCallbacks.beginBroadcast();
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        try {
                            SharedDisplayContainer.sCallbacks.getBroadcastItem(i).onActivityChanged(screenId, property);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                SharedDisplayContainer.sCallbacks.finishBroadcast();
            }
        }

        public static void handleEventChanged(final int event, final String property) {
            Runnable runnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$SharedDisplayImpl$KtC6tnwECTLsoBn6kPpJScCbbTk
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.SharedDisplayImpl.lambda$handleEventChanged$3(event, property);
                }
            };
            ThreadExecutor.execute(runnable);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$handleEventChanged$3(int event, String property) {
            synchronized (SharedDisplayContainer.sCallbacks) {
                int count = SharedDisplayContainer.sCallbacks.getRegisteredCallbackCount();
                if (count <= 0) {
                    return;
                }
                int size = SharedDisplayContainer.sCallbacks.beginBroadcast();
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        try {
                            SharedDisplayContainer.sCallbacks.getBroadcastItem(i).onEventChanged(event, property);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                SharedDisplayContainer.sCallbacks.finishBroadcast();
            }
        }

        public static void handleTransactEventChanged(final int event, final String property) {
            Runnable runnable = new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$SharedDisplayImpl$4e45UQg3MxfEvlsenkOc_wAuZrI
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.SharedDisplayImpl.lambda$handleTransactEventChanged$4(property, event);
                }
            };
            ThreadExecutor.execute(runnable);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$handleTransactEventChanged$4(String property, int event) {
            synchronized (SharedDisplayContainer.sCallbacks) {
                int count = SharedDisplayContainer.sCallbacks.getRegisteredCallbackCount();
                if (count <= 0) {
                    return;
                }
                int size = SharedDisplayContainer.sCallbacks.beginBroadcast();
                String descriptor = (String) xpTextUtils.getValue("descriptor", property, "");
                if (size > 0) {
                    for (int i = 0; i < size; i++) {
                        try {
                            ISharedDisplayListener listener = SharedDisplayContainer.sCallbacks.getBroadcastItem(i);
                            if (listener != null) {
                                boolean callbackToClient = true;
                                if (!TextUtils.isEmpty(descriptor)) {
                                    Object cookie = SharedDisplayContainer.sCallbacks.getRegisteredCallbackCookie(i);
                                    int pid = xpTextUtils.toInteger(cookie, -1).intValue();
                                    String packageName = pid > 0 ? xpActivityManager.getProcessName(pid) : "";
                                    callbackToClient = descriptor.equals(packageName);
                                }
                                if (callbackToClient) {
                                    listener.onEventChanged(event, property);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                SharedDisplayContainer.sCallbacks.finishBroadcast();
            }
        }

        /* JADX WARN: Removed duplicated region for block: B:102:0x01f9  */
        /* JADX WARN: Removed duplicated region for block: B:106:0x022b  */
        /* JADX WARN: Removed duplicated region for block: B:135:0x01af A[EXC_TOP_SPLITTER, SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:138:0x0160 A[SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:24:0x0056 A[RETURN] */
        /* JADX WARN: Removed duplicated region for block: B:25:0x0057 A[Catch: Exception -> 0x026a, TRY_LEAVE, TryCatch #0 {Exception -> 0x026a, blocks: (B:3:0x0006, B:6:0x000e, B:13:0x003b, B:25:0x0057, B:49:0x00cc, B:51:0x00d9, B:53:0x00e0, B:55:0x00f2, B:60:0x0102, B:62:0x0108, B:66:0x0151, B:79:0x0179, B:100:0x01e3, B:127:0x0260, B:105:0x01fd, B:116:0x023a, B:122:0x0248, B:123:0x0250, B:125:0x0256, B:126:0x025b, B:76:0x0168, B:39:0x00ab, B:47:0x00c3, B:10:0x0031), top: B:131:0x0006 }] */
        /* JADX WARN: Removed duplicated region for block: B:76:0x0168 A[Catch: Exception -> 0x026a, LOOP:0: B:61:0x0106->B:76:0x0168, LOOP_END, TryCatch #0 {Exception -> 0x026a, blocks: (B:3:0x0006, B:6:0x000e, B:13:0x003b, B:25:0x0057, B:49:0x00cc, B:51:0x00d9, B:53:0x00e0, B:55:0x00f2, B:60:0x0102, B:62:0x0108, B:66:0x0151, B:79:0x0179, B:100:0x01e3, B:127:0x0260, B:105:0x01fd, B:116:0x023a, B:122:0x0248, B:123:0x0250, B:125:0x0256, B:126:0x025b, B:76:0x0168, B:39:0x00ab, B:47:0x00c3, B:10:0x0031), top: B:131:0x0006 }] */
        /* JADX WARN: Removed duplicated region for block: B:81:0x01ad  */
        /* JADX WARN: Removed duplicated region for block: B:93:0x01ca  */
        /* JADX WARN: Removed duplicated region for block: B:94:0x01cd  */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public static void handleSwipeEvent(int r30, android.content.Context r31, java.lang.String r32) {
            /*
                Method dump skipped, instructions count: 620
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.SharedDisplayContainer.SharedDisplayImpl.handleSwipeEvent(int, android.content.Context, java.lang.String):void");
        }

        public static void setTaskResizingBiDataEvent(Context context, Bundle property) {
            if (property == null) {
                return;
            }
            int from = ((Integer) SharedDisplayFactory.getBundle(property, "from", 0)).intValue();
            int to = ((Integer) SharedDisplayFactory.getBundle(property, "to", 0)).intValue();
            boolean booleanValue = ((Boolean) SharedDisplayFactory.getBundle(property, "enabled", true)).booleanValue();
            String extras = (String) SharedDisplayFactory.getBundle(property, "extras", "");
            String component = (String) SharedDisplayFactory.getBundle(property, "component", "");
            Map<String, String> content = new HashMap<>();
            content.put("from", String.valueOf(from));
            content.put("to", String.valueOf(to));
            content.put("result", String.valueOf(booleanValue ? 1 : 0));
            content.put("component", component);
            if (!TextUtils.isEmpty(extras)) {
                content.put("type", "gesture");
                content.put("x", xpTextUtils.toString(xpTextUtils.getValue("x", extras, 0)));
                content.put("y", xpTextUtils.toString(xpTextUtils.getValue("y", extras, 0)));
                content.put(AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, xpTextUtils.toString(xpTextUtils.getValue(AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, extras, 0)));
            }
            BiDataManager.sendStatData((int) EventLogTags.WM_TASK_CREATED, content);
        }

        public static String getTopWindow(SharedDisplayContainer container) {
            return container != null ? container.getTopWindow() : "";
        }

        public static String getTopActivity(ActivityTaskManagerService atm, int type, int id) {
            return getTopActivity(atm != null ? atm.mRootActivityContainer : null, type, id);
        }

        public static String getTopActivity(RootActivityContainer root, int type, int id) {
            SharedRecord record;
            String component = "";
            StringBuffer buffer = new StringBuffer("");
            buffer.append("getTopActivity");
            buffer.append(" type=" + type);
            buffer.append(" id=" + id);
            if (type == 0) {
                buffer.append(" screen");
                SharedDevice device = (SharedDevice) SharedDisplayContainer.sSharedDevices.getOrDefault(Integer.valueOf(id), null);
                SharedRecord record2 = device != null ? device.getTopActivity() : null;
                component = record2 != null ? record2.getActivityComponent() : "";
                StringBuilder sb = new StringBuilder();
                sb.append(" checkNull(record)=");
                sb.append(record2 == null);
                buffer.append(sb.toString());
                if (TextUtils.isEmpty(component)) {
                    int screenId = SharedDisplayContainer.generateNextScreenId(id);
                    SharedDevice sd = (SharedDevice) SharedDisplayContainer.sSharedDevices.getOrDefault(Integer.valueOf(screenId), null);
                    record = sd != null ? sd.getTopActivity() : null;
                    int intentFlags = record != null ? record.intentFlags : 0;
                    boolean physical = (intentFlags & 32) == 32;
                    buffer.append(" physical=" + physical);
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append(" checkNull(sr)=");
                    sb2.append(record == null);
                    buffer.append(sb2.toString());
                    if (physical && record != null) {
                        component = record.getActivityComponent();
                    }
                }
            } else if (type == 1) {
                buffer.append(" shared");
                int screenId2 = SharedDisplayManager.findScreenId(id);
                SharedDevice device2 = (SharedDevice) SharedDisplayContainer.sSharedDevices.getOrDefault(Integer.valueOf(screenId2), null);
                record = device2 != null ? device2.getTopActivity() : null;
                StringBuilder sb3 = new StringBuilder();
                sb3.append(" checkNull(record)=");
                sb3.append(record == null);
                buffer.append(sb3.toString());
                StringBuilder sb4 = new StringBuilder();
                sb4.append(" sharedId=");
                sb4.append(record != null ? record.sharedId : -1);
                buffer.append(sb4.toString());
                if (record != null && record.sharedId == id) {
                    component = record.getActivityComponent();
                }
            }
            buffer.append(" component=" + component);
            return component;
        }

        public static String getTopActivity(int screenId) {
            if (screenId != 0) {
                if (screenId == 1) {
                    return SharedDisplayContainer.sSecondaryTopComponent;
                }
                return "";
            }
            return SharedDisplayContainer.sPrimaryTopComponent;
        }

        public static boolean isVisibleInScreen(String packageName, int screenId) {
            SharedDevice device;
            if (!TextUtils.isEmpty(packageName) && (device = (SharedDevice) SharedDisplayContainer.sSharedDevices.getOrDefault(Integer.valueOf(screenId), null)) != null && device.activities != null) {
                for (SharedRecord record : device.activities) {
                    if (record != null && record.visible && TextUtils.equals(record.packageName, packageName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        public static List<String> getSharedPackages() {
            IPackageManager pm = xpPackageManager.getPackageManager();
            if (pm != null) {
                try {
                    List<String> list = new ArrayList<>();
                    ParceledListSlice<ApplicationInfo> parceledList = pm.getInstalledApplications(0, 0);
                    List<ApplicationInfo> packages = parceledList == null ? Collections.emptyList() : parceledList.getList();
                    if (packages != null && !packages.isEmpty()) {
                        for (ApplicationInfo ai : packages) {
                            if (ai != null && !TextUtils.isEmpty(ai.packageName)) {
                                int sharedId = SharedDisplayContainer.getSharedId(ai.packageName);
                                JSONObject object = new JSONObject();
                                object.put("packageName", ai.packageName);
                                object.put("sharedId", sharedId);
                                list.add(object.toString());
                            }
                        }
                        if (SharedDisplayContainer.sVirtualPackages != null && !SharedDisplayContainer.sVirtualPackages.isEmpty()) {
                            for (String pkg : SharedDisplayContainer.sVirtualPackages.keySet()) {
                                if (!TextUtils.isEmpty(pkg)) {
                                    JSONObject object2 = new JSONObject();
                                    object2.put("packageName", pkg);
                                    object2.put("sharedId", SharedDisplayContainer.sVirtualPackages.get(pkg));
                                    list.add(object2.toString());
                                }
                            }
                        }
                        return list;
                    }
                    return null;
                } catch (Exception e) {
                }
            }
            return null;
        }

        public static List<String> getFilterPackages(int sharedId) {
            List<String> data = getSharedPackages();
            if (data != null && data.size() > 0) {
                List<String> list = new ArrayList<>();
                try {
                    for (String value : data) {
                        JSONObject object = new JSONObject(value);
                        if (object.has("sharedId") && sharedId == object.getInt("sharedId")) {
                            list.add(value);
                        }
                    }
                } catch (Exception e) {
                }
                return list;
            }
            return null;
        }

        /* JADX WARN: Removed duplicated region for block: B:115:0x0233  */
        /* JADX WARN: Removed duplicated region for block: B:143:0x02a9 A[Catch: Exception -> 0x02c0, all -> 0x0311, TryCatch #0 {Exception -> 0x02c0, blocks: (B:94:0x01ea, B:96:0x01f7, B:98:0x0200, B:118:0x0239, B:120:0x0241, B:122:0x0249, B:124:0x0251, B:125:0x0257, B:127:0x0260, B:129:0x0269, B:131:0x026f, B:132:0x027c, B:134:0x0284, B:137:0x028d, B:139:0x0296, B:141:0x029c, B:138:0x0292, B:143:0x02a9, B:145:0x02b1, B:146:0x02b7, B:105:0x0214, B:108:0x021e, B:111:0x0228), top: B:169:0x01ea }] */
        /* JADX WARN: Removed duplicated region for block: B:56:0x00c3  */
        /* JADX WARN: Removed duplicated region for block: B:66:0x0107 A[Catch: Exception -> 0x0168, all -> 0x0311, TRY_LEAVE, TryCatch #2 {, blocks: (B:36:0x0077, B:37:0x0081, B:40:0x0089, B:42:0x0096, B:44:0x009f, B:58:0x00c7, B:60:0x00d0, B:62:0x00d8, B:64:0x00e0, B:65:0x00e6, B:66:0x0107, B:49:0x00ae, B:52:0x00b8, B:164:0x030f, B:71:0x016e, B:75:0x0193, B:77:0x0199, B:79:0x01a5, B:82:0x01ac, B:85:0x01b7, B:91:0x01e2, B:94:0x01ea, B:96:0x01f7, B:98:0x0200, B:118:0x0239, B:120:0x0241, B:122:0x0249, B:124:0x0251, B:125:0x0257, B:127:0x0260, B:129:0x0269, B:131:0x026f, B:132:0x027c, B:134:0x0284, B:137:0x028d, B:139:0x0296, B:141:0x029c, B:138:0x0292, B:143:0x02a9, B:145:0x02b1, B:146:0x02b7, B:105:0x0214, B:108:0x021e, B:111:0x0228, B:90:0x01da, B:150:0x02c2, B:152:0x02c8, B:154:0x02f2, B:157:0x02fd, B:159:0x0302, B:162:0x030b), top: B:172:0x0042 }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public static void setSharedEvent(final com.android.server.wm.SharedDisplayContainer r11, final int r12, final int r13, final java.lang.String r14) {
            /*
                Method dump skipped, instructions count: 788
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.SharedDisplayContainer.SharedDisplayImpl.setSharedEvent(com.android.server.wm.SharedDisplayContainer, int, int, java.lang.String):void");
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$setSharedEvent$8(SharedDisplayContainer container, int _to, String _component, int _from) {
            SharedDisplayManager.enableScreenIfNeed(container.mContext, _to);
            InputMethodPolicy.performInputMethodEvent(7);
            setPackagePositionChanged(container.mContext, _component, _from, _to);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$setSharedEvent$13(int screenId, boolean topOnly) {
            try {
                Bundle bundle = new Bundle();
                IWindowManager wm = xpWindowManager.getWindowManager();
                bundle.putInt("screenId", screenId);
                bundle.putBoolean("topOnly", topOnly);
                wm.dismissDialog(bundle);
            } catch (Exception e) {
            }
        }

        public static boolean isActivityLevelEnabled(int screenId) {
            return getActivityLevel(screenId) <= 0;
        }

        public static boolean isSharedScreenEnabled(int screenId) {
            boolean screenPolicyEnabled = SharedDisplayPolicy.getScreenPolicy(screenId) == 1;
            boolean activityLevelEnabled = isActivityLevelEnabled(screenId);
            return screenPolicyEnabled && activityLevelEnabled;
        }

        public static boolean isSharedScreenEnabled(int type, int screenId) {
            if (type == 1) {
                return SharedDisplayPolicy.getScreenPolicy(screenId) == 1;
            } else if (type == 2) {
                return isActivityLevelEnabled(screenId);
            } else {
                return isSharedScreenEnabled(screenId);
            }
        }

        public static boolean isSharedPackageEnabled(String packageName) {
            return SharedDisplayPolicy.getPackagePolicy(packageName) == 1;
        }

        public static void setSharedScreenEnabled(int screenId, boolean enabled) {
            SharedDisplayPolicy.setScreenPolicy(screenId, enabled ? 1 : 0);
        }

        public static void setSharedPackageEnabled(String packageName, boolean enabled) {
            SharedDisplayPolicy.setPackagePolicy(packageName, enabled ? 1 : 0);
        }

        public static void setSharedScreenPolicy(int screenId, int policy) {
            SharedDisplayPolicy.setScreenPolicy(screenId, policy);
        }

        public static void setSharedPackagePolicy(String packageName, int policy) {
            SharedDisplayPolicy.setPackagePolicy(packageName, policy);
        }

        public static void restoreSharedActivity(Context context, int sharedId, String component) {
            if (context == null) {
                return;
            }
            try {
                if (TextUtils.isEmpty(component)) {
                    return;
                }
                Intent intent = new Intent();
                intent.setComponent(ComponentName.unflattenFromString(component));
                intent.setSharedId(sharedId);
                intent.addFlags(270532608);
                context.startActivityAsUser(intent, UserHandle.CURRENT);
                xpLogger.i(SharedDisplayContainer.TAG, "restoreSharedActivity sharedId=" + sharedId + " component=" + component);
            } catch (Exception e) {
                xpLogger.i(SharedDisplayContainer.TAG, "restoreSharedActivity e=" + e);
            }
        }

        public static void setPackagePositionChanged(Context context, String component, int from, int to) {
            try {
                Intent intent = new Intent("com.xiaopeng.intent.action.PACKAGE_POSITION_CHANGED");
                intent.putExtra("component", component);
                intent.putExtra("from", from);
                intent.putExtra("to", to);
                context.sendBroadcastAsUser(intent, UserHandle.ALL);
            } catch (Exception e) {
            }
        }

        public static int getActivityLevel(int screenId) {
            ActivityLevel activityLevel = (ActivityLevel) SharedDisplayContainer.sActivityLevelHistory.getOrDefault(Integer.valueOf(screenId), null);
            if (activityLevel != null) {
                return activityLevel.level;
            }
            return 0;
        }

        public static void setActivityLevel(int screenId, int level, ComponentName component) {
            ActivityLevel activityLevel = new ActivityLevel();
            if (xpWindowManager.isComponentValid(component)) {
                activityLevel.level = level;
                activityLevel.packageName = component.getPackageName();
                activityLevel.className = component.getClassName();
                SharedDisplayContainer.sActivityLevelHistory.put(Integer.valueOf(screenId), activityLevel);
            }
        }

        public static void removeActivityLevel(int screenId, ComponentName top) {
            ActivityLevel activityLevel = (ActivityLevel) SharedDisplayContainer.sActivityLevelHistory.getOrDefault(Integer.valueOf(screenId), null);
            int level = activityLevel != null ? activityLevel.level : 0;
            if (activityLevel == null || level == 0) {
                return;
            }
            if (!TextUtils.equals(activityLevel.className, top != null ? top.getClassName() : "")) {
                SharedDisplayContainer.sActivityLevelHistory.remove(Integer.valueOf(screenId));
            }
        }

        public static void removeActivityLevel(String packageName) {
            if (TextUtils.isEmpty(packageName)) {
                return;
            }
            ActivityLevel primary = (ActivityLevel) SharedDisplayContainer.sActivityLevelHistory.getOrDefault(0, null);
            ActivityLevel secondary = (ActivityLevel) SharedDisplayContainer.sActivityLevelHistory.getOrDefault(1, null);
            if (primary != null && TextUtils.equals(packageName, primary.packageName)) {
                SharedDisplayContainer.sActivityLevelHistory.remove(0);
            }
            if (secondary != null && TextUtils.equals(packageName, secondary.packageName)) {
                SharedDisplayContainer.sActivityLevelHistory.remove(1);
            }
        }

        public static void setModeEvent(int sharedId, int mode, String extra) {
            SharedDisplayContainer.sModeHistory.put(Integer.valueOf(sharedId), Integer.valueOf(mode));
        }

        public static int getMode(int sharedId) {
            SharedDisplayData data;
            int mode = -1;
            String value = (String) SharedDisplayContainer.sHomeTopActivity.getOrDefault(Integer.valueOf(sharedId), "");
            if (!TextUtils.isEmpty(value) && (data = SharedDisplayData.fromJson(value)) != null) {
                mode = data.mode;
            }
            if (mode == -1) {
                return ((Integer) SharedDisplayContainer.sModeHistory.getOrDefault(Integer.valueOf(sharedId), -1)).intValue();
            }
            return mode;
        }

        public static int getType(int sharedId) {
            SharedDisplayData data;
            String value = (String) SharedDisplayContainer.sHomeTopActivity.getOrDefault(Integer.valueOf(sharedId), "");
            if (TextUtils.isEmpty(value) || (data = SharedDisplayData.fromJson(value)) == null) {
                return -1;
            }
            int type = data.type;
            return type;
        }

        public static String getMixedType(int sharedId) {
            SharedDisplayData data;
            String value = (String) SharedDisplayContainer.sHomeTopActivity.getOrDefault(Integer.valueOf(sharedId), "");
            if (TextUtils.isEmpty(value) || (data = SharedDisplayData.fromJson(value)) == null) {
                return "";
            }
            String mixedType = data.mixedType;
            return mixedType;
        }

        public static String getPackageName(int pid) {
            return (String) SharedDisplayContainer.sProcessHistory.getOrDefault(Integer.valueOf(pid), "");
        }

        public static boolean hasDialog(int screenId) {
            for (Integer num : SharedDisplayContainer.sDialogHistory.keySet()) {
                int id = num.intValue();
                if (screenId == SharedDisplayManager.findScreenId(id) && ((Boolean) SharedDisplayContainer.sDialogHistory.getOrDefault(Integer.valueOf(id), false)).booleanValue()) {
                    return true;
                }
            }
            return false;
        }
    }

    /* loaded from: classes2.dex */
    public static final class InputMethodPolicy {
        private static volatile IInputMethodManager sInputMethodManager;
        private static volatile String sInputMethodPackageName;
        private static volatile String sInputMethodRequestName;
        private static volatile boolean sInputMethodShown = false;
        private static volatile int sInputMethodPosition = 0;
        private static final ConcurrentHashMap<String, Integer> sPositionSettings = new ConcurrentHashMap<>();

        public static String getPackageName(Context context) {
            if (TextUtils.isEmpty(sInputMethodPackageName)) {
                sInputMethodPackageName = xpActivityManager.getImmPackageName(context);
            }
            return sInputMethodPackageName;
        }

        public static int getInputMethodPosition() {
            return sInputMethodPosition;
        }

        public static void setInputMethodPosition(Context context, int position, String extras) {
            if (SharedDisplayManager.enable()) {
                int inputPosition = position;
                String packageName = getPackageName(context);
                if (inputPosition == -1) {
                    inputPosition = 0;
                }
                if (TextUtils.isEmpty(packageName)) {
                    return;
                }
                SharedDisplayContainer.setSharedId(packageName, inputPosition);
                sInputMethodPosition = inputPosition;
                if (!TextUtils.isEmpty(extras)) {
                    try {
                        JSONObject object = new JSONObject(extras);
                        String requestName = object.has("packageName") ? object.getString("packageName") : "";
                        if (!TextUtils.isEmpty(requestName)) {
                            sPositionSettings.put(requestName, Integer.valueOf(inputPosition));
                        }
                    } catch (Exception e) {
                    }
                }
            }
        }

        public static void performInputMethodEvent(int msg) {
            if (msg != 7) {
                return;
            }
            try {
                IInputMethodManager imm = getInputMethodManager();
                boolean showing = imm != null ? imm.getInputShown() : false;
                if (showing) {
                    imm.forceHideSoftInputMethod();
                }
            } catch (Exception e) {
            }
        }

        public static void onInputMethodChanged(Context context, Handler handler, boolean shown) {
            boolean changed = sInputMethodShown != shown;
            sInputMethodShown = shown;
            if (changed) {
                dispatchInputMethodChanged(context, handler, shown, getInputMethodPosition());
            }
        }

        public static void onInputMethodShow(Context context, String packageName) {
            if (SharedDisplayManager.enable() && !TextUtils.isEmpty(packageName)) {
                int sharedId = getInputMethodSharedId(packageName);
                setInputMethodPosition(context, sharedId, "");
                sInputMethodRequestName = packageName;
                xpLogger.i(SharedDisplayContainer.TAG, "onInputMethodShow packageName=" + packageName + " sharedId=" + sharedId);
            }
        }

        private static int getInputMethodSharedId(String packageName) {
            if (TextUtils.isEmpty(packageName)) {
                return 0;
            }
            if (sPositionSettings.containsKey(packageName)) {
                return sPositionSettings.getOrDefault(packageName, 0).intValue();
            }
            return SharedDisplayContainer.getSharedId(packageName);
        }

        public static String getRequester() {
            return sInputMethodRequestName;
        }

        public static void dispatchInputMethodChanged(final Context context, Handler handler, final boolean shown, final int position) {
            if (context == null || handler == null) {
                return;
            }
            handler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayContainer$InputMethodPolicy$KF9VtlevxHCfRcphvoRfhxPS6FA
                @Override // java.lang.Runnable
                public final void run() {
                    SharedDisplayContainer.InputMethodPolicy.lambda$dispatchInputMethodChanged$0(context, shown, position);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$dispatchInputMethodChanged$0(Context context, boolean shown, int position) {
            try {
                EventTransactProperty property = new EventTransactProperty();
                property.event = 1002;
                property.function = "onInputMethodChanged";
                property.descriptor = SharedDisplayContainer.getHomePackageName(context);
                property.property.put("shown", Boolean.valueOf(shown));
                property.property.put("position", Integer.valueOf(position));
                String content = EventTransactProperty.toJson(property);
                WindowManager wm = (WindowManager) context.getSystemService("window");
                wm.setSharedEvent(property.event, -1, content);
            } catch (Exception e) {
            }
        }

        private static IInputMethodManager getInputMethodManager() {
            try {
                if (sInputMethodManager == null) {
                    IBinder binder = ServiceManager.getServiceOrThrow("input_method");
                    sInputMethodManager = IInputMethodManager.Stub.asInterface(binder);
                }
                return sInputMethodManager;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /* loaded from: classes2.dex */
    public static final class EventTransactProperty {
        public String descriptor;
        public int event;
        public String function;
        public HashMap<String, Object> property = new HashMap<>();

        public static String toJson(EventTransactProperty event) {
            if (event != null) {
                HashMap<String, Object> map = new HashMap<>();
                map.put("event", Integer.valueOf(event.event));
                map.put(xpInputManagerService.InputPolicyKey.KEY_FUNCTION, event.function);
                map.put("descriptor", event.descriptor);
                if (!event.property.isEmpty()) {
                    map.putAll(event.property);
                }
                return xpTextUtils.toJsonString(map);
            }
            return "";
        }
    }
}
