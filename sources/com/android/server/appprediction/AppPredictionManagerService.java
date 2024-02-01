package com.android.server.appprediction;

import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.app.prediction.IPredictionManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.Slog;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.wm.ActivityTaskManagerInternal;
import java.io.FileDescriptor;
import java.util.function.Consumer;

/* loaded from: classes.dex */
public class AppPredictionManagerService extends AbstractMasterSystemService<AppPredictionManagerService, AppPredictionPerUserService> {
    private static final int MAX_TEMP_SERVICE_DURATION_MS = 120000;
    private static final String TAG = AppPredictionManagerService.class.getSimpleName();
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public AppPredictionManagerService(Context context) {
        super(context, new FrameworkResourcesServiceNameResolver(context, 17039698), null, 17);
        this.mActivityTaskManagerInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Can't rename method to resolve collision */
    @Override // com.android.server.infra.AbstractMasterSystemService
    public AppPredictionPerUserService newServiceLocked(int resolvedUserId, boolean disabled) {
        return new AppPredictionPerUserService(this, this.mLock, resolvedUserId);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("app_prediction", new PredictionManagerServiceStub());
    }

    @Override // com.android.server.infra.AbstractMasterSystemService
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission("android.permission.MANAGE_APP_PREDICTIONS", TAG);
    }

    @Override // com.android.server.infra.AbstractMasterSystemService
    protected void onServicePackageUpdatedLocked(int userId) {
        AppPredictionPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageUpdatedLocked();
        }
    }

    @Override // com.android.server.infra.AbstractMasterSystemService
    protected void onServicePackageRestartedLocked(int userId) {
        AppPredictionPerUserService service = peekServiceForUserLocked(userId);
        if (service != null) {
            service.onPackageRestartedLocked();
        }
    }

    @Override // com.android.server.infra.AbstractMasterSystemService
    protected int getMaximumTemporaryServiceDurationMs() {
        return MAX_TEMP_SERVICE_DURATION_MS;
    }

    /* loaded from: classes.dex */
    private class PredictionManagerServiceStub extends IPredictionManager.Stub {
        private PredictionManagerServiceStub() {
        }

        public void createPredictionSession(final AppPredictionContext context, final AppPredictionSessionId sessionId) {
            runForUserLocked("createPredictionSession", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$NmwmTMZXXS4S7viVNKzU2genXA8
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).onCreatePredictionSessionLocked(context, sessionId);
                }
            });
        }

        public void notifyAppTargetEvent(final AppPredictionSessionId sessionId, final AppTargetEvent event) {
            runForUserLocked("notifyAppTargetEvent", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$4yDhFef-19aMlJ-Y7O6RdjSAvnk
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).notifyAppTargetEventLocked(sessionId, event);
                }
            });
        }

        public void notifyLaunchLocationShown(final AppPredictionSessionId sessionId, final String launchLocation, final ParceledListSlice targetIds) {
            runForUserLocked("notifyLaunchLocationShown", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$vWB3PdxOOvPr7p0_NmoqXeH8Ros
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).notifyLaunchLocationShownLocked(sessionId, launchLocation, targetIds);
                }
            });
        }

        public void sortAppTargets(final AppPredictionSessionId sessionId, final ParceledListSlice targets, final IPredictionCallback callback) {
            runForUserLocked("sortAppTargets", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$3-HMCieo6-UZfG43p_6ip1hrL0k
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).sortAppTargetsLocked(sessionId, targets, callback);
                }
            });
        }

        public void registerPredictionUpdates(final AppPredictionSessionId sessionId, final IPredictionCallback callback) {
            runForUserLocked("registerPredictionUpdates", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$40EK4qcr-rG55ENTthOaXAXWDA4
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).registerPredictionUpdatesLocked(sessionId, callback);
                }
            });
        }

        public void unregisterPredictionUpdates(final AppPredictionSessionId sessionId, final IPredictionCallback callback) {
            runForUserLocked("unregisterPredictionUpdates", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$s2vrDOHz5x1TW_6jMihxp1iCAvg
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).unregisterPredictionUpdatesLocked(sessionId, callback);
                }
            });
        }

        public void requestPredictionUpdate(final AppPredictionSessionId sessionId) {
            runForUserLocked("requestPredictionUpdate", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$vSY20eQq5y5FXrxhhqOTcEmezTs
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).requestPredictionUpdateLocked(sessionId);
                }
            });
        }

        public void onDestroyPredictionSession(final AppPredictionSessionId sessionId) {
            runForUserLocked("onDestroyPredictionSession", new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionManagerService$PredictionManagerServiceStub$gV-NT40YbIbIqIJKiNGjlZGVJjc
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((AppPredictionPerUserService) obj).onDestroyPredictionSessionLocked(sessionId);
                }
            });
        }

        /* JADX WARN: Multi-variable type inference failed */
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new AppPredictionManagerServiceShellCommand(AppPredictionManagerService.this).exec(this, in, out, err, args, callback, resultReceiver);
        }

        private void runForUserLocked(String func, Consumer<AppPredictionPerUserService> c) {
            int userId = UserHandle.getCallingUserId();
            Context ctx = AppPredictionManagerService.this.getContext();
            if (ctx.checkCallingPermission("android.permission.PACKAGE_USAGE_STATS") != 0 && !AppPredictionManagerService.this.mServiceNameResolver.isTemporary(userId) && !AppPredictionManagerService.this.mActivityTaskManagerInternal.isCallerRecents(Binder.getCallingUid())) {
                String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " expected caller to hold PACKAGE_USAGE_STATS permission";
                Slog.w(AppPredictionManagerService.TAG, msg);
                throw new SecurityException(msg);
            }
            long origId = Binder.clearCallingIdentity();
            try {
                synchronized (AppPredictionManagerService.this.mLock) {
                    AppPredictionPerUserService service = (AppPredictionPerUserService) AppPredictionManagerService.this.getServiceForUserLocked(userId);
                    c.accept(service);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }
}
