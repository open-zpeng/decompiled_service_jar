package com.android.server.connectivity;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.CaptivePortal;
import android.net.ICaptivePortal;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.captiveportal.CaptivePortalProbeResult;
import android.net.captiveportal.CaptivePortalProbeSpec;
import android.net.dns.ResolvUtil;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.metrics.ValidationProbeEvent;
import android.net.util.Stopwatch;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.connectivity.DnsManager;
import com.android.server.slice.SliceClientPermissions;
import com.xiaopeng.server.input.xpInputActionHandler;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/* loaded from: classes.dex */
public class NetworkMonitor extends StateMachine {
    private static final int BASE = 532480;
    private static final int BLAME_FOR_EVALUATION_ATTEMPTS = 5;
    private static final int CAPTIVE_PORTAL_REEVALUATE_DELAY_MS = 600000;
    private static final int CMD_CAPTIVE_PORTAL_APP_FINISHED = 532489;
    private static final int CMD_CAPTIVE_PORTAL_RECHECK = 532492;
    private static final int CMD_EVALUATE_PRIVATE_DNS = 532495;
    private static final int CMD_FORCE_REEVALUATION = 532488;
    public static final int CMD_LAUNCH_CAPTIVE_PORTAL_APP = 532491;
    public static final int CMD_NETWORK_CONNECTED = 532481;
    public static final int CMD_NETWORK_DISCONNECTED = 532487;
    private static final int CMD_PRIVATE_DNS_SETTINGS_CHANGED = 532493;
    private static final int CMD_REEVALUATE = 532486;
    private static final boolean DBG = true;
    private static final String DEFAULT_FALLBACK_URL = "http://www.google.com/gen_204";
    private static final String DEFAULT_HTTPS_URL = "https://www.google.com/generate_204";
    private static final String DEFAULT_HTTP_URL = "http://connectivitycheck.gstatic.com/generate_204";
    private static final String DEFAULT_OTHER_FALLBACK_URLS = "http://play.googleapis.com/generate_204";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.32 Safari/537.36";
    public static final int EVENT_NETWORK_TESTED = 532482;
    public static final int EVENT_PRIVATE_DNS_CONFIG_RESOLVED = 532494;
    public static final int EVENT_PROVISIONING_NOTIFICATION = 532490;
    private static final int IGNORE_REEVALUATE_ATTEMPTS = 5;
    private static final int INITIAL_REEVALUATE_DELAY_MS = 1000;
    private static final int INVALID_UID = -1;
    private static final int MAX_REEVALUATE_DELAY_MS = 600000;
    public static final int NETWORK_TEST_RESULT_INVALID = 1;
    public static final int NETWORK_TEST_RESULT_VALID = 0;
    private static final int NO_UID = 0;
    private static final int NUM_VALIDATION_LOG_LINES = 20;
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final int SOCKET_TIMEOUT_MS = 10000;
    private static final String TAG = NetworkMonitor.class.getSimpleName();
    private static final boolean VDBG = false;
    private final CaptivePortalProbeSpec[] mCaptivePortalFallbackSpecs;
    private final URL[] mCaptivePortalFallbackUrls;
    private final URL mCaptivePortalHttpUrl;
    private final URL mCaptivePortalHttpsUrl;
    private final State mCaptivePortalState;
    private final String mCaptivePortalUserAgent;
    private final Handler mConnectivityServiceHandler;
    private final Context mContext;
    private final NetworkRequest mDefaultRequest;
    private final State mDefaultState;
    private boolean mDontDisplaySigninNotification;
    private final State mEvaluatingPrivateDnsState;
    private final State mEvaluatingState;
    private final Stopwatch mEvaluationTimer;
    @VisibleForTesting
    protected boolean mIsCaptivePortalCheckEnabled;
    private CaptivePortalProbeResult mLastPortalProbeResult;
    private CustomIntentReceiver mLaunchCaptivePortalAppBroadcastReceiver;
    private final State mMaybeNotifyState;
    private final IpConnectivityLog mMetricsLog;
    private final int mNetId;
    private final Network mNetwork;
    private final NetworkAgentInfo mNetworkAgentInfo;
    private int mNextFallbackUrlIndex;
    private String mPrivateDnsProviderHostname;
    private int mReevaluateToken;
    private final NetworkMonitorSettings mSettings;
    private final TelephonyManager mTelephonyManager;
    private int mUidResponsibleForReeval;
    private boolean mUseHttps;
    private boolean mUserDoesNotWant;
    private final State mValidatedState;
    private int mValidations;
    private final WifiManager mWifiManager;
    public boolean systemReady;
    private final LocalLog validationLogs;

    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface NetworkMonitorSettings {
        public static final NetworkMonitorSettings DEFAULT = new DefaultNetworkMonitorSettings();

        int getSetting(Context context, String str, int i);

        String getSetting(Context context, String str, String str2);
    }

    static /* synthetic */ int access$2308(NetworkMonitor x0) {
        int i = x0.mValidations;
        x0.mValidations = i + 1;
        return i;
    }

