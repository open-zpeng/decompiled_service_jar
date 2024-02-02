package com.android.server.content;

import android.accounts.Account;
import android.app.job.JobParameters;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.util.Log;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.content.SyncManager;
import com.android.server.content.SyncStorageEngine;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import libcore.io.IoUtils;
/* loaded from: classes.dex */
public class SyncLogger {
    public static final int CALLING_UID_SELF = -1;
    private static final String TAG = "SyncLogger";
    private static SyncLogger sInstance;

    SyncLogger() {
    }

    /* JADX WARN: Removed duplicated region for block: B:16:0x0028 A[Catch: all -> 0x003b, TryCatch #0 {, blocks: (B:4:0x0003, B:6:0x0007, B:8:0x000b, B:10:0x0019, B:16:0x0028, B:17:0x0030, B:18:0x0037), top: B:24:0x0003 }] */
    /* JADX WARN: Removed duplicated region for block: B:17:0x0030 A[Catch: all -> 0x003b, TryCatch #0 {, blocks: (B:4:0x0003, B:6:0x0007, B:8:0x000b, B:10:0x0019, B:16:0x0028, B:17:0x0030, B:18:0x0037), top: B:24:0x0003 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static synchronized com.android.server.content.SyncLogger getInstance() {
        /*
            java.lang.Class<com.android.server.content.SyncLogger> r0 = com.android.server.content.SyncLogger.class
            monitor-enter(r0)
            com.android.server.content.SyncLogger r1 = com.android.server.content.SyncLogger.sInstance     // Catch: java.lang.Throwable -> L3b
            if (r1 != 0) goto L37
            boolean r1 = android.os.Build.IS_DEBUGGABLE     // Catch: java.lang.Throwable -> L3b
            if (r1 != 0) goto L25
            java.lang.String r1 = "1"
            java.lang.String r2 = "debug.synclog"
            java.lang.String r2 = android.os.SystemProperties.get(r2)     // Catch: java.lang.Throwable -> L3b
            boolean r1 = r1.equals(r2)     // Catch: java.lang.Throwable -> L3b
            if (r1 != 0) goto L25
            java.lang.String r1 = "SyncLogger"
            r2 = 2
            boolean r1 = android.util.Log.isLoggable(r1, r2)     // Catch: java.lang.Throwable -> L3b
            if (r1 == 0) goto L23
            goto L25
        L23:
            r1 = 0
            goto L26
        L25:
            r1 = 1
        L26:
            if (r1 == 0) goto L30
            com.android.server.content.SyncLogger$RotatingFileLogger r2 = new com.android.server.content.SyncLogger$RotatingFileLogger     // Catch: java.lang.Throwable -> L3b
            r2.<init>()     // Catch: java.lang.Throwable -> L3b
            com.android.server.content.SyncLogger.sInstance = r2     // Catch: java.lang.Throwable -> L3b
            goto L37
        L30:
            com.android.server.content.SyncLogger r2 = new com.android.server.content.SyncLogger     // Catch: java.lang.Throwable -> L3b
            r2.<init>()     // Catch: java.lang.Throwable -> L3b
            com.android.server.content.SyncLogger.sInstance = r2     // Catch: java.lang.Throwable -> L3b
        L37:
            com.android.server.content.SyncLogger r1 = com.android.server.content.SyncLogger.sInstance     // Catch: java.lang.Throwable -> L3b
            monitor-exit(r0)
            return r1
        L3b:
            r1 = move-exception
            monitor-exit(r0)
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.content.SyncLogger.getInstance():com.android.server.content.SyncLogger");
    }

    public void log(Object... message) {
    }

    public void purgeOldLogs() {
    }

    public String jobParametersToString(JobParameters params) {
        return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    }

    public void dumpAll(PrintWriter pw) {
    }

    public boolean enabled() {
        return false;
    }

    /* loaded from: classes.dex */
    private static class RotatingFileLogger extends SyncLogger {
        @GuardedBy("mLock")
        private long mCurrentLogFileDayTimestamp;
        @GuardedBy("mLock")
        private boolean mErrorShown;
        @GuardedBy("mLock")
        private Writer mLogWriter;
        private static final SimpleDateFormat sTimestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        private static final SimpleDateFormat sFilenameDateFormat = new SimpleDateFormat("yyyy-MM-dd");
        private static final boolean DO_LOGCAT = Log.isLoggable(SyncLogger.TAG, 3);
        private final Object mLock = new Object();
        private final long mKeepAgeMs = TimeUnit.DAYS.toMillis(7);
        @GuardedBy("mLock")
        private final Date mCachedDate = new Date();
        @GuardedBy("mLock")
        private final StringBuilder mStringBuilder = new StringBuilder();
        private final File mLogPath = new File(Environment.getDataSystemDirectory(), "syncmanager-log");

