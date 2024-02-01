package com.android.server.backup.encryption.chunking;

import com.android.internal.util.Preconditions;

/* loaded from: classes.dex */
final class ByteRange {
    private final long mEnd;
    private final long mStart;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ByteRange(long start, long end) {
        Preconditions.checkArgument(start >= 0);
        Preconditions.checkArgument(end >= start);
        this.mStart = start;
        this.mEnd = end;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getStart() {
        return this.mStart;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public long getEnd() {
        return this.mEnd;
    }

    int getLength() {
        return (int) ((this.mEnd - this.mStart) + 1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ByteRange extend(long length) {
        Preconditions.checkArgument(length > 0);
        return new ByteRange(this.mStart, this.mEnd + length);
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof ByteRange) {
            ByteRange byteRange = (ByteRange) o;
            return this.mEnd == byteRange.mEnd && this.mStart == byteRange.mStart;
        }
        return false;
    }

    public int hashCode() {
        long j = this.mStart;
        int result = (17 * 31) + ((int) (j ^ (j >>> 32)));
        long j2 = this.mEnd;
        return (result * 31) + ((int) (j2 ^ (j2 >>> 32)));
    }

    public String toString() {
        return String.format("ByteRange{mStart=%d, mEnd=%d}", Long.valueOf(this.mStart), Long.valueOf(this.mEnd));
    }
}
