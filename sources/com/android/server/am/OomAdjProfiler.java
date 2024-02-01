package com.android.server.am;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.util.RingBuffer;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public class OomAdjProfiler {
    private static final boolean PROFILING_DISABLED = true;
    @GuardedBy({"this"})
    private long mLastSystemServerCpuTimeMs;
    @GuardedBy({"this"})
    private boolean mOnBattery;
    @GuardedBy({"this"})
    private long mOomAdjStartTimeMs;
    @GuardedBy({"this"})
    private boolean mOomAdjStarted;
    @GuardedBy({"this"})
    private boolean mScreenOff;
    @GuardedBy({"this"})
    private boolean mSystemServerCpuTimeUpdateScheduled;
    @GuardedBy({"this"})
    private CpuTimes mOomAdjRunTime = new CpuTimes();
    @GuardedBy({"this"})
    private CpuTimes mSystemServerCpuTime = new CpuTimes();
    private final ProcessCpuTracker mProcessCpuTracker = new ProcessCpuTracker(false);
    @GuardedBy({"this"})
    final RingBuffer<CpuTimes> mOomAdjRunTimesHist = new RingBuffer<>(CpuTimes.class, 10);
    @GuardedBy({"this"})
    final RingBuffer<CpuTimes> mSystemServerCpuTimesHist = new RingBuffer<>(CpuTimes.class, 10);

    /* JADX INFO: Access modifiers changed from: package-private */
    public void batteryPowerChanged(boolean onBattery) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onWakefulnessChanged(int wakefulness) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void oomAdjStarted() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void oomAdjEnded() {
    }

    private void scheduleSystemServerCpuTimeUpdate() {
    }

    private void updateSystemServerCpuTime(boolean onBattery, boolean screenOff) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void reset() {
        synchronized (this) {
            if (this.mSystemServerCpuTime.isEmpty()) {
                return;
            }
            this.mOomAdjRunTimesHist.append(this.mOomAdjRunTime);
            this.mSystemServerCpuTimesHist.append(this.mSystemServerCpuTime);
            this.mOomAdjRunTime = new CpuTimes();
            this.mSystemServerCpuTime = new CpuTimes();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw) {
    }

    /* loaded from: classes.dex */
    private class CpuTimes {
        private long mOnBatteryScreenOffTimeMs;
        private long mOnBatteryTimeMs;

        private CpuTimes() {
        }

        public void addCpuTimeMs(long cpuTimeMs) {
            addCpuTimeMs(cpuTimeMs, OomAdjProfiler.this.mOnBattery, OomAdjProfiler.this.mScreenOff);
        }

        public void addCpuTimeMs(long cpuTimeMs, boolean onBattery, boolean screenOff) {
            if (onBattery) {
                this.mOnBatteryTimeMs += cpuTimeMs;
                if (screenOff) {
                    this.mOnBatteryScreenOffTimeMs += cpuTimeMs;
                }
            }
        }

        public boolean isEmpty() {
            return this.mOnBatteryTimeMs == 0 && this.mOnBatteryScreenOffTimeMs == 0;
        }

        public String toString() {
            return "[" + this.mOnBatteryTimeMs + "," + this.mOnBatteryScreenOffTimeMs + "]";
        }
    }
}
