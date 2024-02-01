package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioSystem;
import android.net.Uri;
import android.net.util.NetworkConstants;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;
import com.google.gson.Gson;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.view.ISharedDisplayListener;
import com.xiaopeng.view.SharedDisplayListener;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioData;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.massageSeatEffect;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicy;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class xuiExternalAudioPath {
    private static final String ACTION_SECOND_BT_CONNECTION = "xiaopeng.bluetooth.a2dp.action.CONNECTION_STATE_CHANGED";
    public static final String AVAS_BUS = "AVAS";
    private static final String AVAS_STREAM_ENABLE = "avas_speaker";
    private static final int AudioFlingerRestoreDelay;
    private static final String BUS00_MEDIA = "BUS00_MEDIA";
    private static final String BUS01_SYS_NOTIFICATION = "BUS01_SYS_NOTIFICATION";
    private static final String BUS02_NAV_GUIDANCE = "BUS02_NAV_GUIDANCE";
    private static final String BUS03_PHONE = "BUS03_PHONE";
    private static final String BUS08_FRONT_PASSENGER = "BUS08_FRONT_PASSENGER";
    private static final String BUS16_REAR_SEAT = "BUS16_REAR_SEAT";
    private static final String BUSMEDIA_AND_MASSSEAT = "BUS00_MEDIA$$BUS08_FRONT_PASSENGER";
    private static final String BUSSYS_AND_AVAS = "BUS01_SYS_NOTIFICATION$$BUS16_REAR_SEAT";
    private static final String DataSchemePkg = "package";
    public static final String MASSAGESEAT_BUS = "MASSAGESEAT";
    public static final String MEDIA_BUS = "MEDIA";
    public static final String MMAP_BUS = "MMAP";
    private static final int MSG_AUDIOSERVER_CRASH_RESTORE = 1;
    private static final int MSG_CHANGE_OUTPUT_PATH = 2;
    private static final String MusicAndAvas = "1";
    private static final String MusicAndMassageSeat = "2";
    private static final String MusicOnly = "0";
    private static final String MusicTypeOnlyAvas = "3";
    public static final String NAV_BUS = "NAV";
    private static final String PASSENGERBT = "card=";
    public static final String PHONE_BUS = "PHONE";
    public static final String SYS_BUS = "SYSTEM";
    private static final float SysThreadStreamPatchVol = 0.501188f;
    private static final String TAG = "xuiExternalAudioPath";
    private static final Object arrayListObject;
    private static final boolean avasSoftConflictMute;
    private static int changeoutputDelayTime = 0;
    private static final int duplicateOutAvasOnly = 3;
    private static final int duplicateOutMusicAndAvas = 1;
    private static final int duplicateOutMusicAndMassageSeat = 2;
    private static final int duplicateOutMusicOnly = 0;
    private static final boolean hasAmp;
    private static final boolean hasAvas;
    private static final boolean hasAvasWhiteList;
    private static final boolean hasMassSeat;
    private static final boolean hasPassageBtUsbFeature;
    private static ArrayList<String> mAvasPkgList = null;
    private static Context mContext = null;
    private static Gson mGson = null;
    private static xuiExternalAudioPath mInstance = null;
    private static List<massageSeatEffect> mMassageSeatEffectList = null;
    private static ArrayList<String> mMassageSeatPkgList = null;
    private static PackageManager mPackageManager = null;
    private static WindowManager mWindowManager = null;
    private static xuiAudioData mXuiAudioData = null;
    private static final int massageSeatAtmos;
    private static final int massageSeatModel;
    private static final String pipePath = "/data/mytest";
    private static Object softMuteLock;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private passengerPkgList mPkgList;
    boolean passengerbluetoothConnected = false;
    private ArrayList<Integer> mPassangerPkgList = new ArrayList<>();
    private ArrayList<Integer> mPassengerSessionIdList = new ArrayList<>();
    private ArrayList<Integer> mMediaAndAvasIdList = new ArrayList<>();
    private ArrayList<Integer> mSystemAndAvasIdList = new ArrayList<>();
    private ArrayList<SoftVolMuteInfo> mSoftVolMuteInfoList = new ArrayList<>();
    boolean passageBtUsbOn = false;
    private final ISharedDisplayListener mSharedDisplayListener = new SharedDisplayListener() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath.3
        public void onChanged(String packageName, int sharedId) {
            Log.d(xuiExternalAudioPath.TAG, "mSharedDisplayListener  onChanged:" + packageName + " sharedId:" + sharedId);
            if (xuiExternalAudioPath.this.passengerbluetoothConnected) {
                xuiExternalAudioPath.this.setPassengerPkgList();
                xuiExternalAudioPath.this.sendInvalidateStreamByPkgName(packageName);
            }
            Log.d(xuiExternalAudioPath.TAG, "mSharedDisplayListener  end");
        }
    };
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath.4
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if ("xiaopeng.bluetooth.a2dp.action.CONNECTION_STATE_CHANGED".equals(action)) {
                    int state = ((Integer) intent.getExtra("state", 0)).intValue();
                    Log.d(xuiExternalAudioPath.TAG, "onReceive  state" + state);
                    if (2 == state) {
                        xuiExternalAudioPath xuiexternalaudiopath = xuiExternalAudioPath.this;
                        xuiexternalaudiopath.passengerbluetoothConnected = true;
                        xuiexternalaudiopath.setPassengerBtVolume(xuiExternalAudioPath.mXuiAudioData.getStreamVolume(13));
                        xuiExternalAudioPath.this.changePassengerBtConnectState();
                    } else if (xuiExternalAudioPath.this.passengerbluetoothConnected) {
                        xuiExternalAudioPath xuiexternalaudiopath2 = xuiExternalAudioPath.this;
                        xuiexternalaudiopath2.passengerbluetoothConnected = false;
                        xuiexternalaudiopath2.changePassengerBtConnectState();
                    }
                } else if (action.equals("android.intent.action.BOOT_COMPLETED")) {
                    Log.d(xuiExternalAudioPath.TAG, "ACTION_BOOT_COMPLETED");
                    xuiExternalAudioPath.this.setPassengerPkgList();
                    xuiExternalAudioPath.this.sendSessionList();
                    xuiExternalAudioPath.this.triggerNativeCarServiceConnect();
                    xuiExternalAudioPath.this.sendInvalidateStream();
                    if (xuiExternalAudioPath.hasMassSeat && xuiExternalAudioPath.massageSeatModel == 1) {
                        xuiExternalAudioPath.this.getAllMassageSeatPackages();
                        xuiExternalAudioPath.this.setMassageSeatPkgList();
                    }
                    if (xuiExternalAudioPath.hasAvas && xuiExternalAudioPath.hasAvasWhiteList) {
                        xuiExternalAudioPath.this.getAllAvasPackages();
                        xuiExternalAudioPath.this.setAvasPkgList();
                    }
                }
            }
        }
    };
    private final BroadcastReceiver mReceiverPkg = new BroadcastReceiver() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath.5
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if (action.equals("android.intent.action.PACKAGE_ADDED")) {
                    Log.d(xuiExternalAudioPath.TAG, "ACTION_PACKAGE_ADDED");
                    if (xuiExternalAudioPath.hasMassSeat && xuiExternalAudioPath.massageSeatModel == 1) {
                        xuiExternalAudioPath.this.updateMassageSeatPkgList(true, intent.getDataString());
                        xuiExternalAudioPath.this.setMassageSeatPkgList();
                    }
                    if (xuiExternalAudioPath.hasAvas && xuiExternalAudioPath.hasAvasWhiteList) {
                        xuiExternalAudioPath.this.updateAvasPkgList(true, intent.getDataString());
                        xuiExternalAudioPath.this.setAvasPkgList();
                    }
                } else if (action.equals("android.intent.action.PACKAGE_REMOVED")) {
                    Log.d(xuiExternalAudioPath.TAG, "ACTION_PACKAGE_REMOVED");
                    if (xuiExternalAudioPath.hasMassSeat && xuiExternalAudioPath.massageSeatModel == 1) {
                        xuiExternalAudioPath.this.updateMassageSeatPkgList(false, intent.getDataString());
                        xuiExternalAudioPath.this.setMassageSeatPkgList();
                    }
                    if (xuiExternalAudioPath.hasAvas && xuiExternalAudioPath.hasAvasWhiteList) {
                        xuiExternalAudioPath.this.updateAvasPkgList(false, intent.getDataString());
                        xuiExternalAudioPath.this.setAvasPkgList();
                    }
                }
            }
        }
    };

    static {
        hasPassageBtUsbFeature = FeatureOption.FO_PASSENGER_BT_AUDIO_EXIST > 0;
        hasMassSeat = FeatureOption.hasFeature("FSEAT_RHYTHM");
        hasAmp = FeatureOption.hasFeature("AMP");
        hasAvas = FeatureOption.FO_AVAS_SUPPORT_TYPE != 0;
        hasAvasWhiteList = SystemProperties.getBoolean("persist.xiaopeng.audio.avas.has.whiteList", false);
        massageSeatModel = SystemProperties.getInt("persist.xiaopeng.audio.massageSeat.model", 0);
        massageSeatAtmos = SystemProperties.getInt("persist.xiaopeng.audio.massageSeat.atmosSw", 0);
        avasSoftConflictMute = SystemProperties.getBoolean("persist.xiaopeng.avas.conflict.softMute.enable", true);
        mMassageSeatEffectList = null;
        mMassageSeatPkgList = new ArrayList<String>() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath.1
            {
                add("com.xiaopeng.musicradio");
                add("com.android.bluetooth");
                add("com.xiaopeng.carcontrol");
            }
        };
        mAvasPkgList = new ArrayList<String>() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath.2
            {
                add("com.xiaopeng.musicradio");
                add("com.xiaopeng.homespace");
                add("com.android.bluetooth");
            }
        };
        AudioFlingerRestoreDelay = SystemProperties.getInt("persist.xiaopeng.audioflinger.restroe.delay", (int) NetworkConstants.ETHER_MTU);
        arrayListObject = new Object();
        softMuteLock = new Object();
        changeoutputDelayTime = SystemProperties.getInt("persist.sys.xui.changeoutput.delaytime", 800);
    }

    private xuiExternalAudioPath(Context context) {
        this.mHandlerThread = null;
        this.mHandler = null;
        mContext = context;
        mGson = new Gson();
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread("xuiExternalHandler");
        }
        this.mHandlerThread.start();
        this.mHandler = new xuiExternalHandler(this.mHandlerThread.getLooper());
        mXuiAudioData = xuiAudioData.getInstance(context);
        mWindowManager = (WindowManager) context.getSystemService("window");
        mPackageManager = context.getPackageManager();
        if (hasPassageBtUsbFeature) {
            mWindowManager.registerSharedListener(this.mSharedDisplayListener);
        }
        if (hasMassSeat) {
            mMassageSeatEffectList = xuiAudioParser.parseMassageSeatEffect();
        }
        registerReceiver();
    }

    public static xuiExternalAudioPath getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new xuiExternalAudioPath(context);
        }
        return mInstance;
    }

    public static int getUidByPackageName(String pkgName) {
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(pkgName, 1);
            int ret = ai.uid;
            return ret;
        } catch (Exception e) {
            Log.e(TAG, "getUidByPackageName " + e);
            return -1;
        }
    }

    public static int getPidByPackageName(String pkgName) {
        ActivityManager am = (ActivityManager) mContext.getSystemService("activity");
        try {
            List<ActivityManager.RunningAppProcessInfo> mRunningProcess = am.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo amProcess : mRunningProcess) {
                if (amProcess.processName.equals(pkgName)) {
                    int pid = amProcess.pid;
                    return pid;
                }
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "getPidByPackageName " + e);
            return -1;
        }
    }

    public ArrayList<String> getAllMassageSeatPackages() {
        List<xpPackageInfo> mPkgList;
        if (mPackageManager == null) {
            Log.w(TAG, "getAllMassageSeatPackages mPackageManager is null");
            return null;
        }
        try {
            mPkgList = xpPackageManagerService.get(mContext).getAllXpPackageInfos();
        } catch (Exception e) {
            Log.e(TAG, "getAllMassageSeatPackages " + e);
        }
        if (mPkgList == null) {
            Log.w(TAG, "getAllMassageSeatPackages mPkgList is null");
            return null;
        }
        synchronized (arrayListObject) {
            for (int i = 0; i < mPkgList.size(); i++) {
                xpPackageInfo mPkgInfo = mPkgList.get(i);
                Log.w(TAG, "getAllMassageSeatPackages size:" + mPkgList.size() + " " + mPkgInfo.packageName + " appType:" + mPkgInfo.appType);
                if (mPkgInfo.appType == 1 && !mMassageSeatPkgList.contains(mPkgInfo.packageName)) {
                    mMassageSeatPkgList.add(mPkgInfo.packageName);
                }
            }
        }
        Log.w(TAG, "getAllMassageSeatPackages mPkgList is :" + mMassageSeatPkgList);
        return mMassageSeatPkgList;
    }

    public synchronized void updateMassageSeatPkgList(boolean add, String pkgName) {
        try {
            Log.d(TAG, "updateMassageSeatPkgList " + add + " pkgName:" + pkgName + "!!");
        } catch (Exception e) {
            Log.e(TAG, "updateMassageSeatPkgList " + e);
        }
        if (TextUtils.isEmpty(pkgName)) {
            return;
        }
        String packageName = pkgName.substring("package".length() + 1);
        xpPackageInfo mPkgInfo = xpPackageManagerService.get(mContext).getXpPackageInfo(packageName);
        Log.d(TAG, "updateMassageSeatPkgList  packageName:" + packageName + " mPkgInfo:" + mPkgInfo);
        synchronized (arrayListObject) {
            try {
                if (add && mPkgInfo != null) {
                    Log.d(TAG, "updateMassageSeatPkgList mPkgInfo.appType:" + mPkgInfo.appType);
                    if (mPkgInfo.appType == 1 && !mMassageSeatPkgList.contains(packageName)) {
                        mMassageSeatPkgList.add(packageName);
                    }
                } else if (!add && mMassageSeatPkgList.contains(packageName)) {
                    mMassageSeatPkgList.remove(packageName);
                }
            } finally {
                th = th;
                while (true) {
                    try {
                        break;
                    } catch (Throwable th) {
                        th = th;
                    }
                }
            }
        }
        Log.d(TAG, "updateMassageSeatPkgList done: " + mMassageSeatPkgList);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setMassageSeatPkgList() {
        try {
            StringBuilder mStr = new StringBuilder();
            synchronized (arrayListObject) {
                for (int i = 0; i < mMassageSeatPkgList.size(); i++) {
                    int uid = getUidByPackageName(mMassageSeatPkgList.get(i));
                    if (uid != -1) {
                        mStr.append("" + uid + ",");
                    }
                }
            }
            sendMassageSeatUidList(mStr.toString());
        } catch (Exception e) {
            Log.e(TAG, "setMassageSeatPkgList " + e);
        }
    }

    public ArrayList<String> getAllAvasPackages() {
        List<xpPackageInfo> mPkgList;
        if (hasAvas && hasAvasWhiteList) {
            if (mPackageManager == null) {
                Log.w(TAG, "getAllAvasPackages mPackageManager is null ");
                return null;
            }
            try {
                mPkgList = xpPackageManagerService.get(mContext).getAllXpPackageInfos();
            } catch (Exception e) {
                Log.e(TAG, "getAllAvasPackages " + e);
            }
            if (mPkgList == null) {
                Log.w(TAG, "getAllAvasPackages mPkgList is null");
                return null;
            }
            synchronized (arrayListObject) {
                for (int i = 0; i < mPkgList.size(); i++) {
                    xpPackageInfo mPkgInfo = mPkgList.get(i);
                    Log.d(TAG, "getAllAvasPackages size:" + mPkgList.size() + " " + mPkgInfo.packageName + " appType:" + mPkgInfo.appType);
                    if (!mAvasPkgList.contains(mPkgInfo.packageName)) {
                        mAvasPkgList.add(mPkgInfo.packageName);
                    }
                }
            }
            Log.w(TAG, "getAllAvasPackages mPkgList is :" + mAvasPkgList);
            return mAvasPkgList;
        }
        return null;
    }

    public synchronized void updateAvasPkgList(boolean add, String pkgName) {
        if (hasAvas && hasAvasWhiteList) {
            try {
                Log.d(TAG, "updateAvasPkgList " + add + " pkgName:" + pkgName + "!!");
            } catch (Exception e) {
                Log.e(TAG, "updateAvasPkgList " + e);
            }
            if (TextUtils.isEmpty(pkgName)) {
                return;
            }
            String packageName = pkgName.substring("package".length() + 1);
            xpPackageInfo mPkgInfo = xpPackageManagerService.get(mContext).getXpPackageInfo(packageName);
            Log.d(TAG, "updateAvasPkgList  packageName:" + packageName + " mPkgInfo:" + mPkgInfo);
            synchronized (arrayListObject) {
                try {
                    if (add && mPkgInfo != null) {
                        Log.d(TAG, "updateAvasPkgList mPkgInfo.appType:" + mPkgInfo.appType);
                        if (!mAvasPkgList.contains(packageName)) {
                            mAvasPkgList.add(packageName);
                        }
                    } else if (!add && mAvasPkgList.contains(packageName)) {
                        mAvasPkgList.remove(packageName);
                    }
                } finally {
                    th = th;
                    while (true) {
                        try {
                            break;
                        } catch (Throwable th) {
                            th = th;
                        }
                    }
                }
            }
            Log.d(TAG, "updateAvasPkgList done: " + mAvasPkgList);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setAvasPkgList() {
        if (!hasAvas || !hasAvasWhiteList) {
            return;
        }
        try {
            StringBuilder mStr = new StringBuilder();
            synchronized (arrayListObject) {
                for (int i = 0; i < mAvasPkgList.size(); i++) {
                    int uid = getUidByPackageName(mAvasPkgList.get(i));
                    if (uid != -1) {
                        mStr.append("" + uid + ",");
                    }
                }
            }
            sendAvasUidList(mStr.toString());
        } catch (Exception e) {
            Log.e(TAG, "setAvasPkgList " + e);
        }
    }

    private static boolean checkNeverInPassengerPkgs(String pkgName) {
        try {
            xpPackageInfo mPkgInfo = xpPackageManagerService.get(mContext).getXpPackageInfo(pkgName);
            if (mPkgInfo != null && mPkgInfo.appType == 7) {
                Log.d(TAG, "checkNeverInPassengerPkgs  " + pkgName + " is never in Passenger");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "checkNeverInPassengerPkgs " + e);
            return false;
        }
    }

    public static synchronized ArrayList<Integer> getPassengerUids() {
        ArrayList<Integer> uids;
        synchronized (xuiExternalAudioPath.class) {
            Log.i(TAG, "getPassengerUids()");
            uids = new ArrayList<>();
            try {
                List<String> sharedPkgJson = mWindowManager.getSharedPackages();
                for (int i = 0; i < sharedPkgJson.size(); i++) {
                    JSONObject mObject = new JSONObject(sharedPkgJson.get(i));
                    int sharedId = ((Integer) mObject.get("sharedId")).intValue();
                    if (sharedId == 1) {
                        String pkgName = (String) mObject.get("packageName");
                        if (checkNeverInPassengerPkgs(pkgName)) {
                            Log.d(TAG, " pkgName:" + pkgName + " is only play in driver");
                        } else {
                            int uid = getUidByPackageName(pkgName);
                            if (uid != -1) {
                                Log.d(TAG, "find uid:" + uid + " pkgName:" + pkgName);
                                if (!uids.contains(Integer.valueOf(uid))) {
                                    uids.add(Integer.valueOf(uid));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "getPassengerUids " + e);
            }
        }
        return uids;
    }

    public static synchronized ArrayList<Integer> getPassengerSystemUidPids() {
        ArrayList<Integer> pids;
        int pid;
        synchronized (xuiExternalAudioPath.class) {
            Log.i(TAG, "getPassengerSystemUidPids()");
            pids = new ArrayList<>();
            try {
                List<String> sharedPkgJson = mWindowManager.getSharedPackages();
                for (int i = 0; i < sharedPkgJson.size(); i++) {
                    JSONObject mObject = new JSONObject(sharedPkgJson.get(i));
                    int sharedId = ((Integer) mObject.get("sharedId")).intValue();
                    if (sharedId == 1) {
                        String pkgName = (String) mObject.get("packageName");
                        if (checkNeverInPassengerPkgs(pkgName)) {
                            Log.d(TAG, " pkgName:" + pkgName + " is only play in driver");
                        } else {
                            int uid = getUidByPackageName(pkgName);
                            if (uid != -1 && uid == 1000 && (pid = getPidByPackageName(pkgName)) != -1 && !pids.contains(Integer.valueOf(pid))) {
                                pids.add(Integer.valueOf(pid));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "getPassengerSystemUidPids " + e);
            }
        }
        return pids;
    }

    private void registerReceiver() {
        BluetoothAdapter adapter;
        Log.i(TAG, "registerReceiver");
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction("android.intent.action.BOOT_COMPLETED");
        if (hasPassageBtUsbFeature) {
            mIntentFilter.addAction("xiaopeng.bluetooth.a2dp.action.CONNECTION_STATE_CHANGED");
        }
        mContext.registerReceiver(this.mReceiver, mIntentFilter);
        if ((hasMassSeat && massageSeatModel == 1) || (hasAvas && hasAvasWhiteList)) {
            Log.i(TAG, "registerReceiver  add and remove pkg");
            IntentFilter mIntentFilterPkg = new IntentFilter();
            mIntentFilterPkg.addAction("android.intent.action.PACKAGE_ADDED");
            mIntentFilterPkg.addAction("android.intent.action.PACKAGE_REMOVED");
            mIntentFilterPkg.addDataScheme("package");
            mContext.registerReceiver(this.mReceiverPkg, mIntentFilterPkg);
        }
        if (hasPassageBtUsbFeature && (adapter = BluetoothAdapter.getDefaultAdapter()) != null && adapter.isDeviceConnected(1)) {
            this.passengerbluetoothConnected = true;
            Log.d(TAG, "passengerbluetoothConnected : " + this.passengerbluetoothConnected);
            setPassengerBtVolume(mXuiAudioData.getStreamVolume(13));
        }
        if (hasAvas) {
            boolean avasStreamEnable = Settings.System.getInt(mContext.getContentResolver(), AVAS_STREAM_ENABLE, 0) == 1;
            Log.d(TAG, "avasStreamEnable : " + avasStreamEnable);
            openDuplicateThread(BUS16_REAR_SEAT, BUS00_MEDIA);
            openDuplicateThread(BUS16_REAR_SEAT, BUS01_SYS_NOTIFICATION);
            String key = MusicOnly;
            if (avasStreamEnable && hasAvas) {
                key = MusicTypeOnlyAvas;
            } else if (hasMassSeat && massageSeatModel == 1 && getMusicSeatEnable()) {
                key = MusicAndMassageSeat;
            }
            SystemProperties.set("persist.sys.xiaopeng.musicStreamSpecialOutput", key);
            setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 3, 1.0f);
            setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 1, 1.0f);
            setDuplicateOutStreamVolume(BUSSYS_AND_AVAS, 1, 1.0f);
            setDuplicateOutStreamVolume(BUS01_SYS_NOTIFICATION, 16, SysThreadStreamPatchVol);
            if (hasAvas) {
                mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(AVAS_STREAM_ENABLE), true, new ContentObserver(new Handler()) { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath.6
                    @Override // android.database.ContentObserver
                    public void onChange(boolean selfChange, Uri uri) {
                        super.onChange(selfChange, uri);
                        boolean avasStreamEnable2 = Settings.System.getInt(xuiExternalAudioPath.mContext.getContentResolver(), xuiExternalAudioPath.AVAS_STREAM_ENABLE, 0) == 1;
                        Log.d(xuiExternalAudioPath.TAG, "avasStreamEnable change to " + avasStreamEnable2);
                        xuiExternalAudioPath.this.sendDelayedChangeOutPutMessage();
                    }
                });
            }
        } else {
            String key2 = MusicOnly;
            if (hasMassSeat && massageSeatModel == 1 && getMusicSeatEnable()) {
                key2 = MusicAndMassageSeat;
            }
            SystemProperties.set("persist.sys.xiaopeng.musicStreamSpecialOutput", key2);
        }
        if (hasMassSeat && massageSeatModel == 1) {
            openDuplicateThread(BUS08_FRONT_PASSENGER, BUS00_MEDIA);
            setDuplicateOutStreamVolume(BUS08_FRONT_PASSENGER, 16, 1.0f);
            sendMassageSeatEffectCreate();
            getAllMassageSeatPackages();
            setMassageSeatPkgList();
            sendMassSeatStatus(getMusicSeatEnable());
        } else if (hasMassSeat) {
            sendMassageSeatEffectCreate();
        }
    }

    /* loaded from: classes2.dex */
    private class pkgBean {
        public int pid;
        public String pkgName;
        public int uid;

        private pkgBean() {
        }

        public int getPid() {
            return this.pid;
        }

        public void setPid(int pid) {
            this.pid = pid;
        }

        public int getUid() {
            return this.uid;
        }

        public void setUid(int uid) {
            this.uid = uid;
        }

        public String getPkgName() {
            return this.pkgName;
        }

        public void setPkgName(String pkgName) {
            this.pkgName = pkgName;
        }
    }

    /* loaded from: classes2.dex */
    private class passengerPkgList {
        public pkgBean[] pkglist;

        private passengerPkgList() {
        }

        public pkgBean[] getPkgList() {
            return this.pkglist;
        }

        public void setPkgList(pkgBean[] pkglist) {
            this.pkglist = pkglist;
        }
    }

    public void igStatusChange(int status) {
        Log.d(TAG, "igStatuschange " + status);
        AudioSystem.setParameters("AfMethod=igStatusChange");
        resetSoftMuteInfo();
        synchronized (arrayListObject) {
            this.mPassengerSessionIdList.clear();
            this.mMediaAndAvasIdList.clear();
            this.mSystemAndAvasIdList.clear();
        }
        if (hasMassSeat && massageSeatModel == 1) {
            setDuplicateOutStreamVolume(BUS08_FRONT_PASSENGER, 16, 1.0f);
        }
        setSoftTypeVolumeMute(3, false);
    }

    public void audioServerDiedRestore() {
        Log.w(TAG, "audioServerDiedRestore");
        restoreWithAudioServerCrash();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void restoreWithAudioServerCrash() {
        BluetoothAdapter adapter;
        Log.i(TAG, "restoreWithAudioServerCrash");
        boolean need_recreate = false;
        if (hasPassageBtUsbFeature && (adapter = BluetoothAdapter.getDefaultAdapter()) != null && adapter.isDeviceConnected(1)) {
            this.passengerbluetoothConnected = true;
            setPassengerBtVolume(mXuiAudioData.getStreamVolume(13));
            setPassengerPkgList();
            sendSessionList();
            need_recreate = true;
        }
        if (hasMassSeat && massageSeatModel == 1) {
            openDuplicateThread(BUS08_FRONT_PASSENGER, BUS00_MEDIA);
            setDuplicateOutStreamVolume(BUS08_FRONT_PASSENGER, 16, 1.0f);
            sendMassageSeatEffectCreate();
            setMusicSeatEffect(getMusicSeatEffect());
            setMassageSeatPkgList();
        } else if (hasMassSeat) {
            sendMassageSeatEffectCreate();
        }
        if (FeatureOption.FO_AVAS_SUPPORT_TYPE == 1) {
            openDuplicateThread(BUS16_REAR_SEAT, BUS00_MEDIA);
            need_recreate = true;
        }
        boolean avasStreamEnable = Settings.System.getInt(mContext.getContentResolver(), AVAS_STREAM_ENABLE, 0) == 1;
        Log.d(TAG, "restoreWithAudioServerCrash avasStreamEnable : " + avasStreamEnable);
        String key = MusicOnly;
        if (avasStreamEnable) {
            key = MusicTypeOnlyAvas;
        } else if (hasMassSeat && massageSeatModel == 1 && getMusicSeatEnable()) {
            key = MusicAndMassageSeat;
        }
        SystemProperties.set("persist.sys.xiaopeng.musicStreamSpecialOutput", key);
        setAvasPkgList();
        sendMassSeatStatus(getMusicSeatEnable());
        if (hasAvas) {
            setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 3, 1.0f);
            setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 16, 1.0f);
            setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 1, 1.0f);
            setDuplicateOutStreamVolume(BUSSYS_AND_AVAS, 1, 1.0f);
            setDuplicateOutStreamVolume(BUS01_SYS_NOTIFICATION, 16, SysThreadStreamPatchVol);
        }
        if (need_recreate) {
            sendInvalidateStream();
        }
        triggerNativeCarServiceConnect();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setPassengerPkgList() {
        setPassengerPkgList(this.passengerbluetoothConnected ? getPassengerUids() : null, this.passengerbluetoothConnected ? getPassengerSystemUidPids() : null);
    }

    private synchronized void setPassengerPkgList(ArrayList<Integer> uidList, ArrayList<Integer> pidList) {
        StringBuilder sb = new StringBuilder();
        sb.append("setPassengerPkgList ");
        sb.append(uidList == null ? "" : uidList.toString());
        Log.d(TAG, sb.toString());
        if (uidList != null) {
            StringBuilder mStr = new StringBuilder();
            for (int i = 0; i < uidList.size(); i++) {
                mStr.append("" + uidList.get(i) + ",");
            }
            sendUidList(mStr.toString());
            if (pidList != null && pidList.size() > 0) {
                StringBuilder pidStr = new StringBuilder();
                for (int i2 = 0; i2 < pidList.size(); i2++) {
                    pidStr.append("" + pidList.get(i2) + ",");
                }
                sendPassengerSystemUidPidList(pidStr.toString());
            } else {
                sendPassengerSystemUidPidList("");
            }
        } else {
            sendUidList("");
            sendPassengerSystemUidPidList("");
        }
    }

    public void setAvasStreamEnable(int busType, boolean enable) {
        Log.d(TAG, "setAvasStreamEnable BUS:" + busType + " " + enable);
    }

    public synchronized boolean checkPlayingRouteByPackage(int type, String pkgName) {
        Log.d(TAG, "checkPlayingRouteByPackage  " + type + " " + pkgName);
        if (type == 1 && hasPassageBtUsbFeature && this.passengerbluetoothConnected) {
            try {
                List<String> sharedPkgJson = mWindowManager.getSharedPackages();
                for (int i = 0; i < sharedPkgJson.size(); i++) {
                    JSONObject mObject = new JSONObject(sharedPkgJson.get(i));
                    int sharedId = ((Integer) mObject.get("sharedId")).intValue();
                    if (sharedId == 1 && pkgName.equals((String) mObject.get("packageName"))) {
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "checkPlayingRouteByPackage " + e);
            }
        }
        return false;
    }

    public void setSessionIdStatus(int sessionId, int position, int status) {
        if (!hasPassageBtUsbFeature) {
            return;
        }
        synchronized (arrayListObject) {
            if (position == 0 || status == 0) {
                this.mPassengerSessionIdList.remove(Integer.valueOf(sessionId));
            } else if (!this.mPassengerSessionIdList.contains(Integer.valueOf(sessionId))) {
                this.mPassengerSessionIdList.add(Integer.valueOf(sessionId));
            }
        }
        sendSessionList();
        sendInvalidateStream();
        setPassengerBtVolume(SystemProperties.getInt("persist.sys.xiaopeng.passengerbtVolume", 10));
    }

    public void setAudioPathWhiteList(int type, String writeList) {
        Log.d(TAG, "setAudioPathWhiteList  " + type + " " + writeList);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void changePassengerBtConnectState() {
        setPassengerPkgList();
        sendSessionList();
        String strUid = "";
        String strSessionId = "";
        synchronized (arrayListObject) {
            ArrayList<Integer> uidList = getPassengerUids();
            if (uidList.size() != 0) {
                StringBuilder mStr = new StringBuilder();
                for (int i = 0; i < uidList.size(); i++) {
                    mStr.append("" + uidList.get(i) + ",");
                }
                strUid = mStr.toString();
            }
            if (this.mPassengerSessionIdList.size() != 0) {
                StringBuilder mStr2 = new StringBuilder();
                for (int i2 = 0; i2 < this.mPassengerSessionIdList.size(); i2++) {
                    mStr2.append("" + this.mPassengerSessionIdList.get(i2) + ",");
                }
                strSessionId = mStr2.toString();
            }
        }
        sendInvalidateStreamWithParam(strUid.equals("") ? null : strUid, strSessionId.equals("") ? null : strSessionId);
    }

    public void setPassengerBtVolume(int index) {
        if (!hasPassageBtUsbFeature) {
            return;
        }
        Log.d(TAG, "setPassengerBtVolume " + index);
        setDuplicateOutMasterVolume(PASSENGERBT, ((float) index) / 30.0f);
    }

    public void setMassageSeatLevel(List<String> levelList) {
        if (hasMassSeat && levelList.size() == 4) {
            String mStr = "MassageSeatLevel=start;FL=" + levelList.get(0) + ";FR=" + levelList.get(1) + ";RL=" + levelList.get(2) + ";RR=" + levelList.get(3) + ";";
            Log.d(TAG, "setMassageSeatLevel " + mStr);
            AudioSystem.setParameters("" + mStr);
        }
    }

    public void setMusicSeatEnable(boolean enable) {
        Log.d(TAG, "setMusicSeatEnable " + enable);
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setMusicSeatEnable(enable);
        }
        if (hasMassSeat) {
            if (massageSeatModel == 1) {
                sendDelayedChangeOutPutMessage();
                sendMassSeatStatus(enable);
                return;
            }
            setBusTransferEnable(MEDIA_BUS, MASSAGESEAT_BUS, enable);
        }
    }

    public void setMusicSeatRythmPause(boolean pause) {
        Log.d(TAG, "setMusicSeatRythmPause " + pause);
        if (hasMassSeat) {
            if (!pause) {
                setMassageSeatShake(pause);
            }
            if (massageSeatModel == 1) {
                setDuplicateOutStreamVolume(BUS08_FRONT_PASSENGER, 16, pause ? 0.0f : 1.0f);
            } else if (pause || getMusicSeatEnable()) {
                setBusTransferEnable(MEDIA_BUS, MASSAGESEAT_BUS, !pause);
            }
            if (pause) {
                setMassageSeatShake(pause);
            }
        }
    }

    public void setMusicSeatEffect(int index) {
        Log.d(TAG, "setMusicSeatEffect:" + index);
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setMusicSeatEffect(index);
        }
        sendMassageSeatEffect(index);
    }

    public int getMusicSeatEffect() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getMusicSeatEffect();
        }
        return 0;
    }

    public boolean getMusicSeatEnable() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getMusicSeatEnable();
        }
        return false;
    }

    public void setMmapToAvasEnable(boolean enable) {
        Log.d(TAG, "setMmapToAvasEnable:" + enable);
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            xuiaudiodata.setMmapToAvasEnable(enable);
        }
        setBusTransferEnable(MMAP_BUS, AVAS_BUS, enable);
    }

    public boolean getMmapToAvasEnable() {
        xuiAudioData xuiaudiodata = mXuiAudioData;
        if (xuiaudiodata != null) {
            return xuiaudiodata.getMmapToAvasEnable();
        }
        return false;
    }

    /* JADX WARN: Removed duplicated region for block: B:17:0x0061 A[Catch: all -> 0x00fe, TryCatch #0 {, blocks: (B:37:0x00fc, B:24:0x009b, B:26:0x00a7, B:29:0x00bc, B:31:0x00c4, B:32:0x00ca, B:34:0x00d2, B:35:0x00f4, B:36:0x00f9, B:28:0x00b3, B:10:0x0038, B:12:0x0044, B:15:0x0059, B:17:0x0061, B:18:0x0067, B:20:0x006f, B:21:0x0091, B:22:0x0096, B:14:0x0050), top: B:42:0x0038 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void setSpecialOutputId(int r8, int r9, boolean r10) {
        /*
            Method dump skipped, instructions count: 257
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiExternalAudioPath.setSpecialOutputId(int, int, boolean):void");
    }

    private void setDuplicateOutputStatus(int status) {
        String key = MusicOnly;
        if (hasAvas && status == 1) {
            key = MusicAndAvas;
        } else if (status == 2 && hasMassSeat && massageSeatModel == 1) {
            key = MusicAndMassageSeat;
        } else if (hasAvas && status == 3) {
            key = MusicTypeOnlyAvas;
        }
        SystemProperties.set("persist.sys.xiaopeng.musicStreamSpecialOutput", key);
        String strUid = "";
        String strSessionId = "";
        synchronized (arrayListObject) {
            ArrayList<Integer> uidList = getPassengerUids();
            if (uidList.size() != 0 && this.passengerbluetoothConnected) {
                StringBuilder mStr = new StringBuilder();
                for (int i = 0; i < uidList.size(); i++) {
                    mStr.append("" + uidList.get(i) + ",");
                }
                strUid = mStr.toString();
            }
            if (this.mPassengerSessionIdList.size() != 0 && this.passengerbluetoothConnected) {
                StringBuilder mStr2 = new StringBuilder();
                for (int i2 = 0; i2 < this.mPassengerSessionIdList.size(); i2++) {
                    mStr2.append("" + this.mPassengerSessionIdList.get(i2) + ",");
                }
                strSessionId = mStr2.toString();
            }
        }
        if (!this.passengerbluetoothConnected || (strUid.equals("") && strSessionId.equals(""))) {
            sendInvalidateStream();
        } else {
            sendInvalidateStreamWithOutParam(strUid, strSessionId);
        }
    }

    public void triggerAvasPlayMute(int position, String path) {
        String pkgName = mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
        Log.i(TAG, "triggerAvasPlayMute " + position + " " + path + " " + pkgName);
        addSoftMuteInfo(3, pkgName, path, position);
        doAvasPlayMute();
    }

    public void releaseAvasPlayMute(int position, String path) {
        String pkgName = mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
        Log.i(TAG, "releaseAvasPlayMute path:" + path + " pkgName:" + pkgName);
        removeSoftMuteInfoByPkgName(3, pkgName, path, position);
        doAvasPlayMute();
    }

    private void doAvasPlayMute() {
        int positions = getSoftMutePositions();
        Log.i(TAG, "doAvasPlayMute exist position:" + positions + " avasSoftConflictMute:" + avasSoftConflictMute);
        if (avasSoftConflictMute) {
            if (positions == 16) {
                setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 3, 0.0f);
                setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 16, 0.0f);
            } else if (positions == 0) {
                setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 16, 1.0f);
                setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 3, 1.0f);
            } else if ((positions & 16) == 16) {
                setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 16, 1.0f);
                setDuplicateOutStreamVolume(BUS16_REAR_SEAT, 3, 0.0f);
            }
        }
    }

    public void setSoftTypeVolumeMute(int type, boolean enable) {
        int callinguid = Binder.getCallingUid();
        Log.i(TAG, "setSoftTypeVolumeMute type:" + type + " enable:" + enable + "  callinguid:" + callinguid);
        if (type == 3) {
            setTrackVolumeWithOutUid(BUS00_MEDIA, 1, enable ? 0.0f : 1.0f, enable ? callinguid : -1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void changeAudioOutputPath() {
        Log.d(TAG, "changeAudioOutputPath()");
        boolean avasEnable = Settings.System.getInt(mContext.getContentResolver(), AVAS_STREAM_ENABLE, 0) == 1;
        if (hasAvas && avasEnable) {
            setDuplicateOutputStatus(3);
        } else if (hasMassSeat && massageSeatModel == 1 && getMusicSeatEnable()) {
            setDuplicateOutputStatus(2);
        } else {
            setDuplicateOutputStatus(0);
        }
    }

    private void openDuplicateThread(String thread1, String thread2) {
        AudioSystem.setParameters("AfMethod=setDuplicateStreamEnable;thread1=" + thread1 + ";thread2=" + thread2);
    }

    private void sendUidList(String str) {
        Log.d(TAG, "sendUidList  " + str);
        if (hasPassageBtUsbFeature) {
            AudioSystem.setParameters("ApsMethod=updateUidList;value=" + str);
            AudioSystem.setParameters("AfMethod=updateUidList;value=" + str);
            Log.d(TAG, "send  AfMethod and ApsMethod");
        }
    }

    private void sendPassengerSystemUidPidList(String str) {
        Log.d(TAG, "sendPassengerSystemUidPidList  " + str);
        if (hasPassageBtUsbFeature) {
            AudioSystem.setParameters("AfMethod=PassengerSystemUidPidList;value=" + str);
            Log.d(TAG, "send  AfMethod");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendSessionList() {
        String str = "";
        synchronized (arrayListObject) {
            if (this.mPassengerSessionIdList.size() != 0 && this.passengerbluetoothConnected) {
                StringBuilder mStr = new StringBuilder();
                for (int i = 0; i < this.mPassengerSessionIdList.size(); i++) {
                    mStr.append("" + this.mPassengerSessionIdList.get(i) + ",");
                }
                str = mStr.toString();
            }
        }
        Log.d(TAG, "sendSessionList  " + this.passengerbluetoothConnected + " str:" + str);
        if (hasPassageBtUsbFeature) {
            AudioSystem.setParameters("ApsMethod=updateSessionIdList;value=" + str);
            AudioSystem.setParameters("AfMethod=updateSessionIdList;value=" + str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInvalidateStream() {
        AudioSystem.setParameters("ApsMethod=invalidateStream;value=music");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInvalidateStreamByPkgName(String pkgName) {
        int uid = getUidByPackageName(pkgName);
        if (uid != -1) {
            sendInvalidateStreamWithParam(String.valueOf(uid), null);
        }
    }

    private void sendInvalidateStreamWithParam(String uidList, String sessionIdList) {
        if (uidList == null && sessionIdList == null) {
            return;
        }
        StringBuilder mStr = new StringBuilder();
        if (uidList != null) {
            mStr.append("uid=" + uidList + ";");
        }
        if (sessionIdList != null) {
            mStr.append("sessionId=" + sessionIdList + ";");
        }
        Log.d(TAG, "sendInvalidateStreamWithParam  ApsMethod=invalidateStreamWithParam;" + mStr.toString());
        StringBuilder sb = new StringBuilder();
        sb.append("ApsMethod=invalidateStreamWithParam;");
        sb.append(mStr.toString());
        AudioSystem.setParameters(sb.toString());
    }

    private void sendInvalidateStreamWithOutParam(String uidList, String sessionIdList) {
        if (uidList == null && sessionIdList == null) {
            return;
        }
        StringBuilder mStr = new StringBuilder();
        if (uidList != null) {
            mStr.append("uid=" + uidList + ";");
        }
        if (sessionIdList != null) {
            mStr.append("sessionId=" + sessionIdList + ";");
        }
        Log.d(TAG, "sendInvalidateStreamWithOutParam  ApsMethod=invalidateStreamWithOutParam;" + mStr.toString());
        StringBuilder sb = new StringBuilder();
        sb.append("ApsMethod=invalidateStreamWithOutParam;");
        sb.append(mStr.toString());
        AudioSystem.setParameters(sb.toString());
    }

    private void setBusTransferEnable(String from, String to, boolean enable) {
        StringBuilder sb = new StringBuilder();
        sb.append("changeBusStatus=");
        sb.append(enable ? xpInputManagerService.InputPolicyKey.KEY_ENABLE : "disable");
        sb.append(";from=");
        sb.append(from);
        sb.append(";to=");
        sb.append(to);
        sb.append(";");
        String mStr = sb.toString();
        Log.d(TAG, "setBusTransferEnable  :" + mStr);
        AudioSystem.setParameters(mStr);
    }

    private void sendSpecialOutPut(int outType, String idlist) {
        Log.d(TAG, "sendSpecialOutPut  " + outType + " " + idlist);
        AudioSystem.setParameters("AfMethod=specialOutput;outType=" + outType + ";idlist=" + idlist + ";");
    }

    private void sendMassageSeatUidList(String idlist) {
        Log.d(TAG, "sendMassageSeatUidList " + idlist);
        if (hasMassSeat && massageSeatModel == 1) {
            AudioSystem.setParameters("AfMethod=updateMassSeatUidList;value=" + idlist + ";");
        }
    }

    private void sendAvasUidList(String idlist) {
        if (!hasAvas || !hasAvasWhiteList) {
            return;
        }
        Log.d(TAG, "sendAvasUidList " + idlist);
        AudioSystem.setParameters("AfMethod=updateAvasUidList;value=" + idlist + ";");
    }

    private void setMassageSeatShake(boolean enable) {
        Log.i(TAG, "setMassageSeatShake  " + enable);
        StringBuilder sb = new StringBuilder();
        sb.append("massageSeatShake=");
        sb.append(enable ? xpInputManagerService.InputPolicyKey.KEY_ENABLE : "disable");
        AudioSystem.setParameters(sb.toString());
    }

    private void setDuplicateOutStreamVolume(String busName, int strType, float vol) {
        Log.d(TAG, "setDuplicateOutStreamVolume  " + busName + ";type=" + strType + ";volume=" + vol + ";");
        AudioSystem.setParameters("AfMethod=setVolume;thread=" + busName + ";type=" + strType + ";volume=" + vol + ";");
    }

    private void setDuplicateOutMasterVolume(String busName, float vol) {
        Log.d(TAG, "setDuplicateOutMasterVolume  " + busName + ";volume=" + vol + ";");
        AudioSystem.setParameters("AfMethod=setMasterVolume;thread=" + busName + ";volume=" + vol + ";");
    }

    private void setTrackVolumeWithOutUid(String busName, int usage, float vol, int uid) {
        Log.d(TAG, "setTrackVolumeWithOutUid  " + busName + "; usage=" + usage + ";volume=" + vol + ";uid=" + uid);
        AudioSystem.setParameters("AfMethod=setVolumeForTracksWithoutOneUid;thread=" + busName + ";usage=" + usage + ";volume=" + vol + ";uid=" + uid + ";");
    }

    private void sendMassSeatStatus(boolean enable) {
        if (hasMassSeat && massageSeatModel == 1 && massageSeatAtmos == 1) {
            AudioSystem.setParameters("AfMethod=triggerMassSeatSw;value=" + (enable ? 1 : 0) + ";");
        }
    }

    private void sendMassageSeatEffect(int index) {
        if (hasMassSeat && mMassageSeatEffectList != null) {
            Log.d(TAG, "sendMassageSeatEffect size:" + mMassageSeatEffectList.size());
            for (int i = 0; i < mMassageSeatEffectList.size(); i++) {
                massageSeatEffect tMassageSeatEffect = mMassageSeatEffectList.get(i);
                if (tMassageSeatEffect.modeIndex == index) {
                    sendMassageSeatEffectDate(tMassageSeatEffect);
                }
            }
        }
    }

    private void sendMassageSeatEffectCreate() {
        if (hasMassSeat) {
            Log.d(TAG, "sendMassageSeatEffectCreate");
            AudioSystem.setParameters("GPF=create;");
        }
    }

    private void sendMassageSeatEffectDate(massageSeatEffect tEffect) {
        if (hasMassSeat && tEffect != null) {
            massageSeatEffect.SeatEffectData[] mData = tEffect.getSeatEffectData();
            if (mData == null) {
                Log.w(TAG, "sendMassageSeatEffectDate mData==null");
                return;
            }
            for (int i = 0; i < mData.length; i++) {
                AudioSystem.setParameters("GPF=calibrate;index=" + mData[i].getIndex() + ";type=" + mData[i].getType() + ";fc=" + mData[i].getFc() + ";gain=" + mData[i].getGain() + ";");
                Log.i(TAG, "sendMassageSeatEffectDate : GPF=calibrate;index=" + mData[i].getIndex() + ";type=" + mData[i].getType() + ";fc=" + mData[i].getFc() + ";gain=" + mData[i].getGain() + ";");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void triggerNativeCarServiceConnect() {
        if (hasAmp) {
            Log.d(TAG, "triggerNativeCarServiceConnect");
            AudioSystem.setParameters("AfMethod=initCarService;");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class SoftVolMuteInfo {
        String outPutBus;
        String packageName;
        String path;
        int position;
        int streamType;

        private SoftVolMuteInfo() {
        }
    }

    private void resetSoftMuteInfo() {
        this.mSoftVolMuteInfoList.clear();
        doAvasPlayMute();
    }

    private void addSoftMuteInfo(int streamType, String pkgName, String path, int position) {
        synchronized (softMuteLock) {
            for (int i = 0; i < this.mSoftVolMuteInfoList.size(); i++) {
                SoftVolMuteInfo tempSoftVolMuteInfo = this.mSoftVolMuteInfoList.get(i);
                if (tempSoftVolMuteInfo.streamType == streamType && position == tempSoftVolMuteInfo.position && (pkgName.equals(tempSoftVolMuteInfo.packageName) || (path != null && path.equals(tempSoftVolMuteInfo.path)))) {
                    return;
                }
            }
            SoftVolMuteInfo newSoftInfo = new SoftVolMuteInfo();
            newSoftInfo.streamType = streamType;
            newSoftInfo.packageName = pkgName;
            newSoftInfo.path = path;
            newSoftInfo.position = position;
            this.mSoftVolMuteInfoList.add(newSoftInfo);
            dumpSoftMuteList("addSoftMuteInfo");
        }
    }

    private void removeSoftMuteInfoByPkgName(int streamType, String pkgName, String path, int position) {
        synchronized (softMuteLock) {
            if (pkgName != null) {
                if (this.mSoftVolMuteInfoList.size() > 0) {
                    int i = this.mSoftVolMuteInfoList.size() - 1;
                    while (true) {
                        if (i < 0) {
                            break;
                        }
                        SoftVolMuteInfo tempSoftVolMuteInfo = this.mSoftVolMuteInfoList.get(i);
                        if ((!pkgName.equals(tempSoftVolMuteInfo.packageName) && (path == null || !path.equals(tempSoftVolMuteInfo.path))) || position != tempSoftVolMuteInfo.position || tempSoftVolMuteInfo.streamType != streamType) {
                            i--;
                        } else {
                            this.mSoftVolMuteInfoList.remove(i);
                            break;
                        }
                    }
                    dumpSoftMuteList("removeSoftMuteInfo");
                }
            }
        }
    }

    private int getSoftMutePositions() {
        int position = 0;
        synchronized (softMuteLock) {
            for (int i = 0; i < this.mSoftVolMuteInfoList.size(); i++) {
                SoftVolMuteInfo tempSoftVolMuteInfo = this.mSoftVolMuteInfoList.get(i);
                position |= tempSoftVolMuteInfo.position;
            }
        }
        return position;
    }

    private SoftVolMuteInfo getSoftMuteInfoByPkg(String pkgName) {
        if (pkgName == null) {
            return null;
        }
        synchronized (softMuteLock) {
            for (int i = 0; i < this.mSoftVolMuteInfoList.size(); i++) {
                SoftVolMuteInfo tempSoftVolMuteInfo = this.mSoftVolMuteInfoList.get(i);
                if (pkgName.equals(tempSoftVolMuteInfo.packageName)) {
                    return tempSoftVolMuteInfo;
                }
            }
            return null;
        }
    }

    private int getSoftMutePositionByPkg(String pkgName) {
        if (pkgName == null) {
            return -1;
        }
        synchronized (softMuteLock) {
            for (int i = 0; i < this.mSoftVolMuteInfoList.size(); i++) {
                SoftVolMuteInfo tempSoftVolMuteInfo = this.mSoftVolMuteInfoList.get(i);
                if (pkgName.equals(tempSoftVolMuteInfo.packageName)) {
                    return tempSoftVolMuteInfo.position;
                }
            }
            return -1;
        }
    }

    private void dumpSoftMuteList(String func) {
        if (xuiAudioPolicy.DEBUG_DUMP_ENABLE) {
            return;
        }
        Log.d(TAG, "dumpSoftMuteList " + func + " size:" + this.mSoftVolMuteInfoList.size());
        for (int i = 0; i < this.mSoftVolMuteInfoList.size(); i++) {
            SoftVolMuteInfo tempSoftVolMuteInfo = this.mSoftVolMuteInfoList.get(i);
            Log.d(TAG, "dumpSoftMuteList i:" + i + " type:" + tempSoftVolMuteInfo.streamType + " " + tempSoftVolMuteInfo.packageName + " " + tempSoftVolMuteInfo.outPutBus);
        }
    }

    public void sendDelayedChangeOutPutMessage() {
        Log.w(TAG, "sendDelayedChangeOutPutMessage");
        removeMessages(2);
        sendMessage(2, 0, 0, null, changeoutputDelayTime);
    }

    private void sendMessage(int msg, int arg1, int arg2, Object object, int delayTime) {
        Handler handler = this.mHandler;
        if (handler != null) {
            Message m = handler.obtainMessage(msg, arg1, arg2, object);
            this.mHandler.sendMessageDelayed(m, delayTime);
        }
    }

    private void removeMessages(int msg) {
        Handler handler = this.mHandler;
        if (handler != null) {
            handler.removeMessages(msg);
        }
    }

    /* loaded from: classes2.dex */
    private class xuiExternalHandler extends Handler {
        public xuiExternalHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            Log.d(xuiExternalAudioPath.TAG, "handleMessage " + msg.what + " " + msg.arg1 + " " + msg.arg2);
            int i = msg.what;
            if (i == 1) {
                xuiExternalAudioPath.this.restoreWithAudioServerCrash();
            } else if (i == 2) {
                xuiExternalAudioPath.this.changeAudioOutputPath();
            }
        }
    }

    private void handleException(String func, Exception e) {
        Log.e(TAG, "" + func + " " + e);
    }
}
