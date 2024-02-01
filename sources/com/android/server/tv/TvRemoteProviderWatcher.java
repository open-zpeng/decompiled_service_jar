package com.android.server.tv;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import com.android.server.slice.SliceClientPermissions;
import java.util.ArrayList;
import java.util.Collections;

/* loaded from: classes2.dex */
final class TvRemoteProviderWatcher {
    private final Context mContext;
    private final Handler mHandler;
    private final PackageManager mPackageManager;
    private final ProviderMethods mProvider;
    private boolean mRunning;
    private final String mUnbundledServicePackage;
    private static final String TAG = "TvRemoteProvWatcher";
    private static final boolean DEBUG = Log.isLoggable(TAG, 2);
    private final ArrayList<TvRemoteProviderProxy> mProviderProxies = new ArrayList<>();
    private final BroadcastReceiver mScanPackagesReceiver = new BroadcastReceiver() { // from class: com.android.server.tv.TvRemoteProviderWatcher.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (TvRemoteProviderWatcher.DEBUG) {
                Slog.d(TvRemoteProviderWatcher.TAG, "Received package manager broadcast: " + intent);
            }
            TvRemoteProviderWatcher.this.mHandler.post(TvRemoteProviderWatcher.this.mScanPackagesRunnable);
        }
    };
    private final Runnable mScanPackagesRunnable = new Runnable() { // from class: com.android.server.tv.TvRemoteProviderWatcher.2
        @Override // java.lang.Runnable
        public void run() {
            TvRemoteProviderWatcher.this.scanPackages();
        }
    };
    private final int mUserId = UserHandle.myUserId();

    /* loaded from: classes2.dex */
    public interface ProviderMethods {
        void addProvider(TvRemoteProviderProxy tvRemoteProviderProxy);

        void removeProvider(TvRemoteProviderProxy tvRemoteProviderProxy);
    }

    public TvRemoteProviderWatcher(Context context, ProviderMethods provider, Handler handler) {
        this.mContext = context;
        this.mProvider = provider;
        this.mHandler = handler;
        this.mPackageManager = context.getPackageManager();
        this.mUnbundledServicePackage = context.getString(17039782);
    }

    public void start() {
        if (DEBUG) {
            Slog.d(TAG, "start()");
        }
        if (!this.mRunning) {
            this.mRunning = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.PACKAGE_ADDED");
            filter.addAction("android.intent.action.PACKAGE_REMOVED");
            filter.addAction("android.intent.action.PACKAGE_CHANGED");
            filter.addAction("android.intent.action.PACKAGE_REPLACED");
            filter.addAction("android.intent.action.PACKAGE_RESTARTED");
            filter.addDataScheme("package");
            this.mContext.registerReceiverAsUser(this.mScanPackagesReceiver, new UserHandle(this.mUserId), filter, null, this.mHandler);
            this.mHandler.post(this.mScanPackagesRunnable);
        }
    }

    public void stop() {
        if (this.mRunning) {
            this.mRunning = false;
            this.mContext.unregisterReceiver(this.mScanPackagesReceiver);
            this.mHandler.removeCallbacks(this.mScanPackagesRunnable);
            for (int i = this.mProviderProxies.size() - 1; i >= 0; i--) {
                this.mProviderProxies.get(i).stop();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scanPackages() {
        if (!this.mRunning) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "scanPackages()");
        }
        int targetIndex = 0;
        Intent intent = new Intent("com.android.media.tv.remoteprovider.TvRemoteProvider");
        for (ResolveInfo resolveInfo : this.mPackageManager.queryIntentServicesAsUser(intent, 0, this.mUserId)) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo != null && verifyServiceTrusted(serviceInfo)) {
                int sourceIndex = findProvider(serviceInfo.packageName, serviceInfo.name);
                if (sourceIndex < 0) {
                    TvRemoteProviderProxy providerProxy = new TvRemoteProviderProxy(this.mContext, new ComponentName(serviceInfo.packageName, serviceInfo.name), this.mUserId, serviceInfo.applicationInfo.uid);
                    providerProxy.start();
                    this.mProviderProxies.add(targetIndex, providerProxy);
                    this.mProvider.addProvider(providerProxy);
                    targetIndex++;
                } else if (sourceIndex >= targetIndex) {
                    TvRemoteProviderProxy provider = this.mProviderProxies.get(sourceIndex);
                    provider.start();
                    provider.rebindIfDisconnected();
                    Collections.swap(this.mProviderProxies, sourceIndex, targetIndex);
                    targetIndex++;
                }
            }
        }
        if (DEBUG) {
            Log.d(TAG, "scanPackages() targetIndex " + targetIndex);
        }
        if (targetIndex < this.mProviderProxies.size()) {
            for (int i = this.mProviderProxies.size() - 1; i >= targetIndex; i--) {
                TvRemoteProviderProxy providerProxy2 = this.mProviderProxies.get(i);
                this.mProvider.removeProvider(providerProxy2);
                this.mProviderProxies.remove(providerProxy2);
                providerProxy2.stop();
            }
        }
    }

    private boolean verifyServiceTrusted(ServiceInfo serviceInfo) {
        if (serviceInfo.permission == null || !serviceInfo.permission.equals("android.permission.BIND_TV_REMOTE_SERVICE")) {
            Slog.w(TAG, "Ignoring atv remote provider service because it did not require the BIND_TV_REMOTE_SERVICE permission in its manifest: " + serviceInfo.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + serviceInfo.name);
            return false;
        } else if (!serviceInfo.packageName.equals(this.mUnbundledServicePackage)) {
            Slog.w(TAG, "Ignoring atv remote provider service because the package has not been set and/or whitelisted: " + serviceInfo.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + serviceInfo.name);
            return false;
        } else if (!hasNecessaryPermissions(serviceInfo.packageName)) {
            Slog.w(TAG, "Ignoring atv remote provider service because its package does not have TV_VIRTUAL_REMOTE_CONTROLLER permission: " + serviceInfo.packageName);
            return false;
        } else {
            return true;
        }
    }

    private boolean hasNecessaryPermissions(String packageName) {
        if (this.mPackageManager.checkPermission("android.permission.TV_VIRTUAL_REMOTE_CONTROLLER", packageName) == 0) {
            return true;
        }
        return false;
    }

    private int findProvider(String packageName, String className) {
        int count = this.mProviderProxies.size();
        for (int i = 0; i < count; i++) {
            TvRemoteProviderProxy provider = this.mProviderProxies.get(i);
            if (provider.hasComponentName(packageName, className)) {
                return i;
            }
        }
        return -1;
    }
}
