package com.xiaopeng.server.aftersales;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Slog;
import com.android.server.UiModeManagerService;
import com.xiaopeng.aftersales.IAfterSalesManager;
import com.xiaopeng.aftersales.IAlertListener;
import com.xiaopeng.aftersales.IAuthModeListener;
import com.xiaopeng.aftersales.IEncryptShListener;
import com.xiaopeng.aftersales.ILogicActionListener;
import com.xiaopeng.aftersales.ILogicTreeUpgrader;
import com.xiaopeng.aftersales.IRepairModeListener;
import com.xiaopeng.aftersales.IShellCmdListener;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
/* loaded from: classes.dex */
public class AfterSalesService extends IAfterSalesManager.Stub {
    private static final String AFTERSALESD_TAG = "AftersalesdConnector";
    private static final boolean DBG = false;
    private static final int FALSE = 0;
    private static final String PROPERTY_VALUE_OFF = "off";
    private static final String PROPERTY_VALUE_ON = "on";
    private static final String SYSTEM_PROPERTY_AUTHMODE = "persist.sys.xiaopeng.authmode";
    private static final String SYSTEM_PROPERTY_REPAIRMODE = "persist.sys.xiaopeng.repairmode";
    private static final String TAG = "AfterSalesService";
    private static final int TRUE = 1;
    private AccountManager mAccountManager;
    private AlarmManager mAlarmManager;
    private ConnectivityManager mCM;
    private final Context mContext;
    private Handler mHandler;
    private NetWorkObserver mNetWorkObserver;
    private StorageManager mStorageManager;
    private final RemoteCallbackList<IAlertListener> mAlertListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<IRepairModeListener> mRepairModeListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<IShellCmdListener> mShellCmdListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<IEncryptShListener> mEncryptShListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<IAuthModeListener> mAuthModeListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<ILogicActionListener> mLogicActionListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<ILogicTreeUpgrader> mLogicTreeUpgraders = new RemoteCallbackList<>();
    private String mAuthPass = null;
    private long mAuthEndTime = -1;
    private String mRepairModeEnableTime = null;
    private String mRepairModeDisableTime = null;
    private String mSpeedLimitEnableTime = null;
    private String mSpeedLimitDisableTime = null;
    private String mRepairModeKeyId = null;
    private boolean mSpeedLimitMode = false;
    private CountDownLatch mConnectedSignal = new CountDownLatch(1);
    private AlarmManager.OnAlarmListener mAuthModeAlarmListener = new AlarmManager.OnAlarmListener() { // from class: com.xiaopeng.server.aftersales.-$$Lambda$AfterSalesService$HU9_S7go8XBKQlDYEfTGvM6MsqE
        @Override // android.app.AlarmManager.OnAlarmListener
        public final void onAlarm() {
            AfterSalesService.this.disableAuthModeInner();
        }
    };
    private StorageEventListener mStorageListener = new StorageEventListener() { // from class: com.xiaopeng.server.aftersales.AfterSalesService.1
        public void onStorageStateChanged(String path, String oldState, String newState) {
            File file;
            super.onStorageStateChanged(path, oldState, newState);
            Slog.i(AfterSalesService.TAG, "path = " + path + ", oldState = " + oldState + ", newState = " + newState);
            if (newState.equals("mounted")) {
                try {
                    List<VolumeInfo> volumeInfoList = AfterSalesService.this.mStorageManager.getVolumes();
                    for (VolumeInfo volumeInfo : volumeInfoList) {
                        if (volumeInfo != null && volumeInfo.getPath() != null && volumeInfo.getPath().getPath().equals(path)) {
                            if (volumeInfo.getDisk() != null && volumeInfo.getDisk().isUsb() && (file = volumeInfo.getInternalPath()) != null) {
                                AfterSalesService.this.mConnector.execute(2, 1, file.getPath());
                                return;
                            }
                            return;
                        }
                    }
                } catch (Exception e) {
                    Slog.e(AfterSalesService.TAG, e.toString());
                }
            }
        }
    };
    private OnAccountsUpdateListener mAccountsListener = new OnAccountsUpdateListener() { // from class: com.xiaopeng.server.aftersales.-$$Lambda$AfterSalesService$6KvLo7nx_0uP9NpQVpH-N1V2qnQ
        @Override // android.accounts.OnAccountsUpdateListener
        public final void onAccountsUpdated(Account[] accountArr) {
            AfterSalesService.lambda$new$1(accountArr);
        }
    };
    private final AfterSalesDaemonConnector mConnector = new AfterSalesDaemonConnector(new AftersalesdCallbackReceiver());

