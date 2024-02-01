package com.android.server.backup.utils;

import android.app.backup.IBackupManagerMonitor;
import android.content.pm.Signature;
import android.os.Bundle;
import android.util.Slog;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.UserBackupManagerService;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/* loaded from: classes.dex */
public class TarBackupReader {
    private static final int TAR_HEADER_LENGTH_FILESIZE = 12;
    private static final int TAR_HEADER_LENGTH_MODE = 8;
    private static final int TAR_HEADER_LENGTH_MODTIME = 12;
    private static final int TAR_HEADER_LENGTH_PATH = 100;
    private static final int TAR_HEADER_LENGTH_PATH_PREFIX = 155;
    private static final int TAR_HEADER_LONG_RADIX = 8;
    private static final int TAR_HEADER_OFFSET_FILESIZE = 124;
    private static final int TAR_HEADER_OFFSET_MODE = 100;
    private static final int TAR_HEADER_OFFSET_MODTIME = 136;
    private static final int TAR_HEADER_OFFSET_PATH = 0;
    private static final int TAR_HEADER_OFFSET_PATH_PREFIX = 345;
    private static final int TAR_HEADER_OFFSET_TYPE_CHAR = 156;
    private final BytesReadListener mBytesReadListener;
    private final InputStream mInputStream;
    private IBackupManagerMonitor mMonitor;
    private byte[] mWidgetData = null;

    public TarBackupReader(InputStream inputStream, BytesReadListener bytesReadListener, IBackupManagerMonitor monitor) {
        this.mInputStream = inputStream;
        this.mBytesReadListener = bytesReadListener;
        this.mMonitor = monitor;
    }

    public FileMetadata readTarHeaders() throws IOException {
        byte[] block = new byte[512];
        FileMetadata info = null;
        if (readTarHeader(block)) {
            try {
                info = new FileMetadata();
                info.size = extractRadix(block, TAR_HEADER_OFFSET_FILESIZE, 12, 8);
                info.mtime = extractRadix(block, TAR_HEADER_OFFSET_MODTIME, 12, 8);
                info.mode = extractRadix(block, 100, 8, 8);
                info.path = extractString(block, TAR_HEADER_OFFSET_PATH_PREFIX, TAR_HEADER_LENGTH_PATH_PREFIX);
                String path = extractString(block, 0, 100);
                if (path.length() > 0) {
                    if (info.path.length() > 0) {
                        info.path += '/';
                    }
                    info.path += path;
                }
                int typeChar = block[TAR_HEADER_OFFSET_TYPE_CHAR];
                if (typeChar == 120) {
                    boolean gotHeader = readPaxExtendedHeader(info);
                    if (gotHeader) {
                        gotHeader = readTarHeader(block);
                    }
                    if (!gotHeader) {
                        throw new IOException("Bad or missing pax header");
                    }
                    typeChar = block[TAR_HEADER_OFFSET_TYPE_CHAR];
                }
                if (typeChar == 0) {
                    return null;
                }
                if (typeChar == 48) {
                    info.type = 1;
                } else if (typeChar == 53) {
                    info.type = 2;
                    if (info.size != 0) {
                        Slog.w(BackupManagerService.TAG, "Directory entry with nonzero size in header");
                        info.size = 0L;
                    }
                } else {
                    Slog.e(BackupManagerService.TAG, "Unknown tar entity type: " + typeChar);
                    throw new IOException("Unknown entity type " + typeChar);
                }
                if ("shared/".regionMatches(0, info.path, 0, "shared/".length())) {
                    info.path = info.path.substring("shared/".length());
                    info.packageName = UserBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;
                    info.domain = "shared";
                    Slog.i(BackupManagerService.TAG, "File in shared storage: " + info.path);
                } else if ("apps/".regionMatches(0, info.path, 0, "apps/".length())) {
                    info.path = info.path.substring("apps/".length());
                    int slash = info.path.indexOf(47);
                    if (slash >= 0) {
                        info.packageName = info.path.substring(0, slash);
                        info.path = info.path.substring(slash + 1);
                        if (!info.path.equals(UserBackupManagerService.BACKUP_MANIFEST_FILENAME) && !info.path.equals(UserBackupManagerService.BACKUP_METADATA_FILENAME)) {
                            int slash2 = info.path.indexOf(47);
                            if (slash2 >= 0) {
                                info.domain = info.path.substring(0, slash2);
                                info.path = info.path.substring(slash2 + 1);
                            } else {
                                throw new IOException("Illegal semantic path in non-manifest " + info.path);
                            }
                        }
                    } else {
                        throw new IOException("Illegal semantic path in " + info.path);
                    }
                }
            } catch (IOException e) {
                Slog.e(BackupManagerService.TAG, "Parse error in header: " + e.getMessage());
                throw e;
            }
        }
        return info;
    }

