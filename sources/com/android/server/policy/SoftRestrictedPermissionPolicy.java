package com.android.server.policy;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;

/* loaded from: classes.dex */
public abstract class SoftRestrictedPermissionPolicy {
    private static final SoftRestrictedPermissionPolicy DUMMY_POLICY = new SoftRestrictedPermissionPolicy() { // from class: com.android.server.policy.SoftRestrictedPermissionPolicy.1
        @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
        public int resolveAppOp() {
            return -1;
        }

        @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
        public int getDesiredOpMode() {
            return 3;
        }

        @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
        public boolean shouldSetAppOpIfNotDefault() {
            return false;
        }

        @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
        public boolean canBeGranted() {
            return true;
        }
    };
    private static final int FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT = 14336;

    public abstract boolean canBeGranted();

    public abstract int getDesiredOpMode();

    public abstract int resolveAppOp();

    public abstract boolean shouldSetAppOpIfNotDefault();

    private static int getMinimumTargetSDK(Context context, ApplicationInfo appInfo, UserHandle user) {
        PackageManager pm = context.getPackageManager();
        int minimumTargetSDK = appInfo.targetSdkVersion;
        String[] uidPkgs = pm.getPackagesForUid(appInfo.uid);
        if (uidPkgs != null) {
            int minimumTargetSDK2 = minimumTargetSDK;
            for (String uidPkg : uidPkgs) {
                if (!uidPkg.equals(appInfo.packageName)) {
                    try {
                        ApplicationInfo uidPkgInfo = pm.getApplicationInfoAsUser(uidPkg, 0, user);
                        minimumTargetSDK2 = Integer.min(minimumTargetSDK2, uidPkgInfo.targetSdkVersion);
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                }
            }
            return minimumTargetSDK2;
        }
        return minimumTargetSDK;
    }

    public static SoftRestrictedPermissionPolicy forPermission(Context context, ApplicationInfo appInfo, UserHandle user, String permission) {
        char c;
        final boolean applyRestriction;
        final boolean isWhiteListed;
        final boolean hasAnyRequestedLegacyExternalStorage;
        final int targetSDK;
        boolean hasAnyRequestedLegacyExternalStorage2;
        final boolean isWhiteListed2;
        final int flags;
        int hashCode = permission.hashCode();
        if (hashCode != -406040016) {
            if (hashCode == 1365911975 && permission.equals("android.permission.WRITE_EXTERNAL_STORAGE")) {
                c = 1;
            }
            c = 65535;
        } else {
            if (permission.equals("android.permission.READ_EXTERNAL_STORAGE")) {
                c = 0;
            }
            c = 65535;
        }
        if (c != 0) {
            if (c == 1) {
                if (appInfo != null) {
                    isWhiteListed2 = (context.getPackageManager().getPermissionFlags(permission, appInfo.packageName, user) & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                    flags = getMinimumTargetSDK(context, appInfo, user);
                } else {
                    isWhiteListed2 = false;
                    flags = 0;
                }
                return new SoftRestrictedPermissionPolicy() { // from class: com.android.server.policy.SoftRestrictedPermissionPolicy.3
                    @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
                    public int resolveAppOp() {
                        return -1;
                    }

                    @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
                    public int getDesiredOpMode() {
                        return 3;
                    }

                    @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
                    public boolean shouldSetAppOpIfNotDefault() {
                        return false;
                    }

                    @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
                    public boolean canBeGranted() {
                        return isWhiteListed2 || flags >= 29;
                    }
                };
            }
            return DUMMY_POLICY;
        }
        if (appInfo != null) {
            PackageManager pm = context.getPackageManager();
            int flags2 = pm.getPermissionFlags(permission, appInfo.packageName, user);
            applyRestriction = (flags2 & 16384) != 0;
            isWhiteListed = (flags2 & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
            targetSDK = getMinimumTargetSDK(context, appInfo, user);
            boolean hasAnyRequestedLegacyExternalStorage3 = appInfo.hasRequestedLegacyExternalStorage();
            String[] uidPkgs = pm.getPackagesForUid(appInfo.uid);
            if (uidPkgs == null) {
                hasAnyRequestedLegacyExternalStorage2 = hasAnyRequestedLegacyExternalStorage3;
            } else {
                hasAnyRequestedLegacyExternalStorage2 = hasAnyRequestedLegacyExternalStorage3;
                for (String uidPkg : uidPkgs) {
                    if (!uidPkg.equals(appInfo.packageName)) {
                        try {
                            ApplicationInfo uidPkgInfo = pm.getApplicationInfoAsUser(uidPkg, 0, user);
                            hasAnyRequestedLegacyExternalStorage2 |= uidPkgInfo.hasRequestedLegacyExternalStorage();
                        } catch (PackageManager.NameNotFoundException e) {
                        }
                    }
                }
            }
            hasAnyRequestedLegacyExternalStorage = hasAnyRequestedLegacyExternalStorage2;
        } else {
            applyRestriction = false;
            isWhiteListed = false;
            hasAnyRequestedLegacyExternalStorage = false;
            targetSDK = 0;
        }
        return new SoftRestrictedPermissionPolicy() { // from class: com.android.server.policy.SoftRestrictedPermissionPolicy.2
            @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
            public int resolveAppOp() {
                return 87;
            }

            @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
            public int getDesiredOpMode() {
                if (applyRestriction) {
                    return 3;
                }
                if (hasAnyRequestedLegacyExternalStorage) {
                    return 0;
                }
                return 1;
            }

            @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
            public boolean shouldSetAppOpIfNotDefault() {
                return getDesiredOpMode() != 1;
            }

            @Override // com.android.server.policy.SoftRestrictedPermissionPolicy
            public boolean canBeGranted() {
                if (isWhiteListed || targetSDK >= 29) {
                    return true;
                }
                return false;
            }
        };
    }
}
