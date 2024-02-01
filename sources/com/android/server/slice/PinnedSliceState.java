package com.android.server.slice;

import android.app.slice.SliceSpec;
import android.content.ContentProviderClient;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/* loaded from: classes.dex */
public class PinnedSliceState {
    private static final long SLICE_TIMEOUT = 5000;
    private static final String TAG = "PinnedSliceState";
    private final Object mLock;
    private final String mPkg;
    private final SliceManagerService mService;
    private boolean mSlicePinned;
    private final Uri mUri;
    @GuardedBy({"mLock"})
    private final ArraySet<String> mPinnedPkgs = new ArraySet<>();
    @GuardedBy({"mLock"})
    private final ArrayMap<IBinder, ListenerInfo> mListeners = new ArrayMap<>();
    @GuardedBy({"mLock"})
    private SliceSpec[] mSupportedSpecs = null;
    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() { // from class: com.android.server.slice.-$$Lambda$PinnedSliceState$KzxFkvfomRuMb5PD8_pIHDIhUUE
        @Override // android.os.IBinder.DeathRecipient
        public final void binderDied() {
            PinnedSliceState.this.handleRecheckListeners();
        }
    };

    public PinnedSliceState(SliceManagerService service, Uri uri, String pkg) {
        this.mService = service;
        this.mUri = uri;
        this.mPkg = pkg;
        this.mLock = this.mService.getLock();
    }

    public String getPkg() {
        return this.mPkg;
    }

    public SliceSpec[] getSpecs() {
        return this.mSupportedSpecs;
    }

