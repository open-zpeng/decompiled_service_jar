package com.android.server.usage;

import android.app.usage.ConfigurationStats;
import android.app.usage.EventList;
import android.app.usage.EventStats;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.proto.ProtoInputStream;
import com.android.internal.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.List;

/* loaded from: classes2.dex */
public class IntervalStats {
    public static final int CURRENT_MAJOR_VERSION = 1;
    public static final int CURRENT_MINOR_VERSION = 1;
    public Configuration activeConfiguration;
    public long beginTime;
    public long endTime;
    public long lastTimeSaved;
    public int majorVersion = 1;
    public int minorVersion = 1;
    public final EventTracker interactiveTracker = new EventTracker();
    public final EventTracker nonInteractiveTracker = new EventTracker();
    public final EventTracker keyguardShownTracker = new EventTracker();
    public final EventTracker keyguardHiddenTracker = new EventTracker();
    public final ArrayMap<String, UsageStats> packageStats = new ArrayMap<>();
    public final ArrayMap<Configuration, ConfigurationStats> configurations = new ArrayMap<>();
    public final EventList events = new EventList();
    public final ArraySet<String> mStringCache = new ArraySet<>();

    /* loaded from: classes2.dex */
    public static final class EventTracker {
        public int count;
        public long curStartTime;
        public long duration;
        public long lastEventTime;

        public void commitTime(long timeStamp) {
            long j = this.curStartTime;
            if (j != 0) {
                this.duration += timeStamp - j;
                this.curStartTime = 0L;
            }
        }

        public void update(long timeStamp) {
            if (this.curStartTime == 0) {
                this.count++;
            }
            commitTime(timeStamp);
            this.curStartTime = timeStamp;
            this.lastEventTime = timeStamp;
        }

