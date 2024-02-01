package com.google.android.startop.iorap;

import android.os.Parcel;
import android.os.Parcelable;

/* loaded from: classes2.dex */
public class RequestId implements Parcelable {
    public final long requestId;
    private static Object mLock = new Object();
    private static long mNextRequestId = 0;
    public static final Parcelable.Creator<RequestId> CREATOR = new Parcelable.Creator<RequestId>() { // from class: com.google.android.startop.iorap.RequestId.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public RequestId createFromParcel(Parcel in) {
            return new RequestId(in);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public RequestId[] newArray(int size) {
            return new RequestId[size];
        }
    };

    public static RequestId nextValueForSequence() {
        long currentRequestId;
        synchronized (mLock) {
            currentRequestId = mNextRequestId;
            mNextRequestId++;
        }
        return new RequestId(currentRequestId);
    }

    private RequestId(long requestId) {
        this.requestId = requestId;
        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        if (this.requestId < 0) {
            throw new IllegalArgumentException("request id must be non-negative");
        }
    }

    public String toString() {
        return String.format("{requestId: %d}", Long.valueOf(this.requestId));
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof RequestId) {
            return equals((RequestId) other);
        }
        return false;
    }

    private boolean equals(RequestId other) {
        return this.requestId == other.requestId;
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(this.requestId);
    }

    private RequestId(Parcel in) {
        this.requestId = in.readLong();
        checkConstructorArguments();
    }

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }
}
