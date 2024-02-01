package com.android.server.pm;

import android.util.SparseBooleanArray;
import com.android.server.pm.PackageManagerService;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class PackageVerificationState {
    private final PackageManagerService.InstallParams mParams;
    private boolean mRequiredVerificationComplete;
    private boolean mRequiredVerificationPassed;
    private final int mRequiredVerifierUid;
    private boolean mSufficientVerificationComplete;
    private boolean mSufficientVerificationPassed;
    private final SparseBooleanArray mSufficientVerifierUids = new SparseBooleanArray();
    private boolean mExtendedTimeout = false;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageVerificationState(int requiredVerifierUid, PackageManagerService.InstallParams params) {
        this.mRequiredVerifierUid = requiredVerifierUid;
        this.mParams = params;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageManagerService.InstallParams getInstallParams() {
        return this.mParams;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addSufficientVerifier(int uid) {
        this.mSufficientVerifierUids.put(uid, true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean setVerifierResponse(int uid, int code) {
        if (uid == this.mRequiredVerifierUid) {
            this.mRequiredVerificationComplete = true;
            if (code != 1) {
                if (code == 2) {
                    this.mSufficientVerifierUids.clear();
                } else {
                    this.mRequiredVerificationPassed = false;
                    return true;
                }
            }
            this.mRequiredVerificationPassed = true;
            return true;
        } else if (this.mSufficientVerifierUids.get(uid)) {
            if (code == 1) {
                this.mSufficientVerificationComplete = true;
                this.mSufficientVerificationPassed = true;
            }
            this.mSufficientVerifierUids.delete(uid);
            if (this.mSufficientVerifierUids.size() == 0) {
                this.mSufficientVerificationComplete = true;
            }
            return true;
        } else {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isVerificationComplete() {
        if (!this.mRequiredVerificationComplete) {
            return false;
        }
        if (this.mSufficientVerifierUids.size() == 0) {
            return true;
        }
        return this.mSufficientVerificationComplete;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isInstallAllowed() {
        if (!this.mRequiredVerificationPassed) {
            return false;
        }
        if (this.mSufficientVerificationComplete) {
            return this.mSufficientVerificationPassed;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void extendTimeout() {
        if (!this.mExtendedTimeout) {
            this.mExtendedTimeout = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean timeoutExtended() {
        return this.mExtendedTimeout;
    }
}