        void addToEventStats(List<EventStats> out, int event, long beginTime, long endTime) {
            if (this.count != 0 || this.duration != 0) {
                EventStats ev = new EventStats();
                ev.mEventType = event;
                ev.mCount = this.count;
                ev.mTotalTime = this.duration;
                ev.mLastEventTime = this.lastEventTime;
                ev.mBeginTimeStamp = beginTime;
                ev.mEndTimeStamp = endTime;
                out.add(ev);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsageStats getOrCreateUsageStats(String packageName) {
        UsageStats usageStats = this.packageStats.get(packageName);
        if (usageStats == null) {
            UsageStats usageStats2 = new UsageStats();
            usageStats2.mPackageName = getCachedStringRef(packageName);
            usageStats2.mBeginTimeStamp = this.beginTime;
            usageStats2.mEndTimeStamp = this.endTime;
            this.packageStats.put(usageStats2.mPackageName, usageStats2);
            return usageStats2;
        }
        return usageStats;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ConfigurationStats getOrCreateConfigurationStats(Configuration config) {
        ConfigurationStats configStats = this.configurations.get(config);
        if (configStats == null) {
            ConfigurationStats configStats2 = new ConfigurationStats();
            configStats2.mBeginTimeStamp = this.beginTime;
            configStats2.mEndTimeStamp = this.endTime;
            configStats2.mConfiguration = config;
            this.configurations.put(config, configStats2);
            return configStats2;
        }
        return configStats;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsageEvents.Event buildEvent(String packageName, String className) {
        UsageEvents.Event event = new UsageEvents.Event();
        event.mPackage = getCachedStringRef(packageName);
        if (className != null) {
            event.mClass = getCachedStringRef(className);
        }
        return event;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsageEvents.Event buildEvent(ProtoInputStream parser, List<String> stringPool) throws IOException {
        UsageEvents.Event event = new UsageEvents.Event();
        while (true) {
            switch (parser.nextField()) {
                case -1:
                    int i = event.mEventType;
                    if (i != 5) {
                        if (i == 8) {
                            if (event.mShortcutId == null) {
                                event.mShortcutId = "";
                            }
                        } else if (i == 12 && event.mNotificationChannelId == null) {
                            event.mNotificationChannelId = "";
                        }
                    } else if (event.mConfiguration == null) {
                        event.mConfiguration = new Configuration();
                    }
                    if (event.mTimeStamp == 0) {
                        event.mTimeStamp = this.beginTime;
                    }
                    return event;
                case 1:
                    event.mPackage = getCachedStringRef(parser.readString(1138166333441L));
                    break;
                case 2:
                    event.mPackage = getCachedStringRef(stringPool.get(parser.readInt(1120986464258L) - 1));
                    break;
                case 3:
                    event.mClass = getCachedStringRef(parser.readString(1138166333443L));
                    break;
                case 4:
                    event.mClass = getCachedStringRef(stringPool.get(parser.readInt(1120986464260L) - 1));
                    break;
                case 5:
                    event.mTimeStamp = this.beginTime + parser.readLong(1112396529669L);
                    break;
                case 6:
                    event.mFlags = parser.readInt(1120986464262L);
                    break;
                case 7:
                    event.mEventType = parser.readInt(1120986464263L);
                    break;
                case 8:
                    event.mConfiguration = new Configuration();
                    event.mConfiguration.readFromProto(parser, 1146756268040L);
                    break;
                case 9:
                    event.mShortcutId = parser.readString(1138166333449L).intern();
                    break;
                case 11:
                    event.mBucketAndReason = parser.readInt(1120986464267L);
                    break;
                case 12:
                    event.mNotificationChannelId = parser.readString(1138166333452L);
                    break;
                case 13:
                    event.mNotificationChannelId = getCachedStringRef(stringPool.get(parser.readInt(1120986464269L) - 1));
                    break;
                case 14:
                    event.mInstanceId = parser.readInt(1120986464270L);
                    break;
                case 15:
                    event.mTaskRootPackage = getCachedStringRef(stringPool.get(parser.readInt(1120986464271L) - 1));
                    break;
                case 16:
                    event.mTaskRootClass = getCachedStringRef(stringPool.get(parser.readInt(1120986464272L) - 1));
                    break;
            }
        }
    }

    private boolean isStatefulEvent(int eventType) {
        if (eventType != 1 && eventType != 2 && eventType != 3 && eventType != 4 && eventType != 26) {
            switch (eventType) {
                case 19:
                case 20:
                case 21:
                case 22:
                case 23:
                    break;
                default:
                    return false;
            }
        }
        return true;
    }

    private boolean isUserVisibleEvent(int eventType) {
        return (eventType == 6 || eventType == 11) ? false : true;
    }

    @VisibleForTesting
    public void update(String packageName, String className, long timeStamp, int eventType, int instanceId) {
        if (eventType == 26 || eventType == 25) {
            int size = this.packageStats.size();
            for (int i = 0; i < size; i++) {
                UsageStats usageStats = this.packageStats.valueAt(i);
                usageStats.update(null, timeStamp, eventType, instanceId);
            }
        } else {
            UsageStats usageStats2 = getOrCreateUsageStats(packageName);
            usageStats2.update(className, timeStamp, eventType, instanceId);
        }
        if (timeStamp > this.endTime) {
            this.endTime = timeStamp;
        }
    }

    @VisibleForTesting
    public void addEvent(UsageEvents.Event event) {
        event.mPackage = getCachedStringRef(event.mPackage);
        if (event.mClass != null) {
            event.mClass = getCachedStringRef(event.mClass);
        }
        if (event.mTaskRootPackage != null) {
            event.mTaskRootPackage = getCachedStringRef(event.mTaskRootPackage);
        }
        if (event.mTaskRootClass != null) {
            event.mTaskRootClass = getCachedStringRef(event.mTaskRootClass);
        }
        if (event.mEventType == 12) {
            event.mNotificationChannelId = getCachedStringRef(event.mNotificationChannelId);
        }
        this.events.insert(event);
        if (event.mTimeStamp > this.endTime) {
            this.endTime = event.mTimeStamp;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateChooserCounts(String packageName, String category, String action) {
        ArrayMap<String, Integer> chooserCounts;
        UsageStats usageStats = getOrCreateUsageStats(packageName);
        if (usageStats.mChooserCounts == null) {
            usageStats.mChooserCounts = new ArrayMap();
        }
        int idx = usageStats.mChooserCounts.indexOfKey(action);
        if (idx < 0) {
            chooserCounts = new ArrayMap<>();
            usageStats.mChooserCounts.put(action, chooserCounts);
        } else {
            ArrayMap<String, Integer> chooserCounts2 = usageStats.mChooserCounts;
            chooserCounts = (ArrayMap) chooserCounts2.valueAt(idx);
        }
        int currentCount = chooserCounts.getOrDefault(category, 0).intValue();
        chooserCounts.put(category, Integer.valueOf(currentCount + 1));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateConfigurationStats(Configuration config, long timeStamp) {
        Configuration configuration = this.activeConfiguration;
        if (configuration != null) {
            ConfigurationStats activeStats = this.configurations.get(configuration);
            activeStats.mTotalTimeActive += timeStamp - activeStats.mLastTimeActive;
            activeStats.mLastTimeActive = timeStamp - 1;
        }
        if (config != null) {
            ConfigurationStats configStats = getOrCreateConfigurationStats(config);
            configStats.mLastTimeActive = timeStamp;
            configStats.mActivationCount++;
            this.activeConfiguration = configStats.mConfiguration;
        }
        if (timeStamp > this.endTime) {
            this.endTime = timeStamp;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void incrementAppLaunchCount(String packageName) {
        UsageStats usageStats = getOrCreateUsageStats(packageName);
        usageStats.mAppLaunchCount++;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void commitTime(long timeStamp) {
        this.interactiveTracker.commitTime(timeStamp);
        this.nonInteractiveTracker.commitTime(timeStamp);
        this.keyguardShownTracker.commitTime(timeStamp);
        this.keyguardHiddenTracker.commitTime(timeStamp);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateScreenInteractive(long timeStamp) {
        this.interactiveTracker.update(timeStamp);
        this.nonInteractiveTracker.commitTime(timeStamp);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateScreenNonInteractive(long timeStamp) {
        this.nonInteractiveTracker.update(timeStamp);
        this.interactiveTracker.commitTime(timeStamp);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateKeyguardShown(long timeStamp) {
        this.keyguardShownTracker.update(timeStamp);
        this.keyguardHiddenTracker.commitTime(timeStamp);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateKeyguardHidden(long timeStamp) {
        this.keyguardHiddenTracker.update(timeStamp);
        this.keyguardShownTracker.commitTime(timeStamp);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addEventStatsTo(List<EventStats> out) {
        this.interactiveTracker.addToEventStats(out, 15, this.beginTime, this.endTime);
        this.nonInteractiveTracker.addToEventStats(out, 16, this.beginTime, this.endTime);
        this.keyguardShownTracker.addToEventStats(out, 17, this.beginTime, this.endTime);
        this.keyguardHiddenTracker.addToEventStats(out, 18, this.beginTime, this.endTime);
    }

    private String getCachedStringRef(String str) {
        int index = this.mStringCache.indexOf(str);
        if (index < 0) {
            this.mStringCache.add(str);
            return str;
        }
        return this.mStringCache.valueAt(index);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void upgradeIfNeeded() {
        if (this.majorVersion >= 1) {
            return;
        }
        this.majorVersion = 1;
    }
}
