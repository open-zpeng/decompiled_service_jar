package com.android.server.policy.xkeymgr;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.policy.WindowManagerPolicy;
import com.xiaopeng.server.input.xpInputActionHandler;
import com.xiaopeng.xpInputDeviceWrapper;
import java.text.SimpleDateFormat;
import org.json.JSONException;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class EdwardHandler extends KeyHandler {
    private static final String KEY_AUTO_SHOW_AIRCONDITION_DISABLE = "airCondition_disable";
    private static final String KEY_AUTO_SHOW_SWITCH_MODE = "main_setting_disable";
    private static final String KEY_CHARGE_MODE = "key_vcu_charge_mode";
    private static final String KEY_ICM_LEFT_SWITCH_MODE = "key_icm_left_switch_mode";
    private static final String KEY_ICM_RIGHT_SWITCH_MODE = "key_icm_right_switch_mode";
    private static final String KEY_TOUCH_ROTATION_SPEED = "xp_touch_rotation_speed";
    public static final boolean LONG_CUSTOM_RESELECT = true;
    private static final int LONG_KEY_INTERVAL_TIME = 200;
    private static final int MSG_RESET_CAR_SWITCH_MODE = 101;
    private static final int MSG_RESET_ICM_SWITCH_MODE = 100;
    private static final int MSG_RESET_SYNC_SWITCH_MODE = 102;
    public static final int POS_BOSS = 1;
    public static final int POS_X = 0;
    private static final String TAG = "EdwardHandler";
    static final String TAG_KEYIGNORE = "key-ignore";
    public static final int TYPE_CDU = 0;
    public static final int TYPE_ICM = -1;
    public static final int TYPE_INTERCEPT = -2;
    private static final int XBOSS_INVALID = -1;
    private static final int XBOSS_LONG_NONE = 0;
    private static final int XBOSS_MUTE_UNMUTE = 2;
    private static final int XBOSS_VOICE_ACTIVE = 1;
    private static final int XKEY_INVALID = -1;
    private static final String XP_BOSSKEY_INTENT = "com.xiaopeng.intent.action.bosskey";
    private static final String XP_BOSSKEY_USER_INTENT = "com.xiaopeng.intent.action.bosskey.user";
    private static final String XP_EMERGENCY_IGOFF_INTENT = "com.xiaopeng.intent.action.emergency.igoff";
    private static final String XP_SPEECH_INTENT = "xiaopeng.intent.action.UI_MIC_CLICK";
    private static final String XP_XKEY_INTENT = "com.xiaopeng.intent.action.xkey";
    private static final String XP_XKEY_USER_INTENT = "com.xiaopeng.intent.action.xkey.user";
    private AudioManager mAudioManager;
    private boolean mChargingMode;
    private Context mContext;
    private Handler mHandler;
    private SettingsObserver mSettingsObserver;
    private xpIcmManager mxpIcmManager;
    private xpVehicleManager mxpVehicleManager;
    private static boolean sAutoShowMode = false;
    private static boolean sAutoShowAirCnnditionDisable = false;
    private static long mLastIcmEnterTime = 0;
    private static long mLastCarEnterTime = 0;
    private static boolean mIcmWheelkeySwitchMode = false;
    private static boolean mCarWheelkeySwitchMode = false;
    private static final boolean CFG_SWITCH_MODE_DELAY = SystemProperties.getBoolean("persist.sys.wheelkeymode.reset.enable", false);
    private static final long DEFAULT_RESET_SWITCH_MODE_DELAY = SystemProperties.getLong("persist.sys.wheelkeymode.reset.delay", 5000);
    private long longTouchTimeStamp = 0;
    private long longPressForBackTimeStamp = 0;
    private long longPressForCustomerTimeStamp = 0;
    private int allWheelkeyToIcm = SystemProperties.getInt("persist.sys.keyconfig.icm", 1);
    private boolean needSlideAudioEffect = true;
    private final int GEAR_LEVEL_D = 1;
    private final int GEAR_LEVEL_N = 2;
    private boolean mSupportNewSolution = true;
    private boolean mSupportStraightSlide = false;
    private int wheelkeySlideSensitivity = 2;
    private int mLastIcmSlideKeycode = 0;
    private int mIcmSldeKeyNum = 0;
    private int mLastCarSlideKeycode = 0;
    private int mCarSldeKeyNum = 0;
    private int mLastCarKeycodeForSlide = 0;
    private long mLastCarKeytime = 0;
    private int mLastIcmKeycodeForSlide = 0;
    private long mLastIcmKeytime = 0;
    private boolean mSupportShortEnterOut = true;

    public EdwardHandler(Context context) {
        this.mChargingMode = false;
        this.mContext = context;
        this.mAudioManager = (AudioManager) this.mContext.getSystemService("audio");
        this.mAudioManager.loadSoundEffects();
        this.mxpIcmManager = new xpIcmManager(context);
        this.mxpVehicleManager = new xpVehicleManager(context);
        this.mSettingsObserver = new SettingsObserver();
        if (this.mSettingsObserver != null) {
            this.mSettingsObserver.register(true);
        }
        getAndUpdateSlideSpeed();
        sAutoShowMode = Settings.System.getInt(this.mContext.getContentResolver(), "main_setting_disable", 0) == 1;
        sAutoShowAirCnnditionDisable = Settings.System.getInt(this.mContext.getContentResolver(), "airCondition_disable", 0) == 1;
        this.mChargingMode = Settings.System.getInt(this.mContext.getContentResolver(), "key_vcu_charge_mode", 0) == 1;
        this.mHandler = new Handler(Looper.getMainLooper()) { // from class: com.android.server.policy.xkeymgr.EdwardHandler.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case 100:
                        EdwardHandler.this.handleResetSwitchModeTask(0);
                        return;
                    case 101:
                        EdwardHandler.this.handleResetSwitchModeTask(1);
                        return;
                    case 102:
                        EdwardHandler.this.handleSyncSwitchModeTask();
                        return;
                    default:
                        return;
                }
            }
        };
        postSyncSwitchModeTask();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Removed duplicated region for block: B:108:0x0238  */
    /* JADX WARN: Removed duplicated region for block: B:113:0x0245  */
    /* JADX WARN: Removed duplicated region for block: B:119:0x025c  */
    /* JADX WARN: Removed duplicated region for block: B:266:0x0494 A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:272:0x04bf  */
    /* JADX WARN: Removed duplicated region for block: B:276:0x04c9 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:48:0x0179  */
    @Override // com.android.server.policy.xkeymgr.KeyHandler
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean handleKeyBeforeDispatching(com.android.server.policy.WindowManagerPolicy.WindowState r19, android.view.KeyEvent r20, int r21) {
        /*
            Method dump skipped, instructions count: 1410
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.xkeymgr.EdwardHandler.handleKeyBeforeDispatching(com.android.server.policy.WindowManagerPolicy$WindowState, android.view.KeyEvent, int):boolean");
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Removed duplicated region for block: B:31:0x010b  */
    /* JADX WARN: Removed duplicated region for block: B:34:0x0114  */
    @Override // com.android.server.policy.xkeymgr.KeyHandler
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public boolean handleUnhandleKeyAfterDispatching(com.android.server.policy.WindowManagerPolicy.WindowState r9, android.view.KeyEvent r10, int r11) {
        /*
            Method dump skipped, instructions count: 446
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.xkeymgr.EdwardHandler.handleUnhandleKeyAfterDispatching(com.android.server.policy.WindowManagerPolicy$WindowState, android.view.KeyEvent, int):boolean");
    }

    private boolean canDispatchInput(WindowManagerPolicy.WindowState win, KeyEvent event) {
        if (event.getKeyCode() == 1011 || event.getKeyCode() == 1012 || event.getKeyCode() == 1013 || event.getKeyCode() == 1014) {
            return true;
        }
        return (win.getAttrs().type == 6 || win.getAttrs().type == 2008 || win.getAttrs().type == 9) ? false : true;
    }

    private int dealCarStraightSlide(int keycode) {
        if (this.mSupportStraightSlide) {
            int ret = 0;
            long curtime = System.currentTimeMillis();
            long delta_time = curtime - this.mLastCarKeytime;
            XGlobalKeyManager.log(TAG, "dealStraightSlide keycode:" + keycode + "delta_time:" + delta_time);
            if (delta_time > 0 && delta_time < 500) {
                XGlobalKeyManager.log(TAG, "dealStraightSlide ret:0 keycode:" + keycode + " mLastKeycodeForSlide:" + this.mLastCarKeycodeForSlide);
                if (keycode == 1055) {
                    if (this.mLastCarKeycodeForSlide == 1052 || this.mLastCarKeycodeForSlide == 1054) {
                        ret = 1083;
                    } else if (this.mLastCarKeycodeForSlide == 1051 || this.mLastCarKeycodeForSlide == 1053) {
                        ret = 1084;
                    }
                } else if (this.mLastCarKeycodeForSlide == 1055) {
                    if (keycode == 1052 || keycode == 1054) {
                        ret = 1084;
                    } else if (keycode == 1051 || keycode == 1053) {
                        ret = 1083;
                    }
                }
            }
            this.mLastCarKeytime = curtime;
            if (ret == 0) {
                this.mLastCarKeycodeForSlide = keycode;
            } else {
                this.mLastCarKeycodeForSlide = -1;
                sendKeyToIcm(ret);
            }
            return ret;
        }
        return 0;
    }

    private int dealIcmStraightSlide(int keycode) {
        if (this.mSupportStraightSlide) {
            int ret = 0;
            long curtime = System.currentTimeMillis();
            long delta_time = curtime - this.mLastIcmKeytime;
            XGlobalKeyManager.log(TAG, "dealStraightSlide keycode:" + keycode + "delta_time:" + delta_time);
            if (delta_time > 0 && delta_time < 500) {
                XGlobalKeyManager.log(TAG, "dealStraightSlide ret:0 keycode:" + keycode + " mLastKeycodeForSlide:" + this.mLastIcmKeycodeForSlide);
                if (keycode == 1044) {
                    if (this.mLastIcmKeycodeForSlide == 1041 || this.mLastIcmKeycodeForSlide == 1043) {
                        ret = 1081;
                    } else if (this.mLastIcmKeycodeForSlide == 1040 || this.mLastIcmKeycodeForSlide == 1042) {
                        ret = 1082;
                    }
                } else if (this.mLastIcmKeycodeForSlide == 1044) {
                    if (keycode == 1041 || keycode == 1043) {
                        ret = 1082;
                    } else if (keycode == 1040 || keycode == 1042) {
                        ret = 1081;
                    }
                }
            }
            this.mLastIcmKeytime = curtime;
            if (ret == 0) {
                this.mLastIcmKeycodeForSlide = keycode;
            } else {
                this.mLastIcmKeycodeForSlide = -1;
            }
            return ret;
        }
        return 0;
    }

    private int handleIcmSlideKey(int KeyCode) {
        if (KeyCode != this.mLastIcmSlideKeycode) {
            this.mIcmSldeKeyNum = 0;
            this.mLastIcmSlideKeycode = KeyCode;
        }
        this.mIcmSldeKeyNum++;
        if (this.mIcmSldeKeyNum >= this.wheelkeySlideSensitivity) {
            this.mIcmSldeKeyNum = 0;
            if (KeyCode == 1081) {
                return 1000;
            }
            return NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE;
        }
        return 0;
    }

    private int handleCarSlideKey(int KeyCode) {
        if (KeyCode != this.mLastCarSlideKeycode) {
            this.mCarSldeKeyNum = 0;
            this.mLastCarSlideKeycode = KeyCode;
        }
        this.mCarSldeKeyNum++;
        if (this.mCarSldeKeyNum >= this.wheelkeySlideSensitivity) {
            this.mCarSldeKeyNum = 0;
            return KeyCode == 1083 ? 24 : 25;
        }
        return 0;
    }

    private int convertNewKey(int keyCode) {
        if (this.mSupportNewSolution) {
            boolean disableAirConditon = (sAutoShowMode && sAutoShowAirCnnditionDisable) || this.mChargingMode;
            int newKeycode = keyCode;
            switch (keyCode) {
                case 1000:
                    int newKeycode2 = mIcmWheelkeySwitchMode ? 1081 : keyCode;
                    if (mIcmWheelkeySwitchMode || !disableAirConditon) {
                        r4 = newKeycode2;
                    }
                    newKeycode = r4;
                    if (newKeycode > 0 && this.needSlideAudioEffect && mIcmWheelkeySwitchMode) {
                        this.mAudioManager.playSoundEffect(11);
                        break;
                    }
                    break;
                case NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE /* 1001 */:
                    int newKeycode3 = mIcmWheelkeySwitchMode ? 1082 : keyCode;
                    if (mIcmWheelkeySwitchMode || !disableAirConditon) {
                        r4 = newKeycode3;
                    }
                    newKeycode = r4;
                    if (newKeycode > 0 && this.needSlideAudioEffect && mIcmWheelkeySwitchMode) {
                        this.mAudioManager.playSoundEffect(11);
                        break;
                    }
                    break;
                case 1002:
                case 1003:
                    int newKeycode4 = mIcmWheelkeySwitchMode ? -2 : keyCode;
                    if (mIcmWheelkeySwitchMode || !disableAirConditon) {
                        r4 = newKeycode4;
                    }
                    newKeycode = r4;
                    break;
                default:
                    switch (keyCode) {
                        case 1011:
                            newKeycode = mCarWheelkeySwitchMode ? 1083 : 0;
                            if (newKeycode > 0 && this.needSlideAudioEffect && mCarWheelkeySwitchMode) {
                                this.mAudioManager.playSoundEffect(11);
                                break;
                            }
                            break;
                        case 1012:
                            newKeycode = mCarWheelkeySwitchMode ? 1084 : 0;
                            if (newKeycode > 0 && this.needSlideAudioEffect && mCarWheelkeySwitchMode) {
                                this.mAudioManager.playSoundEffect(11);
                                break;
                            }
                            break;
                        case 1013:
                        case 1014:
                            newKeycode = mCarWheelkeySwitchMode ? -2 : keyCode;
                            break;
                        default:
                            switch (keyCode) {
                                case 1020:
                                case 1021:
                                case 1022:
                                case 1023:
                                    int newKeycode5 = mIcmWheelkeySwitchMode ? -2 : keyCode;
                                    if (mIcmWheelkeySwitchMode || !disableAirConditon) {
                                        r4 = newKeycode5;
                                    }
                                    newKeycode = r4;
                                    break;
                                default:
                                    switch (keyCode) {
                                        case 1081:
                                        case 1082:
                                            int newKeycode6 = mIcmWheelkeySwitchMode ? -2 : handleIcmSlideKey(keyCode);
                                            if (mIcmWheelkeySwitchMode || !disableAirConditon) {
                                                r4 = newKeycode6;
                                            }
                                            newKeycode = r4;
                                            break;
                                        case 1083:
                                        case 1084:
                                            newKeycode = mCarWheelkeySwitchMode ? -2 : handleCarSlideKey(keyCode);
                                            break;
                                    }
                            }
                    }
            }
            XGlobalKeyManager.log(TAG, "convert new keycode " + keyCode + "|" + newKeycode + " autoShowMode=" + sAutoShowMode + " autoShowAircondition=" + sAutoShowAirCnnditionDisable + " icmSwitchMode=" + mIcmWheelkeySwitchMode + " carSwitchMode=" + mCarWheelkeySwitchMode);
            return newKeycode;
        }
        return keyCode;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void getAndUpdateSlideSpeed() {
        if (this.mSupportNewSolution) {
            this.wheelkeySlideSensitivity = Settings.System.getInt(this.mContext.getContentResolver(), KEY_TOUCH_ROTATION_SPEED, 2);
        }
    }

    private String createSwitchContent(String syncMode, boolean value) {
        JSONObject object = new JSONObject();
        try {
            object.put("SyncMode", syncMode);
            object.put("msgId", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            object.put("SyncProgress", value ? 1 : 0);
            return object.toString();
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean setKeySwitchMode(int pos, boolean switchMode, boolean immediate) {
        if (this.mSupportShortEnterOut) {
            long now = System.currentTimeMillis();
            long delta = 0;
            String content = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            switch (pos) {
                case 0:
                    delta = now - mLastIcmEnterTime;
                    if (delta > 100 || immediate) {
                        mIcmWheelkeySwitchMode = switchMode;
                        content = createSwitchContent("LeftSwitchMode", mIcmWheelkeySwitchMode);
                    }
                    mLastIcmEnterTime = now;
                    break;
                case 1:
                    delta = now - mLastCarEnterTime;
                    if (delta > 100 || immediate) {
                        mCarWheelkeySwitchMode = switchMode;
                        content = createSwitchContent("RightSwitchMode", mCarWheelkeySwitchMode);
                    }
                    mLastCarEnterTime = now;
                    break;
            }
            if (!TextUtils.isEmpty(content)) {
                sendSignalToIcm(content);
            }
            if (mIcmWheelkeySwitchMode && mCarWheelkeySwitchMode) {
                setKeySwitchMode(pos == 0 ? 1 : 0, false, true);
            }
            postSyncSwitchModeTask();
            postResetSwitchModeTask(pos);
            StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            buffer.append("switchNewkeyMode pos=" + pos);
            buffer.append(" switchMode=" + switchMode);
            buffer.append(" delta=" + delta);
            buffer.append(" content=" + content);
            buffer.append(" icmSwitchMode=" + mIcmWheelkeySwitchMode);
            buffer.append(" carSwitchMode=" + mCarWheelkeySwitchMode);
            XGlobalKeyManager.log(TAG, buffer.toString());
            return true;
        }
        return false;
    }

    private void postResetSwitchModeTask(int pos) {
        if (!CFG_SWITCH_MODE_DELAY) {
            return;
        }
        int what = pos == 0 ? 100 : 101;
        boolean reset = pos == 0 ? mIcmWheelkeySwitchMode : mCarWheelkeySwitchMode;
        if (reset && this.mHandler != null) {
            this.mHandler.removeMessages(what);
            this.mHandler.sendEmptyMessageDelayed(what, DEFAULT_RESET_SWITCH_MODE_DELAY);
        }
        postSyncSwitchModeTask();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleResetSwitchModeTask(int pos) {
        if (!CFG_SWITCH_MODE_DELAY) {
            return;
        }
        String syncMode = pos == 0 ? "LeftSwitchMode" : "RightSwitchMode";
        boolean reset = pos == 0 ? mIcmWheelkeySwitchMode : mCarWheelkeySwitchMode;
        XGlobalKeyManager.log(TAG, "handleResetSwitchModeTask syncMode=" + syncMode + " reset=" + reset);
        if (reset) {
            String content = createSwitchContent(syncMode, false);
            if (!TextUtils.isEmpty(content)) {
                sendSignalToIcm(content);
            }
            if (pos == 0) {
                mIcmWheelkeySwitchMode = false;
            } else {
                mCarWheelkeySwitchMode = false;
            }
        }
    }

    private void postSyncSwitchModeTask() {
        this.mHandler.removeMessages(102);
        this.mHandler.sendEmptyMessage(102);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSyncSwitchModeTask() {
        Settings.System.putInt(this.mContext.getContentResolver(), "key_icm_left_switch_mode", mIcmWheelkeySwitchMode ? 1 : 0);
        Settings.System.putInt(this.mContext.getContentResolver(), "key_icm_right_switch_mode", mCarWheelkeySwitchMode ? 1 : 0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void exitIcmSwitchModeIfNeed() {
        if (mIcmWheelkeySwitchMode) {
            setKeySwitchMode(0, false, true);
        }
        if (mCarWheelkeySwitchMode) {
            setKeySwitchMode(1, false, true);
        }
        XGlobalKeyManager.log(TAG, "exitIcmSwitchModeIfNeed icmSwitchMode" + mIcmWheelkeySwitchMode + " carSwitchMode=" + mCarWheelkeySwitchMode);
    }

    private void sendSignalToIcm(String signal) {
        try {
            if (this.mxpIcmManager != null) {
                this.mxpIcmManager.setIcmSyncSignal(signal);
            }
        } catch (Exception e) {
            XGlobalKeyManager.log(TAG, e.toString());
        }
    }

    private boolean sendKeyToIcm(int keycode) {
        XGlobalKeyManager.log(TAG, "sendKeyToIcm handle keycode:" + keycode);
        if (keycode == 0) {
            return false;
        }
        if (keycode == -2) {
            return true;
        }
        try {
            if (this.mxpIcmManager != null) {
                XGlobalKeyManager.log(TAG, "sendKeyToIcm:" + keycode + " has sent to ICM");
                this.mxpIcmManager.setIcmWheelkey(keycode);
            }
            return true;
        } catch (Exception e) {
            XGlobalKeyManager.log(TAG, e.toString());
            return false;
        }
    }

    private boolean isUpAction(KeyEvent event) {
        return event.getAction() == 1;
    }

    private boolean isDownAction(KeyEvent event) {
        if (event.getAction() == 0) {
            return true;
        }
        return false;
    }

    private boolean isMirrorMode() {
        return SystemProperties.getInt("xp.key.mirror.flag", 0) == 1;
    }

    private boolean isPhoneMode() {
        return SystemProperties.getInt("xp.key.phone.flag", 0) == 1;
    }

    private boolean isGameMode() {
        return xpInputDeviceWrapper.isGameModeEnable();
    }

    private boolean isAccOrCcMode() {
        if (this.mxpVehicleManager != null && this.mxpVehicleManager.isInAccOrCcStatus()) {
            return true;
        }
        return false;
    }

    private boolean isIntervalLasted() {
        boolean isIntervalLast = this.longPressForCustomerTimeStamp < System.currentTimeMillis() - 200;
        this.longPressForCustomerTimeStamp = System.currentTimeMillis();
        return isIntervalLast;
    }

    public boolean getKeyBoardTouchPrompt() {
        return Settings.System.getInt(this.mContext.getContentResolver(), xpInputActionHandler.InputSettingsObserver.KEY_KTP_MODE, 0) == 1;
    }

    public boolean isOutwardRotationDirection() {
        return Settings.System.getInt(this.mContext.getContentResolver(), xpInputActionHandler.InputSettingsObserver.KEY_TRD_MODE, 1) == 1;
    }

    private void wakeupSpeech(int keyCode) {
        Intent intent = new Intent(XP_SPEECH_INTENT);
        intent.addFlags(16777216);
        if (keyCode == 1005) {
            intent.putExtra("location", "key_speech");
        } else if (keyCode == 1094) {
            intent.putExtra("location", "front_right");
        } else if (keyCode == 1090) {
            intent.putExtra("location", "rear_left");
        } else if (keyCode == 1092) {
            intent.putExtra("location", "rear_right");
        }
        this.mContext.sendBroadcast(intent);
    }

    private void notifyNoOption(int keypos) {
        Intent intent = new Intent(keypos == 0 ? XP_XKEY_USER_INTENT : XP_BOSSKEY_USER_INTENT);
        intent.addFlags(16777216);
        this.mContext.sendBroadcast(intent);
    }

    private void CustomKeyAction(int keypos, String keytype, int keyfunc) {
        Intent intent = new Intent(keypos == 0 ? XP_XKEY_INTENT : XP_BOSSKEY_INTENT);
        intent.addFlags(16777216);
        intent.putExtra("keytype", keytype);
        intent.putExtra("keyfunc", keyfunc);
        this.mContext.sendBroadcast(intent);
    }

    private void trigerBroadcast(String reason, String key, int speed, int anglespeed, int angle, int gear, boolean down) {
        if (down) {
            return;
        }
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dottime = df.format(Long.valueOf(System.currentTimeMillis()));
        new bi_dot(dottime, reason, key, speed, anglespeed, angle);
        Intent tick = new Intent("android.keyinogre.dot.DOT_ACTION");
        tick.putExtra("android.keyinogre.dottime", dottime);
        tick.putExtra("android.keyinogre.key", key);
        tick.putExtra("android.keyinogre.speed", speed);
        tick.putExtra("android.keyinogre.anglespeed", anglespeed);
        tick.putExtra("android.keyinogre.angle", angle);
        tick.putExtra("android.keyinogre.gear", gear);
        long ident = Binder.clearCallingIdentity();
        XGlobalKeyManager.log(TAG_KEYIGNORE, "trigerBroadcast:" + reason + " " + dottime + " " + key + " " + speed + " " + anglespeed + " " + angle + " " + gear);
        try {
            this.mContext.sendBroadcastAsUser(tick, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean checkIgnorePolicy() {
        int XpMuteKeyIgnore = Settings.System.getInt(this.mContext.getContentResolver(), "XpWheelkeyIgnore", 0);
        return XpMuteKeyIgnore != 0;
    }

    @Override // com.android.server.policy.xkeymgr.KeyHandler
    public boolean checkKeyIgnored(int key, boolean down) {
        int mGear;
        float mSpeed;
        float mAngleSpeed;
        float mAngleSpeed2;
        XGlobalKeyManager.log(TAG_KEYIGNORE, "checkKeyIgnored " + key);
        if (checkIgnorePolicy()) {
            if (this.mxpVehicleManager == null) {
                XGlobalKeyManager.log(TAG_KEYIGNORE, "mxpVehicleManager not connected");
                return false;
            }
            float mSpeed2 = 0.0f;
            float mSteeringAngle = 0.0f;
            try {
                int mGear2 = this.mxpVehicleManager.getCarGear();
                mGear = mGear2;
            } catch (Exception e) {
                XGlobalKeyManager.log(TAG_KEYIGNORE, "error :" + e);
                mGear = 0;
            }
            String keyCode = String.valueOf(key);
            if (mGear == 1 || mGear == 2) {
                try {
                    mSpeed2 = this.mxpVehicleManager.getRawSpeed();
                    mSteeringAngle = this.mxpVehicleManager.getCarSteeringAngle();
                    float mAngleSpeed3 = this.mxpVehicleManager.getCarSteeringAngleSpeed();
                    mAngleSpeed2 = mAngleSpeed3;
                    mSpeed = mSpeed2;
                    mAngleSpeed = mSteeringAngle;
                } catch (Exception e2) {
                    XGlobalKeyManager.log(TAG_KEYIGNORE, "error :" + e2);
                    mSpeed = mSpeed2;
                    mAngleSpeed = mSteeringAngle;
                    mAngleSpeed2 = 0.0f;
                }
                float mSteeringAngleAbs = Math.abs(mAngleSpeed);
                if (mAngleSpeed2 > 90 || (mSpeed > 3 && mSteeringAngleAbs > 60)) {
                    XGlobalKeyManager.log(TAG, "[XUI Demo] Wheel Key Trigger: out of limit Angle=" + mSteeringAngleAbs + "|AngleSpeed=" + mAngleSpeed2 + "|Speed=" + mSpeed);
                    int XpKeyIgnoreAngleSpeed = mGear;
                    trigerBroadcast("ConditionIgnored", keyCode, (int) mSpeed, (int) mAngleSpeed2, (int) mAngleSpeed, XpKeyIgnoreAngleSpeed, down);
                    return true;
                }
            }
            trigerBroadcast("NotIgnored", keyCode, 0, 0, 0, mGear, down);
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.policy.xkeymgr.KeyHandler
    public boolean shouldDispatchMediaKeyByCommands() {
        return true;
    }

    private int getOverrideVolumeKeyCode(int keyCode) {
        switch (keyCode) {
            case 1011:
                return 24;
            case 1012:
                return 25;
            default:
                return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class bi_dot implements Parcelable {
        public static final Parcelable.Creator<bi_dot> CREATOR = new Parcelable.Creator<bi_dot>() { // from class: com.android.server.policy.xkeymgr.EdwardHandler.bi_dot.1
            /* JADX WARN: Can't rename method to resolve collision */
            @Override // android.os.Parcelable.Creator
            public bi_dot createFromParcel(Parcel source) {
                return new bi_dot(source);
            }

            /* JADX WARN: Can't rename method to resolve collision */
            @Override // android.os.Parcelable.Creator
            public bi_dot[] newArray(int size) {
                return new bi_dot[size];
            }
        };
        private int angle;
        private int angleSpeed;
        private String key;
        private String reason;
        private int speed;
        private String time;

        public bi_dot() {
        }

        public bi_dot(String time, String reason, String key, int speed, int angleSpeed, int angle) {
            this.time = time;
            this.reason = reason;
            this.key = key;
            this.speed = speed;
            this.angleSpeed = angleSpeed;
            this.angle = angle;
        }

        protected bi_dot(Parcel in) {
            this.time = in.readString();
            this.reason = in.readString();
            this.key = in.readString();
            this.speed = in.readInt();
            this.angleSpeed = in.readInt();
            this.angle = in.readInt();
        }

        public String getTime() {
            return this.time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public String getReason() {
            return this.reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getKey() {
            return this.key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public int getSpeed() {
            return this.speed;
        }

        public void setSpeed(int speed) {
            this.speed = speed;
        }

        public int getAngleSpeed() {
            return this.angleSpeed;
        }

        public void setAngleSpeed(int angleSpeed) {
            this.angleSpeed = angleSpeed;
        }

        public int getAngle() {
            return this.angle;
        }

        public void setAngle(int angle) {
            this.angle = angle;
        }

        @Override // android.os.Parcelable
        public int describeContents() {
            return 0;
        }

        @Override // android.os.Parcelable
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(this.time);
            dest.writeString(this.reason);
            dest.writeString(this.key);
            dest.writeInt(this.speed);
            dest.writeInt(this.angleSpeed);
            dest.writeInt(this.angle);
        }
    }

    /* loaded from: classes.dex */
    final class SettingsObserver extends ContentObserver {
        public SettingsObserver() {
            super(null);
        }

        public void register(boolean register) {
            ContentResolver cr = EdwardHandler.this.mContext.getContentResolver();
            if (register) {
                cr.registerContentObserver(Settings.System.getUriFor(EdwardHandler.KEY_TOUCH_ROTATION_SPEED), false, this);
                cr.registerContentObserver(Settings.System.getUriFor("key_icm_left_switch_mode"), false, this);
                cr.registerContentObserver(Settings.System.getUriFor("key_icm_right_switch_mode"), false, this);
                cr.registerContentObserver(Settings.System.getUriFor("main_setting_disable"), false, this);
                cr.registerContentObserver(Settings.System.getUriFor("airCondition_disable"), false, this);
                cr.registerContentObserver(Settings.System.getUriFor("key_vcu_charge_mode"), false, this);
                return;
            }
            cr.unregisterContentObserver(this);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (Settings.System.getUriFor(EdwardHandler.KEY_TOUCH_ROTATION_SPEED).equals(uri)) {
                EdwardHandler.this.getAndUpdateSlideSpeed();
                return;
            }
            if (Settings.System.getUriFor("key_icm_left_switch_mode").equals(uri)) {
                boolean unused = EdwardHandler.mIcmWheelkeySwitchMode = Settings.System.getInt(EdwardHandler.this.mContext.getContentResolver(), "key_icm_left_switch_mode", 0) == 1;
            } else if (Settings.System.getUriFor("key_icm_right_switch_mode").equals(uri)) {
                boolean unused2 = EdwardHandler.mCarWheelkeySwitchMode = Settings.System.getInt(EdwardHandler.this.mContext.getContentResolver(), "key_icm_right_switch_mode", 0) == 1;
            } else if (Settings.System.getUriFor("main_setting_disable").equals(uri)) {
                int value = Settings.System.getInt(EdwardHandler.this.mContext.getContentResolver(), "main_setting_disable", 0);
                boolean unused3 = EdwardHandler.sAutoShowMode = value == 1;
                XGlobalKeyManager.log(EdwardHandler.TAG, "SettingsObserver auto show value= " + value + " autoShowMode=" + EdwardHandler.sAutoShowMode);
            } else if (Settings.System.getUriFor("airCondition_disable").equals(uri)) {
                int value2 = Settings.System.getInt(EdwardHandler.this.mContext.getContentResolver(), "airCondition_disable", 0);
                boolean unused4 = EdwardHandler.sAutoShowAirCnnditionDisable = value2 == 1;
                XGlobalKeyManager.log(EdwardHandler.TAG, "SettingsObserver auto show aircondition mode value= " + value2 + " autoShowAirconditionEnable=" + EdwardHandler.sAutoShowAirCnnditionDisable);
            } else if (Settings.System.getUriFor("key_vcu_charge_mode").equals(uri)) {
                int value3 = Settings.System.getInt(EdwardHandler.this.mContext.getContentResolver(), "key_vcu_charge_mode", 0);
                EdwardHandler.this.mChargingMode = value3 == 1;
                if (EdwardHandler.this.mChargingMode) {
                    EdwardHandler.this.exitIcmSwitchModeIfNeed();
                }
                XGlobalKeyManager.log(EdwardHandler.TAG, "SettingsObserver vcu charge mode value= " + value3 + " mChargingMode=" + EdwardHandler.this.mChargingMode);
            }
        }
    }
}
