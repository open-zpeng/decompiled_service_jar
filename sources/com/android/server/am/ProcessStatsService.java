package com.android.server.am;

import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.DumpUtils;
import com.android.internal.app.procstats.IProcessStats;
import com.android.internal.app.procstats.ProcessState;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.app.procstats.ServiceState;
import com.android.internal.os.BackgroundThread;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.utils.PriorityDump;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
/* loaded from: classes.dex */
public final class ProcessStatsService extends IProcessStats.Stub {
    static final boolean DEBUG = false;
    static final int MAX_HISTORIC_STATES = 8;
    static final String STATE_FILE_CHECKIN_SUFFIX = ".ci";
    static final String STATE_FILE_PREFIX = "state-";
    static final String STATE_FILE_SUFFIX = ".bin";
    static final String TAG = "ProcessStatsService";
    static long WRITE_PERIOD = BackupAgentTimeoutParameters.DEFAULT_SHARED_BACKUP_AGENT_TIMEOUT_MILLIS;
    final ActivityManagerService mAm;
    final File mBaseDir;
    boolean mCommitPending;
    AtomicFile mFile;
    @GuardedBy("mAm")
    Boolean mInjectedScreenState;
    long mLastWriteTime;
    boolean mMemFactorLowered;
    Parcel mPendingWrite;
    boolean mPendingWriteCommitted;
    AtomicFile mPendingWriteFile;
    ProcessStats mProcessStats;
    boolean mShuttingDown;
    int mLastMemOnlyState = -1;
    final ReentrantLock mWriteLock = new ReentrantLock();
    final Object mPendingWriteLock = new Object();

