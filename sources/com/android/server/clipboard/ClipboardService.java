package com.android.server.clipboard;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import com.android.server.SystemService;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.clipboard.HostClipboardMonitor;
import java.util.HashSet;
import java.util.List;
/* loaded from: classes.dex */
public class ClipboardService extends SystemService {
    private static final boolean IS_EMULATOR = SystemProperties.getBoolean("ro.kernel.qemu", false);
    private static final String TAG = "ClipboardService";
    private final IActivityManager mAm;
    private final AppOpsManager mAppOps;
    private final SparseArray<PerUserClipboard> mClipboards;
    private HostClipboardMonitor mHostClipboardMonitor;
    private Thread mHostMonitorThread;
    private final IBinder mPermissionOwner;
    private final PackageManager mPm;
    private final IUserManager mUm;

    public ClipboardService(Context context) {
        super(context);
        IBinder permOwner = null;
        this.mHostClipboardMonitor = null;
        this.mHostMonitorThread = null;
        this.mClipboards = new SparseArray<>();
        this.mAm = ActivityManager.getService();
        this.mPm = getContext().getPackageManager();
        this.mUm = ServiceManager.getService("user");
        this.mAppOps = (AppOpsManager) getContext().getSystemService("appops");
        try {
            permOwner = this.mAm.newUriPermissionOwner("clipboard");
        } catch (RemoteException e) {
            Slog.w("clipboard", "AM dead", e);
        }
        this.mPermissionOwner = permOwner;
        if (IS_EMULATOR) {
            this.mHostClipboardMonitor = new HostClipboardMonitor(new HostClipboardMonitor.HostClipboardCallback() { // from class: com.android.server.clipboard.ClipboardService.1
                @Override // com.android.server.clipboard.HostClipboardMonitor.HostClipboardCallback
                public void onHostClipboardUpdated(String contents) {
                    ClipData clip = new ClipData("host clipboard", new String[]{"text/plain"}, new ClipData.Item(contents));
                    synchronized (ClipboardService.this.mClipboards) {
                        ClipboardService.this.setPrimaryClipInternal(ClipboardService.this.getClipboard(0), clip, 1000);
                    }
                }
            });
            this.mHostMonitorThread = new Thread(this.mHostClipboardMonitor);
            this.mHostMonitorThread.start();
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("clipboard", new ClipboardImpl());
    }

    @Override // com.android.server.SystemService
    public void onCleanupUser(int userId) {
        synchronized (this.mClipboards) {
            this.mClipboards.remove(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ListenerInfo {
        final String mPackageName;
        final int mUid;

        ListenerInfo(int uid, String packageName) {
            this.mUid = uid;
            this.mPackageName = packageName;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PerUserClipboard {
        ClipData primaryClip;
        final int userId;
        final RemoteCallbackList<IOnPrimaryClipChangedListener> primaryClipListeners = new RemoteCallbackList<>();
        int primaryClipUid = 9999;
        final HashSet<String> activePermissionOwners = new HashSet<>();

        PerUserClipboard(int userId) {
            this.userId = userId;
        }
    }

    /* loaded from: classes.dex */
    private class ClipboardImpl extends IClipboard.Stub {
        private ClipboardImpl() {
        }

        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                if (!(e instanceof SecurityException)) {
                    Slog.wtf("clipboard", "Exception: ", e);
                }
                throw e;
            }
        }

        public void setPrimaryClip(ClipData clip, String callingPackage) {
            synchronized (this) {
                if (clip != null) {
                    try {
                        if (clip.getItemCount() > 0) {
                            int callingUid = Binder.getCallingUid();
                            if (ClipboardService.this.clipboardAccessAllowed(30, callingPackage, callingUid)) {
                                ClipboardService.this.checkDataOwnerLocked(clip, callingUid);
                                ClipboardService.this.setPrimaryClipInternal(clip, callingUid);
                                return;
                            }
                            return;
                        }
                    } catch (Throwable th) {
                        throw th;
                    }
                }
                throw new IllegalArgumentException("No items");
            }
        }

        public void clearPrimaryClip(String callingPackage) {
            synchronized (this) {
                int callingUid = Binder.getCallingUid();
                if (ClipboardService.this.clipboardAccessAllowed(30, callingPackage, callingUid)) {
                    ClipboardService.this.setPrimaryClipInternal(null, callingUid);
                }
            }
        }

        public ClipData getPrimaryClip(String pkg) {
            synchronized (this) {
                if (ClipboardService.this.clipboardAccessAllowed(29, pkg, Binder.getCallingUid()) && !ClipboardService.this.isDeviceLocked()) {
                    ClipboardService.this.addActiveOwnerLocked(Binder.getCallingUid(), pkg);
                    return ClipboardService.this.getClipboard().primaryClip;
                }
                return null;
            }
        }

        public ClipDescription getPrimaryClipDescription(String callingPackage) {
            synchronized (this) {
                if (ClipboardService.this.clipboardAccessAllowed(29, callingPackage, Binder.getCallingUid()) && !ClipboardService.this.isDeviceLocked()) {
                    PerUserClipboard clipboard = ClipboardService.this.getClipboard();
                    return clipboard.primaryClip != null ? clipboard.primaryClip.getDescription() : null;
                }
                return null;
            }
        }

        public boolean hasPrimaryClip(String callingPackage) {
            synchronized (this) {
                if (ClipboardService.this.clipboardAccessAllowed(29, callingPackage, Binder.getCallingUid()) && !ClipboardService.this.isDeviceLocked()) {
                    return ClipboardService.this.getClipboard().primaryClip != null;
                }
                return false;
            }
        }

        public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, String callingPackage) {
            synchronized (this) {
                ClipboardService.this.getClipboard().primaryClipListeners.register(listener, new ListenerInfo(Binder.getCallingUid(), callingPackage));
            }
        }

        public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
            synchronized (this) {
                ClipboardService.this.getClipboard().primaryClipListeners.unregister(listener);
            }
        }

        public boolean hasClipboardText(String callingPackage) {
            synchronized (this) {
                boolean z = false;
                if (ClipboardService.this.clipboardAccessAllowed(29, callingPackage, Binder.getCallingUid()) && !ClipboardService.this.isDeviceLocked()) {
                    PerUserClipboard clipboard = ClipboardService.this.getClipboard();
                    if (clipboard.primaryClip != null) {
                        CharSequence text = clipboard.primaryClip.getItemAt(0).getText();
                        if (text != null && text.length() > 0) {
                            z = true;
                        }
                        return z;
                    }
                    return false;
                }
                return false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PerUserClipboard getClipboard() {
        return getClipboard(UserHandle.getCallingUserId());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PerUserClipboard getClipboard(int userId) {
        PerUserClipboard puc;
        synchronized (this.mClipboards) {
            puc = this.mClipboards.get(userId);
            if (puc == null) {
                puc = new PerUserClipboard(userId);
                this.mClipboards.put(userId, puc);
            }
        }
        return puc;
    }

    List<UserInfo> getRelatedProfiles(int userId) {
        long origId = Binder.clearCallingIdentity();
        try {
            List<UserInfo> related = this.mUm.getProfiles(userId, true);
            return related;
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception calling UserManager: " + e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private boolean hasRestriction(String restriction, int userId) {
        try {
            return this.mUm.hasUserRestriction(restriction, userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote Exception calling UserManager.getUserRestrictions: ", e);
            return true;
        }
    }

    void setPrimaryClipInternal(ClipData clip, int callingUid) {
        int size;
        ClipData clip2;
        CharSequence text;
        if (this.mHostClipboardMonitor != null) {
            if (clip == null) {
                this.mHostClipboardMonitor.setHostClipboard(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            } else if (clip.getItemCount() > 0 && (text = clip.getItemAt(0).getText()) != null) {
                this.mHostClipboardMonitor.setHostClipboard(text.toString());
            }
        }
        int userId = UserHandle.getUserId(callingUid);
        setPrimaryClipInternal(getClipboard(userId), clip, callingUid);
        List<UserInfo> related = getRelatedProfiles(userId);
        if (related != null && (size = related.size()) > 1) {
            boolean canCopy = !hasRestriction("no_cross_profile_copy_paste", userId);
            if (!canCopy) {
                clip2 = null;
            } else {
                clip2 = new ClipData(clip);
                for (int i = clip2.getItemCount() - 1; i >= 0; i--) {
                    clip2.setItemAt(i, new ClipData.Item(clip2.getItemAt(i)));
                }
                clip2.fixUrisLight(userId);
            }
            for (int i2 = 0; i2 < size; i2++) {
                int id = related.get(i2).id;
                if (id != userId) {
                    boolean canCopyIntoProfile = !hasRestriction("no_sharing_into_profile", id);
                    if (canCopyIntoProfile) {
                        setPrimaryClipInternal(getClipboard(id), clip2, callingUid);
                    }
                }
            }
        }
    }

    void setPrimaryClipInternal(PerUserClipboard clipboard, ClipData clip, int callingUid) {
        ClipDescription description;
        revokeUris(clipboard);
        clipboard.activePermissionOwners.clear();
        if (clip == null && clipboard.primaryClip == null) {
            return;
        }
        clipboard.primaryClip = clip;
        if (clip != null) {
            clipboard.primaryClipUid = callingUid;
        } else {
            clipboard.primaryClipUid = 9999;
        }
        if (clip != null && (description = clip.getDescription()) != null) {
            description.setTimestamp(System.currentTimeMillis());
        }
        long ident = Binder.clearCallingIdentity();
        int n = clipboard.primaryClipListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                ListenerInfo li = (ListenerInfo) clipboard.primaryClipListeners.getBroadcastCookie(i);
                if (clipboardAccessAllowed(29, li.mPackageName, li.mUid)) {
                    clipboard.primaryClipListeners.getBroadcastItem(i).dispatchPrimaryClipChanged();
                }
            } catch (RemoteException e) {
            } catch (Throwable th) {
                clipboard.primaryClipListeners.finishBroadcast();
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
        }
        clipboard.primaryClipListeners.finishBroadcast();
        Binder.restoreCallingIdentity(ident);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isDeviceLocked() {
        boolean z;
        int callingUserId = UserHandle.getCallingUserId();
        long token = Binder.clearCallingIdentity();
        try {
            KeyguardManager keyguardManager = (KeyguardManager) getContext().getSystemService(KeyguardManager.class);
            if (keyguardManager != null) {
                if (keyguardManager.isDeviceLocked(callingUserId)) {
                    z = true;
                    return z;
                }
            }
            z = false;
            return z;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private final void checkUriOwnerLocked(Uri uri, int sourceUid) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mAm.checkGrantUriPermission(sourceUid, (String) null, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    private final void checkItemOwnerLocked(ClipData.Item item, int uid) {
        if (item.getUri() != null) {
            checkUriOwnerLocked(item.getUri(), uid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            checkUriOwnerLocked(intent.getData(), uid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void checkDataOwnerLocked(ClipData data, int uid) {
        int N = data.getItemCount();
        for (int i = 0; i < N; i++) {
            checkItemOwnerLocked(data.getItemAt(i), uid);
        }
    }

    private final void grantUriLocked(Uri uri, int sourceUid, String targetPkg, int targetUserId) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mAm.grantUriPermissionFromOwner(this.mPermissionOwner, sourceUid, targetPkg, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)), targetUserId);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    private final void grantItemLocked(ClipData.Item item, int sourceUid, String targetPkg, int targetUserId) {
        if (item.getUri() != null) {
            grantUriLocked(item.getUri(), sourceUid, targetPkg, targetUserId);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            grantUriLocked(intent.getData(), sourceUid, targetPkg, targetUserId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final void addActiveOwnerLocked(int uid, String pkg) {
        PackageInfo pi;
        IPackageManager pm = AppGlobals.getPackageManager();
        int targetUserHandle = UserHandle.getCallingUserId();
        long oldIdentity = Binder.clearCallingIdentity();
        try {
            pi = pm.getPackageInfo(pkg, 0, targetUserHandle);
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(oldIdentity);
            throw th;
        }
        if (pi == null) {
            throw new IllegalArgumentException("Unknown package " + pkg);
        } else if (!UserHandle.isSameApp(pi.applicationInfo.uid, uid)) {
            throw new SecurityException("Calling uid " + uid + " does not own package " + pkg);
        } else {
            Binder.restoreCallingIdentity(oldIdentity);
            PerUserClipboard clipboard = getClipboard();
            if (clipboard.primaryClip != null && !clipboard.activePermissionOwners.contains(pkg)) {
                int N = clipboard.primaryClip.getItemCount();
                for (int i = 0; i < N; i++) {
                    grantItemLocked(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid, pkg, UserHandle.getUserId(uid));
                }
                clipboard.activePermissionOwners.add(pkg);
            }
        }
    }

    private final void revokeUriLocked(Uri uri, int sourceUid) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mAm.revokeUriPermissionFromOwner(this.mPermissionOwner, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } catch (RemoteException e) {
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
        Binder.restoreCallingIdentity(ident);
    }

    private final void revokeItemLocked(ClipData.Item item, int sourceUid) {
        if (item.getUri() != null) {
            revokeUriLocked(item.getUri(), sourceUid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            revokeUriLocked(intent.getData(), sourceUid);
        }
    }

    private final void revokeUris(PerUserClipboard clipboard) {
        if (clipboard.primaryClip == null) {
            return;
        }
        int N = clipboard.primaryClip.getItemCount();
        for (int i = 0; i < N; i++) {
            revokeItemLocked(clipboard.primaryClip.getItemAt(i), clipboard.primaryClipUid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean clipboardAccessAllowed(int op, String callingPackage, int callingUid) {
        if (this.mAppOps.noteOp(op, callingUid, callingPackage) != 0) {
            return false;
        }
        try {
            if (!AppGlobals.getPackageManager().isInstantApp(callingPackage, UserHandle.getUserId(callingUid))) {
                return true;
            }
            return this.mAm.isAppForeground(callingUid);
        } catch (RemoteException e) {
            Slog.e("clipboard", "Failed to get Instant App status for package " + callingPackage, e);
            return false;
        }
    }
}
