package com.android.server.biometrics.iris;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import com.android.server.biometrics.BiometricServiceBase;
import com.android.server.biometrics.BiometricUtils;
import com.android.server.biometrics.Constants;
import java.util.List;

/* loaded from: classes.dex */
public class IrisService extends BiometricServiceBase {
    private static final String TAG = "IrisService";

    public IrisService(Context context) {
        super(context);
    }

    @Override // com.android.server.biometrics.BiometricServiceBase, com.android.server.SystemService
    public void onStart() {
        super.onStart();
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getTag() {
        return TAG;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected BiometricServiceBase.DaemonWrapper getDaemonWrapper() {
        return null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected BiometricUtils getBiometricUtils() {
        return null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected Constants getConstants() {
        return null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean hasReachedEnrollmentLimit(int userId) {
        return false;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void updateActiveGroup(int userId, String clientPackage) {
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getLockoutResetIntent() {
        return null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getLockoutBroadcastPermission() {
        return null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected long getHalDeviceId() {
        return 0L;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean hasEnrolledBiometrics(int userId) {
        return false;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected String getManageBiometricPermission() {
        return null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected void checkUseBiometricPermission() {
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected boolean checkAppOps(int uid, String opPackageName) {
        return false;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected List<? extends BiometricAuthenticator.Identifier> getEnrolledTemplates(int userId) {
        return null;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected int statsModality() {
        return 2;
    }

    @Override // com.android.server.biometrics.BiometricServiceBase
    protected int getLockoutMode() {
        return 0;
    }
}
