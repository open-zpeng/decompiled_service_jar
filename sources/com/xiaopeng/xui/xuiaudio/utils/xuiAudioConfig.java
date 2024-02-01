package com.xiaopeng.xui.xuiaudio.utils;

import android.net.util.NetworkConstants;
import android.os.SystemProperties;
import com.xiaopeng.util.FeatureOption;

/* loaded from: classes2.dex */
public class xuiAudioConfig {
    public static final String ACTION_SECOND_BT_CONNECTION = "xiaopeng.bluetooth.a2dp.action.CONNECTION_STATE_CHANGED";
    public static final int AudioFlingerRestoreDelay;
    public static boolean DEBUG_DUMP_ENABLE = false;
    private static final String TAG = "xuiAudioConfig";
    public static final boolean hasAmp;
    public static final boolean hasMassSeat;
    public static final boolean hasPassageBtUsbFeature;
    public static final boolean mAvasOutputEnable;
    public static final int massageSeatModel;
    public static boolean pioneerAmpOpen;
    public static boolean seatMusicModel;
    public static boolean sndFieldNewway;

    static {
        hasPassageBtUsbFeature = FeatureOption.FO_PASSENGER_BT_AUDIO_EXIST > 0;
        hasMassSeat = FeatureOption.hasFeature("FSEAT_RHYTHM");
        hasAmp = FeatureOption.hasFeature("AMP");
        massageSeatModel = SystemProperties.getInt("persist.xiaopeng.audio.massageSeat.model", 0);
        seatMusicModel = hasMassSeat && massageSeatModel == 1;
        pioneerAmpOpen = FeatureOption.FO_AMP_TYPE == 1 && hasAmp;
        mAvasOutputEnable = FeatureOption.FO_AVAS_SUPPORT_TYPE == 1;
        AudioFlingerRestoreDelay = SystemProperties.getInt("persist.xiaopeng.audioflinger.restroe.delay", (int) NetworkConstants.ETHER_MTU);
        DEBUG_DUMP_ENABLE = SystemProperties.getBoolean("persist.sys.xiaopeng.xuiaudio.DebugDump", false);
        sndFieldNewway = SystemProperties.getBoolean("persist.sys.xiaopeng.xuiaudio.sndfiled", false);
    }
}
