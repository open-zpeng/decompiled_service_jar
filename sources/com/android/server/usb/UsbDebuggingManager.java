package com.android.server.usb;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Base64;
import android.util.Slog;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.dump.DumpUtils;
import com.android.server.FgThread;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.usb.descriptors.UsbDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
/* loaded from: classes.dex */
public class UsbDebuggingManager {
    private static final String ADBD_SOCKET = "adbd";
    private static final String ADB_DIRECTORY = "misc/adb";
    private static final String ADB_KEYS_FILE = "adb_keys";
    private static final int BUFFER_SIZE = 4096;
    private static final boolean DEBUG = false;
    private static final String TAG = "UsbDebuggingManager";
    private final Context mContext;
    private String mFingerprints;
    private UsbDebuggingThread mThread;
    private boolean mAdbEnabled = false;
    private final Handler mHandler = new UsbDebuggingHandler(FgThread.get().getLooper());

    public UsbDebuggingManager(Context context) {
        this.mContext = context;
    }

    /* loaded from: classes.dex */
    class UsbDebuggingThread extends Thread {
        private InputStream mInputStream;
        private OutputStream mOutputStream;
        private LocalSocket mSocket;
        private boolean mStopped;

        UsbDebuggingThread() {
            super(UsbDebuggingManager.TAG);
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            while (true) {
                synchronized (this) {
                    if (this.mStopped) {
                        return;
                    }
                    try {
                        openSocketLocked();
                    } catch (Exception e) {
                        SystemClock.sleep(1000L);
                    }
                }
                try {
                    listenToSocket();
                } catch (Exception e2) {
                    SystemClock.sleep(1000L);
                }
            }
        }

        private void openSocketLocked() throws IOException {
            try {
                LocalSocketAddress address = new LocalSocketAddress(UsbDebuggingManager.ADBD_SOCKET, LocalSocketAddress.Namespace.RESERVED);
                this.mInputStream = null;
                this.mSocket = new LocalSocket();
                this.mSocket.connect(address);
                this.mOutputStream = this.mSocket.getOutputStream();
                this.mInputStream = this.mSocket.getInputStream();
            } catch (IOException ioe) {
                closeSocketLocked();
                throw ioe;
            }
        }

