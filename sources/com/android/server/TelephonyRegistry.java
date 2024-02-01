package com.android.server;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.CallAttributes;
import android.telephony.CallQuality;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.LocationAccessPolicy;
import android.telephony.PhoneCapability;
import android.telephony.PhysicalChannelConfig;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;
import android.util.LocalLog;
import android.util.StatsLog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.IPhoneStateListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstantConversions;
import com.android.internal.telephony.TelephonyPermissions;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.TelephonyRegistry;
import com.android.server.am.BatteryStatsService;
import com.android.server.pm.DumpState;
import com.xiaopeng.server.input.xpInputActionHandler;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
/* loaded from: classes.dex */
public class TelephonyRegistry extends ITelephonyRegistry.Stub {
    private static final boolean DBG = false;
    private static final boolean DBG_LOC = false;
    static final int ENFORCE_LOCATION_PERMISSION_MASK = 1040;
    static final int ENFORCE_PHONE_STATE_PERMISSION_MASK = 16777228;
    private static final int MSG_UPDATE_DEFAULT_SUB = 2;
    private static final int MSG_USER_SWITCHED = 1;
    static final int PRECISE_PHONE_STATE_PERMISSION_MASK = 6144;
    private static final String TAG = "TelephonyRegistry";
    private static final boolean VDBG = false;
    private final AppOpsManager mAppOps;
    private int[] mBackgroundCallState;
    private final IBatteryStats mBatteryStats;
    private CallAttributes[] mCallAttributes;
    private int[] mCallDisconnectCause;
    private boolean[] mCallForwarding;
    private String[] mCallIncomingNumber;
    private int[] mCallNetworkType;
    private int[] mCallPreciseDisconnectCause;
    private CallQuality[] mCallQuality;
    private int[] mCallState;
    private ArrayList<List<CellInfo>> mCellInfo;
    private Bundle[] mCellLocation;
    private final Context mContext;
    private int[] mDataActivationState;
    private int[] mDataActivity;
    private int[] mDataConnectionNetworkType;
    private int[] mDataConnectionState;
    private Map<Integer, List<EmergencyNumber>> mEmergencyNumberList;
    private int[] mForegroundCallState;
    private List<ImsReasonInfo> mImsReasonInfo;
    private boolean[] mMessageWaiting;
    private int mNumPhones;
    private int[] mOtaspMode;
    private ArrayList<List<PhysicalChannelConfig>> mPhysicalChannelConfigs;
    private PreciseCallState[] mPreciseCallState;
    private PreciseDataConnectionState[] mPreciseDataConnectionState;
    private int[] mRingingCallState;
    private ServiceState[] mServiceState;
    private SignalStrength[] mSignalStrength;
    private int[] mSrvccState;
    private boolean[] mUserMobileDataState;
    private int[] mVoiceActivationState;
    private final ArrayList<IBinder> mRemoveList = new ArrayList<>();
    private final ArrayList<Record> mRecords = new ArrayList<>();
    private boolean mHasNotifySubscriptionInfoChangedOccurred = false;
    private boolean mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = false;
    private int mDefaultSubId = -1;
    private int mDefaultPhoneId = -1;
    private boolean mCarrierNetworkChangeState = false;
    private PhoneCapability mPhoneCapability = null;
    private int mActiveDataSubId = -1;
    private int mRadioPowerState = 2;
    private final LocalLog mLocalLog = new LocalLog(100);
    private final LocalLog mListenLog = new LocalLog(100);
    private final Handler mHandler = new Handler() { // from class: com.android.server.TelephonyRegistry.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                int numPhones = TelephonyManager.getDefault().getPhoneCount();
                for (int sub = 0; sub < numPhones; sub++) {
                    TelephonyRegistry telephonyRegistry = TelephonyRegistry.this;
                    telephonyRegistry.notifyCellLocationForSubscriber(sub, telephonyRegistry.mCellLocation[sub]);
                }
            } else if (i == 2) {
                int newDefaultPhoneId = msg.arg1;
                int newDefaultSubId = ((Integer) msg.obj).intValue();
                synchronized (TelephonyRegistry.this.mRecords) {
                    Iterator it = TelephonyRegistry.this.mRecords.iterator();
                    while (it.hasNext()) {
                        Record r = (Record) it.next();
                        if (r.subId == Integer.MAX_VALUE) {
                            TelephonyRegistry.this.checkPossibleMissNotify(r, newDefaultPhoneId);
                        }
                    }
                    TelephonyRegistry.this.handleRemoveListLocked();
                }
                TelephonyRegistry.this.mDefaultSubId = newDefaultSubId;
                TelephonyRegistry.this.mDefaultPhoneId = newDefaultPhoneId;
                LocalLog localLog = TelephonyRegistry.this.mLocalLog;
                localLog.log("Default subscription updated: mDefaultPhoneId=" + TelephonyRegistry.this.mDefaultPhoneId + ", mDefaultSubId" + TelephonyRegistry.this.mDefaultSubId);
            }
        }
    };
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.TelephonyRegistry.2
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.intent.action.USER_SWITCHED".equals(action)) {
                int userHandle = intent.getIntExtra("android.intent.extra.user_handle", 0);
                TelephonyRegistry.this.mHandler.sendMessage(TelephonyRegistry.this.mHandler.obtainMessage(1, userHandle, 0));
            } else if (action.equals("android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED")) {
                Integer newDefaultSubIdObj = new Integer(intent.getIntExtra("subscription", SubscriptionManager.getDefaultSubscriptionId()));
                int newDefaultPhoneId = intent.getIntExtra(xpInputActionHandler.MODE_PHONE, SubscriptionManager.getPhoneId(TelephonyRegistry.this.mDefaultSubId));
                if (TelephonyRegistry.this.validatePhoneId(newDefaultPhoneId)) {
                    if (!newDefaultSubIdObj.equals(Integer.valueOf(TelephonyRegistry.this.mDefaultSubId)) || newDefaultPhoneId != TelephonyRegistry.this.mDefaultPhoneId) {
                        TelephonyRegistry.this.mHandler.sendMessage(TelephonyRegistry.this.mHandler.obtainMessage(2, newDefaultPhoneId, 0, newDefaultSubIdObj));
                    }
                }
            }
        }
    };

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Record {
        IBinder binder;
        IPhoneStateListener callback;
        int callerPid;
        int callerUid;
        String callingPackage;
        Context context;
        TelephonyRegistryDeathRecipient deathRecipient;
        int events;
        IOnSubscriptionsChangedListener onOpportunisticSubscriptionsChangedListenerCallback;
        IOnSubscriptionsChangedListener onSubscriptionsChangedListenerCallback;
        int phoneId;
        int subId;

        private Record() {
            this.subId = -1;
            this.phoneId = -1;
        }

        boolean matchPhoneStateListenerEvent(int events) {
            return (this.callback == null || (this.events & events) == 0) ? false : true;
        }

        boolean matchOnSubscriptionsChangedListener() {
            return this.onSubscriptionsChangedListenerCallback != null;
        }

        boolean matchOnOpportunisticSubscriptionsChangedListener() {
            return this.onOpportunisticSubscriptionsChangedListenerCallback != null;
        }

        boolean canReadCallLog() {
            try {
                return TelephonyPermissions.checkReadCallLog(this.context, this.subId, this.callerPid, this.callerUid, this.callingPackage);
            } catch (SecurityException e) {
                return false;
            }
        }

        public String toString() {
            return "{callingPackage=" + this.callingPackage + " binder=" + this.binder + " callback=" + this.callback + " onSubscriptionsChangedListenererCallback=" + this.onSubscriptionsChangedListenerCallback + " onOpportunisticSubscriptionsChangedListenererCallback=" + this.onOpportunisticSubscriptionsChangedListenerCallback + " callerUid=" + this.callerUid + " subId=" + this.subId + " phoneId=" + this.phoneId + " events=" + Integer.toHexString(this.events) + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class TelephonyRegistryDeathRecipient implements IBinder.DeathRecipient {
        private final IBinder binder;

        TelephonyRegistryDeathRecipient(IBinder binder) {
            this.binder = binder;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            TelephonyRegistry.this.remove(this.binder);
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public TelephonyRegistry(Context context) {
        this.mCellInfo = null;
        this.mImsReasonInfo = null;
        CellLocation location = CellLocation.getEmpty();
        this.mContext = context;
        this.mBatteryStats = BatteryStatsService.getService();
        int numPhones = TelephonyManager.getDefault().getPhoneCount();
        this.mNumPhones = numPhones;
        this.mCallState = new int[numPhones];
        this.mDataActivity = new int[numPhones];
        this.mDataConnectionState = new int[numPhones];
        this.mDataConnectionNetworkType = new int[numPhones];
        this.mCallIncomingNumber = new String[numPhones];
        this.mServiceState = new ServiceState[numPhones];
        this.mVoiceActivationState = new int[numPhones];
        this.mDataActivationState = new int[numPhones];
        this.mUserMobileDataState = new boolean[numPhones];
        this.mSignalStrength = new SignalStrength[numPhones];
        this.mMessageWaiting = new boolean[numPhones];
        this.mCallForwarding = new boolean[numPhones];
        this.mCellLocation = new Bundle[numPhones];
        this.mSrvccState = new int[numPhones];
        this.mOtaspMode = new int[numPhones];
        this.mPreciseCallState = new PreciseCallState[numPhones];
        this.mForegroundCallState = new int[numPhones];
        this.mBackgroundCallState = new int[numPhones];
        this.mRingingCallState = new int[numPhones];
        this.mCallDisconnectCause = new int[numPhones];
        this.mCallPreciseDisconnectCause = new int[numPhones];
        this.mCallQuality = new CallQuality[numPhones];
        this.mCallNetworkType = new int[numPhones];
        this.mCallAttributes = new CallAttributes[numPhones];
        this.mPreciseDataConnectionState = new PreciseDataConnectionState[numPhones];
        this.mCellInfo = new ArrayList<>();
        this.mImsReasonInfo = new ArrayList();
        this.mPhysicalChannelConfigs = new ArrayList<>();
        this.mEmergencyNumberList = new HashMap();
        for (int i = 0; i < numPhones; i++) {
            this.mCallState[i] = 0;
            this.mDataActivity[i] = 0;
            this.mDataConnectionState[i] = -1;
            this.mVoiceActivationState[i] = 0;
            this.mDataActivationState[i] = 0;
            this.mCallIncomingNumber[i] = "";
            this.mServiceState[i] = new ServiceState();
            this.mSignalStrength[i] = new SignalStrength();
            this.mUserMobileDataState[i] = false;
            this.mMessageWaiting[i] = false;
            this.mCallForwarding[i] = false;
            this.mCellLocation[i] = new Bundle();
            this.mCellInfo.add(i, null);
            this.mImsReasonInfo.add(i, null);
            this.mSrvccState[i] = -1;
            this.mPhysicalChannelConfigs.add(i, new ArrayList());
            this.mOtaspMode[i] = 1;
            this.mCallDisconnectCause[i] = -1;
            this.mCallPreciseDisconnectCause[i] = -1;
            this.mCallQuality[i] = new CallQuality();
            this.mCallAttributes[i] = new CallAttributes(new PreciseCallState(), 0, new CallQuality());
            this.mCallNetworkType[i] = 0;
            this.mPreciseCallState[i] = new PreciseCallState();
            this.mRingingCallState[i] = 0;
            this.mForegroundCallState[i] = 0;
            this.mBackgroundCallState[i] = 0;
            this.mPreciseDataConnectionState[i] = new PreciseDataConnectionState();
        }
        if (location != null) {
            for (int i2 = 0; i2 < numPhones; i2++) {
                location.fillInNotifierBundle(this.mCellLocation[i2]);
            }
        }
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
    }

    public void systemRunning() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.USER_SWITCHED");
        filter.addAction("android.intent.action.USER_REMOVED");
        filter.addAction("android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED");
        log("systemRunning register for intents");
        this.mContext.registerReceiver(this.mBroadcastReceiver, filter);
    }

    public void addOnSubscriptionsChangedListener(String callingPackage, IOnSubscriptionsChangedListener callback) {
        UserHandle.getCallingUserId();
        this.mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        synchronized (this.mRecords) {
            IBinder b = callback.asBinder();
            Record r = add(b, Binder.getCallingPid(), false);
            if (r == null) {
                return;
            }
            r.context = this.mContext;
            r.onSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.events = 0;
            if (this.mHasNotifySubscriptionInfoChangedOccurred) {
                try {
                    r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                } catch (RemoteException e) {
                    remove(r.binder);
                }
            } else {
                log("listen oscl: mHasNotifySubscriptionInfoChangedOccurred==false no callback");
            }
        }
    }

    public void removeOnSubscriptionsChangedListener(String pkgForDebug, IOnSubscriptionsChangedListener callback) {
        remove(callback.asBinder());
    }

    public void addOnOpportunisticSubscriptionsChangedListener(String callingPackage, IOnSubscriptionsChangedListener callback) {
        UserHandle.getCallingUserId();
        this.mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        synchronized (this.mRecords) {
            IBinder b = callback.asBinder();
            Record r = add(b, Binder.getCallingPid(), false);
            if (r == null) {
                return;
            }
            r.context = this.mContext;
            r.onOpportunisticSubscriptionsChangedListenerCallback = callback;
            r.callingPackage = callingPackage;
            r.callerUid = Binder.getCallingUid();
            r.callerPid = Binder.getCallingPid();
            r.events = 0;
            if (this.mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                try {
                    r.onOpportunisticSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                } catch (RemoteException e) {
                    remove(r.binder);
                }
            } else {
                log("listen ooscl: hasNotifyOpptSubInfoChangedOccurred==false no callback");
            }
        }
    }

    public void notifySubscriptionInfoChanged() {
        synchronized (this.mRecords) {
            if (!this.mHasNotifySubscriptionInfoChangedOccurred) {
                log("notifySubscriptionInfoChanged: first invocation mRecords.size=" + this.mRecords.size());
            }
            this.mHasNotifySubscriptionInfoChangedOccurred = true;
            this.mRemoveList.clear();
            Iterator<Record> it = this.mRecords.iterator();
            while (it.hasNext()) {
                Record r = it.next();
                if (r.matchOnSubscriptionsChangedListener()) {
                    try {
                        r.onSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    } catch (RemoteException e) {
                        this.mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyOpportunisticSubscriptionInfoChanged() {
        synchronized (this.mRecords) {
            if (!this.mHasNotifyOpportunisticSubscriptionInfoChangedOccurred) {
                log("notifyOpptSubscriptionInfoChanged: first invocation mRecords.size=" + this.mRecords.size());
            }
            this.mHasNotifyOpportunisticSubscriptionInfoChangedOccurred = true;
            this.mRemoveList.clear();
            Iterator<Record> it = this.mRecords.iterator();
            while (it.hasNext()) {
                Record r = it.next();
                if (r.matchOnOpportunisticSubscriptionsChangedListener()) {
                    try {
                        r.onOpportunisticSubscriptionsChangedListenerCallback.onSubscriptionsChanged();
                    } catch (RemoteException e) {
                        this.mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void listen(String pkgForDebug, IPhoneStateListener callback, int events, boolean notifyNow) {
        listenForSubscriber(Integer.MAX_VALUE, pkgForDebug, callback, events, notifyNow);
    }

    public void listenForSubscriber(int subId, String pkgForDebug, IPhoneStateListener callback, int events, boolean notifyNow) {
        listen(pkgForDebug, callback, events, notifyNow, subId);
    }

    private void listen(String callingPackage, IPhoneStateListener callback, int events, boolean notifyNow, int subId) {
        int callerUserId = UserHandle.getCallingUserId();
        this.mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        String str = "listen: E pkg=" + callingPackage + " events=0x" + Integer.toHexString(events) + " notifyNow=" + notifyNow + " subId=" + subId + " myUserId=" + UserHandle.myUserId() + " callerUserId=" + callerUserId;
        this.mListenLog.log(str);
        if (events != 0) {
            if (!checkListenerPermission(events, subId, callingPackage, "listen")) {
                return;
            }
            int phoneId = SubscriptionManager.getPhoneId(subId);
            synchronized (this.mRecords) {
                try {
                    IBinder b = callback.asBinder();
                    boolean shouldEnforceListenerLimit = (Binder.getCallingUid() == 1000 || Binder.getCallingUid() == 1001 || Binder.getCallingUid() == Process.myUid()) ? false : true;
                    Record r = add(b, Binder.getCallingPid(), shouldEnforceListenerLimit);
                    if (r == null) {
                        return;
                    }
                    r.context = this.mContext;
                    try {
                        r.callback = callback;
                        r.callingPackage = callingPackage;
                        r.callerUid = Binder.getCallingUid();
                        r.callerPid = Binder.getCallingPid();
                        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                            r.subId = Integer.MAX_VALUE;
                        } else {
                            r.subId = subId;
                        }
                        r.phoneId = phoneId;
                        r.events = events;
                        if (notifyNow && validatePhoneId(phoneId)) {
                            if ((events & 1) != 0) {
                                try {
                                    ServiceState rawSs = new ServiceState(this.mServiceState[phoneId]);
                                    if (checkFineLocationAccess(r, 29)) {
                                        r.callback.onServiceStateChanged(rawSs);
                                    } else if (checkCoarseLocationAccess(r, 29)) {
                                        r.callback.onServiceStateChanged(rawSs.sanitizeLocationInfo(false));
                                    } else {
                                        r.callback.onServiceStateChanged(rawSs.sanitizeLocationInfo(true));
                                    }
                                } catch (RemoteException e) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 2) != 0) {
                                try {
                                    int gsmSignalStrength = this.mSignalStrength[phoneId].getGsmSignalStrength();
                                    r.callback.onSignalStrengthChanged(gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
                                } catch (RemoteException e2) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 4) != 0) {
                                try {
                                    r.callback.onMessageWaitingIndicatorChanged(this.mMessageWaiting[phoneId]);
                                } catch (RemoteException e3) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 8) != 0) {
                                try {
                                    r.callback.onCallForwardingIndicatorChanged(this.mCallForwarding[phoneId]);
                                } catch (RemoteException e4) {
                                    remove(r.binder);
                                }
                            }
                            if (validateEventsAndUserLocked(r, 16)) {
                                try {
                                    if (checkCoarseLocationAccess(r, 1) && checkFineLocationAccess(r, 29)) {
                                        r.callback.onCellLocationChanged(this.mCellLocation[phoneId]);
                                    }
                                } catch (RemoteException e5) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 32) != 0) {
                                try {
                                    r.callback.onCallStateChanged(this.mCallState[phoneId], getCallIncomingNumber(r, phoneId));
                                } catch (RemoteException e6) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 64) != 0) {
                                try {
                                    r.callback.onDataConnectionStateChanged(this.mDataConnectionState[phoneId], this.mDataConnectionNetworkType[phoneId]);
                                } catch (RemoteException e7) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 128) != 0) {
                                try {
                                    r.callback.onDataActivity(this.mDataActivity[phoneId]);
                                } catch (RemoteException e8) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 256) != 0) {
                                try {
                                    r.callback.onSignalStrengthsChanged(this.mSignalStrength[phoneId]);
                                } catch (RemoteException e9) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 512) != 0) {
                                try {
                                    r.callback.onOtaspChanged(this.mOtaspMode[phoneId]);
                                } catch (RemoteException e10) {
                                    remove(r.binder);
                                }
                            }
                            if (validateEventsAndUserLocked(r, 1024)) {
                                try {
                                    if (checkCoarseLocationAccess(r, 1) && checkFineLocationAccess(r, 29)) {
                                        r.callback.onCellInfoChanged(this.mCellInfo.get(phoneId));
                                    }
                                } catch (RemoteException e11) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 2048) != 0) {
                                try {
                                    r.callback.onPreciseCallStateChanged(this.mPreciseCallState[phoneId]);
                                } catch (RemoteException e12) {
                                    remove(r.binder);
                                }
                            }
                            if ((33554432 & events) != 0) {
                                try {
                                    r.callback.onCallDisconnectCauseChanged(this.mCallDisconnectCause[phoneId], this.mCallPreciseDisconnectCause[phoneId]);
                                } catch (RemoteException e13) {
                                    remove(r.binder);
                                }
                            }
                            if ((134217728 & events) != 0) {
                                try {
                                    r.callback.onImsCallDisconnectCauseChanged(this.mImsReasonInfo.get(phoneId));
                                } catch (RemoteException e14) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 4096) != 0) {
                                try {
                                    r.callback.onPreciseDataConnectionStateChanged(this.mPreciseDataConnectionState[phoneId]);
                                } catch (RemoteException e15) {
                                    remove(r.binder);
                                }
                            }
                            if ((65536 & events) != 0) {
                                try {
                                    r.callback.onCarrierNetworkChange(this.mCarrierNetworkChangeState);
                                } catch (RemoteException e16) {
                                    remove(r.binder);
                                }
                            }
                            if ((131072 & events) != 0) {
                                try {
                                    r.callback.onVoiceActivationStateChanged(this.mVoiceActivationState[phoneId]);
                                } catch (RemoteException e17) {
                                    remove(r.binder);
                                }
                            }
                            if ((262144 & events) != 0) {
                                try {
                                    r.callback.onDataActivationStateChanged(this.mDataActivationState[phoneId]);
                                } catch (RemoteException e18) {
                                    remove(r.binder);
                                }
                            }
                            if ((524288 & events) != 0) {
                                try {
                                    r.callback.onUserMobileDataStateChanged(this.mUserMobileDataState[phoneId]);
                                } catch (RemoteException e19) {
                                    remove(r.binder);
                                }
                            }
                            if ((1048576 & events) != 0) {
                                try {
                                    r.callback.onPhysicalChannelConfigurationChanged(this.mPhysicalChannelConfigs.get(phoneId));
                                } catch (RemoteException e20) {
                                    remove(r.binder);
                                }
                            }
                            if ((16777216 & events) != 0) {
                                try {
                                    r.callback.onEmergencyNumberListChanged(this.mEmergencyNumberList);
                                } catch (RemoteException e21) {
                                    remove(r.binder);
                                }
                            }
                            if ((2097152 & events) != 0) {
                                try {
                                    r.callback.onPhoneCapabilityChanged(this.mPhoneCapability);
                                } catch (RemoteException e22) {
                                    remove(r.binder);
                                }
                            }
                            if ((4194304 & events) != 0 && TelephonyPermissions.checkReadPhoneStateOnAnyActiveSub(r.context, r.callerPid, r.callerUid, r.callingPackage, "listen_active_data_subid_change")) {
                                try {
                                    r.callback.onActiveDataSubIdChanged(this.mActiveDataSubId);
                                } catch (RemoteException e23) {
                                    remove(r.binder);
                                }
                            }
                            if ((8388608 & events) != 0) {
                                try {
                                    r.callback.onRadioPowerStateChanged(this.mRadioPowerState);
                                } catch (RemoteException e24) {
                                    remove(r.binder);
                                }
                            }
                            if ((events & 16384) != 0) {
                                try {
                                    r.callback.onSrvccStateChanged(this.mSrvccState[phoneId]);
                                } catch (RemoteException e25) {
                                    remove(r.binder);
                                }
                            }
                            if ((67108864 & events) != 0) {
                                try {
                                    r.callback.onCallAttributesChanged(this.mCallAttributes[phoneId]);
                                } catch (RemoteException e26) {
                                    remove(r.binder);
                                }
                            }
                        }
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            }
        } else {
            remove(callback.asBinder());
        }
    }

    private String getCallIncomingNumber(Record record, int phoneId) {
        return record.canReadCallLog() ? this.mCallIncomingNumber[phoneId] : "";
    }

    /* JADX WARN: Code restructure failed: missing block: B:22:0x004e, code lost:
        if (r2 < 25) goto L29;
     */
    /* JADX WARN: Code restructure failed: missing block: B:23:0x0050, code lost:
        android.telephony.Rlog.w(com.android.server.TelephonyRegistry.TAG, "Pid " + r9 + " has exceeded half the number of permissibleregistered listeners. Now at " + r2);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.TelephonyRegistry.Record add(android.os.IBinder r8, int r9, boolean r10) {
        /*
            r7 = this;
            java.util.ArrayList<com.android.server.TelephonyRegistry$Record> r0 = r7.mRecords
            monitor-enter(r0)
            java.util.ArrayList<com.android.server.TelephonyRegistry$Record> r1 = r7.mRecords     // Catch: java.lang.Throwable -> L8f
            int r1 = r1.size()     // Catch: java.lang.Throwable -> L8f
            r2 = 0
            r3 = 0
        Lb:
            if (r3 >= r1) goto L24
            java.util.ArrayList<com.android.server.TelephonyRegistry$Record> r4 = r7.mRecords     // Catch: java.lang.Throwable -> L8f
            java.lang.Object r4 = r4.get(r3)     // Catch: java.lang.Throwable -> L8f
            com.android.server.TelephonyRegistry$Record r4 = (com.android.server.TelephonyRegistry.Record) r4     // Catch: java.lang.Throwable -> L8f
            android.os.IBinder r5 = r4.binder     // Catch: java.lang.Throwable -> L8f
            if (r8 != r5) goto L1b
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L8f
            return r4
        L1b:
            int r5 = r4.callerPid     // Catch: java.lang.Throwable -> L8f
            if (r5 != r9) goto L21
            int r2 = r2 + 1
        L21:
            int r3 = r3 + 1
            goto Lb
        L24:
            if (r10 == 0) goto L4a
            r3 = 50
            if (r2 >= r3) goto L2b
            goto L4a
        L2b:
            java.lang.StringBuilder r3 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L8f
            r3.<init>()     // Catch: java.lang.Throwable -> L8f
            java.lang.String r4 = "Pid "
            r3.append(r4)     // Catch: java.lang.Throwable -> L8f
            r3.append(r9)     // Catch: java.lang.Throwable -> L8f
            java.lang.String r4 = " has exceeded the number of permissibleregistered listeners. Ignoring request to add."
            r3.append(r4)     // Catch: java.lang.Throwable -> L8f
            java.lang.String r3 = r3.toString()     // Catch: java.lang.Throwable -> L8f
            loge(r3)     // Catch: java.lang.Throwable -> L8f
            java.lang.IllegalStateException r4 = new java.lang.IllegalStateException     // Catch: java.lang.Throwable -> L8f
            r4.<init>(r3)     // Catch: java.lang.Throwable -> L8f
            throw r4     // Catch: java.lang.Throwable -> L8f
        L4a:
            if (r10 == 0) goto L6e
            r3 = 25
            if (r2 < r3) goto L6e
            java.lang.String r3 = "TelephonyRegistry"
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L8f
            r4.<init>()     // Catch: java.lang.Throwable -> L8f
            java.lang.String r5 = "Pid "
            r4.append(r5)     // Catch: java.lang.Throwable -> L8f
            r4.append(r9)     // Catch: java.lang.Throwable -> L8f
            java.lang.String r5 = " has exceeded half the number of permissibleregistered listeners. Now at "
            r4.append(r5)     // Catch: java.lang.Throwable -> L8f
            r4.append(r2)     // Catch: java.lang.Throwable -> L8f
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L8f
            android.telephony.Rlog.w(r3, r4)     // Catch: java.lang.Throwable -> L8f
        L6e:
            com.android.server.TelephonyRegistry$Record r3 = new com.android.server.TelephonyRegistry$Record     // Catch: java.lang.Throwable -> L8f
            r4 = 0
            r3.<init>()     // Catch: java.lang.Throwable -> L8f
            r3.binder = r8     // Catch: java.lang.Throwable -> L8f
            com.android.server.TelephonyRegistry$TelephonyRegistryDeathRecipient r5 = new com.android.server.TelephonyRegistry$TelephonyRegistryDeathRecipient     // Catch: java.lang.Throwable -> L8f
            r5.<init>(r8)     // Catch: java.lang.Throwable -> L8f
            r3.deathRecipient = r5     // Catch: java.lang.Throwable -> L8f
            com.android.server.TelephonyRegistry$TelephonyRegistryDeathRecipient r5 = r3.deathRecipient     // Catch: android.os.RemoteException -> L8c java.lang.Throwable -> L8f
            r6 = 0
            r8.linkToDeath(r5, r6)     // Catch: android.os.RemoteException -> L8c java.lang.Throwable -> L8f
            java.util.ArrayList<com.android.server.TelephonyRegistry$Record> r4 = r7.mRecords     // Catch: java.lang.Throwable -> L8f
            r4.add(r3)     // Catch: java.lang.Throwable -> L8f
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L8f
            return r3
        L8c:
            r5 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L8f
            return r4
        L8f:
            r1 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L8f
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.TelephonyRegistry.add(android.os.IBinder, int, boolean):com.android.server.TelephonyRegistry$Record");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void remove(IBinder binder) {
        synchronized (this.mRecords) {
            int recordCount = this.mRecords.size();
            for (int i = 0; i < recordCount; i++) {
                Record r = this.mRecords.get(i);
                if (r.binder == binder) {
                    if (r.deathRecipient != null) {
                        try {
                            binder.unlinkToDeath(r.deathRecipient, 0);
                        } catch (NoSuchElementException e) {
                        }
                    }
                    this.mRecords.remove(i);
                    return;
                }
            }
        }
    }

    public void notifyCallState(int state, String phoneNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }
        synchronized (this.mRecords) {
            Iterator<Record> it = this.mRecords.iterator();
            while (it.hasNext()) {
                Record r = it.next();
                if (r.matchPhoneStateListenerEvent(32) && r.subId == Integer.MAX_VALUE) {
                    try {
                        String phoneNumberOrEmpty = r.canReadCallLog() ? phoneNumber : "";
                        r.callback.onCallStateChanged(state, phoneNumberOrEmpty);
                    } catch (RemoteException e) {
                        this.mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastCallStateChanged(state, phoneNumber, -1, -1);
    }

    public void notifyCallStateForPhoneId(int phoneId, int subId, int state, String incomingNumber) {
        if (!checkNotifyPermission("notifyCallState()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mCallState[phoneId] = state;
                this.mCallIncomingNumber[phoneId] = incomingNumber;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(32) && r.subId == subId && r.subId != Integer.MAX_VALUE) {
                        try {
                            String incomingNumberOrEmpty = getCallIncomingNumber(r, phoneId);
                            r.callback.onCallStateChanged(state, incomingNumberOrEmpty);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastCallStateChanged(state, incomingNumber, phoneId, subId);
    }

    public void notifyServiceStateForPhoneId(int phoneId, int subId, ServiceState state) {
        ServiceState stateToSend;
        if (!checkNotifyPermission("notifyServiceState()")) {
            return;
        }
        synchronized (this.mRecords) {
            String str = "notifyServiceStateForSubscriber: subId=" + subId + " phoneId=" + phoneId + " state=" + state;
            this.mLocalLog.log(str);
            if (validatePhoneId(phoneId) && SubscriptionManager.isValidSubscriptionId(subId)) {
                this.mServiceState[phoneId] = state;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(1) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            if (checkFineLocationAccess(r, 29)) {
                                stateToSend = new ServiceState(state);
                            } else if (checkCoarseLocationAccess(r, 29)) {
                                stateToSend = state.sanitizeLocationInfo(false);
                            } else {
                                stateToSend = state.sanitizeLocationInfo(true);
                            }
                            r.callback.onServiceStateChanged(stateToSend);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifyServiceStateForSubscriber: INVALID phoneId=" + phoneId + " or subId=" + subId);
            }
            handleRemoveListLocked();
        }
        broadcastServiceStateChanged(state, phoneId, subId);
    }

    public void notifySimActivationStateChangedForPhoneId(int phoneId, int subId, int activationType, int activationState) {
        if (!checkNotifyPermission("notifySimActivationState()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                if (activationType != 0) {
                    if (activationType != 1) {
                        return;
                    }
                    this.mDataActivationState[phoneId] = activationState;
                } else {
                    this.mVoiceActivationState[phoneId] = activationState;
                }
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (activationType == 0) {
                        try {
                            if (r.matchPhoneStateListenerEvent(131072) && idMatch(r.subId, subId, phoneId)) {
                                r.callback.onVoiceActivationStateChanged(activationState);
                            }
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                    if (activationType == 1 && r.matchPhoneStateListenerEvent(262144) && idMatch(r.subId, subId, phoneId)) {
                        r.callback.onDataActivationStateChanged(activationState);
                    }
                }
            } else {
                log("notifySimActivationStateForPhoneId: INVALID phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
    }

    public void notifySignalStrengthForPhoneId(int phoneId, int subId, SignalStrength signalStrength) {
        if (!checkNotifyPermission("notifySignalStrength()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mSignalStrength[phoneId] = signalStrength;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(256) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                    if (r.matchPhoneStateListenerEvent(2) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            int gsmSignalStrength = signalStrength.getGsmSignalStrength();
                            int ss = gsmSignalStrength == 99 ? -1 : gsmSignalStrength;
                            r.callback.onSignalStrengthChanged(ss);
                        } catch (RemoteException e2) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            } else {
                log("notifySignalStrengthForPhoneId: invalid phoneId=" + phoneId);
            }
            handleRemoveListLocked();
        }
        broadcastSignalStrengthChanged(signalStrength, phoneId, subId);
    }

    public void notifyCarrierNetworkChange(boolean active) {
        int[] subIds = Arrays.stream(SubscriptionManager.from(this.mContext).getActiveSubscriptionIdList()).filter(new IntPredicate() { // from class: com.android.server.-$$Lambda$TelephonyRegistry$B6olxgDfuFsbAHNXng6QnHMFZZM
            @Override // java.util.function.IntPredicate
            public final boolean test(int i) {
                boolean checkCarrierPrivilegeForSubId;
                checkCarrierPrivilegeForSubId = TelephonyPermissions.checkCarrierPrivilegeForSubId(i);
                return checkCarrierPrivilegeForSubId;
            }
        }).toArray();
        if (ArrayUtils.isEmpty(subIds)) {
            loge("notifyCarrierNetworkChange without carrier privilege");
            throw new SecurityException("notifyCarrierNetworkChange without carrier privilege");
        }
        synchronized (this.mRecords) {
            this.mCarrierNetworkChangeState = active;
            for (int subId : subIds) {
                int phoneId = SubscriptionManager.getPhoneId(subId);
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(65536) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCarrierNetworkChange(active);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyCellInfo(List<CellInfo> cellInfo) {
        notifyCellInfoForSubscriber(Integer.MAX_VALUE, cellInfo);
    }

    public void notifyCellInfoForSubscriber(int subId, List<CellInfo> cellInfo) {
        if (!checkNotifyPermission("notifyCellInfo()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mCellInfo.set(phoneId, cellInfo);
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (validateEventsAndUserLocked(r, 1024) && idMatch(r.subId, subId, phoneId) && checkCoarseLocationAccess(r, 1) && checkFineLocationAccess(r, 29)) {
                        try {
                            r.callback.onCellInfoChanged(cellInfo);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPhysicalChannelConfigurationForSubscriber(int phoneId, int subId, List<PhysicalChannelConfig> configs) {
        if (!checkNotifyPermission("notifyPhysicalChannelConfiguration()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mPhysicalChannelConfigs.set(phoneId, configs);
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(DumpState.DUMP_DEXOPT) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPhysicalChannelConfigurationChanged(configs);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyMessageWaitingChangedForPhoneId(int phoneId, int subId, boolean mwi) {
        if (!checkNotifyPermission("notifyMessageWaitingChanged()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mMessageWaiting[phoneId] = mwi;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(4) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onMessageWaitingIndicatorChanged(mwi);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyUserMobileDataStateChangedForPhoneId(int phoneId, int subId, boolean state) {
        if (!checkNotifyPermission("notifyUserMobileDataStateChanged()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mUserMobileDataState[phoneId] = state;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(DumpState.DUMP_FROZEN) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onUserMobileDataStateChanged(state);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyCallForwardingChanged(boolean cfi) {
        notifyCallForwardingChangedForSubscriber(Integer.MAX_VALUE, cfi);
    }

    public void notifyCallForwardingChangedForSubscriber(int subId, boolean cfi) {
        if (!checkNotifyPermission("notifyCallForwardingChanged()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mCallForwarding[phoneId] = cfi;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(8) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallForwardingIndicatorChanged(cfi);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyDataActivity(int state) {
        notifyDataActivityForSubscriber(Integer.MAX_VALUE, state);
    }

    public void notifyDataActivityForSubscriber(int subId, int state) {
        if (!checkNotifyPermission("notifyDataActivity()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mDataActivity[phoneId] = state;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(128) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onDataActivity(state);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyDataConnection(int state, boolean isDataAllowed, String apn, String apnType, LinkProperties linkProperties, NetworkCapabilities networkCapabilities, int networkType, boolean roaming) {
        notifyDataConnectionForSubscriber(Integer.MAX_VALUE, Integer.MAX_VALUE, state, isDataAllowed, apn, apnType, linkProperties, networkCapabilities, networkType, roaming);
    }

    public void notifyDataConnectionForSubscriber(int phoneId, int subId, int state, boolean isDataAllowed, String apn, String apnType, LinkProperties linkProperties, NetworkCapabilities networkCapabilities, int networkType, boolean roaming) {
        if (!checkNotifyPermission("notifyDataConnection()")) {
            return;
        }
        synchronized (this.mRecords) {
            try {
                try {
                    if (validatePhoneId(phoneId)) {
                        if ("default".equals(apnType) && (this.mDataConnectionState[phoneId] != state || this.mDataConnectionNetworkType[phoneId] != networkType)) {
                            String str = "onDataConnectionStateChanged(" + TelephonyManager.dataStateToString(state) + ", " + TelephonyManager.getNetworkTypeName(networkType) + ") subId=" + subId + ", phoneId=" + phoneId;
                            log(str);
                            this.mLocalLog.log(str);
                            Iterator<Record> it = this.mRecords.iterator();
                            while (it.hasNext()) {
                                Record r = it.next();
                                if (r.matchPhoneStateListenerEvent(64) && idMatch(r.subId, subId, phoneId)) {
                                    try {
                                        r.callback.onDataConnectionStateChanged(state, networkType);
                                    } catch (RemoteException e) {
                                        this.mRemoveList.add(r.binder);
                                    }
                                }
                            }
                            handleRemoveListLocked();
                            this.mDataConnectionState[phoneId] = state;
                            this.mDataConnectionNetworkType[phoneId] = networkType;
                        }
                        this.mPreciseDataConnectionState[phoneId] = new PreciseDataConnectionState(state, networkType, ApnSetting.getApnTypesBitmaskFromString(apnType), apn, linkProperties, 0);
                        Iterator<Record> it2 = this.mRecords.iterator();
                        while (it2.hasNext()) {
                            Record r2 = it2.next();
                            if (r2.matchPhoneStateListenerEvent(4096) && idMatch(r2.subId, subId, phoneId)) {
                                try {
                                    r2.callback.onPreciseDataConnectionStateChanged(this.mPreciseDataConnectionState[phoneId]);
                                } catch (RemoteException e2) {
                                    this.mRemoveList.add(r2.binder);
                                }
                            }
                        }
                    }
                    handleRemoveListLocked();
                    broadcastDataConnectionStateChanged(state, isDataAllowed, apn, apnType, linkProperties, networkCapabilities, roaming, subId);
                    broadcastPreciseDataConnectionStateChanged(state, networkType, apnType, apn, linkProperties, 0);
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    public void notifyDataConnectionFailed(String apnType) {
        notifyDataConnectionFailedForSubscriber(Integer.MAX_VALUE, Integer.MAX_VALUE, apnType);
    }

    public void notifyDataConnectionFailedForSubscriber(int phoneId, int subId, String apnType) {
        if (!checkNotifyPermission("notifyDataConnectionFailed()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mPreciseDataConnectionState[phoneId] = new PreciseDataConnectionState(-1, 0, ApnSetting.getApnTypesBitmaskFromString(apnType), null, null, 0);
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(4096) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPreciseDataConnectionStateChanged(this.mPreciseDataConnectionState[phoneId]);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastDataConnectionFailed(apnType, subId);
        broadcastPreciseDataConnectionStateChanged(-1, 0, apnType, null, null, 0);
    }

    public void notifyCellLocation(Bundle cellLocation) {
        notifyCellLocationForSubscriber(Integer.MAX_VALUE, cellLocation);
    }

    public void notifyCellLocationForSubscriber(int subId, Bundle cellLocation) {
        log("notifyCellLocationForSubscriber: subId=" + subId + " cellLocation=" + cellLocation);
        if (!checkNotifyPermission("notifyCellLocation()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mCellLocation[phoneId] = cellLocation;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (validateEventsAndUserLocked(r, 16) && idMatch(r.subId, subId, phoneId) && checkCoarseLocationAccess(r, 1) && checkFineLocationAccess(r, 29)) {
                        try {
                            r.callback.onCellLocationChanged(new Bundle(cellLocation));
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyOtaspChanged(int subId, int otaspMode) {
        if (!checkNotifyPermission("notifyOtaspChanged()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mOtaspMode[phoneId] = otaspMode;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(512) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onOtaspChanged(otaspMode);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPreciseCallState(int phoneId, int subId, int ringingCallState, int foregroundCallState, int backgroundCallState) {
        if (!checkNotifyPermission("notifyPreciseCallState()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mRingingCallState[phoneId] = ringingCallState;
                this.mForegroundCallState[phoneId] = foregroundCallState;
                this.mBackgroundCallState[phoneId] = backgroundCallState;
                this.mPreciseCallState[phoneId] = new PreciseCallState(ringingCallState, foregroundCallState, backgroundCallState, -1, -1);
                boolean notifyCallAttributes = true;
                if (this.mCallQuality == null) {
                    log("notifyPreciseCallState: mCallQuality is null, skipping call attributes");
                    notifyCallAttributes = false;
                } else {
                    if (this.mPreciseCallState[phoneId].getForegroundCallState() != 1) {
                        this.mCallNetworkType[phoneId] = 0;
                        this.mCallQuality[phoneId] = new CallQuality();
                    }
                    this.mCallAttributes[phoneId] = new CallAttributes(this.mPreciseCallState[phoneId], this.mCallNetworkType[phoneId], this.mCallQuality[phoneId]);
                }
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(2048) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPreciseCallStateChanged(this.mPreciseCallState[phoneId]);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                    if (notifyCallAttributes && r.matchPhoneStateListenerEvent(67108864) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallAttributesChanged(this.mCallAttributes[phoneId]);
                        } catch (RemoteException e2) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastPreciseCallStateChanged(ringingCallState, foregroundCallState, backgroundCallState);
    }

    public void notifyDisconnectCause(int phoneId, int subId, int disconnectCause, int preciseDisconnectCause) {
        if (!checkNotifyPermission("notifyDisconnectCause()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mCallDisconnectCause[phoneId] = disconnectCause;
                this.mCallPreciseDisconnectCause[phoneId] = preciseDisconnectCause;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(DumpState.DUMP_APEX) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallDisconnectCauseChanged(this.mCallDisconnectCause[phoneId], this.mCallPreciseDisconnectCause[phoneId]);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyImsDisconnectCause(int subId, ImsReasonInfo imsReasonInfo) {
        if (!checkNotifyPermission("notifyImsCallDisconnectCause()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mImsReasonInfo.set(phoneId, imsReasonInfo);
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(134217728) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onImsCallDisconnectCauseChanged(this.mImsReasonInfo.get(phoneId));
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPreciseDataConnectionFailed(int phoneId, int subId, String apnType, String apn, int failCause) {
        if (!checkNotifyPermission("notifyPreciseDataConnectionFailed()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mPreciseDataConnectionState[phoneId] = new PreciseDataConnectionState(-1, 0, ApnSetting.getApnTypesBitmaskFromString(apnType), apn, null, failCause);
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(4096) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onPreciseDataConnectionStateChanged(this.mPreciseDataConnectionState[phoneId]);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
        broadcastPreciseDataConnectionStateChanged(-1, 0, apnType, apn, null, failCause);
    }

    public void notifySrvccStateChanged(int subId, int state) {
        if (!checkNotifyPermission("notifySrvccStateChanged()")) {
            return;
        }
        int phoneId = SubscriptionManager.getPhoneId(subId);
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mSrvccState[phoneId] = state;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(16384) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onSrvccStateChanged(state);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyOemHookRawEventForSubscriber(int phoneId, int subId, byte[] rawData) {
        if (!checkNotifyPermission("notifyOemHookRawEventForSubscriber")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(32768) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onOemHookRawEvent(rawData);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyPhoneCapabilityChanged(PhoneCapability capability) {
        if (!checkNotifyPermission("notifyPhoneCapabilityChanged()")) {
            return;
        }
        synchronized (this.mRecords) {
            this.mPhoneCapability = capability;
            Iterator<Record> it = this.mRecords.iterator();
            while (it.hasNext()) {
                Record r = it.next();
                if (r.matchPhoneStateListenerEvent(DumpState.DUMP_COMPILER_STATS)) {
                    try {
                        r.callback.onPhoneCapabilityChanged(capability);
                    } catch (RemoteException e) {
                        this.mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyActiveDataSubIdChanged(int activeDataSubId) {
        List<Record> copiedRecords;
        if (!checkNotifyPermission("notifyActiveDataSubIdChanged()")) {
            return;
        }
        synchronized (this.mRecords) {
            copiedRecords = new ArrayList<>(this.mRecords);
        }
        this.mActiveDataSubId = activeDataSubId;
        List<Record> copiedRecords2 = (List) copiedRecords.stream().filter(new Predicate() { // from class: com.android.server.-$$Lambda$TelephonyRegistry$kbHTNHMcu4ep02HSrYzRQeY7ThQ
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return TelephonyRegistry.this.lambda$notifyActiveDataSubIdChanged$1$TelephonyRegistry((TelephonyRegistry.Record) obj);
            }
        }).collect(Collectors.toCollection(new Supplier() { // from class: com.android.server.-$$Lambda$OGSS2qx6njxlnp0dnKb4lA3jnw8
            @Override // java.util.function.Supplier
            public final Object get() {
                return new ArrayList();
            }
        }));
        synchronized (this.mRecords) {
            for (Record r : copiedRecords2) {
                if (this.mRecords.contains(r)) {
                    try {
                        r.callback.onActiveDataSubIdChanged(activeDataSubId);
                    } catch (RemoteException e) {
                        this.mRemoveList.add(r.binder);
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public /* synthetic */ boolean lambda$notifyActiveDataSubIdChanged$1$TelephonyRegistry(Record r) {
        return r.matchPhoneStateListenerEvent(DumpState.DUMP_CHANGES) && TelephonyPermissions.checkReadPhoneStateOnAnyActiveSub(this.mContext, r.callerPid, r.callerUid, r.callingPackage, "notifyActiveDataSubIdChanged");
    }

    public void notifyRadioPowerStateChanged(int phoneId, int subId, int state) {
        if (!checkNotifyPermission("notifyRadioPowerStateChanged()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mRadioPowerState = state;
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(DumpState.DUMP_VOLUMES) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onRadioPowerStateChanged(state);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyEmergencyNumberList(int phoneId, int subId) {
        if (!checkNotifyPermission("notifyEmergencyNumberList()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                TelephonyManager tm = (TelephonyManager) this.mContext.getSystemService(xpInputActionHandler.MODE_PHONE);
                this.mEmergencyNumberList = tm.getEmergencyNumberList();
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(16777216) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onEmergencyNumberListChanged(this.mEmergencyNumberList);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void notifyCallQualityChanged(CallQuality callQuality, int phoneId, int subId, int callNetworkType) {
        if (!checkNotifyPermission("notifyCallQualityChanged()")) {
            return;
        }
        synchronized (this.mRecords) {
            if (validatePhoneId(phoneId)) {
                this.mCallQuality[phoneId] = callQuality;
                this.mCallNetworkType[phoneId] = callNetworkType;
                this.mCallAttributes[phoneId] = new CallAttributes(this.mPreciseCallState[phoneId], callNetworkType, callQuality);
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    if (r.matchPhoneStateListenerEvent(67108864) && idMatch(r.subId, subId, phoneId)) {
                        try {
                            r.callback.onCallAttributesChanged(this.mCallAttributes[phoneId]);
                        } catch (RemoteException e) {
                            this.mRemoveList.add(r.binder);
                        }
                    }
                }
            }
            handleRemoveListLocked();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            synchronized (this.mRecords) {
                int recordCount = this.mRecords.size();
                pw.println("last known state:");
                pw.increaseIndent();
                for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
                    pw.println("Phone Id=" + i);
                    pw.increaseIndent();
                    pw.println("mCallState=" + this.mCallState[i]);
                    pw.println("mRingingCallState=" + this.mRingingCallState[i]);
                    pw.println("mForegroundCallState=" + this.mForegroundCallState[i]);
                    pw.println("mBackgroundCallState=" + this.mBackgroundCallState[i]);
                    pw.println("mPreciseCallState=" + this.mPreciseCallState[i]);
                    pw.println("mCallDisconnectCause=" + this.mCallDisconnectCause[i]);
                    pw.println("mCallIncomingNumber=" + this.mCallIncomingNumber[i]);
                    pw.println("mServiceState=" + this.mServiceState[i]);
                    pw.println("mVoiceActivationState= " + this.mVoiceActivationState[i]);
                    pw.println("mDataActivationState= " + this.mDataActivationState[i]);
                    pw.println("mUserMobileDataState= " + this.mUserMobileDataState[i]);
                    pw.println("mSignalStrength=" + this.mSignalStrength[i]);
                    pw.println("mMessageWaiting=" + this.mMessageWaiting[i]);
                    pw.println("mCallForwarding=" + this.mCallForwarding[i]);
                    pw.println("mDataActivity=" + this.mDataActivity[i]);
                    pw.println("mDataConnectionState=" + this.mDataConnectionState[i]);
                    pw.println("mCellLocation=" + this.mCellLocation[i]);
                    pw.println("mCellInfo=" + this.mCellInfo.get(i));
                    pw.println("mImsCallDisconnectCause=" + this.mImsReasonInfo.get(i));
                    pw.println("mSrvccState=" + this.mSrvccState[i]);
                    pw.println("mOtaspMode=" + this.mOtaspMode[i]);
                    pw.println("mCallPreciseDisconnectCause=" + this.mCallPreciseDisconnectCause[i]);
                    pw.println("mCallQuality=" + this.mCallQuality[i]);
                    pw.println("mCallAttributes=" + this.mCallAttributes[i]);
                    pw.println("mCallNetworkType=" + this.mCallNetworkType[i]);
                    pw.println("mPreciseDataConnectionState=" + this.mPreciseDataConnectionState[i]);
                    pw.decreaseIndent();
                }
                pw.println("mCarrierNetworkChangeState=" + this.mCarrierNetworkChangeState);
                pw.println("mPhoneCapability=" + this.mPhoneCapability);
                pw.println("mActiveDataSubId=" + this.mActiveDataSubId);
                pw.println("mRadioPowerState=" + this.mRadioPowerState);
                pw.println("mEmergencyNumberList=" + this.mEmergencyNumberList);
                pw.println("mDefaultPhoneId=" + this.mDefaultPhoneId);
                pw.println("mDefaultSubId=" + this.mDefaultSubId);
                pw.decreaseIndent();
                pw.println("local logs:");
                pw.increaseIndent();
                this.mLocalLog.dump(fd, pw, args);
                pw.println("listen logs:");
                this.mListenLog.dump(fd, pw, args);
                pw.decreaseIndent();
                pw.println("registrations: count=" + recordCount);
                pw.increaseIndent();
                Iterator<Record> it = this.mRecords.iterator();
                while (it.hasNext()) {
                    Record r = it.next();
                    pw.println(r);
                }
                pw.decreaseIndent();
            }
        }
    }

    private void broadcastServiceStateChanged(ServiceState state, int phoneId, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            this.mBatteryStats.notePhoneState(state.getState());
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
        Intent intent = new Intent("android.intent.action.SERVICE_STATE");
        intent.addFlags(16777216);
        Bundle data = new Bundle();
        state.fillInNotifierBundle(data);
        intent.putExtras(data);
        intent.putExtra("subscription", subId);
        intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subId);
        intent.putExtra("slot", phoneId);
        this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastSignalStrengthChanged(SignalStrength signalStrength, int phoneId, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            this.mBatteryStats.notePhoneSignalStrength(signalStrength);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
        Intent intent = new Intent("android.intent.action.SIG_STR");
        Bundle data = new Bundle();
        signalStrength.fillInNotifierBundle(data);
        intent.putExtras(data);
        intent.putExtra("subscription", subId);
        intent.putExtra("slot", phoneId);
        this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastCallStateChanged(int state, String incomingNumber, int phoneId, int subId) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (state == 0) {
                this.mBatteryStats.notePhoneOff();
                StatsLog.write(95, 0);
            } else {
                this.mBatteryStats.notePhoneOn();
                StatsLog.write(95, 1);
            }
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
        Intent intent = new Intent("android.intent.action.PHONE_STATE");
        intent.putExtra("state", PhoneConstantConversions.convertCallState(state).toString());
        if (subId != -1) {
            intent.setAction("android.intent.action.SUBSCRIPTION_PHONE_STATE");
            intent.putExtra("subscription", subId);
            intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", subId);
        }
        if (phoneId != -1) {
            intent.putExtra("slot", phoneId);
        }
        intent.addFlags(16777216);
        Intent intentWithPhoneNumber = new Intent(intent);
        intentWithPhoneNumber.putExtra("incoming_number", incomingNumber);
        this.mContext.sendBroadcastAsUser(intentWithPhoneNumber, UserHandle.ALL, "android.permission.READ_PRIVILEGED_PHONE_STATE");
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, "android.permission.READ_PHONE_STATE", 51);
        this.mContext.sendBroadcastAsUserMultiplePermissions(intentWithPhoneNumber, UserHandle.ALL, new String[]{"android.permission.READ_PHONE_STATE", "android.permission.READ_CALL_LOG"});
    }

    private void broadcastDataConnectionStateChanged(int state, boolean isDataAllowed, String apn, String apnType, LinkProperties linkProperties, NetworkCapabilities networkCapabilities, boolean roaming, int subId) {
        Intent intent = new Intent("android.intent.action.ANY_DATA_STATE");
        intent.putExtra("state", PhoneConstantConversions.convertDataState(state).toString());
        if (!isDataAllowed) {
            intent.putExtra("networkUnvailable", true);
        }
        if (linkProperties != null) {
            intent.putExtra("linkProperties", linkProperties);
            String iface = linkProperties.getInterfaceName();
            if (iface != null) {
                intent.putExtra("iface", iface);
            }
        }
        if (networkCapabilities != null) {
            intent.putExtra("networkCapabilities", networkCapabilities);
        }
        if (roaming) {
            intent.putExtra("networkRoaming", true);
        }
        intent.putExtra("apn", apn);
        intent.putExtra("apnType", apnType);
        intent.putExtra("subscription", subId);
        this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastDataConnectionFailed(String apnType, int subId) {
        Intent intent = new Intent("android.intent.action.DATA_CONNECTION_FAILED");
        intent.putExtra("apnType", apnType);
        intent.putExtra("subscription", subId);
        this.mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void broadcastPreciseCallStateChanged(int ringingCallState, int foregroundCallState, int backgroundCallState) {
        Intent intent = new Intent("android.intent.action.PRECISE_CALL_STATE");
        intent.putExtra("ringing_state", ringingCallState);
        intent.putExtra("foreground_state", foregroundCallState);
        intent.putExtra("background_state", backgroundCallState);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, "android.permission.READ_PRECISE_PHONE_STATE");
    }

    private void broadcastPreciseDataConnectionStateChanged(int state, int networkType, String apnType, String apn, LinkProperties linkProperties, int failCause) {
        Intent intent = new Intent("android.intent.action.PRECISE_DATA_CONNECTION_STATE_CHANGED");
        intent.putExtra("state", state);
        intent.putExtra("networkType", networkType);
        if (apnType != null) {
            intent.putExtra("apnType", apnType);
        }
        if (apn != null) {
            intent.putExtra("apn", apn);
        }
        if (linkProperties != null) {
            intent.putExtra("linkProperties", linkProperties);
        }
        intent.putExtra("failCause", failCause);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, "android.permission.READ_PRECISE_PHONE_STATE");
    }

    private void enforceNotifyPermissionOrCarrierPrivilege(String method) {
        if (checkNotifyPermission()) {
            return;
        }
        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(SubscriptionManager.getDefaultSubscriptionId(), method);
    }

    private boolean checkNotifyPermission(String method) {
        if (checkNotifyPermission()) {
            return true;
        }
        String str = "Modify Phone State Permission Denial: " + method + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid();
        return false;
    }

    private boolean checkNotifyPermission() {
        return this.mContext.checkCallingOrSelfPermission("android.permission.MODIFY_PHONE_STATE") == 0;
    }

    private boolean checkListenerPermission(int events, int subId, String callingPackage, String message) {
        LocationAccessPolicy.LocationPermissionQuery.Builder callingPackage2 = new LocationAccessPolicy.LocationPermissionQuery.Builder().setCallingPackage(callingPackage);
        LocationAccessPolicy.LocationPermissionQuery.Builder locationQueryBuilder = callingPackage2.setMethod(message + " events: " + events).setCallingPid(Binder.getCallingPid()).setCallingUid(Binder.getCallingUid());
        if ((events & ENFORCE_LOCATION_PERMISSION_MASK) != 0) {
            locationQueryBuilder.setMinSdkVersionForFine(29);
            locationQueryBuilder.setMinSdkVersionForCoarse(0);
            LocationAccessPolicy.LocationPermissionResult result = LocationAccessPolicy.checkLocationPermission(this.mContext, locationQueryBuilder.build());
            int i = AnonymousClass3.$SwitchMap$android$telephony$LocationAccessPolicy$LocationPermissionResult[result.ordinal()];
            if (i == 1) {
                throw new SecurityException("Unable to listen for events " + events + " due to insufficient location permissions.");
            } else if (i == 2) {
                return false;
            }
        }
        if ((ENFORCE_PHONE_STATE_PERMISSION_MASK & events) == 0 || TelephonyPermissions.checkCallingOrSelfReadPhoneState(this.mContext, subId, callingPackage, message)) {
            if ((events & PRECISE_PHONE_STATE_PERMISSION_MASK) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRECISE_PHONE_STATE", null);
            }
            if ((32768 & events) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE", null);
            }
            if ((events & 16384) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE", null);
            }
            if ((33554432 & events) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRECISE_PHONE_STATE", null);
            }
            if ((67108864 & events) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRECISE_PHONE_STATE", null);
            }
            if ((8388608 & events) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE", null);
            }
            if ((131072 & events) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE", null);
            }
            if ((134217728 & events) != 0) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.READ_PRECISE_PHONE_STATE", null);
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.TelephonyRegistry$3  reason: invalid class name */
    /* loaded from: classes.dex */
    public static /* synthetic */ class AnonymousClass3 {
        static final /* synthetic */ int[] $SwitchMap$android$telephony$LocationAccessPolicy$LocationPermissionResult = new int[LocationAccessPolicy.LocationPermissionResult.values().length];

        static {
            try {
                $SwitchMap$android$telephony$LocationAccessPolicy$LocationPermissionResult[LocationAccessPolicy.LocationPermissionResult.DENIED_HARD.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$android$telephony$LocationAccessPolicy$LocationPermissionResult[LocationAccessPolicy.LocationPermissionResult.DENIED_SOFT.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRemoveListLocked() {
        int size = this.mRemoveList.size();
        if (size > 0) {
            Iterator<IBinder> it = this.mRemoveList.iterator();
            while (it.hasNext()) {
                IBinder b = it.next();
                remove(b);
            }
            this.mRemoveList.clear();
        }
    }

    private boolean validateEventsAndUserLocked(Record r, int events) {
        boolean z;
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            int foregroundUser = ActivityManager.getCurrentUser();
            if (UserHandle.getUserId(r.callerUid) == foregroundUser) {
                if (r.matchPhoneStateListenerEvent(events)) {
                    z = true;
                    boolean valid = z;
                    return valid;
                }
            }
            z = false;
            boolean valid2 = z;
            return valid2;
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean validatePhoneId(int phoneId) {
        return phoneId >= 0 && phoneId < this.mNumPhones;
    }

    private static void log(String s) {
        Rlog.d(TAG, s);
    }

    private static void loge(String s) {
        Rlog.e(TAG, s);
    }

    boolean idMatch(int rSubId, int subId, int phoneId) {
        return subId < 0 ? this.mDefaultPhoneId == phoneId : rSubId == Integer.MAX_VALUE ? subId == this.mDefaultSubId : rSubId == subId;
    }

    private boolean checkFineLocationAccess(Record r, int minSdk) {
        final LocationAccessPolicy.LocationPermissionQuery query = new LocationAccessPolicy.LocationPermissionQuery.Builder().setCallingPackage(r.callingPackage).setCallingPid(r.callerPid).setCallingUid(r.callerUid).setMethod("TelephonyRegistry push").setLogAsInfo(true).setMinSdkVersionForFine(minSdk).build();
        return ((Boolean) Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingSupplier() { // from class: com.android.server.-$$Lambda$TelephonyRegistry$H1zNBtijaPaJZvAGiWo3ze1HvCk
            public final Object getOrThrow() {
                return TelephonyRegistry.this.lambda$checkFineLocationAccess$2$TelephonyRegistry(query);
            }
        })).booleanValue();
    }

    public /* synthetic */ Boolean lambda$checkFineLocationAccess$2$TelephonyRegistry(LocationAccessPolicy.LocationPermissionQuery query) throws Exception {
        LocationAccessPolicy.LocationPermissionResult locationResult = LocationAccessPolicy.checkLocationPermission(this.mContext, query);
        return Boolean.valueOf(locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED);
    }

    private boolean checkCoarseLocationAccess(Record r, int minSdk) {
        final LocationAccessPolicy.LocationPermissionQuery query = new LocationAccessPolicy.LocationPermissionQuery.Builder().setCallingPackage(r.callingPackage).setCallingPid(r.callerPid).setCallingUid(r.callerUid).setMethod("TelephonyRegistry push").setLogAsInfo(true).setMinSdkVersionForCoarse(minSdk).build();
        return ((Boolean) Binder.withCleanCallingIdentity(new FunctionalUtils.ThrowingSupplier() { // from class: com.android.server.-$$Lambda$TelephonyRegistry$KVoaCNOF-BlPejFaEBKjW11o5Po
            public final Object getOrThrow() {
                return TelephonyRegistry.this.lambda$checkCoarseLocationAccess$3$TelephonyRegistry(query);
            }
        })).booleanValue();
    }

    public /* synthetic */ Boolean lambda$checkCoarseLocationAccess$3$TelephonyRegistry(LocationAccessPolicy.LocationPermissionQuery query) throws Exception {
        LocationAccessPolicy.LocationPermissionResult locationResult = LocationAccessPolicy.checkLocationPermission(this.mContext, query);
        return Boolean.valueOf(locationResult == LocationAccessPolicy.LocationPermissionResult.ALLOWED);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void checkPossibleMissNotify(Record r, int phoneId) {
        int events = r.events;
        if ((events & 1) != 0) {
            try {
                ServiceState ss = new ServiceState(this.mServiceState[phoneId]);
                if (checkFineLocationAccess(r, 29)) {
                    r.callback.onServiceStateChanged(ss);
                } else if (checkCoarseLocationAccess(r, 29)) {
                    r.callback.onServiceStateChanged(ss.sanitizeLocationInfo(false));
                } else {
                    r.callback.onServiceStateChanged(ss.sanitizeLocationInfo(true));
                }
            } catch (RemoteException e) {
                this.mRemoveList.add(r.binder);
            }
        }
        if ((events & 256) != 0) {
            try {
                SignalStrength signalStrength = this.mSignalStrength[phoneId];
                r.callback.onSignalStrengthsChanged(new SignalStrength(signalStrength));
            } catch (RemoteException e2) {
                this.mRemoveList.add(r.binder);
            }
        }
        if ((events & 2) != 0) {
            try {
                int gsmSignalStrength = this.mSignalStrength[phoneId].getGsmSignalStrength();
                r.callback.onSignalStrengthChanged(gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
            } catch (RemoteException e3) {
                this.mRemoveList.add(r.binder);
            }
        }
        if (validateEventsAndUserLocked(r, 1024)) {
            try {
                if (checkCoarseLocationAccess(r, 1) && checkFineLocationAccess(r, 29)) {
                    r.callback.onCellInfoChanged(this.mCellInfo.get(phoneId));
                }
            } catch (RemoteException e4) {
                this.mRemoveList.add(r.binder);
            }
        }
        if ((524288 & events) != 0) {
            try {
                r.callback.onUserMobileDataStateChanged(this.mUserMobileDataState[phoneId]);
            } catch (RemoteException e5) {
                this.mRemoveList.add(r.binder);
            }
        }
        if ((events & 4) != 0) {
            try {
                r.callback.onMessageWaitingIndicatorChanged(this.mMessageWaiting[phoneId]);
            } catch (RemoteException e6) {
                this.mRemoveList.add(r.binder);
            }
        }
        if ((events & 8) != 0) {
            try {
                r.callback.onCallForwardingIndicatorChanged(this.mCallForwarding[phoneId]);
            } catch (RemoteException e7) {
                this.mRemoveList.add(r.binder);
            }
        }
        if (validateEventsAndUserLocked(r, 16)) {
            try {
                if (checkCoarseLocationAccess(r, 1) && checkFineLocationAccess(r, 29)) {
                    r.callback.onCellLocationChanged(this.mCellLocation[phoneId]);
                }
            } catch (RemoteException e8) {
                this.mRemoveList.add(r.binder);
            }
        }
        if ((events & 64) != 0) {
            try {
                r.callback.onDataConnectionStateChanged(this.mDataConnectionState[phoneId], this.mDataConnectionNetworkType[phoneId]);
            } catch (RemoteException e9) {
                this.mRemoveList.add(r.binder);
            }
        }
    }
}
