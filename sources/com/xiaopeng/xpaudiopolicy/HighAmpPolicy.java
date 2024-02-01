package com.xiaopeng.xpaudiopolicy;

import android.media.IAudioService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import com.android.server.SystemService;
import java.util.Map;

/* loaded from: classes2.dex */
class HighAmpPolicy implements IXpAmpPolicy {
    private static final int CHANGE_SOUNDEFFECT_DELAY = SystemProperties.getInt("persist.audio.changesound.delay", (int) SystemService.PHASE_SYSTEM_SERVICES_READY);
    private static final int MAIN_DRIVER_MODE_DRIVER = 1;
    private static final int MAIN_DRIVER_MODE_NO_ACTIVE = 0;
    private static final int MAIN_DRIVER_MODE_SILENCE = 2;
    private static final int MSG_CHANGE_SOUNDEFFECT = 1;
    public static final String PROP_DYNAUDIO_TYPE = "persist.audio.xpeffect.dynaudio.type";
    public static final String PROP_XSOUND_TYPE = "persist.audio.xpeffect.xsound.type";
    public static final String PROP_XUIAUDIO_MODE = "persist.audio.xpeffect.mode";
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
    private static final String TAG = "HighAmpPolicy";
    public static final int VOICE_POSITION_ALL = 5;
    public static final int VOICE_POSITION_CENTER = 0;
    public static final int VOICE_POSITION_FRONTLEFT = 1;
    public static final int VOICE_POSITION_FRONTRIGHT = 2;
    public static final int VOICE_POSITION_INVALID = -1;
    public static final int VOICE_POSITION_REARLEFT = 3;
    public static final int VOICE_POSITION_REARRIGHT = 4;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private IAudioService sService;
    private boolean mStereoAlarm = false;
    private boolean mSpeechSurround = false;
    private boolean mMainDriver = false;
    private int mMainDriverMode = SystemProperties.getInt("persist.sys.xiaopeng.maindriver.mode", 0);
    private boolean mBtHeadPhone = false;
    private boolean isCallingOut = false;
    private boolean isCallingIn = false;
    private int XP_AMP_CHANNEL_ALL = 4095;
    private int XP_AMP_CHANNEL_ALL_LOCATION = 63;
    private int XP_AMP_CHANNEL_FRONT = 64;
    private int XP_AMP_CHANNEL_FRONTLEFT = 1;
    private int XP_AMP_CHANNEL_FRONTRIGHT = 2;
    private int XP_AMP_CHANNEL_REARLEFT = 4;
    private int XP_AMP_CHANNEL_REARRIGHT = 8;
    private int XP_AMP_CHANNEL_FRONT_LOCATION = 3;
    private int XP_AMP_CHANNEL_MAIN_DRIVER = 3072;
    private int mAlarmChannels = 0;
    private int mSpeechChannels = this.XP_AMP_CHANNEL_FRONT;
    private int voiceposition = -1;
    private boolean btCallOn = false;
    private int btCallOnFlag = 0;
    private boolean isKaraokeOn = false;

