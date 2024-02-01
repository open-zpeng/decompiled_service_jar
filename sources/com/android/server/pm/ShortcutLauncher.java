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

    private ShortcutLauncher(ShortcutUser shortcutUser, int ownerUserId, String packageName, int launcherUserId, ShortcutPackageInfo spi) {
        super(shortcutUser, launcherUserId, packageName, spi != null ? spi : ShortcutPackageInfo.newEmpty());
        this.mPinnedShortcuts = new ArrayMap<>();
        this.mOwnerUserId = ownerUserId;
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

    /* JADX WARN: Removed duplicated region for block: B:31:0x0071  */
    /* JADX WARN: Removed duplicated region for block: B:57:0x0096 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static com.android.server.pm.ShortcutLauncher loadFromXml(org.xmlpull.v1.XmlPullParser r17, com.android.server.pm.ShortcutUser r18, int r19, boolean r20) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        /*
            r0 = r17
            r1 = r19
            r2 = r20
            java.lang.String r3 = "package-name"
            java.lang.String r4 = com.android.server.pm.ShortcutService.parseStringAttribute(r0, r3)
            if (r2 == 0) goto L11
            r5 = r1
            goto L17
        L11:
            java.lang.String r5 = "launcher-user"
            int r5 = com.android.server.pm.ShortcutService.parseIntAttribute(r0, r5, r1)
        L17:
            com.android.server.pm.ShortcutLauncher r6 = new com.android.server.pm.ShortcutLauncher
            r7 = r18
            r6.<init>(r7, r1, r4, r5)
            r8 = 0
            int r9 = r17.getDepth()
        L24:
            int r10 = r17.next()
            r11 = r10
            r12 = 1
            if (r10 == r12) goto Ld8
            r10 = 3
            if (r11 != r10) goto L35
            int r10 = r17.getDepth()
            if (r10 <= r9) goto Ld8
        L35:
            r10 = 2
            if (r11 == r10) goto L3a
            goto Ld4
        L3a:
            int r10 = r17.getDepth()
            java.lang.String r13 = r17.getName()
            int r14 = r9 + 1
            r16 = -1
            if (r10 != r14) goto L9e
            int r14 = r13.hashCode()
            r15 = -1923478059(0xffffffff8d5a0dd5, float:-6.7193086E-31)
            if (r14 == r15) goto L62
            r15 = -807062458(0xffffffffcfe53446, float:-7.6908165E9)
            if (r14 == r15) goto L57
        L56:
            goto L6d
        L57:
            java.lang.String r14 = "package"
            boolean r14 = r13.equals(r14)
            if (r14 == 0) goto L56
            r14 = r12
            goto L6f
        L62:
            java.lang.String r14 = "package-info"
            boolean r14 = r13.equals(r14)
            if (r14 == 0) goto L56
            r14 = 0
            goto L6f
        L6d:
            r14 = r16
        L6f:
            if (r14 == 0) goto L96
            if (r14 == r12) goto L74
            goto L9e
        L74:
            java.lang.String r12 = com.android.server.pm.ShortcutService.parseStringAttribute(r0, r3)
            if (r2 == 0) goto L7c
            r14 = r1
            goto L83
        L7c:
            java.lang.String r14 = "package-user"
            int r14 = com.android.server.pm.ShortcutService.parseIntAttribute(r0, r14, r1)
        L83:
            android.util.ArraySet r15 = new android.util.ArraySet
            r15.<init>()
            r8 = r15
            android.util.ArrayMap<com.android.server.pm.ShortcutUser$PackageWithUser, android.util.ArraySet<java.lang.String>> r15 = r6.mPinnedShortcuts
            com.android.server.pm.ShortcutUser$PackageWithUser r1 = com.android.server.pm.ShortcutUser.PackageWithUser.of(r14, r12)
            r15.put(r1, r8)
            r1 = r19
            goto L24
        L96:
            com.android.server.pm.ShortcutPackageInfo r1 = r6.getPackageInfo()
            r1.loadFromXml(r0, r2)
            goto Ld4
        L9e:
            int r1 = r9 + 2
            if (r10 != r1) goto Ld0
            int r1 = r13.hashCode()
            r12 = 110997(0x1b195, float:1.5554E-40)
            if (r1 == r12) goto Lac
        Lab:
            goto Lb7
        Lac:
            java.lang.String r1 = "pin"
            boolean r1 = r13.equals(r1)
            if (r1 == 0) goto Lab
            r16 = 0
        Lb7:
            if (r16 == 0) goto Lba
            goto Ld0
        Lba:
            if (r8 != 0) goto Lc5
            java.lang.String r1 = "ShortcutService"
            java.lang.String r12 = "pin in invalid place"
            android.util.Slog.w(r1, r12)
            goto Ld4
        Lc5:
            java.lang.String r1 = "value"
            java.lang.String r1 = com.android.server.pm.ShortcutService.parseStringAttribute(r0, r1)
            r8.add(r1)
            goto Ld4
        Ld0:
            com.android.server.pm.ShortcutService.warnForInvalidTag(r10, r13)
        Ld4:
            r1 = r19
            goto L24
        Ld8:
            return r6
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
        ShortcutPackageInfo packageInfo = getPackageInfo();
        packageInfo.dump(pw, prefix + "  ");
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
