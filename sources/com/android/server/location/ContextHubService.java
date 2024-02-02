package com.android.server.location;

import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.HubAppInfo;
import android.hardware.contexthub.V1_0.IContexthub;
import android.hardware.contexthub.V1_0.IContexthubCallback;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubMessage;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubCallback;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.IContextHubService;
import android.hardware.location.IContextHubTransactionCallback;
import android.hardware.location.NanoApp;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppFilter;
import android.hardware.location.NanoAppInstanceInfo;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.util.DumpUtils;
import com.android.server.backup.BackupManagerConstants;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
/* loaded from: classes.dex */
public class ContextHubService extends IContextHubService.Stub {
    public static final int MSG_DISABLE_NANO_APP = 2;
    public static final int MSG_ENABLE_NANO_APP = 1;
    public static final int MSG_HUB_RESET = 7;
    public static final int MSG_LOAD_NANO_APP = 3;
    public static final int MSG_QUERY_MEMORY = 6;
    public static final int MSG_QUERY_NANO_APPS = 5;
    public static final int MSG_UNLOAD_NANO_APP = 4;
    private static final int OS_APP_INSTANCE = -1;
    private static final String TAG = "ContextHubService";
    private final ContextHubClientManager mClientManager;
    private final Context mContext;
    private final Map<Integer, ContextHubInfo> mContextHubIdToInfoMap;
    private final List<ContextHubInfo> mContextHubInfoList;
    private final Map<Integer, IContextHubClient> mDefaultClientMap;
    private final ContextHubTransactionManager mTransactionManager;
    private final RemoteCallbackList<IContextHubCallback> mCallbacksList = new RemoteCallbackList<>();
    private final NanoAppStateManager mNanoAppStateManager = new NanoAppStateManager();
    private final IContexthub mContextHubProxy = getContextHubProxy();

    /* loaded from: classes.dex */
    private class ContextHubServiceCallback extends IContexthubCallback.Stub {
        private final int mContextHubId;

        ContextHubServiceCallback(int contextHubId) {
            this.mContextHubId = contextHubId;
        }

        @Override // android.hardware.contexthub.V1_0.IContexthubCallback
        public void handleClientMsg(ContextHubMsg message) {
            ContextHubService.this.handleClientMessageCallback(this.mContextHubId, message);
        }

        @Override // android.hardware.contexthub.V1_0.IContexthubCallback
        public void handleTxnResult(int transactionId, int result) {
            ContextHubService.this.handleTransactionResultCallback(this.mContextHubId, transactionId, result);
        }

        @Override // android.hardware.contexthub.V1_0.IContexthubCallback
        public void handleHubEvent(int eventType) {
            ContextHubService.this.handleHubEventCallback(this.mContextHubId, eventType);
        }

        @Override // android.hardware.contexthub.V1_0.IContexthubCallback
        public void handleAppAbort(long nanoAppId, int abortCode) {
            ContextHubService.this.handleAppAbortCallback(this.mContextHubId, nanoAppId, abortCode);
        }

        @Override // android.hardware.contexthub.V1_0.IContexthubCallback
        public void handleAppsInfo(ArrayList<HubAppInfo> nanoAppInfoList) {
            ContextHubService.this.handleQueryAppsCallback(this.mContextHubId, nanoAppInfoList);
        }
    }

