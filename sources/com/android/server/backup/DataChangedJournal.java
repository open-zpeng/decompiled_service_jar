package com.android.server.backup;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
/* loaded from: classes.dex */
public class DataChangedJournal {
    private static final int BUFFER_SIZE_BYTES = 8192;
    private static final String FILE_NAME_PREFIX = "journal";
    private final File mFile;

    @FunctionalInterface
    /* loaded from: classes.dex */
    public interface Consumer {
        void accept(String str);
    }

    DataChangedJournal(File file) {
        this.mFile = file;
    }

    public void addPackage(String packageName) throws IOException {
        RandomAccessFile out = new RandomAccessFile(this.mFile, "rws");
        try {
            out.seek(out.length());
            out.writeUTF(packageName);
            $closeResource(null, out);
        } finally {
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

    public void forEach(Consumer consumer) throws IOException {
        BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(this.mFile), 8192);
        try {
            DataInputStream dataInputStream = new DataInputStream(bufferedInputStream);
            while (dataInputStream.available() > 0) {
                String packageName = dataInputStream.readUTF();
                consumer.accept(packageName);
            }
            $closeResource(null, dataInputStream);
            $closeResource(null, bufferedInputStream);
        } finally {
        }
    }

    public boolean delete() {
        return this.mFile.delete();
    }

    public boolean equals(Object object) {
        if (object instanceof DataChangedJournal) {
            DataChangedJournal that = (DataChangedJournal) object;
            try {
                return this.mFile.getCanonicalPath().equals(that.mFile.getCanonicalPath());
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    public String toString() {
        return this.mFile.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static DataChangedJournal newJournal(File journalDirectory) throws IOException {
        return new DataChangedJournal(File.createTempFile(FILE_NAME_PREFIX, null, journalDirectory));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ArrayList<DataChangedJournal> listJournals(File journalDirectory) {
        File[] listFiles;
        ArrayList<DataChangedJournal> journals = new ArrayList<>();
        for (File file : journalDirectory.listFiles()) {
            journals.add(new DataChangedJournal(file));
        }
        return journals;
    }
}
