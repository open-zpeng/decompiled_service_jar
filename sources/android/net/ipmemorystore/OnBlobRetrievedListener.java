package android.net.ipmemorystore;

import android.net.ipmemorystore.IOnBlobRetrievedListener;

/* loaded from: classes.dex */
public interface OnBlobRetrievedListener {
    void onBlobRetrieved(Status status, String str, String str2, Blob blob);

    static IOnBlobRetrievedListener toAIDL(OnBlobRetrievedListener listener) {
        return new IOnBlobRetrievedListener.Stub() { // from class: android.net.ipmemorystore.OnBlobRetrievedListener.1
            @Override // android.net.ipmemorystore.IOnBlobRetrievedListener
            public void onBlobRetrieved(StatusParcelable statusParcelable, String l2Key, String name, Blob blob) {
                OnBlobRetrievedListener onBlobRetrievedListener = OnBlobRetrievedListener.this;
                if (onBlobRetrievedListener != null) {
                    onBlobRetrievedListener.onBlobRetrieved(new Status(statusParcelable), l2Key, name, blob);
                }
            }

            @Override // android.net.ipmemorystore.IOnBlobRetrievedListener
            public int getInterfaceVersion() {
                return 3;
            }
        };
    }
}
