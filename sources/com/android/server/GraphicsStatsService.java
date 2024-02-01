package com.android.server;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.MemoryFile;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Log;
import android.view.IGraphicsStats;
import android.view.IGraphicsStatsCallback;
import com.android.internal.util.DumpUtils;
import com.android.server.job.controllers.JobStatus;
import com.android.server.utils.PriorityDump;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.TimeZone;

/* loaded from: classes.dex */
public class GraphicsStatsService extends IGraphicsStats.Stub {
    private static final int DELETE_OLD = 2;
    public static final String GRAPHICS_STATS_SERVICE = "graphicsstats";
    private static final int SAVE_BUFFER = 1;
    private static final String TAG = "GraphicsStatsService";
    private final AlarmManager mAlarmManager;
    private final AppOpsManager mAppOps;
    private final Context mContext;
    private File mGraphicsStatsDir;
    private Handler mWriteOutHandler;
    private final int ASHMEM_SIZE = nGetAshmemSize();
    private final byte[] ZERO_DATA = new byte[this.ASHMEM_SIZE];
    private final Object mLock = new Object();
    private ArrayList<ActiveBuffer> mActive = new ArrayList<>();
    private final Object mFileAccessLock = new Object();
    private boolean mRotateIsScheduled = false;

    private static native void nAddToDump(long j, String str);

    private static native void nAddToDump(long j, String str, String str2, long j2, long j3, long j4, byte[] bArr);

    private static native long nCreateDump(int i, boolean z);

    private static native void nFinishDump(long j);

    private static native int nGetAshmemSize();

    private static native void nSaveBuffer(String str, String str2, long j, long j2, long j3, byte[] bArr);

    public GraphicsStatsService(Context context) {
        this.mContext = context;
        this.mAppOps = (AppOpsManager) context.getSystemService(AppOpsManager.class);
        this.mAlarmManager = (AlarmManager) context.getSystemService(AlarmManager.class);
        File systemDataDir = new File(Environment.getDataDirectory(), "system");
        this.mGraphicsStatsDir = new File(systemDataDir, GRAPHICS_STATS_SERVICE);
        this.mGraphicsStatsDir.mkdirs();
        if (!this.mGraphicsStatsDir.exists()) {
            throw new IllegalStateException("Graphics stats directory does not exist: " + this.mGraphicsStatsDir.getAbsolutePath());
        }
        HandlerThread bgthread = new HandlerThread("GraphicsStats-disk", 10);
        bgthread.start();
        this.mWriteOutHandler = new Handler(bgthread.getLooper(), new Handler.Callback() { // from class: com.android.server.GraphicsStatsService.1
            @Override // android.os.Handler.Callback
            public boolean handleMessage(Message msg) {
                int i = msg.what;
                if (i == 1) {
                    GraphicsStatsService.this.saveBuffer((HistoricalBuffer) msg.obj);
                } else if (i == 2) {
                    GraphicsStatsService.this.deleteOldBuffers();
                }
                return true;
            }
        });
    }

