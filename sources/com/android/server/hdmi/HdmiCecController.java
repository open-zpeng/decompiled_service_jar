package com.android.server.hdmi;

import android.hardware.hdmi.HdmiPortInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.HdmiAnnotations;
import com.android.server.hdmi.HdmiControlService;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;
import libcore.util.EmptyArray;
import sun.util.locale.LanguageTag;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class HdmiCecController {
    private static final byte[] EMPTY_BODY = EmptyArray.BYTE;
    private static final int MAX_CEC_MESSAGE_HISTORY = 20;
    private static final int NUM_LOGICAL_ADDRESS = 16;
    private static final String TAG = "HdmiCecController";
    private Handler mControlHandler;
    private Handler mIoHandler;
    private volatile long mNativePtr;
    private final HdmiControlService mService;
    private final Predicate<Integer> mRemoteDeviceAddressPredicate = new Predicate<Integer>() { // from class: com.android.server.hdmi.HdmiCecController.1
        @Override // java.util.function.Predicate
        public boolean test(Integer address) {
            return !HdmiCecController.this.isAllocatedLocalDeviceAddress(address.intValue());
        }
    };
    private final Predicate<Integer> mSystemAudioAddressPredicate = new Predicate<Integer>() { // from class: com.android.server.hdmi.HdmiCecController.2
        @Override // java.util.function.Predicate
        public boolean test(Integer address) {
            return HdmiUtils.getTypeFromAddress(address.intValue()) == 5;
        }
    };
    private final SparseArray<HdmiCecLocalDevice> mLocalDevices = new SparseArray<>();
    private final ArrayBlockingQueue<MessageHistoryRecord> mMessageHistory = new ArrayBlockingQueue<>(20);

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface AllocateAddressCallback {
        void onAllocated(int i, int i2);
    }

    private static native int nativeAddLogicalAddress(long j, int i);

    private static native void nativeClearLogicalAddress(long j);

    private static native void nativeEnableAudioReturnChannel(long j, int i, boolean z);

    private static native int nativeGetPhysicalAddress(long j);

    private static native HdmiPortInfo[] nativeGetPortInfos(long j);

    private static native int nativeGetVendorId(long j);

    private static native int nativeGetVersion(long j);

    private static native long nativeInit(HdmiCecController hdmiCecController, MessageQueue messageQueue);

    private static native boolean nativeIsConnected(long j, int i);

    /* JADX INFO: Access modifiers changed from: private */
    public static native int nativeSendCecCommand(long j, int i, int i2, byte[] bArr);

    private static native void nativeSetLanguage(long j, String str);

    private static native void nativeSetOption(long j, int i, boolean z);

    private HdmiCecController(HdmiControlService service) {
        this.mService = service;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static HdmiCecController create(HdmiControlService service) {
        HdmiCecController controller = new HdmiCecController(service);
        long nativePtr = nativeInit(controller, service.getServiceLooper().getQueue());
        if (nativePtr == 0) {
            return null;
        }
        controller.init(nativePtr);
        return controller;
    }

    private void init(long nativePtr) {
        this.mIoHandler = new Handler(this.mService.getIoLooper());
        this.mControlHandler = new Handler(this.mService.getServiceLooper());
        this.mNativePtr = nativePtr;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void addLocalDevice(int deviceType, HdmiCecLocalDevice device) {
        assertRunOnServiceThread();
        this.mLocalDevices.put(deviceType, device);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void allocateLogicalAddress(final int deviceType, final int preferredAddress, final AllocateAddressCallback callback) {
        assertRunOnServiceThread();
        runOnIoThread(new Runnable() { // from class: com.android.server.hdmi.HdmiCecController.3
            @Override // java.lang.Runnable
            public void run() {
                HdmiCecController.this.handleAllocateLogicalAddress(deviceType, preferredAddress, callback);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    @HdmiAnnotations.IoThreadOnly
    public void handleAllocateLogicalAddress(final int deviceType, int preferredAddress, final AllocateAddressCallback callback) {
        assertRunOnIoThread();
        int startAddress = preferredAddress;
        if (preferredAddress == 15) {
            int i = 0;
            while (true) {
                if (i >= 16) {
                    break;
                } else if (deviceType != HdmiUtils.getTypeFromAddress(i)) {
                    i++;
                } else {
                    startAddress = i;
                    break;
                }
            }
        }
        int logicalAddress = 15;
        int i2 = 0;
        while (true) {
            if (i2 >= 16) {
                break;
            }
            int curAddress = (startAddress + i2) % 16;
            if (curAddress != 15 && deviceType == HdmiUtils.getTypeFromAddress(curAddress)) {
                boolean acked = false;
                int j = 0;
                while (true) {
                    if (j >= 3) {
                        break;
                    } else if (!sendPollMessage(curAddress, curAddress, 1)) {
                        j++;
                    } else {
                        acked = true;
                        break;
                    }
                }
                if (!acked) {
                    logicalAddress = curAddress;
                    break;
                }
            }
            i2++;
        }
        final int assignedAddress = logicalAddress;
        HdmiLogger.debug("New logical address for device [%d]: [preferred:%d, assigned:%d]", Integer.valueOf(deviceType), Integer.valueOf(preferredAddress), Integer.valueOf(assignedAddress));
        if (callback != null) {
            runOnServiceThread(new Runnable() { // from class: com.android.server.hdmi.HdmiCecController.4
                @Override // java.lang.Runnable
                public void run() {
                    callback.onAllocated(deviceType, assignedAddress);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static byte[] buildBody(int opcode, byte[] params) {
        byte[] body = new byte[params.length + 1];
        body[0] = (byte) opcode;
        System.arraycopy(params, 0, body, 1, params.length);
        return body;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public HdmiPortInfo[] getPortInfos() {
        return nativeGetPortInfos(this.mNativePtr);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public HdmiCecLocalDevice getLocalDevice(int deviceType) {
        return this.mLocalDevices.get(deviceType);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public int addLogicalAddress(int newLogicalAddress) {
        assertRunOnServiceThread();
        if (HdmiUtils.isValidAddress(newLogicalAddress)) {
            return nativeAddLogicalAddress(this.mNativePtr, newLogicalAddress);
        }
        return 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void clearLogicalAddress() {
        assertRunOnServiceThread();
        for (int i = 0; i < this.mLocalDevices.size(); i++) {
            this.mLocalDevices.valueAt(i).clearAddress();
        }
        nativeClearLogicalAddress(this.mNativePtr);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void clearLocalDevices() {
        assertRunOnServiceThread();
        this.mLocalDevices.clear();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public int getPhysicalAddress() {
        assertRunOnServiceThread();
        return nativeGetPhysicalAddress(this.mNativePtr);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public int getVersion() {
        assertRunOnServiceThread();
        return nativeGetVersion(this.mNativePtr);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public int getVendorId() {
        assertRunOnServiceThread();
        return nativeGetVendorId(this.mNativePtr);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void setOption(int flag, boolean enabled) {
        assertRunOnServiceThread();
        HdmiLogger.debug("setOption: [flag:%d, enabled:%b]", Integer.valueOf(flag), Boolean.valueOf(enabled));
        nativeSetOption(this.mNativePtr, flag, enabled);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void setLanguage(String language) {
        assertRunOnServiceThread();
        if (!LanguageTag.isLanguage(language)) {
            return;
        }
        nativeSetLanguage(this.mNativePtr, language);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void enableAudioReturnChannel(int port, boolean enabled) {
        assertRunOnServiceThread();
        nativeEnableAudioReturnChannel(this.mNativePtr, port, enabled);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public boolean isConnected(int port) {
        assertRunOnServiceThread();
        return nativeIsConnected(this.mNativePtr, port);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void pollDevices(HdmiControlService.DevicePollingCallback callback, int sourceAddress, int pickStrategy, int retryCount) {
        assertRunOnServiceThread();
        List<Integer> pollingCandidates = pickPollCandidates(pickStrategy);
        ArrayList<Integer> allocated = new ArrayList<>();
        runDevicePolling(sourceAddress, pollingCandidates, retryCount, callback, allocated);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public List<HdmiCecLocalDevice> getLocalDeviceList() {
        assertRunOnServiceThread();
        return HdmiUtils.sparseArrayToList(this.mLocalDevices);
    }

    private List<Integer> pickPollCandidates(int pickStrategy) {
        Predicate<Integer> pickPredicate;
        int strategy = pickStrategy & 3;
        if (strategy == 2) {
            pickPredicate = this.mSystemAudioAddressPredicate;
        } else {
            pickPredicate = this.mRemoteDeviceAddressPredicate;
        }
        int iterationStrategy = 196608 & pickStrategy;
        LinkedList<Integer> pollingCandidates = new LinkedList<>();
        int i = 14;
        if (iterationStrategy != 65536) {
            while (true) {
                int i2 = i;
                if (i2 < 0) {
                    break;
                }
                if (pickPredicate.test(Integer.valueOf(i2))) {
                    pollingCandidates.add(Integer.valueOf(i2));
                }
                i = i2 - 1;
            }
        } else {
            for (int i3 = 0; i3 <= 14; i3++) {
                if (pickPredicate.test(Integer.valueOf(i3))) {
                    pollingCandidates.add(Integer.valueOf(i3));
                }
            }
        }
        return pollingCandidates;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @HdmiAnnotations.ServiceThreadOnly
    public boolean isAllocatedLocalDeviceAddress(int address) {
        assertRunOnServiceThread();
        for (int i = 0; i < this.mLocalDevices.size(); i++) {
            if (this.mLocalDevices.valueAt(i).isAddressOf(address)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @HdmiAnnotations.ServiceThreadOnly
    public void runDevicePolling(final int sourceAddress, final List<Integer> candidates, final int retryCount, final HdmiControlService.DevicePollingCallback callback, final List<Integer> allocated) {
        assertRunOnServiceThread();
        if (candidates.isEmpty()) {
            if (callback != null) {
                HdmiLogger.debug("[P]:AllocatedAddress=%s", allocated.toString());
                callback.onPollingFinished(allocated);
                return;
            }
            return;
        }
        final Integer candidate = candidates.remove(0);
        runOnIoThread(new Runnable() { // from class: com.android.server.hdmi.HdmiCecController.5
            @Override // java.lang.Runnable
            public void run() {
                if (HdmiCecController.this.sendPollMessage(sourceAddress, candidate.intValue(), retryCount)) {
                    allocated.add(candidate);
                }
                HdmiCecController.this.runOnServiceThread(new Runnable() { // from class: com.android.server.hdmi.HdmiCecController.5.1
                    @Override // java.lang.Runnable
                    public void run() {
                        HdmiCecController.this.runDevicePolling(sourceAddress, candidates, retryCount, callback, allocated);
                    }
                });
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    @HdmiAnnotations.IoThreadOnly
    public boolean sendPollMessage(int sourceAddress, int destinationAddress, int retryCount) {
        assertRunOnIoThread();
        for (int i = 0; i < retryCount; i++) {
            int ret = nativeSendCecCommand(this.mNativePtr, sourceAddress, destinationAddress, EMPTY_BODY);
            if (ret == 0) {
                return true;
            }
            if (ret != 1) {
                HdmiLogger.warning("Failed to send a polling message(%d->%d) with return code %d", Integer.valueOf(sourceAddress), Integer.valueOf(destinationAddress), Integer.valueOf(ret));
            }
        }
        return false;
    }

    private void assertRunOnIoThread() {
        if (Looper.myLooper() != this.mIoHandler.getLooper()) {
            throw new IllegalStateException("Should run on io thread.");
        }
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != this.mControlHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    private void runOnIoThread(Runnable runnable) {
        this.mIoHandler.post(runnable);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void runOnServiceThread(Runnable runnable) {
        this.mControlHandler.post(runnable);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void flush(final Runnable runnable) {
        assertRunOnServiceThread();
        runOnIoThread(new Runnable() { // from class: com.android.server.hdmi.HdmiCecController.6
            @Override // java.lang.Runnable
            public void run() {
                HdmiCecController.this.runOnServiceThread(runnable);
            }
        });
    }

    private boolean isAcceptableAddress(int address) {
        if (address == 15) {
            return true;
        }
        return isAllocatedLocalDeviceAddress(address);
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void onReceiveCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (isAcceptableAddress(message.getDestination()) && this.mService.handleCecCommand(message)) {
            return;
        }
        maySendFeatureAbortCommand(message, 0);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void maySendFeatureAbortCommand(HdmiCecMessage message, int reason) {
        int originalOpcode;
        assertRunOnServiceThread();
        int src = message.getDestination();
        int dest = message.getSource();
        if (src == 15 || dest == 15 || (originalOpcode = message.getOpcode()) == 0) {
            return;
        }
        sendCommand(HdmiCecMessageBuilder.buildFeatureAbortCommand(src, dest, originalOpcode, reason));
    }

    @HdmiAnnotations.ServiceThreadOnly
    void sendCommand(HdmiCecMessage cecMessage) {
        assertRunOnServiceThread();
        sendCommand(cecMessage, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @HdmiAnnotations.ServiceThreadOnly
    public void sendCommand(final HdmiCecMessage cecMessage, final HdmiControlService.SendMessageCallback callback) {
        assertRunOnServiceThread();
        addMessageToHistory(false, cecMessage);
        runOnIoThread(new Runnable() { // from class: com.android.server.hdmi.HdmiCecController.7
            @Override // java.lang.Runnable
            public void run() {
                final int errorCode;
                HdmiLogger.debug("[S]:" + cecMessage, new Object[0]);
                byte[] body = HdmiCecController.buildBody(cecMessage.getOpcode(), cecMessage.getParams());
                int i = 0;
                while (true) {
                    errorCode = HdmiCecController.nativeSendCecCommand(HdmiCecController.this.mNativePtr, cecMessage.getSource(), cecMessage.getDestination(), body);
                    if (errorCode == 0) {
                        break;
                    }
                    int i2 = i + 1;
                    if (i >= 1) {
                        break;
                    }
                    i = i2;
                }
                if (errorCode != 0) {
                    Slog.w(HdmiCecController.TAG, "Failed to send " + cecMessage + " with errorCode=" + errorCode);
                }
                if (callback != null) {
                    HdmiCecController.this.runOnServiceThread(new Runnable() { // from class: com.android.server.hdmi.HdmiCecController.7.1
                        @Override // java.lang.Runnable
                        public void run() {
                            callback.onSendCompleted(errorCode);
                        }
                    });
                }
            }
        });
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void handleIncomingCecCommand(int srcAddress, int dstAddress, byte[] body) {
        assertRunOnServiceThread();
        HdmiCecMessage command = HdmiCecMessageBuilder.of(srcAddress, dstAddress, body);
        HdmiLogger.debug("[R]:" + command, new Object[0]);
        addMessageToHistory(true, command);
        onReceiveCommand(command);
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void handleHotplug(int port, boolean connected) {
        assertRunOnServiceThread();
        HdmiLogger.debug("Hotplug event:[port:%d, connected:%b]", Integer.valueOf(port), Boolean.valueOf(connected));
        this.mService.onHotplug(port, connected);
    }

    @HdmiAnnotations.ServiceThreadOnly
    private void addMessageToHistory(boolean isReceived, HdmiCecMessage message) {
        assertRunOnServiceThread();
        MessageHistoryRecord record = new MessageHistoryRecord(isReceived, message);
        if (!this.mMessageHistory.offer(record)) {
            this.mMessageHistory.poll();
            this.mMessageHistory.offer(record);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(IndentingPrintWriter pw) {
        for (int i = 0; i < this.mLocalDevices.size(); i++) {
            pw.println("HdmiCecLocalDevice #" + i + ":");
            pw.increaseIndent();
            this.mLocalDevices.valueAt(i).dump(pw);
            pw.decreaseIndent();
        }
        pw.println("CEC message history:");
        pw.increaseIndent();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Iterator<MessageHistoryRecord> it = this.mMessageHistory.iterator();
        while (it.hasNext()) {
            MessageHistoryRecord record = it.next();
            record.dump(pw, sdf);
        }
        pw.decreaseIndent();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class MessageHistoryRecord {
        private final boolean mIsReceived;
        private final HdmiCecMessage mMessage;
        private final long mTime = System.currentTimeMillis();

        public MessageHistoryRecord(boolean isReceived, HdmiCecMessage message) {
            this.mIsReceived = isReceived;
            this.mMessage = message;
        }

        void dump(IndentingPrintWriter pw, SimpleDateFormat sdf) {
            pw.print(this.mIsReceived ? "[R]" : "[S]");
            pw.print(" time=");
            pw.print(sdf.format(new Date(this.mTime)));
            pw.print(" message=");
            pw.println(this.mMessage);
        }
    }
}