        /* JADX WARN: Code restructure failed: missing block: B:12:0x0053, code lost:
            android.util.Slog.e(com.android.server.usb.UsbDebuggingManager.TAG, "Wrong message: " + new java.lang.String(java.util.Arrays.copyOfRange(r0, 0, 2)));
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        private void listenToSocket() throws java.io.IOException {
            /*
                r7 = this;
                r0 = 4096(0x1000, float:5.74E-42)
                byte[] r0 = new byte[r0]     // Catch: java.lang.Throwable -> L7d
            L4:
                java.io.InputStream r1 = r7.mInputStream     // Catch: java.lang.Throwable -> L7d
                int r1 = r1.read(r0)     // Catch: java.lang.Throwable -> L7d
                if (r1 >= 0) goto Ld
                goto L73
            Ld:
                r2 = 0
                r3 = r0[r2]     // Catch: java.lang.Throwable -> L7d
                r4 = 80
                r5 = 2
                if (r3 != r4) goto L53
                r3 = 1
                r3 = r0[r3]     // Catch: java.lang.Throwable -> L7d
                r4 = 75
                if (r3 != r4) goto L53
                java.lang.String r2 = new java.lang.String     // Catch: java.lang.Throwable -> L7d
                byte[] r3 = java.util.Arrays.copyOfRange(r0, r5, r1)     // Catch: java.lang.Throwable -> L7d
                r2.<init>(r3)     // Catch: java.lang.Throwable -> L7d
                java.lang.String r3 = "UsbDebuggingManager"
                java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L7d
                r4.<init>()     // Catch: java.lang.Throwable -> L7d
                java.lang.String r5 = "Received public key: "
                r4.append(r5)     // Catch: java.lang.Throwable -> L7d
                r4.append(r2)     // Catch: java.lang.Throwable -> L7d
                java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L7d
                android.util.Slog.d(r3, r4)     // Catch: java.lang.Throwable -> L7d
                com.android.server.usb.UsbDebuggingManager r3 = com.android.server.usb.UsbDebuggingManager.this     // Catch: java.lang.Throwable -> L7d
                android.os.Handler r3 = com.android.server.usb.UsbDebuggingManager.access$000(r3)     // Catch: java.lang.Throwable -> L7d
                r4 = 5
                android.os.Message r3 = r3.obtainMessage(r4)     // Catch: java.lang.Throwable -> L7d
                r3.obj = r2     // Catch: java.lang.Throwable -> L7d
                com.android.server.usb.UsbDebuggingManager r4 = com.android.server.usb.UsbDebuggingManager.this     // Catch: java.lang.Throwable -> L7d
                android.os.Handler r4 = com.android.server.usb.UsbDebuggingManager.access$000(r4)     // Catch: java.lang.Throwable -> L7d
                r4.sendMessage(r3)     // Catch: java.lang.Throwable -> L7d
                goto L4
            L53:
                java.lang.String r3 = "UsbDebuggingManager"
                java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L7d
                r4.<init>()     // Catch: java.lang.Throwable -> L7d
                java.lang.String r6 = "Wrong message: "
                r4.append(r6)     // Catch: java.lang.Throwable -> L7d
                java.lang.String r6 = new java.lang.String     // Catch: java.lang.Throwable -> L7d
                byte[] r2 = java.util.Arrays.copyOfRange(r0, r2, r5)     // Catch: java.lang.Throwable -> L7d
                r6.<init>(r2)     // Catch: java.lang.Throwable -> L7d
                r4.append(r6)     // Catch: java.lang.Throwable -> L7d
                java.lang.String r2 = r4.toString()     // Catch: java.lang.Throwable -> L7d
                android.util.Slog.e(r3, r2)     // Catch: java.lang.Throwable -> L7d
            L73:
                monitor-enter(r7)
                r7.closeSocketLocked()     // Catch: java.lang.Throwable -> L7a
                monitor-exit(r7)     // Catch: java.lang.Throwable -> L7a
                return
            L7a:
                r0 = move-exception
                monitor-exit(r7)     // Catch: java.lang.Throwable -> L7a
                throw r0
            L7d:
                r0 = move-exception
                monitor-enter(r7)
                r7.closeSocketLocked()     // Catch: java.lang.Throwable -> L84
                monitor-exit(r7)     // Catch: java.lang.Throwable -> L84
                throw r0
            L84:
                r0 = move-exception
                monitor-exit(r7)     // Catch: java.lang.Throwable -> L84
                throw r0
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbDebuggingManager.UsbDebuggingThread.listenToSocket():void");
        }

        private void closeSocketLocked() {
            try {
                if (this.mOutputStream != null) {
                    this.mOutputStream.close();
                    this.mOutputStream = null;
                }
            } catch (IOException e) {
                Slog.e(UsbDebuggingManager.TAG, "Failed closing output stream: " + e);
            }
            try {
                if (this.mSocket != null) {
                    this.mSocket.close();
                    this.mSocket = null;
                }
            } catch (IOException ex) {
                Slog.e(UsbDebuggingManager.TAG, "Failed closing socket: " + ex);
            }
        }

        void stopListening() {
            synchronized (this) {
                this.mStopped = true;
                closeSocketLocked();
            }
        }

        void sendResponse(String msg) {
            synchronized (this) {
                if (!this.mStopped && this.mOutputStream != null) {
                    try {
                        this.mOutputStream.write(msg.getBytes());
                    } catch (IOException ex) {
                        Slog.e(UsbDebuggingManager.TAG, "Failed to write response:", ex);
                    }
                }
            }
        }
    }

    /* loaded from: classes.dex */
    class UsbDebuggingHandler extends Handler {
        private static final int MESSAGE_ADB_ALLOW = 3;
        private static final int MESSAGE_ADB_CLEAR = 6;
        private static final int MESSAGE_ADB_CONFIRM = 5;
        private static final int MESSAGE_ADB_DENY = 4;
        private static final int MESSAGE_ADB_DISABLED = 2;
        private static final int MESSAGE_ADB_ENABLED = 1;

