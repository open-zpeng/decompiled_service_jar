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
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.job.controllers.JobStatus;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import libcore.io.IoUtils;

/* loaded from: classes2.dex */
public class UsageStatsDatabase {
    @VisibleForTesting
    public static final int BACKUP_VERSION = 4;
    private static final String BAK_SUFFIX = ".bak";
    private static final String CHECKED_IN_SUFFIX = "-c";
    private static final boolean DEBUG = false;
    private static final int DEFAULT_CURRENT_VERSION = 4;
    static final boolean KEEP_BACKUP_DIR = false;
    static final String KEY_USAGE_STATS = "usage_stats";
    @VisibleForTesting
    static final int[] MAX_FILES_PER_INTERVAL_TYPE = {100, 50, 12, 10};
    private static final String RETENTION_LEN_KEY = "ro.usagestats.chooser.retention";
    private static final int SELECTION_LOG_RETENTION_LEN = SystemProperties.getInt(RETENTION_LEN_KEY, 14);
    private static final String TAG = "UsageStatsDatabase";
    private final File mBackupsDir;
    private final UnixCalendar mCal;
    private int mCurrentVersion;
    private boolean mFirstUpdate;
    private final File[] mIntervalDirs;
    private final Object mLock;
    private boolean mNewUpdate;
    @VisibleForTesting
    final TimeSparseArray<AtomicFile>[] mSortedStatFiles;
    private final File mUpdateBreadcrumb;
    private final File mVersionFile;

    /* loaded from: classes2.dex */
    public interface CheckinAction {
        boolean checkin(IntervalStats intervalStats);
    }

    /* loaded from: classes2.dex */
    public interface StatCombiner<T> {
        void combine(IntervalStats intervalStats, boolean z, List<T> list);
    }

    @VisibleForTesting
    public UsageStatsDatabase(File dir, int version) {
        this.mLock = new Object();
        this.mIntervalDirs = new File[]{new File(dir, "daily"), new File(dir, "weekly"), new File(dir, "monthly"), new File(dir, "yearly")};
        this.mCurrentVersion = version;
        this.mVersionFile = new File(dir, "version");
        this.mBackupsDir = new File(dir, "backups");
        this.mUpdateBreadcrumb = new File(dir, "breadcrumb");
        this.mSortedStatFiles = new TimeSparseArray[this.mIntervalDirs.length];
        this.mCal = new UnixCalendar(0L);
    }

