package com.android.server.usage;

import android.app.usage.ConfigurationStats;
import android.app.usage.EventList;
import android.app.usage.EventStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.am.AssistDataRequester;
import com.android.server.audio.AudioService;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.usage.IntervalStats;
import com.android.server.usage.UsageStatsDatabase;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.wm.xpWindowManagerService;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class UserUsageStatsService {
    private static final boolean DEBUG = false;
    private static final String TAG = "UsageStatsService";
    private static final int sDateFormatFlags = 131093;
    private final Context mContext;
    private final UsageStatsDatabase mDatabase;
    private String mLastBackgroundedPackage;
    private final StatsUpdatedListener mListener;
    private final String mLogPrefix;
    private final int mUserId;
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final long[] INTERVAL_LENGTH = {86400000, 604800000, UnixCalendar.MONTH_IN_MILLIS, UnixCalendar.YEAR_IN_MILLIS};
    private static final UsageStatsDatabase.StatCombiner<UsageStats> sUsageStatsCombiner = new UsageStatsDatabase.StatCombiner<UsageStats>() { // from class: com.android.server.usage.UserUsageStatsService.1
        @Override // com.android.server.usage.UsageStatsDatabase.StatCombiner
        public void combine(IntervalStats stats, boolean mutable, List<UsageStats> accResult) {
            if (!mutable) {
                accResult.addAll(stats.packageStats.values());
                return;
            }
            int statCount = stats.packageStats.size();
            for (int i = 0; i < statCount; i++) {
                accResult.add(new UsageStats(stats.packageStats.valueAt(i)));
            }
        }
    };
    private static final UsageStatsDatabase.StatCombiner<ConfigurationStats> sConfigStatsCombiner = new UsageStatsDatabase.StatCombiner<ConfigurationStats>() { // from class: com.android.server.usage.UserUsageStatsService.2
        @Override // com.android.server.usage.UsageStatsDatabase.StatCombiner
        public void combine(IntervalStats stats, boolean mutable, List<ConfigurationStats> accResult) {
            if (!mutable) {
                accResult.addAll(stats.configurations.values());
                return;
            }
            int configCount = stats.configurations.size();
            for (int i = 0; i < configCount; i++) {
                accResult.add(new ConfigurationStats(stats.configurations.valueAt(i)));
            }
        }
    };
    private static final UsageStatsDatabase.StatCombiner<EventStats> sEventStatsCombiner = new UsageStatsDatabase.StatCombiner<EventStats>() { // from class: com.android.server.usage.UserUsageStatsService.3
        @Override // com.android.server.usage.UsageStatsDatabase.StatCombiner
        public void combine(IntervalStats stats, boolean mutable, List<EventStats> accResult) {
            stats.addEventStatsTo(accResult);
        }
    };
    private boolean mStatsChanged = false;
    private final UnixCalendar mDailyExpiryDate = new UnixCalendar(0);
    private final IntervalStats[] mCurrentStats = new IntervalStats[4];

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface StatsUpdatedListener {
        void onNewUpdate(int i);

        void onStatsReloaded();

        void onStatsUpdated();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UserUsageStatsService(Context context, int userId, File usageStatsDir, StatsUpdatedListener listener) {
        this.mContext = context;
        this.mDatabase = new UsageStatsDatabase(usageStatsDir);
        this.mListener = listener;
        this.mLogPrefix = "User[" + Integer.toString(userId) + "] ";
        this.mUserId = userId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void init(long currentTimeMillis) {
        IntervalStats[] intervalStatsArr;
        this.mDatabase.init(currentTimeMillis);
        int nullCount = 0;
        for (int nullCount2 = 0; nullCount2 < this.mCurrentStats.length; nullCount2++) {
            this.mCurrentStats[nullCount2] = this.mDatabase.getLatestUsageStats(nullCount2);
            if (this.mCurrentStats[nullCount2] == null) {
                nullCount++;
            }
        }
        if (nullCount > 0) {
            if (nullCount != this.mCurrentStats.length) {
                Slog.w(TAG, this.mLogPrefix + "Some stats have no latest available");
            }
            loadActiveStats(currentTimeMillis);
        } else {
            updateRolloverDeadline();
        }
        for (IntervalStats stat : this.mCurrentStats) {
            int pkgCount = stat.packageStats.size();
            for (int i = 0; i < pkgCount; i++) {
                UsageStats pkgStats = stat.packageStats.valueAt(i);
                if (pkgStats.mLastEvent == 1 || pkgStats.mLastEvent == 4) {
                    stat.update(pkgStats.mPackageName, stat.lastTimeSaved, 3);
                    notifyStatsChanged();
                }
            }
            stat.updateConfigurationStats(null, stat.lastTimeSaved);
        }
        if (this.mDatabase.isNewUpdate()) {
            notifyNewUpdate();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onTimeChanged(long oldTime, long newTime) {
        persistActiveStats();
        this.mDatabase.onTimeChanged(newTime - oldTime);
        loadActiveStats(newTime);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reportEvent(UsageEvents.Event event) {
        if (event.mTimeStamp >= this.mDailyExpiryDate.getTimeInMillis()) {
            rolloverStats(event.mTimeStamp);
        }
        int i = 0;
        IntervalStats currentDailyStats = this.mCurrentStats[0];
        Configuration newFullConfig = event.mConfiguration;
        if (event.mEventType == 5 && currentDailyStats.activeConfiguration != null) {
            event.mConfiguration = Configuration.generateDelta(currentDailyStats.activeConfiguration, newFullConfig);
        }
        if (currentDailyStats.events == null) {
            currentDailyStats.events = new EventList();
        }
        if (event.mEventType != 6) {
            currentDailyStats.events.insert(event);
        }
        boolean incrementAppLaunch = false;
        if (event.mEventType == 1) {
            if (event.mPackage != null && !event.mPackage.equals(this.mLastBackgroundedPackage)) {
                incrementAppLaunch = true;
            }
        } else if (event.mEventType == 2 && event.mPackage != null) {
            this.mLastBackgroundedPackage = event.mPackage;
        }
        IntervalStats[] intervalStatsArr = this.mCurrentStats;
        int length = intervalStatsArr.length;
        int i2 = 0;
        while (i2 < length) {
            IntervalStats stats = intervalStatsArr[i2];
            int i3 = event.mEventType;
            if (i3 == 5) {
                stats.updateConfigurationStats(newFullConfig, event.mTimeStamp);
            } else if (i3 == 9) {
                stats.updateChooserCounts(event.mPackage, event.mContentType, event.mAction);
                String[] annotations = event.mContentAnnotations;
                if (annotations != null) {
                    int length2 = annotations.length;
                    for (int i4 = i; i4 < length2; i4++) {
                        String annotation = annotations[i4];
                        stats.updateChooserCounts(event.mPackage, annotation, event.mAction);
                    }
                }
            } else {
                switch (i3) {
                    case 15:
                        stats.updateScreenInteractive(event.mTimeStamp);
                        continue;
                    case 16:
                        stats.updateScreenNonInteractive(event.mTimeStamp);
                        continue;
                    case 17:
                        stats.updateKeyguardShown(event.mTimeStamp);
                        continue;
                    case 18:
                        stats.updateKeyguardHidden(event.mTimeStamp);
                        continue;
                    default:
                        stats.update(event.mPackage, event.mTimeStamp, event.mEventType);
                        if (incrementAppLaunch) {
                            stats.incrementAppLaunchCount(event.mPackage);
                            break;
                        } else {
                            continue;
                        }
                }
            }
            i2++;
            i = 0;
        }
        notifyStatsChanged();
    }

    private <T> List<T> queryStats(int intervalType, long beginTime, long endTime, UsageStatsDatabase.StatCombiner<T> combiner) {
        int intervalType2;
        if (intervalType == 4) {
            int intervalType3 = this.mDatabase.findBestFitBucket(beginTime, endTime);
            if (intervalType3 < 0) {
                intervalType3 = 0;
            }
            intervalType2 = intervalType3;
        } else {
            intervalType2 = intervalType;
        }
        if (intervalType2 < 0 || intervalType2 >= this.mCurrentStats.length) {
            return null;
        }
        IntervalStats currentStats = this.mCurrentStats[intervalType2];
        if (beginTime >= currentStats.endTime) {
            return null;
        }
        long truncatedEndTime = Math.min(currentStats.beginTime, endTime);
        List<T> results = this.mDatabase.queryUsageStats(intervalType2, beginTime, truncatedEndTime, combiner);
        if (beginTime < currentStats.endTime && endTime > currentStats.beginTime) {
            if (results == null) {
                results = new ArrayList();
            }
            combiner.combine(currentStats, true, results);
        }
        return results;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<UsageStats> queryUsageStats(int bucketType, long beginTime, long endTime) {
        return queryStats(bucketType, beginTime, endTime, sUsageStatsCombiner);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<ConfigurationStats> queryConfigurationStats(int bucketType, long beginTime, long endTime) {
        return queryStats(bucketType, beginTime, endTime, sConfigStatsCombiner);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<EventStats> queryEventStats(int bucketType, long beginTime, long endTime) {
        return queryStats(bucketType, beginTime, endTime, sEventStatsCombiner);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsageEvents queryEvents(final long beginTime, final long endTime, final boolean obfuscateInstantApps) {
        final ArraySet<String> names = new ArraySet<>();
        List<UsageEvents.Event> results = queryStats(0, beginTime, endTime, new UsageStatsDatabase.StatCombiner<UsageEvents.Event>() { // from class: com.android.server.usage.UserUsageStatsService.4
            @Override // com.android.server.usage.UsageStatsDatabase.StatCombiner
            public void combine(IntervalStats stats, boolean mutable, List<UsageEvents.Event> accumulatedResult) {
                if (stats.events == null) {
                    return;
                }
                int startIndex = stats.events.firstIndexOnOrAfter(beginTime);
                int size = stats.events.size();
                for (int i = startIndex; i < size && stats.events.get(i).mTimeStamp < endTime; i++) {
                    UsageEvents.Event event = stats.events.get(i);
                    if (obfuscateInstantApps) {
                        event = event.getObfuscatedIfInstantApp();
                    }
                    names.add(event.mPackage);
                    if (event.mClass != null) {
                        names.add(event.mClass);
                    }
                    accumulatedResult.add(event);
                }
            }
        });
        if (results == null || results.isEmpty()) {
            return null;
        }
        String[] table = (String[]) names.toArray(new String[names.size()]);
        Arrays.sort(table);
        return new UsageEvents(results, table);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsageEvents queryEventsForPackage(final long beginTime, final long endTime, final String packageName) {
        final ArraySet<String> names = new ArraySet<>();
        names.add(packageName);
        List<UsageEvents.Event> results = queryStats(0, beginTime, endTime, new UsageStatsDatabase.StatCombiner() { // from class: com.android.server.usage.-$$Lambda$UserUsageStatsService$aWxPyFEggMep-oyju6mPXDEUesw
            @Override // com.android.server.usage.UsageStatsDatabase.StatCombiner
            public final void combine(IntervalStats intervalStats, boolean z, List list) {
                UserUsageStatsService.lambda$queryEventsForPackage$0(beginTime, endTime, packageName, names, intervalStats, z, list);
            }
        });
        if (results == null || results.isEmpty()) {
            return null;
        }
        String[] table = (String[]) names.toArray(new String[names.size()]);
        Arrays.sort(table);
        return new UsageEvents(results, table);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$queryEventsForPackage$0(long beginTime, long endTime, String packageName, ArraySet names, IntervalStats stats, boolean mutable, List accumulatedResult) {
        if (stats.events == null) {
            return;
        }
        int startIndex = stats.events.firstIndexOnOrAfter(beginTime);
        int size = stats.events.size();
        for (int i = startIndex; i < size && stats.events.get(i).mTimeStamp < endTime; i++) {
            UsageEvents.Event event = stats.events.get(i);
            if (packageName.equals(event.mPackage)) {
                if (event.mClass != null) {
                    names.add(event.mClass);
                }
                accumulatedResult.add(event);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void persistActiveStats() {
        if (this.mStatsChanged) {
            Slog.i(TAG, this.mLogPrefix + "Flushing usage stats to disk");
            for (int i = 0; i < this.mCurrentStats.length; i++) {
                try {
                    this.mDatabase.putUsageStats(i, this.mCurrentStats[i]);
                } catch (IOException e) {
                    Slog.e(TAG, this.mLogPrefix + "Failed to persist active stats", e);
                    return;
                }
            }
            this.mStatsChanged = false;
        }
    }

    private void rolloverStats(long currentTimeMillis) {
        long startTime = SystemClock.elapsedRealtime();
        Slog.i(TAG, this.mLogPrefix + "Rolling over usage stats");
        int i = 0;
        Configuration previousConfig = this.mCurrentStats[0].activeConfiguration;
        ArraySet<String> continuePreviousDay = new ArraySet<>();
        IntervalStats[] intervalStatsArr = this.mCurrentStats;
        int length = intervalStatsArr.length;
        int i2 = 0;
        while (true) {
            int i3 = 4;
            if (i2 >= length) {
                break;
            }
            IntervalStats stat = intervalStatsArr[i2];
            int pkgCount = stat.packageStats.size();
            int i4 = i;
            while (i4 < pkgCount) {
                UsageStats pkgStats = stat.packageStats.valueAt(i4);
                if (pkgStats.mLastEvent == 1 || pkgStats.mLastEvent == i3) {
                    continuePreviousDay.add(pkgStats.mPackageName);
                    stat.update(pkgStats.mPackageName, this.mDailyExpiryDate.getTimeInMillis() - 1, 3);
                    notifyStatsChanged();
                }
                i4++;
                i3 = 4;
            }
            stat.updateConfigurationStats(null, this.mDailyExpiryDate.getTimeInMillis() - 1);
            stat.commitTime(this.mDailyExpiryDate.getTimeInMillis() - 1);
            i2++;
            i = 0;
        }
        persistActiveStats();
        this.mDatabase.prune(currentTimeMillis);
        loadActiveStats(currentTimeMillis);
        int continueCount = continuePreviousDay.size();
        int i5 = 0;
        while (i5 < continueCount) {
            String name = continuePreviousDay.valueAt(i5);
            long beginTime = this.mCurrentStats[0].beginTime;
            IntervalStats[] intervalStatsArr2 = this.mCurrentStats;
            int length2 = intervalStatsArr2.length;
            int i6 = 0;
            while (i6 < length2) {
                IntervalStats stat2 = intervalStatsArr2[i6];
                stat2.update(name, beginTime, 4);
                stat2.updateConfigurationStats(previousConfig, beginTime);
                notifyStatsChanged();
                i6++;
                continueCount = continueCount;
            }
            i5++;
            continueCount = continueCount;
        }
        persistActiveStats();
        long totalTime = SystemClock.elapsedRealtime() - startTime;
        Slog.i(TAG, this.mLogPrefix + "Rolling over usage stats complete. Took " + totalTime + " milliseconds");
    }

    private void notifyStatsChanged() {
        if (!this.mStatsChanged) {
            this.mStatsChanged = true;
            this.mListener.onStatsUpdated();
        }
    }

    private void notifyNewUpdate() {
        this.mListener.onNewUpdate(this.mUserId);
    }

    private void loadActiveStats(long currentTimeMillis) {
        for (int intervalType = 0; intervalType < this.mCurrentStats.length; intervalType++) {
            IntervalStats stats = this.mDatabase.getLatestUsageStats(intervalType);
            if (stats != null && currentTimeMillis - 500 >= stats.endTime && currentTimeMillis < stats.beginTime + INTERVAL_LENGTH[intervalType]) {
                this.mCurrentStats[intervalType] = stats;
            } else {
                this.mCurrentStats[intervalType] = new IntervalStats();
                this.mCurrentStats[intervalType].beginTime = currentTimeMillis;
                this.mCurrentStats[intervalType].endTime = 1 + currentTimeMillis;
            }
        }
        this.mStatsChanged = false;
        updateRolloverDeadline();
        this.mListener.onStatsReloaded();
    }

    private void updateRolloverDeadline() {
        this.mDailyExpiryDate.setTimeInMillis(this.mCurrentStats[0].beginTime);
        this.mDailyExpiryDate.addDays(1);
        Slog.i(TAG, this.mLogPrefix + "Rollover scheduled @ " + sDateFormat.format(Long.valueOf(this.mDailyExpiryDate.getTimeInMillis())) + "(" + this.mDailyExpiryDate.getTimeInMillis() + ")");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkin(final IndentingPrintWriter pw) {
        this.mDatabase.checkinDailyFiles(new UsageStatsDatabase.CheckinAction() { // from class: com.android.server.usage.UserUsageStatsService.5
            @Override // com.android.server.usage.UsageStatsDatabase.CheckinAction
            public boolean checkin(IntervalStats stats) {
                UserUsageStatsService.this.printIntervalStats(pw, stats, false, false, null);
                return true;
            }
        });
    }

    void dump(IndentingPrintWriter pw, String pkg) {
        dump(pw, pkg, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(IndentingPrintWriter pw, String pkg, boolean compact) {
        printLast24HrEvents(pw, !compact, pkg);
        for (int interval = 0; interval < this.mCurrentStats.length; interval++) {
            pw.print("In-memory ");
            pw.print(intervalToString(interval));
            pw.println(" stats");
            printIntervalStats(pw, this.mCurrentStats[interval], !compact, true, pkg);
        }
    }

    private String formatDateTime(long dateTime, boolean pretty) {
        if (pretty) {
            return "\"" + sDateFormat.format(Long.valueOf(dateTime)) + "\"";
        }
        return Long.toString(dateTime);
    }

    private String formatElapsedTime(long elapsedTime, boolean pretty) {
        if (pretty) {
            return "\"" + DateUtils.formatElapsedTime(elapsedTime / 1000) + "\"";
        }
        return Long.toString(elapsedTime);
    }

    void printEvent(IndentingPrintWriter pw, UsageEvents.Event event, boolean prettyDates) {
        pw.printPair("time", formatDateTime(event.mTimeStamp, prettyDates));
        pw.printPair("type", eventToString(event.mEventType));
        pw.printPair("package", event.mPackage);
        if (event.mClass != null) {
            pw.printPair(AudioService.CONNECT_INTENT_KEY_DEVICE_CLASS, event.mClass);
        }
        if (event.mConfiguration != null) {
            pw.printPair(xpWindowManagerService.WindowConfigJson.KEY_CONFIG, Configuration.resourceQualifierString(event.mConfiguration));
        }
        if (event.mShortcutId != null) {
            pw.printPair("shortcutId", event.mShortcutId);
        }
        if (event.mEventType == 11) {
            pw.printPair("standbyBucket", Integer.valueOf(event.getStandbyBucket()));
            pw.printPair(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY, UsageStatsManager.reasonToString(event.getStandbyReason()));
        }
        pw.printHexPair(xpInputManagerService.InputPolicyKey.KEY_FLAGS, event.mFlags);
        pw.println();
    }

    void printLast24HrEvents(IndentingPrintWriter pw, boolean prettyDates, final String pkg) {
        final long endTime = System.currentTimeMillis();
        UnixCalendar yesterday = new UnixCalendar(endTime);
        yesterday.addDays(-1);
        final long beginTime = yesterday.getTimeInMillis();
        List<UsageEvents.Event> events = queryStats(0, beginTime, endTime, new UsageStatsDatabase.StatCombiner<UsageEvents.Event>() { // from class: com.android.server.usage.UserUsageStatsService.6
            @Override // com.android.server.usage.UsageStatsDatabase.StatCombiner
            public void combine(IntervalStats stats, boolean mutable, List<UsageEvents.Event> accumulatedResult) {
                if (stats.events == null) {
                    return;
                }
                int startIndex = stats.events.firstIndexOnOrAfter(beginTime);
                int size = stats.events.size();
                for (int i = startIndex; i < size && stats.events.get(i).mTimeStamp < endTime; i++) {
                    UsageEvents.Event event = stats.events.get(i);
                    if (pkg == null || pkg.equals(event.mPackage)) {
                        accumulatedResult.add(event);
                    }
                }
            }
        });
        pw.print("Last 24 hour events (");
        if (prettyDates) {
            pw.printPair("timeRange", "\"" + DateUtils.formatDateRange(this.mContext, beginTime, endTime, sDateFormatFlags) + "\"");
        } else {
            pw.printPair("beginTime", Long.valueOf(beginTime));
            pw.printPair("endTime", Long.valueOf(endTime));
        }
        pw.println(")");
        if (events != null) {
            pw.increaseIndent();
            for (UsageEvents.Event event : events) {
                printEvent(pw, event, prettyDates);
            }
            pw.decreaseIndent();
        }
    }

    void printEventAggregation(IndentingPrintWriter pw, String label, IntervalStats.EventTracker tracker, boolean prettyDates) {
        if (tracker.count != 0 || tracker.duration != 0) {
            pw.print(label);
            pw.print(": ");
            pw.print(tracker.count);
            pw.print("x for ");
            pw.print(formatElapsedTime(tracker.duration, prettyDates));
            if (tracker.curStartTime != 0) {
                pw.print(" (now running, started at ");
                formatDateTime(tracker.curStartTime, prettyDates);
                pw.print(")");
            }
            pw.println();
        }
    }

    void printIntervalStats(IndentingPrintWriter pw, IntervalStats stats, boolean prettyDates, boolean skipEvents, String pkg) {
        int pkgCount;
        Iterator<UsageStats> it;
        UsageStats usageStats;
        if (prettyDates) {
            pw.printPair("timeRange", "\"" + DateUtils.formatDateRange(this.mContext, stats.beginTime, stats.endTime, sDateFormatFlags) + "\"");
        } else {
            pw.printPair("beginTime", Long.valueOf(stats.beginTime));
            pw.printPair("endTime", Long.valueOf(stats.endTime));
        }
        pw.println();
        pw.increaseIndent();
        pw.println("packages");
        pw.increaseIndent();
        ArrayMap<String, UsageStats> pkgStats = stats.packageStats;
        int pkgCount2 = pkgStats.size();
        for (int i = 0; i < pkgCount2; i++) {
            UsageStats usageStats2 = pkgStats.valueAt(i);
            if (pkg == null || pkg.equals(usageStats2.mPackageName)) {
                pw.printPair("package", usageStats2.mPackageName);
                pw.printPair("totalTime", formatElapsedTime(usageStats2.mTotalTimeInForeground, prettyDates));
                pw.printPair("lastTime", formatDateTime(usageStats2.mLastTimeUsed, prettyDates));
                pw.printPair("appLaunchCount", Integer.valueOf(usageStats2.mAppLaunchCount));
                pw.println();
            }
        }
        pw.decreaseIndent();
        pw.println();
        pw.println("ChooserCounts");
        pw.increaseIndent();
        Iterator<UsageStats> it2 = pkgStats.values().iterator();
        while (it2.hasNext()) {
            UsageStats usageStats3 = it2.next();
            if (pkg == null || pkg.equals(usageStats3.mPackageName)) {
                pw.printPair("package", usageStats3.mPackageName);
                if (usageStats3.mChooserCounts != null) {
                    int chooserCountSize = usageStats3.mChooserCounts.size();
                    for (int i2 = 0; i2 < chooserCountSize; i2++) {
                        String action = (String) usageStats3.mChooserCounts.keyAt(i2);
                        ArrayMap<String, Integer> counts = (ArrayMap) usageStats3.mChooserCounts.valueAt(i2);
                        int annotationSize = counts.size();
                        int j = 0;
                        while (j < annotationSize) {
                            String key = counts.keyAt(j);
                            ArrayMap<String, UsageStats> pkgStats2 = pkgStats;
                            int count = counts.valueAt(j).intValue();
                            if (count != 0) {
                                pkgCount = pkgCount2;
                                it = it2;
                                StringBuilder sb = new StringBuilder();
                                sb.append(action);
                                usageStats = usageStats3;
                                sb.append(":");
                                sb.append(key);
                                sb.append(" is ");
                                sb.append(Integer.toString(count));
                                pw.printPair("ChooserCounts", sb.toString());
                                pw.println();
                            } else {
                                pkgCount = pkgCount2;
                                it = it2;
                                usageStats = usageStats3;
                            }
                            j++;
                            pkgStats = pkgStats2;
                            pkgCount2 = pkgCount;
                            it2 = it;
                            usageStats3 = usageStats;
                        }
                    }
                }
                pw.println();
                pkgStats = pkgStats;
                pkgCount2 = pkgCount2;
                it2 = it2;
            }
        }
        pw.decreaseIndent();
        if (pkg == null) {
            pw.println("configurations");
            pw.increaseIndent();
            ArrayMap<Configuration, ConfigurationStats> configStats = stats.configurations;
            int configCount = configStats.size();
            for (int i3 = 0; i3 < configCount; i3++) {
                ConfigurationStats config = configStats.valueAt(i3);
                pw.printPair(xpWindowManagerService.WindowConfigJson.KEY_CONFIG, Configuration.resourceQualifierString(config.mConfiguration));
                pw.printPair("totalTime", formatElapsedTime(config.mTotalTimeActive, prettyDates));
                pw.printPair("lastTime", formatDateTime(config.mLastTimeActive, prettyDates));
                pw.printPair(AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, Integer.valueOf(config.mActivationCount));
                pw.println();
            }
            pw.decreaseIndent();
            pw.println("event aggregations");
            pw.increaseIndent();
            printEventAggregation(pw, "screen-interactive", stats.interactiveTracker, prettyDates);
            printEventAggregation(pw, "screen-non-interactive", stats.nonInteractiveTracker, prettyDates);
            printEventAggregation(pw, "keyguard-shown", stats.keyguardShownTracker, prettyDates);
            printEventAggregation(pw, "keyguard-hidden", stats.keyguardHiddenTracker, prettyDates);
            pw.decreaseIndent();
        }
        if (!skipEvents) {
            pw.println(xpInputManagerService.InputPolicyKey.KEY_EVENTS);
            pw.increaseIndent();
            EventList events = stats.events;
            int eventCount = events != null ? events.size() : 0;
            int i4 = 0;
            while (true) {
                int i5 = i4;
                if (i5 >= eventCount) {
                    break;
                }
                UsageEvents.Event event = events.get(i5);
                if (pkg == null || pkg.equals(event.mPackage)) {
                    printEvent(pw, event, prettyDates);
                }
                i4 = i5 + 1;
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    private static String intervalToString(int interval) {
        switch (interval) {
            case 0:
                return "daily";
            case 1:
                return "weekly";
            case 2:
                return "monthly";
            case 3:
                return "yearly";
            default:
                return "?";
        }
    }

    private static String eventToString(int eventType) {
        switch (eventType) {
            case 0:
                return "NONE";
            case 1:
                return "MOVE_TO_FOREGROUND";
            case 2:
                return "MOVE_TO_BACKGROUND";
            case 3:
                return "END_OF_DAY";
            case 4:
                return "CONTINUE_PREVIOUS_DAY";
            case 5:
                return "CONFIGURATION_CHANGE";
            case 6:
                return "SYSTEM_INTERACTION";
            case 7:
                return "USER_INTERACTION";
            case 8:
                return "SHORTCUT_INVOCATION";
            case 9:
                return "CHOOSER_ACTION";
            case 10:
                return "NOTIFICATION_SEEN";
            case 11:
                return "STANDBY_BUCKET_CHANGED";
            case 12:
                return "NOTIFICATION_INTERRUPTION";
            case 13:
                return "SLICE_PINNED_PRIV";
            case 14:
                return "SLICE_PINNED";
            case 15:
                return "SCREEN_INTERACTIVE";
            case 16:
                return "SCREEN_NON_INTERACTIVE";
            default:
                return "UNKNOWN";
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public byte[] getBackupPayload(String key) {
        return this.mDatabase.getBackupPayload(key);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyRestoredPayload(String key, byte[] payload) {
        this.mDatabase.applyRestoredPayload(key, payload);
    }
}
