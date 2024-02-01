package com.android.server;

import android.os.SystemClock;
import android.util.Log;
import android.util.StatsLog;
import com.android.server.job.controllers.JobStatus;
import java.time.Instant;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class LocationUsageLogger {
    private static final int API_USAGE_LOG_HOURLY_CAP = 60;
    private static final int ONE_HOUR_IN_MILLIS = 3600000;
    private static final int ONE_MINUTE_IN_MILLIS = 60000;
    private static final int ONE_SEC_IN_MILLIS = 1000;
    private static final String TAG = "LocationUsageLogger";
    private static final boolean D = Log.isLoggable(TAG, 3);
    private long mLastApiUsageLogHour = 0;
    private int mApiUsageLogHourlyCount = 0;

    private static int providerNameToStatsdEnum(String provider) {
        if ("network".equals(provider)) {
            return 1;
        }
        if ("gps".equals(provider)) {
            return 2;
        }
        if ("passive".equals(provider)) {
            return 3;
        }
        if ("fused".equals(provider)) {
            return 4;
        }
        return 0;
    }

    private static int bucketizeIntervalToStatsdEnum(long interval) {
        if (interval < 1000) {
            return 1;
        }
        if (interval < 5000) {
            return 2;
        }
        if (interval < 60000) {
            return 3;
        }
        if (interval < 600000) {
            return 4;
        }
        if (interval < 3600000) {
            return 5;
        }
        return 6;
    }

    private static int bucketizeSmallestDisplacementToStatsdEnum(float smallestDisplacement) {
        if (smallestDisplacement == 0.0f) {
            return 1;
        }
        if (smallestDisplacement > 0.0f && smallestDisplacement <= 100.0f) {
            return 2;
        }
        return 3;
    }

    private static int bucketizeRadiusToStatsdEnum(float radius) {
        if (radius < 0.0f) {
            return 7;
        }
        if (radius < 100.0f) {
            return 1;
        }
        if (radius < 200.0f) {
            return 2;
        }
        if (radius < 300.0f) {
            return 3;
        }
        if (radius < 1000.0f) {
            return 4;
        }
        if (radius < 10000.0f) {
            return 5;
        }
        return 6;
    }

    private static int getBucketizedExpireIn(long expireAt) {
        if (expireAt == JobStatus.NO_LATEST_RUNTIME) {
            return 6;
        }
        long elapsedRealtime = SystemClock.elapsedRealtime();
        long expireIn = Math.max(0L, expireAt - elapsedRealtime);
        if (expireIn < 20000) {
            return 1;
        }
        if (expireIn < 60000) {
            return 2;
        }
        if (expireIn < 600000) {
            return 3;
        }
        if (expireIn < 3600000) {
            return 4;
        }
        return 5;
    }

    private static int categorizeActivityImportance(int importance) {
        if (importance == 100) {
            return 1;
        }
        if (importance == 125) {
            return 2;
        }
        return 3;
    }

    private static int getCallbackType(int apiType, boolean hasListener, boolean hasIntent) {
        if (apiType == 5) {
            return 1;
        }
        if (hasIntent) {
            return 3;
        }
        if (hasListener) {
            return 2;
        }
        return 0;
    }

    private boolean checkApiUsageLogCap() {
        if (D) {
            Log.d(TAG, "checking APIUsage log cap.");
        }
        long currentHour = Instant.now().toEpochMilli() / 3600000;
        if (currentHour > this.mLastApiUsageLogHour) {
            this.mLastApiUsageLogHour = currentHour;
            this.mApiUsageLogHourlyCount = 0;
            return true;
        }
        this.mApiUsageLogHourlyCount = Math.min(this.mApiUsageLogHourlyCount + 1, 60);
        return this.mApiUsageLogHourlyCount < 60;
    }

    /* JADX WARN: Removed duplicated region for block: B:59:0x00e7  */
    /* JADX WARN: Removed duplicated region for block: B:60:0x00ea A[Catch: Exception -> 0x0111, TryCatch #2 {Exception -> 0x0111, blocks: (B:3:0x000a, B:14:0x001f, B:31:0x0080, B:55:0x00d7, B:57:0x00e1, B:61:0x00f4, B:60:0x00ea, B:51:0x00cc, B:47:0x00bb, B:44:0x00ae, B:41:0x00a5, B:38:0x0098), top: B:75:0x000a }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void logLocationApiUsage(int r25, int r26, java.lang.String r27, android.location.LocationRequest r28, boolean r29, boolean r30, android.location.Geofence r31, int r32) {
        /*
            Method dump skipped, instructions count: 284
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.LocationUsageLogger.logLocationApiUsage(int, int, java.lang.String, android.location.LocationRequest, boolean, boolean, android.location.Geofence, int):void");
    }

    public void logLocationApiUsage(int usageType, int apiInUse, String providerName) {
        String str;
        try {
            if (!checkApiUsageLogCap()) {
                return;
            }
            if (D) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("log API Usage to statsd. usageType: ");
                    try {
                        sb.append(usageType);
                        sb.append(", apiInUse: ");
                        sb.append(apiInUse);
                        sb.append(", providerName: ");
                        sb.append(providerName);
                        Log.d(TAG, sb.toString());
                    } catch (Exception e) {
                        e = e;
                        str = TAG;
                        Log.w(str, "Failed to log API usage to statsd.", e);
                    }
                } catch (Exception e2) {
                    e = e2;
                }
            }
            int providerNameToStatsdEnum = providerNameToStatsdEnum(providerName);
            int callbackType = getCallbackType(apiInUse, true, true);
            str = TAG;
            try {
                StatsLog.write(210, usageType, apiInUse, null, providerNameToStatsdEnum, 0, 0, 0, 0L, 0, callbackType, 0, 0);
            } catch (Exception e3) {
                e = e3;
                Log.w(str, "Failed to log API usage to statsd.", e);
            }
        } catch (Exception e4) {
            e = e4;
        }
    }
}