    public UsageStatsDatabase(File dir) {
        this(dir, 4);
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
            for (int i = 0; i < fileCount - 1; i++) {
                if (((AtomicFile) files.valueAt(i)).getBaseFile().getPath().endsWith(CHECKED_IN_SUFFIX)) {
                    lastCheckin = i;
                }
            }
            int i2 = lastCheckin + 1;
            if (i2 == fileCount - 1) {
                return true;
            }
            try {
                IntervalStats stats = new IntervalStats();
                for (int i3 = i2; i3 < fileCount - 1; i3++) {
                    readLocked((AtomicFile) files.valueAt(i3), stats);
                    if (!checkinAction.checkin(stats)) {
                        return false;
                    }
                }
                for (int i4 = i2; i4 < fileCount - 1; i4++) {
                    AtomicFile file = (AtomicFile) files.valueAt(i4);
                    File checkedInFile = new File(file.getBaseFile().getPath() + CHECKED_IN_SUFFIX);
                    if (!file.getBaseFile().renameTo(checkedInFile)) {
                        Slog.e(TAG, "Failed to mark file " + file.getBaseFile().getPath() + " as checked-in");
                        return true;
                    }
                    files.setValueAt(i4, new AtomicFile(checkedInFile));
                }
                return true;
            } catch (IOException e) {
                Slog.e(TAG, "Failed to check-in", e);
                return false;
            }
        }
    }

    @VisibleForTesting
    void forceIndexFiles() {
        synchronized (this.mLock) {
            indexFilesLocked();
        }
    }

    private void indexFilesLocked() {
        FilenameFilter backupFileFilter = new FilenameFilter() { // from class: com.android.server.usage.UsageStatsDatabase.1
            @Override // java.io.FilenameFilter
            public boolean accept(File dir, String name) {
                return !name.endsWith(UsageStatsDatabase.BAK_SUFFIX);
            }
        };
        int i = 0;
        while (true) {
            TimeSparseArray<AtomicFile>[] timeSparseArrayArr = this.mSortedStatFiles;
            if (i < timeSparseArrayArr.length) {
                if (timeSparseArrayArr[i] == null) {
                    timeSparseArrayArr[i] = new TimeSparseArray<>();
                } else {
                    timeSparseArrayArr[i].clear();
                }
                File[] files = this.mIntervalDirs[i].listFiles(backupFileFilter);
                if (files != null) {
                    for (File f : files) {
                        AtomicFile af = new AtomicFile(f);
                        try {
                            this.mSortedStatFiles[i].put(parseBeginTime(af), af);
                        } catch (IOException e) {
                            Slog.e(TAG, "failed to index file: " + f, e);
                        }
                    }
                    int toDelete = this.mSortedStatFiles[i].size() - MAX_FILES_PER_INTERVAL_TYPE[i];
                    if (toDelete > 0) {
                        for (int j = 0; j < toDelete; j++) {
                            ((AtomicFile) this.mSortedStatFiles[i].valueAt(0)).delete();
                            this.mSortedStatFiles[i].removeAt(0);
                        }
                        Slog.d(TAG, "Deleted " + toDelete + " stat files for interval " + i);
                    }
                }
                i++;
            } else {
                return;
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
        int version;
        String currentFingerprint = getBuildFingerprint();
        this.mFirstUpdate = true;
        this.mNewUpdate = true;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(this.mVersionFile));
            version = Integer.parseInt(reader.readLine());
            String buildFingerprint = reader.readLine();
            if (buildFingerprint != null) {
                this.mFirstUpdate = false;
            }
            if (currentFingerprint.equals(buildFingerprint)) {
                this.mNewUpdate = false;
            }
            $closeResource(null, reader);
        } catch (IOException | NumberFormatException e) {
            version = 0;
        }
        if (version != this.mCurrentVersion) {
            Slog.i(TAG, "Upgrading from version " + version + " to " + this.mCurrentVersion);
            if (!this.mUpdateBreadcrumb.exists()) {
                try {
                    doUpgradeLocked(version);
                } catch (Exception e2) {
                    Slog.e(TAG, "Failed to upgrade from version " + version + " to " + this.mCurrentVersion, e2);
                    this.mCurrentVersion = version;
                    return;
                }
            } else {
                Slog.i(TAG, "Version upgrade breadcrumb found on disk! Continuing version upgrade");
            }
        }
        if (this.mUpdateBreadcrumb.exists()) {
            try {
                BufferedReader reader2 = new BufferedReader(new FileReader(this.mUpdateBreadcrumb));
                long token = Long.parseLong(reader2.readLine());
                int previousVersion = Integer.parseInt(reader2.readLine());
                $closeResource(null, reader2);
                continueUpgradeLocked(previousVersion, token);
            } catch (IOException | NumberFormatException e3) {
                Slog.e(TAG, "Failed read version upgrade breadcrumb");
                throw new RuntimeException(e3);
            }
        }
        if (version != this.mCurrentVersion || this.mNewUpdate) {
            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(this.mVersionFile));
                writer.write(Integer.toString(this.mCurrentVersion));
                writer.write("\n");
                writer.write(currentFingerprint);
                writer.write("\n");
                writer.flush();
                $closeResource(null, writer);
            } catch (IOException e4) {
                Slog.e(TAG, "Failed to write new version");
                throw new RuntimeException(e4);
            }
        }
        if (this.mUpdateBreadcrumb.exists()) {
            this.mUpdateBreadcrumb.delete();
        }
        if (this.mBackupsDir.exists()) {
            deleteDirectory(this.mBackupsDir);
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
        boolean z = false;
        if (thisVersion < 2) {
            Slog.i(TAG, "Deleting all usage stats files");
            int i = 0;
            while (true) {
                File[] fileArr = this.mIntervalDirs;
                if (i < fileArr.length) {
                    File[] files = fileArr[i].listFiles();
                    if (files != null) {
                        for (File f : files) {
                            f.delete();
                        }
                    }
                    i++;
                } else {
                    return;
                }
            }
        } else {
            long token = System.currentTimeMillis();
            File backupDir = new File(this.mBackupsDir, Long.toString(token));
            backupDir.mkdirs();
            if (!backupDir.exists()) {
                throw new IllegalStateException("Failed to create backup directory " + backupDir.getAbsolutePath());
            }
            try {
                Files.copy(this.mVersionFile.toPath(), new File(backupDir, this.mVersionFile.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                int i2 = 0;
                while (true) {
                    File[] fileArr2 = this.mIntervalDirs;
                    if (i2 < fileArr2.length) {
                        File backupIntervalDir = new File(backupDir, fileArr2[i2].getName());
                        backupIntervalDir.mkdir();
                        if (!backupIntervalDir.exists()) {
                            throw new IllegalStateException("Failed to create interval backup directory " + backupIntervalDir.getAbsolutePath());
                        }
                        File[] files2 = this.mIntervalDirs[i2].listFiles();
                        if (files2 != null) {
                            int j = 0;
                            while (j < files2.length) {
                                File backupFile = new File(backupIntervalDir, files2[j].getName());
                                try {
                                    Files.move(files2[j].toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    j++;
                                    z = false;
                                } catch (IOException e) {
                                    Slog.e(TAG, "Failed to back up file : " + files2[j].toString());
                                    throw new RuntimeException(e);
                                }
                            }
                            continue;
                        }
                        i2++;
                        z = z;
                    } else {
                        BufferedWriter writer = null;
                        try {
                            try {
                                writer = new BufferedWriter(new FileWriter(this.mUpdateBreadcrumb));
                                writer.write(Long.toString(token));
                                writer.write("\n");
                                writer.write(Integer.toString(thisVersion));
                                writer.write("\n");
                                writer.flush();
                                return;
                            } finally {
                                IoUtils.closeQuietly(writer);
                            }
                        } catch (IOException e2) {
                            Slog.e(TAG, "Failed to write new version upgrade breadcrumb");
                            throw new RuntimeException(e2);
                        }
                    }
                }
            } catch (IOException e3) {
                Slog.e(TAG, "Failed to back up version file : " + this.mVersionFile.toString());
                throw new RuntimeException(e3);
            }
        }
    }

    private void continueUpgradeLocked(int version, long token) {
        File backupDir = new File(this.mBackupsDir, Long.toString(token));
        int i = 0;
        while (true) {
            File[] fileArr = this.mIntervalDirs;
            if (i < fileArr.length) {
                File backedUpInterval = new File(backupDir, fileArr[i].getName());
                File[] files = backedUpInterval.listFiles();
                if (files != null) {
                    for (int j = 0; j < files.length; j++) {
                        try {
                            IntervalStats stats = new IntervalStats();
                            readLocked(new AtomicFile(files[j]), stats, version);
                            writeLocked(new AtomicFile(new File(this.mIntervalDirs[i], Long.toString(stats.beginTime))), stats, this.mCurrentVersion);
                        } catch (Exception e) {
                            Slog.e(TAG, "Failed to upgrade backup file : " + files[j].toString());
                        }
                    }
                }
                i++;
            } else {
                return;
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
            int filesDeleted2 = 0;
            TimeSparseArray<AtomicFile>[] timeSparseArrayArr = this.mSortedStatFiles;
            int length = timeSparseArrayArr.length;
            int i = 0;
            while (i < length) {
                TimeSparseArray<AtomicFile> files = timeSparseArrayArr[i];
                int fileCount = files.size();
                int i2 = 0;
                int filesMoved = filesDeleted2;
                int filesDeleted3 = filesDeleted;
                while (i2 < fileCount) {
                    AtomicFile file = (AtomicFile) files.valueAt(i2);
                    long newTime = files.keyAt(i2) + j;
                    if (newTime < 0) {
                        filesDeleted3++;
                        file.delete();
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
                        filesMoved++;
                        file.getBaseFile().renameTo(newFile);
                    }
                    i2++;
                    j = timeDiffMillis;
                }
                files.clear();
                i++;
                j = timeDiffMillis;
                filesDeleted = filesDeleted3;
                filesDeleted2 = filesMoved;
            }
            logBuilder.append(" files deleted: ");
            logBuilder.append(filesDeleted);
            logBuilder.append(" files moved: ");
            logBuilder.append(filesDeleted2);
            Slog.i(TAG, logBuilder.toString());
            indexFilesLocked();
        }
    }

    public IntervalStats getLatestUsageStats(int intervalType) {
        synchronized (this.mLock) {
            if (intervalType >= 0) {
                if (intervalType < this.mIntervalDirs.length) {
                    int fileCount = this.mSortedStatFiles[intervalType].size();
                    if (fileCount == 0) {
                        return null;
                    }
                    try {
                        AtomicFile f = (AtomicFile) this.mSortedStatFiles[intervalType].valueAt(fileCount - 1);
                        IntervalStats stats = new IntervalStats();
                        readLocked(f, stats);
                        return stats;
                    } catch (IOException e) {
                        Slog.e(TAG, "Failed to read usage stats file", e);
                        return null;
                    }
                }
            }
            throw new IllegalArgumentException("Bad interval type " + intervalType);
        }
    }

    public <T> List<T> queryUsageStats(int intervalType, long beginTime, long endTime, StatCombiner<T> combiner) {
        int startIndex;
        int endIndex;
        UsageStatsDatabase usageStatsDatabase = this;
        synchronized (usageStatsDatabase.mLock) {
            try {
                if (intervalType < 0 || intervalType >= usageStatsDatabase.mIntervalDirs.length) {
                    throw new IllegalArgumentException("Bad interval type " + intervalType);
                }
                TimeSparseArray<AtomicFile> intervalStats = usageStatsDatabase.mSortedStatFiles[intervalType];
                if (endTime <= beginTime) {
                    return null;
                }
                int startIndex2 = intervalStats.closestIndexOnOrBefore(beginTime);
                if (startIndex2 >= 0) {
                    startIndex = startIndex2;
                } else {
                    startIndex = 0;
                }
                int endIndex2 = intervalStats.closestIndexOnOrBefore(endTime);
                if (endIndex2 < 0) {
                    return null;
                }
                if (intervalStats.keyAt(endIndex2) != endTime) {
                    endIndex = endIndex2;
                } else {
                    int endIndex3 = endIndex2 - 1;
                    if (endIndex3 < 0) {
                        return null;
                    }
                    endIndex = endIndex3;
                }
                IntervalStats stats = new IntervalStats();
                ArrayList<T> results = new ArrayList<>();
                int i = startIndex;
                while (i <= endIndex) {
                    AtomicFile f = (AtomicFile) intervalStats.valueAt(i);
                    try {
                        usageStatsDatabase.readLocked(f, stats);
                        if (beginTime < stats.endTime) {
                            try {
                                combiner.combine(stats, false, results);
                            } catch (IOException e) {
                                e = e;
                                Slog.e(TAG, "Failed to read usage stats file", e);
                                i++;
                                usageStatsDatabase = this;
                            }
                        }
                    } catch (IOException e2) {
                        e = e2;
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                    i++;
                    usageStatsDatabase = this;
                }
                return results;
            } catch (Throwable th2) {
                th = th2;
            }
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
            pruneFilesOlderThan(this.mIntervalDirs[0], this.mCal.getTimeInMillis());
            this.mCal.setTimeInMillis(currentTimeMillis);
            this.mCal.addDays(-SELECTION_LOG_RETENTION_LEN);
            for (int i = 0; i < this.mIntervalDirs.length; i++) {
                pruneChooserCountsOlderThan(this.mIntervalDirs[i], this.mCal.getTimeInMillis());
            }
            indexFilesLocked();
        }
    }

    private static void pruneFilesOlderThan(File dir, long expiryTime) {
        long beginTime;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                try {
                    beginTime = parseBeginTime(f);
                } catch (IOException e) {
                    beginTime = 0;
                }
                if (beginTime < expiryTime) {
                    new AtomicFile(f).delete();
                }
            }
        }
    }

    private void pruneChooserCountsOlderThan(File dir, long expiryTime) {
        long beginTime;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                try {
                    beginTime = parseBeginTime(f);
                } catch (IOException e) {
                    beginTime = 0;
                }
                if (beginTime < expiryTime) {
                    try {
                        AtomicFile af = new AtomicFile(f);
                        IntervalStats stats = new IntervalStats();
                        readLocked(af, stats);
                        int pkgCount = stats.packageStats.size();
                        for (int i = 0; i < pkgCount; i++) {
                            UsageStats pkgStats = stats.packageStats.valueAt(i);
                            if (pkgStats.mChooserCounts != null) {
                                pkgStats.mChooserCounts.clear();
                            }
                        }
                        writeLocked(af, stats);
                    } catch (Exception e2) {
                        Slog.e(TAG, "Failed to delete chooser counts from usage stats file", e2);
                    }
                }
            }
        }
    }

    private static long parseBeginTime(AtomicFile file) throws IOException {
        return parseBeginTime(file.getBaseFile());
    }

    private static long parseBeginTime(File file) throws IOException {
        String name = file.getName();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                name = name.substring(0, i);
                break;
            }
        }
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            throw new IOException(e);
        }
    }

    private void writeLocked(AtomicFile file, IntervalStats stats) throws IOException {
        writeLocked(file, stats, this.mCurrentVersion);
    }

    private static void writeLocked(AtomicFile file, IntervalStats stats, int version) throws IOException {
        FileOutputStream fos = file.startWrite();
        try {
            writeLocked(fos, stats, version);
            file.finishWrite(fos);
            fos = null;
        } finally {
            file.failWrite(fos);
        }
    }

    private void writeLocked(OutputStream out, IntervalStats stats) throws IOException {
        writeLocked(out, stats, this.mCurrentVersion);
    }

    private static void writeLocked(OutputStream out, IntervalStats stats, int version) throws IOException {
        if (version == 1 || version == 2 || version == 3) {
            UsageStatsXml.write(out, stats);
        } else if (version == 4) {
            UsageStatsProto.write(out, stats);
        } else {
            throw new RuntimeException("Unhandled UsageStatsDatabase version: " + Integer.toString(version) + " on write.");
        }
    }

    private void readLocked(AtomicFile file, IntervalStats statsOut) throws IOException {
        readLocked(file, statsOut, this.mCurrentVersion);
    }

    private static void readLocked(AtomicFile file, IntervalStats statsOut, int version) throws IOException {
        try {
            FileInputStream in = file.openRead();
            statsOut.beginTime = parseBeginTime(file);
            readLocked(in, statsOut, version);
            statsOut.lastTimeSaved = file.getLastModifiedTime();
            try {
                in.close();
            } catch (IOException e) {
            }
        } catch (FileNotFoundException e2) {
            Slog.e(TAG, TAG, e2);
            throw e2;
        }
    }

    private void readLocked(InputStream in, IntervalStats statsOut) throws IOException {
        readLocked(in, statsOut, this.mCurrentVersion);
    }

    private static void readLocked(InputStream in, IntervalStats statsOut, int version) throws IOException {
        if (version == 1 || version == 2 || version == 3) {
            UsageStatsXml.read(in, statsOut);
        } else if (version == 4) {
            UsageStatsProto.read(in, statsOut);
        } else {
            throw new RuntimeException("Unhandled UsageStatsDatabase version: " + Integer.toString(version) + " on read.");
        }
    }

    public void putUsageStats(int intervalType, IntervalStats stats) throws IOException {
        if (stats == null) {
            return;
        }
        synchronized (this.mLock) {
            if (intervalType >= 0) {
                if (intervalType < this.mIntervalDirs.length) {
                    AtomicFile f = (AtomicFile) this.mSortedStatFiles[intervalType].get(stats.beginTime);
                    if (f == null) {
                        f = new AtomicFile(new File(this.mIntervalDirs[intervalType], Long.toString(stats.beginTime)));
                        this.mSortedStatFiles[intervalType].put(stats.beginTime, f);
                    }
                    writeLocked(f, stats);
                    stats.lastTimeSaved = f.getLastModifiedTime();
                }
            }
            throw new IllegalArgumentException("Bad interval type " + intervalType);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public byte[] getBackupPayload(String key) {
        return getBackupPayload(key, 4);
    }

    @VisibleForTesting
    public byte[] getBackupPayload(String key, int version) {
        byte[] byteArray;
        synchronized (this.mLock) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (KEY_USAGE_STATS.equals(key)) {
                prune(System.currentTimeMillis());
                DataOutputStream out = new DataOutputStream(baos);
                try {
                    out.writeInt(version);
                    out.writeInt(this.mSortedStatFiles[0].size());
                    for (int i = 0; i < this.mSortedStatFiles[0].size(); i++) {
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[0].valueAt(i), version);
                    }
                    out.writeInt(this.mSortedStatFiles[1].size());
                    for (int i2 = 0; i2 < this.mSortedStatFiles[1].size(); i2++) {
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[1].valueAt(i2), version);
                    }
                    out.writeInt(this.mSortedStatFiles[2].size());
                    for (int i3 = 0; i3 < this.mSortedStatFiles[2].size(); i3++) {
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[2].valueAt(i3), version);
                    }
                    out.writeInt(this.mSortedStatFiles[3].size());
                    for (int i4 = 0; i4 < this.mSortedStatFiles[3].size(); i4++) {
                        writeIntervalStatsToStream(out, (AtomicFile) this.mSortedStatFiles[3].valueAt(i4), version);
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
        jadx.core.utils.exceptions.JadxRuntimeException: Unreachable block: B:54:0x00e4
        	at jadx.core.dex.visitors.blocks.BlockProcessor.checkForUnreachableBlocks(BlockProcessor.java:81)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:47)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    @com.android.internal.annotations.VisibleForTesting
    public void applyRestoredPayload(java.lang.String r18, byte[] r19) {
        /*
            Method dump skipped, instructions count: 237
            To view this dump change 'Code comments level' option to 'DEBUG'
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
        beingRestored.events.clear();
        beingRestored.events.merge(onDevice.events);
        return beingRestored;
    }

    private void writeIntervalStatsToStream(DataOutputStream out, AtomicFile statsFile, int version) throws IOException {
        IntervalStats stats = new IntervalStats();
        try {
            readLocked(statsFile, stats);
            sanitizeIntervalStatsForBackup(stats);
            byte[] data = serializeIntervalStats(stats, version);
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
        stats.events.clear();
    }

    private byte[] serializeIntervalStats(IntervalStats stats, int version) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        try {
            out.writeLong(stats.beginTime);
            writeLocked(out, stats, version);
        } catch (Exception ioe) {
            Slog.d(TAG, "Serializing IntervalStats Failed", ioe);
            baos.reset();
        }
        return baos.toByteArray();
    }

    private IntervalStats deserializeIntervalStats(byte[] data, int version) {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream in = new DataInputStream(bais);
        IntervalStats stats = new IntervalStats();
        try {
            stats.beginTime = in.readLong();
            readLocked(in, stats, version);
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

    public void dump(IndentingPrintWriter pw, boolean compact) {
        synchronized (this.mLock) {
            pw.println("UsageStatsDatabase:");
            pw.increaseIndent();
            for (int i = 0; i < this.mSortedStatFiles.length; i++) {
                TimeSparseArray<AtomicFile> files = this.mSortedStatFiles[i];
                int size = files.size();
                pw.print(UserUsageStatsService.intervalToString(i));
                pw.print(" stats files: ");
                pw.print(size);
                pw.println(", sorted list of files:");
                pw.increaseIndent();
                for (int f = 0; f < size; f++) {
                    long fileName = files.keyAt(f);
                    if (compact) {
                        pw.print(UserUsageStatsService.formatDateTime(fileName, false));
                    } else {
                        pw.printPair(Long.toString(fileName), UserUsageStatsService.formatDateTime(fileName, true));
                    }
                    pw.println();
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public IntervalStats readIntervalStatsForFile(int interval, long fileName) {
        IntervalStats stats;
        synchronized (this.mLock) {
            stats = new IntervalStats();
            try {
                readLocked((AtomicFile) this.mSortedStatFiles[interval].get(fileName, (Object) null), stats);
            } catch (Exception e) {
                return null;
            }
        }
        return stats;
    }
}
