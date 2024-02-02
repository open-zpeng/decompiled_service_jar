package com.android.server;

import android.os.Process;
/* loaded from: classes.dex */
public class ThreadPriorityBooster {
    private static final boolean ENABLE_LOCK_GUARD = false;
    private volatile int mBoostToPriority;
    private final int mLockGuardIndex;
    private final ThreadLocal<PriorityState> mThreadState = new ThreadLocal<PriorityState>() { // from class: com.android.server.ThreadPriorityBooster.1
        /* JADX INFO: Access modifiers changed from: protected */
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // java.lang.ThreadLocal
        public PriorityState initialValue() {
            return new PriorityState();
        }
    };

    public ThreadPriorityBooster(int boostToPriority, int lockGuardIndex) {
        this.mBoostToPriority = boostToPriority;
        this.mLockGuardIndex = lockGuardIndex;
    }

    public void boost() {
        int tid = Process.myTid();
        int prevPriority = Process.getThreadPriority(tid);
        PriorityState state = this.mThreadState.get();
        if (state.regionCounter == 0) {
            state.prevPriority = prevPriority;
            if (prevPriority > this.mBoostToPriority) {
                Process.setThreadPriority(tid, this.mBoostToPriority);
            }
        }
        state.regionCounter++;
    }

    public void reset() {
        PriorityState state = this.mThreadState.get();
        state.regionCounter--;
        int currentPriority = Process.getThreadPriority(Process.myTid());
        if (state.regionCounter == 0 && state.prevPriority != currentPriority) {
            Process.setThreadPriority(Process.myTid(), state.prevPriority);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setBoostToPriority(int priority) {
        this.mBoostToPriority = priority;
        PriorityState state = this.mThreadState.get();
        int tid = Process.myTid();
        int prevPriority = Process.getThreadPriority(tid);
        if (state.regionCounter != 0 && prevPriority != priority) {
            Process.setThreadPriority(tid, priority);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class PriorityState {
        int prevPriority;
        int regionCounter;

        private PriorityState() {
        }
    }
}
