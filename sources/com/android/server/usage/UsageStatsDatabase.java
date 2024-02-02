package com.android.server.usage;

import android.app.usage.ConfigurationStats;
import android.app.usage.TimeSparseArray;
import android.app.usage.UsageStats;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TimeUtils;
import com.android.server.job.controllers.JobStatus;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
class UsageStatsDatabase {
    static final int BACKUP_VERSION = 1;
    private static final String BAK_SUFFIX = ".bak";
    private static final String CHECKED_IN_SUFFIX = "-c";
    private static final int CURRENT_VERSION = 3;
    private static final boolean DEBUG = false;
    static final String KEY_USAGE_STATS = "usage_stats";
    private static final String RETENTION_LEN_KEY = "ro.usagestats.chooser.retention";
    private static final int SELECTION_LOG_RETENTION_LEN = SystemProperties.getInt(RETENTION_LEN_KEY, 14);
    private static final String TAG = "UsageStatsDatabase";
    private boolean mFirstUpdate;
    private final File[] mIntervalDirs;
    private boolean mNewUpdate;
    private final TimeSparseArray<AtomicFile>[] mSortedStatFiles;
    private final File mVersionFile;
    private final Object mLock = new Object();
    private final UnixCalendar mCal = new UnixCalendar(0);

    /* loaded from: classes.dex */
    public interface CheckinAction {
        boolean checkin(IntervalStats intervalStats);
    }

    /* loaded from: classes.dex */
    interface StatCombiner<T> {
        void combine(IntervalStats intervalStats, boolean z, List<T> list);
    }

    public UsageStatsDatabase(File dir) {
        this.mIntervalDirs = new File[]{new File(dir, "daily"), new File(dir, "weekly"), new File(dir, "monthly"), new File(dir, "yearly")};
        this.mVersionFile = new File(dir, "version");
        this.mSortedStatFiles = new TimeSparseArray[this.mIntervalDirs.length];
    }

    public void init(long currentTimeMillis) {
        File[] fileArr;
        TimeSparseArray<AtomicFile>[] timeSparseArrayArr;
        synchronized (this.mLock) {
            for (File f : this.mIntervalDirs) {
                f.mkdirs();
                if (!f.exists()) {
                    throw new IllegalStateException("Failed to create directory " + f.getAbsolutePath());
                }
            }
            checkVersionAndBuildLocked();
            indexFilesLocked();
            for (TimeSparseArray<AtomicFile> files : this.mSortedStatFiles) {
                int startIndex = files.closestIndexOnOrAfter(currentTimeMillis);
                if (startIndex >= 0) {
                    int fileCount = files.size();
                    for (int i = startIndex; i < fileCount; i++) {
                        ((AtomicFile) files.valueAt(i)).delete();
                    }
                    for (int i2 = startIndex; i2 < fileCount; i2++) {
                        files.removeAt(i2);
                    }
                }
            }
        }
    }

    public boolean checkinDailyFiles(CheckinAction checkinAction) {
        synchronized (this.mLock) {
            TimeSparseArray<AtomicFile> files = this.mSortedStatFiles[0];
            int fileCount = files.size();
            int lastCheckin = -1;
            for (int lastCheckin2 = 0; lastCheckin2 < fileCount - 1; lastCheckin2++) {
                if (((AtomicFile) files.valueAt(lastCheckin2)).getBaseFile().getPath().endsWith(CHECKED_IN_SUFFIX)) {
                    lastCheckin = lastCheckin2;
                }
            }
            int i = lastCheckin + 1;
            if (i == fileCount - 1) {
                return true;
            }
            try {
                IntervalStats stats = new IntervalStats();
                for (int i2 = i; i2 < fileCount - 1; i2++) {
                    UsageStatsXml.read((AtomicFile) files.valueAt(i2), stats);
                    if (!checkinAction.checkin(stats)) {
                        return false;
                    }
                }
                for (int i3 = i; i3 < fileCount - 1; i3++) {
                    AtomicFile file = (AtomicFile) files.valueAt(i3);
                    File checkedInFile = new File(file.getBaseFile().getPath() + CHECKED_IN_SUFFIX);
                    if (!file.getBaseFile().renameTo(checkedInFile)) {
                        Slog.e(TAG, "Failed to mark file " + file.getBaseFile().getPath() + " as checked-in");
                        return true;
                    }
                    files.setValueAt(i3, new AtomicFile(checkedInFile));
                }
                return true;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to check-in", e);
                return false;
            }
        }
    }

