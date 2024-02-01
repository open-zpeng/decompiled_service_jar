package com.xiaopeng.server.input;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import com.xiaopeng.input.xpInputManager;
import com.xiaopeng.util.xpLogger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
/* loaded from: classes.dex */
public class xpInputGroupHandler {
    public static final boolean DEBUG = false;
    private static final long DEFAULT_KEY_EVENT_TIMEOUT = 3000;
    private static final int MSG_KEY_EVENT_UP_START = 10000;
    private static final String TAG = "xpInputGroupHandler";
    private Context mContext;
    private Handler mHandler = new Handler() { // from class: com.xiaopeng.server.input.xpInputGroupHandler.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int what = msg.what;
            if (what >= 10000) {
                xpInputGroupHandler.this.handleUpEvent(msg);
            }
        }
    };
    private static final List<Integer> sKeyGroupList = new ArrayList();
    private static final HashMap<Integer, KeyGroupInfo> sKeyGroupInfo = new HashMap<>();

    public xpInputGroupHandler(Context context) {
        this.mContext = context;
    }

    public void dispatchKeyEvent(KeyEvent event) {
        if (event == null || !isGroupEvent(event.getKeyCode())) {
            return;
        }
        handleDownEvent(event);
    }

    private void handleDownEvent(KeyEvent event) {
        if (event == null) {
            return;
        }
        int keycode = event.getKeyCode();
        boolean down = event.getAction() == 0;
        boolean groupEvent = isGroupEvent(keycode);
        if (groupEvent && down) {
            setKeyEvent(keycode, true);
            int what = 10000 + keycode;
            this.mHandler.removeMessages(what);
            this.mHandler.sendEmptyMessageDelayed(what, DEFAULT_KEY_EVENT_TIMEOUT);
            handleGroupEvent(keycode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUpEvent(Message msg) {
        if (msg != null) {
            int keycode = msg.what - 10000;
            setKeyEvent(keycode, false);
            handleGroupEvent(keycode);
        }
    }

    private void handleGroupEvent(int keycode) {
        synchronized (sKeyGroupInfo) {
            for (Integer num : sKeyGroupInfo.keySet()) {
                int var = num.intValue();
                try {
                    KeyGroupInfo kgi = sKeyGroupInfo.get(Integer.valueOf(var));
                    if (kgi != null) {
                        boolean groupEvent = kgi.isGroupEvent(keycode);
                        boolean groupPress = kgi.isGroupPress();
                        if (groupEvent) {
                            if (groupPress) {
                                if (!kgi.triggered) {
                                    xpInputManager.sendEvent(kgi.keycode);
                                    xpLogger.i(TAG, "handleGroupEvent sendEvent kgi.keycode=" + kgi.keycode);
                                    kgi.triggered = true;
                                }
                            } else {
                                kgi.triggered = false;
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }
        }
    }

    public void init(HashMap<Integer, List<Integer>> data) {
        if (data != null && data.size() > 0) {
            synchronized (sKeyGroupInfo) {
                sKeyGroupInfo.clear();
                for (Integer num : data.keySet()) {
                    int keycode = num.intValue();
                    List<Integer> list = data.get(Integer.valueOf(keycode));
                    KeyGroupInfo info = new KeyGroupInfo();
                    info.keycode = keycode;
                    info.addGroup(list);
                    sKeyGroupInfo.put(Integer.valueOf(keycode), info);
                }
            }
            synchronized (sKeyGroupList) {
                sKeyGroupList.clear();
                for (Integer num2 : data.keySet()) {
                    List<Integer> list2 = data.get(Integer.valueOf(num2.intValue()));
                    sKeyGroupList.addAll(list2);
                }
            }
        }
    }

    public boolean isGroupEvent(int keycode) {
        boolean contains;
        synchronized (sKeyGroupList) {
            contains = sKeyGroupList.contains(Integer.valueOf(keycode));
        }
        return contains;
    }

    public void setKeyEvent(int keycode, boolean pressed) {
        synchronized (sKeyGroupInfo) {
            for (Integer num : sKeyGroupInfo.keySet()) {
                int var = num.intValue();
                KeyGroupInfo kgi = sKeyGroupInfo.get(Integer.valueOf(var));
                if (kgi != null) {
                    kgi.setKeyEvent(keycode, pressed);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class KeyGroupInfo {
        public List<KeyEventInfo> groups;
        public int keycode;
        public boolean triggered;

        private KeyGroupInfo() {
            this.triggered = false;
            this.groups = new ArrayList();
        }

        public void addGroup(List<Integer> groups) {
            if (groups != null && groups.size() > 0) {
                for (Integer num : groups) {
                    int var = num.intValue();
                    this.groups.add(new KeyEventInfo(var));
                }
            }
        }

        public boolean isGroupEvent(int keycode) {
            if (this.groups != null && !this.groups.isEmpty()) {
                for (KeyEventInfo info : this.groups) {
                    if (info != null && info.keycode == keycode) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }

        public boolean isGroupPress() {
            if (this.groups == null || this.groups.size() <= 0) {
                return true;
            }
            for (KeyEventInfo info : this.groups) {
                if (info != null && !info.pressed) {
                    return false;
                }
            }
            return true;
        }

        public void setKeyEvent(int keycode, boolean pressed) {
            if (this.groups != null && !this.groups.isEmpty()) {
                for (KeyEventInfo info : this.groups) {
                    if (info != null && info.keycode == keycode) {
                        info.pressed = pressed;
                        return;
                    }
                }
            }
        }

        public String toString() {
            StringBuffer buffer = new StringBuffer();
            buffer.append("KeyGroupInfo");
            buffer.append(" keycode=" + this.keycode);
            buffer.append(" triggered=" + this.triggered);
            buffer.append(" groups=" + Arrays.toString(this.groups.toArray()));
            return buffer.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class KeyEventInfo {
        public int keycode;
        public boolean pressed = false;

        public KeyEventInfo(int keycode) {
            this.keycode = keycode;
        }

        public String toString() {
            return "keycode=" + this.keycode + " pressed=" + this.pressed;
        }
    }
}