    public ProcessStatsService(ActivityManagerService am, File file) {
        this.mAm = am;
        this.mBaseDir = file;
        this.mBaseDir.mkdirs();
        this.mProcessStats = new ProcessStats(true);
        updateFile();
        SystemProperties.addChangeCallback(new Runnable() { // from class: com.android.server.am.ProcessStatsService.1
            @Override // java.lang.Runnable
            public void run() {
                synchronized (ProcessStatsService.this.mAm) {
                    try {
                        ActivityManagerService.boostPriorityForLockedSection();
                        if (ProcessStatsService.this.mProcessStats.evaluateSystemProperties(false)) {
                            ProcessStatsService.this.mProcessStats.mFlags |= 4;
                            ProcessStatsService.this.writeStateLocked(true, true);
                            ProcessStatsService.this.mProcessStats.evaluateSystemProperties(true);
                        }
                    } catch (Throwable th) {
                        ActivityManagerService.resetPriorityAfterLockedSection();
                        throw th;
                    }
                }
                ActivityManagerService.resetPriorityAfterLockedSection();
            }
        });
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Process Stats Crash", e);
            }
            throw e;
        }
    }

    public ProcessState getProcessStateLocked(String packageName, int uid, long versionCode, String processName) {
        return this.mProcessStats.getProcessStateLocked(packageName, uid, versionCode, processName);
    }

    public ServiceState getServiceStateLocked(String packageName, int uid, long versionCode, String processName, String className) {
        return this.mProcessStats.getServiceStateLocked(packageName, uid, versionCode, processName, className);
    }

    public boolean isMemFactorLowered() {
        return this.mMemFactorLowered;
    }

    @GuardedBy("mAm")
    public boolean setMemFactorLocked(int memFactor, boolean screenOn, long now) {
        this.mMemFactorLowered = memFactor < this.mLastMemOnlyState;
        this.mLastMemOnlyState = memFactor;
        if (this.mInjectedScreenState != null) {
            screenOn = this.mInjectedScreenState.booleanValue();
        }
        if (screenOn) {
            memFactor += 4;
        }
        if (memFactor != this.mProcessStats.mMemFactor) {
            if (this.mProcessStats.mMemFactor != -1) {
                long[] jArr = this.mProcessStats.mMemFactorDurations;
                int i = this.mProcessStats.mMemFactor;
                jArr[i] = jArr[i] + (now - this.mProcessStats.mStartTime);
            }
            this.mProcessStats.mMemFactor = memFactor;
            this.mProcessStats.mStartTime = now;
            ArrayMap<String, SparseArray<LongSparseArray<ProcessStats.PackageState>>> pmap = this.mProcessStats.mPackages.getMap();
            for (int ipkg = pmap.size() - 1; ipkg >= 0; ipkg--) {
                SparseArray<LongSparseArray<ProcessStats.PackageState>> uids = pmap.valueAt(ipkg);
                for (int iuid = uids.size() - 1; iuid >= 0; iuid--) {
                    LongSparseArray<ProcessStats.PackageState> vers = uids.valueAt(iuid);
                    for (int iver = vers.size() - 1; iver >= 0; iver--) {
                        ProcessStats.PackageState pkg = vers.valueAt(iver);
                        ArrayMap<String, ServiceState> services = pkg.mServices;
                        for (int isvc = services.size() - 1; isvc >= 0; isvc--) {
                            ServiceState service = services.valueAt(isvc);
                            service.setMemFactor(memFactor, now);
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    public int getMemFactorLocked() {
        if (this.mProcessStats.mMemFactor != -1) {
            return this.mProcessStats.mMemFactor;
        }
        return 0;
    }

    public void addSysMemUsageLocked(long cachedMem, long freeMem, long zramMem, long kernelMem, long nativeMem) {
        this.mProcessStats.addSysMemUsage(cachedMem, freeMem, zramMem, kernelMem, nativeMem);
    }

    public boolean shouldWriteNowLocked(long now) {
        if (now > this.mLastWriteTime + WRITE_PERIOD) {
            if (SystemClock.elapsedRealtime() > this.mProcessStats.mTimePeriodStartRealtime + ProcessStats.COMMIT_PERIOD && SystemClock.uptimeMillis() > this.mProcessStats.mTimePeriodStartUptime + ProcessStats.COMMIT_UPTIME_PERIOD) {
                this.mCommitPending = true;
            }
            return true;
        }
        return false;
    }

    public void shutdownLocked() {
        Slog.w(TAG, "Writing process stats before shutdown...");
        this.mProcessStats.mFlags |= 2;
        writeStateSyncLocked();
        this.mShuttingDown = true;
    }

    public void writeStateAsyncLocked() {
        writeStateLocked(false);
    }

    public void writeStateSyncLocked() {
        writeStateLocked(true);
    }

    private void writeStateLocked(boolean sync) {
        if (this.mShuttingDown) {
            return;
        }
        boolean commitPending = this.mCommitPending;
        this.mCommitPending = false;
        writeStateLocked(sync, commitPending);
    }

    public void writeStateLocked(boolean sync, boolean commit) {
        synchronized (this.mPendingWriteLock) {
            long now = SystemClock.uptimeMillis();
            if (this.mPendingWrite == null || !this.mPendingWriteCommitted) {
                this.mPendingWrite = Parcel.obtain();
                this.mProcessStats.mTimePeriodEndRealtime = SystemClock.elapsedRealtime();
                this.mProcessStats.mTimePeriodEndUptime = now;
                if (commit) {
                    this.mProcessStats.mFlags |= 1;
                }
                this.mProcessStats.writeToParcel(this.mPendingWrite, 0);
                this.mPendingWriteFile = new AtomicFile(this.mFile.getBaseFile());
                this.mPendingWriteCommitted = commit;
            }
            if (commit) {
                this.mProcessStats.resetSafely();
                updateFile();
                this.mAm.requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, false);
            }
            this.mLastWriteTime = SystemClock.uptimeMillis();
            final long totalTime = SystemClock.uptimeMillis() - now;
            if (!sync) {
                BackgroundThread.getHandler().post(new Runnable() { // from class: com.android.server.am.ProcessStatsService.2
                    @Override // java.lang.Runnable
                    public void run() {
                        ProcessStatsService.this.performWriteState(totalTime);
                    }
                });
            } else {
                performWriteState(totalTime);
            }
        }
    }

    private void updateFile() {
        File file = this.mBaseDir;
        this.mFile = new AtomicFile(new File(file, STATE_FILE_PREFIX + this.mProcessStats.mTimePeriodStartClockStr + STATE_FILE_SUFFIX));
        this.mLastWriteTime = SystemClock.uptimeMillis();
    }

    void performWriteState(long initialTime) {
        synchronized (this.mPendingWriteLock) {
            Parcel data = this.mPendingWrite;
            AtomicFile file = this.mPendingWriteFile;
            this.mPendingWriteCommitted = false;
            if (data == null) {
                return;
            }
            this.mPendingWrite = null;
            this.mPendingWriteFile = null;
            this.mWriteLock.lock();
            SystemClock.uptimeMillis();
            FileOutputStream stream = null;
            try {
                try {
                    stream = file.startWrite();
                    stream.write(data.marshall());
                    stream.flush();
                    file.finishWrite(stream);
                } catch (IOException e) {
                    Slog.w(TAG, "Error writing process statistics", e);
                    file.failWrite(stream);
                }
            } finally {
                data.recycle();
                trimHistoricStatesWriteLocked();
                this.mWriteLock.unlock();
            }
        }
    }

    boolean readLocked(ProcessStats stats, AtomicFile file) {
        try {
            FileInputStream stream = file.openRead();
            stats.read(stream);
            stream.close();
            if (stats.mReadError != null) {
                Slog.w(TAG, "Ignoring existing stats; " + stats.mReadError);
                return false;
            }
            return true;
        } catch (Throwable e) {
            stats.mReadError = "caught exception: " + e;
            Slog.e(TAG, "Error reading process statistics", e);
            return false;
        }
    }

    private ArrayList<String> getCommittedFiles(int minNum, boolean inclCurrent, boolean inclCheckedIn) {
        File[] files = this.mBaseDir.listFiles();
        if (files == null || files.length <= minNum) {
            return null;
        }
        ArrayList<String> filesArray = new ArrayList<>(files.length);
        String currentFile = this.mFile.getBaseFile().getPath();
        for (File file : files) {
            String fileStr = file.getPath();
            if ((inclCheckedIn || !fileStr.endsWith(STATE_FILE_CHECKIN_SUFFIX)) && (inclCurrent || !fileStr.equals(currentFile))) {
                filesArray.add(fileStr);
            }
        }
        Collections.sort(filesArray);
        return filesArray;
    }

    public void trimHistoricStatesWriteLocked() {
        ArrayList<String> filesArray = getCommittedFiles(8, false, true);
        if (filesArray == null) {
            return;
        }
        while (filesArray.size() > 8) {
            String file = filesArray.remove(0);
            Slog.i(TAG, "Pruning old procstats: " + file);
            new File(file).delete();
        }
    }

    boolean dumpFilteredProcessesCsvLocked(PrintWriter pw, String header, boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates, boolean sepProcStates, int[] procStates, long now, String reqPackage) {
        ArrayList<ProcessState> procs = this.mProcessStats.collectProcessesLocked(screenStates, memStates, procStates, procStates, now, reqPackage, false);
        if (procs.size() > 0) {
            if (header != null) {
                pw.println(header);
            }
            DumpUtils.dumpProcessListCsv(pw, procs, sepScreenStates, screenStates, sepMemStates, memStates, sepProcStates, procStates, now);
            return true;
        }
        return false;
    }

    static int[] parseStateList(String[] states, int mult, String arg, boolean[] outSep, String[] outError) {
        ArrayList<Integer> res = new ArrayList<>();
        int lastPos = 0;
        int lastPos2 = 0;
        while (lastPos2 <= arg.length()) {
            char c = lastPos2 < arg.length() ? arg.charAt(lastPos2) : (char) 0;
            if (c == ',' || c == '+' || c == ' ' || c == 0) {
                boolean isSep = c == ',';
                if (lastPos == 0) {
                    outSep[0] = isSep;
                } else if (c != 0 && outSep[0] != isSep) {
                    outError[0] = "inconsistent separators (can't mix ',' with '+')";
                    return null;
                }
                if (lastPos < lastPos2 - 1) {
                    String str = arg.substring(lastPos, lastPos2);
                    int j = 0;
                    while (true) {
                        if (j < states.length) {
                            if (!str.equals(states[j])) {
                                j++;
                            } else {
                                res.add(Integer.valueOf(j));
                                str = null;
                                break;
                            }
                        } else {
                            break;
                        }
                    }
                    if (str != null) {
                        outError[0] = "invalid word \"" + str + "\"";
                        return null;
                    }
                }
                lastPos = lastPos2 + 1;
            }
            lastPos2++;
        }
        int i = res.size();
        int[] finalRes = new int[i];
        for (int i2 = 0; i2 < res.size(); i2++) {
            finalRes[i2] = res.get(i2).intValue() * mult;
        }
        return finalRes;
    }

    public byte[] getCurrentStats(List<ParcelFileDescriptor> historic) {
        this.mAm.mContext.enforceCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS", null);
        Parcel current = Parcel.obtain();
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                long now = SystemClock.uptimeMillis();
                this.mProcessStats.mTimePeriodEndRealtime = SystemClock.elapsedRealtime();
                this.mProcessStats.mTimePeriodEndUptime = now;
                this.mProcessStats.writeToParcel(current, now, 0);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        this.mWriteLock.lock();
        if (historic != null) {
            try {
                ArrayList<String> files = getCommittedFiles(0, false, true);
                if (files != null) {
                    int i = files.size() - 1;
                    while (true) {
                        int i2 = i;
                        if (i2 < 0) {
                            break;
                        }
                        try {
                            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(files.get(i2)), 268435456);
                            historic.add(pfd);
                        } catch (IOException e) {
                            Slog.w(TAG, "Failure opening procstat file " + files.get(i2), e);
                        }
                        i = i2 - 1;
                    }
                }
            } catch (Throwable th2) {
                this.mWriteLock.unlock();
                throw th2;
            }
        }
        this.mWriteLock.unlock();
        return current.marshall();
    }

    public ParcelFileDescriptor getStatsOverTime(long minTime) {
        boolean z;
        long curTime;
        Throwable th;
        IOException e;
        long curTime2;
        this.mAm.mContext.enforceCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS", null);
        Parcel current = Parcel.obtain();
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                long now = SystemClock.uptimeMillis();
                this.mProcessStats.mTimePeriodEndRealtime = SystemClock.elapsedRealtime();
                this.mProcessStats.mTimePeriodEndUptime = now;
                z = false;
                this.mProcessStats.writeToParcel(current, now, 0);
                curTime = this.mProcessStats.mTimePeriodEndRealtime - this.mProcessStats.mTimePeriodStartRealtime;
            } catch (Throwable th2) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th2;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        this.mWriteLock.lock();
        if (curTime < minTime) {
            try {
                ArrayList<String> files = getCommittedFiles(0, false, true);
                if (files != null && files.size() > 0) {
                    current.setDataPosition(0);
                    ProcessStats stats = (ProcessStats) ProcessStats.CREATOR.createFromParcel(current);
                    current.recycle();
                    int i = files.size() - 1;
                    while (true) {
                        int i2 = i;
                        if (i2 < 0) {
                            break;
                        } else if (stats.mTimePeriodEndRealtime - stats.mTimePeriodStartRealtime >= minTime) {
                            break;
                        } else {
                            AtomicFile file = new AtomicFile(new File(files.get(i2)));
                            int i3 = i2 - 1;
                            ProcessStats moreStats = new ProcessStats(z);
                            readLocked(moreStats, file);
                            if (moreStats.mReadError == null) {
                                stats.add(moreStats);
                                StringBuilder sb = new StringBuilder();
                                sb.append("Added stats: ");
                                sb.append(moreStats.mTimePeriodStartClockStr);
                                sb.append(", over ");
                                curTime2 = curTime;
                                try {
                                    try {
                                        TimeUtils.formatDuration(moreStats.mTimePeriodEndRealtime - moreStats.mTimePeriodStartRealtime, sb);
                                        Slog.i(TAG, sb.toString());
                                    } catch (IOException e2) {
                                        e = e2;
                                        Slog.w(TAG, "Failed building output pipe", e);
                                        this.mWriteLock.unlock();
                                        return null;
                                    }
                                } catch (Throwable th3) {
                                    th = th3;
                                    this.mWriteLock.unlock();
                                    throw th;
                                }
                            } else {
                                curTime2 = curTime;
                                Slog.w(TAG, "Failure reading " + files.get(i3 + 1) + "; " + moreStats.mReadError);
                            }
                            i = i3;
                            curTime = curTime2;
                            z = false;
                        }
                    }
                    current = Parcel.obtain();
                    stats.writeToParcel(current, 0);
                    final byte[] outData = current.marshall();
                    current.recycle();
                    final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
                    Thread thr = new Thread("ProcessStats pipe output") { // from class: com.android.server.am.ProcessStatsService.3
                        @Override // java.lang.Thread, java.lang.Runnable
                        public void run() {
                            FileOutputStream fout = new ParcelFileDescriptor.AutoCloseOutputStream(fds[1]);
                            try {
                                fout.write(outData);
                                fout.close();
                            } catch (IOException e3) {
                                Slog.w(ProcessStatsService.TAG, "Failure writing pipe", e3);
                            }
                        }
                    };
                    thr.start();
                    ParcelFileDescriptor parcelFileDescriptor = fds[0];
                    this.mWriteLock.unlock();
                    return parcelFileDescriptor;
                }
            } catch (IOException e3) {
                e = e3;
                Slog.w(TAG, "Failed building output pipe", e);
                this.mWriteLock.unlock();
                return null;
            } catch (Throwable th4) {
                th = th4;
                this.mWriteLock.unlock();
                throw th;
            }
        }
        final byte[] outData2 = current.marshall();
        current.recycle();
        final ParcelFileDescriptor[] fds2 = ParcelFileDescriptor.createPipe();
        Thread thr2 = new Thread("ProcessStats pipe output") { // from class: com.android.server.am.ProcessStatsService.3
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                FileOutputStream fout = new ParcelFileDescriptor.AutoCloseOutputStream(fds2[1]);
                try {
                    fout.write(outData2);
                    fout.close();
                } catch (IOException e32) {
                    Slog.w(ProcessStatsService.TAG, "Failure writing pipe", e32);
                }
            }
        };
        thr2.start();
        ParcelFileDescriptor parcelFileDescriptor2 = fds2[0];
        this.mWriteLock.unlock();
        return parcelFileDescriptor2;
    }

    public int getCurrentMemoryState() {
        int i;
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                i = this.mLastMemOnlyState;
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        return i;
    }

    private void dumpAggregatedStats(PrintWriter pw, long aggregateHours, long now, String reqPackage, boolean isCompact, boolean dumpDetails, boolean dumpFullDetails, boolean dumpAll, boolean activeOnly) {
        ParcelFileDescriptor pfd = getStatsOverTime((((aggregateHours * 60) * 60) * 1000) - (ProcessStats.COMMIT_PERIOD / 2));
        if (pfd == null) {
            pw.println("Unable to build stats!");
            return;
        }
        ProcessStats stats = new ProcessStats(false);
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        stats.read(stream);
        if (stats.mReadError != null) {
            pw.print("Failure reading: ");
            pw.println(stats.mReadError);
        } else if (isCompact) {
            stats.dumpCheckinLocked(pw, reqPackage);
        } else if (dumpDetails || dumpFullDetails) {
            stats.dumpLocked(pw, reqPackage, now, !dumpFullDetails, dumpAll, activeOnly);
        } else {
            stats.dumpSummaryLocked(pw, reqPackage, now, activeOnly);
        }
    }

    private static void dumpHelp(PrintWriter pw) {
        pw.println("Process stats (procstats) dump options:");
        pw.println("    [--checkin|-c|--csv] [--csv-screen] [--csv-proc] [--csv-mem]");
        pw.println("    [--details] [--full-details] [--current] [--hours N] [--last N]");
        pw.println("    [--max N] --active] [--commit] [--reset] [--clear] [--write] [-h]");
        pw.println("    [--start-testing] [--stop-testing] ");
        pw.println("    [--pretend-screen-on] [--pretend-screen-off] [--stop-pretend-screen]");
        pw.println("    [<package.name>]");
        pw.println("  --checkin: perform a checkin: print and delete old committed states.");
        pw.println("  -c: print only state in checkin format.");
        pw.println("  --csv: output data suitable for putting in a spreadsheet.");
        pw.println("  --csv-screen: on, off.");
        pw.println("  --csv-mem: norm, mod, low, crit.");
        pw.println("  --csv-proc: pers, top, fore, vis, precept, backup,");
        pw.println("    service, home, prev, cached");
        pw.println("  --details: dump per-package details, not just summary.");
        pw.println("  --full-details: dump all timing and active state details.");
        pw.println("  --current: only dump current state.");
        pw.println("  --hours: aggregate over about N last hours.");
        pw.println("  --last: only show the last committed stats at index N (starting at 1).");
        pw.println("  --max: for -a, max num of historical batches to print.");
        pw.println("  --active: only show currently active processes/services.");
        pw.println("  --commit: commit current stats to disk and reset to start new stats.");
        pw.println("  --reset: reset current stats, without committing.");
        pw.println("  --clear: clear all stats; does both --reset and deletes old stats.");
        pw.println("  --write: write current in-memory stats to disk.");
        pw.println("  --read: replace current stats with last-written stats.");
        pw.println("  --start-testing: clear all stats and starting high frequency pss sampling.");
        pw.println("  --stop-testing: stop high frequency pss sampling.");
        pw.println("  --pretend-screen-on: pretend screen is on.");
        pw.println("  --pretend-screen-off: pretend screen is off.");
        pw.println("  --stop-pretend-screen: forget \"pretend screen\" and use the real state.");
        pw.println("  -a: print everything.");
        pw.println("  -h: print this help text.");
        pw.println("  <package.name>: optional name of package to filter output by.");
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!com.android.internal.util.DumpUtils.checkDumpAndUsageStatsPermission(this.mAm.mContext, TAG, pw)) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            if (args.length > 0 && PriorityDump.PROTO_ARG.equals(args[0])) {
                dumpProto(fd);
            } else {
                dumpInner(pw, args);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    private void dumpInner(java.io.PrintWriter r44, java.lang.String[] r45) {
        /*
            Method dump skipped, instructions count: 2230
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ProcessStatsService.dumpInner(java.io.PrintWriter, java.lang.String[]):void");
    }

    private void dumpAggregatedStats(ProtoOutputStream proto, long fieldId, int aggregateHours, long now) {
        ParcelFileDescriptor pfd = getStatsOverTime((((aggregateHours * 60) * 60) * 1000) - (ProcessStats.COMMIT_PERIOD / 2));
        if (pfd == null) {
            return;
        }
        ProcessStats stats = new ProcessStats(false);
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        stats.read(stream);
        if (stats.mReadError != null) {
            return;
        }
        stats.writeToProto(proto, fieldId, now);
    }

    private void dumpProto(FileDescriptor fd) {
        long now;
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                now = SystemClock.uptimeMillis();
                this.mProcessStats.writeToProto(proto, 1146756268033L, now);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        dumpAggregatedStats(proto, 1146756268034L, 3, now);
        dumpAggregatedStats(proto, 1146756268035L, 24, now);
        proto.flush();
    }
}