    /* loaded from: classes.dex */
    static class AftersalesdCmd {
        public static final int AUTHMODE = 4;
        public static final int DIAGNOSIS = 1;
        public static final int LOGICTREE = 5;
        public static final int REPAIRMODE = 2;
        public static final int SHELLCMD = 3;

        AftersalesdCmd() {
        }
    }

    /* loaded from: classes.dex */
    static class DiagnosisAction {
        public static final int ALERT = 2;
        public static final int RECORD = 1;
        public static final int UPDATE_NOT_UPLOAD = 4;
        public static final int UPDATE_UPLOADED = 5;
        public static final int UPLOAD = 3;

        DiagnosisAction() {
        }
    }

    /* loaded from: classes.dex */
    static class LogicTreeAction {
        public static final int RECORD = 1;
        public static final int UPDATE_NOT_UPLOAD = 3;
        public static final int UPDATE_UPLOADED = 4;
        public static final int UPGRADE = 5;
        public static final int UPLOAD = 2;

        LogicTreeAction() {
        }
    }

    /* loaded from: classes.dex */
    static class RepairmodeAction {
        public static final int DISABLE = 3;
        public static final int ENABLE = 2;
        public static final int ENABLE_WITH_KEYID = 6;
        public static final int KEY_ENABLE = 1;
        public static final int READ = 4;
        public static final int RECORD_ACTION = 5;
        public static final int RECORD_SPEED_LIMIT_OFF = 8;
        public static final int RECORD_SPEED_LIMIT_ON = 7;

        RepairmodeAction() {
        }
    }

    /* loaded from: classes.dex */
    static class ShellCmdAction {
        public static final int ACTION_EXEC_ENCRYPT_SH = 3;
        public static final int ACTION_EXEC_ENCRYPT_SH_CLOUD = 4;
        public static final int ACTION_EXEC_SHELL_CMD = 1;
        public static final int ACTION_EXEC_SHELL_CMD_CLOUD = 2;

        ShellCmdAction() {
        }
    }

    /* loaded from: classes.dex */
    static class AuthmodeAction {
        public static final int ACTION_AUTHMODE_DISABLE = 2;
        public static final int ACTION_AUTHMODE_ENABLE = 1;
        public static final int ACTION_AUTHMODE_READ = 3;

        AuthmodeAction() {
        }
    }

    /* loaded from: classes.dex */
    static class PackgeName {
        public static final String CARDIAGNOSIS = "com.xiaopeng.cardiagnosis";
        public static final String DEVTOOLS = "com.xiaopeng.devtools";
        public static final String SYSTEMUI = "com.android.systemui";

        PackgeName() {
        }
    }

    public AfterSalesService(Context context) {
        this.mContext = context;
        Thread thread = new Thread(this.mConnector, AFTERSALESD_TAG);
        thread.start();
    }

    public void systemRunning() {
        this.mCM = (ConnectivityManager) this.mContext.getSystemService(ConnectivityManager.class);
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        this.mHandler = new Handler(handlerThread.getLooper());
        this.mNetWorkObserver = new NetWorkObserver();
        this.mCM.registerDefaultNetworkCallback(this.mNetWorkObserver, this.mHandler);
        this.mStorageManager = (StorageManager) this.mContext.getSystemService(StorageManager.class);
        this.mStorageManager.registerListener(this.mStorageListener);
        this.mAccountManager = AccountManager.get(this.mContext);
        this.mAlarmManager = (AlarmManager) this.mContext.getSystemService("alarm");
    }

