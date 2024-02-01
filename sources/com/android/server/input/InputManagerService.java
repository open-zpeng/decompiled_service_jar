package com.android.server.input;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayViewport;
import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputManager;
import android.hardware.input.ITabletModeChangedListener;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManagerInternal;
import android.hardware.input.KeyboardLayout;
import android.hardware.input.TouchCalibration;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.IInputFilter;
import android.view.IInputFilterHost;
import android.view.IInputMonitorHost;
import android.view.IWindow;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.InputWindowHandle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.ViewConfiguration;
import android.widget.Toast;
import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.slice.SliceClientPermissions;
import com.xiaopeng.IXPKeyListener;
import com.xiaopeng.IXPMotionListener;
import com.xiaopeng.input.IInputEventListener;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.DebugOption;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.xpInputDeviceWrapper;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import libcore.io.IoUtils;
import libcore.io.Streams;

/* loaded from: classes.dex */
public class InputManagerService extends IInputManager.Stub implements Watchdog.Monitor {
    public static final int BTN_MOUSE = 272;
    static final boolean DEBUG = DebugOption.DEBUG_INPUT;
    private static final String DEEP_PRESS_ENABLED = "deep_press_enabled";
    private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";
    private static final int INJECTION_TIMEOUT_MILLIS = 30000;
    private static final int INPUT_EVENT_INJECTION_FAILED = 2;
    private static final int INPUT_EVENT_INJECTION_PERMISSION_DENIED = 1;
    private static final int INPUT_EVENT_INJECTION_SUCCEEDED = 0;
    private static final int INPUT_EVENT_INJECTION_TIMED_OUT = 3;
    public static final int KEY_STATE_DOWN = 1;
    public static final int KEY_STATE_UNKNOWN = -1;
    public static final int KEY_STATE_UP = 0;
    public static final int KEY_STATE_VIRTUAL = 2;
    private static final int MSG_DELIVER_INPUT_DEVICES_CHANGED = 1;
    private static final int MSG_DELIVER_TABLET_MODE_CHANGED = 6;
    private static final int MSG_RELOAD_DEVICE_ALIASES = 5;
    private static final int MSG_RELOAD_KEYBOARD_LAYOUTS = 3;
    private static final int MSG_SWITCH_KEYBOARD_LAYOUT = 2;
    private static final int MSG_UPDATE_KEYBOARD_LAYOUTS = 4;
    private static final String PORT_ASSOCIATIONS_PATH = "etc/input-port-associations.xml";
    public static final int SW_CAMERA_LENS_COVER = 9;
    public static final int SW_CAMERA_LENS_COVER_BIT = 512;
    public static final int SW_HEADPHONE_INSERT = 2;
    public static final int SW_HEADPHONE_INSERT_BIT = 4;
    public static final int SW_JACK_BITS = 212;
    public static final int SW_JACK_PHYSICAL_INSERT = 7;
    public static final int SW_JACK_PHYSICAL_INSERT_BIT = 128;
    public static final int SW_KEYPAD_SLIDE = 10;
    public static final int SW_KEYPAD_SLIDE_BIT = 1024;
    public static final int SW_LID = 0;
    public static final int SW_LID_BIT = 1;
    public static final int SW_LINEOUT_INSERT = 6;
    public static final int SW_LINEOUT_INSERT_BIT = 64;
    public static final int SW_MICROPHONE_INSERT = 4;
    public static final int SW_MICROPHONE_INSERT_BIT = 16;
    public static final int SW_TABLET_MODE = 1;
    public static final int SW_TABLET_MODE_BIT = 2;
    static final String TAG = "InputManager";
    private RemoteCallbackList<IXPKeyListener> mCallbacks;
    private final Context mContext;
    private Context mDisplayContext;
    private final File mDoubleTouchGestureEnableFile;
    private IWindow mFocusedWindow;
    private boolean mFocusedWindowHasCapture;
    private boolean mInputDevicesChangedPending;
    IInputFilter mInputFilter;
    InputFilterHost mInputFilterHost;
    private PendingIntent mKeyboardLayoutIntent;
    private boolean mKeyboardLayoutNotificationShown;
    private RemoteCallbackList<IXPMotionListener> mMotionCallbacks;
    private int mNextVibratorTokenValue;
    private NotificationManager mNotificationManager;
    private final long mPtr;
    private Toast mSwitchedKeyboardLayoutToast;
    private boolean mSystemReady;
    final boolean mUseDevInputEventForAudioJack;
    private WindowManagerCallbacks mWindowManagerCallbacks;
    private WiredAccessoryCallbacks mWiredAccessoryCallbacks;
    private final Object mTabletModeLock = new Object();
    private final SparseArray<TabletModeChangedListenerRecord> mTabletModeChangedListeners = new SparseArray<>();
    private final List<TabletModeChangedListenerRecord> mTempTabletModeChangedListenersToNotify = new ArrayList();
    private final PersistentDataStore mDataStore = new PersistentDataStore();
    private Object mInputDevicesLock = new Object();
    private InputDevice[] mInputDevices = new InputDevice[0];
    private final SparseArray<InputDevicesChangedListenerRecord> mInputDevicesChangedListeners = new SparseArray<>();
    private final ArrayList<InputDevicesChangedListenerRecord> mTempInputDevicesChangedListenersToNotify = new ArrayList<>();
    private final ArrayList<InputDevice> mTempFullKeyboards = new ArrayList<>();
    private Object mVibratorLock = new Object();
    private HashMap<IBinder, VibratorToken> mVibratorTokens = new HashMap<>();
    final Object mInputFilterLock = new Object();
    private final InputManagerHandler mHandler = new InputManagerHandler(DisplayThread.get().getLooper());

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public interface KeyboardLayoutVisitor {
        void visitKeyboardLayout(Resources resources, int i, KeyboardLayout keyboardLayout);
    }

    /* loaded from: classes.dex */
    public interface WindowManagerCallbacks {
        KeyEvent dispatchUnhandledKey(IBinder iBinder, KeyEvent keyEvent, int i);

        int getPointerDisplayId();

        int getPointerLayer();

        long interceptKeyBeforeDispatching(IBinder iBinder, KeyEvent keyEvent, int i);

        int interceptKeyBeforeQueueing(KeyEvent keyEvent, int i);

        int interceptMotionBeforeQueueingNonInteractive(int i, long j, int i2);

        long notifyANR(IBinder iBinder, String str);

        void notifyCameraLensCoverSwitchChanged(long j, boolean z);

        void notifyConfigurationChanged();

        void notifyInputChannelBroken(IBinder iBinder);

        void notifyLidSwitchChanged(long j, boolean z);

        void onPointerDownOutsideFocus(IBinder iBinder);

        int requestInputPolicy(InputEvent inputEvent, int i);

        int requestScreenId(int i, int i2);
    }

    /* loaded from: classes.dex */
    public interface WiredAccessoryCallbacks {
        void notifyWiredAccessoryChanged(long j, int i, int i2);

        void systemReady();
    }

    private static native boolean nativeCanDispatchToDisplay(long j, int i, int i2);

    private static native void nativeCancelVibrate(long j, int i, int i2);

    private static native void nativeDisableInputDevice(long j, int i);

    private static native String nativeDump(long j);

    private static native void nativeEnableInputDevice(long j, int i);

    private static native int nativeGetKeyCodeState(long j, int i, int i2, int i3);

    private static native int nativeGetScanCodeState(long j, int i, int i2, int i3);

    private static native int nativeGetSwitchState(long j, int i, int i2, int i3);

    private static native boolean nativeHasKeys(long j, int i, int i2, int[] iArr, boolean[] zArr);

    private static native long nativeInit(InputManagerService inputManagerService, Context context, MessageQueue messageQueue);

    /* JADX INFO: Access modifiers changed from: private */
    public static native int nativeInjectInputEvent(long j, InputEvent inputEvent, int i, int i2, int i3, int i4, int i5);

    private static native boolean nativeIsInputDeviceEnabled(long j, int i);

    private static native void nativeMonitor(long j);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativePilferPointers(long j, IBinder iBinder);

    private static native void nativeRegisterInputChannel(long j, InputChannel inputChannel, int i);

    private static native void nativeRegisterInputMonitor(long j, InputChannel inputChannel, int i, boolean z);

    private static native void nativeReloadCalibration(long j);

    private static native void nativeReloadDeviceAliases(long j);

    private static native void nativeReloadKeyboardLayouts(long j);

    private static native void nativeReloadPointerIcons(long j);

    private static native void nativeSetCustomPointerIcon(long j, PointerIcon pointerIcon);

    private static native void nativeSetDisplayViewports(long j, DisplayViewport[] displayViewportArr);

    private static native void nativeSetFocusedApplication(long j, int i, InputApplicationHandle inputApplicationHandle);

    private static native void nativeSetFocusedDisplay(long j, int i);

    private static native void nativeSetInputDispatchMode(long j, boolean z, boolean z2);

    private static native void nativeSetInputFilterEnabled(long j, boolean z);

    private static native void nativeSetInputWindows(long j, InputWindowHandle[] inputWindowHandleArr, int i);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativeSetInteractive(long j, boolean z);

    private static native void nativeSetMotionClassifierEnabled(long j, boolean z);

    private static native void nativeSetPointerCapture(long j, boolean z);

    private static native void nativeSetPointerIconType(long j, int i);

    private static native void nativeSetPointerSpeed(long j, int i);

    private static native void nativeSetShowTouches(long j, boolean z);

