package com.xiaopeng.server.input;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.IAudioService;
import android.net.Uri;
import android.os.Handler;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import com.android.server.wm.WindowManagerService;
import com.xiaopeng.bi.BiDataManager;
import com.xiaopeng.input.xpInputManager;
import com.xiaopeng.server.ext.ExternalManagerService;
import com.xiaopeng.server.input.action.InputActionFunction;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.xpInputDeviceWrapper;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class xpInputActionHandler {
    public static final int FLAG_ACC = 2;
    public static final int FLAG_ACD = 64;
    public static final int FLAG_ASM = 32;
    public static final int FLAG_BTS = 128;
    public static final int FLAG_CHARGE = 8192;
    public static final int FLAG_DEFAULT = 1;
    public static final int FLAG_GAME = 1024;
    public static final int FLAG_ILC = 8;
    public static final int FLAG_ILI = 16384;
    public static final int FLAG_IRC = 16;
    public static final int FLAG_IRI = 32768;
    public static final int FLAG_KTP = 256;
    public static final int FLAG_LCC = 4;
    public static final int FLAG_MIRROR = 4096;
    public static final int FLAG_PHONE = 2048;
    public static final int FLAG_QUICK = 16777216;
    public static final int FLAG_TRD = 512;
    public static final int FLAG_WAIST = 1048576;
    public static final String FUNCTION_CHECK_LONG_EVENT = "checkLongEvent";
    public static final String FUNCTION_CHECK_SHORT_EVENT = "checkShortEvent";
    public static final String FUNCTION_DISPATCH_EVENT = "dispatchEvent";
    public static final String FUNCTION_DISPATCH_EVENT_TO_ICM = "dispatchEventToIcm";
    public static final String FUNCTION_DISPATCH_MEDIA = "dispatchMedia";
    public static final String FUNCTION_DISPATCH_SLIDE_EVENT = "dispatchSlideEvent";
    public static final String FUNCTION_PLAY_SOUND_EFFECT = "playSoundEffect";
    public static final String FUNCTION_POST_BI_EVENT = "postBiEvent";
    public static final String FUNCTION_POST_EVENT = "postEvent";
    public static final String FUNCTION_SEND_EVENT_BROADCAST = "sendEventBroadcast";
    public static final String FUNCTION_SEND_SIGNAL_TO_ICM = "sendSignalToIcm";
    public static final String MODE_ACC = "acc";
    public static final String MODE_ACD = "acd";
    public static final String MODE_ASM = "asm";
    public static final String MODE_BTS = "bts";
    public static final String MODE_CHARGE = "charge";
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_GAME = "game";
    public static final String MODE_ILC = "ilc";
    public static final String MODE_ILI = "ili";
    public static final String MODE_IRC = "irc";
    public static final String MODE_IRI = "iri";
    public static final String MODE_KTP = "ktp";
    public static final String MODE_LCC = "lcc";
    public static final String MODE_MIRROR = "mirror";
    public static final String MODE_PHONE = "phone";
    public static final String MODE_QUICK = "quickmenu";
    public static final String MODE_TRD = "trd";
    public static final String MODE_WAIST = "waist";
    private static final String TAG = "xpInputManagerService";
    public static final HashMap<Integer, Long> mLongEventMillis;
    public static final HashMap<Integer, Long> mShortEventMillis;
    public static final HashMap<String, InputActionFunction> sActionFunction;
    public static final HashMap<String, Integer> sModeMapping = new HashMap<>();
    private AudioManager mAudioManager;
    private Context mContext;
    public volatile int modeFlags = 1;
    private int mGear = 0;
    private float mRawSpeed = 0.0f;
    private float mSteeringAngle = 0.0f;
    private float mSteeringAngleSpeed = 0.0f;
    private int mLastIcmSlideKeyCode = 0;
    private int mLastCarSlideKeyCode = 0;
    private int mIcmSlideEventCount = 0;
    private int mCarSlideEventCount = 0;
    private InputEventSettings mInputSettings = new InputEventSettings();
    private Object mLock = new Object();
    private Handler mHandler = new Handler();
    private boolean mLastInputInValid = false;
    private ExternalManagerService.OnEventListener mOnEventListener = new ExternalManagerService.OnEventListener() { // from class: com.xiaopeng.server.input.xpInputActionHandler.1
        @Override // com.xiaopeng.server.ext.ExternalManagerService.OnEventListener
        public void onEventReady() {
            xpInputActionHandler.this.initCarStateIfNeed();
            xpLogger.i(xpInputActionHandler.TAG, "onEventReady");
        }

        @Override // com.xiaopeng.server.ext.ExternalManagerService.OnEventListener
        public void onEventChanged(int var, Object value) {
            try {
                switch (var) {
                    case ExternalManagerService.EventId.ID_CAR_SCU_ACC /* 101001 */:
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ACC, CarManagerHelper.isAccActive(((Integer) value).intValue()));
                        break;
                    case ExternalManagerService.EventId.ID_CAR_VCU_LCC /* 102001 */:
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_LCC, CarManagerHelper.isLccActive(((Integer) value).intValue()));
                        break;
                    case ExternalManagerService.EventId.ID_CAR_VCU_GEAR /* 102002 */:
                        xpInputActionHandler.this.mGear = ((Integer) value).intValue();
                        break;
                    case ExternalManagerService.EventId.ID_CAR_VCU_RAW_SPEED /* 102003 */:
                        return;
                    case ExternalManagerService.EventId.ID_CAR_EPS_STEERING_ANGLE /* 103001 */:
                        xpInputActionHandler.this.mSteeringAngle = ((Float) value).floatValue();
                        break;
                    case ExternalManagerService.EventId.ID_CAR_EPS_STEERING_ANGLE_SPEED /* 103002 */:
                        xpInputActionHandler.this.mSteeringAngleSpeed = ((Float) value).floatValue();
                        break;
                    default:
                        return;
                }
            } catch (Exception e) {
                xpLogger.i(xpInputActionHandler.TAG, "onEventChanged e=" + e);
            }
        }
    };
    private InputActionFunction mPostEventAction = new InputActionFunction(FUNCTION_POST_EVENT) { // from class: com.xiaopeng.server.input.xpInputActionHandler.2
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply postEventAction");
            final int keycode = xpInputActionHandler.getKeycode(var1);
            int overrideCode = xpInputActionHandler.getOverrideCode(var1);
            if (xpInputManagerService.isUnknown(overrideCode)) {
                return false;
            }
            xpInputActionHandler.this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.input.xpInputActionHandler.2.1
                @Override // java.lang.Runnable
                public void run() {
                    xpInputManager.sendEvent(keycode);
                }
            });
            return super.apply(var1);
        }
    };
    private InputActionFunction mDispatchEventAction = new InputActionFunction(FUNCTION_DISPATCH_EVENT) { // from class: com.xiaopeng.server.input.xpInputActionHandler.3
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply dispatchEventAction");
            final int keycode = xpInputActionHandler.getKeycode(var1);
            int overrideCode = xpInputActionHandler.getOverrideCode(var1);
            if (xpInputManagerService.isUnknown(overrideCode)) {
                return false;
            }
            xpInputManagerService.ThreadExecutor.execute(new Runnable() { // from class: com.xiaopeng.server.input.xpInputActionHandler.3.1
                @Override // java.lang.Runnable
                public void run() {
                    xpInputManager.sendEvent(keycode);
                }
            });
            return super.apply(var1);
        }
    };
    private InputActionFunction mDispatchMediaAction = new InputActionFunction(FUNCTION_DISPATCH_MEDIA) { // from class: com.xiaopeng.server.input.xpInputActionHandler.4
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply dispatchMediaAction");
            final int keycode = xpInputActionHandler.getKeycode(var1);
            xpInputManagerService.ThreadExecutor.execute(new Runnable() { // from class: com.xiaopeng.server.input.xpInputActionHandler.4.1
                @Override // java.lang.Runnable
                public void run() {
                    InputMediaManager.dispatchMediaEvent(keycode);
                }
            });
            return super.apply(var1);
        }
    };
    private InputActionFunction mCheckLongEventAction = new InputActionFunction(FUNCTION_CHECK_LONG_EVENT) { // from class: com.xiaopeng.server.input.xpInputActionHandler.5
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply checkLongEventAction");
            int keycode = xpInputActionHandler.getKeycode(var1);
            long now = System.currentTimeMillis();
            long delta = now - (xpInputActionHandler.mLongEventMillis.containsKey(Integer.valueOf(keycode)) ? xpInputActionHandler.mLongEventMillis.get(Integer.valueOf(keycode)).longValue() : 0L);
            boolean longEvent = delta > 500;
            xpInputActionHandler.mLongEventMillis.put(Integer.valueOf(keycode), Long.valueOf(now));
            return longEvent;
        }
    };
    private InputActionFunction mCheckShortEventAction = new InputActionFunction(FUNCTION_CHECK_SHORT_EVENT) { // from class: com.xiaopeng.server.input.xpInputActionHandler.6
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply checkShortEventAction");
            int keycode = xpInputActionHandler.getKeycode(var1);
            long now = System.currentTimeMillis();
            long delta = now - (xpInputActionHandler.mShortEventMillis.containsKey(Integer.valueOf(keycode)) ? xpInputActionHandler.mShortEventMillis.get(Integer.valueOf(keycode)).longValue() : 0L);
            boolean shortEvent = delta > 100;
            xpInputActionHandler.mShortEventMillis.put(Integer.valueOf(keycode), Long.valueOf(now));
            return shortEvent;
        }
    };
    private InputActionFunction mPlaySoundEffectAction = new InputActionFunction(FUNCTION_PLAY_SOUND_EFFECT) { // from class: com.xiaopeng.server.input.xpInputActionHandler.7
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply playSoundEffectAction");
            final int keycode = xpInputActionHandler.getKeycode(var1);
            xpInputManagerService.ThreadExecutor.execute(new Runnable() { // from class: com.xiaopeng.server.input.xpInputActionHandler.7.1
                @Override // java.lang.Runnable
                public void run() {
                    int effectType = 11;
                    switch (keycode) {
                        case 4:
                        case 1016:
                            effectType = 12;
                            break;
                        case 1065:
                        case 1066:
                        case 1076:
                        case 1077:
                            effectType = 13;
                            break;
                    }
                    xpInputActionHandler.this.mAudioManager.playSoundEffect(effectType);
                }
            });
            return super.apply(var1);
        }
    };
    private InputActionFunction mSendSignalToIcmAction = new InputActionFunction(FUNCTION_SEND_SIGNAL_TO_ICM) { // from class: com.xiaopeng.server.input.xpInputActionHandler.8
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply sendSignalToIcmAction");
            int keycode = xpInputActionHandler.getKeycode(var1);
            InputSignalInfo info = CarManagerHelper.makeIcmSignal(keycode, xpInputActionHandler.this.modeFlags);
            if (info != null && !TextUtils.isEmpty(info.signal)) {
                xpLogger.i(xpInputActionHandler.TAG, "apply sendSignalToIcmAction mode=" + Integer.toHexString(xpInputActionHandler.this.modeFlags) + " info.mode=" + Integer.toHexString(info.mode) + " signal=" + info.signal + " callback=" + info.signal);
                xpInputActionHandler.this.modeFlags = info.mode;
                xpInputActionHandler.this.setIcmSyncSignal(info.signal);
                if (!TextUtils.isEmpty(info.callback)) {
                    xpInputActionHandler.this.setIcmSyncSignal(info.callback);
                }
                xpInputActionHandler.setIcmModeSettings(xpInputActionHandler.this.mContext, xpInputActionHandler.this.modeFlags);
            }
            return super.apply(var1);
        }
    };
    private InputActionFunction mDispatchSlideEventAction = new InputActionFunction(FUNCTION_DISPATCH_SLIDE_EVENT) { // from class: com.xiaopeng.server.input.xpInputActionHandler.9
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply dispatchSlideEventAction");
            int keycode = xpInputActionHandler.getKeycode(var1);
            xpInputManager.sendEvent(xpInputActionHandler.this.getOverrideSlideKeyCode(keycode));
            return super.apply(var1);
        }
    };
    private InputActionFunction mDispatchEventToIcmAction = new InputActionFunction(FUNCTION_DISPATCH_EVENT_TO_ICM) { // from class: com.xiaopeng.server.input.xpInputActionHandler.10
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply dispatchEventToIcmAction");
            final int keycode = xpInputActionHandler.getKeycode(var1);
            int overrideCode = xpInputActionHandler.getOverrideCode(var1);
            if (xpInputManagerService.isUnknown(overrideCode)) {
                return false;
            }
            xpInputManagerService.ThreadExecutor.execute(new Runnable() { // from class: com.xiaopeng.server.input.xpInputActionHandler.10.1
                @Override // java.lang.Runnable
                public void run() {
                    xpInputActionHandler.this.setIcmWheelKey(keycode);
                }
            });
            return super.apply(var1);
        }
    };
    private InputActionFunction mSendEventBroadcastAction = new InputActionFunction(FUNCTION_SEND_EVENT_BROADCAST) { // from class: com.xiaopeng.server.input.xpInputActionHandler.11
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            xpLogger.i(xpInputActionHandler.TAG, "apply sendEventBroadcastAction");
            int keycode = xpInputActionHandler.getKeycode(var1);
            InputIntentManager.sendBroadcastAsUser(xpInputActionHandler.this.mContext, keycode, xpInputActionHandler.this.mInputSettings);
            return super.apply(var1);
        }
    };
    private InputActionFunction mPostBiEventAction = new InputActionFunction(FUNCTION_POST_BI_EVENT) { // from class: com.xiaopeng.server.input.xpInputActionHandler.12
        @Override // com.xiaopeng.server.input.action.InputActionFunction, com.xiaopeng.server.input.action.ToActionFunction
        public boolean apply(Object var1) {
            final int keycode = xpInputActionHandler.getKeycode(var1);
            xpLogger.i(xpInputActionHandler.TAG, "apply postBiEventAction keycode=" + keycode);
            final int overrideCode = xpInputActionHandler.getOverrideCode(var1);
            if (xpInputManagerService.isUnknown(overrideCode)) {
                return false;
            }
            final int source = xpInputActionHandler.getSource(var1);
            final String overrideName = xpInputActionHandler.getOverrideName(overrideCode);
            final String sourcename = xpInputActionHandler.getSourceName(source);
            final String keyname = xpInputActionHandler.getKeyName(var1);
            final String win = xpInputActionHandler.getWinName(var1);
            final String mode = xpInputActionHandler.getMode(var1);
            final String function = xpInputActionHandler.getFunction(var1);
            final int dispathListener = xpInputActionHandler.getDispatchListener(var1);
            xpInputActionHandler.this.mHandler.post(new Runnable() { // from class: com.xiaopeng.server.input.xpInputActionHandler.12.1
                @Override // java.lang.Runnable
                public void run() {
                    Map<String, String> content = new HashMap<>();
                    content.put("source", String.valueOf(source));
                    content.put("sourcename", sourcename);
                    content.put("keyname", keyname);
                    content.put("keycode", String.valueOf(keycode));
                    content.put("win", win);
                    content.put(xpInputManagerService.InputPolicyKey.KEY_OVERRIDE_CODE, String.valueOf(overrideCode));
                    content.put("overrideName", overrideName);
                    content.put(xpInputManagerService.InputPolicyKey.KEY_MODE, mode);
                    content.put(xpInputManagerService.InputPolicyKey.KEY_FUNCTION, function);
                    content.put("dispathListener", String.valueOf(dispathListener));
                    content.put("extrainfo", r12);
                    xpLogger.i(xpInputActionHandler.TAG, "apply postBiEventAction source=" + source + " sourcename=" + sourcename + " keyname=" + keyname + " keycode=" + keycode + "win=" + win + " overrideCode=" + overrideCode + " overrideName=" + overrideName + " mode=" + mode + " function=" + function + " dispathListener=" + dispathListener + " extrainfo=" + r12);
                    BiDataManager.sendStatData(BiDataManager.BID_KEYCODE, content);
                }
            });
            return super.apply(var1);
        }
    };
    private InputSettingsObserver mSettingsObserver = new InputSettingsObserver(this.mHandler);

    static {
        sModeMapping.clear();
        sModeMapping.put("default", 1);
        sModeMapping.put(MODE_ACC, 2);
        sModeMapping.put(MODE_LCC, 4);
        sModeMapping.put(MODE_ILC, 8);
        sModeMapping.put(MODE_IRC, 16);
        sModeMapping.put(MODE_ILI, 16384);
        sModeMapping.put(MODE_IRI, 32768);
        sModeMapping.put(MODE_ASM, 32);
        sModeMapping.put(MODE_ACD, 64);
        sModeMapping.put(MODE_BTS, 128);
        sModeMapping.put(MODE_KTP, 256);
        sModeMapping.put(MODE_TRD, 512);
        sModeMapping.put(MODE_GAME, 1024);
        sModeMapping.put(MODE_PHONE, 2048);
        sModeMapping.put(MODE_MIRROR, 4096);
        sModeMapping.put(MODE_CHARGE, 8192);
        sModeMapping.put(MODE_QUICK, 16777216);
        sModeMapping.put(MODE_WAIST, 1048576);
        sActionFunction = new HashMap<>();
        mLongEventMillis = new HashMap<>();
        mShortEventMillis = new HashMap<>();
    }

    public xpInputActionHandler(Context context) {
        this.mContext = context;
        init();
    }

    public void init() {
        initFunction();
        initMediaState();
        initCarListener(true);
        initCarStateIfNeed();
        updateModeIfNeed();
        this.mSettingsObserver.init();
    }

    public void initFunction() {
        sActionFunction.clear();
        sActionFunction.put(this.mPostEventAction.getTag(), this.mPostEventAction);
        sActionFunction.put(this.mDispatchEventAction.getTag(), this.mDispatchEventAction);
        sActionFunction.put(this.mDispatchMediaAction.getTag(), this.mDispatchMediaAction);
        sActionFunction.put(this.mCheckLongEventAction.getTag(), this.mCheckLongEventAction);
        sActionFunction.put(this.mCheckShortEventAction.getTag(), this.mCheckShortEventAction);
        sActionFunction.put(this.mPlaySoundEffectAction.getTag(), this.mPlaySoundEffectAction);
        sActionFunction.put(this.mSendSignalToIcmAction.getTag(), this.mSendSignalToIcmAction);
        sActionFunction.put(this.mDispatchSlideEventAction.getTag(), this.mDispatchSlideEventAction);
        sActionFunction.put(this.mDispatchEventToIcmAction.getTag(), this.mDispatchEventToIcmAction);
        sActionFunction.put(this.mSendEventBroadcastAction.getTag(), this.mSendEventBroadcastAction);
        sActionFunction.put(this.mPostBiEventAction.getTag(), this.mPostBiEventAction);
    }

    public void initMediaState() {
        this.mAudioManager = (AudioManager) this.mContext.getSystemService("audio");
        this.mAudioManager.loadSoundEffects();
    }

    public boolean applyFunction(Object var, String function) {
        try {
            if (!TextUtils.isEmpty(function) && sActionFunction.containsKey(function)) {
                return sActionFunction.get(function).apply(var);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void setMode(String name, boolean value) {
        if (!TextUtils.isEmpty(name) && sModeMapping.containsKey(name)) {
            int flag = sModeMapping.get(name).intValue();
            synchronized (this.mLock) {
                try {
                    if (value) {
                        this.modeFlags |= flag;
                    } else {
                        this.modeFlags &= ~flag;
                    }
                } finally {
                }
            }
        }
    }

    public static boolean hasFlags(String name, int mode) {
        if (TextUtils.isEmpty(name) || !sModeMapping.containsKey(name)) {
            return false;
        }
        int flag = sModeMapping.get(name).intValue();
        return (mode & flag) == flag;
    }

    public static int getAndSetMode(String name, int mode, boolean value) {
        if (!TextUtils.isEmpty(name) && sModeMapping.containsKey(name)) {
            int flag = sModeMapping.get(name).intValue();
            if (value) {
                return mode | flag;
            }
            return mode & (~flag);
        }
        return mode;
    }

    public void updateModeIfNeed() {
        boolean isGameMode = xpInputDeviceWrapper.isGameModeEnable();
        boolean isPhoneMode = SystemProperties.getInt("xp.key.phone.flag", 0) == 1;
        setMode(MODE_GAME, isGameMode);
        setMode(MODE_PHONE, isPhoneMode);
        setMode(MODE_ILC, getSystemInt(this.mContext, InputSettingsObserver.KEY_ILC_MODE, 0) == 1);
        setMode(MODE_IRC, getSystemInt(this.mContext, InputSettingsObserver.KEY_IRC_MODE, 0) == 1);
    }

    public static int getSystemInt(Context context, String key, int defaultValue) {
        try {
            ContentResolver resolver = context.getContentResolver();
            return Settings.System.getInt(resolver, key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static boolean setSystemInt(Context context, String key, int value) {
        try {
            ContentResolver resolver = context.getContentResolver();
            return Settings.System.putInt(resolver, key, value);
        } catch (Exception e) {
            return false;
        }
    }

    public void setIcmMode(boolean enableIlc, boolean enableIrc) {
        boolean ilc = hasFlags(MODE_ILC, this.modeFlags);
        boolean irc = hasFlags(MODE_IRC, this.modeFlags);
        if (ilc != enableIlc) {
            String signal = CarManagerHelper.createSignalContent("LeftSwitchMode", enableIlc);
            setIcmSyncSignal(signal);
            setMode(MODE_ILC, enableIlc);
        }
        if (irc != enableIrc) {
            String signal2 = CarManagerHelper.createSignalContent("RightSwitchMode", enableIrc);
            setIcmSyncSignal(signal2);
            setMode(MODE_IRC, enableIrc);
        }
        xpLogger.i(TAG, "setIcmMode ilc=" + ilc + " irc=" + irc + " enableIlc=" + enableIlc + " enableIrc=" + enableIrc);
        setIcmModeSettings(this.mContext, this.modeFlags);
    }

    public static void setIcmModeSettings(Context context, int mode) {
        try {
            boolean hasFlags = hasFlags(MODE_ILC, mode);
            boolean hasFlags2 = hasFlags(MODE_IRC, mode);
            int leftSettings = getSystemInt(context, InputSettingsObserver.KEY_ILC_MODE, 0);
            int rightSettings = getSystemInt(context, InputSettingsObserver.KEY_IRC_MODE, 0);
            if (hasFlags != leftSettings) {
                int leftValue = hasFlags ? 1 : 0;
                setSystemInt(context, InputSettingsObserver.KEY_ILC_MODE, leftValue);
            }
            if (hasFlags2 != rightSettings) {
                int rightValue = hasFlags2 ? 1 : 0;
                setSystemInt(context, InputSettingsObserver.KEY_IRC_MODE, rightValue);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("setIcmModeSettings leftValue=");
            int leftValue2 = hasFlags ? 1 : 0;
            sb.append(leftValue2);
            sb.append(" rightValue=");
            int rightValue2 = hasFlags2 ? 1 : 0;
            sb.append(rightValue2);
            sb.append(" leftSettings=");
            sb.append(leftSettings);
            sb.append(" rightSettings=");
            sb.append(rightSettings);
            xpLogger.i(TAG, sb.toString());
        } catch (Exception e) {
        }
    }

    public void initCarListener(boolean register) {
        if (register) {
            ExternalManagerService.get(this.mContext).addListener(this.mOnEventListener);
        } else {
            ExternalManagerService.get(this.mContext).removeListener(this.mOnEventListener);
        }
    }

    public boolean inputValidCheckEnable() {
        return FeatureOption.FO_WHEEL_KEY_IGNORE_CHECK && Settings.System.getInt(this.mContext.getContentResolver(), "XpWheelkeyIgnore", 1) != 0;
    }

    public boolean inputEventValid(int keycode) {
        if (inputValidCheckEnable()) {
            int gear = this.mGear;
            float speed = this.mRawSpeed;
            float steeringAngle = this.mSteeringAngle;
            float steeringAngleSpeed = this.mSteeringAngleSpeed;
            float steeringAngleAbs = Math.abs(steeringAngle);
            boolean gearCheck = gear == 1 || gear == 2;
            boolean steeringCheck = steeringAngleSpeed > 90.0f || (speed >= 1.0f && steeringAngleAbs > 60.0f);
            boolean currentInputInvaild = gearCheck && steeringCheck;
            if (currentInputInvaild != this.mLastInputInValid) {
                this.mLastInputInValid = currentInputInvaild;
                xpLogger.i(TAG, "inputEventValid steeringAngleSpeed=" + steeringAngleSpeed + " steeringAngleAbs=" + steeringAngleAbs + " panel speed=" + speed);
            }
            if (gearCheck && steeringCheck) {
                InputIntentManager.sendValidationBroadcast(this.mContext, keycode, gear, speed, steeringAngle, steeringAngleSpeed);
                return false;
            }
            return true;
        }
        return true;
    }

    public int getOverrideSlideKeyCode(int keycode) {
        switch (keycode) {
            case 1081:
            case 1082:
                if (keycode != this.mLastIcmSlideKeyCode) {
                    this.mIcmSlideEventCount = 0;
                    this.mLastIcmSlideKeyCode = keycode;
                }
                this.mIcmSlideEventCount++;
                if (this.mIcmSlideEventCount < this.mInputSettings.slideSensitivity) {
                    return 0;
                }
                this.mIcmSlideEventCount = 0;
                if (keycode == 1081) {
                    return 1000;
                }
                return NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE;
            case 1083:
            case 1084:
                if (keycode != this.mLastCarSlideKeyCode) {
                    this.mCarSlideEventCount = 0;
                    this.mLastCarSlideKeyCode = keycode;
                }
                this.mCarSlideEventCount++;
                if (this.mCarSlideEventCount < this.mInputSettings.slideSensitivity) {
                    return 0;
                }
                this.mCarSlideEventCount = 0;
                return keycode == 1083 ? 24 : 25;
            default:
                return 0;
        }
    }

    public static int getKeycode(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).keycode;
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    public static int getOverrideCode(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).overrideCode;
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public static String getOverrideName(int overrideCode) {
        if (overrideCode != 85) {
            switch (overrideCode) {
                case 1:
                    return "KEYCODE_SOFT_LEFT";
                case 2:
                    return "KEYCODE_SOFT_RIGHT";
                case 3:
                    return "KEYCODE_HOME";
                case 4:
                    return "KEYCODE_BACK";
                case 5:
                    return "KEYCODE_CALL";
                default:
                    switch (overrideCode) {
                        case 24:
                            return "KEYCODE_VOLUME_UP";
                        case WindowManagerService.H.SHOW_STRICT_MODE_VIOLATION /* 25 */:
                            return "KEYCODE_VOLUME_DOWN";
                        default:
                            switch (overrideCode) {
                                case HdmiCecKeycode.CEC_KEYCODE_SELECT_SOUND_PRESENTATION /* 87 */:
                                    return "KEYCODE_MEDIA_NEXT";
                                case 88:
                                    return "KEYCODE_MEDIA_PREVIOUS";
                                case 89:
                                    return "KEYCODE_MEDIA_REWIND";
                                case 90:
                                    return "KEYCODE_MEDIA_FAST_FORWARD";
                                default:
                                    switch (overrideCode) {
                                        case 1081:
                                            return "KEYCODE_ICM_SLIDE_UP";
                                        case 1082:
                                            return "KEYCODE_ICM_SLIDE_DOWN";
                                        case 1083:
                                            return "KEYCODE_CAR_SLIDE_UP";
                                        case 1084:
                                            return "KEYCODE_CAR_SLIDE_DOWN";
                                        default:
                                            return "KEYCODE_UNKNOWN";
                                    }
                            }
                    }
            }
        }
        return "KEYCODE_MEDIA_PLAY_PAUSE";
    }

    public static int getSource(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).source;
            } catch (Exception e) {
                return UsbTerminalTypes.TERMINAL_USB_STREAMING;
            }
        }
        return UsbTerminalTypes.TERMINAL_USB_STREAMING;
    }

    public static String getSourceName(int source) {
        if (source != 257) {
            if (source != 513) {
                if (source != 1025) {
                    if (source != 8194) {
                        if (source != 1048584) {
                            if (source == 268435457) {
                                return "SOURCE_CAR";
                            }
                            return "UNKNOWN";
                        }
                        return "SOURCE_TOUCHPAD";
                    }
                    return "SOURCE_MOUSE";
                }
                return "SOURCE_GAMEPAD";
            }
            return "SOURCE_DPAD";
        }
        return "SOURCE_KEYBOARD";
    }

    public static String getMode(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).mode;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static String getKeyName(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).keyname;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static String getWinName(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).win;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static String getFunction(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).function;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static int getDispatchListener(Object object) {
        if (object != null && (object instanceof xpInputManagerService.InputRecorder)) {
            try {
                return ((xpInputManagerService.InputRecorder) object).dispatchListener;
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public static String modeToString(int mode) {
        HashMap<String, Integer> maps = sModeMapping;
        StringBuffer buffer = new StringBuffer(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        buffer.append("[");
        buffer.append(" ");
        for (String key : maps.keySet()) {
            int value = maps.get(key).intValue();
            if ((mode & value) == value) {
                buffer.append(key);
                buffer.append(" ");
            }
        }
        buffer.append("]");
        return buffer.toString();
    }

    public void initCarStateIfNeed() {
        try {
            boolean isReady = ((Boolean) getCarValue("isReady")).booleanValue();
            if (isReady) {
                this.mGear = ((Integer) getCarValue("getVcuGearState")).intValue();
                this.mSteeringAngle = ((Float) getCarValue("getEpsSteeringAngle")).floatValue();
                this.mSteeringAngleSpeed = ((Float) getCarValue("getEpsSteeringAngleSpeed")).floatValue();
                setMode(MODE_ACC, CarManagerHelper.isAccActive(((Integer) getCarValue("getAccStatus")).intValue()));
                setMode(MODE_LCC, CarManagerHelper.isLccActive(((Integer) getCarValue("getVcuCruiseControlStatus")).intValue()));
            }
        } catch (Exception e) {
        }
    }

    public Object getCarValue(Object params) {
        return ExternalManagerService.get(this.mContext).getValue(1, params, new Object[0]);
    }

    public void setCarValue(Object params, Object value) {
        ExternalManagerService.get(this.mContext).setValue(1, params, value);
    }

    public void setIcmWheelKey(int key) {
        try {
            boolean isValid = xpInputManagerService.keycodeValid(key);
            boolean isReady = ((Boolean) getCarValue("isReady")).booleanValue();
            xpLogger.i(TAG, "setIcmWheelKey key=" + key + " isValid=" + isValid + " isReady=" + isReady);
            if (isReady && isValid) {
                setCarValue("setIcmWheelkey", Integer.valueOf(key));
            }
        } catch (Exception e) {
        }
    }

    public void setIcmSyncSignal(String signal) {
        boolean isReady = ((Boolean) getCarValue("isReady")).booleanValue();
        try {
            xpLogger.i(TAG, "setIcmSyncSignal signal=" + signal + " isReady=" + isReady);
            if (isReady) {
                setCarValue("setIcmSyncSignal", signal);
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class InputSignalInfo {
        public String callback;
        public int mode;
        public String signal;

        private InputSignalInfo() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class InputEventSettings {
        public int bossKeyLongSettings;
        public int bossKeySettings;
        public int slideSensitivity;
        public int xpengKeySettings;

        private InputEventSettings() {
            this.xpengKeySettings = -1;
            this.bossKeySettings = -1;
            this.bossKeyLongSettings = -1;
            this.slideSensitivity = 2;
        }

        public String toString() {
            return "settings xkey=" + this.xpengKeySettings + " boss=" + this.bossKeySettings + " lboss=" + this.bossKeyLongSettings + " slide=" + this.slideSensitivity;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class CarManagerHelper {
        private static final int CRUISE_CONTROL_STATUS_CONTROLLING = 2;
        private static final int CRUISE_CONTROL_STATUS_ERROR = 3;
        private static final int CRUISE_CONTROL_STATUS_OFF = 0;
        private static final int CRUISE_CONTROL_STATUS_ON = 1;
        public static final int GEAR_LEVEL_D = 1;
        public static final int GEAR_LEVEL_N = 2;
        private static final int SCU_ACC_ACTIVE = 3;
        private static final int SCU_ACC_BRAKE_ONLY = 4;
        private static final int SCU_ACC_OFF = 0;
        private static final int SCU_ACC_OVERRIDE = 5;
        private static final int SCU_ACC_PASSIVE = 1;
        private static final int SCU_ACC_PERMANENT_FAILURE = 9;
        private static final int SCU_ACC_STANDACTIVE = 6;
        private static final int SCU_ACC_STANDBY = 2;
        private static final int SCU_ACC_STANDWAIT = 7;
        private static final int SCU_ACC_TEMPORARY_FAILURE = 8;

        private CarManagerHelper() {
        }

        public static boolean isAccActive(int state) {
            if (state >= 3 && state <= 7) {
                return true;
            }
            return false;
        }

        public static boolean isLccActive(int state) {
            switch (state) {
                case 0:
                case 3:
                    return false;
                case 1:
                case 2:
                    return true;
                default:
                    return false;
            }
        }

        public static InputSignalInfo makeIcmSignal(int keycode, int mode) {
            InputSignalInfo info = new InputSignalInfo();
            if (keycode == 1004) {
                info.mode = xpInputActionHandler.getAndSetMode(xpInputActionHandler.MODE_ILC, mode, false);
                info.signal = createSignalContent("LeftSwitchMode", false);
            } else if (keycode == 1015) {
                info.mode = xpInputActionHandler.getAndSetMode(xpInputActionHandler.MODE_IRC, mode, false);
                info.signal = createSignalContent("RightSwitchMode", false);
            } else if (keycode == 1024) {
                boolean irc = xpInputActionHandler.hasFlags(xpInputActionHandler.MODE_IRC, mode);
                info.mode = xpInputActionHandler.getAndSetMode(xpInputActionHandler.MODE_ILC, mode, true);
                info.signal = createSignalContent("LeftSwitchMode", true);
                if (irc) {
                    info.mode = xpInputActionHandler.getAndSetMode(xpInputActionHandler.MODE_IRC, info.mode, false);
                    info.callback = createSignalContent("RightSwitchMode", false);
                }
            } else if (keycode == 1035) {
                boolean ilc = xpInputActionHandler.hasFlags(xpInputActionHandler.MODE_ILC, mode);
                info.mode = xpInputActionHandler.getAndSetMode(xpInputActionHandler.MODE_IRC, mode, true);
                info.signal = createSignalContent("RightSwitchMode", true);
                if (ilc) {
                    info.mode = xpInputActionHandler.getAndSetMode(xpInputActionHandler.MODE_ILC, info.mode, false);
                    info.callback = createSignalContent("LeftSwitchMode", false);
                }
            }
            return info;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public static String createSignalContent(String syncMode, boolean value) {
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
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class InputIntentManager {
        private static final String ACTION_BOSS_KEY = "com.xiaopeng.intent.action.bosskey";
        private static final String ACTION_BOSS_KEY_USER = "com.xiaopeng.intent.action.bosskey.user";
        private static final String ACTION_EMERGENCY_IG_OFF = "com.xiaopeng.intent.action.emergency.igoff";
        private static final String ACTION_LOGGER_UPLOADER = "com.xiaopeng.scu.ACTION_UP_LOAD_CAN_LOG_CS";
        private static final String ACTION_SPEECH = "xiaopeng.intent.action.UI_MIC_CLICK";
        private static final String ACTION_XPENG_KEY = "com.xiaopeng.intent.action.xkey";
        private static final String ACTION_XPENG_KEY_USER = "com.xiaopeng.intent.action.xkey.user";
        private static final int BOSS_MUTE = 2;
        private static final int BOSS_NONE = 0;
        private static final int BOSS_SAY_HI = 4;
        private static final int BOSS_SWITCH_AUDIO = 3;
        private static final int BOSS_VOICE = 1;
        private static final int INVALID = -1;
        private static final int XPENG_AUTO_PARKING = 4;
        private static final int XPENG_AUTO_PILOT = 5;
        private static final int XPENG_DRIVE_RECORDER = 3;
        private static final int XPENG_NONE = 0;
        private static final int XPENG_SAY_HI = 2;
        private static final int XPENG_SWITCH_AUDIO = 1;

        private InputIntentManager() {
        }

        public static Intent makeInputIntent(Context context, int keycode, InputEventSettings settings) {
            if (settings == null) {
                return null;
            }
            Intent intent = new Intent();
            intent.addFlags(16777216);
            switch (keycode) {
                case 1005:
                    intent.setAction(ACTION_SPEECH);
                    intent.putExtra("location", getSpeechLocationExtra(keycode));
                    break;
                case 1006:
                    int mode = settings.xpengKeySettings;
                    if (mode != -1) {
                        intent.setAction(ACTION_XPENG_KEY);
                        intent.putExtra("keytype", "short");
                        intent.putExtra("keyfunc", mode);
                        break;
                    } else {
                        intent.setAction(ACTION_XPENG_KEY_USER);
                        break;
                    }
                case UsbTerminalTypes.TERMINAL_BIDIR_HEADSET /* 1026 */:
                    intent.setAction(ACTION_XPENG_KEY_USER);
                    break;
                case 1090:
                case 1092:
                case 1094:
                    int mode2 = settings.bossKeySettings;
                    if (mode2 == -1) {
                        intent.setAction(ACTION_BOSS_KEY_USER);
                        break;
                    } else {
                        switch (mode2) {
                            case 1:
                                intent.setAction(ACTION_SPEECH);
                                intent.putExtra("location", getSpeechLocationExtra(keycode));
                                break;
                            case 2:
                                intent = null;
                                xpInputManager.sendEvent(164);
                                break;
                            default:
                                intent.setAction(ACTION_BOSS_KEY);
                                intent.putExtra("keytype", "short");
                                intent.putExtra("keyfunc", mode2);
                                break;
                        }
                    }
                case 1095:
                    int mode3 = settings.bossKeyLongSettings;
                    intent.setAction(ACTION_BOSS_KEY);
                    intent.putExtra("keytype", "long");
                    intent.putExtra("keyfunc", mode3);
                    break;
                case 1209:
                    intent.setAction(ACTION_EMERGENCY_IG_OFF);
                    break;
                case 1210:
                    intent.setAction(ACTION_LOGGER_UPLOADER);
                    break;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("makeInputIntent keycode=");
            sb.append(keycode);
            sb.append(" action=");
            sb.append(intent != null ? intent.getAction() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            sb.append(" settings=");
            sb.append(settings.toString());
            xpLogger.i(xpInputActionHandler.TAG, sb.toString());
            return intent;
        }

        public static void sendBroadcastAsUser(Context context, int keycode, InputEventSettings settings) {
            try {
                Intent intent = makeInputIntent(context, keycode, settings);
                if (intent != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("sendBroadcastAsUser intent=");
                    sb.append(intent != null ? intent.getAction() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
                    xpLogger.i(xpInputActionHandler.TAG, sb.toString());
                    context.sendBroadcastAsUser(intent, UserHandle.ALL);
                }
            } catch (Exception e) {
            }
        }

        public static void sendValidationBroadcast(Context context, int key, int gear, float speed, float steeringAngle, float steeringAngleSpeed) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String now = sdf.format(Long.valueOf(System.currentTimeMillis()));
            Intent intent = new Intent("android.keyinogre.dot.DOT_ACTION");
            intent.putExtra("android.keyinogre.dottime", now);
            intent.putExtra("android.keyinogre.key", key);
            intent.putExtra("android.keyinogre.speed", speed);
            intent.putExtra("android.keyinogre.anglespeed", steeringAngleSpeed);
            intent.putExtra("android.keyinogre.angle", steeringAngle);
            intent.putExtra("android.keyinogre.gear", gear);
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        private static String getSpeechLocationExtra(int keycode) {
            if (keycode != 1005) {
                if (keycode != 1090) {
                    if (keycode != 1092) {
                        if (keycode != 1094) {
                            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                        }
                        return "front_right";
                    }
                    return "rear_right";
                }
                return "rear_left";
            }
            return "key_speech";
        }
    }

    /* loaded from: classes.dex */
    private static final class InputMediaManager {
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
        private static final Map<Integer, Integer> sCmdMediaKeyMap = new HashMap();

        private InputMediaManager() {
        }

        static {
            sCmdMediaKeyMap.put(126, 0);
            sCmdMediaKeyMap.put(86, 1);
            sCmdMediaKeyMap.put(85, 2);
            sCmdMediaKeyMap.put(90, 5);
            sCmdMediaKeyMap.put(89, 13);
            sCmdMediaKeyMap.put(87, 6);
            sCmdMediaKeyMap.put(88, 7);
        }

        public static boolean isMediaEvent(int keycode) {
            return sCmdMediaKeyMap.containsKey(Integer.valueOf(keycode));
        }

        public static void dispatchMediaEvent(int keycode) {
            try {
                boolean mediaEvent = isMediaEvent(keycode);
                int cmd = mediaEvent ? sCmdMediaKeyMap.get(Integer.valueOf(keycode)).intValue() : 0;
                IAudioService audio = IAudioService.Stub.asInterface(ServiceManager.checkService("audio"));
                StringBuilder sb = new StringBuilder();
                sb.append("dispatchMediaEvent isMediaEvent=");
                sb.append(mediaEvent);
                sb.append(" cmd=");
                sb.append(cmd);
                sb.append(" audio=");
                sb.append(audio != null);
                xpLogger.i(xpInputActionHandler.TAG, sb.toString());
                if (mediaEvent && audio != null) {
                    audio.playbackControl(cmd, 0);
                } else {
                    xpInputManager.sendEvent(keycode);
                }
            } catch (Exception e) {
                xpLogger.i(xpInputActionHandler.TAG, "dispatchMediaEvent keycode=" + keycode + " e=" + e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InputSettingsObserver extends ContentObserver {
        public static final String KEY_ACD_MODE = "airCondition_disable";
        public static final String KEY_ASM_MODE = "main_setting_disable";
        private static final String KEY_BOSS_KEY_LONG_SETTINGS = "XKeyForLongBoss";
        private static final String KEY_BOSS_KEY_SETTINGS = "XKeyForBoss";
        public static final String KEY_BTS_MODE = "speech_need_back";
        private static final String KEY_CAR_SPEED = "key_panel_car_speed";
        public static final String KEY_CHARGE_MODE = "key_vcu_charge_mode";
        public static final String KEY_ILC_MODE = "key_icm_left_switch_mode";
        public static final String KEY_ILI_MODE = "key_icm_left_intercept_mode";
        public static final String KEY_IRC_MODE = "key_icm_right_switch_mode";
        public static final String KEY_IRI_MODE = "key_icm_right_intercept_mode";
        public static final String KEY_KTP_MODE = "KeyBoardTouchPrompt";
        private static final String KEY_MIRROR_MODE = "key_xp_mirror_flag";
        private static final String KEY_QUICK_MODE = "key_xp_quickmenu_flag";
        private static final String KEY_SLIDE_SENSITIVITY = "xp_touch_rotation_speed";
        public static final String KEY_TRD_MODE = "TouchRotationDirection";
        private static final String KEY_WAIST_MODE = "key_xp_waist_flag";
        private static final String KEY_XPENG_KEY_SETTINGS = "XKeyForCustomer";

        public InputSettingsObserver(Handler handler) {
            super(handler);
        }

        public void init() {
            registerObserver(true);
            xpInputActionHandler.this.mInputSettings.xpengKeySettings = getSystemInt(KEY_XPENG_KEY_SETTINGS, xpInputActionHandler.this.mInputSettings.xpengKeySettings);
            xpInputActionHandler.this.mInputSettings.bossKeySettings = getSystemInt(KEY_BOSS_KEY_SETTINGS, xpInputActionHandler.this.mInputSettings.bossKeySettings);
            xpInputActionHandler.this.mInputSettings.bossKeyLongSettings = getSystemInt(KEY_BOSS_KEY_LONG_SETTINGS, xpInputActionHandler.this.mInputSettings.bossKeyLongSettings);
            xpInputActionHandler.this.mInputSettings.slideSensitivity = getSystemInt(KEY_SLIDE_SENSITIVITY, xpInputActionHandler.this.mInputSettings.slideSensitivity);
            xpInputActionHandler.this.mRawSpeed = getSystemInt(KEY_CAR_SPEED, 0);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ACD, shouldDisableAirCondition());
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ILC, getSystemInt(KEY_ILC_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_IRC, getSystemInt(KEY_IRC_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ILI, getSystemInt(KEY_ILI_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_IRI, getSystemInt(KEY_IRI_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ASM, getSystemInt(KEY_ASM_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_BTS, getGlobalInt(KEY_BTS_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_KTP, getSystemInt(KEY_KTP_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_TRD, getSystemInt(KEY_TRD_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_CHARGE, getSystemInt(KEY_CHARGE_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_MIRROR, getSystemInt(KEY_MIRROR_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_QUICK, getSystemInt(KEY_QUICK_MODE, 0) == 1);
            xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_WAIST, getSystemInt(KEY_WAIST_MODE, 0) == 1);
        }

        public void registerObserver(boolean register) {
            ContentResolver resolver = xpInputActionHandler.this.mContext.getContentResolver();
            if (register) {
                resolver.registerContentObserver(getSystemUri(KEY_XPENG_KEY_SETTINGS), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_BOSS_KEY_SETTINGS), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_BOSS_KEY_LONG_SETTINGS), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_SLIDE_SENSITIVITY), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_CAR_SPEED), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_ILC_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_IRC_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_ILI_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_IRI_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_ASM_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_ACD_MODE), false, this);
                resolver.registerContentObserver(getGlobalUri(KEY_BTS_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_TRD_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_KTP_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_CHARGE_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_MIRROR_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_QUICK_MODE), false, this);
                resolver.registerContentObserver(getSystemUri(KEY_WAIST_MODE), false, this);
                return;
            }
            resolver.unregisterContentObserver(this);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri == null) {
                return;
            }
            xpLogger.i(xpInputActionHandler.TAG, "onChange uri=" + uri);
            if (uri.equals(getSystemUri(KEY_XPENG_KEY_SETTINGS))) {
                xpInputActionHandler.this.mInputSettings.xpengKeySettings = getSystemInt(KEY_XPENG_KEY_SETTINGS, xpInputActionHandler.this.mInputSettings.xpengKeySettings);
            } else if (uri.equals(getSystemUri(KEY_BOSS_KEY_SETTINGS))) {
                xpInputActionHandler.this.mInputSettings.bossKeySettings = getSystemInt(KEY_BOSS_KEY_SETTINGS, xpInputActionHandler.this.mInputSettings.bossKeySettings);
            } else if (uri.equals(getSystemUri(KEY_BOSS_KEY_LONG_SETTINGS))) {
                xpInputActionHandler.this.mInputSettings.bossKeyLongSettings = getSystemInt(KEY_BOSS_KEY_LONG_SETTINGS, xpInputActionHandler.this.mInputSettings.bossKeyLongSettings);
            } else if (uri.equals(getSystemUri(KEY_SLIDE_SENSITIVITY))) {
                xpInputActionHandler.this.mInputSettings.slideSensitivity = getSystemInt(KEY_SLIDE_SENSITIVITY, xpInputActionHandler.this.mInputSettings.slideSensitivity);
            } else {
                if (uri.equals(getSystemUri(KEY_CAR_SPEED))) {
                    xpInputActionHandler.this.mRawSpeed = getSystemInt(KEY_CAR_SPEED, 0);
                    return;
                }
                if (uri.equals(getSystemUri(KEY_TRD_MODE))) {
                    xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_TRD, getSystemInt(KEY_TRD_MODE, 0) == 1);
                } else if (uri.equals(getSystemUri(KEY_KTP_MODE))) {
                    xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_KTP, getSystemInt(KEY_KTP_MODE, 0) == 1);
                } else if (!uri.equals(getSystemUri(KEY_ILC_MODE)) && !uri.equals(getSystemUri(KEY_IRC_MODE))) {
                    if (uri.equals(getSystemUri(KEY_ILI_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ILI, getSystemInt(KEY_ILI_MODE, 0) == 1);
                    } else if (uri.equals(getSystemUri(KEY_IRI_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_IRI, getSystemInt(KEY_IRI_MODE, 0) == 1);
                    } else if (uri.equals(getGlobalUri(KEY_BTS_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_BTS, getGlobalInt(KEY_BTS_MODE, 0) == 1);
                    } else if (uri.equals(getSystemUri(KEY_ASM_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ASM, getSystemInt(KEY_ASM_MODE, 0) == 1);
                    } else if (uri.equals(getSystemUri(KEY_ACD_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_ACD, shouldDisableAirCondition());
                    } else if (uri.equals(getSystemUri(KEY_CHARGE_MODE))) {
                        boolean charging = getSystemInt(KEY_CHARGE_MODE, 0) == 1;
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_CHARGE, charging);
                        if (charging) {
                            xpInputActionHandler.this.setIcmMode(false, false);
                        }
                    } else if (uri.equals(getSystemUri(KEY_MIRROR_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_MIRROR, getSystemInt(KEY_MIRROR_MODE, 0) == 1);
                    } else if (uri.equals(getSystemUri(KEY_QUICK_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_QUICK, getSystemInt(KEY_QUICK_MODE, 0) == 1);
                    } else if (uri.equals(getSystemUri(KEY_WAIST_MODE))) {
                        xpInputActionHandler.this.setMode(xpInputActionHandler.MODE_WAIST, getSystemInt(KEY_WAIST_MODE, 0) == 1);
                    }
                }
            }
        }

        public boolean shouldDisableAirCondition() {
            return getSystemInt(KEY_ACD_MODE, 0) == 1;
        }

        public int getSystemInt(String key, int defaultValue) {
            try {
                ContentResolver resolver = xpInputActionHandler.this.mContext.getContentResolver();
                return Settings.System.getInt(resolver, key, defaultValue);
            } catch (Exception e) {
                return defaultValue;
            }
        }

        public int getGlobalInt(String key, int defaultValue) {
            try {
                ContentResolver resolver = xpInputActionHandler.this.mContext.getContentResolver();
                return Settings.Global.getInt(resolver, key, defaultValue);
            } catch (Exception e) {
                return defaultValue;
            }
        }

        public Uri getSystemUri(String name) {
            return Settings.System.getUriFor(name);
        }

        public Uri getGlobalUri(String name) {
            return Settings.Global.getUriFor(name);
        }
    }
}