    public void registerAlertListener(IAlertListener listener) {
        this.mAlertListeners.register(listener);
    }

    public void unregisterAlertListener(IAlertListener listener) {
        this.mAlertListeners.unregister(listener);
    }

    public void recordDiagnosisError(int module, int errorCode, long millis, String errorMsg, boolean alert) {
        if (alert) {
            this.mConnector.execute(1, 2, Integer.valueOf(module), Integer.valueOf(errorCode), Long.valueOf(millis), errorMsg);
        } else {
            this.mConnector.execute(1, 1, Integer.valueOf(module), Integer.valueOf(errorCode), Long.valueOf(millis), errorMsg);
        }
    }

    public void updateDiagnosisUploadStatus(int module, boolean status, int errorCode, long millis, String errorMsg) {
        this.mConnector.execute(1, status ? 5 : 4, Integer.valueOf(module), Integer.valueOf(errorCode), Long.valueOf(millis), errorMsg);
    }

    public void registerLogicActionListener(ILogicActionListener listener) {
        this.mLogicActionListeners.register(listener);
    }

    public void unregisterLogicActionListener(ILogicActionListener listener) {
        this.mLogicActionListeners.unregister(listener);
    }

    public void recordLogicAction(String issueName, String conclusion, String startTime, String endTime, String logicactionTime, String logicactionEntry, String logictreeVer) {
        this.mConnector.execute(5, 1, issueName, conclusion, startTime, endTime, logicactionTime, logicactionEntry, logictreeVer);
    }

    public void updateLogicActionUploadStatus(boolean status, String issueName, String conclusion, String startTime, String endTime, String logicactionTime, String logicactionEntry, String logictreeVer) {
        this.mConnector.execute(5, status ? 4 : 3, issueName, conclusion, startTime, endTime, logicactionTime, logicactionEntry, logictreeVer);
    }

    public void requestUploadLogicAction() {
        this.mConnector.execute(5, 2, new Object[0]);
    }

    public void requestUpgradeLogicTree(String path) throws RemoteException {
        this.mConnector.execute(5, 5, path);
    }

    public void registerLogicTreeUpgrader(ILogicTreeUpgrader listener) throws RemoteException {
        this.mLogicTreeUpgraders.register(listener);
    }

    public void unregisterLogicTreeUpgrader(ILogicTreeUpgrader listener) throws RemoteException {
        this.mLogicTreeUpgraders.unregister(listener);
    }

    public void recordRepairmodeAction(String action, String result) throws RemoteException {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to recordRepairmodeAction by :" + name);
        if (PackgeName.DEVTOOLS.equals(name)) {
            this.mConnector.execute(2, 5, action, result);
            return;
        }
        Slog.e(TAG, name + " not permitted to recordRepairmodeAction");
    }

