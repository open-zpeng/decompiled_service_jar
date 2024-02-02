package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import java.util.ArrayList;
/* loaded from: classes.dex */
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
    private static channelData mChannelData;
    private static Context mContext;
    private static xuiChannelPolicy mInstance;
    private static xuiAudioData mXuiAudioData;
    private static IXuiChannelInterface mXuiChannelHal;
    private static xuiExternalAudioPath mXuiExternalAudioPath;
    private Handler mHandler;
    private static int mAudioFeature = 0;
    private static boolean passengerBtExist = FeatureOption.FO_PASSENGER_BT_AUDIO_EXIST;
    private final int STREAM_DANGEROUS = 100;
    private final int STREAM_KARAOKE = 101;
    private boolean btheadphone = false;
    private int mainDriverMode = 0;
    private HandlerThread mHandlerThread = null;
    private ArrayList<String> voicePositionPkgList = new ArrayList<>();

    private xuiChannelPolicy(Context context, xuiAudioPolicy instance) {
        mContext = context;
        mXuiChannelHal = xuiChannelToAmp.getInstance(this, context);
        initXuiChannelPolicy(context, instance);
        mXuiAudioData = xuiAudioData.getInstance(context);
        if (passengerBtExist) {
            mXuiExternalAudioPath = xuiExternalAudioPath.getInstance(context);
        }
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
        if (this.voicePositionPkgList == null) {
            Log.w(TAG, "checkPositionNeedSet voicePositionPkgList==null !!!");
            return true;
        } else if (position == -1 && !this.voicePositionPkgList.contains(pkgName)) {
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

    public void igStatusChange(int flag) {
        if (mXuiChannelHal != null) {
            mXuiChannelHal.igStatusChange(flag);
        }
        if (this.voicePositionPkgList != null) {
            this.voicePositionPkgList.clear();
        }
    }

    public void setBtHeadPhone(boolean enable) {
        Log.i(TAG, "setBtHeadPhone: on=" + enable);
        this.btheadphone = enable;
        if (mXuiAudioData != null) {
            mXuiAudioData.setBtHeadPhone(enable);
        }
        if (mXuiChannelHal != null) {
            mXuiChannelHal.setBtHeadPhone(enable);
        }
    }

    public boolean isBtHeadPhoneOn() {
        if (mXuiAudioData != null) {
            return mXuiAudioData.isBtHeadPhoneOn();
        }
        return false;
    }

    public void setMainDriverMode(int mode) {
        this.mainDriverMode = mode;
        if (mXuiAudioData != null) {
            mXuiAudioData.setMainDriverMode(mode);
        }
        if (mXuiChannelHal != null) {
            mXuiChannelHal.setMainDriverMode(mode);
        }
    }

    public int getMainDriverMode() {
        if (mXuiAudioData != null) {
            return mXuiAudioData.getMainDriverMode();
        }
        return 0;
    }

    public void setStereoAlarm(boolean enable) {
        if (mXuiAudioData != null) {
            mXuiAudioData.setStereoAlarm(enable);
        }
    }

    public boolean isStereoAlarmOn() {
        if (mXuiAudioData != null) {
            return mXuiAudioData.isStereoAlarmOn();
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
        } else if (mXuiChannelHal != null) {
            mXuiChannelHal.setVoicePosition(position);
        }
    }

    public int getVoicePosition() {
        if (mXuiChannelHal != null) {
            return mXuiChannelHal.getVoicePosition();
        }
        return 0;
    }

    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        if (mXuiChannelHal != null) {
            mXuiChannelHal.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
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
        if (mXuiChannelHal != null) {
            mXuiChannelHal.triggerChannelChange(streamType, id, playFlag);
        }
    }

    public void setBtCallOnFlag(int flag) {
        if (mXuiChannelHal != null) {
            mXuiChannelHal.setBtCallOnFlag(flag);
        }
    }

    public void setPassengerPkgList(ArrayList<Integer> uidList) {
        Log.d(TAG, "setPassengerPkgList");
        if (mXuiExternalAudioPath != null) {
            mXuiExternalAudioPath.setPassengerPkgList(uidList);
        }
    }

    public void setAvasStreamEnable(int busType, boolean enable) {
        Log.d(TAG, "setAvasStreamEnable  " + busType + " " + enable);
        if (mXuiExternalAudioPath != null) {
            mXuiExternalAudioPath.setAvasStreamEnable(busType, enable);
        }
    }

    private void handleException(String func, Exception e) {
        Log.e(TAG, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + func + " " + e);
    }
}
