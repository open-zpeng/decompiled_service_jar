package com.android.server.am;

import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

/* loaded from: classes.dex */
public class BroadcastDispatcher {
    private static final String TAG = "BroadcastDispatcher";
    private AlarmManagerInternal mAlarm;
    private final BroadcastConstants mConstants;
    private BroadcastRecord mCurrentBroadcast;
    private final Handler mHandler;
    private final Object mLock;
    private final BroadcastQueue mQueue;
    final SparseIntArray mAlarmUids = new SparseIntArray();
    final AlarmManagerInternal.InFlightListener mAlarmListener = new AlarmManagerInternal.InFlightListener() { // from class: com.android.server.am.BroadcastDispatcher.1
        @Override // com.android.server.AlarmManagerInternal.InFlightListener
        public void broadcastAlarmPending(int recipientUid) {
            synchronized (BroadcastDispatcher.this.mLock) {
                int newCount = BroadcastDispatcher.this.mAlarmUids.get(recipientUid, 0) + 1;
                BroadcastDispatcher.this.mAlarmUids.put(recipientUid, newCount);
                int numEntries = BroadcastDispatcher.this.mDeferredBroadcasts.size();
                int i = 0;
                while (true) {
                    if (i >= numEntries) {
                        break;
                    } else if (recipientUid == ((Deferrals) BroadcastDispatcher.this.mDeferredBroadcasts.get(i)).uid) {
                        Deferrals d = (Deferrals) BroadcastDispatcher.this.mDeferredBroadcasts.remove(i);
                        BroadcastDispatcher.this.mAlarmBroadcasts.add(d);
                        break;
                    } else {
                        i++;
                    }
                }
            }
        }

        @Override // com.android.server.AlarmManagerInternal.InFlightListener
        public void broadcastAlarmComplete(int recipientUid) {
            synchronized (BroadcastDispatcher.this.mLock) {
                int newCount = BroadcastDispatcher.this.mAlarmUids.get(recipientUid, 0) - 1;
                if (newCount >= 0) {
                    BroadcastDispatcher.this.mAlarmUids.put(recipientUid, newCount);
                } else {
                    Slog.wtf(BroadcastDispatcher.TAG, "Undercount of broadcast alarms in flight for " + recipientUid);
                    BroadcastDispatcher.this.mAlarmUids.put(recipientUid, 0);
                }
                if (newCount <= 0) {
                    int numEntries = BroadcastDispatcher.this.mAlarmBroadcasts.size();
                    int i = 0;
                    while (true) {
                        if (i >= numEntries) {
                            break;
                        } else if (recipientUid == ((Deferrals) BroadcastDispatcher.this.mAlarmBroadcasts.get(i)).uid) {
                            Deferrals d = (Deferrals) BroadcastDispatcher.this.mAlarmBroadcasts.remove(i);
                            BroadcastDispatcher.insertLocked(BroadcastDispatcher.this.mDeferredBroadcasts, d);
                            break;
                        } else {
                            i++;
                        }
                    }
                }
            }
        }
    };
    final Runnable mScheduleRunnable = new Runnable() { // from class: com.android.server.am.BroadcastDispatcher.2
        @Override // java.lang.Runnable
        public void run() {
            synchronized (BroadcastDispatcher.this.mLock) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                    Slog.v(BroadcastDispatcher.TAG, "Deferral recheck of pending broadcasts");
                }
                BroadcastDispatcher.this.mQueue.scheduleBroadcastsLocked();
                BroadcastDispatcher.this.mRecheckScheduled = false;
            }
        }
    };
    private boolean mRecheckScheduled = false;
    private final ArrayList<BroadcastRecord> mOrderedBroadcasts = new ArrayList<>();
    private final ArrayList<Deferrals> mDeferredBroadcasts = new ArrayList<>();
    private final ArrayList<Deferrals> mAlarmBroadcasts = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class Deferrals {
        int alarmCount;
        final ArrayList<BroadcastRecord> broadcasts = new ArrayList<>();
        long deferUntil;
        long deferredAt;
        long deferredBy;
        final int uid;

        Deferrals(int uid, long now, long backoff, int count) {
            this.uid = uid;
            this.deferredAt = now;
            this.deferredBy = backoff;
            this.deferUntil = now + backoff;
            this.alarmCount = count;
        }

        void add(BroadcastRecord br) {
            this.broadcasts.add(br);
        }

        int size() {
            return this.broadcasts.size();
        }

        boolean isEmpty() {
            return this.broadcasts.isEmpty();
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            Iterator<BroadcastRecord> it = this.broadcasts.iterator();
            while (it.hasNext()) {
                BroadcastRecord br = it.next();
                br.writeToProto(proto, fieldId);
            }
        }

        void dumpLocked(Dumper d) {
            Iterator<BroadcastRecord> it = this.broadcasts.iterator();
            while (it.hasNext()) {
                BroadcastRecord br = it.next();
                d.dump(br);
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Deferrals{uid=");
            sb.append(this.uid);
            sb.append(", deferUntil=");
            sb.append(this.deferUntil);
            sb.append(", #broadcasts=");
            sb.append(this.broadcasts.size());
            sb.append("}");
            return sb.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class Dumper {
        final String mDumpPackage;
        String mHeading;
        String mLabel;
        int mOrdinal;
        final PrintWriter mPw;
        final String mQueueName;
        final SimpleDateFormat mSdf;
        boolean mPrinted = false;
        boolean mNeedSep = true;

        Dumper(PrintWriter pw, String queueName, String dumpPackage, SimpleDateFormat sdf) {
            this.mPw = pw;
            this.mQueueName = queueName;
            this.mDumpPackage = dumpPackage;
            this.mSdf = sdf;
        }

        void setHeading(String heading) {
            this.mHeading = heading;
            this.mPrinted = false;
        }

        void setLabel(String label) {
            this.mLabel = "  " + label + " " + this.mQueueName + " #";
            this.mOrdinal = 0;
        }

        boolean didPrint() {
            return this.mPrinted;
        }

        void dump(BroadcastRecord br) {
            String str = this.mDumpPackage;
            if (str == null || str.equals(br.callerPackage)) {
                if (!this.mPrinted) {
                    if (this.mNeedSep) {
                        this.mPw.println();
                    }
                    this.mPrinted = true;
                    this.mNeedSep = true;
                    PrintWriter printWriter = this.mPw;
                    printWriter.println("  " + this.mHeading + " [" + this.mQueueName + "]:");
                }
                PrintWriter printWriter2 = this.mPw;
                printWriter2.println(this.mLabel + this.mOrdinal + ":");
                this.mOrdinal = this.mOrdinal + 1;
                br.dump(this.mPw, "    ", this.mSdf);
            }
        }
    }

    public BroadcastDispatcher(BroadcastQueue queue, BroadcastConstants constants, Handler handler, Object lock) {
        this.mQueue = queue;
        this.mConstants = constants;
        this.mHandler = handler;
        this.mLock = lock;
    }

    public void start() {
        this.mAlarm = (AlarmManagerInternal) LocalServices.getService(AlarmManagerInternal.class);
        this.mAlarm.registerInFlightListener(this.mAlarmListener);
    }

    public boolean isEmpty() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mCurrentBroadcast == null && this.mOrderedBroadcasts.isEmpty() && isDeferralsListEmpty(this.mDeferredBroadcasts) && isDeferralsListEmpty(this.mAlarmBroadcasts);
        }
        return z;
    }

    private static int pendingInDeferralsList(ArrayList<Deferrals> list) {
        int pending = 0;
        int numEntries = list.size();
        for (int i = 0; i < numEntries; i++) {
            pending += list.get(i).size();
        }
        return pending;
    }

    private static boolean isDeferralsListEmpty(ArrayList<Deferrals> list) {
        return pendingInDeferralsList(list) == 0;
    }

    public String describeStateLocked() {
        StringBuilder sb = new StringBuilder(128);
        if (this.mCurrentBroadcast != null) {
            sb.append("1 in flight, ");
        }
        sb.append(this.mOrderedBroadcasts.size());
        sb.append(" ordered");
        int n = pendingInDeferralsList(this.mAlarmBroadcasts);
        if (n > 0) {
            sb.append(", ");
            sb.append(n);
            sb.append(" deferrals in alarm recipients");
        }
        int n2 = pendingInDeferralsList(this.mDeferredBroadcasts);
        if (n2 > 0) {
            sb.append(", ");
            sb.append(n2);
            sb.append(" deferred");
        }
        return sb.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void enqueueOrderedBroadcastLocked(BroadcastRecord r) {
        this.mOrderedBroadcasts.add(r);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public BroadcastRecord replaceBroadcastLocked(BroadcastRecord r, String typeForLogging) {
        BroadcastRecord old = replaceBroadcastLocked(this.mOrderedBroadcasts, r, typeForLogging);
        if (old == null) {
            old = replaceDeferredBroadcastLocked(this.mAlarmBroadcasts, r, typeForLogging);
        }
        if (old == null) {
            return replaceDeferredBroadcastLocked(this.mDeferredBroadcasts, r, typeForLogging);
        }
        return old;
    }

    private BroadcastRecord replaceDeferredBroadcastLocked(ArrayList<Deferrals> list, BroadcastRecord r, String typeForLogging) {
        int numEntries = list.size();
        for (int i = 0; i < numEntries; i++) {
            Deferrals d = list.get(i);
            BroadcastRecord old = replaceBroadcastLocked(d.broadcasts, r, typeForLogging);
            if (old != null) {
                return old;
            }
        }
        return null;
    }

    private BroadcastRecord replaceBroadcastLocked(ArrayList<BroadcastRecord> list, BroadcastRecord r, String typeForLogging) {
        Intent intent = r.intent;
        for (int i = list.size() - 1; i >= 0; i--) {
            BroadcastRecord old = list.get(i);
            if (old.userId == r.userId && intent.filterEquals(old.intent)) {
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST) {
                    Slog.v(TAG, "***** Replacing " + typeForLogging + " [" + this.mQueue.mQueueName + "]: " + intent);
                }
                r.deferred = old.deferred;
                list.set(i, r);
                return old;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean cleanupDisabledPackageReceiversLocked(String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        BroadcastRecord broadcastRecord;
        boolean didSomething = cleanupBroadcastListDisabledReceiversLocked(this.mOrderedBroadcasts, packageName, filterByClasses, userId, doit);
        if (doit || !didSomething) {
            didSomething |= cleanupDeferralsListDisabledReceiversLocked(this.mAlarmBroadcasts, packageName, filterByClasses, userId, doit);
        }
        if (doit || !didSomething) {
            didSomething |= cleanupDeferralsListDisabledReceiversLocked(this.mDeferredBroadcasts, packageName, filterByClasses, userId, doit);
        }
        if ((doit || !didSomething) && (broadcastRecord = this.mCurrentBroadcast) != null) {
            return didSomething | broadcastRecord.cleanupDisabledPackageReceiversLocked(packageName, filterByClasses, userId, doit);
        }
        return didSomething;
    }

    private boolean cleanupDeferralsListDisabledReceiversLocked(ArrayList<Deferrals> list, String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        boolean didSomething = false;
        Iterator<Deferrals> it = list.iterator();
        while (it.hasNext()) {
            Deferrals d = it.next();
            didSomething = cleanupBroadcastListDisabledReceiversLocked(d.broadcasts, packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }
        return didSomething;
    }

    private boolean cleanupBroadcastListDisabledReceiversLocked(ArrayList<BroadcastRecord> list, String packageName, Set<String> filterByClasses, int userId, boolean doit) {
        boolean didSomething = false;
        Iterator<BroadcastRecord> it = list.iterator();
        while (it.hasNext()) {
            BroadcastRecord br = it.next();
            didSomething |= br.cleanupDisabledPackageReceiversLocked(packageName, filterByClasses, userId, doit);
            if (!doit && didSomething) {
                return true;
            }
        }
        return didSomething;
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        BroadcastRecord broadcastRecord = this.mCurrentBroadcast;
        if (broadcastRecord != null) {
            broadcastRecord.writeToProto(proto, fieldId);
        }
        Iterator<Deferrals> it = this.mAlarmBroadcasts.iterator();
        while (it.hasNext()) {
            Deferrals d = it.next();
            d.writeToProto(proto, fieldId);
        }
        Iterator<BroadcastRecord> it2 = this.mOrderedBroadcasts.iterator();
        while (it2.hasNext()) {
            BroadcastRecord br = it2.next();
            br.writeToProto(proto, fieldId);
        }
        Iterator<Deferrals> it3 = this.mDeferredBroadcasts.iterator();
        while (it3.hasNext()) {
            Deferrals d2 = it3.next();
            d2.writeToProto(proto, fieldId);
        }
    }

    public BroadcastRecord getActiveBroadcastLocked() {
        return this.mCurrentBroadcast;
    }

    public BroadcastRecord getNextBroadcastLocked(long now) {
        BroadcastRecord broadcastRecord = this.mCurrentBroadcast;
        if (broadcastRecord != null) {
            return broadcastRecord;
        }
        boolean someQueued = !this.mOrderedBroadcasts.isEmpty();
        BroadcastRecord next = null;
        if (!this.mAlarmBroadcasts.isEmpty()) {
            next = popLocked(this.mAlarmBroadcasts);
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL && next != null) {
                Slog.i(TAG, "Next broadcast from alarm targets: " + next);
            }
        }
        if (next == null && !this.mDeferredBroadcasts.isEmpty()) {
            int i = 0;
            while (true) {
                if (i >= this.mDeferredBroadcasts.size()) {
                    break;
                }
                Deferrals d = this.mDeferredBroadcasts.get(i);
                if (now < d.deferUntil && someQueued) {
                    break;
                } else if (d.broadcasts.size() > 0) {
                    BroadcastRecord next2 = d.broadcasts.remove(0);
                    next = next2;
                    this.mDeferredBroadcasts.remove(i);
                    d.deferredBy = calculateDeferral(d.deferredBy);
                    d.deferUntil += d.deferredBy;
                    insertLocked(this.mDeferredBroadcasts, d);
                    if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                        Slog.i(TAG, "Next broadcast from deferrals " + next + ", deferUntil now " + d.deferUntil);
                    }
                } else {
                    i++;
                }
            }
        }
        if (next == null && someQueued) {
            BroadcastRecord next3 = this.mOrderedBroadcasts.remove(0);
            next = next3;
            if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                Slog.i(TAG, "Next broadcast from main queue: " + next);
            }
        }
        this.mCurrentBroadcast = next;
        return next;
    }

    public void retireBroadcastLocked(BroadcastRecord r) {
        if (r != this.mCurrentBroadcast) {
            Slog.wtf(TAG, "Retiring broadcast " + r + " doesn't match current outgoing " + this.mCurrentBroadcast);
        }
        this.mCurrentBroadcast = null;
    }

    public boolean isDeferringLocked(int uid) {
        Deferrals d = findUidLocked(uid);
        if (d == null || !d.broadcasts.isEmpty() || SystemClock.uptimeMillis() < d.deferUntil) {
            return d != null;
        }
        if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
            Slog.i(TAG, "No longer deferring broadcasts to uid " + d.uid);
        }
        removeDeferral(d);
        return false;
    }

    public void startDeferring(int uid) {
        synchronized (this.mLock) {
            Deferrals d = findUidLocked(uid);
            if (d == null) {
                long now = SystemClock.uptimeMillis();
                Deferrals d2 = new Deferrals(uid, now, this.mConstants.DEFERRAL, this.mAlarmUids.get(uid, 0));
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i(TAG, "Now deferring broadcasts to " + uid + " until " + d2.deferUntil);
                }
                if (d2.alarmCount == 0) {
                    insertLocked(this.mDeferredBroadcasts, d2);
                    scheduleDeferralCheckLocked(true);
                } else {
                    this.mAlarmBroadcasts.add(d2);
                }
            } else {
                d.deferredBy = this.mConstants.DEFERRAL;
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i(TAG, "Uid " + uid + " slow again, deferral interval reset to " + d.deferredBy);
                }
            }
        }
    }

    public void addDeferredBroadcast(int uid, BroadcastRecord br) {
        if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
            Slog.i(TAG, "Enqueuing deferred broadcast " + br);
        }
        synchronized (this.mLock) {
            Deferrals d = findUidLocked(uid);
            if (d == null) {
                Slog.wtf(TAG, "Adding deferred broadcast but not tracking " + uid);
            } else if (br == null) {
                Slog.wtf(TAG, "Deferring null broadcast to " + uid);
            } else {
                br.deferred = true;
                d.add(br);
            }
        }
    }

    public void scheduleDeferralCheckLocked(boolean force) {
        if ((force || !this.mRecheckScheduled) && !this.mDeferredBroadcasts.isEmpty()) {
            Deferrals d = this.mDeferredBroadcasts.get(0);
            if (!d.broadcasts.isEmpty()) {
                this.mHandler.removeCallbacks(this.mScheduleRunnable);
                this.mHandler.postAtTime(this.mScheduleRunnable, d.deferUntil);
                this.mRecheckScheduled = true;
                if (ActivityManagerDebugConfig.DEBUG_BROADCAST_DEFERRAL) {
                    Slog.i(TAG, "Scheduling deferred broadcast recheck at " + d.deferUntil);
                }
            }
        }
    }

    public void cancelDeferralsLocked() {
        zeroDeferralTimes(this.mAlarmBroadcasts);
        zeroDeferralTimes(this.mDeferredBroadcasts);
    }

    private static void zeroDeferralTimes(ArrayList<Deferrals> list) {
        int num = list.size();
        for (int i = 0; i < num; i++) {
            Deferrals d = list.get(i);
            d.deferredBy = 0L;
            d.deferUntil = 0L;
        }
    }

    private Deferrals findUidLocked(int uid) {
        Deferrals d = findUidLocked(uid, this.mDeferredBroadcasts);
        if (d == null) {
            return findUidLocked(uid, this.mAlarmBroadcasts);
        }
        return d;
    }

    private boolean removeDeferral(Deferrals d) {
        boolean didRemove = this.mDeferredBroadcasts.remove(d);
        if (!didRemove) {
            return this.mAlarmBroadcasts.remove(d);
        }
        return didRemove;
    }

    private static Deferrals findUidLocked(int uid, ArrayList<Deferrals> list) {
        int numElements = list.size();
        for (int i = 0; i < numElements; i++) {
            Deferrals d = list.get(i);
            if (uid == d.uid) {
                return d;
            }
        }
        return null;
    }

    private static BroadcastRecord popLocked(ArrayList<Deferrals> list) {
        Deferrals d = list.get(0);
        if (d.broadcasts.isEmpty()) {
            return null;
        }
        return d.broadcasts.remove(0);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void insertLocked(ArrayList<Deferrals> list, Deferrals d) {
        int numElements = list.size();
        int i = 0;
        while (i < numElements && d.deferUntil >= list.get(i).deferUntil) {
            i++;
        }
        list.add(i, d);
    }

    private long calculateDeferral(long previous) {
        return Math.max(this.mConstants.DEFERRAL_FLOOR, ((float) previous) * this.mConstants.DEFERRAL_DECAY_FACTOR);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dumpLocked(PrintWriter pw, String dumpPackage, String queueName, SimpleDateFormat sdf) {
        Dumper dumper = new Dumper(pw, queueName, dumpPackage, sdf);
        dumper.setHeading("Currently in flight");
        dumper.setLabel("In-Flight Ordered Broadcast");
        BroadcastRecord broadcastRecord = this.mCurrentBroadcast;
        if (broadcastRecord != null) {
            dumper.dump(broadcastRecord);
        } else {
            pw.println("  (null)");
        }
        dumper.setHeading("Active ordered broadcasts");
        dumper.setLabel("Active Ordered Broadcast");
        Iterator<Deferrals> it = this.mAlarmBroadcasts.iterator();
        while (it.hasNext()) {
            Deferrals d = it.next();
            d.dumpLocked(dumper);
        }
        boolean printed = false | dumper.didPrint();
        Iterator<BroadcastRecord> it2 = this.mOrderedBroadcasts.iterator();
        while (it2.hasNext()) {
            BroadcastRecord br = it2.next();
            dumper.dump(br);
        }
        boolean printed2 = printed | dumper.didPrint();
        dumper.setHeading("Deferred ordered broadcasts");
        dumper.setLabel("Deferred Ordered Broadcast");
        Iterator<Deferrals> it3 = this.mDeferredBroadcasts.iterator();
        while (it3.hasNext()) {
            Deferrals d2 = it3.next();
            d2.dumpLocked(dumper);
        }
        return printed2 | dumper.didPrint();
    }
}
