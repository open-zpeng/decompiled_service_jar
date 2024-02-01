package com.android.server.backup;

import android.util.Slog;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/* loaded from: classes.dex */
final class UserBackupManagerFilePersistedSettings {
    private static final String BACKUP_ENABLE_FILE = "backup_enabled";

    UserBackupManagerFilePersistedSettings() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean readBackupEnableState(int userId) {
        return readBackupEnableState(UserBackupManagerFiles.getBaseStateDir(userId));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void writeBackupEnableState(int userId, boolean enable) {
        writeBackupEnableState(UserBackupManagerFiles.getBaseStateDir(userId), enable);
    }

    private static boolean readBackupEnableState(File baseDir) {
        File enableFile = new File(baseDir, BACKUP_ENABLE_FILE);
        if (enableFile.exists()) {
            try {
                FileInputStream fin = new FileInputStream(enableFile);
                int state = fin.read();
                boolean z = state != 0;
                $closeResource(null, fin);
                return z;
            } catch (IOException e) {
                Slog.e(BackupManagerService.TAG, "Cannot read enable state; assuming disabled");
            }
        } else {
            Slog.i(BackupManagerService.TAG, "isBackupEnabled() => false due to absent settings file");
        }
        return false;
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

    private static void writeBackupEnableState(File baseDir, boolean enable) {
        File enableFile = new File(baseDir, BACKUP_ENABLE_FILE);
        File stage = new File(baseDir, "backup_enabled-stage");
        try {
            FileOutputStream fout = new FileOutputStream(stage);
            fout.write(enable ? 1 : 0);
            fout.close();
            stage.renameTo(enableFile);
            $closeResource(null, fout);
        } catch (IOException | RuntimeException e) {
            Slog.e(BackupManagerService.TAG, "Unable to record backup enable state; reverting to disabled: " + e.getMessage());
            enableFile.delete();
            stage.delete();
        }
    }
}
