package com.android.server;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/* loaded from: classes.dex */
public class ServiceWatcher implements ServiceConnection {
    private static final boolean D = false;
    public static final String EXTRA_SERVICE_IS_MULTIUSER = "serviceIsMultiuser";
    public static final String EXTRA_SERVICE_VERSION = "serviceVersion";
    private static final String TAG = "ServiceWatcher";
    private final String mAction;
    private volatile ComponentName mBestComponent;
    private IBinder mBestService;
    private volatile int mBestUserId;
    private volatile int mBestVersion;
    private final Context mContext;
    private int mCurrentUserId;
    private final Handler mHandler;
    private final String mServicePackageName;
    private final List<HashSet<Signature>> mSignatureSets;
    private final String mTag;

    /* loaded from: classes.dex */
    public interface BinderRunner {
        void run(IBinder iBinder) throws RemoteException;
    }

    /* loaded from: classes.dex */
    public interface BlockingBinderRunner<T> {
        T run(IBinder iBinder) throws RemoteException;
    }

    public static ArrayList<HashSet<Signature>> getSignatureSets(Context context, String... packageNames) {
        PackageManager pm = context.getPackageManager();
        ArrayList<HashSet<Signature>> signatureSets = new ArrayList<>(packageNames.length);
        for (String packageName : packageNames) {
            try {
                Signature[] signatures = pm.getPackageInfo(packageName, 1048640).signatures;
                HashSet<Signature> set = new HashSet<>();
                Collections.addAll(set, signatures);
                signatureSets.add(set);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, packageName + " not found");
            }
        }
        return signatureSets;
    }

    public static boolean isSignatureMatch(Signature[] signatures, List<HashSet<Signature>> sigSets) {
        if (signatures == null) {
            return false;
        }
        HashSet<Signature> inputSet = new HashSet<>();
        Collections.addAll(inputSet, signatures);
        for (HashSet<Signature> referenceSet : sigSets) {
            if (referenceSet.equals(inputSet)) {
                return true;
            }
        }
        return false;
    }

    public ServiceWatcher(Context context, String logTag, String action, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, Handler handler) {
        Resources resources = context.getResources();
        this.mContext = context;
        this.mTag = logTag;
        this.mAction = action;
        boolean enableOverlay = resources.getBoolean(overlaySwitchResId);
        if (enableOverlay) {
            String[] pkgs = resources.getStringArray(initialPackageNamesResId);
            this.mServicePackageName = null;
            this.mSignatureSets = getSignatureSets(context, pkgs);
        } else {
            this.mServicePackageName = resources.getString(defaultServicePackageNameResId);
            this.mSignatureSets = getSignatureSets(context, this.mServicePackageName);
        }
        this.mHandler = handler;
        this.mBestComponent = null;
        this.mBestVersion = Integer.MIN_VALUE;
        this.mBestUserId = -10000;
        this.mBestService = null;
    }

    protected void onBind() {
    }

    protected void onUnbind() {
    }

    /* JADX WARN: Type inference failed for: r0v3, types: [com.android.server.ServiceWatcher$1] */
    public final boolean start() {
        if (isServiceMissing()) {
            return false;
        }
        if (this.mServicePackageName == null) {
            new PackageMonitor() { // from class: com.android.server.ServiceWatcher.1
                public void onPackageUpdateFinished(String packageName, int uid) {
                    ServiceWatcher serviceWatcher = ServiceWatcher.this;
                    serviceWatcher.bindBestPackage(Objects.equals(packageName, serviceWatcher.getCurrentPackageName()));
                }

                public void onPackageAdded(String packageName, int uid) {
                    ServiceWatcher serviceWatcher = ServiceWatcher.this;
                    serviceWatcher.bindBestPackage(Objects.equals(packageName, serviceWatcher.getCurrentPackageName()));
                }

                public void onPackageRemoved(String packageName, int uid) {
                    ServiceWatcher serviceWatcher = ServiceWatcher.this;
                    serviceWatcher.bindBestPackage(Objects.equals(packageName, serviceWatcher.getCurrentPackageName()));
                }

                public boolean onPackageChanged(String packageName, int uid, String[] components) {
                    ServiceWatcher serviceWatcher = ServiceWatcher.this;
                    serviceWatcher.bindBestPackage(Objects.equals(packageName, serviceWatcher.getCurrentPackageName()));
                    return super.onPackageChanged(packageName, uid, components);
                }
            }.register(this.mContext, UserHandle.ALL, true, this.mHandler);
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.USER_SWITCHED");
        intentFilter.addAction("android.intent.action.USER_UNLOCKED");
        this.mContext.registerReceiverAsUser(new BroadcastReceiver() { // from class: com.android.server.ServiceWatcher.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                int userId = intent.getIntExtra("android.intent.extra.user_handle", -10000);
                if ("android.intent.action.USER_SWITCHED".equals(action)) {
                    ServiceWatcher.this.mCurrentUserId = userId;
                    ServiceWatcher.this.bindBestPackage(false);
                } else if ("android.intent.action.USER_UNLOCKED".equals(action) && userId == ServiceWatcher.this.mCurrentUserId) {
                    ServiceWatcher.this.bindBestPackage(false);
                }
            }
        }, UserHandle.ALL, intentFilter, null, this.mHandler);
        this.mCurrentUserId = ActivityManager.getCurrentUser();
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$ServiceWatcher$IP3HV4ye72eH3YxzGb9dMfcGWPE
            @Override // java.lang.Runnable
            public final void run() {
                ServiceWatcher.this.lambda$start$0$ServiceWatcher();
            }
        });
        return true;
    }

    public /* synthetic */ void lambda$start$0$ServiceWatcher() {
        bindBestPackage(false);
    }

    public String getCurrentPackageName() {
        ComponentName bestComponent = this.mBestComponent;
        if (bestComponent == null) {
            return null;
        }
        return bestComponent.getPackageName();
    }

    private boolean isServiceMissing() {
        return this.mContext.getPackageManager().queryIntentServicesAsUser(new Intent(this.mAction), 786432, 0).isEmpty();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void bindBestPackage(boolean forceRebind) {
        List<ResolveInfo> rInfos;
        Preconditions.checkState(Looper.myLooper() == this.mHandler.getLooper());
        Intent intent = new Intent(this.mAction);
        String str = this.mServicePackageName;
        if (str != null) {
            intent.setPackage(str);
        }
        List<ResolveInfo> rInfos2 = this.mContext.getPackageManager().queryIntentServicesAsUser(intent, 268435584, this.mCurrentUserId);
        if (rInfos2 != null) {
            rInfos = rInfos2;
        } else {
            rInfos = Collections.emptyList();
        }
        boolean bestIsMultiuser = false;
        int bestVersion = Integer.MIN_VALUE;
        ComponentName bestComponent = null;
        for (ResolveInfo rInfo : rInfos) {
            ComponentName component = rInfo.serviceInfo.getComponentName();
            String packageName = component.getPackageName();
            try {
                PackageInfo pInfo = this.mContext.getPackageManager().getPackageInfo(packageName, 268435520);
                if (!isSignatureMatch(pInfo.signatures, this.mSignatureSets)) {
                    Log.w(this.mTag, packageName + " resolves service " + this.mAction + ", but has wrong signature, ignoring");
                } else {
                    Bundle metadata = rInfo.serviceInfo.metaData;
                    int version = Integer.MIN_VALUE;
                    boolean isMultiuser = false;
                    if (metadata != null) {
                        version = metadata.getInt(EXTRA_SERVICE_VERSION, Integer.MIN_VALUE);
                        isMultiuser = metadata.getBoolean(EXTRA_SERVICE_IS_MULTIUSER, false);
                    }
                    if (version > bestVersion) {
                        int bestVersion2 = version;
                        bestIsMultiuser = isMultiuser;
                        bestVersion = bestVersion2;
                        bestComponent = component;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.wtf(this.mTag, e);
            }
        }
        if (bestComponent == null) {
            Slog.w(this.mTag, "Odd, no component found for service " + this.mAction);
            unbind();
            return;
        }
        int userId = bestIsMultiuser ? 0 : this.mCurrentUserId;
        boolean alreadyBound = Objects.equals(bestComponent, this.mBestComponent) && bestVersion == this.mBestVersion && userId == this.mBestUserId;
        if (forceRebind || !alreadyBound) {
            unbind();
            bind(bestComponent, bestVersion, userId);
        }
    }

    private void bind(ComponentName component, int version, int userId) {
        Preconditions.checkState(Looper.myLooper() == this.mHandler.getLooper());
        Intent intent = new Intent(this.mAction);
        intent.setComponent(component);
        this.mBestComponent = component;
        this.mBestVersion = version;
        this.mBestUserId = userId;
        this.mContext.bindServiceAsUser(intent, this, 1073741829, UserHandle.of(userId));
    }

    private void unbind() {
        Preconditions.checkState(Looper.myLooper() == this.mHandler.getLooper());
        if (this.mBestComponent != null) {
            this.mContext.unbindService(this);
        }
        this.mBestComponent = null;
        this.mBestVersion = Integer.MIN_VALUE;
        this.mBestUserId = -10000;
    }

    public final void runOnBinder(final BinderRunner runner) {
        runOnHandler(new Runnable() { // from class: com.android.server.-$$Lambda$ServiceWatcher$gVk2fFkq2-aamIua2kIpukAFtf8
            @Override // java.lang.Runnable
            public final void run() {
                ServiceWatcher.this.lambda$runOnBinder$1$ServiceWatcher(runner);
            }
        });
    }

    public /* synthetic */ void lambda$runOnBinder$1$ServiceWatcher(BinderRunner runner) {
        IBinder iBinder = this.mBestService;
        if (iBinder == null) {
            return;
        }
        try {
            runner.run(iBinder);
        } catch (RemoteException e) {
        } catch (RuntimeException e2) {
            Log.e(TAG, "exception while while running " + runner + " on " + this.mBestService + " from " + this, e2);
        }
    }

    @Deprecated
    public final <T> T runOnBinderBlocking(final BlockingBinderRunner<T> runner, final T defaultValue) {
        try {
            return (T) runOnHandlerBlocking(new Callable() { // from class: com.android.server.-$$Lambda$ServiceWatcher$b1z9OeL-1VpQ_8p47qz7nMNUpsE
                @Override // java.util.concurrent.Callable
                public final Object call() {
                    return ServiceWatcher.this.lambda$runOnBinderBlocking$2$ServiceWatcher(defaultValue, runner);
                }
            });
        } catch (InterruptedException e) {
            return defaultValue;
        }
    }

    public /* synthetic */ Object lambda$runOnBinderBlocking$2$ServiceWatcher(Object defaultValue, BlockingBinderRunner runner) throws Exception {
        IBinder iBinder = this.mBestService;
        if (iBinder == null) {
            return defaultValue;
        }
        try {
            return runner.run(iBinder);
        } catch (RemoteException e) {
            return defaultValue;
        }
    }

    @Override // android.content.ServiceConnection
    public final void onServiceConnected(final ComponentName component, final IBinder binder) {
        runOnHandler(new Runnable() { // from class: com.android.server.-$$Lambda$ServiceWatcher$uru7j1zD-GiN8rndFZ3KWaTrxYo
            @Override // java.lang.Runnable
            public final void run() {
                ServiceWatcher.this.lambda$onServiceConnected$3$ServiceWatcher(component, binder);
            }
        });
    }

    public /* synthetic */ void lambda$onServiceConnected$3$ServiceWatcher(ComponentName component, IBinder binder) {
        this.mBestService = binder;
        onBind();
    }

    @Override // android.content.ServiceConnection
    public final void onServiceDisconnected(final ComponentName component) {
        runOnHandler(new Runnable() { // from class: com.android.server.-$$Lambda$ServiceWatcher$uCZpuTwrOz-CS9PQS2NY1ZXaU8U
            @Override // java.lang.Runnable
            public final void run() {
                ServiceWatcher.this.lambda$onServiceDisconnected$4$ServiceWatcher(component);
            }
        });
    }

    public /* synthetic */ void lambda$onServiceDisconnected$4$ServiceWatcher(ComponentName component) {
        this.mBestService = null;
        onUnbind();
    }

    public String toString() {
        ComponentName bestComponent = this.mBestComponent;
        if (bestComponent == null) {
            return "null";
        }
        return bestComponent.toShortString() + "@" + this.mBestVersion;
    }

    private void runOnHandler(Runnable r) {
        if (Looper.myLooper() == this.mHandler.getLooper()) {
            r.run();
        } else {
            this.mHandler.post(r);
        }
    }

    private <T> T runOnHandlerBlocking(Callable<T> c) throws InterruptedException {
        if (Looper.myLooper() == this.mHandler.getLooper()) {
            try {
                return c.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        FutureTask<T> task = new FutureTask<>(c);
        this.mHandler.post(task);
        try {
            return task.get();
        } catch (ExecutionException e2) {
            throw new IllegalStateException(e2);
        }
    }
}
