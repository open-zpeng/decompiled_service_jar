package com.android.server.pm;

import android.text.TextUtils;
import java.io.IOException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ShareTargetInfo {
    private static final String ATTR_HOST = "host";
    private static final String ATTR_MIME_TYPE = "mimeType";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_PATH = "path";
    private static final String ATTR_PATH_PATTERN = "pathPattern";
    private static final String ATTR_PATH_PREFIX = "pathPrefix";
    private static final String ATTR_PORT = "port";
    private static final String ATTR_SCHEME = "scheme";
    private static final String ATTR_TARGET_CLASS = "targetClass";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_DATA = "data";
    private static final String TAG_SHARE_TARGET = "share-target";
    final String[] mCategories;
    final String mTargetClass;
    final TargetData[] mTargetData;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class TargetData {
        final String mHost;
        final String mMimeType;
        final String mPath;
        final String mPathPattern;
        final String mPathPrefix;
        final String mPort;
        final String mScheme;

        /* JADX INFO: Access modifiers changed from: package-private */
        public TargetData(String scheme, String host, String port, String path, String pathPattern, String pathPrefix, String mimeType) {
            this.mScheme = scheme;
            this.mHost = host;
            this.mPort = port;
            this.mPath = path;
            this.mPathPattern = pathPattern;
            this.mPathPrefix = pathPrefix;
            this.mMimeType = mimeType;
        }

        public void toStringInner(StringBuilder strBuilder) {
            if (!TextUtils.isEmpty(this.mScheme)) {
                strBuilder.append(" scheme=");
                strBuilder.append(this.mScheme);
            }
            if (!TextUtils.isEmpty(this.mHost)) {
                strBuilder.append(" host=");
                strBuilder.append(this.mHost);
            }
            if (!TextUtils.isEmpty(this.mPort)) {
                strBuilder.append(" port=");
                strBuilder.append(this.mPort);
            }
            if (!TextUtils.isEmpty(this.mPath)) {
                strBuilder.append(" path=");
                strBuilder.append(this.mPath);
            }
            if (!TextUtils.isEmpty(this.mPathPattern)) {
                strBuilder.append(" pathPattern=");
                strBuilder.append(this.mPathPattern);
            }
            if (!TextUtils.isEmpty(this.mPathPrefix)) {
                strBuilder.append(" pathPrefix=");
                strBuilder.append(this.mPathPrefix);
            }
            if (!TextUtils.isEmpty(this.mMimeType)) {
                strBuilder.append(" mimeType=");
                strBuilder.append(this.mMimeType);
            }
        }

        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            toStringInner(strBuilder);
            return strBuilder.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ShareTargetInfo(TargetData[] data, String targetClass, String[] categories) {
        this.mTargetData = data;
        this.mTargetClass = targetClass;
        this.mCategories = categories;
    }

    public String toString() {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append("targetClass=");
        strBuilder.append(this.mTargetClass);
        for (int i = 0; i < this.mTargetData.length; i++) {
            strBuilder.append(" data={");
            this.mTargetData[i].toStringInner(strBuilder);
            strBuilder.append("}");
        }
        for (int i2 = 0; i2 < this.mCategories.length; i2++) {
            strBuilder.append(" category=");
            strBuilder.append(this.mCategories[i2]);
        }
        return strBuilder.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveToXml(XmlSerializer out) throws IOException {
        out.startTag(null, TAG_SHARE_TARGET);
        ShortcutService.writeAttr(out, ATTR_TARGET_CLASS, this.mTargetClass);
        for (int i = 0; i < this.mTargetData.length; i++) {
            out.startTag(null, "data");
            ShortcutService.writeAttr(out, ATTR_SCHEME, this.mTargetData[i].mScheme);
            ShortcutService.writeAttr(out, "host", this.mTargetData[i].mHost);
            ShortcutService.writeAttr(out, ATTR_PORT, this.mTargetData[i].mPort);
            ShortcutService.writeAttr(out, ATTR_PATH, this.mTargetData[i].mPath);
            ShortcutService.writeAttr(out, ATTR_PATH_PATTERN, this.mTargetData[i].mPathPattern);
            ShortcutService.writeAttr(out, ATTR_PATH_PREFIX, this.mTargetData[i].mPathPrefix);
            ShortcutService.writeAttr(out, ATTR_MIME_TYPE, this.mTargetData[i].mMimeType);
            out.endTag(null, "data");
        }
        for (int i2 = 0; i2 < this.mCategories.length; i2++) {
            out.startTag(null, TAG_CATEGORY);
            ShortcutService.writeAttr(out, "name", this.mCategories[i2]);
            out.endTag(null, TAG_CATEGORY);
        }
        out.endTag(null, TAG_SHARE_TARGET);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:39:0x0055 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:43:0x0047 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static com.android.server.pm.ShareTargetInfo loadFromXml(org.xmlpull.v1.XmlPullParser r9) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        /*
            java.lang.String r0 = "targetClass"
            java.lang.String r0 = com.android.server.pm.ShortcutService.parseStringAttribute(r9, r0)
            java.util.ArrayList r1 = new java.util.ArrayList
            r1.<init>()
            java.util.ArrayList r2 = new java.util.ArrayList
            r2.<init>()
        L11:
            int r3 = r9.next()
            r4 = r3
            r5 = 1
            if (r3 == r5) goto L6e
            r3 = 2
            if (r4 != r3) goto L5e
            java.lang.String r3 = r9.getName()
            r6 = -1
            int r7 = r3.hashCode()
            r8 = 3076010(0x2eefaa, float:4.310408E-39)
            if (r7 == r8) goto L3a
            r8 = 50511102(0x302bcfe, float:3.842052E-37)
            if (r7 == r8) goto L30
        L2f:
            goto L44
        L30:
            java.lang.String r7 = "category"
            boolean r3 = r3.equals(r7)
            if (r3 == 0) goto L2f
            r3 = r5
            goto L45
        L3a:
            java.lang.String r7 = "data"
            boolean r3 = r3.equals(r7)
            if (r3 == 0) goto L2f
            r3 = 0
            goto L45
        L44:
            r3 = r6
        L45:
            if (r3 == 0) goto L55
            if (r3 == r5) goto L4a
            goto L5d
        L4a:
            java.lang.String r3 = "name"
            java.lang.String r3 = com.android.server.pm.ShortcutService.parseStringAttribute(r9, r3)
            r2.add(r3)
            goto L5d
        L55:
            com.android.server.pm.ShareTargetInfo$TargetData r3 = parseTargetData(r9)
            r1.add(r3)
        L5d:
            goto L11
        L5e:
            r3 = 3
            if (r4 != r3) goto L11
            java.lang.String r3 = r9.getName()
            java.lang.String r5 = "share-target"
            boolean r3 = r3.equals(r5)
            if (r3 == 0) goto L11
        L6e:
            boolean r3 = r1.isEmpty()
            if (r3 != 0) goto L9b
            if (r0 == 0) goto L9b
            boolean r3 = r2.isEmpty()
            if (r3 == 0) goto L7d
            goto L9b
        L7d:
            com.android.server.pm.ShareTargetInfo r3 = new com.android.server.pm.ShareTargetInfo
            int r5 = r1.size()
            com.android.server.pm.ShareTargetInfo$TargetData[] r5 = new com.android.server.pm.ShareTargetInfo.TargetData[r5]
            java.lang.Object[] r5 = r1.toArray(r5)
            com.android.server.pm.ShareTargetInfo$TargetData[] r5 = (com.android.server.pm.ShareTargetInfo.TargetData[]) r5
            int r6 = r2.size()
            java.lang.String[] r6 = new java.lang.String[r6]
            java.lang.Object[] r6 = r2.toArray(r6)
            java.lang.String[] r6 = (java.lang.String[]) r6
            r3.<init>(r5, r0, r6)
            return r3
        L9b:
            r3 = 0
            return r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShareTargetInfo.loadFromXml(org.xmlpull.v1.XmlPullParser):com.android.server.pm.ShareTargetInfo");
    }

    private static TargetData parseTargetData(XmlPullParser parser) {
        String scheme = ShortcutService.parseStringAttribute(parser, ATTR_SCHEME);
        String host = ShortcutService.parseStringAttribute(parser, "host");
        String port = ShortcutService.parseStringAttribute(parser, ATTR_PORT);
        String path = ShortcutService.parseStringAttribute(parser, ATTR_PATH);
        String pathPattern = ShortcutService.parseStringAttribute(parser, ATTR_PATH_PATTERN);
        String pathPrefix = ShortcutService.parseStringAttribute(parser, ATTR_PATH_PREFIX);
        String mimeType = ShortcutService.parseStringAttribute(parser, ATTR_MIME_TYPE);
        return new TargetData(scheme, host, port, path, pathPattern, pathPrefix, mimeType);
    }
}
