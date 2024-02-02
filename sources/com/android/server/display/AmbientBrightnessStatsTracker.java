package com.android.server.display;

import android.hardware.display.AmbientBrightnessDayStats;
import android.os.SystemClock;
import android.os.UserManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
/* loaded from: classes.dex */
public class AmbientBrightnessStatsTracker {
    @VisibleForTesting
    static final float[] BUCKET_BOUNDARIES_FOR_NEW_STATS = {0.0f, 0.1f, 0.3f, 1.0f, 3.0f, 10.0f, 30.0f, 100.0f, 300.0f, 1000.0f, 3000.0f, 10000.0f};
    private static final boolean DEBUG = false;
    @VisibleForTesting
    static final int MAX_DAYS_TO_TRACK = 7;
    private static final String TAG = "AmbientBrightnessStatsTracker";
    private final AmbientBrightnessStats mAmbientBrightnessStats;
    private float mCurrentAmbientBrightness;
    private int mCurrentUserId;
    private final Injector mInjector;
    private final Timer mTimer;
    private final UserManager mUserManager;

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface Clock {
        long elapsedTimeMillis();
    }

    public AmbientBrightnessStatsTracker(UserManager userManager, Injector injector) {
        this.mUserManager = userManager;
        if (injector != null) {
            this.mInjector = injector;
        } else {
            this.mInjector = new Injector();
        }
        this.mAmbientBrightnessStats = new AmbientBrightnessStats();
        this.mTimer = new Timer(new Clock() { // from class: com.android.server.display.-$$Lambda$AmbientBrightnessStatsTracker$vQZYn_dAhbvzT-Un4vvpuyIATII
            @Override // com.android.server.display.AmbientBrightnessStatsTracker.Clock
            public final long elapsedTimeMillis() {
                long elapsedRealtimeMillis;
                elapsedRealtimeMillis = AmbientBrightnessStatsTracker.this.mInjector.elapsedRealtimeMillis();
                return elapsedRealtimeMillis;
            }
        });
        this.mCurrentAmbientBrightness = -1.0f;
    }

    public synchronized void start() {
        this.mTimer.reset();
        this.mTimer.start();
    }

    public synchronized void stop() {
        if (this.mTimer.isRunning()) {
            this.mAmbientBrightnessStats.log(this.mCurrentUserId, this.mInjector.getLocalDate(), this.mCurrentAmbientBrightness, this.mTimer.totalDurationSec());
        }
        this.mTimer.reset();
        this.mCurrentAmbientBrightness = -1.0f;
    }

    public synchronized void add(int userId, float newAmbientBrightness) {
        if (this.mTimer.isRunning()) {
            if (userId == this.mCurrentUserId) {
                this.mAmbientBrightnessStats.log(this.mCurrentUserId, this.mInjector.getLocalDate(), this.mCurrentAmbientBrightness, this.mTimer.totalDurationSec());
            } else {
                this.mCurrentUserId = userId;
            }
            this.mTimer.reset();
            this.mTimer.start();
            this.mCurrentAmbientBrightness = newAmbientBrightness;
        }
    }

    public synchronized void writeStats(OutputStream stream) throws IOException {
        this.mAmbientBrightnessStats.writeToXML(stream);
    }

    public synchronized void readStats(InputStream stream) throws IOException {
        this.mAmbientBrightnessStats.readFromXML(stream);
    }

    public synchronized ArrayList<AmbientBrightnessDayStats> getUserStats(int userId) {
        return this.mAmbientBrightnessStats.getUserStats(userId);
    }

    public synchronized void dump(PrintWriter pw) {
        pw.println("AmbientBrightnessStats:");
        pw.print(this.mAmbientBrightnessStats);
    }

    /* loaded from: classes.dex */
    class AmbientBrightnessStats {
        private static final String ATTR_BUCKET_BOUNDARIES = "bucket-boundaries";
        private static final String ATTR_BUCKET_STATS = "bucket-stats";
        private static final String ATTR_LOCAL_DATE = "local-date";
        private static final String ATTR_USER = "user";
        private static final String TAG_AMBIENT_BRIGHTNESS_DAY_STATS = "ambient-brightness-day-stats";
        private static final String TAG_AMBIENT_BRIGHTNESS_STATS = "ambient-brightness-stats";
        private Map<Integer, Deque<AmbientBrightnessDayStats>> mStats = new HashMap();

        public AmbientBrightnessStats() {
        }

