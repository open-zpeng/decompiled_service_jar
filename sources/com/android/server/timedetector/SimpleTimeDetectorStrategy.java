package com.android.server.timedetector;

import android.app.timedetector.TimeSignal;
import android.content.Intent;
import android.util.Slog;
import android.util.TimestampedValue;
import com.android.server.timedetector.TimeDetectorStrategy;
import java.io.PrintWriter;

/* loaded from: classes2.dex */
public final class SimpleTimeDetectorStrategy implements TimeDetectorStrategy {
    private static final long SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS = 2000;
    private static final String TAG = "timedetector.SimpleTimeDetectorStrategy";
    private TimeDetectorStrategy.Callback mCallback;
    private TimestampedValue<Long> mLastNitzTime;
    private TimestampedValue<Long> mLastSystemClockTime;
    private boolean mLastSystemClockTimeSendNetworkBroadcast;
    private TimestampedValue<Long> mLastSystemClockTimeSet;

    @Override // com.android.server.timedetector.TimeDetectorStrategy
    public void initialize(TimeDetectorStrategy.Callback callback) {
        this.mCallback = callback;
    }

    @Override // com.android.server.timedetector.TimeDetectorStrategy
    public void suggestTime(TimeSignal timeSignal) {
        if (!"nitz".equals(timeSignal.getSourceId())) {
            Slog.w(TAG, "Ignoring signal from unsupported source: " + timeSignal);
            return;
        }
        TimestampedValue<Long> newNitzUtcTime = timeSignal.getUtcTime();
        boolean nitzTimeIsValid = validateNewNitzTime(newNitzUtcTime, this.mLastNitzTime);
        if (!nitzTimeIsValid) {
            return;
        }
        this.mLastNitzTime = newNitzUtcTime;
        boolean sendNetworkBroadcast = "nitz".equals(timeSignal.getSourceId());
        setSystemClockIfRequired(newNitzUtcTime, sendNetworkBroadcast);
    }

    private static boolean validateNewNitzTime(TimestampedValue<Long> newNitzUtcTime, TimestampedValue<Long> lastNitzTime) {
        if (lastNitzTime != null) {
            long referenceTimeDifference = TimestampedValue.referenceTimeDifference(newNitzUtcTime, lastNitzTime);
            if (referenceTimeDifference < 0 || referenceTimeDifference > 2147483647L) {
                Slog.w(TAG, "validateNewNitzTime: Bad NITZ signal received. referenceTimeDifference=" + referenceTimeDifference + " lastNitzTime=" + lastNitzTime + " newNitzUtcTime=" + newNitzUtcTime);
                return false;
            }
            return true;
        }
        return true;
    }

    private void setSystemClockIfRequired(TimestampedValue<Long> time, boolean sendNetworkBroadcast) {
        this.mLastSystemClockTime = time;
        this.mLastSystemClockTimeSendNetworkBroadcast = sendNetworkBroadcast;
        if (!this.mCallback.isTimeDetectionEnabled()) {
            Slog.d(TAG, "setSystemClockIfRequired: Time detection is not enabled. time=" + time);
            return;
        }
        this.mCallback.acquireWakeLock();
        try {
            long elapsedRealtimeMillis = this.mCallback.elapsedRealtimeMillis();
            long actualTimeMillis = this.mCallback.systemClockMillis();
            if (this.mLastSystemClockTimeSet != null) {
                long expectedTimeMillis = TimeDetectorStrategy.getTimeAt(this.mLastSystemClockTimeSet, elapsedRealtimeMillis);
                long absSystemClockDifference = Math.abs(expectedTimeMillis - actualTimeMillis);
                if (absSystemClockDifference > SYSTEM_CLOCK_PARANOIA_THRESHOLD_MILLIS) {
                    Slog.w(TAG, "System clock has not tracked elapsed real time clock. A clock may be inaccurate or something unexpectedly set the system clock. elapsedRealtimeMillis=" + elapsedRealtimeMillis + " expectedTimeMillis=" + expectedTimeMillis + " actualTimeMillis=" + actualTimeMillis);
                }
            }
            adjustAndSetDeviceSystemClock(time, sendNetworkBroadcast, elapsedRealtimeMillis, actualTimeMillis, "New time signal");
        } finally {
            this.mCallback.releaseWakeLock();
        }
    }

    @Override // com.android.server.timedetector.TimeDetectorStrategy
    public void handleAutoTimeDetectionToggle(boolean enabled) {
        if (enabled) {
            if (this.mLastSystemClockTime != null) {
                boolean sendNetworkBroadcast = this.mLastSystemClockTimeSendNetworkBroadcast;
                this.mCallback.acquireWakeLock();
                try {
                    long elapsedRealtimeMillis = this.mCallback.elapsedRealtimeMillis();
                    long actualTimeMillis = this.mCallback.systemClockMillis();
                    adjustAndSetDeviceSystemClock(this.mLastSystemClockTime, sendNetworkBroadcast, elapsedRealtimeMillis, actualTimeMillis, "Automatic time detection enabled.");
                    return;
                } finally {
                    this.mCallback.releaseWakeLock();
                }
            }
            return;
        }
        this.mLastSystemClockTimeSet = null;
    }

    @Override // com.android.server.timedetector.TimeDetectorStrategy
    public void dump(PrintWriter pw, String[] args) {
        pw.println("mLastNitzTime=" + this.mLastNitzTime);
        pw.println("mLastSystemClockTimeSet=" + this.mLastSystemClockTimeSet);
        pw.println("mLastSystemClockTime=" + this.mLastSystemClockTime);
        pw.println("mLastSystemClockTimeSendNetworkBroadcast=" + this.mLastSystemClockTimeSendNetworkBroadcast);
    }

    private void adjustAndSetDeviceSystemClock(TimestampedValue<Long> newTime, boolean sendNetworkBroadcast, long elapsedRealtimeMillis, long actualSystemClockMillis, String reason) {
        long newSystemClockMillis = TimeDetectorStrategy.getTimeAt(newTime, elapsedRealtimeMillis);
        long absTimeDifference = Math.abs(newSystemClockMillis - actualSystemClockMillis);
        long systemClockUpdateThreshold = this.mCallback.systemClockUpdateThresholdMillis();
        if (absTimeDifference < systemClockUpdateThreshold) {
            Slog.d(TAG, "adjustAndSetDeviceSystemClock: Not setting system clock. New time and system clock are close enough. elapsedRealtimeMillis=" + elapsedRealtimeMillis + " newTime=" + newTime + " reason=" + reason + " systemClockUpdateThreshold=" + systemClockUpdateThreshold + " absTimeDifference=" + absTimeDifference);
            return;
        }
        Slog.d(TAG, "Setting system clock using time=" + newTime + " reason=" + reason + " elapsedRealtimeMillis=" + elapsedRealtimeMillis + " newTimeMillis=" + newSystemClockMillis);
        this.mCallback.setSystemClock(newSystemClockMillis);
        this.mLastSystemClockTimeSet = newTime;
        if (sendNetworkBroadcast) {
            Intent intent = new Intent("android.intent.action.NETWORK_SET_TIME");
            intent.addFlags(536870912);
            intent.putExtra("time", newSystemClockMillis);
            this.mCallback.sendStickyBroadcast(intent);
        }
    }
}
