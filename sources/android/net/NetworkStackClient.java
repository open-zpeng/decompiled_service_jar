package android.net;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.INetworkStackConnector;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.ip.IIpClientCallbacks;
import android.net.util.SharedLog;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.job.controllers.JobStatus;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;

/* loaded from: classes.dex */
public class NetworkStackClient {
    private static final String CONFIG_ALWAYS_RATELIMIT_NETWORKSTACK_CRASH = "always_ratelimit_networkstack_crash";
    private static final String CONFIG_MIN_CRASH_INTERVAL_MS = "min_crash_interval";
    private static final String CONFIG_MIN_UPTIME_BEFORE_CRASH_MS = "min_uptime_before_crash";
    private static final long DEFAULT_MIN_CRASH_INTERVAL_MS = 21600000;
    private static final long DEFAULT_MIN_UPTIME_BEFORE_CRASH_MS = 1800000;
    private static final String IN_PROCESS_SUFFIX = ".InProcess";
    private static final int NETWORKSTACK_TIMEOUT_MS = 10000;
    private static final String PREFS_FILE = "NetworkStackClientPrefs.xml";
    private static final String PREF_KEY_LAST_CRASH_TIME = "lastcrash_time";
    private static final String TAG = NetworkStackClient.class.getSimpleName();
    private static NetworkStackClient sInstance;
    @GuardedBy({"mPendingNetStackRequests"})
    private INetworkStackConnector mConnector;
    @GuardedBy({"mPendingNetStackRequests"})
    private final ArrayList<NetworkStackCallback> mPendingNetStackRequests = new ArrayList<>();
    @GuardedBy({"mLog"})
    private final SharedLog mLog = new SharedLog(TAG);
    private volatile boolean mWasSystemServerInitialized = false;
    @GuardedBy({"mHealthListeners"})
    private final ArraySet<NetworkStackHealthListener> mHealthListeners = new ArraySet<>();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public interface NetworkStackCallback {
        void onNetworkStackConnected(INetworkStackConnector iNetworkStackConnector);
    }

    /* loaded from: classes.dex */
    public interface NetworkStackHealthListener {
        void onNetworkStackFailure(String str);
    }

    private NetworkStackClient() {
    }

    public static synchronized NetworkStackClient getInstance() {
        NetworkStackClient networkStackClient;
        synchronized (NetworkStackClient.class) {
            if (sInstance == null) {
                sInstance = new NetworkStackClient();
            }
            networkStackClient = sInstance;
        }
        return networkStackClient;
    }

    public void registerHealthListener(NetworkStackHealthListener listener) {
        synchronized (this.mHealthListeners) {
            this.mHealthListeners.add(listener);
        }
    }

