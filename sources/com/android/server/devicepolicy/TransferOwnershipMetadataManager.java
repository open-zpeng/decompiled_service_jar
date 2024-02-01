package com.android.server.devicepolicy;

import android.content.ComponentName;
import android.os.Environment;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
class TransferOwnershipMetadataManager {
    static final String ADMIN_TYPE_DEVICE_OWNER = "device-owner";
    static final String ADMIN_TYPE_PROFILE_OWNER = "profile-owner";
    public static final String OWNER_TRANSFER_METADATA_XML = "owner-transfer-metadata.xml";
    private static final String TAG = TransferOwnershipMetadataManager.class.getName();
    @VisibleForTesting
    static final String TAG_ADMIN_TYPE = "admin-type";
    @VisibleForTesting
    static final String TAG_SOURCE_COMPONENT = "source-component";
    @VisibleForTesting
    static final String TAG_TARGET_COMPONENT = "target-component";
    @VisibleForTesting
    static final String TAG_USER_ID = "user-id";
    private final Injector mInjector;

    /* JADX INFO: Access modifiers changed from: package-private */
    public TransferOwnershipMetadataManager() {
        this(new Injector());
    }

    @VisibleForTesting
    TransferOwnershipMetadataManager(Injector injector) {
        this.mInjector = injector;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean saveMetadataFile(Metadata params) {
        File transferOwnershipMetadataFile = new File(this.mInjector.getOwnerTransferMetadataDir(), OWNER_TRANSFER_METADATA_XML);
        AtomicFile atomicFile = new AtomicFile(transferOwnershipMetadataFile);
        FileOutputStream stream = null;
        try {
            stream = atomicFile.startWrite();
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(stream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            insertSimpleTag(serializer, TAG_USER_ID, Integer.toString(params.userId));
            insertSimpleTag(serializer, TAG_SOURCE_COMPONENT, params.sourceComponent.flattenToString());
            insertSimpleTag(serializer, TAG_TARGET_COMPONENT, params.targetComponent.flattenToString());
            insertSimpleTag(serializer, TAG_ADMIN_TYPE, params.adminType);
            serializer.endDocument();
            atomicFile.finishWrite(stream);
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Caught exception while trying to save Owner Transfer Params to file " + transferOwnershipMetadataFile, e);
            transferOwnershipMetadataFile.delete();
            atomicFile.failWrite(stream);
            return false;
        }
    }

    private void insertSimpleTag(XmlSerializer serializer, String tagName, String value) throws IOException {
        serializer.startTag(null, tagName);
        serializer.text(value);
        serializer.endTag(null, tagName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Metadata loadMetadataFile() {
        File transferOwnershipMetadataFile = new File(this.mInjector.getOwnerTransferMetadataDir(), OWNER_TRANSFER_METADATA_XML);
        if (transferOwnershipMetadataFile.exists()) {
            String str = TAG;
            Slog.d(str, "Loading TransferOwnershipMetadataManager from " + transferOwnershipMetadataFile);
            try {
                FileInputStream stream = new FileInputStream(transferOwnershipMetadataFile);
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, null);
                Metadata parseMetadataFile = parseMetadataFile(parser);
                stream.close();
                return parseMetadataFile;
            } catch (IOException | IllegalArgumentException | XmlPullParserException e) {
                String str2 = TAG;
                Slog.e(str2, "Caught exception while trying to load the owner transfer params from file " + transferOwnershipMetadataFile, e);
                return null;
            }
        }
        return null;
    }

    /* JADX WARN: Code restructure failed: missing block: B:32:0x0064, code lost:
        if (r8.equals(com.android.server.devicepolicy.TransferOwnershipMetadataManager.TAG_TARGET_COMPONENT) != false) goto L28;
     */
    /* JADX WARN: Removed duplicated region for block: B:48:0x006c A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:49:0x0074 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:50:0x007c A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:51:0x0084 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:55:0x0008 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private com.android.server.devicepolicy.TransferOwnershipMetadataManager.Metadata parseMetadataFile(org.xmlpull.v1.XmlPullParser r13) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
        /*
            r12 = this;
            int r0 = r13.getDepth()
            r1 = 0
            r2 = 0
            r3 = 0
            r4 = 0
        L8:
            int r5 = r13.next()
            r6 = r5
            r7 = 1
            if (r5 == r7) goto L92
            r5 = 3
            if (r6 != r5) goto L19
            int r8 = r13.getDepth()
            if (r8 <= r0) goto L92
        L19:
            if (r6 == r5) goto L8
            r8 = 4
            if (r6 != r8) goto L1f
            goto L8
        L1f:
            java.lang.String r8 = r13.getName()
            r9 = -1
            int r10 = r8.hashCode()
            r11 = -337219647(0xffffffffebe66fc1, float:-5.5716136E26)
            if (r10 == r11) goto L5d
            r7 = -147180963(0xfffffffff73a325d, float:-3.7765184E33)
            if (r10 == r7) goto L52
            r7 = 281362891(0x10c541cb, float:7.780417E-29)
            if (r10 == r7) goto L47
            r7 = 641951480(0x264366f8, float:6.7793764E-16)
            if (r10 == r7) goto L3d
            goto L67
        L3d:
            java.lang.String r7 = "admin-type"
            boolean r7 = r8.equals(r7)
            if (r7 == 0) goto L67
            r7 = r5
            goto L68
        L47:
            java.lang.String r5 = "source-component"
            boolean r5 = r8.equals(r5)
            if (r5 == 0) goto L67
            r7 = 2
            goto L68
        L52:
            java.lang.String r5 = "user-id"
            boolean r5 = r8.equals(r5)
            if (r5 == 0) goto L67
            r7 = 0
            goto L68
        L5d:
            java.lang.String r5 = "target-component"
            boolean r5 = r8.equals(r5)
            if (r5 == 0) goto L67
            goto L68
        L67:
            r7 = r9
        L68:
            switch(r7) {
                case 0: goto L84;
                case 1: goto L7c;
                case 2: goto L74;
                case 3: goto L6c;
                default: goto L6b;
            }
        L6b:
            goto L90
        L6c:
            r13.next()
            java.lang.String r4 = r13.getText()
            goto L90
        L74:
            r13.next()
            java.lang.String r2 = r13.getText()
            goto L90
        L7c:
            r13.next()
            java.lang.String r3 = r13.getText()
            goto L90
        L84:
            r13.next()
            java.lang.String r5 = r13.getText()
            int r1 = java.lang.Integer.parseInt(r5)
        L90:
            goto L8
        L92:
            com.android.server.devicepolicy.TransferOwnershipMetadataManager$Metadata r5 = new com.android.server.devicepolicy.TransferOwnershipMetadataManager$Metadata
            r5.<init>(r2, r3, r1, r4)
            return r5
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.TransferOwnershipMetadataManager.parseMetadataFile(org.xmlpull.v1.XmlPullParser):com.android.server.devicepolicy.TransferOwnershipMetadataManager$Metadata");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void deleteMetadataFile() {
        new File(this.mInjector.getOwnerTransferMetadataDir(), OWNER_TRANSFER_METADATA_XML).delete();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean metadataFileExists() {
        return new File(this.mInjector.getOwnerTransferMetadataDir(), OWNER_TRANSFER_METADATA_XML).exists();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class Metadata {
        final String adminType;
        final ComponentName sourceComponent;
        final ComponentName targetComponent;
        final int userId;

        /* JADX INFO: Access modifiers changed from: package-private */
        public Metadata(ComponentName sourceComponent, ComponentName targetComponent, int userId, String adminType) {
            this.sourceComponent = sourceComponent;
            this.targetComponent = targetComponent;
            Preconditions.checkNotNull(sourceComponent);
            Preconditions.checkNotNull(targetComponent);
            Preconditions.checkStringNotEmpty(adminType);
            this.userId = userId;
            this.adminType = adminType;
        }

        Metadata(String flatSourceComponent, String flatTargetComponent, int userId, String adminType) {
            this(unflattenComponentUnchecked(flatSourceComponent), unflattenComponentUnchecked(flatTargetComponent), userId, adminType);
        }

        private static ComponentName unflattenComponentUnchecked(String flatComponent) {
            Preconditions.checkNotNull(flatComponent);
            return ComponentName.unflattenFromString(flatComponent);
        }

        public boolean equals(Object obj) {
            if (obj instanceof Metadata) {
                Metadata params = (Metadata) obj;
                return this.userId == params.userId && this.sourceComponent.equals(params.sourceComponent) && this.targetComponent.equals(params.targetComponent) && TextUtils.equals(this.adminType, params.adminType);
            }
            return false;
        }

        public int hashCode() {
            int hashCode = (31 * 1) + this.userId;
            return (31 * ((31 * ((31 * hashCode) + this.sourceComponent.hashCode())) + this.targetComponent.hashCode())) + this.adminType.hashCode();
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    static class Injector {
        Injector() {
        }

        public File getOwnerTransferMetadataDir() {
            return Environment.getDataSystemDirectory();
        }
    }
}
