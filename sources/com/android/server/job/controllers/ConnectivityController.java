package com.android.server.job.controllers;

import android.net.ConnectivityManager;
import android.net.INetworkPolicyListener;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.job.JobSchedulerService;
import com.xiaopeng.server.aftersales.AfterSalesDaemonEvent;
import java.util.function.Predicate;
/* loaded from: classes.dex */
public final class ConnectivityController extends StateController implements ConnectivityManager.OnNetworkActiveListener {
    private static final boolean DEBUG;
    private static final String TAG = "JobScheduler.Connectivity";
    private final ConnectivityManager mConnManager;
    private final INetworkPolicyListener mNetPolicyListener;
    private final NetworkPolicyManager mNetPolicyManager;
    private final ConnectivityManager.NetworkCallback mNetworkCallback;
    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mTrackedJobs;

    static {
        DEBUG = JobSchedulerService.DEBUG || Log.isLoggable(TAG, 3);
    }

    public ConnectivityController(JobSchedulerService service) {
        super(service);
        this.mTrackedJobs = new ArraySet<>();
        this.mNetworkCallback = new ConnectivityManager.NetworkCallback() { // from class: com.android.server.job.controllers.ConnectivityController.1
            @Override // android.net.ConnectivityManager.NetworkCallback
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (ConnectivityController.DEBUG) {
                    Slog.v(ConnectivityController.TAG, "onCapabilitiesChanged: " + network);
                }
                ConnectivityController.this.updateTrackedJobs(-1, network);
            }

            @Override // android.net.ConnectivityManager.NetworkCallback
            public void onLost(Network network) {
                if (ConnectivityController.DEBUG) {
                    Slog.v(ConnectivityController.TAG, "onLost: " + network);
                }
                ConnectivityController.this.updateTrackedJobs(-1, network);
            }
        };
        this.mNetPolicyListener = new NetworkPolicyManager.Listener() { // from class: com.android.server.job.controllers.ConnectivityController.2
            public void onUidRulesChanged(int uid, int uidRules) {
                if (ConnectivityController.DEBUG) {
                    Slog.v(ConnectivityController.TAG, "onUidRulesChanged: " + uid);
                }
                ConnectivityController.this.updateTrackedJobs(uid, null);
            }
        };
        this.mConnManager = (ConnectivityManager) this.mContext.getSystemService(ConnectivityManager.class);
        this.mNetPolicyManager = (NetworkPolicyManager) this.mContext.getSystemService(NetworkPolicyManager.class);
        NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        this.mConnManager.registerNetworkCallback(request, this.mNetworkCallback);
        this.mNetPolicyManager.registerListener(this.mNetPolicyListener);
    }

    @Override // com.android.server.job.controllers.StateController
    @GuardedBy("mLock")
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (jobStatus.hasConnectivityConstraint()) {
            updateConstraintsSatisfied(jobStatus);
            this.mTrackedJobs.add(jobStatus);
            jobStatus.setTrackingController(2);
        }
    }

    @Override // com.android.server.job.controllers.StateController
    @GuardedBy("mLock")
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob, boolean forUpdate) {
        if (jobStatus.clearTrackingController(2)) {
            this.mTrackedJobs.remove(jobStatus);
        }
    }

    private static boolean isInsane(JobStatus jobStatus, Network network, NetworkCapabilities capabilities, JobSchedulerService.Constants constants) {
        long estimatedBytes = jobStatus.getEstimatedNetworkBytes();
        if (estimatedBytes == -1) {
            return false;
        }
        long slowest = NetworkCapabilities.minBandwidth(capabilities.getLinkDownstreamBandwidthKbps(), capabilities.getLinkUpstreamBandwidthKbps());
        if (slowest == 0) {
            return false;
        }
        long estimatedMillis = (1000 * estimatedBytes) / ((1024 * slowest) / 8);
        if (estimatedMillis > 600000) {
            Slog.w(TAG, "Estimated " + estimatedBytes + " bytes over " + slowest + " kbps network would take " + estimatedMillis + "ms; that's insane!");
            return true;
        }
        return false;
    }

    private static boolean isCongestionDelayed(JobStatus jobStatus, Network network, NetworkCapabilities capabilities, JobSchedulerService.Constants constants) {
        return !capabilities.hasCapability(20) && jobStatus.getFractionRunTime() < constants.CONN_CONGESTION_DELAY_FRAC;
    }

    private static boolean isStrictSatisfied(JobStatus jobStatus, Network network, NetworkCapabilities capabilities, JobSchedulerService.Constants constants) {
        return jobStatus.getJob().getRequiredNetwork().networkCapabilities.satisfiedByNetworkCapabilities(capabilities);
    }

    private static boolean isRelaxedSatisfied(JobStatus jobStatus, Network network, NetworkCapabilities capabilities, JobSchedulerService.Constants constants) {
        if (jobStatus.getJob().isPrefetch()) {
            NetworkCapabilities relaxed = new NetworkCapabilities(jobStatus.getJob().getRequiredNetwork().networkCapabilities).removeCapability(11);
            return relaxed.satisfiedByNetworkCapabilities(capabilities) && jobStatus.getFractionRunTime() > constants.CONN_PREFETCH_RELAX_FRAC;
        }
        return false;
    }

    @VisibleForTesting
    static boolean isSatisfied(JobStatus jobStatus, Network network, NetworkCapabilities capabilities, JobSchedulerService.Constants constants) {
        if (network == null || capabilities == null || isInsane(jobStatus, network, capabilities, constants) || isCongestionDelayed(jobStatus, network, capabilities, constants)) {
            return false;
        }
        return isStrictSatisfied(jobStatus, network, capabilities, constants) || isRelaxedSatisfied(jobStatus, network, capabilities, constants);
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus) {
        Network network = this.mConnManager.getActiveNetworkForUid(jobStatus.getSourceUid());
        NetworkCapabilities capabilities = this.mConnManager.getNetworkCapabilities(network);
        return updateConstraintsSatisfied(jobStatus, network, capabilities);
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus, Network network, NetworkCapabilities capabilities) {
        boolean z = true;
        boolean ignoreBlocked = (jobStatus.getFlags() & 1) != 0;
        NetworkInfo info = this.mConnManager.getNetworkInfoForUid(network, jobStatus.getSourceUid(), ignoreBlocked);
        boolean connected = info != null && info.isConnected();
        boolean satisfied = isSatisfied(jobStatus, network, capabilities, this.mConstants);
        if (!connected || !satisfied) {
            z = false;
        }
        boolean changed = jobStatus.setConnectivityConstraintSatisfied(z);
        jobStatus.network = network;
        if (DEBUG) {
            StringBuilder sb = new StringBuilder();
            sb.append("Connectivity ");
            sb.append(changed ? "CHANGED" : "unchanged");
            sb.append(" for ");
            sb.append(jobStatus);
            sb.append(": connected=");
            sb.append(connected);
            sb.append(" satisfied=");
            sb.append(satisfied);
            Slog.i(TAG, sb.toString());
        }
        return changed;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:13:0x0036 A[Catch: all -> 0x008a, TryCatch #0 {, blocks: (B:4:0x0009, B:6:0x001e, B:13:0x0036, B:15:0x003e, B:17:0x004a, B:22:0x0053, B:26:0x0060, B:27:0x0063, B:29:0x006b, B:31:0x0077, B:32:0x007d, B:34:0x0083, B:35:0x0088), top: B:40:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:26:0x0060 A[Catch: all -> 0x008a, TryCatch #0 {, blocks: (B:4:0x0009, B:6:0x001e, B:13:0x0036, B:15:0x003e, B:17:0x004a, B:22:0x0053, B:26:0x0060, B:27:0x0063, B:29:0x006b, B:31:0x0077, B:32:0x007d, B:34:0x0083, B:35:0x0088), top: B:40:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:29:0x006b A[Catch: all -> 0x008a, TryCatch #0 {, blocks: (B:4:0x0009, B:6:0x001e, B:13:0x0036, B:15:0x003e, B:17:0x004a, B:22:0x0053, B:26:0x0060, B:27:0x0063, B:29:0x006b, B:31:0x0077, B:32:0x007d, B:34:0x0083, B:35:0x0088), top: B:40:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:30:0x0075  */
    /* JADX WARN: Removed duplicated region for block: B:44:0x007d A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void updateTrackedJobs(int r19, android.net.Network r20) {
        /*
            r18 = this;
            r1 = r18
            r2 = r19
            r3 = r20
            java.lang.Object r4 = r1.mLock
            monitor-enter(r4)
            android.util.SparseArray r0 = new android.util.SparseArray     // Catch: java.lang.Throwable -> L8a
            r0.<init>()     // Catch: java.lang.Throwable -> L8a
            android.util.SparseArray r5 = new android.util.SparseArray     // Catch: java.lang.Throwable -> L8a
            r5.<init>()     // Catch: java.lang.Throwable -> L8a
            r6 = 0
            android.util.ArraySet<com.android.server.job.controllers.JobStatus> r7 = r1.mTrackedJobs     // Catch: java.lang.Throwable -> L8a
            int r7 = r7.size()     // Catch: java.lang.Throwable -> L8a
            r8 = 1
            int r7 = r7 - r8
        L1c:
            if (r7 < 0) goto L81
            android.util.ArraySet<com.android.server.job.controllers.JobStatus> r9 = r1.mTrackedJobs     // Catch: java.lang.Throwable -> L8a
            java.lang.Object r9 = r9.valueAt(r7)     // Catch: java.lang.Throwable -> L8a
            com.android.server.job.controllers.JobStatus r9 = (com.android.server.job.controllers.JobStatus) r9     // Catch: java.lang.Throwable -> L8a
            int r10 = r9.getSourceUid()     // Catch: java.lang.Throwable -> L8a
            r11 = 0
            r12 = -1
            if (r2 == r12) goto L33
            if (r2 != r10) goto L31
            goto L33
        L31:
            r13 = r11
            goto L34
        L33:
            r13 = r8
        L34:
            if (r13 == 0) goto L7d
            java.lang.Object r14 = r0.get(r10)     // Catch: java.lang.Throwable -> L8a
            android.net.Network r14 = (android.net.Network) r14     // Catch: java.lang.Throwable -> L8a
            if (r14 != 0) goto L48
            android.net.ConnectivityManager r15 = r1.mConnManager     // Catch: java.lang.Throwable -> L8a
            android.net.Network r15 = r15.getActiveNetworkForUid(r10)     // Catch: java.lang.Throwable -> L8a
            r14 = r15
            r0.put(r10, r14)     // Catch: java.lang.Throwable -> L8a
        L48:
            if (r3 == 0) goto L52
            boolean r15 = java.util.Objects.equals(r3, r14)     // Catch: java.lang.Throwable -> L8a
            if (r15 == 0) goto L51
            goto L52
        L51:
            goto L53
        L52:
            r11 = r8
        L53:
            android.net.Network r15 = r9.network     // Catch: java.lang.Throwable -> L8a
            boolean r15 = java.util.Objects.equals(r15, r14)     // Catch: java.lang.Throwable -> L8a
            r15 = r15 ^ r8
            if (r11 != 0) goto L5e
            if (r15 == 0) goto L7d
        L5e:
            if (r14 == 0) goto L63
            int r12 = r14.netId     // Catch: java.lang.Throwable -> L8a
        L63:
            java.lang.Object r16 = r5.get(r12)     // Catch: java.lang.Throwable -> L8a
            android.net.NetworkCapabilities r16 = (android.net.NetworkCapabilities) r16     // Catch: java.lang.Throwable -> L8a
            if (r16 != 0) goto L75
            android.net.ConnectivityManager r8 = r1.mConnManager     // Catch: java.lang.Throwable -> L8a
            android.net.NetworkCapabilities r8 = r8.getNetworkCapabilities(r14)     // Catch: java.lang.Throwable -> L8a
            r5.put(r12, r8)     // Catch: java.lang.Throwable -> L8a
            goto L77
        L75:
            r8 = r16
        L77:
            boolean r16 = r1.updateConstraintsSatisfied(r9, r14, r8)     // Catch: java.lang.Throwable -> L8a
            r6 = r6 | r16
        L7d:
            int r7 = r7 + (-1)
            r8 = 1
            goto L1c
        L81:
            if (r6 == 0) goto L88
            com.android.server.job.StateChangedListener r7 = r1.mStateChangedListener     // Catch: java.lang.Throwable -> L8a
            r7.onControllerStateChanged()     // Catch: java.lang.Throwable -> L8a
        L88:
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L8a
            return
        L8a:
            r0 = move-exception
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L8a
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.job.controllers.ConnectivityController.updateTrackedJobs(int, android.net.Network):void");
    }

    @Override // android.net.ConnectivityManager.OnNetworkActiveListener
    public void onNetworkActive() {
        synchronized (this.mLock) {
            for (int i = this.mTrackedJobs.size() - 1; i >= 0; i--) {
                JobStatus js = this.mTrackedJobs.valueAt(i);
                if (js.isReady()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Running " + js + " due to network activity.");
                    }
                    this.mStateChangedListener.onRunJobNow(js);
                }
            }
        }
    }

    @Override // com.android.server.job.controllers.StateController
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        for (int i = 0; i < this.mTrackedJobs.size(); i++) {
            JobStatus js = this.mTrackedJobs.valueAt(i);
            if (predicate.test(js)) {
                pw.print(AfterSalesDaemonEvent.XP_AFTERSALES_PARAM_SEPARATOR);
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                pw.print(": ");
                pw.print(js.getJob().getRequiredNetwork());
                pw.println();
            }
        }
    }

    @Override // com.android.server.job.controllers.StateController
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId, Predicate<JobStatus> predicate) {
        long token = proto.start(fieldId);
        long mToken = proto.start(1146756268035L);
        for (int i = 0; i < this.mTrackedJobs.size(); i++) {
            JobStatus js = this.mTrackedJobs.valueAt(i);
            if (predicate.test(js)) {
                long jsToken = proto.start(2246267895810L);
                js.writeToShortProto(proto, 1146756268033L);
                proto.write(1120986464258L, js.getSourceUid());
                NetworkRequest rn = js.getJob().getRequiredNetwork();
                if (rn != null) {
                    rn.writeToProto(proto, 1146756268035L);
                }
                proto.end(jsToken);
            }
        }
        proto.end(mToken);
        proto.end(token);
    }
}