    private static int readExactly(InputStream in, byte[] buffer, int offset, int size) throws IOException {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        int soFar = 0;
        while (soFar < size) {
            int nRead = in.read(buffer, offset + soFar, size - soFar);
            if (nRead <= 0) {
                break;
            }
            soFar += nRead;
        }
        return soFar;
    }

    public Signature[] readAppManifestAndReturnSignatures(FileMetadata info) throws IOException {
        if (info.size > 65536) {
            throw new IOException("Restore manifest too big; corrupt? size=" + info.size);
        }
        byte[] buffer = new byte[(int) info.size];
        if (readExactly(this.mInputStream, buffer, 0, (int) info.size) == info.size) {
            this.mBytesReadListener.onBytesRead(info.size);
            String[] str = new String[1];
            try {
                int offset = extractLine(buffer, 0, str);
                int version = Integer.parseInt(str[0]);
                if (version != 1) {
                    Slog.i(BackupManagerService.TAG, "Unknown restore manifest version " + version + " for package " + info.packageName);
                    Bundle monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_EVENT_PACKAGE_NAME", info.packageName);
                    this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 44, null, 3, BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras, "android.app.backup.extra.LOG_EVENT_PACKAGE_VERSION", (long) version));
                } else {
                    int offset2 = extractLine(buffer, offset, str);
                    String manifestPackage = str[0];
                    if (!manifestPackage.equals(info.packageName)) {
                        Slog.i(BackupManagerService.TAG, "Expected package " + info.packageName + " but restore manifest claims " + manifestPackage);
                        Bundle monitoringExtras2 = BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_EVENT_PACKAGE_NAME", info.packageName);
                        this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 43, null, 3, BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras2, "android.app.backup.extra.LOG_MANIFEST_PACKAGE_NAME", manifestPackage));
                    } else {
                        int offset3 = extractLine(buffer, offset2, str);
                        info.version = Integer.parseInt(str[0]);
                        int offset4 = extractLine(buffer, offset3, str);
                        Integer.parseInt(str[0]);
                        int offset5 = extractLine(buffer, offset4, str);
                        info.installerPackageName = str[0].length() > 0 ? str[0] : null;
                        int offset6 = extractLine(buffer, offset5, str);
                        info.hasApk = str[0].equals("1");
                        int offset7 = extractLine(buffer, offset6, str);
                        int numSigs = Integer.parseInt(str[0]);
                        if (numSigs > 0) {
                            Signature[] sigs = new Signature[numSigs];
                            for (int i = 0; i < numSigs; i++) {
                                offset7 = extractLine(buffer, offset7, str);
                                sigs[i] = new Signature(str[0]);
                            }
                            return sigs;
                        }
                        Slog.i(BackupManagerService.TAG, "Missing signature on backed-up package " + info.packageName);
                        this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 42, null, 3, BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_EVENT_PACKAGE_NAME", info.packageName));
                    }
                }
            } catch (NumberFormatException e) {
                Slog.w(BackupManagerService.TAG, "Corrupt restore manifest for package " + info.packageName);
                this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 46, null, 3, BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_EVENT_PACKAGE_NAME", info.packageName));
            } catch (IllegalArgumentException e2) {
                Slog.w(BackupManagerService.TAG, e2.getMessage());
            }
            return null;
        }
        throw new IOException("Unexpected EOF in manifest");
    }

    /* JADX WARN: Can't wrap try/catch for region: R(6:14|(6:19|20|21|22|(1:26)|27)|29|30|31|(7:33|(1:35)(2:38|(1:40)(1:(1:42)(3:43|44|45)))|36|21|22|(2:24|26)|27)(1:48)) */
    /* JADX WARN: Code restructure failed: missing block: B:34:0x013a, code lost:
        r5 = r5;
        r6 = r6;
     */
    /* JADX WARN: Code restructure failed: missing block: B:43:0x0176, code lost:
        r15 = r5;
     */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Removed duplicated region for block: B:45:0x0179  */
    /* JADX WARN: Removed duplicated region for block: B:46:0x0196  */
    /* JADX WARN: Removed duplicated region for block: B:50:0x01b6  */
    /* JADX WARN: Type inference failed for: r6v0, types: [java.lang.String] */
    /* JADX WARN: Type inference failed for: r6v11, types: [com.android.server.backup.restore.RestorePolicy] */
    /* JADX WARN: Type inference failed for: r6v12 */
    /* JADX WARN: Type inference failed for: r6v13 */
    /* JADX WARN: Type inference failed for: r6v14 */
    /* JADX WARN: Type inference failed for: r6v5 */
    /* JADX WARN: Type inference failed for: r6v8 */
    /* JADX WARN: Type inference failed for: r6v9 */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public com.android.server.backup.restore.RestorePolicy chooseRestorePolicy(android.content.pm.PackageManager r18, boolean r19, com.android.server.backup.FileMetadata r20, android.content.pm.Signature[] r21, android.content.pm.PackageManagerInternal r22, int r23) {
        /*
            Method dump skipped, instructions count: 488
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.utils.TarBackupReader.chooseRestorePolicy(android.content.pm.PackageManager, boolean, com.android.server.backup.FileMetadata, android.content.pm.Signature[], android.content.pm.PackageManagerInternal, int):com.android.server.backup.restore.RestorePolicy");
    }

    public void skipTarPadding(long size) throws IOException {
        long partial = (size + 512) % 512;
        if (partial > 0) {
            int needed = 512 - ((int) partial);
            byte[] buffer = new byte[needed];
            if (readExactly(this.mInputStream, buffer, 0, needed) == needed) {
                this.mBytesReadListener.onBytesRead(needed);
                return;
            }
            throw new IOException("Unexpected EOF in padding");
        }
    }

    public void readMetadata(FileMetadata info) throws IOException {
        if (info.size > 65536) {
            throw new IOException("Metadata too big; corrupt? size=" + info.size);
        }
        byte[] buffer = new byte[(int) info.size];
        if (readExactly(this.mInputStream, buffer, 0, (int) info.size) == info.size) {
            this.mBytesReadListener.onBytesRead(info.size);
            String[] str = new String[1];
            int offset = extractLine(buffer, 0, str);
            int version = Integer.parseInt(str[0]);
            if (version == 1) {
                int offset2 = extractLine(buffer, offset, str);
                String pkg = str[0];
                if (info.packageName.equals(pkg)) {
                    ByteArrayInputStream bin = new ByteArrayInputStream(buffer, offset2, buffer.length - offset2);
                    DataInputStream in = new DataInputStream(bin);
                    while (bin.available() > 0) {
                        int token = in.readInt();
                        int size = in.readInt();
                        if (size > 65536) {
                            throw new IOException("Datum " + Integer.toHexString(token) + " too big; corrupt? size=" + info.size);
                        } else if (token == 33549569) {
                            this.mWidgetData = new byte[size];
                            in.read(this.mWidgetData);
                        } else {
                            Slog.i(BackupManagerService.TAG, "Ignoring metadata blob " + Integer.toHexString(token) + " for " + info.packageName);
                            in.skipBytes(size);
                        }
                    }
                    return;
                }
                Slog.w(BackupManagerService.TAG, "Metadata mismatch: package " + info.packageName + " but widget data for " + pkg);
                Bundle monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_EVENT_PACKAGE_NAME", info.packageName);
                this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 47, null, 3, BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras, "android.app.backup.extra.LOG_WIDGET_PACKAGE_NAME", pkg));
                return;
            }
            Slog.w(BackupManagerService.TAG, "Unsupported metadata version " + version);
            Bundle monitoringExtras2 = BackupManagerMonitorUtils.putMonitoringExtra((Bundle) null, "android.app.backup.extra.LOG_EVENT_PACKAGE_NAME", info.packageName);
            this.mMonitor = BackupManagerMonitorUtils.monitorEvent(this.mMonitor, 48, null, 3, BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras2, "android.app.backup.extra.LOG_EVENT_PACKAGE_VERSION", (long) version));
            return;
        }
        throw new IOException("Unexpected EOF in widget data");
    }

    private static int extractLine(byte[] buffer, int offset, String[] outStr) throws IOException {
        int end = buffer.length;
        if (offset >= end) {
            throw new IOException("Incomplete data");
        }
        int pos = offset;
        while (pos < end) {
            byte c = buffer[pos];
            if (c == 10) {
                break;
            }
            pos++;
        }
        outStr[0] = new String(buffer, offset, pos - offset);
        return pos + 1;
    }

    private boolean readTarHeader(byte[] block) throws IOException {
        int got = readExactly(this.mInputStream, block, 0, 512);
        if (got == 0) {
            return false;
        }
        if (got < 512) {
            throw new IOException("Unable to read full block header");
        }
        this.mBytesReadListener.onBytesRead(512L);
        return true;
    }

    private boolean readPaxExtendedHeader(FileMetadata info) throws IOException {
        if (info.size > 32768) {
            Slog.w(BackupManagerService.TAG, "Suspiciously large pax header size " + info.size + " - aborting");
            throw new IOException("Sanity failure: pax header size " + info.size);
        }
        int numBlocks = (int) ((info.size + 511) >> 9);
        byte[] data = new byte[numBlocks * 512];
        if (readExactly(this.mInputStream, data, 0, data.length) < data.length) {
            throw new IOException("Unable to read full pax header");
        }
        this.mBytesReadListener.onBytesRead(data.length);
        int contentSize = (int) info.size;
        int offset = 0;
        while (true) {
            int eol = offset + 1;
            while (eol < contentSize && data[eol] != 32) {
                eol++;
            }
            if (eol >= contentSize) {
                throw new IOException("Invalid pax data");
            }
            int linelen = (int) extractRadix(data, offset, eol - offset, 10);
            int key = eol + 1;
            int eol2 = (offset + linelen) - 1;
            int value = key + 1;
            while (data[value] != 61 && value <= eol2) {
                value++;
            }
            if (value > eol2) {
                throw new IOException("Invalid pax declaration");
            }
            String keyStr = new String(data, key, value - key, "UTF-8");
            String valStr = new String(data, value + 1, (eol2 - value) - 1, "UTF-8");
            if ("path".equals(keyStr)) {
                info.path = valStr;
            } else if ("size".equals(keyStr)) {
                info.size = Long.parseLong(valStr);
            } else {
                Slog.i(BackupManagerService.TAG, "Unhandled pax key: " + key);
            }
            offset += linelen;
            if (offset >= contentSize) {
                return true;
            }
        }
    }

    private static long extractRadix(byte[] data, int offset, int maxChars, int radix) throws IOException {
        long value = 0;
        int end = offset + maxChars;
        for (int i = offset; i < end; i++) {
            byte b = data[i];
            if (b == 0 || b == 32) {
                break;
            } else if (b < 48 || b > (radix + 48) - 1) {
                throw new IOException("Invalid number in header: '" + ((char) b) + "' for radix " + radix);
            } else {
                value = (radix * value) + (b - 48);
            }
        }
        return value;
    }

    private static String extractString(byte[] data, int offset, int maxChars) throws IOException {
        int end = offset + maxChars;
        int eos = offset;
        while (eos < end && data[eos] != 0) {
            eos++;
        }
        return new String(data, offset, eos - offset, "US-ASCII");
    }

    private static void hexLog(byte[] block) {
        int offset = 0;
        int todo = block.length;
        StringBuilder buf = new StringBuilder(64);
        while (todo > 0) {
            buf.append(String.format("%04x   ", Integer.valueOf(offset)));
            int numThisLine = todo <= 16 ? todo : 16;
            for (int i = 0; i < numThisLine; i++) {
                buf.append(String.format("%02x ", Byte.valueOf(block[offset + i])));
            }
            Slog.i("hexdump", buf.toString());
            buf.setLength(0);
            todo -= numThisLine;
            offset += numThisLine;
        }
    }

    public IBackupManagerMonitor getMonitor() {
        return this.mMonitor;
    }

    public byte[] getWidgetData() {
        return this.mWidgetData;
    }
}