    public ContextHubService(Context context) {
        List<ContextHub> hubList;
        this.mContext = context;
        if (this.mContextHubProxy == null) {
            this.mTransactionManager = null;
            this.mClientManager = null;
            this.mDefaultClientMap = Collections.emptyMap();
            this.mContextHubIdToInfoMap = Collections.emptyMap();
            this.mContextHubInfoList = Collections.emptyList();
            return;
        }
        this.mClientManager = new ContextHubClientManager(this.mContext, this.mContextHubProxy);
        this.mTransactionManager = new ContextHubTransactionManager(this.mContextHubProxy, this.mClientManager, this.mNanoAppStateManager);
        try {
            hubList = this.mContextHubProxy.getHubs();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Context Hub info", e);
            hubList = Collections.emptyList();
        }
        this.mContextHubIdToInfoMap = Collections.unmodifiableMap(ContextHubServiceUtil.createContextHubInfoMap(hubList));
        this.mContextHubInfoList = new ArrayList(this.mContextHubIdToInfoMap.values());
        HashMap<Integer, IContextHubClient> defaultClientMap = new HashMap<>();
        for (Integer num : this.mContextHubIdToInfoMap.keySet()) {
            int contextHubId = num.intValue();
            IContextHubClient client = this.mClientManager.registerClient(createDefaultClientCallback(contextHubId), contextHubId);
            defaultClientMap.put(Integer.valueOf(contextHubId), client);
            try {
                this.mContextHubProxy.registerCallback(contextHubId, new ContextHubServiceCallback(contextHubId));
            } catch (RemoteException e2) {
                Log.e(TAG, "RemoteException while registering service callback for hub (ID = " + contextHubId + ")", e2);
            }
            queryNanoAppsInternal(contextHubId);
        }
        this.mDefaultClientMap = Collections.unmodifiableMap(defaultClientMap);
    }

    private IContextHubClientCallback createDefaultClientCallback(final int contextHubId) {
        return new IContextHubClientCallback.Stub() { // from class: com.android.server.location.ContextHubService.1
            public void onMessageFromNanoApp(NanoAppMessage message) {
                int nanoAppHandle = ContextHubService.this.mNanoAppStateManager.getNanoAppHandle(contextHubId, message.getNanoAppId());
                ContextHubService.this.onMessageReceiptOldApi(message.getMessageType(), contextHubId, nanoAppHandle, message.getMessageBody());
            }

            public void onHubReset() {
                byte[] data = {0};
                ContextHubService.this.onMessageReceiptOldApi(7, contextHubId, -1, data);
            }

            public void onNanoAppAborted(long nanoAppId, int abortCode) {
            }

            public void onNanoAppLoaded(long nanoAppId) {
            }

            public void onNanoAppUnloaded(long nanoAppId) {
            }

            public void onNanoAppEnabled(long nanoAppId) {
            }

            public void onNanoAppDisabled(long nanoAppId) {
            }
        };
    }

    private IContexthub getContextHubProxy() {
        try {
            IContexthub proxy = IContexthub.getService(true);
            return proxy;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while attaching to Context Hub HAL proxy", e);
            return null;
        } catch (NoSuchElementException e2) {
            Log.i(TAG, "Context Hub HAL service not found");
            return null;
        }
    }

    public int registerCallback(IContextHubCallback callback) throws RemoteException {
        checkPermissions();
        this.mCallbacksList.register(callback);
        Log.d(TAG, "Added callback, total callbacks " + this.mCallbacksList.getRegisteredCallbackCount());
        return 0;
    }

    public int[] getContextHubHandles() throws RemoteException {
        checkPermissions();
        return ContextHubServiceUtil.createPrimitiveIntArray(this.mContextHubIdToInfoMap.keySet());
    }

