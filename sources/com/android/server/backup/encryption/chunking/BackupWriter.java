package com.android.server.backup.encryption.chunking;

import java.io.IOException;

/* loaded from: classes.dex */
public interface BackupWriter {
    void flush() throws IOException;

    long getBytesWritten();

    void writeBytes(byte[] bArr) throws IOException;

    void writeChunk(long j, int i) throws IOException;
}
