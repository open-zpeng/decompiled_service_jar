package com.android.server.accounts;

import android.accounts.Account;
import android.accounts.AccountManagerInternal;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.PackageUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.accounts.AccountManagerService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public final class AccountManagerBackupHelper {
    private static final String ATTR_ACCOUNT_SHA_256 = "account-sha-256";
    private static final String ATTR_DIGEST = "digest";
    private static final String ATTR_PACKAGE = "package";
    private static final long PENDING_RESTORE_TIMEOUT_MILLIS = 3600000;
    private static final String TAG = "AccountManagerBackupHelper";
    private static final String TAG_PERMISSION = "permission";
    private static final String TAG_PERMISSIONS = "permissions";
    private final AccountManagerInternal mAccountManagerInternal;
    private final AccountManagerService mAccountManagerService;
    private final Object mLock = new Object();
    @GuardedBy({"mLock"})
    private Runnable mRestoreCancelCommand;
    @GuardedBy({"mLock"})
    private RestorePackageMonitor mRestorePackageMonitor;
    @GuardedBy({"mLock"})
    private List<PendingAppPermission> mRestorePendingAppPermissions;

    public AccountManagerBackupHelper(AccountManagerService accountManagerService, AccountManagerInternal accountManagerInternal) {
        this.mAccountManagerService = accountManagerService;
        this.mAccountManagerInternal = accountManagerInternal;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class PendingAppPermission {
        private final String accountDigest;
        private final String certDigest;
        private final String packageName;
        private final int userId;

        public PendingAppPermission(String accountDigest, String packageName, String certDigest, int userId) {
            this.accountDigest = accountDigest;
            this.packageName = packageName;
            this.certDigest = certDigest;
            this.userId = userId;
        }

        public boolean apply(PackageManager packageManager) {
            Account account = null;
            AccountManagerService.UserAccounts accounts = AccountManagerBackupHelper.this.mAccountManagerService.getUserAccounts(this.userId);
            synchronized (accounts.dbLock) {
                synchronized (accounts.cacheLock) {
                    for (Account[] accountsPerType : accounts.accountCache.values()) {
                        int length = accountsPerType.length;
                        int i = 0;
                        while (true) {
                            if (i >= length) {
                                break;
                            }
                            Account accountPerType = accountsPerType[i];
                            if (!this.accountDigest.equals(PackageUtils.computeSha256Digest(accountPerType.name.getBytes()))) {
                                i++;
                            } else {
                                account = accountPerType;
                                break;
                            }
                        }
                        if (account != null) {
                            break;
                        }
                    }
                }
            }
            if (account == null) {
                return false;
            }
            try {
                PackageInfo packageInfo = packageManager.getPackageInfoAsUser(this.packageName, 64, this.userId);
                String[] signaturesSha256Digests = PackageUtils.computeSignaturesSha256Digests(packageInfo.signatures);
                String signaturesSha256Digest = PackageUtils.computeSignaturesSha256Digest(signaturesSha256Digests);
                if (this.certDigest.equals(signaturesSha256Digest) || (packageInfo.signatures.length > 1 && this.certDigest.equals(signaturesSha256Digests[0]))) {
                    int uid = packageInfo.applicationInfo.uid;
                    if (!AccountManagerBackupHelper.this.mAccountManagerInternal.hasAccountAccess(account, uid)) {
                        AccountManagerBackupHelper.this.mAccountManagerService.grantAppPermission(account, "com.android.AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE", uid);
                    }
                    return true;
                }
                return false;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
    }

    public byte[] backupAccountAccessPermissions(int userId) {
        List<Pair<String, Integer>> allAccountGrants;
        int i;
        int i2 = userId;
        AccountManagerService.UserAccounts accounts = this.mAccountManagerService.getUserAccounts(i2);
        synchronized (accounts.dbLock) {
            try {
                try {
                    try {
                        synchronized (accounts.cacheLock) {
                            try {
                                List<Pair<String, Integer>> allAccountGrants2 = accounts.accountsDb.findAllAccountGrants();
                                if (!allAccountGrants2.isEmpty()) {
                                    try {
                                        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
                                        FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                                        fastXmlSerializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
                                        fastXmlSerializer.startDocument(null, true);
                                        fastXmlSerializer.startTag(null, TAG_PERMISSIONS);
                                        PackageManager packageManager = this.mAccountManagerService.mContext.getPackageManager();
                                        for (Pair<String, Integer> grant : allAccountGrants2) {
                                            String accountName = (String) grant.first;
                                            int uid = ((Integer) grant.second).intValue();
                                            String[] packageNames = packageManager.getPackagesForUid(uid);
                                            if (packageNames != null) {
                                                int length = packageNames.length;
                                                int i3 = 0;
                                                while (i3 < length) {
                                                    String packageName = packageNames[i3];
                                                    AccountManagerService.UserAccounts accounts2 = accounts;
                                                    try {
                                                        try {
                                                            PackageInfo packageInfo = packageManager.getPackageInfoAsUser(packageName, 64, i2);
                                                            String digest = PackageUtils.computeSignaturesSha256Digest(packageInfo.signatures);
                                                            if (digest != null) {
                                                                allAccountGrants = allAccountGrants2;
                                                                try {
                                                                    fastXmlSerializer.startTag(null, TAG_PERMISSION);
                                                                    i = length;
                                                                    fastXmlSerializer.attribute(null, ATTR_ACCOUNT_SHA_256, PackageUtils.computeSha256Digest(accountName.getBytes()));
                                                                    fastXmlSerializer.attribute(null, "package", packageName);
                                                                    fastXmlSerializer.attribute(null, ATTR_DIGEST, digest);
                                                                    fastXmlSerializer.endTag(null, TAG_PERMISSION);
                                                                } catch (IOException e) {
                                                                    e = e;
                                                                    Log.e(TAG, "Error backing up account access grants", e);
                                                                    return null;
                                                                }
                                                            } else {
                                                                allAccountGrants = allAccountGrants2;
                                                                i = length;
                                                            }
                                                        } catch (IOException e2) {
                                                            e = e2;
                                                            Log.e(TAG, "Error backing up account access grants", e);
                                                            return null;
                                                        }
                                                    } catch (PackageManager.NameNotFoundException e3) {
                                                        allAccountGrants = allAccountGrants2;
                                                        i = length;
                                                        Slog.i(TAG, "Skipping backup of account access grant for non-existing package: " + packageName);
                                                    }
                                                    i3++;
                                                    i2 = userId;
                                                    accounts = accounts2;
                                                    allAccountGrants2 = allAccountGrants;
                                                    length = i;
                                                }
                                                i2 = userId;
                                            }
                                        }
                                        fastXmlSerializer.endTag(null, TAG_PERMISSIONS);
                                        fastXmlSerializer.endDocument();
                                        fastXmlSerializer.flush();
                                        return dataStream.toByteArray();
                                    } catch (IOException e4) {
                                        e = e4;
                                    }
                                } else {
                                    try {
                                        try {
                                            return null;
                                        } catch (Throwable th) {
                                            th = th;
                                            throw th;
                                        }
                                    } catch (Throwable th2) {
                                        e = th2;
                                        throw e;
                                    }
                                }
                            } catch (Throwable th3) {
                                e = th3;
                            }
                        }
                    } catch (Throwable th4) {
                        e = th4;
                    }
                } catch (Throwable th5) {
                    th = th5;
                }
            } catch (Throwable th6) {
                th = th6;
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r13v0 */
    /* JADX WARN: Type inference failed for: r13v1, types: [com.android.server.accounts.AccountManagerBackupHelper$1, java.lang.String] */
    /* JADX WARN: Type inference failed for: r13v2 */
    /* JADX WARN: Type inference failed for: r13v3 */
    public void restoreAccountAccessPermissions(byte[] data, int userId) {
        try {
            try {
                ByteArrayInputStream dataStream = new ByteArrayInputStream(data);
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(dataStream, StandardCharsets.UTF_8.name());
                PackageManager packageManager = this.mAccountManagerService.mContext.getPackageManager();
                int permissionsOuterDepth = parser.getDepth();
                while (true) {
                    ?? r13 = 0;
                    if (XmlUtils.nextElementWithin(parser, permissionsOuterDepth)) {
                        if (TAG_PERMISSIONS.equals(parser.getName())) {
                            int permissionOuterDepth = parser.getDepth();
                            while (XmlUtils.nextElementWithin(parser, permissionOuterDepth)) {
                                if (TAG_PERMISSION.equals(parser.getName())) {
                                    String accountDigest = parser.getAttributeValue(r13, ATTR_ACCOUNT_SHA_256);
                                    if (TextUtils.isEmpty(accountDigest)) {
                                        XmlUtils.skipCurrentTag(parser);
                                    }
                                    String packageName = parser.getAttributeValue(r13, "package");
                                    if (TextUtils.isEmpty(packageName)) {
                                        XmlUtils.skipCurrentTag(parser);
                                    }
                                    String digest = parser.getAttributeValue(r13, ATTR_DIGEST);
                                    if (TextUtils.isEmpty(digest)) {
                                        XmlUtils.skipCurrentTag(parser);
                                    }
                                    PendingAppPermission pendingAppPermission = new PendingAppPermission(accountDigest, packageName, digest, userId);
                                    if (!pendingAppPermission.apply(packageManager)) {
                                        synchronized (this.mLock) {
                                            if (this.mRestorePackageMonitor == null) {
                                                this.mRestorePackageMonitor = new RestorePackageMonitor();
                                                this.mRestorePackageMonitor.register(this.mAccountManagerService.mContext, this.mAccountManagerService.mHandler.getLooper(), true);
                                            }
                                            if (this.mRestorePendingAppPermissions == null) {
                                                this.mRestorePendingAppPermissions = new ArrayList();
                                            }
                                            this.mRestorePendingAppPermissions.add(pendingAppPermission);
                                        }
                                    }
                                    r13 = 0;
                                }
                            }
                        }
                    } else {
                        this.mRestoreCancelCommand = new CancelRestoreCommand();
                        this.mAccountManagerService.mHandler.postDelayed(this.mRestoreCancelCommand, 3600000L);
                        return;
                    }
                }
            } catch (IOException | XmlPullParserException e) {
                e = e;
                Log.e(TAG, "Error restoring app permissions", e);
            }
        } catch (IOException | XmlPullParserException e2) {
            e = e2;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class RestorePackageMonitor extends PackageMonitor {
        private RestorePackageMonitor() {
        }

        public void onPackageAdded(String packageName, int uid) {
            synchronized (AccountManagerBackupHelper.this.mLock) {
                if (AccountManagerBackupHelper.this.mRestorePendingAppPermissions == null) {
                    return;
                }
                if (UserHandle.getUserId(uid) != 0) {
                    return;
                }
                int count = AccountManagerBackupHelper.this.mRestorePendingAppPermissions.size();
                for (int i = count - 1; i >= 0; i--) {
                    PendingAppPermission pendingAppPermission = (PendingAppPermission) AccountManagerBackupHelper.this.mRestorePendingAppPermissions.get(i);
                    if (pendingAppPermission.packageName.equals(packageName) && pendingAppPermission.apply(AccountManagerBackupHelper.this.mAccountManagerService.mContext.getPackageManager())) {
                        AccountManagerBackupHelper.this.mRestorePendingAppPermissions.remove(i);
                    }
                }
                if (AccountManagerBackupHelper.this.mRestorePendingAppPermissions.isEmpty() && AccountManagerBackupHelper.this.mRestoreCancelCommand != null) {
                    AccountManagerBackupHelper.this.mAccountManagerService.mHandler.removeCallbacks(AccountManagerBackupHelper.this.mRestoreCancelCommand);
                    AccountManagerBackupHelper.this.mRestoreCancelCommand.run();
                    AccountManagerBackupHelper.this.mRestoreCancelCommand = null;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class CancelRestoreCommand implements Runnable {
        private CancelRestoreCommand() {
        }

        @Override // java.lang.Runnable
        public void run() {
            synchronized (AccountManagerBackupHelper.this.mLock) {
                AccountManagerBackupHelper.this.mRestorePendingAppPermissions = null;
                if (AccountManagerBackupHelper.this.mRestorePackageMonitor != null) {
                    AccountManagerBackupHelper.this.mRestorePackageMonitor.unregister();
                    AccountManagerBackupHelper.this.mRestorePackageMonitor = null;
                }
            }
        }
    }
}
