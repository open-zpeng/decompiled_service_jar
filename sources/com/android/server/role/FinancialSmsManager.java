package com.android.server.role;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.sms.IFinancialSmsService;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import java.io.PrintWriter;
import java.util.ArrayList;

/* loaded from: classes.dex */
final class FinancialSmsManager {
    private static final String TAG = "FinancialSmsManager";
    private final Context mContext;
    private final Object mLock = new Object();
    @GuardedBy({"mLock"})
    private ArrayList<Command> mQueuedCommands;
    @GuardedBy({"mLock"})
    private IFinancialSmsService mRemoteService;
    @GuardedBy({"mLock"})
    private ServiceConnection mServiceConnection;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public interface Command {
        void run(IFinancialSmsService iFinancialSmsService) throws RemoteException;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public FinancialSmsManager(Context context) {
        this.mContext = context;
    }

    ServiceInfo getServiceInfo() {
        String packageName = this.mContext.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            Slog.w(TAG, "no external services package!");
            return null;
        }
        Intent intent = new Intent("android.service.sms.action.FINANCIAL_SERVICE_INTENT");
        intent.setPackage(packageName);
        ResolveInfo resolveInfo = this.mContext.getPackageManager().resolveService(intent, 4);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.w(TAG, "No valid components found.");
            return null;
        }
        return resolveInfo.serviceInfo;
    }

    private ComponentName getServiceComponentName() {
        ServiceInfo serviceInfo = getServiceInfo();
        if (serviceInfo == null) {
            return null;
        }
        ComponentName name = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        if (!"android.permission.BIND_FINANCIAL_SMS_SERVICE".equals(serviceInfo.permission)) {
            Slog.w(TAG, name.flattenToShortString() + " does not require permission android.permission.BIND_FINANCIAL_SMS_SERVICE");
            return null;
        }
        return name;
    }

    void reset() {
        synchronized (this.mLock) {
            if (this.mServiceConnection != null) {
                this.mContext.unbindService(this.mServiceConnection);
                this.mServiceConnection = null;
            } else {
                Slog.d(TAG, "reset(): service is not bound. Do nothing.");
            }
        }
    }

    private void connectAndRun(Command command) {
        synchronized (this.mLock) {
            if (this.mRemoteService != null) {
                try {
                    command.run(this.mRemoteService);
                } catch (RemoteException e) {
                    Slog.w(TAG, "exception calling service: " + e);
                }
                return;
            }
            if (this.mQueuedCommands == null) {
                this.mQueuedCommands = new ArrayList<>(1);
            }
            this.mQueuedCommands.add(command);
            if (this.mServiceConnection != null) {
                return;
            }
            this.mServiceConnection = new ServiceConnection() { // from class: com.android.server.role.FinancialSmsManager.1
                @Override // android.content.ServiceConnection
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (FinancialSmsManager.this.mLock) {
                        FinancialSmsManager.this.mRemoteService = IFinancialSmsService.Stub.asInterface(service);
                        if (FinancialSmsManager.this.mQueuedCommands != null) {
                            int size = FinancialSmsManager.this.mQueuedCommands.size();
                            for (int i = 0; i < size; i++) {
                                Command queuedCommand = (Command) FinancialSmsManager.this.mQueuedCommands.get(i);
                                try {
                                    queuedCommand.run(FinancialSmsManager.this.mRemoteService);
                                } catch (RemoteException e2) {
                                    Slog.w(FinancialSmsManager.TAG, "exception calling " + name + ": " + e2);
                                }
                            }
                            FinancialSmsManager.this.mQueuedCommands = null;
                        }
                    }
                }

                @Override // android.content.ServiceConnection
                public void onServiceDisconnected(ComponentName name) {
                    synchronized (FinancialSmsManager.this.mLock) {
                        FinancialSmsManager.this.mRemoteService = null;
                    }
                }

                @Override // android.content.ServiceConnection
                public void onBindingDied(ComponentName name) {
                    synchronized (FinancialSmsManager.this.mLock) {
                        FinancialSmsManager.this.mRemoteService = null;
                    }
                }

                @Override // android.content.ServiceConnection
                public void onNullBinding(ComponentName name) {
                    synchronized (FinancialSmsManager.this.mLock) {
                        FinancialSmsManager.this.mRemoteService = null;
                    }
                }
            };
            ComponentName component = getServiceComponentName();
            if (component != null) {
                Intent intent = new Intent();
                intent.setComponent(component);
                long token = Binder.clearCallingIdentity();
                this.mContext.bindServiceAsUser(intent, this.mServiceConnection, 1, UserHandle.getUserHandleForUid(UserHandle.getCallingUserId()));
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getSmsMessages(final RemoteCallback callback, final Bundle params) {
        connectAndRun(new Command() { // from class: com.android.server.role.-$$Lambda$FinancialSmsManager$UHY1FCAaWVBjCaZaVTnVZ0IItrI
            @Override // com.android.server.role.FinancialSmsManager.Command
            public final void run(IFinancialSmsService iFinancialSmsService) {
                iFinancialSmsService.getSmsMessages(callback, params);
            }
        });
    }

    void dump(String prefix, PrintWriter pw) {
        ComponentName impl = getServiceComponentName();
        pw.print(prefix);
        pw.print("User ID: ");
        pw.println(UserHandle.getCallingUserId());
        pw.print(prefix);
        pw.print("Queued commands: ");
        ArrayList<Command> arrayList = this.mQueuedCommands;
        if (arrayList == null) {
            pw.println("N/A");
        } else {
            pw.println(arrayList.size());
        }
        pw.print(prefix);
        pw.print("Implementation: ");
        if (impl == null) {
            pw.println("N/A");
        } else {
            pw.println(impl.flattenToShortString());
        }
    }
}
