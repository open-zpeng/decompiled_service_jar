package com.android.server.om;

import android.os.IBinder;
import android.os.IIdmap2;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;
import com.android.server.FgThread;
import com.android.server.job.controllers.JobStatus;
import com.android.server.om.IdmapDaemon;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class IdmapDaemon {
    private static final String IDMAP_DAEMON = "idmap2d";
    private static final Object IDMAP_TOKEN = new Object();
    private static final int SERVICE_CONNECT_TIMEOUT_MS = 5000;
    private static final int SERVICE_TIMEOUT_MS = 10000;
    private static IdmapDaemon sInstance;
    private final AtomicInteger mOpenedCount = new AtomicInteger();
    private volatile IIdmap2 mService;

    IdmapDaemon() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class Connection implements AutoCloseable {
        private boolean mOpened;

        private Connection() {
            this.mOpened = true;
            synchronized (IdmapDaemon.IDMAP_TOKEN) {
                IdmapDaemon.this.mOpenedCount.incrementAndGet();
            }
        }

        @Override // java.lang.AutoCloseable
        public void close() {
            synchronized (IdmapDaemon.IDMAP_TOKEN) {
                if (this.mOpened) {
                    this.mOpened = false;
                    if (IdmapDaemon.this.mOpenedCount.decrementAndGet() != 0) {
                        return;
                    }
                    FgThread.getHandler().postDelayed(new Runnable() { // from class: com.android.server.om.-$$Lambda$IdmapDaemon$Connection$4U-n0RSv1BPv15mvu8B8zXARcpk
                        @Override // java.lang.Runnable
                        public final void run() {
                            IdmapDaemon.Connection.this.lambda$close$0$IdmapDaemon$Connection();
                        }
                    }, IdmapDaemon.IDMAP_TOKEN, JobStatus.DEFAULT_TRIGGER_UPDATE_DELAY);
                }
            }
        }

        public /* synthetic */ void lambda$close$0$IdmapDaemon$Connection() {
            synchronized (IdmapDaemon.IDMAP_TOKEN) {
                if (IdmapDaemon.this.mService != null && IdmapDaemon.this.mOpenedCount.get() == 0) {
                    IdmapDaemon.stopIdmapService();
                    IdmapDaemon.this.mService = null;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static IdmapDaemon getInstance() {
        if (sInstance == null) {
            sInstance = new IdmapDaemon();
        }
        return sInstance;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String createIdmap(String targetPath, String overlayPath, int policies, boolean enforce, int userId) throws Exception {
        Connection connection = connect();
        try {
            String createIdmap = this.mService.createIdmap(targetPath, overlayPath, policies, enforce, userId);
            if (connection != null) {
                $closeResource(null, connection);
            }
            return createIdmap;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (connection != null) {
                    $closeResource(th, connection);
                }
                throw th2;
            }
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeIdmap(String overlayPath, int userId) throws Exception {
        Connection connection = connect();
        try {
            boolean removeIdmap = this.mService.removeIdmap(overlayPath, userId);
            if (connection != null) {
                $closeResource(null, connection);
            }
            return removeIdmap;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (connection != null) {
                    $closeResource(th, connection);
                }
                throw th2;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean verifyIdmap(String overlayPath, int policies, boolean enforce, int userId) throws Exception {
        Connection connection = connect();
        try {
            boolean verifyIdmap = this.mService.verifyIdmap(overlayPath, policies, enforce, userId);
            if (connection != null) {
                $closeResource(null, connection);
            }
            return verifyIdmap;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (connection != null) {
                    $closeResource(th, connection);
                }
                throw th2;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getIdmapPath(String overlayPath, int userId) throws Exception {
        Connection connection = connect();
        try {
            String idmapPath = this.mService.getIdmapPath(overlayPath, userId);
            if (connection != null) {
                $closeResource(null, connection);
            }
            return idmapPath;
        } catch (Throwable th) {
            try {
                throw th;
            } catch (Throwable th2) {
                if (connection != null) {
                    $closeResource(th, connection);
                }
                throw th2;
            }
        }
    }

    private static void startIdmapService() {
        SystemProperties.set("ctl.start", IDMAP_DAEMON);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void stopIdmapService() {
        SystemProperties.set("ctl.stop", IDMAP_DAEMON);
    }

    private Connection connect() throws Exception {
        synchronized (IDMAP_TOKEN) {
            FgThread.getHandler().removeCallbacksAndMessages(IDMAP_TOKEN);
            if (this.mService != null) {
                return new Connection();
            }
            startIdmapService();
            FutureTask<IBinder> bindIdmap = new FutureTask<>(new Callable() { // from class: com.android.server.om.-$$Lambda$IdmapDaemon$u_1qfM2VGzol3UUX0R4mwNZs9gY
                @Override // java.util.concurrent.Callable
                public final Object call() {
                    return IdmapDaemon.lambda$connect$0();
                }
            });
            try {
                FgThread.getHandler().postAtFrontOfQueue(bindIdmap);
                IBinder binder = bindIdmap.get(5000L, TimeUnit.MILLISECONDS);
                try {
                    binder.linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.om.-$$Lambda$IdmapDaemon$hZvlb8B5bMAnD3h9mHLjOQXKSTI
                        @Override // android.os.IBinder.DeathRecipient
                        public final void binderDied() {
                            Slog.w("OverlayManager", "service 'idmap' died");
                        }
                    }, 0);
                    this.mService = IIdmap2.Stub.asInterface(binder);
                    return new Connection();
                } catch (RemoteException rethrow) {
                    Slog.e("OverlayManager", "service 'idmap' failed to be bound");
                    throw rethrow;
                }
            } catch (Exception rethrow2) {
                Slog.e("OverlayManager", "service 'idmap' not found;");
                throw rethrow2;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ IBinder lambda$connect$0() throws Exception {
        IBinder binder;
        while (true) {
            try {
                binder = ServiceManager.getService("idmap");
            } catch (Exception e) {
                Slog.e("OverlayManager", "service 'idmap' not retrieved; " + e.getMessage());
            }
            if (binder != null) {
                return binder;
            }
            Thread.sleep(100L);
        }
    }
}
