package com.xiaopeng.server.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.view.KeyEvent;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.sysconfig.SysConfigManager;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpSysConfigUtil;
import com.xiaopeng.util.xpTextUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class xpInputManagerService {
    public static final boolean ENABLE = true;
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
    private final HandlerThread mHandlerThread = new HandlerThread(TAG, 10);
    private PowerManager mPowerManager;
    public static final boolean XP_IMS_ENABLE = SystemProperties.getBoolean("persist.sys.xp.ims.enable", true);
    private static volatile InputPolicyList sPolicyList = new InputPolicyList();
    private static volatile HashMap<Integer, Boolean> sLongPressEvent = new HashMap<>();
    private static xpInputManagerService sService = null;

    /* loaded from: classes.dex */
    public static class InputPolicyKey {
        public static final String KEY_ACTIONS = "actions";
        public static final String KEY_ACTION_TYPE = "actionType";
        public static final String KEY_DISPATCH_CODE = "dispatchCode";
        public static final String KEY_DISPATCH_LISTENER = "dispatchListener";
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
        public static final String KEY_VERSION = "version";
    }

    /* loaded from: classes.dex */
    public static class InputPolicyList {
        public boolean init;
        public double version = 0.0d;
        public String product = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        public HashMap<Integer, InputPolicyInfo> events = new HashMap<>();
        public HashMap<Integer, List<Integer>> groups = new HashMap<>();
    }

    /* loaded from: classes.dex */
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
    }

    private void initPolicy() {
        final Runnable runnable = new Runnable() { // from class: com.xiaopeng.server.input.xpInputManagerService.1
            @Override // java.lang.Runnable
            public void run() {
                int oldMask = StrictMode.allowThreadDiskReadsMask();
                InputPolicyList inputPolicyList = InputPolicy.loadInputPolicy();
                if (inputPolicyList != null) {
                    InputPolicyList unused = xpInputManagerService.sPolicyList = inputPolicyList;
                    xpInputManagerService.this.initGroups();
                }
                StrictMode.setThreadPolicyMask(oldMask);
            }
        };
        this.mBgHandler.postDelayed(runnable, 1000L);
        xpSysConfigUtil.registerConfigUpdateListener(new SysConfigManager.SysConfigListener() { // from class: com.xiaopeng.server.input.-$$Lambda$xpInputManagerService$kmk46Ju3J1ewuyXJaaUfw3cR7Gs
            public final void onSysConfigUpdated(String str) {
                xpInputManagerService.lambda$initPolicy$0(xpInputManagerService.this, runnable, str);
            }
        });
    }

    public static /* synthetic */ void lambda$initPolicy$0(xpInputManagerService xpinputmanagerservice, Runnable runnable, String fileName) {
        if ("xui_input_event_policy.json".equals(fileName)) {
            xpinputmanagerservice.mBgHandler.post(runnable);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initGroups() {
        if (this.mGroupHandler != null && sPolicyList.init) {
            this.mGroupHandler.init(sPolicyList.groups);
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

    /* JADX WARN: Removed duplicated region for block: B:114:0x028b A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:71:0x025f  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public long handleKeyEventPolicy(com.android.server.policy.WindowManagerPolicy.WindowState r45, final android.view.KeyEvent r46, int r47, int r48) {
        /*
            Method dump skipped, instructions count: 1008
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.input.xpInputManagerService.handleKeyEventPolicy(com.android.server.policy.WindowManagerPolicy$WindowState, android.view.KeyEvent, int, int):long");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setAllScreenOn() {
        this.mPowerManager.setXpScreenOn("xp_mt_ivi", SystemClock.uptimeMillis());
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
        switch (seq) {
            case 0:
                return NAME_INTERCEPT_KEY_BEFORE_DISPATCHING;
            case 1:
                return NAME_DISPATCH_UNHANDLED_KEY;
            default:
                return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
    }

    /* loaded from: classes.dex */
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

    /* loaded from: classes.dex */
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

    /* loaded from: classes.dex */
    public static class InputPolicy {
        private static final String POLICY_FILE_NAME = "xui_input_event_policy.json";
        public static final Comparator<InputPolicyAction> sPriorityComparator = new Comparator<InputPolicyAction>() { // from class: com.xiaopeng.server.input.xpInputManagerService.InputPolicy.1
            @Override // java.util.Comparator
            public final int compare(InputPolicyAction a, InputPolicyAction b) {
                if (a == null || b == null || a.priority == b.priority) {
                    return 0;
                }
                return a.priority > b.priority ? -1 : 1;
            }
        };

        public static InputPolicyList loadInputPolicy() {
            JSONObject root;
            JSONArray events;
            String content = xpSysConfigUtil.getConfigContent(POLICY_FILE_NAME);
            if (TextUtils.isEmpty(content)) {
                return null;
            }
            try {
                JSONObject root2 = new JSONObject(content);
                InputPolicyList inputPolicyList = new InputPolicyList();
                inputPolicyList.version = xpTextUtils.toDouble(xpTextUtils.getValue("version", root2), Double.valueOf(0.0d)).doubleValue();
                inputPolicyList.product = xpTextUtils.toString(xpTextUtils.getValue("product", root2));
                if (root2.has(InputPolicyKey.KEY_EVENTS) && (events = root2.getJSONArray(InputPolicyKey.KEY_EVENTS)) != null) {
                    int length = events.length();
                    for (int i = 0; i < length; i++) {
                        HashMap<Integer, InputPolicyInfo> map = parseInputPolicyItem(events.getJSONObject(i));
                        if (map != null && !map.isEmpty()) {
                            inputPolicyList.events.putAll(map);
                        }
                    }
                }
                if (root2.has(InputPolicyKey.KEY_GROUPS)) {
                    HashMap<Integer, List<Integer>> groups = new HashMap<>();
                    JSONArray jgroups = root2.getJSONArray(InputPolicyKey.KEY_GROUPS);
                    if (jgroups != null) {
                        int length2 = jgroups.length();
                        int i2 = 0;
                        while (i2 < length2) {
                            JSONObject object = jgroups.getJSONObject(i2);
                            int keycode = getInteger(object, InputPolicyKey.KEY_KEY_CODE, 0);
                            String groupText = xpTextUtils.toString(xpTextUtils.getValue(InputPolicyKey.KEY_KEY_GROUP, object));
                            List<String> list = asList(groupText);
                            int size = list != null ? list.size() : 0;
                            if (keycode <= 0 || size <= 0) {
                                root = root2;
                            } else {
                                List<Integer> group = new ArrayList<>();
                                int j = 0;
                                while (j < size) {
                                    JSONObject root3 = root2;
                                    int var = xpTextUtils.toInteger(list.get(j), 0).intValue();
                                    if (var > 0) {
                                        group.add(Integer.valueOf(var));
                                    }
                                    j++;
                                    root2 = root3;
                                }
                                root = root2;
                                xpLogger.i(xpInputManagerService.TAG, "loadInputPolicy groups keycode=" + keycode + " groups=" + Arrays.toString(group.toArray()));
                                if (group.size() > 0) {
                                    groups.put(Integer.valueOf(keycode), group);
                                }
                            }
                            i2++;
                            root2 = root;
                        }
                    }
                    if (groups.size() > 0) {
                        inputPolicyList.groups.putAll(groups);
                    }
                }
                inputPolicyList.init = true;
                xpLogger.i(xpInputManagerService.TAG, "loadInputPolicy version=" + inputPolicyList.version + " size=" + inputPolicyList.events.size());
                return inputPolicyList;
            } catch (Exception e) {
                xpLogger.i(xpInputManagerService.TAG, "loadInputPolicy e=" + e);
                return null;
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
                return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
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

    /* loaded from: classes.dex */
    public static class InputPolicyInfo {
        public int keyCode = 0;
        public String keyName = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        public int scanCode = 0;
        public int overrideCode = 0;
        public boolean enable = true;
        public int flags = 3;
        public int repeat = 0;
        public int actionType = 0;
        public List<InputPolicyAction> actions = new ArrayList();

        public String toString() {
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            buffer.append("info:");
            buffer.append(" keyCode=" + this.keyCode);
            buffer.append(" keyName=" + this.keyName);
            buffer.append(" scanCode=" + this.scanCode);
            buffer.append(" overrideCode=" + this.overrideCode);
            buffer.append(" enable=" + this.enable);
            buffer.append(" flags=" + this.flags);
            buffer.append(" repeat=" + this.repeat);
            return buffer.toString();
        }
    }

    /* loaded from: classes.dex */
    public static class InputPolicyAction {
        public String mode = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        public int priority = 0;
        public String sequence = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        public String function = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        public int dispatchListener = 0;
        public String excludeMode = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        public int dispatchCode = 0;
        public int ret = 0;

        public String toString() {
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
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

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class WorkHandler extends Handler {
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
