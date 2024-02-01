package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import java.util.ArrayList;

/* loaded from: classes2.dex */
public class xuiChannelPolicy {
    private static final String ANDROIDSYS_PKGNAME = "android";
    private static final String AUDIO_SYSTEM_PACKAGENAME = "android.audio.System";
    private static final String BTCALL_VOLCHANGE_PACKAGE = "xpaudio_btcall";
    public static final int MAIN_DRIVER_MODE_DRIVER = 1;
    public static final int MAIN_DRIVER_MODE_NO_ACTIVE = 0;
    public static final int MAIN_DRIVER_MODE_SILENCE = 2;
    public static final String MODE_DANGER = "dangerVolLevel";
    public static final String MODE_DRIVING = "driveringMode";
    public static final String MODE_NORMAL = "normal";
    public static final int PLAYING_FLAG_PLAY = 1;
    public static final int PLAYING_FLAG_STOP = 0;
    private static final String SETTINGS_PACKAGE = "com.xiaopeng.car.settings";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String TAG = "xuiChannelPolicy";
    private static final String XP_VOLUME_DANGEROUS = "XP_VOLUME_DANGEROUS";
    private static final String XP_VOLUME_HFP = "XP_VOLUME_HFP";
    private static final String XP_VOLUME_KARAOKE = "XP_VOLUME_KARAOKE";
    private static final String XP_VOLUME_MEDIA = "XP_VOLUME_MEDIA";
    private static final String XP_VOLUME_NAVIGATION = "XP_VOLUME_NAVIGATION";
    private static final String XP_VOLUME_SPEECH = "XP_VOLUME_SPEECH";
    private static final String XP_VOLUME_SYSTEM = "XP_VOLUME_SYSTEM";
    private static final String XUI_SERVICE_PACKAGE = "com.xiaopeng.xuiservice";
    private static final String XUI_SERVICE_PACKAGE2 = "com.xiaopeng.xuiservice2";
    private static int mAudioFeature = 0;
    private static channelData mChannelData;
    private static Context mContext;
    private static xuiChannelPolicy mInstance;
    private static xuiAudioData mXuiAudioData;
    private static IXuiChannelInterface mXuiChannelHal;
    private static boolean passengerBtExist;
    private static boolean pioneerAmpOpen;
    private Handler mHandler;
    private final int STREAM_DANGEROUS = 100;
    private final int STREAM_KARAOKE = 101;
    private boolean btheadphone = false;
    private int mainDriverMode = 0;
    private HandlerThread mHandlerThread = null;
    private ArrayList<String> voicePositionPkgList = new ArrayList<>();

    static {
        passengerBtExist = FeatureOption.FO_PASSENGER_BT_AUDIO_EXIST > 0;
        pioneerAmpOpen = FeatureOption.FO_AMP_TYPE == 1;
    }

    private xuiChannelPolicy(Context context, xuiAudioPolicy instance) {
        mContext = context;
        if (FeatureOption.hasFeature("AMP") && pioneerAmpOpen) {
            mXuiChannelHal = xuiChannelToPioneerAmp.getInstance(this, context);
        }
        initXuiChannelPolicy(context, instance);
        mXuiAudioData = xuiAudioData.getInstance(context);
        setBtHeadPhone(isBtHeadPhoneOn());
        setMainDriverMode(getMainDriverMode());
    }

    public static xuiChannelPolicy getInstance(Context context, xuiAudioPolicy instance) {
        if (mInstance == null) {
            mInstance = new xuiChannelPolicy(context, instance);
        }
        return mInstance;
    }

    private void initXuiChannelPolicy(Context context, xuiAudioPolicy instance) {
        if (mChannelData == null) {
            mChannelData = xuiAudioParser.parseChannelData();
        }
    }

    private boolean checkPositionNeedSet(int position, String pkgName) {
        ArrayList<String> arrayList = this.voicePositionPkgList;
        if (arrayList == null) {
            Log.w(TAG, "checkPositionNeedSet voicePositionPkgList==null !!!");
            return true;
        } else if (position == -1 && !arrayList.contains(pkgName)) {
            return false;
        } else {
            if (position != -1 && !this.voicePositionPkgList.contains(pkgName)) {
                this.voicePositionPkgList.add(pkgName);
            } else if (position == -1 && this.voicePositionPkgList.contains(pkgName)) {
                this.voicePositionPkgList.remove(pkgName);
            }
            return true;
        }
    }

    public void igStatusChange(int flag) {
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.igStatusChange(flag);
        }
        ArrayList<String> arrayList = this.voicePositionPkgList;
        if (arrayList != null) {
            arrayList.clear();
        }
    }

    public void setBtHeadPhone(boolean enable) {
        Log.i(TAG, "setBtHeadPhone: on=" + enable);
        this.btheadphone = enable;
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setBtHeadPhone(enable);
        }
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.setBtHeadPhone(enable);
        }
    }

    public boolean isBtHeadPhoneOn() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.isBtHeadPhoneOn();
        }
        return false;
    }

    public void setMainDriverMode(int mode) {
        this.mainDriverMode = mode;
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setMainDriverMode(mode);
        }
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.setMainDriverMode(mode);
        }
    }

    public int getMainDriverMode() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getMainDriverMode();
        }
        return 0;
    }

    public void setStereoAlarm(boolean enable) {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setStereoAlarm(enable);
        }
    }

    public boolean isStereoAlarmOn() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.isStereoAlarmOn();
        }
        return false;
    }

    public int selectAlarmChannels(int location, int fadeTimeMs, int soundid) {
        return 0;
    }

    public void setVoicePosition(int position, int flag, String pkgName) {
        Log.d(TAG, "setVoicePosition " + position + " " + flag + " " + pkgName);
        if (!checkPositionNeedSet(position, pkgName)) {
            Log.w(TAG, "setVoicePosition position do not need set!! " + pkgName);
            return;
        }
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.setVoicePosition(position);
        }
    }

    public void setStreamPosition(int streamType, String pkgName, int position, int id) {
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.setStreamPosition(streamType, pkgName, position, id);
        }
    }

    public void setSoundPositionEnable(boolean enable) {
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.setSoundPositionEnable(enable);
        }
    }

    public boolean getSoundPositionEnable() {
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            return iXuiChannelInterface.getSoundPositionEnable();
        }
        return false;
    }

    public int getVoicePosition() {
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            return iXuiChannelInterface.getVoicePosition();
        }
        return 0;
    }

    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
        }
    }

    public void ChangeChannelByTrack(int usage, int id, boolean start) {
        try {
            Log.d(TAG, "ChangeChannelByTrack " + usage);
            int streamtype = xuiAudioPolicy.audioAtrributeMap.get(Integer.valueOf(usage)).intValue();
            if (mXuiChannelHal != null && streamtype >= 0) {
                mXuiChannelHal.triggerChannelChange(streamtype, id, start ? 1 : 0);
            }
        } catch (Exception e) {
            handleException("ChangeChannelByTrack", e);
        }
    }

    public void triggerChannelChange(int streamType, int id, int playFlag) {
        Log.d(TAG, "triggerChannelChange " + streamType + " " + id + " " + playFlag);
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.triggerChannelChange(streamType, id, playFlag);
        }
    }

    public void setBtCallOnFlag(int flag) {
        IXuiChannelInterface iXuiChannelInterface = mXuiChannelHal;
        if (iXuiChannelInterface != null) {
            iXuiChannelInterface.setBtCallOnFlag(flag);
        }
    }

    private void handleException(String func, Exception e) {
        Log.e(TAG, "" + func + " " + e);
    }
}