    static /* synthetic */ int access$2904(NetworkMonitor x0) {
        int i = x0.mReevaluateToken + 1;
        x0.mReevaluateToken = i;
        return i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public enum EvaluationResult {
        VALIDATED(true),
        CAPTIVE_PORTAL(false);
        
        final boolean isValidated;

        EvaluationResult(boolean isValidated) {
            this.isValidated = isValidated;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public enum ValidationStage {
        FIRST_VALIDATION(true),
        REVALIDATION(false);
        
        final boolean isFirstValidation;

        ValidationStage(boolean isFirstValidation) {
            this.isFirstValidation = isFirstValidation;
        }
    }

    public static boolean isValidationRequired(NetworkCapabilities dfltNetCap, NetworkCapabilities nc) {
        return dfltNetCap.satisfiedByNetworkCapabilities(nc);
    }

    public NetworkMonitor(Context context, Handler handler, NetworkAgentInfo networkAgentInfo, NetworkRequest defaultRequest) {
        this(context, handler, networkAgentInfo, defaultRequest, new IpConnectivityLog(), NetworkMonitorSettings.DEFAULT);
    }

    @VisibleForTesting
    protected NetworkMonitor(Context context, Handler handler, NetworkAgentInfo networkAgentInfo, NetworkRequest defaultRequest, IpConnectivityLog logger, NetworkMonitorSettings settings) {
        super(TAG + networkAgentInfo.name());
        this.mReevaluateToken = 0;
        this.mUidResponsibleForReeval = -1;
        this.mPrivateDnsProviderHostname = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        this.mValidations = 0;
        this.mUserDoesNotWant = false;
        this.mDontDisplaySigninNotification = false;
        this.systemReady = false;
        this.mDefaultState = new DefaultState();
        this.mValidatedState = new ValidatedState();
        this.mMaybeNotifyState = new MaybeNotifyState();
        this.mEvaluatingState = new EvaluatingState();
        this.mCaptivePortalState = new CaptivePortalState();
        this.mEvaluatingPrivateDnsState = new EvaluatingPrivateDnsState();
        this.mLaunchCaptivePortalAppBroadcastReceiver = null;
        this.validationLogs = new LocalLog(20);
        this.mEvaluationTimer = new Stopwatch();
        this.mLastPortalProbeResult = CaptivePortalProbeResult.FAILED;
        this.mNextFallbackUrlIndex = 0;
        setDbg(false);
        this.mContext = context;
        this.mMetricsLog = logger;
        this.mConnectivityServiceHandler = handler;
        this.mSettings = settings;
        this.mNetworkAgentInfo = networkAgentInfo;
        this.mNetwork = new OneAddressPerFamilyNetwork(networkAgentInfo.network());
        this.mNetId = this.mNetwork.netId;
        this.mTelephonyManager = (TelephonyManager) context.getSystemService(xpInputActionHandler.MODE_PHONE);
        this.mWifiManager = (WifiManager) context.getSystemService("wifi");
        this.mDefaultRequest = defaultRequest;
        addState(this.mDefaultState);
        addState(this.mMaybeNotifyState, this.mDefaultState);
        addState(this.mEvaluatingState, this.mMaybeNotifyState);
        addState(this.mCaptivePortalState, this.mMaybeNotifyState);
        addState(this.mEvaluatingPrivateDnsState, this.mDefaultState);
        addState(this.mValidatedState, this.mDefaultState);
        setInitialState(this.mDefaultState);
        this.mIsCaptivePortalCheckEnabled = getIsCaptivePortalCheckEnabled();
        this.mUseHttps = getUseHttpsValidation();
        this.mCaptivePortalUserAgent = getCaptivePortalUserAgent();
        this.mCaptivePortalHttpsUrl = makeURL(getCaptivePortalServerHttpsUrl());
        this.mCaptivePortalHttpUrl = makeURL(getCaptivePortalServerHttpUrl(settings, context));
        this.mCaptivePortalFallbackUrls = makeCaptivePortalFallbackUrls();
        this.mCaptivePortalFallbackSpecs = makeCaptivePortalFallbackProbeSpecs();
        start();
    }

    public void forceReevaluation(int responsibleUid) {
        sendMessage(CMD_FORCE_REEVALUATION, responsibleUid, 0);
    }

    public void notifyPrivateDnsSettingsChanged(DnsManager.PrivateDnsConfig newCfg) {
        removeMessages(CMD_PRIVATE_DNS_SETTINGS_CHANGED);
        sendMessage(CMD_PRIVATE_DNS_SETTINGS_CHANGED, newCfg);
    }

    protected void log(String s) {
        Log.d(TAG + SliceClientPermissions.SliceAuthority.DELIMITER + this.mNetworkAgentInfo.name(), s);
    }

    private void validationLog(int probeType, Object url, String msg) {
        String probeName = ValidationProbeEvent.getProbeName(probeType);
        validationLog(String.format("%s %s %s", probeName, url, msg));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void validationLog(String s) {
        log(s);
        this.validationLogs.log(s);
    }

    public LocalLog.ReadOnlyLocalLog getValidationLogs() {
        return this.validationLogs.readOnlyLocalLog();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ValidationStage validationStage() {
        return this.mValidations == 0 ? ValidationStage.FIRST_VALIDATION : ValidationStage.REVALIDATION;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isValidationRequired() {
        return isValidationRequired(this.mDefaultRequest.networkCapabilities, this.mNetworkAgentInfo.networkCapabilities);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyNetworkTestResultInvalid(Object obj) {
        this.mConnectivityServiceHandler.sendMessage(obtainMessage(EVENT_NETWORK_TESTED, 1, this.mNetId, obj));
    }

    /* loaded from: classes.dex */
    private class DefaultState extends State {
        private DefaultState() {
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        public boolean processMessage(Message message) {
            switch (message.what) {
                case NetworkMonitor.CMD_NETWORK_CONNECTED /* 532481 */:
                    NetworkMonitor.this.logNetworkEvent(1);
                    NetworkMonitor.this.transitionTo(NetworkMonitor.this.mEvaluatingState);
                    return true;
                case NetworkMonitor.CMD_NETWORK_DISCONNECTED /* 532487 */:
                    NetworkMonitor.this.logNetworkEvent(7);
                    if (NetworkMonitor.this.mLaunchCaptivePortalAppBroadcastReceiver != null) {
                        NetworkMonitor.this.mContext.unregisterReceiver(NetworkMonitor.this.mLaunchCaptivePortalAppBroadcastReceiver);
                        NetworkMonitor.this.mLaunchCaptivePortalAppBroadcastReceiver = null;
                    }
                    NetworkMonitor.this.quit();
                    return true;
                case NetworkMonitor.CMD_FORCE_REEVALUATION /* 532488 */:
                case NetworkMonitor.CMD_CAPTIVE_PORTAL_RECHECK /* 532492 */:
                    NetworkMonitor networkMonitor = NetworkMonitor.this;
                    networkMonitor.log("Forcing reevaluation for UID " + message.arg1);
                    NetworkMonitor.this.mUidResponsibleForReeval = message.arg1;
                    NetworkMonitor.this.transitionTo(NetworkMonitor.this.mEvaluatingState);
                    return true;
                case NetworkMonitor.CMD_CAPTIVE_PORTAL_APP_FINISHED /* 532489 */:
                    NetworkMonitor networkMonitor2 = NetworkMonitor.this;
                    networkMonitor2.log("CaptivePortal App responded with " + message.arg1);
                    NetworkMonitor.this.mUseHttps = false;
                    switch (message.arg1) {
                        case 0:
                            NetworkMonitor.this.sendMessage(NetworkMonitor.CMD_FORCE_REEVALUATION, 0, 0);
                            break;
                        case 1:
                            NetworkMonitor.this.mDontDisplaySigninNotification = true;
                            NetworkMonitor.this.mUserDoesNotWant = true;
                            NetworkMonitor.this.notifyNetworkTestResultInvalid(null);
                            NetworkMonitor.this.mUidResponsibleForReeval = 0;
                            NetworkMonitor.this.transitionTo(NetworkMonitor.this.mEvaluatingState);
                            break;
                        case 2:
                            NetworkMonitor.this.mDontDisplaySigninNotification = true;
                            NetworkMonitor.this.transitionTo(NetworkMonitor.this.mEvaluatingPrivateDnsState);
                            break;
                    }
                    return true;
                case NetworkMonitor.CMD_PRIVATE_DNS_SETTINGS_CHANGED /* 532493 */:
                    DnsManager.PrivateDnsConfig cfg = (DnsManager.PrivateDnsConfig) message.obj;
                    if (!NetworkMonitor.this.isValidationRequired() || cfg == null || !cfg.inStrictMode()) {
                        NetworkMonitor.this.mPrivateDnsProviderHostname = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                        break;
                    } else {
                        NetworkMonitor.this.mPrivateDnsProviderHostname = cfg.hostname;
                        NetworkMonitor.this.sendMessage(NetworkMonitor.CMD_EVALUATE_PRIVATE_DNS);
                        break;
                    }
            }
            return true;
        }
    }

    /* loaded from: classes.dex */
    private class ValidatedState extends State {
        private ValidatedState() {
        }

        public void enter() {
            NetworkMonitor.this.maybeLogEvaluationResult(NetworkMonitor.this.networkEventType(NetworkMonitor.this.validationStage(), EvaluationResult.VALIDATED));
            NetworkMonitor.this.mConnectivityServiceHandler.sendMessage(NetworkMonitor.this.obtainMessage(NetworkMonitor.EVENT_NETWORK_TESTED, 0, NetworkMonitor.this.mNetId, null));
            NetworkMonitor.access$2308(NetworkMonitor.this);
        }

        public boolean processMessage(Message message) {
            int i = message.what;
            if (i == 532481) {
                NetworkMonitor.this.transitionTo(NetworkMonitor.this.mValidatedState);
                return true;
            } else if (i == NetworkMonitor.CMD_EVALUATE_PRIVATE_DNS) {
                NetworkMonitor.this.transitionTo(NetworkMonitor.this.mEvaluatingPrivateDnsState);
                return true;
            } else {
                return false;
            }
        }
    }

    /* loaded from: classes.dex */
    private class MaybeNotifyState extends State {
        private MaybeNotifyState() {
        }

        public boolean processMessage(Message message) {
            if (message.what == 532491) {
                Intent intent = new Intent("android.net.conn.CAPTIVE_PORTAL");
                intent.putExtra("android.net.extra.NETWORK", new Network(NetworkMonitor.this.mNetwork));
                intent.putExtra("android.net.extra.CAPTIVE_PORTAL", new CaptivePortal(new ICaptivePortal.Stub() { // from class: com.android.server.connectivity.NetworkMonitor.MaybeNotifyState.1
                    public void appResponse(int response) {
                        if (response == 2) {
                            NetworkMonitor.this.mContext.enforceCallingPermission("android.permission.CONNECTIVITY_INTERNAL", "CaptivePortal");
                        }
                        NetworkMonitor.this.sendMessage(NetworkMonitor.CMD_CAPTIVE_PORTAL_APP_FINISHED, response);
                    }
                }));
                CaptivePortalProbeResult probeRes = NetworkMonitor.this.mLastPortalProbeResult;
                intent.putExtra("android.net.extra.CAPTIVE_PORTAL_URL", probeRes.detectUrl);
                if (probeRes.probeSpec != null) {
                    String encodedSpec = probeRes.probeSpec.getEncodedSpec();
                    intent.putExtra("android.net.extra.CAPTIVE_PORTAL_PROBE_SPEC", encodedSpec);
                }
                intent.putExtra("android.net.extra.CAPTIVE_PORTAL_USER_AGENT", NetworkMonitor.this.mCaptivePortalUserAgent);
                intent.setFlags(272629760);
                NetworkMonitor.this.mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                return true;
            }
            return false;
        }

        public void exit() {
            Message message = NetworkMonitor.this.obtainMessage(NetworkMonitor.EVENT_PROVISIONING_NOTIFICATION, 0, NetworkMonitor.this.mNetId, null);
            NetworkMonitor.this.mConnectivityServiceHandler.sendMessage(message);
        }
    }

    /* loaded from: classes.dex */
    private class EvaluatingState extends State {
        private int mAttempts;
        private int mReevaluateDelayMs;

        private EvaluatingState() {
        }

        public void enter() {
            if (!NetworkMonitor.this.mEvaluationTimer.isStarted()) {
                NetworkMonitor.this.mEvaluationTimer.start();
            }
            NetworkMonitor.this.sendMessage(NetworkMonitor.CMD_REEVALUATE, NetworkMonitor.access$2904(NetworkMonitor.this), 0);
            if (NetworkMonitor.this.mUidResponsibleForReeval != -1) {
                TrafficStats.setThreadStatsUid(NetworkMonitor.this.mUidResponsibleForReeval);
                NetworkMonitor.this.mUidResponsibleForReeval = -1;
            }
            this.mReevaluateDelayMs = 1000;
            this.mAttempts = 0;
        }

        public boolean processMessage(Message message) {
            int i = message.what;
            if (i != NetworkMonitor.CMD_REEVALUATE) {
                return i == NetworkMonitor.CMD_FORCE_REEVALUATION && this.mAttempts < 5;
            } else if (message.arg1 != NetworkMonitor.this.mReevaluateToken || NetworkMonitor.this.mUserDoesNotWant) {
                return true;
            } else {
                if (!NetworkMonitor.this.isValidationRequired()) {
                    NetworkMonitor.this.validationLog("Network would not satisfy default request, not validating");
                    NetworkMonitor.this.transitionTo(NetworkMonitor.this.mValidatedState);
                    return true;
                }
                this.mAttempts++;
                CaptivePortalProbeResult probeResult = NetworkMonitor.this.isCaptivePortal();
                if (probeResult.isSuccessful()) {
                    NetworkMonitor.this.transitionTo(NetworkMonitor.this.mEvaluatingPrivateDnsState);
                } else if (probeResult.isPortal()) {
                    NetworkMonitor.this.notifyNetworkTestResultInvalid(probeResult.redirectUrl);
                    NetworkMonitor.this.mLastPortalProbeResult = probeResult;
                    NetworkMonitor.this.transitionTo(NetworkMonitor.this.mCaptivePortalState);
                } else {
                    Message msg = NetworkMonitor.this.obtainMessage(NetworkMonitor.CMD_REEVALUATE, NetworkMonitor.access$2904(NetworkMonitor.this), 0);
                    NetworkMonitor.this.sendMessageDelayed(msg, this.mReevaluateDelayMs);
                    NetworkMonitor.this.logNetworkEvent(3);
                    NetworkMonitor.this.notifyNetworkTestResultInvalid(probeResult.redirectUrl);
                    if (this.mAttempts >= 5) {
                        TrafficStats.clearThreadStatsUid();
                    }
                    this.mReevaluateDelayMs *= 2;
                    if (this.mReevaluateDelayMs > 600000) {
                        this.mReevaluateDelayMs = 600000;
                    }
                }
                return true;
            }
        }

        public void exit() {
            TrafficStats.clearThreadStatsUid();
        }
    }

    /* loaded from: classes.dex */
    private class CustomIntentReceiver extends BroadcastReceiver {
        private final String mAction;
        private final int mToken;
        private final int mWhat;

        CustomIntentReceiver(String action, int token, int what) {
            this.mToken = token;
            this.mWhat = what;
            this.mAction = action + "_" + NetworkMonitor.this.mNetId + "_" + token;
            NetworkMonitor.this.mContext.registerReceiver(this, new IntentFilter(this.mAction));
        }

        public PendingIntent getPendingIntent() {
            Intent intent = new Intent(this.mAction);
            intent.setPackage(NetworkMonitor.this.mContext.getPackageName());
            return PendingIntent.getBroadcast(NetworkMonitor.this.mContext, 0, intent, 0);
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(this.mAction)) {
                NetworkMonitor.this.sendMessage(NetworkMonitor.this.obtainMessage(this.mWhat, this.mToken));
            }
        }
    }

    /* loaded from: classes.dex */
    private class CaptivePortalState extends State {
        private static final String ACTION_LAUNCH_CAPTIVE_PORTAL_APP = "android.net.netmon.launchCaptivePortalApp";

        private CaptivePortalState() {
        }

        public void enter() {
            NetworkMonitor.this.maybeLogEvaluationResult(NetworkMonitor.this.networkEventType(NetworkMonitor.this.validationStage(), EvaluationResult.CAPTIVE_PORTAL));
            if (NetworkMonitor.this.mDontDisplaySigninNotification) {
                return;
            }
            if (NetworkMonitor.this.mLaunchCaptivePortalAppBroadcastReceiver == null) {
                NetworkMonitor.this.mLaunchCaptivePortalAppBroadcastReceiver = new CustomIntentReceiver(ACTION_LAUNCH_CAPTIVE_PORTAL_APP, new Random().nextInt(), NetworkMonitor.CMD_LAUNCH_CAPTIVE_PORTAL_APP);
            }
            Message message = NetworkMonitor.this.obtainMessage(NetworkMonitor.EVENT_PROVISIONING_NOTIFICATION, 1, NetworkMonitor.this.mNetId, NetworkMonitor.this.mLaunchCaptivePortalAppBroadcastReceiver.getPendingIntent());
            NetworkMonitor.this.mConnectivityServiceHandler.sendMessage(message);
            NetworkMonitor.this.sendMessageDelayed(NetworkMonitor.CMD_CAPTIVE_PORTAL_RECHECK, 0, 600000L);
            NetworkMonitor.access$2308(NetworkMonitor.this);
        }

        public void exit() {
            NetworkMonitor.this.removeMessages(NetworkMonitor.CMD_CAPTIVE_PORTAL_RECHECK);
        }
    }

    /* loaded from: classes.dex */
    private class EvaluatingPrivateDnsState extends State {
        private DnsManager.PrivateDnsConfig mPrivateDnsConfig;
        private int mPrivateDnsReevalDelayMs;

        private EvaluatingPrivateDnsState() {
        }

        public void enter() {
            this.mPrivateDnsReevalDelayMs = 1000;
            this.mPrivateDnsConfig = null;
            NetworkMonitor.this.sendMessage(NetworkMonitor.CMD_EVALUATE_PRIVATE_DNS);
        }

        public boolean processMessage(Message msg) {
            if (msg.what == NetworkMonitor.CMD_EVALUATE_PRIVATE_DNS) {
                if (inStrictMode()) {
                    if (!isStrictModeHostnameResolved()) {
                        resolveStrictModeHostname();
                        if (isStrictModeHostnameResolved()) {
                            notifyPrivateDnsConfigResolved();
                        } else {
                            handlePrivateDnsEvaluationFailure();
                            return true;
                        }
                    }
                    if (!sendPrivateDnsProbe()) {
                        handlePrivateDnsEvaluationFailure();
                        return true;
                    }
                }
                NetworkMonitor.this.transitionTo(NetworkMonitor.this.mValidatedState);
                return true;
            }
            return false;
        }

        private boolean inStrictMode() {
            return !TextUtils.isEmpty(NetworkMonitor.this.mPrivateDnsProviderHostname);
        }

        private boolean isStrictModeHostnameResolved() {
            return this.mPrivateDnsConfig != null && this.mPrivateDnsConfig.hostname.equals(NetworkMonitor.this.mPrivateDnsProviderHostname) && this.mPrivateDnsConfig.ips.length > 0;
        }

        private void resolveStrictModeHostname() {
            try {
                InetAddress[] ips = ResolvUtil.blockingResolveAllLocally(NetworkMonitor.this.mNetwork, NetworkMonitor.this.mPrivateDnsProviderHostname, 0);
                this.mPrivateDnsConfig = new DnsManager.PrivateDnsConfig(NetworkMonitor.this.mPrivateDnsProviderHostname, ips);
            } catch (UnknownHostException e) {
                this.mPrivateDnsConfig = null;
            }
        }

        private void notifyPrivateDnsConfigResolved() {
            NetworkMonitor.this.mConnectivityServiceHandler.sendMessage(NetworkMonitor.this.obtainMessage(NetworkMonitor.EVENT_PRIVATE_DNS_CONFIG_RESOLVED, 0, NetworkMonitor.this.mNetId, this.mPrivateDnsConfig));
        }

        private void handlePrivateDnsEvaluationFailure() {
            NetworkMonitor.this.notifyNetworkTestResultInvalid(null);
            NetworkMonitor.this.sendMessageDelayed(NetworkMonitor.CMD_EVALUATE_PRIVATE_DNS, this.mPrivateDnsReevalDelayMs);
            this.mPrivateDnsReevalDelayMs *= 2;
            if (this.mPrivateDnsReevalDelayMs > 600000) {
                this.mPrivateDnsReevalDelayMs = 600000;
            }
        }

        private boolean sendPrivateDnsProbe() {
            String host = UUID.randomUUID().toString().substring(0, 8) + "-dnsotls-ds.metric.gstatic.com";
            try {
                InetAddress[] ips = NetworkMonitor.this.mNetworkAgentInfo.network().getAllByName(host);
                if (ips != null) {
                    return ips.length > 0;
                }
                return false;
            } catch (UnknownHostException e) {
                return false;
            }
        }
    }

    /* loaded from: classes.dex */
    private static class OneAddressPerFamilyNetwork extends Network {
        public OneAddressPerFamilyNetwork(Network network) {
            super(network);
        }

        @Override // android.net.Network
        public InetAddress[] getAllByName(String host) throws UnknownHostException {
            List<InetAddress> addrs = Arrays.asList(ResolvUtil.blockingResolveAllLocally(this, host));
            LinkedHashMap<Class, InetAddress> addressByFamily = new LinkedHashMap<>();
            addressByFamily.put(addrs.get(0).getClass(), addrs.get(0));
            Collections.shuffle(addrs);
            for (InetAddress addr : addrs) {
                addressByFamily.put(addr.getClass(), addr);
            }
            return (InetAddress[]) addressByFamily.values().toArray(new InetAddress[addressByFamily.size()]);
        }
    }

    public boolean getIsCaptivePortalCheckEnabled() {
        int mode = this.mSettings.getSetting(this.mContext, "captive_portal_mode", 0);
        return mode != 0;
    }

    public boolean getUseHttpsValidation() {
        return this.mSettings.getSetting(this.mContext, "captive_portal_use_https", 1) == 1;
    }

    public boolean getWifiScansAlwaysAvailableDisabled() {
        return this.mSettings.getSetting(this.mContext, "wifi_scan_always_enabled", 0) == 0;
    }

    private String getCaptivePortalServerHttpsUrl() {
        return this.mSettings.getSetting(this.mContext, "captive_portal_https_url", DEFAULT_HTTPS_URL);
    }

    public static String getCaptivePortalServerHttpUrl(Context context) {
        return getCaptivePortalServerHttpUrl(NetworkMonitorSettings.DEFAULT, context);
    }

    public static String getCaptivePortalServerHttpUrl(NetworkMonitorSettings settings, Context context) {
        return settings.getSetting(context, "captive_portal_http_url", DEFAULT_HTTP_URL);
    }

    private URL[] makeCaptivePortalFallbackUrls() {
        String[] split;
        try {
            String firstUrl = this.mSettings.getSetting(this.mContext, "captive_portal_fallback_url", DEFAULT_FALLBACK_URL);
            String joinedUrls = firstUrl + "," + this.mSettings.getSetting(this.mContext, "captive_portal_other_fallback_urls", DEFAULT_OTHER_FALLBACK_URLS);
            List<URL> urls = new ArrayList<>();
            for (String s : joinedUrls.split(",")) {
                URL u = makeURL(s);
                if (u != null) {
                    urls.add(u);
                }
            }
            if (urls.isEmpty()) {
                Log.e(TAG, String.format("could not create any url from %s", joinedUrls));
            }
            return (URL[]) urls.toArray(new URL[urls.size()]);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing configured fallback URLs", e);
            return new URL[0];
        }
    }

    private CaptivePortalProbeSpec[] makeCaptivePortalFallbackProbeSpecs() {
        try {
            String settingsValue = this.mSettings.getSetting(this.mContext, "captive_portal_fallback_probe_specs", (String) null);
            if (TextUtils.isEmpty(settingsValue)) {
                return null;
            }
            return CaptivePortalProbeSpec.parseCaptivePortalProbeSpecs(settingsValue);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing configured fallback probe specs", e);
            return null;
        }
    }

    private String getCaptivePortalUserAgent() {
        return this.mSettings.getSetting(this.mContext, "captive_portal_user_agent", DEFAULT_USER_AGENT);
    }

    private URL nextFallbackUrl() {
        if (this.mCaptivePortalFallbackUrls.length == 0) {
            return null;
        }
        int idx = Math.abs(this.mNextFallbackUrlIndex) % this.mCaptivePortalFallbackUrls.length;
        this.mNextFallbackUrlIndex += new Random().nextInt();
        return this.mCaptivePortalFallbackUrls[idx];
    }

    private CaptivePortalProbeSpec nextFallbackSpec() {
        if (ArrayUtils.isEmpty(this.mCaptivePortalFallbackSpecs)) {
            return null;
        }
        int idx = Math.abs(new Random().nextInt()) % this.mCaptivePortalFallbackSpecs.length;
        return this.mCaptivePortalFallbackSpecs[idx];
    }

    @VisibleForTesting
    protected CaptivePortalProbeResult isCaptivePortal() {
        CaptivePortalProbeResult result;
        if (!this.mIsCaptivePortalCheckEnabled) {
            validationLog("Validation disabled.");
            return CaptivePortalProbeResult.SUCCESS;
        }
        URL pacUrl = null;
        URL httpsUrl = this.mCaptivePortalHttpsUrl;
        URL httpUrl = this.mCaptivePortalHttpUrl;
        ProxyInfo proxyInfo = this.mNetworkAgentInfo.linkProperties.getHttpProxy();
        if (proxyInfo != null && !Uri.EMPTY.equals(proxyInfo.getPacFileUrl()) && (pacUrl = makeURL(proxyInfo.getPacFileUrl().toString())) == null) {
            return CaptivePortalProbeResult.FAILED;
        }
        URL pacUrl2 = pacUrl;
        if (pacUrl2 == null && (httpUrl == null || httpsUrl == null)) {
            return CaptivePortalProbeResult.FAILED;
        }
        long startTime = SystemClock.elapsedRealtime();
        if (pacUrl2 != null) {
            result = sendDnsAndHttpProbes(null, pacUrl2, 3);
        } else {
            result = this.mUseHttps ? sendParallelHttpProbes(proxyInfo, httpsUrl, httpUrl) : sendDnsAndHttpProbes(proxyInfo, httpUrl, 1);
        }
        CaptivePortalProbeResult result2 = result;
        long endTime = SystemClock.elapsedRealtime();
        sendNetworkConditionsBroadcast(true, result2.isPortal(), startTime, endTime);
        return result2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public CaptivePortalProbeResult sendDnsAndHttpProbes(ProxyInfo proxy, URL url, int probeType) {
        String host = proxy != null ? proxy.getHost() : url.getHost();
        sendDnsProbe(host);
        return sendHttpProbe(url, probeType, null);
    }

    private void sendDnsProbe(String host) {
        int result;
        String connectInfo;
        if (TextUtils.isEmpty(host)) {
            return;
        }
        ValidationProbeEvent.getProbeName(0);
        Stopwatch watch = new Stopwatch().start();
        try {
            InetAddress[] addresses = this.mNetwork.getAllByName(host);
            StringBuffer buffer = new StringBuffer();
            for (InetAddress address : addresses) {
                buffer.append(',');
                buffer.append(address.getHostAddress());
            }
            result = 1;
            connectInfo = "OK " + buffer.substring(1);
        } catch (UnknownHostException e) {
            result = 0;
            connectInfo = "FAIL";
        }
        long latency = watch.stop();
        validationLog(0, host, String.format("%dms %s", Long.valueOf(latency), connectInfo));
        logValidationProbe(latency, 0, result);
    }

    /* JADX WARN: Removed duplicated region for block: B:50:0x011e  */
    /* JADX WARN: Removed duplicated region for block: B:54:0x0130  */
    /* JADX WARN: Removed duplicated region for block: B:56:0x013a  */
    /* JADX WARN: Removed duplicated region for block: B:61:0x0146  */
    @com.android.internal.annotations.VisibleForTesting
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    protected android.net.captiveportal.CaptivePortalProbeResult sendHttpProbe(java.net.URL r20, int r21, android.net.captiveportal.CaptivePortalProbeSpec r22) {
        /*
            Method dump skipped, instructions count: 333
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.connectivity.NetworkMonitor.sendHttpProbe(java.net.URL, int, android.net.captiveportal.CaptivePortalProbeSpec):android.net.captiveportal.CaptivePortalProbeResult");
    }

    /* JADX WARN: Type inference failed for: r1v1, types: [com.android.server.connectivity.NetworkMonitor$1ProbeThread] */
    /* JADX WARN: Type inference failed for: r9v0, types: [com.android.server.connectivity.NetworkMonitor$1ProbeThread] */
    private CaptivePortalProbeResult sendParallelHttpProbes(ProxyInfo proxy, URL httpsUrl, URL httpUrl) {
        CountDownLatch latch = new CountDownLatch(2);
        ?? r1 = new Thread(true, proxy, httpsUrl, httpUrl, latch) { // from class: com.android.server.connectivity.NetworkMonitor.1ProbeThread
            private final boolean mIsHttps;
            private volatile CaptivePortalProbeResult mResult = CaptivePortalProbeResult.FAILED;
            final /* synthetic */ URL val$httpUrl;
            final /* synthetic */ URL val$httpsUrl;
            final /* synthetic */ CountDownLatch val$latch;
            final /* synthetic */ ProxyInfo val$proxy;

            {
                this.val$proxy = proxy;
                this.val$httpsUrl = httpsUrl;
                this.val$httpUrl = httpUrl;
                this.val$latch = latch;
                this.mIsHttps = isHttps;
            }

            public CaptivePortalProbeResult result() {
                return this.mResult;
            }

            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                if (this.mIsHttps) {
                    this.mResult = NetworkMonitor.this.sendDnsAndHttpProbes(this.val$proxy, this.val$httpsUrl, 2);
                } else {
                    this.mResult = NetworkMonitor.this.sendDnsAndHttpProbes(this.val$proxy, this.val$httpUrl, 1);
                }
                if ((this.mIsHttps && this.mResult.isSuccessful()) || (!this.mIsHttps && this.mResult.isPortal())) {
                    while (this.val$latch.getCount() > 0) {
                        this.val$latch.countDown();
                    }
                }
                this.val$latch.countDown();
            }
        };
        ?? r9 = new Thread(false, proxy, httpsUrl, httpUrl, latch) { // from class: com.android.server.connectivity.NetworkMonitor.1ProbeThread
            private final boolean mIsHttps;
            private volatile CaptivePortalProbeResult mResult = CaptivePortalProbeResult.FAILED;
            final /* synthetic */ URL val$httpUrl;
            final /* synthetic */ URL val$httpsUrl;
            final /* synthetic */ CountDownLatch val$latch;
            final /* synthetic */ ProxyInfo val$proxy;

            {
                this.val$proxy = proxy;
                this.val$httpsUrl = httpsUrl;
                this.val$httpUrl = httpUrl;
                this.val$latch = latch;
                this.mIsHttps = isHttps;
            }

            public CaptivePortalProbeResult result() {
                return this.mResult;
            }

            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                if (this.mIsHttps) {
                    this.mResult = NetworkMonitor.this.sendDnsAndHttpProbes(this.val$proxy, this.val$httpsUrl, 2);
                } else {
                    this.mResult = NetworkMonitor.this.sendDnsAndHttpProbes(this.val$proxy, this.val$httpUrl, 1);
                }
                if ((this.mIsHttps && this.mResult.isSuccessful()) || (!this.mIsHttps && this.mResult.isPortal())) {
                    while (this.val$latch.getCount() > 0) {
                        this.val$latch.countDown();
                    }
                }
                this.val$latch.countDown();
            }
        };
        try {
            r1.start();
            r9.start();
            latch.await(3000L, TimeUnit.MILLISECONDS);
            CaptivePortalProbeResult httpsResult = r1.result();
            CaptivePortalProbeResult httpResult = r9.result();
            if (httpResult.isPortal()) {
                return httpResult;
            }
            if (httpsResult.isPortal() || httpsResult.isSuccessful()) {
                return httpsResult;
            }
            CaptivePortalProbeSpec probeSpec = nextFallbackSpec();
            URL fallbackUrl = probeSpec != null ? probeSpec.getUrl() : nextFallbackUrl();
            if (fallbackUrl != null) {
                CaptivePortalProbeResult result = sendHttpProbe(fallbackUrl, 4, probeSpec);
                if (result.isPortal()) {
                    return result;
                }
            }
            try {
                r9.join();
                if (r9.result().isPortal()) {
                    return r9.result();
                }
                r1.join();
                return r1.result();
            } catch (InterruptedException e) {
                validationLog("Error: http or https probe wait interrupted!");
                return CaptivePortalProbeResult.FAILED;
            }
        } catch (InterruptedException e2) {
            validationLog("Error: probes wait interrupted!");
            return CaptivePortalProbeResult.FAILED;
        }
    }

    private URL makeURL(String url) {
        if (url != null) {
            try {
                return new URL(url);
            } catch (MalformedURLException e) {
                validationLog("Bad URL: " + url);
                return null;
            }
        }
        return null;
    }

    private void sendNetworkConditionsBroadcast(boolean responseReceived, boolean isCaptivePortal, long requestTimestampMs, long responseTimestampMs) {
        if (getWifiScansAlwaysAvailableDisabled() || !this.systemReady) {
            return;
        }
        Intent latencyBroadcast = new Intent(ConnectivityConstants.ACTION_NETWORK_CONDITIONS_MEASURED);
        switch (this.mNetworkAgentInfo.networkInfo.getType()) {
            case 0:
                latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_NETWORK_TYPE, this.mTelephonyManager.getNetworkType());
                List<CellInfo> info = this.mTelephonyManager.getAllCellInfo();
                if (info != null) {
                    int numRegisteredCellInfo = 0;
                    for (CellInfo cellInfo : info) {
                        if (cellInfo.isRegistered()) {
                            numRegisteredCellInfo++;
                            if (numRegisteredCellInfo > 1) {
                                return;
                            }
                            if (cellInfo instanceof CellInfoCdma) {
                                CellIdentityCdma cellId = ((CellInfoCdma) cellInfo).getCellIdentity();
                                latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId);
                            } else if (cellInfo instanceof CellInfoGsm) {
                                CellIdentityGsm cellId2 = ((CellInfoGsm) cellInfo).getCellIdentity();
                                latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId2);
                            } else if (cellInfo instanceof CellInfoLte) {
                                CellIdentityLte cellId3 = ((CellInfoLte) cellInfo).getCellIdentity();
                                latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId3);
                            } else if (cellInfo instanceof CellInfoWcdma) {
                                CellIdentityWcdma cellId4 = ((CellInfoWcdma) cellInfo).getCellIdentity();
                                latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CELL_ID, cellId4);
                            } else {
                                return;
                            }
                        }
                    }
                    break;
                } else {
                    return;
                }
            case 1:
                WifiInfo currentWifiInfo = this.mWifiManager.getConnectionInfo();
                if (currentWifiInfo != null) {
                    latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_SSID, currentWifiInfo.getSSID());
                    latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_BSSID, currentWifiInfo.getBSSID());
                    break;
                } else {
                    return;
                }
            default:
                return;
        }
        latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_CONNECTIVITY_TYPE, this.mNetworkAgentInfo.networkInfo.getType());
        latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_RESPONSE_RECEIVED, responseReceived);
        latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_REQUEST_TIMESTAMP_MS, requestTimestampMs);
        if (responseReceived) {
            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_IS_CAPTIVE_PORTAL, isCaptivePortal);
            latencyBroadcast.putExtra(ConnectivityConstants.EXTRA_RESPONSE_TIMESTAMP_MS, responseTimestampMs);
        }
        this.mContext.sendBroadcastAsUser(latencyBroadcast, UserHandle.CURRENT, ConnectivityConstants.PERMISSION_ACCESS_NETWORK_CONDITIONS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logNetworkEvent(int evtype) {
        int[] transports = this.mNetworkAgentInfo.networkCapabilities.getTransportTypes();
        this.mMetricsLog.log(this.mNetId, transports, new NetworkEvent(evtype));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int networkEventType(ValidationStage s, EvaluationResult r) {
        if (s.isFirstValidation) {
            if (r.isValidated) {
                return 8;
            }
            return 10;
        } else if (r.isValidated) {
            return 9;
        } else {
            return 11;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeLogEvaluationResult(int evtype) {
        if (this.mEvaluationTimer.isRunning()) {
            int[] transports = this.mNetworkAgentInfo.networkCapabilities.getTransportTypes();
            this.mMetricsLog.log(this.mNetId, transports, new NetworkEvent(evtype, this.mEvaluationTimer.stop()));
            this.mEvaluationTimer.reset();
        }
    }

    private void logValidationProbe(long durationMs, int probeType, int probeResult) {
        int[] transports = this.mNetworkAgentInfo.networkCapabilities.getTransportTypes();
        boolean isFirstValidation = validationStage().isFirstValidation;
        ValidationProbeEvent ev = new ValidationProbeEvent();
        ev.probeType = ValidationProbeEvent.makeProbeType(probeType, isFirstValidation);
        ev.returnCode = probeResult;
        ev.durationMs = durationMs;
        this.mMetricsLog.log(this.mNetId, transports, ev);
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class DefaultNetworkMonitorSettings implements NetworkMonitorSettings {
        @Override // com.android.server.connectivity.NetworkMonitor.NetworkMonitorSettings
        public int getSetting(Context context, String symbol, int defaultValue) {
            return Settings.Global.getInt(context.getContentResolver(), symbol, defaultValue);
        }

        @Override // com.android.server.connectivity.NetworkMonitor.NetworkMonitorSettings
        public String getSetting(Context context, String symbol, String defaultValue) {
            String value = Settings.Global.getString(context.getContentResolver(), symbol);
            return value != null ? value : defaultValue;
        }
    }
}
