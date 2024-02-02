package com.android.server.connectivity;

import android.content.Context;
import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.metrics.ApfProgramEvent;
import android.os.Binder;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBuffer;
import com.android.internal.util.TokenBucket;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;
/* loaded from: classes.dex */
public final class IpConnectivityMetrics extends SystemService {
    private static final boolean DBG = false;
    private static final int DEFAULT_BUFFER_SIZE = 2000;
    private static final int DEFAULT_LOG_SIZE = 500;
    private static final int ERROR_RATE_LIMITED = -1;
    private static final int MAXIMUM_BUFFER_SIZE = 20000;
    private static final int MAXIMUM_CONNECT_LATENCY_RECORDS = 20000;
    private static final int NYC = 0;
    private static final int NYC_MR1 = 1;
    private static final int NYC_MR2 = 2;
    private static final String SERVICE_NAME = "connmetrics";
    public static final int VERSION = 2;
    @VisibleForTesting
    public final Impl impl;
    @GuardedBy("mLock")
    private final ArrayMap<Class<?>, TokenBucket> mBuckets;
    @GuardedBy("mLock")
    private ArrayList<ConnectivityMetricsEvent> mBuffer;
    @GuardedBy("mLock")
    private int mCapacity;
    private final ToIntFunction<Context> mCapacityGetter;
    @VisibleForTesting
    final DefaultNetworkMetrics mDefaultNetworkMetrics;
    @GuardedBy("mLock")
    private int mDropped;
    @GuardedBy("mLock")
    private final RingBuffer<ConnectivityMetricsEvent> mEventLog;
    private final Object mLock;
    @VisibleForTesting
    NetdEventListenerService mNetdListener;
    private static final String TAG = IpConnectivityMetrics.class.getSimpleName();
    private static final ToIntFunction<Context> READ_BUFFER_SIZE = new ToIntFunction() { // from class: com.android.server.connectivity.-$$Lambda$IpConnectivityMetrics$B0oR30xfeM300kIzUVaV_zUNLCg
        @Override // java.util.function.ToIntFunction
        public final int applyAsInt(Object obj) {
            return IpConnectivityMetrics.lambda$static$0((Context) obj);
        }
    };

    /* loaded from: classes.dex */
    public interface Logger {
        DefaultNetworkMetrics defaultNetworkMetrics();
    }

    public IpConnectivityMetrics(Context ctx, ToIntFunction<Context> capacityGetter) {
        super(ctx);
        this.mLock = new Object();
        this.impl = new Impl();
        this.mEventLog = new RingBuffer<>(ConnectivityMetricsEvent.class, 500);
        this.mBuckets = makeRateLimitingBuckets();
        this.mDefaultNetworkMetrics = new DefaultNetworkMetrics();
        this.mCapacityGetter = capacityGetter;
        initBuffer();
    }

