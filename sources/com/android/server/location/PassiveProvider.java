package com.android.server.location;

import android.location.ILocationManager;
import android.location.Location;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.Log;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import java.io.FileDescriptor;
import java.io.PrintWriter;
/* loaded from: classes.dex */
public class PassiveProvider implements LocationProviderInterface {
    private static final ProviderProperties PROPERTIES = new ProviderProperties(false, false, false, false, false, false, false, 1, 2);
    private static final String TAG = "PassiveProvider";
    private final ILocationManager mLocationManager;
    private boolean mReportLocation;

    public PassiveProvider(ILocationManager locationManager) {
        this.mLocationManager = locationManager;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public String getName() {
        return "passive";
    }

    @Override // com.android.server.location.LocationProviderInterface
    public ProviderProperties getProperties() {
        return PROPERTIES;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public boolean isEnabled() {
        return true;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void enable() {
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void disable() {
    }

    @Override // com.android.server.location.LocationProviderInterface
    public int getStatus(Bundle extras) {
        if (this.mReportLocation) {
            return 2;
        }
        return 1;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public long getStatusUpdateTime() {
        return -1L;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void setRequest(ProviderRequest request, WorkSource source) {
        this.mReportLocation = request.reportLocation;
    }

    public void updateLocation(Location location) {
        if (this.mReportLocation) {
            try {
                this.mLocationManager.reportLocation(location, true);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling reportLocation");
            }
        }
    }

    @Override // com.android.server.location.LocationProviderInterface
    public boolean sendExtraCommand(String command, Bundle extras) {
        return false;
    }

    @Override // com.android.server.location.LocationProviderInterface
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mReportLocation=" + this.mReportLocation);
    }
}
