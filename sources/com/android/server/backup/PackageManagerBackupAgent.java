package com.android.server.backup;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Slog;
import com.android.server.backup.utils.AppBackupUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
/* loaded from: classes.dex */
public class PackageManagerBackupAgent extends BackupAgent {
    private static final String ANCESTRAL_RECORD_KEY = "@ancestral_record@";
    private static final int ANCESTRAL_RECORD_VERSION = 1;
    private static final boolean DEBUG = false;
    private static final String DEFAULT_HOME_KEY = "@home@";
    private static final String GLOBAL_METADATA_KEY = "@meta@";
    private static final String STATE_FILE_HEADER = "=state=";
    private static final int STATE_FILE_VERSION = 2;
    private static final String TAG = "PMBA";
    private static final int UNDEFINED_ANCESTRAL_RECORD_VERSION = -1;
    private List<PackageInfo> mAllPackages;
    private boolean mHasMetadata;
    private PackageManager mPackageManager;
    private ComponentName mRestoredHome;
    private String mRestoredHomeInstaller;
    private ArrayList<byte[]> mRestoredHomeSigHashes;
    private long mRestoredHomeVersion;
    private HashMap<String, Metadata> mRestoredSignatures;
    private ComponentName mStoredHomeComponent;
    private ArrayList<byte[]> mStoredHomeSigHashes;
    private long mStoredHomeVersion;
    private String mStoredIncrementalVersion;
    private int mStoredSdkVersion;
    private HashMap<String, Metadata> mStateVersions = new HashMap<>();
    private final HashSet<String> mExisting = new HashSet<>();

    /* loaded from: classes.dex */
    interface RestoreDataConsumer {
        void consumeRestoreData(BackupDataInput backupDataInput) throws IOException;
    }

    /* loaded from: classes.dex */
    public class Metadata {
        public ArrayList<byte[]> sigHashes;
        public long versionCode;

        Metadata(long version, ArrayList<byte[]> hashes) {
            this.versionCode = version;
            this.sigHashes = hashes;
        }
    }

    public PackageManagerBackupAgent(PackageManager packageMgr, List<PackageInfo> packages) {
        init(packageMgr, packages);
    }

    public PackageManagerBackupAgent(PackageManager packageMgr) {
        init(packageMgr, null);
        evaluateStorablePackages();
    }

    private void init(PackageManager packageMgr, List<PackageInfo> packages) {
        this.mPackageManager = packageMgr;
        this.mAllPackages = packages;
        this.mRestoredSignatures = null;
        this.mHasMetadata = false;
        this.mStoredSdkVersion = Build.VERSION.SDK_INT;
        this.mStoredIncrementalVersion = Build.VERSION.INCREMENTAL;
    }

    public void evaluateStorablePackages() {
        this.mAllPackages = getStorableApplications(this.mPackageManager);
    }

    public static List<PackageInfo> getStorableApplications(PackageManager pm) {
        List<PackageInfo> pkgs = pm.getInstalledPackages(134217728);
        int N = pkgs.size();
        for (int a = N - 1; a >= 0; a--) {
            PackageInfo pkg = pkgs.get(a);
            if (!AppBackupUtils.appIsEligibleForBackup(pkg.applicationInfo, pm)) {
                pkgs.remove(a);
            }
        }
        return pkgs;
    }

    public boolean hasMetadata() {
        return this.mHasMetadata;
    }

    public Metadata getRestoredMetadata(String packageName) {
        if (this.mRestoredSignatures == null) {
            Slog.w(TAG, "getRestoredMetadata() before metadata read!");
            return null;
        }
        return this.mRestoredSignatures.get(packageName);
    }

    public Set<String> getRestoredPackages() {
        if (this.mRestoredSignatures == null) {
            Slog.w(TAG, "getRestoredPackages() before metadata read!");
            return null;
        }
        return this.mRestoredSignatures.keySet();
    }

