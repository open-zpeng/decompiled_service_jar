package com.android.server.am;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.ServiceStartArgs;
import android.content.ComponentName;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.internal.app.procstats.ServiceState;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.ServiceRecord;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.usage.AppStandbyController;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
/* loaded from: classes.dex */
public final class ActiveServices {
    private static final boolean DEBUG_DELAYED_SERVICE = ActivityManagerDebugConfig.DEBUG_SERVICE;
    private static final boolean DEBUG_DELAYED_STARTS = DEBUG_DELAYED_SERVICE;
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 7200000;
    private static final boolean LOG_SERVICE_START_STOP = false;
    static final int SERVICE_BACKGROUND_TIMEOUT = 200000;
    static final int SERVICE_START_FOREGROUND_TIMEOUT = 10000;
    static final int SERVICE_TIMEOUT = 20000;
    private static final boolean SHOW_DUNGEON_NOTIFICATION = false;
    private static final String TAG = "ActivityManager";
    private static final String TAG_MU = "ActivityManager_MU";
    private static final String TAG_SERVICE = "ActivityManager";
    private static final String TAG_SERVICE_EXECUTING = "ActivityManager";
    final ActivityManagerService mAm;
    String mLastAnrDump;
    final int mMaxStartingBackground;
    final SparseArray<ServiceMap> mServiceMap = new SparseArray<>();
    final ArrayMap<IBinder, ArrayList<ConnectionRecord>> mServiceConnections = new ArrayMap<>();
    final ArrayList<ServiceRecord> mPendingServices = new ArrayList<>();
    final ArrayList<ServiceRecord> mRestartingServices = new ArrayList<>();
    final ArrayList<ServiceRecord> mDestroyingServices = new ArrayList<>();
    private ArrayList<ServiceRecord> mTmpCollectionResults = null;
    boolean mScreenOn = true;
    final Runnable mLastAnrDumpClearer = new Runnable() { // from class: com.android.server.am.ActiveServices.1
        @Override // java.lang.Runnable
        public void run() {
            synchronized (ActiveServices.this.mAm) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActiveServices.this.mLastAnrDump = null;
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    };

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class ForcedStandbyListener extends AppStateTracker.Listener {
        ForcedStandbyListener() {
        }

        @Override // com.android.server.AppStateTracker.Listener
        public void stopForegroundServicesForUidPackage(int uid, String packageName) {
            synchronized (ActiveServices.this.mAm) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ServiceMap smap = ActiveServices.this.getServiceMapLocked(UserHandle.getUserId(uid));
                    int N = smap.mServicesByName.size();
                    ArrayList<ServiceRecord> toStop = new ArrayList<>(N);
                    for (int i = 0; i < N; i++) {
                        ServiceRecord r = smap.mServicesByName.valueAt(i);
                        if ((uid == r.serviceInfo.applicationInfo.uid || packageName.equals(r.serviceInfo.packageName)) && r.isForeground) {
                            toStop.add(r);
                        }
                    }
                    int numToStop = toStop.size();
                    if (numToStop > 0 && ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                        Slog.i("ActivityManager", "Package " + packageName + SliceClientPermissions.SliceAuthority.DELIMITER + uid + " entering FAS with foreground services");
                    }
                    for (int i2 = 0; i2 < numToStop; i2++) {
                        ServiceRecord r2 = toStop.get(i2);
                        if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                            Slog.i("ActivityManager", "  Stopping fg for service " + r2);
                        }
                        ActiveServices.this.setServiceForegroundInnerLocked(r2, 0, null, 0);
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static final class ActiveForegroundApp {
        boolean mAppOnTop;
        long mEndTime;
        long mHideTime;
        CharSequence mLabel;
        int mNumActive;
        String mPackageName;
        boolean mShownWhileScreenOn;
        boolean mShownWhileTop;
        long mStartTime;
        long mStartVisibleTime;
        int mUid;

        ActiveForegroundApp() {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ServiceMap extends Handler {
        static final int MSG_BG_START_TIMEOUT = 1;
        static final int MSG_UPDATE_FOREGROUND_APPS = 2;
        final ArrayMap<String, ActiveForegroundApp> mActiveForegroundApps;
        boolean mActiveForegroundAppsChanged;
        final ArrayList<ServiceRecord> mDelayedStartList;
        final ArrayMap<Intent.FilterComparison, ServiceRecord> mServicesByIntent;
        final ArrayMap<ComponentName, ServiceRecord> mServicesByName;
        final ArrayList<ServiceRecord> mStartingBackground;
        final int mUserId;

        ServiceMap(Looper looper, int userId) {
            super(looper);
            this.mServicesByName = new ArrayMap<>();
            this.mServicesByIntent = new ArrayMap<>();
            this.mDelayedStartList = new ArrayList<>();
            this.mStartingBackground = new ArrayList<>();
            this.mActiveForegroundApps = new ArrayMap<>();
            this.mUserId = userId;
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    synchronized (ActiveServices.this.mAm) {
                        try {
                            ActivityManagerService.boostPriorityForLockedSection();
                            rescheduleDelayedStartsLocked();
                        } catch (Throwable th) {
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            throw th;
                        }
                    }
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                case 2:
                    ActiveServices.this.updateForegroundApps(this);
                    return;
                default:
                    return;
            }
        }

        void ensureNotStartingBackgroundLocked(ServiceRecord r) {
            if (this.mStartingBackground.remove(r)) {
                if (ActiveServices.DEBUG_DELAYED_STARTS) {
                    Slog.v("ActivityManager", "No longer background starting: " + r);
                }
                rescheduleDelayedStartsLocked();
            }
            if (!this.mDelayedStartList.remove(r) || !ActiveServices.DEBUG_DELAYED_STARTS) {
                return;
            }
            Slog.v("ActivityManager", "No longer delaying start: " + r);
        }

        void rescheduleDelayedStartsLocked() {
            removeMessages(1);
            long now = SystemClock.uptimeMillis();
            int i = 0;
            int N = this.mStartingBackground.size();
            while (i < N) {
                ServiceRecord r = this.mStartingBackground.get(i);
                if (r.startingBgTimeout <= now) {
                    Slog.i("ActivityManager", "Waited long enough for: " + r);
                    this.mStartingBackground.remove(i);
                    N += -1;
                    i += -1;
                }
                i++;
            }
            while (this.mDelayedStartList.size() > 0 && this.mStartingBackground.size() < ActiveServices.this.mMaxStartingBackground) {
                ServiceRecord r2 = this.mDelayedStartList.remove(0);
                if (ActiveServices.DEBUG_DELAYED_STARTS) {
                    Slog.v("ActivityManager", "REM FR DELAY LIST (exec next): " + r2);
                }
                if (r2.pendingStarts.size() <= 0) {
                    Slog.w("ActivityManager", "**** NO PENDING STARTS! " + r2 + " startReq=" + r2.startRequested + " delayedStop=" + r2.delayedStop);
                }
                if (ActiveServices.DEBUG_DELAYED_SERVICE && this.mDelayedStartList.size() > 0) {
                    Slog.v("ActivityManager", "Remaining delayed list:");
                    for (int i2 = 0; i2 < this.mDelayedStartList.size(); i2++) {
                        Slog.v("ActivityManager", "  #" + i2 + ": " + this.mDelayedStartList.get(i2));
                    }
                }
                r2.delayed = false;
                try {
                    ActiveServices.this.startServiceInnerLocked(this, r2.pendingStarts.get(0).intent, r2, false, true);
                } catch (TransactionTooLargeException e) {
                }
            }
            if (this.mStartingBackground.size() > 0) {
                ServiceRecord next = this.mStartingBackground.get(0);
                long when = next.startingBgTimeout > now ? next.startingBgTimeout : now;
                if (ActiveServices.DEBUG_DELAYED_SERVICE) {
                    Slog.v("ActivityManager", "Top bg start is " + next + ", can delay others up to " + when);
                }
                Message msg = obtainMessage(1);
                sendMessageAtTime(msg, when);
            }
            if (this.mStartingBackground.size() < ActiveServices.this.mMaxStartingBackground) {
                ActiveServices.this.mAm.backgroundServicesFinishedLocked(this.mUserId);
            }
        }
    }

    public ActiveServices(ActivityManagerService service) {
        int i = 1;
        this.mAm = service;
        int maxBg = 0;
        try {
            maxBg = Integer.parseInt(SystemProperties.get("ro.config.max_starting_bg", "0"));
        } catch (RuntimeException e) {
        }
        if (maxBg > 0) {
            i = maxBg;
        } else if (!ActivityManager.isLowRamDeviceStatic()) {
            i = 8;
        }
        this.mMaxStartingBackground = i;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemServicesReady() {
        AppStateTracker ast = (AppStateTracker) LocalServices.getService(AppStateTracker.class);
        ast.addListener(new ForcedStandbyListener());
    }

    ServiceRecord getServiceByNameLocked(ComponentName name, int callingUser) {
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.v(TAG_MU, "getServiceByNameLocked(" + name + "), callingUser = " + callingUser);
        }
        return getServiceMapLocked(callingUser).mServicesByName.get(name);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasBackgroundServicesLocked(int callingUser) {
        ServiceMap smap = this.mServiceMap.get(callingUser);
        return smap != null && smap.mStartingBackground.size() >= this.mMaxStartingBackground;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ServiceMap getServiceMapLocked(int callingUser) {
        ServiceMap smap = this.mServiceMap.get(callingUser);
        if (smap == null) {
            ServiceMap smap2 = new ServiceMap(this.mAm.mHandler.getLooper(), callingUser);
            this.mServiceMap.put(callingUser, smap2);
            return smap2;
        }
        return smap;
    }

    ArrayMap<ComponentName, ServiceRecord> getServicesLocked(int callingUser) {
        return getServiceMapLocked(callingUser).mServicesByName;
    }

    private boolean appRestrictedAnyInBackground(int uid, String packageName) {
        int mode = this.mAm.mAppOpsService.checkOperation(70, uid, packageName);
        return mode != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:144:0x043c  */
    /* JADX WARN: Removed duplicated region for block: B:61:0x01d3  */
    /* JADX WARN: Removed duplicated region for block: B:99:0x02fe  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.content.ComponentName startServiceLocked(android.app.IApplicationThread r25, android.content.Intent r26, java.lang.String r27, int r28, int r29, boolean r30, java.lang.String r31, int r32) throws android.os.TransactionTooLargeException {
        /*
            Method dump skipped, instructions count: 1234
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActiveServices.startServiceLocked(android.app.IApplicationThread, android.content.Intent, java.lang.String, int, int, boolean, java.lang.String, int):android.content.ComponentName");
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(ServiceRecord r, String callingPackage, int callingUid, Intent service, boolean callerFg, final int userId) {
        if (this.mAm.getPackageManagerInternalLocked().isPermissionsReviewRequired(r.packageName, r.userId)) {
            if (!callerFg) {
                Slog.w("ActivityManager", "u" + r.userId + " Starting a service in package" + r.packageName + " requires a permissions review");
                return false;
            }
            IIntentSender target = this.mAm.getIntentSenderLocked(4, callingPackage, callingUid, userId, null, null, 0, new Intent[]{service}, new String[]{service.resolveType(this.mAm.mContext.getContentResolver())}, 1409286144, null);
            final Intent intent = new Intent("android.intent.action.REVIEW_PERMISSIONS");
            intent.addFlags(276824064);
            intent.putExtra("android.intent.extra.PACKAGE_NAME", r.packageName);
            intent.putExtra("android.intent.extra.INTENT", new IntentSender(target));
            if (ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW) {
                Slog.i("ActivityManager", "u" + r.userId + " Launching permission review for package " + r.packageName);
            }
            this.mAm.mHandler.post(new Runnable() { // from class: com.android.server.am.ActiveServices.2
                @Override // java.lang.Runnable
                public void run() {
                    ActiveServices.this.mAm.mContext.startActivityAsUser(intent, new UserHandle(userId));
                }
            });
            return false;
        }
        return true;
    }

    ComponentName startServiceInnerLocked(ServiceMap smap, Intent service, ServiceRecord r, boolean callerFg, boolean addToStarting) throws TransactionTooLargeException {
        ServiceState stracker = r.getTracker();
        if (stracker != null) {
            stracker.setStarted(true, this.mAm.mProcessStats.getMemFactorLocked(), r.lastActivity);
        }
        boolean z = false;
        r.callStart = false;
        synchronized (r.stats.getBatteryStats()) {
            r.stats.startRunningLocked();
        }
        String error = bringUpServiceLocked(r, service.getFlags(), callerFg, false, false);
        if (error != null) {
            return new ComponentName("!!", error);
        }
        if (r.startRequested && addToStarting) {
            if (smap.mStartingBackground.size() == 0) {
                z = true;
            }
            boolean first = z;
            smap.mStartingBackground.add(r);
            r.startingBgTimeout = SystemClock.uptimeMillis() + this.mAm.mConstants.BG_START_TIMEOUT;
            if (DEBUG_DELAYED_SERVICE) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.v("ActivityManager", "Starting background (first=" + first + "): " + r, here);
            } else if (DEBUG_DELAYED_STARTS) {
                Slog.v("ActivityManager", "Starting background (first=" + first + "): " + r);
            }
            if (first) {
                smap.rescheduleDelayedStartsLocked();
            }
        } else if (callerFg || r.fgRequired) {
            smap.ensureNotStartingBackgroundLocked(r);
        }
        return r.name;
    }

    private void stopServiceLocked(ServiceRecord service) {
        if (service.delayed) {
            if (DEBUG_DELAYED_STARTS) {
                Slog.v("ActivityManager", "Delaying stop of pending: " + service);
            }
            service.delayedStop = true;
            return;
        }
        synchronized (service.stats.getBatteryStats()) {
            service.stats.stopRunningLocked();
        }
        service.startRequested = false;
        if (service.tracker != null) {
            service.tracker.setStarted(false, this.mAm.mProcessStats.getMemFactorLocked(), SystemClock.uptimeMillis());
        }
        service.callStart = false;
        bringDownServiceIfNeededLocked(service, false, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int stopServiceLocked(IApplicationThread caller, Intent service, String resolvedType, int userId) {
        String str;
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            StringBuilder sb = new StringBuilder();
            sb.append("stopService: ");
            sb.append(service);
            sb.append(" type=");
            str = resolvedType;
            sb.append(str);
            Slog.v("ActivityManager", sb.toString());
        } else {
            str = resolvedType;
        }
        ProcessRecord callerApp = this.mAm.getRecordForAppLocked(caller);
        if (caller != null && callerApp == null) {
            throw new SecurityException("Unable to find app for caller " + caller + " (pid=" + Binder.getCallingPid() + ") when stopping service " + service);
        }
        ServiceLookupResult r = retrieveServiceLocked(service, str, null, Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, false, false);
        if (r != null) {
            if (r.record != null) {
                long origId = Binder.clearCallingIdentity();
                try {
                    stopServiceLocked(r.record);
                    Binder.restoreCallingIdentity(origId);
                    return 1;
                } catch (Throwable th) {
                    Binder.restoreCallingIdentity(origId);
                    throw th;
                }
            }
            return -1;
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stopInBackgroundLocked(int uid) {
        ServiceMap services = this.mServiceMap.get(UserHandle.getUserId(uid));
        ArrayList<ServiceRecord> stopping = null;
        if (services != null) {
            for (int i = services.mServicesByName.size() - 1; i >= 0; i--) {
                ServiceRecord service = services.mServicesByName.valueAt(i);
                if (service.appInfo.uid == uid && service.startRequested && this.mAm.getAppStartModeLocked(service.appInfo.uid, service.packageName, service.appInfo.targetSdkVersion, -1, false, false, false) != 0) {
                    if (stopping == null) {
                        stopping = new ArrayList<>();
                    }
                    String compName = service.name.flattenToShortString();
                    EventLogTags.writeAmStopIdleService(service.appInfo.uid, compName);
                    StringBuilder sb = new StringBuilder(64);
                    sb.append("Stopping service due to app idle: ");
                    UserHandle.formatUid(sb, service.appInfo.uid);
                    sb.append(" ");
                    TimeUtils.formatDuration(service.createRealTime - SystemClock.elapsedRealtime(), sb);
                    sb.append(" ");
                    sb.append(compName);
                    Slog.w("ActivityManager", sb.toString());
                    stopping.add(service);
                }
            }
            if (stopping != null) {
                for (int i2 = stopping.size() - 1; i2 >= 0; i2--) {
                    ServiceRecord service2 = stopping.get(i2);
                    service2.delayed = false;
                    services.ensureNotStartingBackgroundLocked(service2);
                    stopServiceLocked(service2);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IBinder peekServiceLocked(Intent service, String resolvedType, String callingPackage) {
        ServiceLookupResult r = retrieveServiceLocked(service, resolvedType, callingPackage, Binder.getCallingPid(), Binder.getCallingUid(), UserHandle.getCallingUserId(), false, false, false, false);
        if (r == null) {
            return null;
        }
        if (r.record == null) {
            throw new SecurityException("Permission Denial: Accessing service from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires " + r.permission);
        }
        IntentBindRecord ib = r.record.bindings.get(r.record.intent);
        if (ib == null) {
            return null;
        }
        IBinder ret = ib.binder;
        return ret;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean stopServiceTokenLocked(ComponentName className, IBinder token, int startId) {
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            Slog.v("ActivityManager", "stopServiceToken: " + className + " " + token + " startId=" + startId);
        }
        ServiceRecord r = findServiceLocked(className, token, UserHandle.getCallingUserId());
        if (r == null) {
            return false;
        }
        if (startId >= 0) {
            ServiceRecord.StartItem si = r.findDeliveredStart(startId, false, false);
            if (si != null) {
                while (r.deliveredStarts.size() > 0) {
                    ServiceRecord.StartItem cur = r.deliveredStarts.remove(0);
                    cur.removeUriPermissionsLocked();
                    if (cur == si) {
                        break;
                    }
                }
            }
            if (r.getLastStartId() != startId) {
                return false;
            }
            if (r.deliveredStarts.size() > 0) {
                Slog.w("ActivityManager", "stopServiceToken startId " + startId + " is last, but have " + r.deliveredStarts.size() + " remaining args");
            }
        }
        synchronized (r.stats.getBatteryStats()) {
            r.stats.stopRunningLocked();
        }
        r.startRequested = false;
        if (r.tracker != null) {
            r.tracker.setStarted(false, this.mAm.mProcessStats.getMemFactorLocked(), SystemClock.uptimeMillis());
        }
        r.callStart = false;
        long origId = Binder.clearCallingIdentity();
        bringDownServiceIfNeededLocked(r, false, false);
        Binder.restoreCallingIdentity(origId);
        return true;
    }

    public void setServiceForegroundLocked(ComponentName className, IBinder token, int id, Notification notification, int flags) {
        int userId = UserHandle.getCallingUserId();
        long origId = Binder.clearCallingIdentity();
        try {
            ServiceRecord r = findServiceLocked(className, token, userId);
            if (r != null) {
                setServiceForegroundInnerLocked(r, id, notification, flags);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean foregroundAppShownEnoughLocked(ActiveForegroundApp aa, long nowElapsed) {
        long j;
        if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
            Slog.d("ActivityManager", "Shown enough: pkg=" + aa.mPackageName + ", uid=" + aa.mUid);
        }
        aa.mHideTime = JobStatus.NO_LATEST_RUNTIME;
        if (aa.mShownWhileTop) {
            if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                Slog.d("ActivityManager", "YES - shown while on top");
                return true;
            }
            return true;
        } else if (this.mScreenOn || aa.mShownWhileScreenOn) {
            long j2 = aa.mStartVisibleTime;
            if (aa.mStartTime != aa.mStartVisibleTime) {
                j = this.mAm.mConstants.FGSERVICE_SCREEN_ON_AFTER_TIME;
            } else {
                j = this.mAm.mConstants.FGSERVICE_MIN_SHOWN_TIME;
            }
            long minTime = j2 + j;
            if (nowElapsed >= minTime) {
                if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                    Slog.d("ActivityManager", "YES - shown long enough with screen on");
                }
                return true;
            }
            long reportTime = this.mAm.mConstants.FGSERVICE_MIN_REPORT_TIME + nowElapsed;
            aa.mHideTime = reportTime > minTime ? reportTime : minTime;
            if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                Slog.d("ActivityManager", "NO -- wait " + (aa.mHideTime - nowElapsed) + " with screen on");
                return false;
            }
            return false;
        } else {
            long minTime2 = aa.mEndTime + this.mAm.mConstants.FGSERVICE_SCREEN_ON_BEFORE_TIME;
            if (nowElapsed >= minTime2) {
                if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                    Slog.d("ActivityManager", "YES - gone long enough with screen off");
                }
                return true;
            }
            aa.mHideTime = minTime2;
            if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                Slog.d("ActivityManager", "NO -- wait " + (aa.mHideTime - nowElapsed) + " with screen off");
                return false;
            }
            return false;
        }
    }

    void updateForegroundApps(ServiceMap smap) {
        ArrayList<ActiveForegroundApp> active = null;
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                long now = SystemClock.elapsedRealtime();
                long nextUpdateTime = JobStatus.NO_LATEST_RUNTIME;
                if (smap != null) {
                    if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                        Slog.d("ActivityManager", "Updating foreground apps for user " + smap.mUserId);
                    }
                    for (int i = smap.mActiveForegroundApps.size() - 1; i >= 0; i--) {
                        ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                        if (aa.mEndTime != 0) {
                            boolean canRemove = foregroundAppShownEnoughLocked(aa, now);
                            if (canRemove) {
                                smap.mActiveForegroundApps.removeAt(i);
                                smap.mActiveForegroundAppsChanged = true;
                            } else if (aa.mHideTime < nextUpdateTime) {
                                nextUpdateTime = aa.mHideTime;
                            }
                        }
                        boolean canRemove2 = aa.mAppOnTop;
                        if (!canRemove2) {
                            if (active == null) {
                                active = new ArrayList<>();
                            }
                            if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                                Slog.d("ActivityManager", "Adding active: pkg=" + aa.mPackageName + ", uid=" + aa.mUid);
                            }
                            active.add(aa);
                        }
                    }
                    smap.removeMessages(2);
                    if (nextUpdateTime < JobStatus.NO_LATEST_RUNTIME) {
                        if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                            Slog.d("ActivityManager", "Next update time in: " + (nextUpdateTime - now));
                        }
                        Message msg = smap.obtainMessage(2);
                        smap.sendMessageAtTime(msg, (SystemClock.uptimeMillis() + nextUpdateTime) - SystemClock.elapsedRealtime());
                    }
                }
                if (!smap.mActiveForegroundAppsChanged) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    return;
                }
                smap.mActiveForegroundAppsChanged = false;
                ActivityManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    private void requestUpdateActiveForegroundAppsLocked(ServiceMap smap, long timeElapsed) {
        Message msg = smap.obtainMessage(2);
        if (timeElapsed != 0) {
            smap.sendMessageAtTime(msg, (SystemClock.uptimeMillis() + timeElapsed) - SystemClock.elapsedRealtime());
            return;
        }
        smap.mActiveForegroundAppsChanged = true;
        smap.sendMessage(msg);
    }

    private void decActiveForegroundAppLocked(ServiceMap smap, ServiceRecord r) {
        ActiveForegroundApp active = smap.mActiveForegroundApps.get(r.packageName);
        if (active != null) {
            active.mNumActive--;
            if (active.mNumActive <= 0) {
                active.mEndTime = SystemClock.elapsedRealtime();
                if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                    Slog.d("ActivityManager", "Ended running of service");
                }
                if (foregroundAppShownEnoughLocked(active, active.mEndTime)) {
                    smap.mActiveForegroundApps.remove(r.packageName);
                    smap.mActiveForegroundAppsChanged = true;
                    requestUpdateActiveForegroundAppsLocked(smap, 0L);
                } else if (active.mHideTime < JobStatus.NO_LATEST_RUNTIME) {
                    requestUpdateActiveForegroundAppsLocked(smap, active.mHideTime);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateScreenStateLocked(boolean screenOn) {
        if (this.mScreenOn != screenOn) {
            this.mScreenOn = screenOn;
            if (screenOn) {
                long nowElapsed = SystemClock.elapsedRealtime();
                if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                    Slog.d("ActivityManager", "Screen turned on");
                }
                for (int i = this.mServiceMap.size() - 1; i >= 0; i--) {
                    ServiceMap smap = this.mServiceMap.valueAt(i);
                    long nextUpdateTime = JobStatus.NO_LATEST_RUNTIME;
                    boolean changed = false;
                    for (int j = smap.mActiveForegroundApps.size() - 1; j >= 0; j--) {
                        ActiveForegroundApp active = smap.mActiveForegroundApps.valueAt(j);
                        if (active.mEndTime == 0) {
                            if (!active.mShownWhileScreenOn) {
                                active.mShownWhileScreenOn = true;
                                active.mStartVisibleTime = nowElapsed;
                            }
                        } else {
                            if (!active.mShownWhileScreenOn && active.mStartVisibleTime == active.mStartTime) {
                                active.mStartVisibleTime = nowElapsed;
                                active.mEndTime = nowElapsed;
                            }
                            if (foregroundAppShownEnoughLocked(active, nowElapsed)) {
                                smap.mActiveForegroundApps.remove(active.mPackageName);
                                smap.mActiveForegroundAppsChanged = true;
                                changed = true;
                            } else if (active.mHideTime < nextUpdateTime) {
                                nextUpdateTime = active.mHideTime;
                            }
                        }
                    }
                    if (changed) {
                        requestUpdateActiveForegroundAppsLocked(smap, 0L);
                    } else if (nextUpdateTime < JobStatus.NO_LATEST_RUNTIME) {
                        requestUpdateActiveForegroundAppsLocked(smap, nextUpdateTime);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void foregroundServiceProcStateChangedLocked(UidRecord uidRec) {
        ServiceMap smap = this.mServiceMap.get(UserHandle.getUserId(uidRec.uid));
        if (smap != null) {
            boolean changed = false;
            for (int j = smap.mActiveForegroundApps.size() - 1; j >= 0; j--) {
                ActiveForegroundApp active = smap.mActiveForegroundApps.valueAt(j);
                if (active.mUid == uidRec.uid) {
                    if (uidRec.curProcState <= 2) {
                        if (!active.mAppOnTop) {
                            active.mAppOnTop = true;
                            changed = true;
                        }
                        active.mShownWhileTop = true;
                    } else if (active.mAppOnTop) {
                        active.mAppOnTop = false;
                        changed = true;
                    }
                }
            }
            if (changed) {
                requestUpdateActiveForegroundAppsLocked(smap, 0L);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public void setServiceForegroundInnerLocked(ServiceRecord r, int id, Notification notification, int flags) {
        if (id == 0) {
            if (r.isForeground) {
                ServiceMap smap = getServiceMapLocked(r.userId);
                if (smap != null) {
                    decActiveForegroundAppLocked(smap, r);
                }
                r.isForeground = false;
                this.mAm.mAppOpsService.finishOperation(AppOpsManager.getToken(this.mAm.mAppOpsService), 76, r.appInfo.uid, r.packageName);
                StatsLog.write(60, r.appInfo.uid, r.shortName, 2);
                if (r.app != null) {
                    this.mAm.updateLruProcessLocked(r.app, false, null);
                    updateServiceForegroundLocked(r.app, true);
                }
            }
            if ((flags & 1) != 0) {
                cancelForegroundNotificationLocked(r);
                r.foregroundId = 0;
                r.foregroundNoti = null;
            } else if (r.appInfo.targetSdkVersion >= 21) {
                r.stripForegroundServiceFlagFromNotification();
                if ((flags & 2) != 0) {
                    r.foregroundId = 0;
                    r.foregroundNoti = null;
                }
            }
        } else if (notification == null) {
            throw new IllegalArgumentException("null notification");
        } else {
            if (r.appInfo.isInstantApp()) {
                switch (this.mAm.mAppOpsService.checkOperation(68, r.appInfo.uid, r.appInfo.packageName)) {
                    case 0:
                        break;
                    case 1:
                        Slog.w("ActivityManager", "Instant app " + r.appInfo.packageName + " does not have permission to create foreground services, ignoring.");
                        return;
                    case 2:
                        throw new SecurityException("Instant app " + r.appInfo.packageName + " does not have permission to create foreground services");
                    default:
                        this.mAm.enforcePermission("android.permission.INSTANT_APP_FOREGROUND_SERVICE", r.app.pid, r.appInfo.uid, "startForeground");
                        break;
                }
            } else if (r.appInfo.targetSdkVersion >= 28) {
                this.mAm.enforcePermission("android.permission.FOREGROUND_SERVICE", r.app.pid, r.appInfo.uid, "startForeground");
            }
            boolean alreadyStartedOp = false;
            if (r.fgRequired) {
                if (ActivityManagerDebugConfig.DEBUG_SERVICE || ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                    Slog.i("ActivityManager", "Service called startForeground() as required: " + r);
                }
                r.fgRequired = false;
                r.fgWaiting = false;
                alreadyStartedOp = true;
                this.mAm.mHandler.removeMessages(66, r);
            }
            boolean ignoreForeground = false;
            try {
                int mode = this.mAm.mAppOpsService.checkOperation(76, r.appInfo.uid, r.packageName);
                if (mode != 3) {
                    switch (mode) {
                        case 0:
                            break;
                        default:
                            throw new SecurityException("Foreground not allowed as per app op");
                        case 1:
                            Slog.w("ActivityManager", "Service.startForeground() not allowed due to app op: service " + r.shortName);
                            ignoreForeground = true;
                            break;
                    }
                }
                if (!ignoreForeground && appRestrictedAnyInBackground(r.appInfo.uid, r.packageName)) {
                    Slog.w("ActivityManager", "Service.startForeground() not allowed due to bg restriction: service " + r.shortName);
                    updateServiceForegroundLocked(r.app, false);
                    ignoreForeground = true;
                }
                if (!ignoreForeground) {
                    if (r.foregroundId != id) {
                        cancelForegroundNotificationLocked(r);
                        r.foregroundId = id;
                    }
                    notification.flags |= 64;
                    r.foregroundNoti = notification;
                    if (!r.isForeground) {
                        ServiceMap smap2 = getServiceMapLocked(r.userId);
                        if (smap2 != null) {
                            ActiveForegroundApp active = smap2.mActiveForegroundApps.get(r.packageName);
                            if (active == null) {
                                active = new ActiveForegroundApp();
                                active.mPackageName = r.packageName;
                                active.mUid = r.appInfo.uid;
                                active.mShownWhileScreenOn = this.mScreenOn;
                                if (r.app != null) {
                                    boolean z = r.app.uidRecord.curProcState <= 2;
                                    active.mShownWhileTop = z;
                                    active.mAppOnTop = z;
                                }
                                long elapsedRealtime = SystemClock.elapsedRealtime();
                                active.mStartVisibleTime = elapsedRealtime;
                                active.mStartTime = elapsedRealtime;
                                smap2.mActiveForegroundApps.put(r.packageName, active);
                                requestUpdateActiveForegroundAppsLocked(smap2, 0L);
                            }
                            active.mNumActive++;
                        }
                        r.isForeground = true;
                        this.mAm.mAppOpsService.startOperation(AppOpsManager.getToken(this.mAm.mAppOpsService), 76, r.appInfo.uid, r.packageName, true);
                        StatsLog.write(60, r.appInfo.uid, r.shortName, 1);
                    }
                    r.postNotification();
                    if (r.app != null) {
                        updateServiceForegroundLocked(r.app, true);
                    }
                    getServiceMapLocked(r.userId).ensureNotStartingBackgroundLocked(r);
                    this.mAm.notifyPackageUse(r.serviceInfo.packageName, 2);
                } else if (ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE) {
                    Slog.d("ActivityManager", "Suppressing startForeground() for FAS " + r);
                }
            } finally {
                if (alreadyStartedOp) {
                    this.mAm.mAppOpsService.finishOperation(AppOpsManager.getToken(this.mAm.mAppOpsService), 76, r.appInfo.uid, r.packageName);
                }
            }
        }
    }

    private void cancelForegroundNotificationLocked(ServiceRecord r) {
        if (r.foregroundId != 0) {
            ServiceMap sm = getServiceMapLocked(r.userId);
            if (sm != null) {
                for (int i = sm.mServicesByName.size() - 1; i >= 0; i--) {
                    ServiceRecord other = sm.mServicesByName.valueAt(i);
                    if (other != r && other.foregroundId == r.foregroundId && other.packageName.equals(r.packageName)) {
                        return;
                    }
                }
            }
            r.cancelNotification();
        }
    }

    private void updateServiceForegroundLocked(ProcessRecord proc, boolean oomAdj) {
        boolean anyForeground = false;
        for (int i = proc.services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            if (sr.isForeground || sr.fgRequired) {
                anyForeground = true;
                break;
            }
        }
        this.mAm.updateProcessForegroundLocked(proc, anyForeground, oomAdj);
    }

    private void updateWhitelistManagerLocked(ProcessRecord proc) {
        proc.whitelistManager = false;
        for (int i = proc.services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            if (sr.whitelistManager) {
                proc.whitelistManager = true;
                return;
            }
        }
    }

    public void updateServiceConnectionActivitiesLocked(ProcessRecord clientProc) {
        ArraySet<ProcessRecord> updatedProcesses = null;
        for (int i = 0; i < clientProc.connections.size(); i++) {
            ConnectionRecord conn = clientProc.connections.valueAt(i);
            ProcessRecord proc = conn.binding.service.app;
            if (proc != null && proc != clientProc) {
                if (updatedProcesses == null) {
                    updatedProcesses = new ArraySet<>();
                } else if (updatedProcesses.contains(proc)) {
                }
                updatedProcesses.add(proc);
                updateServiceClientActivitiesLocked(proc, null, false);
            }
        }
    }

    private boolean updateServiceClientActivitiesLocked(ProcessRecord proc, ConnectionRecord modCr, boolean updateLru) {
        if (modCr != null && modCr.binding.client != null && modCr.binding.client.activities.size() <= 0) {
            return false;
        }
        boolean anyClientActivities = false;
        for (int i = proc.services.size() - 1; i >= 0 && !anyClientActivities; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            for (int conni = sr.connections.size() - 1; conni >= 0 && !anyClientActivities; conni--) {
                ArrayList<ConnectionRecord> clist = sr.connections.valueAt(conni);
                int cri = clist.size() - 1;
                while (true) {
                    if (cri >= 0) {
                        ConnectionRecord cr = clist.get(cri);
                        if (cr.binding.client != null && cr.binding.client != proc && cr.binding.client.activities.size() > 0) {
                            anyClientActivities = true;
                            break;
                        }
                        cri--;
                    }
                }
            }
        }
        if (anyClientActivities == proc.hasClientActivities) {
            return false;
        }
        proc.hasClientActivities = anyClientActivities;
        if (updateLru) {
            this.mAm.updateLruProcessLocked(proc, anyClientActivities, null);
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:115:0x0322 A[Catch: all -> 0x030f, TRY_ENTER, TRY_LEAVE, TryCatch #8 {all -> 0x030f, blocks: (B:105:0x02fe, B:107:0x0302, B:108:0x0309, B:115:0x0322, B:120:0x032e, B:123:0x0334, B:127:0x0345), top: B:217:0x02fe }] */
    /* JADX WARN: Removed duplicated region for block: B:119:0x032d  */
    /* JADX WARN: Removed duplicated region for block: B:123:0x0334 A[Catch: all -> 0x030f, TRY_ENTER, TRY_LEAVE, TryCatch #8 {all -> 0x030f, blocks: (B:105:0x02fe, B:107:0x0302, B:108:0x0309, B:115:0x0322, B:120:0x032e, B:123:0x0334, B:127:0x0345), top: B:217:0x02fe }] */
    /* JADX WARN: Removed duplicated region for block: B:127:0x0345 A[Catch: all -> 0x030f, TRY_ENTER, TRY_LEAVE, TryCatch #8 {all -> 0x030f, blocks: (B:105:0x02fe, B:107:0x0302, B:108:0x0309, B:115:0x0322, B:120:0x032e, B:123:0x0334, B:127:0x0345), top: B:217:0x02fe }] */
    /* JADX WARN: Removed duplicated region for block: B:142:0x038d  */
    /* JADX WARN: Removed duplicated region for block: B:145:0x039a  */
    /* JADX WARN: Removed duplicated region for block: B:161:0x03d1 A[Catch: all -> 0x0381, TRY_ENTER, TRY_LEAVE, TryCatch #0 {all -> 0x0381, blocks: (B:134:0x0375, B:147:0x039e, B:148:0x03a3, B:150:0x03a7, B:151:0x03ac, B:153:0x03b6, B:158:0x03c0, B:161:0x03d1, B:166:0x0421), top: B:202:0x0375 }] */
    /* JADX WARN: Removed duplicated region for block: B:180:0x048d A[Catch: all -> 0x04a2, TryCatch #1 {all -> 0x04a2, blocks: (B:176:0x047e, B:181:0x0493, B:178:0x0485, B:180:0x048d), top: B:204:0x0419 }] */
    /* JADX WARN: Removed duplicated region for block: B:209:0x0358 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:215:0x02dd A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:217:0x02fe A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public int bindServiceLocked(android.app.IApplicationThread r41, android.os.IBinder r42, android.content.Intent r43, java.lang.String r44, final android.app.IServiceConnection r45, int r46, java.lang.String r47, final int r48) throws android.os.TransactionTooLargeException {
        /*
            Method dump skipped, instructions count: 1303
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActiveServices.bindServiceLocked(android.app.IApplicationThread, android.os.IBinder, android.content.Intent, java.lang.String, android.app.IServiceConnection, int, java.lang.String, int):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v26, types: [int] */
    /* JADX WARN: Type inference failed for: r12v0, types: [int] */
    public void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
        long origId = Binder.clearCallingIdentity();
        try {
            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "PUBLISHING " + r + " " + intent + ": " + service);
            }
            if (r != null) {
                Intent.FilterComparison filter = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                boolean z = false;
                if (b != null && !b.received) {
                    b.binder = service;
                    b.requested = true;
                    b.received = true;
                    int conni = r.connections.size() - 1;
                    while (conni >= 0) {
                        ArrayList<ConnectionRecord> clist = r.connections.valueAt(conni);
                        boolean z2 = z;
                        while (true) {
                            ?? r12 = z2;
                            int i = clist.size();
                            if (r12 < i) {
                                ConnectionRecord c = clist.get(r12);
                                if (!filter.equals(c.binding.intent.intent)) {
                                    if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                                        Slog.v("ActivityManager", "Not publishing to: " + c);
                                    }
                                    if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                                        Slog.v("ActivityManager", "Bound intent: " + c.binding.intent.intent);
                                    }
                                    if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                                        Slog.v("ActivityManager", "Published intent: " + intent);
                                    }
                                } else {
                                    if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                                        Slog.v("ActivityManager", "Publishing to: " + c);
                                    }
                                    try {
                                        c.conn.connected(r.name, service, z);
                                    } catch (Exception e) {
                                        Slog.w("ActivityManager", "Failure sending service " + r.name + " to connection " + c.conn.asBinder() + " (in " + c.binding.client.processName + ")", e);
                                    }
                                }
                                z = false;
                                z2 = r12 + 1;
                            }
                        }
                        conni--;
                        z = false;
                    }
                }
                serviceDoneExecutingLocked(r, this.mDestroyingServices.contains(r), false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean unbindServiceLocked(IServiceConnection connection) {
        IBinder binder = connection.asBinder();
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            Slog.v("ActivityManager", "unbindService: conn=" + binder);
        }
        ArrayList<ConnectionRecord> clist = this.mServiceConnections.get(binder);
        if (clist == null) {
            Slog.w("ActivityManager", "Unbind failed: could not find connection for " + connection.asBinder());
            return false;
        }
        long origId = Binder.clearCallingIdentity();
        while (true) {
            try {
                boolean z = true;
                if (clist.size() > 0) {
                    ConnectionRecord r = clist.get(0);
                    removeConnectionLocked(r, null, null);
                    if (clist.size() > 0 && clist.get(0) == r) {
                        Slog.wtf("ActivityManager", "Connection " + r + " not removed for binder " + binder);
                        clist.remove(0);
                    }
                    if (r.binding.service.app != null) {
                        if (r.binding.service.app.whitelistManager) {
                            updateWhitelistManagerLocked(r.binding.service.app);
                        }
                        if ((r.flags & 134217728) != 0) {
                            r.binding.service.app.treatLikeActivity = true;
                            ActivityManagerService activityManagerService = this.mAm;
                            ProcessRecord processRecord = r.binding.service.app;
                            if (!r.binding.service.app.hasClientActivities && !r.binding.service.app.treatLikeActivity) {
                                z = false;
                            }
                            activityManagerService.updateLruProcessLocked(processRecord, z, null);
                        }
                        this.mAm.updateOomAdjLocked(r.binding.service.app, false);
                    }
                } else {
                    this.mAm.updateOomAdjLocked();
                    return true;
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unbindFinishedLocked(ServiceRecord r, Intent intent, boolean doRebind) {
        long origId = Binder.clearCallingIdentity();
        if (r != null) {
            try {
                Intent.FilterComparison filter = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("unbindFinished in ");
                    sb.append(r);
                    sb.append(" at ");
                    sb.append(b);
                    sb.append(": apps=");
                    sb.append(b != null ? b.apps.size() : 0);
                    Slog.v("ActivityManager", sb.toString());
                }
                boolean inDestroying = this.mDestroyingServices.contains(r);
                if (b != null) {
                    if (b.apps.size() > 0 && !inDestroying) {
                        boolean inFg = false;
                        for (int i = b.apps.size() - 1; i >= 0; i--) {
                            ProcessRecord client = b.apps.valueAt(i).client;
                            if (client != null && client.setSchedGroup != 0) {
                                inFg = true;
                                break;
                            }
                        }
                        try {
                            requestServiceBindingLocked(r, b, inFg, true);
                        } catch (TransactionTooLargeException e) {
                        }
                    } else {
                        b.doRebind = true;
                    }
                }
                serviceDoneExecutingLocked(r, inDestroying, false);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    private final ServiceRecord findServiceLocked(ComponentName name, IBinder token, int userId) {
        ServiceRecord r = getServiceByNameLocked(name, userId);
        if (r == token) {
            return r;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ServiceLookupResult {
        final String permission;
        final ServiceRecord record;

        ServiceLookupResult(ServiceRecord _record, String _permission) {
            this.record = _record;
            this.permission = _permission;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ServiceRestarter implements Runnable {
        private ServiceRecord mService;

        private ServiceRestarter() {
        }

        void setService(ServiceRecord service) {
            this.mService = service;
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (ActiveServices.this.mAm) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    ActiveServices.this.performServiceRestartLocked(this.mService);
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    }

    /* JADX WARN: Not initialized variable reg: 28, insn: 0x032d: MOVE  (r14 I:??[OBJECT, ARRAY]) = (r28 I:??[OBJECT, ARRAY] A[D('r' com.android.server.am.ServiceRecord)]), block:B:120:0x032d */
    private ServiceLookupResult retrieveServiceLocked(Intent service, String resolvedType, String callingPackage, int callingPid, int callingUid, int userId, boolean createIfNeeded, boolean callingFromFg, boolean isBindExternal, boolean allowInstant) {
        String str;
        int opCode;
        ServiceRecord r;
        ComponentName name;
        ServiceRecord r2 = null;
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            StringBuilder sb = new StringBuilder();
            sb.append("retrieveServiceLocked: ");
            sb.append(service);
            sb.append(" type=");
            str = resolvedType;
            sb.append(str);
            sb.append(" callingUid=");
            sb.append(callingUid);
            Slog.v("ActivityManager", sb.toString());
        } else {
            str = resolvedType;
        }
        int userId2 = this.mAm.mUserController.handleIncomingUser(callingPid, callingUid, userId, false, 1, "service", null);
        ServiceMap smap = getServiceMapLocked(userId2);
        ComponentName comp = service.getComponent();
        if (comp != null) {
            ServiceRecord r3 = smap.mServicesByName.get(comp);
            r2 = r3;
            if (ActivityManagerDebugConfig.DEBUG_SERVICE && r2 != null) {
                Slog.v("ActivityManager", "Retrieved by component: " + r2);
            }
        }
        if (r2 == null && !isBindExternal) {
            ServiceRecord r4 = smap.mServicesByIntent.get(new Intent.FilterComparison(service));
            r2 = r4;
            if (ActivityManagerDebugConfig.DEBUG_SERVICE && r2 != null) {
                Slog.v("ActivityManager", "Retrieved by intent: " + r2);
            }
        }
        if (r2 != null && (r2.serviceInfo.flags & 4) != 0 && !callingPackage.equals(r2.packageName)) {
            r2 = null;
            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "Whoops, can't use existing external service");
            }
        }
        ServiceRecord r5 = r2;
        int i = 0;
        if (r5 == null) {
            int flags = allowInstant ? 268436480 | DumpState.DUMP_VOLUMES : 268436480;
            try {
                try {
                    ResolveInfo rInfo = this.mAm.getPackageManagerInternalLocked().resolveService(service, str, flags, userId2, callingUid);
                    ServiceInfo sInfo = rInfo != null ? rInfo.serviceInfo : null;
                    if (sInfo == null) {
                        Slog.w("ActivityManager", "Unable to start service " + service + " U=" + userId2 + ": not found");
                        return null;
                    }
                    ComponentName name2 = new ComponentName(sInfo.applicationInfo.packageName, sInfo.name);
                    try {
                        if ((sInfo.flags & 4) != 0) {
                            if (!isBindExternal) {
                                throw new SecurityException("BIND_EXTERNAL_SERVICE required for " + name2);
                            } else if (!sInfo.exported) {
                                throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " + name2 + " is not exported");
                            } else if ((sInfo.flags & 2) == 0) {
                                throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " + name2 + " is not an isolatedProcess");
                            } else {
                                ApplicationInfo aInfo = AppGlobals.getPackageManager().getApplicationInfo(callingPackage, 1024, userId2);
                                if (aInfo == null) {
                                    throw new SecurityException("BIND_EXTERNAL_SERVICE failed, could not resolve client package " + callingPackage);
                                }
                                sInfo = new ServiceInfo(sInfo);
                                sInfo.applicationInfo = new ApplicationInfo(sInfo.applicationInfo);
                                sInfo.applicationInfo.packageName = aInfo.packageName;
                                sInfo.applicationInfo.uid = aInfo.uid;
                                name2 = new ComponentName(aInfo.packageName, name2.getClassName());
                                service.setComponent(name2);
                            }
                        } else if (isBindExternal) {
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " + name2 + " is not an externalService");
                        }
                        if (userId2 > 0) {
                            if (this.mAm.isSingleton(sInfo.processName, sInfo.applicationInfo, sInfo.name, sInfo.flags)) {
                                i = callingUid;
                                if (this.mAm.isValidSingletonCall(i, sInfo.applicationInfo.uid)) {
                                    userId2 = 0;
                                    smap = getServiceMapLocked(0);
                                }
                            } else {
                                i = callingUid;
                            }
                            sInfo = new ServiceInfo(sInfo);
                            sInfo.applicationInfo = this.mAm.getAppInfoForUser(sInfo.applicationInfo, userId2);
                        } else {
                            i = callingUid;
                        }
                        ServiceInfo sInfo2 = sInfo;
                        ServiceRecord r6 = smap.mServicesByName.get(name2);
                        try {
                            if (ActivityManagerDebugConfig.DEBUG_SERVICE && r6 != null) {
                                try {
                                    Slog.v("ActivityManager", "Retrieved via pm by intent: " + r6);
                                } catch (RemoteException e) {
                                    r5 = r6;
                                }
                            }
                            if (r6 == null && createIfNeeded) {
                                Intent.FilterComparison filter = new Intent.FilterComparison(service.cloneFilter());
                                ServiceRestarter res = new ServiceRestarter();
                                BatteryStatsImpl stats = this.mAm.mBatteryStatsService.getActiveStatistics();
                                try {
                                    synchronized (stats) {
                                        try {
                                            try {
                                            } catch (Throwable th) {
                                                th = th;
                                            }
                                            try {
                                                BatteryStatsImpl.Uid.Pkg.Serv ss = stats.getServiceStatsLocked(sInfo2.applicationInfo.uid, sInfo2.packageName, sInfo2.name);
                                                ServiceRecord r7 = new ServiceRecord(this.mAm, ss, name2, filter, sInfo2, callingFromFg, res);
                                                try {
                                                    res.setService(r7);
                                                    smap.mServicesByName.put(name2, r7);
                                                    smap.mServicesByIntent.put(filter, r7);
                                                    int i2 = this.mPendingServices.size() - 1;
                                                    while (i2 >= 0) {
                                                        ServiceRecord pr = this.mPendingServices.get(i2);
                                                        Intent.FilterComparison filter2 = filter;
                                                        ServiceRestarter res2 = res;
                                                        if (pr.serviceInfo.applicationInfo.uid == sInfo2.applicationInfo.uid && pr.name.equals(name2)) {
                                                            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                                                                StringBuilder sb2 = new StringBuilder();
                                                                name = name2;
                                                                sb2.append("Remove pending: ");
                                                                sb2.append(pr);
                                                                Slog.v("ActivityManager", sb2.toString());
                                                            } else {
                                                                name = name2;
                                                            }
                                                            this.mPendingServices.remove(i2);
                                                        } else {
                                                            name = name2;
                                                        }
                                                        i2--;
                                                        filter = filter2;
                                                        res = res2;
                                                        name2 = name;
                                                    }
                                                    if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                                                        Slog.v("ActivityManager", "Retrieve created new service: " + r7);
                                                    }
                                                    r5 = r7;
                                                } catch (RemoteException e2) {
                                                    r5 = r7;
                                                }
                                            } catch (Throwable th2) {
                                                th = th2;
                                                while (true) {
                                                    try {
                                                        break;
                                                    } catch (Throwable th3) {
                                                        th = th3;
                                                    }
                                                }
                                                throw th;
                                            }
                                        } catch (Throwable th4) {
                                            th = th4;
                                        }
                                    }
                                } catch (RemoteException e3) {
                                    r5 = r;
                                }
                            } else {
                                r5 = r6;
                            }
                        } catch (RemoteException e4) {
                            r5 = r6;
                        }
                    } catch (RemoteException e5) {
                    }
                } catch (RemoteException e6) {
                    i = callingUid;
                }
            } catch (RemoteException e7) {
                i = callingUid;
            }
        } else {
            i = callingUid;
        }
        if (r5 != null) {
            if (this.mAm.checkComponentPermission(r5.permission, callingPid, i, r5.appInfo.uid, r5.exported) == 0) {
                if (r5.permission == null || callingPackage == null || (opCode = AppOpsManager.permissionToOpCode(r5.permission)) == -1 || this.mAm.mAppOpsService.noteOperation(opCode, i, callingPackage) == 0) {
                    if (this.mAm.mIntentFirewall.checkService(r5.name, service, i, callingPid, resolvedType, r5.appInfo)) {
                        return new ServiceLookupResult(r5, null);
                    }
                    return null;
                }
                Slog.w("ActivityManager", "Appop Denial: Accessing service " + r5.name + " from pid=" + callingPid + ", uid=" + i + " requires appop " + AppOpsManager.opToName(opCode));
                return null;
            } else if (r5.exported) {
                Slog.w("ActivityManager", "Permission Denial: Accessing service " + r5.name + " from pid=" + callingPid + ", uid=" + i + " requires " + r5.permission);
                return new ServiceLookupResult(null, r5.permission);
            } else {
                Slog.w("ActivityManager", "Permission Denial: Accessing service " + r5.name + " from pid=" + callingPid + ", uid=" + i + " that is not exported from uid " + r5.appInfo.uid);
                return new ServiceLookupResult(null, "not exported from uid " + r5.appInfo.uid);
            }
        }
        return null;
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r, boolean fg, String why) {
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            Slog.v("ActivityManager", ">>> EXECUTING " + why + " of " + r + " in app " + r.app);
        } else if (ActivityManagerDebugConfig.DEBUG_SERVICE_EXECUTING) {
            Slog.v("ActivityManager", ">>> EXECUTING " + why + " of " + r.shortName);
        }
        boolean timeoutNeeded = true;
        if (this.mAm.mBootPhase < 600 && r.app != null && r.app.pid == Process.myPid()) {
            Slog.w("ActivityManager", "Too early to start/bind service in system_server: Phase=" + this.mAm.mBootPhase + " " + r.getComponentName());
            timeoutNeeded = false;
        }
        long now = SystemClock.uptimeMillis();
        if (r.executeNesting == 0) {
            r.executeFg = fg;
            ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setExecuting(true, this.mAm.mProcessStats.getMemFactorLocked(), now);
            }
            if (r.app != null) {
                r.app.executingServices.add(r);
                r.app.execServicesFg |= fg;
                if (timeoutNeeded && r.app.executingServices.size() == 1) {
                    scheduleServiceTimeoutLocked(r.app);
                }
            }
        } else if (r.app != null && fg && !r.app.execServicesFg) {
            r.app.execServicesFg = true;
            if (timeoutNeeded) {
                scheduleServiceTimeoutLocked(r.app);
            }
        }
        r.executeFg |= fg;
        r.executeNesting++;
        r.executingStart = now;
    }

    private final boolean requestServiceBindingLocked(ServiceRecord r, IntentBindRecord i, boolean execInFg, boolean rebind) throws TransactionTooLargeException {
        if (r.app == null || r.app.thread == null) {
            return false;
        }
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            Slog.d("ActivityManager", "requestBind " + i + ": requested=" + i.requested + " rebind=" + rebind);
        }
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r, execInFg, "bind");
                r.app.forceProcessStateUpTo(9);
                r.app.thread.scheduleBindService(r, i.intent.getIntent(), rebind, r.app.repProcState);
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (TransactionTooLargeException e) {
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    Slog.v("ActivityManager", "Crashed while binding " + r, e);
                }
                boolean inDestroying = this.mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
                throw e;
            } catch (RemoteException e2) {
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    Slog.v("ActivityManager", "Crashed while binding " + r);
                }
                boolean inDestroying2 = this.mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying2, inDestroying2);
                return false;
            }
        }
        return true;
    }

    private final boolean scheduleServiceRestartLocked(ServiceRecord r, boolean allowCancel) {
        boolean z;
        boolean canceled;
        long minDuration;
        boolean canceled2;
        ServiceMap smap;
        boolean canceled3 = false;
        if (this.mAm.isShuttingDownLocked()) {
            Slog.w("ActivityManager", "Not scheduling restart of crashed service " + r.shortName + " - system is shutting down");
            return false;
        }
        ServiceMap smap2 = getServiceMapLocked(r.userId);
        if (smap2.mServicesByName.get(r.name) != r) {
            ServiceRecord cur = smap2.mServicesByName.get(r.name);
            Slog.wtf("ActivityManager", "Attempting to schedule restart of " + r + " when found in map: " + cur);
            return false;
        }
        long now = SystemClock.uptimeMillis();
        int i = 3;
        if ((r.serviceInfo.applicationInfo.flags & 8) == 0) {
            long minDuration2 = this.mAm.mConstants.SERVICE_RESTART_DURATION;
            long resetTime = this.mAm.mConstants.SERVICE_RESET_RUN_DURATION;
            int N = r.deliveredStarts.size();
            if (N > 0) {
                int i2 = N - 1;
                while (true) {
                    int i3 = i2;
                    if (i3 < 0) {
                        break;
                    }
                    ServiceRecord.StartItem si = r.deliveredStarts.get(i3);
                    si.removeUriPermissionsLocked();
                    if (si.intent != null) {
                        if (!allowCancel) {
                            canceled2 = canceled3;
                        } else if (si.deliveryCount >= i || si.doneExecutingCount >= 6) {
                            Slog.w("ActivityManager", "Canceling start item " + si.intent + " in service " + r.name);
                            canceled3 = true;
                        } else {
                            canceled2 = canceled3;
                        }
                        r.pendingStarts.add(0, si);
                        smap = smap2;
                        long dur = (SystemClock.uptimeMillis() - si.deliveredTime) * 2;
                        if (minDuration2 < dur) {
                            minDuration2 = dur;
                        }
                        if (resetTime < dur) {
                            resetTime = dur;
                        }
                        canceled3 = canceled2;
                        i2 = i3 - 1;
                        smap2 = smap;
                        i = 3;
                    }
                    smap = smap2;
                    i2 = i3 - 1;
                    smap2 = smap;
                    i = 3;
                }
                canceled = canceled3;
                r.deliveredStarts.clear();
            } else {
                canceled = false;
            }
            r.totalRestartCount++;
            if (r.restartDelay == 0) {
                r.restartCount++;
                r.restartDelay = minDuration2;
            } else if (r.crashCount > 1) {
                r.restartDelay = this.mAm.mConstants.BOUND_SERVICE_CRASH_RESTART_DURATION * (r.crashCount - 1);
            } else if (now > r.restartTime + resetTime) {
                r.restartCount = 1;
                r.restartDelay = minDuration2;
            } else {
                r.restartDelay *= this.mAm.mConstants.SERVICE_RESTART_DURATION_FACTOR;
                if (r.restartDelay < minDuration2) {
                    r.restartDelay = minDuration2;
                }
            }
            r.nextRestartTime = r.restartDelay + now;
            while (true) {
                boolean repeat = false;
                long restartTimeBetween = this.mAm.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN;
                int i4 = this.mRestartingServices.size() - 1;
                while (true) {
                    if (i4 < 0) {
                        minDuration = minDuration2;
                        break;
                    }
                    ServiceRecord r2 = this.mRestartingServices.get(i4);
                    if (r2 != r) {
                        minDuration = minDuration2;
                        if (r.nextRestartTime >= r2.nextRestartTime - restartTimeBetween && r.nextRestartTime < r2.nextRestartTime + restartTimeBetween) {
                            r.nextRestartTime = r2.nextRestartTime + restartTimeBetween;
                            r.restartDelay = r.nextRestartTime - now;
                            repeat = true;
                            break;
                        }
                    } else {
                        minDuration = minDuration2;
                    }
                    i4--;
                    minDuration2 = minDuration;
                }
                if (!repeat) {
                    break;
                }
                minDuration2 = minDuration;
            }
            canceled3 = canceled;
            z = false;
        } else {
            r.totalRestartCount++;
            z = false;
            r.restartCount = 0;
            r.restartDelay = 0L;
            r.nextRestartTime = now;
        }
        if (!this.mRestartingServices.contains(r)) {
            r.createdFromFg = z;
            this.mRestartingServices.add(r);
            r.makeRestarting(this.mAm.mProcessStats.getMemFactorLocked(), now);
        }
        cancelForegroundNotificationLocked(r);
        this.mAm.mHandler.removeCallbacks(r.restarter);
        this.mAm.mHandler.postAtTime(r.restarter, r.nextRestartTime);
        r.nextRestartTime = SystemClock.uptimeMillis() + r.restartDelay;
        Slog.w("ActivityManager", "Scheduling restart of crashed service " + r.shortName + " in " + r.restartDelay + "ms");
        EventLog.writeEvent((int) EventLogTags.AM_SCHEDULE_SERVICE_RESTART, Integer.valueOf(r.userId), r.shortName, Long.valueOf(r.restartDelay));
        return canceled3;
    }

    final void performServiceRestartLocked(ServiceRecord r) {
        if (!this.mRestartingServices.contains(r)) {
            return;
        }
        if (!isServiceNeededLocked(r, false, false)) {
            Slog.wtf("ActivityManager", "Restarting service that is not needed: " + r);
            return;
        }
        try {
            bringUpServiceLocked(r, r.intent.getIntent().getFlags(), r.createdFromFg, true, false);
        } catch (TransactionTooLargeException e) {
        }
    }

    private final boolean unscheduleServiceRestartLocked(ServiceRecord r, int callingUid, boolean force) {
        if (!force && r.restartDelay == 0) {
            return false;
        }
        boolean removed = this.mRestartingServices.remove(r);
        if (removed || callingUid != r.appInfo.uid) {
            r.resetRestartCounter();
        }
        if (removed) {
            clearRestartingIfNeededLocked(r);
        }
        this.mAm.mHandler.removeCallbacks(r.restarter);
        return true;
    }

    private void clearRestartingIfNeededLocked(ServiceRecord r) {
        if (r.restartTracker != null) {
            boolean stillTracking = false;
            int i = this.mRestartingServices.size() - 1;
            while (true) {
                if (i < 0) {
                    break;
                } else if (this.mRestartingServices.get(i).restartTracker != r.restartTracker) {
                    i--;
                } else {
                    stillTracking = true;
                    break;
                }
            }
            if (!stillTracking) {
                r.restartTracker.setRestarting(false, this.mAm.mProcessStats.getMemFactorLocked(), SystemClock.uptimeMillis());
                r.restartTracker = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:70:0x0205  */
    /* JADX WARN: Removed duplicated region for block: B:76:0x0241  */
    /* JADX WARN: Removed duplicated region for block: B:79:0x024a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.lang.String bringUpServiceLocked(com.android.server.am.ServiceRecord r20, int r21, boolean r22, boolean r23, boolean r24) throws android.os.TransactionTooLargeException {
        /*
            Method dump skipped, instructions count: 622
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ActiveServices.bringUpServiceLocked(com.android.server.am.ServiceRecord, int, boolean, boolean, boolean):java.lang.String");
    }

    private final void requestServiceBindingsLocked(ServiceRecord r, boolean execInFg) throws TransactionTooLargeException {
        for (int i = r.bindings.size() - 1; i >= 0; i--) {
            IntentBindRecord ibr = r.bindings.valueAt(i);
            if (!requestServiceBindingLocked(r, ibr, execInFg, false)) {
                return;
            }
        }
    }

    private final void realStartServiceLocked(ServiceRecord r, ProcessRecord app, boolean execInFg) throws RemoteException {
        boolean z;
        if (app.thread == null) {
            throw new RemoteException();
        }
        if (ActivityManagerDebugConfig.DEBUG_MU) {
            Slog.v(TAG_MU, "realStartServiceLocked, ServiceRecord.uid = " + r.appInfo.uid + ", ProcessRecord.uid = " + app.uid);
        }
        r.app = app;
        long uptimeMillis = SystemClock.uptimeMillis();
        r.lastActivity = uptimeMillis;
        r.restartTime = uptimeMillis;
        boolean newService = app.services.add(r);
        bumpServiceExecutingLocked(r, execInFg, "create");
        this.mAm.updateLruProcessLocked(app, false, null);
        updateServiceForegroundLocked(r.app, false);
        this.mAm.updateOomAdjLocked();
        try {
            try {
                synchronized (r.stats.getBatteryStats()) {
                    r.stats.startLaunchedLocked();
                }
                this.mAm.notifyPackageUse(r.serviceInfo.packageName, 1);
                app.forceProcessStateUpTo(9);
                app.thread.scheduleCreateService(r, r.serviceInfo, this.mAm.compatibilityInfoForPackageLocked(r.serviceInfo.applicationInfo), app.repProcState);
                r.postNotification();
                if (1 == 0) {
                    boolean inDestroying = this.mDestroyingServices.contains(r);
                    serviceDoneExecutingLocked(r, inDestroying, inDestroying);
                    if (newService) {
                        app.services.remove(r);
                        r.app = null;
                    }
                    if (!inDestroying) {
                        scheduleServiceRestartLocked(r, false);
                    }
                }
                if (r.whitelistManager) {
                    app.whitelistManager = true;
                }
                requestServiceBindingsLocked(r, execInFg);
                updateServiceClientActivitiesLocked(app, null, true);
                if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
                    r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(), null, null, 0));
                }
                sendServiceArgsLocked(r, execInFg, true);
                if (r.delayed) {
                    if (DEBUG_DELAYED_STARTS) {
                        Slog.v("ActivityManager", "REM FR DELAY LIST (new proc): " + r);
                    }
                    getServiceMapLocked(r.userId).mDelayedStartList.remove(r);
                    z = false;
                    r.delayed = false;
                } else {
                    z = false;
                }
                if (r.delayedStop) {
                    r.delayedStop = z;
                    if (r.startRequested) {
                        if (DEBUG_DELAYED_STARTS) {
                            Slog.v("ActivityManager", "Applying delayed stop (from start): " + r);
                        }
                        stopServiceLocked(r);
                    }
                }
            } catch (DeadObjectException e) {
                Slog.w("ActivityManager", "Application dead when creating service " + r);
                this.mAm.appDiedLocked(app);
                throw e;
            }
        } catch (Throwable th) {
            if (0 == 0) {
                boolean inDestroying2 = this.mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying2, inDestroying2);
                if (newService) {
                    app.services.remove(r);
                    r.app = null;
                }
                if (!inDestroying2) {
                    scheduleServiceRestartLocked(r, false);
                }
            }
            throw th;
        }
    }

    private final void sendServiceArgsLocked(ServiceRecord r, boolean execInFg, boolean oomAdjusted) throws TransactionTooLargeException {
        int i;
        int N = r.pendingStarts.size();
        if (N == 0) {
            return;
        }
        ArrayList<ServiceStartArgs> args = new ArrayList<>();
        while (true) {
            if (r.pendingStarts.size() <= 0) {
                break;
            }
            ServiceRecord.StartItem si = r.pendingStarts.remove(0);
            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "Sending arguments to: " + r + " " + r.intent + " args=" + si.intent);
            }
            if (si.intent != null || N <= 1) {
                si.deliveredTime = SystemClock.uptimeMillis();
                r.deliveredStarts.add(si);
                si.deliveryCount++;
                if (si.neededGrants != null) {
                    this.mAm.grantUriPermissionUncheckedFromIntentLocked(si.neededGrants, si.getUriPermissionsLocked());
                }
                this.mAm.grantEphemeralAccessLocked(r.userId, si.intent, r.appInfo.uid, UserHandle.getAppId(si.callingId));
                bumpServiceExecutingLocked(r, execInFg, "start");
                if (!oomAdjusted) {
                    oomAdjusted = true;
                    this.mAm.updateOomAdjLocked(r.app, true);
                }
                if (r.fgRequired && !r.fgWaiting) {
                    if (!r.isForeground) {
                        if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                            Slog.i("ActivityManager", "Launched service must call startForeground() within timeout: " + r);
                        }
                        scheduleServiceForegroundTransitionTimeoutLocked(r);
                    } else {
                        if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                            Slog.i("ActivityManager", "Service already foreground; no new timeout: " + r);
                        }
                        r.fgRequired = false;
                    }
                }
                int flags = 0;
                if (si.deliveryCount > 1) {
                    flags = 0 | 2;
                }
                if (si.doneExecutingCount > 0) {
                    flags |= 1;
                }
                args.add(new ServiceStartArgs(si.taskRemoved, si.id, flags, si.intent));
            }
        }
        ParceledListSlice<ServiceStartArgs> slice = new ParceledListSlice<>(args);
        slice.setInlineCountLimit(4);
        Exception caughtException = null;
        try {
            r.app.thread.scheduleServiceArgs(r, slice);
        } catch (TransactionTooLargeException e) {
            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "Transaction too large for " + args.size() + " args, first: " + args.get(0).args);
            }
            Slog.w("ActivityManager", "Failed delivering service starts", e);
            caughtException = e;
        } catch (RemoteException e2) {
            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "Crashed while sending args: " + r);
            }
            Slog.w("ActivityManager", "Failed delivering service starts", e2);
            caughtException = e2;
        } catch (Exception e3) {
            Slog.w("ActivityManager", "Unexpected exception", e3);
            caughtException = e3;
        }
        if (caughtException != null) {
            boolean inDestroying = this.mDestroyingServices.contains(r);
            for (i = 0; i < args.size(); i++) {
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
            }
            if (caughtException instanceof TransactionTooLargeException) {
                throw ((TransactionTooLargeException) caughtException);
            }
        }
    }

    private final boolean isServiceNeededLocked(ServiceRecord r, boolean knowConn, boolean hasConn) {
        if (r.startRequested) {
            return true;
        }
        if (!knowConn) {
            hasConn = r.hasAutoCreateConnections();
        }
        return hasConn;
    }

    private final void bringDownServiceIfNeededLocked(ServiceRecord r, boolean knowConn, boolean hasConn) {
        if (isServiceNeededLocked(r, knowConn, hasConn) || this.mPendingServices.contains(r)) {
            return;
        }
        bringDownServiceLocked(r);
    }

    private final void bringDownServiceLocked(ServiceRecord r) {
        int conni = r.connections.size() - 1;
        while (true) {
            if (conni < 0) {
                break;
            }
            ArrayList<ConnectionRecord> c = r.connections.valueAt(conni);
            for (int i = 0; i < c.size(); i++) {
                ConnectionRecord cr = c.get(i);
                cr.serviceDead = true;
                try {
                    cr.conn.connected(r.name, (IBinder) null, true);
                } catch (Exception e) {
                    Slog.w("ActivityManager", "Failure disconnecting service " + r.name + " to connection " + c.get(i).conn.asBinder() + " (in " + c.get(i).binding.client.processName + ")", e);
                }
            }
            conni--;
        }
        if (r.app != null && r.app.thread != null) {
            for (int i2 = r.bindings.size() - 1; i2 >= 0; i2--) {
                IntentBindRecord ibr = r.bindings.valueAt(i2);
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    Slog.v("ActivityManager", "Bringing down binding " + ibr + ": hasBound=" + ibr.hasBound);
                }
                if (ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r, false, "bring down unbind");
                        this.mAm.updateOomAdjLocked(r.app, true);
                        ibr.hasBound = false;
                        ibr.requested = false;
                        r.app.thread.scheduleUnbindService(r, ibr.intent.getIntent());
                    } catch (Exception e2) {
                        Slog.w("ActivityManager", "Exception when unbinding service " + r.shortName, e2);
                        serviceProcessGoneLocked(r);
                    }
                }
            }
        }
        if (r.fgRequired) {
            Slog.w("ActivityManager", "Bringing down service while still waiting for start foreground: " + r);
            r.fgRequired = false;
            r.fgWaiting = false;
            this.mAm.mAppOpsService.finishOperation(AppOpsManager.getToken(this.mAm.mAppOpsService), 76, r.appInfo.uid, r.packageName);
            this.mAm.mHandler.removeMessages(66, r);
            if (r.app != null) {
                Message msg = this.mAm.mHandler.obtainMessage(69);
                msg.obj = r.app;
                msg.getData().putCharSequence("servicerecord", r.toString());
                this.mAm.mHandler.sendMessage(msg);
            }
        }
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            RuntimeException here = new RuntimeException();
            here.fillInStackTrace();
            Slog.v("ActivityManager", "Bringing down " + r + " " + r.intent, here);
        }
        r.destroyTime = SystemClock.uptimeMillis();
        ServiceMap smap = getServiceMapLocked(r.userId);
        ServiceRecord found = smap.mServicesByName.remove(r.name);
        if (found != null && found != r) {
            smap.mServicesByName.put(r.name, found);
            throw new IllegalStateException("Bringing down " + r + " but actually running " + found);
        }
        smap.mServicesByIntent.remove(r.intent);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r, 0, true);
        for (int i3 = this.mPendingServices.size() - 1; i3 >= 0; i3--) {
            if (this.mPendingServices.get(i3) == r) {
                this.mPendingServices.remove(i3);
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    Slog.v("ActivityManager", "Removed pending: " + r);
                }
            }
        }
        cancelForegroundNotificationLocked(r);
        if (r.isForeground) {
            decActiveForegroundAppLocked(smap, r);
            this.mAm.mAppOpsService.finishOperation(AppOpsManager.getToken(this.mAm.mAppOpsService), 76, r.appInfo.uid, r.packageName);
            StatsLog.write(60, r.appInfo.uid, r.shortName, 2);
        }
        r.isForeground = false;
        r.foregroundId = 0;
        r.foregroundNoti = null;
        r.clearDeliveredStartsLocked();
        r.pendingStarts.clear();
        if (r.app != null) {
            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopLaunchedLocked();
            }
            r.app.services.remove(r);
            if (r.whitelistManager) {
                updateWhitelistManagerLocked(r.app);
            }
            if (r.app.thread != null) {
                updateServiceForegroundLocked(r.app, false);
                try {
                    bumpServiceExecutingLocked(r, false, "destroy");
                    this.mDestroyingServices.add(r);
                    r.destroying = true;
                    this.mAm.updateOomAdjLocked(r.app, true);
                    r.app.thread.scheduleStopService(r);
                } catch (Exception e3) {
                    Slog.w("ActivityManager", "Exception when destroying service " + r.shortName, e3);
                    serviceProcessGoneLocked(r);
                }
            } else if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "Removed service that has no process: " + r);
            }
        } else if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            Slog.v("ActivityManager", "Removed service that is not running: " + r);
        }
        if (r.bindings.size() > 0) {
            r.bindings.clear();
        }
        if (r.restarter instanceof ServiceRestarter) {
            ((ServiceRestarter) r.restarter).setService(null);
        }
        int memFactor = this.mAm.mProcessStats.getMemFactorLocked();
        long now = SystemClock.uptimeMillis();
        if (r.tracker != null) {
            r.tracker.setStarted(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            if (r.executeNesting == 0) {
                r.tracker.clearCurrentOwner(r, false);
                r.tracker = null;
            }
        }
        smap.ensureNotStartingBackgroundLocked(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeConnectionLocked(ConnectionRecord c, ProcessRecord skipApp, ActivityRecord skipAct) {
        IBinder binder = c.conn.asBinder();
        AppBindRecord b = c.binding;
        ServiceRecord s = b.service;
        ArrayList<ConnectionRecord> clist = s.connections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                s.connections.remove(binder);
            }
        }
        b.connections.remove(c);
        if (c.activity != null && c.activity != skipAct && c.activity.connections != null) {
            c.activity.connections.remove(c);
        }
        if (b.client != skipApp) {
            b.client.connections.remove(c);
            if ((c.flags & 8) != 0) {
                b.client.updateHasAboveClientLocked();
            }
            if ((c.flags & 16777216) != 0) {
                s.updateWhitelistManager();
                if (!s.whitelistManager && s.app != null) {
                    updateWhitelistManagerLocked(s.app);
                }
            }
            if (s.app != null) {
                updateServiceClientActivitiesLocked(s.app, c, true);
            }
        }
        ArrayList<ConnectionRecord> clist2 = this.mServiceConnections.get(binder);
        if (clist2 != null) {
            clist2.remove(c);
            if (clist2.size() == 0) {
                this.mServiceConnections.remove(binder);
            }
        }
        this.mAm.stopAssociationLocked(b.client.uid, b.client.processName, s.appInfo.uid, s.name);
        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }
        if (!c.serviceDead) {
            if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "Disconnecting binding " + b.intent + ": shouldUnbind=" + b.intent.hasBound);
            }
            if (s.app != null && s.app.thread != null && b.intent.apps.size() == 0 && b.intent.hasBound) {
                try {
                    bumpServiceExecutingLocked(s, false, "unbind");
                    if (b.client != s.app && (c.flags & 32) == 0 && s.app.setProcState <= 12) {
                        this.mAm.updateLruProcessLocked(s.app, false, null);
                    }
                    this.mAm.updateOomAdjLocked(s.app, true);
                    b.intent.hasBound = false;
                    b.intent.doRebind = false;
                    s.app.thread.scheduleUnbindService(s, b.intent.intent.getIntent());
                } catch (Exception e) {
                    Slog.w("ActivityManager", "Exception when unbinding service " + s.shortName, e);
                    serviceProcessGoneLocked(s);
                }
            }
            this.mPendingServices.remove(s);
            if ((c.flags & 1) != 0) {
                boolean hasAutoCreate = s.hasAutoCreateConnections();
                if (!hasAutoCreate && s.tracker != null) {
                    s.tracker.setBound(false, this.mAm.mProcessStats.getMemFactorLocked(), SystemClock.uptimeMillis());
                }
                bringDownServiceIfNeededLocked(s, true, hasAutoCreate);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void serviceDoneExecutingLocked(ServiceRecord r, int type, int startId, int res) {
        boolean inDestroying = this.mDestroyingServices.contains(r);
        if (r != null) {
            if (type == 1) {
                r.callStart = true;
                if (res != 1000) {
                    switch (res) {
                        case 0:
                        case 1:
                            r.findDeliveredStart(startId, false, true);
                            r.stopIfKilled = false;
                            break;
                        case 2:
                            r.findDeliveredStart(startId, false, true);
                            if (r.getLastStartId() == startId) {
                                r.stopIfKilled = true;
                                break;
                            }
                            break;
                        case 3:
                            ServiceRecord.StartItem si = r.findDeliveredStart(startId, false, false);
                            if (si != null) {
                                si.deliveryCount = 0;
                                si.doneExecutingCount++;
                                r.stopIfKilled = true;
                                break;
                            }
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown service start result: " + res);
                    }
                } else {
                    r.findDeliveredStart(startId, true, true);
                }
                if (res == 0) {
                    r.callStart = false;
                }
            } else if (type == 2) {
                if (inDestroying) {
                    if (r.executeNesting != 1) {
                        Slog.w("ActivityManager", "Service done with onDestroy, but executeNesting=" + r.executeNesting + ": " + r);
                        r.executeNesting = 1;
                    }
                } else if (r.app != null) {
                    Slog.w("ActivityManager", "Service done with onDestroy, but not inDestroying: " + r + ", app=" + r.app);
                }
            }
            long origId = Binder.clearCallingIdentity();
            serviceDoneExecutingLocked(r, inDestroying, inDestroying);
            Binder.restoreCallingIdentity(origId);
            return;
        }
        Slog.w("ActivityManager", "Done executing unknown service from pid " + Binder.getCallingPid());
    }

    private void serviceProcessGoneLocked(ServiceRecord r) {
        if (r.tracker != null) {
            int memFactor = this.mAm.mProcessStats.getMemFactorLocked();
            long now = SystemClock.uptimeMillis();
            r.tracker.setExecuting(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            r.tracker.setStarted(false, memFactor, now);
        }
        serviceDoneExecutingLocked(r, true, true);
    }

    private void serviceDoneExecutingLocked(ServiceRecord r, boolean inDestroying, boolean finishing) {
        if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
            Slog.v("ActivityManager", "<<< DONE EXECUTING " + r + ": nesting=" + r.executeNesting + ", inDestroying=" + inDestroying + ", app=" + r.app);
        } else if (ActivityManagerDebugConfig.DEBUG_SERVICE_EXECUTING) {
            Slog.v("ActivityManager", "<<< DONE EXECUTING " + r.shortName);
        }
        r.executeNesting--;
        if (r.executeNesting <= 0) {
            if (r.app != null) {
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    Slog.v("ActivityManager", "Nesting at 0 of " + r.shortName);
                }
                r.app.execServicesFg = false;
                r.app.executingServices.remove(r);
                if (r.app.executingServices.size() == 0) {
                    if (ActivityManagerDebugConfig.DEBUG_SERVICE || ActivityManagerDebugConfig.DEBUG_SERVICE_EXECUTING) {
                        Slog.v("ActivityManager", "No more executingServices of " + r.shortName);
                    }
                    this.mAm.mHandler.removeMessages(12, r.app);
                } else if (r.executeFg) {
                    int i = r.app.executingServices.size() - 1;
                    while (true) {
                        if (i < 0) {
                            break;
                        } else if (!r.app.executingServices.valueAt(i).executeFg) {
                            i--;
                        } else {
                            r.app.execServicesFg = true;
                            break;
                        }
                    }
                }
                if (inDestroying) {
                    if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                        Slog.v("ActivityManager", "doneExecuting remove destroying " + r);
                    }
                    this.mDestroyingServices.remove(r);
                    r.bindings.clear();
                }
                this.mAm.updateOomAdjLocked(r.app, true);
            }
            r.executeFg = false;
            if (r.tracker != null) {
                r.tracker.setExecuting(false, this.mAm.mProcessStats.getMemFactorLocked(), SystemClock.uptimeMillis());
                if (finishing) {
                    r.tracker.clearCurrentOwner(r, false);
                    r.tracker = null;
                }
            }
            if (finishing) {
                if (r.app != null && !r.app.persistent) {
                    r.app.services.remove(r);
                    if (r.whitelistManager) {
                        updateWhitelistManagerLocked(r.app);
                    }
                }
                r.app = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean attachApplicationLocked(ProcessRecord proc, String processName) throws RemoteException {
        boolean didSomething = false;
        int i = 0;
        if (this.mPendingServices.size() > 0) {
            ServiceRecord sr = null;
            boolean didSomething2 = false;
            int i2 = 0;
            while (i2 < this.mPendingServices.size()) {
                try {
                    sr = this.mPendingServices.get(i2);
                    if (proc == sr.isolatedProc || (proc.uid == sr.appInfo.uid && processName.equals(sr.processName))) {
                        this.mPendingServices.remove(i2);
                        i2--;
                        proc.addPackage(sr.appInfo.packageName, sr.appInfo.longVersionCode, this.mAm.mProcessStats);
                        realStartServiceLocked(sr, proc, sr.createdFromFg);
                        didSomething2 = true;
                        if (!isServiceNeededLocked(sr, false, false)) {
                            bringDownServiceLocked(sr);
                        }
                    }
                    i2++;
                } catch (RemoteException e) {
                    Slog.w("ActivityManager", "Exception in new application when starting service " + sr.shortName, e);
                    throw e;
                }
            }
            didSomething = didSomething2;
        }
        if (this.mRestartingServices.size() > 0) {
            while (true) {
                int i3 = i;
                if (i3 >= this.mRestartingServices.size()) {
                    break;
                }
                ServiceRecord sr2 = this.mRestartingServices.get(i3);
                if (proc == sr2.isolatedProc || (proc.uid == sr2.appInfo.uid && processName.equals(sr2.processName))) {
                    this.mAm.mHandler.removeCallbacks(sr2.restarter);
                    this.mAm.mHandler.post(sr2.restarter);
                }
                i = i3 + 1;
            }
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void processStartTimedOutLocked(ProcessRecord proc) {
        int i = 0;
        while (i < this.mPendingServices.size()) {
            ServiceRecord sr = this.mPendingServices.get(i);
            if ((proc.uid == sr.appInfo.uid && proc.processName.equals(sr.processName)) || sr.isolatedProc == proc) {
                Slog.w("ActivityManager", "Forcing bringing down service: " + sr);
                sr.isolatedProc = null;
                this.mPendingServices.remove(i);
                i += -1;
                bringDownServiceLocked(sr);
            }
            i++;
        }
    }

    private boolean collectPackageServicesLocked(String packageName, Set<String> filterByClasses, boolean evenPersistent, boolean doit, boolean killProcess, ArrayMap<ComponentName, ServiceRecord> services) {
        boolean didSomething = false;
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceRecord service = services.valueAt(i);
            boolean sameComponent = packageName == null || (service.packageName.equals(packageName) && (filterByClasses == null || filterByClasses.contains(service.name.getClassName())));
            if (sameComponent && (service.app == null || evenPersistent || !service.app.persistent)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i("ActivityManager", "  Force stopping service " + service);
                if (service.app != null) {
                    service.app.removed = killProcess;
                    if (!service.app.persistent) {
                        service.app.services.remove(service);
                        if (service.whitelistManager) {
                            updateWhitelistManagerLocked(service.app);
                        }
                    }
                }
                service.app = null;
                service.isolatedProc = null;
                if (this.mTmpCollectionResults == null) {
                    this.mTmpCollectionResults = new ArrayList<>();
                }
                this.mTmpCollectionResults.add(service);
            }
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean bringDownDisabledPackageServicesLocked(String packageName, Set<String> filterByClasses, int userId, boolean evenPersistent, boolean killProcess, boolean doit) {
        boolean didSomething = false;
        if (this.mTmpCollectionResults != null) {
            this.mTmpCollectionResults.clear();
        }
        if (userId == -1) {
            int i = this.mServiceMap.size() - 1;
            while (true) {
                int i2 = i;
                if (i2 < 0) {
                    break;
                }
                didSomething |= collectPackageServicesLocked(packageName, filterByClasses, evenPersistent, doit, killProcess, this.mServiceMap.valueAt(i2).mServicesByName);
                if (!doit && didSomething) {
                    return true;
                }
                if (doit && filterByClasses == null) {
                    forceStopPackageLocked(packageName, this.mServiceMap.valueAt(i2).mUserId);
                }
                i = i2 - 1;
            }
        } else {
            ServiceMap smap = this.mServiceMap.get(userId);
            if (smap != null) {
                ArrayMap<ComponentName, ServiceRecord> items = smap.mServicesByName;
                didSomething = collectPackageServicesLocked(packageName, filterByClasses, evenPersistent, doit, killProcess, items);
            }
            if (doit && filterByClasses == null) {
                forceStopPackageLocked(packageName, userId);
            }
        }
        if (this.mTmpCollectionResults != null) {
            for (int i3 = this.mTmpCollectionResults.size() - 1; i3 >= 0; i3--) {
                bringDownServiceLocked(this.mTmpCollectionResults.get(i3));
            }
            this.mTmpCollectionResults.clear();
        }
        return didSomething;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forceStopPackageLocked(String packageName, int userId) {
        ServiceMap smap = this.mServiceMap.get(userId);
        if (smap != null && smap.mActiveForegroundApps.size() > 0) {
            for (int i = smap.mActiveForegroundApps.size() - 1; i >= 0; i--) {
                ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                if (aa.mPackageName.equals(packageName)) {
                    smap.mActiveForegroundApps.removeAt(i);
                    smap.mActiveForegroundAppsChanged = true;
                }
            }
            if (smap.mActiveForegroundAppsChanged) {
                requestUpdateActiveForegroundAppsLocked(smap, 0L);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cleanUpRemovedTaskLocked(TaskRecord tr, ComponentName component, Intent baseIntent) {
        ArrayList<ServiceRecord> services = new ArrayList<>();
        ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(tr.userId);
        for (int i = alls.size() - 1; i >= 0; i--) {
            ServiceRecord sr = alls.valueAt(i);
            if (sr.packageName.equals(component.getPackageName())) {
                services.add(sr);
            }
        }
        int i2 = services.size();
        int i3 = i2 - 1;
        while (true) {
            int i4 = i3;
            if (i4 >= 0) {
                ServiceRecord sr2 = services.get(i4);
                if (sr2.startRequested) {
                    if ((sr2.serviceInfo.flags & 1) != 0) {
                        Slog.i("ActivityManager", "Stopping service " + sr2.shortName + ": remove task");
                        stopServiceLocked(sr2);
                    } else {
                        sr2.pendingStarts.add(new ServiceRecord.StartItem(sr2, true, sr2.getLastStartId(), baseIntent, null, 0));
                        if (sr2.app != null && sr2.app.thread != null) {
                            try {
                                sendServiceArgsLocked(sr2, true, false);
                            } catch (TransactionTooLargeException e) {
                            }
                        }
                    }
                }
                i3 = i4 - 1;
            } else {
                return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r4v25, types: [android.os.IBinder] */
    /* JADX WARN: Type inference failed for: r4v27 */
    /* JADX WARN: Type inference failed for: r4v32 */
    public final void killServicesLocked(ProcessRecord app, boolean allowRestart) {
        ProcessRecord processRecord;
        int i = app.connections.size() - 1;
        while (true) {
            processRecord = null;
            if (i < 0) {
                break;
            }
            removeConnectionLocked(app.connections.valueAt(i), app, null);
            i--;
        }
        updateServiceConnectionActivitiesLocked(app);
        app.connections.clear();
        boolean z = false;
        app.whitelistManager = false;
        int i2 = app.services.size() - 1;
        while (i2 >= 0) {
            ServiceRecord sr = app.services.valueAt(i2);
            synchronized (sr.stats.getBatteryStats()) {
                sr.stats.stopLaunchedLocked();
            }
            if (sr.app != app && sr.app != null && !sr.app.persistent) {
                sr.app.services.remove(sr);
            }
            sr.app = processRecord;
            sr.isolatedProc = processRecord;
            sr.executeNesting = z ? 1 : 0;
            sr.forceClearTracker();
            if (this.mDestroyingServices.remove(sr) && ActivityManagerDebugConfig.DEBUG_SERVICE) {
                Slog.v("ActivityManager", "killServices remove destroying " + sr);
            }
            int numClients = sr.bindings.size();
            int bindingi = numClients - 1;
            ?? r4 = processRecord;
            while (bindingi >= 0) {
                IntentBindRecord b = sr.bindings.valueAt(bindingi);
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    Slog.v("ActivityManager", "Killing binding " + b + ": shouldUnbind=" + b.hasBound);
                }
                b.binder = r4;
                b.hasBound = z;
                b.received = z;
                b.requested = z;
                for (int appi = b.apps.size() - 1; appi >= 0; appi--) {
                    ProcessRecord proc = b.apps.keyAt(appi);
                    if (!proc.killedByAm && proc.thread != null) {
                        AppBindRecord abind = b.apps.valueAt(appi);
                        boolean hasCreate = false;
                        int conni = abind.connections.size() - 1;
                        while (true) {
                            if (conni < 0) {
                                break;
                            }
                            ConnectionRecord conn = abind.connections.valueAt(conni);
                            if ((conn.flags & 49) == 1) {
                                hasCreate = true;
                                break;
                            }
                            conni--;
                        }
                        if (!hasCreate) {
                        }
                    }
                }
                bindingi--;
                z = false;
                r4 = 0;
            }
            i2--;
            z = false;
            processRecord = null;
        }
        ServiceMap smap = getServiceMapLocked(app.userId);
        for (int i3 = app.services.size() - 1; i3 >= 0; i3--) {
            ServiceRecord sr2 = app.services.valueAt(i3);
            if (!app.persistent) {
                app.services.removeAt(i3);
            }
            ServiceRecord curRec = smap.mServicesByName.get(sr2.name);
            if (curRec != sr2) {
                if (curRec != null) {
                    Slog.wtf("ActivityManager", "Service " + sr2 + " in process " + app + " not same as in map: " + curRec);
                }
            } else if (allowRestart && sr2.crashCount >= this.mAm.mConstants.BOUND_SERVICE_MAX_CRASH_RETRY && (sr2.serviceInfo.applicationInfo.flags & 8) == 0) {
                Slog.w("ActivityManager", "Service crashed " + sr2.crashCount + " times, stopping: " + sr2);
                EventLog.writeEvent((int) EventLogTags.AM_SERVICE_CRASHED_TOO_MUCH, Integer.valueOf(sr2.userId), Integer.valueOf(sr2.crashCount), sr2.shortName, Integer.valueOf(app.pid));
                bringDownServiceLocked(sr2);
            } else {
                if (allowRestart && this.mAm.mUserController.isUserRunning(sr2.userId, 0)) {
                    boolean canceled = scheduleServiceRestartLocked(sr2, true);
                    if (sr2.startRequested && ((sr2.stopIfKilled || canceled) && sr2.pendingStarts.size() == 0)) {
                        sr2.startRequested = false;
                        if (sr2.tracker != null) {
                            sr2.tracker.setStarted(false, this.mAm.mProcessStats.getMemFactorLocked(), SystemClock.uptimeMillis());
                        }
                        if (!sr2.hasAutoCreateConnections()) {
                            bringDownServiceLocked(sr2);
                        }
                    }
                } else {
                    bringDownServiceLocked(sr2);
                }
            }
        }
        if (!allowRestart) {
            app.services.clear();
            for (int i4 = this.mRestartingServices.size() - 1; i4 >= 0; i4--) {
                ServiceRecord r = this.mRestartingServices.get(i4);
                if (r.processName.equals(app.processName) && r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    this.mRestartingServices.remove(i4);
                    clearRestartingIfNeededLocked(r);
                }
            }
            int i5 = this.mPendingServices.size() - 1;
            while (true) {
                int i6 = i5;
                if (i6 < 0) {
                    break;
                }
                ServiceRecord r2 = this.mPendingServices.get(i6);
                if (r2.processName.equals(app.processName) && r2.serviceInfo.applicationInfo.uid == app.info.uid) {
                    this.mPendingServices.remove(i6);
                }
                i5 = i6 - 1;
            }
        }
        int i7 = this.mDestroyingServices.size();
        while (i7 > 0) {
            i7--;
            ServiceRecord sr3 = this.mDestroyingServices.get(i7);
            if (sr3.app == app) {
                sr3.forceClearTracker();
                this.mDestroyingServices.remove(i7);
                if (ActivityManagerDebugConfig.DEBUG_SERVICE) {
                    Slog.v("ActivityManager", "killServices remove destroying " + sr3);
                }
            }
        }
        app.executingServices.clear();
    }

    ActivityManager.RunningServiceInfo makeRunningServiceInfoLocked(ServiceRecord r) {
        ActivityManager.RunningServiceInfo info = new ActivityManager.RunningServiceInfo();
        info.service = r.name;
        if (r.app != null) {
            info.pid = r.app.pid;
        }
        info.uid = r.appInfo.uid;
        info.process = r.processName;
        info.foreground = r.isForeground;
        info.activeSince = r.createRealTime;
        info.started = r.startRequested;
        info.clientCount = r.connections.size();
        info.crashCount = r.crashCount;
        info.lastActivityTime = r.lastActivity;
        if (r.isForeground) {
            info.flags |= 2;
        }
        if (r.startRequested) {
            info.flags |= 1;
        }
        if (r.app != null && r.app.pid == ActivityManagerService.MY_PID) {
            info.flags |= 4;
        }
        if (r.app != null && r.app.persistent) {
            info.flags |= 8;
        }
        for (int conni = r.connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> connl = r.connections.valueAt(conni);
            for (int i = 0; i < connl.size(); i++) {
                ConnectionRecord conn = connl.get(i);
                if (conn.clientLabel != 0) {
                    info.clientPackage = conn.binding.client.info.packageName;
                    info.clientLabel = conn.clientLabel;
                    return info;
                }
            }
        }
        return info;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<ActivityManager.RunningServiceInfo> getRunningServiceInfoLocked(int maxNum, int flags, int callingUid, boolean allowed, boolean canInteractAcrossUsers) {
        ArrayList<ActivityManager.RunningServiceInfo> res = new ArrayList<>();
        long ident = Binder.clearCallingIdentity();
        int i = 0;
        try {
            if (canInteractAcrossUsers) {
                int[] users = this.mAm.mUserController.getUsers();
                for (int ui = 0; ui < users.length && res.size() < maxNum; ui++) {
                    ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(users[ui]);
                    for (int i2 = 0; i2 < alls.size() && res.size() < maxNum; i2++) {
                        res.add(makeRunningServiceInfoLocked(alls.valueAt(i2)));
                    }
                }
                while (i < this.mRestartingServices.size() && res.size() < maxNum) {
                    ServiceRecord r = this.mRestartingServices.get(i);
                    ActivityManager.RunningServiceInfo info = makeRunningServiceInfoLocked(r);
                    info.restarting = r.nextRestartTime;
                    res.add(info);
                    i++;
                }
            } else {
                int userId = UserHandle.getUserId(callingUid);
                ArrayMap<ComponentName, ServiceRecord> alls2 = getServicesLocked(userId);
                for (int i3 = 0; i3 < alls2.size() && res.size() < maxNum; i3++) {
                    ServiceRecord sr = alls2.valueAt(i3);
                    if (allowed || (sr.app != null && sr.app.uid == callingUid)) {
                        res.add(makeRunningServiceInfoLocked(sr));
                    }
                }
                while (i < this.mRestartingServices.size() && res.size() < maxNum) {
                    ServiceRecord r2 = this.mRestartingServices.get(i);
                    if (r2.userId == userId && (allowed || (r2.app != null && r2.app.uid == callingUid))) {
                        ActivityManager.RunningServiceInfo info2 = makeRunningServiceInfoLocked(r2);
                        info2.restarting = r2.nextRestartTime;
                        res.add(info2);
                    }
                    i++;
                }
            }
            return res;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public PendingIntent getRunningServiceControlPanelLocked(ComponentName name) {
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        ServiceRecord r = getServiceByNameLocked(name, userId);
        if (r != null) {
            for (int conni = r.connections.size() - 1; conni >= 0; conni--) {
                ArrayList<ConnectionRecord> conn = r.connections.valueAt(conni);
                for (int i = 0; i < conn.size(); i++) {
                    if (conn.get(i).clientIntent != null) {
                        return conn.get(i).clientIntent;
                    }
                }
            }
            return null;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (proc.executingServices.size() != 0 && proc.thread != null) {
                    long now = SystemClock.uptimeMillis();
                    long maxTime = now - (proc.execServicesFg ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
                    ServiceRecord timeout = null;
                    long nextTime = 0;
                    int i = proc.executingServices.size() - 1;
                    while (true) {
                        if (i < 0) {
                            break;
                        }
                        ServiceRecord sr = proc.executingServices.valueAt(i);
                        if (sr.executingStart < maxTime) {
                            timeout = sr;
                            break;
                        }
                        if (sr.executingStart > nextTime) {
                            nextTime = sr.executingStart;
                        }
                        i--;
                    }
                    if (timeout != null && this.mAm.mLruProcesses.contains(proc)) {
                        Slog.w("ActivityManager", "Timeout executing service: " + timeout);
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                        pw.println(timeout);
                        timeout.dump(pw, "    ");
                        pw.close();
                        this.mLastAnrDump = sw.toString();
                        this.mAm.mHandler.removeCallbacks(this.mLastAnrDumpClearer);
                        this.mAm.mHandler.postDelayed(this.mLastAnrDumpClearer, AppStandbyController.SettingsObserver.DEFAULT_SYSTEM_UPDATE_TIMEOUT);
                        anrMessage = "executing service " + timeout.shortName;
                    } else {
                        Message msg = this.mAm.mHandler.obtainMessage(12);
                        msg.obj = proc;
                        this.mAm.mHandler.sendMessageAtTime(msg, (proc.execServicesFg ? 20000L : 200000L) + nextTime);
                    }
                    String anrMessage2 = anrMessage;
                    try {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        if (anrMessage2 != null) {
                            this.mAm.mAppErrors.appNotResponding(proc, null, null, false, anrMessage2);
                            return;
                        }
                        return;
                    } catch (Throwable th) {
                        th = th;
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void serviceForegroundTimeout(ServiceRecord r) {
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (r.fgRequired && !r.destroying) {
                    ProcessRecord app = r.app;
                    if (app != null && app.debugging) {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        return;
                    }
                    if (ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK) {
                        Slog.i("ActivityManager", "Service foreground-required timeout for " + r);
                    }
                    r.fgWaiting = false;
                    stopServiceLocked(r);
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    if (app != null) {
                        AppErrors appErrors = this.mAm.mAppErrors;
                        appErrors.appNotResponding(app, null, null, false, "Context.startForegroundService() did not then call Service.startForeground(): " + r);
                        return;
                    }
                    return;
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
    }

    public void updateServiceApplicationInfoLocked(ApplicationInfo applicationInfo) {
        int userId = UserHandle.getUserId(applicationInfo.uid);
        ServiceMap serviceMap = this.mServiceMap.get(userId);
        if (serviceMap != null) {
            ArrayMap<ComponentName, ServiceRecord> servicesByName = serviceMap.mServicesByName;
            for (int j = servicesByName.size() - 1; j >= 0; j--) {
                ServiceRecord serviceRecord = servicesByName.valueAt(j);
                if (applicationInfo.packageName.equals(serviceRecord.appInfo.packageName)) {
                    serviceRecord.appInfo = applicationInfo;
                    serviceRecord.serviceInfo.applicationInfo = applicationInfo;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void serviceForegroundCrash(ProcessRecord app, CharSequence serviceRecord) {
        ActivityManagerService activityManagerService = this.mAm;
        int i = app.uid;
        int i2 = app.pid;
        String str = app.info.packageName;
        int i3 = app.userId;
        activityManagerService.crashApplication(i, i2, str, i3, "Context.startForegroundService() did not then call Service.startForeground(): " + ((Object) serviceRecord));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleServiceTimeoutLocked(ProcessRecord proc) {
        if (proc.executingServices.size() == 0 || proc.thread == null) {
            return;
        }
        Message msg = this.mAm.mHandler.obtainMessage(12);
        msg.obj = proc;
        this.mAm.mHandler.sendMessageDelayed(msg, proc.execServicesFg ? 20000L : 200000L);
    }

    void scheduleServiceForegroundTransitionTimeoutLocked(ServiceRecord r) {
        if (r.app.executingServices.size() == 0 || r.app.thread == null) {
            return;
        }
        Message msg = this.mAm.mHandler.obtainMessage(66);
        msg.obj = r;
        r.fgWaiting = true;
        this.mAm.mHandler.sendMessageDelayed(msg, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class ServiceDumper {
        private final String[] args;
        private final boolean dumpAll;
        private final String dumpPackage;
        private final FileDescriptor fd;
        private final PrintWriter pw;
        final /* synthetic */ ActiveServices this$0;
        private final ArrayList<ServiceRecord> services = new ArrayList<>();
        private final long nowReal = SystemClock.elapsedRealtime();
        private boolean needSep = false;
        private boolean printedAnything = false;
        private boolean printed = false;
        private final ActivityManagerService.ItemMatcher matcher = new ActivityManagerService.ItemMatcher();

        ServiceDumper(ActiveServices this$0, FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage) {
            ActiveServices activeServices = this$0;
            this.this$0 = activeServices;
            int i = 0;
            this.fd = fd;
            this.pw = pw;
            this.args = args;
            this.dumpAll = dumpAll;
            this.dumpPackage = dumpPackage;
            this.matcher.build(args, opti);
            int[] users = activeServices.mAm.mUserController.getUsers();
            int length = users.length;
            int i2 = 0;
            while (i2 < length) {
                int user = users[i2];
                ServiceMap smap = activeServices.getServiceMapLocked(user);
                if (smap.mServicesByName.size() > 0) {
                    for (int si = i; si < smap.mServicesByName.size(); si++) {
                        ServiceRecord r = smap.mServicesByName.valueAt(si);
                        if (this.matcher.match(r, r.name) && (dumpPackage == null || dumpPackage.equals(r.appInfo.packageName))) {
                            this.services.add(r);
                        }
                    }
                }
                i2++;
                activeServices = this$0;
                i = 0;
            }
        }

        private void dumpHeaderLocked() {
            this.pw.println("ACTIVITY MANAGER SERVICES (dumpsys activity services)");
            if (this.this$0.mLastAnrDump != null) {
                this.pw.println("  Last ANR service:");
                this.pw.print(this.this$0.mLastAnrDump);
                this.pw.println();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void dumpLocked() {
            dumpHeaderLocked();
            try {
                int[] users = this.this$0.mAm.mUserController.getUsers();
                for (int user : users) {
                    int serviceIdx = 0;
                    while (serviceIdx < this.services.size() && this.services.get(serviceIdx).userId != user) {
                        serviceIdx++;
                    }
                    this.printed = false;
                    if (serviceIdx < this.services.size()) {
                        this.needSep = false;
                        while (serviceIdx < this.services.size()) {
                            ServiceRecord r = this.services.get(serviceIdx);
                            serviceIdx++;
                            if (r.userId != user) {
                                break;
                            }
                            dumpServiceLocalLocked(r);
                        }
                        this.needSep |= this.printed;
                    }
                    dumpUserRemainsLocked(user);
                }
            } catch (Exception e) {
                Slog.w("ActivityManager", "Exception in dumpServicesLocked", e);
            }
            dumpRemainsLocked();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void dumpWithClient() {
            synchronized (this.this$0.mAm) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    dumpHeaderLocked();
                } finally {
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
            try {
                int[] users = this.this$0.mAm.mUserController.getUsers();
                for (int user : users) {
                    int serviceIdx = 0;
                    while (serviceIdx < this.services.size() && this.services.get(serviceIdx).userId != user) {
                        serviceIdx++;
                    }
                    this.printed = false;
                    if (serviceIdx < this.services.size()) {
                        this.needSep = false;
                        while (serviceIdx < this.services.size()) {
                            ServiceRecord r = this.services.get(serviceIdx);
                            serviceIdx++;
                            if (r.userId != user) {
                                break;
                            }
                            synchronized (this.this$0.mAm) {
                                ActivityManagerService.boostPriorityForLockedSection();
                                dumpServiceLocalLocked(r);
                            }
                            ActivityManagerService.resetPriorityAfterLockedSection();
                            dumpServiceClient(r);
                        }
                        this.needSep |= this.printed;
                    }
                    synchronized (this.this$0.mAm) {
                        ActivityManagerService.boostPriorityForLockedSection();
                        dumpUserRemainsLocked(user);
                    }
                }
            } catch (Exception e) {
                Slog.w("ActivityManager", "Exception in dumpServicesLocked", e);
            }
            synchronized (this.this$0.mAm) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    dumpRemainsLocked();
                } finally {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }

        private void dumpUserHeaderLocked(int user) {
            if (!this.printed) {
                if (this.printedAnything) {
                    this.pw.println();
                }
                PrintWriter printWriter = this.pw;
                printWriter.println("  User " + user + " active services:");
                this.printed = true;
            }
            this.printedAnything = true;
            if (this.needSep) {
                this.pw.println();
            }
        }

        private void dumpServiceLocalLocked(ServiceRecord r) {
            dumpUserHeaderLocked(r.userId);
            this.pw.print("  * ");
            this.pw.println(r);
            if (this.dumpAll) {
                r.dump(this.pw, "    ");
                this.needSep = true;
                return;
            }
            this.pw.print("    app=");
            this.pw.println(r.app);
            this.pw.print("    created=");
            TimeUtils.formatDuration(r.createRealTime, this.nowReal, this.pw);
            this.pw.print(" started=");
            this.pw.print(r.startRequested);
            this.pw.print(" connections=");
            this.pw.println(r.connections.size());
            if (r.connections.size() > 0) {
                this.pw.println("    Connections:");
                for (int conni = 0; conni < r.connections.size(); conni++) {
                    ArrayList<ConnectionRecord> clist = r.connections.valueAt(conni);
                    for (int i = 0; i < clist.size(); i++) {
                        ConnectionRecord conn = clist.get(i);
                        this.pw.print("      ");
                        this.pw.print(conn.binding.intent.intent.getIntent().toShortString(false, false, false, false));
                        this.pw.print(" -> ");
                        ProcessRecord proc = conn.binding.client;
                        this.pw.println(proc != null ? proc.toShortString() : "null");
                    }
                }
            }
        }

        private void dumpServiceClient(ServiceRecord r) {
            IApplicationThread thread;
            ProcessRecord proc = r.app;
            if (proc == null || (thread = proc.thread) == null) {
                return;
            }
            this.pw.println("    Client:");
            this.pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                thread.dumpService(tp.getWriteFd(), r, this.args);
                tp.setBufferPrefix("      ");
                tp.go(this.fd, 2000L);
                tp.kill();
            } catch (RemoteException e) {
                this.pw.println("      Got a RemoteException while dumping the service");
            } catch (IOException e2) {
                PrintWriter printWriter = this.pw;
                printWriter.println("      Failure while dumping the service: " + e2);
            }
            this.needSep = true;
        }

        private void dumpUserRemainsLocked(int user) {
            ServiceMap smap = this.this$0.getServiceMapLocked(user);
            this.printed = false;
            int SN = smap.mDelayedStartList.size();
            for (int si = 0; si < SN; si++) {
                ServiceRecord r = smap.mDelayedStartList.get(si);
                if (this.matcher.match(r, r.name) && (this.dumpPackage == null || this.dumpPackage.equals(r.appInfo.packageName))) {
                    if (!this.printed) {
                        if (this.printedAnything) {
                            this.pw.println();
                        }
                        PrintWriter printWriter = this.pw;
                        printWriter.println("  User " + user + " delayed start services:");
                        this.printed = true;
                    }
                    this.printedAnything = true;
                    this.pw.print("  * Delayed start ");
                    this.pw.println(r);
                }
            }
            this.printed = false;
            int SN2 = smap.mStartingBackground.size();
            for (int si2 = 0; si2 < SN2; si2++) {
                ServiceRecord r2 = smap.mStartingBackground.get(si2);
                if (this.matcher.match(r2, r2.name) && (this.dumpPackage == null || this.dumpPackage.equals(r2.appInfo.packageName))) {
                    if (!this.printed) {
                        if (this.printedAnything) {
                            this.pw.println();
                        }
                        PrintWriter printWriter2 = this.pw;
                        printWriter2.println("  User " + user + " starting in background:");
                        this.printed = true;
                    }
                    this.printedAnything = true;
                    this.pw.print("  * Starting bg ");
                    this.pw.println(r2);
                }
            }
        }

        private void dumpRemainsLocked() {
            if (this.this$0.mPendingServices.size() > 0) {
                this.printed = false;
                for (int i = 0; i < this.this$0.mPendingServices.size(); i++) {
                    ServiceRecord r = this.this$0.mPendingServices.get(i);
                    if (this.matcher.match(r, r.name) && (this.dumpPackage == null || this.dumpPackage.equals(r.appInfo.packageName))) {
                        this.printedAnything = true;
                        if (!this.printed) {
                            if (this.needSep) {
                                this.pw.println();
                            }
                            this.needSep = true;
                            this.pw.println("  Pending services:");
                            this.printed = true;
                        }
                        this.pw.print("  * Pending ");
                        this.pw.println(r);
                        r.dump(this.pw, "    ");
                    }
                }
                this.needSep = true;
            }
            if (this.this$0.mRestartingServices.size() > 0) {
                this.printed = false;
                for (int i2 = 0; i2 < this.this$0.mRestartingServices.size(); i2++) {
                    ServiceRecord r2 = this.this$0.mRestartingServices.get(i2);
                    if (this.matcher.match(r2, r2.name) && (this.dumpPackage == null || this.dumpPackage.equals(r2.appInfo.packageName))) {
                        this.printedAnything = true;
                        if (!this.printed) {
                            if (this.needSep) {
                                this.pw.println();
                            }
                            this.needSep = true;
                            this.pw.println("  Restarting services:");
                            this.printed = true;
                        }
                        this.pw.print("  * Restarting ");
                        this.pw.println(r2);
                        r2.dump(this.pw, "    ");
                    }
                }
                this.needSep = true;
            }
            if (this.this$0.mDestroyingServices.size() > 0) {
                this.printed = false;
                for (int i3 = 0; i3 < this.this$0.mDestroyingServices.size(); i3++) {
                    ServiceRecord r3 = this.this$0.mDestroyingServices.get(i3);
                    if (this.matcher.match(r3, r3.name) && (this.dumpPackage == null || this.dumpPackage.equals(r3.appInfo.packageName))) {
                        this.printedAnything = true;
                        if (!this.printed) {
                            if (this.needSep) {
                                this.pw.println();
                            }
                            this.needSep = true;
                            this.pw.println("  Destroying services:");
                            this.printed = true;
                        }
                        this.pw.print("  * Destroy ");
                        this.pw.println(r3);
                        r3.dump(this.pw, "    ");
                    }
                }
                this.needSep = true;
            }
            if (this.dumpAll) {
                this.printed = false;
                for (int ic = 0; ic < this.this$0.mServiceConnections.size(); ic++) {
                    ArrayList<ConnectionRecord> r4 = this.this$0.mServiceConnections.valueAt(ic);
                    for (int i4 = 0; i4 < r4.size(); i4++) {
                        ConnectionRecord cr = r4.get(i4);
                        if (this.matcher.match(cr.binding.service, cr.binding.service.name) && (this.dumpPackage == null || (cr.binding.client != null && this.dumpPackage.equals(cr.binding.client.info.packageName)))) {
                            this.printedAnything = true;
                            if (!this.printed) {
                                if (this.needSep) {
                                    this.pw.println();
                                }
                                this.needSep = true;
                                this.pw.println("  Connection bindings to services:");
                                this.printed = true;
                            }
                            this.pw.print("  * ");
                            this.pw.println(cr);
                            cr.dump(this.pw, "    ");
                        }
                    }
                }
            }
            if (this.matcher.all) {
                long nowElapsed = SystemClock.elapsedRealtime();
                int[] users = this.this$0.mAm.mUserController.getUsers();
                for (int user : users) {
                    boolean printedUser = false;
                    ServiceMap smap = this.this$0.mServiceMap.get(user);
                    if (smap != null) {
                        for (int i5 = smap.mActiveForegroundApps.size() - 1; i5 >= 0; i5--) {
                            ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i5);
                            if (this.dumpPackage == null || this.dumpPackage.equals(aa.mPackageName)) {
                                if (!printedUser) {
                                    printedUser = true;
                                    this.printedAnything = true;
                                    if (this.needSep) {
                                        this.pw.println();
                                    }
                                    this.needSep = true;
                                    this.pw.print("Active foreground apps - user ");
                                    this.pw.print(user);
                                    this.pw.println(":");
                                }
                                this.pw.print("  #");
                                this.pw.print(i5);
                                this.pw.print(": ");
                                this.pw.println(aa.mPackageName);
                                if (aa.mLabel != null) {
                                    this.pw.print("    mLabel=");
                                    this.pw.println(aa.mLabel);
                                }
                                this.pw.print("    mNumActive=");
                                this.pw.print(aa.mNumActive);
                                this.pw.print(" mAppOnTop=");
                                this.pw.print(aa.mAppOnTop);
                                this.pw.print(" mShownWhileTop=");
                                this.pw.print(aa.mShownWhileTop);
                                this.pw.print(" mShownWhileScreenOn=");
                                this.pw.println(aa.mShownWhileScreenOn);
                                this.pw.print("    mStartTime=");
                                TimeUtils.formatDuration(aa.mStartTime - nowElapsed, this.pw);
                                this.pw.print(" mStartVisibleTime=");
                                TimeUtils.formatDuration(aa.mStartVisibleTime - nowElapsed, this.pw);
                                this.pw.println();
                                if (aa.mEndTime != 0) {
                                    this.pw.print("    mEndTime=");
                                    TimeUtils.formatDuration(aa.mEndTime - nowElapsed, this.pw);
                                    this.pw.println();
                                }
                            }
                        }
                        if (smap.hasMessagesOrCallbacks()) {
                            if (this.needSep) {
                                this.pw.println();
                            }
                            this.printedAnything = true;
                            this.needSep = true;
                            this.pw.print("  Handler - user ");
                            this.pw.print(user);
                            this.pw.println(":");
                            smap.dumpMine(new PrintWriterPrinter(this.pw), "    ");
                        }
                    }
                }
            }
            if (!this.printedAnything) {
                this.pw.println("  (nothing)");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ServiceDumper newServiceDumperLocked(FileDescriptor fd, PrintWriter pw, String[] args, int opti, boolean dumpAll, String dumpPackage) {
        return new ServiceDumper(this, fd, pw, args, opti, dumpAll, dumpPackage);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        int i;
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                long outterToken = proto.start(fieldId);
                int[] users = this.mAm.mUserController.getUsers();
                int length = users.length;
                int i2 = 0;
                while (i2 < length) {
                    int user = users[i2];
                    ServiceMap smap = this.mServiceMap.get(user);
                    if (smap == null) {
                        i = i2;
                    } else {
                        long token = proto.start(2246267895809L);
                        proto.write(1120986464257L, user);
                        ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByName;
                        int i3 = 0;
                        while (i3 < alls.size()) {
                            alls.valueAt(i3).writeToProto(proto, 2246267895810L);
                            i3++;
                            i2 = i2;
                        }
                        i = i2;
                        proto.end(token);
                    }
                    i2 = i + 1;
                }
                proto.end(outterToken);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean dumpService(FileDescriptor fd, PrintWriter pw, String name, String[] args, int opti, boolean dumpAll) {
        int i;
        ArrayList<ServiceRecord> services = new ArrayList<>();
        Predicate<ServiceRecord> filter = DumpUtils.filterRecord(name);
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                int[] users = this.mAm.mUserController.getUsers();
                i = 0;
                for (int user : users) {
                    ServiceMap smap = this.mServiceMap.get(user);
                    if (smap != null) {
                        ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByName;
                        for (int i2 = 0; i2 < alls.size(); i2++) {
                            ServiceRecord r1 = alls.valueAt(i2);
                            if (filter.test(r1)) {
                                services.add(r1);
                            }
                        }
                    }
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        if (services.size() <= 0) {
            return false;
        }
        services.sort(Comparator.comparing(new Function() { // from class: com.android.server.am.-$$Lambda$Y_KRxxoOXfy-YceuDG7WHd46Y_I
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((ServiceRecord) obj).getComponentName();
            }
        }));
        boolean needSep = false;
        while (true) {
            int i3 = i;
            if (i3 < services.size()) {
                if (needSep) {
                    pw.println();
                }
                needSep = true;
                dumpService(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, fd, pw, services.get(i3), args, dumpAll);
                i = i3 + 1;
            } else {
                return true;
            }
        }
    }

    private void dumpService(String prefix, FileDescriptor fd, PrintWriter pw, ServiceRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                pw.print(prefix);
                pw.print("SERVICE ");
                pw.print(r.shortName);
                pw.print(" ");
                pw.print(Integer.toHexString(System.identityHashCode(r)));
                pw.print(" pid=");
                if (r.app != null) {
                    pw.println(r.app.pid);
                } else {
                    pw.println("(not running)");
                }
                if (dumpAll) {
                    r.dump(pw, innerPrefix);
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        if (r.app != null && r.app.thread != null) {
            pw.print(prefix);
            pw.println("  Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                r.app.thread.dumpService(tp.getWriteFd(), r, args);
                tp.setBufferPrefix(prefix + "    ");
                tp.go(fd);
                tp.kill();
            } catch (RemoteException e) {
                pw.println(prefix + "    Got a RemoteException while dumping the service");
            } catch (IOException e2) {
                pw.println(prefix + "    Failure while dumping the service: " + e2);
            }
        }
    }
}