        public void log(int userId, LocalDate localDate, float ambientBrightness, float durationSec) {
            Deque<AmbientBrightnessDayStats> userStats = getOrCreateUserStats(this.mStats, userId);
            AmbientBrightnessDayStats dayStats = getOrCreateDayStats(userStats, localDate);
            dayStats.log(ambientBrightness, durationSec);
        }

        public ArrayList<AmbientBrightnessDayStats> getUserStats(int userId) {
            if (this.mStats.containsKey(Integer.valueOf(userId))) {
                return new ArrayList<>(this.mStats.get(Integer.valueOf(userId)));
            }
            return null;
        }

        public void writeToXML(OutputStream stream) throws IOException {
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            LocalDate cutOffDate = AmbientBrightnessStatsTracker.this.mInjector.getLocalDate().minusDays(7L);
            fastXmlSerializer.startTag(null, TAG_AMBIENT_BRIGHTNESS_STATS);
            for (Map.Entry<Integer, Deque<AmbientBrightnessDayStats>> entry : this.mStats.entrySet()) {
                for (AmbientBrightnessDayStats userDayStats : entry.getValue()) {
                    int userSerialNumber = AmbientBrightnessStatsTracker.this.mInjector.getUserSerialNumber(AmbientBrightnessStatsTracker.this.mUserManager, entry.getKey().intValue());
                    if (userSerialNumber != -1 && userDayStats.getLocalDate().isAfter(cutOffDate)) {
                        fastXmlSerializer.startTag(null, TAG_AMBIENT_BRIGHTNESS_DAY_STATS);
                        fastXmlSerializer.attribute(null, ATTR_USER, Integer.toString(userSerialNumber));
                        fastXmlSerializer.attribute(null, ATTR_LOCAL_DATE, userDayStats.getLocalDate().toString());
                        StringBuilder bucketBoundariesValues = new StringBuilder();
                        StringBuilder timeSpentValues = new StringBuilder();
                        for (int i = 0; i < userDayStats.getBucketBoundaries().length; i++) {
                            if (i > 0) {
                                bucketBoundariesValues.append(",");
                                timeSpentValues.append(",");
                            }
                            bucketBoundariesValues.append(userDayStats.getBucketBoundaries()[i]);
                            timeSpentValues.append(userDayStats.getStats()[i]);
                        }
                        fastXmlSerializer.attribute(null, ATTR_BUCKET_BOUNDARIES, bucketBoundariesValues.toString());
                        fastXmlSerializer.attribute(null, ATTR_BUCKET_STATS, timeSpentValues.toString());
                        fastXmlSerializer.endTag(null, TAG_AMBIENT_BRIGHTNESS_DAY_STATS);
                    }
                }
            }
            fastXmlSerializer.endTag(null, TAG_AMBIENT_BRIGHTNESS_STATS);
            fastXmlSerializer.endDocument();
            stream.flush();
        }

