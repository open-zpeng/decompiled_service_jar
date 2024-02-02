package com.android.server.power.batterysaver;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.IoThread;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class FileUpdater {
    private static final boolean DEBUG = false;
    private static final String PROP_SKIP_WRITE = "debug.batterysaver.no_write_files";
    private static final String TAG = "BatterySaverController";
    private static final String TAG_DEFAULT_ROOT = "defaults";
    private final int MAX_RETRIES;
    private final long RETRY_INTERVAL_MS;
    private final Context mContext;
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mDefaultValues;
    private Runnable mHandleWriteOnHandlerRunnable;
    private final Handler mHandler;
    private final Object mLock;
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mPendingWrites;
    @GuardedBy("mLock")
    private int mRetries;

    public FileUpdater(Context context) {
        this(context, IoThread.get().getLooper(), 10, 5000);
    }

    @VisibleForTesting
    FileUpdater(Context context, Looper looper, int maxRetries, int retryIntervalMs) {
        this.mLock = new Object();
        this.mPendingWrites = new ArrayMap<>();
        this.mDefaultValues = new ArrayMap<>();
        this.mRetries = 0;
        this.mHandleWriteOnHandlerRunnable = new Runnable() { // from class: com.android.server.power.batterysaver.-$$Lambda$FileUpdater$NUmipjKCJwbgmFbIcGS3uaz3QFk
            @Override // java.lang.Runnable
            public final void run() {
                FileUpdater.this.handleWriteOnHandler();
            }
        };
        this.mContext = context;
        this.mHandler = new Handler(looper);
        this.MAX_RETRIES = maxRetries;
        this.RETRY_INTERVAL_MS = retryIntervalMs;
    }

    public void systemReady(boolean runtimeRestarted) {
        synchronized (this.mLock) {
            try {
                if (runtimeRestarted) {
                    if (loadDefaultValuesLocked()) {
                        Slog.d(TAG, "Default values loaded after runtime restart; writing them...");
                        restoreDefault();
                    }
                } else {
                    injectDefaultValuesFilename().delete();
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public void writeFiles(ArrayMap<String, String> fileValues) {
        synchronized (this.mLock) {
            for (int i = fileValues.size() - 1; i >= 0; i--) {
                String file = fileValues.keyAt(i);
                String value = fileValues.valueAt(i);
                this.mPendingWrites.put(file, value);
            }
            this.mRetries = 0;
            this.mHandler.removeCallbacks(this.mHandleWriteOnHandlerRunnable);
            this.mHandler.post(this.mHandleWriteOnHandlerRunnable);
        }
    }

    public void restoreDefault() {
        synchronized (this.mLock) {
            this.mPendingWrites.clear();
            writeFiles(this.mDefaultValues);
        }
    }

    private String getKeysString(Map<String, String> source) {
        return new ArrayList(source.keySet()).toString();
    }

    private ArrayMap<String, String> cloneMap(ArrayMap<String, String> source) {
        return new ArrayMap<>(source);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleWriteOnHandler() {
        synchronized (this.mLock) {
            if (this.mPendingWrites.size() == 0) {
                return;
            }
            ArrayMap<String, String> writes = cloneMap(this.mPendingWrites);
            boolean needRetry = false;
            int size = writes.size();
            for (int i = 0; i < size; i++) {
                String file = writes.keyAt(i);
                String value = writes.valueAt(i);
                if (ensureDefaultLoaded(file)) {
                    try {
                        injectWriteToFile(file, value);
                        removePendingWrite(file);
                    } catch (IOException e) {
                        needRetry = true;
                    }
                }
            }
            if (needRetry) {
                scheduleRetry();
            }
        }
    }

    private void removePendingWrite(String file) {
        synchronized (this.mLock) {
            this.mPendingWrites.remove(file);
        }
    }

    private void scheduleRetry() {
        synchronized (this.mLock) {
            if (this.mPendingWrites.size() == 0) {
                return;
            }
            this.mRetries++;
            if (this.mRetries > this.MAX_RETRIES) {
                doWtf("Gave up writing files: " + getKeysString(this.mPendingWrites));
                return;
            }
            this.mHandler.removeCallbacks(this.mHandleWriteOnHandlerRunnable);
            this.mHandler.postDelayed(this.mHandleWriteOnHandlerRunnable, this.RETRY_INTERVAL_MS);
        }
    }

    private boolean ensureDefaultLoaded(String file) {
        synchronized (this.mLock) {
            if (this.mDefaultValues.containsKey(file)) {
                return true;
            }
            try {
                String originalValue = injectReadFromFileTrimmed(file);
                synchronized (this.mLock) {
                    this.mDefaultValues.put(file, originalValue);
                    saveDefaultValuesLocked();
                }
                return true;
            } catch (IOException e) {
                injectWtf("Unable to read from file", e);
                removePendingWrite(file);
                return false;
            }
        }
    }

    @VisibleForTesting
    String injectReadFromFileTrimmed(String file) throws IOException {
        return IoUtils.readFileAsString(file).trim();
    }

    @VisibleForTesting
    void injectWriteToFile(String file, String value) throws IOException {
        if (injectShouldSkipWrite()) {
            Slog.i(TAG, "Skipped writing to '" + file + "'");
            return;
        }
        try {
            FileWriter out = new FileWriter(file);
            out.write(value);
            $closeResource(null, out);
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "Failed writing '" + value + "' to '" + file + "': " + e.getMessage());
            throw e;
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

    @GuardedBy("mLock")
    private void saveDefaultValuesLocked() {
        AtomicFile file = new AtomicFile(injectDefaultValuesFilename());
        FileOutputStream outs = null;
        try {
            file.getBaseFile().getParentFile().mkdirs();
            outs = file.startWrite();
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(outs, StandardCharsets.UTF_8.name());
            fastXmlSerializer.startDocument(null, true);
            fastXmlSerializer.startTag(null, TAG_DEFAULT_ROOT);
            XmlUtils.writeMapXml(this.mDefaultValues, fastXmlSerializer, (XmlUtils.WriteMapCallback) null);
            fastXmlSerializer.endTag(null, TAG_DEFAULT_ROOT);
            fastXmlSerializer.endDocument();
            file.finishWrite(outs);
        } catch (IOException | RuntimeException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:14:0x003b, code lost:
        android.util.Slog.e(com.android.server.power.batterysaver.FileUpdater.TAG, "Invalid root tag: " + r9);
     */
    /* JADX WARN: Code restructure failed: missing block: B:15:0x0052, code lost:
        if (r5 == null) goto L25;
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x0054, code lost:
        $closeResource(null, r5);
     */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x0057, code lost:
        return false;
     */
    @com.android.internal.annotations.GuardedBy("mLock")
    @com.android.internal.annotations.VisibleForTesting
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    boolean loadDefaultValuesLocked() {
        /*
            r13 = this;
            android.util.AtomicFile r0 = new android.util.AtomicFile
            java.io.File r1 = r13.injectDefaultValuesFilename()
            r0.<init>(r1)
            r1 = 0
            r2 = r1
            r3 = 0
            r4 = 1
            java.io.FileInputStream r5 = r0.openRead()     // Catch: java.lang.Throwable -> L72 java.io.FileNotFoundException -> L8e
            org.xmlpull.v1.XmlPullParser r6 = android.util.Xml.newPullParser()     // Catch: java.lang.Throwable -> L6a
            java.nio.charset.Charset r7 = java.nio.charset.StandardCharsets.UTF_8     // Catch: java.lang.Throwable -> L6a
            java.lang.String r7 = r7.name()     // Catch: java.lang.Throwable -> L6a
            r6.setInput(r5, r7)     // Catch: java.lang.Throwable -> L6a
        L1e:
            int r7 = r6.next()     // Catch: java.lang.Throwable -> L6a
            r8 = r7
            if (r7 == r4) goto L62
            r7 = 2
            if (r8 == r7) goto L29
            goto L1e
        L29:
            int r7 = r6.getDepth()     // Catch: java.lang.Throwable -> L6a
            java.lang.String r9 = r6.getName()     // Catch: java.lang.Throwable -> L6a
            if (r7 != r4) goto L58
            java.lang.String r10 = "defaults"
            boolean r10 = r10.equals(r9)     // Catch: java.lang.Throwable -> L6a
            if (r10 != 0) goto L1e
            java.lang.String r10 = "BatterySaverController"
            java.lang.StringBuilder r11 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L6a
            r11.<init>()     // Catch: java.lang.Throwable -> L6a
            java.lang.String r12 = "Invalid root tag: "
            r11.append(r12)     // Catch: java.lang.Throwable -> L6a
            r11.append(r9)     // Catch: java.lang.Throwable -> L6a
            java.lang.String r11 = r11.toString()     // Catch: java.lang.Throwable -> L6a
            android.util.Slog.e(r10, r11)     // Catch: java.lang.Throwable -> L6a
            if (r5 == 0) goto L57
            $closeResource(r1, r5)     // Catch: java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.io.FileNotFoundException -> L8e
        L57:
            return r3
        L58:
            java.lang.String[] r10 = new java.lang.String[r4]     // Catch: java.lang.Throwable -> L6a
            java.lang.String r11 = "defaults"
            android.util.ArrayMap r11 = com.android.internal.util.XmlUtils.readThisArrayMapXml(r6, r11, r10, r1)     // Catch: java.lang.Throwable -> L6a
            r2 = r11
            goto L1e
        L62:
            if (r5 == 0) goto L90
            $closeResource(r1, r5)     // Catch: java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.io.FileNotFoundException -> L8e
            goto L90
        L68:
            r6 = move-exception
            goto L6c
        L6a:
            r1 = move-exception
            throw r1     // Catch: java.lang.Throwable -> L68
        L6c:
            if (r5 == 0) goto L71
            $closeResource(r1, r5)     // Catch: java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.io.FileNotFoundException -> L8e
        L71:
            throw r6     // Catch: java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.lang.Throwable -> L72 java.io.FileNotFoundException -> L8e
        L72:
            r1 = move-exception
            java.lang.String r5 = "BatterySaverController"
            java.lang.StringBuilder r6 = new java.lang.StringBuilder
            r6.<init>()
            java.lang.String r7 = "Failed to read file "
            r6.append(r7)
            java.io.File r7 = r0.getBaseFile()
            r6.append(r7)
            java.lang.String r6 = r6.toString()
            android.util.Slog.e(r5, r6, r1)
            goto L91
        L8e:
            r1 = move-exception
            r2 = 0
        L90:
        L91:
            if (r2 == 0) goto L9e
            android.util.ArrayMap<java.lang.String, java.lang.String> r1 = r13.mDefaultValues
            r1.clear()
            android.util.ArrayMap<java.lang.String, java.lang.String> r1 = r13.mDefaultValues
            r1.putAll(r2)
            return r4
        L9e:
            return r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.power.batterysaver.FileUpdater.loadDefaultValuesLocked():boolean");
    }

    private void doWtf(String message) {
        injectWtf(message, null);
    }

    @VisibleForTesting
    void injectWtf(String message, Throwable e) {
        Slog.wtf(TAG, message, e);
    }

    File injectDefaultValuesFilename() {
        File dir = new File(Environment.getDataSystemDirectory(), "battery-saver");
        dir.mkdirs();
        return new File(dir, "default-values.xml");
    }

    @VisibleForTesting
    boolean injectShouldSkipWrite() {
        return SystemProperties.getBoolean(PROP_SKIP_WRITE, false);
    }

    @VisibleForTesting
    ArrayMap<String, String> getDefaultValuesForTest() {
        return this.mDefaultValues;
    }
}
