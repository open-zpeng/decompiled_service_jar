package com.android.server.pm;

import android.content.Context;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
/* loaded from: classes.dex */
public class ProtectedPackages {
    private final Context mContext;
    @GuardedBy("this")
    private String mDeviceOwnerPackage;
    @GuardedBy("this")
    private int mDeviceOwnerUserId;
    @GuardedBy("this")
    private final String mDeviceProvisioningPackage;
    @GuardedBy("this")
    private SparseArray<String> mProfileOwnerPackages;

    public ProtectedPackages(Context context) {
        this.mContext = context;
        this.mDeviceProvisioningPackage = this.mContext.getResources().getString(17039664);
    }

    public synchronized void setDeviceAndProfileOwnerPackages(int deviceOwnerUserId, String deviceOwnerPackage, SparseArray<String> profileOwnerPackages) {
        this.mDeviceOwnerUserId = deviceOwnerUserId;
        SparseArray<String> sparseArray = null;
        this.mDeviceOwnerPackage = deviceOwnerUserId == -10000 ? null : deviceOwnerPackage;
        if (profileOwnerPackages != null) {
            sparseArray = profileOwnerPackages.clone();
        }
        this.mProfileOwnerPackages = sparseArray;
    }

    private synchronized boolean hasDeviceOwnerOrProfileOwner(int userId, String packageName) {
        if (packageName == null) {
            return false;
        }
        if (this.mDeviceOwnerPackage != null && this.mDeviceOwnerUserId == userId && packageName.equals(this.mDeviceOwnerPackage)) {
            return true;
        }
        if (this.mProfileOwnerPackages != null) {
            if (packageName.equals(this.mProfileOwnerPackages.get(userId))) {
                return true;
            }
        }
        return false;
    }

    public synchronized String getDeviceOwnerOrProfileOwnerPackage(int userId) {
        if (this.mDeviceOwnerUserId == userId) {
            return this.mDeviceOwnerPackage;
        }
        return this.mProfileOwnerPackages.get(userId);
    }

    private synchronized boolean isProtectedPackage(String packageName) {
        boolean z;
        if (packageName != null) {
            z = packageName.equals(this.mDeviceProvisioningPackage) ? true : true;
        }
        z = false;
        return z;
    }

    public boolean isPackageStateProtected(int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName) || isProtectedPackage(packageName);
    }

    public boolean isPackageDataProtected(int userId, String packageName) {
        return hasDeviceOwnerOrProfileOwner(userId, packageName) || isProtectedPackage(packageName);
    }
}
