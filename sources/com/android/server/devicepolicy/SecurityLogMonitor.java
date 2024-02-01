package com.android.server.devicepolicy;

import android.app.admin.SecurityLog;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.job.controllers.JobStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class SecurityLogMonitor implements Runnable {
    private static final int BUFFER_ENTRIES_CRITICAL_LEVEL = 9216;
    private static final int BUFFER_ENTRIES_MAXIMUM_LEVEL = 10240;
    @VisibleForTesting
    static final int BUFFER_ENTRIES_NOTIFICATION_LEVEL = 1024;
    private static final boolean DEBUG = false;
    private static final String TAG = "SecurityLogMonitor";
    @GuardedBy("mLock")
    private boolean mAllowedToRetrieve;
    @GuardedBy("mLock")
    private boolean mCriticalLevelLogged;
    private final Semaphore mForceSemaphore;
    @GuardedBy("mLock")
    private long mId;
    private long mLastEventNanos;
    private final ArrayList<SecurityLog.SecurityEvent> mLastEvents;
    @GuardedBy("mForceSemaphore")
    private long mLastForceNanos;
    private final Lock mLock;
    @GuardedBy("mLock")
    private Thread mMonitorThread;
    @GuardedBy("mLock")
    private long mNextAllowedRetrievalTimeMillis;
    @GuardedBy("mLock")
    private boolean mPaused;
    @GuardedBy("mLock")
    private ArrayList<SecurityLog.SecurityEvent> mPendingLogs;
    private final DevicePolicyManagerService mService;
    private static final long RATE_LIMIT_INTERVAL_MS = TimeUnit.HOURS.toMillis(2);
    private static final long BROADCAST_RETRY_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long POLLING_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long OVERLAP_NS = TimeUnit.SECONDS.toNanos(3);
    private static final long FORCE_FETCH_THROTTLE_NS = TimeUnit.SECONDS.toNanos(10);

    /* JADX INFO: Access modifiers changed from: package-private */
    public SecurityLogMonitor(DevicePolicyManagerService service) {
        this(service, 0L);
    }

    @VisibleForTesting
    SecurityLogMonitor(DevicePolicyManagerService service, long id) {
        this.mLock = new ReentrantLock();
        this.mMonitorThread = null;
        this.mPendingLogs = new ArrayList<>();
        this.mAllowedToRetrieve = false;
        this.mCriticalLevelLogged = false;
        this.mLastEvents = new ArrayList<>();
        this.mLastEventNanos = -1L;
        this.mNextAllowedRetrievalTimeMillis = -1L;
        this.mPaused = false;
        this.mForceSemaphore = new Semaphore(0);
        this.mLastForceNanos = 0L;
        this.mService = service;
        this.mId = id;
        this.mLastForceNanos = System.nanoTime();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void start() {
        Slog.i(TAG, "Starting security logging.");
        SecurityLog.writeEvent(210011, new Object[0]);
        this.mLock.lock();
        try {
            if (this.mMonitorThread == null) {
                this.mPendingLogs = new ArrayList<>();
                this.mCriticalLevelLogged = false;
                this.mId = 0L;
                this.mAllowedToRetrieve = false;
                this.mNextAllowedRetrievalTimeMillis = -1L;
                this.mPaused = false;
                this.mMonitorThread = new Thread(this);
                this.mMonitorThread.start();
            }
        } finally {
            this.mLock.unlock();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void stop() {
        Slog.i(TAG, "Stopping security logging.");
        SecurityLog.writeEvent(210012, new Object[0]);
        this.mLock.lock();
        try {
            if (this.mMonitorThread != null) {
                this.mMonitorThread.interrupt();
                try {
                    this.mMonitorThread.join(TimeUnit.SECONDS.toMillis(5L));
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for thread to stop", e);
                }
                this.mPendingLogs = new ArrayList<>();
                this.mId = 0L;
                this.mAllowedToRetrieve = false;
                this.mNextAllowedRetrievalTimeMillis = -1L;
                this.mPaused = false;
                this.mMonitorThread = null;
            }
        } finally {
            this.mLock.unlock();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void pause() {
        Slog.i(TAG, "Paused.");
        this.mLock.lock();
        this.mPaused = true;
        this.mAllowedToRetrieve = false;
        this.mLock.unlock();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resume() {
        this.mLock.lock();
        try {
            if (!this.mPaused) {
                Log.d(TAG, "Attempted to resume, but logging is not paused.");
                return;
            }
            this.mPaused = false;
            this.mAllowedToRetrieve = false;
            this.mLock.unlock();
            Slog.i(TAG, "Resumed.");
            try {
                notifyDeviceOwnerIfNeeded(false);
            } catch (InterruptedException e) {
                Log.w(TAG, "Thread interrupted.", e);
            }
        } finally {
            this.mLock.unlock();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void discardLogs() {
        this.mLock.lock();
        this.mAllowedToRetrieve = false;
        this.mPendingLogs = new ArrayList<>();
        this.mCriticalLevelLogged = false;
        this.mLock.unlock();
        Slog.i(TAG, "Discarded all logs.");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<SecurityLog.SecurityEvent> retrieveLogs() {
        this.mLock.lock();
        try {
            if (this.mAllowedToRetrieve) {
                this.mAllowedToRetrieve = false;
                this.mNextAllowedRetrievalTimeMillis = SystemClock.elapsedRealtime() + RATE_LIMIT_INTERVAL_MS;
                List<SecurityLog.SecurityEvent> result = this.mPendingLogs;
                this.mPendingLogs = new ArrayList<>();
                this.mCriticalLevelLogged = false;
                return result;
            }
            return null;
        } finally {
            this.mLock.unlock();
        }
    }

    private void getNextBatch(ArrayList<SecurityLog.SecurityEvent> newLogs) throws IOException {
        if (this.mLastEventNanos < 0) {
            SecurityLog.readEvents(newLogs);
        } else {
            long startNanos = this.mLastEvents.isEmpty() ? this.mLastEventNanos : Math.max(0L, this.mLastEventNanos - OVERLAP_NS);
            SecurityLog.readEventsSince(startNanos, newLogs);
        }
        for (int i = 0; i < newLogs.size() - 1; i++) {
            if (newLogs.get(i).getTimeNanos() > newLogs.get(i + 1).getTimeNanos()) {
                newLogs.sort(new Comparator() { // from class: com.android.server.devicepolicy.-$$Lambda$SecurityLogMonitor$y5Q3dMmmJ8bk5nBh8WR2MUroKrI
                    @Override // java.util.Comparator
                    public final int compare(Object obj, Object obj2) {
                        int signum;
                        signum = Long.signum(((SecurityLog.SecurityEvent) obj).getTimeNanos() - ((SecurityLog.SecurityEvent) obj2).getTimeNanos());
                        return signum;
                    }
                });
                return;
            }
        }
    }

    private void saveLastEvents(ArrayList<SecurityLog.SecurityEvent> newLogs) {
        this.mLastEvents.clear();
        if (newLogs.isEmpty()) {
            return;
        }
        this.mLastEventNanos = newLogs.get(newLogs.size() - 1).getTimeNanos();
        int pos = newLogs.size() - 2;
        while (pos >= 0 && this.mLastEventNanos - newLogs.get(pos).getTimeNanos() < OVERLAP_NS) {
            pos--;
        }
        this.mLastEvents.addAll(newLogs.subList(pos + 1, newLogs.size()));
    }

    @GuardedBy("mLock")
    private void mergeBatchLocked(ArrayList<SecurityLog.SecurityEvent> newLogs) {
        this.mPendingLogs.ensureCapacity(this.mPendingLogs.size() + newLogs.size());
        int curPos = 0;
        int curPos2 = 0;
        while (curPos2 < this.mLastEvents.size() && curPos < newLogs.size()) {
            SecurityLog.SecurityEvent curEvent = newLogs.get(curPos);
            long currentNanos = curEvent.getTimeNanos();
            if (currentNanos > this.mLastEventNanos) {
                break;
            }
            SecurityLog.SecurityEvent lastEvent = this.mLastEvents.get(curPos2);
            long lastNanos = lastEvent.getTimeNanos();
            if (lastNanos > currentNanos) {
                assignLogId(curEvent);
                this.mPendingLogs.add(curEvent);
                curPos++;
            } else if (lastNanos < currentNanos) {
                curPos2++;
            } else {
                if (!lastEvent.equals(curEvent)) {
                    assignLogId(curEvent);
                    this.mPendingLogs.add(curEvent);
                }
                curPos2++;
                curPos++;
            }
        }
        List<SecurityLog.SecurityEvent> idLogs = newLogs.subList(curPos, newLogs.size());
        for (SecurityLog.SecurityEvent event : idLogs) {
            assignLogId(event);
        }
        this.mPendingLogs.addAll(idLogs);
        checkCriticalLevel();
        if (this.mPendingLogs.size() > BUFFER_ENTRIES_MAXIMUM_LEVEL) {
            this.mPendingLogs = new ArrayList<>(this.mPendingLogs.subList(this.mPendingLogs.size() - 5120, this.mPendingLogs.size()));
            this.mCriticalLevelLogged = false;
            Slog.i(TAG, "Pending logs buffer full. Discarding old logs.");
        }
    }

    @GuardedBy("mLock")
    private void checkCriticalLevel() {
        if (SecurityLog.isLoggingEnabled() && this.mPendingLogs.size() >= BUFFER_ENTRIES_CRITICAL_LEVEL && !this.mCriticalLevelLogged) {
            this.mCriticalLevelLogged = true;
            SecurityLog.writeEvent(210015, new Object[0]);
        }
    }

    @GuardedBy("mLock")
    private void assignLogId(SecurityLog.SecurityEvent event) {
        event.setId(this.mId);
        if (this.mId == JobStatus.NO_LATEST_RUNTIME) {
            Slog.i(TAG, "Reached maximum id value; wrapping around.");
            this.mId = 0L;
            return;
        }
        this.mId++;
    }

    @Override // java.lang.Runnable
    public void run() {
        Process.setThreadPriority(10);
        ArrayList<SecurityLog.SecurityEvent> newLogs = new ArrayList<>();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean force = this.mForceSemaphore.tryAcquire(POLLING_INTERVAL_MS, TimeUnit.MILLISECONDS);
                getNextBatch(newLogs);
                this.mLock.lockInterruptibly();
                try {
                    mergeBatchLocked(newLogs);
                    this.mLock.unlock();
                    saveLastEvents(newLogs);
                    newLogs.clear();
                    notifyDeviceOwnerIfNeeded(force);
                } catch (Throwable th) {
                    this.mLock.unlock();
                    throw th;
                    break;
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to read security log", e);
            } catch (InterruptedException e2) {
                Log.i(TAG, "Thread interrupted, exiting.", e2);
            }
        }
        this.mLastEvents.clear();
        if (this.mLastEventNanos != -1) {
            this.mLastEventNanos++;
        }
        Slog.i(TAG, "MonitorThread exit.");
    }

    private void notifyDeviceOwnerIfNeeded(boolean force) throws InterruptedException {
        boolean allowRetrievalAndNotifyDO = false;
        this.mLock.lockInterruptibly();
        try {
            if (this.mPaused) {
                return;
            }
            int logSize = this.mPendingLogs.size();
            if ((logSize >= 1024 || (force && logSize > 0)) && !this.mAllowedToRetrieve) {
                allowRetrievalAndNotifyDO = true;
            }
            if (logSize > 0 && SystemClock.elapsedRealtime() >= this.mNextAllowedRetrievalTimeMillis) {
                allowRetrievalAndNotifyDO = true;
            }
            if (allowRetrievalAndNotifyDO) {
                this.mAllowedToRetrieve = true;
                this.mNextAllowedRetrievalTimeMillis = SystemClock.elapsedRealtime() + BROADCAST_RETRY_INTERVAL_MS;
            }
            if (allowRetrievalAndNotifyDO) {
                Slog.i(TAG, "notify DO");
                this.mService.sendDeviceOwnerCommand("android.app.action.SECURITY_LOGS_AVAILABLE", null);
            }
        } finally {
            this.mLock.unlock();
        }
    }

    public long forceLogs() {
        long nowNanos = System.nanoTime();
        synchronized (this.mForceSemaphore) {
            long toWaitNanos = (this.mLastForceNanos + FORCE_FETCH_THROTTLE_NS) - nowNanos;
            if (toWaitNanos > 0) {
                return TimeUnit.NANOSECONDS.toMillis(toWaitNanos) + 1;
            }
            this.mLastForceNanos = nowNanos;
            if (this.mForceSemaphore.availablePermits() == 0) {
                this.mForceSemaphore.release();
            }
            return 0L;
        }
    }
}
