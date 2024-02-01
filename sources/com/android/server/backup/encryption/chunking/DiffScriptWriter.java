package com.android.server.backup.encryption.chunking;

import java.io.IOException;
import java.io.OutputStream;

/* loaded from: classes.dex */
interface DiffScriptWriter {

    /* loaded from: classes.dex */
    public interface Factory {
        DiffScriptWriter create(OutputStream outputStream);
    }

    void flush() throws IOException;

    void writeByte(byte b) throws IOException;

    void writeChunk(long j, int i) throws IOException;
}
