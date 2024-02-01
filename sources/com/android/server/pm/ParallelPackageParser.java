package com.android.server.pm;

import android.content.pm.PackageParser;
import android.os.Trace;
import android.util.DisplayMetrics;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ConcurrentUtils;
import java.io.File;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ParallelPackageParser implements AutoCloseable {
    private static final int MAX_THREADS = 4;
    private static final int QUEUE_CAPACITY = 10;
    private final File mCacheDir;
    private volatile String mInterruptedInThread;
    private final DisplayMetrics mMetrics;
    private final boolean mOnlyCore;
    private final PackageParser.Callback mPackageParserCallback;
    private final String[] mSeparateProcesses;
    private final BlockingQueue<ParseResult> mQueue = new ArrayBlockingQueue(10);
    private final ExecutorService mService = ConcurrentUtils.newFixedThreadPool(4, "package-parsing-thread", -2);

    /* JADX INFO: Access modifiers changed from: package-private */
    public ParallelPackageParser(String[] separateProcesses, boolean onlyCoreApps, DisplayMetrics metrics, File cacheDir, PackageParser.Callback callback) {
        this.mSeparateProcesses = separateProcesses;
        this.mOnlyCore = onlyCoreApps;
        this.mMetrics = metrics;
        this.mCacheDir = cacheDir;
        this.mPackageParserCallback = callback;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class ParseResult {
        PackageParser.Package pkg;
        File scanFile;
        Throwable throwable;

        ParseResult() {
        }

        public String toString() {
            return "ParseResult{pkg=" + this.pkg + ", scanFile=" + this.scanFile + ", throwable=" + this.throwable + '}';
        }
    }

    public ParseResult take() {
        try {
            if (this.mInterruptedInThread != null) {
                throw new InterruptedException("Interrupted in " + this.mInterruptedInThread);
            }
            return this.mQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public void submit(final File scanFile, final int parseFlags) {
        this.mService.submit(new Runnable() { // from class: com.android.server.pm.-$$Lambda$ParallelPackageParser$FTtinPrp068lVeI7K6bC1tNE3iM
            @Override // java.lang.Runnable
            public final void run() {
                ParallelPackageParser.lambda$submit$0(ParallelPackageParser.this, scanFile, parseFlags);
            }
        });
    }

    public static /* synthetic */ void lambda$submit$0(ParallelPackageParser parallelPackageParser, File scanFile, int parseFlags) {
        ParseResult pr = new ParseResult();
        Trace.traceBegin(262144L, "parallel parsePackage [" + scanFile + "]");
        try {
            PackageParser pp = new PackageParser();
            pp.setSeparateProcesses(parallelPackageParser.mSeparateProcesses);
            pp.setOnlyCoreApps(parallelPackageParser.mOnlyCore);
            pp.setDisplayMetrics(parallelPackageParser.mMetrics);
            pp.setCacheDir(parallelPackageParser.mCacheDir);
            pp.setCallback(parallelPackageParser.mPackageParserCallback);
            pr.scanFile = scanFile;
            pr.pkg = parallelPackageParser.parsePackage(pp, scanFile, parseFlags);
        } finally {
            try {
                parallelPackageParser.mQueue.put(pr);
            } finally {
            }
        }
        try {
            parallelPackageParser.mQueue.put(pr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            parallelPackageParser.mInterruptedInThread = Thread.currentThread().getName();
        }
    }

    @VisibleForTesting
    protected PackageParser.Package parsePackage(PackageParser packageParser, File scanFile, int parseFlags) throws PackageParser.PackageParserException {
        return packageParser.parsePackage(scanFile, parseFlags, true);
    }

    @Override // java.lang.AutoCloseable
    public void close() {
        List<Runnable> unfinishedTasks = this.mService.shutdownNow();
        if (!unfinishedTasks.isEmpty()) {
            throw new IllegalStateException("Not all tasks finished before calling close: " + unfinishedTasks);
        }
    }
}