    public void enableRepairMode() {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to enableRepairMode by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mConnector.execute(2, 2, new Object[0]);
            return;
        }
        Slog.e(TAG, name + " not permitted to enableRepairMode");
    }

    public void enableRepairModeWithKey(String keyPath) {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to enableRepairModeWithKey by :" + name);
        if (PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mConnector.execute(2, 1, keyPath);
            return;
        }
        Slog.e(TAG, name + " not permitted to enableRepairModeWithKey");
    }

    public void enableRepairModeWithKeyId(String keyId) throws RemoteException {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to enableRepairModeWithKeyId by :" + name);
        if (PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mConnector.execute(2, 6, keyId);
            return;
        }
        Slog.e(TAG, name + " not permitted to enableRepairModeWithKeyId");
    }

    public void disableRepairMode() {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to disableRepairMode by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.SYSTEMUI.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            disableRepairModeInner();
            return;
        }
        Slog.e(TAG, name + " not permitted to disableRepairMode");
    }

    private void disableRepairModeInner() {
        this.mConnector.execute(2, 3, new Object[0]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void getRepairModeInner() {
        this.mConnector.execute(2, 4, new Object[0]);
    }

    public boolean getRepairMode() {
        String repairmode = SystemProperties.get(SYSTEM_PROPERTY_REPAIRMODE, PROPERTY_VALUE_OFF);
        Slog.i(TAG, "getRepairMode : " + repairmode);
        return PROPERTY_VALUE_ON.equals(repairmode);
    }

    public String getRepairModeEnableTime() throws RemoteException {
        return this.mRepairModeEnableTime;
    }

    public String getRepairModeDisableTime() throws RemoteException {
        return this.mRepairModeDisableTime;
    }

    public boolean getSpeedLimitMode() throws RemoteException {
        return this.mSpeedLimitMode;
    }

    public String getSpeedLimitEnableTime() throws RemoteException {
        return this.mSpeedLimitEnableTime;
    }

    public String getSpeedLimitDisableTime() throws RemoteException {
        return this.mSpeedLimitDisableTime;
    }

    public String getRepairModeKeyId() throws RemoteException {
        return this.mRepairModeKeyId;
    }

    public void recordSpeedLimitOn() throws RemoteException {
        this.mConnector.execute(2, 7, new Object[0]);
    }

    public void recordSpeedLimitOff() throws RemoteException {
        this.mConnector.execute(2, 8, new Object[0]);
    }

    public void registerRepairModeListener(IRepairModeListener listener) {
        this.mRepairModeListeners.register(listener);
    }

    public void unregisterRepairModeListener(IRepairModeListener listener) {
        this.mRepairModeListeners.unregister(listener);
    }

    public void executeShellCmd(int cmdtype, String param, boolean isCloudCmd) {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to executeShellCmd by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mConnector.execute(3, isCloudCmd ? 2 : 1, Integer.valueOf(cmdtype), param);
            return;
        }
        Slog.e(TAG, name + " not permitted to executeShellCmd");
    }

    public void executeShellCmdWithLimitLine(int cmdtype, String param, int limitLine, String quitcmd, boolean isCloudCmd) {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to executeShellCmdWithLimitLine by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mConnector.execute(3, isCloudCmd ? 2 : 1, Integer.valueOf(cmdtype), param, Integer.valueOf(limitLine), quitcmd);
            return;
        }
        Slog.e(TAG, name + " not permitted to executeShellCmdWithLimitLine");
    }

    public void registerShellCmdListener(IShellCmdListener listener) {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to registerShellCmdListener by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mShellCmdListeners.register(listener);
            return;
        }
        Slog.e(TAG, name + " not permitted to registerShellCmdListener");
    }

    public void unregisterShellCmdListener(IShellCmdListener listener) {
        this.mShellCmdListeners.unregister(listener);
    }

    public void executeEncryptSh(String path, boolean isCloudCmd) {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to executeEncryptSh by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mConnector.execute(3, isCloudCmd ? 4 : 3, 1, path);
            return;
        }
        Slog.e(TAG, name + " not permitted to executeEncryptSh");
    }

    public void registerEncryptShListener(IEncryptShListener listener) {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to registerEncryptShListener by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            this.mEncryptShListeners.register(listener);
            return;
        }
        Slog.e(TAG, name + " not permitted to registerEncryptShListener");
    }

    public void unregisterEncryptShListener(IEncryptShListener listener) {
        this.mEncryptShListeners.unregister(listener);
    }

    public void enableAuthMode(String value, long time) throws RemoteException {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to enableAuthMode by :" + name);
        if (PackgeName.DEVTOOLS.equals(name)) {
            this.mConnector.execute(4, 1, value, Long.valueOf(time));
            return;
        }
        Slog.e(TAG, name + " not permitted to enableAuthMode");
    }

    public void disableAuthMode() throws RemoteException {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to disableAuthMode by :" + name);
        if (PackgeName.DEVTOOLS.equals(name) || PackgeName.SYSTEMUI.equals(name) || PackgeName.CARDIAGNOSIS.equals(name)) {
            disableAuthModeInner();
            return;
        }
        Slog.e(TAG, name + " not permitted to disableAuthMode");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void disableAuthModeInner() {
        this.mConnector.execute(4, 2, new Object[0]);
    }

    public boolean getAuthMode() throws RemoteException {
        String authmode = SystemProperties.get(SYSTEM_PROPERTY_AUTHMODE, PROPERTY_VALUE_OFF);
        Slog.i(TAG, "getAuthMode : " + authmode);
        return PROPERTY_VALUE_ON.equals(authmode);
    }

    public String getAuthPass() throws RemoteException {
        String name = getPackageNameByPid(Binder.getCallingPid());
        Slog.i(TAG, "to getAuthPass by :" + name);
        if (PackgeName.CARDIAGNOSIS.equals(name)) {
            return this.mAuthPass;
        }
        Slog.e(TAG, name + " not permitted to getAuthPass");
        return null;
    }

    public long getAuthEndTime() throws RemoteException {
        return this.mAuthEndTime;
    }

    public void registerAuthModeListener(IAuthModeListener listener) throws RemoteException {
        this.mAuthModeListeners.register(listener);
    }

    public void unregisterAuthModeListener(IAuthModeListener listener) throws RemoteException {
        this.mAuthModeListeners.unregister(listener);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void getAuthModeInner() {
        this.mConnector.execute(4, 3, new Object[0]);
    }

    /* loaded from: classes.dex */
    private class AftersalesdCallbackReceiver implements IAfterSalesDaemonCallbacks {
        private AftersalesdCallbackReceiver() {
        }

        @Override // com.xiaopeng.server.aftersales.IAfterSalesDaemonCallbacks
        public void onDaemonConnected() {
            Slog.i(AfterSalesService.TAG, "onDaemonConnected()");
            AfterSalesService.this.mConnectedSignal.countDown();
            AfterSalesService.this.getAuthModeInner();
            AfterSalesService.this.getRepairModeInner();
        }

        /* JADX WARN: Removed duplicated region for block: B:126:? A[RETURN, SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:62:0x01a6  */
        /* JADX WARN: Removed duplicated region for block: B:63:0x01ac  */
        /* JADX WARN: Removed duplicated region for block: B:64:0x01b2  */
        /* JADX WARN: Removed duplicated region for block: B:65:0x01b8  */
        @Override // com.xiaopeng.server.aftersales.IAfterSalesDaemonCallbacks
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void onEvent(com.xiaopeng.server.aftersales.AfterSalesDaemonEvent r20) {
            /*
                Method dump skipped, instructions count: 704
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.server.aftersales.AfterSalesService.AftersalesdCallbackReceiver.onEvent(com.xiaopeng.server.aftersales.AfterSalesDaemonEvent):void");
        }
    }

    /* loaded from: classes.dex */
    private class NetWorkObserver extends ConnectivityManager.NetworkCallback {
        private NetWorkObserver() {
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onAvailable(Network network) {
            Slog.i(AfterSalesService.TAG, "NetWorkObserver onAvailable:" + network);
            AfterSalesService.this.mConnector.execute(1, 3, new Object[0]);
            AfterSalesService.this.mConnector.execute(5, 2, new Object[0]);
            try {
                AfterSalesService.this.shareRepairModeStatus(AfterSalesService.this.getRepairMode(), -1);
                AfterSalesService.this.shareAuthModeStatus(AfterSalesService.this.getAuthMode(), -1);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onLost(Network network) {
            Slog.i(AfterSalesService.TAG, "NetWorkObserver onLost:" + network);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void alertDiagnosisError(int module, int errorCode, long time, String errorMsg) {
        int length = this.mAlertListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                try {
                    this.mAlertListeners.getBroadcastItem(i).alertDiagnosisError(module, errorCode, time, errorMsg);
                } catch (RemoteException | RuntimeException e) {
                    Slog.e(TAG, e.toString());
                }
            } finally {
                this.mAlertListeners.finishBroadcast();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void uploadLogicAction(String issueName, String conclusion, String startTime, String endTime, String logicactionTime, String logicactionEntry, String logictreeVer) {
        int length = this.mLogicActionListeners.beginBroadcast();
        int i = 0;
        while (true) {
            int i2 = i;
            if (i2 < length) {
                try {
                    try {
                        this.mLogicActionListeners.getBroadcastItem(i2).uploadLogicAction(issueName, conclusion, startTime, endTime, logicactionTime, logicactionEntry, logictreeVer);
                    } catch (RemoteException | RuntimeException e) {
                        Slog.e(TAG, e.toString());
                    }
                    i = i2 + 1;
                } finally {
                    this.mLogicActionListeners.finishBroadcast();
                }
            } else {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void shareLogicTreeUpgradeStatus(boolean result) {
        Slog.w(TAG, "shareLogicTreeUpgradeStatus result:" + result);
        int length = this.mLogicTreeUpgraders.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                try {
                    this.mLogicTreeUpgraders.getBroadcastItem(i).onUpgradeStatus(result);
                } catch (RemoteException | RuntimeException e) {
                    Slog.e(TAG, e.toString());
                }
            } finally {
                this.mLogicTreeUpgraders.finishBroadcast();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$new$1(Account[] accounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("onAccountsUpdated acounts length: ");
        sb.append(accounts != null ? accounts.length : 0);
        Slog.i(TAG, sb.toString());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logoutAccount() {
        if (this.mAccountManager != null) {
            Slog.e(TAG, "logoutAccount");
            this.mAccountManager.logoutExplicitly();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void shareRepairModeStatus(boolean onoff, int switchResult) {
        Slog.w(TAG, "shareRepairModeStatus onoff:" + onoff + ", switchResult:" + switchResult);
        int length = this.mRepairModeListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                try {
                    this.mRepairModeListeners.getBroadcastItem(i).onRepairModeChanged(onoff, switchResult);
                } catch (RemoteException | RuntimeException e) {
                    Slog.e(TAG, e.toString());
                }
            } finally {
                this.mRepairModeListeners.finishBroadcast();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void shareAuthModeStatus(boolean onoff, int switchResult) {
        Slog.w(TAG, "shareAuthModeStatus onoff:" + onoff + ", switchResult:" + switchResult);
        int length = this.mAuthModeListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                try {
                    this.mAuthModeListeners.getBroadcastItem(i).onAuthModeChanged(onoff, switchResult);
                } catch (RemoteException | RuntimeException e) {
                    Slog.e(TAG, e.toString());
                }
            } finally {
                this.mAuthModeListeners.finishBroadcast();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void responseShellResult(int errorcode, String resultPath, boolean isCloudCmd) {
        Slog.w(TAG, "responseShellResult errorcode:" + errorcode + ", resultPath:" + resultPath);
        int length = this.mShellCmdListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                try {
                    this.mShellCmdListeners.getBroadcastItem(i).onShellResponse(errorcode, resultPath, isCloudCmd);
                } catch (RemoteException | RuntimeException e) {
                    Slog.e(TAG, e.toString());
                }
            } finally {
                this.mShellCmdListeners.finishBroadcast();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void responseEncryptShResult(int errorcode, String resultPath, String outputPath, boolean isCloudCmd) {
        Slog.w(TAG, "responseShellResult errorcode:" + errorcode + ", resultPath:" + resultPath);
        int length = this.mEncryptShListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            try {
                try {
                    this.mEncryptShListeners.getBroadcastItem(i).onEncryptShResponse(errorcode, resultPath, outputPath, isCloudCmd);
                } catch (RemoteException | RuntimeException e) {
                    Slog.e(TAG, e.toString());
                }
            } finally {
                this.mEncryptShListeners.finishBroadcast();
            }
        }
    }

    private String getPackageNameByPid(int pid) {
        ActivityManager am = (ActivityManager) this.mContext.getSystemService("activity");
        List<ActivityManager.RunningAppProcessInfo> appProcessList = am.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo appProcess : appProcessList) {
            if (pid == appProcess.pid) {
                String packageName = appProcess.processName;
                return packageName;
            }
        }
        return UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN;
    }
}
