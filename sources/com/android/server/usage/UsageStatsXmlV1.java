package com.android.server.usage;

import android.app.usage.ConfigurationStats;
import android.app.usage.EventList;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.util.ArrayMap;
import com.android.internal.util.XmlUtils;
import com.android.server.am.AssistDataRequester;
import com.android.server.usage.IntervalStats;
import java.io.IOException;
import java.net.ProtocolException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
final class UsageStatsXmlV1 {
    private static final String ACTIVE_ATTR = "active";
    private static final String APP_LAUNCH_COUNT_ATTR = "appLaunchCount";
    private static final String CATEGORY_TAG = "category";
    private static final String CHOOSER_COUNT_TAG = "chosen_action";
    private static final String CLASS_ATTR = "class";
    private static final String CONFIGURATIONS_TAG = "configurations";
    private static final String CONFIG_TAG = "config";
    private static final String COUNT = "count";
    private static final String COUNT_ATTR = "count";
    private static final String END_TIME_ATTR = "endTime";
    private static final String EVENT_LOG_TAG = "event-log";
    private static final String EVENT_TAG = "event";
    private static final String FLAGS_ATTR = "flags";
    private static final String INTERACTIVE_TAG = "interactive";
    private static final String KEYGUARD_HIDDEN_TAG = "keyguard-hidden";
    private static final String KEYGUARD_SHOWN_TAG = "keyguard-shown";
    private static final String LAST_EVENT_ATTR = "lastEvent";
    private static final String LAST_TIME_ACTIVE_ATTR = "lastTimeActive";
    private static final String NAME = "name";
    private static final String NON_INTERACTIVE_TAG = "non-interactive";
    private static final String PACKAGES_TAG = "packages";
    private static final String PACKAGE_ATTR = "package";
    private static final String PACKAGE_TAG = "package";
    private static final String SHORTCUT_ID_ATTR = "shortcutId";
    private static final String STANDBY_BUCKET_ATTR = "standbyBucket";
    private static final String TAG = "UsageStatsXmlV1";
    private static final String TIME_ATTR = "time";
    private static final String TOTAL_TIME_ACTIVE_ATTR = "timeActive";
    private static final String TYPE_ATTR = "type";

