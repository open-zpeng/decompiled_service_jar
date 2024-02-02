package com.xiaopeng.xui.xuiaudio;

import android.app.ActivityManager;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioSystem;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;
import com.android.server.backup.BackupManagerConstants;
import com.xiaopeng.XpEffectSet;
import com.xiaopeng.audio.xpAudioSessionInfo;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import com.xiaopeng.xui.xuiaudio.utils.remoteConnect;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.xuiChannelPolicy;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.xuiVolumePolicy;
import com.xiaopeng.xuimanager.mediacenter.IMediaCenter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
/* loaded from: classes.dex */
public class xuiAudioPolicy implements remoteConnect.RemoteConnectListerer {
    private static final String ACTION_POWER_STATE_CHANGED = "com.xiaopeng.intent.action.XUI_POWER_STATE_CHANGED";
    public static final int BTCALL_CALLING_OUT = 3;
    public static final int BTCALL_CONNECTTED = 2;
    public static final int BTCALL_NOT_CONNECTTED = 0;
    public static final int BTCALL_RINGING = 1;
    private static final String BluetoothPkgName = "com.android.bluetooth";
    public static final int BtCALLMODE_CDU = 1;
    public static final int BtCALLMODE_PHONE = 0;
    private static final String EXTRA_IG_STATE = "android.intent.extra.IG_STATE";
    private static final int MESSAGE_APPLYUSAGE = 1;
    private static final int MESSAGE_CLEARFLAGS = 3;
    private static final int MESSAGE_RELEASEUSAGE = 2;
    private static final String SETTINGS_PACKAGE = "com.xiaopeng.car.settings";
    public static final String STREAM_DANGEROUS = "STREAM_DANGEROUS";
    public static final String STREAM_KARAOKE = "STREAM_KARAOKE";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String TAG = "xuiAudioPolicy";
    private static Context mContext = null;
    private static remoteConnect mRemoteConnect = null;
    private static xuiChannelPolicy mXuiChannelPolicy = null;
    private static xuiVolumePolicy mXuiVolumePolicy = null;
    private static final String testAction = "audio.playbacktest.action.xuitest";
    private List<Integer>[] mAlarmIDs;
    private long mApplyTtsTimeTick;
    Handler mHandler;
    private HandlerThread mHandlerThread;
    private IMediaCenter mMcService;
    private XpEffectSet mXpEffectSet;
    public static int STREAM_TYPE_DANGEROUS = 11;
    public static int STREAM_TYPE_KARAOKE = 12;
    private static Object SessionMapLock = new Object();
    public static final Map<Integer, Integer> streamtypeMap = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioPolicy.1
        {
            put(0, 2);
            put(1, 13);
            put(2, 6);
            put(3, 1);
            put(4, 4);
            put(5, 5);
            put(6, 2);
            put(7, 10);
            put(8, 3);
            put(9, 12);
            put(10, 11);
        }
    };
    public static final Map<Integer, Integer> audioAtrributeMap = new HashMap<Integer, Integer>() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioPolicy.2
        {
            put(1, 3);
            put(2, 0);
            put(3, 0);
            put(4, 4);
            put(5, 5);
            put(6, 2);
            put(7, 5);
            put(8, 5);
            put(9, 5);
            put(10, 7);
            put(11, 10);
            put(12, 9);
            put(13, 1);
            put(14, 3);
            put(15, 5);
            put(16, 10);
        }
    };
    private static int AudioFeature = 0;
    private static long lastBindXuiServiceTime = 0;
    private static boolean KaraokeSysVolMode = false;
    private ConcurrentHashMap<Integer, String> XiaoPengMusicSessionMap = new ConcurrentHashMap<>();
    private SparseArray<xpAudioSessionInfo> mActiveSessionArray = new SparseArray<>();
    private final String XiaoPengPkgName = "com.xiaopeng.musicradio";
    private final String NativePkgName = "android.uid.system:1000";
    private final String CarSettingPkgName = SETTINGS_PACKAGE;
    private final String BluetoothPackage = BluetoothPkgName;
    private boolean isInKaraoke = false;
    private int btcallMode = 0;
    private int mBtCallFlag = 0;
    private final int alarmUsageSize = 2;
    private final int alarmUsage_Alarm = 0;
    private final int alarmUsage_System = 1;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.xiaopeng.xui.xuiaudio.xuiAudioPolicy.3
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if (action.equals("android.intent.action.BOOT_COMPLETED")) {
                    Log.d(xuiAudioPolicy.TAG, "ACTION_BOOT_COMPLETED");
                    if (xuiAudioPolicy.this.mMcService == null) {
                        xuiAudioPolicy.this.BindXUIService();
                    }
                } else if (!"android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED".equals(action)) {
                    if (xuiAudioPolicy.ACTION_POWER_STATE_CHANGED.equals(action)) {
                        int igState = intent.getIntExtra(xuiAudioPolicy.EXTRA_IG_STATE, -1);
                        if (igState == 0 || igState == 1) {
                            xuiAudioPolicy.this.igStatusChange(igState);
                        }
                    } else if (xuiAudioPolicy.testAction.equals(action)) {
                        Log.d(xuiAudioPolicy.TAG, "TRY TO SET TEST ACTION");
                    }
                } else {
                    BluetoothHeadsetClientCall mCall = (BluetoothHeadsetClientCall) intent.getExtra("android.bluetooth.headsetclient.extra.CALL", null);
                    if (mCall != null) {
                        int callState = mCall.getState();
                        Log.d(xuiAudioPolicy.TAG, "when call status changes: callState " + callState + " number == " + mCall.getNumber());
                        if (callState == 4) {
                            xuiAudioPolicy.this.setCallingIn(true);
                        } else if (callState == 2) {
                            xuiAudioPolicy.this.setCallingOut(true);
                        } else if (callState != 0 && callState == 7) {
                            xuiAudioPolicy.this.setCallingOut(false);
                            xuiAudioPolicy.this.setCallingIn(false);
                        }
                    }
                }
            }
        }
    };
    private List<Integer> mRingtionBanSessions = new CopyOnWriteArrayList();

    public xuiAudioPolicy(Context context) {
        this.mXpEffectSet = null;
        this.mHandlerThread = null;
        mContext = context;
        mRemoteConnect = remoteConnect.getInstance(context);
        mRemoteConnect.registerListener(this);
        mXuiVolumePolicy = xuiVolumePolicy.getInstance(context, this);
        mXuiChannelPolicy = xuiChannelPolicy.getInstance(context, this);
        registerReceiver();
        policyTriggerThread task = new policyTriggerThread();
        task.start();
        if (this.mHandlerThread == null) {
            this.mHandlerThread = new HandlerThread(TAG);
        }
        this.mHandlerThread.start();
        this.mHandler = new xuiAudioPolicyHandler(this.mHandlerThread.getLooper());
        this.mAlarmIDs = new ArrayList[2];
        for (int i = 0; i < 2; i++) {
            this.mAlarmIDs[i] = new ArrayList();
        }
        this.mXpEffectSet = new XpEffectSet();
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onCarServiceConnected() {
    }

    @Override // com.xiaopeng.xui.xuiaudio.utils.remoteConnect.RemoteConnectListerer
    public void onXuiServiceConnected() {
        Log.d(TAG, "onXuiServiceConnected()");
        if (mRemoteConnect != null) {
            this.mMcService = mRemoteConnect.getMediaCenter();
        }
    }

    private void registerReceiver() {
        Log.i(TAG, "registerReceiver");
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction("android.intent.action.BOOT_COMPLETED");
        mIntentFilter.addAction(ACTION_POWER_STATE_CHANGED);
        mIntentFilter.addAction("android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED");
        mIntentFilter.addAction(testAction);
        mContext.registerReceiver(this.mReceiver, mIntentFilter);
    }

    public void unregisterReceiver() {
        Log.i(TAG, "unregisterReceiver.");
        mContext.unregisterReceiver(this.mReceiver);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void BindXUIService() {
        long currentTime = System.currentTimeMillis();
        Log.i(TAG, "BindXUIService() " + currentTime);
        if (Math.abs(currentTime - lastBindXuiServiceTime) > 5000) {
            mRemoteConnect.BindXUIService();
        }
    }

    /* loaded from: classes.dex */
    public class policyTriggerThread extends Thread {
        public policyTriggerThread() {
        }

        /* JADX WARN: Removed duplicated region for block: B:29:0x00fa A[ADDED_TO_REGION] */
        /* JADX WARN: Removed duplicated region for block: B:34:0x010c A[ADDED_TO_REGION] */
        /* JADX WARN: Removed duplicated region for block: B:38:0x011e A[ADDED_TO_REGION] */
        /* JADX WARN: Removed duplicated region for block: B:43:0x0130 A[ADDED_TO_REGION] */
        @Override // java.lang.Thread, java.lang.Runnable
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void run() {
            /*
                Method dump skipped, instructions count: 337
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.xui.xuiaudio.xuiAudioPolicy.policyTriggerThread.run():void");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getSessionIdFromProp(String prop) {
        String[] strarray = prop.split(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
        try {
            Log.d(TAG, "getSessionIdFromProp pid:" + strarray[0] + " sessionid:" + strarray[1] + " " + prop);
            return Integer.parseInt(strarray[1]);
        } catch (Exception e) {
            handleException("getSessionIdFromProp", e);
            return 0;
        }
    }

    private String getAppName(int pID) {
        String processName = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        ActivityManager am = (ActivityManager) mContext.getSystemService("activity");
        List l = am.getRunningAppProcesses();
        mContext.getPackageManager();
        for (ActivityManager.RunningAppProcessInfo info : l) {
            try {
                if (info.pid == pID) {
                    processName = info.processName;
                }
            } catch (Exception e) {
                handleException("getAppName", e);
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
            handleException("getPackageNameFromProp", e);
            return "xpAudio";
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
        applyUsage(1, 0);
        SystemProperties.set("void.mediaaudio.play", "-2");
        setRingtoneSessionId(3, Sessionid, pkgName);
        if (!checkIsRingtoneBanSession(Sessionid)) {
            startAudioCapture(Sessionid, 1, pkgName);
        }
    }

    private void triggerNativeStop(int Sessionid, String pkgName) {
        releaseUsage(1, 0);
        SystemProperties.set("void.mediaaudio.stop", "-2");
        if (!checkIsRingtoneBanSession(Sessionid)) {
            stopAudioCapture(Sessionid, 1, pkgName);
        }
        removeRingtoneBanSession(Sessionid);
    }

    public void setRingtoneSessionId(int StreamType, int sessionId, String pkgName) {
        Log.i(TAG, "setRingtoneSessionId " + StreamType + " " + sessionId + " " + pkgName);
        if (StreamType == 3) {
            if ((SETTINGS_PACKAGE.equals(pkgName) || "com.android.systemui".equals(pkgName)) && !this.mRingtionBanSessions.contains(Integer.valueOf(sessionId))) {
                this.mRingtionBanSessions.add(Integer.valueOf(sessionId));
            }
        }
    }

    private boolean checkIsRingtoneBanSession(int sessionid) {
        for (int i = 0; i < this.mRingtionBanSessions.size(); i++) {
            Log.i(TAG, "checkIsRingtoneBanSession: " + this.mRingtionBanSessions.get(i));
        }
        return this.mRingtionBanSessions.contains(Integer.valueOf(sessionid));
    }

    private void removeRingtoneBanSession(int sessionid) {
        if (this.mRingtionBanSessions.contains(Integer.valueOf(sessionid))) {
            Log.i(TAG, "removeRingtoneBanSession " + sessionid);
            this.mRingtionBanSessions.remove(Integer.valueOf(sessionid));
        }
    }

    private boolean isSessionIdActive(int audioSession) {
        return this.XiaoPengMusicSessionMap.containsKey(Integer.valueOf(audioSession));
    }

    private void addSessionMap(int audioSession, String pkgName) {
        synchronized (SessionMapLock) {
            if (!isSessionIdActive(audioSession) && !pkgName.contains("com.xiaopeng.musicradio") && !pkgName.contains("android.uid.system:1000") && !pkgName.contains(SETTINGS_PACKAGE) && !pkgName.contains(BluetoothPkgName)) {
                Log.d(TAG, "addSessionMap " + audioSession + " pkgName:" + pkgName);
                this.XiaoPengMusicSessionMap.put(Integer.valueOf(audioSession), pkgName);
            }
        }
    }

    private void removeSessionMap(int audioSession, String pkgName) {
        synchronized (SessionMapLock) {
            if (isSessionIdActive(audioSession) && !pkgName.contains("com.xiaopeng.musicradio") && !pkgName.contains("android.uid.system:1000") && !pkgName.contains(SETTINGS_PACKAGE) && !pkgName.contains(BluetoothPkgName)) {
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

    private boolean isLegalUsage(int usage) {
        if (usage > 0 && usage <= 16) {
            return true;
        }
        return false;
    }

    private void setKaraokeModeSysVolume(boolean enable) {
        if (KaraokeSysVolMode == enable) {
            return;
        }
        Log.i(TAG, "setKaraokeModeSysVolume  " + enable);
        KaraokeSysVolMode = enable;
        if (enable) {
            mXuiVolumePolicy.forceSetStreamVolumeByStr(STREAM_KARAOKE);
        } else {
            mXuiVolumePolicy.forceSetStreamVolume(1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void triggerKaraokeModeSysVolume() {
        Log.d(TAG, "triggerKaraokeModeSysVolume " + isKaraokeOn() + " " + getAlarmIdSize(0) + " " + getAlarmIdSize(1));
        if (isKaraokeOn() && getAlarmIdSize(4) == 0) {
            setKaraokeModeSysVolume(true);
        } else {
            setKaraokeModeSysVolume(false);
        }
    }

    public void checkAlarmVolume() {
        setKaraokeModeSysVolume(false);
    }

    private void btBroadcastPhoneState(boolean on) {
        Intent action_phone_changed = new Intent("android.intent.action.PHONE_STATE");
        long ident = Binder.clearCallingIdentity();
        Log.d(TAG, "btBroadcastPhoneState  " + on + "  " + getMainDriverMode());
        if (getMainDriverMode() != 1) {
            try {
                try {
                    SystemProperties.set("audio.telephony.callstate", on ? "1" : "0");
                    mContext.sendBroadcastAsUser(action_phone_changed, UserHandle.ALL);
                } catch (Exception e) {
                    Log.e(TAG, "broadcastVolumeChangedToAll " + e);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean checkUsageValid(int usage) {
        if (usage >= 1 && usage <= 16) {
            return true;
        }
        Log.w(TAG, "checkUsageValid  " + usage + "  NOT valid");
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleException(String func, Exception e) {
        Log.e(TAG, "handleException " + func + " " + e);
        e.printStackTrace();
    }

    public void setCallingOut(boolean flag) {
    }

    public void setCallingIn(boolean flag) {
    }

    public void igStatusChange(int status) {
        Log.i(TAG, "igStatusChange() " + status);
        if (status == 1) {
            setBtCallOnFlag(0);
            AudioSystem.setParameters("IG_ON=true");
            setCallingOut(false);
            setCallingIn(false);
        } else if (status == 0) {
            this.btcallMode = 0;
            SystemProperties.set("audio.telephony.callstate", "0");
        }
        if (isKaraokeOn()) {
            setKaraokeOn(false);
        }
        synchronized (SessionMapLock) {
            if (this.XiaoPengMusicSessionMap != null) {
                this.XiaoPengMusicSessionMap.clear();
            }
        }
        setKaraokeModeSysVolume(false);
        if (mXuiVolumePolicy != null) {
            mXuiVolumePolicy.igStatusChange(status);
        }
        if (mXuiChannelPolicy != null) {
            mXuiChannelPolicy.igStatusChange(status);
        }
    }

    public boolean checkStreamActive(int streamType) {
        return false;
    }

    public boolean isAnyStreamActive() {
        if (isFmOn()) {
            Log.d(TAG, "isAnyStreamActive() fm is on");
            return true;
        } else if (isKaraokeOn() || isBtCallOn()) {
            return true;
        } else {
            for (int i = 0; i <= 10; i++) {
                if ((i == 0 || i == 3 || i == 6 || i == 9 || i == 10) && AudioSystem.isStreamActive(i, 0)) {
                    Log.d(TAG, "isAnyStreamActive() TYPE:" + i + " is active");
                    return true;
                }
            }
            return false;
        }
    }

    public int checkStreamCanPlay(int streamType) {
        int ret = 2;
        if (streamType != 3) {
            switch (streamType) {
                case 9:
                    if (isBtCallOn()) {
                        ret = 0;
                        break;
                    } else if (getMainDriverMode() == 1) {
                        ret = 1;
                        break;
                    }
                    break;
                case 10:
                    if (isBtCallOn()) {
                        ret = 0;
                        break;
                    }
                    break;
            }
        } else if (isBtCallOn()) {
            ret = 0;
        }
        Log.i(TAG, "checkStreamCanPlay streamType:" + streamType + "  return=" + ret);
        return ret;
    }

    public void startAudioCapture(int audioSession, int usage) {
        String pkgName = mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
        startAudioCapture(audioSession, usage, pkgName);
    }

    private void startAudioCapture(int audioSession, int usage, String pkgName) {
        Log.i(TAG, "startAudioCapture " + audioSession + " packageName:" + pkgName + " usage:" + usage);
        if (this.mMcService != null && mRemoteConnect != null && mRemoteConnect.isXuiConnected() && !checkIsRingtoneBanSession(audioSession)) {
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
        String pkgName = mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
        stopAudioCapture(audioSession, usage, pkgName);
    }

    public void stopAudioCapture(int audioSession, int usage, String pkgName) {
        Log.i(TAG, "stopAudioCapture  " + audioSession + "  packageName:" + pkgName + " usage:" + usage);
        if (this.mMcService != null && mRemoteConnect != null && mRemoteConnect.isXuiConnected() && !checkIsRingtoneBanSession(audioSession)) {
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

    public void startSpeechEffect(int audioSession) {
        try {
            if (this.mXpEffectSet != null) {
                this.mXpEffectSet.setEffect(true, audioSession);
            }
        } catch (Exception e) {
            handleException("startSpeechEffect", e);
        }
    }

    public void stopSpeechEffect(int audioSession) {
        try {
            if (this.mXpEffectSet != null) {
                this.mXpEffectSet.setEffect(false, audioSession);
            }
        } catch (Exception e) {
            handleException("stopSpeechEffect", e);
        }
    }

    public int applyUsage(int usage, int id) {
        if (isLegalUsage(usage)) {
            if (usage == 12) {
                this.mApplyTtsTimeTick = System.currentTimeMillis();
            }
            if (this.mHandler != null) {
                Message m = this.mHandler.obtainMessage(1, usage, id, 0);
                this.mHandler.sendMessage(m);
            }
            return -1;
        }
        return -1;
    }

    public int releaseUsage(int usage, int id) {
        int timeflag = 0;
        if (usage == 12 && Math.abs(System.currentTimeMillis() - this.mApplyTtsTimeTick) < 200) {
            timeflag = -1;
        }
        if (this.mHandler != null) {
            Message m = this.mHandler.obtainMessage(2, usage, id, Integer.valueOf(timeflag));
            this.mHandler.sendMessage(m);
            return -1;
        }
        return -1;
    }

    public boolean isUsageActive(int usage) {
        Log.e(TAG, "isUsageActive  " + usage + "  INTERFACE NOT UESED!!!");
        return false;
    }

    public void setBtCallOn(boolean enable) {
        Log.e(TAG, "setBtCallOn INTERFACE NOT USED!!");
    }

    public void setBtCallOnFlag(int flag) {
        Log.d(TAG, "setBtCallOnFlag " + flag + " btcallMode:" + this.btcallMode);
        this.mBtCallFlag = flag;
        if (mXuiChannelPolicy != null) {
            mXuiChannelPolicy.setBtCallOnFlag(flag);
        }
        if (mXuiVolumePolicy != null) {
            mXuiVolumePolicy.setBtCallOnFlag(flag);
        }
        if (flag == 0) {
            btBroadcastPhoneState(false);
        } else {
            btBroadcastPhoneState(true);
        }
    }

    public int getBtCallOnFlag() {
        return this.mBtCallFlag;
    }

    public void setBtCallMode(int mode) {
        Log.d(TAG, "setBtCallMode " + mode + " mBtCallFlag:" + this.mBtCallFlag);
        this.btcallMode = mode;
        setBtCallOnFlag(this.mBtCallFlag);
    }

    public int getBtCallMode() {
        return this.btcallMode;
    }

    public boolean isBtCallOn() {
        return this.mBtCallFlag == 2;
    }

    public boolean isBtCallConnectOrCallout() {
        return this.mBtCallFlag == 2 || this.mBtCallFlag == 3;
    }

    public void setKaraokeOn(boolean on) {
        this.isInKaraoke = on;
        String str = on ? "On" : "Off";
        AudioSystem.setParameters("Karaoke=" + str);
        triggerKaraokeModeSysVolume();
        if (mXuiChannelPolicy != null) {
            mXuiChannelPolicy.triggerChannelChange(STREAM_TYPE_KARAOKE, 0, on ? 1 : 0);
        }
    }

    public boolean isKaraokeOn() {
        return this.isInKaraoke;
    }

    public boolean isFmOn() {
        return false;
    }

    public void playbackControl(int cmd, int param) {
        Log.d(TAG, "playbackControl  cmd=" + cmd + "  param=" + param);
        if (this.mMcService != null && mRemoteConnect != null && mRemoteConnect.isXuiConnected()) {
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

    public void setDangerousTtsStatus(int on) {
        if (mXuiVolumePolicy != null) {
            mXuiVolumePolicy.setDangerousTtsStatus(on);
        }
    }

    public int getDangerousTtsStatus() {
        if (mXuiVolumePolicy != null) {
            return mXuiVolumePolicy.getDangerousTtsStatus();
        }
        return 0;
    }

    public void setMainDriverMode(int mode) {
        if (mXuiChannelPolicy != null) {
            mXuiChannelPolicy.setMainDriverMode(mode);
        }
    }

    public int getMainDriverMode() {
        if (mXuiChannelPolicy != null) {
            return mXuiChannelPolicy.getMainDriverMode();
        }
        return 0;
    }

    public void applyAlarmId(int usage, int id) {
        if (this.mAlarmIDs == null) {
            return;
        }
        if (usage == 4 && !this.mAlarmIDs[0].contains(Integer.valueOf(id))) {
            this.mAlarmIDs[0].add(Integer.valueOf(id));
        } else if (usage == 13 && !this.mAlarmIDs[1].contains(Integer.valueOf(id))) {
            this.mAlarmIDs[1].add(Integer.valueOf(id));
        }
    }

    public int getAlarmIdSize(int usage) {
        if (this.mAlarmIDs == null) {
            return 0;
        }
        if (usage == 4) {
            return this.mAlarmIDs[0].size();
        }
        if (usage == 13) {
            return this.mAlarmIDs[1].size();
        }
        return 0;
    }

    public void removeAlarmId(int usage, int id) {
        if (this.mAlarmIDs == null) {
            return;
        }
        if (usage == 4 && this.mAlarmIDs[0].contains(Integer.valueOf(id))) {
            this.mAlarmIDs[0].remove(Integer.valueOf(id));
        } else if (usage == 13 && this.mAlarmIDs[1].contains(Integer.valueOf(id))) {
            this.mAlarmIDs[1].remove(Integer.valueOf(id));
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
    public class xuiAudioPolicyHandler extends Handler {
        public xuiAudioPolicyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            Log.d(xuiAudioPolicy.TAG, "handleMessage " + msg.what + " " + msg.arg1 + " " + msg.arg2);
            switch (msg.what) {
                case 1:
                    int usage = msg.arg1;
                    int id = msg.arg2;
                    if (!xuiAudioPolicy.this.checkUsageValid(usage)) {
                        return;
                    }
                    if (usage == 4 || usage == 13) {
                        xuiAudioPolicy.this.applyAlarmId(usage, id);
                    }
                    xuiAudioPolicy.this.triggerKaraokeModeSysVolume();
                    if (xuiAudioPolicy.mXuiChannelPolicy != null) {
                        xuiAudioPolicy.mXuiChannelPolicy.triggerChannelChange(xuiAudioPolicy.audioAtrributeMap.get(Integer.valueOf(usage)).intValue(), id, 1);
                        return;
                    }
                    return;
                case 2:
                    int usage2 = msg.arg1;
                    int id2 = msg.arg2;
                    if (!xuiAudioPolicy.this.checkUsageValid(usage2)) {
                        return;
                    }
                    ((Integer) msg.obj).intValue();
                    if (usage2 == 4 || usage2 == 13) {
                        xuiAudioPolicy.this.removeAlarmId(usage2, id2);
                    }
                    xuiAudioPolicy.this.triggerKaraokeModeSysVolume();
                    if (xuiAudioPolicy.mXuiChannelPolicy != null) {
                        xuiAudioPolicy.mXuiChannelPolicy.triggerChannelChange(xuiAudioPolicy.audioAtrributeMap.get(Integer.valueOf(usage2)).intValue(), id2, 0);
                        return;
                    }
                    return;
                default:
                    return;
            }
        }
    }
}