    public IpConnectivityMetrics(Context ctx) {
        this(ctx, READ_BUFFER_SIZE);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            this.mNetdListener = new NetdEventListenerService(getContext());
            publishBinderService(SERVICE_NAME, this.impl);
            NetdEventListenerService netdEventListenerService = this.mNetdListener;
            publishBinderService(NetdEventListenerService.SERVICE_NAME, this.mNetdListener);
            LocalServices.addService(Logger.class, new LoggerImpl());
        }
    }

    @VisibleForTesting
    public int bufferCapacity() {
        return this.mCapacityGetter.applyAsInt(getContext());
    }

    private void initBuffer() {
        synchronized (this.mLock) {
            this.mDropped = 0;
            this.mCapacity = bufferCapacity();
            this.mBuffer = new ArrayList<>(this.mCapacity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int append(ConnectivityMetricsEvent event) {
        synchronized (this.mLock) {
            this.mEventLog.append(event);
            int left = this.mCapacity - this.mBuffer.size();
            if (event == null) {
                return left;
            }
            if (isRateLimited(event)) {
                return -1;
            }
            if (left == 0) {
                this.mDropped++;
                return 0;
            }
            this.mBuffer.add(event);
            return left - 1;
        }
    }

    private boolean isRateLimited(ConnectivityMetricsEvent event) {
        TokenBucket tb = this.mBuckets.get(event.data.getClass());
        return (tb == null || tb.get()) ? false : true;
    }

    private String flushEncodedOutput() {
        ArrayList<ConnectivityMetricsEvent> events;
        int dropped;
        synchronized (this.mLock) {
            events = this.mBuffer;
            dropped = this.mDropped;
            initBuffer();
        }
        List<IpConnectivityLogClass.IpConnectivityEvent> protoEvents = IpConnectivityEventBuilder.toProto(events);
        this.mDefaultNetworkMetrics.flushEvents(protoEvents);
        if (this.mNetdListener != null) {
            this.mNetdListener.flushStatistics(protoEvents);
        }
        try {
            byte[] data = IpConnectivityEventBuilder.serialize(dropped, protoEvents);
            return Base64.encodeToString(data, 0);
        } catch (IOException e) {
            Log.e(TAG, "could not serialize events", e);
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cmdFlush(PrintWriter pw) {
        pw.print(flushEncodedOutput());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cmdList(PrintWriter pw) {
        pw.println("metrics events:");
        List<ConnectivityMetricsEvent> events = getEvents();
        for (ConnectivityMetricsEvent ev : events) {
            pw.println(ev.toString());
        }
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        if (this.mNetdListener != null) {
            this.mNetdListener.list(pw);
        }
        pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        this.mDefaultNetworkMetrics.listEvents(pw);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cmdListAsProto(PrintWriter pw) {
        List<ConnectivityMetricsEvent> events = getEvents();
        for (IpConnectivityLogClass.IpConnectivityEvent ev : IpConnectivityEventBuilder.toProto(events)) {
            pw.print(ev.toString());
        }
        if (this.mNetdListener != null) {
            this.mNetdListener.listAsProtos(pw);
        }
        this.mDefaultNetworkMetrics.listEventsAsProto(pw);
    }

    private List<ConnectivityMetricsEvent> getEvents() {
        List<ConnectivityMetricsEvent> asList;
        synchronized (this.mLock) {
            asList = Arrays.asList((ConnectivityMetricsEvent[]) this.mEventLog.toArray());
        }
        return asList;
    }

    /* loaded from: classes.dex */
    public final class Impl extends IIpConnectivityMetrics.Stub {
        static final String CMD_DEFAULT = "";
        static final String CMD_FLUSH = "flush";
        static final String CMD_IPCLIENT = "ipclient";
        static final String CMD_LIST = "list";
        static final String CMD_PROTO = "proto";

        public Impl() {
        }

        public int logEvent(ConnectivityMetricsEvent event) {
            enforceConnectivityInternalPermission();
            return IpConnectivityMetrics.this.append(event);
        }

        /* JADX WARN: Code restructure failed: missing block: B:22:0x0042, code lost:
            if (r0.equals(com.android.server.connectivity.IpConnectivityMetrics.Impl.CMD_FLUSH) != false) goto L15;
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void dump(java.io.FileDescriptor r7, java.io.PrintWriter r8, java.lang.String[] r9) {
            /*
                r6 = this;
                r6.enforceDumpPermission()
                int r0 = r9.length
                r1 = 0
                if (r0 <= 0) goto La
                r0 = r9[r1]
                goto Lc
            La:
                java.lang.String r0 = ""
            Lc:
                r2 = -1
                int r3 = r0.hashCode()
                r4 = 3322014(0x32b09e, float:4.655133E-39)
                r5 = 1
                if (r3 == r4) goto L45
                r4 = 97532676(0x5d03b04, float:1.9581905E-35)
                if (r3 == r4) goto L3c
                r1 = 106940904(0x65fc9e8, float:4.2089976E-35)
                if (r3 == r1) goto L31
                r1 = 1864910770(0x6f2847b2, float:5.2080156E28)
                if (r3 == r1) goto L27
                goto L50
            L27:
                java.lang.String r1 = "ipclient"
                boolean r1 = r0.equals(r1)
                if (r1 == 0) goto L50
                r1 = 2
                goto L51
            L31:
                java.lang.String r1 = "proto"
                boolean r1 = r0.equals(r1)
                if (r1 == 0) goto L50
                r1 = r5
                goto L51
            L3c:
                java.lang.String r3 = "flush"
                boolean r3 = r0.equals(r3)
                if (r3 == 0) goto L50
                goto L51
            L45:
                java.lang.String r1 = "list"
                boolean r1 = r0.equals(r1)
                if (r1 == 0) goto L50
                r1 = 3
                goto L51
            L50:
                r1 = r2
            L51:
                r2 = 0
                switch(r1) {
                    case 0: goto L82;
                    case 1: goto L7c;
                    case 2: goto L69;
                    case 3: goto L63;
                    default: goto L55;
                }
            L55:
                com.android.server.connectivity.IpConnectivityMetrics r1 = com.android.server.connectivity.IpConnectivityMetrics.this
                com.android.server.connectivity.IpConnectivityMetrics.access$400(r1, r8)
                java.lang.String r1 = ""
                r8.println(r1)
                android.net.ip.IpClient.dumpAllLogs(r8, r2)
                return
            L63:
                com.android.server.connectivity.IpConnectivityMetrics r1 = com.android.server.connectivity.IpConnectivityMetrics.this
                com.android.server.connectivity.IpConnectivityMetrics.access$400(r1, r8)
                return
            L69:
                if (r9 == 0) goto L77
                int r1 = r9.length
                if (r1 <= r5) goto L77
                int r1 = r9.length
                java.lang.Object[] r1 = java.util.Arrays.copyOfRange(r9, r5, r1)
                r2 = r1
                java.lang.String[] r2 = (java.lang.String[]) r2
            L77:
                r1 = r2
                android.net.ip.IpClient.dumpAllLogs(r8, r1)
                return
            L7c:
                com.android.server.connectivity.IpConnectivityMetrics r1 = com.android.server.connectivity.IpConnectivityMetrics.this
                com.android.server.connectivity.IpConnectivityMetrics.access$300(r1, r8)
                return
            L82:
                com.android.server.connectivity.IpConnectivityMetrics r1 = com.android.server.connectivity.IpConnectivityMetrics.this
                com.android.server.connectivity.IpConnectivityMetrics.access$200(r1, r8)
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.connectivity.IpConnectivityMetrics.Impl.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
        }

        private void enforceConnectivityInternalPermission() {
            enforcePermission("android.permission.CONNECTIVITY_INTERNAL");
        }

        private void enforceDumpPermission() {
            enforcePermission("android.permission.DUMP");
        }

        private void enforcePermission(String what) {
            IpConnectivityMetrics.this.getContext().enforceCallingOrSelfPermission(what, "IpConnectivityMetrics");
        }

        private void enforceNetdEventListeningPermission() {
            int uid = Binder.getCallingUid();
            if (uid != 1000) {
                throw new SecurityException(String.format("Uid %d has no permission to listen for netd events.", Integer.valueOf(uid)));
            }
        }

        public boolean addNetdEventCallback(int callerType, INetdEventCallback callback) {
            enforceNetdEventListeningPermission();
            if (IpConnectivityMetrics.this.mNetdListener == null) {
                return false;
            }
            return IpConnectivityMetrics.this.mNetdListener.addNetdEventCallback(callerType, callback);
        }

        public boolean removeNetdEventCallback(int callerType) {
            enforceNetdEventListeningPermission();
            if (IpConnectivityMetrics.this.mNetdListener == null) {
                return true;
            }
            return IpConnectivityMetrics.this.mNetdListener.removeNetdEventCallback(callerType);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$static$0(Context ctx) {
        int size = Settings.Global.getInt(ctx.getContentResolver(), "connectivity_metrics_buffer_size", DEFAULT_BUFFER_SIZE);
        return size <= 0 ? DEFAULT_BUFFER_SIZE : Math.min(size, 20000);
    }

    private static ArrayMap<Class<?>, TokenBucket> makeRateLimitingBuckets() {
        ArrayMap<Class<?>, TokenBucket> map = new ArrayMap<>();
        map.put(ApfProgramEvent.class, new TokenBucket(60000, 50));
        return map;
    }

    /* loaded from: classes.dex */
    private class LoggerImpl implements Logger {
        private LoggerImpl() {
        }

        @Override // com.android.server.connectivity.IpConnectivityMetrics.Logger
        public DefaultNetworkMetrics defaultNetworkMetrics() {
            return IpConnectivityMetrics.this.mDefaultNetworkMetrics;
        }
    }
}
