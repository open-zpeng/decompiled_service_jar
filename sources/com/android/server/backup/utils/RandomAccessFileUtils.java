package com.android.server.backup.utils;

import android.util.Slog;
import com.android.server.backup.BackupManagerService;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/* loaded from: classes.dex */
public final class RandomAccessFileUtils {
    private static RandomAccessFile getRandomAccessFile(File file) throws FileNotFoundException {
        return new RandomAccessFile(file, "rwd");
    }

    public static void writeBoolean(File file, boolean b) {
        try {
            RandomAccessFile af = getRandomAccessFile(file);
            af.writeBoolean(b);
            $closeResource(null, af);
        } catch (IOException e) {
            Slog.w(BackupManagerService.TAG, "Error writing file:" + file.getAbsolutePath(), e);
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

    public static boolean readBoolean(File file, boolean def) {
        try {
            RandomAccessFile af = getRandomAccessFile(file);
            boolean readBoolean = af.readBoolean();
            $closeResource(null, af);
            return readBoolean;
        } catch (IOException e) {
            Slog.w(BackupManagerService.TAG, "Error reading file:" + file.getAbsolutePath(), e);
            return def;
        }
    }
}
