package com.android.server.policy.xkeymgr;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.view.KeyEvent;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.policy.WindowManagerPolicy;
/* loaded from: classes.dex */
public class DavidHandler extends KeyHandler {
    private static final long DEFAULT_LONG_PRESS_TIMEOUT = 5000;
    private static final String KEY_SPEECH_NEED_BACK = "speech_need_back";
    private static final int MSG_LONG_MEDIA_NEXT_TIMEOUT = 102;
    private static final int MSG_LONG_SPEECH_TIMEOUT = 101;
    private static final String TAG = "DavidHandler";
    private static final int XKEY_INVALID = -1;
    private static final String XP_SPEECH_INTENT = "xiaopeng.intent.action.UI_MIC_CLICK";
    private static final String XP_XKEY_INTENT = "com.xiaopeng.intent.action.xkey";
    private static final String XP_XKEY_USER_INTENT = "com.xiaopeng.intent.action.xkey.user";
    private Context mContext;
    private Handler mHandler;
    private static boolean sLogUploaded = false;
    private static boolean sSpeechLongPress = false;
    private static boolean sMediaNextLongPress = false;
    private int mSpeechNeedBack = 0;
    private SettingsObserver mSettingsObserver = new SettingsObserver();

    public DavidHandler(Context context) {
        this.mHandler = null;
        this.mContext = context;
        if (this.mSettingsObserver != null) {
            this.mSettingsObserver.register(true);
        }
        this.mHandler = new Handler(Looper.getMainLooper()) { // from class: com.android.server.policy.xkeymgr.DavidHandler.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case 101:
                    case 102:
                        DavidHandler.this.handleLoggerKeyEvent(msg);
                        return;
                    default:
                        return;
                }
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Removed duplicated region for block: B:20:0x0099  */
    /* JADX WARN: Removed duplicated region for block: B:21:0x009c  */
    @Override // com.android.server.policy.xkeymgr.KeyHandler
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean handleKeyBeforeDispatching(com.android.server.policy.WindowManagerPolicy.WindowState r10, android.view.KeyEvent r11, int r12) {
        /*
            Method dump skipped, instructions count: 412
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.xkeymgr.DavidHandler.handleKeyBeforeDispatching(com.android.server.policy.WindowManagerPolicy$WindowState, android.view.KeyEvent, int):boolean");
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.policy.xkeymgr.KeyHandler
    public boolean handleUnhandleKeyAfterDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        event.getKeyCode();
        StringBuilder sb = new StringBuilder();
        sb.append("handleUnhandleKeyAfterDispatching event=");
        sb.append(event != null ? event.toString() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        sb.append(" newKeyCode=");
        sb.append(0);
        XGlobalKeyManager.log(TAG, sb.toString());
        if (0 != 0) {
            if (isDownAction(event)) {
                super.dispatchKey(0);
                return true;
            }
            return true;
        }
        return false;
    }

    private boolean isDownAction(KeyEvent event) {
        if (event.getAction() == 0) {
            return true;
        }
        return false;
    }

    private void wakeupSpeech(int keyCode) {
        Intent intent = new Intent(XP_SPEECH_INTENT);
        intent.addFlags(16777216);
        if (keyCode == 1005) {
            intent.putExtra("location", "key_speech");
        }
        this.mContext.sendBroadcast(intent);
    }

    private void notifyNoOption() {
        Intent intent = new Intent(XP_XKEY_USER_INTENT);
        intent.addFlags(16777216);
        this.mContext.sendBroadcast(intent);
    }

    private void CustomKeyAction(String keytype, int keyfunc) {
        Intent intent = new Intent(XP_XKEY_INTENT);
        intent.addFlags(16777216);
        intent.putExtra("keytype", keytype);
        intent.putExtra("keyfunc", keyfunc);
        this.mContext.sendBroadcast(intent);
    }

    private void notifyLoggerKeyEvent(KeyEvent event) {
        if (event == null) {
            return;
        }
        int keyCode = event.getKeyCode();
        if (keyCode == 1025) {
            if (isDownAction(event)) {
                sSpeechLongPress = true;
                this.mHandler.removeMessages(101);
                this.mHandler.sendEmptyMessageDelayed(101, DEFAULT_LONG_PRESS_TIMEOUT);
            }
        } else if (keyCode == 1034 && isDownAction(event)) {
            sMediaNextLongPress = true;
            this.mHandler.removeMessages(102);
            this.mHandler.sendEmptyMessageDelayed(102, DEFAULT_LONG_PRESS_TIMEOUT);
        }
        if (sSpeechLongPress && sMediaNextLongPress && !sLogUploaded) {
            notifyLoggerBroadcast();
            sLogUploaded = true;
        }
        XGlobalKeyManager.log(TAG, "notifyLoggerKeyEvent event=" + event.getKeyCode() + " logUploaded=" + sLogUploaded + " speechLongPress=" + sSpeechLongPress + " mediaNextLongPress=" + sMediaNextLongPress);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLoggerKeyEvent(Message msg) {
        if (msg == null) {
            return;
        }
        switch (msg.what) {
            case 101:
                sSpeechLongPress = false;
                break;
            case 102:
                sMediaNextLongPress = false;
                break;
        }
        if (!sSpeechLongPress && !sMediaNextLongPress) {
            sLogUploaded = false;
        }
        XGlobalKeyManager.log(TAG, "handleLoggerKeyEvent msg.what=" + msg.what + " logUploaded=" + sLogUploaded + " speechLongPress=" + sSpeechLongPress + " mediaNextLongPress=" + sMediaNextLongPress);
    }

    private void notifyLoggerBroadcast() {
        try {
            XGlobalKeyManager.log(TAG, "notifyLoggerBroadcast");
            Intent intent = new Intent("com.xiaopeng.scu.ACTION_UP_LOAD_CAN_LOG_CS");
            intent.addFlags(16777216);
            this.mContext.sendBroadcast(intent);
        } catch (Exception e) {
            XGlobalKeyManager.log(TAG, "notifyLoggerBroadcast e=" + e);
        }
    }

    /* loaded from: classes.dex */
    final class SettingsObserver extends ContentObserver {
        public SettingsObserver() {
            super(null);
        }

        public void register(boolean register) {
            ContentResolver cr = DavidHandler.this.mContext.getContentResolver();
            if (register) {
                cr.registerContentObserver(Settings.Global.getUriFor("speech_need_back"), false, this);
            } else {
                cr.unregisterContentObserver(this);
            }
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (Settings.Global.getUriFor("speech_need_back").equals(uri)) {
                DavidHandler.this.mSpeechNeedBack = Settings.Global.getInt(DavidHandler.this.mContext.getContentResolver(), "speech_need_back", 0);
            }
        }
    }
}
