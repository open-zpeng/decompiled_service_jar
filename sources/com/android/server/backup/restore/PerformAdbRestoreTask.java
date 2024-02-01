package com.android.server.backup.restore;

import android.app.IBackupAgent;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.BackupPasswordManager;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
import com.android.server.backup.utils.PasswordUtils;
import com.android.server.pm.PackageManagerService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.InflaterInputStream;
/* loaded from: classes.dex */
public class PerformAdbRestoreTask implements Runnable {
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private long mAppVersion;
    private final BackupManagerService mBackupManagerService;
    private long mBytes;
    private final String mCurrentPassword;
    private final String mDecryptPassword;
    private final ParcelFileDescriptor mInputFile;
    private final AtomicBoolean mLatchObject;
    private FullBackupObbConnection mObbConnection;
    private IFullBackupRestoreObserver mObserver;
    private final PackageManagerBackupAgent mPackageManagerBackupAgent;
    private final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();
    private ParcelFileDescriptor[] mPipes = null;
    private byte[] mWidgetData = null;
    private final HashMap<String, RestorePolicy> mPackagePolicies = new HashMap<>();
    private final HashMap<String, String> mPackageInstallers = new HashMap<>();
    private final HashMap<String, Signature[]> mManifestSignatures = new HashMap<>();
    private final HashSet<String> mClearedPackages = new HashSet<>();
    private IBackupAgent mAgent = null;
    private String mAgentPackage = null;
    private ApplicationInfo mTargetApp = null;

