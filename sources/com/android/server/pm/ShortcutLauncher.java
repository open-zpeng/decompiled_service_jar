package com.android.server.pm;

import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.ShortcutService;
import com.android.server.pm.ShortcutUser;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlSerializer;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ShortcutLauncher extends ShortcutPackageItem {
    private static final String ATTR_LAUNCHER_USER_ID = "launcher-user";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_PACKAGE_USER_ID = "package-user";
    private static final String ATTR_VALUE = "value";
    private static final String TAG = "ShortcutService";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PIN = "pin";
    static final String TAG_ROOT = "launcher-pins";
    private final int mOwnerUserId;
    private final ArrayMap<ShortcutUser.PackageWithUser, ArraySet<String>> mPinnedShortcuts;

    /* JADX WARN: Illegal instructions before constructor call */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private ShortcutLauncher(com.android.server.pm.ShortcutUser r2, int r3, java.lang.String r4, int r5, com.android.server.pm.ShortcutPackageInfo r6) {
        /*
            r1 = this;
            if (r6 == 0) goto L5
            r0 = r6
            goto L9
        L5:
            com.android.server.pm.ShortcutPackageInfo r0 = com.android.server.pm.ShortcutPackageInfo.newEmpty()
        L9:
            r1.<init>(r2, r5, r4, r0)
            android.util.ArrayMap r0 = new android.util.ArrayMap
            r0.<init>()
            r1.mPinnedShortcuts = r0
            r1.mOwnerUserId = r3
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutLauncher.<init>(com.android.server.pm.ShortcutUser, int, java.lang.String, int, com.android.server.pm.ShortcutPackageInfo):void");
    }

    public ShortcutLauncher(ShortcutUser shortcutUser, int ownerUserId, String packageName, int launcherUserId) {
        this(shortcutUser, ownerUserId, packageName, launcherUserId, null);
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    public int getOwnerUserId() {
        return this.mOwnerUserId;
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    protected boolean canRestoreAnyVersion() {
        return true;
    }

    private void onRestoreBlocked() {
        ArrayList<ShortcutUser.PackageWithUser> pinnedPackages = new ArrayList<>(this.mPinnedShortcuts.keySet());
        this.mPinnedShortcuts.clear();
        for (int i = pinnedPackages.size() - 1; i >= 0; i--) {
            ShortcutUser.PackageWithUser pu = pinnedPackages.get(i);
            ShortcutPackage p = this.mShortcutUser.getPackageShortcutsIfExists(pu.packageName);
            if (p != null) {
                p.refreshPinnedFlags();
            }
        }
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    protected void onRestored(int restoreBlockReason) {
        if (restoreBlockReason != 0) {
            onRestoreBlocked();
        }
    }

    public void pinShortcuts(int packageUserId, String packageName, List<String> ids, boolean forPinRequest) {
        ShortcutPackage packageShortcuts = this.mShortcutUser.getPackageShortcutsIfExists(packageName);
        if (packageShortcuts == null) {
            return;
        }
        ShortcutUser.PackageWithUser pu = ShortcutUser.PackageWithUser.of(packageUserId, packageName);
        int idSize = ids.size();
        if (idSize == 0) {
            this.mPinnedShortcuts.remove(pu);
        } else {
            ArraySet<String> prevSet = this.mPinnedShortcuts.get(pu);
            ArraySet<String> newSet = new ArraySet<>();
            for (int i = 0; i < idSize; i++) {
                String id = ids.get(i);
                ShortcutInfo si = packageShortcuts.findShortcutById(id);
                if (si != null && (si.isDynamic() || si.isManifestShortcut() || ((prevSet != null && prevSet.contains(id)) || forPinRequest))) {
                    newSet.add(id);
                }
            }
            this.mPinnedShortcuts.put(pu, newSet);
        }
        packageShortcuts.refreshPinnedFlags();
    }

    public ArraySet<String> getPinnedShortcutIds(String packageName, int packageUserId) {
        return this.mPinnedShortcuts.get(ShortcutUser.PackageWithUser.of(packageUserId, packageName));
    }

    public boolean hasPinned(ShortcutInfo shortcut) {
        ArraySet<String> pinned = getPinnedShortcutIds(shortcut.getPackage(), shortcut.getUserId());
        return pinned != null && pinned.contains(shortcut.getId());
    }

    public void addPinnedShortcut(String packageName, int packageUserId, String id, boolean forPinRequest) {
        ArrayList<String> pinnedList;
        ArraySet<String> pinnedSet = getPinnedShortcutIds(packageName, packageUserId);
        if (pinnedSet != null) {
            pinnedList = new ArrayList<>(pinnedSet.size() + 1);
            pinnedList.addAll(pinnedSet);
        } else {
            pinnedList = new ArrayList<>(1);
        }
        pinnedList.add(id);
        pinShortcuts(packageUserId, packageName, pinnedList, forPinRequest);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean cleanUpPackage(String packageName, int packageUserId) {
        return this.mPinnedShortcuts.remove(ShortcutUser.PackageWithUser.of(packageUserId, packageName)) != null;
    }

    public void ensurePackageInfo() {
        PackageInfo pi = this.mShortcutUser.mService.getPackageInfoWithSignatures(getPackageName(), getPackageUserId());
        if (pi == null) {
            Slog.w(TAG, "Package not found: " + getPackageName());
            return;
        }
        getPackageInfo().updateFromPackageInfo(pi);
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    public void saveToXml(XmlSerializer out, boolean forBackup) throws IOException {
        int size;
        if ((forBackup && !getPackageInfo().isBackupAllowed()) || (size = this.mPinnedShortcuts.size()) == 0) {
            return;
        }
        out.startTag(null, TAG_ROOT);
        ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME, getPackageName());
        ShortcutService.writeAttr(out, ATTR_LAUNCHER_USER_ID, getPackageUserId());
        getPackageInfo().saveToXml(this.mShortcutUser.mService, out, forBackup);
        for (int i = 0; i < size; i++) {
            ShortcutUser.PackageWithUser pu = this.mPinnedShortcuts.keyAt(i);
            if (!forBackup || pu.userId == getOwnerUserId()) {
                out.startTag(null, "package");
                ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME, pu.packageName);
                ShortcutService.writeAttr(out, ATTR_PACKAGE_USER_ID, pu.userId);
                ArraySet<String> ids = this.mPinnedShortcuts.valueAt(i);
                int idSize = ids.size();
                for (int j = 0; j < idSize; j++) {
                    ShortcutService.writeTagValue(out, TAG_PIN, ids.valueAt(j));
                }
                out.endTag(null, "package");
            }
        }
        out.endTag(null, TAG_ROOT);
    }

    /* JADX WARN: Removed duplicated region for block: B:58:0x0096 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:59:0x009e A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:60:0x0074 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static com.android.server.pm.ShortcutLauncher loadFromXml(org.xmlpull.v1.XmlPullParser r17, com.android.server.pm.ShortcutUser r18, int r19, boolean r20) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        /*
            r0 = r17
            r1 = r19
            r2 = r20
            java.lang.String r3 = "package-name"
            java.lang.String r3 = com.android.server.pm.ShortcutService.parseStringAttribute(r0, r3)
            if (r2 == 0) goto L11
            r4 = r1
            goto L18
        L11:
            java.lang.String r4 = "launcher-user"
            int r4 = com.android.server.pm.ShortcutService.parseIntAttribute(r0, r4, r1)
        L18:
            com.android.server.pm.ShortcutLauncher r5 = new com.android.server.pm.ShortcutLauncher
            r6 = r18
            r5.<init>(r6, r1, r3, r4)
            r7 = 0
            int r8 = r17.getDepth()
        L24:
            int r9 = r17.next()
            r10 = r9
            r11 = 1
            if (r9 == r11) goto Ld7
            r9 = 3
            if (r10 != r9) goto L35
            int r9 = r17.getDepth()
            if (r9 <= r8) goto Ld7
        L35:
            r9 = 2
            if (r10 == r9) goto L39
            goto L24
        L39:
            int r9 = r17.getDepth()
            java.lang.String r12 = r17.getName()
            int r13 = r8 + 1
            r14 = 0
            r15 = -1
            if (r9 != r13) goto L9e
            int r13 = r12.hashCode()
            r11 = -1923478059(0xffffffff8d5a0dd5, float:-6.7193086E-31)
            if (r13 == r11) goto L62
            r11 = -807062458(0xffffffffcfe53446, float:-7.6908165E9)
            if (r13 == r11) goto L56
            goto L6e
        L56:
            java.lang.String r11 = "package"
            boolean r11 = r12.equals(r11)
            if (r11 == 0) goto L6e
            r16 = 1
            goto L70
        L62:
            java.lang.String r11 = "package-info"
            boolean r11 = r12.equals(r11)
            if (r11 == 0) goto L6e
            r16 = r14
            goto L70
        L6e:
            r16 = r15
        L70:
            switch(r16) {
                case 0: goto L96;
                case 1: goto L74;
                default: goto L73;
            }
        L73:
            goto L9e
        L74:
            java.lang.String r11 = "package-name"
            java.lang.String r11 = com.android.server.pm.ShortcutService.parseStringAttribute(r0, r11)
            if (r2 == 0) goto L7f
            r13 = r1
            goto L86
        L7f:
            java.lang.String r13 = "package-user"
            int r13 = com.android.server.pm.ShortcutService.parseIntAttribute(r0, r13, r1)
        L86:
            android.util.ArraySet r14 = new android.util.ArraySet
            r14.<init>()
            r7 = r14
            android.util.ArrayMap<com.android.server.pm.ShortcutUser$PackageWithUser, android.util.ArraySet<java.lang.String>> r14 = r5.mPinnedShortcuts
            com.android.server.pm.ShortcutUser$PackageWithUser r15 = com.android.server.pm.ShortcutUser.PackageWithUser.of(r13, r11)
            r14.put(r15, r7)
            goto L24
        L96:
            com.android.server.pm.ShortcutPackageInfo r11 = r5.getPackageInfo()
            r11.loadFromXml(r0, r2)
            goto L24
        L9e:
            int r11 = r8 + 2
            if (r9 != r11) goto Ld2
            int r11 = r12.hashCode()
            r13 = 110997(0x1b195, float:1.5554E-40)
            if (r11 == r13) goto Lac
            goto Lb6
        Lac:
            java.lang.String r11 = "pin"
            boolean r11 = r12.equals(r11)
            if (r11 == 0) goto Lb6
            goto Lb7
        Lb6:
            r14 = r15
        Lb7:
            if (r14 == 0) goto Lba
            goto Ld2
        Lba:
            if (r7 != 0) goto Lc6
            java.lang.String r11 = "ShortcutService"
            java.lang.String r13 = "pin in invalid place"
            android.util.Slog.w(r11, r13)
            goto L24
        Lc6:
            java.lang.String r11 = "value"
            java.lang.String r11 = com.android.server.pm.ShortcutService.parseStringAttribute(r0, r11)
            r7.add(r11)
            goto L24
        Ld2:
            com.android.server.pm.ShortcutService.warnForInvalidTag(r9, r12)
            goto L24
        Ld7:
            return r5
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutLauncher.loadFromXml(org.xmlpull.v1.XmlPullParser, com.android.server.pm.ShortcutUser, int, boolean):com.android.server.pm.ShortcutLauncher");
    }

    public void dump(PrintWriter pw, String prefix, ShortcutService.DumpFilter filter) {
        pw.println();
        pw.print(prefix);
        pw.print("Launcher: ");
        pw.print(getPackageName());
        pw.print("  Package user: ");
        pw.print(getPackageUserId());
        pw.print("  Owner user: ");
        pw.print(getOwnerUserId());
        pw.println();
        getPackageInfo().dump(pw, prefix + "  ");
        pw.println();
        int size = this.mPinnedShortcuts.size();
        for (int i = 0; i < size; i++) {
            pw.println();
            ShortcutUser.PackageWithUser pu = this.mPinnedShortcuts.keyAt(i);
            pw.print(prefix);
            pw.print("  ");
            pw.print("Package: ");
            pw.print(pu.packageName);
            pw.print("  User: ");
            pw.println(pu.userId);
            ArraySet<String> ids = this.mPinnedShortcuts.valueAt(i);
            int idSize = ids.size();
            for (int j = 0; j < idSize; j++) {
                pw.print(prefix);
                pw.print("    Pinned: ");
                pw.print(ids.valueAt(j));
                pw.println();
            }
        }
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    public JSONObject dumpCheckin(boolean clear) throws JSONException {
        JSONObject result = super.dumpCheckin(clear);
        return result;
    }

    @VisibleForTesting
    ArraySet<String> getAllPinnedShortcutsForTest(String packageName, int packageUserId) {
        return new ArraySet<>(this.mPinnedShortcuts.get(ShortcutUser.PackageWithUser.of(packageUserId, packageName)));
    }
}
