package com.android.server.accounts;

import android.accounts.Account;
import android.accounts.AccountAndUser;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManagerInternal;
import android.accounts.AccountManagerResponse;
import android.accounts.AuthenticatorDescription;
import android.accounts.CantAddAccountActivity;
import android.accounts.ChooseAccountActivity;
import android.accounts.GrantCredentialsPermissionActivity;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.accounts.IAccountManager;
import android.accounts.IAccountManagerResponse;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.net.watchlist.WatchlistLoggingHandler;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.google.android.collect.Lists;
import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
/* loaded from: classes.dex */
public class AccountManagerService extends IAccountManager.Stub implements RegisteredServicesCacheListener<AuthenticatorDescription> {
    private static final Intent ACCOUNTS_CHANGED_INTENT = new Intent("android.accounts.LOGIN_ACCOUNTS_CHANGED");
    private static final Account[] EMPTY_ACCOUNT_ARRAY;
    private static final int MESSAGE_COPY_SHARED_ACCOUNT = 4;
    private static final int MESSAGE_TIMED_OUT = 3;
    private static final String PRE_N_DATABASE_NAME = "accounts.db";
    private static final int SIGNATURE_CHECK_MATCH = 1;
    private static final int SIGNATURE_CHECK_MISMATCH = 0;
    private static final int SIGNATURE_CHECK_UID_MATCH = 2;
    private static final String TAG = "AccountManagerService";
    private static AtomicReference<AccountManagerService> sThis;
    private final AppOpsManager mAppOpsManager;
    private final IAccountAuthenticatorCache mAuthenticatorCache;
    final Context mContext;
    final MessageHandler mHandler;
    private final Injector mInjector;
    private final PackageManager mPackageManager;
    private UserManager mUserManager;
    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<>();
    private final SparseArray<UserAccounts> mUsers = new SparseArray<>();
    private final SparseBooleanArray mLocalUnlockedUsers = new SparseBooleanArray();
    private final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private CopyOnWriteArrayList<AccountManagerInternal.OnAppPermissionChangeListener> mAppPermissionChangeListeners = new CopyOnWriteArrayList<>();

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        private AccountManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mService = new AccountManagerService(new Injector(getContext()));
            publishBinderService("account", this.mService);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            this.mService.onUnlockUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userHandle) {
            Slog.i(AccountManagerService.TAG, "onStopUser " + userHandle);
            this.mService.purgeUserData(userHandle);
        }
    }

    static {
        ACCOUNTS_CHANGED_INTENT.setFlags(83886080);
        sThis = new AtomicReference<>();
        EMPTY_ACCOUNT_ARRAY = new Account[0];
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class UserAccounts {
        final AccountsDb accountsDb;
        private SQLiteStatement statementForLogging;
        private final int userId;
        private final HashMap<Pair<Pair<Account, String>, Integer>, NotificationId> credentialsPermissionNotificationIds = new HashMap<>();
        private final HashMap<Account, NotificationId> signinRequiredNotificationIds = new HashMap<>();
        final Object cacheLock = new Object();
        final Object dbLock = new Object();
        final HashMap<String, Account[]> accountCache = new LinkedHashMap();
        private final Map<Account, Map<String, String>> userDataCache = new HashMap();
        private final Map<Account, Map<String, String>> authTokenCache = new HashMap();
        private final TokenCache accountTokenCaches = new TokenCache();
        private final Map<Account, Map<String, Integer>> visibilityCache = new HashMap();
        private final Map<String, Map<String, Integer>> mReceiversForType = new HashMap();
        private final HashMap<Account, AtomicReference<String>> previousNameCache = new HashMap<>();
        private int debugDbInsertionPoint = -1;

        UserAccounts(Context context, int userId, File preNDbFile, File deDbFile) {
            this.userId = userId;
            synchronized (this.dbLock) {
                synchronized (this.cacheLock) {
                    this.accountsDb = AccountsDb.create(context, userId, preNDbFile, deDbFile);
                }
            }
        }
    }

    public static AccountManagerService getSingleton() {
        return sThis.get();
    }

    /* JADX WARN: Type inference failed for: r3v3, types: [com.android.server.accounts.AccountManagerService$3] */
    public AccountManagerService(Injector injector) {
        this.mInjector = injector;
        this.mContext = injector.getContext();
        this.mPackageManager = this.mContext.getPackageManager();
        this.mAppOpsManager = (AppOpsManager) this.mContext.getSystemService(AppOpsManager.class);
        this.mHandler = new MessageHandler(injector.getMessageHandlerLooper());
        this.mAuthenticatorCache = this.mInjector.getAccountAuthenticatorCache();
        this.mAuthenticatorCache.setListener(this, null);
        sThis.set(this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.PACKAGE_REMOVED");
        intentFilter.addDataScheme("package");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.accounts.AccountManagerService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context1, Intent intent) {
                if (!intent.getBooleanExtra("android.intent.extra.REPLACING", false)) {
                    final String removedPackageName = intent.getData().getSchemeSpecificPart();
                    Runnable purgingRunnable = new Runnable() { // from class: com.android.server.accounts.AccountManagerService.1.1
                        @Override // java.lang.Runnable
                        public void run() {
                            AccountManagerService.this.purgeOldGrantsAll();
                            AccountManagerService.this.removeVisibilityValuesForPackage(removedPackageName);
                        }
                    };
                    AccountManagerService.this.mHandler.post(purgingRunnable);
                }
            }
        }, intentFilter);
        injector.addLocalService(new AccountManagerInternalImpl());
        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction("android.intent.action.USER_REMOVED");
        this.mContext.registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.accounts.AccountManagerService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                int userId;
                String action = intent.getAction();
                if (!"android.intent.action.USER_REMOVED".equals(action) || (userId = intent.getIntExtra("android.intent.extra.user_handle", -1)) < 1) {
                    return;
                }
                Slog.i(AccountManagerService.TAG, "User " + userId + " removed");
                AccountManagerService.this.purgeUserData(userId);
            }
        }, UserHandle.ALL, userFilter, null, null);
        new PackageMonitor() { // from class: com.android.server.accounts.AccountManagerService.3
            public void onPackageAdded(String packageName, int uid) {
                AccountManagerService.this.cancelAccountAccessRequestNotificationIfNeeded(uid, true);
            }

            public void onPackageUpdateFinished(String packageName, int uid) {
                AccountManagerService.this.cancelAccountAccessRequestNotificationIfNeeded(uid, true);
            }
        }.register(this.mContext, this.mHandler.getLooper(), UserHandle.ALL, true);
        this.mAppOpsManager.startWatchingMode(62, (String) null, (AppOpsManager.OnOpChangedListener) new AppOpsManager.OnOpChangedInternalListener() { // from class: com.android.server.accounts.AccountManagerService.4
            public void onOpChanged(int op, String packageName) {
                try {
                    int userId = ActivityManager.getCurrentUser();
                    int uid = AccountManagerService.this.mPackageManager.getPackageUidAsUser(packageName, userId);
                    int mode = AccountManagerService.this.mAppOpsManager.checkOpNoThrow(62, uid, packageName);
                    if (mode == 0) {
                        long identity = Binder.clearCallingIdentity();
                        AccountManagerService.this.cancelAccountAccessRequestNotificationIfNeeded(packageName, uid, true);
                        Binder.restoreCallingIdentity(identity);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
        });
        this.mPackageManager.addOnPermissionsChangeListener(new PackageManager.OnPermissionsChangedListener() { // from class: com.android.server.accounts.-$$Lambda$AccountManagerService$c6GExIY3Vh2fORdBziuAPJbExac
            public final void onPermissionsChanged(int i) {
                AccountManagerService.lambda$new$0(AccountManagerService.this, i);
            }
        });
    }

    public static /* synthetic */ void lambda$new$0(AccountManagerService accountManagerService, int uid) {
        Throwable th;
        String[] packageNames = accountManagerService.mPackageManager.getPackagesForUid(uid);
        if (packageNames != null) {
            int userId = UserHandle.getUserId(uid);
            long identity = Binder.clearCallingIdentity();
            try {
                Account[] accounts = null;
                for (String packageName : packageNames) {
                    try {
                        if (accountManagerService.mPackageManager.checkPermission("android.permission.GET_ACCOUNTS", packageName) == 0) {
                            if (accounts == null) {
                                accounts = accountManagerService.getAccountsAsUser(null, userId, PackageManagerService.PLATFORM_PACKAGE_NAME);
                                if (ArrayUtils.isEmpty(accounts)) {
                                    Binder.restoreCallingIdentity(identity);
                                    return;
                                }
                            }
                            for (Account account : accounts) {
                                accountManagerService.cancelAccountAccessRequestNotificationIfNeeded(account, uid, packageName, true);
                            }
                        }
                    } catch (Throwable th2) {
                        th = th2;
                        Binder.restoreCallingIdentity(identity);
                        throw th;
                    }
                }
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean getBindInstantServiceAllowed(int userId) {
        return this.mAuthenticatorCache.getBindInstantServiceAllowed(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setBindInstantServiceAllowed(int userId, boolean allowed) {
        this.mAuthenticatorCache.setBindInstantServiceAllowed(userId, allowed);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelAccountAccessRequestNotificationIfNeeded(int uid, boolean checkAccess) {
        Account[] accounts = getAccountsAsUser(null, UserHandle.getUserId(uid), PackageManagerService.PLATFORM_PACKAGE_NAME);
        for (Account account : accounts) {
            cancelAccountAccessRequestNotificationIfNeeded(account, uid, checkAccess);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelAccountAccessRequestNotificationIfNeeded(String packageName, int uid, boolean checkAccess) {
        Account[] accounts = getAccountsAsUser(null, UserHandle.getUserId(uid), PackageManagerService.PLATFORM_PACKAGE_NAME);
        for (Account account : accounts) {
            cancelAccountAccessRequestNotificationIfNeeded(account, uid, packageName, checkAccess);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelAccountAccessRequestNotificationIfNeeded(Account account, int uid, boolean checkAccess) {
        String[] packageNames = this.mPackageManager.getPackagesForUid(uid);
        if (packageNames != null) {
            for (String packageName : packageNames) {
                cancelAccountAccessRequestNotificationIfNeeded(account, uid, packageName, checkAccess);
            }
        }
    }

    private void cancelAccountAccessRequestNotificationIfNeeded(Account account, int uid, String packageName, boolean checkAccess) {
        if (!checkAccess || hasAccountAccess(account, packageName, UserHandle.getUserHandleForUid(uid))) {
            cancelNotification(getCredentialPermissionNotificationId(account, "com.android.AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE", uid), packageName, UserHandle.getUserHandleForUid(uid));
        }
    }

    public boolean addAccountExplicitlyWithVisibility(Account account, String password, Bundle extras, Map packageToVisibility) {
        Bundle.setDefusable(extras, true);
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "addAccountExplicitly: " + account + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot explicitly add accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return addAccountInternal(accounts, account, password, extras, callingUid, packageToVisibility);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public Map<Account, Integer> getAccountsAndVisibilityForPackage(String packageName, String accountType) {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        boolean isSystemUid = UserHandle.isSameApp(callingUid, 1000);
        List<String> managedTypes = getTypesForCaller(callingUid, userId, isSystemUid);
        if ((accountType != null && !managedTypes.contains(accountType)) || (accountType == null && !isSystemUid)) {
            throw new SecurityException("getAccountsAndVisibilityForPackage() called from unauthorized uid " + callingUid + " with packageName=" + packageName);
        }
        if (accountType != null) {
            managedTypes = new ArrayList();
            managedTypes.add(accountType);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return getAccountsAndVisibilityForPackage(packageName, managedTypes, Integer.valueOf(callingUid), accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private Map<Account, Integer> getAccountsAndVisibilityForPackage(String packageName, List<String> accountTypes, Integer callingUid, UserAccounts accounts) {
        if (!packageExistsForUser(packageName, accounts.userId)) {
            Log.d(TAG, "Package not found " + packageName);
            return new LinkedHashMap();
        }
        Map<Account, Integer> result = new LinkedHashMap<>();
        for (String accountType : accountTypes) {
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    Account[] accountsOfType = accounts.accountCache.get(accountType);
                    if (accountsOfType != null) {
                        for (Account account : accountsOfType) {
                            result.put(account, resolveAccountVisibility(account, packageName, accounts));
                        }
                    }
                }
            }
        }
        return filterSharedAccounts(accounts, result, callingUid.intValue(), packageName);
    }

    public Map<String, Integer> getPackagesAndVisibilityForAccount(Account account) {
        Map<String, Integer> packagesAndVisibilityForAccountLocked;
        Preconditions.checkNotNull(account, "account cannot be null");
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId) && !isSystemUid(callingUid)) {
            String msg = String.format("uid %s cannot get secrets for account %s", Integer.valueOf(callingUid), account);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    packagesAndVisibilityForAccountLocked = getPackagesAndVisibilityForAccountLocked(account, accounts);
                }
            }
            return packagesAndVisibilityForAccountLocked;
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private Map<String, Integer> getPackagesAndVisibilityForAccountLocked(Account account, UserAccounts accounts) {
        Map<String, Integer> accountVisibility = (Map) accounts.visibilityCache.get(account);
        if (accountVisibility == null) {
            Log.d(TAG, "Visibility was not initialized");
            HashMap hashMap = new HashMap();
            accounts.visibilityCache.put(account, hashMap);
            return hashMap;
        }
        return accountVisibility;
    }

    public int getAccountVisibility(Account account, String packageName) {
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId) && !isSystemUid(callingUid)) {
            String msg = String.format("uid %s cannot get secrets for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            if ("android:accounts:key_legacy_visible".equals(packageName)) {
                int visibility = getAccountVisibilityFromCache(account, packageName, accounts);
                if (visibility != 0) {
                    return visibility;
                }
                return 2;
            } else if ("android:accounts:key_legacy_not_visible".equals(packageName)) {
                int visibility2 = getAccountVisibilityFromCache(account, packageName, accounts);
                if (visibility2 != 0) {
                    return visibility2;
                }
                return 4;
            } else {
                return resolveAccountVisibility(account, packageName, accounts).intValue();
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private int getAccountVisibilityFromCache(Account account, String packageName, UserAccounts accounts) {
        int intValue;
        synchronized (accounts.cacheLock) {
            Map<String, Integer> accountVisibility = getPackagesAndVisibilityForAccountLocked(account, accounts);
            Integer visibility = accountVisibility.get(packageName);
            intValue = visibility != null ? visibility.intValue() : 0;
        }
        return intValue;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Integer resolveAccountVisibility(Account account, String packageName, UserAccounts accounts) {
        int signatureCheckResult;
        int visibility;
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        try {
            long identityToken = clearCallingIdentity();
            int uid = this.mPackageManager.getPackageUidAsUser(packageName, accounts.userId);
            restoreCallingIdentity(identityToken);
            if (!UserHandle.isSameApp(uid, 1000) && (signatureCheckResult = checkPackageSignature(account.type, uid, accounts.userId)) != 2) {
                int visibility2 = getAccountVisibilityFromCache(account, packageName, accounts);
                if (visibility2 == 0) {
                    boolean isPrivileged = isPermittedForPackage(packageName, uid, accounts.userId, "android.permission.GET_ACCOUNTS_PRIVILEGED");
                    if (isProfileOwner(uid)) {
                        return 1;
                    }
                    boolean preO = isPreOApplication(packageName);
                    if (signatureCheckResult != 0 || ((preO && checkGetAccountsPermission(packageName, uid, accounts.userId)) || ((checkReadContactsPermission(packageName, uid, accounts.userId) && accountTypeManagesContacts(account.type, accounts.userId)) || isPrivileged))) {
                        visibility = getAccountVisibilityFromCache(account, "android:accounts:key_legacy_visible", accounts);
                        if (visibility == 0) {
                            visibility = 2;
                        }
                    } else {
                        visibility = getAccountVisibilityFromCache(account, "android:accounts:key_legacy_not_visible", accounts);
                        if (visibility == 0) {
                            visibility = 4;
                        }
                    }
                    return Integer.valueOf(visibility);
                }
                return Integer.valueOf(visibility2);
            }
            return 1;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Package not found " + e.getMessage());
            return 3;
        }
    }

    private boolean isPreOApplication(String packageName) {
        try {
            long identityToken = clearCallingIdentity();
            ApplicationInfo applicationInfo = this.mPackageManager.getApplicationInfo(packageName, 0);
            restoreCallingIdentity(identityToken);
            if (applicationInfo == null) {
                return true;
            }
            int version = applicationInfo.targetSdkVersion;
            return version < 26;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Package not found " + e.getMessage());
            return true;
        }
    }

    public boolean setAccountVisibility(Account account, String packageName, int newVisibility) {
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId) && !isSystemUid(callingUid)) {
            String msg = String.format("uid %s cannot get secrets for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return setAccountVisibility(account, packageName, newVisibility, true, accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean isVisible(int visibility) {
        return visibility == 1 || visibility == 2;
    }

    private boolean setAccountVisibility(Account account, String packageName, int newVisibility, boolean notify, UserAccounts accounts) {
        Map<String, Integer> packagesToVisibility;
        List<String> accountRemovedReceivers;
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                if (notify) {
                    if (!isSpecialPackageKey(packageName)) {
                        if (!packageExistsForUser(packageName, accounts.userId)) {
                            return false;
                        }
                        packagesToVisibility = new HashMap<>();
                        packagesToVisibility.put(packageName, resolveAccountVisibility(account, packageName, accounts));
                        accountRemovedReceivers = new ArrayList<>();
                        if (shouldNotifyPackageOnAccountRemoval(account, packageName, accounts)) {
                            accountRemovedReceivers.add(packageName);
                        }
                    } else {
                        packagesToVisibility = getRequestingPackages(account, accounts);
                        accountRemovedReceivers = getAccountRemovedReceivers(account, accounts);
                    }
                } else if (!isSpecialPackageKey(packageName) && !packageExistsForUser(packageName, accounts.userId)) {
                    return false;
                } else {
                    packagesToVisibility = Collections.emptyMap();
                    accountRemovedReceivers = Collections.emptyList();
                }
                if (!updateAccountVisibilityLocked(account, packageName, newVisibility, accounts)) {
                    return false;
                }
                if (notify) {
                    for (Map.Entry<String, Integer> packageToVisibility : packagesToVisibility.entrySet()) {
                        int oldVisibility = packageToVisibility.getValue().intValue();
                        int currentVisibility = resolveAccountVisibility(account, packageName, accounts).intValue();
                        if (isVisible(oldVisibility) != isVisible(currentVisibility)) {
                            notifyPackage(packageToVisibility.getKey(), accounts);
                        }
                    }
                    for (String packageNameToNotify : accountRemovedReceivers) {
                        sendAccountRemovedBroadcast(account, packageNameToNotify, accounts.userId);
                    }
                    sendAccountsChangedBroadcast(accounts.userId);
                }
                return true;
            }
        }
    }

    private boolean updateAccountVisibilityLocked(Account account, String packageName, int newVisibility, UserAccounts accounts) {
        long accountId = accounts.accountsDb.findDeAccountId(account);
        if (accountId < 0) {
            return false;
        }
        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            if (accounts.accountsDb.setAccountVisibility(accountId, packageName, newVisibility)) {
                StrictMode.setThreadPolicy(oldPolicy);
                Map<String, Integer> accountVisibility = getPackagesAndVisibilityForAccountLocked(account, accounts);
                accountVisibility.put(packageName, Integer.valueOf(newVisibility));
                return true;
            }
            return false;
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    public void registerAccountListener(String[] accountTypes, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            registerAccountListener(accountTypes, opPackageName, accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void registerAccountListener(String[] accountTypes, String opPackageName, UserAccounts accounts) {
        synchronized (accounts.mReceiversForType) {
            if (accountTypes == null) {
                accountTypes = new String[]{null};
            }
            for (String type : accountTypes) {
                Map<String, Integer> receivers = (Map) accounts.mReceiversForType.get(type);
                if (receivers == null) {
                    receivers = new HashMap<>();
                    accounts.mReceiversForType.put(type, receivers);
                }
                Integer cnt = receivers.get(opPackageName);
                int i = 1;
                if (cnt != null) {
                    i = 1 + cnt.intValue();
                }
                receivers.put(opPackageName, Integer.valueOf(i));
            }
        }
    }

    public void unregisterAccountListener(String[] accountTypes, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            unregisterAccountListener(accountTypes, opPackageName, accounts);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void unregisterAccountListener(String[] accountTypes, String opPackageName, UserAccounts accounts) {
        synchronized (accounts.mReceiversForType) {
            if (accountTypes == null) {
                accountTypes = new String[]{null};
            }
            for (String type : accountTypes) {
                Map<String, Integer> receivers = (Map) accounts.mReceiversForType.get(type);
                if (receivers == null || receivers.get(opPackageName) == null) {
                    throw new IllegalArgumentException("attempt to unregister wrong receiver");
                }
                Integer cnt = receivers.get(opPackageName);
                if (cnt.intValue() == 1) {
                    receivers.remove(opPackageName);
                } else {
                    receivers.put(opPackageName, Integer.valueOf(cnt.intValue() - 1));
                }
            }
        }
    }

    private void sendNotificationAccountUpdated(Account account, UserAccounts accounts) {
        Map<String, Integer> packagesToVisibility = getRequestingPackages(account, accounts);
        for (Map.Entry<String, Integer> packageToVisibility : packagesToVisibility.entrySet()) {
            if (packageToVisibility.getValue().intValue() != 3 && packageToVisibility.getValue().intValue() != 4) {
                notifyPackage(packageToVisibility.getKey(), accounts);
            }
        }
    }

    private void notifyPackage(String packageName, UserAccounts accounts) {
        Intent intent = new Intent("android.accounts.action.VISIBLE_ACCOUNTS_CHANGED");
        intent.setPackage(packageName);
        intent.setFlags(1073741824);
        this.mContext.sendBroadcastAsUser(intent, new UserHandle(accounts.userId));
    }

    private Map<String, Integer> getRequestingPackages(Account account, UserAccounts accounts) {
        String[] strArr;
        Set<String> packages = new HashSet<>();
        synchronized (accounts.mReceiversForType) {
            for (String type : new String[]{account.type, null}) {
                Map<String, Integer> receivers = (Map) accounts.mReceiversForType.get(type);
                if (receivers != null) {
                    packages.addAll(receivers.keySet());
                }
            }
        }
        Map<String, Integer> result = new HashMap<>();
        for (String packageName : packages) {
            result.put(packageName, resolveAccountVisibility(account, packageName, accounts));
        }
        return result;
    }

    private List<String> getAccountRemovedReceivers(Account account, UserAccounts accounts) {
        Intent intent = new Intent("android.accounts.action.ACCOUNT_REMOVED");
        intent.setFlags(16777216);
        List<ResolveInfo> receivers = this.mPackageManager.queryBroadcastReceiversAsUser(intent, 0, accounts.userId);
        List<String> result = new ArrayList<>();
        if (receivers == null) {
            return result;
        }
        for (ResolveInfo resolveInfo : receivers) {
            String packageName = resolveInfo.activityInfo.applicationInfo.packageName;
            int visibility = resolveAccountVisibility(account, packageName, accounts).intValue();
            if (visibility == 1 || visibility == 2) {
                result.add(packageName);
            }
        }
        return result;
    }

    private boolean shouldNotifyPackageOnAccountRemoval(Account account, String packageName, UserAccounts accounts) {
        int visibility = resolveAccountVisibility(account, packageName, accounts).intValue();
        if (visibility == 1 || visibility == 2) {
            Intent intent = new Intent("android.accounts.action.ACCOUNT_REMOVED");
            intent.setFlags(16777216);
            intent.setPackage(packageName);
            List<ResolveInfo> receivers = this.mPackageManager.queryBroadcastReceiversAsUser(intent, 0, accounts.userId);
            return receivers != null && receivers.size() > 0;
        }
        return false;
    }

    private boolean packageExistsForUser(String packageName, int userId) {
        try {
            long identityToken = clearCallingIdentity();
            this.mPackageManager.getPackageUidAsUser(packageName, userId);
            restoreCallingIdentity(identityToken);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isSpecialPackageKey(String packageName) {
        return "android:accounts:key_legacy_visible".equals(packageName) || "android:accounts:key_legacy_not_visible".equals(packageName);
    }

    private void sendAccountsChangedBroadcast(int userId) {
        Log.i(TAG, "the accounts changed, sending broadcast of " + ACCOUNTS_CHANGED_INTENT.getAction());
        this.mContext.sendBroadcastAsUser(ACCOUNTS_CHANGED_INTENT, new UserHandle(userId));
    }

    private void sendAccountRemovedBroadcast(Account account, String packageName, int userId) {
        Intent intent = new Intent("android.accounts.action.ACCOUNT_REMOVED");
        intent.setFlags(16777216);
        intent.setPackage(packageName);
        intent.putExtra("authAccount", account.name);
        intent.putExtra("accountType", account.type);
        this.mContext.sendBroadcastAsUser(intent, new UserHandle(userId));
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Account Manager Crash", e);
            }
            throw e;
        }
    }

    private UserManager getUserManager() {
        if (this.mUserManager == null) {
            this.mUserManager = UserManager.get(this.mContext);
        }
        return this.mUserManager;
    }

    public void validateAccounts(int userId) {
        UserAccounts accounts = getUserAccounts(userId);
        validateAccountsInternal(accounts, true);
    }

    /* JADX WARN: Removed duplicated region for block: B:109:0x0331 A[Catch: all -> 0x0340, TRY_ENTER, TryCatch #0 {all -> 0x0340, blocks: (B:109:0x0331, B:110:0x0338, B:100:0x0317, B:101:0x031e, B:113:0x033e), top: B:123:0x006c }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void validateAccountsInternal(com.android.server.accounts.AccountManagerService.UserAccounts r30, boolean r31) {
        /*
            Method dump skipped, instructions count: 843
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.accounts.AccountManagerService.validateAccountsInternal(com.android.server.accounts.AccountManagerService$UserAccounts, boolean):void");
    }

    private SparseBooleanArray getUidsOfInstalledOrUpdatedPackagesAsUser(int userId) {
        List<PackageInfo> pkgsWithData = this.mPackageManager.getInstalledPackagesAsUser(8192, userId);
        SparseBooleanArray knownUids = new SparseBooleanArray(pkgsWithData.size());
        for (PackageInfo pkgInfo : pkgsWithData) {
            if (pkgInfo.applicationInfo != null && (pkgInfo.applicationInfo.flags & DumpState.DUMP_VOLUMES) != 0) {
                knownUids.put(pkgInfo.applicationInfo.uid, true);
            }
        }
        return knownUids;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static HashMap<String, Integer> getAuthenticatorTypeAndUIDForUser(Context context, int userId) {
        AccountAuthenticatorCache authCache = new AccountAuthenticatorCache(context);
        return getAuthenticatorTypeAndUIDForUser(authCache, userId);
    }

    private static HashMap<String, Integer> getAuthenticatorTypeAndUIDForUser(IAccountAuthenticatorCache authCache, int userId) {
        HashMap<String, Integer> knownAuth = new LinkedHashMap<>();
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> service : authCache.getAllServices(userId)) {
            knownAuth.put(((AuthenticatorDescription) service.type).type, Integer.valueOf(service.uid));
        }
        return knownAuth;
    }

    private UserAccounts getUserAccountsForCaller() {
        return getUserAccounts(UserHandle.getCallingUserId());
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public UserAccounts getUserAccounts(int userId) {
        UserAccounts accounts;
        synchronized (this.mUsers) {
            accounts = this.mUsers.get(userId);
            boolean validateAccounts = false;
            if (accounts == null) {
                File preNDbFile = new File(this.mInjector.getPreNDatabaseName(userId));
                File deDbFile = new File(this.mInjector.getDeDatabaseName(userId));
                accounts = new UserAccounts(this.mContext, userId, preNDbFile, deDbFile);
                initializeDebugDbSizeAndCompileSqlStatementForLogging(accounts);
                this.mUsers.append(userId, accounts);
                purgeOldGrants(accounts);
                validateAccounts = true;
            }
            if (!accounts.accountsDb.isCeDatabaseAttached() && this.mLocalUnlockedUsers.get(userId)) {
                Log.i(TAG, "User " + userId + " is unlocked - opening CE database");
                synchronized (accounts.dbLock) {
                    synchronized (accounts.cacheLock) {
                        File ceDatabaseFile = new File(this.mInjector.getCeDatabaseName(userId));
                        accounts.accountsDb.attachCeDatabase(ceDatabaseFile);
                    }
                }
                syncDeCeAccountsLocked(accounts);
            }
            if (validateAccounts) {
                validateAccountsInternal(accounts, true);
            }
        }
        return accounts;
    }

    private void syncDeCeAccountsLocked(UserAccounts accounts) {
        Preconditions.checkState(Thread.holdsLock(this.mUsers), "mUsers lock must be held");
        List<Account> accountsToRemove = accounts.accountsDb.findCeAccountsNotInDe();
        if (!accountsToRemove.isEmpty()) {
            Slog.i(TAG, "Accounts " + accountsToRemove + " were previously deleted while user " + accounts.userId + " was locked. Removing accounts from CE tables");
            logRecord(accounts, AccountsDb.DEBUG_ACTION_SYNC_DE_CE_ACCOUNTS, "accounts");
            for (Account account : accountsToRemove) {
                removeAccountInternal(accounts, account, 1000);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void purgeOldGrantsAll() {
        synchronized (this.mUsers) {
            for (int i = 0; i < this.mUsers.size(); i++) {
                purgeOldGrants(this.mUsers.valueAt(i));
            }
        }
    }

    private void purgeOldGrants(UserAccounts accounts) {
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                List<Integer> uids = accounts.accountsDb.findAllUidGrants();
                for (Integer num : uids) {
                    int uid = num.intValue();
                    boolean packageExists = this.mPackageManager.getPackagesForUid(uid) != null;
                    if (!packageExists) {
                        Log.d(TAG, "deleting grants for UID " + uid + " because its package is no longer installed");
                        accounts.accountsDb.deleteGrantsByUid(uid);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeVisibilityValuesForPackage(String packageName) {
        if (isSpecialPackageKey(packageName)) {
            return;
        }
        synchronized (this.mUsers) {
            int numberOfUsers = this.mUsers.size();
            for (int i = 0; i < numberOfUsers; i++) {
                UserAccounts accounts = this.mUsers.valueAt(i);
                try {
                    this.mPackageManager.getPackageUidAsUser(packageName, accounts.userId);
                } catch (PackageManager.NameNotFoundException e) {
                    accounts.accountsDb.deleteAccountVisibilityForPackage(packageName);
                    synchronized (accounts.dbLock) {
                        synchronized (accounts.cacheLock) {
                            for (Account account : accounts.visibilityCache.keySet()) {
                                Map<String, Integer> accountVisibility = getPackagesAndVisibilityForAccountLocked(account, accounts);
                                accountVisibility.remove(packageName);
                            }
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void purgeUserData(int userId) {
        UserAccounts accounts;
        synchronized (this.mUsers) {
            accounts = this.mUsers.get(userId);
            this.mUsers.remove(userId);
            this.mLocalUnlockedUsers.delete(userId);
        }
        if (accounts != null) {
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    accounts.statementForLogging.close();
                    accounts.accountsDb.close();
                }
            }
        }
    }

    @VisibleForTesting
    void onUserUnlocked(Intent intent) {
        onUnlockUser(intent.getIntExtra("android.intent.extra.user_handle", -1));
    }

    void onUnlockUser(final int userId) {
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "onUserUnlocked " + userId);
        }
        synchronized (this.mUsers) {
            this.mLocalUnlockedUsers.put(userId, true);
        }
        if (userId < 1) {
            return;
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.accounts.-$$Lambda$AccountManagerService$ncg6hlXg7I0Ee1EZqbXw8fQH9bY
            @Override // java.lang.Runnable
            public final void run() {
                AccountManagerService.this.syncSharedAccounts(userId);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void syncSharedAccounts(int userId) {
        int parentUserId;
        Account[] sharedAccounts = getSharedAccountsAsUser(userId);
        if (sharedAccounts == null || sharedAccounts.length == 0) {
            return;
        }
        Account[] accounts = getAccountsAsUser(null, userId, this.mContext.getOpPackageName());
        if (UserManager.isSplitSystemUser()) {
            parentUserId = getUserManager().getUserInfo(userId).restrictedProfileParentId;
        } else {
            parentUserId = 0;
        }
        if (parentUserId < 0) {
            Log.w(TAG, "User " + userId + " has shared accounts, but no parent user");
            return;
        }
        for (Account sa : sharedAccounts) {
            if (!ArrayUtils.contains(accounts, sa)) {
                copyAccountToUser(null, sa, parentUserId, userId);
            }
        }
    }

    public void onServiceChanged(AuthenticatorDescription desc, int userId, boolean removed) {
        validateAccountsInternal(getUserAccounts(userId), false);
    }

    public String getPassword(Account account) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "getPassword: " + account + ", caller's uid " + Binder.getCallingUid() + ", pid " + Binder.getCallingPid());
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot get secrets for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return readPasswordInternal(accounts, account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readPasswordInternal(UserAccounts accounts, Account account) {
        String findAccountPasswordByNameAndType;
        if (account != null) {
            if (!isLocalUnlockedUser(accounts.userId)) {
                Log.w(TAG, "Password is not available - user " + accounts.userId + " data is locked");
                return null;
            }
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    findAccountPasswordByNameAndType = accounts.accountsDb.findAccountPasswordByNameAndType(account.name, account.type);
                }
            }
            return findAccountPasswordByNameAndType;
        }
        return null;
    }

    public String getPreviousName(Account account) {
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "getPreviousName: " + account + ", caller's uid " + Binder.getCallingUid() + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return readPreviousNameInternal(accounts, account);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private String readPreviousNameInternal(UserAccounts accounts, Account account) {
        if (account == null) {
            return null;
        }
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                AtomicReference<String> previousNameRef = (AtomicReference) accounts.previousNameCache.get(account);
                if (previousNameRef == null) {
                    String previousName = accounts.accountsDb.findDeAccountPreviousName(account);
                    accounts.previousNameCache.put(account, new AtomicReference<>(previousName));
                    return previousName;
                }
                return previousNameRef.get();
            }
        }
    }

    public String getUserData(Account account, String key) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            String msg = String.format("getUserData( account: %s, key: %s, callerUid: %s, pid: %s", account, key, Integer.valueOf(callingUid), Integer.valueOf(Binder.getCallingPid()));
            Log.v(TAG, msg);
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(key, "key cannot be null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg2 = String.format("uid %s cannot get user data for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg2);
        } else if (!isLocalUnlockedUser(userId)) {
            Log.w(TAG, "User " + userId + " data is locked. callingUid " + callingUid);
            return null;
        } else {
            long identityToken = clearCallingIdentity();
            try {
                UserAccounts accounts = getUserAccounts(userId);
                if (accountExistsCache(accounts, account)) {
                    return readUserDataInternal(accounts, account, key);
                }
                return null;
            } finally {
                restoreCallingIdentity(identityToken);
            }
        }
    }

    public AuthenticatorDescription[] getAuthenticatorTypes(int userId) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "getAuthenticatorTypes: for user id " + userId + " caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(String.format("User %s tying to get authenticator types for %s", Integer.valueOf(UserHandle.getCallingUserId()), Integer.valueOf(userId)));
        }
        long identityToken = clearCallingIdentity();
        try {
            return getAuthenticatorTypesInternal(userId);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private AuthenticatorDescription[] getAuthenticatorTypesInternal(int userId) {
        this.mAuthenticatorCache.updateServices(userId);
        Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> authenticatorCollection = this.mAuthenticatorCache.getAllServices(userId);
        AuthenticatorDescription[] types = new AuthenticatorDescription[authenticatorCollection.size()];
        int i = 0;
        for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> authenticator : authenticatorCollection) {
            types[i] = (AuthenticatorDescription) authenticator.type;
            i++;
        }
        return types;
    }

    private boolean isCrossUser(int callingUid, int userId) {
        return (userId == UserHandle.getCallingUserId() || callingUid == 1000 || this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") == 0) ? false : true;
    }

    public boolean addAccountExplicitly(Account account, String password, Bundle extras) {
        return addAccountExplicitlyWithVisibility(account, password, extras, null);
    }

    public void copyAccountToUser(final IAccountManagerResponse response, final Account account, final int userFrom, int userTo) {
        long identityToken;
        int callingUid = Binder.getCallingUid();
        if (isCrossUser(callingUid, -1)) {
            throw new SecurityException("Calling copyAccountToUser requires android.permission.INTERACT_ACROSS_USERS_FULL");
        }
        UserAccounts fromAccounts = getUserAccounts(userFrom);
        final UserAccounts toAccounts = getUserAccounts(userTo);
        if (fromAccounts != null && toAccounts != null) {
            Slog.d(TAG, "Copying account " + account.name + " from user " + userFrom + " to user " + userTo);
            long identityToken2 = clearCallingIdentity();
            try {
            } catch (Throwable th) {
                th = th;
                identityToken = identityToken2;
            }
            try {
                new Session(fromAccounts, response, account.type, false, false, account.name, false) { // from class: com.android.server.accounts.AccountManagerService.5
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        return super.toDebugString(now) + ", getAccountCredentialsForClone, " + account.type;
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.getAccountCredentialsForCloning(this, account);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void onResult(Bundle result) {
                        Bundle.setDefusable(result, true);
                        if (result != null && result.getBoolean("booleanResult", false)) {
                            AccountManagerService.this.completeCloningAccount(response, result, account, toAccounts, userFrom);
                        } else {
                            super.onResult(result);
                        }
                    }
                }.bind();
                restoreCallingIdentity(identityToken2);
                return;
            } catch (Throwable th2) {
                th = th2;
                identityToken = identityToken2;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        }
        if (response != null) {
            Bundle result = new Bundle();
            result.putBoolean("booleanResult", false);
            try {
                response.onResult(result);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to report error back to the client." + e);
            }
        }
    }

    public boolean accountAuthenticated(Account account) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            String msg = String.format("accountAuthenticated( account: %s, callerUid: %s)", account, Integer.valueOf(callingUid));
            Log.v(TAG, msg);
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg2 = String.format("uid %s cannot notify authentication for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg2);
        } else if (canUserModifyAccounts(userId, callingUid) && canUserModifyAccountsForType(userId, account.type, callingUid)) {
            long identityToken = clearCallingIdentity();
            try {
                getUserAccounts(userId);
                return updateLastAuthenticatedTime(account);
            } finally {
                restoreCallingIdentity(identityToken);
            }
        } else {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updateLastAuthenticatedTime(Account account) {
        boolean updateAccountLastAuthenticatedTime;
        UserAccounts accounts = getUserAccountsForCaller();
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                updateAccountLastAuthenticatedTime = accounts.accountsDb.updateAccountLastAuthenticatedTime(account);
            }
        }
        return updateAccountLastAuthenticatedTime;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void completeCloningAccount(IAccountManagerResponse response, final Bundle accountCredentials, final Account account, UserAccounts targetUser, final int parentUserId) {
        long id;
        Bundle.setDefusable(accountCredentials, true);
        long id2 = clearCallingIdentity();
        try {
        } catch (Throwable th) {
            th = th;
            id = id2;
        }
        try {
            new Session(targetUser, response, account.type, false, false, account.name, false) { // from class: com.android.server.accounts.AccountManagerService.6
                @Override // com.android.server.accounts.AccountManagerService.Session
                protected String toDebugString(long now) {
                    return super.toDebugString(now) + ", getAccountCredentialsForClone, " + account.type;
                }

                @Override // com.android.server.accounts.AccountManagerService.Session
                public void run() throws RemoteException {
                    Account[] accounts;
                    for (Account acc : AccountManagerService.this.getAccounts(parentUserId, AccountManagerService.this.mContext.getOpPackageName())) {
                        if (acc.equals(account)) {
                            this.mAuthenticator.addAccountFromCredentials(this, account, accountCredentials);
                            return;
                        }
                    }
                }

                @Override // com.android.server.accounts.AccountManagerService.Session
                public void onResult(Bundle result) {
                    Bundle.setDefusable(result, true);
                    super.onResult(result);
                }

                @Override // com.android.server.accounts.AccountManagerService.Session
                public void onError(int errorCode, String errorMessage) {
                    super.onError(errorCode, errorMessage);
                }
            }.bind();
            restoreCallingIdentity(id2);
        } catch (Throwable th2) {
            th = th2;
            id = id2;
            restoreCallingIdentity(id);
            throw th;
        }
    }

    private boolean addAccountInternal(UserAccounts accounts, Account account, String password, Bundle extras, int callingUid, Map<String, Integer> packageToVisibility) {
        Bundle.setDefusable(extras, true);
        if (account == null) {
            return false;
        }
        if (!isLocalUnlockedUser(accounts.userId)) {
            Log.w(TAG, "Account " + account + " cannot be added - user " + accounts.userId + " is locked. callingUid=" + callingUid);
            return false;
        }
        synchronized (accounts.dbLock) {
            try {
                try {
                    try {
                        synchronized (accounts.cacheLock) {
                            try {
                                accounts.accountsDb.beginTransaction();
                                try {
                                    if (accounts.accountsDb.findCeAccountId(account) >= 0) {
                                        Log.w(TAG, "insertAccountIntoDatabase: " + account + ", skipping since the account already exists");
                                        accounts.accountsDb.endTransaction();
                                        return false;
                                    }
                                    try {
                                        long accountId = accounts.accountsDb.insertCeAccount(account, password);
                                        if (accountId < 0) {
                                            Log.w(TAG, "insertAccountIntoDatabase: " + account + ", skipping the DB insert failed");
                                            accounts.accountsDb.endTransaction();
                                            return false;
                                        } else if (accounts.accountsDb.insertDeAccount(account, accountId) < 0) {
                                            Log.w(TAG, "insertAccountIntoDatabase: " + account + ", skipping the DB insert failed");
                                            accounts.accountsDb.endTransaction();
                                            return false;
                                        } else {
                                            if (extras != null) {
                                                for (String key : extras.keySet()) {
                                                    String value = extras.getString(key);
                                                    if (accounts.accountsDb.insertExtra(accountId, key, value) < 0) {
                                                        Log.w(TAG, "insertAccountIntoDatabase: " + account + ", skipping since insertExtra failed for key " + key);
                                                        accounts.accountsDb.endTransaction();
                                                        return false;
                                                    }
                                                }
                                            }
                                            if (packageToVisibility != null) {
                                                for (Map.Entry<String, Integer> entry : packageToVisibility.entrySet()) {
                                                    setAccountVisibility(account, entry.getKey(), entry.getValue().intValue(), false, accounts);
                                                    accountId = accountId;
                                                }
                                            }
                                            accounts.accountsDb.setTransactionSuccessful();
                                            logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_ADD, "accounts", accountId, accounts, callingUid);
                                            insertAccountIntoCacheLocked(accounts, account);
                                            accounts.accountsDb.endTransaction();
                                            if (getUserManager().getUserInfo(accounts.userId).canHaveProfile()) {
                                                addAccountToLinkedRestrictedUsers(account, accounts.userId);
                                            }
                                            sendNotificationAccountUpdated(account, accounts);
                                            sendAccountsChangedBroadcast(accounts.userId);
                                            return true;
                                        }
                                    } catch (Throwable th) {
                                        th = th;
                                        accounts.accountsDb.endTransaction();
                                        throw th;
                                    }
                                } catch (Throwable th2) {
                                    th = th2;
                                }
                            } catch (Throwable th3) {
                                th = th3;
                                throw th;
                            }
                        }
                    } catch (Throwable th4) {
                        th = th4;
                    }
                } catch (Throwable th5) {
                    th = th5;
                    throw th;
                }
            } catch (Throwable th6) {
                th = th6;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isLocalUnlockedUser(int userId) {
        boolean z;
        synchronized (this.mUsers) {
            z = this.mLocalUnlockedUsers.get(userId);
        }
        return z;
    }

    private void addAccountToLinkedRestrictedUsers(Account account, int parentUserId) {
        List<UserInfo> users = getUserManager().getUsers();
        for (UserInfo user : users) {
            if (user.isRestricted() && parentUserId == user.restrictedProfileParentId) {
                addSharedAccountAsUser(account, user.id);
                if (isLocalUnlockedUser(user.id)) {
                    this.mHandler.sendMessage(this.mHandler.obtainMessage(4, parentUserId, user.id, account));
                }
            }
        }
    }

    public void hasFeatures(IAccountManagerResponse response, Account account, String[] features, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "hasFeatures: " + account + ", response " + response + ", features " + Arrays.toString(features) + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkArgument(account != null, "account cannot be null");
        Preconditions.checkArgument(response != null, "response cannot be null");
        Preconditions.checkArgument(features != null, "features cannot be null");
        int userId = UserHandle.getCallingUserId();
        checkReadAccountsPermitted(callingUid, account.type, userId, opPackageName);
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            new TestFeaturesSession(accounts, response, account, features).bind();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /* loaded from: classes.dex */
    private class TestFeaturesSession extends Session {
        private final Account mAccount;
        private final String[] mFeatures;

        public TestFeaturesSession(UserAccounts accounts, IAccountManagerResponse response, Account account, String[] features) {
            super(AccountManagerService.this, accounts, response, account.type, false, true, account.name, false);
            this.mFeatures = features;
            this.mAccount = account;
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        public void run() throws RemoteException {
            try {
                this.mAuthenticator.hasFeatures(this, this.mAccount, this.mFeatures);
            } catch (RemoteException e) {
                onError(1, "remote exception");
            }
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    if (result != null) {
                        if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                            Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onResult() on response " + response);
                        }
                        Bundle newResult = new Bundle();
                        newResult.putBoolean("booleanResult", result.getBoolean("booleanResult", false));
                        response.onResult(newResult);
                        return;
                    }
                    response.onError(5, "null bundle");
                } catch (RemoteException e) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        protected String toDebugString(long now) {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toDebugString(now));
            sb.append(", hasFeatures, ");
            sb.append(this.mAccount);
            sb.append(", ");
            sb.append(this.mFeatures != null ? TextUtils.join(",", this.mFeatures) : null);
            return sb.toString();
        }
    }

    public void renameAccount(IAccountManagerResponse response, Account accountToRename, String newName) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "renameAccount: " + accountToRename + " -> " + newName + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        if (accountToRename == null) {
            throw new IllegalArgumentException("account is null");
        }
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(accountToRename.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot rename accounts of type: %s", Integer.valueOf(callingUid), accountToRename.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            Account resultingAccount = renameAccountInternal(accounts, accountToRename, newName);
            Bundle result = new Bundle();
            result.putString("authAccount", resultingAccount.name);
            result.putString("accountType", resultingAccount.type);
            result.putString("accountAccessId", resultingAccount.getAccessId());
            try {
                response.onResult(result);
            } catch (RemoteException e) {
                Log.w(TAG, e.getMessage());
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private Account renameAccountInternal(UserAccounts accounts, Account accountToRename, String newName) {
        Account renamedAccount;
        cancelNotification(getSigninRequiredNotificationId(accounts, accountToRename), new UserHandle(accounts.userId));
        synchronized (accounts.credentialsPermissionNotificationIds) {
            for (Pair<Pair<Account, String>, Integer> pair : accounts.credentialsPermissionNotificationIds.keySet()) {
                if (accountToRename.equals(((Pair) pair.first).first)) {
                    NotificationId id = (NotificationId) accounts.credentialsPermissionNotificationIds.get(pair);
                    cancelNotification(id, new UserHandle(accounts.userId));
                }
            }
        }
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                List<String> accountRemovedReceivers = getAccountRemovedReceivers(accountToRename, accounts);
                accounts.accountsDb.beginTransaction();
                Account renamedAccount2 = new Account(newName, accountToRename.type);
                if (accounts.accountsDb.findCeAccountId(renamedAccount2) >= 0) {
                    Log.e(TAG, "renameAccount failed - account with new name already exists");
                    accounts.accountsDb.endTransaction();
                    return null;
                }
                long accountId = accounts.accountsDb.findDeAccountId(accountToRename);
                if (accountId < 0) {
                    Log.e(TAG, "renameAccount failed - old account does not exist");
                    accounts.accountsDb.endTransaction();
                    return null;
                }
                accounts.accountsDb.renameCeAccount(accountId, newName);
                if (!accounts.accountsDb.renameDeAccount(accountId, newName, accountToRename.name)) {
                    Log.e(TAG, "renameAccount failed");
                    accounts.accountsDb.endTransaction();
                    return null;
                }
                accounts.accountsDb.setTransactionSuccessful();
                accounts.accountsDb.endTransaction();
                Account renamedAccount3 = insertAccountIntoCacheLocked(accounts, renamedAccount2);
                Map<String, String> tmpData = (Map) accounts.userDataCache.get(accountToRename);
                Map<String, String> tmpTokens = (Map) accounts.authTokenCache.get(accountToRename);
                Map<String, Integer> tmpVisibility = (Map) accounts.visibilityCache.get(accountToRename);
                removeAccountFromCacheLocked(accounts, accountToRename);
                accounts.userDataCache.put(renamedAccount3, tmpData);
                accounts.authTokenCache.put(renamedAccount3, tmpTokens);
                accounts.visibilityCache.put(renamedAccount3, tmpVisibility);
                accounts.previousNameCache.put(renamedAccount3, new AtomicReference(accountToRename.name));
                int parentUserId = accounts.userId;
                if (canHaveProfile(parentUserId)) {
                    List<UserInfo> users = getUserManager().getUsers(true);
                    for (UserInfo user : users) {
                        if (user.isRestricted()) {
                            renamedAccount = renamedAccount3;
                            if (user.restrictedProfileParentId == parentUserId) {
                                renameSharedAccountAsUser(accountToRename, newName, user.id);
                            }
                        } else {
                            renamedAccount = renamedAccount3;
                        }
                        renamedAccount3 = renamedAccount;
                    }
                }
                sendNotificationAccountUpdated(renamedAccount3, accounts);
                sendAccountsChangedBroadcast(accounts.userId);
                for (String packageName : accountRemovedReceivers) {
                    sendAccountRemovedBroadcast(accountToRename, packageName, accounts.userId);
                }
                return renamedAccount3;
            }
        }
    }

    private boolean canHaveProfile(int parentUserId) {
        UserInfo userInfo = getUserManager().getUserInfo(parentUserId);
        return userInfo != null && userInfo.canHaveProfile();
    }

    public void removeAccount(IAccountManagerResponse response, Account account, boolean expectActivityLaunch) {
        removeAccountAsUser(response, account, expectActivityLaunch, UserHandle.getCallingUserId());
    }

    public void removeAccountAsUser(IAccountManagerResponse response, Account account, boolean expectActivityLaunch, int userId) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "removeAccount: " + account + ", response " + response + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid() + ", for user id " + userId);
        }
        Preconditions.checkArgument(account != null, "account cannot be null");
        Preconditions.checkArgument(response != null, "response cannot be null");
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(String.format("User %s tying remove account for %s", Integer.valueOf(UserHandle.getCallingUserId()), Integer.valueOf(userId)));
        }
        UserHandle user = UserHandle.of(userId);
        if (!isAccountManagedByCaller(account.type, callingUid, user.getIdentifier()) && !isSystemUid(callingUid) && !isProfileOwner(callingUid)) {
            String msg = String.format("uid %s cannot remove accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        } else if (canUserModifyAccounts(userId, callingUid)) {
            if (!canUserModifyAccountsForType(userId, account.type, callingUid)) {
                try {
                    response.onError(101, "User cannot modify accounts of this type (policy).");
                    return;
                } catch (RemoteException e) {
                    return;
                }
            }
            long identityToken = clearCallingIdentity();
            UserAccounts accounts = getUserAccounts(userId);
            cancelNotification(getSigninRequiredNotificationId(accounts, account), user);
            synchronized (accounts.credentialsPermissionNotificationIds) {
                try {
                    for (Pair<Pair<Account, String>, Integer> pair : accounts.credentialsPermissionNotificationIds.keySet()) {
                        try {
                            if (account.equals(((Pair) pair.first).first)) {
                                NotificationId id = (NotificationId) accounts.credentialsPermissionNotificationIds.get(pair);
                                cancelNotification(id, user);
                            }
                        } catch (Throwable th) {
                            th = th;
                            while (true) {
                                try {
                                    break;
                                } catch (Throwable th2) {
                                    th = th2;
                                }
                            }
                            throw th;
                        }
                    }
                    long accountId = accounts.accountsDb.findDeAccountId(account);
                    logRecord(AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_REMOVE, "accounts", accountId, accounts, callingUid);
                    try {
                        new RemoveAccountSession(accounts, response, account, expectActivityLaunch).bind();
                    } finally {
                        restoreCallingIdentity(identityToken);
                    }
                } catch (Throwable th3) {
                    th = th3;
                }
            }
        } else {
            try {
                response.onError(100, "User cannot modify accounts");
            } catch (RemoteException e2) {
            }
        }
    }

    public boolean removeAccountExplicitly(Account account) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "removeAccountExplicitly: " + account + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        int userId = Binder.getCallingUserHandle().getIdentifier();
        if (account == null) {
            Log.e(TAG, "account is null");
            return false;
        } else if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot explicitly remove accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        } else {
            UserAccounts accounts = getUserAccountsForCaller();
            long accountId = accounts.accountsDb.findDeAccountId(account);
            logRecord(AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_REMOVE, "accounts", accountId, accounts, callingUid);
            long identityToken = clearCallingIdentity();
            try {
                return removeAccountInternal(accounts, account, callingUid);
            } finally {
                restoreCallingIdentity(identityToken);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class RemoveAccountSession extends Session {
        final Account mAccount;

        public RemoveAccountSession(UserAccounts accounts, IAccountManagerResponse response, Account account, boolean expectActivityLaunch) {
            super(AccountManagerService.this, accounts, response, account.type, expectActivityLaunch, true, account.name, false);
            this.mAccount = account;
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", removeAccount, account " + this.mAccount;
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        public void run() throws RemoteException {
            this.mAuthenticator.getAccountRemovalAllowed(this, this.mAccount);
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
            if (result != null && result.containsKey("booleanResult") && !result.containsKey("intent")) {
                boolean removalAllowed = result.getBoolean("booleanResult");
                if (removalAllowed) {
                    AccountManagerService.this.removeAccountInternal(this.mAccounts, this.mAccount, getCallingUid());
                }
                IAccountManagerResponse response = getResponseAndClose();
                if (response != null) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onResult() on response " + response);
                    }
                    try {
                        response.onResult(result);
                    } catch (RemoteException e) {
                        Slog.e(AccountManagerService.TAG, "Error calling onResult()", e);
                    }
                }
            }
            super.onResult(result);
        }
    }

    @VisibleForTesting
    protected void removeAccountInternal(Account account) {
        removeAccountInternal(getUserAccountsForCaller(), account, getCallingUid());
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:102:0x01ae -> B:103:0x01af). Please submit an issue!!! */
    public boolean removeAccountInternal(UserAccounts accounts, final Account account, int callingUid) {
        long accountId;
        boolean isChanged;
        boolean isChanged2 = false;
        boolean userUnlocked = isLocalUnlockedUser(accounts.userId);
        if (!userUnlocked) {
            Slog.i(TAG, "Removing account " + account + " while user " + accounts.userId + " is still locked. CE data will be removed later");
        }
        synchronized (accounts.dbLock) {
            try {
                synchronized (accounts.cacheLock) {
                    try {
                        Map<String, Integer> packagesToVisibility = getRequestingPackages(account, accounts);
                        List<String> accountRemovedReceivers = getAccountRemovedReceivers(account, accounts);
                        accounts.accountsDb.beginTransaction();
                        try {
                            try {
                                accountId = accounts.accountsDb.findDeAccountId(account);
                                if (accountId >= 0) {
                                    try {
                                        isChanged2 = accounts.accountsDb.deleteDeAccount(accountId);
                                    } catch (Throwable th) {
                                        th = th;
                                        accounts.accountsDb.endTransaction();
                                        throw th;
                                    }
                                }
                                isChanged = isChanged2;
                                if (userUnlocked) {
                                    try {
                                        long ceAccountId = accounts.accountsDb.findCeAccountId(account);
                                        if (ceAccountId >= 0) {
                                            accounts.accountsDb.deleteCeAccount(ceAccountId);
                                        }
                                    } catch (Throwable th2) {
                                        th = th2;
                                        accounts.accountsDb.endTransaction();
                                        throw th;
                                    }
                                }
                            } catch (Throwable th3) {
                                th = th3;
                            }
                            try {
                                accounts.accountsDb.setTransactionSuccessful();
                                try {
                                    accounts.accountsDb.endTransaction();
                                    if (isChanged) {
                                        removeAccountFromCacheLocked(accounts, account);
                                        for (Map.Entry<String, Integer> packageToVisibility : packagesToVisibility.entrySet()) {
                                            if (packageToVisibility.getValue().intValue() == 1 || packageToVisibility.getValue().intValue() == 2) {
                                                notifyPackage(packageToVisibility.getKey(), accounts);
                                            }
                                        }
                                        sendAccountsChangedBroadcast(accounts.userId);
                                        for (String packageName : accountRemovedReceivers) {
                                            sendAccountRemovedBroadcast(account, packageName, accounts.userId);
                                        }
                                        String action = userUnlocked ? AccountsDb.DEBUG_ACTION_ACCOUNT_REMOVE : AccountsDb.DEBUG_ACTION_ACCOUNT_REMOVE_DE;
                                        logRecord(action, "accounts", accountId, accounts);
                                    }
                                    try {
                                        long id = Binder.clearCallingIdentity();
                                        try {
                                            int parentUserId = accounts.userId;
                                            if (canHaveProfile(parentUserId)) {
                                                List<UserInfo> users = getUserManager().getUsers(true);
                                                for (UserInfo user : users) {
                                                    if (user.isRestricted() && parentUserId == user.restrictedProfileParentId) {
                                                        try {
                                                            removeSharedAccountAsUser(account, user.id, callingUid);
                                                        } catch (Throwable th4) {
                                                            th = th4;
                                                            Binder.restoreCallingIdentity(id);
                                                            throw th;
                                                        }
                                                    }
                                                }
                                            }
                                            Binder.restoreCallingIdentity(id);
                                            if (isChanged) {
                                                synchronized (accounts.credentialsPermissionNotificationIds) {
                                                    for (Pair<Pair<Account, String>, Integer> key : accounts.credentialsPermissionNotificationIds.keySet()) {
                                                        if (account.equals(((Pair) key.first).first) && "com.android.AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE".equals(((Pair) key.first).second)) {
                                                            final int uid = ((Integer) key.second).intValue();
                                                            this.mHandler.post(new Runnable() { // from class: com.android.server.accounts.-$$Lambda$AccountManagerService$lqbNdAUKUSipmpqby9oIO8JlNTQ
                                                                @Override // java.lang.Runnable
                                                                public final void run() {
                                                                    AccountManagerService.this.cancelAccountAccessRequestNotificationIfNeeded(account, uid, false);
                                                                }
                                                            });
                                                        }
                                                    }
                                                }
                                            }
                                            return isChanged;
                                        } catch (Throwable th5) {
                                            th = th5;
                                        }
                                    } catch (Throwable th6) {
                                        th = th6;
                                        throw th;
                                    }
                                } catch (Throwable th7) {
                                    th = th7;
                                    throw th;
                                }
                            } catch (Throwable th8) {
                                th = th8;
                                accounts.accountsDb.endTransaction();
                                throw th;
                            }
                        } catch (Throwable th9) {
                            th = th9;
                        }
                    } catch (Throwable th10) {
                        th = th10;
                    }
                }
            } catch (Throwable th11) {
                th = th11;
            }
        }
    }

    public void invalidateAuthToken(String accountType, String authToken) {
        int callerUid = Binder.getCallingUid();
        Preconditions.checkNotNull(accountType, "accountType cannot be null");
        Preconditions.checkNotNull(authToken, "authToken cannot be null");
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "invalidateAuthToken: accountType " + accountType + ", caller's uid " + callerUid + ", pid " + Binder.getCallingPid());
        }
        int userId = UserHandle.getCallingUserId();
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            synchronized (accounts.dbLock) {
                accounts.accountsDb.beginTransaction();
                try {
                    List<Pair<Account, String>> deletedTokens = invalidateAuthTokenLocked(accounts, accountType, authToken);
                    accounts.accountsDb.setTransactionSuccessful();
                    accounts.accountsDb.endTransaction();
                    synchronized (accounts.cacheLock) {
                        for (Pair<Account, String> tokenInfo : deletedTokens) {
                            Account act = (Account) tokenInfo.first;
                            String tokenType = (String) tokenInfo.second;
                            writeAuthTokenIntoCacheLocked(accounts, act, tokenType, null);
                        }
                        accounts.accountTokenCaches.remove(accountType, authToken);
                    }
                } catch (Throwable th) {
                    accounts.accountsDb.endTransaction();
                    throw th;
                }
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private List<Pair<Account, String>> invalidateAuthTokenLocked(UserAccounts accounts, String accountType, String authToken) {
        List<Pair<Account, String>> results = new ArrayList<>();
        Cursor cursor = accounts.accountsDb.findAuthtokenForAllAccounts(accountType, authToken);
        while (cursor.moveToNext()) {
            try {
                String authTokenId = cursor.getString(0);
                String accountName = cursor.getString(1);
                String authTokenType = cursor.getString(2);
                accounts.accountsDb.deleteAuthToken(authTokenId);
                results.add(Pair.create(new Account(accountName, accountType), authTokenType));
            } finally {
                cursor.close();
            }
        }
        return results;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveCachedToken(UserAccounts accounts, Account account, String callerPkg, byte[] callerSigDigest, String tokenType, String token, long expiryMillis) {
        if (account == null || tokenType == null || callerPkg == null || callerSigDigest == null) {
            return;
        }
        cancelNotification(getSigninRequiredNotificationId(accounts, account), UserHandle.of(accounts.userId));
        synchronized (accounts.cacheLock) {
            accounts.accountTokenCaches.put(account, token, tokenType, callerPkg, callerSigDigest, expiryMillis);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean saveAuthTokenToDatabase(UserAccounts accounts, Account account, String type, String authToken) {
        if (account == null || type == null) {
            return false;
        }
        cancelNotification(getSigninRequiredNotificationId(accounts, account), UserHandle.of(accounts.userId));
        synchronized (accounts.dbLock) {
            accounts.accountsDb.beginTransaction();
            long accountId = accounts.accountsDb.findDeAccountId(account);
            if (accountId < 0) {
                accounts.accountsDb.endTransaction();
                if (0 != 0) {
                    synchronized (accounts.cacheLock) {
                        try {
                            writeAuthTokenIntoCacheLocked(accounts, account, type, authToken);
                        } catch (Throwable th) {
                            th = th;
                        }
                    }
                }
                return false;
            }
            accounts.accountsDb.deleteAuthtokensByAccountIdAndType(accountId, type);
            if (accounts.accountsDb.insertAuthToken(accountId, type, authToken) >= 0) {
                accounts.accountsDb.setTransactionSuccessful();
                accounts.accountsDb.endTransaction();
                if (1 != 0) {
                    synchronized (accounts.cacheLock) {
                        writeAuthTokenIntoCacheLocked(accounts, account, type, authToken);
                    }
                }
                return true;
            }
            accounts.accountsDb.endTransaction();
            if (0 != 0) {
                synchronized (accounts.cacheLock) {
                    try {
                        writeAuthTokenIntoCacheLocked(accounts, account, type, authToken);
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
            }
            return false;
            throw th;
        }
    }

    public String peekAuthToken(Account account, String authTokenType) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "peekAuthToken: " + account + ", authTokenType " + authTokenType + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(authTokenType, "authTokenType cannot be null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot peek the authtokens associated with accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        } else if (!isLocalUnlockedUser(userId)) {
            Log.w(TAG, "Authtoken not available - user " + userId + " data is locked. callingUid " + callingUid);
            return null;
        } else {
            long identityToken = clearCallingIdentity();
            try {
                UserAccounts accounts = getUserAccounts(userId);
                return readAuthTokenInternal(accounts, account, authTokenType);
            } finally {
                restoreCallingIdentity(identityToken);
            }
        }
    }

    public void setAuthToken(Account account, String authTokenType, String authToken) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "setAuthToken: " + account + ", authTokenType " + authTokenType + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(authTokenType, "authTokenType cannot be null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot set auth tokens associated with accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            saveAuthTokenToDatabase(accounts, account, authTokenType, authToken);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setPassword(Account account, String password) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "setAuthToken: " + account + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot set secrets for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            setPasswordInternal(accounts, account, password, callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void setPasswordInternal(UserAccounts accounts, Account account, String password, int callingUid) {
        String str;
        if (account == null) {
            return;
        }
        boolean isChanged = false;
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                accounts.accountsDb.beginTransaction();
                try {
                    long accountId = accounts.accountsDb.findDeAccountId(account);
                    if (accountId >= 0) {
                        accounts.accountsDb.updateCeAccountPassword(accountId, password);
                        accounts.accountsDb.deleteAuthTokensByAccountId(accountId);
                        accounts.authTokenCache.remove(account);
                        accounts.accountTokenCaches.remove(account);
                        accounts.accountsDb.setTransactionSuccessful();
                        if (password != null) {
                            try {
                                if (password.length() != 0) {
                                    str = AccountsDb.DEBUG_ACTION_SET_PASSWORD;
                                    String action = str;
                                    logRecord(action, "accounts", accountId, accounts, callingUid);
                                    isChanged = true;
                                }
                            } catch (Throwable th) {
                                th = th;
                                isChanged = true;
                                accounts.accountsDb.endTransaction();
                                if (isChanged) {
                                    sendNotificationAccountUpdated(account, accounts);
                                    sendAccountsChangedBroadcast(accounts.userId);
                                }
                                throw th;
                            }
                        }
                        str = AccountsDb.DEBUG_ACTION_CLEAR_PASSWORD;
                        String action2 = str;
                        logRecord(action2, "accounts", accountId, accounts, callingUid);
                        isChanged = true;
                    }
                    accounts.accountsDb.endTransaction();
                    if (isChanged) {
                        sendNotificationAccountUpdated(account, accounts);
                        sendAccountsChangedBroadcast(accounts.userId);
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            }
        }
    }

    public void clearPassword(Account account) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "clearPassword: " + account + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot clear passwords for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            setPasswordInternal(accounts, account, null, callingUid);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void setUserData(Account account, String key, String value) {
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "setUserData: " + account + ", key " + key + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(account.type, callingUid, userId)) {
            String msg = String.format("uid %s cannot set user data for accounts of type: %s", Integer.valueOf(callingUid), account.type);
            throw new SecurityException(msg);
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            if (!accountExistsCache(accounts, account)) {
                return;
            }
            setUserdataInternal(accounts, account, key, value);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private boolean accountExistsCache(UserAccounts accounts, Account account) {
        Account[] accountArr;
        synchronized (accounts.cacheLock) {
            if (accounts.accountCache.containsKey(account.type)) {
                for (Account acc : accounts.accountCache.get(account.type)) {
                    if (acc.name.equals(account.name)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private void setUserdataInternal(UserAccounts accounts, Account account, String key, String value) {
        synchronized (accounts.dbLock) {
            accounts.accountsDb.beginTransaction();
            long accountId = accounts.accountsDb.findDeAccountId(account);
            if (accountId < 0) {
                accounts.accountsDb.endTransaction();
                return;
            }
            long extrasId = accounts.accountsDb.findExtrasIdByAccountId(accountId, key);
            if (extrasId < 0) {
                if (accounts.accountsDb.insertExtra(accountId, key, value) < 0) {
                    accounts.accountsDb.endTransaction();
                    return;
                }
            } else if (!accounts.accountsDb.updateExtra(extrasId, value)) {
                accounts.accountsDb.endTransaction();
                return;
            }
            accounts.accountsDb.setTransactionSuccessful();
            accounts.accountsDb.endTransaction();
            synchronized (accounts.cacheLock) {
                writeUserDataIntoCacheLocked(accounts, account, key, value);
            }
        }
    }

    private void onResult(IAccountManagerResponse response, Bundle result) {
        if (result == null) {
            Log.e(TAG, "the result is unexpectedly null", new Exception());
        }
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, getClass().getSimpleName() + " calling onResult() on response " + response);
        }
        try {
            response.onResult(result);
        } catch (RemoteException e) {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "failure while notifying response", e);
            }
        }
    }

    public void getAuthTokenLabel(IAccountManagerResponse response, final String accountType, final String authTokenType) throws RemoteException {
        long identityToken;
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
        Preconditions.checkArgument(authTokenType != null, "authTokenType cannot be null");
        int callingUid = getCallingUid();
        clearCallingIdentity();
        if (UserHandle.getAppId(callingUid) != 1000) {
            throw new SecurityException("can only call from system");
        }
        int userId = UserHandle.getUserId(callingUid);
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            try {
                new Session(accounts, response, accountType, false, false, null, false) { // from class: com.android.server.accounts.AccountManagerService.7
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        return super.toDebugString(now) + ", getAuthTokenLabel, " + accountType + ", authTokenType " + authTokenType;
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.getAuthTokenLabel(this, authTokenType);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void onResult(Bundle result) {
                        Bundle.setDefusable(result, true);
                        if (result != null) {
                            String label = result.getString("authTokenLabelKey");
                            Bundle bundle = new Bundle();
                            bundle.putString("authTokenLabelKey", label);
                            super.onResult(bundle);
                            return;
                        }
                        super.onResult(result);
                    }
                }.bind();
                restoreCallingIdentity(identityToken2);
            } catch (Throwable th) {
                th = th;
                identityToken = identityToken2;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
    }

    public void getAuthToken(IAccountManagerResponse response, final Account account, final String authTokenType, final boolean notifyOnAuthFailure, boolean expectActivityLaunch, final Bundle loginOptions) {
        long identityToken;
        final byte[] callerPkgSigDigest;
        long identityToken2;
        UserAccounts accounts;
        int callerUid;
        long identityToken3;
        final int i;
        final UserAccounts userAccounts;
        Bundle.setDefusable(loginOptions, true);
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "getAuthToken: " + account + ", response " + response + ", authTokenType " + authTokenType + ", notifyOnAuthFailure " + notifyOnAuthFailure + ", expectActivityLaunch " + expectActivityLaunch + ", caller's uid " + Binder.getCallingUid() + ", pid " + Binder.getCallingPid());
        }
        Preconditions.checkArgument(response != null, "response cannot be null");
        try {
            if (account == null) {
                Slog.w(TAG, "getAuthToken called with null account");
                response.onError(7, "account is null");
            } else if (authTokenType == null) {
                Slog.w(TAG, "getAuthToken called with null authTokenType");
                response.onError(7, "authTokenType is null");
            } else {
                int userId = UserHandle.getCallingUserId();
                long ident = Binder.clearCallingIdentity();
                try {
                    UserAccounts accounts2 = getUserAccounts(userId);
                    RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> authenticatorInfo = this.mAuthenticatorCache.getServiceInfo(AuthenticatorDescription.newKey(account.type), accounts2.userId);
                    final boolean customTokens = authenticatorInfo != null && ((AuthenticatorDescription) authenticatorInfo.type).customTokens;
                    int callerUid2 = Binder.getCallingUid();
                    final boolean permissionGranted = customTokens || permissionIsGranted(account, authTokenType, callerUid2, userId);
                    final String callerPkg = loginOptions.getString("androidPackageName");
                    ident = Binder.clearCallingIdentity();
                    try {
                        List<String> callerOwnedPackageNames = Arrays.asList(this.mPackageManager.getPackagesForUid(callerUid2));
                        if (callerPkg == null || !callerOwnedPackageNames.contains(callerPkg)) {
                            String msg = String.format("Uid %s is attempting to illegally masquerade as package %s!", Integer.valueOf(callerUid2), callerPkg);
                            throw new SecurityException(msg);
                        }
                        loginOptions.putInt("callerUid", callerUid2);
                        loginOptions.putInt("callerPid", Binder.getCallingPid());
                        if (notifyOnAuthFailure) {
                            loginOptions.putBoolean("notifyOnAuthFailure", true);
                        }
                        long identityToken4 = clearCallingIdentity();
                        try {
                            callerPkgSigDigest = calculatePackageSignatureDigest(callerPkg);
                            if (!customTokens && permissionGranted) {
                                try {
                                    String authToken = readAuthTokenInternal(accounts2, account, authTokenType);
                                    if (authToken != null) {
                                        Bundle result = new Bundle();
                                        try {
                                            result.putString("authtoken", authToken);
                                            result.putString("authAccount", account.name);
                                            result.putString("accountType", account.type);
                                            onResult(response, result);
                                            restoreCallingIdentity(identityToken4);
                                            return;
                                        } catch (Throwable th) {
                                            th = th;
                                            identityToken = identityToken4;
                                            restoreCallingIdentity(identityToken);
                                            throw th;
                                        }
                                    }
                                } catch (Throwable th2) {
                                    th = th2;
                                    identityToken = identityToken4;
                                }
                            }
                            if (customTokens) {
                                callerUid = callerUid2;
                                identityToken2 = identityToken4;
                                accounts = accounts2;
                                try {
                                    String token = readCachedTokenInternal(accounts2, account, authTokenType, callerPkg, callerPkgSigDigest);
                                    if (token != null) {
                                        if (Log.isLoggable(TAG, 2)) {
                                            Log.v(TAG, "getAuthToken: cache hit ofr custom token authenticator.");
                                        }
                                        Bundle result2 = new Bundle();
                                        result2.putString("authtoken", token);
                                        result2.putString("authAccount", account.name);
                                        result2.putString("accountType", account.type);
                                        onResult(response, result2);
                                        restoreCallingIdentity(identityToken2);
                                        return;
                                    }
                                } catch (Throwable th3) {
                                    th = th3;
                                    identityToken = identityToken2;
                                    restoreCallingIdentity(identityToken);
                                    throw th;
                                }
                            } else {
                                identityToken2 = identityToken4;
                                accounts = accounts2;
                                callerUid = callerUid2;
                            }
                            try {
                                identityToken3 = identityToken2;
                                i = callerUid;
                                userAccounts = accounts;
                            } catch (Throwable th4) {
                                th = th4;
                                identityToken = identityToken2;
                            }
                        } catch (Throwable th5) {
                            th = th5;
                            identityToken = identityToken4;
                        }
                        try {
                            new Session(accounts, response, account.type, expectActivityLaunch, false, account.name, false) { // from class: com.android.server.accounts.AccountManagerService.8
                                @Override // com.android.server.accounts.AccountManagerService.Session
                                protected String toDebugString(long now) {
                                    if (loginOptions != null) {
                                        loginOptions.keySet();
                                    }
                                    return super.toDebugString(now) + ", getAuthToken, " + account + ", authTokenType " + authTokenType + ", loginOptions " + loginOptions + ", notifyOnAuthFailure " + notifyOnAuthFailure;
                                }

                                @Override // com.android.server.accounts.AccountManagerService.Session
                                public void run() throws RemoteException {
                                    if (!permissionGranted) {
                                        this.mAuthenticator.getAuthTokenLabel(this, authTokenType);
                                    } else {
                                        this.mAuthenticator.getAuthToken(this, account, authTokenType, loginOptions);
                                    }
                                }

                                @Override // com.android.server.accounts.AccountManagerService.Session
                                public void onResult(Bundle result3) {
                                    Bundle.setDefusable(result3, true);
                                    if (result3 != null) {
                                        if (result3.containsKey("authTokenLabelKey")) {
                                            Intent intent = AccountManagerService.this.newGrantCredentialsPermissionIntent(account, null, i, new AccountAuthenticatorResponse((IAccountAuthenticatorResponse) this), authTokenType, true);
                                            Bundle bundle = new Bundle();
                                            bundle.putParcelable("intent", intent);
                                            onResult(bundle);
                                            return;
                                        }
                                        String authToken2 = result3.getString("authtoken");
                                        if (authToken2 != null) {
                                            String name = result3.getString("authAccount");
                                            String type = result3.getString("accountType");
                                            if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name)) {
                                                onError(5, "the type and name should not be empty");
                                                return;
                                            }
                                            Account resultAccount = new Account(name, type);
                                            if (!customTokens) {
                                                AccountManagerService.this.saveAuthTokenToDatabase(this.mAccounts, resultAccount, authTokenType, authToken2);
                                            }
                                            long expiryMillis = result3.getLong("android.accounts.expiry", 0L);
                                            if (customTokens && expiryMillis > System.currentTimeMillis()) {
                                                AccountManagerService.this.saveCachedToken(this.mAccounts, account, callerPkg, callerPkgSigDigest, authTokenType, authToken2, expiryMillis);
                                            }
                                        }
                                        Intent intent2 = (Intent) result3.getParcelable("intent");
                                        if (intent2 != null && notifyOnAuthFailure && !customTokens) {
                                            if (checkKeyIntent(Binder.getCallingUid(), intent2)) {
                                                AccountManagerService.this.doNotification(this.mAccounts, account, result3.getString("authFailedMessage"), intent2, PackageManagerService.PLATFORM_PACKAGE_NAME, userAccounts.userId);
                                            } else {
                                                onError(5, "invalid intent in bundle returned");
                                                return;
                                            }
                                        }
                                    }
                                    super.onResult(result3);
                                }
                            }.bind();
                            restoreCallingIdentity(identityToken3);
                        } catch (Throwable th6) {
                            th = th6;
                            identityToken = identityToken3;
                            restoreCallingIdentity(identityToken);
                            throw th;
                        }
                    } finally {
                    }
                } finally {
                }
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to report error back to the client." + e);
        }
    }

    private byte[] calculatePackageSignatureDigest(String callerPkg) {
        MessageDigest digester;
        Signature[] signatureArr;
        try {
            digester = MessageDigest.getInstance("SHA-256");
            PackageInfo pkgInfo = this.mPackageManager.getPackageInfo(callerPkg, 64);
            for (Signature sig : pkgInfo.signatures) {
                digester.update(sig.toByteArray());
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not find packageinfo for: " + callerPkg);
            digester = null;
        } catch (NoSuchAlgorithmException x) {
            Log.wtf(TAG, "SHA-256 should be available", x);
            digester = null;
        }
        if (digester == null) {
            return null;
        }
        return digester.digest();
    }

    private void createNoCredentialsPermissionNotification(Account account, Intent intent, String packageName, int userId) {
        int uid = intent.getIntExtra(WatchlistLoggingHandler.WatchlistEventKeys.UID, -1);
        String authTokenType = intent.getStringExtra("authTokenType");
        String titleAndSubtitle = this.mContext.getString(17040573, account.name);
        int index = titleAndSubtitle.indexOf(10);
        String title = titleAndSubtitle;
        String subtitle = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        if (index > 0) {
            title = titleAndSubtitle.substring(0, index);
            subtitle = titleAndSubtitle.substring(index + 1);
        }
        UserHandle user = UserHandle.of(userId);
        Context contextForUser = getContextForUser(user);
        Notification n = new Notification.Builder(contextForUser, SystemNotificationChannels.ACCOUNT).setSmallIcon(17301642).setWhen(0L).setColor(contextForUser.getColor(17170861)).setContentTitle(title).setContentText(subtitle).setContentIntent(PendingIntent.getActivityAsUser(this.mContext, 0, intent, 268435456, null, user)).build();
        installNotification(getCredentialPermissionNotificationId(account, authTokenType, uid), n, packageName, user.getIdentifier());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Intent newGrantCredentialsPermissionIntent(Account account, String packageName, int uid, AccountAuthenticatorResponse response, String authTokenType, boolean startInNewTask) {
        Intent intent = new Intent(this.mContext, GrantCredentialsPermissionActivity.class);
        if (startInNewTask) {
            intent.setFlags(268435456);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getCredentialPermissionNotificationId(account, authTokenType, uid).mTag);
        sb.append(packageName != null ? packageName : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        intent.addCategory(sb.toString());
        intent.putExtra("account", account);
        intent.putExtra("authTokenType", authTokenType);
        intent.putExtra("response", response);
        intent.putExtra(WatchlistLoggingHandler.WatchlistEventKeys.UID, uid);
        return intent;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NotificationId getCredentialPermissionNotificationId(Account account, String authTokenType, int uid) {
        NotificationId nId;
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.credentialsPermissionNotificationIds) {
            Pair<Pair<Account, String>, Integer> key = new Pair<>(new Pair(account, authTokenType), Integer.valueOf(uid));
            nId = (NotificationId) accounts.credentialsPermissionNotificationIds.get(key);
            if (nId == null) {
                String tag = "AccountManagerService:38:" + account.hashCode() + ":" + authTokenType.hashCode();
                nId = new NotificationId(tag, 38);
                accounts.credentialsPermissionNotificationIds.put(key, nId);
            }
        }
        return nId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public NotificationId getSigninRequiredNotificationId(UserAccounts accounts, Account account) {
        NotificationId nId;
        synchronized (accounts.signinRequiredNotificationIds) {
            nId = (NotificationId) accounts.signinRequiredNotificationIds.get(account);
            if (nId == null) {
                String tag = "AccountManagerService:37:" + account.hashCode();
                nId = new NotificationId(tag, 37);
                accounts.signinRequiredNotificationIds.put(account, nId);
            }
        }
        return nId;
    }

    public void addAccount(IAccountManagerResponse response, final String accountType, final String authTokenType, final String[] requiredFeatures, boolean expectActivityLaunch, Bundle optionsIn) {
        boolean z;
        long identityToken;
        Bundle.setDefusable(optionsIn, true);
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("addAccount: accountType ");
            sb.append(accountType);
            sb.append(", response ");
            sb.append(response);
            sb.append(", authTokenType ");
            sb.append(authTokenType);
            sb.append(", requiredFeatures ");
            sb.append(Arrays.toString(requiredFeatures));
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(Binder.getCallingUid());
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is null");
        }
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(uid);
        if (!canUserModifyAccounts(userId, uid)) {
            try {
                response.onError(100, "User is not allowed to add an account!");
            } catch (RemoteException e) {
            }
            showCantAddAccount(100, userId);
        } else if (!canUserModifyAccountsForType(userId, accountType, uid)) {
            try {
                response.onError(101, "User cannot modify accounts of this type (policy).");
            } catch (RemoteException e2) {
            }
            showCantAddAccount(101, userId);
        } else {
            int pid = Binder.getCallingPid();
            final Bundle options = optionsIn == null ? new Bundle() : optionsIn;
            options.putInt("callerUid", uid);
            options.putInt("callerPid", pid);
            int usrId = UserHandle.getCallingUserId();
            long identityToken2 = clearCallingIdentity();
            try {
                UserAccounts accounts = getUserAccounts(usrId);
                logRecordWithUid(accounts, AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_ADD, "accounts", uid);
                try {
                    new Session(accounts, response, accountType, z, true, null, false, true) { // from class: com.android.server.accounts.AccountManagerService.9
                        @Override // com.android.server.accounts.AccountManagerService.Session
                        public void run() throws RemoteException {
                            this.mAuthenticator.addAccount(this, this.mAccountType, authTokenType, requiredFeatures, options);
                        }

                        @Override // com.android.server.accounts.AccountManagerService.Session
                        protected String toDebugString(long now) {
                            return super.toDebugString(now) + ", addAccount, accountType " + accountType + ", requiredFeatures " + Arrays.toString(requiredFeatures);
                        }
                    }.bind();
                    restoreCallingIdentity(identityToken2);
                } catch (Throwable th) {
                    th = th;
                    identityToken = identityToken2;
                    restoreCallingIdentity(identityToken);
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                identityToken = identityToken2;
            }
        }
    }

    public void addAccountAsUser(IAccountManagerResponse response, final String accountType, final String authTokenType, final String[] requiredFeatures, boolean expectActivityLaunch, Bundle optionsIn, int userId) {
        boolean z;
        long identityToken;
        Bundle.setDefusable(optionsIn, true);
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("addAccount: accountType ");
            sb.append(accountType);
            sb.append(", response ");
            sb.append(response);
            sb.append(", authTokenType ");
            sb.append(authTokenType);
            sb.append(", requiredFeatures ");
            sb.append(Arrays.toString(requiredFeatures));
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(Binder.getCallingUid());
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            sb.append(", for user id ");
            sb.append(userId);
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        Preconditions.checkArgument(response != null, "response cannot be null");
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(String.format("User %s trying to add account for %s", Integer.valueOf(UserHandle.getCallingUserId()), Integer.valueOf(userId)));
        }
        if (!canUserModifyAccounts(userId, callingUid)) {
            try {
                response.onError(100, "User is not allowed to add an account!");
            } catch (RemoteException e) {
            }
            showCantAddAccount(100, userId);
        } else if (!canUserModifyAccountsForType(userId, accountType, callingUid)) {
            try {
                response.onError(101, "User cannot modify accounts of this type (policy).");
            } catch (RemoteException e2) {
            }
            showCantAddAccount(101, userId);
        } else {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            final Bundle options = optionsIn == null ? new Bundle() : optionsIn;
            options.putInt("callerUid", uid);
            options.putInt("callerPid", pid);
            long identityToken2 = clearCallingIdentity();
            try {
                UserAccounts accounts = getUserAccounts(userId);
                logRecordWithUid(accounts, AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_ADD, "accounts", userId);
                try {
                    new Session(accounts, response, accountType, z, true, null, false, true) { // from class: com.android.server.accounts.AccountManagerService.10
                        @Override // com.android.server.accounts.AccountManagerService.Session
                        public void run() throws RemoteException {
                            this.mAuthenticator.addAccount(this, this.mAccountType, authTokenType, requiredFeatures, options);
                        }

                        @Override // com.android.server.accounts.AccountManagerService.Session
                        protected String toDebugString(long now) {
                            String str;
                            StringBuilder sb2 = new StringBuilder();
                            sb2.append(super.toDebugString(now));
                            sb2.append(", addAccount, accountType ");
                            sb2.append(accountType);
                            sb2.append(", requiredFeatures ");
                            if (requiredFeatures != null) {
                                str = TextUtils.join(",", requiredFeatures);
                            } else {
                                str = null;
                            }
                            sb2.append(str);
                            return sb2.toString();
                        }
                    }.bind();
                    restoreCallingIdentity(identityToken2);
                } catch (Throwable th) {
                    th = th;
                    identityToken = identityToken2;
                    restoreCallingIdentity(identityToken);
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                identityToken = identityToken2;
            }
        }
    }

    public void startAddAccountSession(IAccountManagerResponse response, final String accountType, final String authTokenType, final String[] requiredFeatures, boolean expectActivityLaunch, Bundle optionsIn) {
        boolean z;
        long identityToken;
        Bundle.setDefusable(optionsIn, true);
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("startAddAccountSession: accountType ");
            sb.append(accountType);
            sb.append(", response ");
            sb.append(response);
            sb.append(", authTokenType ");
            sb.append(authTokenType);
            sb.append(", requiredFeatures ");
            sb.append(Arrays.toString(requiredFeatures));
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(Binder.getCallingUid());
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        Preconditions.checkArgument(response != null, "response cannot be null");
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(uid);
        if (!canUserModifyAccounts(userId, uid)) {
            try {
                response.onError(100, "User is not allowed to add an account!");
            } catch (RemoteException e) {
            }
            showCantAddAccount(100, userId);
        } else if (!canUserModifyAccountsForType(userId, accountType, uid)) {
            try {
                response.onError(101, "User cannot modify accounts of this type (policy).");
            } catch (RemoteException e2) {
            }
            showCantAddAccount(101, userId);
        } else {
            int pid = Binder.getCallingPid();
            final Bundle options = optionsIn == null ? new Bundle() : optionsIn;
            options.putInt("callerUid", uid);
            options.putInt("callerPid", pid);
            String callerPkg = optionsIn.getString("androidPackageName");
            boolean isPasswordForwardingAllowed = isPermitted(callerPkg, uid, "android.permission.GET_PASSWORD");
            long identityToken2 = clearCallingIdentity();
            try {
                UserAccounts accounts = getUserAccounts(userId);
                logRecordWithUid(accounts, AccountsDb.DEBUG_ACTION_CALLED_START_ACCOUNT_ADD, "accounts", uid);
                try {
                    new StartAccountSession(accounts, response, accountType, z, null, false, true, isPasswordForwardingAllowed) { // from class: com.android.server.accounts.AccountManagerService.11
                        @Override // com.android.server.accounts.AccountManagerService.Session
                        public void run() throws RemoteException {
                            this.mAuthenticator.startAddAccountSession(this, this.mAccountType, authTokenType, requiredFeatures, options);
                        }

                        @Override // com.android.server.accounts.AccountManagerService.Session
                        protected String toDebugString(long now) {
                            String requiredFeaturesStr = TextUtils.join(",", requiredFeatures);
                            StringBuilder sb2 = new StringBuilder();
                            sb2.append(super.toDebugString(now));
                            sb2.append(", startAddAccountSession, accountType ");
                            sb2.append(accountType);
                            sb2.append(", requiredFeatures ");
                            sb2.append(requiredFeatures != null ? requiredFeaturesStr : null);
                            return sb2.toString();
                        }
                    }.bind();
                    restoreCallingIdentity(identityToken2);
                } catch (Throwable th) {
                    th = th;
                    identityToken = identityToken2;
                    restoreCallingIdentity(identityToken);
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                identityToken = identityToken2;
            }
        }
    }

    /* loaded from: classes.dex */
    private abstract class StartAccountSession extends Session {
        private final boolean mIsPasswordForwardingAllowed;

        public StartAccountSession(UserAccounts accounts, IAccountManagerResponse response, String accountType, boolean expectActivityLaunch, String accountName, boolean authDetailsRequired, boolean updateLastAuthenticationTime, boolean isPasswordForwardingAllowed) {
            super(accounts, response, accountType, expectActivityLaunch, true, accountName, authDetailsRequired, updateLastAuthenticationTime);
            this.mIsPasswordForwardingAllowed = isPasswordForwardingAllowed;
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        public void onResult(Bundle result) {
            IAccountManagerResponse response;
            Bundle.setDefusable(result, true);
            this.mNumResults++;
            Intent intent = null;
            if (result != null) {
                Intent intent2 = (Intent) result.getParcelable("intent");
                intent = intent2;
                if (intent2 != null && !checkKeyIntent(Binder.getCallingUid(), intent)) {
                    onError(5, "invalid intent in bundle returned");
                    return;
                }
            }
            if (this.mExpectActivityLaunch && result != null && result.containsKey("intent")) {
                response = this.mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response == null) {
                return;
            }
            if (result == null) {
                if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                    Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onError() on response " + response);
                }
                AccountManagerService.this.sendErrorResponse(response, 5, "null bundle returned");
            } else if (result.getInt("errorCode", -1) > 0 && intent == null) {
                AccountManagerService.this.sendErrorResponse(response, result.getInt("errorCode"), result.getString("errorMessage"));
            } else {
                if (!this.mIsPasswordForwardingAllowed) {
                    result.remove("password");
                }
                result.remove("authtoken");
                if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                    Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onResult() on response " + response);
                }
                Bundle sessionBundle = result.getBundle("accountSessionBundle");
                if (sessionBundle != null) {
                    String accountType = sessionBundle.getString("accountType");
                    if (TextUtils.isEmpty(accountType) || !this.mAccountType.equalsIgnoreCase(accountType)) {
                        Log.w(AccountManagerService.TAG, "Account type in session bundle doesn't match request.");
                    }
                    sessionBundle.putString("accountType", this.mAccountType);
                    try {
                        CryptoHelper cryptoHelper = CryptoHelper.getInstance();
                        Bundle encryptedBundle = cryptoHelper.encryptBundle(sessionBundle);
                        result.putBundle("accountSessionBundle", encryptedBundle);
                    } catch (GeneralSecurityException e) {
                        if (Log.isLoggable(AccountManagerService.TAG, 3)) {
                            Log.v(AccountManagerService.TAG, "Failed to encrypt session bundle!", e);
                        }
                        AccountManagerService.this.sendErrorResponse(response, 5, "failed to encrypt session bundle");
                        return;
                    }
                }
                AccountManagerService.this.sendResponse(response, result);
            }
        }
    }

    public void finishSessionAsUser(IAccountManagerResponse response, Bundle sessionBundle, boolean expectActivityLaunch, Bundle appInfo, int userId) {
        boolean z;
        final Bundle decryptedBundle;
        long identityToken;
        UserAccounts accounts;
        Bundle.setDefusable(sessionBundle, true);
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("finishSession: response ");
            sb.append(response);
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(callingUid);
            sb.append(", caller's user id ");
            sb.append(UserHandle.getCallingUserId());
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            sb.append(", for user id ");
            sb.append(userId);
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        Preconditions.checkArgument(response != null, "response cannot be null");
        if (sessionBundle == null || sessionBundle.size() == 0) {
            throw new IllegalArgumentException("sessionBundle is empty");
        }
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(String.format("User %s trying to finish session for %s without cross user permission", Integer.valueOf(UserHandle.getCallingUserId()), Integer.valueOf(userId)));
        }
        if (!canUserModifyAccounts(userId, callingUid)) {
            sendErrorResponse(response, 100, "User is not allowed to add an account!");
            showCantAddAccount(100, userId);
            return;
        }
        int pid = Binder.getCallingPid();
        try {
            CryptoHelper cryptoHelper = CryptoHelper.getInstance();
            decryptedBundle = cryptoHelper.decryptBundle(sessionBundle);
        } catch (GeneralSecurityException e) {
            e = e;
        }
        try {
            if (decryptedBundle == null) {
                sendErrorResponse(response, 8, "failed to decrypt session bundle");
                return;
            }
            final String accountType = decryptedBundle.getString("accountType");
            if (TextUtils.isEmpty(accountType)) {
                sendErrorResponse(response, 7, "accountType is empty");
                return;
            }
            if (appInfo != null) {
                decryptedBundle.putAll(appInfo);
            }
            decryptedBundle.putInt("callerUid", callingUid);
            decryptedBundle.putInt("callerPid", pid);
            if (!canUserModifyAccountsForType(userId, accountType, callingUid)) {
                sendErrorResponse(response, 101, "User cannot modify accounts of this type (policy).");
                showCantAddAccount(101, userId);
                return;
            }
            long identityToken2 = clearCallingIdentity();
            try {
                accounts = getUserAccounts(userId);
                logRecordWithUid(accounts, AccountsDb.DEBUG_ACTION_CALLED_ACCOUNT_SESSION_FINISH, "accounts", callingUid);
            } catch (Throwable th) {
                th = th;
                identityToken = identityToken2;
            }
            try {
                new Session(accounts, response, accountType, z, true, null, false, true) { // from class: com.android.server.accounts.AccountManagerService.12
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.finishSession(this, this.mAccountType, decryptedBundle);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        return super.toDebugString(now) + ", finishSession, accountType " + accountType;
                    }
                }.bind();
                restoreCallingIdentity(identityToken2);
            } catch (Throwable th2) {
                th = th2;
                identityToken = identityToken2;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (GeneralSecurityException e2) {
            e = e2;
            if (Log.isLoggable(TAG, 3)) {
                Log.v(TAG, "Failed to decrypt session bundle!", e);
            }
            sendErrorResponse(response, 8, "failed to decrypt session bundle");
        }
    }

    private void showCantAddAccount(int errorCode, int userId) {
        DevicePolicyManagerInternal dpmi = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class);
        Intent intent = null;
        if (dpmi == null) {
            intent = getDefaultCantAddAccountIntent(errorCode);
        } else if (errorCode == 100) {
            intent = dpmi.createUserRestrictionSupportIntent(userId, "no_modify_accounts");
        } else if (errorCode == 101) {
            intent = dpmi.createShowAdminSupportIntent(userId, false);
        }
        if (intent == null) {
            intent = getDefaultCantAddAccountIntent(errorCode);
        }
        long identityToken = clearCallingIdentity();
        try {
            this.mContext.startActivityAsUser(intent, new UserHandle(userId));
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private Intent getDefaultCantAddAccountIntent(int errorCode) {
        Intent cantAddAccount = new Intent(this.mContext, CantAddAccountActivity.class);
        cantAddAccount.putExtra("android.accounts.extra.ERROR_CODE", errorCode);
        cantAddAccount.addFlags(268435456);
        return cantAddAccount;
    }

    public void confirmCredentialsAsUser(IAccountManagerResponse response, final Account account, final Bundle options, boolean expectActivityLaunch, int userId) {
        boolean z;
        long identityToken;
        Bundle.setDefusable(options, true);
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("confirmCredentials: ");
            sb.append(account);
            sb.append(", response ");
            sb.append(response);
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(callingUid);
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        if (isCrossUser(callingUid, userId)) {
            throw new SecurityException(String.format("User %s trying to confirm account credentials for %s", Integer.valueOf(UserHandle.getCallingUserId()), Integer.valueOf(userId)));
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            try {
                new Session(accounts, response, account.type, z, true, account.name, true, true) { // from class: com.android.server.accounts.AccountManagerService.13
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.confirmCredentials(this, account, options);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        return super.toDebugString(now) + ", confirmCredentials, " + account;
                    }
                }.bind();
                restoreCallingIdentity(identityToken2);
            } catch (Throwable th) {
                th = th;
                identityToken = identityToken2;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
    }

    public void updateCredentials(IAccountManagerResponse response, final Account account, final String authTokenType, boolean expectActivityLaunch, final Bundle loginOptions) {
        boolean z;
        long identityToken;
        Bundle.setDefusable(loginOptions, true);
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("updateCredentials: ");
            sb.append(account);
            sb.append(", response ");
            sb.append(response);
            sb.append(", authTokenType ");
            sb.append(authTokenType);
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(Binder.getCallingUid());
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        int userId = UserHandle.getCallingUserId();
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            try {
                new Session(accounts, response, account.type, z, true, account.name, false, true) { // from class: com.android.server.accounts.AccountManagerService.14
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.updateCredentials(this, account, authTokenType, loginOptions);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        if (loginOptions != null) {
                            loginOptions.keySet();
                        }
                        return super.toDebugString(now) + ", updateCredentials, " + account + ", authTokenType " + authTokenType + ", loginOptions " + loginOptions;
                    }
                }.bind();
                restoreCallingIdentity(identityToken2);
            } catch (Throwable th) {
                th = th;
                identityToken = identityToken2;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
    }

    public void startUpdateCredentialsSession(IAccountManagerResponse response, final Account account, final String authTokenType, boolean expectActivityLaunch, final Bundle loginOptions) {
        boolean z;
        long identityToken;
        Bundle.setDefusable(loginOptions, true);
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("startUpdateCredentialsSession: ");
            sb.append(account);
            sb.append(", response ");
            sb.append(response);
            sb.append(", authTokenType ");
            sb.append(authTokenType);
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(Binder.getCallingUid());
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        int uid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        String callerPkg = loginOptions.getString("androidPackageName");
        boolean isPasswordForwardingAllowed = isPermitted(callerPkg, uid, "android.permission.GET_PASSWORD");
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            try {
                new StartAccountSession(accounts, response, account.type, z, account.name, false, true, isPasswordForwardingAllowed) { // from class: com.android.server.accounts.AccountManagerService.15
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.startUpdateCredentialsSession(this, account, authTokenType, loginOptions);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        if (loginOptions != null) {
                            loginOptions.keySet();
                        }
                        return super.toDebugString(now) + ", startUpdateCredentialsSession, " + account + ", authTokenType " + authTokenType + ", loginOptions " + loginOptions;
                    }
                }.bind();
                restoreCallingIdentity(identityToken2);
            } catch (Throwable th) {
                th = th;
                identityToken = identityToken2;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
    }

    public void isCredentialsUpdateSuggested(IAccountManagerResponse response, final Account account, final String statusToken) {
        long identityToken;
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "isCredentialsUpdateSuggested: " + account + ", response " + response + ", caller's uid " + Binder.getCallingUid() + ", pid " + Binder.getCallingPid());
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (account == null) {
            throw new IllegalArgumentException("account is null");
        }
        if (TextUtils.isEmpty(statusToken)) {
            throw new IllegalArgumentException("status token is empty");
        }
        int usrId = UserHandle.getCallingUserId();
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(usrId);
            try {
                new Session(accounts, response, account.type, false, false, account.name, false) { // from class: com.android.server.accounts.AccountManagerService.16
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        return super.toDebugString(now) + ", isCredentialsUpdateSuggested, " + account;
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.isCredentialsUpdateSuggested(this, account, statusToken);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void onResult(Bundle result) {
                        Bundle.setDefusable(result, true);
                        IAccountManagerResponse response2 = getResponseAndClose();
                        if (response2 == null) {
                            return;
                        }
                        if (result == null) {
                            AccountManagerService.this.sendErrorResponse(response2, 5, "null bundle");
                            return;
                        }
                        if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                            Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onResult() on response " + response2);
                        }
                        if (result.getInt("errorCode", -1) > 0) {
                            AccountManagerService.this.sendErrorResponse(response2, result.getInt("errorCode"), result.getString("errorMessage"));
                        } else if (!result.containsKey("booleanResult")) {
                            AccountManagerService.this.sendErrorResponse(response2, 5, "no result in response");
                        } else {
                            Bundle newResult = new Bundle();
                            newResult.putBoolean("booleanResult", result.getBoolean("booleanResult", false));
                            AccountManagerService.this.sendResponse(response2, newResult);
                        }
                    }
                }.bind();
                restoreCallingIdentity(identityToken2);
            } catch (Throwable th) {
                th = th;
                identityToken = identityToken2;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
    }

    public void editProperties(IAccountManagerResponse response, final String accountType, boolean expectActivityLaunch) {
        boolean z;
        long identityToken;
        int callingUid = Binder.getCallingUid();
        if (Log.isLoggable(TAG, 2)) {
            StringBuilder sb = new StringBuilder();
            sb.append("editProperties: accountType ");
            sb.append(accountType);
            sb.append(", response ");
            sb.append(response);
            sb.append(", expectActivityLaunch ");
            z = expectActivityLaunch;
            sb.append(z);
            sb.append(", caller's uid ");
            sb.append(callingUid);
            sb.append(", pid ");
            sb.append(Binder.getCallingPid());
            Log.v(TAG, sb.toString());
        } else {
            z = expectActivityLaunch;
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is null");
        }
        int userId = UserHandle.getCallingUserId();
        if (!isAccountManagedByCaller(accountType, callingUid, userId) && !isSystemUid(callingUid)) {
            String msg = String.format("uid %s cannot edit authenticator properites for account type: %s", Integer.valueOf(callingUid), accountType);
            throw new SecurityException(msg);
        }
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            identityToken = identityToken2;
            try {
                new Session(accounts, response, accountType, z, true, null, false) { // from class: com.android.server.accounts.AccountManagerService.17
                    @Override // com.android.server.accounts.AccountManagerService.Session
                    public void run() throws RemoteException {
                        this.mAuthenticator.editProperties(this, this.mAccountType);
                    }

                    @Override // com.android.server.accounts.AccountManagerService.Session
                    protected String toDebugString(long now) {
                        return super.toDebugString(now) + ", editProperties, accountType " + accountType;
                    }
                }.bind();
                restoreCallingIdentity(identityToken);
            } catch (Throwable th) {
                th = th;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
    }

    public boolean hasAccountAccess(Account account, String packageName, UserHandle userHandle) {
        if (UserHandle.getAppId(Binder.getCallingUid()) != 1000) {
            throw new SecurityException("Can be called only by system UID");
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        Preconditions.checkNotNull(userHandle, "userHandle cannot be null");
        int userId = userHandle.getIdentifier();
        Preconditions.checkArgumentInRange(userId, 0, Integer.MAX_VALUE, "user must be concrete");
        try {
            int uid = this.mPackageManager.getPackageUidAsUser(packageName, userId);
            return hasAccountAccess(account, packageName, uid);
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Package not found " + e.getMessage());
            return false;
        }
    }

    private String getPackageNameForUid(int uid) {
        int version;
        String[] packageNames = this.mPackageManager.getPackagesForUid(uid);
        if (ArrayUtils.isEmpty(packageNames)) {
            return null;
        }
        String packageName = packageNames[0];
        if (packageNames.length == 1) {
            return packageName;
        }
        int oldestVersion = Integer.MAX_VALUE;
        String packageName2 = packageName;
        for (String name : packageNames) {
            try {
                ApplicationInfo applicationInfo = this.mPackageManager.getApplicationInfo(name, 0);
                if (applicationInfo != null && (version = applicationInfo.targetSdkVersion) < oldestVersion) {
                    oldestVersion = version;
                    packageName2 = name;
                }
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        return packageName2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean hasAccountAccess(Account account, String packageName, int uid) {
        if (packageName == null && (packageName = getPackageNameForUid(uid)) == null) {
            return false;
        }
        if (permissionIsGranted(account, null, uid, UserHandle.getUserId(uid))) {
            return true;
        }
        int visibility = resolveAccountVisibility(account, packageName, getUserAccounts(UserHandle.getUserId(uid))).intValue();
        return visibility == 1 || visibility == 2;
    }

    public IntentSender createRequestAccountAccessIntentSenderAsUser(Account account, String packageName, UserHandle userHandle) {
        if (UserHandle.getAppId(Binder.getCallingUid()) != 1000) {
            throw new SecurityException("Can be called only by system UID");
        }
        Preconditions.checkNotNull(account, "account cannot be null");
        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        Preconditions.checkNotNull(userHandle, "userHandle cannot be null");
        int userId = userHandle.getIdentifier();
        Preconditions.checkArgumentInRange(userId, 0, Integer.MAX_VALUE, "user must be concrete");
        try {
            int uid = this.mPackageManager.getPackageUidAsUser(packageName, userId);
            Intent intent = newRequestAccountAccessIntent(account, packageName, uid, null);
            long identity = Binder.clearCallingIdentity();
            try {
                return PendingIntent.getActivityAsUser(this.mContext, 0, intent, 1409286144, null, new UserHandle(userId)).getIntentSender();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Unknown package " + packageName);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Intent newRequestAccountAccessIntent(final Account account, final String packageName, final int uid, final RemoteCallback callback) {
        return newGrantCredentialsPermissionIntent(account, packageName, uid, new AccountAuthenticatorResponse((IAccountAuthenticatorResponse) new IAccountAuthenticatorResponse.Stub() { // from class: com.android.server.accounts.AccountManagerService.18
            public void onResult(Bundle value) throws RemoteException {
                handleAuthenticatorResponse(true);
            }

            public void onRequestContinued() {
            }

            public void onError(int errorCode, String errorMessage) throws RemoteException {
                handleAuthenticatorResponse(false);
            }

            private void handleAuthenticatorResponse(boolean accessGranted) throws RemoteException {
                AccountManagerService.this.cancelNotification(AccountManagerService.this.getCredentialPermissionNotificationId(account, "com.android.AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE", uid), packageName, UserHandle.getUserHandleForUid(uid));
                if (callback != null) {
                    Bundle result = new Bundle();
                    result.putBoolean("booleanResult", accessGranted);
                    callback.sendResult(result);
                }
            }
        }), "com.android.AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE", false);
    }

    public boolean someUserHasAccount(Account account) {
        if (!UserHandle.isSameApp(1000, Binder.getCallingUid())) {
            throw new SecurityException("Only system can check for accounts across users");
        }
        long token = Binder.clearCallingIdentity();
        try {
            AccountAndUser[] allAccounts = getAllAccounts();
            for (int i = allAccounts.length - 1; i >= 0; i--) {
                if (allAccounts[i].account.equals(account)) {
                    return true;
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /* loaded from: classes.dex */
    private class GetAccountsByTypeAndFeatureSession extends Session {
        private volatile Account[] mAccountsOfType;
        private volatile ArrayList<Account> mAccountsWithFeatures;
        private final int mCallingUid;
        private volatile int mCurrentAccount;
        private final String[] mFeatures;
        private final boolean mIncludeManagedNotVisible;
        private final String mPackageName;

        public GetAccountsByTypeAndFeatureSession(UserAccounts accounts, IAccountManagerResponse response, String type, String[] features, int callingUid, String packageName, boolean includeManagedNotVisible) {
            super(AccountManagerService.this, accounts, response, type, false, true, null, false);
            this.mAccountsOfType = null;
            this.mAccountsWithFeatures = null;
            this.mCurrentAccount = 0;
            this.mCallingUid = callingUid;
            this.mFeatures = features;
            this.mPackageName = packageName;
            this.mIncludeManagedNotVisible = includeManagedNotVisible;
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        public void run() throws RemoteException {
            this.mAccountsOfType = AccountManagerService.this.getAccountsFromCache(this.mAccounts, this.mAccountType, this.mCallingUid, this.mPackageName, this.mIncludeManagedNotVisible);
            this.mAccountsWithFeatures = new ArrayList<>(this.mAccountsOfType.length);
            this.mCurrentAccount = 0;
            checkAccount();
        }

        public void checkAccount() {
            if (this.mCurrentAccount >= this.mAccountsOfType.length) {
                sendResult();
                return;
            }
            IAccountAuthenticator accountAuthenticator = this.mAuthenticator;
            if (accountAuthenticator == null) {
                if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                    Log.v(AccountManagerService.TAG, "checkAccount: aborting session since we are no longer connected to the authenticator, " + toDebugString());
                    return;
                }
                return;
            }
            try {
                accountAuthenticator.hasFeatures(this, this.mAccountsOfType[this.mCurrentAccount], this.mFeatures);
            } catch (RemoteException e) {
                onError(1, "remote exception");
            }
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        public void onResult(Bundle result) {
            Bundle.setDefusable(result, true);
            this.mNumResults++;
            if (result == null) {
                onError(5, "null bundle");
                return;
            }
            if (result.getBoolean("booleanResult", false)) {
                this.mAccountsWithFeatures.add(this.mAccountsOfType[this.mCurrentAccount]);
            }
            this.mCurrentAccount++;
            checkAccount();
        }

        public void sendResult() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    Account[] accounts = new Account[this.mAccountsWithFeatures.size()];
                    for (int i = 0; i < accounts.length; i++) {
                        accounts[i] = this.mAccountsWithFeatures.get(i);
                    }
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onResult() on response " + response);
                    }
                    Bundle result = new Bundle();
                    result.putParcelableArray("accounts", accounts);
                    response.onResult(result);
                } catch (RemoteException e) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        @Override // com.android.server.accounts.AccountManagerService.Session
        protected String toDebugString(long now) {
            StringBuilder sb = new StringBuilder();
            sb.append(super.toDebugString(now));
            sb.append(", getAccountsByTypeAndFeatures, ");
            sb.append(this.mFeatures != null ? TextUtils.join(",", this.mFeatures) : null);
            return sb.toString();
        }
    }

    public Account[] getAccounts(int userId, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId, opPackageName);
        if (visibleAccountTypes.isEmpty()) {
            return EMPTY_ACCOUNT_ARRAY;
        }
        long identityToken = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            return getAccountsInternal(accounts, callingUid, opPackageName, visibleAccountTypes, false);
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public AccountAndUser[] getRunningAccounts() {
        try {
            int[] runningUserIds = ActivityManager.getService().getRunningUserIds();
            return getAccounts(runningUserIds);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public AccountAndUser[] getAllAccounts() {
        List<UserInfo> users = getUserManager().getUsers(true);
        int[] userIds = new int[users.size()];
        for (int i = 0; i < userIds.length; i++) {
            userIds[i] = users.get(i).id;
        }
        return getAccounts(userIds);
    }

    private AccountAndUser[] getAccounts(int[] userIds) {
        ArrayList<AccountAndUser> runningAccounts = Lists.newArrayList();
        for (int userId : userIds) {
            UserAccounts userAccounts = getUserAccounts(userId);
            if (userAccounts != null) {
                Account[] accounts = getAccountsFromCache(userAccounts, null, Binder.getCallingUid(), null, false);
                for (Account account : accounts) {
                    runningAccounts.add(new AccountAndUser(account, userId));
                }
            }
        }
        AccountAndUser[] accountsArray = new AccountAndUser[runningAccounts.size()];
        return (AccountAndUser[]) runningAccounts.toArray(accountsArray);
    }

    public Account[] getAccountsAsUser(String type, int userId, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        return getAccountsAsUserForPackage(type, userId, opPackageName, -1, opPackageName, false);
    }

    private Account[] getAccountsAsUserForPackage(String type, int userId, String callingPackage, int packageUid, String opPackageName, boolean includeUserManagedNotVisible) {
        String opPackageName2;
        int callingUid;
        long identityToken;
        int callingUid2 = Binder.getCallingUid();
        if (userId != UserHandle.getCallingUserId() && callingUid2 != 1000 && this.mContext.checkCallingOrSelfPermission("android.permission.INTERACT_ACROSS_USERS_FULL") != 0) {
            throw new SecurityException("User " + UserHandle.getCallingUserId() + " trying to get account for " + userId);
        }
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "getAccounts: accountType " + type + ", caller's uid " + Binder.getCallingUid() + ", pid " + Binder.getCallingPid());
        }
        List<String> managedTypes = getTypesManagedByCaller(callingUid2, UserHandle.getUserId(callingUid2));
        if (packageUid == -1 || (!UserHandle.isSameApp(callingUid2, 1000) && (type == null || !managedTypes.contains(type)))) {
            opPackageName2 = opPackageName;
            callingUid = callingUid2;
        } else {
            callingUid = packageUid;
            opPackageName2 = callingPackage;
        }
        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId, opPackageName2);
        if (visibleAccountTypes.isEmpty() || (type != null && !visibleAccountTypes.contains(type))) {
            return EMPTY_ACCOUNT_ARRAY;
        }
        if (visibleAccountTypes.contains(type)) {
            visibleAccountTypes = new ArrayList();
            visibleAccountTypes.add(type);
        }
        List<String> visibleAccountTypes2 = visibleAccountTypes;
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts accounts = getUserAccounts(userId);
            identityToken = identityToken2;
            try {
                Account[] accountsInternal = getAccountsInternal(accounts, callingUid, opPackageName2, visibleAccountTypes2, includeUserManagedNotVisible);
                restoreCallingIdentity(identityToken);
                return accountsInternal;
            } catch (Throwable th) {
                th = th;
                restoreCallingIdentity(identityToken);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
    }

    private Account[] getAccountsInternal(UserAccounts userAccounts, int callingUid, String callingPackage, List<String> visibleAccountTypes, boolean includeUserManagedNotVisible) {
        if (!isLocalUnlockedUser(userAccounts.userId)) {
            Log.w(TAG, "getAccountsInternal - User " + userAccounts.userId + " data is locked. callingUid " + callingUid);
            return new Account[0];
        }
        ArrayList<Account> visibleAccounts = new ArrayList<>();
        for (String visibleType : visibleAccountTypes) {
            Account[] accountsForType = getAccountsFromCache(userAccounts, visibleType, callingUid, callingPackage, includeUserManagedNotVisible);
            if (accountsForType != null) {
                visibleAccounts.addAll(Arrays.asList(accountsForType));
            }
        }
        Account[] result = new Account[visibleAccounts.size()];
        for (int i = 0; i < visibleAccounts.size(); i++) {
            result[i] = visibleAccounts.get(i);
        }
        return result;
    }

    public void addSharedAccountsFromParentUser(int parentUserId, int userId, String opPackageName) {
        checkManageOrCreateUsersPermission("addSharedAccountsFromParentUser");
        Account[] accounts = getAccountsAsUser(null, parentUserId, opPackageName);
        for (Account account : accounts) {
            addSharedAccountAsUser(account, userId);
        }
    }

    private boolean addSharedAccountAsUser(Account account, int userId) {
        UserAccounts accounts = getUserAccounts(handleIncomingUser(userId));
        accounts.accountsDb.deleteSharedAccount(account);
        long accountId = accounts.accountsDb.insertSharedAccount(account);
        if (accountId < 0) {
            Log.w(TAG, "insertAccountIntoDatabase: " + account + ", skipping the DB insert failed");
            return false;
        }
        logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_ADD, "shared_accounts", accountId, accounts);
        return true;
    }

    public boolean renameSharedAccountAsUser(Account account, String newName, int userId) {
        UserAccounts accounts = getUserAccounts(handleIncomingUser(userId));
        long sharedTableAccountId = accounts.accountsDb.findSharedAccountId(account);
        int r = accounts.accountsDb.renameSharedAccount(account, newName);
        if (r > 0) {
            int callingUid = getCallingUid();
            logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_RENAME, "shared_accounts", sharedTableAccountId, accounts, callingUid);
            renameAccountInternal(accounts, account, newName);
        }
        return r > 0;
    }

    public boolean removeSharedAccountAsUser(Account account, int userId) {
        return removeSharedAccountAsUser(account, userId, getCallingUid());
    }

    private boolean removeSharedAccountAsUser(Account account, int userId, int callingUid) {
        UserAccounts accounts = getUserAccounts(handleIncomingUser(userId));
        long sharedTableAccountId = accounts.accountsDb.findSharedAccountId(account);
        boolean deleted = accounts.accountsDb.deleteSharedAccount(account);
        if (deleted) {
            logRecord(AccountsDb.DEBUG_ACTION_ACCOUNT_REMOVE, "shared_accounts", sharedTableAccountId, accounts, callingUid);
            removeAccountInternal(accounts, account, callingUid);
        }
        return deleted;
    }

    public Account[] getSharedAccountsAsUser(int userId) {
        Account[] accountArray;
        UserAccounts accounts = getUserAccounts(handleIncomingUser(userId));
        synchronized (accounts.dbLock) {
            List<Account> accountList = accounts.accountsDb.getSharedAccounts();
            accountArray = new Account[accountList.size()];
            accountList.toArray(accountArray);
        }
        return accountArray;
    }

    public Account[] getAccounts(String type, String opPackageName) {
        return getAccountsAsUser(type, UserHandle.getCallingUserId(), opPackageName);
    }

    public Account[] getAccountsForPackage(String packageName, int uid, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        if (!UserHandle.isSameApp(callingUid, 1000)) {
            throw new SecurityException("getAccountsForPackage() called from unauthorized uid " + callingUid + " with uid=" + uid);
        }
        return getAccountsAsUserForPackage(null, UserHandle.getCallingUserId(), packageName, uid, opPackageName, true);
    }

    public Account[] getAccountsByTypeForPackage(String type, String packageName, String opPackageName) {
        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getCallingUserId();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        try {
            int packageUid = this.mPackageManager.getPackageUidAsUser(packageName, userId);
            if (!UserHandle.isSameApp(callingUid, 1000) && type != null && !isAccountManagedByCaller(type, callingUid, userId)) {
                return EMPTY_ACCOUNT_ARRAY;
            }
            if (!UserHandle.isSameApp(callingUid, 1000) && type == null) {
                return getAccountsAsUserForPackage(type, userId, packageName, packageUid, opPackageName, false);
            }
            return getAccountsAsUserForPackage(type, userId, packageName, packageUid, opPackageName, true);
        } catch (PackageManager.NameNotFoundException re) {
            Slog.e(TAG, "Couldn't determine the packageUid for " + packageName + re);
            return EMPTY_ACCOUNT_ARRAY;
        }
    }

    private boolean needToStartChooseAccountActivity(Account[] accounts, String callingPackage) {
        if (accounts.length < 1) {
            return false;
        }
        if (accounts.length > 1) {
            return true;
        }
        Account account = accounts[0];
        UserAccounts userAccounts = getUserAccounts(UserHandle.getCallingUserId());
        int visibility = resolveAccountVisibility(account, callingPackage, userAccounts).intValue();
        return visibility == 4;
    }

    private void startChooseAccountActivityWithAccounts(IAccountManagerResponse response, Account[] accounts, String callingPackage) {
        Intent intent = new Intent(this.mContext, ChooseAccountActivity.class);
        intent.putExtra("accounts", accounts);
        intent.putExtra("accountManagerResponse", (Parcelable) new AccountManagerResponse(response));
        intent.putExtra("androidPackageName", callingPackage);
        this.mContext.startActivityAsUser(intent, UserHandle.of(UserHandle.getCallingUserId()));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleGetAccountsResult(IAccountManagerResponse response, Account[] accounts, String callingPackage) {
        if (needToStartChooseAccountActivity(accounts, callingPackage)) {
            startChooseAccountActivityWithAccounts(response, accounts, callingPackage);
        } else if (accounts.length == 1) {
            Bundle bundle = new Bundle();
            bundle.putString("authAccount", accounts[0].name);
            bundle.putString("accountType", accounts[0].type);
            onResult(response, bundle);
        } else {
            onResult(response, new Bundle());
        }
    }

    public void getAccountByTypeAndFeatures(final IAccountManagerResponse response, String accountType, String[] features, final String opPackageName) {
        long identityToken;
        int callingUid = Binder.getCallingUid();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "getAccount: accountType " + accountType + ", response " + response + ", features " + Arrays.toString(features) + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is null");
        }
        int userId = UserHandle.getCallingUserId();
        long identityToken2 = clearCallingIdentity();
        try {
            UserAccounts userAccounts = getUserAccounts(userId);
            if (ArrayUtils.isEmpty(features)) {
                try {
                    Account[] accountsWithManagedNotVisible = getAccountsFromCache(userAccounts, accountType, callingUid, opPackageName, true);
                    handleGetAccountsResult(response, accountsWithManagedNotVisible, opPackageName);
                    restoreCallingIdentity(identityToken2);
                    return;
                } catch (Throwable th) {
                    th = th;
                    identityToken = identityToken2;
                }
            } else {
                try {
                    new GetAccountsByTypeAndFeatureSession(userAccounts, new IAccountManagerResponse.Stub() { // from class: com.android.server.accounts.AccountManagerService.19
                        public void onResult(Bundle value) throws RemoteException {
                            Parcelable[] parcelables = value.getParcelableArray("accounts");
                            Account[] accounts = new Account[parcelables.length];
                            for (int i = 0; i < parcelables.length; i++) {
                                accounts[i] = (Account) parcelables[i];
                            }
                            AccountManagerService.this.handleGetAccountsResult(response, accounts, opPackageName);
                        }

                        public void onError(int errorCode, String errorMessage) throws RemoteException {
                        }
                    }, accountType, features, callingUid, opPackageName, true).bind();
                    restoreCallingIdentity(identityToken2);
                    return;
                } catch (Throwable th2) {
                    th = th2;
                    identityToken = identityToken2;
                }
            }
        } catch (Throwable th3) {
            th = th3;
            identityToken = identityToken2;
        }
        restoreCallingIdentity(identityToken);
        throw th;
    }

    public void getAccountsByFeatures(IAccountManagerResponse response, String type, String[] features, String opPackageName) {
        long identityToken;
        UserAccounts userAccounts;
        int callingUid = Binder.getCallingUid();
        this.mAppOpsManager.checkPackage(callingUid, opPackageName);
        if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "getAccounts: accountType " + type + ", response " + response + ", features " + Arrays.toString(features) + ", caller's uid " + callingUid + ", pid " + Binder.getCallingPid());
        }
        if (response == null) {
            throw new IllegalArgumentException("response is null");
        }
        if (type == null) {
            throw new IllegalArgumentException("accountType is null");
        }
        int userId = UserHandle.getCallingUserId();
        List<String> visibleAccountTypes = getTypesVisibleToCaller(callingUid, userId, opPackageName);
        if (!visibleAccountTypes.contains(type)) {
            Bundle result = new Bundle();
            result.putParcelableArray("accounts", EMPTY_ACCOUNT_ARRAY);
            try {
                response.onResult(result);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot respond to caller do to exception.", e);
                return;
            }
        }
        long identityToken2 = clearCallingIdentity();
        try {
            userAccounts = getUserAccounts(userId);
            if (features == null) {
                identityToken = identityToken2;
            } else if (features.length != 0) {
                try {
                    new GetAccountsByTypeAndFeatureSession(userAccounts, response, type, features, callingUid, opPackageName, false).bind();
                    restoreCallingIdentity(identityToken2);
                    return;
                } catch (Throwable th) {
                    th = th;
                    identityToken = identityToken2;
                    restoreCallingIdentity(identityToken);
                    throw th;
                }
            } else {
                identityToken = identityToken2;
            }
        } catch (Throwable th2) {
            th = th2;
            identityToken = identityToken2;
        }
        try {
            Account[] accounts = getAccountsFromCache(userAccounts, type, callingUid, opPackageName, false);
            Bundle result2 = new Bundle();
            result2.putParcelableArray("accounts", accounts);
            onResult(response, result2);
            restoreCallingIdentity(identityToken);
        } catch (Throwable th3) {
            th = th3;
            restoreCallingIdentity(identityToken);
            throw th;
        }
    }

    public void onAccountAccessed(String token) throws RemoteException {
        Account[] accounts;
        int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) == 1000) {
            return;
        }
        int userId = UserHandle.getCallingUserId();
        long identity = Binder.clearCallingIdentity();
        try {
            for (Account account : getAccounts(userId, this.mContext.getOpPackageName())) {
                if (Objects.equals(account.getAccessId(), token) && !hasAccountAccess(account, (String) null, uid)) {
                    updateAppPermission(account, "com.android.AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE", uid, true);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new AccountManagerServiceShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class Session extends IAccountAuthenticatorResponse.Stub implements IBinder.DeathRecipient, ServiceConnection {
        final String mAccountName;
        final String mAccountType;
        protected final UserAccounts mAccounts;
        final boolean mAuthDetailsRequired;
        IAccountAuthenticator mAuthenticator;
        final long mCreationTime;
        final boolean mExpectActivityLaunch;
        private int mNumErrors;
        private int mNumRequestContinued;
        public int mNumResults;
        IAccountManagerResponse mResponse;
        private final boolean mStripAuthTokenFromResult;
        final boolean mUpdateLastAuthenticatedTime;

        public abstract void run() throws RemoteException;

        public Session(AccountManagerService accountManagerService, UserAccounts accounts, IAccountManagerResponse response, String accountType, boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName, boolean authDetailsRequired) {
            this(accounts, response, accountType, expectActivityLaunch, stripAuthTokenFromResult, accountName, authDetailsRequired, false);
        }

        public Session(UserAccounts accounts, IAccountManagerResponse response, String accountType, boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName, boolean authDetailsRequired, boolean updateLastAuthenticatedTime) {
            this.mNumResults = 0;
            this.mNumRequestContinued = 0;
            this.mNumErrors = 0;
            this.mAuthenticator = null;
            if (accountType == null) {
                throw new IllegalArgumentException("accountType is null");
            }
            this.mAccounts = accounts;
            this.mStripAuthTokenFromResult = stripAuthTokenFromResult;
            this.mResponse = response;
            this.mAccountType = accountType;
            this.mExpectActivityLaunch = expectActivityLaunch;
            this.mCreationTime = SystemClock.elapsedRealtime();
            this.mAccountName = accountName;
            this.mAuthDetailsRequired = authDetailsRequired;
            this.mUpdateLastAuthenticatedTime = updateLastAuthenticatedTime;
            synchronized (AccountManagerService.this.mSessions) {
                AccountManagerService.this.mSessions.put(toString(), this);
            }
            if (response != null) {
                try {
                    response.asBinder().linkToDeath(this, 0);
                } catch (RemoteException e) {
                    this.mResponse = null;
                    binderDied();
                }
            }
        }

        IAccountManagerResponse getResponseAndClose() {
            if (this.mResponse == null) {
                return null;
            }
            IAccountManagerResponse response = this.mResponse;
            close();
            return response;
        }

        protected boolean checkKeyIntent(int authUid, Intent intent) {
            intent.setFlags(intent.getFlags() & (-196));
            long bid = Binder.clearCallingIdentity();
            try {
                PackageManager pm = AccountManagerService.this.mContext.getPackageManager();
                ResolveInfo resolveInfo = pm.resolveActivityAsUser(intent, 0, this.mAccounts.userId);
                if (resolveInfo == null) {
                    Binder.restoreCallingIdentity(bid);
                    return false;
                }
                ActivityInfo targetActivityInfo = resolveInfo.activityInfo;
                int targetUid = targetActivityInfo.applicationInfo.uid;
                PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
                if (!isExportedSystemActivity(targetActivityInfo)) {
                    try {
                        if (!pmi.hasSignatureCapability(targetUid, authUid, 16)) {
                            String pkgName = targetActivityInfo.packageName;
                            String activityName = targetActivityInfo.name;
                            Log.e(AccountManagerService.TAG, String.format("KEY_INTENT resolved to an Activity (%s) in a package (%s) that does not share a signature with the supplying authenticator (%s).", activityName, pkgName, this.mAccountType));
                            Binder.restoreCallingIdentity(bid);
                            return false;
                        }
                    } catch (Throwable th) {
                        th = th;
                        Binder.restoreCallingIdentity(bid);
                        throw th;
                    }
                }
                Binder.restoreCallingIdentity(bid);
                return true;
            } catch (Throwable th2) {
                th = th2;
            }
        }

        private boolean isExportedSystemActivity(ActivityInfo activityInfo) {
            String className = activityInfo.name;
            return PackageManagerService.PLATFORM_PACKAGE_NAME.equals(activityInfo.packageName) && (GrantCredentialsPermissionActivity.class.getName().equals(className) || CantAddAccountActivity.class.getName().equals(className));
        }

        private void close() {
            synchronized (AccountManagerService.this.mSessions) {
                if (AccountManagerService.this.mSessions.remove(toString()) == null) {
                    return;
                }
                if (this.mResponse != null) {
                    this.mResponse.asBinder().unlinkToDeath(this, 0);
                    this.mResponse = null;
                }
                cancelTimeout();
                unbind();
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            this.mResponse = null;
            close();
        }

        protected String toDebugString() {
            return toDebugString(SystemClock.elapsedRealtime());
        }

        protected String toDebugString(long now) {
            StringBuilder sb = new StringBuilder();
            sb.append("Session: expectLaunch ");
            sb.append(this.mExpectActivityLaunch);
            sb.append(", connected ");
            sb.append(this.mAuthenticator != null);
            sb.append(", stats (");
            sb.append(this.mNumResults);
            sb.append(SliceClientPermissions.SliceAuthority.DELIMITER);
            sb.append(this.mNumRequestContinued);
            sb.append(SliceClientPermissions.SliceAuthority.DELIMITER);
            sb.append(this.mNumErrors);
            sb.append("), lifetime ");
            sb.append((now - this.mCreationTime) / 1000.0d);
            return sb.toString();
        }

        void bind() {
            if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                Log.v(AccountManagerService.TAG, "initiating bind to authenticator type " + this.mAccountType);
            }
            if (!bindToAuthenticator(this.mAccountType)) {
                Log.d(AccountManagerService.TAG, "bind attempt failed for " + toDebugString());
                onError(1, "bind failure");
            }
        }

        private void unbind() {
            if (this.mAuthenticator != null) {
                this.mAuthenticator = null;
                AccountManagerService.this.mContext.unbindService(this);
            }
        }

        public void cancelTimeout() {
            AccountManagerService.this.mHandler.removeMessages(3, this);
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            this.mAuthenticator = IAccountAuthenticator.Stub.asInterface(service);
            try {
                run();
            } catch (RemoteException e) {
                onError(1, "remote exception");
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            this.mAuthenticator = null;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(1, "disconnected");
                } catch (RemoteException e) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, "Session.onServiceDisconnected: caught RemoteException while responding", e);
                    }
                }
            }
        }

        public void onTimedOut() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    response.onError(1, "timeout");
                } catch (RemoteException e) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, "Session.onTimedOut: caught RemoteException while responding", e);
                    }
                }
            }
        }

        public void onResult(Bundle result) {
            IAccountManagerResponse response;
            boolean needUpdate = true;
            Bundle.setDefusable(result, true);
            this.mNumResults++;
            Intent intent = null;
            if (result != null) {
                boolean isSuccessfulConfirmCreds = result.getBoolean("booleanResult", false);
                boolean isSuccessfulUpdateCredsOrAddAccount = result.containsKey("authAccount") && result.containsKey("accountType");
                if (!this.mUpdateLastAuthenticatedTime || (!isSuccessfulConfirmCreds && !isSuccessfulUpdateCredsOrAddAccount)) {
                    needUpdate = false;
                }
                if (needUpdate || this.mAuthDetailsRequired) {
                    boolean accountPresent = AccountManagerService.this.isAccountPresentForCaller(this.mAccountName, this.mAccountType);
                    if (needUpdate && accountPresent) {
                        AccountManagerService.this.updateLastAuthenticatedTime(new Account(this.mAccountName, this.mAccountType));
                    }
                    if (this.mAuthDetailsRequired) {
                        long lastAuthenticatedTime = -1;
                        if (accountPresent) {
                            lastAuthenticatedTime = this.mAccounts.accountsDb.findAccountLastAuthenticatedTime(new Account(this.mAccountName, this.mAccountType));
                        }
                        result.putLong("lastAuthenticatedTime", lastAuthenticatedTime);
                    }
                }
            }
            if (result != null) {
                Intent intent2 = (Intent) result.getParcelable("intent");
                intent = intent2;
                if (intent2 != null && !checkKeyIntent(Binder.getCallingUid(), intent)) {
                    onError(5, "invalid intent in bundle returned");
                    return;
                }
            }
            if (result != null && !TextUtils.isEmpty(result.getString("authtoken"))) {
                String accountName = result.getString("authAccount");
                String accountType = result.getString("accountType");
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    Account account = new Account(accountName, accountType);
                    AccountManagerService.this.cancelNotification(AccountManagerService.this.getSigninRequiredNotificationId(this.mAccounts, account), new UserHandle(this.mAccounts.userId));
                }
            }
            if (this.mExpectActivityLaunch && result != null && result.containsKey("intent")) {
                response = this.mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response != null) {
                try {
                    if (result == null) {
                        if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                            Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onError() on response " + response);
                        }
                        response.onError(5, "null bundle returned");
                        return;
                    }
                    if (this.mStripAuthTokenFromResult) {
                        result.remove("authtoken");
                    }
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onResult() on response " + response);
                    }
                    if (result.getInt("errorCode", -1) > 0 && intent == null) {
                        response.onError(result.getInt("errorCode"), result.getString("errorMessage"));
                    } else {
                        response.onResult(result);
                    }
                } catch (RemoteException e) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        public void onRequestContinued() {
            this.mNumRequestContinued++;
        }

        public void onError(int errorCode, String errorMessage) {
            this.mNumErrors++;
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                    Log.v(AccountManagerService.TAG, getClass().getSimpleName() + " calling onError() on response " + response);
                }
                try {
                    response.onError(errorCode, errorMessage);
                } catch (RemoteException e) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, "Session.onError: caught RemoteException while responding", e);
                    }
                }
            } else if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                Log.v(AccountManagerService.TAG, "Session.onError: already closed");
            }
        }

        private boolean bindToAuthenticator(String authenticatorType) {
            RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> authenticatorInfo = AccountManagerService.this.mAuthenticatorCache.getServiceInfo(AuthenticatorDescription.newKey(authenticatorType), this.mAccounts.userId);
            if (authenticatorInfo != null) {
                if (!AccountManagerService.this.isLocalUnlockedUser(this.mAccounts.userId) && !authenticatorInfo.componentInfo.directBootAware) {
                    Slog.w(AccountManagerService.TAG, "Blocking binding to authenticator " + authenticatorInfo.componentName + " which isn't encryption aware");
                    return false;
                }
                Intent intent = new Intent();
                intent.setAction("android.accounts.AccountAuthenticator");
                intent.setComponent(authenticatorInfo.componentName);
                if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                    Log.v(AccountManagerService.TAG, "performing bindService to " + authenticatorInfo.componentName);
                }
                int flags = AccountManagerService.this.mAuthenticatorCache.getBindInstantServiceAllowed(this.mAccounts.userId) ? 1 | DumpState.DUMP_CHANGES : 1;
                if (!AccountManagerService.this.mContext.bindServiceAsUser(intent, this, flags, UserHandle.of(this.mAccounts.userId))) {
                    if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                        Log.v(AccountManagerService.TAG, "bindService to " + authenticatorInfo.componentName + " failed");
                    }
                    return false;
                }
                return true;
            }
            if (Log.isLoggable(AccountManagerService.TAG, 2)) {
                Log.v(AccountManagerService.TAG, "there is no authenticator for " + authenticatorType + ", bailing out");
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class MessageHandler extends Handler {
        MessageHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 3:
                    Session session = (Session) msg.obj;
                    session.onTimedOut();
                    return;
                case 4:
                    AccountManagerService.this.copyAccountToUser(null, (Account) msg.obj, msg.arg1, msg.arg2);
                    return;
                default:
                    throw new IllegalStateException("unhandled message: " + msg.what);
            }
        }
    }

    private void logRecord(UserAccounts accounts, String action, String tableName) {
        logRecord(action, tableName, -1L, accounts);
    }

    private void logRecordWithUid(UserAccounts accounts, String action, String tableName, int uid) {
        logRecord(action, tableName, -1L, accounts, uid);
    }

    private void logRecord(String action, String tableName, long accountId, UserAccounts userAccount) {
        logRecord(action, tableName, accountId, userAccount, getCallingUid());
    }

    private void logRecord(String action, String tableName, long accountId, UserAccounts userAccount, int callingUid) {
        Runnable runnable = new Runnable(action, tableName, accountId, userAccount, callingUid, userAccount.debugDbInsertionPoint) { // from class: com.android.server.accounts.AccountManagerService.1LogRecordTask
            private final long accountId;
            private final String action;
            private final int callingUid;
            private final String tableName;
            private final UserAccounts userAccount;
            private final long userDebugDbInsertionPoint;

            {
                this.action = action;
                this.tableName = tableName;
                this.accountId = accountId;
                this.userAccount = userAccount;
                this.callingUid = callingUid;
                this.userDebugDbInsertionPoint = userDebugDbInsertionPoint;
            }

            @Override // java.lang.Runnable
            public void run() {
                SQLiteStatement logStatement = this.userAccount.statementForLogging;
                logStatement.bindLong(1, this.accountId);
                logStatement.bindString(2, this.action);
                logStatement.bindString(3, AccountManagerService.this.mDateFormat.format(new Date()));
                logStatement.bindLong(4, this.callingUid);
                logStatement.bindString(5, this.tableName);
                logStatement.bindLong(6, this.userDebugDbInsertionPoint);
                try {
                    try {
                        logStatement.execute();
                    } catch (IllegalStateException e) {
                        Slog.w(AccountManagerService.TAG, "Failed to insert a log record. accountId=" + this.accountId + " action=" + this.action + " tableName=" + this.tableName + " Error: " + e);
                    }
                } finally {
                    logStatement.clearBindings();
                }
            }
        };
        userAccount.debugDbInsertionPoint = (userAccount.debugDbInsertionPoint + 1) % 64;
        this.mHandler.post(runnable);
    }

    private void initializeDebugDbSizeAndCompileSqlStatementForLogging(UserAccounts userAccount) {
        userAccount.debugDbInsertionPoint = userAccount.accountsDb.calculateDebugTableInsertionPoint();
        userAccount.statementForLogging = userAccount.accountsDb.compileSqlStatementForLogging();
    }

    public IBinder onBind(Intent intent) {
        return asBinder();
    }

    private static boolean scanArgs(String[] args, String value) {
        if (args != null) {
            for (String arg : args) {
                if (value.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, fout)) {
            boolean isCheckinRequest = scanArgs(args, "--checkin") || scanArgs(args, "-c");
            IndentingPrintWriter ipw = new IndentingPrintWriter(fout, "  ");
            List<UserInfo> users = getUserManager().getUsers();
            for (UserInfo user : users) {
                ipw.println("User " + user + ":");
                ipw.increaseIndent();
                dumpUser(getUserAccounts(user.id), fd, ipw, args, isCheckinRequest);
                ipw.println();
                ipw.decreaseIndent();
            }
        }
    }

    private void dumpUser(UserAccounts userAccounts, FileDescriptor fd, PrintWriter fout, String[] args, boolean isCheckinRequest) {
        boolean isUserUnlocked;
        if (isCheckinRequest) {
            synchronized (userAccounts.dbLock) {
                userAccounts.accountsDb.dumpDeAccountsTable(fout);
            }
            return;
        }
        Account[] accounts = getAccountsFromCache(userAccounts, null, 1000, null, false);
        fout.println("Accounts: " + accounts.length);
        for (Account account : accounts) {
            fout.println("  " + account);
        }
        fout.println();
        synchronized (userAccounts.dbLock) {
            try {
                userAccounts.accountsDb.dumpDebugTable(fout);
            } catch (Throwable th) {
                th = th;
                while (true) {
                    try {
                        break;
                    } catch (Throwable th2) {
                        th = th2;
                    }
                }
                throw th;
            }
        }
        fout.println();
        synchronized (this.mSessions) {
            try {
                long now = SystemClock.elapsedRealtime();
                fout.println("Active Sessions: " + this.mSessions.size());
                for (Session session : this.mSessions.values()) {
                    fout.println("  " + session.toDebugString(now));
                }
            } catch (Throwable th3) {
                th = th3;
                while (true) {
                    try {
                        break;
                    } catch (Throwable th4) {
                        th = th4;
                    }
                }
                throw th;
            }
        }
        fout.println();
        this.mAuthenticatorCache.dump(fd, fout, args, userAccounts.userId);
        synchronized (this.mUsers) {
            isUserUnlocked = isLocalUnlockedUser(userAccounts.userId);
        }
        if (!isUserUnlocked) {
            return;
        }
        fout.println();
        synchronized (userAccounts.dbLock) {
            Map<Account, Map<String, Integer>> allVisibilityValues = userAccounts.accountsDb.findAllVisibilityValues();
            fout.println("Account visibility:");
            for (Account account2 : allVisibilityValues.keySet()) {
                fout.println("  " + account2.name);
                Map<String, Integer> visibilities = allVisibilityValues.get(account2);
                for (Map.Entry<String, Integer> entry : visibilities.entrySet()) {
                    fout.println("    " + entry.getKey() + ", " + entry.getValue());
                    allVisibilityValues = allVisibilityValues;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doNotification(UserAccounts accounts, Account account, CharSequence message, Intent intent, String packageName, int userId) {
        long identityToken = clearCallingIdentity();
        try {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "doNotification: " + ((Object) message) + " intent:" + intent);
            }
            if (intent.getComponent() == null || !GrantCredentialsPermissionActivity.class.getName().equals(intent.getComponent().getClassName())) {
                Context contextForUser = getContextForUser(new UserHandle(userId));
                NotificationId id = getSigninRequiredNotificationId(accounts, account);
                intent.addCategory(id.mTag);
                String notificationTitleFormat = contextForUser.getText(17040404).toString();
                Notification n = new Notification.Builder(contextForUser, SystemNotificationChannels.ACCOUNT).setWhen(0L).setSmallIcon(17301642).setColor(contextForUser.getColor(17170861)).setContentTitle(String.format(notificationTitleFormat, account.name)).setContentText(message).setContentIntent(PendingIntent.getActivityAsUser(this.mContext, 0, intent, 268435456, null, new UserHandle(userId))).build();
                installNotification(id, n, packageName, userId);
            } else {
                createNoCredentialsPermissionNotification(account, intent, packageName, userId);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    private void installNotification(NotificationId id, Notification notification, String packageName, int userId) {
        long token = clearCallingIdentity();
        try {
            INotificationManager notificationManager = this.mInjector.getNotificationManager();
            try {
                notificationManager.enqueueNotificationWithTag(packageName, packageName, id.mTag, id.mId, notification, userId);
            } catch (RemoteException e) {
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelNotification(NotificationId id, UserHandle user) {
        cancelNotification(id, this.mContext.getPackageName(), user);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelNotification(NotificationId id, String packageName, UserHandle user) {
        long identityToken = clearCallingIdentity();
        try {
            INotificationManager service = this.mInjector.getNotificationManager();
            service.cancelNotificationWithTag(packageName, id.mTag, id.mId, user.getIdentifier());
        } catch (RemoteException e) {
        } catch (Throwable th) {
            restoreCallingIdentity(identityToken);
            throw th;
        }
        restoreCallingIdentity(identityToken);
    }

    private boolean isPermittedForPackage(String packageName, int uid, int userId, String... permissions) {
        int opCode;
        long identity = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            for (String perm : permissions) {
                if (pm.checkPermission(perm, packageName, userId) == 0 && ((opCode = AppOpsManager.permissionToOpCode(perm)) == -1 || this.mAppOpsManager.noteOpNoThrow(opCode, uid, packageName) == 0)) {
                    Binder.restoreCallingIdentity(identity);
                    return true;
                }
            }
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
        Binder.restoreCallingIdentity(identity);
        return false;
    }

    private boolean isPermitted(String opPackageName, int callingUid, String... permissions) {
        for (String perm : permissions) {
            if (this.mContext.checkCallingOrSelfPermission(perm) == 0) {
                if (Log.isLoggable(TAG, 2)) {
                    Log.v(TAG, "  caller uid " + callingUid + " has " + perm);
                }
                int opCode = AppOpsManager.permissionToOpCode(perm);
                if (opCode == -1 || this.mAppOpsManager.noteOpNoThrow(opCode, callingUid, opPackageName) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private int handleIncomingUser(int userId) {
        try {
            return ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, true, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, (String) null);
        } catch (RemoteException e) {
            return userId;
        }
    }

    private boolean isPrivileged(int callingUid) {
        long identityToken = Binder.clearCallingIdentity();
        try {
            String[] packages = this.mPackageManager.getPackagesForUid(callingUid);
            if (packages == null) {
                Log.d(TAG, "No packages for callingUid " + callingUid);
                return false;
            }
            for (String name : packages) {
                try {
                    PackageInfo packageInfo = this.mPackageManager.getPackageInfo(name, 0);
                    if (packageInfo != null && (packageInfo.applicationInfo.privateFlags & 8) != 0) {
                        Binder.restoreCallingIdentity(identityToken);
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Log.d(TAG, "Package not found " + e.getMessage());
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    private boolean permissionIsGranted(Account account, String authTokenType, int callerUid, int userId) {
        if (UserHandle.getAppId(callerUid) == 1000) {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "Access to " + account + " granted calling uid is system");
            }
            return true;
        } else if (isPrivileged(callerUid)) {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "Access to " + account + " granted calling uid " + callerUid + " privileged");
            }
            return true;
        } else if (account != null && isAccountManagedByCaller(account.type, callerUid, userId)) {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "Access to " + account + " granted calling uid " + callerUid + " manages the account");
            }
            return true;
        } else if (account != null && hasExplicitlyGrantedPermission(account, authTokenType, callerUid)) {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "Access to " + account + " granted calling uid " + callerUid + " user granted access");
            }
            return true;
        } else if (Log.isLoggable(TAG, 2)) {
            Log.v(TAG, "Access to " + account + " not granted for uid " + callerUid);
            return false;
        } else {
            return false;
        }
    }

    private boolean isAccountVisibleToCaller(String accountType, int callingUid, int userId, String opPackageName) {
        if (accountType == null) {
            return false;
        }
        return getTypesVisibleToCaller(callingUid, userId, opPackageName).contains(accountType);
    }

    private boolean checkGetAccountsPermission(String packageName, int uid, int userId) {
        return isPermittedForPackage(packageName, uid, userId, "android.permission.GET_ACCOUNTS", "android.permission.GET_ACCOUNTS_PRIVILEGED");
    }

    private boolean checkReadContactsPermission(String packageName, int uid, int userId) {
        return isPermittedForPackage(packageName, uid, userId, "android.permission.READ_CONTACTS");
    }

    private boolean accountTypeManagesContacts(String accountType, int userId) {
        if (accountType == null) {
            return false;
        }
        long identityToken = Binder.clearCallingIdentity();
        try {
            Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> serviceInfos = this.mAuthenticatorCache.getAllServices(userId);
            Binder.restoreCallingIdentity(identityToken);
            for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo : serviceInfos) {
                if (accountType.equals(((AuthenticatorDescription) serviceInfo.type).type)) {
                    return isPermittedForPackage(((AuthenticatorDescription) serviceInfo.type).packageName, serviceInfo.uid, userId, "android.permission.WRITE_CONTACTS");
                }
            }
            return false;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identityToken);
            throw th;
        }
    }

    private int checkPackageSignature(String accountType, int callingUid, int userId) {
        if (accountType == null) {
            return 0;
        }
        long identityToken = Binder.clearCallingIdentity();
        try {
            Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> serviceInfos = this.mAuthenticatorCache.getAllServices(userId);
            Binder.restoreCallingIdentity(identityToken);
            PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
            for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo : serviceInfos) {
                if (accountType.equals(((AuthenticatorDescription) serviceInfo.type).type)) {
                    if (serviceInfo.uid == callingUid) {
                        return 2;
                    }
                    if (pmi.hasSignatureCapability(serviceInfo.uid, callingUid, 16)) {
                        return 1;
                    }
                }
            }
            return 0;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identityToken);
            throw th;
        }
    }

    private boolean isAccountManagedByCaller(String accountType, int callingUid, int userId) {
        if (accountType == null) {
            return false;
        }
        return getTypesManagedByCaller(callingUid, userId).contains(accountType);
    }

    private List<String> getTypesVisibleToCaller(int callingUid, int userId, String opPackageName) {
        return getTypesForCaller(callingUid, userId, true);
    }

    private List<String> getTypesManagedByCaller(int callingUid, int userId) {
        return getTypesForCaller(callingUid, userId, false);
    }

    private List<String> getTypesForCaller(int callingUid, int userId, boolean isOtherwisePermitted) {
        List<String> managedAccountTypes = new ArrayList<>();
        long identityToken = Binder.clearCallingIdentity();
        try {
            Collection<RegisteredServicesCache.ServiceInfo<AuthenticatorDescription>> serviceInfos = this.mAuthenticatorCache.getAllServices(userId);
            Binder.restoreCallingIdentity(identityToken);
            PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
            for (RegisteredServicesCache.ServiceInfo<AuthenticatorDescription> serviceInfo : serviceInfos) {
                if (isOtherwisePermitted || pmi.hasSignatureCapability(serviceInfo.uid, callingUid, 16)) {
                    managedAccountTypes.add(((AuthenticatorDescription) serviceInfo.type).type);
                }
            }
            return managedAccountTypes;
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identityToken);
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isAccountPresentForCaller(String accountName, String accountType) {
        Account[] accountArr;
        if (getUserAccountsForCaller().accountCache.containsKey(accountType)) {
            for (Account account : getUserAccountsForCaller().accountCache.get(accountType)) {
                if (account.name.equals(accountName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void checkManageUsersPermission(String message) {
        if (ActivityManager.checkComponentPermission("android.permission.MANAGE_USERS", Binder.getCallingUid(), -1, true) != 0) {
            throw new SecurityException("You need MANAGE_USERS permission to: " + message);
        }
    }

    private static void checkManageOrCreateUsersPermission(String message) {
        if (ActivityManager.checkComponentPermission("android.permission.MANAGE_USERS", Binder.getCallingUid(), -1, true) != 0 && ActivityManager.checkComponentPermission("android.permission.CREATE_USERS", Binder.getCallingUid(), -1, true) != 0) {
            throw new SecurityException("You need MANAGE_USERS or CREATE_USERS permission to: " + message);
        }
    }

    private boolean hasExplicitlyGrantedPermission(Account account, String authTokenType, int callerUid) {
        long grantsCount;
        if (UserHandle.getAppId(callerUid) == 1000) {
            return true;
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(callerUid));
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                if (authTokenType != null) {
                    grantsCount = accounts.accountsDb.findMatchingGrantsCount(callerUid, authTokenType, account);
                } else {
                    grantsCount = accounts.accountsDb.findMatchingGrantsCountAnyToken(callerUid, account);
                }
                boolean permissionGranted = grantsCount > 0;
                if (permissionGranted || !ActivityManager.isRunningInTestHarness()) {
                    return permissionGranted;
                }
                Log.d(TAG, "no credentials permission for usage of " + account + ", " + authTokenType + " by uid " + callerUid + " but ignoring since device is in test harness.");
                return true;
            }
        }
    }

    private boolean isSystemUid(int callingUid) {
        long ident = Binder.clearCallingIdentity();
        try {
            String[] packages = this.mPackageManager.getPackagesForUid(callingUid);
            if (packages != null) {
                for (String name : packages) {
                    try {
                        PackageInfo packageInfo = this.mPackageManager.getPackageInfo(name, 0);
                        if (packageInfo != null && (packageInfo.applicationInfo.flags & 1) != 0) {
                            return true;
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.w(TAG, String.format("Could not find package [%s]", name), e);
                    }
                }
            } else {
                Log.w(TAG, "No known packages with uid " + callingUid);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void checkReadAccountsPermitted(int callingUid, String accountType, int userId, String opPackageName) {
        if (!isAccountVisibleToCaller(accountType, callingUid, userId, opPackageName)) {
            String msg = String.format("caller uid %s cannot access %s accounts", Integer.valueOf(callingUid), accountType);
            Log.w(TAG, "  " + msg);
            throw new SecurityException(msg);
        }
    }

    private boolean canUserModifyAccounts(int userId, int callingUid) {
        return isProfileOwner(callingUid) || !getUserManager().getUserRestrictions(new UserHandle(userId)).getBoolean("no_modify_accounts");
    }

    private boolean canUserModifyAccountsForType(int userId, String accountType, int callingUid) {
        if (isProfileOwner(callingUid)) {
            return true;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) this.mContext.getSystemService("device_policy");
        String[] typesArray = dpm.getAccountTypesWithManagementDisabledAsUser(userId);
        if (typesArray == null) {
            return true;
        }
        for (String forbiddenType : typesArray) {
            if (forbiddenType.equals(accountType)) {
                return false;
            }
        }
        return true;
    }

    private boolean isProfileOwner(int uid) {
        DevicePolicyManagerInternal dpmi = (DevicePolicyManagerInternal) LocalServices.getService(DevicePolicyManagerInternal.class);
        return dpmi != null && dpmi.isActiveAdminWithPolicy(uid, -1);
    }

    public void updateAppPermission(Account account, String authTokenType, int uid, boolean value) throws RemoteException {
        int callingUid = getCallingUid();
        if (UserHandle.getAppId(callingUid) != 1000) {
            throw new SecurityException();
        }
        if (value) {
            grantAppPermission(account, authTokenType, uid);
        } else {
            revokeAppPermission(account, authTokenType, uid);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void grantAppPermission(final Account account, String authTokenType, final int uid) {
        if (account == null || authTokenType == null) {
            Log.e(TAG, "grantAppPermission: called with invalid arguments", new Exception());
            return;
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                long accountId = accounts.accountsDb.findDeAccountId(account);
                if (accountId >= 0) {
                    accounts.accountsDb.insertGrant(accountId, authTokenType, uid);
                }
                cancelNotification(getCredentialPermissionNotificationId(account, authTokenType, uid), UserHandle.of(accounts.userId));
                cancelAccountAccessRequestNotificationIfNeeded(account, uid, true);
            }
        }
        Iterator<AccountManagerInternal.OnAppPermissionChangeListener> it = this.mAppPermissionChangeListeners.iterator();
        while (it.hasNext()) {
            final AccountManagerInternal.OnAppPermissionChangeListener listener = it.next();
            this.mHandler.post(new Runnable() { // from class: com.android.server.accounts.-$$Lambda$AccountManagerService$nCdu9dc3c8qBwJIwS0ZQk2waXfY
                @Override // java.lang.Runnable
                public final void run() {
                    listener.onAppPermissionChanged(account, uid);
                }
            });
        }
    }

    private void revokeAppPermission(final Account account, String authTokenType, final int uid) {
        if (account == null || authTokenType == null) {
            Log.e(TAG, "revokeAppPermission: called with invalid arguments", new Exception());
            return;
        }
        UserAccounts accounts = getUserAccounts(UserHandle.getUserId(uid));
        synchronized (accounts.dbLock) {
            synchronized (accounts.cacheLock) {
                accounts.accountsDb.beginTransaction();
                try {
                    long accountId = accounts.accountsDb.findDeAccountId(account);
                    if (accountId >= 0) {
                        accounts.accountsDb.deleteGrantsByAccountIdAuthTokenTypeAndUid(accountId, authTokenType, uid);
                        accounts.accountsDb.setTransactionSuccessful();
                    }
                    accounts.accountsDb.endTransaction();
                    cancelNotification(getCredentialPermissionNotificationId(account, authTokenType, uid), UserHandle.of(accounts.userId));
                } catch (Throwable th) {
                    accounts.accountsDb.endTransaction();
                    throw th;
                }
            }
        }
        Iterator<AccountManagerInternal.OnAppPermissionChangeListener> it = this.mAppPermissionChangeListeners.iterator();
        while (it.hasNext()) {
            final AccountManagerInternal.OnAppPermissionChangeListener listener = it.next();
            this.mHandler.post(new Runnable() { // from class: com.android.server.accounts.-$$Lambda$AccountManagerService$b-wmW_X7TIC2Bc_zEKaPtyELmHY
                @Override // java.lang.Runnable
                public final void run() {
                    listener.onAppPermissionChanged(account, uid);
                }
            });
        }
    }

    private void removeAccountFromCacheLocked(UserAccounts accounts, Account account) {
        Account[] oldAccountsForType = accounts.accountCache.get(account.type);
        if (oldAccountsForType != null) {
            ArrayList<Account> newAccountsList = new ArrayList<>();
            for (Account curAccount : oldAccountsForType) {
                if (!curAccount.equals(account)) {
                    newAccountsList.add(curAccount);
                }
            }
            if (newAccountsList.isEmpty()) {
                accounts.accountCache.remove(account.type);
            } else {
                Account[] newAccountsForType = new Account[newAccountsList.size()];
                accounts.accountCache.put(account.type, (Account[]) newAccountsList.toArray(newAccountsForType));
            }
        }
        accounts.userDataCache.remove(account);
        accounts.authTokenCache.remove(account);
        accounts.previousNameCache.remove(account);
        accounts.visibilityCache.remove(account);
    }

    private Account insertAccountIntoCacheLocked(UserAccounts accounts, Account account) {
        Account[] accountsForType = accounts.accountCache.get(account.type);
        int oldLength = accountsForType != null ? accountsForType.length : 0;
        Account[] newAccountsForType = new Account[oldLength + 1];
        if (accountsForType != null) {
            System.arraycopy(accountsForType, 0, newAccountsForType, 0, oldLength);
        }
        String token = account.getAccessId() != null ? account.getAccessId() : UUID.randomUUID().toString();
        newAccountsForType[oldLength] = new Account(account, token);
        accounts.accountCache.put(account.type, newAccountsForType);
        return newAccountsForType[oldLength];
    }

    private Account[] filterAccounts(UserAccounts accounts, Account[] unfiltered, int callingUid, String callingPackage, boolean includeManagedNotVisible) {
        String visibilityFilterPackage = callingPackage;
        if (visibilityFilterPackage == null) {
            visibilityFilterPackage = getPackageNameForUid(callingUid);
        }
        Map<Account, Integer> firstPass = new LinkedHashMap<>();
        for (Account account : unfiltered) {
            int visibility = resolveAccountVisibility(account, visibilityFilterPackage, accounts).intValue();
            if (visibility == 1 || visibility == 2 || (includeManagedNotVisible && visibility == 4)) {
                firstPass.put(account, Integer.valueOf(visibility));
            }
        }
        Map<Account, Integer> secondPass = filterSharedAccounts(accounts, firstPass, callingUid, callingPackage);
        Account[] filtered = new Account[secondPass.size()];
        return (Account[]) secondPass.keySet().toArray(filtered);
    }

    private Map<Account, Integer> filterSharedAccounts(UserAccounts userAccounts, Map<Account, Integer> unfiltered, int callingUid, String callingPackage) {
        if (getUserManager() != null && userAccounts != null && userAccounts.userId >= 0 && callingUid != 1000) {
            UserInfo user = getUserManager().getUserInfo(userAccounts.userId);
            if (user != null && user.isRestricted()) {
                String[] packages = this.mPackageManager.getPackagesForUid(callingUid);
                if (packages == null) {
                    packages = new String[0];
                }
                String[] packages2 = packages;
                String visibleList = this.mContext.getResources().getString(17039634);
                for (String packageName : packages2) {
                    if (visibleList.contains(";" + packageName + ";")) {
                        return unfiltered;
                    }
                }
                Account[] sharedAccounts = getSharedAccountsAsUser(userAccounts.userId);
                if (ArrayUtils.isEmpty(sharedAccounts)) {
                    return unfiltered;
                }
                String requiredAccountType = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                try {
                    if (callingPackage != null) {
                        PackageInfo pi = this.mPackageManager.getPackageInfo(callingPackage, 0);
                        if (pi != null && pi.restrictedAccountType != null) {
                            requiredAccountType = pi.restrictedAccountType;
                        }
                    } else {
                        int length = packages2.length;
                        int i = 0;
                        while (true) {
                            if (i >= length) {
                                break;
                            }
                            String packageName2 = packages2[i];
                            PackageInfo pi2 = this.mPackageManager.getPackageInfo(packageName2, 0);
                            if (pi2 == null || pi2.restrictedAccountType == null) {
                                i++;
                            } else {
                                requiredAccountType = pi2.restrictedAccountType;
                                break;
                            }
                        }
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Log.d(TAG, "Package not found " + e.getMessage());
                }
                Map<Account, Integer> filtered = new LinkedHashMap<>();
                for (Map.Entry<Account, Integer> entry : unfiltered.entrySet()) {
                    Account account = entry.getKey();
                    if (account.type.equals(requiredAccountType)) {
                        filtered.put(account, entry.getValue());
                    } else {
                        boolean found = false;
                        int length2 = sharedAccounts.length;
                        int i2 = 0;
                        while (true) {
                            if (i2 >= length2) {
                                break;
                            }
                            Account shared = sharedAccounts[i2];
                            if (!shared.equals(account)) {
                                i2++;
                            } else {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            filtered.put(account, entry.getValue());
                        }
                    }
                }
                return filtered;
            }
            return unfiltered;
        }
        return unfiltered;
    }

    protected Account[] getAccountsFromCache(UserAccounts userAccounts, String accountType, int callingUid, String callingPackage, boolean includeManagedNotVisible) {
        Account[] accounts;
        Preconditions.checkState(!Thread.holdsLock(userAccounts.cacheLock), "Method should not be called with cacheLock");
        if (accountType != null) {
            synchronized (userAccounts.cacheLock) {
                accounts = userAccounts.accountCache.get(accountType);
            }
            if (accounts == null) {
                return EMPTY_ACCOUNT_ARRAY;
            }
            return filterAccounts(userAccounts, (Account[]) Arrays.copyOf(accounts, accounts.length), callingUid, callingPackage, includeManagedNotVisible);
        }
        int totalLength = 0;
        synchronized (userAccounts.cacheLock) {
            for (Account[] accounts2 : userAccounts.accountCache.values()) {
                totalLength += accounts2.length;
            }
            if (totalLength == 0) {
                return EMPTY_ACCOUNT_ARRAY;
            }
            Account[] accountsArray = new Account[totalLength];
            int totalLength2 = 0;
            for (Account[] accountsOfType : userAccounts.accountCache.values()) {
                System.arraycopy(accountsOfType, 0, accountsArray, totalLength2, accountsOfType.length);
                totalLength2 += accountsOfType.length;
            }
            return filterAccounts(userAccounts, accountsArray, callingUid, callingPackage, includeManagedNotVisible);
        }
    }

    protected void writeUserDataIntoCacheLocked(UserAccounts accounts, Account account, String key, String value) {
        Map<String, String> userDataForAccount = (Map) accounts.userDataCache.get(account);
        if (userDataForAccount == null) {
            userDataForAccount = accounts.accountsDb.findUserExtrasForAccount(account);
            accounts.userDataCache.put(account, userDataForAccount);
        }
        if (value == null) {
            userDataForAccount.remove(key);
        } else {
            userDataForAccount.put(key, value);
        }
    }

    protected String readCachedTokenInternal(UserAccounts accounts, Account account, String tokenType, String callingPackage, byte[] pkgSigDigest) {
        String str;
        synchronized (accounts.cacheLock) {
            str = accounts.accountTokenCaches.get(account, tokenType, callingPackage, pkgSigDigest);
        }
        return str;
    }

    protected void writeAuthTokenIntoCacheLocked(UserAccounts accounts, Account account, String key, String value) {
        Map<String, String> authTokensForAccount = (Map) accounts.authTokenCache.get(account);
        if (authTokensForAccount == null) {
            authTokensForAccount = accounts.accountsDb.findAuthTokensByAccount(account);
            accounts.authTokenCache.put(account, authTokensForAccount);
        }
        if (value == null) {
            authTokensForAccount.remove(key);
        } else {
            authTokensForAccount.put(key, value);
        }
    }

    protected String readAuthTokenInternal(UserAccounts accounts, Account account, String authTokenType) {
        String str;
        synchronized (accounts.cacheLock) {
            Map<String, String> authTokensForAccount = (Map) accounts.authTokenCache.get(account);
            if (authTokensForAccount != null) {
                return authTokensForAccount.get(authTokenType);
            }
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    Map<String, String> authTokensForAccount2 = (Map) accounts.authTokenCache.get(account);
                    if (authTokensForAccount2 == null) {
                        authTokensForAccount2 = accounts.accountsDb.findAuthTokensByAccount(account);
                        accounts.authTokenCache.put(account, authTokensForAccount2);
                    }
                    str = authTokensForAccount2.get(authTokenType);
                }
            }
            return str;
        }
    }

    private String readUserDataInternal(UserAccounts accounts, Account account, String key) {
        Map<String, String> userDataForAccount;
        synchronized (accounts.cacheLock) {
            userDataForAccount = (Map) accounts.userDataCache.get(account);
        }
        if (userDataForAccount == null) {
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    userDataForAccount = (Map) accounts.userDataCache.get(account);
                    if (userDataForAccount == null) {
                        userDataForAccount = accounts.accountsDb.findUserExtrasForAccount(account);
                        accounts.userDataCache.put(account, userDataForAccount);
                    }
                }
            }
        }
        return userDataForAccount.get(key);
    }

    private Context getContextForUser(UserHandle user) {
        try {
            return this.mContext.createPackageContextAsUser(this.mContext.getPackageName(), 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            return this.mContext;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendResponse(IAccountManagerResponse response, Bundle result) {
        try {
            response.onResult(result);
        } catch (RemoteException e) {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "failure while notifying response", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendErrorResponse(IAccountManagerResponse response, int errorCode, String errorMessage) {
        try {
            response.onError(errorCode, errorMessage);
        } catch (RemoteException e) {
            if (Log.isLoggable(TAG, 2)) {
                Log.v(TAG, "failure while notifying response", e);
            }
        }
    }

    /* loaded from: classes.dex */
    private final class AccountManagerInternalImpl extends AccountManagerInternal {
        @GuardedBy("mLock")
        private AccountManagerBackupHelper mBackupHelper;
        private final Object mLock;

        private AccountManagerInternalImpl() {
            this.mLock = new Object();
        }

        public void requestAccountAccess(Account account, String packageName, int userId, RemoteCallback callback) {
            UserAccounts userAccounts;
            if (account == null) {
                Slog.w(AccountManagerService.TAG, "account cannot be null");
            } else if (packageName == null) {
                Slog.w(AccountManagerService.TAG, "packageName cannot be null");
            } else if (userId < 0) {
                Slog.w(AccountManagerService.TAG, "user id must be concrete");
            } else if (callback != null) {
                int visibility = AccountManagerService.this.resolveAccountVisibility(account, packageName, AccountManagerService.this.getUserAccounts(userId)).intValue();
                if (visibility == 3) {
                    Slog.w(AccountManagerService.TAG, "requestAccountAccess: account is hidden");
                } else if (!AccountManagerService.this.hasAccountAccess(account, packageName, new UserHandle(userId))) {
                    try {
                        int uid = AccountManagerService.this.mPackageManager.getPackageUidAsUser(packageName, userId);
                        Intent intent = AccountManagerService.this.newRequestAccountAccessIntent(account, packageName, uid, callback);
                        synchronized (AccountManagerService.this.mUsers) {
                            userAccounts = (UserAccounts) AccountManagerService.this.mUsers.get(userId);
                        }
                        SystemNotificationChannels.createAccountChannelForPackage(packageName, uid, AccountManagerService.this.mContext);
                        AccountManagerService.this.doNotification(userAccounts, account, null, intent, packageName, userId);
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.e(AccountManagerService.TAG, "Unknown package " + packageName);
                    }
                } else {
                    Bundle result = new Bundle();
                    result.putBoolean("booleanResult", true);
                    callback.sendResult(result);
                }
            } else {
                Slog.w(AccountManagerService.TAG, "callback cannot be null");
            }
        }

        public void addOnAppPermissionChangeListener(AccountManagerInternal.OnAppPermissionChangeListener listener) {
            AccountManagerService.this.mAppPermissionChangeListeners.add(listener);
        }

        public boolean hasAccountAccess(Account account, int uid) {
            return AccountManagerService.this.hasAccountAccess(account, (String) null, uid);
        }

        public byte[] backupAccountAccessPermissions(int userId) {
            byte[] backupAccountAccessPermissions;
            synchronized (this.mLock) {
                if (this.mBackupHelper == null) {
                    this.mBackupHelper = new AccountManagerBackupHelper(AccountManagerService.this, this);
                }
                backupAccountAccessPermissions = this.mBackupHelper.backupAccountAccessPermissions(userId);
            }
            return backupAccountAccessPermissions;
        }

        public void restoreAccountAccessPermissions(byte[] data, int userId) {
            synchronized (this.mLock) {
                if (this.mBackupHelper == null) {
                    this.mBackupHelper = new AccountManagerBackupHelper(AccountManagerService.this, this);
                }
                this.mBackupHelper.restoreAccountAccessPermissions(data, userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class Injector {
        private final Context mContext;

        public Injector(Context context) {
            this.mContext = context;
        }

        Looper getMessageHandlerLooper() {
            ServiceThread serviceThread = new ServiceThread(AccountManagerService.TAG, -2, true);
            serviceThread.start();
            return serviceThread.getLooper();
        }

        Context getContext() {
            return this.mContext;
        }

        void addLocalService(AccountManagerInternal service) {
            LocalServices.addService(AccountManagerInternal.class, service);
        }

        String getDeDatabaseName(int userId) {
            File databaseFile = new File(Environment.getDataSystemDeDirectory(userId), "accounts_de.db");
            return databaseFile.getPath();
        }

        String getCeDatabaseName(int userId) {
            File databaseFile = new File(Environment.getDataSystemCeDirectory(userId), "accounts_ce.db");
            return databaseFile.getPath();
        }

        String getPreNDatabaseName(int userId) {
            File systemDir = Environment.getDataSystemDirectory();
            File databaseFile = new File(Environment.getUserSystemDirectory(userId), AccountManagerService.PRE_N_DATABASE_NAME);
            if (userId == 0) {
                File oldFile = new File(systemDir, AccountManagerService.PRE_N_DATABASE_NAME);
                if (oldFile.exists() && !databaseFile.exists()) {
                    File userDir = Environment.getUserSystemDirectory(userId);
                    if (!userDir.exists() && !userDir.mkdirs()) {
                        throw new IllegalStateException("User dir cannot be created: " + userDir);
                    } else if (!oldFile.renameTo(databaseFile)) {
                        throw new IllegalStateException("User dir cannot be migrated: " + databaseFile);
                    }
                }
            }
            return databaseFile.getPath();
        }

        IAccountAuthenticatorCache getAccountAuthenticatorCache() {
            return new AccountAuthenticatorCache(this.mContext);
        }

        INotificationManager getNotificationManager() {
            return NotificationManager.getService();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class NotificationId {
        private final int mId;
        final String mTag;

        NotificationId(String tag, int type) {
            this.mTag = tag;
            this.mId = type;
        }
    }
}
