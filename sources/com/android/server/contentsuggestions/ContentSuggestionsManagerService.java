package com.android.server.contentsuggestions;

import android.app.contentsuggestions.ClassificationsRequest;
import android.app.contentsuggestions.IClassificationsCallback;
import android.app.contentsuggestions.IContentSuggestionsManager;
import android.app.contentsuggestions.ISelectionsCallback;
import android.app.contentsuggestions.SelectionsRequest;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;
import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.wm.ActivityTaskManagerInternal;
import java.io.FileDescriptor;

/* loaded from: classes.dex */
public class ContentSuggestionsManagerService extends AbstractMasterSystemService<ContentSuggestionsManagerService, ContentSuggestionsPerUserService> {
    private static final int MAX_TEMP_SERVICE_DURATION_MS = 120000;
    private static final String TAG = ContentSuggestionsManagerService.class.getSimpleName();
    private static final boolean VERBOSE = false;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public ContentSuggestionsManagerService(Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context, 17039704), "no_content_suggestions");
        this.mActivityTaskManagerInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Can't rename method to resolve collision */
    @Override // com.android.server.infra.AbstractMasterSystemService
    public ContentSuggestionsPerUserService newServiceLocked(int resolvedUserId, boolean disabled) {
        return new ContentSuggestionsPerUserService(this, this.mLock, resolvedUserId);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("content_suggestions", new ContentSuggestionsManagerStub());
    }

    @Override // com.android.server.infra.AbstractMasterSystemService
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission("android.permission.MANAGE_CONTENT_SUGGESTIONS", TAG);
    }

    @Override // com.android.server.infra.AbstractMasterSystemService
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void enforceCaller(int userId, String func) {
        Context ctx = getContext();
        if (ctx.checkCallingPermission("android.permission.MANAGE_CONTENT_SUGGESTIONS") == 0 || this.mServiceNameResolver.isTemporary(userId) || this.mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid())) {
            return;
        }
        String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " expected caller is recents";
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    /* loaded from: classes.dex */
    private class ContentSuggestionsManagerStub extends IContentSuggestionsManager.Stub {
        private ContentSuggestionsManagerStub() {
        }

        public void provideContextImage(int userId, int taskId, Bundle imageContextRequestExtras) {
            if (imageContextRequestExtras != null) {
                ContentSuggestionsManagerService.this.enforceCaller(UserHandle.getCallingUserId(), "provideContextImage");
                synchronized (ContentSuggestionsManagerService.this.mLock) {
                    ContentSuggestionsPerUserService service = (ContentSuggestionsPerUserService) ContentSuggestionsManagerService.this.getServiceForUserLocked(userId);
                    if (service != null) {
                        service.provideContextImageLocked(taskId, imageContextRequestExtras);
                    }
                }
                return;
            }
            throw new IllegalArgumentException("Expected non-null imageContextRequestExtras");
        }

        public void suggestContentSelections(int userId, SelectionsRequest selectionsRequest, ISelectionsCallback selectionsCallback) {
            ContentSuggestionsManagerService.this.enforceCaller(UserHandle.getCallingUserId(), "suggestContentSelections");
            synchronized (ContentSuggestionsManagerService.this.mLock) {
                ContentSuggestionsPerUserService service = (ContentSuggestionsPerUserService) ContentSuggestionsManagerService.this.getServiceForUserLocked(userId);
                if (service != null) {
                    service.suggestContentSelectionsLocked(selectionsRequest, selectionsCallback);
                }
            }
        }

        public void classifyContentSelections(int userId, ClassificationsRequest classificationsRequest, IClassificationsCallback callback) {
            ContentSuggestionsManagerService.this.enforceCaller(UserHandle.getCallingUserId(), "classifyContentSelections");
            synchronized (ContentSuggestionsManagerService.this.mLock) {
                ContentSuggestionsPerUserService service = (ContentSuggestionsPerUserService) ContentSuggestionsManagerService.this.getServiceForUserLocked(userId);
                if (service != null) {
                    service.classifyContentSelectionsLocked(classificationsRequest, callback);
                }
            }
        }

        public void notifyInteraction(int userId, String requestId, Bundle bundle) {
            ContentSuggestionsManagerService.this.enforceCaller(UserHandle.getCallingUserId(), "notifyInteraction");
            synchronized (ContentSuggestionsManagerService.this.mLock) {
                ContentSuggestionsPerUserService service = (ContentSuggestionsPerUserService) ContentSuggestionsManagerService.this.getServiceForUserLocked(userId);
                if (service != null) {
                    service.notifyInteractionLocked(requestId, bundle);
                }
            }
        }

        public void isEnabled(int userId, IResultReceiver receiver) throws RemoteException {
            boolean isDisabled;
            ContentSuggestionsManagerService.this.enforceCaller(UserHandle.getCallingUserId(), "isEnabled");
            synchronized (ContentSuggestionsManagerService.this.mLock) {
                isDisabled = ContentSuggestionsManagerService.this.isDisabledLocked(userId);
            }
            receiver.send(isDisabled ? 0 : 1, (Bundle) null);
        }

        /* JADX WARN: Multi-variable type inference failed */
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) throws RemoteException {
            int callingUid = Binder.getCallingUid();
            if (callingUid != 2000 && callingUid != 0) {
                Slog.e(ContentSuggestionsManagerService.TAG, "Expected shell caller");
            } else {
                new ContentSuggestionsManagerServiceShellCommand(ContentSuggestionsManagerService.this).exec(this, in, out, err, args, callback, resultReceiver);
            }
        }
    }
}
