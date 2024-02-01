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
import com.android.server.wm.ActivityTaskManagerService;
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
    @GuardedBy({"mLock"})
    private final ArrayMap<String, String> mDefaultValues;
    private Runnable mHandleWriteOnHandlerRunnable;
    private final Handler mHandler;
    private final Object mLock;
    @GuardedBy({"mLock"})
    private final ArrayMap<String, String> mPendingWrites;
    @GuardedBy({"mLock"})
    private int mRetries;

    public FileUpdater(Context context) {
        this(context, IoThread.get().getLooper(), 10, ActivityTaskManagerService.KEY_DISPATCHING_TIMEOUT_MS);
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
                FileUpdater.this.lambda$new$0$FileUpdater();
            }
        };
        this.mContext = context;
        this.mHandler = new Handler(looper);
        this.MAX_RETRIES = maxRetries;
        this.RETRY_INTERVAL_MS = retryIntervalMs;
    }

    public void systemReady(boolean runtimeRestarted) {
        synchronized (this.mLock) {
            if (runtimeRestarted) {
                if (loadDefaultValuesLocked()) {
                    Slog.d(TAG, "Default values loaded after runtime restart; writing them...");
                    restoreDefault();
                }
            } else {
                injectDefaultValuesFilename().delete();
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
    /* renamed from: handleWriteOnHandler */
    public void lambda$new$0$FileUpdater() {
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

    @GuardedBy({"mLock"})
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

    /* JADX WARN: Code restructure failed: missing block: B:15:0x003d, code lost:
        android.util.Slog.e(com.android.server.power.batterysaver.FileUpdater.TAG, "Invalid root tag: " + r10);
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x0052, code lost:
        if (r5 == null) goto L26;
     */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x0054, code lost:
        $closeResource(null, r5);
     */
    /* JADX WARN: Code restructure failed: missing block: B:18:0x0057, code lost:
        return false;
     */
    @com.android.internal.annotations.GuardedBy({"mLock"})
    @com.android.internal.annotations.VisibleForTesting
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    boolean loadDefaultValuesLocked() {
        /*
            r13 = this;
            java.lang.String r0 = "BatterySaverController"
            android.util.AtomicFile r1 = new android.util.AtomicFile
            java.io.File r2 = r13.injectDefaultValuesFilename()
            r1.<init>(r2)
            r2 = 0
            r3 = 0
            r4 = 1
            java.io.FileInputStream r5 = r1.openRead()     // Catch: java.lang.Throwable -> L6f java.io.FileNotFoundException -> L89
            org.xmlpull.v1.XmlPullParser r6 = android.util.Xml.newPullParser()     // Catch: java.lang.Throwable -> L66
            java.nio.charset.Charset r7 = java.nio.charset.StandardCharsets.UTF_8     // Catch: java.lang.Throwable -> L66
            java.lang.String r7 = r7.name()     // Catch: java.lang.Throwable -> L66
            r6.setInput(r5, r7)     // Catch: java.lang.Throwable -> L66
        L1f:
            int r7 = r6.next()     // Catch: java.lang.Throwable -> L66
            r8 = r7
            r9 = 0
            if (r7 == r4) goto L60
            r7 = 2
            if (r8 == r7) goto L2b
            goto L1f
        L2b:
            int r7 = r6.getDepth()     // Catch: java.lang.Throwable -> L66
            java.lang.String r10 = r6.getName()     // Catch: java.lang.Throwable -> L66
            java.lang.String r11 = "defaults"
            if (r7 != r4) goto L58
            boolean r11 = r11.equals(r10)     // Catch: java.lang.Throwable -> L66
            if (r11 != 0) goto L1f
            java.lang.StringBuilder r11 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L66
            r11.<init>()     // Catch: java.lang.Throwable -> L66
            java.lang.String r12 = "Invalid root tag: "
            r11.append(r12)     // Catch: java.lang.Throwable -> L66
            r11.append(r10)     // Catch: java.lang.Throwable -> L66
            java.lang.String r11 = r11.toString()     // Catch: java.lang.Throwable -> L66
            android.util.Slog.e(r0, r11)     // Catch: java.lang.Throwable -> L66
            if (r5 == 0) goto L57
            $closeResource(r9, r5)     // Catch: java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.io.FileNotFoundException -> L89
        L57:
            return r3
        L58:
            java.lang.String[] r12 = new java.lang.String[r4]     // Catch: java.lang.Throwable -> L66
            android.util.ArrayMap r9 = com.android.internal.util.XmlUtils.readThisArrayMapXml(r6, r11, r12, r9)     // Catch: java.lang.Throwable -> L66
            r2 = r9
            goto L1f
        L60:
            if (r5 == 0) goto L8b
            $closeResource(r9, r5)     // Catch: java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.io.FileNotFoundException -> L89
            goto L8b
        L66:
            r6 = move-exception
            throw r6     // Catch: java.lang.Throwable -> L68
        L68:
            r7 = move-exception
            if (r5 == 0) goto L6e
            $closeResource(r6, r5)     // Catch: java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.io.FileNotFoundException -> L89
        L6e:
            throw r7     // Catch: java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.lang.Throwable -> L6f java.io.FileNotFoundException -> L89
        L6f:
            r5 = move-exception
            java.lang.StringBuilder r6 = new java.lang.StringBuilder
            r6.<init>()
            java.lang.String r7 = "Failed to read file "
            r6.append(r7)
            java.io.File r7 = r1.getBaseFile()
            r6.append(r7)
            java.lang.String r6 = r6.toString()
            android.util.Slog.e(r0, r6, r5)
            goto L8c
        L89:
            r0 = move-exception
            r2 = 0
        L8b:
        L8c:
            if (r2 == 0) goto L99
            android.util.ArrayMap<java.lang.String, java.lang.String> r0 = r13.mDefaultValues
            r0.clear()
            android.util.ArrayMap<java.lang.String, java.lang.String> r0 = r13.mDefaultValues
            r0.putAll(r2)
            return r4
        L99:
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
