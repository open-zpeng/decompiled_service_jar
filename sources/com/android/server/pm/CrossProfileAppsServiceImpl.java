package com.android.server.pm;

import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ICrossProfileApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
public class CrossProfileAppsServiceImpl extends ICrossProfileApps.Stub {
    private static final String TAG = "CrossProfileAppsService";
    private Context mContext;
    private Injector mInjector;

    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface Injector {
        long clearCallingIdentity();

        ActivityManagerInternal getActivityManagerInternal();

        AppOpsManager getAppOpsManager();

        int getCallingUid();

        UserHandle getCallingUserHandle();

        int getCallingUserId();

        PackageManager getPackageManager();

        PackageManagerInternal getPackageManagerInternal();

        UserManager getUserManager();

        void restoreCallingIdentity(long j);
    }

    public CrossProfileAppsServiceImpl(Context context) {
        this(context, new InjectorImpl(context));
    }

    @VisibleForTesting
    CrossProfileAppsServiceImpl(Context context, Injector injector) {
        this.mContext = context;
        this.mInjector = injector;
    }

    public List<UserHandle> getTargetUserProfiles(String callingPackage) {
        Preconditions.checkNotNull(callingPackage);
        verifyCallingPackage(callingPackage);
        return getTargetUserProfilesUnchecked(callingPackage, this.mInjector.getCallingUserId());
    }

    public void startActivityAsUser(IApplicationThread caller, String callingPackage, ComponentName component, UserHandle user) throws RemoteException {
        Preconditions.checkNotNull(callingPackage);
        Preconditions.checkNotNull(component);
        Preconditions.checkNotNull(user);
        verifyCallingPackage(callingPackage);
        List<UserHandle> allowedTargetUsers = getTargetUserProfilesUnchecked(callingPackage, this.mInjector.getCallingUserId());
        if (!allowedTargetUsers.contains(user)) {
            throw new SecurityException(callingPackage + " cannot access unrelated user " + user.getIdentifier());
        } else if (!callingPackage.equals(component.getPackageName())) {
            throw new SecurityException(callingPackage + " attempts to start an activity in other package - " + component.getPackageName());
        } else {
            int callingUid = this.mInjector.getCallingUid();
            Intent launchIntent = new Intent("android.intent.action.MAIN");
            launchIntent.addCategory("android.intent.category.LAUNCHER");
            launchIntent.addFlags(270532608);
            launchIntent.setPackage(component.getPackageName());
            verifyActivityCanHandleIntentAndExported(launchIntent, component, callingUid, user);
            launchIntent.setPackage(null);
            launchIntent.setComponent(component);
            this.mInjector.getActivityManagerInternal().startActivityAsUser(caller, callingPackage, launchIntent, ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle(), user.getIdentifier());
        }
    }

    private List<UserHandle> getTargetUserProfilesUnchecked(String callingPackage, int callingUserId) {
        long ident = this.mInjector.clearCallingIdentity();
        try {
            int[] enabledProfileIds = this.mInjector.getUserManager().getEnabledProfileIds(callingUserId);
            List<UserHandle> targetProfiles = new ArrayList<>();
            for (int userId : enabledProfileIds) {
                if (userId != callingUserId && isPackageEnabled(callingPackage, userId)) {
                    targetProfiles.add(UserHandle.of(userId));
                }
            }
            return targetProfiles;
        } finally {
            this.mInjector.restoreCallingIdentity(ident);
        }
    }

    private boolean isPackageEnabled(String packageName, int userId) {
        boolean z;
        int callingUid = this.mInjector.getCallingUid();
        long ident = this.mInjector.clearCallingIdentity();
        try {
            PackageInfo info = this.mInjector.getPackageManagerInternal().getPackageInfo(packageName, 786432, callingUid, userId);
            if (info != null) {
                if (info.applicationInfo.enabled) {
                    z = true;
                    return z;
                }
            }
            z = false;
            return z;
        } finally {
            this.mInjector.restoreCallingIdentity(ident);
        }
    }

    private void verifyActivityCanHandleIntentAndExported(Intent launchIntent, ComponentName component, int callingUid, UserHandle user) {
        long ident = this.mInjector.clearCallingIdentity();
        try {
            List<ResolveInfo> apps = this.mInjector.getPackageManagerInternal().queryIntentActivities(launchIntent, 786432, callingUid, user.getIdentifier());
            int size = apps.size();
            for (int i = 0; i < size; i++) {
                ActivityInfo activityInfo = apps.get(i).activityInfo;
                if (TextUtils.equals(activityInfo.packageName, component.getPackageName()) && TextUtils.equals(activityInfo.name, component.getClassName()) && activityInfo.exported) {
                    return;
                }
            }
            throw new SecurityException("Attempt to launch activity without  category Intent.CATEGORY_LAUNCHER or activity is not exported" + component);
        } finally {
            this.mInjector.restoreCallingIdentity(ident);
        }
    }

    private void verifyCallingPackage(String callingPackage) {
        this.mInjector.getAppOpsManager().checkPackage(this.mInjector.getCallingUid(), callingPackage);
    }

    /* loaded from: classes.dex */
    private static class InjectorImpl implements Injector {
        private Context mContext;

        public InjectorImpl(Context context) {
            this.mContext = context;
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public int getCallingUserId() {
            return UserHandle.getCallingUserId();
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public UserHandle getCallingUserHandle() {
            return Binder.getCallingUserHandle();
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public void restoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public UserManager getUserManager() {
            return (UserManager) this.mContext.getSystemService(UserManager.class);
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public PackageManagerInternal getPackageManagerInternal() {
            return (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public PackageManager getPackageManager() {
            return this.mContext.getPackageManager();
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public AppOpsManager getAppOpsManager() {
            return (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        }

        @Override // com.android.server.pm.CrossProfileAppsServiceImpl.Injector
        public ActivityManagerInternal getActivityManagerInternal() {
            return (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        }
    }
}
