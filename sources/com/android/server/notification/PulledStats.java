package com.android.server.notification;

import android.os.ParcelFileDescriptor;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.notification.NotificationManagerService;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
public class PulledStats {
    static final String TAG = "PulledStats";
    private long mTimePeriodEndMs;
    private final long mTimePeriodStartMs;
    private List<String> mUndecoratedPackageNames = new ArrayList();

    public PulledStats(long startMs) {
        this.mTimePeriodStartMs = startMs;
        this.mTimePeriodEndMs = startMs;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ParcelFileDescriptor toParcelFileDescriptor(final int report) throws IOException {
        final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
        if (report == 1) {
            Thread thr = new Thread("NotificationManager pulled metric output") { // from class: com.android.server.notification.PulledStats.1
                @Override // java.lang.Thread, java.lang.Runnable
                public void run() {
                    try {
                        FileOutputStream fout = new ParcelFileDescriptor.AutoCloseOutputStream(fds[1]);
                        ProtoOutputStream proto = new ProtoOutputStream(fout);
                        PulledStats.this.writeToProto(report, proto);
                        proto.flush();
                        fout.close();
                    } catch (IOException e) {
                        Slog.w(PulledStats.TAG, "Failure writing pipe", e);
                    }
                }
            };
            thr.start();
        } else {
            Slog.w(TAG, "Unknown pulled stats request: " + report);
        }
        return fds[0];
    }

    public long endTimeMs() {
        return this.mTimePeriodEndMs;
    }

    public void dump(int report, PrintWriter pw, NotificationManagerService.DumpFilter filter) {
        if (report == 1) {
            pw.print("  Packages with undecordated notifications (");
            pw.print(this.mTimePeriodStartMs);
            pw.print(" - ");
            pw.print(this.mTimePeriodEndMs);
            pw.println("):");
            if (this.mUndecoratedPackageNames.size() == 0) {
                pw.println("    none");
                return;
            }
            for (String pkg : this.mUndecoratedPackageNames) {
                if (!filter.filtered || pkg.equals(filter.pkgFilter)) {
                    pw.println("    " + pkg);
                }
            }
            return;
        }
        pw.println("Unknown pulled stats request: " + report);
    }

    @VisibleForTesting
    void writeToProto(int report, ProtoOutputStream proto) {
        if (report == 1) {
            for (String pkg : this.mUndecoratedPackageNames) {
                long token = proto.start(2246267895809L);
                proto.write(1138166333441L, pkg);
                proto.end(token);
            }
            return;
        }
        Slog.w(TAG, "Unknown pulled stats request: " + report);
    }

    public void addUndecoratedPackage(String packageName, long timestampMs) {
        this.mUndecoratedPackageNames.add(packageName);
        this.mTimePeriodEndMs = Math.max(this.mTimePeriodEndMs, timestampMs);
    }
}
