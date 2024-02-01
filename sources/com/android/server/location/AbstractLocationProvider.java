package com.android.server.location;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.os.WorkSource;
import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/* loaded from: classes.dex */
public abstract class AbstractLocationProvider {
    protected final Context mContext;
    private final LocationProviderManager mLocationProviderManager;

    /* loaded from: classes.dex */
    public interface LocationProviderManager {
        void onReportLocation(Location location);

        void onReportLocation(List<Location> list);

        void onSetEnabled(boolean z);

        void onSetProperties(ProviderProperties providerProperties);
    }

    public abstract void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr);

    public abstract void sendExtraCommand(String str, Bundle bundle);

    public abstract void setRequest(ProviderRequest providerRequest, WorkSource workSource);

    /* JADX INFO: Access modifiers changed from: protected */
    public AbstractLocationProvider(Context context, LocationProviderManager locationProviderManager) {
        this.mContext = context;
        this.mLocationProviderManager = locationProviderManager;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void reportLocation(Location location) {
        this.mLocationProviderManager.onReportLocation(location);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void reportLocation(List<Location> locations) {
        this.mLocationProviderManager.onReportLocation(locations);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setEnabled(boolean enabled) {
        this.mLocationProviderManager.onSetEnabled(enabled);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setProperties(ProviderProperties properties) {
        this.mLocationProviderManager.onSetProperties(properties);
    }

    public List<String> getProviderPackages() {
        return Collections.singletonList(this.mContext.getPackageName());
    }

    @Deprecated
    public int getStatus(Bundle extras) {
        return 2;
    }

    @Deprecated
    public long getStatusUpdateTime() {
        return 0L;
    }
}
