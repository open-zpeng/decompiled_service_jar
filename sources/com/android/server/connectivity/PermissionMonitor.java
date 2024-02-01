package com.android.server.connectivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.net.util.NetworkConstants;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/* loaded from: classes.dex */
public class PermissionMonitor {
    private static final boolean DBG = true;
    private static final String TAG = "PermissionMonitor";
    private final Context mContext;
    private final INetworkManagementService mNetd;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private static final Boolean SYSTEM = Boolean.TRUE;
    private static final Boolean NETWORK = Boolean.FALSE;
    private final Set<Integer> mUsers = new HashSet();
    private final Map<Integer, Boolean> mApps = new HashMap();
    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() { // from class: com.android.server.connectivity.PermissionMonitor.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int user = intent.getIntExtra("android.intent.extra.user_handle", -10000);
            int appUid = intent.getIntExtra("android.intent.extra.UID", -1);
            Uri appData = intent.getData();
            String appName = appData != null ? appData.getSchemeSpecificPart() : null;
            if ("android.intent.action.USER_ADDED".equals(action)) {
                PermissionMonitor.this.onUserAdded(user);
            } else if ("android.intent.action.USER_REMOVED".equals(action)) {
                PermissionMonitor.this.onUserRemoved(user);
            } else if ("android.intent.action.PACKAGE_ADDED".equals(action)) {
                PermissionMonitor.this.onAppAdded(appName, appUid);
            } else if ("android.intent.action.PACKAGE_REMOVED".equals(action)) {
                PermissionMonitor.this.onAppRemoved(appUid);
            }
        }
    };

    public PermissionMonitor(Context context, INetworkManagementService netd) {
        this.mContext = context;
        this.mPackageManager = context.getPackageManager();
        this.mUserManager = UserManager.get(context);
        this.mNetd = netd;
    }

    public synchronized void startMonitoring() {
        Boolean permission;
        log("Monitoring");
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.USER_ADDED");
        intentFilter.addAction("android.intent.action.USER_REMOVED");
        this.mContext.registerReceiverAsUser(this.mIntentReceiver, UserHandle.ALL, intentFilter, null, null);
        IntentFilter intentFilter2 = new IntentFilter();
        intentFilter2.addAction("android.intent.action.PACKAGE_ADDED");
        intentFilter2.addAction("android.intent.action.PACKAGE_REMOVED");
        intentFilter2.addDataScheme("package");
        this.mContext.registerReceiverAsUser(this.mIntentReceiver, UserHandle.ALL, intentFilter2, null, null);
        List<PackageInfo> apps = this.mPackageManager.getInstalledPackages(4096);
        if (apps == null) {
            loge("No apps");
            return;
        }
        for (PackageInfo app : apps) {
            int uid = app.applicationInfo != null ? app.applicationInfo.uid : -1;
            if (uid >= 0) {
                boolean isNetwork = hasNetworkPermission(app);
                boolean hasRestrictedPermission = hasRestrictedNetworkPermission(app);
                if ((isNetwork || hasRestrictedPermission) && ((permission = this.mApps.get(Integer.valueOf(uid))) == null || permission == NETWORK)) {
                    this.mApps.put(Integer.valueOf(uid), Boolean.valueOf(hasRestrictedPermission));
                }
            }
        }
        List<UserInfo> users = this.mUserManager.getUsers(true);
        if (users != null) {
            for (UserInfo user : users) {
                this.mUsers.add(Integer.valueOf(user.id));
            }
        }
        log("Users: " + this.mUsers.size() + ", Apps: " + this.mApps.size());
        update(this.mUsers, this.mApps, true);
    }

    @VisibleForTesting
    boolean isPreinstalledSystemApp(PackageInfo app) {
        int flags = app.applicationInfo != null ? app.applicationInfo.flags : 0;
        return (flags & NetworkConstants.ICMPV6_ECHO_REPLY_TYPE) != 0;
    }

    @VisibleForTesting
    boolean hasPermission(PackageInfo app, String permission) {
        String[] strArr;
        if (app.requestedPermissions != null) {
            for (String p : app.requestedPermissions) {
                if (permission.equals(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasNetworkPermission(PackageInfo app) {
        return hasPermission(app, "android.permission.CHANGE_NETWORK_STATE");
    }

    private boolean hasRestrictedNetworkPermission(PackageInfo app) {
        return isPreinstalledSystemApp(app) || hasPermission(app, "android.permission.CONNECTIVITY_INTERNAL") || hasPermission(app, "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS");
    }

    private boolean hasUseBackgroundNetworksPermission(PackageInfo app) {
        return hasPermission(app, "android.permission.CHANGE_NETWORK_STATE") || hasPermission(app, "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS") || hasPermission(app, "android.permission.CONNECTIVITY_INTERNAL") || hasPermission(app, "android.permission.NETWORK_STACK") || isPreinstalledSystemApp(app);
    }

    public boolean hasUseBackgroundNetworksPermission(int uid) {
        String[] names = this.mPackageManager.getPackagesForUid(uid);
        if (names == null || names.length == 0) {
            return false;
        }
        try {
            int userId = UserHandle.getUserId(uid);
            PackageInfo app = this.mPackageManager.getPackageInfoAsUser(names[0], 4096, userId);
            return hasUseBackgroundNetworksPermission(app);
        } catch (PackageManager.NameNotFoundException e) {
            loge("NameNotFoundException " + names[0], e);
            return false;
        }
    }

    private int[] toIntArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i).intValue();
        }
        return array;
    }

    private void update(Set<Integer> users, Map<Integer, Boolean> apps, boolean add) {
        List<Integer> network = new ArrayList<>();
        List<Integer> system = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> app : apps.entrySet()) {
            List<Integer> list = app.getValue().booleanValue() ? system : network;
            for (Integer num : users) {
                int user = num.intValue();
                list.add(Integer.valueOf(UserHandle.getUid(user, app.getKey().intValue())));
            }
        }
        try {
            if (add) {
                this.mNetd.setPermission("NETWORK", toIntArray(network));
                this.mNetd.setPermission("SYSTEM", toIntArray(system));
            } else {
                this.mNetd.clearPermission(toIntArray(network));
                this.mNetd.clearPermission(toIntArray(system));
            }
        } catch (RemoteException e) {
            loge("Exception when updating permissions: " + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void onUserAdded(int user) {
        if (user < 0) {
            loge("Invalid user in onUserAdded: " + user);
            return;
        }
        this.mUsers.add(Integer.valueOf(user));
        Set<Integer> users = new HashSet<>();
        users.add(Integer.valueOf(user));
        update(users, this.mApps, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void onUserRemoved(int user) {
        if (user < 0) {
            loge("Invalid user in onUserRemoved: " + user);
            return;
        }
        this.mUsers.remove(Integer.valueOf(user));
        Set<Integer> users = new HashSet<>();
        users.add(Integer.valueOf(user));
        update(users, this.mApps, false);
    }

    private Boolean highestPermissionForUid(Boolean currentPermission, String name) {
        if (currentPermission == SYSTEM) {
            return currentPermission;
        }
        try {
            PackageInfo app = this.mPackageManager.getPackageInfo(name, 4096);
            boolean isNetwork = hasNetworkPermission(app);
            boolean hasRestrictedPermission = hasRestrictedNetworkPermission(app);
            if (isNetwork || hasRestrictedPermission) {
                return Boolean.valueOf(hasRestrictedPermission);
            }
            return currentPermission;
        } catch (PackageManager.NameNotFoundException e) {
            loge("NameNotFoundException " + name);
            return currentPermission;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void onAppAdded(String appName, int appUid) {
        if (!TextUtils.isEmpty(appName) && appUid >= 0) {
            Boolean permission = highestPermissionForUid(this.mApps.get(Integer.valueOf(appUid)), appName);
            if (permission != this.mApps.get(Integer.valueOf(appUid))) {
                this.mApps.put(Integer.valueOf(appUid), permission);
                Map<Integer, Boolean> apps = new HashMap<>();
                apps.put(Integer.valueOf(appUid), permission);
                update(this.mUsers, apps, true);
            }
            return;
        }
        loge("Invalid app in onAppAdded: " + appName + " | " + appUid);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void onAppRemoved(int appUid) {
        if (appUid < 0) {
            loge("Invalid app in onAppRemoved: " + appUid);
            return;
        }
        Map<Integer, Boolean> apps = new HashMap<>();
        Boolean permission = null;
        String[] packages = this.mPackageManager.getPackagesForUid(appUid);
        if (packages != null && packages.length > 0) {
            Boolean permission2 = null;
            for (String name : packages) {
                permission2 = highestPermissionForUid(permission2, name);
                if (permission2 == SYSTEM) {
                    return;
                }
            }
            permission = permission2;
        }
        if (permission == this.mApps.get(Integer.valueOf(appUid))) {
            return;
        }
        if (permission != null) {
            this.mApps.put(Integer.valueOf(appUid), permission);
            apps.put(Integer.valueOf(appUid), permission);
            update(this.mUsers, apps, true);
        } else {
            this.mApps.remove(Integer.valueOf(appUid));
            apps.put(Integer.valueOf(appUid), NETWORK);
            update(this.mUsers, apps, false);
        }
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

    private static void loge(String s, Throwable e) {
        Log.e(TAG, s, e);
    }
}
