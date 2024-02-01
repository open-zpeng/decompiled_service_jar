package com.android.server.biometrics;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/* loaded from: classes.dex */
public class Utils {
    public static boolean isDebugEnabled(Context context, int targetUserId) {
        if (targetUserId == -10000) {
            return false;
        }
        if ((!Build.IS_ENG && !Build.IS_USERDEBUG) || Settings.Secure.getIntForUser(context.getContentResolver(), "biometric_debug_enabled", 0, targetUserId) == 0) {
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean isKeyguard(Context context, String clientPackage) {
        boolean hasPermission = context.checkCallingOrSelfPermission("android.permission.USE_BIOMETRIC_INTERNAL") == 0;
        ComponentName keyguardComponent = ComponentName.unflattenFromString(context.getResources().getString(17039753));
        String keyguardPackage = keyguardComponent != null ? keyguardComponent.getPackageName() : null;
        return hasPermission && keyguardPackage != null && keyguardPackage.equals(clientPackage);
    }
}