    private void indexFilesLocked() {
        FilenameFilter backupFileFilter = new FilenameFilter() { // from class: com.android.server.usage.UsageStatsDatabase.1
            @Override // java.io.FilenameFilter
            public boolean accept(File dir, String name) {
                return !name.endsWith(UsageStatsDatabase.BAK_SUFFIX);
            }
        };
        for (int i = 0; i < this.mSortedStatFiles.length; i++) {
            if (this.mSortedStatFiles[i] == null) {
                this.mSortedStatFiles[i] = new TimeSparseArray<>();
            } else {
                this.mSortedStatFiles[i].clear();
            }
            File[] files = this.mIntervalDirs[i].listFiles(backupFileFilter);
            if (files != null) {
                for (File f : files) {
                    AtomicFile af = new AtomicFile(f);
                    try {
                        this.mSortedStatFiles[i].put(UsageStatsXml.parseBeginTime(af), af);
                    } catch (IOException e) {
                        Slog.e(TAG, "failed to index file: " + f, e);
                    }
                }
            }
        }
    }

    boolean isFirstUpdate() {
        return this.mFirstUpdate;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isNewUpdate() {
        return this.mNewUpdate;
    }

    private void checkVersionAndBuildLocked() {
        String currentFingerprint = getBuildFingerprint();
        this.mFirstUpdate = true;
        this.mNewUpdate = true;
        int version = 0;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(this.mVersionFile));
            int version2 = Integer.parseInt(reader.readLine());
            String buildFingerprint = reader.readLine();
            if (buildFingerprint != null) {
                this.mFirstUpdate = false;
            }
            if (currentFingerprint.equals(buildFingerprint)) {
                this.mNewUpdate = false;
            }
            $closeResource(null, reader);
            version = version2;
        } catch (IOException | NumberFormatException e) {
        }
        if (version != 3) {
            Slog.i(TAG, "Upgrading from version " + version + " to 3");
            doUpgradeLocked(version);
        }
        if (version != 3 || this.mNewUpdate) {
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(this.mVersionFile));
                writer.write(Integer.toString(3));
                writer.write("\n");
                writer.write(currentFingerprint);
                writer.write("\n");
                writer.flush();
                $closeResource(null, writer);
            } catch (IOException e2) {
                Slog.e(TAG, "Failed to write new version");
                throw new RuntimeException(e2);
            }
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    private String getBuildFingerprint() {
        return Build.VERSION.RELEASE + ";" + Build.VERSION.CODENAME + ";" + Build.VERSION.INCREMENTAL;
    }

    private void doUpgradeLocked(int thisVersion) {
        if (thisVersion < 2) {
            Slog.i(TAG, "Deleting all usage stats files");
            for (int i = 0; i < this.mIntervalDirs.length; i++) {
                File[] files = this.mIntervalDirs[i].listFiles();
                if (files != null) {
                    for (File f : files) {
                        f.delete();
                    }
                }
            }
        }
    }

    public void onTimeChanged(long timeDiffMillis) {
        long j = timeDiffMillis;
        synchronized (this.mLock) {
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("Time changed by ");
            TimeUtils.formatDuration(j, logBuilder);
            logBuilder.append(".");
            int filesDeleted = 0;
            TimeSparseArray<AtomicFile>[] timeSparseArrayArr = this.mSortedStatFiles;
            int length = timeSparseArrayArr.length;
            int filesMoved = 0;
            int filesMoved2 = 0;
            while (filesMoved2 < length) {
                TimeSparseArray<AtomicFile> files = timeSparseArrayArr[filesMoved2];
                int fileCount = files.size();
                int filesMoved3 = filesMoved;
                int filesDeleted2 = filesDeleted;
                int filesDeleted3 = 0;
                while (true) {
                    int i = filesDeleted3;
                    if (i < fileCount) {
                        AtomicFile file = (AtomicFile) files.valueAt(i);
                        int filesDeleted4 = filesDeleted2;
                        long newTime = files.keyAt(i) + j;
                        if (newTime < 0) {
                            int filesDeleted5 = filesDeleted4 + 1;
                            file.delete();
                            filesDeleted2 = filesDeleted5;
                        } else {
                            try {
                                file.openRead().close();
                            } catch (IOException e) {
                            }
                            String newName = Long.toString(newTime);
                            if (file.getBaseFile().getName().endsWith(CHECKED_IN_SUFFIX)) {
                                newName = newName + CHECKED_IN_SUFFIX;
                            }
                            File newFile = new File(file.getBaseFile().getParentFile(), newName);
                            filesMoved3++;
                            file.getBaseFile().renameTo(newFile);
                            filesDeleted2 = filesDeleted4;
                        }
                        filesDeleted3 = i + 1;
                        j = timeDiffMillis;
                    }
                }
                int filesDeleted6 = filesDeleted2;
                files.clear();
                filesMoved2++;
                filesMoved = filesMoved3;
                filesDeleted = filesDeleted6;
                j = timeDiffMillis;
            }
            logBuilder.append(" files deleted: ");
            logBuilder.append(filesDeleted);
            logBuilder.append(" files moved: ");
            logBuilder.append(filesMoved);
            Slog.i(TAG, logBuilder.toString());
            indexFilesLocked();
        }
    }

    public IntervalStats getLatestUsageStats(int intervalType) {
        synchronized (this.mLock) {
            if (intervalType >= 0) {
                try {
                    if (intervalType < this.mIntervalDirs.length) {
                        int fileCount = this.mSortedStatFiles[intervalType].size();
                        if (fileCount == 0) {
                            return null;
                        }
                        try {
                            AtomicFile f = (AtomicFile) this.mSortedStatFiles[intervalType].valueAt(fileCount - 1);
                            IntervalStats stats = new IntervalStats();
                            UsageStatsXml.read(f, stats);
                            return stats;
                        } catch (IOException e) {
                            Slog.e(TAG, "Failed to read usage stats file", e);
                            return null;
                        }
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            throw new IllegalArgumentException("Bad interval type " + intervalType);
        }
    }

    public <T> List<T> queryUsageStats(int intervalType, long beginTime, long endTime, StatCombiner<T> combiner) {
        long j = beginTime;
        synchronized (this.mLock) {
            if (intervalType >= 0) {
                try {
                    if (intervalType < this.mIntervalDirs.length) {
                        TimeSparseArray<AtomicFile> intervalStats = this.mSortedStatFiles[intervalType];
                        if (endTime <= j) {
                            return null;
                        }
                        int startIndex = intervalStats.closestIndexOnOrBefore(j);
                        if (startIndex < 0) {
                            startIndex = 0;
                        }
                        int startIndex2 = startIndex;
                        int endIndex = intervalStats.closestIndexOnOrBefore(endTime);
                        if (endIndex < 0) {
                            return null;
                        }
                        if (intervalStats.keyAt(endIndex) == endTime && endIndex - 1 < 0) {
                            return null;
                        }
                        int endIndex2 = endIndex;
                        IntervalStats stats = new IntervalStats();
                        ArrayList<T> results = new ArrayList<>();
                        int i = startIndex2;
                        while (true) {
                            int i2 = i;
                            if (i2 > endIndex2) {
                                return results;
                            }
                            AtomicFile f = (AtomicFile) intervalStats.valueAt(i2);
                            try {
                                UsageStatsXml.read(f, stats);
                                if (j < stats.endTime) {
                                    try {
                                        combiner.combine(stats, false, results);
                                    } catch (IOException e) {
                                        e = e;
                                        Slog.e(TAG, "Failed to read usage stats file", e);
                                        i = i2 + 1;
                                        j = beginTime;
                                    }
                                }
                            } catch (IOException e2) {
                                e = e2;
                            }
                            i = i2 + 1;
                            j = beginTime;
                        }
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            throw new IllegalArgumentException("Bad interval type " + intervalType);
        }
    }

    public int findBestFitBucket(long beginTimeStamp, long endTimeStamp) {
        int bestBucket;
        synchronized (this.mLock) {
            bestBucket = -1;
            long smallestDiff = JobStatus.NO_LATEST_RUNTIME;
            for (int i = this.mSortedStatFiles.length - 1; i >= 0; i--) {
                int index = this.mSortedStatFiles[i].closestIndexOnOrBefore(beginTimeStamp);
                int size = this.mSortedStatFiles[i].size();
                if (index >= 0 && index < size) {
                    long diff = Math.abs(this.mSortedStatFiles[i].keyAt(index) - beginTimeStamp);
                    if (diff < smallestDiff) {
                        smallestDiff = diff;
                        bestBucket = i;
                    }
                }
            }
        }
        return bestBucket;
    }

    public void prune(long currentTimeMillis) {
        synchronized (this.mLock) {
            this.mCal.setTimeInMillis(currentTimeMillis);
            this.mCal.addYears(-3);
            pruneFilesOlderThan(this.mIntervalDirs[3], this.mCal.getTimeInMillis());
            this.mCal.setTimeInMillis(currentTimeMillis);
            this.mCal.addMonths(-6);
            pruneFilesOlderThan(this.mIntervalDirs[2], this.mCal.getTimeInMillis());
            this.mCal.setTimeInMillis(currentTimeMillis);
            this.mCal.addWeeks(-4);
            pruneFilesOlderThan(this.mIntervalDirs[1], this.mCal.getTimeInMillis());
            this.mCal.setTimeInMillis(currentTimeMillis);
            this.mCal.addDays(-10);
            int i = 0;
            pruneFilesOlderThan(this.mIntervalDirs[0], this.mCal.getTimeInMillis());
            this.mCal.setTimeInMillis(currentTimeMillis);
            this.mCal.addDays(-SELECTION_LOG_RETENTION_LEN);
            while (true) {
                int i2 = i;
                if (i2 < this.mIntervalDirs.length) {
                    pruneChooserCountsOlderThan(this.mIntervalDirs[i2], this.mCal.getTimeInMillis());
                    i = i2 + 1;
                } else {
                    indexFilesLocked();
                }
            }
        }
    }

    private static void pruneFilesOlderThan(File dir, long expiryTime) {
        long beginTime;
        File[] files = dir.listFiles();
        if (files != null) {
            int length = files.length;
            for (int i = 0; i < length; i++) {
                File f = files[i];
                String path = f.getPath();
                if (path.endsWith(BAK_SUFFIX)) {
                    f = new File(path.substring(0, path.length() - BAK_SUFFIX.length()));
                }
                try {
                    beginTime = UsageStatsXml.parseBeginTime(f);
                } catch (IOException e) {
                    beginTime = 0;
                }
                if (beginTime < expiryTime) {
                    new AtomicFile(f).delete();
                }
            }
        }
    }

    private static void pruneChooserCountsOlderThan(File dir, long expiryTime) {
        File f;
        long beginTime;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f2 : files) {
                String path = f2.getPath();
                if (path.endsWith(BAK_SUFFIX)) {
                    f = new File(path.substring(0, path.length() - BAK_SUFFIX.length()));
                } else {
                    f = f2;
                }
                try {
                    beginTime = UsageStatsXml.parseBeginTime(f);
                } catch (IOException e) {
                    beginTime = 0;
                }
                if (beginTime < expiryTime) {
                    try {
                        AtomicFile af = new AtomicFile(f);
                        IntervalStats stats = new IntervalStats();
                        UsageStatsXml.read(af, stats);
                        int pkgCount = stats.packageStats.size();
                        for (int i = 0; i < pkgCount; i++) {
                            UsageStats pkgStats = stats.packageStats.valueAt(i);
                            if (pkgStats.mChooserCounts != null) {
                                pkgStats.mChooserCounts.clear();
                            }
                        }
                        UsageStatsXml.write(af, stats);
                    } catch (IOException e2) {
                        Slog.e(TAG, "Failed to delete chooser counts from usage stats file", e2);
                    }
                }
            }
        }
    }

    public void putUsageStats(int intervalType, IntervalStats stats) throws IOException {
        if (stats == null) {
            return;
        }
        synchronized (this.mLock) {
            if (intervalType >= 0) {
                try {
                    if (intervalType < this.mIntervalDirs.length) {
                        AtomicFile f = (AtomicFile) this.mSortedStatFiles[intervalType].get(stats.beginTime);
                        if (f == null) {
                            f = new AtomicFile(new File(this.mIntervalDirs[intervalType], Long.toString(stats.beginTime)));
                            this.mSortedStatFiles[intervalType].put(stats.beginTime, f);
                        }
                        UsageStatsXml.write(f, stats);
                        stats.lastTimeSaved = f.getLastModifiedTime();
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
            throw new IllegalArgumentException("Bad interval type " + intervalType);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public byte[] getBackupPayload(String key) {
        byte[] byteArray;
        synchronized (this.mLock) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (KEY_USAGE_STATS.equals(key)) {
                prune(System.currentTimeMillis());
                DataOutputStream out = new DataOutputStream(baos);
                try {
                    out.writeInt(1);
                    int i = 0;
                    out.writeInt(this.mSortedStatFiles[0].size());
                    for (int i2 = 0; i2 < this.mSortedStatFiles[0].size(); i2++) {
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[0].valueAt(i2));
                    }
                    out.writeInt(this.mSortedStatFiles[1].size());
                    for (int i3 = 0; i3 < this.mSortedStatFiles[1].size(); i3++) {
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[1].valueAt(i3));
                    }
                    out.writeInt(this.mSortedStatFiles[2].size());
                    for (int i4 = 0; i4 < this.mSortedStatFiles[2].size(); i4++) {
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[2].valueAt(i4));
                    }
                    out.writeInt(this.mSortedStatFiles[3].size());
                    while (true) {
                        int i5 = i;
                        if (i5 >= this.mSortedStatFiles[3].size()) {
                            break;
                        }
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[3].valueAt(i5));
                        i = i5 + 1;
                    }
                } catch (IOException ioe) {
                    Slog.d(TAG, "Failed to write data to output stream", ioe);
                    baos.reset();
                }
            }
            byteArray = baos.toByteArray();
        }
        return byteArray;
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:53:0x00e3
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    void applyRestoredPayload(java.lang.String r18, byte[] r19) {
        /*
            Method dump skipped, instructions count: 236
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usage.UsageStatsDatabase.applyRestoredPayload(java.lang.String, byte[]):void");
    }

    private IntervalStats mergeStats(IntervalStats beingRestored, IntervalStats onDevice) {
        if (onDevice == null) {
            return beingRestored;
        }
        if (beingRestored == null) {
            return null;
        }
        beingRestored.activeConfiguration = onDevice.activeConfiguration;
        beingRestored.configurations.putAll((ArrayMap<? extends Configuration, ? extends ConfigurationStats>) onDevice.configurations);
        beingRestored.events = onDevice.events;
        return beingRestored;
    }

    private void writeIntervalStatsToStream(DataOutputStream out, AtomicFile statsFile) throws IOException {
        IntervalStats stats = new IntervalStats();
        try {
            UsageStatsXml.read(statsFile, stats);
            sanitizeIntervalStatsForBackup(stats);
            byte[] data = serializeIntervalStats(stats);
            out.writeInt(data.length);
            out.write(data);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read usage stats file", e);
            out.writeInt(0);
        }
    }

    private static byte[] getIntervalStatsBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] buffer = new byte[length];
        in.read(buffer, 0, length);
        return buffer;
    }

    private static void sanitizeIntervalStatsForBackup(IntervalStats stats) {
        if (stats == null) {
            return;
        }
        stats.activeConfiguration = null;
        stats.configurations.clear();
        if (stats.events != null) {
            stats.events.clear();
        }
    }

    private static byte[] serializeIntervalStats(IntervalStats stats) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        try {
            out.writeLong(stats.beginTime);
            UsageStatsXml.write(out, stats);
        } catch (IOException ioe) {
            Slog.d(TAG, "Serializing IntervalStats Failed", ioe);
            baos.reset();
        }
        return baos.toByteArray();
    }

    private static IntervalStats deserializeIntervalStats(byte[] data) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream in = new DataInputStream(bais);
        IntervalStats stats = new IntervalStats();
        try {
            stats.beginTime = in.readLong();
            UsageStatsXml.read(in, stats);
            return stats;
        } catch (IOException ioe) {
            Slog.d(TAG, "DeSerializing IntervalStats Failed", ioe);
            return null;
        }
    }

    private static void deleteDirectoryContents(File directory) {
        File[] files = directory.listFiles();
        for (File file : files) {
            deleteDirectory(file);
        }
    }

    private static void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isDirectory()) {
                    file.delete();
                } else {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }
}
