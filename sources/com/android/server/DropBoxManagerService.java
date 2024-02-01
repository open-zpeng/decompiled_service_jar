package com.android.server;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IDropBoxManagerService;
import com.android.internal.util.ObjectUtils;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.PackageManagerService;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public final class DropBoxManagerService extends SystemService {
    private static final int DEFAULT_AGE_SECONDS = 259200;
    private static final int DEFAULT_MAX_FILES = 600;
    private static final int DEFAULT_MAX_FILES_LOWRAM = 300;
    private static final int DEFAULT_QUOTA_KB = 5120;
    private static final int DEFAULT_QUOTA_PERCENT = 10;
    private static final int DEFAULT_RESERVE_PERCENT = 10;
    private static final boolean PROFILE_DUMP = false;
    private static final int QUOTA_RESCAN_MILLIS = 5000;
    private static final String TAG = "DropBoxManagerService";
    private FileList mAllFiles;
    private int mBlockSize;
    private volatile boolean mBooted;
    private int mCachedQuotaBlocks;
    private long mCachedQuotaUptimeMillis;
    private final ContentResolver mContentResolver;
    private final File mDropBoxDir;
    private ArrayMap<String, FileList> mFilesByTag;
    private final DropBoxManagerBroadcastHandler mHandler;
    private long mLowPriorityRateLimitPeriod;
    private ArraySet<String> mLowPriorityTags;
    private int mMaxFiles;
    private final BroadcastReceiver mReceiver;
    private StatFs mStatFs;
    private final IDropBoxManagerService.Stub mStub;

    /* loaded from: classes.dex */
    private class ShellCmd extends ShellCommand {
        private ShellCmd() {
        }

        /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            PrintWriter pw = getOutPrintWriter();
            char c = 65535;
            try {
                switch (cmd.hashCode()) {
                    case -1412652367:
                        if (cmd.equals("restore-defaults")) {
                            c = 3;
                            break;
                        }
                        break;
                    case -529247831:
                        if (cmd.equals("add-low-priority")) {
                            c = 1;
                            break;
                        }
                        break;
                    case -444925274:
                        if (cmd.equals("remove-low-priority")) {
                            c = 2;
                            break;
                        }
                        break;
                    case 1936917209:
                        if (cmd.equals("set-rate-limit")) {
                            c = 0;
                            break;
                        }
                        break;
                }
                if (c == 0) {
                    String addedTag = getNextArgRequired();
                    long period = Long.parseLong(addedTag);
                    DropBoxManagerService.this.setLowPriorityRateLimit(period);
                } else if (c == 1) {
                    String addedTag2 = getNextArgRequired();
                    DropBoxManagerService.this.addLowPriorityTag(addedTag2);
                } else if (c == 2) {
                    String removeTag = getNextArgRequired();
                    DropBoxManagerService.this.removeLowPriorityTag(removeTag);
                } else if (c == 3) {
                    DropBoxManagerService.this.restoreDefaults();
                } else {
                    return handleDefaultCommands(cmd);
                }
            } catch (Exception e) {
                pw.println(e);
            }
            return 0;
        }

        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Dropbox manager service commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  set-rate-limit PERIOD");
            pw.println("    Sets low priority broadcast rate limit period to PERIOD ms");
            pw.println("  add-low-priority TAG");
            pw.println("    Add TAG to dropbox low priority list");
            pw.println("  remove-low-priority TAG");
            pw.println("    Remove TAG from dropbox low priority list");
            pw.println("  restore-defaults");
            pw.println("    restore dropbox settings to defaults");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class DropBoxManagerBroadcastHandler extends Handler {
        static final int MSG_SEND_BROADCAST = 1;
        static final int MSG_SEND_DEFERRED_BROADCAST = 2;
        @GuardedBy({"mLock"})
        private final ArrayMap<String, Intent> mDeferredMap;
        private final Object mLock;

        DropBoxManagerBroadcastHandler(Looper looper) {
            super(looper);
            this.mLock = new Object();
            this.mDeferredMap = new ArrayMap<>();
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            Intent deferredIntent;
            int i = msg.what;
            if (i == 1) {
                prepareAndSendBroadcast((Intent) msg.obj);
            } else if (i == 2) {
                synchronized (this.mLock) {
                    deferredIntent = this.mDeferredMap.remove((String) msg.obj);
                }
                if (deferredIntent != null) {
                    prepareAndSendBroadcast(deferredIntent);
                }
            }
        }

        private void prepareAndSendBroadcast(Intent intent) {
            if (!DropBoxManagerService.this.mBooted) {
                intent.addFlags(1073741824);
            }
            DropBoxManagerService.this.getContext().sendBroadcastAsUser(intent, UserHandle.SYSTEM, "android.permission.READ_LOGS");
        }

        private Intent createIntent(String tag, long time) {
            Intent dropboxIntent = new Intent("android.intent.action.DROPBOX_ENTRY_ADDED");
            dropboxIntent.putExtra("tag", tag);
            dropboxIntent.putExtra("time", time);
            return dropboxIntent;
        }

        public void sendBroadcast(String tag, long time) {
            sendMessage(obtainMessage(1, createIntent(tag, time)));
        }

        public void maybeDeferBroadcast(String tag, long time) {
            synchronized (this.mLock) {
                Intent intent = this.mDeferredMap.get(tag);
                if (intent == null) {
                    this.mDeferredMap.put(tag, createIntent(tag, time));
                    sendMessageDelayed(obtainMessage(2, tag), DropBoxManagerService.this.mLowPriorityRateLimitPeriod);
                    return;
                }
                intent.putExtra("time", time);
                int dropped = intent.getIntExtra("android.os.extra.DROPPED_COUNT", 0);
                intent.putExtra("android.os.extra.DROPPED_COUNT", dropped + 1);
            }
        }
    }

    public DropBoxManagerService(Context context) {
        this(context, new File("/data/system/dropbox"), FgThread.get().getLooper());
    }

    @VisibleForTesting
    public DropBoxManagerService(Context context, File path, Looper looper) {
        super(context);
        this.mAllFiles = null;
        this.mFilesByTag = null;
        this.mLowPriorityRateLimitPeriod = 0L;
        this.mLowPriorityTags = null;
        this.mStatFs = null;
        this.mBlockSize = 0;
        this.mCachedQuotaBlocks = 0;
        this.mCachedQuotaUptimeMillis = 0L;
        this.mBooted = false;
        this.mMaxFiles = -1;
        this.mReceiver = new BroadcastReceiver() { // from class: com.android.server.DropBoxManagerService.1
            /* JADX WARN: Type inference failed for: r0v1, types: [com.android.server.DropBoxManagerService$1$1] */
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                DropBoxManagerService.this.mCachedQuotaUptimeMillis = 0L;
                new Thread() { // from class: com.android.server.DropBoxManagerService.1.1
                    @Override // java.lang.Thread, java.lang.Runnable
                    public void run() {
                        try {
                            DropBoxManagerService.this.init();
                            DropBoxManagerService.this.trimToFit();
                        } catch (IOException e) {
                            Slog.e(DropBoxManagerService.TAG, "Can't init", e);
                        }
                    }
                }.start();
            }
        };
        this.mStub = new IDropBoxManagerService.Stub() { // from class: com.android.server.DropBoxManagerService.2
            public void add(DropBoxManager.Entry entry) {
                DropBoxManagerService.this.add(entry);
            }

            public boolean isTagEnabled(String tag) {
                return DropBoxManagerService.this.isTagEnabled(tag);
            }

            public DropBoxManager.Entry getNextEntry(String tag, long millis, String callingPackage) {
                return DropBoxManagerService.this.getNextEntry(tag, millis, callingPackage);
            }

            public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
                DropBoxManagerService.this.dump(fd, pw, args);
            }

            /* JADX WARN: Multi-variable type inference failed */
            public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err, String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                new ShellCmd().exec(this, in, out, err, args, callback, resultReceiver);
            }
        };
        this.mDropBoxDir = path;
        this.mContentResolver = getContext().getContentResolver();
        this.mHandler = new DropBoxManagerBroadcastHandler(looper);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("dropbox", this.mStub);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase != 500) {
            if (phase == 1000) {
                this.mBooted = true;
                return;
            }
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.DEVICE_STORAGE_LOW");
        getContext().registerReceiver(this.mReceiver, filter);
        this.mContentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, new ContentObserver(new Handler()) { // from class: com.android.server.DropBoxManagerService.3
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                DropBoxManagerService.this.mReceiver.onReceive(DropBoxManagerService.this.getContext(), null);
            }
        });
        getLowPriorityResourceConfigs();
    }

    public IDropBoxManagerService getServiceStub() {
        return this.mStub;
    }

    /* JADX WARN: Not initialized variable reg: 17, insn: 0x0187: MOVE  (r3 I:??[OBJECT, ARRAY]) = (r17 I:??[OBJECT, ARRAY] A[D('temp' java.io.File)]), block:B:63:0x0187 */
    /* JADX WARN: Not initialized variable reg: 17, insn: 0x018b: MOVE  (r3 I:??[OBJECT, ARRAY]) = (r17 I:??[OBJECT, ARRAY] A[D('temp' java.io.File)]), block:B:65:0x018b */
    public void add(DropBoxManager.Entry entry) {
        int flags;
        File temp;
        File temp2;
        long max;
        long lastTrim;
        File temp3 = null;
        InputStream input = null;
        String tag = entry.getTag();
        try {
            flags = entry.getFlags();
            Slog.i(TAG, "add tag=" + tag + " isTagEnabled=" + isTagEnabled(tag) + " flags=0x" + Integer.toHexString(flags));
            try {
            } catch (IOException e) {
                e = e;
                temp3 = temp2;
            } catch (Throwable th) {
                th = th;
                temp3 = temp;
            }
        } catch (IOException e2) {
            e = e2;
        } catch (Throwable th2) {
            th = th2;
        }
        if ((flags & 1) != 0) {
            throw new IllegalArgumentException();
        }
        init();
        if (!isTagEnabled(tag)) {
            IoUtils.closeQuietly((AutoCloseable) null);
            IoUtils.closeQuietly((AutoCloseable) null);
            entry.close();
            if (0 != 0) {
                temp3.delete();
                return;
            }
            return;
        }
        long max2 = trimToFit();
        long max3 = System.currentTimeMillis();
        byte[] buffer = new byte[this.mBlockSize];
        input = entry.getInputStream();
        int read = 0;
        while (read < buffer.length) {
            try {
                try {
                    int n = input.read(buffer, read, buffer.length - read);
                    if (n <= 0) {
                        break;
                    }
                    read += n;
                } catch (Throwable th3) {
                    th = th3;
                    IoUtils.closeQuietly((AutoCloseable) null);
                    IoUtils.closeQuietly(input);
                    entry.close();
                    if (temp3 != null) {
                        temp3.delete();
                    }
                    throw th;
                }
            } catch (IOException e3) {
                e = e3;
                Slog.e(TAG, "Can't write: " + tag, e);
                IoUtils.closeQuietly((AutoCloseable) null);
                IoUtils.closeQuietly(input);
                entry.close();
                if (temp3 == null) {
                    return;
                }
                temp3.delete();
            }
        }
        File file = this.mDropBoxDir;
        StringBuilder sb = new StringBuilder();
        sb.append("drop");
        long max4 = max2;
        long max5 = Thread.currentThread().getId();
        sb.append(max5);
        sb.append(".tmp");
        File temp4 = new File(file, sb.toString());
        int bufferSize = this.mBlockSize;
        if (bufferSize > 4096) {
            bufferSize = 4096;
        }
        if (bufferSize < 512) {
            bufferSize = 512;
        }
        FileOutputStream foutput = new FileOutputStream(temp4);
        OutputStream output = new BufferedOutputStream(foutput, bufferSize);
        if (read <= buffer.length && read >= 512 && (flags & 4) == 0) {
            output = new GZIPOutputStream(output);
            flags |= 4;
        }
        while (true) {
            output.write(buffer, 0, read);
            long now = System.currentTimeMillis();
            if (now - max3 > 30000) {
                long max6 = trimToFit();
                lastTrim = max6;
                max = now;
            } else {
                max = max3;
                lastTrim = max4;
            }
            read = input.read(buffer);
            if (read <= 0) {
                FileUtils.sync(foutput);
                output.close();
                output = null;
            } else {
                output.flush();
            }
            long len = temp4.length();
            if (len > lastTrim) {
                Slog.w(TAG, "Dropping: " + tag + " (" + temp4.length() + " > " + lastTrim + " bytes)");
                temp4.delete();
                temp4 = null;
                break;
            }
            int bufferSize2 = bufferSize;
            byte[] buffer2 = buffer;
            FileOutputStream foutput2 = foutput;
            if (read <= 0) {
                break;
            }
            buffer = buffer2;
            max4 = lastTrim;
            max3 = max;
            bufferSize = bufferSize2;
            foutput = foutput2;
        }
        long time = createEntry(temp4, tag, flags);
        temp3 = null;
        if (this.mLowPriorityTags == null || !this.mLowPriorityTags.contains(tag)) {
            this.mHandler.sendBroadcast(tag, time);
        } else {
            this.mHandler.maybeDeferBroadcast(tag, time);
        }
        IoUtils.closeQuietly(output);
        IoUtils.closeQuietly(input);
        entry.close();
        if (0 == 0) {
            return;
        }
        temp3.delete();
    }

    public boolean isTagEnabled(String tag) {
        long token = Binder.clearCallingIdentity();
        try {
            ContentResolver contentResolver = this.mContentResolver;
            return !"disabled".equals(Settings.Global.getString(contentResolver, "dropbox:" + tag));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean checkPermission(int callingUid, String callingPackage) {
        getContext().enforceCallingOrSelfPermission("android.permission.READ_LOGS", TAG);
        int noteOp = ((AppOpsManager) getContext().getSystemService(AppOpsManager.class)).noteOp(43, callingUid, callingPackage);
        if (noteOp != 0) {
            if (noteOp == 3) {
                getContext().enforceCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS", TAG);
                return true;
            }
            return false;
        }
        return true;
    }

    public synchronized DropBoxManager.Entry getNextEntry(String tag, long millis, String callingPackage) {
        if (checkPermission(Binder.getCallingUid(), callingPackage)) {
            try {
                init();
                FileList list = tag == null ? this.mAllFiles : this.mFilesByTag.get(tag);
                if (list == null) {
                    return null;
                }
                for (EntryFile entry : list.contents.tailSet(new EntryFile(millis + 1))) {
                    if (entry.tag != null) {
                        if ((entry.flags & 1) != 0) {
                            return new DropBoxManager.Entry(entry.tag, entry.timestampMillis);
                        }
                        File file = entry.getFile(this.mDropBoxDir);
                        try {
                            return new DropBoxManager.Entry(entry.tag, entry.timestampMillis, file, entry.flags);
                        } catch (IOException e) {
                            Slog.wtf(TAG, "Can't read: " + file, e);
                        }
                    }
                }
                return null;
            } catch (IOException e2) {
                Slog.e(TAG, "Can't init", e2);
                return null;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void setLowPriorityRateLimit(long period) {
        this.mLowPriorityRateLimitPeriod = period;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void addLowPriorityTag(String tag) {
        this.mLowPriorityTags.add(tag);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void removeLowPriorityTag(String tag) {
        this.mLowPriorityTags.remove(tag);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void restoreDefaults() {
        getLowPriorityResourceConfigs();
    }

    /* JADX WARN: Removed duplicated region for block: B:180:0x0386 A[Catch: all -> 0x0407, TRY_ENTER, TRY_LEAVE, TryCatch #4 {, blocks: (B:4:0x0007, B:8:0x0015, B:9:0x0019, B:11:0x002b, B:13:0x002e, B:15:0x0038, B:18:0x0044, B:20:0x004e, B:23:0x0059, B:25:0x0063, B:28:0x006e, B:30:0x0078, B:31:0x0088, B:32:0x008e, B:38:0x00b7, B:40:0x00ff, B:41:0x0108, B:43:0x010e, B:44:0x011d, B:45:0x0122, B:46:0x0139, B:48:0x013f, B:52:0x0158, B:54:0x0168, B:59:0x0178, B:63:0x0186, B:65:0x018a, B:66:0x018f, B:70:0x01a0, B:72:0x01ac, B:73:0x01bc, B:75:0x01c2, B:76:0x01d2, B:78:0x01dd, B:79:0x01e2, B:83:0x01ee, B:86:0x0209, B:91:0x0222, B:197:0x03ac, B:151:0x02ff, B:153:0x0304, B:180:0x0386, B:182:0x038b, B:189:0x0399, B:191:0x039e, B:194:0x03a3, B:89:0x0211, B:90:0x0216, B:69:0x019e, B:202:0x03c8, B:204:0x03cf, B:209:0x03de, B:207:0x03d4, B:208:0x03d9, B:214:0x03ea), top: B:222:0x0007, inners: #13 }] */
    /* JADX WARN: Removed duplicated region for block: B:189:0x0399 A[Catch: all -> 0x0407, TRY_ENTER, TRY_LEAVE, TryCatch #4 {, blocks: (B:4:0x0007, B:8:0x0015, B:9:0x0019, B:11:0x002b, B:13:0x002e, B:15:0x0038, B:18:0x0044, B:20:0x004e, B:23:0x0059, B:25:0x0063, B:28:0x006e, B:30:0x0078, B:31:0x0088, B:32:0x008e, B:38:0x00b7, B:40:0x00ff, B:41:0x0108, B:43:0x010e, B:44:0x011d, B:45:0x0122, B:46:0x0139, B:48:0x013f, B:52:0x0158, B:54:0x0168, B:59:0x0178, B:63:0x0186, B:65:0x018a, B:66:0x018f, B:70:0x01a0, B:72:0x01ac, B:73:0x01bc, B:75:0x01c2, B:76:0x01d2, B:78:0x01dd, B:79:0x01e2, B:83:0x01ee, B:86:0x0209, B:91:0x0222, B:197:0x03ac, B:151:0x02ff, B:153:0x0304, B:180:0x0386, B:182:0x038b, B:189:0x0399, B:191:0x039e, B:194:0x03a3, B:89:0x0211, B:90:0x0216, B:69:0x019e, B:202:0x03c8, B:204:0x03cf, B:209:0x03de, B:207:0x03d4, B:208:0x03d9, B:214:0x03ea), top: B:222:0x0007, inners: #13 }] */
    /* JADX WARN: Removed duplicated region for block: B:197:0x03ac A[Catch: all -> 0x0407, TryCatch #4 {, blocks: (B:4:0x0007, B:8:0x0015, B:9:0x0019, B:11:0x002b, B:13:0x002e, B:15:0x0038, B:18:0x0044, B:20:0x004e, B:23:0x0059, B:25:0x0063, B:28:0x006e, B:30:0x0078, B:31:0x0088, B:32:0x008e, B:38:0x00b7, B:40:0x00ff, B:41:0x0108, B:43:0x010e, B:44:0x011d, B:45:0x0122, B:46:0x0139, B:48:0x013f, B:52:0x0158, B:54:0x0168, B:59:0x0178, B:63:0x0186, B:65:0x018a, B:66:0x018f, B:70:0x01a0, B:72:0x01ac, B:73:0x01bc, B:75:0x01c2, B:76:0x01d2, B:78:0x01dd, B:79:0x01e2, B:83:0x01ee, B:86:0x0209, B:91:0x0222, B:197:0x03ac, B:151:0x02ff, B:153:0x0304, B:180:0x0386, B:182:0x038b, B:189:0x0399, B:191:0x039e, B:194:0x03a3, B:89:0x0211, B:90:0x0216, B:69:0x019e, B:202:0x03c8, B:204:0x03cf, B:209:0x03de, B:207:0x03d4, B:208:0x03d9, B:214:0x03ea), top: B:222:0x0007, inners: #13 }] */
    /* JADX WARN: Removed duplicated region for block: B:223:0x038b A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:229:0x039e A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public synchronized void dump(java.io.FileDescriptor r31, java.io.PrintWriter r32, java.lang.String[] r33) {
        /*
            Method dump skipped, instructions count: 1034
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.DropBoxManagerService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class FileList implements Comparable<FileList> {
        public int blocks;
        public final TreeSet<EntryFile> contents;

        private FileList() {
            this.blocks = 0;
            this.contents = new TreeSet<>();
        }

        @Override // java.lang.Comparable
        public final int compareTo(FileList o) {
            int i = this.blocks;
            int i2 = o.blocks;
            if (i != i2) {
                return i2 - i;
            }
            if (this == o) {
                return 0;
            }
            if (hashCode() < o.hashCode()) {
                return -1;
            }
            return hashCode() > o.hashCode() ? 1 : 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static final class EntryFile implements Comparable<EntryFile> {
        public final int blocks;
        public final int flags;
        public final String tag;
        public final long timestampMillis;

        @Override // java.lang.Comparable
        public final int compareTo(EntryFile o) {
            int comp = Long.compare(this.timestampMillis, o.timestampMillis);
            if (comp != 0) {
                return comp;
            }
            int comp2 = ObjectUtils.compare(this.tag, o.tag);
            if (comp2 != 0) {
                return comp2;
            }
            int comp3 = Integer.compare(this.flags, o.flags);
            return comp3 != 0 ? comp3 : Integer.compare(hashCode(), o.hashCode());
        }

        public EntryFile(File temp, File dir, String tag, long timestampMillis, int flags, int blockSize) throws IOException {
            if ((flags & 1) != 0) {
                throw new IllegalArgumentException();
            }
            this.tag = TextUtils.safeIntern(tag);
            this.timestampMillis = timestampMillis;
            this.flags = flags;
            File file = getFile(dir);
            if (!temp.renameTo(file)) {
                throw new IOException("Can't rename " + temp + " to " + file);
            }
            this.blocks = (int) (((file.length() + blockSize) - 1) / blockSize);
        }

        public EntryFile(File dir, String tag, long timestampMillis) throws IOException {
            this.tag = TextUtils.safeIntern(tag);
            this.timestampMillis = timestampMillis;
            this.flags = 1;
            this.blocks = 0;
            new FileOutputStream(getFile(dir)).close();
        }

        public EntryFile(File file, int blockSize) {
            boolean parseFailure = false;
            String name = file.getName();
            int flags = 0;
            String tag = null;
            long millis = 0;
            int at = name.lastIndexOf(64);
            if (at < 0) {
                parseFailure = true;
            } else {
                tag = Uri.decode(name.substring(0, at));
                if (name.endsWith(PackageManagerService.COMPRESSED_EXTENSION)) {
                    flags = 0 | 4;
                    name = name.substring(0, name.length() - 3);
                }
                if (name.endsWith(".lost")) {
                    flags |= 1;
                    name = name.substring(at + 1, name.length() - 5);
                } else if (name.endsWith(".txt")) {
                    flags |= 2;
                    name = name.substring(at + 1, name.length() - 4);
                } else if (name.endsWith(".dat")) {
                    name = name.substring(at + 1, name.length() - 4);
                } else {
                    parseFailure = true;
                }
                if (!parseFailure) {
                    try {
                        millis = Long.parseLong(name);
                    } catch (NumberFormatException e) {
                        parseFailure = true;
                    }
                }
            }
            if (parseFailure) {
                Slog.wtf(DropBoxManagerService.TAG, "Invalid filename: " + file);
                file.delete();
                this.tag = null;
                this.flags = 1;
                this.timestampMillis = 0L;
                this.blocks = 0;
                return;
            }
            this.blocks = (int) (((file.length() + blockSize) - 1) / blockSize);
            this.tag = TextUtils.safeIntern(tag);
            this.flags = flags;
            this.timestampMillis = millis;
        }

        public EntryFile(long millis) {
            this.tag = null;
            this.timestampMillis = millis;
            this.flags = 1;
            this.blocks = 0;
        }

        public boolean hasFile() {
            return this.tag != null;
        }

        private String getExtension() {
            if ((this.flags & 1) != 0) {
                return ".lost";
            }
            StringBuilder sb = new StringBuilder();
            sb.append((this.flags & 2) != 0 ? ".txt" : ".dat");
            sb.append((this.flags & 4) != 0 ? PackageManagerService.COMPRESSED_EXTENSION : "");
            return sb.toString();
        }

        public String getFilename() {
            if (hasFile()) {
                return Uri.encode(this.tag) + "@" + this.timestampMillis + getExtension();
            }
            return null;
        }

        public File getFile(File dir) {
            if (hasFile()) {
                return new File(dir, getFilename());
            }
            return null;
        }

        public void deleteFile(File dir) {
            if (hasFile()) {
                getFile(dir).delete();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized void init() throws IOException {
        if (this.mStatFs == null) {
            if (!this.mDropBoxDir.isDirectory() && !this.mDropBoxDir.mkdirs()) {
                throw new IOException("Can't mkdir: " + this.mDropBoxDir);
            }
            try {
                this.mStatFs = new StatFs(this.mDropBoxDir.getPath());
                this.mBlockSize = this.mStatFs.getBlockSize();
            } catch (IllegalArgumentException e) {
                throw new IOException("Can't statfs: " + this.mDropBoxDir);
            }
        }
        if (this.mAllFiles == null) {
            File[] files = this.mDropBoxDir.listFiles();
            if (files == null) {
                throw new IOException("Can't list files: " + this.mDropBoxDir);
            }
            this.mAllFiles = new FileList();
            this.mFilesByTag = new ArrayMap<>();
            for (File file : files) {
                if (file.getName().endsWith(".tmp")) {
                    Slog.i(TAG, "Cleaning temp file: " + file);
                    file.delete();
                } else {
                    EntryFile entry = new EntryFile(file, this.mBlockSize);
                    if (entry.hasFile()) {
                        enrollEntry(entry);
                    }
                }
            }
        }
    }

    private synchronized void enrollEntry(EntryFile entry) {
        this.mAllFiles.contents.add(entry);
        this.mAllFiles.blocks += entry.blocks;
        if (entry.hasFile() && entry.blocks > 0) {
            FileList tagFiles = this.mFilesByTag.get(entry.tag);
            if (tagFiles == null) {
                tagFiles = new FileList();
                this.mFilesByTag.put(TextUtils.safeIntern(entry.tag), tagFiles);
            }
            tagFiles.contents.add(entry);
            tagFiles.blocks += entry.blocks;
        }
    }

    private synchronized long createEntry(File temp, String tag, int flags) throws IOException {
        EntryFile[] future;
        long t;
        SortedSet<EntryFile> tail;
        long j;
        String tag2 = tag;
        synchronized (this) {
            long t2 = System.currentTimeMillis();
            SortedSet<EntryFile> tail2 = this.mAllFiles.contents.tailSet(new EntryFile(JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY + t2));
            if (tail2.isEmpty()) {
                future = null;
            } else {
                EntryFile[] future2 = (EntryFile[]) tail2.toArray(new EntryFile[tail2.size()]);
                tail2.clear();
                future = future2;
            }
            long j2 = 1;
            if (!this.mAllFiles.contents.isEmpty()) {
                t2 = Math.max(t2, this.mAllFiles.contents.last().timestampMillis + 1);
            }
            if (future != null) {
                int length = future.length;
                t = t2;
                int i = 0;
                while (i < length) {
                    EntryFile late = future[i];
                    this.mAllFiles.blocks -= late.blocks;
                    FileList tagFiles = this.mFilesByTag.get(late.tag);
                    if (tagFiles != null && tagFiles.contents.remove(late)) {
                        tagFiles.blocks -= late.blocks;
                    }
                    if ((late.flags & 1) == 0) {
                        tail = tail2;
                        enrollEntry(new EntryFile(late.getFile(this.mDropBoxDir), this.mDropBoxDir, late.tag, t, late.flags, this.mBlockSize));
                        t += j2;
                        j = 1;
                    } else {
                        tail = tail2;
                        j = 1;
                        enrollEntry(new EntryFile(this.mDropBoxDir, late.tag, t));
                        t++;
                    }
                    i++;
                    j2 = j;
                    tail2 = tail;
                }
            } else {
                t = t2;
            }
            if (!tag.isEmpty() && tag2.contains("_xiaopengbughunter_")) {
                int index = tag2.lastIndexOf(95);
                try {
                    t = Long.parseLong(tag2.substring(index + 1));
                    tag2 = tag2.substring(0, index);
                } catch (Exception e) {
                    Slog.w(TAG, "parse fail: " + e.getMessage());
                }
            }
            if (temp == null) {
                enrollEntry(new EntryFile(this.mDropBoxDir, tag2, t));
            } else {
                enrollEntry(new EntryFile(temp, this.mDropBoxDir, tag2, t, flags, this.mBlockSize));
            }
        }
        return t;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public synchronized long trimToFit() throws IOException {
        int ageSeconds;
        long cutoffMillis;
        int ageSeconds2 = Settings.Global.getInt(this.mContentResolver, "dropbox_age_seconds", DEFAULT_AGE_SECONDS);
        this.mMaxFiles = Settings.Global.getInt(this.mContentResolver, "dropbox_max_files", ActivityManager.isLowRamDeviceStatic() ? 300 : 600);
        if (Build.IS_USER) {
            this.mMaxFiles = 300;
        }
        long cutoffMillis2 = System.currentTimeMillis() - (ageSeconds2 * 1000);
        while (!this.mAllFiles.contents.isEmpty()) {
            EntryFile entry = this.mAllFiles.contents.first();
            if (entry.timestampMillis > cutoffMillis2 && this.mAllFiles.contents.size() < this.mMaxFiles) {
                break;
            }
            FileList tag = this.mFilesByTag.get(entry.tag);
            if (tag != null && tag.contents.remove(entry)) {
                tag.blocks -= entry.blocks;
            }
            if (this.mAllFiles.contents.remove(entry)) {
                this.mAllFiles.blocks -= entry.blocks;
            }
            entry.deleteFile(this.mDropBoxDir);
        }
        long uptimeMillis = SystemClock.uptimeMillis();
        if (uptimeMillis > this.mCachedQuotaUptimeMillis + 5000) {
            int quotaPercent = Settings.Global.getInt(this.mContentResolver, "dropbox_quota_percent", 10);
            int reservePercent = Settings.Global.getInt(this.mContentResolver, "dropbox_reserve_percent", 10);
            int quotaKb = Settings.Global.getInt(this.mContentResolver, "dropbox_quota_kb", DEFAULT_QUOTA_KB);
            String dirPath = this.mDropBoxDir.getPath();
            try {
                this.mStatFs.restat(dirPath);
                int available = this.mStatFs.getAvailableBlocks();
                int nonreserved = available - ((this.mStatFs.getBlockCount() * reservePercent) / 100);
                int maximum = (quotaKb * 1024) / this.mBlockSize;
                this.mCachedQuotaBlocks = Math.min(maximum, Math.max(0, (nonreserved * quotaPercent) / 100));
                this.mCachedQuotaUptimeMillis = uptimeMillis;
            } catch (IllegalArgumentException e) {
                throw new IOException("Can't restat: " + this.mDropBoxDir);
            }
        }
        if (this.mAllFiles.blocks > this.mCachedQuotaBlocks) {
            int unsqueezed = this.mAllFiles.blocks;
            int squeezed = 0;
            TreeSet<FileList> tags = new TreeSet<>(this.mFilesByTag.values());
            Iterator<FileList> it = tags.iterator();
            int unsqueezed2 = unsqueezed;
            while (it.hasNext()) {
                FileList tag2 = it.next();
                if (squeezed > 0 && tag2.blocks <= (this.mCachedQuotaBlocks - unsqueezed2) / squeezed) {
                    break;
                }
                unsqueezed2 -= tag2.blocks;
                squeezed++;
            }
            int tagQuota = (this.mCachedQuotaBlocks - unsqueezed2) / squeezed;
            Iterator<FileList> it2 = tags.iterator();
            while (it2.hasNext()) {
                FileList tag3 = it2.next();
                if (this.mAllFiles.blocks < this.mCachedQuotaBlocks) {
                    break;
                }
                while (tag3.blocks > tagQuota && !tag3.contents.isEmpty()) {
                    EntryFile entry2 = tag3.contents.first();
                    if (tag3.contents.remove(entry2)) {
                        tag3.blocks -= entry2.blocks;
                    }
                    if (this.mAllFiles.contents.remove(entry2)) {
                        this.mAllFiles.blocks -= entry2.blocks;
                    }
                    try {
                        entry2.deleteFile(this.mDropBoxDir);
                        ageSeconds = ageSeconds2;
                        cutoffMillis = cutoffMillis2;
                        try {
                            enrollEntry(new EntryFile(this.mDropBoxDir, entry2.tag, entry2.timestampMillis));
                        } catch (IOException e2) {
                            e = e2;
                            Slog.e(TAG, "Can't write tombstone file", e);
                            ageSeconds2 = ageSeconds;
                            cutoffMillis2 = cutoffMillis;
                        }
                    } catch (IOException e3) {
                        e = e3;
                        ageSeconds = ageSeconds2;
                        cutoffMillis = cutoffMillis2;
                    }
                    ageSeconds2 = ageSeconds;
                    cutoffMillis2 = cutoffMillis;
                }
                ageSeconds2 = ageSeconds2;
                cutoffMillis2 = cutoffMillis2;
            }
        }
        return this.mCachedQuotaBlocks * this.mBlockSize;
    }

    private void getLowPriorityResourceConfigs() {
        this.mLowPriorityRateLimitPeriod = Resources.getSystem().getInteger(17694806);
        String[] lowPrioritytags = Resources.getSystem().getStringArray(17236027);
        int size = lowPrioritytags.length;
        if (size == 0) {
            this.mLowPriorityTags = null;
            return;
        }
        this.mLowPriorityTags = new ArraySet<>(size);
        for (String str : lowPrioritytags) {
            this.mLowPriorityTags.add(str);
        }
    }
}
