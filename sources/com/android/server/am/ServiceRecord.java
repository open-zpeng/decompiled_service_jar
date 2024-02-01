package com.android.server.am;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;
import com.android.internal.app.procstats.ServiceState;
import com.android.internal.os.BatteryStatsImpl;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.pm.DumpState;
import com.android.server.uri.NeededUriGrants;
import com.android.server.uri.UriPermissionOwner;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class ServiceRecord extends Binder implements ComponentName.WithComponentName {
    static final int MAX_DELIVERY_COUNT = 3;
    static final int MAX_DONE_EXECUTING_COUNT = 6;
    private static final String TAG = "ActivityManager";
    final ActivityManagerService ams;
    ProcessRecord app;
    ApplicationInfo appInfo;
    boolean callStart;
    int crashCount;
    final long createRealTime;
    boolean createdFromFg;
    final String definingPackageName;
    final int definingUid;
    boolean delayed;
    boolean delayedStop;
    long destroyTime;
    boolean destroying;
    boolean executeFg;
    int executeNesting;
    long executingStart;
    final boolean exported;
    boolean fgRequired;
    boolean fgWaiting;
    int foregroundId;
    Notification foregroundNoti;
    int foregroundServiceType;
    final ComponentName instanceName;
    final Intent.FilterComparison intent;
    boolean isForeground;
    ProcessRecord isolatedProc;
    long lastActivity;
    private int lastStartId;
    private ProcessRecord mAppForStartedWhitelistingBgActivityStarts;
    private boolean mHasBindingWhitelistingBgActivityStarts;
    private boolean mHasStartedWhitelistingBgActivityStarts;
    private Runnable mStartedWhitelistingBgActivityStartsCleanUp;
    final ComponentName name;
    long nextRestartTime;
    final String packageName;
    int pendingConnectionGroup;
    int pendingConnectionImportance;
    final String permission;
    final String processName;
    int restartCount;
    long restartDelay;
    long restartTime;
    ServiceState restartTracker;
    final Runnable restarter;
    final ServiceInfo serviceInfo;
    final String shortInstanceName;
    boolean startRequested;
    long startingBgTimeout;
    final BatteryStatsImpl.Uid.Pkg.Serv stats;
    boolean stopIfKilled;
    String stringName;
    int totalRestartCount;
    ServiceState tracker;
    final int userId;
    boolean whitelistManager;
    final ArrayMap<Intent.FilterComparison, IntentBindRecord> bindings = new ArrayMap<>();
    private final ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = new ArrayMap<>();
    final ArrayList<StartItem> deliveredStarts = new ArrayList<>();
    final ArrayList<StartItem> pendingStarts = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class StartItem {
        final int callingId;
        long deliveredTime;
        int deliveryCount;
        int doneExecutingCount;
        final int id;
        final Intent intent;
        final NeededUriGrants neededGrants;
        final ServiceRecord sr;
        String stringName;
        final boolean taskRemoved;
        UriPermissionOwner uriPermissions;

        /* JADX INFO: Access modifiers changed from: package-private */
        public StartItem(ServiceRecord _sr, boolean _taskRemoved, int _id, Intent _intent, NeededUriGrants _neededGrants, int _callingId) {
            this.sr = _sr;
            this.taskRemoved = _taskRemoved;
            this.id = _id;
            this.intent = _intent;
            this.neededGrants = _neededGrants;
            this.callingId = _callingId;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public UriPermissionOwner getUriPermissionsLocked() {
            if (this.uriPermissions == null) {
                this.uriPermissions = new UriPermissionOwner(this.sr.ams.mUgmInternal, this);
            }
            return this.uriPermissions;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void removeUriPermissionsLocked() {
            UriPermissionOwner uriPermissionOwner = this.uriPermissions;
            if (uriPermissionOwner != null) {
                uriPermissionOwner.removeUriPermissions();
                this.uriPermissions = null;
            }
        }

        public void writeToProto(ProtoOutputStream proto, long fieldId, long now) {
            long token = proto.start(fieldId);
            proto.write(1120986464257L, this.id);
            ProtoUtils.toDuration(proto, 1146756268034L, this.deliveredTime, now);
            proto.write(1120986464259L, this.deliveryCount);
            proto.write(1120986464260L, this.doneExecutingCount);
            Intent intent = this.intent;
            if (intent != null) {
                intent.writeToProto(proto, 1146756268037L, true, true, true, false);
            }
            NeededUriGrants neededUriGrants = this.neededGrants;
            if (neededUriGrants != null) {
                neededUriGrants.writeToProto(proto, 1146756268038L);
            }
            UriPermissionOwner uriPermissionOwner = this.uriPermissions;
            if (uriPermissionOwner != null) {
                uriPermissionOwner.writeToProto(proto, 1146756268039L);
            }
            proto.end(token);
        }

        public String toString() {
            String str = this.stringName;
            if (str != null) {
                return str;
            }
            StringBuilder sb = new StringBuilder(128);
            sb.append("ServiceRecord{");
            sb.append(Integer.toHexString(System.identityHashCode(this.sr)));
            sb.append(' ');
            sb.append(this.sr.shortInstanceName);
            sb.append(" StartItem ");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" id=");
            sb.append(this.id);
            sb.append('}');
            String sb2 = sb.toString();
            this.stringName = sb2;
            return sb2;
        }
    }

    void dumpStartList(PrintWriter pw, String prefix, List<StartItem> list, long now) {
        int N = list.size();
        for (int i = 0; i < N; i++) {
            StartItem si = list.get(i);
            pw.print(prefix);
            pw.print("#");
            pw.print(i);
            pw.print(" id=");
            pw.print(si.id);
            if (now != 0) {
                pw.print(" dur=");
                TimeUtils.formatDuration(si.deliveredTime, now, pw);
            }
            if (si.deliveryCount != 0) {
                pw.print(" dc=");
                pw.print(si.deliveryCount);
            }
            if (si.doneExecutingCount != 0) {
                pw.print(" dxc=");
                pw.print(si.doneExecutingCount);
            }
            pw.println("");
            pw.print(prefix);
            pw.print("  intent=");
            if (si.intent != null) {
                pw.println(si.intent.toString());
            } else {
                pw.println("null");
            }
            if (si.neededGrants != null) {
                pw.print(prefix);
                pw.print("  neededGrants=");
                pw.println(si.neededGrants);
            }
            if (si.uriPermissions != null) {
                si.uriPermissions.dump(pw, prefix);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1138166333441L, this.shortInstanceName);
        proto.write(1133871366146L, this.app != null);
        ProcessRecord processRecord = this.app;
        if (processRecord != null) {
            proto.write(1120986464259L, processRecord.pid);
        }
        Intent.FilterComparison filterComparison = this.intent;
        if (filterComparison != null) {
            filterComparison.getIntent().writeToProto(proto, 1146756268036L, false, true, false, true);
        }
        proto.write(1138166333445L, this.packageName);
        proto.write(1138166333446L, this.processName);
        proto.write(1138166333447L, this.permission);
        long now = SystemClock.uptimeMillis();
        long nowReal = SystemClock.elapsedRealtime();
        if (this.appInfo != null) {
            long appInfoToken = proto.start(1146756268040L);
            proto.write(1138166333441L, this.appInfo.sourceDir);
            if (!Objects.equals(this.appInfo.sourceDir, this.appInfo.publicSourceDir)) {
                proto.write(1138166333442L, this.appInfo.publicSourceDir);
            }
            proto.write(1138166333443L, this.appInfo.dataDir);
            proto.end(appInfoToken);
        }
        ProcessRecord processRecord2 = this.app;
        if (processRecord2 != null) {
            processRecord2.writeToProto(proto, 1146756268041L);
        }
        ProcessRecord processRecord3 = this.isolatedProc;
        if (processRecord3 != null) {
            processRecord3.writeToProto(proto, 1146756268042L);
        }
        proto.write(1133871366155L, this.whitelistManager);
        proto.write(1133871366156L, this.delayed);
        if (this.isForeground || this.foregroundId != 0) {
            long fgToken = proto.start(1146756268045L);
            proto.write(1120986464257L, this.foregroundId);
            this.foregroundNoti.writeToProto(proto, 1146756268034L);
            proto.end(fgToken);
        }
        ProtoUtils.toDuration(proto, 1146756268046L, this.createRealTime, nowReal);
        ProtoUtils.toDuration(proto, 1146756268047L, this.startingBgTimeout, now);
        ProtoUtils.toDuration(proto, 1146756268048L, this.lastActivity, now);
        ProtoUtils.toDuration(proto, 1146756268049L, this.restartTime, now);
        proto.write(1133871366162L, this.createdFromFg);
        if (this.startRequested || this.delayedStop || this.lastStartId != 0) {
            long startToken = proto.start(1146756268051L);
            proto.write(1133871366145L, this.startRequested);
            proto.write(1133871366146L, this.delayedStop);
            proto.write(1133871366147L, this.stopIfKilled);
            proto.write(1120986464261L, this.lastStartId);
            proto.end(startToken);
        }
        if (this.executeNesting != 0) {
            long executNestingToken = proto.start(1146756268052L);
            proto.write(1120986464257L, this.executeNesting);
            proto.write(1133871366146L, this.executeFg);
            ProtoUtils.toDuration(proto, 1146756268035L, this.executingStart, now);
            proto.end(executNestingToken);
        }
        if (this.destroying || this.destroyTime != 0) {
            ProtoUtils.toDuration(proto, 1146756268053L, this.destroyTime, now);
        }
        if (this.crashCount != 0 || this.restartCount != 0 || this.restartDelay != 0 || this.nextRestartTime != 0) {
            long crashToken = proto.start(1146756268054L);
            proto.write(1120986464257L, this.restartCount);
            ProtoUtils.toDuration(proto, 1146756268034L, this.restartDelay, now);
            ProtoUtils.toDuration(proto, 1146756268035L, this.nextRestartTime, now);
            proto.write(1120986464260L, this.crashCount);
            proto.end(crashToken);
        }
        if (this.deliveredStarts.size() > 0) {
            int N = this.deliveredStarts.size();
            for (int i = 0; i < N; i++) {
                this.deliveredStarts.get(i).writeToProto(proto, 2246267895831L, now);
            }
        }
        if (this.pendingStarts.size() > 0) {
            int N2 = this.pendingStarts.size();
            for (int i2 = 0; i2 < N2; i2++) {
                this.pendingStarts.get(i2).writeToProto(proto, 2246267895832L, now);
            }
        }
        if (this.bindings.size() > 0) {
            int N3 = this.bindings.size();
            for (int i3 = 0; i3 < N3; i3++) {
                IntentBindRecord b = this.bindings.valueAt(i3);
                b.writeToProto(proto, 2246267895833L);
            }
        }
        if (this.connections.size() > 0) {
            int N4 = this.connections.size();
            for (int conni = 0; conni < N4; conni++) {
                ArrayList<ConnectionRecord> c = this.connections.valueAt(conni);
                for (int i4 = 0; i4 < c.size(); i4++) {
                    c.get(i4).writeToProto(proto, 2246267895834L);
                }
            }
        }
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("intent={");
        pw.print(this.intent.getIntent().toShortString(false, true, false, true));
        pw.println('}');
        pw.print(prefix);
        pw.print("packageName=");
        pw.println(this.packageName);
        pw.print(prefix);
        pw.print("processName=");
        pw.println(this.processName);
        if (this.permission != null) {
            pw.print(prefix);
            pw.print("permission=");
            pw.println(this.permission);
        }
        long now = SystemClock.uptimeMillis();
        long nowReal = SystemClock.elapsedRealtime();
        if (this.appInfo != null) {
            pw.print(prefix);
            pw.print("baseDir=");
            pw.println(this.appInfo.sourceDir);
            if (!Objects.equals(this.appInfo.sourceDir, this.appInfo.publicSourceDir)) {
                pw.print(prefix);
                pw.print("resDir=");
                pw.println(this.appInfo.publicSourceDir);
            }
            pw.print(prefix);
            pw.print("dataDir=");
            pw.println(this.appInfo.dataDir);
        }
        pw.print(prefix);
        pw.print("app=");
        pw.println(this.app);
        if (this.isolatedProc != null) {
            pw.print(prefix);
            pw.print("isolatedProc=");
            pw.println(this.isolatedProc);
        }
        if (this.whitelistManager) {
            pw.print(prefix);
            pw.print("whitelistManager=");
            pw.println(this.whitelistManager);
        }
        if (this.mHasBindingWhitelistingBgActivityStarts) {
            pw.print(prefix);
            pw.print("hasBindingWhitelistingBgActivityStarts=");
            pw.println(this.mHasBindingWhitelistingBgActivityStarts);
        }
        if (this.mHasStartedWhitelistingBgActivityStarts) {
            pw.print(prefix);
            pw.print("hasStartedWhitelistingBgActivityStarts=");
            pw.println(this.mHasStartedWhitelistingBgActivityStarts);
        }
        if (this.delayed) {
            pw.print(prefix);
            pw.print("delayed=");
            pw.println(this.delayed);
        }
        if (this.isForeground || this.foregroundId != 0) {
            pw.print(prefix);
            pw.print("isForeground=");
            pw.print(this.isForeground);
            pw.print(" foregroundId=");
            pw.print(this.foregroundId);
            pw.print(" foregroundNoti=");
            pw.println(this.foregroundNoti);
        }
        pw.print(prefix);
        pw.print("createTime=");
        TimeUtils.formatDuration(this.createRealTime, nowReal, pw);
        pw.print(" startingBgTimeout=");
        TimeUtils.formatDuration(this.startingBgTimeout, now, pw);
        pw.println();
        pw.print(prefix);
        pw.print("lastActivity=");
        TimeUtils.formatDuration(this.lastActivity, now, pw);
        pw.print(" restartTime=");
        TimeUtils.formatDuration(this.restartTime, now, pw);
        pw.print(" createdFromFg=");
        pw.println(this.createdFromFg);
        if (this.pendingConnectionGroup != 0) {
            pw.print(prefix);
            pw.print(" pendingConnectionGroup=");
            pw.print(this.pendingConnectionGroup);
            pw.print(" Importance=");
            pw.println(this.pendingConnectionImportance);
        }
        if (this.startRequested || this.delayedStop || this.lastStartId != 0) {
            pw.print(prefix);
            pw.print("startRequested=");
            pw.print(this.startRequested);
            pw.print(" delayedStop=");
            pw.print(this.delayedStop);
            pw.print(" stopIfKilled=");
            pw.print(this.stopIfKilled);
            pw.print(" callStart=");
            pw.print(this.callStart);
            pw.print(" lastStartId=");
            pw.println(this.lastStartId);
        }
        if (this.executeNesting != 0) {
            pw.print(prefix);
            pw.print("executeNesting=");
            pw.print(this.executeNesting);
            pw.print(" executeFg=");
            pw.print(this.executeFg);
            pw.print(" executingStart=");
            TimeUtils.formatDuration(this.executingStart, now, pw);
            pw.println();
        }
        if (this.destroying || this.destroyTime != 0) {
            pw.print(prefix);
            pw.print("destroying=");
            pw.print(this.destroying);
            pw.print(" destroyTime=");
            TimeUtils.formatDuration(this.destroyTime, now, pw);
            pw.println();
        }
        if (this.crashCount != 0 || this.restartCount != 0 || this.restartDelay != 0 || this.nextRestartTime != 0) {
            pw.print(prefix);
            pw.print("restartCount=");
            pw.print(this.restartCount);
            pw.print(" restartDelay=");
            TimeUtils.formatDuration(this.restartDelay, now, pw);
            pw.print(" nextRestartTime=");
            TimeUtils.formatDuration(this.nextRestartTime, now, pw);
            pw.print(" crashCount=");
            pw.println(this.crashCount);
        }
        if (this.deliveredStarts.size() > 0) {
            pw.print(prefix);
            pw.println("Delivered Starts:");
            dumpStartList(pw, prefix, this.deliveredStarts, now);
        }
        if (this.pendingStarts.size() > 0) {
            pw.print(prefix);
            pw.println("Pending Starts:");
            dumpStartList(pw, prefix, this.pendingStarts, 0L);
        }
        if (this.bindings.size() > 0) {
            pw.print(prefix);
            pw.println("Bindings:");
            for (int i = 0; i < this.bindings.size(); i++) {
                IntentBindRecord b = this.bindings.valueAt(i);
                pw.print(prefix);
                pw.print("* IntentBindRecord{");
                pw.print(Integer.toHexString(System.identityHashCode(b)));
                if ((b.collectFlags() & 1) != 0) {
                    pw.append(" CREATE");
                }
                pw.println("}:");
                b.dumpInService(pw, prefix + "  ");
            }
        }
        if (this.connections.size() > 0) {
            pw.print(prefix);
            pw.println("All Connections:");
            for (int conni = 0; conni < this.connections.size(); conni++) {
                ArrayList<ConnectionRecord> c = this.connections.valueAt(conni);
                for (int i2 = 0; i2 < c.size(); i2++) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.println(c.get(i2));
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ServiceRecord(ActivityManagerService ams, BatteryStatsImpl.Uid.Pkg.Serv servStats, ComponentName name, ComponentName instanceName, String definingPackageName, int definingUid, Intent.FilterComparison intent, ServiceInfo sInfo, boolean callerIsFg, Runnable restarter) {
        this.ams = ams;
        this.stats = servStats;
        this.name = name;
        this.instanceName = instanceName;
        this.shortInstanceName = instanceName.flattenToShortString();
        this.definingPackageName = definingPackageName;
        this.definingUid = definingUid;
        this.intent = intent;
        this.serviceInfo = sInfo;
        this.appInfo = sInfo.applicationInfo;
        this.packageName = sInfo.applicationInfo.packageName;
        if ((sInfo.flags & 2) != 0) {
            this.processName = sInfo.processName + ":" + instanceName.getClassName();
        } else {
            this.processName = sInfo.processName;
        }
        this.permission = sInfo.permission;
        this.exported = sInfo.exported;
        this.restarter = restarter;
        this.createRealTime = SystemClock.elapsedRealtime();
        this.lastActivity = SystemClock.uptimeMillis();
        this.userId = UserHandle.getUserId(this.appInfo.uid);
        this.createdFromFg = callerIsFg;
    }

    public ServiceState getTracker() {
        ServiceState serviceState = this.tracker;
        if (serviceState != null) {
            return serviceState;
        }
        if ((this.serviceInfo.applicationInfo.flags & 8) == 0) {
            this.tracker = this.ams.mProcessStats.getServiceStateLocked(this.serviceInfo.packageName, this.serviceInfo.applicationInfo.uid, this.serviceInfo.applicationInfo.longVersionCode, this.serviceInfo.processName, this.serviceInfo.name);
            this.tracker.applyNewOwner(this);
        }
        return this.tracker;
    }

    public void forceClearTracker() {
        ServiceState serviceState = this.tracker;
        if (serviceState != null) {
            serviceState.clearCurrentOwner(this, true);
            this.tracker = null;
        }
    }

    public void makeRestarting(int memFactor, long now) {
        if (this.restartTracker == null) {
            if ((this.serviceInfo.applicationInfo.flags & 8) == 0) {
                this.restartTracker = this.ams.mProcessStats.getServiceStateLocked(this.serviceInfo.packageName, this.serviceInfo.applicationInfo.uid, this.serviceInfo.applicationInfo.longVersionCode, this.serviceInfo.processName, this.serviceInfo.name);
            }
            if (this.restartTracker == null) {
                return;
            }
        }
        this.restartTracker.setRestarting(true, memFactor, now);
    }

    public void setProcess(ProcessRecord _proc) {
        if (_proc != null) {
            ProcessRecord processRecord = this.mAppForStartedWhitelistingBgActivityStarts;
            if (processRecord != null && processRecord != _proc) {
                processRecord.removeAllowBackgroundActivityStartsToken(this);
                this.ams.mHandler.removeCallbacks(this.mStartedWhitelistingBgActivityStartsCleanUp);
            }
            this.mAppForStartedWhitelistingBgActivityStarts = this.mHasStartedWhitelistingBgActivityStarts ? _proc : null;
            if (this.mHasStartedWhitelistingBgActivityStarts || this.mHasBindingWhitelistingBgActivityStarts) {
                _proc.addAllowBackgroundActivityStartsToken(this);
            } else {
                _proc.removeAllowBackgroundActivityStartsToken(this);
            }
        }
        ProcessRecord processRecord2 = this.app;
        if (processRecord2 != null && processRecord2 != _proc) {
            if (!this.mHasStartedWhitelistingBgActivityStarts) {
                processRecord2.removeAllowBackgroundActivityStartsToken(this);
            }
            this.app.updateBoundClientUids();
        }
        this.app = _proc;
        int i = this.pendingConnectionGroup;
        if (i > 0 && _proc != null) {
            _proc.connectionService = this;
            _proc.connectionGroup = i;
            _proc.connectionImportance = this.pendingConnectionImportance;
            this.pendingConnectionImportance = 0;
            this.pendingConnectionGroup = 0;
        }
        for (int conni = this.connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> cr = this.connections.valueAt(conni);
            for (int i2 = 0; i2 < cr.size(); i2++) {
                ConnectionRecord conn = cr.get(i2);
                if (_proc != null) {
                    conn.startAssociationIfNeeded();
                } else {
                    conn.stopAssociation();
                }
            }
        }
        if (_proc != null) {
            _proc.updateBoundClientUids();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayMap<IBinder, ArrayList<ConnectionRecord>> getConnections() {
        return this.connections;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addConnection(IBinder binder, ConnectionRecord c) {
        ArrayList<ConnectionRecord> clist = this.connections.get(binder);
        if (clist == null) {
            clist = new ArrayList<>();
            this.connections.put(binder, clist);
        }
        clist.add(c);
        ProcessRecord processRecord = this.app;
        if (processRecord != null) {
            processRecord.addBoundClientUid(c.clientUid);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeConnection(IBinder binder) {
        this.connections.remove(binder);
        ProcessRecord processRecord = this.app;
        if (processRecord != null) {
            processRecord.updateBoundClientUids();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateHasBindingWhitelistingBgActivityStarts() {
        boolean hasWhitelistingBinding = false;
        for (int conni = this.connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> cr = this.connections.valueAt(conni);
            int i = 0;
            while (true) {
                if (i < cr.size()) {
                    if ((cr.get(i).flags & DumpState.DUMP_DEXOPT) == 0) {
                        i++;
                    } else {
                        hasWhitelistingBinding = true;
                        break;
                    }
                } else {
                    break;
                }
            }
            if (hasWhitelistingBinding) {
                break;
            }
        }
        setHasBindingWhitelistingBgActivityStarts(hasWhitelistingBinding);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setHasBindingWhitelistingBgActivityStarts(boolean newValue) {
        if (this.mHasBindingWhitelistingBgActivityStarts != newValue) {
            this.mHasBindingWhitelistingBgActivityStarts = newValue;
            updateParentProcessBgActivityStartsWhitelistingToken();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void whitelistBgActivityStartsOnServiceStart() {
        setHasStartedWhitelistingBgActivityStarts(true);
        ProcessRecord processRecord = this.app;
        if (processRecord != null) {
            this.mAppForStartedWhitelistingBgActivityStarts = processRecord;
        }
        if (this.mStartedWhitelistingBgActivityStartsCleanUp == null) {
            this.mStartedWhitelistingBgActivityStartsCleanUp = new Runnable() { // from class: com.android.server.am.-$$Lambda$ServiceRecord$LibDrdWU9t_vgStZ6swd0FNzHXc
                @Override // java.lang.Runnable
                public final void run() {
                    ServiceRecord.this.lambda$whitelistBgActivityStartsOnServiceStart$0$ServiceRecord();
                }
            };
        }
        this.ams.mHandler.removeCallbacks(this.mStartedWhitelistingBgActivityStartsCleanUp);
        this.ams.mHandler.postDelayed(this.mStartedWhitelistingBgActivityStartsCleanUp, this.ams.mConstants.SERVICE_BG_ACTIVITY_START_TIMEOUT);
    }

    public /* synthetic */ void lambda$whitelistBgActivityStartsOnServiceStart$0$ServiceRecord() {
        synchronized (this.ams) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (this.app == this.mAppForStartedWhitelistingBgActivityStarts) {
                    setHasStartedWhitelistingBgActivityStarts(false);
                } else if (this.mAppForStartedWhitelistingBgActivityStarts != null) {
                    this.mAppForStartedWhitelistingBgActivityStarts.removeAllowBackgroundActivityStartsToken(this);
                }
                this.mAppForStartedWhitelistingBgActivityStarts = null;
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    private void setHasStartedWhitelistingBgActivityStarts(boolean newValue) {
        if (this.mHasStartedWhitelistingBgActivityStarts != newValue) {
            this.mHasStartedWhitelistingBgActivityStarts = newValue;
            updateParentProcessBgActivityStartsWhitelistingToken();
        }
    }

    private void updateParentProcessBgActivityStartsWhitelistingToken() {
        ProcessRecord processRecord = this.app;
        if (processRecord == null) {
            return;
        }
        if (this.mHasStartedWhitelistingBgActivityStarts || this.mHasBindingWhitelistingBgActivityStarts) {
            this.app.addAllowBackgroundActivityStartsToken(this);
        } else {
            processRecord.removeAllowBackgroundActivityStartsToken(this);
        }
    }

    public AppBindRecord retrieveAppBindingLocked(Intent intent, ProcessRecord app) {
        Intent.FilterComparison filter = new Intent.FilterComparison(intent);
        IntentBindRecord i = this.bindings.get(filter);
        if (i == null) {
            i = new IntentBindRecord(this, filter);
            this.bindings.put(filter, i);
        }
        AppBindRecord a = i.apps.get(app);
        if (a != null) {
            return a;
        }
        AppBindRecord a2 = new AppBindRecord(this, i, app);
        i.apps.put(app, a2);
        return a2;
    }

    public boolean hasAutoCreateConnections() {
        for (int conni = this.connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> cr = this.connections.valueAt(conni);
            for (int i = 0; i < cr.size(); i++) {
                if ((cr.get(i).flags & 1) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public void updateWhitelistManager() {
        this.whitelistManager = false;
        for (int conni = this.connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> cr = this.connections.valueAt(conni);
            for (int i = 0; i < cr.size(); i++) {
                if ((cr.get(i).flags & 16777216) != 0) {
                    this.whitelistManager = true;
                    return;
                }
            }
        }
    }

    public void resetRestartCounter() {
        this.restartCount = 0;
        this.restartDelay = 0L;
        this.restartTime = 0L;
    }

    public StartItem findDeliveredStart(int id, boolean taskRemoved, boolean remove) {
        int N = this.deliveredStarts.size();
        for (int i = 0; i < N; i++) {
            StartItem si = this.deliveredStarts.get(i);
            if (si.id == id && si.taskRemoved == taskRemoved) {
                if (remove) {
                    this.deliveredStarts.remove(i);
                }
                return si;
            }
        }
        return null;
    }

    public int getLastStartId() {
        return this.lastStartId;
    }

    public int makeNextStartId() {
        this.lastStartId++;
        if (this.lastStartId < 1) {
            this.lastStartId = 1;
        }
        return this.lastStartId;
    }

    public void postNotification() {
        final int appUid = this.appInfo.uid;
        final int appPid = this.app.pid;
        if (this.foregroundId != 0 && this.foregroundNoti != null) {
            final String localPackageName = this.packageName;
            final int localForegroundId = this.foregroundId;
            final Notification _foregroundNoti = this.foregroundNoti;
            this.ams.mHandler.post(new Runnable() { // from class: com.android.server.am.ServiceRecord.1
                /* JADX WARN: Removed duplicated region for block: B:20:0x0113  */
                /* JADX WARN: Removed duplicated region for block: B:31:0x0158 A[Catch: RuntimeException -> 0x018c, TryCatch #3 {RuntimeException -> 0x018c, blocks: (B:18:0x0105, B:21:0x0115, B:27:0x0137, B:28:0x0151, B:29:0x0152, B:31:0x0158, B:32:0x0171, B:33:0x018b), top: B:45:0x0105 }] */
                /* JADX WARN: Removed duplicated region for block: B:32:0x0171 A[Catch: RuntimeException -> 0x018c, TryCatch #3 {RuntimeException -> 0x018c, blocks: (B:18:0x0105, B:21:0x0115, B:27:0x0137, B:28:0x0151, B:29:0x0152, B:31:0x0158, B:32:0x0171, B:33:0x018b), top: B:45:0x0105 }] */
                @Override // java.lang.Runnable
                /*
                    Code decompiled incorrectly, please refer to instructions dump.
                    To view partially-correct code enable 'Show inconsistent code' option in preferences
                */
                public void run() {
                    /*
                        Method dump skipped, instructions count: 423
                        To view this dump change 'Code comments level' option to 'DEBUG'
                    */
                    throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ServiceRecord.AnonymousClass1.run():void");
                }
            });
        }
    }

    public void cancelNotification() {
        final String localPackageName = this.packageName;
        final int localForegroundId = this.foregroundId;
        this.ams.mHandler.post(new Runnable() { // from class: com.android.server.am.ServiceRecord.2
            @Override // java.lang.Runnable
            public void run() {
                INotificationManager inm = NotificationManager.getService();
                if (inm == null) {
                    return;
                }
                try {
                    inm.cancelNotificationWithTag(localPackageName, (String) null, localForegroundId, ServiceRecord.this.userId);
                } catch (RemoteException e) {
                } catch (RuntimeException e2) {
                    Slog.w(ServiceRecord.TAG, "Error canceling notification for service", e2);
                }
            }
        });
    }

    public void stripForegroundServiceFlagFromNotification() {
        if (this.foregroundId == 0) {
            return;
        }
        final int localForegroundId = this.foregroundId;
        final int localUserId = this.userId;
        final String localPackageName = this.packageName;
        this.ams.mHandler.post(new Runnable() { // from class: com.android.server.am.ServiceRecord.3
            @Override // java.lang.Runnable
            public void run() {
                NotificationManagerInternal nmi = (NotificationManagerInternal) LocalServices.getService(NotificationManagerInternal.class);
                if (nmi == null) {
                    return;
                }
                nmi.removeForegroundServiceFlagFromNotification(localPackageName, localForegroundId, localUserId);
            }
        });
    }

    public void clearDeliveredStartsLocked() {
        for (int i = this.deliveredStarts.size() - 1; i >= 0; i--) {
            this.deliveredStarts.get(i).removeUriPermissionsLocked();
        }
        this.deliveredStarts.clear();
    }

    public String toString() {
        String str = this.stringName;
        if (str != null) {
            return str;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ServiceRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" u");
        sb.append(this.userId);
        sb.append(' ');
        sb.append(this.shortInstanceName);
        sb.append('}');
        String sb2 = sb.toString();
        this.stringName = sb2;
        return sb2;
    }

    public ComponentName getComponentName() {
        return this.name;
    }
}