    public void mergeSpecs(final SliceSpec[] supportedSpecs) {
        synchronized (this.mLock) {
            if (this.mSupportedSpecs == null) {
                this.mSupportedSpecs = supportedSpecs;
            } else {
                List<SliceSpec> specs = Arrays.asList(this.mSupportedSpecs);
                this.mSupportedSpecs = (SliceSpec[]) specs.stream().map(new Function() { // from class: com.android.server.slice.-$$Lambda$PinnedSliceState$j_JfEZwPCa729MjgsTSd8MAItIw
                    @Override // java.util.function.Function
                    public final Object apply(Object obj) {
                        return PinnedSliceState.this.lambda$mergeSpecs$0$PinnedSliceState(supportedSpecs, (SliceSpec) obj);
                    }
                }).filter(new Predicate() { // from class: com.android.server.slice.-$$Lambda$PinnedSliceState$2PaYhOaggf1E5xg82LTTEwxmLE4
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return PinnedSliceState.lambda$mergeSpecs$1((SliceSpec) obj);
                    }
                }).toArray(new IntFunction() { // from class: com.android.server.slice.-$$Lambda$PinnedSliceState$vxnx7v9Z67Tj9aywVmtdX48br1M
                    @Override // java.util.function.IntFunction
                    public final Object apply(int i) {
                        return PinnedSliceState.lambda$mergeSpecs$2(i);
                    }
                });
            }
        }
    }

    public /* synthetic */ SliceSpec lambda$mergeSpecs$0$PinnedSliceState(SliceSpec[] supportedSpecs, SliceSpec s) {
        SliceSpec other = findSpec(supportedSpecs, s.getType());
        if (other == null) {
            return null;
        }
        if (other.getRevision() < s.getRevision()) {
            return other;
        }
        return s;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$mergeSpecs$1(SliceSpec s) {
        return s != null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ SliceSpec[] lambda$mergeSpecs$2(int x$0) {
        return new SliceSpec[x$0];
    }

    private SliceSpec findSpec(SliceSpec[] specs, String type) {
        for (SliceSpec spec : specs) {
            if (Objects.equals(spec.getType(), type)) {
                return spec;
            }
        }
        return null;
    }

    public Uri getUri() {
        return this.mUri;
    }

    public void destroy() {
        setSlicePinned(false);
    }

    private void setSlicePinned(boolean pinned) {
        synchronized (this.mLock) {
            if (this.mSlicePinned == pinned) {
                return;
            }
            this.mSlicePinned = pinned;
            if (pinned) {
                this.mService.getHandler().post(new Runnable() { // from class: com.android.server.slice.-$$Lambda$PinnedSliceState$TZdoqC_LDA8If7sQ7WXz9LM6VHg
                    @Override // java.lang.Runnable
                    public final void run() {
                        PinnedSliceState.this.handleSendPinned();
                    }
                });
            } else {
                this.mService.getHandler().post(new Runnable() { // from class: com.android.server.slice.-$$Lambda$PinnedSliceState$t5Vl61Ns1u_83c4ri7920sczEu0
                    @Override // java.lang.Runnable
                    public final void run() {
                        PinnedSliceState.this.handleSendUnpinned();
                    }
                });
            }
        }
    }

    public void pin(String pkg, SliceSpec[] specs, IBinder token) {
        synchronized (this.mLock) {
            this.mListeners.put(token, new ListenerInfo(token, pkg, true, Binder.getCallingUid(), Binder.getCallingPid()));
            try {
                token.linkToDeath(this.mDeathRecipient, 0);
            } catch (RemoteException e) {
            }
            mergeSpecs(specs);
            setSlicePinned(true);
        }
    }

    public boolean unpin(String pkg, IBinder token) {
        synchronized (this.mLock) {
            token.unlinkToDeath(this.mDeathRecipient, 0);
            this.mListeners.remove(token);
        }
        return !hasPinOrListener();
    }

    public boolean isListening() {
        boolean z;
        synchronized (this.mLock) {
            z = !this.mListeners.isEmpty();
        }
        return z;
    }

    @VisibleForTesting
    public boolean hasPinOrListener() {
        boolean z;
        synchronized (this.mLock) {
            z = (this.mPinnedPkgs.isEmpty() && this.mListeners.isEmpty()) ? false : true;
        }
        return z;
    }

    ContentProviderClient getClient() {
        ContentProviderClient client = this.mService.getContext().getContentResolver().acquireUnstableContentProviderClient(this.mUri);
        if (client == null) {
            return null;
        }
        client.setDetectNotResponding(SLICE_TIMEOUT);
        return client;
    }

    private void checkSelfRemove() {
        if (!hasPinOrListener()) {
            this.mService.removePinnedSlice(this.mUri);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRecheckListeners() {
        if (hasPinOrListener()) {
            synchronized (this.mLock) {
                for (int i = this.mListeners.size() - 1; i >= 0; i--) {
                    ListenerInfo l = this.mListeners.valueAt(i);
                    if (!l.token.isBinderAlive()) {
                        this.mListeners.removeAt(i);
                    }
                }
                checkSelfRemove();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSendPinned() {
        ContentProviderClient client = getClient();
        if (client != null) {
            try {
                Bundle b = new Bundle();
                b.putParcelable("slice_uri", this.mUri);
                try {
                    client.call("pin", null, b);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to contact " + this.mUri, e);
                }
                $closeResource(null, client);
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    $closeResource(th, client);
                    throw th2;
                }
            }
        } else if (client != null) {
            $closeResource(null, client);
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSendUnpinned() {
        ContentProviderClient client = getClient();
        if (client != null) {
            try {
                Bundle b = new Bundle();
                b.putParcelable("slice_uri", this.mUri);
                try {
                    client.call("unpin", null, b);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to contact " + this.mUri, e);
                }
                $closeResource(null, client);
            } catch (Throwable th) {
                try {
                    throw th;
                } catch (Throwable th2) {
                    $closeResource(th, client);
                    throw th2;
                }
            }
        } else if (client != null) {
            $closeResource(null, client);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ListenerInfo {
        private int callingPid;
        private int callingUid;
        private boolean hasPermission;
        private String pkg;
        private IBinder token;

        public ListenerInfo(IBinder token, String pkg, boolean hasPermission, int callingUid, int callingPid) {
            this.token = token;
            this.pkg = pkg;
            this.hasPermission = hasPermission;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
        }
    }
}
