package com.android.server.backup.encryption.chunking;

import java.io.IOException;

/* loaded from: classes.dex */
public interface EncryptedChunkEncoder {
    int getChunkOrderingType();

    int getEncodedLengthOfChunk(EncryptedChunk encryptedChunk);

    void writeChunkToWriter(BackupWriter backupWriter, EncryptedChunk encryptedChunk) throws IOException;
}
