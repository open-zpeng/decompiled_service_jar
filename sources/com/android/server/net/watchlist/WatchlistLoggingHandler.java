package com.android.server.net.watchlist;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StrictMode;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.HexDump;
import com.android.server.net.watchlist.WatchlistReportDbHelper;
import com.android.server.pm.DumpState;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WatchlistLoggingHandler extends Handler {
    private static final boolean DEBUG = false;
    private static final String DROPBOX_TAG = "network_watchlist_report";
    @VisibleForTesting
    static final int FORCE_REPORT_RECORDS_NOW_FOR_TEST_MSG = 3;
    @VisibleForTesting
    static final int LOG_WATCHLIST_EVENT_MSG = 1;
    @VisibleForTesting
    static final int REPORT_RECORDS_IF_NECESSARY_MSG = 2;
    private final ConcurrentHashMap<Integer, byte[]> mCachedUidDigestMap;
    private final WatchlistConfig mConfig;
    private final Context mContext;
    private final WatchlistReportDbHelper mDbHelper;
    private final DropBoxManager mDropBoxManager;
    private final PackageManager mPm;
    private int mPrimaryUserId;
    private final ContentResolver mResolver;
    private final WatchlistSettings mSettings;
    private static final String TAG = WatchlistLoggingHandler.class.getSimpleName();
    private static final long ONE_DAY_MS = TimeUnit.DAYS.toMillis(1);

    /* loaded from: classes.dex */
    private interface WatchlistEventKeys {
        public static final String HOST = "host";
        public static final String IP_ADDRESSES = "ipAddresses";
        public static final String TIMESTAMP = "timestamp";
        public static final String UID = "uid";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WatchlistLoggingHandler(Context context, Looper looper) {
        super(looper);
        this.mPrimaryUserId = -1;
        this.mCachedUidDigestMap = new ConcurrentHashMap<>();
        this.mContext = context;
        this.mPm = this.mContext.getPackageManager();
        this.mResolver = this.mContext.getContentResolver();
        this.mDbHelper = WatchlistReportDbHelper.getInstance(context);
        this.mConfig = WatchlistConfig.getInstance();
        this.mSettings = WatchlistSettings.getInstance();
        this.mDropBoxManager = (DropBoxManager) this.mContext.getSystemService(DropBoxManager.class);
        this.mPrimaryUserId = getPrimaryUserId();
    }

    @Override // android.os.Handler
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case 1:
                Bundle data = msg.getData();
                handleNetworkEvent(data.getString(WatchlistEventKeys.HOST), data.getStringArray(WatchlistEventKeys.IP_ADDRESSES), data.getInt(WatchlistEventKeys.UID), data.getLong(WatchlistEventKeys.TIMESTAMP));
                return;
            case 2:
                tryAggregateRecords(getLastMidnightTime());
                return;
            case 3:
                if (msg.obj instanceof Long) {
                    long lastRecordTime = ((Long) msg.obj).longValue();
                    tryAggregateRecords(lastRecordTime);
                    return;
                }
                Slog.e(TAG, "Msg.obj needs to be a Long object.");
                return;
            default:
                Slog.d(TAG, "WatchlistLoggingHandler received an unknown of message.");
                return;
        }
    }

    private int getPrimaryUserId() {
        UserInfo primaryUserInfo = ((UserManager) this.mContext.getSystemService("user")).getPrimaryUser();
        if (primaryUserInfo != null) {
            return primaryUserInfo.id;
        }
        return -1;
    }

    private boolean isPackageTestOnly(int uid) {
        try {
            String[] packageNames = this.mPm.getPackagesForUid(uid);
            if (packageNames != null && packageNames.length != 0) {
                ApplicationInfo ai = this.mPm.getApplicationInfo(packageNames[0], 0);
                return (ai.flags & 256) != 0;
            }
            String str = TAG;
            Slog.e(str, "Couldn't find package: " + packageNames);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public void reportWatchlistIfNecessary() {
        Message msg = obtainMessage(2);
        sendMessage(msg);
    }

    public void forceReportWatchlistForTest(long lastReportTime) {
        Message msg = obtainMessage(3);
        msg.obj = Long.valueOf(lastReportTime);
        sendMessage(msg);
    }

    public void asyncNetworkEvent(String host, String[] ipAddresses, int uid) {
        Message msg = obtainMessage(1);
        Bundle bundle = new Bundle();
        bundle.putString(WatchlistEventKeys.HOST, host);
        bundle.putStringArray(WatchlistEventKeys.IP_ADDRESSES, ipAddresses);
        bundle.putInt(WatchlistEventKeys.UID, uid);
        bundle.putLong(WatchlistEventKeys.TIMESTAMP, System.currentTimeMillis());
        msg.setData(bundle);
        sendMessage(msg);
    }

    private void handleNetworkEvent(String hostname, String[] ipAddresses, int uid, long timestamp) {
        if (this.mPrimaryUserId == -1) {
            this.mPrimaryUserId = getPrimaryUserId();
        }
        if (UserHandle.getUserId(uid) != this.mPrimaryUserId) {
            return;
        }
        String cncDomain = searchAllSubDomainsInWatchlist(hostname);
        if (cncDomain != null) {
            insertRecord(uid, cncDomain, timestamp);
            return;
        }
        String cncIp = searchIpInWatchlist(ipAddresses);
        if (cncIp != null) {
            insertRecord(uid, cncIp, timestamp);
        }
    }

    private boolean insertRecord(int uid, String cncHost, long timestamp) {
        if (!this.mConfig.isConfigSecure() && !isPackageTestOnly(uid)) {
            return true;
        }
        byte[] digest = getDigestFromUid(uid);
        if (digest == null) {
            String str = TAG;
            Slog.e(str, "Cannot get digest from uid: " + uid);
            return false;
        }
        boolean result = this.mDbHelper.insertNewRecord(digest, cncHost, timestamp);
        return result;
    }

    private boolean shouldReportNetworkWatchlist(long lastRecordTime) {
        long lastReportTime = Settings.Global.getLong(this.mResolver, "network_watchlist_last_report_time", 0L);
        if (lastRecordTime >= lastReportTime) {
            return lastRecordTime >= ONE_DAY_MS + lastReportTime;
        }
        Slog.i(TAG, "Last report time is larger than current time, reset report");
        this.mDbHelper.cleanup(lastReportTime);
        return false;
    }

    private void tryAggregateRecords(long lastRecordTime) {
        long startTime = System.currentTimeMillis();
        int oldMask = StrictMode.allowThreadDiskReadsMask();
        try {
            if (!shouldReportNetworkWatchlist(lastRecordTime)) {
                Slog.i(TAG, "No need to aggregate record yet.");
                return;
            }
            Slog.i(TAG, "Start aggregating watchlist records.");
            if (this.mDropBoxManager == null || !this.mDropBoxManager.isTagEnabled(DROPBOX_TAG)) {
                Slog.w(TAG, "Network Watchlist dropbox tag is not enabled");
            } else {
                Settings.Global.putLong(this.mResolver, "network_watchlist_last_report_time", lastRecordTime);
                WatchlistReportDbHelper.AggregatedResult aggregatedResult = this.mDbHelper.getAggregatedRecords(lastRecordTime);
                if (aggregatedResult == null) {
                    Slog.i(TAG, "Cannot get result from database");
                    return;
                }
                List<String> digestsForReport = getAllDigestsForReport(aggregatedResult);
                byte[] secretKey = this.mSettings.getPrivacySecretKey();
                byte[] encodedResult = ReportEncoder.encodeWatchlistReport(this.mConfig, secretKey, digestsForReport, aggregatedResult);
                if (encodedResult != null) {
                    addEncodedReportToDropBox(encodedResult);
                }
            }
            this.mDbHelper.cleanup(lastRecordTime);
        } finally {
            StrictMode.setThreadPolicyMask(oldMask);
            long endTime = System.currentTimeMillis();
            String str = TAG;
            Slog.i(str, "Milliseconds spent on tryAggregateRecords(): " + (endTime - startTime));
        }
    }

    @VisibleForTesting
    List<String> getAllDigestsForReport(WatchlistReportDbHelper.AggregatedResult record) {
        List<ApplicationInfo> apps = this.mContext.getPackageManager().getInstalledApplications(DumpState.DUMP_INTENT_FILTER_VERIFIERS);
        HashSet<String> result = new HashSet<>(apps.size() + record.appDigestCNCList.size());
        int size = apps.size();
        for (int i = 0; i < size; i++) {
            byte[] digest = getDigestFromUid(apps.get(i).uid);
            if (digest != null) {
                result.add(HexDump.toHexString(digest));
            } else {
                String str = TAG;
                Slog.e(str, "Cannot get digest from uid: " + apps.get(i).uid + ",pkg: " + apps.get(i).packageName);
            }
        }
        result.addAll(record.appDigestCNCList.keySet());
        return new ArrayList(result);
    }

    private void addEncodedReportToDropBox(byte[] encodedReport) {
        this.mDropBoxManager.addData(DROPBOX_TAG, encodedReport, 0);
    }

    private byte[] getDigestFromUid(final int uid) {
        return this.mCachedUidDigestMap.computeIfAbsent(Integer.valueOf(uid), new Function() { // from class: com.android.server.net.watchlist.-$$Lambda$WatchlistLoggingHandler$GBD0dX6RhipHIkM0Z_B5jLlwfHQ
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return WatchlistLoggingHandler.lambda$getDigestFromUid$0(WatchlistLoggingHandler.this, uid, (Integer) obj);
            }
        });
    }

    public static /* synthetic */ byte[] lambda$getDigestFromUid$0(WatchlistLoggingHandler watchlistLoggingHandler, int uid, Integer key) {
        String[] packageNames = watchlistLoggingHandler.mPm.getPackagesForUid(key.intValue());
        int userId = UserHandle.getUserId(uid);
        if (!ArrayUtils.isEmpty(packageNames)) {
            for (String packageName : packageNames) {
                try {
                    String apkPath = watchlistLoggingHandler.mPm.getPackageInfoAsUser(packageName, 786432, userId).applicationInfo.publicSourceDir;
                    if (TextUtils.isEmpty(apkPath)) {
                        Slog.w(TAG, "Cannot find apkPath for " + packageName);
                    } else {
                        return DigestUtils.getSha256Hash(new File(apkPath));
                    }
                } catch (PackageManager.NameNotFoundException | IOException | NoSuchAlgorithmException e) {
                    Slog.e(TAG, "Should not happen", e);
                    return null;
                }
            }
        }
        return null;
    }

    private String searchIpInWatchlist(String[] ipAddresses) {
        for (String ipAddress : ipAddresses) {
            if (isIpInWatchlist(ipAddress)) {
                return ipAddress;
            }
        }
        return null;
    }

    private boolean isIpInWatchlist(String ipAddr) {
        if (ipAddr == null) {
            return false;
        }
        return this.mConfig.containsIp(ipAddr);
    }

    private boolean isHostInWatchlist(String host) {
        if (host == null) {
            return false;
        }
        return this.mConfig.containsDomain(host);
    }

    private String searchAllSubDomainsInWatchlist(String host) {
        if (host == null) {
            return null;
        }
        String[] subDomains = getAllSubDomains(host);
        for (String subDomain : subDomains) {
            if (isHostInWatchlist(subDomain)) {
                return subDomain;
            }
        }
        return null;
    }

    @VisibleForTesting
    static String[] getAllSubDomains(String host) {
        if (host == null) {
            return null;
        }
        ArrayList<String> subDomainList = new ArrayList<>();
        subDomainList.add(host);
        int index = host.indexOf(".");
        while (index != -1) {
            host = host.substring(index + 1);
            if (!TextUtils.isEmpty(host)) {
                subDomainList.add(host);
            }
            index = host.indexOf(".");
        }
        return (String[]) subDomainList.toArray(new String[0]);
    }

    static long getLastMidnightTime() {
        return getMidnightTimestamp(0);
    }

    static long getMidnightTimestamp(int daysBefore) {
        Calendar date = new GregorianCalendar();
        date.set(11, 0);
        date.set(12, 0);
        date.set(13, 0);
        date.set(14, 0);
        date.add(5, -daysBefore);
        return date.getTimeInMillis();
    }
}
