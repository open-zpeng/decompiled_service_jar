package com.android.server.backup.encryption.chunking;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

/* loaded from: classes.dex */
public interface Chunker {

    /* loaded from: classes.dex */
    public interface ChunkConsumer {
        void accept(byte[] bArr) throws GeneralSecurityException;
    }

    void chunkify(InputStream inputStream, ChunkConsumer chunkConsumer) throws IOException, GeneralSecurityException;
}