        RotatingFileLogger() {
        }

        @Override // com.android.server.content.SyncLogger
        public boolean enabled() {
            return true;
        }

        private void handleException(String message, Exception e) {
            if (!this.mErrorShown) {
                Slog.e(SyncLogger.TAG, message, e);
                this.mErrorShown = true;
            }
        }

        @Override // com.android.server.content.SyncLogger
        public void log(Object... message) {
            if (message == null) {
                return;
            }
            synchronized (this.mLock) {
                long now = System.currentTimeMillis();
                openLogLocked(now);
                if (this.mLogWriter == null) {
                    return;
                }
                this.mStringBuilder.setLength(0);
                this.mCachedDate.setTime(now);
                this.mStringBuilder.append(sTimestampFormat.format(this.mCachedDate));
                this.mStringBuilder.append(' ');
                this.mStringBuilder.append(Process.myTid());
                this.mStringBuilder.append(' ');
                int messageStart = this.mStringBuilder.length();
                for (Object o : message) {
                    this.mStringBuilder.append(o);
                }
                this.mStringBuilder.append('\n');
                try {
                    this.mLogWriter.append((CharSequence) this.mStringBuilder);
                    this.mLogWriter.flush();
                    if (DO_LOGCAT) {
                        Log.d(SyncLogger.TAG, this.mStringBuilder.substring(messageStart));
                    }
                } catch (IOException e) {
                    handleException("Failed to write log", e);
                }
            }
        }

        @GuardedBy("mLock")
        private void openLogLocked(long now) {
            long day = now % 86400000;
            if (this.mLogWriter != null && day == this.mCurrentLogFileDayTimestamp) {
                return;
            }
            closeCurrentLogLocked();
            this.mCurrentLogFileDayTimestamp = day;
            this.mCachedDate.setTime(now);
            String filename = "synclog-" + sFilenameDateFormat.format(this.mCachedDate) + ".log";
            File file = new File(this.mLogPath, filename);
            file.getParentFile().mkdirs();
            try {
                this.mLogWriter = new FileWriter(file, true);
            } catch (IOException e) {
                handleException("Failed to open log file: " + file, e);
            }
        }

        @GuardedBy("mLock")
        private void closeCurrentLogLocked() {
            IoUtils.closeQuietly(this.mLogWriter);
            this.mLogWriter = null;
        }

        @Override // com.android.server.content.SyncLogger
        public void purgeOldLogs() {
            synchronized (this.mLock) {
                FileUtils.deleteOlderFiles(this.mLogPath, 1, this.mKeepAgeMs);
            }
        }

        @Override // com.android.server.content.SyncLogger
        public String jobParametersToString(JobParameters params) {
            return SyncJobService.jobParametersToString(params);
        }

        @Override // com.android.server.content.SyncLogger
        public void dumpAll(PrintWriter pw) {
            synchronized (this.mLock) {
                String[] files = this.mLogPath.list();
                if (files != null && files.length != 0) {
                    Arrays.sort(files);
                    for (String file : files) {
                        dumpFile(pw, new File(this.mLogPath, file));
                    }
                }
            }
        }

        private void dumpFile(PrintWriter pw, File file) {
            Slog.w(SyncLogger.TAG, "Dumping " + file);
            char[] buffer = new char[32768];
            try {
                Reader in = new BufferedReader(new FileReader(file));
                while (true) {
                    int read = in.read(buffer);
                    if (read >= 0) {
                        if (read > 0) {
                            pw.write(buffer, 0, read);
                        }
                    } else {
                        in.close();
                        return;
                    }
                }
            } catch (IOException e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String logSafe(Account account) {
        if (account == null) {
            return "[null]";
        }
        return "***/" + account.type;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String logSafe(SyncStorageEngine.EndPoint endPoint) {
        return endPoint == null ? "[null]" : endPoint.toSafeString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String logSafe(SyncOperation operation) {
        return operation == null ? "[null]" : operation.toSafeString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String logSafe(SyncManager.ActiveSyncContext asc) {
        return asc == null ? "[null]" : asc.toSafeString();
    }
}
