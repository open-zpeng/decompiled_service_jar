package com.xiaopeng.xui.xuiaudio;

import android.car.hardware.XpVehicle.IXpVehicle;
import android.content.Context;
import android.media.SoundEffectParms;
import android.media.SoundField;
import android.util.Log;
import com.xiaopeng.audio.xpAudioSessionInfo;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiChannelPolicy;
import com.xiaopeng.xui.xuiaudio.xuiAudioEffect.xuiEffectPolicy;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.xuiVolumePolicy;
import java.util.List;
/* loaded from: classes.dex */
public class xuiAudioPolicyTrigger {
    private static final String TAG = "xuiAudioPolicyTrigger";
    private Context mContext;
    private xuiAudioPolicy mXuiAudioPolicy;
    private xuiChannelPolicy mXuiChannelPolicy;
    private xuiEffectPolicy mXuiEffectPolicy;
    private xuiVolumePolicy mXuiVolumePolicy;

    public xuiAudioPolicyTrigger(Context context) {
        this.mContext = context;
        this.mXuiAudioPolicy = new xuiAudioPolicy(context);
        this.mXuiVolumePolicy = xuiVolumePolicy.getInstance(context, this.mXuiAudioPolicy);
        this.mXuiChannelPolicy = xuiChannelPolicy.getInstance(context, this.mXuiAudioPolicy);
        this.mXuiEffectPolicy = xuiEffectPolicy.getInstance(context, this.mXuiAudioPolicy);
    }

    public IXpVehicle getXpVehicle() {
        return null;
    }

