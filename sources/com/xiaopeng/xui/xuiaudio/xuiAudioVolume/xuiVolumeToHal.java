package com.xiaopeng.xui.xuiaudio.xuiAudioVolume;

import android.car.ICar;
import android.car.hardware.XpVehicle.IXpVehicle;
import android.car.media.ICarAudio;
import android.content.Context;
import android.util.Log;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.utils.remoteConnect;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class xuiVolumeToHal implements IXuiVolumeInterface, remoteConnect.RemoteConnectListerer {
    private static final float MAX_VOLUME = 30.0f;
    private static final String TAG = "xuiVolumeToHal";
    private static int mAudioFeature = 0;
    private static Context mContext;
    private static xuiVolumeToHal mInstance;
    private static remoteConnect mRemoteConnect;
    private static xuiVolumePolicy mXuiVolumePolicy;
    private ICarAudio mCarAudio;
    private ICar mCarService;
    private int[] mGroupMaxVolume;
    private float[] mRatios;
    private IXpVehicle mXpVehicle;

    private xuiVolumeToHal(xuiVolumePolicy instance, Context context) {
        mContext = context;
        mXuiVolumePolicy = instance;
        mRemoteConnect = remoteConnect.getInstance(context);
        mRemoteConnect.registerListener(this);
    }

    public static xuiVolumeToHal getInstance(xuiVolumePolicy instance, Context context) {
        if (mInstance == null) {
            mInstance = new xuiVolumeToHal(instance, context);
        }
        return mInstance;
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onCarServiceConnected() {
        Log.d(TAG, "onCarServiceConnected() " + mRemoteConnect);
        if (mRemoteConnect != null) {
            this.mCarAudio = mRemoteConnect.getCarAudio();
            if (this.mCarAudio != null) {
                try {
                    int count = this.mCarAudio.getVolumeGroupCount();
                    this.mRatios = new float[count];
                    this.mGroupMaxVolume = new int[count];
                    for (int i = 0; i < count; i++) {
                        this.mGroupMaxVolume[i] = this.mCarAudio.getGroupMaxVolume(i);
                        this.mRatios[i] = this.mGroupMaxVolume[i] / MAX_VOLUME;
                    }
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
            }
            if (mXuiVolumePolicy != null) {
                mXuiVolumePolicy.resetAllVolume();
            }
            this.mXpVehicle = mRemoteConnect.getXpVehicle();
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onXuiServiceConnected() {
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioVolume.IXuiVolumeInterface
    public void setGroupVolume(int groupId, int index, int flag) {
        if (this.mCarAudio != null) {
            try {
                Log.d(TAG, "setGroupVolume " + groupId + " " + index + " " + flag);
                int value = Float.valueOf((float) Math.round(((float) index) * this.mRatios[groupId])).intValue();
                this.mCarAudio.setGroupVolume(groupId, value, flag);
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioVolume.IXuiVolumeInterface
    public void broadcastVolumeToICM(int streamType, int volume, int savedStreamVolume, boolean fromadj) {
        Log.i(TAG, "broadcastVolumeToICM TYPE:" + streamType + " VOL:" + volume + " fromadj:" + fromadj);
        String volType = null;
        int type = -1;
        switch (streamType) {
            case 2:
            case 6:
                volType = "Phone Volume";
                type = 0;
                break;
            case 3:
                volType = "Media Volume";
                type = 3;
                break;
            case 9:
                volType = "Navi Volume";
                type = -1;
                break;
            case 10:
                volType = "Voice Volume";
                type = 4;
                break;
        }
        if (this.mXpVehicle != null && volType != null) {
            try {
                if (FeatureOption.FO_ICM_TYPE == 0) {
                    if (type != -1) {
                        if (volume == 255) {
                            this.mXpVehicle.setMeterSoundState(type, savedStreamVolume, 1);
                        } else if (volume >= 0 && volume <= 30) {
                            this.mXpVehicle.setMeterSoundState(type, volume, 0);
                        }
                    }
                } else if (fromadj) {
                    JSONObject volumeObj = new JSONObject();
                    volumeObj.put("OSDMode", volType);
                    volumeObj.put("OSDLastValue", mXuiVolumePolicy.getStreamVolume(streamType));
                    volumeObj.put("OSDValue", volume);
                    this.mXpVehicle.setIcmOsdShow(volumeObj.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    @Override // com.xiaopeng.xui.xuiaudio.xuiAudioVolume.IXuiVolumeInterface
    public void sendMusicVolumeToAmp(int index) {
        if (this.mXpVehicle != null) {
            try {
                this.mXpVehicle.sendCduVolumeToAmp(index);
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
    }
}
