package com.android.server.backup.utils;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
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

    /*  JADX ERROR: JadxRuntimeException in pass: BlockProcessor
        jadx.core.utils.exceptions.JadxRuntimeException: Found unreachable blocks
        	at jadx.core.dex.visitors.blocks.DominatorTree.sortBlocks(DominatorTree.java:35)
        	at jadx.core.dex.visitors.blocks.DominatorTree.compute(DominatorTree.java:25)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.computeDominators(BlockProcessor.java:202)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.processBlocksTree(BlockProcessor.java:45)
        	at jadx.core.dex.visitors.blocks.BlockProcessor.visit(BlockProcessor.java:39)
        */
    public void serialize(T r8) throws java.io.IOException {
        /*
            r7 = this;
            java.io.FileOutputStream r0 = new java.io.FileOutputStream
            java.io.File r1 = r7.mFile
            r0.<init>(r1)
            r1 = 0
            java.io.BufferedOutputStream r2 = new java.io.BufferedOutputStream     // Catch: java.lang.Throwable -> L43
            r2.<init>(r0)     // Catch: java.lang.Throwable -> L43
            java.io.DataOutputStream r3 = new java.io.DataOutputStream     // Catch: java.lang.Throwable -> L37
            r3.<init>(r2)     // Catch: java.lang.Throwable -> L37
            com.android.server.backup.utils.DataStreamCodec<T> r4 = r7.mCodec     // Catch: java.lang.Throwable -> L2a
            r4.serialize(r8, r3)     // Catch: java.lang.Throwable -> L2a
            r3.flush()     // Catch: java.lang.Throwable -> L2a
            $closeResource(r1, r3)     // Catch: java.lang.Throwable -> L37
            $closeResource(r1, r2)     // Catch: java.lang.Throwable -> L43
            $closeResource(r1, r0)
            return
        L27:
            r4 = move-exception
            r5 = r1
            goto L30
        L2a:
            r4 = move-exception
            throw r4     // Catch: java.lang.Throwable -> L2c
        L2c:
            r5 = move-exception
            r6 = r5
            r5 = r4
            r4 = r6
        L30:
            $closeResource(r5, r3)     // Catch: java.lang.Throwable -> L37
            throw r4     // Catch: java.lang.Throwable -> L37
        L34:
            r3 = move-exception
            r4 = r1
            goto L3d
        L37:
            r3 = move-exception
            throw r3     // Catch: java.lang.Throwable -> L39
        L39:
            r4 = move-exception
            r6 = r4
            r4 = r3
            r3 = r6
        L3d:
            $closeResource(r4, r2)     // Catch: java.lang.Throwable -> L43
            throw r3     // Catch: java.lang.Throwable -> L43
        L41:
            r2 = move-exception
            goto L45
        L43:
            r1 = move-exception
            throw r1     // Catch: java.lang.Throwable -> L41
        L45:
            $closeResource(r1, r0)
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.utils.DataStreamFileCodec.serialize(java.lang.Object):void");
    }
}
