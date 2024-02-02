package com.android.server.devicepolicy;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.security.Credentials;
import android.security.KeyChain;
import android.util.Log;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
/* loaded from: classes.dex */
public class CertificateMonitor {
    protected static final String LOG_TAG = "DevicePolicyManager";
    protected static final int MONITORING_CERT_NOTIFICATION_ID = 33;
    private final Handler mHandler;
    private final DevicePolicyManagerService.Injector mInjector;
    private final BroadcastReceiver mRootCaReceiver = new BroadcastReceiver() { // from class: com.android.server.devicepolicy.CertificateMonitor.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (StorageManager.inCryptKeeperBounce()) {
                return;
            }
            int userId = intent.getIntExtra("android.intent.extra.user_handle", getSendingUserId());
            CertificateMonitor.this.updateInstalledCertificates(UserHandle.of(userId));
        }
    };
    private final DevicePolicyManagerService mService;

    public CertificateMonitor(DevicePolicyManagerService service, DevicePolicyManagerService.Injector injector, Handler handler) {
        this.mService = service;
        this.mInjector = injector;
        this.mHandler = handler;
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.USER_STARTED");
        filter.addAction("android.intent.action.USER_UNLOCKED");
        filter.addAction("android.security.action.TRUST_STORE_CHANGED");
        filter.setPriority(1000);
        this.mInjector.mContext.registerReceiverAsUser(this.mRootCaReceiver, UserHandle.ALL, filter, null, this.mHandler);
    }

    public String installCaCert(UserHandle userHandle, byte[] certBuffer) {
        try {
            X509Certificate cert = parseCert(certBuffer);
            byte[] pemCert = Credentials.convertToPem(new Certificate[]{cert});
            try {
                KeyChain.KeyChainConnection keyChainConnection = this.mInjector.keyChainBindAsUser(userHandle);
                String installCaCertificate = keyChainConnection.getService().installCaCertificate(pemCert);
                if (keyChainConnection != null) {
                    $closeResource(null, keyChainConnection);
                }
                return installCaCertificate;
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "installCaCertsToKeyChain(): ", e);
                return null;
            } catch (InterruptedException e1) {
                Log.w(LOG_TAG, "installCaCertsToKeyChain(): ", e1);
                Thread.currentThread().interrupt();
                return null;
            }
        } catch (IOException | CertificateException ce) {
            Log.e(LOG_TAG, "Problem converting cert", ce);
            return null;
        }
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

    public void uninstallCaCerts(UserHandle userHandle, String[] aliases) {
        try {
            KeyChain.KeyChainConnection keyChainConnection = this.mInjector.keyChainBindAsUser(userHandle);
            for (String str : aliases) {
                try {
                    keyChainConnection.getService().deleteCaCertificate(str);
                } catch (Throwable th) {
                    try {
                        throw th;
                    } catch (Throwable th2) {
                        if (keyChainConnection != null) {
                            $closeResource(th, keyChainConnection);
                        }
                        throw th2;
                    }
                }
            }
            if (keyChainConnection != null) {
                $closeResource(null, keyChainConnection);
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "from CaCertUninstaller: ", e);
        } catch (InterruptedException ie) {
            Log.w(LOG_TAG, "CaCertUninstaller: ", ie);
            Thread.currentThread().interrupt();
        }
    }

    public List<String> getInstalledCaCertificates(UserHandle userHandle) throws RemoteException, RuntimeException {
        try {
            KeyChain.KeyChainConnection conn = this.mInjector.keyChainBindAsUser(userHandle);
            List<String> list = conn.getService().getUserCaAliases().getList();
            if (conn != null) {
                $closeResource(null, conn);
            }
            return list;
        } catch (AssertionError e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void onCertificateApprovalsChanged(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.devicepolicy.-$$Lambda$CertificateMonitor$nzwzuvk_fK7AIlili6jDKrKWLJM
            @Override // java.lang.Runnable
            public final void run() {
                CertificateMonitor.this.updateInstalledCertificates(UserHandle.of(userId));
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateInstalledCertificates(UserHandle userHandle) {
        if (!this.mInjector.getUserManager().isUserUnlocked(userHandle.getIdentifier())) {
            return;
        }
        try {
            List<String> installedCerts = getInstalledCaCertificates(userHandle);
            this.mService.onInstalledCertificatesChanged(userHandle, installedCerts);
            int pendingCertificateCount = installedCerts.size() - this.mService.getAcceptedCaCertificates(userHandle).size();
            if (pendingCertificateCount == 0) {
                this.mInjector.getNotificationManager().cancelAsUser(LOG_TAG, 33, userHandle);
                return;
            }
            Notification noti = buildNotification(userHandle, pendingCertificateCount);
            this.mInjector.getNotificationManager().notifyAsUser(LOG_TAG, 33, noti, userHandle);
        } catch (RemoteException | RuntimeException e) {
            Log.e(LOG_TAG, "Could not retrieve certificates from KeyChain service", e);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:14:0x00a9  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private android.app.Notification buildNotification(android.os.UserHandle r20, int r21) {
        /*
            Method dump skipped, instructions count: 277
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.devicepolicy.CertificateMonitor.buildNotification(android.os.UserHandle, int):android.app.Notification");
    }

    private static X509Certificate parseCert(byte[] certBuffer) throws CertificateException {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBuffer));
    }
}