    private static void loadUsageStats(XmlPullParser parser, IntervalStats statsOut) throws XmlPullParserException, IOException {
        String pkg = parser.getAttributeValue(null, "package");
        if (pkg == null) {
            throw new ProtocolException("no package attribute present");
        }
        UsageStats stats = statsOut.getOrCreateUsageStats(pkg);
        stats.mLastTimeUsed = statsOut.beginTime + XmlUtils.readLongAttribute(parser, LAST_TIME_ACTIVE_ATTR);
        stats.mTotalTimeInForeground = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);
        stats.mLastEvent = XmlUtils.readIntAttribute(parser, LAST_EVENT_ATTR);
        stats.mAppLaunchCount = XmlUtils.readIntAttribute(parser, APP_LAUNCH_COUNT_ATTR, 0);
        while (true) {
            int eventCode = parser.next();
            if (eventCode != 1) {
                String tag = parser.getName();
                if (eventCode != 3 || !tag.equals("package")) {
                    if (eventCode == 2 && tag.equals(CHOOSER_COUNT_TAG)) {
                        String action = XmlUtils.readStringAttribute(parser, "name");
                        loadChooserCounts(parser, stats, action);
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private static void loadCountAndTime(XmlPullParser parser, IntervalStats.EventTracker tracker) throws IOException, XmlPullParserException {
        tracker.count = XmlUtils.readIntAttribute(parser, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, 0);
        tracker.duration = XmlUtils.readLongAttribute(parser, TIME_ATTR, 0L);
        XmlUtils.skipCurrentTag(parser);
    }

    private static void loadChooserCounts(XmlPullParser parser, UsageStats usageStats, String action) throws XmlPullParserException, IOException {
        if (action == null) {
            return;
        }
        if (usageStats.mChooserCounts == null) {
            usageStats.mChooserCounts = new ArrayMap();
        }
        if (!usageStats.mChooserCounts.containsKey(action)) {
            ArrayMap<String, Integer> counts = new ArrayMap<>();
            usageStats.mChooserCounts.put(action, counts);
        }
        while (true) {
            int eventCode = parser.next();
            if (eventCode != 1) {
                String tag = parser.getName();
                if (eventCode != 3 || !tag.equals(CHOOSER_COUNT_TAG)) {
                    if (eventCode == 2 && tag.equals(CATEGORY_TAG)) {
                        String category = XmlUtils.readStringAttribute(parser, "name");
                        int count = XmlUtils.readIntAttribute(parser, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT);
                        ((ArrayMap) usageStats.mChooserCounts.get(action)).put(category, Integer.valueOf(count));
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private static void loadConfigStats(XmlPullParser parser, IntervalStats statsOut) throws XmlPullParserException, IOException {
        Configuration config = new Configuration();
        Configuration.readXmlAttrs(parser, config);
        ConfigurationStats configStats = statsOut.getOrCreateConfigurationStats(config);
        configStats.mLastTimeActive = statsOut.beginTime + XmlUtils.readLongAttribute(parser, LAST_TIME_ACTIVE_ATTR);
        configStats.mTotalTimeActive = XmlUtils.readLongAttribute(parser, TOTAL_TIME_ACTIVE_ATTR);
        configStats.mActivationCount = XmlUtils.readIntAttribute(parser, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT);
        if (XmlUtils.readBooleanAttribute(parser, ACTIVE_ATTR)) {
            statsOut.activeConfiguration = configStats.mConfiguration;
        }
    }

    private static void loadEvent(XmlPullParser parser, IntervalStats statsOut) throws XmlPullParserException, IOException {
        String packageName = XmlUtils.readStringAttribute(parser, "package");
        if (packageName == null) {
            throw new ProtocolException("no package attribute present");
        }
        String className = XmlUtils.readStringAttribute(parser, "class");
        UsageEvents.Event event = statsOut.buildEvent(packageName, className);
        event.mFlags = XmlUtils.readIntAttribute(parser, "flags", 0);
        event.mTimeStamp = statsOut.beginTime + XmlUtils.readLongAttribute(parser, TIME_ATTR);
        event.mEventType = XmlUtils.readIntAttribute(parser, "type");
        int i = event.mEventType;
        if (i == 5) {
            event.mConfiguration = new Configuration();
            Configuration.readXmlAttrs(parser, event.mConfiguration);
        } else if (i == 8) {
            String id = XmlUtils.readStringAttribute(parser, SHORTCUT_ID_ATTR);
            event.mShortcutId = id != null ? id.intern() : null;
        } else if (i == 11) {
            event.mBucketAndReason = XmlUtils.readIntAttribute(parser, STANDBY_BUCKET_ATTR, 0);
        }
        if (statsOut.events == null) {
            statsOut.events = new EventList();
        }
        statsOut.events.insert(event);
    }

    private static void writeUsageStats(XmlSerializer xml, IntervalStats stats, UsageStats usageStats) throws IOException {
        xml.startTag(null, "package");
        XmlUtils.writeLongAttribute(xml, LAST_TIME_ACTIVE_ATTR, usageStats.mLastTimeUsed - stats.beginTime);
        XmlUtils.writeStringAttribute(xml, "package", usageStats.mPackageName);
        XmlUtils.writeLongAttribute(xml, TOTAL_TIME_ACTIVE_ATTR, usageStats.mTotalTimeInForeground);
        XmlUtils.writeIntAttribute(xml, LAST_EVENT_ATTR, usageStats.mLastEvent);
        if (usageStats.mAppLaunchCount > 0) {
            XmlUtils.writeIntAttribute(xml, APP_LAUNCH_COUNT_ATTR, usageStats.mAppLaunchCount);
        }
        writeChooserCounts(xml, usageStats);
        xml.endTag(null, "package");
    }

    private static void writeCountAndTime(XmlSerializer xml, String tag, int count, long time) throws IOException {
        xml.startTag(null, tag);
        XmlUtils.writeIntAttribute(xml, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, count);
        XmlUtils.writeLongAttribute(xml, TIME_ATTR, time);
        xml.endTag(null, tag);
    }

    private static void writeChooserCounts(XmlSerializer xml, UsageStats usageStats) throws IOException {
        if (usageStats == null || usageStats.mChooserCounts == null || usageStats.mChooserCounts.keySet().isEmpty()) {
            return;
        }
        int chooserCountSize = usageStats.mChooserCounts.size();
        for (int i = 0; i < chooserCountSize; i++) {
            String action = (String) usageStats.mChooserCounts.keyAt(i);
            ArrayMap<String, Integer> counts = (ArrayMap) usageStats.mChooserCounts.valueAt(i);
            if (action != null && counts != null && !counts.isEmpty()) {
                xml.startTag(null, CHOOSER_COUNT_TAG);
                XmlUtils.writeStringAttribute(xml, "name", action);
                writeCountsForAction(xml, counts);
                xml.endTag(null, CHOOSER_COUNT_TAG);
            }
        }
    }

    private static void writeCountsForAction(XmlSerializer xml, ArrayMap<String, Integer> counts) throws IOException {
        int countsSize = counts.size();
        for (int i = 0; i < countsSize; i++) {
            String key = counts.keyAt(i);
            int count = counts.valueAt(i).intValue();
            if (count > 0) {
                xml.startTag(null, CATEGORY_TAG);
                XmlUtils.writeStringAttribute(xml, "name", key);
                XmlUtils.writeIntAttribute(xml, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, count);
                xml.endTag(null, CATEGORY_TAG);
            }
        }
    }

    private static void writeConfigStats(XmlSerializer xml, IntervalStats stats, ConfigurationStats configStats, boolean isActive) throws IOException {
        xml.startTag(null, "config");
        XmlUtils.writeLongAttribute(xml, LAST_TIME_ACTIVE_ATTR, configStats.mLastTimeActive - stats.beginTime);
        XmlUtils.writeLongAttribute(xml, TOTAL_TIME_ACTIVE_ATTR, configStats.mTotalTimeActive);
        XmlUtils.writeIntAttribute(xml, AssistDataRequester.KEY_RECEIVER_EXTRA_COUNT, configStats.mActivationCount);
        if (isActive) {
            XmlUtils.writeBooleanAttribute(xml, ACTIVE_ATTR, true);
        }
        Configuration.writeXmlAttrs(xml, configStats.mConfiguration);
        xml.endTag(null, "config");
    }

    private static void writeEvent(XmlSerializer xml, IntervalStats stats, UsageEvents.Event event) throws IOException {
        xml.startTag(null, EVENT_TAG);
        XmlUtils.writeLongAttribute(xml, TIME_ATTR, event.mTimeStamp - stats.beginTime);
        XmlUtils.writeStringAttribute(xml, "package", event.mPackage);
        if (event.mClass != null) {
            XmlUtils.writeStringAttribute(xml, "class", event.mClass);
        }
        XmlUtils.writeIntAttribute(xml, "flags", event.mFlags);
        XmlUtils.writeIntAttribute(xml, "type", event.mEventType);
        int i = event.mEventType;
        if (i != 5) {
            if (i == 8) {
                if (event.mShortcutId != null) {
                    XmlUtils.writeStringAttribute(xml, SHORTCUT_ID_ATTR, event.mShortcutId);
                }
            } else if (i == 11 && event.mBucketAndReason != 0) {
                XmlUtils.writeIntAttribute(xml, STANDBY_BUCKET_ATTR, event.mBucketAndReason);
            }
        } else if (event.mConfiguration != null) {
            Configuration.writeXmlAttrs(xml, event.mConfiguration);
        }
        xml.endTag(null, EVENT_TAG);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x0077, code lost:
        if (r5.equals(com.android.server.usage.UsageStatsXmlV1.NON_INTERACTIVE_TAG) != false) goto L22;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static void read(org.xmlpull.v1.XmlPullParser r8, com.android.server.usage.IntervalStats r9) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        /*
            android.util.ArrayMap<java.lang.String, android.app.usage.UsageStats> r0 = r9.packageStats
            r0.clear()
            android.util.ArrayMap<android.content.res.Configuration, android.app.usage.ConfigurationStats> r0 = r9.configurations
            r0.clear()
            r0 = 0
            r9.activeConfiguration = r0
            android.app.usage.EventList r0 = r9.events
            if (r0 == 0) goto L16
            android.app.usage.EventList r0 = r9.events
            r0.clear()
        L16:
            long r0 = r9.beginTime
            java.lang.String r2 = "endTime"
            long r2 = com.android.internal.util.XmlUtils.readLongAttribute(r8, r2)
            long r0 = r0 + r2
            r9.endTime = r0
            int r0 = r8.getDepth()
        L25:
            int r1 = r8.next()
            r2 = r1
            r3 = 1
            if (r1 == r3) goto Lb9
            r1 = 3
            if (r2 != r1) goto L36
            int r4 = r8.getDepth()
            if (r4 <= r0) goto Lb9
        L36:
            r4 = 2
            if (r2 == r4) goto L3a
            goto L25
        L3a:
            java.lang.String r5 = r8.getName()
            r6 = -1
            int r7 = r5.hashCode()
            switch(r7) {
                case -1354792126: goto L84;
                case -1169351247: goto L7a;
                case -807157790: goto L70;
                case -807062458: goto L65;
                case 96891546: goto L5b;
                case 526608426: goto L51;
                case 1844104930: goto L47;
                default: goto L46;
            }
        L46:
            goto L8e
        L47:
            java.lang.String r1 = "interactive"
            boolean r1 = r5.equals(r1)
            if (r1 == 0) goto L8e
            r3 = 0
            goto L8f
        L51:
            java.lang.String r1 = "keyguard-shown"
            boolean r1 = r5.equals(r1)
            if (r1 == 0) goto L8e
            r3 = r4
            goto L8f
        L5b:
            java.lang.String r1 = "event"
            boolean r1 = r5.equals(r1)
            if (r1 == 0) goto L8e
            r3 = 6
            goto L8f
        L65:
            java.lang.String r1 = "package"
            boolean r1 = r5.equals(r1)
            if (r1 == 0) goto L8e
            r3 = 4
            goto L8f
        L70:
            java.lang.String r1 = "non-interactive"
            boolean r1 = r5.equals(r1)
            if (r1 == 0) goto L8e
            goto L8f
        L7a:
            java.lang.String r3 = "keyguard-hidden"
            boolean r3 = r5.equals(r3)
            if (r3 == 0) goto L8e
            r3 = r1
            goto L8f
        L84:
            java.lang.String r1 = "config"
            boolean r1 = r5.equals(r1)
            if (r1 == 0) goto L8e
            r3 = 5
            goto L8f
        L8e:
            r3 = r6
        L8f:
            switch(r3) {
                case 0: goto Lb1;
                case 1: goto Lab;
                case 2: goto La5;
                case 3: goto L9f;
                case 4: goto L9b;
                case 5: goto L97;
                case 6: goto L93;
                default: goto L92;
            }
        L92:
            goto Lb7
        L93:
            loadEvent(r8, r9)
            goto Lb7
        L97:
            loadConfigStats(r8, r9)
            goto Lb7
        L9b:
            loadUsageStats(r8, r9)
            goto Lb7
        L9f:
            com.android.server.usage.IntervalStats$EventTracker r1 = r9.keyguardHiddenTracker
            loadCountAndTime(r8, r1)
            goto Lb7
        La5:
            com.android.server.usage.IntervalStats$EventTracker r1 = r9.keyguardShownTracker
            loadCountAndTime(r8, r1)
            goto Lb7
        Lab:
            com.android.server.usage.IntervalStats$EventTracker r1 = r9.nonInteractiveTracker
            loadCountAndTime(r8, r1)
            goto Lb7
        Lb1:
            com.android.server.usage.IntervalStats$EventTracker r1 = r9.interactiveTracker
            loadCountAndTime(r8, r1)
        Lb7:
            goto L25
        Lb9:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usage.UsageStatsXmlV1.read(org.xmlpull.v1.XmlPullParser, com.android.server.usage.IntervalStats):void");
    }

    public static void write(XmlSerializer xml, IntervalStats stats) throws IOException {
        XmlUtils.writeLongAttribute(xml, END_TIME_ATTR, stats.endTime - stats.beginTime);
        writeCountAndTime(xml, INTERACTIVE_TAG, stats.interactiveTracker.count, stats.interactiveTracker.duration);
        writeCountAndTime(xml, NON_INTERACTIVE_TAG, stats.nonInteractiveTracker.count, stats.nonInteractiveTracker.duration);
        writeCountAndTime(xml, KEYGUARD_SHOWN_TAG, stats.keyguardShownTracker.count, stats.keyguardShownTracker.duration);
        writeCountAndTime(xml, KEYGUARD_HIDDEN_TAG, stats.keyguardHiddenTracker.count, stats.keyguardHiddenTracker.duration);
        xml.startTag(null, PACKAGES_TAG);
        int statsCount = stats.packageStats.size();
        for (int i = 0; i < statsCount; i++) {
            writeUsageStats(xml, stats, stats.packageStats.valueAt(i));
        }
        xml.endTag(null, PACKAGES_TAG);
        xml.startTag(null, CONFIGURATIONS_TAG);
        int configCount = stats.configurations.size();
        for (int i2 = 0; i2 < configCount; i2++) {
            boolean active = stats.activeConfiguration.equals(stats.configurations.keyAt(i2));
            writeConfigStats(xml, stats, stats.configurations.valueAt(i2), active);
        }
        xml.endTag(null, CONFIGURATIONS_TAG);
        xml.startTag(null, EVENT_LOG_TAG);
        int eventCount = stats.events != null ? stats.events.size() : 0;
        for (int i3 = 0; i3 < eventCount; i3++) {
            writeEvent(xml, stats, stats.events.get(i3));
        }
        xml.endTag(null, EVENT_LOG_TAG);
    }

    private UsageStatsXmlV1() {
    }
}
