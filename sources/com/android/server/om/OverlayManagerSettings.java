package com.android.server.om;

import android.content.om.OverlayInfo;
import android.util.ArrayMap;
import android.util.Xml;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;
import com.android.server.om.OverlayManagerSettings;
import com.android.server.pm.PackageManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class OverlayManagerSettings {
    private final ArrayList<SettingsItem> mItems = new ArrayList<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public void init(String packageName, int userId, String targetPackageName, String targetOverlayableName, String baseCodePath, boolean isStatic, int priority, String overlayCategory) {
        remove(packageName, userId);
        SettingsItem item = new SettingsItem(packageName, userId, targetPackageName, targetOverlayableName, baseCodePath, isStatic, priority, overlayCategory);
        if (!isStatic) {
            this.mItems.add(item);
            return;
        }
        item.setEnabled(true);
        int i = this.mItems.size() - 1;
        while (i >= 0) {
            SettingsItem parentItem = this.mItems.get(i);
            if (parentItem.mIsStatic && parentItem.mPriority <= priority) {
                break;
            }
            i--;
        }
        int pos = i + 1;
        if (pos == this.mItems.size()) {
            this.mItems.add(item);
        } else {
            this.mItems.add(pos, item);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean remove(String packageName, int userId) {
        int idx = select(packageName, userId);
        if (idx < 0) {
            return false;
        }
        this.mItems.remove(idx);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public OverlayInfo getOverlayInfo(String packageName, int userId) throws BadKeyException {
        int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return this.mItems.get(idx).getOverlayInfo();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setBaseCodePath(String packageName, int userId, String path) throws BadKeyException {
        int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return this.mItems.get(idx).setBaseCodePath(path);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setCategory(String packageName, int userId, String category) throws BadKeyException {
        int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return this.mItems.get(idx).setCategory(category);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getEnabled(String packageName, int userId) throws BadKeyException {
        int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return this.mItems.get(idx).isEnabled();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setEnabled(String packageName, int userId, boolean enable) throws BadKeyException {
        int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return this.mItems.get(idx).setEnabled(enable);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getState(String packageName, int userId) throws BadKeyException {
        int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return this.mItems.get(idx).getState();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setState(String packageName, int userId, int state) throws BadKeyException {
        int idx = select(packageName, userId);
        if (idx < 0) {
            throw new BadKeyException(packageName, userId);
        }
        return this.mItems.get(idx).setState(state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<OverlayInfo> getOverlaysForTarget(String targetPackageName, int userId) {
        return (List) selectWhereTarget(targetPackageName, userId).filter(new Predicate() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$ATr0DZmWpSWdKD0COw4t2qS-DRk
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return OverlayManagerSettings.lambda$getOverlaysForTarget$0((OverlayManagerSettings.SettingsItem) obj);
            }
        }).map(new Function() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$WYtPK6Ebqjgxm8_8Cot-ijv_z_8
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                OverlayInfo overlayInfo;
                overlayInfo = ((OverlayManagerSettings.SettingsItem) obj).getOverlayInfo();
                return overlayInfo;
            }
        }).collect(Collectors.toList());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$getOverlaysForTarget$0(SettingsItem i) {
        return (i.isStatic() && PackageManagerService.PLATFORM_PACKAGE_NAME.equals(i.getTargetPackageName())) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ArrayMap<String, List<OverlayInfo>> getOverlaysForUser(int userId) {
        return (ArrayMap) selectWhereUser(userId).filter(new Predicate() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$IkswmT9ZZJXmNAztGRVrD3hODMw
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return OverlayManagerSettings.lambda$getOverlaysForUser$2((OverlayManagerSettings.SettingsItem) obj);
            }
        }).map(new Function() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$jZUujzDxrP0hpAqUxnqEf-b-nQc
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                OverlayInfo overlayInfo;
                overlayInfo = ((OverlayManagerSettings.SettingsItem) obj).getOverlayInfo();
                return overlayInfo;
            }
        }).collect(Collectors.groupingBy(new Function() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$sx0Nyvq91kCH_A-4Ctf09G_0u9M
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                String str;
                str = ((OverlayInfo) obj).targetPackageName;
                return str;
            }
        }, new Supplier() { // from class: com.android.server.om.-$$Lambda$bXuJGR0fITXNwGnQfQHv9KS-XgY
            @Override // java.util.function.Supplier
            public final Object get() {
                return new ArrayMap();
            }
        }, Collectors.toList()));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$getOverlaysForUser$2(SettingsItem i) {
        return (i.isStatic() && PackageManagerService.PLATFORM_PACKAGE_NAME.equals(i.getTargetPackageName())) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int[] getUsers() {
        return this.mItems.stream().mapToInt(new ToIntFunction() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$vXm2C4y9Q-F5yYZNimB-Lr6w-oI
            @Override // java.util.function.ToIntFunction
            public final int applyAsInt(Object obj) {
                int userId;
                userId = ((OverlayManagerSettings.SettingsItem) obj).getUserId();
                return userId;
            }
        }).distinct().toArray();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeUser(int userId) {
        boolean removed = false;
        int i = 0;
        while (i < this.mItems.size()) {
            SettingsItem item = this.mItems.get(i);
            if (item.getUserId() == userId) {
                this.mItems.remove(i);
                removed = true;
                i--;
            }
            i++;
        }
        return removed;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setPriority(String packageName, String newParentPackageName, int userId) {
        int moveIdx;
        int parentIdx;
        if (!packageName.equals(newParentPackageName) && (moveIdx = select(packageName, userId)) >= 0 && (parentIdx = select(newParentPackageName, userId)) >= 0) {
            SettingsItem itemToMove = this.mItems.get(moveIdx);
            if (itemToMove.getTargetPackageName().equals(this.mItems.get(parentIdx).getTargetPackageName())) {
                this.mItems.remove(moveIdx);
                int newParentIdx = select(newParentPackageName, userId) + 1;
                this.mItems.add(newParentIdx, itemToMove);
                return moveIdx != newParentIdx;
            }
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setLowestPriority(String packageName, int userId) {
        int idx = select(packageName, userId);
        if (idx <= 0) {
            return false;
        }
        SettingsItem item = this.mItems.get(idx);
        this.mItems.remove(item);
        this.mItems.add(0, item);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setHighestPriority(String packageName, int userId) {
        int idx = select(packageName, userId);
        if (idx < 0 || idx == this.mItems.size() - 1) {
            return false;
        }
        SettingsItem item = this.mItems.get(idx);
        this.mItems.remove(idx);
        this.mItems.add(item);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter p, final DumpState dumpState) {
        Stream<SettingsItem> items = this.mItems.stream();
        if (dumpState.getUserId() != -1) {
            items = items.filter(new Predicate() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$n3zAcJx5VlITl9U9fQatqN2KJyA
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return OverlayManagerSettings.lambda$dump$6(DumpState.this, (OverlayManagerSettings.SettingsItem) obj);
                }
            });
        }
        if (dumpState.getPackageName() != null) {
            items = items.filter(new Predicate() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$leWA95COTthWNYtDKcdKVChlc-c
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean equals;
                    equals = ((OverlayManagerSettings.SettingsItem) obj).mPackageName.equals(DumpState.this.getPackageName());
                    return equals;
                }
            });
        }
        final IndentingPrintWriter pw = new IndentingPrintWriter(p, "  ");
        if (dumpState.getField() != null) {
            items.forEach(new Consumer() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$BKNCDt6MBH2RSKr2mbIUnL_dIvA
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    OverlayManagerSettings.this.lambda$dump$8$OverlayManagerSettings(pw, dumpState, (OverlayManagerSettings.SettingsItem) obj);
                }
            });
        } else {
            items.forEach(new Consumer() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$Xr3l7ivgTflBmPTqf9hbG3i0H_I
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    OverlayManagerSettings.this.lambda$dump$9$OverlayManagerSettings(pw, (OverlayManagerSettings.SettingsItem) obj);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$dump$6(DumpState dumpState, SettingsItem item) {
        return item.mUserId == dumpState.getUserId();
    }

    public /* synthetic */ void lambda$dump$8$OverlayManagerSettings(IndentingPrintWriter pw, DumpState dumpState, SettingsItem item) {
        dumpSettingsItemField(pw, item, dumpState.getField());
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: dumpSettingsItem */
    public void lambda$dump$9$OverlayManagerSettings(IndentingPrintWriter pw, SettingsItem item) {
        pw.println(item.mPackageName + ":" + item.getUserId() + " {");
        pw.increaseIndent();
        StringBuilder sb = new StringBuilder();
        sb.append("mPackageName...........: ");
        sb.append(item.mPackageName);
        pw.println(sb.toString());
        pw.println("mUserId................: " + item.getUserId());
        pw.println("mTargetPackageName.....: " + item.getTargetPackageName());
        pw.println("mTargetOverlayableName.: " + item.getTargetOverlayableName());
        pw.println("mBaseCodePath..........: " + item.getBaseCodePath());
        pw.println("mState.................: " + OverlayInfo.stateToString(item.getState()));
        pw.println("mIsEnabled.............: " + item.isEnabled());
        pw.println("mIsStatic..............: " + item.isStatic());
        pw.println("mPriority..............: " + item.mPriority);
        pw.println("mCategory..............: " + item.mCategory);
        pw.decreaseIndent();
        pw.println("}");
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    private void dumpSettingsItemField(IndentingPrintWriter pw, SettingsItem item, String field) {
        char c;
        switch (field.hashCode()) {
            case -1750736508:
                if (field.equals("targetoverlayablename")) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case -1248283232:
                if (field.equals("targetpackagename")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case -1165461084:
                if (field.equals(xpInputManagerService.InputPolicyKey.KEY_PRIORITY)) {
                    c = '\b';
                    break;
                }
                c = 65535;
                break;
            case -836029914:
                if (field.equals("userid")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            case 50511102:
                if (field.equals("category")) {
                    c = '\t';
                    break;
                }
                c = 65535;
                break;
            case 109757585:
                if (field.equals("state")) {
                    c = 5;
                    break;
                }
                c = 65535;
                break;
            case 440941271:
                if (field.equals("isenabled")) {
                    c = 6;
                    break;
                }
                c = 65535;
                break;
            case 697685016:
                if (field.equals("isstatic")) {
                    c = 7;
                    break;
                }
                c = 65535;
                break;
            case 909712337:
                if (field.equals("packagename")) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case 1693907299:
                if (field.equals("basecodepath")) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        switch (c) {
            case 0:
                pw.println(item.mPackageName);
                return;
            case 1:
                pw.println(item.mUserId);
                return;
            case 2:
                pw.println(item.mTargetPackageName);
                return;
            case 3:
                pw.println(item.mTargetOverlayableName);
                return;
            case 4:
                pw.println(item.mBaseCodePath);
                return;
            case 5:
                pw.println(OverlayInfo.stateToString(item.mState));
                return;
            case 6:
                pw.println(item.mIsEnabled);
                return;
            case 7:
                pw.println(item.mIsStatic);
                return;
            case '\b':
                pw.println(item.mPriority);
                return;
            case '\t':
                pw.println(item.mCategory);
                return;
            default:
                return;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void restore(InputStream is) throws IOException, XmlPullParserException {
        Serializer.restore(this.mItems, is);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void persist(OutputStream os) throws IOException, XmlPullParserException {
        Serializer.persist(this.mItems, os);
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    static final class Serializer {
        private static final String ATTR_BASE_CODE_PATH = "baseCodePath";
        private static final String ATTR_CATEGORY = "category";
        private static final String ATTR_IS_ENABLED = "isEnabled";
        private static final String ATTR_IS_STATIC = "isStatic";
        private static final String ATTR_PACKAGE_NAME = "packageName";
        private static final String ATTR_PRIORITY = "priority";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_TARGET_OVERLAYABLE_NAME = "targetOverlayableName";
        private static final String ATTR_TARGET_PACKAGE_NAME = "targetPackageName";
        private static final String ATTR_USER_ID = "userId";
        private static final String ATTR_VERSION = "version";
        @VisibleForTesting
        static final int CURRENT_VERSION = 3;
        private static final String TAG_ITEM = "item";
        private static final String TAG_OVERLAYS = "overlays";

        Serializer() {
        }

        public static void restore(ArrayList<SettingsItem> table, InputStream is) throws IOException, XmlPullParserException {
            InputStreamReader reader = new InputStreamReader(is);
            try {
                table.clear();
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(reader);
                XmlUtils.beginDocument(parser, TAG_OVERLAYS);
                int version = XmlUtils.readIntAttribute(parser, "version");
                if (version != 3) {
                    upgrade(version);
                }
                int depth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, depth)) {
                    String name = parser.getName();
                    char c = 65535;
                    if (name.hashCode() == 3242771 && name.equals("item")) {
                        c = 0;
                    }
                    if (c == 0) {
                        SettingsItem item = restoreRow(parser, depth + 1);
                        table.add(item);
                    }
                }
                reader.close();
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    try {
                        reader.close();
                    } catch (Throwable th3) {
                        th.addSuppressed(th3);
                    }
                    throw th2;
                }
            }
        }

        private static void upgrade(int oldVersion) throws XmlPullParserException {
            if (oldVersion == 0 || oldVersion == 1 || oldVersion == 2) {
                throw new XmlPullParserException("old version " + oldVersion + "; ignoring");
            }
            throw new XmlPullParserException("unrecognized version " + oldVersion);
        }

        private static SettingsItem restoreRow(XmlPullParser parser, int depth) throws IOException {
            String packageName = XmlUtils.readStringAttribute(parser, "packageName");
            int userId = XmlUtils.readIntAttribute(parser, ATTR_USER_ID);
            String targetPackageName = XmlUtils.readStringAttribute(parser, ATTR_TARGET_PACKAGE_NAME);
            String targetOverlayableName = XmlUtils.readStringAttribute(parser, ATTR_TARGET_OVERLAYABLE_NAME);
            String baseCodePath = XmlUtils.readStringAttribute(parser, ATTR_BASE_CODE_PATH);
            int state = XmlUtils.readIntAttribute(parser, ATTR_STATE);
            boolean isEnabled = XmlUtils.readBooleanAttribute(parser, ATTR_IS_ENABLED);
            boolean isStatic = XmlUtils.readBooleanAttribute(parser, ATTR_IS_STATIC);
            int priority = XmlUtils.readIntAttribute(parser, "priority");
            String category = XmlUtils.readStringAttribute(parser, ATTR_CATEGORY);
            return new SettingsItem(packageName, userId, targetPackageName, targetOverlayableName, baseCodePath, state, isEnabled, isStatic, priority, category);
        }

        public static void persist(ArrayList<SettingsItem> table, OutputStream os) throws IOException, XmlPullParserException {
            FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(os, "utf-8");
            xml.startDocument((String) null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xml.startTag((String) null, TAG_OVERLAYS);
            XmlUtils.writeIntAttribute(xml, "version", 3);
            int n = table.size();
            for (int i = 0; i < n; i++) {
                SettingsItem item = table.get(i);
                persistRow(xml, item);
            }
            xml.endTag((String) null, TAG_OVERLAYS);
            xml.endDocument();
        }

        private static void persistRow(FastXmlSerializer xml, SettingsItem item) throws IOException {
            xml.startTag((String) null, "item");
            XmlUtils.writeStringAttribute(xml, "packageName", item.mPackageName);
            XmlUtils.writeIntAttribute(xml, ATTR_USER_ID, item.mUserId);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_PACKAGE_NAME, item.mTargetPackageName);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_OVERLAYABLE_NAME, item.mTargetOverlayableName);
            XmlUtils.writeStringAttribute(xml, ATTR_BASE_CODE_PATH, item.mBaseCodePath);
            XmlUtils.writeIntAttribute(xml, ATTR_STATE, item.mState);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_ENABLED, item.mIsEnabled);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_STATIC, item.mIsStatic);
            XmlUtils.writeIntAttribute(xml, "priority", item.mPriority);
            XmlUtils.writeStringAttribute(xml, ATTR_CATEGORY, item.mCategory);
            xml.endTag((String) null, "item");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class SettingsItem {
        private String mBaseCodePath;
        private OverlayInfo mCache;
        private String mCategory;
        private boolean mIsEnabled;
        private boolean mIsStatic;
        private final String mPackageName;
        private int mPriority;
        private int mState;
        private final String mTargetOverlayableName;
        private final String mTargetPackageName;
        private final int mUserId;

        SettingsItem(String packageName, int userId, String targetPackageName, String targetOverlayableName, String baseCodePath, int state, boolean isEnabled, boolean isStatic, int priority, String category) {
            this.mPackageName = packageName;
            this.mUserId = userId;
            this.mTargetPackageName = targetPackageName;
            this.mTargetOverlayableName = targetOverlayableName;
            this.mBaseCodePath = baseCodePath;
            this.mState = state;
            this.mIsEnabled = isEnabled || isStatic;
            this.mCategory = category;
            this.mCache = null;
            this.mIsStatic = isStatic;
            this.mPriority = priority;
        }

        SettingsItem(String packageName, int userId, String targetPackageName, String targetOverlayableName, String baseCodePath, boolean isStatic, int priority, String category) {
            this(packageName, userId, targetPackageName, targetOverlayableName, baseCodePath, -1, false, isStatic, priority, category);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public String getTargetPackageName() {
            return this.mTargetPackageName;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public String getTargetOverlayableName() {
            return this.mTargetOverlayableName;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public int getUserId() {
            return this.mUserId;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public String getBaseCodePath() {
            return this.mBaseCodePath;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean setBaseCodePath(String path) {
            if (!this.mBaseCodePath.equals(path)) {
                this.mBaseCodePath = path;
                invalidateCache();
                return true;
            }
            return false;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public int getState() {
            return this.mState;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean setState(int state) {
            if (this.mState != state) {
                this.mState = state;
                invalidateCache();
                return true;
            }
            return false;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isEnabled() {
            return this.mIsEnabled;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean setEnabled(boolean enable) {
            if (this.mIsStatic || this.mIsEnabled == enable) {
                return false;
            }
            this.mIsEnabled = enable;
            invalidateCache();
            return true;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean setCategory(String category) {
            if (!Objects.equals(this.mCategory, category)) {
                this.mCategory = category == null ? null : category.intern();
                invalidateCache();
                return true;
            }
            return false;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public OverlayInfo getOverlayInfo() {
            if (this.mCache == null) {
                this.mCache = new OverlayInfo(this.mPackageName, this.mTargetPackageName, this.mTargetOverlayableName, this.mCategory, this.mBaseCodePath, this.mState, this.mUserId, this.mPriority, this.mIsStatic);
            }
            return this.mCache;
        }

        private void invalidateCache() {
            this.mCache = null;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isStatic() {
            return this.mIsStatic;
        }

        private int getPriority() {
            return this.mPriority;
        }
    }

    private int select(String packageName, int userId) {
        int n = this.mItems.size();
        for (int i = 0; i < n; i++) {
            SettingsItem item = this.mItems.get(i);
            if (item.mUserId == userId && item.mPackageName.equals(packageName)) {
                return i;
            }
        }
        return -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$selectWhereUser$10(int userId, SettingsItem item) {
        return item.mUserId == userId;
    }

    private Stream<SettingsItem> selectWhereUser(final int userId) {
        return this.mItems.stream().filter(new Predicate() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$Fjt465P6G89HQZERZFsOEjMbtXI
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return OverlayManagerSettings.lambda$selectWhereUser$10(userId, (OverlayManagerSettings.SettingsItem) obj);
            }
        });
    }

    private Stream<SettingsItem> selectWhereTarget(final String targetPackageName, int userId) {
        return selectWhereUser(userId).filter(new Predicate() { // from class: com.android.server.om.-$$Lambda$OverlayManagerSettings$L_Sj43p2Txm_KH-wT0lseBTVzh8
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean equals;
                equals = ((OverlayManagerSettings.SettingsItem) obj).getTargetPackageName().equals(targetPackageName);
                return equals;
            }
        });
    }

    /* loaded from: classes.dex */
    static final class BadKeyException extends RuntimeException {
        BadKeyException(String packageName, int userId) {
            super("Bad key mPackageName=" + packageName + " mUserId=" + userId);
        }
    }
}
