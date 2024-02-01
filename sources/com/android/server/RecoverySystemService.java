package com.android.server;

import android.content.Context;
import android.net.INetd;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IRecoverySystem;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.util.Slog;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public final class RecoverySystemService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String INIT_SERVICE_CLEAR_BCB = "init.svc.clear-bcb";
    private static final String INIT_SERVICE_SETUP_BCB = "init.svc.setup-bcb";
    private static final String INIT_SERVICE_UNCRYPT = "init.svc.uncrypt";
    private static final int SOCKET_CONNECTION_MAX_RETRY = 30;
    private static final String TAG = "RecoverySystemService";
    private static final String UNCRYPT_SOCKET = "uncrypt";
    private static final Object sRequestLock = new Object();
    private Context mContext;

    public RecoverySystemService(Context context) {
        super(context);
        this.mContext = context;
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("recovery", new BinderService());
    }

    /* loaded from: classes.dex */
    private final class BinderService extends IRecoverySystem.Stub {
        private BinderService() {
        }

        /* JADX WARN: Code restructure failed: missing block: B:40:0x00c7, code lost:
            android.util.Slog.e(com.android.server.RecoverySystemService.TAG, "uncrypt failed with status: " + r8);
            r6.writeInt(0);
         */
        /* JADX WARN: Code restructure failed: missing block: B:41:0x00e2, code lost:
            libcore.io.IoUtils.closeQuietly(r6);
            libcore.io.IoUtils.closeQuietly(r6);
            libcore.io.IoUtils.closeQuietly(r3);
         */
        /* JADX WARN: Code restructure failed: missing block: B:43:0x00ec, code lost:
            return false;
         */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public boolean uncrypt(java.lang.String r14, android.os.IRecoverySystemProgressListener r15) {
            /*
                Method dump skipped, instructions count: 316
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.RecoverySystemService.BinderService.uncrypt(java.lang.String, android.os.IRecoverySystemProgressListener):boolean");
        }

        public boolean clearBcb() {
            boolean z;
            synchronized (RecoverySystemService.sRequestLock) {
                z = setupOrClearBcb(false, null);
            }
            return z;
        }

        public boolean setupBcb(String command) {
            boolean z;
            synchronized (RecoverySystemService.sRequestLock) {
                z = setupOrClearBcb(true, command);
            }
            return z;
        }

        public void rebootRecoveryWithCommand(String command) {
            synchronized (RecoverySystemService.sRequestLock) {
                if (setupOrClearBcb(true, command)) {
                    PowerManager pm = (PowerManager) RecoverySystemService.this.mContext.getSystemService("power");
                    pm.reboot("recovery");
                }
            }
        }

        private boolean checkAndWaitForUncryptService() {
            int retry = 0;
            while (true) {
                boolean busy = false;
                if (retry >= 30) {
                    return false;
                }
                String uncryptService = SystemProperties.get(RecoverySystemService.INIT_SERVICE_UNCRYPT);
                String setupBcbService = SystemProperties.get(RecoverySystemService.INIT_SERVICE_SETUP_BCB);
                String clearBcbService = SystemProperties.get(RecoverySystemService.INIT_SERVICE_CLEAR_BCB);
                if (INetd.IF_FLAG_RUNNING.equals(uncryptService) || INetd.IF_FLAG_RUNNING.equals(setupBcbService) || INetd.IF_FLAG_RUNNING.equals(clearBcbService)) {
                    busy = true;
                }
                if (!busy) {
                    return true;
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Slog.w(RecoverySystemService.TAG, "Interrupted:", e);
                }
                retry++;
            }
        }

        private LocalSocket connectService() {
            LocalSocket socket = new LocalSocket();
            boolean done = false;
            int retry = 0;
            while (true) {
                if (retry >= 30) {
                    break;
                }
                try {
                    socket.connect(new LocalSocketAddress(RecoverySystemService.UNCRYPT_SOCKET, LocalSocketAddress.Namespace.RESERVED));
                    done = true;
                    break;
                } catch (IOException e) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e2) {
                        Slog.w(RecoverySystemService.TAG, "Interrupted:", e2);
                    }
                    retry++;
                }
            }
            if (!done) {
                Slog.e(RecoverySystemService.TAG, "Timed out connecting to uncrypt socket");
                return null;
            }
            return socket;
        }

        private boolean setupOrClearBcb(boolean isSetup, String command) {
            RecoverySystemService.this.mContext.enforceCallingOrSelfPermission("android.permission.RECOVERY", null);
            boolean available = checkAndWaitForUncryptService();
            if (!available) {
                Slog.e(RecoverySystemService.TAG, "uncrypt service is unavailable.");
                return false;
            }
            if (isSetup) {
                SystemProperties.set("ctl.start", "setup-bcb");
            } else {
                SystemProperties.set("ctl.start", "clear-bcb");
            }
            LocalSocket socket = connectService();
            if (socket == null) {
                Slog.e(RecoverySystemService.TAG, "Failed to connect to uncrypt socket");
                return false;
            }
            DataInputStream dis = null;
            DataOutputStream dos = null;
            try {
                dis = new DataInputStream(socket.getInputStream());
                dos = new DataOutputStream(socket.getOutputStream());
                if (isSetup) {
                    byte[] cmdUtf8 = command.getBytes("UTF-8");
                    dos.writeInt(cmdUtf8.length);
                    dos.write(cmdUtf8, 0, cmdUtf8.length);
                    dos.flush();
                }
                int status = dis.readInt();
                dos.writeInt(0);
                if (status != 100) {
                    Slog.e(RecoverySystemService.TAG, "uncrypt failed with status: " + status);
                    return false;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("uncrypt ");
                sb.append(isSetup ? "setup" : "clear");
                sb.append(" bcb successfully finished.");
                Slog.i(RecoverySystemService.TAG, sb.toString());
                IoUtils.closeQuietly(dis);
                IoUtils.closeQuietly(dos);
                IoUtils.closeQuietly(socket);
                return true;
            } catch (IOException e) {
                Slog.e(RecoverySystemService.TAG, "IOException when communicating with uncrypt:", e);
                return false;
            } finally {
                IoUtils.closeQuietly(dis);
                IoUtils.closeQuietly(dos);
                IoUtils.closeQuietly(socket);
            }
        }
    }
}
