package com.xiaopeng.xui.xuiaudio.xuiAudioEffect;

import android.car.hardware.XpVehicle.IXpVehicle;
import android.content.Context;
import android.media.SoundField;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import com.xiaopeng.xui.xuiaudio.utils.remoteConnect;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import java.util.Arrays;

/* loaded from: classes2.dex */
public class xuiEffectToPioneerAmp implements IXuiEffectInterface, remoteConnect.RemoteConnectListerer {
    private static final int CHANGE_SOUNDEFFECT_DELAY = 500;
    private static final int MSG_CHANGE_SOUNDEFFECT = 1;
    public static final int SOUNDFIELD_DRIVER = 2;
    public static final int SOUNDFIELD_FRONT_MIDDLE = 12;
    public static final int SOUNDFIELD_MIDDLE = 8;
    public static final int SOUNDFIELD_OFF = 1;
    public static final int SOUNDFIELD_PASSENGER = 3;
    public static final int SOUNDFIELD_REAR_MIDDLE = 7;
    public static final int SOUNDFIELD_RL_PASSENGER = 9;
    public static final int SOUNDFIELD_RR_PASSENGER = 10;
    public static final int SOUNDFIELD_SURROUND = 11;
    private static final String TAG = "xuiEffectToPioneerAmp";
    private static int mAudioFeature = 0;
    private static Context mContext;
    private static xuiEffectToPioneerAmp mInstance;
    private static remoteConnect mRemoteConnect;
    private static xuiAudioData mXuiAudioData;
    private static xuiAudioPolicy mXuiAudioPolicy;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private IXpVehicle mXpVehicle;

