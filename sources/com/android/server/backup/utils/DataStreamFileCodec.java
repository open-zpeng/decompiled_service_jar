package com.android.server.backup.utils;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/* loaded from: classes.dex */
public final class DataStreamFileCodec<T> {
    private final DataStreamCodec<T> mCodec;
    private final File mFile;

    public DataStreamFileCodec(File file, DataStreamCodec<T> codec) {
        this.mFile = file;
        this.mCodec = codec;
    }

    public T deserialize() throws IOException {
        FileInputStream fileInputStream = new FileInputStream(this.mFile);
        try {
            DataInputStream dataInputStream = new DataInputStream(fileInputStream);
            T deserialize = this.mCodec.deserialize(dataInputStream);
            $closeResource(null, dataInputStream);
            $closeResource(null, fileInputStream);
            return deserialize;
        } finally {
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    public void serialize(T t) throws IOException {
        FileOutputStream fileOutputStream = new FileOutputStream(this.mFile);
        try {
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
            DataOutputStream dataOutputStream = new DataOutputStream(bufferedOutputStream);
            try {
                this.mCodec.serialize(t, dataOutputStream);
                dataOutputStream.flush();
                $closeResource(null, dataOutputStream);
                $closeResource(null, bufferedOutputStream);
                $closeResource(null, fileOutputStream);
            } finally {
            }
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                $closeResource(th, fileOutputStream);
                throw th2;
            }
        }
    }
}
