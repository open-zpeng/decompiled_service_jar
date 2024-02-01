package com.android.server.clipboard;

import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IUriGrantsManager;
import android.app.KeyguardManager;
import android.app.UriGrantsManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
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
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillManagerInternal;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.clipboard.HostClipboardMonitor;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import java.util.HashSet;
import java.util.List;

/* loaded from: classes.dex */
public class ClipboardService extends SystemService {
    private static final boolean IS_EMULATOR = SystemProperties.getBoolean("ro.kernel.qemu", false);
    private static final String TAG = "ClipboardService";
    private final ActivityManagerInternal mAmInternal;
    private final AppOpsManager mAppOps;
    private final AutofillManagerInternal mAutofillInternal;
    private final SparseArray<PerUserClipboard> mClipboards;
    private final ContentCaptureManagerInternal mContentCaptureInternal;
    private HostClipboardMonitor mHostClipboardMonitor;
    private Thread mHostMonitorThread;
    private final IBinder mPermissionOwner;
    private final PackageManager mPm;
    private final IUriGrantsManager mUgm;
    private final UriGrantsManagerInternal mUgmInternal;
    private final IUserManager mUm;
    private final WindowManagerInternal mWm;

    public ClipboardService(Context context) {
        super(context);
        this.mHostClipboardMonitor = null;
        this.mHostMonitorThread = null;
        this.mClipboards = new SparseArray<>();
        this.mAmInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        this.mUgm = UriGrantsManager.getService();
        this.mUgmInternal = (UriGrantsManagerInternal) LocalServices.getService(UriGrantsManagerInternal.class);
        this.mWm = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
        this.mPm = getContext().getPackageManager();
        this.mUm = ServiceManager.getService("user");
        this.mAppOps = (AppOpsManager) getContext().getSystemService("appops");
        this.mContentCaptureInternal = (ContentCaptureManagerInternal) LocalServices.getService(ContentCaptureManagerInternal.class);
        this.mAutofillInternal = (AutofillManagerInternal) LocalServices.getService(AutofillManagerInternal.class);
        IBinder permOwner = this.mUgmInternal.newUriPermissionOwner("clipboard");
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

    private boolean isInternalSysWindowAppWithWindowFocus(String callingPackage) {
        if (this.mPm.checkPermission("android.permission.INTERNAL_SYSTEM_WINDOW", callingPackage) == 0 && this.mWm.isUidFocused(Binder.getCallingUid())) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getIntendingUserId(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        if (!UserManager.supportsMultipleUsers() || callingUserId == userId) {
            return callingUserId;
        }
        int intendingUserId = this.mAmInternal.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, 2, "checkClipboardServiceCallingUser", packageName);
        return intendingUserId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int getIntendingUid(String packageName, int userId) {
        return UserHandle.getUid(getIntendingUserId(packageName, userId), UserHandle.getAppId(Binder.getCallingUid()));
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

        public void setPrimaryClip(ClipData clip, String callingPackage, int userId) {
            synchronized (this) {
                if (clip != null) {
                    if (clip.getItemCount() > 0) {
                        int intendingUid = ClipboardService.this.getIntendingUid(callingPackage, userId);
                        int intendingUserId = UserHandle.getUserId(intendingUid);
                        if (ClipboardService.this.clipboardAccessAllowed(30, callingPackage, intendingUid, intendingUserId)) {
                            ClipboardService.this.checkDataOwnerLocked(clip, intendingUid);
                            ClipboardService.this.setPrimaryClipInternal(clip, intendingUid);
                            return;
                        }
                        return;
                    }
                }
                throw new IllegalArgumentException("No items");
            }
        }

        public void clearPrimaryClip(String callingPackage, int userId) {
            synchronized (this) {
                int intendingUid = ClipboardService.this.getIntendingUid(callingPackage, userId);
                int intendingUserId = UserHandle.getUserId(intendingUid);
                if (ClipboardService.this.clipboardAccessAllowed(30, callingPackage, intendingUid, intendingUserId)) {
                    ClipboardService.this.setPrimaryClipInternal(null, intendingUid);
                }
            }
        }

        public ClipData getPrimaryClip(String pkg, int userId) {
            synchronized (this) {
                int intendingUid = ClipboardService.this.getIntendingUid(pkg, userId);
                int intendingUserId = UserHandle.getUserId(intendingUid);
                if (ClipboardService.this.clipboardAccessAllowed(29, pkg, intendingUid, intendingUserId) && !ClipboardService.this.isDeviceLocked(intendingUserId)) {
                    ClipboardService.this.addActiveOwnerLocked(intendingUid, pkg);
                    return ClipboardService.this.getClipboard(intendingUserId).primaryClip;
                }
                return null;
            }
        }

        public ClipDescription getPrimaryClipDescription(String callingPackage, int userId) {
            synchronized (this) {
                int intendingUid = ClipboardService.this.getIntendingUid(callingPackage, userId);
                int intendingUserId = UserHandle.getUserId(intendingUid);
                if (ClipboardService.this.clipboardAccessAllowed(29, callingPackage, intendingUid, intendingUserId) && !ClipboardService.this.isDeviceLocked(intendingUserId)) {
                    PerUserClipboard clipboard = ClipboardService.this.getClipboard(intendingUserId);
                    return clipboard.primaryClip != null ? clipboard.primaryClip.getDescription() : null;
                }
                return null;
            }
        }

        public boolean hasPrimaryClip(String callingPackage, int userId) {
            synchronized (this) {
                int intendingUid = ClipboardService.this.getIntendingUid(callingPackage, userId);
                int intendingUserId = UserHandle.getUserId(intendingUid);
                if (ClipboardService.this.clipboardAccessAllowed(29, callingPackage, intendingUid, intendingUserId) && !ClipboardService.this.isDeviceLocked(intendingUserId)) {
                    return ClipboardService.this.getClipboard(intendingUserId).primaryClip != null;
                }
                return false;
            }
        }

        public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, String callingPackage, int userId) {
            synchronized (this) {
                int intendingUid = ClipboardService.this.getIntendingUid(callingPackage, userId);
                int intendingUserId = UserHandle.getUserId(intendingUid);
                ClipboardService.this.getClipboard(intendingUserId).primaryClipListeners.register(listener, new ListenerInfo(intendingUid, callingPackage));
            }
        }

        public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, String callingPackage, int userId) {
            synchronized (this) {
                int intendingUserId = ClipboardService.this.getIntendingUserId(callingPackage, userId);
                ClipboardService.this.getClipboard(intendingUserId).primaryClipListeners.unregister(listener);
            }
        }

        public boolean hasClipboardText(String callingPackage, int userId) {
            synchronized (this) {
                int intendingUid = ClipboardService.this.getIntendingUid(callingPackage, userId);
                int intendingUserId = UserHandle.getUserId(intendingUid);
                boolean z = false;
                if (ClipboardService.this.clipboardAccessAllowed(29, callingPackage, intendingUid, intendingUserId) && !ClipboardService.this.isDeviceLocked(intendingUserId)) {
                    PerUserClipboard clipboard = ClipboardService.this.getClipboard(intendingUserId);
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

    void setPrimaryClipInternal(ClipData clip, int uid) {
        int size;
        CharSequence text;
        HostClipboardMonitor hostClipboardMonitor = this.mHostClipboardMonitor;
        if (hostClipboardMonitor != null) {
            if (clip == null) {
                hostClipboardMonitor.setHostClipboard("");
            } else if (clip.getItemCount() > 0 && (text = clip.getItemAt(0).getText()) != null) {
                this.mHostClipboardMonitor.setHostClipboard(text.toString());
            }
        }
        int userId = UserHandle.getUserId(uid);
        setPrimaryClipInternal(getClipboard(userId), clip, uid);
        List<UserInfo> related = getRelatedProfiles(userId);
        if (related != null && (size = related.size()) > 1) {
            boolean canCopy = !hasRestriction("no_cross_profile_copy_paste", userId);
            if (!canCopy) {
                clip = null;
            } else if (clip != null) {
                clip = new ClipData(clip);
                for (int i = clip.getItemCount() - 1; i >= 0; i--) {
                    clip.setItemAt(i, new ClipData.Item(clip.getItemAt(i)));
                }
                clip.fixUrisLight(userId);
            }
            for (int i2 = 0; i2 < size; i2++) {
                int id = related.get(i2).id;
                if (id != userId) {
                    boolean canCopyIntoProfile = !hasRestriction("no_sharing_into_profile", id);
                    if (canCopyIntoProfile) {
                        setPrimaryClipInternal(getClipboard(id), clip, uid);
                    }
                }
            }
        }
    }

    void setPrimaryClipInternal(PerUserClipboard clipboard, ClipData clip, int uid) {
        ClipDescription description;
        revokeUris(clipboard);
        clipboard.activePermissionOwners.clear();
        if (clip == null && clipboard.primaryClip == null) {
            return;
        }
        clipboard.primaryClip = clip;
        if (clip != null) {
            clipboard.primaryClipUid = uid;
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
                if (clipboardAccessAllowed(29, li.mPackageName, li.mUid, UserHandle.getUserId(li.mUid))) {
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
    public boolean isDeviceLocked(int userId) {
        boolean z;
        long token = Binder.clearCallingIdentity();
        try {
            KeyguardManager keyguardManager = (KeyguardManager) getContext().getSystemService(KeyguardManager.class);
            if (keyguardManager != null) {
                if (keyguardManager.isDeviceLocked(userId)) {
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
        if (uri == null || !ActivityTaskManagerInternal.ASSIST_KEY_CONTENT.equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mUgmInternal.checkGrantUriPermission(sourceUid, (String) null, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
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
        if (uri == null || !ActivityTaskManagerInternal.ASSIST_KEY_CONTENT.equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mUgm.grantUriPermissionFromOwner(this.mPermissionOwner, sourceUid, targetPkg, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)), targetUserId);
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
            PerUserClipboard clipboard = getClipboard(UserHandle.getUserId(uid));
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
        if (uri == null || !ActivityTaskManagerInternal.ASSIST_KEY_CONTENT.equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            this.mUgmInternal.revokeUriPermissionFromOwner(this.mPermissionOwner, ContentProvider.getUriWithoutUserId(uri), 1, ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(sourceUid)));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
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
    public boolean clipboardAccessAllowed(int op, String callingPackage, int uid, int userId) {
        AutofillManagerInternal autofillManagerInternal;
        ContentCaptureManagerInternal contentCaptureManagerInternal;
        boolean allowed = false;
        if (this.mAppOps.noteOp(op, uid, callingPackage) != 0) {
            return false;
        }
        if (this.mPm.checkPermission("android.permission.READ_CLIPBOARD_IN_BACKGROUND", callingPackage) == 0) {
            return true;
        }
        String defaultIme = Settings.Secure.getStringForUser(getContext().getContentResolver(), "default_input_method", userId);
        if (!TextUtils.isEmpty(defaultIme)) {
            String imePkg = ComponentName.unflattenFromString(defaultIme).getPackageName();
            if (imePkg.equals(callingPackage)) {
                return true;
            }
        }
        if (op != 29) {
            if (op == 30) {
                return true;
            }
            throw new IllegalArgumentException("Unknown clipboard appop " + op);
        }
        if (this.mWm.isUidFocused(uid) || isInternalSysWindowAppWithWindowFocus(callingPackage)) {
            allowed = true;
        }
        if (!allowed && (contentCaptureManagerInternal = this.mContentCaptureInternal) != null) {
            allowed = contentCaptureManagerInternal.isContentCaptureServiceForUser(uid, userId);
        }
        if (!allowed && (autofillManagerInternal = this.mAutofillInternal) != null) {
            allowed = autofillManagerInternal.isAugmentedAutofillServiceForUser(uid, userId);
        }
        if (!allowed) {
            Slog.e(TAG, "Denying clipboard access to " + callingPackage + ", application is not in focus neither is a system service for user " + userId);
        }
        return allowed;
    }
}
