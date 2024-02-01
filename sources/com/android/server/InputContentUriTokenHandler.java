package com.android.server;

import android.app.ActivityManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.IInputContentUriToken;
/* loaded from: classes.dex */
final class InputContentUriTokenHandler extends IInputContentUriToken.Stub {
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private IBinder mPermissionOwnerToken = null;
    private final int mSourceUid;
    private final int mSourceUserId;
    private final String mTargetPackage;
    private final int mTargetUserId;
    private final Uri mUri;

    /* JADX INFO: Access modifiers changed from: package-private */
    public InputContentUriTokenHandler(Uri contentUri, int sourceUid, String targetPackage, int sourceUserId, int targetUserId) {
        this.mUri = contentUri;
        this.mSourceUid = sourceUid;
        this.mTargetPackage = targetPackage;
        this.mSourceUserId = sourceUserId;
        this.mTargetUserId = targetUserId;
    }

    public void take() {
        synchronized (this.mLock) {
            if (this.mPermissionOwnerToken != null) {
                return;
            }
            try {
                this.mPermissionOwnerToken = ActivityManager.getService().newUriPermissionOwner("InputContentUriTokenHandler");
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            doTakeLocked(this.mPermissionOwnerToken);
        }
    }

    private void doTakeLocked(IBinder permissionOwner) {
        long origId = Binder.clearCallingIdentity();
        try {
            try {
                ActivityManager.getService().grantUriPermissionFromOwner(permissionOwner, this.mSourceUid, this.mTargetPackage, this.mUri, 1, this.mSourceUserId, this.mTargetUserId);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void release() {
        synchronized (this.mLock) {
            if (this.mPermissionOwnerToken == null) {
                return;
            }
            try {
                ActivityManager.getService().revokeUriPermissionFromOwner(this.mPermissionOwnerToken, this.mUri, 1, this.mSourceUserId);
                this.mPermissionOwnerToken = null;
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
                this.mPermissionOwnerToken = null;
            }
        }
    }

    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super/*java.lang.Object*/.finalize();
        }
    }
}
