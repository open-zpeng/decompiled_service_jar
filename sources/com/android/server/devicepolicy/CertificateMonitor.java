package com.android.server.devicepolicy;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.security.Credentials;
import android.security.KeyChain;
import android.util.Log;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.devicepolicy.DevicePolicyManagerService;
import com.android.server.pm.DumpState;
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
                try {
                    String installCaCertificate = keyChainConnection.getService().installCaCertificate(pemCert);
                    $closeResource(null, keyChainConnection);
                    return installCaCertificate;
                } finally {
                }
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
                keyChainConnection.getService().deleteCaCertificate(str);
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
            $closeResource(null, conn);
            return list;
        } catch (AssertionError e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public /* synthetic */ void lambda$onCertificateApprovalsChanged$0$CertificateMonitor(int userId) {
        updateInstalledCertificates(UserHandle.of(userId));
    }

    public void onCertificateApprovalsChanged(final int userId) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.devicepolicy.-$$Lambda$CertificateMonitor$nzwzuvk_fK7AIlili6jDKrKWLJM
            @Override // java.lang.Runnable
            public final void run() {
                CertificateMonitor.this.lambda$onCertificateApprovalsChanged$0$CertificateMonitor(userId);
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

    private Notification buildNotification(UserHandle userHandle, int pendingCertificateCount) {
        int parentUserId;
        String contentText;
        int smallIconId;
        try {
            Context userContext = this.mInjector.createContextAsUser(userHandle);
            Resources resources = this.mInjector.getResources();
            int parentUserId2 = userHandle.getIdentifier();
            if (this.mService.getProfileOwner(userHandle.getIdentifier()) != null) {
                String contentText2 = resources.getString(17041075, this.mService.getProfileOwnerName(userHandle.getIdentifier()));
                parentUserId = this.mService.getProfileParentId(userHandle.getIdentifier());
                contentText = contentText2;
                smallIconId = 17303556;
            } else if (this.mService.getDeviceOwnerUserId() == userHandle.getIdentifier()) {
                this.mService.getDeviceOwnerName();
                String contentText3 = resources.getString(17041075, this.mService.getDeviceOwnerName());
                parentUserId = parentUserId2;
                smallIconId = 17303556;
                contentText = contentText3;
            } else {
                String contentText4 = resources.getString(17041074);
                parentUserId = parentUserId2;
                contentText = contentText4;
                smallIconId = 17301642;
            }
            Intent dialogIntent = new Intent("com.android.settings.MONITORING_CERT_INFO");
            dialogIntent.setFlags(268468224);
            dialogIntent.putExtra("android.settings.extra.number_of_certificates", pendingCertificateCount);
            dialogIntent.putExtra("android.intent.extra.USER_ID", userHandle.getIdentifier());
            ActivityInfo targetInfo = dialogIntent.resolveActivityInfo(this.mInjector.getPackageManager(), DumpState.DUMP_DEXOPT);
            if (targetInfo != null) {
                dialogIntent.setComponent(targetInfo.getComponentName());
            }
            PendingIntent notifyIntent = this.mInjector.pendingIntentGetActivityAsUser(userContext, 0, dialogIntent, 134217728, null, UserHandle.of(parentUserId));
            return new Notification.Builder(userContext, SystemNotificationChannels.SECURITY).setSmallIcon(smallIconId).setContentTitle(resources.getQuantityText(18153497, pendingCertificateCount)).setContentText(contentText).setContentIntent(notifyIntent).setShowWhen(false).setColor(17170460).build();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Create context as " + userHandle + " failed", e);
            return null;
        }
    }

    private static X509Certificate parseCert(byte[] certBuffer) throws CertificateException {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBuffer));
    }
}
