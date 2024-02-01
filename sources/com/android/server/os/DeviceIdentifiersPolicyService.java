package com.android.server.os;

import android.content.Context;
import android.os.Binder;
import android.os.IDeviceIdentifiersPolicyService;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import com.android.server.SystemService;
import com.android.server.UiModeManagerService;
/* loaded from: classes.dex */
public final class DeviceIdentifiersPolicyService extends SystemService {
    public DeviceIdentifiersPolicyService(Context context) {
        super(context);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("device_identifiers", new DeviceIdentifiersPolicy(getContext()));
    }

    /* loaded from: classes.dex */
    private static final class DeviceIdentifiersPolicy extends IDeviceIdentifiersPolicyService.Stub {
        private final Context mContext;

        public DeviceIdentifiersPolicy(Context context) {
            this.mContext = context;
        }

        public String getSerial() throws RemoteException {
            if (UserHandle.getAppId(Binder.getCallingUid()) != 1000 && this.mContext.checkCallingOrSelfPermission("android.permission.READ_PHONE_STATE") != 0 && this.mContext.checkCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") != 0) {
                throw new SecurityException("getSerial requires READ_PHONE_STATE or READ_PRIVILEGED_PHONE_STATE permission");
            }
            return SystemProperties.get("ro.serialno", UiModeManagerService.Shell.NIGHT_MODE_STR_UNKNOWN);
        }
    }
}
