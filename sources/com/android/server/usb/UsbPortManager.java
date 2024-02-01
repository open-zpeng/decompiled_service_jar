package com.android.server.usb;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.hardware.usb.V1_0.IUsb;
import android.hardware.usb.V1_0.PortRole;
import android.hardware.usb.V1_0.PortStatus;
import android.hardware.usb.V1_1.PortStatus_1_1;
import android.hardware.usb.V1_2.IUsbCallback;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Bundle;
import android.os.Handler;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.StatsLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.usb.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

/* loaded from: classes2.dex */
public class UsbPortManager {
    private static final int MSG_SYSTEM_READY = 2;
    private static final int MSG_UPDATE_PORTS = 1;
    private static final String PORT_INFO = "port_info";
    private static final String TAG = "UsbPortManager";
    private static final int USB_HAL_DEATH_COOKIE = 1000;
    private final Context mContext;
    private int mIsPortContaminatedNotificationId;
    private NotificationManager mNotificationManager;
    private boolean mSystemReady;
    private static final int COMBO_SOURCE_HOST = UsbPort.combineRolesAsBit(1, 1);
    private static final int COMBO_SOURCE_DEVICE = UsbPort.combineRolesAsBit(1, 2);
    private static final int COMBO_SINK_HOST = UsbPort.combineRolesAsBit(2, 1);
    private static final int COMBO_SINK_DEVICE = UsbPort.combineRolesAsBit(2, 2);
    @GuardedBy({"mLock"})
    private IUsb mProxy = null;
    private HALCallback mHALCallback = new HALCallback(null, this);
    private final Object mLock = new Object();
    private final ArrayMap<String, PortInfo> mPorts = new ArrayMap<>();
    private final ArrayMap<String, RawPortInfo> mSimulatedPorts = new ArrayMap<>();
    private final ArrayMap<String, Boolean> mConnected = new ArrayMap<>();
    private final ArrayMap<String, Integer> mContaminantStatus = new ArrayMap<>();
    private final Handler mHandler = new Handler(FgThread.get().getLooper()) { // from class: com.android.server.usb.UsbPortManager.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i != 1) {
                if (i == 2) {
                    UsbPortManager usbPortManager = UsbPortManager.this;
                    usbPortManager.mNotificationManager = (NotificationManager) usbPortManager.mContext.getSystemService("notification");
                    return;
                }
                return;
            }
            Bundle b = msg.getData();
            ArrayList<RawPortInfo> PortInfo2 = b.getParcelableArrayList(UsbPortManager.PORT_INFO);
            synchronized (UsbPortManager.this.mLock) {
                UsbPortManager.this.updatePortsLocked(null, PortInfo2);
            }
        }
    };

    public UsbPortManager(Context context) {
        this.mContext = context;
        try {
            ServiceNotification serviceNotification = new ServiceNotification();
            boolean ret = IServiceManager.getService().registerForNotifications(IUsb.kInterfaceName, "", serviceNotification);
            if (!ret) {
                logAndPrint(6, null, "Failed to register service start notification");
            }
            connectToProxy(null);
        } catch (RemoteException e) {
            logAndPrintException(null, "Failed to register service start notification", e);
        }
    }

    public void systemReady() {
        this.mSystemReady = true;
        IUsb iUsb = this.mProxy;
        if (iUsb != null) {
            try {
                iUsb.queryPortStatus();
            } catch (RemoteException e) {
                logAndPrintException(null, "ServiceStart: Failed to query port status", e);
            }
        }
        this.mHandler.sendEmptyMessage(2);
    }

    private void updateContaminantNotification() {
        int i;
        int i2;
        PortInfo currentPortInfo = null;
        Resources r = this.mContext.getResources();
        int contaminantStatus = 2;
        for (PortInfo portInfo : this.mPorts.values()) {
            contaminantStatus = portInfo.mUsbPortStatus.getContaminantDetectionStatus();
            if (contaminantStatus != 3) {
                if (contaminantStatus == 1) {
                }
            }
            currentPortInfo = portInfo;
        }
        if (contaminantStatus == 3 && (i2 = this.mIsPortContaminatedNotificationId) != 52) {
            if (i2 == 53) {
                this.mNotificationManager.cancelAsUser(null, i2, UserHandle.ALL);
            }
            this.mIsPortContaminatedNotificationId = 52;
            CharSequence title = r.getText(17041180);
            String channel = SystemNotificationChannels.ALERTS;
            CharSequence message = r.getText(17041179);
            Intent intent = new Intent();
            intent.addFlags(268435456);
            intent.setClassName("com.android.systemui", "com.android.systemui.usb.UsbContaminantActivity");
            intent.putExtra("port", (Parcelable) ParcelableUsbPort.of(currentPortInfo.mUsbPort));
            PendingIntent pi = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 0, null, UserHandle.CURRENT);
            Notification.Builder builder = new Notification.Builder(this.mContext, channel).setOngoing(true).setTicker(title).setColor(this.mContext.getColor(17170460)).setContentIntent(pi).setContentTitle(title).setContentText(message).setVisibility(1).setSmallIcon(17301642).setStyle(new Notification.BigTextStyle().bigText(message));
            Notification notification = builder.build();
            this.mNotificationManager.notifyAsUser(null, this.mIsPortContaminatedNotificationId, notification, UserHandle.ALL);
        } else if (contaminantStatus != 3 && (i = this.mIsPortContaminatedNotificationId) == 52) {
            this.mNotificationManager.cancelAsUser(null, i, UserHandle.ALL);
            this.mIsPortContaminatedNotificationId = 0;
            if (contaminantStatus == 2) {
                this.mIsPortContaminatedNotificationId = 53;
                CharSequence title2 = r.getText(17041182);
                String channel2 = SystemNotificationChannels.ALERTS;
                CharSequence message2 = r.getText(17041181);
                Notification.Builder builder2 = new Notification.Builder(this.mContext, channel2).setSmallIcon(17302837).setTicker(title2).setColor(this.mContext.getColor(17170460)).setContentTitle(title2).setContentText(message2).setVisibility(1).setStyle(new Notification.BigTextStyle().bigText(message2));
                Notification notification2 = builder2.build();
                this.mNotificationManager.notifyAsUser(null, this.mIsPortContaminatedNotificationId, notification2, UserHandle.ALL);
            }
        }
    }

    public UsbPort[] getPorts() {
        UsbPort[] result;
        synchronized (this.mLock) {
            int count = this.mPorts.size();
            result = new UsbPort[count];
            for (int i = 0; i < count; i++) {
                result[i] = this.mPorts.valueAt(i).mUsbPort;
            }
        }
        return result;
    }

    public UsbPortStatus getPortStatus(String portId) {
        UsbPortStatus usbPortStatus;
        synchronized (this.mLock) {
            PortInfo portInfo = this.mPorts.get(portId);
            usbPortStatus = portInfo != null ? portInfo.mUsbPortStatus : null;
        }
        return usbPortStatus;
    }

    public void enableContaminantDetection(String portId, boolean enable, IndentingPrintWriter pw) {
        PortInfo portInfo = this.mPorts.get(portId);
        if (portInfo == null) {
            if (pw != null) {
                pw.println("No such USB port: " + portId);
            }
        } else if (!portInfo.mUsbPort.supportsEnableContaminantPresenceDetection()) {
        } else {
            if (!enable || portInfo.mUsbPortStatus.getContaminantDetectionStatus() == 1) {
                if ((!enable && portInfo.mUsbPortStatus.getContaminantDetectionStatus() == 1) || portInfo.mUsbPortStatus.getContaminantDetectionStatus() == 0) {
                    return;
                }
                try {
                    android.hardware.usb.V1_2.IUsb proxy = android.hardware.usb.V1_2.IUsb.castFrom((IHwInterface) this.mProxy);
                    proxy.enableContaminantPresenceDetection(portId, enable);
                } catch (RemoteException e) {
                    logAndPrintException(pw, "Failed to set contaminant detection", e);
                } catch (ClassCastException e2) {
                    logAndPrintException(pw, "Method only applicable to V1.2 or above implementation", e2);
                }
            }
        }
    }

    public void setPortRoles(String portId, int newPowerRole, int newDataRole, IndentingPrintWriter pw) {
        int newMode;
        synchronized (this.mLock) {
            PortInfo portInfo = this.mPorts.get(portId);
            if (portInfo == null) {
                if (pw != null) {
                    pw.println("No such USB port: " + portId);
                }
            } else if (!portInfo.mUsbPortStatus.isRoleCombinationSupported(newPowerRole, newDataRole)) {
                logAndPrint(6, pw, "Attempted to set USB port into unsupported role combination: portId=" + portId + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole) + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));
            } else {
                int currentDataRole = portInfo.mUsbPortStatus.getCurrentDataRole();
                int currentPowerRole = portInfo.mUsbPortStatus.getCurrentPowerRole();
                if (currentDataRole == newDataRole && currentPowerRole == newPowerRole) {
                    if (pw != null) {
                        pw.println("No change.");
                    }
                    return;
                }
                boolean canChangeMode = portInfo.mCanChangeMode;
                boolean canChangePowerRole = portInfo.mCanChangePowerRole;
                boolean canChangeDataRole = portInfo.mCanChangeDataRole;
                int currentMode = portInfo.mUsbPortStatus.getCurrentMode();
                if ((!canChangePowerRole && currentPowerRole != newPowerRole) || (!canChangeDataRole && currentDataRole != newDataRole)) {
                    if (canChangeMode && newPowerRole == 1 && newDataRole == 1) {
                        newMode = 2;
                    } else if (canChangeMode && newPowerRole == 2 && newDataRole == 2) {
                        newMode = 1;
                    } else {
                        logAndPrint(6, pw, "Found mismatch in supported USB role combinations while attempting to change role: " + portInfo + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole) + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));
                        return;
                    }
                } else {
                    newMode = currentMode;
                }
                logAndPrint(4, pw, "Setting USB port mode and role: portId=" + portId + ", currentMode=" + UsbPort.modeToString(currentMode) + ", currentPowerRole=" + UsbPort.powerRoleToString(currentPowerRole) + ", currentDataRole=" + UsbPort.dataRoleToString(currentDataRole) + ", newMode=" + UsbPort.modeToString(newMode) + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole) + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));
                RawPortInfo sim = this.mSimulatedPorts.get(portId);
                if (sim != null) {
                    sim.currentMode = newMode;
                    sim.currentPowerRole = newPowerRole;
                    sim.currentDataRole = newDataRole;
                    updatePortsLocked(pw, null);
                } else {
                    int newMode2 = newMode;
                    if (this.mProxy != null) {
                        if (currentMode != newMode2) {
                            logAndPrint(6, pw, "Trying to set the USB port mode: portId=" + portId + ", newMode=" + UsbPort.modeToString(newMode2));
                            PortRole newRole = new PortRole();
                            newRole.type = 2;
                            newRole.role = newMode2;
                            try {
                                this.mProxy.switchRole(portId, newRole);
                            } catch (RemoteException e) {
                                logAndPrintException(pw, "Failed to set the USB port mode: portId=" + portId + ", newMode=" + UsbPort.modeToString(newRole.role), e);
                            }
                        } else {
                            if (currentPowerRole != newPowerRole) {
                                PortRole newRole2 = new PortRole();
                                newRole2.type = 1;
                                newRole2.role = newPowerRole;
                                try {
                                    this.mProxy.switchRole(portId, newRole2);
                                } catch (RemoteException e2) {
                                    logAndPrintException(pw, "Failed to set the USB port power role: portId=" + portId + ", newPowerRole=" + UsbPort.powerRoleToString(newRole2.role), e2);
                                    return;
                                }
                            }
                            if (currentDataRole != newDataRole) {
                                PortRole newRole3 = new PortRole();
                                newRole3.type = 0;
                                newRole3.role = newDataRole;
                                try {
                                    this.mProxy.switchRole(portId, newRole3);
                                } catch (RemoteException e3) {
                                    logAndPrintException(pw, "Failed to set the USB port data role: portId=" + portId + ", newDataRole=" + UsbPort.dataRoleToString(newRole3.role), e3);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void addSimulatedPort(String portId, int supportedModes, IndentingPrintWriter pw) {
        synchronized (this.mLock) {
            if (this.mSimulatedPorts.containsKey(portId)) {
                pw.println("Port with same name already exists.  Please remove it first.");
                return;
            }
            pw.println("Adding simulated port: portId=" + portId + ", supportedModes=" + UsbPort.modeToString(supportedModes));
            this.mSimulatedPorts.put(portId, new RawPortInfo(portId, supportedModes));
            updatePortsLocked(pw, null);
        }
    }

    public void connectSimulatedPort(String portId, int mode, boolean canChangeMode, int powerRole, boolean canChangePowerRole, int dataRole, boolean canChangeDataRole, IndentingPrintWriter pw) {
        synchronized (this.mLock) {
            RawPortInfo portInfo = this.mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Cannot connect simulated port which does not exist.");
                return;
            }
            if (mode != 0 && powerRole != 0 && dataRole != 0) {
                if ((portInfo.supportedModes & mode) == 0) {
                    pw.println("Simulated port does not support mode: " + UsbPort.modeToString(mode));
                    return;
                }
                pw.println("Connecting simulated port: portId=" + portId + ", mode=" + UsbPort.modeToString(mode) + ", canChangeMode=" + canChangeMode + ", powerRole=" + UsbPort.powerRoleToString(powerRole) + ", canChangePowerRole=" + canChangePowerRole + ", dataRole=" + UsbPort.dataRoleToString(dataRole) + ", canChangeDataRole=" + canChangeDataRole);
                portInfo.currentMode = mode;
                portInfo.canChangeMode = canChangeMode;
                portInfo.currentPowerRole = powerRole;
                portInfo.canChangePowerRole = canChangePowerRole;
                portInfo.currentDataRole = dataRole;
                portInfo.canChangeDataRole = canChangeDataRole;
                updatePortsLocked(pw, null);
                return;
            }
            pw.println("Cannot connect simulated port in null mode, power role, or data role.");
        }
    }

    public void simulateContaminantStatus(String portId, boolean detected, IndentingPrintWriter pw) {
        int i;
        synchronized (this.mLock) {
            RawPortInfo portInfo = this.mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Simulated port not found.");
                return;
            }
            pw.println("Simulating wet port: portId=" + portId + ", wet=" + detected);
            if (detected) {
                i = 3;
            } else {
                i = 2;
            }
            portInfo.contaminantDetectionStatus = i;
            updatePortsLocked(pw, null);
        }
    }

    public void disconnectSimulatedPort(String portId, IndentingPrintWriter pw) {
        synchronized (this.mLock) {
            RawPortInfo portInfo = this.mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Cannot disconnect simulated port which does not exist.");
                return;
            }
            pw.println("Disconnecting simulated port: portId=" + portId);
            portInfo.currentMode = 0;
            portInfo.canChangeMode = false;
            portInfo.currentPowerRole = 0;
            portInfo.canChangePowerRole = false;
            portInfo.currentDataRole = 0;
            portInfo.canChangeDataRole = false;
            updatePortsLocked(pw, null);
        }
    }

    public void removeSimulatedPort(String portId, IndentingPrintWriter pw) {
        synchronized (this.mLock) {
            int index = this.mSimulatedPorts.indexOfKey(portId);
            if (index < 0) {
                pw.println("Cannot remove simulated port which does not exist.");
                return;
            }
            pw.println("Disconnecting simulated port: portId=" + portId);
            this.mSimulatedPorts.removeAt(index);
            updatePortsLocked(pw, null);
        }
    }

    public void resetSimulation(IndentingPrintWriter pw) {
        synchronized (this.mLock) {
            pw.println("Removing all simulated ports and ending simulation.");
            if (!this.mSimulatedPorts.isEmpty()) {
                this.mSimulatedPorts.clear();
                updatePortsLocked(pw, null);
            }
        }
    }

    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);
        synchronized (this.mLock) {
            dump.write("is_simulation_active", 1133871366145L, !this.mSimulatedPorts.isEmpty());
            for (PortInfo portInfo : this.mPorts.values()) {
                portInfo.dump(dump, "usb_ports", 2246267895810L);
            }
        }
        dump.end(token);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class HALCallback extends IUsbCallback.Stub {
        public UsbPortManager portManager;
        public IndentingPrintWriter pw;

        HALCallback(IndentingPrintWriter pw, UsbPortManager portManager) {
            this.pw = pw;
            this.portManager = portManager;
        }

        @Override // android.hardware.usb.V1_0.IUsbCallback
        public void notifyPortStatusChange(ArrayList<PortStatus> currentPortStatus, int retval) {
            if (!this.portManager.mSystemReady) {
                return;
            }
            if (retval != 0) {
                UsbPortManager.logAndPrint(6, this.pw, "port status enquiry failed");
                return;
            }
            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();
            Iterator<PortStatus> it = currentPortStatus.iterator();
            while (it.hasNext()) {
                PortStatus current = it.next();
                RawPortInfo temp = new RawPortInfo(current.portName, current.supportedModes, 0, current.currentMode, current.canChangeMode, current.currentPowerRole, current.canChangePowerRole, current.currentDataRole, current.canChangeDataRole, false, 0, false, 0);
                newPortInfo.add(temp);
                IndentingPrintWriter indentingPrintWriter = this.pw;
                UsbPortManager.logAndPrint(4, indentingPrintWriter, "ClientCallback V1_0: " + current.portName);
            }
            Message message = this.portManager.mHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(UsbPortManager.PORT_INFO, newPortInfo);
            message.what = 1;
            message.setData(bundle);
            this.portManager.mHandler.sendMessage(message);
        }

        @Override // android.hardware.usb.V1_1.IUsbCallback
        public void notifyPortStatusChange_1_1(ArrayList<PortStatus_1_1> currentPortStatus, int retval) {
            if (!this.portManager.mSystemReady) {
                return;
            }
            if (retval != 0) {
                UsbPortManager.logAndPrint(6, this.pw, "port status enquiry failed");
                return;
            }
            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();
            int numStatus = currentPortStatus.size();
            for (int i = 0; i < numStatus; i++) {
                PortStatus_1_1 current = currentPortStatus.get(i);
                RawPortInfo temp = new RawPortInfo(current.status.portName, current.supportedModes, 0, current.currentMode, current.status.canChangeMode, current.status.currentPowerRole, current.status.canChangePowerRole, current.status.currentDataRole, current.status.canChangeDataRole, false, 0, false, 0);
                newPortInfo.add(temp);
                IndentingPrintWriter indentingPrintWriter = this.pw;
                UsbPortManager.logAndPrint(4, indentingPrintWriter, "ClientCallback V1_1: " + current.status.portName);
            }
            Message message = this.portManager.mHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(UsbPortManager.PORT_INFO, newPortInfo);
            message.what = 1;
            message.setData(bundle);
            this.portManager.mHandler.sendMessage(message);
        }

        @Override // android.hardware.usb.V1_2.IUsbCallback
        public void notifyPortStatusChange_1_2(ArrayList<android.hardware.usb.V1_2.PortStatus> currentPortStatus, int retval) {
            if (!this.portManager.mSystemReady) {
                return;
            }
            if (retval != 0) {
                UsbPortManager.logAndPrint(6, this.pw, "port status enquiry failed");
                return;
            }
            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();
            int numStatus = currentPortStatus.size();
            int i = 0;
            while (i < numStatus) {
                android.hardware.usb.V1_2.PortStatus current = currentPortStatus.get(i);
                String str = current.status_1_1.status.portName;
                int i2 = current.status_1_1.supportedModes;
                int i3 = current.supportedContaminantProtectionModes;
                int i4 = current.status_1_1.currentMode;
                boolean z = current.status_1_1.status.canChangeMode;
                int i5 = current.status_1_1.status.currentPowerRole;
                boolean z2 = current.status_1_1.status.canChangePowerRole;
                int i6 = current.status_1_1.status.currentDataRole;
                boolean z3 = current.status_1_1.status.canChangeDataRole;
                boolean z4 = current.supportsEnableContaminantPresenceProtection;
                int numStatus2 = numStatus;
                int numStatus3 = current.contaminantProtectionStatus;
                boolean z5 = current.supportsEnableContaminantPresenceDetection;
                int i7 = i;
                int i8 = current.contaminantDetectionStatus;
                RawPortInfo temp = new RawPortInfo(str, i2, i3, i4, z, i5, z2, i6, z3, z4, numStatus3, z5, i8);
                newPortInfo.add(temp);
                IndentingPrintWriter indentingPrintWriter = this.pw;
                UsbPortManager.logAndPrint(4, indentingPrintWriter, "ClientCallback V1_2: " + current.status_1_1.status.portName);
                i = i7 + 1;
                numStatus = numStatus2;
            }
            Message message = this.portManager.mHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(UsbPortManager.PORT_INFO, newPortInfo);
            message.what = 1;
            message.setData(bundle);
            this.portManager.mHandler.sendMessage(message);
        }

        @Override // android.hardware.usb.V1_0.IUsbCallback
        public void notifyRoleSwitchStatus(String portName, PortRole role, int retval) {
            if (retval == 0) {
                IndentingPrintWriter indentingPrintWriter = this.pw;
                UsbPortManager.logAndPrint(4, indentingPrintWriter, portName + " role switch successful");
                return;
            }
            IndentingPrintWriter indentingPrintWriter2 = this.pw;
            UsbPortManager.logAndPrint(6, indentingPrintWriter2, portName + " role switch failed");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public final class DeathRecipient implements IHwBinder.DeathRecipient {
        public IndentingPrintWriter pw;

        DeathRecipient(IndentingPrintWriter pw) {
            this.pw = pw;
        }

        public void serviceDied(long cookie) {
            if (cookie == 1000) {
                IndentingPrintWriter indentingPrintWriter = this.pw;
                UsbPortManager.logAndPrint(6, indentingPrintWriter, "Usb hal service died cookie: " + cookie);
                synchronized (UsbPortManager.this.mLock) {
                    UsbPortManager.this.mProxy = null;
                }
            }
        }
    }

    /* loaded from: classes2.dex */
    final class ServiceNotification extends IServiceNotification.Stub {
        ServiceNotification() {
        }

        @Override // android.hidl.manager.V1_0.IServiceNotification
        public void onRegistration(String fqName, String name, boolean preexisting) {
            UsbPortManager.logAndPrint(4, null, "Usb hal service started " + fqName + " " + name);
            UsbPortManager.this.connectToProxy(null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void connectToProxy(IndentingPrintWriter pw) {
        synchronized (this.mLock) {
            if (this.mProxy != null) {
                return;
            }
            try {
                this.mProxy = IUsb.getService();
                this.mProxy.linkToDeath(new DeathRecipient(pw), 1000L);
                this.mProxy.setCallback(this.mHALCallback);
                this.mProxy.queryPortStatus();
            } catch (RemoteException e) {
                logAndPrintException(pw, "connectToProxy: usb hal service not responding", e);
            } catch (NoSuchElementException e2) {
                logAndPrintException(pw, "connectToProxy: usb hal service not found. Did the service fail to start?", e2);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePortsLocked(IndentingPrintWriter pw, ArrayList<RawPortInfo> newPortInfo) {
        int i = this.mPorts.size();
        while (true) {
            int i2 = i - 1;
            if (i <= 0) {
                break;
            }
            this.mPorts.valueAt(i2).mDisposition = 3;
            i = i2;
        }
        if (!this.mSimulatedPorts.isEmpty()) {
            int i3 = 0;
            for (int count = this.mSimulatedPorts.size(); i3 < count; count = count) {
                RawPortInfo portInfo = this.mSimulatedPorts.valueAt(i3);
                addOrUpdatePortLocked(portInfo.portId, portInfo.supportedModes, portInfo.supportedContaminantProtectionModes, portInfo.currentMode, portInfo.canChangeMode, portInfo.currentPowerRole, portInfo.canChangePowerRole, portInfo.currentDataRole, portInfo.canChangeDataRole, portInfo.supportsEnableContaminantPresenceProtection, portInfo.contaminantProtectionStatus, portInfo.supportsEnableContaminantPresenceDetection, portInfo.contaminantDetectionStatus, pw);
                i3++;
            }
        } else {
            Iterator<RawPortInfo> it = newPortInfo.iterator();
            while (it.hasNext()) {
                RawPortInfo currentPortInfo = it.next();
                addOrUpdatePortLocked(currentPortInfo.portId, currentPortInfo.supportedModes, currentPortInfo.supportedContaminantProtectionModes, currentPortInfo.currentMode, currentPortInfo.canChangeMode, currentPortInfo.currentPowerRole, currentPortInfo.canChangePowerRole, currentPortInfo.currentDataRole, currentPortInfo.canChangeDataRole, currentPortInfo.supportsEnableContaminantPresenceProtection, currentPortInfo.contaminantProtectionStatus, currentPortInfo.supportsEnableContaminantPresenceDetection, currentPortInfo.contaminantDetectionStatus, pw);
            }
        }
        int i4 = this.mPorts.size();
        while (true) {
            int i5 = i4 - 1;
            if (i4 > 0) {
                PortInfo portInfo2 = this.mPorts.valueAt(i5);
                int i6 = portInfo2.mDisposition;
                if (i6 == 0) {
                    handlePortAddedLocked(portInfo2, pw);
                    portInfo2.mDisposition = 2;
                } else if (i6 == 1) {
                    handlePortChangedLocked(portInfo2, pw);
                    portInfo2.mDisposition = 2;
                } else if (i6 == 3) {
                    this.mPorts.removeAt(i5);
                    portInfo2.mUsbPortStatus = null;
                    handlePortRemovedLocked(portInfo2, pw);
                }
                i4 = i5;
            } else {
                return;
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:26:0x00a5  */
    /* JADX WARN: Removed duplicated region for block: B:27:0x00e1  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void addOrUpdatePortLocked(java.lang.String r24, int r25, int r26, int r27, boolean r28, int r29, boolean r30, int r31, boolean r32, boolean r33, int r34, boolean r35, int r36, com.android.internal.util.IndentingPrintWriter r37) {
        /*
            Method dump skipped, instructions count: 389
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbPortManager.addOrUpdatePortLocked(java.lang.String, int, int, int, boolean, int, boolean, int, boolean, boolean, int, boolean, int, com.android.internal.util.IndentingPrintWriter):void");
    }

    private void handlePortLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        sendPortChangedBroadcastLocked(portInfo);
        logToStatsd(portInfo, pw);
        updateContaminantNotification();
    }

    private void handlePortAddedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(4, pw, "USB port added: " + portInfo);
        handlePortLocked(portInfo, pw);
    }

    private void handlePortChangedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(4, pw, "USB port changed: " + portInfo);
        enableContaminantDetectionIfNeeded(portInfo, pw);
        handlePortLocked(portInfo, pw);
    }

    private void handlePortRemovedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(4, pw, "USB port removed: " + portInfo);
        handlePortLocked(portInfo, pw);
    }

    private static int convertContaminantDetectionStatusToProto(int contaminantDetectionStatus) {
        if (contaminantDetectionStatus != 0) {
            if (contaminantDetectionStatus != 1) {
                if (contaminantDetectionStatus != 2) {
                    if (contaminantDetectionStatus == 3) {
                        return 4;
                    }
                    return 0;
                }
                return 3;
            }
            return 2;
        }
        return 1;
    }

    private void sendPortChangedBroadcastLocked(PortInfo portInfo) {
        final Intent intent = new Intent("android.hardware.usb.action.USB_PORT_CHANGED");
        intent.addFlags(285212672);
        intent.putExtra("port", (Parcelable) ParcelableUsbPort.of(portInfo.mUsbPort));
        intent.putExtra("portStatus", (Parcelable) portInfo.mUsbPortStatus);
        this.mHandler.post(new Runnable() { // from class: com.android.server.usb.-$$Lambda$UsbPortManager$FUqGOOupcl6RrRkZBk-BnrRQyPI
            @Override // java.lang.Runnable
            public final void run() {
                UsbPortManager.this.lambda$sendPortChangedBroadcastLocked$0$UsbPortManager(intent);
            }
        });
    }

    public /* synthetic */ void lambda$sendPortChangedBroadcastLocked$0$UsbPortManager(Intent intent) {
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL, "android.permission.MANAGE_USB");
    }

    private void enableContaminantDetectionIfNeeded(PortInfo portInfo, IndentingPrintWriter pw) {
        if (this.mConnected.containsKey(portInfo.mUsbPort.getId()) && this.mConnected.get(portInfo.mUsbPort.getId()).booleanValue() && !portInfo.mUsbPortStatus.isConnected() && portInfo.mUsbPortStatus.getContaminantDetectionStatus() == 1) {
            enableContaminantDetection(portInfo.mUsbPort.getId(), true, pw);
        }
    }

    private void logToStatsd(PortInfo portInfo, IndentingPrintWriter pw) {
        if (portInfo.mUsbPortStatus == null) {
            if (this.mConnected.containsKey(portInfo.mUsbPort.getId())) {
                if (this.mConnected.get(portInfo.mUsbPort.getId()).booleanValue()) {
                    StatsLog.write(70, 0, portInfo.mUsbPort.getId(), portInfo.mLastConnectDurationMillis);
                }
                this.mConnected.remove(portInfo.mUsbPort.getId());
            }
            if (this.mContaminantStatus.containsKey(portInfo.mUsbPort.getId())) {
                if (this.mContaminantStatus.get(portInfo.mUsbPort.getId()).intValue() == 3) {
                    StatsLog.write(146, portInfo.mUsbPort.getId(), convertContaminantDetectionStatusToProto(2));
                }
                this.mContaminantStatus.remove(portInfo.mUsbPort.getId());
                return;
            }
            return;
        }
        if (!this.mConnected.containsKey(portInfo.mUsbPort.getId()) || this.mConnected.get(portInfo.mUsbPort.getId()).booleanValue() != portInfo.mUsbPortStatus.isConnected()) {
            this.mConnected.put(portInfo.mUsbPort.getId(), Boolean.valueOf(portInfo.mUsbPortStatus.isConnected()));
            StatsLog.write(70, portInfo.mUsbPortStatus.isConnected() ? 1 : 0, portInfo.mUsbPort.getId(), portInfo.mLastConnectDurationMillis);
        }
        if (!this.mContaminantStatus.containsKey(portInfo.mUsbPort.getId()) || this.mContaminantStatus.get(portInfo.mUsbPort.getId()).intValue() != portInfo.mUsbPortStatus.getContaminantDetectionStatus()) {
            this.mContaminantStatus.put(portInfo.mUsbPort.getId(), Integer.valueOf(portInfo.mUsbPortStatus.getContaminantDetectionStatus()));
            StatsLog.write(146, portInfo.mUsbPort.getId(), convertContaminantDetectionStatusToProto(portInfo.mUsbPortStatus.getContaminantDetectionStatus()));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void logAndPrint(int priority, IndentingPrintWriter pw, String msg) {
        Slog.println(priority, TAG, msg);
        if (pw != null) {
            pw.println(msg);
        }
    }

    private static void logAndPrintException(IndentingPrintWriter pw, String msg, Exception e) {
        Slog.e(TAG, msg, e);
        if (pw != null) {
            pw.println(msg + e);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class PortInfo {
        public static final int DISPOSITION_ADDED = 0;
        public static final int DISPOSITION_CHANGED = 1;
        public static final int DISPOSITION_READY = 2;
        public static final int DISPOSITION_REMOVED = 3;
        public boolean mCanChangeDataRole;
        public boolean mCanChangeMode;
        public boolean mCanChangePowerRole;
        public long mConnectedAtMillis;
        public int mDisposition;
        public long mLastConnectDurationMillis;
        public final UsbPort mUsbPort;
        public UsbPortStatus mUsbPortStatus;

        PortInfo(UsbManager usbManager, String portId, int supportedModes, int supportedContaminantProtectionModes, boolean supportsEnableContaminantPresenceDetection, boolean supportsEnableContaminantPresenceProtection) {
            this.mUsbPort = new UsbPort(usbManager, portId, supportedModes, supportedContaminantProtectionModes, supportsEnableContaminantPresenceDetection, supportsEnableContaminantPresenceProtection);
        }

        /* JADX WARN: Code restructure failed: missing block: B:11:0x0037, code lost:
            if (r17.mUsbPortStatus.getSupportedRoleCombinations() == r24) goto L11;
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public boolean setStatus(int r18, boolean r19, int r20, boolean r21, int r22, boolean r23, int r24) {
            /*
                r17 = this;
                r0 = r17
                r1 = 0
                r2 = r19
                r0.mCanChangeMode = r2
                r3 = r21
                r0.mCanChangePowerRole = r3
                r4 = r23
                r0.mCanChangeDataRole = r4
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                if (r5 == 0) goto L49
                int r5 = r5.getCurrentMode()
                r13 = r18
                if (r5 != r13) goto L42
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getCurrentPowerRole()
                r14 = r20
                if (r5 != r14) goto L3d
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getCurrentDataRole()
                r15 = r22
                if (r5 != r15) goto L3a
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getSupportedRoleCombinations()
                r12 = r24
                if (r5 == r12) goto L67
                goto L51
            L3a:
                r12 = r24
                goto L51
            L3d:
                r15 = r22
                r12 = r24
                goto L51
            L42:
                r14 = r20
                r15 = r22
                r12 = r24
                goto L51
            L49:
                r13 = r18
                r14 = r20
                r15 = r22
                r12 = r24
            L51:
                android.hardware.usb.UsbPortStatus r5 = new android.hardware.usb.UsbPortStatus
                r11 = 0
                r16 = 0
                r6 = r5
                r7 = r18
                r8 = r20
                r9 = r22
                r10 = r24
                r12 = r16
                r6.<init>(r7, r8, r9, r10, r11, r12)
                r0.mUsbPortStatus = r5
                r1 = 1
            L67:
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                boolean r5 = r5.isConnected()
                r6 = 0
                if (r5 == 0) goto L80
                long r8 = r0.mConnectedAtMillis
                int r5 = (r8 > r6 ? 1 : (r8 == r6 ? 0 : -1))
                if (r5 != 0) goto L80
                long r8 = android.os.SystemClock.elapsedRealtime()
                r0.mConnectedAtMillis = r8
                r0.mLastConnectDurationMillis = r6
                goto L99
            L80:
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                boolean r5 = r5.isConnected()
                if (r5 != 0) goto L99
                long r8 = r0.mConnectedAtMillis
                int r5 = (r8 > r6 ? 1 : (r8 == r6 ? 0 : -1))
                if (r5 == 0) goto L99
                long r8 = android.os.SystemClock.elapsedRealtime()
                long r10 = r0.mConnectedAtMillis
                long r8 = r8 - r10
                r0.mLastConnectDurationMillis = r8
                r0.mConnectedAtMillis = r6
            L99:
                return r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbPortManager.PortInfo.setStatus(int, boolean, int, boolean, int, boolean, int):boolean");
        }

        /* JADX WARN: Code restructure failed: missing block: B:15:0x004b, code lost:
            if (r16.mUsbPortStatus.getContaminantDetectionStatus() == r25) goto L15;
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public boolean setStatus(int r17, boolean r18, int r19, boolean r20, int r21, boolean r22, int r23, int r24, int r25) {
            /*
                r16 = this;
                r0 = r16
                r1 = 0
                r2 = r18
                r0.mCanChangeMode = r2
                r3 = r20
                r0.mCanChangePowerRole = r3
                r4 = r22
                r0.mCanChangeDataRole = r4
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                if (r5 == 0) goto L71
                int r5 = r5.getCurrentMode()
                r13 = r17
                if (r5 != r13) goto L66
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getCurrentPowerRole()
                r14 = r19
                if (r5 != r14) goto L5d
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getCurrentDataRole()
                r15 = r21
                if (r5 != r15) goto L56
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getSupportedRoleCombinations()
                r12 = r23
                if (r5 != r12) goto L51
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getContaminantProtectionStatus()
                r11 = r24
                if (r5 != r11) goto L4e
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                int r5 = r5.getContaminantDetectionStatus()
                r10 = r25
                if (r5 == r10) goto L92
                goto L7d
            L4e:
                r10 = r25
                goto L7d
            L51:
                r11 = r24
                r10 = r25
                goto L7d
            L56:
                r12 = r23
                r11 = r24
                r10 = r25
                goto L7d
            L5d:
                r15 = r21
                r12 = r23
                r11 = r24
                r10 = r25
                goto L7d
            L66:
                r14 = r19
                r15 = r21
                r12 = r23
                r11 = r24
                r10 = r25
                goto L7d
            L71:
                r13 = r17
                r14 = r19
                r15 = r21
                r12 = r23
                r11 = r24
                r10 = r25
            L7d:
                android.hardware.usb.UsbPortStatus r5 = new android.hardware.usb.UsbPortStatus
                r6 = r5
                r7 = r17
                r8 = r19
                r9 = r21
                r10 = r23
                r11 = r24
                r12 = r25
                r6.<init>(r7, r8, r9, r10, r11, r12)
                r0.mUsbPortStatus = r5
                r1 = 1
            L92:
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                boolean r5 = r5.isConnected()
                r6 = 0
                if (r5 == 0) goto Lab
                long r8 = r0.mConnectedAtMillis
                int r5 = (r8 > r6 ? 1 : (r8 == r6 ? 0 : -1))
                if (r5 != 0) goto Lab
                long r8 = android.os.SystemClock.elapsedRealtime()
                r0.mConnectedAtMillis = r8
                r0.mLastConnectDurationMillis = r6
                goto Lc4
            Lab:
                android.hardware.usb.UsbPortStatus r5 = r0.mUsbPortStatus
                boolean r5 = r5.isConnected()
                if (r5 != 0) goto Lc4
                long r8 = r0.mConnectedAtMillis
                int r5 = (r8 > r6 ? 1 : (r8 == r6 ? 0 : -1))
                if (r5 == 0) goto Lc4
                long r8 = android.os.SystemClock.elapsedRealtime()
                long r10 = r0.mConnectedAtMillis
                long r8 = r8 - r10
                r0.mLastConnectDurationMillis = r8
                r0.mConnectedAtMillis = r6
            Lc4:
                return r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbPortManager.PortInfo.setStatus(int, boolean, int, boolean, int, boolean, int, int, int):boolean");
        }

        void dump(DualDumpOutputStream dump, String idName, long id) {
            long token = dump.start(idName, id);
            DumpUtils.writePort(dump, "port", 1146756268033L, this.mUsbPort);
            DumpUtils.writePortStatus(dump, "status", 1146756268034L, this.mUsbPortStatus);
            dump.write("can_change_mode", 1133871366147L, this.mCanChangeMode);
            dump.write("can_change_power_role", 1133871366148L, this.mCanChangePowerRole);
            dump.write("can_change_data_role", 1133871366149L, this.mCanChangeDataRole);
            dump.write("connected_at_millis", 1112396529670L, this.mConnectedAtMillis);
            dump.write("last_connect_duration_millis", 1112396529671L, this.mLastConnectDurationMillis);
            dump.end(token);
        }

        public String toString() {
            return "port=" + this.mUsbPort + ", status=" + this.mUsbPortStatus + ", canChangeMode=" + this.mCanChangeMode + ", canChangePowerRole=" + this.mCanChangePowerRole + ", canChangeDataRole=" + this.mCanChangeDataRole + ", connectedAtMillis=" + this.mConnectedAtMillis + ", lastConnectDurationMillis=" + this.mLastConnectDurationMillis;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class RawPortInfo implements Parcelable {
        public static final Parcelable.Creator<RawPortInfo> CREATOR = new Parcelable.Creator<RawPortInfo>() { // from class: com.android.server.usb.UsbPortManager.RawPortInfo.1
            /* JADX WARN: Can't rename method to resolve collision */
            @Override // android.os.Parcelable.Creator
            public RawPortInfo createFromParcel(Parcel in) {
                String id = in.readString();
                int supportedModes = in.readInt();
                int supportedContaminantProtectionModes = in.readInt();
                int currentMode = in.readInt();
                boolean canChangeMode = in.readByte() != 0;
                int currentPowerRole = in.readInt();
                boolean canChangePowerRole = in.readByte() != 0;
                int currentDataRole = in.readInt();
                boolean canChangeDataRole = in.readByte() != 0;
                boolean supportsEnableContaminantPresenceProtection = in.readBoolean();
                int contaminantProtectionStatus = in.readInt();
                boolean supportsEnableContaminantPresenceDetection = in.readBoolean();
                int contaminantDetectionStatus = in.readInt();
                return new RawPortInfo(id, supportedModes, supportedContaminantProtectionModes, currentMode, canChangeMode, currentPowerRole, canChangePowerRole, currentDataRole, canChangeDataRole, supportsEnableContaminantPresenceProtection, contaminantProtectionStatus, supportsEnableContaminantPresenceDetection, contaminantDetectionStatus);
            }

            /* JADX WARN: Can't rename method to resolve collision */
            @Override // android.os.Parcelable.Creator
            public RawPortInfo[] newArray(int size) {
                return new RawPortInfo[size];
            }
        };
        public boolean canChangeDataRole;
        public boolean canChangeMode;
        public boolean canChangePowerRole;
        public int contaminantDetectionStatus;
        public int contaminantProtectionStatus;
        public int currentDataRole;
        public int currentMode;
        public int currentPowerRole;
        public final String portId;
        public final int supportedContaminantProtectionModes;
        public final int supportedModes;
        public boolean supportsEnableContaminantPresenceDetection;
        public boolean supportsEnableContaminantPresenceProtection;

        RawPortInfo(String portId, int supportedModes) {
            this.portId = portId;
            this.supportedModes = supportedModes;
            this.supportedContaminantProtectionModes = 0;
            this.supportsEnableContaminantPresenceProtection = false;
            this.contaminantProtectionStatus = 0;
            this.supportsEnableContaminantPresenceDetection = false;
            this.contaminantDetectionStatus = 0;
        }

        RawPortInfo(String portId, int supportedModes, int supportedContaminantProtectionModes, int currentMode, boolean canChangeMode, int currentPowerRole, boolean canChangePowerRole, int currentDataRole, boolean canChangeDataRole, boolean supportsEnableContaminantPresenceProtection, int contaminantProtectionStatus, boolean supportsEnableContaminantPresenceDetection, int contaminantDetectionStatus) {
            this.portId = portId;
            this.supportedModes = supportedModes;
            this.supportedContaminantProtectionModes = supportedContaminantProtectionModes;
            this.currentMode = currentMode;
            this.canChangeMode = canChangeMode;
            this.currentPowerRole = currentPowerRole;
            this.canChangePowerRole = canChangePowerRole;
            this.currentDataRole = currentDataRole;
            this.canChangeDataRole = canChangeDataRole;
            this.supportsEnableContaminantPresenceProtection = supportsEnableContaminantPresenceProtection;
            this.contaminantProtectionStatus = contaminantProtectionStatus;
            this.supportsEnableContaminantPresenceDetection = supportsEnableContaminantPresenceDetection;
            this.contaminantDetectionStatus = contaminantDetectionStatus;
        }

        @Override // android.os.Parcelable
        public int describeContents() {
            return 0;
        }

        @Override // android.os.Parcelable
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(this.portId);
            dest.writeInt(this.supportedModes);
            dest.writeInt(this.supportedContaminantProtectionModes);
            dest.writeInt(this.currentMode);
            dest.writeByte(this.canChangeMode ? (byte) 1 : (byte) 0);
            dest.writeInt(this.currentPowerRole);
            dest.writeByte(this.canChangePowerRole ? (byte) 1 : (byte) 0);
            dest.writeInt(this.currentDataRole);
            dest.writeByte(this.canChangeDataRole ? (byte) 1 : (byte) 0);
            dest.writeBoolean(this.supportsEnableContaminantPresenceProtection);
            dest.writeInt(this.contaminantProtectionStatus);
            dest.writeBoolean(this.supportsEnableContaminantPresenceDetection);
            dest.writeInt(this.contaminantDetectionStatus);
        }
    }
}
