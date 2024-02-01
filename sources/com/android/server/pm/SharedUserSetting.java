package com.android.server.pm;

import android.content.pm.PackageParser;
import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;
import com.android.server.pm.permission.PermissionsState;
import com.android.server.slice.SliceClientPermissions;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
/* loaded from: classes.dex */
public final class SharedUserSetting extends SettingBase {
    final String name;
    final ArraySet<PackageSetting> packages;
    int seInfoTargetSdkVersion;
    final PackageSignatures signatures;
    Boolean signaturesChanged;
    int uidFlags;
    int uidPrivateFlags;
    int userId;

    @Override // com.android.server.pm.SettingBase
    public /* bridge */ /* synthetic */ void copyFrom(SettingBase settingBase) {
        super.copyFrom(settingBase);
    }

    @Override // com.android.server.pm.SettingBase
    public /* bridge */ /* synthetic */ PermissionsState getPermissionsState() {
        return super.getPermissionsState();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SharedUserSetting(String _name, int _pkgFlags, int _pkgPrivateFlags) {
        super(_pkgFlags, _pkgPrivateFlags);
        this.packages = new ArraySet<>();
        this.signatures = new PackageSignatures();
        this.uidFlags = _pkgFlags;
        this.uidPrivateFlags = _pkgPrivateFlags;
        this.name = _name;
        this.seInfoTargetSdkVersion = 10000;
    }

    public String toString() {
        return "SharedUserSetting{" + Integer.toHexString(System.identityHashCode(this)) + " " + this.name + SliceClientPermissions.SliceAuthority.DELIMITER + this.userId + "}";
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1120986464257L, this.userId);
        proto.write(1138166333442L, this.name);
        proto.end(token);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removePackage(PackageSetting packageSetting) {
        if (this.packages.remove(packageSetting)) {
            if ((this.pkgFlags & packageSetting.pkgFlags) != 0) {
                int aggregatedFlags = this.uidFlags;
                Iterator<PackageSetting> it = this.packages.iterator();
                while (it.hasNext()) {
                    PackageSetting ps = it.next();
                    aggregatedFlags |= ps.pkgFlags;
                }
                setFlags(aggregatedFlags);
            }
            int aggregatedFlags2 = this.pkgPrivateFlags;
            if ((aggregatedFlags2 & packageSetting.pkgPrivateFlags) != 0) {
                int aggregatedPrivateFlags = this.uidPrivateFlags;
                Iterator<PackageSetting> it2 = this.packages.iterator();
                while (it2.hasNext()) {
                    PackageSetting ps2 = it2.next();
                    aggregatedPrivateFlags |= ps2.pkgPrivateFlags;
                }
                setPrivateFlags(aggregatedPrivateFlags);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addPackage(PackageSetting packageSetting) {
        if (this.packages.size() == 0 && packageSetting.pkg != null) {
            this.seInfoTargetSdkVersion = packageSetting.pkg.applicationInfo.targetSdkVersion;
        }
        if (this.packages.add(packageSetting)) {
            setFlags(this.pkgFlags | packageSetting.pkgFlags);
            setPrivateFlags(this.pkgPrivateFlags | packageSetting.pkgPrivateFlags);
        }
    }

    public List<PackageParser.Package> getPackages() {
        if (this.packages == null || this.packages.size() == 0) {
            return null;
        }
        ArrayList<PackageParser.Package> pkgList = new ArrayList<>(this.packages.size());
        Iterator<PackageSetting> it = this.packages.iterator();
        while (it.hasNext()) {
            PackageSetting ps = it.next();
            if (ps != null && ps.pkg != null) {
                pkgList.add(ps.pkg);
            }
        }
        return pkgList;
    }

    public boolean isPrivileged() {
        return (this.pkgPrivateFlags & 8) != 0;
    }

    public void fixSeInfoLocked() {
        List<PackageParser.Package> pkgList = getPackages();
        if (pkgList == null || pkgList.size() == 0) {
            return;
        }
        for (PackageParser.Package pkg : pkgList) {
            if (pkg.applicationInfo.targetSdkVersion < this.seInfoTargetSdkVersion) {
                this.seInfoTargetSdkVersion = pkg.applicationInfo.targetSdkVersion;
            }
        }
        for (PackageParser.Package pkg2 : pkgList) {
            boolean isPrivileged = isPrivileged() | pkg2.isPrivileged();
            pkg2.applicationInfo.seInfo = SELinuxMMAC.getSeInfo(pkg2, isPrivileged, pkg2.applicationInfo.targetSandboxVersion, this.seInfoTargetSdkVersion);
        }
    }
}