        public UsbDebuggingHandler(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    if (!UsbDebuggingManager.this.mAdbEnabled) {
                        UsbDebuggingManager.this.mAdbEnabled = true;
                        UsbDebuggingManager.this.mThread = new UsbDebuggingThread();
                        UsbDebuggingManager.this.mThread.start();
                        return;
                    }
                    return;
                case 2:
                    if (UsbDebuggingManager.this.mAdbEnabled) {
                        UsbDebuggingManager.this.mAdbEnabled = false;
                        if (UsbDebuggingManager.this.mThread != null) {
                            UsbDebuggingManager.this.mThread.stopListening();
                            UsbDebuggingManager.this.mThread = null;
                            return;
                        }
                        return;
                    }
                    return;
                case 3:
                    String key = (String) msg.obj;
                    String fingerprints = UsbDebuggingManager.this.getFingerprints(key);
                    if (!fingerprints.equals(UsbDebuggingManager.this.mFingerprints)) {
                        Slog.e(UsbDebuggingManager.TAG, "Fingerprints do not match. Got " + fingerprints + ", expected " + UsbDebuggingManager.this.mFingerprints);
                        return;
                    }
                    if (msg.arg1 == 1) {
                        UsbDebuggingManager.this.writeKey(key);
                    }
                    if (UsbDebuggingManager.this.mThread != null) {
                        UsbDebuggingManager.this.mThread.sendResponse("OK");
                        return;
                    }
                    return;
                case 4:
                    if (UsbDebuggingManager.this.mThread != null) {
                        UsbDebuggingManager.this.mThread.sendResponse("NO");
                        return;
                    }
                    return;
                case 5:
                    if ("trigger_restart_min_framework".equals(SystemProperties.get("vold.decrypt"))) {
                        Slog.d(UsbDebuggingManager.TAG, "Deferring adb confirmation until after vold decrypt");
                        if (UsbDebuggingManager.this.mThread != null) {
                            UsbDebuggingManager.this.mThread.sendResponse("NO");
                            return;
                        }
                        return;
                    }
                    String key2 = (String) msg.obj;
                    String fingerprints2 = UsbDebuggingManager.this.getFingerprints(key2);
                    if (BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS.equals(fingerprints2)) {
                        if (UsbDebuggingManager.this.mThread != null) {
                            UsbDebuggingManager.this.mThread.sendResponse("NO");
                            return;
                        }
                        return;
                    }
                    UsbDebuggingManager.this.mFingerprints = fingerprints2;
                    UsbDebuggingManager.this.startConfirmation(key2, UsbDebuggingManager.this.mFingerprints);
                    return;
                case 6:
                    UsbDebuggingManager.this.deleteKeyFile();
                    return;
                default:
                    return;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getFingerprints(String key) {
        StringBuilder sb = new StringBuilder();
        if (key == null) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        try {
            MessageDigest digester = MessageDigest.getInstance("MD5");
            byte[] base64_data = key.split("\\s+")[0].getBytes();
            try {
                byte[] digest = digester.digest(Base64.decode(base64_data, 0));
                for (int i = 0; i < digest.length; i++) {
                    sb.append("0123456789ABCDEF".charAt((digest[i] >> 4) & 15));
                    sb.append("0123456789ABCDEF".charAt(digest[i] & UsbDescriptor.DESCRIPTORTYPE_BOS));
                    if (i < digest.length - 1) {
                        sb.append(":");
                    }
                }
                return sb.toString();
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "error doing base64 decoding", e);
                return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            }
        } catch (Exception ex) {
            Slog.e(TAG, "Error getting digester", ex);
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startConfirmation(String key, String fingerprints) {
        String componentString;
        int currentUserId = ActivityManager.getCurrentUser();
        UserInfo userInfo = UserManager.get(this.mContext).getUserInfo(currentUserId);
        if (userInfo.isAdmin()) {
            componentString = Resources.getSystem().getString(17039644);
        } else {
            componentString = Resources.getSystem().getString(17039645);
        }
        ComponentName componentName = ComponentName.unflattenFromString(componentString);
        if (startConfirmationActivity(componentName, userInfo.getUserHandle(), key, fingerprints) || startConfirmationService(componentName, userInfo.getUserHandle(), key, fingerprints)) {
            return;
        }
        Slog.e(TAG, "unable to start customAdbPublicKeyConfirmation[SecondaryUser]Component " + componentString + " as an Activity or a Service");
    }

    private boolean startConfirmationActivity(ComponentName componentName, UserHandle userHandle, String key, String fingerprints) {
        PackageManager packageManager = this.mContext.getPackageManager();
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        intent.addFlags(268435456);
        if (packageManager.resolveActivity(intent, 65536) != null) {
            try {
                this.mContext.startActivityAsUser(intent, userHandle);
                return true;
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "unable to start adb whitelist activity: " + componentName, e);
                return false;
            }
        }
        return false;
    }

    private boolean startConfirmationService(ComponentName componentName, UserHandle userHandle, String key, String fingerprints) {
        Intent intent = createConfirmationIntent(componentName, key, fingerprints);
        try {
            if (this.mContext.startServiceAsUser(intent, userHandle) != null) {
                return true;
            }
            return false;
        } catch (SecurityException e) {
            Slog.e(TAG, "unable to start adb whitelist service: " + componentName, e);
            return false;
        }
    }

    private Intent createConfirmationIntent(ComponentName componentName, String key, String fingerprints) {
        Intent intent = new Intent();
        intent.setClassName(componentName.getPackageName(), componentName.getClassName());
        intent.putExtra("key", key);
        intent.putExtra("fingerprints", fingerprints);
        return intent;
    }

    private File getUserKeyFile() {
        File dataDir = Environment.getDataDirectory();
        File adbDir = new File(dataDir, ADB_DIRECTORY);
        if (!adbDir.exists()) {
            Slog.e(TAG, "ADB data directory does not exist");
            return null;
        }
        return new File(adbDir, ADB_KEYS_FILE);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeKey(String key) {
        try {
            File keyFile = getUserKeyFile();
            if (keyFile == null) {
                return;
            }
            if (!keyFile.exists()) {
                keyFile.createNewFile();
                FileUtils.setPermissions(keyFile.toString(), 416, -1, -1);
            }
            FileOutputStream fo = new FileOutputStream(keyFile, true);
            fo.write(key.getBytes());
            fo.write(10);
            fo.close();
        } catch (IOException ex) {
            Slog.e(TAG, "Error writing key:" + ex);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void deleteKeyFile() {
        File keyFile = getUserKeyFile();
        if (keyFile != null) {
            keyFile.delete();
        }
    }

    public void setAdbEnabled(boolean enabled) {
        this.mHandler.sendEmptyMessage(enabled ? 1 : 2);
    }

    public void allowUsbDebugging(boolean alwaysAllow, String publicKey) {
        Message msg = this.mHandler.obtainMessage(3);
        msg.arg1 = alwaysAllow ? 1 : 0;
        msg.obj = publicKey;
        this.mHandler.sendMessage(msg);
    }

    public void denyUsbDebugging() {
        this.mHandler.sendEmptyMessage(4);
    }

    public void clearUsbDebuggingKeys() {
        this.mHandler.sendEmptyMessage(6);
    }

    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);
        dump.write("connected_to_adb", 1133871366145L, this.mThread != null);
        DumpUtils.writeStringIfNotNull(dump, "last_key_received", 1138166333442L, this.mFingerprints);
        try {
            dump.write("user_keys", 1138166333443L, FileUtils.readTextFile(new File("/data/misc/adb/adb_keys"), 0, null));
        } catch (IOException e) {
            Slog.e(TAG, "Cannot read user keys", e);
        }
        try {
            dump.write("system_keys", 1138166333444L, FileUtils.readTextFile(new File("/adb_keys"), 0, null));
        } catch (IOException e2) {
            Slog.e(TAG, "Cannot read system keys", e2);
        }
        dump.end(token);
    }
}