    public HighAmpPolicy() {
        this.sService = null;
        this.mHandlerThread = null;
        this.mHandler = null;
        if (this.sService == null) {
            IBinder b = ServiceManager.getService("audio");
            this.sService = IAudioService.Stub.asInterface(b);
        }
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread(TAG);
        }
        this.mHandlerThread.start();
        this.mHandler = new xpHighPolicyHandler(this.mHandlerThread.getLooper());
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void initAreaConfig(Map<String, Integer> areaMap) {
        if (areaMap == null) {
            return;
        }
        this.XP_AMP_CHANNEL_ALL = areaMap.get("ALL_AREA").intValue();
        this.XP_AMP_CHANNEL_ALL_LOCATION = areaMap.get("ALL_LOCATION").intValue();
        this.XP_AMP_CHANNEL_FRONT = areaMap.get("FRONT").intValue();
        this.XP_AMP_CHANNEL_FRONT_LOCATION = areaMap.get("FRONT_LOCATION").intValue();
        this.XP_AMP_CHANNEL_MAIN_DRIVER = areaMap.get("MAIN_DRIVER").intValue();
        this.mSpeechChannels = -1;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setStereoAlarm(boolean enable) {
        this.mStereoAlarm = enable;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSpeechSurround(boolean enable) {
        this.mSpeechSurround = enable;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setAlarmChannels(int bits) {
        this.mAlarmChannels = bits;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setMainDriver(boolean enable) {
        this.mMainDriver = enable;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setBtHeadPhone(boolean enable) {
        this.mBtHeadPhone = enable;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setBtCallOn(boolean enable) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setBtCallOnFlag(int flag) {
        this.btCallOnFlag = flag;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setCallingOut(boolean enable) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setCallingIn(boolean enable) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setVoicePosition(int position, int flag) {
        Log.i(TAG, "setVoicePosition position:" + position);
        switch (position) {
            case -1:
                this.mSpeechChannels = -1;
                break;
            case 0:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_FRONT;
                break;
            case 1:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_FRONTLEFT;
                break;
            case 2:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_FRONTRIGHT;
                break;
            case 3:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_REARLEFT;
                break;
            case 4:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_REARRIGHT;
                break;
            case 5:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_ALL;
                break;
            default:
                Log.e(TAG, "setVoicePosition  ERROR position:" + position);
                return;
        }
        this.voiceposition = position;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getVoicePosition() {
        return this.voiceposition;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setKaraokeOn(boolean on) {
        this.isKaraokeOn = on;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setDangerousTtsOn(boolean on) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getChannels(int soundType) {
        Log.d(TAG, "getChannels soundType:" + soundType + " mStereoAlarm:" + this.mStereoAlarm + " mMainDriver:" + this.mMainDriver + " mBtHeadPhone:" + this.mBtHeadPhone + " btCallOnFlag:" + this.btCallOnFlag + " isCallingOut:" + this.isCallingOut + " isCallingIn:" + this.isCallingIn + " isKaraokeOn:" + this.isKaraokeOn + " mMainDriverMode:" + this.mMainDriverMode);
        if (XpAudioPolicy.isNewMainDriverMode && this.mMainDriverMode == 2) {
            return this.XP_AMP_CHANNEL_MAIN_DRIVER;
        }
        switch (soundType) {
            case 1:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mSpeechChannels != -1) {
                        int channelBits = this.mSpeechChannels;
                        return channelBits;
                    } else if (this.mBtHeadPhone && this.btCallOnFlag != 0) {
                        int channelBits2 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits2;
                    } else {
                        int channelBits3 = this.XP_AMP_CHANNEL_FRONT;
                        return channelBits3;
                    }
                } else if (this.mMainDriverMode == 1) {
                    if (this.btCallOnFlag != 0) {
                        int channelBits4 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits4;
                    } else if (!this.isKaraokeOn) {
                        if (this.mSpeechChannels != -1) {
                            int channelBits5 = this.mSpeechChannels;
                            return channelBits5;
                        }
                        int channelBits6 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits6;
                    } else {
                        int channelBits7 = this.XP_AMP_CHANNEL_ALL;
                        return channelBits7;
                    }
                } else {
                    int channelBits8 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits8;
                }
            case 2:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriver) {
                        int channelBits9 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits9;
                    }
                    int channelBits10 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits10;
                } else if (this.mMainDriverMode == 1) {
                    int channelBits11 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits11;
                } else {
                    int channelBits12 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits12;
                }
            case 3:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriver) {
                        int channelBits13 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits13;
                    }
                    int channelBits14 = this.XP_AMP_CHANNEL_ALL;
                    return channelBits14;
                }
                int channelBits15 = this.XP_AMP_CHANNEL_ALL;
                return channelBits15;
            case 4:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mBtHeadPhone) {
                        int channelBits16 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits16;
                    }
                    int channelBits17 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits17;
                } else if (this.mMainDriverMode == 1) {
                    int channelBits18 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits18;
                } else {
                    int channelBits19 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits19;
                }
            case 5:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mSpeechChannels != -1) {
                        int channelBits20 = this.mSpeechChannels;
                        return channelBits20;
                    }
                    int channelBits21 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits21;
                } else if (this.mMainDriverMode != 1 || this.btCallOnFlag == 0) {
                    if (this.mSpeechChannels != -1) {
                        int channelBits22 = this.mSpeechChannels;
                        return channelBits22;
                    }
                    int channelBits23 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits23;
                } else {
                    int channelBits24 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits24;
                }
            case 6:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriver) {
                        int channelBits25 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits25;
                    }
                    int channelBits26 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits26;
                } else if (this.mMainDriverMode == 1) {
                    if (this.isKaraokeOn) {
                        int channelBits27 = this.XP_AMP_CHANNEL_ALL;
                        return channelBits27;
                    }
                    int channelBits28 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits28;
                } else {
                    int channelBits29 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits29;
                }
            case 7:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mSpeechChannels != -1) {
                        int channelBits30 = this.mSpeechChannels;
                        return channelBits30;
                    } else if (this.mMainDriver) {
                        int channelBits31 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits31;
                    } else if (this.mBtHeadPhone && this.btCallOnFlag != 0) {
                        int channelBits32 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits32;
                    } else {
                        int channelBits33 = this.XP_AMP_CHANNEL_FRONT;
                        return channelBits33;
                    }
                } else if (this.mMainDriverMode == 1) {
                    if (this.btCallOnFlag != 0) {
                        int channelBits34 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits34;
                    } else if (!this.isKaraokeOn) {
                        if (this.mSpeechChannels != -1) {
                            int channelBits35 = this.mSpeechChannels;
                            return channelBits35;
                        }
                        int channelBits36 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits36;
                    } else {
                        int channelBits37 = this.XP_AMP_CHANNEL_ALL;
                        return channelBits37;
                    }
                } else {
                    int channelBits38 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits38;
                }
            default:
                return 0;
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setAmpChanVolSource(int channelbit, int volume, int soundSource, int activebit) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectMode(int mode) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectType(int mode, int type) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectScene(int mode, int scene) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundField(int mode, int xSound, int ySound) {
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setMainDriverMode(int mode) {
        this.mMainDriverMode = mode;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getMainDriverMode() {
        return this.mMainDriverMode;
    }

    /* loaded from: classes2.dex */
    public class xpHighPolicyHandler extends Handler {
        public xpHighPolicyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            Log.d(HighAmpPolicy.TAG, "handleMessage " + msg.what + " " + msg.arg1 + " " + msg.arg2);
            int i = msg.what;
        }
    }
}
