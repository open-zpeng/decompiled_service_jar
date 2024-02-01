package com.android.server.am;

import android.util.SparseArray;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class ActiveUids {
    private final SparseArray<UidRecord> mActiveUids = new SparseArray<>();
    private boolean mPostChangesToAtm;
    private ActivityManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActiveUids(ActivityManagerService service, boolean postChangesToAtm) {
        this.mService = service;
        this.mPostChangesToAtm = postChangesToAtm;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void put(int uid, UidRecord value) {
        this.mActiveUids.put(uid, value);
        if (this.mPostChangesToAtm) {
            this.mService.mAtmInternal.onUidActive(uid, value.getCurProcState());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void remove(int uid) {
        this.mActiveUids.remove(uid);
        if (this.mPostChangesToAtm) {
            this.mService.mAtmInternal.onUidInactive(uid);
        }
    }

    void clear() {
        this.mActiveUids.clear();
        if (this.mPostChangesToAtm) {
            this.mService.mAtmInternal.onActiveUidsCleared();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UidRecord get(int uid) {
        return this.mActiveUids.get(uid);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int size() {
        return this.mActiveUids.size();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UidRecord valueAt(int index) {
        return this.mActiveUids.valueAt(index);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int keyAt(int index) {
        return this.mActiveUids.keyAt(index);
    }

    int indexOfKey(int uid) {
        return this.mActiveUids.indexOfKey(uid);
    }
}
