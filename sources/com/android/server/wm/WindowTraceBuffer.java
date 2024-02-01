package com.android.server.wm;

import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.function.Predicate;

/* loaded from: classes2.dex */
class WindowTraceBuffer {
    private static final long MAGIC_NUMBER_VALUE = 4990904633914181975L;
    private int mBufferCapacity;
    private int mBufferUsedSize;
    private final Object mBufferLock = new Object();
    private final Queue<ProtoOutputStream> mBuffer = new ArrayDeque();

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowTraceBuffer(int bufferCapacity) {
        this.mBufferCapacity = bufferCapacity;
        resetBuffer();
    }

    int getAvailableSpace() {
        return this.mBufferCapacity - this.mBufferUsedSize;
    }

    int size() {
        return this.mBuffer.size();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCapacity(int capacity) {
        this.mBufferCapacity = capacity;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void add(ProtoOutputStream proto) {
        int protoLength = proto.getRawSize();
        if (protoLength > this.mBufferCapacity) {
            throw new IllegalStateException("Trace object too large for the buffer. Buffer size:" + this.mBufferCapacity + " Object size: " + protoLength);
        }
        synchronized (this.mBufferLock) {
            discardOldest(protoLength);
            this.mBuffer.add(proto);
            this.mBufferUsedSize += protoLength;
            this.mBufferLock.notify();
        }
    }

    boolean contains(final byte[] other) {
        return this.mBuffer.stream().anyMatch(new Predicate() { // from class: com.android.server.wm.-$$Lambda$WindowTraceBuffer$N2ubPF2l5_1sFrDHIeldAcm7Q30
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                boolean equals;
                equals = Arrays.equals(((ProtoOutputStream) obj).getBytes(), other);
                return equals;
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeTraceToFile(File traceFile) throws IOException {
        synchronized (this.mBufferLock) {
            traceFile.delete();
            OutputStream os = new FileOutputStream(traceFile);
            traceFile.setReadable(true, false);
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(1125281431553L, MAGIC_NUMBER_VALUE);
            os.write(proto.getBytes());
            for (ProtoOutputStream protoOutputStream : this.mBuffer) {
                byte[] protoBytes = protoOutputStream.getBytes();
                os.write(protoBytes);
            }
            os.flush();
            os.close();
        }
    }

    private void discardOldest(int protoLength) {
        long availableSpace = getAvailableSpace();
        while (availableSpace < protoLength) {
            ProtoOutputStream item = this.mBuffer.poll();
            if (item == null) {
                throw new IllegalStateException("No element to discard from buffer");
            }
            this.mBufferUsedSize -= item.getRawSize();
            availableSpace = getAvailableSpace();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetBuffer() {
        synchronized (this.mBufferLock) {
            this.mBuffer.clear();
            this.mBufferUsedSize = 0;
        }
    }

    @VisibleForTesting
    int getBufferSize() {
        return this.mBufferUsedSize;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getStatus() {
        String str;
        synchronized (this.mBufferLock) {
            str = "Buffer size: " + this.mBufferCapacity + " bytes\nBuffer usage: " + this.mBufferUsedSize + " bytes\nElements in the buffer: " + this.mBuffer.size();
        }
        return str;
    }
}
