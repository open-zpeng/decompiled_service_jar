package com.android.server.wm;

import android.content.ComponentName;
import android.os.Process;
import android.service.vr.IPersistentVrStateCallbacks;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.vr.VrManagerInternal;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public final class VrController {
    private static final int FLAG_NON_VR_MODE = 0;
    private static final int FLAG_PERSISTENT_VR_MODE = 2;
    private static final int FLAG_VR_MODE = 1;
    private static int[] ORIG_ENUMS = {0, 1, 2};
    private static int[] PROTO_ENUMS = {0, 1, 2};
    private static final String TAG = "VrController";
    private final Object mGlobalAmLock;
    private int mVrState = 0;
    private int mVrRenderThreadTid = 0;
    private final IPersistentVrStateCallbacks mPersistentVrModeListener = new IPersistentVrStateCallbacks.Stub() { // from class: com.android.server.wm.VrController.1
        public void onPersistentVrStateChanged(boolean enabled) {
            synchronized (VrController.this.mGlobalAmLock) {
                if (enabled) {
                    VrController.this.setVrRenderThreadLocked(0, 3, true);
                    VrController.access$276(VrController.this, 2);
                } else {
                    VrController.this.setPersistentVrRenderThreadLocked(0, true);
                    VrController.access$272(VrController.this, -3);
                }
            }
        }
    };

    static /* synthetic */ int access$272(VrController x0, int x1) {
        int i = x0.mVrState & x1;
        x0.mVrState = i;
        return i;
    }

    static /* synthetic */ int access$276(VrController x0, int x1) {
        int i = x0.mVrState | x1;
        x0.mVrState = i;
        return i;
    }

    public VrController(Object globalAmLock) {
        this.mGlobalAmLock = globalAmLock;
    }

    public void onSystemReady() {
        VrManagerInternal vrManagerInternal = (VrManagerInternal) LocalServices.getService(VrManagerInternal.class);
        if (vrManagerInternal != null) {
            vrManagerInternal.addPersistentVrModeStateListener(this.mPersistentVrModeListener);
        }
    }

    public void onTopProcChangedLocked(WindowProcessController proc) {
        int curSchedGroup = proc.getCurrentSchedulingGroup();
        if (curSchedGroup == 3) {
            setVrRenderThreadLocked(proc.mVrThreadTid, curSchedGroup, true);
        } else if (proc.mVrThreadTid == this.mVrRenderThreadTid) {
            clearVrRenderThreadLocked(true);
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:24:0x0044 -> B:25:0x0045). Please submit an issue!!! */
    public boolean onVrModeChanged(ActivityRecord record) {
        int processId;
        VrManagerInternal vrService = (VrManagerInternal) LocalServices.getService(VrManagerInternal.class);
        if (vrService == null) {
            return false;
        }
        synchronized (this.mGlobalAmLock) {
            try {
                boolean vrMode = record.requestedVrComponent != null;
                ComponentName requestedPackage = record.requestedVrComponent;
                int userId = record.mUserId;
                ComponentName callingPackage = record.info.getComponentName();
                boolean changed = changeVrModeLocked(vrMode, record.app);
                try {
                    if (record.app == null) {
                        processId = -1;
                    } else {
                        processId = record.app.getPid();
                    }
                    try {
                        vrService.setVrMode(vrMode, requestedPackage, userId, processId, callingPackage);
                        return changed;
                    } catch (Throwable th) {
                        th = th;
                        throw th;
                    }
                } catch (Throwable th2) {
                    th = th2;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    public void setVrThreadLocked(int tid, int pid, WindowProcessController proc) {
        if (hasPersistentVrFlagSet()) {
            Slog.w(TAG, "VR thread cannot be set in persistent VR mode!");
        } else if (proc == null) {
            Slog.w(TAG, "Persistent VR thread not set, calling process doesn't exist!");
        } else {
            if (tid != 0) {
                enforceThreadInProcess(tid, pid);
            }
            if (!inVrMode()) {
                Slog.w(TAG, "VR thread cannot be set when not in VR mode!");
            } else {
                setVrRenderThreadLocked(tid, proc.getCurrentSchedulingGroup(), false);
            }
            proc.mVrThreadTid = tid > 0 ? tid : 0;
        }
    }

    public void setPersistentVrThreadLocked(int tid, int pid, WindowProcessController proc) {
        if (!hasPersistentVrFlagSet()) {
            Slog.w(TAG, "Persistent VR thread may only be set in persistent VR mode!");
        } else if (proc == null) {
            Slog.w(TAG, "Persistent VR thread not set, calling process doesn't exist!");
        } else {
            if (tid != 0) {
                enforceThreadInProcess(tid, pid);
            }
            setPersistentVrRenderThreadLocked(tid, false);
        }
    }

    public boolean shouldDisableNonVrUiLocked() {
        return this.mVrState != 0;
    }

    private boolean changeVrModeLocked(boolean vrMode, WindowProcessController proc) {
        int oldVrState = this.mVrState;
        if (vrMode) {
            this.mVrState |= 1;
        } else {
            this.mVrState &= -2;
        }
        boolean changed = oldVrState != this.mVrState;
        if (changed) {
            if (proc != null) {
                if (proc.mVrThreadTid > 0) {
                    setVrRenderThreadLocked(proc.mVrThreadTid, proc.getCurrentSchedulingGroup(), false);
                }
            } else {
                clearVrRenderThreadLocked(false);
            }
        }
        return changed;
    }

    private int updateVrRenderThreadLocked(int newTid, boolean suppressLogs) {
        int i = this.mVrRenderThreadTid;
        if (i == newTid) {
            return i;
        }
        if (i > 0) {
            ActivityManagerService.scheduleAsRegularPriority(i, suppressLogs);
            this.mVrRenderThreadTid = 0;
        }
        if (newTid > 0) {
            this.mVrRenderThreadTid = newTid;
            ActivityManagerService.scheduleAsFifoPriority(this.mVrRenderThreadTid, suppressLogs);
        }
        return this.mVrRenderThreadTid;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int setPersistentVrRenderThreadLocked(int newTid, boolean suppressLogs) {
        if (!hasPersistentVrFlagSet()) {
            if (!suppressLogs) {
                Slog.w(TAG, "Failed to set persistent VR thread, system not in persistent VR mode.");
            }
            return this.mVrRenderThreadTid;
        }
        return updateVrRenderThreadLocked(newTid, suppressLogs);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int setVrRenderThreadLocked(int newTid, int schedGroup, boolean suppressLogs) {
        boolean inVr = inVrMode();
        boolean inPersistentVr = hasPersistentVrFlagSet();
        if (!inVr || inPersistentVr || schedGroup != 3) {
            if (!suppressLogs) {
                String reason = "caller is not the current top application.";
                if (!inVr) {
                    reason = "system not in VR mode.";
                } else if (inPersistentVr) {
                    reason = "system in persistent VR mode.";
                }
                Slog.w(TAG, "Failed to set VR thread, " + reason);
            }
            return this.mVrRenderThreadTid;
        }
        return updateVrRenderThreadLocked(newTid, suppressLogs);
    }

    private void clearVrRenderThreadLocked(boolean suppressLogs) {
        updateVrRenderThreadLocked(0, suppressLogs);
    }

    private void enforceThreadInProcess(int tid, int pid) {
        if (!Process.isThreadInProcess(pid, tid)) {
            throw new IllegalArgumentException("VR thread does not belong to process");
        }
    }

    private boolean inVrMode() {
        return (this.mVrState & 1) != 0;
    }

    private boolean hasPersistentVrFlagSet() {
        return (this.mVrState & 2) != 0;
    }

    public String toString() {
        return String.format("[VrState=0x%x,VrRenderThreadTid=%d]", Integer.valueOf(this.mVrState), Integer.valueOf(this.mVrRenderThreadTid));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        ProtoUtils.writeBitWiseFlagsToProtoEnum(proto, 2259152797697L, this.mVrState, ORIG_ENUMS, PROTO_ENUMS);
        proto.write(1120986464258L, this.mVrRenderThreadTid);
        proto.end(token);
    }
}
