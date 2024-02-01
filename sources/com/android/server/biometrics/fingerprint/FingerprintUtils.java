package com.android.server.biometrics.fingerprint;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.fingerprint.Fingerprint;
import android.text.TextUtils;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.server.biometrics.BiometricUtils;
import java.util.List;

/* loaded from: classes.dex */
public class FingerprintUtils implements BiometricUtils {
    private static FingerprintUtils sInstance;
    private static final Object sInstanceLock = new Object();
    @GuardedBy({"this"})
    private final SparseArray<FingerprintUserState> mUsers = new SparseArray<>();

    public static FingerprintUtils getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new FingerprintUtils();
            }
        }
        return sInstance;
    }

    private FingerprintUtils() {
    }

    @Override // com.android.server.biometrics.BiometricUtils
    public List<Fingerprint> getBiometricsForUser(Context ctx, int userId) {
        return getStateForUser(ctx, userId).getBiometrics();
    }

    @Override // com.android.server.biometrics.BiometricUtils
    public void addBiometricForUser(Context context, int userId, BiometricAuthenticator.Identifier identifier) {
        getStateForUser(context, userId).addBiometric(identifier);
    }

    @Override // com.android.server.biometrics.BiometricUtils
    public void removeBiometricForUser(Context context, int userId, int fingerId) {
        getStateForUser(context, userId).removeBiometric(fingerId);
    }

    @Override // com.android.server.biometrics.BiometricUtils
    public void renameBiometricForUser(Context context, int userId, int fingerId, CharSequence name) {
        if (TextUtils.isEmpty(name)) {
            return;
        }
        getStateForUser(context, userId).renameBiometric(fingerId, name);
    }

    @Override // com.android.server.biometrics.BiometricUtils
    public CharSequence getUniqueName(Context context, int userId) {
        return getStateForUser(context, userId).getUniqueName();
    }

    private FingerprintUserState getStateForUser(Context ctx, int userId) {
        FingerprintUserState state;
        synchronized (this) {
            state = this.mUsers.get(userId);
            if (state == null) {
                state = new FingerprintUserState(ctx, userId);
                this.mUsers.put(userId, state);
            }
        }
        return state;
    }
}
