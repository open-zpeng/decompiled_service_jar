package com.android.server.pm.permission;

import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.UserHandle;
import android.util.Slog;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.PackageSettingBase;
import com.android.server.pm.Settings;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public final class BasePermission {
    static final String TAG = "PackageManager";
    public static final int TYPE_BUILTIN = 1;
    public static final int TYPE_DYNAMIC = 2;
    public static final int TYPE_NORMAL = 0;
    private int[] gids;
    private boolean mPermissionDefinitionChanged;
    final String name;
    PermissionInfo pendingPermissionInfo;
    private boolean perUser;
    PackageParser.Permission perm;
    int protectionLevel = 2;
    String sourcePackageName;
    PackageSettingBase sourcePackageSetting;
    final int type;
    int uid;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface PermissionType {
    }

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface ProtectionLevel {
    }

    public BasePermission(String _name, String _sourcePackageName, int _type) {
        this.name = _name;
        this.sourcePackageName = _sourcePackageName;
        this.type = _type;
    }

    public String toString() {
        return "BasePermission{" + Integer.toHexString(System.identityHashCode(this)) + " " + this.name + "}";
    }

    public String getName() {
        return this.name;
    }

    public int getProtectionLevel() {
        return this.protectionLevel;
    }

    public String getSourcePackageName() {
        return this.sourcePackageName;
    }

    public PackageSettingBase getSourcePackageSetting() {
        return this.sourcePackageSetting;
    }

    public Signature[] getSourceSignatures() {
        return this.sourcePackageSetting.getSignatures();
    }

    public boolean isPermissionDefinitionChanged() {
        return this.mPermissionDefinitionChanged;
    }

    public int getType() {
        return this.type;
    }

    public int getUid() {
        return this.uid;
    }

    public void setGids(int[] gids, boolean perUser) {
        this.gids = gids;
        this.perUser = perUser;
    }

    public void setPermission(PackageParser.Permission perm) {
        this.perm = perm;
    }

    public void setSourcePackageSetting(PackageSettingBase sourcePackageSetting) {
        this.sourcePackageSetting = sourcePackageSetting;
    }

    public void setPermissionDefinitionChanged(boolean shouldOverride) {
        this.mPermissionDefinitionChanged = shouldOverride;
    }

    public int[] computeGids(int userId) {
        if (this.perUser) {
            int[] userGids = new int[this.gids.length];
            int i = 0;
            while (true) {
                int[] iArr = this.gids;
                if (i < iArr.length) {
                    userGids[i] = UserHandle.getUid(userId, iArr[i]);
                    i++;
                } else {
                    return userGids;
                }
            }
        } else {
            return this.gids;
        }
    }

    public int calculateFootprint(BasePermission perm) {
        if (this.uid == perm.uid) {
            return perm.name.length() + perm.perm.info.calculateFootprint();
        }
        return 0;
    }

    public boolean isPermission(PackageParser.Permission perm) {
        return this.perm == perm;
    }

    public boolean isDynamic() {
        return this.type == 2;
    }

    public boolean isNormal() {
        return (this.protectionLevel & 15) == 0;
    }

    public boolean isRuntime() {
        return (this.protectionLevel & 15) == 1;
    }

    public boolean isRemoved() {
        PackageParser.Permission permission = this.perm;
        return (permission == null || permission.info == null || (this.perm.info.flags & 2) == 0) ? false : true;
    }

    public boolean isSoftRestricted() {
        PackageParser.Permission permission = this.perm;
        return (permission == null || permission.info == null || (this.perm.info.flags & 8) == 0) ? false : true;
    }

    public boolean isHardRestricted() {
        PackageParser.Permission permission = this.perm;
        return (permission == null || permission.info == null || (this.perm.info.flags & 4) == 0) ? false : true;
    }

    public boolean isHardOrSoftRestricted() {
        PackageParser.Permission permission = this.perm;
        return (permission == null || permission.info == null || (this.perm.info.flags & 12) == 0) ? false : true;
    }

    public boolean isImmutablyRestricted() {
        PackageParser.Permission permission = this.perm;
        return (permission == null || permission.info == null || (this.perm.info.flags & 16) == 0) ? false : true;
    }

    public boolean isSignature() {
        return (this.protectionLevel & 15) == 2;
    }

    public boolean isAppOp() {
        return (this.protectionLevel & 64) != 0;
    }

    public boolean isDevelopment() {
        return isSignature() && (this.protectionLevel & 32) != 0;
    }

    public boolean isInstaller() {
        return (this.protectionLevel & 256) != 0;
    }

    public boolean isInstant() {
        return (this.protectionLevel & 4096) != 0;
    }

    public boolean isOEM() {
        return (this.protectionLevel & 16384) != 0;
    }

    public boolean isPre23() {
        return (this.protectionLevel & 128) != 0;
    }

    public boolean isPreInstalled() {
        return (this.protectionLevel & 1024) != 0;
    }

    public boolean isPrivileged() {
        return (this.protectionLevel & 16) != 0;
    }

    public boolean isRuntimeOnly() {
        return (this.protectionLevel & 8192) != 0;
    }

    public boolean isSetup() {
        return (this.protectionLevel & 2048) != 0;
    }

    public boolean isVerifier() {
        return (this.protectionLevel & 512) != 0;
    }

    public boolean isVendorPrivileged() {
        return (this.protectionLevel & 32768) != 0;
    }

    public boolean isSystemTextClassifier() {
        return (this.protectionLevel & 65536) != 0;
    }

    public boolean isWellbeing() {
        return (this.protectionLevel & 131072) != 0;
    }

    public boolean isDocumenter() {
        return (this.protectionLevel & 262144) != 0;
    }

    public boolean isConfigurator() {
        return (this.protectionLevel & DumpState.DUMP_FROZEN) != 0;
    }

    public boolean isIncidentReportApprover() {
        return (this.protectionLevel & DumpState.DUMP_DEXOPT) != 0;
    }

    public boolean isAppPredictor() {
        return (this.protectionLevel & DumpState.DUMP_COMPILER_STATS) != 0;
    }

    public void transfer(String origPackageName, String newPackageName) {
        if (!origPackageName.equals(this.sourcePackageName)) {
            return;
        }
        this.sourcePackageName = newPackageName;
        this.sourcePackageSetting = null;
        this.perm = null;
        PermissionInfo permissionInfo = this.pendingPermissionInfo;
        if (permissionInfo != null) {
            permissionInfo.packageName = newPackageName;
        }
        this.uid = 0;
        setGids(null, false);
    }

    public boolean addToTree(int protectionLevel, PermissionInfo info, BasePermission tree) {
        PackageParser.Permission permission;
        boolean changed = (this.protectionLevel == protectionLevel && (permission = this.perm) != null && this.uid == tree.uid && permission.owner.equals(tree.perm.owner) && comparePermissionInfos(this.perm.info, info)) ? false : true;
        this.protectionLevel = protectionLevel;
        PermissionInfo info2 = new PermissionInfo(info);
        info2.protectionLevel = protectionLevel;
        this.perm = new PackageParser.Permission(tree.perm.owner, info2);
        this.perm.info.packageName = tree.perm.info.packageName;
        this.uid = tree.uid;
        return changed;
    }

    public void updateDynamicPermission(Collection<BasePermission> permissionTrees) {
        BasePermission tree;
        PackageParser.Permission permission;
        if (this.sourcePackageSetting == null && this.pendingPermissionInfo != null && (tree = findPermissionTree(permissionTrees, this.name)) != null && (permission = tree.perm) != null) {
            this.sourcePackageSetting = tree.sourcePackageSetting;
            this.perm = new PackageParser.Permission(permission.owner, new PermissionInfo(this.pendingPermissionInfo));
            this.perm.info.packageName = tree.perm.info.packageName;
            this.perm.info.name = this.name;
            this.uid = tree.uid;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static BasePermission createOrUpdate(BasePermission bp, PackageParser.Permission p, PackageParser.Package pkg, Collection<BasePermission> permissionTrees, boolean chatty) {
        BasePermission bp2 = bp;
        PackageSettingBase pkgSetting = (PackageSettingBase) pkg.mExtras;
        boolean ownerChanged = false;
        if (bp2 != null && !Objects.equals(bp2.sourcePackageName, p.info.packageName)) {
            PackageParser.Permission permission = bp2.perm;
            boolean currentOwnerIsSystem = permission != null && permission.owner.isSystem();
            if (p.owner.isSystem()) {
                if (bp2.type == 1 && bp2.perm == null) {
                    bp2.sourcePackageSetting = pkgSetting;
                    bp2.perm = p;
                    bp2.uid = pkg.applicationInfo.uid;
                    bp2.sourcePackageName = p.info.packageName;
                    p.info.flags |= 1073741824;
                } else if (!currentOwnerIsSystem) {
                    String msg = "New decl " + p.owner + " of permission  " + p.info.name + " is system; overriding " + bp2.sourcePackageName;
                    PackageManagerService.reportSettingsProblem(5, msg);
                    ownerChanged = true;
                    bp2 = null;
                }
            }
        }
        if (bp2 == null) {
            bp2 = new BasePermission(p.info.name, p.info.packageName, 0);
        }
        boolean wasNonRuntime = !bp2.isRuntime();
        StringBuilder r = null;
        if (bp2.perm == null) {
            String str = bp2.sourcePackageName;
            if (str == null || str.equals(p.info.packageName)) {
                BasePermission tree = findPermissionTree(permissionTrees, p.info.name);
                if (tree == null || tree.sourcePackageName.equals(p.info.packageName)) {
                    bp2.sourcePackageSetting = pkgSetting;
                    bp2.perm = p;
                    bp2.uid = pkg.applicationInfo.uid;
                    bp2.sourcePackageName = p.info.packageName;
                    PermissionInfo permissionInfo = p.info;
                    permissionInfo.flags = 1073741824 | permissionInfo.flags;
                    if (chatty) {
                        if (0 == 0) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.info.name);
                    }
                } else {
                    Slog.w(TAG, "Permission " + p.info.name + " from package " + p.info.packageName + " ignored: base tree " + tree.name + " is from package " + tree.sourcePackageName);
                }
            } else {
                Slog.w(TAG, "Permission " + p.info.name + " from package " + p.info.packageName + " ignored: original from " + bp2.sourcePackageName);
            }
        } else if (chatty) {
            if (0 == 0) {
                r = new StringBuilder(256);
            } else {
                r.append(' ');
            }
            r.append("DUP:");
            r.append(p.info.name);
        }
        if (bp2.perm == p) {
            bp2.protectionLevel = p.info.protectionLevel;
        }
        if (bp2.isRuntime() && (ownerChanged || wasNonRuntime)) {
            bp2.mPermissionDefinitionChanged = true;
        }
        return bp2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static BasePermission enforcePermissionTree(Collection<BasePermission> permissionTrees, String permName, int callingUid) {
        BasePermission bp;
        if (permName != null && (bp = findPermissionTree(permissionTrees, permName)) != null) {
            if (bp.uid == UserHandle.getAppId(callingUid)) {
                return bp;
            }
            throw new SecurityException("Calling uid " + callingUid + " is not allowed to add to permission tree " + bp.name + " owned by uid " + bp.uid);
        }
        throw new SecurityException("No permission tree found for " + permName);
    }

    public void enforceDeclaredUsedAndRuntimeOrDevelopment(PackageParser.Package pkg) {
        PackageSetting pkgSetting = (PackageSetting) pkg.mExtras;
        PermissionsState permsState = pkgSetting.getPermissionsState();
        int index = pkg.requestedPermissions.indexOf(this.name);
        if (!permsState.hasRequestedPermission(this.name) && index == -1) {
            throw new SecurityException("Package " + pkg.packageName + " has not requested permission " + this.name);
        } else if (!isRuntime() && !isDevelopment()) {
            throw new SecurityException("Permission " + this.name + " requested by " + pkg.packageName + " is not a changeable permission type");
        }
    }

    private static BasePermission findPermissionTree(Collection<BasePermission> permissionTrees, String permName) {
        for (BasePermission bp : permissionTrees) {
            if (permName.startsWith(bp.name) && permName.length() > bp.name.length() && permName.charAt(bp.name.length()) == '.') {
                return bp;
            }
        }
        return null;
    }

    public PermissionInfo generatePermissionInfo(String groupName, int flags) {
        if (groupName == null) {
            PackageParser.Permission permission = this.perm;
            if (permission == null || permission.info.group == null) {
                return generatePermissionInfo(this.protectionLevel, flags);
            }
            return null;
        }
        PackageParser.Permission permission2 = this.perm;
        if (permission2 != null && groupName.equals(permission2.info.group)) {
            return PackageParser.generatePermissionInfo(this.perm, flags);
        }
        return null;
    }

    public PermissionInfo generatePermissionInfo(int adjustedProtectionLevel, int flags) {
        if (this.perm != null) {
            boolean protectionLevelChanged = this.protectionLevel != adjustedProtectionLevel;
            PermissionInfo permissionInfo = PackageParser.generatePermissionInfo(this.perm, flags);
            if (protectionLevelChanged && permissionInfo == this.perm.info) {
                PermissionInfo permissionInfo2 = new PermissionInfo(permissionInfo);
                permissionInfo2.protectionLevel = adjustedProtectionLevel;
                return permissionInfo2;
            }
            return permissionInfo;
        }
        PermissionInfo permissionInfo3 = new PermissionInfo();
        String str = this.name;
        permissionInfo3.name = str;
        permissionInfo3.packageName = this.sourcePackageName;
        permissionInfo3.nonLocalizedLabel = str;
        permissionInfo3.protectionLevel = this.protectionLevel;
        return permissionInfo3;
    }

    public static boolean readLPw(Map<String, BasePermission> out, XmlPullParser parser) {
        String tagName = parser.getName();
        if (tagName.equals(Settings.TAG_ITEM)) {
            String name = parser.getAttributeValue(null, Settings.ATTR_NAME);
            String sourcePackage = parser.getAttributeValue(null, "package");
            String ptype = parser.getAttributeValue(null, "type");
            if (name == null || sourcePackage == null) {
                PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: permissions has no name at " + parser.getPositionDescription());
                return false;
            }
            boolean dynamic = "dynamic".equals(ptype);
            BasePermission bp = out.get(name);
            if (bp == null || bp.type != 1) {
                bp = new BasePermission(name.intern(), sourcePackage, dynamic ? 2 : 0);
            }
            bp.protectionLevel = readInt(parser, null, "protection", 0);
            bp.protectionLevel = PermissionInfo.fixProtectionLevel(bp.protectionLevel);
            if (dynamic) {
                PermissionInfo pi = new PermissionInfo();
                pi.packageName = sourcePackage.intern();
                pi.name = name.intern();
                pi.icon = readInt(parser, null, "icon", 0);
                pi.nonLocalizedLabel = parser.getAttributeValue(null, "label");
                pi.protectionLevel = bp.protectionLevel;
                bp.pendingPermissionInfo = pi;
            }
            out.put(bp.name, bp);
            return true;
        }
        return false;
    }

    private static int readInt(XmlPullParser parser, String ns, String name, int defValue) {
        String v = parser.getAttributeValue(ns, name);
        if (v == null) {
            return defValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(5, "Error in package manager settings: attribute " + name + " has bad integer value " + v + " at " + parser.getPositionDescription());
            return defValue;
        }
    }

    public void writeLPr(XmlSerializer serializer) throws IOException {
        if (this.sourcePackageName == null) {
            return;
        }
        serializer.startTag(null, Settings.TAG_ITEM);
        serializer.attribute(null, Settings.ATTR_NAME, this.name);
        serializer.attribute(null, "package", this.sourcePackageName);
        int i = this.protectionLevel;
        if (i != 0) {
            serializer.attribute(null, "protection", Integer.toString(i));
        }
        if (this.type == 2) {
            PackageParser.Permission permission = this.perm;
            PermissionInfo pi = permission != null ? permission.info : this.pendingPermissionInfo;
            if (pi != null) {
                serializer.attribute(null, "type", "dynamic");
                if (pi.icon != 0) {
                    serializer.attribute(null, "icon", Integer.toString(pi.icon));
                }
                if (pi.nonLocalizedLabel != null) {
                    serializer.attribute(null, "label", pi.nonLocalizedLabel.toString());
                }
            }
        }
        serializer.endTag(null, Settings.TAG_ITEM);
    }

    private static boolean compareStrings(CharSequence s1, CharSequence s2) {
        if (s1 == null) {
            return s2 == null;
        } else if (s2 == null || s1.getClass() != s2.getClass()) {
            return false;
        } else {
            return s1.equals(s2);
        }
    }

    private static boolean comparePermissionInfos(PermissionInfo pi1, PermissionInfo pi2) {
        return pi1.icon == pi2.icon && pi1.logo == pi2.logo && pi1.protectionLevel == pi2.protectionLevel && compareStrings(pi1.name, pi2.name) && compareStrings(pi1.nonLocalizedLabel, pi2.nonLocalizedLabel) && compareStrings(pi1.packageName, pi2.packageName);
    }

    public boolean dumpPermissionsLPr(PrintWriter pw, String packageName, Set<String> permissionNames, boolean readEnforced, boolean printedSomething, DumpState dumpState) {
        if (packageName != null && !packageName.equals(this.sourcePackageName)) {
            return false;
        }
        if (permissionNames != null && !permissionNames.contains(this.name)) {
            return false;
        }
        if (!printedSomething) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Permissions:");
        }
        pw.print("  Permission [");
        pw.print(this.name);
        pw.print("] (");
        pw.print(Integer.toHexString(System.identityHashCode(this)));
        pw.println("):");
        pw.print("    sourcePackage=");
        pw.println(this.sourcePackageName);
        pw.print("    uid=");
        pw.print(this.uid);
        pw.print(" gids=");
        pw.print(Arrays.toString(computeGids(0)));
        pw.print(" type=");
        pw.print(this.type);
        pw.print(" prot=");
        pw.println(PermissionInfo.protectionToString(this.protectionLevel));
        if (this.perm != null) {
            pw.print("    perm=");
            pw.println(this.perm);
            if ((this.perm.info.flags & 1073741824) == 0 || (this.perm.info.flags & 2) != 0) {
                pw.print("    flags=0x");
                pw.println(Integer.toHexString(this.perm.info.flags));
            }
        }
        if (this.sourcePackageSetting != null) {
            pw.print("    packageSetting=");
            pw.println(this.sourcePackageSetting);
        }
        if ("android.permission.READ_EXTERNAL_STORAGE".equals(this.name)) {
            pw.print("    enforced=");
            pw.println(readEnforced);
            return true;
        }
        return true;
    }
}
