package com.android.server.appprediction;

import android.app.AppGlobals;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.server.appprediction.AppPredictionPerUserService;
import com.android.server.appprediction.RemoteAppPredictionService;
import com.android.server.infra.AbstractPerUserSystemService;
import java.util.function.Consumer;

/* loaded from: classes.dex */
public class AppPredictionPerUserService extends AbstractPerUserSystemService<AppPredictionPerUserService, AppPredictionManagerService> implements RemoteAppPredictionService.RemoteAppPredictionServiceCallbacks {
    private static final String TAG = AppPredictionPerUserService.class.getSimpleName();
    @GuardedBy({"mLock"})
    private RemoteAppPredictionService mRemoteService;
    @GuardedBy({"mLock"})
    private final ArrayMap<AppPredictionSessionId, AppPredictionSessionInfo> mSessionInfos;
    @GuardedBy({"mLock"})
    private boolean mZombie;

    /* JADX INFO: Access modifiers changed from: protected */
    public AppPredictionPerUserService(AppPredictionManagerService master, Object lock, int userId) {
        super(master, lock, userId);
        this.mSessionInfos = new ArrayMap<>();
    }

    @Override // com.android.server.infra.AbstractPerUserSystemService
    protected ServiceInfo newServiceInfoLocked(ComponentName serviceComponent) throws PackageManager.NameNotFoundException {
        try {
            ServiceInfo si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, 128, this.mUserId);
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
        if (enabledChanged && !isEnabledLocked()) {
            this.mRemoteService = null;
        }
        return enabledChanged;
    }

