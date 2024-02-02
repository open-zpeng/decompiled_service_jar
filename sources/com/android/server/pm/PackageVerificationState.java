package com.android.server.pm;

import android.util.SparseBooleanArray;
import com.android.server.pm.PackageManagerService;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class PackageVerificationState {
    private final PackageManagerService.InstallArgs mArgs;
    private boolean mRequiredVerificationComplete;
    private boolean mRequiredVerificationPassed;
    private final int mRequiredVerifierUid;
    private boolean mSufficientVerificationComplete;
    private boolean mSufficientVerificationPassed;
    private final SparseBooleanArray mSufficientVerifierUids = new SparseBooleanArray();
    private boolean mExtendedTimeout = false;

    public PackageVerificationState(int requiredVerifierUid, PackageManagerService.InstallArgs args) {
        this.mRequiredVerifierUid = requiredVerifierUid;
        this.mArgs = args;
    }

    public PackageManagerService.InstallArgs getInstallArgs() {
        return this.mArgs;
    }

    public void addSufficientVerifier(int uid) {
        this.mSufficientVerifierUids.put(uid, true);
    }

    public boolean setVerifierResponse(int uid, int code) {
        if (uid == this.mRequiredVerifierUid) {
            this.mRequiredVerificationComplete = true;
            switch (code) {
                case 2:
                    this.mSufficientVerifierUids.clear();
                case 1:
                    this.mRequiredVerificationPassed = true;
                    break;
                default:
                    this.mRequiredVerificationPassed = false;
                    break;
            }
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

    public boolean isVerificationComplete() {
        if (!this.mRequiredVerificationComplete) {
            return false;
        }
        if (this.mSufficientVerifierUids.size() == 0) {
            return true;
        }
        return this.mSufficientVerificationComplete;
    }

    public boolean isInstallAllowed() {
        if (!this.mRequiredVerificationPassed) {
            return false;
        }
        if (this.mSufficientVerificationComplete) {
            return this.mSufficientVerificationPassed;
        }
        return true;
    }

    public void extendTimeout() {
        if (!this.mExtendedTimeout) {
            this.mExtendedTimeout = true;
        }
    }

    public boolean timeoutExtended() {
        return this.mExtendedTimeout;
    }
}
