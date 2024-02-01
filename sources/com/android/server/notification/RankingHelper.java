package com.android.server.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.metrics.LogMaker;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.job.JobSchedulerShellCommand;
import com.android.server.notification.NotificationManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class RankingHelper implements RankingConfig {
    private static final String ATT_APP_USER_LOCKED_FIELDS = "app_user_locked_fields";
    private static final String ATT_ID = "id";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_NAME = "name";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_SHOW_BADGE = "show_badge";
    private static final String ATT_UID = "uid";
    private static final String ATT_VERSION = "version";
    private static final String ATT_VISIBILITY = "visibility";
    private static final int DEFAULT_IMPORTANCE = -1000;
    private static final int DEFAULT_LOCKED_APP_FIELDS = 0;
    private static final int DEFAULT_PRIORITY = 0;
    private static final boolean DEFAULT_SHOW_BADGE = true;
    private static final int DEFAULT_VISIBILITY = -1000;
    private static final String TAG = "RankingHelper";
    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_GROUP = "channelGroup";
    private static final String TAG_PACKAGE = "package";
    static final String TAG_RANKING = "ranking";
    private static final int XML_VERSION = 1;
    private boolean mAreChannelsBypassingDnd;
    private SparseBooleanArray mBadgingEnabled;
    private final Context mContext;
    private final PackageManager mPm;
    private final NotificationComparator mPreliminaryComparator;
    private final RankingHandler mRankingHandler;
    private final NotificationSignalExtractor[] mSignalExtractors;
    private ZenModeHelper mZenModeHelper;
    private final GlobalSortKeyComparator mFinalComparator = new GlobalSortKeyComparator();
    private final ArrayMap<String, Record> mRecords = new ArrayMap<>();
    private final ArrayMap<String, NotificationRecord> mProxyByGroupTmp = new ArrayMap<>();
    private final ArrayMap<String, Record> mRestoredWithoutUids = new ArrayMap<>();

    /* loaded from: classes.dex */
    public @interface LockableAppFields {
        public static final int USER_LOCKED_IMPORTANCE = 1;
    }

    public RankingHelper(Context context, PackageManager pm, RankingHandler rankingHandler, ZenModeHelper zenHelper, NotificationUsageStats usageStats, String[] extractorNames) {
        this.mContext = context;
        this.mRankingHandler = rankingHandler;
        this.mPm = pm;
        this.mZenModeHelper = zenHelper;
        this.mPreliminaryComparator = new NotificationComparator(this.mContext);
        updateBadgingEnabled();
        int N = extractorNames.length;
        this.mSignalExtractors = new NotificationSignalExtractor[N];
        for (int i = 0; i < N; i++) {
            try {
                Class<?> extractorClass = this.mContext.getClassLoader().loadClass(extractorNames[i]);
                NotificationSignalExtractor extractor = (NotificationSignalExtractor) extractorClass.newInstance();
                extractor.initialize(this.mContext, usageStats);
                extractor.setConfig(this);
                extractor.setZenHelper(zenHelper);
                this.mSignalExtractors[i] = extractor;
            } catch (ClassNotFoundException e) {
                Slog.w(TAG, "Couldn't find extractor " + extractorNames[i] + ".", e);
            } catch (IllegalAccessException e2) {
                Slog.w(TAG, "Problem accessing extractor " + extractorNames[i] + ".", e2);
            } catch (InstantiationException e3) {
                Slog.w(TAG, "Couldn't instantiate extractor " + extractorNames[i] + ".", e3);
            }
        }
        this.mAreChannelsBypassingDnd = (this.mZenModeHelper.getNotificationPolicy().state & 1) == 1;
        updateChannelsBypassingDnd();
    }

    public <T extends NotificationSignalExtractor> T findExtractor(Class<T> extractorClass) {
        int N = this.mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            T t = (T) this.mSignalExtractors[i];
            if (extractorClass.equals(t.getClass())) {
                return t;
            }
        }
        return null;
    }

    public void extractSignals(NotificationRecord r) {
        int N = this.mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            NotificationSignalExtractor extractor = this.mSignalExtractors[i];
            try {
                RankingReconsideration recon = extractor.process(r);
                if (recon != null) {
                    this.mRankingHandler.requestReconsideration(recon);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "NotificationSignalExtractor failed.", t);
            }
        }
    }

    public void readXml(XmlPullParser parser, boolean forRestore) throws XmlPullParserException, IOException {
        int uid;
        Record r;
        int innerDepth;
        int innerDepth2;
        int innerDepth3;
        String str;
        int i = 2;
        if (parser.getEventType() != 2 || !TAG_RANKING.equals(parser.getName())) {
            return;
        }
        this.mRestoredWithoutUids.clear();
        while (true) {
            int next = parser.next();
            int type = next;
            if (next == 1) {
                throw new IllegalStateException("Failed to reach END_DOCUMENT");
            }
            String tag = parser.getName();
            if (type == 3 && TAG_RANKING.equals(tag)) {
                return;
            }
            if (type == i && "package".equals(tag)) {
                int uid2 = XmlUtils.readIntAttribute(parser, "uid", Record.UNKNOWN_UID);
                String name = parser.getAttributeValue(null, "name");
                if (!TextUtils.isEmpty(name)) {
                    if (forRestore) {
                        try {
                            int uid3 = this.mPm.getPackageUidAsUser(name, 0);
                            uid = uid3;
                        } catch (PackageManager.NameNotFoundException e) {
                        }
                        r = getOrCreateRecord(name, uid, XmlUtils.readIntAttribute(parser, ATT_IMPORTANCE, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE), XmlUtils.readIntAttribute(parser, "priority", 0), XmlUtils.readIntAttribute(parser, ATT_VISIBILITY, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE), XmlUtils.readBooleanAttribute(parser, ATT_SHOW_BADGE, true));
                        r.importance = XmlUtils.readIntAttribute(parser, ATT_IMPORTANCE, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                        r.priority = XmlUtils.readIntAttribute(parser, "priority", 0);
                        r.visibility = XmlUtils.readIntAttribute(parser, ATT_VISIBILITY, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                        r.showBadge = XmlUtils.readBooleanAttribute(parser, ATT_SHOW_BADGE, true);
                        r.lockedAppFields = XmlUtils.readIntAttribute(parser, ATT_APP_USER_LOCKED_FIELDS, 0);
                        innerDepth = parser.getDepth();
                        while (true) {
                            innerDepth2 = innerDepth;
                            innerDepth3 = parser.next();
                            type = innerDepth3;
                            if (innerDepth3 != 1 || (type == 3 && parser.getDepth() <= innerDepth2)) {
                                try {
                                    deleteDefaultChannelIfNeeded(r);
                                    break;
                                } catch (PackageManager.NameNotFoundException e2) {
                                    Slog.e(TAG, "deleteDefaultChannelIfNeeded - Exception: " + e2);
                                }
                            } else {
                                if (type != 3 && type != 4) {
                                    String tagName = parser.getName();
                                    if (TAG_GROUP.equals(tagName)) {
                                        str = null;
                                        String id = parser.getAttributeValue(null, ATT_ID);
                                        CharSequence groupName = parser.getAttributeValue(null, "name");
                                        if (!TextUtils.isEmpty(id)) {
                                            NotificationChannelGroup group = new NotificationChannelGroup(id, groupName);
                                            group.populateFromXml(parser);
                                            r.groups.put(id, group);
                                        }
                                    } else {
                                        str = null;
                                    }
                                    if (TAG_CHANNEL.equals(tagName)) {
                                        String id2 = parser.getAttributeValue(str, ATT_ID);
                                        String channelName = parser.getAttributeValue(str, "name");
                                        int channelImportance = XmlUtils.readIntAttribute(parser, ATT_IMPORTANCE, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                                        if (!TextUtils.isEmpty(id2) && !TextUtils.isEmpty(channelName)) {
                                            NotificationChannel channel = new NotificationChannel(id2, channelName, channelImportance);
                                            if (forRestore) {
                                                channel.populateFromXmlForRestore(parser, this.mContext);
                                            } else {
                                                channel.populateFromXml(parser);
                                            }
                                            r.channels.put(id2, channel);
                                        }
                                    }
                                }
                                innerDepth = innerDepth2;
                            }
                        }
                    }
                    uid = uid2;
                    r = getOrCreateRecord(name, uid, XmlUtils.readIntAttribute(parser, ATT_IMPORTANCE, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE), XmlUtils.readIntAttribute(parser, "priority", 0), XmlUtils.readIntAttribute(parser, ATT_VISIBILITY, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE), XmlUtils.readBooleanAttribute(parser, ATT_SHOW_BADGE, true));
                    r.importance = XmlUtils.readIntAttribute(parser, ATT_IMPORTANCE, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                    r.priority = XmlUtils.readIntAttribute(parser, "priority", 0);
                    r.visibility = XmlUtils.readIntAttribute(parser, ATT_VISIBILITY, (int) JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                    r.showBadge = XmlUtils.readBooleanAttribute(parser, ATT_SHOW_BADGE, true);
                    r.lockedAppFields = XmlUtils.readIntAttribute(parser, ATT_APP_USER_LOCKED_FIELDS, 0);
                    innerDepth = parser.getDepth();
                    while (true) {
                        innerDepth2 = innerDepth;
                        innerDepth3 = parser.next();
                        type = innerDepth3;
                        if (innerDepth3 != 1) {
                        }
                        deleteDefaultChannelIfNeeded(r);
                        break;
                        innerDepth = innerDepth2;
                    }
                }
            }
            i = 2;
        }
    }

    private static String recordKey(String pkg, int uid) {
        return pkg + "|" + uid;
    }

    private Record getRecord(String pkg, int uid) {
        Record record;
        String key = recordKey(pkg, uid);
        synchronized (this.mRecords) {
            record = this.mRecords.get(key);
        }
        return record;
    }

    private Record getOrCreateRecord(String pkg, int uid) {
        return getOrCreateRecord(pkg, uid, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE, 0, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE, true);
    }

    private Record getOrCreateRecord(String pkg, int uid, int importance, int priority, int visibility, boolean showBadge) {
        Record r;
        String key = recordKey(pkg, uid);
        synchronized (this.mRecords) {
            r = uid == Record.UNKNOWN_UID ? this.mRestoredWithoutUids.get(pkg) : this.mRecords.get(key);
            if (r == null) {
                r = new Record();
                r.pkg = pkg;
                r.uid = uid;
                r.importance = importance;
                r.priority = priority;
                r.visibility = visibility;
                r.showBadge = showBadge;
                try {
                    createDefaultChannelIfNeeded(r);
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "createDefaultChannelIfNeeded - Exception: " + e);
                }
                if (r.uid == Record.UNKNOWN_UID) {
                    this.mRestoredWithoutUids.put(pkg, r);
                } else {
                    this.mRecords.put(key, r);
                }
            }
        }
        return r;
    }

    private boolean shouldHaveDefaultChannel(Record r) throws PackageManager.NameNotFoundException {
        int userId = UserHandle.getUserId(r.uid);
        ApplicationInfo applicationInfo = this.mPm.getApplicationInfoAsUser(r.pkg, 0, userId);
        return applicationInfo.targetSdkVersion < 26;
    }

    private void deleteDefaultChannelIfNeeded(Record r) throws PackageManager.NameNotFoundException {
        if (!r.channels.containsKey("miscellaneous") || shouldHaveDefaultChannel(r)) {
            return;
        }
        r.channels.remove("miscellaneous");
    }

    private void createDefaultChannelIfNeeded(Record r) throws PackageManager.NameNotFoundException {
        if (r.channels.containsKey("miscellaneous")) {
            r.channels.get("miscellaneous").setName(this.mContext.getString(17039796));
        } else if (!shouldHaveDefaultChannel(r)) {
        } else {
            NotificationChannel channel = new NotificationChannel("miscellaneous", this.mContext.getString(17039796), r.importance);
            channel.setBypassDnd(r.priority == 2);
            channel.setLockscreenVisibility(r.visibility);
            if (r.importance != -1000) {
                channel.lockFields(4);
            }
            if (r.priority != 0) {
                channel.lockFields(1);
            }
            if (r.visibility != -1000) {
                channel.lockFields(2);
            }
            r.channels.put(channel.getId(), channel);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:29:0x005f A[Catch: all -> 0x0115, TryCatch #0 {, blocks: (B:4:0x0015, B:6:0x001f, B:8:0x0029, B:56:0x0109, B:11:0x0033, B:13:0x0039, B:15:0x003d, B:17:0x0041, B:19:0x0045, B:21:0x0049, B:23:0x0051, B:29:0x005f, B:31:0x0071, B:32:0x007c, B:34:0x0080, B:35:0x008c, B:37:0x0090, B:38:0x009c, B:40:0x00b5, B:41:0x00c1, B:42:0x00cb, B:44:0x00d1, B:45:0x00db, B:46:0x00e5, B:48:0x00eb, B:50:0x00f3, B:52:0x00f9, B:53:0x00ff, B:55:0x0103, B:57:0x010d), top: B:63:0x0015 }] */
    /* JADX WARN: Removed duplicated region for block: B:68:0x0109 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void writeXml(org.xmlpull.v1.XmlSerializer r12, boolean r13) throws java.io.IOException {
        /*
            Method dump skipped, instructions count: 280
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.RankingHelper.writeXml(org.xmlpull.v1.XmlSerializer, boolean):void");
    }

    private void updateConfig() {
        int N = this.mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            this.mSignalExtractors[i].setConfig(this);
        }
        this.mRankingHandler.requestSort();
    }

    /* JADX WARN: Removed duplicated region for block: B:33:0x00bd  */
    /* JADX WARN: Removed duplicated region for block: B:34:0x00be  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void sort(java.util.ArrayList<com.android.server.notification.NotificationRecord> r18) {
        /*
            r17 = this;
            r1 = r17
            r2 = r18
            int r3 = r18.size()
            int r0 = r3 + (-1)
        La:
            if (r0 < 0) goto L19
            java.lang.Object r4 = r2.get(r0)
            com.android.server.notification.NotificationRecord r4 = (com.android.server.notification.NotificationRecord) r4
            r5 = 0
            r4.setGlobalSortKey(r5)
            int r0 = r0 + (-1)
            goto La
        L19:
            com.android.server.notification.NotificationComparator r0 = r1.mPreliminaryComparator
            java.util.Collections.sort(r2, r0)
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r4 = r1.mProxyByGroupTmp
            monitor-enter(r4)
            int r0 = r3 + (-1)
        L23:
            if (r0 < 0) goto L47
            java.lang.Object r5 = r2.get(r0)     // Catch: java.lang.Throwable -> L44
            com.android.server.notification.NotificationRecord r5 = (com.android.server.notification.NotificationRecord) r5     // Catch: java.lang.Throwable -> L44
            r5.setAuthoritativeRank(r0)     // Catch: java.lang.Throwable -> L44
            java.lang.String r6 = r5.getGroupKey()     // Catch: java.lang.Throwable -> L44
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r7 = r1.mProxyByGroupTmp     // Catch: java.lang.Throwable -> L44
            java.lang.Object r7 = r7.get(r6)     // Catch: java.lang.Throwable -> L44
            com.android.server.notification.NotificationRecord r7 = (com.android.server.notification.NotificationRecord) r7     // Catch: java.lang.Throwable -> L44
            if (r7 != 0) goto L41
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r8 = r1.mProxyByGroupTmp     // Catch: java.lang.Throwable -> L44
            r8.put(r6, r5)     // Catch: java.lang.Throwable -> L44
        L41:
            int r0 = r0 + (-1)
            goto L23
        L44:
            r0 = move-exception
            goto Leb
        L47:
            r0 = 0
            r5 = r0
        L49:
            if (r5 >= r3) goto Ldf
            java.lang.Object r6 = r2.get(r5)     // Catch: java.lang.Throwable -> L44
            com.android.server.notification.NotificationRecord r6 = (com.android.server.notification.NotificationRecord) r6     // Catch: java.lang.Throwable -> L44
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r7 = r1.mProxyByGroupTmp     // Catch: java.lang.Throwable -> L44
            java.lang.String r8 = r6.getGroupKey()     // Catch: java.lang.Throwable -> L44
            java.lang.Object r7 = r7.get(r8)     // Catch: java.lang.Throwable -> L44
            com.android.server.notification.NotificationRecord r7 = (com.android.server.notification.NotificationRecord) r7     // Catch: java.lang.Throwable -> L44
            android.app.Notification r8 = r6.getNotification()     // Catch: java.lang.Throwable -> L44
            java.lang.String r8 = r8.getSortKey()     // Catch: java.lang.Throwable -> L44
            if (r8 != 0) goto L6b
            java.lang.String r9 = "nsk"
        L6a:
            goto L87
        L6b:
            java.lang.String r9 = ""
            boolean r9 = r8.equals(r9)     // Catch: java.lang.Throwable -> L44
            if (r9 == 0) goto L76
            java.lang.String r9 = "esk"
            goto L6a
        L76:
            java.lang.StringBuilder r9 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L44
            r9.<init>()     // Catch: java.lang.Throwable -> L44
            java.lang.String r10 = "gsk="
            r9.append(r10)     // Catch: java.lang.Throwable -> L44
            r9.append(r8)     // Catch: java.lang.Throwable -> L44
            java.lang.String r9 = r9.toString()     // Catch: java.lang.Throwable -> L44
        L87:
            android.app.Notification r10 = r6.getNotification()     // Catch: java.lang.Throwable -> L44
            boolean r10 = r10.isGroupSummary()     // Catch: java.lang.Throwable -> L44
            java.lang.String r11 = "intrsv=%c:grnk=0x%04x:gsmry=%c:%s:rnk=0x%04x"
            r12 = 5
            java.lang.Object[] r12 = new java.lang.Object[r12]     // Catch: java.lang.Throwable -> L44
            boolean r13 = r6.isRecentlyIntrusive()     // Catch: java.lang.Throwable -> L44
            r15 = 48
            r14 = 1
            if (r13 == 0) goto La7
            int r13 = r6.getImportance()     // Catch: java.lang.Throwable -> L44
            if (r13 <= r14) goto La7
        La5:
            r13 = r15
            goto Laa
        La7:
            r13 = 49
        Laa:
            java.lang.Character r13 = java.lang.Character.valueOf(r13)     // Catch: java.lang.Throwable -> L44
            r12[r0] = r13     // Catch: java.lang.Throwable -> L44
            int r13 = r7.getAuthoritativeRank()     // Catch: java.lang.Throwable -> L44
            java.lang.Integer r13 = java.lang.Integer.valueOf(r13)     // Catch: java.lang.Throwable -> L44
            r12[r14] = r13     // Catch: java.lang.Throwable -> L44
            r13 = 2
            if (r10 == 0) goto Lbe
            goto Lc0
        Lbe:
            r15 = 49
        Lc0:
            java.lang.Character r14 = java.lang.Character.valueOf(r15)     // Catch: java.lang.Throwable -> L44
            r12[r13] = r14     // Catch: java.lang.Throwable -> L44
            r13 = 3
            r12[r13] = r9     // Catch: java.lang.Throwable -> L44
            r13 = 4
            int r14 = r6.getAuthoritativeRank()     // Catch: java.lang.Throwable -> L44
            java.lang.Integer r14 = java.lang.Integer.valueOf(r14)     // Catch: java.lang.Throwable -> L44
            r12[r13] = r14     // Catch: java.lang.Throwable -> L44
            java.lang.String r11 = java.lang.String.format(r11, r12)     // Catch: java.lang.Throwable -> L44
            r6.setGlobalSortKey(r11)     // Catch: java.lang.Throwable -> L44
            int r5 = r5 + 1
            goto L49
        Ldf:
            android.util.ArrayMap<java.lang.String, com.android.server.notification.NotificationRecord> r0 = r1.mProxyByGroupTmp     // Catch: java.lang.Throwable -> L44
            r0.clear()     // Catch: java.lang.Throwable -> L44
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L44
            com.android.server.notification.GlobalSortKeyComparator r0 = r1.mFinalComparator
            java.util.Collections.sort(r2, r0)
            return
        Leb:
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L44
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.RankingHelper.sort(java.util.ArrayList):void");
    }

    public int indexOf(ArrayList<NotificationRecord> notificationList, NotificationRecord target) {
        return Collections.binarySearch(notificationList, target, this.mFinalComparator);
    }

    @Override // com.android.server.notification.RankingConfig
    public int getImportance(String packageName, int uid) {
        return getOrCreateRecord(packageName, uid).importance;
    }

    public boolean getIsAppImportanceLocked(String packageName, int uid) {
        int userLockedFields = getOrCreateRecord(packageName, uid).lockedAppFields;
        return (userLockedFields & 1) != 0;
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean canShowBadge(String packageName, int uid) {
        return getOrCreateRecord(packageName, uid).showBadge;
    }

    @Override // com.android.server.notification.RankingConfig
    public void setShowBadge(String packageName, int uid, boolean showBadge) {
        getOrCreateRecord(packageName, uid).showBadge = showBadge;
        updateConfig();
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean isGroupBlocked(String packageName, int uid, String groupId) {
        if (groupId == null) {
            return false;
        }
        Record r = getOrCreateRecord(packageName, uid);
        NotificationChannelGroup group = r.groups.get(groupId);
        if (group == null) {
            return false;
        }
        return group.isBlocked();
    }

    int getPackagePriority(String pkg, int uid) {
        return getOrCreateRecord(pkg, uid).priority;
    }

    int getPackageVisibility(String pkg, int uid) {
        return getOrCreateRecord(pkg, uid).visibility;
    }

    @Override // com.android.server.notification.RankingConfig
    public void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group, boolean fromTargetApp) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(group);
        Preconditions.checkNotNull(group.getId());
        Preconditions.checkNotNull(Boolean.valueOf(!TextUtils.isEmpty(group.getName())));
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        NotificationChannelGroup oldGroup = r.groups.get(group.getId());
        if (!group.equals(oldGroup)) {
            MetricsLogger.action(getChannelGroupLog(group.getId(), pkg));
        }
        if (oldGroup != null) {
            group.setChannels(oldGroup.getChannels());
            if (fromTargetApp) {
                group.setBlocked(oldGroup.isBlocked());
            }
        }
        r.groups.put(group.getId(), group);
    }

    @Override // com.android.server.notification.RankingConfig
    public void createNotificationChannel(String pkg, int uid, NotificationChannel channel, boolean fromTargetApp, boolean hasDndAccess) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channel);
        Preconditions.checkNotNull(channel.getId());
        Preconditions.checkArgument(!TextUtils.isEmpty(channel.getName()));
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        if (channel.getGroup() != null && !r.groups.containsKey(channel.getGroup())) {
            throw new IllegalArgumentException("NotificationChannelGroup doesn't exist");
        }
        if ("miscellaneous".equals(channel.getId())) {
            throw new IllegalArgumentException("Reserved id");
        }
        NotificationChannel existing = r.channels.get(channel.getId());
        if (existing != null && fromTargetApp) {
            if (existing.isDeleted()) {
                existing.setDeleted(false);
                MetricsLogger.action(getChannelLog(channel, pkg).setType(1));
            }
            existing.setName(channel.getName().toString());
            existing.setDescription(channel.getDescription());
            existing.setBlockableSystem(channel.isBlockableSystem());
            if (existing.getGroup() == null) {
                existing.setGroup(channel.getGroup());
            }
            if (existing.getUserLockedFields() == 0 && channel.getImportance() < existing.getImportance()) {
                existing.setImportance(channel.getImportance());
            }
            if (existing.getUserLockedFields() == 0 && hasDndAccess) {
                boolean bypassDnd = channel.canBypassDnd();
                existing.setBypassDnd(bypassDnd);
                if (bypassDnd != this.mAreChannelsBypassingDnd) {
                    updateChannelsBypassingDnd();
                }
            }
            updateConfig();
        } else if (channel.getImportance() < 0 || channel.getImportance() > 5) {
            throw new IllegalArgumentException("Invalid importance level");
        } else {
            if (fromTargetApp && !hasDndAccess) {
                channel.setBypassDnd(r.priority == 2);
            }
            if (fromTargetApp) {
                channel.setLockscreenVisibility(r.visibility);
            }
            clearLockedFields(channel);
            if (channel.getLockscreenVisibility() == 1) {
                channel.setLockscreenVisibility(JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
            }
            if (!r.showBadge) {
                channel.setShowBadge(false);
            }
            r.channels.put(channel.getId(), channel);
            if (channel.canBypassDnd() != this.mAreChannelsBypassingDnd) {
                updateChannelsBypassingDnd();
            }
            MetricsLogger.action(getChannelLog(channel, pkg).setType(1));
        }
    }

    void clearLockedFields(NotificationChannel channel) {
        channel.unlockFields(channel.getUserLockedFields());
    }

    @Override // com.android.server.notification.RankingConfig
    public void updateNotificationChannel(String pkg, int uid, NotificationChannel updatedChannel, boolean fromUser) {
        Preconditions.checkNotNull(updatedChannel);
        Preconditions.checkNotNull(updatedChannel.getId());
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            throw new IllegalArgumentException("Invalid package");
        }
        NotificationChannel channel = r.channels.get(updatedChannel.getId());
        if (channel == null || channel.isDeleted()) {
            throw new IllegalArgumentException("Channel does not exist");
        }
        if (updatedChannel.getLockscreenVisibility() == 1) {
            updatedChannel.setLockscreenVisibility(JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
        }
        if (!fromUser) {
            updatedChannel.unlockFields(updatedChannel.getUserLockedFields());
        }
        if (fromUser) {
            updatedChannel.lockFields(channel.getUserLockedFields());
            lockFieldsForUpdate(channel, updatedChannel);
        }
        r.channels.put(updatedChannel.getId(), updatedChannel);
        if ("miscellaneous".equals(updatedChannel.getId())) {
            r.importance = updatedChannel.getImportance();
            r.priority = updatedChannel.canBypassDnd() ? 2 : 0;
            r.visibility = updatedChannel.getLockscreenVisibility();
            r.showBadge = updatedChannel.canShowBadge();
        }
        if (!channel.equals(updatedChannel)) {
            MetricsLogger.action(getChannelLog(updatedChannel, pkg));
        }
        if (updatedChannel.canBypassDnd() != this.mAreChannelsBypassingDnd) {
            updateChannelsBypassingDnd();
        }
        updateConfig();
    }

    @Override // com.android.server.notification.RankingConfig
    public NotificationChannel getNotificationChannel(String pkg, int uid, String channelId, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        Record r = getOrCreateRecord(pkg, uid);
        if (r == null) {
            return null;
        }
        if (channelId == null) {
            channelId = "miscellaneous";
        }
        NotificationChannel nc = r.channels.get(channelId);
        if (nc == null || (!includeDeleted && nc.isDeleted())) {
            return null;
        }
        return nc;
    }

    @Override // com.android.server.notification.RankingConfig
    public void deleteNotificationChannel(String pkg, int uid, String channelId) {
        NotificationChannel channel;
        Record r = getRecord(pkg, uid);
        if (r != null && (channel = r.channels.get(channelId)) != null) {
            channel.setDeleted(true);
            LogMaker lm = getChannelLog(channel, pkg);
            lm.setType(2);
            MetricsLogger.action(lm);
            if (this.mAreChannelsBypassingDnd && channel.canBypassDnd()) {
                updateChannelsBypassingDnd();
            }
        }
    }

    @Override // com.android.server.notification.RankingConfig
    @VisibleForTesting
    public void permanentlyDeleteNotificationChannel(String pkg, int uid, String channelId) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channelId);
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return;
        }
        r.channels.remove(channelId);
    }

    @Override // com.android.server.notification.RankingConfig
    public void permanentlyDeleteNotificationChannels(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return;
        }
        int N = r.channels.size() - 1;
        for (int i = N; i >= 0; i--) {
            String key = r.channels.keyAt(i);
            if (!"miscellaneous".equals(key)) {
                r.channels.remove(key);
            }
        }
    }

    public NotificationChannelGroup getNotificationChannelGroupWithChannels(String pkg, int uid, String groupId, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        Record r = getRecord(pkg, uid);
        if (r == null || groupId == null || !r.groups.containsKey(groupId)) {
            return null;
        }
        NotificationChannelGroup group = r.groups.get(groupId).clone();
        group.setChannels(new ArrayList());
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            NotificationChannel nc = r.channels.valueAt(i);
            if ((includeDeleted || !nc.isDeleted()) && groupId.equals(nc.getGroup())) {
                group.addChannel(nc);
            }
        }
        return group;
    }

    public NotificationChannelGroup getNotificationChannelGroup(String groupId, String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return null;
        }
        return r.groups.get(groupId);
    }

    @Override // com.android.server.notification.RankingConfig
    public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(String pkg, int uid, boolean includeDeleted, boolean includeNonGrouped, boolean includeEmpty) {
        Preconditions.checkNotNull(pkg);
        Map<String, NotificationChannelGroup> groups = new ArrayMap<>();
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return ParceledListSlice.emptyList();
        }
        NotificationChannelGroup nonGrouped = new NotificationChannelGroup(null, null);
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            NotificationChannel nc = r.channels.valueAt(i);
            if (includeDeleted || !nc.isDeleted()) {
                if (nc.getGroup() != null) {
                    if (r.groups.get(nc.getGroup()) != null) {
                        NotificationChannelGroup ncg = groups.get(nc.getGroup());
                        if (ncg == null) {
                            ncg = r.groups.get(nc.getGroup()).clone();
                            ncg.setChannels(new ArrayList());
                            groups.put(nc.getGroup(), ncg);
                        }
                        ncg.addChannel(nc);
                    }
                } else {
                    nonGrouped.addChannel(nc);
                }
            }
        }
        if (includeNonGrouped && nonGrouped.getChannels().size() > 0) {
            groups.put(null, nonGrouped);
        }
        if (includeEmpty) {
            for (NotificationChannelGroup group : r.groups.values()) {
                if (!groups.containsKey(group.getId())) {
                    groups.put(group.getId(), group);
                }
            }
        }
        return new ParceledListSlice<>(new ArrayList(groups.values()));
    }

    public List<NotificationChannel> deleteNotificationChannelGroup(String pkg, int uid, String groupId) {
        List<NotificationChannel> deletedChannels = new ArrayList<>();
        Record r = getRecord(pkg, uid);
        if (r == null || TextUtils.isEmpty(groupId)) {
            return deletedChannels;
        }
        r.groups.remove(groupId);
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            NotificationChannel nc = r.channels.valueAt(i);
            if (groupId.equals(nc.getGroup())) {
                nc.setDeleted(true);
                deletedChannels.add(nc);
            }
        }
        return deletedChannels;
    }

    @Override // com.android.server.notification.RankingConfig
    public Collection<NotificationChannelGroup> getNotificationChannelGroups(String pkg, int uid) {
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return new ArrayList();
        }
        return r.groups.values();
    }

    @Override // com.android.server.notification.RankingConfig
    public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg, int uid, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        List<NotificationChannel> channels = new ArrayList<>();
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return ParceledListSlice.emptyList();
        }
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            NotificationChannel nc = r.channels.valueAt(i);
            if (includeDeleted || !nc.isDeleted()) {
                channels.add(nc);
            }
        }
        return new ParceledListSlice<>(channels);
    }

    public boolean onlyHasDefaultChannel(String pkg, int uid) {
        Record r = getOrCreateRecord(pkg, uid);
        return r.channels.size() == 1 && r.channels.containsKey("miscellaneous");
    }

    public int getDeletedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int deletedCount = 0;
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return 0;
        }
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            NotificationChannel nc = r.channels.valueAt(i);
            if (nc.isDeleted()) {
                deletedCount++;
            }
        }
        return deletedCount;
    }

    public int getBlockedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int blockedCount = 0;
        Record r = getRecord(pkg, uid);
        if (r == null) {
            return 0;
        }
        int N = r.channels.size();
        for (int i = 0; i < N; i++) {
            NotificationChannel nc = r.channels.valueAt(i);
            if (!nc.isDeleted() && nc.getImportance() == 0) {
                blockedCount++;
            }
        }
        return blockedCount;
    }

    public int getBlockedAppCount(int userId) {
        int count = 0;
        synchronized (this.mRecords) {
            int N = this.mRecords.size();
            for (int i = 0; i < N; i++) {
                Record r = this.mRecords.valueAt(i);
                if (userId == UserHandle.getUserId(r.uid) && r.importance == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    public void updateChannelsBypassingDnd() {
        synchronized (this.mRecords) {
            int numRecords = this.mRecords.size();
            for (int recordIndex = 0; recordIndex < numRecords; recordIndex++) {
                Record r = this.mRecords.valueAt(recordIndex);
                int numChannels = r.channels.size();
                for (int channelIndex = 0; channelIndex < numChannels; channelIndex++) {
                    NotificationChannel channel = r.channels.valueAt(channelIndex);
                    if (!channel.isDeleted() && channel.canBypassDnd()) {
                        if (!this.mAreChannelsBypassingDnd) {
                            this.mAreChannelsBypassingDnd = true;
                            updateZenPolicy(true);
                        }
                        return;
                    }
                }
            }
            if (this.mAreChannelsBypassingDnd) {
                this.mAreChannelsBypassingDnd = false;
                updateZenPolicy(false);
            }
        }
    }

    public void updateZenPolicy(boolean areChannelsBypassingDnd) {
        NotificationManager.Policy policy = this.mZenModeHelper.getNotificationPolicy();
        this.mZenModeHelper.setNotificationPolicy(new NotificationManager.Policy(policy.priorityCategories, policy.priorityCallSenders, policy.priorityMessageSenders, policy.suppressedVisualEffects, areChannelsBypassingDnd ? 1 : 0));
    }

    public boolean areChannelsBypassingDnd() {
        return this.mAreChannelsBypassingDnd;
    }

    @Override // com.android.server.notification.RankingConfig
    public void setImportance(String pkgName, int uid, int importance) {
        getOrCreateRecord(pkgName, uid).importance = importance;
        updateConfig();
    }

    public void setEnabled(String packageName, int uid, boolean enabled) {
        boolean wasEnabled = getImportance(packageName, uid) != 0;
        if (wasEnabled == enabled) {
            return;
        }
        setImportance(packageName, uid, enabled ? JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE : 0);
    }

    public void setAppImportanceLocked(String packageName, int uid) {
        Record record = getOrCreateRecord(packageName, uid);
        if ((record.lockedAppFields & 1) != 0) {
            return;
        }
        record.lockedAppFields |= 1;
        updateConfig();
    }

    @VisibleForTesting
    void lockFieldsForUpdate(NotificationChannel original, NotificationChannel update) {
        if (original.canBypassDnd() != update.canBypassDnd()) {
            update.lockFields(1);
        }
        if (original.getLockscreenVisibility() != update.getLockscreenVisibility()) {
            update.lockFields(2);
        }
        if (original.getImportance() != update.getImportance()) {
            update.lockFields(4);
        }
        if (original.shouldShowLights() != update.shouldShowLights() || original.getLightColor() != update.getLightColor()) {
            update.lockFields(8);
        }
        if (!Objects.equals(original.getSound(), update.getSound())) {
            update.lockFields(32);
        }
        if (!Arrays.equals(original.getVibrationPattern(), update.getVibrationPattern()) || original.shouldVibrate() != update.shouldVibrate()) {
            update.lockFields(16);
        }
        if (original.canShowBadge() != update.canShowBadge()) {
            update.lockFields(128);
        }
    }

    public void dump(PrintWriter pw, String prefix, NotificationManagerService.DumpFilter filter) {
        int N = this.mSignalExtractors.length;
        pw.print(prefix);
        pw.print("mSignalExtractors.length = ");
        pw.println(N);
        for (int i = 0; i < N; i++) {
            pw.print(prefix);
            pw.print("  ");
            pw.println(this.mSignalExtractors[i].getClass().getSimpleName());
        }
        pw.print(prefix);
        pw.println("per-package config:");
        pw.println("Records:");
        synchronized (this.mRecords) {
            dumpRecords(pw, prefix, filter, this.mRecords);
        }
        pw.println("Restored without uid:");
        dumpRecords(pw, prefix, filter, this.mRestoredWithoutUids);
    }

    public void dump(ProtoOutputStream proto, NotificationManagerService.DumpFilter filter) {
        int N = this.mSignalExtractors.length;
        for (int i = 0; i < N; i++) {
            proto.write(2237677961217L, this.mSignalExtractors[i].getClass().getSimpleName());
        }
        synchronized (this.mRecords) {
            dumpRecords(proto, 2246267895810L, filter, this.mRecords);
        }
        dumpRecords(proto, 2246267895811L, filter, this.mRestoredWithoutUids);
    }

    private static void dumpRecords(ProtoOutputStream proto, long fieldId, NotificationManagerService.DumpFilter filter, ArrayMap<String, Record> records) {
        int N = records.size();
        for (int i = 0; i < N; i++) {
            Record r = records.valueAt(i);
            if (filter.matches(r.pkg)) {
                long fToken = proto.start(fieldId);
                proto.write(1138166333441L, r.pkg);
                proto.write(1120986464258L, r.uid);
                proto.write(1172526071811L, r.importance);
                proto.write(1120986464260L, r.priority);
                proto.write(1172526071813L, r.visibility);
                proto.write(1133871366150L, r.showBadge);
                for (NotificationChannel channel : r.channels.values()) {
                    channel.writeToProto(proto, 2246267895815L);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    group.writeToProto(proto, 2246267895816L);
                }
                proto.end(fToken);
            }
        }
    }

    private static void dumpRecords(PrintWriter pw, String prefix, NotificationManagerService.DumpFilter filter, ArrayMap<String, Record> records) {
        int N = records.size();
        for (int i = 0; i < N; i++) {
            Record r = records.valueAt(i);
            if (filter.matches(r.pkg)) {
                pw.print(prefix);
                pw.print("  AppSettings: ");
                pw.print(r.pkg);
                pw.print(" (");
                pw.print(r.uid == Record.UNKNOWN_UID ? "UNKNOWN_UID" : Integer.toString(r.uid));
                pw.print(')');
                if (r.importance != -1000) {
                    pw.print(" importance=");
                    pw.print(NotificationListenerService.Ranking.importanceToString(r.importance));
                }
                if (r.priority != 0) {
                    pw.print(" priority=");
                    pw.print(Notification.priorityToString(r.priority));
                }
                if (r.visibility != -1000) {
                    pw.print(" visibility=");
                    pw.print(Notification.visibilityToString(r.visibility));
                }
                pw.print(" showBadge=");
                pw.print(Boolean.toString(r.showBadge));
                pw.println();
                for (NotificationChannel channel : r.channels.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.println(channel);
                }
                for (NotificationChannelGroup group : r.groups.values()) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.print("  ");
                    pw.println(group);
                }
            }
        }
    }

    public JSONObject dumpJson(NotificationManagerService.DumpFilter filter) {
        JSONObject ranking = new JSONObject();
        JSONArray records = new JSONArray();
        try {
            ranking.put("noUid", this.mRestoredWithoutUids.size());
        } catch (JSONException e) {
        }
        synchronized (this.mRecords) {
            int N = this.mRecords.size();
            for (int i = 0; i < N; i++) {
                Record r = this.mRecords.valueAt(i);
                if (filter == null || filter.matches(r.pkg)) {
                    JSONObject record = new JSONObject();
                    try {
                        record.put("userId", UserHandle.getUserId(r.uid));
                        record.put("packageName", r.pkg);
                        if (r.importance != -1000) {
                            record.put(ATT_IMPORTANCE, NotificationListenerService.Ranking.importanceToString(r.importance));
                        }
                        if (r.priority != 0) {
                            record.put("priority", Notification.priorityToString(r.priority));
                        }
                        if (r.visibility != -1000) {
                            record.put(ATT_VISIBILITY, Notification.visibilityToString(r.visibility));
                        }
                        if (!r.showBadge) {
                            record.put("showBadge", Boolean.valueOf(r.showBadge));
                        }
                        JSONArray channels = new JSONArray();
                        for (NotificationChannel channel : r.channels.values()) {
                            channels.put(channel.toJson());
                        }
                        record.put("channels", channels);
                        JSONArray groups = new JSONArray();
                        for (NotificationChannelGroup group : r.groups.values()) {
                            groups.put(group.toJson());
                        }
                        record.put(xpInputManagerService.InputPolicyKey.KEY_GROUPS, groups);
                    } catch (JSONException e2) {
                    }
                    records.put(record);
                }
            }
        }
        try {
            ranking.put("records", records);
        } catch (JSONException e3) {
        }
        return ranking;
    }

    public JSONArray dumpBansJson(NotificationManagerService.DumpFilter filter) {
        JSONArray bans = new JSONArray();
        Map<Integer, String> packageBans = getPackageBans();
        for (Map.Entry<Integer, String> ban : packageBans.entrySet()) {
            int userId = UserHandle.getUserId(ban.getKey().intValue());
            String packageName = ban.getValue();
            if (filter == null || filter.matches(packageName)) {
                JSONObject banJson = new JSONObject();
                try {
                    banJson.put("userId", userId);
                    banJson.put("packageName", packageName);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                bans.put(banJson);
            }
        }
        return bans;
    }

    public Map<Integer, String> getPackageBans() {
        ArrayMap<Integer, String> packageBans;
        synchronized (this.mRecords) {
            int N = this.mRecords.size();
            packageBans = new ArrayMap<>(N);
            for (int i = 0; i < N; i++) {
                Record r = this.mRecords.valueAt(i);
                if (r.importance == 0) {
                    packageBans.put(Integer.valueOf(r.uid), r.pkg);
                }
            }
        }
        return packageBans;
    }

    public JSONArray dumpChannelsJson(NotificationManagerService.DumpFilter filter) {
        JSONArray channels = new JSONArray();
        Map<String, Integer> packageChannels = getPackageChannels();
        for (Map.Entry<String, Integer> channelCount : packageChannels.entrySet()) {
            String packageName = channelCount.getKey();
            if (filter == null || filter.matches(packageName)) {
                JSONObject channelCountJson = new JSONObject();
                try {
                    channelCountJson.put("packageName", packageName);
                    channelCountJson.put("channelCount", channelCount.getValue());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                channels.put(channelCountJson);
            }
        }
        return channels;
    }

    private Map<String, Integer> getPackageChannels() {
        ArrayMap<String, Integer> packageChannels = new ArrayMap<>();
        synchronized (this.mRecords) {
            for (int i = 0; i < this.mRecords.size(); i++) {
                Record r = this.mRecords.valueAt(i);
                int channelCount = 0;
                for (int channelCount2 = 0; channelCount2 < r.channels.size(); channelCount2++) {
                    if (!r.channels.valueAt(channelCount2).isDeleted()) {
                        channelCount++;
                    }
                }
                packageChannels.put(r.pkg, Integer.valueOf(channelCount));
            }
        }
        return packageChannels;
    }

    public void onUserRemoved(int userId) {
        synchronized (this.mRecords) {
            int N = this.mRecords.size();
            for (int i = N - 1; i >= 0; i--) {
                Record record = this.mRecords.valueAt(i);
                if (UserHandle.getUserId(record.uid) == userId) {
                    this.mRecords.removeAt(i);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onLocaleChanged(Context context, int userId) {
        synchronized (this.mRecords) {
            int N = this.mRecords.size();
            for (int i = 0; i < N; i++) {
                Record record = this.mRecords.valueAt(i);
                if (UserHandle.getUserId(record.uid) == userId && record.channels.containsKey("miscellaneous")) {
                    record.channels.get("miscellaneous").setName(context.getResources().getString(17039796));
                }
            }
        }
    }

    public void onPackagesChanged(boolean removingPackage, int changeUserId, String[] pkgList, int[] uidList) {
        if (pkgList == null || pkgList.length == 0) {
            return;
        }
        boolean updated = false;
        int i = 0;
        if (removingPackage) {
            int size = Math.min(pkgList.length, uidList.length);
            while (i < size) {
                String pkg = pkgList[i];
                int uid = uidList[i];
                synchronized (this.mRecords) {
                    this.mRecords.remove(recordKey(pkg, uid));
                }
                this.mRestoredWithoutUids.remove(pkg);
                updated = true;
                i++;
            }
        } else {
            int length = pkgList.length;
            while (i < length) {
                String pkg2 = pkgList[i];
                Record r = this.mRestoredWithoutUids.get(pkg2);
                if (r != null) {
                    try {
                        r.uid = this.mPm.getPackageUidAsUser(r.pkg, changeUserId);
                        this.mRestoredWithoutUids.remove(pkg2);
                        synchronized (this.mRecords) {
                            this.mRecords.put(recordKey(r.pkg, r.uid), r);
                        }
                        updated = true;
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                }
                try {
                    Record fullRecord = getRecord(pkg2, this.mPm.getPackageUidAsUser(pkg2, changeUserId));
                    if (fullRecord != null) {
                        createDefaultChannelIfNeeded(fullRecord);
                        deleteDefaultChannelIfNeeded(fullRecord);
                    }
                } catch (PackageManager.NameNotFoundException e2) {
                }
                i++;
            }
        }
        if (updated) {
            updateConfig();
        }
    }

    private LogMaker getChannelLog(NotificationChannel channel, String pkg) {
        return new LogMaker(856).setType(6).setPackageName(pkg).addTaggedData(857, channel.getId()).addTaggedData(858, Integer.valueOf(channel.getImportance()));
    }

    private LogMaker getChannelGroupLog(String groupId, String pkg) {
        return new LogMaker(859).setType(6).addTaggedData(860, groupId).setPackageName(pkg);
    }

    public void updateBadgingEnabled() {
        if (this.mBadgingEnabled == null) {
            this.mBadgingEnabled = new SparseBooleanArray();
        }
        boolean changed = false;
        for (int index = 0; index < this.mBadgingEnabled.size(); index++) {
            int userId = this.mBadgingEnabled.keyAt(index);
            boolean oldValue = this.mBadgingEnabled.get(userId);
            boolean z = true;
            boolean newValue = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "notification_badging", 1, userId) != 0;
            this.mBadgingEnabled.put(userId, newValue);
            if (oldValue == newValue) {
                z = false;
            }
            changed |= z;
        }
        if (changed) {
            updateConfig();
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean badgingEnabled(UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        boolean z = false;
        if (userId == -1) {
            return false;
        }
        if (this.mBadgingEnabled.indexOfKey(userId) < 0) {
            SparseBooleanArray sparseBooleanArray = this.mBadgingEnabled;
            if (Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "notification_badging", 1, userId) != 0) {
                z = true;
            }
            sparseBooleanArray.put(userId, z);
        }
        return this.mBadgingEnabled.get(userId, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Record {
        static int UNKNOWN_UID = -10000;
        ArrayMap<String, NotificationChannel> channels;
        Map<String, NotificationChannelGroup> groups;
        int importance;
        int lockedAppFields;
        String pkg;
        int priority;
        boolean showBadge;
        int uid;
        int visibility;

        private Record() {
            this.uid = UNKNOWN_UID;
            this.importance = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            this.priority = 0;
            this.visibility = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            this.showBadge = true;
            this.lockedAppFields = 0;
            this.channels = new ArrayMap<>();
            this.groups = new ConcurrentHashMap();
        }
    }
}
