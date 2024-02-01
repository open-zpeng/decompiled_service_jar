package com.android.server.am;

import android.util.SparseArray;
import com.android.server.am.ActivityManagerService;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class PendingTempWhitelists {
    private final SparseArray<ActivityManagerService.PendingTempWhitelist> mPendingTempWhitelist = new SparseArray<>();
    private ActivityManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PendingTempWhitelists(ActivityManagerService service) {
        this.mService = service;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void put(int uid, ActivityManagerService.PendingTempWhitelist value) {
        this.mPendingTempWhitelist.put(uid, value);
        this.mService.mAtmInternal.onUidAddedToPendingTempWhitelist(uid, value.tag);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAt(int index) {
        int uid = this.mPendingTempWhitelist.keyAt(index);
        this.mPendingTempWhitelist.removeAt(index);
        this.mService.mAtmInternal.onUidRemovedFromPendingTempWhitelist(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManagerService.PendingTempWhitelist get(int uid) {
        return this.mPendingTempWhitelist.get(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int size() {
        return this.mPendingTempWhitelist.size();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityManagerService.PendingTempWhitelist valueAt(int index) {
        return this.mPendingTempWhitelist.valueAt(index);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int indexOfKey(int key) {
        return this.mPendingTempWhitelist.indexOfKey(key);
    }
}
