package com.android.server.adb;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.debug.AdbManagerInternal;
import android.debug.IAdbManager;
import android.debug.IAdbTransport;
import android.os.Build;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.sysprop.AdbProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.adb.AdbService;
import com.xiaopeng.app.xpActivityManager;
import java.io.File;
import java.io.IOException;

/* loaded from: classes.dex */
public class AdbService extends IAdbManager.Stub {
    private static final boolean DEBUG = false;
    private static final int MSG_BOOT_COMPLETED = 2;
    private static final int MSG_ENABLE_ADB = 1;
    private static final int MSG_ENABLE_XP_DEBUG = 3;
    private static final int MSG_XP_USB_MODE = 4;
    private static final String PROPERTY_XP_DEBUG = "persist.sys.xp.debug.state";
    private static final String PROPERTY_XP_USB_MODE = "sys.xp.usb_mode";
    private static final String TAG = "AdbService";
    private static final String USB_MODE_CONFIG = "/sys/devices/platform/soc/a600000.ssusb/mode";
    private static final String USB_PERIPHERAL = "peripheral";
    private static final String USB_PERSISTENT_CONFIG_PROPERTY = "persist.sys.usb.config";
    private static final String XP_DEBUG_DEBUG_ON = "debug_on";
    private static final String XP_DEBUG_OFF = "off";
    private static final String XP_DEBUG_USER_ON = "user_on";
    private boolean mAdbEnabled;
    private IBinder mBinder;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final IBinder.DeathRecipient mDeathRecipient;
    private AdbDebuggingManager mDebuggingManager;
    private final AdbHandler mHandler;
    private final ArrayMap<IBinder, IAdbTransport> mTransports;
    private boolean mXpDebugEnabled;

