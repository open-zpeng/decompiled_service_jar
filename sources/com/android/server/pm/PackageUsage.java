package com.android.server.pm;

import android.content.pm.PackageParser;
import android.os.FileUtils;
import android.util.AtomicFile;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
class PackageUsage extends AbstractStatsBase<Map<String, PackageParser.Package>> {
    private static final String USAGE_FILE_MAGIC = "PACKAGE_USAGE__VERSION_";
    private static final String USAGE_FILE_MAGIC_VERSION_1 = "PACKAGE_USAGE__VERSION_1";
    private boolean mIsHistoricalPackageUsageAvailable;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageUsage() {
        super("package-usage.list", "PackageUsage_DiskWriter", true);
        this.mIsHistoricalPackageUsageAvailable = true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isHistoricalPackageUsageAvailable() {
        return this.mIsHistoricalPackageUsageAvailable;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.pm.AbstractStatsBase
    public void writeInternal(Map<String, PackageParser.Package> packages) {
        long[] jArr;
        AtomicFile file = getFile();
        FileOutputStream f = null;
        try {
            f = file.startWrite();
            BufferedOutputStream out = new BufferedOutputStream(f);
            FileUtils.setPermissions(file.getBaseFile().getPath(), 416, 1000, 1032);
            StringBuilder sb = new StringBuilder();
            sb.append(USAGE_FILE_MAGIC_VERSION_1);
            sb.append('\n');
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            for (PackageParser.Package pkg : packages.values()) {
                if (pkg.getLatestPackageUseTimeInMills() != 0) {
                    sb.setLength(0);
                    sb.append(pkg.packageName);
                    for (long usageTimeInMillis : pkg.mLastPackageUsageTimeInMills) {
                        sb.append(' ');
                        sb.append(usageTimeInMillis);
                    }
                    sb.append('\n');
                    out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
                }
            }
            out.flush();
            file.finishWrite(f);
        } catch (IOException e) {
            if (f != null) {
                file.failWrite(f);
            }
            Log.e("PackageManager", "Failed to write package usage times", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.pm.AbstractStatsBase
    public void readInternal(Map<String, PackageParser.Package> packages) {
        AtomicFile file = getFile();
        BufferedInputStream in = null;
        try {
            try {
                in = new BufferedInputStream(file.openRead());
                StringBuffer sb = new StringBuffer();
                String firstLine = readLine(in, sb);
                if (firstLine != null) {
                    if (USAGE_FILE_MAGIC_VERSION_1.equals(firstLine)) {
                        readVersion1LP(packages, in, sb);
                    } else {
                        readVersion0LP(packages, in, sb, firstLine);
                    }
                }
            } catch (FileNotFoundException e) {
                this.mIsHistoricalPackageUsageAvailable = false;
            } catch (IOException e2) {
                Log.w("PackageManager", "Failed to read package usage times", e2);
            }
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void readVersion0LP(Map<String, PackageParser.Package> packages, InputStream in, StringBuffer sb, String firstLine) throws IOException {
        String line = firstLine;
        while (line != null) {
            String[] tokens = line.split(" ");
            if (tokens.length != 2) {
                throw new IOException("Failed to parse " + line + " as package-timestamp pair.");
            }
            String packageName = tokens[0];
            PackageParser.Package pkg = packages.get(packageName);
            if (pkg != null) {
                long timestamp = parseAsLong(tokens[1]);
                for (int reason = 0; reason < 8; reason++) {
                    pkg.mLastPackageUsageTimeInMills[reason] = timestamp;
                }
            }
            line = readLine(in, sb);
        }
    }

    private void readVersion1LP(Map<String, PackageParser.Package> packages, InputStream in, StringBuffer sb) throws IOException {
        while (true) {
            String line = readLine(in, sb);
            if (line != null) {
                String[] tokens = line.split(" ");
                if (tokens.length != 9) {
                    throw new IOException("Failed to parse " + line + " as a timestamp array.");
                }
                String packageName = tokens[0];
                PackageParser.Package pkg = packages.get(packageName);
                if (pkg != null) {
                    for (int reason = 0; reason < 8; reason++) {
                        pkg.mLastPackageUsageTimeInMills[reason] = parseAsLong(tokens[reason + 1]);
                    }
                }
            } else {
                return;
            }
        }
    }

    private long parseAsLong(String token) throws IOException {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new IOException("Failed to parse " + token + " as a long.", e);
        }
    }

    private String readLine(InputStream in, StringBuffer sb) throws IOException {
        return readToken(in, sb, '\n');
    }

    private String readToken(InputStream in, StringBuffer sb, char endOfToken) throws IOException {
        sb.setLength(0);
        while (true) {
            int ch = in.read();
            if (ch == -1) {
                if (sb.length() == 0) {
                    return null;
                }
                throw new IOException("Unexpected EOF");
            } else if (ch == endOfToken) {
                return sb.toString();
            } else {
                sb.append((char) ch);
            }
        }
    }
}