    public void makeDhcpServer(final String ifName, final DhcpServingParamsParcel params, final IDhcpServerCallbacks cb) {
        requestConnector(new NetworkStackCallback() { // from class: android.net.-$$Lambda$NetworkStackClient$tuv4lz5fwSxR2XuU69pB4cKkltA
            @Override // android.net.NetworkStackClient.NetworkStackCallback
            public final void onNetworkStackConnected(INetworkStackConnector iNetworkStackConnector) {
                NetworkStackClient.lambda$makeDhcpServer$0(ifName, params, cb, iNetworkStackConnector);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$makeDhcpServer$0(String ifName, DhcpServingParamsParcel params, IDhcpServerCallbacks cb, INetworkStackConnector connector) {
        try {
            connector.makeDhcpServer(ifName, params, cb);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public void makeIpClient(final String ifName, final IIpClientCallbacks cb) {
        requestConnector(new NetworkStackCallback() { // from class: android.net.-$$Lambda$NetworkStackClient$EsrnifYD8E-HxTwVQsf45HJKvtM
            @Override // android.net.NetworkStackClient.NetworkStackCallback
            public final void onNetworkStackConnected(INetworkStackConnector iNetworkStackConnector) {
                NetworkStackClient.lambda$makeIpClient$1(ifName, cb, iNetworkStackConnector);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$makeIpClient$1(String ifName, IIpClientCallbacks cb, INetworkStackConnector connector) {
        try {
            connector.makeIpClient(ifName, cb);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public void makeNetworkMonitor(final Network network, final String name, final INetworkMonitorCallbacks cb) {
        requestConnector(new NetworkStackCallback() { // from class: android.net.-$$Lambda$NetworkStackClient$8Y7GJyozK7_xixdmgfHS4QSif-A
            @Override // android.net.NetworkStackClient.NetworkStackCallback
            public final void onNetworkStackConnected(INetworkStackConnector iNetworkStackConnector) {
                NetworkStackClient.lambda$makeNetworkMonitor$2(network, name, cb, iNetworkStackConnector);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$makeNetworkMonitor$2(Network network, String name, INetworkMonitorCallbacks cb, INetworkStackConnector connector) {
        try {
            connector.makeNetworkMonitor(network, name, cb);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public void fetchIpMemoryStore(final IIpMemoryStoreCallbacks cb) {
        requestConnector(new NetworkStackCallback() { // from class: android.net.-$$Lambda$NetworkStackClient$qInwLPrclXOFvKSYRjcCaCSeEhw
            @Override // android.net.NetworkStackClient.NetworkStackCallback
            public final void onNetworkStackConnected(INetworkStackConnector iNetworkStackConnector) {
                NetworkStackClient.lambda$fetchIpMemoryStore$3(IIpMemoryStoreCallbacks.this, iNetworkStackConnector);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$fetchIpMemoryStore$3(IIpMemoryStoreCallbacks cb, INetworkStackConnector connector) {
        try {
            connector.fetchIpMemoryStore(cb);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /* loaded from: classes.dex */
    private class NetworkStackConnection implements ServiceConnection {
        private final Context mContext;
        private final String mPackageName;

        private NetworkStackConnection(Context context, String packageName) {
            this.mContext = context;
            this.mPackageName = packageName;
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            NetworkStackClient.this.logi("Network stack service connected");
            NetworkStackClient.this.registerNetworkStackService(service);
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            NetworkStackClient.this.maybeCrashWithTerribleFailure("Lost network stack", this.mContext, this.mPackageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void registerNetworkStackService(IBinder service) {
        ArrayList<NetworkStackCallback> requests;
        INetworkStackConnector connector = INetworkStackConnector.Stub.asInterface(service);
        ServiceManager.addService("network_stack", service, false, 6);
        log("Network stack service registered");
        synchronized (this.mPendingNetStackRequests) {
            requests = new ArrayList<>(this.mPendingNetStackRequests);
            this.mPendingNetStackRequests.clear();
            this.mConnector = connector;
        }
        Iterator<NetworkStackCallback> it = requests.iterator();
        while (it.hasNext()) {
            NetworkStackCallback r = it.next();
            r.onNetworkStackConnected(connector);
        }
    }

    public void init() {
        log("Network stack init");
        this.mWasSystemServerInitialized = true;
    }

    public void start(Context context) {
        log("Starting network stack");
        PackageManager pm = context.getPackageManager();
        Intent intent = getNetworkStackIntent(pm, true);
        if (intent == null) {
            intent = getNetworkStackIntent(pm, false);
            log("Starting network stack process");
        } else {
            log("Starting network stack in-process");
        }
        if (intent == null) {
            maybeCrashWithTerribleFailure("Could not resolve the network stack", context, null);
            return;
        }
        String packageName = intent.getComponent().getPackageName();
        if (!context.bindServiceAsUser(intent, new NetworkStackConnection(context, packageName), 65, UserHandle.SYSTEM)) {
            maybeCrashWithTerribleFailure("Could not bind to network stack in-process, or in app with " + intent, context, packageName);
            return;
        }
        log("Network stack service start requested");
    }

    private Intent getNetworkStackIntent(PackageManager pm, boolean inSystemProcess) {
        String str;
        String baseAction = INetworkStackConnector.class.getName();
        if (inSystemProcess) {
            str = baseAction + IN_PROCESS_SUFFIX;
        } else {
            str = baseAction;
        }
        Intent intent = new Intent(str);
        ComponentName comp = intent.resolveSystemService(pm, 0);
        if (comp == null) {
            return null;
        }
        intent.setComponent(comp);
        int uid = -1;
        try {
            uid = pm.getPackageUidAsUser(comp.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            logWtf("Network stack package not found", e);
        }
        int expectedUid = inSystemProcess ? 1000 : 1073;
        if (uid != expectedUid) {
            throw new SecurityException("Invalid network stack UID: " + uid);
        }
        if (!inSystemProcess) {
            checkNetworkStackPermission(pm, comp);
        }
        return intent;
    }

    private void checkNetworkStackPermission(PackageManager pm, ComponentName comp) {
        int hasPermission = pm.checkPermission("android.permission.MAINLINE_NETWORK_STACK", comp.getPackageName());
        if (hasPermission != 0) {
            throw new SecurityException("Network stack does not have permission android.permission.MAINLINE_NETWORK_STACK");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void maybeCrashWithTerribleFailure(String message, Context context, String packageName) {
        ArraySet<NetworkStackHealthListener> listeners;
        logWtf(message, null);
        long uptime = SystemClock.elapsedRealtime();
        long now = System.currentTimeMillis();
        long minCrashIntervalMs = DeviceConfig.getLong("connectivity", CONFIG_MIN_CRASH_INTERVAL_MS, (long) DEFAULT_MIN_CRASH_INTERVAL_MS);
        long minUptimeBeforeCrash = DeviceConfig.getLong("connectivity", CONFIG_MIN_UPTIME_BEFORE_CRASH_MS, 1800000L);
        boolean z = false;
        boolean alwaysRatelimit = DeviceConfig.getBoolean("connectivity", CONFIG_ALWAYS_RATELIMIT_NETWORKSTACK_CRASH, false);
        SharedPreferences prefs = getSharedPreferences(context);
        long lastCrashTime = tryGetLastCrashTime(prefs);
        boolean alwaysCrash = Build.IS_DEBUGGABLE && !alwaysRatelimit;
        boolean justBooted = uptime < minUptimeBeforeCrash;
        boolean haveLastCrashTime = lastCrashTime != 0 && lastCrashTime < now;
        if (haveLastCrashTime && now < lastCrashTime + minCrashIntervalMs) {
            z = true;
        }
        boolean haveKnownRecentCrash = z;
        if (!alwaysCrash && (justBooted || haveKnownRecentCrash)) {
            if (packageName != null) {
                synchronized (this.mHealthListeners) {
                    listeners = new ArraySet<>(this.mHealthListeners);
                }
                Iterator<NetworkStackHealthListener> it = listeners.iterator();
                while (it.hasNext()) {
                    NetworkStackHealthListener listener = it.next();
                    listener.onNetworkStackFailure(packageName);
                }
                return;
            }
            return;
        }
        tryWriteLastCrashTime(prefs, now);
        throw new IllegalStateException(message);
    }

    private SharedPreferences getSharedPreferences(Context context) {
        try {
            File prefsFile = new File(Environment.getDataSystemDeDirectory(0), PREFS_FILE);
            return context.createDeviceProtectedStorageContext().getSharedPreferences(prefsFile, 0);
        } catch (Throwable e) {
            logWtf("Error loading shared preferences", e);
            return null;
        }
    }

    private long tryGetLastCrashTime(SharedPreferences prefs) {
        if (prefs == null) {
            return 0L;
        }
        try {
            return prefs.getLong(PREF_KEY_LAST_CRASH_TIME, 0L);
        } catch (Throwable e) {
            logWtf("Error getting last crash time", e);
            return 0L;
        }
    }

    private void tryWriteLastCrashTime(SharedPreferences prefs, long value) {
        if (prefs == null) {
            return;
        }
        try {
            prefs.edit().putLong(PREF_KEY_LAST_CRASH_TIME, value).commit();
        } catch (Throwable e) {
            logWtf("Error writing last crash time", e);
        }
    }

    private void log(String message) {
        synchronized (this.mLog) {
            this.mLog.log(message);
        }
    }

    private void logWtf(String message, Throwable e) {
        Slog.wtf(TAG, message);
        synchronized (this.mLog) {
            this.mLog.e(message, e);
        }
    }

    private void loge(String message, Throwable e) {
        synchronized (this.mLog) {
            this.mLog.e(message, e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logi(String message) {
        synchronized (this.mLog) {
            this.mLog.i(message);
        }
    }

    private INetworkStackConnector getRemoteConnector() {
        try {
            long before = System.currentTimeMillis();
            do {
                IBinder connector = ServiceManager.getService("network_stack");
                if (connector == null) {
                    Thread.sleep(20L);
                } else {
                    return INetworkStackConnector.Stub.asInterface(connector);
                }
            } while (System.currentTimeMillis() - before <= JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
            loge("Timeout waiting for NetworkStack connector", null);
            return null;
        } catch (InterruptedException e) {
            loge("Error waiting for NetworkStack connector", e);
            return null;
        }
    }

    private void requestConnector(NetworkStackCallback request) {
        int caller = Binder.getCallingUid();
        if (caller != 1000 && !UserHandle.isSameApp(caller, 1002) && !UserHandle.isSameApp(caller, NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE)) {
            throw new SecurityException("Only the system server should try to bind to the network stack.");
        }
        if (!this.mWasSystemServerInitialized) {
            INetworkStackConnector connector = getRemoteConnector();
            synchronized (this.mPendingNetStackRequests) {
                this.mConnector = connector;
            }
            request.onNetworkStackConnected(connector);
            return;
        }
        synchronized (this.mPendingNetStackRequests) {
            INetworkStackConnector connector2 = this.mConnector;
            if (connector2 == null) {
                this.mPendingNetStackRequests.add(request);
            } else {
                request.onNetworkStackConnected(connector2);
            }
        }
    }

    public void dump(PrintWriter pw) {
        int requestsQueueLength;
        this.mLog.dump(null, pw, null);
        synchronized (this.mPendingNetStackRequests) {
            requestsQueueLength = this.mPendingNetStackRequests.size();
        }
        pw.println();
        pw.println("pendingNetStackRequests length: " + requestsQueueLength);
    }
}
