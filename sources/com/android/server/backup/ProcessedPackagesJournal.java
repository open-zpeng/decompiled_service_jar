package com.android.server.backup;

import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/* loaded from: classes.dex */
final class ProcessedPackagesJournal {
    private static final boolean DEBUG = true;
    private static final String JOURNAL_FILE_NAME = "processed";
    private static final String TAG = "ProcessedPackagesJournal";
    @GuardedBy({"mProcessedPackages"})
    private final Set<String> mProcessedPackages = new HashSet();
    private final File mStateDirectory;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProcessedPackagesJournal(File stateDirectory) {
        this.mStateDirectory = stateDirectory;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void init() {
        synchronized (this.mProcessedPackages) {
            loadFromDisk();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasBeenProcessed(String packageName) {
        boolean contains;
        synchronized (this.mProcessedPackages) {
            contains = this.mProcessedPackages.contains(packageName);
        }
        return contains;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addPackage(String packageName) {
        synchronized (this.mProcessedPackages) {
            if (this.mProcessedPackages.add(packageName)) {
                File journalFile = new File(this.mStateDirectory, JOURNAL_FILE_NAME);
                try {
                    RandomAccessFile out = new RandomAccessFile(journalFile, "rws");
                    try {
                        out.seek(out.length());
                        out.writeUTF(packageName);
                        $closeResource(null, out);
                    } finally {
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Can't log backup of " + packageName + " to " + journalFile);
                }
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

    /* JADX INFO: Access modifiers changed from: package-private */
    public Set<String> getPackagesCopy() {
        HashSet hashSet;
        synchronized (this.mProcessedPackages) {
            hashSet = new HashSet(this.mProcessedPackages);
        }
        return hashSet;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reset() {
        synchronized (this.mProcessedPackages) {
            this.mProcessedPackages.clear();
            File journalFile = new File(this.mStateDirectory, JOURNAL_FILE_NAME);
            journalFile.delete();
        }
    }

    private void loadFromDisk() {
        File journalFile = new File(this.mStateDirectory, JOURNAL_FILE_NAME);
        if (!journalFile.exists()) {
            return;
        }
        try {
            DataInputStream oldJournal = new DataInputStream(new BufferedInputStream(new FileInputStream(journalFile)));
            while (true) {
                String packageName = oldJournal.readUTF();
                Slog.v(TAG, "   + " + packageName);
                this.mProcessedPackages.add(packageName);
            }
        } catch (EOFException e) {
        } catch (IOException e2) {
            Slog.e(TAG, "Error reading processed packages journal", e2);
        }
    }
}
