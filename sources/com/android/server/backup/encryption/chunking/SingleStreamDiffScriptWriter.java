package com.android.server.backup.encryption.chunking;

import com.android.internal.util.Preconditions;
import com.android.server.backup.encryption.chunking.DiffScriptWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/* loaded from: classes.dex */
public class SingleStreamDiffScriptWriter implements DiffScriptWriter {
    static final byte LINE_SEPARATOR = 10;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private int mBufferSize = 0;
    private final byte[] mByteBuffer;
    private final int mMaxNewByteChunkSize;
    private final OutputStream mOutputStream;
    private ByteRange mReusableChunk;

    public SingleStreamDiffScriptWriter(OutputStream outputStream, int maxNewByteChunkSize) {
        this.mOutputStream = outputStream;
        this.mMaxNewByteChunkSize = maxNewByteChunkSize;
        this.mByteBuffer = new byte[maxNewByteChunkSize];
    }

    @Override // com.android.server.backup.encryption.chunking.DiffScriptWriter
    public void writeByte(byte b) throws IOException {
        if (this.mReusableChunk != null) {
            writeReusableChunk();
        }
        byte[] bArr = this.mByteBuffer;
        int i = this.mBufferSize;
        this.mBufferSize = i + 1;
        bArr[i] = b;
        if (this.mBufferSize == this.mMaxNewByteChunkSize) {
            writeByteBuffer();
        }
    }

    @Override // com.android.server.backup.encryption.chunking.DiffScriptWriter
    public void writeChunk(long chunkStart, int chunkLength) throws IOException {
        Preconditions.checkArgument(chunkStart >= 0);
        Preconditions.checkArgument(chunkLength > 0);
        if (this.mBufferSize != 0) {
            writeByteBuffer();
        }
        ByteRange byteRange = this.mReusableChunk;
        if (byteRange != null && byteRange.getEnd() + 1 == chunkStart) {
            this.mReusableChunk = this.mReusableChunk.extend(chunkLength);
            return;
        }
        writeReusableChunk();
        this.mReusableChunk = new ByteRange(chunkStart, (chunkLength + chunkStart) - 1);
    }

    @Override // com.android.server.backup.encryption.chunking.DiffScriptWriter
    public void flush() throws IOException {
        Preconditions.checkState(this.mBufferSize == 0 || this.mReusableChunk == null);
        if (this.mBufferSize != 0) {
            writeByteBuffer();
        }
        if (this.mReusableChunk != null) {
            writeReusableChunk();
        }
        this.mOutputStream.flush();
    }

    private void writeByteBuffer() throws IOException {
        this.mOutputStream.write(Integer.toString(this.mBufferSize).getBytes(UTF_8));
        this.mOutputStream.write(10);
        this.mOutputStream.write(this.mByteBuffer, 0, this.mBufferSize);
        this.mOutputStream.write(10);
        this.mBufferSize = 0;
    }

    private void writeReusableChunk() throws IOException {
        if (this.mReusableChunk != null) {
            this.mOutputStream.write(String.format(Locale.US, "%d-%d", Long.valueOf(this.mReusableChunk.getStart()), Long.valueOf(this.mReusableChunk.getEnd())).getBytes(UTF_8));
            this.mOutputStream.write(10);
            this.mReusableChunk = null;
        }
    }

    /* loaded from: classes.dex */
    public static class Factory implements DiffScriptWriter.Factory {
        private final int mMaxNewByteChunkSize;
        private final OutputStreamWrapper mOutputStreamWrapper;

        public Factory(int maxNewByteChunkSize, OutputStreamWrapper outputStreamWrapper) {
            this.mMaxNewByteChunkSize = maxNewByteChunkSize;
            this.mOutputStreamWrapper = outputStreamWrapper;
        }

        @Override // com.android.server.backup.encryption.chunking.DiffScriptWriter.Factory
        public SingleStreamDiffScriptWriter create(OutputStream outputStream) {
            OutputStreamWrapper outputStreamWrapper = this.mOutputStreamWrapper;
            if (outputStreamWrapper != null) {
                outputStream = outputStreamWrapper.wrap(outputStream);
            }
            return new SingleStreamDiffScriptWriter(outputStream, this.mMaxNewByteChunkSize);
        }
    }
}