    /* synthetic */ AdbService(Context x0, AnonymousClass1 x1) {
        this(x0);
    }

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        private AdbService mAdbService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mAdbService = new AdbService(getContext(), null);
            publishBinderService("adb", this.mAdbService);
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            if (phase == 550) {
                this.mAdbService.systemReady();
            } else if (phase == 1000) {
                this.mAdbService.bootCompleted();
            }
        }
    }

    /* loaded from: classes.dex */
    private class AdbManagerInternalImpl extends AdbManagerInternal {
        private AdbManagerInternalImpl() {
        }

        /* synthetic */ AdbManagerInternalImpl(AdbService x0, AnonymousClass1 x1) {
            this();
        }

        public void registerTransport(IAdbTransport transport) {
            AdbService.this.mTransports.put(transport.asBinder(), transport);
        }

        public void unregisterTransport(IAdbTransport transport) {
            AdbService.this.mTransports.remove(transport.asBinder());
        }

        public boolean isAdbEnabled() {
            return AdbService.this.mAdbEnabled;
        }

        public File getAdbKeysFile() {
            return AdbService.this.mDebuggingManager.getUserKeyFile();
        }

        public File getAdbTempKeysFile() {
            return AdbService.this.mDebuggingManager.getAdbTempKeysFile();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AdbHandler extends Handler {
        AdbHandler(Looper looper) {
            super(looper);
            try {
                AdbService.this.mAdbEnabled = containsFunction(SystemProperties.get(AdbService.USB_PERSISTENT_CONFIG_PROPERTY, ""), "adb");
                String mode = FileUtils.readTextFile(new File(AdbService.USB_MODE_CONFIG), AdbService.USB_PERIPHERAL.length(), null);
                Slog.i(AdbService.TAG, "usb mode: " + mode);
                if (AdbService.USB_PERIPHERAL.equals(mode)) {
                    AdbService.this.mXpDebugEnabled = true;
                }
                AdbService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("adb_enabled"), false, new AdbSettingsObserver());
                AdbService.this.mContentResolver.registerContentObserver(Settings.Global.getUriFor("xp_usb_mode"), false, new XpUsbSettingsObserver());
            } catch (Exception e) {
                Slog.e(AdbService.TAG, "Error initializing AdbHandler", e);
            }
        }

        private boolean containsFunction(String functions, String function) {
            int index = functions.indexOf(function);
            if (index < 0) {
                return false;
            }
            if (index > 0 && functions.charAt(index - 1) != ',') {
                return false;
            }
            int charAfter = function.length() + index;
            if (charAfter < functions.length() && functions.charAt(charAfter) != ',') {
                return false;
            }
            return true;
        }

        public void sendMessage(int what, boolean arg) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.arg1 = arg ? 1 : 0;
            sendMessage(m);
        }

        public void sendMessage(int what, String arg) {
            removeMessages(what);
            Message m = Message.obtain(this, what);
            m.obj = arg;
            sendMessage(m);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                AdbService.this.setAdbEnabled(msg.arg1 == 1);
            } else if (i == 2) {
                if (AdbService.this.mDebuggingManager != null) {
                    AdbService.this.mDebuggingManager.setAdbEnabled(AdbService.this.mAdbEnabled);
                }
            } else if (i == 3) {
                AdbService.this.setXpDebugEnabled(msg.arg1 == 1);
            } else if (i == 4) {
                String arg = (String) msg.obj;
                if (!TextUtils.isEmpty(arg)) {
                    AdbService.this.switchUsbDeviceEnable("usb_device".equals(arg));
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private class AdbSettingsObserver extends ContentObserver {
        AdbSettingsObserver() {
            super(null);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            boolean enable = Settings.Global.getInt(AdbService.this.mContentResolver, "adb_enabled", 0) > 0;
            AdbService.this.mHandler.sendMessage(1, enable);
        }
    }

    /* loaded from: classes.dex */
    private class XpUsbSettingsObserver extends ContentObserver {
        public XpUsbSettingsObserver() {
            super(null);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange) {
            String usbMode = Settings.Global.getString(AdbService.this.mContentResolver, "xp_usb_mode");
            AdbService.this.mHandler.sendMessage(4, usbMode);
        }
    }

    private AdbService(Context context) {
        this.mTransports = new ArrayMap<>();
        this.mContext = context;
        this.mContentResolver = context.getContentResolver();
        boolean secureAdbEnabled = ((Boolean) AdbProperties.secure().orElse(false)).booleanValue();
        boolean dataEncrypted = "1".equals(SystemProperties.get("vold.decrypt"));
        if (secureAdbEnabled && !dataEncrypted) {
            this.mDebuggingManager = new AdbDebuggingManager(context);
        }
        this.mHandler = new AdbHandler(FgThread.get().getLooper());
        this.mDeathRecipient = new AnonymousClass1();
        LocalServices.addService(AdbManagerInternal.class, new AdbManagerInternalImpl(this, null));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: com.android.server.adb.AdbService$1  reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 implements IBinder.DeathRecipient {
        AnonymousClass1() {
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            Slog.i(AdbService.TAG, "XpUsbMode binder is died");
            AdbService.this.mHandler.post(new Runnable() { // from class: com.android.server.adb.-$$Lambda$AdbService$1$lk1pqfC-USUqnjT88Dg7TjsHREU
                @Override // java.lang.Runnable
                public final void run() {
                    AdbService.AnonymousClass1.this.lambda$binderDied$0$AdbService$1();
                }
            });
        }

        public /* synthetic */ void lambda$binderDied$0$AdbService$1() {
            AdbService adbService = AdbService.this;
            adbService.unlinkToDeath(adbService.mDeathRecipient, 0);
            if (AdbService.this.mXpDebugEnabled) {
                SystemProperties.set(AdbService.PROPERTY_XP_USB_MODE, "usb_device");
            } else {
                SystemProperties.set(AdbService.PROPERTY_XP_USB_MODE, "usb_host");
            }
            AdbService.this.mBinder = null;
        }
    }

    public void systemReady() {
        try {
            Settings.Global.putInt(this.mContentResolver, "adb_enabled", this.mAdbEnabled ? 1 : 0);
            Settings.Global.putString(this.mContentResolver, "xp_usb_mode", this.mXpDebugEnabled ? "usb_device" : "usb_host");
        } catch (SecurityException e) {
            Slog.d(TAG, "ADB_ENABLED is restricted.");
        }
    }

    public void bootCompleted() {
        this.mHandler.sendEmptyMessage(2);
    }

    public void allowDebugging(boolean alwaysAllow, String publicKey) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_DEBUGGING", null);
        AdbDebuggingManager adbDebuggingManager = this.mDebuggingManager;
        if (adbDebuggingManager != null) {
            adbDebuggingManager.allowDebugging(alwaysAllow, publicKey);
        }
    }

    public void denyDebugging() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_DEBUGGING", null);
        AdbDebuggingManager adbDebuggingManager = this.mDebuggingManager;
        if (adbDebuggingManager != null) {
            adbDebuggingManager.denyDebugging();
        }
    }

    public void clearDebuggingKeys() {
        this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_DEBUGGING", null);
        AdbDebuggingManager adbDebuggingManager = this.mDebuggingManager;
        if (adbDebuggingManager != null) {
            adbDebuggingManager.clearDebuggingKeys();
            return;
        }
        throw new RuntimeException("Cannot clear ADB debugging keys, AdbDebuggingManager not enabled");
    }

    public boolean isDebugEnabled() {
        boolean z;
        synchronized (this) {
            z = this.mXpDebugEnabled;
        }
        return z;
    }

    public void setDebugStatus(boolean status) {
        this.mHandler.sendMessage(3, status);
    }

    public void registerXpUsbMode(final IBinder binder) {
        if (binder == null) {
            throw new NullPointerException("binder cannot be null");
        }
        final int pid = getCallingPid();
        final String processName = xpActivityManager.getProcessName(this.mContext, pid);
        this.mHandler.post(new Runnable() { // from class: com.android.server.adb.-$$Lambda$AdbService$IpY2HGxrdOIatp7r4a39gZ2Bwl8
            @Override // java.lang.Runnable
            public final void run() {
                AdbService.this.lambda$registerXpUsbMode$0$AdbService(binder, pid, processName);
            }
        });
    }

    public /* synthetic */ void lambda$registerXpUsbMode$0$AdbService(IBinder binder, int pid, String processName) {
        if (this.mBinder == binder) {
            return;
        }
        Slog.i(TAG, "registerXpUsbMode pid: " + pid + ", processName: " + processName + ", binder: " + binder);
        IBinder iBinder = this.mBinder;
        if (iBinder != null) {
            iBinder.unlinkToDeath(this.mDeathRecipient, 0);
            this.mBinder = null;
        }
        this.mBinder = binder;
        try {
            this.mBinder.linkToDeath(this.mDeathRecipient, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void unregisterXpUsbMode(final IBinder binder) {
        if (binder == null) {
            throw new NullPointerException("binder cannot be null");
        }
        final int pid = getCallingPid();
        final String processName = xpActivityManager.getProcessName(this.mContext, pid);
        this.mHandler.post(new Runnable() { // from class: com.android.server.adb.-$$Lambda$AdbService$QtZ8aqBK81zAF5eSDWadwtu6bDk
            @Override // java.lang.Runnable
            public final void run() {
                AdbService.this.lambda$unregisterXpUsbMode$1$AdbService(pid, processName, binder);
            }
        });
    }

    public /* synthetic */ void lambda$unregisterXpUsbMode$1$AdbService(int pid, String processName, IBinder binder) {
        if (this.mBinder != null) {
            Slog.i(TAG, "unregisterXpUsbMode pid: " + pid + ", processName: " + processName + ", binder: " + binder);
            this.mBinder.unlinkToDeath(this.mDeathRecipient, 0);
            if (this.mXpDebugEnabled) {
                SystemProperties.set(PROPERTY_XP_USB_MODE, "usb_device");
            } else {
                SystemProperties.set(PROPERTY_XP_USB_MODE, "usb_host");
            }
            this.mBinder = null;
        }
    }

    public void enableXpUsbDevice(boolean enable) {
        int pid = getCallingPid();
        String processName = xpActivityManager.getProcessName(this.mContext, pid);
        Slog.i(TAG, "enableXpUsbDevice pid: " + pid + ", processName: " + processName + ", enable: " + enable);
        if (enable) {
            SystemProperties.set(PROPERTY_XP_USB_MODE, "usb_device");
        } else {
            SystemProperties.set(PROPERTY_XP_USB_MODE, "usb_host");
        }
    }

    public boolean isXpUsbDevice() {
        String mode = null;
        try {
            mode = FileUtils.readTextFile(new File(USB_MODE_CONFIG), USB_PERIPHERAL.length(), null);
        } catch (IOException e) {
            Slog.w(TAG, "isXpUsbDevice --> " + e.getMessage());
        }
        Slog.i(TAG, "isXpUsbDevice usb mode: " + mode);
        if (USB_PERIPHERAL.equals(mode)) {
            return true;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setAdbEnabled(boolean enable) {
        if (enable == this.mAdbEnabled) {
            return;
        }
        this.mAdbEnabled = enable;
        for (IAdbTransport transport : this.mTransports.values()) {
            try {
                transport.onAdbEnabled(enable);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to send onAdbEnabled to transport " + transport.toString());
            }
        }
        AdbDebuggingManager adbDebuggingManager = this.mDebuggingManager;
        if (adbDebuggingManager != null) {
            adbDebuggingManager.setAdbEnabled(enable);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setXpDebugEnabled(boolean enable) {
        boolean z = this.mAdbEnabled;
        if (enable != z && enable && !z) {
            Settings.Global.putInt(this.mContentResolver, "adb_enabled", 1);
        }
        switchUsbDeviceEnable(enable);
        Settings.Global.putString(this.mContentResolver, "xp_usb_mode", enable ? "usb_device" : "usb_host");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void switchUsbDeviceEnable(boolean enable) {
        String debugMode;
        if (enable == this.mXpDebugEnabled) {
            return;
        }
        synchronized (this) {
            this.mXpDebugEnabled = enable;
        }
        if (enable) {
            debugMode = Build.IS_USER ? XP_DEBUG_USER_ON : XP_DEBUG_DEBUG_ON;
        } else {
            debugMode = XP_DEBUG_OFF;
        }
        SystemProperties.set(PROPERTY_XP_DEBUG, debugMode);
    }

    /* JADX WARN: Code restructure failed: missing block: B:17:0x0046, code lost:
        r1 = new com.android.internal.util.dump.DualDumpOutputStream(new android.util.proto.ProtoOutputStream(r11));
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void dump(java.io.FileDescriptor r11, java.io.PrintWriter r12, java.lang.String[] r13) {
        /*
            r10 = this;
            android.content.Context r0 = r10.mContext
            java.lang.String r1 = "AdbService"
            boolean r0 = com.android.internal.util.DumpUtils.checkDumpPermission(r0, r1, r12)
            if (r0 != 0) goto Lb
            return
        Lb:
            com.android.internal.util.IndentingPrintWriter r0 = new com.android.internal.util.IndentingPrintWriter
            java.lang.String r1 = "  "
            r0.<init>(r12, r1)
            long r2 = android.os.Binder.clearCallingIdentity()
            android.util.ArraySet r4 = new android.util.ArraySet     // Catch: java.lang.Throwable -> L7a
            r4.<init>()     // Catch: java.lang.Throwable -> L7a
            java.util.Collections.addAll(r4, r13)     // Catch: java.lang.Throwable -> L7a
            r5 = 0
            java.lang.String r6 = "--proto"
            boolean r6 = r4.contains(r6)     // Catch: java.lang.Throwable -> L7a
            if (r6 == 0) goto L28
            r5 = 1
        L28:
            int r6 = r4.size()     // Catch: java.lang.Throwable -> L7a
            if (r6 == 0) goto L44
            java.lang.String r6 = "-a"
            boolean r6 = r4.contains(r6)     // Catch: java.lang.Throwable -> L7a
            if (r6 != 0) goto L44
            if (r5 == 0) goto L39
            goto L44
        L39:
            java.lang.String r1 = "Dump current ADB state"
            r0.println(r1)     // Catch: java.lang.Throwable -> L7a
            java.lang.String r1 = "  No commands available"
            r0.println(r1)     // Catch: java.lang.Throwable -> L7a
            goto L75
        L44:
            if (r5 == 0) goto L51
            com.android.internal.util.dump.DualDumpOutputStream r1 = new com.android.internal.util.dump.DualDumpOutputStream     // Catch: java.lang.Throwable -> L7a
            android.util.proto.ProtoOutputStream r6 = new android.util.proto.ProtoOutputStream     // Catch: java.lang.Throwable -> L7a
            r6.<init>(r11)     // Catch: java.lang.Throwable -> L7a
            r1.<init>(r6)     // Catch: java.lang.Throwable -> L7a
            goto L61
        L51:
            java.lang.String r6 = "ADB MANAGER STATE (dumpsys adb):"
            r0.println(r6)     // Catch: java.lang.Throwable -> L7a
            com.android.internal.util.dump.DualDumpOutputStream r6 = new com.android.internal.util.dump.DualDumpOutputStream     // Catch: java.lang.Throwable -> L7a
            com.android.internal.util.IndentingPrintWriter r7 = new com.android.internal.util.IndentingPrintWriter     // Catch: java.lang.Throwable -> L7a
            r7.<init>(r0, r1)     // Catch: java.lang.Throwable -> L7a
            r6.<init>(r7)     // Catch: java.lang.Throwable -> L7a
            r1 = r6
        L61:
            com.android.server.adb.AdbDebuggingManager r6 = r10.mDebuggingManager     // Catch: java.lang.Throwable -> L7a
            if (r6 == 0) goto L71
            com.android.server.adb.AdbDebuggingManager r6 = r10.mDebuggingManager     // Catch: java.lang.Throwable -> L7a
            java.lang.String r7 = "debugging_manager"
            r8 = 1146756268033(0x10b00000001, double:5.66572876188E-312)
            r6.dump(r1, r7, r8)     // Catch: java.lang.Throwable -> L7a
        L71:
            r1.flush()     // Catch: java.lang.Throwable -> L7a
        L75:
            android.os.Binder.restoreCallingIdentity(r2)
            return
        L7a:
            r1 = move-exception
            android.os.Binder.restoreCallingIdentity(r2)
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.adb.AdbService.dump(java.io.FileDescriptor, java.io.PrintWriter, java.lang.String[]):void");
    }
}
