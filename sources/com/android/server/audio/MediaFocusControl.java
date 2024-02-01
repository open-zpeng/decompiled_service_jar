package com.android.server.audio;

import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.IAudioFocusDispatcher;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.audio.AudioEventLogger;
import com.android.server.slice.SliceClientPermissions;
import com.android.xpeng.audio.xpAudio;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.view.ISharedDisplayListener;
import com.xiaopeng.view.SharedDisplayListener;
import com.xiaopeng.xui.xuiaudio.utils.xuiAudioConfig;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicyTrigger;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class MediaFocusControl implements PlayerFocusEnforcer {
    static final boolean DEBUG = true;
    static final int DUCKING_IN_APP_SDK_LEVEL = 25;
    static final boolean ENFORCE_DUCKING = false;
    static final boolean ENFORCE_DUCKING_FOR_NEW = true;
    static final boolean ENFORCE_MUTING_FOR_RING_OR_CALL = true;
    private static boolean FOCUS_DUMP_ENABLE = false;
    private static final String FOCUS_DUMP_SWITCH = "persist.sys.xiaopeng.mediafoucs.dump";
    private static final int FROM_MAIN_TO_PASSENGER = 1;
    private static final int FROM_PASSENGER_TO_MAIN = 0;
    private static final int MAX_STACK_SIZE = 100;
    private static final int RING_CALL_MUTING_ENFORCEMENT_DELAY_MS = 100;
    private static final String TAG = "MediaFocusControl";
    private static final int[] USAGES_TO_MUTE_IN_RING_OR_CALL;
    private static boolean hasPassageBtUsbFeature = false;
    private static final Object mAudioFocusLock;
    private static final AudioEventLogger mEventLogger;
    private static PackageManager mPackageManager = null;
    private static ArrayList<String> mPassengerPkgList = null;
    private static WindowManager mWindowManager = null;
    private static final String packageInDriver = "com.audio.napa.packageInDriver";
    private static final String packageInPassenger = "com.audio.napa.packageInPassenger";
    private static boolean passengerbluetoothConnected;
    private static boolean passengerbluetoothOn;
    private final AppOpsManager mAppOps;
    private final Context mContext;
    @GuardedBy({"mExtFocusChangeLock"})
    private long mExtFocusChangeCounter;
    private PlayerFocusEnforcer mFocusEnforcer;
    private xpAudio mXpAudio;
    private xuiAudioPolicyTrigger mxuiAudioPolicyTrigger;
    private static final AudioAttributes DEFAULT_ATTRIBUTES = new AudioAttributes.Builder().build();
    private static final boolean newPolicyOpen = AudioManager.newPolicyOpen;
    private boolean mRingOrCallActive = false;
    private final Object mExtFocusChangeLock = new Object();
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.android.server.audio.MediaFocusControl.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                String action = intent.getAction();
                if (action == "android.intent.action.BOOT_COMPLETED") {
                    Log.d(MediaFocusControl.TAG, "ACTION_BOOT_COMPLETED");
                    ArrayList unused = MediaFocusControl.mPassengerPkgList = MediaFocusControl.getPassengerPkgs();
                } else if (action == xuiAudioConfig.ACTION_SECOND_BT_CONNECTION) {
                    int state = ((Integer) intent.getExtra("state", 0)).intValue();
                    Log.d(MediaFocusControl.TAG, "onReceive:" + action + "  state:" + state);
                    if (2 == state) {
                        boolean unused2 = MediaFocusControl.passengerbluetoothConnected = true;
                        MediaFocusControl.this.focusStackChange(true);
                    } else if (MediaFocusControl.passengerbluetoothConnected) {
                        MediaFocusControl.this.focusStackChange(false);
                        boolean unused3 = MediaFocusControl.passengerbluetoothConnected = false;
                    }
                }
            }
        }
    };
    private final ISharedDisplayListener mSharedDisplayListener = new SharedDisplayListener() { // from class: com.android.server.audio.MediaFocusControl.3
        public void onChanged(String packageName, int sharedId) {
            Log.d(MediaFocusControl.TAG, "mSharedDisplayListener  onChanged:" + packageName + " sharedId:" + sharedId + " " + MediaFocusControl.this.checkPassengerExist());
            ArrayList unused = MediaFocusControl.mPassengerPkgList = MediaFocusControl.getPassengerPkgs();
            Log.d(MediaFocusControl.TAG, "mSharedDisplayListener  end");
            if (MediaFocusControl.this.checkPassengerExist()) {
                if (sharedId == 1) {
                    MediaFocusControl.this.focusStackMove(packageName, 0, null, 1);
                } else if (sharedId == 0) {
                    MediaFocusControl.this.focusStackMove(packageName, 0, null, 0);
                }
            }
        }

        public void onPositionChanged(String packageName, int event, int from, int to) {
        }
    };
    private String lastFocusAppName = "";
    private final Stack<FocusRequester> mFocusStack = new Stack<>();
    private final Stack<FocusRequester> mPassengerFocusStack = new Stack<>();
    private boolean mNotifyFocusOwnerOnDuck = true;
    private ArrayList<IAudioPolicyCallback> mFocusFollowers = new ArrayList<>();
    @GuardedBy({"mAudioFocusLock"})
    private IAudioPolicyCallback mFocusPolicy = null;
    @GuardedBy({"mAudioFocusLock"})
    private IAudioPolicyCallback mPreviousFocusPolicy = null;
    private HashMap<String, FocusRequester> mFocusOwnersForFocusPolicy = new HashMap<>();
    List<String> mAudioFocusPackageNameList = new ArrayList();

    static {
        hasPassageBtUsbFeature = FeatureOption.FO_PASSENGER_BT_AUDIO_EXIST > 0;
        passengerbluetoothConnected = false;
        passengerbluetoothOn = false;
        FOCUS_DUMP_ENABLE = SystemProperties.getInt(FOCUS_DUMP_SWITCH, 0) == 1;
        mAudioFocusLock = new Object();
        mEventLogger = new AudioEventLogger(50, "focus commands as seen by MediaFocusControl");
        USAGES_TO_MUTE_IN_RING_OR_CALL = new int[]{1, 14};
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public MediaFocusControl(Context cntxt, PlayerFocusEnforcer pfe) {
        this.mContext = cntxt;
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService("appops");
        this.mFocusEnforcer = pfe;
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.BOOT_COMPLETED");
        intentFilter.addAction(xuiAudioConfig.ACTION_SECOND_BT_CONNECTION);
        this.mContext.registerReceiver(this.mReceiver, intentFilter);
        mPackageManager = this.mContext.getPackageManager();
        mWindowManager = (WindowManager) this.mContext.getSystemService("window");
        mWindowManager.registerSharedListener(this.mSharedDisplayListener);
        this.mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor("second_bluetooth_on"), true, new ContentObserver(new Handler()) { // from class: com.android.server.audio.MediaFocusControl.1
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                boolean unused = MediaFocusControl.passengerbluetoothOn = Settings.Global.getInt(MediaFocusControl.this.mContext.getContentResolver(), "second_bluetooth_on", 0) == 1;
                Log.d(MediaFocusControl.TAG, "passengerbluetoothOn change to " + MediaFocusControl.passengerbluetoothOn);
                if (!MediaFocusControl.passengerbluetoothOn) {
                    boolean unused2 = MediaFocusControl.passengerbluetoothConnected = false;
                }
            }
        });
        passengerbluetoothOn = Settings.Global.getInt(this.mContext.getContentResolver(), "second_bluetooth_on", 0) == 1;
        if (!passengerbluetoothOn) {
            passengerbluetoothConnected = false;
        }
        Log.d(TAG, "MediaFocusControl  passengerbluetoothOn:" + passengerbluetoothOn);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void dump(PrintWriter pw) {
        pw.println("\nMediaFocusControl dump time: " + DateFormat.getTimeInstance().format(new Date()));
        dumpFocusStack(pw);
        pw.println("\n");
        mEventLogger.dump(pw);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public boolean duckPlayers(FocusRequester winner, FocusRequester loser, boolean forceDuck) {
        return this.mFocusEnforcer.duckPlayers(winner, loser, forceDuck);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public void unduckPlayers(FocusRequester winner) {
        this.mFocusEnforcer.unduckPlayers(winner);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public void mutePlayersForCall(int[] usagesToMute) {
        this.mFocusEnforcer.mutePlayersForCall(usagesToMute);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public void unmutePlayersForCall() {
        this.mFocusEnforcer.unmutePlayersForCall();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setXpAudio(xpAudio mAudio) {
        this.mXpAudio = mAudio;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setXuiAudio(xuiAudioPolicyTrigger mAudio) {
        this.mxuiAudioPolicyTrigger = mAudio;
    }

    private int checkStreamCanPlay(int streamType) {
        xpAudio xpaudio;
        xuiAudioPolicyTrigger xuiaudiopolicytrigger;
        if (newPolicyOpen && (xuiaudiopolicytrigger = this.mxuiAudioPolicyTrigger) != null) {
            return xuiaudiopolicytrigger.checkStreamCanPlay(streamType);
        }
        if (!newPolicyOpen && (xpaudio = this.mXpAudio) != null) {
            return xpaudio.checkStreamCanPlay(streamType);
        }
        return 2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean checkPassengerExist() {
        if (!hasPassageBtUsbFeature || !passengerbluetoothConnected || mPassengerPkgList == null) {
            return false;
        }
        return true;
    }

    private boolean checkNeverInPassengerPkgs(String pkgName) {
        try {
            xpPackageInfo mPkgInfo = xpPackageManagerService.get(this.mContext).getXpPackageInfo(pkgName);
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

    /* JADX INFO: Access modifiers changed from: private */
    public boolean checkIsPassengerPath(String pkgName, int uid) {
        if (checkPassengerExist()) {
            Log.d(TAG, "checkIsPassengerPath  " + pkgName + " " + uid);
            if (pkgName == null) {
                String[] pkgNameList = this.mContext.getPackageManager().getPackagesForUid(uid);
                pkgName = pkgNameList != null ? pkgNameList[0] : null;
            }
            if (checkNeverInPassengerPkgs(pkgName)) {
                return false;
            }
            if (pkgName.equals(packageInPassenger)) {
                return true;
            }
            for (int i = 0; i < mPassengerPkgList.size(); i++) {
                if (mPassengerPkgList.get(i).contains(pkgName)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private boolean checkIsPassengerByBinder(IBinder cb) {
        if (!checkPassengerExist()) {
            Log.d(TAG, "checkIsPassengerByBinder  PASSENGER NOT EXIST");
            return false;
        }
        synchronized (mAudioFocusLock) {
            Iterator<FocusRequester> stackIterator = this.mPassengerFocusStack.iterator();
            while (stackIterator.hasNext()) {
                FocusRequester fr = stackIterator.next();
                if (FOCUS_DUMP_ENABLE) {
                    Log.d(TAG, "checkIsPassengerByBinder " + fr.getIBinder() + "  cb:" + cb);
                }
                if (fr.hasSameBinder(cb)) {
                    if (FOCUS_DUMP_ENABLE) {
                        Log.d(TAG, "checkIsPassengerByBinder got samebinder!!");
                    }
                    return true;
                }
            }
            return false;
        }
    }

    private boolean checkIsPassengerByClientId(String clientId) {
        if (checkPassengerExist()) {
            synchronized (mAudioFocusLock) {
                Iterator<FocusRequester> stackIterator = this.mPassengerFocusStack.iterator();
                while (stackIterator.hasNext()) {
                    FocusRequester fr = stackIterator.next();
                    Log.d(TAG, "checkIsPassengerByClientId  fr.clientId:" + fr.getClientId() + " clientId:" + clientId);
                    if (fr.hasSameClient(clientId)) {
                        Log.d(TAG, "GOT !! " + clientId);
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    public static ArrayList<String> getPassengerPkgs() {
        List<String> sharedPkgJson;
        Log.i(TAG, "getPassengerUids()");
        ArrayList<String> nameList = new ArrayList<>();
        try {
            sharedPkgJson = mWindowManager.getSharedPackages();
        } catch (Exception e) {
            Log.e(TAG, "getPassengerUids " + e);
        }
        if (sharedPkgJson == null) {
            return nameList;
        }
        for (int i = 0; i < sharedPkgJson.size(); i++) {
            JSONObject mObject = new JSONObject(sharedPkgJson.get(i));
            int sharedId = ((Integer) mObject.get("sharedId")).intValue();
            if (sharedId == 1) {
                String pkgName = (String) mObject.get("packageName");
                Log.d(TAG, "find  pkgName:" + pkgName);
                nameList.add(pkgName);
            }
        }
        return nameList;
    }

    private void dumpStacks(String reason) {
        if (!FOCUS_DUMP_ENABLE) {
            return;
        }
        Log.d(TAG, "dumpStacks  " + reason);
        synchronized (mAudioFocusLock) {
            Log.d(TAG, "dumpStacks   start ######");
            if (this.mFocusStack != null) {
                Iterator<FocusRequester> stackIterator = this.mFocusStack.iterator();
                Log.d(TAG, "dumpStacks   mFocusStack  start:");
                while (stackIterator.hasNext()) {
                    FocusRequester tempFocusOwner = stackIterator.next();
                    Log.d(TAG, "        :" + tempFocusOwner.getPackageName() + " " + tempFocusOwner.getClientUid() + " clientId:" + tempFocusOwner.getClientId() + " " + tempFocusOwner.getGainRequest() + " binderId:" + tempFocusOwner.getIBinder() + " " + tempFocusOwner.getGrantFlags() + " " + tempFocusOwner.getAudioAttributes());
                }
                Log.d(TAG, "dumpStacks   mFocusStack  end");
            }
            if (this.mPassengerFocusStack != null) {
                Iterator<FocusRequester> stackIterator2 = this.mPassengerFocusStack.iterator();
                Log.d(TAG, "dumpStacks   mPassengerFocusStack start:");
                while (stackIterator2.hasNext()) {
                    FocusRequester tempFocusOwner2 = stackIterator2.next();
                    Log.d(TAG, "        :" + tempFocusOwner2.getPackageName() + " " + tempFocusOwner2.getClientUid() + " clientId:" + tempFocusOwner2.getClientId() + " " + tempFocusOwner2.getGainRequest() + " binderId:" + tempFocusOwner2.getIBinder() + " " + tempFocusOwner2.getGrantFlags() + " " + tempFocusOwner2.getAudioAttributes());
                }
                Log.d(TAG, "dumpStacks   mPassengerFocusStack  end");
            }
            Log.d(TAG, "dumpStacks   end ######");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:113:0x03e5, code lost:
        r0.remove();
        r2 = true;
        r1 = r3;
     */
    /* JADX WARN: Code restructure failed: missing block: B:35:0x0194, code lost:
        r20.remove();
        r3 = true;
        r1 = r2;
        android.util.Log.d(com.android.server.audio.MediaFocusControl.TAG, "focusStackMove find uid:" + r30 + " pkgName:" + r29 + " " + r1);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void focusStackMove(java.lang.String r29, int r30, java.lang.String r31, int r32) {
        /*
            Method dump skipped, instructions count: 1109
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.MediaFocusControl.focusStackMove(java.lang.String, int, java.lang.String, int):void");
    }

    private void focusStackMerge() {
        Log.d(TAG, "focusStackMerge()");
        dumpStacks("Before focusStackMerge");
        synchronized (mAudioFocusLock) {
            int stackCount = 0;
            int lastPassLoss = 0;
            Stack<FocusRequester> mTempMainStack = new Stack<>();
            if (!this.mFocusStack.empty() || !this.mPassengerFocusStack.empty()) {
                while (!this.mFocusStack.empty()) {
                    FocusRequester focusOwner = this.mFocusStack.pop();
                    Log.d(TAG, "mFocusStack focusOwner " + focusOwner);
                    if (stackCount < 99) {
                        mTempMainStack.push(focusOwner);
                        stackCount++;
                        lastPassLoss = focusOwner.getGainRequest();
                        if (lastPassLoss == 1) {
                            break;
                        }
                    } else {
                        focusOwner.release();
                    }
                }
                while (!this.mPassengerFocusStack.empty()) {
                    FocusRequester focusOwner2 = this.mPassengerFocusStack.pop();
                    Log.d(TAG, "mPassengerFocusStack focusOwner " + focusOwner2);
                    if (lastPassLoss != 0) {
                        focusOwner2.handleFocusLoss(lastPassLoss * (-1), null, false);
                        if (lastPassLoss != 1) {
                            lastPassLoss = 0;
                        }
                    }
                    if (stackCount < 99) {
                        mTempMainStack.push(focusOwner2);
                        stackCount++;
                        if (focusOwner2.getGainRequest() == 1) {
                            break;
                        }
                    } else {
                        focusOwner2.release();
                    }
                }
            }
            while (!this.mFocusStack.empty()) {
                this.mFocusStack.pop().release();
            }
            while (!this.mPassengerFocusStack.empty()) {
                this.mPassengerFocusStack.pop().release();
            }
            while (!mTempMainStack.empty()) {
                this.mFocusStack.push(mTempMainStack.pop());
            }
        }
        dumpStacks("after focusStackMerge");
    }

    private void focusStackSplit() {
        Log.d(TAG, "focusStackSplit()");
        dumpStacks("Before focusStackSplit");
        synchronized (mAudioFocusLock) {
            Stack<FocusRequester> mTempMainStack = new Stack<>();
            Stack<FocusRequester> mTempPassStack = new Stack<>();
            int count = 0;
            int mainCount = 0;
            int passCount = 0;
            this.mFocusStack.iterator();
            while (!this.mFocusStack.empty()) {
                FocusRequester focusOwner = this.mFocusStack.pop();
                if (checkIsPassengerPath(focusOwner.getPackageName(), focusOwner.getClientUid())) {
                    if (passCount == 0 && count != 0) {
                        focusOwner.handleFocusGain(1);
                    }
                    mTempPassStack.push(focusOwner);
                    passCount++;
                } else {
                    if (mainCount == 0 && count != 0) {
                        focusOwner.handleFocusGain(1);
                    }
                    mTempMainStack.push(focusOwner);
                    mainCount++;
                }
                count++;
            }
            this.mPassengerFocusStack.iterator();
            while (!this.mPassengerFocusStack.empty()) {
                FocusRequester focusOwner2 = this.mPassengerFocusStack.pop();
                focusOwner2.release();
            }
            mTempMainStack.iterator();
            while (!mTempMainStack.empty()) {
                FocusRequester focusOwner3 = mTempMainStack.pop();
                this.mFocusStack.push(focusOwner3);
            }
            mTempPassStack.iterator();
            while (!mTempPassStack.empty()) {
                FocusRequester focusOwner4 = mTempPassStack.pop();
                this.mPassengerFocusStack.push(focusOwner4);
            }
        }
        dumpStacks("After focusStackSplit");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void focusStackChange(boolean isPassengerBtConnected) {
        Log.d(TAG, "focusStackChange  " + isPassengerBtConnected);
        if (isPassengerBtConnected) {
            focusStackSplit();
        } else {
            focusStackMerge();
        }
    }

    public void changeAudioFocusPosition(String clientId, int position) {
        Log.d(TAG, "changeAudioFocusPosition clientId:" + clientId + " position:" + position);
        focusStackMove("", 0, clientId, position);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void noFocusForSuspendedApp(String packageName, int uid) {
        synchronized (mAudioFocusLock) {
            Stack<FocusRequester> mTempStack = this.mFocusStack;
            if (hasPassageBtUsbFeature) {
                boolean isPassengerPath = checkIsPassengerPath(packageName, uid);
                if (isPassengerPath) {
                    mTempStack = this.mPassengerFocusStack;
                }
            }
            Iterator<FocusRequester> stackIterator = mTempStack.iterator();
            List<String> clientsToRemove = new ArrayList<>();
            while (stackIterator.hasNext()) {
                FocusRequester focusOwner = stackIterator.next();
                if (focusOwner.hasSameUid(uid) && focusOwner.hasSamePackage(packageName)) {
                    clientsToRemove.add(focusOwner.getClientId());
                    AudioEventLogger audioEventLogger = mEventLogger;
                    audioEventLogger.log(new AudioEventLogger.StringEvent("focus owner:" + focusOwner.getClientId() + " in uid:" + uid + " pack: " + packageName + " getting AUDIOFOCUS_LOSS due to app suspension").printLog(TAG));
                    focusOwner.dispatchFocusChange(-1);
                }
            }
            for (String clientToRemove : clientsToRemove) {
                removeFocusStackEntry(clientToRemove, false, true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasAudioFocusUsers() {
        synchronized (mAudioFocusLock) {
            Stack<FocusRequester> stack = this.mFocusStack;
            boolean z = true;
            if (hasPassageBtUsbFeature && this.mPassengerFocusStack != null) {
                Log.d(TAG, "hasAudioFocusUsers xui patch  has passenger BT USB");
                if (this.mPassengerFocusStack.empty() || this.mFocusStack.empty()) {
                    z = false;
                }
                return z;
            }
            if (this.mFocusStack.empty()) {
                z = false;
            }
            return z;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void discardAudioFocusOwner() {
        synchronized (mAudioFocusLock) {
            Stack<FocusRequester> mTempStack = this.mFocusStack;
            boolean isPassengerPath = false;
            if (hasPassageBtUsbFeature) {
                Log.d(TAG, "discardAudioFocusOwner  xui patch  " + Binder.getCallingUid());
                isPassengerPath = checkIsPassengerPath(null, Binder.getCallingUid());
                if (isPassengerPath) {
                    mTempStack = this.mPassengerFocusStack;
                }
            }
            if (!mTempStack.empty()) {
                FocusRequester exFocusOwner = isPassengerPath ? this.mPassengerFocusStack.pop() : this.mFocusStack.pop();
                exFocusOwner.handleFocusLoss(-1, null, false);
                exFocusOwner.release();
            }
        }
    }

    @GuardedBy({"mAudioFocusLock"})
    private void notifyTopOfAudioFocusStack(IBinder cb, boolean isPassengerPath) {
        Stack<FocusRequester> mTempStack = this.mFocusStack;
        if (hasPassageBtUsbFeature) {
            dumpStacks("notifyTopOfAudioFocusStack " + isPassengerPath);
            Log.d(TAG, "notifyTopOfAudioFocusStack  xui patch isPassengerPath:" + isPassengerPath + "  " + cb);
            if (isPassengerPath) {
                mTempStack = this.mPassengerFocusStack;
            }
        }
        if (!mTempStack.empty() && canReassignAudioFocus(cb, isPassengerPath)) {
            mTempStack.peek().handleFocusGain(1);
        }
    }

    @GuardedBy({"mAudioFocusLock"})
    private void propagateFocusLossFromGain_syncAf(int focusGain, FocusRequester fr, boolean forceDuck, boolean isPassenger) {
        List<String> clientsToRemove = new LinkedList<>();
        Stack<FocusRequester> mTempStack = this.mFocusStack;
        if (hasPassageBtUsbFeature) {
            Log.d(TAG, "propagateFocusLossFromGain_syncAf  xui patch isPassengers:" + isPassenger);
            if (isPassenger) {
                mTempStack = this.mPassengerFocusStack;
            }
        }
        Iterator<FocusRequester> it = mTempStack.iterator();
        while (it.hasNext()) {
            FocusRequester focusLoser = it.next();
            boolean isDefinitiveLoss = focusLoser.handleFocusLossFromGain(focusGain, fr, forceDuck);
            if (isDefinitiveLoss) {
                clientsToRemove.add(focusLoser.getClientId());
            }
        }
        for (String clientToRemove : clientsToRemove) {
            removeFocusStackEntry(clientToRemove, false, true);
        }
    }

    private void dumpFocusStack(PrintWriter pw) {
        pw.println("\nAudio Focus stack entries (last is top of stack):");
        synchronized (mAudioFocusLock) {
            Iterator<FocusRequester> stackIterator = this.mFocusStack.iterator();
            while (stackIterator.hasNext()) {
                stackIterator.next().dump(pw);
            }
            if (hasPassageBtUsbFeature) {
                pw.println("\n");
                pw.println("\nAudio Focus mPassengerFocusStack entries (last is top of stack):");
                Iterator<FocusRequester> stackIterator2 = this.mPassengerFocusStack.iterator();
                while (stackIterator2.hasNext()) {
                    stackIterator2.next().dump(pw);
                }
            }
            pw.println("\n");
            if (this.mFocusPolicy == null) {
                pw.println("No external focus policy\n");
            } else {
                pw.println("External focus policy: " + this.mFocusPolicy + ", focus owners:\n");
                dumpExtFocusPolicyFocusOwners(pw);
            }
        }
        pw.println("\n");
        pw.println(" Notify on duck:  " + this.mNotifyFocusOwnerOnDuck + "\n");
        pw.println(" In ring or call: " + this.mRingOrCallActive + "\n");
    }

    @GuardedBy({"mAudioFocusLock"})
    private void removeFocusStackEntry(String clientToRemove, boolean signal, boolean notifyFocusFollowers) {
        Stack<FocusRequester> mTempStack = this.mFocusStack;
        boolean isPassengerPath = false;
        if (hasPassageBtUsbFeature) {
            Log.d(TAG, "removeFocusStackEntry  xui patch");
            isPassengerPath = checkIsPassengerByClientId(clientToRemove);
            if (isPassengerPath) {
                mTempStack = this.mPassengerFocusStack;
            }
        }
        if (!mTempStack.empty() && mTempStack.peek().hasSameClient(clientToRemove)) {
            FocusRequester fr = (isPassengerPath ? this.mPassengerFocusStack : this.mFocusStack).pop();
            fr.release();
            if (notifyFocusFollowers) {
                AudioFocusInfo afi = fr.toAudioFocusInfo();
                afi.clearLossReceived();
                notifyExtPolicyFocusLoss_syncAf(afi, false);
            }
            if (signal) {
                notifyTopOfAudioFocusStack(fr.getIBinder(), isPassengerPath);
                return;
            }
            return;
        }
        Iterator<FocusRequester> stackIterator = mTempStack.iterator();
        while (stackIterator.hasNext()) {
            FocusRequester fr2 = stackIterator.next();
            if (fr2.hasSameClient(clientToRemove)) {
                Log.i(TAG, "AudioFocus  removeFocusStackEntry(): removing entry for " + clientToRemove);
                stackIterator.remove();
                fr2.release();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mAudioFocusLock"})
    public void removeFocusStackEntryOnDeath(IBinder cb) {
        Stack<FocusRequester> mTempStack = this.mFocusStack;
        boolean isPassengerPath = false;
        if (hasPassageBtUsbFeature) {
            Log.d(TAG, "removeFocusStackEntryOnDeath  xui patch ");
            isPassengerPath = checkIsPassengerByBinder(cb);
            if (isPassengerPath) {
                mTempStack = this.mPassengerFocusStack;
            }
        }
        boolean isTopOfStackForClientToRemove = !mTempStack.isEmpty() && mTempStack.peek().hasSameBinder(cb);
        Iterator<FocusRequester> stackIterator = mTempStack.iterator();
        while (stackIterator.hasNext()) {
            FocusRequester fr = stackIterator.next();
            if (fr.hasSameBinder(cb)) {
                Log.i(TAG, "AudioFocus  removeFocusStackEntryOnDeath(): removing entry for " + cb);
                stackIterator.remove();
                fr.release();
            }
        }
        if (isTopOfStackForClientToRemove) {
            notifyTopOfAudioFocusStack(cb, isPassengerPath);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mAudioFocusLock"})
    public void removeFocusEntryForExtPolicy(IBinder cb) {
        if (this.mFocusOwnersForFocusPolicy.isEmpty()) {
            return;
        }
        Set<Map.Entry<String, FocusRequester>> owners = this.mFocusOwnersForFocusPolicy.entrySet();
        Iterator<Map.Entry<String, FocusRequester>> ownerIterator = owners.iterator();
        while (ownerIterator.hasNext()) {
            Map.Entry<String, FocusRequester> owner = ownerIterator.next();
            FocusRequester fr = owner.getValue();
            if (fr.hasSameBinder(cb)) {
                ownerIterator.remove();
                fr.release();
                notifyExtFocusPolicyFocusAbandon_syncAf(fr.toAudioFocusInfo());
                return;
            }
        }
    }

    private boolean canReassignAudioFocus(IBinder cb, boolean isPassengerPath) {
        Stack<FocusRequester> mTempStack = this.mFocusStack;
        if (hasPassageBtUsbFeature) {
            Log.d(TAG, "canReassignAudioFocus " + Binder.getCallingUid() + " isPassengerPath:" + isPassengerPath);
            if (isPassengerPath) {
                mTempStack = this.mPassengerFocusStack;
            }
        }
        if (!mTempStack.isEmpty() && isLockedFocusOwner(mTempStack.peek())) {
            return false;
        }
        return true;
    }

    private boolean isLockedFocusOwner(FocusRequester fr) {
        return fr.hasSameClient("AudioFocus_For_Phone_Ring_And_Calls") || fr.isLockedFocusOwner();
    }

    @GuardedBy({"mAudioFocusLock"})
    private int pushBelowLockedFocusOwners(FocusRequester nfr, boolean isPassenger) {
        int lastLockedFocusOwnerIndex = this.mFocusStack.size();
        for (int index = this.mFocusStack.size() - 1; index >= 0; index--) {
            if (isLockedFocusOwner(this.mFocusStack.elementAt(index))) {
                lastLockedFocusOwnerIndex = index;
            }
        }
        if (lastLockedFocusOwnerIndex == this.mFocusStack.size()) {
            Log.e(TAG, "No exclusive focus owner found in propagateFocusLossFromGain_syncAf()", new Exception());
            propagateFocusLossFromGain_syncAf(nfr.getGainRequest(), nfr, false, isPassenger);
            this.mFocusStack.push(nfr);
            return 1;
        }
        this.mFocusStack.insertElementAt(nfr, lastLockedFocusOwnerIndex);
        return 2;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public class AudioFocusDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb;

        AudioFocusDeathHandler(IBinder cb) {
            this.mCb = cb;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (MediaFocusControl.mAudioFocusLock) {
                if (MediaFocusControl.this.mFocusPolicy != null) {
                    MediaFocusControl.this.removeFocusEntryForExtPolicy(this.mCb);
                } else {
                    MediaFocusControl.this.removeFocusStackEntryOnDeath(this.mCb);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setDuckingInExtPolicyAvailable(boolean available) {
        this.mNotifyFocusOwnerOnDuck = !available;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean mustNotifyFocusOwnerOnDuck() {
        return this.mNotifyFocusOwnerOnDuck;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addFocusFollower(IAudioPolicyCallback ff) {
        if (ff == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            boolean found = false;
            Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                IAudioPolicyCallback pcb = it.next();
                if (pcb.asBinder().equals(ff.asBinder())) {
                    found = true;
                    break;
                }
            }
            if (found) {
                return;
            }
            this.mFocusFollowers.add(ff);
            notifyExtPolicyCurrentFocusAsync(ff);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeFocusFollower(IAudioPolicyCallback ff) {
        if (ff == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                IAudioPolicyCallback pcb = it.next();
                if (pcb.asBinder().equals(ff.asBinder())) {
                    this.mFocusFollowers.remove(pcb);
                    break;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFocusPolicy(IAudioPolicyCallback policy, boolean isTestFocusPolicy) {
        if (policy == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            if (isTestFocusPolicy) {
                this.mPreviousFocusPolicy = this.mFocusPolicy;
            }
            this.mFocusPolicy = policy;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unsetFocusPolicy(IAudioPolicyCallback policy, boolean isTestFocusPolicy) {
        if (policy == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            if (this.mFocusPolicy == policy) {
                if (isTestFocusPolicy) {
                    this.mFocusPolicy = this.mPreviousFocusPolicy;
                } else {
                    this.mFocusPolicy = null;
                }
            }
        }
    }

    void notifyExtPolicyCurrentFocusAsync(final IAudioPolicyCallback pcb) {
        Thread thread = new Thread() { // from class: com.android.server.audio.MediaFocusControl.4
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                synchronized (MediaFocusControl.mAudioFocusLock) {
                    Stack<FocusRequester> mTempStack = MediaFocusControl.this.mFocusStack;
                    if (MediaFocusControl.hasPassageBtUsbFeature) {
                        Log.i(MediaFocusControl.TAG, "notifyExtPolicyCurrentFocusAsync xui patch " + pcb + " " + Binder.getCallingUid());
                        boolean isPassengerPath = MediaFocusControl.this.checkIsPassengerPath(null, Binder.getCallingUid());
                        if (isPassengerPath) {
                            mTempStack = MediaFocusControl.this.mPassengerFocusStack;
                        }
                    }
                    if (mTempStack.isEmpty()) {
                        return;
                    }
                    try {
                        pcb.notifyAudioFocusGrant(mTempStack.peek().toAudioFocusInfo(), 1);
                    } catch (RemoteException e) {
                        Log.e(MediaFocusControl.TAG, "Can't call notifyAudioFocusGrant() on IAudioPolicyCallback " + pcb.asBinder(), e);
                    }
                }
            }
        };
        thread.start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyExtPolicyFocusGrant_syncAf(AudioFocusInfo afi, int requestResult) {
        Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
        while (it.hasNext()) {
            IAudioPolicyCallback pcb = it.next();
            try {
                pcb.notifyAudioFocusGrant(afi, requestResult);
            } catch (RemoteException e) {
                Log.e(TAG, "Can't call notifyAudioFocusGrant() on IAudioPolicyCallback " + pcb.asBinder(), e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyExtPolicyFocusLoss_syncAf(AudioFocusInfo afi, boolean wasDispatched) {
        Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
        while (it.hasNext()) {
            IAudioPolicyCallback pcb = it.next();
            try {
                pcb.notifyAudioFocusLoss(afi, wasDispatched);
            } catch (RemoteException e) {
                Log.e(TAG, "Can't call notifyAudioFocusLoss() on IAudioPolicyCallback " + pcb.asBinder(), e);
            }
        }
    }

    boolean notifyExtFocusPolicyFocusRequest_syncAf(AudioFocusInfo afi, IAudioFocusDispatcher fd, IBinder cb) {
        boolean keepTrack;
        Log.v(TAG, "notifyExtFocusPolicyFocusRequest client=" + afi.getClientId() + " dispatcher=" + fd + " mFocusPolicy:" + this.mFocusPolicy);
        synchronized (this.mExtFocusChangeLock) {
            long j = this.mExtFocusChangeCounter;
            this.mExtFocusChangeCounter = 1 + j;
            afi.setGen(j);
        }
        FocusRequester existingFr = this.mFocusOwnersForFocusPolicy.get(afi.getClientId());
        if (existingFr != null) {
            if (existingFr.hasSameDispatcher(fd)) {
                keepTrack = false;
            } else {
                existingFr.release();
                keepTrack = true;
            }
        } else {
            keepTrack = true;
        }
        if (keepTrack) {
            AudioFocusDeathHandler hdlr = new AudioFocusDeathHandler(cb);
            try {
                cb.linkToDeath(hdlr, 0);
                this.mFocusOwnersForFocusPolicy.put(afi.getClientId(), new FocusRequester(afi, fd, cb, hdlr, this));
            } catch (RemoteException e) {
                Log.e(TAG, "notifyExtFocusPolicyFocusRequest  client has already died!");
                return false;
            }
        }
        try {
            this.mFocusPolicy.notifyAudioFocusRequest(afi, 1);
            return true;
        } catch (RemoteException e2) {
            Log.e(TAG, "Can't call notifyAudioFocusRequest() on IAudioPolicyCallback " + this.mFocusPolicy.asBinder(), e2);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFocusRequestResultFromExtPolicy(AudioFocusInfo afi, int requestResult) {
        synchronized (this.mExtFocusChangeLock) {
            if (afi.getGen() > this.mExtFocusChangeCounter) {
                return;
            }
            FocusRequester fr = this.mFocusOwnersForFocusPolicy.get(afi.getClientId());
            if (fr != null) {
                fr.dispatchFocusResultFromExtPolicy(requestResult);
            }
        }
    }

    boolean notifyExtFocusPolicyFocusAbandon_syncAf(AudioFocusInfo afi) {
        if (this.mFocusPolicy == null) {
            return false;
        }
        FocusRequester fr = this.mFocusOwnersForFocusPolicy.remove(afi.getClientId());
        if (fr != null) {
            fr.release();
        }
        try {
            this.mFocusPolicy.notifyAudioFocusAbandon(afi);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call notifyAudioFocusAbandon() on IAudioPolicyCallback " + this.mFocusPolicy.asBinder(), e);
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int dispatchFocusChange(AudioFocusInfo afi, int focusChange) {
        FocusRequester fr;
        Log.v(TAG, "dispatchFocusChange " + focusChange + " to afi client=" + afi.getClientId());
        synchronized (mAudioFocusLock) {
            if (this.mFocusPolicy == null) {
                Log.v(TAG, "> failed: no focus policy");
                return 0;
            }
            if (focusChange == -1) {
                fr = this.mFocusOwnersForFocusPolicy.remove(afi.getClientId());
            } else {
                fr = this.mFocusOwnersForFocusPolicy.get(afi.getClientId());
            }
            if (fr == null) {
                Log.v(TAG, "> failed: no such focus requester known");
                return 0;
            }
            return fr.dispatchFocusChange(focusChange);
        }
    }

    private void dumpExtFocusPolicyFocusOwners(PrintWriter pw) {
        Set<Map.Entry<String, FocusRequester>> owners = this.mFocusOwnersForFocusPolicy.entrySet();
        for (Map.Entry<String, FocusRequester> owner : owners) {
            FocusRequester fr = owner.getValue();
            fr.dump(pw);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public int getCurrentAudioFocus() {
        synchronized (mAudioFocusLock) {
            Stack<FocusRequester> mTempStack = this.mFocusStack;
            if (hasPassageBtUsbFeature) {
                Log.d(TAG, "getCurrentAudioFocus xui patch");
                boolean isPassengerPath = checkIsPassengerPath(null, Binder.getCallingUid());
                if (isPassengerPath) {
                    mTempStack = this.mPassengerFocusStack;
                }
            }
            if (mTempStack.empty()) {
                return 0;
            }
            return mTempStack.peek().getGainRequest();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public String getCurrentAudioFocusPackageName() {
        synchronized (mAudioFocusLock) {
            if (this.mFocusStack.empty()) {
                return "";
            }
            return this.mFocusStack.peek().getPackageName();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public String getLastAudioFocusPackageName() {
        String str;
        synchronized (mAudioFocusLock) {
            str = this.lastFocusAppName;
        }
        return str;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public List<String> getAudioFocusPackageNameList() {
        synchronized (mAudioFocusLock) {
            if (this.mFocusStack.empty()) {
                return null;
            }
            this.mAudioFocusPackageNameList.clear();
            for (int index = this.mFocusStack.size() - 1; index >= 0; index--) {
                this.mAudioFocusPackageNameList.add(this.mFocusStack.elementAt(index).getPackageName());
            }
            return this.mAudioFocusPackageNameList;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public List<String> getAudioFocusPackageNameListByPosition(int position) {
        synchronized (mAudioFocusLock) {
            if (position == 1) {
                try {
                    if (!checkPassengerExist() || this.mPassengerFocusStack.empty()) {
                        return null;
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (position == 0 && this.mFocusStack.empty()) {
                return null;
            }
            this.mAudioFocusPackageNameList.clear();
            if ((position == 1 || position == 2) && !this.mPassengerFocusStack.empty()) {
                for (int index = this.mPassengerFocusStack.size() - 1; index >= 0; index--) {
                    this.mAudioFocusPackageNameList.add(this.mPassengerFocusStack.elementAt(index).getPackageName());
                }
            }
            if ((position == 0 || position == 2) && !this.mFocusStack.empty()) {
                for (int index2 = this.mFocusStack.size() - 1; index2 >= 0; index2--) {
                    this.mAudioFocusPackageNameList.add(this.mFocusStack.elementAt(index2).getPackageName());
                }
            }
            return this.mAudioFocusPackageNameList;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public AudioAttributes getCurrentAudioFocusAttributes() {
        synchronized (mAudioFocusLock) {
            if (this.mFocusStack.empty()) {
                return DEFAULT_ATTRIBUTES;
            }
            return this.mFocusStack.peek().getAudioAttributes();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public static int getFocusRampTimeMs(int focusGain, AudioAttributes attr) {
        switch (attr.getUsage()) {
            case 1:
            case 14:
                return 1000;
            case 2:
            case 3:
            case 5:
            case 7:
            case 8:
            case 9:
            case 10:
            case 13:
                return SystemService.PHASE_SYSTEM_SERVICES_READY;
            case 4:
            case 6:
            case 11:
            case 12:
            case 16:
                return 700;
            case 15:
            default:
                return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public int requestAudioFocus(AudioAttributes aa, int focusChangeHint, IBinder cb, IAudioFocusDispatcher fd, String clientId, int position, int flags, int sdk, boolean forceDuck) {
        return requestAudioFocus(aa, focusChangeHint, cb, fd, clientId, position == 0 ? packageInDriver : packageInPassenger, flags, sdk, forceDuck);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:114:0x0281 A[Catch: all -> 0x02dd, TryCatch #21 {all -> 0x02dd, blocks: (B:112:0x027c, B:114:0x0281, B:116:0x0287, B:117:0x028e, B:118:0x02a5, B:120:0x02a7), top: B:301:0x027c }] */
    /* JADX WARN: Removed duplicated region for block: B:120:0x02a7 A[Catch: all -> 0x02dd, TRY_LEAVE, TryCatch #21 {all -> 0x02dd, blocks: (B:112:0x027c, B:114:0x0281, B:116:0x0287, B:117:0x028e, B:118:0x02a5, B:120:0x02a7), top: B:301:0x027c }] */
    /* JADX WARN: Type inference failed for: r12v2 */
    /* JADX WARN: Type inference failed for: r12v3, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r12v5 */
    /* JADX WARN: Type inference failed for: r8v11 */
    /* JADX WARN: Type inference failed for: r8v7 */
    /* JADX WARN: Type inference failed for: r8v8, types: [boolean, int] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int requestAudioFocus(android.media.AudioAttributes r24, int r25, android.os.IBinder r26, android.media.IAudioFocusDispatcher r27, java.lang.String r28, java.lang.String r29, int r30, int r31, boolean r32) {
        /*
            Method dump skipped, instructions count: 1291
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.MediaFocusControl.requestAudioFocus(android.media.AudioAttributes, int, android.os.IBinder, android.media.IAudioFocusDispatcher, java.lang.String, java.lang.String, int, int, boolean):int");
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public int abandonAudioFocus(IAudioFocusDispatcher fl, String clientId, AudioAttributes aa, String callingPackageName) {
        AudioEventLogger audioEventLogger = mEventLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("abandonAudioFocus() from uid/pid " + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid() + " clientId=" + clientId).printLog(TAG));
        try {
        } catch (ConcurrentModificationException cme) {
            Log.e(TAG, "FATAL EXCEPTION AudioFocus  abandonAudioFocus() caused " + cme);
            cme.printStackTrace();
        }
        synchronized (mAudioFocusLock) {
            if (this.mFocusPolicy != null) {
                AudioFocusInfo afi = new AudioFocusInfo(aa, Binder.getCallingUid(), clientId, callingPackageName, 0, 0, 0, 0);
                if (notifyExtFocusPolicyFocusAbandon_syncAf(afi)) {
                    dumpStacks("abandonAudioFocus");
                    return 1;
                }
            }
            boolean exitingRingOrCall = this.mRingOrCallActive & ("AudioFocus_For_Phone_Ring_And_Calls".compareTo(clientId) == 0);
            if (exitingRingOrCall) {
                this.mRingOrCallActive = false;
            }
            removeFocusStackEntry(clientId, true, true);
            if (exitingRingOrCall & true) {
                runAudioCheckerForRingOrCallAsync(false);
            }
            dumpStacks("abandonAudioFocus");
            return 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void unregisterAudioFocusClient(String clientId) {
        synchronized (mAudioFocusLock) {
            removeFocusStackEntry(clientId, false, true);
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.audio.MediaFocusControl$5] */
    private void runAudioCheckerForRingOrCallAsync(final boolean enteringRingOrCall) {
        new Thread() { // from class: com.android.server.audio.MediaFocusControl.5
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                if (enteringRingOrCall) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                synchronized (MediaFocusControl.mAudioFocusLock) {
                    if (MediaFocusControl.this.mRingOrCallActive) {
                        MediaFocusControl.this.mFocusEnforcer.mutePlayersForCall(MediaFocusControl.USAGES_TO_MUTE_IN_RING_OR_CALL);
                    } else {
                        MediaFocusControl.this.mFocusEnforcer.unmutePlayersForCall();
                    }
                }
            }
        }.start();
    }
}
