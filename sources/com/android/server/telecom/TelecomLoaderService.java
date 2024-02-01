package com.android.server.telecom;

import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManagerInternal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
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
import com.android.server.pm.permission.DefaultPermissionGrantPolicy;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

/* loaded from: classes2.dex */
public class TelecomLoaderService extends SystemService {
    private static final String SERVICE_ACTION = "com.android.ITelecomService";
    private static final ComponentName SERVICE_COMPONENT = new ComponentName("com.android.server.telecom", "com.android.server.telecom.components.TelecomService");
    private static final String TAG = "TelecomLoaderService";
    private final Context mContext;
    @GuardedBy({"mLock"})
    private IntArray mDefaultSimCallManagerRequests;
    private final Object mLock;
    @GuardedBy({"mLock"})
    private TelecomServiceConnection mServiceConnection;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class TelecomServiceConnection implements ServiceConnection {
        private TelecomServiceConnection() {
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
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
                    if (TelecomLoaderService.this.mDefaultSimCallManagerRequests != null) {
                        DefaultPermissionGrantPolicy permissionPolicy = TelecomLoaderService.this.getDefaultPermissionGrantPolicy();
                        if (TelecomLoaderService.this.mDefaultSimCallManagerRequests != null) {
                            TelecomManager telecomManager = (TelecomManager) TelecomLoaderService.this.mContext.getSystemService("telecom");
                            PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager();
                            if (phoneAccount != null) {
                                int requestCount = TelecomLoaderService.this.mDefaultSimCallManagerRequests.size();
                                String packageName = phoneAccount.getComponentName().getPackageName();
                                for (int i = requestCount - 1; i >= 0; i--) {
                                    int userId = TelecomLoaderService.this.mDefaultSimCallManagerRequests.get(i);
                                    TelecomLoaderService.this.mDefaultSimCallManagerRequests.remove(i);
                                    permissionPolicy.grantDefaultPermissionsToDefaultSimCallManager(packageName, userId);
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

    /* JADX INFO: Access modifiers changed from: private */
    public DefaultPermissionGrantPolicy getDefaultPermissionGrantPolicy() {
        return ((PermissionManagerServiceInternal) LocalServices.getService(PermissionManagerServiceInternal.class)).getDefaultPermissionGrantPolicy();
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
        DefaultPermissionGrantPolicy permissionPolicy = getDefaultPermissionGrantPolicy();
        permissionPolicy.setSmsAppPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.telecom.-$$Lambda$TelecomLoaderService$lBXoYxesURvEmfzumX9uIBbg66M
            public final String[] getPackages(int i) {
                return TelecomLoaderService.this.lambda$registerDefaultAppProviders$0$TelecomLoaderService(i);
            }
        });
        permissionPolicy.setDialerAppPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.telecom.-$$Lambda$TelecomLoaderService$VVmvEgI0M6umDuBUYKUoUMO7-l0
            public final String[] getPackages(int i) {
                return TelecomLoaderService.this.lambda$registerDefaultAppProviders$1$TelecomLoaderService(i);
            }
        });
        permissionPolicy.setSimCallManagerPackagesProvider(new PackageManagerInternal.PackagesProvider() { // from class: com.android.server.telecom.-$$Lambda$TelecomLoaderService$-gelHWcVU9jWWZhCeN99A3Sudtw
            public final String[] getPackages(int i) {
                return TelecomLoaderService.this.lambda$registerDefaultAppProviders$2$TelecomLoaderService(i);
            }
        });
    }

    public /* synthetic */ String[] lambda$registerDefaultAppProviders$0$TelecomLoaderService(int userId) {
        synchronized (this.mLock) {
            if (this.mServiceConnection == null) {
                return null;
            }
            ComponentName smsComponent = SmsApplication.getDefaultSmsApplication(this.mContext, true);
            if (smsComponent != null) {
                return new String[]{smsComponent.getPackageName()};
            }
            return null;
        }
    }

    public /* synthetic */ String[] lambda$registerDefaultAppProviders$1$TelecomLoaderService(int userId) {
        synchronized (this.mLock) {
            if (this.mServiceConnection == null) {
                return null;
            }
            String packageName = DefaultDialerManager.getDefaultDialerApplication(this.mContext);
            if (packageName != null) {
                return new String[]{packageName};
            }
            return null;
        }
    }

    public /* synthetic */ String[] lambda$registerDefaultAppProviders$2$TelecomLoaderService(int userId) {
        synchronized (this.mLock) {
            if (this.mServiceConnection == null) {
                if (this.mDefaultSimCallManagerRequests == null) {
                    this.mDefaultSimCallManagerRequests = new IntArray();
                }
                this.mDefaultSimCallManagerRequests.add(userId);
                return null;
            }
            TelecomManager telecomManager = (TelecomManager) this.mContext.getSystemService("telecom");
            PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager(userId);
            if (phoneAccount != null) {
                return new String[]{phoneAccount.getComponentName().getPackageName()};
            }
            return null;
        }
    }

    private void registerDefaultAppNotifier() {
        final DefaultPermissionGrantPolicy permissionPolicy = getDefaultPermissionGrantPolicy();
        RoleManager roleManager = (RoleManager) this.mContext.getSystemService(RoleManager.class);
        roleManager.addOnRoleHoldersChangedListenerAsUser(this.mContext.getMainExecutor(), new OnRoleHoldersChangedListener() { // from class: com.android.server.telecom.-$$Lambda$TelecomLoaderService$JaEag0KH0v0eOJ4BOrxYzuIZXXo
            public final void onRoleHoldersChanged(String str, UserHandle userHandle) {
                TelecomLoaderService.this.lambda$registerDefaultAppNotifier$3$TelecomLoaderService(permissionPolicy, str, userHandle);
            }
        }, UserHandle.ALL);
    }

    public /* synthetic */ void lambda$registerDefaultAppNotifier$3$TelecomLoaderService(DefaultPermissionGrantPolicy permissionPolicy, String roleName, UserHandle user) {
        updateSimCallManagerPermissions(permissionPolicy, user.getIdentifier());
    }

    private void registerCarrierConfigChangedReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() { // from class: com.android.server.telecom.TelecomLoaderService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                int[] userIds;
                if (intent.getAction().equals("android.telephony.action.CARRIER_CONFIG_CHANGED")) {
                    for (int userId : UserManagerService.getInstance().getUserIds()) {
                        TelecomLoaderService telecomLoaderService = TelecomLoaderService.this;
                        telecomLoaderService.updateSimCallManagerPermissions(telecomLoaderService.getDefaultPermissionGrantPolicy(), userId);
                    }
                }
            }
        };
        this.mContext.registerReceiverAsUser(receiver, UserHandle.ALL, new IntentFilter("android.telephony.action.CARRIER_CONFIG_CHANGED"), null, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateSimCallManagerPermissions(DefaultPermissionGrantPolicy permissionGrantPolicy, int userId) {
        TelecomManager telecomManager = (TelecomManager) this.mContext.getSystemService("telecom");
        PhoneAccountHandle phoneAccount = telecomManager.getSimCallManager(userId);
        if (phoneAccount != null) {
            Slog.i(TAG, "updating sim call manager permissions for userId:" + userId);
            String packageName = phoneAccount.getComponentName().getPackageName();
            permissionGrantPolicy.grantDefaultPermissionsToDefaultSimCallManager(packageName, userId);
        }
    }
}
