package com.android.server.uri;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.GrantedUriPermission;
import android.app.IUriGrantsManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.DumpState;
import com.android.server.uri.UriPermission;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/* loaded from: classes2.dex */
public class UriGrantsManagerService extends IUriGrantsManager.Stub {
    private static final String ATTR_CREATED_TIME = "createdTime";
    private static final String ATTR_MODE_FLAGS = "modeFlags";
    private static final String ATTR_PREFIX = "prefix";
    private static final String ATTR_SOURCE_PKG = "sourcePkg";
    private static final String ATTR_SOURCE_USER_ID = "sourceUserId";
    private static final String ATTR_TARGET_PKG = "targetPkg";
    private static final String ATTR_TARGET_USER_ID = "targetUserId";
    private static final String ATTR_URI = "uri";
    private static final String ATTR_USER_HANDLE = "userHandle";
    private static final boolean DEBUG = false;
    private static final int MAX_PERSISTED_URI_GRANTS = 128;
    private static final String TAG = "UriGrantsManagerService";
    private static final String TAG_URI_GRANT = "uri-grant";
    private static final String TAG_URI_GRANTS = "uri-grants";
    ActivityManagerInternal mAmInternal;
    private final Context mContext;
    private final AtomicFile mGrantFile;
    private final SparseArray<ArrayMap<GrantUri, UriPermission>> mGrantedUriPermissions;
    private final H mH;
    private final Object mLock;
    PackageManagerInternal mPmInternal;

