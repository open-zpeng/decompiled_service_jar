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

/* loaded from: classes2.dex */
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
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.checkStreamActive(streamType);
        }
        return false;
    }

    public boolean isAnyStreamActive() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.isAnyStreamActive();
        }
        return false;
    }

    public int checkStreamCanPlay(int streamType) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.checkStreamCanPlay(streamType);
        }
        return 2;
    }

    public void startAudioCapture(int audioSession, int usage) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.startAudioCapture(audioSession, usage);
        }
    }

    public void stopAudioCapture(int audioSession, int usage) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.stopAudioCapture(audioSession, usage);
        }
    }

    public int applyUsage(int usage, int id) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.applyUsage(usage, id);
        }
        return -1;
    }

    public int releaseUsage(int usage, int id) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.releaseUsage(usage, id);
        }
        return -1;
    }

    public boolean isUsageActive(int usage) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.isUsageActive(usage);
        }
        return false;
    }

    public void setRingtoneSessionId(int streamType, int sessionId, String pkgName) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setRingtoneSessionId(streamType, sessionId, pkgName);
        }
    }

    public void setBtCallOn(boolean enable) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setBtCallOn(enable);
        }
    }

    public void setBtCallOnFlag(int flag) {
        Log.i(TAG, "setBtCallOnFlag " + flag);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setBtCallOnFlag(flag);
        }
    }

    public void setNetEcallEnable(boolean enable) {
        Log.i(TAG, "setNetEcallEnable " + enable);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setNetEcallEnable(enable);
        }
    }

    public int getBtCallOnFlag() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getBtCallOnFlag();
        }
        return 0;
    }

    public boolean isBtCallOn() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.isBtCallOn();
        }
        return false;
    }

    public void setBtCallMode(int mode) {
        Log.i(TAG, "setBtCallMode " + mode);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setBtCallMode(mode);
        }
    }

    public int getBtCallMode() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getBtCallMode();
        }
        return 0;
    }

    public void setKaraokeOn(boolean on) {
        Log.i(TAG, "setKaraokeOn " + on);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setKaraokeOn(on);
        }
    }

    public boolean isKaraokeOn() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.isKaraokeOn();
        }
        return false;
    }

    public boolean isOtherSessionOn() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.isOtherSessionOn();
        }
        return false;
    }

    public List<String> getOtherMusicPlayingPkgs() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getOtherMusicPlayingPkgs();
        }
        return null;
    }

    public boolean isFmOn() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.isFmOn();
        }
        return false;
    }

    public void playbackControl(int cmd, int param) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.playbackControl(cmd, param);
        }
    }

    public void setDangerousTtsStatus(int on) {
        Log.i(TAG, "setDangerousTtsStatus " + on);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setDangerousTtsStatus(on);
        }
    }

    public int getDangerousTtsStatus() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getDangerousTtsStatus();
        }
        return 0;
    }

    public void setMainDriverMode(int mode) {
        Log.i(TAG, "setMainDriverMode " + mode);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setMainDriverMode(mode);
        }
    }

    public int getMainDriverMode() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getMainDriverMode();
        }
        return 0;
    }

    public void applyAlarmId(int usage, int id) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.applyAlarmId(usage, id);
        }
    }

    public void startSpeechEffect(int audioSession) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.startSpeechEffect(audioSession);
        }
    }

    public void stopSpeechEffect(int audioSession) {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.stopSpeechEffect(audioSession);
        }
    }

    public List<xpAudioSessionInfo> getActiveSessionList() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getActiveSessionList();
        }
        return null;
    }

    public void audioServerDiedRestore() {
        Log.i(TAG, "audioServerDiedRestore ");
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.audioServerDiedRestore();
        }
    }

    public void adjustStreamVolume(int streamType, int direction, int flags, String packageName) {
        xuiAudioPolicy xuiaudiopolicy;
        printDebugLog("adjustStreamVolume " + streamType + " " + direction + " " + flags + " " + packageName);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.adjustStreamVolume(streamType, direction, flags, packageName);
        }
        if (flags == 268599296 && (xuiaudiopolicy = this.mXuiAudioPolicy) != null) {
            xuiaudiopolicy.igStatusChange(1);
        }
    }

    public void setStreamVolume(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setVolWhenPlay(streamType);
        }
    }

    public void setStreamVolume(int streamType, int index, int flags, String packageName) {
        printDebugLog("setStreamVolume " + streamType + " " + index + " " + flags + " " + packageName);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setStreamVolume(streamType, index, flags, packageName);
        }
    }

    public boolean getStreamMute(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getStreamMute(streamType);
        }
        return false;
    }

    public int getStreamVolume(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getStreamVolume(streamType);
        }
        return -1;
    }

    public int getStreamMaxVolume(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getStreamMaxVolume(streamType);
        }
        return -1;
    }

    public int getStreamMinVolume(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getStreamMinVolume(streamType);
        }
        return -1;
    }

    public int getLastAudibleStreamVolume(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getLastAudibleStreamVolume(streamType);
        }
        return -1;
    }

    public void setMusicLimitMode(boolean modeOn) {
        printDebugLog("setMusicLimitMode " + modeOn);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setMusicLimitMode(modeOn);
        }
    }

    public boolean isMusicLimitMode() {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.isMusicLimitMode();
        }
        return false;
    }

    public void setBanVolumeChangeMode(int streamType, int mode, String pkgName) {
        printDebugLog("setBanVolumeChangeMode " + streamType + " " + mode + " " + pkgName);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setBanTemporaryVolChangeMode(streamType, mode, pkgName);
        }
    }

    public int getBanVolumeChangeMode(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getBanTemporaryVolChangeMode(streamType);
        }
        return 0;
    }

    public void checkAlarmVolume() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.checkAlarmVolume();
        }
    }

    public boolean setFixedVolume(boolean enable, int vol, int streamType, String callingPackage) {
        printDebugLog("setFixedVolume " + enable + " " + vol + " " + streamType + " " + callingPackage);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.setFixedVolume(enable, vol, streamType, callingPackage);
        }
        return false;
    }

    public boolean isFixedVolume(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.isFixedVolume(streamType);
        }
        return false;
    }

    public void setVolumeFaded(int streamType, int vol, int fadetime, String callingPackage) {
        printDebugLog("setVolumeFaded " + streamType + " " + vol + " " + fadetime + " " + callingPackage);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setVolumeFaded(streamType, vol, fadetime, 0, callingPackage);
        }
    }

    public void restoreMusicVolume(String callingPackage) {
        printDebugLog("restoreMusicVolume " + callingPackage);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.restoreMusicVolume(callingPackage);
        }
    }

    public void setDangerousTtsVolLevel(int level) {
        printDebugLog("setDangerousTtsVolLevel " + level);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setDangerousTtsVolLevel(level);
        }
    }

    public int getDangerousTtsVolLevel() {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getDangerousTtsVolLevel();
        }
        return 1;
    }

    public void temporaryChangeVolumeDown(int StreamType, int dstVol, boolean restoreVol, int flag, String packageName) {
        printDebugLog("temporaryChangeVolumeDown " + StreamType + " " + dstVol + " " + restoreVol + " " + flag + " " + packageName);
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.triggerEnvConflictPolicy(flag, dstVol, !restoreVol, packageName);
        }
    }

    public void setVolWhenPlay(int streamType) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setVolWhenPlay(streamType);
        }
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        Log.i(TAG, "setSoundField " + mode + " " + xSound + " " + ySound);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setSoundField(mode, xSound, ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getSoundField(mode);
        }
        return null;
    }

    public int getSoundEffectMode() {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getSoundEffectMode();
        }
        return -1;
    }

    public void setSoundEffectMode(int mode) {
        Log.i(TAG, "setSoundEffectMode " + mode);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setSoundEffectMode(mode);
        }
    }

    public void setSoundEffectType(int mode, int type) {
        Log.i(TAG, "setSoundEffectType " + mode + " " + type);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setSoundEffectType(mode, type);
        }
    }

    public int getSoundEffectType(int mode) {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getSoundEffectType(mode);
        }
        return -1;
    }

    public void setSoundEffectScene(int mode, int type) {
        Log.i(TAG, "setSoundEffectScene " + mode + " " + type);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setSoundEffectScene(mode, type);
        }
    }

    public int getSoundEffectScene(int mode) {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getSoundEffectScene(mode);
        }
        return -1;
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        Log.i(TAG, "setSoundEffectParms " + effectType + " " + nativeValue + " " + softValue + " " + innervationValue);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setSoundEffectParms(effectType, nativeValue, softValue, innervationValue);
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getSoundEffectParms(effectType, modeType);
        }
        return new SoundEffectParms(0, 0, 0);
    }

    public void setSoundSpeedLinkLevel(int level) {
        Log.i(TAG, "setSoundSpeedLinkLevel  " + level);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setSoundSpeedLinkLevel(level);
        }
    }

    public int getSoundSpeedLinkLevel() {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getSoundSpeedLinkLevel();
        }
        return 0;
    }

    public void setDyn3dEffectLevel(int level) {
        Log.i(TAG, "setDyn3dEffectLevel  " + level);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setDyn3dEffectLevel(level);
        }
    }

    public int getDyn3dEffectLevel() {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getDyn3dEffectLevel();
        }
        return 0;
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            xuivolumepolicy.setNavVolDecreaseEnable(enable);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        xuiVolumePolicy xuivolumepolicy = this.mXuiVolumePolicy;
        if (xuivolumepolicy != null) {
            return xuivolumepolicy.getNavVolDecreaseEnable();
        }
        return true;
    }

    public void setXpCustomizeEffect(int type, int value) {
        Log.i(TAG, "setXpCustomizeEffect  " + type + " " + value);
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.setXpCustomizeEffect(type, value);
        }
    }

    public int getXpCustomizeEffect(int type) {
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            return xuieffectpolicy.getXpCustomizeEffect(type);
        }
        return 0;
    }

    public void flushXpCustomizeEffects(int[] values) {
        Log.i(TAG, "setXpCustomizeEffect ");
        xuiEffectPolicy xuieffectpolicy = this.mXuiEffectPolicy;
        if (xuieffectpolicy != null) {
            xuieffectpolicy.flushXpCustomizeEffects(values);
        }
    }

    public void setStereoAlarm(boolean enable) {
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            xuichannelpolicy.setStereoAlarm(enable);
        }
    }

    public void setSpeechSurround(boolean enable) {
        Log.d(TAG, "setSpeechSurround  NOT USED");
    }

    public void setMainDriver(boolean enable) {
        Log.d(TAG, "setMainDriver  NOT USED");
    }

    public boolean isStereoAlarmOn() {
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            return xuichannelpolicy.isStereoAlarmOn();
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
        Log.i(TAG, "setBtHeadPhone " + enable);
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            xuichannelpolicy.setBtHeadPhone(enable);
        }
    }

    public boolean isBtHeadPhoneOn() {
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            return xuichannelpolicy.isBtHeadPhoneOn();
        }
        return false;
    }

    public int selectAlarmChannels(int location, int fadeTimeMs, int soundid) {
        if (this.mXuiChannelPolicy != null) {
            Log.d(TAG, "selectAlarmChannels location:" + location + " fadeTimeMs:" + fadeTimeMs + " soundid:" + soundid);
            this.mXuiChannelPolicy.selectAlarmChannels(location, fadeTimeMs, soundid);
            this.mXuiChannelPolicy.setStreamPosition(4, "xuiAudio", location, soundid);
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
        Log.i(TAG, "setVoicePosition  " + position + " " + flag + " " + pkgName);
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            xuichannelpolicy.setVoicePosition(position, flag, pkgName);
        }
    }

    public int getVoicePosition() {
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            return xuichannelpolicy.getVoicePosition();
        }
        return 0;
    }

    public void forceChangeToAmpChannel(int channelBits, int activeBits, int volume, boolean stop) {
        Log.i(TAG, "forceChangeToAmpChannel  " + channelBits + " " + activeBits + " " + volume + " " + stop);
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            xuichannelpolicy.forceChangeToAmpChannel(channelBits, activeBits, volume, stop);
        }
    }

    public void ChangeChannelByTrack(int usage, int id, boolean start) {
        Log.i(TAG, "ChangeChannelByTrack  " + usage + " " + id + " " + start);
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            xuichannelpolicy.ChangeChannelByTrack(usage, id, start);
        }
    }

    public void setStreamPosition(int streamType, String pkgName, int position, int id) {
        Log.i(TAG, "setStreamPosition  " + streamType + " " + pkgName + " " + position + " " + id);
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            xuichannelpolicy.setStreamPosition(streamType, pkgName, position, id);
        }
    }

    public void setSoundPositionEnable(boolean enable) {
        Log.i(TAG, "setSoundPositionEnable  " + enable);
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            xuichannelpolicy.setSoundPositionEnable(enable);
        }
    }

    public boolean getSoundPositionEnable() {
        xuiChannelPolicy xuichannelpolicy = this.mXuiChannelPolicy;
        if (xuichannelpolicy != null) {
            return xuichannelpolicy.getSoundPositionEnable();
        }
        return false;
    }

    public void setAvasStreamEnable(int busType, boolean enable) {
    }

    public void setSessionIdStatus(int sessionId, int position, int status) {
        Log.i(TAG, "setSessionIdStatus  " + sessionId + " " + position + " " + status);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setSessionIdStatus(sessionId, position, status);
        }
    }

    public void setMassageSeatLevel(List<String> levelList) {
        Log.i(TAG, "setMassageSeatLevel  ");
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setMassageSeatLevel(levelList);
        }
    }

    public void setMusicSeatEnable(boolean enable) {
        Log.i(TAG, "setMusicSeatEnable  " + enable);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setMusicSeatEnable(enable);
        }
    }

    public boolean getMusicSeatEnable() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getMusicSeatEnable();
        }
        return false;
    }

    public void setMusicSeatRythmPause(boolean pause) {
        Log.i(TAG, "setMusicSeatRythmPause " + pause);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setMusicSeatRythmPause(pause);
        }
    }

    public void setMusicSeatEffect(int index) {
        Log.i(TAG, "setMusicSeatEffect:" + index);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setMusicSeatEffect(index);
        }
    }

    public int getMusicSeatEffect() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getMusicSeatEffect();
        }
        return 0;
    }

    public void setMmapToAvasEnable(boolean enable) {
        Log.i(TAG, "setMmapToAvasEnable  " + enable);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setMmapToAvasEnable(enable);
        }
    }

    public boolean getMmapToAvasEnable() {
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.getMmapToAvasEnable();
        }
        return false;
    }

    public void setSpecialOutputId(int outType, int sessionId, boolean enable) {
        Log.i(TAG, "setSpecialOutputId  " + outType + " " + sessionId + " " + enable);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setSpecialOutputId(outType, sessionId, enable);
        }
    }

    public void setAudioPathWhiteList(int type, String writeList) {
        Log.i(TAG, "setAudioPathWhiteList  " + type + " " + writeList);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setAudioPathWhiteList(type, writeList);
        }
    }

    public void setSoftTypeVolumeMute(int type, boolean enable) {
        Log.i(TAG, "setSoftTypeVolumeMute  " + enable);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.setSoftTypeVolumeMute(type, enable);
        }
    }

    public void playAvasSound(int position, String path) {
        Log.i(TAG, "playAvasSound  " + position + " " + path);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.playAvasSound(position, path);
        }
    }

    public void stopAvasSound(String path) {
        Log.i(TAG, "stopAvasSound  " + path);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            xuiaudiopolicy.stopAvasSound(path);
        }
    }

    public boolean checkPlayingRouteByPackage(int type, String pkgName) {
        Log.i(TAG, "checkPlayingRouteByPackage  " + type + " " + pkgName);
        xuiAudioPolicy xuiaudiopolicy = this.mXuiAudioPolicy;
        if (xuiaudiopolicy != null) {
            return xuiaudiopolicy.checkPlayingRouteByPackage(type, pkgName);
        }
        return false;
    }

    private void printDebugLog(String log) {
        Log.d(TAG, log);
    }
}
