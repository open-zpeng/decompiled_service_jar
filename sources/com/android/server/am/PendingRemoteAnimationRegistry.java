package com.android.server.am;

import android.app.ActivityOptions;
import android.os.Handler;
import android.util.ArrayMap;
import android.view.RemoteAnimationAdapter;
import com.android.server.am.PendingRemoteAnimationRegistry;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class PendingRemoteAnimationRegistry {
    private static final long TIMEOUT_MS = 3000;
    private final ArrayMap<String, Entry> mEntries = new ArrayMap<>();
    private final Handler mHandler;
    private final ActivityManagerService mService;

    /* JADX INFO: Access modifiers changed from: package-private */
    public PendingRemoteAnimationRegistry(ActivityManagerService service, Handler handler) {
        this.mService = service;
        this.mHandler = handler;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addPendingAnimation(String packageName, RemoteAnimationAdapter adapter) {
        this.mEntries.put(packageName, new Entry(packageName, adapter));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ActivityOptions overrideOptionsIfNeeded(String callingPackage, ActivityOptions options) {
        Entry entry = this.mEntries.get(callingPackage);
        if (entry == null) {
            return options;
        }
        if (options == null) {
            options = ActivityOptions.makeRemoteAnimation(entry.adapter);
        } else {
            options.setRemoteAnimationAdapter(entry.adapter);
        }
        this.mEntries.remove(callingPackage);
        return options;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class Entry {
        final RemoteAnimationAdapter adapter;
        final String packageName;

        Entry(final String packageName, RemoteAnimationAdapter adapter) {
            this.packageName = packageName;
            this.adapter = adapter;
            PendingRemoteAnimationRegistry.this.mHandler.postDelayed(new Runnable() { // from class: com.android.server.am.-$$Lambda$PendingRemoteAnimationRegistry$Entry$nMsaTjyghAPVeCjs7XjsdMM78mc
                @Override // java.lang.Runnable
                public final void run() {
                    PendingRemoteAnimationRegistry.Entry.lambda$new$0(PendingRemoteAnimationRegistry.Entry.this, packageName);
                }
            }, PendingRemoteAnimationRegistry.TIMEOUT_MS);
        }

        public static /* synthetic */ void lambda$new$0(Entry entry, String packageName) {
            synchronized (PendingRemoteAnimationRegistry.this.mService) {
                try {
                    ActivityManagerService.boostPriorityForLockedSection();
                    Entry entry2 = (Entry) PendingRemoteAnimationRegistry.this.mEntries.get(packageName);
                    if (entry2 == entry) {
                        PendingRemoteAnimationRegistry.this.mEntries.remove(packageName);
                    }
                } catch (Throwable th) {
                    ActivityManagerService.resetPriorityAfterLockedSection();
                    throw th;
                }
            }
            ActivityManagerService.resetPriorityAfterLockedSection();
        }
    }
}
