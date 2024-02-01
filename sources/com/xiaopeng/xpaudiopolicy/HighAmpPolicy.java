package com.xiaopeng.xpaudiopolicy;

import android.car.hardware.XpVehicle.IXpVehicle;
import android.media.IAudioService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import com.xiaopeng.xpaudioeffectpolicy.XpAudioEffect;
import java.util.Map;
/* loaded from: classes.dex */
class HighAmpPolicy implements IXpAmpPolicy {
    private static final int CHANGE_SOUNDEFFECT_DELAY = SystemProperties.getInt("persist.audio.changesound.delay", 1000);
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
    public static final int VOICE_POSITION_HEAD = 8;
    public static final int VOICE_POSITION_INVALID = -1;
    public static final int VOICE_POSITION_REARLEFT = 3;
    public static final int VOICE_POSITION_REARRIGHT = 4;
    public static final int VOICE_POSITION_SL = 6;
    public static final int VOICE_POSITION_SR = 7;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private IAudioService sService = null;
    private IXpVehicle mXpVehicle = null;
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
    private int XP_AMP_CHANNEL_SL = 16;
    private int XP_AMP_CHANNEL_SR = 32;
    private int XP_AMP_CHANNEL_HEAD = 3072;
    private int mAlarmChannels = 0;
    private int mSpeechChannels = this.XP_AMP_CHANNEL_FRONT;
    private int voiceposition = -1;
    private boolean btCallOn = false;
    private int btCallOnFlag = 0;
    private boolean isKaraokeOn = false;
    private boolean dangerousTtsOn = false;
    private boolean isAmpAllChannelOn = SystemProperties.getBoolean("persist.sys.xiaopeng.amp.allchannel.on", false);

