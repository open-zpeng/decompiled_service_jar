package com.android.server.oemlock;

import android.content.Context;
import android.hardware.oemlock.V1_0.IOemLock;
import android.os.RemoteException;
import android.util.Slog;
import java.util.ArrayList;
import java.util.NoSuchElementException;

/* loaded from: classes.dex */
class VendorLock extends OemLock {
    private static final String TAG = "OemLock";
    private Context mContext;
    private IOemLock mOemLock;

    /* JADX INFO: Access modifiers changed from: package-private */
    public static IOemLock getOemLockHalService() {
        try {
            return IOemLock.getService();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (NoSuchElementException e2) {
            Slog.i(TAG, "OemLock HAL not present on device");
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public VendorLock(Context context, IOemLock oemLock) {
        this.mContext = context;
        this.mOemLock = oemLock;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.oemlock.OemLock
    public String getLockName() {
        final Integer[] requestStatus = new Integer[1];
        final String[] lockName = new String[1];
        try {
            this.mOemLock.getName(new IOemLock.getNameCallback() { // from class: com.android.server.oemlock.-$$Lambda$VendorLock$mE2wEMNMcvqMft72oSVABYa_mYs
                @Override // android.hardware.oemlock.V1_0.IOemLock.getNameCallback
                public final void onValues(int i, String str) {
                    VendorLock.lambda$getLockName$0(requestStatus, lockName, i, str);
                }
            });
            int intValue = requestStatus[0].intValue();
            if (intValue != 0) {
                if (intValue != 1) {
                    Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
                    return null;
                }
                Slog.e(TAG, "Failed to get OEM lock name.");
                return null;
            }
            return lockName[0];
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get name from HAL", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$getLockName$0(Integer[] requestStatus, String[] lockName, int status, String name) {
        requestStatus[0] = Integer.valueOf(status);
        lockName[0] = name;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.oemlock.OemLock
    public void setOemUnlockAllowedByCarrier(boolean allowed, byte[] signature) {
        try {
            ArrayList<Byte> signatureBytes = toByteArrayList(signature);
            int oemUnlockAllowedByCarrier = this.mOemLock.setOemUnlockAllowedByCarrier(allowed, signatureBytes);
            if (oemUnlockAllowedByCarrier == 0) {
                Slog.i(TAG, "Updated carrier allows OEM lock state to: " + allowed);
                return;
            }
            if (oemUnlockAllowedByCarrier != 1) {
                if (oemUnlockAllowedByCarrier != 2) {
                    Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
                } else if (signatureBytes.isEmpty()) {
                    throw new IllegalArgumentException("Signature required for carrier unlock");
                } else {
                    throw new SecurityException("Invalid signature used in attempt to carrier unlock");
                }
            }
            throw new RuntimeException("Failed to set carrier OEM unlock state");
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set carrier state with HAL", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.oemlock.OemLock
    public boolean isOemUnlockAllowedByCarrier() {
        final Integer[] requestStatus = new Integer[1];
        final Boolean[] allowedByCarrier = new Boolean[1];
        try {
            this.mOemLock.isOemUnlockAllowedByCarrier(new IOemLock.isOemUnlockAllowedByCarrierCallback() { // from class: com.android.server.oemlock.-$$Lambda$VendorLock$HjegvthxXAHFarV_FukbaMGePGU
                @Override // android.hardware.oemlock.V1_0.IOemLock.isOemUnlockAllowedByCarrierCallback
                public final void onValues(int i, boolean z) {
                    VendorLock.lambda$isOemUnlockAllowedByCarrier$1(requestStatus, allowedByCarrier, i, z);
                }
            });
            int intValue = requestStatus[0].intValue();
            if (intValue == 0) {
                return allowedByCarrier[0].booleanValue();
            }
            if (intValue != 1) {
                Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
            }
            throw new RuntimeException("Failed to get carrier OEM unlock state");
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get carrier state from HAL");
            throw e.rethrowFromSystemServer();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$isOemUnlockAllowedByCarrier$1(Integer[] requestStatus, Boolean[] allowedByCarrier, int status, boolean allowed) {
        requestStatus[0] = Integer.valueOf(status);
        allowedByCarrier[0] = Boolean.valueOf(allowed);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.oemlock.OemLock
    public void setOemUnlockAllowedByDevice(boolean allowedByDevice) {
        try {
            int oemUnlockAllowedByDevice = this.mOemLock.setOemUnlockAllowedByDevice(allowedByDevice);
            if (oemUnlockAllowedByDevice == 0) {
                Slog.i(TAG, "Updated device allows OEM lock state to: " + allowedByDevice);
                return;
            }
            if (oemUnlockAllowedByDevice != 1) {
                Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
            }
            throw new RuntimeException("Failed to set device OEM unlock state");
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to set device state with HAL", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.oemlock.OemLock
    public boolean isOemUnlockAllowedByDevice() {
        final Integer[] requestStatus = new Integer[1];
        final Boolean[] allowedByDevice = new Boolean[1];
        try {
            this.mOemLock.isOemUnlockAllowedByDevice(new IOemLock.isOemUnlockAllowedByDeviceCallback() { // from class: com.android.server.oemlock.-$$Lambda$VendorLock$8zNsLj4Jt-s5XEEk_KIC2zQR29g
                @Override // android.hardware.oemlock.V1_0.IOemLock.isOemUnlockAllowedByDeviceCallback
                public final void onValues(int i, boolean z) {
                    VendorLock.lambda$isOemUnlockAllowedByDevice$2(requestStatus, allowedByDevice, i, z);
                }
            });
            int intValue = requestStatus[0].intValue();
            if (intValue == 0) {
                return allowedByDevice[0].booleanValue();
            }
            if (intValue != 1) {
                Slog.e(TAG, "Unknown return value indicates code is out of sync with HAL");
            }
            throw new RuntimeException("Failed to get device OEM unlock state");
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to get devie state from HAL");
            throw e.rethrowFromSystemServer();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$isOemUnlockAllowedByDevice$2(Integer[] requestStatus, Boolean[] allowedByDevice, int status, boolean allowed) {
        requestStatus[0] = Integer.valueOf(status);
        allowedByDevice[0] = Boolean.valueOf(allowed);
    }

    private ArrayList<Byte> toByteArrayList(byte[] data) {
        if (data == null) {
            return new ArrayList<>();
        }
        ArrayList<Byte> result = new ArrayList<>(data.length);
        for (byte b : data) {
            result.add(Byte.valueOf(b));
        }
        return result;
    }
}