    static /* synthetic */ long access$014(PerformAdbRestoreTask x0, long x1) {
        long j = x0.mBytes + x1;
        x0.mBytes = j;
        return j;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class RestoreFinishedRunnable implements Runnable {
        private final IBackupAgent mAgent;
        private final BackupManagerService mBackupManagerService;
        private final int mToken;

        RestoreFinishedRunnable(IBackupAgent agent, int token, BackupManagerService backupManagerService) {
            this.mAgent = agent;
            this.mToken = token;
            this.mBackupManagerService = backupManagerService;
        }

        @Override // java.lang.Runnable
        public void run() {
            try {
                this.mAgent.doRestoreFinished(this.mToken, this.mBackupManagerService.getBackupManagerBinder());
            } catch (RemoteException e) {
            }
        }
    }

    public PerformAdbRestoreTask(BackupManagerService backupManagerService, ParcelFileDescriptor fd, String curPassword, String decryptPassword, IFullBackupRestoreObserver observer, AtomicBoolean latch) {
        this.mObbConnection = null;
        this.mBackupManagerService = backupManagerService;
        this.mInputFile = fd;
        this.mCurrentPassword = curPassword;
        this.mDecryptPassword = decryptPassword;
        this.mObserver = observer;
        this.mLatchObject = latch;
        this.mPackageManagerBackupAgent = backupManagerService.makeMetadataAgent();
        this.mObbConnection = new FullBackupObbConnection(backupManagerService);
        this.mAgentTimeoutParameters = (BackupAgentTimeoutParameters) Preconditions.checkNotNull(backupManagerService.getAgentTimeoutParameters(), "Timeout parameters cannot be null");
        this.mClearedPackages.add(PackageManagerService.PLATFORM_PACKAGE_NAME);
        this.mClearedPackages.add(BackupManagerService.SETTINGS_PACKAGE);
    }

    /* JADX WARN: Removed duplicated region for block: B:108:0x01ae A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:112:0x0160 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    @Override // java.lang.Runnable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void run() {
        /*
            Method dump skipped, instructions count: 474
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.restore.PerformAdbRestoreTask.run():void");
    }

    private static void readFullyOrThrow(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int bytesRead = in.read(buffer, offset, buffer.length - offset);
            if (bytesRead <= 0) {
                throw new IOException("Couldn't fully read data");
            }
            offset += bytesRead;
        }
    }

    @VisibleForTesting
    public static InputStream parseBackupFileHeaderAndReturnTarStream(InputStream rawInputStream, String decryptPassword) throws IOException {
        boolean compressed = false;
        InputStream preCompressStream = rawInputStream;
        boolean okay = false;
        int headerLen = BackupManagerService.BACKUP_FILE_HEADER_MAGIC.length();
        byte[] streamHeader = new byte[headerLen];
        readFullyOrThrow(rawInputStream, streamHeader);
        byte[] magicBytes = BackupManagerService.BACKUP_FILE_HEADER_MAGIC.getBytes("UTF-8");
        if (Arrays.equals(magicBytes, streamHeader)) {
            String s = readHeaderLine(rawInputStream);
            int archiveVersion = Integer.parseInt(s);
            if (archiveVersion <= 5) {
                boolean pbkdf2Fallback = archiveVersion == 1;
                compressed = Integer.parseInt(readHeaderLine(rawInputStream)) != 0;
                String s2 = readHeaderLine(rawInputStream);
                if (s2.equals("none")) {
                    okay = true;
                } else if (decryptPassword != null && decryptPassword.length() > 0) {
                    preCompressStream = decodeAesHeaderAndInitialize(decryptPassword, s2, pbkdf2Fallback, rawInputStream);
                    if (preCompressStream != null) {
                        okay = true;
                    }
                } else {
                    Slog.w(BackupManagerService.TAG, "Archive is encrypted but no password given");
                }
            } else {
                Slog.w(BackupManagerService.TAG, "Wrong header version: " + s);
            }
        } else {
            Slog.w(BackupManagerService.TAG, "Didn't read the right header magic");
        }
        if (okay) {
            return compressed ? new InflaterInputStream(preCompressStream) : preCompressStream;
        }
        Slog.w(BackupManagerService.TAG, "Invalid restore data; aborting.");
        return null;
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        StringBuilder buffer = new StringBuilder(80);
        while (true) {
            int c = in.read();
            if (c < 0 || c == 10) {
                break;
            }
            buffer.append((char) c);
        }
        return buffer.toString();
    }

    /* JADX WARN: Removed duplicated region for block: B:57:0x00d4  */
    /* JADX WARN: Removed duplicated region for block: B:62:0x00e7  */
    /* JADX WARN: Removed duplicated region for block: B:67:0x00f9  */
    /* JADX WARN: Removed duplicated region for block: B:72:0x010b  */
    /* JADX WARN: Removed duplicated region for block: B:77:0x011d  */
    /* JADX WARN: Removed duplicated region for block: B:82:0x012f  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static java.io.InputStream attemptMasterKeyDecryption(java.lang.String r20, java.lang.String r21, byte[] r22, byte[] r23, int r24, java.lang.String r25, java.lang.String r26, java.io.InputStream r27, boolean r28) {
        /*
            Method dump skipped, instructions count: 313
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.restore.PerformAdbRestoreTask.attemptMasterKeyDecryption(java.lang.String, java.lang.String, byte[], byte[], int, java.lang.String, java.lang.String, java.io.InputStream, boolean):java.io.InputStream");
    }

    private static InputStream decodeAesHeaderAndInitialize(String decryptPassword, String encryptionName, boolean pbkdf2Fallback, InputStream rawInStream) {
        InputStream result = null;
        try {
            if (encryptionName.equals(PasswordUtils.ENCRYPTION_ALGORITHM_NAME)) {
                String userSaltHex = readHeaderLine(rawInStream);
                byte[] userSalt = PasswordUtils.hexToByteArray(userSaltHex);
                String ckSaltHex = readHeaderLine(rawInStream);
                byte[] ckSalt = PasswordUtils.hexToByteArray(ckSaltHex);
                int rounds = Integer.parseInt(readHeaderLine(rawInStream));
                String userIvHex = readHeaderLine(rawInStream);
                String masterKeyBlobHex = readHeaderLine(rawInStream);
                result = attemptMasterKeyDecryption(decryptPassword, BackupPasswordManager.PBKDF_CURRENT, userSalt, ckSalt, rounds, userIvHex, masterKeyBlobHex, rawInStream, false);
                if (result == null && pbkdf2Fallback) {
                    result = attemptMasterKeyDecryption(decryptPassword, BackupPasswordManager.PBKDF_FALLBACK, userSalt, ckSalt, rounds, userIvHex, masterKeyBlobHex, rawInStream, true);
                }
            } else {
                Slog.w(BackupManagerService.TAG, "Unsupported encryption method: " + encryptionName);
            }
        } catch (IOException e) {
            Slog.w(BackupManagerService.TAG, "Can't read input header");
        } catch (NumberFormatException e2) {
            Slog.w(BackupManagerService.TAG, "Can't parse restore data header");
        }
        return result;
    }

    /* JADX WARN: Removed duplicated region for block: B:117:0x03e6 A[Catch: IOException -> 0x04ac, TRY_LEAVE, TryCatch #10 {IOException -> 0x04ac, blocks: (B:117:0x03e6, B:114:0x03d9), top: B:214:0x03d9 }] */
    /* JADX WARN: Removed duplicated region for block: B:154:0x0469  */
    /* JADX WARN: Removed duplicated region for block: B:156:0x0473 A[Catch: IOException -> 0x04f6, TryCatch #1 {IOException -> 0x04f6, blocks: (B:153:0x045f, B:156:0x0473, B:162:0x04b9, B:165:0x04cc, B:167:0x04d2), top: B:200:0x045f }] */
    /* JADX WARN: Removed duplicated region for block: B:162:0x04b9 A[Catch: IOException -> 0x04f6, TryCatch #1 {IOException -> 0x04f6, blocks: (B:153:0x045f, B:156:0x0473, B:162:0x04b9, B:165:0x04cc, B:167:0x04d2), top: B:200:0x045f }] */
    /* JADX WARN: Removed duplicated region for block: B:197:0x0521 A[RETURN, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:198:0x0524 A[ORIG_RETURN, RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    boolean restoreOneFile(java.io.InputStream r53, boolean r54, byte[] r55, android.content.pm.PackageInfo r56, boolean r57, int r58, android.app.backup.IBackupManagerMonitor r59) {
        /*
            Method dump skipped, instructions count: 1330
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.restore.PerformAdbRestoreTask.restoreOneFile(java.io.InputStream, boolean, byte[], android.content.pm.PackageInfo, boolean, int, android.app.backup.IBackupManagerMonitor):boolean");
    }

    private static boolean isCanonicalFilePath(String path) {
        if (path.contains("..") || path.contains("//")) {
            return false;
        }
        return true;
    }

    private void setUpPipes() throws IOException {
        this.mPipes = ParcelFileDescriptor.createPipe();
    }

    private void tearDownPipes() {
        if (this.mPipes != null) {
            try {
                this.mPipes[0].close();
                this.mPipes[0] = null;
                this.mPipes[1].close();
                this.mPipes[1] = null;
            } catch (IOException e) {
                Slog.w(BackupManagerService.TAG, "Couldn't close agent pipes", e);
            }
            this.mPipes = null;
        }
    }

    private void tearDownAgent(ApplicationInfo app, boolean doRestoreFinished) {
        if (this.mAgent != null) {
            if (doRestoreFinished) {
                try {
                    int token = this.mBackupManagerService.generateRandomIntegerToken();
                    long fullBackupAgentTimeoutMillis = this.mAgentTimeoutParameters.getFullBackupAgentTimeoutMillis();
                    AdbRestoreFinishedLatch latch = new AdbRestoreFinishedLatch(this.mBackupManagerService, token);
                    this.mBackupManagerService.prepareOperationTimeout(token, fullBackupAgentTimeoutMillis, latch, 1);
                    if (this.mTargetApp.processName.equals("system")) {
                        Runnable runner = new RestoreFinishedRunnable(this.mAgent, token, this.mBackupManagerService);
                        new Thread(runner, "restore-sys-finished-runner").start();
                    } else {
                        this.mAgent.doRestoreFinished(token, this.mBackupManagerService.getBackupManagerBinder());
                    }
                    latch.await();
                } catch (RemoteException e) {
                    Slog.d(BackupManagerService.TAG, "Lost app trying to shut down");
                }
            }
            this.mBackupManagerService.tearDownAgentAndKill(app);
            this.mAgent = null;
        }
    }
}
