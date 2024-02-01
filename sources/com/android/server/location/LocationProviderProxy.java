package com.android.server.location;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import com.android.internal.location.ILocationProvider;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.internal.os.TransferPipe;
import com.android.server.LocationManagerService;
import com.android.server.ServiceWatcher;
import com.android.server.backup.BackupManagerConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
/* loaded from: classes.dex */
public class LocationProviderProxy implements LocationProviderInterface {
    private static final boolean D = LocationManagerService.D;
    private static final String TAG = "LocationProviderProxy";
    private final Context mContext;
    private final String mName;
    private ProviderProperties mProperties;
    private final ServiceWatcher mServiceWatcher;
    private Object mLock = new Object();
    private boolean mEnabled = false;
    private ProviderRequest mRequest = null;
    private WorkSource mWorksource = new WorkSource();
    private Runnable mNewServiceWork = new Runnable() { // from class: com.android.server.location.LocationProviderProxy.1
        @Override // java.lang.Runnable
        public void run() {
            final boolean enabled;
            final ProviderRequest request;
            final WorkSource source;
            if (LocationProviderProxy.D) {
                Log.d(LocationProviderProxy.TAG, "applying state to connected service");
            }
            final ProviderProperties[] properties = new ProviderProperties[1];
            synchronized (LocationProviderProxy.this.mLock) {
                enabled = LocationProviderProxy.this.mEnabled;
                request = LocationProviderProxy.this.mRequest;
                source = LocationProviderProxy.this.mWorksource;
            }
            LocationProviderProxy.this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.1.1
                @Override // com.android.server.ServiceWatcher.BinderRunner
                public void run(IBinder binder) {
                    ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                    try {
                        properties[0] = service.getProperties();
                        if (properties[0] == null) {
                            Log.e(LocationProviderProxy.TAG, LocationProviderProxy.this.mServiceWatcher.getBestPackageName() + " has invalid location provider properties");
                        }
                        if (enabled) {
                            service.enable();
                            if (request != null) {
                                service.setRequest(request, source);
                            }
                        }
                    } catch (RemoteException e) {
                        Log.w(LocationProviderProxy.TAG, e);
                    } catch (Exception e2) {
                        Log.e(LocationProviderProxy.TAG, "Exception from " + LocationProviderProxy.this.mServiceWatcher.getBestPackageName(), e2);
                    }
                }
            });
            synchronized (LocationProviderProxy.this.mLock) {
                LocationProviderProxy.this.mProperties = properties[0];
            }
        }
    };

    public static LocationProviderProxy createAndBind(Context context, String name, String action, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, Handler handler) {
        LocationProviderProxy proxy = new LocationProviderProxy(context, name, action, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, handler);
        if (proxy.bind()) {
            return proxy;
        }
        return null;
    }

    private LocationProviderProxy(Context context, String name, String action, int overlaySwitchResId, int defaultServicePackageNameResId, int initialPackageNamesResId, Handler handler) {
        this.mContext = context;
        this.mName = name;
        Context context2 = this.mContext;
        this.mServiceWatcher = new ServiceWatcher(context2, "LocationProviderProxy-" + name, action, overlaySwitchResId, defaultServicePackageNameResId, initialPackageNamesResId, this.mNewServiceWork, handler);
    }

    private boolean bind() {
        return this.mServiceWatcher.start();
    }

    public String getConnectedPackageName() {
        return this.mServiceWatcher.getBestPackageName();
    }

    @Override // com.android.server.location.LocationProviderInterface
    public String getName() {
        return this.mName;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public ProviderProperties getProperties() {
        ProviderProperties providerProperties;
        synchronized (this.mLock) {
            providerProperties = this.mProperties;
        }
        return providerProperties;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void enable() {
        synchronized (this.mLock) {
            this.mEnabled = true;
        }
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.2
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    service.enable();
                } catch (RemoteException e) {
                    Log.w(LocationProviderProxy.TAG, e);
                } catch (Exception e2) {
                    Log.e(LocationProviderProxy.TAG, "Exception from " + LocationProviderProxy.this.mServiceWatcher.getBestPackageName(), e2);
                }
            }
        });
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void disable() {
        synchronized (this.mLock) {
            this.mEnabled = false;
        }
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.3
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    service.disable();
                } catch (RemoteException e) {
                    Log.w(LocationProviderProxy.TAG, e);
                } catch (Exception e2) {
                    Log.e(LocationProviderProxy.TAG, "Exception from " + LocationProviderProxy.this.mServiceWatcher.getBestPackageName(), e2);
                }
            }
        });
    }

    @Override // com.android.server.location.LocationProviderInterface
    public boolean isEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mEnabled;
        }
        return z;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void setRequest(final ProviderRequest request, final WorkSource source) {
        synchronized (this.mLock) {
            this.mRequest = request;
            this.mWorksource = source;
        }
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.4
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    service.setRequest(request, source);
                } catch (RemoteException e) {
                    Log.w(LocationProviderProxy.TAG, e);
                } catch (Exception e2) {
                    Log.e(LocationProviderProxy.TAG, "Exception from " + LocationProviderProxy.this.mServiceWatcher.getBestPackageName(), e2);
                }
            }
        });
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void dump(final FileDescriptor fd, final PrintWriter pw, final String[] args) {
        pw.append("REMOTE SERVICE");
        pw.append(" name=").append((CharSequence) this.mName);
        pw.append(" pkg=").append((CharSequence) this.mServiceWatcher.getBestPackageName());
        PrintWriter append = pw.append(" version=");
        append.append((CharSequence) (BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + this.mServiceWatcher.getBestVersion()));
        pw.append('\n');
        if (!this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.5
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    TransferPipe.dumpAsync(service.asBinder(), fd, args);
                } catch (RemoteException | IOException e) {
                    PrintWriter printWriter = pw;
                    printWriter.println("Failed to dump location provider: " + e);
                }
            }
        })) {
            pw.println("service down (null)");
        }
    }

    @Override // com.android.server.location.LocationProviderInterface
    public int getStatus(final Bundle extras) {
        final int[] result = {1};
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.6
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    result[0] = service.getStatus(extras);
                } catch (RemoteException e) {
                    Log.w(LocationProviderProxy.TAG, e);
                } catch (Exception e2) {
                    Log.e(LocationProviderProxy.TAG, "Exception from " + LocationProviderProxy.this.mServiceWatcher.getBestPackageName(), e2);
                }
            }
        });
        return result[0];
    }

    @Override // com.android.server.location.LocationProviderInterface
    public long getStatusUpdateTime() {
        final long[] result = {0};
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.7
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    result[0] = service.getStatusUpdateTime();
                } catch (RemoteException e) {
                    Log.w(LocationProviderProxy.TAG, e);
                } catch (Exception e2) {
                    Log.e(LocationProviderProxy.TAG, "Exception from " + LocationProviderProxy.this.mServiceWatcher.getBestPackageName(), e2);
                }
            }
        });
        return result[0];
    }

    @Override // com.android.server.location.LocationProviderInterface
    public boolean sendExtraCommand(final String command, final Bundle extras) {
        final boolean[] result = {false};
        this.mServiceWatcher.runOnBinder(new ServiceWatcher.BinderRunner() { // from class: com.android.server.location.LocationProviderProxy.8
            @Override // com.android.server.ServiceWatcher.BinderRunner
            public void run(IBinder binder) {
                ILocationProvider service = ILocationProvider.Stub.asInterface(binder);
                try {
                    result[0] = service.sendExtraCommand(command, extras);
                } catch (RemoteException e) {
                    Log.w(LocationProviderProxy.TAG, e);
                } catch (Exception e2) {
                    Log.e(LocationProviderProxy.TAG, "Exception from " + LocationProviderProxy.this.mServiceWatcher.getBestPackageName(), e2);
                }
            }
        });
        return result[0];
    }
}
