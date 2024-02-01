package com.xiaopeng.server.input;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.IXPKeyListener;
import com.xiaopeng.input.IInputEventListener;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.SharedDisplayManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class xpInputManagerService {
    private static final String ACTION_POWER_STATE_CHANGED = "com.xiaopeng.intent.action.XUI_POWER_STATE_CHANGED";
    public static final boolean ENABLE = true;
    private static final int FLAG_ACTION_CANCEL = 8;
    private static final int FLAG_ACTION_DOWN = 1;
    private static final int FLAG_ACTION_MOVE = 4;
    private static final int FLAG_ACTION_POINTER_DOWN = 16;
    private static final int FLAG_ACTION_POINTER_UP = 32;
    private static final int FLAG_ACTION_UP = 2;
    public static final int KEYCODE_DEFAULT = 0;
    public static final int KEYCODE_UNKNOWN = -1;
    private static final String NAME_DISPATCH_UNHANDLED_KEY = "dispatchUnhandledKey";
    private static final String NAME_INTERCEPT_KEY_BEFORE_DISPATCHING = "interceptKeyBeforeDispatching";
    public static final String OR = "\\|";
    private static final String TAG = "xpInputManagerService";
    private static final int TYPE_DISPATCH_UNHANDLED_KEY = 1;
    private static final int TYPE_INTERCEPT_KEY_BEFORE_DISPATCHING = 0;
    private xpInputActionHandler mActionHandler;
    private final WorkHandler mBgHandler;
    private Context mContext;
    private xpInputGroupHandler mGroupHandler;
    private PowerManager mPowerManager;
    public static final boolean XP_IMS_ENABLE = SystemProperties.getBoolean("persist.sys.xp.ims.enable", true);
    private static volatile InputPolicyList sPolicyList = new InputPolicyList();
    private static volatile HashMap<Integer, Boolean> sLongPressEvent = new HashMap<>();
    private static xpInputManagerService sService = null;
    private int mDispatchPolicy = -1;
    private Object mPolicyLock = new Object();
    private RemoteCallbackList<IInputEventListener> mInputEventListeners = new RemoteCallbackList<>();
    private RemoteCallbackList<IXPKeyListener> mGlobalKeyInterceptListeners = new RemoteCallbackList<>();
    private HashMap<Integer, RemoteCallbackList<IXPKeyListener>> mSpecialKeyInterceptMap = new HashMap<>();
    private List<Integer> mGlobalInterceptList = new ArrayList();
    private SparseArray<Integer> mInputSourceArray = new SparseArray<>();
    private final int MSG_DISPATCH_SPECIAL_KEY = 1;
    private BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.server.input.xpInputManagerService.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            xpLogger.i(xpInputManagerService.TAG, "onReceive action=" + intent.getAction());
            if (xpInputManagerService.ACTION_POWER_STATE_CHANGED.equals(intent.getAction())) {
                int igState = intent.getIntExtra("android.intent.extra.IG_STATE", -1);
                int powerState = intent.getIntExtra("android.intent.extra.POWER_STATE", -1);
                int bootReason = intent.getIntExtra("android.intent.extra.BOOT_REASON", -1);
                xpLogger.i(xpInputManagerService.TAG, "onReceive power state igState=" + igState + " powerState=" + powerState + " bootReason=" + bootReason);
                if (igState == 0) {
                    xpInputManagerService.this.clearGlobalIntercept();
                }
            }
        }
    };
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);

    /* loaded from: classes2.dex */
    public static class InputPolicyKey {
        public static final String KEY_ACTIONS = "actions";
        public static final String KEY_ACTION_TYPE = "actionType";
        public static final String KEY_DISPATCH_CODE = "dispatchCode";
        public static final String KEY_DISPATCH_LISTENER = "dispatchListener";
        public static final String KEY_DISPATCH_POLICY = "dispatchPolicy";
        public static final String KEY_ENABLE = "enable";
        public static final String KEY_EVENTS = "events";
        public static final String KEY_EXCLUDE_MODE = "excludeMode";
        public static final String KEY_FLAGS = "flags";
        public static final String KEY_FUNCTION = "function";
        public static final String KEY_GROUPS = "groups";
        public static final String KEY_KEY_CODE = "keyCode";
        public static final String KEY_KEY_GROUP = "keyGroup";
        public static final String KEY_KEY_NAME = "keyName";
        public static final String KEY_MODE = "mode";
        public static final String KEY_OVERRIDE_CODE = "overrideCode";
        public static final String KEY_PRIORITY = "priority";
        public static final String KEY_PRODUCT = "product";
        public static final String KEY_REPEAT = "repeat";
        public static final String KEY_RETURN = "return";
        public static final String KEY_SCAN_CODE = "scanCode";
        public static final String KEY_SEQUENCE = "sequence";
        public static final String KEY_SOURCE = "source";
        public static final String KEY_VERSION = "version";
    }

    /* loaded from: classes2.dex */
    public static class InputPolicyList {
        public boolean init;
        public double version = 0.0d;
        public String product = "";
        public HashMap<Integer, InputPolicyInfo> events = new HashMap<>();
        public HashMap<Integer, List<Integer>> groups = new HashMap<>();
    }

    /* loaded from: classes2.dex */
    public static class ReturnValues {
        public static final int DISPATCHING = 0;
        public static final int FLAG_DISPATCHING = 4;
        public static final int FLAG_INTERCEPTED = 8;
        public static final int FLAG_NEXT = 1;
        public static final int FLAG_STOP = 2;
        public static final int INTERCEPTED = -1;
        public static final boolean RET_FALSE = true;
        public static final int RET_NEXT_DISPATCHING = 5;
        public static final int RET_NEXT_INTERCEPTED = 9;
        public static final int RET_STOP_DISPATCHING = 6;
        public static final int RET_STOP_INTERCEPTED = 10;
        public static final boolean RET_TRUE = true;
    }

    public xpInputManagerService(Context context) {
        this.mContext = null;
        this.mContext = context;
        this.mGroupHandler = new xpInputGroupHandler(this.mContext);
        this.mActionHandler = new xpInputActionHandler(this.mContext);
        this.mHandlerThread.start();
        this.mBgHandler = new WorkHandler(this.mHandlerThread.getLooper());
        this.mPowerManager = (PowerManager) this.mContext.getSystemService("power");
    }

    public static xpInputManagerService get(Context context) {
        if (sService == null) {
            synchronized (xpInputManagerService.class) {
                if (sService == null) {
                    sService = new xpInputManagerService(context);
                }
            }
        }
        return sService;
    }

    public void init() {
        initPolicy();
        registerReceiver();
    }

    private void initPolicy() {
        Runnable runnable = new Runnable() { // from class: com.xiaopeng.server.input.xpInputManagerService.1
            @Override // java.lang.Runnable
            public void run() {
                int oldMask = StrictMode.allowThreadDiskReadsMask();
                InputPolicy.loadInputPolicy();
                xpInputManagerService.this.initGroups();
                StrictMode.setThreadPolicyMask(oldMask);
            }
        };
        this.mBgHandler.postDelayed(runnable, 1000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initGroups() {
        if (this.mGroupHandler != null && sPolicyList.init) {
            this.mGroupHandler.init(sPolicyList.groups);
        }
    }

    private void registerReceiver() {
        try {
            xpLogger.i(TAG, "registerReceiver");
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_POWER_STATE_CHANGED);
            this.mContext.registerReceiver(this.mReceiver, filter);
        } catch (Exception e) {
            xpLogger.i(TAG, "registerReceiver e=" + e);
        }
    }

    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        if (event != null && !this.mActionHandler.inputEventValid(event.getKeyCode())) {
            return -1;
        }
        return 0;
    }

    public long interceptKeyBeforeDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        return handleKeyEventPolicy(win, event, policyFlags, 0);
    }

    public KeyEvent dispatchUnhandledKey(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        handleKeyEventPolicy(win, event, policyFlags, 1);
        return null;
    }

    public int requestInputPolicy(InputEvent event, int flags) {
        int policy;
        if (event == null || !SharedDisplayManager.enable()) {
            return -1;
        }
        try {
            int policy2 = getInputPolicy();
            if (policy2 >= 0) {
                return policy2;
            }
            int policy3 = this.mInputSourceArray.get(event.getSource(), -1).intValue();
            if (policy3 >= 0) {
                return policy3;
            }
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.getSource() == 268435457) {
                    xpLogger.i(TAG, "requestInputPolicy dispatch to primary screen");
                    return 0;
                }
                int keycode = keyEvent.getKeyCode();
                InputPolicyInfo ipi = sPolicyList.events.get(Integer.valueOf(keycode));
                if (ipi != null && (policy = ipi.dispatchPolicy) >= 0) {
                    return policy;
                }
                int policy4 = getKeyEventPolicy(keyEvent);
                if (policy4 >= 0) {
                    return policy4;
                }
            }
            if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                int policy5 = getMotionEventPolicy(motionEvent);
                if (policy5 >= 0) {
                    return policy5;
                }
            }
            return SystemProperties.getInt("persist.sys.xp.ims.policy", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    public void setInputPolicy(int policy) {
        synchronized (this.mPolicyLock) {
            this.mDispatchPolicy = policy;
        }
    }

    public int getInputPolicy() {
        int i;
        synchronized (this.mPolicyLock) {
            i = this.mDispatchPolicy;
        }
        return i;
    }

    public void setInputSourcePolicy(int inputSource, int policy) {
        synchronized (this.mInputSourceArray) {
            this.mInputSourceArray.put(inputSource, Integer.valueOf(policy));
        }
    }

    public void registerInputListener(IInputEventListener listener, String id) {
        synchronized (this.mInputEventListeners) {
            if (listener != null) {
                this.mInputEventListeners.register(listener, id);
            }
        }
    }

    public void unregisterInputListener(IInputEventListener listener, String id) {
        synchronized (this.mInputEventListeners) {
            if (listener != null) {
                this.mInputEventListeners.unregister(listener);
            }
        }
    }

    public void dispatchInputEventToListener(final InputEvent event, final String extra) {
        if (event == null) {
            return;
        }
        synchronized (this.mInputEventListeners) {
            boolean hasTargetEvent = false;
            int count = this.mInputEventListeners.getRegisteredCallbackCount();
            if (count <= 0) {
                return;
            }
            for (int i = 0; i < count; i++) {
                try {
                    if (event instanceof MotionEvent) {
                        Object cookie = this.mInputEventListeners.getRegisteredCallbackCookie(i);
                        if (hasTargetEventListener(event, cookie, extra)) {
                            hasTargetEvent = true;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (hasTargetEvent) {
                Runnable runnable = new Runnable() { // from class: com.xiaopeng.server.input.-$$Lambda$xpInputManagerService$V4zlwt06c5MyaA3nTzMGPNHW3xU
                    @Override // java.lang.Runnable
                    public final void run() {
                        xpInputManagerService.this.lambda$dispatchInputEventToListener$0$xpInputManagerService(event, extra);
                    }
                };
                this.mBgHandler.post(runnable);
            }
        }
    }

    public /* synthetic */ void lambda$dispatchInputEventToListener$0$xpInputManagerService(InputEvent event, String extra) {
        synchronized (this.mInputEventListeners) {
            int size = this.mInputEventListeners.beginBroadcast();
            if (size > 0) {
                for (int i = 0; i < size; i++) {
                    try {
                        if (event instanceof MotionEvent) {
                            Object cookie = this.mInputEventListeners.getRegisteredCallbackCookie(i);
                            if (hasTargetEventListener(event, cookie, extra)) {
                                this.mInputEventListeners.getBroadcastItem(i).onTouchEvent((MotionEvent) event, extra);
                            }
                        } else if (event instanceof KeyEvent) {
                            this.mInputEventListeners.getBroadcastItem(i).onKeyEvent((KeyEvent) event, extra);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            this.mInputEventListeners.finishBroadcast();
        }
    }

    public boolean hasTargetEventListener(InputEvent event, Object cookie, String extra) {
        if (event == null) {
            return false;
        }
        boolean hasTarget = false;
        int action = ((MotionEvent) event).getAction();
        int pointerAction = action & 255;
        String cookieText = cookie != null ? cookie.toString() : "";
        int flags = ((Integer) xpTextUtils.getValue(InputPolicyKey.KEY_FLAGS, cookieText, 3)).intValue();
        if (action != 0) {
            if (action != 1) {
                if (action != 2) {
                    if (action == 3 && (flags & 8) == 8) {
                        hasTarget = true;
                    }
                } else if ((flags & 4) == 4) {
                    hasTarget = true;
                }
            } else if ((flags & 2) == 2) {
                hasTarget = true;
            }
        } else if ((flags & 1) == 1) {
            hasTarget = true;
        }
        if (pointerAction == 5) {
            if ((flags & 16) == 16) {
                hasTarget = true;
            }
        } else if (pointerAction == 6 && (flags & 32) == 32) {
            hasTarget = true;
        }
        if (!hasTarget) {
            return false;
        }
        return true;
    }

    public void registerGlobalKeyInterceptListener(IXPKeyListener listener, String id, int callingPid) {
        synchronized (this.mGlobalKeyInterceptListeners) {
            if (listener != null) {
                this.mGlobalKeyInterceptListeners.register(listener, id);
            }
        }
        requestGlobalIntercept(callingPid, true);
    }

    public void unregisterGlobalKeyInterceptListener(IXPKeyListener listener, String id, int callingPid) {
        synchronized (this.mGlobalKeyInterceptListeners) {
            if (listener != null) {
                this.mGlobalKeyInterceptListeners.unregister(listener);
            }
        }
        requestGlobalIntercept(callingPid, false);
    }

    public void registerSpecialKeyInterceptListener(int[] keys, IXPKeyListener listener, String id) {
        if (keys != null && keys.length > 0 && listener != null) {
            synchronized (this.mSpecialKeyInterceptMap) {
                for (int i : keys) {
                    Integer key = Integer.valueOf(i);
                    if (this.mSpecialKeyInterceptMap.containsKey(key)) {
                        this.mSpecialKeyInterceptMap.get(key).register(listener);
                    } else {
                        RemoteCallbackList<IXPKeyListener> list = new RemoteCallbackList<>();
                        list.register(listener);
                        this.mSpecialKeyInterceptMap.put(key, list);
                    }
                }
            }
        }
    }

    public void unregisterSpecialKeyInterceptListener(int[] keys, IXPKeyListener listener, String id) {
        if (keys != null && keys.length > 0 && listener != null) {
            synchronized (this.mSpecialKeyInterceptMap) {
                for (int i : keys) {
                    Integer key = Integer.valueOf(i);
                    if (this.mSpecialKeyInterceptMap.containsKey(key)) {
                        RemoteCallbackList<IXPKeyListener> list = this.mSpecialKeyInterceptMap.get(key);
                        list.unregister(listener);
                        if (list.getRegisteredCallbackCount() == 0) {
                            this.mSpecialKeyInterceptMap.remove(key);
                        }
                    }
                }
            }
        }
    }

    public void dispatchKeyEventToInterceptListener(final KeyEvent event, final String extra) {
        if (event == null) {
            return;
        }
        synchronized (this.mGlobalKeyInterceptListeners) {
            int count = this.mGlobalKeyInterceptListeners.getRegisteredCallbackCount();
            if (count <= 0) {
                return;
            }
            Runnable runnable = new Runnable() { // from class: com.xiaopeng.server.input.-$$Lambda$xpInputManagerService$Yezs4Zxx7pF2v471ALlZiC8lTpU
                @Override // java.lang.Runnable
                public final void run() {
                    xpInputManagerService.this.lambda$dispatchKeyEventToInterceptListener$1$xpInputManagerService(event, extra);
                }
            };
            this.mBgHandler.post(runnable);
        }
    }

    public /* synthetic */ void lambda$dispatchKeyEventToInterceptListener$1$xpInputManagerService(KeyEvent event, String extra) {
        synchronized (this.mGlobalKeyInterceptListeners) {
            int size = this.mGlobalKeyInterceptListeners.beginBroadcast();
            if (size > 0) {
                for (int i = 0; i < size; i++) {
                    try {
                        this.mGlobalKeyInterceptListeners.getBroadcastItem(i).notify(event, extra);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            this.mGlobalKeyInterceptListeners.finishBroadcast();
        }
    }

    public void dispatchKeyEventToInterceptListener(RemoteCallbackList<IXPKeyListener> callbackList, KeyEvent event, String extra) {
        if (event == null || callbackList == null) {
            return;
        }
        int count = callbackList.getRegisteredCallbackCount();
        if (count <= 0) {
            return;
        }
        synchronized (callbackList) {
            int size = callbackList.beginBroadcast();
            xpLogger.d(TAG, "dispatchKeyEventToInterceptListener beginBroadcast size:" + size);
            if (size > 0) {
                for (int i = 0; i < size; i++) {
                    try {
                        callbackList.getBroadcastItem(i).notify(event, extra);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            callbackList.finishBroadcast();
            xpLogger.d(TAG, "dispatchKeyEventToInterceptListener finishBroadcast");
        }
    }

    private void requestGlobalIntercept(int callingPid, boolean register) {
        if (register) {
            if (!this.mGlobalInterceptList.contains(Integer.valueOf(callingPid))) {
                this.mGlobalInterceptList.add(Integer.valueOf(callingPid));
                return;
            }
            return;
        }
        this.mGlobalInterceptList.remove(Integer.valueOf(callingPid));
    }

    private String getGlobalInterceptInfo() {
        StringBuffer sb = new StringBuffer();
        if (this.mGlobalInterceptList.size() > 0) {
            for (Integer id : this.mGlobalInterceptList) {
                sb.append(String.valueOf(id));
                sb.append("|");
            }
        }
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearGlobalIntercept() {
        this.mGlobalInterceptList.clear();
        int size = this.mGlobalKeyInterceptListeners.getRegisteredCallbackCount();
        if (size > 0) {
            List<IXPKeyListener> listeners = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                listeners.add(this.mGlobalKeyInterceptListeners.getRegisteredCallbackItem(i));
            }
            for (IXPKeyListener listener : listeners) {
                this.mGlobalKeyInterceptListeners.unregister(listener);
            }
        }
    }

    private int getKeyEventPolicy(KeyEvent event) {
        return -1;
    }

    private int getMotionEventPolicy(MotionEvent event) {
        return -1;
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:84:0x02e0  */
    /* JADX WARN: Removed duplicated region for block: B:87:0x0310  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public long handleKeyEventPolicy(com.android.server.policy.WindowManagerPolicy.WindowState r48, final android.view.KeyEvent r49, int r50, int r51) {
        /*
            Method dump skipped, instructions count: 1147
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.input.xpInputManagerService.handleKeyEventPolicy(com.android.server.policy.WindowManagerPolicy$WindowState, android.view.KeyEvent, int, int):long");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setAllScreenOn() {
        this.mPowerManager.setXpScreenOn("xp_mt_ivi", SystemClock.uptimeMillis());
        this.mPowerManager.setXpScreenOn("xp_mt_psg", SystemClock.uptimeMillis());
        this.mPowerManager.setXpIcmScreenOn(SystemClock.uptimeMillis());
    }

    public boolean matchMode(int mode, InputPolicyAction action, List<InputPolicyAction> actions) {
        HashMap<String, Integer> maps = xpInputActionHandler.sModeMapping;
        if (action == null || actions == null || maps == null) {
            return false;
        }
        int priority = action.priority;
        List<String> includeList = new ArrayList<>();
        List<String> excludeList = new ArrayList<>();
        includeList.addAll(Arrays.asList(xpTextUtils.toStringArray(action.mode, OR)));
        for (InputPolicyAction a : actions) {
            if (a != null && priority < a.priority && !TextUtils.isEmpty(a.excludeMode)) {
                excludeList.addAll(Arrays.asList(xpTextUtils.toStringArray(a.excludeMode, OR)));
            }
        }
        for (String var : includeList) {
            if (!TextUtils.isEmpty(var) && !excludeList.contains(var) && maps.containsKey(var)) {
                int flag = maps.get(var).intValue();
                if ((mode & flag) == flag) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean matchSequence(int seq, InputPolicyAction action) {
        List<String> list;
        if (action == null || (list = Arrays.asList(xpTextUtils.toStringArray(action.sequence, OR))) == null || list.isEmpty()) {
            return false;
        }
        String name = getSeqName(seq);
        return !TextUtils.isEmpty(name) && list.contains(name);
    }

    public static boolean keycodeValid(int keycode) {
        return keycode > 0 && keycode <= KeyEvent.getMaxKeyCode();
    }

    public static boolean isDefault(int keycode) {
        return keycode == 0;
    }

    public static boolean isUnknown(int keycode) {
        return keycode == -1;
    }

    public static int getOverrideCode(int keycode, int overrideCode, int dispatchCode) {
        if (keycodeValid(dispatchCode)) {
            return dispatchCode;
        }
        if (keycodeValid(overrideCode)) {
            return overrideCode;
        }
        if (isUnknown(dispatchCode)) {
            return dispatchCode;
        }
        if (isUnknown(overrideCode)) {
            return overrideCode;
        }
        return keycode;
    }

    public static void dispatchToListener(KeyEvent event, int policyFlags) {
        InputManager.getInstance().dispatchKeyToListener(event, policyFlags);
    }

    public static String getSeqName(int seq) {
        if (seq != 0) {
            if (seq != 1) {
                return "";
            }
            return NAME_DISPATCH_UNHANDLED_KEY;
        }
        return NAME_INTERCEPT_KEY_BEFORE_DISPATCHING;
    }

    /* loaded from: classes2.dex */
    public static class ThreadExecutor {
        private static final ExecutorService sThreadPool = Executors.newFixedThreadPool(4);

        public static void execute(final Runnable runnable, final Runnable callback, final int priority) {
            try {
                if (!sThreadPool.isShutdown()) {
                    sThreadPool.execute(new Runnable() { // from class: com.xiaopeng.server.input.-$$Lambda$xpInputManagerService$ThreadExecutor$YO919jycskWp3wCqxKalK9tcyIU
                        @Override // java.lang.Runnable
                        public final void run() {
                            xpInputManagerService.ThreadExecutor.lambda$execute$0(priority, runnable, callback);
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
    }

    /* loaded from: classes2.dex */
    public static class InputRecorder {
        public int dispatchListener;
        public String function;
        public int keycode;
        public String keyname;
        public String mode;
        public int overrideCode;
        public int source;
        public String win;

        public InputRecorder(int keycode) {
            this.keycode = keycode;
        }

        public InputRecorder(int keycode, int overrideCode) {
            this.keycode = keycode;
            this.overrideCode = overrideCode;
        }

        public InputRecorder(int keycode, int overrideCode, int source, String keyname, String win, String mode, String function, int dispatchListener) {
            this.keycode = keycode;
            this.overrideCode = overrideCode;
            this.source = source;
            this.keyname = keyname;
            this.win = win;
            this.mode = mode;
            this.function = function;
            this.dispatchListener = dispatchListener;
        }
    }

    /* loaded from: classes2.dex */
    public static class InputPolicy {
        private static final String POLICY_FILE_DATA = "/data/xuiservice/xui_input_event_policy.json";
        private static final String POLICY_FILE_SYSTEM = "/system/etc/xui_input_event_policy.json";
        public static final Comparator<InputPolicyAction> sPriorityComparator = new Comparator<InputPolicyAction>() { // from class: com.xiaopeng.server.input.xpInputManagerService.InputPolicy.1
            @Override // java.util.Comparator
            public final int compare(InputPolicyAction a, InputPolicyAction b) {
                if (a == null || b == null || a.priority == b.priority) {
                    return 0;
                }
                return a.priority > b.priority ? -1 : 1;
            }
        };

        public static void loadInputPolicy() {
            JSONArray jgroups;
            File dataFile;
            File dataFile2 = new File(POLICY_FILE_DATA);
            File systemFile = new File(POLICY_FILE_SYSTEM);
            String content = xpTextUtils.getValueByVersion(dataFile2, systemFile, "version");
            if (TextUtils.isEmpty(content)) {
                return;
            }
            try {
                JSONObject root = new JSONObject(content);
                xpInputManagerService.sPolicyList.version = xpTextUtils.toDouble(xpTextUtils.getValue("version", root), Double.valueOf(0.0d)).doubleValue();
                xpInputManagerService.sPolicyList.product = xpTextUtils.toString(xpTextUtils.getValue("product", root));
                if (root.has(InputPolicyKey.KEY_EVENTS)) {
                    try {
                        JSONArray events = root.getJSONArray(InputPolicyKey.KEY_EVENTS);
                        if (events != null) {
                            int length = events.length();
                            for (int i = 0; i < length; i++) {
                                HashMap<Integer, InputPolicyInfo> map = parseInputPolicyItem(events.getJSONObject(i));
                                if (map != null && !map.isEmpty()) {
                                    xpInputManagerService.sPolicyList.events.putAll(map);
                                }
                            }
                        }
                    } catch (Exception e) {
                        e = e;
                        xpLogger.i(xpInputManagerService.TAG, "loadInputPolicy e=" + e);
                        return;
                    }
                }
                if (root.has(InputPolicyKey.KEY_GROUPS)) {
                    HashMap<Integer, List<Integer>> groups = new HashMap<>();
                    JSONArray jgroups2 = root.getJSONArray(InputPolicyKey.KEY_GROUPS);
                    if (jgroups2 != null) {
                        int length2 = jgroups2.length();
                        int i2 = 0;
                        while (i2 < length2) {
                            JSONObject object = jgroups2.getJSONObject(i2);
                            int keycode = getInteger(object, InputPolicyKey.KEY_KEY_CODE, 0);
                            String groupText = xpTextUtils.toString(xpTextUtils.getValue(InputPolicyKey.KEY_KEY_GROUP, object));
                            List<String> list = asList(groupText);
                            int size = list != null ? list.size() : 0;
                            if (keycode <= 0 || size <= 0) {
                                jgroups = jgroups2;
                                dataFile = dataFile2;
                            } else {
                                List<Integer> group = new ArrayList<>();
                                int j = 0;
                                while (j < size) {
                                    JSONArray jgroups3 = jgroups2;
                                    File dataFile3 = dataFile2;
                                    try {
                                        int var = xpTextUtils.toInteger(list.get(j), 0).intValue();
                                        if (var > 0) {
                                            group.add(Integer.valueOf(var));
                                        }
                                        j++;
                                        jgroups2 = jgroups3;
                                        dataFile2 = dataFile3;
                                    } catch (Exception e2) {
                                        e = e2;
                                        xpLogger.i(xpInputManagerService.TAG, "loadInputPolicy e=" + e);
                                        return;
                                    }
                                }
                                jgroups = jgroups2;
                                dataFile = dataFile2;
                                xpLogger.i(xpInputManagerService.TAG, "loadInputPolicy groups keycode=" + keycode + " groups=" + Arrays.toString(group.toArray()));
                                if (group.size() > 0) {
                                    groups.put(Integer.valueOf(keycode), group);
                                }
                            }
                            i2++;
                            jgroups2 = jgroups;
                            dataFile2 = dataFile;
                        }
                    }
                    if (groups.size() > 0) {
                        xpInputManagerService.sPolicyList.groups.putAll(groups);
                    }
                }
                xpInputManagerService.sPolicyList.init = true;
                xpLogger.i(xpInputManagerService.TAG, "loadInputPolicy version=" + xpInputManagerService.sPolicyList.version + " size=" + xpInputManagerService.sPolicyList.events.size());
            } catch (Exception e3) {
                e = e3;
            }
        }

        private static HashMap<Integer, InputPolicyInfo> parseInputPolicyItem(JSONObject object) {
            if (object == null) {
                return null;
            }
            try {
                HashMap<Integer, InputPolicyInfo> events = new HashMap<>();
                String content = xpTextUtils.toString(xpTextUtils.getValue(InputPolicyKey.KEY_KEY_CODE, object));
                List<String> list = asList(content);
                int size = list != null ? list.size() : 0;
                if (size <= 0) {
                    return null;
                }
                JSONArray actions = object.getJSONArray(InputPolicyKey.KEY_ACTIONS);
                for (int i = 0; i < size; i++) {
                    int keycode = xpTextUtils.toInteger(list.get(i), 0).intValue();
                    if (keycode >= 0) {
                        InputPolicyInfo info = new InputPolicyInfo();
                        info.keyCode = keycode;
                        info.keyName = getString(object, InputPolicyKey.KEY_KEY_NAME, i);
                        info.scanCode = getInteger(object, InputPolicyKey.KEY_SCAN_CODE, i, 0);
                        info.overrideCode = getInteger(object, InputPolicyKey.KEY_OVERRIDE_CODE, i, 0);
                        boolean z = true;
                        if (getInteger(object, InputPolicyKey.KEY_ENABLE, 1) != 1) {
                            z = false;
                        }
                        info.enable = z;
                        info.flags = getInteger(object, InputPolicyKey.KEY_FLAGS, 0);
                        info.repeat = getInteger(object, InputPolicyKey.KEY_REPEAT, 0);
                        info.actionType = getInteger(object, InputPolicyKey.KEY_ACTION_TYPE, 0);
                        info.dispatchPolicy = getInteger(object, InputPolicyKey.KEY_DISPATCH_POLICY, -1);
                        info.source = getInteger(object, InputPolicyKey.KEY_SOURCE, 268435457);
                        info.actions = parseInputPolicyActions(actions, i);
                        if (info.actions != null) {
                            info.actions.sort(sPriorityComparator);
                        }
                        events.put(Integer.valueOf(keycode), info);
                    }
                }
                return events;
            } catch (Exception e) {
                xpLogger.i(xpInputManagerService.TAG, "parseInputPolicyItem e=" + e);
                return null;
            }
        }

        private static List<InputPolicyAction> parseInputPolicyActions(JSONArray actions, int index) {
            int size = actions != null ? actions.length() : 0;
            if (size <= 0) {
                return null;
            }
            List<InputPolicyAction> list = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                try {
                    JSONObject object = actions.getJSONObject(i);
                    if (object != null) {
                        InputPolicyAction action = new InputPolicyAction();
                        action.mode = xpTextUtils.toString(xpTextUtils.getValue(InputPolicyKey.KEY_MODE, object));
                        action.priority = xpTextUtils.toInteger(xpTextUtils.getValue(InputPolicyKey.KEY_PRIORITY, object), 0).intValue();
                        action.sequence = xpTextUtils.toString(xpTextUtils.getValue(InputPolicyKey.KEY_SEQUENCE, object));
                        action.function = xpTextUtils.toString(xpTextUtils.getValue(InputPolicyKey.KEY_FUNCTION, object));
                        action.dispatchListener = xpTextUtils.toInteger(xpTextUtils.getValue(InputPolicyKey.KEY_DISPATCH_LISTENER, object), 0).intValue();
                        action.excludeMode = xpTextUtils.toString(xpTextUtils.getValue(InputPolicyKey.KEY_EXCLUDE_MODE, object));
                        action.dispatchCode = getInteger(object, InputPolicyKey.KEY_DISPATCH_CODE, index, 0);
                        action.ret = xpTextUtils.toInteger(xpTextUtils.getValue(InputPolicyKey.KEY_RETURN, object), 0).intValue();
                        list.add(action);
                    }
                } catch (Exception e) {
                    xpLogger.log(xpInputManagerService.TAG, "parseInputPolicyActions e=" + e.getStackTrace());
                }
            }
            return list;
        }

        private static String getString(JSONObject object, String name) {
            return xpTextUtils.toString(xpTextUtils.getValue(name, object));
        }

        private static String getString(JSONObject object, String name, int index) {
            if (object == null || TextUtils.isEmpty(name) || index < 0) {
                return "";
            }
            List<String> list = asList(xpTextUtils.toString(xpTextUtils.getValue(name, object)));
            return xpTextUtils.toString(getValueByIndex(list, index));
        }

        private static int getInteger(JSONObject object, String name, int defaultValue) {
            return xpTextUtils.toInteger(xpTextUtils.getValue(name, object), Integer.valueOf(defaultValue)).intValue();
        }

        private static int getInteger(JSONObject object, String name, int index, int defaultValue) {
            if (object == null || TextUtils.isEmpty(name) || index < 0) {
                return defaultValue;
            }
            List<String> list = asList(xpTextUtils.toString(xpTextUtils.getValue(name, object)));
            return xpTextUtils.toInteger(getValueByIndex(list, index), Integer.valueOf(defaultValue)).intValue();
        }

        private static <T> Object getValueByIndex(List<T> list, int index) {
            if (list != null && !list.isEmpty() && index >= 0 && index < list.size()) {
                return list.get(index);
            }
            return null;
        }

        private static List<String> asList(String content) {
            if (TextUtils.isEmpty(content)) {
                return null;
            }
            return Arrays.asList(xpTextUtils.toStringArray(content, xpInputManagerService.OR));
        }
    }

    /* loaded from: classes2.dex */
    public static class InputPolicyInfo {
        public int keyCode = 0;
        public String keyName = "";
        public int scanCode = 0;
        public int overrideCode = 0;
        public boolean enable = true;
        public int flags = 3;
        public int repeat = 0;
        public int actionType = 0;
        public int dispatchPolicy = -1;
        public int source = 268435457;
        public List<InputPolicyAction> actions = new ArrayList();

        public String toString() {
            StringBuffer buffer = new StringBuffer("");
            buffer.append("info:");
            buffer.append(" keyCode=" + this.keyCode);
            buffer.append(" keyName=" + this.keyName);
            buffer.append(" scanCode=" + this.scanCode);
            buffer.append(" overrideCode=" + this.overrideCode);
            buffer.append(" enable=" + this.enable);
            buffer.append(" flags=" + this.flags);
            buffer.append(" repeat=" + this.repeat);
            buffer.append(" dispatchPolicy=" + this.dispatchPolicy);
            buffer.append(" source=" + this.source);
            return buffer.toString();
        }
    }

    /* loaded from: classes2.dex */
    public static class InputPolicyAction {
        public String mode = "";
        public int priority = 0;
        public String sequence = "";
        public String function = "";
        public int dispatchListener = 0;
        public String excludeMode = "";
        public int dispatchCode = 0;
        public int ret = 0;

        public String toString() {
            StringBuffer buffer = new StringBuffer("");
            buffer.append("action:");
            buffer.append(" mode=" + this.mode);
            buffer.append(" priority=" + this.priority);
            buffer.append(" sequence=" + this.sequence);
            buffer.append(" function=" + this.function);
            buffer.append(" dispatchListener=" + this.dispatchListener);
            buffer.append(" excludeMode=" + this.excludeMode);
            buffer.append(" dispatchCode=" + this.dispatchCode);
            buffer.append(" ret=" + this.ret);
            return buffer.toString();
        }
    }

    /* loaded from: classes2.dex */
    public static class DispatchEvent {
        public RemoteCallbackList<IXPKeyListener> mCallbackList;
        public String mExtra;
        public KeyEvent mKeyEvent;

        public DispatchEvent(KeyEvent event, RemoteCallbackList<IXPKeyListener> callbackList, String extra) {
            this.mKeyEvent = event;
            this.mCallbackList = callbackList;
            this.mExtra = extra;
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
            if (msg.what == 1) {
                DispatchEvent dispatchEvent = (DispatchEvent) msg.obj;
                xpInputManagerService.this.dispatchKeyEventToInterceptListener(dispatchEvent.mCallbackList, dispatchEvent.mKeyEvent, dispatchEvent.mExtra);
            }
        }
    }
}
