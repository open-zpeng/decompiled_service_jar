package com.android.server.telecom;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.DefaultDialerManager;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.IntArray;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.telephony.SmsApplication;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerService;
/* loaded from: classes.dex */
public class TelecomLoaderService extends SystemService {
    private static final String SERVICE_ACTION = "com.android.ITelecomService";
    private static final ComponentName SERVICE_COMPONENT = new ComponentName("com.android.server.telecom", "com.android.server.telecom.components.TelecomService");
    private static final String TAG = "TelecomLoaderService";
    private final Context mContext;
    @GuardedBy("mLock")
    private IntArray mDefaultDialerAppRequests;
    @GuardedBy("mLock")
    private IntArray mDefaultSimCallManagerRequests;
    @GuardedBy("mLock")
    private IntArray mDefaultSmsAppRequests;
    private final Object mLock;
    @GuardedBy("mLock")
    private TelecomServiceConnection mServiceConnection;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class TelecomServiceConnection implements ServiceConnection {
        private TelecomServiceConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            String packageName;
            ComponentName smsComponent;
            try {
                service.linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.telecom.TelecomLoaderService.TelecomServiceConnection.1
                    @Override // android.os.IBinder.DeathRecipient
                    public void binderDied() {
                        TelecomLoaderService.this.connectToTelecom();
                    }
                }, 0);
                SmsApplication.getDefaultMmsApplication(TelecomLoaderService.this.mContext, false);
                ServiceManager.addService("telecom", service);
                synchronized (TelecomLoaderService.this.mLock) {
                    if (TelecomLoaderService.this.mDefaultSmsAppRequests != null || TelecomLoaderService.this.mDefaultDialerAppRequests != null || TelecomLoaderService.this.mDefaultSimCallManagerRequests != null) {
                        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
                        if (TelecomLoaderService.this.mDefaultSmsAppRequests != null && (smsComponent = SmsApplication.getDefaultSmsApplication(TelecomLoaderService.this.mContext, true)) != null) {
                            int requestCount = TelecomLoaderService.this.mDefaultSmsAppRequests.size();
                            for (int i = requestCount - 1; i >= 0; i--) {
                                int userid = TelecomLoaderService.this.mDefaultSmsAppRequests.get(i);
                                TelecomLoaderService.this.mDefaultSmsAppRequests.remove(i);
                                packageManagerInternal.grantDefaultPermissionsToDefaultSmsApp(smsComponent.getPackageName(), userid);
                            }
                        }
                        if (TelecomLoaderService.this.mDefaultDialerAppRequests != null && (packageName = DefaultDialerManager.getDefaultDialerApplication(TelecomLoaderService.this.mContext)) != null) {
                            int requestCount2 = TelecomLoaderService.this.mDefaultDialerAppRequests.size();
                            for (int i2 = requestCount2 - 1; i2 >= 0; i2--) {
                                int userId = TelecomLoaderService.this.mDefaultDialerAppRequests.get(i2);
                                TelecomLoaderService.this.mDefaultDialerAppRequests.remove(i2);
                                packageManagerInternal.grantDefaultPermissionsToDefaultDialerApp(packageName, userId);
                            }
                        }
                        if (TelecomLoaderService.this.mDefaultSimCallManagerRequests != null) {
                            TelecomManager telecomManager = (TelecomManager) TelecomLoaderService.this.mContext.getSystemService("telecom");
                            PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager();
                            if (phoneAccount != null) {
                                int requestCount3 = TelecomLoaderService.this.mDefaultSimCallManagerRequests.size();
                                String packageName2 = phoneAccount.getComponentName().getPackageName();
                                for (int i3 = requestCount3 - 1; i3 >= 0; i3--) {
                                    int userId2 = TelecomLoaderService.this.mDefaultSimCallManagerRequests.get(i3);
                                    TelecomLoaderService.this.mDefaultSimCallManagerRequests.remove(i3);
                                    packageManagerInternal.grantDefaultPermissionsToDefaultSimCallManager(packageName2, userId2);
                                }
                            }
                        }
                    }
                }
            } catch (RemoteException e) {
                Slog.w(TelecomLoaderService.TAG, "Failed linking to death.");
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            TelecomLoaderService.this.connectToTelecom();
        }
    }

    public TelecomLoaderService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mContext = context;
        registerDefaultAppProviders();
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 550) {
            registerDefaultAppNotifier();
            registerCarrierConfigChangedReceiver();
            connectToTelecom();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void connectToTelecom() {
        synchronized (this.mLock) {
            if (this.mServiceConnection != null) {
                this.mContext.unbindService(this.mServiceConnection);
                this.mServiceConnection = null;
            }
            TelecomServiceConnection serviceConnection = new TelecomServiceConnection();
            Intent intent = new Intent(SERVICE_ACTION);
            intent.setComponent(SERVICE_COMPONENT);
            if (this.mContext.bindServiceAsUser(intent, serviceConnection, 67108929, UserHandle.SYSTEM)) {
                this.mServiceConnection = serviceConnection;
            }
        }
    }

    private void registerDefaultAppProviders() {
        PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        packageManagerInternal.setSmsAppPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.telecom.TelecomLoaderService.1
            public String[] getPackages(int userId) {
                synchronized (TelecomLoaderService.this.mLock) {
                    if (TelecomLoaderService.this.mServiceConnection == null) {
                        if (TelecomLoaderService.this.mDefaultSmsAppRequests == null) {
                            TelecomLoaderService.this.mDefaultSmsAppRequests = new IntArray();
                        }
                        TelecomLoaderService.this.mDefaultSmsAppRequests.add(userId);
                        return null;
                    }
                    ComponentName smsComponent = SmsApplication.getDefaultSmsApplication(TelecomLoaderService.this.mContext, true);
                    if (smsComponent != null) {
                        return new String[]{smsComponent.getPackageName()};
                    }
                    return null;
                }
            }
        });
        packageManagerInternal.setDialerAppPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.telecom.TelecomLoaderService.2
            public String[] getPackages(int userId) {
                synchronized (TelecomLoaderService.this.mLock) {
                    if (TelecomLoaderService.this.mServiceConnection == null) {
                        if (TelecomLoaderService.this.mDefaultDialerAppRequests == null) {
                            TelecomLoaderService.this.mDefaultDialerAppRequests = new IntArray();
                        }
                        TelecomLoaderService.this.mDefaultDialerAppRequests.add(userId);
                        return null;
                    }
                    String packageName = DefaultDialerManager.getDefaultDialerApplication(TelecomLoaderService.this.mContext);
                    if (packageName != null) {
                        return new String[]{packageName};
                    }
                    return null;
                }
            }
        });
        packageManagerInternal.setSimCallManagerPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.telecom.TelecomLoaderService.3
            public String[] getPackages(int userId) {
                synchronized (TelecomLoaderService.this.mLock) {
                    if (TelecomLoaderService.this.mServiceConnection == null) {
                        if (TelecomLoaderService.this.mDefaultSimCallManagerRequests == null) {
                            TelecomLoaderService.this.mDefaultSimCallManagerRequests = new IntArray();
                        }
                        TelecomLoaderService.this.mDefaultSimCallManagerRequests.add(userId);
                        return null;
                    }
                    TelecomManager telecomManager = (TelecomManager) TelecomLoaderService.this.mContext.getSystemService("telecom");
                    PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager(userId);
                    if (phoneAccount != null) {
                        return new String[]{phoneAccount.getComponentName().getPackageName()};
                    }
                    return null;
                }
            }
        });
    }

    private void registerDefaultAppNotifier() {
        final PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        final Uri defaultSmsAppUri = Settings.Secure.getUriFor("sms_default_application");
        final Uri defaultDialerAppUri = Settings.Secure.getUriFor("dialer_default_application");
        ContentObserver contentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) { // from class: com.android.server.telecom.TelecomLoaderService.4
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (defaultSmsAppUri.equals(uri)) {
                    ComponentName smsComponent = SmsApplication.getDefaultSmsApplication(TelecomLoaderService.this.mContext, true);
                    if (smsComponent != null) {
                        packageManagerInternal.grantDefaultPermissionsToDefaultSmsApp(smsComponent.getPackageName(), userId);
                    }
                } else if (defaultDialerAppUri.equals(uri)) {
                    String packageName = DefaultDialerManager.getDefaultDialerApplication(TelecomLoaderService.this.mContext);
                    if (packageName != null) {
                        packageManagerInternal.grantDefaultPermissionsToDefaultDialerApp(packageName, userId);
                    }
                    TelecomLoaderService.this.updateSimCallManagerPermissions(packageManagerInternal, userId);
                }
            }
        };
        this.mContext.getContentResolver().registerContentObserver(defaultSmsAppUri, false, contentObserver, -1);
        this.mContext.getContentResolver().registerContentObserver(defaultDialerAppUri, false, contentObserver, -1);
    }

    private void registerCarrierConfigChangedReceiver() {
        final PackageManagerInternal packageManagerInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        BroadcastReceiver receiver = new BroadcastReceiver() { // from class: com.android.server.telecom.TelecomLoaderService.5
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                int[] userIds;
                if (intent.getAction().equals("android.telephony.action.CARRIER_CONFIG_CHANGED")) {
                    for (int userId : UserManagerService.getInstance().getUserIds()) {
                        TelecomLoaderService.this.updateSimCallManagerPermissions(packageManagerInternal, userId);
                    }
                }
            }
        };
        this.mContext.registerReceiverAsUser(receiver, UserHandle.ALL, new IntentFilter("android.telephony.action.CARRIER_CONFIG_CHANGED"), null, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSimCallManagerPermissions(PackageManagerInternal packageManagerInternal, int userId) {
        TelecomManager telecomManager = (TelecomManager) this.mContext.getSystemService("telecom");
        PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager(userId);
        if (phoneAccount != null) {
            Slog.i(TAG, "updating sim call manager permissions for userId:" + userId);
            String packageName = phoneAccount.getComponentName().getPackageName();
            packageManagerInternal.grantDefaultPermissionsToDefaultSimCallManager(packageName, userId);
        }
    }
}
