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
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.Preconditions;
import com.android.server.job.JobSchedulerShellCommand;
import com.android.server.notification.NotificationManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class PreferencesHelper implements RankingConfig {
    private static final String ATT_ALLOW_BUBBLE = "allow_bubble";
    private static final String ATT_APP_USER_LOCKED_FIELDS = "app_user_locked_fields";
    private static final String ATT_ENABLED = "enabled";
    private static final String ATT_HIDE_SILENT = "hide_gentle";
    private static final String ATT_ID = "id";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_NAME = "name";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_SHOW_BADGE = "show_badge";
    private static final String ATT_UID = "uid";
    private static final String ATT_USER_ALLOWED = "allowed";
    private static final String ATT_VERSION = "version";
    private static final String ATT_VISIBILITY = "visibility";
    static final boolean DEFAULT_ALLOW_BUBBLE = true;
    private static final boolean DEFAULT_APP_LOCKED_IMPORTANCE = false;
    @VisibleForTesting
    static final boolean DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS = false;
    private static final int DEFAULT_IMPORTANCE = -1000;
    private static final int DEFAULT_LOCKED_APP_FIELDS = 0;
    private static final boolean DEFAULT_OEM_LOCKED_IMPORTANCE = false;
    private static final int DEFAULT_PRIORITY = 0;
    private static final boolean DEFAULT_SHOW_BADGE = true;
    private static final int DEFAULT_VISIBILITY = -1000;
    private static final String NON_BLOCKABLE_CHANNEL_DELIM = ":";
    @VisibleForTesting
    static final int NOTIFICATION_CHANNEL_COUNT_LIMIT = 50000;
    private static final String TAG = "NotificationPrefHelper";
    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_DELEGATE = "delegate";
    private static final String TAG_GROUP = "channelGroup";
    private static final String TAG_PACKAGE = "package";
    @VisibleForTesting
    static final String TAG_RANKING = "ranking";
    private static final String TAG_STATUS_ICONS = "silent_status_icons";
    private static final int UNKNOWN_UID = -10000;
    private static final int XML_VERSION = 1;
    private boolean mAreChannelsBypassingDnd;
    private SparseBooleanArray mBadgingEnabled;
    private final Context mContext;
    private final PackageManager mPm;
    private final RankingHandler mRankingHandler;
    private final ZenModeHelper mZenModeHelper;
    private final ArrayMap<String, PackagePreferences> mPackagePreferences = new ArrayMap<>();
    private final ArrayMap<String, PackagePreferences> mRestoredWithoutUids = new ArrayMap<>();
    private boolean mBubblesEnabled = true;
    private boolean mHideSilentStatusBarIcons = false;

    /* loaded from: classes.dex */
    public @interface LockableAppFields {
        public static final int USER_LOCKED_BUBBLE = 2;
        public static final int USER_LOCKED_IMPORTANCE = 1;
    }

    public PreferencesHelper(Context context, PackageManager pm, RankingHandler rankingHandler, ZenModeHelper zenHelper) {
        this.mContext = context;
        this.mZenModeHelper = zenHelper;
        this.mRankingHandler = rankingHandler;
        this.mPm = pm;
        updateBadgingEnabled();
        updateBubblesEnabled();
        syncChannelsBypassingDnd(this.mContext.getUserId());
    }

    /* JADX WARN: Code restructure failed: missing block: B:107:0x0242, code lost:
        throw new java.lang.IllegalStateException("Failed to reach END_DOCUMENT");
     */
    /* JADX WARN: Code restructure failed: missing block: B:90:0x01fa, code lost:
        deleteDefaultChannelIfNeededLocked(r1);
     */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:110:0x0246 -> B:111:0x0247). Please submit an issue!!! */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void readXml(org.xmlpull.v1.XmlPullParser r24, boolean r25, int r26) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        /*
            Method dump skipped, instructions count: 585
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.PreferencesHelper.readXml(org.xmlpull.v1.XmlPullParser, boolean, int):void");
    }

    private PackagePreferences getPackagePreferencesLocked(String pkg, int uid) {
        String key = packagePreferencesKey(pkg, uid);
        return this.mPackagePreferences.get(key);
    }

    private PackagePreferences getOrCreatePackagePreferencesLocked(String pkg, int uid) {
        return getOrCreatePackagePreferencesLocked(pkg, uid, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE, 0, JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE, true, true);
    }

    private PackagePreferences getOrCreatePackagePreferencesLocked(String pkg, int uid, int importance, int priority, int visibility, boolean showBadge, boolean allowBubble) {
        String key = packagePreferencesKey(pkg, uid);
        PackagePreferences r = uid == UNKNOWN_UID ? this.mRestoredWithoutUids.get(pkg) : this.mPackagePreferences.get(key);
        if (r == null) {
            r = new PackagePreferences();
            r.pkg = pkg;
            r.uid = uid;
            r.importance = importance;
            r.priority = priority;
            r.visibility = visibility;
            r.showBadge = showBadge;
            r.allowBubble = allowBubble;
            try {
                createDefaultChannelIfNeededLocked(r);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "createDefaultChannelIfNeededLocked - Exception: " + e);
            }
            if (r.uid == UNKNOWN_UID) {
                this.mRestoredWithoutUids.put(pkg, r);
            } else {
                this.mPackagePreferences.put(key, r);
            }
        }
        return r;
    }

    private boolean shouldHaveDefaultChannel(PackagePreferences r) throws PackageManager.NameNotFoundException {
        int userId = UserHandle.getUserId(r.uid);
        ApplicationInfo applicationInfo = this.mPm.getApplicationInfoAsUser(r.pkg, 0, userId);
        if (applicationInfo.targetSdkVersion >= 26) {
            return false;
        }
        return true;
    }

    private boolean deleteDefaultChannelIfNeededLocked(PackagePreferences r) throws PackageManager.NameNotFoundException {
        if (r.channels.containsKey("miscellaneous") && !shouldHaveDefaultChannel(r)) {
            r.channels.remove("miscellaneous");
            return true;
        }
        return false;
    }

    private boolean createDefaultChannelIfNeededLocked(PackagePreferences r) throws PackageManager.NameNotFoundException {
        if (r.channels.containsKey("miscellaneous")) {
            r.channels.get("miscellaneous").setName(this.mContext.getString(17039857));
            return false;
        } else if (shouldHaveDefaultChannel(r)) {
            NotificationChannel channel = new NotificationChannel("miscellaneous", this.mContext.getString(17039857), r.importance);
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
            return true;
        } else {
            return false;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:38:0x0085 A[Catch: all -> 0x0196, TryCatch #0 {, blocks: (B:9:0x0034, B:11:0x003d, B:13:0x0047, B:77:0x018a, B:16:0x0051, B:18:0x0057, B:20:0x005b, B:22:0x005f, B:24:0x0063, B:26:0x0067, B:28:0x006f, B:30:0x0077, B:32:0x007b, B:38:0x0085, B:40:0x0097, B:41:0x00a2, B:43:0x00a6, B:44:0x00b2, B:46:0x00b6, B:47:0x00c2, B:49:0x00c6, B:50:0x00d1, B:52:0x00ea, B:53:0x00f6, B:55:0x00fa, B:57:0x011d, B:58:0x012a, B:60:0x0130, B:61:0x013d, B:62:0x0142, B:63:0x014c, B:65:0x0152, B:66:0x015c, B:67:0x0166, B:69:0x016c, B:71:0x0174, B:73:0x017a, B:74:0x0180, B:76:0x0184, B:78:0x018e), top: B:84:0x0034 }] */
    /* JADX WARN: Removed duplicated region for block: B:89:0x018a A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void writeXml(org.xmlpull.v1.XmlSerializer r11, boolean r12, int r13) throws java.io.IOException {
        /*
            Method dump skipped, instructions count: 409
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.PreferencesHelper.writeXml(org.xmlpull.v1.XmlSerializer, boolean, int):void");
    }

    public void setBubblesAllowed(String pkg, int uid, boolean allowed) {
        boolean changed;
        synchronized (this.mPackagePreferences) {
            PackagePreferences p = getOrCreatePackagePreferencesLocked(pkg, uid);
            changed = p.allowBubble != allowed;
            p.allowBubble = allowed;
            p.lockedAppFields |= 2;
        }
        if (changed) {
            updateConfig();
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean areBubblesAllowed(String pkg, int uid) {
        boolean z;
        synchronized (this.mPackagePreferences) {
            z = getOrCreatePackagePreferencesLocked(pkg, uid).allowBubble;
        }
        return z;
    }

    public int getAppLockedFields(String pkg, int uid) {
        int i;
        synchronized (this.mPackagePreferences) {
            i = getOrCreatePackagePreferencesLocked(pkg, uid).lockedAppFields;
        }
        return i;
    }

    @Override // com.android.server.notification.RankingConfig
    public int getImportance(String packageName, int uid) {
        int i;
        synchronized (this.mPackagePreferences) {
            i = getOrCreatePackagePreferencesLocked(packageName, uid).importance;
        }
        return i;
    }

    public boolean getIsAppImportanceLocked(String packageName, int uid) {
        boolean z;
        synchronized (this.mPackagePreferences) {
            int userLockedFields = getOrCreatePackagePreferencesLocked(packageName, uid).lockedAppFields;
            z = (userLockedFields & 1) != 0;
        }
        return z;
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean canShowBadge(String packageName, int uid) {
        boolean z;
        synchronized (this.mPackagePreferences) {
            z = getOrCreatePackagePreferencesLocked(packageName, uid).showBadge;
        }
        return z;
    }

    @Override // com.android.server.notification.RankingConfig
    public void setShowBadge(String packageName, int uid, boolean showBadge) {
        synchronized (this.mPackagePreferences) {
            getOrCreatePackagePreferencesLocked(packageName, uid).showBadge = showBadge;
        }
        updateConfig();
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean isGroupBlocked(String packageName, int uid, String groupId) {
        if (groupId == null) {
            return false;
        }
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(packageName, uid);
            NotificationChannelGroup group = r.groups.get(groupId);
            if (group == null) {
                return false;
            }
            return group.isBlocked();
        }
    }

    int getPackagePriority(String pkg, int uid) {
        int i;
        synchronized (this.mPackagePreferences) {
            i = getOrCreatePackagePreferencesLocked(pkg, uid).priority;
        }
        return i;
    }

    int getPackageVisibility(String pkg, int uid) {
        int i;
        synchronized (this.mPackagePreferences) {
            i = getOrCreatePackagePreferencesLocked(pkg, uid).visibility;
        }
        return i;
    }

    @Override // com.android.server.notification.RankingConfig
    public void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group, boolean fromTargetApp) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(group);
        Preconditions.checkNotNull(group.getId());
        Preconditions.checkNotNull(Boolean.valueOf(!TextUtils.isEmpty(group.getName())));
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
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
                    group.unlockFields(group.getUserLockedFields());
                    group.lockFields(oldGroup.getUserLockedFields());
                } else if (group.isBlocked() != oldGroup.isBlocked()) {
                    group.lockFields(1);
                    updateChannelsBypassingDnd(this.mContext.getUserId());
                }
            }
            r.groups.put(group.getId(), group);
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean createNotificationChannel(String pkg, int uid, NotificationChannel channel, boolean fromTargetApp, boolean hasDndAccess) {
        boolean bypassDnd;
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channel);
        Preconditions.checkNotNull(channel.getId());
        Preconditions.checkArgument(!TextUtils.isEmpty(channel.getName()));
        boolean needsPolicyFileChange = false;
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
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
                    needsPolicyFileChange = true;
                    MetricsLogger.action(getChannelLog(channel, pkg).setType(1));
                }
                if (!Objects.equals(channel.getName().toString(), existing.getName().toString())) {
                    existing.setName(channel.getName().toString());
                    needsPolicyFileChange = true;
                }
                if (!Objects.equals(channel.getDescription(), existing.getDescription())) {
                    existing.setDescription(channel.getDescription());
                    needsPolicyFileChange = true;
                }
                if (channel.isBlockableSystem() != existing.isBlockableSystem()) {
                    existing.setBlockableSystem(channel.isBlockableSystem());
                    needsPolicyFileChange = true;
                }
                if (channel.getGroup() != null && existing.getGroup() == null) {
                    existing.setGroup(channel.getGroup());
                    needsPolicyFileChange = true;
                }
                int previousExistingImportance = existing.getImportance();
                if (existing.getUserLockedFields() == 0 && channel.getImportance() < existing.getImportance()) {
                    existing.setImportance(channel.getImportance());
                    needsPolicyFileChange = true;
                }
                if (existing.getUserLockedFields() == 0 && hasDndAccess && (bypassDnd = channel.canBypassDnd()) != existing.canBypassDnd()) {
                    existing.setBypassDnd(bypassDnd);
                    needsPolicyFileChange = true;
                    if (bypassDnd != this.mAreChannelsBypassingDnd || previousExistingImportance != existing.getImportance()) {
                        updateChannelsBypassingDnd(this.mContext.getUserId());
                    }
                }
                updateConfig();
                return needsPolicyFileChange;
            } else if (r.channels.size() >= NOTIFICATION_CHANNEL_COUNT_LIMIT) {
                throw new IllegalStateException("Limit exceed; cannot create more channels");
            } else {
                if (channel.getImportance() < 0 || channel.getImportance() > 5) {
                    throw new IllegalArgumentException("Invalid importance level");
                }
                if (fromTargetApp && !hasDndAccess) {
                    channel.setBypassDnd(r.priority == 2);
                }
                if (fromTargetApp) {
                    channel.setLockscreenVisibility(r.visibility);
                }
                clearLockedFieldsLocked(channel);
                channel.setImportanceLockedByOEM(r.oemLockedImportance);
                if (!channel.isImportanceLockedByOEM() && r.futureOemLockedChannels.remove(channel.getId())) {
                    channel.setImportanceLockedByOEM(true);
                }
                channel.setImportanceLockedByCriticalDeviceFunction(r.defaultAppLockedImportance);
                if (channel.getLockscreenVisibility() == 1) {
                    channel.setLockscreenVisibility(JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
                }
                if (!r.showBadge) {
                    channel.setShowBadge(false);
                }
                r.channels.put(channel.getId(), channel);
                if (channel.canBypassDnd() != this.mAreChannelsBypassingDnd) {
                    updateChannelsBypassingDnd(this.mContext.getUserId());
                }
                MetricsLogger.action(getChannelLog(channel, pkg).setType(1));
                return true;
            }
        }
    }

    void clearLockedFieldsLocked(NotificationChannel channel) {
        channel.unlockFields(channel.getUserLockedFields());
    }

    @Override // com.android.server.notification.RankingConfig
    public void updateNotificationChannel(String pkg, int uid, NotificationChannel updatedChannel, boolean fromUser) {
        Preconditions.checkNotNull(updatedChannel);
        Preconditions.checkNotNull(updatedChannel.getId());
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            if (r == null) {
                throw new IllegalArgumentException("Invalid package");
            }
            NotificationChannel channel = r.channels.get(updatedChannel.getId());
            if (channel == null || channel.isDeleted()) {
                throw new IllegalArgumentException("Channel does not exist");
            }
            int i = 1;
            if (updatedChannel.getLockscreenVisibility() == 1) {
                updatedChannel.setLockscreenVisibility(JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE);
            }
            if (fromUser) {
                updatedChannel.lockFields(channel.getUserLockedFields());
                lockFieldsForUpdateLocked(channel, updatedChannel);
            } else {
                updatedChannel.unlockFields(updatedChannel.getUserLockedFields());
            }
            updatedChannel.setImportanceLockedByOEM(channel.isImportanceLockedByOEM());
            if (updatedChannel.isImportanceLockedByOEM()) {
                updatedChannel.setImportance(channel.getImportance());
            }
            updatedChannel.setImportanceLockedByCriticalDeviceFunction(r.defaultAppLockedImportance);
            if (updatedChannel.isImportanceLockedByCriticalDeviceFunction() && updatedChannel.getImportance() == 0) {
                updatedChannel.setImportance(channel.getImportance());
            }
            r.channels.put(updatedChannel.getId(), updatedChannel);
            if (onlyHasDefaultChannel(pkg, uid)) {
                r.importance = updatedChannel.getImportance();
                r.priority = updatedChannel.canBypassDnd() ? 2 : 0;
                r.visibility = updatedChannel.getLockscreenVisibility();
                r.showBadge = updatedChannel.canShowBadge();
            }
            if (!channel.equals(updatedChannel)) {
                LogMaker channelLog = getChannelLog(updatedChannel, pkg);
                if (!fromUser) {
                    i = 0;
                }
                MetricsLogger.action(channelLog.setSubtype(i));
            }
            if (updatedChannel.canBypassDnd() != this.mAreChannelsBypassingDnd || channel.getImportance() != updatedChannel.getImportance()) {
                updateChannelsBypassingDnd(this.mContext.getUserId());
            }
        }
        updateConfig();
    }

    @Override // com.android.server.notification.RankingConfig
    public NotificationChannel getNotificationChannel(String pkg, int uid, String channelId, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
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
    }

    @Override // com.android.server.notification.RankingConfig
    public void deleteNotificationChannel(String pkg, int uid, String channelId) {
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return;
            }
            NotificationChannel channel = r.channels.get(channelId);
            if (channel != null) {
                channel.setDeleted(true);
                LogMaker lm = getChannelLog(channel, pkg);
                lm.setType(2);
                MetricsLogger.action(lm);
                if (this.mAreChannelsBypassingDnd && channel.canBypassDnd()) {
                    updateChannelsBypassingDnd(this.mContext.getUserId());
                }
            }
        }
    }

    @Override // com.android.server.notification.RankingConfig
    @VisibleForTesting
    public void permanentlyDeleteNotificationChannel(String pkg, int uid, String channelId) {
        Preconditions.checkNotNull(pkg);
        Preconditions.checkNotNull(channelId);
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return;
            }
            r.channels.remove(channelId);
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public void permanentlyDeleteNotificationChannels(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
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
    }

    public boolean shouldHideSilentStatusIcons() {
        return this.mHideSilentStatusBarIcons;
    }

    public void setHideSilentStatusIcons(boolean hide) {
        this.mHideSilentStatusBarIcons = hide;
    }

    public void lockChannelsForOEM(String[] appOrChannelList) {
        String[] appSplit;
        if (appOrChannelList == null) {
            return;
        }
        for (String appOrChannel : appOrChannelList) {
            if (!TextUtils.isEmpty(appOrChannel) && (appSplit = appOrChannel.split(NON_BLOCKABLE_CHANNEL_DELIM)) != null && appSplit.length > 0) {
                String appName = appSplit[0];
                String channelId = appSplit.length == 2 ? appSplit[1] : null;
                synchronized (this.mPackagePreferences) {
                    for (PackagePreferences r : this.mPackagePreferences.values()) {
                        if (r.pkg.equals(appName)) {
                            if (channelId == null) {
                                r.oemLockedImportance = true;
                                for (NotificationChannel channel : r.channels.values()) {
                                    channel.setImportanceLockedByOEM(true);
                                }
                            } else {
                                NotificationChannel channel2 = r.channels.get(channelId);
                                if (channel2 != null) {
                                    channel2.setImportanceLockedByOEM(true);
                                } else {
                                    r.futureOemLockedChannels.add(channelId);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void updateDefaultApps(int userId, ArraySet<String> toRemove, ArraySet<Pair<String, Integer>> toAdd) {
        synchronized (this.mPackagePreferences) {
            for (PackagePreferences p : this.mPackagePreferences.values()) {
                if (userId == UserHandle.getUserId(p.uid) && toRemove != null && toRemove.contains(p.pkg)) {
                    p.defaultAppLockedImportance = false;
                    for (NotificationChannel channel : p.channels.values()) {
                        channel.setImportanceLockedByCriticalDeviceFunction(false);
                    }
                }
            }
            if (toAdd != null) {
                Iterator<Pair<String, Integer>> it = toAdd.iterator();
                while (it.hasNext()) {
                    Pair<String, Integer> approvedApp = it.next();
                    PackagePreferences p2 = getOrCreatePackagePreferencesLocked((String) approvedApp.first, ((Integer) approvedApp.second).intValue());
                    p2.defaultAppLockedImportance = true;
                    for (NotificationChannel channel2 : p2.channels.values()) {
                        channel2.setImportanceLockedByCriticalDeviceFunction(true);
                    }
                }
            }
        }
    }

    public NotificationChannelGroup getNotificationChannelGroupWithChannels(String pkg, int uid, String groupId, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r != null && groupId != null && r.groups.containsKey(groupId)) {
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
            return null;
        }
    }

    public NotificationChannelGroup getNotificationChannelGroup(String groupId, String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return null;
            }
            return r.groups.get(groupId);
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(String pkg, int uid, boolean includeDeleted, boolean includeNonGrouped, boolean includeEmpty) {
        Preconditions.checkNotNull(pkg);
        Map<String, NotificationChannelGroup> groups = new ArrayMap<>();
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
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
    }

    public List<NotificationChannel> deleteNotificationChannelGroup(String pkg, int uid, String groupId) {
        List<NotificationChannel> deletedChannels = new ArrayList<>();
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r != null && !TextUtils.isEmpty(groupId)) {
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
            return deletedChannels;
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public Collection<NotificationChannelGroup> getNotificationChannelGroups(String pkg, int uid) {
        List<NotificationChannelGroup> groups = new ArrayList<>();
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
            if (r == null) {
                return groups;
            }
            groups.addAll(r.groups.values());
            return groups;
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg, int uid, boolean includeDeleted) {
        Preconditions.checkNotNull(pkg);
        List<NotificationChannel> channels = new ArrayList<>();
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
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
    }

    public ParceledListSlice<NotificationChannel> getNotificationChannelsBypassingDnd(String pkg, int userId) {
        List<NotificationChannel> channels = new ArrayList<>();
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = this.mPackagePreferences.get(packagePreferencesKey(pkg, userId));
            if (r != null && r.importance != 0) {
                for (NotificationChannel channel : r.channels.values()) {
                    if (channelIsLiveLocked(r, channel) && channel.canBypassDnd()) {
                        channels.add(channel);
                    }
                }
            }
        }
        return new ParceledListSlice<>(channels);
    }

    public boolean onlyHasDefaultChannel(String pkg, int uid) {
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getOrCreatePackagePreferencesLocked(pkg, uid);
            return r.channels.size() == 1 && r.channels.containsKey("miscellaneous");
        }
    }

    public int getDeletedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int deletedCount = 0;
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
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
    }

    public int getBlockedChannelCount(String pkg, int uid) {
        Preconditions.checkNotNull(pkg);
        int blockedCount = 0;
        synchronized (this.mPackagePreferences) {
            PackagePreferences r = getPackagePreferencesLocked(pkg, uid);
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
    }

    public int getBlockedAppCount(int userId) {
        int count = 0;
        synchronized (this.mPackagePreferences) {
            int N = this.mPackagePreferences.size();
            for (int i = 0; i < N; i++) {
                PackagePreferences r = this.mPackagePreferences.valueAt(i);
                if (userId == UserHandle.getUserId(r.uid) && r.importance == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    public int getAppsBypassingDndCount(int userId) {
        int count = 0;
        synchronized (this.mPackagePreferences) {
            int numPackagePreferences = this.mPackagePreferences.size();
            for (int i = 0; i < numPackagePreferences; i++) {
                PackagePreferences r = this.mPackagePreferences.valueAt(i);
                if (userId == UserHandle.getUserId(r.uid) && r.importance != 0) {
                    Iterator<NotificationChannel> it = r.channels.values().iterator();
                    while (true) {
                        if (it.hasNext()) {
                            NotificationChannel channel = it.next();
                            if (channelIsLiveLocked(r, channel) && channel.canBypassDnd()) {
                                count++;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    private void syncChannelsBypassingDnd(int userId) {
        this.mAreChannelsBypassingDnd = (this.mZenModeHelper.getNotificationPolicy().state & 1) == 1;
        updateChannelsBypassingDnd(userId);
    }

    private void updateChannelsBypassingDnd(int userId) {
        synchronized (this.mPackagePreferences) {
            int numPackagePreferences = this.mPackagePreferences.size();
            for (int i = 0; i < numPackagePreferences; i++) {
                PackagePreferences r = this.mPackagePreferences.valueAt(i);
                if (userId == UserHandle.getUserId(r.uid) && r.importance != 0) {
                    for (NotificationChannel channel : r.channels.values()) {
                        if (channelIsLiveLocked(r, channel) && channel.canBypassDnd()) {
                            if (!this.mAreChannelsBypassingDnd) {
                                this.mAreChannelsBypassingDnd = true;
                                updateZenPolicy(true);
                            }
                            return;
                        }
                    }
                    continue;
                }
            }
            if (this.mAreChannelsBypassingDnd) {
                this.mAreChannelsBypassingDnd = false;
                updateZenPolicy(false);
            }
        }
    }

    private boolean channelIsLiveLocked(PackagePreferences pkgPref, NotificationChannel channel) {
        return (isGroupBlocked(pkgPref.pkg, pkgPref.uid, channel.getGroup()) || channel.isDeleted() || channel.getImportance() == 0) ? false : true;
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
        synchronized (this.mPackagePreferences) {
            getOrCreatePackagePreferencesLocked(pkgName, uid).importance = importance;
        }
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
        synchronized (this.mPackagePreferences) {
            PackagePreferences prefs = getOrCreatePackagePreferencesLocked(packageName, uid);
            if ((prefs.lockedAppFields & 1) != 0) {
                return;
            }
            prefs.lockedAppFields |= 1;
            updateConfig();
        }
    }

    public String getNotificationDelegate(String sourcePkg, int sourceUid) {
        synchronized (this.mPackagePreferences) {
            PackagePreferences prefs = getPackagePreferencesLocked(sourcePkg, sourceUid);
            if (prefs != null && prefs.delegate != null) {
                if (prefs.delegate.mUserAllowed && prefs.delegate.mEnabled) {
                    return prefs.delegate.mPkg;
                }
                return null;
            }
            return null;
        }
    }

    public void setNotificationDelegate(String sourcePkg, int sourceUid, String delegatePkg, int delegateUid) {
        boolean userAllowed;
        synchronized (this.mPackagePreferences) {
            PackagePreferences prefs = getOrCreatePackagePreferencesLocked(sourcePkg, sourceUid);
            if (prefs.delegate != null && !prefs.delegate.mUserAllowed) {
                userAllowed = false;
                Delegate delegate = new Delegate(delegatePkg, delegateUid, true, userAllowed);
                prefs.delegate = delegate;
            }
            userAllowed = true;
            Delegate delegate2 = new Delegate(delegatePkg, delegateUid, true, userAllowed);
            prefs.delegate = delegate2;
        }
        updateConfig();
    }

    public void revokeNotificationDelegate(String sourcePkg, int sourceUid) {
        boolean changed = false;
        synchronized (this.mPackagePreferences) {
            PackagePreferences prefs = getPackagePreferencesLocked(sourcePkg, sourceUid);
            if (prefs != null && prefs.delegate != null) {
                prefs.delegate.mEnabled = false;
                changed = true;
            }
        }
        if (changed) {
            updateConfig();
        }
    }

    public void toggleNotificationDelegate(String sourcePkg, int sourceUid, boolean userAllowed) {
        boolean changed = false;
        synchronized (this.mPackagePreferences) {
            PackagePreferences prefs = getPackagePreferencesLocked(sourcePkg, sourceUid);
            if (prefs != null && prefs.delegate != null) {
                prefs.delegate.mUserAllowed = userAllowed;
                changed = true;
            }
        }
        if (changed) {
            updateConfig();
        }
    }

    public boolean isDelegateAllowed(String sourcePkg, int sourceUid, String potentialDelegatePkg, int potentialDelegateUid) {
        boolean z;
        synchronized (this.mPackagePreferences) {
            PackagePreferences prefs = getPackagePreferencesLocked(sourcePkg, sourceUid);
            z = prefs != null && prefs.isValidDelegate(potentialDelegatePkg, potentialDelegateUid);
        }
        return z;
    }

    @VisibleForTesting
    void lockFieldsForUpdateLocked(NotificationChannel original, NotificationChannel update) {
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
        if (original.canBubble() != update.canBubble()) {
            update.lockFields(256);
        }
    }

    public void dump(PrintWriter pw, String prefix, NotificationManagerService.DumpFilter filter) {
        pw.print(prefix);
        pw.println("per-package config:");
        pw.println("PackagePreferences:");
        synchronized (this.mPackagePreferences) {
            dumpPackagePreferencesLocked(pw, prefix, filter, this.mPackagePreferences);
        }
        pw.println("Restored without uid:");
        dumpPackagePreferencesLocked(pw, prefix, filter, this.mRestoredWithoutUids);
    }

    public void dump(ProtoOutputStream proto, NotificationManagerService.DumpFilter filter) {
        synchronized (this.mPackagePreferences) {
            dumpPackagePreferencesLocked(proto, 2246267895810L, filter, this.mPackagePreferences);
        }
        dumpPackagePreferencesLocked(proto, 2246267895811L, filter, this.mRestoredWithoutUids);
    }

    private static void dumpPackagePreferencesLocked(PrintWriter pw, String prefix, NotificationManagerService.DumpFilter filter, ArrayMap<String, PackagePreferences> packagePreferences) {
        int N = packagePreferences.size();
        for (int i = 0; i < N; i++) {
            PackagePreferences r = packagePreferences.valueAt(i);
            if (filter.matches(r.pkg)) {
                pw.print(prefix);
                pw.print("  AppSettings: ");
                pw.print(r.pkg);
                pw.print(" (");
                pw.print(r.uid == UNKNOWN_UID ? "UNKNOWN_UID" : Integer.toString(r.uid));
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
                if (!r.showBadge) {
                    pw.print(" showBadge=");
                    pw.print(r.showBadge);
                }
                if (r.defaultAppLockedImportance) {
                    pw.print(" defaultAppLocked=");
                    pw.print(r.defaultAppLockedImportance);
                }
                if (r.oemLockedImportance) {
                    pw.print(" oemLocked=");
                    pw.print(r.oemLockedImportance);
                }
                if (!r.futureOemLockedChannels.isEmpty()) {
                    pw.print(" futureLockedChannels=");
                    pw.print(r.futureOemLockedChannels);
                }
                pw.println();
                for (NotificationChannel channel : r.channels.values()) {
                    pw.print(prefix);
                    channel.dump(pw, "    ", filter.redact);
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

    private static void dumpPackagePreferencesLocked(ProtoOutputStream proto, long fieldId, NotificationManagerService.DumpFilter filter, ArrayMap<String, PackagePreferences> packagePreferences) {
        int N = packagePreferences.size();
        for (int i = 0; i < N; i++) {
            PackagePreferences r = packagePreferences.valueAt(i);
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

    public JSONObject dumpJson(NotificationManagerService.DumpFilter filter) {
        JSONObject ranking = new JSONObject();
        JSONArray PackagePreferencess = new JSONArray();
        try {
            ranking.put("noUid", this.mRestoredWithoutUids.size());
        } catch (JSONException e) {
        }
        synchronized (this.mPackagePreferences) {
            int N = this.mPackagePreferences.size();
            for (int i = 0; i < N; i++) {
                PackagePreferences r = this.mPackagePreferences.valueAt(i);
                if (filter == null || filter.matches(r.pkg)) {
                    JSONObject PackagePreferences2 = new JSONObject();
                    try {
                        PackagePreferences2.put("userId", UserHandle.getUserId(r.uid));
                        PackagePreferences2.put("packageName", r.pkg);
                        if (r.importance != -1000) {
                            PackagePreferences2.put(ATT_IMPORTANCE, NotificationListenerService.Ranking.importanceToString(r.importance));
                        }
                        if (r.priority != 0) {
                            PackagePreferences2.put("priority", Notification.priorityToString(r.priority));
                        }
                        if (r.visibility != -1000) {
                            PackagePreferences2.put(ATT_VISIBILITY, Notification.visibilityToString(r.visibility));
                        }
                        if (!r.showBadge) {
                            PackagePreferences2.put("showBadge", Boolean.valueOf(r.showBadge));
                        }
                        JSONArray channels = new JSONArray();
                        for (NotificationChannel channel : r.channels.values()) {
                            channels.put(channel.toJson());
                        }
                        PackagePreferences2.put("channels", channels);
                        JSONArray groups = new JSONArray();
                        for (NotificationChannelGroup group : r.groups.values()) {
                            groups.put(group.toJson());
                        }
                        PackagePreferences2.put(xpInputManagerService.InputPolicyKey.KEY_GROUPS, groups);
                    } catch (JSONException e2) {
                    }
                    PackagePreferencess.put(PackagePreferences2);
                }
            }
        }
        try {
            ranking.put("PackagePreferencess", PackagePreferencess);
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
        synchronized (this.mPackagePreferences) {
            int N = this.mPackagePreferences.size();
            packageBans = new ArrayMap<>(N);
            for (int i = 0; i < N; i++) {
                PackagePreferences r = this.mPackagePreferences.valueAt(i);
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
        synchronized (this.mPackagePreferences) {
            for (int i = 0; i < this.mPackagePreferences.size(); i++) {
                PackagePreferences r = this.mPackagePreferences.valueAt(i);
                int channelCount = 0;
                for (int j = 0; j < r.channels.size(); j++) {
                    if (!r.channels.valueAt(j).isDeleted()) {
                        channelCount++;
                    }
                }
                packageChannels.put(r.pkg, Integer.valueOf(channelCount));
            }
        }
        return packageChannels;
    }

    public void onUserSwitched(int userId) {
        syncChannelsBypassingDnd(userId);
    }

    public void onUserUnlocked(int userId) {
        syncChannelsBypassingDnd(userId);
    }

    public void onUserRemoved(int userId) {
        synchronized (this.mPackagePreferences) {
            int N = this.mPackagePreferences.size();
            for (int i = N - 1; i >= 0; i--) {
                PackagePreferences PackagePreferences2 = this.mPackagePreferences.valueAt(i);
                if (UserHandle.getUserId(PackagePreferences2.uid) == userId) {
                    this.mPackagePreferences.removeAt(i);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onLocaleChanged(Context context, int userId) {
        synchronized (this.mPackagePreferences) {
            int N = this.mPackagePreferences.size();
            for (int i = 0; i < N; i++) {
                PackagePreferences PackagePreferences2 = this.mPackagePreferences.valueAt(i);
                if (UserHandle.getUserId(PackagePreferences2.uid) == userId && PackagePreferences2.channels.containsKey("miscellaneous")) {
                    PackagePreferences2.channels.get("miscellaneous").setName(context.getResources().getString(17039857));
                }
            }
        }
    }

    public boolean onPackagesChanged(boolean removingPackage, int changeUserId, String[] pkgList, int[] uidList) {
        if (pkgList == null || pkgList.length == 0) {
            return false;
        }
        boolean updated = false;
        if (removingPackage) {
            int size = Math.min(pkgList.length, uidList.length);
            for (int i = 0; i < size; i++) {
                String pkg = pkgList[i];
                int uid = uidList[i];
                synchronized (this.mPackagePreferences) {
                    this.mPackagePreferences.remove(packagePreferencesKey(pkg, uid));
                }
                this.mRestoredWithoutUids.remove(pkg);
                updated = true;
            }
        } else {
            for (String pkg2 : pkgList) {
                PackagePreferences r = this.mRestoredWithoutUids.get(pkg2);
                if (r != null) {
                    try {
                        r.uid = this.mPm.getPackageUidAsUser(r.pkg, changeUserId);
                        this.mRestoredWithoutUids.remove(pkg2);
                        synchronized (this.mPackagePreferences) {
                            this.mPackagePreferences.put(packagePreferencesKey(r.pkg, r.uid), r);
                        }
                        updated = true;
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                }
                try {
                    synchronized (this.mPackagePreferences) {
                        PackagePreferences fullPackagePreferences = getPackagePreferencesLocked(pkg2, this.mPm.getPackageUidAsUser(pkg2, changeUserId));
                        if (fullPackagePreferences != null) {
                            updated = updated | createDefaultChannelIfNeededLocked(fullPackagePreferences) | deleteDefaultChannelIfNeededLocked(fullPackagePreferences);
                        }
                    }
                } catch (PackageManager.NameNotFoundException e2) {
                }
            }
        }
        if (updated) {
            updateConfig();
        }
        return updated;
    }

    public void clearData(String pkg, int uid) {
        synchronized (this.mPackagePreferences) {
            PackagePreferences p = getPackagePreferencesLocked(pkg, uid);
            if (p != null) {
                p.channels = new ArrayMap<>();
                p.groups = new ArrayMap();
                p.delegate = null;
                p.lockedAppFields = 0;
                p.allowBubble = true;
                p.importance = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                p.priority = 0;
                p.visibility = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                p.showBadge = true;
            }
        }
    }

    private LogMaker getChannelLog(NotificationChannel channel, String pkg) {
        return new LogMaker(856).setType(6).setPackageName(pkg).addTaggedData(857, channel.getId()).addTaggedData(858, Integer.valueOf(channel.getImportance()));
    }

    private LogMaker getChannelGroupLog(String groupId, String pkg) {
        return new LogMaker(859).setType(6).addTaggedData(860, groupId).setPackageName(pkg);
    }

    public void updateBubblesEnabled() {
        boolean newValue = Settings.Global.getInt(this.mContext.getContentResolver(), "notification_bubbles", 1) == 1;
        if (newValue != this.mBubblesEnabled) {
            this.mBubblesEnabled = newValue;
            updateConfig();
        }
    }

    @Override // com.android.server.notification.RankingConfig
    public boolean bubblesEnabled() {
        return this.mBubblesEnabled;
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
        if (userId == -1) {
            return false;
        }
        if (this.mBadgingEnabled.indexOfKey(userId) < 0) {
            this.mBadgingEnabled.put(userId, Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "notification_badging", 1, userId) != 0);
        }
        return this.mBadgingEnabled.get(userId, true);
    }

    private void updateConfig() {
        this.mRankingHandler.requestSort();
    }

    private static String packagePreferencesKey(String pkg, int uid) {
        return pkg + "|" + uid;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class PackagePreferences {
        boolean allowBubble;
        ArrayMap<String, NotificationChannel> channels;
        boolean defaultAppLockedImportance;
        Delegate delegate;
        List<String> futureOemLockedChannels;
        Map<String, NotificationChannelGroup> groups;
        int importance;
        int lockedAppFields;
        boolean oemLockedImportance;
        String pkg;
        int priority;
        boolean showBadge;
        int uid;
        int visibility;

        private PackagePreferences() {
            this.uid = PreferencesHelper.UNKNOWN_UID;
            this.importance = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            this.priority = 0;
            this.visibility = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            this.showBadge = true;
            this.allowBubble = true;
            this.lockedAppFields = 0;
            this.oemLockedImportance = false;
            this.futureOemLockedChannels = new ArrayList();
            this.defaultAppLockedImportance = false;
            this.delegate = null;
            this.channels = new ArrayMap<>();
            this.groups = new ConcurrentHashMap();
        }

        public boolean isValidDelegate(String pkg, int uid) {
            Delegate delegate = this.delegate;
            return delegate != null && delegate.isAllowed(pkg, uid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Delegate {
        static final boolean DEFAULT_ENABLED = true;
        static final boolean DEFAULT_USER_ALLOWED = true;
        boolean mEnabled;
        String mPkg;
        int mUid;
        boolean mUserAllowed;

        Delegate(String pkg, int uid, boolean enabled, boolean userAllowed) {
            this.mUid = PreferencesHelper.UNKNOWN_UID;
            this.mEnabled = true;
            this.mUserAllowed = true;
            this.mPkg = pkg;
            this.mUid = uid;
            this.mEnabled = enabled;
            this.mUserAllowed = userAllowed;
        }

        public boolean isAllowed(String pkg, int uid) {
            return pkg != null && uid != PreferencesHelper.UNKNOWN_UID && pkg.equals(this.mPkg) && uid == this.mUid && this.mUserAllowed && this.mEnabled;
        }
    }
}
