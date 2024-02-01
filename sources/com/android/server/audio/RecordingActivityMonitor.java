package com.android.server.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioSystem;
import android.media.IRecordingConfigDispatcher;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.server.audio.AudioEventLogger;
import com.android.server.backup.BackupManagerConstants;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
/* loaded from: classes.dex */
public final class RecordingActivityMonitor implements AudioSystem.AudioRecordingCallback {
    public static final String TAG = "AudioService.RecordingActivityMonitor";
    private static final AudioEventLogger sEventLogger = new AudioEventLogger(50, "recording activity as reported through AudioSystem.AudioRecordingCallback");
    private final PackageManager mPackMan;
    private ArrayList<RecMonitorClient> mClients = new ArrayList<>();
    private boolean mHasPublicClients = false;
    private HashMap<Integer, AudioRecordingConfiguration> mRecordConfigs = new HashMap<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public RecordingActivityMonitor(Context ctxt) {
        RecMonitorClient.sMonitor = this;
        this.mPackMan = ctxt.getPackageManager();
    }

    public void onRecordingConfigurationChanged(int event, int uid, int session, int source, int[] recordingInfo, String packName) {
        List<AudioRecordingConfiguration> configsSystem;
        List<AudioRecordingConfiguration> configsPublic;
        if (!MediaRecorder.isSystemOnlyAudioSource(source) && (configsSystem = updateSnapshot(event, uid, session, source, recordingInfo)) != null) {
            synchronized (this.mClients) {
                if (this.mHasPublicClients) {
                    configsPublic = anonymizeForPublicConsumption(configsSystem);
                } else {
                    configsPublic = new ArrayList<>();
                }
                Iterator<RecMonitorClient> clientIterator = this.mClients.iterator();
                while (clientIterator.hasNext()) {
                    RecMonitorClient rmc = clientIterator.next();
                    try {
                        if (rmc.mIsPrivileged) {
                            rmc.mDispatcherCb.dispatchRecordingConfigChange(configsSystem);
                        } else {
                            rmc.mDispatcherCb.dispatchRecordingConfigChange(configsPublic);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "Could not call dispatchRecordingConfigChange() on client", e);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void dump(PrintWriter pw) {
        pw.println("\nRecordActivityMonitor dump time: " + DateFormat.getTimeInstance().format(new Date()));
        synchronized (this.mRecordConfigs) {
            for (AudioRecordingConfiguration conf : this.mRecordConfigs.values()) {
                conf.dump(pw);
            }
        }
        pw.println("\n");
        sEventLogger.dump(pw);
    }

    private ArrayList<AudioRecordingConfiguration> anonymizeForPublicConsumption(List<AudioRecordingConfiguration> sysConfigs) {
        ArrayList<AudioRecordingConfiguration> publicConfigs = new ArrayList<>();
        for (AudioRecordingConfiguration config : sysConfigs) {
            publicConfigs.add(AudioRecordingConfiguration.anonymizedCopy(config));
        }
        return publicConfigs;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void initMonitor() {
        AudioSystem.setRecordingCallback(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerRecordingCallback(IRecordingConfigDispatcher rcdb, boolean isPrivileged) {
        if (rcdb == null) {
            return;
        }
        synchronized (this.mClients) {
            RecMonitorClient rmc = new RecMonitorClient(rcdb, isPrivileged);
            if (rmc.init()) {
                if (!isPrivileged) {
                    this.mHasPublicClients = true;
                }
                this.mClients.add(rmc);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterRecordingCallback(IRecordingConfigDispatcher rcdb) {
        if (rcdb == null) {
            return;
        }
        synchronized (this.mClients) {
            Iterator<RecMonitorClient> clientIterator = this.mClients.iterator();
            boolean hasPublicClients = false;
            while (clientIterator.hasNext()) {
                RecMonitorClient rmc = clientIterator.next();
                if (rcdb.equals(rmc.mDispatcherCb)) {
                    rmc.release();
                    clientIterator.remove();
                } else if (!rmc.mIsPrivileged) {
                    hasPublicClients = true;
                }
            }
            this.mHasPublicClients = hasPublicClients;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<AudioRecordingConfiguration> getActiveRecordingConfigurations(boolean isPrivileged) {
        synchronized (this.mRecordConfigs) {
            try {
                if (isPrivileged) {
                    return new ArrayList(this.mRecordConfigs.values());
                }
                List<AudioRecordingConfiguration> configsPublic = anonymizeForPublicConsumption(new ArrayList(this.mRecordConfigs.values()));
                return configsPublic;
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:21:0x0080 A[Catch: all -> 0x0065, TRY_ENTER, TryCatch #2 {all -> 0x0065, blocks: (B:12:0x005f, B:14:0x0062, B:21:0x0080, B:25:0x008e), top: B:57:0x005f }] */
    /* JADX WARN: Removed duplicated region for block: B:27:0x009a A[Catch: all -> 0x00c1, TRY_ENTER, TryCatch #0 {all -> 0x00c1, blocks: (B:9:0x0014, B:19:0x006c, B:30:0x00a3, B:27:0x009a), top: B:54:0x0014 }] */
    /* JADX WARN: Removed duplicated region for block: B:30:0x00a3 A[Catch: all -> 0x00c1, TRY_LEAVE, TryCatch #0 {all -> 0x00c1, blocks: (B:9:0x0014, B:19:0x006c, B:30:0x00a3, B:27:0x009a), top: B:54:0x0014 }] */
    /* JADX WARN: Removed duplicated region for block: B:33:0x00bd  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private java.util.List<android.media.AudioRecordingConfiguration> updateSnapshot(int r20, int r21, int r22, int r23, int[] r24) {
        /*
            Method dump skipped, instructions count: 300
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.RecordingActivityMonitor.updateSnapshot(int, int, int, int, int[]):java.util.List");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class RecMonitorClient implements IBinder.DeathRecipient {
        static RecordingActivityMonitor sMonitor;
        final IRecordingConfigDispatcher mDispatcherCb;
        final boolean mIsPrivileged;

        RecMonitorClient(IRecordingConfigDispatcher rcdb, boolean isPrivileged) {
            this.mDispatcherCb = rcdb;
            this.mIsPrivileged = isPrivileged;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Log.w(RecordingActivityMonitor.TAG, "client died");
            sMonitor.unregisterRecordingCallback(this.mDispatcherCb);
        }

        boolean init() {
            try {
                this.mDispatcherCb.asBinder().linkToDeath(this, 0);
                return true;
            } catch (RemoteException e) {
                Log.w(RecordingActivityMonitor.TAG, "Could not link to client death", e);
                return false;
            }
        }

        void release() {
            this.mDispatcherCb.asBinder().unlinkToDeath(this, 0);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class RecordingEvent extends AudioEventLogger.Event {
        private final int mClientUid;
        private final String mPackName;
        private final int mRecEvent;
        private final int mSession;
        private final int mSource;

        RecordingEvent(int event, int uid, int session, int source, String packName) {
            this.mRecEvent = event;
            this.mClientUid = uid;
            this.mSession = session;
            this.mSource = source;
            this.mPackName = packName;
        }

        @Override // com.android.server.audio.AudioEventLogger.Event
        public String eventToString() {
            String str;
            StringBuilder sb = new StringBuilder("rec ");
            sb.append(this.mRecEvent == 1 ? "start" : "stop ");
            sb.append(" uid:");
            sb.append(this.mClientUid);
            sb.append(" session:");
            sb.append(this.mSession);
            sb.append(" src:");
            sb.append(MediaRecorder.toLogFriendlyAudioSource(this.mSource));
            if (this.mPackName == null) {
                str = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            } else {
                str = " pack:" + this.mPackName;
            }
            sb.append(str);
            return sb.toString();
        }
    }
}
