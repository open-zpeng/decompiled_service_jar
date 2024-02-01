package com.google.android.startop.iorap;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityMetricsLaunchObserverRegistry;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.google.android.startop.iorap.AppLaunchEvent;
import com.google.android.startop.iorap.IIorap;
import com.google.android.startop.iorap.ITaskListener;
import com.google.android.startop.iorap.IorapForwardingService;

/* loaded from: classes2.dex */
public class IorapForwardingService extends SystemService {
    private final AppLaunchObserver mAppLaunchObserver;
    private final Handler mHandler;
    private IIorap mIorapRemote;
    private final Object mLock;
    private boolean mRegisteredListeners;
    public static final String TAG = "IorapForwardingService";
    public static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static boolean IS_ENABLED = SystemProperties.getBoolean("ro.iorapd.enable", true);
    private static boolean WTF_CRASH = SystemProperties.getBoolean("iorapd.forwarding_service.wtf_crash", false);

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public interface RemoteRunnable {
        void run() throws RemoteException;
    }

    public IorapForwardingService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mHandler = new BinderConnectionHandler(IoThread.getHandler().getLooper());
        this.mAppLaunchObserver = new AppLaunchObserver();
        this.mRegisteredListeners = false;
    }

    @VisibleForTesting
    protected ActivityMetricsLaunchObserverRegistry provideLaunchObserverRegistry() {
        ActivityTaskManagerInternal amtInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry = amtInternal.getLaunchObserverRegistry();
        return launchObserverRegistry;
    }

    @VisibleForTesting
    protected IIorap provideIorapRemote() {
        try {
            IIorap iorap = IIorap.Stub.asInterface(ServiceManager.getServiceOrThrow("iorapd"));
            try {
                iorap.asBinder().linkToDeath(provideDeathRecipient(), 0);
                return iorap;
            } catch (RemoteException e) {
                handleRemoteError(e);
                return null;
            }
        } catch (ServiceManager.ServiceNotFoundException e2) {
            handleRemoteError(e2);
            return null;
        }
    }

    @VisibleForTesting
    protected IBinder.DeathRecipient provideDeathRecipient() {
        return new IBinder.DeathRecipient() { // from class: com.google.android.startop.iorap.IorapForwardingService.1
            @Override // android.os.IBinder.DeathRecipient
            public void binderDied() {
                Log.w(IorapForwardingService.TAG, "iorapd has died");
                IorapForwardingService.this.retryConnectToRemoteAndConfigure(0);
            }
        };
    }

    @VisibleForTesting
    protected boolean isIorapEnabled() {
        return IS_ENABLED;
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        if (DEBUG) {
            Log.v(TAG, "onStart");
        }
        retryConnectToRemoteAndConfigure(0);
    }

    /* loaded from: classes2.dex */
    private class BinderConnectionHandler extends Handler {
        public static final int MESSAGE_BINDER_CONNECT = 0;
        private int mAttempts;

        public BinderConnectionHandler(Looper looper) {
            super(looper);
            this.mAttempts = 0;
        }

        @Override // android.os.Handler
        public void handleMessage(Message message) {
            if (message.what == 0) {
                if (!IorapForwardingService.this.retryConnectToRemoteAndConfigure(this.mAttempts)) {
                    this.mAttempts++;
                    return;
                } else {
                    this.mAttempts = 0;
                    return;
                }
            }
            throw new AssertionError("Unknown message: " + message.toString());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean retryConnectToRemoteAndConfigure(int attempts) {
        if (DEBUG) {
            Log.v(TAG, "retryConnectToRemoteAndConfigure - attempt #" + attempts);
        }
        if (connectToRemoteAndConfigure()) {
            return true;
        }
        Log.w(TAG, "Failed to connect to iorapd, is it down? Delay for 1000");
        Handler handler = this.mHandler;
        handler.sendMessageDelayed(handler.obtainMessage(0), 1000L);
        return false;
    }

    private boolean connectToRemoteAndConfigure() {
        boolean connectToRemoteAndConfigureLocked;
        synchronized (this.mLock) {
            connectToRemoteAndConfigureLocked = connectToRemoteAndConfigureLocked();
        }
        return connectToRemoteAndConfigureLocked;
    }

    private boolean connectToRemoteAndConfigureLocked() {
        if (!isIorapEnabled()) {
            if (DEBUG) {
                Log.v(TAG, "connectToRemoteAndConfigure - iorapd is disabled, skip rest of work");
            }
            return true;
        }
        this.mIorapRemote = provideIorapRemote();
        if (this.mIorapRemote == null) {
            Log.e(TAG, "connectToRemoteAndConfigure - null iorap remote. check for Log.wtf?");
            return false;
        }
        invokeRemote(new RemoteRunnable() { // from class: com.google.android.startop.iorap.-$$Lambda$IorapForwardingService$wgbD3UX3tm4cAHA8-DcwmeBBlXU
            @Override // com.google.android.startop.iorap.IorapForwardingService.RemoteRunnable
            public final void run() {
                IorapForwardingService.this.lambda$connectToRemoteAndConfigureLocked$0$IorapForwardingService();
            }
        });
        registerInProcessListenersLocked();
        return true;
    }

    public /* synthetic */ void lambda$connectToRemoteAndConfigureLocked$0$IorapForwardingService() throws RemoteException {
        this.mIorapRemote.setTaskListener(new RemoteTaskListener());
    }

    private void registerInProcessListenersLocked() {
        if (this.mRegisteredListeners) {
            return;
        }
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry = provideLaunchObserverRegistry();
        launchObserverRegistry.registerLaunchObserver(this.mAppLaunchObserver);
        this.mRegisteredListeners = true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class AppLaunchObserver implements ActivityMetricsLaunchObserver {
        private long mSequenceId;

        private AppLaunchObserver() {
            this.mSequenceId = -1L;
        }

        @Override // com.android.server.wm.ActivityMetricsLaunchObserver
        public void onIntentStarted(final Intent intent) {
            this.mSequenceId++;
            if (IorapForwardingService.DEBUG) {
                Log.v(IorapForwardingService.TAG, String.format("AppLaunchObserver#onIntentStarted(%d, %s)", Long.valueOf(this.mSequenceId), intent));
            }
            IorapForwardingService.invokeRemote(new RemoteRunnable() { // from class: com.google.android.startop.iorap.-$$Lambda$IorapForwardingService$AppLaunchObserver$u_UKVYknAlZkgsiOa6tZ-3aJvmY
                @Override // com.google.android.startop.iorap.IorapForwardingService.RemoteRunnable
                public final void run() {
                    IorapForwardingService.AppLaunchObserver.this.lambda$onIntentStarted$0$IorapForwardingService$AppLaunchObserver(intent);
                }
            });
        }

        public /* synthetic */ void lambda$onIntentStarted$0$IorapForwardingService$AppLaunchObserver(Intent intent) throws RemoteException {
            IorapForwardingService.this.mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(), new AppLaunchEvent.IntentStarted(this.mSequenceId, intent));
        }

        @Override // com.android.server.wm.ActivityMetricsLaunchObserver
        public void onIntentFailed() {
            if (IorapForwardingService.DEBUG) {
                Log.v(IorapForwardingService.TAG, String.format("AppLaunchObserver#onIntentFailed(%d)", Long.valueOf(this.mSequenceId)));
            }
            IorapForwardingService.invokeRemote(new RemoteRunnable() { // from class: com.google.android.startop.iorap.-$$Lambda$IorapForwardingService$AppLaunchObserver$oJmTSQGqnxFQdlesx2pP7kUlasM
                @Override // com.google.android.startop.iorap.IorapForwardingService.RemoteRunnable
                public final void run() {
                    IorapForwardingService.AppLaunchObserver.this.lambda$onIntentFailed$1$IorapForwardingService$AppLaunchObserver();
                }
            });
        }

        public /* synthetic */ void lambda$onIntentFailed$1$IorapForwardingService$AppLaunchObserver() throws RemoteException {
            IorapForwardingService.this.mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(), new AppLaunchEvent.IntentFailed(this.mSequenceId));
        }

        @Override // com.android.server.wm.ActivityMetricsLaunchObserver
        public void onActivityLaunched(final byte[] activity, final int temperature) {
            if (IorapForwardingService.DEBUG) {
                Log.v(IorapForwardingService.TAG, String.format("AppLaunchObserver#onActivityLaunched(%d, %s, %d)", Long.valueOf(this.mSequenceId), activity, Integer.valueOf(temperature)));
            }
            IorapForwardingService.invokeRemote(new RemoteRunnable() { // from class: com.google.android.startop.iorap.-$$Lambda$IorapForwardingService$AppLaunchObserver$OnievfJDqzAD_wVaScGowAqIDbo
                @Override // com.google.android.startop.iorap.IorapForwardingService.RemoteRunnable
                public final void run() {
                    IorapForwardingService.AppLaunchObserver.this.lambda$onActivityLaunched$2$IorapForwardingService$AppLaunchObserver(activity, temperature);
                }
            });
        }

        public /* synthetic */ void lambda$onActivityLaunched$2$IorapForwardingService$AppLaunchObserver(byte[] activity, int temperature) throws RemoteException {
            IorapForwardingService.this.mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(), new AppLaunchEvent.ActivityLaunched(this.mSequenceId, activity, temperature));
        }

        @Override // com.android.server.wm.ActivityMetricsLaunchObserver
        public void onActivityLaunchCancelled(final byte[] activity) {
            if (IorapForwardingService.DEBUG) {
                Log.v(IorapForwardingService.TAG, String.format("AppLaunchObserver#onActivityLaunchCancelled(%d, %s)", Long.valueOf(this.mSequenceId), activity));
            }
            IorapForwardingService.invokeRemote(new RemoteRunnable() { // from class: com.google.android.startop.iorap.-$$Lambda$IorapForwardingService$AppLaunchObserver$RQgRTe7AkW8YeCxIA_z8NLuEhNE
                @Override // com.google.android.startop.iorap.IorapForwardingService.RemoteRunnable
                public final void run() {
                    IorapForwardingService.AppLaunchObserver.this.lambda$onActivityLaunchCancelled$3$IorapForwardingService$AppLaunchObserver(activity);
                }
            });
        }

        public /* synthetic */ void lambda$onActivityLaunchCancelled$3$IorapForwardingService$AppLaunchObserver(byte[] activity) throws RemoteException {
            IorapForwardingService.this.mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(), new AppLaunchEvent.ActivityLaunchCancelled(this.mSequenceId, activity));
        }

        @Override // com.android.server.wm.ActivityMetricsLaunchObserver
        public void onActivityLaunchFinished(final byte[] activity) {
            if (IorapForwardingService.DEBUG) {
                Log.v(IorapForwardingService.TAG, String.format("AppLaunchObserver#onActivityLaunchFinished(%d, %s)", Long.valueOf(this.mSequenceId), activity));
            }
            IorapForwardingService.invokeRemote(new RemoteRunnable() { // from class: com.google.android.startop.iorap.-$$Lambda$IorapForwardingService$AppLaunchObserver$PpBaZ7ewYnNhOxRNE7FOesPnTOE
                @Override // com.google.android.startop.iorap.IorapForwardingService.RemoteRunnable
                public final void run() {
                    IorapForwardingService.AppLaunchObserver.this.lambda$onActivityLaunchFinished$4$IorapForwardingService$AppLaunchObserver(activity);
                }
            });
        }

        public /* synthetic */ void lambda$onActivityLaunchFinished$4$IorapForwardingService$AppLaunchObserver(byte[] activity) throws RemoteException {
            IorapForwardingService.this.mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(), new AppLaunchEvent.ActivityLaunchFinished(this.mSequenceId, activity));
        }
    }

    /* loaded from: classes2.dex */
    private class RemoteTaskListener extends ITaskListener.Stub {
        private RemoteTaskListener() {
        }

        @Override // com.google.android.startop.iorap.ITaskListener
        public void onProgress(RequestId requestId, TaskResult result) throws RemoteException {
            if (IorapForwardingService.DEBUG) {
                Log.v(IorapForwardingService.TAG, String.format("RemoteTaskListener#onProgress(%s, %s)", requestId, result));
            }
        }

        @Override // com.google.android.startop.iorap.ITaskListener
        public void onComplete(RequestId requestId, TaskResult result) throws RemoteException {
            if (IorapForwardingService.DEBUG) {
                Log.v(IorapForwardingService.TAG, String.format("RemoteTaskListener#onComplete(%s, %s)", requestId, result));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void invokeRemote(RemoteRunnable r) {
        try {
            r.run();
        } catch (RemoteException e) {
            handleRemoteError(e);
        }
    }

    private static void handleRemoteError(Throwable t) {
        if (WTF_CRASH) {
            throw new AssertionError("unexpected remote error", t);
        }
        Log.wtf(TAG, t);
    }
}
