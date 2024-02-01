package com.android.server.location;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ILocationProviderManager;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.FgThread;
import com.android.server.LocationManagerService;
import com.android.server.ServiceWatcher;
import com.android.server.location.AbstractLocationProvider;
import com.android.server.pm.DumpState;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/* loaded from: classes.dex */
public class LocationProviderProxy extends AbstractLocationProvider {
    private static final boolean D = LocationManagerService.D;
    private static final String TAG = "LocationProviderProxy";
    private final ILocationProviderManager.Stub mManager;
    @GuardedBy({"mProviderPackagesLock"})
    private final CopyOnWriteArrayList<String> mProviderPackages;
    private final Object mProviderPackagesLock;
    @GuardedBy({"mRequestLock"})
    private ProviderRequest mRequest;
    private final Object mRequestLock;
    private final ServiceWatcher mServiceWatcher;
    @GuardedBy({"mRequestLock"})
    private WorkSource mWorkSource;

    public static LocationProviderProxy createAndBind(Context context, AbstractLocationProvider.LocationProviderManager locationProviderManager, String action, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, locationProviderManager, action, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId);
        if (proxy.bind()) {
            return proxy;
        }
        return null;
    }

    private LocationProviderProxy(Context context, AbstractLocationProvider.LocationProviderManager locationProviderManager, String action, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId) {
        super(context, locationProviderManager);
        this.mProviderPackagesLock = new Object();
        this.mRequestLock = new Object();
        this.mManager = new ILocationProviderManager.Stub() { // from class: com.android.server.location.LocationProviderProxy.1
            public void onSetAdditionalProviderPackages(List<String> packageNames) {
                LocationProviderProxy.this.onSetAdditionalProviderPackages(packageNames);
            }

            public void onSetEnabled(boolean enabled) {
                LocationProviderProxy.this.setEnabled(enabled);
            }

            public void onSetProperties(ProviderProperties properties) {
                LocationProviderProxy.this.setProperties(properties);
            }

            public void onReportLocation(Location location) {
                LocationProviderProxy.this.reportLocation(location);
            }
        };
        this.mProviderPackages = new CopyOnWriteArrayList<>();
        this.mServiceWatcher = new AnonymousClass2(context, TAG, action, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, FgThread.getHandler());
        this.mRequest = null;
        this.mWorkSource = new WorkSource();
    }

    /* renamed from: com.android.server.location.LocationProviderProxy$2  reason: invalid class name */
    /* loaded from: classes.dex */
    class AnonymousClass2 extends ServiceWatcher {
        AnonymousClass2(Context context, String logTag, String action, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, Handler handler) {
            super(context, logTag, action, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, handler);
        }

        @Override // com.android.server.ServiceWatcher
        protected void onBind() {
            final LocationProviderProxy locationProviderProxy = LocationProviderProxy.this;
            runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.-$$Lambda$LocationProviderProxy$2$QT3uzVX4fLIc1b7F_cP9P1hzluA
                @Override // com.android.server.ServiceWatcher.BinderRunner
                public final void run(IBinder iBinder) {
                    LocationProviderProxy.this.initializeService(iBinder);
                }
            });
        }

        @Override // com.android.server.ServiceWatcher
        protected void onUnbind() {
            LocationProviderProxy.this.resetProviderPackages(Collections.emptyList());
            LocationProviderProxy.this.setEnabled(false);
            LocationProviderProxy.this.setProperties(null);
        }
    }

    private boolean bind() {
        return this.mServiceWatcher.start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initializeService(IBinder binder) throws RemoteException {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        if (D) {
            Log.d(TAG, "applying state to connected service " + this.mServiceWatcher);
        }
        resetProviderPackages(Collections.emptyList());
        service.setLocationProviderManager(this.mManager);
        synchronized (this.mRequestLock) {
            if (this.mRequest != null) {
                service.setRequest(this.mRequest, this.mWorkSource);
            }
        }
    }

    @Override // com.android.server.location.AbstractLocationProvider
    public List<String> getProviderPackages() {
        CopyOnWriteArrayList<String> copyOnWriteArrayList;
        synchronized (this.mProviderPackagesLock) {
            copyOnWriteArrayList = this.mProviderPackages;
        }
        return copyOnWriteArrayList;
    }

    @Override // com.android.server.location.AbstractLocationProvider
    public void setRequest(final ProviderRequest request, final WorkSource source) {
        synchronized (this.mRequestLock) {
            this.mRequest = request;
            this.mWorkSource = source;
        }
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.-$$Lambda$LocationProviderProxy$p3DjIvk7Of_sUF4Dc9plMNfdklc
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public final void run(IBinder iBinder) {
                LocationProviderProxy.lambda$setRequest$0(request, source, iBinder);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setRequest$0(ProviderRequest request, WorkSource source, IBinder binder) throws RemoteException {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        service.setRequest(request, source);
    }

    @Override // com.android.server.location.AbstractLocationProvider
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("    service=" + this.mServiceWatcher);
        synchronized (this.mProviderPackagesLock) {
            if (this.mProviderPackages.size() > 1) {
                pw.println("    additional packages=" + this.mProviderPackages);
            }
        }
    }

    @Override // com.android.server.location.AbstractLocationProvider
    public int getStatus(final Bundle extras) {
        return ((Integer) this.mServiceWatcher.runOnBinderBlocking(new ServiceWatcher.BlockingBinderRunner() { // from class: com.android.server.location.-$$Lambda$LocationProviderProxy$HNmfjzTDtRS07pxKSpIAhcUh88Y
            @Override // com.android.server.ServiceWatcher.BlockingBinderRunner
            public final Object run(IBinder iBinder) {
                return LocationProviderProxy.lambda$getStatus$1(extras, iBinder);
            }
        }, 1)).intValue();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Integer lambda$getStatus$1(Bundle extras, IBinder binder) throws RemoteException {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        return Integer.valueOf(service.getStatus(extras));
    }

    @Override // com.android.server.location.AbstractLocationProvider
    public long getStatusUpdateTime() {
        return ((Long) this.mServiceWatcher.runOnBinderBlocking(new ServiceWatcher.BlockingBinderRunner() { // from class: com.android.server.location.-$$Lambda$LocationProviderProxy$UCv9FaTEgs0wfXFwhEpgptlzg-k
            @Override // com.android.server.ServiceWatcher.BlockingBinderRunner
            public final Object run(IBinder iBinder) {
                return LocationProviderProxy.lambda$getStatusUpdateTime$2(iBinder);
            }
        }, 0L)).longValue();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Long lambda$getStatusUpdateTime$2(IBinder binder) throws RemoteException {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        return Long.valueOf(service.getStatusUpdateTime());
    }

    @Override // com.android.server.location.AbstractLocationProvider
    public void sendExtraCommand(final String command, final Bundle extras) {
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.-$$Lambda$LocationProviderProxy$QOuHAndhADLzUnTK39JocbxRlQY
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public final void run(IBinder iBinder) {
                LocationProviderProxy.lambda$sendExtraCommand$3(command, extras, iBinder);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$sendExtraCommand$3(String command, Bundle extras, IBinder binder) throws RemoteException {
        ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
        service.sendExtraCommand(command, extras);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onSetAdditionalProviderPackages(List<String> packageNames) {
        resetProviderPackages(packageNames);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resetProviderPackages(List<String> additionalPackageNames) {
        ArrayList<String> permittedPackages = new ArrayList<>(additionalPackageNames.size());
        for (String packageName : additionalPackageNames) {
            try {
                this.mContext.getPackageManager().getPackageInfo(packageName, DumpState.DUMP_DEXOPT);
                permittedPackages.add(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, this.mServiceWatcher + " specified unknown additional provider package: " + packageName);
            }
        }
        synchronized (this.mProviderPackagesLock) {
            this.mProviderPackages.clear();
            String myPackage = this.mServiceWatcher.getCurrentPackageName();
            if (myPackage != null) {
                this.mProviderPackages.add(myPackage);
                this.mProviderPackages.addAll(permittedPackages);
            }
        }
    }
}
