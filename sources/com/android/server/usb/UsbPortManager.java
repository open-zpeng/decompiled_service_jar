package com.android.server.usb;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.hardware.usb.V1_0.IUsb;
import android.hardware.usb.V1_0.PortRole;
import android.hardware.usb.V1_0.PortStatus;
import android.hardware.usb.V1_1.IUsbCallback;
import android.hardware.usb.V1_1.PortStatus_1_1;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Bundle;
import android.os.Handler;
import android.os.IHwBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.usb.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;
import com.android.server.backup.BackupManagerConstants;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
/* loaded from: classes.dex */
public class UsbPortManager {
    private static final int MSG_UPDATE_PORTS = 1;
    private static final String PORT_INFO = "port_info";
    private static final String TAG = "UsbPortManager";
    private static final int USB_HAL_DEATH_COOKIE = 1000;
    private final Context mContext;
    private boolean mSystemReady;
    private static final int COMBO_SOURCE_HOST = UsbPort.combineRolesAsBit(1, 1);
    private static final int COMBO_SOURCE_DEVICE = UsbPort.combineRolesAsBit(1, 2);
    private static final int COMBO_SINK_HOST = UsbPort.combineRolesAsBit(2, 1);
    private static final int COMBO_SINK_DEVICE = UsbPort.combineRolesAsBit(2, 2);
    @GuardedBy("mLock")
    private IUsb mProxy = null;
    private HALCallback mHALCallback = new HALCallback(null, this);
    private final Object mLock = new Object();
    private final ArrayMap<String, PortInfo> mPorts = new ArrayMap<>();
    private final ArrayMap<String, RawPortInfo> mSimulatedPorts = new ArrayMap<>();
    private final Handler mHandler = new Handler(FgThread.get().getLooper()) { // from class: com.android.server.usb.UsbPortManager.1
        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                Bundle b = msg.getData();
                ArrayList<RawPortInfo> PortInfo2 = b.getParcelableArrayList(UsbPortManager.PORT_INFO);
                synchronized (UsbPortManager.this.mLock) {
                    UsbPortManager.this.updatePortsLocked(null, PortInfo2);
                }
            }
        }
    };

    public UsbPortManager(Context context) {
        this.mContext = context;
        try {
            ServiceNotification serviceNotification = new ServiceNotification();
            boolean ret = IServiceManager.getService().registerForNotifications(IUsb.kInterfaceName, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, serviceNotification);
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
        if (this.mProxy != null) {
            try {
                this.mProxy.queryPortStatus();
            } catch (RemoteException e) {
                logAndPrintException(null, "ServiceStart: Failed to query port status", e);
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
                int newMode2 = newMode;
                logAndPrint(4, pw, "Setting USB port mode and role: portId=" + portId + ", currentMode=" + UsbPort.modeToString(currentMode) + ", currentPowerRole=" + UsbPort.powerRoleToString(currentPowerRole) + ", currentDataRole=" + UsbPort.dataRoleToString(currentDataRole) + ", newMode=" + UsbPort.modeToString(newMode2) + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole) + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));
                RawPortInfo sim = this.mSimulatedPorts.get(portId);
                if (sim != null) {
                    sim.currentMode = newMode2;
                    sim.currentPowerRole = newPowerRole;
                    sim.currentDataRole = newDataRole;
                    updatePortsLocked(pw, null);
                } else if (this.mProxy != null) {
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
    /* loaded from: classes.dex */
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
                RawPortInfo temp = new RawPortInfo(current.portName, current.supportedModes, current.currentMode, current.canChangeMode, current.currentPowerRole, current.canChangePowerRole, current.currentDataRole, current.canChangeDataRole);
                newPortInfo.add(temp);
                IndentingPrintWriter indentingPrintWriter = this.pw;
                UsbPortManager.logAndPrint(4, indentingPrintWriter, "ClientCallback: " + current.portName);
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
            Iterator<PortStatus_1_1> it = currentPortStatus.iterator();
            while (it.hasNext()) {
                PortStatus_1_1 current = it.next();
                RawPortInfo temp = new RawPortInfo(current.status.portName, current.supportedModes, current.currentMode, current.status.canChangeMode, current.status.currentPowerRole, current.status.canChangePowerRole, current.status.currentDataRole, current.status.canChangeDataRole);
                newPortInfo.add(temp);
                IndentingPrintWriter indentingPrintWriter = this.pw;
                UsbPortManager.logAndPrint(4, indentingPrintWriter, "ClientCallback: " + current.status.portName);
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
    /* loaded from: classes.dex */
    public final class DeathRecipient implements IHwBinder.DeathRecipient {
        public IndentingPrintWriter pw;

        DeathRecipient(IndentingPrintWriter pw) {
            this.pw = pw;
        }

        @Override // android.os.IHwBinder.DeathRecipient
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

    /* loaded from: classes.dex */
    final class ServiceNotification extends IServiceNotification.Stub {
        ServiceNotification() {
        }

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
            int count = this.mSimulatedPorts.size();
            int i3 = 0;
            while (true) {
                int i4 = i3;
                if (i4 >= count) {
                    break;
                }
                RawPortInfo portInfo = this.mSimulatedPorts.valueAt(i4);
                addOrUpdatePortLocked(portInfo.portId, portInfo.supportedModes, portInfo.currentMode, portInfo.canChangeMode, portInfo.currentPowerRole, portInfo.canChangePowerRole, portInfo.currentDataRole, portInfo.canChangeDataRole, pw);
                i3 = i4 + 1;
            }
        } else {
            Iterator<RawPortInfo> it = newPortInfo.iterator();
            while (it.hasNext()) {
                RawPortInfo currentPortInfo = it.next();
                addOrUpdatePortLocked(currentPortInfo.portId, currentPortInfo.supportedModes, currentPortInfo.currentMode, currentPortInfo.canChangeMode, currentPortInfo.currentPowerRole, currentPortInfo.canChangePowerRole, currentPortInfo.currentDataRole, currentPortInfo.canChangeDataRole, pw);
            }
        }
        int i5 = this.mPorts.size();
        while (true) {
            int i6 = i5 - 1;
            if (i5 > 0) {
                PortInfo portInfo2 = this.mPorts.valueAt(i6);
                int i7 = portInfo2.mDisposition;
                if (i7 != 3) {
                    switch (i7) {
                        case 0:
                            handlePortAddedLocked(portInfo2, pw);
                            portInfo2.mDisposition = 2;
                            continue;
                        case 1:
                            handlePortChangedLocked(portInfo2, pw);
                            portInfo2.mDisposition = 2;
                            continue;
                    }
                } else {
                    this.mPorts.removeAt(i6);
                    portInfo2.mUsbPortStatus = null;
                    handlePortRemovedLocked(portInfo2, pw);
                }
                i5 = i6;
            } else {
                return;
            }
        }
    }

    private void addOrUpdatePortLocked(String portId, int supportedModes, int currentMode, boolean canChangeMode, int currentPowerRole, boolean canChangePowerRole, int currentDataRole, boolean canChangeDataRole, IndentingPrintWriter pw) {
        boolean canChangeMode2;
        int currentMode2;
        int i = currentMode;
        if ((supportedModes & 3) != 3) {
            if (i != 0 && i != supportedModes) {
                logAndPrint(5, pw, "Ignoring inconsistent current mode from USB port driver: supportedModes=" + UsbPort.modeToString(supportedModes) + ", currentMode=" + UsbPort.modeToString(currentMode));
                i = 0;
            }
            currentMode2 = i;
            canChangeMode2 = false;
        } else {
            canChangeMode2 = canChangeMode;
            currentMode2 = i;
        }
        int supportedRoleCombinations = UsbPort.combineRolesAsBit(currentPowerRole, currentDataRole);
        if (currentMode2 != 0 && currentPowerRole != 0 && currentDataRole != 0) {
            if (canChangePowerRole && canChangeDataRole) {
                supportedRoleCombinations |= COMBO_SOURCE_HOST | COMBO_SOURCE_DEVICE | COMBO_SINK_HOST | COMBO_SINK_DEVICE;
            } else if (canChangePowerRole) {
                supportedRoleCombinations = supportedRoleCombinations | UsbPort.combineRolesAsBit(1, currentDataRole) | UsbPort.combineRolesAsBit(2, currentDataRole);
            } else if (canChangeDataRole) {
                supportedRoleCombinations = supportedRoleCombinations | UsbPort.combineRolesAsBit(currentPowerRole, 1) | UsbPort.combineRolesAsBit(currentPowerRole, 2);
            } else if (canChangeMode2) {
                supportedRoleCombinations |= COMBO_SOURCE_HOST | COMBO_SINK_DEVICE;
            }
        }
        int supportedRoleCombinations2 = supportedRoleCombinations;
        PortInfo portInfo = this.mPorts.get(portId);
        if (portInfo == null) {
            PortInfo portInfo2 = new PortInfo(portId, supportedModes);
            portInfo2.setStatus(currentMode2, canChangeMode2, currentPowerRole, canChangePowerRole, currentDataRole, canChangeDataRole, supportedRoleCombinations2);
            this.mPorts.put(portId, portInfo2);
            return;
        }
        if (supportedModes != portInfo.mUsbPort.getSupportedModes()) {
            logAndPrint(5, pw, "Ignoring inconsistent list of supported modes from USB port driver (should be immutable): previous=" + UsbPort.modeToString(portInfo.mUsbPort.getSupportedModes()) + ", current=" + UsbPort.modeToString(supportedModes));
        }
        if (portInfo.setStatus(currentMode2, canChangeMode2, currentPowerRole, canChangePowerRole, currentDataRole, canChangeDataRole, supportedRoleCombinations2)) {
            portInfo.mDisposition = 1;
        } else {
            portInfo.mDisposition = 2;
        }
    }

    private void handlePortAddedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(4, pw, "USB port added: " + portInfo);
        sendPortChangedBroadcastLocked(portInfo);
    }

    private void handlePortChangedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(4, pw, "USB port changed: " + portInfo);
        sendPortChangedBroadcastLocked(portInfo);
    }

    private void handlePortRemovedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(4, pw, "USB port removed: " + portInfo);
        sendPortChangedBroadcastLocked(portInfo);
    }

    private void sendPortChangedBroadcastLocked(PortInfo portInfo) {
        final Intent intent = new Intent("android.hardware.usb.action.USB_PORT_CHANGED");
        intent.addFlags(285212672);
        intent.putExtra("port", (Parcelable) portInfo.mUsbPort);
        intent.putExtra("portStatus", (Parcelable) portInfo.mUsbPortStatus);
        this.mHandler.post(new Runnable() { // from class: com.android.server.usb.-$$Lambda$UsbPortManager$FUqGOOupcl6RrRkZBk-BnrRQyPI
            @Override // java.lang.Runnable
            public final void run() {
                UsbPortManager.this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        });
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
    /* loaded from: classes.dex */
    public static final class PortInfo {
        public static final int DISPOSITION_ADDED = 0;
        public static final int DISPOSITION_CHANGED = 1;
        public static final int DISPOSITION_READY = 2;
        public static final int DISPOSITION_REMOVED = 3;
        public boolean mCanChangeDataRole;
        public boolean mCanChangeMode;
        public boolean mCanChangePowerRole;
        public int mDisposition;
        public final UsbPort mUsbPort;
        public UsbPortStatus mUsbPortStatus;

        public PortInfo(String portId, int supportedModes) {
            this.mUsbPort = new UsbPort(portId, supportedModes);
        }

        public boolean setStatus(int currentMode, boolean canChangeMode, int currentPowerRole, boolean canChangePowerRole, int currentDataRole, boolean canChangeDataRole, int supportedRoleCombinations) {
            this.mCanChangeMode = canChangeMode;
            this.mCanChangePowerRole = canChangePowerRole;
            this.mCanChangeDataRole = canChangeDataRole;
            if (this.mUsbPortStatus == null || this.mUsbPortStatus.getCurrentMode() != currentMode || this.mUsbPortStatus.getCurrentPowerRole() != currentPowerRole || this.mUsbPortStatus.getCurrentDataRole() != currentDataRole || this.mUsbPortStatus.getSupportedRoleCombinations() != supportedRoleCombinations) {
                this.mUsbPortStatus = new UsbPortStatus(currentMode, currentPowerRole, currentDataRole, supportedRoleCombinations);
                return true;
            }
            return false;
        }

        void dump(DualDumpOutputStream dump, String idName, long id) {
            long token = dump.start(idName, id);
            DumpUtils.writePort(dump, "port", 1146756268033L, this.mUsbPort);
            DumpUtils.writePortStatus(dump, "status", 1146756268034L, this.mUsbPortStatus);
            dump.write("can_change_mode", 1133871366147L, this.mCanChangeMode);
            dump.write("can_change_power_role", 1133871366148L, this.mCanChangePowerRole);
            dump.write("can_change_data_role", 1133871366149L, this.mCanChangeDataRole);
            dump.end(token);
        }

        public String toString() {
            return "port=" + this.mUsbPort + ", status=" + this.mUsbPortStatus + ", canChangeMode=" + this.mCanChangeMode + ", canChangePowerRole=" + this.mCanChangePowerRole + ", canChangeDataRole=" + this.mCanChangeDataRole;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class RawPortInfo implements Parcelable {
        public static final Parcelable.Creator<RawPortInfo> CREATOR = new Parcelable.Creator<RawPortInfo>() { // from class: com.android.server.usb.UsbPortManager.RawPortInfo.1
            /* JADX WARN: Can't rename method to resolve collision */
            @Override // android.os.Parcelable.Creator
            public RawPortInfo createFromParcel(Parcel in) {
                String id = in.readString();
                int supportedModes = in.readInt();
                int currentMode = in.readInt();
                boolean canChangeMode = in.readByte() != 0;
                int currentPowerRole = in.readInt();
                boolean canChangePowerRole = in.readByte() != 0;
                int currentDataRole = in.readInt();
                boolean canChangeDataRole = in.readByte() != 0;
                return new RawPortInfo(id, supportedModes, currentMode, canChangeMode, currentPowerRole, canChangePowerRole, currentDataRole, canChangeDataRole);
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
        public int currentDataRole;
        public int currentMode;
        public int currentPowerRole;
        public final String portId;
        public final int supportedModes;

        RawPortInfo(String portId, int supportedModes) {
            this.portId = portId;
            this.supportedModes = supportedModes;
        }

        RawPortInfo(String portId, int supportedModes, int currentMode, boolean canChangeMode, int currentPowerRole, boolean canChangePowerRole, int currentDataRole, boolean canChangeDataRole) {
            this.portId = portId;
            this.supportedModes = supportedModes;
            this.currentMode = currentMode;
            this.canChangeMode = canChangeMode;
            this.currentPowerRole = currentPowerRole;
            this.canChangePowerRole = canChangePowerRole;
            this.currentDataRole = currentDataRole;
            this.canChangeDataRole = canChangeDataRole;
        }

        @Override // android.os.Parcelable
        public int describeContents() {
            return 0;
        }

        @Override // android.os.Parcelable
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(this.portId);
            dest.writeInt(this.supportedModes);
            dest.writeInt(this.currentMode);
            dest.writeByte(this.canChangeMode ? (byte) 1 : (byte) 0);
            dest.writeInt(this.currentPowerRole);
            dest.writeByte(this.canChangePowerRole ? (byte) 1 : (byte) 0);
            dest.writeInt(this.currentDataRole);
            dest.writeByte(this.canChangeDataRole ? (byte) 1 : (byte) 0);
        }
    }
}
