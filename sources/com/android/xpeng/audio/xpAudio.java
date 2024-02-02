package com.android.xpeng.audio;

import android.app.ActivityManager;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.car.ICar;
import android.car.hardware.XpVehicle.IXpVehicle;
import android.car.media.ICarAudio;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioSystem;
import android.media.SoundEffectParms;
import android.media.SoundField;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import com.android.server.backup.BackupManagerConstants;
import com.xiaopeng.XpEffectSet;
import com.xiaopeng.audio.xpAudioSessionInfo;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xpaudioeffectpolicy.XpAudioEffect;
import com.xiaopeng.xpaudiopolicy.IXpVolumePolicy;
import com.xiaopeng.xpaudiopolicy.XpAudioPolicy;
import com.xiaopeng.xuimanager.IXUIService;
import com.xiaopeng.xuimanager.mediacenter.IMediaCenter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class xpAudio {
    private static final String ACTION_POWER_STATE_CHANGED = "com.xiaopeng.intent.action.XUI_POWER_STATE_CHANGED";
    private static final String ANDROIDSYS_PKGNAME = "android";
    public static final int BTCALL_CALLING_OUT = 3;
    public static final int BTCALL_CONNECTTED = 2;
    public static final int BTCALL_NOT_CONNECTTED = 0;
    public static final int BTCALL_RINGING = 1;
    private static final String BTCALL_VOLCHANGE_PACKAGE = "xpaudio_btcall";
    private static final String BluetoothPkgName = "com.android.bluetooth";
    public static final int BtCallMode_CDU = 1;
    public static final int BtCallMode_Phone = 0;
    private static final String CAR_AUDIO_SERVICE = "audio";
    private static final String CAR_SERVICE_INTERFACE_NAME = "android.car.ICar";
    private static final String CAR_SERVICE_PACKAGE = "com.android.car";
    private static final String ChaoPaoPkgName = "com.car.chaopaoshenglangxp";
    private static final int DEFAULT_VOLUME = 15;
    private static final int DEF_FADE_TIME = 500;
    private static final String EXTRA_IG_STATE = "android.intent.extra.IG_STATE";
    private static boolean FORCE_BT_FADE_SW = false;
    private static final float MAX_VOLUME = 30.0f;
    private static final String MEDIACENTER_SERVICE = "mediacenter";
    private static final int MSG_ADJUSTSTREAM_VOLUME = 4;
    private static final int MSG_CARSERVICE_ON_CONNECT = 2;
    private static final int MSG_CHANGE_STREAM_VOL = 5;
    private static final int MSG_MEDIASERVICE_ON_CONNECT = 1;
    private static final int MSG_SETSTREAM_VOLUME = 3;
    private static final String PROP_VOLUME_LIMITEDMODE = "persist.audio.volume.limitemode";
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_DISCONNECTED = 0;
    private static final String SystemUiPkgName = "com.android.systemui";
    private static final String TAG = "xpAudio";
    private static final int TEMPVOLCHANGE_FLAG_BTCALL = 4;
    private static final int TEMPVOLCHANGE_FLAG_DANGERTTS = 8;
    private static final int TEMPVOLCHANGE_FLAG_DOOR = 2;
    private static final int TEMPVOLCHANGE_FLAG_GEAR = 1;
    private static final int TEMPVOLCHANGE_FLAG_XUIALARM = 16;
    private static final int TEMPVOLCHANGE_FLAG_ZENMODE = 32;
    public static final int VALIDVOL_RESET_FLAG = 8;
    private static final String XP_VEHICLE_SERVICE = "xp_vehicle";
    private static final String XUI_SERVICE_CLASS = "com.xiaopeng.xuiservice.XUIService";
    private static final String XUI_SERVICE_INTERFACE_NAME = "com.xiaopeng.xuimanager.IXUIService";
    private static final String XUI_SERVICE_PACKAGE = "com.xiaopeng.xuiservice";
    private static final String XUI_SERVICE_PACKAGE2 = "com.xiaopeng.xuiservice2";
    private static final long adjustInterval = 50;
    private static int[] currCarVolume = null;
    private static final String settingsPkgName = "com.xiaopeng.car.settings";
    private static int[] tempVolChangeSaved;
    private final String CarSettingPkgName;
    private final String NativePkgName;
    private boolean[] VolumeFadeBreakFlag;
    private ConcurrentHashMap<Integer, String> XiaoPengMusicSessionMap;
    private final String XiaoPengPkgName;
    int btcallMode;
    private final BroadcastReceiver mBootReceiver;
    private ICarAudio mCarAudio;
    private ICar mCarService;
    private int mConnectionState;
    private Context mContext;
    private boolean[] mFadingFlag;
    private int[] mGroupMaxVolume;
    private int[] mGroupMinVolume;
    Handler mHandler;
    private HandlerThread mHandlerThread;
    boolean mIsMusicLimitmode;
    private IMediaCenter mMcService;
    private float[] mRatios;
    private final BroadcastReceiver mReceiver;
    private List<Integer> mRingtionBanSessions;
    private final ArrayList<tempVolChangeData> mTempVolChangeDataList;
    private boolean[] mValidVolumeMode;
    private final Intent mVolumeChanged;
    private IXUIService mXUIService;
    private XpAudioEffect mXpAudioEffect;
    private XpAudioPolicy mXpAudioPolicy;
    private XpEffectSet mXpEffectSet;
    private IXpVehicle mXpVehicle;
    private IXpVolumePolicy mXpVolumePolicy;
    private int musicRestoreVolume;
    private static final int AUDIO_EFFECT_FORM = FeatureOption.FO_AUDIO_EFFECT_FORM;
    private static Object mFadeBLock = new Object();
    private static Object carVolumeLock = new Object();
    private static Object xpAuioBroadcastLock = new Object();
    private static boolean isInBtCall = false;
    private static boolean isInKaraoke = false;
    private static long lastBindXuiServiceTime = 0;
    private static long lastAdjustTime = -1;
    private static Object SessionMapLock = new Object();
    public static int mBtCallFlag = 0;
    private static boolean sysVolFading = false;
    private static boolean KaraokeSysVolMode = false;
    private static int DangerousTtsStatus = 0;
    private static boolean inForceMusicVolumeDown = false;
    private static int inForceMusicSavedVol = -1;
    Map<Integer, Integer> StreamGroupIdMap = new HashMap();
    boolean fakeMuteFlag = false;
    int mFadeTime = 0;
    boolean fadeIn = false;
    String AUDIO_SYSTEM_PACKAGENAME = "android.audio.System";
    String EXTRA_VOLCHANGE_PACKAGENAME = "android.media.vol_change.PACKAGE_NAME";
    private SparseArray<xpAudioSessionInfo> mActiveSessionArray = new SparseArray<>();
    private final ServiceConnection mMediaServiceConnectionListener = new ServiceConnection() { // from class: com.android.xpeng.audio.xpAudio.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            xpAudio.this.mXUIService = IXUIService.Stub.asInterface(service);
            xpAudio.this.mConnectionState = 2;
            IBinder binder = null;
            Log.d(xpAudio.TAG, "xuiservice onServiceConnected!!!!");
            try {
                binder = xpAudio.this.mXUIService.getXUIService(xpAudio.MEDIACENTER_SERVICE);
            } catch (RemoteException e) {
                Log.e(xpAudio.TAG, e.toString());
            }
            if (binder != null) {
                xpAudio.this.mMcService = IMediaCenter.Stub.asInterface(binder);
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            xpAudio.this.mXUIService = null;
            xpAudio.this.mConnectionState = 0;
        }
    };
    private final ServiceConnection mCarServiceConnectionListener = new ServiceConnection() { // from class: com.android.xpeng.audio.xpAudio.2
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            xpAudio.this.mCarService = ICar.Stub.asInterface(service);
            try {
                IBinder binder = xpAudio.this.mCarService.getCarService(xpAudio.CAR_AUDIO_SERVICE);
                if (binder != null) {
                    xpAudio.this.mCarAudio = ICarAudio.Stub.asInterface(binder);
                    int count = xpAudio.this.mCarAudio.getVolumeGroupCount();
                    int[] unused = xpAudio.currCarVolume = new int[count];
                    xpAudio.this.mRatios = new float[count];
                    xpAudio.this.mGroupMaxVolume = new int[count];
                    xpAudio.this.mGroupMinVolume = new int[count];
                    for (int i = 0; i < count; i++) {
                        xpAudio.this.mGroupMaxVolume[i] = xpAudio.this.mCarAudio.getGroupMaxVolume(i);
                        xpAudio.this.mRatios[i] = xpAudio.this.mGroupMaxVolume[i] / xpAudio.MAX_VOLUME;
                        xpAudio.this.mGroupMinVolume[i] = xpAudio.this.mCarAudio.getGroupMinVolume(i);
                    }
                    for (int i2 = 0; i2 <= 10; i2++) {
                        xpAudio.this.StreamGroupIdMap.put(Integer.valueOf(i2), Integer.valueOf(xpAudio.this.mCarAudio.getVolumeGroupIdForStreamType(i2)));
                        Log.i(xpAudio.TAG, "StreamGroupIdMap  put i:" + i2 + " " + xpAudio.this.StreamGroupIdMap.get(Integer.valueOf(i2)));
                    }
                }
                IBinder binder2 = xpAudio.this.mCarService.getCarService(xpAudio.XP_VEHICLE_SERVICE);
                if (binder2 != null) {
                    xpAudio.this.mXpVehicle = IXpVehicle.Stub.asInterface(binder2);
                    if (xpAudio.this.isHighAmp) {
                        Log.i(xpAudio.TAG, "set AMP unMute!!");
                        xpAudio.this.mXpVehicle.setAmpMute(0);
                    }
                }
                CarServiceDeathRecipient deathRecipient = new CarServiceDeathRecipient(binder2);
                binder2.linkToDeath(deathRecipient, 0);
                xpAudio.this.resetAllVolume();
            } catch (RemoteException e) {
                Log.e(xpAudio.TAG, e.toString());
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            xpAudio.this.mCarService = null;
            xpAudio.this.mCarAudio = null;
            xpAudio.this.mXpVehicle = null;
        }
    };
    boolean isHighAmp = false;

    static {
        FORCE_BT_FADE_SW = SystemProperties.getInt("persist.audio.btfadevol.sw", 1) == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getSessionIdFromProp(String prop) {
        String[] strarray = prop.split(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
        try {
            Log.d(TAG, "getSessionIdFromProp pid:" + strarray[0] + " sessionid:" + strarray[1] + " " + prop);
            return Integer.parseInt(strarray[1]);
        } catch (Exception e) {
            Log.e(TAG, "getSessionIdFromProp ERROR:" + e);
            return 0;
        }
    }

    private String getAppName(int pID) {
        String processName = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        ActivityManager am = (ActivityManager) this.mContext.getSystemService("activity");
        List l = am.getRunningAppProcesses();
        this.mContext.getPackageManager();
        for (ActivityManager.RunningAppProcessInfo info : l) {
            try {
                if (info.pid == pID) {
                    processName = info.processName;
                }
            } catch (Exception e) {
                Log.e("Process", "Error>> :" + e.toString());
            }
        }
        return processName;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getPackageNameFromProp(String prop) {
        String[] strarray = prop.split(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
        try {
            Log.d(TAG, "getPackageNameFromProp pid:" + strarray[0] + " sessionid:" + strarray[1] + " " + prop);
            return getAppName(Integer.parseInt(strarray[0]));
        } catch (Exception e) {
            Log.e(TAG, "getPackageNameFromProp ERROR:" + e);
            return TAG;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long getSystemTimeFromProp(String prop) {
        String[] strarray = prop.split(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
        try {
            Log.d(TAG, "getSystemTimeFromProp pid:" + strarray[0] + " sessionid:" + strarray[1] + " " + strarray[2] + " " + prop);
            return Long.parseLong(strarray[2]);
        } catch (Exception e) {
            Log.e(TAG, "getSystemTimeFromProp ERROR:" + e);
            return 0L;
        }
    }

    /* JADX WARN: Type inference failed for: r1v25, types: [com.android.xpeng.audio.xpAudio$3] */
    public xpAudio(Context context) {
        this.mXpAudioEffect = null;
        this.mHandlerThread = null;
        this.mXpEffectSet = null;
        this.mIsMusicLimitmode = false;
        this.musicRestoreVolume = this.isHighAmp ? FeatureOption.FO_MEDIA_RESTORE_VOLUME_AMP : FeatureOption.FO_MEDIA_RESTORE_VOLUME;
        this.mBootReceiver = new BroadcastReceiver() { // from class: com.android.xpeng.audio.xpAudio.6
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if (intent != null) {
                    String action = intent.getAction();
                    if (action.equals("android.intent.action.BOOT_COMPLETED")) {
                        Log.d(xpAudio.TAG, "ACTION_BOOT_COMPLETED");
                        if (xpAudio.this.mXUIService == null) {
                            xpAudio.this.BindXUIService();
                        }
                    } else if ("android.intent.action.ACTION_SHUTDOWN".equals(action) && xpAudio.this.isHighAmp && xpAudio.this.mXpVehicle != null) {
                        Log.i(xpAudio.TAG, "REBOOTING!!!");
                        try {
                            xpAudio.this.mXpVehicle.setAmpMute(1);
                        } catch (Exception e) {
                        }
                    }
                }
            }
        };
        this.XiaoPengMusicSessionMap = new ConcurrentHashMap<>();
        this.XiaoPengPkgName = "com.xiaopeng.musicradio";
        this.NativePkgName = "android.uid.system:1000";
        this.CarSettingPkgName = settingsPkgName;
        this.btcallMode = 0;
        this.mRingtionBanSessions = new CopyOnWriteArrayList();
        this.mTempVolChangeDataList = new ArrayList<>();
        this.mValidVolumeMode = new boolean[11];
        Arrays.fill(this.mValidVolumeMode, false);
        synchronized (mFadeBLock) {
            this.mFadingFlag = new boolean[11];
            Arrays.fill(this.mFadingFlag, false);
            this.VolumeFadeBreakFlag = new boolean[11];
            Arrays.fill(this.VolumeFadeBreakFlag, false);
        }
        tempVolChangeSaved = new int[11];
        Arrays.fill(tempVolChangeSaved, -1);
        this.mContext = context;
        startExternalServices();
        this.mVolumeChanged = new Intent("android.media.VOLUME_CHANGED_ACTION");
        this.mXpAudioPolicy = XpAudioPolicy.getInstance();
        this.mXpVolumePolicy = this.mXpAudioPolicy.getXpVolumePolicy();
        this.mXpAudioEffect = XpAudioEffect.getInstance();
        this.mXpEffectSet = new XpEffectSet();
        new Thread() { // from class: com.android.xpeng.audio.xpAudio.3
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                try {
                    if (xpAudio.this.mXpAudioEffect != null) {
                        XpAudioEffect unused = xpAudio.this.mXpAudioEffect;
                        XpAudioEffect.parseAndSetAudioEffect();
                    }
                    xpAudio.this.isHighAmp = xpAudio.this.mXpAudioPolicy.checkIsHighAmp();
                    if (xpAudio.this.isHighAmp) {
                        while (xpAudio.this.mXpVehicle == null) {
                            Thread.sleep(500L);
                        }
                    }
                    if (xpAudio.this.isHighAmp) {
                        Log.i(xpAudio.TAG, "restart set AMP unMute!!");
                        xpAudio.this.mXpVehicle.setAmpMute(0);
                        AudioSystem.setParameters("vehicle_model=2");
                    } else {
                        AudioSystem.setParameters("vehicle_model=1");
                    }
                    if (!SystemProperties.get("persist.audio.xpeffect.mode", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS).equals(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS)) {
                        if (xpAudio.this.mXpAudioEffect != null) {
                            xpAudio.this.mXpAudioEffect.setXpAudioEffectMode(xpAudio.this.mXpAudioEffect.getXpAudioEffectMode());
                        }
                        if (xpAudio.AUDIO_EFFECT_FORM == 1) {
                            int mode = xpAudio.this.mXpAudioEffect.getXpAudioEffectMode();
                            int type = xpAudio.this.getSoundEffectType(mode);
                            if ((mode == 1 && type == 7) || ((mode == 3 && type == 5) || xpAudio.this.isHighAmp)) {
                                xpAudio.this.setXpCustomizeEffect();
                                return;
                            }
                            return;
                        }
                        return;
                    }
                    Log.i(xpAudio.TAG, "AudioEffect first setting");
                    if (xpAudio.this.mXpAudioEffect != null) {
                        xpAudio.this.mXpAudioEffect.setXpAudioEffectMode(1);
                    }
                    xpAudio.this.setSoundField(4, XpAudioPolicy.DEFAULT_SOUNDFIELD_X, XpAudioPolicy.DEFAULT_SOUNDFIELD_Y);
                    if (xpAudio.this.isHighAmp) {
                        xpAudio.this.setSoundField(3, XpAudioPolicy.DEFAULT_SOUNDFIELD_X, XpAudioPolicy.DEFAULT_SOUNDFIELD_Y);
                    }
                    xpAudio.this.setSoundField(1, XpAudioPolicy.DEFAULT_SOUNDFIELD_X, XpAudioPolicy.DEFAULT_SOUNDFIELD_Y);
                    if (xpAudio.this.mXpAudioPolicy != null) {
                        xpAudio.this.mXpAudioPolicy.setSoundEffectType(1, 1);
                    }
                    xpAudio.this.setSoundEffectScene(1, 4);
                    if (xpAudio.this.isHighAmp) {
                        xpAudio.this.setXpCustomizeEffect();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread(TAG);
        }
        this.mHandlerThread.start();
        this.mHandler = new xpAudioHandler(this.mHandlerThread.getLooper());
        this.mXpAudioPolicy.setContext(this.mContext, this);
        this.mIsMusicLimitmode = SystemProperties.getBoolean(PROP_VOLUME_LIMITEDMODE, false);
        new Thread(new Runnable() { // from class: com.android.xpeng.audio.xpAudio.4
            /* JADX WARN: Can't wrap try/catch for region: R(23:2|3|4|(2:5|6)|(18:11|12|(13:17|18|19|(9:62|(3:65|(1:67)|68)|32|(6:46|(3:51|(1:53)|54)|42|43|44|45)(3:37|(1:39)|40)|41|42|43|44|45)(3:22|(5:24|25|26|27|29)(2:59|60)|30)|31|32|(1:34)|46|(5:48|51|(0)|54|41)|42|43|44|45)|73|74|18|19|(1:61)(1:69)|62|(4:65|(0)|68|31)|32|(0)|46|(0)|42|43|44|45)|75|12|(15:14|17|18|19|(0)(0)|62|(0)|32|(0)|46|(0)|42|43|44|45)|73|74|18|19|(0)(0)|62|(0)|32|(0)|46|(0)|42|43|44|45) */
            /* JADX WARN: Code restructure failed: missing block: B:64:0x013b, code lost:
                r0 = e;
             */
            /* JADX WARN: Removed duplicated region for block: B:25:0x00a5 A[ADDED_TO_REGION] */
            /* JADX WARN: Removed duplicated region for block: B:36:0x00cb A[ADDED_TO_REGION] */
            /* JADX WARN: Removed duplicated region for block: B:39:0x00d5 A[Catch: Exception -> 0x0137, TryCatch #2 {Exception -> 0x0137, blocks: (B:30:0x00b8, B:41:0x00e1, B:43:0x00ea, B:46:0x00f4, B:48:0x00fc, B:60:0x012d, B:51:0x0108, B:53:0x0110, B:56:0x011a, B:58:0x0122, B:34:0x00c1, B:37:0x00cd, B:39:0x00d5), top: B:72:0x00b8 }] */
            /* JADX WARN: Removed duplicated region for block: B:43:0x00ea A[Catch: Exception -> 0x0137, TryCatch #2 {Exception -> 0x0137, blocks: (B:30:0x00b8, B:41:0x00e1, B:43:0x00ea, B:46:0x00f4, B:48:0x00fc, B:60:0x012d, B:51:0x0108, B:53:0x0110, B:56:0x011a, B:58:0x0122, B:34:0x00c1, B:37:0x00cd, B:39:0x00d5), top: B:72:0x00b8 }] */
            /* JADX WARN: Removed duplicated region for block: B:53:0x0110 A[Catch: Exception -> 0x0137, TryCatch #2 {Exception -> 0x0137, blocks: (B:30:0x00b8, B:41:0x00e1, B:43:0x00ea, B:46:0x00f4, B:48:0x00fc, B:60:0x012d, B:51:0x0108, B:53:0x0110, B:56:0x011a, B:58:0x0122, B:34:0x00c1, B:37:0x00cd, B:39:0x00d5), top: B:72:0x00b8 }] */
            /* JADX WARN: Removed duplicated region for block: B:58:0x0122 A[Catch: Exception -> 0x0137, TryCatch #2 {Exception -> 0x0137, blocks: (B:30:0x00b8, B:41:0x00e1, B:43:0x00ea, B:46:0x00f4, B:48:0x00fc, B:60:0x012d, B:51:0x0108, B:53:0x0110, B:56:0x011a, B:58:0x0122, B:34:0x00c1, B:37:0x00cd, B:39:0x00d5), top: B:72:0x00b8 }] */
            /* JADX WARN: Removed duplicated region for block: B:76:0x00c1 A[ADDED_TO_REGION, SYNTHETIC] */
            @Override // java.lang.Runnable
            /*
                Code decompiled incorrectly, please refer to instructions dump.
                To view partially-correct add '--show-bad-code' argument
            */
            public void run() {
                /*
                    Method dump skipped, instructions count: 325
                    To view this dump add '--comments-level debug' option
                */
                throw new UnsupportedOperationException("Method not decompiled: com.android.xpeng.audio.xpAudio.AnonymousClass4.run():void");
            }
        }).start();
        this.mReceiver = new BroadcastReceiver() { // from class: com.android.xpeng.audio.xpAudio.5
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if (intent != null) {
                    String action = intent.getAction();
                    Log.i(xpAudio.TAG, "BTService receiver action == " + action);
                    if (!"android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED".equals(action)) {
                        if (xpAudio.ACTION_POWER_STATE_CHANGED.equals(action)) {
                            int igState = intent.getIntExtra(xpAudio.EXTRA_IG_STATE, -1);
                            if (igState == 0) {
                                xpAudio.this.igOffResetFlag();
                                return;
                            }
                            return;
                        }
                        return;
                    }
                    BluetoothHeadsetClientCall mCall = (BluetoothHeadsetClientCall) intent.getExtra("android.bluetooth.headsetclient.extra.CALL", null);
                    if (mCall != null) {
                        int callState = mCall.getState();
                        Log.d(xpAudio.TAG, "when call status changes: callState " + callState + " number == " + mCall.getNumber());
                        if (callState == 4) {
                            if (xpAudio.this.mXpAudioPolicy != null) {
                                xpAudio.this.mXpAudioPolicy.setCallingIn(true);
                            }
                        } else if (callState == 2) {
                            if (xpAudio.this.mXpAudioPolicy != null) {
                                xpAudio.this.mXpAudioPolicy.setCallingOut(true);
                            }
                        } else if (callState != 0 && callState == 7 && xpAudio.this.mXpAudioPolicy != null) {
                            xpAudio.this.mXpAudioPolicy.setCallingOut(false);
                            xpAudio.this.mXpAudioPolicy.setCallingIn(false);
                        }
                    }
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED");
        intentFilter.addAction(ACTION_POWER_STATE_CHANGED);
        this.mContext.registerReceiver(this.mReceiver, intentFilter);
        registerReceiver();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void triggerNativeSession(int startSessionid, int stopSessionid, String startPkgName, String stopPkgName, long startTime, long stopTime) {
        if (startSessionid >= 0 || stopSessionid >= 0) {
            Log.d(TAG, "triggerNativeSession " + startPkgName + " " + startSessionid + " " + startTime + " " + stopPkgName + " " + stopSessionid + " " + stopTime);
        }
        if (stopTime > startTime) {
            if (startSessionid >= 0) {
                triggerNativePlay(startSessionid, startPkgName);
            }
            if (stopSessionid >= 0) {
                triggerNativeStop(stopSessionid, stopPkgName);
                return;
            }
            return;
        }
        if (stopSessionid >= 0) {
            triggerNativeStop(stopSessionid, stopPkgName);
        }
        if (startSessionid >= 0) {
            triggerNativePlay(startSessionid, startPkgName);
        }
    }

    private void triggerNativePlay(int Sessionid, String pkgName) {
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.applyUsage(1, 0, pkgName);
            SystemProperties.set("void.mediaaudio.play", "-2");
            setRingtoneSessionId(3, Sessionid, pkgName);
            if (!checkIsRingtoneBanSession(Sessionid)) {
                startAudioCapture(Sessionid, 1, pkgName);
            }
        }
    }

    private void triggerNativeStop(int Sessionid, String pkgName) {
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.releaseUsage(1, 0, pkgName);
            SystemProperties.set("void.mediaaudio.stop", "-2");
            if (!checkIsRingtoneBanSession(Sessionid)) {
                stopAudioCapture(Sessionid, 1, pkgName);
            }
            removeRingtoneBanSession(Sessionid);
        }
    }

    private void registerReceiver() {
        Log.i(TAG, "registerReceiver");
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction("android.intent.action.BOOT_COMPLETED");
        mIntentFilter.addAction("android.intent.action.ACTION_SHUTDOWN");
        this.mContext.registerReceiver(this.mBootReceiver, mIntentFilter);
    }

    public void unregisterReceiver() {
        Log.i(TAG, "unregisterReceiver.");
        this.mContext.unregisterReceiver(this.mBootReceiver);
    }

    /* loaded from: classes.dex */
    class CarServiceDeathRecipient implements IBinder.DeathRecipient {
        private static final String TAG = "xpAudio.Binder";
        private IBinder mrBinder;

        CarServiceDeathRecipient(IBinder binder) {
            this.mrBinder = binder;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.d(TAG, "binderDied " + this.mrBinder);
            xpAudio.this.BindCarService();
        }

        void release() {
            this.mrBinder.unlinkToDeath(this, 0);
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.xpeng.audio.xpAudio$7] */
    private void startExternalServices() {
        new Thread() { // from class: com.android.xpeng.audio.xpAudio.7
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                try {
                    Log.d(xpAudio.TAG, "startExternalServices");
                    Thread.sleep(5000L);
                    if (xpAudio.this.mCarService == null) {
                        xpAudio.this.BindCarService();
                    }
                    Log.d(xpAudio.TAG, "startExternalServices BindXUIService()");
                    if (xpAudio.this.mXUIService == null) {
                        xpAudio.this.BindXUIService();
                    }
                } catch (Exception e) {
                }
            }
        }.start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void BindCarService() {
        if (this.mCarService == null) {
            Intent intent = new Intent();
            intent.setPackage(CAR_SERVICE_PACKAGE);
            intent.setAction(CAR_SERVICE_INTERFACE_NAME);
            this.mContext.bindService(intent, this.mCarServiceConnectionListener, 1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void BindXUIService() {
        long currentTime = System.currentTimeMillis();
        Log.i(TAG, "BindXUIService() ");
        if (Math.abs(currentTime - lastBindXuiServiceTime) > 5000) {
            Intent intent = new Intent();
            intent.setPackage(XUI_SERVICE_PACKAGE);
            intent.setAction(XUI_SERVICE_INTERFACE_NAME);
            this.mContext.bindService(intent, this.mMediaServiceConnectionListener, 1);
        }
    }

    public IXpVehicle getXpVehicle() {
        return this.mXpVehicle;
    }

    private void broadcastVolumeToICM(int streamType, int volume, boolean fromadj) throws RemoteException {
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
                            this.mXpVehicle.setMeterSoundState(type, getStreamVolume(streamType), 1);
                        } else if (volume >= 0 && volume <= 30) {
                            this.mXpVehicle.setMeterSoundState(type, volume, 0);
                        }
                    }
                } else if (fromadj) {
                    JSONObject volumeObj = new JSONObject();
                    volumeObj.put("OSDMode", volType);
                    volumeObj.put("OSDLastValue", getStreamVolume(streamType));
                    volumeObj.put("OSDValue", volume);
                    this.mXpVehicle.setIcmOsdShow(volumeObj.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    private int getGroupIdByStreamType(int streamType) {
        if (streamType < 0 || streamType > 10) {
            return -1;
        }
        return this.StreamGroupIdMap.get(Integer.valueOf(streamType)).intValue();
    }

    public void adjustStreamVolume(int streamType, int direction, int flags, String packageName) {
        synchronized (mFadeBLock) {
            this.VolumeFadeBreakFlag[streamType] = true;
        }
        if (flags == 268599296) {
            if (this.mXpAudioPolicy != null) {
                this.mXpAudioPolicy.igOnResetFlags();
            }
            this.XiaoPengMusicSessionMap.clear();
            igOnRestFlags();
        }
        long currentTime = System.currentTimeMillis();
        if ((direction == 1 || direction == -1) && Math.abs(currentTime - lastAdjustTime) < adjustInterval) {
            Log.w(TAG, "adjustStreamVolume too fast ,  return  timelogs:" + currentTime + "," + lastAdjustTime + "," + Math.abs(currentTime - lastAdjustTime) + "," + adjustInterval);
            return;
        }
        lastAdjustTime = currentTime;
        if (this.mHandler != null) {
            StreamVolumeClass mStreamVolumeClass = new StreamVolumeClass();
            mStreamVolumeClass.flag = flags;
            mStreamVolumeClass.packageName = packageName;
            mStreamVolumeClass.streamType = streamType;
            mStreamVolumeClass.direction = direction;
            Message m = this.mHandler.obtainMessage(4, streamType, direction, mStreamVolumeClass);
            this.mHandler.sendMessage(m);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class StreamVolumeClass {
        int direction;
        int flag;
        String packageName;
        int streamType;

        private StreamVolumeClass() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void xpadjustStreamVolume(int streamType, int direction, int flags, String packageName) {
        boolean isAdjustFlag;
        int oldVol;
        int newVol;
        boolean SendToIcm_e28;
        int index;
        int newVol2;
        int maxVol;
        boolean SendToIcm_e282;
        int index2;
        int flags2 = flags;
        try {
            Log.i(TAG, "adjustStreamVolume: streamType=" + streamType + " direction=" + direction + " flags:" + flags2 + " packageName:" + packageName);
            if (this.mCarAudio != null) {
                int groupId = getGroupIdByStreamType(streamType);
                if (groupId < 0) {
                    Log.e(TAG, "adjustStreamVolume error. streamType=" + streamType);
                    return;
                }
                boolean MuteFlag = false;
                boolean isAdjustFlag2 = false;
                boolean fakeflag = false;
                boolean broadcastMuteFlag = false;
                boolean SendToIcm_e283 = false;
                if (packageName.equals("android")) {
                    SendToIcm_e283 = true;
                    isAdjustFlag2 = true;
                }
                int maxVol2 = this.mGroupMaxVolume[groupId];
                int minVol = this.mGroupMinVolume[groupId];
                int oldVol2 = currCarVolume[groupId];
                int index3 = -1;
                if (direction == -100) {
                    isAdjustFlag = isAdjustFlag2;
                    boolean SendToIcm_e284 = SendToIcm_e283;
                    oldVol = oldVol2;
                    newVol = 0;
                    if ((flags2 & Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                        this.fakeMuteFlag = true;
                        fakeflag = true;
                        flags2 = Integer.MAX_VALUE & flags2;
                    }
                    int volumeTypeByStreamType = this.mXpAudioPolicy.getVolumeTypeByStreamType(streamType);
                    XpAudioPolicy xpAudioPolicy = this.mXpAudioPolicy;
                    if (volumeTypeByStreamType == 3) {
                        Log.d(TAG, "adjustSteamVolume  please do not mute system volume. streamType:" + streamType);
                        return;
                    }
                    if (this.mXpVolumePolicy != null && !fakeflag) {
                        this.mXpVolumePolicy.saveStreamMute(streamType, true);
                    }
                    SendToIcm_e28 = SendToIcm_e284;
                    index3 = -1;
                } else if (direction == -1 || direction == 1) {
                    boolean SendToIcm_e285 = SendToIcm_e283;
                    int index4 = -1;
                    if (this.mXpVolumePolicy != null) {
                        int index5 = this.mXpVolumePolicy.getStreamVolume(streamType);
                        int index6 = index5 + direction;
                        newVol2 = Float.valueOf(index6 * this.mRatios[groupId]).intValue();
                        index4 = index6;
                    } else {
                        newVol2 = currCarVolume[groupId] + ((int) (direction * this.mRatios[groupId]));
                    }
                    boolean isMuted = getStreamMute(streamType);
                    boolean SendToIcm_e286 = (streamType == 3 && isMuted && newVol2 > 0) ? true : SendToIcm_e285;
                    if (!isMuted || newVol2 <= maxVol2) {
                        if (newVol2 < minVol) {
                            maxVol = maxVol2;
                        } else if (newVol2 > maxVol2) {
                            maxVol = maxVol2;
                        }
                        Log.d(TAG, "adjustStreamVolume out of range.");
                        if (1 != 0) {
                            int broadcastVolume = newVol2 < minVol ? minVol : maxVol;
                            broadcastVolumeToICM(streamType, Float.valueOf(Math.round(broadcastVolume / this.mRatios[groupId])).intValue(), SendToIcm_e286);
                            broadcastVolumeChangedToAll(streamType, Float.valueOf(Math.round(broadcastVolume / this.mRatios[groupId])).intValue(), Float.valueOf(Math.round(oldVol2 / this.mRatios[groupId])).intValue(), true, packageName, false);
                            return;
                        }
                        return;
                    }
                    newVol2 = maxVol2;
                    int newVol3 = newVol2;
                    int volumeTypeByStreamType2 = this.mXpAudioPolicy.getVolumeTypeByStreamType(streamType);
                    XpAudioPolicy xpAudioPolicy2 = this.mXpAudioPolicy;
                    if (volumeTypeByStreamType2 == 3 && Float.valueOf(Math.round(newVol3 / this.mRatios[groupId])).intValue() != XpAudioPolicy.DEFAULT_VOLUME_SYSTEM_CONFIGED) {
                        Log.d(TAG, "adjustSteamVolume  please do not change system volume. streamType:" + streamType + " index:" + Float.valueOf(Math.round(newVol3 / this.mRatios[groupId])).intValue());
                        return;
                    }
                    if (packageName.equals(XUI_SERVICE_PACKAGE2) || packageName.equals("com.android.systemui") || packageName.equals(settingsPkgName) || packageName.equals("android")) {
                        removeTempChangeVol(streamType);
                        removeTempVolChangeDataOfStreamType(streamType);
                    }
                    synchronized (carVolumeLock) {
                        currCarVolume[groupId] = newVol3;
                    }
                    if (streamType == 3 && (isOtherSessionOn() || isKaraokeOn())) {
                        broadcastMuteFlag = true;
                    }
                    if (this.mXpVolumePolicy != null) {
                        this.mXpVolumePolicy.saveStreamVolume(streamType, Float.valueOf(Math.round(newVol3 / this.mRatios[groupId])).intValue());
                    }
                    newVol = newVol3;
                    SendToIcm_e28 = SendToIcm_e286;
                    oldVol = oldVol2;
                    isAdjustFlag = true;
                    index3 = index4;
                } else {
                    switch (direction) {
                        case 100:
                            isAdjustFlag = isAdjustFlag2;
                            int newVol4 = currCarVolume[groupId];
                            boolean streamMute = false;
                            int newVol5 = flags2 & Integer.MIN_VALUE;
                            if (newVol5 == Integer.MIN_VALUE) {
                                this.fakeMuteFlag = false;
                                int flags3 = flags2 & Integer.MAX_VALUE;
                                try {
                                    streamMute = getStreamMute(streamType);
                                    fakeflag = true;
                                    SendToIcm_e282 = SendToIcm_e283;
                                    index2 = -1;
                                    flags2 = flags3;
                                } catch (Exception e) {
                                    e = e;
                                    Log.e(TAG, e.toString());
                                    return;
                                }
                            } else if ((flags2 & 1073741824) == 1073741824 && this.mXpVolumePolicy.getStreamVolume(streamType) == 0) {
                                Log.d(TAG, "FLAG_UNMUTE_RESET_DEFALUT_VOL set streamTyep:" + streamType + " vol to default");
                                if (this.mHandler != null) {
                                    Message m = this.mHandler.obtainMessage(3, streamType, 15, 1);
                                    this.mHandler.sendMessage(m);
                                    return;
                                }
                                return;
                            } else {
                                SendToIcm_e282 = SendToIcm_e283;
                                index2 = -1;
                            }
                            if (streamType == 3 && (isOtherSessionOn() || isKaraokeOn())) {
                                broadcastMuteFlag = true;
                            }
                            SendToIcm_e283 = (streamType == 3 && getStreamMute(streamType) && packageName.equals("android")) ? true : SendToIcm_e282;
                            if (this.mXpVolumePolicy != null && !fakeflag) {
                                this.mXpVolumePolicy.saveStreamMute(streamType, streamMute);
                            }
                            MuteFlag = getStreamMute(streamType);
                            if (!this.fakeMuteFlag || !MuteFlag) {
                                index3 = this.mXpVolumePolicy.getStreamVolume(streamType);
                                oldVol = oldVol2;
                                newVol = newVol4;
                                break;
                            } else {
                                Log.d(TAG, "fakeMute unmuted  but type:" + streamType + " is still muted");
                                newVol = 0;
                                oldVol = oldVol2;
                                index3 = index2;
                                break;
                            }
                            break;
                        case 101:
                            isAdjustFlag = isAdjustFlag2;
                            if (this.mXpVolumePolicy != null) {
                                int volumeTypeByStreamType3 = this.mXpAudioPolicy.getVolumeTypeByStreamType(streamType);
                                XpAudioPolicy xpAudioPolicy3 = this.mXpAudioPolicy;
                                if (volumeTypeByStreamType3 != 3) {
                                    MuteFlag = this.mXpVolumePolicy.getStreamMute(streamType);
                                    if (streamType == 3 && MuteFlag) {
                                        SendToIcm_e283 = true;
                                    }
                                    this.mXpVolumePolicy.saveStreamMute(streamType, !MuteFlag);
                                    if (MuteFlag) {
                                        index3 = this.mXpVolumePolicy.getStreamVolume(streamType);
                                        int newVol6 = index3 >= 0 ? Float.valueOf(Math.round(index3 * this.mRatios[groupId])).intValue() : 0;
                                        newVol = newVol6;
                                        MuteFlag = false;
                                    } else {
                                        newVol = 0;
                                    }
                                    oldVol = oldVol2;
                                    break;
                                } else {
                                    Log.d(TAG, "adjustSteamVolume  please do not mute system volume. streamType:" + streamType);
                                    return;
                                }
                            } else {
                                return;
                            }
                        default:
                            Log.d(TAG, "adjustStreamVolume unkown direction=" + direction);
                            return;
                    }
                    SendToIcm_e28 = SendToIcm_e283;
                }
                Log.d(TAG, "adjustStreamVolume: newGroupVol=" + newVol + " groupId:" + groupId + " flags:" + flags2);
                if (((!this.fakeMuteFlag && !MuteFlag) || newVol == 0) && (!this.mValidVolumeMode[streamType] || flags2 == 8)) {
                    if (direction != -100 && index3 <= 30 && index3 >= 0) {
                        broadcastVolumeToICM(streamType, index3, SendToIcm_e28);
                    }
                    setVolumeToGroup(streamType, groupId, newVol, flags2);
                }
                if (direction == -100 || (direction == 101 && this.mXpVolumePolicy.getStreamMute(streamType))) {
                    Log.e(TAG, "AdjustVolume -> mute, do not change volume");
                    newVol = currCarVolume[groupId];
                    broadcastVolumeToICM(streamType, 255, SendToIcm_e28);
                }
                if (this.mXpVolumePolicy != null) {
                    int index7 = this.mXpVolumePolicy.getStreamVolume(streamType);
                    index = index7;
                } else {
                    index = index3;
                }
                boolean SendToIcm_e287 = broadcastMuteFlag;
                broadcastVolumeChangedToAll(streamType, index, Float.valueOf(Math.round(oldVol / this.mRatios[groupId])).intValue(), isAdjustFlag, packageName, SendToIcm_e287);
                Log.i(TAG, "adjustStreamVolume: newGroupVol=" + newVol);
            }
        } catch (Exception e2) {
            e = e2;
        }
    }

    private void resetCurrentGroupVol(int streamtype) {
        try {
            if (this.mXpVolumePolicy != null) {
                int groupId = getGroupIdByStreamType(streamtype);
                currCarVolume[groupId] = Float.valueOf(Math.round(this.mXpVolumePolicy.getStreamVolume(streamtype) * this.mRatios[groupId])).intValue();
            }
        } catch (Exception e) {
            Log.e(TAG, "resetCurrentGroupVol");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetAllVolume() {
        if (this.mXpVolumePolicy != null) {
            doSetStreamVolume(3);
            resetCurrentGroupVol(3);
            int volume = this.mXpVolumePolicy.getStreamVolume(3);
            boolean broadcastMuteFlag = false;
            broadcastMuteFlag = (isOtherSessionOn() || isKaraokeOn()) ? true : true;
            broadcastVolumeChangedToAll(3, volume, volume, false, this.AUDIO_SYSTEM_PACKAGENAME, broadcastMuteFlag);
            doSetStreamVolume(0);
            resetCurrentGroupVol(0);
            doSetStreamVolume(9);
            resetCurrentGroupVol(9);
            int volume2 = this.mXpVolumePolicy.getStreamVolume(9);
            broadcastVolumeChangedToAll(9, volume2, volume2, false, this.AUDIO_SYSTEM_PACKAGENAME, false);
            setStreamVolume(1, this.mXpVolumePolicy.getStreamVolume(1), 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        }
        DangerousTtsStatus = 0;
    }

    public void setStreamVolume(int streamType, int index, int flags, String packageName) {
        synchronized (mFadeBLock) {
            this.VolumeFadeBreakFlag[streamType] = true;
            if (flags == 4 && XUI_SERVICE_PACKAGE.equals(packageName)) {
                packageName = XUI_SERVICE_PACKAGE2;
            }
            setStreamVolume(streamType, index, flags, false, true, packageName);
        }
    }

    public void setStreamVolume(int streamType) {
        if (streamType == 3 || streamType == 9) {
            return;
        }
        doSetStreamVolume(streamType);
    }

    private void doSetStreamVolume(int streamType) {
        if (this.mXpVolumePolicy == null) {
            return;
        }
        boolean muteflag = this.mXpVolumePolicy.getStreamMute(streamType);
        int volumeType = this.mXpAudioPolicy.getVolumeTypeByStreamType(streamType);
        if ((this.fakeMuteFlag || muteflag) && (volumeType == 0 || volumeType == 2)) {
            Log.d(TAG, "streamType:" + streamType + " volume can not be set for fakeMuteFlag:" + this.fakeMuteFlag + "  muteflag:" + muteflag);
        } else if (3 == volumeType) {
        } else {
            Log.d(TAG, "setStreamVolume xui streamType=" + streamType);
            int index = this.mXpVolumePolicy.getStreamVolume(streamType);
            setStreamVolume(streamType, index, 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:60:0x0193  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void setStreamVolume(int r22, int r23, int r24, boolean r25, boolean r26, java.lang.String r27) {
        /*
            Method dump skipped, instructions count: 567
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.xpeng.audio.xpAudio.setStreamVolume(int, int, int, boolean, boolean, java.lang.String):void");
    }

    /* loaded from: classes.dex */
    private class changeVolParam {
        boolean ignoremute;
        String packageName;
        boolean resetback;
        int streamtype;
        int volume;

        private changeVolParam() {
        }
    }

    /* JADX WARN: Type inference failed for: r1v1, types: [com.android.xpeng.audio.xpAudio$8] */
    public void changeStramVolByPolicy(final int streamType, final int index, boolean ignoremute, boolean resetback) {
        if (this.mHandler != null) {
            final changeVolParam param = new changeVolParam();
            param.ignoremute = ignoremute;
            param.resetback = resetback;
            new Thread() { // from class: com.android.xpeng.audio.xpAudio.8
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    try {
                        if (streamType == 3 && index == 1) {
                            Thread.sleep(150L);
                        }
                        Message m = xpAudio.this.mHandler.obtainMessage(5, streamType, index, param);
                        xpAudio.this.mHandler.sendMessage(m);
                    } catch (Exception e) {
                        Log.e("Exception", e.toString());
                    }
                }
            }.start();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void xpChangeStreamVol(int streamType, int index, boolean ignoremute, boolean resetback) {
        int groupId;
        try {
            Log.i(TAG, "xpChangeStreamVol streamType:" + streamType + " index:" + index + " resetback:" + resetback);
            boolean muteflag = this.mXpVolumePolicy.getStreamMute(streamType);
            if (muteflag && !ignoremute) {
                Log.w(TAG, "xpChangeStreamVol streamType:" + streamType + " is muted!!!");
            } else if (resetback && !muteflag) {
                if (this.mXpVolumePolicy == null) {
                    return;
                }
                Log.d(TAG, "xpChangeStreamVol resetback  streamType:" + streamType);
                this.mXpAudioPolicy.getVolumeTypeByStreamType(streamType);
                int index2 = this.mXpVolumePolicy.getStreamVolume(streamType);
                if (this.mHandler != null) {
                    Message m = this.mHandler.obtainMessage(3, streamType, index2, 0);
                    this.mHandler.sendMessage(m);
                }
            } else {
                if (resetback && muteflag) {
                    index = 0;
                }
                if (this.mCarAudio == null || (groupId = getGroupIdByStreamType(streamType)) < 0) {
                    return;
                }
                int maxVol = this.mGroupMaxVolume[groupId];
                int minVol = this.mGroupMinVolume[groupId];
                int newVol = Float.valueOf(Math.round(index * this.mRatios[groupId])).intValue();
                if (newVol >= minVol && newVol <= maxVol) {
                    setGroupVolume(streamType, groupId, newVol, 0);
                    Log.i(TAG, "xpChangeStreamVol newGroupVol=" + newVol + ", streamType=" + streamType + " isActive:" + this.mXpAudioPolicy.isStreamActive(streamType));
                    return;
                }
                Log.w(TAG, "xpChangeStreamVol out of range. streamType=" + streamType);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    private void sendMusicVolumeToAmp(int streamType, int groupId, int newVol) {
        if (streamType == 3 && this.isHighAmp && this.mRatios[groupId] != 0.0f) {
            int index = Float.valueOf(Math.round(newVol / this.mRatios[groupId])).intValue();
            if (index < 0 || index > 30) {
                Log.w(TAG, "sendMusicVolumeToAmp  index=" + index + "  out of range");
                return;
            }
            Log.i(TAG, "sendMusicVolumeToAmp  index=" + index);
            if (this.mXpVehicle != null) {
                try {
                    this.mXpVehicle.sendCduVolumeToAmp(index);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
            }
        }
    }

    private void setVolumeToGroup(int streamType, int groupId, int volume, int flags) {
        if (this.mCarAudio != null) {
            if (!isBtCallConnectOrCallout() || ((isBtCallConnectOrCallout() && this.btcallMode == 0) || ((isBtCallConnectOrCallout() && (streamType == 6 || streamType == 3)) || ((this.fakeMuteFlag && streamType == 10) || (streamType == 9 && getDangerousTtsStatus() == 1))))) {
                setGroupVolume(streamType, groupId, volume, flags);
                return;
            }
            Log.w(TAG, "setVolumeToGroup streamType=" + streamType + " FAILED: isInBtCall" + isBtCallConnectOrCallout() + " fakeMuteFlag:" + this.fakeMuteFlag);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setGroupVolume(int streamType, int groupId, int volume, int flags) {
        int newVol_set = volume;
        if (streamType == 3 && this.mIsMusicLimitmode) {
            newVol_set /= 2;
        }
        Log.i(TAG, "setGroupVolume: newGroupVol=" + newVol_set + " groupId:" + groupId + " flags:" + flags);
        if (streamType == 9 && DangerousTtsStatus != 0 && this.mXpAudioPolicy != null) {
            int index = this.mXpAudioPolicy.getDangerousTtsVolume();
            int dangerousvol = Float.valueOf(Math.round(index * this.mRatios[groupId])).intValue();
            if (volume != dangerousvol) {
                Log.w(TAG, "in dangerous tts mode, newVol_set should not be set!!  dangerousttsvol =" + index);
                if (this.mCarAudio != null) {
                    try {
                        this.mCarAudio.setGroupVolume(groupId, dangerousvol, 0);
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, e.toString());
                        return;
                    }
                }
                return;
            }
        }
        if (this.mCarAudio != null) {
            try {
                this.mCarAudio.setGroupVolume(groupId, newVol_set, flags);
                sendMusicVolumeToAmp(streamType, groupId, newVol_set);
            } catch (Exception e2) {
                Log.e(TAG, e2.toString());
            }
        }
    }

    public boolean checkStreamActive(int streamType) {
        if (this.mXpAudioPolicy != null) {
            return this.mXpAudioPolicy.isStreamActive(streamType);
        }
        return false;
    }

    public boolean isAnyStreamActive() {
        if (isBtCallOn()) {
            Log.d(TAG, "isAnyStreamActive BtCall in");
            return true;
        } else if (this.mXpAudioPolicy != null) {
            return this.mXpAudioPolicy.isAnyStreamActive();
        } else {
            return false;
        }
    }

    public int checkStreamCanPlay(int streamType) {
        if (this.mXpAudioPolicy != null) {
            return this.mXpAudioPolicy.checkStreamCanPlay(streamType);
        }
        return 2;
    }

    public int getStreamVolume(int streamType) {
        int groupId;
        if (this.mXpVolumePolicy != null) {
            return this.mXpVolumePolicy.getStreamVolume(streamType);
        }
        try {
            if (this.mCarAudio == null || (groupId = getGroupIdByStreamType(streamType)) < 0) {
                return -1;
            }
            int groupVol = this.mCarAudio.getGroupVolume(groupId);
            synchronized (carVolumeLock) {
                currCarVolume[groupId] = groupVol;
            }
            Log.d(TAG, "getStreamVolume: groupVol=" + groupVol);
            return Float.valueOf(Math.round(groupVol / this.mRatios[groupId])).intValue();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
        return -1;
    }

    public boolean getStreamMute(int streamType) {
        if (this.mXpVolumePolicy != null) {
            return this.mXpVolumePolicy.getStreamMute(streamType);
        }
        return false;
    }

    public int getStreamMaxVolume(int streamType) {
        return 30;
    }

    public int getStreamMinVolume(int streamType) {
        int groupId;
        try {
            if (this.mCarAudio == null || (groupId = getGroupIdByStreamType(streamType)) < 0) {
                return -1;
            }
            int minVol = this.mGroupMinVolume[groupId];
            Log.d(TAG, "minGroupVol=" + minVol);
            return Float.valueOf(Math.round(minVol / this.mRatios[groupId])).intValue();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
        return -1;
    }

    public int getLastAudibleStreamVolume(int streamType) {
        int intValue;
        if (this.mXpVolumePolicy != null) {
            return this.mXpVolumePolicy.getStreamVolume(streamType);
        }
        if (this.mCarAudio != null) {
            try {
                int groupId = getGroupIdByStreamType(streamType);
                if (groupId < 0) {
                    return -1;
                }
                synchronized (carVolumeLock) {
                    intValue = Float.valueOf(Math.round(currCarVolume[groupId] / this.mRatios[groupId])).intValue();
                }
                return intValue;
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
        return -1;
    }

    private boolean isSessionIdActive(int audioSession) {
        return this.XiaoPengMusicSessionMap.containsKey(Integer.valueOf(audioSession));
    }

    private void addSessionMap(int audioSession, String pkgName) {
        synchronized (SessionMapLock) {
            if (!isSessionIdActive(audioSession) && !pkgName.contains("com.xiaopeng.musicradio") && !pkgName.contains("android.uid.system:1000") && !pkgName.contains(settingsPkgName) && !pkgName.contains(BluetoothPkgName) && !pkgName.contains(ChaoPaoPkgName)) {
                Log.d(TAG, "addSessionMap " + audioSession + " pkgName:" + pkgName);
                this.XiaoPengMusicSessionMap.put(Integer.valueOf(audioSession), pkgName);
            }
        }
    }

    private void removeSessionMap(int audioSession, String pkgName) {
        synchronized (SessionMapLock) {
            if (isSessionIdActive(audioSession) && !pkgName.contains("com.xiaopeng.musicradio") && !pkgName.contains("android.uid.system:1000") && !pkgName.contains(settingsPkgName) && !pkgName.contains(BluetoothPkgName) && !pkgName.contains(ChaoPaoPkgName)) {
                Log.d(TAG, "removeSessionMap " + audioSession + " pkgName:" + pkgName);
                this.XiaoPengMusicSessionMap.remove(Integer.valueOf(audioSession));
            }
        }
    }

    public boolean isOtherSessionOn() {
        synchronized (SessionMapLock) {
            Log.i(TAG, "isOtherSessionOn " + this.XiaoPengMusicSessionMap.size());
            if (this.XiaoPengMusicSessionMap.size() == 0) {
                return false;
            }
            if (!AudioSystem.isStreamActive(3, 0)) {
                Log.w(TAG, "isOtherSessionOn no music running");
                this.XiaoPengMusicSessionMap.clear();
                return false;
            }
            for (int i = 0; i < this.XiaoPengMusicSessionMap.size(); i++) {
                for (Integer num : this.XiaoPengMusicSessionMap.keySet()) {
                    int key = num.intValue();
                    Log.d(TAG, "isXiaoPengSessionOn " + this.XiaoPengMusicSessionMap.get(Integer.valueOf(key)));
                }
            }
            return true;
        }
    }

    public List<String> getOtherMusicPlayingPkgs() {
        List<String> mList;
        synchronized (SessionMapLock) {
            mList = new ArrayList<>();
            for (int i = 0; i < this.XiaoPengMusicSessionMap.size(); i++) {
                for (Integer num : this.XiaoPengMusicSessionMap.keySet()) {
                    int key = num.intValue();
                    String tmpPkgName = this.XiaoPengMusicSessionMap.get(Integer.valueOf(key));
                    if (!checkNameExistInList(mList, tmpPkgName)) {
                        mList.add(tmpPkgName);
                    } else {
                        Log.d(TAG, "getOtherMusicPlayingPkgs tmpPkgName:" + tmpPkgName + " EXIST!! ignore size:" + mList.size());
                    }
                }
            }
        }
        return mList;
    }

    private boolean checkNameExistInList(List<String> mList, String name) {
        if (mList == null) {
            return false;
        }
        for (int i = 0; i < mList.size(); i++) {
            try {
                if (mList.get(i).equals(name)) {
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "checkNameExistInList " + e);
            }
        }
        return false;
    }

    public void startAudioCapture(int audioSession, int usage) {
        String pkgName = this.mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
        startAudioCapture(audioSession, usage, pkgName);
    }

    private void startAudioCapture(int audioSession, int usage, String pkgName) {
        Log.i(TAG, "startAudioCapture " + audioSession + " packageName:" + pkgName + " usage:" + usage);
        if (this.mMcService != null && this.mConnectionState == 2 && !checkIsRingtoneBanSession(audioSession)) {
            if (usage == 1) {
                try {
                    addSessionMap(audioSession, pkgName);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                    return;
                }
            }
            synchronized (this.mActiveSessionArray) {
                this.mActiveSessionArray.put(audioSession, new xpAudioSessionInfo(audioSession, usage, SystemClock.elapsedRealtime(), pkgName));
            }
            this.mMcService.vendorStartAudioSession(audioSession, usage, pkgName);
            return;
        }
        Log.d(TAG, "startAudioCapture   mediacenter not connected");
        BindXUIService();
    }

    public void stopAudioCapture(int audioSession, int usage) {
        String pkgName = this.mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
        stopAudioCapture(audioSession, usage, pkgName);
    }

    public void stopAudioCapture(int audioSession, int usage, String pkgName) {
        Log.i(TAG, "stopAudioCapture  " + audioSession + "  packageName:" + pkgName + " usage:" + usage);
        if (this.mMcService != null && this.mConnectionState == 2 && !checkIsRingtoneBanSession(audioSession)) {
            if (usage == 1) {
                try {
                    removeSessionMap(audioSession, pkgName);
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                    return;
                }
            }
            synchronized (this.mActiveSessionArray) {
                this.mActiveSessionArray.remove(audioSession);
            }
            this.mMcService.vendorStopAudioSession(audioSession, pkgName);
            return;
        }
        Log.d(TAG, "stopAudioCapture   mediacenter not connected");
        BindXUIService();
    }

    public void playbackControl(int cmd, int param) {
        Log.d(TAG, "playbackControl  cmd=" + cmd + "  param=" + param);
        if (this.mMcService != null && this.mConnectionState == 2) {
            try {
                this.mMcService.playbackControl(0, cmd, param);
                return;
            } catch (Exception e) {
                Log.e(TAG, e.toString());
                return;
            }
        }
        Log.d(TAG, "playbackControl  cmd=" + cmd + "  param=" + param + " mediacenter not connected");
        BindXUIService();
    }

    public void startSpeechEffect(int audioSession) {
        try {
            if (this.mXpEffectSet != null) {
                this.mXpEffectSet.setEffect(true, audioSession);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    public void stopSpeechEffect(int audioSession) {
        try {
            if (this.mXpEffectSet != null) {
                this.mXpEffectSet.setEffect(false, audioSession);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    public void setSoundField(int mode, int xSound, int ySound) {
        Log.i(TAG, "setSoundField x=" + xSound + ", y=" + ySound + ", mode=" + mode);
        int ret = AudioSystem.setParameters("method=setSoundField;mode=" + mode + ";x=" + xSound + ";y=" + ySound + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundField ERROR");
        }
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.setSoundField(mode, xSound, ySound);
        }
    }

    public SoundField getSoundField(int mode) {
        int x = 0;
        int y = 0;
        String str = AudioSystem.getParameters("method=getSoundField;mode=" + mode + ";");
        Log.d(TAG, "getSoundField mode=" + mode + " " + str);
        String[] parms = str.split(";");
        for (String parm : parms) {
            if (parm.startsWith("x=")) {
                x = Integer.parseInt(parm.substring(2));
            } else if (parm.startsWith("y=")) {
                y = Integer.parseInt(parm.substring(2));
            }
        }
        return new SoundField(x, y);
    }

    public int getSoundEffectMode() {
        if (AUDIO_EFFECT_FORM == 0) {
            String tStr = AudioSystem.getParameters("method=getSoundEffectMode;");
            Log.d(TAG, "getSoundEffectMode " + tStr);
            String[] parms = tStr.split(";");
            for (String parm : parms) {
                if (parm.startsWith("mode=")) {
                    int mode = Integer.parseInt(parm.substring(5));
                    return mode;
                }
            }
            return -1;
        } else if (AUDIO_EFFECT_FORM == 1 && this.mXpAudioEffect != null) {
            return this.mXpAudioEffect.getXpAudioEffectMode();
        } else {
            return -1;
        }
    }

    public void setSoundEffectMode(int mode) {
        Log.i(TAG, "setSoundEffectMode mode=" + mode);
        if (AUDIO_EFFECT_FORM == 1) {
            if (this.mXpAudioPolicy != null) {
                this.mXpAudioPolicy.setSoundEffectMode(mode);
            }
            if (this.mXpAudioEffect != null) {
                this.mXpAudioEffect.setXpAudioEffectMode(mode);
            }
        } else if (AUDIO_EFFECT_FORM == 0) {
            setSoundEffectType(mode, getSoundEffectType(mode));
        }
        int ret = AudioSystem.setParameters("method=setSoundEffectMode;mode=" + mode + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectMode ERROR");
        }
        SoundField sf = getSoundField(mode);
        setSoundField(mode, sf.x, sf.y);
        setSoundEffectScene(mode, getSoundEffectScene(mode));
    }

    public void setSoundEffectType(int mode, int type) {
        Log.i(TAG, "setSoundEffectType mode=" + mode + " type=" + type);
        if (AUDIO_EFFECT_FORM != 0) {
            if (AUDIO_EFFECT_FORM == 1) {
                if (this.mXpAudioPolicy != null) {
                    this.mXpAudioPolicy.setSoundEffectType(mode, type);
                }
                if (this.mXpAudioEffect != null) {
                    this.mXpAudioEffect.setXpAudioEffect(mode, type, false);
                    return;
                }
                return;
            }
            return;
        }
        int ret = AudioSystem.setParameters("method=setSoundEffectType;mode=" + mode + ";type=" + type + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectType ERROR");
        }
    }

    public int getSoundEffectType(int mode) {
        if (AUDIO_EFFECT_FORM == 0) {
            String tStr = AudioSystem.getParameters("method=getSoundEffectType;mode=" + mode + ";");
            Log.d(TAG, "getSoundEffectType mode=" + mode + " " + tStr);
            String[] parms = tStr.split(";");
            for (String parm : parms) {
                if (parm.startsWith("type=")) {
                    int type = Integer.parseInt(parm.substring(5));
                    return type;
                }
            }
            return -1;
        } else if (AUDIO_EFFECT_FORM == 1 && this.mXpAudioEffect != null) {
            return this.mXpAudioEffect.getXpAudioEffectType(mode);
        } else {
            return -1;
        }
    }

    public void setXpCustomizeEffect(int type, int value) {
        saveXpCustomizeEffect(type, value);
        setXpCustomizeEffect();
    }

    public void flushXpCustomizeEffects(int[] values) {
        for (int i = 0; i < values.length; i++) {
            saveXpCustomizeEffect(i, values[i]);
        }
        setXpCustomizeEffect();
    }

    private void saveXpCustomizeEffect(int type, int value) {
        if (type >= 0 && type < 10) {
            ContentResolver contentResolver = this.mContext.getContentResolver();
            Settings.System.putInt(contentResolver, "XpCustomizeEffect" + type, value);
        }
    }

    public int getXpCustomizeEffect(int type) {
        if (type < 0 || type >= 10) {
            return 0;
        }
        ContentResolver contentResolver = this.mContext.getContentResolver();
        return Settings.System.getInt(contentResolver, "XpCustomizeEffect" + type, 0);
    }

    public void setNavVolDecreaseEnable(boolean enable) {
        if (this.mContext != null) {
            Settings.System.putInt(this.mContext.getContentResolver(), "decrease_volume_navigating", enable ? 1 : 0);
        }
    }

    public boolean getNavVolDecreaseEnable() {
        return this.mContext == null || Settings.System.getInt(this.mContext.getContentResolver(), "decrease_volume_navigating", 1) == 1;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setXpCustomizeEffect() {
        try {
            int[] data = new int[10];
            for (int i = 0; i < 10; i++) {
                data[i] = getXpCustomizeEffect(i);
            }
            AudioSystem.setParameters("method=setXpCustomizeEffect;value1=" + data[0] + ";value2=" + data[1] + ";value3=" + data[2] + ";value4=" + data[3] + ";value5=" + data[4] + ";value6=" + data[5] + ";value7=" + data[6] + ";value8=" + data[7] + ";value9=" + data[8] + ";value10=" + data[9] + ";");
        } catch (Exception e) {
            Log.e(TAG, "setXpCustomizeEffect ERROR:" + e);
        }
    }

    public void setSoundEffectScene(int mode, int scene) {
        Log.i(TAG, "setSoundEffectScene mode=" + mode + " scene=" + scene);
        int ret = AudioSystem.setParameters("method=setSoundEffectScene;mode=" + mode + ";scene=" + scene + ";");
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectScene ERROR");
        }
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.setSoundEffectScene(mode, scene);
        }
    }

    public int getSoundEffectScene(int mode) {
        String tStr = AudioSystem.getParameters("method=getSoundEffectScene;mode=" + mode + ";");
        Log.d(TAG, "getSoundEffectScene mode=" + mode + " " + tStr);
        String[] parms = tStr.split(";");
        for (String parm : parms) {
            if (parm.startsWith("scene=")) {
                int scene = Integer.parseInt(parm.substring(6));
                return scene;
            }
        }
        return -1;
    }

    public void setSoundEffectParms(int effectType, int nativeValue, int softValue, int innervationValue) {
        Log.i(TAG, "setSoundEffectParms effectType=" + effectType + ", native=" + nativeValue + ", soft=" + softValue + ", innervation=" + innervationValue);
        int ret = AudioSystem.setParameters("method=setSoundEffectParms;effect=" + effectType + ";native=" + nativeValue + ";soft=" + softValue + ";innervation=" + innervationValue);
        if (ret == -1) {
            Log.e(TAG, "setSoundEffectParms ERROR");
        }
    }

    public SoundEffectParms getSoundEffectParms(int effectType, int modeType) {
        int nativeValue = 0;
        int softValue = 0;
        int innervationValue = 0;
        String str = AudioSystem.getParameters("method=getSoundEffectParms;effect=" + effectType + ";mode=" + modeType);
        StringBuilder sb = new StringBuilder();
        sb.append("getSoundEffectParms ");
        sb.append(str);
        Log.d(TAG, sb.toString());
        String[] parms = str.split(";");
        for (String parm : parms) {
            if (parm.startsWith("native=")) {
                nativeValue = Integer.parseInt(parm.substring(7));
            } else if (parm.startsWith("soft=")) {
                softValue = Integer.parseInt(parm.substring(5));
            } else if (parm.startsWith("innervation=")) {
                innervationValue = Integer.parseInt(parm.substring(12));
            }
        }
        return new SoundEffectParms(nativeValue, softValue, innervationValue);
    }

    public void setBtCallOnFlag(int flag) {
        mBtCallFlag = flag;
        if (this.mXpVolumePolicy == null) {
            Log.e(TAG, "mXpVolumePolicy is null");
            return;
        }
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.setBtCallOnFlag(flag);
        }
        Log.i(TAG, "setBtCallOnFlag " + flag + " btcallMode:" + this.btcallMode);
        if ((flag == 2 || flag == 3) && this.btcallMode == 1) {
            setStreamVolume(6, getStreamVolume(6), 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        } else {
            setStreamVolume(2, getStreamVolume(2), 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        }
        if (flag == 0) {
            btforceFadeMusicVolume(false);
        } else {
            btforceFadeMusicVolume(true);
        }
    }

    public void setBtCallOn(boolean enable) {
        isInBtCall = enable;
        if (this.mXpVolumePolicy == null) {
            Log.e(TAG, "mXpVolumePolicy is null");
            return;
        }
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.setBtCallOn(enable);
        }
        Log.i(TAG, "setBtCallOn " + enable + " btcallMode:" + this.btcallMode);
        if (enable && this.btcallMode == 1) {
            setStreamVolume(6, getStreamVolume(6), 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        } else {
            setStreamVolume(2, getStreamVolume(2), 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        }
    }

    public int getBtCallOnFlag() {
        return mBtCallFlag;
    }

    public boolean isBtCallOn() {
        return mBtCallFlag == 2;
    }

    public boolean isBtCallConnectOrCallout() {
        return mBtCallFlag == 2 || mBtCallFlag == 3;
    }

    public void igOffResetFlag() {
        Log.i(TAG, "igOffResetFlag()");
        btforceFadeMusicVolume(false);
        clearTempVolChangeData();
        SystemProperties.set("audio.telephony.callstate", "0");
        if (isKaraokeOn()) {
            setKaraokeOn(false);
        }
        openKaraokeModeSysVolume(false);
    }

    private void igOnRestFlags() {
        this.btcallMode = 0;
        DangerousTtsStatus = 0;
        inForceMusicVolumeDown = false;
        if (isKaraokeOn()) {
            setKaraokeOn(false);
        }
        openKaraokeModeSysVolume(false);
    }

    public void setBtCallMode(int mode) {
        if (this.mXpVolumePolicy == null) {
            Log.e(TAG, "mXpVolumePolicy is null");
            return;
        }
        this.btcallMode = mode;
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.setBtCallMode(mode);
        }
        Log.i(TAG, "setBtCallMode " + mode + " " + isBtCallConnectOrCallout());
        if (mode == 1 && isBtCallConnectOrCallout()) {
            setStreamVolume(6, getStreamVolume(6), 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        } else {
            setStreamVolume(2, getStreamVolume(2), 0, false, false, this.AUDIO_SYSTEM_PACKAGENAME);
        }
        if (mBtCallFlag == 0) {
            btforceFadeMusicVolume(false);
        } else {
            btforceFadeMusicVolume(true);
        }
    }

    public int getBtCallMode() {
        return this.btcallMode;
    }

    public void setKaraokeOn(boolean on) {
        Log.i(TAG, "setKaraokeOn  " + on);
        isInKaraoke = on;
        String str = on ? "On" : "Off";
        AudioSystem.setParameters("Karaoke=" + str);
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.setKaraokeOn(on);
        }
        if (!on || (on && this.mXpAudioPolicy != null && this.mXpAudioPolicy.getAlarmIdSize() == 0 && this.mXpAudioPolicy.getSystemTypeCount() == 0)) {
            openKaraokeModeSysVolume(on);
        }
    }

    public boolean isKaraokeOn() {
        return isInKaraoke;
    }

    /* JADX WARN: Type inference failed for: r0v2, types: [com.android.xpeng.audio.xpAudio$9] */
    public void openKaraokeModeSysVolume(boolean enable) {
        if (KaraokeSysVolMode == enable) {
            Log.i(TAG, "openKaraokeModeSysVolume  already " + enable);
            return;
        }
        Log.i(TAG, "openKaraokeModeSysVolume  " + enable);
        KaraokeSysVolMode = enable;
        if (!enable) {
            try {
                sysVolFading = false;
                Thread.sleep(20L);
                setGroupVolume(1, 2, XpAudioPolicy.DEFAULT_VOLUME_SYSTEM_CONFIGED * 2, 0);
                return;
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }
        new Thread() { // from class: com.android.xpeng.audio.xpAudio.9
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                try {
                    boolean unused = xpAudio.sysVolFading = true;
                    int srcVol = XpAudioPolicy.DEFAULT_VOLUME_SYSTEM_CONFIGED * 2;
                    int dstVol = XpAudioPolicy.DEFAULT_VOLUME_KARAOKE * 2;
                    int lastSetVol = -1;
                    for (int stepSetVol = 1; stepSetVol < 10; stepSetVol++) {
                        if (xpAudio.sysVolFading) {
                            int stepSetVol2 = srcVol + ((Math.abs(dstVol - srcVol) * stepSetVol) / 10);
                            if (lastSetVol != stepSetVol2) {
                                xpAudio.this.setGroupVolume(1, 2, stepSetVol2, 0);
                                lastSetVol = stepSetVol2;
                                Thread.sleep(20L);
                            }
                        } else {
                            Log.w(xpAudio.TAG, "openKaraokeModeSysVolume  fade breaked by flag!!!");
                            return;
                        }
                    }
                    if (xpAudio.sysVolFading) {
                        xpAudio.this.setGroupVolume(1, 2, XpAudioPolicy.DEFAULT_VOLUME_KARAOKE * 2, 0);
                        boolean unused2 = xpAudio.sysVolFading = false;
                        return;
                    }
                    Log.w(xpAudio.TAG, "openKaraokeModeSysVolume  fade breaked by flag!!!");
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
            }
        }.start();
    }

    public boolean isFmOn() {
        if (this.mXpAudioPolicy != null) {
            return this.mXpAudioPolicy.isFmOn();
        }
        return false;
    }

    public void setVoiceStatus(int status) {
        if (this.mXpAudioPolicy != null) {
            this.mXpAudioPolicy.setVoiceStatus(status);
        }
    }

    public int getVoiceStatus() {
        if (this.mXpAudioPolicy != null) {
            return this.mXpAudioPolicy.getVoiceStatus();
        }
        return 0;
    }

    public boolean setFixedVolume(boolean enable, int vol, int streamType, String packageName) {
        Log.i(TAG, "setFixedVolume  " + enable + " vol:" + vol + " streamType:" + streamType + " packageName:" + packageName);
        int resetVol = 0;
        if (!enable && !getStreamMute(streamType)) {
            resetVol = getStreamVolume(streamType);
        }
        int resetVol2 = resetVol;
        if (this.mValidVolumeMode[streamType] == enable) {
            String reason = enable ? "setted!" : "restored";
            Log.w(TAG, "setFixedVolume  volume " + reason);
            return false;
        }
        setStreamVolume(streamType, enable ? vol : resetVol2, enable ? 268468224 : 8, true, false, packageName);
        SystemProperties.set("persist.audioconfig.fixedvolume", enable ? "true" : "false");
        this.mValidVolumeMode[streamType] = enable;
        return false;
    }

    public boolean isFixedVolume(int streamType) {
        return this.mValidVolumeMode[streamType];
    }

    public void setMusicLimitMode(boolean mode) {
        Log.i(TAG, "setMusicLimitMode  " + mode);
        String limitmode = mode ? "true" : "false";
        SystemProperties.set(PROP_VOLUME_LIMITEDMODE, limitmode);
        this.mIsMusicLimitmode = mode;
        if (this.mHandler != null) {
            int index = getStreamVolume(3);
            Message m = this.mHandler.obtainMessage(3, 3, index, 1);
            this.mHandler.sendMessage(m);
        }
    }

    /* JADX WARN: Type inference failed for: r1v10, types: [com.android.xpeng.audio.xpAudio$10] */
    public void setVolumeFaded(final int StreamType, final int vol, int fadetime, final String packageName) {
        Log.i(TAG, "setVolumeFaded  StreamType=" + StreamType + " vol=" + vol + " fadetime=" + fadetime);
        synchronized (mFadeBLock) {
            if (this.mFadingFlag[StreamType]) {
                this.VolumeFadeBreakFlag[StreamType] = true;
                Log.i(TAG, "setVolumeFaded stop fading , just set! StreamType=" + StreamType);
                try {
                    Thread.sleep(20L);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                setStreamVolume(StreamType, vol, 0, false, true, packageName);
                return;
            }
            this.VolumeFadeBreakFlag[StreamType] = false;
            if (fadetime <= 0) {
                this.mFadeTime = 500;
            } else {
                this.mFadeTime = fadetime;
            }
            final int currentVol = getStreamVolume(StreamType);
            if (currentVol == vol) {
                Log.i(TAG, "setVolumeFaded  currentVol == destVolume");
                return;
            }
            if (currentVol > vol) {
                this.fadeIn = false;
            } else {
                this.fadeIn = true;
            }
            if (this.mCarAudio != null) {
                try {
                    int groupId = getGroupIdByStreamType(StreamType);
                    if (groupId < 0) {
                        return;
                    }
                    int maxVol = this.mGroupMaxVolume[groupId];
                    int minVol = this.mGroupMinVolume[groupId];
                    int i = currCarVolume[groupId];
                    int newVol = Float.valueOf(Math.round(vol * this.mRatios[groupId])).intValue();
                    if (newVol >= minVol && newVol <= maxVol) {
                        synchronized (carVolumeLock) {
                            currCarVolume[groupId] = newVol;
                        }
                    }
                    Log.w(TAG, "setFateVolume out of range. streamType=" + StreamType);
                    return;
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
            }
            if (this.mXpVolumePolicy != null) {
                this.mXpVolumePolicy.saveStreamVolume(StreamType, vol);
                boolean broadcastMuteFlag = false;
                if (StreamType == 3 && (isOtherSessionOn() || isKaraokeOn())) {
                    broadcastMuteFlag = true;
                }
                broadcastVolumeChangedToAll(StreamType, vol, vol, false, packageName, broadcastMuteFlag);
            }
            new Thread() { // from class: com.android.xpeng.audio.xpAudio.10
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    int i2;
                    int stepSetVol;
                    try {
                        if (xpAudio.this.mFadeTime > 0) {
                            synchronized (xpAudio.mFadeBLock) {
                                xpAudio.this.mFadingFlag[StreamType] = true;
                            }
                            int i3 = 10;
                            if (Math.abs(currentVol - vol) <= 10) {
                                i3 = Math.abs(currentVol - vol);
                            }
                            int step = i3;
                            int sleepTime = xpAudio.this.mFadeTime / step;
                            int lastSetVol = -1;
                            for (i2 = 1; i2 < step; i2++) {
                                synchronized (xpAudio.mFadeBLock) {
                                    if (xpAudio.this.VolumeFadeBreakFlag[StreamType]) {
                                        Log.d(xpAudio.TAG, "setFateVolume  fade breaked by flag!!! StreamType=" + StreamType);
                                        xpAudio.this.mFadingFlag[StreamType] = false;
                                        return;
                                    }
                                    stepSetVol = xpAudio.this.fadeIn ? currentVol + ((Math.abs(currentVol - vol) * i2) / step) : currentVol - ((Math.abs(currentVol - vol) * i2) / step);
                                    if (lastSetVol != stepSetVol) {
                                        xpAudio.this.setStreamVolume(StreamType, stepSetVol, 268468224, true, false, packageName);
                                    }
                                }
                                lastSetVol = stepSetVol;
                                Thread.sleep(sleepTime);
                            }
                            synchronized (xpAudio.mFadeBLock) {
                                if (!xpAudio.this.VolumeFadeBreakFlag[StreamType]) {
                                    xpAudio.this.setStreamVolume(StreamType, vol, 268468224, true, false, packageName);
                                    xpAudio.this.mFadingFlag[StreamType] = false;
                                    return;
                                }
                                Log.d(xpAudio.TAG, "setFateVolume  fade breaked by flag!!! StreamType=" + StreamType);
                                xpAudio.this.mFadingFlag[StreamType] = false;
                            }
                        }
                    } catch (Exception e3) {
                        e3.printStackTrace();
                    }
                }
            }.start();
        }
    }

    public boolean isMusicLimitMode() {
        return SystemProperties.getBoolean(PROP_VOLUME_LIMITEDMODE, false);
    }

    public void restoreMusicVolume(String pkgName) {
        Log.i(TAG, "restoreMusicVolume() " + pkgName);
        if (getStreamVolume(3) == 0) {
            setStreamVolume(3, this.musicRestoreVolume, 0, pkgName);
        } else if (getStreamMute(3)) {
            adjustStreamVolume(3, 100, 0, pkgName);
        }
    }

    public void setRingtoneSessionId(int StreamType, int sessionId, String pkgName) {
        Log.i(TAG, "setRingtoneSessionId " + StreamType + " " + sessionId + " " + pkgName);
        if (StreamType == 3) {
            if ((settingsPkgName.equals(pkgName) || "com.android.systemui".equals(pkgName)) && !this.mRingtionBanSessions.contains(Integer.valueOf(sessionId))) {
                this.mRingtionBanSessions.add(Integer.valueOf(sessionId));
            }
        }
    }

    private boolean checkIsRingtoneBanSession(int sessionid) {
        return this.mRingtionBanSessions.contains(Integer.valueOf(sessionid));
    }

    private void removeRingtoneBanSession(int sessionid) {
        if (this.mRingtionBanSessions.contains(Integer.valueOf(sessionid))) {
            Log.i(TAG, "removeRingtoneBanSession " + sessionid);
            this.mRingtionBanSessions.remove(Integer.valueOf(sessionid));
        }
    }

    public void setDangerousTtsStatus(int on) {
        int index;
        DangerousTtsStatus = on;
        Log.i(TAG, "setDangerousTtsStatus " + on);
        if (this.mXpAudioPolicy == null) {
            Log.e(TAG, "setDangerousTtsStatus Error: mXpAudioPolicy == null");
            return;
        }
        this.mXpAudioPolicy.setDangerousTtsOn(on != 0);
        if (on == 0) {
            if (getStreamMute(9)) {
                index = 0;
            } else {
                index = getStreamVolume(9);
            }
            setStreamVolume(9, index, 0, true, false, this.AUDIO_SYSTEM_PACKAGENAME);
            temporaryChangeVolumeDown(3, 0, true, 8, XUI_SERVICE_PACKAGE);
            return;
        }
        int index2 = this.mXpAudioPolicy.getDangerousTtsVolume();
        setStreamVolume(9, index2, 0, true, false, this.AUDIO_SYSTEM_PACKAGENAME);
        int vol = getTempChangeVol(3);
        if (vol == -1) {
            vol = getStreamVolume(3);
        }
        if (vol > 1) {
            temporaryChangeVolumeDown(3, vol / 5 > 1 ? vol / 5 : 1, false, 8, XUI_SERVICE_PACKAGE);
        }
    }

    public int getDangerousTtsStatus() {
        return DangerousTtsStatus;
    }

    private void broadcastVolumeChangedToAll(int streamType, int newVol, int oldVol, boolean adjust, String packageName, boolean muteflag) {
        Log.i(TAG, "broadcastVolumeChangedToAll streamType:" + streamType + " newVol:" + newVol + " pkgName:" + packageName + " muteflag:" + muteflag + " adjust:" + adjust);
        long ident = Binder.clearCallingIdentity();
        try {
            try {
                synchronized (xpAuioBroadcastLock) {
                    this.mVolumeChanged.addFlags(67108864);
                    this.mVolumeChanged.addFlags(268435456);
                    this.mVolumeChanged.putExtra(this.EXTRA_VOLCHANGE_PACKAGENAME, packageName);
                    this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", streamType);
                    this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", newVol);
                    this.mVolumeChanged.putExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", oldVol);
                    if (!adjust) {
                        this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_FLAG", 0);
                    } else {
                        this.mVolumeChanged.putExtra("android.media.EXTRA_VOLUME_STREAM_FLAG", 1);
                    }
                    this.mVolumeChanged.putExtra("android.media.EXTRA_HAS_MUSIC_RUNNING_FLAG", muteflag ? 1 : 0);
                    String str = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                    if (muteflag) {
                        StringBuilder mStr = new StringBuilder();
                        List<String> mList = getOtherMusicPlayingPkgs();
                        for (int i = 0; i < mList.size(); i++) {
                            mStr.append(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + mList.get(i) + ";");
                        }
                        str = mStr.toString();
                    }
                    this.mVolumeChanged.putExtra("android.media.other_musicplaying.PACKAGE_NAME", str);
                    this.mContext.sendBroadcastAsUser(this.mVolumeChanged, UserHandle.ALL);
                }
            } catch (Exception e) {
                Log.e(TAG, "broadcastVolumeChangedToAll " + e);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void btBroadcastPhoneState(boolean on) {
        Intent action_phone_changed = new Intent("android.intent.action.PHONE_STATE");
        long ident = Binder.clearCallingIdentity();
        Log.d(TAG, "btBroadcastPhoneState  " + on + "  " + this.mXpAudioPolicy.getMainDriverMode());
        if (this.mXpAudioPolicy != null) {
            try {
                if (this.mXpAudioPolicy.getMainDriverMode() != 1) {
                    try {
                        SystemProperties.set("audio.telephony.callstate", on ? "1" : "0");
                        this.mContext.sendBroadcastAsUser(action_phone_changed, UserHandle.ALL);
                    } catch (Exception e) {
                        Log.e(TAG, "broadcastVolumeChangedToAll " + e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void btforceFadeMusicVolume(boolean on) {
        btBroadcastPhoneState(on);
        Log.d(TAG, "btforceFadeMusicVolume " + on + " " + inForceMusicVolumeDown);
        if (on) {
            if (!inForceMusicVolumeDown) {
                if (getStreamMute(3) || getStreamVolume(3) == 0) {
                    return;
                }
                inForceMusicSavedVol = getTempChangeVol(3);
                if (inForceMusicSavedVol == -1) {
                    inForceMusicSavedVol = getStreamVolume(3);
                }
                temporaryChangeVolumeDown(3, inForceMusicSavedVol / 5 <= 1 ? 1 : inForceMusicSavedVol / 5, false, 4, BTCALL_VOLCHANGE_PACKAGE);
            }
            inForceMusicVolumeDown = true;
        } else if (!on) {
            if (inForceMusicVolumeDown) {
                temporaryChangeVolumeDown(3, inForceMusicSavedVol / 5 > 1 ? inForceMusicSavedVol / 5 : 1, true, 4, BTCALL_VOLCHANGE_PACKAGE);
            }
            inForceMusicVolumeDown = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class tempVolChangeData {
        int StreamType;
        int changeIndex;
        int flag;
        String packageName;

        private tempVolChangeData() {
        }
    }

    private tempVolChangeData getTempMinVolChangeDataByStreamType(int StreamType) {
        Log.d(TAG, "getTempMinVolChangeDataByStreamType StreamType:" + StreamType + " size:" + this.mTempVolChangeDataList.size());
        if (this.mTempVolChangeDataList.size() == 0) {
            return null;
        }
        tempVolChangeData xTempData = null;
        for (int i = 0; i < this.mTempVolChangeDataList.size(); i++) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType && (xTempData == null || xTempData.changeIndex > mTempData.changeIndex)) {
                Log.d(TAG, "getTempMinVolChangeDataByStreamType index:" + mTempData.changeIndex + " " + mTempData.packageName);
                xTempData = mTempData;
            }
        }
        return xTempData;
    }

    private void removeTempVolChangeData(int StreamType, int dstVol, int flag, String packageName) {
        if (this.mTempVolChangeDataList.size() == 0) {
            return;
        }
        for (int i = 0; i < this.mTempVolChangeDataList.size(); i++) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType && mTempData.flag == flag && packageName.equals(mTempData.packageName)) {
                Log.d(TAG, "removeTempVolChangeData " + mTempData.changeIndex);
                this.mTempVolChangeDataList.remove(i);
                return;
            }
        }
    }

    private void removeTempVolChangeDataOfStreamType(int StreamType) {
        if (this.mTempVolChangeDataList.size() == 0) {
            return;
        }
        for (int i = this.mTempVolChangeDataList.size() - 1; i >= 0; i--) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType) {
                Log.d(TAG, "removeTempVolChangeDataOfStreamType " + mTempData.changeIndex);
                this.mTempVolChangeDataList.remove(i);
            }
        }
    }

    private boolean checkTempVolChangeDataExist(int StreamType, int dstVol, int flag, String packageName) {
        if (this.mTempVolChangeDataList.size() == 0) {
            return false;
        }
        for (int i = 0; i < this.mTempVolChangeDataList.size(); i++) {
            tempVolChangeData mTempData = this.mTempVolChangeDataList.get(i);
            if (mTempData != null && mTempData.StreamType == StreamType && mTempData.flag == flag && packageName.equals(mTempData.packageName)) {
                Log.d(TAG, "checkTempVolChangeDataExist :" + StreamType + " " + dstVol + " " + flag);
                return true;
            }
        }
        return false;
    }

    private void addTempVolChangeData(int StreamType, int vol, int flag, String packageName) {
        if (checkTempVolChangeDataExist(StreamType, vol, flag, packageName)) {
            return;
        }
        tempVolChangeData mTempData = new tempVolChangeData();
        mTempData.StreamType = StreamType;
        mTempData.changeIndex = vol;
        mTempData.packageName = packageName;
        mTempData.flag = flag;
        this.mTempVolChangeDataList.add(mTempData);
    }

    private void clearTempVolChangeData() {
        Log.d(TAG, "clearTempVolChangeData()");
        this.mTempVolChangeDataList.clear();
        if (tempVolChangeSaved == null) {
            tempVolChangeSaved = new int[11];
        }
        Arrays.fill(tempVolChangeSaved, -1);
    }

    private void saveTempChangeVol(int StreamType) {
        Log.d(TAG, "saveTempChangeVol " + StreamType + " " + tempVolChangeSaved[StreamType] + " " + getStreamVolume(StreamType));
        if (tempVolChangeSaved != null && StreamType < 11 && StreamType >= 0 && tempVolChangeSaved[StreamType] == -1) {
            tempVolChangeSaved[StreamType] = getStreamVolume(StreamType);
        }
    }

    private void removeTempChangeVol(int StreamType) {
        Log.d(TAG, "removeTempChangeVol " + StreamType);
        if (tempVolChangeSaved != null && StreamType < 11 && StreamType >= 0 && tempVolChangeSaved[StreamType] != -1) {
            tempVolChangeSaved[StreamType] = -1;
        }
    }

    private int getTempChangeVol(int StreamType) {
        if (tempVolChangeSaved != null && StreamType < 11 && StreamType >= 0) {
            return tempVolChangeSaved[StreamType];
        }
        return -1;
    }

    private void dealVolChangeWhenMute(int StreamType, int Vol, String packageName) {
        Log.i(TAG, "dealVolChangeWhenMute  " + StreamType + " " + Vol + " " + packageName);
        if (StreamType > 0 && StreamType < AudioSystem.getNumStreamTypes() && Vol >= 0 && Vol <= 30 && this.mXpVolumePolicy != null) {
            this.mXpVolumePolicy.saveStreamVolume(StreamType, Vol, false);
            resetCurrentGroupVol(StreamType);
            boolean broadcastMuteFlag = false;
            if (StreamType == 3 && (isOtherSessionOn() || isKaraokeOn())) {
                broadcastMuteFlag = true;
            }
            broadcastVolumeChangedToAll(StreamType, Vol, 0, false, packageName, broadcastMuteFlag);
        }
    }

    private void dealTemporaryVolChange(int StreamType, int vol, String packageName) {
        if (this.mXpAudioPolicy.getBanVolumeChangeMode(StreamType) == 0) {
            if (getStreamMute(StreamType) || (StreamType == 10 && isBtCallConnectOrCallout())) {
                dealVolChangeWhenMute(StreamType, vol, packageName);
                return;
            } else {
                setVolumeFaded(StreamType, vol, 0, packageName);
                return;
            }
        }
        Log.w(TAG, "StreamType " + StreamType + "  is in ban mode !!! do not change volume");
    }

    public synchronized void temporaryChangeVolumeDown(int StreamType, int dstVol, boolean restoreVol, int flag, String packageName) {
        Log.i(TAG, "temporaryChangeVolumeDown StreamType:" + StreamType + " " + dstVol + " " + restoreVol + " " + packageName + " " + getTempChangeVol(StreamType) + " flag:" + flag);
        if (restoreVol) {
            removeTempVolChangeData(StreamType, dstVol, flag, packageName);
            tempVolChangeData tData = getTempMinVolChangeDataByStreamType(StreamType);
            if (tData == null && getTempChangeVol(StreamType) >= 0) {
                dealTemporaryVolChange(StreamType, getTempChangeVol(StreamType), packageName);
                removeTempChangeVol(StreamType);
            } else if (tData != null) {
                dealTemporaryVolChange(StreamType, tData.changeIndex, packageName);
            }
        } else {
            addTempVolChangeData(StreamType, dstVol, flag, packageName);
            saveTempChangeVol(StreamType);
            tempVolChangeData tData2 = getTempMinVolChangeDataByStreamType(StreamType);
            if (tData2 == null) {
                return;
            }
            dealTemporaryVolChange(StreamType, tData2.changeIndex, packageName);
        }
    }

    public List<xpAudioSessionInfo> getActiveSessionList() {
        List<xpAudioSessionInfo> result = new ArrayList<>();
        synchronized (this.mActiveSessionArray) {
            if (this.mActiveSessionArray.size() > 0) {
                for (int i = 0; i < this.mActiveSessionArray.size(); i++) {
                    result.add(this.mActiveSessionArray.valueAt(i));
                }
            }
        }
        return result;
    }

    /* loaded from: classes.dex */
    public class xpAudioHandler extends Handler {
        public xpAudioHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 3:
                    Log.i(xpAudio.TAG, "handle MSG_SETSTREAM_VOLUME streamtype:" + msg.arg1);
                    xpAudio.this.setStreamVolume(msg.arg1, msg.arg2, 0, false, ((Integer) msg.obj).intValue() != 0, xpAudio.this.AUDIO_SYSTEM_PACKAGENAME);
                    return;
                case 4:
                    Log.i(xpAudio.TAG, "handle MSG_ADJUSTSTREAM_VOLUME");
                    StreamVolumeClass mStreamVolumeClass = (StreamVolumeClass) msg.obj;
                    xpAudio.this.xpadjustStreamVolume(msg.arg1, msg.arg2, mStreamVolumeClass.flag, mStreamVolumeClass.packageName);
                    return;
                case 5:
                    Log.i(xpAudio.TAG, "handle MSG_CHANGE_STREAM_VOL");
                    changeVolParam param = (changeVolParam) msg.obj;
                    xpAudio.this.xpChangeStreamVol(msg.arg1, msg.arg2, param.ignoremute, param.resetback);
                    return;
                default:
                    return;
            }
        }
    }
}