    @GuardedBy({"mLock"})
    public void onCreatePredictionSessionLocked(AppPredictionContext context, AppPredictionSessionId sessionId) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.onCreatePredictionSession(context, sessionId);
            if (!this.mSessionInfos.containsKey(sessionId)) {
                this.mSessionInfos.put(sessionId, new AppPredictionSessionInfo(sessionId, context, new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionPerUserService$ot809pjFOVEJ6shAJalMZ9_QhCo
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        AppPredictionPerUserService.this.removeAppPredictionSessionInfo((AppPredictionSessionId) obj);
                    }
                }));
            }
        }
    }

    @GuardedBy({"mLock"})
    public void notifyAppTargetEventLocked(AppPredictionSessionId sessionId, AppTargetEvent event) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.notifyAppTargetEvent(sessionId, event);
        }
    }

    @GuardedBy({"mLock"})
    public void notifyLaunchLocationShownLocked(AppPredictionSessionId sessionId, String launchLocation, ParceledListSlice targetIds) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.notifyLaunchLocationShown(sessionId, launchLocation, targetIds);
        }
    }

    @GuardedBy({"mLock"})
    public void sortAppTargetsLocked(AppPredictionSessionId sessionId, ParceledListSlice targets, IPredictionCallback callback) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.sortAppTargets(sessionId, targets, callback);
        }
    }

    @GuardedBy({"mLock"})
    public void registerPredictionUpdatesLocked(AppPredictionSessionId sessionId, IPredictionCallback callback) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.registerPredictionUpdates(sessionId, callback);
            AppPredictionSessionInfo sessionInfo = this.mSessionInfos.get(sessionId);
            if (sessionInfo != null) {
                sessionInfo.addCallbackLocked(callback);
            }
        }
    }

    @GuardedBy({"mLock"})
    public void unregisterPredictionUpdatesLocked(AppPredictionSessionId sessionId, IPredictionCallback callback) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.unregisterPredictionUpdates(sessionId, callback);
            AppPredictionSessionInfo sessionInfo = this.mSessionInfos.get(sessionId);
            if (sessionInfo != null) {
                sessionInfo.removeCallbackLocked(callback);
            }
        }
    }

    @GuardedBy({"mLock"})
    public void requestPredictionUpdateLocked(AppPredictionSessionId sessionId) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.requestPredictionUpdate(sessionId);
        }
    }

    @GuardedBy({"mLock"})
    public void onDestroyPredictionSessionLocked(AppPredictionSessionId sessionId) {
        RemoteAppPredictionService service = getRemoteServiceLocked();
        if (service != null) {
            service.onDestroyPredictionSession(sessionId);
            AppPredictionSessionInfo sessionInfo = this.mSessionInfos.get(sessionId);
            if (sessionInfo != null) {
                sessionInfo.destroy();
            }
        }
    }

    @Override // com.android.server.appprediction.RemoteAppPredictionService.RemoteAppPredictionServiceCallbacks
    public void onFailureOrTimeout(boolean timedOut) {
        if (isDebug()) {
            String str = TAG;
            Slog.d(str, "onFailureOrTimeout(): timed out=" + timedOut);
        }
    }

    @Override // com.android.server.appprediction.RemoteAppPredictionService.RemoteAppPredictionServiceCallbacks
    public void onConnectedStateChanged(boolean connected) {
        if (isDebug()) {
            String str = TAG;
            Slog.d(str, "onConnectedStateChanged(): connected=" + connected);
        }
        if (connected) {
            synchronized (this.mLock) {
                if (this.mZombie) {
                    if (this.mRemoteService == null) {
                        Slog.w(TAG, "Cannot resurrect sessions because remote service is null");
                    } else {
                        this.mZombie = false;
                        resurrectSessionsLocked();
                    }
                }
            }
        }
    }

    public void onServiceDied(RemoteAppPredictionService service) {
        if (isDebug()) {
            String str = TAG;
            Slog.w(str, "onServiceDied(): service=" + service);
        }
        synchronized (this.mLock) {
            this.mZombie = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPackageUpdatedLocked() {
        if (isDebug()) {
            Slog.v(TAG, "onPackageUpdatedLocked()");
        }
        destroyAndRebindRemoteService();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPackageRestartedLocked() {
        if (isDebug()) {
            Slog.v(TAG, "onPackageRestartedLocked()");
        }
        destroyAndRebindRemoteService();
    }

    private void destroyAndRebindRemoteService() {
        if (this.mRemoteService == null) {
            return;
        }
        if (isDebug()) {
            Slog.d(TAG, "Destroying the old remote service.");
        }
        this.mRemoteService.destroy();
        this.mRemoteService = null;
        this.mRemoteService = getRemoteServiceLocked();
        if (this.mRemoteService != null) {
            if (isDebug()) {
                Slog.d(TAG, "Rebinding to the new remote service.");
            }
            this.mRemoteService.reconnect();
        }
    }

    private void resurrectSessionsLocked() {
        int numSessions = this.mSessionInfos.size();
        if (isDebug()) {
            String str = TAG;
            Slog.d(str, "Resurrecting remote service (" + this.mRemoteService + ") on " + numSessions + " sessions.");
        }
        for (AppPredictionSessionInfo sessionInfo : this.mSessionInfos.values()) {
            sessionInfo.resurrectSessionLocked(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeAppPredictionSessionInfo(AppPredictionSessionId sessionId) {
        if (isDebug()) {
            String str = TAG;
            Slog.d(str, "removeAppPredictionSessionInfo(): sessionId=" + sessionId);
        }
        synchronized (this.mLock) {
            this.mSessionInfos.remove(sessionId);
        }
    }

    @GuardedBy({"mLock"})
    private RemoteAppPredictionService getRemoteServiceLocked() {
        if (this.mRemoteService == null) {
            String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (((AppPredictionManagerService) this.mMaster).verbose) {
                    Slog.v(TAG, "getRemoteServiceLocked(): not set");
                    return null;
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            this.mRemoteService = new RemoteAppPredictionService(getContext(), "android.service.appprediction.AppPredictionService", serviceComponent, this.mUserId, this, ((AppPredictionManagerService) this.mMaster).isBindInstantServiceAllowed(), ((AppPredictionManagerService) this.mMaster).verbose);
        }
        return this.mRemoteService;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class AppPredictionSessionInfo {
        private static final boolean DEBUG = false;
        private final RemoteCallbackList<IPredictionCallback> mCallbacks = new RemoteCallbackList<IPredictionCallback>() { // from class: com.android.server.appprediction.AppPredictionPerUserService.AppPredictionSessionInfo.1
            @Override // android.os.RemoteCallbackList
            public void onCallbackDied(IPredictionCallback callback) {
                if (AppPredictionSessionInfo.this.mCallbacks.getRegisteredCallbackCount() == 0) {
                    AppPredictionSessionInfo.this.destroy();
                }
            }
        };
        private final AppPredictionContext mPredictionContext;
        private final Consumer<AppPredictionSessionId> mRemoveSessionInfoAction;
        private final AppPredictionSessionId mSessionId;

        AppPredictionSessionInfo(AppPredictionSessionId id, AppPredictionContext predictionContext, Consumer<AppPredictionSessionId> removeSessionInfoAction) {
            this.mSessionId = id;
            this.mPredictionContext = predictionContext;
            this.mRemoveSessionInfoAction = removeSessionInfoAction;
        }

        void addCallbackLocked(IPredictionCallback callback) {
            this.mCallbacks.register(callback);
        }

        void removeCallbackLocked(IPredictionCallback callback) {
            this.mCallbacks.unregister(callback);
        }

        void destroy() {
            this.mCallbacks.kill();
            this.mRemoveSessionInfoAction.accept(this.mSessionId);
        }

        void resurrectSessionLocked(final AppPredictionPerUserService service) {
            this.mCallbacks.getRegisteredCallbackCount();
            service.onCreatePredictionSessionLocked(this.mPredictionContext, this.mSessionId);
            this.mCallbacks.broadcast(new Consumer() { // from class: com.android.server.appprediction.-$$Lambda$AppPredictionPerUserService$AppPredictionSessionInfo$LQ7iu1YPVHeNHnTTNfaw5e_68Z4
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    AppPredictionPerUserService.AppPredictionSessionInfo.this.lambda$resurrectSessionLocked$0$AppPredictionPerUserService$AppPredictionSessionInfo(service, (IPredictionCallback) obj);
                }
            });
        }

        public /* synthetic */ void lambda$resurrectSessionLocked$0$AppPredictionPerUserService$AppPredictionSessionInfo(AppPredictionPerUserService service, IPredictionCallback callback) {
            service.registerPredictionUpdatesLocked(this.mSessionId, callback);
        }
    }
}
