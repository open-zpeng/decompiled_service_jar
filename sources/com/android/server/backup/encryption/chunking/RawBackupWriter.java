package com.android.server.backup.encryption.chunking;

import java.io.IOException;
import java.io.OutputStream;

/* loaded from: classes.dex */
public class RawBackupWriter implements BackupWriter {
    private long bytesWritten;
    private final OutputStream outputStream;

    public RawBackupWriter(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override // com.android.server.backup.encryption.chunking.BackupWriter
    public void writeBytes(byte[] bytes) throws IOException {
        this.outputStream.write(bytes);
        this.bytesWritten += bytes.length;
    }

    @Override // com.android.server.backup.encryption.chunking.BackupWriter
    public void writeChunk(long start, int length) throws IOException {
        throw new UnsupportedOperationException("RawBackupWriter cannot write existing chunks");
    }

    @Override // com.android.server.backup.encryption.chunking.BackupWriter
    public long getBytesWritten() {
        return this.bytesWritten;
    }

    @Override // com.android.server.backup.encryption.chunking.BackupWriter
    public void flush() throws IOException {
        this.outputStream.flush();
    }
}
