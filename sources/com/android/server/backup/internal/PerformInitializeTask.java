package com.android.server.backup.internal;

import android.app.backup.IBackupObserver;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.TransportManager;
import com.android.server.backup.transport.TransportClient;
import com.android.server.job.JobSchedulerShellCommand;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
/* loaded from: classes.dex */
public class PerformInitializeTask implements Runnable {
    private final BackupManagerService mBackupManagerService;
    private final File mBaseStateDir;
    private final OnTaskFinishedListener mListener;
    private IBackupObserver mObserver;
    private final String[] mQueue;
    private final TransportManager mTransportManager;

    public PerformInitializeTask(BackupManagerService backupManagerService, String[] transportNames, IBackupObserver observer, OnTaskFinishedListener listener) {
        this(backupManagerService, backupManagerService.getTransportManager(), transportNames, observer, listener, backupManagerService.getBaseStateDir());
    }

    @VisibleForTesting
    PerformInitializeTask(BackupManagerService backupManagerService, TransportManager transportManager, String[] transportNames, IBackupObserver observer, OnTaskFinishedListener listener, File baseStateDir) {
        this.mBackupManagerService = backupManagerService;
        this.mTransportManager = transportManager;
        this.mQueue = transportNames;
        this.mObserver = observer;
        this.mListener = listener;
        this.mBaseStateDir = baseStateDir;
    }

    private void notifyResult(String target, int status) {
        try {
            if (this.mObserver != null) {
                this.mObserver.onResult(target, status);
            }
        } catch (RemoteException e) {
            this.mObserver = null;
        }
    }

    private void notifyFinished(int status) {
        try {
            if (this.mObserver != null) {
                this.mObserver.backupFinished(status);
            }
        } catch (RemoteException e) {
            this.mObserver = null;
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r4v4 */
    @Override // java.lang.Runnable
    public void run() {
        int result;
        String[] strArr;
        int i;
        long delay;
        StringBuilder sb;
        boolean z;
        List<TransportClient> transportClientsToDisposeOf = new ArrayList<>(this.mQueue.length);
        boolean result2 = 0;
        try {
            try {
                String[] strArr2 = this.mQueue;
                int length = strArr2.length;
                result = 0;
                int result3 = 0;
                while (result3 < length) {
                    try {
                        String transportName = strArr2[result3];
                        TransportClient transportClient = this.mTransportManager.getTransportClient(transportName, "PerformInitializeTask.run()");
                        if (transportClient == null) {
                            Slog.e(BackupManagerService.TAG, "Requested init for " + transportName + " but not found");
                            strArr = strArr2;
                            i = length;
                        } else {
                            transportClientsToDisposeOf.add(transportClient);
                            Slog.i(BackupManagerService.TAG, "Initializing (wiping) backup transport storage: " + transportName);
                            String transportDirName = this.mTransportManager.getTransportDirName(transportClient.getTransportComponent());
                            EventLog.writeEvent((int) EventLogTags.BACKUP_START, transportDirName);
                            long startRealtime = SystemClock.elapsedRealtime();
                            IBackupTransport transport = transportClient.connectOrThrow("PerformInitializeTask.run()");
                            int status = transport.initializeDevice();
                            if (status == 0) {
                                status = transport.finishBackup();
                            }
                            if (status == 0) {
                                Slog.i(BackupManagerService.TAG, "Device init successful");
                                strArr = strArr2;
                                i = length;
                                int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                                EventLog.writeEvent((int) EventLogTags.BACKUP_INITIALIZE, new Object[0]);
                                File stateFileDir = new File(this.mBaseStateDir, transportDirName);
                                this.mBackupManagerService.resetBackupState(stateFileDir);
                                EventLog.writeEvent((int) EventLogTags.BACKUP_SUCCESS, 0, Integer.valueOf(millis));
                                this.mBackupManagerService.recordInitPending(false, transportName, transportDirName);
                                notifyResult(transportName, 0);
                            } else {
                                strArr = strArr2;
                                i = length;
                                Slog.e(BackupManagerService.TAG, "Transport error in initializeDevice()");
                                EventLog.writeEvent((int) EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                                this.mBackupManagerService.recordInitPending(true, transportName, transportDirName);
                                notifyResult(transportName, status);
                                int result4 = status;
                                try {
                                    delay = transport.requestBackupTime();
                                    sb = new StringBuilder();
                                } catch (Exception e) {
                                    e = e;
                                } catch (Throwable th) {
                                    th = th;
                                    result = result4;
                                }
                                try {
                                    sb.append("Init failed on ");
                                    sb.append(transportName);
                                    sb.append(" resched in ");
                                    sb.append(delay);
                                    Slog.w(BackupManagerService.TAG, sb.toString());
                                    z = false;
                                    this.mBackupManagerService.getAlarmManager().set(0, System.currentTimeMillis() + delay, this.mBackupManagerService.getRunInitIntent());
                                    result = result4;
                                    result3++;
                                    strArr2 = strArr;
                                    length = i;
                                } catch (Exception e2) {
                                    e = e2;
                                    Slog.e(BackupManagerService.TAG, "Unexpected error performing init", e);
                                    result = JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
                                    Iterator<TransportClient> it = transportClientsToDisposeOf.iterator();
                                    while (true) {
                                        result2 = it.hasNext();
                                        if (!result2) {
                                            break;
                                        }
                                        TransportClient transportClient2 = it.next();
                                        this.mTransportManager.disposeOfTransportClient(transportClient2, "PerformInitializeTask.run()");
                                    }
                                    notifyFinished(result);
                                    this.mListener.onFinished("PerformInitializeTask.run()");
                                } catch (Throwable th2) {
                                    th = th2;
                                    result = result4;
                                    for (TransportClient transportClient3 : transportClientsToDisposeOf) {
                                        this.mTransportManager.disposeOfTransportClient(transportClient3, "PerformInitializeTask.run()");
                                    }
                                    notifyFinished(result);
                                    this.mListener.onFinished("PerformInitializeTask.run()");
                                    throw th;
                                }
                            }
                        }
                        z = false;
                        result3++;
                        strArr2 = strArr;
                        length = i;
                    } catch (Exception e3) {
                        e = e3;
                    } catch (Throwable th3) {
                        th = th3;
                    }
                }
                Iterator<TransportClient> it2 = transportClientsToDisposeOf.iterator();
                while (true) {
                    result2 = it2.hasNext();
                    if (!result2) {
                        break;
                    }
                    TransportClient transportClient4 = it2.next();
                    this.mTransportManager.disposeOfTransportClient(transportClient4, "PerformInitializeTask.run()");
                }
            } catch (Throwable th4) {
                th = th4;
                result = result2;
            }
        } catch (Exception e4) {
            e = e4;
        }
        notifyFinished(result);
        this.mListener.onFinished("PerformInitializeTask.run()");
    }
}