    public boolean checkStreamActive(int streamType) {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.checkStreamActive(streamType);
        }
        return false;
    }

    public boolean isAnyStreamActive() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.isAnyStreamActive();
        }
        return false;
    }

    public int checkStreamCanPlay(int streamType) {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.checkStreamCanPlay(streamType);
        }
        return 2;
    }

    public void startAudioCapture(int audioSession, int usage) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.startAudioCapture(audioSession, usage);
        }
    }

    public void stopAudioCapture(int audioSession, int usage) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.stopAudioCapture(audioSession, usage);
        }
    }

    public int applyUsage(int usage, int id) {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.applyUsage(usage, id);
        }
        return -1;
    }

    public int releaseUsage(int usage, int id) {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.releaseUsage(usage, id);
        }
        return -1;
    }

    public boolean isUsageActive(int usage) {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.isUsageActive(usage);
        }
        return false;
    }

    public void setRingtoneSessionId(int streamType, int sessionId, String pkgName) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.setRingtoneSessionId(streamType, sessionId, pkgName);
        }
    }

    public void setBtCallOn(boolean enable) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.setBtCallOn(enable);
        }
    }

    public void setBtCallOnFlag(int flag) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.setBtCallOnFlag(flag);
        }
    }

    public int getBtCallOnFlag() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.getBtCallOnFlag();
        }
        return 0;
    }

    public boolean isBtCallOn() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.isBtCallOn();
        }
        return false;
    }

    public void setBtCallMode(int mode) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.setBtCallMode(mode);
        }
    }

    public int getBtCallMode() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.getBtCallMode();
        }
        return 0;
    }

    public void setKaraokeOn(boolean on) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.setKaraokeOn(on);
        }
    }

    public boolean isKaraokeOn() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.isKaraokeOn();
        }
        return false;
    }

    public boolean isOtherSessionOn() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.isOtherSessionOn();
        }
        return false;
    }

    public List<String> getOtherMusicPlayingPkgs() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.getOtherMusicPlayingPkgs();
        }
        return null;
    }

    public boolean isFmOn() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.isFmOn();
        }
        return false;
    }

    public void playbackControl(int cmd, int param) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.playbackControl(cmd, param);
        }
    }

    public void setDangerousTtsStatus(int on) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.setDangerousTtsStatus(on);
        }
    }

    public int getDangerousTtsStatus() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.getDangerousTtsStatus();
        }
        return 0;
    }

    public void setMainDriverMode(int mode) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.setMainDriverMode(mode);
        }
    }

    public int getMainDriverMode() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.getMainDriverMode();
        }
        return 0;
    }

    public void applyAlarmId(int usage, int id) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.applyAlarmId(usage, id);
        }
    }

    public void startSpeechEffect(int audioSession) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.startSpeechEffect(audioSession);
        }
    }

    public void stopSpeechEffect(int audioSession) {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.stopSpeechEffect(audioSession);
        }
    }

    public List<xpAudioSessionInfo> getActiveSessionList() {
        if (this.mXuiAudioPolicy != null) {
            return this.mXuiAudioPolicy.getActiveSessionList();
        }
        return null;
    }

    public void adjustStreamVolume(int streamType, int direction, int flags, String packageName) {
        printDebugLog("adjustStreamVolume " + streamType + " " + direction + " " + flags + " " + packageName);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.adjustStreamVolume(streamType, direction, flags, packageName);
        }
        if (flags == 268599296 && this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.igStatusChange(1);
        }
    }

    public void setStreamVolume(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setVolWhenPlay(streamType);
        }
    }

    public void setStreamVolume(int streamType, int index, int flags, String packageName) {
        printDebugLog("setStreamVolume " + streamType + " " + index + " " + flags + " " + packageName);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setStreamVolume(streamType, index, flags, packageName);
        }
    }

    public boolean getStreamMute(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getStreamMute(streamType);
        }
        return false;
    }

    public int getStreamVolume(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getStreamVolume(streamType);
        }
        return -1;
    }

    public int getStreamMaxVolume(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getStreamMaxVolume(streamType);
        }
        return -1;
    }

    public int getStreamMinVolume(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getStreamMinVolume(streamType);
        }
        return -1;
    }

    public int getLastAudibleStreamVolume(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getLastAudibleStreamVolume(streamType);
        }
        return -1;
    }

    public void setMusicLimitMode(boolean modeOn) {
        printDebugLog("setMusicLimitMode " + modeOn);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setMusicLimitMode(modeOn);
        }
    }

    public boolean isMusicLimitMode() {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.isMusicLimitMode();
        }
        return false;
    }

    public void setBanVolumeChangeMode(int streamType, int mode, String pkgName) {
        printDebugLog("setBanVolumeChangeMode " + streamType + " " + mode + " " + pkgName);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setBanTemporaryVolChangeMode(streamType, mode, pkgName);
        }
    }

    public int getBanVolumeChangeMode(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getBanTemporaryVolChangeMode(streamType);
        }
        return 0;
    }

    public void checkAlarmVolume() {
        if (this.mXuiAudioPolicy != null) {
            this.mXuiAudioPolicy.checkAlarmVolume();
        }
    }

    public boolean setFixedVolume(boolean enable, int vol, int streamType, String callingPackage) {
        printDebugLog("setFixedVolume " + enable + " " + vol + " " + streamType + " " + callingPackage);
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.setFixedVolume(enable, vol, streamType, callingPackage);
        }
        return false;
    }

    public boolean isFixedVolume(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.isFixedVolume(streamType);
        }
        return false;
    }

    public void setVolumeFaded(int streamType, int vol, int fadetime, String callingPackage) {
        printDebugLog("setVolumeFaded " + streamType + " " + vol + " " + fadetime + " " + callingPackage);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setVolumeFaded(streamType, vol, fadetime, 0, callingPackage);
        }
    }

    public void restoreMusicVolume(String callingPackage) {
        printDebugLog("restoreMusicVolume " + callingPackage);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.restoreMusicVolume(callingPackage);
        }
    }

    public void setDangerousTtsVolLevel(int level) {
        printDebugLog("setDangerousTtsVolLevel " + level);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setDangerousTtsVolLevel(level);
        }
    }

    public int getDangerousTtsVolLevel() {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getDangerousTtsVolLevel();
        }
        return 1;
    }

    public void temporaryChangeVolumeDown(int StreamType, int dstVol, boolean restoreVol, int flag, String packageName) {
        printDebugLog("temporaryChangeVolumeDown " + StreamType + " " + dstVol + " " + restoreVol + " " + flag + " " + packageName);
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.triggerEnvConflictPolicy(flag, !restoreVol, packageName);
        }
    }

    public void setVolWhenPlay(int streamType) {
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setVolWhenPlay(streamType);
        }
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        if (this.mXuiEffectPolicy != null) {
            this.mXuiEffectPolicy.setSoundField(mode, xSound, ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        if (this.mXuiEffectPolicy != null) {
            return this.mXuiEffectPolicy.getSoundField(mode);
        }
        return null;
    }

    public int getSoundEffectMode() {
        if (this.mXuiEffectPolicy != null) {
            return this.mXuiEffectPolicy.getSoundEffectMode();
        }
        return -1;
    }

    public void setSoundEffectMode(int mode) {
        if (this.mXuiEffectPolicy != null) {
            this.mXuiEffectPolicy.setSoundEffectMode(mode);
        }
    }

    public void setSoundEffectType(int mode, int type) {
        if (this.mXuiEffectPolicy != null) {
            this.mXuiEffectPolicy.setSoundEffectType(mode, type);
        }
    }

    public int getSoundEffectType(int mode) {
        if (this.mXuiEffectPolicy != null) {
            return this.mXuiEffectPolicy.getSoundEffectType(mode);
        }
        return -1;
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        if (this.mXuiVolumePolicy != null) {
            this.mXuiVolumePolicy.setNavVolDecreaseEnable(enable);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        if (this.mXuiVolumePolicy != null) {
            return this.mXuiVolumePolicy.getNavVolDecreaseEnable();
        }
        return true;
    }

    public void setXpCustomizeEffect(int type, int value) {
        Log.i(TAG, "setXpCustomizeEffect  " + type + " " + value);
        if (this.mXuiEffectPolicy != null) {
            this.mXuiEffectPolicy.setXpCustomizeEffect(type, value);
        }
    }

    public int getXpCustomizeEffect(int type) {
        if (this.mXuiEffectPolicy != null) {
            return this.mXuiEffectPolicy.getXpCustomizeEffect(type);
        }
        return 0;
    }

    public void flushXpCustomizeEffects(int[] values) {
        Log.i(TAG, "setXpCustomizeEffect ");
        if (this.mXuiEffectPolicy != null) {
            this.mXuiEffectPolicy.flushXpCustomizeEffects(values);
        }
    }

    public void setSoundEffectScene(int mode, int type) {
        if (this.mXuiEffectPolicy != null) {
            this.mXuiEffectPolicy.setSoundEffectScene(mode, type);
        }
    }

    public int getSoundEffectScene(int mode) {
        if (this.mXuiEffectPolicy != null) {
            return this.mXuiEffectPolicy.getSoundEffectScene(mode);
        }
        return -1;
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        if (this.mXuiEffectPolicy != null) {
            this.mXuiEffectPolicy.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        if (this.mXuiEffectPolicy != null) {
            return this.mXuiEffectPolicy.getSoundEffectParms(effectType, modeType);
        }
        return new SoundEffectParms(0, 0, 0);
    }

    public void setStereoAlarm(boolean enable) {
        if (this.mXuiChannelPolicy != null) {
            this.mXuiChannelPolicy.setStereoAlarm(enable);
        }
    }

    public void setSpeechSurround(boolean enable) {
        Log.d(TAG, "setSpeechSurround  NOT USED");
    }

    public void setMainDriver(boolean enable) {
        Log.d(TAG, "setMainDriver  NOT USED");
    }

    public boolean isStereoAlarmOn() {
        if (this.mXuiChannelPolicy != null) {
            return this.mXuiChannelPolicy.isStereoAlarmOn();
        }
        return false;
    }

    public boolean isSpeechSurroundOn() {
        Log.d(TAG, "isSpeechSurroundOn  NOT USED");
        return false;
    }

    public boolean isMainDriverOn() {
        Log.d(TAG, "isMainDriverOn  NOT USED");
        return false;
    }

    public void setBtHeadPhone(boolean enable) {
        Log.d(TAG, "setBtHeadPhone " + enable);
        if (this.mXuiChannelPolicy != null) {
            this.mXuiChannelPolicy.setBtHeadPhone(enable);
        }
    }

    public boolean isBtHeadPhoneOn() {
        if (this.mXuiChannelPolicy != null) {
            return this.mXuiChannelPolicy.isBtHeadPhoneOn();
        }
        return false;
    }

    public int selectAlarmChannels(int location, int fadeTimeMs, int soundid) {
        if (this.mXuiChannelPolicy != null) {
            this.mXuiChannelPolicy.selectAlarmChannels(location, fadeTimeMs, soundid);
            return 0;
        }
        return 0;
    }

    public void setVoiceStatus(int status) {
        Log.d(TAG, "setVoiceStatus  NOT USED");
    }

    public int getVoiceStatus() {
        Log.d(TAG, "getVoiceStatus  NOT USED");
        return 0;
    }

    public void setVoicePosition(int position, int flag, String pkgName) {
        if (this.mXuiChannelPolicy != null) {
            this.mXuiChannelPolicy.setVoicePosition(position, flag, pkgName);
        }
    }

    public int getVoicePosition() {
        if (this.mXuiChannelPolicy != null) {
            return this.mXuiChannelPolicy.getVoicePosition();
        }
        return 0;
    }

    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        if (this.mXuiChannelPolicy != null) {
            this.mXuiChannelPolicy.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
        }
    }

    public void ChangeChannelByTrack(int usage, int id, boolean start) {
        if (this.mXuiChannelPolicy != null) {
            this.mXuiChannelPolicy.ChangeChannelByTrack(usage, id, start);
        }
    }

    public void setAvasStreamEnable(int busType, boolean enable) {
        if (this.mXuiChannelPolicy != null) {
            this.mXuiChannelPolicy.setAvasStreamEnable(busType, enable);
        }
    }

    private void printDebugLog(String log) {
        Log.d(TAG, log);
    }
}
