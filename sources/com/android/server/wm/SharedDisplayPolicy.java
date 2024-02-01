package com.android.server.wm;

import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import com.xiaopeng.app.ActivityInfoManager;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.xpWindowManager;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/* loaded from: classes2.dex */
public class SharedDisplayPolicy {
    public static final String METADATA_STATE = "com.xiaopeng.metadata.shared.display.state";
    private static final boolean PACKAGE_SETTINGS_ENABLED = false;
    private static final String TAG = "SharedDisplayPolicy";
    private static final ConcurrentHashMap<Integer, Integer> sSharedScreenPolicies = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PolicyState> sSharedPackagePolicies = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PackageSettings> sSharedPackageSettings = new ConcurrentHashMap<>();

    public static void removePackageSettings(int screenId, String topPackageName) {
    }

    public static void setPackageSettings(String packageName, Bundle extras) {
    }

    public static PackageSettings getPackageSettings(String packageName) {
        return null;
    }

    public static boolean isPackageSettingsEmpty() {
        return sSharedPackageSettings.isEmpty();
    }

    public static int getScreenPolicy(int screenId) {
        return sSharedScreenPolicies.getOrDefault(Integer.valueOf(screenId), 1).intValue();
    }

    public static void setScreenPolicy(int screenId, int policy) {
        sSharedScreenPolicies.put(Integer.valueOf(screenId), Integer.valueOf(policy));
    }

    public static int getPackagePolicy(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return -1;
        }
        initPackagePolicyIfNeed(packageName);
        PolicyState state = sSharedPackagePolicies.getOrDefault(packageName, null);
        if (state == null) {
            return -1;
        }
        return state.packageState == 1 ? state.dynamicState : state.packageState == 0 ? 0 : -1;
    }

    public static void setPackagePolicy(String packageName, int policy) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        initPackagePolicyIfNeed(packageName);
        PolicyState state = sSharedPackagePolicies.getOrDefault(packageName, null);
        if (state == null || state.packageState == 0) {
            return;
        }
        state.dynamicState = policy;
        sSharedPackagePolicies.put(packageName, state);
    }

    private static void initPackagePolicyIfNeed(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        PolicyState state = sSharedPackagePolicies.getOrDefault(packageName, null);
        boolean initialized = state != null ? state.initialized : false;
        if (initialized) {
            return;
        }
        if (state == null) {
            state = new PolicyState(packageName);
        }
        state.packageState = packageEnable(packageName) ? 1 : 0;
        state.initialized = true;
        sSharedPackagePolicies.put(packageName, state);
    }

    private static boolean packageEnable(String packageName) {
        List<ApplicationInfo> apps;
        if (SharedDisplayManager.enable() && !TextUtils.isEmpty(packageName)) {
            if (ActivityInfoManager.isSystemApplication(packageName)) {
                try {
                    IPackageManager pm = AppGlobals.getPackageManager();
                    ParceledListSlice<ApplicationInfo> parceledList = pm.getInstalledApplications(128, UserHandle.myUserId());
                    if (parceledList == null) {
                        apps = Collections.emptyList();
                    } else {
                        apps = parceledList.getList();
                    }
                } catch (Exception e) {
                }
                if (apps != null && apps.size() != 0) {
                    for (ApplicationInfo ai : apps) {
                        if (ai != null && ai.metaData != null && packageName.equals(ai.packageName) && ai.metaData.containsKey(METADATA_STATE)) {
                            int state = ai.metaData.getInt(METADATA_STATE);
                            return state == 1;
                        }
                    }
                    return FeatureOption.FO_PROJECT_UI_TYPE == 2;
                }
                return false;
            }
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class PolicyState {
        public int defaultState;
        public String packageName;
        public int packageState;
        public boolean initialized = false;
        public int dynamicState = 1;

        public PolicyState(String packageName) {
            this.packageName = packageName;
        }
    }

    /* loaded from: classes2.dex */
    public static final class PackageSettings {
        public int flags;
        public String packageName;
        public int screenId;
        public int sharedId;

        public static PackageSettings from(String packageName, Bundle extras) {
            if (extras == null) {
                return null;
            }
            String _packageName = extras.getString("packageName", packageName);
            int sharedId = extras.getInt("sharedId", getSharedId(_packageName));
            if (TextUtils.isEmpty(_packageName)) {
                return null;
            }
            PackageSettings settings = new PackageSettings();
            settings.packageName = _packageName;
            settings.sharedId = sharedId;
            settings.screenId = SharedDisplayManager.findScreenId(sharedId);
            settings.flags = extras.getInt(xpInputManagerService.InputPolicyKey.KEY_FLAGS, 0);
            return settings;
        }

        public static int getSharedId(String packageName) {
            try {
                return xpWindowManager.getWindowManager().getSharedId(packageName);
            } catch (Exception e) {
                return -1;
            }
        }

        public static boolean hasFlag(PackageSettings settings, int flag) {
            return flag > 0 && settings != null && (settings.flags & flag) == flag;
        }

        public static boolean hasFlag(PackageSettings settings, int flag, int screenId) {
            return flag > 0 && settings != null && screenId == settings.screenId && (settings.flags & flag) == flag;
        }

        public String toString() {
            StringBuffer buffer = new StringBuffer("");
            buffer.append("PackageSettings");
            buffer.append(" packageName=");
            buffer.append(this.packageName);
            buffer.append(" sharedId=");
            buffer.append(this.sharedId);
            buffer.append(" screenId=");
            buffer.append(this.screenId);
            buffer.append(" flags=0x");
            buffer.append(Integer.toHexString(this.flags));
            return buffer.toString();
        }
    }
}
