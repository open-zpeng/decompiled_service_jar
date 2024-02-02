package com.android.server.timezone;

import android.util.AtomicFile;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.backup.BackupManagerConstants;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import org.xmlpull.v1.XmlPullParser;
/* loaded from: classes.dex */
final class PackageStatusStorage {
    private static final String ATTRIBUTE_CHECK_STATUS = "checkStatus";
    private static final String ATTRIBUTE_DATA_APP_VERSION = "dataAppPackageVersion";
    private static final String ATTRIBUTE_OPTIMISTIC_LOCK_ID = "optimisticLockId";
    private static final String ATTRIBUTE_UPDATE_APP_VERSION = "updateAppPackageVersion";
    private static final String LOG_TAG = "timezone.PackageStatusStorage";
    private static final String TAG_PACKAGE_STATUS = "PackageStatus";
    private static final long UNKNOWN_PACKAGE_VERSION = -1;
    private final AtomicFile mPackageStatusFile;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageStatusStorage(File storageDir) {
        this.mPackageStatusFile = new AtomicFile(new File(storageDir, "package-status.xml"), "timezone-status");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initialize() throws IOException {
        if (!this.mPackageStatusFile.getBaseFile().exists()) {
            insertInitialPackageStatus();
        }
    }

    void deleteFileForTests() {
        synchronized (this) {
            this.mPackageStatusFile.delete();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageStatus getPackageStatus() {
        PackageStatus packageStatusLocked;
        synchronized (this) {
            try {
                try {
                    packageStatusLocked = getPackageStatusLocked();
                } catch (ParseException e) {
                    Slog.e(LOG_TAG, "Package status invalid, resetting and retrying", e);
                    recoverFromBadData(e);
                    try {
                        return getPackageStatusLocked();
                    } catch (ParseException e2) {
                        throw new IllegalStateException("Recovery from bad file failed", e2);
                    }
                }
            } catch (Throwable th) {
                throw th;
            }
        }
        return packageStatusLocked;
    }

    @GuardedBy("this")
    private PackageStatus getPackageStatusLocked() throws ParseException {
        try {
            FileInputStream fis = this.mPackageStatusFile.openRead();
            XmlPullParser parser = parseToPackageStatusTag(fis);
            Integer checkStatus = getNullableIntAttribute(parser, ATTRIBUTE_CHECK_STATUS);
            if (checkStatus == null) {
                if (fis != null) {
                    $closeResource(null, fis);
                }
                return null;
            }
            int updateAppVersion = getIntAttribute(parser, ATTRIBUTE_UPDATE_APP_VERSION);
            int dataAppVersion = getIntAttribute(parser, ATTRIBUTE_DATA_APP_VERSION);
            PackageStatus packageStatus = new PackageStatus(checkStatus.intValue(), new PackageVersions(updateAppVersion, dataAppVersion));
            if (fis != null) {
                $closeResource(null, fis);
            }
            return packageStatus;
        } catch (IOException e) {
            ParseException e2 = new ParseException("Error reading package status", 0);
            e2.initCause(e);
            throw e2;
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

    @GuardedBy("this")
    private int recoverFromBadData(Exception cause) {
        this.mPackageStatusFile.delete();
        try {
            return insertInitialPackageStatus();
        } catch (IOException e) {
            IllegalStateException fatal = new IllegalStateException(e);
            fatal.addSuppressed(cause);
            throw fatal;
        }
    }

    private int insertInitialPackageStatus() throws IOException {
        int initialOptimisticLockId = (int) System.currentTimeMillis();
        writePackageStatusLocked(null, initialOptimisticLockId, null);
        return initialOptimisticLockId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public CheckToken generateCheckToken(PackageVersions currentInstalledVersions) {
        int optimisticLockId;
        CheckToken checkToken;
        if (currentInstalledVersions == null) {
            throw new NullPointerException("currentInstalledVersions == null");
        }
        synchronized (this) {
            try {
                optimisticLockId = getCurrentOptimisticLockId();
            } catch (ParseException e) {
                Slog.w(LOG_TAG, "Unable to find optimistic lock ID from package status");
                optimisticLockId = recoverFromBadData(e);
            }
            int newOptimisticLockId = optimisticLockId + 1;
            try {
                boolean statusUpdated = writePackageStatusWithOptimisticLockCheck(optimisticLockId, newOptimisticLockId, 1, currentInstalledVersions);
                if (!statusUpdated) {
                    throw new IllegalStateException("Unable to update status to CHECK_STARTED. synchronization failure?");
                }
                checkToken = new CheckToken(newOptimisticLockId, currentInstalledVersions);
            } catch (IOException e2) {
                throw new IllegalStateException(e2);
            }
        }
        return checkToken;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetCheckState() {
        int optimisticLockId;
        synchronized (this) {
            try {
                optimisticLockId = getCurrentOptimisticLockId();
            } catch (ParseException e) {
                Slog.w(LOG_TAG, "resetCheckState: Unable to find optimistic lock ID from package status");
                optimisticLockId = recoverFromBadData(e);
            }
            int newOptimisticLockId = optimisticLockId + 1;
            try {
                if (!writePackageStatusWithOptimisticLockCheck(optimisticLockId, newOptimisticLockId, null, null)) {
                    throw new IllegalStateException("resetCheckState: Unable to reset package status, newOptimisticLockId=" + newOptimisticLockId);
                }
            } catch (IOException e2) {
                throw new IllegalStateException(e2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean markChecked(CheckToken checkToken, boolean succeeded) {
        boolean writePackageStatusWithOptimisticLockCheck;
        synchronized (this) {
            int optimisticLockId = checkToken.mOptimisticLockId;
            int newOptimisticLockId = optimisticLockId + 1;
            int status = succeeded ? 2 : 3;
            try {
                writePackageStatusWithOptimisticLockCheck = writePackageStatusWithOptimisticLockCheck(optimisticLockId, newOptimisticLockId, Integer.valueOf(status), checkToken.mPackageVersions);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return writePackageStatusWithOptimisticLockCheck;
    }

    @GuardedBy("this")
    private int getCurrentOptimisticLockId() throws ParseException {
        try {
            FileInputStream fis = this.mPackageStatusFile.openRead();
            XmlPullParser parser = parseToPackageStatusTag(fis);
            int intAttribute = getIntAttribute(parser, ATTRIBUTE_OPTIMISTIC_LOCK_ID);
            if (fis != null) {
                $closeResource(null, fis);
            }
            return intAttribute;
        } catch (IOException e) {
            ParseException e2 = new ParseException("Unable to read file", 0);
            e2.initCause(e);
            throw e2;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:13:0x002e, code lost:
        throw new java.text.ParseException("Unable to find PackageStatus tag", 0);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static org.xmlpull.v1.XmlPullParser parseToPackageStatusTag(java.io.FileInputStream r5) throws java.text.ParseException {
        /*
            r0 = 0
            org.xmlpull.v1.XmlPullParser r1 = android.util.Xml.newPullParser()     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            java.nio.charset.Charset r2 = java.nio.charset.StandardCharsets.UTF_8     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            java.lang.String r2 = r2.name()     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            r1.setInput(r5, r2)     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
        Le:
            int r2 = r1.next()     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            r3 = r2
            r4 = 1
            if (r2 == r4) goto L27
            java.lang.String r2 = r1.getName()     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            r4 = 2
            if (r3 != r4) goto L26
            java.lang.String r4 = "PackageStatus"
            boolean r4 = r4.equals(r2)     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            if (r4 == 0) goto L26
            return r1
        L26:
            goto Le
        L27:
            java.text.ParseException r2 = new java.text.ParseException     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            java.lang.String r4 = "Unable to find PackageStatus tag"
            r2.<init>(r4, r0)     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
            throw r2     // Catch: java.io.IOException -> L2f org.xmlpull.v1.XmlPullParserException -> L3c
        L2f:
            r1 = move-exception
            java.text.ParseException r2 = new java.text.ParseException
            java.lang.String r3 = "Error reading XML"
            r2.<init>(r3, r0)
            r0 = r2
            r1.initCause(r1)
            throw r0
        L3c:
            r0 = move-exception
            java.lang.IllegalStateException r1 = new java.lang.IllegalStateException
            java.lang.String r2 = "Unable to configure parser"
            r1.<init>(r2, r0)
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.timezone.PackageStatusStorage.parseToPackageStatusTag(java.io.FileInputStream):org.xmlpull.v1.XmlPullParser");
    }

    @GuardedBy("this")
    private boolean writePackageStatusWithOptimisticLockCheck(int optimisticLockId, int newOptimisticLockId, Integer status, PackageVersions packageVersions) throws IOException {
        try {
            int currentOptimisticLockId = getCurrentOptimisticLockId();
            if (currentOptimisticLockId != optimisticLockId) {
                return false;
            }
            writePackageStatusLocked(status, newOptimisticLockId, packageVersions);
            return true;
        } catch (ParseException e) {
            recoverFromBadData(e);
            return false;
        }
    }

    @GuardedBy("this")
    private void writePackageStatusLocked(Integer status, int optimisticLockId, PackageVersions packageVersions) throws IOException {
        if ((status == null) != (packageVersions == null)) {
            throw new IllegalArgumentException("Provide both status and packageVersions, or neither.");
        }
        FileOutputStream fos = null;
        try {
            fos = this.mPackageStatusFile.startWrite();
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(fos, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.startTag(null, TAG_PACKAGE_STATUS);
            String statusAttributeValue = status == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : Integer.toString(status.intValue());
            fastXmlSerializer.attribute(null, ATTRIBUTE_CHECK_STATUS, statusAttributeValue);
            fastXmlSerializer.attribute(null, ATTRIBUTE_OPTIMISTIC_LOCK_ID, Integer.toString(optimisticLockId));
            long dataAppVersion = -1;
            long updateAppVersion = status == null ? -1L : packageVersions.mUpdateAppVersion;
            fastXmlSerializer.attribute(null, ATTRIBUTE_UPDATE_APP_VERSION, Long.toString(updateAppVersion));
            if (status != null) {
                dataAppVersion = packageVersions.mDataAppVersion;
            }
            fastXmlSerializer.attribute(null, ATTRIBUTE_DATA_APP_VERSION, Long.toString(dataAppVersion));
            fastXmlSerializer.endTag(null, TAG_PACKAGE_STATUS);
            fastXmlSerializer.endDocument();
            fastXmlSerializer.flush();
            this.mPackageStatusFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                this.mPackageStatusFile.failWrite(fos);
            }
            throw e;
        }
    }

    public void forceCheckStateForTests(int checkStatus, PackageVersions packageVersions) throws IOException {
        synchronized (this) {
            try {
                try {
                    int initialOptimisticLockId = (int) System.currentTimeMillis();
                    writePackageStatusLocked(Integer.valueOf(checkStatus), initialOptimisticLockId, packageVersions);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    private static Integer getNullableIntAttribute(XmlPullParser parser, String attributeName) throws ParseException {
        String attributeValue = parser.getAttributeValue(null, attributeName);
        try {
            if (attributeValue == null) {
                throw new ParseException("Attribute " + attributeName + " missing", 0);
            } else if (attributeValue.isEmpty()) {
                return null;
            } else {
                return Integer.valueOf(Integer.parseInt(attributeValue));
            }
        } catch (NumberFormatException e) {
            throw new ParseException("Bad integer for attributeName=" + attributeName + ": " + attributeValue, 0);
        }
    }

    private static int getIntAttribute(XmlPullParser parser, String attributeName) throws ParseException {
        Integer value = getNullableIntAttribute(parser, attributeName);
        if (value == null) {
            throw new ParseException("Missing attribute " + attributeName, 0);
        }
        return value.intValue();
    }

    public void dump(PrintWriter printWriter) {
        printWriter.println("Package status: " + getPackageStatus());
    }
}
