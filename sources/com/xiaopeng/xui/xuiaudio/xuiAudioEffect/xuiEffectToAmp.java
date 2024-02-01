package com.xiaopeng.xui.xuiaudio.xuiAudioEffect;

import android.car.hardware.XpVehicle.IXpVehicle;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import com.xiaopeng.xui.xuiaudio.utils.remoteConnect;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;

/* loaded from: classes2.dex */
public class xuiEffectToAmp implements IXuiEffectInterface, remoteConnect.RemoteConnectListerer {
    private static final int CHANGE_SOUNDEFFECT_DELAY = 500;
    private static final int MSG_CHANGE_SOUNDEFFECT = 1;
    public static final int SOUNDFIELD_DYNA_DRIVER = 2;
    public static final int SOUNDFIELD_DYNA_MIDDLE = 8;
    public static final int SOUNDFIELD_DYNA_PASSENGER = 3;
    public static final int SOUNDFIELD_DYNA_REAR_MIDDLE = 7;
    public static final int SOUNDFIELD_OFF = 1;
    public static final int SOUNDFIELD_XSOUND_DRIVER = 4;
    public static final int SOUNDFIELD_XSOUND_MIDDLE = 6;
    public static final int SOUNDFIELD_XSOUND_PASSENGER = 5;
    public static final int SOUNDFIELD_XSOUND_RL_PASSENGER = 9;
    public static final int SOUNDFIELD_XSOUND_RR_PASSENGER = 10;
    private static final String TAG = "xuiEffectToAmp";
    private static int mAudioFeature = 0;
    private static Context mContext;
    private static xuiEffectToAmp mInstance;
    private static remoteConnect mRemoteConnect;
    private static xuiAudioData mXuiAudioData;
    private static xuiAudioPolicy mXuiAudioPolicy;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private IXpVehicle mXpVehicle;

    private xuiEffectToAmp(Context context, xuiAudioPolicy instance) {
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

    public static xuiEffectToAmp getInstance(Context context, xuiAudioPolicy instance) {
        if (mInstance == null) {
            mInstance = new xuiEffectToAmp(context, instance);
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
            if (type >= 1 && type < 5) {
                setSoundEffectType(mode, type);
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setSoundEffectType(int mode, int type) {
        Log.i(TAG, "setSoundEffectType high:  mode:" + mode + " type:" + type);
        if (this.mXpVehicle != null) {
            if (mode == 3) {
                type += 4;
            }
            try {
                if (xuiEffectPolicy.XSOUND_AMP_SOP_PROTECT) {
                    type = 1;
                }
                if (this.mHandler != null) {
                    this.mXpVehicle.setAmpMute(1);
                }
                this.mXpVehicle.setAmpMusicStyle(type);
                if (this.mHandler != null) {
                    Message m = this.mHandler.obtainMessage(1, 0, 0, 0);
                    this.mHandler.sendMessageDelayed(m, 500L);
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
        try {
            if (mode == 3) {
                if (xSound >= 0 && xSound < 50 && ySound >= 0 && ySound < 33) {
                    soundfieldmode = 2;
                } else if (xSound >= 50 && xSound <= 100 && ySound >= 0 && ySound < 33) {
                    soundfieldmode = 3;
                } else if (ySound >= 33 && ySound < 66) {
                    soundfieldmode = 8;
                } else {
                    soundfieldmode = 7;
                }
            } else if (mode == 1) {
                if (xSound >= 0 && xSound < 50 && ySound >= 0 && ySound < 33) {
                    soundfieldmode = 4;
                } else if (xSound >= 50 && xSound <= 100 && ySound >= 0 && ySound < 33) {
                    soundfieldmode = 5;
                } else if (ySound >= 33 && ySound < 66) {
                    soundfieldmode = 6;
                } else if (xSound >= 0 && xSound < 50 && ySound >= 66 && ySound <= 100) {
                    soundfieldmode = 9;
                } else {
                    soundfieldmode = 10;
                }
            } else {
                Log.w(TAG, "setSoundField  failed  mode=" + mode);
                return;
            }
            Log.i(TAG, "setSoundField soundfieldmode:" + soundfieldmode);
            if (this.mHandler != null) {
                this.mXpVehicle.setAmpMute(1);
            }
            this.mXpVehicle.setAmpSoundFieldMode(soundfieldmode);
            if (this.mHandler != null) {
                Message m = this.mHandler.obtainMessage(1, 0, 0, 0);
                this.mHandler.sendMessageDelayed(m, 500L);
            }
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
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setDyn3dEffectLevel(int level) {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioEffect.IXuiEffectInterface
    public void setXpCustomizeEffect() {
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
            Log.d(xuiEffectToAmp.TAG, "handleMessage " + msg.what + " " + msg.arg1 + " " + msg.arg2);
            if (msg.what == 1 && xuiEffectToAmp.this.mXpVehicle != null) {
                try {
                    xuiEffectToAmp.this.mXpVehicle.setAmpMute(0);
                } catch (Exception e) {
                    xuiEffectToAmp.this.handleException(e);
                }
                Log.i(xuiEffectToAmp.TAG, "setAmpMute(0)");
            }
        }
    }
}
