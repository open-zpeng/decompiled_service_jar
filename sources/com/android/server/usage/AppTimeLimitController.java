package com.android.server.usage;

import android.app.PendingIntent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
/* loaded from: classes.dex */
public class AppTimeLimitController {
    private static final boolean DEBUG = false;
    private static final long MAX_OBSERVER_PER_UID = 1000;
    private static final long ONE_MINUTE = 60000;
    private static final String TAG = "AppTimeLimitController";
    private final MyHandler mHandler;
    private OnLimitReachedListener mListener;
    private final Lock mLock = new Lock();
    @GuardedBy("mLock")
    private final SparseArray<UserData> mUsers = new SparseArray<>();

    /* loaded from: classes.dex */
    public interface OnLimitReachedListener {
        void onLimitReached(int i, int i2, long j, long j2, PendingIntent pendingIntent);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Lock {
        private Lock() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class UserData {
        private String currentForegroundedPackage;
        private long currentForegroundedTime;
        private SparseArray<TimeLimitGroup> groups;
        private SparseIntArray observerIdCounts;
        private ArrayMap<String, ArrayList<TimeLimitGroup>> packageMap;
        private int userId;

        private UserData(int userId) {
            this.packageMap = new ArrayMap<>();
            this.groups = new SparseArray<>();
            this.observerIdCounts = new SparseIntArray();
            this.userId = userId;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class TimeLimitGroup {
        PendingIntent callbackIntent;
        String currentPackage;
        int observerId;
        String[] packages;
        int requestingUid;
        long timeCurrentPackageStarted;
        long timeLimit;
        long timeRemaining;
        long timeRequested;
        int userId;

        TimeLimitGroup() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class MyHandler extends Handler {
        static final int MSG_CHECK_TIMEOUT = 1;
        static final int MSG_INFORM_LISTENER = 2;

        MyHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    AppTimeLimitController.this.checkTimeout((TimeLimitGroup) msg.obj);
                    return;
                case 2:
                    AppTimeLimitController.this.informListener((TimeLimitGroup) msg.obj);
                    return;
                default:
                    super.handleMessage(msg);
                    return;
            }
        }
    }

    public AppTimeLimitController(OnLimitReachedListener listener, Looper looper) {
        this.mHandler = new MyHandler(looper);
        this.mListener = listener;
    }

    @VisibleForTesting
    protected long getUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    @VisibleForTesting
    protected long getObserverPerUidLimit() {
        return 1000L;
    }

    @VisibleForTesting
    protected long getMinTimeLimit() {
        return 60000L;
    }

    private UserData getOrCreateUserDataLocked(int userId) {
        UserData userData = this.mUsers.get(userId);
        if (userData == null) {
            UserData userData2 = new UserData(userId);
            this.mUsers.put(userId, userData2);
            return userData2;
        }
        return userData;
    }

    public void onUserRemoved(int userId) {
        synchronized (this.mLock) {
            this.mUsers.remove(userId);
        }
    }

    public void addObserver(int requestingUid, int observerId, String[] packages, long timeLimit, PendingIntent callbackIntent, int userId) {
        if (timeLimit < getMinTimeLimit()) {
            throw new IllegalArgumentException("Time limit must be >= " + getMinTimeLimit());
        }
        synchronized (this.mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            removeObserverLocked(user, requestingUid, observerId, true);
            int observerIdCount = user.observerIdCounts.get(requestingUid, 0);
            if (observerIdCount < getObserverPerUidLimit()) {
                user.observerIdCounts.put(requestingUid, observerIdCount + 1);
                TimeLimitGroup group = new TimeLimitGroup();
                group.observerId = observerId;
                group.callbackIntent = callbackIntent;
                group.packages = packages;
                group.timeLimit = timeLimit;
                group.timeRemaining = group.timeLimit;
                group.timeRequested = getUptimeMillis();
                group.requestingUid = requestingUid;
                group.timeCurrentPackageStarted = -1L;
                group.userId = userId;
                user.groups.append(observerId, group);
                addGroupToPackageMapLocked(user, packages, group);
                if (user.currentForegroundedPackage != null && inPackageList(group.packages, user.currentForegroundedPackage)) {
                    group.timeCurrentPackageStarted = group.timeRequested;
                    group.currentPackage = user.currentForegroundedPackage;
                    if (group.timeRemaining > 0) {
                        postCheckTimeoutLocked(group, group.timeRemaining);
                    }
                }
            } else {
                throw new IllegalStateException("Too many observers added by uid " + requestingUid);
            }
        }
    }

    public void removeObserver(int requestingUid, int observerId, int userId) {
        synchronized (this.mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            removeObserverLocked(user, requestingUid, observerId, false);
        }
    }

    @VisibleForTesting
    TimeLimitGroup getObserverGroup(int observerId, int userId) {
        TimeLimitGroup timeLimitGroup;
        synchronized (this.mLock) {
            timeLimitGroup = (TimeLimitGroup) getOrCreateUserDataLocked(userId).groups.get(observerId);
        }
        return timeLimitGroup;
    }

    private static boolean inPackageList(String[] packages, String packageName) {
        return ArrayUtils.contains(packages, packageName);
    }

    @GuardedBy("mLock")
    private void removeObserverLocked(UserData user, int requestingUid, int observerId, boolean readding) {
        TimeLimitGroup group = (TimeLimitGroup) user.groups.get(observerId);
        if (group != null && group.requestingUid == requestingUid) {
            removeGroupFromPackageMapLocked(user, group);
            user.groups.remove(observerId);
            this.mHandler.removeMessages(1, group);
            int observerIdCount = user.observerIdCounts.get(requestingUid);
            if (observerIdCount > 1 || readding) {
                user.observerIdCounts.put(requestingUid, observerIdCount - 1);
            } else {
                user.observerIdCounts.delete(requestingUid);
            }
        }
    }

    public void moveToForeground(String packageName, String className, int userId) {
        synchronized (this.mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            user.currentForegroundedPackage = packageName;
            user.currentForegroundedTime = getUptimeMillis();
            maybeWatchForPackageLocked(user, packageName, user.currentForegroundedTime);
        }
    }

    public void moveToBackground(String packageName, String className, int userId) {
        int size;
        synchronized (this.mLock) {
            UserData user = getOrCreateUserDataLocked(userId);
            if (!TextUtils.equals(user.currentForegroundedPackage, packageName)) {
                Slog.w(TAG, "Eh? Last foregrounded package = " + user.currentForegroundedPackage + " and now backgrounded = " + packageName);
                return;
            }
            long stopTime = getUptimeMillis();
            ArrayList<TimeLimitGroup> groups = (ArrayList) user.packageMap.get(packageName);
            if (groups != null) {
                int size2 = groups.size();
                int i = 0;
                while (i < size2) {
                    TimeLimitGroup group = groups.get(i);
                    if (group.timeRemaining > 0) {
                        size = size2;
                        long startTime = Math.max(user.currentForegroundedTime, group.timeRequested);
                        long diff = stopTime - startTime;
                        group.timeRemaining -= diff;
                        if (group.timeRemaining <= 0) {
                            postInformListenerLocked(group);
                        }
                        group.currentPackage = null;
                        group.timeCurrentPackageStarted = -1L;
                        this.mHandler.removeMessages(1, group);
                    } else {
                        size = size2;
                    }
                    i++;
                    size2 = size;
                }
            }
            user.currentForegroundedPackage = null;
        }
    }

    private void postInformListenerLocked(TimeLimitGroup group) {
        this.mHandler.sendMessage(this.mHandler.obtainMessage(2, group));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void informListener(TimeLimitGroup group) {
        if (this.mListener != null) {
            this.mListener.onLimitReached(group.observerId, group.userId, group.timeLimit, group.timeLimit - group.timeRemaining, group.callbackIntent);
        }
        synchronized (this.mLock) {
            UserData user = getOrCreateUserDataLocked(group.userId);
            removeObserverLocked(user, group.requestingUid, group.observerId, false);
        }
    }

    @GuardedBy("mLock")
    private void maybeWatchForPackageLocked(UserData user, String packageName, long uptimeMillis) {
        ArrayList<TimeLimitGroup> groups = (ArrayList) user.packageMap.get(packageName);
        if (groups == null) {
            return;
        }
        int size = groups.size();
        for (int i = 0; i < size; i++) {
            TimeLimitGroup group = groups.get(i);
            if (group.timeRemaining > 0) {
                group.timeCurrentPackageStarted = uptimeMillis;
                group.currentPackage = packageName;
                postCheckTimeoutLocked(group, group.timeRemaining);
            }
        }
    }

    private void addGroupToPackageMapLocked(UserData user, String[] packages, TimeLimitGroup group) {
        for (int i = 0; i < packages.length; i++) {
            ArrayList<TimeLimitGroup> list = (ArrayList) user.packageMap.get(packages[i]);
            if (list == null) {
                list = new ArrayList<>();
                user.packageMap.put(packages[i], list);
            }
            list.add(group);
        }
    }

    private void removeGroupFromPackageMapLocked(UserData user, TimeLimitGroup group) {
        int mapSize = user.packageMap.size();
        for (int i = 0; i < mapSize; i++) {
            ArrayList<TimeLimitGroup> list = (ArrayList) user.packageMap.valueAt(i);
            list.remove(group);
        }
    }

    private void postCheckTimeoutLocked(TimeLimitGroup group, long timeout) {
        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(1, group), timeout);
    }

    void checkTimeout(TimeLimitGroup group) {
        synchronized (this.mLock) {
            UserData user = getOrCreateUserDataLocked(group.userId);
            if (user.groups.get(group.observerId) != group) {
                return;
            }
            if (group.timeRemaining <= 0) {
                return;
            }
            if (inPackageList(group.packages, user.currentForegroundedPackage)) {
                if (group.timeCurrentPackageStarted < 0) {
                    Slog.w(TAG, "startTime was not set correctly for " + group);
                }
                long timeInForeground = getUptimeMillis() - group.timeCurrentPackageStarted;
                if (group.timeRemaining <= timeInForeground) {
                    group.timeRemaining -= timeInForeground;
                    postInformListenerLocked(group);
                    group.timeCurrentPackageStarted = -1L;
                    group.currentPackage = null;
                } else {
                    postCheckTimeoutLocked(group, group.timeRemaining - timeInForeground);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw) {
        synchronized (this.mLock) {
            pw.println("\n  App Time Limits");
            int nUsers = this.mUsers.size();
            for (int i = 0; i < nUsers; i++) {
                UserData user = this.mUsers.valueAt(i);
                pw.print("   User ");
                pw.println(user.userId);
                int nGroups = user.groups.size();
                for (int j = 0; j < nGroups; j++) {
                    TimeLimitGroup group = (TimeLimitGroup) user.groups.valueAt(j);
                    pw.print("    Group id=");
                    pw.print(group.observerId);
                    pw.print(" timeLimit=");
                    pw.print(group.timeLimit);
                    pw.print(" remaining=");
                    pw.print(group.timeRemaining);
                    pw.print(" currentPackage=");
                    pw.print(group.currentPackage);
                    pw.print(" timeCurrentPkgStarted=");
                    pw.print(group.timeCurrentPackageStarted);
                    pw.print(" packages=");
                    pw.println(Arrays.toString(group.packages));
                }
                pw.println();
                pw.print("    currentForegroundedPackage=");
                pw.println(user.currentForegroundedPackage);
            }
        }
    }
}