    private static native void nativeSetSystemUiVisibility(long j, int i);

    private static native void nativeStart(long j);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativeToggleCapsLock(long j, int i);

    private static native boolean nativeTransferTouchFocus(long j, InputChannel inputChannel, InputChannel inputChannel2);

    /* JADX INFO: Access modifiers changed from: private */
    public static native void nativeUnregisterInputChannel(long j, InputChannel inputChannel);

    private static native void nativeVibrate(long j, int i, long[] jArr, int i2, int i3);

    public InputManagerService(Context context) {
        this.mContext = context;
        this.mUseDevInputEventForAudioJack = context.getResources().getBoolean(17891562);
        Slog.i(TAG, "Initializing input manager, mUseDevInputEventForAudioJack=" + this.mUseDevInputEventForAudioJack);
        this.mPtr = nativeInit(this, this.mContext, this.mHandler.getLooper().getQueue());
        String doubleTouchGestureEnablePath = context.getResources().getString(17039725);
        this.mDoubleTouchGestureEnableFile = TextUtils.isEmpty(doubleTouchGestureEnablePath) ? null : new File(doubleTouchGestureEnablePath);
        LocalServices.addService(InputManagerInternal.class, new LocalService());
        this.mCallbacks = new RemoteCallbackList<>();
        this.mMotionCallbacks = new RemoteCallbackList<>();
    }

    public void setWindowManagerCallbacks(WindowManagerCallbacks callbacks) {
        this.mWindowManagerCallbacks = callbacks;
    }

    public void setWiredAccessoryCallbacks(WiredAccessoryCallbacks callbacks) {
        this.mWiredAccessoryCallbacks = callbacks;
    }

