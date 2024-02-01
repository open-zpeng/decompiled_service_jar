package com.android.server.policy.role;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import com.android.internal.util.CollectionUtils;
import com.android.server.LocalServices;
import com.android.server.role.RoleManagerService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/* loaded from: classes.dex */
public class LegacyRoleResolutionPolicy implements RoleManagerService.RoleHoldersResolver {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "LegacyRoleResolutionPol";
    private final Context mContext;

    public LegacyRoleResolutionPolicy(Context context) {
        this.mContext = context;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    @Override // com.android.server.role.RoleManagerService.RoleHoldersResolver
    public List<String> getRoleHolders(String roleName, int userId) {
        char c;
        String packageName;
        String packageName2;
        String packageName3;
        switch (roleName.hashCode()) {
            case 443215373:
                if (roleName.equals("android.app.role.SMS")) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case 666116809:
                if (roleName.equals("android.app.role.DIALER")) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 854448779:
                if (roleName.equals("android.app.role.HOME")) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case 1634943122:
                if (roleName.equals("android.app.role.ASSISTANT")) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case 1834128197:
                if (roleName.equals("android.app.role.EMERGENCY")) {
                    c = 5;
                    break;
                }
                c = 65535;
                break;
            case 1965677020:
                if (roleName.equals("android.app.role.BROWSER")) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        if (c == 0) {
            String setting = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "assistant", userId);
            if (setting != null) {
                if (!setting.isEmpty()) {
                    ComponentName componentName = ComponentName.unflattenFromString(setting);
                    packageName = componentName != null ? componentName.getPackageName() : null;
                } else {
                    packageName = null;
                }
            } else if (this.mContext.getPackageManager().isDeviceUpgrading()) {
                String defaultAssistant = this.mContext.getString(17039393);
                packageName = TextUtils.isEmpty(defaultAssistant) ? null : defaultAssistant;
            } else {
                packageName = null;
            }
            return CollectionUtils.singletonOrEmpty(packageName);
        } else if (c == 1) {
            PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
            String packageName4 = packageManagerInternal.removeLegacyDefaultBrowserPackageName(userId);
            return CollectionUtils.singletonOrEmpty(packageName4);
        } else if (c == 2) {
            String setting2 = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "dialer_default_application", userId);
            if (!TextUtils.isEmpty(setting2)) {
                packageName2 = setting2;
            } else if (this.mContext.getPackageManager().isDeviceUpgrading()) {
                packageName2 = this.mContext.getString(17039395);
            } else {
                packageName2 = null;
            }
            return CollectionUtils.singletonOrEmpty(packageName2);
        } else if (c == 3) {
            String setting3 = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "sms_default_application", userId);
            if (!TextUtils.isEmpty(setting3)) {
                packageName3 = setting3;
            } else if (this.mContext.getPackageManager().isDeviceUpgrading()) {
                packageName3 = this.mContext.getString(17039396);
            } else {
                packageName3 = null;
            }
            return CollectionUtils.singletonOrEmpty(packageName3);
        } else if (c == 4) {
            PackageManager packageManager = this.mContext.getPackageManager();
            List<ResolveInfo> resolveInfos = new ArrayList<>();
            ComponentName componentName2 = packageManager.getHomeActivities(resolveInfos);
            String packageName5 = componentName2 != null ? componentName2.getPackageName() : null;
            return CollectionUtils.singletonOrEmpty(packageName5);
        } else if (c == 5) {
            String defaultEmergencyApp = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "emergency_assistance_application", userId);
            return CollectionUtils.singletonOrEmpty(defaultEmergencyApp);
        } else {
            Slog.e(LOG_TAG, "Don't know how to find legacy role holders for " + roleName);
            return Collections.emptyList();
        }
    }
}
