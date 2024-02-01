package com.android.server;

import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.IStoraged;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.util.DumpUtils;
import com.android.server.storage.DiskStatsFileLogger;
import com.android.server.storage.DiskStatsLoggingService;
import com.android.server.utils.PriorityDump;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import libcore.io.IoUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class DiskStatsService extends Binder {
    private static final String DISKSTATS_DUMP_FILE = "/data/system/diskstats_cache.json";
    private static final String TAG = "DiskStatsService";
    private final Context mContext;

    public DiskStatsService(Context context) {
        this.mContext = context;
        DiskStatsLoggingService.schedule(context);
    }

    @Override // android.os.Binder
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        PrintWriter pw2;
        ProtoOutputStream proto;
        if (DumpUtils.checkDumpAndUsageStatsPermission(this.mContext, TAG, pw)) {
            byte[] junk = new byte[512];
            for (int i = 0; i < junk.length; i++) {
                junk[i] = (byte) i;
            }
            File tmp = new File(Environment.getDataDirectory(), "system/perftest.tmp");
            FileOutputStream fos = null;
            IOException error = null;
            long before = SystemClock.uptimeMillis();
            try {
                fos = new FileOutputStream(tmp);
                fos.write(junk);
                try {
                    fos.close();
                } catch (IOException e) {
                }
            } catch (IOException e2) {
                error = e2;
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e3) {
                    }
                }
            } catch (Throwable th) {
                FileOutputStream fos2 = fos;
                if (fos2 != null) {
                    try {
                        fos2.close();
                    } catch (IOException e4) {
                    }
                }
                throw th;
            }
            IOException error2 = error;
            long after = SystemClock.uptimeMillis();
            if (tmp.exists()) {
                tmp.delete();
            }
            boolean protoFormat = hasOption(args, PriorityDump.PROTO_ARG);
            if (protoFormat) {
                ProtoOutputStream proto2 = new ProtoOutputStream(fd);
                proto2.write(1133871366145L, error2 != null);
                if (error2 != null) {
                    proto2.write(1138166333442L, error2.toString());
                } else {
                    proto2.write(1120986464259L, after - before);
                }
                pw2 = null;
                proto = proto2;
            } else {
                if (error2 != null) {
                    pw.print("Test-Error: ");
                    pw.println(error2.toString());
                } else {
                    pw.print("Latency: ");
                    pw.print(after - before);
                    pw.println("ms [512B Data Write]");
                }
                pw2 = pw;
                proto = null;
            }
            if (protoFormat) {
                reportDiskWriteSpeedProto(proto);
            } else {
                reportDiskWriteSpeed(pw2);
            }
            PrintWriter printWriter = pw2;
            ProtoOutputStream protoOutputStream = proto;
            reportFreeSpace(Environment.getDataDirectory(), "Data", printWriter, protoOutputStream, 0);
            reportFreeSpace(Environment.getDownloadCacheDirectory(), "Cache", printWriter, protoOutputStream, 1);
            reportFreeSpace(new File("/system"), "System", printWriter, protoOutputStream, 2);
            boolean fileBased = StorageManager.isFileEncryptedNativeOnly();
            boolean blockBased = fileBased ? false : StorageManager.isBlockEncrypted();
            if (protoFormat) {
                if (fileBased) {
                    proto.write(1159641169925L, 3);
                } else if (blockBased) {
                    proto.write(1159641169925L, 2);
                } else {
                    proto.write(1159641169925L, 1);
                }
            } else if (fileBased) {
                pw2.println("File-based Encryption: true");
            }
            if (protoFormat) {
                reportCachedValuesProto(proto);
            } else {
                reportCachedValues(pw2);
            }
            if (protoFormat) {
                proto.flush();
            }
        }
    }

    private void reportFreeSpace(File path, String name, PrintWriter pw, ProtoOutputStream proto, int folderType) {
        try {
            StatFs statfs = new StatFs(path.getPath());
            long bsize = statfs.getBlockSize();
            long avail = statfs.getAvailableBlocks();
            long total = statfs.getBlockCount();
            if (bsize <= 0 || total <= 0) {
                throw new IllegalArgumentException("Invalid stat: bsize=" + bsize + " avail=" + avail + " total=" + total);
            } else if (proto != null) {
                long freeSpaceToken = proto.start(2246267895812L);
                proto.write(1159641169921L, folderType);
                proto.write(1112396529666L, (avail * bsize) / 1024);
                proto.write(1112396529667L, (total * bsize) / 1024);
                proto.end(freeSpaceToken);
            } else {
                pw.print(name);
                pw.print("-Free: ");
                pw.print((avail * bsize) / 1024);
                pw.print("K / ");
                pw.print((total * bsize) / 1024);
                pw.print("K total = ");
                pw.print((100 * avail) / total);
                pw.println("% free");
            }
        } catch (IllegalArgumentException e) {
            if (proto == null) {
                pw.print(name);
                pw.print("-Error: ");
                pw.println(e.toString());
            }
        }
    }

    private boolean hasOption(String[] args, String arg) {
        for (String opt : args) {
            if (arg.equals(opt)) {
                return true;
            }
        }
        return false;
    }

    private void reportCachedValues(PrintWriter pw) {
        try {
            String jsonString = IoUtils.readFileAsString("/data/system/diskstats_cache.json");
            JSONObject json = new JSONObject(jsonString);
            pw.print("App Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY));
            pw.print("App Data Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY));
            pw.print("App Cache Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY));
            pw.print("Photos Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.PHOTOS_KEY));
            pw.print("Videos Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.VIDEOS_KEY));
            pw.print("Audio Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.AUDIO_KEY));
            pw.print("Downloads Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.DOWNLOADS_KEY));
            pw.print("System Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.SYSTEM_KEY));
            pw.print("Other Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.MISC_KEY));
            pw.print("Package Names: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY));
            pw.print("App Sizes: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY));
            pw.print("App Data Sizes: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY));
            pw.print("Cache Sizes: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "exception reading diskstats cache file", e);
        }
    }

    private void reportCachedValuesProto(ProtoOutputStream proto) {
        try {
            String jsonString = IoUtils.readFileAsString("/data/system/diskstats_cache.json");
            JSONObject json = new JSONObject(jsonString);
            long cachedValuesToken = proto.start(1146756268038L);
            proto.write(1112396529665L, json.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY));
            proto.write(1112396529674L, json.getLong(DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY));
            proto.write(1112396529666L, json.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY));
            proto.write(1112396529667L, json.getLong(DiskStatsFileLogger.PHOTOS_KEY));
            proto.write(1112396529668L, json.getLong(DiskStatsFileLogger.VIDEOS_KEY));
            proto.write(1112396529669L, json.getLong(DiskStatsFileLogger.AUDIO_KEY));
            proto.write(1112396529670L, json.getLong(DiskStatsFileLogger.DOWNLOADS_KEY));
            proto.write(1112396529671L, json.getLong(DiskStatsFileLogger.SYSTEM_KEY));
            proto.write(1112396529672L, json.getLong(DiskStatsFileLogger.MISC_KEY));
            JSONArray packageNamesArray = json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
            JSONArray appSizesArray = json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
            JSONArray appDataSizesArray = json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY);
            JSONArray cacheSizesArray = json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
            int len = packageNamesArray.length();
            if (len == appSizesArray.length() && len == appDataSizesArray.length() && len == cacheSizesArray.length()) {
                int i = 0;
                while (i < len) {
                    long packageToken = proto.start(2246267895817L);
                    proto.write(1138166333441L, packageNamesArray.getString(i));
                    proto.write(1112396529666L, appSizesArray.getLong(i));
                    proto.write(1112396529668L, appDataSizesArray.getLong(i));
                    proto.write(1112396529667L, cacheSizesArray.getLong(i));
                    proto.end(packageToken);
                    i++;
                    packageNamesArray = packageNamesArray;
                    appSizesArray = appSizesArray;
                }
            } else {
                Slog.wtf(TAG, "Sizes of packageNamesArray, appSizesArray, appDataSizesArray  and cacheSizesArray are not the same");
            }
            proto.end(cachedValuesToken);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "exception reading diskstats cache file", e);
        }
    }

    private int getRecentPerf() throws RemoteException, IllegalStateException {
        IBinder binder = ServiceManager.getService("storaged");
        if (binder == null) {
            throw new IllegalStateException("storaged not found");
        }
        IStoraged storaged = IStoraged.Stub.asInterface(binder);
        return storaged.getRecentPerf();
    }

    private void reportDiskWriteSpeed(PrintWriter pw) {
        try {
            long perf = getRecentPerf();
            if (perf != 0) {
                pw.print("Recent Disk Write Speed (kB/s) = ");
                pw.println(perf);
            } else {
                pw.println("Recent Disk Write Speed data unavailable");
                Log.w(TAG, "Recent Disk Write Speed data unavailable!");
            }
        } catch (RemoteException | IllegalStateException e) {
            pw.println(e.toString());
            Log.e(TAG, e.toString());
        }
    }

    private void reportDiskWriteSpeedProto(ProtoOutputStream proto) {
        try {
            long perf = getRecentPerf();
            if (perf != 0) {
                proto.write(1120986464263L, perf);
            } else {
                Log.w(TAG, "Recent Disk Write Speed data unavailable!");
            }
        } catch (RemoteException | IllegalStateException e) {
            Log.e(TAG, e.toString());
        }
    }
}
