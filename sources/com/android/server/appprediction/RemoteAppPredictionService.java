package com.android.server.appprediction;

import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.IBinder;
import android.os.IInterface;
import android.service.appprediction.IPredictionService;
import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;
import com.android.internal.infra.AbstractRemoteService;
import com.android.server.pm.DumpState;

/* loaded from: classes.dex */
public class RemoteAppPredictionService extends AbstractMultiplePendingRequestsRemoteService<RemoteAppPredictionService, IPredictionService> {
    private static final String TAG = "RemoteAppPredictionService";
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2000;
    private final RemoteAppPredictionServiceCallbacks mCallback;

    /* loaded from: classes.dex */
    public interface RemoteAppPredictionServiceCallbacks extends AbstractRemoteService.VultureCallback<RemoteAppPredictionService> {
        void onConnectedStateChanged(boolean z);

        void onFailureOrTimeout(boolean z);
    }

    public RemoteAppPredictionService(Context context, String serviceInterface, ComponentName componentName, int userId, RemoteAppPredictionServiceCallbacks callback, boolean bindInstantServiceAllowed, boolean verbose) {
        super(context, serviceInterface, componentName, userId, callback, context.getMainThreadHandler(), bindInstantServiceAllowed ? DumpState.DUMP_CHANGES : 0, verbose, 1);
        this.mCallback = callback;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public IPredictionService getServiceInterface(IBinder service) {
        return IPredictionService.Stub.asInterface(service);
    }

    protected long getTimeoutIdleBindMillis() {
        return 0L;
    }

    protected long getRemoteRequestMillis() {
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    public void onCreatePredictionSession(final AppPredictionContext context, final AppPredictionSessionId sessionId) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$Ikwq62LQ8mos7hCBmykUhqvUq2Y
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).onCreatePredictionSession(context, sessionId);
            }
        });
    }

    public void notifyAppTargetEvent(final AppPredictionSessionId sessionId, final AppTargetEvent event) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$qroIh2ewx0BLP-J9XIAX2CaX8J4
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).notifyAppTargetEvent(sessionId, event);
            }
        });
    }

    public void notifyLaunchLocationShown(final AppPredictionSessionId sessionId, final String launchLocation, final ParceledListSlice targetIds) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$2EyTj40DnIRaUJU1GBU3r9jPAJg
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).notifyLaunchLocationShown(sessionId, launchLocation, targetIds);
            }
        });
    }

    public void sortAppTargets(final AppPredictionSessionId sessionId, final ParceledListSlice targets, final IPredictionCallback callback) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$V2_zSuJJPrke_XrPl6iB-Ekw1Z4
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).sortAppTargets(sessionId, targets, callback);
            }
        });
    }

    public void registerPredictionUpdates(final AppPredictionSessionId sessionId, final IPredictionCallback callback) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$UaZoW5Y9AD8L3ktnyw-25jtnxhA
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).registerPredictionUpdates(sessionId, callback);
            }
        });
    }

    public void unregisterPredictionUpdates(final AppPredictionSessionId sessionId, final IPredictionCallback callback) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$sQgYVaCXRIosCYaNa7w5ZuNn7u8
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).unregisterPredictionUpdates(sessionId, callback);
            }
        });
    }

    public void requestPredictionUpdate(final AppPredictionSessionId sessionId) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$9DCowUTEF8fYuBlWGxOmP5hTAWA
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).requestPredictionUpdate(sessionId);
            }
        });
    }

    public void onDestroyPredictionSession(final AppPredictionSessionId sessionId) {
        scheduleAsyncRequest(new AbstractRemoteService.AsyncRequest() { // from class: com.android.server.appprediction.-$$Lambda$RemoteAppPredictionService$dsYLGE9YRnrxNNkC1jG8ymCUr5Q
            public final void run(IInterface iInterface) {
                ((IPredictionService) iInterface).onDestroyPredictionSession(sessionId);
            }
        });
    }

    public void reconnect() {
        super.scheduleBind();
    }

    protected void handleOnConnectedStateChanged(boolean connected) {
        RemoteAppPredictionServiceCallbacks remoteAppPredictionServiceCallbacks = this.mCallback;
        if (remoteAppPredictionServiceCallbacks != null) {
            remoteAppPredictionServiceCallbacks.onConnectedStateChanged(connected);
        }
    }
}
