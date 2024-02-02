package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.IBinder;
import android.util.Slog;
import com.android.server.backup.BackupManagerConstants;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
public abstract class InternalEnumerateClient extends EnumerateClient {
    private List<Fingerprint> mEnrolledList;
    private List<Fingerprint> mUnknownFingerprints;

    public InternalEnumerateClient(Context context, long halDeviceId, IBinder token, IFingerprintServiceReceiver receiver, int groupId, int userId, boolean restricted, String owner, List<Fingerprint> enrolledList) {
        super(context, halDeviceId, token, receiver, userId, groupId, restricted, owner);
        this.mUnknownFingerprints = new ArrayList();
        this.mEnrolledList = enrolledList;
    }

    private void handleEnumeratedFingerprint(int fingerId, int groupId, int remaining) {
        boolean matched = false;
        int i = 0;
        while (true) {
            if (i >= this.mEnrolledList.size()) {
                break;
            } else if (this.mEnrolledList.get(i).getFingerId() != fingerId) {
                i++;
            } else {
                this.mEnrolledList.remove(i);
                matched = true;
                break;
            }
        }
        if (!matched && fingerId != 0) {
            Fingerprint fingerprint = new Fingerprint(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, groupId, fingerId, getHalDeviceId());
            this.mUnknownFingerprints.add(fingerprint);
        }
    }

    private void doFingerprintCleanup() {
        if (this.mEnrolledList == null) {
            return;
        }
        for (Fingerprint f : this.mEnrolledList) {
            Slog.e("FingerprintService", "Internal Enumerate: Removing dangling enrolled fingerprint: " + ((Object) f.getName()) + " " + f.getFingerId() + " " + f.getGroupId() + " " + f.getDeviceId());
            FingerprintUtils.getInstance().removeFingerprintIdForUser(getContext(), f.getFingerId(), getTargetUserId());
        }
        this.mEnrolledList.clear();
    }

    public List<Fingerprint> getUnknownFingerprints() {
        return this.mUnknownFingerprints;
    }

    @Override // com.android.server.fingerprint.EnumerateClient, com.android.server.fingerprint.ClientMonitor
    public boolean onEnumerationResult(int fingerId, int groupId, int remaining) {
        handleEnumeratedFingerprint(fingerId, groupId, remaining);
        if (remaining == 0) {
            doFingerprintCleanup();
        }
        return remaining == 0;
    }
}