    public HighAmpPolicy() {
        this.mHandlerThread = null;
        this.mHandler = null;
        if (this.sService == null) {
            bindAudioService();
        }
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread(TAG);
        }
        this.mHandlerThread.start();
        this.mHandler = new xpHighPolicyHandler(this.mHandlerThread.getLooper());
    }

    private void bindAudioService() {
        IBinder b = ServiceManager.getService("audio");
        this.sService = IAudioService.Stub.asInterface(b);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleException(Exception e) {
        Log.e(TAG, e.toString());
        this.mXpVehicle = null;
        bindAudioService();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public IXpVehicle getXpVehicle() {
        if (this.sService != null && this.mXpVehicle == null) {
            try {
                this.mXpVehicle = this.sService.getXpVehicle();
            } catch (RemoteException e) {
                handleException(e);
            }
        }
        return this.mXpVehicle;
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
            case 6:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_SL;
                break;
            case 7:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_SR;
                break;
            case 8:
                this.mSpeechChannels = this.XP_AMP_CHANNEL_HEAD;
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
        this.dangerousTtsOn = on;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getChannels(int soundType) {
        Log.d(TAG, "getChannels soundType:" + soundType + " mStereoAlarm:" + this.mStereoAlarm + " mMainDriver:" + this.mMainDriver + " mBtHeadPhone:" + this.mBtHeadPhone + " btCallOnFlag:" + this.btCallOnFlag + " isCallingOut:" + this.isCallingOut + " isCallingIn:" + this.isCallingIn + " isKaraokeOn:" + this.isKaraokeOn + " mMainDriverMode:" + this.mMainDriverMode + " dangerousTtsOn:" + this.dangerousTtsOn);
        if (XpAudioPolicy.isNewMainDriverMode && this.mMainDriverMode == 2) {
            return this.XP_AMP_CHANNEL_MAIN_DRIVER;
        }
        switch (soundType) {
            case 1:
                if (XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriverMode == 1) {
                        if (this.btCallOnFlag != 0) {
                            int channelBits = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                            return channelBits;
                        } else if (this.isKaraokeOn) {
                            int channelBits2 = this.XP_AMP_CHANNEL_ALL;
                            return channelBits2;
                        } else if (this.dangerousTtsOn) {
                            int channelBits3 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                            return channelBits3;
                        } else if (this.mSpeechChannels != -1) {
                            int channelBits4 = this.mSpeechChannels;
                            return channelBits4;
                        } else {
                            int channelBits5 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                            return channelBits5;
                        }
                    }
                    int channelBits6 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits6;
                } else if (this.mSpeechChannels != -1) {
                    int channelBits7 = this.mSpeechChannels;
                    return channelBits7;
                } else if (this.mBtHeadPhone && this.btCallOnFlag != 0) {
                    int channelBits8 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits8;
                } else {
                    int channelBits9 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits9;
                }
            case 2:
                if (XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriverMode == 1) {
                        int channelBits10 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits10;
                    }
                    int channelBits11 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits11;
                } else if (this.mMainDriver) {
                    int channelBits12 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits12;
                } else {
                    int channelBits13 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits13;
                }
            case 3:
                if (!XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriver) {
                        int channelBits14 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits14;
                    }
                    int channelBits15 = this.XP_AMP_CHANNEL_ALL;
                    return channelBits15;
                }
                int channelBits16 = this.XP_AMP_CHANNEL_ALL;
                return channelBits16;
            case 4:
                if (XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriverMode == 1) {
                        int channelBits17 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits17;
                    }
                    int channelBits18 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits18;
                } else if (this.mBtHeadPhone) {
                    int channelBits19 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits19;
                } else {
                    int channelBits20 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits20;
                }
            case 5:
                if (XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriverMode == 1 && (this.btCallOnFlag != 0 || this.dangerousTtsOn)) {
                        int channelBits21 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits21;
                    } else if (this.mSpeechChannels != -1) {
                        int channelBits22 = this.mSpeechChannels;
                        return channelBits22;
                    } else {
                        int channelBits23 = this.XP_AMP_CHANNEL_FRONT;
                        return channelBits23;
                    }
                } else if (this.mSpeechChannels != -1) {
                    int channelBits24 = this.mSpeechChannels;
                    return channelBits24;
                } else {
                    int channelBits25 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits25;
                }
            case 6:
                if (XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriverMode == 1) {
                        if (this.isKaraokeOn) {
                            int channelBits26 = this.XP_AMP_CHANNEL_ALL;
                            return channelBits26;
                        }
                        int channelBits27 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                        return channelBits27;
                    }
                    int channelBits28 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits28;
                } else if (this.mMainDriver) {
                    int channelBits29 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits29;
                } else {
                    int channelBits30 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits30;
                }
            case 7:
                if (XpAudioPolicy.isNewMainDriverMode) {
                    if (this.mMainDriverMode == 1) {
                        if (this.btCallOnFlag != 0 || this.dangerousTtsOn) {
                            int channelBits31 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                            return channelBits31;
                        } else if (this.isKaraokeOn) {
                            int channelBits32 = this.XP_AMP_CHANNEL_ALL;
                            return channelBits32;
                        } else if (this.mSpeechChannels != -1) {
                            int channelBits33 = this.mSpeechChannels;
                            return channelBits33;
                        } else {
                            int channelBits34 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                            return channelBits34;
                        }
                    }
                    int channelBits35 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits35;
                } else if (this.mSpeechChannels != -1) {
                    int channelBits36 = this.mSpeechChannels;
                    return channelBits36;
                } else if (this.mMainDriver) {
                    int channelBits37 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits37;
                } else if (this.mBtHeadPhone && this.btCallOnFlag != 0) {
                    int channelBits38 = this.XP_AMP_CHANNEL_MAIN_DRIVER;
                    return channelBits38;
                } else {
                    int channelBits39 = this.XP_AMP_CHANNEL_FRONT;
                    return channelBits39;
                }
            default:
                return 0;
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setAmpChanVolSource(int channelbit, int volume, int soundSource, int activebit) {
        IXpVehicle tXpVehicle = getXpVehicle();
        Log.i(TAG, "setAmpChanVolSource high:   channelbit:" + channelbit + " volume:" + volume + " soundSource:" + soundSource + " activebit:" + activebit);
        if (tXpVehicle != null) {
            try {
                if (this.isAmpAllChannelOn) {
                    tXpVehicle.setAmpChannelVolAndSource(1023, 100, 3, 1023);
                } else {
                    tXpVehicle.setAmpChannelVolAndSource(channelbit, volume, soundSource, activebit);
                }
            } catch (RemoteException e) {
                handleException(e);
            }
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectMode(int mode) {
        IXpVehicle tXpVehicle = getXpVehicle();
        if (tXpVehicle != null) {
            int type = 1;
            if (mode == 1) {
                type = SystemProperties.getInt("persist.audio.xpeffect.xsound.type", 1);
            } else if (mode == 3) {
                type = SystemProperties.getInt("persist.audio.xpeffect.dynaudio.type", 3);
            }
            Log.i(TAG, "setSoundEffectMode high:   mode:" + mode + " type:" + type);
            if (type >= 1 && type < 5) {
                setSoundEffectType(mode, type);
            }
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectType(int mode, int type) {
        IXpVehicle tXpVehicle = getXpVehicle();
        Log.i(TAG, "setSoundEffectType high:  mode:" + mode + " type:" + type);
        if (tXpVehicle != null) {
            if (mode == 3) {
                type += 4;
            }
            try {
                if (XpAudioEffect.XSOUND_AMP_SOP_PROTECT) {
                    type = 1;
                }
                if (this.mHandler != null) {
                    tXpVehicle.setAmpMute(1);
                    Thread.sleep(50L);
                }
                tXpVehicle.setAmpMusicStyle(type);
                if (this.mHandler != null) {
                    this.mHandler.removeMessages(1);
                    Message m = this.mHandler.obtainMessage(1, 0, 0, 0);
                    this.mHandler.sendMessageDelayed(m, CHANGE_SOUNDEFFECT_DELAY);
                }
            } catch (Exception e) {
                handleException(e);
            }
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundEffectScene(int mode, int scene) {
        IXpVehicle tXpVehicle = getXpVehicle();
        Log.i(TAG, "setSoundEffectScene high:   mode:" + mode + " scene:" + scene);
        if (tXpVehicle != null) {
            try {
                if (this.mHandler != null) {
                    tXpVehicle.setAmpMute(1);
                    Thread.sleep(50L);
                }
                if (mode != 1) {
                    tXpVehicle.setAmpMusicScene(5);
                } else {
                    tXpVehicle.setAmpMusicScene(scene);
                }
                if (this.mHandler != null) {
                    this.mHandler.removeMessages(1);
                    Message m = this.mHandler.obtainMessage(1, 0, 0, 0);
                    this.mHandler.sendMessageDelayed(m, CHANGE_SOUNDEFFECT_DELAY);
                }
            } catch (Exception e) {
                handleException(e);
            }
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setSoundField(int mode, int xSound, int ySound) {
        int soundfieldmode;
        IXpVehicle tXpVehicle = getXpVehicle();
        Log.i(TAG, "setSoundField high:  mode:" + mode + " xSound:" + xSound + " ySound:" + ySound);
        if (tXpVehicle == null || xSound < 0 || xSound > 100 || ySound < 0 || ySound > 100) {
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
                tXpVehicle.setAmpMute(1);
                Thread.sleep(50L);
            }
            tXpVehicle.setAmpSoundFieldMode(soundfieldmode);
            if (this.mHandler != null) {
                this.mHandler.removeMessages(1);
                Message m = this.mHandler.obtainMessage(1, 0, 0, 0);
                this.mHandler.sendMessageDelayed(m, CHANGE_SOUNDEFFECT_DELAY);
            }
        } catch (Exception e) {
            handleException(e);
        }
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public void setMainDriverMode(int mode) {
        this.mMainDriverMode = mode;
    }

    @Override // com.xiaopeng.xpaudiopolicy.IXpAmpPolicy
    public int getMainDriverMode() {
        return this.mMainDriverMode;
    }

    /* loaded from: classes.dex */
    public class xpHighPolicyHandler extends Handler {
        public xpHighPolicyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            IXpVehicle tXpVehicle;
            Log.d(HighAmpPolicy.TAG, "handleMessage " + msg.what + " " + msg.arg1 + " " + msg.arg2);
            if (msg.what == 1 && (tXpVehicle = HighAmpPolicy.this.getXpVehicle()) != null) {
                try {
                    tXpVehicle.setAmpMute(0);
                } catch (RemoteException e) {
                    HighAmpPolicy.this.handleException(e);
                }
                Log.i(HighAmpPolicy.TAG, "setAmpMute(0)");
            }
        }
    }
}
