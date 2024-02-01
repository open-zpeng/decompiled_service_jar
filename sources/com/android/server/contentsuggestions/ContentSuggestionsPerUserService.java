package com.android.server.contentsuggestions;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.contentsuggestions.ClassificationsRequest;
import android.app.contentsuggestions.IClassificationsCallback;
import android.app.contentsuggestions.ISelectionsCallback;
import android.app.contentsuggestions.SelectionsRequest;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.contentsuggestions.RemoteContentSuggestionsService;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

/* loaded from: classes.dex */
public final class ContentSuggestionsPerUserService extends AbstractPerUserSystemService<ContentSuggestionsPerUserService, ContentSuggestionsManagerService> {
    private static final String TAG = ContentSuggestionsPerUserService.class.getSimpleName();
    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
    @GuardedBy({"mLock"})
    private RemoteContentSuggestionsService mRemoteService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContentSuggestionsPerUserService(ContentSuggestionsManagerService master, Object lock, int userId) {
        super(master, lock, userId);
        this.mActivityTaskManagerInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override // com.android.server.infra.AbstractPerUserSystemService
    @GuardedBy({"mLock"})
    protected ServiceInfo newServiceInfoLocked(ComponentName serviceComponent) throws PackageManager.NameNotFoundException {
        try {
            ServiceInfo si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, 128, this.mUserId);
            if (!"android.permission.BIND_CONTENT_SUGGESTIONS_SERVICE".equals(si.permission)) {
                String str = TAG;
                Slog.w(str, "ContentSuggestionsService from '" + si.packageName + "' does not require permission android.permission.BIND_CONTENT_SUGGESTIONS_SERVICE");
                throw new SecurityException("Service does not require permission android.permission.BIND_CONTENT_SUGGESTIONS_SERVICE");
            }
            return si;
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException("Could not get service for " + serviceComponent);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.infra.AbstractPerUserSystemService
    @GuardedBy({"mLock"})
    public boolean updateLocked(boolean disabled) {
        boolean enabledChanged = super.updateLocked(disabled);
        updateRemoteServiceLocked();
        return enabledChanged;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void provideContextImageLocked(int taskId, Bundle imageContextRequestExtras) {
        ActivityManager.TaskSnapshot snapshot;
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            GraphicBuffer snapshotBuffer = null;
            int colorSpaceId = 0;
            if (!imageContextRequestExtras.containsKey("android.contentsuggestions.extra.BITMAP") && (snapshot = this.mActivityTaskManagerInternal.getTaskSnapshotNoRestore(taskId, false)) != null) {
                snapshotBuffer = snapshot.getSnapshot();
                ColorSpace colorSpace = snapshot.getColorSpace();
                if (colorSpace != null) {
                    colorSpaceId = colorSpace.getId();
                }
            }
            service.provideContextImage(taskId, snapshotBuffer, colorSpaceId, imageContextRequestExtras);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void suggestContentSelectionsLocked(SelectionsRequest selectionsRequest, ISelectionsCallback selectionsCallback) {
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            service.suggestContentSelections(selectionsRequest, selectionsCallback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void classifyContentSelectionsLocked(ClassificationsRequest classificationsRequest, IClassificationsCallback callback) {
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            service.classifyContentSelections(classificationsRequest, callback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy({"mLock"})
    public void notifyInteractionLocked(String requestId, Bundle bundle) {
        RemoteContentSuggestionsService service = ensureRemoteServiceLocked();
        if (service != null) {
            service.notifyInteraction(requestId, bundle);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy({"mLock"})
    public void updateRemoteServiceLocked() {
        RemoteContentSuggestionsService remoteContentSuggestionsService = this.mRemoteService;
        if (remoteContentSuggestionsService != null) {
            remoteContentSuggestionsService.destroy();
            this.mRemoteService = null;
        }
    }

    @GuardedBy({"mLock"})
    private RemoteContentSuggestionsService ensureRemoteServiceLocked() {
        if (this.mRemoteService == null) {
            String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (((ContentSuggestionsManagerService) this.mMaster).verbose) {
                    Slog.v(TAG, "ensureRemoteServiceLocked(): not set");
                    return null;
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            this.mRemoteService = new RemoteContentSuggestionsService(getContext(), serviceComponent, this.mUserId, new RemoteContentSuggestionsService.Callbacks() { // from class: com.android.server.contentsuggestions.ContentSuggestionsPerUserService.1
                public void onServiceDied(RemoteContentSuggestionsService service) {
                    Slog.w(ContentSuggestionsPerUserService.TAG, "remote content suggestions service died");
                    ContentSuggestionsPerUserService.this.updateRemoteServiceLocked();
                }
            }, ((ContentSuggestionsManagerService) this.mMaster).isBindInstantServiceAllowed(), ((ContentSuggestionsManagerService) this.mMaster).verbose);
        }
        return this.mRemoteService;
    }
}
