package com.android.server.connectivity.tethering;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telephony.CarrierConfigManager;
import android.util.ArraySet;
import android.util.SparseIntArray;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StateMachine;
import com.android.server.connectivity.MockableSystemProperties;
import java.io.PrintWriter;
import java.util.Iterator;

/* loaded from: classes.dex */
public class EntitlementManager {
    private static final String ACTION_PROVISIONING_ALARM = "com.android.server.connectivity.tethering.PROVISIONING_RECHECK_ALARM";
    private static final boolean DBG = false;
    @VisibleForTesting
    protected static final String DISABLE_PROVISIONING_SYSPROP_KEY = "net.tethering.noprovisioning";
    private static final int EVENT_GET_ENTITLEMENT_VALUE = 4;
    private static final int EVENT_MAYBE_RUN_PROVISIONING = 3;
    private static final int EVENT_START_PROVISIONING = 0;
    private static final int EVENT_STOP_PROVISIONING = 1;
    private static final int EVENT_UPSTREAM_CHANGED = 2;
    private static final String EXTRA_SUBID = "subId";
    private static final int MS_PER_HOUR = 3600000;
    private static final String TAG = EntitlementManager.class.getSimpleName();
    private static final ComponentName TETHER_SERVICE = ComponentName.unflattenFromString(Resources.getSystem().getString(17039792));
    private final Context mContext;
    private TetheringConfigurationFetcher mFetcher;
    private final EntitlementHandler mHandler;
    private OnUiEntitlementFailedListener mListener;
    private final SharedLog mLog;
    private final int mPermissionChangeMessageCode;
    private PendingIntent mProvisioningRecheckAlarm;
    private final MockableSystemProperties mSystemProperties;
    private final StateMachine mTetherMasterSM;
    private boolean mCellularUpstreamPermitted = true;
    private boolean mUsingCellularAsUpstream = false;
    private boolean mNeedReRunProvisioningUi = false;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() { // from class: com.android.server.connectivity.tethering.EntitlementManager.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (EntitlementManager.ACTION_PROVISIONING_ALARM.equals(intent.getAction())) {
                EntitlementManager.this.mLog.log("Received provisioning alarm");
                TetheringConfiguration config = EntitlementManager.this.mFetcher.fetchTetheringConfiguration();
                EntitlementManager.this.reevaluateSimCardProvisioning(config);
            }
        }
    };
    private final ArraySet<Integer> mCurrentTethers = new ArraySet<>();
    private final SparseIntArray mCellularPermitted = new SparseIntArray();
    private final SparseIntArray mEntitlementCacheValue = new SparseIntArray();

    /* loaded from: classes.dex */
    public interface OnUiEntitlementFailedListener {
        void onUiEntitlementFailed(int i);
    }

    /* loaded from: classes.dex */
    public interface TetheringConfigurationFetcher {
        TetheringConfiguration fetchTetheringConfiguration();
    }

    public EntitlementManager(Context ctx, StateMachine tetherMasterSM, SharedLog log, int permissionChangeMessageCode, MockableSystemProperties systemProperties) {
        this.mContext = ctx;
        this.mLog = log.forSubComponent(TAG);
        this.mSystemProperties = systemProperties;
        this.mTetherMasterSM = tetherMasterSM;
        this.mPermissionChangeMessageCode = permissionChangeMessageCode;
        Handler masterHandler = tetherMasterSM.getHandler();
        this.mHandler = new EntitlementHandler(masterHandler.getLooper());
        this.mContext.registerReceiver(this.mReceiver, new IntentFilter(ACTION_PROVISIONING_ALARM), null, this.mHandler);
    }

    public void setOnUiEntitlementFailedListener(OnUiEntitlementFailedListener listener) {
        this.mListener = listener;
    }

    public void setTetheringConfigurationFetcher(TetheringConfigurationFetcher fetcher) {
        this.mFetcher = fetcher;
    }

    public boolean isCellularUpstreamPermitted() {
        return this.mCellularUpstreamPermitted;
    }

    public void startProvisioningIfNeeded(int downstreamType, boolean showProvisioningUi) {
        EntitlementHandler entitlementHandler = this.mHandler;
        entitlementHandler.sendMessage(entitlementHandler.obtainMessage(0, downstreamType, encodeBool(showProvisioningUi)));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleStartProvisioningIfNeeded(int type, boolean showProvisioningUi) {
        if (isValidDownstreamType(type)) {
            if (!this.mCurrentTethers.contains(Integer.valueOf(type))) {
                this.mCurrentTethers.add(Integer.valueOf(type));
            }
            TetheringConfiguration config = this.mFetcher.fetchTetheringConfiguration();
            if (isTetherProvisioningRequired(config)) {
                if (this.mCellularPermitted.size() == 0) {
                    this.mCellularUpstreamPermitted = false;
                }
                if (this.mUsingCellularAsUpstream) {
                    if (showProvisioningUi) {
                        runUiTetherProvisioning(type, config.subId);
                    } else {
                        runSilentTetherProvisioning(type, config.subId);
                    }
                    this.mNeedReRunProvisioningUi = false;
                    return;
                }
                this.mNeedReRunProvisioningUi |= showProvisioningUi;
                return;
            }
            this.mCellularUpstreamPermitted = true;
        }
    }

    public void stopProvisioningIfNeeded(int type) {
        EntitlementHandler entitlementHandler = this.mHandler;
        entitlementHandler.sendMessage(entitlementHandler.obtainMessage(1, type, 0));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleStopProvisioningIfNeeded(int type) {
        if (isValidDownstreamType(type)) {
            this.mCurrentTethers.remove(Integer.valueOf(type));
            removeDownstreamMapping(type);
        }
    }

    public void notifyUpstream(boolean isCellular) {
        EntitlementHandler entitlementHandler = this.mHandler;
        entitlementHandler.sendMessage(entitlementHandler.obtainMessage(2, encodeBool(isCellular), 0));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleNotifyUpstream(boolean isCellular) {
        this.mUsingCellularAsUpstream = isCellular;
        if (this.mUsingCellularAsUpstream) {
            TetheringConfiguration config = this.mFetcher.fetchTetheringConfiguration();
            handleMaybeRunProvisioning(config);
        }
    }

    public void maybeRunProvisioning() {
        EntitlementHandler entitlementHandler = this.mHandler;
        entitlementHandler.sendMessage(entitlementHandler.obtainMessage(3));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleMaybeRunProvisioning(TetheringConfiguration config) {
        if (this.mCurrentTethers.size() == 0 || !isTetherProvisioningRequired(config)) {
            return;
        }
        Iterator<Integer> it = this.mCurrentTethers.iterator();
        while (it.hasNext()) {
            Integer downstream = it.next();
            if (this.mCellularPermitted.indexOfKey(downstream.intValue()) < 0) {
                if (this.mNeedReRunProvisioningUi) {
                    this.mNeedReRunProvisioningUi = false;
                    runUiTetherProvisioning(downstream.intValue(), config.subId);
                } else {
                    runSilentTetherProvisioning(downstream.intValue(), config.subId);
                }
            }
        }
    }

    @VisibleForTesting
    protected boolean isTetherProvisioningRequired(TetheringConfiguration config) {
        return (this.mSystemProperties.getBoolean(DISABLE_PROVISIONING_SYSPROP_KEY, false) || config.provisioningApp.length == 0 || carrierConfigAffirmsEntitlementCheckNotRequired(config) || config.provisioningApp.length != 2) ? false : true;
    }

    public void reevaluateSimCardProvisioning(TetheringConfiguration config) {
        if (!this.mHandler.getLooper().isCurrentThread()) {
            this.mLog.log("reevaluateSimCardProvisioning() don't run in TetherMaster thread");
        }
        this.mEntitlementCacheValue.clear();
        this.mCellularPermitted.clear();
        if (!config.hasMobileHotspotProvisionApp() || carrierConfigAffirmsEntitlementCheckNotRequired(config)) {
            evaluateCellularPermission(config);
        } else if (this.mUsingCellularAsUpstream) {
            handleMaybeRunProvisioning(config);
        }
    }

    public PersistableBundle getCarrierConfig(TetheringConfiguration config) {
        CarrierConfigManager configManager = (CarrierConfigManager) this.mContext.getSystemService("carrier_config");
        if (configManager == null) {
            return null;
        }
        PersistableBundle carrierConfig = configManager.getConfigForSubId(config.subId);
        if (!CarrierConfigManager.isConfigForIdentifiedCarrier(carrierConfig)) {
            return null;
        }
        return carrierConfig;
    }

    private boolean carrierConfigAffirmsEntitlementCheckNotRequired(TetheringConfiguration config) {
        PersistableBundle carrierConfig = getCarrierConfig(config);
        if (carrierConfig == null) {
            return false;
        }
        boolean isEntitlementCheckRequired = carrierConfig.getBoolean("require_entitlement_checks_bool");
        return !isEntitlementCheckRequired;
    }

    @VisibleForTesting
    protected void runSilentTetherProvisioning(int type, int subId) {
        ResultReceiver receiver = buildProxyReceiver(type, false, null);
        Intent intent = new Intent();
        intent.putExtra("extraAddTetherType", type);
        intent.putExtra("extraRunProvision", true);
        intent.putExtra("extraProvisionCallback", receiver);
        intent.putExtra(EXTRA_SUBID, subId);
        intent.setComponent(TETHER_SERVICE);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void runUiTetherProvisioning(int type, int subId) {
        ResultReceiver receiver = buildProxyReceiver(type, true, null);
        runUiTetherProvisioning(type, subId, receiver);
    }

    @VisibleForTesting
    protected void runUiTetherProvisioning(int type, int subId, ResultReceiver receiver) {
        Intent intent = new Intent("android.settings.TETHER_PROVISIONING_UI");
        intent.putExtra("extraAddTetherType", type);
        intent.putExtra("extraProvisionCallback", receiver);
        intent.putExtra(EXTRA_SUBID, subId);
        intent.addFlags(268435456);
        long ident = Binder.clearCallingIdentity();
        try {
            this.mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void scheduleProvisioningRechecks(TetheringConfiguration config) {
        int period;
        if (this.mProvisioningRecheckAlarm != null || (period = config.provisioningCheckPeriod) <= 0) {
            return;
        }
        Intent intent = new Intent(ACTION_PROVISIONING_ALARM);
        this.mProvisioningRecheckAlarm = PendingIntent.getBroadcast(this.mContext, 0, intent, 0);
        AlarmManager alarmManager = (AlarmManager) this.mContext.getSystemService("alarm");
        long periodMs = 3600000 * period;
        long firstAlarmTime = SystemClock.elapsedRealtime() + periodMs;
        alarmManager.setRepeating(3, firstAlarmTime, periodMs, this.mProvisioningRecheckAlarm);
    }

    private void cancelTetherProvisioningRechecks() {
        if (this.mProvisioningRecheckAlarm != null) {
            AlarmManager alarmManager = (AlarmManager) this.mContext.getSystemService("alarm");
            alarmManager.cancel(this.mProvisioningRecheckAlarm);
            this.mProvisioningRecheckAlarm = null;
        }
    }

    private void evaluateCellularPermission(TetheringConfiguration config) {
        boolean oldPermitted = this.mCellularUpstreamPermitted;
        boolean z = false;
        this.mCellularUpstreamPermitted = (!isTetherProvisioningRequired(config) || this.mCellularPermitted.indexOfValue(0) > -1) ? true : true;
        if (this.mCellularUpstreamPermitted != oldPermitted) {
            SharedLog sharedLog = this.mLog;
            sharedLog.log("Cellular permission change: " + this.mCellularUpstreamPermitted);
            this.mTetherMasterSM.sendMessage(this.mPermissionChangeMessageCode);
        }
        if (this.mCellularUpstreamPermitted && this.mCellularPermitted.size() > 0) {
            scheduleProvisioningRechecks(config);
        } else {
            cancelTetherProvisioningRechecks();
        }
    }

    protected void addDownstreamMapping(int type, int resultCode) {
        SharedLog sharedLog = this.mLog;
        sharedLog.i("addDownstreamMapping: " + type + ", result: " + resultCode + " ,TetherTypeRequested: " + this.mCurrentTethers.contains(Integer.valueOf(type)));
        if (this.mCurrentTethers.contains(Integer.valueOf(type))) {
            this.mCellularPermitted.put(type, resultCode);
            TetheringConfiguration config = this.mFetcher.fetchTetheringConfiguration();
            evaluateCellularPermission(config);
        }
    }

    protected void removeDownstreamMapping(int type) {
        SharedLog sharedLog = this.mLog;
        sharedLog.i("removeDownstreamMapping: " + type);
        this.mCellularPermitted.delete(type);
        TetheringConfiguration config = this.mFetcher.fetchTetheringConfiguration();
        evaluateCellularPermission(config);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class EntitlementHandler extends Handler {
        EntitlementHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 0) {
                EntitlementManager.this.handleStartProvisioningIfNeeded(msg.arg1, EntitlementManager.toBool(msg.arg2));
            } else if (i == 1) {
                EntitlementManager.this.handleStopProvisioningIfNeeded(msg.arg1);
            } else if (i == 2) {
                EntitlementManager.this.handleNotifyUpstream(EntitlementManager.toBool(msg.arg1));
            } else if (i == 3) {
                TetheringConfiguration config = EntitlementManager.this.mFetcher.fetchTetheringConfiguration();
                EntitlementManager.this.handleMaybeRunProvisioning(config);
            } else if (i != 4) {
                SharedLog sharedLog = EntitlementManager.this.mLog;
                sharedLog.log("Unknown event: " + msg.what);
            } else {
                EntitlementManager.this.handleGetLatestTetheringEntitlementValue(msg.arg1, (ResultReceiver) msg.obj, EntitlementManager.toBool(msg.arg2));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean toBool(int encodedBoolean) {
        return encodedBoolean != 0;
    }

    private static int encodeBool(boolean b) {
        return b ? 1 : 0;
    }

    private static boolean isValidDownstreamType(int type) {
        if (type == 0 || type == 1 || type == 2) {
            return true;
        }
        return false;
    }

    public void dump(PrintWriter pw) {
        pw.print("mCellularUpstreamPermitted: ");
        pw.println(this.mCellularUpstreamPermitted);
        Iterator<Integer> it = this.mCurrentTethers.iterator();
        while (it.hasNext()) {
            Integer type = it.next();
            pw.print("Type: ");
            pw.print(typeString(type.intValue()));
            if (this.mCellularPermitted.indexOfKey(type.intValue()) > -1) {
                pw.print(", Value: ");
                pw.println(errorString(this.mCellularPermitted.get(type.intValue())));
            } else {
                pw.println(", Value: empty");
            }
        }
    }

    private static String typeString(int type) {
        if (type != -1) {
            if (type != 0) {
                if (type != 1) {
                    return type != 2 ? String.format("TETHERING UNKNOWN TYPE (%d)", Integer.valueOf(type)) : "TETHERING_BLUETOOTH";
                }
                return "TETHERING_USB";
            }
            return "TETHERING_WIFI";
        }
        return "TETHERING_INVALID";
    }

    private static String errorString(int value) {
        if (value != 0) {
            if (value != 11) {
                return value != 13 ? String.format("UNKNOWN ERROR (%d)", Integer.valueOf(value)) : "TETHER_ERROR_ENTITLEMENT_UNKONWN";
            }
            return "TETHER_ERROR_PROVISION_FAILED";
        }
        return "TETHER_ERROR_NO_ERROR";
    }

    private ResultReceiver buildProxyReceiver(final int type, final boolean notifyFail, final ResultReceiver receiver) {
        ResultReceiver rr = new ResultReceiver(this.mHandler) { // from class: com.android.server.connectivity.tethering.EntitlementManager.2
            @Override // android.os.ResultReceiver
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                int updatedCacheValue = EntitlementManager.this.updateEntitlementCacheValue(type, resultCode);
                EntitlementManager.this.addDownstreamMapping(type, updatedCacheValue);
                if (updatedCacheValue == 11 && notifyFail) {
                    EntitlementManager.this.mListener.onUiEntitlementFailed(type);
                }
                ResultReceiver resultReceiver = receiver;
                if (resultReceiver != null) {
                    resultReceiver.send(updatedCacheValue, null);
                }
            }
        };
        return writeToParcel(rr);
    }

    private ResultReceiver writeToParcel(ResultReceiver receiver) {
        Parcel parcel = Parcel.obtain();
        receiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ResultReceiver receiverForSending = (ResultReceiver) ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return receiverForSending;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int updateEntitlementCacheValue(int type, int resultCode) {
        if (resultCode == 0) {
            this.mEntitlementCacheValue.put(type, resultCode);
            return resultCode;
        }
        this.mEntitlementCacheValue.put(type, 11);
        return 11;
    }

    public void getLatestTetheringEntitlementResult(int downstream, ResultReceiver receiver, boolean showEntitlementUi) {
        EntitlementHandler entitlementHandler = this.mHandler;
        entitlementHandler.sendMessage(entitlementHandler.obtainMessage(4, downstream, encodeBool(showEntitlementUi), receiver));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleGetLatestTetheringEntitlementValue(int downstream, ResultReceiver receiver, boolean showEntitlementUi) {
        TetheringConfiguration config = this.mFetcher.fetchTetheringConfiguration();
        if (!isTetherProvisioningRequired(config)) {
            receiver.send(0, null);
            return;
        }
        int cacheValue = this.mEntitlementCacheValue.get(downstream, 13);
        if (cacheValue == 0 || !showEntitlementUi) {
            receiver.send(cacheValue, null);
            return;
        }
        ResultReceiver proxy = buildProxyReceiver(downstream, false, receiver);
        runUiTetherProvisioning(downstream, config.subId, proxy);
    }
}
