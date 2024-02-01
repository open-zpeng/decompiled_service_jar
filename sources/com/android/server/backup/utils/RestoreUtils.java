package com.android.server.backup.utils;

import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.IBinder;
import com.android.internal.annotations.GuardedBy;

/* loaded from: classes.dex */
public class RestoreUtils {
    /* JADX WARN: Removed duplicated region for block: B:123:0x028c  */
    /* JADX WARN: Removed duplicated region for block: B:202:0x026f A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static boolean installApk(java.io.InputStream r22, android.content.Context r23, com.android.server.backup.restore.RestoreDeleteObserver r24, java.util.HashMap<java.lang.String, android.content.pm.Signature[]> r25, java.util.HashMap<java.lang.String, com.android.server.backup.restore.RestorePolicy> r26, com.android.server.backup.FileMetadata r27, java.lang.String r28, com.android.server.backup.utils.BytesReadListener r29, int r30) {
        /*
            Method dump skipped, instructions count: 856
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.backup.utils.RestoreUtils.installApk(java.io.InputStream, android.content.Context, com.android.server.backup.restore.RestoreDeleteObserver, java.util.HashMap, java.util.HashMap, com.android.server.backup.FileMetadata, java.lang.String, com.android.server.backup.utils.BytesReadListener, int):boolean");
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    /* loaded from: classes.dex */
    private static class LocalIntentReceiver {
        private IIntentSender.Stub mLocalSender;
        private final Object mLock;
        @GuardedBy({"mLock"})
        private Intent mResult;

        private LocalIntentReceiver() {
            this.mLock = new Object();
            this.mResult = null;
            this.mLocalSender = new IIntentSender.Stub() { // from class: com.android.server.backup.utils.RestoreUtils.LocalIntentReceiver.1
                public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken, IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                    synchronized (LocalIntentReceiver.this.mLock) {
                        LocalIntentReceiver.this.mResult = intent;
                        LocalIntentReceiver.this.mLock.notifyAll();
                    }
                }
            };
        }

        public IntentSender getIntentSender() {
            return new IntentSender(this.mLocalSender);
        }

        public Intent getResult() {
            Intent intent;
            synchronized (this.mLock) {
                while (this.mResult == null) {
                    try {
                        this.mLock.wait();
                    } catch (InterruptedException e) {
                    }
                }
                intent = this.mResult;
            }
            return intent;
        }
    }
}