    private xuiEffectToPioneerAmp(Context context, xuiAudioPolicy instance) {
        this.mHandlerThread = null;
        this.mHandler = null;
        mContext = context;
        mXuiAudioPolicy = instance;
        mRemoteConnect = remoteConnect.getInstance(context);
        mRemoteConnect.registerListener(this);
        mXuiAudioData = xuiAudioData.getInstance(context);
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread("HighAmpPolicy");
        }
        this.mHandlerThread.start();
        this.mHandler = new xpHighPolicyHandler(this.mHandlerThread.getLooper());
    }

    public static xuiEffectToPioneerAmp getInstance(Context context, xuiAudioPolicy instance) {
        if (mInstance == null) {
            mInstance = new xuiEffectToPioneerAmp(context, instance);
        }
        return mInstance;
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onCarServiceConnected() {
        Log.d(TAG, "onCarServiceConnected()");
        remoteConnect remoteconnect = mRemoteConnect;
        if (remoteconnect != null) {
            this.mXpVehicle = remoteconnect.getXpVehicle();
        }
        try {
            int mode = mXuiAudioData.getXpAudioEffectMode();
            SoundField mSoundField = mXuiAudioData.getSoundField(mode);
            if (mSoundField != null) {
                setSoundField(mode, mSoundField.x, mSoundField.y);
            }
            setSoundEffectType(mode, mXuiAudioData.getXpAudioEffectType(mode));
            setSoundSpeedLinkLevel(mXuiAudioData.getSoundSpeedLinkLevel());
            setDyn3dEffectLevel(mXuiAudioData.getDyn3dEffectLevel());
        } catch (Exception e) {
            handleException(e);
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onXuiServiceConnected() {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void igStatusChange(int igstat) {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setSoundEffectMode(int mode) {
        if (this.mXpVehicle != null) {
            int type = mXuiAudioData.getXpAudioEffectType(mode);
            Log.i(TAG, "setSoundEffectMode high:   mode:" + mode + " type:" + type);
            if (type >= 0 && type < 5) {
                setSoundEffectType(mode, type);
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setSoundEffectType(int mode, int type) {
        Log.i(TAG, "setSoundEffectType high:  mode:" + mode + " type:" + type);
        IXpVehicle iXpVehicle = this.mXpVehicle;
        if (iXpVehicle != null) {
            try {
                iXpVehicle.setAmpSoundStyle(type);
                if (type == 3) {
                    setXpCustomizeEffect();
                }
            } catch (Exception e) {
                handleException(e);
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setSoundEffectScene(int mode, int scene) {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setSoundField(int mode, int xSound, int ySound) {
        int soundfieldmode;
        Log.i(TAG, "setSoundField high:  mode:" + mode + " xSound:" + xSound + " ySound:" + ySound);
        if (this.mXpVehicle == null || xSound < 0 || xSound > 100 || ySound < 0 || ySound > 100) {
            return;
        }
        if (xSound < 0 || xSound >= 33 || ySound < 0 || ySound >= 33) {
            if (xSound >= 33 && xSound < 66 && ySound >= 0 && ySound < 33) {
                soundfieldmode = 12;
            } else if (xSound >= 66 && xSound <= 100 && ySound >= 0 && ySound < 33) {
                soundfieldmode = 3;
            } else if (ySound >= 33 && ySound < 66) {
                soundfieldmode = 8;
            } else if (xSound >= 0 && xSound < 33 && ySound >= 66 && ySound <= 100) {
                soundfieldmode = 9;
            } else if (xSound >= 33 && xSound <= 66 && ySound >= 66 && ySound <= 100) {
                soundfieldmode = 7;
            } else if (xSound > 66 && xSound <= 100 && ySound >= 66 && ySound <= 100) {
                soundfieldmode = 10;
            } else {
                soundfieldmode = 2;
            }
        } else {
            soundfieldmode = 2;
        }
        try {
            Log.i(TAG, "setSoundField soundfieldmode:" + soundfieldmode);
            this.mXpVehicle.setAmpSoundFieldMode(soundfieldmode);
        } catch (Exception e) {
            handleException(e);
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public int getSoundEffectMode() {
        return -1;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public int getSoundEffectType(int mode) {
        return -1;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public int getSoundEffectScene(int mode) {
        return -1;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public int getSoundField(int mode) {
        return -1;
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setSoundSpeedLinkLevel(int level) {
        IXpVehicle iXpVehicle = this.mXpVehicle;
        if (iXpVehicle != null) {
            try {
                iXpVehicle.setAmpSdsscLevel(level);
            } catch (Exception e) {
                handleException(e);
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setDyn3dEffectLevel(int level) {
        IXpVehicle iXpVehicle = this.mXpVehicle;
        if (iXpVehicle != null) {
            try {
                iXpVehicle.setAmpDyn3DEffectLevel(level);
            } catch (Exception e) {
                handleException(e);
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setXpCustomizeEffect() {
        if (this.mXpVehicle != null) {
            int[] data = new int[10];
            for (int i = 0; i < 10; i++) {
                data[i] = mXuiAudioData.getXpCustomizeEffect(i);
            }
            try {
                Log.d(TAG, "setXpCustomizeEffect " + Arrays.toString(data));
                this.mXpVehicle.setAmpFreqGainGroupControlValue(data);
            } catch (Exception e) {
                handleException(e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleException(Exception e) {
        Log.e(TAG, e.toString());
    }

    /* loaded from: classes2.dex */
    public class xpHighPolicyHandler extends Handler {
        public xpHighPolicyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            Log.d(xuiEffectToPioneerAmp.TAG, "handleMessage " + msg.what + " " + msg.arg1 + " " + msg.arg2);
            if (msg.what == 1 && xuiEffectToPioneerAmp.this.mXpVehicle != null) {
                try {
                    xuiEffectToPioneerAmp.this.mXpVehicle.setAmpMute(0);
                } catch (Exception e) {
                    xuiEffectToPioneerAmp.this.handleException(e);
                }
                Log.i(xuiEffectToPioneerAmp.TAG, "setAmpMute(0)");
            }
        }
    }
}
