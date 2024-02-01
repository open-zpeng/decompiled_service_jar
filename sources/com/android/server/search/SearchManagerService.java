package com.android.server.search;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.ISearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.DumpState;
import com.android.server.statusbar.StatusBarManagerInternal;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/* loaded from: classes.dex */
public class SearchManagerService extends ISearchManager.Stub {
    private static final String TAG = "SearchManagerService";
    private final Context mContext;
    final Handler mHandler;
    @GuardedBy({"mSearchables"})
    private final SparseArray<Searchables> mSearchables = new SparseArray<>();

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        private SearchManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mService = new SearchManagerService(getContext());
            publishBinderService("search", this.mService);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(final int userId) {
            this.mService.mHandler.post(new Runnable() { // from class: com.android.server.search.SearchManagerService.Lifecycle.1
                @Override // java.lang.Runnable
                public void run() {
                    Lifecycle.this.mService.onUnlockUser(userId);
                }
            });
        }

        @Override // com.android.server.SystemService
        public void onCleanupUser(int userHandle) {
            this.mService.onCleanupUser(userHandle);
        }
    }

    public SearchManagerService(Context context) {
        this.mContext = context;
        new MyPackageMonitor().register(context, null, UserHandle.ALL, true);
        new GlobalSearchProviderObserver(context.getContentResolver());
        this.mHandler = BackgroundThread.getHandler();
    }

    private Searchables getSearchables(int userId) {
        return getSearchables(userId, false);
    }

    private Searchables getSearchables(int userId, boolean forceUpdate) {
        Searchables searchables;
        long token = Binder.clearCallingIdentity();
        try {
            UserManager um = (UserManager) this.mContext.getSystemService(UserManager.class);
            if (um.getUserInfo(userId) == null) {
                throw new IllegalStateException("User " + userId + " doesn't exist");
            } else if (!um.isUserUnlockingOrUnlocked(userId)) {
                throw new IllegalStateException("User " + userId + " isn't unlocked");
            } else {
                Binder.restoreCallingIdentity(token);
                synchronized (this.mSearchables) {
                    searchables = this.mSearchables.get(userId);
                    if (searchables == null) {
                        searchables = new Searchables(this.mContext, userId);
                        searchables.updateSearchableList();
                        this.mSearchables.append(userId, searchables);
                    } else if (forceUpdate) {
                        searchables.updateSearchableList();
                    }
                }
                return searchables;
            }
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(token);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUnlockUser(int userId) {
        try {
            getSearchables(userId, true);
        } catch (IllegalStateException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onCleanupUser(int userId) {
        synchronized (this.mSearchables) {
            this.mSearchables.remove(userId);
        }
    }

    /* loaded from: classes.dex */
    class MyPackageMonitor extends PackageMonitor {
        MyPackageMonitor() {
        }

        public void onSomePackagesChanged() {
            updateSearchables();
        }

        public void onPackageModified(String pkg) {
            updateSearchables();
        }

        private void updateSearchables() {
            int changingUserId = getChangingUserId();
            synchronized (SearchManagerService.this.mSearchables) {
                int i = 0;
                while (true) {
                    if (i >= SearchManagerService.this.mSearchables.size()) {
                        break;
                    } else if (changingUserId == SearchManagerService.this.mSearchables.keyAt(i)) {
                        ((Searchables) SearchManagerService.this.mSearchables.valueAt(i)).updateSearchableList();
                        break;
                    } else {
                        i++;
                    }
                }
            }
            Intent intent = new Intent("android.search.action.SEARCHABLES_CHANGED");
            intent.addFlags(603979776);
            SearchManagerService.this.mContext.sendBroadcastAsUser(intent, new UserHandle(changingUserId));
        }
    }

    /* loaded from: classes.dex */
    class GlobalSearchProviderObserver extends ContentObserver {
        private final ContentResolver mResolver;

        public GlobalSearchProviderObserver(ContentResolver resolver) {
            super(null);
            this.mResolver = resolver;
            this.mResolver.registerContentObserver(Settings.Secure.getUriFor("search_global_search_activity"), false, this);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            synchronized (SearchManagerService.this.mSearchables) {
                for (int i = 0; i < SearchManagerService.this.mSearchables.size(); i++) {
                    ((Searchables) SearchManagerService.this.mSearchables.valueAt(i)).updateSearchableList();
                }
            }
            Intent intent = new Intent("android.search.action.GLOBAL_SEARCH_ACTIVITY_CHANGED");
            intent.addFlags(536870912);
            SearchManagerService.this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    public SearchableInfo getSearchableInfo(ComponentName launchActivity) {
        if (launchActivity == null) {
            Log.e(TAG, "getSearchableInfo(), activity == null");
            return null;
        }
        return getSearchables(UserHandle.getCallingUserId()).getSearchableInfo(launchActivity);
    }

    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        return getSearchables(UserHandle.getCallingUserId()).getSearchablesInGlobalSearchList();
    }

    public List<ResolveInfo> getGlobalSearchActivities() {
        return getSearchables(UserHandle.getCallingUserId()).getGlobalSearchActivities();
    }

    public ComponentName getGlobalSearchActivity() {
        return getSearchables(UserHandle.getCallingUserId()).getGlobalSearchActivity();
    }

    public ComponentName getWebSearchActivity() {
        return getSearchables(UserHandle.getCallingUserId()).getWebSearchActivity();
    }

    public void launchAssist(Bundle args) {
        StatusBarManagerInternal statusBarManager = (StatusBarManagerInternal) LocalServices.getService(StatusBarManagerInternal.class);
        if (statusBarManager != null) {
            statusBarManager.startAssist(args);
        }
    }

    private ComponentName getLegacyAssistComponent(int userHandle) {
        try {
            int userHandle2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userHandle, true, false, "getLegacyAssistComponent", null);
            PackageManager pm = this.mContext.getPackageManager();
            Intent intentAssistProbe = new Intent("android.service.voice.VoiceInteractionService");
            List<ResolveInfo> infoListVis = pm.queryIntentServicesAsUser(intentAssistProbe, DumpState.DUMP_DEXOPT, userHandle2);
            if (infoListVis != null && !infoListVis.isEmpty()) {
                ResolveInfo rInfo = infoListVis.get(0);
                return new ComponentName(rInfo.serviceInfo.applicationInfo.packageName, rInfo.serviceInfo.name);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getLegacyAssistComponent: " + e);
            return null;
        }
    }

    public boolean launchLegacyAssist(String hint, int userHandle, Bundle args) {
        ComponentName comp = getLegacyAssistComponent(userHandle);
        if (comp == null) {
            return false;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent("android.service.voice.VoiceInteractionService");
            intent.setComponent(comp);
            IActivityTaskManager am = ActivityTaskManager.getService();
            if (args != null) {
                args.putInt("android.intent.extra.KEY_EVENT", 219);
            }
            intent.putExtras(args);
            boolean launchAssistIntent = am.launchAssistIntent(intent, 0, hint, userHandle, args);
            Binder.restoreCallingIdentity(ident);
            return launchAssistIntent;
        } catch (RemoteException e) {
            Binder.restoreCallingIdentity(ident);
            return true;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            PrintWriter indentingPrintWriter = new IndentingPrintWriter(pw, "  ");
            synchronized (this.mSearchables) {
                for (int i = 0; i < this.mSearchables.size(); i++) {
                    indentingPrintWriter.print("\nUser: ");
                    indentingPrintWriter.println(this.mSearchables.keyAt(i));
                    indentingPrintWriter.increaseIndent();
                    this.mSearchables.valueAt(i).dump(fd, indentingPrintWriter, args);
                    indentingPrintWriter.decreaseIndent();
                }
            }
        }
    }
}
