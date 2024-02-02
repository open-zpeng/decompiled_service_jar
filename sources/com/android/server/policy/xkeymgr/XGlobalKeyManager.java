package com.android.server.policy.xkeymgr;

import android.content.Context;
import android.media.IAudioService;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.view.KeyEvent;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.util.xpLogger;
import java.util.HashMap;
import java.util.Map;
/* loaded from: classes.dex */
public class XGlobalKeyManager {
    private static final boolean DBG = true;
    public static final int PLAYBACK_CMD_ENTER = 11;
    public static final int PLAYBACK_CMD_EXIT = 12;
    public static final int PLAYBACK_CMD_FAVORITE = 8;
    public static final int PLAYBACK_CMD_NEXT = 6;
    public static final int PLAYBACK_CMD_PLAY_PAUSE = 2;
    public static final int PLAYBACK_CMD_PREV = 7;
    public static final int PLAYBACK_CMD_REWIND = 13;
    public static final int PLAYBACK_CMD_SEEKTO = 4;
    public static final int PLAYBACK_CMD_SET_LYRIC = 10;
    public static final int PLAYBACK_CMD_SET_MODE = 9;
    public static final int PLAYBACK_CMD_SPEED = 5;
    public static final int PLAYBACK_CMD_START = 0;
    public static final int PLAYBACK_CMD_STOP = 1;
    private static final String TAG = "XGlobalKeyManager";
    private static XGlobalKeyManager mInstance;
    private static IAudioService sAudioService;
    private KeyHandler mHandler;
    public static final String PRODUCT_DEVICE_NAME = "ro.product.device";
    public static final String DEVICE = SystemProperties.get(PRODUCT_DEVICE_NAME, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
    private static final Map<Integer, Integer> sCmdMediaKeyMap = new HashMap<Integer, Integer>() { // from class: com.android.server.policy.xkeymgr.XGlobalKeyManager.1
        {
            put(126, 0);
            put(86, 1);
            put(85, 2);
            put(90, 5);
            put(89, 13);
            put(87, 6);
            put(88, 7);
        }
    };

    private XGlobalKeyManager(Context context) {
        log(TAG, "init KeyHandler deviceName=" + DEVICE);
        if ("e28".equals(DEVICE)) {
            this.mHandler = new EdwardHandler(context);
        } else {
            this.mHandler = new DavidHandler(context);
        }
    }

    public static synchronized XGlobalKeyManager getInstance(Context context) {
        XGlobalKeyManager xGlobalKeyManager;
        synchronized (XGlobalKeyManager.class) {
            if (mInstance == null) {
                mInstance = new XGlobalKeyManager(context);
            }
            xGlobalKeyManager = mInstance;
        }
        return xGlobalKeyManager;
    }

    public KeyHandler getKeyHandler() {
        return this.mHandler;
    }

    private void setKeyHandler(KeyHandler handler) {
        this.mHandler = handler;
    }

    public boolean processKeyEventBeforeDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        log(TAG, "processKeyEventBeforeDispatching KeyEvent: " + event.getKeyCode());
        if (this.mHandler == null) {
            return false;
        }
        return this.mHandler.handleKeyBeforeDispatching(win, event, policyFlags);
    }

    public boolean processUnhandleKeyEvent(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        log(TAG, "processUnhandleKeyEvent KeyEvent: " + event.getKeyCode());
        if (this.mHandler == null) {
            return false;
        }
        return this.mHandler.handleUnhandleKeyAfterDispatching(win, event, policyFlags);
    }

    public boolean checkKeyIgnored(int key, boolean down) {
        log(TAG, "checkKeyIgnored Key: " + key);
        if (this.mHandler == null) {
            return false;
        }
        return this.mHandler.checkKeyIgnored(key, down);
    }

    public static boolean hasMediaCommands(int keyCode) {
        return sCmdMediaKeyMap.containsKey(Integer.valueOf(keyCode));
    }

    public static void dispatchMediaKeyEvent(int keyCode) {
        try {
            IAudioService audio = getAudioService();
            if (audio != null && hasMediaCommands(keyCode)) {
                audio.playbackControl(sCmdMediaKeyMap.get(Integer.valueOf(keyCode)).intValue(), 0);
            }
        } catch (Exception e) {
            log(TAG, "dispatchMediaKeyEvent keyCode: " + keyCode + " e: " + e);
        }
    }

    private static IAudioService getAudioService() {
        if (sAudioService == null) {
            sAudioService = IAudioService.Stub.asInterface(ServiceManager.checkService("audio"));
        }
        return sAudioService;
    }

    public static void log(String tag, String msg) {
        xpLogger.log(tag, msg);
    }
}