    /* JADX WARN: Code restructure failed: missing block: B:34:0x00dc, code lost:
        if (r15 == null) goto L22;
     */
    /* JADX WARN: Code restructure failed: missing block: B:35:0x00de, code lost:
        r0.reset();
        r0.writeUTF(r15.flattenToString());
        r0.writeLong(r13);
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x00eb, code lost:
        if (r16 == null) goto L21;
     */
    /* JADX WARN: Code restructure failed: missing block: B:37:0x00ed, code lost:
        r0 = r16;
     */
    /* JADX WARN: Code restructure failed: missing block: B:38:0x00f0, code lost:
        r0 = com.android.server.backup.BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
     */
    /* JADX WARN: Code restructure failed: missing block: B:39:0x00f2, code lost:
        r0.writeUTF(r0);
        writeSignatureHashArray(r0, r6);
        writeEntity(r25, com.android.server.backup.PackageManagerBackupAgent.DEFAULT_HOME_KEY, r0.toByteArray());
     */
    /* JADX WARN: Code restructure failed: missing block: B:40:0x0102, code lost:
        r25.writeEntityHeader(com.android.server.backup.PackageManagerBackupAgent.DEFAULT_HOME_KEY, -1);
     */
    @Override // android.app.backup.BackupAgent
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void onBackup(android.os.ParcelFileDescriptor r24, android.app.backup.BackupDataOutput r25, android.os.ParcelFileDescriptor r26) {
        /*
            Method dump skipped, instructions count: 530
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.PackageManagerBackupAgent.onBackup(android.os.ParcelFileDescriptor, android.app.backup.BackupDataOutput, android.os.ParcelFileDescriptor):void");
    }

    private static void writeEntity(BackupDataOutput data, String key, byte[] bytes) throws IOException {
        data.writeEntityHeader(key, bytes.length);
        data.writeEntityData(bytes, bytes.length);
    }

    @Override // android.app.backup.BackupAgent
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) throws IOException {
        int ancestralRecordVersion = getAncestralRecordVersionValue(data);
        RestoreDataConsumer consumer = getRestoreDataConsumer(ancestralRecordVersion);
        if (consumer == null) {
            Slog.w(TAG, "Ancestral restore set version is unknown to this Android version; not restoring");
        } else {
            consumer.consumeRestoreData(data);
        }
    }

    private int getAncestralRecordVersionValue(BackupDataInput data) throws IOException {
        if (!data.readNextHeader()) {
            return -1;
        }
        String key = data.getKey();
        int dataSize = data.getDataSize();
        if (!ANCESTRAL_RECORD_KEY.equals(key)) {
            return -1;
        }
        byte[] inputBytes = new byte[dataSize];
        data.readEntityData(inputBytes, 0, dataSize);
        ByteArrayInputStream inputBuffer = new ByteArrayInputStream(inputBytes);
        DataInputStream inputBufferStream = new DataInputStream(inputBuffer);
        int ancestralRecordVersionValue = inputBufferStream.readInt();
        return ancestralRecordVersionValue;
    }

    private RestoreDataConsumer getRestoreDataConsumer(int ancestralRecordVersion) {
        if (ancestralRecordVersion != -1) {
            if (ancestralRecordVersion == 1) {
                return new AncestralVersion1RestoreDataConsumer();
            }
            Slog.e(TAG, "Unrecognized ANCESTRAL_RECORD_VERSION: " + ancestralRecordVersion);
            return null;
        }
        return new LegacyRestoreDataConsumer();
    }

    private static void writeSignatureHashArray(DataOutputStream out, ArrayList<byte[]> hashes) throws IOException {
        out.writeInt(hashes.size());
        Iterator<byte[]> it = hashes.iterator();
        while (it.hasNext()) {
            byte[] buffer = it.next();
            out.writeInt(buffer.length);
            out.write(buffer);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static ArrayList<byte[]> readSignatureHashArray(DataInputStream in) {
        try {
            try {
                int num = in.readInt();
                if (num > 20) {
                    Slog.e(TAG, "Suspiciously large sig count in restore data; aborting");
                    throw new IllegalStateException("Bad restore state");
                }
                boolean nonHashFound = false;
                ArrayList<byte[]> sigs = new ArrayList<>(num);
                for (int i = 0; i < num; i++) {
                    int len = in.readInt();
                    byte[] readHash = new byte[len];
                    in.read(readHash);
                    sigs.add(readHash);
                    if (len != 32) {
                        nonHashFound = true;
                    }
                }
                if (nonHashFound) {
                    return BackupUtils.hashSignatureArray(sigs);
                }
                return sigs;
            } catch (EOFException e) {
                Slog.w(TAG, "Read empty signature block");
                return null;
            }
        } catch (IOException e2) {
            Slog.e(TAG, "Unable to read signatures");
            return null;
        }
    }

    private void parseStateFile(ParcelFileDescriptor stateFile) {
        long versionCode;
        this.mExisting.clear();
        this.mStateVersions.clear();
        this.mStoredSdkVersion = 0;
        this.mStoredIncrementalVersion = null;
        this.mStoredHomeComponent = null;
        this.mStoredHomeVersion = 0L;
        this.mStoredHomeSigHashes = null;
        FileInputStream instream = new FileInputStream(stateFile.getFileDescriptor());
        BufferedInputStream inbuffer = new BufferedInputStream(instream);
        DataInputStream in = new DataInputStream(inbuffer);
        boolean ignoreExisting = false;
        try {
            String pkg = in.readUTF();
            if (pkg.equals(STATE_FILE_HEADER)) {
                int stateVersion = in.readInt();
                if (stateVersion > 2) {
                    Slog.w(TAG, "Unsupported state file version " + stateVersion + ", redoing from start");
                    return;
                }
                pkg = in.readUTF();
            } else {
                Slog.i(TAG, "Older version of saved state - rewriting");
                ignoreExisting = true;
            }
            if (pkg.equals(DEFAULT_HOME_KEY)) {
                this.mStoredHomeComponent = ComponentName.unflattenFromString(in.readUTF());
                this.mStoredHomeVersion = in.readLong();
                this.mStoredHomeSigHashes = readSignatureHashArray(in);
                pkg = in.readUTF();
            }
            if (pkg.equals(GLOBAL_METADATA_KEY)) {
                this.mStoredSdkVersion = in.readInt();
                this.mStoredIncrementalVersion = in.readUTF();
                if (!ignoreExisting) {
                    this.mExisting.add(GLOBAL_METADATA_KEY);
                }
                while (true) {
                    String pkg2 = in.readUTF();
                    int versionCodeInt = in.readInt();
                    if (versionCodeInt == Integer.MIN_VALUE) {
                        versionCode = in.readLong();
                    } else {
                        versionCode = versionCodeInt;
                    }
                    if (!ignoreExisting) {
                        this.mExisting.add(pkg2);
                    }
                    this.mStateVersions.put(pkg2, new Metadata(versionCode, null));
                }
            } else {
                Slog.e(TAG, "No global metadata in state file!");
            }
        } catch (EOFException e) {
        } catch (IOException e2) {
            Slog.e(TAG, "Unable to read Package Manager state file: " + e2);
        }
    }

    private ComponentName getPreferredHomeComponent() {
        return this.mPackageManager.getHomeActivities(new ArrayList());
    }

    private void writeStateFile(List<PackageInfo> pkgs, ComponentName preferredHome, long homeVersion, ArrayList<byte[]> homeSigHashes, ParcelFileDescriptor stateFile) {
        FileOutputStream outstream = new FileOutputStream(stateFile.getFileDescriptor());
        BufferedOutputStream outbuf = new BufferedOutputStream(outstream);
        DataOutputStream out = new DataOutputStream(outbuf);
        try {
            out.writeUTF(STATE_FILE_HEADER);
            out.writeInt(2);
            if (preferredHome != null) {
                out.writeUTF(DEFAULT_HOME_KEY);
                out.writeUTF(preferredHome.flattenToString());
                out.writeLong(homeVersion);
                writeSignatureHashArray(out, homeSigHashes);
            }
            out.writeUTF(GLOBAL_METADATA_KEY);
            out.writeInt(Build.VERSION.SDK_INT);
            out.writeUTF(Build.VERSION.INCREMENTAL);
            for (PackageInfo pkg : pkgs) {
                out.writeUTF(pkg.packageName);
                if (pkg.versionCodeMajor != 0) {
                    out.writeInt(Integer.MIN_VALUE);
                    out.writeLong(pkg.getLongVersionCode());
                } else {
                    out.writeInt(pkg.versionCode);
                }
            }
            out.flush();
        } catch (IOException e) {
            Slog.e(TAG, "Unable to write package manager state file!");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class LegacyRestoreDataConsumer implements RestoreDataConsumer {
        private LegacyRestoreDataConsumer() {
        }

        /* JADX WARN: Removed duplicated region for block: B:28:0x00b6 A[SYNTHETIC] */
        /* JADX WARN: Removed duplicated region for block: B:30:0x00db A[SYNTHETIC] */
        @Override // com.android.server.backup.PackageManagerBackupAgent.RestoreDataConsumer
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public void consumeRestoreData(android.app.backup.BackupDataInput r18) throws java.io.IOException {
            /*
                r17 = this;
                r0 = r17
                java.util.ArrayList r1 = new java.util.ArrayList
                r1.<init>()
                java.util.HashMap r2 = new java.util.HashMap
                r2.<init>()
                r3 = -1
            Ld:
                java.lang.String r4 = r18.getKey()
                int r5 = r18.getDataSize()
                byte[] r6 = new byte[r5]
                r7 = 0
                r8 = r18
                r8.readEntityData(r6, r7, r5)
                java.io.ByteArrayInputStream r7 = new java.io.ByteArrayInputStream
                r7.<init>(r6)
                java.io.DataInputStream r9 = new java.io.DataInputStream
                r9.<init>(r7)
                java.lang.String r10 = "@meta@"
                boolean r10 = r4.equals(r10)
                if (r10 == 0) goto L4b
                int r10 = r9.readInt()
                com.android.server.backup.PackageManagerBackupAgent r11 = com.android.server.backup.PackageManagerBackupAgent.this
                com.android.server.backup.PackageManagerBackupAgent.access$202(r11, r10)
                com.android.server.backup.PackageManagerBackupAgent r11 = com.android.server.backup.PackageManagerBackupAgent.this
                java.lang.String r12 = r9.readUTF()
                com.android.server.backup.PackageManagerBackupAgent.access$302(r11, r12)
                com.android.server.backup.PackageManagerBackupAgent r11 = com.android.server.backup.PackageManagerBackupAgent.this
                r12 = 1
                com.android.server.backup.PackageManagerBackupAgent.access$402(r11, r12)
            L48:
                r16 = r1
                goto Laf
            L4b:
                java.lang.String r10 = "@home@"
                boolean r10 = r4.equals(r10)
                if (r10 == 0) goto L7c
                java.lang.String r10 = r9.readUTF()
                com.android.server.backup.PackageManagerBackupAgent r11 = com.android.server.backup.PackageManagerBackupAgent.this
                android.content.ComponentName r12 = android.content.ComponentName.unflattenFromString(r10)
                com.android.server.backup.PackageManagerBackupAgent.access$502(r11, r12)
                com.android.server.backup.PackageManagerBackupAgent r11 = com.android.server.backup.PackageManagerBackupAgent.this
                long r12 = r9.readLong()
                com.android.server.backup.PackageManagerBackupAgent.access$602(r11, r12)
                com.android.server.backup.PackageManagerBackupAgent r11 = com.android.server.backup.PackageManagerBackupAgent.this
                java.lang.String r12 = r9.readUTF()
                com.android.server.backup.PackageManagerBackupAgent.access$702(r11, r12)
                com.android.server.backup.PackageManagerBackupAgent r11 = com.android.server.backup.PackageManagerBackupAgent.this
                java.util.ArrayList r12 = com.android.server.backup.PackageManagerBackupAgent.access$900(r9)
                com.android.server.backup.PackageManagerBackupAgent.access$802(r11, r12)
                goto L48
            L7c:
                int r10 = r9.readInt()
                r11 = -2147483648(0xffffffff80000000, float:-0.0)
                if (r10 != r11) goto L89
                long r11 = r9.readLong()
                goto L8a
            L89:
                long r11 = (long) r10
            L8a:
                java.util.ArrayList r13 = com.android.server.backup.PackageManagerBackupAgent.access$900(r9)
                if (r13 == 0) goto Lbd
                int r14 = r13.size()
                if (r14 != 0) goto L99
                r16 = r1
                goto Lbf
            L99:
                android.content.pm.ApplicationInfo r14 = new android.content.pm.ApplicationInfo
                r14.<init>()
                r14.packageName = r4
                r1.add(r14)
                com.android.server.backup.PackageManagerBackupAgent$Metadata r15 = new com.android.server.backup.PackageManagerBackupAgent$Metadata
                r16 = r1
                com.android.server.backup.PackageManagerBackupAgent r1 = com.android.server.backup.PackageManagerBackupAgent.this
                r15.<init>(r11, r13)
                r2.put(r4, r15)
            Laf:
                boolean r1 = r18.readNextHeader()
                if (r1 != 0) goto Lbc
            Lb6:
                com.android.server.backup.PackageManagerBackupAgent r1 = com.android.server.backup.PackageManagerBackupAgent.this
                com.android.server.backup.PackageManagerBackupAgent.access$1002(r1, r2)
                return
            Lbc:
                goto Ldb
            Lbd:
                r16 = r1
            Lbf:
                java.lang.String r1 = "PMBA"
                java.lang.StringBuilder r14 = new java.lang.StringBuilder
                r14.<init>()
                java.lang.String r15 = "Not restoring package "
                r14.append(r15)
                r14.append(r4)
                java.lang.String r15 = " since it appears to have no signatures."
                r14.append(r15)
                java.lang.String r14 = r14.toString()
                android.util.Slog.w(r1, r14)
            Ldb:
                r1 = r16
                goto Ld
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.PackageManagerBackupAgent.LegacyRestoreDataConsumer.consumeRestoreData(android.app.backup.BackupDataInput):void");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class AncestralVersion1RestoreDataConsumer implements RestoreDataConsumer {
        private AncestralVersion1RestoreDataConsumer() {
        }

        @Override // com.android.server.backup.PackageManagerBackupAgent.RestoreDataConsumer
        public void consumeRestoreData(BackupDataInput data) throws IOException {
            long versionCode;
            List<ApplicationInfo> restoredApps;
            List<ApplicationInfo> restoredApps2 = new ArrayList<>();
            HashMap<String, Metadata> sigMap = new HashMap<>();
            while (data.readNextHeader()) {
                String key = data.getKey();
                int dataSize = data.getDataSize();
                byte[] inputBytes = new byte[dataSize];
                data.readEntityData(inputBytes, 0, dataSize);
                ByteArrayInputStream inputBuffer = new ByteArrayInputStream(inputBytes);
                DataInputStream inputBufferStream = new DataInputStream(inputBuffer);
                if (key.equals(PackageManagerBackupAgent.GLOBAL_METADATA_KEY)) {
                    int storedSdkVersion = inputBufferStream.readInt();
                    PackageManagerBackupAgent.this.mStoredSdkVersion = storedSdkVersion;
                    PackageManagerBackupAgent.this.mStoredIncrementalVersion = inputBufferStream.readUTF();
                    PackageManagerBackupAgent.this.mHasMetadata = true;
                } else if (key.equals(PackageManagerBackupAgent.DEFAULT_HOME_KEY)) {
                    String cn = inputBufferStream.readUTF();
                    PackageManagerBackupAgent.this.mRestoredHome = ComponentName.unflattenFromString(cn);
                    PackageManagerBackupAgent.this.mRestoredHomeVersion = inputBufferStream.readLong();
                    PackageManagerBackupAgent.this.mRestoredHomeInstaller = inputBufferStream.readUTF();
                    PackageManagerBackupAgent.this.mRestoredHomeSigHashes = PackageManagerBackupAgent.readSignatureHashArray(inputBufferStream);
                } else {
                    int versionCodeInt = inputBufferStream.readInt();
                    if (versionCodeInt == Integer.MIN_VALUE) {
                        versionCode = inputBufferStream.readLong();
                    } else {
                        versionCode = versionCodeInt;
                    }
                    ArrayList<byte[]> sigs = PackageManagerBackupAgent.readSignatureHashArray(inputBufferStream);
                    if (sigs == null) {
                        restoredApps = restoredApps2;
                    } else if (sigs.size() == 0) {
                        restoredApps = restoredApps2;
                    } else {
                        ApplicationInfo app = new ApplicationInfo();
                        app.packageName = key;
                        restoredApps2.add(app);
                        restoredApps = restoredApps2;
                        sigMap.put(key, new Metadata(versionCode, sigs));
                        restoredApps2 = restoredApps;
                    }
                    Slog.w(PackageManagerBackupAgent.TAG, "Not restoring package " + key + " since it appears to have no signatures.");
                    restoredApps2 = restoredApps;
                }
                restoredApps = restoredApps2;
                restoredApps2 = restoredApps;
            }
            PackageManagerBackupAgent.this.mRestoredSignatures = sigMap;
        }
    }
}