        /* JADX WARN: Code restructure failed: missing block: B:24:0x005d, code lost:
            if (r9 != 4) goto L34;
         */
        /* JADX WARN: Code restructure failed: missing block: B:25:0x0060, code lost:
            r16 = r2;
         */
        /* JADX WARN: Code restructure failed: missing block: B:27:0x006f, code lost:
            if (com.android.server.display.AmbientBrightnessStatsTracker.AmbientBrightnessStats.TAG_AMBIENT_BRIGHTNESS_DAY_STATS.equals(r2.getName()) == false) goto L56;
         */
        /* JADX WARN: Code restructure failed: missing block: B:28:0x0071, code lost:
            r9 = r2.getAttributeValue(null, com.android.server.display.AmbientBrightnessStatsTracker.AmbientBrightnessStats.ATTR_USER);
            r11 = java.time.LocalDate.parse(r2.getAttributeValue(null, com.android.server.display.AmbientBrightnessStatsTracker.AmbientBrightnessStats.ATTR_LOCAL_DATE));
            r12 = r2.getAttributeValue(null, com.android.server.display.AmbientBrightnessStatsTracker.AmbientBrightnessStats.ATTR_BUCKET_BOUNDARIES).split(",");
            r10 = r2.getAttributeValue(null, com.android.server.display.AmbientBrightnessStatsTracker.AmbientBrightnessStats.ATTR_BUCKET_STATS).split(",");
         */
        /* JADX WARN: Code restructure failed: missing block: B:29:0x009e, code lost:
            if (r12.length != r10.length) goto L55;
         */
        /* JADX WARN: Code restructure failed: missing block: B:31:0x00a1, code lost:
            if (r12.length < r6) goto L52;
         */
        /* JADX WARN: Code restructure failed: missing block: B:32:0x00a3, code lost:
            r13 = new float[r12.length];
            r14 = new float[r10.length];
            r15 = 0;
         */
        /* JADX WARN: Code restructure failed: missing block: B:34:0x00ab, code lost:
            if (r15 >= r12.length) goto L44;
         */
        /* JADX WARN: Code restructure failed: missing block: B:35:0x00ad, code lost:
            r13[r15] = java.lang.Float.parseFloat(r12[r15]);
            r14[r15] = java.lang.Float.parseFloat(r10[r15]);
            r15 = r15 + 1;
         */
        /* JADX WARN: Code restructure failed: missing block: B:36:0x00c1, code lost:
            r16 = r2;
            r2 = r17.this$0.mInjector.getUserId(r17.this$0.mUserManager, java.lang.Integer.parseInt(r9));
         */
        /* JADX WARN: Code restructure failed: missing block: B:37:0x00d8, code lost:
            if (r2 == (-1)) goto L50;
         */
        /* JADX WARN: Code restructure failed: missing block: B:39:0x00de, code lost:
            if (r11.isAfter(r7) == false) goto L50;
         */
        /* JADX WARN: Code restructure failed: missing block: B:40:0x00e0, code lost:
            r6 = getOrCreateUserStats(r0, r2);
            r6.offer(new android.hardware.display.AmbientBrightnessDayStats(r11, r13, r14));
         */
        /* JADX WARN: Code restructure failed: missing block: B:47:0x0100, code lost:
            r17.mStats = r0;
         */
        /* JADX WARN: Code restructure failed: missing block: B:48:0x0103, code lost:
            return;
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void readFromXML(java.io.InputStream r18) throws java.io.IOException {
            /*
                Method dump skipped, instructions count: 298
                To view this dump add '--comments-level debug' option
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.display.AmbientBrightnessStatsTracker.AmbientBrightnessStats.readFromXML(java.io.InputStream):void");
        }

        public String toString() {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<Integer, Deque<AmbientBrightnessDayStats>> entry : this.mStats.entrySet()) {
                for (AmbientBrightnessDayStats dayStats : entry.getValue()) {
                    builder.append("  ");
                    builder.append(entry.getKey());
                    builder.append(" ");
                    builder.append(dayStats);
                    builder.append("\n");
                }
            }
            return builder.toString();
        }

        private Deque<AmbientBrightnessDayStats> getOrCreateUserStats(Map<Integer, Deque<AmbientBrightnessDayStats>> stats, int userId) {
            if (!stats.containsKey(Integer.valueOf(userId))) {
                stats.put(Integer.valueOf(userId), new ArrayDeque());
            }
            return stats.get(Integer.valueOf(userId));
        }

        private AmbientBrightnessDayStats getOrCreateDayStats(Deque<AmbientBrightnessDayStats> userStats, LocalDate localDate) {
            AmbientBrightnessDayStats lastBrightnessStats = userStats.peekLast();
            if (lastBrightnessStats != null && lastBrightnessStats.getLocalDate().equals(localDate)) {
                return lastBrightnessStats;
            }
            AmbientBrightnessDayStats dayStats = new AmbientBrightnessDayStats(localDate, AmbientBrightnessStatsTracker.BUCKET_BOUNDARIES_FOR_NEW_STATS);
            if (userStats.size() == 7) {
                userStats.poll();
            }
            userStats.offer(dayStats);
            return dayStats;
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    static class Timer {
        private final Clock clock;
        private long startTimeMillis;
        private boolean started;

        public Timer(Clock clock) {
            this.clock = clock;
        }

        public void reset() {
            this.started = false;
        }

        public void start() {
            if (!this.started) {
                this.startTimeMillis = this.clock.elapsedTimeMillis();
                this.started = true;
            }
        }

        public boolean isRunning() {
            return this.started;
        }

        public float totalDurationSec() {
            if (this.started) {
                return (float) ((this.clock.elapsedTimeMillis() - this.startTimeMillis) / 1000.0d);
            }
            return 0.0f;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class Injector {
        Injector() {
        }

        public long elapsedRealtimeMillis() {
            return SystemClock.elapsedRealtime();
        }

        public int getUserSerialNumber(UserManager userManager, int userId) {
            return userManager.getUserSerialNumber(userId);
        }

        public int getUserId(UserManager userManager, int userSerialNumber) {
            return userManager.getUserHandle(userSerialNumber);
        }

        public LocalDate getLocalDate() {
            return LocalDate.now();
        }
    }
}