    public void start() {
        Slog.i(TAG, "Starting input manager");
        nativeStart(this.mPtr);
        Watchdog.getInstance().addMonitor(this);
        registerPointerSpeedSettingObserver();
        registerShowTouchesSettingObserver();
        registerAccessibilityLargePointerSettingObserver();
        registerLongPressTimeoutObserver();
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.input.InputManagerService.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                InputManagerService.this.updatePointerSpeedFromSettings();
                InputManagerService.this.updateShowTouchesFromSettings();
                InputManagerService.this.updateAccessibilityLargePointerFromSettings();
                InputManagerService.this.updateDeepPressStatusFromSettings("user switched");
            }
        }, new IntentFilter("android.intent.action.USER_SWITCHED"), null, this.mHandler);
        updatePointerSpeedFromSettings();
        updateShowTouchesFromSettings();
        updateAccessibilityLargePointerFromSettings();
        updateDeepPressStatusFromSettings("just booted");
    }

    public void systemRunning() {
        if (DEBUG) {
            Slog.d(TAG, "System ready.");
        }
        this.mNotificationManager = (NotificationManager) this.mContext.getSystemService("notification");
        this.mSystemReady = true;
        IntentFilter filter = new IntentFilter("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addAction("android.intent.action.PACKAGE_CHANGED");
        filter.addAction("android.intent.action.PACKAGE_REPLACED");
        filter.addDataScheme("package");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.input.InputManagerService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                InputManagerService.this.updateKeyboardLayouts();
            }
        }, filter, null, this.mHandler);
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.input.InputManagerService.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                InputManagerService.this.reloadDeviceAliases();
            }
        }, new IntentFilter("android.bluetooth.device.action.ALIAS_CHANGED"), null, this.mHandler);
        this.mHandler.sendEmptyMessage(5);
        this.mHandler.sendEmptyMessage(4);
        WiredAccessoryCallbacks wiredAccessoryCallbacks = this.mWiredAccessoryCallbacks;
        if (wiredAccessoryCallbacks != null) {
            wiredAccessoryCallbacks.systemReady();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadKeyboardLayouts() {
        if (DEBUG) {
            Slog.d(TAG, "Reloading keyboard layouts.");
        }
        nativeReloadKeyboardLayouts(this.mPtr);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadDeviceAliases() {
        if (DEBUG) {
            Slog.d(TAG, "Reloading device names.");
        }
        nativeReloadDeviceAliases(this.mPtr);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setDisplayViewportsInternal(List<DisplayViewport> viewports) {
        nativeSetDisplayViewports(this.mPtr, (DisplayViewport[]) viewports.toArray(new DisplayViewport[0]));
    }

    public int getKeyCodeState(int deviceId, int sourceMask, int keyCode) {
        return nativeGetKeyCodeState(this.mPtr, deviceId, sourceMask, keyCode);
    }

    public int getScanCodeState(int deviceId, int sourceMask, int scanCode) {
        return nativeGetScanCodeState(this.mPtr, deviceId, sourceMask, scanCode);
    }

    public int getSwitchState(int deviceId, int sourceMask, int switchCode) {
        return nativeGetSwitchState(this.mPtr, deviceId, sourceMask, switchCode);
    }

    public boolean hasKeys(int deviceId, int sourceMask, int[] keyCodes, boolean[] keyExists) {
        if (keyCodes == null) {
            throw new IllegalArgumentException("keyCodes must not be null.");
        }
        if (keyExists == null || keyExists.length < keyCodes.length) {
            throw new IllegalArgumentException("keyExists must not be null and must be at least as large as keyCodes.");
        }
        return nativeHasKeys(this.mPtr, deviceId, sourceMask, keyCodes, keyExists);
    }

    public InputChannel monitorInput(String inputChannelName, int displayId) {
        if (inputChannelName == null) {
            throw new IllegalArgumentException("inputChannelName must not be null.");
        }
        if (displayId < 0) {
            throw new IllegalArgumentException("displayId must >= 0.");
        }
        InputChannel[] inputChannels = InputChannel.openInputChannelPair(inputChannelName);
        inputChannels[0].setToken(new Binder());
        nativeRegisterInputMonitor(this.mPtr, inputChannels[0], displayId, false);
        inputChannels[0].dispose();
        return inputChannels[1];
    }

    public InputMonitor monitorGestureInput(String inputChannelName, int displayId) {
        if (!checkCallingPermission("android.permission.MONITOR_INPUT", "monitorInputRegion()")) {
            throw new SecurityException("Requires MONITOR_INPUT permission");
        }
        Objects.requireNonNull(inputChannelName, "inputChannelName must not be null.");
        if (displayId < 0) {
            throw new IllegalArgumentException("displayId must >= 0.");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            InputChannel[] inputChannels = InputChannel.openInputChannelPair(inputChannelName);
            InputMonitorHost host = new InputMonitorHost(inputChannels[0]);
            inputChannels[0].setToken(host.asBinder());
            nativeRegisterInputMonitor(this.mPtr, inputChannels[0], displayId, true);
            return new InputMonitor(inputChannelName, inputChannels[1], host);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void registerInputChannel(InputChannel inputChannel, IBinder token) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }
        if (token == null) {
            token = new Binder();
        }
        inputChannel.setToken(token);
        nativeRegisterInputChannel(this.mPtr, inputChannel, -1);
        if (DEBUG) {
            xpLogger.i(TAG, "registerInputChannel inputChannel=" + inputChannel + " token=" + token + " ptr=" + this.mPtr + " name=" + inputChannel.getName());
        }
    }

    public void unregisterInputChannel(InputChannel inputChannel) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }
        nativeUnregisterInputChannel(this.mPtr, inputChannel);
        if (DEBUG) {
            xpLogger.i(TAG, "registerInputChannel inputChannel=" + inputChannel + " ptr=" + this.mPtr + " name=" + inputChannel.getName());
        }
    }

    public void setInputFilter(IInputFilter filter) {
        synchronized (this.mInputFilterLock) {
            IInputFilter oldFilter = this.mInputFilter;
            if (oldFilter == filter) {
                return;
            }
            if (oldFilter != null) {
                this.mInputFilter = null;
                this.mInputFilterHost.disconnectLocked();
                this.mInputFilterHost = null;
                try {
                    oldFilter.uninstall();
                } catch (RemoteException e) {
                }
            }
            if (filter != null) {
                this.mInputFilter = filter;
                this.mInputFilterHost = new InputFilterHost();
                try {
                    filter.install(this.mInputFilterHost);
                } catch (RemoteException e2) {
                }
            }
            nativeSetInputFilterEnabled(this.mPtr, filter != null);
        }
    }

    public boolean injectInputEvent(InputEvent event, int mode) {
        return injectInputEventInternal(event, mode);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean injectInputEventInternal(InputEvent event, int mode) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (mode != 0 && mode != 2 && mode != 1) {
            throw new IllegalArgumentException("mode is invalid");
        }
        int pid = Binder.getCallingPid();
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            int result = nativeInjectInputEvent(this.mPtr, event, pid, uid, mode, INJECTION_TIMEOUT_MILLIS, 134217728);
            if (result != 0) {
                if (result == 1) {
                    Slog.w(TAG, "Input event injection from pid " + pid + " permission denied.");
                    throw new SecurityException("Injecting to another application requires INJECT_EVENTS permission");
                } else if (result == 3) {
                    Slog.w(TAG, "Input event injection from pid " + pid + " timed out.");
                    return false;
                } else {
                    Slog.w(TAG, "Input event injection from pid " + pid + " failed.");
                    return false;
                }
            }
            return true;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public InputDevice getInputDevice(int deviceId) {
        synchronized (this.mInputDevicesLock) {
            int count = this.mInputDevices.length;
            for (int i = 0; i < count; i++) {
                InputDevice inputDevice = this.mInputDevices[i];
                if (inputDevice.getId() == deviceId) {
                    return inputDevice;
                }
            }
            return null;
        }
    }

    public boolean isInputDeviceEnabled(int deviceId) {
        return nativeIsInputDeviceEnabled(this.mPtr, deviceId);
    }

    public void enableInputDevice(int deviceId) {
        if (!checkCallingPermission("android.permission.DISABLE_INPUT_DEVICE", "enableInputDevice()")) {
            throw new SecurityException("Requires DISABLE_INPUT_DEVICE permission");
        }
        nativeEnableInputDevice(this.mPtr, deviceId);
    }

    public void disableInputDevice(int deviceId) {
        if (!checkCallingPermission("android.permission.DISABLE_INPUT_DEVICE", "disableInputDevice()")) {
            throw new SecurityException("Requires DISABLE_INPUT_DEVICE permission");
        }
        nativeDisableInputDevice(this.mPtr, deviceId);
    }

    public int[] getInputDeviceIds() {
        int[] ids;
        synchronized (this.mInputDevicesLock) {
            int count = this.mInputDevices.length;
            ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = this.mInputDevices[i].getId();
            }
        }
        return ids;
    }

    public InputDevice[] getInputDevices() {
        InputDevice[] inputDeviceArr;
        synchronized (this.mInputDevicesLock) {
            inputDeviceArr = this.mInputDevices;
        }
        return inputDeviceArr;
    }

    public void registerInputDevicesChangedListener(IInputDevicesChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (this.mInputDevicesLock) {
            int callingPid = Binder.getCallingPid();
            if (this.mInputDevicesChangedListeners.get(callingPid) != null) {
                throw new SecurityException("The calling process has already registered an InputDevicesChangedListener.");
            }
            InputDevicesChangedListenerRecord record = new InputDevicesChangedListenerRecord(callingPid, listener);
            try {
                IBinder binder = listener.asBinder();
                binder.linkToDeath(record, 0);
                this.mInputDevicesChangedListeners.put(callingPid, record);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onInputDevicesChangedListenerDied(int pid) {
        synchronized (this.mInputDevicesLock) {
            this.mInputDevicesChangedListeners.remove(pid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void deliverInputDevicesChanged(InputDevice[] oldInputDevices) {
        int numFullKeyboardsAdded = 0;
        this.mTempInputDevicesChangedListenersToNotify.clear();
        this.mTempFullKeyboards.clear();
        synchronized (this.mInputDevicesLock) {
            try {
                if (this.mInputDevicesChangedPending) {
                    this.mInputDevicesChangedPending = false;
                    int numListeners = this.mInputDevicesChangedListeners.size();
                    for (int i = 0; i < numListeners; i++) {
                        this.mTempInputDevicesChangedListenersToNotify.add(this.mInputDevicesChangedListeners.valueAt(i));
                    }
                    int numDevices = this.mInputDevices.length;
                    int[] deviceIdAndGeneration = new int[numDevices * 2];
                    for (int i2 = 0; i2 < numDevices; i2++) {
                        InputDevice inputDevice = this.mInputDevices[i2];
                        deviceIdAndGeneration[i2 * 2] = inputDevice.getId();
                        deviceIdAndGeneration[(i2 * 2) + 1] = inputDevice.getGeneration();
                        if (!inputDevice.isVirtual() && inputDevice.isFullKeyboard()) {
                            if (!containsInputDeviceWithDescriptor(oldInputDevices, inputDevice.getDescriptor())) {
                                int numFullKeyboardsAdded2 = numFullKeyboardsAdded + 1;
                                try {
                                    this.mTempFullKeyboards.add(numFullKeyboardsAdded, inputDevice);
                                    numFullKeyboardsAdded = numFullKeyboardsAdded2;
                                } catch (Throwable th) {
                                    th = th;
                                    throw th;
                                }
                            } else {
                                this.mTempFullKeyboards.add(inputDevice);
                            }
                        }
                    }
                    for (int i3 = 0; i3 < numListeners; i3++) {
                        this.mTempInputDevicesChangedListenersToNotify.get(i3).notifyInputDevicesChanged(deviceIdAndGeneration);
                    }
                    this.mTempInputDevicesChangedListenersToNotify.clear();
                    List<InputDevice> keyboardsMissingLayout = new ArrayList<>();
                    int numFullKeyboards = this.mTempFullKeyboards.size();
                    synchronized (this.mDataStore) {
                        for (int i4 = 0; i4 < numFullKeyboards; i4++) {
                            InputDevice inputDevice2 = this.mTempFullKeyboards.get(i4);
                            String layout = getCurrentKeyboardLayoutForInputDevice(inputDevice2.getIdentifier());
                            if (layout == null && (layout = getDefaultKeyboardLayout(inputDevice2)) != null) {
                                setCurrentKeyboardLayoutForInputDevice(inputDevice2.getIdentifier(), layout);
                            }
                            if (layout == null) {
                                keyboardsMissingLayout.add(inputDevice2);
                            }
                        }
                    }
                    if (this.mNotificationManager != null) {
                        if (!keyboardsMissingLayout.isEmpty()) {
                            if (keyboardsMissingLayout.size() > 1) {
                                showMissingKeyboardLayoutNotification(null);
                            } else {
                                showMissingKeyboardLayoutNotification(keyboardsMissingLayout.get(0));
                            }
                        } else if (this.mKeyboardLayoutNotificationShown) {
                            hideMissingKeyboardLayoutNotification();
                        }
                    }
                    this.mTempFullKeyboards.clear();
                }
            } catch (Throwable th2) {
                th = th2;
            }
        }
    }

    private String getDefaultKeyboardLayout(final InputDevice d) {
        final Locale systemLocale = this.mContext.getResources().getConfiguration().locale;
        if (systemLocale == null || TextUtils.isEmpty(systemLocale.getLanguage())) {
            return null;
        }
        final List<KeyboardLayout> layouts = new ArrayList<>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() { // from class: com.android.server.input.InputManagerService.4
            @Override // com.android.server.input.InputManagerService.KeyboardLayoutVisitor
            public void visitKeyboardLayout(Resources resources, int keyboardLayoutResId, KeyboardLayout layout) {
                if (layout.getVendorId() != d.getVendorId() || layout.getProductId() != d.getProductId()) {
                    return;
                }
                LocaleList locales = layout.getLocales();
                int numLocales = locales.size();
                for (int localeIndex = 0; localeIndex < numLocales; localeIndex++) {
                    if (InputManagerService.isCompatibleLocale(systemLocale, locales.get(localeIndex))) {
                        layouts.add(layout);
                        return;
                    }
                }
            }
        });
        if (layouts.isEmpty()) {
            return null;
        }
        Collections.sort(layouts);
        int N = layouts.size();
        for (int i = 0; i < N; i++) {
            KeyboardLayout layout = layouts.get(i);
            LocaleList locales = layout.getLocales();
            int numLocales = locales.size();
            for (int localeIndex = 0; localeIndex < numLocales; localeIndex++) {
                Locale locale = locales.get(localeIndex);
                if (locale.getCountry().equals(systemLocale.getCountry()) && locale.getVariant().equals(systemLocale.getVariant())) {
                    return layout.getDescriptor();
                }
            }
        }
        for (int i2 = 0; i2 < N; i2++) {
            KeyboardLayout layout2 = layouts.get(i2);
            LocaleList locales2 = layout2.getLocales();
            int numLocales2 = locales2.size();
            for (int localeIndex2 = 0; localeIndex2 < numLocales2; localeIndex2++) {
                if (locales2.get(localeIndex2).getCountry().equals(systemLocale.getCountry())) {
                    return layout2.getDescriptor();
                }
            }
        }
        return layouts.get(0).getDescriptor();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isCompatibleLocale(Locale systemLocale, Locale keyboardLocale) {
        if (systemLocale.getLanguage().equals(keyboardLocale.getLanguage())) {
            return TextUtils.isEmpty(systemLocale.getCountry()) || TextUtils.isEmpty(keyboardLocale.getCountry()) || systemLocale.getCountry().equals(keyboardLocale.getCountry());
        }
        return false;
    }

    public TouchCalibration getTouchCalibrationForInputDevice(String inputDeviceDescriptor, int surfaceRotation) {
        TouchCalibration touchCalibration;
        if (inputDeviceDescriptor == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }
        synchronized (this.mDataStore) {
            touchCalibration = this.mDataStore.getTouchCalibration(inputDeviceDescriptor, surfaceRotation);
        }
        return touchCalibration;
    }

    public void setTouchCalibrationForInputDevice(String inputDeviceDescriptor, int surfaceRotation, TouchCalibration calibration) {
        if (!checkCallingPermission("android.permission.SET_INPUT_CALIBRATION", "setTouchCalibrationForInputDevice()")) {
            throw new SecurityException("Requires SET_INPUT_CALIBRATION permission");
        }
        if (inputDeviceDescriptor == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }
        if (calibration == null) {
            throw new IllegalArgumentException("calibration must not be null");
        }
        if (surfaceRotation < 0 || surfaceRotation > 3) {
            throw new IllegalArgumentException("surfaceRotation value out of bounds");
        }
        synchronized (this.mDataStore) {
            if (this.mDataStore.setTouchCalibration(inputDeviceDescriptor, surfaceRotation, calibration)) {
                nativeReloadCalibration(this.mPtr);
            }
            this.mDataStore.saveIfNeeded();
        }
    }

    public int isInTabletMode() {
        if (!checkCallingPermission("android.permission.TABLET_MODE", "isInTabletMode()")) {
            throw new SecurityException("Requires TABLET_MODE permission");
        }
        return getSwitchState(-1, -256, 1);
    }

    public void registerTabletModeChangedListener(ITabletModeChangedListener listener) {
        if (!checkCallingPermission("android.permission.TABLET_MODE", "registerTabletModeChangedListener()")) {
            throw new SecurityException("Requires TABLET_MODE_LISTENER permission");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (this.mTabletModeLock) {
            int callingPid = Binder.getCallingPid();
            if (this.mTabletModeChangedListeners.get(callingPid) != null) {
                throw new IllegalStateException("The calling process has already registered a TabletModeChangedListener.");
            }
            TabletModeChangedListenerRecord record = new TabletModeChangedListenerRecord(callingPid, listener);
            try {
                IBinder binder = listener.asBinder();
                binder.linkToDeath(record, 0);
                this.mTabletModeChangedListeners.put(callingPid, record);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onTabletModeChangedListenerDied(int pid) {
        synchronized (this.mTabletModeLock) {
            this.mTabletModeChangedListeners.remove(pid);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void deliverTabletModeChanged(long whenNanos, boolean inTabletMode) {
        int numListeners;
        this.mTempTabletModeChangedListenersToNotify.clear();
        synchronized (this.mTabletModeLock) {
            numListeners = this.mTabletModeChangedListeners.size();
            for (int i = 0; i < numListeners; i++) {
                this.mTempTabletModeChangedListenersToNotify.add(this.mTabletModeChangedListeners.valueAt(i));
            }
        }
        for (int i2 = 0; i2 < numListeners; i2++) {
            this.mTempTabletModeChangedListenersToNotify.get(i2).notifyTabletModeChanged(whenNanos, inTabletMode);
        }
    }

    private void showMissingKeyboardLayoutNotification(InputDevice device) {
        if (!this.mKeyboardLayoutNotificationShown) {
            Intent intent = new Intent("android.settings.HARD_KEYBOARD_SETTINGS");
            if (device != null) {
                intent.putExtra("input_device_identifier", (Parcelable) device.getIdentifier());
            }
            intent.setFlags(337641472);
            PendingIntent keyboardLayoutIntent = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 0, null, UserHandle.CURRENT);
            Resources r = this.mContext.getResources();
            Notification notification = new Notification.Builder(this.mContext, SystemNotificationChannels.PHYSICAL_KEYBOARD).setContentTitle(r.getString(17040999)).setContentText(r.getString(17040998)).setContentIntent(keyboardLayoutIntent).setSmallIcon(17302804).setColor(this.mContext.getColor(17170460)).build();
            this.mNotificationManager.notifyAsUser(null, 19, notification, UserHandle.ALL);
            this.mKeyboardLayoutNotificationShown = true;
        }
    }

    private void hideMissingKeyboardLayoutNotification() {
        if (this.mKeyboardLayoutNotificationShown) {
            this.mKeyboardLayoutNotificationShown = false;
            this.mNotificationManager.cancelAsUser(null, 19, UserHandle.ALL);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateKeyboardLayouts() {
        final HashSet<String> availableKeyboardLayouts = new HashSet<>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() { // from class: com.android.server.input.InputManagerService.5
            @Override // com.android.server.input.InputManagerService.KeyboardLayoutVisitor
            public void visitKeyboardLayout(Resources resources, int keyboardLayoutResId, KeyboardLayout layout) {
                availableKeyboardLayouts.add(layout.getDescriptor());
            }
        });
        synchronized (this.mDataStore) {
            this.mDataStore.removeUninstalledKeyboardLayouts(availableKeyboardLayouts);
            this.mDataStore.saveIfNeeded();
        }
        reloadKeyboardLayouts();
    }

    private static boolean containsInputDeviceWithDescriptor(InputDevice[] inputDevices, String descriptor) {
        for (InputDevice inputDevice : inputDevices) {
            if (inputDevice.getDescriptor().equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    public KeyboardLayout[] getKeyboardLayouts() {
        final ArrayList<KeyboardLayout> list = new ArrayList<>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() { // from class: com.android.server.input.InputManagerService.6
            @Override // com.android.server.input.InputManagerService.KeyboardLayoutVisitor
            public void visitKeyboardLayout(Resources resources, int keyboardLayoutResId, KeyboardLayout layout) {
                list.add(layout);
            }
        });
        return (KeyboardLayout[]) list.toArray(new KeyboardLayout[list.size()]);
    }

    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(final InputDeviceIdentifier identifier) {
        final String[] enabledLayoutDescriptors = getEnabledKeyboardLayoutsForInputDevice(identifier);
        final ArrayList<KeyboardLayout> enabledLayouts = new ArrayList<>(enabledLayoutDescriptors.length);
        final ArrayList<KeyboardLayout> potentialLayouts = new ArrayList<>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() { // from class: com.android.server.input.InputManagerService.7
            boolean mHasSeenDeviceSpecificLayout;

            @Override // com.android.server.input.InputManagerService.KeyboardLayoutVisitor
            public void visitKeyboardLayout(Resources resources, int keyboardLayoutResId, KeyboardLayout layout) {
                String[] strArr;
                for (String s : enabledLayoutDescriptors) {
                    if (s != null && s.equals(layout.getDescriptor())) {
                        enabledLayouts.add(layout);
                        return;
                    }
                }
                if (layout.getVendorId() == identifier.getVendorId() && layout.getProductId() == identifier.getProductId()) {
                    if (!this.mHasSeenDeviceSpecificLayout) {
                        this.mHasSeenDeviceSpecificLayout = true;
                        potentialLayouts.clear();
                    }
                    potentialLayouts.add(layout);
                } else if (layout.getVendorId() == -1 && layout.getProductId() == -1 && !this.mHasSeenDeviceSpecificLayout) {
                    potentialLayouts.add(layout);
                }
            }
        });
        int enabledLayoutSize = enabledLayouts.size();
        int potentialLayoutSize = potentialLayouts.size();
        KeyboardLayout[] layouts = new KeyboardLayout[enabledLayoutSize + potentialLayoutSize];
        enabledLayouts.toArray(layouts);
        for (int i = 0; i < potentialLayoutSize; i++) {
            layouts[enabledLayoutSize + i] = potentialLayouts.get(i);
        }
        return layouts;
    }

    public KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor) {
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }
        final KeyboardLayout[] result = new KeyboardLayout[1];
        visitKeyboardLayout(keyboardLayoutDescriptor, new KeyboardLayoutVisitor() { // from class: com.android.server.input.InputManagerService.8
            @Override // com.android.server.input.InputManagerService.KeyboardLayoutVisitor
            public void visitKeyboardLayout(Resources resources, int keyboardLayoutResId, KeyboardLayout layout) {
                result[0] = layout;
            }
        });
        if (result[0] == null) {
            Slog.w(TAG, "Could not get keyboard layout with descriptor '" + keyboardLayoutDescriptor + "'.");
        }
        return result[0];
    }

    private void visitAllKeyboardLayouts(KeyboardLayoutVisitor visitor) {
        PackageManager pm = this.mContext.getPackageManager();
        Intent intent = new Intent("android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS");
        for (ResolveInfo resolveInfo : pm.queryBroadcastReceivers(intent, 786560)) {
            ActivityInfo activityInfo = resolveInfo.activityInfo;
            int priority = resolveInfo.priority;
            visitKeyboardLayoutsInPackage(pm, activityInfo, null, priority, visitor);
        }
    }

    private void visitKeyboardLayout(String keyboardLayoutDescriptor, KeyboardLayoutVisitor visitor) {
        KeyboardLayoutDescriptor d = KeyboardLayoutDescriptor.parse(keyboardLayoutDescriptor);
        if (d != null) {
            PackageManager pm = this.mContext.getPackageManager();
            try {
                ActivityInfo receiver = pm.getReceiverInfo(new ComponentName(d.packageName, d.receiverName), 786560);
                visitKeyboardLayoutsInPackage(pm, receiver, d.keyboardLayoutName, 0, visitor);
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
    }

    private void visitKeyboardLayoutsInPackage(PackageManager pm, ActivityInfo receiver, String keyboardName, int requestedPriority, KeyboardLayoutVisitor visitor) {
        XmlResourceParser parser;
        Bundle metaData;
        int configResId;
        Resources resources;
        int i;
        TypedArray a;
        int pid;
        Object obj = keyboardName;
        Bundle metaData2 = receiver.metaData;
        if (metaData2 == null) {
            return;
        }
        int configResId2 = metaData2.getInt("android.hardware.input.metadata.KEYBOARD_LAYOUTS");
        if (configResId2 == 0) {
            Slog.w(TAG, "Missing meta-data 'android.hardware.input.metadata.KEYBOARD_LAYOUTS' on receiver " + receiver.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + receiver.name);
            return;
        }
        CharSequence receiverLabel = receiver.loadLabel(pm);
        String collection = receiverLabel != null ? receiverLabel.toString() : "";
        int i2 = 1;
        int priority = (receiver.applicationInfo.flags & 1) != 0 ? requestedPriority : 0;
        try {
            Resources resources2 = pm.getResourcesForApplication(receiver.applicationInfo);
            XmlResourceParser parser2 = resources2.getXml(configResId2);
            try {
                XmlUtils.beginDocument(parser2, "keyboard-layouts");
                while (true) {
                    XmlUtils.nextElement(parser2);
                    String element = parser2.getName();
                    if (element == null) {
                        try {
                            parser2.close();
                            return;
                        } catch (Exception e) {
                            ex = e;
                            Slog.w(TAG, "Could not parse keyboard layout resource from receiver " + receiver.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + receiver.name, ex);
                            return;
                        }
                    }
                    if (element.equals("keyboard-layout")) {
                        TypedArray a2 = resources2.obtainAttributes(parser2, R.styleable.KeyboardLayout);
                        try {
                            String name = a2.getString(i2);
                            String label = a2.getString(0);
                            int keyboardLayoutResId = a2.getResourceId(2, 0);
                            String languageTags = a2.getString(3);
                            LocaleList locales = getLocalesFromLanguageTags(languageTags);
                            metaData = metaData2;
                            try {
                                int vid = a2.getInt(5, -1);
                                a = a2;
                                configResId = configResId2;
                                try {
                                    pid = a.getInt(4, -1);
                                } catch (Throwable th) {
                                    th = th;
                                    parser = parser2;
                                }
                                try {
                                    try {
                                        if (name == null || label == null) {
                                            parser = parser2;
                                            resources = resources2;
                                            i = 1;
                                        } else if (keyboardLayoutResId == 0) {
                                            parser = parser2;
                                            resources = resources2;
                                            i = 1;
                                        } else {
                                            String descriptor = KeyboardLayoutDescriptor.format(receiver.packageName, receiver.name, name);
                                            try {
                                                try {
                                                    if (obj != null) {
                                                        try {
                                                            if (!name.equals(obj)) {
                                                                parser = parser2;
                                                                resources = resources2;
                                                                i = 1;
                                                                a.recycle();
                                                            }
                                                        } catch (Throwable th2) {
                                                            th = th2;
                                                            parser = parser2;
                                                            a.recycle();
                                                            throw th;
                                                        }
                                                    }
                                                    KeyboardLayout layout = new KeyboardLayout(descriptor, label, collection, priority, locales, vid, pid);
                                                    visitor.visitKeyboardLayout(resources, keyboardLayoutResId, layout);
                                                    a.recycle();
                                                } catch (Throwable th3) {
                                                    th = th3;
                                                    a.recycle();
                                                    throw th;
                                                }
                                                parser = parser2;
                                                resources = resources2;
                                                i = 1;
                                            } catch (Throwable th4) {
                                                th = th4;
                                                parser = parser2;
                                            }
                                        }
                                        a.recycle();
                                    } catch (Throwable th5) {
                                        th = th5;
                                        try {
                                            parser.close();
                                            throw th;
                                        } catch (Exception e2) {
                                            ex = e2;
                                            Slog.w(TAG, "Could not parse keyboard layout resource from receiver " + receiver.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + receiver.name, ex);
                                            return;
                                        }
                                    }
                                    Slog.w(TAG, "Missing required 'name', 'label' or 'keyboardLayout' attributes in keyboard layout resource from receiver " + receiver.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + receiver.name);
                                } catch (Throwable th6) {
                                    th = th6;
                                    a.recycle();
                                    throw th;
                                }
                            } catch (Throwable th7) {
                                th = th7;
                                parser = parser2;
                                a = a2;
                            }
                        } catch (Throwable th8) {
                            th = th8;
                            parser = parser2;
                            a = a2;
                        }
                    } else {
                        metaData = metaData2;
                        configResId = configResId2;
                        parser = parser2;
                        resources = resources2;
                        i = i2;
                        Slog.w(TAG, "Skipping unrecognized element '" + element + "' in keyboard layout resource from receiver " + receiver.packageName + SliceClientPermissions.SliceAuthority.DELIMITER + receiver.name);
                    }
                    resources2 = resources;
                    i2 = i;
                    metaData2 = metaData;
                    configResId2 = configResId;
                    parser2 = parser;
                    obj = keyboardName;
                }
            } catch (Throwable th9) {
                th = th9;
                parser = parser2;
            }
        } catch (Exception e3) {
            ex = e3;
        }
    }

    private static LocaleList getLocalesFromLanguageTags(String languageTags) {
        if (TextUtils.isEmpty(languageTags)) {
            return LocaleList.getEmptyLocaleList();
        }
        return LocaleList.forLanguageTags(languageTags.replace('|', ','));
    }

    private String getLayoutDescriptor(InputDeviceIdentifier identifier) {
        if (identifier == null || identifier.getDescriptor() == null) {
            throw new IllegalArgumentException("identifier and descriptor must not be null");
        }
        if (identifier.getVendorId() == 0 && identifier.getProductId() == 0) {
            return identifier.getDescriptor();
        }
        return "vendor:" + identifier.getVendorId() + ",product:" + identifier.getProductId();
    }

    public String getCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier) {
        String layout;
        String key = getLayoutDescriptor(identifier);
        synchronized (this.mDataStore) {
            layout = this.mDataStore.getCurrentKeyboardLayout(key);
            if (layout == null && !key.equals(identifier.getDescriptor())) {
                layout = this.mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
            }
            if (DEBUG) {
                Slog.d(TAG, "Loaded keyboard layout id for " + key + " and got " + layout);
            }
        }
        return layout;
    }

    public void setCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier, String keyboardLayoutDescriptor) {
        if (!checkCallingPermission("android.permission.SET_KEYBOARD_LAYOUT", "setCurrentKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }
        String key = getLayoutDescriptor(identifier);
        synchronized (this.mDataStore) {
            if (this.mDataStore.setCurrentKeyboardLayout(key, keyboardLayoutDescriptor)) {
                if (DEBUG) {
                    Slog.d(TAG, "Saved keyboard layout using " + key);
                }
                this.mHandler.sendEmptyMessage(3);
            }
            this.mDataStore.saveIfNeeded();
        }
    }

    public String[] getEnabledKeyboardLayoutsForInputDevice(InputDeviceIdentifier identifier) {
        String[] layouts;
        String key = getLayoutDescriptor(identifier);
        synchronized (this.mDataStore) {
            layouts = this.mDataStore.getKeyboardLayouts(key);
            if ((layouts == null || layouts.length == 0) && !key.equals(identifier.getDescriptor())) {
                layouts = this.mDataStore.getKeyboardLayouts(identifier.getDescriptor());
            }
        }
        return layouts;
    }

    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier, String keyboardLayoutDescriptor) {
        if (!checkCallingPermission("android.permission.SET_KEYBOARD_LAYOUT", "addKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }
        String key = getLayoutDescriptor(identifier);
        synchronized (this.mDataStore) {
            String oldLayout = this.mDataStore.getCurrentKeyboardLayout(key);
            if (oldLayout == null && !key.equals(identifier.getDescriptor())) {
                oldLayout = this.mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
            }
            if (this.mDataStore.addKeyboardLayout(key, keyboardLayoutDescriptor) && !Objects.equals(oldLayout, this.mDataStore.getCurrentKeyboardLayout(key))) {
                this.mHandler.sendEmptyMessage(3);
            }
            this.mDataStore.saveIfNeeded();
        }
    }

    public void removeKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier, String keyboardLayoutDescriptor) {
        if (!checkCallingPermission("android.permission.SET_KEYBOARD_LAYOUT", "removeKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }
        String key = getLayoutDescriptor(identifier);
        synchronized (this.mDataStore) {
            String oldLayout = this.mDataStore.getCurrentKeyboardLayout(key);
            if (oldLayout == null && !key.equals(identifier.getDescriptor())) {
                oldLayout = this.mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
            }
            boolean removed = this.mDataStore.removeKeyboardLayout(key, keyboardLayoutDescriptor);
            if (!key.equals(identifier.getDescriptor())) {
                removed |= this.mDataStore.removeKeyboardLayout(identifier.getDescriptor(), keyboardLayoutDescriptor);
            }
            if (removed && !Objects.equals(oldLayout, this.mDataStore.getCurrentKeyboardLayout(key))) {
                this.mHandler.sendEmptyMessage(3);
            }
            this.mDataStore.saveIfNeeded();
        }
    }

    public void switchKeyboardLayout(int deviceId, int direction) {
        this.mHandler.obtainMessage(2, deviceId, direction).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleSwitchKeyboardLayout(int deviceId, int direction) {
        boolean changed;
        String keyboardLayoutDescriptor;
        KeyboardLayout keyboardLayout;
        InputDevice device = getInputDevice(deviceId);
        if (device != null) {
            String key = getLayoutDescriptor(device.getIdentifier());
            synchronized (this.mDataStore) {
                changed = this.mDataStore.switchKeyboardLayout(key, direction);
                keyboardLayoutDescriptor = this.mDataStore.getCurrentKeyboardLayout(key);
                this.mDataStore.saveIfNeeded();
            }
            if (changed) {
                Toast toast = this.mSwitchedKeyboardLayoutToast;
                if (toast != null) {
                    toast.cancel();
                    this.mSwitchedKeyboardLayoutToast = null;
                }
                if (keyboardLayoutDescriptor != null && (keyboardLayout = getKeyboardLayout(keyboardLayoutDescriptor)) != null) {
                    this.mSwitchedKeyboardLayoutToast = Toast.makeText(this.mContext, keyboardLayout.getLabel(), 0);
                    this.mSwitchedKeyboardLayoutToast.show();
                }
                reloadKeyboardLayouts();
            }
        }
    }

    public void setFocusedApplication(int displayId, InputApplicationHandle application) {
        nativeSetFocusedApplication(this.mPtr, displayId, application);
    }

    public void setFocusedDisplay(int displayId) {
        nativeSetFocusedDisplay(this.mPtr, displayId);
    }

    public void onDisplayRemoved(int displayId) {
        nativeSetInputWindows(this.mPtr, null, displayId);
    }

    public void requestPointerCapture(IBinder windowToken, boolean enabled) {
        IWindow iWindow = this.mFocusedWindow;
        if (iWindow == null || iWindow.asBinder() != windowToken) {
            Slog.e(TAG, "requestPointerCapture called for a window that has no focus: " + windowToken);
        } else if (this.mFocusedWindowHasCapture == enabled) {
            StringBuilder sb = new StringBuilder();
            sb.append("requestPointerCapture: already ");
            sb.append(enabled ? "enabled" : "disabled");
            Slog.i(TAG, sb.toString());
        } else {
            setPointerCapture(enabled);
        }
    }

    private void setPointerCapture(boolean enabled) {
        if (this.mFocusedWindowHasCapture != enabled) {
            this.mFocusedWindowHasCapture = enabled;
            try {
                this.mFocusedWindow.dispatchPointerCaptureChanged(enabled);
            } catch (RemoteException e) {
            }
            nativeSetPointerCapture(this.mPtr, enabled);
        }
    }

    public void setInputDispatchMode(boolean enabled, boolean frozen) {
        nativeSetInputDispatchMode(this.mPtr, enabled, frozen);
    }

    public void setSystemUiVisibility(int visibility) {
        nativeSetSystemUiVisibility(this.mPtr, visibility);
    }

    public boolean transferTouchFocus(InputChannel fromChannel, InputChannel toChannel) {
        if (fromChannel == null) {
            throw new IllegalArgumentException("fromChannel must not be null.");
        }
        if (toChannel == null) {
            throw new IllegalArgumentException("toChannel must not be null.");
        }
        return nativeTransferTouchFocus(this.mPtr, fromChannel, toChannel);
    }

    public void tryPointerSpeed(int speed) {
        if (!checkCallingPermission("android.permission.SET_POINTER_SPEED", "tryPointerSpeed()")) {
            throw new SecurityException("Requires SET_POINTER_SPEED permission");
        }
        if (speed < -7 || speed > 7) {
            throw new IllegalArgumentException("speed out of range");
        }
        setPointerSpeedUnchecked(speed);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updatePointerSpeedFromSettings() {
        int speed = getPointerSpeedSetting();
        setPointerSpeedUnchecked(speed);
    }

    private void setPointerSpeedUnchecked(int speed) {
        nativeSetPointerSpeed(this.mPtr, Math.min(Math.max(speed, -7), 7));
    }

    private void registerPointerSpeedSettingObserver() {
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("pointer_speed"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.input.InputManagerService.9
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                InputManagerService.this.updatePointerSpeedFromSettings();
            }
        }, -1);
    }

    private int getPointerSpeedSetting() {
        try {
            int speed = Settings.System.getIntForUser(this.mContext.getContentResolver(), "pointer_speed", -2);
            return speed;
        } catch (Settings.SettingNotFoundException e) {
            return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateShowTouchesFromSettings() {
        int setting = getShowTouchesSetting(0);
        nativeSetShowTouches(this.mPtr, setting != 0);
    }

    private void registerShowTouchesSettingObserver() {
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor("show_touches"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.input.InputManagerService.10
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                InputManagerService.this.updateShowTouchesFromSettings();
            }
        }, -1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateAccessibilityLargePointerFromSettings() {
        boolean z = false;
        int accessibilityConfig = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "accessibility_large_pointer_icon", 0, -2);
        if (accessibilityConfig == 1) {
            z = true;
        }
        PointerIcon.setUseLargeIcons(z);
        nativeReloadPointerIcons(this.mPtr);
    }

    private void registerAccessibilityLargePointerSettingObserver() {
        this.mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("accessibility_large_pointer_icon"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.input.InputManagerService.11
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                InputManagerService.this.updateAccessibilityLargePointerFromSettings();
            }
        }, -1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateDeepPressStatusFromSettings(String reason) {
        int timeout = Settings.Secure.getIntForUser(this.mContext.getContentResolver(), "long_press_timeout", 400, -2);
        boolean z = true;
        boolean featureEnabledFlag = DeviceConfig.getBoolean("input_native_boot", DEEP_PRESS_ENABLED, true);
        boolean enabled = (!featureEnabledFlag || timeout > 400) ? false : false;
        StringBuilder sb = new StringBuilder();
        sb.append(enabled ? "Enabling" : "Disabling");
        sb.append(" motion classifier because ");
        sb.append(reason);
        sb.append(": feature ");
        sb.append(featureEnabledFlag ? "enabled" : "disabled");
        sb.append(", long press timeout = ");
        sb.append(timeout);
        Log.i(TAG, sb.toString());
        nativeSetMotionClassifierEnabled(this.mPtr, enabled);
    }

    private void registerLongPressTimeoutObserver() {
        this.mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("long_press_timeout"), true, new ContentObserver(this.mHandler) { // from class: com.android.server.input.InputManagerService.12
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                InputManagerService.this.updateDeepPressStatusFromSettings("timeout changed");
            }
        }, -1);
    }

    private int getShowTouchesSetting(int defaultValue) {
        try {
            int result = Settings.System.getIntForUser(this.mContext.getContentResolver(), "show_touches", -2);
            return result;
        } catch (Settings.SettingNotFoundException e) {
            return defaultValue;
        }
    }

    public void vibrate(int deviceId, long[] pattern, int repeat, IBinder token) {
        VibratorToken v;
        if (repeat >= pattern.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        synchronized (this.mVibratorLock) {
            v = this.mVibratorTokens.get(token);
            if (v == null) {
                int i = this.mNextVibratorTokenValue;
                this.mNextVibratorTokenValue = i + 1;
                v = new VibratorToken(deviceId, token, i);
                try {
                    token.linkToDeath(v, 0);
                    this.mVibratorTokens.put(token, v);
                } catch (RemoteException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
        synchronized (v) {
            v.mVibrating = true;
            nativeVibrate(this.mPtr, deviceId, pattern, repeat, v.mTokenValue);
        }
    }

    public void cancelVibrate(int deviceId, IBinder token) {
        synchronized (this.mVibratorLock) {
            VibratorToken v = this.mVibratorTokens.get(token);
            if (v != null && v.mDeviceId == deviceId) {
                cancelVibrateIfNeeded(v);
            }
        }
    }

    public void registerListener(IXPKeyListener listener, String id, boolean reg) {
        synchronized (this.mCallbacks) {
            if (reg && listener != null) {
                this.mCallbacks.register(listener, id);
            } else if (!reg && listener != null) {
                this.mCallbacks.unregister(listener);
            }
        }
    }

    public void dispatchKeyToListener(KeyEvent event, int policyFlags) {
        globalCallBack(event, policyFlags);
    }

    public void registerMotionListener(IXPMotionListener listener, String id, boolean reg) {
        synchronized (this.mCallbacks) {
            if (reg && listener != null) {
                this.mMotionCallbacks.register(listener, id);
            } else if (!reg && listener != null) {
                this.mMotionCallbacks.unregister(listener);
            }
        }
    }

    public void dispatchGenericMotionToListener(MotionEvent event, int policyFlags) {
        globalMotionEventCallBack(event, policyFlags);
    }

    void onVibratorTokenDied(VibratorToken v) {
        synchronized (this.mVibratorLock) {
            this.mVibratorTokens.remove(v.mToken);
        }
        cancelVibrateIfNeeded(v);
    }

    private void cancelVibrateIfNeeded(VibratorToken v) {
        synchronized (v) {
            if (v.mVibrating) {
                nativeCancelVibrate(this.mPtr, v.mDeviceId, v.mTokenValue);
                v.mVibrating = false;
            }
        }
    }

    public void setPointerIconType(int iconId) {
        nativeSetPointerIconType(this.mPtr, iconId);
    }

    public void setCustomPointerIcon(PointerIcon icon) {
        Preconditions.checkNotNull(icon);
        nativeSetCustomPointerIcon(this.mPtr, icon);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            pw.println("INPUT MANAGER (dumpsys input)\n");
            String dumpStr = nativeDump(this.mPtr);
            if (dumpStr != null) {
                pw.println(dumpStr);
            }
        }
    }

    private boolean checkCallingPermission(String permission, String func) {
        if (Binder.getCallingPid() == Process.myPid() || this.mContext.checkCallingPermission(permission) == 0) {
            return true;
        }
        String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " requires " + permission;
        Slog.w(TAG, msg);
        return false;
    }

    @Override // com.android.server.Watchdog.Monitor
    public void monitor() {
        synchronized (this.mInputFilterLock) {
        }
        nativeMonitor(this.mPtr);
    }

    private void notifyConfigurationChanged(long whenNanos) {
        this.mWindowManagerCallbacks.notifyConfigurationChanged();
    }

    private void notifyInputDevicesChanged(InputDevice[] inputDevices) {
        synchronized (this.mInputDevicesLock) {
            if (!this.mInputDevicesChangedPending) {
                this.mInputDevicesChangedPending = true;
                this.mHandler.obtainMessage(1, this.mInputDevices).sendToTarget();
            }
            this.mInputDevices = inputDevices;
        }
    }

    private void notifySwitch(long whenNanos, int switchValues, int switchMask) {
        if (DEBUG) {
            Slog.d(TAG, "notifySwitch: values=" + Integer.toHexString(switchValues) + ", mask=" + Integer.toHexString(switchMask));
        }
        if ((switchMask & 1) != 0) {
            boolean lidOpen = (switchValues & 1) == 0;
            this.mWindowManagerCallbacks.notifyLidSwitchChanged(whenNanos, lidOpen);
        }
        if ((switchMask & 512) != 0) {
            boolean lensCovered = (switchValues & 512) != 0;
            this.mWindowManagerCallbacks.notifyCameraLensCoverSwitchChanged(whenNanos, lensCovered);
        }
        boolean lensCovered2 = this.mUseDevInputEventForAudioJack;
        if (lensCovered2 && (switchMask & SW_JACK_BITS) != 0) {
            this.mWiredAccessoryCallbacks.notifyWiredAccessoryChanged(whenNanos, switchValues, switchMask);
        }
        if ((switchMask & 2) != 0) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = (int) ((-1) & whenNanos);
            args.argi2 = (int) (whenNanos >> 32);
            args.arg1 = Boolean.valueOf((switchValues & 2) != 0);
            this.mHandler.obtainMessage(6, args).sendToTarget();
        }
    }

    private void notifyInputChannelBroken(IBinder token) {
        this.mWindowManagerCallbacks.notifyInputChannelBroken(token);
    }

    private void notifyFocusChanged(IBinder oldToken, IBinder newToken) {
        IWindow iWindow = this.mFocusedWindow;
        if (iWindow != null) {
            if (iWindow.asBinder() == newToken) {
                Slog.w(TAG, "notifyFocusChanged called with unchanged mFocusedWindow=" + this.mFocusedWindow);
                return;
            }
            setPointerCapture(false);
        }
        this.mFocusedWindow = IWindow.Stub.asInterface(newToken);
    }

    private long notifyANR(IBinder token, String reason) {
        return this.mWindowManagerCallbacks.notifyANR(token, reason);
    }

    final boolean filterInputEvent(InputEvent event, int policyFlags) {
        synchronized (this.mInputFilterLock) {
            if (this.mInputFilter != null) {
                try {
                    this.mInputFilter.filterInputEvent(event, policyFlags);
                } catch (RemoteException e) {
                }
                return false;
            }
            event.recycle();
            return true;
        }
    }

    private int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        return this.mWindowManagerCallbacks.interceptKeyBeforeQueueing(event, policyFlags);
    }

    private int interceptMotionBeforeQueueingNonInteractive(int displayId, long whenNanos, int policyFlags) {
        return this.mWindowManagerCallbacks.interceptMotionBeforeQueueingNonInteractive(displayId, whenNanos, policyFlags);
    }

    private int globalCallBack(KeyEvent event, int policyFlags) {
        int ret = 0;
        synchronized (this.mCallbacks) {
            if (this.mCallbacks.getRegisteredCallbackCount() > 0) {
                int n = this.mCallbacks.beginBroadcast();
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        try {
                            String id = (String) this.mCallbacks.getBroadcastCookie(i);
                            if (!interceptXPKeyBeforeDispatching(id)) {
                                ret = this.mCallbacks.getBroadcastItem(i).notify(event, String.valueOf(policyFlags));
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                this.mCallbacks.finishBroadcast();
            }
        }
        return ret;
    }

    private int globalMotionEventCallBack(MotionEvent event, int policyFlags) {
        int ret = 0;
        synchronized (this.mMotionCallbacks) {
            if (this.mMotionCallbacks.getRegisteredCallbackCount() > 0) {
                int n = this.mMotionCallbacks.beginBroadcast();
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        try {
                            String str = (String) this.mMotionCallbacks.getBroadcastCookie(i);
                            ret = this.mMotionCallbacks.getBroadcastItem(i).notify(event, String.valueOf(policyFlags));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                this.mMotionCallbacks.finishBroadcast();
            }
        }
        return ret;
    }

    private boolean interceptXPKeyBeforeDispatching(String id) {
        return xpInputDeviceWrapper.ignoreByXpGameMode(id);
    }

    private long interceptKeyBeforeDispatching(IBinder focus, KeyEvent event, int policyFlags) {
        return this.mWindowManagerCallbacks.interceptKeyBeforeDispatching(focus, event, policyFlags);
    }

    private KeyEvent dispatchUnhandledKey(IBinder focus, KeyEvent event, int policyFlags) {
        return this.mWindowManagerCallbacks.dispatchUnhandledKey(focus, event, policyFlags);
    }

    private boolean checkInjectEventsPermission(int injectorPid, int injectorUid) {
        return this.mContext.checkPermission("android.permission.INJECT_EVENTS", injectorPid, injectorUid) == 0;
    }

    private void onPointerDownOutsideFocus(IBinder touchedToken) {
        this.mWindowManagerCallbacks.onPointerDownOutsideFocus(touchedToken);
    }

    private int getVirtualKeyQuietTimeMillis() {
        return this.mContext.getResources().getInteger(17694908);
    }

    private static String[] getExcludedDeviceNames() {
        List<String> names = new ArrayList<>();
        File[] baseDirs = {Environment.getRootDirectory(), Environment.getVendorDirectory()};
        for (File baseDir : baseDirs) {
            File confFile = new File(baseDir, EXCLUDED_DEVICES_PATH);
            try {
                InputStream stream = new FileInputStream(confFile);
                names.addAll(ConfigurationProcessor.processExcludedDeviceNames(stream));
            } catch (FileNotFoundException e) {
            } catch (Exception e2) {
                Slog.e(TAG, "Could not parse '" + confFile.getAbsolutePath() + "'", e2);
            }
        }
        return (String[]) names.toArray(new String[0]);
    }

    private static <T> List<T> flatten(List<Pair<T, T>> pairs) {
        ArrayList arrayList = new ArrayList(pairs.size() * 2);
        for (Pair<T, T> pair : pairs) {
            arrayList.add(pair.first);
            arrayList.add(pair.second);
        }
        return arrayList;
    }

    private static String[] getInputPortAssociations() {
        File baseDir = Environment.getVendorDirectory();
        File confFile = new File(baseDir, PORT_ASSOCIATIONS_PATH);
        try {
            InputStream stream = new FileInputStream(confFile);
            List<Pair<String, String>> associations = ConfigurationProcessor.processInputPortAssociations(stream);
            List<String> associationList = flatten(associations);
            return (String[]) associationList.toArray(new String[0]);
        } catch (FileNotFoundException e) {
            return new String[0];
        } catch (Exception e2) {
            Slog.e(TAG, "Could not parse '" + confFile.getAbsolutePath() + "'", e2);
            return new String[0];
        }
    }

    public boolean canDispatchToDisplay(int deviceId, int displayId) {
        return nativeCanDispatchToDisplay(this.mPtr, deviceId, displayId);
    }

    private int getKeyRepeatTimeout() {
        return ViewConfiguration.getKeyRepeatTimeout();
    }

    private int getKeyRepeatDelay() {
        return ViewConfiguration.getKeyRepeatDelay();
    }

    private int getHoverTapTimeout() {
        return ViewConfiguration.getHoverTapTimeout();
    }

    private int getHoverTapSlop() {
        return ViewConfiguration.getHoverTapSlop();
    }

    private int getDoubleTapTimeout() {
        return ViewConfiguration.getDoubleTapTimeout();
    }

    private int getLongPressTimeout() {
        return ViewConfiguration.getLongPressTimeout();
    }

    private int getPointerLayer() {
        return this.mWindowManagerCallbacks.getPointerLayer();
    }

    private PointerIcon getPointerIcon(int displayId) {
        return PointerIcon.getDefaultIcon(getContextForDisplay(displayId));
    }

    private Context getContextForDisplay(int displayId) {
        Context context = this.mDisplayContext;
        if (context != null && context.getDisplay().getDisplayId() == displayId) {
            return this.mDisplayContext;
        }
        if (this.mContext.getDisplay().getDisplayId() == displayId) {
            this.mDisplayContext = this.mContext;
            return this.mDisplayContext;
        }
        DisplayManager displayManager = (DisplayManager) this.mContext.getSystemService(DisplayManager.class);
        Display display = displayManager.getDisplay(displayId);
        this.mDisplayContext = this.mContext.createDisplayContext(display);
        return this.mDisplayContext;
    }

    private int getPointerDisplayId() {
        return this.mWindowManagerCallbacks.getPointerDisplayId();
    }

    private String[] getKeyboardLayoutOverlay(InputDeviceIdentifier identifier) {
        String keyboardLayoutDescriptor;
        if (this.mSystemReady && (keyboardLayoutDescriptor = getCurrentKeyboardLayoutForInputDevice(identifier)) != null) {
            final String[] result = new String[2];
            visitKeyboardLayout(keyboardLayoutDescriptor, new KeyboardLayoutVisitor() { // from class: com.android.server.input.InputManagerService.13
                @Override // com.android.server.input.InputManagerService.KeyboardLayoutVisitor
                public void visitKeyboardLayout(Resources resources, int keyboardLayoutResId, KeyboardLayout layout) {
                    try {
                        result[0] = layout.getDescriptor();
                        result[1] = Streams.readFully(new InputStreamReader(resources.openRawResource(keyboardLayoutResId)));
                    } catch (Resources.NotFoundException e) {
                    } catch (IOException e2) {
                    }
                }
            });
            if (result[0] == null) {
                Slog.w(TAG, "Could not get keyboard layout with descriptor '" + keyboardLayoutDescriptor + "'.");
                return null;
            }
            return result;
        }
        return null;
    }

    private String getDeviceAlias(String uniqueId) {
        return BluetoothAdapter.checkBluetoothAddress(uniqueId) ? null : null;
    }

    private int requestScreenId(int ownerPid, int ownerUid) {
        return this.mWindowManagerCallbacks.requestScreenId(ownerPid, ownerUid);
    }

    private int requestInputPolicy(InputEvent event, int flags) {
        return this.mWindowManagerCallbacks.requestInputPolicy(event, flags);
    }

    public void setInputPolicy(int policy) {
        xpInputManagerService.get(this.mContext).setInputPolicy(policy);
    }

    public int getInputPolicy() {
        return xpInputManagerService.get(this.mContext).getInputPolicy();
    }

    public void registerInputListener(IInputEventListener listener, String id) {
        xpInputManagerService.get(this.mContext).registerInputListener(listener, id);
    }

    public void unregisterInputListener(IInputEventListener listener, String id) {
        xpInputManagerService.get(this.mContext).unregisterInputListener(listener, id);
    }

    public void dispatchInputEventToListener(InputEvent event, String extra) {
        xpInputManagerService.get(this.mContext).dispatchInputEventToListener(event, extra);
    }

    public void registerGlobalKeyInterceptListener(IXPKeyListener listener, String id) {
        int pid = Binder.getCallingPid();
        Log.i(TAG, "registerGlobalKeyInterceptListener pid:" + pid);
        xpInputManagerService.get(this.mContext).registerGlobalKeyInterceptListener(listener, id, pid);
    }

    public void unregisterGlobalKeyInterceptListener(IXPKeyListener listener, String id) {
        int pid = Binder.getCallingPid();
        Log.i(TAG, "unregisterGlobalKeyInterceptListener pid:" + pid);
        xpInputManagerService.get(this.mContext).unregisterGlobalKeyInterceptListener(listener, id, pid);
    }

    public void registerSpecialKeyInterceptListener(int[] keys, IXPKeyListener listener, String id) {
        int pid = Binder.getCallingPid();
        Log.i(TAG, "registerSpecialKeyInterceptListener pid:" + pid);
        xpInputManagerService.get(this.mContext).registerSpecialKeyInterceptListener(keys, listener, id);
    }

    public void unregisterSpecialKeyInterceptListener(int[] keys, IXPKeyListener listener, String id) {
        int pid = Binder.getCallingPid();
        Log.i(TAG, "unregisterSpecialKeyInterceptListener pid:" + pid);
        xpInputManagerService.get(this.mContext).unregisterSpecialKeyInterceptListener(keys, listener, id);
    }

    public void setInputSourcePolicy(int inputSource, int policy) {
        xpInputManagerService.get(this.mContext).setInputSourcePolicy(inputSource, policy);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InputManagerHandler extends Handler {
        public InputManagerHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    InputManagerService.this.deliverInputDevicesChanged((InputDevice[]) msg.obj);
                    return;
                case 2:
                    InputManagerService.this.handleSwitchKeyboardLayout(msg.arg1, msg.arg2);
                    return;
                case 3:
                    InputManagerService.this.reloadKeyboardLayouts();
                    return;
                case 4:
                    InputManagerService.this.updateKeyboardLayouts();
                    return;
                case 5:
                    InputManagerService.this.reloadDeviceAliases();
                    return;
                case 6:
                    SomeArgs args = (SomeArgs) msg.obj;
                    long whenNanos = (args.argi1 & 4294967295L) | (args.argi2 << 32);
                    boolean inTabletMode = ((Boolean) args.arg1).booleanValue();
                    InputManagerService.this.deliverTabletModeChanged(whenNanos, inTabletMode);
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InputFilterHost extends IInputFilterHost.Stub {
        private boolean mDisconnected;

        private InputFilterHost() {
        }

        public void disconnectLocked() {
            this.mDisconnected = true;
        }

        public void sendInputEvent(InputEvent event, int policyFlags) {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }
            synchronized (InputManagerService.this.mInputFilterLock) {
                if (!this.mDisconnected) {
                    InputManagerService.nativeInjectInputEvent(InputManagerService.this.mPtr, event, 0, 0, 0, 0, policyFlags | 67108864);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private final class InputMonitorHost extends IInputMonitorHost.Stub {
        private final InputChannel mInputChannel;

        InputMonitorHost(InputChannel channel) {
            this.mInputChannel = channel;
        }

        public void pilferPointers() {
            InputManagerService.nativePilferPointers(InputManagerService.this.mPtr, asBinder());
        }

        public void dispose() {
            InputManagerService.nativeUnregisterInputChannel(InputManagerService.this.mPtr, this.mInputChannel);
            this.mInputChannel.dispose();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class KeyboardLayoutDescriptor {
        public String keyboardLayoutName;
        public String packageName;
        public String receiverName;

        private KeyboardLayoutDescriptor() {
        }

        public static String format(String packageName, String receiverName, String keyboardName) {
            return packageName + SliceClientPermissions.SliceAuthority.DELIMITER + receiverName + SliceClientPermissions.SliceAuthority.DELIMITER + keyboardName;
        }

        public static KeyboardLayoutDescriptor parse(String descriptor) {
            int pos2;
            int pos = descriptor.indexOf(47);
            if (pos < 0 || pos + 1 == descriptor.length() || (pos2 = descriptor.indexOf(47, pos + 1)) < pos + 2 || pos2 + 1 == descriptor.length()) {
                return null;
            }
            KeyboardLayoutDescriptor result = new KeyboardLayoutDescriptor();
            result.packageName = descriptor.substring(0, pos);
            result.receiverName = descriptor.substring(pos + 1, pos2);
            result.keyboardLayoutName = descriptor.substring(pos2 + 1);
            return result;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InputDevicesChangedListenerRecord implements IBinder.DeathRecipient {
        private final IInputDevicesChangedListener mListener;
        private final int mPid;

        public InputDevicesChangedListenerRecord(int pid, IInputDevicesChangedListener listener) {
            this.mPid = pid;
            this.mListener = listener;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (InputManagerService.DEBUG) {
                Slog.d(InputManagerService.TAG, "Input devices changed listener for pid " + this.mPid + " died.");
            }
            InputManagerService.this.onInputDevicesChangedListenerDied(this.mPid);
        }

        public void notifyInputDevicesChanged(int[] info) {
            try {
                this.mListener.onInputDevicesChanged(info);
            } catch (RemoteException ex) {
                Slog.w(InputManagerService.TAG, "Failed to notify process " + this.mPid + " that input devices changed, assuming it died.", ex);
                binderDied();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class TabletModeChangedListenerRecord implements IBinder.DeathRecipient {
        private final ITabletModeChangedListener mListener;
        private final int mPid;

        public TabletModeChangedListenerRecord(int pid, ITabletModeChangedListener listener) {
            this.mPid = pid;
            this.mListener = listener;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (InputManagerService.DEBUG) {
                Slog.d(InputManagerService.TAG, "Tablet mode changed listener for pid " + this.mPid + " died.");
            }
            InputManagerService.this.onTabletModeChangedListenerDied(this.mPid);
        }

        public void notifyTabletModeChanged(long whenNanos, boolean inTabletMode) {
            try {
                this.mListener.onTabletModeChanged(whenNanos, inTabletMode);
            } catch (RemoteException ex) {
                Slog.w(InputManagerService.TAG, "Failed to notify process " + this.mPid + " that tablet mode changed, assuming it died.", ex);
                binderDied();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class VibratorToken implements IBinder.DeathRecipient {
        public final int mDeviceId;
        public final IBinder mToken;
        public final int mTokenValue;
        public boolean mVibrating;

        public VibratorToken(int deviceId, IBinder token, int tokenValue) {
            this.mDeviceId = deviceId;
            this.mToken = token;
            this.mTokenValue = tokenValue;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            if (InputManagerService.DEBUG) {
                Slog.d(InputManagerService.TAG, "Vibrator token died.");
            }
            InputManagerService.this.onVibratorTokenDied(this);
        }
    }

    /* loaded from: classes.dex */
    private final class LocalService extends InputManagerInternal {
        private LocalService() {
        }

        public void setDisplayViewports(List<DisplayViewport> viewports) {
            InputManagerService.this.setDisplayViewportsInternal(viewports);
        }

        public boolean injectInputEvent(InputEvent event, int mode) {
            return InputManagerService.this.injectInputEventInternal(event, mode);
        }

        public void setInteractive(boolean interactive) {
            InputManagerService.nativeSetInteractive(InputManagerService.this.mPtr, interactive);
        }

        public void toggleCapsLock(int deviceId) {
            InputManagerService.nativeToggleCapsLock(InputManagerService.this.mPtr, deviceId);
        }

        public void setPulseGestureEnabled(boolean enabled) {
            if (InputManagerService.this.mDoubleTouchGestureEnableFile != null) {
                FileWriter writer = null;
                try {
                    try {
                        writer = new FileWriter(InputManagerService.this.mDoubleTouchGestureEnableFile);
                        writer.write(enabled ? "1" : "0");
                    } catch (IOException e) {
                        Log.wtf(InputManagerService.TAG, "Unable to setPulseGestureEnabled", e);
                    }
                } finally {
                    IoUtils.closeQuietly(writer);
                }
            }
        }
    }
}