    private UriGrantsManagerService(Context context) {
        this.mLock = new Object();
        this.mGrantedUriPermissions = new SparseArray<>();
        this.mContext = context;
        this.mH = new H(IoThread.get().getLooper());
        File systemDir = SystemServiceManager.ensureSystemDir();
        this.mGrantFile = new AtomicFile(new File(systemDir, "urigrants.xml"), TAG_URI_GRANTS);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void start() {
        LocalServices.addService(UriGrantsManagerInternal.class, new LocalService());
    }

    void onActivityManagerInternalAdded() {
        this.mAmInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
    }

    /* loaded from: classes2.dex */
    public static final class Lifecycle extends SystemService {
        private final UriGrantsManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            this.mService = new UriGrantsManagerService(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService("uri_grants", this.mService);
            this.mService.start();
        }

        public UriGrantsManagerService getService() {
            return this.mService;
        }
    }

    public void grantUriPermissionFromOwner(IBinder token, int fromUid, String targetPkg, Uri uri, int modeFlags, int sourceUserId, int targetUserId) {
        int targetUserId2 = this.mAmInternal.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), targetUserId, false, 2, "grantUriPermissionFromOwner", (String) null);
        synchronized (this.mLock) {
            try {
                try {
                    UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
                    try {
                        if (owner == null) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Unknown owner: ");
                            sb.append(token);
                            throw new IllegalArgumentException(sb.toString());
                        }
                        try {
                            if (fromUid != Binder.getCallingUid()) {
                                try {
                                    if (Binder.getCallingUid() != Process.myUid()) {
                                        throw new SecurityException("nice try");
                                    }
                                } catch (Throwable th) {
                                    th = th;
                                    throw th;
                                }
                            }
                            if (targetPkg == null) {
                                throw new IllegalArgumentException("null target");
                            }
                            if (uri == null) {
                                throw new IllegalArgumentException("null uri");
                            }
                            try {
                                grantUriPermission(fromUid, targetPkg, new GrantUri(sourceUserId, uri, false), modeFlags, owner, targetUserId2);
                            } catch (Throwable th2) {
                                th = th2;
                                throw th;
                            }
                        } catch (Throwable th3) {
                            th = th3;
                        }
                    } catch (Throwable th4) {
                        th = th4;
                    }
                } catch (Throwable th5) {
                    th = th5;
                }
            } catch (Throwable th6) {
                th = th6;
            }
        }
    }

    public ParceledListSlice<android.content.UriPermission> getUriPermissions(String packageName, boolean incoming, boolean persistedOnly) {
        enforceNotIsolatedCaller("getUriPermissions");
        Preconditions.checkNotNull(packageName, "packageName");
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            int packageUid = pm.getPackageUid(packageName, 786432, callingUserId);
            if (packageUid != callingUid) {
                throw new SecurityException("Package " + packageName + " does not belong to calling UID " + callingUid);
            }
            ArrayList<android.content.UriPermission> result = Lists.newArrayList();
            synchronized (this.mLock) {
                if (incoming) {
                    ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.get(callingUid);
                    if (perms == null) {
                        Slog.w(TAG, "No permission grants found for " + packageName);
                    } else {
                        for (int j = 0; j < perms.size(); j++) {
                            UriPermission perm = perms.valueAt(j);
                            if (packageName.equals(perm.targetPkg) && (!persistedOnly || perm.persistedModeFlags != 0)) {
                                result.add(perm.buildPersistedPublicApiObject());
                            }
                        }
                    }
                } else {
                    int size = this.mGrantedUriPermissions.size();
                    for (int i = 0; i < size; i++) {
                        ArrayMap<GrantUri, UriPermission> perms2 = this.mGrantedUriPermissions.valueAt(i);
                        for (int j2 = 0; j2 < perms2.size(); j2++) {
                            UriPermission perm2 = perms2.valueAt(j2);
                            if (packageName.equals(perm2.sourcePkg) && (!persistedOnly || perm2.persistedModeFlags != 0)) {
                                result.add(perm2.buildPersistedPublicApiObject());
                            }
                        }
                    }
                }
            }
            return new ParceledListSlice<>(result);
        } catch (RemoteException e) {
            throw new SecurityException("Failed to verify package name ownership");
        }
    }

    public ParceledListSlice<GrantedUriPermission> getGrantedUriPermissions(String packageName, int userId) {
        this.mAmInternal.enforceCallingPermission("android.permission.GET_APP_GRANTED_URI_PERMISSIONS", "getGrantedUriPermissions");
        List<GrantedUriPermission> result = new ArrayList<>();
        synchronized (this.mLock) {
            int size = this.mGrantedUriPermissions.size();
            for (int i = 0; i < size; i++) {
                ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.valueAt(i);
                for (int j = 0; j < perms.size(); j++) {
                    UriPermission perm = perms.valueAt(j);
                    if ((packageName == null || packageName.equals(perm.targetPkg)) && perm.targetUserId == userId && perm.persistedModeFlags != 0) {
                        result.add(perm.buildGrantedUriPermission());
                    }
                }
            }
        }
        return new ParceledListSlice<>(result);
    }

    /* JADX WARN: Code restructure failed: missing block: B:25:0x0075, code lost:
        r3 = false | r5.takePersistableModes(r13);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void takePersistableUriPermission(android.net.Uri r12, int r13, java.lang.String r14, int r15) {
        /*
            r11 = this;
            r0 = 0
            if (r14 == 0) goto L15
            android.app.ActivityManagerInternal r1 = r11.mAmInternal
            java.lang.String r2 = "android.permission.FORCE_PERSISTABLE_URI_PERMISSIONS"
            java.lang.String r3 = "takePersistableUriPermission"
            r1.enforceCallingPermission(r2, r3)
            android.content.pm.PackageManagerInternal r1 = r11.getPmInternal()
            int r1 = r1.getPackageUid(r14, r0, r15)
            goto L1e
        L15:
            java.lang.String r1 = "takePersistableUriPermission"
            r11.enforceNotIsolatedCaller(r1)
            int r1 = android.os.Binder.getCallingUid()
        L1e:
            r2 = 3
            com.android.internal.util.Preconditions.checkFlagsArgument(r13, r2)
            java.lang.Object r2 = r11.mLock
            monitor-enter(r2)
            r3 = 0
            com.android.server.uri.GrantUri r4 = new com.android.server.uri.GrantUri     // Catch: java.lang.Throwable -> L8d
            r4.<init>(r15, r12, r0)     // Catch: java.lang.Throwable -> L8d
            com.android.server.uri.UriPermission r5 = r11.findUriPermissionLocked(r1, r4)     // Catch: java.lang.Throwable -> L8d
            com.android.server.uri.GrantUri r6 = new com.android.server.uri.GrantUri     // Catch: java.lang.Throwable -> L8d
            r7 = 1
            r6.<init>(r15, r12, r7)     // Catch: java.lang.Throwable -> L8d
            com.android.server.uri.UriPermission r6 = r11.findUriPermissionLocked(r1, r6)     // Catch: java.lang.Throwable -> L8d
            if (r5 == 0) goto L42
            int r8 = r5.persistableModeFlags     // Catch: java.lang.Throwable -> L8d
            r8 = r8 & r13
            if (r8 != r13) goto L42
            r8 = r7
            goto L43
        L42:
            r8 = r0
        L43:
            if (r6 == 0) goto L4b
            int r9 = r6.persistableModeFlags     // Catch: java.lang.Throwable -> L8d
            r9 = r9 & r13
            if (r9 != r13) goto L4b
            r0 = r7
        L4b:
            if (r8 != 0) goto L73
            if (r0 == 0) goto L50
            goto L73
        L50:
            java.lang.SecurityException r7 = new java.lang.SecurityException     // Catch: java.lang.Throwable -> L8d
            java.lang.StringBuilder r9 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L8d
            r9.<init>()     // Catch: java.lang.Throwable -> L8d
            java.lang.String r10 = "No persistable permission grants found for UID "
            r9.append(r10)     // Catch: java.lang.Throwable -> L8d
            r9.append(r1)     // Catch: java.lang.Throwable -> L8d
            java.lang.String r10 = " and Uri "
            r9.append(r10)     // Catch: java.lang.Throwable -> L8d
            java.lang.String r10 = r4.toSafeString()     // Catch: java.lang.Throwable -> L8d
            r9.append(r10)     // Catch: java.lang.Throwable -> L8d
            java.lang.String r9 = r9.toString()     // Catch: java.lang.Throwable -> L8d
            r7.<init>(r9)     // Catch: java.lang.Throwable -> L8d
            throw r7     // Catch: java.lang.Throwable -> L8d
        L73:
            if (r8 == 0) goto L7a
            boolean r7 = r5.takePersistableModes(r13)     // Catch: java.lang.Throwable -> L8d
            r3 = r3 | r7
        L7a:
            if (r0 == 0) goto L81
            boolean r7 = r6.takePersistableModes(r13)     // Catch: java.lang.Throwable -> L8d
            r3 = r3 | r7
        L81:
            boolean r7 = r11.maybePrunePersistedUriGrants(r1)     // Catch: java.lang.Throwable -> L8d
            r3 = r3 | r7
            if (r3 == 0) goto L8b
            r11.schedulePersistUriGrants()     // Catch: java.lang.Throwable -> L8d
        L8b:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L8d
            return
        L8d:
            r0 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L8d
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.uri.UriGrantsManagerService.takePersistableUriPermission(android.net.Uri, int, java.lang.String, int):void");
    }

    public void clearGrantedUriPermissions(String packageName, int userId) {
        this.mAmInternal.enforceCallingPermission("android.permission.CLEAR_APP_GRANTED_URI_PERMISSIONS", "clearGrantedUriPermissions");
        synchronized (this.mLock) {
            removeUriPermissionsForPackage(packageName, userId, true, true);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:17:0x0065, code lost:
        r3 = false | r0.releasePersistableModes(r10);
        removeUriPermissionIfNeeded(r0);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void releasePersistableUriPermission(android.net.Uri r9, int r10, java.lang.String r11, int r12) {
        /*
            r8 = this;
            r0 = 0
            if (r11 == 0) goto L15
            android.app.ActivityManagerInternal r1 = r8.mAmInternal
            java.lang.String r2 = "android.permission.FORCE_PERSISTABLE_URI_PERMISSIONS"
            java.lang.String r3 = "releasePersistableUriPermission"
            r1.enforceCallingPermission(r2, r3)
            android.content.pm.PackageManagerInternal r1 = r8.getPmInternal()
            int r1 = r1.getPackageUid(r11, r0, r12)
            goto L1e
        L15:
            java.lang.String r1 = "releasePersistableUriPermission"
            r8.enforceNotIsolatedCaller(r1)
            int r1 = android.os.Binder.getCallingUid()
        L1e:
            r2 = 3
            com.android.internal.util.Preconditions.checkFlagsArgument(r10, r2)
            java.lang.Object r2 = r8.mLock
            monitor-enter(r2)
            r3 = 0
            com.android.server.uri.GrantUri r4 = new com.android.server.uri.GrantUri     // Catch: java.lang.Throwable -> L7e
            r4.<init>(r12, r9, r0)     // Catch: java.lang.Throwable -> L7e
            com.android.server.uri.UriPermission r0 = r8.findUriPermissionLocked(r1, r4)     // Catch: java.lang.Throwable -> L7e
            com.android.server.uri.GrantUri r4 = new com.android.server.uri.GrantUri     // Catch: java.lang.Throwable -> L7e
            r5 = 1
            r4.<init>(r12, r9, r5)     // Catch: java.lang.Throwable -> L7e
            com.android.server.uri.UriPermission r4 = r8.findUriPermissionLocked(r1, r4)     // Catch: java.lang.Throwable -> L7e
            if (r0 != 0) goto L63
            if (r4 != 0) goto L63
            if (r11 == 0) goto L40
            goto L63
        L40:
            java.lang.SecurityException r5 = new java.lang.SecurityException     // Catch: java.lang.Throwable -> L7e
            java.lang.StringBuilder r6 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L7e
            r6.<init>()     // Catch: java.lang.Throwable -> L7e
            java.lang.String r7 = "No permission grants found for UID "
            r6.append(r7)     // Catch: java.lang.Throwable -> L7e
            r6.append(r1)     // Catch: java.lang.Throwable -> L7e
            java.lang.String r7 = " and Uri "
            r6.append(r7)     // Catch: java.lang.Throwable -> L7e
            java.lang.String r7 = r9.toSafeString()     // Catch: java.lang.Throwable -> L7e
            r6.append(r7)     // Catch: java.lang.Throwable -> L7e
            java.lang.String r6 = r6.toString()     // Catch: java.lang.Throwable -> L7e
            r5.<init>(r6)     // Catch: java.lang.Throwable -> L7e
            throw r5     // Catch: java.lang.Throwable -> L7e
        L63:
            if (r0 == 0) goto L6d
            boolean r5 = r0.releasePersistableModes(r10)     // Catch: java.lang.Throwable -> L7e
            r3 = r3 | r5
            r8.removeUriPermissionIfNeeded(r0)     // Catch: java.lang.Throwable -> L7e
        L6d:
            if (r4 == 0) goto L77
            boolean r5 = r4.releasePersistableModes(r10)     // Catch: java.lang.Throwable -> L7e
            r3 = r3 | r5
            r8.removeUriPermissionIfNeeded(r4)     // Catch: java.lang.Throwable -> L7e
        L77:
            if (r3 == 0) goto L7c
            r8.schedulePersistUriGrants()     // Catch: java.lang.Throwable -> L7e
        L7c:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L7e
            return
        L7e:
            r0 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L7e
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.uri.UriGrantsManagerService.releasePersistableUriPermission(android.net.Uri, int, java.lang.String, int):void");
    }

    void removeUriPermissionsForPackage(String packageName, int userHandle, boolean persistable, boolean targetOnly) {
        if (userHandle == -1 && packageName == null) {
            throw new IllegalArgumentException("Must narrow by either package or user");
        }
        boolean persistChanged = false;
        int N = this.mGrantedUriPermissions.size();
        int i = 0;
        while (i < N) {
            int targetUid = this.mGrantedUriPermissions.keyAt(i);
            ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.valueAt(i);
            if (userHandle == -1 || userHandle == UserHandle.getUserId(targetUid)) {
                Iterator<UriPermission> it = perms.values().iterator();
                while (it.hasNext()) {
                    UriPermission perm = it.next();
                    if (packageName == null || ((!targetOnly && perm.sourcePkg.equals(packageName)) || perm.targetPkg.equals(packageName))) {
                        if (!"downloads".equals(perm.uri.uri.getAuthority()) || persistable) {
                            persistChanged |= perm.revokeModes(persistable ? -1 : -65, true);
                            if (perm.modeFlags == 0) {
                                it.remove();
                            }
                        }
                    }
                }
                if (perms.isEmpty()) {
                    this.mGrantedUriPermissions.remove(targetUid);
                    N--;
                    i--;
                }
            }
            i++;
        }
        if (persistChanged) {
            schedulePersistUriGrants();
        }
    }

    boolean checkAuthorityGrants(int callingUid, ProviderInfo cpi, int userId, boolean checkUser) {
        ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.get(callingUid);
        if (perms != null) {
            for (int i = perms.size() - 1; i >= 0; i--) {
                GrantUri grantUri = perms.keyAt(i);
                if ((grantUri.sourceUserId == userId || !checkUser) && matchesProvider(grantUri.uri, cpi)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private boolean matchesProvider(Uri uri, ProviderInfo cpi) {
        String uriAuth = uri.getAuthority();
        String cpiAuth = cpi.authority;
        if (cpiAuth.indexOf(59) == -1) {
            return cpiAuth.equals(uriAuth);
        }
        String[] cpiAuths = cpiAuth.split(";");
        for (String str : cpiAuths) {
            if (str.equals(uriAuth)) {
                return true;
            }
        }
        return false;
    }

    private boolean maybePrunePersistedUriGrants(int uid) {
        ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.get(uid);
        if (perms == null || perms.size() < 128) {
            return false;
        }
        ArrayList<UriPermission> persisted = Lists.newArrayList();
        for (UriPermission perm : perms.values()) {
            if (perm.persistedModeFlags != 0) {
                persisted.add(perm);
            }
        }
        int trimCount = persisted.size() - 128;
        if (trimCount <= 0) {
            return false;
        }
        Collections.sort(persisted, new UriPermission.PersistedTimeComparator());
        for (int i = 0; i < trimCount; i++) {
            UriPermission perm2 = persisted.get(i);
            perm2.releasePersistableModes(-1);
            removeUriPermissionIfNeeded(perm2);
        }
        return true;
    }

    NeededUriGrants checkGrantUriPermissionFromIntent(int callingUid, String targetPkg, Intent intent, int mode, NeededUriGrants needed, int targetUserId) {
        int contentUserHint;
        int targetUid;
        NeededUriGrants newNeeded;
        NeededUriGrants needed2;
        NeededUriGrants needed3 = needed;
        if (targetPkg != null) {
            if (intent == null) {
                return null;
            }
            Uri data = intent.getData();
            ClipData clip = intent.getClipData();
            if (data == null && clip == null) {
                return null;
            }
            int contentUserHint2 = intent.getContentUserHint();
            if (contentUserHint2 != -2) {
                contentUserHint = contentUserHint2;
            } else {
                contentUserHint = UserHandle.getUserId(callingUid);
            }
            IPackageManager pm = AppGlobals.getPackageManager();
            if (needed3 == null) {
                try {
                    targetUid = pm.getPackageUid(targetPkg, 268435456, targetUserId);
                    if (targetUid < 0) {
                        return null;
                    }
                } catch (RemoteException e) {
                    return null;
                }
            } else {
                targetUid = needed3.targetUid;
            }
            if (data != null) {
                GrantUri grantUri = GrantUri.resolve(contentUserHint, data);
                targetUid = checkGrantUriPermission(callingUid, targetPkg, grantUri, mode, targetUid);
                if (targetUid > 0) {
                    if (needed3 == null) {
                        needed2 = new NeededUriGrants(targetPkg, targetUid, mode);
                    } else {
                        needed2 = needed3;
                    }
                    needed2.add(grantUri);
                    needed3 = needed2;
                }
            }
            if (clip == null) {
                return needed3;
            }
            int targetUid2 = targetUid;
            NeededUriGrants needed4 = needed3;
            for (int targetUid3 = 0; targetUid3 < clip.getItemCount(); targetUid3++) {
                Uri uri = clip.getItemAt(targetUid3).getUri();
                if (uri != null) {
                    GrantUri grantUri2 = GrantUri.resolve(contentUserHint, uri);
                    int targetUid4 = checkGrantUriPermission(callingUid, targetPkg, grantUri2, mode, targetUid2);
                    if (targetUid4 > 0) {
                        if (needed4 == null) {
                            needed4 = new NeededUriGrants(targetPkg, targetUid4, mode);
                        }
                        needed4.add(grantUri2);
                    }
                    targetUid2 = targetUid4;
                } else {
                    Intent clipIntent = clip.getItemAt(targetUid3).getIntent();
                    if (clipIntent != null && (newNeeded = checkGrantUriPermissionFromIntent(callingUid, targetPkg, clipIntent, mode, needed4, targetUserId)) != null) {
                        needed4 = newNeeded;
                    }
                }
            }
            return needed4;
        }
        throw new NullPointerException(ATTR_TARGET_PKG);
    }

    void grantUriPermissionFromIntent(int callingUid, String targetPkg, Intent intent, UriPermissionOwner owner, int targetUserId) {
        NeededUriGrants needed = checkGrantUriPermissionFromIntent(callingUid, targetPkg, intent, intent != null ? intent.getFlags() : 0, null, targetUserId);
        if (needed == null) {
            return;
        }
        grantUriPermissionUncheckedFromIntent(needed, owner);
    }

    /* JADX WARN: Removed duplicated region for block: B:32:0x00b8 A[Catch: XmlPullParserException -> 0x010b, IOException -> 0x010d, FileNotFoundException -> 0x010f, all -> 0x0141, TRY_ENTER, TryCatch #2 {all -> 0x0141, blocks: (B:16:0x0090, B:18:0x0097, B:21:0x00a0, B:23:0x00a8, B:32:0x00b8, B:35:0x00dc, B:51:0x0134, B:54:0x013c), top: B:66:0x000b }] */
    /* JADX WARN: Removed duplicated region for block: B:33:0x00d1  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    void readGrantedUriPermissions() {
        /*
            Method dump skipped, instructions count: 335
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.uri.UriGrantsManagerService.readGrantedUriPermissions():void");
    }

    private UriPermission findOrCreateUriPermission(String sourcePkg, String targetPkg, int targetUid, GrantUri grantUri) {
        ArrayMap<GrantUri, UriPermission> targetUris = this.mGrantedUriPermissions.get(targetUid);
        if (targetUris == null) {
            targetUris = Maps.newArrayMap();
            this.mGrantedUriPermissions.put(targetUid, targetUris);
        }
        UriPermission perm = targetUris.get(grantUri);
        if (perm == null) {
            UriPermission perm2 = new UriPermission(sourcePkg, targetPkg, targetUid, grantUri);
            targetUris.put(grantUri, perm2);
            return perm2;
        }
        return perm;
    }

    private void grantUriPermissionUnchecked(int targetUid, String targetPkg, GrantUri grantUri, int modeFlags, UriPermissionOwner owner) {
        if (!Intent.isAccessUriMode(modeFlags)) {
            return;
        }
        String authority = grantUri.uri.getAuthority();
        ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId, 268435456);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for grant: " + grantUri.toSafeString());
            return;
        }
        if ((modeFlags & 128) != 0) {
            grantUri.prefix = true;
        }
        UriPermission perm = findOrCreateUriPermission(pi.packageName, targetPkg, targetUid, grantUri);
        perm.grantModes(modeFlags, owner);
    }

    void grantUriPermissionUncheckedFromIntent(NeededUriGrants needed, UriPermissionOwner owner) {
        if (needed == null) {
            return;
        }
        for (int i = 0; i < needed.size(); i++) {
            GrantUri grantUri = needed.get(i);
            grantUriPermissionUnchecked(needed.targetUid, needed.targetPkg, grantUri, needed.flags, owner);
        }
    }

    void grantUriPermission(int callingUid, String targetPkg, GrantUri grantUri, int modeFlags, UriPermissionOwner owner, int targetUserId) {
        if (targetPkg == null) {
            throw new NullPointerException(ATTR_TARGET_PKG);
        }
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            int targetUid = checkGrantUriPermission(callingUid, targetPkg, grantUri, modeFlags, pm.getPackageUid(targetPkg, 268435456, targetUserId));
            if (targetUid < 0) {
                return;
            }
            grantUriPermissionUnchecked(targetUid, targetPkg, grantUri, modeFlags, owner);
        } catch (RemoteException e) {
        }
    }

    void revokeUriPermission(String targetPackage, int callingUid, GrantUri grantUri, int modeFlags) {
        IPackageManager pm = AppGlobals.getPackageManager();
        String authority = grantUri.uri.getAuthority();
        ProviderInfo pi = getProviderInfo(authority, grantUri.sourceUserId, 786432);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for permission revoke: " + grantUri.toSafeString());
        } else if (!checkHoldingPermissions(pm, pi, grantUri, callingUid, modeFlags)) {
            ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.get(callingUid);
            if (perms != null) {
                boolean persistChanged = false;
                for (int i = perms.size() - 1; i >= 0; i--) {
                    UriPermission perm = perms.valueAt(i);
                    if ((targetPackage == null || targetPackage.equals(perm.targetPkg)) && perm.uri.sourceUserId == grantUri.sourceUserId && perm.uri.uri.isPathPrefixMatch(grantUri.uri)) {
                        persistChanged |= perm.revokeModes(modeFlags | 64, false);
                        if (perm.modeFlags == 0) {
                            perms.removeAt(i);
                        }
                    }
                }
                if (perms.isEmpty()) {
                    this.mGrantedUriPermissions.remove(callingUid);
                }
                if (persistChanged) {
                    schedulePersistUriGrants();
                }
            }
        } else {
            boolean persistChanged2 = false;
            for (int i2 = this.mGrantedUriPermissions.size() - 1; i2 >= 0; i2--) {
                this.mGrantedUriPermissions.keyAt(i2);
                ArrayMap<GrantUri, UriPermission> perms2 = this.mGrantedUriPermissions.valueAt(i2);
                for (int j = perms2.size() - 1; j >= 0; j--) {
                    UriPermission perm2 = perms2.valueAt(j);
                    if ((targetPackage == null || targetPackage.equals(perm2.targetPkg)) && perm2.uri.sourceUserId == grantUri.sourceUserId && perm2.uri.uri.isPathPrefixMatch(grantUri.uri)) {
                        persistChanged2 |= perm2.revokeModes(modeFlags | 64, targetPackage == null);
                        if (perm2.modeFlags == 0) {
                            perms2.removeAt(j);
                        }
                    }
                }
                if (perms2.isEmpty()) {
                    this.mGrantedUriPermissions.removeAt(i2);
                }
            }
            if (persistChanged2) {
                schedulePersistUriGrants();
            }
        }
    }

    private boolean checkHoldingPermissions(IPackageManager pm, ProviderInfo pi, GrantUri grantUri, int uid, int modeFlags) {
        if (UserHandle.getUserId(uid) != grantUri.sourceUserId && ActivityManager.checkComponentPermission("android.permission.INTERACT_ACROSS_USERS", uid, -1, true) != 0) {
            return false;
        }
        return checkHoldingPermissionsInternal(pm, pi, grantUri, uid, modeFlags, true);
    }

    private boolean checkHoldingPermissionsInternal(IPackageManager pm, ProviderInfo pi, GrantUri grantUri, int uid, int modeFlags, boolean considerUidPermissions) {
        String ppwperm;
        String pprperm;
        if (pi.applicationInfo.uid == uid) {
            return true;
        }
        if (pi.exported) {
            boolean readMet = (modeFlags & 1) == 0;
            boolean writeMet = (modeFlags & 2) == 0;
            if (!readMet) {
                try {
                    if (pi.readPermission != null && considerUidPermissions && pm.checkUidPermission(pi.readPermission, uid) == 0) {
                        readMet = true;
                    }
                } catch (RemoteException e) {
                    return false;
                }
            }
            if (!writeMet && pi.writePermission != null && considerUidPermissions && pm.checkUidPermission(pi.writePermission, uid) == 0) {
                writeMet = true;
            }
            boolean allowDefaultRead = pi.readPermission == null;
            boolean allowDefaultWrite = pi.writePermission == null;
            PathPermission[] pps = pi.pathPermissions;
            if (pps != null) {
                try {
                    String path = grantUri.uri.getPath();
                    int i = pps.length;
                    while (i > 0 && (!readMet || !writeMet)) {
                        i--;
                        PathPermission pp = pps[i];
                        if (pp.match(path)) {
                            if (!readMet && (pprperm = pp.getReadPermission()) != null) {
                                if (considerUidPermissions && pm.checkUidPermission(pprperm, uid) == 0) {
                                    readMet = true;
                                } else {
                                    allowDefaultRead = false;
                                }
                            }
                            if (!writeMet && (ppwperm = pp.getWritePermission()) != null) {
                                if (considerUidPermissions && pm.checkUidPermission(ppwperm, uid) == 0) {
                                    writeMet = true;
                                } else {
                                    allowDefaultWrite = false;
                                }
                            }
                        }
                    }
                } catch (RemoteException e2) {
                    return false;
                }
            }
            if (allowDefaultRead) {
                readMet = true;
            }
            if (allowDefaultWrite) {
                writeMet = true;
            }
            return readMet && writeMet;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeUriPermissionIfNeeded(UriPermission perm) {
        ArrayMap<GrantUri, UriPermission> perms;
        if (perm.modeFlags != 0 || (perms = this.mGrantedUriPermissions.get(perm.targetUid)) == null) {
            return;
        }
        perms.remove(perm.uri);
        if (perms.isEmpty()) {
            this.mGrantedUriPermissions.remove(perm.targetUid);
        }
    }

    private UriPermission findUriPermissionLocked(int targetUid, GrantUri grantUri) {
        ArrayMap<GrantUri, UriPermission> targetUris = this.mGrantedUriPermissions.get(targetUid);
        if (targetUris != null) {
            return targetUris.get(grantUri);
        }
        return null;
    }

    private void schedulePersistUriGrants() {
        if (!this.mH.hasMessages(1)) {
            H h = this.mH;
            h.sendMessageDelayed(h.obtainMessage(1), JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    private ProviderInfo getProviderInfo(String authority, int userHandle, int pmFlags) {
        try {
            ProviderInfo pi = AppGlobals.getPackageManager().resolveContentProvider(authority, pmFlags | 2048, userHandle);
            return pi;
        } catch (RemoteException e) {
            return null;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:85:0x014d  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    int checkGrantUriPermission(int r19, java.lang.String r20, com.android.server.uri.GrantUri r21, int r22, int r23) {
        /*
            Method dump skipped, instructions count: 569
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.uri.UriGrantsManagerService.checkGrantUriPermission(int, java.lang.String, com.android.server.uri.GrantUri, int, int):int");
    }

    int checkGrantUriPermission(int callingUid, String targetPkg, Uri uri, int modeFlags, int userId) {
        return checkGrantUriPermission(callingUid, targetPkg, new GrantUri(userId, uri, false), modeFlags, -1);
    }

    boolean checkUriPermission(GrantUri grantUri, int uid, int modeFlags) {
        boolean persistable = (modeFlags & 64) != 0;
        int minStrength = persistable ? 3 : 1;
        if (uid == 0) {
            return true;
        }
        ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.get(uid);
        if (perms == null) {
            return false;
        }
        UriPermission exactPerm = perms.get(grantUri);
        if (exactPerm == null || exactPerm.getStrength(modeFlags) < minStrength) {
            int N = perms.size();
            for (int i = 0; i < N; i++) {
                UriPermission perm = perms.valueAt(i);
                if (perm.uri.prefix && grantUri.uri.isPathPrefixMatch(perm.uri.uri) && perm.getStrength(modeFlags) >= minStrength) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeGrantedUriPermissions() {
        long startTime = SystemClock.uptimeMillis();
        ArrayList<UriPermission.Snapshot> persist = Lists.newArrayList();
        synchronized (this) {
            int size = this.mGrantedUriPermissions.size();
            for (int i = 0; i < size; i++) {
                ArrayMap<GrantUri, UriPermission> perms = this.mGrantedUriPermissions.valueAt(i);
                for (UriPermission perm : perms.values()) {
                    if (perm.persistedModeFlags != 0) {
                        persist.add(perm.snapshot());
                    }
                }
            }
        }
        FileOutputStream fos = null;
        try {
            fos = this.mGrantFile.startWrite(startTime);
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(fos, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.startTag(null, TAG_URI_GRANTS);
            Iterator<UriPermission.Snapshot> it = persist.iterator();
            while (it.hasNext()) {
                UriPermission.Snapshot perm2 = it.next();
                fastXmlSerializer.startTag(null, TAG_URI_GRANT);
                XmlUtils.writeIntAttribute(fastXmlSerializer, ATTR_SOURCE_USER_ID, perm2.uri.sourceUserId);
                XmlUtils.writeIntAttribute(fastXmlSerializer, ATTR_TARGET_USER_ID, perm2.targetUserId);
                fastXmlSerializer.attribute(null, ATTR_SOURCE_PKG, perm2.sourcePkg);
                fastXmlSerializer.attribute(null, ATTR_TARGET_PKG, perm2.targetPkg);
                fastXmlSerializer.attribute(null, ATTR_URI, String.valueOf(perm2.uri.uri));
                XmlUtils.writeBooleanAttribute(fastXmlSerializer, ATTR_PREFIX, perm2.uri.prefix);
                XmlUtils.writeIntAttribute(fastXmlSerializer, ATTR_MODE_FLAGS, perm2.persistedModeFlags);
                XmlUtils.writeLongAttribute(fastXmlSerializer, ATTR_CREATED_TIME, perm2.persistedCreateTime);
                fastXmlSerializer.endTag(null, TAG_URI_GRANT);
            }
            fastXmlSerializer.endTag(null, TAG_URI_GRANTS);
            fastXmlSerializer.endDocument();
            this.mGrantFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                this.mGrantFile.failWrite(fos);
            }
        }
    }

    private PackageManagerInternal getPmInternal() {
        if (this.mPmInternal == null) {
            this.mPmInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        }
        return this.mPmInternal;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public final class H extends Handler {
        static final int PERSIST_URI_GRANTS_MSG = 1;

        public H(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                UriGrantsManagerService.this.writeGrantedUriPermissions();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public final class LocalService implements UriGrantsManagerInternal {
        LocalService() {
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void removeUriPermissionIfNeeded(UriPermission perm) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.removeUriPermissionIfNeeded(perm);
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void grantUriPermission(int callingUid, String targetPkg, GrantUri grantUri, int modeFlags, UriPermissionOwner owner, int targetUserId) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.grantUriPermission(callingUid, targetPkg, grantUri, modeFlags, owner, targetUserId);
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void revokeUriPermission(String targetPackage, int callingUid, GrantUri grantUri, int modeFlags) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.revokeUriPermission(targetPackage, callingUid, grantUri, modeFlags);
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public boolean checkUriPermission(GrantUri grantUri, int uid, int modeFlags) {
            boolean checkUriPermission;
            synchronized (UriGrantsManagerService.this.mLock) {
                checkUriPermission = UriGrantsManagerService.this.checkUriPermission(grantUri, uid, modeFlags);
            }
            return checkUriPermission;
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public int checkGrantUriPermission(int callingUid, String targetPkg, GrantUri uri, int modeFlags, int userId) {
            int checkGrantUriPermission;
            synchronized (UriGrantsManagerService.this.mLock) {
                checkGrantUriPermission = UriGrantsManagerService.this.checkGrantUriPermission(callingUid, targetPkg, uri, modeFlags, userId);
            }
            return checkGrantUriPermission;
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public int checkGrantUriPermission(int callingUid, String targetPkg, Uri uri, int modeFlags, int userId) {
            int checkGrantUriPermission;
            UriGrantsManagerService.this.enforceNotIsolatedCaller("checkGrantUriPermission");
            synchronized (UriGrantsManagerService.this.mLock) {
                checkGrantUriPermission = UriGrantsManagerService.this.checkGrantUriPermission(callingUid, targetPkg, uri, modeFlags, userId);
            }
            return checkGrantUriPermission;
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public NeededUriGrants checkGrantUriPermissionFromIntent(int callingUid, String targetPkg, Intent intent, int mode, NeededUriGrants needed, int targetUserId) {
            NeededUriGrants checkGrantUriPermissionFromIntent;
            synchronized (UriGrantsManagerService.this.mLock) {
                checkGrantUriPermissionFromIntent = UriGrantsManagerService.this.checkGrantUriPermissionFromIntent(callingUid, targetPkg, intent, mode, needed, targetUserId);
            }
            return checkGrantUriPermissionFromIntent;
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void grantUriPermissionFromIntent(int callingUid, String targetPkg, Intent intent, int targetUserId) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.grantUriPermissionFromIntent(callingUid, targetPkg, intent, null, targetUserId);
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void grantUriPermissionFromIntent(int callingUid, String targetPkg, Intent intent, UriPermissionOwner owner, int targetUserId) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.grantUriPermissionFromIntent(callingUid, targetPkg, intent, owner, targetUserId);
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void grantUriPermissionUncheckedFromIntent(NeededUriGrants needed, UriPermissionOwner owner) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.grantUriPermissionUncheckedFromIntent(needed, owner);
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void onSystemReady() {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.readGrantedUriPermissions();
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void onActivityManagerInternalAdded() {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.onActivityManagerInternalAdded();
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public IBinder newUriPermissionOwner(String name) {
            Binder externalToken;
            UriGrantsManagerService.this.enforceNotIsolatedCaller("newUriPermissionOwner");
            synchronized (UriGrantsManagerService.this.mLock) {
                UriPermissionOwner owner = new UriPermissionOwner(this, name);
                externalToken = owner.getExternalToken();
            }
            return externalToken;
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void removeUriPermissionsForPackage(String packageName, int userHandle, boolean persistable, boolean targetOnly) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriGrantsManagerService.this.removeUriPermissionsForPackage(packageName, userHandle, persistable, targetOnly);
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode, int userId) {
            synchronized (UriGrantsManagerService.this.mLock) {
                UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
                if (owner == null) {
                    throw new IllegalArgumentException("Unknown owner: " + token);
                } else if (uri == null) {
                    owner.removeUriPermissions(mode);
                } else {
                    boolean prefix = (mode & 128) != 0;
                    owner.removeUriPermission(new GrantUri(userId, uri, prefix), mode);
                }
            }
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public boolean checkAuthorityGrants(int callingUid, ProviderInfo cpi, int userId, boolean checkUser) {
            boolean checkAuthorityGrants;
            synchronized (UriGrantsManagerService.this.mLock) {
                checkAuthorityGrants = UriGrantsManagerService.this.checkAuthorityGrants(callingUid, cpi, userId, checkUser);
            }
            return checkAuthorityGrants;
        }

        @Override // com.android.server.uri.UriGrantsManagerInternal
        public void dump(PrintWriter pw, boolean dumpAll, String dumpPackage) {
            synchronized (UriGrantsManagerService.this.mLock) {
                boolean needSep = false;
                boolean printedAnything = false;
                if (UriGrantsManagerService.this.mGrantedUriPermissions.size() > 0) {
                    boolean printed = false;
                    int dumpUid = -2;
                    if (dumpPackage != null) {
                        try {
                            dumpUid = UriGrantsManagerService.this.mContext.getPackageManager().getPackageUidAsUser(dumpPackage, DumpState.DUMP_CHANGES, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            dumpUid = -1;
                        }
                    }
                    for (int i = 0; i < UriGrantsManagerService.this.mGrantedUriPermissions.size(); i++) {
                        int uid = UriGrantsManagerService.this.mGrantedUriPermissions.keyAt(i);
                        if (dumpUid < -1 || UserHandle.getAppId(uid) == dumpUid) {
                            ArrayMap<GrantUri, UriPermission> perms = (ArrayMap) UriGrantsManagerService.this.mGrantedUriPermissions.valueAt(i);
                            if (!printed) {
                                if (needSep) {
                                    pw.println();
                                }
                                needSep = true;
                                pw.println("  Granted Uri Permissions:");
                                printed = true;
                                printedAnything = true;
                            }
                            pw.print("  * UID ");
                            pw.print(uid);
                            pw.println(" holds:");
                            for (UriPermission perm : perms.values()) {
                                pw.print("    ");
                                pw.println(perm);
                                if (dumpAll) {
                                    perm.dump(pw, "      ");
                                }
                            }
                        }
                    }
                }
                if (!printedAnything) {
                    pw.println("  (nothing)");
                }
            }
        }
    }
}