    private void scheduleRotateLocked() {
        if (this.mRotateIsScheduled) {
            return;
        }
        this.mRotateIsScheduled = true;
        Calendar calendar = normalizeDate(System.currentTimeMillis());
        calendar.add(5, 1);
        this.mAlarmManager.setExact(1, calendar.getTimeInMillis(), TAG, new AlarmManager.OnAlarmListener() { // from class: com.android.server.-$$Lambda$GraphicsStatsService$2EDVu98hsJvSwNgKvijVLSR3IrQ
            @Override // android.app.AlarmManager.OnAlarmListener
            public final void onAlarm() {
                GraphicsStatsService.this.onAlarm();
            }
        }, this.mWriteOutHandler);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onAlarm() {
        ActiveBuffer[] activeCopy;
        synchronized (this.mLock) {
            this.mRotateIsScheduled = false;
            scheduleRotateLocked();
            activeCopy = (ActiveBuffer[]) this.mActive.toArray(new ActiveBuffer[0]);
        }
        for (ActiveBuffer active : activeCopy) {
            try {
                active.mCallback.onRotateGraphicsStatsBuffer();
            } catch (RemoteException e) {
                Log.w(TAG, String.format("Failed to notify '%s' (pid=%d) to rotate buffers", active.mInfo.packageName, Integer.valueOf(active.mPid)), e);
            }
        }
        this.mWriteOutHandler.sendEmptyMessageDelayed(2, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
    }

    public ParcelFileDescriptor requestBufferForProcess(String packageName, IGraphicsStatsCallback token) throws RemoteException {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            try {
                this.mAppOps.checkPackage(uid, packageName);
                PackageInfo info = this.mContext.getPackageManager().getPackageInfoAsUser(packageName, 0, UserHandle.getUserId(uid));
                try {
                    synchronized (this.mLock) {
                        try {
                            ParcelFileDescriptor pfd = requestBufferForProcessLocked(token, uid, pid, packageName, info.getLongVersionCode());
                            return pfd;
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (PackageManager.NameNotFoundException e) {
                throw new RemoteException("Unable to find package: '" + packageName + "'");
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    private ParcelFileDescriptor getPfd(MemoryFile file) {
        try {
            if (!file.getFileDescriptor().valid()) {
                throw new IllegalStateException("Invalid file descriptor");
            }
            return new ParcelFileDescriptor(file.getFileDescriptor());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to get PFD from memory file", ex);
        }
    }

    private ParcelFileDescriptor requestBufferForProcessLocked(IGraphicsStatsCallback token, int uid, int pid, String packageName, long versionCode) throws RemoteException {
        ActiveBuffer buffer = fetchActiveBuffersLocked(token, uid, pid, packageName, versionCode);
        scheduleRotateLocked();
        return getPfd(buffer.mProcessBuffer);
    }

    private Calendar normalizeDate(long timestamp) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(timestamp);
        calendar.set(11, 0);
        calendar.set(12, 0);
        calendar.set(13, 0);
        calendar.set(14, 0);
        return calendar;
    }

    private File pathForApp(BufferInfo info) {
        String subPath = String.format("%d/%s/%d/total", Long.valueOf(normalizeDate(info.startTime).getTimeInMillis()), info.packageName, Long.valueOf(info.versionCode));
        return new File(this.mGraphicsStatsDir, subPath);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveBuffer(HistoricalBuffer buffer) {
        if (Trace.isTagEnabled(524288L)) {
            Trace.traceBegin(524288L, "saving graphicsstats for " + buffer.mInfo.packageName);
        }
        synchronized (this.mFileAccessLock) {
            File path = pathForApp(buffer.mInfo);
            File parent = path.getParentFile();
            parent.mkdirs();
            if (!parent.exists()) {
                Log.w(TAG, "Unable to create path: '" + parent.getAbsolutePath() + "'");
                return;
            }
            nSaveBuffer(path.getAbsolutePath(), buffer.mInfo.packageName, buffer.mInfo.versionCode, buffer.mInfo.startTime, buffer.mInfo.endTime, buffer.mData);
            Trace.traceEnd(524288L);
        }
    }

    private void deleteRecursiveLocked(File file) {
        File[] listFiles;
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                deleteRecursiveLocked(child);
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete '" + file.getAbsolutePath() + "'!");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void deleteOldBuffers() {
        Trace.traceBegin(524288L, "deleting old graphicsstats buffers");
        synchronized (this.mFileAccessLock) {
            File[] files = this.mGraphicsStatsDir.listFiles();
            if (files != null && files.length > 3) {
                long[] sortedDates = new long[files.length];
                for (int i = 0; i < files.length; i++) {
                    try {
                        sortedDates[i] = Long.parseLong(files[i].getName());
                    } catch (NumberFormatException e) {
                    }
                }
                int i2 = sortedDates.length;
                if (i2 <= 3) {
                    return;
                }
                Arrays.sort(sortedDates);
                for (int i3 = 0; i3 < sortedDates.length - 3; i3++) {
                    deleteRecursiveLocked(new File(this.mGraphicsStatsDir, Long.toString(sortedDates[i3])));
                }
                Trace.traceEnd(524288L);
            }
        }
    }

    private void addToSaveQueue(ActiveBuffer buffer) {
        try {
            HistoricalBuffer data = new HistoricalBuffer(buffer);
            Message.obtain(this.mWriteOutHandler, 1, data).sendToTarget();
        } catch (IOException e) {
            Log.w(TAG, "Failed to copy graphicsstats from " + buffer.mInfo.packageName, e);
        }
        buffer.closeAllBuffers();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void processDied(ActiveBuffer buffer) {
        synchronized (this.mLock) {
            this.mActive.remove(buffer);
        }
        addToSaveQueue(buffer);
    }

    /* JADX WARN: Code restructure failed: missing block: B:15:0x0040, code lost:
        r0 = new com.android.server.GraphicsStatsService.ActiveBuffer(r15, r16, r17, r18, r19, r20);
        r15.mActive.add(r0);
     */
    /* JADX WARN: Code restructure failed: missing block: B:16:0x0056, code lost:
        return r0;
     */
    /* JADX WARN: Code restructure failed: missing block: B:19:0x005f, code lost:
        throw new android.os.RemoteException("Failed to allocate space");
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.GraphicsStatsService.ActiveBuffer fetchActiveBuffersLocked(android.view.IGraphicsStatsCallback r16, int r17, int r18, java.lang.String r19, long r20) throws android.os.RemoteException {
        /*
            r15 = this;
            r9 = r15
            java.util.ArrayList<com.android.server.GraphicsStatsService$ActiveBuffer> r0 = r9.mActive
            int r10 = r0.size()
            long r0 = java.lang.System.currentTimeMillis()
            java.util.Calendar r0 = r15.normalizeDate(r0)
            long r11 = r0.getTimeInMillis()
            r0 = 0
        L14:
            if (r0 >= r10) goto L3c
            java.util.ArrayList<com.android.server.GraphicsStatsService$ActiveBuffer> r1 = r9.mActive
            java.lang.Object r1 = r1.get(r0)
            com.android.server.GraphicsStatsService$ActiveBuffer r1 = (com.android.server.GraphicsStatsService.ActiveBuffer) r1
            int r2 = r1.mPid
            r13 = r18
            if (r2 != r13) goto L37
            int r2 = r1.mUid
            r14 = r17
            if (r2 != r14) goto L39
            com.android.server.GraphicsStatsService$BufferInfo r2 = r1.mInfo
            long r2 = r2.startTime
            int r2 = (r2 > r11 ? 1 : (r2 == r11 ? 0 : -1))
            if (r2 >= 0) goto L36
            r1.binderDied()
            goto L40
        L36:
            return r1
        L37:
            r14 = r17
        L39:
            int r0 = r0 + 1
            goto L14
        L3c:
            r14 = r17
            r13 = r18
        L40:
            com.android.server.GraphicsStatsService$ActiveBuffer r0 = new com.android.server.GraphicsStatsService$ActiveBuffer     // Catch: java.io.IOException -> L57
            r1 = r0
            r2 = r15
            r3 = r16
            r4 = r17
            r5 = r18
            r6 = r19
            r7 = r20
            r1.<init>(r3, r4, r5, r6, r7)     // Catch: java.io.IOException -> L57
            java.util.ArrayList<com.android.server.GraphicsStatsService$ActiveBuffer> r1 = r9.mActive     // Catch: java.io.IOException -> L57
            r1.add(r0)     // Catch: java.io.IOException -> L57
            return r0
        L57:
            r0 = move-exception
            android.os.RemoteException r1 = new android.os.RemoteException
            java.lang.String r2 = "Failed to allocate space"
            r1.<init>(r2)
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.GraphicsStatsService.fetchActiveBuffersLocked(android.view.IGraphicsStatsCallback, int, int, java.lang.String, long):com.android.server.GraphicsStatsService$ActiveBuffer");
    }

    private HashSet<File> dumpActiveLocked(long dump, ArrayList<HistoricalBuffer> buffers) {
        HashSet<File> skipFiles = new HashSet<>(buffers.size());
        for (int i = 0; i < buffers.size(); i++) {
            HistoricalBuffer buffer = buffers.get(i);
            File path = pathForApp(buffer.mInfo);
            skipFiles.add(path);
            nAddToDump(dump, path.getAbsolutePath(), buffer.mInfo.packageName, buffer.mInfo.versionCode, buffer.mInfo.startTime, buffer.mInfo.endTime, buffer.mData);
        }
        return skipFiles;
    }

    private void dumpHistoricalLocked(long dump, HashSet<File> skipFiles) {
        File[] fileArr;
        File[] listFiles = this.mGraphicsStatsDir.listFiles();
        int length = listFiles.length;
        int i = 0;
        while (i < length) {
            File date = listFiles[i];
            File[] listFiles2 = date.listFiles();
            int length2 = listFiles2.length;
            int i2 = 0;
            while (i2 < length2) {
                File pkg = listFiles2[i2];
                File[] listFiles3 = pkg.listFiles();
                int length3 = listFiles3.length;
                int i3 = 0;
                while (i3 < length3) {
                    File version = listFiles3[i3];
                    File data = new File(version, "total");
                    if (skipFiles.contains(data)) {
                        fileArr = listFiles;
                    } else {
                        fileArr = listFiles;
                        nAddToDump(dump, data.getAbsolutePath());
                    }
                    i3++;
                    listFiles = fileArr;
                }
                i2++;
                listFiles = listFiles;
            }
            i++;
            listFiles = listFiles;
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        ArrayList<HistoricalBuffer> buffers;
        if (DumpUtils.checkDumpAndUsageStatsPermission(this.mContext, TAG, fout)) {
            boolean dumpProto = false;
            int length = args.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    break;
                }
                String str = args[i];
                if (!PriorityDump.PROTO_ARG.equals(str)) {
                    i++;
                } else {
                    dumpProto = true;
                    break;
                }
            }
            synchronized (this.mLock) {
                buffers = new ArrayList<>(this.mActive.size());
                for (int i2 = 0; i2 < this.mActive.size(); i2++) {
                    try {
                        buffers.add(new HistoricalBuffer(this.mActive.get(i2)));
                    } catch (IOException e) {
                    }
                }
            }
            long dump = nCreateDump(fd.getInt$(), dumpProto);
            try {
                synchronized (this.mFileAccessLock) {
                    HashSet<File> skipList = dumpActiveLocked(dump, buffers);
                    buffers.clear();
                    dumpHistoricalLocked(dump, skipList);
                }
            } finally {
                nFinishDump(dump);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class BufferInfo {
        long endTime;
        final String packageName;
        long startTime;
        final long versionCode;

        BufferInfo(String packageName, long versionCode, long startTime) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.startTime = startTime;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ActiveBuffer implements IBinder.DeathRecipient {
        final IGraphicsStatsCallback mCallback;
        final BufferInfo mInfo;
        final int mPid;
        MemoryFile mProcessBuffer;
        final IBinder mToken;
        final int mUid;

        ActiveBuffer(IGraphicsStatsCallback token, int uid, int pid, String packageName, long versionCode) throws RemoteException, IOException {
            this.mInfo = new BufferInfo(packageName, versionCode, System.currentTimeMillis());
            this.mUid = uid;
            this.mPid = pid;
            this.mCallback = token;
            this.mToken = this.mCallback.asBinder();
            this.mToken.linkToDeath(this, 0);
            this.mProcessBuffer = new MemoryFile("GFXStats-" + pid, GraphicsStatsService.this.ASHMEM_SIZE);
            this.mProcessBuffer.writeBytes(GraphicsStatsService.this.ZERO_DATA, 0, 0, GraphicsStatsService.this.ASHMEM_SIZE);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            this.mToken.unlinkToDeath(this, 0);
            GraphicsStatsService.this.processDied(this);
        }

        void closeAllBuffers() {
            MemoryFile memoryFile = this.mProcessBuffer;
            if (memoryFile != null) {
                memoryFile.close();
                this.mProcessBuffer = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class HistoricalBuffer {
        final byte[] mData;
        final BufferInfo mInfo;

        HistoricalBuffer(ActiveBuffer active) throws IOException {
            this.mData = new byte[GraphicsStatsService.this.ASHMEM_SIZE];
            this.mInfo = active.mInfo;
            this.mInfo.endTime = System.currentTimeMillis();
            active.mProcessBuffer.readBytes(this.mData, 0, 0, GraphicsStatsService.this.ASHMEM_SIZE);
        }
    }
}
