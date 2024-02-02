package com.android.server.am;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
/* loaded from: classes.dex */
public class DumpHeapProvider extends ContentProvider {
    static File sHeapDumpJavaFile;
    static final Object sLock = new Object();

    public static File getJavaFile() {
        File file;
        synchronized (sLock) {
            file = sHeapDumpJavaFile;
        }
        return file;
    }

    @Override // android.content.ContentProvider
    public boolean onCreate() {
        synchronized (sLock) {
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            File heapdumpDir = new File(systemDir, "heapdump");
            heapdumpDir.mkdir();
            sHeapDumpJavaFile = new File(heapdumpDir, "javaheap.bin");
        }
        return true;
    }

    @Override // android.content.ContentProvider
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override // android.content.ContentProvider
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override // android.content.ContentProvider
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override // android.content.ContentProvider
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override // android.content.ContentProvider
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor open;
        synchronized (sLock) {
            String path = uri.getEncodedPath();
            String tag = Uri.decode(path);
            if (tag.equals("/java")) {
                open = ParcelFileDescriptor.open(sHeapDumpJavaFile, 268435456);
            } else {
                throw new FileNotFoundException("Invalid path for " + uri);
            }
        }
        return open;
    }
}