    public ContextHubInfo getContextHubInfo(int contextHubHandle) throws RemoteException {
        checkPermissions();
        if (!this.mContextHubIdToInfoMap.containsKey(Integer.valueOf(contextHubHandle))) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in getContextHubInfo");
            return null;
        }
        return this.mContextHubIdToInfoMap.get(Integer.valueOf(contextHubHandle));
    }

    public List<ContextHubInfo> getContextHubs() throws RemoteException {
        checkPermissions();
        return this.mContextHubInfoList;
    }

    private IContextHubTransactionCallback createLoadTransactionCallback(final int contextHubId, final NanoAppBinary nanoAppBinary) {
        return new IContextHubTransactionCallback.Stub() { // from class: com.android.server.location.ContextHubService.2
            public void onTransactionComplete(int result) {
                ContextHubService.this.handleLoadResponseOldApi(contextHubId, result, nanoAppBinary);
            }

            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
            }
        };
    }

    private IContextHubTransactionCallback createUnloadTransactionCallback(final int contextHubId) {
        return new IContextHubTransactionCallback.Stub() { // from class: com.android.server.location.ContextHubService.3
            public void onTransactionComplete(int result) {
                ContextHubService.this.handleUnloadResponseOldApi(contextHubId, result);
            }

            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
            }
        };
    }

    private IContextHubTransactionCallback createQueryTransactionCallback(final int contextHubId) {
        return new IContextHubTransactionCallback.Stub() { // from class: com.android.server.location.ContextHubService.4
            public void onTransactionComplete(int result) {
            }

            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
                byte[] data = {(byte) result};
                ContextHubService.this.onMessageReceiptOldApi(5, contextHubId, -1, data);
            }
        };
    }

    public int loadNanoApp(int contextHubHandle, NanoApp nanoApp) throws RemoteException {
        checkPermissions();
        if (this.mContextHubProxy == null) {
            return -1;
        }
        if (!isValidContextHubId(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in loadNanoApp");
            return -1;
        } else if (nanoApp == null) {
            Log.e(TAG, "NanoApp cannot be null in loadNanoApp");
            return -1;
        } else {
            NanoAppBinary nanoAppBinary = new NanoAppBinary(nanoApp.getAppBinary());
            IContextHubTransactionCallback onCompleteCallback = createLoadTransactionCallback(contextHubHandle, nanoAppBinary);
            ContextHubServiceTransaction transaction = this.mTransactionManager.createLoadTransaction(contextHubHandle, nanoAppBinary, onCompleteCallback);
            this.mTransactionManager.addTransaction(transaction);
            return 0;
        }
    }

    public int unloadNanoApp(int nanoAppHandle) throws RemoteException {
        checkPermissions();
        if (this.mContextHubProxy == null) {
            return -1;
        }
        NanoAppInstanceInfo info = this.mNanoAppStateManager.getNanoAppInstanceInfo(nanoAppHandle);
        if (info == null) {
            Log.e(TAG, "Invalid nanoapp handle " + nanoAppHandle + " in unloadNanoApp");
            return -1;
        }
        int contextHubId = info.getContexthubId();
        long nanoAppId = info.getAppId();
        IContextHubTransactionCallback onCompleteCallback = createUnloadTransactionCallback(contextHubId);
        ContextHubServiceTransaction transaction = this.mTransactionManager.createUnloadTransaction(contextHubId, nanoAppId, onCompleteCallback);
        this.mTransactionManager.addTransaction(transaction);
        return 0;
    }

    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) throws RemoteException {
        checkPermissions();
        return this.mNanoAppStateManager.getNanoAppInstanceInfo(nanoAppHandle);
    }

    public int[] findNanoAppOnHub(int contextHubHandle, NanoAppFilter filter) throws RemoteException {
        checkPermissions();
        ArrayList<Integer> foundInstances = new ArrayList<>();
        for (NanoAppInstanceInfo info : this.mNanoAppStateManager.getNanoAppInstanceInfoCollection()) {
            if (filter.testMatch(info)) {
                foundInstances.add(Integer.valueOf(info.getHandle()));
            }
        }
        int[] retArray = new int[foundInstances.size()];
        for (int i = 0; i < foundInstances.size(); i++) {
            retArray[i] = foundInstances.get(i).intValue();
        }
        return retArray;
    }

    private int queryNanoAppsInternal(int contextHubId) {
        if (this.mContextHubProxy == null) {
            return 1;
        }
        IContextHubTransactionCallback onCompleteCallback = createQueryTransactionCallback(contextHubId);
        ContextHubServiceTransaction transaction = this.mTransactionManager.createQueryTransaction(contextHubId, onCompleteCallback);
        this.mTransactionManager.addTransaction(transaction);
        return 0;
    }

    public int sendMessage(int contextHubHandle, int nanoAppHandle, ContextHubMessage msg) throws RemoteException {
        checkPermissions();
        if (this.mContextHubProxy == null) {
            return -1;
        }
        if (msg == null) {
            Log.e(TAG, "ContextHubMessage cannot be null in sendMessage");
            return -1;
        } else if (msg.getData() == null) {
            Log.e(TAG, "ContextHubMessage message body cannot be null in sendMessage");
            return -1;
        } else if (!isValidContextHubId(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in sendMessage");
            return -1;
        } else {
            boolean success = false;
            if (nanoAppHandle == -1) {
                if (msg.getMsgType() == 5) {
                    success = queryNanoAppsInternal(contextHubHandle) == 0;
                } else {
                    Log.e(TAG, "Invalid OS message params of type " + msg.getMsgType());
                }
            } else {
                NanoAppInstanceInfo info = getNanoAppInstanceInfo(nanoAppHandle);
                if (info != null) {
                    NanoAppMessage message = NanoAppMessage.createMessageToNanoApp(info.getAppId(), msg.getMsgType(), msg.getData());
                    IContextHubClient client = this.mDefaultClientMap.get(Integer.valueOf(contextHubHandle));
                    success = client.sendMessageToNanoApp(message) == 0;
                } else {
                    Log.e(TAG, "Failed to send nanoapp message - nanoapp with handle " + nanoAppHandle + " does not exist.");
                }
            }
            return success ? 0 : -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleClientMessageCallback(int contextHubId, ContextHubMsg message) {
        this.mClientManager.onMessageFromNanoApp(contextHubId, message);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleLoadResponseOldApi(int contextHubId, int result, NanoAppBinary nanoAppBinary) {
        if (nanoAppBinary == null) {
            Log.e(TAG, "Nanoapp binary field was null for a load transaction");
            return;
        }
        byte[] data = new byte[5];
        data[0] = (byte) result;
        int nanoAppHandle = this.mNanoAppStateManager.getNanoAppHandle(contextHubId, nanoAppBinary.getNanoAppId());
        ByteBuffer.wrap(data, 1, 4).order(ByteOrder.nativeOrder()).putInt(nanoAppHandle);
        onMessageReceiptOldApi(3, contextHubId, -1, data);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUnloadResponseOldApi(int contextHubId, int result) {
        byte[] data = {(byte) result};
        onMessageReceiptOldApi(4, contextHubId, -1, data);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleTransactionResultCallback(int contextHubId, int transactionId, int result) {
        this.mTransactionManager.onTransactionResponse(transactionId, result);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleHubEventCallback(int contextHubId, int eventType) {
        if (eventType == 1) {
            this.mTransactionManager.onHubReset();
            queryNanoAppsInternal(contextHubId);
            this.mClientManager.onHubReset(contextHubId);
            return;
        }
        Log.i(TAG, "Received unknown hub event (hub ID = " + contextHubId + ", type = " + eventType + ")");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleAppAbortCallback(int contextHubId, long nanoAppId, int abortCode) {
        this.mClientManager.onNanoAppAborted(contextHubId, nanoAppId, abortCode);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleQueryAppsCallback(int contextHubId, List<HubAppInfo> nanoAppInfoList) {
        List<NanoAppState> nanoAppStateList = ContextHubServiceUtil.createNanoAppStateList(nanoAppInfoList);
        this.mNanoAppStateManager.updateCache(contextHubId, nanoAppInfoList);
        this.mTransactionManager.onQueryResponse(nanoAppStateList);
    }

    private boolean isValidContextHubId(int contextHubId) {
        return this.mContextHubIdToInfoMap.containsKey(Integer.valueOf(contextHubId));
    }

    public IContextHubClient createClient(IContextHubClientCallback clientCallback, int contextHubId) throws RemoteException {
        checkPermissions();
        if (!isValidContextHubId(contextHubId)) {
            throw new IllegalArgumentException("Invalid context hub ID " + contextHubId);
        } else if (clientCallback == null) {
            throw new NullPointerException("Cannot register client with null callback");
        } else {
            return this.mClientManager.registerClient(clientCallback, contextHubId);
        }
    }

    public void loadNanoAppOnHub(int contextHubId, IContextHubTransactionCallback transactionCallback, NanoAppBinary nanoAppBinary) throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(contextHubId, transactionCallback, 0)) {
            return;
        }
        if (nanoAppBinary == null) {
            Log.e(TAG, "NanoAppBinary cannot be null in loadNanoAppOnHub");
            transactionCallback.onTransactionComplete(2);
            return;
        }
        ContextHubServiceTransaction transaction = this.mTransactionManager.createLoadTransaction(contextHubId, nanoAppBinary, transactionCallback);
        this.mTransactionManager.addTransaction(transaction);
    }

    public void unloadNanoAppFromHub(int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId) throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(contextHubId, transactionCallback, 1)) {
            return;
        }
        ContextHubServiceTransaction transaction = this.mTransactionManager.createUnloadTransaction(contextHubId, nanoAppId, transactionCallback);
        this.mTransactionManager.addTransaction(transaction);
    }

    public void enableNanoApp(int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId) throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(contextHubId, transactionCallback, 2)) {
            return;
        }
        ContextHubServiceTransaction transaction = this.mTransactionManager.createEnableTransaction(contextHubId, nanoAppId, transactionCallback);
        this.mTransactionManager.addTransaction(transaction);
    }

    public void disableNanoApp(int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId) throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(contextHubId, transactionCallback, 3)) {
            return;
        }
        ContextHubServiceTransaction transaction = this.mTransactionManager.createDisableTransaction(contextHubId, nanoAppId, transactionCallback);
        this.mTransactionManager.addTransaction(transaction);
    }

    public void queryNanoApps(int contextHubId, IContextHubTransactionCallback transactionCallback) throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(contextHubId, transactionCallback, 4)) {
            return;
        }
        ContextHubServiceTransaction transaction = this.mTransactionManager.createQueryTransaction(contextHubId, transactionCallback);
        this.mTransactionManager.addTransaction(transaction);
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            pw.println("Dumping ContextHub Service");
            pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            pw.println("=================== CONTEXT HUBS ====================");
            for (ContextHubInfo hubInfo : this.mContextHubIdToInfoMap.values()) {
                pw.println(hubInfo);
            }
            pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            pw.println("=================== NANOAPPS ====================");
            for (NanoAppInstanceInfo info : this.mNanoAppStateManager.getNanoAppInstanceInfoCollection()) {
                pw.println(info);
            }
        }
    }

    private void checkPermissions() {
        ContextHubServiceUtil.checkPermissions(this.mContext);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int onMessageReceiptOldApi(int msgType, int contextHubHandle, int appInstance, byte[] data) {
        if (data == null) {
            return -1;
        }
        int callbacksCount = this.mCallbacksList.beginBroadcast();
        Log.d(TAG, "Sending message " + msgType + " version 0 from hubHandle " + contextHubHandle + ", appInstance " + appInstance + ", callBackCount " + callbacksCount);
        if (callbacksCount < 1) {
            Log.v(TAG, "No message callbacks registered.");
            return 0;
        }
        ContextHubMessage msg = new ContextHubMessage(msgType, 0, data);
        for (int i = 0; i < callbacksCount; i++) {
            IContextHubCallback callback = this.mCallbacksList.getBroadcastItem(i);
            try {
                callback.onMessageReceipt(contextHubHandle, appInstance, msg);
            } catch (RemoteException e) {
                Log.i(TAG, "Exception (" + e + ") calling remote callback (" + callback + ").");
            }
        }
        this.mCallbacksList.finishBroadcast();
        return 0;
    }

    private boolean checkHalProxyAndContextHubId(int contextHubId, IContextHubTransactionCallback callback, int transactionType) {
        if (this.mContextHubProxy == null) {
            try {
                callback.onTransactionComplete(8);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onTransactionComplete", e);
            }
            return false;
        } else if (!isValidContextHubId(contextHubId)) {
            Log.e(TAG, "Cannot start " + ContextHubTransaction.typeToString(transactionType, false) + " transaction for invalid hub ID " + contextHubId);
            try {
                callback.onTransactionComplete(2);
            } catch (RemoteException e2) {
                Log.e(TAG, "RemoteException while calling onTransactionComplete", e2);
            }
            return false;
        } else {
            return true;
        }
    }
}
