package com.android.server;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.persistentdata.IPersistentDataBlockService;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.slice.SliceClientPermissions;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public class PersistentDataBlockService extends SystemService {
    public static final int DIGEST_SIZE_BYTES = 32;
    private static final String FLASH_LOCK_LOCKED = "1";
    private static final String FLASH_LOCK_PROP = "ro.boot.flash.locked";
    private static final String FLASH_LOCK_UNLOCKED = "0";
    private static final int FRP_CREDENTIAL_RESERVED_SIZE = 1000;
    private static final int HEADER_SIZE = 8;
    private static final int MAX_DATA_BLOCK_SIZE = 102400;
    private static final int MAX_FRP_CREDENTIAL_HANDLE_SIZE = 996;
    private static final int MAX_TEST_MODE_DATA_SIZE = 9996;
    private static final String OEM_UNLOCK_PROP = "sys.oem_unlock_allowed";
    private static final int PARTITION_TYPE_MARKER = 428873843;
    private static final String PERSISTENT_DATA_BLOCK_PROP = "ro.frp.pst";
    private static final String TAG = PersistentDataBlockService.class.getSimpleName();
    private static final int TEST_MODE_RESERVED_SIZE = 10000;
    private int mAllowedUid;
    private long mBlockDeviceSize;
    private final Context mContext;
    private final String mDataBlockFile;
    private final CountDownLatch mInitDoneSignal;
    private PersistentDataBlockManagerInternal mInternalService;
    @GuardedBy({"mLock"})
    private boolean mIsWritable;
    private final Object mLock;
    private final IBinder mService;

    private native long nativeGetBlockDeviceSize(String str);

    /* JADX INFO: Access modifiers changed from: private */
    public native int nativeWipe(String str);

    public PersistentDataBlockService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mInitDoneSignal = new CountDownLatch(1);
        this.mAllowedUid = -1;
        this.mIsWritable = true;
        this.mService = new IPersistentDataBlockService.Stub() { // from class: com.android.server.PersistentDataBlockService.1
            public int write(byte[] data) throws RemoteException {
                PersistentDataBlockService.this.enforceUid(Binder.getCallingUid());
                long maxBlockSize = PersistentDataBlockService.this.doGetMaximumDataBlockSize();
                if (data.length > maxBlockSize) {
                    return (int) (-maxBlockSize);
                }
                try {
                    DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(new File(PersistentDataBlockService.this.mDataBlockFile)));
                    ByteBuffer headerAndData = ByteBuffer.allocate(data.length + 8);
                    headerAndData.putInt(PersistentDataBlockService.PARTITION_TYPE_MARKER);
                    headerAndData.putInt(data.length);
                    headerAndData.put(data);
                    synchronized (PersistentDataBlockService.this.mLock) {
                        if (!PersistentDataBlockService.this.mIsWritable) {
                            IoUtils.closeQuietly(outputStream);
                            return -1;
                        }
                        try {
                            byte[] checksum = new byte[32];
                            outputStream.write(checksum, 0, 32);
                            outputStream.write(headerAndData.array());
                            outputStream.flush();
                            IoUtils.closeQuietly(outputStream);
                            if (PersistentDataBlockService.this.computeAndWriteDigestLocked()) {
                                return data.length;
                            }
                            return -1;
                        } catch (IOException e) {
                            Slog.e(PersistentDataBlockService.TAG, "failed writing to the persistent data block", e);
                            IoUtils.closeQuietly(outputStream);
                            return -1;
                        }
                    }
                } catch (FileNotFoundException e2) {
                    Slog.e(PersistentDataBlockService.TAG, "partition not available?", e2);
                    return -1;
                }
            }

            public byte[] read() {
                PersistentDataBlockService.this.enforceUid(Binder.getCallingUid());
                if (PersistentDataBlockService.this.enforceChecksumValidity()) {
                    try {
                        DataInputStream inputStream = new DataInputStream(new FileInputStream(new File(PersistentDataBlockService.this.mDataBlockFile)));
                        try {
                            try {
                                synchronized (PersistentDataBlockService.this.mLock) {
                                    int totalDataSize = PersistentDataBlockService.this.getTotalDataSizeLocked(inputStream);
                                    if (totalDataSize == 0) {
                                        byte[] bArr = new byte[0];
                                        try {
                                            inputStream.close();
                                        } catch (IOException e) {
                                            Slog.e(PersistentDataBlockService.TAG, "failed to close OutputStream");
                                        }
                                        return bArr;
                                    }
                                    byte[] data = new byte[totalDataSize];
                                    int read = inputStream.read(data, 0, totalDataSize);
                                    if (read >= totalDataSize) {
                                        try {
                                            inputStream.close();
                                        } catch (IOException e2) {
                                            Slog.e(PersistentDataBlockService.TAG, "failed to close OutputStream");
                                        }
                                        return data;
                                    }
                                    String str = PersistentDataBlockService.TAG;
                                    Slog.e(str, "failed to read entire data block. bytes read: " + read + SliceClientPermissions.SliceAuthority.DELIMITER + totalDataSize);
                                    try {
                                        inputStream.close();
                                    } catch (IOException e3) {
                                        Slog.e(PersistentDataBlockService.TAG, "failed to close OutputStream");
                                    }
                                    return null;
                                }
                            } catch (Throwable th) {
                                try {
                                    inputStream.close();
                                } catch (IOException e4) {
                                    Slog.e(PersistentDataBlockService.TAG, "failed to close OutputStream");
                                }
                                throw th;
                            }
                        } catch (IOException e5) {
                            Slog.e(PersistentDataBlockService.TAG, "failed to read data", e5);
                            try {
                                inputStream.close();
                            } catch (IOException e6) {
                                Slog.e(PersistentDataBlockService.TAG, "failed to close OutputStream");
                            }
                            return null;
                        }
                    } catch (FileNotFoundException e7) {
                        Slog.e(PersistentDataBlockService.TAG, "partition not available?", e7);
                        return null;
                    }
                }
                return new byte[0];
            }

            public void wipe() {
                PersistentDataBlockService.this.enforceOemUnlockWritePermission();
                synchronized (PersistentDataBlockService.this.mLock) {
                    int ret = PersistentDataBlockService.this.nativeWipe(PersistentDataBlockService.this.mDataBlockFile);
                    if (ret < 0) {
                        Slog.e(PersistentDataBlockService.TAG, "failed to wipe persistent partition");
                    } else {
                        PersistentDataBlockService.this.mIsWritable = false;
                        Slog.i(PersistentDataBlockService.TAG, "persistent partition now wiped and unwritable");
                    }
                }
            }

            public void setOemUnlockEnabled(boolean enabled) throws SecurityException {
                if (!ActivityManager.isUserAMonkey()) {
                    PersistentDataBlockService.this.enforceOemUnlockWritePermission();
                    PersistentDataBlockService.this.enforceIsAdmin();
                    if (enabled) {
                        PersistentDataBlockService.this.enforceUserRestriction("no_oem_unlock");
                        PersistentDataBlockService.this.enforceUserRestriction("no_factory_reset");
                    }
                    synchronized (PersistentDataBlockService.this.mLock) {
                        PersistentDataBlockService.this.doSetOemUnlockEnabledLocked(enabled);
                        PersistentDataBlockService.this.computeAndWriteDigestLocked();
                    }
                }
            }

            public boolean getOemUnlockEnabled() {
                PersistentDataBlockService.this.enforceOemUnlockReadPermission();
                return PersistentDataBlockService.this.doGetOemUnlockEnabled();
            }

            public int getFlashLockState() {
                boolean z;
                PersistentDataBlockService.this.enforceOemUnlockReadPermission();
                String locked = SystemProperties.get(PersistentDataBlockService.FLASH_LOCK_PROP);
                int hashCode = locked.hashCode();
                if (hashCode != 48) {
                    if (hashCode == 49 && locked.equals(PersistentDataBlockService.FLASH_LOCK_LOCKED)) {
                        z = false;
                    }
                    z = true;
                } else {
                    if (locked.equals(PersistentDataBlockService.FLASH_LOCK_UNLOCKED)) {
                        z = true;
                    }
                    z = true;
                }
                if (z) {
                    return !z ? -1 : 0;
                }
                return 1;
            }

            public int getDataBlockSize() {
                int totalDataSizeLocked;
                enforcePersistentDataBlockAccess();
                try {
                    DataInputStream inputStream = new DataInputStream(new FileInputStream(new File(PersistentDataBlockService.this.mDataBlockFile)));
                    try {
                        synchronized (PersistentDataBlockService.this.mLock) {
                            totalDataSizeLocked = PersistentDataBlockService.this.getTotalDataSizeLocked(inputStream);
                        }
                        return totalDataSizeLocked;
                    } catch (IOException e) {
                        Slog.e(PersistentDataBlockService.TAG, "error reading data block size");
                        return 0;
                    } finally {
                        IoUtils.closeQuietly(inputStream);
                    }
                } catch (FileNotFoundException e2) {
                    Slog.e(PersistentDataBlockService.TAG, "partition not available");
                    return 0;
                }
            }

            private void enforcePersistentDataBlockAccess() {
                if (PersistentDataBlockService.this.mContext.checkCallingPermission("android.permission.ACCESS_PDB_STATE") != 0) {
                    PersistentDataBlockService.this.enforceUid(Binder.getCallingUid());
                }
            }

            public long getMaximumDataBlockSize() {
                PersistentDataBlockService.this.enforceUid(Binder.getCallingUid());
                return PersistentDataBlockService.this.doGetMaximumDataBlockSize();
            }

            public boolean hasFrpCredentialHandle() {
                enforcePersistentDataBlockAccess();
                try {
                    return PersistentDataBlockService.this.mInternalService.getFrpCredentialHandle() != null;
                } catch (IllegalStateException e) {
                    Slog.e(PersistentDataBlockService.TAG, "error reading frp handle", e);
                    throw new UnsupportedOperationException("cannot read frp credential");
                }
            }
        };
        this.mInternalService = new PersistentDataBlockManagerInternal() { // from class: com.android.server.PersistentDataBlockService.2
            @Override // com.android.server.PersistentDataBlockManagerInternal
            public void setFrpCredentialHandle(byte[] handle) {
                writeInternal(handle, PersistentDataBlockService.this.getFrpCredentialDataOffset(), PersistentDataBlockService.MAX_FRP_CREDENTIAL_HANDLE_SIZE);
            }

            @Override // com.android.server.PersistentDataBlockManagerInternal
            public byte[] getFrpCredentialHandle() {
                return readInternal(PersistentDataBlockService.this.getFrpCredentialDataOffset(), PersistentDataBlockService.MAX_FRP_CREDENTIAL_HANDLE_SIZE);
            }

            @Override // com.android.server.PersistentDataBlockManagerInternal
            public void setTestHarnessModeData(byte[] data) {
                writeInternal(data, PersistentDataBlockService.this.getTestHarnessModeDataOffset(), PersistentDataBlockService.MAX_TEST_MODE_DATA_SIZE);
            }

            @Override // com.android.server.PersistentDataBlockManagerInternal
            public byte[] getTestHarnessModeData() {
                byte[] data = readInternal(PersistentDataBlockService.this.getTestHarnessModeDataOffset(), PersistentDataBlockService.MAX_TEST_MODE_DATA_SIZE);
                if (data == null) {
                    return new byte[0];
                }
                return data;
            }

            @Override // com.android.server.PersistentDataBlockManagerInternal
            public void clearTestHarnessModeData() {
                int size = Math.min((int) PersistentDataBlockService.MAX_TEST_MODE_DATA_SIZE, getTestHarnessModeData().length) + 4;
                writeDataBuffer(PersistentDataBlockService.this.getTestHarnessModeDataOffset(), ByteBuffer.allocate(size));
            }

            private void writeInternal(byte[] data, long offset, int dataLength) {
                boolean z = true;
                Preconditions.checkArgument(data == null || data.length > 0, "data must be null or non-empty");
                if (data != null && data.length > dataLength) {
                    z = false;
                }
                Preconditions.checkArgument(z, "data must not be longer than " + dataLength);
                ByteBuffer dataBuffer = ByteBuffer.allocate(dataLength + 4);
                dataBuffer.putInt(data != null ? data.length : 0);
                if (data != null) {
                    dataBuffer.put(data);
                }
                dataBuffer.flip();
                writeDataBuffer(offset, dataBuffer);
            }

            private void writeDataBuffer(long offset, ByteBuffer dataBuffer) {
                try {
                    FileOutputStream outputStream = new FileOutputStream(new File(PersistentDataBlockService.this.mDataBlockFile));
                    synchronized (PersistentDataBlockService.this.mLock) {
                        if (!PersistentDataBlockService.this.mIsWritable) {
                            IoUtils.closeQuietly(outputStream);
                            return;
                        }
                        try {
                            FileChannel channel = outputStream.getChannel();
                            channel.position(offset);
                            channel.write(dataBuffer);
                            outputStream.flush();
                            IoUtils.closeQuietly(outputStream);
                            PersistentDataBlockService.this.computeAndWriteDigestLocked();
                        } catch (IOException e) {
                            Slog.e(PersistentDataBlockService.TAG, "unable to access persistent partition", e);
                            IoUtils.closeQuietly(outputStream);
                        }
                    }
                } catch (FileNotFoundException e2) {
                    Slog.e(PersistentDataBlockService.TAG, "partition not available", e2);
                }
            }

            private byte[] readInternal(long offset, int maxLength) {
                if (PersistentDataBlockService.this.enforceChecksumValidity()) {
                    try {
                        DataInputStream inputStream = new DataInputStream(new FileInputStream(new File(PersistentDataBlockService.this.mDataBlockFile)));
                        try {
                            try {
                                synchronized (PersistentDataBlockService.this.mLock) {
                                    inputStream.skip(offset);
                                    int length = inputStream.readInt();
                                    if (length > 0 && length <= maxLength) {
                                        byte[] bytes = new byte[length];
                                        inputStream.readFully(bytes);
                                        return bytes;
                                    }
                                    return null;
                                }
                            } catch (IOException e) {
                                throw new IllegalStateException("persistent partition not readable", e);
                            }
                        } finally {
                            IoUtils.closeQuietly(inputStream);
                        }
                    } catch (FileNotFoundException e2) {
                        throw new IllegalStateException("persistent partition not available");
                    }
                }
                throw new IllegalStateException("invalid checksum");
            }

            @Override // com.android.server.PersistentDataBlockManagerInternal
            public void forceOemUnlockEnabled(boolean enabled) {
                synchronized (PersistentDataBlockService.this.mLock) {
                    PersistentDataBlockService.this.doSetOemUnlockEnabledLocked(enabled);
                    PersistentDataBlockService.this.computeAndWriteDigestLocked();
                }
            }
        };
        this.mContext = context;
        this.mDataBlockFile = SystemProperties.get(PERSISTENT_DATA_BLOCK_PROP);
        this.mBlockDeviceSize = -1L;
    }

    private int getAllowedUid(int userHandle) {
        String allowedPackage = this.mContext.getResources().getString(17039771);
        PackageManager pm = this.mContext.getPackageManager();
        try {
            int allowedUid = pm.getPackageUidAsUser(allowedPackage, DumpState.DUMP_DEXOPT, userHandle);
            return allowedUid;
        } catch (PackageManager.NameNotFoundException e) {
            String str = TAG;
            Slog.e(str, "not able to find package " + allowedPackage, e);
            return -1;
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        SystemServerInitThreadPool systemServerInitThreadPool = SystemServerInitThreadPool.get();
        Runnable runnable = new Runnable() { // from class: com.android.server.-$$Lambda$PersistentDataBlockService$EZl9OYaT2eNL7kfSr2nKUBjxidk
            @Override // java.lang.Runnable
            public final void run() {
                PersistentDataBlockService.this.lambda$onStart$0$PersistentDataBlockService();
            }
        };
        systemServerInitThreadPool.submit(runnable, TAG + ".onStart");
    }

    public /* synthetic */ void lambda$onStart$0$PersistentDataBlockService() {
        this.mAllowedUid = getAllowedUid(0);
        enforceChecksumValidity();
        formatIfOemUnlockEnabled();
        publishBinderService("persistent_data_block", this.mService);
        this.mInitDoneSignal.countDown();
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 500) {
            try {
                if (!this.mInitDoneSignal.await(10L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Service " + TAG + " init timeout");
                }
                LocalServices.addService(PersistentDataBlockManagerInternal.class, this.mInternalService);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Service " + TAG + " init interrupted", e);
            }
        }
        super.onBootPhase(phase);
    }

    private void formatIfOemUnlockEnabled() {
        boolean enabled = doGetOemUnlockEnabled();
        if (enabled) {
            synchronized (this.mLock) {
                formatPartitionLocked(true);
            }
        }
        SystemProperties.set(OEM_UNLOCK_PROP, enabled ? FLASH_LOCK_LOCKED : FLASH_LOCK_UNLOCKED);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceOemUnlockReadPermission() {
        if (this.mContext.checkCallingOrSelfPermission("android.permission.READ_OEM_UNLOCK_STATE") == -1 && this.mContext.checkCallingOrSelfPermission("android.permission.OEM_UNLOCK_STATE") == -1) {
            throw new SecurityException("Can't access OEM unlock state. Requires READ_OEM_UNLOCK_STATE or OEM_UNLOCK_STATE permission.");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceOemUnlockWritePermission() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.OEM_UNLOCK_STATE", "Can't modify OEM unlock state");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceUid(int callingUid) {
        if (callingUid != this.mAllowedUid) {
            throw new SecurityException("uid " + callingUid + " not allowed to access PST");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceIsAdmin() {
        int userId = UserHandle.getCallingUserId();
        boolean isAdmin = UserManager.get(this.mContext).isUserAdmin(userId);
        if (!isAdmin) {
            throw new SecurityException("Only the Admin user is allowed to change OEM unlock state");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceUserRestriction(String userRestriction) {
        if (UserManager.get(this.mContext).hasUserRestriction(userRestriction)) {
            throw new SecurityException("OEM unlock is disallowed by user restriction: " + userRestriction);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getTotalDataSizeLocked(DataInputStream inputStream) throws IOException {
        inputStream.skipBytes(32);
        int blockId = inputStream.readInt();
        if (blockId == PARTITION_TYPE_MARKER) {
            int totalDataSize = inputStream.readInt();
            return totalDataSize;
        }
        return 0;
    }

    private long getBlockDeviceSize() {
        synchronized (this.mLock) {
            if (this.mBlockDeviceSize == -1) {
                this.mBlockDeviceSize = nativeGetBlockDeviceSize(this.mDataBlockFile);
            }
        }
        return this.mBlockDeviceSize;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long getFrpCredentialDataOffset() {
        return (getBlockDeviceSize() - 1) - 1000;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long getTestHarnessModeDataOffset() {
        return getFrpCredentialDataOffset() - JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean enforceChecksumValidity() {
        byte[] storedDigest = new byte[32];
        synchronized (this.mLock) {
            byte[] digest = computeDigestLocked(storedDigest);
            if (digest != null && Arrays.equals(storedDigest, digest)) {
                return true;
            }
            Slog.i(TAG, "Formatting FRP partition...");
            formatPartitionLocked(false);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean computeAndWriteDigestLocked() {
        byte[] digest = computeDigestLocked(null);
        if (digest != null) {
            try {
                DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(new File(this.mDataBlockFile)));
                try {
                    try {
                        outputStream.write(digest, 0, 32);
                        outputStream.flush();
                        IoUtils.closeQuietly(outputStream);
                        return true;
                    } catch (IOException e) {
                        Slog.e(TAG, "failed to write block checksum", e);
                        IoUtils.closeQuietly(outputStream);
                        return false;
                    }
                } catch (Throwable th) {
                    IoUtils.closeQuietly(outputStream);
                    throw th;
                }
            } catch (FileNotFoundException e2) {
                Slog.e(TAG, "partition not available?", e2);
                return false;
            }
        }
        return false;
    }

    /* JADX WARN: Removed duplicated region for block: B:14:0x0038 A[Catch: all -> 0x0045, IOException -> 0x0047, LOOP:0: B:12:0x0030->B:14:0x0038, LOOP_END, Merged into TryCatch #2 {all -> 0x0045, IOException -> 0x0047, blocks: (B:7:0x001e, B:9:0x0021, B:11:0x0028, B:12:0x0030, B:14:0x0038, B:10:0x0025, B:21:0x0048), top: B:32:0x001e }, TRY_LEAVE] */
    /* JADX WARN: Removed duplicated region for block: B:39:0x003c A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private byte[] computeDigestLocked(byte[] r9) {
        /*
            r8 = this;
            r0 = 0
            java.io.DataInputStream r1 = new java.io.DataInputStream     // Catch: java.io.FileNotFoundException -> L64
            java.io.FileInputStream r2 = new java.io.FileInputStream     // Catch: java.io.FileNotFoundException -> L64
            java.io.File r3 = new java.io.File     // Catch: java.io.FileNotFoundException -> L64
            java.lang.String r4 = r8.mDataBlockFile     // Catch: java.io.FileNotFoundException -> L64
            r3.<init>(r4)     // Catch: java.io.FileNotFoundException -> L64
            r2.<init>(r3)     // Catch: java.io.FileNotFoundException -> L64
            r1.<init>(r2)     // Catch: java.io.FileNotFoundException -> L64
            java.lang.String r2 = "SHA-256"
            java.security.MessageDigest r2 = java.security.MessageDigest.getInstance(r2)     // Catch: java.security.NoSuchAlgorithmException -> L58
            r3 = 32
            if (r9 == 0) goto L25
            int r4 = r9.length     // Catch: java.lang.Throwable -> L45 java.io.IOException -> L47
            if (r4 != r3) goto L25
            r1.read(r9)     // Catch: java.lang.Throwable -> L45 java.io.IOException -> L47
            goto L28
        L25:
            r1.skipBytes(r3)     // Catch: java.lang.Throwable -> L45 java.io.IOException -> L47
        L28:
            r4 = 1024(0x400, float:1.435E-42)
            byte[] r4 = new byte[r4]     // Catch: java.lang.Throwable -> L45 java.io.IOException -> L47
            r5 = 0
            r2.update(r4, r5, r3)     // Catch: java.lang.Throwable -> L45 java.io.IOException -> L47
        L30:
            int r3 = r1.read(r4)     // Catch: java.lang.Throwable -> L45 java.io.IOException -> L47
            r6 = r3
            r7 = -1
            if (r3 == r7) goto L3c
            r2.update(r4, r5, r6)     // Catch: java.lang.Throwable -> L45 java.io.IOException -> L47
            goto L30
        L3c:
            libcore.io.IoUtils.closeQuietly(r1)
            byte[] r0 = r2.digest()
            return r0
        L45:
            r0 = move-exception
            goto L54
        L47:
            r3 = move-exception
            java.lang.String r4 = com.android.server.PersistentDataBlockService.TAG     // Catch: java.lang.Throwable -> L45
            java.lang.String r5 = "failed to read partition"
            android.util.Slog.e(r4, r5, r3)     // Catch: java.lang.Throwable -> L45
            libcore.io.IoUtils.closeQuietly(r1)
            return r0
        L54:
            libcore.io.IoUtils.closeQuietly(r1)
            throw r0
        L58:
            r2 = move-exception
            java.lang.String r3 = com.android.server.PersistentDataBlockService.TAG
            java.lang.String r4 = "SHA-256 not supported?"
            android.util.Slog.e(r3, r4, r2)
            libcore.io.IoUtils.closeQuietly(r1)
            return r0
        L64:
            r1 = move-exception
            java.lang.String r2 = com.android.server.PersistentDataBlockService.TAG
            java.lang.String r3 = "partition not available?"
            android.util.Slog.e(r2, r3, r1)
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.PersistentDataBlockService.computeDigestLocked(byte[]):byte[]");
    }

    private void formatPartitionLocked(boolean setOemUnlockEnabled) {
        try {
            DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(new File(this.mDataBlockFile)));
            byte[] data = new byte[32];
            try {
                try {
                    outputStream.write(data, 0, 32);
                    outputStream.writeInt(PARTITION_TYPE_MARKER);
                    outputStream.writeInt(0);
                    outputStream.flush();
                    IoUtils.closeQuietly(outputStream);
                    doSetOemUnlockEnabledLocked(setOemUnlockEnabled);
                    computeAndWriteDigestLocked();
                } catch (IOException e) {
                    Slog.e(TAG, "failed to format block", e);
                    IoUtils.closeQuietly(outputStream);
                }
            } catch (Throwable th) {
                IoUtils.closeQuietly(outputStream);
                throw th;
            }
        } catch (FileNotFoundException e2) {
            Slog.e(TAG, "partition not available?", e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doSetOemUnlockEnabledLocked(boolean enabled) {
        String str = FLASH_LOCK_LOCKED;
        try {
            FileOutputStream outputStream = new FileOutputStream(new File(this.mDataBlockFile));
            try {
                try {
                    FileChannel channel = outputStream.getChannel();
                    channel.position(getBlockDeviceSize() - 1);
                    byte b = 1;
                    ByteBuffer data = ByteBuffer.allocate(1);
                    if (!enabled) {
                        b = 0;
                    }
                    data.put(b);
                    data.flip();
                    channel.write(data);
                    outputStream.flush();
                    if (!enabled) {
                        str = FLASH_LOCK_UNLOCKED;
                    }
                    SystemProperties.set(OEM_UNLOCK_PROP, str);
                    IoUtils.closeQuietly(outputStream);
                } catch (IOException e) {
                    Slog.e(TAG, "unable to access persistent partition", e);
                    if (!enabled) {
                        str = FLASH_LOCK_UNLOCKED;
                    }
                    SystemProperties.set(OEM_UNLOCK_PROP, str);
                    IoUtils.closeQuietly(outputStream);
                }
            } catch (Throwable th) {
                if (!enabled) {
                    str = FLASH_LOCK_UNLOCKED;
                }
                SystemProperties.set(OEM_UNLOCK_PROP, str);
                IoUtils.closeQuietly(outputStream);
                throw th;
            }
        } catch (FileNotFoundException e2) {
            Slog.e(TAG, "partition not available", e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean doGetOemUnlockEnabled() {
        boolean z;
        try {
            DataInputStream inputStream = new DataInputStream(new FileInputStream(new File(this.mDataBlockFile)));
            try {
                synchronized (this.mLock) {
                    inputStream.skip(getBlockDeviceSize() - 1);
                    z = inputStream.readByte() != 0;
                }
                return z;
            } catch (IOException e) {
                Slog.e(TAG, "unable to access persistent partition", e);
                return false;
            } finally {
                IoUtils.closeQuietly(inputStream);
            }
        } catch (FileNotFoundException e2) {
            Slog.e(TAG, "partition not available");
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public long doGetMaximumDataBlockSize() {
        long actualSize = ((((getBlockDeviceSize() - 8) - 32) - JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY) - 1000) - 1;
        if (actualSize <= 102400) {
            return actualSize;
        }
        return 102400L;
    }
}
