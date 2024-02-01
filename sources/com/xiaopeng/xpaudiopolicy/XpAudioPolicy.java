package com.xiaopeng.xpaudiopolicy;

import android.content.Context;
import android.media.AudioSystem;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import com.android.xpeng.audio.xpAudio;
import com.xiaopeng.util.FeatureOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* loaded from: classes2.dex */
public class XpAudioPolicy {
    public static final int ANALOG = 2;
    private static final String AREA_CONFIG_FILE = "/vendor/etc/amp_area_policy_config.xml";
    public static final int BTCALL_CALLING_OUT = 3;
    public static final int BTCALL_CONNECTTED = 2;
    public static final int BTCALL_NOT_CONNECTTED = 0;
    public static final int BTCALL_RINGING = 1;
    private static final String CARSPEECHSERVICE_PKG = "com.xiaopeng.carspeechservice";
    private static int DANGEROUS_TTS_VOLUME_HIGH = 0;
    private static int DANGEROUS_TTS_VOLUME_HIGH_AMP = 0;
    private static int DANGEROUS_TTS_VOLUME_LOW = 0;
    private static int DANGEROUS_TTS_VOLUME_LOW_AMP = 0;
    private static int DANGEROUS_TTS_VOLUME_NORMAL = 0;
    private static int DANGEROUS_TTS_VOLUME_NORMAL_AMP = 0;
    public static final int DEFAULT_VOLUME_HFP = 15;
    public static final int DEFAULT_VOLUME_MEDIA = 15;
    public static final int DEFAULT_VOLUME_NAVIGATION = 15;
    public static final int DEFAULT_VOLUME_SPEECH = 15;
    private static final int MESSAGE_APPLYUSAGE = 1;
    private static final int MESSAGE_CLEARFLAGS = 3;
    private static final int MESSAGE_RECHANGECHANNEL = 4;
    private static final int MESSAGE_RELEASEUSAGE = 2;
    public static final int MIX = 3;
    private static final String PROP_NAVIGATION_MUTED = "persist.audio.navi.muted";
    private static final String PROP_SYSTEM_NOTPLAY = "void.xppolicy.system.notplay";
    private static final String PROP_VOLUME_DANGERTTS = "persist.audio.volume.dangertts";
    private static final String PROP_VOLUME_HFP = "persist.audio.volume.hfp";
    private static final String PROP_VOLUME_MEDIA = "persist.audio.volume.media";
    public static final int SPDIF = 1;
    private static final int STREAM_ACCESSIBILITY_PRIORITY = 4;
    private static final int STREAM_BLUETOOTH_SCO_PRIORITY = 5;
    private static final int STREAM_RING_PRIORITY = 5;
    private static final int STREAM_SYSTEM_PRIORITY = 5;
    private static final int STREAM_TTS_PRIORITY = 3;
    private static final int SoundCallOnBit = 16;
    private static final int SoundChimeOnbit = 2;
    private static final int SoundMediaOnbit = 8;
    private static final int SoundNttsOnbit = 64;
    private static final int SoundSpeechOnbit = 32;
    private static final int SoundSttsOnbit = 4;
    private static final int SoundSysOnbit = 128;
    private static List<Integer> SystemTypeCount = null;
    private static final String TAG = "XpAudioPolicy";
    private static final int VOICE_POSITION_ALL = 5;
    private static final int VOICE_POSITION_CENTER = 0;
    private static final int VOICE_POSITION_INVALID = -1;
    private static final int VOICE_POSITION_REARRIGHT = 4;
    static boolean VoicePositionFading = false;
    public static final int XP_AMP_CHANNEL_1 = 1;
    public static final int XP_AMP_CHANNEL_10 = 512;
    public static final int XP_AMP_CHANNEL_11 = 1024;
    public static final int XP_AMP_CHANNEL_12 = 2048;
    public static final int XP_AMP_CHANNEL_2 = 2;
    public static final int XP_AMP_CHANNEL_3 = 4;
    public static final int XP_AMP_CHANNEL_4 = 8;
    public static final int XP_AMP_CHANNEL_5 = 16;
    public static final int XP_AMP_CHANNEL_6 = 32;
    public static final int XP_AMP_CHANNEL_7 = 64;
    public static final int XP_AMP_CHANNEL_8 = 128;
    public static final int XP_AMP_CHANNEL_9 = 256;
    public static final int XP_OUTPUT_COUNT = 4;
    public static final int XP_OUTPUT_DRIVERSIDE = 2;
    public static final int XP_OUTPUT_MEDIA = 0;
    public static final int XP_OUTPUT_PHONE = 1;
    public static final int XP_OUTPUT_SYS = 3;
    public static final int XP_SOUND_CALL = 4;
    public static final int XP_SOUND_CHIME = 1;
    public static final int XP_SOUND_COUNT = 7;
    public static final int XP_SOUND_MEDIA = 3;
    public static final int XP_SOUND_NTTS = 6;
    public static final int XP_SOUND_SPEECH = 5;
    public static final int XP_SOUND_STTS = 2;
    public static final int XP_SOUND_SYSTEM = 7;
    public static final int XP_VOICE_ALL_SCENE = 4;
    public static final int XP_VOICE_KEEP_LISTENING = 2;
    public static final int XP_VOICE_SLEEP = 0;
    public static final int XP_VOICE_WAKEUP = 1;
    public static final int XP_VOICE_WEAK_WAKEUP = 3;
    public static final int XP_VOLUME_COUNT = 5;
    public static final int XP_VOLUME_HFP = 4;
    public static final int XP_VOLUME_MEDIA = 0;
    public static final int XP_VOLUME_NAVIGATION = 2;
    public static final int XP_VOLUME_SPEECH = 1;
    public static final int XP_VOLUME_SYSTEM = 3;
    private static final String XUI_SERVICE_PACKAGE = "com.xiaopeng.xuiservice";
    private static boolean isCarSpeechOn;
    public static final boolean isNewMainDriverMode;
    private static long lastIgOntime;
    private static xpAudioEventListener mXpAudioEventListener;
    private static int mainDriverMode;
    private static Object ppidListObject;
    private ConcurrentHashMap<Integer, Integer> alarmBitsMap;
    private boolean isHighAmp;
    private int[] mApplyCounts;
    private ArrayList<Integer>[] mApplyList;
    private int[] mApplySoundCounts;
    private long[] mApplyTypeTimeTick;
    private Context mContext;
    Handler mHandler;
    private HandlerThread mHandlerThread;
    private boolean[] mPolicyChange;
    private IXpAmpPolicy mXpAmpPolicy;
    private boolean[] mXpMutes;
    private int[] mXpVolBanMode;
    private IXpVolumePolicy mXpVolumePolicy;
    private int[] mXpVolumes;
    private static boolean stereoAlarm = false;
    private static boolean speechSurround = false;
    private static boolean mainDriver = false;
    private static boolean btheadphone = false;
    private static boolean isInBtCall = false;
    private static int mBtCallFlag = 0;
    private static int mVoiceStatus = 0;
    private static final boolean system_follow_speech = SystemProperties.getBoolean("persist.audio.systemfollowspeech", true);
    private static final String PROP_VOLUME_SYSTEM = "persist.audio.volume.system";
    public static final int DEFAULT_VOLUME_SYSTEM_AMP = SystemProperties.getInt(PROP_VOLUME_SYSTEM, 15);
    private static final String PROP_VOLUME_SPEECH = "persist.audio.volume.speech";
    public static final int DEFAULT_VOLUME_SPEECH_AMP = SystemProperties.getInt(PROP_VOLUME_SPEECH, 10);
    private static final String PROP_VOLUME_NAVIGATION = "persist.audio.volume.navi";
    public static final int DEFAULT_VOLUME_NAVIGATION_AMP = SystemProperties.getInt(PROP_VOLUME_NAVIGATION, 10);
    public static final int DEFAULT_VOLUME_SYSTEM = FeatureOption.FO_AUDIO_SYSTEM_VOLUME;
    public static final int DEFAULT_VOLUME_KARAOKE = SystemProperties.getInt("persist.audio.volume.karaoke", 28);
    public static int DEFAULT_VOLUME_SYSTEM_CONFIGED = DEFAULT_VOLUME_SYSTEM;
    private static int XPAUDIO_DEBUG = SystemProperties.getInt("persist.xpaudio.debug.enable", 0);
    public static int DEFAULT_SOUNDFIELD_X = SystemProperties.getInt("persist.xpaudio.soundfield.x", 50);
    public static int DEFAULT_SOUNDFIELD_Y = SystemProperties.getInt("persist.xpaudio.soundfield.y", 50);
    private static final Map<Integer, Integer> streamtypeMap = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.1
        {
            put(0, 4);
            put(1, 7);
            put(2, 4);
            put(3, 3);
            put(4, 1);
            put(5, 7);
            put(6, 4);
            put(7, 7);
            put(8, 7);
            put(9, 6);
            put(10, 5);
        }
    };
    private static final Map<Integer, Integer> usageMap = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.2
        {
            put(1, 3);
            put(2, 4);
            put(3, 4);
            put(4, 1);
            put(5, 7);
            put(6, 4);
            put(7, 7);
            put(8, 7);
            put(9, 7);
            put(10, 7);
            put(11, 5);
            put(12, 6);
            put(13, 7);
            put(14, 3);
            put(15, 3);
            put(16, 5);
        }
    };
    private static final Map<Integer, Integer> soundMap = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.3
        {
            put(1, 2);
            put(2, 2);
            put(3, 1);
            put(4, 2);
            put(5, 2);
            put(6, 2);
            put(7, 2);
        }
    };
    private static final Map<Integer, Integer> volumeMap = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.4
        {
            put(0, 1);
            put(1, 3);
            put(2, 1);
            put(3, 0);
            put(4, 3);
            put(5, 3);
            put(6, 4);
            put(7, 3);
            put(8, 3);
            put(9, 2);
            put(10, 1);
        }
    };
    private static final Map<Integer, Integer> volumeMap2 = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.5
        {
            put(1, 0);
            put(2, 1);
            put(3, 1);
            put(4, 3);
            put(5, 3);
            put(6, 1);
            put(7, 3);
            put(8, 3);
            put(9, 3);
            put(10, 3);
            put(11, 1);
            put(12, 2);
            put(13, 3);
            put(14, 0);
            put(15, 0);
            put(16, 1);
        }
    };
    private static final Map<Integer, Integer> streamtypeToOutput = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.6
        {
            put(0, 1);
            put(1, 3);
            put(2, 1);
            put(3, 0);
            put(4, 3);
            put(5, 3);
            put(6, 1);
            put(7, 3);
            put(8, 1);
            put(9, 2);
            put(10, 1);
        }
    };
    private static XpAudioPolicy instance = new XpAudioPolicy();
    private static Object changeChannelObject = new Object();
    private static Object ApplyListObject = new Object();
    private int srcChannels = 0;
    private int dstChannels = 0;
    private xpAudio mxpAudio = null;
    private boolean isCallingOut = false;
    private ArrayList<String> voicePositionPkgList = new ArrayList<>();
    private final ArrayList<StreamPriority> streamPriority1 = new ArrayList<StreamPriority>() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.8
        {
            add(new StreamPriority(2, 5));
            add(new StreamPriority(6, 5));
            add(new StreamPriority(9, 3));
            add(new StreamPriority(10, 4));
        }
    };
    private boolean mPolicyChange_TTSbySpeech = false;
    private boolean SpeechTrackOn = false;
    private ArrayList<Integer> ppidList = new ArrayList<>();
    private boolean mOobeSurroundMode = false;
    int mLastActiveSoundBits = 0;
    private int SoundVolChangedBit = 0;

    /* loaded from: classes2.dex */
    public interface xpAudioEventListener {
        void onCommonEvent(int i, int i2);
    }

    static {
        boolean z = true;
        if (SystemProperties.getInt("persist.sys.xiaopeng.isnewmaindriver", 1) != 1) {
            z = false;
        }
        isNewMainDriverMode = z;
        mainDriverMode = SystemProperties.getInt("persist.sys.xiaopeng.maindriver.mode", 0);
        lastIgOntime = 0L;
        VoicePositionFading = false;
        isCarSpeechOn = false;
        ppidListObject = new Object();
        DANGEROUS_TTS_VOLUME_LOW = 14;
        DANGEROUS_TTS_VOLUME_NORMAL = 18;
        DANGEROUS_TTS_VOLUME_HIGH = 22;
        DANGEROUS_TTS_VOLUME_LOW_AMP = 14;
        DANGEROUS_TTS_VOLUME_NORMAL_AMP = 18;
        DANGEROUS_TTS_VOLUME_HIGH_AMP = 22;
    }

    public void setContext(Context context, xpAudio txpAudio) {
        this.mContext = context;
        this.mxpAudio = txpAudio;
        initAllSettings();
        initXpVolumes();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveVolume(int type, String prop, int index) {
        if (type == 0) {
            SystemProperties.set(prop, Integer.toString(index));
            return;
        }
        Context context = this.mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), prop, index);
        }
    }

    private int getSavedVolume(int type, String prop, int defindex) {
        if (type == 0) {
            return SystemProperties.getInt(prop, defindex);
        }
        Context context = this.mContext;
        if (context == null) {
            return 0;
        }
        return Settings.System.getInt(context.getContentResolver(), prop, defindex);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveMute(int type, String prop, int muted) {
        if (type == 0) {
            SystemProperties.set(prop, Integer.toString(muted));
            return;
        }
        Context context = this.mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), prop, muted);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getSavedMute(int type, String prop, int defindex) {
        if (type == 0) {
            return SystemProperties.getInt(prop, defindex);
        }
        Context context = this.mContext;
        if (context == null) {
            return 0;
        }
        return Settings.System.getInt(context.getContentResolver(), prop, defindex);
    }

    public boolean checkIsHighAmp() {
        return this.isHighAmp;
    }

    private XpAudioPolicy() {
        this.isHighAmp = false;
        this.mHandlerThread = null;
        String ampConfig = SystemProperties.get("persist.sys.xiaopeng.AMP", "0");
        if (ampConfig.equals("1")) {
            this.isHighAmp = true;
            this.mXpAmpPolicy = new HighAmpPolicy();
        } else {
            this.mXpAmpPolicy = new LowAmpPolicy();
        }
        this.mXpAmpPolicy.initAreaConfig(AmpAreaConfigParser.parseConfigXml(AREA_CONFIG_FILE));
        initXpMutes();
        this.mXpVolumePolicy = new IXpVolumePolicy() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.7
            @Override // com.xiaopeng.xpaudiopolicy.IXpVolumePolicy
            public void saveStreamVolume(int streamType, int index) {
                saveStreamVolume(streamType, index, true);
            }

            @Override // com.xiaopeng.xpaudiopolicy.IXpVolumePolicy
            public synchronized void saveStreamVolume(int streamType, int index, boolean savemute) {
                int volumeType = XpAudioPolicy.this.getVolumeTypeByStreamType(streamType);
                if (volumeType == -1) {
                    Log.e(XpAudioPolicy.TAG, "saveStreamVolume error: streamType=" + streamType);
                    return;
                }
                Log.i(XpAudioPolicy.TAG, "saveStreamVolume : streamType=" + streamType + ", index=" + index + ", volumeType= " + volumeType);
                XpAudioPolicy.this.mXpVolumes[volumeType] = index;
                if (index > 0 && savemute) {
                    saveStreamMute(streamType, false);
                }
                if (volumeType == 0) {
                    XpAudioPolicy.this.saveVolume(0, XpAudioPolicy.PROP_VOLUME_MEDIA, index);
                } else if (volumeType == 1) {
                    XpAudioPolicy.this.saveVolume(0, XpAudioPolicy.PROP_VOLUME_SPEECH, index);
                } else if (volumeType == 2) {
                    XpAudioPolicy.this.saveVolume(0, XpAudioPolicy.PROP_VOLUME_NAVIGATION, index);
                } else if (volumeType == 3) {
                    Log.d(XpAudioPolicy.TAG, "saveStreamVolume!!! XP_VOLUME_SYSTEM WILL BE CHANGE TO " + index);
                } else if (volumeType == 4) {
                    XpAudioPolicy.this.saveVolume(0, XpAudioPolicy.PROP_VOLUME_HFP, index);
                }
            }

            @Override // com.xiaopeng.xpaudiopolicy.IXpVolumePolicy
            public int getStreamVolume(int streamType) {
                int volumeType = XpAudioPolicy.this.getVolumeTypeByStreamType(streamType);
                if (volumeType != -1) {
                    return XpAudioPolicy.this.mXpVolumes[volumeType];
                }
                Log.e(XpAudioPolicy.TAG, "getStreamVolume error: streamType=" + streamType);
                return -1;
            }

            @Override // com.xiaopeng.xpaudiopolicy.IXpVolumePolicy
            public synchronized void saveStreamMute(int streamType, boolean mute) {
                int volumeType = XpAudioPolicy.this.getVolumeTypeByStreamType(streamType);
                if (volumeType == -1) {
                    Log.e(XpAudioPolicy.TAG, "saveStreamMute error: streamType=" + streamType);
                    return;
                }
                Log.i(XpAudioPolicy.TAG, "saveStreamMute : streamType=" + streamType + ", mute=" + mute);
                XpAudioPolicy.this.mXpMutes[volumeType] = mute;
                if (streamType == 9) {
                    XpAudioPolicy.this.saveMute(0, XpAudioPolicy.PROP_NAVIGATION_MUTED, mute ? 1 : 0);
                }
            }

            @Override // com.xiaopeng.xpaudiopolicy.IXpVolumePolicy
            public boolean getStreamMute(int streamType) {
                int volumeType = XpAudioPolicy.this.getVolumeTypeByStreamType(streamType);
                if (volumeType == -1) {
                    Log.e(XpAudioPolicy.TAG, "getStreamMute error: streamType=" + streamType);
                    return false;
                } else if (streamType == 9) {
                    int ttsMuted = XpAudioPolicy.this.getSavedMute(0, XpAudioPolicy.PROP_NAVIGATION_MUTED, 0);
                    if (ttsMuted != 1) {
                        return false;
                    }
                    return true;
                } else {
                    return XpAudioPolicy.this.mXpMutes[volumeType];
                }
            }
        };
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread("xpAudioPolicy");
        }
        this.mHandlerThread.start();
        this.mHandler = new xpAudioPolicyHandler(this.mHandlerThread.getLooper());
    }

    public static XpAudioPolicy getInstance() {
        return instance;
    }

    public boolean isLegalUsage(int usage) {
        if (usage > 0 && usage <= 16) {
            return true;
        }
        return false;
    }

    public boolean isLegalStreamType(int streamType) {
        if (streamType >= 0 && streamType <= 10) {
            return true;
        }
        return false;
    }

    public boolean isLegalSoundType(int soundType) {
        if (soundType > 0 && soundType <= 7) {
            return true;
        }
        return false;
    }

    public int getSoundTypeByAttrUsage(int usage) {
        if (!isLegalUsage(usage)) {
            return -1;
        }
        return usageMap.get(Integer.valueOf(usage)).intValue();
    }

    public int getSoundTypeByStreamType(int streamType) {
        if (!isLegalStreamType(streamType)) {
            return -1;
        }
        return streamtypeMap.get(Integer.valueOf(streamType)).intValue();
    }

    public int getVolumeTypeByAttrUsage(int usage) {
        if (!isLegalUsage(usage)) {
            return -1;
        }
        return volumeMap2.get(Integer.valueOf(usage)).intValue();
    }

    public int getVolumeTypeByStreamType(int streamType) {
        if (!isLegalStreamType(streamType)) {
            return -1;
        }
        return volumeMap.get(Integer.valueOf(streamType)).intValue();
    }

    public int getOutputByStreamType(int streamType) {
        if (!isLegalStreamType(streamType)) {
            return -1;
        }
        return streamtypeToOutput.get(Integer.valueOf(streamType)).intValue();
    }

    public int applyUsage(int usage, int id, String pkgName) {
        if (isLegalUsage(usage)) {
            synchronized (ApplyListObject) {
                int soundType = getSoundTypeByAttrUsage(usage);
                int volumeType = getVolumeTypeByAttrUsage(usage);
                if (volumeType >= 0 && volumeType < 5) {
                    if (this.mApplyList[volumeType] != null && this.mApplyList[volumeType].indexOf(Integer.valueOf(id)) == -1) {
                        this.mApplyList[volumeType].add(Integer.valueOf(id));
                        int[] iArr = this.mApplyCounts;
                        iArr[volumeType] = iArr[volumeType] + 1;
                    } else if (this.mApplyList[volumeType] != null && id == 0) {
                        if (this.mApplyList[volumeType].indexOf(Integer.valueOf(id)) == -1) {
                            this.mApplyList[volumeType].add(Integer.valueOf(id));
                        }
                        int[] iArr2 = this.mApplyCounts;
                        iArr2[volumeType] = iArr2[volumeType] + 1;
                    }
                }
                if (soundType >= 0 && soundType < 7) {
                    int[] iArr3 = this.mApplySoundCounts;
                    int i = soundType - 1;
                    iArr3[i] = iArr3[i] + 1;
                    this.mApplyTypeTimeTick[soundType - 1] = System.currentTimeMillis();
                }
                Log.i(TAG, "applyUsage: usage=" + usage + ", soundType=" + soundType + ", volumeType=" + volumeType + " mApplyCounts:" + this.mApplyCounts[volumeType] + " id:" + id + " pkgName:" + pkgName);
                dumpActiveStream();
                if (this.mHandler != null) {
                    Message m = this.mHandler.obtainMessage(1, usage, id, pkgName);
                    this.mHandler.sendMessage(m);
                }
            }
            return -1;
        }
        return -1;
    }

    public void releaseUsage(int usage, int id, String pkgName) {
        int[] iArr;
        int i;
        int[] iArr2;
        int[] iArr3;
        if (!isLegalUsage(usage)) {
            return;
        }
        synchronized (ApplyListObject) {
            int soundType = getSoundTypeByAttrUsage(usage);
            int volumeType = getVolumeTypeByAttrUsage(usage);
            Log.i(TAG, "releaseUsage: usage=" + usage + ", volumeType=" + volumeType + ", pkgName=" + pkgName);
            if (volumeType >= 0 && volumeType < 5) {
                if (this.mApplyList[volumeType] != null && this.mApplyList[volumeType].indexOf(Integer.valueOf(id)) != -1) {
                    int index = this.mApplyList[volumeType].indexOf(Integer.valueOf(id));
                    this.mApplyList[volumeType].remove(index);
                    this.mApplyCounts[volumeType] = iArr3[volumeType] - 1;
                    if (this.mApplyCounts[volumeType] < 0) {
                        this.mApplyCounts[volumeType] = 0;
                    }
                } else if (this.mApplyList[volumeType] != null && id == 0) {
                    if (this.mApplyList[volumeType].indexOf(Integer.valueOf(id)) != -1) {
                        int index2 = this.mApplyList[volumeType].indexOf(Integer.valueOf(id));
                        this.mApplyList[volumeType].remove(index2);
                    }
                    this.mApplyCounts[volumeType] = iArr2[volumeType] - 1;
                    if (this.mApplyCounts[volumeType] < 0) {
                        this.mApplyCounts[volumeType] = 0;
                    }
                }
            }
            if (soundType >= 0 && soundType < 7 && this.mApplySoundCounts[soundType - 1] > 0) {
                this.mApplySoundCounts[soundType - 1] = iArr[i] - 1;
            }
            long currenttime = System.currentTimeMillis();
            if (soundType == 6 && Math.abs(currenttime - this.mApplyTypeTimeTick[soundType - 1]) < 200) {
                pkgName = null;
            }
            dumpActiveStream();
            if (this.mHandler != null) {
                Message m = this.mHandler.obtainMessage(2, usage, id, pkgName);
                this.mHandler.sendMessage(m);
            }
            Log.d(TAG, "releaseUsage: usage=" + usage + " soundType=" + soundType + ", volumeType=" + volumeType + " mApplyCounts[volumeType]:" + this.mApplyCounts[volumeType]);
        }
    }

    public boolean isUsageActive(int usage) {
        int volumeType = getVolumeTypeByAttrUsage(usage);
        Log.d(TAG, "isUsageActive: usage=" + usage + ", volumeType=" + volumeType);
        if (volumeType < 0 || volumeType >= 5) {
            return false;
        }
        Log.d(TAG, "isUsageActive: usage=" + usage + ", ApplyCount=" + this.mApplyCounts[volumeType]);
        return this.mApplyCounts[volumeType] > 0;
    }

    public boolean checkStreamActiveFromOutput(int streamType) {
        int outPutType = getOutputByStreamType(streamType);
        for (int i = 0; i <= 10; i++) {
            int outPutType2 = streamtypeToOutput.get(Integer.valueOf(i)).intValue();
            if (outPutType == outPutType2 && AudioSystem.isStreamActive(i, 0)) {
                Log.d(TAG, "checkStreamActiveFromAudioSystem: streamType=" + streamType + ", checkType=" + i + " is active");
                return true;
            }
        }
        return false;
    }

    public boolean isStreamActive(int streamType) {
        if (isFmOn() && streamType == 3) {
            return true;
        }
        if (checkStreamActiveFromOutput(streamType)) {
            if (streamType == 3 && AudioSystem.isStreamActive(streamType, 0)) {
                return true;
            }
            int volumeType = getVolumeTypeByStreamType(streamType);
            return volumeType >= 0 && volumeType < 5 && this.mApplyCounts[volumeType] > 0;
        }
        return false;
    }

    public boolean isAnyStreamActive() {
        if (isFmOn()) {
            Log.d(TAG, "isAnyStreamActive() fm is on");
            return true;
        }
        xpAudio xpaudio = this.mxpAudio;
        if (xpaudio == null || !xpaudio.isKaraokeOn()) {
            for (int i = 0; i <= 10; i++) {
                if ((i == 0 || i == 3 || i == 6 || i == 9 || i == 10) && AudioSystem.isStreamActive(i, 0)) {
                    Log.d(TAG, "isAnyStreamActive() TYPE:" + i + " is active");
                    return true;
                }
            }
            dumpActiveStream();
            return false;
        }
        return true;
    }

    public boolean isFmOn() {
        return false;
    }

    /* loaded from: classes2.dex */
    private class StreamPriority {
        public int mPriority;
        public int mStreamtype;

        public StreamPriority(int streamtype, int priority) {
            this.mStreamtype = streamtype;
            this.mPriority = priority;
        }

        void setStreamtype(int streamtype) {
            this.mStreamtype = streamtype;
        }

        void setPriority(int priority) {
            this.mPriority = priority;
        }

        int getSreamtype() {
            return this.mStreamtype;
        }

        int getPriority() {
            return this.mPriority;
        }
    }

    void dumpApplySound() {
        if (XPAUDIO_DEBUG == 1) {
            for (int i = 0; i < 7; i++) {
                Log.d(TAG, "xp_sound_" + i + "=" + this.mApplySoundCounts[i]);
            }
        }
    }

    void dumpActiveStream() {
        if (XPAUDIO_DEBUG == 1) {
            for (int i = 0; i <= 10; i++) {
                if (AudioSystem.isStreamActive(i, 0)) {
                    Log.d(TAG, "dumpActiveStream() TYPE:" + i + " is active");
                }
            }
        }
    }

    public int checkStreamCanPlay(int streamType) {
        int ret = 2;
        getSoundTypeByStreamType(streamType);
        if (streamType != 1) {
            if (streamType != 3) {
                if (streamType != 7) {
                    if (streamType != 9) {
                        if (streamType == 10 && isBtCallOn()) {
                            ret = 0;
                        }
                    } else if (!isBtCallOn()) {
                        if (mainDriverMode == 1) {
                            ret = 1;
                        }
                    } else {
                        ret = 0;
                    }
                }
            } else if (getVoiceStatus() != 0 || isBtCallOn()) {
                ret = 0;
            }
        } else if (this.mApplySoundCounts[0] > 0) {
            dumpApplySound();
            ret = 0;
        }
        Log.i(TAG, "checkStreamCanPlay streamType:" + streamType + "  return=" + ret);
        return ret;
    }

    public int applyStreamType(int streamType) {
        IXpAmpPolicy iXpAmpPolicy;
        int soundType = getSoundTypeByStreamType(streamType);
        Log.d(TAG, "applyStreamType: streamType=" + streamType + ", soundType=" + soundType);
        if (isLegalSoundType(soundType) && (iXpAmpPolicy = this.mXpAmpPolicy) != null) {
            this.mLastActiveSoundBits = 0;
            iXpAmpPolicy.setAmpChanVolSource(getChannels(soundType), 100, getInterface(soundType), getChannels(soundType));
            return 0;
        }
        return -1;
    }

    public void setStereoAlarm(boolean enable) {
        Log.i(TAG, "setStereoAlarm: on=" + enable);
        stereoAlarm = enable;
        Context context = this.mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpStereoAlarmOn", enable ? 1 : 0);
        }
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setStereoAlarm(enable);
        }
    }

    public void setSpeechSurround(boolean enable) {
        Log.i(TAG, "setSpeechSurround: on=" + enable);
        speechSurround = enable;
        Context context = this.mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpSpeechSurroundOn", enable ? 1 : 0);
        }
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setSpeechSurround(enable);
        }
    }

    public void setMainDriver(boolean enable) {
        Log.e(TAG, "setMainDriver: on=" + enable);
    }

    public void setMainDriverMode(int mode) {
        Log.i(TAG, "setMainDriverMode: mode=" + mode);
        mainDriverMode = mode;
        Context context = this.mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpMainDriverMode", mode);
        }
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setMainDriverMode(mode);
        }
        int activeSoundBits = getActiveSoundBits(0, false);
        this.mLastActiveSoundBits = 0;
        changeChannel(activeSoundBits);
        this.mOobeSurroundMode = false;
        VoicePositionFading = false;
    }

    public int getMainDriverMode() {
        Context context = this.mContext;
        if (context != null) {
            mainDriverMode = Settings.System.getInt(context.getContentResolver(), "XpMainDriverMode", 0);
        }
        Log.d(TAG, "getMainDriverMode: mode=" + mainDriverMode);
        return mainDriverMode;
    }

    public void setBtHeadPhone(boolean enable) {
        Log.i(TAG, "setBtHeadPhone: on=" + enable);
        btheadphone = enable;
        Context context = this.mContext;
        if (context != null) {
            Settings.System.putInt(context.getContentResolver(), "XpBtHeadPhoneOn", enable ? 1 : 0);
        }
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setBtHeadPhone(enable);
        }
        int activeSoundBits = getActiveSoundBits(0, false);
        this.mLastActiveSoundBits = 0;
        changeChannel(activeSoundBits);
    }

    public void setSoundEffectMode(int mode) {
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setSoundEffectMode(mode);
        }
    }

    public void setSoundEffectType(int mode, int type) {
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setSoundEffectType(mode, type);
        }
    }

    public void setSoundEffectScene(int mode, int scene) {
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setSoundEffectScene(mode, scene);
        }
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setSoundField(mode, xSound, ySound);
        }
    }

    public boolean isStereoAlarmOn() {
        Context context = this.mContext;
        if (context != null) {
            stereoAlarm = Settings.System.getInt(context.getContentResolver(), "XpStereoAlarmOn", 0) == 1;
        }
        Log.d(TAG, "isStereoAlarmOn: on=" + stereoAlarm);
        return stereoAlarm;
    }

    public boolean isSpeechSurroundOn() {
        Context context = this.mContext;
        if (context != null) {
            speechSurround = Settings.System.getInt(context.getContentResolver(), "XpSpeechSurroundOn", 0) == 1;
        }
        Log.d(TAG, "isSpeechSurroundOn: on=" + speechSurround);
        return speechSurround;
    }

    public boolean isMainDriverOn() {
        return false;
    }

    public boolean isBtHeadPhoneOn() {
        Context context = this.mContext;
        if (context != null) {
            btheadphone = Settings.System.getInt(context.getContentResolver(), "XpBtHeadPhoneOn", 0) == 1;
        }
        Log.d(TAG, "isBtHeadPhoneOn: on=" + btheadphone);
        return btheadphone;
    }

    public IXpVolumePolicy getXpVolumePolicy() {
        return this.mXpVolumePolicy;
    }

    public void clearActiveSoundBits() {
        this.mLastActiveSoundBits = 0;
    }

    public void igOnResetFlags() {
        Log.i(TAG, "igOnResetFlags() ");
        clearActiveSoundBits();
        setBtCallOn(false);
        mVoiceStatus = 0;
        SystemTypeCount.clear();
        int ret = AudioSystem.setParameters("IG_ON=true");
        Handler handler = this.mHandler;
        if (handler != null) {
            Message m = handler.obtainMessage(3, 0, 0, 0);
            this.mHandler.sendMessageDelayed(m, 5000L);
        }
        this.mOobeSurroundMode = false;
        ArrayList<String> arrayList = this.voicePositionPkgList;
        if (arrayList != null) {
            arrayList.clear();
        }
        VoicePositionFading = false;
        lastIgOntime = System.currentTimeMillis();
        if (ret == -1) {
            Log.e(TAG, "IG_ON ERROR");
        }
        for (int i = 0; i < 5; i++) {
            this.mXpVolBanMode[i] = 0;
        }
        setCallingOut(false);
        setCallingIn(false);
        isCarSpeechOn = false;
        this.SpeechTrackOn = false;
        setDangerousTtsOn(false);
        setKaraokeOn(false);
        synchronized (ppidListObject) {
            this.ppidList.clear();
        }
        SystemProperties.set(PROP_SYSTEM_NOTPLAY, "false");
        this.alarmBitsMap.clear();
    }

    private int getActiveAlarmBits(int soundid) {
        if (this.alarmBitsMap.containsKey(Integer.valueOf(soundid))) {
            return this.alarmBitsMap.get(Integer.valueOf(soundid)).intValue();
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void putAlarmBits(int soundid, int alarmbits) {
        Log.d(TAG, "putAlarmBits :" + soundid + "  " + alarmbits);
        this.alarmBitsMap.put(Integer.valueOf(soundid), Integer.valueOf(alarmbits));
    }

    private boolean removeAlarmBits(int soundid) {
        Log.d(TAG, "removeAlarmBits :" + soundid);
        if (getActiveAlarmBits(soundid) == -1) {
            return false;
        }
        this.alarmBitsMap.remove(Integer.valueOf(soundid));
        return true;
    }

    public int getAlarmIdSize() {
        return this.alarmBitsMap.size();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getMixedAlarmBits() {
        int mixedAlarmBits = 0;
        for (Integer num : this.alarmBitsMap.keySet()) {
            int key = num.intValue();
            mixedAlarmBits |= this.alarmBitsMap.get(Integer.valueOf(key)).intValue();
        }
        Log.d(TAG, "getMixedAlarmBits size:" + this.alarmBitsMap.size() + " bits:" + mixedAlarmBits);
        return mixedAlarmBits;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean releaseAlarmBits(int usage, int id) {
        if (usage != 4) {
            return false;
        }
        boolean ret = removeAlarmBits(id);
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setAlarmChannels(getMixedAlarmBits());
        }
        Log.d(TAG, "releaseAlarmBits id:" + id + " ret:" + ret);
        return ret;
    }

    /* JADX WARN: Type inference failed for: r0v6, types: [com.xiaopeng.xpaudiopolicy.XpAudioPolicy$9] */
    public synchronized int selectAlarmChannels(int location, final int fadeTimeMs, final int soundid) {
        Log.i(TAG, "selectAlarmChannels location=" + location + ", fadeTimeMs=" + fadeTimeMs);
        if (!stereoAlarm) {
            Log.e(TAG, "stereoAlarm mode is off");
        }
        if (this.mXpAmpPolicy == null) {
            Log.e(TAG, "amp policy has not inited !");
            return -1;
        }
        if (!mainDriver && getMainDriverMode() == 0) {
            if (location == 0) {
                this.dstChannels = 1;
            } else if (location == 1) {
                this.dstChannels = 2;
            } else if (location == 2) {
                this.srcChannels = 1;
                this.dstChannels = 2;
            } else if (location == 3) {
                this.dstChannels = 1;
                this.srcChannels = 2;
            } else if (location == 4) {
                this.dstChannels = 1;
            } else if (location == 5) {
                this.dstChannels = 64;
            }
            new Thread() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.9
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    try {
                        if (XpAudioPolicy.this.mXpAmpPolicy != null) {
                            int activeSoundBits = XpAudioPolicy.this.getActiveSoundBits(4, true);
                            int changeSoundBits = XpAudioPolicy.this.trigerVolumePolicy(4, activeSoundBits);
                            XpAudioPolicy.this.XpChangeVolume(changeSoundBits);
                            XpAudioPolicy.this.trigerOtherPolicy(4);
                        }
                        int activeSoundBits2 = fadeTimeMs;
                        if (activeSoundBits2 <= 0) {
                            XpAudioPolicy.this.putAlarmBits(soundid, XpAudioPolicy.this.dstChannels);
                            XpAudioPolicy.this.openKaraokeModeSysVolume(4, false);
                            XpAudioPolicy.this.mXpAmpPolicy.setAlarmChannels(XpAudioPolicy.this.getMixedAlarmBits());
                            int activeSoundBits3 = XpAudioPolicy.this.getActiveSoundBits(4, true);
                            XpAudioPolicy.this.changeChannel(activeSoundBits3);
                            XpAudioPolicy.this.mLastActiveSoundBits = 0;
                            return;
                        }
                        int sleepTime = fadeTimeMs / 10;
                        int stepVolume = 100 / 10;
                        for (int i = 1; i <= 10; i++) {
                            XpAudioPolicy.this.mXpAmpPolicy.setAmpChanVolSource(XpAudioPolicy.this.srcChannels, 100 - (stepVolume * i), XpAudioPolicy.this.getInterface(1), XpAudioPolicy.this.srcChannels | XpAudioPolicy.this.dstChannels);
                            XpAudioPolicy.this.mXpAmpPolicy.setAmpChanVolSource(XpAudioPolicy.this.dstChannels, stepVolume * i, XpAudioPolicy.this.getInterface(1), XpAudioPolicy.this.srcChannels | XpAudioPolicy.this.dstChannels);
                            Thread.sleep(sleepTime);
                        }
                        XpAudioPolicy.this.mLastActiveSoundBits = 0;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }.start();
            return 0;
        }
        if (location == 2) {
            this.srcChannels = 1024;
            this.dstChannels = 2048;
        } else if (location == 3) {
            this.dstChannels = 1024;
            this.srcChannels = 2048;
        } else {
            this.dstChannels = 3072;
        }
        new Thread() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.9
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                try {
                    if (XpAudioPolicy.this.mXpAmpPolicy != null) {
                        int activeSoundBits = XpAudioPolicy.this.getActiveSoundBits(4, true);
                        int changeSoundBits = XpAudioPolicy.this.trigerVolumePolicy(4, activeSoundBits);
                        XpAudioPolicy.this.XpChangeVolume(changeSoundBits);
                        XpAudioPolicy.this.trigerOtherPolicy(4);
                    }
                    int activeSoundBits2 = fadeTimeMs;
                    if (activeSoundBits2 <= 0) {
                        XpAudioPolicy.this.putAlarmBits(soundid, XpAudioPolicy.this.dstChannels);
                        XpAudioPolicy.this.openKaraokeModeSysVolume(4, false);
                        XpAudioPolicy.this.mXpAmpPolicy.setAlarmChannels(XpAudioPolicy.this.getMixedAlarmBits());
                        int activeSoundBits3 = XpAudioPolicy.this.getActiveSoundBits(4, true);
                        XpAudioPolicy.this.changeChannel(activeSoundBits3);
                        XpAudioPolicy.this.mLastActiveSoundBits = 0;
                        return;
                    }
                    int sleepTime = fadeTimeMs / 10;
                    int stepVolume = 100 / 10;
                    for (int i = 1; i <= 10; i++) {
                        XpAudioPolicy.this.mXpAmpPolicy.setAmpChanVolSource(XpAudioPolicy.this.srcChannels, 100 - (stepVolume * i), XpAudioPolicy.this.getInterface(1), XpAudioPolicy.this.srcChannels | XpAudioPolicy.this.dstChannels);
                        XpAudioPolicy.this.mXpAmpPolicy.setAmpChanVolSource(XpAudioPolicy.this.dstChannels, stepVolume * i, XpAudioPolicy.this.getInterface(1), XpAudioPolicy.this.srcChannels | XpAudioPolicy.this.dstChannels);
                        Thread.sleep(sleepTime);
                    }
                    XpAudioPolicy.this.mLastActiveSoundBits = 0;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();
        return 0;
    }

    public void checkAlarmVolume() {
        openKaraokeModeSysVolume(4, false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getInterface(int soundType) {
        if (!isLegalSoundType(soundType)) {
            return -1;
        }
        return soundMap.get(Integer.valueOf(soundType)).intValue();
    }

    private int getChannels(int soundType) {
        if (this.mXpAmpPolicy != null && isLegalSoundType(soundType)) {
            return this.mXpAmpPolicy.getChannels(soundType);
        }
        return 0;
    }

    private void initXpVolumes() {
        Log.i(TAG, "initXpVolumes");
        this.mXpVolumes = new int[5];
        this.mApplyCounts = new int[5];
        this.mApplySoundCounts = new int[7];
        this.mPolicyChange = new boolean[7];
        this.mApplyList = new ArrayList[5];
        this.mApplyTypeTimeTick = new long[7];
        this.mXpVolBanMode = new int[5];
        this.mXpVolumes[0] = getSavedVolume(0, PROP_VOLUME_MEDIA, 15);
        this.mXpVolumes[1] = getSavedVolume(0, PROP_VOLUME_SPEECH, 15);
        this.mXpVolumes[2] = getSavedVolume(0, PROP_VOLUME_NAVIGATION, 15);
        if (this.isHighAmp) {
            this.mXpVolumes[3] = getSavedVolume(0, PROP_VOLUME_SYSTEM, DEFAULT_VOLUME_SYSTEM_AMP);
            this.mXpVolumes[1] = getSavedVolume(0, PROP_VOLUME_SPEECH, DEFAULT_VOLUME_SPEECH_AMP);
            this.mXpVolumes[2] = getSavedVolume(0, PROP_VOLUME_NAVIGATION, DEFAULT_VOLUME_NAVIGATION_AMP);
            DANGEROUS_TTS_VOLUME_LOW = DANGEROUS_TTS_VOLUME_LOW_AMP;
            DANGEROUS_TTS_VOLUME_NORMAL = DANGEROUS_TTS_VOLUME_NORMAL_AMP;
            DANGEROUS_TTS_VOLUME_HIGH = DANGEROUS_TTS_VOLUME_HIGH_AMP;
        } else {
            this.mXpVolumes[3] = getSavedVolume(0, PROP_VOLUME_SYSTEM, DEFAULT_VOLUME_SYSTEM);
        }
        this.mXpVolumes[4] = getSavedVolume(0, PROP_VOLUME_HFP, 15);
        DEFAULT_VOLUME_SYSTEM_CONFIGED = this.mXpVolumes[3];
        Log.i(TAG, "initXpVolumes  ampConfig:" + this.isHighAmp + " XP_VOLUME_SYSTEM:" + this.mXpVolumes[3] + " " + getSavedVolume(0, PROP_VOLUME_SYSTEM, DEFAULT_VOLUME_SYSTEM_AMP));
        for (int i = 0; i < 5; i++) {
            this.mApplyCounts[i] = 0;
            this.mApplyList[i] = new ArrayList<>();
        }
        for (int i2 = 0; i2 < 7; i2++) {
            this.mApplySoundCounts[i2] = 0;
            this.mApplyTypeTimeTick[i2] = 0;
        }
        for (int i3 = 0; i3 < 7; i3++) {
            this.mPolicyChange[i3] = false;
        }
        this.alarmBitsMap = new ConcurrentHashMap<>();
        SystemTypeCount = new ArrayList();
    }

    public boolean getPolicyChangeStatus(int streamType) {
        int soundType = getSoundTypeByStreamType(streamType);
        if (soundType == -1) {
            return false;
        }
        if (soundType == 6 && this.mPolicyChange_TTSbySpeech) {
            return true;
        }
        return this.mPolicyChange[soundType - 1];
    }

    private void initXpMutes() {
        this.mXpMutes = new boolean[5];
        boolean[] zArr = this.mXpMutes;
        zArr[0] = false;
        zArr[1] = false;
        zArr[2] = false;
        zArr[3] = false;
        zArr[4] = false;
    }

    /* JADX WARN: Type inference failed for: r0v1, types: [com.xiaopeng.xpaudiopolicy.XpAudioPolicy$10] */
    private void initAllSettings() {
        Log.i(TAG, "initAllSettings()");
        new Thread() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.10
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                try {
                    if (XpAudioPolicy.this.mXpAmpPolicy != null) {
                        XpAudioPolicy.this.mXpAmpPolicy.setSpeechSurround(XpAudioPolicy.this.isSpeechSurroundOn());
                        if (!XpAudioPolicy.isNewMainDriverMode) {
                            XpAudioPolicy.this.mXpAmpPolicy.setMainDriver(XpAudioPolicy.this.isMainDriverOn());
                        } else {
                            XpAudioPolicy.this.mXpAmpPolicy.setMainDriverMode(XpAudioPolicy.this.getMainDriverMode());
                        }
                        XpAudioPolicy.this.mXpAmpPolicy.setStereoAlarm(XpAudioPolicy.this.isStereoAlarmOn());
                        XpAudioPolicy.this.mXpAmpPolicy.setBtHeadPhone(XpAudioPolicy.this.isBtHeadPhoneOn());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    public void setBtCallOn(boolean enable) {
        isInBtCall = enable;
        Log.i(TAG, "setBtCallOn " + enable + " " + this.mxpAudio.getBtCallMode());
        if (this.mXpAmpPolicy != null) {
            if (enable && this.mxpAudio.getBtCallMode() == 1) {
                this.mXpAmpPolicy.setBtCallOn(true);
            } else {
                this.mXpAmpPolicy.setBtCallOn(false);
            }
        }
    }

    public void setBtCallOnFlag(int flag) {
        mBtCallFlag = flag;
        Log.i(TAG, "setBtCallOn " + flag + " " + this.mxpAudio.getBtCallMode());
        if (this.mXpAmpPolicy != null) {
            if (mBtCallFlag == 2 && this.mxpAudio.getBtCallMode() == 1) {
                this.mXpAmpPolicy.setBtCallOnFlag(mBtCallFlag);
            } else {
                int i = mBtCallFlag;
                if (i == 1) {
                    this.mXpAmpPolicy.setBtCallOnFlag(1);
                } else if (i == 3) {
                    this.mXpAmpPolicy.setBtCallOnFlag(3);
                } else {
                    this.mXpAmpPolicy.setBtCallOnFlag(0);
                }
            }
        }
        if (mBtCallFlag == 0) {
            int activeSoundBits = getActiveSoundBits(2, false);
            changeChannel(activeSoundBits);
            return;
        }
        int activeSoundBits2 = getActiveSoundBits(2, true);
        changeChannel(activeSoundBits2);
    }

    public boolean isBtCallOn() {
        return mBtCallFlag == 2;
    }

    public void setBtCallMode(int mode) {
        Log.i(TAG, "setBtCallMode " + mode + " " + mBtCallFlag);
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            int i = mBtCallFlag;
            if (i == 2 && mode == 1) {
                iXpAmpPolicy.setBtCallOnFlag(i);
                return;
            }
            int i2 = mBtCallFlag;
            if (i2 == 1) {
                this.mXpAmpPolicy.setBtCallOnFlag(1);
            } else if (i2 == 3) {
                this.mXpAmpPolicy.setBtCallOnFlag(3);
            } else {
                this.mXpAmpPolicy.setBtCallOnFlag(0);
            }
        }
    }

    public void setVoiceStatus(int status) {
        Log.i(TAG, "setVoiceStatus " + status);
        mVoiceStatus = status;
        if (status <= 0 && !isBtCallOn()) {
            int activeSoundBits = getActiveSoundBits(11, false);
            changeChannel(activeSoundBits);
        }
    }

    public int getVoiceStatus() {
        return mVoiceStatus;
    }

    private boolean checkPositionNeedSet(int position, String pkgName) {
        ArrayList<String> arrayList = this.voicePositionPkgList;
        if (arrayList == null) {
            Log.w(TAG, "checkPositionNeedSet voicePositionPkgList==null !!!");
            return true;
        } else if (position == -1 && !arrayList.contains(pkgName)) {
            return false;
        } else {
            if (position == 0 && !this.voicePositionPkgList.contains(pkgName)) {
                this.voicePositionPkgList.add(pkgName);
            } else if (position == -1 && this.voicePositionPkgList.contains(pkgName)) {
                this.voicePositionPkgList.remove(pkgName);
            }
            return true;
        }
    }

    /* JADX WARN: Type inference failed for: r1v10, types: [com.xiaopeng.xpaudiopolicy.XpAudioPolicy$11] */
    public void setVoicePosition(final int position, final int flag, String pkgName) {
        Log.d(TAG, "setVoicePosition " + position + " flag:" + flag + " pkgName:" + pkgName);
        if (!checkPositionNeedSet(position, pkgName)) {
            Log.w(TAG, "setVoicePosition position do not need set!! " + pkgName);
            return;
        }
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null && this.isHighAmp) {
            final int currentPosition = iXpAmpPolicy.getVoicePosition();
            Log.i(TAG, "setVoicePosition " + position + " flag:" + flag + " currentPosition:" + currentPosition + " " + pkgName);
            if (isCarSpeechOn && !CARSPEECHSERVICE_PKG.equals(pkgName)) {
                Log.w(TAG, "setVoicePosition isCarSpeechOn!!");
                return;
            }
            if (CARSPEECHSERVICE_PKG.equals(pkgName)) {
                isCarSpeechOn = position != -1;
            }
            if (currentPosition == position || position < -1 || position > 5) {
                return;
            }
            if (currentPosition == -1 || position == -1 || position == 0) {
                this.mXpAmpPolicy.setVoicePosition(position, flag);
                this.mLastActiveSoundBits = 0;
                int activeSoundBits = getActiveSoundBits(11, position != -1);
                changeChannel(activeSoundBits);
                return;
            }
            new Thread() { // from class: com.xiaopeng.xpaudiopolicy.XpAudioPolicy.11
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    int i = 1;
                    try {
                        XpAudioPolicy.VoicePositionFading = true;
                        int srcBit = XpAudioPolicy.this.mXpAmpPolicy.getChannels(5);
                        int destBit = 1 << (position - 1);
                        Log.i(XpAudioPolicy.TAG, "setVoicePosition  fading srcBit:" + srcBit + " destBit:" + destBit);
                        int i2 = 1;
                        while (i2 <= 10) {
                            int i3 = 3;
                            int mediabits = XpAudioPolicy.this.mXpAmpPolicy.getChannels(3);
                            int mixChannelBits = mediabits & (srcBit | destBit);
                            int spdifChannelBits = mediabits & (~mixChannelBits);
                            int activeChannelBits = mediabits | srcBit | destBit;
                            int i4 = (srcBit & mixChannelBits) != 0 ? i : 0;
                            int i5 = (destBit & mixChannelBits) != 0 ? i : 0;
                            if (spdifChannelBits != 0) {
                                XpAudioPolicy.this.mXpAmpPolicy.setAmpChanVolSource(spdifChannelBits, 100, i, activeChannelBits);
                            }
                            if (currentPosition != -1) {
                                IXpAmpPolicy iXpAmpPolicy2 = XpAudioPolicy.this.mXpAmpPolicy;
                                int i6 = 100 - (i2 * 10);
                                if (i4 == 0) {
                                    i3 = 2;
                                }
                                iXpAmpPolicy2.setAmpChanVolSource(srcBit, i6, i3, activeChannelBits);
                            }
                            if (position != -1) {
                                XpAudioPolicy.this.mXpAmpPolicy.setAmpChanVolSource(destBit, i2 * 10, i5 != 0 ? 3 : 2, activeChannelBits);
                            }
                            Thread.sleep(100L);
                            i2++;
                            i = 1;
                        }
                        XpAudioPolicy.this.mXpAmpPolicy.setVoicePosition(position, flag);
                        XpAudioPolicy.this.mLastActiveSoundBits = 0;
                    } catch (Exception e) {
                    }
                    XpAudioPolicy.VoicePositionFading = false;
                }
            }.start();
        }
    }

    public void setCallingOut(boolean enable) {
        Log.i(TAG, "setCallingOut " + enable);
        this.isCallingOut = enable;
        this.mLastActiveSoundBits = 0;
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setCallingOut(enable);
        }
    }

    public void setCallingIn(boolean enable) {
        Log.i(TAG, "setCallingIn " + enable);
        this.isCallingOut = enable;
        this.mLastActiveSoundBits = 0;
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setCallingIn(enable);
        }
    }

    public int getVoicePosition() {
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            return iXpAmpPolicy.getVoicePosition();
        }
        return 0;
    }

    private void addOrRemoveSystemType(int id, boolean add) {
        if (add) {
            for (int i = 0; i < SystemTypeCount.size(); i++) {
                if (SystemTypeCount.get(i).intValue() == id) {
                    return;
                }
            }
            SystemTypeCount.add(Integer.valueOf(id));
            return;
        }
        for (int i2 = SystemTypeCount.size() - 1; i2 >= 0; i2--) {
            if (SystemTypeCount.get(i2).intValue() == id) {
                SystemTypeCount.remove(i2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean setSystemTypeOn(boolean enable, int usage, int id, String pkgName) {
        if (usage != 13) {
            return false;
        }
        if (!enable && SystemTypeCount.size() > 0 && pkgName != null && !pkgName.equals(XUI_SERVICE_PACKAGE)) {
            addOrRemoveSystemType(id, false);
            Log.i(TAG, "setSystemTypeOn enable:" + enable + " count:" + SystemTypeCount.size() + " id:" + id);
            return true;
        } else if (!enable || pkgName == null || pkgName.equals(XUI_SERVICE_PACKAGE)) {
            return false;
        } else {
            addOrRemoveSystemType(id, true);
            Log.i(TAG, "setSystemTypeOn enable:" + enable + " count:" + SystemTypeCount.size() + " id:" + id);
            return true;
        }
    }

    public int getSystemTypeCount() {
        return SystemTypeCount.size();
    }

    public void openKaraokeModeSysVolume(int usage, boolean close) {
        xpAudio xpaudio = this.mxpAudio;
        if (xpaudio == null) {
            return;
        }
        if (!xpaudio.isKaraokeOn() && !close && (usage == 13 || usage == 4)) {
            this.mxpAudio.openKaraokeModeSysVolume(false);
        } else if (usage == 4) {
            Log.i(TAG, "openKaraokeModeSysVolume usage:" + usage + " close:" + close + " alarmIdSize:" + getAlarmIdSize());
            if (this.mxpAudio.isKaraokeOn() && !close) {
                this.mxpAudio.openKaraokeModeSysVolume(false);
            } else if (this.mxpAudio.isKaraokeOn() && close && getAlarmIdSize() == 0 && getSystemTypeCount() == 0) {
                this.mxpAudio.openKaraokeModeSysVolume(true);
            }
        } else if (usage == 13) {
            Log.i(TAG, "openKaraokeModeSysVolume usage:" + usage + " close:" + close + "  systemSoundCounts:" + getSystemTypeCount());
            if (!close && getSystemTypeCount() > 0 && this.mxpAudio.isKaraokeOn()) {
                this.mxpAudio.openKaraokeModeSysVolume(false);
            } else if (close && getSystemTypeCount() == 0) {
                if (this.mxpAudio.isKaraokeOn() & (getAlarmIdSize() == 0)) {
                    this.mxpAudio.openKaraokeModeSysVolume(true);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getActiveSoundBits(int usage, boolean in) {
        int soundType;
        if (XPAUDIO_DEBUG == 1) {
            Log.d(TAG, "getActiveSoundBits:" + usage + " " + in);
        }
        int activeSoundBits = 0;
        int usageSoundType = getSoundTypeByAttrUsage(usage);
        for (int i = 0; i <= 10; i++) {
            if (AudioSystem.isStreamActive(i, 0)) {
                if (XPAUDIO_DEBUG == 1) {
                    Log.d(TAG, "getActiveSoundBits() TYPE:" + i + " is active");
                }
                int soundType2 = getSoundTypeByStreamType(i);
                if (soundType2 != -1) {
                    activeSoundBits |= 1 << soundType2;
                }
            }
        }
        int i2 = mBtCallFlag;
        if (i2 != 0) {
            activeSoundBits |= 16;
        }
        if (in) {
            if (usageSoundType != -1) {
                activeSoundBits |= 1 << usageSoundType;
            }
            if (usage == 11) {
                int soundType3 = getSoundTypeByAttrUsage(1);
                if (soundType3 != -1) {
                    activeSoundBits &= ~(1 << soundType3);
                }
            } else if (usage == 12 && this.isHighAmp && (soundType = getSoundTypeByAttrUsage(11)) != -1) {
                activeSoundBits &= ~(1 << soundType);
            }
        } else if ((usage == 12 || usage == 11) && usageSoundType != -1) {
            activeSoundBits &= ~(1 << usageSoundType);
        }
        int activeSoundBits2 = activeSoundBits | 8;
        if (((activeSoundBits2 & 64) == 0 || !in) && (activeSoundBits2 & 32) == 0 && (activeSoundBits2 & 16) == 0) {
            activeSoundBits2 |= 128;
        }
        if ((system_follow_speech || (!in && (usageSoundType == 7 || usageSoundType == 1))) && (activeSoundBits2 & 32) != 0) {
            activeSoundBits2 = activeSoundBits2 & (-129) & (-3);
        }
        if ((activeSoundBits2 & 64) != 0) {
            activeSoundBits2 = activeSoundBits2 & (-3) & (-129);
        }
        xpAudio xpaudio = this.mxpAudio;
        if (xpaudio != null && xpaudio.getDangerousTtsStatus() != 0) {
            activeSoundBits2 = 72;
        } else if (this.SpeechTrackOn && mBtCallFlag == 0) {
            activeSoundBits2 = 40;
        }
        Log.d(TAG, "getActiveSoundBits() activeSoundBits:" + activeSoundBits2);
        return activeSoundBits2;
    }

    private int getActiveChannelBits(int activeSoundBits) {
        int activeChannelBits = 0;
        for (int i = 1; i <= 7; i++) {
            if (((1 << i) & activeSoundBits) != 0) {
                activeChannelBits |= getChannels(i);
                if (XPAUDIO_DEBUG == 1) {
                    Log.d(TAG, "getActiveChannelBits activeSoundBits:" + activeSoundBits + " SoundType i:" + i + " activeChannelBits:" + activeChannelBits);
                }
            }
        }
        if (this.mxpAudio.isKaraokeOn()) {
            return activeChannelBits | 1023;
        }
        return activeChannelBits;
    }

    private int getSpdifChannelBits(int activeSoundBits) {
        int spdifChannelBits = 0;
        for (int i = 1; i <= 7; i++) {
            if (((1 << i) & activeSoundBits) != 0) {
                int soundinterface = getInterface(i);
                if (soundinterface == 1) {
                    spdifChannelBits |= getChannels(i);
                }
                if (XPAUDIO_DEBUG == 1) {
                    Log.d(TAG, "getSpdifChannelBits activeSoundBits:" + activeSoundBits + " SoundType i:" + i + " interface:" + soundinterface + " spdifChannelBits:" + spdifChannelBits);
                }
            }
        }
        return spdifChannelBits;
    }

    private int getAnalogChannelBits(int activeSoundBits) {
        int analogChannelBits = 0;
        for (int i = 1; i <= 7; i++) {
            if (((1 << i) & activeSoundBits) != 0) {
                int soundinterface = getInterface(i);
                if (soundinterface == 2) {
                    analogChannelBits |= getChannels(i);
                }
                if (XPAUDIO_DEBUG == 1) {
                    Log.d(TAG, "getAnalogChannelBits activeSoundBits:" + activeSoundBits + " SoundType i:" + i + " interface:" + soundinterface + " analogChannelBits:" + analogChannelBits);
                }
            }
        }
        if (this.mxpAudio.isKaraokeOn() && mainDriverMode != 2) {
            return 1023;
        }
        return analogChannelBits;
    }

    private int getMixChannelBits(int activeSoundBits) {
        int mixChannelBits = 0;
        for (int i = 1; i <= 7; i++) {
            if (((1 << i) & activeSoundBits) != 0) {
                int soundinterface = getInterface(i);
                if (soundinterface == 3) {
                    mixChannelBits |= getChannels(i);
                }
                Log.d(TAG, "getMixChannelBits activeSoundBits:" + activeSoundBits + " SoundType i:" + i + " interface:" + soundinterface + " mixChannelBits:" + mixChannelBits);
            }
        }
        return mixChannelBits;
    }

    private boolean checkPpidExist(int ppid) {
        for (int i = 0; i < this.ppidList.size(); i++) {
            if (this.ppidList.get(i).intValue() == ppid) {
                return true;
            }
        }
        return false;
    }

    private boolean checkPpidEmpty() {
        return this.ppidList.size() <= 0;
    }

    private void addPpid(int ppid) {
        synchronized (ppidListObject) {
            if (!checkPpidExist(ppid)) {
                this.ppidList.add(Integer.valueOf(ppid));
            }
        }
    }

    private void removePpid(int ppid) {
        synchronized (ppidListObject) {
            if (checkPpidExist(ppid)) {
                this.ppidList.remove(Integer.valueOf(ppid));
            }
        }
    }

    private void dumpPpid() {
        for (int i = 0; i < this.ppidList.size(); i++) {
            Log.d(TAG, "dumpPpid: i" + i + " ppid:" + this.ppidList.get(i));
        }
    }

    public void ChangeChannelByTrack(int usage, int ppid, boolean start) {
        int activeSoundBits;
        if (!this.isHighAmp) {
            return;
        }
        Log.i(TAG, "ChangeChannelByTrack " + usage + " " + ppid + " " + start + " ppidList:" + this.ppidList.size());
        int soundType = getSoundTypeByAttrUsage(usage);
        if (soundType == 5) {
            if (start) {
                addPpid(ppid);
            } else {
                removePpid(ppid);
            }
            if (checkPpidEmpty()) {
                this.SpeechTrackOn = false;
            } else {
                this.SpeechTrackOn = true;
            }
            if (XPAUDIO_DEBUG == 1) {
                dumpPpid();
            }
            Log.d(TAG, "SpeechTrackOn:" + this.SpeechTrackOn);
            int activeSoundBits2 = 0 | 8;
            if (start) {
                activeSoundBits = activeSoundBits2 | 32;
            } else {
                activeSoundBits = activeSoundBits2 | 128;
            }
            changeChannel(activeSoundBits);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void changeChannel(int activeSoundBits) {
        if (this.isHighAmp) {
            if (this.mOobeSurroundMode) {
                Log.w(TAG, "changeChannel in mOobeSurroundMode!!");
            } else if (VoicePositionFading) {
                Log.w(TAG, "changeChannel in VoicePositionFading!!");
            } else {
                long currenttime = System.currentTimeMillis();
                if (this.mLastActiveSoundBits == activeSoundBits && (activeSoundBits & 32) == 0 && Math.abs(currenttime - lastIgOntime) > 5000) {
                    Log.d(TAG, "changeChannel  activeBits is same as last, do not change!!" + this.mLastActiveSoundBits + " " + activeSoundBits + " currenttime:" + currenttime + " lastIgOntime:" + lastIgOntime);
                    return;
                }
                this.mLastActiveSoundBits = activeSoundBits;
                int activeChannelBits = getActiveChannelBits(activeSoundBits);
                int spdifBits = getSpdifChannelBits(activeSoundBits);
                int analogBits = getAnalogChannelBits(activeSoundBits);
                int mixChannelBits = getMixChannelBits(activeSoundBits);
                int spdifChannelBits = (~analogBits) & spdifBits;
                int analogChannelBits = (~spdifBits) & analogBits;
                int mixChannelBits2 = mixChannelBits | (spdifBits & analogBits);
                if (XPAUDIO_DEBUG == 1) {
                    Log.i(TAG, "changeChannel activeSoundBits:" + activeSoundBits + " spdifChannelBits:" + spdifChannelBits + " analogChannelBits:" + analogChannelBits + " mixChannelBits:" + mixChannelBits2 + " activeChannelBits:" + activeChannelBits);
                }
                if (this.mXpAmpPolicy != null && activeChannelBits != 0) {
                    if (spdifChannelBits != 0) {
                        this.mXpAmpPolicy.setAmpChanVolSource(spdifChannelBits, 100, 1, activeChannelBits);
                    }
                    if (analogChannelBits != 0) {
                        this.mXpAmpPolicy.setAmpChanVolSource(analogChannelBits, 100, 2, activeChannelBits);
                    }
                    if (mixChannelBits2 != 0) {
                        this.mXpAmpPolicy.setAmpChanVolSource(mixChannelBits2, 100, 3, activeChannelBits);
                    }
                } else if (this.mXpAmpPolicy != null) {
                    this.mXpAmpPolicy.setAmpChanVolSource(getChannels(6), 100, getInterface(6), getChannels(6));
                }
            }
        }
    }

    public void forceChangeToAmpChannel(int selectBits, int activeBits, int volume, boolean stop) {
        Log.i(TAG, "forceChangeToAmpChannel  selectBits:" + selectBits + " activeBits:" + activeBits + " volume:" + volume + " stop:" + stop + " ampConfig:" + this.isHighAmp);
        if (this.isHighAmp) {
            if (stop) {
                this.mOobeSurroundMode = false;
                this.mLastActiveSoundBits = 0;
                return;
            }
            this.mOobeSurroundMode = true;
            IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
            if (iXpAmpPolicy != null) {
                iXpAmpPolicy.setAmpChanVolSource(selectBits, volume, 3, activeBits);
            }
        } else if (stop) {
            AudioSystem.setParameters("DSP FR CTL=UNMUTE FL");
            AudioSystem.setParameters("DSP FR CTL=UNMUTE FR");
            AudioSystem.setParameters("DSP FR CTL=UNMUTE RL");
            AudioSystem.setParameters("DSP FR CTL=UNMUTE RR");
        } else {
            if ((selectBits & 1) == 1 && volume != 0) {
                AudioSystem.setParameters("DSP FR CTL=UNMUTE FL");
            } else {
                AudioSystem.setParameters("DSP FR CTL=MUTE FL");
            }
            if ((selectBits & 2) == 2 && volume != 0) {
                AudioSystem.setParameters("DSP FR CTL=UNMUTE FR");
            } else {
                AudioSystem.setParameters("DSP FR CTL=MUTE FR");
            }
            if ((selectBits & 4) == 4 && volume != 0) {
                AudioSystem.setParameters("DSP FR CTL=UNMUTE RL");
            } else {
                AudioSystem.setParameters("DSP FR CTL=MUTE RL");
            }
            if ((selectBits & 8) == 8 && volume != 0) {
                AudioSystem.setParameters("DSP FR CTL=UNMUTE RR");
            } else {
                AudioSystem.setParameters("DSP FR CTL=MUTE RR");
            }
        }
    }

    public void setKaraokeOn(boolean on) {
        this.mLastActiveSoundBits = 0;
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setKaraokeOn(on);
        }
    }

    public void setDangerousTtsOn(boolean on) {
        IXpAmpPolicy iXpAmpPolicy = this.mXpAmpPolicy;
        if (iXpAmpPolicy != null) {
            iXpAmpPolicy.setDangerousTtsOn(on);
            int activeSoundBits = getActiveSoundBits(0, false);
            changeChannel(activeSoundBits);
        }
    }

    public void setBanVolumeChangeMode(int streamType, int mode, String pkgName) {
        Log.i(TAG, "setBanVolumeChangeMode  " + streamType + " " + mode + " " + pkgName);
        int volumeType = getVolumeTypeByStreamType(streamType);
        if (volumeType == -1) {
            Log.e(TAG, "setBanVolumeChangeMode  volumeType out of range");
        } else {
            this.mXpVolBanMode[volumeType] = mode;
        }
    }

    public int getBanVolumeChangeMode(int streamType) {
        int volumeType = getVolumeTypeByStreamType(streamType);
        if (volumeType == -1) {
            Log.e(TAG, "setBanVolumeChangeMode  volumeType out of range");
            return 0;
        }
        return this.mXpVolBanMode[volumeType];
    }

    public void setDangerousTtsVolLevel(int level) {
        int dangerttsStatus;
        Log.i(TAG, "setDangerousTtsVolLevel: level=" + level);
        try {
            if (this.mContext != null) {
                Settings.System.putInt(this.mContext.getContentResolver(), "DangerousTtsVolLevel", level);
            }
        } catch (Exception e) {
            Log.e(TAG, "setDangerousTtsVolLevel " + e);
        }
        if (level == 1) {
            SystemProperties.set(PROP_VOLUME_DANGERTTS, Integer.toString(DANGEROUS_TTS_VOLUME_LOW));
        } else if (level == 2) {
            SystemProperties.set(PROP_VOLUME_DANGERTTS, Integer.toString(DANGEROUS_TTS_VOLUME_NORMAL));
        } else if (level == 3) {
            SystemProperties.set(PROP_VOLUME_DANGERTTS, Integer.toString(DANGEROUS_TTS_VOLUME_HIGH));
        }
        xpAudio xpaudio = this.mxpAudio;
        if (xpaudio != null && (dangerttsStatus = xpaudio.getDangerousTtsStatus()) != 0) {
            this.mxpAudio.setDangerousTtsStatus(dangerttsStatus);
        }
    }

    public int getDangerousTtsVolLevel() {
        Context context = this.mContext;
        if (context == null) {
            return 2;
        }
        int ttsVolLevel = Settings.System.getInt(context.getContentResolver(), "DangerousTtsVolLevel", 2);
        return ttsVolLevel;
    }

    public int getDangerousTtsVolume() {
        return SystemProperties.getInt(PROP_VOLUME_DANGERTTS, DANGEROUS_TTS_VOLUME_HIGH);
    }

    public static int toXpStreamType(int usage) {
        Log.d(TAG, "toXpStreamType  usageType:" + usage);
        switch (usage) {
            case 0:
                return 3;
            case 1:
            case 14:
                return 3;
            case 2:
                return 0;
            case 3:
                return 0;
            case 4:
                return 4;
            case 5:
            case 7:
            case 8:
            case 9:
                return 5;
            case 6:
                return 2;
            case 10:
                return 7;
            case 11:
                return 10;
            case 12:
                return 9;
            case 13:
                return 1;
            case 15:
            default:
                return 3;
            case 16:
                return 10;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void XpChangeVolume(int soundVolChangeBit) {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void XpResetVolume(int soundVolChangeBit) {
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int trigerVolumePolicy(int usage, int activeSoundBits) {
        int soundTypeByAttrUsage = getSoundTypeByAttrUsage(usage);
        if (soundTypeByAttrUsage == 4) {
            Log.i(TAG, "trigerVolumePolicy XP_SOUND_CALL usage:" + usage + " activeSoundBits:" + activeSoundBits);
            int tempSoundVolChangeBit = 0 | 64;
            return tempSoundVolChangeBit;
        } else if (soundTypeByAttrUsage != 5) {
            return 0;
        } else {
            Log.i(TAG, "trigerVolumePolicy XP_SOUND_SPEECH usage:" + usage + " activeSoundBits:" + activeSoundBits);
            int tempSoundVolChangeBit2 = 0 | 32;
            return tempSoundVolChangeBit2;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void trigerOtherPolicy(int usage) {
        if (usage == 4) {
            Log.i(TAG, "trigerOtherPolicy  usage:" + usage);
            SystemProperties.set(PROP_SYSTEM_NOTPLAY, "true");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void releaseOtherPolicy(int usage) {
        if (usage == 4) {
            Log.i(TAG, "releaseOtherPolicy  usage:" + usage);
            SystemProperties.set(PROP_SYSTEM_NOTPLAY, "false");
        }
    }

    /* loaded from: classes2.dex */
    public class xpAudioPolicyHandler extends Handler {
        public xpAudioPolicyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            Log.d(XpAudioPolicy.TAG, "handleMessage " + msg.what + " " + msg.arg1 + " " + msg.arg2);
            int i = msg.what;
            if (i == 1) {
                int usage = msg.arg1;
                XpAudioPolicy.this.setSystemTypeOn(true, usage, msg.arg2, (String) msg.obj);
                XpAudioPolicy.this.openKaraokeModeSysVolume(usage, false);
                if (XpAudioPolicy.this.mXpAmpPolicy != null) {
                    int activeSoundBits = XpAudioPolicy.this.getActiveSoundBits(usage, true);
                    int changeSoundBits = XpAudioPolicy.this.trigerVolumePolicy(usage, activeSoundBits);
                    XpAudioPolicy.this.XpChangeVolume(changeSoundBits);
                    int soundType = XpAudioPolicy.this.getSoundTypeByAttrUsage(usage);
                    if (soundType != 5) {
                        XpAudioPolicy.this.changeChannel(activeSoundBits);
                    }
                    XpAudioPolicy.this.trigerOtherPolicy(usage);
                }
            } else if (i != 2) {
                if (i == 3) {
                    XpAudioPolicy.this.clearActiveSoundBits();
                } else if (i == 4) {
                    XpAudioPolicy.this.changeChannel(XpAudioPolicy.this.getActiveSoundBits(1, true));
                }
            } else {
                int usage2 = msg.arg1;
                int id = msg.arg2;
                String pkgName = (String) msg.obj;
                if (XpAudioPolicy.this.releaseAlarmBits(usage2, id) || XpAudioPolicy.this.setSystemTypeOn(false, usage2, id, pkgName)) {
                    XpAudioPolicy.this.openKaraokeModeSysVolume(usage2, true);
                }
                int activeSoundBits2 = XpAudioPolicy.this.getActiveSoundBits(usage2, false);
                int soundType2 = XpAudioPolicy.this.getSoundTypeByAttrUsage(usage2);
                if (pkgName != null && soundType2 != 5) {
                    XpAudioPolicy.this.changeChannel(activeSoundBits2);
                }
                int changeSoundBits2 = XpAudioPolicy.this.trigerVolumePolicy(usage2, activeSoundBits2);
                XpAudioPolicy.this.XpResetVolume(changeSoundBits2);
                XpAudioPolicy.this.releaseOtherPolicy(usage2);
            }
        }
    }

    public void registerAudioListener(xpAudioEventListener listener) {
        mXpAudioEventListener = listener;
    }
}
